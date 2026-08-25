# Task 11: predication and null-skipping ops

**Status: DONE.** See `PLAN_MILESTONE_2.md` (task row 11, section 2.6) for
the milestone context and `PLAN_TASK_10.md` for the emitter this task extends.
Sections 1-5 are the plan as written before implementation; section 6 records
what was built, the measurements, and the deviations - including a JIT finding
(6.3) that reshaped the emitted method layout.

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
  `DateTimeUtils.getDayOfWeek`/`getWeekDay` (epoch day 0 was a Thursday).
  Java's `%` is wrong for negative epoch days - dates before 1970 - so the
  differential range must cross zero (that is exactly where a naive port
  breaks). The scalar tail uses `Math.floorMod` by name.

  The lane sequence for the mod-7 itself is a measured three-way choice, per
  the datetime vector-algorithms note (division elimination is its group 2;
  the techniques are Hacker's Delight chapter 10 material):

  1. *Lanewise `DIV`*: `r = v - (v / 7) * 7`, then `r += 7` where `r < 0`.
     Simplest and certainly correct, but x86 has no SIMD integer divide, so
     the Vector API may scalarize the `DIV` lanes.
  2. *Granlund-Montgomery magic multiply*: replace `v / 7` with a multiply by
     a magic constant plus shifts. Only viable if the Vector API exposes a
     multiply-high on int lanes (the low-half `MUL` alone is not enough for
     32-bit magic division).
  3. *Base-8 digit sum*: `7 = 2^3 - 1`, so mod-7 reduces to summing 3-bit
     chunks - fold 15-bit halves twice (`2^15 = 1 mod 7`), then 6- and 3-bit
     chunks, then one compare-subtract fixup - shifts, ands and adds only,
     all cheap lane ops with no multiply-high dependency.

  Both strength-reduced variants need a careful treatment of negative inputs
  (an unsigned bias must preserve congruence mod 7, and `2^32 mod 7 = 4`, so
  a wrap-around bias is *not* congruence-preserving for free) - which is why
  the negative-days differential is mandatory for whichever variant ships,
  and why variant 1 is the reference the others are tested against.

  **Pre-measured** (standalone scratch bench, 1M ints, 16 int lanes, the
  task-10 host; numbers to be reconfirmed in the task's committed benchmark):
  variant 2 is off the table as written - JDK 25's `VectorOperators` has no
  multiply-high on any lane type (verified against the module; only low-half
  `MUL` exists), leaving only an `I2L`-widening detour that halves lane
  throughput before it starts. Variant 1 confirms the scalarization worry:
  780 M/s, only 2.9x scalar `Math.floorMod` (271 M/s) at 16 lanes - the `DIV`
  lanes do not vectorize - yet still 12x the allocating `LocalDate` baseline
  (62 M/s). Variant 3 runs 6.9 G/s on non-negative inputs, 8.8x variant 1 and
  111x the baseline, so the implementation order is: ship variant 3, keep
  variant 1 as the tested reference, and drop variant 2.

  **The negative-input bias is worked out and validated.** The unsigned folds
  compute `unsigned(v) mod 7`, and `unsigned(v) = v + 2^32 * [v < 0]` with
  `2^32 = 4 (mod 7)`, so the correction is one masked *add of 3* (`-4 = +3
  (mod 7)`) where `v < 0`, before the final compare-subtract fixup (whose
  input then peaks at 12, still within one subtraction). Verified over 1M
  random full-range ints plus the pinned edges `Int.MinValue`,
  `Int.MaxValue`, `-1`, `0` and `+-7` against `Math.floorMod`: exact, at
  6.2 G/s - 7.7x the `DIV` variant and 100x the `LocalDate` baseline. What
  remains for the task is reproducing this inside the emitted loop and its
  differential.

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
* One differential with AQE *enabled*. Every Varka suite session disables AQE
  for plan determinism, so nothing pinned the default-config path until a
  scratch experiment during planning confirmed it: under AQE the rule fires
  inside query stages and the kernels run (`numVarkaBatches > 0`; note that
  a query stage is a leaf node, so the assertion helpers must traverse with
  `AdaptiveSparkPlanHelper`, not `SparkPlan.collect`). This task adds the
  permanent test so the guarantee outlives the experiment.

### 3.4 Benchmark (`VarkaEmitterParityBenchmark`, extended) and the gate

* A `CASE WHEN` case: predicated chain (compare + blend over depth-4 arms) vs
  the same arms unpredicated - pricing predication itself; dense and masked.
* A `dayofweek` case with a per-row `LocalDate.ofEpochDay` loop as baseline -
  the allocating path the SIMD version replaces, and the task's headline
  number candidate.
* The mod-7 A/B from 2.3, now two-way after the pre-measurement (lanewise
  `DIV` vs the base-8 digit sum; the magic multiply is dropped for lack of a
  multiply-high), re-run inside the emitted loop at both widths. The winner
  ships; both numbers are recorded in section 5 so the choice is
  re-checkable when the Vector API or hardware moves.
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
* The `DIV`-scalarization risk is measured rather than open (2.3): confirmed
  real, and confirmed survivable - even scalarized `DIV` clears the
  `LocalDate` bar 12x. The digit-sum variant's negative-input bias - the spot
  where a subtly wrong mod-7 passes every post-1970 test - is likewise
  pre-validated in scratch (2.3); the negative-days differential re-proves it
  in the emitted loop, where the construction could still be transcribed
  wrong.
* Vector API erasure again, now for `compare`/`blend`/`max`/`min` and the
  `VectorOperators$Comparison` constants: same defense as before - one
  descriptor table line per call, and the misdescribe-style test discipline.

## 6. Outcome

Everything in section 2 exists as planned; section 6.4 lists the deviations.
All suites green at the preferred width and under `-XX:MaxVectorSize=16`:
`VarkaLoopEmitterSuite` (17 tests, seven new, against an in-suite reference
evaluator implementing the 2.6 semantics independently),
`VarkaExpressionCompilerSuite` (9), the sql/core Varka suites (69, including
seven new differentials and the AQE test), the engine untouched (28),
scalastyle and lint-java clean.

### 6.1 The numbers

`VarkaEmitterParityBenchmark`, 1M rows, best-time M rows/s, emitted loop vs
the hand-written kernel (committed file carries the preferred width; the
four-lane run in parentheses):

| case | kernel | emitted | ratio | (4-lane ratio) |
| :--- | ---: | ---: | ---: | ---: |
| date_add, null-free | 6243 | 15285 | 2.4x | (3.7x) |
| date_add, mixed | 5072 | 16761 | 3.3x | (3.2x) |
| datediff, null-free | 4109 | 6397 | 1.6x | (3.9x) |
| datediff, mixed | 3109 | 9413 | 3.0x | (3.8x) |

Predication and the new ops, preferred width: `CASE WHEN` with depth-4 arms
runs at 0.9x the same-depth plain arithmetic on null-free input (10778 vs
12016 M rows/s) *while computing both arms plus the compare and blend* - the
gate asked for "within a small factor" and this is within noise of free.
`dayofweek` runs 3598 M rows/s - 5.5x the lanewise-`DIV` reference variant
(659, confirming the scalarization in-loop) and **36x** the per-row
`LocalDate` path (100) Spark uses today. Chains: depth-16 fused 7819 vs 115
sequential (68x); the DAG pair 3348 with CSE vs 2767 without (+21%; +200% at
four lanes, where compute is scarcer) vs 197 sequential. The widest shape (64
ops) runs 986 vs 29 sequential - see 6.3 for why that number exists at all.

### 6.2 The plan's other measured decision: unmasked compute shipped

Section 2.4 predicted the masked body could drop masked ops for word
bookkeeping and win; it did, decisively: against task 10's committed numbers,
mixed-null `date_add` went from 8367 to 16761 M rows/s and mixed `datediff`
from 3779 to 9413 - roughly 2x across every nulled case - and the masked body
became structurally "the dense body plus validity words", with `VectorMask`
materialized only at compare, blend and substitute sites. The scalar tail was
rebuilt as a per-row topological pass over the node slots, mirroring the word
algebra rule for rule (and CSE-ing shared subtrees per row, which task 10's
recursive tail did not).

### 6.3 The JIT finding: loop methods must be small by construction

The widest-shape case collapsed ~100x mid-task, and the collapse was
*history-dependent*: a 64-op loop method ran 1.0 G rows/s in a fresh JVM, 9
M rows/s after the same JVM had compiled and hot-run just seven other emitted
kernels, and 13 M rows/s in the full benchmark JVM - while 16-op loop methods
were healthy under every pollution level measured. Splitting the scalar tail
into a sibling method (the first suspect) was correct layering but not the
cure; capping `MAX_FUSED_NODES` at 48 failed too, because the cliff moves
with JVM history. The shipped fix is structural: outputs are partitioned into
sibling *loop methods* of at most `GROUP_BUDGET = 16` ops each (greedy in
output order, counting only nodes new to the group so shared subtrees keep
their cross-output CSE), with a driver method zeroing validity, taking the
all-null shortcut, and calling the loops and the tail in sequence. With the
split, the 64-op kernel runs 986 M rows/s *in the fully polluted benchmark
JVM* - matching its fresh-JVM number - and `MAX_FUSED_NODES = 64` stands with
an honest story. Recorded for milestone 3: per-task loaders make every query
a fresh set of compiled vector methods, so an executor JVM accumulates
exactly the pollution this reproduces - the byte cache (item 2), which
shrinks the set of distinct compiled classes per JVM, gains a second
motivation beyond amortization.

### 6.4 Deviations from the plan

* `CaseWhen` compiles branches in query order (left to right, then ELSE), so
  input ordinals and literal slots register deterministically in reading
  order; only the fold into nested `IfElse` is right-associative.
* `Cond` extends `VarkaVectorIR` rather than standing apart: the shared memo
  and analysis machinery must see condition nodes. The typed field on
  `IfElse` enforces one direction statically; the emitter's analysis rejects
  the other (a condition as a root or value operand), and the suite pins the
  rejections.
* The emitter's method layout is driver + loop groups + tail (6.3), not the
  planned single body per variant.
* One emitter bug found by machinery, not review: an early digit-sum draft
  double-stored the child vector, and the Class-File API's stack-map
  generation rejected the class at build time - the fail-fast the descriptor
  discipline was designed for, one layer earlier than expected.
* The AQE differential (planned during the pre-task investigation) is in:
  the rule fires inside query stages and the kernels run, asserted through
  `AdaptiveSparkPlanHelper` because a query stage is a leaf node that plain
  `SparkPlan.collect` never descends into.
* The mod-7 A/B ran in-loop as planned: digit sum 5.5x the `DIV` variant;
  the sign bias (`+3` where negative, `2^32 = 4 mod 7`) validated in-suite
  against `Math.floorMod` and `LocalDate` across `Int.MinValue`/`MaxValue`
  and both null shapes. The `DIV` variant stays behind a test hook as the
  tested reference.
