/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.benchmark

import scala.concurrent.duration._

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.internal.config.UI.UI_ENABLED
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.{VarkaColumnarRule, VarkaColumnarToRowExec, VarkaProjectExec}
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}

/**
 * End-to-end throughput benchmark (Task 7): rows/sec for `date_add` / `date_sub` / `datediff`
 * over ~2M Arrow-cached date rows with Varka on (SIMD kernels, [[VarkaColumnarRule]] fused into
 * [[org.apache.spark.sql.execution.VarkaColumnarToRowExec]]) vs the standard Janino row path, plus
 * a mixed projection that is not Varka-eligible (fallback) to show the non-fused path has no
 * regression.
 *
 * The cache serializer is resolved process-wide on first use, so this benchmark manages its own
 * sessions (two Arrow-backed sessions on a shared context: a baseline without the Varka rule and
 * a fused varka session) and stops the inherited `SqlBasedBenchmark` session first. The active
 * and default session are cleared around each `getOrCreate`, without which the second call
 * returns the first session with the Varka config applied to it - two names for one session, and
 * a "baseline" that is not one.
 *
 * The `runQueries` cases write to `noop`, and `noop` accepts columnar batches, so their varka
 * sides hand the kernels' own Arrow batches to the sink through
 * [[org.apache.spark.sql.execution.VarkaProjectExec]] - no columnar-to-row conversion is inside
 * the measurement. That includes the mixed projection, Varka-eligible since task 12 (one fused
 * entry, one forwarded, one residual). The `runRowQueries` cases force the row path instead
 * (`toRdd`), measuring [[org.apache.spark.sql.execution.VarkaColumnarToRowExec]]'s batch
 * assembly plus the read back to rows - the number behind task 12's escape-hatch decision
 * (assemble-then-read vs merge-at-row, `PLAN_TASK_12.md` section 2.3).
 *
 * Task 14 added the milestone-2 fusion cases (nested chains, the shared subchain that DAG-CSE
 * serves, `CASE WHEN` on predictable and pseudo-random data, `dayofweek`) and the chain-depth
 * scaling pairs on both consumers, and moved every case to the committed-run methodology of
 * `PLAN_TASK_14.md` 2.1: five iterations minimum over two-second warmup and measurement windows,
 * replacing the single-run 2x1s settings whose day-to-day swing the debt register recorded.
 * The two `CASE WHEN` tables differ only in data: over `varka_date_pairs` the condition is
 * constant (`d2 - d` is a fixed 366 days), so a per-row branch predicts perfectly and the case
 * prices pure fusion; over `varka_date_pairs_rand` the condition flips pseudo-randomly, adding
 * the branch-free win. The gap between the two committed relatives is the misprediction cost.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt
 *        "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaThroughputBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain ..."
 *      Results will be written to "benchmarks/VarkaThroughputBenchmark-jdk<NN>-results.txt".
 * }}}
 */
object VarkaThroughputBenchmark extends SqlBasedBenchmark {

  private val numRows = 2000000 // 2M Arrow-cached date rows

  private def createSession(appName: String, varkaEnabled: Boolean): SparkSession = {
    val builder = SparkSession.builder()
      .master("local[1]")
      .appName(appName)
      .config(UI_ENABLED.key, false)
      .config(SQLConf.SHUFFLE_PARTITIONS.key, 1)
      .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
      .config(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
        classOf[ArrowCachedBatchSerializer].getName)
      .config(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
    if (varkaEnabled) {
      builder
        .config(SQLConf.VARKA_ENABLED.key, "true")
        // Steady state, checked to have run on the kernel before timing: a new shape's
        // first query must not wait on the row path for its kernel to compile.
        .config(SQLConf.VARKA_WARMUP_ENABLED.key, "false")
        .withExtensions(_.injectColumnar(_ => VarkaColumnarRule))
    }
    builder.getOrCreate()
  }

  private def cacheDates(session: SparkSession): Unit = {
    session.sql(
      """select case when id % 31 = 0 then null
        |       else date_add(date'2020-01-01', cast(id as int) % 1460) end as d,
        |       cast(id as int) as i
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_dates")
    session.catalog.cacheTable("varka_dates")
    // `cacheTable` is lazy, and the first case measured would otherwise pay for building the
    // whole 2M-row Arrow cache.
    session.sql("select count(*) from varka_dates").collect()
  }

  /** Task 42's table: the year, month and day of the dates `cacheDates` builds, as three ints. */
  private def cacheDateParts(session: SparkSession): Unit = {
    session.sql(
      """select year(d) as y, month(d) as m, day(d) as dd
        |from (select date_add(date'2020-01-01', cast(id as int) % 1460) as d
        |      from range(0, 2000000))""".stripMargin)
      .createOrReplaceTempView("varka_date_parts")
    session.catalog.cacheTable("varka_date_parts")
    session.sql("select count(*) from varka_date_parts").collect()
  }

  private def cacheDatePairs(session: SparkSession): Unit = {
    session.sql(
      """select date_add(date'2020-01-01', cast(id as int) % 1500) as d,
        |       date_add(date'2021-01-01', cast(id as int) % 1500) as d2,
        |       cast(id as int) as i
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_date_pairs")
    session.catalog.cacheTable("varka_date_pairs")
    session.sql("select count(*) from varka_date_pairs").collect()
  }

  /**
   * Like `varka_date_pairs` but with pseudo-random day offsets, so `d < d2` flips irregularly
   * row to row. On `varka_date_pairs` that comparison is constant (`d2 - d` is a fixed 366
   * days) and a per-row branch predicts perfectly; this table is the one where branchiness
   * costs, which is what the blend-based `CASE WHEN` case needs to show separately.
   */
  private def cacheRandomDatePairs(session: SparkSession): Unit = {
    session.sql(
      """select date_add(date'2020-01-01', pmod(hash(id), 1500)) as d,
        |       date_add(date'2020-01-01', pmod(hash(id + 7), 1500)) as d2,
        |       cast(id as int) as i
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_date_pairs_rand")
    session.catalog.cacheTable("varka_date_pairs_rand")
    session.sql("select count(*) from varka_date_pairs_rand").collect()
  }

  /**
   * A date column `d` and an int month count `m` in `[-120, 120]` - well inside
   * `VarkaChrono.MONTH_ARITH_MIN/MAX_MONTHS` - for task 60's `add_months` column-count pair.
   */
  private def cacheDatesMonthCounts(session: SparkSession): Unit = {
    session.sql(
      """select date_add(date'2020-01-01', cast(id as int) % 1460) as d,
        |       cast(pmod(id, 241) - 120 as int) as m
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_date_months")
    session.catalog.cacheTable("varka_date_months")
    session.sql("select count(*) from varka_date_months").collect()
  }

  /**
   * Task 67's fixture: `cacheDatesMonthCounts`' generator exactly, plus the same count spelled
   * as a `MONTH`-unit interval. Its own table rather than a column added to
   * `varka_date_months`, so task 60's committed rows keep the fixture they were measured on -
   * a cached table with one more column is not the same cached table, even for a query that
   * never reads it.
   */
  private def cacheDatesIntervalCounts(session: SparkSession): Unit = {
    session.sql(
      """select date_add(date'2020-01-01', cast(id as int) % 1460) as d,
        |       cast(pmod(id, 241) - 120 as int) as m,
        |       cast(cast(pmod(id, 241) - 120 as int) as interval month) as ym
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_date_interval_counts")
    session.catalog.cacheTable("varka_date_interval_counts")
    session.sql("select count(*) from varka_date_interval_counts").collect()
  }

  /**
   * Task 68's fixture: two month counts, each spelled once as an int column and once as a
   * `MONTH`-unit interval, so the binary algebra has a genuine second operand. Its own table
   * rather than two columns added to `varka_date_interval_counts`, for the reason that table
   * itself records - a cached table with one more column is not the same cached table, so
   * task 67's committed rows keep the fixture they were measured on.
   *
   * The second count is the first generator shifted by 97 within a period of 241, which is
   * prime, so `m2` differs from `m` on every row and no row is a disguised `x + x`. That is
   * asserted here rather than argued, per this project's fixture rule; both counts stay inside
   * `[-120, 120]`, so their sum cannot overflow and the pair measures the arithmetic rather
   * than a fallback.
   */
  private def cacheIntervalPairs(session: SparkSession): Unit = {
    session.sql(
      """select date_add(date'2020-01-01', cast(id as int) % 1460) as d,
        |       cast(pmod(id, 241) - 120 as int) as m,
        |       cast(pmod(id + 97, 241) - 120 as int) as m2,
        |       cast(cast(pmod(id, 241) - 120 as int) as interval month) as ym,
        |       cast(cast(pmod(id + 97, 241) - 120 as int) as interval month) as ym2
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_interval_pairs")
    session.catalog.cacheTable("varka_interval_pairs")
    val same = session.sql(
      "select count(*) from varka_interval_pairs where m = m2").collect().head.getLong(0)
    require(same == 0L,
      s"varka_interval_pairs' two counts must differ on every row, got $same equal rows")
  }

  /**
   * `varka_dates_weekday` for task 59: dates `d` and `d2` beside a weekday name `s` cycling
   * through the 21 spellings in three case styles, every name valid, so the derived leaf's
   * parse is the whole of the pre-pass and nothing declines.
   */
  private def cacheDatesWeekday(session: SparkSession): Unit = {
    val spellings = Seq("SU", "SUN", "SUNDAY", "MO", "MON", "MONDAY", "TU", "TUE", "TUESDAY",
      "WE", "WED", "WEDNESDAY", "TH", "THU", "THURSDAY", "FR", "FRI", "FRIDAY", "SA", "SAT",
      "SATURDAY")
    val styled = spellings ++ spellings.map(_.toLowerCase(java.util.Locale.ROOT)) ++
      spellings.map(n => n.head.toString + n.tail.toLowerCase(java.util.Locale.ROOT))
    val names = styled.map(n => s"'$n'").mkString("array(", ", ", ")")
    session.sql(
      s"""select date_add(date'2020-01-01', cast(id as int) % 1500) as d,
        |       date_add(date'2021-01-01', cast(id as int) % 1500) as d2,
        |       element_at($names, cast(id % ${styled.size} as int) + 1) as s
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_dates_weekday")
    session.catalog.cacheTable("varka_dates_weekday")
    session.sql("select count(*) from varka_dates_weekday").collect()
  }

  /**
   * An alternating `date_add`/`date_sub` chain of the given depth over column `d`, every
   * literal distinct, so neither Catalyst constant-folding nor C2 reassociation can shorten
   * it - each depth really is `depth` dependent ops per row.
   */
  private def chainExpr(depth: Int): String = {
    (0 until depth).foldLeft("d") { (expr, k) =>
      if (k % 2 == 0) s"date_add($expr, ${k + 1})" else s"date_sub($expr, ${k + 1})"
    }
  }

  /**
   * Every varka-side case must actually run the kernels: a query the compiler declines would
   * run the stock plan, and one the emitter rejects at run time would take the ghost fallback -
   * either way the committed "varka" number would measure nothing. So this executes the query
   * once (row consumer; the kernels are the same on both consumers) and asserts the plan fused
   * *and* `numVarkaBatches` counted, before any timing starts.
   */
  private def requireFused(varka: SparkSession, name: String, query: String): Unit = {
    val df = varka.sql(query)
    df.queryExecution.toRdd.count()
    val plan = df.queryExecution.executedPlan
    val node = plan.collectFirst {
      case v: VarkaProjectExec => v.metrics("numVarkaBatches")
      case v: VarkaColumnarToRowExec => v.metrics("numVarkaBatches")
    }.getOrElse(throw new IllegalStateException(
      s"case '$name' did not fuse on the varka session:\n${plan.treeString}"))
    require(node.value > 0,
      s"case '$name' fused but fell back at run time (numVarkaBatches = ${node.value})")
  }

  /**
   * `varka_dates_trunc_formats` for task 61: dates `d` beside a trunc format `fmt` cycling
   * through the eight accepted spellings in three case styles, every format valid, so the
   * derived leaf's parse is the whole of the pre-pass and every row is a live lane.
   */
  private def cacheDatesTruncFormats(session: SparkSession): Unit = {
    val spellings = Seq("YEAR", "YYYY", "YY", "MON", "MONTH", "MM", "QUARTER", "WEEK")
    val styled = spellings ++ spellings.map(_.toLowerCase(java.util.Locale.ROOT)) ++
      spellings.map(f => f.head.toString + f.tail.toLowerCase(java.util.Locale.ROOT))
    val formats = styled.map(f => s"'$f'").mkString("array(", ", ", ")")
    session.sql(
      s"""select date_add(date'2020-01-01', cast(id as int) % 1500) as d,
        |       element_at($formats, cast(id % ${styled.size} as int) + 1) as fmt
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_dates_trunc_formats")
    session.catalog.cacheTable("varka_dates_trunc_formats")
    session.sql("select count(*) from varka_dates_trunc_formats").collect()
  }

  private def runQueries(
      baseline: SparkSession,
      varka: SparkSession,
      name: String,
      query: String): Unit = {
    requireFused(varka, name, query)
    runBenchmark(name) {
      // The committed-run methodology of PLAN_TASK_14.md 2.1: at least five measured iterations
      // over two-second windows, so a committed number is a distribution, not a single draw.
      val benchmark = new Benchmark(s"$name over $numRows Arrow-cached rows", numRows,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      benchmark.addCase("baseline (Janino)") { _ =>
        baseline.sql(query).noop()
      }
      benchmark.addCase("varka (SIMD)") { _ =>
        varka.sql(query).noop()
      }
      benchmark.run()
    }
  }

  /**
   * Like [[runQueries]] but consuming rows (`toRdd` forces the row-output plan), so the varka
   * side runs `VarkaColumnarToRowExec`: kernels, batch assembly, then per-row read-back. The
   * assemble-then-read variant of the task 12 escape hatch is what this prices.
   */
  private def runRowQueries(
      baseline: SparkSession,
      varka: SparkSession,
      name: String,
      query: String): Unit = {
    requireFused(varka, name, query)
    runBenchmark(name) {
      val benchmark = new Benchmark(s"$name over $numRows Arrow-cached rows", numRows,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      benchmark.addCase("baseline (Janino)") { _ =>
        baseline.sql(query).queryExecution.toRdd.count()
      }
      benchmark.addCase("varka (SIMD)") { _ =>
        varka.sql(query).queryExecution.toRdd.count()
      }
      benchmark.run()
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // The inherited base session uses the default serializer; these benchmarks own their
    // Arrow-backed sessions (see the class javadoc).
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()

    val baseline = createSession("VarkaThroughputTrace-baseline", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaThroughputTrace-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    try {
      cacheDates(baseline)
      cacheDates(varka)
      cacheDatePairs(baseline)
      cacheDatePairs(varka)
      cacheDateParts(baseline)
      cacheDateParts(varka)
      cacheRandomDatePairs(baseline)
      cacheRandomDatePairs(varka)
      cacheDatesMonthCounts(baseline)
      cacheDatesMonthCounts(varka)
      cacheDatesIntervalCounts(baseline)
      cacheDatesIntervalCounts(varka)
      cacheIntervalPairs(baseline)
      cacheIntervalPairs(varka)
      cacheDatesWeekday(baseline)
      cacheDatesWeekday(varka)
      cacheDatesTruncFormats(baseline)
      cacheDatesTruncFormats(varka)

      runQueries(baseline, varka, "date_add", "SELECT date_add(d, 3) AS a FROM varka_dates")
      runQueries(baseline, varka, "date_sub", "SELECT date_sub(d, 5) AS a FROM varka_dates")
      // Task 56's pair: the same column-offset kernel with and without the evaluator's per-batch
      // bound check. `date_add(d, i)` (task 38) records no bound and is the control;
      // `d + CAST(i AS INTERVAL DAY)` compiles to the same node with a bound on `i`, because
      // Spark's cast throws past 106751991 days, so its varka row pays one vector compare pass
      // over the offset column before the kernel runs. The difference between the two varka rows
      // is the check's price on the cheapest shape that pays it.
      runQueries(baseline, varka, "date_add, column offset (task 56 control)",
        "SELECT date_add(d, i) AS a FROM varka_dates")
      runQueries(baseline, varka, "date + CAST(i AS INTERVAL DAY), bound checked (task 56)",
        "SELECT d + CAST(i AS INTERVAL DAY) AS a FROM varka_dates")
      // Task 60's pair, the same A/B shape on add_months' heavier kernel: the month count
      // widened from a compile-time-bounded literal to a column carrying a per-batch runtime
      // guard instead. `add_months(d, 13)` is the literal control, on the same fixture so the
      // two rows differ only in the offset's shape, not the underlying dates.
      runQueries(baseline, varka, "add_months, literal (task 60 control)",
        "SELECT add_months(d, 13) AS a FROM varka_date_months")
      runQueries(baseline, varka, "add_months, column count (task 60)",
        "SELECT add_months(d, m) AS a FROM varka_date_months")
      // Task 67's pair, and what it is for: the same count, once as an int column and once as
      // a MONTH-unit interval, on one fixture holding both. The two compile to the identical
      // node over the identical lanes - a year-month interval is a month count in an int32
      // buffer - so the rows should agree, and the measurement is that admitting the type
      // costs nothing rather than that it is fast. A gap between them is a finding about the
      // Arrow read path, not about the lane.
      runQueries(baseline, varka, "add_months, int count (task 67 control)",
        "SELECT add_months(d, m) AS a FROM varka_date_interval_counts")
      runQueries(baseline, varka, "d + interval column (task 67)",
        "SELECT d + ym AS a FROM varka_date_interval_counts")
      // Task 68's two pairs, and what they are for. The type exists only above the kernel, in
      // `outputTypes` and `allocateVector`, and not in the IR, so an emitter-level parity row
      // cannot see it at all: an interval arm and its int twin are the same nodes over the same
      // lanes. Only an end-to-end row can, and what it prices is the Arrow write path - whether
      // filling an `IntervalYearVector` costs what filling an `IntVector` costs. A gap between
      // the halves of a pair is therefore a finding about that path, not about the lane.
      //
      // The addition pair is task 63's checked add, whose interval spelling is checked in every
      // ANSI mode because Spark computes it with `addExact` unconditionally. The composite pair
      // is the shape that fuses with no check at all, both operands being calendar fields the
      // compile-time bound proves safe.
      runQueries(baseline, varka, "interval add, int count (task 68 control)",
        "SELECT m + m2 AS a FROM varka_interval_pairs")
      runQueries(baseline, varka, "interval add, interval columns (task 68)",
        "SELECT ym + ym2 AS a FROM varka_interval_pairs")
      runQueries(baseline, varka, "month composite, int form (task 68 control)",
        "SELECT year(d) * 12 + month(d) AS a FROM varka_interval_pairs")
      runQueries(baseline, varka, "make_ym_interval (task 68)",
        "SELECT make_ym_interval(year(d), month(d)) AS a FROM varka_interval_pairs")
      // Task 42: a date built from three int columns, under the session's default (ANSI) mode.
      runQueries(baseline, varka, "make_date",
        "SELECT make_date(y, m, dd) AS a FROM varka_date_parts")
      runQueries(baseline, varka, "datediff",
        "SELECT datediff(d2, d) AS diff FROM varka_date_pairs")
      // The milestone-2 fusion cases (PLAN_TASK_14.md 2.2). The nested projection is the query
      // the milestone plan opens with - milestone 1's per-op kernels could not fuse it at all.
      runQueries(baseline, varka, "nested projection",
        "SELECT datediff(date_add(d, 1), d2) AS n FROM varka_date_pairs")
      // The interned subtree (`date_add(d, 1)`) is computed once per lane group across both
      // outputs by DAG-CSE; Janino's per-row subexpression elimination redoes it per row.
      runQueries(baseline, varka, "shared subchain (DAG-CSE)",
        "SELECT date_add(d, 1) AS a, datediff(date_add(d, 1), d2) AS b FROM varka_date_pairs")
      // The CASE WHEN pair: same query, two data patterns (see the class doc). The committed
      // headline is the unpredictable one; the predictable run prices pure fusion.
      runQueries(baseline, varka, "case when, predictable data",
        "SELECT CASE WHEN d < d2 THEN date_add(d, 7) ELSE date_sub(d2, 7) END AS c " +
          "FROM varka_date_pairs")
      runQueries(baseline, varka, "case when, unpredictable data",
        "SELECT CASE WHEN d < d2 THEN date_add(d, 7) ELSE date_sub(d2, 7) END AS c " +
          "FROM varka_date_pairs_rand")
      // The one case that replaces an allocating path (Janino's LocalDate round trip) rather
      // than just fusing arithmetic - the kernel-level 36x of PLAN_TASK_11.md at query level.
      runQueries(baseline, varka, "dayofweek", "SELECT dayofweek(d) AS dw FROM varka_dates")
      // Task 59's three rows: the literal weekday is the control (task 33's kernel, one input);
      // the column weekday pays the derived leaf - the row engine's parse per row, before the
      // kernel - so its varka row is the leaf plus the kernel against Janino's parse plus
      // arithmetic per row; the reuse row pays one leaf for two kernels, which section 2.26
      // predicts is where the mechanism's value lies.
      runQueries(baseline, varka, "next_day, literal weekday (task 59 control)",
        "SELECT next_day(d, 'MON') AS a FROM varka_dates_weekday")
      runQueries(baseline, varka, "next_day, weekday column (task 59)",
        "SELECT next_day(d, s) AS a FROM varka_dates_weekday")
      runQueries(baseline, varka, "next_day, weekday column reused by two outputs (task 59)",
        "SELECT next_day(d, s) AS a, next_day(d2, s) AS b FROM varka_dates_weekday")
      // Task 61's two rows: the literal format is the control (task 35's one-input kernel);
      // the format column pays the derived leaf - parseTruncLevel per row - and a kernel that
      // computes all four periods and blends on the level.
      runQueries(baseline, varka, "trunc, literal format (task 61 control)",
        "SELECT trunc(d, 'MONTH') AS a FROM varka_dates_trunc_formats")
      runQueries(baseline, varka, "trunc, format column (task 61)",
        "SELECT trunc(d, fmt) AS a FROM varka_dates_trunc_formats")
      // Task 37: the ISO week by the Thursday rule, the widest single-field kernel.
      runQueries(baseline, varka, "weekofyear", "SELECT weekofyear(d) AS w FROM varka_dates")
      runQueries(baseline, varka, "yearofweek",
        "SELECT extract(YEAROFWEEK FROM d) AS y FROM varka_dates")
      // Task 63's int arithmetic, end to end. The composite key is the shape its plan is
      // about: both operands are bounded by the calendar, so the compiler proves overflow
      // out and emits no check even under ANSI, and the row engine decomposes the date twice
      // where Varka decomposes it once. `datediff + 1` is the same arithmetic over a shape
      // with almost no prefix, so it prices the add against the `datediff` row above it, and
      // `try_add` is the mode that nulls the lane rather than condemning the batch.
      runQueries(baseline, varka, "year * 100 + month (task 63)",
        "SELECT year(d) * 100 + month(d) AS k FROM varka_dates")
      runQueries(baseline, varka, "datediff + 1 (task 63)",
        "SELECT datediff(d, DATE'2000-01-01') + 1 AS a FROM varka_dates")
      runQueries(baseline, varka, "try_add over datediff (task 63)",
        "SELECT try_add(datediff(d, DATE'2000-01-01'), i) AS a FROM varka_dates")
      // The same projection twice, which is the end-to-end worth of lowering arithmetic.
      // The first row is what this case measured until task 63: one fused date entry, one
      // forwarded column, one residual - except that `i + 1` fuses now, so the projection is
      // whole and the row prices that. The second keeps a residual entry by using an operator
      // no arm lowers, so the partial-fusion shape the file has always tracked is still
      // tracked. Read together they say what the residual entry costs the whole projection.
      runQueries(baseline, varka, "mixed projection, arithmetic entry fused (task 63)",
        "SELECT date_add(d, 3) AS a, i, i + 1 AS inc FROM varka_dates")
      runQueries(baseline, varka, "mixed projection (partial fusion)",
        "SELECT date_add(d, 3) AS a, i, i % 7 AS inc FROM varka_dates")
      // Chain-depth scaling (PLAN_TASK_14.md 2.3): the fused loop pays one load and one store
      // whatever the depth; Janino pays per-row per-op overhead. Columnar consumer here, the
      // same chains through the row consumer below - their crossing is the break-even depth
      // milestone 3's fuse-profitability item needs.
      Seq(1, 2, 4, 8).foreach { depth =>
        runQueries(baseline, varka, s"chain depth $depth",
          s"SELECT ${chainExpr(depth)} AS a FROM varka_dates")
      }
      Seq(1, 2, 4, 8).foreach { depth =>
        runRowQueries(baseline, varka, s"chain depth $depth, row consumer",
          s"SELECT ${chainExpr(depth)} AS a FROM varka_dates")
      }
      // The all-fused control for the row-consumer pair below: how much of their gap is the
      // per-row read-back this node always pays, as opposed to the merge itself.
      runRowQueries(baseline, varka, "date_add, row consumer",
        "SELECT date_add(d, 3) AS a FROM varka_dates")
      runRowQueries(baseline, varka, "mixed projection, row consumer",
        "SELECT date_add(d, 3) AS a, i, i % 7 AS inc FROM varka_dates")
      // Residual-heavy: the shape where merge-at-row would win if the extra materialisation
      // of assemble-then-read costs anything worth building it for.
      runRowQueries(baseline, varka, "residual-heavy projection, row consumer",
        "SELECT date_add(d, 3) AS a, i % 7 AS r1, i % 9 AS r2, i % 11 AS r3, i % 13 AS r4 " +
          "FROM varka_dates")
      // The heavy-op row twins (task 19): every row-consumer case above fuses only cheap
      // adds, where the ~6 ns/row read-back is most likely to dominate - deciding the
      // profitability rule on them alone would decide it on the worst case. These four reuse
      // their columnar cases' SQL verbatim, so each pair differs only in the consumer.
      runRowQueries(baseline, varka, "dayofweek, row consumer",
        "SELECT dayofweek(d) AS dw FROM varka_dates")
      runRowQueries(baseline, varka, "case when unpredictable, row consumer",
        "SELECT CASE WHEN d < d2 THEN date_add(d, 7) ELSE date_sub(d2, 7) END AS c " +
          "FROM varka_date_pairs_rand")
      runRowQueries(baseline, varka, "datediff, row consumer",
        "SELECT datediff(d2, d) AS diff FROM varka_date_pairs")
      runRowQueries(baseline, varka, "nested projection, row consumer",
        "SELECT datediff(date_add(d, 1), d2) AS n FROM varka_date_pairs")
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
