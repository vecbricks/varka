# VARKA-613: No projection that fuses in part slower than the row path

## 1. Where this came from

Row 613 of `PLAN.md`, added on 14 November 2026 from the day-time baseline runs of row 103's
first part (#1534, `VARKA-103.md` 10), run 43582841112 at 256 bits and run 43582853857 at 128.
The section `extract DAY, HOUR, MINUTE and SECOND together` fuses in part: `extract(DAY FROM
dt)` fuses, and the other three wait for 103b (`VARKA-103.md` 5). The projection is then slower
than not fusing it at both widths.

## 2. The admission check, done

Read on master at `e5d70012bdc`, 20 November 2026, with the fresh runner run the lead pointed
to. No run was made for this plan.

1. **The loss, committed.** In
   `VarkaDayTimeIntervalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` the varka on arm
   reads 66.1 ns a row against 47.2 for the row path without nulls, and 67.4 against 49.9 with
   one row in ten null. In
   `VarkaDayTimeIntervalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt` it reads 94.4
   against 47.2, and 93.7 against 50.3.
2. **The fused entry is fast, so the loss is the partial plan.** In the same 256-bit file
   `extract(DAY FROM dt)` alone reads 3.9X without nulls and 4.9X with them. A fresh EPYC 7763
   run at 256 bits on master `e5d70012bdc` (run 44485236913 of pull request #1550, not yet
   merged) repeats the loss; its results file is not on master, so this plan quotes no number
   from it. Nothing on master since #1534 changes the section's plan at either width.
3. **Where the time goes, from the code.** `PartialVarkaProjection` classifies the four entries:
   one `FusedOutput` and three `ResidualOutput`. `VarkaColumnarToRowExec` runs the kernel for
   `DAY`. Then it builds each output row in `mergeProjection`, an `UnsafeProjection` over a
   `JoinedRow` of the input row and the fused row, which evaluates `HOUR`, `MINUTE` and `SECOND`
   per row. That projection is not whole-stage code, so it pays the per-row read-back that
   `VARKA-12.md` 6.2 measured. Spark's plan does all four extracts in one whole-stage loop. The
   kernel saves the row path one extract a row and adds a batch pass plus the merge's
   indirection.
4. **The record already had this shape.** `VARKA-12.md` 6.3 timed a residual-heavy projection
   (one fused entry, four residual) through a row consumer at 0.7x, in milestone 2. The
   all-fused row-consumer case then also lost (0.8x). It now wins 3.9x (item 2), so the read-back
   got cheaper, but the partial shape did not. No other committed baseline section is `PARTIAL`.
5. **103b would close it another way.** Once `HOUR`, `MINUTE` and `SECOND` fuse, the section is
   `WHOLE`. 103b waits on rows 332b, 157 and 347 (`VARKA-103.md` 5, item 3). None of them is
   close to merging, so this row cannot wait for it.

The problem holds.

## 3. The design

### 3.1 A decline of a partial projection, chosen by a probe

The fix is a decline in `VarkaColumnarRule`'s post stage, where `ColumnarToRowExec` over a
`VarkaProjectExec` becomes a `VarkaColumnarToRowExec` (`VarkaColumnarRule.scala`, the
`case ColumnarToRowExec(varka: VarkaProjectExec)` arm). Only there is the consumer known to want
rows. `VarkaExpressionCompiler.compilePartial` cannot see the consumer: the pre stage asks it
through `isVarkaEligible` before the transitions are inserted, so a demotion there would also
decline the columnar consumer's partial projection, which wins (`VARKA-12.md` 6.3, 1.5x). When the
rule fires, the arm builds Spark's `ProjectExec` over a plain `ColumnarToRowExec` of the child
instead, and the node's fusion report records the reason. The same check guards the post
stage's `ProjectExec ... if isVarkaEligible` arm, which builds a `VarkaColumnarToRowExec` for a
projection the pre stage did not see, over the very `ProjectExec(ColumnarToRowExec(child))`
shape the decline emits; there a declined projection stays the `project` it was. So no second
route or second pass re-fuses it. The narrowing arm needs no check: what it absorbs fuses
nothing. The rule reads the node's
`classification()`, the compile that already serves its `EXPLAIN`. Two rules are candidates, and a
probe chooses between them before the code (step 1):

* **(R1) Shared input.** Decline when every column the fused entries read is also read by a
  residual entry. The merge pass then reads and converts those columns anyway, so the kernel
  saves no read. This is 598's reasoning for filters: a second pass in front of a row pass that
  reads every row regardless. The blend fires (all four read `dt`). `date_add(d, 3), i, i + 1`
  does not.
* **(R2) Residual-heavy.** Decline when residual entries outnumber fused ones. This is simpler,
  but it ignores what the entries read and cost.

The probe, `VarkaPartialProjectionProbe`, is over the day-time baseline's table on the laptop
under `-XX:UseAVX=2` at `MaxVectorSize` 32 and 16, both sessions, best times, and its output is
committed as `results/VARKA-613-probe.txt`. The rule is chosen from the 256-bit shapes. At 128
bits the checked arithmetic arms of shapes c and d carry row 612's two-lane loss (`abs(dt)` and
`dt * 3` at 0.5X to 0.9X in the committed 128-bit file), which is not the partial plan's, so
their 128-bit readings are recorded but do not choose the rule; shapes a and b have no check and
are read at both widths. It has four shapes:

The three arithmetic arms are `-dt`, `abs(dt)` and `dt * 3`, all over `dt` alone.

| shape | fused | residual | R1 | R2 |
|---|---|---|---|---|
| a. the blend | `DAY` | `HOUR`, `MINUTE`, `SECOND` of `dt` | declines | declines |
| b. other inputs | `DAY` of `dt` | `HOUR`, `MINUTE`, `SECOND` of `dt2` | keeps | declines |
| c. heavy, shared | the three arithmetic arms | `HOUR` of `dt` | declines | keeps |
| d. heavy, other | the three arithmetic arms | `make_dt_interval(i, i2, i3)` | keeps | keeps |

A shape counts as a loss or a win only when its ratio to the row path is past 1.3x either way
(risk 1); between, it counts for neither. A rule matches when it declines every shape that
loses and keeps every shape that wins. If both match, (R1) is taken: it declines only where the
merge pass reads the kernel's inputs anyway, the cause 2 item 3 names, while (R2) only counts
entries. If neither matches, the plan falls back to (R3): decline every partial projection
under a row consumer. All three rules sit in the post stage's row-consumer arms, so a columnar
consumer keeps its partial projection under any of them. That fallback is the owner's to accept
(question 10.1).
**Alternative: restructure so the fused part carries the work.** This means computing
`HOUR`, `MINUTE` and `SECOND` in the kernel, which is 103b's design (`VARKA-103.md` 2.3) and
blocked as in 2 item 5. A cheaper restructure, Spark's whole-stage projection over a columnar
kernel output, is the "assemble then read" form that `VARKA-12.md` 6.2 measured at 0.5x to 0.7x
and deleted. Neither is built here. When 103b lands, the blend becomes `WHOLE` and the rule
stops firing on it by itself.

### 3.2 What is deliberately unchanged

* Projections that fuse whole, and partial projections the chosen rule keeps.
* `VarkaProjectExec` under a columnar consumer, under every rule.
* Filters (rows 598 and 601).

### 3.3 Registered op counts

None: no kernel's bytes change, and declined projections have no kernel.

## 4. Files

| file | what |
|---|---|
| `VarkaPartialProjectionProbe.java`, `results/VARKA-613-probe.txt` | the diagnosis, step 1 |
| `VarkaColumnarRule.scala` | the decline in the post stage, and its reason |
| `VarkaPartialDeclineSuite.scala`, new | the rule's plans under a row and a columnar consumer |
| `VarkaDayTimeIntervalBaselineBenchmark.java` | the blend's class, `PARTIAL` to `NONE` |
| `VarkaDayTimeIntervalBaselineQueriesSuite.scala` | the class and answers over the table |
| `VarkaDecimalBaselineQueriesSuite.scala` | its `PARTIAL` fusion-class case, if the rule moves it |

## 5. Tests, and what each is for

* `VarkaPartialDeclineSuite` (sql/core, new), at `useAVX` -1, 2 and 3: shapes a to d of the
  probe under a row consumer, each planned as Spark's projection or as `VarkaColumnarToRowExec`
  as the chosen rule says, with the reason in the fusion report; a declined shape handed to the
  post stage as `ProjectExec(ColumnarToRowExec(scan))`, and the post stage run twice, stays
  Spark's projection; the same shapes under a
  columnar consumer keep their `VarkaProjectExec`; an all-fused projection is unchanged. It
  catches a rule that fires on the all-fused case or reaches the columnar consumer.
* `VarkaDayTimeIntervalBaselineQueriesSuite`: the blend lists `NONE` at both refusal settings,
  and both sessions answer alike over the first 2000 rows.
* `VarkaDecimalBaselineQueriesSuite`'s fusion-class tests: `x9 < y9, -x38 < y38` reads two
  different column pairs, so (R1) keeps it `PARTIAL`. (R2) ties at one each and keeps it too.
  The test is unchanged under both. It catches a rule broader than chosen.
* The golden corpus and `VarkaFusionReport`'s lines move where a recorded projection was partial
  with shared inputs. They are regenerated, and the diff is read entry by entry.

## 6. The measurement

The probe, step 1, decides the rule (laptop, diagnostic only). The done-when's evidence is the
day-time baseline rerun on the EPYC 7763 runners at both widths after the change, dispatched by
the owner. The controls are `extract(DAY FROM dt)` alone and the `CASE WHEN` control, which the
rule does not touch.

### 6.1 Predictions, registered before the run

1. In the probe, shape a loses at both widths (as the runners show), and shape c wins at 256
   bits by at least 1.3x, since three fused arithmetic entries carry most of the row path's work.
2. Shape b is within 10% of the row path at 256 bits. The kernel reads `dt`, which the merge
   pass then does not read, but the pass still converts `dt2`.
3. Shape d wins at 256 bits by at least 1.3x.
4. So (R2) matches, with c as the shape that separates the rules: R1 declines c, R2 keeps it.
   If c loses, (R1) is chosen. Shape b is where R2 declines and R1 keeps: inside the 1.3x band
   it counts for neither rule, and if it loses past it, that is evidence for (R2).
5. After the change the blend reads 1.0X on the runners within 5% at both widths and densities:
   its plan is Spark's.

## 7. Risks

1. The probe runs on a laptop shared with other agents' JVMs. Arms are read against each other
   within a run, by best time, as `results/VARKA-598-probe.txt` was. A shape whose ratio is
   under 1.3x is not used to choose the rule.
2. A rule chosen on day-time shapes may misjudge other families' partial projections (decimal,
   floating). The census of partial plans in the golden corpus (5) lists every one it moves, for
   the review to read.
3. 103a2 or 103b may land first and fuse more of the blend. Then the section's class moves under
   this change's feet. The class check fails the run in either order, which is the alarm.

## 8. Sequencing

One pull request, about 350 lines.

1. Step 1: the probe and its committed output. Done when `results/VARKA-613-probe.txt` holds
   shapes a to d at both widths, and this plan records the rule chosen.
2. Step 2: the decline, its tests and the classes. Done when `build/sbt 'sql/testOnly
   *VarkaPartialDeclineSuite *VarkaDayTimeIntervalBaselineQueriesSuite
   *VarkaDecimalBaselineQueriesSuite'` passes.
3. Step 3: the regenerated golden corpus and fusion report. Done when
   `dev/varka_regen_generated.sh --all` leaves no diff.
4. Step 4: the owner's runner dispatch at both widths, and the results committed. Done when
   `dev/varka_quote_check.py` passes and the blend reads within 5% of 1.0X or above, by the
   minimums of two runs, at 256 and 128 bits.

Done when (the row's): the blend either declines whole (the row engine answers) or is
restructured so the fused part carries the work. The baseline section then shows it no slower
than the row path at 256 and 128 bits.

## 9. Outcome

Started 20 November 2026, while the owner was away; steps 1 and 2 are built, steps 3 and 4 are
owed (below).

### 9.1 Step 1, the probe

`VarkaPartialProjectionProbe` ran on the laptop before the change, three passes of each null
density: run 1 at 256 and at 128 bits, then run 2 at 256 bits. Its output is
`results/VARKA-613-probe.txt`. The laptop was shared at a one-minute load of 30 to 60 (about 40 at
the start of run 1, about 60 at the end of run 2, the file gives each), far busier than 598's
probe.

**The control decides which passes count.** `extract(DAY FROM dt)` alone is committed at 3.9X on
the runners, but it lost in the no-null pass of every run (0.8X, 0.9X, 0.8X), and won in every
nulls pass (2.6X, 1.4X, 2.1X). A pass whose control loses is not measuring the kernel, so only
the nulls passes are read. This drops c's 2.2X in run 1's no-null pass, which favours (R2), as
well as both readings that contradict it: a at 1.3X in run 1 and b at 1.6X in run 2, each in a
pass whose row arm read far slower than the same query at the other density (301 against 103 ms,
506 against 289).

Best times, ms, row path then varka on, the counted passes:

| shape | run 1, 256 bits | run 2, 256 bits | run 1, 128 bits |
|---|---|---|---|
| a. the blend | 103 / 153, loss | 395 / 426, band | 305 / 394, band |
| b. other inputs | 128 / 136, band | 289 / 351, band | 281 / 402, loss |
| c. heavy, shared | 118 / 81, win | 328 / 296, band | (row 612's loss) |
| d. heavy, other | 285 / 183, win | 464 / 515, band | (row 612's loss) |

The 128-bit no-null pass is not read either (its control read 0.9X); there a and b read 0.8X and
0.5X, losses for both.

**The rule is (R2), residual-heavy.** (R1) declines c, which wins, and keeps b, which loses at
128 bits, so it fails twice. (R2) declines a and b and keeps c and d, which fits every counted
reading and the runners' committed loss of the blend at both widths. The rule is
`VarkaPartialDecline.reason`: decline when the residual entries outnumber the fused ones, the
entries of every kernel together, with forwarded columns counted as neither.

Predictions of 6.1, scored on the counted passes:

1. Shape a loses at both widths: **half right**. It read 0.7X at 256 bits in run 1 and stayed in
   the band in run 2 and at 128 bits. Shape c wins at 256 bits by 1.3x: **right** in run 1
   (1.5X), in the band in run 2.
2. Shape b within 10% of the row path at 256 bits: **right** in run 1 (6%), **wrong** in run 2
   (21% slower, still inside the 1.3x band).
3. Shape d wins at 256 bits by 1.3x: **right** in run 1 (1.6X), in the band in run 2.
4. (R2) matches, c separates the rules: **right**.
5. The blend at 1.0X on the runners: owed (step 4).

### 9.2 Step 2, the decline

Built as 3.1 says, with the reason in `VarkaPartialDecline` rather than a fusion report
(decisions, item 1). `VarkaColumnarRule`'s post stage asks it in the
`ColumnarToRowExec(VarkaProjectExec)` arm, which then plans `ProjectExec(ColumnarToRowExec(child))`,
and in the `ProjectExec ... if isVarkaEligible` arm, which then leaves the projection as it is.
The blend's class in `VarkaDayTimeIntervalBaselineBenchmark` is `NONE` at both densities.
`VarkaDecimalBaselineQueriesSuite`'s `x9 < y9, -x38 < y38` ties one to one and stays `PARTIAL`, so
that suite is unchanged. Only `build/sbt sql/Test/compile` was run in this window; the suites of
step 2's done-when are owed.

One quick run of `VarkaPartialDeclineSuite` passed four of its five tests. The fifth compared
the declined plan with the plan of the shared Varka-off session, `disabledSpark`, and failed
there: that session reads the Arrow cache as rows (`Project` over `InputAdapter`, no
`ColumnarToRow`), while the declined plan reads it as batches. The test now asserts the declined
plan's operators instead and has not been run since. Prediction 5 rests on the benchmark's
varka off arm reading batches too, as its `VarkaArrowSessions` session sets the vectorized cache
reader; the owed plan-only dry run of the baseline is where to check it.

### 9.3 Where the plan was wrong

1. 3.1's procedure has no rule for a failed control or for a shape that reads both ways in two
   passes. The control rule above is the one this probe needed; a later probe should state it
   before it runs.
2. Step 3's done-when, `dev/varka_regen_generated.sh --all` leaving no diff, does not cover the
   golden corpus, which has its own switch (`skills/working-in-this-repo.md`, the `--all`
   section): `VARKA_GOLDEN_REGEN=true build/sbt -batch "sql/testOnly *VarkaGoldenCorpusSuite"`.
3. 3.1's "the node's fusion report records the reason" cannot hold for a declined plan, which
   has no Varka node (decisions, item 1).

### 9.4 Owed

* Step 2's suites: `VarkaPartialDeclineSuite`, `VarkaDayTimeIntervalBaselineQueriesSuite`,
  `VarkaDecimalBaselineQueriesSuite`.
* Step 3: the golden corpus regenerated with its own switch, then `dev/varka_regen_generated.sh
  --all`, and both diffs read entry by entry.
* Step 4: the owner's runner dispatch of the day-time baseline at both widths, and beside it
  `VarkaPartialProjectionProbe` on the EPYC 7763 at both widths, which confirms (R2) on a quiet
  machine or widens it. The blend declines under all three rules, so the row's done-when does not
  depend on that second run.

## Decisions taken while the owner was away (20 November 2026)

1. **Where the reason lives.** A declined projection has no Varka node, so no fusion report can
   carry the reason as 3.1 says. The rule is one Java method, `VarkaPartialDecline.reason`,
   which returns the reason or nothing; both post-stage arms call it, the rule logs the reason at
   DEBUG with the projection (as it logs a filter it leaves to Spark), and
   `VarkaPartialDeclineSuite` reads the same method. Reason: it is the smallest place that both
   the rule and a test can read, and it adds no plan node or tag that Spark's `EXPLAIN` would not
   show anyway.
2. **A forced residual is never declined.** `forceResidualAt` (VARKA-296) makes an entry
   residual so that the Spark differential and the composition fuzzer run the merge path. A
   forced residual can tip a projection's count, so under the decline a forced shape could lose
   its Varka node, and the differential would count the force as not applied instead of checking
   the merge. So the rule returns nothing while the option is set. Reason: the option is a fault
   injector that no user configuration reaches, and the merge path still runs for every partial
   projection under a columnar consumer and every one the rule keeps.
3. **The rule is Java, the arms Scala.** The classification is a sealed `VarkaOutputSpec`
   matched exhaustively, which is the house style for the IR (AGENTS.md, "Java code"); the rule
   object stays Scala because it is a Catalyst rule.
4. **The probe's passes are read by their control, and the rule is (R2).** The laptop could not
   be made quiet in this window, and a third run under the same load would not have been
   cleaner. 9.1 gives the control rule and the reading. (R3) was not taken: it would decline c
   and d, which never lose past 1.3x at 256 bits in any reading. Question 10.1 is therefore moot.

## 10. Questions for the owner

1. **The fallback rule (R3), if the probe matches neither (R1) nor (R2).** Recommended: accept
   it. It declines every partial projection under a row consumer and keeps the columnar one.
   That is the broadest rule, but it rests on two measurements a milestone apart (`VARKA-12.md`
   6.3 and this row's). I cannot decide it because it gives up partial fusion for every row
   consumer. That is the engine's main plan shape, and a scope call.
2. **Taking the decline while 103b is pending.** Recommended: yes. The rule is general and stays
   useful after 103b, and the blend is the only committed loss. The alternative is to leave the
   section losing until 103b, which the owner's "no fused shape slower than the row path" rules
   out.
