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

import java.time.{Duration, LocalTime}

import org.apache.spark.SparkArithmeticException
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.types.{DayTimeIntervalType, StructField, StructType, TimeType}

/**
 * Task 102's first TIME kernels against the row engine, over every second of a day.
 *
 * `t1 - t2`, `time_diff` and `time_trunc` are a subtraction and a constant division on the long
 * lane; `t + dt` is a wrapping add under two range guards, because Spark's
 * `DateTimeUtils.timeAddInterval` throws where the sum leaves the day and a lane cannot throw,
 * so the batch declines to the row engine instead. Each is run with Varka on and off over the
 * same cached table and the answers compared, through the row consumer and through the Arrow
 * cache's columnar consumer - `VarkaCoverageDifferentialSuite`'s two paths.
 *
 * The table is what the coverage differential's fixture cannot be for `t + dt`: that fixture
 * reaches both ends of the day, so no constant interval keeps every row inside it. Here the
 * interval is chosen per row so that it does, and a second table is built so that it does not -
 * which is the test that matters most, since a kernel that wrapped instead of declining would
 * return a time where Spark returns an error, and that is exactly the difference the guard
 * exists to remove.
 */
class VarkaTimeArithmeticSuite extends QueryTest with VarkaSharedSessions {

  private val secondsPerDay = 86400
  private val day = "varka_time_day"
  private val crossing = "varka_time_crossing"

  /**
   * Every second of the day, plus a sub-second edge on every thirteenth row: one microsecond,
   * half a second, the last microsecond before the next second. Every 31st `t` and every 47th
   * `dt` is null.
   */
  private def dayRows: Seq[Row] = (0 until secondsPerDay).map { i =>
    val fraction = if (i % 13 == 0) Seq(1000L, 500000000L, 999999000L)(i / 13 % 3) else 0L
    val t = LocalTime.ofSecondOfDay(i).plusNanos(fraction)
    // Shifted by twelve hours and thirty-seven minutes, modulo the day.
    val t2 = t.plusSeconds(12 * 3600 + 37 * 60)
    // Truncated to milliseconds, so the TIME(3) column exercises the widening cast.
    val t3 = LocalTime.ofNanoOfDay(t.toNanoOfDay / 1000000L * 1000000L)
    // An hour forward before noon and back after it stays inside the day for every row; the
    // half-second one exercises the sub-second path and the microsecond precision step.
    val forward = t.isBefore(LocalTime.NOON)
    val dt = if (forward) Duration.ofHours(1) else Duration.ofHours(-1)
    val dt2 = if (forward) Duration.ofMillis(500) else Duration.ofMillis(-500)
    Row(if (i % 31 == 30) null else t, t2, t3, if (i % 47 == 46) null else dt, dt2)
  }

  /** Three rows whose sum leaves the day, one that stays, and a null. */
  private def crossingRows: Seq[Row] = Seq(
    Row(LocalTime.of(23, 30), Duration.ofHours(1)),
    Row(LocalTime.of(0, 15), Duration.ofHours(-1)),
    Row(LocalTime.of(23, 59, 59, 999999000), Duration.ofNanos(1000)),
    Row(LocalTime.of(12, 0), Duration.ofHours(1)),
    Row(null, Duration.ofHours(1)))

  private val daySchema = StructType(Seq(
    StructField("t", TimeType(6)), StructField("t2", TimeType(6)),
    StructField("t3", TimeType(3)), StructField("dt", DayTimeIntervalType()),
    StructField("dt2", DayTimeIntervalType())))

  private val crossingSchema = StructType(Seq(
    StructField("t", TimeType(6)), StructField("dt", DayTimeIntervalType())))

  private def cache(session: SparkSession): Unit = {
    session.createDataFrame(session.sparkContext.parallelize(dayRows, 2), daySchema)
      .createOrReplaceTempView(day)
    session.catalog.cacheTable(day)
    session.createDataFrame(session.sparkContext.parallelize(crossingRows, 1), crossingSchema)
      .createOrReplaceTempView(crossing)
    session.catalog.cacheTable(crossing)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cache(spark)
    cache(varkaSpark)
  }

  private def isColumnarVarkaNode(plan: SparkPlan): Boolean = plan match {
    case _: VarkaProjectExec | _: VarkaFilterExec => true
    case _ => false
  }

  /**
   * Both consumers: the direct query fused and served by the kernels with the row engine's
   * answer, and the same query cached, which the Arrow cache builder feeds from the columnar
   * node.
   */
  private def checkBoth(select: String, table: String = day): Unit = {
    val query = s"SELECT $select AS v FROM $table"
    withClue(s"row consumer: $select") {
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, spark.sql(query))
      assertKernelsRan(plan)
    }
    val view = "varka_time_out"
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(query).createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
    }
    try {
      withClue(s"columnar consumer: $select") {
        val cached = varkaSpark.table(view).queryExecution.executedPlan.collectFirst {
          case scan: InMemoryTableScanExec => scan.relation.cachedPlan
        }
        assert(cached.isDefined, "expected the view to be served from the cache")
        assert(cached.get.find(isColumnarVarkaNode).isDefined,
          s"expected a columnar Varka node under the cache:\n${cached.get.treeString}")
        checkAnswer(varkaSpark.table(view), spark.table(view))
      }
    } finally {
      for (session <- Seq(spark, varkaSpark)) session.catalog.uncacheTable(view)
    }
  }

  test("t1 - t2 and time_diff at every unit agree with the row engine over every second") {
    // The subtraction of two nanosecond counts and the constant division that follows it,
    // including a TIME(3) operand reached through the widening cast, and every unit
    // DateTimeUtils.getNanosPerTimeUnit accepts, in both spellings the compiler reads.
    checkBoth("t - t2")
    checkBoth("t2 - t")
    checkBoth("t - t3")
    for (unit <- Seq("HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND", "second")) {
      checkBoth(s"time_diff('$unit', t, t2)")
    }
  }

  test("time_trunc at every level agrees with the row engine over every second") {
    // `truncatedTo(level)` as a division and a multiply, at both precisions the table holds.
    for (level <- Seq("HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND")) {
      checkBoth(s"time_trunc('$level', t)")
      checkBoth(s"time_trunc('$level', t3)")
    }
  }

  test("t + dt agrees with the row engine where the sum stays inside the day") {
    // The interval is chosen per row so that no sum crosses midnight, which makes this the
    // check of the arithmetic and the guard's pass-through: a column interval of whole hours,
    // one of half seconds, a literal, and a TIME(3) time whose result widens to TIME(6). The
    // precision step Spark applies after the add is provably the identity for these types and
    // is not emitted; agreement here is what that proof is held to.
    checkBoth("t + dt")
    checkBoth("t + dt2")
    checkBoth("t3 + dt2")
    checkBoth("t2 + INTERVAL '0 00:00:00.000001' DAY TO SECOND")
  }

  test("t + dt that crosses midnight raises Spark's own error under Varka, not a wrapped time") {
    // The test the guard exists for. A kernel that wrapped would answer a time here; Spark
    // answers DATETIME_OVERFLOW, and so must a query served by Varka, from the same rows.
    val query = s"SELECT t + dt AS v FROM $crossing"
    // Fused first, so the error below is one the kernel's decline handed to the row engine and
    // not one a query that never reached the kernel would have raised anyway.
    assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
    val expected = intercept[SparkArithmeticException](spark.sql(query).collect())
    val actual = intercept[SparkArithmeticException](varkaSpark.sql(query).collect())
    assert(expected.getCondition === "DATETIME_OVERFLOW")
    assert(actual.getCondition === expected.getCondition)
    assert(actual.getMessage === expected.getMessage)
  }

  test("a guard under a CASE arm fires only for the lanes the arm is taken for") {
    // The emitter confines a guard under a CASE arm to the lanes the arm is selected for
    // (VarkaEmitOptions.guardUnderArm), so a crossing row in the untaken arm neither throws on
    // the row engine nor declines the kernel: the batch is served and the answers agree. This
    // pins that the TIME guard rides that machinery like the day guard does - a guard that
    // fired for every lane would have declined here for nothing.
    val query = s"SELECT CASE WHEN t = TIME'12:00:00' THEN t + dt ELSE t END AS v FROM $crossing"
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, spark.sql(query))
    assertKernelsRan(plan)
    val node = plan.collectFirst { case v if isVarkaNode(v) => v }.get
    val declined = node.metrics.get("numFallbackBatchesDeclined").map(_.value).getOrElse(0L)
    assert(declined === 0L, s"a guard under an untaken arm must not decline: ${node.metrics}")
  }

  test("a crossing lane under a conjunction declines to the row engine, which answers what " +
      "it would") {
    // Where the row engine does not throw but the kernel does compute the crossing sum: AND is
    // short-circuit on the row engine, so a false conjunct to the left spares the add, while a
    // kernel evaluates every conjunct of a fused predicate for every lane. The guard fires on
    // the crossing lanes, the batch declines, the row engine answers the same one row - and
    // the decline is visible in the node's metric, which is what separates "declined" from
    // "wrong" for a guard no value can show.
    //
    // The selection is a range and not `t = TIME'12:00:00'` on purpose: the optimizer's
    // constant propagation would rewrite the sum's `t` to the literal, and a literal noon plus
    // any of these intervals stays inside the day, so nothing would be left to fire.
    val query = s"SELECT t FROM $crossing WHERE t > TIME'11:59:59' AND t < TIME'12:00:01' " +
      "AND t + dt < TIME'23:00:00'"
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, spark.sql(query))
    val node = plan.collectFirst { case v if isVarkaNode(v) => v }.get
    val declined = node.metrics.get("numFallbackBatchesDeclined").map(_.value).getOrElse(0L)
    assert(declined > 0L, s"expected the crossing batch to decline, metrics: ${node.metrics}")
  }
}
