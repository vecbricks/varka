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

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.spark.sql.{DataFrame, QueryTest, Row}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaKernelWarmth,
  VarkaKernelWarmup, VarkaShapeCache}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.util.QueryExecutionListener

/**
 * The kernel warm-up end to end (`spark.sql.codegen.varka.warmup.enabled`, `PLAN_TASK_212.md`
 * 10): a shape's first query serves its batches on the row path while a background thread gets
 * the new kernel compiled, and once the warm-up has its verdict the same shape's next query runs
 * the kernel. Both queries must give the row engine's answers; what the tests pin is which path
 * served each batch, read from the node's metrics.
 *
 * The shared sessions pin the warm-up off, so every test here turns it on for its own queries.
 * Besides the path each batch took, they pin how the batches that did not reach a kernel are
 * counted: a batch waiting on a warm-up - this node's, or that of a Varka node below it - is a
 * warm-up batch, and a batch the evaluator declines while it copies it for a warm-up is the
 * declined batch it would have been on the kernel path.
 */
class VarkaWarmupEndToEndSuite extends QueryTest with VarkaSharedSessions {

  private val numRows = 40000

  /** The cache's default batch holds 10,000 rows, so the table is four batches. */
  private val numBatches = 4L

  private def withWarmup[T](body: => T): T = {
    varkaSpark.conf.set(SQLConf.VARKA_WARMUP_ENABLED.key, "true")
    try body finally varkaSpark.conf.set(SQLConf.VARKA_WARMUP_ENABLED.key, "false")
  }

  /**
   * Runs the query once and checks its rows. Not `checkAnswer`, which runs a query twice - the
   * second run would meet a shape the first had already claimed, and the metrics would count
   * both.
   */
  private def runAndCheck(df: DataFrame, expected: Seq[Row]): Unit = {
    QueryTest.getErrorMessageInCheckAnswer(df, expected, checkToRDD = false).foreach(fail(_))
  }

  /** A metric of the query's Varka node, after running it. */
  private def varkaMetric(df: DataFrame, name: String): Long =
    varkaMetric(df.queryExecution.executedPlan, name)

  private def varkaMetric(plan: SparkPlan, name: String): Long = {
    val node = collectFirst(plan) { case v if isVarkaNode(v) => v }
      .getOrElse(fail(s"no Varka node in:\n${plan.treeString}"))
    node.metrics(name).value
  }

  /** Writes the query to `noop` - a columnar sink - and returns the plan the write executed. */
  private def writeToNoop(sql: String): SparkPlan = {
    @volatile var executed: SparkPlan = null
    val listener = new QueryExecutionListener {
      override def onSuccess(funcName: String, qe: QueryExecution, durationNs: Long): Unit = {
        executed = qe.executedPlan
      }
      override def onFailure(funcName: String, qe: QueryExecution, e: Exception): Unit = {}
    }
    varkaSpark.listenerManager.register(listener)
    try {
      varkaSpark.sql(sql).write.format("noop").mode("append").save()
      varkaSpark.sparkContext.listenerBus.waitUntilEmpty()
    } finally {
      varkaSpark.listenerManager.unregister(listener)
    }
    assert(executed != null, "the write did not report a query execution")
    executed
  }

  /**
   * Runs the query twice on the Varka session with the warm-up on, waiting for the warm-up's
   * verdict in between, and checks both runs against the row engine and which path served them.
   */
  private def checkWarmup(query: String): Unit = {
    cacheDatesBig(spark, numRows)
    val expected = spark.sql(query).collect().toSeq
    cacheDatesBig(varkaSpark, numRows)
    withWarmup {
      // A shape another suite emitted is a class this JVM has already met; start from a new one.
      VarkaShapeCache.invalidateAll()
      val first = varkaSpark.sql(query)
      runAndCheck(first, expected)
      assert(varkaMetric(first, "numWarmupBatches") === numBatches)
      assert(varkaMetric(first, "numVarkaBatches") === 0L)

      assert(VarkaKernelWarmup.awaitIdle(120000), "the warm-up did not finish in two minutes")
      val outcome = VarkaKernelWarmup.recentOutcomes().asScala.last
      logInfo(s"The warm-up of `$query`: $outcome")
      assert(outcome.state() === VarkaKernelWarmth.State.COMPILED, outcome)
      assert(outcome.firstProbeBytes() > outcome.lastProbeBytes(), outcome)

      val second = varkaSpark.sql(query)
      runAndCheck(second, expected)
      assert(varkaMetric(second, "numVarkaBatches") === numBatches)
      assert(varkaMetric(second, "numWarmupBatches") === 0L)
      Seq("numFallbackBatchesNonArrow", "numFallbackBatchesKernel", "numFallbackBatchesRowPath",
        "numFallbackBatchesDeclined").foreach { m =>
        assert(varkaMetric(first, m) === 0L, m)
        assert(varkaMetric(second, m) === 0L, m)
      }
    }
  }

  test("a projection's first query takes the row path and its next one the compiled kernel") {
    checkWarmup(
      "SELECT i, date_add(d, 3) AS a, add_months(d, 2) AS b, last_day(d) AS c " +
        "FROM varka_dates_big")
  }

  test("a filter's first query takes the row path and its next one the compiled kernel") {
    checkWarmup("SELECT i FROM varka_dates_big WHERE date_add(d, 30) > DATE'2020-07-01'")
  }

  test("a columnar consumer's first write takes the row path and its next one the kernel") {
    // The columnar node's row path projects each row and converts it back into vectors, so the
    // sink still receives batches; only the metrics tell the two paths apart.
    cacheDatesBig(varkaSpark, numRows)
    val query = "SELECT date_add(d, 3) AS a, last_day(d) AS b FROM varka_dates_big"
    withWarmup {
      VarkaShapeCache.invalidateAll()
      val first = writeToNoop(query)
      assert(first.find(_.isInstanceOf[VarkaProjectExec]).isDefined,
        s"the write does not consume the columnar Varka node:\n${first.treeString}")
      assert(varkaMetric(first, "numWarmupBatches") === numBatches)
      assert(varkaMetric(first, "numVarkaBatches") === 0L)
      assert(varkaMetric(first, "numOutputRows") === numRows.toLong)
      assert(VarkaKernelWarmup.awaitIdle(120000), "the warm-up did not finish in two minutes")
      assert(VarkaKernelWarmup.recentOutcomes().asScala.last.state() ===
        VarkaKernelWarmth.State.COMPILED)
      val second = writeToNoop(query)
      assert(varkaMetric(second, "numVarkaBatches") === numBatches)
      assert(varkaMetric(second, "numWarmupBatches") === 0L)
      assert(varkaMetric(second, "numOutputRows") === numRows.toLong)
    }
  }

  test("a projection over a filter counts the batches the filter's warm-up sends as warm-up") {
    // The filter's row path emits on-heap batches, which the projection above cannot run its
    // kernel on: while the filter warms, those are warm-up batches for the projection too, not
    // non-Arrow fallbacks. The projection claims its own warm-up at its first Arrow batch.
    cacheDatesBig(varkaSpark, numRows)
    val query = "SELECT date_add(d, 3) AS a FROM varka_dates_big " +
      "WHERE date_add(d, 30) > DATE'2020-07-01'"
    def projection(plan: SparkPlan): SparkPlan =
      collectFirst(plan) { case p: VarkaProjectExec => p }
        .getOrElse(fail(s"no Varka projection in:\n${plan.treeString}"))
    def filter(plan: SparkPlan): SparkPlan =
      collectFirst(plan) { case f: VarkaFilterExec => f }
        .getOrElse(fail(s"no Varka filter in:\n${plan.treeString}"))
    def metric(node: SparkPlan, name: String): Long = node.metrics(name).value
    withWarmup {
      VarkaShapeCache.invalidateAll()
      val runs = mutable.ArrayBuffer(writeToNoop(query))
      assert(metric(projection(runs.head), "numWarmupBatches") === numBatches,
        "the projection cannot have its kernel before the filter below it serves Arrow batches")
      // The filter's warm-up, then the projection's: one run each, and one more that both serve.
      while (runs.size < 4 && Seq(filter(runs.last), projection(runs.last))
          .exists(metric(_, "numVarkaBatches") < numBatches)) {
        assert(VarkaKernelWarmup.awaitIdle(120000), "a warm-up did not finish in two minutes")
        runs += writeToNoop(query)
      }
      Seq(filter(runs.last), projection(runs.last)).foreach { node =>
        assert(metric(node, "numVarkaBatches") === numBatches, node.nodeName)
      }
      for (run <- runs; node <- Seq(filter(run), projection(run))) {
        assert(metric(node, "numVarkaBatches") + metric(node, "numWarmupBatches") === numBatches,
          node.nodeName)
        Seq("numFallbackBatchesNonArrow", "numFallbackBatchesKernel",
          "numFallbackBatchesRowPath", "numFallbackBatchesDeclined").foreach { m =>
          assert(metric(node, m) === 0L, s"${node.nodeName} $m")
        }
      }
    }
  }

  test("a batch declined while it is copied for a warm-up is counted as declined") {
    // Under ANSI a string that is not a weekday name, beside a null date, declines the batch in
    // the evaluator before the kernel would run; the row engine answers NULL for that row. The
    // claim goes back each time, so no warm-up starts and no batch is a warm-up batch.
    cacheDatesWeekdayBadOnNulls(spark)
    cacheDatesWeekdayBadOnNulls(varkaSpark)
    val query = "SELECT next_day(d, s) AS a FROM varka_dates_weekday_bad_on_nulls ORDER BY a"
    withAnsi(true) {
      val expected = spark.sql(query).collect().toSeq
      withWarmup {
        VarkaShapeCache.invalidateAll()
        val df = varkaSpark.sql(query)
        runAndCheck(df, expected)
        assert(varkaMetric(df, "numFallbackBatchesDeclined") > 0L)
        assert(varkaMetric(df, "numWarmupBatches") === 0L)
        assert(varkaMetric(df, "numVarkaBatches") === 0L)
      }
    }
  }
}
