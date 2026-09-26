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
and the architecture in [`sql/varka/VISION.md`](sql/varka/VISION.md). For the
question those do not answer at a glance - *is my expression covered?* - see the
[expression coverage table](docs/sql-varka.md#expression-coverage), which is
generated from the compiler and fails the build when it drifts from it.

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

This is not the first attempt to vectorise Spark's expressions inside the JVM;
[`sql/varka/VISION.md`](sql/varka/VISION.md) section 14 records the two earlier
ones, what they found, and what this engine does differently.

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
| `dayofweek(d)`, projection | 38.0x |
| `weekofyear(d)`, projection | 28.8x |
| `date_add(d, 3)`, projection | 17.3x |
| `year(d)`, projection | 17.6x |
| `last_day(d)`, projection | 14.2x |
| `add_months(d, i)`, projection - the heaviest single call | 12.1x |
| `WHERE d BETWEEN ... AND ...`, columnar consumer | 7.8x |
| `WHERE year(d) = 2020`, columnar consumer | 6.5x |
| `WHERE d IN (3 literals)`, columnar consumer | 6.3x |
| `WHERE d < d2 AND month(d) = 6`, counted | 3.4x |
| `WHERE d IS NULL`, counted | 2.9x |
| `WHERE d < d2`, columnar consumer | **1.14x** |
| `WHERE d < d2`, counted | **0.80x** |
| `WHERE d IS NOT NULL`, counted | **0.45x** |

45 projection rows span 11.5x to 38.0x with a median of 19.5x; 18 filter rows
span 0.45x to 14.0x.

**The two remaining losses have two different causes**, and neither is the
kernel. (An earlier version of this section said all three were one bug; the
measurement below disproved it.)

The first cause is a *narrowing projection*. A predicate over two columns
forwards both, so a `SELECT d` above it genuinely narrows - and a projection of
bare forwarded columns fuses nothing, so the rule used to decline it and leave a
row-based operator on top, dragging the discarded column across the row
boundary. One-column predicates never hit this, because Spark's own column
pruning removes the redundant projection first. Absorbing that projection into
the filter node fixed the columnar-consumer row: **`WHERE d < d2` went from
0.59x to 1.14x**, a loss turned into a win.

The second cause is the **read-back floor**: about 25 ns for every row that
crosses from the vector world into Spark's row world. It is why the two
*counted* rows are still below 1.0x while both columnar rows are fine. Removing
the operator boundary moved `WHERE d < d2` counted from 0.56x to 0.80x and could
not take it further, and it left `WHERE d IS NOT NULL` counted at 0.45x
essentially untouched - that one is a single-column predicate, so it never had a
narrowing projection to remove. Heavy expressions clear the floor; a bare
`COUNT(*)` over a cheap predicate cannot, because there is almost nothing else
in the row for the vector loop to have saved.

One row is quoted against the engine-off column instead of stock:
`trunc(d, 'QUARTER')` reads 29.4x against stock 4.2.0 but **21.6x** against this
fork's own row engine, because the fork tracks Spark master and its `truncDate`
is faster than 4.2.0's. That difference is upstream Spark's, not Varka's.
It is the only row of fifty where the two baselines disagree by more than 20%.

### The TIME chains, on the same verified 512-bit machine

`TimeChain-*-results.txt`. Twelve chained `TIME` expressions three to five
operations deep - truncations, differences, interval addition and the
hour/minute/second extracts - over 100M Arrow-cached rows, one partition, table
fully resident. AMD EPYC 9V45, JDK 25, datapath probe **1.97**.

| Expression | ns/row | vs stock 4.2 (JDK 25) |
| :--- | ---: | ---: |
| `time_diff('MILLISECOND', greatest(time_trunc('MINUTE', t + dt) + dt2, t2), least(...))` | 4.2 | **46.9x** |
| `time_diff('HOUR', time_trunc('MINUTE', t + dt) + dt2, time_trunc('SECOND', ...))` | 3.7 | **46.3x** |
| `least(time_trunc('MINUTE', t + dt) + dt2, time_trunc('SECOND', t2) + dt2, time_trunc('HOUR', t) + dt)` | 3.9 | **44.8x** |
| `hour(least(time_trunc('MINUTE', t + dt) + dt2, time_trunc('SECOND', t2) + dt2, ...))` | 4.3 | **41.8x** |
| `minute(least(time_trunc('MINUTE', t + dt), time_trunc('SECOND', t2) + dt2))` | 4.2 | **29.8x** |
| `if(l > l2, time_diff('MINUTE', time_trunc('HOUR', t) + dt, t2), time_diff('SECOND', ...))` | 4.5 | **19.6x** |
| all twelve | 3.7 - 5.5 | **19.6x - 46.9x**, median 31.8x |

By executor time, which excludes the job's fixed cost, the same twelve read
21.0x to 53.2x with a median of **34.9x**. Against the fork with the engine off
they read 17.5x to 36.4x, median 25.9x.

These are the one set of files taken above this project's 5% fixed-cost
ceiling. A cloud runner's constant is about 36 ms, Varka does 0.42 s of work per
iteration here, and no row count both fits 15 GiB and keeps the constant under
5% - so the bound was lifted to 11% and each file records the bound it was held
to. The constant inflates every arm alike, so it drags the ratio *down*: the
wall column above is the conservative one, which is why the executor figures are
reported beside it rather than instead of it.

### The TIME surface: one entry per supported TIME expression

`TimeSurface-*-results.txt`. Every `TIME` and day-time interval expression the
engine covers, over 500M Arrow-cached rows on an AMD Ryzen AI 9 HX PRO 370
(JDK 25), with `spark.sql.timeType.enabled=true` on every arm. As with the date
surface this is the coverage document, and it commits the losses.

| Case | vs stock 4.2 (JDK 25) |
| :--- | ---: |
| `time_trunc('MILLISECOND', t2)`, projection | 38.2x |
| `time_trunc('MINUTE', t)`, projection | 34.3x |
| `time_diff('microsecond', t2, t)`, projection | 31.4x |
| `t + dt`, projection | 20.6x |
| `hour(t)`, projection | 17.0x |
| `t - t2`, projection | 14.4x |
| `minute(t)`, projection | 13.4x |
| `WHERE time_trunc('MINUTE', t) = ...`, columnar consumer | 9.9x |
| `WHERE l2 IS NULL`, counted | 2.7x |
| `WHERE t < t2`, counted | **0.93x** |
| `WHERE greatest(l, l2) > ...`, counted | **0.76x** |
| `WHERE dt IS NOT NULL`, counted | **0.34x** |

15 projection rows span 13.4x to 38.2x with a median of 16.2x; 28 filter rows
span 0.34x to 9.9x. The losses are the same read-back floor the date surface
has, and the explanation below applies unchanged: a `COUNT(*)` over a cheap
predicate has almost nothing in the row for a vector loop to save.

Two things this table is not. The extracts - `hour`, `minute`, `second` - are
measuring vanilla Spark's `LocalTime` allocation as much as Varka's lane, so
they are the rows least likely to generalise to a workload that is not already
building one object per row. And the whole table is a development-machine
measurement: the laptop's own datapath probe reads 1.14, so nothing here is a
claim about 512-bit hardware. The chains above are, and they are the reason that
list exists separately.

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

**On `TIME` the answer is the other way round, and the reason is the division.**
The same twelve `TIME` chains ran on an AMD EPYC 7763 - Zen 3, `avx avx2` only -
at the same row count and commit. The three scalar arms gain 1.64x, 1.76x and
1.80x between the machines, the same generation effect as above; Varka gains
**14.6x**. Divide the machine out and **8.3x is the kernel's**, which is two
things multiplied: the lane count doubles, four 64-bit lanes to eight, and the
division lowering changes, because a machine without AVX-512 falls back to a
fourteen-operation magic sequence where one with it emits a three-operation
conversion through double lanes. Two times 4.7 is 9.4 predicted against 8.3
measured. The same effect is visible on one machine: the `TIME` surface's
division rows under `-XX:UseAVX=2` run at 0.09 to 0.16 of their full-width rate
on the same laptop.

So the width is worth 1.14x on date chains and the lowering is worth 4.7x on
`TIME` chains, and both are honest. A date chain is a dependency chain of cheap
operations and waits on latency; a `TIME` chain *is* its divisions, so the
instruction the hardware offers for them decides the rate.

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

# All five distributions, one after another, into sql/varka/bench/benchmarks/.
# Each argument is LABEL=SPARK_HOME:JAVA_HOME with an optional trailing token.
# `:varka` switches on the engine *and* the Arrow columnar cache it reads through
# - the kernels need that cache to run at all - and `:arrow-cache` switches on the
# cache alone, under the row engine. So the fourth and fifth arms separate what
# the cache format is worth from what the kernels are worth, and the third is the
# fork with neither.
J17=/usr/lib/jvm/java-17-openjdk-amd64
J25=/usr/lib/jvm/java-25-openjdk-amd64
dev/varka_bench_surface.sh --benchmark chains --rows 200000000 \
  spark-4.2.0-jdk17=$PWD/spark-4.2.0-bin-hadoop3:$J17 \
  spark-4.2.0-jdk25=$PWD/spark-4.2.0-bin-hadoop3:$J25 \
  varka-off-jdk25=$PWD:$J25 \
  varka-cache-jdk25=$PWD:$J25:arrow-cache \
  varka-jdk25=$PWD:$J25:varka
```

**Do not skip the engine jar.** A distribution with Varka switched on and no
engine jar falls back on every batch, logs a `ClassNotFoundException` and then
measures the *row* engine under the kernel's name - a wrong number rather than a
failure. `dev/varka_bench_surface.sh` guards against exactly this: it fails the run
if *any* batch of a row expected to fuse fell back to the row engine - not merely if
all of them did, because a partial decline publishes a rate blended from kernel and
row-engine batches, which looks like a kernel rate and is not one.

`--benchmark surface` runs the coverage list instead, `--benchmark time` the
`TIME` surface over its own table (the type is switched on for every arm by the
driver, so the recipe above needs no extra flag), and `--benchmark timechains`
the `TIME` chains over that table. The script refuses a run
whose cached table did not stay in memory or whose per-row fixed cost exceeds 5%
of wall time, so a result that survives is one worth reading; it records the
datapath probe and the machine in every file it writes. The one place that
bound has been lifted is the `TIME` chains on a cloud runner, where the job's
constant is about 36 ms and no row count both fits 15 GiB and keeps the
constant under 5% - those files were taken at 11%, which the dispatch below
passes explicitly so that a run records the bound it was held to. Sizes are for a 16 GiB
machine - lower `--rows` if the table will not fit, and the script will tell you
if it did not.

Ratios between two files, and the tables above:

```bash
dev/varka_bench_diff.py sql/varka/bench/benchmarks/DateChain-spark-4.2.0-jdk25-results.txt \
                        sql/varka/bench/benchmarks/DateChain-varka-jdk25-results.txt
```

#### The 512-bit numbers, which need a machine you probably do not have

The chain tables above were measured on a full-width 512-bit machine, and most
hardware - including every laptop we have measured - executes AVX-512
instructions on a 256-bit datapath instead, at half the throughput. Rather than
ask you to own the right machine, the workflow that produced those files runs
on GitHub-hosted runners, and anyone with a fork can dispatch it:

```bash
gh workflow run varka-surface-benchmark.yml --repo <your-fork>/varka --ref master \
  -f stop-after=run -f require-datapath=512 -f benchmark=timechains \
  -f rows=100000000 -f partitions=1 -f driver-memory=12g \
  -f max-fixed-share=11 -f create-commit=false
```

That is the `timechains` benchmark, which is what the `TIME` chain table
above was measured with; swap `benchmark` and `rows` for the date chains
(`chains` at 2e8) and drop `max-fixed-share`, which only the `TIME` chains
need.

Three things to know before spending runner minutes on it. The gate runs the
datapath probe **inside the measuring job**, on the VM that will do the
measuring, so a dispatch that lands on a 256-bit machine aborts in about a
minute rather than producing a number under the wrong label. That is what
happens most of the time: roughly one GitHub runner in eighteen has a genuinely
full-width datapath, so a batch of ten to fifteen dispatches is the unit of
work, and the `Measure` job's `Run the surface` step having started is what
tells you one got through. And a `stop-after=build` dispatch first, from any
runner, warms the jar cache for the commit so the lucky machine spends its hour
measuring rather than building.

The results arrive as a run artifact whose files carry the CPU, the JDK, the
kernel, the row count, the cache residency, `MaxVectorSize` and the probe's own
reading, so the machine can be checked against the claim without taking anyone's
word for it.

#### Where the benchmarks live

| what | where |
| :--- | :--- |
| the expressions, one list per benchmark | [`Surface.java`](sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/Surface.java), [`Chains.java`](sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/Chains.java), [`Times.java`](sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/Times.java), [`TimeChains.java`](sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/TimeChains.java) |
| the driver: tables, arms, guards, provenance | [`DateSurfaceBenchmark.java`](sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/DateSurfaceBenchmark.java) |
| the shell entry point | [`dev/varka_bench_surface.sh`](dev/varka_bench_surface.sh) |
| the workflow that runs it on GitHub | [`.github/workflows/varka-surface-benchmark.yml`](.github/workflows/varka-surface-benchmark.yml) |
| every committed result | [`sql/varka/bench/benchmarks/`](sql/varka/bench/benchmarks) |

Each entry list is data with a comment saying why each expression is in it -
`TimeChains` records the emitter op count that earns every entry its place, and
a unit test holds the list to it - so adding an expression is one line and
removing one is visible in review.

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
q.collect()   // a new kernel: this run's batches take the row path while it compiles
Thread.sleep(5000)
q.collect()   // compiled: this run's batches run the kernel
// The fused node and its metrics:
println(q.queryExecution.executedPlan.treeString)   // VarkaColumnarToRowExec (varka: ...)
```

A fused plan shows a `Varka*Exec` node whose `numVarkaBatches` metric counts
the batches the kernels actually served. A newly emitted kernel serves none
until HotSpot has compiled it: a background thread warms it on a copy of the
first batch, within a few seconds for a narrow kernel and longer for a wide
one, and the batches that took Spark's row path meanwhile are counted in
`numWarmupBatches` (`spark.sql.codegen.varka.warmup.enabled`). Anything else
fell back to stock Spark and stayed correct.

## Reading the source

Varka is about 50 non-test source files across three modules, and they are not
equally interesting. What follows is one query's journey, in the order the code
runs it. Read these nine and you have the engine; the rest is telemetry,
caching and leaf helpers hanging off them. For the same journey taken by one
expression, with the compiler's tree, the IR, the emitted loop and the
assembly it compiles to, read
[`sql/varka/WALKTHROUGH.md`](sql/varka/WALKTHROUGH.md).

**Plan time - deciding what Varka will run.**

1. [`VarkaColumnarRule`](sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaColumnarRule.scala)
   (Scala, `sql/core`) rewrites the physical plan. It finds projections and
   filters sitting above a columnar source, asks whether they are eligible, and
   swaps in a Varka node - switching a dual-mode source to its columnar output
   where needed. Eligibility is *partial*: a projection qualifies when at least
   one entry compiles, and the rest is forwarded or left to stock Spark. Start
   here, because everything else is reached from this decision.
2. [`VarkaExpressionCompiler`](sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/codegen/VarkaExpressionCompiler.scala)
   (Scala, `sql/catalyst`) answers that question by trying to translate the
   Catalyst expression trees into Varka's own IR. It is the only file that
   knows about Spark's expression classes, and the one to extend when adding an
   expression. It is also where a translation is *declined*, which matters as
   much as where one succeeds - a decline is how correctness is preserved.
3. [`VarkaVectorIR`](sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorIR.java)
   (Java) is that IR: a sealed interface whose records are values over int32
   lanes. It is deliberately much smaller than Catalyst's expression tree, and
   deliberately carries no literal *values* - a folded literal becomes a slot
   index, so one emitted class serves every literal a query might use and the
   shape cache can key on structure alone.

**Emit time - turning the IR into a machine loop.**

4. [`VarkaLoopEmitter`](sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaLoopEmitter.java)
   (Java) walks the IR post-order and assembles a class with the JDK 25
   Class-File API - no Java source, no Janino, no ASM. Intermediates are left
   on the JVM operand stack so they stay in vector registers rather than
   memory. This is the largest and most intricate file in the engine, and the
   one to read slowly.
5. [`VarkaFusedKernel`](sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaFusedKernel.java)
   (Java) is the interface that emitted class implements - the seam between
   generated bytecode and ordinary Java. Small, and worth reading before the
   emitter, because it tells you what the emitter has to produce.
6. [`VarkaShapeCacheImpl`](sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaShapeCacheImpl.java)
   and [`VarkaShapeKey`](sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaShapeKey.java)
   keep one emitted class per plan *shape*, so the emit cost is paid once
   rather than per task.

**Run time - the loop over Arrow buffers.**

7. [`VarkaMorsel`](sql/varka/engine/src/main/java/org/apache/spark/sql/varka/memory/VarkaMorsel.java)
   (Java, `sql/varka/engine`) maps an Arrow vector's data and validity buffers
   onto Panama `MemorySegment`s, zero-copy and off-heap. This is the "morsel" a
   kernel reads and writes.
8. [`DateVectorOps`](sql/varka/engine/src/main/java/org/apache/spark/sql/varka/vector/DateVectorOps.java)
   and [`ChronoVectorOps`](sql/varka/engine/src/main/java/org/apache/spark/sql/varka/vector/ChronoVectorOps.java)
   are hand-written Vector API kernels. They are no longer the execution path -
   the emitted loop is - but they are the **reference semantics** the emitter
   mirrors, and they are commented at that level. Read `DateVectorOps` to
   understand what a Varka loop *is* before reading the emitter that generates
   one.
9. [`VarkaProjectExec`](sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaProjectExec.scala),
   [`VarkaFilterExec`](sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaFilterExec.scala)
   and [`VarkaColumnarToRowExec`](sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaColumnarToRowExec.scala)
   are the plan nodes that call the kernel per batch, and the boundary back to
   Spark's row world.

**The safety property to look for while reading.** Every stage above is allowed
to give up - the compiler on an expression it cannot translate, the emitter on
a shape it will not build, the node on a batch whose inputs do not fit. Each
gives up *into stock Spark*, per batch, and the query still returns the right
answer. This is the "ghost fallback", and it is why a decline is a normal
outcome in this code rather than an error path.

If you want to *add* an expression rather than read one,
[`sql/varka/ADDING_AN_EXPRESSION.md`](sql/varka/ADDING_AN_EXPRESSION.md) is the path from a
Catalyst tree to a committed benchmark case, with the files named in the order the code
runs them.

[`docs/sql-varka.md`](docs/sql-varka.md) is the full architecture guide - it opens with a
glossary, which is worth two minutes before anything else here: *lane*, *word*, *morsel*
and *epilogue* all mean something specific in this codebase and appear on almost every
page -
morsel layout, the emitted method anatomy, the null and predication algebra,
configuration and limitations. Read it alongside step 4 when the emitter stops
being obvious.

## Status and roadmap

Development happens in `sql/varka/plans/`, one plan file per milestone and
task, each with a recorded outcome.

*A note for anyone arriving here first:* those plans are **engineering
records**, not documentation. They say what was planned, what was built, what
was measured and where a prediction turned out wrong, and they keep the wrong
predictions in place rather than editing them away - which makes them long
(2 MB across 78 files), and makes them the wrong place to learn what Varka is.
For that, read [`docs/sql-varka.md`](docs/sql-varka.md) and the "Reading the
source" section above. The same goes for the lesson files under
[`sql/varka/skills/`](sql/varka/skills/), indexed by [`SKILLS.md`](SKILLS.md):
they are a lab notebook of measured lessons, most of them negative results kept
so nobody re-litigates a settled question, and they assume you already know the
codebase.

The milestones:

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
  [`sql/varka/plans/SCOPE_MILESTONE_7.md`](sql/varka/plans/SCOPE_MILESTONE_7.md),
  driven by a census of TPC-DS, TPC-H and the New York taxi benchmark. What that
  census says: `DateType`, the only type Varka has today, is 3.1% of the columns
  in TPC-DS and TPC-H; `DECIMAL` is the most-aggregated type and strings are 60%
  of grouping keys; and 122 of 125 queries end in an aggregate. So the milestone
  is decimals, strings as keys, grouped aggregation, and benchmarks that publish
  the number - extending three of Spark's own rather than only writing more of
  ours.

Docs map: [`docs/sql-varka.md`](docs/sql-varka.md) (user-facing guide),
[`sql/varka/VISION.md`](sql/varka/VISION.md) (architecture),
[`SKILLS.md`](SKILLS.md) (the index over the measured lessons the project keeps,
which live in [`sql/varka/skills/`](sql/varka/skills/)).

## Contributing

The work is organised as milestones with a numbered task table each; the
milestone in flight is
[`sql/varka/plans/PLAN_MILESTONE_5.md`](sql/varka/plans/PLAN_MILESTONE_5.md),
and its rows marked **Scoped** or **Planned** are open. Pick one, open an
issue naming the row, and read [`CONTRIBUTING.md`](CONTRIBUTING.md) for how
the work is done here: plans as records, numbers that trace to committed
files, the one-command gate, and the pull request conventions. Measurements
from hardware the project does not have, AMD AVX2 machines and Arm in
particular, are a contribution on their own: run `dev/varka_datapath.sh` and
open an issue with the "Add my machine" template, and the machine joins
[`sql/varka/HARDWARE.md`](sql/varka/HARDWARE.md).

## About Apache Spark

This is a research fork of [Apache Spark](https://spark.apache.org/) and is
not affiliated with or endorsed by the Apache Software Foundation. Everything
outside the Varka additions is upstream Spark; see the
[upstream repository](https://github.com/apache/spark) for Spark itself, its
[documentation](https://spark.apache.org/documentation.html) and
[contribution guide](https://spark.apache.org/contributing.html). Licensed
under the [Apache License 2.0](LICENSE), like Spark itself.
