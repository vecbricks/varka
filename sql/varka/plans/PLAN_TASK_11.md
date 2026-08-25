# Task 11: predication and null-skipping ops

**Status: PLANNED.** See `PLAN_MILESTONE_2.md` (task row 11, section 2.6) for
the milestone context and `PLAN_TASK_10.md` for the emitter this task extends.
Sections 1-4 are the plan; a section 5 will record the outcome. Implementation
branches from master once task 10 (PR #28) is merged.

## 1. Why this task, and what it carries

Task 10 made nested arithmetic chains fuse. What the milestone actually bet
its "irreducible chains" argument on is this task: `CASE WHEN` via
`VectorMask.blend` (a comparison lives as a mask in a register, both branches
compute under it, no boolean column, no branch, no misprediction),
`greatest`/`least` (one lane instruction each, and `greatest(date_add(d1, 7),
d2)` is a chain no folding rule can reduce), and `dayofweek`/`weekday` (Spark
computes them through `LocalDate.ofEpochDay` - an object allocation per row -
so the SIMD version does not just fuse, it replaces an allocating path).

This is also where the milestone's correctness risk concentrates, which is why
it is its own task: three different mask algebras (AND, OR-with-substitution,
blend) coexist in one loop, three-valued logic rides on mask pairs, and the
scalar tail must agree with all of it row for row. The rules are written in
`PLAN_MILESTONE_2.md` 2.6 and restated here as implementation contracts, not
rediscovered in review.

What task 10 hands over, and this task must not break: the twin-body structure
(`runDense`/`runMasked` as sibling methods with a per-batch dispatcher), the
word-space mask algebra with one `VectorMask` per distinct referenced-column
set, equality-keyed DAG-CSE, the hybrid broadcast rule, and the caps. The
compiler stays the single eligibility oracle; the rule and the evaluator do
not change in this task.

## 2. Deliverables

### 2.1 IR: values, and now conditions

`VarkaVectorIR` gains a nested `sealed interface Cond` for mask-valued nodes -
a condition is not an int-lane value and the type system should say so:

* `Compare(CompareOp op, VarkaVectorIR left, VarkaVectorIR right)` implements
  `Cond`; `CompareOp` is an enum `{LT, LE, GT, GE, EQ}`. Interior only: the
  emitter rejects a `Cond` as an output root (boolean output columns stay out
  of scope, per the milestone).
* `And(Cond, Cond)`, `Or(Cond, Cond)`, `Not(Cond)` implement `Cond` - what
  makes `CASE WHEN` conditions realistic, and what `BETWEEN` rewrites to.
* `IfElse(Cond cond, VarkaVectorIR thenNode, VarkaVectorIR elseNode)` - a
  value node; `CASE WHEN` desugars onto it (2.2).
* `Greatest(VarkaVectorIR, VarkaVectorIR)`, `Least(...)` - value nodes with
  the null-skipping algebra (2.3).
* `DayOfWeek(VarkaVectorIR)`, `WeekDay(VarkaVectorIR)` - unary,
  null-intolerant, `IntegerType` outputs.

One invariant is decided here because the dense body depends on it: **every
task-11 node is total and null-strict-or-skipping over valid inputs** -
comparisons and `floorMod` cannot fail, a blend of valid branches is valid,
`greatest` of valids is valid. Therefore null-free inputs still imply all-valid
outputs, and the dense body keeps writing all-true validity with no
bookkeeping. The node that would break this - a null literal, i.e. `CASE WHEN`
with no `ELSE` - is deliberately excluded (see 4), because it makes output
validity condition-dependent even on null-free batches.

### 2.2 Compiler: conditions, branches, and date literals

`VarkaExpressionCompiler` recursion grows:

* `LessThan`/`LessThanOrEqual`/`GreaterThan`/`GreaterThanOrEqual`/`EqualTo`
  over two compilable date-lane children -> `Compare`. `EqualNullSafe` (`<=>`)
  is excluded: its truth value on two nulls breaks the null-intolerant
  comparison rule, and it earns its own algebra entry or nothing (see 4).
* `And`/`Or`/`Not` over compilable conditions.
* `If(pred, t, f)` -> `IfElse`. `CaseWhen(branches, Some(else))` -> a right
  fold into nested `IfElse` - SQL's first-match semantics is exactly nested
  if-else. `CaseWhen` with no `ELSE` declines (2.1's invariant; the whole
  projection then stays on Janino, all-or-nothing as before).
* `Greatest(children)`/`Least(children)` - n-ary in Spark - left-fold into the
  binary IR nodes; the skip-null algebra is associative (2.3), so the fold is
  exact. Two children minimum by Spark's checkInputDataTypes.
* `DayOfWeek(child)`/`WeekDay(child)` over a compilable date child.
* New leaf fold: a `DateType` literal (its value already an epoch-day int)
  becomes a `LiteralSlot`, sharing the existing per-distinct-value table.
  `d < DATE'2024-06-01'` and `greatest(d, DATE'2020-01-01')` are the common
  shapes that need it; without this the comparison work is nearly unreachable
  from real queries.

Branch and comparison children must be date-or-int lane values with equal
Spark types where Spark requires it - the analyzer has already enforced type
agreement (and inserts `Cast`s otherwise, which decline as always). Output
Spark types: `IfElse`/`Greatest`/`Least` carry their branch type
(`DateType` or `IntegerType`); `DayOfWeek`/`WeekDay` are `IntegerType`.

### 2.3 Emitter: the three algebras, in word space

Task 10 computes validity as long words and materializes one `VectorMask` per
distinct referenced-column set. That scheme assumed a node's validity is the
AND of its referenced columns - true for null-intolerant ops only. Task 11
generalizes: **validity becomes a per-node word, memoized with the same
equality-keyed memo as values**, and the static column-set table survives only
as the fast path for pure-arithmetic subtrees. The rules, per 2.6 of the
milestone plan, all in long arithmetic:

* Arithmetic, `DayOfWeek`/`WeekDay`: `valid = AND` of children's words
  (unchanged).
* `Compare`: the lanes compare in vector space -
  `left.compare(OP, right)` yields a `VectorMask` - and `cmp = mask.toLong()`
  enters word space once. The pair is then
  `knownTrue = cmp & validL & validR`,
  `knownFalse = ~cmp & validL & validR`.
* Connectives, three-valued on the pairs:
  `kT(A AND B) = kT(A) & kT(B)`, `kF(A AND B) = kF(A) | kF(B)`; dually for
  `OR`; `NOT` swaps the pair. This is why known-false is tracked at all.
* `IfElse`: the effective condition is `eff = kT(cond)` - an unknown condition
  falls through to ELSE, SQL's rule. Value:
  `elseVec.blend(thenVec, fromLong(eff))`. Validity:
  `(eff & validThen) | (~eff & validElse)` - nothing is ANDed globally; a null
  in the branch not taken must not null the result.
* `Greatest`/`Least`: `valid = validA | validB` (null only when all inputs
  are); value substitutes the other lane where one side is null -
  `aSel = a.blend(b, fromLong(~validA))`, `bSel = b.blend(a, fromLong(~validB))`,
  then `aSel.max(bSel)` (`min` for `Least`) - which reduces every case (both
  valid, only A, only B) to a plain max.
* `DayOfWeek(d)` = `floorMod(days + 4, 7) + 1`;
  `WeekDay(d)` = `floorMod(days + 3, 7)` - verified against
  `DateTimeUtils.getDayOfWeek`/`getWeekDay` (epoch day 0 was a Thursday). The
  lane sequence is the truncated-division floorMod: `r = v - (v / 7) * 7`,
  then `r += 7` where `r < 0` (a compare and a masked add or blend), then the
  constant offset. Java's `%` is wrong for negative epoch days - dates before
  1970 - so the differential range must cross zero (that is exactly where a
  naive port breaks). The scalar tail uses `Math.floorMod` by name.

`VectorMask.toLong` re-enters the descriptor table (task 10 removed it);
`compare` (which takes the *erased* `Vector` and a
`VectorOperators$Comparison` constant read with `getstatic`), `blend` (erased
`Vector`), `max`/`min` (erased `Vector`), and lanewise `DIV` join it. A
`fromLong` is materialized per distinct word a blend or store needs, memoized
per group exactly as the column-set masks are today.

The dense body stays radically simpler, by 2.1's invariant: `cmp` words are
the comparison masks unconverted (`eff` = the compare mask itself, used
directly in the blend - no `toLong`/`fromLong` round trip), `Greatest` is a
bare `max`, validity is all-true everywhere, and `floorMod` needs no masking.
The masked/dense split now pays off twice: predication in the dense body costs
almost nothing over the arithmetic it decorates.

Caps and validation extend naturally: `Cond` nodes count toward
`MAX_FUSED_NODES` and path depth; a `Cond` output root, `EqualNullSafe`-less
shapes the compiler already declined, and non-`INT` lanes reject as before.

### 2.4 A measured decision: unmasked compute in the masked body

Task 10's dense-path finding (masked ops with an all-true mask cost 2.3x-2.9x)
raises the same question *inside* the masked body: with validity now carried
as words, the lane ops themselves never need masks for correctness - invalid
lanes may compute garbage because the engine contract already declares invalid
data lanes undefined, every load and store inside `loopBound` is in bounds
(all-null columns included: Arrow allocates their data buffers), and truth
lives only in the validity words the loop writes. The masked body can
therefore run *unmasked* loads, ops and stores end to end, materializing masks
only where a blend semantically needs one.

Expectation, stated before measuring (task 10's 5.2 is the cautionary tale for
predicting these): this wins on mixed-null batches by a margin worth having,
and structurally the masked body becomes "the dense body plus word
bookkeeping". Decided by A/B on the mixed-null parity and chain cases; ships
only if it wins, and either way the numbers go in section 5. If it wins, the
per-column-set `VectorMask` table shrinks to blend sites only.

### 2.5 Wiring

None. The compiler is the oracle and the evaluator drives whatever it
compiles; new nodes flow through `CompiledVarkaProjection` unchanged.
`outputTypes` already distinguishes `DateType` from `IntegerType` outputs.
Both exec nodes and the rule are untouched.

## 3. Verification

The oracle for every rule is Janino - `VarkaDifferentialSuite` compares the
fused path against Spark's row engine - and for `dayofweek`/`weekday`
additionally the `LocalDate`-based `DateTimeUtils` results.

### 3.1 Emitter suite (`VarkaLoopEmitterSuite`, extended; hand-built IR)

* Each `CompareOp` inside an `IfElse`, differential against a scalar oracle,
  over the task-9 length matrix with per-column null patterns.
* The 2.6 null-pattern matrix, three-valued connectives included: nulls in the
  condition's columns, in either branch, in both, `NOT` over unknowns
  (`kF` correctness), `AND`/`OR` where one side is unknown - asserting the
  unknown-falls-to-ELSE rule bit for bit on the validity bitmap.
* `Greatest`/`Least`: both valid, only A, only B, neither (row null), plus the
  n-ary fold shape from the compiler.
* `DayOfWeek`/`WeekDay` differential vs `Math.floorMod` scalar oracle across a
  day range straddling zero (negative epoch days mandatory), every lane
  boundary, and all null patterns.
* Tail-only lengths (below one lane group) for every new node - the tail
  mirrors all three algebras row for row.
* Dense/masked agreement: identical inputs run through both bodies (null-free
  through `runDense`, the same data plus one padding null through
  `runMasked`) must agree on the shared rows.
* Rejections: a `Cond` as output root, named.

### 3.2 Compiler suite (`VarkaExpressionCompilerSuite`, extended)

`CASE WHEN` desugar shape (multi-branch, right fold), else-less `CASE`
declines, n-ary `greatest` fold, date literals landing in the shared slot
table, `EqualNullSafe` declines, a bare comparison as a projection output
declines the projection.

### 3.3 End-to-end (`VarkaDifferentialSuite`, extended)

* `CASE WHEN d < d2 THEN date_add(d, 3) ELSE date_sub(d2, 1) END` and a
  three-branch `CASE`, with nulls in every column, fused and matching Janino.
* `IF(d BETWEEN DATE'...' AND DATE'...', ..., ...)` - the `BETWEEN` rewrite
  and date literals in one query.
* Conditions with `AND`/`OR`/`NOT` over nullable comparisons - the
  three-valued matrix at the SQL level.
* `greatest`/`least` two- and three-arg, with nulls, nested with `date_add`
  (`greatest(date_add(d, 7), d2)` - the milestone's irreducible chain).
* `dayofweek`/`weekday` over a date range crossing 1970, against the row
  engine; `expectFused = true` is again itself the new behavior.
* The negative control (`isFailKernelForTesting`) on a predicated plan.

### 3.4 Benchmark (`VarkaEmitterParityBenchmark`, extended) and the gate

* A `CASE WHEN` case: predicated chain (compare + blend over depth-4 arms) vs
  the same arms unpredicated - pricing predication itself; dense and masked.
* A `dayofweek` case with a per-row `LocalDate.ofEpochDay` loop as baseline -
  the allocating path the SIMD version replaces, and the task's headline
  number candidate.
* The 2.4 A/B on mixed-null cases, recorded either way.

Gate: the predicated dense case must stay within a small factor of the
unpredicated arithmetic of the same depth (blend and compare are one lane op
each; if predication costs multiples, C2 did not intrinsify the new calls -
diagnose with `-XX:+PrintIntrinsics` before building on it). `dayofweek` must
beat the `LocalDate` baseline outright at both widths.

Everything runs green at the preferred width and under
`-XX:MaxVectorSize=16`, and the committed results file is regenerated.

### 3.5 Commands

```
build/sbt "catalyst/testOnly *VarkaLoopEmitterSuite *VarkaExpressionCompilerSuite"
build/sbt "project catalyst" 'set Test/javaOptions += "-XX:MaxVectorSize=16"' \
  "testOnly *VarkaLoopEmitterSuite"
build/sbt "sql/testOnly *Varka*"
build/mvn -f sql/varka/engine/pom.xml test          # untouched, must stay green
SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
  "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
build/sbt "catalyst/scalastyle" "catalyst/Test/scalastyle" "sql/scalastyle" \
  "sql/Test/scalastyle" && dev/lint-java
```

## 4. Explicitly out of task 11

`CASE WHEN` with no `ELSE` (a null-literal branch breaks the dense body's
all-valid invariant; needs a `NullValue` node and condition-dependent validity
in the dense body - record in `PLAN_MILESTONE_3.md` if not picked up sooner).
`EqualNullSafe` (its both-null-is-true case needs its own algebra entry).
Boolean output columns (comparisons stay interior). Integer arithmetic on
`datediff` outputs (the ANSI overflow trap, milestone 2.6 - none of the ops
this task adds can throw, so no ANSI gating is needed for them). Partial
eligibility and passthrough (task 12). Telemetry (task 13). Throughput
benchmark and docs refresh (task 14). The `lazy val` drive-by (task 15).

## 5. Risks, named

* The mask-pair algebra is the correctness surface: `kF` exists only for
  `NOT`, and a wrong pair silently gives two-valued logic that agrees with SQL
  on every null-free input. Only the 3.1 unknown-matrix catches it - which is
  why that matrix is written into the plan.
* Lanewise integer `DIV` (the `floorMod` step) may not map to a hardware
  instruction on x86 and could scalarize inside the Vector API. The bar is the
  allocating `LocalDate` path, which it should clear regardless; if the margin
  disappoints, a strength-reduced mod-7 is a follow-up, not a blocker.
* Vector API erasure again, now for `compare`/`blend`/`max`/`min` and the
  `VectorOperators$Comparison` constants: same defense as before - one
  descriptor table line per call, and the misdescribe-style test discipline.
