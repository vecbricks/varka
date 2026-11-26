# VARKA-379: Far-day dates outside 0001-9999 get wrong calendar fields

## 1. Where this came from

Row 245's refinement proofs (`m7/VARKA-245.md` 9.2 item 1, on branch `varka-245`, #868) found
that the range analysis reads a bare date column as 0001-01-01 to 9999-12-31 and nothing checks
it. Spark stores any int32 day count, and a cached column can hold one; a calendar field over such
a column fuses with no per-lane check and is served wrong. That is a wrong answer, not a failure,
so the ghost fallback cannot catch it - the case `sql/varka/AGENTS.md` names ("Anything that can
return a wrong answer rather than fail ... gets its own differential coverage") - and it sits
under milestone 7's done-when list item 2, the tests reaching what they miss, and item 1, whose
proofs are stated over guarded domains: a domain assumed rather than guarded is not one.

## 2. The admission check, done

The reproduction, end to end on master `e0acfd4fa4a` (worktree `varka-repro-farday`,
16 October 2026): a cached view `SELECT v, date_from_unix_date(v) AS d` in one Arrow batch, each
query run on Varka and on the row engine.

- The kernel served the batch (`numVarkaBatches=1`, nothing declined, no fallback) and answered
  wrong: 108 rows differed over the shapes tried.
- The last correct days are 14766813 (42400-02-29) at the top and -5394572 at the bottom; one
  day past either is wrong. At 14766814 `dayofmonth` is 2 against Spark's 1; at 100000000 `year`
  is 69873 against 275760 and `month` is 31848.
- `quarter`, `weekofyear`, `last_day` and `trunc(d, 'MM')` are also wrong past the range;
  `dayofweek`, `weekday`, the ISO day of week and `unix_date` are right for every int.
- A column plus a literal (`year(date_add(d, 1))`) is wrong the same way. A column-offset
  producer (`date_add(d, i)`) is already guarded and declines.

The three places the code decides there is no guard: `VarkaRangeAnalysis.range`'s `ColumnRef`
arm answers `CONTRACT` unconditionally; `VarkaChronoCompiler.admitCalendar`'s first arm admits a
node whose interval decomposes exactly with no `GuardedDay`; and `Analysis.collectRefusals`
refuses only a column-offset `AddDays`/`SubDays`.

A second consumer of the same assumption, found while enumerating them for this plan: the `INT`
query. `datediff(a, b)` over two columns is bounded by the bound's width, 3.65 million, so under
ANSI `datediff(a, b) * 100` and `-datediff(a, b)` fuse with their overflow checks removed. With a
far day Spark raises and the kernel would wrap. Same cause, same fix; `VarkaFarDaySuite` has it.

What the check would have rejected: a design that only covers calendar admission (the `INT`
path stays open), and one that bounds every date column (it would decline batches for
`dayofweek` and the plain reads, whose lowerings are exact for every int).

## 3. The design

Two designs were named. **(b), widening the decomposition so it is exact over every int32 day,
is not built: it was built once and measured.** `VARKA-26.md` 11.2's `TOTAL` lowering covered the
whole int range without a guard and cost 14 to 24% against the narrowed one, depending on width
and null pattern; the owner chose `NARROWED` and `TOTAL` was removed, on the guard's strength.
The guard that decision rested on covered producers and not bare columns, which is this bug;
the price of totality has not changed, so (b) would buy back a range no SQL literal reaches at
the cost the owner already declined. There is therefore no losing arm to keep as an option.

### 3.1 The column bound, checked per batch

(a), and through machinery that already exists rather than a new IR node: the compiler's input
bounds (`DeclineSink.bound`, carried in `CompiledVarkaProjection.inputBounds`, checked in
`VarkaKernelRunner.fill` over each batch's live lanes by `IntRangeOps.allWithin`, declining with
`STATUS_INPUT_BOUND`), which the `CAST(i AS INTERVAL DAY)` day offset already uses. One vectorised
min/max pass over the column before the kernel, and the batch goes to the row engine when a live
day is outside.

- `VarkaRangeAnalysis.range` takes an `IntConsumer` and reports the input slot of every date
  column whose `CONTRACT` its answer rests on. Not reported: a column under a `GuardedDay`, under
  `MakeDate`, or under a column-offset day shift, whose own result the producer guard checks. The
  four-argument form reports nothing, for callers that do not act on the answer.
- `VarkaChronoCompiler.admitCalendar` asks once more on the node it admits (after `rearm`, so a
  column below a placed `GuardedDay` is not bounded) and notes the bound on each reported column.
- `VarkaNodeCompiler.boundedByIntMax`, which removes an overflow check, notes the bound on the
  columns its magnitude rested on - only when it answers yes.
- `DeclineSink` learns a slot-keyed bound, so the analysis's report (slots, as `ColumnRef`
  carries) needs no table; it rolls back with the entry like every other bound.

The bound noted is `CONTRACT` itself, so every compile-time decision is what it was: the same
shapes admit and decline, and the analysis's statement becomes a checked one. A day in
`[NARROW_MIN_DAYS, CONTRACT_MIN_DAYS)` or past 9999 that a kernel served correctly before now
declines too: a slower path for data no SQL literal writes, accepted for a bound that is one
constant. A per-shape bound - the widest interval the admitted shape is still exact over - would
serve those days, and is `m10/SCOPE.md` item 102 (added with this task's outcome).

### 3.2 What is deliberately unchanged

- The IR, the emitter and `Analysis.collectRefusals`: no node, no refusal, no option. The input
  bounds are not in `VarkaShapeKey`, so the emitted bytes do not move (`emitted_bytes.json`
  unchanged, checked by `VarkaEmittedBytesSuite`).
- The proof files: their constants are unchanged; `sql/varka/proofs/README.md` says the leaf
  interval is a premise the evaluator now discharges.
- The fuzzers' value draws: the Spark-level fuzzers (`VarkaSparkFuzz*`) are row 298's; whether
  they draw days past the bound is reported to the owner rather than edited here. The IR fuzzer
  calls kernels below the evaluator, where the bound is a precondition, so it must not draw them.
- The column-offset producer guard and its option `guardDayProducers` (VARKA-52, VARKA-93).

### 3.3 Registered op counts

None move: no kernel changes. `VarkaEmittedBytesSuite` is the check.

## 4. Files

| file | what |
|---|---|
| `VarkaRangeAnalysis.java` | the column report; `CONTRACT`'s doc says it is checked |
| `VarkaChronoCompiler.java` | `boundColumns` on admission |
| `VarkaNodeCompiler.java` | `boundedByIntMax` notes the bound (one signature; row 318 also edits this file) |
| `VarkaIntervalCompiler.java` | `negationMode` passes the sink |
| `DeclineSink.java` | slot-keyed bounds, `dayColumnBound` |
| `VarkaChrono.java`, `proofs/README.md` | the bound's wording |
| `VarkaFarDaySuite.scala` | the differential |

## 5. Tests, and what each is for

`VarkaFarDaySuite` (sql/core), every query on both engines with `VarkaMaybeThrows`' comparison
(same rows or the same error), and each answered query asserting it reached a Varka node, no
kernel failed, and every batch was served or declined:

1. The reproduction: `year`, `month`, `dayofmonth` over the reproduction's days, dense and with
   a null row (both bodies); each must decline on the input bound.
2. Every row of `coverage.json` over the date, int and year-month columns, over days past the
   bound and int32's ends, dense and nulled. The rows whose lowering is exact for every int
   (`dayofweek`, `weekday`, ISO day of week, `unix_date`, `datediff`, the predicates) must not
   decline on the input bound - the check is not over-applied.
3. The bound's edges serve with no input-bound decline; one day past either declines.
4. A column plus a literal, and filters (`WHERE year(d) = 275760`).
5. ANSI `datediff(d, d2) * 100` and `-datediff(d, date_from_unix_date(0))` raise as Spark does past the
   bound, and still fuse and serve inside it.

The suite is the oracle for the wrong answer; it fails on master (section 2's reproduction is its
first test).

## 6. The measurement

The check is one pass per bounded column per batch, so the cost is per batch and shows where
batches are small. A pair on GitHub runners (`dev/varka_bench_pair.sh` through `benchmark.yml`,
base the merge base), two classes, since the catalyst benchmarks call kernels below the evaluator
and never run the check:

- `VarkaEvaluatorOverheadBenchmark`: `trunc(d, fmt)` is bounded now; `date_add(d, 3)`,
  `least(l, ...)` and the filter `d > DATE'2000-01-01'` are not, and are the controls.
- `VarkaThroughputBenchmark`: `year`, `month`, `day`, `weekofyear`, `year(d) * 100 + month(d)`
  are bounded; `dayofweek` is not, the control.

### 6.1 Predictions, registered before the run

Stated at the calibration table's lesson (`skills/testing-and-debugging.md`): a per-batch check
costs more than the kernel arithmetic says, so these allow for the harness.

1. `VarkaEvaluatorOverheadBenchmark`, `trunc(d, fmt)`: slower by 2 to 8% at 1 and 16 rows a batch
   (a pass that maps the segments and broadcasts two vectors, about 20 to 50 ns against 712 and
   1237 ns), and within 3% at 1024 rows, where the pass is under 1% of a 30 us batch. The three
   controls within the runner's band.
2. `VarkaThroughputBenchmark`: every bounded row within 3% (a 4 KB pass beside a decomposition of
   tens of operations a lane, and the query's scan and row conversion); `dayofweek` unmoved.
3. No row fails the pair's 10% threshold.
4. `VarkaFarDaySuite` costs under 60 s including its sessions, its tests under 30 s (about 250
   queries over a one-batch cached view, at VARKA-243's ratio of 2 on 0.05 s a query).

## 7. Risks

1. A consumer of the column's range not found: anything that reads `CONTRACT` without going
   through `range` with a collector. Grepped: `CONTRACT` is read only by the `ColumnRef` arm, and
   the analysis is acted on in `admitCalendar` and `boundedByIntMax` only. Test 2 runs every
   coverage row past the bound.
2. Over-application costing served batches: test 2's no-decline set, and the pair's controls.
3. A slot-keyed bound pointing at the wrong input after a rollback: slots only append and are
   truncated with the bounds at the same marks; the existing differential suites and the option
   matrix run every shape.
4. Tests that pin a compiled plan's `inputBounds` as empty for a calendar shape: the catalyst
   suites show them.

## 8. Sequencing

One commit for the plan, one for the fix with its suite; the pair dispatched by the owner.

## 9. Outcome

Built as planned (16 October 2026), on branch `varka-379`; the pair is owed.

**What was built.** Section 3.1 as written, with one change from the review below: the calendar
admission notes the bound from the query it admits on, and asks again only for a node `rearm`
rewrote. The bound is noted on 24 of the coverage table's 51 rows over `d` - the calendar fields
whose lowering decomposes the day, the month arithmetic over them, and the int arithmetic and
predicates over those fields - and on none of `dayofweek`, `weekday`, the ISO day of week,
`next_day`, `trunc(d, 'WEEK')`, `unix_date`, `datediff`, the day shifts, the choice nodes or the
date comparisons, whose lowerings are exact for every int. `emitted_bytes.json` is unchanged
(`VarkaEmittedBytesSuite` passes without a regeneration): the input bounds are runtime data, not
part of the shape.

**Two consequences the plan did not list.** The coverage table's VARKA-279 rule - a row whose
kernel can decline a batch names its domain - made the 24 rows carry
`unix_date(d) BETWEEN -719162 AND 2932896` (conjoined with the domain four of them had), so
`coverage.json` and `docs/sql-varka.md` were regenerated, and the coverage differential's edges
fixture gained `d` at both ends of the bound and one past each; its domain tests now run every
bounded row over batches wholly inside, outside and null, and the outside ones decline with
`STATUS_INPUT_BOUND`. And the `datediff` path (section 2) is a confirmed second wrong answer, not
only a reading: with the bound's note disabled, `datediff(d, d2) * 100` answers a wrapped value
where Spark raises `ARITHMETIC_OVERFLOW`.

**The suite's teeth.** With `DeclineSink.dayColumnBound` made a no-op, all five
`VarkaFarDaySuite` tests fail: the reproduction's rows differ, the coverage sweep's `year(d)`
differs, the edge test sees no decline one day past the bound, `year(date_add(d, 1))` differs,
and the `datediff` product answers where Spark raises.

**Suites run.** `dev/varka_matrix.sh --defaults --split 4` with the sanitizer: 1,383 passed, one
failure, `VarkaTierWitnessSuite`, which asserts JIT tiers and traps in `VarkaMemorySanitizer.check`
under the sanitizer; the gate runs it in its quiet phase without the sanitizer, where it passes
here (7 of 7). The option matrix over the guard-relevant configurations, `guardDayProducers=false`,
`guardUnderArm=false`, `exactRefusalStatus=true` and `rangeSets=false`, with the defaults: all
five pass, 1,237 to 1,335 tests each, nothing failed or unfused.

**Predictions.** 6.1 #4: `VarkaFarDaySuite` took 25 s with its sessions and 16 s of tests,
against under 60 s and under 30 s - a ratio of about 0.5. 6.1 #1 to #3 wait for the pair.

**Review** (`/code-review high`, eight findings). Fixed: the admission's second traversal on the
plain path; the suite's hand-kept list of unbounded rows, now read from the table's domains so
every other date row is held to declining nothing; the proofs README's wrapping; the range
analysis now says the producer guard's reference configuration (`guardDayProducers=false`) is the
one gap it cannot see, which computes such a lane wrongly by design, as before. Not changed:
a slot-keyed bound past the input table is dropped as an ordinal-keyed one always was, which
happens only when a declined entry's rollback is skipped (the `misdescribeRollback` fault
injector), and then only for the declined entry's own columns; the per-shape widest bound (`m10/SCOPE.md`
item 102); and the four-argument `range` defaulting to no report, which the tests and proofs use.

**Left for others.** The Spark-level fuzzer's value draws (`VarkaSparkFuzz`, row 298's file) stop
at 0001-01-15 and 9999-12-01 and a wide draw of 200,000 days either side of the epoch, so it
never draws a day past the bound; `farDates` there wants `date_from_unix_date` values past
42400-02-29 and before day -5394572, and int32's ends.

## 10. The check folded into the kernel (17 October 2026)

### 10.1 Why

The pair of section 6 (VarkaThroughputBenchmark, ten rounds on GitHub runners, EPYC 7763, AVX2,
run 38959217998) found the separate pass costly on Varka's side only: `datediff + 1` 17% slower
by median and 11% by best, `year * 100 + month` 16% and 10%, `yearofweek` 14% and 10%,
`weekofyear` 15% and 9%, every Janino row within 2%. Prediction 6.1 #2 (every bounded row within
3%) failed: the pass reads every bounded column once more from memory before the kernel reads it
again, which costs as much as a cheap kernel's own work. The owner held the pull request until
the check sits inside the kernel's loop, on the vector the kernel already loads.

### 10.2 The design

The kernel already has an in-loop range check with everything this needs: `GuardedRange`, the IR
node that passes its child through and declines the batch when a live lane is outside
`[lo, hi]` (VARKA-102's `TIME + INTERVAL`). Its emission is two compares on the loaded vector,
ANDed with the lane's validity word in the masked body and with the bounds mask in the epilogue,
ORed into the method's guard mask; the mask is reduced once per method after the loop and the
driver ORs the methods' statuses, so it is right in both bodies, in the tail, at both widths and
across split methods, and it is priced by the size machinery (`GUARD_WEIGHT`, the emit cost
table). So the column bound becomes that node, with no new emitter code:

- The compile of one entry (a projection entry, a predicate conjunct, and `fitsAlone`'s
  trial) ends by wrapping every `ColumnRef` of a slot the entry noted with `dayColumnBound` in
  `GuardedRange(ColumnRef, CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS)`, before the budgets are asked,
  so the size admission sees what the emitter will emit. Once per entry at its root rather than
  at the three sites that note the bound: the overflow check's site (`boundedByIntMax`) answers a
  question about operands its callers then build the node from, and a wrap that did not flow back
  into that node would lose the guard while the overflow check stays removed.
- The slot bounds the wrap discharges leave the sink, so they no longer reach
  `CompiledVarkaProjection.inputBounds` and the evaluator's pre-pass. The ordinal bounds stay
  there: `CAST(i AS INTERVAL DAY)`'s offset column sits in `date_add`'s day-offset position,
  which admits a bare column and not a guard (`VarkaOperandPosition`), so moving it in-kernel is
  not a one-line change and is not made here. The wrap never reaches into that position nor under
  `IsNotNull` (which reads only validity); a noted slot whose column sits in a restricted
  position keeps its bound for the pre-pass, though no date column can sit in one.
- `VarkaRangeAnalysis` answers a `GuardedRange`'s own bounds for the `DAY` kind too, as it
  already did for `INT`: a wrapped column reads as the range its guard enforces.
- A decline is `STATUS_VALUE_RANGE`, the node's own reason, rather than the evaluator's
  `STATUS_INPUT_BOUND`, which stays the pre-pass's.

What a declined batch's outputs are worth is the same contract every in-kernel guard already
rests on (`VarkaFusedKernel`'s status): the kernel writes its outputs and returns a non-zero
status; `VarkaKernelRunner.invoke` throws `VarkaBatchDeclined` before the both-bodies check; and
`VarkaEvaluatorBase.serveBatch` catches it and runs the caller's fallback for the whole batch,
for every exec node (project, filter, columnar-to-row) and for a projection of several kernels
(`VarkaKernelPart` rethrows naming the kernel). Nothing written before the decline is read.

What changes in which batches decline. Within one entry the wrap covers every use of a noted
column, so `year(date_add(d, i)) + year(d)` checks `d` under the `date_add` too, where the
producer guard alone would have served a far `d` the shift brings back into range; the pre-pass
did the same across the whole kernel, so nothing declines that did not before. A column use
under an `IF`/`CASE` arm is checked only on the lanes the arm takes (`emitArmContext`), which the
pre-pass could not do. The per-shape bound remains `m10/SCOPE.md` item 102.

### 10.3 Predictions, registered before the build

1. `emitted_bytes.json` moves for the shapes that hold a bounded date column and for no other;
   each body method of such a kernel grows by 40 to 90 bytes for its first guarded column (the
   guard, the mask's initialisation and the status reduction) and by 20 to 50 for each further
   one; no entry's method count changes and no coverage row stops fusing.
2. The re-pair of section 6's `VarkaThroughputBenchmark` against the base: no row 10% slower by
   both median and best, and the four rows above within 5% of the base by best - the guard is
   four vector ops a lane group on a vector already in a register, beside a decomposition of tens.
3. The far-date suites pass unchanged in what they assert, except the status a decline carries.

### 10.4 Outcome

Built as 10.2 describes (17 October 2026). Section 6.1's predictions #2 and #3 are scored by the
pair of 10.1, both failed: the bounded rows were 9 to 17% slower, not within 3%, and four rows
crossed the pair's 10% threshold by median (a calibration entry, ratio 3 to 6). #1, the evaluator
overhead benchmark's rows, was not part of the pair reported here.

**What was built.** `DeclineSink.guardDayColumns`, called at the root of each projection entry,
predicate conjunct and `fitsAlone` trial, before the budgets; `VarkaRangeAnalysis.guardColumns`,
the rewrite; and the range analysis's `GuardedRange` arm answering days. No emitter code changed.
The evaluator's pre-pass, `IntRangeOps` and `STATUS_INPUT_BOUND` stay for the
`CAST(i AS INTERVAL DAY)` bound alone.

**Emitted code (10.3 #1).** `emitted_bytes.json` moved for exactly the 24 coverage rows that carry
the bound, at both widths and in the option arms built from them; none of the fuzz blocks moved,
and no row's set of methods changed. Per method, at the laptop's width (`dev/varka_emit.sh`,
before with the wrap disabled):

| shape | dense loop | masked loop |
|---|---|---|
| `year(d)` | 409 to 461 | 409 to 555 |
| `weekofyear(d)` | 622 to 674 | 622 to 768 |
| `extract(YEAROFWEEK FROM d)` | 531 to 583 | 531 to 677 |
| `year(d) * 100 + month(d)` | 472 to 524 | 472 to 618 |
| `datediff(d, d2) + 1` | 167 to 252 | 167 to 440 |

The dense bodies are inside the prediction (52 bytes for one column, 85 for two); the masked
bodies are not: 146 bytes for one column and 273 for two, against at most 90 and 140. What the
prediction missed is that these masked loops read no input validity before - the driver computes
the outputs' validity a word at a time - and the guard has to, so that a null lane's undefined
day cannot condemn the batch: each lane group now loads the column's validity word and makes a
mask of it, per guarded column. The pre-pass did the same per lane group, with a byte loop.
`year(d) * 100 + month(d)` shows the guard shared: both fields read one guarded column, one guard.

**Two tests the guard's ops moved.** `VarkaInliningCliffSuite` pins the census shape of VARKA-209,
twenty-two `year(d) + k` tails with the call-site budget off, at 99 vector call sites a loop; the
guard's two compares make it 101, still past the C1 limit the census found, and the pin says so.
And `VarkaExpressionCompilerSuite`'s op-cap case (VARKA-169) builds a 63-op tree over `d`, which
with its guard is 64, the cap itself: beside `year(d)` it became the overflow entry instead of
`month(d)`, so the legacy half of that test now puts the tree first.

**Tests added.** `VarkaGuardEdgeSuite`: the column bound joins the guards' edge table (both ends
and one past, a full lane group and the epilogue lane, valid and null, through both bodies); and a
test that compiles `year(d)`, `datediff(d, d2) + 1` under ANSI and a kernel split one output a
method with the guarded output last, and runs each guarded column with a far day in a full lane
group and in the tail, in the dense body, the masked body and the masked body forced, and with far
days only under null rows, which its masked body answers and its dense body, the nulls cleared,
declines; the split case checks the guard's constant is in a loop method other than the first.
`VarkaFarDaySuite` reads the value-range reason where it read the input bound; what it asserts is
otherwise unchanged and it passes (10.3 #3 held).

**Suites run.** The gate step by step, since one call cannot hold the whole of it: compile, wide
and narrow (1,389 passed each, 34 cancelled as on every run, after the cliff pin's fix, which the
first wide run failed on), quiet, sweep, doc, bench and quotes passed. Lint: `dev/lint-java` and
`dev/scalastyle` passed, and Error Prone after two `ReferenceEquality` suppressions on the
rewrite's deliberate identity tests; NullAway then stops on `VarkaEmitTrace.onEmission`, which this
change does not touch and master has fixed since this branch's last merge of it (#889, #890). The
option matrix over the guard-relevant configurations: `exactRefusalStatus=true` and
`guardUnderArm=false` pass; `methodByteBudget=0` fails two tests whose IR is built by hand and
never reaches the compiler - `VarkaKernelPlanSuite`'s shared trace over 300 `date_add` nodes, which
that configuration's 64-op cap refuses, and `VarkaEmitterChronoSuite`'s scratch refusal over
`year` and `month` of a bare column - so neither rests on this change, and master's scheduled
option matrix of 11 October (run 38129966925) failed the same two in that configuration.

**After review** (17 October 2026). Two tests added: `VarkaFarDaySuite` drives a far day only on
rows a `CASE` arm does not take, which the kernel now serves where the pre-pass declined, and a
projection split over two kernels whose second alone holds the guard, which declines the whole
batch; `VarkaGuardEdgeSuite` runs that split's kernels below the evaluator. One correction to 10.2:
the arm qualifies the guard only where every use of the column sits under the arm. The wrap guards
every use of the column in the entry with one shared node, so a condition that reads the column
itself, `CASE WHEN d < d2 THEN year(d) ...`, leaves it unqualified, and a far day there declines as
the pre-pass did. The suite's case reads another column in its condition; it is listed for
`guardUnderArm=false` in `matrix/skips.tsv`, where it fails by design.

**Left open.** A far-day decline is counted under the value-range reason, beside the `TIME`
family's range guards, rather than a reason of its own; the kernel cannot tell the two apart
without a status bit for the column bound, which would be a second refusal reason on the same
node. Prediction 10.3 #2 waits for the re-pair.

## 11. A cheaper guard (17 October 2026)

### 11.1 Why, and where the cost is

The re-pair of section 10 (`VarkaThroughputBenchmark`, ten rounds on GitHub runners, EPYC 7763,
AVX2, run 38994401183) took most of the pass's cost away and left a residual on Varka's side:
`datediff + 1` 7.3% slower by median and 3.6% by best, `year * 100 + month` 6.5% and 4.0%,
`yearofweek` 6.9% and 3.2%, `weekofyear` 5.8% and 6.9%, their Janino rows flat by best. That
scores 10.3 #2: its first half held (no row 10% slower by both), its second failed on
`weekofyear`, 6.9% by best against within 5% (a calibration entry, ratio about 1.4). The owner
holds the pull request until the residual is designed out too.

Where it goes, read from C2's assembly of `datediff(d, DATE'2000-01-01') + 1`'s loop methods,
on the laptop with `-XX:UseAVX=2 -XX:MaxVectorSize=32`, the runner's instruction set: the
benchmark's `varka_dates` holds a null every 31st row, so every batch runs the masked body, and
there each lane group spends nine instructions turning the column's validity byte into a lane mask
for the guard (a bounds-checked byte load, `pdep`, `vmovq`, `vpxor`, `vpsubb`, `vpmovsxbd`,
`vpand`) beside four on the range itself (two `vpcmpgtd`, two `vpor`). The kernel's own work for
that shape is four: a load, a subtract, an add and a store. The dense body pays the four only.
Section 10.4's note that "each lane group now loads the column's validity word and makes a mask of
it" was the cost, and the masked loops before the guard read no validity at all.

### 11.2 The candidates

1. **No validity.** A range guard over a bare int column checks the column's stored values, null
   rows included. It removes the nine, and the masked loop reads no validity word again: the
   liveness pass stops demanding the column's word for the guard (`Slots.guardedWord`).
2. **Min and max, checked once.** In a loop body, the guarded vector goes into a lane-wise min and
   a lane-wise max (`vpminsd`, `vpmaxsd`: two ops a group in place of four, and no mask), into two
   accumulators started at `broadcast(lo)` and `broadcast(hi)`; after the loop the two compare
   once against the bounds and fold into the guard mask the status return already reduces, so
   the driver, the split methods and `exactRefusalStatus` read the result as before. It needs 1:
   a min cannot be qualified by validity without a blend per group, which is the cost 1 removes.
   It stays out of the epilogue (one pass; it keeps the compares and the bounds mask's AND, minus
   validity) and of a guard under `IfElse` arms, whose mask is ANDed with the arm's condition and
   keeps the compares, minus validity.
3. **Not built, recorded.** (a) Keeping live lanes only, cheaply: OR each group's out-of-range
   mask as bits (`toLong() << offset`) into a long and AND it with the validity word once per 64
   rows - about three scalar ops a group instead of nine. It is the design if null rows' stored
   values could not be relied on to be in range, which 11.3 checks. (b) The Arrow cache already
   computes each batch's min and max date (`ArrowCachedBatchSerializer.calculateMinMaxDate`) for
   pruning; carried into the evaluator it would make the check free for cached input, but a
   kernel's input is not only the cache, and the stats do not reach the `ColumnarBatch` today.
   (c) Confirming a fired cheap check exactly before declining (rerun the old pass over live lanes
   only, serve the already written outputs when it passes) would keep 1's declines away, at the
   price of a status bit of the column bound's own and a serve path that reads outputs after a
   non-zero status, which no other guard does.

The pair cannot tell 1 from 1+2, and both change the same batches' fate, so the choice between
them is a laptop A/B of the kernel alone under the runner's instruction set (11.4 #2), labelled as
such. Neither is a machine-dependent trade-off in direction - a min or a max is one op on AVX2,
AVX-512 and NEON alike, against two compares and two ORs - so the loser is recorded here and not
kept as an emit option.

### 11.3 What 1 changes in which batches decline

The pre-pass and section 10's guard read live lanes only; 1 reads null rows' stored days too. What
a null row stores: Arrow's fixed-width vectors zero their value buffer on `allocateNew`, on
`reAlloc` (the new part) and on `reset` (`BaseFixedWidthVector`, arrow-vector 19.0.0, the build's),
and `setNull` clears the validity bit only. So a null the cache's row path writes through Spark's
`ArrowWriter` is day 0, inside the bound, and 1 declines nothing there that 10 served. A far day
under a null comes only from a producer that computed one: the cache takes an Arrow batch
zero-copy (`ColumnarBatchToArrowCachedBatchIterator`), and a Varka kernel's output holds in a null
lane what its lowering computed from the stored inputs (`date_add(d, i)` with `d` null holds `i`).
Such a batch now declines and the row engine answers it: slower, never wrong, and only in the
masked body, since the dense one runs on batches without nulls.

The form is chosen structurally: an int-lane `GuardedRange` whose child is a `ColumnRef`. The
compiler builds exactly one, the column bound (`VarkaRangeAnalysis.guardColumns`); the `TIME`
family's range guards sit on the long lane, where AVX2 has no 64-bit min or max, and keep both
their validity and their compares. The IR grammar draws int-lane `GuardedRange`s over columns with
other bounds, which take the form too, and the guard verdict (`VarkaGuardSpecs`) reads a null row
under one as a row that may decline: the reference has no value for it, and the fuzzer poisons
null lanes with the lane's extremes.

### 11.4 Predictions, registered before the build

1. Emitted bytes, per loop method at the laptop's width (`dev/varka_emit.sh`), against 10.4's
   table: every masked loop shrinks, by 60 to 100 bytes for one guarded column and 150 to 250 for
   `datediff(d, d2) + 1`'s two, and reads no validity word; every dense loop grows by 10 to 40 for
   one column (the accumulators' start and the fold after the loop cost more bytes than the two
   compares save) and changes by less than 20 for two, since the second column's guard is two ops
   into the same accumulators. No method count changes and no coverage row stops fusing.
2. Laptop A/B, the kernel alone (`VarkaEmitDump --rounds`, 4,096-row batches, 33 nulls per column
   and none, `-XX:UseAVX=2 -XX:MaxVectorSize=32`), against the same kernel without the guard: the
   head's masked-loop overhead on `datediff + 1` falls by at least two thirds under 1 and by at
   least three quarters under 1+2; the dense overhead falls by at least a third under 1+2 and does
   not move under 1. 1+2 is built if it beats 1 on the masked loop and loses nothing on the dense.
3. The re-pair (`VarkaThroughputBenchmark`, ten rounds, against the base): the four rows above
   within 3% of the base by best, no row 10% slower by both median and best, the Janino rows flat.
   That is 10.3 #2's estimate of the guard's remaining work - one or two ops a group beside a
   decomposition of tens - at the 1.4 it scored.
4. No suite is added; the tests added cost under 2 s.

### 11.5 The consumers of the guard's emission

Enumerated before the build, since each reads the guard's shape: the liveness pass's demand
(`Slots.guardedWord`) and the scratch predicate (`guardScratch`, no park slot for the min/max
form); the slot plan (two accumulators per distinct bound pair in a loop body that holds the form);
`emitGuardCollect`'s validity AND and arm context; the status return under one mask and under
`exactRefusalStatus`; the guard verdict (`VarkaGuardSpecs`) and the suites over it
(`VarkaGuardEdgeSuite`'s edge table and its test of far days under null rows, which flips from
served to declined in the masked body, `VarkaFarDaySuite`'s null-row cases); the price tables
(`VarkaEmitCostTable`, `VarkaEmitCostRegister`, regenerated by `VarkaEmitCostSuite`) and the audit;
the op-count pins (`VarkaInliningCliffSuite`'s census, `VarkaExpressionCompilerSuite`'s op cap, the
emitter suites' counts); `emitted_bytes.json`.

### 11.6 Outcome

Built as 11.2's candidates 1 and 2 together (17 October 2026), with one correction to 11.3 found
by the gate and described below.

**What was built.** `Analysis.guardsStoredValues` names the column bound: an int-lane
`GuardedRange` over a bare column with the bounds `CONTRACT_MIN_DAYS` and `CONTRACT_MAX_DAYS`.
Its guard ANDs with no validity word in any body, and the liveness pass stops demanding the
column's word for it (`Slots.guardedWord` and the `WordWalk` arm). In a loop body, where no arm
qualifies it (`Analysis.accumulatesRange`), it is a lane-wise min and max into two accumulators
per pair of bounds (`Slots.rangeAcc`, `VarkaVectorWalk.emitRangeAccumulate`). The accumulators
start at the bounds, and once after the loop they are compared and folded into the guard mask
(`VarkaBodyEmitter.emitRangeFold`), or into the value-range reason's mask under
`exactRefusalStatus`. The epilogue and a guard under arms keep the two compares, without
validity. A guard whose accumulators were not planned is refused by name rather than dropped.
`VarkaEmitMechanism.GUARD_MIN_MAX` records the form.

**Correction to 11.3.** 11.3 chose the form by shape alone, so the IR grammar's range guards over
int columns, drawn with other bounds, would take it too. They did, and the gate failed on it.
The harness poisons null lanes with int32's ends, so `VarkaSmallTreeSuite`,
`VarkaVariantSuite` and three tests of `VarkaIrFuzzSuite` saw those batches decline where no
decline is allowed. The form is now told by the column bound's own bounds as well as its shape,
and every other range guard keeps the validity AND and the compares. The compiled shapes still
met the poison under their real column bound: `VarkaCoverageCompositionFuzzSuite` compared 136 of
184 kernels on its poisoned batch, and the coverage differential's domain check read a null row as
one that may decline. So the kernel harness now stores the bound's two ends, in turn, under a null
of a column read through the column bound (`VarkaKernelChecks.storedUnderNull`, also used by
`VarkaCompiledCheck`). That is still as far from zero as a stored day can sit without declining,
where production stores 0. The guard verdict (`VarkaGuardSpecs`) reads a null row's stored day
for the column bound: 0 from the Scala face, as Spark's writer leaves it. The fuzzers therefore
do not draw the min-and-max form. It is driven by `VarkaGuardEdgeSuite`, the coverage suites and
`VarkaFarDaySuite`, which compile it. With the form narrowed, the price tables and
`emit_cost_audit.json` came back to what they were before this section. The fit's range-guard
samples do not carry the column bound.

**Emitted code (11.4 #1, held).** `emitted_bytes.json` moved for the 24 bounded coverage rows at
both widths and in the option arms. No fuzz block moved, and no row's set of methods changed.
Per loop method at the laptop's width, against 10.4's "after" column:

| shape | dense loop | masked loop |
|---|---|---|
| `year(d)` | 461 to 494 | 555 to 494 |
| `weekofyear(d)` | 674 to 707 | 768 to 707 |
| `extract(YEAROFWEEK FROM d)` | 583 to 616 | 677 to 616 |
| `year(d) * 100 + month(d)` | 524 to 557 | 618 to 557 |
| `datediff(d, d2) + 1` | 252 to 270 | 440 to 270 |

The masked loops (and epilogues) are now the dense ones' bytes, since neither reads input
validity. In C2's AVX2 code for `datediff(d, DATE'2000-01-01') + 1`, the masked loop fell from 630
instructions to 368, the dense loop's count, and its eight `pdep` mask builds, one per unrolled
group, are gone. The dense loop's lane group is now a load, a subtract, a min, an add, a max and
a store. `VarkaInliningCliffSuite`'s census shape reads 105 vector call sites a loop, where the
compare form read 101: the min and max, the two broadcasts that start them and the two compares
after the loop. It is still past C1's limit, and the pin says so.

**The laptop A/B (11.4 #2): laptop numbers, used only to choose.** `VarkaEmitDump --rounds 400000`
over 4,096-row batches, `-XX:UseAVX=2 -XX:MaxVectorSize=32`, pinned to two of the Ryzen AI 9 HX
370's full cores, minimum of three runs, ns a batch. The machine was shared with other agents' gates
(load 5 to 45 during the runs), and the spread between runs of one arm was up to 50%. The four arms
were: no guard; the compares with validity (section 10); 1, the compares without validity; and 1+2,
the min and max.

| shape, body | none | section 10 | 1 | 1+2 |
|---|---:|---:|---:|---:|
| `datediff + 1`, nulls | 91 | 341 | 210 | 219 |
| `datediff + 1`, dense | 113 | 255 | 295 | 227 |
| `year * 100 + month`, nulls | 2715 | 3128 | 2935 | 2800 |
| `year * 100 + month`, dense | 2746 | 2880 | 2901 | 2830 |
| `weekofyear`, nulls | 4992 | 5373 | 5135 | 5165 |
| `weekofyear`, dense | 4980 | 5156 | 5104 | 5124 |

(The `datediff + 1` rows with nulls are from an unpinned run of five; its pinned rerun, at load 34,
was too noisy to read.) Scored: the masked overhead fell by about half under 1 on `datediff + 1`
and by 47 to 62% on the others, not the two thirds predicted. Under 1+2 it fell by about half,
79% and 55%, not the three quarters predicted. The dense overhead fell by 18 to 37% under 1+2,
not the third predicted on two of three shapes. So 11.4 #2 failed as worded. Its rule for
choosing - 1+2 if it beats 1 on the masked loop and loses nothing on the dense - is not met
strictly either. `weekofyear` lost 30 ns masked and 20 ns dense, under 1% of its time and inside
the runs' spread, while 1+2 won 135 ns masked on `year * 100 + month` and 68 ns dense on
`datediff + 1`. 1+2 is kept on the evidence that does not depend on the machine's noise: two ops a
lane group against four, in the assembly.

What the A/B did teach: on the four-op kernel any per-lane check still costs about the kernel's
own time. Every form folds into an accumulator carried from one lane group to the next, and C2
does not split that chain across its unrolled copies. That is now a lesson in
`skills/vector-api-and-width.md`, beside the AVX2 cost of a validity mask. On the runner's Zen 3
the chain is one cycle a group, which the pair will price.

**Tests changed.** `VarkaGuardEdgeSuite`'s column bound test gains the masked body forced over a
batch with no nulls, inside the bound and with a far day in a full lane group; its null-row cases
read the bound's ends the harness now stores. The cliff pin moves to 105. `VarkaGuardSpecs` reads
a null row's stored day for the column bound. The harness change is above.

**Review** (a reviewer agent at `/code-review high` over the first three commits). Two blockers,
both the gate's failures above: the predicate's reach into the grammar's guards, and the composition
fuzzer's poisoned batches under the real column bound. Both are fixed. Also fixed: a comment in
`emitGuardCollect` that still called `guardedWord` a superset of `guardScratch`, which the column
bound now breaks on purpose (safe, because its callers pass no word); a `guardsStoredValues` doc
that disagreed with the code; and fully qualified `java.util` types in `Slots`. Verified fine by the
review: no guard is dropped (the accumulators are planned from the set the walk emits, and a missing
one throws); `VALUE_RANGE` always has a mask under `exactRefusalStatus`; the accumulators are stored
before the loop on every path, and a zero-trip loop compares `lo < lo` and `hi > hi`; the liveness
pass and the scratch slots agree with what the walk emits. Not changed: the generated reach reports
under `sql/varka/coverage/` do not list `GUARD_MIN_MAX` until they are next regenerated, which is a
run of the fuzz suite with `VARKA_REACH_DUMP`. Left open from the review: whether
`VarkaKernelWarmup` reads a decline from a masked warm-up call over a zero-copy cache batch with far
days stored under nulls differently from any other decline.

**Suites run.** The gate's compile, wide, narrow, lint and quotes steps, after the narrowing:
wide and narrow 1,392 passed each, none failed, 34 cancelled as on every run (105 suites, with
the sanitizer); lint (`dev/lint-java`, `dev/scalastyle`, Error Prone and NullAway) and the quote
check pass. The first gate, before the narrowing, is the failure 11.6 corrects. 11.4 #4 held: no
suite added, and the column bound test's two new batches are milliseconds. The option matrix
over the two guard configurations that change this emission's paths, against that gate's
defaults: `exactRefusalStatus=true` 1,267 passed and `guardUnderArm=false` 1,263, none failed or
unfused in either. A suite that pins the per-reason branch of `emitRangeFold` is not among them:
the edge suite's column-bound shapes hold one refusal reason each, so whether a two-reason body
reaches that branch rests on the coverage and composition suites run under that configuration.

**Prediction 11.4 #3** waits for the re-pair.
