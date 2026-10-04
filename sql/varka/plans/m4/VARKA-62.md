# VARKA-62: The closing measurement: every date expression against stock Spark

## 1. Where this came from

`m4/PLAN.md` section 2.29 and row 62, written on 4 September 2026
when the milestone was re-scoped to the date family, from the owner's
directive: "at the end of milestone 4 I would like to have benchmarks for
all added expressions for the DATE data type. And we should run the
benchmark on a CPU with 512bit datapath and compare with vanilla Spark on
JDK 17 (default one) and on JDK 25", run on a GitHub Actions runner picked
by CPU model, with the README rewritten around the result and a
reproduction guide. The audience is outside this repository: the owner
intends to present Varka publicly once the milestone closes. The same day,
VARKA-56's measurement (`VARKA-56.md` 9.2) showed that the throughput
benchmark's 10 ms Varka rows are mostly the job's fixed cost, and the owner
added the job-size rule to section 2.29: the per-job fixed cost under 5% of
every Varka row's wall time, with executor time recorded beside wall time.
This task is the last row of the milestone; this plan splits it into three
pull requests so the driver and its laptop run land first, as the baseline
the rule needs, while the remaining date-lane tasks are still open.

## 2. The admission check, done

**2.1 The stock release, and whether it runs on JDK 25.** This fork is a
snapshot of Apache Spark's master (`5.0.0-SNAPSHOT`), so no released Spark
carries its code; the baseline a reader has today is the newest release,
**Spark 4.2.0** (the newest directory under `dlcdn.apache.org/spark/` on 4
September 2026). Checked: `spark-4.2.0-bin-hadoop3`'s `spark-sql` answers
`select date_add(date'2020-01-01', 3), version()` under this machine's
OpenJDK 17 and OpenJDK 25 alike, so both baseline rows exist. The table
says "Spark 4.2.0", not "stock master", and the fork-with-Varka-off run is
the row that shows what the fork carries besides the kernel. What the check
would have rejected: a JDK 25 baseline row that does not start, in which
case the second baseline would be 4.2.0 on the newest JDK it supports.

**2.2 The benchmark workflow cannot run a downloaded distribution as it
stands.** `.github/workflows/benchmark.yml` builds this checkout's test jars
and submits `spark-core*-tests.jar` with `--class
org.apache.spark.benchmark.Benchmarks` through the checkout's own
`bin/spark-submit` under one `setup-java`; the class input is an argument to
that dispatcher. Its CPU pin, extra JVM options, `create-commit` and
tar-what-git-sees steps carry over unchanged to any file a driver writes.
So PR (B) is a sibling workflow with the three `SPARK_HOME`s and two JDKs,
not a mode of this one - and the driver is a plain `spark-submit`
application, not a class on the test classpath, or it could not run on
stock Spark at all.

**2.3 The job size.** VARKA-56's probe (`VARKA-56.md` 9.2) put the
cheapest Varka shape through the executor near a nanosecond per row, with
the job's fixed cost near 10 ms on that laptop; the section 2.29 rule (fixed
under 5%, so at least 200 ms of wall time per Varka row) therefore means
about 200M rows, whatever the partitioning, because the fixed cost is
per job and the executor cost is per row. At 200M rows the table is three
columns of four bytes plus validity, under 3 GB in either cache, and the
stock rows at their measured 20-odd nanoseconds per row take four to five
seconds per iteration, which puts a full three-distribution run in the
order of an hour. What the check would have rejected: a row count picked
for a short run, which is what the throughput benchmark's 2M rows were.

**2.4 What is covered today.** `compileNode` and `compileCond`, read in
this worktree: the arms in 3.4, and no others. `weekofyear` (VARKA-37),
`make_date` (VARKA-42), the ISO fields (VARKA-57 and VARKA-58) and the column
forms of `next_day`, `add_months` and `trunc` (VARKA-59 to VARKA-61) are open
rows, so they are not in this PR's list; the list is data and PR (B)'s
dispatch runs what it holds then.

**2.5 A packaged tree does not run the kernels, found by the smoke run.** The
driver's first run on this checkout's `bin/spark-submit` with Varka on planned
every entry through a Varka node and answered at the row engine's speed, with
a `ClassNotFoundException` for `VarkaVectorSupport` on every batch: the
assembly does not ship the engine jar, because the build has the engine as a
test-scope dependency, and under sbt's test classpath nobody had noticed. The
shell driver passes the jar with `--driver-class-path` (the kernels' loader
delegates to the system loader, which `--jars` does not reach), the issue is
recorded (`ISSUES.md`, "The engine jar is not in the distribution"), and the
build fix is its own task. What the check would have rejected: a public
table whose Varka column was Janino under another name - which is exactly
what the `EXPLAIN` check alone would have passed, since the plan was right
and the runtime was not. The driver therefore also fails a Varka run whose
log shows a kernel fallback on every batch: see 3.1, the fallback count.

## 3. The design

### 3.1 A standalone driver, submitted to any Spark

**Three pull requests.** (A) the driver, its module, the shell driver and
the laptop's run - this PR; (B) the workflow that runs the three
distributions on a pinned runner and commits their files; (C) the README's
benchmark section rewritten from the committed files, with the reproduction
guide. Each has its own gate; the milestone row closes with (C).

**The module: `sql/varka/bench`**, Maven coordinates
`org.apache.spark.varka:varka-bench`, on the engine module's precedent
(`sql/varka/engine/pom.xml`: no parent, its own small plugin set) but *not*
in the root reactor, because it compiles against a **released** Spark - the
`spark-sql_2.13` artifact of the stock release under test, `provided` scope -
so that the one jar runs unchanged on every distribution. It uses only the
API that has been stable since Spark 3: `SparkSession.builder`, `sql`,
`Dataset.write.format("noop")`, `count`, `SparkListener` and `TaskMetrics`,
and `EXPLAIN` through SQL. `maven.compiler.release` is **17**, the oldest JDK
it runs on. No dependency on this fork: the fork is a distribution like the
others, and enabling Varka is a `--conf`
(`spark.sql.codegen.varka.enabled=true`, the rule being registered in every
session by `BaseSessionStateBuilder`) plus the static
`spark.sql.cache.serializer` pointing at the Arrow serializer, both passed
by the shell driver, never known to the Java code.

**The driver, `DateSurfaceBenchmark`.** Arguments: `--out FILE`, `--rows N`,
`--label NAME` (the distribution's name, printed in every table), `--iters`
(default 5), `--warmup-seconds` and `--min-seconds` (default 2 and 2, VARKA-14's methodology), `--only REGEX` for a partial run, and `--provenance
KEY=VALUE` repeated, for what only the shell driver knows (commit, the
datapath probe). It builds one table, `varka_dates`, with the generator the
throughput benchmark uses - `d` a date with every 31st row null, `d2` a
second date, `i` an int - over `range(0, N)` in `ceil(N / 4M)` partitions,
caches it through `spark.catalog.cacheTable` and forces it with a count.
Then, for every entry of the **surface** (3.4), in two shapes:

* the projection: `SELECT <expr> AS a FROM varka_dates` written to the
  `noop` sink, which accepts columnar batches, so the fork's kernel output
  is consumed without a row conversion and stock Spark's codegen output is
  consumed the same way;
* the filter: `SELECT count(*) FROM varka_dates WHERE <pred>`, the count
  being the cheapest consumer of a filter that cannot be optimised away.

Each is warmed for `--warmup-seconds`, then run for at least `--iters`
iterations and `--min-seconds`, with wall time per iteration from
`System.nanoTime` and executor time per iteration from a `SparkListener`
summing `TaskMetrics.executorRunTime` over the iteration's tasks (the
listener bus is drained with `waitUntilEmpty` before reading, so no
iteration reads the previous one's tasks; warm-up iterations are discarded
by index). Before timing, on every distribution, the driver runs
`EXPLAIN` on the query and records whether the plan contains a Varka node;
on the fork with Varka on, an entry that does not fuse is reported in the
file as `residual`, never silently timed, and the shell driver fails the
run if any entry the surface marks as expected-fused is residual.

**Corrected after the smoke run (2.5), the same day.** Two things the first
run taught. The counted filter is the WHERE-plus-aggregate shape a query has,
and on the fork it pays the row read-back for every selected row on the way
to the count, so it measures the read-back floor as much as the kernel; the
fork's own filter benchmark prices both consumers, and so does this driver
now: a third shape, `filter, columnar consumer` - `SELECT d FROM varka_dates
WHERE <pred>` written to the `noop` sink - beside `filter, counted`. And the
`EXPLAIN` check is not enough to know the kernel ran: the driver registers a
`QueryExecutionListener` and sums the fork's `numVarkaBatches` and
`numFallbackBatches*` metrics over each shape's measured iterations, prints
them on the plan line (`Varka (kernel N batches, fallback M)`), and under
`--expect-fused` fails a shape that planned a Varka node and ran no kernel
batch. On stock Spark no plan carries the metrics and the counts stay 0.

**Corrected after the first 200M-row run, the same day.** Every counted
filter on stock Spark took 4 ms with no executor time. The driver had built
one `Dataset` per shape and called `collect` on it each iteration; the count's
final aggregate is behind a shuffle once the table has more than one
partition, and Spark reuses a registered shuffle map stage for an RDD lineage
it has already run, so from the second iteration only the one-partition
result stage executed. The 2M-row smoke runs had one partition, no shuffle,
and could not show it. The driver now plans every iteration's query afresh
(a fresh plan is a fresh lineage), and fails any shape whose best executor
time is zero, which is what a reused stage or a folded query looks like from
the file. Every row of the first run's file was discarded with the run.

**The results file** is in Spark's harness format exactly - the table
header `<name>:  Best Time(ms)  Avg Time(ms)  Stdev(ms)  Rate(M/s)  Per
Row(ns)  Relative` and its row layout - because `dev/varka_bench_diff.py`
keys on that header and `dev/varka_quote_check.py` reads those numbers.
Two tables per entry: `<entry> over N rows` with its cases - `projection,
columnar consumer`, `filter, columnar consumer`, `filter, counted` - and
`<entry> over N rows, executor time` with the same two cases, so the fixed
share of every row is `(wall - executor) / wall` from the file. The file opens
with the provenance block `dev/varka_bench_regen.sh` writes - commit, date,
JDK, kernel, CPU, power, load at start - extended with what this task needs:
Spark's `version()`, the JVM's `MaxVectorSize` read through
`HotSpotDiagnosticMXBean` (the flag the run actually had, not the one it was
asked for), the `avx512*` flags from `/proc/cpuinfo`, and the datapath probe
(below). Files are named `DateSurface-<label>-jdk<NN>-results.txt` under
`sql/varka/bench/benchmarks/`, and the quote check's `RESULT_GLOBS` gains that
directory.

**The shell driver, `dev/varka_bench_surface.sh`.** Takes the three (or
four) distributions as `LABEL=SPARK_HOME:JAVA_HOME[:extra confs]`, refuses a
busy machine like the regen script, runs the canary, runs the **datapath
probe** - `dev/varka_canary/Canary.java` under JDK 25 at
`-XX:MaxVectorSize=32` and `=64`, whose compute rates' ratio is near 2x on a
full-width unit and near 1x on a double-pumped one (`SKILLS.md`, "This
machine's AVX-512 is 256 bits wide") - and passes `datapath=<r32>/<r64>` to
every run's provenance, then submits the jar to each distribution in turn
with `spark-submit --master local[1] --driver-memory 8g`, and finishes with
the diff script's comparison of each baseline file against the Varka file.
`dev/varka_bench_diff.py` gains `--table`, which prints the README's
markdown table - one row per entry and shape, the query text beside the
number, wall rates and the ratio new/old - from two files.

**The job size.** `--rows` defaults to 200M, from the rule in section 2.29
and the arithmetic in 2.3: the cheapest Varka shape runs near a nanosecond
per row through the executor, so 200 ms of wall time is 200M rows, and the
row count is the same for every entry and distribution so ratios are on
the same data. The driver prints the fixed share beside every Varka row
and the shell driver fails the run when a Varka row is over 5%.

### 3.2 What is deliberately unchanged

* The emitter, the compiler, the evaluator: this task measures them.
* `VarkaThroughputBenchmark` and the other committed benchmarks: they stay
  the project's own A/B instruments at 2M rows; the sizing rule applies to
  the public table, and their methodology is a separate note for milestone
  5's debt register.
* `.github/workflows/benchmark.yml`: it runs this repository's test-jar
  benchmarks through the `Benchmarks` dispatcher and stays so; PR (B) adds
  a sibling workflow rather than a mode.
* The stock distribution's cache serializer: stock Spark caches through its
  own columnar serializer, the fork through the Arrow one when Varka is on.
  That difference is part of what the reader gets, and the fork-with-Varka-
  off run (default serializer) is the row that separates it from the kernel.

### 3.3 Registered op counts

None: no emitted byte changes.

### 3.4 The surface

One entry per covered date expression, from the compiler's arms as of this
PR (`VarkaExpressionCompiler.compileNode` and `compileCond`), with the
spelling a reader would write. Projections: `date_add(d, 3)`,
`date_add(d, i)`, `date_sub(d, 5)`, `datediff(d2, d)`, `year`, `month`,
`day`, `quarter`, `dayofyear`, `dayofweek`, `weekday`, `next_day(d,
'MONDAY')`, `last_day`, `add_months(d, 3)`, `d + INTERVAL 3 MONTH`,
`trunc(d, 'YEAR'|'MONTH'|'QUARTER'|'WEEK')`, `unix_date`,
`date_from_unix_date(unix_date(d))`, `if(d < d2, d, d2)`, `CASE WHEN d <
d2 THEN d ELSE d2 END`, `coalesce(d, d2)`, `greatest(d, d2)`, `least(d,
d2)`, `year(date_add(d, 30))` as the fused chain. Filters: `d < d2`,
`d = d2`, `d BETWEEN DATE'2020-06-01' AND DATE'2021-06-01'`, `d IN
(DATE'2020-01-01', DATE'2020-07-01', DATE'2021-01-01')`, `d IS NULL`, `d IS
NOT NULL`, `year(d) = 2021`, `dayofweek(d) = 1`, `d < d2 AND month(d) = 6`.
The list is data in one Java class; VARKA-56's `d + CAST(i AS INTERVAL
DAY)` (#118, open), `weekofyear`, `make_date` and VARKA-57 to VARKA-61's forms are
one line each when they land, and the final dispatch in PR (B) runs
whatever the list holds then. *Noted after the laptop run (9.1): on this
branch the three predicates over a calendar field and an int literal plan
only partly fused (#123 is where the literal is admitted), and the two-column
`d < d2` in the columnar-consumer shape narrows the filter's output and runs
through rows; both are marked in the files' plan lines.*

## 4. Files

| file | what |
|---|---|
| `sql/varka/bench/pom.xml` | the module: release 17, `spark-sql_2.13` of the stock release provided, JUnit 5 |
| `sql/varka/bench/src/main/java/.../bench/DateSurfaceBenchmark.java` | arguments, the table, the loop over the surface, the two shapes |
| `.../bench/Surface.java` | the entry list: label, projection expression, filter predicate, expected fused |
| `.../bench/Harness.java` | warm-up, iterations, wall and executor timing, the harness-format tables |
| `.../bench/Provenance.java` | the block: the regen script's fields plus version, `MaxVectorSize`, CPU flags, probe |
| `sql/varka/bench/src/test/java/...` | section 5's tests |
| `sql/varka/bench/benchmarks/` | the laptop's files, one per distribution, from this PR's run |
| `dev/varka_bench_surface.sh` | the shell driver: gates, canary, datapath probe, the runs, the comparison |
| `dev/varka_bench_diff.py` | `--table` |
| `dev/varka_quote_check.py` | the new results glob |
| `dev/varka_bench_regen.sh` | (folded in, unrelated) a bare class name resolved from the module's sources |
| `m4/PLAN.md`, this file | row 62 as "(A) done, (B) and (C) open", section 9 |

## 5. Tests, and what each is for

* `HarnessFormatTest`: the formatter's output matches the diff script's
  `HEADER` and `ROW` regexes, copied into the test, for a table with two
  cases, so a drift in the layout fails here and not as an empty diff.
* `HarnessTimingTest`: with a fake clock and a fake listener, five
  iterations over two-second windows yield the best, average and standard
  deviation the harness prints, warm-up iterations excluded.
* `SurfaceTest`: every entry parses and runs on a local stock session over
  a thousand rows, both shapes, and every entry's projection is a `DateType`
  or the type the entry declares - the failure it catches is a typo in the
  list, before a two-hour run finds it.
* `ProvenanceTest`: the block has every key, `MaxVectorSize` is an integer,
  and unknown values print as `n/a` rather than failing the run.
* On the fork, the driver's own `EXPLAIN` check is the fusion test: the
  laptop's Varka file must show every expected-fused entry fused, which the
  shell driver enforces.

## 6. The measurement

The laptop, this PR: `dev/varka_bench_surface.sh` over Spark 4.2.0 on JDK
17, Spark 4.2.0 on JDK 25 (if it starts; 2.1), this fork with Varka on JDK
25, and this fork with Varka off on JDK 25, at 200M rows, on an idle
machine, one run, the four files committed with provenance. The control
rows are the two stock runs against each other (the JDK's own effect) and
the fork-with-Varka-off run against stock on JDK 25 (what the fork carries
besides Varka). The 512-bit runner's files are PR (B)'s.

### 6.1 Predictions, registered before the run

1. At 200M rows every Varka projection row runs 180 to 600 ms of wall time
   with a fixed share under 5%; the rule passes without a second sizing.
2. On this laptop (256-bit datapath), Varka against stock Spark on JDK 17,
   wall time: 4x to 10x on the single-expression projections, 2x to 4x on
   the filters, the fused chain at the top of the range. Executor-time
   ratios are higher than wall ratios on every row, because the fixed cost
   is the same on both sides and is a larger fraction of the faster one.
3. Stock Spark on JDK 25 against JDK 17: within 10% either way on every
   row; the JDK is not where the difference comes from.
4. The fork with Varka off against stock Spark on JDK 25: within 20% on
   every row, the residue being the Arrow cache path against stock's
   columnar cache; if a row is further apart, that row is explained before
   the README quotes it.
5. No row is a loss on the projections; on the filters, a predicate that
   selects almost nothing (`d IS NULL`) is where the fork is closest to 1x,
   since the count dominates.

## 7. Risks

1. **Spark 4.2.0 does not run on JDK 25.** Checked in 2.1 before the
   design was fixed; if it does not, the JDK 25 baseline is Spark 4.2.0 on
   the newest JDK it supports, said so in the table.
2. **200M rows do not fit the runner.** PR (B)'s problem, but the row
   count is an argument and the fixed share is in the file, so a smaller
   run is visibly a smaller run, never a silently overhead-dominated one.
3. **The provided API drifts between 4.2.0 and this fork's 5.0.0.** The
   driver uses the stable subset only and `SurfaceTest` runs on the stock
   artifact; the fork run is exercised by the laptop measurement.
4. **The `noop` sink is not columnar on stock Spark.** It is
   (`NoopDataSource.supportsColumnarWrite`), and the driver's `EXPLAIN`
   shows a `ColumnarToRow` if it is not; the file would say so.
5. **A residual entry is timed as if fused.** The `EXPLAIN` check and the
   shell driver's failure on an expected-fused residual.

## 8. Sequencing

1. The regen script's class resolution (the unrelated one-liner), and this
   plan.
2. The module, the driver, the harness and provenance, with the four unit
   tests; green under `build/mvn -f sql/varka/bench/pom.xml verify`.
3. The shell driver, the datapath probe, `--table`, the quote glob.
4. The laptop run: four files, section 9, the milestone row.

## 9. Outcome, PR (A): the driver and the laptop's run

Measured on the night of 4-5 September 2026 on the idle laptop under the
performance governor (canary ok, datapath probe ratio 1.00: the 256-bit
datapath `SKILLS.md` records), 500M rows in one partition, `--iters 5`,
two-second warm-up and windows, through `dev/varka_bench_surface.sh` with
`--max-fixed-share 15`. Four files under `sql/varka/bench/benchmarks/`:
`DateSurface-spark-4.2.0-jdk17-results.txt`,
`DateSurface-spark-4.2.0-jdk25-results.txt`,
`DateSurface-varka-off-jdk25-results.txt` and
`DateSurface-varka-jdk25-results.txt`, each with its provenance block; the
numbers below are wall-time rates in M rows/s from those files, stock Spark
4.2.0 on JDK 17 against this fork with Varka on JDK 25, and the executor-time
tables beside them agree within the fixed share.

### 9.1 What the laptop says

**Projections, columnar consumer.** Every entry between 15x and 41x:
`date_add(d, 3)` 74.9 against 1637.0 (21.86x), `datediff(d2, d)` 57.0
against 1303.4 (22.87x), `year(d)` 48.9 against 1055.8 (21.59x),
`dayofweek(d)` 36.6 against 1487.7 (40.65x), `last_day(d)` 46.0 against
802.2 (17.44x), `add_months(d, 3)` 33.5 against 505.5 (15.09x),
`trunc(d, 'QUARTER')` 26.6 against 804.9 (30.26x), and the fused chain
`year(date_add(d, 30))` 48.2 against 1069.4 (22.19x). No projection row is
a loss.

**Filters that fused whole.** Columnar consumer: `d BETWEEN ...` 152.7
against 1216.2 (7.96x), `d IN (...)` 208.8 against 1405.4 (6.73x), `d IS
NULL` 209.9 against 1208.7 (5.76x), `d IS NOT NULL` 83.3 against 883.1
(10.60x), `d = d2` 93.3 against 681.2 (7.30x). Counted, where every selected
row crosses the read-back floor into the aggregate: `d IN` 215.1 against
825.2 (3.84x), `d IS NULL` 225.4 against 693.2 (3.08x), `d BETWEEN` 206.5
against 245.8 (1.19x), and `d IS NOT NULL` at 96.8% selected 189.1 against
78.7 (0.42x) - the VARKA-19 floor, in the public table as the loss it is.

**Three filter rows are losses, and none is a kernel loss.** `year(d) =
2021` 86.3 against 59.8 (0.69x), `dayofweek(d) = 1` 50.9 against 43.1
(0.85x), `d < d2` 67.7 against 43.2 (0.64x), and `d < d2 AND month(d) = 6`
70.6 against 67.2 (0.95x). `EXPLAIN` on the fork, run while writing this
section, shows why: the calendar predicates declined at their int literal
on this branch (the compiler admits an int literal against a fused field
only from VARKA-37, #123), so the Varka filter took `isnotnull(d)` alone and
Janino's `Filter` ran over every row through the filter's row-producing
variant - 16.7 ns per input row is exactly that path; and `SELECT d ... WHERE
d < d2` narrows the filter's output to one of its two columns, a projection
the columnar rule does not take, so a Janino `Project` sits above the filter
and again every selected row is a row. The driver's fusion check had passed
all three because the plan contained a Varka node; it is a three-way
classification now (`Fusion.PARTIAL` when a row-engine `Filter` or a
non-empty `Project` sits above the Varka node, `PlanCheckTest` over the three
plans as printed), and under `--expect-fused` a partial shape fails the run.
The first finding closes with #123; the second is new and goes to the debt
register (9.4).

**The controls.** Stock 4.2.0 on JDK 25 against JDK 17: the date arithmetic
rows within 11% (`date_add(d, i)` 56.6 to 62.8), but the calendar rows well
outside it - `year(d)` 48.9 to 65.2 (+33.3%), its counted filter 100.2 to
142.7 (+42.4%): JDK 25's C2 does better on the row engine's calendar code,
so the JDK 17 column is the one a reader upgrading from today's Spark sees
and the JDK 25 column the one that isolates Varka. The fork with Varka off
against stock on JDK 25: every row within 4% except `trunc(d, 'QUARTER')`
26.8 to 35.9 (+34.0%) and `trunc(d, 'WEEK')` 65.6 to 63.2 (-3.7%), so the
fork's row engine is stock's, and the one row that is not is explained
before PR (C) quotes it (9.4).

**The fixed share.** 5.1% to 9.6% on the Varka projection rows (0.2% to
0.4% on the filters, whose wall time is seconds): about 25 to 45 ms of
planning and commit per job against 300 to 600 ms of kernel, so section
2.29's 5% rule is **not met at 500M rows**, and the run passed only because
the night script set the gate at 15%. The machine has 83 GB, so 1B rows
(12 GB cached) is the next run's size; the executor-time table already
gives the engine-only number, and every ratio quoted above moves by under
one part in twenty between the two tables.

### 9.2 The predictions of 6.1, scored

1. **Every Varka projection row at 180-600 ms of wall time with a fixed
   share under 5%.** The first half held (305 ms for `date_add(d, 3)`, 989
   ms for `add_months`); the second **missed**: 5.1% to 9.6%, because the
   per-job fixed cost is 25-45 ms when every iteration plans its query
   afresh and commits a `noop` write, not the 10 ms VARKA-56's probe saw on a
   reused plan.
2. **4x to 10x on the projections, 2x to 4x on the filters.** Exceeded by
   two to four times on the projections (15x to 41x), because stock Spark's
   cached-table scan and codegen run at 27-75 M rows/s over 500M rows here,
   where the in-repo Janino baseline at 2M rows ran two to three times
   faster from cache-resident data and the in-repo Varka rows were paying
   the fixed cost - the varka-off control shows it is the row engine at this
   size, not the fork. The filters landed above the band where they fused
   (5.8x to 10.6x columnar) and below it where they did not (9.1).
3. **Stock on JDK 25 within 10% of JDK 17 on every row.** **Missed** on the
   calendar rows (+33% to +42%) and at the edge on day arithmetic (+7% to
   +11%).
4. **The fork with Varka off within 20% of stock on JDK 25.** Held on every
   row but `trunc(d, 'QUARTER')` (+34.0%), to be explained.
5. **No projection loss; the filters closest to 1x at `d IS NULL`.** The
   first half held; the second missed - `d IS NULL` is 5.76x and 3.08x, and
   the rows near or under 1x are the three partial-fusion shapes and the
   counted `d IS NOT NULL`.

### 9.3 What moved that the plan did not list

* The table is 500M rows in one partition, not 200M in fifty tasks: the
  first 200M-row run left 20-45% of every Varka row outside the executor in
  per-task scheduling and commit on `local[1]` (the commit history has it).
* The driver plans every iteration's query afresh, after a reused shuffle
  stage answered the counted filters in 4 ms with no executor work; and a
  shape whose best executor time is zero fails the run.
* The third shape, `filter, columnar consumer`, and the selectivity beside
  every filter row, since the filter rows split on it.
* The fusion check is three-way (9.1), with a test over the plans as printed.
* The fixed-share gate is a script option, and the night run set it at 15%.

### 9.4 What PR (A) leaves for later

* **A column-narrowing projection above a Varka filter runs through rows.**
  `SELECT d FROM t WHERE d < d2` plans a Janino `Project` over the
  row-producing filter, because a projection with no fusable entry is not
  eligible even when every entry is a forwarded column of a Varka child. The
  fix is small - let `VarkaFilterExec` prune its output to the columns the
  parent needs, or take a forwarded-only projection when the child is a
  Varka node - and it is worth a task row: every two-column predicate whose
  consumer wants fewer columns pays the floor today. Recorded in
  `m4/PLAN.md`'s debt register.
* **The 1B-row run**, after the stack (#123 to #130) lands and the surface
  gains its entries (`weekofyear`, `make_date`, the ISO fields, the column
  forms of `next_day`, `add_months` and `trunc`, `d < d2` narrowed), which
  replaces these four files with ones that meet the 5% rule and show the
  calendar predicates fused.
* **`trunc(d, 'QUARTER')` on the fork's row engine is 34% faster than
  stock's**: the fork tracks master, whose `truncDate` may differ from
  4.2.0's; to be read before PR (C) quotes the row.
* PR (B), the runner with the pinned Xeon and the datapath probe; PR (C),
  the README from the runner's files.

## 10. Outcome, the 1B-row run: the size rule met, and three losses become wins

9.4 left the laptop's run to be redone "after the stack (#123 to #130) lands
and the surface gains its entries", because the 500M files broke the job-size
rule of `m4/PLAN.md` 2.29 (5.1% to 9.6% fixed share on the Varka
projection rows, passed only because the night script's gate was 15%) and
because three of their four filter losses were shapes that declined at their
int literal on a branch that predated VARKA-37. Both are now done. The four
files here are one sweep at 1,000,000,000 rows in one partition under a 32g
driver, stock 4.2.0 on JDK 17, stock 4.2.0 on JDK 25, this fork with Varka
off, and this fork with Varka on, in that order, on an idle laptop with the
canary green and the datapath probe reading 1.00 (this machine's AVX-512 is
256 bits wide - `SKILLS.md` - so every ratio here is a 256-bit-datapath one).

**The size rule is met, with room.** Fifty Varka rows carry a fixed share; the
worst two read 4.5%, the median 1.7%, and none exceeds the 5% rule. At 500M
the same rows ran 5.1% to 9.6%. The rule can be checked from the committed
file alone: every `# fixed share` line is in it.

**Four entries were added to the surface**, the ones the existing table
supports: `weekofyear(d)` (VARKA-37), `extract(DAYOFWEEK_ISO FROM d)` and
`extract(YEAROFWEEK FROM d)` (VARKA-57 and VARKA-58), and `add_months(d, i)`, VARKA-60's column month count - `i` runs 0..3649, inside
`VarkaChrono.MONTH_ARITH_MAX_MONTHS` (24564), so no batch trips the guard,
which matters because a declined batch would measure the row engine under a
fused row's name. Thirty-nine entries, fifty measured shapes, every one of
them a Varka plan with zero fallback batches.

| new entry | stock JDK 17 | Varka | ratio |
|---|---|---|---|
| `weekofyear(d)` | 24.6 | 835.3 | 33.96x |
| `extract(DAYOFWEEK_ISO FROM d)` | 40.6 | 1613.8 | 39.75x |
| `extract(YEAROFWEEK FROM d)` | 38.3 | 971.0 | 25.35x |
| `add_months(d, i)` | 32.1 | 451.7 | 14.07x |

**Three of the four losses are gone.** 9.1 reported `year(d) = 2021` at 0.69x,
`dayofweek(d) = 1` at 0.85x and `d < d2 AND month(d) = 6` at 0.95x, and named
the cause: the compiler admitted an int literal against a fused field only
from VARKA-37, so the Varka filter took `isnotnull(d)` alone and Janino filtered
every row. With VARKA-37 in, all three fuse and all three win, against stock on
JDK 17:

| shape | before (500M) | now, columnar | now, counted |
|---|---|---|---|
| `year(d) = 2021` | 0.69x | 9.47x | 2.16x |
| `dayofweek(d) = 1` | 0.85x | 16.53x | 6.48x |
| `d < d2 AND month(d) = 6` | 0.95x | 4.20x | 4.63x |

**Two losses remain, and neither is a kernel loss.**

* `d < d2` at 0.66x columnar and 0.57x counted: the column-narrowing debt of
  9.4. `SELECT d FROM t WHERE d < d2` puts a Janino `Project` above the
  row-producing filter because a projection with no fusable entry is not
  eligible even when every entry is a forwarded column of a Varka child.

  *Corrected on 8 September 2026, by `VARKA-78.md` 2.3's admission check:
  this bullet read "the same shape without the narrowing, `d = d2`, runs
  6.95x". `d = d2` is `Surface.residualFilter` and carries exactly the same
  narrowing `Project` - this run classifies it `PARTIAL` for that reason - so
  it is not a control for the narrowing at all. What separates the two rows is
  selectivity: an equality between two independent date columns selects almost
  nothing, `<` selects about 70%, and the read-back cost is per selected row.
  The sentence was read as pricing the projection at 6.3x, and nothing here
  prices the projection; VARKA-78 measures it against a fixed selectivity.*
* `d IS NOT NULL`, counted, at 0.43x with 96.8% selected: VARKA-19's read-back
  floor, in the public table as the loss it is.

The three shapes that are partial for that reason - `d < d2`, `d = d2` and
`d < d2 AND month(d) = 6` - are now `Surface.residualFilter` rather than
`Surface.filter`, so `--expect-fused` holds every other shape to full fusion
and records these as expected-partial instead. The first sweep exited 3 on
them, after writing all four files: the gate found what it exists to find, and
the flag now says which shapes are known to be partial rather than turning the
check off.

**The projections, for the record.** Every one of the 32 projection rows is
between 11.5x and 41.9x of stock on JDK 17. The extremes are `dayofweek(d)` at
41.85x and `add_months(d, i)` at 14.07x, with `weekday(d)` 40.26x,
`extract(DAYOFWEEK_ISO FROM d)` 39.75x, `weekofyear(d)` 33.96x and
`trunc(d, 'QUARTER')` 30.65x above the rest. The plain day arithmetic sits
near 24x and the calendar extractions near 20x against JDK 25.

**The controls still say what they said.** The fork with Varka off tracks
stock on JDK 25 within a couple of percent on every row but
`trunc(d, 'QUARTER')` (38.4 against 27.9), which is 9.4's open item: the fork
tracks master, whose `truncDate` may differ from 4.2.0's, and it is to be read
before PR (C) quotes that row. Stock on JDK 25 against JDK 17 keeps the shape
9.1 found - the date arithmetic within a few percent, the calendar rows well
apart (`year(d)` 54.0 to 68.1) - so the JDK 17 column is what a reader
upgrading from today's Spark sees and the JDK 25 column isolates Varka.

**What this still does not do.** `make_date` and the column forms of
`next_day` and `trunc` are not in the surface: each needs columns the table
does not have, two of them strings, and at a billion rows those columns take
the cached table from about 12 GB to about 42 GB. The house pattern is a
narrow table per shape family, as `VarkaThroughputBenchmark` has, which is a
driver change rather than a surface one and belongs with PR (B). PR (B), the
pinned full-width runner, and PR (C), the README, are unchanged by this run
except that they now have a laptop data point that meets the size rule.

## 11. PR (B): the runner

*Planned 11 September 2026, after PR (A) and its 1B-row run landed.*

### 11.1 What (B) is, and what it is not

3.1 said PR (B) is "the workflow that runs the three distributions on a
pinned runner and commits their files". The word *pinned* was doing work it
cannot do, and this section replaces it.

**There is no pinned runner and there does not need to be.** Every committed
benchmark results file in this repository - the upstream ones, produced by
`.github/workflows/benchmark.yml` - carries the CPU it ran on, and across the
tree those CPUs are: AMD EPYC 7763 (Zen 3, no AVX-512) on about 1800 files,
AMD EPYC 9V74 (Zen 4, AVX-512 double-pumped to 256 bits) on about 875, Intel
Xeon Platinum 8370C (Ice Lake-SP, full 512-bit) on 64, and Intel Xeon
6973P-C and 6975P-C (Granite Rapids, full 512-bit) on 54. All four families
appear in files committed on one day from a single regeneration, all on
`Linux ...-azure`. So GitHub's pool is heterogeneous *within one workflow
run*, it does reach genuine full-width AVX-512 hardware, and the upstream
project's whole approach is to record the CPU in the file and accept what it
gets.

**So (B) follows that convention and adds the check the convention lacks.**
`benchmark.yml` in this fork already carries an early CPU check - the
`expected-cpu` input, whose step reads `/proc/cpuinfo` and fails the job
before any build when the model does not match. (B) reuses that shape and
goes one better, because "512-bit datapath" is a property and not a model:
the **datapath probe decides**, and the model list is documentation.

### 11.2 The admission check, to do first

Two questions, and the second can close this PR's *design* even though it
cannot close the task.

**11.2.1 Can a GitHub runner hold enough rows?** This is 7's risk 2 and it is
the real one. `benchmark.yml` passes `--driver-memory 6g` with the comment
"GitHub Actions has 7 GB memory limit"; the shell driver defaults to `16g`,
and section 10's committed files are 1B rows in one partition, which is where
the 5% fixed-share rule is finally met - the worst row at 4.5%, the median at
1.7%. At 500M the rule was *not* met for the fastest shapes, which is why the
run was redone. A runner that cannot cache 1B rows therefore cannot reproduce
the committed files' size discipline, and the driver will say so rather than
lie: it fails any Varka row whose fixed share exceeds `--max-fixed-share`.

What to establish, in one short workflow dispatch before the full one is
written:

* the runner's actual memory (`free -g`) and core count, since the "7 GB"
  comment predates GitHub's current standard runner;
* how many rows the Arrow cache holds inside it - the cache is off-heap
  `MemorySegment`s, so the binding constraint is machine memory rather than
  the driver heap, and the two must be measured rather than reasoned about;
* whether the fixed-share rule holds at that row count, which the driver
  already answers by failing.

If the largest row count that fits leaves the rule unmet, the honest outcomes
are, in order of preference: more partitions (which costs ~2 ms of scheduling
per task on `local[1]` and may still win), a documented higher share with the
number in the file, or a larger runner. What is **not** acceptable is a file
whose headline rate is mostly job overhead, which is exactly what the rule
exists to prevent and what the first 200M-row attempt produced.

**Measured, 11 September 2026.** The runner is 4 cores and **15 GiB**, which
retires `benchmark.yml`'s "7 GB memory limit" comment as the basis for the `6g`
driver setting. The build is **9m48s cold** - `sbt package` 363 s, then the two
Maven modules, then 525 MB of jars uploaded - and **52 s warm**, which is the
commit-keyed cache of 11.10 working. Both numbers are far below what this
section feared; see 11.10 for what that retires.

The row count is the live question. At **1e7 rows** the surface completed in
21m19s and then failed 61 Varka rows, the worst at **68.2%** and the median at
**35.9%** against the 5% ceiling; at **1e8** it failed 45, worst **18.3%**,
median **7.2%**. That is the rule doing its job at the small end: with one
partition there is not enough executor time to hide planning and scheduling.

**Then 2e8 passed, and the pass was false.** See 11.12, which is the most
important thing this task has found. Two extrapolations of the trend above -
"about 5.5% at 1e8" and "about 2.9% at 2e8" - were both written here and both
were wrong, and the reason they were wrong is that the trend they extrapolated
had a cliff in it that the fixed-share rule cannot see.

**11.2.2 How often does the pool give a full-width machine?** The census
counts *files*, not dispatches, and one dispatch writes as many files as the
benchmarks it ran - so 118 full-width files out of ~2815 is **not** a 4% hit
rate and must not be quoted as one. What the census establishes is weaker and
sufficient: full-width machines are in the pool and are reached in ordinary
use. The per-dispatch rate is unknown and is what the `probe-only` dispatches
measure, ten of them being enough to tell "roughly one in three" from "one in
twenty" and to price the retry loop.

It is fine for the rate to be low *provided the probe runs first*: a
wrong-width dispatch must cost two minutes, not two hours. If nothing
full-width appears in twenty dispatches the fallback is a larger runner or a
self-hosted one, and that is a cost decision rather than a design one.

### 11.3 The design

**A sibling workflow, `.github/workflows/varka-surface-benchmark.yml`.** Not a
mode of `benchmark.yml`: 2.2 established that one builds this checkout's test
jars and submits through its own `bin/spark-submit` under a single
`setup-java`, while this driver is a plain application that must run on a
*downloaded* Spark under two JDKs.

Inputs, following `benchmark.yml`'s naming so the two read alike:

| input | default | why |
|---|---|---|
| `rows` | from 11.2.1 | the job-size rule's number for this machine |
| `partitions` | `1` | 2.6's finding; raised only if 11.2.1 forces it |
| `driver-memory` | from 11.2.1 | the script defaults to `16g` for the laptop; `benchmark.yml` uses `6g` and the runner is the binding constraint |
| `require-datapath` | `512` | `512` aborts unless the probe says full width; `any` records and continues |
| `expected-cpu` | `''` | `benchmark.yml`'s input verbatim, as documentation and for a deliberate repeat on one model |
| `only` | `''` | the driver's `--only` regex, for a partial re-run |
| `create-commit` | `false` | `benchmark.yml`'s semantics and its rebase-retry push |
| `probe-only` | `false` | run the gate steps and stop - this is what makes re-dispatching cheap |

**The steps, in the order that makes a miss cheap.**

1. Checkout.
2. **Install JDK 25 only** - `setup-java`, cached, seconds - because the
   gate below needs it and nothing else yet does. `Canary.java` imports
   `jdk.incubator.vector` and is run with `--add-modules
   jdk.incubator.vector`, so the runner's preinstalled JDK cannot be assumed
   to serve it. Putting this before the gate rather than after is the
   difference between a gate that runs and one that fails for the wrong
   reason.
3. **The gate, before any build.** Record the CPU model and the `avx`/`sve`
   flags as `benchmark.yml` does; fail on an `expected-cpu` mismatch; then
   run `dev/varka_canary/Canary.java` at `-XX:MaxVectorSize=32` and `=64` and
   abort when `require-datapath` is `512` and the ratio is below a threshold
   this PR fixes (the laptop reads 1.00 double-pumped against a nominal 2x;
   the threshold wants to sit well clear of both, and the first dispatches
   say where). Everything after this step is expensive; everything in it and
   step 2 is seconds. `probe-only` returns here, after also printing
   `free -g`, `nproc` and the runner label for 11.2.1.
4. Install JDK 17 beside it, since the surface runs stock on both and
   `setup-java` keeps each version's `JAVA_HOME` under its own variable.
5. Cache and download `spark-4.2.0-bin-hadoop3`, verified against its
   published checksum.
6. Build the fork's assembly, the bench jar and the engine jar. The engine
   jar is not optional and its absence is silent: `ISSUES.md` records that a
   distribution with Varka on and no engine jar falls back on every batch
   with a `ClassNotFoundException` in the log and measures the row engine
   under the kernel's name. The shell driver already builds and passes it
   with `--driver-class-path`; the workflow must not route around that.
7. Run `dev/varka_bench_surface.sh` over the four distributions in the order
   3.1 fixes, Varka last, with `--force` and the reason recorded (11.4), and
   with `--driver-memory` and `--rows` from the inputs rather than the
   script's laptop defaults of `16g` and 500M.
8. Tar the changed files and, under `create-commit`, push them with
   `benchmark.yml`'s rebase-retry loop. Always upload the artifact, so a run
   that fails the fixed-share rule still leaves its evidence.

**What the file must say that the laptop's does not.** The provenance block
already carries `datapath`, `MaxVectorSize`, the CPU flags and the CPU model.
(B) adds the runner's identity - the workflow run id and the runner label -
so a committed file names the dispatch that produced it and a reader can go
back to the log. That is the CI counterpart of `host: aqua` in the laptop's
files.

### 11.4 The canary, which is calibrated to the laptop

`dev/varka_bench_canary.sh` compares the machine against a *recorded state*
in `dev/varka_canary/baseline-<host>.txt` and exits 2 when there is none for
this host - which is what will happen on every runner, since the hostname is
per-dispatch - and the shell driver refuses to run when it fails;
`dev/varka_bench_surface.sh` also refuses a load average over 1.0. Neither
gate means on a shared CI VM what it means on the laptop, and the honest
handling is not to weaken them but to pass `--force` and record why: the
file's `canary` line then reads `OFF (CI runner)` rather than `ok (...)`,
which tells a reader exactly how
much the absolute numbers are worth. The *ratios* are what (C) quotes, and
they are sound for the reason 2.21's band work established - both arms of a
comparison sit in one run, sharing a JVM, a layout and a clock - except that
here the four distributions are four *processes* on one machine, which is
weaker than one process's A/B and is the reason the fixed-share rule and the
executor-time table exist.

A second canary calibrated to the runner is possible and is deliberately out
of scope: it would have to be recalibrated per CPU family, and the census
says there are at least four.

### 11.5 Predictions, registered before the run

Section 6.1 registered predictions for (A) and 9.2 scored them, two of four
missed. (B) gets the same treatment, and its first prediction is the one that
decides how much this PR was worth.

1. **The ratios grow against the laptop's, but less than the absolute rates
   do.** `SPECIES_PREFERRED` is 16 int lanes on a double-pumped Zen and on a
   full-width Xeon alike - both report 512-bit registers - so the *lane count
   does not change* and the emitted kernel is the same. What changes is
   whether the two halves are pumped or one operation issues. Stock Spark's
   row engine is unaffected either way, so Varka's rise should carry straight
   into the ratio.
2. **Unless the surface is memory-bound, in which case almost nothing moves -
   and that is the finding.** At a row count large enough to satisfy the
   fixed-share rule the table does not fit in cache, and a kernel that is
   waiting on memory does not care how wide its ALU is. If the full-width run
   reproduces the laptop's ratios inside the band, then "on a 512-bit
   datapath" was never the interesting property of this measurement, row 62's
   title oversold it, and (C) should say so rather than quietly present the
   numbers as a wider machine's. The executor-time tables and the per-row
   nanoseconds are what separate the two cases; the prediction is that at
   least the cheapest shapes - `date_add`, the comparisons - are bound by
   memory and move least.
3. **The same entries fuse.** Fusion is a compile-time decision over the IR
   and has nothing to do with the vector width, so all the entries that fused
   on the laptop must fuse on the runner. Any difference is a bug rather than
   a measurement, and the driver already fails the run on an expected-fused
   entry that is residual.
4. **The fixed share is worse than the laptop's.** Fewer rows fit, and the
   job's fixed cost does not shrink with them. The prediction is that the
   worst row lands between the laptop's 4.5% and the 5% rule, and the run
   fails if it does not - which is the outcome 11.2.1 exists to anticipate
   rather than discover at the end of a six-hour job.

### 11.6 Tests and verification

* A `probe-only` dispatch that lands on a known double-pumped machine must
  fail with the ratio in the log, and one on a full-width machine must pass.
  Both are observations of real dispatches, recorded in this section.
* `dev/varka_quote_check.py` over the committed files, which already reads
  this directory's glob.
* `dev/varka_bench_diff.py --table` against the laptop's files: the *shape*
  of the comparison must survive the machine change even where the rates do
  not, and an entry that fuses on the laptop and is residual on the runner is
  a finding rather than a number.
* The workflow's own yaml linted the way the repo lints the others.

### 11.7 Risks

1. **The runner cannot hold the rows.** 11.2.1, checked first; the driver
   fails rather than publishing an overhead-dominated rate.
2. **The pool never gives a full-width machine.** 11.2.2; the probe makes a
   miss cost minutes, and the escalation is a cost decision. *Settled by 11.9:
   it does, one runner in eighteen, an AMD EPYC 9V45 reading 1.99.*
3. **The build and the four runs blow the job's 6-hour limit.** The fork's
   assembly dominates and `probe-only` stops before it, so this risk is
   deliberately *not* covered by 11.2's dispatches: it needs one build-only
   dispatch of its own, whose number goes in 11.2.1 beside the memory. Four
   distributions at the laptop's per-run cost is the other half of the sum,
   and the surface's tables give the shape of it - 52 entries as of 12 September
   2026, not the 39 the committed laptop files carry, which is itself a reason
   to read the count off `Surface.ENTRIES` rather than from a document. Caching is why the
   download and the build are separate steps. *Settled by 11.10, which gives
   the build and the run a six-hour budget each instead of one between them -
   and, because 11.9's hit rate made the build-only dispatch this risk asks
   for unaffordable on the gated path, lets it run on any machine.*
4. **A committed file looks like the laptop's but is not comparable.** The
   `canary: OFF (CI runner)` line and the runner identity are what stop a
   reader taking it for one; (C) must quote the machine beside the number.

### 11.8 Sequencing

**Corrected 11 September 2026, while building it.** This list read as though
the dispatches could precede the PR. They cannot: `workflow_dispatch` fires
only for a workflow file that is on the repository's **default branch**, so a
new workflow is not dispatchable from the branch that adds it. The fork's own
default branch is a copy of `apache/spark` rather than this project's master,
and `vecbricks/varka` is where Actions run for this repository, so the
workflow has to land on `vecbricks/varka` `master` before it can be run once.

That splits step 1 in two and is the reason the workflow ships with
`probe-only` defaulting to **true**: the first dispatches after it merges are
gate-only, they cost minutes, and nothing expensive can run by accident while
the numbers 11.2 needs are still unknown.

1. The workflow, with `probe-only`, merged - it is inert until dispatched.
   **Done, PR #180.** The probe it gated on was wrong; **fixed in PR #181**,
   and 11.9 is that finding.
2. A handful of `probe-only` dispatches: 11.2.2's hit rate, and 11.2.1's
   memory and core count, recorded here. **Done:** 4 cores, 15 GiB, one
   qualifying runner in eighteen, the census in 11.9.

**Steps 3 to 6 restated, because 11.10 changed their order.** The old step 3 -
one dispatch to price the build against the six-hour limit - is gone: it needed
the same one-in-eighteen machine as the real run, so it cost what it was meant
to de-risk. The job split replaces it, and the two questions that do *not*
depend on the machine are answered first, on any runner:

3. The job split, merged, so a dispatch can build and measure separately.
4. One `build-only` dispatch at `require-datapath: any`. Warms the
   commit-keyed jar cache from wherever it lands, and times the cold build.
5. 11.2.1's row ladder at `require-datapath: any` with the cache warm: 1e7,
   5e7, 1e8 rows at one partition and 6g. Row capacity and cost per row are
   properties of the 4 cores and 15 GiB every runner shares, so this needs no
   luck - only the published numbers do.
6. The gated run: batches at `require-datapath: 512` and the row count step 5
   chose, `create-commit` off, until one lands on the EPYC 9V45. Each miss
   stops at the `gate` job in about a minute and never reaches the build.
7. The committed files once a run passes the fixed-share rule, this section's
   outcome, and 11.5's predictions scored.
8. (C) reads them.

### 11.9 The datapath probe was reading the frequency control

*Found on 11 September 2026, by building 11.3's gate and dispatching it.*

**The probe this section was designed around did not measure the datapath.**
`dev/varka_bench_surface.sh` built its `datapath` provenance line from
`Canary.compute` at `-XX:MaxVectorSize=32` and `=64`. That loop is a scalar
xorshift over one `long` with a self-dependency, and `Canary`'s own javadoc
says what it is: "a scalar multiply-add recurrence. Frequency-bound and
touches no memory, so it moves only if the clock does. **This is the
control.**" `MaxVectorSize` cannot touch it. The ratio is 1.00 on every
machine, and that is what nine dispatches showed - an AMD EPYC 7763 with no
AVX-512 at all, an EPYC 9V74, an **Intel Xeon 6973P-C with `UseAVX=3`,
`MaxVectorSize=64` and the full flag set**, and the development laptop, all
exactly 1.00.

Three things follow. The gate of 11.3 would have rejected every machine
forever, the full-width Xeon included. Every `datapath` line in the four
committed surface files is a scalar rate wearing a vector label. And
`SKILLS.md`'s "This machine's AVX-512 is 256 bits wide" is *not* affected -
it rests on VARKA-43's op-count ladder at three widths, where 256 to 512 buys
0.95x, which is independent evidence and stands.

**The replacement, `dev/varka_canary/Datapath.java`.** Eight independent
`IntVector` accumulators, register-resident, no loads or stores, reporting
**lanes per nanosecond** rather than operations per second - which is the
whole trick. A full-width unit retires a 512-bit operation in the time a
256-bit one takes, so doubling the species doubles the lanes per nanosecond; a
double-pumped unit takes twice as long for twice the lanes and the rate is
flat.

`Canary.compute` is left alone rather than fixed, because it is the frequency
control `dev/varka_bench_canary.sh` compares a machine against and every
committed `baseline-<host>.txt` is calibrated to it.

**It validates in both directions on one machine**, which the old probe never
could. On the development laptop:

| transition | lane-ops/ns | ratio |
|---|---|---|
| 128 -> 256 bits | 67.6 -> 136.3 | **2.02x** |
| 256 -> 512 bits | 136.3 -> 155.6 | **1.14x** |

The first is the positive control - the probe *can* see a doubling when the
datapath really doubles - and the second is the negative one, agreeing with
VARKA-43's independent 0.95x. The gate now takes the 128-to-256 reading on
every dispatch and fails when it is under 1.50, because a probe that cannot
see a doubling on the machine in front of it has no standing to pronounce on
512 bits either.

**What the fixed probe found on the pool.** Twenty-four dispatches on 11
September 2026, immediately after the fix merged; eighteen reached the probe,
the other six having been rejected first by an `expected-cpu` filter. The
control reads 2.00 to 2.04 on every one of the eighteen - which is the positive
control this section said had never been observed away from the laptop, and it
is now observed on five CPU models from four families. The 512-bit reading then
splits the pool three ways, and the third way was not anticipated:

| CPU | runs | flags | `MaxVectorSize` | 256:128 control | 512:256 |
|---|---|---|---|---|---|
| AMD EPYC 7763 (Zen 3) | 8 | no `avx512` | 32 | 2.00 - 2.01 | **1.00** |
| AMD EPYC 9V74 (Zen 4) | 3 | no `avx512` | 32 | 2.00 | **0.91 - 1.00** |
| Intel Xeon Platinum 8573C | 4 | full `avx512` set | 64 | 2.00 - 2.03 | **1.34 - 1.36** |
| Intel Xeon 6973P-C | 2 | full `avx512` set | 64 | 2.00 - 2.01 | **1.33 - 1.35** |
| AMD EPYC 9V45 (Zen 5) | 1 | full `avx512` set | 64 | 2.04 | **1.99** |

The Xeon 6973P-C row is the same machine 11.9's opening paragraph lists among
the nine the broken probe read 1.00 on. It is 1.33 with the fixed one, which is
the cleanest demonstration available that the old reading carried no
information about the hardware at all.

**So the answer is yes, and it is an AMD.** The EPYC 9V45 reads 1.99: a
512-bit integer add costs exactly what a 256-bit one costs, so twice the lanes
arrive in the same time. That is the machine this task needs, and it is in the
pool.

**"Full width" is not a yes-or-no property, which is the finding.** The two
Xeons have the entire AVX-512 flag set, `UseAVX=3` and `MaxVectorSize=64`, and
still read 1.33 to 1.36 rather than 2. The reason is issue ports rather than
datapath: Intel's server cores retire 256-bit integer vector ops on three
ports, and a 512-bit op consumes a fused pair plus the third, so the issue
rate falls from three per cycle to two while the lanes double - 2 x 2/3 =
1.33 predicted, against 1.33 and 1.35 measured on two different Intel
generations. Zen 5 has four 512-bit-native vector pipes and loses nothing,
which is the 1.99. A model-name allowlist would have called all three of these
"full width" and been wrong about two of them by a third; the probe
distinguishes them because it measures the quantity the benchmark actually
spends, which is lanes retired per unit time and not vector register width.

That also vindicates 11.3's threshold. 1.50 sits between 1.35 and 1.99 with
room on both sides, so the gate admits the Zen 5 and rejects the Xeon without
either being a near miss.

**What this costs in dispatches, and what had to change because of it.** One
machine in eighteen. Each miss costs about a minute, so the re-dispatch loop
11.3 was designed around still works - but the hit rate is low enough that the
lucky runner cannot also be asked to do a cold build inside one six-hour job,
because a single overrun forfeits both the run and the cache. That is what
11.10 splits apart.

**And while the gate was being fixed: are the runner's cores the same?** The
development machine's are not: four Zen 5 at 5158 MHz and eight Zen 5c at
3289 MHz in two L3 clusters, which is exactly why `dev/varka_bench_regen.sh`
and `dev/varka_bench_surface.sh` pin to `0,12,13,14,15,1,2,3` - the eight SMT
threads of the four fast cores. A measurement spread over two core types is
two measurements averaged, so a runner that is heterogeneous has to be pinned
too, and the gate now reports `lscpu --extended`, the distinct core maximum
frequencies and the number of distinct core types on every dispatch. The first
eight dispatches reported 4 cores and 15 GiB but not the core layout, which is
what this adds.

### 11.10 The build and the measurement no longer share a job

*Written 11 September 2026, from 11.9's hit rate.*

11.3 shipped one job - gate, then build, then run - on the reasoning that a
miss is cheap because the gate is seconds. That reasoning is sound and 11.9
confirms it: a miss costs about a minute. What it did not anticipate is that a
**hit** would be scarce. At one runner in eighteen, the single job asks the
machine we waited eighteen dispatches for to complete a cold
`build/sbt package` of the whole fork plus two Maven modules *and* the
four-distribution surface run inside one six-hour limit. An overrun is
cancelled, the `actions/cache` post-step never saves, and the next lucky runner
starts from nothing - so the risk is not "one wasted run", it is "no run ever
completes".

11.8's step 3 was meant to price this with one cheap dispatch. It cannot: the
pricing run needs the same lucky machine as the real one, so pricing it costs
what doing it costs.

**The observation that dissolves it: the build is machine-independent.** Only
the measurement needs the Zen 5. So the job splits three ways, which is the
shape `.github/workflows/benchmark.yml` already uses:

* `gate` - the probe block, unchanged, about a minute. Its CPU model and three
  probe readings become job outputs, so the later jobs record them in the
  provenance without re-running anything.
* `build`, `needs: gate` - an `actions/cache` entry keyed on the commit SHA
  over `assembly/target/scala-2.13/jars` (what `bin/spark-class` resolves
  `SPARK_JARS_DIR` to), the bench driver jar, the engine jar and
  `conf/log4j2.properties`. A hit does nothing; a miss builds and saves. The
  SBT/Maven/Coursier cache stays underneath it as the second level.
* `measure`, `needs: build` - the surface run, the tar, the upload and the
  optional commit, unchanged.

Two consequences, one per open question. Each job carries its own six-hour
budget, so the surface run no longer shares a clock with the build - 11.7's
risk 3 is answered structurally rather than by a measurement that could not be
afforded. And a `build-only` dispatch with `require-datapath: any` warms the
SHA-keyed cache from *any* runner, so by the time a gated dispatch lands on the
Zen 5 it pays a cache restore and the run, not the build. The lottery ticket
buys the cheap half.

**What is deliberately not done here.** The row count and the wall time per row
are properties of the 4 cores and 15 GiB that every runner in the pool shares,
so 11.2.1's ladder runs at `require-datapath: any` and needs no luck at all.
Only the published numbers wait for the Zen 5.

#### 11.10.1 What the split was worth, measured - and where its argument was wrong

*The dispatches this section authorised, run the same day.*

**The six-hour argument above is weaker than it was stated.** The cold build is
**9m48s**, not the hour or more the risk assumed, so a single job would have
left more than five and a half hours for the surface run and the limit was
never close. That reasoning should not be relied on again without the number
beside it.

Two things the split *is* worth stand unaffected, and one of them is why it had
to happen at all:

1. **It fixed a build that could never have run.** The single job installed JDK
   17 after JDK 25, and `actions/setup-java` leaves `JAVA_HOME` at whatever it
   installed last, while this tree compiles with `--release 25`. No dispatch had
   ever cleared the gate to find out. That bug, not the six-hour limit, is what
   the restructure actually bought.
2. **The warm build is 52 s against 9m48s.** Every gated dispatch after the
   first pays a cache restore rather than a build, which at one qualifying
   runner in eighteen is the difference between a cheap lottery ticket and an
   expensive one.

**And it exposed two more bugs, both of the same kind: work thrown away because
a step failed on purpose.** The surface job *is designed to fail* when a run
misses the fixed-share rule, and both `actions/cache`'s post-step save and the
steps after a failed one are skipped on a failing job. So the first calibration
dispatch ran the whole surface for twenty-one minutes and uploaded nothing -
though the workflow's own comment promised that "a run that fails the
fixed-share rule still leaves its evidence" - and re-downloaded the 400 MB stock
distribution it had just fetched. The fix is `!cancelled()` on the tar and
upload steps, and splitting the stock-Spark cache into restore plus an explicit
save placed directly after the download rather than left to a post step.

The general lesson, which is worth more than the two fixes: **a step that fails
by design makes every later step and every post step conditional, so anything
that must survive that failure has to say so explicitly.** A comment asserting
it is not enough, and this one asserted the opposite of what the file did.

### 11.11 Sharding the surface across runners

*Written 12 September 2026, from the ladder of 11.2.1 and a suggestion that the
pool's parallelism was being left on the table.*

**The bind.** The fixed-share rule wants about 5e8 rows; the measured ladder
puts the whole surface at roughly eight hours there, against a six-hour job
limit. One dispatch cannot do it, and lowering the rows to fit fails the rule
the numbers are published under.

**What the runner was actually doing.** `--master local[1]`, by design, so the
benchmark uses one core and three of the runner's four sit idle. The
parallelism to reach for is therefore not a wider executor - that would change
what the committed laptop files measured - but more runners. GitHub allows
twenty jobs at once.

**The axis, which is the whole decision.** Sharding by *distribution* is the
obvious four-way split and it destroys the deliverable: the headline is Varka
against stock, and those two arms would then have been measured on different
machines. Sharding by *entry* - each shard runs all four distributions over a
subset of the entries - keeps every ratio inside one machine, and spends the
heterogeneity only on comparisons between entries, which is the same
heterogeneity Spark's own committed files already accept by recording the CPU
in each header.

**Measured shape at 1e8 rows**, from the run of 11 September:

| arm | wall |
|---|---|
| `spark-4.2.0-jdk17` | 18m55s |
| `spark-4.2.0-jdk25` | 17m09s |
| `varka-off-jdk25` | 16m44s |
| `varka-jdk25` | 5m34s |

About 45 s of each stock arm and 25 s of the Varka arm is JVM start plus the
cached table, and **that part does not shard**: a shard runs fewer queries but
still builds the whole table, four times. So the useful number of shards is
bounded by when the per-shard table build starts to dominate, not by how many
runners are free.

**The stride, not a slice.** Shard *i* takes entries *i*, *i+N*, *i+2N*. The
surface is ordered by expression family, so contiguous blocks would hand one
shard every calendar extraction and another every comparison, and their run
times would differ several-fold - and the wall clock is the slowest shard, not
the mean. A stride mixes the families so the shards finish together.

**The index, not a regex.** `--only` already existed and could have expressed
shards as names. It would have been wrong: the names would be written by hand,
and a surface that gains an entry - as it did, from 39 to 52 - would leave the
hand-written shards quietly covering the wrong set with nothing to notice. An
index modulo the list length cannot drift from the list it indexes. The price
is that every shard of one run must come from one commit, which
`dev/varka_bench_merge.py` checks rather than assumes.

**What the merge refuses.** Shards of one label must agree on commit, rows,
partitions, shard count, surface entry count, CPU model, `MaxVectorSize` and
datapath ratio; their indices must be exactly 0..N-1, once each; and the
entries must union to the whole surface. Host and date are exactly what
sharding trades away, so they are recorded per shard in the merged header
rather than collapsed into one line that would be false. A mis-joined file is a
published number nobody can reproduce, and it has no other symptom - which is
why this is a program with exit codes and not a `cat`.

**The dispatch economics.** Each shard is its own dispatch on its own random
runner, so no single round fills N shards at a hit rate of one in eighteen.
Re-dispatching only the outstanding ones does: twenty parallel dispatches
return roughly one qualifying runner per round at about a minute per miss, so
eight shards take on the order of eight rounds.
`dev/varka_surface_shards.py` is that loop, with its state in
`.git/` so it is interruptible.

### 11.12 The fixed-share rule passed a run that measured nothing

*Found on 12 September 2026, by disbelieving a success.*

**The 2e8-row dispatch succeeded, with a better fixed share than any run before
it, and its numbers were meaningless.**

| rows | `date_add(d, 3)`, Varka arm | worst fixed share | the rule says |
|---|---|---|---|
| 1e8 | **854 M rows/s** | 15.4% | fail |
| 2e8 | **72.6 M rows/s** | **1.9%** | **pass** |

An eleven-fold collapse in throughput, reported as an improvement. The
committed laptop file reads 1811.6 M/s for the same entry, so a 2e8 file would
have published a number 25 times too small as Varka's date_add rate.

**What happened.** The log carries
`WARN MemoryStore: Not enough space to cache rdd_4_0 in memory!` 392 times, and
the 1e8 log carries it zero times. The next line says where it went:
`Persisting block rdd_4_0 to disk instead`. So the measurement was not a
recompute rate, as this section first said - it was the runner's **SSD read
rate**, served out of a cache that was still, as far as everything downstream
could tell, a cache.

**Why the rule not only missed it but was fooled by it.** The rule is
`(wall - executor) / wall`, and it exists to fail a job too *small* to amortise
its driver overhead. A job whose table does not fit has an enormous executor
time, so its constant driver cost becomes a negligible fraction of it - the
failure does not merely evade the rule, it *satisfies* the rule, and the worse
it gets the better it looks. A rule with only one side is a rule that can be
walked around from the other.

**Three things that were not the cause, each checked.** It is not the driver
heap: 6g, 8g and 11g produce 392, 389 and 389 warnings, identically. It is not
the stock arms: every warning in all three runs is in `varka-jdk25`, the
Arrow-cached arm. And it is not a scaling law - the "2.51x wall time per
doubling of rows" recorded during the run, and used here to project an
eight-hour surface at 5e8, was the onset of this cliff and nothing more. That
projection is withdrawn.

**What it actually is: a per-block limit, not a per-byte one.** With
`--partitions 1` the whole table is a single block, and Spark declines to cache
one block larger than the unrolling memory available, however large the heap -
which is exactly why raising `--driver-memory` did nothing. The table is also
twice the size this section had assumed: VARKA-67 added three year-month
interval columns, so it is six four-byte columns, 24 bytes a row, and 4.8 GB at
2e8. More partitions is the lever, and 11.2.1 listed it first among the
fallbacks before any of this was measured.

**The first fix: stop offering the disk.** `spark.catalog().cacheTable(name)`
takes Spark's default storage level, `MEMORY_AND_DISK`. That default is right
for a workload, which should finish rather than fail, and exactly wrong for a
benchmark, which should fail rather than measure something else - it is what
converted "this table does not fit" into "this works, at storage bandwidth".
The driver now asks for `MEMORY_ONLY`, with `--storage-level` to override, and
records the level in the provenance beside the residency. It costs nothing in
comparability: a run that fits behaves identically under either level, so the
committed laptop files stay directly comparable.

It also makes *cached* mean one thing. Under `MEMORY_AND_DISK` a table wholly
on disk still reports every partition cached, which is why the guard below
needs a separate `diskSize > 0` clause; under `MEMORY_ONLY` the partition count
alone is a complete residency statement.

**The second fix, which is a rule and not a bigger number.**
`DateSurfaceBenchmark` now refuses to write a file whose cached table is not
entirely resident, checked once immediately after the table is materialised so
a misconfigured run costs a minute rather than three hours, and records the
residency in the provenance so a reader can see it rather than trust it.
`--allow-nonresident-cache` exists for a deliberate exception. The two rules now
bracket the row count from opposite sides: too small fails the fixed share, too
large fails residency, and a file can only be written between them.

The guard is kept even though the storage level removes the disk path, because
the two rule out different things: the level rules out that one substitution,
and the guard rules out the rest - a partially cached table, or one evicted
later under execution pressure. A level is a property that cannot be forgotten;
a guard catches what the property does not cover.

**The general lesson, which is why this is a section and not a commit message.**
A benchmark guard that can only fail in one direction will eventually be
satisfied by the failure in the other, and the more complete that failure the
more comfortably it passes. Ask of every such rule what its own violation looks
like from the far side - here, "what does a run that is far too big look like to
a rule that catches runs that are too small?" - and if the answer is "healthy",
the rule needs a partner before it is trusted.

### 11.13 The surface cannot show the datapath, and the chains are the answer

*Opened 12 September 2026, from a question about what is actually being timed.*

**Most of the surface is bound by memory bandwidth, not by arithmetic, and a
wider vector datapath cannot help it.** The committed laptop file has
`date_add(d, 3)` at 0.5 ns/row, moving four bytes in and four out - about
15 GB/s, which is single-core DRAM speed on that machine. One add per eight
bytes. Roughly a third of the 52 entries are single calls in that regime.

That is a problem for this task's whole premise. The point of gating on a
full-width 512-bit runner is to show what the width buys; on a bandwidth-bound
kernel it buys nothing, however genuine the 1.99 the probe reads. VARKA-43
measured the width with an op-count ladder precisely because that is
compute-bound. The surface is a different instrument and answers a different
question - Varka against stock Spark, where it reads 18x to 25x, which is the
milestone's headline and is not in doubt.

**So the chains are a second benchmark, not a change to the surface.**
`Chains.ENTRIES` composes the same operations until the arithmetic dominates:
twelve entries at **293 to 483** emitter ops, against 34 for `year(d)` and 64
for `weekofyear(d)`, every one checked to fuse before it was added.

**Most of them were impossible until VARKA-93 landed.** A chain that shifts a
date by a column of days and then by a column month count declined, because
VARKA-52's guard promised the whole narrowed range and the shift above it had
none left - `dayofyear(add_months(last_day(date_add(d, i)), 1) + ymy)` is the
shape that opened VARKA-93, and it is entry 7 here at 332 ops. Re-arming the
guard is what makes this list what it is; the first version, written against
what fused before, was 163 to 304 ops.

**And the deeper list is the better demonstration of the primary claim, not
only of the datapath.** Stock Spark's generated code pays its per-row costs at
every link of a chain, while the kernel fuses the whole chain into one
vectorised loop and shares the civil-from-days prefix across the calendar nodes
reading one date (VARKA-32). So the ratio against stock should *grow* with
depth, and the surface's 18x to 25x is the floor of what the engine is worth
rather than the headline.

**One family still cannot sit above a re-arm.** `weekofyear` and `YEAROFWEEK`
shift by the Thursday rule's literal three days, and VARKA-93 re-arms only a
runtime-valued shift - so `extract(YEAROFWEEK FROM add_months(last_day(
date_add(d, i)), i) + ymy)` declines at `[-6156431, 12144130]`, three days past
the floor. Whether a small literal shift should re-arm - guarding it would
decline almost no batch, unlike a shift of twenty million days - is a follow-up
to VARKA-93 rather than fixed here. (It is not in that plan's 9.5, which records only the double guard; this paragraph is its record.)

**The week entry sidesteps it by spelling the day shift as an interval**, and
the difference is the saturation rather than the guard: a column day offset
makes `dayRange` answer the whole of `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]` on
VARKA-52's guarantee, leaving the shifts above it nothing, while a month shift
over a plain column keeps the interval additive from the contract range. So it
lowers with no `GuardedDay` in it at all and is admitted on `admitCalendar`'s
first case.

**An IR census of the twelve, since the claim above invites it.** Eleven carry a
`GuardedDay`; the `quarter`/`next_day` entry carries two. Only the week entry
carries none. So "most of these were impossible before VARKA-93" understates it -
it is all but one - and the eleven are the regression set for guard placement,
not the week entry.
`DateChainBenchmark` runs the chains through the surface's driver - same table, same
harness, same residency and fixed-share guards - and writes
`DateChain-<label>-results.txt`. The surface keeps its coverage job and its
spelling; the chains answer the width question.

**And they mix the three types, which is the second reason to have them.**
Varka covers DATE, INT and the year-month interval, all int32 in one lane, and
a benchmark of dates alone both understates that to a reader and exercises less
of the compiler. All twelve carry a date column, an int column and an
interval column in one expression; one produces an interval rather than
consuming one. (This read "eight of the twelve" until 12 September: eight
was the count for the *first* list, and neither this paragraph nor
`Chains`'s own javadoc was recounted when VARKA-93 replaced it. `ChainsTest`
now asserts the count against `ENTRIES.size()` rather than against a
written-down number, so the two cannot drift apart again.) Counted over
column references rather than substrings - the first version of the test
that guards this counted the literal in `* 12` as int coverage and so passed
a list where only four entries touched the int column at all. A literal
folds into the kernel; a column is loaded and vectorised, and only the
second demonstrates anything.

**A tooling bug found while choosing them, which invalidates earlier op
counts.** `dev/varka_emit.sh` resolved attributes and functions but never ran
type coercion, so `d + ym` stayed an `Add` over a date and an interval instead
of becoming `DateAddYMInterval`, and the tool reported **declined** for it -
for an expression `Surface` has been timing with `expectFused` for weeks. Every
operator-spelled expression was affected, which is every date/interval
arithmetic shape VARKA-67 added. The fix resolves through a `LocalRelation` and
`SimpleAnalyzer`; any op count taken from this tool for an operator expression
before 12 September 2026 should be re-taken.

**Coverage gaps found the same way, and not fixed here.** `datediff(d2, d) * i`
and `i % 20` decline - an int multiply by a column and an int remainder -
although multiplying by a literal is fine, as `CAST(month(d) AS INTERVAL YEAR)
* 3` shows. `make_ym_interval(i, i)` declines where
`make_ym_interval(year(d), month(d))` fuses. These two belong in the
milestone's task table rather than in this task, and they are why several
natural mixed-type spellings are absent from the list.

A third was listed here and is **closed**:
`dayofyear(add_months(last_day(date_add(d, i)), 1) + ymy)` declined when this
paragraph was written and fuses at 332 ops since VARKA-93, which is why it is
entry 7 of `Chains` - see 11.13's opening. The line stayed after the task
landed, so a reader turning this paragraph into follow-ups would have filed a
coverage gap for an expression already being benchmarked.

**Registered prediction, to be scored against the first committed chain file.**
From the 1e8 dispatch of 11 September, whose results were never committed:
the per-iteration fixed cost is near 18 ms, and fitting `year(d)` at 1.5 ns/row
and `weekofyear(d)` at 2.1 ns/row against their op counts gives roughly
0.02 ns per op over a memory floor near 0.8 ns. That predicts:

1. every chain entry above **3.6 ns/row** at 1e8 rows on a runner. The model
   above puts the shipped list, 293 to 483 ops, at **6.7 to 10.5 ns/row** - so
   the margin is wide, and the prediction to score is that range and not the
   3.9-to-5.2 one this section carried while the list was 152 to 218 ops. A
   scorer reading the old range against the shipped file would see a large
   systematic under-prediction and call the model wrong;
2. therefore a worst fixed share **under 5%** at 1e8 rows, where the surface
   needed 5e8 - so one dispatch, resident table, no sharding;
3. and a **measurable 256-to-512 difference** on these entries where the
   surface's lightest rows show none.

The third is the one that matters and the one most likely to be wrong: it
assumes the kernels are issue-bound rather than latency-bound on their
dependency chains, and a deep chain of dependent operations may be neither.
If it fails, the finding is that Varka's date kernels do not benefit from
width at all, which would be worth knowing and worth publishing.

### 11.14 Explicitly out of scope

A self-hosted runner; a CI-calibrated canary; changing the driver, the
surface or the shell driver, except where 11.2.1 forces the row count or the
partition count; and the 128-bit companion run, which is milestone 5's VARKA-92 and wants a *narrower* machine than this one.

### 11.15 The gate gated a machine that did nothing

*Found 12 September 2026, by the provenance of the run it invalidated. Numbered
after 11.14 because 11.14 is cited by number elsewhere and renumbering it to keep
the out-of-scope section last would cost more than the odd ordering does.*

11.10 split this workflow into `gate`, `build` and `measure` so that the
one-in-eighteen runner would not have to spend its six hours on a cold build. It
did that, and in the same change it **silently destroyed the gate**.

Every GitHub-hosted job runs on its own fresh ephemeral VM. So a probe that runs
in `gate` measures the `gate` VM and says nothing whatever about the machine
`measure` is handed. The chains calibration of 12 September (run 34707977257)
is the demonstration: `gate` passed on an **AMD EPYC 9V45**, full AVX-512 flag
set, 512:256 ratio **2.00**; `measure` ran on an **AMD EPYC 9V74**, flags `avx
avx2` only, `MaxVectorSize: 32`, ratio **1.00**. A 256-bit measurement produced
under a gate that had certified a 512-bit machine.

Worse, it was *labelled* with the machine it did not run on. The job's `name:`
interpolated `needs.gate.outputs.cpu`, so GitHub's UI read "Date surface on AMD
EPYC 9V45 96-Core Processor" across the whole run. The one place the truth
survived was the results file's own provenance, because
`dev/varka_bench_surface.sh` re-probes on the measuring machine and records
`datapath:`, `cpu:` and `MaxVectorSize:` in every file it writes. That habit -
a results file that carries the evidence for its own claims rather than
inheriting it - is what turned a wrong published number into a caught bug, and
it is the argument for keeping it everywhere.

**What makes this worth a section rather than a line.** The failure was not that
the gate was wrong about its machine; it was right. It was that the gate was
*asked the wrong question* and answered it correctly, which no amount of care in
the probe could have caught. It is the sixth mechanism in this task to produce a
plausible wrong answer - after a probe reading a scalar loop (11.9), a workflow
discarding its evidence (11.12), a rule satisfied by the failure it could not
see (11.12), a cache substituting a disk (11.12), and partitioning that appeared
to help while making things worse (11.11).

**And the naive repair would have been much worse than the bug.** Leaving `gate`
upstream of `measure` and adding a second probe inside `measure` would require
*both* VMs to be full-width for a `512` dispatch to produce anything - squaring a
one-in-eighteen chance into roughly one in three hundred, at a minute a miss.
The gate has to move, not multiply.

**The fix.** The probe is now one script, `dev/varka_datapath.sh`, rather than a
copy in the workflow and another in the shell driver, and it takes `--require`
so the same code is both the survey and the gate. It runs *inside* `measure`,
directly after the JDKs and before the 400 MB jar restore, so a miss costs
seconds. `gate` keeps its name only as the pool census that `stop-after: gate`
dispatches - it enforces nothing, is upstream of nothing, and never sees
`require-datapath`, because a census that aborts on the machines it is counting
cannot count them. `measure` is named for its inputs, never for a CPU: a job
name is fixed before any job runs, so the only CPU it could ever interpolate is
another machine's.

**What the invalidated run is still good for.** Everything except the datapath
question. Residency, fusion, the fixed-share rule and the ratio against stock
are properties of the row count and the expressions, so 11.13's predictions 1
and 2 are scored from it in 11.16; only prediction 3, the 256-to-512
difference, needs the re-run.

### 11.16 The chains measured, and the prediction that mattered was backwards

*Run 34707977257, 12 September 2026: 1e8 rows, one partition, 8g driver, on an
AMD EPYC 9V74 at 256 bits (see 11.15 - the gate certified a different machine).
64 minutes for four distributions over twelve chains. `create-commit: false`, so
the numbers below are from the run's artifact and are **not** committed; the
committed file waits for the re-run under a working gate.*

**The job-size rule is met, which is the whole reason the chains exist.**
`2.3 GiB in memory, 0.0 GiB on disk`, zero `Not enough space` warnings, all
twelve entries `kernel 60000 batches, fallback 0`, and a worst fixed share of
**3.2%** on the Varka arm against a 5% rule. At 1e8 rows, where the surface
needed 5e8 and no runner in the pool could hold it. Buying executor time with
arithmetic instead of rows worked exactly as 11.13 argued it would.

**11.13's registered predictions, scored.**

| | predicted | measured | |
|---|---|---|---|
| 1a | every entry above 3.6 ns/row | 13.6 to 20.5 | held |
| 1b | the shipped list at 6.7 to 10.5 ns/row | 13.6 to 20.5 | **failed, ~2x low** |
| 2 | worst fixed share under 5% at 1e8 rows | 3.2% | held |
| 3 | a measurable 256-to-512 difference | - | not scored, 11.15 |

1b failed in the safe direction: the model's 0.02 ns per emitter op over a
0.8 ns floor is about half the true per-op cost on this machine, so every entry
sits further above the fixed-share threshold than predicted, which is why 2
passed as comfortably as it did. `Chains.MIN_OPS` was set to 280 against a
computed break-even near 140 precisely so that a wrong model could not put an
entry under the rule, and that margin is what absorbed the error.

**The prediction that was not registered as one, and is wrong.** 11.13 argued
that stock Spark pays its per-row costs at every link of a chain while the
kernel fuses the whole chain, so *"the ratio against stock should grow with
depth, and the surface's 18x to 25x is the floor of what this engine is worth
rather than the headline."*

It is the ceiling. Measured:

| | Varka ns/row | stock 4.2 ns/row | ratio |
|---|---|---|---|
| chains, 12 entries, 293-483 ops | 13.6 - 20.5 | 122.6 - 229.7 | **8.6x - 11.1x** |
| surface, committed laptop file, 32 entries | 0.6 - 2.2 | 21.5 - 25.4 | 11.5x - 38.8x, median 21.6x |

and the trend *within* the surface points the same way: its cheapest entries
carry the largest ratios (`dayofweek(d)`, 38.8x) and its heaviest the smallest
(`add_months(d, i)`, 11.5x), heading straight for the chains' 9x to 11x.

The mechanism is not subtle once the numbers are in front of you. Stock's cost
per row is overhead plus work; Varka's is work over lanes. When the expression
is trivial the ratio is measuring Spark's per-row overhead and reads 38x. When
the expression is heavy the overhead washes out and the ratio converges on the
genuine compute speedup, which is **about 9x to 11x**. The argument in 11.13
was not wrong about the mechanism - the kernel really does fuse the chain and
really does share the civil-from-days prefix - it just ignored that the same
depth grows the numerator too, and grows it from a much larger base.

**This changes what the milestone should claim.** 9x to 11x on genuinely
compute-bound expressions is the defensible number and the more interesting
one: it is what the engine is worth when Spark's per-row overhead has been
amortised away and only the arithmetic is left. The 20x to 38x on single calls
is real, but it is largely a measurement of the row engine's overhead rather
than of vectorised arithmetic, and a reader who is told 38x and later measures
10x on their own workload will conclude the project oversold itself. Say both,
and say which is which.

*Caveat, and the reason this is not yet a finding.* The two rows of that table
come from different machines and different row counts - chains on a runner at
1e8, surface on the development laptop at 1e9. The within-surface trend is
same-machine and does support the conclusion, but a surface run on the
re-run's machine would settle it properly, and 11.17 should take one while the
gate is fixed anyway.

### 11.17 The closing measurement, on a machine the gate actually checked

*Run 34721687731, 13 September 2026: 2e8 rows, one partition, 12g driver, four
distributions over the twelve chains, 74 minutes. **AMD EPYC 9V45 96-Core**,
full AVX-512 flag set, `MaxVectorSize: 64`, datapath probe 2.01. Eighteen
dispatches for the hit, which is the rate 11.9's survey predicted to the
dispatch.*

**The gate is verified by agreement, not by assertion.** The probe that admitted
this job read 2.01 on the measure VM, and the `datapath:` line that
`dev/varka_bench_surface.sh` wrote independently into all four results files
reads 2.01 on the same VM. Under 11.15's defect those two numbers came from
different machines and disagreed 2.00 against 1.00; here they agree because
there is only one machine involved. A reader who doubts the gate can check the
committed files without leaving the tree.

**The numbers.** Varka runs the twelve chains at **6.9 to 11.2 ns/row** against
stock Spark 4.2's 68.2 to 143.9, and the table was fully resident - 4.6 GiB in
memory, nothing on disk, zero `Not enough space` warnings, all twelve entries
`fallback 0`.

| | |
|---|---|
| Varka against stock Spark 4.2 (best of JDK 17 and 25) | **9.4x to 14.2x**, median 10.2x |
| Varka against the same fork with the engine off | 9.5x to 14.2x, median 10.4x |
| worst fixed share on a Varka row | 3.5%, against the 5% rule |

The `varka-off` column agreeing with stock to within a few percent on every row
is the control this task's admission check asked for: the fork itself is not
what makes the difference, the engine is.

**The row count had to move, and 11.12 was wrong about why it could not.** The
first gated run (34714282714, same machine, same probe reading) failed the
fixed-share rule with five entries between 5.0% and 5.9%. Nothing was broken:
the table was resident and every entry fused. The full-width machine simply runs
these kernels about twice as fast, so per-iteration executor time fell to about
700 ms while the job's constant cost stayed where it was - and that constant is
**36 ms median, not the 18 ms** 11.13's model assumed, which is the third figure
in that model to come in low. The chains were sized to clear 5% at 1e8 rows on a
*256-bit* runner, and the benchmark was outrun by the improvement it exists to
demonstrate.

The fix was more rows, and here 11.12's finding blocked the obvious answer: it
records 2e8 as non-resident "at every partition count and driver memory tried",
11g included. A four-rung ladder on ordinary runners says otherwise - 1.5e8 at
8g and at 11g, and 2e8 at 11g and at 12g, all four fully resident with the Varka
arm holding its whole table and no runner reporting a shortage. **What changed
between the two is the storage level.** Those earlier attempts predate the
`MEMORY_ONLY` change, so they ran under Spark's default `MEMORY_AND_DISK`, which
does not refuse a block it cannot hold - it writes it to disk and carries on. So
11.12 measured a spill and read it as a capacity limit. 11.12 stands as the
record of the run it describes; its conclusion about 2e8 does not.

Choosing between the rungs was arithmetic on this machine's own per-entry fixed
costs rather than a guess: extrapolating each entry's measured constant against
more rows puts the worst entry at 4.0% at 1.5e8 and 3.0% at 2e8. 2e8 was taken
for the margin, and came in at 3.5% - half a point over the extrapolation, close
enough that the method is sound and far enough to justify not having chosen
1.5e8's predicted 4.0%.

**11.13's prediction 3, scored, and it is the interesting one.** The prediction
was a measurable 256-to-512 difference on these entries. There is one, and it is
much smaller than the lane count suggests. Comparing the 1e8 runs on the 9V74 at
256 bits and the 9V45 at 512 bits, Varka moves 2.07x - but so does everything
else, because the two machines differ by more than their vector width:

| arm | 9V74 to 9V45 |
|---|---|
| `varka-jdk25` (vectorised) | 2.07x |
| `varka-off-jdk25` (scalar) | 1.84x |
| `spark-4.2.0-jdk25` (scalar) | 1.78x |
| `spark-4.2.0-jdk17` (scalar) | 1.78x |

Three independent scalar arms agree within 0.06x, and that is the machine -
Zen 4 to Zen 5, clocks and IPC. Dividing it out leaves roughly **1.14x
attributable to the datapath**. 11.13 named this risk in the same breath as the
prediction: it assumed the kernels are issue-bound, and a deep chain of
dependent operations may be latency-bound instead. The evidence says latency,
and it is consistent with VARKA-43's ladder, which found the 256-to-512 step
worth nothing at all on a double-pumped laptop.

This is a cross-machine subtraction and should be read as one. The clean
experiment is the same machine at `MaxVectorSize` 32 and 64, which is
milestone 5's VARKA-92 and now has a much sharper question to answer than "does
width help": it has a number to confirm or refute.

**What this means for the milestone's public claim.** The defensible headline
from this run is **about 10x against stock Spark on compute-bound date
expressions**, on a machine whose datapath is verified in the file that carries
the number. 11.16's caution stands and is now measured on one machine rather
than across two: the surface's larger ratios are largely Spark's per-row
overhead, and this figure is what remains when that overhead has been amortised
away. Both are true; only one of them is what the engine's arithmetic is worth.

### 11.18 PR (C), the README, and the control it had to clear first

The README's benchmark section is rewritten from the committed files rather
than from the micro-benchmarks it used to quote, and it leads with the number a
reader will reproduce rather than the largest one in the tree.

**Section 6's prediction 4, scored, because (C) is where it falls due.** It said
the fork with Varka off would sit within 20% of stock Spark 4.2.0 on JDK 25 on
every row, "and if a row is further apart, that row is explained before the
README quotes it". Checked across all fifty (entry, shape) pairs of the surface:
**forty-nine hold**. The one that does not is `trunc(d, 'QUARTER')`, where the
fork's own row engine is 38% faster than 4.2.0's - 26.1 ns against 35.9 - which
9.4 had already flagged as needing to be read before (C) quoted it. The cause is
upstream: the fork tracks Spark master, whose `truncDate` differs from 4.2.0's.
So the row's 32.6x against stock overstates Varka by that margin, and the README
quotes it at **23.7x**, against the fork's own row engine, with the reason given
in the text. No other row needed the treatment.

**What the section says, and why it is shaped that way.** It opens by
separating two questions rather than choosing between them, because 11.16 and
11.17 established that the answer depends entirely on how much arithmetic a
query does: about 10x on the chains, up to 38.8x on a single call. Both are
honest and only one generalises, so the section states the mechanism -- stock
pays overhead plus arithmetic, Varka pays arithmetic over lanes -- and tells the
reader to carry away 10x. Publishing the larger figure alone would have been
defensible from the files and would have set up every outside reader who
measured their own workload to conclude the project oversold itself.

It also keeps the three losses in the table, at 0.46x to 0.59x, with the
one-sentence version of VARKA-78's cause and the note that the fix is written but
not yet re-measured on a runner.

**The reproduction guide is the CI job's commands, checked against it rather
than reconstructed.** Three corrections came out of that comparison: the sbt
build needs `-Pscala-2.13 -Phive -Phive-thriftserver` and not a bare `package`;
the log4j step belongs in it; and the guide has to warn that a distribution with
Varka on and no engine jar measures the row engine under the kernel's name. The
driver's guard against that last one is also stronger than the first draft of
the guide claimed - it fails on *any* fallback batch in a row expected to fuse,
not only on a total decline, because a partial decline publishes a blended rate
that looks like a kernel rate.

**Still open after this**: the 128-bit companion (VARKA-92), the same-machine
width confirmation this task's 1.14x invites, and VARKA-78's runner dispatch,
which is what turns the three losses into the wins the laptop already shows.
