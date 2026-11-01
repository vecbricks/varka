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

import java.time.LocalDate

import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.catalyst.util.DateTimeConstants
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DateType, IntegerType, StructField, StructType}

/**
 * `d1 - d2` over dates, which Spark types as a day-time interval in microseconds, against the row
 * engine. Spark computes `multiplyExact(subtractExact(end, start), MICROS_PER_DAY)` with no ANSI
 * switch, so it raises where either call overflows; the kernel widens both dates, guards the day
 * count to the largest one whose product is a long, and declines the batch outside it, which
 * leaves the row engine to raise its own error on the row. The overflow tests run both engines
 * and compare what each did, rather than naming an exception class.
 */
class VarkaDateMinusDateSuite extends QueryTest with VarkaMaybeThrows with VarkaTestWatchdog {

  private val maxDays = Long.MaxValue / DateTimeConstants.MICROS_PER_DAY

  private def day(n: Long): LocalDate = LocalDate.ofEpochDay(n)

  private val schema = StructType(Seq(
    StructField("d1", DateType), StructField("d2", DateType), StructField("i", IntegerType)))

  /** Pairs well inside the guard, with a null in either column on some rows. */
  private def pairRows: Seq[Row] = (0 until 5000).map { i =>
    val d1 = if (i % 7 == 3) null else day((i * 9973L) % 2000000 - 1000000)
    val d2 = if (i % 11 == 5) null else day((i * 7919L) % 2000000 - 1000000)
    Row(d1, d2, i)
  }

  /** The edges of the guard that still succeed, and nulls beside values past them. */
  private def edgeRows: Seq[Row] = Seq(
    Row(day(maxDays), day(0), 0),
    Row(day(0), day(maxDays), 1),
    Row(day(maxDays - 1), day(-1), 2),
    Row(null, day(Int.MinValue), 3),
    Row(day(Int.MaxValue), null, 4),
    Row(null, null, 5),
    Row(day(5), day(3), 6))

  private def overflowTables: Map[String, Seq[Row]] = Map(
    "varka_dmd_past" -> Seq(Row(day(maxDays + 1), day(0), 0), Row(day(1), day(0), 1)),
    "varka_dmd_past_negative" -> Seq(Row(day(0), day(maxDays + 1), 0), Row(day(1), day(0), 1)),
    "varka_dmd_int_wrap" -> Seq(Row(day(Int.MaxValue), day(Int.MinValue), 0),
      Row(day(1), day(0), 1)),
    "varka_dmd_int_wrap_negative" -> Seq(Row(day(Int.MinValue), day(Int.MaxValue), 0),
      Row(day(1), day(0), 1)),
    "varka_dmd_int_min" -> Seq(Row(day(Int.MinValue), day(0), 0), Row(day(1), day(0), 1)))

  private def cache(session: SparkSession): Unit = {
    val tables = Map("varka_dmd_pairs" -> pairRows, "varka_dmd_edges" -> edgeRows) ++
      overflowTables
    for ((name, rows) <- tables) {
      session.createDataFrame(session.sparkContext.parallelize(rows, 1), schema)
        .createOrReplaceTempView(name)
      session.catalog.cacheTable(name)
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cache(spark)
    cache(varkaSpark)
  }

  /**
   * The kernel serves the query and agrees with the row engine. Every table this is run over is
   * inside the guard, so no batch may be declined either: a declined batch would be the row engine
   * answering, which `checkAnswer` alone cannot tell from the kernel.
   */
  private def checkFused(query: String): Unit = {
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, expectedSql(query, actual))
    assertKernelsRan(plan)
    val node = collectFirst(plan) { case v if isVarkaNode(v) => v }.get
    val declined = node.metrics("numFallbackBatchesDeclined").value
    assert(declined === 0L, s"$query: $declined batch(es) declined inside the guard's range")
  }

  test("d1 - d2 agrees with the row engine over pairs with nulls, in either order") {
    checkFused("SELECT d1 - d2 AS v FROM varka_dmd_pairs")
    checkFused("SELECT d2 - d1 AS v FROM varka_dmd_pairs")
    checkFused("SELECT d1 - d1 AS v, d1 - d2 AS w FROM varka_dmd_pairs")
  }

  test("d1 - d2 answers the guard's own edges and a null beside a value past them") {
    checkFused("SELECT d1 - d2 AS v FROM varka_dmd_edges")
    checkFused("SELECT d2 - d1 AS v FROM varka_dmd_edges")
  }

  test("a day count past the multiply's range raises the row engine's error, from either side") {
    for (table <- overflowTables.keys.toSeq.sorted) {
      val query = s"SELECT d1 - d2 AS v FROM $table"
      // Fused first, so the error below is the decline handing the batch to the row engine and
      // not a query that never reached the kernel.
      assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
      assert(checkSparkAnswerMaybeThrows(query).isInstanceOf[VarkaMaybeThrows.Raised],
        s"$query: the row engine answered where Spark raises")
    }
  }

  test("datediff is not date - date: an int day count, still fused and unchanged") {
    checkFused("SELECT datediff(d1, d2) AS v FROM varka_dmd_pairs")
    assert(varkaSpark.sql("SELECT datediff(d1, d2) AS v FROM varka_dmd_pairs").schema.head
      .dataType === IntegerType)
  }

  test("date - date under the legacy interval setting is a CalendarInterval and stays unfused") {
    val key = SQLConf.LEGACY_INTERVAL_ENABLED.key
    val query = "SELECT d1 - d2 AS v FROM varka_dmd_pairs"
    for (session <- Seq(spark, varkaSpark)) session.conf.set(key, "true")
    try {
      val actual = varkaSpark.sql(query)
      assertNotFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, expectedSql(query, actual))
    } finally {
      for (session <- Seq(spark, varkaSpark)) session.conf.unset(key)
    }
  }
}
