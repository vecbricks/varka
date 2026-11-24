# VARKA-612: No day-time interval arm slower than the row path at two 64-bit lanes

## 1. Where this came from

Row 612 of `PLAN.md`, added on 14 November 2026 from the day-time baseline runs of row 103's
first part (#1534, `VARKA-103.md` 10). Run 43582841112 at the defaults (256 bits) and run
43582853857 at `-XX:MaxVectorSize=16` (128 bits), both on an EPYC 7763, are committed as
`VarkaDayTimeIntervalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and its 128-bit twin.
At 256 bits the fused day-time arms win 3.9x to 4.4x. At 128 bits the same kernels are at parity
or slower.

## 2. The admission check, done

Read on master at `e5d70012bdc`, 20 November 2026. No run was made for this plan.

1. **The losses, by the committed file.** In
   `VarkaDayTimeIntervalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt` the varka
   on arm reads, without nulls, 47.2 ns a row against 48.4 for `dt + dt2 - dt` (1.0X), 52.8
   against 44.3 for `abs(dt)` (0.8X) and 43.7 against 38.4 for `dt * 3` (0.9X). With one row in
   ten null it reads 80.0 against 52.8, 92.3 against 43.3 and 60.2 against 38.8 (0.7X, 0.5X,
   0.6X). `extract(DAY FROM dt)`, which has no check, is not among the losses, and neither is
   the `CASE WHEN dt > dt2` control, at 1.6X and 1.2X in the same file. The four-extract blend
   is row 613's. The no-null ratios are one run each and under the 1.3x a claim needs
   (`sql/varka/AGENTS.md`); `dt * 1.5`, which plans the row path on both arms, reads 0.8X in the
   same file. So the admission rests on the null-density losses, 0.5X to 0.7X, and the no-null
   ones are directions only.
2. **The cause is the overflow check, and master already declines it for `BIGINT`.**
   `VarkaNodeCompiler.twoLaneChecked` (row 104, #1484) declines a checked operation with
   `TWO_LANE_CHECK_REASON` where `DeclineSink.declinesTwoLaneChecks()` holds: x86 at two 64-bit
   lanes, where the overflow test is a long-lane mask that C2 does not lower (`VARKA-503.md` 1).
   It is called only where `isLong(whole)` holds, which is `DataTypes.LongType.equals(...)`. The
   day-time arms are in `VarkaIntervalCompiler`: `dayTimeArith` (`ADD`/`SUB` in `FAIL`), `-dt`
   and `abs(dt)` (`IntNeg` in `FAIL`), and `dayTimeMultiply` (a `GuardedRange` over
   `dayTimeProductBounds` for a literal factor, then a wrapping product; a column factor
   declines). They build the same nodes on the same lane, but
   their type is `DayTimeIntervalType`, so they never reach `twoLaneChecked`. The `BIGINT` forms
   are declined at 128 bits (they read 1.0X with the session's decline,
   `VarkaBigintArithmeticBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`). The
   day-time ones are not.
3. **The same gap elsewhere.** These also build a long-lane `IntArith` in `FAIL`, or a
   `GuardedRange`, outside `twoLaneChecked`. `VarkaTimeCompiler`: `TIME` minus `TIME`, `TIME`
   plus or minus a day-time interval, and the `TIMESTAMP_NTZ` shifts of row 339 (#1442).
   `VarkaDecimalCompiler`'s checked long-decimal forms are a third, whose modes are passed as
   values (**[I]**: not traced site by site). No committed 128-bit runner file times any of them
   fused. The `TIMESTAMP_NTZ` arithmetic and integral-cast baselines at 128 bits are from before
   their rows fused (provenance commits `35d74425c70` and `451066f5154`). So the reasoning covers
   them, but no number does.
4. **What the record says about a decline at two lanes.** Row 503 measured blends and plain
   arithmetic winning at two lanes on the laptop (`VARKA-503.md` 2), so a decline of every
   long-driven projection would give up wins: the `CASE WHEN` control wins at 128 bits on the
   runner too. The losses are the checked forms only.

The problem holds on master.

## 3. The design

### 3.1 612a: the decline, by the tree rather than by the arm

`VarkaKernelPlan.hostRefusal` already refuses a 64-bit-driven condition root at two lanes. It
gains a third case: a projection root whose tree holds a raising or nulling check on a 64-bit
lane, `Kind.TWO_LANE_CHECK`. That is an `IntArith` or `IntNeg` in `FAIL` or `NULL` whose lane is
`LONG`, or a `GuardedRange` whose child is on the `LONG` lane. A walk like `readsDecimal` finds
it. A refused entry leaves the projection's other entries to fuse. The reason reuses the text of
`TWO_LANE_CHECK_REASON`, which moves to `VarkaKernelPlan` without the word "bigint".

`twoLaneChecked` in `VarkaNodeCompiler` stays, so `EXPLAIN` keeps naming the declined
subexpression for a `BIGINT` operation. The tree check is the net that covers every family,
including ones not written yet.

**Alternative: extend `twoLaneChecked` to each arm.** `VarkaIntervalCompiler`'s four arms and
`VarkaTimeCompiler`'s, per site. It is smaller per arm and keeps the reason on the
subexpression. But it misses the next family that builds a check (the decimal forms are already
unclear, 2 item 3). The tree check is chosen. The owner may prefer the per-arm form
(question 10.1).

### 3.2 612b: a check without a mask at two lanes, measured against the decline

The done-when also allows "a width-conditional lowering". A `FAIL` check only has to send the
batch to the row engine. It does not mark lanes. So at two 64-bit lanes it can be tested
without a mask: for `x + y = r`, `t = (x ^ r) & (y ^ r)`, and for `x - y = r`,
`t = (x ^ y) & (x ^ r)`; then `t.reduceLanes(OR) < 0` as a scalar branch to the decline. For
negation and `abs`, the lane equal to `Long.MIN_VALUE`. For a `GuardedRange`, an unsigned compare by
the borrow of `(hi - lo) - (x - lo)`. A `FAIL` product on the long lane (`l * m` in `ANSI`)
has no mask-free test here, so the tree check still declines it under `REDUCE`. A null lane's
slot may hold any value, so the test can fire falsely there. That sends the batch to the row path,
which answers the same rows, so a false alarm costs time and never correctness. `NULL` mode needs
lanes marked and stays declined.

This is a narrow piece of `m9/SCOPE.md` item 101 (a mask-free lowering at two lanes). It goes
behind a `VarkaEmitOptions` component, `twoLaneChecks` (`MASK`, today's, and `REDUCE`). It is
admitted in place of 612a's decline only where `REDUCE` is set. It flips the default only if it
beats the row path at 128 bits on the runners. Whether to build it in this row or leave it to
item 101 is the owner's call (question 10.2).

### 3.3 What is deliberately unchanged

* Projections at two lanes with no check: blends, `LEGACY` arithmetic, `extract(DAY FROM dt)`.
* Filters, already declined by row 503.
* Every host with four or more 64-bit lanes, and aarch64.

### 3.4 Registered op counts

612a changes no kernel's bytes: the refused shapes have no kernel at two lanes. For 612b, from
`dev/varka_emit.sh --table --variant twoLaneChecks=REDUCE "dt + dt2" "abs(dt)" "dt * 3"
"l + l2"` at `lanesOverride=2`, pasted before its measurement.

## 4. Files

| file | what |
|---|---|
| `VarkaKernelPlan.java` | `Kind.TWO_LANE_CHECK`, the tree walk, the reason |
| `VarkaNodeCompiler.java` | the reason's constant moved |
| `VarkaExpressionCompilerSuite.scala` | its two asserts on the constant, at its new home |
| `docs/sql-varka.md`, `width_audit.json` | the reason's new text, regenerated |
| `VarkaDayTimeIntervalBaselineBenchmark.java` | the arms' classes at two lanes |
| `VarkaDayTimeIntervalBaselineQueriesSuite.scala` | the classes at both refusal settings |
| `VarkaTwoLaneDefault128Suite.java` | the decline at the 128-bit defaults |
| 612b: `VarkaEmitOptions.java`, `VarkaEmitOption.java`, `VarkaVectorWalk.java` | `twoLaneChecks` |

## 5. Tests, and what each is for

* `VarkaTwoLaneConditionSuite`, beside `hostRefusal`'s existing cases: each check kind on the
  long lane is refused with the reason at `twoLaneMasksFallBack=true` and admitted at `false`. A
  `WRAP` arithmetic, a blend and an int-lane `FAIL` add are admitted at both. It catches a walk
  that refuses too much (the blends that win) or too little.
* `VarkaDayTimeIntervalBaselineQueriesSuite`: `dt + dt2 - dt`, `abs(dt)` and `dt * 3` list
  `NONE` where long conditions decline and `WHOLE` elsewhere, and both sessions answer alike.
* `VarkaTwoLaneDefault128Suite`: `dt + dt2`, `abs(dt)`, `t + dt` over `TIME`, and a
  `TIMESTAMP_NTZ` shift have no Varka node at the 128-bit defaults, and their `EXPLAIN` names
  the reason. It catches the other families of 2 item 3.
* A coverage census at both settings: every coverage row compiled with the refusal on and off,
  the refused list printed into `results/VARKA-612-refused.txt`. The plan records the list, so
  the reasoning's reach is a file and not a guess.
* 612b: `VarkaEmitterArithmeticSuite` and `VarkaDayTimeIntervalConformanceSuite` cells at
  `lanesOverride=2` for the overflow edges (`MAX + 1`, `MIN + (-1)`, `MIN - 1`, `MAX - (-1)`,
  `abs(MIN)`, the product bounds plus and minus one) under `REDUCE`, against the row engine. A null
  lane holding an overflowing value must answer as the row engine does.

## 6. The measurement

612a needs no new benchmark. The day-time baseline at 128 bits on the runners after it shows
the three arms at the row path's speed (they no longer fuse). 612b reruns the same class at
`-XX:MaxVectorSize=16` with `twoLaneChecks=REDUCE` through `extra-java-options`, two runs,
compared by minimums. The owner dispatches both. The controls are the `CASE WHEN` section and
`extract(DAY FROM dt)`, which neither change touches.

### 6.1 Predictions, registered before the run

1. After 612a the three arms read 1.0X at 128 bits within 20%, with and without nulls, by the
   minimums of two runs: the plans are the row path's, and 20% is the spread an unfused section
   already shows in one run (`dt * 1.5` at 0.8X).
2. 612a moves no 256-bit number by more than the run's noise, since no 256-bit plan changes.
3. Under 612b the three arms beat the row path at 128 bits without nulls by at least 1.3x. The
   check becomes four lane operations and a reduction, against a Java mask loop.
4. Under 612b with one row in ten null they win by less than without, at least 1.1x. The masked
   body's other masks (the validity) still run at two lanes.
5. The census of 2 item 3 refuses between 10 and 40 coverage rows at two lanes. Every one is
   `ANSI` or `TRY` arithmetic, a guard on a long lane, or a composition of one.

## 7. Risks

1. The tree walk may refuse a `GuardedRange` added for a column bound (VARKA-379's stored-values
   guard), which folds a min and a max rather than making a mask. The walk excludes the guard
   that `Analysis.guardsStoredValues` reads that way. The census shows whether any is left.
2. The refusal moves `emitted_bytes.json` nowhere, since the compiler overloads without the
   flag refuse nothing (`VARKA-503.md` 3.1). A golden-corpus file written under the 128-bit
   defaults would move; `VarkaTwoLaneDefault128Suite` is the one that runs there.
3. Under 612b a column whose null slots hold overflowing garbage would send every batch to the
   row path. Spark's Arrow writer leaves null slots zeroed (**[I]**), so the cached table's
   batches do not, but another source's might.

## 8. Sequencing

1. **612a** (about 250 lines). Step 1: the tree check and its suite. Done when
   `build/sbt 'catalyst/testOnly *VarkaTwoLaneConditionSuite *VarkaExpressionCompilerSuite'`
   passes and `dev/varka_regen_generated.sh --all` leaves no diff. Step 2: the census file and
   the 128-bit default suite. Done when `build/sbt 'sql/testOnly *VarkaTwoLaneDefault128Suite
   *VarkaDayTimeIntervalBaselineQueriesSuite'` passes, and the baseline's plan-only dry run
   (`-Dvarka.bench.dryRun=true -XX:MaxVectorSize=16`) lists the three arms `NONE`. Step 3: the
   owner's 128-bit runner dispatch and the results committed. Done when
   `dev/varka_quote_check.py` passes and, by the minimums of two runs, no day-time arm but the
   blend (row 613) reads under 1.0X by more than prediction 1's 20%, the spread an unfused
   section already shows.
2. **612b** (about 400 lines, if the owner takes it here). Step 4: the option, the mask-free
   test and the cells. Done when `build/sbt 'catalyst/testOnly *VarkaEmitterArithmeticSuite
   *VarkaEmitOptionSuite'` passes and the width audit under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` at
   128 bits lists no fallback for the three shapes under `REDUCE`. Step 5: the runner rerun.
   Done when 6.1's predictions 3 and 4 are scored here and the default is set by them.

Done when (the row's): the day-time arms, and the other masked long-lane projections the same
reasoning covers, either decline at two 64-bit lanes as the two-lane condition (row 503)
declines filters, or a width-conditional lowering is built and measured. The matrix and the
128-bit baseline then show no row slower than the row path.

## 9. Outcome

### 9.1 612a, built on 20 November 2026 (branch `varka-612-daytime-two-lanes`)

Built locally while the owner was away; nothing pushed, no suite but the one targeted run below
was run, and no runner was dispatched.

* `VarkaKernelPlan`: `Kind.TWO_LANE_CHECK`, `TWO_LANE_CHECK_REASON` (moved from
  `VarkaNodeCompiler`, the word "bigint" dropped) and `holdsTwoLaneCheck`, a walk with a `seen`
  set like `readsDecimal`, asked by `hostRefusal` for every root that is not a condition. It
  refuses an `IntArith` or `IntNeg` in `FAIL` or `NULL` whose lane is `LONG`, and a
  `GuardedRange` whose child is on the `LONG` lane, in either mode.
* `VarkaNodeCompiler.twoLaneChecked` stays and names the moved constant, so `EXPLAIN` still names
  the declined `BIGINT` operation.
* The javadocs that list what the two-lane setting refuses (`VarkaAdmission`,
  `VarkaExpressionCompiler`'s `compilePartial` and entry pass) name the new case.
* Tests written: `VarkaTwoLaneConditionSuite` (two tests: each check kind refused and admitted,
  and the wrapping, blend and int-lane forms admitted on both settings);
  `VarkaExpressionCompilerSuite` (the constant's new home, and the `TIMESTAMP_NTZ` difference,
  which master's test pinned as untouched, now refused with the reason);
  `VarkaDayTimeIntervalBaselineBenchmark` and its queries suite (the three checked arms `NONE`
  under the refusal); `VarkaTwoLaneDefault128Suite.longLaneChecksDecline` (`dt + dt2`,
  `abs(dt)`, `t + dts` over `TIME` and `ntz + dt` declined with the reason beside a fused
  blend, at both densities, answers checked against Varka off, the reason in the formatted
  `EXPLAIN`); and the census, a test of `VarkaCoverageSuite` that renders
  `results/VARKA-612-refused.txt` (written only by `VARKA_COVERAGE_REGEN=true`, so the file is
  owed with the regeneration).

**Run.** `build/sbt catalyst/Test/compile sql/Test/compile` compiles; the one targeted run,
`build/sbt 'catalyst/testOnly *VarkaTwoLaneConditionSuite *VarkaExpressionCompilerSuite'`,
passed: 192 tests, 0 failed (20 November 2026, laptop). Every other suite named in 5 and 9.2 is
owed.

**Where the plan was wrong, or silent.**

0. 2 item 3 left the decimal forms untraced (**[I]**). Read now: `VarkaDecimalCompiler` builds
   every long-lane `IntArith` and `IntNeg` in `WRAP` and no `GuardedRange` (its checks are the
   slot refusals of the decimal store), so the walk refuses no decimal projection and question
   10.1's "decimal" reach is empty. The census file is the check of that reading.

1. The plan's file table lists only the day-time suites, but the walk reaches every baseline
   whose queries suite checks its sections' classes under both refusal settings
   (`forEachTwoLaneRefusal`). See 9.2 for the ones changed and the ones owed.
2. `VarkaExpressionCompilerSuite` pinned `SubtractTimestamps(ntz, ntz)` as untouched by the
   two-lane setting. Under the tree check it is refused, as 2 item 3 predicted; the assert is
   rewritten, not removed.
3. The emitter has one more long-lane guard outside the three node kinds: the bounded `L2D` of a
   `ConvertLane` (`VarkaConversionLowering`, `emitRangeGuard` over the long child). It is not
   added to the walk: the conversions have their own refusal (`Kind.CONVERSION`,
   `VarkaEmitOptions.convertsFallBack`), and row 584 measures them.

### 9.2 The baselines the walk reaches, read from the compilers

The walk reaches past the day-time arms, so every baseline whose queries suite checks its
sections' classes under both refusal settings was read against it (a static reading of the
compilers, no run). Nine sections in four more classes hold a long-lane check, each `WHOLE`
today and `NONE` under the refusal at both densities, and their classes now depend on it:

| class | sections | the check |
|---|---|---|
| `VarkaIntegralCastBaselineBenchmark` | `CAST(dt AS BIGINT)` | `GuardedRange` over `dt` (`VarkaTimeCompiler.dayTimeToLong`) |
| `VarkaTimestampNtzFieldsBaselineBenchmark` | `make_timestamp_ntz(d, tm)`, `CAST(tm AS TIMESTAMP_NTZ)` | the midnight plus the time, `FAIL` |
| `VarkaTimestampNtzBaselineBenchmark` | the arithmetic chain | the difference and the shift, `FAIL` |
| `VarkaTimestampNtzArithmeticBaselineBenchmark` | the chain, `ntz + dt`, `timestampadd(HOUR, q, ntz)` | `FAIL` adds and subtracts |

The three classes whose `sections()` took no refusal take it now, their runs resolve it from the
Varka session as `VarkaTimestampNtzBaselineBenchmark` already did, and their queries suites check
each setting's list. Judged unaffected on the reading: the `DOUBLE` divisions (their checks are
on the `DOUBLE` lane), the month shift of a `TIMESTAMP_NTZ` (its guard is on the int day), the
`TIMESTAMP_NTZ` fields and the decimal `TIME` outputs (wrapping or `ConstDivide` only), and the
math, rounding, floating, floating-cast and boolean-output baselines. This reading is the
prediction the owed runs of those suites score; the census file is its exhaustive form.

*23 November 2026, the owner's decision (8a), superseding the tree walk above:* the refusal is
narrowed to the family measured slower at 128 bits, the checked day-time interval arithmetic:
`VarkaIntervalCompiler.twoLaneMeasured` declines `dt + dt2`, `dt - dt2`, `abs(dt)` and `dt * k`
where a long-lane check is left in their tree, beside master's checked `BIGINT` operation
(VARKA-104). The kernel plan refuses no check any more; `VarkaKernelPlan.holdsLongLaneCheck` is
the walk the compiler asks. The other long-lane checks keep fusing unmeasured: `-dt`, `TIME`'s
and `TIMESTAMP_NTZ`'s (`t + dts`, `ntz + dt`, `ntz - ntz2`, `timestampadd`), the ANSI and try
narrowing casts of a `BIGINT` (`CAST(l AS INT)`), `d - d2`, `CAST(d AS TIMESTAMP_NTZ)` and
`CAST(dt AS BIGINT)`. The four baselines above keep master's classes. The census file now lists
13 rows (it listed 46). Owed, item 9.4: their 128-bit measurement.

### 9.4 Owed: the 128-bit measurement of the unmeasured long-lane checks

No existing section times the shapes the narrowed refusal leaves fusing unmeasured, so the
measurement is a follow-up row: first new sections, then two runs of
`benchmark.yml` with `-XX:MaxVectorSize=16` on an EPYC 7763, compared by minimums, which the
owner dispatches.

| shape | class to add it to |
|---|---|
| ANSI `CAST(l AS INT)`, `try_cast(l AS INT)` | `VarkaIntegralCastBaselineBenchmark` (beside its ANSI and try narrowing sections, over `l`) |
| `timestampadd(MONTH, <bigint>, ntz)` | `VarkaTimestampNtzArithmeticBaselineBenchmark`, with a `BIGINT` quantity column beside `q` |
| `CAST(d AS TIMESTAMP_NTZ)` | `VarkaTimestampNtzFieldsBaselineBenchmark` (it has a `DATE` `d`) |
| `d - d2` over dates | a second `DATE` column there, or the surface driver's date table (`d`, `d2`) |

The other unmeasured checks already have sections: `CAST(dt AS BIGINT)`, the `TIMESTAMP_NTZ`
chains, `ntz + dt` and `timestampadd(HOUR, q, ntz)` in the four classes of 9.2, which the same
runs time; `-dt` and `t + dts` get sections with them. A family whose fused arm is slower than
the row path at 128 bits joins `twoLaneMeasured` in its own change. The same dispatch reruns `VarkaDayTimeIntervalBaselineBenchmark` at 128 bits: its committed file still says the three
checked sections fuse, and under the refusal both arms time the row path.

Row 103a2 adds `dt * 1.5`, `dt / 1.5` and `dt / k`. On a two-lane host they fuse unmeasured, as
decision 8a leaves them, and join this measurement. `VarkaDayTimeIntervalBaselineBenchmark` times
`dt * 1.5` and `dt / 24`; `dt / 1.5` needs a new section there.

## Decisions taken while the owner was away (20 November 2026)

1. **Question 10.1: the tree check.** Taken as recommended (3.1), because the brief has the plan's
   recommendation decide and the per-arm form would leave the `TIME`, `TIMESTAMP_NTZ` and decimal
   checks fused on a host where the same mask runs as the fallback. The cost the question names
   stands: those families are declined on the mechanism, with no 128-bit number of any of them
   fused. The census file and the 128-bit runner rerun of their baselines are what would show a
   win given up; both are owed.
2. **The lane of the walk: `LONG` only, not every 64-bit lane.** A `DOUBLE` lane has no check of
   these kinds (`IntArith` and `IntNeg` are `WRAP` there by construction, and no compiler guards a
   double child with a `GuardedRange`), so the plan's `LONG` is kept as written.
3. **Question 10.2: 612b in this row, on a stacked branch, default left at `MASK`.** Taken as
   recommended. The scope question (a piece of `m9/SCOPE.md` item 101 inside milestone 8) is the
   owner's to overturn; the branch is separate so that 612a can merge without it, and the default
   cannot move before the runner measurement of 6.1 predictions 3 and 4.

## 10. Questions for the owner

1. **The tree check or a per-arm `twoLaneChecked`.** Recommended: the tree check (3.1). It covers
   the `TIME`, `TIMESTAMP_NTZ` and decimal checks too, and the families still to come. I cannot
   decide it: it declines those families on reasoning, with no 128-bit number for any of them
   fused, and row 503 set the precedent of refusing only where a measurement put the loss. If
   the answer is per arm, 612a covers the day-time arms alone. The others then wait for 128-bit
   baselines of their own.
2. **612b in this row or in item 101.** Recommended: in this row, as a second pull request after
   612a. The decline closes the done-when on its own, but it gives up the 4x the same kernels
   reach at 256 bits, and the lowering is small for the `FAIL` case. I cannot decide it because
   `m9/SCOPE.md` item 101 is a later milestone's scope by the owner's plan, and taking part of it
   into milestone 8 is a scope decision.
