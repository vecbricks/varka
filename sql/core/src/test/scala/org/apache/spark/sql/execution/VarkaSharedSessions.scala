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

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChrono
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.execution.columnar.{ArrowCachedBatchSerializer, InMemoryRelation}
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Shared session setup and helpers for the Varka sql/core suites (Task 7). Data is cached with
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
 */
trait VarkaSharedSessions extends SharedSparkSession with AdaptiveSparkPlanHelper {

  protected var varkaSpark: SparkSession = _
  protected var disabledSpark: SparkSession = _

  override protected def sparkConf = {
    super.sparkConf
      .set(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
        classOf[ArrowCachedBatchSerializer].getName)
      .set(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
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
  }

  override protected def afterAll(): Unit = {
    InMemoryRelation.clearSerializer()
    varkaSpark = null
    disabledSpark = null
    super.afterAll()
  }

  private def newSession(varkaEnabled: Boolean): SparkSession = SparkSession.builder()
    .sparkContext(spark.sparkContext)
    .config(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
      classOf[ArrowCachedBatchSerializer].getName)
    .config(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
    .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
    .config(SQLConf.VARKA_ENABLED.key, varkaEnabled.toString)
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
   * Builds and caches a `varka_date_parts` temp view for task 42: three nullable int columns
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
   * body's queries, so it has to be live on each session while they run (task 42).
   */
  protected def withAnsi[T](enabled: Boolean)(body: => T): T = {
    val sessions = Seq(spark, varkaSpark)
    val before = sessions.map(_.conf.get(SQLConf.ANSI_ENABLED.key))
    sessions.foreach(_.conf.set(SQLConf.ANSI_ENABLED.key, enabled.toString))
    try body
    finally sessions.zip(before).foreach { case (s, v) => s.conf.set(SQLConf.ANSI_ENABLED.key, v) }
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
   * Builds and caches a `varka_dates_nullable_offset` temp view: a date column `d` and an int
   * column `off`, each nullable independently of the other. `cacheDates`'s `i` column is never
   * null (it comes from `zipWithIndex`), which is fine for a literal offset - always valid -
   * but a day offset that is a column (task 38) can be null on its own, and that is the case
   * this fixture exists to exercise: a null offset must still null out its row even when the
   * date beside it is not null.
   */
  protected def cacheDatesNullableOffset(session: SparkSession): Unit = {
    val rows = Seq(
      (date("2024-01-01"), Int.box(3)),
      (date("2024-01-02"), null: java.lang.Integer),
      (null: java.sql.Date, Int.box(-5)),
      (null: java.sql.Date, null: java.lang.Integer),
      (date("1969-12-31"), Int.box(100)))
    session.createDataFrame(rows).toDF("d", "off")
      .createOrReplaceTempView("varka_dates_nullable_offset")
    session.catalog.cacheTable("varka_dates_nullable_offset")
  }

  /**
   * Builds and caches a `varka_dates_far_offset` temp view for task 52: dates `d` and `d2`, an
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
   * Builds and caches a `varka_date_months` temp view for task 60: a date `d` and an int month
   * count `m`, each nullable independently, with `m` covering both ends of
   * `VarkaChrono.MONTH_ARITH_MIN/MAX_MONTHS` and a row 30000 past each end - the value the
   * removed task-26-style differential used for a far day offset, here for a far month count.
   * The row engine's `DateTimeUtils.dateAddMonths` answers any int count correctly, so it is a
   * valid oracle for the past-bound rows too; only a count near the type's extremes could
   * overflow the bias added in the lowering, and none here does.
   */
  protected def cacheDatesMonthCounts(session: SparkSession): Unit = {
    val rows = Seq(
      (date("2024-01-01"), Int.box(3)),
      (date("2024-01-02"), null: java.lang.Integer),
      (null: java.sql.Date, Int.box(-5)),
      (null: java.sql.Date, null: java.lang.Integer),
      (date("1969-12-31"), Int.box(VarkaChrono.MONTH_ARITH_MAX_MONTHS)),
      (date("1969-12-31"), Int.box(VarkaChrono.MONTH_ARITH_MIN_MONTHS)),
      (date("2000-06-15"), Int.box(VarkaChrono.MONTH_ARITH_MAX_MONTHS + 30000)),
      (date("2000-06-15"), Int.box(VarkaChrono.MONTH_ARITH_MIN_MONTHS - 30000)))
    session.createDataFrame(rows).toDF("d", "m")
      .createOrReplaceTempView("varka_date_months")
    session.catalog.cacheTable("varka_date_months")
  }

  /**
   * Builds and caches a `varka_dates_big` temp view with `numRows` rows, one null every 17 rows
   * to exercise null handling, and `parts` partitions (via `repartition` when > 1) so the scan
   * fans out over several tasks (which share one cached kernel class since task 18).
   */
  /**
   * Builds and caches `varka_dates_weekday` for task 59: dates `d` and `d2` and a weekday name
   * `s` mixing the three spellings in three case styles, THURSDAY (the negative `k`), a name
   * naming its own date's weekday (so `next_day(d, s) = d2` holds on those rows and the filter
   * route has something to count), and the rows that are not names - `'xyz'`, the empty
   * string, an untrimmed `' MON'` - beside a null name and null dates. One partition, so the
   * ANSI error surfaces from one task and both engines raise it for the same row (task 42's
   * discipline). `varka_dates_weekday_valid` is the same table without the non-name rows: the
   * one a fused ANSI query runs over with nothing to decline.
   */
  protected def cacheDatesWeekday(session: SparkSession): Unit =
    cacheWeekdayView(session, "varka_dates_weekday", weekdayRows)

  protected def cacheDatesWeekdayValid(session: SparkSession): Unit =
    cacheWeekdayView(session, "varka_dates_weekday_valid", weekdayRows.filter(_._2 != null))

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

  /** `varka_dates_weekday_lcase`: the same rows as `varka_dates_weekday`, `s` collated. */
  protected def cacheDatesWeekdayCollated(session: SparkSession): Unit = {
    cacheDatesWeekday(session)
    session.table("varka_dates_weekday")
      .selectExpr("d", "d2", "CAST(s AS STRING COLLATE UTF8_LCASE) AS s")
      .coalesce(1)
      .createOrReplaceTempView("varka_dates_weekday_lcase")
    session.catalog.cacheTable("varka_dates_weekday_lcase")
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

  /** Whether a node is one of the four Varka exec nodes (projections since task 6, filters
   * since task 21). The assertions below go through this so a suite written against one node
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
}
