# Task 10: chains, DAG-CSE and mask algebra

**Status: PLANNED.** See `PLAN_MILESTONE_2.md` (task row 10) for the milestone
context and `PLAN_TASK_9.md` for the emitter this task generalises. Sections
1-4 are the plan; a section 5 will record the outcome.

## 1. Why this task, and what it inherits

Task 9 proved the milestone's bet: a Class-File-API-emitted vector loop reaches
the hand-written kernels' speed at both vector widths, and a fused chain leaves
sequential kernel passes 45x behind at depth 16. But nothing reaches that
emitter yet. Task 10 connects it to real queries and removes the three task-9
restrictions that were never meant to survive it: one output, one input column,
`AddDays`/`SubDays` only.

After this task, a projection whose every entry is a nested date-arithmetic
chain - `date_add(date_add(d, 1), 2)`, `datediff(date_add(d1, 7), d2)`, several
such outputs sharing subchains - plans as a Varka node and runs as one emitted
loop: each referenced column loaded once per lane group, each distinct subchain
computed once (DAG-CSE), intermediates in vector registers, one masked store
per output. Eligibility stays all-or-nothing at the projection level; partial
eligibility and passthrough columns are task 12, predication ops are task 11.

What exists and is kept as-is: the `VarkaFusedKernel.run` shape (its array
parameters were sized for exactly this task), `VarkaGeneratedClassLoader`,
`DateVarkaSupport.foldDaysOffset` as the single literal-folding rule, and the
hand-written `DateVectorOps` kernels as the reference semantics and the
benchmark baseline. The expressions' `isClassFileGenEligible` and its
genCode-time registration stay untouched, per the milestone's section 4
decision: they feed the Janino compile-cache key, so recursion lives in a new
check owned by the rule and the compiler, never on the trait.

## 2. Deliverables

### 2.1 IR: `DateDiff` joins the sealed interface

`VarkaVectorIR` gains `DateDiff(VarkaVectorIR end, VarkaVectorIR start)` (a new
`permits` entry). Lane math is `end - start` on int lanes - the same `isub` the
binary kernel does - but the *Spark* output type is `IntegerType` where
`AddDays`/`SubDays` produce `DateType`. The IR does not model Spark types (lane
type stays `INT` everywhere); the compiler tracks the Spark type per output so
the evaluator allocates an `IntVector` instead of a `DateDayVector`.

Two shapes stay out deliberately, with the milestone's reasons: integer `Add`
over a `datediff` result (ANSI overflow semantics cannot throw row-accurately
from a lane; `PLAN_MILESTONE_2.md` 2.6) and everything predication-shaped
(task 11). Nesting `date_add` *over* a `datediff` result cannot type-check in
SQL without a cast, so it needs no special rejection - the compiler simply
finds no rule for the cast and the projection is ineligible.

### 2.2 Compiler: `VarkaExpressionCompiler` (catalyst, Scala)

New `expressions/codegen/VarkaExpressionCompiler.scala`. One entry point used
by both the rule and the evaluator, so eligibility cannot drift from execution
- the drift risk that `VarkaKernelEvaluator.outputOp`'s comment warns about
today is retired by construction:

```scala
case class CompiledVarkaProjection(
    outputs: Seq[VarkaVectorIR],       // one root per projectList entry, in order
    outputTypes: Seq[DataType],        // DateType or IntegerType, per root
    inputOrdinals: Seq[Int],           // child ordinals, dense kernel input index = position
    literals: Array[Int])              // scalarArgs values, slot index = position

object VarkaExpressionCompiler {
  def compile(projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute]): Option[CompiledVarkaProjection]
}
```

The compiler binds references itself (as the evaluator does today), strips
`Alias`, and recurses:

* `BoundReference` of `DateType` -> `ColumnRef` over a dense input index (the
  first referenced child ordinal becomes kernel input 0, and so on).
* `DateAdd(e, days)` / `DateSub(e, days)` where `e` recursively compiles and
  `DateVarkaSupport.foldDaysOffset(days)` is defined -> `AddDays`/`SubDays`
  with a `LiteralSlot`.
* `DateDiff(e1, e2)` where both children recursively compile -> `DateDiff`.
* Anything else -> the whole projection returns `None` (all-or-nothing until
  task 12). A *bare* date column as an output entry also returns `None` here:
  emitting it would be a copy loop, while task 12 forwards it zero-copy; making
  it eligible now would ship a regression to unship later.

Literal slots are assigned per distinct *value*, not per occurrence. This is
load-bearing for CSE: two occurrences of `date_add(d, 1)` must compile to
identical records or no interning can see they are the same computation. It
also keeps the chain's shape well-defined for milestone 3's cache: slots are
numbered in first-occurrence order, so `date_add(d, 5), date_sub(d, 7)` and
`date_add(d, 1), date_sub(d, 3)` share one shape.

The roots are returned as plain trees; common-subexpression detection is the
emitter's job (2.3), keyed on structural equality, which the records provide.
This deviates from the milestone's "the compiler interns" wording - same
effect, one owner, and it makes the emitter robust against any caller, not
only the compiler.

### 2.3 Emitter: multi-input, multi-output, DAG-CSE, per-node masks

`VarkaLoopEmitter.emit` keeps its signature; the task-9 rejections of
`outputs.size() != 1`, `numInputs != 1` and `ordinal != 0` are replaced by real
support. The emitted `run` keeps the six-step kernel shape, generalised:

* **Prologue.** Per input: data segment, and - unless that input's
  `srcNullCount[i] == length` (all-null) or `== 0` (null-free) - a validity
  segment. An all-null input's validity address is `0L` by the morsel contract,
  so the segment must not be materialised before this check. Per output: data
  segment, validity segment, `zero(dstValidity)` *unconditionally* - the
  milestone's emitter invariant: a skipped output must still read as all-null.
  An output any of whose referenced columns is all-null is *dead*: its store
  and validity writes are skipped (compile-time, per output - the referenced
  column set is static). If every output is dead, return after the zeroing.
  The task-9 early `return` on the single input generalises to exactly this.
* **Mask algebra, as long words first.** Per lane group, per *live referenced
  input*: one validity word from `validityBitsAt` (or `-1L` for a null-free
  input - decided per input now, not globally). Task 10's ops are all
  null-intolerant, so a node's mask is the AND of the validity words of the
  columns its subtree references - computed bottom-up over the DAG and
  memoized, `VectorMask.fromLong` applied once per *distinct referenced-column
  set*, not per node (a single-column chain therefore builds exactly one mask,
  as task 9 did). The bottom-up rule is structured per node so task 11's
  OR-with-substitution and blend algebras replace one case each rather than a
  global assumption.
* **DAG-CSE.** The emitter walks each output root with a memo table keyed on
  record equality. First computation of a shared node stores its `IntVector`
  in a local (`astore`); later uses are `aload`. Column loads are the same
  mechanism: `ColumnRef` is a node like any other, so each referenced column
  is loaded once per lane group no matter how many outputs read it. Single-use
  intermediates stay on the operand stack exactly as in task 9 - a local is
  paid for only where sharing exists.
* **Stores.** Per live output: masked `intoMemorySegment` with the root's
  mask, then `orValidityBitsAt` with that mask's `toLong()`.
* **Scalar tail.** Per row, per live output: the row is computed iff every
  referenced column's bit is set (the same AND, in boolean form), then
  `set`/`setBit`. Shared subtrees are simply recomputed per row - scalar
  recomputation costs less than the bookkeeping to avoid it, and the tail is
  at most one lane group long. Row-for-row agreement with the vector body is
  what the differential lengths prove.
* **Caps.** `MAX_CHAIN_DEPTH = 16` now means the longest root-to-leaf path,
  per output. A new `MAX_FUSED_NODES = 64` bounds the *total* distinct op
  nodes across all outputs - the method-size and register-pressure bound that
  depth alone no longer implies once outputs multiply. Both are policy numbers
  far past real projections; the parity benchmark's widest case (2.6) keeps
  them honest. Inputs are capped at 64 by the referenced-column-set
  representation (a long bitset); rejection, like every rejection here, is an
  `IllegalArgumentException` the evaluator treats as "fall back".

The descriptor table gains nothing: `DateDiff` is `sub` with the operands
swapped (`end - start`), and every other call is already declared. The
`misdescribeAddForTesting` hook and its test survive unchanged.

### 2.4 Wiring: the rule and the evaluator

* **`VarkaColumnarRule`**: `isFullyVarkaEligible` becomes
  `VarkaExpressionCompiler.compile(projectList, child.output).isDefined`. The
  trait-based `VarkaClassFileGen.eligibleOps` check retires from the rule (the
  trait itself stays, per section 1). Nested projections now plan as Varka
  nodes; everything else about the two-stage rewrite is untouched.
* **`VarkaKernelEvaluator`**: `OutputOp`, `outputOp`, `KernelKind` and
  `KernelRunners` give way to the compiled IR. Per task: compile once (`None`
  never happens given the rule, but stays the safe fallback), emit and load
  one `VarkaFusedKernel` class through a task-lifetime
  `VarkaGeneratedClassLoader` (released by the task-completion listener, as
  `KernelRunners` does today), and allocate the `run` argument arrays once -
  `long[numInputs]` x2, `int[numInputs]`, `long[numOutputs]` x2, and the
  literals array straight from the compiler - refilled per batch, never
  reallocated. Emission failures (`IllegalArgumentException` from the caps,
  any `LinkageError`) take the existing "assembly failed, fall back to the
  per-row projection" path; `isCatchable` already covers both.
* `canRun`'s Arrow check iterates the compiled `inputOrdinals` (every IR leaf
  is a date column, so the `DateDayVector`-with-exact-valueCount rule applies
  unchanged); `buildVector`'s type dispatch reads `outputTypes`. The morsel
  extraction and batch-lifetime machinery do not change.
* `VarkaClassFileGen.assembleKernelClass` and the unary/binary dispatcher
  interfaces lose their production caller but stay: the emitter suite and the
  parity benchmark drive the hand-written kernels through them, and they are
  milestone 1's documented artifact. Whether they eventually fold into the
  `VarkaProjection`-shell cleanup (`PLAN_TASK_9.md` 5.4) is decided there, not
  here.

Both exec nodes are untouched: they already talk to the evaluator through
`canRun`/`project`/`release` only.

### 2.5 The dense-path decision (measured, in-plan)

The milestone left "an unmasked body selected when `nullCount == 0`" open,
conditional on the data. Task 9's data says no: the emitted *masked* loop with
a `-1L` mask already beats the hand-written kernel on null-free input at both
widths (1.13x / 1.07x), so a masked lane op with an all-true mask costs
nothing C2 cannot eliminate. The remaining question - masked-all-true vs a
genuinely unmasked body - gets one throwaway A/B during development (an
emitter test hook, three unmasked descriptor entries, one benchmark run, not
committed). The unmasked body ships only if it wins by more than 10% on the
null-free parity case; the numbers and the decision go in section 5 either
way. Expectation: it does not win, and the specialisation is *recorded* as
rejected so milestone 3 does not re-litigate it.

## 3. Verification

### 3.1 Emitter suite (`VarkaLoopEmitterSuite`, extended)

* Differential, multi-input: `DateDiff(col0, col1)` vs
  `DateVectorOps.vectorDateDiff` over the task-9 length matrix, with the null
  patterns applied *independently per column* (null-free x mixed, mixed x
  all-null, ...) - the per-input `hasNulls`/dead logic is new and this is its
  matrix. Sentinel data and pre-set destination validity, bit-for-bit, as
  before.
* Differential, DAG: two outputs sharing a subchain
  (`a = f(d), b = g(f(d), d2)`) vs the same results from sequential
  hand-written kernel passes; and a mixed-type pair (`date_add` output next to
  a `datediff` output) proving per-output types and masks stay independent.
* All-dead and part-dead: one input all-null kills only the outputs that
  reference it; their validity reads all-zero while sibling outputs are
  served; the all-dead case returns after zeroing.
* CSE observability: with the memo disabled through a test hook, the emitted
  bytes differ (duplicate subchain code) but results agree - pinning that CSE
  is an optimisation, never a semantics change.
* Rejections: node count over `MAX_FUSED_NODES`, ordinal outside `numInputs`,
  and the surviving task-9 rejections, each named.
* Everything runs green at the preferred width and under
  `-XX:MaxVectorSize=16`, as in task 9.

### 3.2 End-to-end (`VarkaDifferentialSuite`, extended)

The suite's existing harness (`checkDifferential`, `assertFused`,
`assertKernelsRan` via `numVarkaBatches`) takes new queries:

* `date_add(date_add(d, 1), 2)` and deeper nests - fused, matching the row
  engine (these plan as plain `Project` today, so `expectFused = true` is
  itself the new behavior).
* `datediff(date_add(d1, 7), d2)` both argument orders, with nulls in either
  column.
* The shared-subchain projection from the milestone plan:
  `SELECT date_add(d, 1) AS a, datediff(date_add(d, 1), d2) AS b`.
* A projection with one ineligible entry (`d + 1` over an int column, a bare
  date column) stays unfused - all-or-nothing is still the rule until task 12,
  and the test names task 12 so its flip is deliberate.
* Extreme-offset wrap-around repeated through a nested chain (the existing
  int32-wrap oracle pattern).
* Negative control: `isFailKernelForTesting` still forces the fallback on the
  new plans, results still correct, `numVarkaBatches` stays 0.

### 3.3 The fusion gate and the benchmark

`VarkaEmitterParityBenchmark` gains: a `datediff` parity case (emitted vs
`vectorDateDiff`, null-free and mixed); a two-output shared-subchain case at
depth 8 - fused-with-CSE vs fused-with-the-memo-disabled vs sequential kernel
passes, which prices DAG-CSE itself, not just fusion; and the widest-shape
case (`MAX_FUSED_NODES` reached) proving no cliff at the cap. The committed
results file is regenerated at the preferred width; the four-lane numbers go
in this file's section 5.

The milestone's fusion gate - a two-op chain over 1M rows clearly beating two
kernel passes - was already cleared in task 9 (3.5x at depth 2); it is
re-checked here from the regenerated file, now with the compiler in the loop.

### 3.4 Commands

```
build/sbt "catalyst/testOnly *VarkaLoopEmitterSuite *ClassFileCodegenSupportSuite \
  *VarkaGeneratedClassLoaderSuite"
build/sbt "project catalyst" 'set Test/javaOptions += "-XX:MaxVectorSize=16"' \
  "testOnly *VarkaLoopEmitterSuite"
build/sbt "sql/testOnly *Varka*"
build/mvn -f sql/varka/engine/pom.xml test          # untouched, must stay green
SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
  "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
build/sbt "catalyst/scalastyle" "catalyst/Test/scalastyle" "sql/scalastyle" \
  "sql/Test/scalastyle" && dev/lint-java
```

## 4. Explicitly out of task 10

Predication, comparisons, `IfElse`, `greatest`/`least`, `dayofweek`/`weekday`
and the three-valued mask pairs (task 11 - the mask algebra here is
deliberately only the null-intolerant AND case, structured so task 11 replaces
cases, not assumptions). Partial eligibility, passthrough forwarding, batch
ownership for forwarded vectors, the row-node escape hatch (task 12).
Telemetry attributes (task 13). Throughput-benchmark and docs refresh
(task 14). The `lazy val` drive-by (task 15). Deleting the dispatcher
machinery or the `VarkaProjection` shell (the `PLAN_TASK_9.md` 5.4 follow-up,
awaiting the milestone owner's go-ahead).
