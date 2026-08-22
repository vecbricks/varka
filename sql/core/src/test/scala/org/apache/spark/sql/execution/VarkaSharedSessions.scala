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
 *   - `varkaSpark`: `spark.sql.codegen.varka.enabled=true` with `VarkaColumnarRule` injected -
 *     the actual (fused) side;
 *   - `disabledSpark`: Varka disabled but the rule injected - proves the config gate.
 *
 * AQE is off on the custom sessions so the physical plan is materialized deterministically and
 * the Varka fusion boundary is visible in the executed plan. `InMemoryRelation.clearSerializer()`
 * resets the process-wide serializer singleton around session creation and afterwards so it is
 * not leaked to later suites.
 */
trait VarkaSharedSessions extends SharedSparkSession {

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
    .withExtensions(_.injectColumnar(_ => VarkaColumnarRule))
    .getOrCreate()

  protected def date(value: String): java.sql.Date = java.sql.Date.valueOf(value)

  protected def cacheDates(session: SparkSession): Unit = {
    val dates = Seq(date("2024-01-01"), date("2024-01-02"), date("2023-12-27"),
      date("1969-12-31"), null).zipWithIndex
    session.createDataFrame(dates.map { case (d, i) => (d, i) }).toDF("d", "i")
      .createOrReplaceTempView("varka_dates")
    session.catalog.cacheTable("varka_dates")
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
   * Builds and caches a `varka_dates_big` temp view with `numRows` rows, one null every 17 rows
   * to exercise null handling, and `parts` partitions (via `repartition` when > 1) so the scan
   * fans out over several tasks (one Varka loader per task).
   */
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

  protected def assertKernelsRan(plan: SparkPlan): Unit = {
    val node = plan.collectFirst { case v: VarkaColumnarToRowExec => v }
      .getOrElse(fail(s"expected a VarkaColumnarToRowExec in the plan:\n${plan.treeString}"))
    val varkaBatches = node.metrics.get("numVarkaBatches").map(_.value).getOrElse(0L)
    assert(varkaBatches > 0L,
      s"expected the SIMD kernels to process the cached Arrow batches, got $varkaBatches")
  }

  protected def assertFused(plan: SparkPlan): Unit = {
    assert(plan.find(_.isInstanceOf[VarkaColumnarToRowExec]).isDefined,
      s"expected a VarkaColumnarToRowExec in the plan:\n${plan.treeString}")
  }

  protected def assertNotFused(plan: SparkPlan): Unit = {
    assert(plan.find(_.isInstanceOf[VarkaColumnarToRowExec]).isEmpty,
      s"expected no VarkaColumnarToRowExec in the plan:\n${plan.treeString}")
  }
}
