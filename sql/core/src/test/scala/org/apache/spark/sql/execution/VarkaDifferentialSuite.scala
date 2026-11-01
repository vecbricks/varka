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

import org.apache.spark.{SparkArithmeticException, SparkDateTimeException, SparkIllegalArgumentException}
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaMatrix, VarkaShapeCache}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.internal.SQLConf

/**
 * Differential tests (VARKA-7) through two sessions: what [[VarkaDifferentialHarnessSuite]],
 * which holds the date, interval and integer kernels' answers with no session, cannot check -
 * the plan around the Varka nodes (a narrowing projection absorbed, a residual conjunct's row
 * filter and the order it is evaluated in, a stacked projection over a filter's compacted
 * batch, a hoisted common expression), the fallback hooks and the metrics they move, AQE,
 * aggregation, the cache's batches and a cached view of fused work, tasks sharing a kernel
 * class, a non-Arrow source, the SQL UI's residual count, Metaspace, and the error a user sees
 * where no coverage row raises it. Where the projection is fused into [[VarkaColumnarToRowExec]],
 * the SIMD kernels must actually process the Arrow batches; where it is not, the plan must be
 * untouched.
 */
class VarkaDifferentialSuite extends QueryTest with VarkaMaybeThrows with VarkaTestWatchdog {

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
      // VARKA-18: execute the query a second time, so the kernel class is served from the warm
      // cross-task cache - a wrong or stale hit would surface as a wrong answer right here,
      // which the ghost fallback could never catch.
      checkAnswer(actualSession.sql(query), expected)
    } else {
      assertNotFused(plan)
      checkAnswer(actual, expected)
    }
    plan
  }

  test("a projection with a nondeterministic entry runs as vanilla's, to rows and to batches") {
    // The compiler declines such a projection, so no Varka node evaluates `rand`: the values are
    // vanilla's, drawn from one seeded generator per partition, whichever sink takes them.
    cacheDates(varkaSpark)
    cacheDates(disabledSpark)
    val query = "SELECT date_add(d, 1) AS a, rand(7) AS r FROM varka_dates"
    checkDifferential(disabledSpark, varkaSpark, query, expectFused = false)
    varkaSpark.sql(query).write.format("noop").mode("overwrite").save()
  }

  test("a projection that only narrows a Varka filter leaves no Project above it") {
    // The milestone's one shape where Varka was slower than stock Spark end to end. The
    // predicate reads two columns and the consumer wants one, so Spark's column pruning
    // cannot drop the projection - it is not redundant. Left in place, a Janino `Project [d]`
    // would sit above the row-producing filter: an operator boundary out of the node's row
    // iterator, and a row conversion over `d2` as well as `d` whose second column the
    // projection immediately discards (VARKA-78).
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    try {
      val q = "SELECT d FROM varka_date_pairs WHERE d < d2"
      val actual = varkaSpark.sql(q)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, spark.sql(q))

      val filterNode = plan.collectFirst { case f: VarkaFilterColumnarToRowExec => f }
      assert(filterNode.isDefined, s"expected the row-out Varka filter:\n${plan.treeString}")
      assert(filterNode.get.narrowing.isDefined,
        s"expected the projection absorbed into the filter:\n${plan.treeString}")
      assert(plan.collectFirst { case p: ProjectExec => p }.isEmpty,
        s"no row Project should remain above the filter:\n${plan.treeString}")
      assert(filterNode.get.output.map(_.name) === Seq("d"))
      assert(filterNode.get.metrics("numFallbackBatchesKernel").value === 0L)

      // The one-column predicate is the control: its projection was already redundant and
      // Spark removed it before any columnar rule ran, so there is nothing to absorb and the
      // node is the one VARKA-21 shipped. This is the assertion that VARKA-78 moved only the
      // shape that was losing.
      val control = varkaSpark.sql("SELECT d FROM varka_date_pairs WHERE d < DATE'2024-02-01'")
      val controlPlan = control.queryExecution.executedPlan
      checkAnswer(control, spark.sql("SELECT d FROM varka_date_pairs WHERE d < DATE'2024-02-01'"))
      assert(controlPlan.collectFirst { case f: VarkaFilterColumnarToRowExec => f }
        .exists(_.narrowing.isEmpty),
        s"the one-column predicate should need no narrowing:\n${controlPlan.treeString}")
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_date_pairs"))
    }
  }

  /** The Varka node's metric, zero if the node or the metric is absent. */
  private def varkaMetric(plan: SparkPlan, name: String): Long =
    plan.collectFirst { case v if isVarkaNode(v) => v }
      .flatMap(_.metrics.get(name)).map(_.value).getOrElse(0L)

  test("with the guard off, the far batch runs on the kernel - asserted on the " +
      "metric, never on the value") {
    // The reference variant (guardDayProducers = false) is VARKA-51's bytes: the far batch is
    // computed, and computed wrongly past the range. VARKA-51.md section 3 is why no test
    // encodes that answer as green - this asserts only that the batch was not declined, which
    // is what the A/B in VarkaEmitterParityBenchmark measures the cost of.
    cacheDatesFarOffset(spark)
    cacheDatesFarOffset(varkaSpark)
    VarkaColumnarToRowExec.setEmitOptionsForTesting(
      VarkaMatrix.base.withGuardDayProducers(false))
    try {
      val q = "SELECT year(date_add(d, off)) AS y FROM varka_dates_far_offset ORDER BY y"
      val df = varkaSpark.sql(q)
      df.collect()
      val plan = df.queryExecution.executedPlan
      assertFused(plan)
      assert(varkaMetric(plan, "numFallbackBatchesDeclined") === 0L)
      assert(varkaMetric(plan, "numVarkaBatches") > 0L)
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaMatrix.base)
    }
  }

  test("BETWEEN over a computed input fuses through the common-expression hoist") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    // A non-cheap BETWEEN input hoists into `_common_expr_0` in its own Project; the hoisted
    // arithmetic and the IF over its ref fuse as stacked Varka nodes.
    checkDifferential(spark, varkaSpark,
      "SELECT IF(date_add(d, 7) BETWEEN d2 AND date_add(d2, 40), d, date_sub(d2, 1)) AS a " +
        "FROM varka_date_pairs ORDER BY a",
      expectFused = true)
  }

  test("a fused conjunct that can raise sees only the rows the residual conjuncts before it " +
      "let through, as Spark evaluates them") {
    // VARKA-273. Spark evaluates a filter's conjuncts in order and stops at the first false one,
    // so `make_date(2021, i, 1)` never runs on the row whose `s` is not 'x', and that row's
    // month of 13 raises nothing under ANSI. Varka fuses the `make_date` conjunct and leaves
    // `s = 'x'`, which it cannot compile, in a row filter above its node; the kernel declines
    // the batch the invalid month is in, and the declined batch must still be filtered in
    // Spark's order.
    Seq(spark, varkaSpark).foreach { session =>
      session.createDataFrame(Seq(
          ("x", Int.box(5), date("2021-12-31")),
          ("y", Int.box(13), date("2021-12-31")),
          ("x", Int.box(6), date("2021-01-01"))))
        .toDF("s", "i", "d").coalesce(1)
        .createOrReplaceTempView("varka_filter_order")
      session.catalog.cacheTable("varka_filter_order")
    }
    try {
      withAnsi(true) {
        val q = "SELECT s, i, d FROM varka_filter_order " +
          "WHERE s = 'x' AND make_date(2021, i, 1) < d ORDER BY i"
        val plan = varkaSpark.sql(q).queryExecution.executedPlan
        // The shape under test: the make_date conjunct fused, `s = 'x'` in a row filter above.
        assertFused(plan)
        assert(collectFirst(plan) {
          case f: FilterExec if f.condition.references.exists(_.name == "s") => f
        }.isDefined, s"expected `s = 'x'` in a row filter above the Varka node:\n$plan")
        // The table's one batch holds the invalid month, so the kernel declines it: what is
        // compared is how the declined batch is filtered, not the kernel's answer.
        checkAnswer(varkaSpark.sql(q), spark.sql(q).collect().toSeq)
      }
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_filter_order"))
    }
  }

  test("a residual conjunct that can raise sees every row Spark evaluates it on, so Varka " +
      "raises where Spark raises and fuses only the conjuncts Spark evaluates before it") {
    // VARKA-380, the mirror of the test above. BIGINT arithmetic does not fuse, so `(l + 1) > 0`
    // stays in the row filter above the Varka node; under ANSI it raises on the row whose `l` is
    // the largest BIGINT, and a fused date conjunct that rejects that row must not run first.
    // Under LEGACY the sum wraps and raises nothing, so the date conjuncts fuse as before; a
    // year-month sum overflows in both modes. One partition, so one error is raised first.
    Seq(spark, varkaSpark).foreach { session =>
      session.sql(
        """SELECT * FROM VALUES
          |  (5, 1L, DATE'2021-03-01', make_ym_interval(1, 0)),
          |  (6, 9223372036854775807L, DATE'2021-12-31', make_ym_interval(178956969, 0)),
          |  (7, 2L, DATE'2021-01-15', make_ym_interval(2, 0))
          |AS t(i, l, d, ym)""".stripMargin)
        .coalesce(1).createOrReplaceTempView("varka_raising_residual")
      session.catalog.cacheTable("varka_raising_residual")
      // The codegen order's case: `d IS NOT NULL` written first runs after `(l + 1) > 0`, which
      // does not read `d`, so Spark raises on the row whose `d` is null.
      session.sql(
        """SELECT * FROM VALUES
          |  (1L, DATE'2021-03-01'),
          |  (9223372036854775807L, CAST(NULL AS DATE))
          |AS t(l, d)""".stripMargin)
        .coalesce(1).createOrReplaceTempView("varka_raising_null_test")
      session.catalog.cacheTable("varka_raising_null_test")
      // The fallback's case: every conjunct fuses, the kernel declines the batch on the month
      // of 13, and the per-row fallback must evaluate `d2 IS NOT NULL` where codegen does, last.
      session.sql(
        """SELECT * FROM VALUES
          |  (13, DATE'2021-12-31', CAST(NULL AS DATE)),
          |  (5, DATE'2021-12-31', DATE'2021-01-01')
          |AS t(i, d, d2)""".stripMargin)
        .coalesce(1).createOrReplaceTempView("varka_raising_fallback")
      session.catalog.cacheTable("varka_raising_fallback")
    }
    def plan(q: String): SparkPlan = varkaSpark.sql(q).queryExecution.executedPlan
    val t = "FROM varka_raising_residual WHERE"
    try {
      withAnsi(true) {
        // Raising first: nothing Spark evaluates after it may fuse.
        val first = s"SELECT i $t (l + 1) > 0 AND d < DATE'2021-06-01'"
        assertNotFused(plan(first))
        checkSparkRaisesTheSame[SparkArithmeticException](first)
        // Between two fused conjuncts: the first stays fused, the overflow row passes it.
        val between = s"SELECT i $t d > DATE'2021-02-01' AND (l + 1) > 0 AND d < DATE'2021-06-01'"
        assertFused(plan(between))
        checkSparkRaisesTheSame[SparkArithmeticException](between)
        // A fused conjunct written first rejects the overflow row before the sum runs, on both.
        val rejected = s"SELECT i $t d < DATE'2021-06-01' AND (l + 1) > 0 AND i > 5 ORDER BY i"
        assertFused(plan(rejected))
        assert(checkSparkAnswerMaybeThrows(rejected) == VarkaMaybeThrows.Answered(Seq(Row(7))))
        val nullTest = "SELECT l FROM varka_raising_null_test WHERE d IS NOT NULL AND (l + 1) > 0"
        assertNotFused(plan(nullTest))
        checkSparkRaisesTheSame[SparkArithmeticException](nullTest)
        val fallback =
          "SELECT i FROM varka_raising_fallback WHERE d2 IS NOT NULL AND make_date(2021, i, 1) < d"
        assertFused(plan(fallback))
        checkSparkRaisesTheSame[SparkDateTimeException](fallback)
        // Without whole-stage codegen Spark evaluates the condition as written and the null test
        // drops the row first: no error on either engine, so the fallback follows that order.
        withBothConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key, "false") {
          assertFused(plan(fallback))
          assert(checkSparkAnswerMaybeThrows(fallback) == VarkaMaybeThrows.Answered(Seq(Row(5))))
        }
      }
      withAnsi(false) {
        val first = s"SELECT i $t (l + 1) > 0 AND d < DATE'2021-06-01' ORDER BY i"
        assertFused(plan(first))
        assert(checkSparkAnswerMaybeThrows(first) == VarkaMaybeThrows.Answered(Seq(Row(5), Row(7))))
        val interval = s"SELECT i $t (ym + ym) > ym AND d < DATE'2021-06-01'"
        checkSparkRaisesTheSame[SparkArithmeticException](interval)
      }
    } finally {
      Seq(spark, varkaSpark).foreach { session =>
        session.catalog.uncacheTable("varka_raising_residual")
        session.catalog.uncacheTable("varka_raising_null_test")
        session.catalog.uncacheTable("varka_raising_fallback")
        Seq("varka_raising_residual", "varka_raising_null_test", "varka_raising_fallback")
          .foreach(session.catalog.dropTempView)
      }
    }
  }

  test("a projection over a Varka filter's compacted batch answers through the row " +
      "path, because the filter compacts a string column generically (recorded limitation)") {
    // The filter's compaction (VARKA-21) rebuilds fixed-width Arrow columns as Arrow and every
    // other column through the generic on-heap pass, so the string column reaches the stacked
    // projection as a non-Arrow vector and the derived leaf's batch is refused - correctly,
    // and counted under the non-Arrow cause. Pinned so the day the compaction learns strings
    // this test says so; the plan's section 9 records it for the debt register.
    cacheDatesWeekday(spark)
    cacheDatesWeekday(varkaSpark)
    withAnsi(false) {
      val q = "SELECT next_day(d, s) AS a FROM varka_dates_weekday WHERE d2 IS NOT NULL ORDER BY a"
      val expected = spark.sql(q)
      val actual = varkaSpark.sql(q)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, expected)
      assert(varkaMetric(plan, "numFallbackBatchesNonArrow") > 0L,
        "the compacted string column should have refused the projection's batch")
      assert(varkaMetric(plan, "numFallbackBatchesRowPath") === 0L)
    }
  }

  test("under ANSI the valid rows fuse, a non-name beside a live date raises the " +
      "row engine's own error, and one beside a null date is NULL with no error") {
    // The error a user sees: the leaf declines the batch on the first non-name and the row
    // engine raises ILLEGAL_DAY_OF_WEEK for the same row, so the two errors are one error. The
    // valid rows' and the null dates' answers are VarkaDifferentialHarnessSuite's.
    cacheDatesWeekday(spark)
    cacheDatesWeekday(varkaSpark)
    withAnsi(true) {
      val q = "SELECT next_day(d, s) AS a FROM varka_dates_weekday ORDER BY a"
      assertFused(varkaSpark.sql(q).queryExecution.executedPlan)
      checkSparkRaisesTheSame[SparkIllegalArgumentException](q)
    }
  }

  test("the rule fires and the kernels run under AQE") {
    // Every Varka session disables AQE for plan determinism, so this pins the default-config
    // path. With AQE on the fused node sits inside a query stage, which a plain
    // SparkPlan.collect never descends into: the shared assertions are stage-aware since
    // VARKA-17, and these two tests are what keeps them that way.
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
      val query = "SELECT date_add(d, 3) AS a, i, i % 7 AS inc FROM varka_dates ORDER BY a"
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

  test("a declined batch falls back with the row engine's answers, counted as its own cause") {
    // VARKA-26: a partial lowering (the narrowed civil-from-days one) reports a batch it cannot
    // compute, and the evaluator recomputes it row by row. The VARKA-52 tests reach that path
    // with real out-of-range days and no hook; this one uses the hook to make a whole-query
    // fallback cheap to assert without depending on any expression's range. What
    // it proves is the routing - that a declined batch answers correctly, and lands under its
    // own metric rather than the ghost fallback's, which is a defect count and must stay
    // clean.
    cacheDates(spark)
    cacheDates(varkaSpark)
    VarkaColumnarToRowExec.setDeclineKernelForTesting(true)
    try {
      val q = "SELECT year(d) AS a, date_add(d, 3) AS b FROM varka_dates ORDER BY a, b"
      val expected = spark.sql(q)
      val actual = varkaSpark.sql(q)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, expected)
      def metric(name: String): Long = plan.collectFirst { case v: VarkaColumnarToRowExec => v }
        .flatMap(_.metrics.get(name)).map(_.value).getOrElse(0L)
      assert(metric("numVarkaBatches") === 0L, "no batch should have been served by the kernel")
      assert(metric("numFallbackBatchesDeclined") > 0L, "the declined metric should have fired")
      assert(metric("numFallbackBatchesKernel") === 0L,
        "a declined batch is not a kernel failure and must not be counted as one")
    } finally {
      VarkaColumnarToRowExec.setDeclineKernelForTesting(false)
    }
  }

  test("a kernel failure on a mixed projection falls back whole-batch with correct results") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    VarkaColumnarToRowExec.setFailKernelForTesting(true)
    try {
      val q = "SELECT date_add(d, 3) AS a, i, i % 7 AS inc FROM varka_dates ORDER BY a"
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
    // The WHERE below fuses (VARKA-21): the filter runs the mask kernel and the projection
    // stacks on the compacted batches.
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 7) AS a FROM varka_dates_big WHERE d IS NOT NULL ORDER BY a",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT max(date_add(d, 1)) AS m, count(*) AS c FROM varka_dates_big",
      expectFused = false)
    checkDifferential(spark, varkaSpark,
      "SELECT d, count(*) AS c FROM varka_dates_big GROUP BY d ORDER BY d",
      expectFused = false)
  }

  test("the survey's filter shapes match the row engine, warm cache included") {
    cacheDatesBig(spark, 2048)
    cacheDatesBig(varkaSpark, 2048)
    // BETWEEN - the survey's dominant date predicate; the optimizer hands it over as paired
    // comparisons on the AND spine. A bare-column output keeps the plan on the row-boundary
    // filter node (no compaction).
    checkDifferential(spark, varkaSpark,
      "SELECT d FROM varka_dates_big " +
        "WHERE d BETWEEN DATE'2020-02-01' AND DATE'2020-06-01' ORDER BY d",
      expectFused = true)
    // The dominant end-to-end shape: WHERE plus an aggregate.
    checkDifferential(spark, varkaSpark,
      "SELECT count(*) AS c FROM varka_dates_big " +
        "WHERE d BETWEEN DATE'2020-02-01' AND DATE'2020-06-01'",
      expectFused = true)
    // IN - the VARKA-20 lowering, at a filter root (the benchmark's anchor shape).
    checkDifferential(spark, varkaSpark,
      "SELECT count(*) AS c FROM varka_dates_big " +
        "WHERE d IN (DATE'2020-01-02', DATE'2020-03-04', DATE'2020-11-30')",
      expectFused = true)
    // A projection stacked on the filter: the compacted batch must keep the Arrow
    // invariant, so the projection's kernels run over it rather than falling back.
    val stacked = checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 7) AS a FROM varka_dates_big " +
        "WHERE d < DATE'2020-06-01' ORDER BY a",
      expectFused = true)
    val filterNode = stacked.collectFirst { case f: VarkaFilterExec => f }
    assert(filterNode.isDefined, s"expected a compacting filter node:\n${stacked.treeString}")
    assert(filterNode.get.metrics("numVarkaBatches").value > 0L)
    assert(filterNode.get.metrics("numFallbackBatchesNonArrow").value === 0L)
  }

  test("a mixed predicate splits - the residual conjunct stays above, correct") {
    cacheDatesBig(spark, 1024)
    cacheDatesBig(varkaSpark, 1024)
    val plan = checkDifferential(spark, varkaSpark,
      "SELECT i FROM varka_dates_big WHERE d < DATE'2020-06-01' AND i % 3 = 0 ORDER BY i",
      expectFused = true)
    // The int conjunct cannot fuse: it must survive as a row FilterExec above the Varka
    // node, seeing only the rows the mask kernel let through.
    assert(collectFirst(plan) { case f: FilterExec => f }.isDefined,
      s"expected the residual conjunct's row filter in the plan:\n${plan.treeString}")
  }

  test("the driver-side residual count reaches the SQL UI store") {
    // The listener aggregates task-end updates and posted driver updates only: a driver-side
    // `+=` that is not posted is visible to plan.metrics (what the exec suites read) but
    // never to the UI. This goes through a real tracked execution on the Arrow-backed varka
    // session - the default cache serializer has no columnar output for DateType at all, so
    // only this harness can plan the Varka node - and asserts on the status store, the same
    // surface the SQL tab renders.
    cacheDates(varkaSpark)
    // One fused entry keeps the projection eligible; `i % 7` is residual beside it.
    varkaSpark.sql("SELECT date_add(d, 1) AS a, i % 7 AS b FROM varka_dates").collect()
    varkaSpark.sparkContext.listenerBus.waitUntilEmpty()
    val statusStore = varkaSpark.sharedState.statusStore
    val executionId = statusStore.executionsList().reverse
      .find(_.physicalPlanDescription.contains("VarkaColumnarToRow"))
      .map(_.executionId)
      .getOrElse(fail("no tracked execution with a Varka node found"))
    val metricId = statusStore.execution(executionId).get.metrics
      .find(_.name.contains("residual")).map(_.accumulatorId)
      .getOrElse(fail("the residual metric is not registered on the execution"))
    val posted = statusStore.executionMetrics(executionId)
    assert(posted.get(metricId).exists(_.contains("1")),
      s"expected the posted residual count in the store, got: $posted")
  }

  test("caching a view over fused Varka work keeps the work") {
    // The cache builder strips a topmost columnar-to-row transition to reach the columnar plan
    // underneath - sound for the stock transition, silently wrong for the fused Varka nodes,
    // which carry a projection or filter inside it. The Arrow serializer converts them to
    // their columnar siblings instead; this pins it, because the failure mode is vicious:
    // every direct query is right and only a CACHED view materializes the dropped work.
    cacheDatesBig(spark, 256)
    cacheDatesBig(varkaSpark, 256)
    for (session <- Seq(spark, varkaSpark)) {
      session.sql("SELECT date_add(d, 5) AS a FROM varka_dates_big WHERE d IS NOT NULL")
        .createOrReplaceTempView("varka_cached_fused")
      session.catalog.cacheTable("varka_cached_fused")
    }
    // The query over the cache has no Varka work of its own; what it checks is the cache's
    // content - built through the converted VarkaProjectExec-over-VarkaFilterExec plan on
    // the varka session, and through the row engine on the baseline.
    checkDifferential(spark, varkaSpark,
      "SELECT a FROM varka_cached_fused ORDER BY a", expectFused = false)
    for (session <- Seq(spark, varkaSpark)) {
      session.catalog.uncacheTable("varka_cached_fused")
    }
  }

  test("a nondeterministic conjunct keeps the whole filter unfused") {
    // The conjunct split would hoist the date predicate below rand(), changing which rows
    // the seeded stream sees; the compiler declines the whole predicate instead. Plan-shape
    // assertion only: an always-true rand comparison gets optimized away entirely (leaving a
    // deterministic filter that legitimately fuses), and a live rand makes answers
    // uncomparable; the reorder semantics themselves
    // are pinned in the compiler suite.
    cacheDatesBig(varkaSpark, 256)
    val plan = varkaSpark.sql(
      "SELECT i FROM varka_dates_big WHERE rand(42) < 0.5 AND d IS NOT NULL ORDER BY i")
      .queryExecution.executedPlan
    assertNotFused(plan)
  }

  test("filters over multiple batches and tasks share one mask kernel class") {
    // The Arrow cache's own knob (VARKA-301): it ignores COLUMN_BATCH_SIZE, which an earlier
    // version set here and whose assertion held only through the second partition. Restored to
    // what it was, so the batch-size axis's setting survives the test.
    val key = SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.key
    val before = Seq(spark, varkaSpark).map(_.conf.getOption(key))
    try {
      spark.conf.set(key, "32")
      varkaSpark.conf.set(key, "32")
      cacheDatesBig(spark, 1024, parts = 4)
      cacheDatesBig(varkaSpark, 1024, parts = 4)
      val plan = checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c FROM varka_dates_big WHERE d < DATE'2020-06-01'",
        expectFused = true)
      val batches = plan.collectFirst { case v if isVarkaNode(v) => v }
        .flatMap(_.metrics.get("numVarkaBatches")).map(_.value).getOrElse(0L)
      assert(batches > 1L, s"expected more than one kernel batch, got $batches")
    } finally {
      Seq(spark, varkaSpark).zip(before).foreach {
        case (session, Some(value)) => session.conf.set(key, value)
        case (session, None) => session.conf.unset(key)
      }
    }
  }

  test("multi-batch: every cached Arrow batch is processed by the kernels") {
    // The Arrow cache's own knob (VARKA-301): it ignores COLUMN_BATCH_SIZE, which an earlier
    // version set here and whose assertion held only through the second partition. Restored to
    // what it was, so the batch-size axis's setting survives the test.
    val key = SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.key
    val before = Seq(spark, varkaSpark).map(_.conf.getOption(key))
    try {
      spark.conf.set(key, "32")
      varkaSpark.conf.set(key, "32")
      cacheDatesBig(spark, 1024)
      cacheDatesBig(varkaSpark, 1024)
      val plan = checkDifferential(spark, varkaSpark,
        "SELECT date_add(d, 1) AS a FROM varka_dates_big ORDER BY a",
        expectFused = true)
      val batches = plan.collectFirst { case v: VarkaColumnarToRowExec => v }
        .flatMap(_.metrics.get("numVarkaBatches")).map(_.value).getOrElse(0L)
      assert(batches > 1L, s"expected more than one kernel batch, got $batches")
    } finally {
      Seq(spark, varkaSpark).zip(before).foreach {
        case (session, Some(value)) => session.conf.set(key, value)
        case (session, None) => session.conf.unset(key)
      }
    }
  }

  test("the cached batches hold the rows the batch-size axis names (VARKA-301)") {
    // The axis cannot pass vacuously: under arrowBatchSize=<rows> every cached batch holds at most
    // that many rows and a table of 100 is split, and under the defaults no batch exceeds the
    // default. Read off the cache itself, which is what the kernels are handed.
    cacheDatesBig(varkaSpark, 100)
    val relation = varkaSpark.table("varka_dates_big").queryExecution.withCachedData
      .collectFirst { case r: columnar.InMemoryRelation => r }
      .getOrElse(fail("expected varka_dates_big to be cached"))
    val rows = relation.cacheBuilder.cachedColumnBuffers.map(_.numRows).collect().toSeq
    assert(rows.sum === 100)
    val limit = VarkaMatrix.arrowBatchSize
      .getOrElse(SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.defaultValue.get)
    assert(rows.max <= limit, s"a cached batch of ${rows.max} rows past the limit $limit")
    VarkaMatrix.arrowBatchSize.foreach { size =>
      assert(rows.size >= 100 / size, s"$size rows a batch gave ${rows.size} batches: $rows")
    }
  }

  test("multi-task: tasks sharing one cached kernel class produce correct results") {
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

  // varka-session-lint: allow the path is a hundred tasks of one shape through the cache
  test("many distinct-literal Varka tasks are one cached shape, Metaspace bounded") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    val before = metaspaceUsed()
    val missesBefore = VarkaShapeCache.missCount
    val hitsBefore = VarkaShapeCache.hitCount
    (0 until 100).foreach { i =>
      checkDifferential(spark, varkaSpark,
        s"SELECT date_add(d, $i) AS a FROM varka_dates ORDER BY a", expectFused = true)
    }
    // VARKA-18 inverted what this test proves. The hundred queries differ only in their
    // literal, which never enters the shape key - they are one shape, so at most one task
    // emitted a class (zero if an earlier test already cached the shape) and the rest hit the
    // JVM-wide cache. The deterministic eviction guarantee lives in VarkaShapeCacheSuite.
    assert(VarkaShapeCache.missCount - missesBefore <= 1,
      s"expected at most one emission for one shape, got ${VarkaShapeCache.missCount} misses")
    assert(VarkaShapeCache.hitCount - hitsBefore >= 100,
      "the repeated shape must be served from the cache")
    System.gc()
    System.runFinalization()
    System.gc()
    val delta = metaspaceUsed() - before
    // Lenient: one cached kernel class (a few KB) must stay far below this bound.
    assert(delta < 64L * 1024 * 1024, s"Metaspace grew by $delta bytes across 100 Varka tasks")
  }

  test("a trunc projection over a Varka filter's compacted batch answers through the " +
      "row path, the string-column limitation VARKA-59 recorded") {
    cacheDatesTruncFormats(spark)
    cacheDatesTruncFormats(varkaSpark)
    withAnsi(false) {
      val q = "SELECT trunc(d, fmt) AS a FROM varka_dates_trunc_formats WHERE d IS NOT NULL " +
        "ORDER BY a"
      val expected = spark.sql(q)
      val actual = varkaSpark.sql(q)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, expected)
      assert(varkaMetric(plan, "numFallbackBatchesNonArrow") > 0L,
        "the compacted string column should have refused the projection's batch")
      assert(varkaMetric(plan, "numFallbackBatchesRowPath") === 0L)
    }
  }
}
