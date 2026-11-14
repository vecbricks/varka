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

import java.time.Period

import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.types.{IntegerType, StructField, StructType, YearMonthIntervalType}

/**
 * `ym / num` for a literal integral `num`, and the two YEAR-unit casts, against the row engine.
 * Spark rounds the quotient half away from zero (`IntMath.divide(.., HALF_UP)`) and raises on a
 * zero divisor and on `Int.MinValue / -1`; the kernel keeps the rounding on the int lane and
 * leaves both errors to the row engine, so the error tests run both engines and compare what each
 * did rather than naming an exception class.
 */
class VarkaYmDivideSuite extends QueryTest with VarkaMaybeThrows with VarkaTestWatchdog {

  private val yearOnly = YearMonthIntervalType(YearMonthIntervalType.YEAR,
    YearMonthIntervalType.YEAR)
  private val monthOnly = YearMonthIntervalType(YearMonthIntervalType.MONTH,
    YearMonthIntervalType.MONTH)

  private val schema = StructType(Seq(
    StructField("ym", YearMonthIntervalType()), StructField("ymy", yearOnly),
    StructField("ymm", monthOnly), StructField("i", IntegerType)))

  private def months(n: Int): Period = Period.ofMonths(n)

  /**
   * Month counts that put the quotient on both sides of every half point for the small divisors
   * (a dense run around zero), spread across the range, at its edges, with a null on some rows.
   */
  private def rows: Seq[Row] = {
    val edges = Seq(Int.MinValue, Int.MinValue + 1, Int.MaxValue, Int.MaxValue - 1, 0, 1, -1)
    val values = (-1500 until 1500) ++ (0 until 3000).map(i => (i * 1431655765L).toInt) ++ edges
    values.zipWithIndex.map { case (v, i) =>
      Row(if (i % 13 == 3) null else months(v), if (i % 17 == 5) null else months(v),
        if (i % 19 == 7) null else months(v), i)
    }
  }

  private val errorTables: Map[String, Seq[Row]] = {
    def table(v: Int) = Seq(Row(months(v), months(v), months(v), 0), Row(months(12), months(12),
      months(12), 1))
    Map("varka_ymd_min" -> table(Int.MinValue), "varka_ymd_ok" -> table(Int.MinValue + 1))
  }

  private val pairSchema = StructType(Seq(
    StructField("ym", YearMonthIntervalType()), StructField("ym2", YearMonthIntervalType())))

  /**
   * Two interval columns for the dividends that are themselves checked arithmetic: one table
   * whose sums, doubles and shifts by a literal interval all fit, one with a row of each that
   * overflows, among rows that do not. The month counts include rows near half points of 7.
   */
  private val pairTables: Map[String, Seq[Row]] = {
    val fits = (-300 until 300).map(i => Row(months(i * 37), months(i * 11 - 5))) :+
      Row(months(Int.MaxValue / 4), months(Int.MaxValue / 4)) :+ Row(null, months(3)) :+
      Row(months(3), null)
    Map("varka_ymd_fits" -> fits,
      "varka_ymd_sum_overflows" -> (fits :+ Row(months(Int.MaxValue), months(1))),
      "varka_ymd_double_overflows" -> (fits :+ Row(months(Int.MaxValue / 2 + 1), months(0))),
      "varka_ymd_literal_overflows" -> (fits :+ Row(months(Int.MaxValue - 1), months(0))))
  }

  private def cache(session: SparkSession): Unit = {
    def register(name: String, data: Seq[Row], rowSchema: StructType): Unit = {
      session.createDataFrame(session.sparkContext.parallelize(data, 1), rowSchema)
        .createOrReplaceTempView(name)
      session.catalog.cacheTable(name)
    }
    for ((name, data) <- Map("varka_ymd_rows" -> rows) ++ errorTables) {
      register(name, data, schema)
    }
    for ((name, data) <- pairTables) register(name, data, pairSchema)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cache(spark)
    cache(varkaSpark)
  }

  /** The kernel serves the query and agrees with the row engine, with no batch declined. */
  private def checkFused(query: String, declinesAllowed: Boolean = false): Unit = {
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, expectedSql(query, actual))
    assertKernelsRan(plan)
    if (!declinesAllowed) {
      val node = collectFirst(plan) { case v if isVarkaNode(v) => v }.get
      val declined = node.metrics("numFallbackBatchesDeclined").value
      assert(declined === 0L, s"$query: $declined batch(es) declined")
    }
  }

  test("ym / num rounds half away from zero like the row engine, for every divisor class") {
    checkFused("SELECT ym / 2 AS a, ym / -2 AS b, ym / 3 AS c, ym / -3 AS d, ym / 7 AS e, " +
      "ym / 12 AS f, ym / -12 AS g, ym / 100 AS h, ym / 1000 AS i FROM varka_ymd_rows")
    checkFused("SELECT ym / 1 AS a, ym / 4 AS b, ym / 1024 AS c, ym / 65536 AS d, " +
      "ym / 1073741824 AS e, ym / 2147483647 AS f, ym / -1073741824 AS g, " +
      "ym / -2147483647 AS h FROM varka_ymd_rows")
    checkFused("SELECT ymy / 12 AS a, ymm / -5 AS b, ymm / 2 AS c, ym / 5 + ym / 6 AS d " +
      "FROM varka_ymd_rows")
  }

  test("ym / num takes Int, Short and Byte literals, and Int.MinValue") {
    checkFused("SELECT ym / CAST(7 AS SMALLINT) AS a, ym / CAST(-100 AS TINYINT) AS b, " +
      "ym / CAST(-128 AS TINYINT) AS c, ym / CAST(32767 AS SMALLINT) AS d " +
      "FROM varka_ymd_rows")
    checkFused("SELECT ym / CAST(-2147483648 AS INT) AS a FROM varka_ymd_rows")
  }

  test("a zero or minus-one divisor, a long, fractional or column divisor stays unfused, and " +
      "the two that raise raise the same error in both engines") {
    for (query <- Seq("SELECT ym / 0 AS a FROM varka_ymd_ok",
        "SELECT ym / -1 AS a FROM varka_ymd_min")) {
      assertNotFused(varkaSpark.sql(query).queryExecution.executedPlan)
      assert(checkSparkAnswerMaybeThrows(query).isInstanceOf[VarkaMaybeThrows.Raised],
        s"$query: the row engine answered where Spark raises")
    }
    for (query <- Seq("SELECT ym / 2L AS a FROM varka_ymd_rows",
        "SELECT ym / 2.5 AS a FROM varka_ymd_rows", "SELECT ym / 2.5D AS a FROM varka_ymd_rows",
        "SELECT ym / (i % 7 + 1) AS a FROM varka_ymd_rows")) {
      val actual = varkaSpark.sql(query)
      assertNotFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, expectedSql(query, actual))
    }
  }

  test("CAST(ymy AS INT) and the YEAR narrowing agree with the row engine") {
    checkFused("SELECT CAST(ymy AS INT) AS a, CAST(ymm AS INTERVAL YEAR) AS b, " +
      "CAST(ym AS INTERVAL YEAR) AS c FROM varka_ymd_rows")
    checkFused("SELECT CAST(ymy AS INT) + 1 AS a FROM varka_ymd_rows")
  }

  /**
   * Both engines on `query` over `table`: they answer the same rows or raise the same error. When
   * the answer is rows the kernel serves the query; the raise is the row engine's, after the
   * kernel declined the batch the checked arithmetic could not finish.
   */
  private def checkComposed(query: String, table: String, raises: Boolean,
      fuses: Boolean = true): Unit = {
    val sql = query.replace("TABLE", table)
    val (onVarka, plan) = runOutcome(varkaSpark, sql)
    val onSpark = expectedOutcome(sql, plan)
    VarkaMaybeThrows.difference(onSpark, onVarka).foreach(d => fail(s"$sql\n$d"))
    assert(onSpark.isInstanceOf[VarkaMaybeThrows.Raised] === raises, s"$sql: $onSpark")
    if (fuses) {
      // The plan is the executed one even when the query raised: the kernel is in it, and the
      // raise came out of the row engine after the kernel declined the batch.
      assertFused(plan.get)
      if (!raises) assertKernelsRan(plan.get)
    } else {
      assertNotFused(plan.get)
    }
  }

  test("a division over a checked sum, product, literal interval or narrowing answers or " +
      "raises as the row engine does") {
    val queries = Seq(
      "SELECT (ym + ym2) / 7 AS a, (ym + ym2) / -12 AS b FROM TABLE",
      // The checked product of a stored column is unbounded, so it declines at compile time.
      "SELECT (ym * 2) / 7 AS a, (ym * 2) / 100 AS b FROM TABLE",
      "SELECT (ym + INTERVAL '1' MONTH) / 7 AS a FROM TABLE",
      "SELECT CAST(ym + ym2 AS INTERVAL YEAR) / 7 AS a FROM TABLE",
      "SELECT CAST(CAST(ym * 2 AS INTERVAL YEAR) / 12 AS INT) AS a FROM TABLE")
    for (query <- queries) {
      checkComposed(query, "varka_ymd_fits", raises = false, fuses = !query.contains("ym * 2"))
    }
    for (query <- queries.take(1) ++ queries.slice(3, 4)) {
      checkComposed(query, "varka_ymd_sum_overflows", raises = true)
    }
    for (query <- queries.slice(1, 2) ++ queries.slice(4, 5)) {
      checkComposed(query, "varka_ymd_double_overflows", raises = true, fuses = false)
    }
    checkComposed("SELECT (ym + INTERVAL '2147483647' MONTH) / 7 AS a FROM TABLE",
      "varka_ymd_literal_overflows", raises = true)
  }
}
