# Varka Milestone 6 Scope: coverage

*Renumbered from milestone 5 on 4 September 2026, when milestone 4 was
re-scoped to the date family and the emitter and the other lanes became a
milestone of their own (`PLAN_MILESTONE_5.md`). The text below still says
"milestone 4" where it argues about breadth; read "milestones 4 and 5".*

Milestone 3 is *reach* - make the fast path apply to real queries. Milestone 4
is the date family and the emitter under it, and milestone 5 the other lanes -
together, *breadth*, the wider vocabulary of types and expressions. Milestone 6
is **coverage**: pick named queries from benchmarks people actually cite, find
every feature standing between Varka and running them, and publish the number.

The distinction matters because breadth is measured in expressions and coverage
is measured in queries. A milestone can add nine expression families and still
accelerate zero benchmark queries, if the tenth thing every one of them needs is
missing. This file exists to find that tenth thing before milestone 4 is built
rather than after.

Section 1 is the survey milestone 4's section 5 asked for and could not assume.
It changes milestone 4's ordering, and section 2 says how - honestly, because
the project scores its predictions.

Task numbering continues the single sequence, and no task numbers are assigned
here: this milestone's plan takes the next free numbers when it is written
(milestone 4's plan reached 55, and milestone 5 carries 27-30, 39 and 49).

## 1. The survey

**Corpora.** TPC-DS (103 queries) and TPC-H (22 queries) are in this tree, with
their schemas, at `sql/core/src/test/resources/tpcds/`, `.../tpch/`,
`TPCDSSchema.scala` and `TPCHBase.scala` - so every count below is reproducible
from the repository. The New York taxi benchmark is **not** in this tree: its
four queries and the yellow-trip parquet schema are taken from the published
benchmark, and the numbers for it are structural claims about those queries, not
measurements made here. Treat the taxi rows as weaker evidence than the other
two, and confirm the schema against the actual files before building for it -
the public dataset's column types drift between years (`passenger_count` in
particular has shipped as both an integer and a double).

### 1.1 Columns, by declared type

| Type | TPC-DS | TPC-H | Combined | Share |
|---|---|---|---|---|
| `INT` | 189 | 4 | 193 | 39.7% |
| `CHAR(n)` / `VARCHAR(n)` / `STRING` | 145 | 29 | 174 | 35.8% |
| `DECIMAL(p,s)` | 80 | 9 | 89 | 18.3% |
| `BIGINT` | 0 | 15 | 15 | 3.1% |
| `DATE` | 11 | 4 | 15 | 3.1% |
| `DOUBLE` / `FLOAT` | 0 | 0 | 0 | 0% |
| `TIMESTAMP` | 0 | 0 | 0 | 0% |
| **Total** | **425** | **61** | **486** | |

`CHAR(n)` and `VARCHAR(n)` arrive as `StringType` unless
`spark.sql.preserveCharVarcharTypeInfo` is set, so they are one bucket for
Varka's purposes, with padding semantics on top.

Two readings, both uncomfortable:

* **`DateType` - the only type Varka has - is 3.1% of the columns in these two
  benchmarks.** Milestones 1 and 2 were right to start there (dates are int32
  and their semantics are small enough to prove), but the corpus has been saying
  since q1 that dates are where the *predicates* are, not where the data is.
* **There is not one `DOUBLE` or `FLOAT` column in either benchmark.** Milestone
  4's item 3 - float lanes, the largest expression count in that file - is worth
  exactly nothing to TPC-DS and TPC-H. It is worth a great deal to the taxi
  benchmark, whose measures are all doubles, which is precisely why a
  single-corpus survey would have got this wrong.

### 1.2 What the aggregates consume

Column references inside `sum`, `avg`, `min`, `max` and `stddev_samp`, resolved
against the schemas:

| Declared type of aggregated column | References |
|---|---|
| `DECIMAL(p,s)` | 247 |
| `INT` | 172 |
| unresolved (aliases, subquery outputs) | 48 |
| `STRING` | 4 |

The `INT` count is inflated: it includes condition columns inside
`sum(case when d_moy = 1 then ... end)`, and `d_moy` alone accounts for 48 of
them. The uninflated reading is that the money columns - `ss_ext_sales_price`,
`ws_ext_sales_price`, `ss_sales_price`, `ss_net_profit`, `l_extendedprice` -
are decimal, and they are what these benchmarks add up.

### 1.3 What the group-by keys are

| Declared type of GROUP BY key | References |
|---|---|
| `CHAR(n)` | 190 |
| `INT` | 157 |
| `VARCHAR(n)` | 71 |
| `DECIMAL(p,s)` | 18 |
| `STRING` | 14 |
| `BIGINT` | 8 |
| `DATE` | 4 |

Strings are 60% of grouping keys. Grouping is the string-heaviest thing these
benchmarks do, and it is not string *functions* - no `upper`, no `substr`, no
`LIKE` - it is equality and hashing over short, often fixed-width values.

### 1.4 Query structure

| | TPC-DS | TPC-H |
|---|---|---|
| Queries | 103 | 22 |
| Contain an aggregate | 100 | 22 |
| Contain `GROUP BY` | 86 | 16 |
| Contain a window function | 9 | 0 |
| Touch exactly one table (no join at all) | 2 (q9, q41) | 3 (q1, q4, q6) |
| Filter on `d_date` directly | 18 | n/a |
| Filter on `d_year` | 55 | n/a |

Expression frequencies across all 125 queries: `sum(` 361, `cast(` 92, `avg(`
84, `count(` 81, `coalesce(` 41, `substr(` 23, `rank(` 15, `concat(` 9,
`round(` 9, `abs(` 5, `year(` 3, `upper(` 2. Constructs: `CASE WHEN` 127,
`BETWEEN` 165, `IN (` 118, `UNION` 38, `HAVING` 18, `OVER (` 19. Interval
arithmetic appears 29 times and is entirely of the `date '1994-01-01' +
interval '1' year` form, which Catalyst constant-folds - it costs Varka nothing
and needs nothing.

**122 of 125 queries aggregate. 120 of 125 join.** That is the shape of the
problem: Varka rewrites `ProjectExec` today and milestone 3 adds `FilterExec`,
which between them own the *inside* of a scan-side pipeline. In a join-heavy,
aggregate-terminated query, that is a real but bounded slice - and it is
unmeasurable as a query-level number until an aggregate can also stay on the
fast path.

### 1.5 The New York taxi benchmark

The widely cited form is four single-table scan-and-aggregate queries over the
yellow-trip table: count by cab type; average total amount by passenger count;
count by passenger count and pickup year; and count by passenger count, pickup
year and rounded trip distance, ordered by year and count. Structurally:

* **All four are single-table.** No joins at all - the opposite of TPC-DS and
  TPC-H, and the reason this benchmark is the better first target even though it
  is the less famous one.
* **The measures are `DOUBLE`** (`total_amount`, `trip_distance`,
  `fare_amount`), which is where milestone 4's float lanes pay off.
* **The keys are numeric** (`passenger_count`, and `year(pickup_datetime)`),
  which sidesteps string grouping entirely.
* **`year()` over a `TIMESTAMP`**, not a `DATE` - so it needs milestone 4's
  int64 lanes (item 2) *and* its extraction family (item 6) at the same time,
  which neither item currently assumes.
* **`round()`** on a double, which milestone 4 explicitly excluded from item 3
  as scale-dependent.

### 1.6 A third corpus: Spark's own benchmarks

Spark ships 129 benchmark classes with committed results, which is a corpus in
its own right and a more direct one - a number in `sql/core/benchmarks/` is a
number a Spark reader already has a baseline for. Surveying it turned up one
structural obstacle and one clean win, both written up in item 8: nearly every
SQL benchmark is `spark.range(N).selectExpr(..).noop()`, a row source into a row
sink, so Varka engages with none of them as written; and `InExpressionBenchmark`
shows `IN` over dates running at 27.4 M rows/s falling to 8.3 as the list grows.
That second one turned out to belong to milestone 3 rather than here - see
section 2 - which leaves this milestone the string and decimal half of it.

### 1.7 How much of Spark's surface a benchmark actually needs

Spark's `FunctionRegistry` holds 511 registered names behind 464 distinct
expression classes. **Twenty-two of those names cover all 125 TPC-DS and TPC-H
queries**, counted by resolving every `identifier(` in the corpus against the
registry:

| Function | Uses | | Function | Uses |
|---|---|---|---|---|
| `sum` | 361 | | `concat` | 9 |
| `in` | 128 | | `round` | 9 |
| `and` | 107 | | `max` | 8 |
| `cast` | 92 | | `stddev_samp` | 8 |
| `avg` | 84 | | `abs` | 5 |
| `count` | 81 | | `min` | 4 |
| `or` | 46 | | `between` | 3 |
| `coalesce` | 41 | | `year` | 3 |
| `substr` | 23 | | `substring` | 3 |
| `exists` | 17 | | `upper` | 2 |
| `grouping` | 16 | | | |
| `rank` | 15 | | | |

The tail is the point. Once this milestone's items land, six of the twenty-two
are still missing - `substr`/`substring`, `concat` and `upper` (milestone 4's
item 8), `rank` (its item 10), and `round`, which its item 3 excluded as
scale-dependent. Four of the six are string functions, so milestone 4's item 8
is most of what stands between this milestone and the whole corpus surface, and
the census in 1.3 already says that item should be split: the *functions* are
this tail, while the string *keys* are 275 references and are item 3 here. Two
of the twenty-two are out of charter in any milestone: `exists` is subquery
machinery and `grouping` is a grouping-set expansion above the aggregate.
`coalesce` was the seventh until this census found it - it was in no milestone
at all, so it moved into milestone 3's task 20 alongside `In`.

The same count answers the question people ask about Janino. Retiring it would
mean the other ~400 expression classes, the ~17 whole-stage operators Varka does
not own, and all seven of the code generators that live outside whole-stage
codegen entirely - `GenerateUnsafeProjection`, `GenerateOrdering`,
`GeneratePredicate`, `GenerateMutableProjection`, `GenerateSafeProjection`,
`GenerateUnsafeRowJoiner` and `GenerateColumnAccessor`. That last group is not
an expression problem at all: those generators exist to produce and compare
`UnsafeRow`, which Varka does not produce, so covering every expression in the
registry would retire none of them. Whether Varka ever wants to is milestone 3's
task 22, and nothing in this file assumes an answer.

## 2. What the survey changes about milestones 3 and 4

Recorded as corrections, not quietly folded in:

* **Milestone 4's ordering was wrong about float lanes.** Its table puts item 3
  seventh with the note "the largest expression count"; the corpus says that
  count is zero for TPC-DS and TPC-H and material only for taxi. Item 3 should
  be re-argued as "the taxi benchmark's item", which raises it if taxi is the
  first target and lowers it otherwise.
* **Milestone 4 set decimals aside, and decimals are the answer.**
  `PLAN_MILESTONE_4.md` item 12 puts `DecimalType` out of scope: "not a lane
  type ... needs its own design pass rather than an item". The survey says it is
  the single most-aggregated type in both benchmarks. The judgement was right -
  it does need its own design pass - and section 3 below is that pass starting.
* **Milestone 4's item 6 calibration holds.** It predicted extraction is "one
  function wide" because TPC-DS pre-materialises calendar parts; the count is
  `year(` 3, `month(` 0, `quarter(` 0, `dayofweek(` 0. Correct. But taxi uses
  `year()` in two of four queries, over timestamps, so the function survives on
  a different corpus than the one that motivated it.
* **Milestone 4's item 8 was two items, and has been split.** It bundled
  string *functions* (`upper`, `substr`, `LIKE`) with keys, hashing and
  dictionaries. The survey says the functions are rare (`substr(` 23, `upper(`
  2, `LIKE` 8) and the *keys* are everywhere (275 references), so item 8 is now
  the functions and item 9 is the keys. The cheap equality-and-grouping subset
  of the keys is pulled forward into this milestone as item 3, since TPC-H q1
  cannot run without it.
* **Milestone 3's filter priority is confirmed** from a second direction: 165
  `BETWEEN` and 118 `IN (` across the corpus, with 55 TPC-DS queries filtering
  `d_year` and 18 filtering `d_date`.
* **`IN` was missing from every milestone, and has been moved into milestone
  3.** Task 20 took `cast(string AS DATE)` folding and the `BETWEEN` rewrite as
  its two cheap gating shapes; `In` is the third - 118 `IN (` sites, a lowering
  to `Compare(EQ)` joined by `Or` that needs nothing milestone 2 did not build,
  and a committed upstream baseline in `InExpressionBenchmark` showing 27.4
  M rows/s over dates falling to 8.3 at 500 literals. It is now part of task 20,
  which also owes a literal-count cap: task 10 measured 482 against 1616
  M rows/s on a two-chain shape and 168 against 526 at 64 literals, so a
  500-literal node is a shape milestone 2 never sized for. What stays in this
  milestone is the half that needs lane types Varka does not have - `IN` over
  strings (2.6 M rows/s at 200 literals) and over decimals (1.8), which ride
  items 3 and 1.
* **`Coalesce` was in no milestone either, and has also moved into task 20.**
  Forty-one uses, the third most common non-aggregate function in the corpus
  after `cast`, and the cheapest thing named in any of these files: a `blend`
  per argument with the `or` of the arguments' masks as output validity, both of
  which the loop emits today. What it costs is one IR node - the first condition
  that reads an input's *validity* rather than comparing values - and the rule
  for what that means inside `And` and `Or`. `IsNull` and `IsNotNull` (11 and 10
  uses) come through the same door.

## 3. The targets

Five named queries, in the order they should fall. Each is stated with what it
needs that does not exist yet, so the milestone can be closed against queries
rather than against features.

### Target 1. TPC-H q6 - one table, one sum

    select sum(l_extendedprice * l_discount) as revenue
    from lineitem
    where l_shipdate >= date '1994-01-01'
      and l_shipdate < date '1994-01-01' + interval '1' year
      and l_discount between .06 - 0.01 and .06 + 0.01
      and l_quantity < 24

The whole query is a scan, a three-predicate filter and one aggregate. Needs:
milestone 3's filters (the date predicate and two decimal `BETWEEN`s), decimal
lanes (item 1), decimal multiply (item 2), and a `sum` reduction with the
aggregate wiring (items 4 and 5). Nothing else. This is the smallest complete
benchmark query in either corpus and it should be the milestone's first
committed number.

### Target 2. TPC-H q1 - grouped, with string keys

Same table, filtered on one date, grouped by `l_returnflag` and `l_linestatus`
(both single-character strings), with four decimal `sum`s, three `avg`s and a
`count(*)`. Adds: strings as group keys (item 3), grouped aggregation (item 4),
`avg` and `count(*)` (item 5). It is the standard "does your engine do
aggregation" query and it is the one to publish against.

### Target 3 and 4. TPC-DS q9 and q41 - the two single-table queries

q9 is fifteen scalar subqueries over `store_sales`, each an
`ss_quantity BETWEEN a AND b` filter with an `avg` over a decimal column - the
same shape fifteen times, which makes it a clean test of whether class reuse
(milestone 3's task 18) actually holds across a query. q41 is a single-table
scan of `item` under a large disjunction of string equality predicates
(`i_category = 'Women' AND (i_color = 'powder' OR ...)`), with `DISTINCT` on
top. Together they are the string-predicate and repeated-shape cases that
neither TPC-H target exercises.

### Target 5. The taxi four

Single-table, double measures, numeric keys, `year()` over a timestamp and one
`round()`. The value of this target is that it is the only one of the five that
Varka could own *entirely* - filter, projection and aggregate, with no join in
the plan at all - so it is the honest place to publish a whole-query speedup
rather than an operator-level one.

## 4. Scope catalogue

### Item 1. Decimal as unscaled integer lanes, and the Arrow de-interleave

**Spark surface.** `DecimalType(p, s)` columns: 18.3% of benchmark columns and
the type of nearly every aggregated measure. Predicates over them come along for
the ride, and they are slow enough to be their own argument: Spark's
`InExpressionBenchmark` runs `IN` over 200 small decimals at 1.8 M rows/s, the
worst number in that benchmark, against 29.3 for the same list length over
`INT`.

**The representation, and the problem.** Spark stores a decimal with precision
<= 18 as an unscaled `long` (and TPC-DS's `DECIMAL(7,2)` fits an unscaled
`int`), which is exactly a lane type. Arrow does not: `ArrowUtils` maps every
`DecimalType` to `new ArrowType.Decimal(precision, scale, 8 * 16)`, so an
Arrow-backed decimal column is **128 bits per value regardless of precision**,
and a Varka kernel reading it sees pairs of longs where it wants one.

There is no vector gather over `MemorySegment` (milestone 4, item 11), so
"read every other long" has to be built out of what exists:

* **De-interleave by shuffle.** Load two consecutive `LongVector`s and
  `rearrange` them through a fixed even-lane `VectorShuffle` - or the two-vector
  `selectFrom` - to get one full-width vector of low words. Two loads and one
  cross-lane op per output vector. The shuffle is loop-invariant, so it hoists.
* **De-interleave by mask compression.** `compress` each loaded vector with an
  alternating mask and splice the halves. Cheaper where `compress`
  intrinsifies (AVX-512) and worse where it does not - the same portability
  caveat milestone 4's item 11 records.
* **The high words are not free.** For precision <= 18 they are sign extension
  by construction, but "by construction" means "if the writer respected the
  precision". One vector compare of the high word against `low >> 63` per lane
  group validates it, and a mismatch is a fall-back, not a wrong answer.

Which of the three costs least is a measurement, and it is the first thing this
milestone should measure - the entire decimal case rests on the de-interleave
being cheap relative to the arithmetic it enables.

**Vector API it needs**: `LongVector`, `VectorShuffle` with `rearrange` or the
two-vector `selectFrom`, and optionally `compress` - all currently unused, and
`rearrange`/`selectFrom` are milestone 4's item 9 territory pulled forward for a
non-string reason.

### Item 2. Decimal arithmetic semantics

**Spark surface.** `+`, `-`, `*`, `/` over decimals, and the result-type rules
Catalyst applies to them.

**Design input.** Unscaled integer arithmetic is only correct once the scales
agree, and Spark's decimal type coercion changes the result precision and scale
on every operation: `DECIMAL(15,2) * DECIMAL(15,2)` is `DECIMAL(31,4)` before
`spark.sql.decimalOperations.allowPrecisionLoss` trims it. Three consequences:

* An operand rescale is a multiply by a power of ten - cheap, and constant per
  expression node, so it hoists like any other invariant.
* A product of two 18-digit values does not fit 64 bits. TPC-H q6's
  `l_extendedprice * l_discount` at `DECIMAL(10,0)` in this tree's schema fits;
  at the spec's `DECIMAL(15,2)` it does not. The safe rule for a first pass is a
  static precision bound - compile the kernel only when the *result* precision
  stays within the lane, and decline otherwise with a task-16 decline reason.
* Overflow returns null (or throws under ANSI), which is milestone 4's item 4
  machinery reused: detect, then either mask or fall back.

Division is the awkward one: it needs a wider intermediate by definition. It
should be out of the first pass, and named as out, because `avg` over decimals
divides - so `avg` either declines or computes the sum vectorised and the
division scalar, once per group.

### Item 3. String columns as predicates and as group keys

**Spark surface.** 275 group-by key references and the great majority of TPC-DS
filter predicates: `i_category = 'Women'`, `d_year = 2000` on the string side,
`IN` lists of short literals, and the disjunctions of q41. Milestone 3's task 20
takes `In` over the lane types Varka already has; the string half stays here,
and Spark's `InExpressionBenchmark` prices it at 34.8 M rows/s over 5 string
literals falling to 2.6 over 200.

**Design input.** This is deliberately *not* milestone 4's item 8. No `substr`,
no `upper`, no `LIKE` - only equality against a literal, `IN` against a small
literal set, and hashing for grouping. That subset has properties the general
string case does not:

* The literals are known at compile time, so their length is known. An equality
  test against a short literal is a fixed-width compare, not a general memcmp.
* TPC-DS's columns are `CHAR(n)`, so the *values* are fixed-width too, once
  padding semantics are pinned down.
* Arrow's `Utf8` layout is offsets plus bytes; a fixed-width compare over
  variable-width storage still needs the offsets, so the first design question
  is whether to compare in the offset domain (length first, then bytes) or to
  hash unconditionally.
* Dictionary-encoded columns turn both cases into integer work - and
  dictionary-encoded is exactly what a Parquet reader produces for a
  low-cardinality `CHAR(n)`. The gather constraint from item 1 applies again.

**Vector API it needs**: `ByteVector`, `compare`, `anyTrue`/`allTrue`, and the
rotate and bit ops for hashing (`ROL`, `XOR`, `MUL`) - milestone 4's item 9
list, minus everything that needs variable-length control flow.

**A second consumer of the same load.** Milestone 4's item 8 now carries a
design for `cast(string AS DATE)` over the fixed `yyyy-MM-dd` form (from Daniel
Lemire's `sse_date.c`): a saturating-subtract shape mask, a SWAR digit combine
in long lanes, and the existing days-from-civil. Its one open question is how a
10-byte record reaches a long lane from offsets-plus-bytes storage - the same
question this item has to answer for a fixed-width `CHAR(n)` compare. Whatever
load this item settles on should be checked against that consumer before it is
called done, so the string family gets one load path rather than two.

### Item 4. Grouped aggregation

**Spark surface.** `HashAggregateExec` with grouping keys - 86 of 103 TPC-DS
queries and 16 of 22 TPC-H queries.

**Design input.** Milestone 4's item 7 stops deliberately at partial aggregation
*without* grouping keys, because that is the part that is a pure `reduceLanes`.
Grouping is a different machine: hash the key vector, probe a hash table,
scatter the accumulator update. Scatter is the operation the Vector API does not
have over `MemorySegment` (item 1's constraint, third appearance), which points
at the split the design notes already suggest for joins - vectorise the hash and
the key compare, keep the probe and the accumulator update scalar. That is worth
stating as the expected shape before anyone tries to vectorise the whole thing.

A cheaper intermediate exists and should be measured first: low-cardinality
grouping where the key set fits in a small dense array (TPC-H q1 has exactly
*six* groups) collapses to indexed accumulators with no hash table at all.

### Item 5. The aggregate operator wiring

**Spark surface.** `count(*)`, `count(col)`, `avg`, `stddev_samp` (8 uses), and
the `VarkaColumnarRule` change that lets an aggregate stay on the fast path at
all.

**Design input.** The expression-level work in item 4 is useless until the rule
rewrites an aggregate node, which is a bigger plan-shape change than milestone
3's filter: an aggregate has a partial and a final phase, and only the partial
one is columnar. `count(*)` is not a lane operation at all - it is a lane count
plus a validity `trueCount` - and `avg` is a `sum` and a `count` divided once
per group at the end. `stddev_samp` needs sum and sum-of-squares, which is free
once both exist.

### Item 6. `CASE WHEN` inside `sum()`

**Spark surface.** 127 `CASE WHEN`s, and the specific shape milestone 3's survey
named and declined: `sum(case when <cond> then x else 0 end)` in TPC-DS q21 and
q40.

**Design input.** Varka already compiles `CaseWhen` (milestone 2) and will have
predication and masks; what is missing is that the consumer is an aggregate
rather than a projection. Once item 5 lands the wiring, this is aggregate-input
fusion: Varka computes the CASE columnar and hands the aggregate a vector. It is
listed separately because it is the highest-frequency single shape in the corpus
that needs no new lane type at all.

### Item 7. The scan gap - a dependency, not a deliverable

Every number this milestone can produce today comes from a table cached with
`ArrowCachedBatchSerializer`, because that is the only Arrow-backed batch source
Varka sees. TPC-DS, TPC-H and taxi are all Parquet. The Arrow-native Parquet
reader is the project owner's work (milestone 3, item 11), and until it exists
the honest framing of every benchmark result in this milestone is "on an
Arrow-cached copy of the benchmark table", stated in the docs, not buried.

What this milestone owes the dependency: the benchmark work (item 8) should
be written so the same query runs against both sources, so the day the reader
lands the numbers can be regenerated rather than redesigned.

One representation question belongs to the cache itself, recorded here beside
open question 1 (a Varka-side layout chosen at cache-write time): sorted or
near-sorted date and timestamp columns compress four to eight times under
frame-of-reference plus bit-packing (Lemire and Boytsov's SIMD-BP128, cited by
Stumpf and Povyshev, IJDMS 17(6), 2025, section 2.2.5), and the unpacking is
itself a lane loop. A kernel that unpacks such a column straight into its
lanes, rather than through a generic decoder into a `DateDayVector` first,
would spend less memory bandwidth on exactly the rows the parity benchmark
keeps showing as memory-bound. That is another physical form of a date column
for item 11's extractor to choose, and a change to `ArrowCachedBatchSerializer`
rather than to the emitter; it is not this milestone's, but the serializer
should not be shaped in a way that rules it out.

### Item 8. Benchmarks: extend Spark's, rather than only writing our own

**What is missing.** Every committed Varka number is the fork's own -
`VarkaEmitterParityBenchmark` at the buffer level, `VarkaCodegenBenchmark`,
`VarkaColdStartBenchmark` and `VarkaThroughputBenchmark` at the operator level.
None of them is a number a Spark reader already has a baseline for.

**What a survey of Spark's own corpus found.** Spark ships 129 benchmark
classes with committed results, and nearly every SQL one is
`spark.range(N).selectExpr(..).noop()` - a row source into a row sink, so Varka
never engages on any of them as written. `InMemoryColumnarBenchmark` is the only
one that caches, and it measures cache deserialization rather than expressions.

The terminal operator matters as much as the source. `.noop()` accepts
columnar batches in this fork (the milestone-1 columnar-write work; it is
what `VarkaThroughputBenchmark`'s columnar cases terminate in), so an
Arrow-cached variant that keeps `.noop()` measures the fused loop with a
columnar terminal. It is row terminals (`toRdd`) that measure the ~25 ns/row
read-back floor task 19 settled. A Varka variant therefore needs a columnar
source; `.noop()` already serves as the columnar terminal. (Corrected in
task 19: this note originally called `.noop()` a row consumer.)

With that fixed, three of Spark's benchmarks isolate the expression well enough
to be worth extending, and four are traps:

| Benchmark | Spark's committed number | Verdict |
|---|---|---|
| `InExpressionBenchmark` | 27.4 -> 8.3 M rows/s over dates, 5 to 500 literals | **Extend.** For milestone 3's task 20 over dates, and for item 3 over strings; the baseline is already committed |
| `ExtractBenchmark` | baseline `cast to timestamp` 26.7 ns/row; `YEAR` 81.5, `WEEK` 110.8 | **Extend.** The extraction itself is 55-84 ns of real work |
| `AggregateBenchmark`, grouped cases | 14-21 M rows/s across linear, string, decimal and multiple keys | **Extend.** Items 5 and 6 |
| `AggregateBenchmark`, `agg w/o group` | 1352 M rows/s, 0.7 ns/row with whole-stage codegen | Skip. Already at bandwidth; nothing to demonstrate |
| `DateTimeBenchmark`, date arithmetic | `date_add` 72.8 ns/row against `cast to date` 73.7 | Skip. The operation Varka accelerates is free in that harness |
| `FilterPushdownBenchmark` | 2.4 M rows/s, 57 with pushdown | Skip. Measures the Parquet reader, which is item 8's gap |
| `InMemoryColumnarBenchmark` | 10.7 M rows/s | Skip. Measures cache deserialization |

The `DateTimeBenchmark` row is the one worth reading twice: the date arithmetic
Varka has shipped since milestone 1 costs nothing measurable there, because the
harness spends its time in `timestamp_seconds`, the cast and the range
generator. Choosing that benchmark to demonstrate `date_add` would produce a
1.0x and it would be the harness's fault, not the engine's.

**A caveat on the numbers above.** They come from Azure EPYC runners on JDK 17,
not this project's machine on JDK 25, so they are evidence about *shape* - which
cases have headroom - and not a baseline to compare a Varka run against. Each
extended benchmark regenerates its own pair locally, on the five-iteration
two-second-window methodology task 14 fixed.

**Design input.** Extending an upstream benchmark beats inventing a harness: the
cases, the data generation and the result-file format already exist, and a
reader who knows Spark's numbers can read the fork's without learning anything
new. The shape to copy is the fork's own `VarkaThroughputBenchmark` - two
sessions, baseline and Varka, over Arrow-cached tables - applied as a variant of
the three benchmarks above. `TPCDSQueryBenchmark` gets the same treatment at
query level for the five targets in section 3, at a fixed scale factor.

Whatever the form, print the fallback log's decline reasons (task 16) for any
case that does not fuse, so a coverage regression shows up as a decline reason
rather than as a silent 1.0x.

### Item 9. Considered and set aside

* **Joins** (120 of 125 queries): still out, for the reason milestone 4 gave -
  scalar probe over off-heap tables, SIMD only in radix partitioning - and
  because item 4 has to come first regardless.
* **Sorting, `ORDER BY`, `LIMIT`** (128 `ORDER BY`s): not Varka's operator.
  Worth noting only because it caps the whole-query speedup any of these
  targets can show.
* **`ROLLUP` / `CUBE` / `grouping()`** (16 uses): a grouping-set expansion above
  the aggregate; out.
* **Scalar subqueries** (TPC-DS q9's fifteen): Catalyst's problem. The shape
  matters to this milestone only because it makes q9 a class-reuse test.
* **`DISTINCT`** (q41): an aggregate with no aggregate functions; it rides item
  4's machinery if it rides anything.
* **Interval arithmetic** (29 uses): all constant-folded by Catalyst before
  Varka sees a plan. Nothing to do - recorded so it is not re-costed.
* **Window functions** (9 TPC-DS queries, `rank(` 15): milestone 4's item 10.
  Nothing here changes its priority.

### Item 10. The calendar as a lookup table, sized to the era

**Where this came from.** Reading ClickHouse's `toYear` while milestone 4's
calendar family was being built. It is not a milestone 6 subject by topic - it
belongs to the calendar family - but milestone 4's catalogue has become a task
plan, and this is not a task yet, so it lands here per `sql/varka/AGENTS.md`.

**The idea.** ClickHouse's `DATE_LUT_SIZE` is `0x23AB1`: 146097, exactly one
Gregorian era, anchored at 1900 (`src/Common/DateLUTImpl.h`). Timestamps outside
the window go to cctz, but a *day number* outside it does not fall back:
`shiftIntoLUTRange` moves it by whole 400-year cycles into the table and adds
`400 * cycles` to the year, because 400 years is the calendar's *period*. A table
indexed by **day of era** makes that the only path - every `int32` day reduces
into it, and the year is `400 * (era - bias) + table[dayOfEra]`. Varka's prefix
already computes that index: `emitEra` is the first thing it emits. The table
would replace everything after it.

ClickHouse's entry is 16 bytes, but only six of them are calendar: `year` (two
bytes), `month`, `day_of_month`, `day_of_week` and `days_in_month`. The other ten
are the day's start in epoch seconds and two DST bytes, which a `DATE` table does
not need. Packed, the calendar part is 26 bits - year in era 9, month 4, day 5,
weekday 3, days in month 5 - so **the four-field table is the same 571 KB
`int[]` as the year-only one already measured**, and every field after the
first is a shift and a mask. One gather yields every field: the problem task 32
solves with a shared prefix, solved with memory instead, and the version of this
idea worth measuring rather than the year-only one.

ClickHouse also keeps the **inverse**: `years_months_lut`, the table index of
the first day of every (year, month) in the era, 4800 entries. Days-from-civil
is one lookup plus `day - 1`, and at 19 KB the table lives in L1. Varka's
`emitDaysFromCivil` (task 40) is arithmetic, under `add_months`, `make_date` and
`last_day`; with both tables `add_months` is a gather, the month arithmetic, a
second gather, and a clamp against the `days_in_month` bits the first gather
already returned.

**What is already measured** (`VarkaVectorApiProbeBenchmark`, milestone 4's item
9 and `SKILLS.md`). With the column in a `MemorySegment` the way a real kernel
has it, `year(d) = 1998` counted: an era-indexed year table reaches 2070.8 M
rows/s against the arithmetic's 1329.3, a **1.6x**, at 571 KB for the table and
about 10 KB touched by a seven-year query. The gather is reachable because the
table is on-heap and Varka owns it - the API limit that blocks item 9's
dictionary is about gathering *from* off-heap memory, which this is not.

**What has to be measured before it is a task**, and the reason this is a
catalogue entry rather than a plan:

* **The multi-field table**, which is the actual win. One gather yielding four
  fields from a packed 26-bit entry against task 32's shared prefix at 797.7 M
  rows/s - the only comparison that matters, and the only one not yet run.
* **The inverse table under `add_months`.** Two gathers and about fifteen ops
  against the arithmetic round trip, which is the family's most expensive body.
  A 4800-entry table has no cache question to answer; the forward table is the
  one that does.
* **Cache behaviour under a real query**, not a probe. 571 KB of constant data
  competing with the scan is a different thing from 571 KB measured alone, and
  the seven-year figure flatters it: a `DATE` column with a wide range touches
  proportionally more.
* **Nulls, and the emitted shape.** The probe counts; a kernel writes a column
  and a validity word, and the gather's index spill has to live somewhere in the
  slot plan. Whether `GROUP_BUDGET` should weigh a gather at all is open. What a
  gather costs in instructions is not: on this CPU `IntVector.fromArray` with an
  index map compiles to one `vpgatherdd` plus a fixed five-op index check the
  API performs in Java (two compares, `korb`, `kortestb`, branch) and a `kxnorw`
  for the all-ones mask - seven instructions, no call (`SKILLS.md`, "Every
  operator the plans rely on"). A table no longer than the lane count needs none
  of that: `selectFrom` is a single `vpermd`, where `rearrange(ix.toShuffle())`
  spends four more on index wrapping.
* **128-bit.** The measured 1.6x is AVX-512; the scratch probe put the same
  shape at 1853.0 M rows/s at four lanes, which is a smaller margin over a
  smaller arithmetic cost, and a gather that loses at one width and wins at the
  other is a `VarkaEmitOptions` variant, not a default.
* **Whether it survives fusion with more than one thing.** Every number here is
  one calendar node and a compare. The milestone-4 measurements have twice
  reversed when the shape widened.

**Vector API it needs**: `IntVector.fromArray(species, int[], int, int[], int)` -
the index-map gather, currently unused by the emitter, which has no notion of a
constant table at all. That is the real cost of this item: the emitter would gain
a class of operand it does not have.

**Why it might still lose.** The arithmetic is branch-free, needs no memory, and
gets cheaper every time this family is optimised - task 48 took four ops off the
year tail and the leap-flag rewrite took eighteen off `add_months`. A table's
cost is fixed and paid in cache. The honest position is that a 1.6x on one node
in one shape is a reason to measure the four-field case, not a reason to build
anything.

**The rest of ClickHouse's date code, read so it need not be read again**
(September 2026, `src/Functions` and `src/Common/DateLUTImpl.*`). Nothing else
transfers. `GregorianDate.cpp` is a January-based decomposition with plain
divisions and a month loop, behind Varka's arithmetic. `toYearWeek` is MySQL's
eight-mode `WEEK()` verbatim, and Spark has only ISO week; `toISOWeek` finds the
ISO year by the Thursday rule and then counts Mondays, which is dearer than the
`datealgo-rs` form task 37 uses. `addMonths` clamps with
`min(day, daysInMonth)` behind a `day <= 28` branch, which in lanes is the
blend Varka already emits. `dateDiff` counts unit boundaries and `age` corrects
by a lexicographic compare of the remaining components; Spark's
`months_between` and `timestampdiff` define the units differently. No date file
contains explicit SIMD: the per-row table loop is left to the compiler, and the
lookup is a scalar load, so a Vector API gather here is not copying something
ClickHouse does. The one design worth remembering outside the table is
`formatDateTime`, which compiles the format string once into an instruction list
and, when every formatter is fixed width, fills the whole output column with a
template by doubling `memcpy` and lets the instructions patch bytes in place.
That is the shape for a `date_format` kernel, a string-output expression outside
this milestone (section 6).

### Item 11. Physical representation as a compiler decision

**Where this came from.** Three documents read on 5 September 2026: two concept
notes proposing a "time compiler" built from flat expression tables, equality
saturation over date identities, Arrow columns with the Vector API and
Class-File API emission; and the paper they sketch, egg (Willsey et al., POPL
2021), which specialises e-graphs to equality saturation with deferred
*rebuilding* (20.96x over whole runs, 87.85x on congruence maintenance in its
section 3.4) and *e-class analyses* (a semilattice fact per equivalence class,
readable by conditional rewrites, with extraction itself one such analysis).
Like item 10 this is not a milestone 6 subject by topic - it is a compiler
question - but it is not a task yet, so it lands here per `sql/varka/AGENTS.md`.

**What is already built.** Three of the pitched four stages are Varka: the
shape cache amortises compilation to once per shape, the kernels run over Arrow
buffers at both widths, and the emitter is the Class-File API. The compiler
also already carries the parts of a rewrite system as individual arms -
balanced `AND`/`OR` folds, `IN` dedup and sort, literal slots interned by value,
the identity `CAST` and `unix_date` unwraps, `date - INTERVAL n DAY` absorbed
into `SubDays`, `trunc(d, 'WEEK')` rewritten onto `next_day`, and two analyses,
`dayRange` (an interval domain with the hull as its join) and `inputBounds`.
That works because the rules are few and none conflict, so their order never
matters - exactly the condition under which equality saturation adds nothing
over a fixed pass.

**The idea, and why it is not about dates.** Against the date engine the
benefit is small and unmeasured: canonical shapes (fewer kernel classes),
identities Catalyst does not fold (`datediff(date_add(d, k), d) -> k`,
literal-offset chains, idempotent `last_day`), and one home for the ad hoc
arms. Against the engine Varka is meant to become - every Spark type and
expression, several Arrow encodings per type, and Varka's own layouts - the
question changes: *who decides, per expression, which physical form a value
lives in.* That is the e-graph's native problem: an e-class is every way to
obtain one logical value, its e-nodes become physical forms joined by
conversion nodes, and extraction chooses, over the whole projection, where to
convert and where to compute. Varka already solves that by hand in four
places:

| where | logical value | physical alternatives | who chooses today |
|---|---|---|---|
| `isArrowBacked` | a column | `DateDayVector`/`IntVector` accepted; every other encoding refused per batch | a hard-coded match - the refusal is a missing conversion |
| task 59 | a weekday name | `VarCharVector` bytes, or an int32 code column derived per batch | the compiler, by a fixed rule |
| task 32 | a date | int32 days, or the civil fields the shared prefix leaves live | the emitter's sharing rules and `GROUP_BUDGET` |
| item 1 | a `DECIMAL(p <= 18)` | Arrow's 128-bit pairs, or one long lane after a de-interleave | to be measured |

Four mechanisms are fine at four rows; not at the full type system times its
encodings times Varka's layouts. Task 58's measurement is the first cost of
guessing: two calendar outputs over one shift run at 0.58x of one of them
alone, the no-sharing ratio, because the shipped budget decides sharing rather
than a cost.

**A worked example, from the data side.** Warehouses store dates as decimal
integers - `20231027` in an `INT` column - as often as they store `DATE`, and
compute over them with integer arithmetic: `t div 10000` for the year,
`t div 100` for a month key, `t BETWEEN 20230101 AND 20230131` for a range
(Stumpf and Povyshev, IJDMS 17(6), December 2025, survey the pattern across
telecom, IoT and trading schemas; TPC-DS's `date_dim` surrogate keys are its
relative). To Varka today such a column is not a date at all. Under this item
it is the same logical value in a second physical form, `int32 yyyymmdd`
beside `int32 days`, with a conversion node each way - the digit split is
three magic multiplies, the recompose is task 42's `make_date` - and the
cost asymmetry is the point: `year` is *cheaper* in the digit form (one
division) than after conversion (the prefix), while `date_add` and
`datediff` are cheaper after. Which form each expression computes in is the
extraction decision, and this is the first case where it is not obvious by
inspection. The literal-divisor `div` and `%` the digit form needs are noted
under task 63 (`PLAN_MILESTONE_4.md` 2.30).

**A catalogue of the date's physical forms.** Each form is defined by which
operations it makes cheap, which is what an extractor trades on. Four
families, by where the form comes from.

*Forms the data arrives in - encodings to accept or convert away from.*

| form | what it is | cheap in this form | cost to reach `int32 days` |
|---|---|---|---|
| `int32 days` (Arrow `Date32`, Spark's own) | days since 1970-01-01 | `date_add`, `datediff`, compare, `dayofweek` | none: Varka's home form |
| `int64 millis` (Arrow `Date64`) | milliseconds at midnight | nothing extra | one magic division by 86400000, and a narrowing from a 64-bit lane to a 32-bit one |
| `int32 yyyymmdd` | decimal digits | `year`, `month`, month keys, range filters on literal bounds | three magic multiplies, then task 42's `make_date` |
| ISO-8601 text `yyyy-MM-dd` | ten ASCII bytes in a `VarCharVector` | equality and range compare as bytes - lexicographic order is date order | a fixed-width digit parse at known offsets, no per-row `String`; task 59's leaf is the template |
| dictionary / surrogate key | an int index into a small table of dates (TPC-DS's `d_date_sk` into `date_dim`; Arrow dictionary encoding) | grouping and equality - the key is the group id; any field the dictionary carries | a gather, which Varka has no vector form of (milestone 4, item 11) - so this form wants its fields computed once on the dictionary, not per row |
| INT96 (legacy Parquet) | Julian day plus nanoseconds | nothing | subtract 2440588 from the day half |
| Julian Day Number, Rata Die, Excel serial | other epochs | as epoch days | one add: "epoch days" is a family, not one form |

*Forms Varka already computes and keeps live across outputs.*

| form | where it exists today | cheap in this form |
|---|---|---|
| civil fields (era, year of era, March month, day of era) | the shared prefix's slots (task 32) | `year`, `month`, `dayofmonth`, `quarter`, `dayofyear`, `last_day`, `add_months`, `trunc` |
| Thursday-shifted days | `ThursdayOf(d)` (task 37) | `weekofyear`, `yearofweek` |
| `floorMod(d + 3, 7)` | `emitFloorMod7`'s scratch | `dayofweek`, `weekday`, `next_day` |
| a derived int32 code | task 59's weekday leaf | any string-argument function, once parsed |

These are what task 58's measurement is about: which of them to materialise
across outputs, and when.

*Forms that would make one operation cheap - candidates for new nodes.*

| form | cheap in this form | note |
|---|---|---|
| month number and day of month, `(year * 12 + month - 1, dom)` | `add_months` as an add plus a clamp, `months_between`, `trunc(MONTH)`, `last_day`, month-keyed grouping | the form the `add_months` kernel rebuilds every time (112 dense-loop calls); several month-arithmetic outputs would build it once |
| `(year, dayOfYear)` | `dayofyear`, `trunc(YEAR)`, year-relative windows | the January day of year is already a prefix tail (task 34) |
| `(isoYear, isoWeek, isoWeekday)` | the week family in one decomposition | task 37's shift is halfway there |
| day of era plus era | every civil field by one load from item 10's 146097-entry table | a conversion whose cost is a gather rather than arithmetic |
| packed bit fields, `year:16 / month:4 / day:5` | every field a shift and mask; compare order preserved | a better `yyyymmdd`: fields free, no decimal divisions; `date_add` impossible without unpacking |

*Forms defined by the batch rather than the value.*

| form | what it buys | note |
|---|---|---|
| frame of reference, `base + int16 offset` (or `int8`) | sixteen lanes per 256-bit vector instead of eight - a 2x lever on every memory-bound row | item 7's packed cache column seen from the kernel's side; widening back is one op |
| constant or run-length (Arrow run-end encoding) | one computation per run and a broadcast; a `year(d)` over a one-day partition is a scalar and a fill | date-partitioned fact tables deliver exactly these batches; Varka computes the same value 4096 times today |
| sorted run, monotonicity known | neighbour `datediff`, period boundaries and windowed features as scans rather than per-lane calendar work | a batch-level fact - an e-class analysis - rather than an encoding |
| null as a sentinel (`Int.MIN_VALUE`) instead of a validity bitmap | the dense body usable with nulls, at one compare per lane | the dense/masked split is already a two-form choice over the same data, made by a null count rather than by cost |

Not worth a node: `float64` days (Excel's real form), `LocalDate` objects (the
row engine's form, the thing being escaped), decimal-string BCD (a worse
`yyyymmdd`).

The families are arithmetic (the second and third tables), layout (the
fourth) and encoding (the first), and an extractor needs a cost for each
conversion and for each operation in each form. Three would pay before any
engine exists and can be measured on the existing benchmarks: the month-number
form under `add_months`-heavy projections, `int16` frame-of-reference offsets
for the lane-width doubling, and the run-length case for date-partitioned
scans. They are the natural first measurements for this item, ahead of the
corpus audit below.

**Design input - what to decide now, before any engine exists.**

* Make the physical form explicit on IR values rather than implied by the
  node type: a representation attribute, and conversion nodes as first-class
  IR. Then an e-graph later is a change of engine, not of language. The
  `DateDayVector`-only match in `isArrowBacked` is the first line to redesign.
* Keep analyses semilattice-shaped (make, join, modify), as `dayRange` is;
  add nullability and encoding facts in the same shape.
* Keep the IR as immutable records with structural equality - they are
  already e-nodes; hashconsing is literal-slot interning generalised.
* Turn the register into a cost table keyed by operation, representation and
  width, fed from measurement. It is the extractor's input either way.

**What must hold whatever is built.** Costs stay measured, never modelled -
the project's evidence (task 52's guard costs its `fromLong`, task 37's fold
runs at 0.41x of `year`, task 58's sharing row) says op counts mispredict by
2x, so extraction by a static cost would pick worse than today's measured
defaults, and the A/B variants (`FloorMod7`, Neri-Schneider, the Julian map)
stay measured. Every rule proves Spark's semantics, nulls and ANSI included;
conversions add their own (item 1's high-word check). Extraction is
deterministic: a fixed point or a deterministic bound, never a wall-clock
timeout, because the pinned fixtures and shape hashes depend on it.

**What it needs first.** One script over the corpus with two columns: how
many expressions match a dozen candidate identities and how many shapes merge
under canonicalisation; and how many columns arrive in an encoding the
evaluator refuses today. The first sizes the algebraic benefit for dates,
which may well be small; the second says how soon the conversion machinery
pays, and that is the number the decision rests on.

**When.** Milestone 7, once there are several types with several ops each,
unless item 1 lands a second physical representation of a value earlier - at
which point the IR decisions above become due, and the engine question is
asked against real conversions rather than one. Candidate rules and their
semantic conditions are recorded in the analysis of 5 September 2026. The
engine itself - egg ported to Java 25 as a library in its own repository
under `github.com/vecbricks`, with the determinism Varka needs and without
proofs, parsing or the ILP extractor - is planned in `PLAN_EGRAPH_PORT.md`,
independent of this item and buildable before it; Varka takes it as a
pinned dependency when this item needs it.

### Item 12. Fork-only date functions on intermediates the kernels already hold

Recorded on 4 September 2026 while planning task 37, at the owner's request.
A calendar kernel computes, per date, a set of values it then throws away
after one field is read: the era and year, the March-based day of year, the
month numerator, the day of month, the leap flag, the January day of year, the
weekday, the month start and, from task 37, the Thursday of the ISO week. A
function whose whole cost is a few ops over those is nearly free to fuse. Spark
has no such functions today, so every one of these is fork-only SQL surface and
a product decision before it is a task; the ISO ones are the strongest, because
they answer a grouping question users answer with strings today.

| function | definition | on top of what | about |
|---|---|---|---|
| `yearweek(d)` | the ISO week key as one int, `yearofweek * 100 + weekofyear` | task 37's Thursday prefix, shared by both fields | 2 ops |
| `days_in_month(d)` | the month's length | the closed-form month length of the `datealgo-rs` review plus the leap blend | 3-5 ops, against `day(last_day(d))` recomposing the date |
| `is_leap_year(d)` | the leap flag itself | `emitLeapFlag`'s four ops; boolean output, so milestone 5's boolean lanes first | 4 ops |
| `previous_day(d, 'MON')` | the mirror of `next_day` | the same mod 7 with one subtract the other way | as `next_day`, 15 ops |
| `months_diff(d1, d2)` | `(year1 - year2) * 12 + month1 - month2`, an int | two prefixes | 3 ops past them, against Spark's double-valued `months_between` |
| `iso_week_start(d)` | the Monday of the ISO week, `ThursdayOf(d) - 3` | task 37's shift | 1 op; `trunc(d, 'WEEK')` already gives it |

Compositions users write for the same things today fuse only as far as the
date lane goes: `trunc(d, 'WEEK')` for the previous Monday and
`datediff(d, trunc(d, 'QUARTER'))` for the day of quarter are one kernel,
but the `+ 1` after that, the `+ 2440588` that turns `unix_date(d)` into a
Julian day number and the `/ 7 + 1` of a week of month are integer
arithmetic over an output, which the compiler has no arm for - the entry
declines whole and the row engine computes it. That is milestone 5's task
30 (ANSI integer arithmetic), and it is also why task 57 gives
`extract(DAYOFWEEK_ISO)` a node of its own rather than lowering the
`Add(WeekDay, 1)` the analyzer desugars it to. Until task 30 lands, the
functions in the table are the only way to get these as one kernel.

Two cautions. A string or double output leaves the int lane whatever the
arithmetic costs, so `dayname`, `monthname` and `date_format` fields wait for
the formatter (section 6) and `months_between` as Spark defines it for the
double lane. And the sharing rule cuts both ways: `weekofyear(d)` beside
`year(d)` in one projection decomposes twice, once over the Thursday and once
over the day, so a query mixing ISO and calendar fields pays two prefixes;
`yearweek` avoids that by living on the Thursday side alone, which is another
argument for it.

## 5. Ordering

The survey supports an order this time rather than an argument. Item 8 leads
because the benchmarks it extends already carry their baselines upstream, so
everything below can be claimed against a published number rather than against
one this project invented:

| Order | Item | Why here |
|---|---|---|
| 1 | 8, the benchmark work | Nothing below can be claimed without it, and extending Spark's three is cheap |
| 2 | 1, decimal lanes and the de-interleave | Everything about the decimal case rests on this measurement |
| 3 | 2, decimal arithmetic | Completes TPC-H q6 with milestone 3's filters |
| 4 | 5, aggregate wiring | The plan-shape change; `sum` and `count(*)` only |
| 5 | 4, grouped aggregation | Dense-key case first, hash table second |
| 6 | 3, string keys and equality | Unlocks TPC-H q1's grouping, TPC-DS q41, and `IN` over strings |
| 7 | 6, `CASE WHEN` in `sum()` | Highest-frequency shape once the wiring exists |

Targets fall in the order 1 (TPC-H q6), 5 (taxi, if milestone 4's items 2, 3
and 6 have landed), 2 (TPC-H q1), then 3 and 4 (TPC-DS q9 and q41).

Items 10 and 11 are deliberately absent from that table. Item 11 is a
milestone 7 question by its own text, and its only near-term deliverable is
the two-column corpus measurement, which needs no ordering against the spine.
Item 10 is deliberately absent It is gated on one measurement
it does not yet have - the four-field lookup against task 32's shared prefix -
and it belongs to the calendar family rather than to this milestone's decimal
and aggregation spine. If that measurement comes back the way the single-field
one did, it becomes a task in its own right and is scheduled then; if it does
not, the catalogue entry is the record of why the idea was dropped, which is
worth as much.

## 6. Explicitly out of milestone 6

* An equality-saturation engine or a representation-selecting extractor
  (item 11): the IR decisions it depends on may be taken here as they
  arise; the engine is milestone 7's.
* Joins, sorting, grouping sets, and window functions - per item 9.
* Decimal *division*, and any decimal whose result precision exceeds the lane -
  declined with a reason, not computed wrongly.
* Decimal precision above 18: the 128-bit case has no lane at any species, and
  neither benchmark needs it.
* The Arrow-native Parquet reader itself, per item 7.
* String-producing date functions, `date_format` first. When one is planned,
  start from ClickHouse's `formatDateTime` shape recorded under item 10:
  compile the format once, fill the output with a fixed-width template, patch.

## 7. Open questions

1. **Is the de-interleave cheap?** If reading a decimal128 column costs more
   than the arithmetic it feeds, the whole decimal case changes shape - it might
   argue for a Varka-side unscaled-int64 representation at cache-write time
   instead, which is a change to `ArrowCachedBatchSerializer` rather than to the
   emitter. Measure before choosing.
2. **Which benchmark is the headline?** Taxi is the only corpus Varka can own a
   whole query in, and the least cited. TPC-H q1 is the most cited and will
   always be part-scalar. Pick deliberately and say which, because it decides
   whether milestone 4's float lanes are urgent or not.
3. **Do `CHAR(n)` padding semantics survive the Arrow round trip?** The answer
   decides whether string equality is a fixed-width compare or a general one,
   which is most of item 3's cost.
4. **What scale factor?** Large enough that the JIT ladder is amortised (which
   milestone 3's task 18 changes) and small enough to run on the development
   machine. Fix it once, in the harness, and commit it with the numbers.
5. **How much redundancy does the corpus carry, and in what?** Item 11's
   two-column measurement: identities that fold and shapes that merge, and
   columns whose Arrow encoding the evaluator refuses. The second column is
   what decides whether representation selection is a milestone 7 question or
   a milestone 6 one.
