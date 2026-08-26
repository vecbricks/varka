# Varka Milestone 3 Plan: scope and ordering

This is a scope document, not yet a task plan. Milestone 2 (`PLAN_MILESTONE_2.md`)
is where deferrals land; this file is where they live, each with the reason it
was deferred and what has to be true before it starts. It gets rewritten into a
task plan the way milestone 2's was - against the code as it then stands, with
per-task detail files - once milestone 2 is done. Until then, items are ordered
by intent, not committed.

The recurring theme: milestone 2 builds the emitter and proves it on int32 date
chains; milestone 3 spends that emitter - on more types, more plan shapes, and
the operational features (caching, whole-stage questions) that only make sense
once generated code is the main path.

## 1. int64 lanes: timestamps

First, because it is the type-genericity demonstration - one emitter serving two
lane widths where hand-written kernels multiply per type - and because milestone
2 prepared for it deliberately: IR nodes carry a lane type from day one, and the
emitter rejects non-int32 rather than assuming it.

Scope discipline matters more here than anywhere: timestamp *arithmetic* is a
timezone and DST minefield. The safe subset is `TimestampNTZType` (pure int64
microseconds) plus comparisons and diffs on `TimestampType`; anything involving
day or month intervals on zoned timestamps stays out until the semantics are
written down with the same care as milestone 2's section 2.6. `LongVector`
species has half the lanes of int, so the JMH parity gate reruns at both widths.

When zoned operations do enter, the datetime vector-algorithms note (its
group 5) names the technique: pack the IANA tzdata transition rules into flat
`long[]` interval arrays and resolve a vector of timestamps against them with
a SIMD binary search, instead of per-row `ZoneRules` lookups. That is a design
input for the zoned follow-on, not for the NTZ subset this item starts with.
The second-to-day and micros-to-second conversions this item needs are
divisions by invariant constants (86400, 60) - Granlund-Montgomery magic
multiplies (Hacker's Delight chapter 10). Task 11's pre-measurement settled
the enabling question for int lanes: JDK 25's Vector API has no multiply-high
operator, so on long lanes this either waits for one or goes through 128-bit
tricks; the fallback ladder task 11 measured (lanewise `DIV` scalarizes but
still beats allocating paths comfortably) applies here too.

## 2. Cross-task cache of assembled bytes

Deferred from milestone 2 (its section 2.7) for two recorded reasons: it is pure
amortisation with no correctness content, and it collides with the telemetry
that bakes operator and stage into the class bytes. The key is the chain
signature that milestone 2's literal slots made well-defined (IR shape, input
ordinals, lane and output types - never literal values). The cache holds
`byte[]`, not `Class`, preserving the per-task loader's Metaspace guarantee.
The telemetry reconciliation - patching or externalising the debug attributes on
cache hits - is part of the design, not an afterthought; the M2 `assemblyAttempts`
counter pattern is the validation hook.

**Design correction (task 14's post-commit diagnosis, `PLAN_TASK_14.md` 7.5).**
A byte cache as specified above saves only the ~80 us emission - which was
never the cost. The measured per-task cost is HotSpot's tier ladder: a class
defined fresh in each task is a *new* class to the JVM and re-pays
interpreter, C1-with-boxed-vectors and the C2 OSR compile every task - a
fixed 13-50 ms per task growing with the loop method's op count, the
dominant term behind the committed depth-curve erosion and the `dayofweek`
loss. Amortising that requires reusing the *loaded class* across tasks, so
this item's real design question is a bounded class/loader cache and what
remains of the per-task Metaspace guarantee (an LRU bound and unload on
eviction, rather than unload on task end). The byte layer may still exist
under it, but bytes alone do not buy the win this item exists for.

## 3. Filters and selection vectors

The design-note material (selection vectors, zero-copy filtering, forced
compaction below ~15% selectivity) that milestone 2 excluded because fusing into
*filters* changes the plan shape, not just the projection. This is also where
interior comparisons stop being interior: a filter consumes the mask itself.
Depends on nothing in this list but deserves its own plan-shape design pass -
`VarkaColumnarRule` today only ever rewrites a `ProjectExec`.

A survey of the in-repo TPC-DS/TPC-H query resources (157 files, done after
task 11) hardens this item's priority with numbers: 53-78% of all
date-column references sit in WHERE clauses, and the corpus contains exactly
*five* DATE-typed projection expressions total - while `d_date BETWEEN`
predicates (41) and date `+/- INTERVAL` arithmetic (~55 sites, essentially
all in WHERE) are everywhere. Two gating shapes for that reach, both cheap:
`cast(string AS DATE)` folding (85 sites wrap date expressions in it) and
`BETWEEN`'s rewrite to paired comparisons. A second reach lever the survey
exposed: `CASE WHEN <date cmp> THEN x ELSE 0 END` inside `sum(...)`
(TPC-DS q21/q40) is aggregate-*input* fusion, a different wiring than the
projection path. Projection-side date *functions* buy almost nothing in these
benchmarks; the leverage is filters, then aggregate inputs.

## 4. Boolean outputs

Comparisons as projection results. Excluded from milestone 2 to avoid bit-packed
boolean output columns; once filters (item 3) handle masks as first-class
values, materialising one as an output column is the small remainder.

## 5. Calendar field extraction

`year`, `month`, `quarter`, `date_trunc`, `months_between`, `last_day`. All need
the civil-from-days algorithm (Neri-Schneider style: multiplies and shifts, so
SIMD-able), which is real kernel work with a real correctness surface - the
`LocalDate` oracle and a wide-range differential matrix, negative epoch days
included, exactly as milestone 2 did for `dayofweek`.

The datetime vector-algorithms note (group 1) fixes the candidate set by name:
Neri-Schneider (branchless O(1) civil-from-days over the 400-year Gregorian
cycle - the preferred fit for lanes), Cassels' March-shifted year (month/day
from a linear formula like `(5 * d + 2) / 153`), and Howard Hinnant's
`std::chrono` decomposition (the one Velox and DuckDB borrow). All three lean
on division by invariant constants, so the Granlund-Montgomery machinery from
item 1 is a prerequisite worth building once and sharing; whichever ships, the
`LocalDate` differential stays the oracle.

A calibration from the TPC-DS/TPC-H survey (see item 3): TPC-DS
pre-materializes calendar parts as *integer* dimension columns (`d_year`,
`d_moy`, `d_dom`, `d_qoy`, `d_dow`), so extraction functions appear zero
times there - intuition overweights this item for benchmark coverage. The one
projection-resident extraction in the corpus is `year(date)` in TPC-H
q7/q8/q9: small, but the single unsupported function standing between Varka
and a real benchmark projection, so `year` leads whenever this item starts.

## 6. Plain integer arithmetic, ANSI-correct

`datediff(d2, d1) + 1` and friends. Milestone 2 names this a trap and keeps it
out: Spark's `Add` on integers throws on overflow under ANSI mode (the default),
and a SIMD lane cannot throw row-accurately for free. The milestone-3 version
either gates on `ansiEnabled = false` or does vectorised overflow detection
(sign-trick compare, then a scalar re-walk of the offending lane group to raise
the error with the right row). `date_add` remains exempt - it wraps by spec.

## 7. Wider species and heavier operators

Float/Double lanes; aggregation with multi-accumulator unrolling (the acc0-acc3
pattern from the design notes); hash joins on the Hydra split - scalar probing
over off-heap tables, SIMD reserved for radix partitioning and post-probe
projection. Each of these is milestone-sized on its own; they are listed so the
door is visibly open, not because milestone 3 commits to them.

## 8. Buffer alignment, enforced

64-byte alignment of morsel buffers is observed but not enforced today
(`VarkaMorsel.reportAlignment` regularly prints `cacheLineAligned=false`); the
design notes want `Arena.allocate(size, 64)`. Worth doing when a measurement
shows it matters, and only then - the kernels are correct either way.

## 9. The row-path generator question

Milestone 2's section 7 defers a decision: `JavaClassFileEngine` and
`ClassFileAssembler` still assemble a `VarkaProjection` shell whose `apply`
throws, unrouted since the compile-funnel routing was removed. If milestone 2
deleted it, this item is closed; if it kept it, milestone 3 either gives `apply`
a real body (a row-path generator - the whole-stage direction) or removes it.
Tied to item 10.

**Closed: milestone 2 deleted the shell** (after task 10, per `PLAN_TASK_9.md`
section 5.4). Item 10's question stands on its own: a row-path or whole-stage
generator, if ever built, starts from the vector IR and the loop emitter.

## 10. The whole-stage question, answered honestly

The original design documents' headline motivations include the 64 KB method
limit and Janino's compile latency for *whole-stage* code. Neither milestone 1
nor 2 generates the whole-stage class, so neither addresses the limit - a
statement `VISION.md` should carry so it is not read as delivered. Milestone 3
is where the project decides whether Varka ever owns whole-stage generation
(method-size tracking, continuation methods, constant-pool splitting - the
full apparatus) or whether its identity is the columnar fast path beside
whole-stage, with the limit explicitly out of charter. Both are defensible;
leaving it unstated is not.

## 11. Reaching real Parquet scans: the Arrow-native reader/writer

The Varka path today serves only Arrow-backed batches - in practice, tables
cached with `ArrowCachedBatchSerializer`. Vectorized Parquet produces
`OnHeapColumnVector`/`OffHeapColumnVector` and falls back
(`docs/sql-varka.md` records the limitation, but until this item nothing
recorded the reach) - and real workloads live on scans, not cached tables.

The direction is decided: a new vectorized Parquet reader and writer that
returns and accepts *Arrow* batches, planned by the project owner. The data
arrives Arrow-shaped and the Varka path stays Arrow-only - the kernels, the
emitted loops and the morsel contract change not at all, which is the point.
The write side pairs with the columnar-write split already in the tree (a
DSv2 write has no columnar API, so the plan-level route `VarkaProjectExec`
feeds is the landing zone).

The alternative that is *not* the plan, recorded so it is not re-proposed:
adapting the kernel contract to Spark's writable column vectors. Their null
encoding is one byte per row, not bit-packed validity, so that road means a
validity-format dimension in the contract or a byte-to-bit repack per batch
- complexity the Arrow-native reader makes unnecessary. It would return only
if the reader plan were abandoned and a measurement showed the repack beats
the fallback.

## 12. SWAR string-to-date parsing

`CAST(string AS DATE)` / `to_date` on `yyyy-MM-dd` input, from the datetime
vector-algorithms note (group 3): load the digit bytes as one word, subtract
`0x30303030`, collapse to an integer with a multiply-add - three or four
instructions per field, no per-character loop - and validate the separators
with one vector compare whose failing lanes send the whole batch to the
existing parser. That fallback shape is exactly Varka's ghost-fallback
discipline, which is why the idea fits the project at all. Deferred past the
items above because it is the first work outside int lanes: variable-width
Arrow string vectors, byte-lane processing, and Spark's cast-error semantics
under ANSI all need design before any kernel is written.

## 13. Debt register (audit after task 11) - moved to milestone 2

The register written during the task-11 audit lives in `PLAN_MILESTONE_2.md`
(section 8) as of task 17, which swept the items still open: the milestone-1
dispatcher layer was retired, the shared test helpers were made AQE-aware, and
the `GROUP_BUDGET` retuning candidate was measured and closed. Milestone 2 is
where those debts were incurred and where their outcomes belong; this heading
stays so the references to "section 13" in `PLAN_TASK_13.md` and
`PLAN_TASK_14.md` still resolve, and so section 14 keeps its number.

The one item that is not a debt but a decision - whether `VarkaColumnarRule`
should decline fusions whose consumer wants rows, which task 14 measured at
0.6-0.7x at every depth - stays milestone 3's, tied to filters (item 3) and the
Arrow-native writer (item 11).

## 14. Debuggability beyond the task-13 telemetry

From the debuggability review after task 13 shipped. The quick wins - the
IR-indexed `LineNumberTable`, the kernel identity in the fallback warning,
the class dump directory, per-entry decline reasons in verbose `EXPLAIN` -
went to milestone 2 as task 16; what lives here is the heavier remainder,
each with the reason it waits.

* **Fallback-cause metrics in the SQL UI.** `numVarkaBatches` says how many
  batches the kernels served; a counter per fallback cause (non-Arrow input,
  emission failure, kernel failure) on both exec nodes would say *why* a
  production query is off the fast path without log-diving. Waits on
  task 16's decline-reason vocabulary, so the metric names and the explain
  output speak the same language rather than inventing two taxonomies.
* **Loop-method grouping recorded in `VarkaDebugInfo`.** A profiler or
  `-XX:+PrintCompilation` log names `loopMasked2` hot, but nothing says
  which outputs group 2 holds; the grouping decision exists at emit time
  and could ride the debug attribute. Waits because it only pays off on
  wide multi-group kernels, which stay rare until this milestone's ops
  widen real projections past `GROUP_BUDGET`.
* **A field differential mode.** A config that runs the Janino projection
  beside the kernels for the first N batches of a task and compares -
  the differential suite's oracle, available in production. It is the one
  channel that catches wrong results, which the ghost fallback cannot by
  design. Waits for two reasons: double evaluation must be scoped around
  nondeterministic expressions and side effects, and its value peaks
  exactly when this milestone widens the correctness surface (int64 lanes,
  new ops) - it should land with item 1, not before it.
* **JFR events for emission and fallback.** One event per kernel emission
  (class name, IR size, emit micros) and per fallback (cause), so Varka
  appears on the same timeline as the JIT and GC events that already told
  this project's C2-latency story (`SKILLS.md`). Waits to pair with the
  byte cache (item 2), whose hit/miss telemetry wants the same channel;
  designing the event set once, with the cache in view, beats retrofitting.
* **Distinct generated-class names.** Every kernel is
  `VarkaFusedProjection`; the `SourceFile` attribute disambiguates in stack
  traces but not in tools that key on the class name alone. A shape-hash
  suffix would fix that - and the shape hash *is* the cache key of item 2,
  so the naming decision belongs to the cache design, not ahead of it.
