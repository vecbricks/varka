# Varka

Varka is a research fork of [Apache Spark](https://spark.apache.org/) exploring
SIMD-vectorized execution for SQL: eligible projections are compiled into a
single fused vector loop - bytecode emitted at runtime with the JDK 25
Class-File API, running the Vector API over zero-copy views of Arrow columnar
buffers - behind one config flag, falling back to stock Spark per batch on
anything the engine cannot serve. A Varka failure never fails a query.

The current scope is date arithmetic (`date_add`, `date_sub`, `datediff`,
`CASE WHEN`/`IF` over date comparisons, `greatest`/`least`,
`dayofweek`/`weekday`, and since task 26 the calendar extractions
`year`/`month`/`dayofmonth`/`quarter`) and, since task 21, date filters
(`BETWEEN`, `IN`, `IS [NOT] NULL` and their `AND`/`OR` combinations as mask
kernels) over
Arrow-cached data - deep enough to exercise real fusion, small enough to
measure honestly.

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
* **Filters as mask kernels**: a predicate's fusable conjuncts compile to one
  loop whose output is a selection bitmap (null-as-false by construction);
  the batch is compacted by it, or its rows skipped at the row boundary.
* **Ghost fallback**: the Janino projection is compiled lazily, only if a
  batch actually needs it; any Varka failure degrades to stock Spark.
* **Per-task class loading**: emitted classes unload with the task - proven
  Metaspace reclamation instead of a growing codegen cache.
* **Telemetry baked into the bytes**: every emitted class carries a
  `SourceFile` naming its operator and stage plus a `VarkaDebugInfo`
  attribute with its IR and plan fragment, so profilers and heap dumps name
  the plan node with no mapping table.

## Benchmarks

Two questions, and they have different answers. **How much faster is a query
that does real arithmetic?** About **10x**. **How much faster is a single date
call?** Up to **39x** - but most of that is Spark's per-row overhead rather than
vectorised arithmetic, and the honest way to read the two numbers is below the
tables.

Every figure here comes from a committed file under
[`sql/varka/bench/benchmarks/`](sql/varka/bench/benchmarks), each carrying its
own CPU, JDK, kernel, row count, cache residency and vector-datapath probe, so
a claim can be checked without rerunning anything. Both runs compare four
distributions on identical data: stock Spark 4.2.0 on JDK 17, stock 4.2.0 on
JDK 25, this fork with the engine **off**, and this fork with the engine **on**.
The engine-off column is the control that separates "Varka" from "a fork of
Spark that happens to be newer".

### Chained expressions, on a verified 512-bit machine

`DateChain-*-results.txt`. Twelve expressions three and four operations deep,
mixing all three types Varka covers in one int32 lane - DATE, INT and the
year-month interval - over 200M Arrow-cached rows, one partition, table fully
resident. AMD EPYC 9V45, JDK 25, datapath probe **2.01** (a genuine 512-bit
unit, not a double-pumped one), worst per-row fixed cost 3.5% of wall time.

| Expression | ns/row | vs stock 4.2 (JDK 25) |
| :--- | ---: | ---: |
| `datediff(add_months(last_day(date_add(d, i)), i) + ymy, last_day(d + ym))` | 7.4 | **14.2x** |
| `weekofyear(add_months(last_day(d + ymm), i) + ymy)` | 10.1 | **13.2x** |
| `quarter(next_day(add_months(last_day(date_add(d, i)), i) + ymy, 'MONDAY'))` | 7.5 | **11.7x** |
| `dayofweek(add_months(last_day(date_add(d, i)), i) + ymy)` | 7.2 | **11.3x** |
| `make_ym_interval(year(add_months(last_day(date_add(d, i)), i) + ymy), month(d + ymm) + i)` | 9.6 | **10.9x** |
| `year(add_months(last_day(date_add(d, i)), i) + ymy)` | 7.5 | **9.4x** |
| all twelve | 6.9 - 11.2 | **9.4x - 14.2x**, median 10.2x |

Against the fork with the engine off the same twelve read 9.5x to 14.2x, so the
speedup is the engine and not the fork.

### The date surface: one entry per supported expression

`DateSurface-*-results.txt`. Every date expression the engine covers, written
the way a reader would write it, over 1B Arrow-cached rows on an AMD Ryzen AI 9
HX PRO 370 (JDK 25). This is the coverage document, and it commits the losses.

| Case | vs stock 4.2 (JDK 25) |
| :--- | ---: |
| `dayofweek(d)`, projection | 38.8x |
| `weekofyear(d)`, projection | 26.0x |
| `date_add(d, 3)`, projection | 21.8x |
| `year(d)`, projection | 18.4x |
| `last_day(d)`, projection | 15.7x |
| `add_months(d, i)`, projection - the heaviest single call | 11.6x |
| `WHERE d BETWEEN ... AND ...`, columnar consumer | 7.6x |
| `WHERE year(d) = 2020`, columnar consumer | 6.9x |
| `WHERE d IN (3 literals)`, columnar consumer | 6.3x |
| `WHERE d < d2 AND month(d) = 6`, counted | 3.5x |
| `WHERE d IS NULL`, counted | 3.1x |
| `WHERE d < d2`, columnar consumer | **0.59x** |
| `WHERE d < d2`, counted | **0.56x** |
| `WHERE d IS NOT NULL`, counted | **0.46x** |

32 projection rows span 11.6x to 38.8x with a median of 21.6x; 18 filter rows
span 0.46x to 14.0x.

**The three losses are one bug with one cause**, and it is not the kernel. A
predicate over two columns forwards both, so a `SELECT d` above it is a genuine
narrowing projection - and a projection of bare forwarded columns fuses
nothing, so the rule declines it and leaves a row-based operator on top, which
drags the discarded column across the row boundary. One-column predicates never
hit it, because Spark's own column pruning removes the redundant projection
before any of this runs. The fix (task 78) is written and turns the shape into a
1.3x - 2.2x win on the development machine; these committed rows predate it and
stay until this table is regenerated, which has to happen on a machine that can
hold a billion rows - see the note on where the surface can run.

One row is quoted against the engine-off column instead of stock:
`trunc(d, 'QUARTER')` reads 32.6x against stock 4.2.0 but **23.7x** against this
fork's own row engine, because the fork tracks Spark master and its `truncDate`
is 38% faster than 4.2.0's. That difference is upstream Spark's, not Varka's.
It is the only row of fifty where the two baselines disagree by more than 20%.

### Why 10x and 39x are both true

Stock Spark's cost per row is *overhead plus arithmetic*; Varka's is
*arithmetic divided by lanes*. So the ratio between them depends entirely on
how much arithmetic there is:

* On `dayofweek(d)` - one call, a few instructions - almost all of stock's 23 ns
  is per-row machinery, and removing it reads as 39x.
* On a four-deep chain, that machinery is amortised across real work, and what
  is left is the arithmetic speedup: **about 10x**.

Both numbers are real; they measure different things. **10x is what to expect on
your own workload**, and it is the figure to carry away. Anyone quoting 39x
should say that it is a single call on cached columnar data.

The surface also understates the engine in the other direction: about a third of
its entries move four bytes in and four out per row and are limited by memory
bandwidth, not by the vector unit - `date_add(d, 3)` runs at 0.6 ns/row, roughly
single-core DRAM speed. No width of datapath moves those, which is why the
chains exist as a separate list.

**Where each table can be measured, which is why they are on different machines.**
A benchmark row is only worth reading if the job's constant cost - scheduling a
task and collecting its result - is small against the work being timed, and this
project fails a run whose constant exceeds 5% of any Varka row's wall time. On
the development laptop that constant is about 15 ms, so a 0.5 ns/row entry needs
roughly 500M rows to clear it and a billion is comfortable. On a cloud CI runner
it is about 36 ms, and the same entry would need **1.4 billion rows - some 33 GiB
of cached data on a 15 GiB machine**. So the surface, whose lightest third is
that fast, cannot be measured honestly on a CI runner at all, and stays a
development-machine table. The chains can, because they do enough arithmetic per
row to need only 200M.

### What the 512-bit datapath is worth

Less than the lane count suggests. Comparing the same chains on a 256-bit
machine and the 512-bit one, Varka gains 2.07x - but the three scalar arms gain
1.78x, 1.78x and 1.84x on the same pair of machines, which is the machine
generation and not the vector width. Subtracting that leaves roughly **1.14x**
attributable to the wider datapath: these kernels are limited by the dependency
chains between their operations rather than by how many vector operations issue
per cycle. A same-machine confirmation at `MaxVectorSize` 32 against 64 is
future work.

### Reproducing it

The benchmark is a standalone jar that runs on **any** Spark 4.x distribution -
it depends on no part of this fork, so the same jar measures the fork and the
releases it is compared against. From a clean checkout with JDK 17 and JDK 25
installed:

```bash
# Quieter logs, as the CI run does it.
cp conf/log4j2.properties.template conf/log4j2.properties
sed -i 's/rootLogger.level = info/rootLogger.level = warn/g' conf/log4j2.properties

# The assembly, so this checkout's own bin/spark-submit runs it as a distribution.
./build/sbt -Pscala-2.13 -Phive -Phive-thriftserver package

# The benchmark driver, compiled against the *released* Spark at provided scope,
# and the engine jar. The engine jar is not optional - see the warning below.
./build/mvn -f sql/varka/bench/pom.xml -DskipTests package
./build/mvn -f sql/varka/engine/pom.xml -DskipTests package

# A stock release to compare against.
curl -sSLO https://archive.apache.org/dist/spark/spark-4.2.0/spark-4.2.0-bin-hadoop3.tgz
tar xf spark-4.2.0-bin-hadoop3.tgz

# All four distributions, one after another, into sql/varka/bench/benchmarks/.
# Each argument is LABEL=SPARK_HOME:JAVA_HOME, with a trailing :varka switching
# the engine on - so the third and fourth differ only by that flag.
J17=/usr/lib/jvm/java-17-openjdk-amd64
J25=/usr/lib/jvm/java-25-openjdk-amd64
dev/varka_bench_surface.sh --benchmark chains --rows 200000000 \
  spark-4.2.0-jdk17=$PWD/spark-4.2.0-bin-hadoop3:$J17 \
  spark-4.2.0-jdk25=$PWD/spark-4.2.0-bin-hadoop3:$J25 \
  varka-off-jdk25=$PWD:$J25 \
  varka-jdk25=$PWD:$J25:varka
```

**Do not skip the engine jar.** A distribution with Varka switched on and no
engine jar falls back on every batch, logs a `ClassNotFoundException` and then
measures the *row* engine under the kernel's name - a wrong number rather than a
failure. `dev/varka_bench_surface.sh` guards against exactly this: it fails the run
if *any* batch of a row expected to fuse fell back to the row engine - not merely if
all of them did, because a partial decline publishes a rate blended from kernel and
row-engine batches, which looks like a kernel rate and is not one.

`--benchmark surface` runs the coverage list instead. The script refuses a run
whose cached table did not stay in memory or whose per-row fixed cost exceeds 5%
of wall time, so a result that survives is one worth reading; it records the
datapath probe and the machine in every file it writes. Sizes are for a 16 GiB
machine - lower `--rows` if the table will not fit, and the script will tell you
if it did not.

Ratios between two files, and the tables above:

```bash
dev/varka_bench_diff.py sql/varka/bench/benchmarks/DateChain-spark-4.2.0-jdk25-results.txt \
                        sql/varka/bench/benchmarks/DateChain-varka-jdk25-results.txt
```

### Micro-benchmarks

Narrower questions - codegen cost, cold start, `CASE WHEN` predictability, the
read-back floor - live in `sql/core/benchmarks/` and `sql/catalyst/benchmarks/`
(same machine, 2M rows, best of >= 5 iterations over 2s windows):

| Case | vs stock Spark (Janino) |
| :--- | :--- |
| `CASE WHEN`, unpredictable / predictable condition | 7.0x / 5.8x - predication costs the same either way |
| `CASE WHEN d IN (...)`, 5 / 16 literals | 3.5x / 3.9x (fused to a 16-literal cap; longer lists decline with a reason) |
| Two outputs sharing a subchain (DAG-CSE) | 6.0x |
| Chain of 8 date ops, columnar consumer | 6.9x - flat from depth 1 to 8 since the task 18 class cache |
| The same chains through a **row** consumer | 0.8x - the ~25 ns/row read-back floor; heavy shapes clear it |
| `COUNT(*)` over an 85%-selective filter | 0.8x - nearly every row crosses the floor (1.8x at 15%) |
| Cold start: first run of a fresh plan shape | 1.8x - a fresh shape misses the class cache by design |
| Emit + load + instantiate a kernel vs one Janino compile | 66x cheaper (~99 us vs ~6.5 ms) |

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
  (done - task 20 fuses `IN` in condition position at 3.5-3.9x up to a
  16-literal cap, with `coalesce` and `IS [NOT] NULL` riding the new
  validity condition), and
  `coalesce` is the corpus' third most common non-aggregate function - and
  answer the whole-stage charter question in writing. The row-consumer
  question above is settled (task 19: the rule keeps fusing - heavy shapes
  win through rows and no plan-time number separates them from the cheap
  chains that do not).
* **Milestone 4**: *breadth* - the task plan is in
  [`sql/varka/plans/PLAN_MILESTONE_4.md`](sql/varka/plans/PLAN_MILESTONE_4.md)
  (tasks 24-31): the types, expressions and loop schedules the engine cannot
  say yet. The scalar tail is gone already - task 24 replaced it with a masked
  epilogue and took the filter's compaction to `compress(mask)` with it -
  leaving `year` and the extraction family, boolean outputs, lane-width
  conversion, int64 lanes for `TimestampNTZ`, and ANSI-correct integer
  arithmetic - of which the int32 half has since shipped as task 63, checked
  where the operands' own ranges cannot rule overflow out and unchecked where
  they can - plus two tasks that add no vocabulary at all: one asking how
  many independent chains the emitted loop should carry, since a superscalar
  core has vector ports that a single dependency chain leaves idle, and one
  asserting the *instructions* the kernels compile to rather than inferring
  vectorization from a throughput ratio. Float lanes wait for the taxi
  target, and aggregation leads the follow-on ladder.
* **Milestone 5**: *the other lanes* - the task plan is
  [`sql/varka/plans/PLAN_MILESTONE_5.md`](sql/varka/plans/PLAN_MILESTONE_5.md):
  the tasks milestone 4 planned for every lane but the date's int32, moved out
  when milestone 4 was re-scoped to the date family and the emitter under it -
  boolean outputs, lane-width conversion, int64 lanes (`TimestampNTZ`,
  `bigint`), the rest of ANSI integer arithmetic (`/`, `div`, `%` and the
  int64 forms; the int32 add, subtract, multiply and negate came back to
  milestone 4 as task 63), `date - date`, and civil-from-days in long lanes.
* **Milestone 6**: *coverage* - the scope catalogue is in
  [`sql/varka/plans/SCOPE_MILESTONE_6.md`](sql/varka/plans/SCOPE_MILESTONE_6.md),
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
