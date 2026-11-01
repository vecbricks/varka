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

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.{DataFrame, QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaFusedKernel, VarkaTestWatchdog}
import org.apache.spark.sql.execution.varka.{VarkaFallbackAccounting, VarkaReasonMetric}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.internal.SQLConf

/**
 * Which reason a query fell back for, read where a user reads it (VARKA-313): the declined batches
 * by refusal reason on the Varka node's SQL metrics, the rows through each path, and the
 * compile-time declines in verbose `EXPLAIN`, under adaptive execution as well. The reasons are
 * planted through the decline hook, so that a test can name one, or a folded pair, on a query the
 * row engine answers, and reached once with real data.
 */
class VarkaFallbackReasonSuite extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

  private val reasons = VarkaFallbackAccounting.REASONS.asScala.toSeq

  /** The query's Varka node, inside adaptive query stages too. */
  private def varkaNode(df: DataFrame): SparkPlan =
    collectFirst(df.queryExecution.executedPlan) { case v if isVarkaNode(v) => v }
      .getOrElse(fail(s"no Varka node in:\n${df.queryExecution.executedPlan.treeString}"))

  /**
   * The suite's form of Comet's `checkSparkAnswerAndFallbackReason`: runs `query` on the row
   * engine and on Varka, compares the answers, and asserts that every batch the Varka node
   * declined was counted under `reason`, in the driver's accumulators and in the SQL UI. Returns
   * the Varka node for further checks.
   */
  private def checkVarkaAnswerAndFallbackReason(
      query: String, reason: String, session: SparkSession = varkaSpark): SparkPlan = {
    val actual = session.sql(query)
    checkAnswer(actual, spark.sql(query))
    val node = varkaNode(actual)
    val declined = node.metrics("numFallbackBatchesDeclined").value
    assert(declined > 0L, s"nothing declined:\n${node.treeString}")
    val counts = VarkaFallbackReasons.counts(node).asScala
    assert(counts(reason) === declined, counts)
    val shown = VarkaFallbackReasons.rendered(session, node).asScala
    assert(shown(reason) === declined.toString, shown)
    node
  }

  private def withPlantedStatus[T](status: Int)(body: => T): T = {
    VarkaColumnarToRowExec.setDeclineStatusForTesting(status)
    try body finally VarkaColumnarToRowExec.setDeclineStatusForTesting(0)
  }

  /** Runs `body` with `exactRefusalStatus` set explicitly on the Varka session. */
  private def withExactStatus[T](exact: Boolean)(body: => T): T = {
    val key = SQLConf.VARKA_EMIT_EXACT_REFUSAL_STATUS.key
    varkaSpark.conf.set(key, exact.toString)
    try body finally varkaSpark.conf.unset(key)
  }

  /**
   * Runs `body` with the day producers' guard on over whatever else the option matrix sets. The
   * two tests that read a real out-of-range day need the guard to decline it; under
   * `guardDayProducers=false` there is none, the batch runs on the kernel, and the premise they
   * plant is inert (as the liveness tests of VARKA-269 found for `validityByBitmap`).
   */
  private def withDayGuard[T](body: => T): T = {
    val previous = VarkaColumnarToRowExec.currentEmitOptions
    VarkaColumnarToRowExec.setEmitOptionsForTesting(previous.withGuardDayProducers(true))
    try body finally VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
  }

  private val datesQuery = "SELECT year(d) AS a, date_add(d, 3) AS b FROM varka_dates"

  test("a planted reason is counted under its own metric, every other reason reads 0, and the " +
      "rows add up across the two paths") {
    // Catches: a reason routed to another reason's metric, a lone bit taken for a candidate set,
    // and rows counted under neither path or both.
    cacheDates(spark)
    cacheDates(varkaSpark)
    val node = withPlantedStatus(VarkaFusedKernel.STATUS_DAY_RANGE) {
      checkVarkaAnswerAndFallbackReason(datesQuery, "day-range")
    }
    val counts = VarkaFallbackReasons.counts(node).asScala
    assert(reasons.filter(_ != "day-range").forall(r => counts(r) == 0L), counts)
    val shown = VarkaFallbackReasons.rendered(varkaSpark, node).asScala
    assert(reasons.filter(_ != "day-range").forall(r => shown(r) == "0"), shown)
    // Every batch declined, so every row was answered by the row path; the row-out node's
    // fallback is lazy and registers no fallback time at all rather than one that reads 0.
    assert(node.metrics("numVarkaRows").value === 0L)
    assert(node.metrics("numFallbackRows").value === 5L)
    assert(!node.metrics.contains("fallbackTime"), node.metrics.keys)
  }

  test("a folded pair of reasons reads \"not reported\", never 0, and is counted under " +
      "exactRefusalStatus") {
    // Under the default options a loop method that holds guards of two reasons reports both for
    // a batch either declined, so neither can be counted. Catches the row's "never as 0": a
    // candidate set counted as two reasons, or left at a 0 the UI would show.
    cacheDates(spark)
    cacheDates(varkaSpark)
    val folded = VarkaFusedKernel.STATUS_DAY_RANGE | VarkaFusedKernel.STATUS_INT_OVERFLOW
    withPlantedStatus(folded) {
      withExactStatus(exact = false) {
        val actual = varkaSpark.sql(datesQuery)
        checkAnswer(actual, spark.sql(datesQuery))
        val node = varkaNode(actual)
        val declined = node.metrics("numFallbackBatchesDeclined").value
        assert(declined > 0L)
        val shown = VarkaFallbackReasons.rendered(varkaSpark, node).asScala
        val counts = VarkaFallbackReasons.counts(node).asScala
        val unattributed = VarkaFallbackReasons.unattributed(node).asScala
        for (r <- Seq("day-range", "int-overflow")) {
          assert(shown(r).startsWith(VarkaReasonMetric.NOT_REPORTED), shown)
          assert(counts(r) === 0L && unattributed(r) === declined, (counts, unattributed))
        }
        assert(reasons.filterNot(Set("day-range", "int-overflow")).forall(r => shown(r) == "0"),
          shown)
      }
      withExactStatus(exact = true) {
        val node = checkVarkaAnswerAndFallbackReason(datesQuery, "day-range")
        assert(VarkaFallbackReasons.counts(node).get("int-overflow") ===
          node.metrics("numFallbackBatchesDeclined").value)
      }
    }
  }

  test("a real out-of-range day is counted under the day producer's guard") {
    // VARKA-52's far rows push date_add(d, off) twenty million days past the calendar's range,
    // and the producer's guard declines the batch. Catches a reason that only the hook reaches.
    cacheDatesFarOffset(spark)
    cacheDatesFarOffset(varkaSpark)
    withDayGuard {
      checkVarkaAnswerAndFallbackReason(
        "SELECT year(date_add(d, off)) AS y FROM varka_dates_far_offset", "day-producer")
    }
  }

  test("an ANSI division by a zero divisor is counted under divide-by-zero") {
    // The division sits on the right of an AND whose left rejects the zero divisors, so Spark
    // never evaluates it there and answers; the kernel computes every lane, and its guard, in a
    // condition and so qualified by no arm, sees the live zero divisor: the batch declines with
    // STATUS_DIVIDE_BY_ZERO (m9/SCOPE.md item 96). Catches the bit left out of the accounting's
    // names, which would count the batch as unrecognised.
    for (s <- Seq(spark, varkaSpark)) {
      s.createDataFrame(Seq((1.0, 0.0), (6.0, 2.0), (-3.0, -0.0), (5.0, 4.0)))
        .toDF("x1", "x2").createOrReplaceTempView("varka_divisors")
      s.catalog.cacheTable("varka_divisors")
    }
    withAnsi(true) {
      val node = checkVarkaAnswerAndFallbackReason(
        "SELECT IF(x2 <> 0.0D AND x1 / x2 > 1.0D, 1.0D, 0.0D) AS v FROM varka_divisors",
        "divide-by-zero")
      val counts = VarkaFallbackReasons.counts(node).asScala
      assert(counts(VarkaFallbackAccounting.UNRECOGNISED) === 0L, counts)
    }
  }

  test("under adaptive execution the final plan's Varka node carries the reasons, and EXPLAIN " +
      "after the run still names the residual entry's reason") {
    // m9/SCOPE.md item 28: Gluten loses its fallback tags when AQE builds a stage's plan anew.
    // Varka's EXPLAIN lines are computed from the node's own fields and its metrics are the
    // executed node's own, so both should survive; this holds them to it, through a shuffle.
    val aqe = varkaSpark.newSession()
    aqe.conf.set(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true")
    cacheDatesFarOffset(spark)
    cacheDatesFarOffset(aqe)
    val query = "SELECT year(date_add(d, off)) AS y, off % 7 AS r FROM varka_dates_far_offset"
    withDayGuard {
      val actual = aqe.sql(query).repartition(2, col("y"))
      checkAnswer(actual, spark.sql(query))
      val plan = actual.queryExecution.executedPlan
      assert(plan.isInstanceOf[adaptive.AdaptiveSparkPlanExec], plan.treeString)
      val node = varkaNode(actual)
      val declined = node.metrics("numFallbackBatchesDeclined").value
      assert(declined > 0L, node.treeString)
      assert(VarkaFallbackReasons.rendered(aqe, node).get("day-producer") === declined.toString)
      val explained = actual.queryExecution.explainString(ExplainMode.fromString("formatted"))
      assert(explained.contains("r: residual ("), explained)
    }
  }

  test("a split predicate's EXPLAIN names the residual conjunct and its reason") {
    // The rule keeps a declined conjunct in a row Filter above the Varka node, whose condition
    // holds only the fused ones; rendered from that alone, every line read "fused" and the
    // residual conjunct's reason was printed nowhere. Catches a regression to that rendering.
    cacheDatesFarOffset(varkaSpark)
    val df = varkaSpark.sql(
      "SELECT d FROM varka_dates_far_offset WHERE d < date'2024-01-02' AND off % 7 = 1")
    val explained = df.queryExecution.explainString(ExplainMode.fromString("formatted"))
    assert(explained.contains("residual ("), explained)
    assert(explained.contains(": fused"), explained)
  }

  test("the reason metric renders a count, \"not reported\", or both, from its task values") {
    // The UI's aggregation over the tasks' values, each the exact declines plus the unattributed
    // ones in the bits above 32. Catches a decode that mixes the two, and an unattributed
    // decline that renders as a count. A task that never reported reads 0 in the UI's array.
    val metric = new VarkaReasonMetric
    val u = VarkaReasonMetric.UNATTRIBUTED
    assert(metric.aggregateTaskMetrics(Array(2L, 0L, 1L)) === "3")
    assert(metric.aggregateTaskMetrics(Array.empty[Long]) === "0")
    assert(metric.aggregateTaskMetrics(Array(u, 2 * u, 0L)) ===
      s"${VarkaReasonMetric.NOT_REPORTED} (3 batches named it among others)")
    assert(metric.aggregateTaskMetrics(Array(4L, u, 0L)) ===
      s"4, and ${VarkaReasonMetric.NOT_REPORTED} in 1 batches that named it among others")
  }
}
