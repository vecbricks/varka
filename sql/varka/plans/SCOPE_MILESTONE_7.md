# Varka Milestone 7 Scope: coverage

*Renumbered from milestone 6 on 23 September 2026, when milestone 6 became the
compiler's foundation and got a task plan of its own (`PLAN_MILESTONE_6.md`).
The house rule is that the furthest-out scope catalogue moves forward when a
milestone's own plan becomes a task plan, and this is that move; the file keeps
its item numbers, so every `item <n>` citation resolves unchanged.*

**What this file now is.** The coverage spine it argues for - decimal lanes,
decimal arithmetic, aggregate wiring, grouped aggregation, string keys, and a
first end-to-end TPC-H q6 number - is milestone 7's, and section 5's ordering is
milestone 7's ordering. Nothing here is withdrawn.

***Read "milestone 6" below as "this milestone", except where a sentence is
dated.*** The text was written when this catalogue was milestone 6's and it is
not rewritten, because the project's rule is that a correction is added rather
than the record edited. A dated statement such as "moved here from milestone 5
on 21 September 2026" is a fact about the day it happened, and means the
milestone this file then was.

**Twelve items are not milestone 7's at all.** Items 8, 15 (task 87), 38, 39
(task 148), 40, 41, 42, 43, 44, 45, 46 and 49 were taken by
`PLAN_MILESTONE_6.md`, which names each with the number it has here.

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
*On 15 September 2026 milestone 5 was re-scoped to 64-bit lanes and `TIME`, and
fourteen of its rows moved here as item 15; their numbers stay theirs.*

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
  targets can show - and because someone else measured it: Ishizaki's DAIS 2021
  prototype (`VISION.md` section 14) found a SIMD comb sort slower than scalar
  radix sort, 117 ms against 84 ms on a million pairs, with the exchange
  dominating regardless.
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
| a `TIME` as `(seconds of day: int32, nanoseconds within the second: int32)` | `hour`, `minute`, `second` as int-lane magic divides by 3600 and 60 - the family task 88's A/B measured at 3.7x the double route - over dividends the type bounds; `second_with_fraction`'s two halves already apart | *added 19 September 2026.* A `TIME` has no calendar, so its one expensive step is the long-lane division: three operations natively, fourteen under AVX2 (`PLAN_TASK_88.md` 9.2). The split moves it to the cheap lane once, and the extracts stop being mixed-width kernels, which is `PLAN_TASK_102.md` 2.5's question answered by changing the representation rather than by narrowing at the store or waiting for task 28. The conversion is one `ConstDivide` by 10^9 and a multiply-subtract, and it is a true isomorphism on nanoseconds of day, so no guard rides it. The Arrow cache can hold the split as a second encoding of the column (task 116 proved it carries `TIME`), which is the first row of this catalogue made concrete. Measured by task 152 (`VarkaTimeBenchmark`, `PLAN_TASK_152.md` 6): under the emitter's existing int-lane division the split buys nothing, because that lowering divides in double lanes exactly as often per row as the long lane does; with a bounded single multiply it buys 4x to 13x in cache and the byte ratio out of it, and the split itself costs less than one long extract. So the representation and the bounded magic divide are one decision - the first case in this catalogue where a form is worth nothing without the lowering its bound licenses. `PLAN_TASK_102.md` 8 prices the three routes to the extracts and finds the split's win lives in the *stored* form: a split computed per batch costs about one extract, so it is the cache's encoding - this item's first concrete case - that pays, and the extracts ship first through a narrowing store |

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

**A second source, read 19 September 2026: isomorphic specialization.**
Slesarenko, Filippov and Romanov, "First-class Isomorphic Specialization by
Staged Evaluation", WGP '14 (Gothenburg, 31 August 2014), ACM
978-1-4503-3042-8/14/08, pages 35-46; Shannon Laboratory, Huawei, Moscow.
Transcribed in `sql/varka/papers/slesarenko-2014-isomorphic-specialization.md`
on the owner's decision - the page carries ACM's copyright and permission
notice, under which the README's default is notes only - with the losses the
file's header lists.

What it proposes: in a staged, LMS-style Scala framework, the programmer
*declares* isomorphisms `Iso[From, To]` between an abstract type and a
core-language representation - `DenseVec[T]` as `Array[T]`, `SparseVec[T]` as
`(Array[Int], (Array[T], Int))`, their Figure 5 - and staged evaluation builds
a hash-consed DAG of the program. *Isomorphic specialization* is a rewrite
system over that DAG (Figures 17 and 18) that pushes the `to`/`from` views
along the edges, composing isos through pairs, sums, arrays and functions,
until only core-language nodes remain: abstraction overhead is gone, and the
representation was chosen at staging time, possibly from a data property such
as sparsity. Their evaluation is matrix-vector product at 10^4 x 10^4 over
four dense/sparse pairings and nine sparsity settings: the original versions run
in 167 to 53348 ms and the specialized, LMS-fused ones in 8 to 1134 ms (their
Tables 1 and 2), most of the gain being LMS's loop fusion, with the iso
machinery being what let every representation be tried "for free". Correctness is Conjecture 1, unproven; DAGs are acyclic only;
pairs and sums only.

What it means here. Nearly all of the mechanism is what this project already
is: the IR is immutable records with structural equality, the compiler is the
staging step, CSE and the shared prefix are their collapsing injection, the
emitter is the code generator. What the paper adds is a vocabulary and a
discipline for the design input above - representation as a first-class
object, one declared iso per (logical type, physical form), and
conversion-pushing rewrites that move a representation boundary toward the
leaves or the root so a query computes in one form and converts once - stated
as a formal system with worked rules. Three limits keep it a source and not a
design. It eliminates *abstraction* overhead, of which the kernels have none;
Varka's overhead is arithmetic, so the win is representation *choice*, and the
paper chooses by a hand-written predicate where this item's rule is measured
cost. Its rewriting runs to a fixed point with no notion of optimality, so it
decides how to eliminate views once a form is chosen, not which form to
choose - which is extraction's job, and egg's above. And an iso must be a true
isomorphism; the paper's own footnote concedes "a subset T of tau", and
`yyyymmdd` to days is an injection with invalid encodings, so every such
conversion needs this project's guard-and-decline machinery, which the paper
hands off. The `TIME` split above is the one form in the catalogue that is an
isomorphism outright.

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

Compositions users write for the same things now fuse further than this
section first assumed. `trunc(d, 'WEEK')` for the previous Monday and
`datediff(d, trunc(d, 'QUARTER'))` for the day of quarter were already one
kernel, and since task 63 lowered int arithmetic the `+ 1` after that and the
`+ 2440588` that turns `unix_date(d)` into a Julian day number are in the same
kernel too. Only the first of them loses its ANSI check, though: `datediff` is
bounded by the date contract, while `unix_date(d)` is the date column relabelled
(task 41 gives it no node of its own), and a column carries no bound - so that
one fuses with the check rather than without it. The `/ 7 + 1` of a week of month is still residual, because
integer division has no arm; that part waits, though for `div` rather than for
the arithmetic. This paragraph named milestone 5's task 30 (ANSI integer
arithmetic) as what these were waiting for; task 63 delivered the arithmetic
itself in milestone 4, and it is still why task 57 gives
`extract(DAYOFWEEK_ISO)` a node of its own rather than lowering the
`Add(WeekDay, 1)` the analyzer desugars it to - the dedicated node is cheaper
than the general arm, not a substitute for it. So the functions in the table
are now a convenience and a shared-prefix saving rather than the only way to
get these as one kernel.

Two cautions. A string or double output leaves the int lane whatever the
arithmetic costs, so `dayname`, `monthname` and `date_format` fields wait for
the formatter (section 6) and `months_between` as Spark defines it for the
double lane. And the sharing rule cuts both ways: `weekofyear(d)` beside
`year(d)` in one projection decomposes twice, once over the Thursday and once
over the day, so a query mixing ISO and calendar fields pays two prefixes;
`yearweek` avoids that by living on the Thursday side alone, which is another
argument for it.

### Item 13. The row boundary: 22 ns of every 25 are not the kernel

*Recorded 7 September 2026, at the owner's request, from the reading of
`VarkaThroughputBenchmark`'s committed file that followed task 70. Items 13
and 14 are the two places the survey of that file puts the next order of
magnitude; neither is a lane, an expression or a target, which is why they
are catalogue items here rather than tasks in milestone 5.*

**The number.** In `VarkaThroughputBenchmark-jdk25-results.txt` the row
consumer costs the same whatever the kernel computed: `chain depth 1` 25.1
ns/row, `chain depth 8` 25.0, `date_add` 25.2, `datediff` 26.2, `dayofweek`
26.6, and the Janino baseline for `date_add` 27.0. The columnar consumer
runs the same shapes at 2.4 to 6.9 ns/row, and the parity harness puts the
`date_add` kernel itself near 0.1 ns/row. Task 19 measured this floor,
named it (assemble-then-read, a flat ~25 ns/row), and recorded that
fusing row consumers stays on because the heavy shapes still win against
Janino's own 20 to 31 ns/row; it did not attack the floor, and said the
question reopens when there is a reason to. The reason is that after task
70 the kernels are fast enough that a row consumer sees none of it.

**Where the 22 ns go.** `VarkaColumnarToRowExec` turns its fused batch
into rows the way the stock transition does when codegen is off: a
`ColumnarBatch.rowIterator()` whose row reads each field through an
`ArrowColumnVector` accessor, and an interpreted `UnsafeProjection` that
copies the row out. Per row that is one virtual accessor call per field,
Arrow's bounds checks behind each, and one row write; per batch, nothing is
amortised. The node is deliberately not `CodegenSupport` - its own doc says
"correctness first, codegen support is a follow-up" - so whole-stage
codegen splits at it, and the parent stage, which would otherwise read the
vectors directly into its own variables, starts from rows instead. Spark's
own `ColumnarToRowExec` is `CodegenSupport` and pays neither the iterator
nor the projection; that its baseline still reads 27 ns/row here says the
accessors and the `toRdd` boundary carry a good share of the cost too, which
is what the first step below measures rather than assumes.

**Two levers, in order.**

1. *Join whole-stage codegen.* Make the node `CodegenSupport` the way
   `ColumnarToRowExec` is, producing the fused outputs as the parent's input
   variables straight from the kernel's vectors - the follow-up the node's
   doc already names. For a codegen consumer, an aggregate or a `noop`
   sink, no row is materialised at all; the fused columns are read once,
   by the consumer, at the accessor's cost and nothing else. The mixed
   projection's merge-at-row (task 12) becomes the residual expressions
   evaluated inside the same generated loop, which is where Janino puts
   them anyway. Correctness is the reason it was deferred: the node
   carries the whole fused projection, and a codegen parent that reads a
   vector after the evaluator released the batch reads freed memory, so the
   batch lifetime has to follow the generated loop, not the iterator.
2. *A bulk row writer for the consumers that stay rows.* Exchanges, sorts
   and `toRdd` need `UnsafeRow`s. Every Varka output is fixed width, so
   for a projection of fixed-width columns the row layout is fixed too - a
   null word and eight bytes per field - and a whole batch's rows can be
   written as one contiguous buffer by a column-to-row transpose that reads
   the Arrow buffers directly, never through an accessor, with one reused
   `UnsafeRow` pointed at successive offsets. That runs at memory bandwidth,
   1 to 2 ns/row for three fields, against 22.

**The admission check.** A JMH pair on one 10000-row two-column batch:
the read-back as the node does it today, Spark's codegen read-back from the
same `ArrowColumnVector`s, and the transpose from the raw buffers. It
splits the 22 ns into accessor, projection and iterator shares and says
which lever pays first; registered expectation, transpose under 3 ns/row
and codegen read-back under 8. Then the throughput file's `row consumer`
section is the gate: `chain depth 1, row consumer` from 25.1 ns/row to
under 8, and `residual-heavy projection, row consumer` (50.5) no worse.

**What it changes upstream of here.** Task 19's acceptance rests on the
floor; with the floor at a third of Janino's cost the cheap chains stop
losing at 0.8x and the rule's open cost-model question closes without a
cost model. Item 5's aggregate wiring is the other way to remove the row
boundary for the targets in section 3, and the two are complementary: item
5 for the queries whose consumer Varka owns, this item for every other.

### Item 14. The columnar floor: 2.5 ns/row around a 0.1 ns/row kernel

*Recorded 7 September 2026 with item 13.*

**The number.** Same file, columnar consumer: `chain depth 1` 2.5 ns/row,
`chain depth 2` 2.5, `chain depth 4` 2.5, `chain depth 8` 2.4 - a kernel
eight times the work costs nothing more end to end. `date_add` over the
two-column `varka_dates` fixture reads 6.9, `date_sub` 5.4, the task 56
control 5.0, `datediff` 3.6. Against a kernel the parity harness measures
in tenths of a nanosecond, about 95% of the columnar path's time is spent
around the kernel, and it moves with the fixture's column count more than
with the expression.

**What the code does per batch.** Three fixed costs are visible without a
profile, none of them measured on its own:

* Every scan re-decodes its cached batch. `ArrowCachedBatch` holds the
  IPC bytes on the heap; `deserializeToRoot` reads them back through
  `MessageSerializer` into freshly allocated off-heap buffers and
  `VectorLoader.load`s a new root - a copy of every selected column, per
  batch, per scan, with `spark.sql.execution.arrow.cache.prefetch.enabled`
  off by default so it sits on the critical path. Spark's own
  `InMemoryColumnarBenchmark` measures exactly this at 10.7 M rows/s for
  the stock cache (item 8's table).
* Every output vector is allocated fresh: `allocateVector` calls
  `allocateNew(len)` per output per batch, and the driver zeroes the
  validity again for any output the bitmap pass does not serve.
* The batch is 10000 rows, `spark.sql.inMemoryColumnarStorage.batchSize`'s
  default, chosen for the row cache's memory profile. At that size a
  one-microsecond kernel runs beside allocator calls, iterator hops and
  the decode above, and the kernel's share cannot be large whatever it
  computes.

**The admission check is a profile, not an argument.** An async-profiler
flame graph of the columnar `chain depth 1` row, and the same row at batch
sizes 10000, 65536 and 262144, on an idle machine, committed as a results
file beside the throughput one. It attributes the 2.5 ns among decode,
allocation, the sink and the kernel, and the sweep says how much of it is
per-batch rather than per-row. Registered expectation: decode is the
largest share and the sweep halves the floor by 65536 rows.

**The levers the profile chooses between.** Retain decoded batches - keep
the off-heap Arrow buffers rather than the IPC bytes for a hot cached
relation, which is an accounting change in the cache's memory manager and
turns the per-scan copy into a pointer; pool the output vectors per task,
which task 70's whole-bitmap pass makes safe for served roots because the
pass overwrites the validity it would otherwise have to zero; turn prefetch
on by default if the profile shows decode on the critical path; and give
the Arrow cache its own batch size rather than the row cache's. Item 7's
frame-of-reference form of a date column belongs to the same decision:
what a batch costs to *arrive* is now the number, not what the kernel does
with it once it has.

**What it changes upstream of here.** Every columnar relative in the
throughput file - the 5x to 8x against Janino the README quotes - is
bounded by this floor, not by the kernels; the parity harness's numbers are
the kernels' own and stay as they are. The targets in section 3 run on
Arrow-cached copies (item 7), so this floor is in every number this
milestone will publish.

### Item 15. Moved from milestone 5 on 15 September 2026

Milestone 5 was re-scoped that day to one lane - the long lane - and the types
that share it (`bigint`, `TIME`, day-time intervals - and, until 17 September,
the timestamps, which then moved to item 31), ending in a public message about
`TIME`. Fourteen open rows had no bearing on that and
moved here, on the 4 and 11 September precedents: text and task numbers
unchanged, each design section still where it was in `PLAN_MILESTONE_5.md`
with a note under its heading, so every citation resolves. None is ordered
against this milestone's spine (section 5); each re-enters with its own
argument, and the reason it left is the start of that argument.

| task | what it is | `PLAN_MILESTONE_5.md` | why it left milestone 5 |
| ---: | :--- | :--- | :--- |
| 25 | ILP: the unroll factor as a plan decision | 2.24 | int32 tuning, and its harness has to be re-established before it measures anything |
| 27 | Boolean outputs | 2.1 | a projection output the `TIME` message does not need; the borderline call - a `TIME` benchmark row wanting `SELECT t1 < t2 AS flag` argues it back |
| 49 | Exact civil-from-days in long lanes | 2.6 | date-algorithm precision; uses the long lane but serves dates |
| 64 | Statistics-directed guard selection | 2.28 | int32 guard tuning; also what task 104's `div`/`%` over unbounded `bigint` would need, so it may be the first back |
| 65 | Joffe's `fast32` civil-from-days | 2.7 | a calendar algorithm for the int32 date path |
| 66 | Second-level chrono fragments | 2.8 | a calendar refactor for the int32 date path |
| 72 | Output order for prefix affinity | 2.25 | calendar-prefix tuning |
| 73 | A stopping rule for the guard walk | 2.26 | int32 guard-walk tuning; its admission check is done and recorded there |
| 74 | The validity-word algebra's missing axioms | 2.9 | an optimisation of the existing validity pass |
| 75 | Zero-copy validity for leaf words | 2.10 | an optimisation whose bound moved below its own decline line |
| 80 | String-column compaction keeping the Arrow layout | 2.27 | strings, sized for this milestone's item 3 |
| 82 | The mask-to-long disposal in a checked kernel | 2.12 | a micro-optimisation of the int32 checked path |
| 87 | The epilogue is the one method no budget bounds | 2.18 | kept for a future fix at the owner's request; milestone 5's section 6 says when it would come back |
| 98 | Two filter rows under 1.0x because the consumer counts | 2.33 | the read-back floor - this milestone's item 13, which is where it now belongs |


### Item 16. Borrowed from PolarDB-X's vectorized executor

Recorded on 16 September 2026, from a survey of PolarDB-X SQL
(`github.com/polardb/polardbx-sql`, module `polardbx-executor`) made at the
owner's request while milestone 5's first PRs waited on CI. PolarDB-X solved the
same problem with the opposite toolkit: it targets Java 8, so it has no Vector
API and every kernel is a plain loop shaped for C2's auto-vectorizer; the kernels
are generated at build time by FreeMarker into one class per operator, type pair
and column-or-constant shape (`src/main/codegen/templates/*.ftl`), with a
registry that lets a hand-written kernel displace a generated one; columnar data
is ORC, decoded lazily; nulls are a `boolean[]` per block rather than a bitmap;
and its vectorized tests are YAML-driven type matrices with hand-written expected
values - no differential against the row engine, no fuzzer, no committed
benchmarks. On emission, null handling and testing Varka keeps its own answers.
Four mechanisms are worth this milestone's attention, ranked, each with the file
that shows it. A fifth, the packed MySQL datetime layout whose `EXTRACT(YEAR)` is
a shift and a division (`polardbx-common/.../time/core/TimeStorage.java`,
`vectorized/ExtractVectorizedExpression.java`), is deliberately not on the list:
Varka's sources are Spark's physical types, epoch days and nanoseconds of day,
so the decomposition PolarDB-X avoids is the conversion Varka would have to pay
to reach that layout; it belongs in the design notes of the Arrow datasource, if
that ever chooses its own storage layout, and nowhere else.

1. **Per-node fallback through a derived input.** PolarDB-X falls back per
   expression node: a function with no vectorized kernel becomes a row loop
   writing one intermediate slot of the chunk while its siblings stay vectorized
   (`vectorized/BuiltInFunctionVectorizedExpression.java`; the tree is built by
   `vectorized/build/Rex2VectorizedExpressionVisitor.java`, slots by
   `addOutput()`). Varka falls back per projection entry and per batch. The hook
   already exists: task 59's derived inputs compute a column per batch ahead of
   the kernel. A declined subtree could become a derived input evaluated by
   Spark's row path, and the rest of the expression stays fused, which shrinks
   the ghost fallback's blast radius from the whole entry to one node. The first
   Varka task here is a measurement: what a declined subtree costs today against
   the same entry with the subtree derived.
2. **Overflow that widens instead of falling back.** Decimal subtraction runs
   the batch at 64 bits with a branchless accumulated flag,
   `overflow |= ((l ^ r) & (l ^ result)) < 0`, and only if it fired re-runs the
   batch at 128 bits (`vectorized/math/FastSubDecimalColDecimalColVectorizedExpression.java`,
   `doDecimal64SameScaleSubTo128`; `DecimalBlock` carries a runtime state that
   picks the narrow form when both inputs allow it). Varka accumulates a
   checked-arithmetic mask the same way and, on overflow, declines the batch to
   rows. Once task 29's long lanes exist, an int32 batch that overflows can
   re-run at 64-bit lanes in the same kernel class rather than leave it. Depends
   on 29 and on task 28's lane-width conversion; not before.
3. **Selection narrowing across `AND` and `CASE`.** The right arm is evaluated
   only over the rows the left arm selected, by swapping a temporary selection
   onto the chunk and restoring it (`vectorized/logical/FastAndLongColLongColVectorizedExpression.java`,
   generalised as `VectorizedExpressionUtils.conditionalEval` and used by
   `CaseVectorizedExpression` and `CoalesceVectorizedExpression`). Varka
   evaluates both arms under masks and blends, which task 62 measured at 7.0x for
   `CASE WHEN`; for a cheap arm the blend wins. For an expensive arm behind a
   selective predicate - a calendar decomposition of thirty ops - compress,
   evaluate, expand may win, and the Vector API's `compress` and `expand` make it
   expressible. A build-both-and-measure task, per the house rule, over the
   `CASE` shapes the surface already times.
4. **An adaptive dense-versus-selection choice per batch.** The lazy evaluator
   picks among no selection, a full selection vector, partial selection and
   evaluate-dense-then-intersect from the measured cardinality against a ratio
   (`operator/scan/impl/DefaultLazyEvaluator.java`, `EvaluationStrategy.get`).
   Varka's columnar filter always compacts into a fresh dense batch. For a filter
   that keeps most rows, passing the mask and evaluating dense is cheaper than
   compaction; for a selective one, compaction is right. Measurable on the
   filter shapes in the surface, and it touches only the filter node.

Two more are on the record for later milestones rather than this one:
dictionary pre-evaluation of constants - a constant mapped to a dictionary id
once, the batch compared as ints, a constant absent from the dictionary making
the whole batch false (`vectorized/compare/EQVarcharColCharConstVectorizedExpression.java`)
- which fits Varka the day it reads dictionary-encoded Arrow strings; and lazy
blocks with the surviving selection handed back to still-undecoded projection
columns (`operator/scan/impl/AbstractScanWork.java`, `rebuildProject`), which is
a reader-boundary shape for the Arrow datasource.


*Added 16 September 2026.* Item 24 records Comet's working form of the per-node
fallback above: Spark's own generated code for one expression, compiled into a
batch kernel over the Arrow columns and run inside the columnar pipeline.
Item 28 records Gluten's: a partial project that converts only the needed
input columns to rows, evaluates the unsupported expressions with Spark's
`UnsafeProjection`, and composes the result column back into the batch.

### Item 17. Compared against Apache Druid's vectorized engine

Recorded on 16 September 2026, from a survey of Apache Druid
(`github.com/apache/druid`, `processing/src/main/java/org/apache/druid/math/expr/vector`
and `segment/vector`) made at the owner's request the same day as Item 16.
Druid is a mirror more than a source: it made several of Varka's choices
independently and, in places, chose the option Varka measured and rejected. Its
vector engine decides vectorisability per query and segment, works over primitive
arrays with a `boolean[]` null vector beside them (or `null` when a batch has
none), gives every operator its own final class so call sites stay monomorphic,
and since 2024 carries an optional `jdk.incubator.vector` package
(`math/expr/vector/simd/`, off by default) in which each op is a class with a
`loopBound` main loop and a scalar tail that reuses the non-SIMD processor's
lambda. Its calendar functions (`timestamp_floor`, `timestamp_extract`,
`query/expression/TimestampFloorExprMacro.java`) call Joda per row. Its guarantee
that the two engines agree is `VectorExprResultConsistencyTest`: a corpus of
expression strings run both ways at vector sizes 3, 8, 17 and 67, with a random
and a sequential binding generator, comparing errors as well as values, and a
twelve-line subclass that re-runs the whole corpus with the Vector API flag on.

What Varka already has or has measured past: lengths that are not multiples of
any lane count (the fuzzer's and the emitter matrices' 1, 3, 7, 15, 17, 33, 65,
257); the flag flip, as the gate's `narrow` step; one lowering for the dense loop
and the masked epilogue rather than a shared scalar lambda; bitmaps rather than a
`boolean[]` shadow, which is the representation the validity-word work timed
against; duplicate column reads deduplicated at the kernel's input mapping; and
guards with a per-batch decline where Druid's SIMD rule excludes any op that can
throw mid-lane (`SimdSupportedBinaryOp.DIV`). Task 81's error entries are the
corpus shape Druid's consistency test confirms.

Two things are worth taking.

1. **Bucket short-circuit for time-ordered batches.** Druid never floors
   `__time` per row when grouping by granularity: because segments are
   time-sorted, `query/vector/VectorCursorGranularizer.java` walks bucket
   boundaries and splits the vector into ranges, and skips reading the time
   column when one bucket covers the whole interval. Varka has the ingredient
   without a sortedness assumption: the Arrow cache's per-batch column statistics,
   the ones the in-memory scan already prunes on. When a batch's minimum and
   maximum date fall in one month, `trunc(d, 'MONTH')`, `year(d)`, `month(d)` and
   `add_months(d, n)` over it are one decomposition and a broadcast, not one per
   lane. Time-series data is exactly the shape milestone 5's `TIME` work is about,
   so this is a candidate for the first task after it that has a benchmark row to
   show it on. The first step is the measurement: how often the surface's cached
   batches are single-bucket at day, month and year.
2. **Cost-ordered conjuncts with an early exit.** `query/filter/FilterBundle.java`
   sorts an `AND`'s children by `estimatedComputeCost()` and
   `segment/filter/AndFilter.java` stops as soon as the selection is empty.
   Varka's fused filter computes every conjunct's mask for every lane. Ordering
   conjuncts by a static cost from the value-range analysis, and leaving the rest
   of the batch once the running mask is all-false, is a bounded change in the
   emitter; it is the same question Item 16's selection narrowing asks of `AND`,
   and should be measured with it rather than separately.


### Item 18. Velox's expression evaluator, compared

Recorded on 16 September 2026, from a survey of `velox/expression` and
`velox/vector` at commit `484ef82`, made at the owner's request. Velox's calendar
functions were read earlier for a different question and the record of that is
`sql/varka/skills/calendar-algorithms.md`, "Velox is a semantics reference for
the calendar family, not a performance one"; this item is the evaluator, which
that read did not cover. As with Items 16 and 17, most of what it shows is this
engine's design arrived at independently, and the value is in the three things
it does that this engine does not.

**Arrived at twice.** `Expr::evalArgsDefaultNulls` narrows the row set word by
word as each argument's nulls become known (`rowBits[j] &= flatNulls[j] |
errorNulls[j]`, `Expr.cpp`), so a function body never sees a null row: the
validity-word algebra in lanes. ANSI arithmetic returns a status per row rather
than throwing (`velox/functions/sparksql/Arithmetic.h`, `CheckedAddFunction`),
which is the checked-arithmetic mask; `TRY` ORs an error bitmap into the result's
nulls (`TryExpr.cpp`, `nullOutErrors`), which is the `NULL` overflow mode; the
error bitmap itself allocates exception objects lazily and is one word scan when
empty (`EvalCtx.h`, `EvalErrors`). The flat-no-nulls path that skips every piece
of bookkeeping (`Expr::evalFlatNoNulls`) is the dense body.
`SimpleFunctionAdapter` turns one scalar signature into a family of loops chosen
per batch from facts - no nulls, all ASCII, all inputs flat or constant - and
says in a comment why the duplication is deliberate ("applying this check once
per batch instead of once per row"); this engine makes the same choice per shape
at emission and per batch on the null count. Constant folding before compilation
(`ExprCompiler.cpp`, `expression::optimize`) is the optimizer's job here.

**Where this engine is right to differ.** Velox raises an ANSI error per row;
here `FAIL` declines the batch to the row engine, because Spark's error carries
the offending row's values and its exact message, which the row engine produces
and a kernel would have to reconstruct. And Velox keeps rows that errored active
under `AND` so a later conjunct may short-circuit them to false
(`ConjunctExpr.cpp`, `extraActive`); Spark evaluates conjuncts in order and an
erroring left conjunct fails the query, so that rule does not transfer as
written. Item 19 records Trino's form of it, which does: reorder only terms
that cannot fail.

**Three things worth taking.**

1. **Dictionary peeling with cross-batch memoisation.** `Expr::peelEncodings`
   and `PeeledEncoding::peel` (`PeeledEncoding.cpp`) evaluate the whole expression
   once over a dictionary's values and re-wrap the result with the indices, when
   every non-constant input shares the same indices buffer; `Expr::evalWithMemo`
   then caches the evaluated dictionary across batches, keyed on the identity of
   the dictionary's base buffer, starting only when the same base is seen a second
   time (`baseOfDictionaryRepeats_`) so memory stays bounded, and excluding rows
   that errored. This is the general form of Item 16's sixth point, the
   constant-only trick, and it is the shape of the Arrow dictionary-encoded
   strings this engine declines today. It belongs with Item 3 and supersedes
   16.6: the fused loop runs over the dictionary, the indices are re-wrapped, and
   the second batch over the same dictionary costs nothing.
2. **The conjunct metric.** Where Items 16 and 17 speak of cost ordering, Velox
   orders by clocks per row eliminated - `timeClocks_ / (numIn_ - numOut_)`,
   `velox/common/base/SelectivityInfo.h` - accumulated across batches, with the
   sort run only when a scan finds an inversion (`maybeReorderInputs`). If
   selection narrowing across `AND` is ever built here, this measured metric
   replaces a static cost.
3. **Benchmark sets as differential tests.** `ExpressionBenchmarkBuilder::
   testBenchmarks()` evaluates every expression in a benchmark set and asserts it
   equals the first before anything is timed. This engine's surface driver
   asserts fusion and counts fallen-back batches, but writes both arms to the
   noop sink and never compares their answers; a checksum per arm, computed once
   outside the timed loop and required equal, would catch a fast wrong kernel
   that the differential suites happen not to cover. It is a small infrastructure
   task and should land before task 118's final benchmarks; it is to be filed as
   a milestone 5 row beside task 123 once #220 has merged, since both land on the
   same table lines.

**Noted for later.** The expression fuzzer wraps several input columns in one
shared dictionary and randomly deselects rows so that the peeling thresholds are
crossed (`ExpressionFuzzerVerifier.cpp`); that is the fuzzing the IR fuzzer will
need the day dictionaries arrive. Its three-way check - common path, a naive
per-row evaluator inside Velox, and an external engine through SQL - is what this
engine already has as the reference evaluator plus the row engine. Its shared
subexpression cache is keyed on the identity of the input vectors with a
partial-row top-up (`Expr::evaluateSharedSubexpr`), which an interpreter needs
and a fused loop with compile-time common subtrees does not.


### Item 19. Trino's expression evaluator, compared

Recorded on 16 September 2026, from a survey of `core/trino-main` (`sql/gen`,
`sql/gen/columnar`, `operator/project`, `simd`) and `core/trino-spi` at commit
`15587ef846a`, made at the owner's request. Trino was not in the record before
this. It is the JVM engine nearest to this one in situation - generated bytecode
over columnar blocks, a per-batch choice between null-aware and null-free loops,
a class per expression - and the survey found the same convergence as Items 16
to 18, plus two things worth taking and one correction to Item 18.

**Arrived at twice.** Trino's columnar filter path (`sql/gen/columnar`, added in
June 2023, commit `c4d6fe74448`) generates one class per filter with two entry
points, `filterPositionsRange` over a contiguous range and `filterPositionsList`
over a position list, and inside each an `if (block.mayHaveNull())` chooses a
null-checking loop or a bare one (`CallColumnarFilterGenerator`): the twin
bodies, chosen per batch, over a selection that is a range or an index list
(`SelectedPositions`), as in Item 16. Each filter class is compiled once per
expression and cached (`ColumnarFilterCompiler.filterCache`), and loaded in a
private class loader (`IsolatedClass`) so that one expression's profile does not
pollute another's, which is what this engine's shape-keyed classes do. Its
compaction of a nullable long column for the wire is this engine's
`SelectionVectorOps` line for line - a validity word turned into a
`VectorMask.fromLong`, `compress`, a store, advance by the bit count, a scalar
tail (`LongArrayBlockEncoding.compactLongsWithNullsVectorized`, December 2025);
and in July 2026 (commit `71781e97f6d`) Trino moved its nulls from a byte per
row to a bitmap, arriving at Arrow's validity layout from the other direction.
Its dictionary handling is Velox's (Item 18.1) with a plainer policy: a filter
over a dictionary runs once over the dictionary's values into a boolean mask
indexed by id and reuses the mask while the same dictionary object keeps
arriving (`DictionaryAwareColumnarFilter.selectedDictionaryMask`); a projection
processes the dictionary when it is no larger than the batch, or on the first
batch, or when the last dictionary was used for more positions than it had
values (`DictionaryAwarePageProjection`). The two policies together are enough
for Item 3.

**Where it stops, and this engine goes on.** Trino requires the Vector API
module at startup (`TrinoSystemRequirements.verifyVectorApiEnabled`) and uses it
in Parquet decoding and exchange serialisation, not in the evaluator: its filter
loops are scalar bytecode left to C2's auto-vectoriser, the bet Gandiva makes
with LLVM (`VISION.md`, section 14) and the one the AVX2 investigation showed
does not pay on the JVM. Its date and time functions run row by row over Joda's
`ISOChronology` (`DateTimeFunctions`), so there is no calendar kernel to learn
from. Its `TIME` is picoseconds since midnight in a long with twelve digits of
precision, its `time + interval` wraps modulo a day (`TimeOperators.add`) and its
casts between precisions round rather than truncate, wrapping to midnight
(`TimeOperators`, `round(...) % PICOSECONDS_PER_DAY`); when milestone 5's `TIME`
arithmetic lands, Trino's answers for wrap-around and precision change are one
more reference to hold the row engine's fixtures against, not an authority.

**Two things worth taking, and the correction.**

1. **Reordering guarded by "cannot fail".** `AndFilterEvaluator` runs the
   conjuncts in an order it learns: each term is scored by time taken per
   position it removed, `totalTime / (1 + filteredPositions)`, the order is
   re-sorted once about eight thousand positions have passed and only when a
   scan finds an inversion, and a term that has never run scores infinite so it
   is not promoted (`FilterReorderingProfiler`, November 2024, commit
   `7b36b0bbd8b`, whose TPC-H `AND` benchmark went about five times faster).
   That is Velox's metric (Item 18.2). What Trino adds is the rule that makes it
   legal under ordered semantics: `isReorderingSafe` allows it only when no term
   `mayFail` (`IrExpressions.mayFail` - function metadata declaring
   `neverFails`, division by a constant that is not zero, casts checked case by
   case), and under assertions the function manager wraps every `neverFails`
   implementation to throw if it ever does (`FunctionManager
   .reportIfNeverFailsViolated`). This is the correction to Item 18: reordering
   transfers to Spark for terms that cannot fail, and this engine already has
   the proof of "cannot fail" per node - `VarkaRangeAnalysis` (task 84) decides
   whether a node's guard is armed at all. A fused conjunction over nodes whose
   guards are all `NONE` may be reordered by the measured metric; one with an
   armed guard keeps Spark's order, since a guard that trips declines the batch
   and the row engine must see the rows in the order that decides which error,
   if any, is raised.
2. **A capability gate on `compress`.** `BlockEncodingSimdSupport` reads the
   CPU's flags and enables the `compress` path only where the instruction is
   native - `avx512f` for ints and longs, `avx512vbmi2` for bytes and shorts,
   SVE on ARM - because the JDK gives no way to ask whether `compress` is
   intrinsic or emulated and the emulation is slower than the scalar loop it
   replaced; NEON-only machines, Graviton2 among them, get the scalar path. This
   engine's `SelectionVectorOps` says the same thing in its own comment and has
   no gate. Its "both widths" measurement (`PLAN_TASK_24.md`) was the 128-bit
   lane override on the Zen 5, which has native `VPCOMPRESSD` at 128 bits too,
   so the emulated path has never been measured here, and task 62's runner
   census says most of the GitHub pool - the Zen 3 EPYC 7763 above all - reports
   no AVX-512 flags at all. The measurement is one benchmark run with the JIT
   held to AVX2 (`-XX:UseAVX=2` on the Zen 5 makes `compress` emulated), and the
   gate is a few lines beside the datapath probe task 62 already has. It is to
   be filed as a milestone 5 row beside task 123 and Item 18's checksum row once
   #220 has merged.
   Item 22 adds what HotSpot itself does: on a machine without AVX-512VL, C2
   still matches the vector compress node and lowers it to a permutation
   through a stub table (`x86.ad`, `vcompress_reg_avx`; `c2_MacroAssembler_x86
   .cpp`, `vector_compress_expand_avx2`), so the "emulation" is a permute
   sequence, not a Java loop, and the measurement decides whether it beats the
   scalar tail; and `Long.compress` becomes `PEXT` wherever `UseBMI2Instructions`
   is on, which HotSpot enables on every CPU that advertises BMI2 with no
   generation check, although Arrow's `CpuInfo::HasEfficientBmi2` trusts the
   instruction on Intel only. The gate has two instructions to consider, not one. ClickHouse's rule is the third data point (Item 23): its filter takes the
   compress-store path only on Ice Lake and later, where every width has the
   native instruction, and the plain copy-or-skip path everywhere else.

**Noted for later.** `PageProcessor` sizes projection batches by output bytes,
halving when a page exceeds sixteen megabytes and doubling below four, with a
retry on a too-large page; that is the batch policy variable-width outputs will
need when Item 3 produces strings. `InCodeGenerator` switches `IN` from a
`lookupswitch` to a hash set at eight values, a tipping point it measured
between five and ten for scalar code; this engine's `compileInList` sorts the
literals and compares, and the point where that chain loses to another
membership test in lanes is a measurement not yet made.


### Item 20. DuckDB's expression executor, compared

Recorded on 16 September 2026, from a survey of `src/execution/expression_executor`,
`src/execution/adaptive_filter.cpp`, `src/include/duckdb/common/vector_operations`,
`src/optimizer/rule`, `src/common/types/date.cpp` and
`extension/core_functions/scalar/date/date_part.cpp` at commit `d397c964cc`, made
at the owner's request. The record had one line on DuckDB before this, in
`PLAN_MILESTONE_3.md`: that its calendar decomposition is Hinnant's. DuckDB is
an interpreter over precompiled templates, not a compiler, and it uses no
explicit SIMD anywhere in its source; what it has to teach is in its optimizer
and in one policy.

**Arrived at twice.** Validity lives in 64-bit entries and a missing mask means
no nulls (`ValidityMask::CannotHaveNull` is a null pointer); every kernel loop
walks the entries and dispatches per entry - all valid, a bare loop; none
valid, skip; mixed, a per-bit check (`scalar_executor.hpp`). That is the
validity-word algebra with the dense and masked bodies chosen per sixty-four
rows rather than per batch. Comparisons are evaluated as selections into a true
and a false vector, specialised at compile time on whether nulls are possible,
which of the two sinks is wanted and which operand is constant, and a `!=` is
run as `=` with the sinks swapped (`BinarySelectAdapter`,
`ComparisonSelectComplement`). A filter's output is a slice - a dictionary over
the input, no copy (`PhysicalFilter`, `DataChunk::Slice`) - and every kernel
accepts flat, constant and dictionary input through one view
(`UnifiedVectorFormat`), which is Item 16's selection narrowing and Item 11's
several-representations stance together. `CASE` evaluates each `WHEN` only on
the rows still unresolved and each `THEN` only on the rows that matched
(`execute_case.cpp`). Its `IN` becomes a chain of `=` below six constants and a
hash join at or above (`IN_CLAUSE_REWRITE_THRESHOLD`), the scalar tipping point
Item 19 notes for Trino at eight.

**A third policy for conjunct order.** Velox (Item 18) and Trino (Item 19)
measure a metric per term. DuckDB's `AdaptiveFilter` measures nothing per term:
after a five-batch warm-up it runs twenty batches under the current order,
swaps one random adjacent pair - each pair with its own likelihood, starting at
one hundred - observes ten batches, keeps the swap if the mean time fell and
otherwise reverts it and halves that pair's likelihood, never below one. The
initial order is a static cost table (`ExpressionHeuristics`: arithmetic five,
`year` twenty, `LIKE` two hundred, an unknown function a thousand). A trial of
the whole order measures the order that actually ran, so it is not misled by
correlated terms the way a per-term metric taken under one order can be; it
pays for that with permanent exploration. And it has Trino's guard in the same
words: a term that `CanThrow()` disables permutation altogether. If selection
narrowing is ever built here, the choice between a metric and a trial is a
measurement on the surface benchmark, not an argument.

**Two things worth taking.**

1. **The monotone preimage.** `MonotonePreimageRule` (July 2026, commit
   `5e892bb9c0`) rewrites `f(col) OP c` into a range on `col` whenever `f` is a
   deterministic unary function declared monotone in its argument
   (`arg_properties.hpp`), by bisecting the column type's finite domain and
   probing `f` - no per-function inverse, and it bails if a probe errors or
   returns `NULL`. So `year(d) = 2021` becomes a `BETWEEN` on `d`: two compares
   in place of a decomposition, and a range a scan can prune on. Spark's
   optimizer has no such rule (`sql/catalyst/.../optimizer` names neither
   `Year` nor `DatePart` nor `TruncDate`), and this engine serves `year(d) =
   2021` today with the full per-lane year decomposition (`PLAN_TASK_37.md`).
   The rewrite is exact under Spark's semantics too - `year` of a non-null date
   never fails and the range compare is null-preserving in the same way - and
   the compiler can bisect `VarkaChrono` itself at compile time, for `year`,
   `date_trunc`, `unix_date` and the casts, without knowing an inverse. It is a
   candidate row: it belongs with the algebraic rewrites `PLAN_EGRAPH_PORT.md`
   plans, or as one Catalyst rule in the fork, and the surface benchmark's
   `year(d) = 2021` row is its measurement.
   Item 21 records DataFusion's form of the same rewrite, declared per function
   rather than found by bisection, and the ClickHouse origin both cite.
2. **Statistics flip the kernel, and the parts propagate.** `PropagateNumericStats`
   computes the result's bounds from the children's statistics and, when no
   overflow is possible, replaces the function's callback with the unchecked
   operator (`SetFunctionCallback(GetScalarIntegerFunction<BASEOP>)`,
   `arithmetic.cpp`); the date parts propagate too - `year` at the minimum and
   maximum date is exact (`PropagateDatePartStatistics`), and `month` is exact
   only when both endpoints fall in one year, else the fixed `[1, 12]`
   (`PropagatePartWithinParentStatistics`). Item 15 already holds this
   engine's version - the batch's own minimum and maximum as the source of a
   bound the compiler cannot prove, choosing the guard-free body - so this is
   not new; what DuckDB adds to Item 15 is the propagation rule through the
   calendar parts, which `VarkaRangeAnalysis` (task 84) can apply as written,
   and the reminder that the flip is of the body, not of a check inside it.

**Noted for later.** `CASE` with an expensive arm: blending every arm across
all lanes is right while the arms are cheap, and DuckDB's narrowing to the
unresolved rows is right when an arm carries a decomposition and few rows reach
it; where the crossover is in lanes is a measurement. The per-entry dispatch
inside a batch - a bare body for an all-valid word, a skip for an all-null one
- is a finer grain than this engine's per-batch choice, one branch per
sixty-four rows; whether it pays on a mostly-null batch is likewise a
measurement, not a design. Its `year` extraction normalises into one
four-hundred-year cycle and interpolates from a cumulative-days table
(`Date::ExtractYearOffset`), a table lookup where this engine's `VarkaChrono`
is arithmetic; `calendar-algorithms.md` already settled that question for lanes.


### Item 21. DataFusion's expression layer, compared

Recorded on 16 September 2026, from a survey of `datafusion/expr-common`
(`interval_arithmetic.rs`), `datafusion/physical-expr` (`intervals/cp_solver.rs`,
`analysis.rs`, `expressions/binary.rs`, `expressions/case.rs`,
`physical_expr.rs`), `datafusion/optimizer/src/simplify_expressions` and
`datafusion/spark` at commit `606ae0f69`, made at the owner's request.
DataFusion was not in the record. It is an interpreter over Arrow arrays in
Rust, with no code generation and no explicit SIMD of its own (its kernels are
arrow-rs's), so what it has to teach is, as with DuckDB, in the analysis it
does before a kernel runs. It is also where Comet's Spark-compatible functions
now live (`datafusion/spark`), which makes it the nearest published attempt at
this engine's contract; Comet itself is not checked out and remains unread.

**Arrived at twice.** A value is an array or a scalar repeated
(`ColumnarValue`), and every kernel takes either on either side, which is the
literal slot. `AND` and `OR` short-circuit per batch on the left side's
bit-count - all false or all true returns without evaluating the right - and
when the side that cannot decide the operator is rare, no more than one fifth
of the rows, the right side is evaluated on the filtered batch and scattered
back (`check_short_circuit`, `pre_selection_scatter`, April 2025, commit
`4818966fa`): selection narrowing behind a measured threshold, Item 16 again.
`evaluate_selection` is the general form - filter the batch, evaluate, scatter,
and never evaluate a fallible expression on an empty batch - and `CASE` is
compiled into one of five shapes at construction (`EvalMethod`), one of which
evaluates a `THEN` over the whole batch only when it is a bare column, since
only that is known cheap and infallible (`is_cheap_and_infallible`). The
optimizer's `reorder_predicates` (June 2026) is Trino's and DuckDB's initial
order reduced to two classes, cheap and expensive, with `LIKE` and regular
expressions the only expensive operators; its `simplify_predicates` folds
`x > 5 AND x > 6`; its `unwrap_cast` says in its own doc that it is Spark's
`UnwrapCastInBinaryComparison`. The Spark crate picks a checked or a wrapping
kernel per call from `enable_ansi_mode` (`math/abs.rs`), and its `date_add`
wraps, as Spark's does.

**Three things worth taking.**

1. **Backward interval propagation.** `interval_arithmetic.rs` is a complete
   interval lattice over Arrow types - endpoints that overflow become unbounded,
   comparisons yield certainly-true, certainly-false or unknown, `and` and `or`
   compose those - and `cp_solver.rs` (March 2023, commit `3c1e4c0fd`) runs it
   in both directions over an expression graph: bottom-up to bound a node from
   its children, which is what `VarkaRangeAnalysis` (task 84) does, and then
   top-down, from a known interval on a node to tighter intervals on its
   operands (`propagate_arithmetic`: for `x + y` in `[pL, pU]`, `x` narrows to
   `[pL, pU] - [yL, yU]`, intersected with its own `[xL, xU]`, and `y` likewise;
   `propagate_comparison` for the six operators), until a fixed point or an
   empty interval, which proves the expression unsatisfiable. This engine's
   analysis has only the first pass. The second is what lets a conjunct bound a
   sibling: under `d >= DATE'2020-01-01' AND d < DATE'2022-01-01' AND
   year(date_add(d, i)) = 2021`, the first two conjuncts, asserted true, narrow
   `d`, and the guard on the third can be decided with that narrower `d` rather
   than the column contract. It is a second traversal over the same `Range`
   lattice with the same saturating arithmetic, and `VarkaValueRange` already
   has the intersect it needs. It should be a task when a guard is found that
   only a sibling can retire.
2. **The preimage, declared.** DataFusion has the rewrite Item 20 takes from
   DuckDB, from January 2026 (commit `c2f3d6541`, `ScalarUDFImpl::preimage`,
   `udf_preimage.rs`), and it cites ClickHouse's VLDB 2024 paper as the origin -
   so the idea is in three engines, and ClickHouse's `getMonotonicityForRange`
   is the part of its evaluator the calendar read did not cover (it is covered
   in Item 23). The difference
   from DuckDB is the mechanism: a function *declares* its preimage - `date_part`
   returns `[year-01-01, (year+1)-01-01)` for `YEAR` and nothing else, `floor`
   returns `[c, c+1)` - as a half-open interval so `=` becomes `>= lo AND <
   hi` with no upper-bound adjustment, and the simplifier applies it to the six
   comparisons, to `IS [NOT] DISTINCT FROM` with the `NULL` case written out,
   and to `IN` lists of at most three literals as a disjunction of ranges
   (`THRESHOLD_INLINE_INLIST`). For this engine the two mechanisms compose:
   declare the preimage where `VarkaChrono` has a closed form (`year`,
   `date_trunc`, the month of a year), and bisect where it does not. The
   `IS NOT DISTINCT FROM` and `IN` cases are the ones a first version forgets.
3. **Selectivity from the same lattice.** `analysis.rs` runs the solver with
   the predicate asserted true over the columns' initial bounds and reads the
   selectivity off the ratio of the final to the initial interval widths
   (`cardinality_ratio`), which `FilterExec` uses for its statistics. It is
   free once the second pass exists, and it is a static estimate to check
   against, or to seed, whatever measured conjunct policy Items 18 to 20 end
   in.

**Noted for later.** The `PhysicalExpr` trait carries `evaluate_statistics` and
`propagate_statistics` over distributions - uniform, exponential, Gaussian,
Bernoulli, generic - in both directions like the intervals; more machinery than
a range guard needs, recorded so it is not rediscovered. Its `IN` builds a
static hash filter when the list is all constants and falls back to a chain of
`=` otherwise, the same two shapes as Trino's and DuckDB's with the threshold
left to the set size. The Spark crate's functions are a second reference
implementation of Spark's calendar semantics, in Rust over Arrow days, that the
differential could be run against if a case ever needs a third opinion
(`spark/src/function/datetime`: `add_months`, `date_add`, `date_diff`,
`date_trunc`, `last_day`, `next_day`, `trunc`, `weekday` and others).


### Item 22. Arrow's compute kernels, compared

Recorded on 16 September 2026, from a survey of `arrow/cpp/src/arrow/compute`
(`kernel.h`, `exec.cc`, `function.cc`, `expression.cc`,
`kernels/codegen_internal.h`, `kernels/vector_selection_filter_internal.cc`,
`kernels/scalar_if_else.cc`, `kernels/scalar_boolean.cc`,
`kernels/scalar_arithmetic.cc`, `kernels/scalar_temporal_unary.cc`),
`arrow/util/bit_block_counter.h`, `arrow/visit_data_inline.h` and
`arrow/util/cpu_info.h` at commit `b274238283`, made at the owner's request.
The earlier Arrow read was Gandiva only (`VISION.md`, section 14); this is the
library of precompiled kernels beside it, over the same buffers this engine
reads, so its choices are about the same bits.

**Arrived at twice, on the same buffers.** A kernel declares how its validity
is produced (`NullHandling`): `INTERSECTION`, the bitwise AND of the arguments'
bitmaps computed by the executor before the kernel runs, is the default and is
the validity-word algebra outside the loop; the executor's null propagator does
nothing when no argument has nulls and reuses the one bitmap without copying
when exactly one does and its offset is a multiple of eight (`exec.cc`,
"Null propagation implementation"). Kernels then run over the values through a
block visitor (`VisitBitBlocks`): an `OptionalBitBlockCounter` walks the
validity bitmap in blocks of 64 or 256 bits - or pretends every block is all
set when there is no bitmap, which is one code path for both cases - and the
visitor takes a bare loop for an all-set block, skips a none-set block and
checks bits only in a mixed one. That is DuckDB's entry dispatch (Item 20)
with a wider block and the missing-bitmap case folded in. `BinaryBitBlockCounter`
popcounts the AND, AND-NOT, OR or OR-NOT of two bitmaps a word at a time
without materialising the result, which is how the filter kernel counts "true
and not null" (`DropNullCounter`). The filter kernel itself is a block
dispatch: a filter block all set over data all valid copies the segment; all
set over some nulls copies values and validity; none set under `DROP` skips
the block "for this exceedingly common case in low-selectivity filters"; the
mixed block walks bits. `AND` and `OR` are Kleene three-valued, word by word
(`ComputeKleene`), and the file says in an assertion that the three-valued
path "is unnecessarily expensive for the non-null case", so it is taken only
when a side has nulls. Arithmetic comes in two functions, `add` and
`add_checked`, the checked one raising `Status::Invalid("overflow")` from a
per-element `AddWithOverflow`; the caller picks the function, which is this
engine's overflow mode chosen at compile time. `if_else` copies every value,
null slots included, unless the input is more than four fifths null, and only
then pays for bit-masked copying. `is_in` is a hash memo table always. Kernels
write into slices of one contiguous preallocation across execution chunks
(`can_write_into_slices`, `exec_chunksize`, default sixty-four thousand rows),
which is the batch as a cache-sized window over a larger output. Year, month
and the rest go through Hinnant's `year_month_day` per element
(`scalar_temporal_unary.cc`, `struct Year`) with a localiser for the zone; the
record already has that decomposition, and there is no calendar kernel here to
learn from.

**Where it stops.** Scalar kernels carry a `SimdLevel` so that a function may
hold several kernels of one signature and the dispatcher pick the best the CPU
supports (`function.cc`, `DispatchBest`) - and the only kernels that use it are
the aggregates (`aggregate_basic_avx2.cc`, `aggregate_basic_avx512.cc`); every
scalar kernel is plain C++ left to the compiler's auto-vectoriser over the
all-set blocks the visitor hands it. It is the fourth engine on that bet after
Gandiva, Trino and DuckDB, with the most honest structure for it: the visitor
guarantees the compiler a contiguous, branch-free loop whenever the data allow
one. `ARROW_USER_SIMD_LEVEL` caps the level from the environment, the
measurement lever the compress question (Item 19.2) needs and this engine has
as `-XX:UseAVX`.

**Two things worth taking, and a fact.**

1. **A proven comparison reuses the validity bitmap.** `SimplifyWithGuarantee`
   (`expression.cc`) simplifies a filter under a guarantee - a predicate known
   true, in Arrow's case a partition expression such as `x = 5` or `x > 3`
   (`Inequality`, `ExtractKnownFieldValues`) - and when a comparison on a
   nullable field is proved true by the guarantee it does not become the
   literal `true`: it becomes `true_unless_null(x)`, which "purely reuses the
   validity bitmap for the values buffer", because the comparison is still
   `NULL` where `x` is. This engine's range analysis (task 84) proves
   comparisons true or false the same way, and Item 21's backward pass will
   prove more; when it does, the result of a proven compare is the operand's
   validity word, one load and no compare, and the proven-false case is the
   zero word. A proven `IN` is deliberately not simplified to `true` for the
   same null reason, which is the case a first version gets wrong.
2. **`PEXT` is not free everywhere.** `CpuInfo::HasEfficientBmi2` returns true
   only for Intel: "BMI2 (pext, pdep) is only efficient on Intel X86
   processors", and Arrow's AVX2 index-extraction paths (`compute/util.cc`,
   `bits_to_indexes`) fall back to scalar code elsewhere. This engine's
   compaction uses `Long.compress` on the validity bits, which HotSpot lowers
   to `PEXT` wherever BMI2 is advertised; on AMD before Zen 3 that instruction
   is microcoded and slow, on Zen 3 and later it is fast, and Arrow's rule is
   older than that change. The gate Item 19.2 files should consider both
   instructions and read HotSpot's own match rules (recorded there) rather
   than a vendor name; Zen 5 is the development machine and Zen 3 the common
   runner, so neither pays today, and the measurement is for the machines the
   public post will be read on.

**Noted for later.** `FilterOptions::NullSelectionBehavior` names the choice a
filter makes for a `NULL` predicate - `DROP` or `EMIT_NULL` - as an option;
SQL is `DROP` and this engine has only that, rightly. The all-scalar shortcut
in `ExecuteScalarExpression` evaluates a batch of length one when every input
is a literal, which is constant folding at run time for a plan the optimizer
did not fold. `BitBlockCounter::NextFourWords` reads five words to produce a
256-bit block when the bitmap is unaligned, which is the same slack-past-the-end
requirement this engine's compaction places on its destination.


### Item 23. ClickHouse's evaluator, the part the calendar read left

Recorded on 16 September 2026, from a survey of `src/Functions/IFunction.h` and
`IFunction.cpp`, `src/Functions/FunctionsLogical.h`, `src/Columns/MaskOperations
.cpp`, `src/Columns/ColumnVector.cpp`, `src/Common/PODArray.h`,
`src/Interpreters/ExpressionActions.cpp`, `src/Interpreters/ExpressionJIT.cpp`,
`src/Interpreters/JIT`, `src/Analyzer/Passes/OptimizeDateOrDateTimeConverter
WithPreimagePass.*`, `src/Storages/MergeTree/KeyCondition.cpp` and
`src/Core/Settings.cpp` at commit `ab12a1449`, made at the owner's request.
Item 10 read ClickHouse's calendar - the date lookup table, `toYear`, "the
rest of ClickHouse's date code, read so it need not be read again" - and
nothing else of it is in the record; this item is the evaluator, and the
calendar is not reopened.

**Arrived at twice.** A function declares its contract as flags on `IFunction`
- `useDefaultImplementationForNulls`, `ForConstants`, `ForLowCardinalityColumns`,
`ForSparseColumns`, `isSuitableForConstantFolding`, `isDeterministic` - and a
generic layer honours them before `executeImpl` runs: nullable arguments are
replaced by their nested columns, the kernel runs over every row "with garbage
input for the null rows", and the result is wrapped with the OR of the
arguments' null maps (`IFunction.cpp`, `defaultImplementationForNulls`); a
single low-cardinality argument with constant companions runs the kernel over
the dictionary and keeps the indexes (`IFunction.cpp`, "single-dictionary fast
path"). That is Gandiva's classification, Velox's and Trino's dictionary rule
and this engine's validity algebra, stated once as function metadata. Nulls
are a byte per row (`ColumnNullable`, a `ColumnUInt8` null map), not a bitmap.
Every column is a `PaddedPODArray` with sixty-four bytes of slack after the
data and sixty-three before (`PODArray_fwd.h`, `Defines.h`), so a kernel may
read or write a whole register at the last element without a tail, and the
default block size is sixty-five thousand four hundred and nine rows so that a
block plus its padding is exactly sixty-four kibibytes. The filter over a
numeric column turns sixty-four mask bytes into one word with four
`movemask`s, copies the sixty-four values when the word is all ones, skips
them when it is zero, and otherwise compress-stores by the word
(`ColumnVector.cpp`, `doFilterAligned`); the compress path is compiled as a
separate target variant and taken at run time only where `isArchSupported(
x86_64_icelake)`, so every width has the native instruction and no width
falls to emulation. Three-valued `AND` and `OR` are computed on a two-bit
code chosen so that `False < Null < True` and the operators become `min` and
`max` (`FunctionsLogical.h`, `namespace Ternary`), one vectorisable op each
after an encode of three. Arithmetic wraps; overflow checks exist for
decimals only (`FunctionBinaryArithmetic.h`, `check_overflow`), so nothing
about `ANSI` transfers. The preimage API is here in full - `getPreimage`
returning a left-closed right-open interval, the analyzer pass that turns
`toYear(c) = 2023` into a range on `c` behind `optimize_time_filter_with_preimage`,
and `getMonotonicityForRange` chains that let the primary-key index answer a
predicate on a function of the key (`KeyCondition`,
`applyMonotonicFunctionsChainToRange`) - which is the origin DataFusion cites
(Item 21) and DuckDB reinvents by bisection (Item 20). An `if` chain is
folded to one `multiIf` (`IfChainToMultiIfPass`).

**Two things worth taking, and one to measure.**

1. **Lazy arguments gated per function.** `and`, `or`, `if` and `multiIf`
   declare themselves short-circuit (`isShortCircuit`), and their arguments
   arrive as unevaluated `ColumnFunction`s that are run only over the rows the
   operator has not decided: the argument's inputs are filtered by the mask,
   the function runs on the survivors, and the result is expanded back with
   defaults in the unselected rows (`MaskOperations.cpp`, `maskedExecute`);
   an all-zero mask evaluates nothing, an all-one mask evaluates without the
   filter. The policy that makes this pay is per function: an argument is
   evaluated lazily only if its function says it is suitable
   (`isSuitableForShortCircuitArgumentsExecution`), which the comment defines
   as "can throw an exception or it's computationally heavy" - integer
   division and modulo say yes, plain arithmetic says no, and
   `short_circuit_function_evaluation = force_enable` overrides for every
   function. This is the answer to the question Items 20 and 21 left open,
   when a `CASE` arm or an `AND` operand should be narrowed rather than
   blended: not by selectivity, which is not known at compile time, but by
   the arm's own cost and fallibility, which are. This engine knows both at
   emission - a chrono decomposition in an arm is heavy, an armed guard is the
   analogue of "can throw" - so the rule can be applied per node with no
   measurement of the data, and the surface benchmark's `CASE` rows are its
   measurement. It is a candidate row.
2. **Filter the nulls out before an expensive function.** When the fraction of
   rows with a null in any argument reaches
   `short_circuit_function_evaluation_for_nulls_threshold`, the default
   implementation filters every argument down to the non-null rows, runs the
   kernel on those, and expands the result (`IFunction.cpp`, "If short circuit
   is enabled"); the threshold defaults to one, so out of the box only the
   all-null batch is skipped, which this engine also does. The knob is the
   point: the same per-node cost that decides item 1 decides whether a
   mostly-null batch is worth compacting before a heavy kernel rather than
   running it masked over every lane, and this engine's compaction (`SelectionVectorOps`)
   is the filter step already written.
3. **The ternary code, to measure.** `min` and `max` on a two-bit code is the
   cheapest three-valued `AND` and `OR` on record, and this engine computes
   them on separate value and validity words with several ops each. Encoding
   costs three ops and decoding two, so a chain of several logical operators
   would have to be long before the code wins, and the words are already what
   the guards and the compaction consume. Recorded as a shape for the IR
   fuzzer's op-count oracle to price, not as a task.

**Where it stops, and this engine goes on.** ClickHouse is the one engine in
this survey that does both things: it interprets over padded columns, and an
LLVM JIT (`ExpressionJIT.cpp`, `CompileDAG`) fuses a chain of compilable
functions - arithmetic, comparison, conversion, logic, `isNull`, `isNotNull`,
`assumeNotNull`, `toNullable`, fourteen files declare `isCompilableImpl` -
into one loop per DAG fragment, with nullable values carried inside the
compiled code as value-and-flag pairs, and lazily evaluated arguments excluded
from compilation. Two policies differ from this engine's. The JIT compiles an
expression only after it has been seen three times (`min_count_to_compile_expression`),
caching by hash; this engine compiles on first sight and lets the JVM's tiers
defer the expensive optimisation, which is the right split for bytecode. And
the compiled loop is scalar IR left to LLVM to vectorise, the same bet as
Gandiva's with the same compiler; the emitter that writes lanes is still the
step not taken.


### Item 24. Comet, the accelerator built for the same contract

Recorded on 16 September 2026, from a survey of `datafusion-comet` at commit
`8c229a703`: `spark/src/main/scala/org/apache/comet` (`serde`, `rules`,
`codegen`, `CometConf`, `ExtendedExplainInfo`, `SparkErrorConverter`),
`native/spark-expr`, the contributor guide (`sql_error_propagation.md`,
`adding_a_new_expression.md`, `optimizing_expressions.md`, `expression-audits`)
and the test base, made at the owner's request. Comet was not in the record.
It is the one system in this survey with this engine's exact contract - a
plug-in that takes Spark's physical plan, runs what it can in another engine,
falls back for the rest, and must answer what Spark answers under `ANSI` - so
the comparison is of contracts and process more than of kernels, and its
kernels are DataFusion's (Item 21), read there.

**Arrived at twice.** Every expression has a serde object that says at
planning time whether it is `Compatible`, `Incompatible` with notes, or
`Unsupported` with a reason (`SupportLevel`), and the reasons are tagged on
the plan node and printed by `EXPLAIN` as `[COMET: ...]` (`ExtendedExplainInfo`,
`withFallbackReason`), which is this engine's decline reason in verbose
`EXPLAIN` (`docs/sql-varka.md`). The support table and the compatibility guide
are generated from those objects (`GenerateDocs`), as `coverage.json` is from
the compiler, and a rule that all data-producing children must be native
before an operator converts keeps islands whole. `EvalMode` is `Legacy`,
`Ansi`, `Try` (`native/spark-expr/src/lib.rs`), this engine's overflow modes.
Its recent kernel tuning is the arithmetic this engine started with:
`dayofweek` computed from the epoch day in one modulo instead of a calendar
date per row (`day_of_week.rs`, recorded in the audit as about nine times
faster), `hour`, `minute` and `second` by Euclidean division on the stored
microseconds; `date_trunc` still builds a `chrono` date per row
(`kernels/temporal.rs`). Its optimisation guide says what this repository's
skills say: measure first, keep the output bit-identical, prove the win with a
benchmark over the shapes where the change could backfire, and do not submit
without one.

**Where Comet paid for what this engine declined to buy.** Under `ANSI`, Comet
raises Spark's errors natively: each expression's query context - the start
and stop character offsets and the SQL text - is serialised into the plan,
interned into a pool, registered in a native map by expression id, attached
to the error the kernel raises, carried back as JSON and converted by a
per-Spark-version shim into the typed Spark exception with its
`SQLQueryContext` (`sql_error_propagation.md`, `QueryContextInterner`,
`SparkErrorConverter`, `ShimSparkErrorConverter`). It is a pipeline of its own,
and the compatibility guide still lists residual divergences - a byte or short
overflow raising `ARITHMETIC_OVERFLOW` where Spark raises
`BINARY_ARITHMETIC_OVERFLOW`, a long overflow reported as "integer overflow",
Rust type names in `abs` messages - and the contributor guide forbids wiring
any expression that exposes `failOnError`, `evalMode`, `nullOnOverflow` or
`ansiEnabled` through the generic scalar path, failing closed instead. This is
the evidence for the choice Item 18 recorded: declining the batch under `FAIL`
and letting the row engine raise costs nothing to keep exact, and the
alternative is a subsystem with a published list of the ways it is not.

**Three things worth taking.**

1. **The per-expression switch, and the honest tier.** Every Comet expression
   can be disabled alone (`spark.comet.expression.<Class>.enabled=false`) and
   an expression with known differences runs only if the user opts in for
   that expression (`.allowIncompatible=true`); there is no global opt-in
   (`CometConf.isExprEnabled`, `getExprAllowIncompatConfigKey`,
   `expressions.md`). This engine has one switch. A per-expression kill switch
   is the escape hatch a user needs when one kernel is wrong in production and
   the rest are not, and it is cheap: the compiler already declines by node,
   and a declined node with a reason is what the switch produces. The
   incompatible tier is not needed while every fused arm is exact, and should
   stay unneeded; the switch is a candidate row.
2. **The batch kernel from Spark's own codegen.** `CometBatchKernelCodegen`
   compiles a bound Catalyst expression plus an Arrow schema into one
   Janino-compiled method per expression and schema that reads the Arrow
   columns through `InternalRow` views, runs Spark's generated code for the
   expression, and writes one Arrow output vector - with a `NullIntolerant`
   short circuit and a common-subexpression variant - and Comet routes an
   expression through it by default whenever its native path is inexact,
   because "a byte-exact match to Spark matters more than the native speedup"
   (`compatibility/index.md`). It is Item 16's per-node fallback, built and
   shipped: an unsupported or inexact node evaluated by the row engine over
   the batch, its result handed to the columnar neighbours as a derived
   column, at the cost of one crossing per batch. Here the crossing has no
   JNI in it, which makes the case stronger, and the design question Item 16
   left - how the fused kernel takes a derived input - has Comet's answer:
   as one more Arrow vector in the batch.
3. **Tests that assert the reason.** The test base has, beside the answer
   check, `checkSparkAnswerAndOperator` (every operator replaced except a
   named list), `checkSparkAnswerAndFallbackReason` (the answer matches *and*
   the plan carries this fallback text), and `checkSparkAnswerMaybeThrows`
   (both engines throw, or both agree) (`CometTestBase`). This engine's
   compiler suite asserts decline reasons and its differential asserts fusion;
   the "maybe throws" form is the one it lacks and the one `ANSI` rows need:
   under `FAIL` the assertion is that Varka and the row engine either both
   raise the same error or both agree, which is stronger than "declined". A
   small addition to `VarkaSharedSessions`, best made when the first `ANSI`
   overflow row lands in the coverage table.

**Noted for later.** Comet reverts a whole stage to Spark rows when it counts
more than a configured number of columnar-to-row transitions in it
(`transitionRevert.enabled`, `maxTransitions`), which is the plan-shape cost
this engine's fused filter-to-row node already avoids for one node and does
not yet count across a stage. Its native columnar-to-row converter writes
`UnsafeRow`s into one reused buffer that the rows point into, and it keeps an
isolated conversion benchmark with the scan excluded (`CometC2RIsolatedBench`);
this engine measures the row consumer inside the throughput benchmark, and an
isolated number belongs with whatever task next touches `VarkaColumnarToRowExec`.
Its expression audits (`expression-audits/*.md`) keep, per expression, dated
notes across Spark versions, a "tuned on" line with the PR and the speedup, and
a native-candidate assessment, updated by an agent skill; this repository keeps
the same facts per task in the plans, and the per-expression index is the view
a newcomer asks for first. Its fuzz data generator has switches for nulls,
`NaN`, negative zero, infinities, a base date and custom strings
(`DataGenOptions`); the date and string switches are the ones the IR fuzzer's
fixtures could take. And it runs Spark's own SQL test suites with Comet
enabled by patching the test base (`spark-sql-tests.md`); this engine is a
fork and can flip its default in one job, which is the widest differential
available and not yet in the record as a row.


### Item 25. Fifteen papers, read against the engine

Recorded on 16 September 2026. The owner downloaded fifteen papers on SIMD
query execution to `/home/max/Documents/SIMD`; they were transcribed
mechanically the same day (the method of `sql/varka/papers/README.md`) and read
against Items 1 to 24, `VISION.md` section 14, the design doc and the skills.
Four whose printed terms permit a copy - Lang 2020, Ngom 2021, Benson 2023,
Schmidt 2025 - are in `sql/varka/papers`; the other eleven are ACM copyright,
Creative Commons BY-NC-ND or arXiv postings, so this item is their record. Page
numbers below are the PDFs' pages. Where the papers disagree with each other,
the disagreement is written down rather than resolved, because it is a
measurement.

**What the papers confirm.** The three structural choices this engine made
without them are the ones they defend by measurement. Lang (VLDB Journal 2020,
section 6) finds that materialising a filter's survivors at an operator
boundary is the best form on out-of-order cores and that leaving lanes
protected inside a pipeline ("partial consume") loses by up to half; that is
`VarkaFilterExec`'s compaction into a dense batch, and partial consume is
excluded from the option space. Kersten et al. (PVLDB 2018, pp. 4-5) find that
fused loops win compute-bound work because intermediates stay in registers -
Typer ran Q1 in 68 instructions per tuple against Tectorwise's 162 - which is
the register-resident argument of `VISION.md` section 14 with a number, and
they report that fusing adjacent vectorised primitives into one JIT-compiled
loop "has not (yet) been integrated into any system" (p. 11), which locates
this engine. Benson, Ebeling and Rabl (ADMS 2023) reach in C++ the conclusion
this engine reached for the JVM: one portable vector layer, explicit emission
rather than the auto-vectoriser (good in two of eight cases), and one
platform-specific island where no portable form reaches the instruction -
compress-store, for them as for Item 19.

**A. Selection, divergence and narrowing** (Lang 2018 and 2020; Ngom et al.,
DaMoN 2021; Raducanu, Boncz and Zukowski, SIGMOD 2013; Polychroniou and Ross,
DaMoN 2019). Lang's cost model gives Item 16 a rule in place of "build both":
with one vector to refill, the best threshold is all lanes, so a heavy remainder
should never run with idle lanes, and the optimum falls to about five of eight
when five vectors must be refilled (pp. 10, 15); the no-op case costs a
popcount and a branch, under six percent (p. 17); static register allocation
charges for refill buffers even when unused (p. 16), which is the dead-local
effect in `vector-api-and-width.md` and the reason any in-kernel buffer must be
measured at 128 bits too. Ngom's model - tuples processed times per-tuple
iteration cost plus operation cost - puts the Full-versus-Selective crossover
above about fifteen straight-line operations (pp. 4-5); this engine's calendar
arms are about thirty; strings and integer division should run over the
compacted batch, never under a mask (pp. 3-4); and bitmap-to-selection
conversion costs a fraction of a percent (pp. 5-6), the number behind keeping
the bitmap canonical. Raducanu's Table 9 (p. 10) is the loss case the task must
register: forced full computation cost 43 percent overall and 13x on single
instances, and it paid from 30 percent selectivity on 32-bit lanes and never on
64-bit ones (p. 6), so no int32 threshold carries to milestone 5's long lanes.
Raducanu's chooser itself - explore and exploit phases on a recent-window mean,
parameters 1024, 8 and 2 (pp. 7-9) - is a fourth candidate policy beside Items
18 to 20, with two JVM caveats it never faced: an unexercised body never
reaches C2, and JIT bimodality makes a body's cost non-stationary within a run.
Polychroniou 2019 (p. 2) adds the cheapest variant, skipping a whole lane group
when the running conjunction word is zero for it, with no data movement; the
ByteSlice early-stop argument (below) says such a skip pays only where the
branch is almost always taken and narrowing should be batch-granular. The two
readings disagree on granularity, and Item 16's task settles it on a
low-selectivity ladder with a heavy remainder. Kersten's Q6 cascade (pp. 6-7)
rules out one form outright: a selection vector that turns contiguous loads
into gathers collapses the SIMD gain to scalar parity below fifty percent
selectivity, so narrowing is in-register compress or lane-group skip, never a
selection vector feeding gathers.

**B. Compilation, vectorisation and the earlier Spark attempts** (Kersten 2018;
Shen, Xiong and Jiang, ICPP 2021; Behm et al., SIGMOD 2022). Kersten's limit is
as useful as his support: vectorised interpretation wins hash-probe work
because simple loops keep more loads in flight, gather buys 1.1x, and the gain
vanishes once the table leaves cache (pp. 5, 7), so Item 4 should keep probes
as simple loops behind a batch boundary and not fuse them into the kernel.
Vector size between one and four thousand rows was best, under 64 and over 64K
hurt (pp. 5-6), which frames Item 14's sweep. He warns that IPC misleads (Q1:
40 percent higher IPC and 74 percent slower, p. 5), so comparisons rest on
stall cycles and op counts, and that branch-free all-lanes selection lost 20
percent at 20 threads from bandwidth (p. 8, footnote 8), the many-task rung
below. Shen's VEE is the third JVM attempt, missing from section 14 until this
item: a whole-engine fork of Spark 2.4 in Java relying on the JIT, no Vector
API, no fused loop, with vectorised shuffle, sort and aggregation; its own
decomposition shows plain X100-style vectorisation on Spark slower than
whole-stage codegen on 21 of 22 TPC-H queries, and the whole gain coming from
shuffle and cache-aware operators (p. 10). That is the published reason the
write-up leads with kernel-against-boundary attribution. Shen's batch length
is derived from the working set a step touches against the last-level cache
(p. 6), with an in-cache optimum near one megabyte of touched vectors (p. 8):
for a fused kernel the touched set is known at emission, so Item 14's batch
length can be per shape. Photon is Item 24's contract in native code: column
batches with a position list of active rows, kernels templated on
has-nulls times all-rows-active (Listing 2, p. 7), per-batch adaptivity on
nulls, active rows and ASCII-ness (p. 7), a rule that never starts an island
mid-plan because each costs a pivot (p. 8), a buffer pool sized by the fixed
number of allocations per batch (pp. 6-7), and a testing regime that runs one
expected table through every specialisation and hooks Spark's own expression
unit tests through the function registry (p. 9). Its reasons for leaving the
JVM and codegen (pp. 2, 4-5) each have a named answer here; the 64 KB epilogue
(task 87) is the one cliff the write-up should own, and the two parity hazards
it names, native casts and time zone database versions, do not exist in the
same JVM.

**C. SIMD scans and layouts** (Willhalm et al., VLDB 2009; Feng, Lo, Kao and Xu,
SIGMOD 2015; Polychroniou, Raghavan and Ross, SIGMOD 2015). SIMD-Scan's rule,
shift the constant rather than the data (p. 7), transfers to literal slots and
to Item 11's frame-of-reference form: compare rebased narrow lanes against the
rebased literal and fold an out-of-range literal to a constant mask. ByteSlice
gives the early-stop probability, one minus two to the minus t, to the power of
lanes over eight (Eq. 2, p. 6): at register width the branch is taken almost
always, at a 64-row word about three quarters of the time, at a batch never -
which is why task 24's per-group branch was declined and why any word-level
skip must be justified as a predictable branch on the running conjunction mask,
not on validity; its column-first pipelining beat predicate-first (p. 10), and
its census that ninety percent of TPC-H columns encode under 24 bits (p. 13)
supports the sixteen-lane form, with byte-slicing itself at most a filter-side
cache layout, since reconstruction costs a load per slice. Polychroniou 2015
supplies two designs: a vertical `IN` probe - a collision-free table built from
the compile-time literals, then hash, one index-map gather over a heap array,
one compare (p. 5) - as the second arm Item 19 leaves unmeasured, lifting the
sixteen-literal cap; and the vectorised Bloom filter probe (pp. 6, 10), which
matters because Spark's optimizer injects `BloomFilterMightContain` on the
probe side of most joins, it is a filter expression inside this engine's
contract, it needs only milestone 5's long lanes and a lane hash, and it is
absent from the record. His Haswell result that every vector selection variant
saturates bandwidth and branchless scalar catches up at ten percent
selectivity (p. 9) is the reading rule for filter benchmarks: once a selection
is at bandwidth, further kernel work is invisible, and Items 13 and 14 gate
every filter number.

**D. Hardware and intrinsics** (Benson 2023; Boether, Benson, Klimovic and Rabl,
PVLDB 2023; Schmidt et al., CIDR 2025; Boivin and Legaux, arXiv 2026). Benson
measured the compress gate's two instructions: native compress-store is worth
several times the best shuffle path on Ice Lake and scales 3.4x from 128 to 512
bits where table-lookup variants scale 2x (pp. 8, 10), PEXT loses to shuffles
even on Intel (p. 7), and Velox's PDEP unpacker ran at a tenth of scalar speed
on AMD Rome (p. 9); Items 19 and 22 cite it. His Ice Lake finding that the
core retires two 256-bit operations per 512-bit one (p. 7) is prior art for
`PLAN_TASK_62.md` section 11, and his long-multiply emulation losing on
AVX2-only parts (p. 5) is a row milestone 5 must add under `-XX:UseAVX=2`
before any Zen 3 runner number. He and Boether both found mask-to-bitmask the
worst code generation on NEON, with different best answers per shape (Benson
p. 6; Boether pp. 5-7, 12); this engine's validity words lean on that
conversion, so it is the first Graviton measurement. Boether's bucket-based
comparison - 16 to 64 one-byte fingerprints compared per operation, taken from
the bits the index does not use, tested for any match before extracting one
(pp. 4-8) - is the other candidate for hash-based `IN`, lane-friendly and
needing no gather; the test-before-extract rule applies to the compare chain
today. Schmidt shows a 48-core socket's DRAM saturating at about twelve scalar
threads with SIMD only lowering that count (p. 2): this engine's headline
numbers are one partition on one core, and the write-up must say per shape
whether a win is compute-bound; a partitions ladder on the Zen 5, committed,
makes that a measurement. Boivin's one useful citation is the AVX-512 frequency
licence effect (p. 3), unmeasured here and to be named in the hardware
section; his data-dependent-branch results (p. 10) are the compiler-specific
case for explicit vector code that this engine settles by measurement.

**Applicable now, as rows or amendments.**

1. Item 16 gets Lang's threshold rule, Ngom's crossover, Raducanu's loss case,
   Kersten's exclusion of gather-fed selection vectors, and the granularity
   disagreement as the thing to measure.
2. Milestone 5 re-measures selectivity policy and long multiply at 64-bit lanes
   (Raducanu p. 6; Benson p. 5) before inheriting any int32 number.
3. Item 14 sweeps batch size from one to sixteen thousand rows and derives a
   per-shape length from the touched set (Kersten pp. 5-6; Shen pp. 6, 8) and
   an output-pool size from the plan (Photon pp. 6-7).
4. The public write-up states the concurrency regime from a committed
   partitions ladder (Schmidt p. 2; Kersten p. 8) and names the frequency
   licence as unmeasured (Boivin p. 3).
5. Items 19 and 22 cite Benson's measurements for the compress gate.
6. Benchmarks gain a log-scale low-selectivity ladder with a heavy projection
   stacked on the filter, stall-cycle counters beside throughput, and a rule to
   read Intel-runner bimodality against code alignment first (Lang p. 9; Kersten
   p. 5; Benson pp. 8-9).
7. Testing gains Photon's two forms: every coverage row through both bodies
   and a lane tail with poisoned null lanes, and a hook so Spark's own
   `checkEvaluation` rows also run through a fused plan when the compiler admits
   them (p. 9).
8. The planner gains a "would add a pivot" decline reason and a per-node split
   of kernel time from conversion time (Photon p. 8; Item 24).
9. Two code rules: transform a literal once rather than per lane (Willhalm
   p. 7); test the mask before extracting a match in the `IN` chain (Boether
   p. 6).

**Applicable later.** The vertical probe and the fingerprint bucket as the two
arms against the sorted `IN` chain; `BloomFilterMightContain` as a filter
kernel on long lanes (Item 27 carries the exact recipe Spark's filter
imposes, and corrects "needs only long lanes and a lane hash"); probes kept out
of fused kernels and lane-replicated
accumulators for Item 4 (Item 26 adds that the index-map scatter those accumulators need is
lowered only under AVX-512 and is a Java loop on AVX2 machines); ASCII-ness as
a batch fact (Photon: 3x on `upper`,
p. 10) and the missing cross-lane byte shuffle on AVX2 (Benson p. 7) for Item
3; the sixteen-lane frame-of-reference form with rebased literals for Item 11;
mask-to-bitmask before any NEON number; micro-adaptivity as a fourth policy.

**For `VISION.md` section 14**, done in this item's commit: VEE as the third
attempt with its 21-of-22 result, and a paragraph on what the literature says
about where the fused-loop argument holds and where it stops.


### Item 26. HotSpot, read as a system

Recorded on 16 September 2026, from the JDK 25 sources at
`/home/max/proj/openjdk-build/jdk25`: `src/hotspot/cpu/x86/x86.ad` (the
instruction selectors and `Matcher::match_rule_supported_vector`),
`cpu/x86/matcher_x86.hpp`, `cpu/x86/c2_MacroAssembler_x86.cpp`,
`cpu/x86/vm_version_x86.cpp`, `cpu/aarch64/aarch64_vector.ad` and
`c2_MacroAssembler_aarch64.cpp`, `share/opto/vectorIntrinsics.cpp`,
`share/opto/loopTransform.cpp`, `share/opto/mempointer.hpp`,
`share/classfile/modules.cpp`, and `src/jdk.incubator.vector`'s `IntVector.java`.
The skills (`the-jit.md`, `vector-api-and-width.md`) established most of what
follows by measurement; this item is the mechanism, read from the source, and
the few places where the source says something the measurements did not reach.
Everything below is a statement about which selector matches, not about speed;
speed stays a measurement.

**Three outcomes, not two.** A Vector API call ends in one of three places. If
`match_rule_supported_vector` accepts the node for the CPU's features, an
instruction selector matches it, and the selector is either a single
instruction or a hand-written sequence in the macro assembler. If it does not,
the intrinsic is refused at `LibraryCallKit` and the call runs the Java
fallback in the API's own code (`IntVector.java`, `compressTemplate` and its
kin pass a lambda that loops over lanes), which is correct, slow and silent.
The only place the refusal is visible is `-XX:+UnlockDiagnosticVMOptions
-XX:+PrintIntrinsics`, which prints lines beginning `  ** not supported:`
(`vectorIntrinsics.cpp`, `log_if_needed`), and nothing at run time counts
them. Intrinsics exist at all only because `modules.cpp` sets
`EnableVectorSupport`, and with it the two reboxing flags, when
`jdk.incubator.vector` is defined to the boot loader at startup, and logs
`EnableVectorSupport=true` under `-Xlog:compilation`; a JVM that reaches the
module any other way runs every call as the Java fallback with no error.

**Which selector, per operation, on x86** (feature names are the source's).

* `compress`, `expand`: `vcompress_expand_reg_evex` under AVX-512VL or at 512
  bits; otherwise `vcompress_reg_avx`, a permutation through a stub table, and
  for mask compress (`CompressM`) AVX-512 plus BMI2 only (Items 19 and 22).
* `VectorMask.toLong`: with mask registers `kmov`, one instruction; on AVX2 one
  `vmovmskps` or `vmovmskpd` for int and long lanes (`vector_mask_operation`),
  with a `pext` only for the sub-word lane types. Cheap everywhere on x86.
* `VectorMask.fromLong`: `kmovq` with mask registers; on AVX2
  `vector_long_to_maskvec`, which begins with `pdepq` and continues with moves
  and a sign-extending widen, about eight instructions. So the skill's lesson
  that a guard costs its `fromLong` has its mechanism, and the AMD-before-Zen-3
  `PDEP` caution of Item 22 applies to `fromLong` as well as to
  `Long.compress`.
* Gather, `fromArray` with an index map: `vpgatherdd` and `vpgatherdq` on AVX2
  up to 256 bits for int and long; the masked form needs AVX-512VL or a 512-bit
  vector, otherwise Java; sub-word gathers are scalar loops emitted by C2. There
  is no gather from a `MemorySegment` in the API at all.
* Scatter, `intoArray` with an index map: `match_rule_supported` refuses it
  below `UseAVX=3`, so on AVX2 it is a Java loop. This corrects Item 25's note
  that lane-replicated accumulators are "expressible today": they are, on
  AVX-512 machines only.
* Long multiply: `evpmullq` needs AVX-512DQ (plus VL below 512 bits); otherwise
  `vmulL_reg`, a sequence of `vpmulld`, `vpmuludq`, shifts and `vpaddq`, and
  `matcher_x86.hpp` charges it six nodes against the unroll limit. Long
  minimum and maximum below 512 bits without AVX-512 are a compare and a blend;
  long absolute value (`AbsVL`) is refused below `UseAVX=3` and is Java on AVX2.
  Long reductions need AVX-512DQ for the single-instruction form, and long
  min and max reductions are refused without AVX-512VL, BW and DQ. Milestone 5's
  64-bit lanes meet all of these on the Zen 3 runners.
* Masked loads and stores: `vpmaskmovd` and `vpmaskmovq` from AVX1 for int and
  long lanes; the sub-word forms need AVX-512BW and are Java below it, which
  Item 3's byte kernels will meet on AVX2 machines.
* `rearrange`: `vpermd` for int lanes on AVX (256 bits needs AVX2); for long
  lanes below eight without AVX-512VL an emulation; for byte lanes at 256 bits
  a multi-instruction sequence with two temporaries unless AVX-512VBMI, which
  is Benson's "no cross-lane byte shuffle on AVX2" (Item 25) read from the
  selector.
* Lane popcount: one instruction only with AVX-512 VPOPCNTDQ (BITALG for
  sub-word); otherwise a table sequence charged forty to fifty nodes.
  Counting leading or trailing zeros needs AVX-512CD for one instruction.
  Rotates are one instruction under AVX-512 and shift-shift-or otherwise, in
  C2, not Java.
* `anyTrue`, `allTrue`: `vptest` from SSE4.1, `ktest` with mask registers, one
  instruction.
* The default width: `MaxVectorSize` is set to the highest the CPU supports, 64
  when `UseAVX` is 3, with one exception written into the source: "Don't use
  AVX-512 on older Skylakes unless explicitly requested" - on Skylake server
  parts below stepping 5, that is before Cascade Lake, HotSpot itself defaults
  `UseAVX` to 2 (`vm_version_x86.cpp`). That is the JVM's own answer to the
  frequency licence Item 25 names, and the hardware section can cite it.

**On AArch64.** `aarch64_vector.ad` refuses under NEON alone every one of
`LoadVectorMasked`, `StoreVectorMasked`, `VectorMaskGen`, `CompressV`,
`CompressM`, gather and scatter: on a NEON-only part such as Graviton2 all of
them are Java loops. Gather needs SVE and is refused for sub-word types; expand
needs SVE2. `toLong` on NEON is `fmov` plus a three-`orr` byte-mask compress
for up to eight lanes and twice that plus an `orr` for sixteen, about eight
instructions, and on SVE a path that wants the bit-permute extension.
`fromLong` needs `svebitperm`, which is SVE2, and is refused otherwise: on an
SVE1 part such as Graviton3 the validity word's `fromLong` per lane group would
be a Java loop. This is the concrete form of Item 25's "mask-to-bitmask is the
first NEON measurement": the selectors say which conversions are not lowered at
all, before anything is timed.

**Unrolling, from the source.** `policy_unroll` sums a body size in which most
vector nodes count one and a few count more (`vector_op_pre_select_sz_estimate`:
long multiply six without AVX-512DQ, gathers of sub-word types fifty, lane
popcount forty or fifty without the instruction, float-to-int casts thirty),
and refuses to unroll when that exceeds `LoopUnrollLimit`, sixty on x86, with a
four-times allowance only for sub-word loops. A fused calendar body is far past
sixty, which is the mechanism behind the skill's measured "entered zero times".
`LoopMaxUnroll` is sixteen and profile trip counts cap it further.

**SuperWord and native memory.** JDK 25's `MemPointer` parses native addresses,
including a `MemorySegment` over native memory (`mempointer.hpp`, example 6),
and `vectorization.cpp` adds a speculative alignment check for a native base
or gives up. So the auto-vectoriser is not blind to off-heap loops in
principle; the skill's measurement that a trivial `MemorySegment` loop gained
almost nothing and the calendar loop was never entered stands, and its cause is
body size and profitability, not the address kind.

**Worth taking, as rows or amendments.**

1. A no-fallback proof in CI: run the kernel suites once under
   `-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics` and fail on any
   `** not supported` or `** Rejected` line whose method is a Varka class. It is
   the JVM's own statement that every emitted operation was lowered, the kind of
   evidence the project prefers, and it is the only way a silent Java fallback
   on a new runner or a new lane type shows itself before a benchmark does. A
   startup line under `-Xlog:compilation` confirming `EnableVectorSupport=true`
   belongs beside the datapath probe for the same reason.
2. Milestone 5's 64-bit plan lists the operations above that are sequences or
   Java on AVX2 - long multiply, absolute value, min and max reductions, masked
   sub-word access - and measures each on the Zen 3 runner before any long-lane
   number is published; Item 25's `-XX:UseAVX=2` row covers the multiply, and
   the others are the same run.
3. Item 19's gate reads two more selectors: `fromLong` on AVX2 is `PDEP`, and
   scatter and long absolute value are AVX-512 only. The gate is a table of
   operations against features, which the match rules already are; the
   engine's version of that table is what the emitter should consult when it
   chooses a body.
4. The ARM plan, when there is one, starts from the refused list: on NEON alone
   there is no masked access, no compress and no gather, and on SVE1 no
   `fromLong`; the design of the validity word on those parts is different, not
   slower.


### Item 27. Twelve more papers, the ones Item 25 asked for

Recorded on 16 September 2026. After Item 25 the owner downloaded the papers it
had named as unread, to `/home/max/Downloads/Worth_Papers`; they were
transcribed by the same method and read against the record in the same way.
None of the twelve prints a Creative Commons Attribution licence - VOILA and
the 2017 Gubner paper are BY-NC-ND, the rest carry ACM or IEEE notices or none
- so none is copied into `sql/varka/papers`, and this item is their record.
Page numbers are the PDFs'. Where a reader's claim was about this repository,
it was checked against the source before it was written here.

**A. Plans for conjunctions, and the staging point** (Ross, PODS 2002; Menon,
Mowry and Pavlo, PVLDB 2017). Ross is the theory behind the four engine
policies of Items 17 to 20 and 23. His cost model has three loop bodies -
branching `&&`, one-branch `&`, and a branch-free form that writes every row's
index and advances the output by the conjunction's value - each best in a
selectivity band and about twice worse than optimal outside it (pp. 3-4); the
normal form is a chain of branch-free `&`-blocks separated by `&&` points, the
cost of splitting `E & F` into `E && F` is paid back only when
`(1 - p_E) * cost(F)` exceeds the misprediction term, and the terms inside a
block order by `(p - 1) / cost` (pp. 5-7). Read in lanes, this engine's fused
conjunction is the branch-free plan, the only orderable thing is the position
of group-granular skip points, `cost` is the emitter's op count and `p` a
per-batch popcount; a skip point in front of a cheap remainder never pays, one
in front of a thirty-op calendar arm pays only when the group mask is almost
always zero, which is Lang's and Ngom's answer in Item 25 derived a third way.
His Appendix C is the warning aimed at this engine's kernels: on a superscalar
core every `&&` point serialises the blocks around it, so plans should have few
points and wide blocks, and the measurement needs stall cycles beside time
(p. 12). His precondition that reordered terms must never error (p. 2) is
Trino's rule (Item 19). Menon's relaxed operator fusion puts a stage boundary
on every SIMD operator's output so it delivers a full vector of valid ids, and
at the input of any operator doing random access into a structure larger than
cache, so it can prefetch in groups (pp. 4-6); his microbenchmark shows a
vertical SIMD probe losing to a scalar probe with prefetching even when the
table is cache-resident (p. 4), and stage vector sizes from 64 to 256 thousand
rows made no difference on seven of eight queries while the prefetch group
size did (pp. 9-10). For Item 4 the staging point is the dense batch this
engine already produces plus a companion column of lane-computed hashes, and
the probe is a scalar loop over it; the caveat is that JDK 25 has no software
prefetch, so the substitute is independent iterations that keep loads in
flight, to be confirmed from the disassembly.

**B. The design space, and compact types** (Gubner and Boncz, PVLDB 2021 and
ADMS 2017). VOILA generates the space between vectorised interpretation and
data-centric compilation from four components - computation (scalar, vector
primitives, or an eight-lane AVX-512 target), control (goto or state
machines), prefetch, and a buffer that physically removes filtered tuples - and
measures ten thousand flavours of one query ranging over ninety times (Table
6, p. 8). In that vocabulary this engine is a Spark data-centric row pipeline
with a fragment of the AVX-512 flavour spliced in for Project and Filter: goto
control, no prefetch, buffered at the filter and at the row boundary, with the
Arrow decode as the input buffer and `VarkaColumnarToRowExec` as the output
buffer. FUJI measured that computation flavour as the middle point on both its
queries, and its Table 6 says each flavour transition costs a buffer - which is
Items 13 and 14 with a published mechanism, and the argument for widening the
columnar span before tuning kernels. Its Q6 result is Item 16's missing loss
case: computing every predicate over all rows and building one selection from
the conjunction ran 2.2 times slower than a selection per predicate on a
selective filter (Table 7, p. 10). The 2017 paper's lever is compact types:
Q1's arithmetic fits bytes, shorts and ints, turning 320 bits of SIMD work into
128 (p. 3), and compact types pay only in vectorised flavours, which is Item
11's narrow-lane form as the paper's central result; its overflow rule -
prevent rather than detect, or OR the lanes' overflow flags and raise once per
morsel (p. 4) - is a data-driven alternative to this engine's range-derived
guards that milestone 5's long multiply should measure against
`inputBounds`; its identity hash for few-group aggregation, seven instructions
per sixty-four ids (p. 4), and its finding that fusing in-register aggregation
primitives "can even be detrimental" (p. 7) both go to Item 4.

**C. Tiers** (Kohn, Leis and Neumann, ICDE 2018; Kersten, Leis and Neumann,
VLDB Journal 2021). Kohn's adaptive execution maps onto the JVM exactly and
unflatteringly: his bytecode interpreter is HotSpot's interpreter, unoptimised
LLVM is C1, optimised LLVM is C2, his per-morsel function-pointer swap is
on-stack replacement, and his 0.7 ms code generation is this engine's 99
microseconds; his remaining-time arithmetic, run interpreted while the compile
would not finish sooner (Fig. 7, p. 5), is a cheap decline rule for tiny tasks
whose row count is known, and his per-morsel rate tracking is the measurement
this engine lacks - batches executed before a kernel's tier-4 compile landed,
recordable through the telemetry attribute and JFR. Kersten's Flying Start
makes the cheap tier only 1.2 times off optimal (Table 3, p. 18) so that "the
performance cliff becomes a small performance step"; the JVM inverts that
premise, since C1 with boxed vectors is about a hundred times off, so no
switching policy closes the gap and time-to-C2 per shape is the only lever,
which is why `the-jit.md`'s small-methods rule is this engine's Flying Start
and a per-shape time-to-tier-4 ladder belongs beside the cold-start benchmark.
Both papers confirm Item 23's compile-on-first-sight: Kohn says caching cannot
hide the first query's cost, and on the JVM running the kernel is the warm-up.

**D. Layouts, block statistics, and the Bloom filter** (Lang et al., SIGMOD
2016; Raman et al., PVLDB 2013; Polychroniou and Ross, DaMoN 2014). Data
Blocks keeps compression byte-addressable, evaluates predicates on the codes
after converting the constant once per block, and narrows scans with two
indexes: per-attribute minimum and maximum, and positional small materialized
aggregates, a 256-entry table keyed by the leading byte of the value minus the
block minimum that maps to a row range (pp. 3-5). Its numbers say byte-aligned
codes beat bit-packing on predicates and unpacking by large factors except when
everything qualifies (p. 12), which is the case for Item 11's narrow
frame-of-reference form over SIMD-BP128 in Item 7, and that predicates on
64-bit codes gained at most 1.5x (p. 9), a milestone 5 warning. For this
engine: Item 15's batch bounds should be computed at cache-write time as a
minimum and maximum per column per batch, so the guard decision is a compare
of the batch interval against the guard interval rather than a pass over the
data; and the positional table is a new batch-granular narrowing form for Item
16, turning an equality or range filter into a row range the lane loop runs
over, at cache-write cost and with no new Vector API. BLU's frequency
partitioning gives common values short codes and orders each partition so range
predicates work on codes (pp. 2-3); its rule of a cheap redundant minimum and
maximum check ahead of a long `IN` list (p. 4) is a one-rule change to
`compileInList` that composes with batch bounds to skip the chain for a whole
batch, and its evaluate-once-over-the-dictionary is Items 16, 18 and 19. The
Bloom paper's lane algorithm probes one hash per lane per iteration, gathers
32-bit words, permutes finished lanes to the tail and refills with a masked
load (pp. 2-4), 1.4 to 3.3 times scalar with the filter in cache (p. 5). The
correction to Item 25 is that the kernel cannot choose the hash: Spark's
`BloomFilterImpl` (`common/sketch`) fixes h1 as Murmur3 of the long, h2 as
Murmur3 seeded by h1, k from the bit and item counts, and the index as the
absolute value of `h1 + i * h2` modulo the bit size, with a 64-bit variant in
`BloomFilterImplV2` that multiplies h1 by `Integer.MAX_VALUE`; the word is the
index divided by sixty-four in an on-heap `long[]`. So the kernel needs Murmur3
in int lanes, a modulo by a per-filter runtime constant (a multiply-and-shift
pair in a literal slot for the int form; the 64-bit form needs a high multiply
the Vector API lacks and may have to decline), a gather from `long[]` by an int
index map whose intrinsification is a `PrintIntrinsics` check, and the
int-to-long lane mismatch of Item 1. With k fixed by the build side, the first
arm to measure is k unrolled gathers per lane group with a running conjunction
word and Item 25's lane-group skip, against the paper's permute-and-refill.

**E. Benchmark credibility, and the frequency licence** (Raasveldt, Holanda,
Gubner and Muehleisen, DBTest 2018; Gottschlag and Bellosa, 2019; Gottschlag,
Brantsch and Bellosa, 2020). Against Raasveldt's pitfalls the record covers
reproducibility, hot against cold, hot against warm, overly specific tuning
and disclosure; three are open. The engine-off control does not isolate what
the README says: `dev/varka_bench_surface.sh` sets both
`spark.sql.codegen.varka.enabled` and the Arrow cache serializer from the one
`varka` token, while the README says the third and fourth arms "differ only by
that flag" - the engine needs the Arrow cache to run at all, so the missing
arm is engine off with the Arrow cache on, which attributes the cache format's
share of the published ratio. Cache build time is measured by nothing and
should be named as excluded. And the driver never compares the arms' answers,
which is Item 18's checksum row. Gottschlag gives the licence mechanism the
record lacked: three per-core frequency levels on Intel server parts since
Skylake-SP, the middle one entered by heavy 256-bit or light 512-bit
instructions, the lowest by heavy 512-bit ones, where heavy means floating
point and integer multiply; the core drops throughput at once, the voltage
settles in up to half a millisecond, and the level is held for about two
milliseconds after the last qualifying instruction (2020, pp. 3, 6); a Xeon Gold
6130 ran 2.8, 2.4 and 1.9 GHz at the three levels, and scalar neighbours
slowed by a tenth with under one percent of time in AVX-512 code (2020, p. 3).
Neither paper measures AMD or anything after Skylake-SP, so the census
machines are outside their evidence. For this engine the shape is the bad one:
kernel calls of microseconds against a two-millisecond hold mean a Skylake
core never returns to the top level, and the calendar kernels are
multiply-heavy while the datapath probe uses only adds. Three effect-based
probes fit the existing survey job and need no root: a heavy multiply variant
of the datapath probe beside the light one; a tail probe timing the scalar
canary in short windows after a 512-bit burst; and a sibling probe with a
scalar control pinned to the other hyperthread. The Zen 5 is the negative
control, and Item 26 adds that HotSpot itself defaults to AVX2 on the Skylake
steppings these papers measured.

**Worth taking, as rows or amendments.**

1. Item 16 takes Ross's plan structure and VOILA's Q6 loss case; Item 15 takes
   batch bounds at cache-write time in Data Blocks' form; the positional table
   is a candidate narrowing form.
2. The surface benchmark gains a fifth arm, engine off with the Arrow cache on,
   and the README's "differ only by that flag" is corrected; cache build time
   is named as excluded.
3. The datapath probe gains a heavy multiply reading and the two licence
   probes, so the hardware section states a measurement rather than a name.
4. Milestone 5 measures the OR-the-flags overflow form against range-derived
   guards on long multiply, and reads Data Blocks' 64-bit result as the
   expectation to beat.
5. `compileInList` gains BLU's cheap bounds check ahead of the chain; the Bloom
   item carries Spark's recipe and its first arm.
6. A per-shape time-to-tier-4 ladder and a batches-below-C2 counter join the
   cold-start benchmark; Kohn's remaining-time rule is a decline rule for tiny
   tasks.


### Item 28. Gluten, the other Spark accelerator

Recorded on 16 September 2026, from a survey of `gluten-core`,
`gluten-substrait`, `backends-velox`, `gluten-ui`, `gluten-ut` and
`docs/developers` at commit `90186c196`, made at the owner's request. Gluten
was not in the record. Its kernels are Velox's (Item 18) or ClickHouse's (Item
23), read there; what it owns is the Spark side, and the comparison is with
Item 24's Comet: the same contract, a different set of answers to the planning
questions.

**Arrived at twice.** Fallback reasons are tags on plan nodes (`FallbackTags`),
collected by a reporter rule that logs each one, can fail the query on any
fallback under a test switch (`spark.gluten.sql.columnar.failOnFallback`), and
posts one event per query to a Gluten tab in the SQL UI carrying the counts of
native and fallen-back nodes and the node-to-reason map
(`GlutenFallbackReporter`, `GlutenSQLAppStatusListener`); a `Dataset` helper
returns the same summary programmatically (`GlutenImplicits.fallbackSummary`).
A per-expression blacklist (`spark.gluten.expression.blacklist`) is Item 24's
kill switch, and the support-progress tables are generated. Under `ANSI` the
default is the whole plan falling back - "Gluten currently doesn't support
ANSI mode", with an issue tracking it (`FallbackOnANSIMode`,
`velox-backend-limitations.md`) - the strongest form of Item 18's choice, made
by declining everything rather than the batch. Spark's own test suites run
against the engine as an in-repo module with a per-test exclusion list: for
Spark 3.4 on Velox, 285 suites enabled and 414 tests excluded, many of them
timestamp and cast cases (`gluten-ut`, `VeloxTestSettings`); that is the
widest differential Items 24 and 25 named, built as a module with its
exclusions written down, and the shape task 81's plan should compare itself
with. The default batch is 4096 rows (`spark.gluten.sql.columnar.maxBatchSize`),
one more data point for Item 14.

**Three things worth taking.**

1. **A fallback policy that counts transitions, with a threshold and a
   comparison.** `ExpandFallbackPolicy` walks a stage (under adaptive
   execution) or the whole query, counts every columnar-to-row transition and
   every vanilla leaf as a unit of cost, optionally ignores row-to-columnar,
   and if the count reaches a threshold (`wholeStage.fallback.threshold`,
   `query.fallback.threshold`, both off by default) reverts the stage or query
   to rows - but only after costing the reverted plan too, because a reverted
   stage may still need a transition to adapt to the previous columnar stage,
   and it keeps the native plan when the vanilla one would not have fewer
   transitions (`preferColumnar`). Item 24 noted Comet's stage revert; this is
   the version with the accounting written out, and its two subtleties - the
   cost of the plan you fall back to, and table caches counting as a hidden
   transition - are the ones a Varka pivot budget would otherwise rediscover.
   With it comes a second rule worth quoting for VISION: a project or filter
   whose nested expression count reaches fifty falls back to Spark
   "considering Spark codegen can bring better performance for such case"
   (`fallback.expressions.threshold`), an accelerator's own statement that a
   vectorised interpreter loses to whole-stage codegen on deep expression
   trees, which is exactly the regime a fused emitted loop is built for.
2. **The partial project.** `ColumnarPartialProjectExec` splits a project the
   backend cannot take whole into a native project plus a JVM island: only the
   input columns the unsupported expressions read are converted to rows, Spark's
   `UnsafeProjection` evaluates those expressions, the result columns are
   converted back and composed into the batch (`VeloxColumnarBatches.compose`),
   and the native project consumes them as ordinary columns. Its admission
   rules are the design constraints Item 16's per-node fallback needs written
   down: only user-defined or blacklisted expressions qualify, the number of
   columns converted to rows must be smaller than the project's own width, and
   the complex-expression threshold still applies (`doValidateInternal`). It is
   Comet's codegen dispatch (Item 24) at the operator level rather than the
   expression level, and the two together settle how a fused kernel takes a
   derived input.
3. **Transitions as a shortest path over conventions.** Every operator declares
   the batch convention it consumes and produces - vanilla, Velox, Arrow Java,
   Arrow native - and `TransitionGraph` runs Floyd-Warshall over the registered
   conversions with a cost model to insert the cheapest chain
   (`transition/TransitionGraph.scala`, `Convention.BatchType`). The costers are
   deliberately rough: a columnar-to-row or row-to-columnar transition ten, a
   columnar-to-columnar conversion five, a native operator ten, a vanilla
   project a hundred, any other vanilla operator a thousand, a row-to-columnar
   over complex types infinite, and a project of only cheap expressions costed
   like a native one "to reduce unnecessary c2r and r2c" (`LegacyCoster`,
   `RoughCoster`). Item 11 plans several physical representations per type;
   when a second Arrow form exists, the conversions between forms and the row
   boundary become exactly this graph, and the rough costs are a starting
   table until Items 13 and 14 supply measured ones.

**Noted for later.** The reporter copies each fallback reason onto the node's
logical link, because under adaptive execution the next stage's physical plan
is a new instance that does not carry the tag; this engine's decline reasons
in verbose `EXPLAIN` should be checked for the same loss under adaptive
execution. `FallbackMultiCodegens` leaves a chain of several wide joins to
Spark's whole-stage codegen on purpose, a second admission in the same
direction as the fifty-expression threshold. The metrics framework maps native
operator statistics onto Spark's metrics by treefying both plans in one order
(`MetricsFramework.md`); an in-JVM engine has no such boundary to cross.


### Item 29. StarRocks, an interpreter that grew an expression JIT

Recorded on 16 September 2026, from a survey of `be/src/exprs/jit`,
`be/src/exprs` (arithmetic, predicates, `case`, `time_functions`),
`be/src/base/simd/filter.cpp`, `be/src/column`, `be/src/storage_primitive`
(`column_predicate.h`, `column_in_predicate.cpp`), `be/src/types`
(`date_value.h`, `time_types`), the low-cardinality rules under
`fe/fe-core/.../optimizer/rule/tree/lowcardinality`, and the session-variable
documentation, at commit `a5dc21cf97d`, made at the owner's request. StarRocks
was not in the record. It is a ClickHouse-descended vectorised C++ engine that
added an LLVM expression JIT in its third major version, so it is the newest
instance of the path ClickHouse took (Item 23), and its admission policy for
what to compile is the thing to read.

**Arrived at twice.** Nulls are a byte per row with a `has_null` flag
(`NullableColumn`); chunks are 4096 rows (`vector_chunk_size`); a kernel sees
its arguments after a generic layer unfolds constants and wraps or unwraps
nullability (`JITExpr::evaluate_checked`); filters are selector bytes; storage
predicates evaluate into a byte selection with `evaluate_and` and
`evaluate_or`, or over a `uint16_t` index list in `evaluate_branchless`, so
both of Ngom's representations coexist at the storage layer
(`column_predicate.h`), beside zone maps, bitmap indexes and Bloom filters.
Dates decompose through a lookup table of year, month, day and week per day
for two hundred years from the Unix epoch, with arithmetic beyond it
(`to_date_with_cache`, `CACHE_JULIAN_DAYS`), which is Item 10's ClickHouse
table again; the calendar functions run per row over it, and there is no
calendar kernel to learn from. Dictionary predicates are rewritten onto codes
at the segment level with an always-true, always-false, changed or unchanged
verdict (`ColumnPredicateRewriter`), and the planner rewrites whole plans over
low-cardinality string columns onto their global dictionary codes bottom-up,
inserting a decode only where strings are needed and evaluating string
expressions once over the dictionary (`AddDecodeNodeForDictStringRule`,
`DecodeRewriter`, `DecodeCollector`), which is Items 16, 18 and 19 lifted from
the batch to the plan, with a benefit estimate per column. The JIT's LLVM
pipeline is O3 with the loop and SLP vectorisers added explicitly
(`jit_engine.cpp`), and its generated code is a per-row loop over values and
null-flag bytes: the fifth engine in this catalogue to leave lanes to the
auto-vectoriser.

**Three things worth taking.**

1. **Admission by benefit vote.** `jit_level` is a bit mask over expression
   classes - arithmetic, cast, case, comparison, logical, division, modulo -
   and its default, one, means adaptive (`expr_jit_types.h`,
   `System_variable.md`). Under adaptive admission every node of a compilable
   subtree returns a score and a count (`compute_jit_score`): most nodes vote
   one, literals vote nothing, a logical `AND` or `OR` counts itself but adds
   no benefit, and a `CASE` counts each `WHEN` and `THEN` branch as valid only
   if that branch's own ratio exceeds three tenths (`case_expr_tpl.hpp`); the
   subtree is compiled only if its score exceeds eighty-eight hundredths of its
   node count and it has more than two nodes (`expr_jit_rewriter.cpp`,
   `kExprJitScoreRatio`). It is an admission model by composition - compile
   when enough of the tree gains - where this engine admits a whole shape or
   declines it. The place it applies here is the boundary: a project entry
   whose only fused work is one cheap node may not repay the row conversion
   Items 13 and 14 measured, and a benefit score over the entry's nodes
   against a threshold is the cheap form of the "would add a pivot" rule
   Items 24 and 25 ask for, with the score weights coming from the emitter's
   op counts rather than a vote of one.
2. **A sparse guard on 64-bit compress, from a down-clocking machine.**
   `filter.cpp` compacts by scanning thirty-two selector bytes per step: an
   all-dropped step is skipped, an all-kept step is one `memmove`, and only a
   mixed step does work; for 4-byte lanes the mixed step is `vpcompressd`, but
   for 8-byte lanes the code says that `vpcompressq` "is a fixed four-group
   cost per batch and, on down-clocking Intel parts, loses to a plain scalar
   copy once only a few lanes survive", so below six set bits in thirty-two it
   copies the survivors one by one (`kCompressMinBits`), while the 4-byte path
   "always takes the vectorised path". Each width is a separate function with
   a target attribute, dispatched at run time - Item 26's gate as function
   multiversioning - and the AVX2 form for every width is the scan without any
   permute table. For milestone 5 this is the row to add before the 64-bit
   compaction is written: `SelectionVectorOps` is 32-bit today, and the 64-bit
   form needs the popcount guard measured on the Intel runners, where Item 27's
   licence effect and this comment point the same way.
3. **`IN` as a bitset over the literal span.** For integer types the storage
   layer has `BitsetInPredicate`: a dense bitset over the literals' minimum to
   maximum, tested with a range check, and a `contains_range` against a
   segment's zone map that prunes the segment when no literal falls inside it
   (`column_in_predicate.cpp`). Item 19 left the crossover from the sorted
   compare chain unmeasured and Item 25 named a gather probe and fingerprint
   buckets as its arms; this is a third arm for the case that matters most for
   dates, literals within a short span: subtract the minimum, range-check, and
   test one bit, which for a span of at most sixty-four values is a shift of a
   single constant word in lanes with no gather at all, and for longer spans a
   word gather from a small table. Its `contains_range` is also the batch
   bounds check of Item 27 for `IN`, stated for a bitset.

**Where it stops.** Semantics are MySQL's; there is no `ANSI` mode and nothing
about it transfers. The JIT covers arithmetic, casts, `CASE`, comparisons and
logic, not the calendar. Its one runtime contract check is worth a sentence:
when a child declared non-nullable produces nulls, the JIT path fails the
query with a message naming the switch to turn it off ("set jit_level = 0 to
disable jit and retry", `jit_expr.cpp`), and a compile failure falls back to
the interpreter with a warning. This engine declines the batch instead, and
the message that names the switch is the form Item 24's per-expression kill
switch should take when a kernel is disabled by hand.


### Item 30. The JDK's own Vector API microbenchmarks

Recorded on 16 September 2026, from `test/micro/org/openjdk/bench/jdk/incubator/vector`
in the JDK 25 tree at `/home/max/proj/openjdk-build/jdk25` (a single-commit
snapshot, so no file histories), with the correctness tests under
`test/jdk/jdk/incubator/vector` and `doc/testing.md` for how they run. Item 26
read the match rules for which operation is lowered to what; these are the
cases the people who write the intrinsics keep to measure them, and they are
the reference beside which this engine's own costs can be told apart from the
platform's. The snapshot holds thirty hand-written benchmark classes and no
generated per-species ones, and eighty-nine correctness test files. Two facts
about using them first: they need a JMH bundle the tree does not carry
(`configure --with-jmh`, `make/devkit/createJMHBundle.sh`), and they are GPL
with the Classpath exception, so they are run from the JDK tree and never
copied into this repository; a case worth keeping here is rewritten from its
call shape, not its source.

**The cases that are this engine's operations, by name.**

* `ColumnFilterBenchmark`: a column filter by `compare`, `compress` and
  `trueCount` into an output array, int and long lanes, one to four thousand
  rows - `SelectionVectorOps` in twelve lines - and its fork pins
  `-XX:UseAVX=2`. The JDK measures its own compress at the AVX2 lowering
  (Item 26's permutation stub), which is the number Item 19's gate needs and
  has an upstream case for.
* `MaskFromLongBenchmark` per species and `MaskQueryOperationsBenchmark`
  (`trueCount`, `firstTrue`, `lastTrue`, `toLong`), `StoreMaskTrueCount`: the
  guard's `fromLong` and the validity word's `toLong`, the conversions whose
  AVX2 and NEON lowerings Item 26 read.
* `MaskCastOperationsBenchmark`: a mask cast from int lanes to long lanes and
  back, the int-to-long lane mismatch Item 1 and the Bloom recipe of Item 27
  both meet.
* `MaskedLogicOpts`: fully masked lanewise operations against partially
  masked ones, which is predicated execution under AVX-512 against a blend
  everywhere else, the masked body's cost in one case.
* `GatherOperationsBenchmark`, masked and unmasked per width;
  `LoadMaskedIOOBEBenchmark`, `StoreMaskedIOOBEBenchmark`,
  `StoreMaskedBenchmark`: masked loads and stores that run past an array's
  end, the tail problem the emitter solves with slack.
* `MemorySegmentVectorAccess`: vector loads from native against heap segments,
  and five degrees of profile pollution when one call site sees several
  segment kinds; `TestLoadStoreBytes` and `TestLoadStoreShorts`: the same
  loads from a native segment under an automatic arena against a confined
  one. This engine reads Arrow buffers through native segments; how they are
  wrapped, and whether any shared helper takes both heap and native segments
  at one site, is a measurement these two cases make cheap.
* `VectorMultiplyOptBenchmark`: six patterns of a long-lane multiply whose
  operands are masked or shifted to thirty-two bits. The benchmark exists
  because C2 recognises them: `MulVLNode::has_uint_inputs` accepts an operand
  that is an `AND` with a constant at most `0xFFFFFFFF` or an unsigned right
  shift by at least thirty-two, and `has_int_inputs` accepts a widening cast
  from int lanes or a signed shift by at least thirty-two, and lowers the
  product to one `vpmuludq` or `vpmuldq` instead of the five-instruction
  emulation of Item 26 (`vectornode.cpp`, `x86.ad` `vmuludq_reg`,
  `vmuldq_reg`). That is the rule for milestone 5's `TIME` kernels: a product
  of an int part widened to long lanes and a constant that fits thirty-two
  bits is one instruction on every runner, if the emitter keeps the widening
  cast or the mask adjacent to the multiply; `VectorXXH3HashingBenchmark`
  uses the same split into low and high halves for its lane hash.
* `VectorCommutativeOperSharingBenchmark`: `a op b` and `b op a` for `ADD`,
  `MUL`, `AND` and `OR`, kept as a benchmark because C2 shares them; the
  emitter's common-subexpression pass need not canonicalise operand order for
  those four, and the benchmark is the check that it still holds.
* `VectorZeroExtend` (`convertShape` from int to long, zero-extended) and
  `IndexInRangeBenchmark` (`indexInRange` tail masks), `SelectFromBenchmark`
  (`selectFrom` against `rearrange` over two sources, a lookup of up to twice
  the lane count of entries, which is the small-table form of Item 29's bitset
  `IN` and of a dictionary of a few entries), `RearrangeBytesBenchmark` (byte
  shuffles at each width, Item 3), `SpiltReplicate` (broadcasts hoisted out
  of loops, the skill's own lesson), `VectorExtractBenchmark` (lane extraction
  and `laneIsSet` by constant and variable index, the cost of any scalar tail
  that reads lanes one at a time).

**Worth taking.**

1. **A reference table beside Item 26.** Run the cases above, at the widths
   the emitter uses and under `-XX:UseAVX=2`, on the Zen 5 and through the
   survey job on the runners, and keep the numbers as a results file. Where a
   Varka kernel's cost exceeds the sum of its operations' reference costs, the
   difference is the engine's; where it does not, the cost is the platform's
   and no emitter change will move it. This is the file the skills cite today
   from memory of individual measurements.
2. **The thirty-two-bit multiply rule for long lanes.** An emitter rule, not
   a measurement: when a long-lane multiply has an operand that is a widened
   int or a constant under thirty-two bits, emit the cast or the mask so C2's
   pattern matches, and assert `vpmuludq` or `vpmuldq` in the disassembly test.
   It removes the five-instruction emulation from every `TIME` kernel that
   scales a part, on the AVX2 runners too.
3. **Two segment measurements**, from the two segment cases: the arena kind
   under which Arrow buffers are wrapped, and whether any helper's call site
   sees both heap and native segments, which the pollution cases price at up
   to five kinds.

**Noted for later.** The correctness tests under `test/jdk/jdk/incubator/vector`
are generated per species and cover `compress`, `expand`, the masked loads and
stores and the conversions with the JDK's own oracle; when a Vector API
operation behaves unexpectedly on a runner, they are the first thing to run
there, before any Varka suite.

### Item 31. The timestamp types on the long lane

*Moved out of milestone 5 on 17 September 2026, on the owner's decision while
reading `PLAN_TASK_29.md`: "I didn't plan to support TIMESTAMP_NTZ during this
milestone." Milestone 5's row 29 had carried both timestamp types since a 15
September widening that was the assistant's, not the owner's.*

`TimestampType` and `TimestampNTZType` are `PhysicalLongType` - micros since the
epoch in the same eight-byte lane as `bigint`, `TIME` and day-time intervals -
so once task 29 lands, admitting either is one arm in the compiler's
`laneOf`, one vector class in `isArrowBacked` (`TimeStampMicroTZVector`,
`TimeStampMicroVector`) and one destination in `allocateVector`. The plumbing
is not the argument. The semantics are, and the review of task 29's first plan
found them, read in `datetimeExpressions.scala` and `DateTimeUtils.scala` on 17
September 2026:

* **On a zoned `TIMESTAMP`, only comparisons are zone-independent.**
  `SubtractTimestamps` evaluates `ChronoUnit.MICROS.between(localStart,
  localEnd)` on the two instants' local date-times in the session zone
  (`DateTimeUtils.subtractTimestamps`), and `TimestampAddInterval` evaluates
  `.atZone(zoneId).plusDays(days).plus(micros)` (`timestampAddDayTime`) -
  calendar days in that zone. Across a DST transition neither equals the instant
  arithmetic a long kernel would do, so a kernel that computed them would be
  wrong by an hour on the rows that cross it and right on every other row. Any
  future admission of `TIMESTAMP` beyond comparisons needs a test whose rows
  straddle both transitions of a year under a DST zone, because on any other
  rows a wrongly admitted kernel agrees with Spark.
* **The `TIMESTAMP_NTZ` family is evaluated in UTC** (`zoneIdForType`), so its
  differences and interval additions are plain long arithmetic; and both go
  through `LocalDateTime.until` and `instantToMicros`, whose exact arithmetic
  raises on overflow whatever the ANSI setting, so the checked mode is the only
  correct lowering and there is no mode to choose.
* **Decomposition** - `year(ts)` and the calendar fields through
  `floorDiv(micros, 86 400 000 000)` into the int32 civil-from-days prefix - is
  the argument `PLAN_MILESTONE_5.md` section 8 already kept from 15 September:
  the first kernel where a long lane feeds the calendar machinery, over a value
  range that forces the full-range exact division rather than `TIME`'s bounded
  one.
* **Not this item:** the nanosecond timestamps `TimestampNTZNanosType` and
  `TimestampLTZNanosType`, whose physical type is a sixteen-byte
  `TimestampNanosVal` stored by `ArrowWriter` as a `StructVector`. They are not
  one lane of anything and would be their own item.

Re-enters with: `TIMESTAMP_NTZ` comparisons, differences and interval addition
first, since their semantics are settled; zoned `TIMESTAMP` comparisons beside
them; zoned arithmetic only with the DST-straddling test above.

### Item 32. The bitwise and shift operators

*Opened 18 September 2026, from a coverage audit of what Varka does not
vectorize and why. The audit's other findings all named a blocker - an output
representation, a mixed width, an admission rule. These have none.*

`&`, `|`, `^`, `~`, `bit_count` and `bit_get` are the whole of
`bitwiseExpressions.scala`, and `shiftleft`, `shiftright` and
`shiftrightunsigned` sit apart from them in `mathExpressions.scala` - a division
of the source that cost this item a first draft, which scoped the audit to the
one file and missed the three. Varka admits none of the nine. They are unusual in
this catalogue for having no reason not to: the operands and the result are the
same integral lane the engine already owns, the Vector API declares every
operator natively - `AND`, `OR` and `XOR` as `Associative`, `NOT` and
`BIT_COUNT` as `Unary` - and **the emitter already emits `AND`, `XOR` and `LSHR`
today**, inside the calendar lowerings, through the same `lanewise` descriptors
an expression arm would use. The kernel side is largely built; what is missing is
IR nodes and compiler arms.

They also arrive at both widths at once, which is new. `BitwiseNot` is
`child.dataType` and the three binary ones inherit their operands', so an
`int` and a `bigint` column take the same arm at the lane each already has - the
first family since the long lane landed where covering `int` covers `bigint` for
free.

**Three groups, not six, and the split is the usual one.**

* **`&`, `|`, `^`, `~` and the three shifts - nothing in the way.** Same lane in
  and out - `BitShiftOperation` declares `dataType = left.dataType`, so a shift
  follows its operand's width exactly as the binary bitwise ops follow theirs -
  one lanewise op each, null-intolerant like every other binary node. This is the
  cheapest coverage in the audit.

  The shifts have one wrinkle the others do not: the emitter's `emitShift` takes
  a **constant** shift amount, which is all the calendar lowerings ever needed. A
  literal shift is therefore free, and a shift by a *column* needs the
  vector-operand form of `lanewise`, which the Vector API has and the emitter has
  never emitted. Worth splitting on that line rather than treating the three as
  one shape.
* **`bit_count` - clean at int32, mixed width at int64.** It returns
  `IntegerType` whatever it is given, so `bit_count(i)` is same-lane and
  `bit_count(l)` is int64 in, int32 out - the narrowing shape item 28's task
  owns in milestone 5, and the same shape as the `TIME` field extracts. The int32
  form need not wait for it.
* **`bit_get` - blocked on a representation.** It returns `ByteType`, and Varka
  has neither a byte lane nor an Arrow vector for one. This is the same blocker
  as `extract(MONTH FROM ym)`, which milestone 5 records as matched only to
  decline; the two should be lifted together, by whatever admits a narrow
  integral output, and neither is worth lifting alone.

**Two more the audit turned up beside them**, both marginal and recorded so the
next reader need not re-derive them: `floor(i)` and `ceil(i)` over an integral
return `LongType`, so they are the *widening* identity - correct, vectorizable
once item 28's conversion exists, and worth almost nothing; and `factorial(i)` is
a twenty-one entry lookup, which `selectFrom` serves natively. Neither earns work
of its own; both are free riders on machinery built for something else.

**Why it is worth a row at all**, given none of these is a headline function: the
audit that found them was looking for mechanism failures and found that Varka's
gaps are otherwise all representations, widths and admission rules. A family with
no blocker at all is the cheapest breadth available, and `bit_count` over a
`bigint` doubles as a second caller for the narrowing that the `TIME` extracts
would otherwise be the only user of - which is worth having before that lowering
is designed around one caller.

### Item 33. The integral division shapes nothing rows

*Opened 18 September 2026, from the same coverage audit as item 32, after the
owner asked what else it had found without a row.*

Three arithmetic shapes over the integral lanes are vectorizable and belong to no
task. They are separated from item 32 because each has a reason to be thought
about, where the bitwise family had none.

**`pmod`.** Milestone 5's task 95 owns `i % 20` - "an int remainder at all" - and
names only `%`. `pmod` is the same family with a different sign rule: Java's `%`
takes the dividend's sign, `pmod` the divisor's, which is one masked add over the
remainder. Whoever builds `%` should build it, and the two should not be
discovered separately a second time.

**`div` (`IntegralDivide`).** Int32 in, **int64 out**, so it is a *widening*
kernel - the mirror of the `TIME` field extracts, which narrow. Milestone 5's
section 2.39 covers `div` over `bigint` and says it takes 2.19's rule, exact
under a proven bound and declined otherwise; what nothing covers is the int32
form, whose output is wider than its input and which therefore waits on
milestone 5's task 28 rather than on a bound. It is worth having as a second
caller for that widening, the way item 32's `bit_count(l)` is a second caller for
the narrowing.

**A note on what this is not.** `/` over two ints returns a **double** in Spark,
so it is item 3's business and not this one's; recording that here saves the next
reader the same lookup.

### Item 34. Null-safe equality

*Opened 18 September 2026, from the coverage audit; the one gap it found that is
a question about Varka's own design rather than about a type or a width.*

`a <=> b` is `EqualNullSafe`, and Varka admits every other comparison. It is not
admitted, and the reason is structural rather than incidental: **every binary
node in the IR is null-intolerant**. The result is known exactly where both
operands are valid and unknown elsewhere, and the validity word is the AND of the
operands' - a rule the emitter, the compiler and the reference evaluator all
share, and which `<=>` breaks by design, since it is *true* when both sides are
null and *false* when exactly one is.

The lowering itself is not the difficulty. The validity words are already in the
kernel; `a <=> b` is the comparison's mask, narrowed to the lanes where both are
valid, OR'd with the lanes where neither is. What needs deciding is whether that
becomes a second kind of node - a null-*tolerant* binary, with its own rule
everywhere the null-intolerant one is assumed - or whether the existing machinery
can express it without a second rule for every reader to learn.

That is a design question about the null model, which is why it is a scope item
and not a task: it should be answered before it is built, and the answer is worth
more than the expression.

### Item 35. The double lane, and the math family it unlocks

*Opened 19 September 2026, from `SCOPE_FUNCTIONS.md`.*

Sixty-one of Spark's 511 registered functions are the math family, and every
one of them waits on the same thing: a `LaneType.DOUBLE`. Today a double lane
exists only as a conversion target inside one node's lowering (task 88). With
the lane, 23 functions are one Vector API operator each - `sin cos tan asin
acos atan sinh cosh tanh exp expm1 log log10 log1p cbrt sqrt pow atan2 hypot`
and the sign and absolute-value pair - and a dozen more are composites of those
(`log2`, the reciprocal trig, `degrees`, `radians`, the inverse hyperbolics
Spark writes as log-and-sqrt).

Two things the lane does not give. **Rounding**: `VectorOperators` has no
`FLOOR`, `CEIL`, `RINT` or `ROUND`, so `floor`, `ceil`, `rint`, `round` and
`bround` are built from the 2^52 trick or a `D2L` round trip, and Spark's
`floor`/`ceil` return `LONG`, so the conversion is the result. **Division by a
column**: `mod`, `pmod`, `div` with a non-constant divisor have no magic and take
the double route as `a - trunc(a / b) * b`, a new lowering.

Sequencing follows task 28's lane-as-a-property-of-the-node work, since a
double lane is the third lane and the first whose values are not integers.

### Item 36. The math family needs a ULP contract, an emitted fdlibm, or a decline - on every host

*Opened 19 September 2026, from `SCOPE_FUNCTIONS.md` section 3; re-read the
same day on both CI runner architectures, after the first reading proved to be
the scalar fallback measuring itself.*

`dev/varka_canary/MathLaneProbe.java`, run with the library actually reached -
the operator a compile-time constant, every operator bound before warm-up, a
forced-fallback control pass beside it - finds that **no math operator
reproduces the row engine's bits on any host**. Against the library Spark
calls, SVML at AVX-512, SVML at AVX2 and SLEEF on NEON each differ on up to
thirteen percent of ordinary inputs, by one ULP, two for `log10` everywhere and
`tanh` on x86; and the three library builds disagree with one another, so there
is no bit pattern to promise even within x86. The two exact operators - `pow`
at AVX2, `tanh` on NEON - are exact because the JDK has no symbol for them and
runs the scalar call per lane, at scalar speed. `SCOPE_FUNCTIONS.md` section 3
has the tables; the outputs are committed beside the probe.

The options, none free, now apply to the whole family rather than to six
functions. A **ULP contract**: each ported function states in the coverage
table "within 1 ulp of `java.lang.Math`" or "within 2 ulp of `StrictMath`" -
SLEEF's `_u10` tier, the standard-mode register's place for a deliberate
deviation - with a stated answer for the ghost fallback serving one query
partly from each library, which is the mix a cluster of x86 and aarch64
executors already produces for `sin`, since HotSpot's `Math.sin` differs
between the two. A **Varka-emitted fdlibm** for `exp expm1 log log10 log1p
pow`, the six whose row-engine bits are the same on every host: `exp` and `log`
are a table and a short polynomial, and a lane that reproduces fdlibm's
arithmetic reproduces its bits, at a cost against SVML to be measured. Or a
**decline** of the family. The speed at stake is width-bound - 5x to 14x at
eight lanes, 2.5x to 9x at four, 1.1x to 3.9x at two, so on NEON only `exp`,
`log10`, `expm1`, `log1p`, `pow` and `atan2` clear 2x - which is an input to
the decision, not a way round it. This is a decision about the contract before
it is a task, which is why it is a scope item.

### Item 37. A third AVX2 lowering for the 64-bit divide, from 32-bit converts

*Opened 19 September 2026, from reading SLEEF for
`sql/varka/skills/vector-api-and-width.md`.*

Task 88 step 3 gave the long-lane division two forms: the native `L2D`/`D2L`
conversions where they intrinsify, and the `0x4330000000000000` identity where
they do not (AVX2). SLEEF's `vtruncate2_vd_vd` shows a third: build the 64-bit
conversion from **32-bit** converts, which AVX2 has - split the value into
halves, `cvtdq2pd` each, combine with one FMA, and back the same way. Exact to
2^53 rather than the identity's 2^52, and no exponent bit in the argument.

**How.** A third arm behind `useAVX` at the long lane, its own admission row in
`verify_double_division.py` (the FMA changes the rounding count, which the
script's bound assumes is one per operation), and the A/B on task 121's AVX2
runner beside the identity form. **Done when** the AVX2 default is chosen from
a committed file rather than from the identity being the one that was written
first.

### Item 38. Onboarding: the task tables as issues, a hardware census, and templates

*Opened 21 September 2026, from the first "can we contribute?" under the
public post, and deferred to this milestone by the owner's decision: milestone
5 stays as it is.*

A newcomer arriving from the post finds a contributing guide (`CONTRIBUTING.md`,
#284) and, behind it, a task table inside a plan file that is a record rather
than a front door. Three low-cost pieces would give the project a first rung.

1. **The task tables mirrored as GitHub issues.** Issues are now enabled on the
   fork (they are off on a fork by default; the owner switched them on). A
   script, `dev/varka_issues.py`, reads the current milestone's task table,
   opens one issue per row marked Scoped or Planned - `[Task <n>] <title>`,
   the row's deliverables and validation as the body, a link to the plan file
   - and closes the issue when the row turns Done, quoting the row's outcome.
   The table stays the source of truth; the issues are the view GitHub shows.
   Labels: `task`, `milestone-<n>`, and `good first issue` from a short
   hand-kept list of at most five rows a newcomer can finish in a day. The
   status vocabulary of milestone 5's table is not uniform (Scoped, Planned,
   Done, DONE, Withdrawn, Moved, Partly done, landed, and eight rows with no
   marker), so the script's first job is to state the rule it applies and
   list the rows it cannot classify.
2. **A hardware census page and its issue template.** `dev/varka_datapath.sh`
   prints a machine's CPU model, vector flags, the JVM's `UseAVX` and
   `MaxVectorSize`, and the datapath readings at three widths. A `HARDWARE.md`
   table seeded with the laptop and the runner census of `PLAN_TASK_62.md`
   section 11, plus an "add my machine" issue template that asks for that
   script's output, makes a first contribution that needs no build and fills
   the AVX2 and Arm gaps the committed tables have.
3. **Templates.** An issue template for taking a task (which row, the plan
   file, the acceptance line), and a `PLAN_TASK_TEMPLATE.md` with the sections
   the house rules expect - the question, the change, predictions before the
   run, the outcome - so a first plan file has the shape without reading ten
   examples.

*Pieces 2 and 3 shipped on 21 September 2026, on the owner's instruction:
`sql/varka/HARDWARE.md` with the "add my machine" template, the "take a task"
template, `PLAN_TASK_TEMPLATE.md`, and `sql/varka/WALKTHROUGH.md` (one
expression from SQL to assembly). Piece 1, the issue sync, is what remains for
this milestone.*

Set aside with reasons: a chat channel or mailing list before there are three
regular contributors, and any contributor agreement beyond the license
affirmation `CONTRIBUTING.md` already carries. **Done when** every open row
of the milestone in flight is an issue, closing a row closes its issue on the
next sync, and `HARDWARE.md` has at least one machine that is not the owner's.

### Item 39. Moved from milestone 5 on 21 September 2026

Planning milestone 5's closing task (`PLAN_TASK_118.md`) read the milestone's
exit against its open rows: the message about the 64-bit lane and `TIME`, the
three-type support claim as the coverage table states it, and the full-width
number. Twenty-eight open rows had no bearing on those and moved here, on the 15
September precedent (item 15): text and task numbers unchanged, each design
section still where it was in `PLAN_MILESTONE_5.md` with a note under its
heading, so every citation resolves. Twelve of them were this catalogue's own
items 16 to 29, scoped into milestone 5 on 16 September, and simply return.
None is ordered against this milestone's spine (section 5); each re-enters
with its own argument, and the reason it left is the start of that argument.

| task | what it is | `PLAN_MILESTONE_5.md` | why it left milestone 5 |
| ---: | :--- | :--- | :--- |
| 28 | Lane-width conversion | 2.2 | the widening cast; nothing in the message needs it, and task 104 that would have leaned on it moves too |
| 30 | ANSI integer arithmetic | 2.4 | the int32 arithmetic remainder (`/`, `div`, `%`); the 64-bit half is task 104 and moves with it |
| 39 | `date - date` | 2.5 | absorbed by task 103 and moves with it |
| 81 | Spark's own date tests as a differential corpus | 2.11 | the date lane's differential corpus; a newcomer's task through the task template |
| 83 | One refusal, instead of four | 2.14 | an engine refactor on the int32 refusal paths; not the lane the milestone ships |
| 86 | One operand admission, stated once | 2.17 | an int32 admission refactor; not the lane the milestone ships |
| 89 | The year-month interval divisions | 2.20 | the year-month divisions beyond `extract(YEAR FROM ym)`, which landed; int32 date-lane work |
| 91 | A guard bound the shift above it chooses | 2.22 | an int32 guard-bound tuning |
| 92 | The validity write, keyed on the bit layout | 2.23 | an int-lane validity option, measured and defaulting off |
| 95 | Two int32 shapes decline that a reader would expect to fuse | 2.30 | two int32 date shapes; the date lane is milestone 4's |
| 96 | `make_ym_interval` takes only arguments derived from a date | 2.31 | a year-month interval argument rule on the date lane |
| 103 | Day-time interval expressions, absorbing task 39 | 2.38 | day-time interval arithmetic; the milestone claims comparisons, selections and the interval as an operand of `TIME` arithmetic, as the coverage table states them |
| 104 | `Long` arithmetic, task 30's int64 half | 2.39 | bigint arithmetic; the milestone claims comparisons and selections over bigint, as the coverage table states them |
| 127 | The 64-bit operations table on AVX2 | 2.62 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 128 | 64-bit compaction with a sparse guard | 2.63 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 129 | Selectivity policy re-measured at 64-bit lanes | 2.64 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 130 | Narrowing, built four ways | 2.65 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 131 | The batch-size sweep | 2.66 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 132 | Kernel time split from conversion time, and a pivot budget | 2.67 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 133 | The frequency-licence probes | 2.68 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 135 | The backward interval pass | 2.70 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 136 | The preimage rewrite | 2.71 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 137 | Batch bounds at cache-write time, and a bounds check ahead of `IN` | 2.72 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 138 | Three test forms | 2.73 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 139 | A per-expression switch, and the tier ladder | 2.74 | one of the thirteen rows scoped on 16 September from this catalogue's own items 16 to 29; it returns to where it came from |
| 148 | The group budget under-counts an int-lane division sevenfold | 2.84 | an emitter budget finding on the int lane; recorded, not the message |
| 157 | The widening store: a decimal output is sixteen bytes a row | 2.93 | the decimal store; the two decimal declines are named in the coverage table, which is the honest form for the message |
| 163 | The emitted three-field int kernel trails its hand-written twin by 10% to 20% while cache-resident | 2.99 | an emitter performance finding on the int lane; recorded, not the message |

Two of them change what the milestone 5 message may claim, and the plan of its
closing task says so in as many words: without 104 the `bigint` claim is
"comparisons and selections", and without 103 the day-time interval claim is
"comparisons, selections and the interval as an operand of `TIME` arithmetic".
Both are the first rows to re-enter when the long lane's arithmetic is taken up.

### Item 40. The emitter's shared constants out of the facade

*Opened 22 September 2026, from the retrospective on task 159's refactor, on
the owner's instruction; items 40 to 47 come from the same review.*

After the split, `VarkaVectorWalk`, `VarkaBodyEmitter` and the two lowering
classes reach the word sentinels (`WORD_ALL_TRUE`, `WORD_DEAD`) and the limits
(`MAX_CHAIN_DEPTH`, `MAX_FUSED_NODES`, `MAX_INPUTS`) through a static import of
`VarkaLoopEmitter`, so the dependency graph has cycles that hide who needs
what: a lowering imports the facade to get a constant. The limits belong in
`VarkaEmitBudget` with the budgets they bound; the word sentinels belong with
`loadWord` and `storeWord` in the walk, which is the one path every word goes
through. **Done when** no class under `codegen/varka` static-imports
`VarkaLoopEmitter`, the facade imports the pieces and not the reverse, and
the bytes oracle is unchanged.

### Item 41. A disjointness test for the compiler's family chain

`VarkaExpressionCompiler.compileNode` chains the four family partial functions
(task 159 step 3.4) and the chain is order-safe because every arm is gated by
the expression class or its data type, so no expression matches arms of two
families. That is an argument, not a test, and a fifth family or a widened
guard would break it silently: the first family in the chain would win. A
test that runs every expression of the coverage table and of the compiler
suite's corpus through each family's `arms(...).isDefinedAt`, plus the leaf,
arithmetic and fallback groups the compiler keeps, and asserts that exactly one
answers, turns the argument into a fact that stays true. **Done when** the
test exists in the compiler suite and fails when an arm is duplicated across
two families on purpose.

### Item 42. Port `VarkaIntervalCompiler` to Java, the first family port

The compiler split leaves four family objects, and the project's direction is
Java (`sql/varka/CLAUDE.md`). `VarkaIntervalCompiler` is the smallest, about
250 lines and self-contained: the year-month interval leaves, casts and
algebra. It is the right first port because it exercises the translation that
every later port repeats - a Scala `match` with guards over Catalyst
expressions into a Java `switch` with pattern matching over the same classes -
on something a reviewer reads in ten minutes, and its oracle is complete: the
compiler suite, the coverage suite and the bytes oracle. The dispatch stays a
partial function on the Scala side until the last family moves. **Done when**
the object is a Java class with the same arms and the same decline reasons,
the Scala file is deleted, and the three oracles are unchanged.

### Item 43. Javadoc position, checked

The refactor found three doc comments attached to the wrong method, each
because Java attaches a `/** */` block to the next declaration and two stacked
blocks raise no warning: `guardedWord`'s sat on `isDayOffsetShape`,
`guardScratch`'s on `reachesGuardedDay`, `emitDoubleDivide`'s on
`takesMagicDivide`. Checkstyle's `InvalidJavadocPosition` reports exactly this.
**Done when** the check is on in `dev/checkstyle.xml`, whatever it finds in
the Varka sources is fixed, and `dev/lint-java` passes.

### Item 44. A CI queue script

The fork runs at most twenty jobs at once, so the standing rule is one Build
run per PR at a time, in merge order. Today that rule is kept by hand and by
scratch scripts: one that cancels a new PR's automatic run, one that reruns the
held runs one after another when the previous run completes, one that prints
which PR is ready to merge. They belong in the repository as
`dev/varka_ci_queue.sh`: `hold <pr>` cancels the run and records it, `run`
reruns the held runs in order, each starting when the previous has completed
(polling the run's status, never `gh run watch`, which returns at once without
a terminal), and `status` prints each open PR with its run's state and whether
the run's head is the PR's. Every wait keys on a completed status, and the
script exits through a trap on every path. **Done when** the script exists,
`CONTRIBUTING.md` names it, and the scratch scripts are gone.

### Item 45. A scoped CI path for oracle-proven refactors

A byte-identical move inside the emitter costs the full matrix, about fifty
minutes and 1283 job-minutes, because a catalyst Java file changed, and the
one-at-a-time rule made task 159's PRs a serial chain across a day. The bytes
oracle, the shape hashes and the coverage table already prove that such a PR
changes nothing an emitted class does. A `[REFACTOR]` tag in the title, or a
label, could send the PR down the scoped path plus the oracle suites and the
Varka `sql/core` suites, provided master runs the full matrix after the merge,
which is the condition to verify first: a scoped PR run is only safe if the
matrix still runs somewhere before a release. The trade-off is recorded either
way. **Done when** the precondition is checked and written down, and either
the tag exists and `dev/varka_scope.py` honours it, or the item says why not.

### Item 46. The refactoring tools under `dev/`

Task 159 was done with three scratch scripts that any later refactor or port
wants: a member map (every top-level member of a Java or Scala file with its
line range, doc comment included, and what it calls), a call graph between
named groups of members (which is how each seam's crossings were enumerated
before cutting), and an unused-import stripper driven by scalac's own
`-Wunused:imports` errors, since Spark's build makes those errors and a moved
file inherits every import of its source. They belong under `dev/` with a
README that says when to use each; a plan's member list is generated from the
map rather than written from memory, which is how task 159's plan came to name
an `emitBoundedDivide` that never existed. **Done when** the three scripts are
committed with usage in the README, and `PLAN_TASK_TEMPLATE.md` points to the
member map for a refactor's inventory.

### Item 47. One place per node, deferred

Task 159's step 3.7 proposed each IR node's emitter knowledge - its children,
its word rule, its value emission, its range rule - in one class behind a
sealed interface, with the thirteen switches over the IR becoming one dispatch.
It was not built, on the owner's constraint against abstractions written for
types that do not exist yet: after the split, a new node already has one
obvious place per family, and the sealed IR makes the compiler refuse a switch
that misses it. The step reopens when a real second case arrives - a second
lane type, or a second physical representation of one logical type (item 11) -
so that the abstraction is written against two concrete cases rather than
none. **Done when** it is either built against that second case or this item
records why it was not needed even then.

Task 28, the lane-width conversion, is already in item 39's table; the
retrospective adds a third reason to take it first when the long lane's
arithmetic re-enters: `time_from_seconds(i)` declines, `hour(t) + 1` declines,
and the coverage differential had to change a row because of it.

### Item 48. What `validityOrFirst` is for

Task 167's option audit emitted the oracle's whole shape set - ninety-two
coverage rows and the fuzz blocks, at both widths - under both values of every
emit option, and `validityOrFirst` is the one field that moved no hash
anywhere. Every other option moved something, including the two whose subject
only one half of the set reaches. An option that changes no emitted byte over
a corpus that size is either dead code or a switch guarding a shape the corpus
does not contain, and the audit cannot tell those apart: it reports the count,
not the reason. **Done when** the field's subject is named - the shape that
would distinguish its two values, added to the oracle's set, or the field
removed with the tests that set it - so that the oracle's silence about it
means something.
### Item 49. The two band measurements milestone 5 did not take

Milestone row 90 closed on its census and its decision, and left two
measurements behind because both need a quiet machine rather than an argument.

The first is the **arithmetic benchmark's band**. `VarkaArithmeticBenchmark`
has committed results at both widths and no band file, so a regeneration of it
is still read against a flat threshold rather than against its tiers.

*Correction, 23 September 2026: an earlier draft of this item called it "the
last family in that state", which was read off the census of the five families
that had bands rather than off the benchmark directory. Counted properly there
are eighteen Varka benchmark families and six now carry a band -
`VarkaEmitterParityBenchmark` and `VarkaThroughputBenchmark` at two widths
each, the date surface, the date chains, the `TIME` surface, and
`VarkaFilterNarrowingBenchmark`, which task 145 measured because it needed one.
Twelve do not, `TimeChain`, `VarkaArithmeticBenchmark`, `VarkaFilterBenchmark`,
`VarkaNarrowingBenchmark`, `VarkaTimeBenchmark` and
`VarkaLongLaneThroughputBenchmark` among them.*

The arithmetic benchmark is still the one to take first, because it is the one
whose regenerations get compared. The general shape of the work is what task
145 did in passing: a family gets its band the first time someone needs to read
a move in it, which is cheaper than banding eighteen families against a day
that may never come.

The second is **`PLAN_TASK_63.md` 9.7's 26.1% dead-local attribution**, taken
from two *unpinned* regenerations at a time when the worst case on that file
moved 75%. The mechanism is plausible - the emitted bytes did change, and a
dead local does change register pressure - but 26.1% sits at the very top of
the band later measured for the same file, so the magnitude is not evidence.
Section 2.12 of milestone 5 narrowed task 82 to "a 128-bit task" on the
strength of it, which is the concrete thing a wrong number would have cost.

**Done when** the arithmetic band is committed beside its results, and 9.7's
figure has been re-taken pinned and either confirmed, corrected, or withdrawn
with task 82's scope re-read against whatever replaces it. The other eleven
families are not part of this item: each gets its band when a move in it has to
be read.
### Item 50. `TIME +/- INTERVAL`: the semantics Varka's guard waits on

*Moved from milestone 5 on 23 September 2026 (row 146, `PLAN_MILESTONE_5.md`
2.82, text unchanged there).*

Vanilla Spark's `timeAddInterval` adds exactly and throws when the result
leaves `[0, 24h)`. A lane cannot throw, so `PLAN_TASK_102.md` 4.1 lowers
`t + dt` as a range guard that fails the whole batch into the ghost fallback,
where the row engine raises the identical error on the identical row. That is
correct and it costs a compare per batch plus, on a batch that really crosses
midnight, the whole batch on the row engine.

[SPARK-57853](https://issues.apache.org/jira/browse/SPARK-57853) asks whether
ANSI's modulo-24 replaces the throw. If it does, Varka's lowering becomes a
`floorMod` by `NANOS_PER_DAY` with no guard, no decline channel and no batch
ever falling back - strictly cheaper and strictly simpler than what ships
today.

**What changed since the row was scoped.** It was scoped on the reading that
the ticket carried no patch. It does: `apache/spark#57044`,
"[SPARK-57853][SQL] Use ANSI modulo-24 semantics for TIME +/- INTERVAL", open
since 6 July 2026, 153 lines added and 68 removed over eleven files -
`DateTimeUtils.timeAddInterval`, `TryEval`, the error class,
`TimeExpressionsSuite`, `DateTimeUtilsSuite` and the `TIME` golden files, which
is the ticket's own acceptance list. Its author asked this repository's owner
to review it on 20 July and nothing has moved since. The ticket itself is still
Open and unassigned, last touched 14 July.

So the work here is a review rather than a patch, and it is upstream work that
happens to unblock a Varka simplification. **Done when** the ticket has a
resolution Varka can lower against - a merged patch or a recorded decision to
keep the throw - and `PLAN_TASK_102.md` 4.1 says which, with its guard deleted
or kept accordingly. Size: small in Varka, and unbounded upstream, which is
why nothing in Varka blocks on it; 102's guard is built to be easy to delete.

### Item 51. A mapping type per IR node, before the non-element-wise operators

*Added on 24 September 2026, from `READING_MILESTONE_6.md` section 3.*

Every operator Varka fuses today is element-wise - one input lane to one output
lane - so any two of them fuse legally and profitably, and the emitter has never
had to ask. That ends with the operators this catalogue plans: aggregates
(Items 4 to 6), a hash-based `IN` (Item 3), strings. TVM classifies operators as
injective, reduction, complex-out-fusable and opaque; DNNFusion by mapping type,
as one-to-one, one-to-many, many-to-many, reorganize or shuffle, with a table of
which pairs fuse profitably, which do not, and which need a measurement.

The proposal is that each IR node carries its mapping type from the day the
first non-element-wise node is added, and that fusion across a many-to-one or
an opaque node (a UDF - XLA's custom-call) is a kernel boundary by rule, with
DNNFusion's "needs profiling" pairs measured before they are allowed. **Done
when** the first non-element-wise node lands with its type and the rule. Size:
small, if it is done then rather than retrofitted.

### Item 52. Velox's adaptive filter order

*Added on 24 September 2026, from `READING_MILESTONE_6.md` section 2.*

Velox orders conjunctive filters at run time by a score per filter,
time / (1 + values in - values out), so the filter that drops the most values
in the least time runs first (Pedreira et al., PVLDB 15(12), section 4.5.1). It
is one concrete rule for what Items 16 and 20 leave open - which conjunct of a
fused filter runs first, and whether the order may change per batch - and it is
cheap to compute from counts Varka's filter node already has. **Done when**
Items 16 and 20 either adopt it with a measurement or record why not. Size:
small.

### Item 53. Varka as a plugin for stock Spark

*Added on 24 September 2026, from the review of spark-vector.*

Varka ships as a fork of Spark, so trying it means building that fork.
spark-vector, a Java Vector API engine of about the same age, ships as a jar
for stock Spark 4.1.3 and turns on with `spark.plugins` and two JVM options.
For the readers the posts bring, "add a jar" and "build our Spark" are very
different asks. The item is an inventory before any design: every place
Varka's filter and projection path touches the fork rather than a public
extension point (the columnar rule, the Arrow cache serializer, the
`VARKA_ENABLED` setting, the exec nodes, the codegen hooks), and for each,
whether `SparkSessionExtensions`, a `SparkPlugin` or a cache serializer
setting already carries it on stock Spark. **Done when** the inventory says
which of them a plugin build could carry and what the rest would cost - a
patch upstream, or a feature the plugin gives up. Size: small for the
inventory; the build it leads to is its own item.

### Item 54. An expression support table for users

*Added on 24 September 2026, from the review of spark-vector.*

Comet publishes one row per Spark expression: whether it runs natively, for
which types, and the exact reason it falls back where it does. spark-vector's
`docs/expressions.md` copies that format. Varka has the facts - the fusion
report, the coverage table (task 120) and each lowering's refusal reasons -
but they are written for this project, not for a reader deciding whether
Varka covers their query. The item is that table, generated from the coverage
data rather than written by hand, so it cannot fall behind the lowerings.
**Done when** the table is committed and a check fails when a lowering is
added or removed without it. Size: small.

### Item 55. Int values as the branches of `if`, `coalesce` and `greatest`

*Added on 25 September 2026, found while probing task 172's kernels with
`dev/varka_emit.sh`.*

`if(c, 1, 0)` and `if(c, i, i + 1)` over an int column `i` decline, the first
as "unsupported expression" and the second as "non-date column of type int",
although the int literal is already a `LiteralSlot` on the same 32-bit lane and
the int column already a kernel input. The value leaves of `compileNode` are a
date column and a date literal; ints reach a kernel only as operands, of the
int arithmetic arms and of a comparison (task 122). That is deliberate, and
`PLAN_TASK_122.md` 3.2 says why: `compileNode` also compiles date operands, and
the IR's lanes carry no type, so an int leaf there would admit
`date_add(d, i)`'s offset as a date. So the item is not to widen that leaf but
to give the value positions that take any type of their own a typed arm: the
branches of `if`/`CASE WHEN`, the operands of `coalesce`/`nvl`, and those of
`greatest`/`least`, each admitting an int column or literal when the
expression's own type is `IntegerType`, and a date one when it is `DateType`,
never mixing the two. One rule stays as it is: a whole output that reads no
column, such as a bare constant, is still refused, because the all-null
shortcut and the forwarding of bare columns assume every output reads one.
**Done when** those shapes fuse over int columns with a differential check
against the row engine, `date_add(d, i)` over an int `i` still declines, and
the coverage table lists them. Size: small.

### Item 56. JDK 27, tried at run time

*Added on 25 September 2026, from a reading of JEP 537 against the record.*

JEP 537 (Vector API, twelfth incubator) re-incubates the API in JDK 27
"without API change"; its one change is the bundled SLEEF, 3.6.1 to 3.9.0,
which serves the math intrinsics on AArch64 and RISC-V only. Every machine
this project measures on is x86, where those intrinsics are SVML's, and
`vector-api-and-width.md` already read SLEEF 3.9. So the JEP brings Varka
nothing, and the incubator flag stays. What JDK 27 carries beside it, read
from the JDK bug tracker with fix version 27, is three things:

* **Inlining deferred at the node-count cutoff** (JDK-8382700). The mechanism
  in `emitter-and-ir.md` under "a refused call is refused by the caller's
  budget": a kernel whose Vector API intrinsics parse to about
  `NodeCountInliningCutoff` nodes has its last call in program order refused,
  which is why the emitter writes the validity OR first (`validityOrFirst`,
  item 48). JDK 27 can defer such a call and inline it later instead of
  refusing it. It shipped switched off: `DelayAfterInliningCutoff` is a
  diagnostic flag, false by default, disabled again in JDK-8384948 after C2
  ran out of memory and footprint regressed on ordinary workloads. A lever
  for an experiment, then, not for a release.
* **Mask-cast chains folded** (JDK-8370863): chains of `VectorLoadMask`,
  `VectorMaskCast` and `VectorStoreMask`, the nodes `VectorMask.fromLong` and
  `toLong` lower to, which every validity word in Varka's kernels goes
  through. Whether any instruction leaves Varka's loops is a measurement.
* **A correctness fix JDK 25 lacks** (JDK-8388492): since JDK 20, C2 could
  drop a scalar store that a masked vector store follows, and the fix reached
  no release below 27. Varka's masked `intoMemorySegment` stores and its
  scalar validity writes address different segments, so the shape is not
  expected in its kernels; it is named here so that a wrong lane on JDK 25
  is checked against it before anything else.

Nothing in 27 touches the limits the record names most: C2 still does not
unroll a Vector API loop, mask registers are allocated as before, and the
8000-byte method limit stands. JDK-8380195, the bimodal-performance report,
stays closed as alignment, which `PLAN_TASK_32.md` 11 already tested and
refuted for this project's buffers.

The item is a run-time trial, not a build change: Spark's CI builds on 17, 21
and 25, Scala 2.13.18's compatibility table stops at JDK 26, and class files
compiled for release 25 run on 27, which is all the trial needs. JDK 27 GA
builds are on jdk.java.net, and Adoptium lists 27, so `setup-java` has it.

1. **A fourth rung of the demo's JDK ladder.** `varka-demo.yml` runs the
   method-size cliff on stock Spark 4.2.0 under 17, 21 and 25; add 27 and
   commit `method_size_cliff-jdk27-output.txt` beside the others. If the
   stock distribution does not start on 27, that is the finding.
2. **The engine's JMH benchmarks and the emitter parity file on JDK 27**, in
   a quiet window, twice: as shipped, and with
   `-XX:+UnlockDiagnosticVMOptions -XX:+DelayAfterInliningCutoff`. The
   results files already carry a `-jdk25` suffix, so the `-jdk27` ones sit
   beside them; `dev/varka_bench_regen.sh` reads the JDK for its provenance
   line but writes the suffix as a constant, so it takes the suffix from the
   running JDK first. The flagged run is the point of the item: it says
   whether the refused-call effect the emitter's ordering works around
   disappears when C2 defers instead of refusing, in the parity file's masked
   rows, which are where task 46 saw it.
3. The generated-code comparison with `dev/varka_emit.sh --asm`, only if step
   2 moves a row, because that script runs through sbt and so needs the build
   itself on 27, which step 2 does not.

The expectation, written before the run: no headline change, since the x86
intrinsics are the same, and a measured answer on the deferred inlining
either way. **Done when** the three results files are committed with the JDK
in their provenance and this section records what the flagged run did to the
parity file's refused-call rows. Size: small, measured.

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

Item 15's fourteen tasks are also absent from the table, deliberately: each
arrived with the reason it left milestone 5 rather than with an argument for a
place here, and the two that already have one - 98, which is item 13's row
boundary by another name, and 64, which task 104's divisions would need - are
scheduled when those arguments are made, not before.

Items 13 and 14 sit beside the table rather than in it: each opens with an
admission check (a JMH pair, a profile and a batch-size sweep) that costs a
day and needs nothing from the spine, and each bounds every number the spine
can publish - item 14 the columnar relatives, item 13 the row-consumer ones.
The checks belong before item 8's first regenerated file, so that file is
measured against a floor the project has already looked at.

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
6. **What are the two floors made of?** Items 13 and 14 each open with a
   measurement because the record has none: the 25 ns/row of the row
   consumer has never been split into accessor, projection and iterator
   shares, and the 2.5 ns/row of the columnar consumer has never been split
   into decode, allocation, sink and kernel. Until those two splits are
   committed, every relative in the throughput file is a statement about
   the pipeline around the kernels, and should be read as one.
7. **Where would the SQL standard buy a faster kernel than Spark's semantics?**
   Opened 18 September 2026 and kept in `SCOPE_STANDARD_MODE.md` rather than
   here, because the register accumulates across milestones as tasks meet the
   cases. Varka's row answers follow vanilla Spark and that does not change;
   what the standard offers is narrower *ranges*, which is what Varka's
   lowerings want - the int-lane magic is exact only over a bounded dividend,
   and a bound the standard would declare turns a seven-op double-lane division
   into a two-op magic, and turns task 103's recorded interval declines into
   kernels. The register also records why this need not change any answer: the
   per-batch `VarkaInputBound` check already exists, so the cheaper lowering can
   be guarded and a non-conforming batch declines to the row engine. Item 64's
   statistics-directed bounds are the principled source for such a bound, which
   is why the two belong together.
