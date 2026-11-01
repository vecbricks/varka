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

package org.apache.spark.sql.execution

import org.apache.spark.internal.config.BUFFER_PAGESIZE
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaMatrix, VarkaMatrixTests}
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.execution.columnar.{ArrowCachedBatchSerializer, InMemoryRelation, InMemoryTableScanExec}
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.util.QueryExecutionListener

/**
 * Shared session setup and helpers for the Varka sql/core suites (VARKA-7). Data is cached with
 * the Arrow serializer and the vectorized reader so that `InMemoryTableScanExec` feeds real Arrow
 * `DateDayVector` batches into a columnar-to-row transition. Three sessions on the shared
 * `SparkContext` are set up by [[VarkaSharedSessions#beforeAll]]:
 *
 *   - `spark`: the base `SharedSparkSession` session (Arrow serializer, Varka off) - the
 *     expected (row-engine) side;
 *   - `varkaSpark`: `spark.sql.codegen.varka.enabled=true` - the actual (fused) side;
 *   - `disabledSpark`: the same session shape with Varka disabled - proves the config gate.
 *
 * None of them injects [[VarkaColumnarRule]] by hand: it is registered for every session by
 * `BaseSessionStateBuilder.columnarRules` and is inert unless the config is set.
 *
 * AQE is off on the custom sessions so the physical plan is materialized deterministically and
 * the Varka fusion boundary is visible in the executed plan. `InMemoryRelation.clearSerializer()`
 * resets the process-wide serializer singleton around session creation and afterwards so it is
 * not leaked to later suites.
 *
 * Under the option matrix (`VarkaMatrix`) every kernel is emitted with the matrix's base options,
 * and `varkaSpark`'s queries report the batches the Varka kernels processed, so the runner can
 * tell a test that still fuses from one the row path answered alone.
 */
trait VarkaSharedSessions extends SharedSparkSession with AdaptiveSparkPlanHelper
    with VarkaMatrixTests {

  protected var varkaSpark: SparkSession = _
  protected var disabledSpark: SparkSession = _

  override protected def sparkConf = {
    val conf = super.sparkConf
      .set(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
        classOf[ArrowCachedBatchSerializer].getName)
      .set(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
    // A sort's memory page, 1 MB rather than the up to 64 MB Spark sizes from the heap and the
    // cores: every task of a sort allocates and zeroes one, and the suites sort a few rows in
    // hundreds of queries, so the differential suite spent half its time in the allocator
    // (147 s against 72 s on the laptop, VARKA-507). The answers do not depend on the page.
    conf.set(BUFFER_PAGESIZE.key, "1m")
    // The batch-size axis (VARKA-301): this JVM's rows per Arrow cached batch, in every session.
    VarkaMatrix.arrowBatchSize.foreach(rows =>
      conf.set(SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.key, rows.toString))
    conf
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    InMemoryRelation.clearSerializer()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    disabledSpark = newSession(varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    varkaSpark = newSession(varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaMatrix.base)
    varkaSpark.listenerManager.register(VarkaSharedSessions.fusedBatchCounter)
    VarkaMatrix.fusedBatches = () => {
      spark.sparkContext.listenerBus.waitUntilEmpty()
      VarkaSharedSessions.fusedBatchCount.get()
    }
  }

  override protected def afterAll(): Unit = {
    InMemoryRelation.clearSerializer()
    VarkaMatrix.fusedBatches = () => -1L
    if (varkaSpark != null) {
      varkaSpark.listenerManager.unregister(VarkaSharedSessions.fusedBatchCounter)
    }
    varkaSpark = null
    disabledSpark = null
    super.afterAll()
  }

  /**
   * `spark` keeps its cache from test to test, as `varkaSpark` and `disabledSpark` do: the
   * suites cache their fixtures on both sides once, and a comparison from the second test on
   * would otherwise set Varka over the cached table against the row engine over the inlined,
   * uncached plan. [[expectedSql]] and [[sameCacheReads]] check that both sides read the cache.
   */
  override protected def clearCacheAfterEachTest: Boolean = false

  private def newSession(varkaEnabled: Boolean): SparkSession = SparkSession.builder()
    .sparkContext(spark.sparkContext)
    .config(SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.key,
      VarkaMatrix.arrowBatchSize.getOrElse(SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH
        .defaultValue.get).toString)
    .config(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
      classOf[ArrowCachedBatchSerializer].getName)
    .config(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
    .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
    .config(SQLConf.VARKA_ENABLED.key, varkaEnabled.toString)
    // The suites compare the kernel's answers with the row engine's, so the kernel must serve
    // a shape's first batch: with the warm-up on, a new shape's first query takes the row path
    // and such a comparison would compare the row engine with itself.
    .config(SQLConf.VARKA_WARMUP_ENABLED.key, "false")
    // The emitter's default level, not the host's: see TestSQLContext.overrideConfs.
    .config(SQLConf.VARKA_EMIT_USE_AVX.key, "-1")
    .getOrCreate()

  protected def date(value: String): java.sql.Date = java.sql.Date.valueOf(value)

  protected def cacheDates(session: SparkSession): Unit = {
    val dates = Seq(date("2024-01-01"), date("2024-01-02"), date("2023-12-27"),
      date("1969-12-31"), null).zipWithIndex
    session.createDataFrame(dates.map { case (d, i) => (d, i) }).toDF("d", "i")
      .createOrReplaceTempView("varka_dates")
    session.catalog.cacheTable("varka_dates")
  }

  /**
   * Builds and caches a `varka_date_parts` temp view for VARKA-42: three nullable int columns
   * `y`, `m`, `dd` - valid dates, 29 February in a leap and a common year, a month and a day
   * out of range, and nulls in each position - beside `d`, the date the valid rows spell, so a
   * test can compare `make_date(y, m, dd)` with the column it came from.
   */
  protected def cacheDateParts(session: SparkSession): Unit = {
    def n: java.lang.Integer = null
    val rows: Seq[(java.sql.Date, java.lang.Integer, java.lang.Integer, java.lang.Integer)] = Seq(
      (date("2024-01-01"), 2024, 1, 1),
      (date("2024-02-29"), 2024, 2, 29),
      (date("1969-12-31"), 1969, 12, 31),
      (date("0001-01-01"), 1, 1, 1),
      (date("9999-12-31"), 9999, 12, 31),
      (null, 2023, 2, 29),
      (null, 2024, 4, 31),
      (null, 2024, 13, 1),
      (null, 2024, 6, 0),
      (null, n, 6, 15),
      (null, 2024, n, 15),
      (null, 2024, 6, n))
    // One partition, so the first invalid row - and with it the error message the ANSI test
    // compares across the two sessions - is the same row every time rather than whichever
    // failing task reaches the driver first.
    session.createDataFrame(rows).toDF("d", "y", "m", "dd").coalesce(1)
      .createOrReplaceTempView("varka_date_parts")
    session.catalog.cacheTable("varka_date_parts")
  }

  /**
   * Runs `body` with `spark.sql.ansi.enabled` set on both sessions, restoring the previous
   * values afterwards. The setting is read when an expression is analyzed, which is inside the
   * body's queries, so it has to be live on each session while they run (VARKA-42).
   */
  protected def withAnsi[T](enabled: Boolean)(body: => T): T =
    withBothConf(SQLConf.ANSI_ENABLED.key, enabled.toString)(body)

  /**
   * Runs `body` with the SQL conf `key` set to `value` on `spark` and `varkaSpark`, restoring
   * each session's previous value afterwards, or unsetting the key where it had none, so that
   * both sides of a comparison plan their queries under the same setting.
   */
  protected def withBothConf[T](key: String, value: String)(body: => T): T = {
    val sessions = Seq(spark, varkaSpark)
    val before = sessions.map(_.conf.getOption(key))
    sessions.foreach(_.conf.set(key, value))
    try body finally sessions.zip(before).foreach {
      case (session, Some(v)) => session.conf.set(key, v)
      case (session, None) => session.conf.unset(key)
    }
  }

  protected def cacheDatePairs(session: SparkSession): Unit = {
    val dates = Seq(
      (date("2024-03-01"), date("2024-01-01")),
      (date("2024-01-01"), date("2024-01-01")),
      (date("2023-12-01"), date("2024-01-01")),
      (null, date("2024-01-01")),
      (date("2024-01-01"), null)).zipWithIndex
    session.createDataFrame(dates.map { case ((d, d2), i) => (d, d2, i) })
      .toDF("d", "d2", "i")
      .createOrReplaceTempView("varka_date_pairs")
    session.catalog.cacheTable("varka_date_pairs")
  }

  /**
   * Builds and caches `varka_dates_intervals` for VARKA-67: a date `d` beside one year-month
   * interval column per unit - `ymm` (MONTH), `ymy` (YEAR) and `ym` (YEAR TO MONTH) - built
   * from an int month count so the rows are legible as counts. The counts straddle VARKA-60's
   * `MONTH_ARITH_MIN/MAX_MONTHS` guard: 24565 is one past the maximum and -300000 far below
   * the minimum, so the batch declines and the row engine answers, while the rest are ordinary
   * in-range counts. Nulls sit in `d` and in the interval columns on different rows, so a null
   * on either side of `d + ym` is covered.
   *
   * Built through SQL rather than `createDataFrame` because a year-month interval column has
   * no plain Scala literal: the units come from the casts and `make_ym_interval`, which is
   * also the shape a user writes.
   *
   * `ymm2` is VARKA-68's second interval, for the binary algebra - genuinely a different count
   * from `ymm` on every row, not a second call to the same generator, so `ymm - ymm2` is not a
   * disguised zero. Its `Int.MaxValue` row is what makes `ymm + ymm2` overflow, which is how
   * the checked arithmetic's error identity is tested; the other rows stay well inside the
   * range so a filtered query over them fuses and answers.
   */
  protected def cacheDatesIntervals(session: SparkSession): Unit = {
    session.sql(
      """SELECT d,
        |       CAST(m AS INTERVAL MONTH) AS ymm,
        |       CAST(y AS INTERVAL YEAR) AS ymy,
        |       make_ym_interval(y, mm) AS ym,
        |       CAST(m2 AS INTERVAL MONTH) AS ymm2,
        |       m, m2
        |FROM VALUES
        |  (DATE'2024-01-31',  3,       1,  2, 10),
        |  (DATE'2024-02-29', -14,     -2,  0, -20),
        |  (DATE'2020-05-05',  24565,   3,  1, 2147483647),
        |  (DATE'1969-12-31', -300000,  0, 11, 5),
        |  (DATE'2024-01-01',  NULL,    1,  1, 100),
        |  (NULL,              7,       2,  3, NULL),
        |  (DATE'9999-12-01',  1,       0,  1, -3)
        |AS t(d, m, y, mm, m2)""".stripMargin)
      .createOrReplaceTempView("varka_dates_intervals")
    session.catalog.cacheTable("varka_dates_intervals")
  }

  /**
   * Builds and caches a `varka_dates_far_offset` temp view for VARKA-52: dates `d` and `d2`, an
   * int offset `off` whose two extreme rows push `date_add(d, off)` twenty million days past
   * the range the calendar lowering is exact over - the value the removed task-26 differential
   * used - beside in-range and null rows, and an int offset `small` that never leaves it. The
   * row engine's answer for a far day is a real year (LocalDate handles any int day), so it is
   * a valid oracle here; only an offset near `Int.MaxValue` would wrap, and none is.
   */
  protected def cacheDatesFarOffset(session: SparkSession): Unit = {
    val rows = Seq(
      (date("2024-01-01"), date("2024-01-01"), Int.box(20000000), Int.box(3)),
      (date("2024-01-02"), date("2020-05-05"), Int.box(-20000000), Int.box(-5)),
      (date("2023-12-27"), null: java.sql.Date, Int.box(3), null: java.lang.Integer),
      (null: java.sql.Date, date("2024-01-01"), null: java.lang.Integer, Int.box(7)),
      (date("1969-12-31"), date("1969-12-31"), Int.box(100), Int.box(100)),
      (date("2024-02-29"), date("2024-02-29"), null: java.lang.Integer, Int.box(0)))
    session.createDataFrame(rows).toDF("d", "d2", "off", "small")
      .createOrReplaceTempView("varka_dates_far_offset")
    session.catalog.cacheTable("varka_dates_far_offset")
  }

  /**
   * Builds and caches a `varka_date_months` temp view for VARKA-60: a date `d` and two int month
   * counts, each nullable independently. `m` covers both ends of
   * `VarkaChrono.MONTH_ARITH_MIN/MAX_MONTHS` and includes a row 30000 months past each end, far
   * enough to trip the count guard; `m_small` holds only counts inside the bound, both ends
   * included. The pair is `cacheDatesFarOffset`'s `off`/`small` shape and exists for the same
   * reason: these rows are one batch, so a query over `m` declines the batch in full and never
   * reaches the kernel - a test asserting values over `m` alone compares the row engine with
   * itself. `m_small` is what exercises the kernel, and the two together separate "the guard
   * fires" from "the arithmetic is right".
   *
   * 30000 months is 2500 years, which keeps the row engine a valid oracle for the past-bound
   * rows: `DateTimeUtils.dateAddMonths` goes through `LocalDate.plusMonths`, which throws only
   * once the count pushes the year outside `LocalDate`'s own range, and no row here is close.
   */
  protected def cacheDatesMonthCounts(session: SparkSession): Unit = {
    val hi = VarkaChrono.MONTH_ARITH_MAX_MONTHS
    val lo = VarkaChrono.MONTH_ARITH_MIN_MONTHS
    val rows = Seq(
      (date("2024-01-01"), Int.box(3), Int.box(3)),
      (date("2024-01-02"), null: java.lang.Integer, Int.box(-5)),
      (null: java.sql.Date, Int.box(-5), null: java.lang.Integer),
      (null: java.sql.Date, null: java.lang.Integer, Int.box(0)),
      (date("1969-12-31"), Int.box(hi), Int.box(hi)),
      (date("1969-12-31"), Int.box(lo), Int.box(lo)),
      (date("2000-06-15"), Int.box(hi + 30000), Int.box(12)),
      (date("2000-06-15"), Int.box(lo - 30000), Int.box(-12)))
    session.createDataFrame(rows).toDF("d", "m", "m_small").coalesce(1)
      .createOrReplaceTempView("varka_date_months")
    session.catalog.cacheTable("varka_date_months")
  }

  /**
   * Builds and caches a `varka_dates_big` temp view with `numRows` rows, one null every 17 rows
   * to exercise null handling, and `parts` partitions (via `repartition` when > 1) so the scan
   * fans out over several tasks (which share one cached kernel class, VARKA-18).
   */
  /**
   * Builds and caches `varka_dates_weekday` for VARKA-59: dates `d` and `d2` and a weekday name
   * `s` mixing the three spellings in three case styles, THURSDAY (the negative `k`), a name
   * naming its own date's weekday (so `next_day(d, s) = d2` holds on those rows and the filter
   * route has something to count), and the rows that are not names - `'xyz'`, the empty
   * string, an untrimmed `' MON'` - beside a null name and null dates. One partition, so the
   * ANSI error surfaces from one task and both engines raise it for the same row (VARKA-42's
   * discipline).
   */
  protected def cacheDatesWeekday(session: SparkSession): Unit =
    cacheWeekdayView(session, "varka_dates_weekday", weekdayRows)

  private val weekdayRows: Seq[(java.sql.Date, java.sql.Date, String)] = Seq(
      (date("2024-01-01"), date("2024-01-08"), "Mon"),
      (date("2024-01-02"), date("2024-01-09"), "tuesday"),
      (date("2024-01-03"), date("2024-01-10"), "WE"),
      (date("2024-01-04"), date("2024-01-11"), "th"),
      (date("2024-01-05"), date("2024-01-12"), "Fri"),
      (date("1969-12-31"), date("1970-01-07"), "WEDNESDAY"),
      (date("2024-02-29"), date("2024-03-07"), "thu"),
      (date("2024-01-10"), date("2024-01-11"), "sunday"),
      (date("2024-01-06"), null, "xyz"),
      (date("2024-01-07"), null, ""),
      (date("2024-01-08"), null, " MON"),
      (date("2024-01-09"), date("2024-01-16"), null),
      (null, date("2024-01-17"), "TUE"),
      (null, null, "SAT"))

  /**
   * `varka_dates_weekday_bad_on_nulls`: the non-names sit only beside null dates, which is the
   * row the ANSI route hinges on - the row engine never parses a name beside a null date, so
   * the query is NULLs and no error, and a pre-pass that raised on the parse would be wrong.
   */
  protected def cacheDatesWeekdayBadOnNulls(session: SparkSession): Unit =
    cacheWeekdayView(session, "varka_dates_weekday_bad_on_nulls", Seq(
      (date("2024-01-01"), date("2024-01-08"), "Mon"),
      (null, date("2024-01-09"), "bogus"),
      (date("2024-01-03"), date("2024-01-10"), "wednesday"),
      (null, null, ""),
      (date("2024-01-05"), null, "fri")))

  /**
   * `varka_dates_trunc_formats` for VARKA-61: dates `d` beside a format string `fmt` - the
   * accepted spellings in mixed case, sub-day levels, unrecognised strings, the empty string
   * and nulls, beside null dates too; dates at year, quarter, month and week boundaries in
   * both year kinds and either side of the epoch. One partition.
   */
  protected def cacheDatesTruncFormats(session: SparkSession): Unit = {
    val rows: Seq[(java.sql.Date, String)] = Seq(
      (date("2024-03-15"), "YEAR"), (date("2024-03-15"), "yy"), (date("2024-03-15"), "Month"),
      (date("2024-03-15"), "mm"), (date("2024-03-15"), "QUARTER"), (date("2024-03-15"), "week"),
      (date("2024-03-15"), "HOUR"), (date("2024-03-15"), "QTR"), (date("2024-03-15"), ""),
      (date("2024-03-15"), null),
      (date("2023-12-31"), "yyyy"), (date("2023-12-31"), "MON"), (date("2023-12-31"), "quarter"),
      (date("2023-12-31"), "WEEK"), (date("2024-02-29"), "Week"), (date("2024-02-29"), "DD"),
      (date("1969-12-31"), "WEEK"), (date("1970-01-01"), "MONTH"), (date("2024-01-01"), "week"),
      (date("2024-07-01"), "Quarter"), (date("2024-05-20"), "MICROSECOND"),
      (date("2024-05-20"), " YEAR"), (date("0001-01-01"), "YEAR"), (date("9999-12-31"), "week"),
      (null, "YEAR"), (null, "xyz"), (null, null))
    session.createDataFrame(rows).toDF("d", "fmt").coalesce(1)
      .createOrReplaceTempView("varka_dates_trunc_formats")
    session.catalog.cacheTable("varka_dates_trunc_formats")
  }

  private def cacheWeekdayView(
      session: SparkSession,
      name: String,
      rows: Seq[(java.sql.Date, java.sql.Date, String)]): Unit = {
    session.createDataFrame(rows).toDF("d", "d2", "s").coalesce(1)
      .createOrReplaceTempView(name)
    session.catalog.cacheTable(name)
  }

  protected def cacheDatesBig(session: SparkSession, numRows: Int, parts: Int = 1): Unit = {
    val rows = (0 until numRows).map { i =>
      val d = if (i % 17 == 0) null
      else java.sql.Date.valueOf(java.time.LocalDate.of(2020, 1, 1).plusDays(i % 365))
      (d, i)
    }
    val df = session.createDataFrame(rows).toDF("d", "i")
    (if (parts > 1) df.repartition(parts) else df).createOrReplaceTempView("varka_dates_big")
    session.catalog.cacheTable("varka_dates_big")
  }

  /** Whether a node is one of the four Varka exec nodes (projections, VARKA-6, and filters,
   * VARKA-21). The assertions below go through this so a suite written against one node
   * kind keeps working as the rule learns new rewrites. */
  protected def isVarkaNode(plan: SparkPlan): Boolean = plan match {
    case _: VarkaColumnarToRowExec | _: VarkaProjectExec
        | _: VarkaFilterExec | _: VarkaFilterColumnarToRowExec => true
    case _ => false
  }

  protected def assertKernelsRan(plan: SparkPlan): Unit = {
    val node = collectFirst(plan) { case v if isVarkaNode(v) => v }
      .getOrElse(fail(s"expected a Varka node in the plan:\n${plan.treeString}"))
    val varkaBatches = node.metrics.get("numVarkaBatches").map(_.value).getOrElse(0L)
    assert(varkaBatches > 0L,
      s"expected the SIMD kernels to process the cached Arrow batches, got $varkaBatches")
  }

  protected def assertFused(plan: SparkPlan): Unit = {
    assert(find(plan)(isVarkaNode).isDefined,
      s"expected a Varka node in the plan:\n${plan.treeString}")
  }

  protected def assertNotFused(plan: SparkPlan): Unit = {
    assert(find(plan)(isVarkaNode).isEmpty,
      s"expected no Varka node in the plan:\n${plan.treeString}")
  }

  /** The cached tables a plan reads, by name, one entry for each scan of the cache. */
  protected def cachedReads(plan: SparkPlan): Seq[String] = collect(plan) {
    case scan: InMemoryTableScanExec => scan.relation.cacheBuilder.tableName.getOrElse("<unnamed>")
  }.sorted

  /**
   * Checks that `expected`, the row engine's side of a comparison, reads the same cached tables
   * as `actual`, Varka's side. Both are meant to answer over the same cached batches: a row
   * engine whose cache was lost reads the inlined plan instead, which can raise or round where
   * the cached scan does not, so a difference between the plans would go unseen. With
   * `mustRead`, the plans must read at least one cached table as well, so that two sides that
   * both lost the cache do not pass as agreeing - unless the optimizer folded the query to no
   * rows, so that the plan reads nothing at all (`WHERE (d IS NULL) IS NULL`).
   */
  protected def sameCacheReads(
      expected: SparkPlan, actual: SparkPlan, what: String, mustRead: Boolean = false): Unit = {
    val reads = cachedReads(expected)
    assert(reads == cachedReads(actual),
      s"$what: the row engine reads the cached tables $reads, Varka " +
        s"${cachedReads(actual)}:\n${expected.treeString}")
    assert(!mustRead || reads.nonEmpty || readsNoRows(expected),
      s"$what: neither side reads a cached table:\n${expected.treeString}")
  }

  /** Whether every leaf of `plan` is an empty relation, as an optimizer-folded query's are. */
  private def readsNoRows(plan: SparkPlan): Boolean = collect(plan) {
    case leaf if leaf.children.isEmpty => leaf
  }.forall {
    case l: LocalTableScanExec => l.rows.isEmpty
    case leaf => leaf.isInstanceOf[EmptyRelationExec]
  }

  /** The row engine's answer to `query`, checked by [[sameCacheReads]] against `actual`'s. */
  protected def expectedSql(query: String, actual: DataFrame): DataFrame =
    expectedOf(spark.sql(query), actual, query)

  /**
   * The row engine's rows for `query`, its plan checked by [[sameCacheReads]] against
   * `varkaPlan`, the plan Varka ran the same query with, for a comparison that holds Varka's
   * rows rather than its frame.
   */
  protected def expectedRows(query: String, varkaPlan: SparkPlan): Seq[Row] = {
    val expected = spark.sql(query)
    sameCacheReads(expected.queryExecution.executedPlan, varkaPlan, query, mustRead = true)
    expected.collect().toSeq
  }

  /** As the other form, with Varka's plan for `query` built here. */
  protected def expectedRows(query: String): Seq[Row] =
    expectedRows(query, varkaSpark.sql(query).queryExecution.executedPlan)

  /** `expected`, checked by [[sameCacheReads]] against `actual`. */
  protected def expectedOf(expected: DataFrame, actual: DataFrame, what: String): DataFrame = {
    sameCacheReads(expected.queryExecution.executedPlan, actual.queryExecution.executedPlan, what,
      mustRead = true)
    expected
  }
}

private object VarkaSharedSessions {

  /** Batches the Varka kernels processed in the queries `varkaSpark` ran, over the JVM. */
  val fusedBatchCount = new java.util.concurrent.atomic.AtomicLong

  /** Sums each successful query's `numVarkaBatches` over its plan into [[fusedBatchCount]]. */
  val fusedBatchCounter: QueryExecutionListener = new QueryExecutionListener {
    override def onSuccess(funcName: String, qe: QueryExecution, durationNs: Long): Unit = {
      val batches = qe.executedPlan.collectWithSubqueries {
        case node if node.metrics.contains("numVarkaBatches") =>
          node.metrics("numVarkaBatches").value
      }
      fusedBatchCount.addAndGet(batches.sum)
    }

    override def onFailure(funcName: String, qe: QueryExecution, exception: Exception): Unit = {}
  }
}
