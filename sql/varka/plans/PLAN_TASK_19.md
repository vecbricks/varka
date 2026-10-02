# Task 19: fuse profitability, decided

Milestone 3 section 2.2 (`PLAN_MILESTONE_3.md`), the question task 14 raised
and task 17 declined to answer on stale numbers: should `VarkaColumnarRule`
decline a fusion whose consumer wants rows? Task 18 handed this task the
matrix it asked for - the row-consumer chains flat at 0.8x, no crossing at
any depth - and this task settles the policy on it.

## 1. Why the committed matrix cannot decide this alone

Every committed row-consumer case fuses only cheap adds: `date_add`/
`date_sub` chains (depths 1-8), the all-fused `date_add` control, and two
residual shapes. Post-18, their varka wall time decomposes into ~6 ms of
kernel work and ~45 ms of batch assembly plus per-row read-back - the
read-back is nearly the whole cost, so these shapes are the *worst case*
for assemble-then-read, and the committed 0.7-0.9x band measures little
else.

No heavy op has a row-consumer case at all. The columnar side's largest
wins - `dayofweek` 9.2x (64 ms Janino vs 7 ms varka), `CASE WHEN`
unpredictable 7.0x, `datediff` 5.2x, nested 5.7x - are exactly the shapes
where Janino's per-row cost is big enough that fusion could pay *through*
the read-back: `dayofweek`'s arithmetic says ~7 + ~45 = ~52 ms against a
baseline that costs 64 ms before any row-consumer overhead of its own.
Deciding "decline row consumers" on the committed matrix alone would
decide it on the one family fusion helps least.

So this task measures first: four heavy-op row twins added to
`VarkaThroughputBenchmark` (`dayofweek`, `case when unpredictable`,
`datediff`, `nested projection`), each reusing its columnar case's SQL
verbatim so the pair differs only in the consumer.

A second stale number gets restated on the way: the "~16 ns/row read-back"
quoted in `docs/sql-varka.md`, `PLAN_MILESTONE_2.md` and `PLAN_TASK_14.md`
came from the task-14 depth-8 pairing (35.8 vs 19.7 ns/row) and was
contaminated by the per-task JIT warm-up that task 18 removed; the
committed post-18 delta is ~5-6 ns/row on the same shapes (residual-heavy
+13.8 the outlier).

## 2. Predictions, registered before the run

Written before the extended matrix was first executed; scored in section
6. The mechanism behind them: varka's row-consumer time is roughly
(columnar kernel time + a fixed assembly/read-back share of ~45 ms on 2M
rows), while the Janino baseline pays its columnar wall time plus its own
row overhead.

1. The cheap chains stay at 0.8x (0.7-0.9x band), flat in depth.
2. `dayofweek, row consumer` **wins**: predicted 1.2-1.3x (varka ~52 ms vs
   baseline >= 64 ms).
3. `case when unpredictable, row consumer` lands at parity to a small win:
   predicted 1.0-1.1x (varka ~53 ms vs baseline ~57+ ms).
4. `datediff, row consumer` and `nested projection, row consumer` land at
   a small loss to parity: predicted 0.8-1.0x (their columnar baselines
   are 41-51 ms, close to the cheap-chain regime).

## 3. Decision criteria, registered before the run

Applied to the regenerated matrix, compared by minimums, with every
decision-bearing ratio (all under 1.3x by expectation) confirmed by an
interleaved second run per the task-14 rule:

* **Every row family below 1.0x** -> `VarkaColumnarRule` declines
  row-consumer fusions unconditionally: rewrite back to the baseline plan
  shape at both post-transition sites, an internal escape-hatch conf so
  the fused row path stays measurable, plan tests including one under
  AQE-on.
* **Some at or above 1.0x, some below** -> no blanket decline. A cost gate
  (one number computable at rule time from `compilePartial`'s fused IR)
  lands only if the measured winners and losers separate by more than the
  ~15% noise band with the threshold in the gap; otherwise the outcome is
  a recorded acceptance - no rule, the numbers and the reason written
  here, revisited when task 21's filters change the consumer mix.
* **Everything at or above 1.0x** -> no rule; the row-consumer loss is
  recorded as gone.

Settled with the project owner before implementation: measure-first, and
the gate-only-if-clean standard for the split outcome.

## 4. Design of the possible landings

Whichever branch section 3 selects, the machinery is planned here so the
decision only picks, never designs under time pressure.

### 4.1 The decline rule (unconditional or gated)

`VarkaColumnarRule.postColumnarTransitions` has exactly two sites where
the rule learns the consumer wants rows, and they are not symmetric:

* `ColumnarToRowExec(varka: VarkaProjectExec)` (the fuse-the-transition
  case): declining must *rewrite*, not merely stop matching - leaving
  `ColumnarToRowExec(VarkaProjectExec(...))` in the plan is worse than the
  baseline. The decline produces
  `ProjectExec(varka.projectList, ColumnarToRowExec(varka.child))`, the
  exact shape `insertTransitions` builds without Varka, so whole-stage
  codegen re-fuses the projection as if the rule had never run.
* The plain-`ProjectExec`-over-transition case: declining returns the
  project unchanged.

The columnar-write path (`VarkaProjectExec` with no transition above it)
is untouched by construction - no `ColumnarToRowExec` appears, so neither
site fires.

An internal escape hatch `spark.sql.codegen.varka.fuseRowConsumers`
(`SQLConf`, boolean, default false, version 5.0.0, binding policy
NOT_APPLICABLE) restores today's behavior. It exists so the fused row
path stays measurable: `VarkaThroughputBenchmark` and
`VarkaColdStartBenchmark` both consume rows (`toRdd`) and both guard on a
fused plan, so their varka sessions set it - and the cold-start results
file regenerates with the throughput file in the same session. The
differential and end-to-end row-consumer suites set it too: they exist to
exercise kernel correctness on that path, not the rule.

Surfacing: a declined plan has no Varka node for EXPLAIN to hang a field
on, so the decline logs at debug with a whole-projection reason
("declined: consumer wants rows"), the plan-side sibling of
`VarkaFusionReport`'s "no entry is Varka-eligible". The per-entry
`VarkaDecline` taxonomy stays compiler-side.

Tests: the three plan-level rule tests in `VarkaColumnarToRowExecSuite`
flip to expect the decline under the default conf, plus a case proving
the escape hatch restores fusion; `VarkaColumnarWriteSuite`'s row-consumer
expectation flips while its columnar-write cases must stay green (the
milestone's no-columnar-regression gate); one decline test runs under
AQE-on, because `assertNotFused` passes vacuously outside query stages
(the recorded milestone-2 lesson).

The gated variant differs only in the decline predicate: a threshold
constant on one number read from the already-computed
`PartialVarkaProjection`, its doc comment citing the measured basis.

### 4.2 Recorded acceptance

No plan-behavior change and no conf. The decision paragraph in section 6
carries the per-shape ratios and the corrected read-back ns/row; the
milestone table row closes on it; the docs' honest rows state the loss
and why it is accepted (heavy shapes win even through rows; task 21's
filters keep more output columnar); the milestone-2 register entry is
swept in the past tense.

### 4.3 Writing owed either way

`docs/sql-varka.md` (the honest row and Limitations, the stale ~16 ns/row
replaced by the measured delta, the conf table if 4.1 lands), `README.md`
(honest table row, the milestone promise), `PLAN_MILESTONE_3.md` (task-19
row, and the "Row consumers do not pay" why-bullet still quoting
0.6-0.7x), `PLAN_MILESTONE_2.md` (register entry swept), and the
`SCOPE_MILESTONE_8.md` claim that `.noop()` is a row consumer - it
contradicts this benchmark's class doc and one of the two must be fixed.

## 5. Measurement

Two full back-to-back runs of the extended matrix on the otherwise idle
committed-numbers machine (AMD Ryzen AI 9 HX PRO 370, JDK 25). Run A's
*columnar* `case when, unpredictable data` carried a varka-side outlier
(15 ms best against the usual 8; run B read 8, matching every prior
committed run), so run B is the committed file - the same double-run
disclosure discipline as task 18. On every decision-bearing row-consumer
case the two runs agree by minimums within 1-2 ms:

| case (row consumer, 2M rows) | baseline best | varka best | relative |
|---|---|---|---|
| chain depth 1 / 2 / 4 / 8 | 39-40 ms | 50-51 ms | 0.8x |
| `date_add` (all-fused control) | 40 ms | 51 ms | 0.8x |
| mixed projection | 51 ms | 61 ms | 0.8x |
| residual-heavy projection | 53 ms | 83 ms | 0.6x |
| **`dayofweek`** | 62 ms | 50 ms | **1.2x** |
| **`case when unpredictable`** | 57 ms | 51 ms | **1.1x** |
| `datediff` | 48 ms | 50 ms | 1.0x (0.96 by minimums) |
| `nested projection` | 49 ms | 50 ms | 1.0x (0.98 by minimums) |

The structural fact the matrix exposes: the varka side reads 50-51 ms
(25.0-25.7 ns/row) on *every* all-fused single-output shape, whatever the
fused work - `dayofweek`'s ~12-op fold and a depth-1 `date_add` cost the
same through rows. Assemble-then-read has a flat floor of ~25 ns/row, and
profitability through a row consumer is decided entirely by whether the
Janino baseline's own per-row cost clears it: `dayofweek` at 30.9 ns/row
and unpredictable `CASE WHEN` at 28.3 do (wins), the cheap chains at
19.5-19.9 do not (0.8x). The read-back premium over Janino on the cheap
chains is ~6 ns/row - restating the "~16 ns/row" that task 14 committed,
which was contaminated by the pre-cache JIT warm-up its own 7.5 diagnosed.

## 6. Outcome

Status: **DONE - recorded acceptance, no decline rule.**

### 6.1 Predictions scored: 4 of 4

1. **Cheap chains stay 0.8x, flat: right.** 0.8x at every depth, both runs.
2. **`dayofweek` wins 1.2-1.3x: right.** 1.2x (62 ms vs 50), stable
   across both runs.
3. **`case when unpredictable` at parity-to-small-win: right.** 1.1x.
4. **`datediff`/nested at small-loss-to-parity: right.** 0.96 and 0.98 by
   minimums.

The running project score is now 6.5 of 12 (task 14: 2.5 of 8, task 18:
2.5 of 3 - the mechanism model behind these four was task 18's own
decomposition, which is why they landed).

### 6.2 The decision

Applying section 3's pre-registered criteria to the split outcome: no
blanket decline (two families win), and **no cost gate**, because no
number available at planning time separates the winners from the losers -
fused-op count orders them wrongly (the 8-op cheap chain loses at 0.8x
while the ~6-op `CASE WHEN` wins at 1.1x), since the deciding quantity is
the *baseline's* per-row cost against the ~25 ns/row assemble-then-read
floor, and the rule cannot know Janino's cost at plan time. So
`VarkaColumnarRule` keeps fusing row consumers, and the acceptance is
recorded here with its numbers: the worst committed loss is bounded (0.6x
residual-heavy, 0.8x cheap chains, ~6 ns/row over Janino), the heavy
shapes win outright (1.1-1.2x), and task 21's filters move more consumers
columnar, shrinking the exposed surface. Revisit if a later milestone
gives the rule a real cost model for the row engine's side of the ledger.

### 6.3 Deviations and doc corrections

* No deviation from the plan's design sections; Phase R's machinery
  (section 4.1) was not needed and was not built.
* Docs and README requoted whole from run B per the one-run discipline -
  the columnar relatives drifted within noise (`dayofweek` 9.2x -> 9.8x,
  `datediff` 5.2x -> 5.6x, chains 6.5-7.2x -> 7.0-7.5x flat, `CASE WHEN`
  predictable 6.2x -> 5.8x, mixed-row 0.9x -> 0.8x, residual 0.7x ->
  0.6x), and the stale ~16 ns/row was replaced by the measured floor.
* `SCOPE_MILESTONE_8.md`'s claim that `.noop()` is a row consumer was
  wrong - this fork's noop write accepts columnar batches (that is what
  the columnar throughput cases measure) - and was corrected; the
  milestone-2 register's row-consumer entry is swept in the past tense.

## 7. Explicitly out of task 19

Filters and selection vectors (task 21), fallback-cause metrics and JFR
events (task 22), the task-18 review-residue debt (`PLAN_MILESTONE_3.md`
section 10), any emitter or kernel change, and any change to the columnar
consumer path - the milestone gate requires the columnar cases not to
regress.
