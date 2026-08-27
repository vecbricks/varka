# Varka Milestone 5 Scope: coverage

Milestone 3 is *reach* - make the fast path apply to real queries. Milestone 4
is *breadth* - widen the vocabulary of types and expressions. Milestone 5 is
**coverage**: pick named queries from benchmarks people actually cite, find
every feature standing between Varka and running them, and publish the number.

The distinction matters because breadth is measured in expressions and coverage
is measured in queries. A milestone can add nine expression families and still
accelerate zero benchmark queries, if the tenth thing every one of them needs is
missing. This file exists to find that tenth thing before milestone 4 is built
rather than after.

Section 1 is the survey milestone 4's section 5 asked for and could not assume.
It changes milestone 4's ordering, and section 2 says how - honestly, because
the project scores its predictions.

Task numbering continues the single sequence. Milestone 4 resumes it at 23, so
this milestone's numbers are not assignable until milestone 4's task plan is
written. No task numbers are assigned here.

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
item 8), `rank` (its item 9), and `round`, which its item 3 excluded as
scale-dependent. Two more are out of charter in any milestone: `exists` is
subquery machinery and `grouping` is a grouping-set expansion above the
aggregate. `coalesce` was the seventh until this census found it - it was in no
milestone at all, so it moved into milestone 3's task 20 alongside `In`.

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
  `SCOPE_MILESTONE_4.md` item 11 puts `DecimalType` out of scope: "not a lane
  type ... needs its own design pass rather than an item". The survey says it is
  the single most-aggregated type in both benchmarks. The judgement was right -
  it does need its own design pass - and section 3 below is that pass starting.
* **Milestone 4's item 6 calibration holds.** It predicted extraction is "one
  function wide" because TPC-DS pre-materialises calendar parts; the count is
  `year(` 3, `month(` 0, `quarter(` 0, `dayofweek(` 0. Correct. But taxi uses
  `year()` in two of four queries, over timestamps, so the function survives on
  a different corpus than the one that motivated it.
* **Milestone 4's item 8 is two items, not one.** It bundles string *functions*
  (`upper`, `substr`, `LIKE`) with hashing and dictionaries. The survey says the
  functions are rare (`substr(` 23, `upper(` 2, `LIKE` 8) and the *keys* are
  everywhere (275 references). String equality and string grouping should be
  split out and pulled forward; string functions can stay where they are.
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
  caveat milestone 4's item 10 records.
* **The high words are not free.** For precision <= 18 they are sign extension
  by construction, but "by construction" means "if the writer respected the
  precision". One vector compare of the high word against `low >> 63` per lane
  group validates it, and a mismatch is a fall-back, not a wrong answer.

Which of the three costs least is a measurement, and it is the first thing this
milestone should measure - the entire decimal case rests on the de-interleave
being cheap relative to the arithmetic it enables.

**Vector API it needs**: `LongVector`, `VectorShuffle` with `rearrange` or the
two-vector `selectFrom`, and optionally `compress` - all currently unused, and
`rearrange`/`selectFrom` are milestone 4's item 8 territory pulled forward for a
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
rotate and bit ops for hashing (`ROL`, `XOR`, `MUL`) - milestone 4's item 8
list, minus everything that needs variable-length control flow.

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

The terminal operator matters as much as the source. `.noop()` is a row
consumer, so an Arrow-cached variant that keeps `.noop()` would measure the
0.6-0.7x read-back band milestone 3's task 19 exists to settle, not the fused
loop. A Varka variant needs a columnar source *and* a columnar terminal - an
aggregate or a count.

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
* **Window functions** (9 TPC-DS queries, `rank(` 15): milestone 4's item 9.
  Nothing here changes its priority.

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

## 6. Explicitly out of milestone 5

* Joins, sorting, grouping sets, and window functions - per item 9.
* Decimal *division*, and any decimal whose result precision exceeds the lane -
  declined with a reason, not computed wrongly.
* Decimal precision above 18: the 128-bit case has no lane at any species, and
  neither benchmark needs it.
* The Arrow-native Parquet reader itself, per item 7.

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
