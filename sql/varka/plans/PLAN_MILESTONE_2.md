# Varka Milestone 2 Implementation Plan: generate the vector loop, not a call to it

This is the plan for the project's second milestone. Milestone 1 (the MVP,
tasks 1-8) is in `PLAN_MILESTONE_1.md`; `VISION.md` remains the architectural
source of truth. Per-task detail will live in `PLAN_TASK_9.md` onward, each
committed with the task it describes.

The work depends on the columnar-write split (`VarkaProjectExec`,
`VarkaKernelEvaluator`) landing first: several files this plan changes exist
only on that branch, so milestone 2 branches from master after it merges.

## 1. Why

Milestone 1 shipped a working vertical slice: three hand-written SIMD kernels in
`sql/varka/engine`, a Class-File-assembled dispatcher per op, a per-task class
loader, and `VarkaColumnarRule` fusing an eligible projection into
`VarkaColumnarToRowExec` - and, since the columnar-write work, into a
columnar-out `VarkaProjectExec`. It is measurably faster: the kernels run 2.2x
to 3.0x their scalar counterparts in JMH, and end to end into a columnar sink
the fused path is 2.5x to 3.7x the Janino baseline.

What it does not do is generate compute. `VarkaClassFileGen.assembleKernelClass`
emits a class whose `run` pushes its parameters and does one `invokestatic` into
`DateVectorOps`: a dispatcher, not a loop. Three consequences, all measured:

* **Nesting is not eligible at all.** `datediff(date_add(d, 1), d2)` plans as a
  plain `*(1) Project` and runs per row in Janino. `DateDiff.isClassFileGenEligible`
  requires both children to be bare attributes
  (`datetimeExpressions.scala:3593`), and `eligibleOps` never recurses
  (`ClassFileCodegenSupport.scala:70-75`).
* **No fusion between ops.** Each output op gets its own full pass over the
  batch into its own freshly allocated Arrow vector, so a chain round-trips
  through memory. At 1M rows the kernels already run at roughly 34 GB/s, which
  is bandwidth-bound: a fused chain removes a write and a read per intermediate.
* **All-or-nothing eligibility.** One ineligible expression sends the whole
  projection to Janino, so `SELECT date_add(d, 3), i, i + 1` gets nothing. That
  is the flat "mixed projection" row in the committed throughput results.

A fourth observation shapes the scope. The MVP's three ops alone cannot
*demonstrate* fusion: `datediff` produces an int and no date op consumes one, so
every chain they can form is either nested `date_add`/`date_sub` - which int
wrap-around makes associative, so a one-line Catalyst folding rule collapses it
to a single op - or `datediff` over such a chain, depth two and equally
reducible. An emitter proven only on reducible chains proves little. Milestone 2
therefore also covers the date expressions that form *irreducible* chains and
showcase what fusion uniquely buys:

* **`CASE WHEN` / `IF` over dates, via `VectorMask.blend`** - the comparison
  lives as a mask in a register, both branches compute under it, and `blend`
  selects: no boolean column materialised, no branch, no misprediction. This is
  the "If-Conversion via Predication" pattern from the original design notes.
* **Comparisons (`<`, `<=`, `=`, ...) over dates, as interior nodes only** -
  they feed the blend condition and are never projection outputs, which keeps
  bit-packed boolean output columns out of scope.
* **`greatest` / `least` over dates** - one lane instruction each
  (`IntVector.max`/`min`), and `greatest(date_add(d1, 7), d2)` is an
  irreducible chain.
* **`dayofweek` / `weekday`** - semantically `floorMod(days + 4, 7) + 1`, two
  lane ops, while Spark computes it through `LocalDate.ofEpochDay`
  (`DateTimeUtils.scala:226`), an object allocation per row. The SIMD version
  does not just fuse; it replaces an allocating path.

The hand-written kernels stay as the reference semantics and the per-batch
fallback. Scope remains int32 in and int32 out. Everything deliberately deferred
- int64 lanes (timestamps) first, then caching, filters, boolean outputs,
calendar field extraction and the rest - is collected with rationale and
ordering in `PLAN_MILESTONE_3.md`, so a deferral here always has a landing place
there. The 64 KB method limit is addressed by neither milestone: nothing here
generates the whole-stage class.

## 2. Design

### 2.1 A vector IR, in Java, built from Catalyst

`sealed interface VarkaVectorIR` with records `ColumnRef(int ordinal)`,
`LiteralSlot(int index)`, `AddDays(node, node)`, `SubDays(node, node)`,
`DateDiff(node, node)`, `Compare(op, node, node)` (interior only - it produces a
mask, never a stored column), `And(cond, cond)`, `Or(cond, cond)`, `Not(cond)`
(interior, mask-valued - what makes `CASE WHEN` conditions realistic, and what
`BETWEEN` rewrites to), `IfElse(cond, thenNode, elseNode)`,
`Greatest(node, node)`, `Least(node, node)`, `DayOfWeek(node)`,
`WeekDay(node)`. Java, so the emitter can pattern-match records; constructed
from bound Catalyst expressions by a Scala `VarkaExpressionCompiler`, which
replaces the flat `VarkaKernelEvaluator.outputOp` matcher and recurses instead
of demanding bare attributes.

The IR is a DAG, not a forest: the compiler interns identical subtrees, so a
subchain shared by several outputs - `SELECT date_add(d, 1) AS a,
datediff(date_add(d, 1), d2) AS b` - is computed once per lane group and stored
twice. That is the register-residency claim extended *across* outputs, which
neither the per-op kernels nor Janino's per-row subexpression elimination can
match inside a vector loop. Every node also carries its lane type - always
`INT` in this milestone, and the emitter rejects anything else. One field, no
speculative code paths; it exists so milestone 3's int64 lanes extend the IR
instead of reworking it.

The new expressions (`CaseWhen`, `If`, the binary comparisons, `Greatest`,
`Least`, `DayOfWeek`, `WeekDay`) are matched *structurally* by the compiler;
they do not gain the `ClassFileCodegenSupport` trait. That keeps the milestone
consistent with the decision in section 4: the trait and its genCode-time
registration feed the Janino compile-cache key and stay untouched.

Literal values never enter the IR. Folding a literal (via
`DateVarkaSupport.foldDaysOffset`, so eligibility cannot drift from what the
rule matched on) assigns it a slot in a per-chain argument table, and the
generated code reads it from the `scalarArgs` parameter at run time - exactly as
`daysOffset` is a runtime argument of today's dispatchers, and for the same
reason: one generated class serves every literal a query might use, and a
chain's identity is its shape, not its constants. That identity is also what
milestone 3's cache will key on, so this is decided now even though the cache is
not built now.

### 2.2 An emitter that writes the loop

`VarkaLoopEmitter` (Java, Class-File API) turns a list of output IR nodes plus
the argument table into one class implementing a single new shape:

```java
public interface VarkaFusedKernel {
  void run(long[] srcData, long[] srcValidity, int[] srcNullCount,
           long[] dstData, long[] dstValidity, int[] scalarArgs, int length);
}
```

The arrays are primitive and are unpacked into locals at method entry, never
indexed inside the loop (loop-invariant code motion, as the design notes
require); the caller reuses the arrays across batches, so nothing is allocated
per call. Per lane group the generated body builds each referenced column's
validity mask once; loads each referenced column once with
`IntVector.fromMemorySegment(SPECIES, seg, off, ORDER, mask)`; computes each
distinct IR node once, reusing shared subchains across outputs, with
intermediates - `IntVector`s and `VectorMask`s both - on the operand stack or
in locals; stores once per output column; and writes each output's validity. A scalar tail then mirrors the chain row for row, including
the predication and null-skipping semantics of 2.6.

Mask algebra is per node, not global: the arithmetic ops are null-intolerant and
AND their inputs' masks; `greatest`/`least` skip nulls and OR them (2.6);
`IfElse` blends validity by the effective condition (2.6). The old
"AND everything" rule was only ever right when every op was null-intolerant, and
with predication in scope it no longer is.

Two emitter invariants, stated because getting them wrong is silent: every
output's validity buffer is zeroed before the loop, unconditionally - the
all-null shortcut is per *output*, not per kernel, and a skipped output must
still read as all-null; and `IntVector.SPECIES_PREFERRED` is read with
`getstatic` so it stays a JIT constant, which is what lets C2 intrinsify the
calls.

### 2.3 Runtime support stays in the engine

Promote `DateVectorOps`' package-private helpers into a public
`VarkaVectorSupport` in the same module: `validityBitsAt`, `orValidityBitsAt`,
`laneMask`, `groupBytes`, plus `zero` (the pre-loop validity clear) and
`isBitSet` / `setBit` (the scalar tail's counterparts). Generated code calls
them by name, the way it already calls `DateVectorOps`; `DateVectorOps` keeps
using them. Static calls on a final class are monomorphic and inline; emitting
that bit math inline would triple the generated bytecode for no gain.

### 2.4 The obvious alternative, and why it is a trap

Generating a lane function (`IntVector apply(IntVector[], VectorMask)`) called
from a hand-written generic loop looks simpler. Per-task loaders make every
generated class a distinct receiver type, so the shared call site in the
template goes megamorphic across queries, C2 stops inlining, and the vectors
that were supposed to stay in registers get boxed. The loop has to be generated
- or hot-patched per query, which is the milestone-3 version of the same idea.

### 2.5 Partial eligibility (the scalar escape hatch)

A projection becomes eligible when *any* entry is, rather than when all are. The
output batch is assembled column by column: kernel-served columns from the fused
loop, plain `AttributeReference`s forwarded as the input's own `ColumnVector`
(zero copy), and anything else evaluated per row into a writable vector with the
machinery the columnar-write change already added (`RowToColumnConverter`,
`OnHeapColumnVector` / `OffHeapColumnVector`).

Batch ownership needs an explicit design here, because
`ColumnarBatch.close()` closes every column unconditionally: an output batch
holding forwarded input vectors must not close them - that would free the input
producer's buffers out from under it. The output batch therefore closes only the
vectors it owns (kernel outputs and fallback vectors), with forwarded columns
either wrapped in a non-owning view or tracked on an owned-columns list. This
gets its own lifetime test, on both the drained and the abandoned-iterator
paths.

For the row-output node the escape hatch is a measured decision, not a default.
Today a mixed projection under `VarkaColumnarToRowExec` is one per-row pass;
naively applying the batch assembly gives the residual columns per-row
evaluation *into a vector* and then a read back out to rows - an extra
materialisation for exactly the columns that gained nothing. Task 12 therefore
benchmarks "mixed projection, row consumer" and either evaluates residual
expressions directly during the row conversion or keeps the batch assembly,
whichever measures better. The columnar node has no such choice; it must
materialise.

### 2.6 Predication and null semantics

This is where the milestone's correctness risk concentrates, so the rules are
written down rather than discovered in review.

* **Comparisons are null-intolerant.** A comparison over dates produces, per
  lane group, `cmpMask AND validLeft AND validRight` - the *effective*
  condition. A null comparison result never selects the THEN branch, which is
  SQL's rule: a null condition falls through to ELSE.
* **`IfElse` blends values and validity by the effective condition.** Value:
  `elseVec.blend(thenVec, effCond)`. Validity: the *chosen* branch's validity,
  lane-wise - `elseValid.blend(thenValid, effCond)` in mask terms. Nothing is
  ANDed globally: a null in the branch not taken must not null the result.
* **`greatest`/`least` skip nulls, they do not propagate them.** Spark's
  semantics: null only when *all* inputs are null. Lane-wise:
  `valid = validA OR validB`; value `max(select(a, validA, b),
  select(b, validB, a))` - substituting the other lane's value where one input
  is null reduces every case (both valid, only A, only B) to a plain `max`.
* **The connectives follow SQL's three-valued logic, tracked as mask pairs.**
  Each condition node carries a known-true and a known-false mask:
  `knownTrue(A AND B) = knownTrue(A) AND knownTrue(B)`, dually for `OR`, and
  `knownTrue(NOT A) = knownFalse(A)` - which is why known-false must be tracked
  at all. The effective condition feeding a blend is the known-true mask, which
  preserves the rule that an unknown condition falls through to ELSE.
* **Plain integer arithmetic on `datediff` outputs is a trap, and stays out.**
  `datediff(d2, d1) + 1` looks like a free chain extension on the same lanes,
  but Spark's `Add` on integers throws on overflow under ANSI mode - the
  default - and a SIMD lane cannot throw row-accurately without overflow
  detection and a row index for the error. If it is ever added it must be gated
  on `ansiEnabled = false`; the ANSI-correct version, with vectorised overflow
  detection, is milestone 3's problem. `date_add` itself is exempt: it wraps by
  spec regardless of ANSI.
* **`dayofweek`/`weekday` are `floorMod`, not `%`.** Java's `%` is wrong for
  negative epoch days (dates before 1970), so the emitted sequence is the
  two-instruction floorMod, and the differential test range must include
  negative days - that is exactly where a naive port breaks.
* **The scalar tail mirrors all of it row for row.** The tail is generated from
  the same IR, and the null-pattern matrix (nulls in the condition, in either
  branch, in both `greatest` inputs, all-null columns, empty batches) runs
  against both the vector loop and the tail lengths that force tail-only
  execution.

The oracle for every rule above is Janino: `VarkaDifferentialSuite` compares the
fused path against Spark's row engine, and for `dayofweek`/`weekday`
specifically against the `LocalDate`-based `DateTimeUtils` results across a wide
day range, negative days included.

### 2.7 Caching is milestone 3

Milestone 2 keeps the MVP's model: assembled per task, ~13 us per class (the
Gen-time benchmark's number), unloaded with the task. A cross-task cache of
assembled bytes - keyed on the chain signature that 2.1's literal slots make
well-defined - moves to milestone 3, for two reasons. It is pure amortisation
with no correctness content, so it dilutes a milestone whose risk is the
emitter. And it collides with this milestone's telemetry: 2.8 bakes the operator
and stage into the class bytes, which byte-level caching would either defeat
(stage in the key) or falsify (a cached class reporting another query's stage).
Milestone 3 has to reconcile the two - most likely by patching or externalising
the debug attributes on cache hits - and that reconciliation is part of the
cache's design, not something to bolt on here (`PLAN_MILESTONE_3.md`, item 2).

### 2.8 Telemetry

`SourceFileAttribute` named for the operator and stage
(`Varka_Project_Stage3.java`) so a stack trace names the plan node, plus a
`VarkaDebugInfo` custom attribute (an `AttributeMapper` implementation) carrying
the IR and the plan fragment, read back with `ClassFile.parse` by a small
diagnostics helper. The JIT ignores it, so it costs nothing at runtime.

## 3. Task breakdown

Numbering continues from milestone 1, whose last task was 8.

| # | Task | Deliverable | Validation |
| :--- | :--- | :--- | :--- |
| 9 | **IR + emitter spike** | `VarkaVectorIR`, `VarkaLoopEmitter`, `VarkaFusedKernel`, `VarkaVectorSupport`; single op, single output. **DONE** (`PLAN_TASK_9.md`): both gates passed at both widths, cap fixed at 16 by measurement | Generated class verifies and matches `DateVectorOps` row for row; the intrinsification gate below; the chain-depth cap measured and fixed as a number |
| 10 | Chains, DAG-CSE and mask algebra | Recursive `VarkaExpressionCompiler`; nested arithmetic ops; interned subtrees computed once per lane group; per-node mask algebra; scalar tail mirrors the chain; dense-path specialisation (an unmasked body selected when `nullCount == 0`) if task 9's JMH delta justifies it | Differential vs Janino over a nested-expression matrix including shared subchains; null, all-null and empty batches; the fusion gate below |
| 11 | Predication and null-skipping ops | Interior comparisons and `AND`/`OR`/`NOT` with the known-true/known-false mask pairs; `If`/`CaseWhen` via `VectorMask.blend`; `greatest`/`least`; `dayofweek`/`weekday` via floorMod | The 2.6 null-pattern matrix - three-valued connectives included - on both the vector loop and the scalar tail; `dayofweek` differential vs the `LocalDate` oracle including negative epoch days |
| 12 | Multi-output, passthrough, escape hatch | Partial eligibility in `VarkaColumnarRule` and both exec nodes; owned-columns batch release | Plan tests on both sides of the fork; the mixed projection is fused; lifetime tests for forwarded vectors (drained and abandoned); the row-node escape-hatch decision, benchmarked |
| 13 | Telemetry | `SourceFileAttribute` and the `VarkaDebugInfo` attribute plus its reader | Round-trip test: parse the attribute back off a generated class |
| 14 | Benchmarks and docs | JMH fused-chain case; throughput cases for nested, shared-subchain, `CASE WHEN` and mixed projections; a chain-depth scaling case (depth 1-4, fused vs per-op passes); a cold-query latency case (first execution of a fresh plan shape, Varka vs Janino); regenerate both result files; update `docs/sql-varka.md`, `VISION.md` and this file | Committed results show the chain speedup and its scaling with depth; the `CASE WHEN` case is the headline fusion number - branch-free blend against Janino's per-row branches; the cold-latency case turns the 636x generation-time figure into a query-level number |
| 15 | Drive-by | `fallbackProjection` becomes a `lazy val` in both Varka evaluators | A kernel-only task compiles no Janino projection |

Task 9 carries the milestone's real risk, so it ships first and alone. Task 11
carries the milestone's *correctness* risk - the 2.6 semantics - which is why it
is its own task and not a bullet inside task 10.

## 4. Files

* **New (catalyst, Java):** `expressions/codegen/varka/VarkaVectorIR.java`,
  `VarkaLoopEmitter.java`, `VarkaFusedKernel.java`, `VarkaDebugInfo.java`.
* **New (catalyst, Scala):** `expressions/codegen/VarkaExpressionCompiler.scala`
  (matches `DateAdd`/`DateSub`/`DateDiff` and, structurally, `CaseWhen`, `If`,
  the binary comparisons, `Greatest`, `Least`, `DayOfWeek`, `WeekDay`).
* **New (engine):** `vector/VarkaVectorSupport.java`, holding the helpers
  promoted from `DateVectorOps`, which keeps using them.
* **Changed:** `ClassFileCodegenSupport.scala` (`kernelInterface` gains the
  fused shape); `VarkaKernelEvaluator.scala` (drives the fused kernel;
  `OutputOp` gives way to the IR); `VarkaProjectExec.scala` and
  `VarkaColumnarToRowExec.scala` (multi-source batch assembly);
  `VarkaColumnarRule.scala` (partial eligibility, via a *new* recursive
  eligibility check owned by the rule and the IR compiler).
* **Deliberately unchanged:** the expressions' `isClassFileGenEligible` and its
  genCode-time registration. Those feed the Janino compile-cache key
  (`CodeAndComment.classFileGenOps` is in `equals`/`hashCode`,
  `CodeGenerator.scala:1465-1472`, populated via
  `CodegenContext.isClassFileGenEligible` at `:163`), so widening them would
  change cache-key shape for every query containing these expressions, Varka on
  or off. Recursion and the new ops live in the new check instead, which is also
  why `CaseWhen` and the comparisons need no trait.

## 5. Verification

```
build/sbt "catalyst/testOnly *Varka* *ClassFileCodegen*" "sql/testOnly *Varka*"
build/mvn -f sql/varka/engine/pom.xml test
build/mvn -f sql/varka/engine/pom.xml test -Dvarka.jmh=true
SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
  "sql/Test/runMain org.apache.spark.sql.execution.benchmark.VarkaThroughputBenchmark"
build/sbt "sql/scalastyle" "sql/Test/scalastyle" && dev/lint-java
```

Two gates decide whether the milestone works at all:

* **Intrinsification gate (task 9).** The generated single-op loop must reach the
  hand-written `DateVectorOps` kernel's JMH throughput within noise - at the
  host's preferred width *and* under `-XX:MaxVectorSize=16`, the four-lane shape
  the engine's narrow-vector run already pins (milestone 1's finding 1 is the
  reason: the narrow shape is where bugs hide). If it lands 3x to 5x slower, C2
  did not intrinsify the emitted Vector API calls and the emitter is wrong, most
  likely because the species stopped being a JIT constant. Check with
  `-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics` before changing anything
  else.
* **Fusion gate (task 10).** A two-op chain over 1M rows must beat two separate
  kernel passes by clearly more than noise, since the point is one less write
  and read of an intermediate.

Correctness rests on differential testing against Janino, which already exists
(`VarkaDifferentialSuite`): extend its query matrix with nested projections,
mixed projections, and the 2.6 null-pattern matrix for predication and
null-skipping ops, rather than writing a parallel harness. Each task also runs
its own negative control - disable the emitter, then the partial-eligibility
rule, and confirm the new tests fail for the stated reason.

## 6. Risks

* Vector API descriptors and erasure are easy to get subtly wrong
  (`add(Vector, VectorMask)` erases to `Ljdk/incubator/vector/Vector;`). Wrong
  bytecode surfaces as a `VerifyError`, which the ghost fallback swallows, so
  tests must assert the kernel path *ran* rather than only that results were
  right. `numVarkaBatches` already exists for that.
* Predication null semantics are the milestone's bug surface: three different
  mask algebras (AND, OR-with-substitution, blend) coexist in one loop, and the
  scalar tail must agree with all of them row for row. Mitigated by task 11's
  dedicated matrix and by the Janino oracle, not by review alone.
* Register pressure on long chains, now with masks as well as vectors live.
  Date chains are shallow, but the emitter caps chain depth and falls back
  rather than spills; the cap is a concrete number measured in task 9, not a
  guess.

## 7. Open question, to settle in task 9

`JavaClassFileEngine` and `ClassFileAssembler` still assemble a `VarkaProjection`
shell whose `apply` throws, unrouted since the compile-funnel routing was
removed. Once the emitter exists, either it grows a real body (a row-path
generator, milestone 3) or the shell should go. Decide when task 9 lands, rather
than leaving a second half-built generator in the tree.

**Settled: deleted.** Task 9 recorded the recommendation (`PLAN_TASK_9.md`
section 5.4), the milestone owner agreed after task 10, and the shell went in a
follow-up PR. A milestone-3 row-path generator, if it happens, builds on the
emitter and the IR.
