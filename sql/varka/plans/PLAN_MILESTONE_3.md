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

## 3. Filters and selection vectors

The design-note material (selection vectors, zero-copy filtering, forced
compaction below ~15% selectivity) that milestone 2 excluded because fusing into
*filters* changes the plan shape, not just the projection. This is also where
interior comparisons stop being interior: a filter consumes the mask itself.
Depends on nothing in this list but deserves its own plan-shape design pass -
`VarkaColumnarRule` today only ever rewrites a `ProjectExec`.

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

## 11. Non-Arrow columnar sources (vectorized Parquet)

The Varka path today serves only Arrow-backed batches - in practice, tables
cached with `ArrowCachedBatchSerializer`. Vectorized Parquet produces
`OnHeapColumnVector`/`OffHeapColumnVector` and falls back
(`docs/sql-varka.md` records the limitation, but until this item nothing
recorded the reach): serving the off-heap variant would take the kernels to
real scans, which is where most real workloads live. Two design questions
make it an item and not a patch. `OffHeapColumnVector` exposes raw native
addresses, but its null encoding is one *byte* per row, not Arrow's
bit-packed validity - so either the kernel contract grows a validity-format
dimension, or a byte-to-bit repack pass runs per batch (and has to beat the
fallback to be worth it), or the emitted loop reads byte-nulls directly as a
third mask source. And `OnHeapColumnVector` has no stable address at all, so
the on-heap default either stays on the fallback or goes through the same
repack. Measure the repack cost first; the answer decides the shape.

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
