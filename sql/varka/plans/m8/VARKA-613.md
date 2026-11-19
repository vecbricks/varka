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
2. **The loss, current.** A fresh EPYC 7763 run of the class at 256 bits on master `e5d70012bdc`
   (run 44485236913, 19 November 2026, on branch `varka-598r-runner-256`, pull request #1550,
   not yet merged) reads the section at 0.72x without nulls and 0.78x with them, by its per-row
   times. Its `extract(DAY FROM dt)` alone wins 3.5x and 4.5x in the same run. So the fused entry
   is fast, and the loss is the partial plan. The 128-bit side was not rerun; nothing on master
   since #1534 changes the plan there.
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
   all-fused row-consumer case then also lost (0.8x). It now wins 3.5x (item 2), so the read-back
   got cheaper, but the partial shape did not. No other committed baseline section is `PARTIAL`.
5. **103b would close it another way.** Once `HOUR`, `MINUTE` and `SECOND` fuse, the section is
   `WHOLE`. 103b waits on rows 332b, 157 and 347 (`VARKA-103.md` 5, item 3). None of them is
   close to merging, so this row cannot wait for it.

The problem holds.

## 3. The design

### 3.1 A decline of a partial projection, chosen by a probe

The fix is a demotion in `VarkaExpressionCompiler.compilePartialUnobserved`. When the rule
fires, every fused entry becomes residual with a reason, `compilePartial` returns empty, and
`VarkaColumnarRule` leaves Spark's projection whole. Two rules are candidates, and a probe
chooses between them before the code (step 1):

* **(R1) Shared input.** Decline when every column the fused entries read is also read by a
  residual entry. The merge pass then reads and converts those columns anyway, so the kernel
  saves no read. This is 598's reasoning for filters: a second pass in front of a row pass that
  reads every row regardless. The blend fires (all four read `dt`). `date_add(d, 3), i, i + 1`
  does not.
* **(R2) Residual-heavy.** Decline when residual entries outnumber fused ones. This is simpler,
  but it ignores what the entries read and cost.

The probe, `VarkaPartialProjectionProbe`, is over the day-time baseline's table on the laptop
under `-XX:UseAVX=2` at `MaxVectorSize` 32 and 16, both sessions, best times, and its output is
committed as `results/VARKA-613-probe.txt`. It has four shapes:

The three arithmetic arms are `-dt`, `abs(dt)` and `dt * 3`, all over `dt` alone.

| shape | fused | residual | R1 | R2 |
|---|---|---|---|---|
| a. the blend | `DAY` | `HOUR`, `MINUTE`, `SECOND` of `dt` | declines | declines |
| b. other inputs | `DAY` of `dt` | `HOUR`, `MINUTE`, `SECOND` of `dt2` | keeps | declines |
| c. heavy, shared | the three arithmetic arms | `HOUR` of `dt` | declines | keeps |
| d. heavy, other | the three arithmetic arms | `make_dt_interval(i, i2, i3)` | keeps | keeps |

The rule chosen is the one whose "declines" column matches the shapes that lose in the probe.
If neither matches, the plan falls back to (R3): decline every partial projection under a row
consumer. A columnar consumer keeps it, since `VARKA-12.md` 6.3 measured partial fusion winning
there (1.5x). That fallback is the owner's to accept (question 10.1).

**Alternative: restructure so the fused part carries the work.** This means computing
`HOUR`, `MINUTE` and `SECOND` in the kernel, which is 103b's design (`VARKA-103.md` 2.3) and
blocked as in 2 item 5. A cheaper restructure, Spark's whole-stage projection over a columnar
kernel output, is the "assemble then read" form that `VARKA-12.md` 6.2 measured at 0.5x to 0.7x
and deleted. Neither is built here. When 103b lands, the blend becomes `WHOLE` and the rule
stops firing on it by itself.

### 3.2 What is deliberately unchanged

* Projections that fuse whole, and partial projections the chosen rule keeps.
* `VarkaProjectExec` under a columnar consumer, unless (R3) is taken.
* Filters (rows 598 and 601).

### 3.3 Registered op counts

None: no kernel's bytes change, and declined projections have no kernel.

## 4. Files

| file | what |
|---|---|
| `VarkaPartialProjectionProbe.java`, `results/VARKA-613-probe.txt` | the diagnosis, step 1 |
| `VarkaExpressionCompiler.java` | the demotion and its reason |
| `VarkaExpressionCompilerSuite.scala` | the rule at the three emit levels |
| `VarkaDayTimeIntervalBaselineBenchmark.java` | the blend's class, `PARTIAL` to `NONE` |
| `VarkaDayTimeIntervalBaselineQueriesSuite.scala` | the class and answers over the table |
| `VarkaDecimalBaselineQueriesSuite.scala` | its `PARTIAL` fusion-class case, if the rule moves it |

## 5. Tests, and what each is for

* `VarkaExpressionCompilerSuite`, at `useAVX` -1, 2 and 3: shapes a to d of the probe, each
  declined or kept as the chosen rule says, with the reason on every demoted entry; an all-fused
  projection unchanged. It catches a rule that fires on the all-fused case.
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
4. So (R1) matches the losses, with c as the shape that separates the rules. If c loses, (R2) is
   chosen.
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
2. Step 2: the demotion, its tests and the classes. Done when `build/sbt
   'catalyst/testOnly *VarkaExpressionCompilerSuite' 'sql/testOnly
   *VarkaDayTimeIntervalBaselineQueriesSuite *VarkaDecimalBaselineQueriesSuite'` passes.
3. Step 3: the regenerated golden corpus and fusion report. Done when
   `dev/varka_regen_generated.sh --all` leaves no diff.
4. Step 4: the owner's runner dispatch at both widths, and the results committed. Done when
   `dev/varka_quote_check.py` passes and the blend reads at least 1.0X at 256 and 128 bits.

Done when (the row's): the blend either declines whole (the row engine answers) or is
restructured so the fused part carries the work. The baseline section then shows it no slower
than the row path at 256 and 128 bits.

## 9. Outcome

Not built.

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
