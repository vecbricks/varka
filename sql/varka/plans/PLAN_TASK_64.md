# Task 64: statistics-directed guard selection

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 64 and section 2.31, added on 4 September 2026 from
a question the owner asked about task 52's runtime guard: the input batch may
already know the range of a column, and then the per-lane check is work the
batch has proved unnecessary. Task 52 (#115) put a per-lane range guard on a
`date_add`/`date_sub` whose offset is a column and whose result a calendar
node reads, because the compiler cannot bound a column at compile time. The
guard's price is committed in the parity file
(`sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt`,
"year" section, task 52's A/B on `year(date_add(d, off))`): 2793.3 M rows/s
with the guard against 3166.2 without on null-free data (-11.8%) and 2821.2
against 3056.1 with mixed nulls (-7.7%) at 256 bits; 1193.1 against 1247.4
(-4.4%) and 1033.9 against 1229.2 (-15.9%) at 128 bits in the `-128bit-`
file. That is **4 to 16%** of the one shape that pays it, on every batch,
when in the corpus no batch ever carries an offset that could leave the
range.

*Requoted on 11 September 2026, and this time the range itself moved.* The
figures this paragraph carried until then - 2786.8 against 3100.8, 2030.9
against 2302.5, 1189.9 against 1237.2 and 727.5 against 855.3, read as "10-15%
of the one shape that pays it" - were taken on 8 September from task 63's
regeneration, and tasks 70, 71, 76 and 77 have regenerated both files since.
None of those eight numbers survives in a current file; they are in the files'
git history, which is why `dev/varka_quote_check.py` kept passing over them. A
plan whose admission check rests on numbers no file carries reads as clean and
is not.

**One of the four moved for a reason worth recording rather than requoting.**
The masked AVX-512 row went from 2030.9 to 2821.2 M rows/s, a 39% rise, and
the guard's share of it fell from 11.8% to 7.7%. The guard did not get
cheaper: task 70's bitmap pass took the per-group validity write out of
exactly this kernel, so everything around the guard got faster and the guard
became a smaller fraction of a smaller total. `PLAN_TASK_70.md` 9.5 saw the
same interaction from its side, noting that "task 64 removes the guard from
the in-range case and so widens what this task's rule drops". The reading for
this task is that **the prize erodes as the masked path improves**, and that
the 128-bit null-free case is now a 4.4% prize rather than the 10% the old
summary implied. The task is still worth building - section 2's check is
about the *pass* being cheaper than the guard, and that holds by a wider
margin than before - but it is worth building for the 128-bit mixed-null case
at 15.9% rather than uniformly.

The kernel that does not pay it already exists: task 52's
`guardDayProducers` option emits the unguarded bytes, the shape cache keys on
options, and the differential suite drives those bytes through the real
evaluator today (`VarkaDifferentialSuite`, "the reference variant" test over
`varka_dates_far_offset`). What is missing is the decision, per batch, of
which of the two classes to run. This plan is step one of 2.31 - compute the
bound with a vector pass and choose - and the design of step two - read the
bound the Arrow cache already stores - built behind its own switch.

## 2. The admission check, done

The design is worth building if the pre-pass costs less than the guard it
removes, on the batches that pass, and if those are all the batches the
corpus has. Both halves are answerable from committed numbers before any
code moves.

**The pass costs less than the guard.** The pre-pass is
`IntRangeOps.allWithin` over the offset column - one vector compare pass,
reading four bytes per row and touching no output - and task 56 already runs
exactly that pass, on exactly a `date_add(d, i)` kernel, for the interval
bound. Its price is the committed throughput pair
(`sql/core/benchmarks/VarkaThroughputBenchmark-jdk25-results.txt`): the
control `date_add, column offset (task 56 control)` at 201.3 M rows/s and
`date + CAST(i AS INTERVAL DAY), bound checked (task 56)` at 220.8 M rows/s
over 2M Arrow-cached rows; at 128 bits 192.4 against 212.4. *Requoted on 11
September 2026 alongside section 1, from the current file. The pair has now
been quoted three times - 199.2 against 211.6 at planning, 204.9 against
230.6 after task 63's regeneration, and these - and every version has said
the same thing, which is the point: the checked row is faster than its
control at both widths in all three.* The reading is unchanged and is in fact
firmer: the checked
row is *faster* in both files, by less than the rows' 2-3 ms standard
deviation, so the pass is below the noise floor of an end-to-end row whose
kernel is the cheapest shape there is. The guard, by
contrast, is 10-15% of a kernel measured at the parity harness's resolution
(section 1). A pass that cannot be seen against a 5 ns/row query is cheaper
than a guard that costs 0.05-0.14 ns/row of kernel time (`PLAN_TASK_52.md`
11's per-row figures), and the pass is paid once per batch regardless of how
many calendar consumers read the producer, while the guard is paid per
producer per lane group.

What this check would have rejected: a pass whose committed cost was a
visible fraction of the guard's, which would have made the selection a wash
on the null-free row and a loss on any batch that fails the pass and then
runs the guarded kernel too. The numbers say neither.

**Every real batch passes.** The selection bound for a bare date column is
`[NARROW_MIN_DAYS - CONTRACT_MIN_DAYS, NARROW_MAX_DAYS - CONTRACT_MAX_DAYS]`,
which with the constants in `VarkaChrono` is `[-4675410, 8449747]`: an offset
of about 12800 years back or 23100 years forward. Every fixture the
differential suite calls in-range, the throughput benchmark's `i` in
`[0, 2000000)`, and the parity benchmark's offsets in `[-10000, 10000)` lie
inside it. Only `varka_dates_far_offset` (offsets of +-20000000) and the
guard-compose fixture leave it, and those are the batches the guarded kernel
exists for. The bound narrows when the day operand is itself shifted (section
3.1 says how); the narrowest bound a fixture in the repo produces is still
millions of days wide.

What this check would have rejected: a bound so narrow that ordinary data
failed the pass, which would run the pass *and* the guard on real batches.

**Not measured here, deferred to section 6.** Whether the selection recovers
the guard's cost end to end - through the evaluator, with the second cache
lookup, at the throughput benchmark's resolution - is what the measurement
is for. The parity pair bounds what can be recovered; the throughput pair says
what is.

## 3. The design

### 3.1 The mechanism: the compiler records a bound, the evaluator chooses

Nothing in the emitter changes. The two kernels this task chooses between are
the two task 52 already emits - `VarkaEmitOptions.DEFAULTS` and
`DEFAULTS.withGuardDayProducers(false)` - and the shape cache already holds
each under its own key. This task adds no `VarkaEmitOptions` component
(section 3.2 says why that matters for #145) and asserts byte identity of
every kernel class it touches against master.

**The compiler records a selection bound.** `dayRange`'s `columnShifted` arm
in `VarkaExpressionCompiler` is where the compiler decides that a
`date_add`/`date_sub` with a column offset is a guarded producer: it knows
the day operand's interval `[dlo, dhi]` (a `Bounded` result of the recursive
call; `Unknown` never reaches the guard) and the offset's `ColumnRef`. The
condition under which no lane can leave the calendar range is a bound on the
offset alone:

    date_add(days, off):  NARROW_MIN_DAYS - dlo <= off <= NARROW_MAX_DAYS - dhi
    date_sub(days, off):  dhi - NARROW_MAX_DAYS <= off <= dlo - NARROW_MIN_DAYS

For a bare column operand this is 2.31's `[NARROW_MIN_DAYS - CONTRACT_MIN_DAYS,
NARROW_MAX_DAYS - CONTRACT_MAX_DAYS]`; for a literal-shifted operand the
bound shifts with it; for a guarded producer whose day operand is itself a
guarded producer (`year(date_add(date_add(d, off1), off2))`, which fuses
today with both producers guarded) the inner producer's interval is the
narrowed range itself and the outer bound is `[0, 0]` - a bound so tight that
ordinary offsets fail it, which is correct: the inner producer can land
anywhere in the narrowed range, so no non-zero outer offset is safe without
the guard, and the guarded kernel runs, as it does today. (Task 52's
`varka_dates_guard_compose` shape, `add_months` over a guarded `date_add`,
is different: `dayRange` widens the producer's interval by the month shift
and `admitCalendar` declines it at compile time, so no kernel and no bound
exist for it.) The bounds are collected by a walk over the admitted subtree
in `admitCalendar`'s successful arm, once per calendar node, so a producer
under two calendar consumers is recorded twice and `distinct` collapses the
pair.

The record is `VarkaGuardBound(inputIndex, lo, hi)`, a case class beside
`VarkaInputBound` with the same shape and a different contract, carried on
`CompiledVarkaProjection` as `guardBounds: Seq[VarkaGuardBound] = Nil`. It is
keyed by the offset `ColumnRef`'s kernel input index directly - the IR has no
ordinal, and an accepted entry's indices never move because a declining
entry truncates the input table back to its own mark - and noted through the
`DeclineSink` with its own mark and truncation, so a residual entry leaves no
bound behind: the discipline the task 56 bounds follow at lines 328-344. The
two lists are kept separate rather than tagged on one, because their
evaluator semantics differ in kind: a failed `inputBounds` check *declines
the batch*; a failed `guardBounds` check *selects the guarded kernel*. A
reader of either list must never have to ask which. The guard bound is
computed in `Long` and clamped to `Int` at the record (the day operand's
interval can put a bound past `Int` range, in which case that side is
unconstrained).

The compiler's set of calendar consumers (the twelve expression arms that
reach `admitCalendar`, through `calendarInput` or the `ThursdayOf` arms) and
the emitter's (`isChrono`, whose `chronoChild` switch names the ten IR node
types those arms build) must agree on *which* producers are guarded, or the evaluator will
select the unguarded kernel for a producer the emitter guards under a
consumer the compiler did not walk. They agree by construction today, and
section 5 pins that with a test that emits both kernels for every calendar
consumer and asserts the compiler recorded a bound exactly when the two
kernels' bytes differ.

**The evaluator chooses.** `FusedRunner` resolves two cache entries when the
plan carries guard bounds and the switch is on: the guarded kernel under the
current options (what it resolves today) and the unguarded one under
`options.withGuardDayProducers(false)`. Both lookups record the execution in
the cache's side table under their own shape hash, so the telemetry of task
16 and the compilation watch of task 50 see two classes for one node, which
is what is running. Per batch, after `fillSources` has bound the inputs and
run the task 56 check, one line decides:

    val kernel =
      if (runner.unguarded != null && allGuardBoundsHold(runner, len))
        runner.unguarded
      else runner.kernel

where `allGuardBoundsHold` is `plan.guardBounds.forall(b => IntRangeOps
.allWithin(srcData(k), srcValidity(k), srcNullCount(k), len, b.lo, b.hi))`
- every guarded producer's offset must pass, because the unguarded class has
no guard on any of them. A null offset lane is a null result lane, not a
range risk, and `allWithin` already skips dead lanes. A plan with no guard
bounds (the common case: every kernel without a column-offset producer under
a calendar node) resolves one entry and pays nothing, not even the null
check, because `unguarded` is a field decided at runner construction.

When the unguarded kernel runs and a lane would have left the range, the
answer is wrong - which is exactly why the choice is made only when the pass
has proved no lane can. The soundness argument is the same one `dayRange`
makes at compile time, instantiated per batch: the day operand's interval is
a compile-time fact, the offset's interval is now a batch-time fact, and their
sum is inside the narrowed range. The guarded kernel remains the answer for a
batch whose offsets say "maybe", and a far offset still declines through the
route task 52 built.

**A metric names the choice.** `numVarkaGuardsElided` joins
`VarkaExecMetrics` (a tenth `Option[SQLMetric]`, registered in the one metric
set the four nodes share, description "batches that ran the unguarded kernel
because every guarded offset column was inside its bound (task 64)"), and is
the assertion surface of every end-to-end test in section 5: a batch counted
there ran without the guard, a batch not counted there ran with it. This is
the discipline of `SKILLS.md`'s "Metrics as the 'did it really run' proof",
and the same reason task 52 asserted `numFallbackBatchesDeclined` rather than
values.

**The switch.** `spark.sql.codegen.varka.guardSelection.enabled`, a session
`SQLConf` entry beside `VARKA_ENABLED` in the same style (`.internal()`,
`.version("5.0.0")`, `.withBindingPolicy(ConfigBindingPolicy.NOT_APPLICABLE)`
with the same reason - the evaluator's choice between two kernels that
compute the same function cannot change what a view resolves to -
`.booleanConf`, default decided by section 6.1's rule, provisionally `true`).
Read once per node in `doExecute` and passed to the evaluator factory the way
`varkaClassDumpDirectory` is, never per batch. A SQLConf entry rather than a
`VarkaEmitOptions` component because the choice is the evaluator's, not the
emitter's: the emitted bytes are the same under either setting, so the shape
key must not move with it, and `docs/sql-varka.md`'s "No unused
configuration" rule applies - the entry has a test that flips it and observes
the metric. `SparkConfigBindingPolicySuite` (`sql/hive`) runs after the entry
lands; it fails the build on any entry without a policy.

**Step two, designed here, built behind its own switch in a follow-up
commit of this task if step one's numbers say it pays.** `ArrowCachedBatch`
carries `stats: InternalRow` with five fields per cache column - lower bound,
upper bound, null count, row count, size - computed by `collectStatistics` at
cache time and read today only by the scan's batch pruning. The iterator that
turns a cached batch into a `ColumnarBatch` (`ArrowCachedBatchSerializer`,
`next()` at line 1379) has the `ArrowCachedBatch` in hand and drops its
stats. Step two keeps them: a `VarkaArrowColumnarBatch extends ColumnarBatch`
(Spark's class is not final and the fork owns both ends) that carries the
stats row and the column-index map, constructed there instead of the plain
`ColumnarBatch`; the evaluator, before the pass, asks the batch for the
offset column's `[lower, upper]` and skips `allWithin` when the interval is
already inside the bound, falling through to the pass when the batch is not a
Varka one (a filter's re-wrapped batch, a `VarkaProjectExec` output). The same
read answers task 56's interval bound, so both checks skip together. Its own
switch, `spark.sql.codegen.varka.guardSelection.cachedStatistics`, because it
touches the cache path every Arrow-cached scan takes, Varka or not, and a
regression there must be attributable. Predicted gain over step one: the pass
is already below the noise floor of the throughput row (section 2), so step
two's measurable effect is zero on that row; its value is the channel - the
same stats prune whole batches under a filter inside the fused pipeline
later, and the file-level bounds of `SCOPE_MILESTONE_8.md` item 8 arrive
through it. If step one's throughput pair shows the pass at under 1%, step
two is built for the channel with the prediction "no measurable change"
registered, or deferred to the milestone that needs the channel; the owner
decides from step one's numbers, recorded in section 9.

### 3.2 What is deliberately unchanged

* **The emitter.** No component is added to `VarkaEmitOptions` and no class
  changes bytes. PR #145 (task 70) rewrites the masked body and adds a
  component of its own; this task's footprint - compiler analysis, evaluator,
  a config entry, a metric - is disjoint from it, and keeping it so is what
  lets the two merge in either order. The one semantic dependency runs the
  other way: with #145 merged the unguarded kernel is a one-body kernel, so
  the gain this task realises is measured against #145's numbers, not
  master's (section 6).
* **Task 52's analysis and guard.** Which producers need a guard, and the
  guard's bytes, are task 52's. This task decides per batch whether a given
  one runs.
* **Task 56's decline bound.** A `CAST(i AS INTERVAL DAY)` past its limit still
  declines the batch; the two bound lists are separate (3.1) and the interval
  check runs first, so a batch that would throw on the row engine never
  reaches the kernel choice.
* **`add_months` with a column count (task 60).** Its guard protects its own
  month arithmetic and its bound is on the count, not a day; a selection for
  it is the same mechanism over a different bound, and belongs to a task that
  measures that kernel. Debt register, not this plan.
* **The far-offset route.** A batch outside the bound runs the guarded kernel
  and declines as today; `varka_dates_far_offset`'s tests are unchanged and
  keep passing under both switch values.
* **The Arrow cache's stats format and pruning.** Step two reads what is
  there; it adds no statistic and changes no pruning decision.

### 3.3 Registered op counts

Unchanged, by construction: this task emits nothing new. The two kernels it
chooses between are task 52's, whose op counts are in `PLAN_TASK_52.md` 3
and asserted by `VarkaLoopEmitterSuite`. Section 5's byte-identity test
asserts that under both switch values, and with and without guard bounds
recorded, the classes resolved from the cache are byte-identical to the
classes master emits for the same options.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/.../codegen/VarkaExpressionCompiler.scala` | `VarkaGuardBound`; `guardBounds` on `CompiledVarkaProjection`; `DeclineSink.guardBound`/`guardBounds`, truncated with the task 56 bounds; `columnShifted` computes and records the bound from the day operand's interval |
| `sql/catalyst/.../codegen/VarkaExpressionCompilerSuite.scala` | the bound's arithmetic per producer shape (section 5) |
| `sql/core/.../execution/VarkaKernelEvaluator.scala` | `FusedRunner.unguarded` (second lookup under `withGuardDayProducers(false)`); the per-batch choice after `fillSources` in `computeFused` and `filterMask`; `numVarkaGuardsElided` in `VarkaExecMetrics` and its registration |
| `sql/core/.../execution/VarkaColumnarToRowExec.scala` and the three sibling nodes | read the switch in `doExecute`, pass it to the evaluator factory beside `classDumpDirectory` |
| `sql/catalyst/.../internal/SQLConf.scala` | `VARKA_GUARD_SELECTION_ENABLED` beside `VARKA_ENABLED`, with its `def` at the accessor block |
| `sql/core/.../execution/VarkaDifferentialSuite.scala` | end-to-end tests on the metric, both switch values, project and filter paths |
| `sql/core/.../execution/VarkaSharedSessions.scala` | `cacheDatesShiftedOffset`: a fixture whose day operand is literal-shifted so the narrowed bound is exercised |
| `sql/core/.../execution/benchmark/VarkaThroughputBenchmark.scala` | the task 64 pair beside task 56's (section 6) |
| `sql/core/benchmarks/VarkaThroughputBenchmark-jdk25-results.txt`, `-128bit-`, `-provenance.txt` | regenerated |
| `sql/varka/bench/src/main/java/.../Surface.java` | `Entry.projection("year(date_add(d, i))")` beside `date_add(d, i)`, so the surface run covers the selected shape |
| `sql/hive/.../configaudit/SparkConfigBindingPolicySuite.scala` | run, not edited: the new entry declares its policy inline |
| `docs/sql-varka.md` | the calendar-guard bullet (the per-batch choice, the metric), the config table row, the metrics list |
| `sql/varka/plans/PLAN_MILESTONE_4.md` | row 64 Planned then DONE; 2.31 update note; debt entries from 3.2 |
| `SKILLS.md` | the lesson (section 9 names it) |
| `dev/varka_quote_allowlist.txt` | only if a figure quoted here loses provenance to a squash, per the task 70 lesson |

Step two adds `ArrowCachedBatchSerializer.scala` (the batch subclass and its
construction in `next()`), the second config entry, and a stats-read fast
path in the evaluator; listed when built.

## 5. Tests, and what each is for

Compiler (`VarkaExpressionCompilerSuite`, constants read from `VarkaChrono`,
never retyped; `HI = NARROW_MAX_DAYS - CONTRACT_MAX_DAYS`, `LO = NARROW_MIN_DAYS
- CONTRACT_MIN_DAYS`):

1. `year(date_add(d, off))` records one guard bound on `off`'s kernel input
   with `[LO, HI]`; `year(date_sub(d, off))` records `[-HI, -LO]`. Catches a
   sign error in the `date_sub` arm, which no end-to-end test with symmetric
   fixtures would.
2. `year(date_add(date_add(d, 1000), off))` records `[LO - 1000, HI - 1000]`.
   Catches a bound computed from the column contract instead of the operand's
   interval - the error that would let a literal shift plus a passing offset
   leave the range unguarded.
3. `year(date_add(date_add(d, off1), off2))` (two guarded producers) records
   `[LO, HI]` for `off1` and `[0, 0]` for `off2` (`NARROW_MIN_DAYS -
   NARROW_MIN_DAYS` to `NARROW_MAX_DAYS - NARROW_MAX_DAYS`). Catches an outer
   bound that forgot the inner producer's interval is the whole narrowed
   range. `year(add_months(date_add(d, off), m))` records nothing because it
   declines at compile time, as today.
4. `date_add(d, off)` alone and `datediff(date_add(d, off), d)` record no
   bound. Catches a bound recorded for a producer no calendar node reads,
   which would make the evaluator resolve a second class for nothing (and
   would have failed the byte-identity test below with a confusing message).
5. `year(date_add(d, off))` beside a declining entry in the same projection:
   the bound survives; a declining entry containing the producer leaves none
   (`truncateBounds`). Catches the residual-entry leak the task 56 bounds
   guard against at 328-344.
6. Agreement: for every calendar consumer the compiler admits (the twelve
   arms that reach `admitCalendar`: `year`, `month`, `dayofmonth`, `quarter`,
   `dayofyear`, `last_day`, `weekofyear`, `yearofweek`, `trunc` with a
   literal and with a column format, `add_months`, and `d + INTERVAL '1'
   YEAR`) over `date_add(d, off)`, the compiler records a bound if and only
   if the emitter's classes under `guardDayProducers` on and off differ in
   bytes. Catches the two lists of "calendar consumers" drifting apart - the
   one failure that turns this optimisation into a wrong answer.

Evaluator (`VarkaDifferentialSuite`, metric assertions, both vector widths):

7. Switch on, `varka_dates_nullable_offset` (in-range offsets, nulls in both
   columns): `year(date_add(d, off))` matches the row engine,
   `numVarkaGuardsElided == numVarkaBatches`, `numFallbackBatchesDeclined ==
   0`. The headline: every in-range batch ran unguarded.
8. Switch on, `varka_dates_far_offset`: `numVarkaGuardsElided == 0`,
   `numFallbackBatchesDeclined > 0`, answers match. The far batch still
   declines, through the guarded kernel.
9. Switch off, both fixtures: `numVarkaGuardsElided == 0`; declined counts as
   on master. The switch does what it says, and the config is not unused.
10. Filter path, switch on: `WHERE year(date_add(d, off)) = 2024` over the
    nullable-offset fixture through `VarkaFilterExec` (the `collectFirst`
    metric idiom): elided count equals the batch count, answers match.
    Catches the choice made in `computeFused` and forgotten in `filterMask`.
11. `cacheDatesShiftedOffset`: `d` in the contract range, `off` values such
    that `off` is inside `[LO, HI]` but outside `[LO - S, HI - S]` for the
    fixture's literal shift `S`; `year(date_add(date_add(d, S), off))` has
    `numVarkaGuardsElided == 0` and answers match. Catches the evaluator
    reading the bound off the wrong list or the compiler recording the bare
    contract bound (test 2's failure, observed end to end).
12. Two guarded producers, `year(date_add(date_add(d, off1), off2))` over a
    small fixture with `off2` non-zero: elided count 0 under the switch,
    answers match, no decline. The `[0, 0]` bound in practice: the pass
    fails, the guarded kernel runs, and it is right. The
    `varka_dates_guard_compose` test is unchanged (residual, as today).
13. Cache accounting: with the switch on and a plan with a guard bound, one
    execution costs at most two misses and the hit count grows by two per
    task (the existing "at most one emission for one shape" test gains a
    twin for the two-shape case). `VarkaProjectExecSuite`'s `hits + misses
    === 3` pin runs `date_add(d, 3)`, which records no bound and resolves one
    entry, so it holds unchanged and is left as the witness that a plan
    without a bound pays no second lookup.

Byte identity (`VarkaLoopEmitterSuite`):

14. For `Year(AddDays(col0, col1))` and `AddDays(col0, col1)`, the class bytes
    under `DEFAULTS` and under `DEFAULTS.withGuardDayProducers(false)` are
    identical to the bytes the same options produce on master (pinned by the
    suite's existing `codeSize` and the shape hash oracle, which do not move
    because no option component is added). This is what "the emitter does not
    move" means, asserted.

No pinned fixture moves: no node type, no option component, no shape-hash
input changes. `everyNode` and `pinnedLineMap` are untouched.

## 6. The measurement

Two benchmarks, both regenerated with `dev/varka_bench_regen.sh` on an idle
machine, both widths. This section was written to wait for PR #145, because
the unguarded kernel is a one-body kernel only with task 70 and a
regeneration before that merge would have priced the selection against a
kernel that no longer existed a week later. **#145 merged, and section 1's
requote of 11 September 2026 is that post-task-70 number** - which is where
the masked AVX-512 row's 39% rise came from.

**The ceiling: the parity pair, already committed.** `year(date_add(d, off)),
producer guard on / off (task 52 A/B)` at both null patterns and both widths
is the kernel-level difference between the two classes the evaluator chooses
between. No row is added to the parity benchmark: this task changes no
kernel, and the parity harness bypasses the evaluator, so it cannot see the
choice. Section 1's values are the post-task-70 pair and are what
the predictions below are read against.

**What is realised: the throughput pair, added beside task 56's.** In
`VarkaThroughputBenchmark`, after the task 56 pair and on the same
`varka_dates` fixture (`i` in `[0, 2000000)`, inside the bound):

| row | query | switch |
|---|---|---|
| `year(date_add(d, i)), guard selected (task 64 A/B)` | `SELECT year(date_add(d, i)) AS y FROM varka_dates` | on |
| `year(date_add(d, i)), guard always (task 64 A/B)` | same | off |
| `date_add, column offset (task 56 control)` | existing | either; the control - no calendar consumer, no bound, one class, byte-identical under both settings |

`runQueries` gains an optional `confs: Seq[(String, String)]` applied with
`withSQLConf` around the varka session's case, so the two rows differ in the
switch alone and share the cached fixture, JIT and thermal state as adjacent
cases. The switch is named in both row labels so the labels survive the
default changing. The `year(d)` row already in the file is the second control:
a calendar kernel with no producer, whose number the selected row cannot beat.

The `Surface` entry `year(date_add(d, i))` is added so the surface run in
`sql/varka/bench` covers the selected shape, but the surface is not the
measurement - it is a coverage inventory (task 70's lesson: reading versus
counting inventories).

### 6.1 Predictions, registered before the run

1. **The selected row recovers the guard.** At 256 bits the selected row's
   rate is within 2% of what the unguarded parity kernel's advantage predicts
   for an end-to-end row - concretely, the gap between the two throughput
   rows is between 3% and 10% of the "guard always" row. Reason: the parity
   pair puts the guard at 11.8% of kernel time null-free at 256 bits and
   4.4% at 128 bits - so this prediction is registered against the wide
   width, and the narrow one is expected to land under it - and the throughput
   row spends roughly half its 5 ns/row outside the kernel (batch assembly,
   the cache read, the noop sink), which halves the visible fraction. If the
   gap is under 2%, the kernel is not where this row's time goes and the
   result is recorded as "not measurable at this resolution", not as a
   failure of the mechanism - the parity pair remains the mechanism's number.
2. **The pass is invisible.** The "guard always" row is within the run's
   noise (the control pair's spread) of master's `year`-family rows of the
   same shape, and the selected row is not slower than the always row in any
   run. Reason: task 56's pair puts one `allWithin` pass below the noise
   floor on the cheapest kernel; this task's pass is the same call on the
   same column.
3. **128 bits gains less null-free and the same with nulls.** The 128-bit
   selected/always gap is under 3% (the parity pair's 3.8% null-free
   ceiling, halved) and the mixed-null gap, if the fixture's 1/31 nulls
   count as mixed, is larger than the null-free one. Reason: the parity file
   says the guard's cost is null-pattern dependent at 128 bits (3.8% against
   14.9%).
4. **Two lookups cost nothing measurable.** The cold-start benchmark's
   `year(date_add(d, off))`-like shape (if one exists; otherwise the
   differential's cache-accounting test is the only witness) is unchanged
   within noise: the second lookup is a hash of the same IR under different
   options and one map probe per task, not per batch.
5. **No batch fails the pass.** `numVarkaGuardsElided == numVarkaBatches` on
   every fixture the suite calls in-range, and on the throughput fixture,
   asserted by test 7 and observed in the benchmark's metric dump.

**The rule that decides the default.** The switch defaults to on if
predictions 2 and 5 hold at both widths - the selection is never slower and
never fires the guarded kernel on ordinary data - regardless of how much of
prediction 1 is realised: a mechanism that costs nothing and is correct
belongs on. It defaults to off if the selected row is slower than the always
row in either run at either width, and section 9 explains why before anything
else is built on it. Prediction 1's magnitude decides whether step two is
built in this task (3.1's last paragraph).

## 7. Risks

1. **The compiler and the emitter disagree on which producers are guarded.**
   The evaluator then runs unguarded bytes over a producer the emitter would
   have guarded, or resolves a second class for nothing. Test 6 is the check,
   and it is the one test in this plan that must run under every calendar
   arm, including the ones added after task 52 (`trunc`, `weekofyear`, the
   year-month interval arm, and task 67's shapes if they land first).
2. **A bound computed from the wrong interval.** The bare contract range
   instead of the operand's interval makes a literal-shifted operand plus a
   passing offset leave the range unguarded - a silent wrong year. Tests 2
   and 11 are the check at both layers.
3. **The choice made on one path and not the other.** `computeFused` and
   `filterMask` each call `fillSources` then `invokeFused`; a choice inserted
   in one is silently absent in the other, and the filter would keep paying
   the guard. Test 10.
4. **The task 56 check and the guard pass read the same column twice.** For
   `year(d + CAST(i AS INTERVAL DAY))` both lists carry a bound on `i`, and
   the two passes are two reads. Correct but wasteful; the intersection of
   the two intervals is one pass. Recorded as a debt item unless the
   measurement shows the second pass, which prediction 2 says it will not.
5. **The metric double-counts under a declined batch.** A batch that passes
   the guard pass, runs unguarded, and then declines for another reason (a
   derived-input decline, a task 60 month guard) is both elided and declined.
   The metric is incremented after `invokeFused` returns status 0, not at the
   choice, so a declined batch is never counted as elided. Test 12's compose
   fixture plus a month-count guard in the same projection is the witness if
   one is cheap to build; otherwise the ordering is documented at the
   increment.
6. **Cache counts that assumed one lookup per task.** A plan with a guard
   bound resolves two entries per task. `VarkaProjectExecSuite`'s pin runs
   `date_add(d, 3)` and is unaffected (test 13); any other test that counts
   hits or misses over a calendar-over-column-offset shape moves by a factor
   of two and says why at the assertion.
7. **#145 merges mid-task.** The footprint is disjoint (3.2), so the merge is
   mechanical; the measurement waits for it regardless (section 6). If #145
   does not merge before the measurement is due, the numbers are taken
   against master and re-taken after the merge as a second regeneration, and
   both are recorded (task 70's lesson: a default decided from a
   regeneration costs a second one).

## 8. Sequencing

1. **Commit 1 - the plan.** This file; `PLAN_MILESTONE_4.md` row 64 marked
   Planned with the plan linked.
2. **Commit 2 - the compiler's bound.** `VarkaGuardBound`, the sink
   plumbing, `columnShifted`'s arithmetic, tests 1-6. Green on its own: the
   evaluator ignores the new list, no byte moves, no committed number moves
   (`git diff --stat` shows no `benchmarks/` file).
3. **Commit 3 - the evaluator's choice, behind the switch.** The config entry
   (then `build/sbt 'hive/testOnly *SparkConfigBindingPolicySuite'`), the
   second lookup, the per-batch choice on both paths, the metric, tests
   7-14. Default provisionally on; the differential's switch-off tests pin
   the reference behaviour.
4. **Commit 4 - the measurement.** After #145 merges: merge master, add the
   throughput pair and the `Surface` entry, regenerate both throughput files
   on an idle machine, score 6.1, set the default by its rule, write section
   9, update `docs/sql-varka.md`, the milestone row and notes, `SKILLS.md`.
5. **Commit 5, conditional - step two.** Only if 6.1's rule and 3.1's last
   paragraph say so; otherwise a debt entry naming what it waits for.
6. Push to `fork`, PR against `origin` `master` from the
   `PULL_REQUEST_TEMPLATE`, soft-wrapped, attribution lines; the CI queue
   policy applies (one Build run at a time, in merge order).

## 9. Outcome

<!-- Filled in when the measurement lands: the numbers with the committed file
     they trace to (dev/varka_quote_check.py holds you to this), 6.1's
     predictions scored one by one, what moved that the plan did not list, and
     what the task leaves for later - which goes to the milestone's debt
     register or a scope document, never to a code comment. -->
