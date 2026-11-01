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

import java.time.{Duration, LocalDate, LocalTime, Period}

import org.apache.spark.SparkThrowable
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.internal.SQLConf

/**
 * Spark's departures from `sql/varka/proofs/spec/STANDARD.md`, one test per named delta of
 * `sql/varka/proofs/spec/DELTAS.md` (VARKA-244), and the checks that found no departure.
 *
 * Each test runs its query on `spark`, the row engine with Varka off, and asserts the delta's
 * answer as a literal: the value Spark returns where the standard returns another or raises, or
 * the error condition and SQLSTATE Spark raises where the standard names another. So a Spark
 * change that removes or alters a delta fails here, and the definition in DELTAS.md is corrected
 * rather than left to go stale. Where a query reads a cached column through an expression the
 * coverage table fuses, it also runs on `varkaSpark` and must give the same answer: Varka follows
 * Spark (`plans/SCOPE_STANDARD_MODE.md` 1), and these are exactly the rows where Spark is not
 * the standard. Those queries are asserted fused, and that the kernels served the batch, except
 * over the int32 day count's two ends, where a range guard declining the batch to the row engine
 * is a correct way to give Spark's answer. A query that raises is asserted to raise the same
 * exception, with the same message, on both engines, and fused where a coverage row reaches it.
 *
 * Dates come back as `LocalDate` (`spark.sql.datetime.java8API.enabled`), which holds year 0 and
 * year 10000 as themselves; `java.sql.Date` would print them through the hybrid calendar.
 */
class VarkaStandardDeltasSuite extends QueryTest with VarkaSharedSessions
  with VarkaTestWatchdog {

  private def d(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

  /** Builds a view from `sql` in `session`, one partition, and caches it. */
  private def cacheView(session: SparkSession, name: String, sql: String): Unit = {
    session.sql(sql).coalesce(1).createOrReplaceTempView(name)
    session.catalog.cacheTable(name)
  }

  /**
   * The fixtures, one per group of deltas, so that a row which shows one delta does not raise
   * inside another's query. Built through SQL, as `cacheDatesIntervals` is, so that a date is
   * written as the standard's literal and an interval comes from its cast.
   */
  private def cacheAll(session: SparkSession): Unit = {
    // overflow_wraps, overflow_yields_null, interval_overflow_state: int32's two ends.
    cacheView(session, "varka_deltas_ints",
      """SELECT d, i FROM VALUES
        |  (DATE'1970-01-01', 2147483647),
        |  (DATE'1970-01-01', -2147483648),
        |  (DATE'1970-01-01', 5),
        |  (NULL, NULL)
        |AS t(d, i)""".stripMargin)
    // clamp_to_month_end: a target month without the day, either way and across a year, beside
    // a row whose day exists, where Spark and the standard agree.
    cacheView(session, "varka_deltas_months",
      """SELECT d, d2, CAST(m AS INTERVAL MONTH) AS ym FROM VALUES
        |  (DATE'2024-01-31', DATE'2024-03-31', 1),
        |  (DATE'2024-02-29', DATE'2024-05-31', 12),
        |  (DATE'2024-08-31', DATE'2024-12-31', -2),
        |  (DATE'1970-01-15', DATE'1970-01-15', -1),
        |  (NULL, NULL, NULL)
        |AS t(d, d2, m)""".stripMargin)
    // proleptic_range: the two ends of the standard's 0001-9999, each stepped past.
    cacheView(session, "varka_deltas_range",
      """SELECT d, k, CAST(k AS INTERVAL MONTH) AS ym FROM VALUES
        |  (DATE'9999-12-31', 1),
        |  (DATE'0001-01-01', -1),
        |  (NULL, NULL)
        |AS t(d, k)""".stripMargin)
    // day_count_wraps: the two ends of the int32 day count, each stepped past.
    cacheView(session, "varka_deltas_wrap",
      """SELECT date_from_unix_date(n) AS d, k FROM VALUES
        |  (2147483647, 1),
        |  (-2147483648, -1),
        |  (NULL, NULL)
        |AS t(n, k)""".stripMargin)
    // time_add_raises, time_add_day_field: a time an hour from either end of the day.
    cacheView(session, "varka_deltas_times",
      """SELECT CAST(s AS TIME(6)) AS t FROM VALUES
        |  ('23:30:00'), ('00:15:00'), (NULL)
        |AS t(s)""".stripMargin)
    // The ISO 8601 marks of EXTENSIONS.md: an ISO week 53 in January, an ISO week 1 in
    // December, a Sunday, and a leap year's last day.
    cacheView(session, "varka_deltas_weeks",
      """SELECT d FROM VALUES
        |  (DATE'2021-01-03'), (DATE'2024-12-30'), (DATE'2024-01-07'), (DATE'2024-12-31'), (NULL)
        |AS t(d)""".stripMargin)
  }

  private val java8Api = SQLConf.DATETIME_JAVA8API_ENABLED.key

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cacheAll(spark)
    cacheAll(varkaSpark)
  }

  /**
   * Runs `body` with `session` active and the Java 8 date API on in it. A DataFrame picks its row
   * encoder from the active session's settings, and the shared sessions leave none active.
   */
  private def onSession[T](session: SparkSession)(body: => T): T = {
    val before = SparkSession.getActiveSession
    SparkSession.setActiveSession(session)
    try withBothConf(java8Api, "true")(body) finally before match {
      case Some(active) => SparkSession.setActiveSession(active)
      case None => SparkSession.clearActiveSession()
    }
  }

  /** The query's answer on the row engine is `expected`. */
  private def sparkAnswers(query: String, expected: Seq[Row]): Unit =
    onSession(spark)(withClue(query)(checkAnswer(spark.sql(query), expected)))

  /**
   * The query's answer is `expected` on the row engine, and the same with Varka on, where the
   * query is fused: the delta as Spark has it, followed by Varka. With `kernels`, the fixture has
   * no row a guard declines, so the kernels must have served it; without, a range guard may hand
   * a far date to the row engine, which is a correct way to give Spark's answer.
   */
  private def bothAnswer(query: String, expected: Seq[Row], kernels: Boolean): Unit = {
    sparkAnswers(query, expected)
    onSession(varkaSpark) {
      withClue(s"with Varka on: $query") {
        val actual = varkaSpark.sql(query)
        val plan = actual.queryExecution.executedPlan
        assertFused(plan)
        sameCacheReads(onSession(spark)(spark.sql(query).queryExecution.executedPlan), plan, query)
        checkAnswer(actual, expected)
        if (kernels) assertKernelsRan(plan)
      }
    }
  }

  /**
   * The first `SparkThrowable` with a condition on `e`'s cause chain: a literal's error is thrown
   * by the optimizer's folding, a column's by a task, which a job failure may wrap.
   */
  private def sparkThrowable(e: Throwable): Option[Throwable with SparkThrowable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).collectFirst {
      case t: Throwable with SparkThrowable if t.getCondition != null => t
    }

  /** The query raises `condition` with `sqlState` on `session`; the error is returned. */
  private def raisesOn(
      session: SparkSession, query: String, condition: String, sqlState: String): Throwable =
    withClue(query) {
      val e = intercept[Throwable](session.sql(query).collect())
      val t = sparkThrowable(e).getOrElse(fail(s"no condition on the error's causes: $e", e))
      assert(t.getCondition === condition)
      assert(t.getSqlState === sqlState)
      e
    }

  private def raises(query: String, condition: String, sqlState: String): Unit =
    raisesOn(spark, query, condition, sqlState)

  /**
   * The query raises `condition` with `sqlState` with Varka off and with it on, as the same
   * exception with the same message, so the fallback changes nothing a user sees. With `fused`
   * the Varka plan is asserted fused first, so the error is one a kernel's decline handed to the
   * row engine rather than the row engine's alone.
   */
  private def bothRaise(query: String, condition: String, sqlState: String,
      fused: Boolean): Unit = {
    val expected = raisesOn(spark, query, condition, sqlState)
    if (fused) {
      withClue(s"with Varka on: $query") {
        assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
      }
    }
    val actual = raisesOn(varkaSpark, query, condition, sqlState)
    withClue(s"with Varka on: $query") {
      assert(actual.getClass === expected.getClass)
      assert(sparkThrowable(actual).map(_.getMessage) ===
        sparkThrowable(expected).map(_.getMessage))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The deltas, in DELTAS.md's order
  // ---------------------------------------------------------------------------------------------

  test("overflow_wraps: non-ANSI int arithmetic wraps where the standard raises 22003") {
    // STANDARD.md 4.1: the exact result in range, 22003 otherwise.
    withAnsi(enabled = false) {
      bothAnswer("SELECT i + 1, i - 1, -i FROM varka_deltas_ints", Seq(
        Row(Int.MinValue, Int.MaxValue - 1, -Int.MaxValue),
        Row(Int.MinValue + 1, Int.MaxValue, Int.MinValue),
        Row(6, 4, -5),
        Row(null, null, null)), kernels = true)
      sparkAnswers("SELECT 2147483647 * 2, 9223372036854775807L + 1L",
        Seq(Row(-2, Long.MinValue)))
    }
    // ANSI mode is the standard: the same overflows raise 22003.
    withAnsi(enabled = true) {
      bothRaise("SELECT i + 1 FROM varka_deltas_ints", "ARITHMETIC_OVERFLOW", "22003",
        fused = true)
      bothRaise("SELECT -i FROM varka_deltas_ints", "ARITHMETIC_OVERFLOW", "22003", fused = true)
      raises("SELECT 2147483647 * 2", "ARITHMETIC_OVERFLOW", "22003")
    }
  }

  test("overflow_yields_null: non-ANSI division by zero and a non-existent make_date are null") {
    // Outside the fragment: SQL-92 6.12 GR 4 raises 22012 on a zero divisor, and no coverage row
    // divides. make_date is Spark's own (EXTENSIONS.md), and the one coverage row this reaches.
    withAnsi(enabled = false) {
      sparkAnswers("SELECT 1 / 0, 7 % 0, make_date(2024, 2, 30)", Seq(Row(null, null, null)))
      bothAnswer("SELECT make_date(2021, i, 1) FROM varka_deltas_ints",
        Seq(Row(null), Row(null), Row(d(2021, 5, 1)), Row(null)), kernels = true)
    }
    withAnsi(enabled = true) {
      raises("SELECT 1 / 0", "DIVIDE_BY_ZERO", "22012")
      raises("SELECT make_date(2024, 2, 30)",
        "DATETIME_FIELD_OUT_OF_BOUNDS.WITHOUT_SUGGESTION", "22023")
    }
  }

  test("clamp_to_month_end: a month added to a day the target month lacks is its last day") {
    // STANDARD.md 4.13: the DAY field is kept, and a day the month lacks raises 22008.
    // The fourth row's day exists in its target month, and there the two agree.
    Seq(false, true).foreach { ansi =>
      withAnsi(ansi) {
        bothAnswer("SELECT d + ym, d2 - ym FROM varka_deltas_months", Seq(
          Row(d(2024, 2, 29), d(2024, 2, 29)),
          Row(d(2025, 2, 28), d(2023, 5, 31)),
          Row(d(2024, 6, 30), d(2025, 2, 28)),
          Row(d(1969, 12, 15), d(1970, 2, 15)),
          Row(null, null)), kernels = true)
        // Spark's add_months (EXTENSIONS.md) clamps by the same rule.
        bothAnswer("SELECT add_months(d, 1) FROM varka_deltas_months", Seq(
          Row(d(2024, 2, 29)), Row(d(2024, 3, 29)), Row(d(2024, 9, 30)), Row(d(1970, 2, 15)),
          Row(null)), kernels = true)
      }
    }
  }

  test("proleptic_range: a step past 9999-12-31 or before 0001-01-01 is a date, not 22008") {
    // STANDARD.md C1 and 4.10, 4.13, 4.14: YEAR is 0001 to 9999, and arithmetic leaving it
    // raises 22008. Spark's date is any int32 day count: year 10000, and year 0, which the
    // proleptic calendar makes a leap year.
    Seq(false, true).foreach { ansi =>
      withAnsi(ansi) {
        bothAnswer("SELECT date_add(d, k), d + CAST(k AS INTERVAL DAY), d + ym, " +
          "year(date_add(d, k)) FROM varka_deltas_range", Seq(
          Row(d(10000, 1, 1), d(10000, 1, 1), d(10000, 1, 31), 10000),
          Row(d(0, 12, 31), d(0, 12, 31), d(0, 12, 1), 0),
          Row(null, null, null, null)), kernels = true)
        sparkAnswers("SELECT make_date(0, 2, 29), make_date(-1, 1, 1), DATE'+10000-01-01'",
          Seq(Row(d(0, 2, 29), d(-1, 1, 1), d(10000, 1, 1))))
      }
    }
  }

  test("day_count_wraps: a day added past the int32 day count wraps to the other end") {
    // STANDARD.md 4.14: n + k, or 22008 outside the range. Spark's DateAdd is a plain Int add,
    // in either mode; a month added there raises 22003 rather than 22008.
    Seq(false, true).foreach { ansi =>
      withAnsi(ansi) {
        bothAnswer("SELECT unix_date(date_add(d, k)), unix_date(d + CAST(k AS INTERVAL DAY)) " +
          "FROM varka_deltas_wrap", Seq(
          Row(Int.MinValue, Int.MinValue),
          Row(Int.MaxValue, Int.MaxValue),
          Row(null, null)), kernels = false)
        raises("SELECT date_from_unix_date(2147483647) + INTERVAL '1' MONTH",
          "ARITHMETIC_OVERFLOW", "22003")
        // Spark's own functions do the same plain Int arithmetic (EXTENSIONS.md): last_day of
        // the last representable date is (n - 11) + 31, wrapped; add_months raises like d + ym.
        sparkAnswers("SELECT unix_date(last_day(date_from_unix_date(2147483647)))",
          Seq(Row(Int.MinValue + 19)))
        raises("SELECT add_months(date_from_unix_date(2147483647), 1)",
          "ARITHMETIC_OVERFLOW", "22003")
      }
    }
  }

  test("interval_precision_unbounded: an interval's leading field is not held to two digits") {
    // STANDARD.md C6 and 4.19: with no leading precision written, SQL-92 10.1 SR 5 makes it 2,
    // so 100 days or 100 years is 22015. Spark's interval types have no precision; the range is
    // the lane's, and only past it does Spark raise 22015.
    sparkAnswers("SELECT INTERVAL '100' DAY, CAST(100 AS INTERVAL DAY), INTERVAL '100' YEAR, " +
      "CAST(100 AS INTERVAL YEAR), INTERVAL '106751991' DAY", Seq(Row(
        Duration.ofDays(100), Duration.ofDays(100), Period.ofYears(100), Period.ofYears(100),
        Duration.ofDays(106751991))))
    raises("SELECT INTERVAL '106751992' DAY",
      "INTERVAL_ARITHMETIC_OVERFLOW.WITHOUT_SUGGESTION", "22015")
  }

  test("interval_overflow_state: a cast past the lane raises 22003, an interval product none") {
    // SQL-92 6.10 GR 12(a)(i): a number cast to an interval it does not fit is 22015, and 6.15
    // GR 4(e) casts an interval's product that way. Spark's cast raises CAST_OVERFLOW, 22003;
    // its year-month product throws a bare ArithmeticException with no condition at all.
    raises("SELECT CAST(106751992 AS INTERVAL DAY)", "CAST_OVERFLOW", "22003")
    raises("SELECT CAST(178956971 AS INTERVAL YEAR)", "CAST_OVERFLOW", "22003")
    withAnsi(enabled = true) {
      bothRaise("SELECT d + CAST(i AS INTERVAL DAY) FROM varka_deltas_ints",
        "CAST_OVERFLOW", "22003", fused = true)
    }
    val e = intercept[ArithmeticException](
      spark.sql("SELECT CAST(1073741824 AS INTERVAL MONTH) * 2").collect())
    assert(e.getMessage === "integer overflow")
    assert(sparkThrowable(e).isEmpty, s"expected no condition, got $e")
    // Interval addition, subtraction and negation raise 22015, as the standard does; an
    // over-long literal does too (interval_precision_unbounded's test).
    raises("SELECT CAST(2147483647 AS INTERVAL MONTH) + INTERVAL '1' MONTH",
      "INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION", "22015")
    raises("SELECT CAST(-2147483648 AS INTERVAL MONTH) - INTERVAL '1' MONTH",
      "INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION", "22015")
    raises("SELECT -CAST(-2147483648 AS INTERVAL MONTH)",
      "INTERVAL_ARITHMETIC_OVERFLOW.WITHOUT_SUGGESTION", "22015")
  }

  test("time_add_raises: a time plus an interval that leaves the day raises 22008, no modulo") {
    // STANDARD.md 4.15: mod(t + 1000 * u, 86,400 * 10^9), so 23:30 plus an hour is 00:30 and
    // 00:15 minus an hour is 23:15. Spark raises DATETIME_OVERFLOW, in either mode.
    Seq(false, true).foreach { ansi =>
      withAnsi(ansi) {
        raises("SELECT TIME'23:30:00' + INTERVAL '1' HOUR", "DATETIME_OVERFLOW", "22008")
        raises("SELECT TIME'00:15:00' + INTERVAL '-1' HOUR", "DATETIME_OVERFLOW", "22008")
        bothRaise("SELECT t + INTERVAL '1' HOUR FROM varka_deltas_times",
          "DATETIME_OVERFLOW", "22008", fused = true)
        bothRaise("SELECT t - INTERVAL '1' HOUR FROM varka_deltas_times",
          "DATETIME_OVERFLOW", "22008", fused = true)
      }
    }
  }

  test("time_add_day_field: a time plus an interval with a DAY field is accepted") {
    // SQL-92 6.14 SR 3 admits only fields the datetime has, so TIME plus a DAY interval is not
    // valid SQL. Spark accepts it - the coverage row's DAY TO SECOND interval is one - and a
    // nonzero day then leaves the day and raises.
    sparkAnswers("SELECT TIME'01:00:00' + INTERVAL '0' DAY, " +
      "TIME'01:00:00' + INTERVAL '0 00:30:00' DAY TO SECOND",
      Seq(Row(LocalTime.of(1, 0), LocalTime.of(1, 30))))
    raises("SELECT TIME'01:00:00' + INTERVAL '1' DAY", "DATETIME_OVERFLOW", "22008")
    bothAnswer("SELECT t + INTERVAL '0 00:00:00' DAY TO SECOND FROM varka_deltas_times",
      Seq(Row(LocalTime.of(23, 30)), Row(LocalTime.of(0, 15)), Row(null)), kernels = true)
  }

  test("date_add_time_field: a date plus an HOUR interval is a timestamp") {
    // SQL-92 6.14 SR 3 again: a date has no HOUR field, so the expression is not valid SQL.
    // Spark casts the date to a timestamp and returns one.
    sparkAnswers("SELECT typeof(DATE'2024-01-01' + INTERVAL '1' HOUR), " +
      "DATE'2024-01-01' + INTERVAL '1' HOUR = TIMESTAMP'2024-01-01 01:00:00'",
      Seq(Row("timestamp", true)))
  }

  test("no_leap_second: second 60 is refused, where the standard admits it") {
    // STANDARD.md C2: SQL-92's Table 10 admits SECOND up to 61.9(N). Spark's TIME has no 60th
    // second, so the literal is a parse error and the cast null or 22018 by mode.
    raises("SELECT TIME'23:59:60'", "INVALID_TYPED_LITERAL", "42604")
    withAnsi(enabled = false) {
      sparkAnswers("SELECT CAST('23:59:60' AS TIME)", Seq(Row(null)))
    }
    withAnsi(enabled = true) {
      raises("SELECT CAST('23:59:60' AS TIME)", "CAST_INVALID_INPUT", "22018")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Checked, and not deltas: DELTAS.md's last section
  // ---------------------------------------------------------------------------------------------

  test("not a delta: EXTRACT(SECOND FROM t) keeps the fraction; Spark's second(t) is its own") {
    // STANDARD.md 4.11 said second(t) drops the fraction. It does, but the standard's EXTRACT
    // is Spark's extract, which keeps it at the time's precision; second(t) is EXTENSIONS.md's.
    sparkAnswers("SELECT extract(SECOND FROM TIME'12:34:56.789'), " +
      "typeof(extract(SECOND FROM TIME'12:34:56.789')), " +
      "typeof(extract(SECOND FROM CAST(TIME'12:34:56.123456789' AS TIME(9)))), " +
      "second(TIME'12:34:56.789')",
      Seq(Row(BigDecimal("56.789000").bigDecimal, "decimal(8,6)", "decimal(11,9)", 56)))
  }

  test("not a delta: an interval's fields truncate, and a field outside it is refused") {
    // STANDARD.md 4.12, C5 and SR 3(a); 4.4's comparison in microseconds.
    sparkAnswers("SELECT extract(YEAR FROM INTERVAL '-1-6' YEAR TO MONTH), " +
      "extract(MONTH FROM INTERVAL '-1-6' YEAR TO MONTH), " +
      "extract(DAY FROM INTERVAL '-1 12' DAY TO HOUR), " +
      "INTERVAL '0.4' SECOND = INTERVAL '0' SECOND",
      Seq(Row(-1, -6.toByte, -1, false)))
    val e = intercept[Throwable](spark.sql("SELECT extract(YEAR FROM INTERVAL '30' MONTH)"))
    assert(sparkThrowable(e).map(_.getCondition) === Some("INVALID_EXTRACT_FIELD"))
  }

  test("not a delta: three-valued logic, IN with a null, the calendar across 1582, datediff") {
    // STANDARD.md 4.6 and 4.7; C1's continuous calendar; datediff as (a - b) DAY (section 5).
    sparkAnswers("SELECT NULL AND NULL, NULL OR NULL, NULL AND false, NULL OR true, " +
      "1 IN (2, NULL), 1 IN (1, NULL)", Seq(Row(null, null, false, true, null, true)))
    sparkAnswers("SELECT date_add(DATE'1582-10-04', 1), datediff(DATE'1582-10-15', " +
      "DATE'1582-10-04'), datediff(DATE'1969-12-31', DATE'1970-01-02'), " +
      "DATE'1969-12-31' - DATE'1970-01-02'",
      Seq(Row(d(1582, 10, 5), 11, -2, Duration.ofDays(-2))))
  }

  test("not a delta: a time difference truncates toward zero, Spark's choice and the spec's") {
    // STANDARD.md 4.16: quot(t_A - t_B, 1000) microseconds; -1500 ns is -1 us.
    sparkAnswers("SELECT CAST(TIME'00:00:00.0000015' AS TIME(9)) - " +
      "CAST(TIME'00:00:00.000003' AS TIME(9))", Seq(Row(Duration.ofNanos(-1000))))
  }

  // ---------------------------------------------------------------------------------------------
  // EXTENSIONS.md's marks
  // ---------------------------------------------------------------------------------------------

  test("ISO 8601's week rules, and Spark's own day-of-week numberings") {
    // ISO 8601: a week starts on Monday, and week 1 holds the year's first Thursday, so
    // 2021-01-03 is in week 53 of 2020 and 2024-12-30 in week 1 of 2025; Sunday is day 7; the
    // ordinal day of a leap year's last day is 366. Spark's dayofweek counts from Sunday = 1,
    // its weekday from Monday = 0.
    bothAnswer("SELECT weekofyear(d), extract(YEAROFWEEK FROM d), " +
      "extract(DAYOFWEEK_ISO FROM d), dayofyear(d), trunc(d, 'WEEK'), dayofweek(d), " +
      "weekday(d) FROM varka_deltas_weeks", Seq(
      Row(53, 2020, 7, 3, d(2020, 12, 28), 1, 6),
      Row(1, 2025, 1, 365, d(2024, 12, 30), 2, 0),
      Row(1, 2024, 7, 7, d(2024, 1, 1), 1, 6),
      Row(1, 2025, 2, 366, d(2024, 12, 30), 3, 1),
      Row(null, null, null, null, null, null, null)), kernels = true)
  }
}
