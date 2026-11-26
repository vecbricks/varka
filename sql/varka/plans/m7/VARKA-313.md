# VARKA-313: Why a query did not use Varka, where users look

*Row 313 of `m7/PLAN.md`, added by the owner on 12 October 2026 with rows 314 to 319
(`m7/PLAN.md` 2.4, "Errors and troubleshooting"); it takes in `m10/SCOPE.md` item 65. Planned on
14 October 2026 in a worktree at master `f37b52026ba` (after #807). Section 2 corrects the row in
four places; the row's text is left as written and this file is the correction.*

## 1. Where this came from

The row asks, per Varka operator, for SQL metrics that say how many batches a kernel served and
how many fell back and why, with the rows and the time through each path; for the compile-time
declines in `EXPLAIN`; for a refusal reason that was not recorded to read as "not reported" and
never as 0, as Polars keeps the two apart (`READING_POLARS.md`); for the reasons to survive
adaptive re-planning, where Gluten lost its tags (`m10/SCOPE.md` item 28); and for tests that
assert which reason a query fell back for, as Comet's `checkSparkAnswerAndFallbackReason` and
Gluten's `failOnFallback` do (`READING.md` 2). It also asks what exact refusal status costs, and
for that status to become the default if the cost is within noise. Item 65 adds the warm-up's
verdict as one more counter and asks that a benchmark's provenance name the fallback share of a
run.

## 2. The admission check, done

Read on 14 October 2026 against `f37b52026ba`.

**Where a batch's fate is decided and counted.** Every Varka node runs each batch through
`VarkaEvaluatorBase.serveBatch` (line 617). It sends a batch down the caller's fallback for one
of six causes, each already counted on its own `SQLMetric` through `VarkaFallbackAccounting` or
the evaluator:

| cause | decided at | metric today |
| --- | --- | --- |
| input not Arrow-backed | `canRun` / `recordRefusedBatch` (line 795) | `numFallbackBatchesNonArrow` |
| warm-up: the shape's kernel not compiled yet | `kernelReady`, `isWarmupBatch` | `numWarmupBatches` |
| declined: a lane outside a lowering's range | `VarkaBatchDeclined` from the runner or the kernel's status | `numFallbackBatchesDeclined` |
| kernel failure (the ghost fallback) | `VarkaKernelFailure` | `numFallbackBatchesKernel` |
| per-row machinery failure beside the kernel | any other catchable throw | `numFallbackBatchesRowPath` |
| emission failure, once per task | `fusedRunner`'s catch | `numEmissionFailures` |

So **item 65's premise is stale**: "the operators under `sql/core`'s `execution` package define no
SQL metrics of their own" was true on 29 September and is not now. `VarkaExecMetrics.nodeMetrics`
registers twelve metrics on every Varka node, and the projection nodes add
`numResidualEntries`. What is missing is narrower:

1. **No per-reason count reaches a metric.** A declined batch carries a status word whose bits
   name its refusal reasons (`VarkaFusedKernel`: day producer, month count, `make_date`, int
   overflow, day range, value range; `VarkaKernelRunner`: input bound, derived input, forced).
   `VarkaFallbackAccounting.declinedBatch` already tallies them per bit in `declinedByBit`, but
   that array lives in one task's evaluator, is read by one unit test
   (`VarkaEvaluatorComponentsSuite`) and nowhere else; the status reaches only a debug log line
   and the JFR `Fallback` event.
2. **No rows per path** and **no time per path**: `numOutputRows` counts every row whichever path
   answered it.

**The row's second correction: the exact reason is always recorded; what is missing is the
attribution of a folded decline.** The row says a refused batch "names its guard only under
`exactRefusalStatus`". In fact the status word is always produced. A loop method whose guards
share one reason reports exactly that reason; one that holds guards of several reasons folds them
into one mask and reports every reason it holds, a candidate set (`docs/sql-varka.md`, "A declined
batch names its guard"; `VARKA-308.md` 9 measured status 9, `DAY_PRODUCER | INT_OVERFLOW`, for a
batch only the overflow guard declined). `exactRefusalStatus` gives each reason its own mask. So a
status of one bit is exact under either setting, and only a status of several bits under the
default is ambiguous. The evaluator's own bits (input bound, derived input, forced) are raised one
at a time and are always exact.

**The third: run-time metrics cannot appear in `EXPLAIN`.** `EXPLAIN` renders before anything
runs. What it can carry is the compile-time declines, and it already does for a projection:
`VarkaProjectExec` and `VarkaColumnarToRowExec` render every entry as fused, forwarded or residual
with the compiler's reason in verbose `EXPLAIN` (`VarkaFusionReport.lines`), tested by
`VarkaProjectExecSuite` "verbose EXPLAIN accounts for every entry, with the residual entry's
reason". The done-when is read as: run-time metrics in the SQL UI, compile-time declines in
verbose `EXPLAIN`.

**A gap the check found in the filter's `EXPLAIN`.** `VarkaColumnarRule.rewriteFilter` splits a
predicate: the fused conjuncts go into the Varka filter node's `condition`, the declined ones into
a row `FilterExec` above it. The Varka node renders its `fusionLines` from `condition` alone, so
every line reads "fused" by construction (the comment on `predicateLines` says so) and the reason
each residual conjunct was declined is printed nowhere in a real plan. The node holds the whole
condition as `wholeCondition` whenever a split happened, which is the input that renders both.

**The fourth: the reasons survive adaptive re-planning by construction.** Gluten copies a tag
onto the logical link because a new physical node does not carry it. Varka keeps no tag: a node
computes its classification from its own `projectList` (or condition) and child output when
`EXPLAIN` asks, and a node AQE builds anew computes it again. The run-time metrics are the
executed node's own accumulators, reported to the UI through the adaptive plan update like any
node's. This still wants one test under AQE with a shuffle, since the Varka suites' shared
sessions run with AQE off.

**Can a metric say "not reported"?** A plain sum `SQLMetric` renders 0 for a metric nothing added
to, and `MetricUtils.stringValue` does not filter a negative initial value on the sum path. The one
way Spark offers to put text in a metric cell is the data source V2 custom metric: an `SQLMetric`
whose type is `v2Custom_<class>` makes `SQLAppStatusListener.aggregateMetrics` load that
`CustomMetric` class and call `aggregateTaskMetrics(long[])`, whose string is what the UI shows.
`Task.collectAccumulatorUpdates` sends every external accumulator of a successful task, an
untouched one included, and `SQLMetric.toInfoUpdate` sends the raw value, so a metric created at
-1 that a task never set arrives as -1 (`LiveStageMetrics.updateTaskMetrics` stores it as sent).
The check would have rejected a "not reported" rendering if either were false.

**The exact-status cost has never been measured.** `VARKA-304.md` and `VARKA-308.md` committed no
benchmark of it; no benchmark source names `exactRefusalStatus`.

## 3. The design

### 3.1 Reasons, rows and time as metrics

**Per-reason decline counts, one metric per reason**, keyed `numDeclinedBatches[<reason>]` with
the names `VarkaFallbackAccounting` already gives the bits (`day-producer`, `month-count`,
`make-date`, `int-overflow`, `day-range`, `value-range`, `input-bound`, `derived-input`,
`forced`). Each is an `SQLMetric` of a new custom type, `VarkaReasonMetric`, created at -1.

- Per batch, the accounting only adds to its per-task tallies, as it does today, and marks a bit
  *unattributed* when a status of several bits arrives under the default options: no allocation,
  no metric write on the batch path.
- At task completion (the evaluator's ledger listener, registered when the top-level evaluator is
  built inside a task), each reason's metric is set: to its tally when no decline that named it
  went unattributed, a 0 included, and left at -1 otherwise.
- `VarkaReasonMetric.aggregateTaskMetrics` renders the sum when every task reported, "not
  reported" when none did, and "N attributed, not reported in K tasks" in between.

So a reason that fired inside a folded mask reads "not reported" until the session sets
`exactRefusalStatus`, and never reads as a 0 it might not be. A test reads either the driver-side
accumulator (`isZero` for none reported) or the UI string from the status store.

**Rows and time through each path**: `numVarkaRows` and `numFallbackRows` (input rows of the
batches each path answered; a filter's selected rows stay in `numOutputRows`), and
`varkaKernelTime` and `fallbackTime`, nanosecond timing metrics. The kernel path's work up to its
output batch is eager on every node, so `varkaKernelTime` is measured around it on all four. The
fallback is eager only on the columnar-out nodes (`VarkaProjectExec`, `VarkaFilterExec`, which
project or filter into a new batch); on the two row-out nodes it is a lazy row iterator, which a
bracket around the call does not time, and timing each row would cost more than the row. So the
row-out nodes do not register `fallbackTime` at all: an absent metric is not a 0 either. Two
`System.nanoTime` calls per batch on a timed path, and no allocation.

**The bundle**: `VarkaExecMetrics` gains the four path metrics as a sub-record and the reason
metrics as one list in the accounting's reason order, not as more positional fields; the builder
grows to match.

**The filter's `EXPLAIN`** renders from `wholeCondition` when the rule split the predicate, so the
residual conjuncts appear with the compiler's reason beside the fused ones.

**Planted declines for tests.** The decline hook (`setDeclineKernelForTesting`, which plants
`STATUS_FORCED`) becomes a status word, `setDeclineStatusForTesting(int)`, the boolean form kept
as the forced status. A suite can then plant one reason, or a folded set of two, on a query the
row engine answers.

**The fallback-reason assertion.** A suite helper in the manner of Comet's
`checkSparkAnswerAndFallbackReason`: run the query on the row engine and on Varka, compare the
answers, and assert the Varka node's per-reason counts. Gluten's `failOnFallback` already has its
Varka counterpart for failures (`failureFallbackForbidden`, VARKA-275); a decline is a designed
outcome, so it is asserted by reason rather than forbidden.

### 3.2 The exact-status default, decided by a measurement owed to the quiet machine

The owner's direction for this task is that the measurement needs a quiet machine and is not taken
on the laptop beside other jobs. So the default stays off, and this task commits the benchmark case
and the decision rule:

- **The case**: a section of `VarkaEmitterParityBenchmark` with one kernel whose loop method folds
  two reasons (an ANSI int sum feeding a guarded day, under `year`), emitted at the defaults and
  with `exactRefusalStatus`, null-free and with mixed nulls, over in-range data so no batch
  declines and the rows time the extra mask alone.
- **The command**, on the quiet machine:
  `dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark --sections "<the section's title>"`,
  then `dev/varka_bench_diff.py --within sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt --ab "<folded arm>" "<exact arm>"`.
  `dev/varka_bench_pair.sh` compares a base with a head, not two arms in one file, so it is not
  the tool here.
- **The rule**: the rows have no band yet, so the flat threshold applies. Within it at both widths,
  a follow-up flips `exactRefusalStatus` on by default; that changes every guarded kernel's bytes,
  so it is its own pull request with `emitted_bytes.json` regenerated. Outside it, the
  alternative is not "one batch in N" as the row puts it - the option is in the shape hash, so
  sampling would need a second class per shape, its own define and C2 compile - but attribution
  on decline: a folded decline runs that one batch again through an exact-status class emitted on
  the first such decline, a cost only declining shapes pay, on batches that already pay the row
  path.

### 3.3 What is deliberately unchanged

- The per-batch dispatch and its causes: the six causes keep their metrics and meanings.
- A projection or filter with nothing to fuse stays Spark's own node, and its `EXPLAIN` says
  nothing about Varka. That is the whole-plan "why is there no Varka node" question, which needs a
  tag on Spark's nodes; it is recorded in section 9 rather than taken here.
- Row 315's files (`VarkaClassDump`, `VarkaErrorContext`, `VarkaKernelFailure`) and the failure
  catches of `serveBatch`; row 314b's error conditions.
- The warm-up's verdict as its own counter (item 65's extra) is `numWarmupBatches`, which exists.

### 3.4 Registered op counts

None: no emitted byte changes.

## 4. Files

| file | what |
|---|---|
| `VarkaExecMetrics.java` | the path sub-record, the reason list, their registration |
| `varka/VarkaReasonMetric.java` | new: the custom metric type and its "not reported" rendering |
| `varka/VarkaFallbackAccounting.java` | attribution per status, the task-end flush |
| `VarkaEvaluatorBase.java` | rows and time per path in `serveBatch`, the flush's registration |
| `varka/VarkaKernelRunner.java`, `VarkaColumnarToRowExec.scala` | the decline hook as a status word |
| `VarkaFilterExec.scala` | `EXPLAIN` from the whole condition |
| `VarkaFallbackReasonSuite.scala`, a Java helper beside it | the suite |
| `VarkaEmitterParityBenchmark.java` | the exact-status section |
| `docs/sql-varka.md`, `sql/varka/skills/` | the metrics and how to read them |

## 5. Tests, and what each is for

- **A planted single reason**, end to end on the cached Arrow table: `day-range` planted, answers
  equal the row engine's, that reason's metric counts every batch, the others read 0. Catches a
  reason routed to the wrong metric, or a metric never set.
- **Real data**: the far-offset fixture's `year(date_add(d, off))`, whose day-producer guard
  declines the far batch; the helper asserts `day-producer`. Catches a planted-only path.
- **A planted folded set** of two kernel reasons under the default options: both read "not
  reported" in the UI string, the others 0; under `exactRefusalStatus` the same plant reads as
  counts. Catches the row's "never 0".
- **Rows and time**: the kernel and fallback rows add up to the input rows; the kernel time is
  reported when a batch ran on the kernel; the row-out node has no `fallbackTime`.
- **AQE**: a query with a shuffle under adaptive execution: the final plan's Varka node carries
  the reason counts, and `EXPLAIN FORMATTED` after the run still prints the residual entry's
  reason.
- **The filter's `EXPLAIN`**: a split predicate prints the residual conjunct with its reason.
- **The rendering**: `VarkaReasonMetric` over all-reported, none-reported and mixed task values.
- The existing decline tests, whose hook changes form.

## 6. The measurement

Owed, section 3.2: the command and the rule are there. The machine this task ran on carried other
agents' jobs, and the owner's direction was not to take it there.

### 6.1 Predictions, registered before the run

1. The exact arm is within the flat threshold of the folded arm on the null-free and mixed-null
   rows at both widths: one more mask register and one more `OR` per method per lane group, in a
   method dominated by the calendar's arithmetic.
2. The new metrics cost no measurable time per batch: two `nanoTime` calls and a few array adds
   per batch of 4096 rows. Not measured by this task; `VarkaEvaluatorOverheadBenchmark` is where a
   reader would see it.

## 7. Risks

1. **A task-end write that never reaches the driver.** If accumulator updates were collected
   before completion listeners ran, every reason would read "not reported". The planted
   single-reason test reads a count, which a lost write cannot produce.
2. **A history server without the class.** The listener renders "N/A" for a custom metric class it
   cannot load. The class is in `sql/core`, which a history server of this build carries.
3. **Nine more metrics per node** lengthen the UI's node box. Accepted: they are the row's ask, and
   a node that declined nothing shows them as 0.
4. **Row 315 edits the same evaluator.** This task stays in the decline and metrics branches.

## 8. Sequencing

1. This plan; row 313 marked Planned.
2. The metrics, the hook, the filter's `EXPLAIN`, the suite.
3. The benchmark section, the docs and the lesson.
4. The outcome.

## 9. Outcome

Built on 14 October 2026 in two commits after the plan; the measurement of section 6 is owed.

**What landed, against section 3.** As designed, with one finding the plan did not have:

- The reason metrics (`VarkaReasonMetric`), the rows per path on every node, the kernel time on
  every node and the fallback time on the two columnar-out nodes; the decline hook as a status word; the filter's
  `EXPLAIN` from the whole condition; the benchmark section, whose case ids 985 and 986 the dry
  run (`dev/varka_bench_ids.sh`) accepts and whose setup checks that neither arm declines.
- **Section 3.1's -1 sentinel was wrong, and was replaced before the pull request.** The review
  (`/code-review high` on the branch) found that a metric left at -1 for "not reported" and set at
  the task's end does not survive Spark: `DAGScheduler.updateAccumulators` drops an update whose
  `isZero` holds, so the event log and a history server never see the -1; a coalesce runs one
  evaluator per parent partition in one task against one metric copy, and the first `add` turns
  -1 into 0; and the driver's merge skips it, so `value` read an attributed sum the UI
  contradicted. Each reason's metric now carries two sums in one value: an exact decline adds 1,
  a decline whose status named the reason among others adds `1L << 32`, and the renderer decodes
  them into a count, "not reported", or both. The task-end flush and its listener registration
  went with the sentinel. A tenth reason, `unrecognised`, counts status bits no name covers, which
  the first version counted under no reason (another review finding). The lesson is in
  `skills/testing-and-debugging.md`.

**The tests.** `VarkaFallbackReasonSuite`, 6 tests (section 5's planted reason, folded pair, real
data, AQE, split filter and rendering); one more in `VarkaEvaluatorComponentsSuite` for the
attribution rule; the rows, time and reported zeros in `VarkaProjectExecSuite`'s metrics test. Two
mutations were run against the suite: counting every status as attributed fails the folded-pair
test, and rendering the filter from the fused conjuncts fails the split-filter test. Sixteen
Varka suites around the change pass after the review's fixes, 275 tests (6 cancelled by their
option-matrix assumptions, as on master).

**Section 7's risks.** 1, a task-end write lost: moot once the flush was removed. 2 and 3 stand
as written, 3 with ten metrics rather than nine. 4: the change stays out of row 315's files and the failure catches.

**The review's other findings.** Ten in all; the sentinel (three findings) and the unrecognised
bits are above. Fixed: `fromNode` again fails the plan on a metric key one side renamed (the first
version looked keys up leniently so that `fallbackTime` could be absent; now only that key may
be), and the two timing brackets are one helper. Described rather than changed: `fallbackTime`
includes the per-row machinery's one-time compile and `varkaKernelTime` excludes the kernel's
emission, and on a row-out node the kernel time ends at the kernels' output batch, before the
residual entries and the row conversion run lazily - both now said in the metric descriptions and
`docs/sql-varka.md`; a projection that only forwards columns counts its rows with Varka's path,
whose description now says so. Accepted: ten more metrics per node, the row's own ask (risk 3).

**The matrix on master, 15 October 2026.** The run after #824's merge failed one test, the
two-refusal-reasons test of `VarkaEmitterArithmeticSuite`, under `checkIntOverflow=false`: its
FAIL-mode addition holds an overflow refusal only with the check on, so with it off the method held
one reason and the overflow lanes read status 0 (`0 did not equal 40`). The test now forces the
option on, as the guard-day tests force `guardDayProducers`. A sweep of the 23 suites that the
merges of 14 and 15 October added or changed, over all 50 configurations and the defaults (1173
suite-configuration runs), found no other failure; the cancellations are the skip list's and the
`PinsDefaults` tests'.

**6.1, scored.** 1 held at the wide width and missed at the narrow one. The command of 3.2 ran
on GitHub-hosted runners on the fork on 15 and 16 October 2026 (the harness with the section's
title as `sections`, JDK 25.0.4.1; the laptop's canary read the cache loop 15% off its baseline
that evening, so it was not used). Four runs on one CPU model, an EPYC 7763 (`expected-cpu`
pinned), repeat the first measurement. Wide (256-bit datapath), three runs, the exact arm's rate
against the folded arm's: null-free 2.5%, 3.1% and 3.4% lower (1012.7, 1006.2 and 1003.8 against
1038.9, 1038.2 and 1039.0 M rows/s) and with mixed nulls 1.4%, 1.3% and 1.3% higher (860.0, 860.2
and 859.9 against 847.8, 848.9 and 849.2). Narrow (`-XX:MaxVectorSize=16`), two runs: null-free
3.9% lower in both (499.6 and 500.6 against 520.0 and 520.9) and with mixed nulls 3.7% and 3.8%
lower (427.7 and 427.8 against 444.0 and 444.9). The runs agree to 0.1 points at the narrow width,
so the gap is the arm's, not the day's; the first narrow run, on an EPYC 9V74, read 6.1% and 3.6%
lower (485.4 against 517.0; 399.9 against 414.7). The flat threshold of 3.2 is 3%: the narrow
width and the wide null-free pattern are at or past it, so the rule's "within it at both widths"
is not met. 2 was not measured, as registered; 314a's pair on the same day, 6.1 of
`VARKA-314.md`, read the evaluator's per-batch path within 4.1%.

**The owner's decision, 16 October 2026: exact status stays off by default.** It is a diagnostic
(it never changes an answer or whether a batch declines), the cost above falls on every query
with a method of several guard reasons, and a user or developer who needs the exact reasons sets
`spark.sql.codegen.varka.emit.exactRefusalStatus=true` for the session that diagnoses and pays for
them there. The alternative of 3.2 (attribution on decline) is not built; it would be a task of
its own if exact counts without the cost are ever wanted. The cause of the narrow-width cost is not
established (likeliest: one more live mask in a loop at register pressure); a comparison of the two
arms' compiled loops on the fastdebug JDK would name it.

**Left for later, not taken here.**

1. **The exact-status default** (3.2): decided on 16 October 2026 (6.1): off, the flag for the
   session that diagnoses. The cause of the narrow-width cost, and the alternative of 3.2
   (attribution on decline, a cost only declining shapes pay), are not taken.
2. **A plan with no Varka node says nothing about Varka.** A projection or filter with nothing to
   fuse stays Spark's node, and nothing in `EXPLAIN` says why; answering it needs a tag on Spark's
   nodes, as Gluten's `FallbackTags` do, and is the whole-plan half of the row's question.
3. **The benchmarks' provenance naming the fallback share** (item 65's last done-when clause): the
   rows per path now exist for a provenance writer to read; no benchmark reads them yet.

**Found by the option matrix, 15 October 2026.** Master's run after #815 failed three tests under
`guardDayProducers=false`: "a real out-of-range day is counted under the day producer's guard"
and the AQE test (both in `VarkaFallbackReasonSuite`, "Results do not match": the far-offset
batch is not declined and the kernel's wrapped days differ from the row engine's), and the
exact-status test in `VarkaProjectExecSuite` ("1 was not greater than 1 status int-overflow": the
method holds no day guard, so the status without the exact masks is the overflow bit alone).
The emitter shows the premise gone: `dev/varka_emit.sh "year(date_add(d, i))" --options
guardDayProducers=false` emits the four loop and epilogue methods with no `VectorMask` operations
(0, against 4 to 7) and 50 to 250 bytes shorter. The three tests now set
`withGuardDayProducers(true)` over the matrix's base; the three, run under the option before and
after, fail and pass. The same family as VARKA-269's planted bug (see
`skills/testing-and-debugging.md`): a test's premise that an option removes.
