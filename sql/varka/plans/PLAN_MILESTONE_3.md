# Varka Milestone 3 Plan: reach

Milestone 2 closed with task 17, so this file is no longer the scope document it
opened as: it is the task plan that document promised, written against the code
as it now stands. The scope catalogue it grew from is kept whole in section 9,
with every item's number unchanged, because other plans cite those numbers
(`PLAN_TASK_13.md` and `PLAN_TASK_14.md` cite "item 2"; `PLAN_MILESTONE_2.md`
cites items 13 and 14). Where the catalogue and this plan disagree, this plan
wins - the catalogue records what was thought before the measurements, which is
worth keeping precisely because several of those thoughts turned out wrong.

Milestone 1 built kernels. Milestone 2 built the emitter and proved it on int32
date chains. Milestone 3 spends it: **reach** - making the fast path apply to
queries people actually run, and making its wins survive contact with a real
task lifecycle.

## 1. Why

Milestone 2 ends with an emitter that beats Janino roughly 2x on fused
projections over Arrow-cached dates, and a set of measurements that say plainly
where that number does *not* apply. Three of those measurements set this
milestone's agenda:

* **The per-task JIT ladder eats the win** (`PLAN_TASK_14.md` 7.5). Every task
  defines a fresh kernel class, so HotSpot re-runs interpreter, C1-with-boxed-
  vectors and the C2 OSR compile - a fixed 13-50 ms per task that grows with the
  loop method's op count. It is the dominant term behind the committed
  depth-curve erosion (2.2x at depth 1 down to 1.3x at depth 8) and the one
  committed loss before task 17's follow-up. Nothing else in this plan changes
  a committed number as much as removing it.
* **The corpus says the leverage is in filters, not projections** (the
  TPC-DS/TPC-H survey, item 3). 53-78% of all date-column references sit in
  WHERE clauses; the whole corpus holds exactly five DATE-typed projection
  expressions. Varka today rewrites only `ProjectExec`. A fast projection engine
  that never sees a real query's date predicate is a demo, not an engine.
* **Row consumers do not pay** (`PLAN_TASK_14.md` 7.3): 0.6-0.7x at every
  measured chain depth, the per-row read-back dominating. Task 17 left the
  decision - should the rule decline those fusions? - open on purpose, because
  class reuse moves the numbers it would be decided on.

So the spine is: make the machinery cheap per task, then widen what it applies
to, then decide the policy questions with numbers that mean something.

## 2. Design

### 2.1 Cross-task class reuse (task 18)

The catalogue's item 2 specified a cache of assembled *bytes*. Task 14's
diagnosis corrected it: emission costs ~80 us and was never the problem, while
re-defining a class costs the whole tier ladder because a re-defined class is a
*new* class to the JVM. Only reusing the **loaded class** amortises that.

The design that follows from it:

* **Key.** The IR shape signature milestone 2's literal slots made
  well-defined: IR structure, input ordinals, literal *count*, lane and output
  types - never literal values, which already travel as runtime arguments. Two
  queries with the same shape and different constants must hit.
* **Lifetime.** The per-task loader guarantee becomes a bounded loader cache:
  an LRU over loaders, released (and so unloaded) on eviction rather than on
  task end. Metaspace stays bounded by cache size instead of by task lifetime,
  which is a weaker guarantee than milestone 1's and has to be proven the same
  way - the weak-reference collection proof, run against eviction.
* **Telemetry reconciliation, decided rather than deferred.** Today the class
  bakes `Varka_<operator>_Stage<n>.java` into its `SourceFile`, which goes stale
  the moment a class outlives the stage that emitted it. The plan: name the
  class by its shape hash (`VarkaFusedProjection_<hash>`), let `SourceFile`
  carry that same shape identity, and record the per-execution identity
  (operator, stage, plan fragment) in a side table keyed by the hash which the
  diagnostics reader joins. That closes catalogue item 14's "distinct
  generated-class names" in the same stroke, and keeps every task-16 debug
  surface working - the `LineNumberTable` and its key are properties of the
  shape, so they are exactly what *should* be shared.
* **Correctness is the risk, not performance.** A wrong cache hit returns wrong
  results, and the ghost fallback cannot catch it - it catches failures, not
  silently different answers. The key must therefore be derived from the IR by
  construction (a structural hash of the same records the emitter walks), and
  the differential suite must run with the cache warm as well as cold.

Gate: the committed cases that carry the per-task surcharge lose it. Concretely,
`dayofweek` and chain depth 8 on the columnar consumer must show the shape their
buffer-level numbers predict (depth 8 back above 2x), and a 10k-distinct-shape
stress must keep Metaspace bounded.

### 2.2 The profitability decision (task 19)

With 18 landed, re-measure the row-consumer chains and settle the question task
14 raised and task 17 declined to answer on stale numbers: should
`VarkaColumnarRule` decline a fusion whose consumer wants rows - always, or under
a cost rule? The measurement is the same committed matrix; the deliverable is
either a rule with a test that proves it declines, or a recorded decision that
the read-back cost is acceptable because filters (task 21) keep more output
columnar. Whichever way it goes, the docs' honest row must be regenerated with
it.

### 2.3 Reach: the two cheap shapes, then filters (tasks 20-22)

The survey named two gating shapes that cost almost nothing and unlock a large
fraction of real date expressions: `cast(string AS DATE)` folding (85 sites wrap
date expressions in it) and `BETWEEN`'s rewrite into paired comparisons (41
sites). Both are compiler-side only - no new kernel, no plan-shape change - so
they come first and independently (task 20).

Filters (task 21) are the milestone's real reach work and its only plan-shape
change. `VarkaColumnarRule` rewrites `ProjectExec` today; a filter needs the mask
to become a first-class value that leaves the loop - a selection vector - with
the design note's compaction rule (compact below ~15% selectivity, pass the
selection vector otherwise) and a decision about how a selected batch travels to
the next operator. This is where interior comparisons stop being interior, and
where milestone 2's mask algebra earns its second use.

Boolean outputs (task 22) are the small remainder once masks are first-class:
materialising a mask as an output column, with Spark's bit-packed boolean
vector as the only new format question.

### 2.4 Ops: `year` first, then int64 lanes (tasks 23-24)

The survey's calibration is the ordering argument. TPC-DS pre-materialises
calendar parts as integer dimension columns, so extraction functions appear zero
times there; TPC-H q7/q8/q9 use `year(date)` and nothing else. So `year` is the
single function standing between Varka and a real benchmark projection, and it
leads - built on civil-from-days (Neri-Schneider, branchless over the 400-year
cycle) with the `LocalDate` differential as oracle, exactly as `dayofweek` was.
Its division-by-invariant-constant machinery is shared, and task 17's follow-up
gives the technique: narrow the range first, then a low-32-bit magic multiply.

Int64 lanes (task 24) are the type-genericity demonstration milestone 2 prepared
for - IR nodes carry a lane type and the emitter rejects non-int32 rather than
assuming. The safe subset is `TimestampNTZType` plus comparisons and diffs on
`TimestampType`; zoned day/month arithmetic stays out until its semantics are
written down as carefully as milestone 2's section 2.6. `LongVector` halves the
lanes, so every parity gate reruns at both widths.

### 2.5 Debuggability, and the whole-stage answer (task 25)

The catalogue's item 14 remainder, now that task 16 shipped the vocabulary it
waited on: fallback-cause metrics on both exec nodes speaking task 16's decline
taxonomy, and JFR events for emission, cache hit/miss and fallback - which is
why they pair with task 18 rather than standing alone. The field differential
mode lands with task 24, where the correctness surface widens.

And the question the catalogue's item 10 keeps asking: does Varka ever own
whole-stage generation, or is its identity the columnar fast path beside it,
with the 64 KB method limit explicitly out of charter? Both are defensible;
leaving it unstated in `VISION.md` is not. This task answers it in writing.

## 3. Task breakdown

Tasks 18-22 are the committed spine; 23-25 follow if the spine's measurements
hold. Numbering continues the project's single sequence (milestone 1: 1-8,
milestone 2: 9-17).

| # | Task | Deliverables | Validation |
|---|---|---|---|
| 18 | Cross-task class reuse | Shape-signature key derived structurally from the IR; a bounded LRU loader/class cache replacing per-task define; shape-hash class naming with the per-execution identity moved to a side table the diagnostics reader joins; cache hit/miss counters | The differential suites pass with the cache warm as well as cold; committed `dayofweek` and depth-8 columnar cases lose the per-task surcharge; a 10k-distinct-shape stress keeps Metaspace bounded, with eviction proven by weak reference |
| 19 | Fuse profitability, decided | Re-measured row-consumer matrix on top of 18; either a rule that declines row-consumer fusions (with the plan test that proves it) or a recorded decision not to, with the docs' honest row regenerated | Committed numbers before and after; no regression on the columnar cases; the decision is a paragraph with a number in it |
| 20 | The two gating shapes | `cast(string AS DATE)` folding and `BETWEEN` -> paired comparisons in `VarkaExpressionCompiler` | Differential over the survey's shapes; the corpus' wrapped date expressions compile where they previously declined, with decline reasons (task 16) showing the change |
| 21 | Filters and selection vectors | Mask as a first-class value leaving the loop; selection vector with the ~15% compaction rule; `VarkaColumnarRule` rewriting a filter, and the batch contract for a selected batch | Differential on filter-heavy shapes including all-selected and none-selected; committed throughput against Janino on the survey's `d_date BETWEEN` shape |
| 22 | Boolean outputs | Comparisons as projection results, bit-packed boolean output vectors | Differential including null-heavy inputs; the mask algebra's three-valued rules hold at the output boundary |
| 23 | `year`, then the extraction family | Civil-from-days (Neri-Schneider) on int lanes, sharing the range-narrowed magic-division technique; `year` first, the rest as the surface allows | `LocalDate` differential across the full int range, negative epoch days included, at both vector widths; parity against the per-row path |
| 24 | int64 lanes: `TimestampNTZ` | Lane-type genericity through the IR, emitter and evaluator; the safe subset only; the field differential mode of item 14 rides along | Every gate rerun at both lane widths; the semantics written down before the kernels, as 2.6 was |
| 25 | Operational debuggability, and the charter answer | Fallback-cause metrics in the SQL UI speaking task 16's taxonomy; JFR events for emission, cache and fallback; item 10 answered in `VISION.md` | A fallen-back production query is diagnosable from metrics alone; the JFR event set covers emission and cache hit/miss; the whole-stage question has a written answer |

## 4. Files

* **New (catalyst):** the shape signature and its cache (`codegen/varka/`), the
  civil-from-days kernels inside `VarkaLoopEmitter`, the selection-vector
  representation.
* **Changed (catalyst):** `VarkaVectorIR` (lane types beyond int32, boolean
  outputs, mask-as-value), `VarkaExpressionCompiler` (cast folding, `BETWEEN`,
  extraction, filter predicates), `VarkaLoopEmitter` (long lanes, selection
  output), `VarkaDebugInfo` (shape identity rather than per-execution identity).
* **Changed (sql/core):** `VarkaKernelEvaluator` (cache lookup instead of
  per-task emission), `VarkaColumnarRule` (filters; the profitability rule),
  both exec nodes (metrics), a new filter exec node if 21's design calls for one.
* **Docs:** `docs/sql-varka.md` and `README.md` regenerated from one benchmark
  run once 18 and 19 land, since both move committed numbers.

## 5. Verification

The milestone's standing gates, inherited and unchanged:

* Differential against the row engine over every new shape, null patterns
  included, at the preferred width **and** `-XX:MaxVectorSize=16`.
* Parity: an emitted loop stays at or above the hand-written kernel where one
  exists; committed results regenerated in a single run on an idle machine, on
  the five-iteration two-second-window methodology task 14 fixed.
* Metaspace: the weak-reference proof, now against cache eviction rather than
  task completion.
* The ghost fallback still never fails a query, and the cache never returns a
  class for a shape it was not emitted from.

One gap is recorded rather than closed: four-lane coverage is local only, via
`-XX:MaxVectorSize=16`. A real aarch64 runner is the residual half of
`ISSUES.md` finding 4 and remains CI work, dependent on runner availability.

## 6. Risks

* **A wrong cache hit is a wrong answer.** The one failure mode in this
  milestone that the ghost fallback cannot catch. Mitigation: the key is derived
  structurally from the IR the emitter walks, not assembled by hand at the call
  site, and the differential suites run warm.
* **Filters change the plan shape.** Every previous Varka rewrite was
  operator-for-operator; a selection vector crossing an operator boundary is a
  new contract, and its lifetime rules meet the same ownership discipline task
  12 wrote for forwarded vectors.
* **Numbers move under the milestone's own feet.** Class reuse changes every
  committed relative. Docs must be regenerated from one run after task 19, not
  patched case by case - the discipline task 14 established.
* **Scope creep through the catalogue.** Section 9 lists more good ideas than a
  milestone can hold. The spine is 18-22; anything else needs its own argument.

## 7. Open questions, to settle early

1. **Cache scope.** Executor-wide (one cache per JVM) or per-session? Per-JVM
   maximises hits and complicates isolation; the shape key makes cross-session
   sharing safe in principle, and the answer decides where the cache lives.
2. **Selected-batch contract.** Does a selection vector travel with the batch to
   the next operator, or does Varka always compact at its own boundary? The
   first is faster and the second is far smaller - task 21 decides with a
   measurement, not a preference.
3. **The charter question** (item 10), which task 25 answers but which shapes
   how much of this milestone's machinery is worth generalising.

## 8. Explicitly out of milestone 3

* **ANSI-correct plain integer arithmetic** (item 6): still a trap - a SIMD lane
  cannot throw row-accurately for free, and the vectorised-detection design owes
  a measurement before it is worth building.
* **Wider species and heavier operators** (item 7): float lanes, aggregation
  with multi-accumulator unrolling, hash joins. Each is milestone-sized alone.
* **Buffer alignment enforcement** (item 8): correct either way; do it when a
  measurement shows it matters.
* **The Arrow-native Parquet reader and writer** (item 11): the project owner's
  work, and the thing that would make every number in this milestone apply to
  scans rather than cached tables. Coordinate, do not duplicate.
* **SWAR string-to-date parsing** (item 12): the first work outside int lanes;
  wants its own design pass on variable-width Arrow strings and ANSI cast
  errors.

## 9. Scope catalogue

The pre-plan catalogue, item numbers preserved. Items the plan above adopts are
condensed to a pointer; items it defers keep their full design input, which is
what makes them worth carrying forward.

### Item 1. int64 lanes: timestamps

Adopted as task 24 (see 2.4). Design input kept: the safe subset is
`TimestampNTZType` (pure int64 microseconds) plus comparisons and diffs on
`TimestampType`; `LongVector` species has half the lanes of int, so every
parity gate reruns at both widths. When zoned operations do enter, the datetime
vector-algorithms note (group 5) names the technique: pack the IANA tzdata
transition rules into flat `long[]` interval arrays and resolve a vector of
timestamps against them with a SIMD binary search, instead of per-row
`ZoneRules` lookups. Second-to-day and micros-to-second conversions are
divisions by invariant constants (86400, 60); on long lanes the Vector API's
missing multiply-high either waits for one or goes through 128-bit tricks -
task 17's range-narrowing trick is the first thing to try.

### Item 2. Cross-task cache of assembled bytes

Adopted as task 18, **redesigned**: see 2.1. The original specification - a
`byte[]` cache preserving the per-task loader - is superseded by task 14's
diagnosis, which showed the cost is HotSpot's tier ladder rather than emission,
so only reusing the loaded class amortises it. The chain-signature key and the
telemetry-reconciliation requirement carry over unchanged.

### Item 3. Filters and selection vectors

Adopted as tasks 20-21 (see 2.3). The survey behind the priority, kept: 53-78%
of all date-column references sit in WHERE clauses, and the corpus contains
exactly five DATE-typed projection expressions, while `d_date BETWEEN`
predicates (41) and date `+/- INTERVAL` arithmetic (~55 sites, essentially all
in WHERE) are everywhere. Two gating shapes, both cheap: `cast(string AS DATE)`
folding (85 sites) and `BETWEEN`'s rewrite to paired comparisons. A second reach
lever the survey exposed and this milestone does *not* take: `CASE WHEN <date
cmp> THEN x ELSE 0 END` inside `sum(...)` (TPC-DS q21/q40) is aggregate-*input*
fusion, a different wiring than the projection path.

### Item 4. Boolean outputs

Adopted as task 22 (see 2.3).

### Item 5. Calendar field extraction

Adopted as task 23, `year` first (see 2.4). Design input kept: the candidate
algorithms are Neri-Schneider (branchless O(1) civil-from-days over the
400-year Gregorian cycle - the preferred fit for lanes), Cassels' March-shifted
year (month/day from a linear formula like `(5 * d + 2) / 153`), and Howard
Hinnant's `std::chrono` decomposition (the one Velox and DuckDB borrow). All
three lean on division by invariant constants. TPC-DS pre-materialises calendar
parts as integer dimension columns (`d_year`, `d_moy`, `d_dom`, `d_qoy`,
`d_dow`), so extraction appears zero times there - intuition overweights this
item for benchmark coverage.

### Item 6. Plain integer arithmetic, ANSI-correct

Deferred (section 8). `datediff(d2, d1) + 1` and friends: Spark's `Add` on
integers throws on overflow under ANSI mode (the default), and a SIMD lane
cannot throw row-accurately for free. The eventual version either gates on
`ansiEnabled = false` or does vectorised overflow detection (sign-trick compare,
then a scalar re-walk of the offending lane group to raise the error with the
right row). `date_add` remains exempt - it wraps by spec.

### Item 7. Wider species and heavier operators

Deferred (section 8). Float/Double lanes; aggregation with multi-accumulator
unrolling (the acc0-acc3 pattern from the design notes); hash joins on the Hydra
split - scalar probing over off-heap tables, SIMD reserved for radix
partitioning and post-probe projection.

### Item 8. Buffer alignment, enforced

Deferred (section 8). 64-byte alignment of morsel buffers is observed but not
enforced (`VarkaMorsel.reportAlignment` regularly prints
`cacheLineAligned=false`); the design notes want `Arena.allocate(size, 64)`.

### Item 9. The row-path generator question

**Closed in milestone 2:** the `VarkaProjection` shell was deleted after task 10
(`PLAN_TASK_9.md` section 5.4), and task 17 retired the dispatcher layer around
it. A row-path or whole-stage generator, if ever built, starts from the vector
IR and the loop emitter.

### Item 10. The whole-stage question, answered honestly

Adopted as part of task 25 (see 2.5). Neither milestone 1 nor 2 generates the
whole-stage class, so neither addresses the 64 KB method limit that the original
design documents cite as a motivation - a statement `VISION.md` should carry so
it is not read as delivered.

### Item 11. Reaching real Parquet scans: the Arrow-native reader/writer

Out of milestone 3 (section 8), and the most consequential thing on this list.
The Varka path serves only Arrow-backed batches - in practice tables cached with
`ArrowCachedBatchSerializer` - while real workloads live on scans. The direction
is decided and owned by the project owner: a vectorized Parquet reader and
writer that returns and accepts *Arrow* batches, so the kernels, the emitted
loops and the morsel contract change not at all. The alternative that is *not*
the plan, recorded so it is not re-proposed: adapting the kernel contract to
Spark's writable column vectors, whose null encoding is one byte per row rather
than bit-packed validity - that road means a validity-format dimension in the
contract or a byte-to-bit repack per batch.

### Item 12. SWAR string-to-date parsing

Deferred (section 8). From the datetime vector-algorithms note (group 3): load
the digit bytes as one word, subtract `0x30303030`, collapse to an integer with
a multiply-add - three or four instructions per field, no per-character loop -
and validate the separators with one vector compare whose failing lanes send the
whole batch to the existing parser. That fallback shape is exactly Varka's
ghost-fallback discipline, which is why the idea fits the project at all.

### Item 13. Debt register (audit after task 11) - moved to milestone 2

The register lives in `PLAN_MILESTONE_2.md` section 8 as of task 17, which swept
the items still open: the milestone-1 dispatcher layer was retired, the shared
test helpers were made AQE-aware, and the `GROUP_BUDGET` retuning candidate was
measured and closed. This heading stays so references to "section 13" resolve.
The one register entry that was a decision rather than a debt - whether the rule
should decline row-consumer fusions - is task 19 above.

### Item 14. Debuggability beyond the task-13 telemetry

Adopted as task 25 (fallback-cause metrics, JFR events) and folded into tasks 18
and 24 (distinct class names via the shape hash; the field differential mode).
The remaining entry, loop-method grouping recorded in `VarkaDebugInfo`, still
waits for the same reason: it pays off only on wide multi-group kernels, which
stay rare until this milestone's ops widen real projections past `GROUP_BUDGET`.
