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
    // kernel) are a plain int add that wraps mod 2^32, but the end-to-end row engine applies an
    // extra calendar-day rebase to DATE results outside its representable range, so the row
    // engine is NOT the right oracle at the overflow boundary (and those days cannot be decoded
    // to java.sql.Date). The oracle here is therefore the plain int32 wrap - DateAdd.eval and
    // this kernel agree - computed in Scala over the fixed `cacheDates` input, null-aware, in
    // deterministic input order.
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
      // `toRdd` hands back the projection's own row, rewritten per row, so the rows have to be
      // copied before they are collected into an array. This is not a Varka rule: the row engine
      // reuses rows here too, and collecting this query from it without the copy yields two
      // distinct row objects for five rows.
      val rows = actual.queryExecution.toRdd.map(_.copy()).collect()
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

  test("a mixed-eligibility projection fuses partially and matches the row engine") {
    // Pinned as "not fused" until task 12: one ineligible entry used to poison the whole
    // projection. Now the date entry runs on the kernels, the bare `i` forwards zero-copy, and
    // `i + 1` is evaluated per row beside them.
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 3) AS a, i, i + 1 AS inc FROM varka_dates ORDER BY a",
      expectFused = true)
  }

  test("a projection of forwards and residuals alone stays unfused") {
    // Nothing to fuse means nothing gained: the rule leaves the projection on Janino.
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT i, i + 1 AS inc FROM varka_dates ORDER BY i",
      expectFused = false)
  }

  test("a predicated entry fuses beside residual and forwarded ones") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT CASE WHEN d < d2 THEN date_add(d, 1) ELSE d2 END AS a, i, i + 1 AS inc " +
        "FROM varka_date_pairs ORDER BY a, i",
      expectFused = true)
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

  test("nested date expressions are fused and match the row engine") {
    // These planned as a plain per-row Project until task 10: the recursive compiler is what
    // makes `expectFused = true` hold here at all.
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(date_add(d, 1), 2) AS a FROM varka_dates ORDER BY a",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT date_sub(date_add(d, 5), 5) AS a FROM varka_dates ORDER BY a",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT date_sub(date_add(date_sub(date_add(d, 1), 2), 3), 4) AS a " +
        "FROM varka_dates ORDER BY a",
      expectFused = true)
  }

  test("datediff over nested chains is fused in both argument orders with nulls") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT datediff(date_add(d, 7), d2) AS diff FROM varka_date_pairs ORDER BY diff",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT datediff(d2, date_sub(d, 7)) AS diff FROM varka_date_pairs ORDER BY diff",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT datediff(date_add(d, 3), date_sub(d2, 3)) AS diff " +
        "FROM varka_date_pairs ORDER BY diff",
      expectFused = true)
  }

  test("a shared subchain across outputs is fused and matches the row engine") {
    // The milestone's DAG example: `date_add(d, 1)` feeds both outputs and the emitted loop
    // computes it once per lane group. Correctness here; the CSE mechanics are pinned in
    // VarkaLoopEmitterSuite and the win is priced in VarkaEmitterParityBenchmark.
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 1) AS a, datediff(date_add(d, 1), d2) AS b " +
        "FROM varka_date_pairs ORDER BY a, b",
      expectFused = true)
  }

  test("a nested chain wraps int32 day arithmetic exactly like the row engine") {
    // The inner add leaves the representable date range and the outer sub wraps it back, so the
    // end-to-end result is decodable and the row engine is a valid oracle for the round trip -
    // unlike a one-way extreme offset (see the wrap-around block in the date_add test above).
    cacheDates(spark)
    cacheDates(varkaSpark)
    val off = Int.MaxValue - 1
    checkDifferential(spark, varkaSpark,
      s"SELECT date_sub(date_add(d, $off), $off) AS a FROM varka_dates ORDER BY a",
      expectFused = true)
  }

  test("a projection with a bare date column fuses and forwards the column zero-copy") {
    // Pinned as "stays unfused until task 12": a bare column output compiles to nothing on
    // purpose - emitting it would be a copy loop - and now forwards as the input's own vector
    // instead (the `eq` assertion lives in VarkaKernelEvaluatorSuite).
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 3) AS a, d FROM varka_dates ORDER BY a",
      expectFused = true)
  }

  test("CASE WHEN over dates is fused and matches the row engine, three-valued nulls included") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT CASE WHEN d < d2 THEN date_add(d, 3) ELSE date_sub(d2, 1) END AS a " +
        "FROM varka_date_pairs ORDER BY a",
      expectFused = true)
    // Three branches, the first match winning; nulls in either column fall through.
    checkDifferential(spark, varkaSpark,
      "SELECT CASE WHEN d < d2 THEN d WHEN d = d2 THEN date_add(d, 1) " +
        "ELSE date_sub(d2, 2) END AS a FROM varka_date_pairs ORDER BY a",
      expectFused = true)
    // A CASE with no ELSE has a null-literal branch and must stay on the row engine.
    checkDifferential(spark, varkaSpark,
      "SELECT CASE WHEN d < d2 THEN date_add(d, 3) END AS a FROM varka_date_pairs ORDER BY a",
      expectFused = false)
  }

  test("IF with BETWEEN and date literals is fused and matches the row engine") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT IF(d BETWEEN DATE'2023-12-01' AND DATE'2024-01-01', date_add(d, 7), d) AS a " +
        "FROM varka_dates ORDER BY a",
      expectFused = true)
  }

  test("AND, OR and NOT conditions follow three-valued logic like the row engine") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT CASE WHEN NOT(d < d2) OR d = d2 THEN date_add(d, 1) ELSE d2 END AS a " +
        "FROM varka_date_pairs ORDER BY a",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT CASE WHEN d <= d2 AND NOT(d = d2) THEN d ELSE date_sub(d2, 3) END AS a " +
        "FROM varka_date_pairs ORDER BY a",
      expectFused = true)
  }

  test("greatest and least skip nulls and fuse, nested chains included") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT greatest(d, d2) AS a, least(d, d2) AS b FROM varka_date_pairs ORDER BY a, b",
      expectFused = true)
    // The milestone's irreducible chain, plus a three-arg fold with a date literal.
    checkDifferential(spark, varkaSpark,
      "SELECT greatest(date_add(d, 7), d2) AS a, " +
        "least(d, d2, DATE'2024-01-15') AS b FROM varka_date_pairs ORDER BY a, b",
      expectFused = true)
  }

  test("dayofweek and weekday match the row engine across 1970 and nulls") {
    val rows = Seq("2024-01-01", "1969-12-31", "1969-01-05", "1900-02-28", "2100-07-04", null)
    Seq(spark, varkaSpark).foreach { session =>
      import scala.jdk.CollectionConverters._
      val schema = org.apache.spark.sql.types.StructType(Seq(
        org.apache.spark.sql.types.StructField("d", org.apache.spark.sql.types.DateType, true)))
      val data = rows.map(v =>
        org.apache.spark.sql.Row(if (v == null) null else java.sql.Date.valueOf(v)))
      session.createDataFrame(data.asJava, schema).createOrReplaceTempView("varka_dow")
      session.catalog.cacheTable("varka_dow")
    }
    try {
      checkDifferential(spark, varkaSpark,
        "SELECT dayofweek(d) AS a, weekday(d) AS b, dayofweek(date_add(d, 1)) AS c " +
          "FROM varka_dow ORDER BY a, b, c",
        expectFused = true)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_dow"))
    }
  }

  test("the rule fires and the kernels run under AQE") {
    // Every Varka session disables AQE for plan determinism, so this pins the default-config
    // path. With AQE on the fused node sits inside a query stage, which a plain
    // SparkPlan.collect never descends into: the shared assertions are stage-aware since
    // task 17, and these two tests are what keeps them that way.
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    varkaSpark.conf.set(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true")
    try {
      val query = "SELECT CASE WHEN d < d2 THEN date_add(d, 3) ELSE d2 END AS a " +
        "FROM varka_date_pairs ORDER BY a"
      val expected = spark.sql(query)
      val actual = varkaSpark.sql(query)
      checkAnswer(actual, expected)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      assertKernelsRan(plan)
    } finally {
      varkaSpark.conf.set(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
    }
  }

  test("the rule fires and the kernels run under AQE on a mixed projection") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    varkaSpark.conf.set(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true")
    try {
      val query = "SELECT date_add(d, 3) AS a, i, i + 1 AS inc FROM varka_dates ORDER BY a"
      val expected = spark.sql(query)
      val actual = varkaSpark.sql(query)
      checkAnswer(actual, expected)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      assertKernelsRan(plan)
    } finally {
      varkaSpark.conf.set(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
    }
  }

  test("a kernel failure on a mixed projection falls back whole-batch with correct results") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    VarkaColumnarToRowExec.setFailKernelForTesting(true)
    try {
      val q = "SELECT date_add(d, 3) AS a, i, i + 1 AS inc FROM varka_dates ORDER BY a"
      val expected = spark.sql(q)
      val actual = varkaSpark.sql(q)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, expected)
      val batches = plan.collectFirst { case v: VarkaColumnarToRowExec => v }
        .flatMap(_.metrics.get("numVarkaBatches")).map(_.value).getOrElse(0L)
      assert(batches === 0L, s"expected the fallback to serve every batch, got $batches")
    } finally {
      VarkaColumnarToRowExec.setFailKernelForTesting(false)
    }
  }

  test("a kernel failure on a predicated plan falls back per batch with correct results") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    VarkaColumnarToRowExec.setFailKernelForTesting(true)
    try {
      val q = "SELECT CASE WHEN d < d2 THEN date_add(d, 1) ELSE d2 END AS a " +
        "FROM varka_date_pairs ORDER BY a"
      val expected = spark.sql(q)
      val actual = varkaSpark.sql(q)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, expected)
      val batches = plan.collectFirst { case v: VarkaColumnarToRowExec => v }
        .flatMap(_.metrics.get("numVarkaBatches")).map(_.value).getOrElse(0L)
      assert(batches === 0L, s"expected the fallback to serve every batch, got $batches")
    } finally {
      VarkaColumnarToRowExec.setFailKernelForTesting(false)
    }
  }

  test("a kernel failure on a nested plan falls back per batch with correct results") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    VarkaColumnarToRowExec.setFailKernelForTesting(true)
    try {
      val query = "SELECT datediff(date_add(d, 1), d) AS a FROM varka_dates ORDER BY a"
      val expected = spark.sql(query)
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, expected)
      val batches = plan.collectFirst { case v: VarkaColumnarToRowExec => v }
        .flatMap(_.metrics.get("numVarkaBatches")).map(_.value).getOrElse(0L)
      assert(batches === 0L, s"expected the fallback to serve every batch, got $batches")
    } finally {
      VarkaColumnarToRowExec.setFailKernelForTesting(false)
    }
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
