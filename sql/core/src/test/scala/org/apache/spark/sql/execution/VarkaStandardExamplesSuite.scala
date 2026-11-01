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

import java.time.{Duration, LocalTime, Period}

import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.types.{DateType, IntegerType, StructField, StructType, TimeType,
  YearMonthIntervalType}
import org.apache.spark.sql.types.YearMonthIntervalType.{MONTH, YEAR}

/**
 * The worked examples of `sql/varka/proofs/spec/STANDARD.md`, run through Varka's kernels
 * (VARKA-243).
 *
 * That file defines each operation of the coverage table that the SQL standard fixes, and says
 * for each whether it truncates, floors or does not divide, with an example over a negative
 * value where the two would differ. A wrong example there would mislead the proofs restated
 * against it, so the examples on which Spark agrees with the standard are run here: the query is
 * fused, its kernels serve the batch, and the answer is the literal value the file states and the
 * row engine's. Three are not, because no coverage row fuses them over a negative value: an
 * interval's MONTH field and a day-time interval's DAY field (4.12), and an interval times a
 * number (4.18), which fuses only over operands a bound keeps small. The examples where Spark
 * departs from the standard - a month added to the 31st, a time plus an interval leaving the
 * day - are VARKA-244's tests, not these.
 *
 * One cached table, two rows of negative inputs, a row whose dates run the other way, and a row
 * of nulls:
 *
 *  - `d`, a date to take fields from: 1969-12-31, day count -1, and 1600-02-29, a proleptic leap
 *    day far before the epoch; then 2000-03-01, after the epoch and after its `d2`;
 *  - `d2`, a date whose day exists in every month the tests move it to;
 *  - `i`, a day count to add, which crosses 29 February 1968 in the second row; `n`, an int at the
 *    bottom of its range; `ym`, `ymm` and `ymy`, year-month intervals of the three unit ranges,
 *    negative where it matters; `t` and `t2`, times on either side of each other.
 */
class VarkaStandardExamplesSuite extends QueryTest with VarkaSharedSessions
  with VarkaTestWatchdog {

  private val table = "varka_standard_examples"

  private def months(n: Int): Period = Period.ofMonths(n).normalized()

  private def rows: Seq[Row] = Seq(
    Row(date("1969-12-31"), date("1970-01-01"), -1, -2147483647, months(-18), months(6),
      Period.ofYears(-2), LocalTime.of(0, 15), LocalTime.of(1, 0)),
    Row(date("1600-02-29"), date("1970-01-15"), -700, 0, months(-1), months(-6),
      Period.ofYears(1), LocalTime.of(23, 0), LocalTime.of(0, 0)),
    Row(date("2000-03-01"), date("1969-12-31"), 0, 5, months(12), months(-12),
      Period.ofYears(0), LocalTime.of(12, 0), LocalTime.of(11, 59, 59, 999999000)),
    Row(null, null, null, null, null, null, null, null, null))

  private val schema = StructType(Seq(
    StructField("d", DateType), StructField("d2", DateType), StructField("i", IntegerType),
    StructField("n", IntegerType),
    StructField("ym", YearMonthIntervalType(YEAR, MONTH)),
    StructField("ymm", YearMonthIntervalType(MONTH, MONTH)),
    StructField("ymy", YearMonthIntervalType(YEAR, YEAR)),
    StructField("t", TimeType(6)), StructField("t2", TimeType(6))))

  private def cache(session: SparkSession): Unit = {
    session.createDataFrame(session.sparkContext.parallelize(rows, 1), schema)
      .createOrReplaceTempView(table)
    session.catalog.cacheTable(table)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cache(spark)
    cache(varkaSpark)
  }

  /**
   * The query fused, its kernels run, and its answer both `expected` and the row engine's. A
   * predicate fuses as a filter, not as a projected value, so a comparison is run as `where`.
   */
  private def checkExample(select: String, expected: Seq[Row], where: String = ""): Unit = {
    val query = s"SELECT $select FROM $table" + (if (where.isEmpty) "" else s" WHERE $where")
    withClue(query) {
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, expected)
      checkAnswer(actual, expectedSql(query, actual))
      assertKernelsRan(plan)
    }
  }

  test("an interval's year truncates: minus eighteen months is minus one year, not minus two") {
    // 4.12: YEAR is quot(m, 12), the magnitude's years with the interval's sign.
    checkExample("extract(YEAR FROM ym)", Seq(Row(-1), Row(0), Row(1), Row(null)))
  }

  test("a date's fields decode from the day count by flooring: day minus one is 1969-12-31") {
    // 4.10: the day count is negative for every date before 1970.
    checkExample("year(d), month(d), day(d)",
      Seq(Row(1969, 12, 31), Row(1600, 2, 29), Row(2000, 3, 1), Row(null, null, null)))
  }

  test("a month carry floors: 1970-01-15 plus minus one month is 1969-12-15") {
    // 4.13: the carry div(M - 1 + m, 12) is -1, and the day is kept.
    checkExample("d2 + ym", Seq(Row(date("1968-07-01")), Row(date("1969-12-15")),
      Row(date("1970-12-31")), Row(null)))
    checkExample("d2 - ym", Seq(Row(date("1971-07-01")), Row(date("1970-02-15")),
      Row(date("1968-12-31")), Row(null)))
  }

  test("a negative day interval moves a date back across the epoch and a leap day") {
    // 4.14 and 4.19: n + k, with the cast's day count exact; 700 days back from 1970-01-15
    // passes 29 February 1968.
    checkExample("d2 + CAST(i AS INTERVAL DAY)",
      Seq(Row(date("1969-12-31")), Row(date("1968-02-15")), Row(date("1969-12-31")), Row(null)))
  }

  test("an int subtraction at the bottom of the range is exact: -2147483647 - 1 is in range") {
    // 4.1: the exact result, which INTEGER can represent here.
    checkExample("n - 1", Seq(Row(Int.MinValue), Row(-1), Row(4), Row(null)))
  }

  test("interval arithmetic and the year-to-month cast keep one sign across zero") {
    // 4.17 and 4.19: m_A - m_B over the least significant field, and 12 * years.
    checkExample("ym - ymm", Seq(Row(months(-24)), Row(months(5)), Row(months(24)), Row(null)))
    checkExample("CAST(ymy AS INTERVAL MONTH)",
      Seq(Row(months(-24)), Row(months(12)), Row(months(0)), Row(null)))
  }

  test("dates compare by their signed day counts, before and after the epoch") {
    // 4.3: (X - Y) DAY against zero.
    // The first two rows' d is the earlier date, the third's the later; the nulls are in neither.
    checkExample("d", Seq(Row(date("1969-12-31")), Row(date("1600-02-29"))), where = "d < d2")
    checkExample("d", Seq(Row(date("2000-03-01"))), where = "d > d2")
  }

  test("a time minus a later time is a negative interval, exact at six digits") {
    // 4.16: quot(t_A - t_B, 1000) microseconds, exact over TIME(6).
    checkExample("t - t2", Seq(Row(Duration.ofMinutes(-45)), Row(Duration.ofHours(23)),
      Row(Duration.ofNanos(1000)), Row(null)))
  }
}
