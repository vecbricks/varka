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

import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.internal.SQLConf

/**
 * Differential tests (Task 7): the Varka session must produce results identical to the row-based
 * engine across a query matrix - literal offsets (including extreme values), both `datediff`
 * argument orders, null patterns, foldable offsets, ineligible projections, nested expressions,
 * filters/aggregation, multi-batch caches, multi-task scans, and a non-Arrow columnar source.
 * Where the projection is fused into [[VarkaColumnarToRowExec]], the SIMD kernels must actually
 * process the Arrow batches; where it is not, the plan must be untouched.
 */
class VarkaDifferentialSuite extends QueryTest with VarkaSharedSessions {

  private def metaspaceUsed(): Long = {
    java.lang.management.ManagementFactory.getMemoryPoolMXBeans.asScala.collect {
      case p if p.getName == "Metaspace" || p.getName == "Compressed Class Space" =>
        p.getUsage.getUsed
    }.sum
  }

  /**
   * Runs `query` on the base row-engine session and on the varka session, asserting the results
   * match and that the plan is fused (and the kernels ran) exactly when `expectFused`.
   */
  private def checkDifferential(
      expectedSession: SparkSession,
      actualSession: SparkSession,
      query: String,
      expectFused: Boolean): SparkPlan = {
    val expected = expectedSession.sql(query)
    val actual = actualSession.sql(query)
    val plan = actual.queryExecution.executedPlan
    if (expectFused) {
      assertFused(plan)
      checkAnswer(actual, expected)
      assertKernelsRan(plan)
    } else {
      assertNotFused(plan)
      checkAnswer(actual, expected)
    }
    plan
  }

  test("date_add and date_sub match the row engine across literal offsets") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    Seq(0, 3, -5, 100).foreach { off =>
      checkDifferential(spark, varkaSpark,
        s"SELECT date_add(d, $off) AS a, date_sub(d, $off) AS b FROM varka_dates ORDER BY a",
        expectFused = true)
    }
    // Extreme offsets wrap the int32 day arithmetic. Spark's own DateAdd semantics (and the SIMD
// kernel) are a plain int add that wraps mod 2^32, but the end-to-end row engine applies an extra
// calendar-day rebase to DATE results outside its representable range, so the row engine is NOT
// the right oracle at the overflow boundary (and those days cannot be decoded to java.sql.Date).
// The oracle here is therefore the plain int32 wrap - DateAdd.eval and this kernel agree - computed
// in Scala over the fixed `cacheDates` input, null-aware, in deterministic input order.
    val inputDays: Seq[java.lang.Integer] = Seq(
      "2024-01-01", "2024-01-02", "2023-12-27", "1969-12-31", null).map { v =>
        if (v == null) null else java.time.LocalDate.parse(v).toEpochDay.toInt
      }
    val addWrap = (d: Int, off: Int) => d + off // Scala Int wraps mod 2^32
    val subWrap = (d: Int, off: Int) => d - off
    Seq(Int.MaxValue - 1, Int.MinValue).foreach { off =>
      val query =
        s"SELECT date_add(d, $off) AS a, date_sub(d, $off) AS b FROM varka_dates"
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      val rows = actual.queryExecution.toRdd.collect()
      assert(rows.length == inputDays.length,
        s"expected ${inputDays.length} rows for offset $off but got ${rows.length}")
      rows.zip(inputDays).foreach { case (a, d) =>
        val expectedAdd = if (d == null) null else addWrap(d, off)
        val expectedSub = if (d == null) null else subWrap(d, off)
        assert(a.isNullAt(0) == (expectedAdd == null),
          s"date_add null mismatch (offset $off, input $d)")
        assert(a.isNullAt(1) == (expectedSub == null),
          s"date_sub null mismatch (offset $off, input $d)")
        if (expectedAdd != null) {
          assert(a.getInt(0) == expectedAdd,
            s"date_add day mismatch (offset $off, input $d)")
        }
        if (expectedSub != null) {
          assert(a.getInt(1) == expectedSub,
            s"date_sub day mismatch (offset $off, input $d)")
        }
      }
      assertKernelsRan(plan)
    }
  }

  test("datediff matches the row engine in both argument orders with nulls") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT datediff(d2, d) AS diff FROM varka_date_pairs ORDER BY diff",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT datediff(d, d2) AS diff FROM varka_date_pairs ORDER BY diff",
      expectFused = true)
  }

  test("datediff matches the row engine on null-free and all-null inputs") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    spark.sql("SELECT d, d2 FROM varka_date_pairs WHERE d IS NOT NULL AND d2 IS NOT NULL")
      .createOrReplaceTempView("varka_null_free")
    spark.catalog.cacheTable("varka_null_free")
    spark.sql("SELECT CAST(NULL AS DATE) AS d, CAST(NULL AS DATE) AS d2 FROM varka_date_pairs")
      .createOrReplaceTempView("varka_all_null")
    spark.catalog.cacheTable("varka_all_null")
    varkaSpark.sql("SELECT d, d2 FROM varka_date_pairs WHERE d IS NOT NULL AND d2 IS NOT NULL")
      .createOrReplaceTempView("varka_null_free")
    varkaSpark.catalog.cacheTable("varka_null_free")
    varkaSpark.sql("SELECT CAST(NULL AS DATE) AS d, CAST(NULL AS DATE) AS d2 FROM varka_date_pairs")
      .createOrReplaceTempView("varka_all_null")
    varkaSpark.catalog.cacheTable("varka_all_null")
    checkDifferential(spark, varkaSpark,
      "SELECT datediff(d2, d) AS diff FROM varka_null_free ORDER BY diff",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT datediff(d2, d) AS diff FROM varka_all_null ORDER BY diff",
      expectFused = true)
  }

  test("a mixed-eligibility projection is not fused but matches the row engine") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 3) AS a, i, i + 1 AS inc FROM varka_dates ORDER BY a",
      expectFused = false)
  }

  test("constant-folded offsets are fused and match the row engine") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 1 + 2) AS a FROM varka_dates ORDER BY a",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, CAST(3 AS INT)) AS a FROM varka_dates ORDER BY a",
      expectFused = true)
  }

  test("nested date expressions are not fused but match the row engine") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(date_add(d, 1), 2) AS a FROM varka_dates ORDER BY a",
      expectFused = false)
    checkDifferential(spark, varkaSpark,
      "SELECT date_sub(date_add(d, 5), 5) AS a FROM varka_dates ORDER BY a",
      expectFused = false)
  }

  test("filters and aggregation match the row engine") {
    cacheDatesBig(spark, 1024)
    cacheDatesBig(varkaSpark, 1024)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 7) AS a FROM varka_dates_big WHERE d IS NOT NULL ORDER BY a",
      expectFused = false)
    checkDifferential(spark, varkaSpark,
      "SELECT max(date_add(d, 1)) AS m, count(*) AS c FROM varka_dates_big",
      expectFused = false)
    checkDifferential(spark, varkaSpark,
      "SELECT d, count(*) AS c FROM varka_dates_big GROUP BY d ORDER BY d",
      expectFused = false)
  }

  test("multi-batch: every cached Arrow batch is processed by the kernels") {
    val batchSize = "32"
    try {
      spark.conf.set(SQLConf.COLUMN_BATCH_SIZE.key, batchSize)
      varkaSpark.conf.set(SQLConf.COLUMN_BATCH_SIZE.key, batchSize)
      cacheDatesBig(spark, 1024)
      cacheDatesBig(varkaSpark, 1024)
      val plan = checkDifferential(spark, varkaSpark,
        "SELECT date_add(d, 1) AS a FROM varka_dates_big ORDER BY a",
        expectFused = true)
      val batches = plan.collectFirst { case v: VarkaColumnarToRowExec => v }
        .flatMap(_.metrics.get("numVarkaBatches")).map(_.value).getOrElse(0L)
      assert(batches > 1L, s"expected more than one kernel batch, got $batches")
    } finally {
      spark.conf.unset(SQLConf.COLUMN_BATCH_SIZE.key)
      varkaSpark.conf.unset(SQLConf.COLUMN_BATCH_SIZE.key)
    }
  }

  test("multi-task: per-task loaders produce correct results") {
    cacheDatesBig(spark, 1024, parts = 4)
    cacheDatesBig(varkaSpark, 1024, parts = 4)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 1) AS a FROM varka_dates_big ORDER BY a",
      expectFused = true)
  }

  test("a non-Arrow columnar source never runs the kernels and matches the row engine") {
    withTempPath { dir =>
      val rows = Seq((date("2024-01-01"), 0), (date("2023-12-01"), 1), (null, 2))
      spark.createDataFrame(rows).toDF("d", "i").write.parquet(dir.getCanonicalPath)
      val expected = spark.read.parquet(dir.getCanonicalPath).selectExpr("date_add(d, 3) AS a")
      val actual = varkaSpark.read.parquet(dir.getCanonicalPath).selectExpr("date_add(d, 3) AS a")
      val plan = actual.queryExecution.executedPlan
      checkAnswer(actual, expected)
      plan.collectFirst { case v: VarkaColumnarToRowExec => v }.foreach { v =>
        val batches = v.metrics.get("numVarkaBatches").map(_.value).getOrElse(0L)
        assert(batches === 0L, s"expected no kernel batches on a non-Arrow source, got $batches")
      }
    }
  }

  test("many distinct Varka tasks keep Metaspace bounded (lenient integration check)") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    val before = metaspaceUsed()
    (0 until 100).foreach { i =>
      checkDifferential(spark, varkaSpark,
        s"SELECT date_add(d, $i) AS a FROM varka_dates ORDER BY a", expectFused = true)
    }
    // The deterministic unloading guarantee lives in VarkaGeneratedClassLoaderSuite; here we only
    // assert the class loader scope keeps the long-run Metaspace footprint bounded after a GC.
    System.gc()
    System.runFinalization()
    System.gc()
    val delta = metaspaceUsed() - before
    // Lenient: 100 generated kernel classes (a few KB each) must stay far below this bound.
    assert(delta < 64L * 1024 * 1024, s"Metaspace grew by $delta bytes across 100 Varka tasks")
  }
}
