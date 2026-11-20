# VARKA-601: Measure the row copy of a split filter whose fused part reads a value

## 1. Where this came from

Row 601 of `PLAN.md`, added on 11 November 2026 on the owner's word from the build of row 598
(`VARKA-598.md`, #1486). That build found the cost of a filter split into a Varka kernel and a
row filter above it. The to-row node (`VarkaFilterColumnarToRowExec`) copies every kept row's
columns into an `UnsafeRow` before the row filter reads it. Where the fused part is only the
`IS NOT NULL` tests Spark infers, the kernel drops almost nothing, and the copy made the split
slower than Spark's own filter. Row 598 now declines that shape (`nullTestsAlone` in
`VarkaExpressionCompiler`). A split whose fused part reads a value, such as
`isnotnull(l) AND l < 5 AND <residual>`, pays the same copy for each row it keeps. But it drops
rows before the copy, and `VARKA-598.md` 3.2 lists it as not measured.

## 2. The admission check, done

Read on master at `e5d70012bdc`, 20 November 2026. No run was made for this plan.

1. **The split is still planned and still copies.** `nullTestsAlone` demotes the fused
   conjuncts only when every one is an `IS NOT NULL` over a bare column. One value-reading
   conjunct returns `Map.of()` and keeps the split. `VarkaFilterColumnarToRowExec`
   (`VarkaFilterExec.scala`) is unchanged by #1486.
2. **No committed runner file times such a split.** The baselines' class check cannot hold one.
   `VarkaSqlBenchmarkBase.fusion` returns empty for a plan with a row operator that computes
   above a Varka node that is not a null-test filter, and `requireBaseline` fails on an empty
   class. So no baseline section has the shape, at either width.
3. **One laptop record has the shape, by accident.** After row 503, `(d < d2) AND (l < l2)` at
   two long lanes is a split. `d < d2` fuses as an int kernel and `l < l2` is the row filter's.
   `results/VARKA-503/two-lane-after.txt` (laptop, `-XX:UseAVX=2 -XX:MaxVectorSize=16`) times
   it at 1.3X of the row path without nulls and 1.8X at one row in ten null. The share of rows
   `d < d2` keeps is not printed there (**[I]**: about half, from the table's two hashed date
   columns). So a split that drops about half its rows can win. Whether one that keeps 90%
   does is still open, and nothing was measured on the EPYC 7763.

The problem the row states holds: the shape is unmeasured on the runners, and the one record
suggests the answer depends on the share kept.

## 3. The design

### 3.1 A baseline class, and a fusion class for the split

* **`VarkaSplitFilterBaselineBenchmark`**, a new class beside the other baselines, over its own
  Arrow-cached table of 2,000,000 rows: `u`, an `INT` uniform on `[0, 1000)`
  (`pmod(hash(id, 1), 1000)`); `lu`, the same as a `BIGINT`; and `i`, `l` and `l2`, payload
  columns. `_n` twins of `u` and `lu` have one row in ten null; `i` is never null, so the
  residual keeps the same rows at both densities. The residual conjunct is `hash(i) % 7 <> 0`.
  `Murmur3Hash` is in no milestone 8 row, so it stays on the row path, and it keeps six rows in
  seven.
* **Sections**, each at both densities:
  - `u < 900 AND <residual>` and `u < 100 AND <residual>`: an int split keeping 90% and 10%.
  - `lu < 900 AND <residual>` and `lu < 100 AND <residual>`: the long lane's. At two long lanes
    on x86 row 503 declines the long conjunct, so these are `NONE` there, as the day-time
    baseline lists its long filters (`declinesLongConditions`).
  - `u < 900 AND <residual>` with `i, l, l2` out: the copy of three columns, not one.
  - Controls: `u < 900` alone (`WHOLE`, the kernel with no row filter) and the residual alone
    (`NONE`).
* **`Fusion.SPLIT`**, a new class in `VarkaSqlBenchmarkBase`: the only operator that computes on
  rows is one `FilterExec` directly above a `VarkaFilterColumnarToRowExec` whose condition reads
  a value. Every plan that fits no class today still fits none, so no listed section moves. The
  queries suite (`VarkaSplitFilterBaselineQueriesSuite`) checks the classes at both refusal
  settings, as the others do.

**Alternative considered: a probe** like `VarkaNullTestFilterProbe`, committed under
`results/` with no class check. It is cheaper by about 150 lines. A baseline is better here:
a later row that changes the split (a cheaper handoff, a run-time choice) moves a class, and the
class check fails the run instead of timing a different plan under the same label. The owner can
override this (question 10.2).

### 3.2 The rule, decided by the measurement

The rule cannot read the share a conjunct keeps. Plan time has no statistics for an Arrow-cached
view. So the rule is syntactic, like 598's, and the measurement picks one of three:

* **(a) No rule.** Every split keeps fusing. Taken when no section loses: every share reads
  within 5% of 1.0X or above at both widths, by the minimums of two runs.
* **(b) Decline every value split beside a residual.** Taken when the splits lose at both shares
  at a width where (c) does not separate them. The demotion goes in
  `VarkaExpressionCompiler.predicate` beside `nullTestsAlone`, with its own reason in `EXPLAIN`.
* **(c) Decline by width.** Taken when the splits lose at one width only, at both shares. The
  session setting carries it, as row 503's `declineTwoLaneConditions` does.

The remaining outcome is mixed: the 10% split wins and the 90% one loses. No syntactic rule
then declines every losing shape without declining a winning one, so the row's done-when ("each
shape that loses declines whole") cannot be met by plan-time syntax. That outcome goes to the
owner (question 10.1). Until he answers, the plan builds nothing for it, and the row stays open
with the loss recorded here, not closed under (a). A run-time choice is the cure
(`m9/SCOPE.md` item 119, the same idea for null tests).

### 3.3 What is deliberately unchanged

* The null-tests-alone decline (row 598) and the two-lane condition (row 503).
* The copy itself. `VARKA-598.md` 2 found no cheaper handoff than Spark's own plan.

### 3.4 Registered op counts

None: no kernel's bytes change under (a), and (b) and (c) only remove kernels.

## 4. Files

| file | what |
|---|---|
| `VarkaSplitFilterBaselineBenchmark.java` | the new baseline |
| `VarkaSplitFilterBaselineQueriesSuite.scala` | classes, answers and kernel checks over 2000 rows |
| `VarkaSqlBenchmarkBase.java` | `Fusion.SPLIT` |
| `VarkaDecimalBaselineQueriesSuite.scala` | a fusion-class case for `SPLIT` |
| `VarkaExpressionCompiler.java`, `VarkaExpressionCompilerSuite.scala` | the rule, (b) or (c) only |

## 5. Tests, and what each is for

* `VarkaSplitFilterBaselineQueriesSuite`, `PinsDefaults`: every section on both sessions over
  the table's first 2000 rows, answers equal, and each section's class at both refusal settings.
  It catches a section that times the row path under the split's label.
* `VarkaDecimalBaselineQueriesSuite`, the fusion-class test: `SPLIT` for a value split, empty
  for a value split under a sort, `NULL_TESTS` unchanged. It catches `SPLIT` swallowing a shape
  that fits no class today.
* Under (b) or (c): `VarkaExpressionCompilerSuite`, the demotion at `useAVX` -1, 2 and 3 with
  its reason, and a value conjunct with nothing left over still fusing.
* Cost: the new suite under 20 s, its tests under 10 s. That is VARKA-379's measured 25 s for a
  sql/core suite of about 200 queries over cached views, scaled to about 30 queries, at its
  ratio of 0.5.

## 6. The measurement

The new class on the EPYC 7763 runners at the defaults and at `-XX:MaxVectorSize=16`, two runs a
width, compared by minimums (the arms tie within 1.3x in the predictions). The owner dispatches
it (`gh workflow run benchmark.yml -f class=VarkaSplitFilterBaselineBenchmark ...`). The
controls are read first: `u < 900` alone must win at both widths, or the run is the machine's.

### 6.1 Predictions, registered before the run

1. At 10% kept the int split wins at both widths, by at least 1.5x. The kernel drops nine rows in
   ten before the copy, and the row filter's hash runs on a tenth of the rows.
2. At 90% kept the int split is within 10% of the row path, either side. The kernel pass and the
   copy of nine rows in ten cost about what 598's null tests did. They lost by 59 to 83 ms on 2M
   rows (`results/VARKA-598-probe.txt`), against the 10% of the hash work the kernel saves.
3. Three columns out lose more than one at 90% kept, by at least 10% more.
4. The long splits at 256 bits read as the int splits within 15%. At 128 bits they are `NONE`
   and tie.
5. With one row in ten null every split reads within 10% of its no-null twin.

## 7. Risks

1. The residual may join a future fused family (a `hash` row). Then the section moves class
   and the check fails it, which is the intended alarm.
2. `u < 100` keeps 10% at both densities only if the null rows are not `u`'s low values. The
   `_n` twin nulls by `id % 10`, independent of the hash.
3. A rule (b) would decline a user's selective split. Plan time cannot tell, which is why the
   mixed case goes to the owner.

## 8. Sequencing

1. **601a** (about 450 lines): `Fusion.SPLIT`, the class, its queries suite, the fusion-class
   case. Done when `build/sbt 'sql/testOnly *VarkaSplitFilterBaselineQueriesSuite
   *VarkaDecimalBaselineQueriesSuite'` passes and a plan-only dry run
   (`-Dvarka.bench.dryRun=true`, as the other baselines take it) lists every section's class
   at both widths.
2. The owner's runner dispatch at both widths, two runs each.
3. **601b** (about 150 lines, or 300 with a rule): the results files and their provenance,
   section 9 scored, and the rule under (b) or (c) with its classes moved. Done when
   `dev/varka_quote_check.py` passes and, under (b) or (c), the runner rerun of the declined
   sections reads within 5% of 1.0X or above, by the minimums of two runs.

Done when (the row's): a baseline section times such splits on the EPYC 7763 at both widths, at
a high and a low share kept, against Spark's row path, and each shape that loses declines whole
to Spark's filter under a stated rule, with the baselines' classes following.

## 9. Outcome

Not built.

## 10. Questions for the owner

1. **The shares disagree.** If the 10% split wins and the 90% one loses, recommended: keep the
   split when the 90% loss is under 10% and the 10% win is over 1.3x, which needs the done-when
   amended to accept that loss; otherwise decline every value split (rule (b)). I cannot decide
   it: it trades a measured loss on dense data against a
   measured win on selective data, and the house rule "no fused shape slower than the row path"
   would decline. But that would give up wins whose size only the run will show.
2. **A baseline rather than a probe.** Recommended: the baseline (3.1). The owner's rows so far
   have taken either; 598 used a probe. It costs about 150 more lines in 601a.
