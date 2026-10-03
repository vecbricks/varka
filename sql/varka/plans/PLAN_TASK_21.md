# Task 21: filters and selection vectors

Milestone 3 section 2.3's second half (`PLAN_MILESTONE_3.md`), the milestone's
real reach work and its only plan-shape change: the mask becomes a first-class
value that leaves the emitted loop, `VarkaColumnarRule` rewrites `FilterExec`,
and the selected-batch contract (open question 2) is decided with a
measurement. The gate: differential on filter-heavy shapes including
all-selected and none-selected, and committed throughput against Janino on the
survey's `d_date BETWEEN` shape.

## 1. Why filters, and why now

The corpus survey (`SCOPE_MILESTONE_8.md` section 1) is unambiguous: 53-78% of
all date-column references sit in WHERE clauses, and the whole TPC-DS/TPC-H
corpus holds exactly five DATE-typed projection expressions. Until this task a
filter in the plan blocked fusion outright - the differential suite pinned
`WHERE d IS NOT NULL` as `expectFused = false`, and the IN benchmark's filter
anchors were committed unfused "by design until task 21". Task 20 built the
mask algebra this task ships: the EQ-OR chains, `IsNotNull`, and the budget
mirror all run inside conditions that until now could only feed an `IfElse`.

## 2. Decisions, and who made them

* **Split conjuncts** (project owner, at planning): a mixed `WHERE` fuses the
  conjuncts that compile and keeps the rest in a row `FilterExec` above the
  Varka node, mirroring the projection's per-entry eligibility - the survey
  says mixed predicates are the norm, so all-or-nothing would have declined
  most real WHERE clauses.
* **Both consumer paths** (project owner, at planning): the columnar
  `VarkaFilterExec` compacts selected rows into a fresh dense batch, and the
  row-boundary `VarkaFilterColumnarToRowExec` consumes the selection bitmap
  directly - emitting only selected rows during row conversion, no compaction.
  The benchmark prices both across a selectivity ladder; that measurement is
  what settles open question 2.
* **The v1 selected-batch contract**: compact at the columnar boundary,
  consume the mask at the row boundary. A selection vector cannot travel to an
  arbitrary consumer: no Spark operator understands one (there is no selection
  vector anywhere in the tree; ORC's is explicitly asserted away), and the
  evaluator's `canRun` invariant - Arrow valueCount equals batch numRows, the
  rule that keeps the kernel's null counts sound - is exactly what a
  mask-annotated batch would break. Varka-internal mask passing between
  stacked Varka nodes goes to milestone 4 item 11 with the compaction numbers
  this task commits.
* **Unknown is false at the mask root, written down** (the task-20 standard
  for null rules): a row is selected exactly where the condition is known
  true - SQL's WHERE semantics. The emitter gets it free: the known-true word
  is a subset of the operands' validity by construction, so `selected = kT`
  and the known-false word goes unused at a root, preserving `Not`'s
  zero-code slot swap. Recorded in `VarkaVectorIR.Cond`'s doc.

## 3. Design

* **No new IR node.** A filter root is an existing `Cond`; `canonical`
  already renders every condition, so the shape-cache key covers mask kernels
  with no cache change. The emitter's four "root means value" assumptions are
  relaxed instead: `analyzeRoot` accepts a `Cond`, the vector loop writes the
  root's known-true word (dense: the degenerate single mask) with
  `orValidityBitsAt` - the same call that writes output validity - and skips
  the data store, and the tail sets the row's bit iff its `tailKt`. The
  driver's zero-then-OR discipline doubles as the selection invariant: an
  unwritten row reads as unselected.
* **The all-null shortcut excludes Cond roots.** Found during implementation,
  not in the plan: the driver's all-null shortcut (return early when every
  output reads an all-null column) is wrong for an `Or` root -
  `Or(unknown, known-true)` is known true, so an OR over one all-null column
  and one live one still selects rows the zeroed bitmap would deny. Cond
  roots are excluded outright rather than reasoned about case by case; the
  loop needs no shortcut to be correct (an all-null input's word is 0L and
  contributes no known-true bits). Pinned by an emitter test.
* **The cache builder was silently dropping fused transitions - a latent
  wrong-results bug back to task 6, found by this task's differentials.**
  `CachedBatchSerializer.convertToColumnarPlanIfPossible` strips a topmost
  `ColumnarToRowTransition` to hand the cache columnar input - sound for the
  stock transition, whose only work is the row conversion, and wrong for the
  fused Varka nodes, which carry a whole projection or filter inside the
  transition tag. Caching a view whose top was the fused filter cached the
  unfiltered table; the same strip would have dropped a fused projection
  since task 6, unobserved because no test ever cached over fused work. The
  fix uses the API's own extension point: the Arrow serializer now converts
  a fused row node to its columnar sibling (`VarkaColumnarToRowExec` to
  `VarkaProjectExec`, `VarkaFilterColumnarToRowExec` to `VarkaFilterExec` -
  identical kernels, columnar output), keeping both the columnar fast path
  and the work. The caveat is documented on both row nodes, and the
  differential suite pins the cached-view case - vicious precisely because
  every direct query stays right and only a cached view materializes the
  dropped work.
* **Owned vectors opt out of consumer frees.** The cache writer (and any
  columnar consumer following the stock convention) calls
  `closeIfFreeable()` on batches it drains; plain `ArrowColumnVector` treats
  that as a real close, which frees Varka-owned buffers under the
  evaluator's own ledger (benign today only because Arrow's close is
  idempotent). Kernel outputs and compacted columns are now wrapped in
  `VarkaOwnedArrowColumnVector`, whose `closeIfFreeable` is the no-op the
  two-tier convention prescribes for producer-owned vectors - the same
  override `WritableColumnVector` makes.
* **Kernel ABI unchanged.** A mask root occupies an output slot whose
  `dstData` is 0L and never materialized (the same rule as an all-null
  input's validity address) and whose `dstValidity` receives the bitmap. The
  selected count is a popcount over the bitmap in the evaluator
  (`VarkaSelectionBitmap`, new Java, catalyst) - no return-type change, no
  new engine helper.
* **Compiler: a third entry point.** `compilePredicate` splits the condition
  on its AND spine and runs each conjunct through the existing `compileCond`
  with the task-12 table-rollback discipline; budgets are mirrored against
  the recombined root (the AND fold adds a node per conjunct), not the
  conjunct alone. The result classifies every conjunct
  (`VarkaConjunctSpec`), and the rule keeps residual conjuncts in a row
  `FilterExec` above - which then sees only the rows the mask kernel let
  through.
* **Runtime: a shared evaluator base.** The task-lifetime machinery
  (shape-cached runner, task allocator, open-batch ledger and its
  task-completion safety net, `canRun`, telemetry names) moved from
  `VarkaKernelEvaluator` into `VarkaEvaluatorBase`; the projection evaluator
  keeps batch assembly and vector allocation, and the new
  `VarkaFilterEvaluator` adds the reused selection buffer, the mask run, and
  compaction. Compaction is the honest scalar loop milestone 4 item 11 is
  expected to replace with `compress(mask)`: a typed Arrow-to-Arrow copy for
  `DateDayVector`/`IntVector` columns (so a stacked Varka node can still
  serve them) and one row pass through the standard row-to-column converter
  for everything else. Every output column of a compacting filter is owned -
  forwarding ends there, a forwarded vector cannot be shortened.
* **Nodes.** `VarkaFilterExec` (columnar out) and
  `VarkaFilterColumnarToRowExec` (the to-row fusion), sharing
  `FilterExec`-compatible output through `VarkaFilterExecBase`: the
  nullability tightening is copied rule for rule, because the rewrite must
  not change what the planner believes about the columns. Metrics are the
  projection nodes' vocabulary minus the residual-entry count (a filter's
  residual is a visible row `FilterExec`, not a number), with
  `numOutputRows` counting selected rows. Fallback causes and JFR events ride
  the task-22 machinery unchanged.

## 4. Predictions

Registered before any benchmark run, scored in section 5 (the task-14/19/20
discipline). The benchmark is `VarkaFilterBenchmark`: `d < DATE` over 2M
Arrow-cached rows at a selectivity ladder, columnar-terminal (the compacting
`VarkaFilterExec`) and row-consumer (the mask-skip
`VarkaFilterColumnarToRowExec`) variants, plus two stacked and two COUNT(*)
cases; and the `VarkaInExpressionBenchmark` anchor at 5 literals, which flips
from unfused to fused.

1. **Row consumer, low selectivity wins big.** At 0-15% selected, the mask
   skip beats Janino by at least 2x: the baseline evaluates the predicate on
   every row in row space while Varka pays a SIMD mask plus row emission
   only for survivors. The win shrinks as selectivity rises.
2. **Row consumer, high selectivity approaches the read-back floor.** At
   100% selected the shape degenerates to task 19's cheap row-consumer case
   (every row read back, ~25 ns/row floor): predict 0.8-1.2x, possibly a
   loss, consistent with the recorded acceptance.
3. **Columnar terminal wins across the whole ladder.** The baseline pays
   per-row predicate evaluation and row emission into noop at every
   selectivity; Varka pays the mask plus a scalar copy per selected row.
   Predict at least 2x at 0-50%, and at least 1.3x even at 100% - if the
   100% rung falls below 1x, the scalar compaction is the reason and
   milestone 4 item 11's `compress(mask)` case is made by measurement.
4. **The stacked shape holds the projection's win.** Filter (compacting)
   plus a fused `date_add` projection at 15% and 85%: predict at least 2x -
   the compaction must not eat the projection kernel's committed advantage.
5. **The flipped IN anchor wins.** `COUNT(*) WHERE d IN (5 literals)`
   fused: predict at least 1.5x over the same-session Janino baseline it
   was previously committed at parity with.

## 5. Outcome

Status: **DONE.** Scored against section 4, from the committed
`VarkaFilterBenchmark-jdk25-results.txt` and the regenerated
`VarkaInExpressionBenchmark-jdk25-results.txt` (one run, idle machine):

1. **Met.** Mask skip at 0/1/15%: 2.3x / 2.3x / 2.0x - and the win shrinks
   with selectivity exactly as predicted (1.5x at 50%, 1.2x at 85%).
2. **Met.** 1.1x at 100% selected, inside the predicted 0.8-1.2x band: the
   shape degenerates to the read-back floor and stays a hair above water.
3. **Met, beyond the prediction.** The compacting path wins the entire
   ladder at 2.3-2.7x - including 2.7x at 100% selected, where the
   prediction hedged at 1.3x. The typed scalar copy costs only ~1-3 ns/row
   over the mask itself (8.4 ns/row total at full selectivity against
   5.5-7.7 at none), so compaction never comes close to eating the win.
4. **Met.** Filter plus fused projection: 2.6x at 15%, 2.4x at 85%.
5. **Met.** The flipped IN anchor (`COUNT(*) WHERE d IN (5 literals)`): 2.0x
   against its previously-at-parity baseline; the over-cap anchors (50, 200,
   500) hold parity, the no-regression proof.

One honest loss the predictions did not call: **COUNT(*) at 85% selected is
0.8x** - nearly every row must be emitted through the row boundary into the
aggregate, which is the task-19 assemble-then-read floor wearing a filter,
and consistent with that task's recorded acceptance. The same shape at 15%
wins 1.8x. No new rule follows, for task 19's reason: no plan-time number
separates the two.

**Open question 2, settled by these numbers.** The v1 selected-batch
contract - compact into an ordinary dense batch at the columnar boundary,
consume the bitmap at the row boundary - is the recorded decision, and the
ladder says the design note's ~15% compaction threshold is not warranted at
these batch sizes: there is no selectivity at which compacting loses to the
row baseline (2.3-2.7x flat), so no threshold conf exists (the no-unused-
configuration rule holds trivially). Passing a selection vector between
stacked Varka nodes stays future work, now priced: it could save at most the
~1-3 ns/row typed copy, which also bounds what milestone 4 item 11's
`compress(mask)` can recover on this machine.

`VarkaColdStartBenchmark`'s freshness argument was re-derived per the
SKILLS.md rule and stands: it manufactures freshness by invalidating the
cache per timed iteration, which no shape-surface growth affects; its file
is untouched, as are the throughput and codegen files (no filter shapes -
verified, not assumed).

Deviations from the plan, all recorded in section 3 as they were found: the
all-null shortcut's Cond-root exclusion, the cache builder's
transition-strip bug (latent since task 6, fixed at the serializer's
extension point), and the owned-vector `closeIfFreeable` override. The
benchmark's 0%-selected rung also changed shape once: an always-false range
predicate is pruned whole by the in-memory scan's stats before the filter
node runs, so the rung uses an interval containing no whole day - selecting
nothing while staying unprunable (the differential suite's none-selected
case does the same, for the same reason).

## 6. The max review round

A maximum-effort review ran against the merged task-22 commit while this
task's branch was still local, and its fifteen findings were fixed here -
they overlap this task's files, and the shared evaluator base this task
introduced is where the deduplicated fixes belong. The four correctness
findings, all in the task-22 diagnostics themselves:

1. **The residual-entry count never reached the SQL UI.** The driver-side
   metric add was never posted through `SQLMetrics.postDriverMetricUpdates`,
   so the listener - the surface the SQL tab renders - always read zero
   while the driver-local accumulator the suites read carried the count:
   task 22's own "diagnosable from the SQL UI alone" gate was unmet as
   shipped. Both projection nodes now post it, pinned by a status-store
   test that goes through a real tracked execution.
2. **Empty batches were counted as "input not Arrow-backed".** `canRun`
   also refuses 0-row batches and the defensive no-plan case; the shared
   `recordRefusedBatch` now counts only a non-empty batch that actually
   fails the Arrow check, and the metrics test pins an empty Arrow batch
   at zero cause counts.
3. **Non-kernel failures were metered as kernel failures.** The per-batch
   catch also caught the lazy residual/merge projection's compile or
   evaluation errors - re-counted per batch, since a throwing lazy re-runs
   its initializer. `invokeFused` now marks genuine kernel throws with a
   wrapper the nodes match on; everything else catchable events and logs
   under the new `row-path-failure` cause with no metric (the cause set
   stays bounded; the event and warning carry it).
4. **The cache-lookup JFR event carried the raw, unbounded execution
   identity**, diverging from its own javadoc and from the side table's
   abbreviated copy - the advertised join between the event stream and
   `executionsFor` never matched past the bound. The identity is bounded
   once, for both consumers, pinned by an over-long identity in the JFR
   test.

Plus one efficiency regression - `kernelIdentity` (a SHA-256 over the
canonical IR) was recomputed eagerly per fallback batch even with JFR off,
on exactly the misconfigured-serializer path the task-22 plan called
untouched; it is a lazy val now and the event helper takes it by name -
and ten cleanups: the per-batch fallback accounting and the metric-map and
bundle definitions deduplicated into the evaluator base and the
`VarkaExecMetrics` companion (the four nodes had byte-identical copies);
one memoized compilation per node serving EXPLAIN and the residual count;
the JFR cause vocabulary as constants on `VarkaFallbackEvent`; a shared
`withJfrRecording` test helper that closes the recording on every path and
filters by event class rather than re-typed name strings; throwaway
metric registrations dropped from six suite sites; the default-restating
`@Enabled`/`@Threshold` annotations removed; the `VarkaExecMetrics`
sectioning fixed; the docs' only full event-name spelling un-split from a
line break; and the decision that `VarkaExecMetrics` stays Scala recorded
in its doc (named arguments over six same-typed optional fields, where a
Java record's positional constructor would be a silent-swap hazard).

The review also refuted two suspected defects, worth keeping: re-executing
a plan does not double-add driver metrics (`SparkPlan` memoizes `doExecute`
via `LazyTry`), and JFR's threshold semantics need no annotation defense.

### 6.1 The second round, on this branch

A second maximum-effort review ran against the branch itself (both commits)
and returned fifteen more findings, all fixed here. The three correctness
items were in the new filter work:

1. **The conjunct split reordered evaluation across nondeterministic
   conjuncts** - hoisting the fused date predicate below a `rand()` residual
   changes which rows the seeded stream sees, exactly the move Spark's own
   pushdown refuses (`span(_.deterministic)`). One nondeterministic conjunct
   now declines the whole predicate, pinned in the compiler suite and by a
   differential whose `rand(42) < 2.0` conjunct keeps the answers
   deterministic while the plan stays unfused.
2. **A failed selection-buffer grow left a dangling `ArrowBuf`** - the old
   buffer was closed before its replacement was allocated, so an allocator
   throw would have a later smaller batch write its bitmap into freed
   memory and the task listener double-close it. The field is nulled across
   the close-and-replace.
3. **The filter nodes dropped `outputPartitioning`** (the comment claiming a
   pass-through default was wrong - SparkPlan defaults to
   UnknownPartitioning), reintroducing shuffles above cached filtered
   relations. Both forward the child's partitioning now, as `FilterExec`
   does.

The rest: the filter rewrite now gates on a plan-time `vectorTypes` check
(`supportsColumnar` alone is satisfied by Parquet/ORC vectorized scans whose
every batch would fail `canRun` - a permanently falling-back Varka filter is
strictly slower than the `FilterExec` it replaces; the projection rewrites
keep the optimistic task-6 proxy deliberately, their fallback being the same
per-row projection the stock plan runs); `row-path-failure` gained the
bounded cause metric the first round withheld (without one those batches
vanished from the SQL UI entirely); the row node counts `numOutputRows` as
rows are emitted rather than pre-charging the batch's selected count
(overcounts under LIMIT, double-counts on a throw-after-add); a throwing
`onTaskCleanup` hook no longer skips the allocator close; the filter nodes
memoize their EXPLAIN classification like the projection nodes; the filter
evaluator logs the once-per-task fusion account the docs promise; the
selection iterator throws `NoSuchElementException` past exhaustion; two
suite tests moved `markTaskCompleted` into the finally; the cache-strip fix
became a `VarkaFusedTransition` trait with a `columnarSibling` the compiler
forces on every future fused transition node (the serializer handles the
trait, not a node list); the four-copy canRun/catch/refuse skeleton
collapsed into one `serveBatch` template on the evaluator base; the typed
compaction's per-type switch became a single `BaseFixedWidthVector` arm
over Arrow's `copyFromSafe`, so future lane types compact to Arrow instead
of silently degrading through the generic pass; and the README's IN-case
quotes were requoted to the regenerated file's 3.6x / 4.2x.

### 6.2 One more, caught producing the PR artifact

Rendering a real `EXPLAIN FORMATTED` for the PR description crashed:
`ExplainUtils.generateFieldString` does not accept a bare expression, so the
formatted mode threw on every Varka filter node's `Condition` field (verbose
mode and the suites' `predicateLines` assertions never exercised the node's
own renderer). The condition now renders as a plain line, exactly as
`FilterExec` renders its own, pinned by a test that renders both nodes the
way formatted EXPLAIN does.

## 7. Explicitly out of task 21

Boolean output columns (milestone 4 item 5 - this task owes them only the
mask-as-value machinery); `compress(mask)` SIMD compaction (milestone 4 item
11 replaces the scalar loops committed here); selection vectors traveling
between stacked Varka nodes (deferred with the measurement that prices it);
single-kernel filter-plus-projection fusion (a follow-up candidate once both
exist separately); any new lane type or expression; the task-18 debt register
(task 23).
