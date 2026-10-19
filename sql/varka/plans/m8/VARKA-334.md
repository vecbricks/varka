# VARKA-334: `IS NULL` and `IS NOT NULL` over a predicate

*Row 334 of `m8/PLAN.md`, wave 0. Planned on 16 October 2026 at `8a50f61a368`, from `m9/SCOPE.md`
item 93 and the parked branch it names. The design is the branch's; the row's work is to
understand the branch's seven test failures, which section 2 sizes and leaves as the first step.*

## 1. Where this came from

VARKA-138's admission check (`m7/VARKA-138.md` 2 and 9) found that `(p) IS NULL` was fused for
none of the coverage table's 27 predicate rows. It reaches Varka only where `NullDownPropagation`
pushes the test onto the columns, 30 of 81 cases; a predicate it cannot push through, or a query
whose optimizer settings keep the form, hands the filter or the CASE back to Spark's row path.
Item 93 (13 October 2026) proposed the design and a local branch built it; its first catalyst run
failed seven tests, undiagnosed, and the work was parked. Row 336 (null-safe equality) is the
second condition over validity words and follows this one.

## 2. The admission check, done

1. **The design.** A condition already carries a known-true and a known-false mask
   (`VarkaVectorIR.Cond`, permitting `Compare`, `And`, `Or`, `Not`, `IsNotNull` and `InRanges`,
   215 to 225). `IsNotNull` takes a condition child beside a column: `kT = kT(c) | kF(c)` and
   `kF = ~kT` in a masked body, constant true in a dense one, which has no unknown lane; `IS NULL`
   stays the `Not` of it, a slot swap (680). Today `IsNotNull` (682 to 695) is restricted to a
   `ColumnRef`, its walk at `VarkaVectorWalk` 977 to 980 reading the column's word.
2. **The branch.** `varka-326`, local to the main checkout and not pushed, one commit
   `8077a4b9ddf` (13 October 2026) on merge base `25a1bae6893`, which master has passed by 124
   commits. Nine files, 97 lines added and 16 removed: `VarkaConditionCompiler.java` (a
   `BooleanType` operand of a validity predicate compiles as a condition), `Analysis.java` (the
   child may be a `Cond`), `Slots.java` (the condition child's edge followed in a masked body only,
   and no word demanded for it), `VarkaVectorIR.java` (the doc and the restriction),
   `VarkaVectorWalk.java` (a masked arm: load `kT` and `kF`, `OR`, store, then `XOR -1`),
   `VarkaReference.java`, `VarkaCoverageSuite.scala`, `coverage.json` (four rows, all fusing) and
   `docs/sql-varka.md`. The branch names the work "VARKA-326" in two comments, and `m7` has a row
   326 of its own; this row renames them VARKA-334.
3. **The seven failures, grouped by suite.** Three are in `VarkaEmittedBytesSuite`: the oracle, its
   many-threads test, and "validityOrFirst moves nothing at the defaults and moves the bytes over
   its subject" (624). Four are in `VarkaCoverageCompositionFuzzSuite`: the per-row kernel check,
   the wide composition test and two shrinker tests. Inferred, not checked: the first three are
   the bytes oracle meeting four new coverage rows with no `emitted_bytes.json` entries, which
   fails the oracle and every test that reads the same fixture, and would be cured by
   regenerating it; the composition four are the real question, because that suite composes
   coverage rows into wider trees, and a condition child under `IsNotNull` is the first node whose
   child's masks must be computed before a parent condition reads them in a masked body - an
   ordering `Slots` now follows by the new edge rule, which a composed tree with the same predicate
   shared by two parents may break.
4. **No null literal is involved**, and the row needs no new IR node: `IsNotNull` widens its child.
   Its validity semantics (`skips = true`, Analysis) are unchanged.
5. **The census does not move.** A predicate is `BOOLEAN`-typed, so `isnull` over one is an
   `isnull` pair of type `bool`, and the census counts a type only where a coverage row reads a
   column of it (2.1); a boolean column is 331's. The row's progress shows in the coverage table
   and item 93's 27 predicate rows, not in the census share.

**What still needs a run, and what admits the row.** The branch replayed on master and the seven
tests run, on cores 3 to 5:

    dev/varka_task_new.sh 334 "IS NULL and IS NOT NULL over a predicate"
    git cherry-pick 8077a4b9ddf          # in the task's worktree
    taskset -c 3-5 build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite \
      *VarkaCoverageCompositionFuzzSuite'

then, if the three bytes-oracle tests fail as before, the suite again under
`VARKA_BYTES_REGEN=true` (its regeneration switch, `VarkaEmittedBytesSuite` 63) and once more
without it.
**Admitted** as designed if the bytes-oracle tests pass once the fixture holds the new rows and
the composition failures reduce to a named cause with a seed (`VarkaCoverageCompositionFuzzSuite`
prints its seed and iteration). If a composition failure is a wrong answer in the kernel, not in
the harness, the design is rejected as it stands until the cause is fixed; the row records which.

## 3. The design

### 3.1 The branch's design, completed

1. **The compiler**: `compileValidity` compiles a `BooleanType` child as a condition and wraps it
   in `IsNotNull`; the filter and the `CASE` condition paths reach it as they reach a column's.
2. **The analysis and the slots**: as the branch has them, plus whatever section 2's run finds
   the composition needs - most likely that a condition child shared by two parents is lowered
   once per lane group and its masks read by both.
3. **The walk**: the branch's masked arm; the dense arm emits the constant-true mask without
   lowering the child (no unknown lane exists there).
4. **The partition oracle**: VARKA-138's TLP `WHERE` oracle queries `p IS NULL` directly instead of
   its composed form, item 93's last clause.
5. **The cost table**: an `IsNotNull` over a `Cond` costs the child's lowering plus one `OR` and one
   `XOR` in a masked body and nothing in a dense one, entered in `VarkaEmitCostTable` so the
   budget prices it.

### 3.2 What is deliberately unchanged

* `IsNotNull` over a column: same bytes (the oracle holds it).
* A value child other than a column: still refused - a computed value's word materializes only
  during its value walk, after condition emission.
* `<=>` and `equal_null` (336), a boolean column as a condition (331), boolean outputs (27).

### 3.3 Registered op counts

`(d < d2) IS NOT NULL` in a masked body: the comparison's own count plus two `VectorMask`
operations (`or`, then `not`); in a dense body: zero beyond a constant mask, the comparison not
lowered. Read with `dev/varka_emit.sh "(d < d2) IS NULL" --nulls 64` once built, asserted by an
op-count test.

## 4. Files

* `.../codegen/VarkaConditionCompiler.java`: the boolean operand of a validity predicate
* `.../codegen/varka/VarkaVectorIR.java`: `IsNotNull`'s child restriction and doc
* `.../codegen/varka/Analysis.java`, `Slots.java`, `VarkaVectorWalk.java`: the condition child's
  analysis, edges and lowering
* `.../codegen/varka/VarkaEmitCostTable.java`: the node's cost over a condition
* test: `VarkaReference.java`, `VarkaIrGrammar.java`: the oracle's arm and the fuzzer's production
* test: `VarkaCoverageSuite.scala`; sql/core `VarkaSparkFuzz.scala` (the TLP oracle): the rows; `p
  IS NULL` queried directly
* `sql/varka/coverage.json`, `docs/sql-varka.md`, `sql/varka/emitted_bytes.json`: regenerated; new
  rows only

## 5. Tests, and what each is for

1. **The coverage rows**: `p IS NULL` and `p IS NOT NULL` as filters and as `CASE` conditions over
   every predicate row of the coverage table (27), with the poisoned null lanes and both validity
   fills; the row engine is the oracle.
2. **The seven tests of section 2** green, with the cause of each named in section 9.
3. **The partition test**: `p`, `NOT p` and `p IS NULL` partition the rows exactly, with the TLP
   oracle querying `p IS NULL` itself.
4. **A shared predicate**: one condition under `IS NULL` and under a `CASE` in the same kernel;
   catches the lowering-order case section 2 infers.
5. **Column children unchanged**: the oracle's existing `IsNotNull` rows do not move.

## 6. The measurement

None of throughput: the shapes fused here went to the row path before, and a kernel is not
measured against itself. The cost table's entry is checked by the emit cost suite.

### 6.1 Predictions, registered before the run

1. **The three bytes-oracle failures are the missing fixture rows** and pass once it is
   regenerated (section 2 item 3's inference).
2. **The four composition failures share one cause**, a condition child lowered in the wrong order
   or twice in a composed tree, not a wrong mask formula.
3. **All 27 predicate rows fuse** under `IS NULL` and `IS NOT NULL`, as filters and as conditions,
   once the composition cause is fixed.
4. **The added test time is under 20 s**: about sixty coverage rows, estimated at 10 s and doubled
   at VARKA-243's ratio, the calibration table's commonest miss.

## 7. Risks

1. **The composition failures are a wrong answer.** Then section 2's admission rejects the design
   as it stands; the fuzzer's seed reproduces it and the row stops until the cause is fixed.
2. **A condition lowered twice.** Correct but slower; the op-count test and the cost table see it.
3. **The branch is 124 commits behind.** The cherry-pick may conflict in `Slots` or the walk, both
   edited since by milestone 7; the conflict is resolved against master's structure, not the
   branch's.

## 8. Sequencing

1. This plan; section 2's replay and the seven tests, their causes recorded here.
2. The branch's change on master with the fixes, the coverage rows and the regenerated fixtures.
3. The partition oracle's direct query, the cost table entry.
4. The predictions scored.

Explicitly out of this row: null-safe equality (336), boolean columns (331), boolean outputs (27),
and a computed value child of `IsNotNull`.

## 9. Outcome

*19 October 2026.* `IS NULL` and `IS NOT NULL` over a predicate fuse, as filters and as `CASE`
conditions, over every predicate row whose predicate can be null. The design is the branch's;
the work found why it failed, one gap it had, and two consequences it had for code it did not
touch.

**1. The seven failures: one cause, not a wrong answer.** The branch's commit was cherry-picked
onto master (`e6aa78e3cd5`); `Analysis` and `VarkaCoverageSuite` conflicted and were resolved
against master's structure, the generated files were taken from master and regenerated, and
"VARKA-326" became VARKA-334 in the comments. With `coverage.json` regenerated so the suites read
the four rows, `VarkaEmittedBytesSuite` and `VarkaCoverageCompositionFuzzSuite` failed exactly the
branch's seven tests, all with one exception: `Analysis.analyzeOp` refuses a condition as the child
of any node but `IfElse`, `And`, `Or` and `Not` ("condition node Compare[...] in a value position
(child of IsNotNull)"). The check is older than the branch (its merge base `25a1bae6893` has it),
so the branch's kernels never got past analysis; `VarkaCoverageSuite` passed there because it
compiles a row without emitting it. Neither inference of section 2 item 3 held: the bytes-oracle
failures were not missing fixture rows, and the composition failures were not a lowering order.
With `IsNotNull` added to the exemption and the fixtures regenerated, all nineteen tests of the two
suites pass, the composition fuzzer's per-row kernel check and wide compositions included, so the
design is admitted. (A first run of the replay also passed all nineteen; it read master's
`coverage.json`, without the new rows, and exercised nothing new.)

**2. A gap the branch had: the guards under a null test.** `VarkaRangeAnalysis.guardColumns` left
every `IsNotNull` subtree unguarded, right over a column and wrong over a condition, since whether
a conjunction or a disjunction is known depends on its operands' values: in
`(year(d) = 2021 AND i > 0) IS NULL` the `year` of a date outside the calendar contract had no
in-kernel guard and no evaluator check. Found by reading; the row with its domain proved it, the
coverage suite failing it with "names the domain ... but carries no guard" until the rule became
"over a column only". The differential suite's domain test then found the second half: the dense
body answers the test constant true without lowering the condition, so it served a batch outside
the domain that the masked body declines. Its answer was right, since a dense batch has no unknown
lane and the test does not depend on the year, but a guard's verdict depended on which body ran.
Lowering the condition in the dense body for its guards alone was tried first and refused by the
slot discipline (a condition mask stored and never read, `VarkaUnreadLocalsSuite`); instead such a
kernel has no dense body (`Analysis.guardUnderNullTest`, dispatched as the existing
`nullsFromValidInputs` kernels are), costing it the masked body on null-free batches. The dense
arm's constant-true lowering holds for every condition with no guard under it.

**3. What the dense skip asked of the slot plan.** A dense body that does not enter the condition
must not plan for it: `Slots.emittedNodes` and the prefix fragments' read plan
(`planFragmentsReadingMonth`) now follow the emission past an `IsNotNull` over a condition, as
they already did past a loaded calendar prefix. Before that, 16 shared slots and 78 other locals
of the cost corpus's dense methods were stored and never read. The review before the pull request
found a wrong answer the skip made possible in IR the compiler does not build: a group that reaches
a date only under such a test is still the date's producer when its prefix is materialized across
groups, so its dense body never stored the prefix a later group's dense body then loaded
(`month(c1)` read 0 for 12 beside `year(c1) = 2021` under the test). Through SQL a calendar node
over a column always carries its column guard, which already makes the kernel masked-only (item
2), but the grammar draws the unguarded form. Such a kernel now has no dense body either
(`Analysis.maskedOnly`, read at emission, since the prefixes are planned per grouping), and
`VarkaEmitterValiditySuite` pins the shape, which fails without it.

**4. A latent emitter bug the corpus redraw surfaced.** The grammar's new production redrew the
wide shapes that reach it, and one of them, `wide int 66`, stored a month step nothing read. Shrunk
to three roots over one date - `if(c0 < c2, dayofyear(c1), add_months(c1, c2))`,
`thursdayof(c1)`, `quarter(c1)` - it has nothing of this row's in it and reproduces under the
weights' grouping, which no price moves: a group whose masked body decomposes the date in two
fragments, keyed by different words, computed the month for the later group's load in both, and
only the first stores it. Only the storing fragment now computes it (`VarkaChronoLowering`); a
dead vector op, never a wrong answer. `VarkaUnreadLocalsSuite` pins the shrunk shape, which fails
without the fix.

**5. What landed.**

* The compiler, analysis, slots and walk as the branch had them, with the exemption of item 1, the
  guard rule and dispatch of item 2 and the plan of item 3.
* Fourteen coverage rows, 142 from 128 once row 28's merged (predicates 67 from 56): the branch's
  four; a conjunction under `IS NULL` with its domain, a disjunction under `IS NOT NULL`, a negation
  under `IS NULL`, the long lane's `IS NOT NULL`, the comparison shared by its null test and itself
  as a filter (`(d < d2) IS NULL OR d < d2`) and as a `CASE` (`CASE WHEN (d < d2) IS NULL THEN d
  WHEN d < d2 ...`), which section 5's test 4 asked for, an equality under each test, as a filter
  and as a `CASE`, and two across the lanes, `(i = 5 AND l = l2) IS NOT NULL` and `(i = 5) IS NULL
  OR l = l2`. No existing row's bytes moved in `emitted_bytes.json`.
* **Beside row 28's kernels of both lanes** (merged 20 October 2026): `onLongLane`, which lifts an
  int condition to meet a bigint one, widened an `IsNotNull`'s child as a value, which over a
  condition would build a widening of a condition; it now lifts the condition under the test. And
  the null test's column checks read a column through a widening as a column.
* **What a default session reaches.** Spark's `NullDownPropagation`, on by default, rewrites a
  null test over an ordered comparison (`<`, `<=`, `>`, `>=`) or a negation into null tests on its
  operands (`supportedNullIntolerant` in `optimizer/expressions.scala`): `(d < d2) IS NULL` reaches
  Varka as `isnull(d) OR isnull(d2)`, which fused before this row. A null test over an equality, a
  conjunction or a disjunction is left as written, and `(NOT (d = d2)) IS NULL` reaches Varka as
  `(d = d2) IS NULL`; those are the forms a default session brings to the new code. The rows over
  an ordered comparison say so in their notes, the two equality rows exercise the default path end
  to end in the per-row differential, and the partition and `CASE` tests below run with the rule
  excluded as well (*corrected 19 October 2026 after review: the first note on `(d < d2) IS NULL`
  called it "the form it cannot push", which was wrong*).
* **The partition oracle queries `(p) IS NULL` itself.** `WHERE p`, `WHERE NOT (p)` and
  `WHERE (p) IS NULL` run on Varka, each with a Varka filter whose kernel served every batch, each
  equal to Spark's on rows, and the three share no row and leave none out. Where `p` cannot be null
  (the 19 null-test rows) Spark's `NullPropagation` folds the third to false and only its answer is
  compared; where the default optimizer turns it into a null test over a computed value - only
  `(month(d) > i) IS NULL`, which becomes `isnull(month(d)) OR isnull(i)` - Varka declines that by
  design (section 3.2) and the answer is compared. `WHERE (p) IS NOT NULL` runs on Varka as well,
  under the same conditions, and must be Spark's and exactly the true and the false rows.
* **Every predicate row as a `CASE` condition.** `CASE WHEN (p) IS NULL THEN a ELSE b END` and its
  `IS NOT NULL`, over the row's lane, on the three fixtures with the rewrites excluded: the kernel
  serves the whole projection for each of the 41 rows whose predicate can be null (46 after the
  merge), and every answer is Spark's.
* The grammar and the series draw the null test over a condition (`VarkaIrGrammar`,
  `VarkaIrSeries`); the cost model counts it as its own feature, `IsNotNull/cond/<lane>`, priced
  in both tables. The redraw moved two pinned lists: the wide shapes that gain a loop method under
  the prediction (`VarkaEmitCostSuite`: long 84 and 184 now, int 120 and long 78 before), and the
  fuzz suite's one draw whose stranger variant is over the op cap and fits, which is rare (one in
  the first 200,000 draws of seven seeds) and is named again.
* Op counts (`VarkaEmitterValiditySuite`): over a comparison the test adds no `IntVector` or
  `VectorMask` call to the masked loop - its `OR` and `XOR` are on the `long` words, so section
  3.3's "two `VectorMask` operations" was wrong - and lowers no comparison in the dense one, whose
  calls equal the column form's; beside the same comparison the comparison is lowered once.
* Regenerated: `coverage.json` and the docs table, `emitted_bytes.json` (the new rows, the fuzz
  blocks the redraw moved, the whole-table option arms), `width_audit.json` (the new rows),
  `VarkaEmitCostTable`, `VarkaEmitCostRegister` and `emit_cost_audit.json`. The census does not
  move (section 2 item 5): `dev/varka_census.py --check` passes.

**6. A known limitation.** `Analysis.maskedOnly` takes the dense body away whenever a calendar
prefix is materialized for a date a calendar node under a null test over a condition reads, also
when only a group that loads the prefix, and not the one that stores it, reaches the test. Such a
group's dense body would load correctly; only the storing group's skip loses the store. The
over-approximation costs those kernels the masked body on null-free batches and never an answer;
narrowing it to the producing group is left until a measurement shows the cost.

### 9.1 The predictions

| | prediction | measured | |
| :-- | :-- | :-- | :-- |
| 1 | the three bytes-oracle failures are the missing fixture rows | one analysis check, older than the branch, failed all seven | missed |
| 2 | the four composition failures share one cause, an order or a double lowering | one cause, the same check; no order | half: one cause, not that one |
| 3 | all 27 predicate rows fuse under both tests, as filters and as conditions | every predicate row whose predicate can be null, 41 of 61, as written; under the default optimizer one row's `IS NULL` becomes a test over a computed value, outside the row | held, as written |
| 4 | the added test time is under 20 s | `VarkaCoverageDifferentialSuite` 136 to 159 s (+23 s, 332 to 421 tests), `VarkaSmallTreeSuite` 30.1 to 33.6 s (+3.4 s, the series' new arm); the suites reading the coverage table grow by their ten rows, unmeasured; single runs of master's test files against this branch's, at load 6 | missed, at least 1.3 |

Prediction 4 priced coverage rows; most of the time is the differential suite's sixty `CASE`
tests and its third Varka query per partition test, which the plan's sections 5.1 and 5.3 asked
for and the estimate did not count.

**Tests.** `catalyst/testOnly *Varka*`: 730 passed, 42 cancelled (the opt-in ones), 0 failed;
`sql/testOnly *Varka*`: 51 suites, 892 passed, 14 cancelled, 0 failed, the coverage differential's
421 among them.
