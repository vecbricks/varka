# Varka

Varka is a research fork of [Apache Spark](https://spark.apache.org/) exploring
SIMD-vectorized execution for SQL: eligible projections are compiled into a
single fused vector loop - bytecode emitted at runtime with the JDK 25
Class-File API, running the Vector API over zero-copy views of Arrow columnar
buffers - behind one config flag, falling back to stock Spark per batch on
anything the engine cannot serve. A Varka failure never fails a query.

The current scope is date arithmetic (`date_add`, `date_sub`, `datediff`,
`CASE WHEN`/`IF` over date comparisons, `greatest`/`least`,
`dayofweek`/`weekday`) over Arrow-cached data - deep enough to exercise real
fusion, small enough to measure honestly.

## Main ideas

Each one line, with the details in [`docs/sql-varka.md`](docs/sql-varka.md)
and the architecture in [`sql/varka/VISION.md`](sql/varka/VISION.md):

* **Generate the loop, not a call to it**: every projection becomes its own
  emitted class, so call sites stay monomorphic where a shared interpreter
  goes megamorphic.
* **Zero-copy Arrow morsels**: Arrow buffers are mapped to Panama
  `MemorySegment`s; no per-row heap objects on the fast path.
* **Whole-projection fusion**: one loop, one load per input column, one store
  per output - with common subtrees computed once per lane group *across*
  outputs (DAG-CSE in vector registers).
* **Predication, not branches**: `CASE WHEN` runs by `VectorMask.blend` with
  SQL's three-valued null logic in mask algebra, so data-dependent conditions
  cost the same as predictable ones.
* **Partial eligibility**: fusable entries fuse, untouched columns are
  forwarded zero-copy, the rest runs the stock row path and merges.
* **Ghost fallback**: the Janino projection is compiled lazily, only if a
  batch actually needs it; any Varka failure degrades to stock Spark.
* **Per-task class loading**: emitted classes unload with the task - proven
  Metaspace reclamation instead of a growing codegen cache.
* **Telemetry baked into the bytes**: every emitted class carries a
  `SourceFile` naming its operator and stage plus a `VarkaDebugInfo`
  attribute with its IR and plan fragment, so profilers and heap dumps name
  the plan node with no mapping table.

## Benchmarks

Committed results from `sql/core/benchmarks/` and `sql/catalyst/benchmarks/`
(AMD Ryzen AI 9 HX PRO 370, JDK 25, Linux, 2M Arrow-cached rows unless noted;
best of >= 5 iterations over 2s windows, August 2026). The honest rows are in
the table too - this fork commits its losses:

| Case | vs stock Spark (Janino) |
| :--- | :--- |
| `date_add` / `datediff`, columnar consumer | 3.8x / 5.6x |
| Nested `datediff(date_add(d, 1), d2)` | 5.7x |
| Two outputs sharing a subchain (DAG-CSE) | 5.7x |
| `CASE WHEN`, unpredictable condition | 7.1x |
| `CASE WHEN`, predictable condition | 5.8x |
| `CASE WHEN d IN (...)`, 5 / 16 literals | 3.5x / 4.0x (fused up to the 16-literal cap; longer lists decline with a reason) |
| Chain of 8 date ops, columnar consumer | 7.5x - flat from depth 1 to 8 since the task 18 class cache (was 1.3x, eroding) |
| Same chains through a row consumer | 0.8x - the ~25 ns/row read-back floor; heavy shapes clear it instead (`dayofweek` 1.2x, `CASE WHEN` 1.1x through rows), task 19's recorded decision |
| `dayofweek` | 9.8x - was 0.9x before the magic-multiply mod-7 lowering and the class cache (see the docs) |
| Cold start: first run of a fresh plan shape (100K rows) | 1.7x (a fresh shape misses the class cache by design) |
| Emit+define+load+instantiate a fused kernel vs one Janino compile | 68x cheaper (~130 us vs ~9 ms) |

Regenerate with `SPARK_GENERATE_BENCHMARK_FILES=1`:

```bash
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaThroughputBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaColdStartBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaCodegenBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaInExpressionBenchmark"
build/sbt "catalyst/test:runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
```

## Quick start

Build (JDK 25 required), then enable the engine and the Arrow cache:

```bash
./build/mvn -DskipTests clean package
./bin/spark-shell \
  --conf spark.sql.codegen.varka.enabled=true \
  --conf spark.sql.cache.serializer=org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer
```

```scala
spark.sql("select date_add(date'2020-01-01', cast(id as int) % 1000) as d from range(2000000)")
  .createOrReplaceTempView("t")
spark.catalog.cacheTable("t")
val q = spark.sql("select datediff(date_add(d, 1), d) from t")
q.collect()
// The fused node and its metric:
println(q.queryExecution.executedPlan.treeString)   // VarkaColumnarToRowExec (varka: ...)
```

A fused plan shows a `Varka*Exec` node whose `numVarkaBatches` metric counts
the batches the kernels actually served; anything else fell back to stock
Spark and stayed correct.

## Status and roadmap

Development happens in `sql/varka/plans/`, one plan file per milestone and
task, each with a recorded outcome:

* **Milestone 1 (done)**: the MVP - per-op SIMD kernels over Arrow-cached
  dates, the columnar rule and both exec nodes, ghost fallback, per-task
  class unloading.
* **Milestone 2 (done)**: the fused vector loop - expression IR and
  Class-File emitter, nested chains with DAG-CSE, predication, partial
  eligibility with zero-copy forwarding, telemetry attributes, and the
  benchmark/docs pass that produced the numbers above.
* **Milestone 3 (in progress)**: *reach* - the task plan is in
  [`sql/varka/plans/PLAN_MILESTONE_3.md`](sql/varka/plans/PLAN_MILESTONE_3.md).
  Its spine: reuse the emitted class across tasks (done - task 18's shape
  cache removed the per-task JIT warm-up and moved every committed
  end-to-end number above), fuse date *filters* rather than only
  projections (where a corpus survey found 53-78% of real date references
  live), lower `IN` lists and `Coalesce` onto the mask algebra - Spark's own
  benchmark puts `IN` over dates at 31.2 M rows/s, its slowest primitive
  (done - task 20 fuses `IN` in condition position at 3.5-4.0x up to a
  16-literal cap, with `coalesce` and `IS [NOT] NULL` riding the new
  validity condition), and
  `coalesce` is the corpus' third most common non-aggregate function - and
  answer the whole-stage charter question in writing. The row-consumer
  question above is settled (task 19: the rule keeps fusing - heavy shapes
  win through rows and no plan-time number separates them from the cheap
  chains that do not).
* **Milestone 4**: *breadth* - the scope catalogue is in
  [`sql/varka/plans/SCOPE_MILESTONE_4.md`](sql/varka/plans/SCOPE_MILESTONE_4.md):
  the types, expressions and operators the engine cannot say yet. `year` and
  the extraction family, int64 lanes for `TimestampNTZ`, boolean outputs,
  ANSI-correct integer arithmetic, float lanes, and the first horizontal
  reduction. It is organised around what the unused half of the Vector API
  makes possible - plus one item that adds no vocabulary at all and asks
  instead how many independent chains the emitted loop should carry, since a
  superscalar core has vector ports that a single dependency chain leaves idle.
* **Milestone 5**: *coverage* - the scope catalogue is in
  [`sql/varka/plans/SCOPE_MILESTONE_5.md`](sql/varka/plans/SCOPE_MILESTONE_5.md),
  driven by a census of TPC-DS, TPC-H and the New York taxi benchmark. What that
  census says: `DateType`, the only type Varka has today, is 3.1% of the columns
  in TPC-DS and TPC-H; `DECIMAL` is the most-aggregated type and strings are 60%
  of grouping keys; and 122 of 125 queries end in an aggregate. So the milestone
  is decimals, strings as keys, grouped aggregation, and benchmarks that publish
  the number - extending three of Spark's own rather than only writing more of
  ours.

Docs map: [`docs/sql-varka.md`](docs/sql-varka.md) (user-facing guide),
[`sql/varka/VISION.md`](sql/varka/VISION.md) (architecture),
[`SKILLS.md`](SKILLS.md) (measured lessons the project keeps).

## About Apache Spark

This is a research fork of [Apache Spark](https://spark.apache.org/) and is
not affiliated with or endorsed by the Apache Software Foundation. Everything
outside the Varka additions is upstream Spark; see the
[upstream repository](https://github.com/apache/spark) for Spark itself, its
[documentation](https://spark.apache.org/documentation.html) and
[contribution guide](https://spark.apache.org/contributing.html). Licensed
under the [Apache License 2.0](LICENSE), like Spark itself.
