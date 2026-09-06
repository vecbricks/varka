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
import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaEmitOptions, VarkaShapeCache}
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
      // Task 18: execute the query a second time, so the kernel class is served from the warm
      // cross-task cache - a wrong or stale hit would surface as a wrong answer right here,
      // which the ghost fallback could never catch.
      checkAnswer(actualSession.sql(query), expected)
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

  test("task 38: date_add/date_sub with a column offset match the row engine") {
    // `varka_date_pairs`'s `i` column is not nullable (it comes from zipWithIndex) - the
    // literal-offset shapes already covered that side; this exercises the new column-offset
    // path over both spellings.
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, i) AS a, d + i AS b, date_sub(d, i) AS c FROM varka_date_pairs " +
        "ORDER BY a, b, c",
      expectFused = true)
  }

  test("task 38: a null offset nulls out its row even when the date beside it is not null") {
    cacheDatesNullableOffset(spark)
    cacheDatesNullableOffset(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, off) AS a, date_sub(d, off) AS b FROM varka_dates_nullable_offset " +
        "ORDER BY d, off",
      expectFused = true)
  }

  test("task 38, then 56: an int column cast to a day interval fuses as the column offset it is") {
    // `d + INTERVAL n DAY` with a foldable `n` already fused before task 38 (the analyzer folds
    // it to a DateAdd literal). Task 38 left the non-foldable interval column declined -
    // BinaryArithmeticWithDatetimeResolver rewrites it to
    // DateAdd(d, ExtractANSIIntervalDays(intervalCol)), which had no arm - and this test
    // pinned that. Task 56 gave the int-cast form its arm: CAST(i AS INTERVAL DAY) is
    // DayTimeIntervalType(DAY, DAY), the extractor undoes the cast exactly, and the entry is
    // task 38's own column-offset node under a per-batch bound. The stored interval column,
    // which is what task 38's comment was really about, stays declined in the task 56 tests.
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT d + CAST(i AS INTERVAL DAY) AS a FROM varka_dates ORDER BY a",
      expectFused = true)
  }

  test("task 38: a column-offset date_add fuses inside a filter predicate too") {
    // The projection-side column-offset tests above never exercise the mask kernel - a
    // WHERE clause is the shape VarkaFilterExec/VarkaFilterColumnarToRowExec compile, and
    // compileOffset is shared code, so this proves the column-offset path works there too,
    // not only when the offset column feeds a projected value.
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    try {
      val fused = checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c FROM varka_date_pairs WHERE date_add(d, i) > d2",
        expectFused = true)
      assert(!fused.toString.contains("Filter (date_add("),
        s"the column-offset predicate should be fused, not residual:\n$fused")
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_date_pairs"))
    }
  }

  test("task 52: a literal day shift past the calendar range is residual, with its reason, " +
      "and an in-range one fuses") {
    // The compile-time half of the range guard: `year(date_add(d, 20000000))` is the query
    // task 51's removed differential used, and it fused - wrongly - between task 51 and this
    // task. Now the entry declines at compile time, the row engine computes it (LocalDate
    // handles any int day, so the answer is a real year near 56770), and verbose EXPLAIN says
    // why. `near` keeps the node fused so there is a Varka node to explain at all.
    cacheDates(spark)
    cacheDates(varkaSpark)
    val plan = checkDifferential(spark, varkaSpark,
      "SELECT year(date_add(d, 20000000)) AS far, year(d) AS near FROM varka_dates " +
        "ORDER BY near, far",
      expectFused = true)
    val explained = plan.collect { case p if isVarkaNode(p) => p.verboseStringWithOperatorId() }
      .mkString("\n")
    assert(explained.contains("far: residual (day range ["), explained)
    assert(explained.contains("near: fused"), explained)
    // The largest shift the analysis admits still fuses and still matches the row engine.
    val shiftHi = VarkaChrono.NARROW_MAX_DAYS - VarkaChrono.CONTRACT_MAX_DAYS
    checkDifferential(spark, varkaSpark,
      s"SELECT year(date_add(d, $shiftHi)) AS y, month(date_sub(d, 30)) AS m FROM varka_dates " +
        "ORDER BY y, m",
      expectFused = true)
  }

  /** The Varka node's metric, zero if the node or the metric is absent. */
  private def varkaMetric(plan: SparkPlan, name: String): Long =
    plan.collectFirst { case v if isVarkaNode(v) => v }
      .flatMap(_.metrics.get(name)).map(_.value).getOrElse(0L)

  test("task 52: a column-offset date_add under a calendar node declines the batch that " +
      "leaves the range, and the row engine answers it") {
    // The runtime half of the range guard, restoring what task 51 removed: the far rows of the
    // fixture put date_add(d, off) twenty million days past the range, the producer's guard
    // reports the batch, and the evaluator recomputes it on the row engine - the answers
    // match, and the batch lands under the declined metric, not the kernel-failure one.
    cacheDatesFarOffset(spark)
    cacheDatesFarOffset(varkaSpark)
    val q = "SELECT year(date_add(d, off)) AS y, month(date_sub(d, off)) AS m, " +
      "dayofmonth(d + off) AS dm FROM varka_dates_far_offset ORDER BY y, m, dm"
    val expected = spark.sql(q)
    val actual = varkaSpark.sql(q)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, expected)
    assert(varkaMetric(plan, "numFallbackBatchesDeclined") > 0L,
      s"the producer guard should have declined the far batch:\n${plan.treeString}")
    assert(varkaMetric(plan, "numFallbackBatchesKernel") === 0L)
    // An in-range column offset over the same dates fuses, runs on the kernel and never
    // declines - the guard is silent on the data it exists for.
    val near = checkDifferential(spark, varkaSpark,
      "SELECT year(date_add(d, small)) AS y, month(date_sub(d, small)) AS m " +
        "FROM varka_dates_far_offset ORDER BY y, m",
      expectFused = true)
    assert(varkaMetric(near, "numFallbackBatchesDeclined") === 0L)
  }

  test("task 52: with the guard off, the far batch runs on the kernel - asserted on the " +
      "metric, never on the value") {
    // The reference variant (guardDayProducers = false) is task 51's bytes: the far batch is
    // computed, and computed wrongly past the range. PLAN_TASK_51.md section 3 is why no test
    // encodes that answer as green - this asserts only that the batch was not declined, which
    // is what the A/B in VarkaEmitterParityBenchmark measures the cost of.
    cacheDatesFarOffset(spark)
    cacheDatesFarOffset(varkaSpark)
    VarkaColumnarToRowExec.setEmitOptionsForTesting(
      VarkaEmitOptions.DEFAULTS.withGuardDayProducers(false))
    try {
      val q = "SELECT year(date_add(d, off)) AS y FROM varka_dates_far_offset ORDER BY y"
      val df = varkaSpark.sql(q)
      df.collect()
      val plan = df.queryExecution.executedPlan
      assertFused(plan)
      assert(varkaMetric(plan, "numFallbackBatchesDeclined") === 0L)
      assert(varkaMetric(plan, "numVarkaBatches") > 0L)
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS)
    }
  }

  test("task 46: the width-specialised validity helpers answer what the general pair did") {
    // Every other test in this suite runs the specialised path, because task 46's switch
    // defaults on - so what is left to check is the other arm, and that the two agree end to
    // end rather than only in the emitter suite's hand-built batches. Both shapes that still
    // write validity per lane group are here: a masked projection (nulls on either side) and a
    // filter, whose selection bitmap is OR-ed per group in both bodies.
    cacheDatesNullableOffset(spark)
    cacheDatesNullableOffset(varkaSpark)
    val queries = Seq(
      "SELECT year(date_add(d, off)) AS y, dayofweek(d) AS w FROM varka_dates_nullable_offset " +
        "ORDER BY y, w",
      "SELECT d, off FROM varka_dates_nullable_offset WHERE date_add(d, off) > DATE'2000-01-01' " +
        "ORDER BY d, off")
    for (query <- queries) {
      checkDifferential(spark, varkaSpark, query, expectFused = true)
    }
    VarkaColumnarToRowExec.setEmitOptionsForTesting(
      VarkaEmitOptions.DEFAULTS.withValidityByWidth(false))
    try {
      for (query <- queries) {
        checkDifferential(spark, varkaSpark, query, expectFused = true)
      }
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS)
    }
  }

  test("task 52: the producer guard reaches a filter predicate through the same route") {
    // The mask kernel shares the emitter and the evaluator's status route with the
    // projection; a calendar node over a column-offset producer in a WHERE clause declines the
    // far batch to the row filter, and the count agrees with the row engine.
    cacheDatesFarOffset(spark)
    cacheDatesFarOffset(varkaSpark)
    try {
      val q = "SELECT count(*) AS c FROM varka_dates_far_offset " +
        "WHERE year(date_add(d, off)) = year(d2)"
      val expected = spark.sql(q)
      val actual = varkaSpark.sql(q)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      assert(!plan.toString.contains("Filter (year("),
        s"the predicate should be fused, not residual:\n$plan")
      checkAnswer(actual, expected)
      val filterNode = plan.collectFirst { case f: VarkaFilterExec => f }
        .orElse(plan.collectFirst { case f: VarkaFilterColumnarToRowExec => f })
      assert(filterNode.isDefined, s"expected a Varka filter node:\n${plan.treeString}")
      assert(filterNode.get.metrics("numFallbackBatchesDeclined").value > 0L)
      assert(filterNode.get.metrics("numFallbackBatchesKernel").value === 0L)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_dates_far_offset"))
    }
  }

  test("task 56: date +- CAST(i AS INTERVAL DAY) matches the row engine on the projection " +
      "and filter paths, and a stored interval column stays residual") {
    cacheDatesNullableOffset(spark)
    cacheDatesNullableOffset(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT d + CAST(off AS INTERVAL DAY) AS a, d - CAST(off AS INTERVAL DAY) AS b " +
        "FROM varka_dates_nullable_offset ORDER BY a, b",
      expectFused = true)
    val filtered = checkDifferential(spark, varkaSpark,
      "SELECT count(*) AS c FROM varka_dates_nullable_offset " +
        "WHERE d + CAST(off AS INTERVAL DAY) > DATE'2024-01-01'",
      expectFused = true)
    assert(!filtered.toString.contains("Filter (d"), filtered.toString)
    // A stored INTERVAL DAY column is int64 microseconds, out of the date lane by decision.
    // Cached as its own table so the column really is stored: a subquery's cast collapses back
    // onto the int under the optimizer and fuses, which is the case above.
    Seq(spark, varkaSpark).foreach { session =>
      session.sql("SELECT d, CAST(off AS INTERVAL DAY) AS iv FROM varka_dates_nullable_offset")
        .createOrReplaceTempView("varka_interval_column")
      session.catalog.cacheTable("varka_interval_column")
    }
    try {
      checkDifferential(spark, varkaSpark,
        "SELECT d + iv AS a FROM varka_interval_column ORDER BY a", expectFused = false)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_interval_column"))
    }
  }

  test("task 56: an offset past the cast's limit raises the row engine's error through Varka") {
    // The bound the evaluator checks per batch: Spark's CAST(i AS INTERVAL DAY) throws past
    // INTERVAL_DAY_LIMIT_DAYS in every mode. Through Varka the batch declines to the row
    // engine, so the error is the row engine's own - compared by running both sessions, as
    // the plan's error-identity rule asks.
    val rows = Seq(
      (date("2024-01-01"), Int.box(3)),
      (date("2024-01-02"), Int.box(VarkaChrono.INTERVAL_DAY_LIMIT_DAYS + 1)),
      (null: java.sql.Date, Int.box(5)))
    Seq(spark, varkaSpark).foreach { session =>
      session.createDataFrame(rows).toDF("d", "off").createOrReplaceTempView("varka_far_interval")
      session.catalog.cacheTable("varka_far_interval")
    }
    try {
      val q = "SELECT d + CAST(off AS INTERVAL DAY) AS a FROM varka_far_interval ORDER BY a"
      val expected = intercept[SparkArithmeticException](spark.sql(q).collect())
      val actual = intercept[SparkArithmeticException](varkaSpark.sql(q).collect())
      assert(actual.getCondition === expected.getCondition)
      assert(actual.getMessage === expected.getMessage)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_far_interval"))
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

  test("task 20: IN over date literals fuses to the cap and declines above it") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    // Base 2023-12-27 with step 3 intersects the table (2023-12-27 itself and 2024-01-02),
    // so the fused EQ path is exercised on true lanes, not only on the all-miss ELSE side -
    // the review caught the original base (2023-12-25) never matching any row.
    def literals(n: Int): String = (0 until n).map { k =>
      s"DATE'${java.time.LocalDate.of(2023, 12, 27).plusDays(k * 3L)}'"
    }.mkString(", ")
    // 5 literals arrive as In, 16 as InSet (the optimizer's inSetConversionThreshold is 10);
    // both lists hit and miss real rows, and the null row's unknown condition falls to ELSE.
    checkDifferential(spark, varkaSpark,
      s"SELECT CASE WHEN d IN (${literals(5)}) THEN date_add(d, 1) ELSE d END AS a " +
        "FROM varka_dates ORDER BY a",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      s"SELECT CASE WHEN d IN (${literals(16)}) THEN date_add(d, 1) ELSE d END AS a " +
        "FROM varka_dates ORDER BY a",
      expectFused = true)
    // Duplicated literals collapse before the cap is counted, so a doubled list still fuses.
    checkDifferential(spark, varkaSpark,
      s"SELECT CASE WHEN d IN (${literals(5)}, ${literals(5)}) THEN d " +
        "ELSE date_add(d, 2) END AS a FROM varka_dates ORDER BY a",
      expectFused = true)
    // Above the cap the entry declines with a recorded reason and stays on the row engine.
    for (n <- Seq(17, 50)) {
      checkDifferential(spark, varkaSpark,
        s"SELECT CASE WHEN d IN (${literals(n)}) THEN date_add(d, 1) ELSE d END AS a " +
          "FROM varka_dates ORDER BY a",
        expectFused = false)
    }
    // A non-literal element makes the whole list ineligible - correct on the row engine.
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      s"SELECT CASE WHEN d IN (d2, ${literals(3)}) THEN d ELSE date_add(d, 4) END AS a " +
        "FROM varka_date_pairs ORDER BY a",
      expectFused = false)
  }

  test("task 20: coalesce, nvl, ifnull and nvl2 fuse and match the row engine") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    for (q <- Seq(
      "SELECT coalesce(d, d2) AS a FROM varka_date_pairs ORDER BY a",
      "SELECT coalesce(d, d2, DATE'1999-09-09') AS a FROM varka_date_pairs ORDER BY a",
      "SELECT nvl(d, date_add(d2, 1)) AS a FROM varka_date_pairs ORDER BY a",
      "SELECT ifnull(d, d2) AS a FROM varka_date_pairs ORDER BY a",
      "SELECT nvl2(d, date_add(d2, 3), date_sub(d2, 3)) AS a " +
        "FROM varka_date_pairs ORDER BY a")) {
      checkDifferential(spark, varkaSpark, q, expectFused = true)
    }
    // A computed operand before the last cannot be guarded (the validity condition reads a
    // column's word) and declines - correct on the row engine.
    checkDifferential(spark, varkaSpark,
      "SELECT coalesce(date_add(d, 1), d2) AS a FROM varka_date_pairs ORDER BY a",
      expectFused = false)
  }

  test("task 20: coalesce over all-null and null-free inputs") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    for (session <- Seq(spark, varkaSpark)) {
      session.sql("SELECT d, d2 FROM varka_date_pairs WHERE d IS NOT NULL AND d2 IS NOT NULL")
        .createOrReplaceTempView("varka_null_free")
      session.catalog.cacheTable("varka_null_free")
      session.sql(
        "SELECT CAST(NULL AS DATE) AS d, CAST(NULL AS DATE) AS d2 FROM varka_date_pairs")
        .createOrReplaceTempView("varka_all_null")
      session.catalog.cacheTable("varka_all_null")
    }
    // All-null exercises the skipping contract: the all-null shortcut must not short-circuit
    // an IfElse over the validity condition, and coalesce(all-null, all-null) is all-null.
    checkDifferential(spark, varkaSpark,
      "SELECT coalesce(d, d2) AS a FROM varka_null_free ORDER BY a", expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT coalesce(d, d2) AS a FROM varka_all_null ORDER BY a", expectFused = true)
  }

  test("task 20: IS NULL and IS NOT NULL fuse as conditions, connectives included") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT CASE WHEN d IS NULL THEN d2 ELSE d END AS a FROM varka_date_pairs ORDER BY a",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT CASE WHEN d IS NOT NULL AND d < d2 THEN date_add(d, 1) ELSE d2 END AS a " +
        "FROM varka_date_pairs ORDER BY a",
      expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT IF(d IS NULL OR d2 IS NULL, DATE'1970-01-01', greatest(d, d2)) AS a " +
        "FROM varka_date_pairs ORDER BY a",
      expectFused = true)
  }

  test("task 20: BETWEEN over a computed input fuses through the common-expression hoist") {
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    // A non-cheap BETWEEN input hoists into `_common_expr_0` in its own Project; the hoisted
    // arithmetic and the IF over its ref fuse as stacked Varka nodes.
    checkDifferential(spark, varkaSpark,
      "SELECT IF(date_add(d, 7) BETWEEN d2 AND date_add(d2, 40), d, date_sub(d2, 1)) AS a " +
        "FROM varka_date_pairs ORDER BY a",
      expectFused = true)
  }

  test("task 20: cast-wrapped date expressions fuse, folded or unwrapped before the kernel") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    // The optimizer folds the literal cast and drops the identity cast (SimplifyCasts); the
    // compiler's own unwrap covers hand-built trees. Either layer, the query fuses.
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(CAST(d AS DATE), 2) AS a, " +
        "IF(d < CAST('2024-01-02' AS DATE), d, date_sub(d, 1)) AS b " +
        "FROM varka_dates ORDER BY a",
      expectFused = true)
  }

  test("task 41: unix_date and date_from_unix_date fuse as a pure relabel") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT unix_date(d) AS u FROM varka_dates ORDER BY u",
      expectFused = true)
    // date_from_unix_date's child is an integer column, which no leaf can read until task 38
    // opens it - it declines through the ordinary non-date-column path and the projection has
    // nothing left to fuse, exactly like any other read of a bare int column today.
    checkDifferential(spark, varkaSpark,
      "SELECT date_from_unix_date(i) AS x FROM varka_dates",
      expectFused = false)
    // The actual argument for the task: a relabelled entry beside an ordinary one must not
    // demote the whole projection to Janino. Before this task the relabel became a residual
    // (per-row) entry rather than blocking `a` too - task 12's per-entry eligibility already
    // covered that - but it still cost a Janino re-evaluation of every row for `b` instead of
    // riding the same vectorized loop as `a`; see VarkaExpressionCompilerSuite for the
    // compiler-level proof that both entries now fuse rather than one falling to residual.
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 1) AS a, unix_date(d) AS b FROM varka_dates ORDER BY a",
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

  test("task 58: extract(YEAROFWEEK) matches the row engine on the rows the ISO year moves " +
      "on, beside weekofyear and year, under both spellings and on the filter path") {
    // December 28 to January 4 of years whose week 1 starts in the old year (2004/2005,
    // 2020/2021, 2026/2027) and of years where it does not (2018/2019, 2022/2023), the century
    // years and the range ends, with a null: the three fields disagree exactly here.
    val turns = Seq(2004, 2018, 2020, 2022, 2026).flatMap { y =>
      (28 to 31).map(dd => s"$y-12-$dd") ++ (1 to 4).map(dd => f"${y + 1}-01-$dd%02d")
    } ++ Seq("1900-01-01", "1900-12-31", "2000-01-01", "2000-12-31", "0001-01-01",
      "9999-12-31", null)
    Seq(spark, varkaSpark).foreach { session =>
      import scala.jdk.CollectionConverters._
      val schema = org.apache.spark.sql.types.StructType(Seq(
        org.apache.spark.sql.types.StructField("d", org.apache.spark.sql.types.DateType, true)))
      val rows = turns.map(v =>
        org.apache.spark.sql.Row(if (v == null) null else java.sql.Date.valueOf(v)))
      session.createDataFrame(rows.asJava, schema).createOrReplaceTempView("varka_iso_turns")
      session.catalog.cacheTable("varka_iso_turns")
    }
    try {
      checkDifferential(spark, varkaSpark,
        "SELECT extract(YEAROFWEEK FROM d) AS y, date_part('YEAROFWEEK', d) AS y2, " +
          "weekofyear(d) AS w, year(d) AS a FROM varka_iso_turns ORDER BY d",
        expectFused = true)
      // The boundary rows are exactly the ones where the ISO year is not the calendar year.
      checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c FROM varka_iso_turns WHERE extract(YEAROFWEEK FROM d) <> year(d)",
        expectFused = true)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_iso_turns"))
    }
  }

  test("task 42: make_date matches the row engine in both modes - nulls for invalid dates " +
      "with ANSI off, the row engine's error with ANSI on, the date feeding further work") {
    cacheDateParts(spark)
    cacheDateParts(varkaSpark)
    withAnsi(false) {
      checkDifferential(spark, varkaSpark,
        "SELECT make_date(y, m, dd) AS a, year(make_date(y, m, dd)) AS b, " +
          "date_add(make_date(y, m, dd), 7) AS c, make_date(y, 2, 29) AS e FROM varka_date_parts " +
          "ORDER BY a, b, c, e",
        expectFused = true)
      // The date the valid rows spell comes back as itself; the filter route counts them.
      checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c FROM varka_date_parts WHERE make_date(y, m, dd) = d",
        expectFused = true)
    }
    withAnsi(true) {
      // The valid rows alone match; the invalid rows raise the same error through both.
      checkDifferential(spark, varkaSpark,
        "SELECT make_date(y, m, dd) AS a FROM varka_date_parts WHERE d IS NOT NULL ORDER BY a",
        expectFused = true)
      val q = "SELECT make_date(y, m, dd) AS a FROM varka_date_parts ORDER BY a"
      val expected = intercept[SparkDateTimeException](spark.sql(q).collect())
      val actual = intercept[SparkDateTimeException](varkaSpark.sql(q).collect())
      assert(actual.getCondition === expected.getCondition)
      assert(actual.getMessage === expected.getMessage)
    }
  }

  test("task 37: weekofyear matches the row engine on every day across forty year " +
      "boundaries, at Velox's fixtures, under every spelling and on the filter path") {
    // The dense sweep of the plan: every day from 1990-12-20 to 2030-01-10 built from range,
    // so the row engine and the kernel see the same 14,631 rows, beside dayofyear and year
    // over the same column - the three disagree on exactly the rows that matter - and the
    // Velox Spark-compatibility fixtures as literal rows with a null.
    Seq(spark, varkaSpark).foreach { session =>
      session.sql("SELECT date_add(DATE'1990-12-20', CAST(id AS INT)) AS d FROM range(0, 14631)")
        .createOrReplaceTempView("varka_iso_days")
      session.catalog.cacheTable("varka_iso_days")
      val fixtures = Seq("1919-12-31", "1969-12-31", "1960-01-01", "0001-01-01", "9999-12-31",
        "2020-12-31", "2004-12-31", "2016-12-31", "2016-01-01", "2019-12-30", null)
      import scala.jdk.CollectionConverters._
      val schema = org.apache.spark.sql.types.StructType(Seq(
        org.apache.spark.sql.types.StructField("d", org.apache.spark.sql.types.DateType, true)))
      val rows = fixtures.map(v =>
        org.apache.spark.sql.Row(if (v == null) null else java.sql.Date.valueOf(v)))
      session.createDataFrame(rows.asJava, schema).createOrReplaceTempView("varka_iso_fixtures")
      session.catalog.cacheTable("varka_iso_fixtures")
    }
    try {
      checkDifferential(spark, varkaSpark,
        "SELECT weekofyear(d) AS w, dayofyear(d) AS y, year(d) AS a FROM varka_iso_days " +
          "ORDER BY d",
        expectFused = true)
      checkDifferential(spark, varkaSpark,
        "SELECT weekofyear(d) AS w, EXTRACT(WEEKS FROM d) AS w2, EXTRACT(WEEK FROM d) AS w3, " +
          "date_part('W', d) AS w4 FROM varka_iso_fixtures ORDER BY w, w2, w3, w4",
        expectFused = true)
      // The predicate route: week 53 exists in 2004, 2009, 2015, 2020 and 2026 of the span.
      checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c, min(d) AS lo, max(d) AS hi FROM varka_iso_days " +
          "WHERE weekofyear(d) = 53",
        expectFused = true)
    } finally {
      Seq(spark, varkaSpark).foreach { session =>
        session.catalog.uncacheTable("varka_iso_days")
        session.catalog.uncacheTable("varka_iso_fixtures")
      }
    }
  }

  test("task 57: extract(DAYOFWEEK_ISO), date_part('DOW_ISO') and weekday(d) + 1 match the " +
      "row engine as one node, with nulls") {
    // The three spellings the analyzer turns into Add(WeekDay(d), 1), over the shared dates
    // table (a Monday, a Tuesday, a Wednesday, a Sunday - 1969-12-31 - and a null), beside
    // dayofweek and weekday so the three offsets are checked against each other.
    cacheDates(spark)
    cacheDates(varkaSpark)
    checkDifferential(spark, varkaSpark,
      "SELECT extract(DAYOFWEEK_ISO FROM d) AS a, date_part('DOW_ISO', d) AS b, " +
        "weekday(d) + 1 AS c, dayofweek(d) AS e, weekday(d) AS f FROM varka_dates " +
        "ORDER BY a, b, c, e, f",
      expectFused = true)
    // weekday(d) + 2 is not the node and stays residual: the arm is the constant one only.
    checkDifferential(spark, varkaSpark,
      "SELECT weekday(d) + 2 AS a FROM varka_dates ORDER BY a", expectFused = false)
  }

  test("the calendar extractions match the row engine across the Gregorian range") {
    // Every shape the decomposition could get wrong end to end: leap days of a 400-divisible
    // year (2000) and a 100-divisible one (1900), the century boundary itself, the era
    // boundary at 1600, month-length edges, the first and last dates SQL can write, and a
    // null. The March-based year the lowering works in turns at 1 March, so both sides of
    // that are here too.
    val rows = Seq("2024-01-01", "2024-02-29", "2024-03-01", "2024-12-31", "1969-12-31",
      "1970-01-01", "1900-02-28", "1900-03-01", "2000-02-29", "2000-03-01", "1600-02-29",
      "1600-03-01", "0001-01-01", "9999-12-31", "2025-07-04", null)
    Seq(spark, varkaSpark).foreach { session =>
      import scala.jdk.CollectionConverters._
      val schema = org.apache.spark.sql.types.StructType(Seq(
        org.apache.spark.sql.types.StructField("d", org.apache.spark.sql.types.DateType, true)))
      val data = rows.map(v =>
        org.apache.spark.sql.Row(if (v == null) null else java.sql.Date.valueOf(v)))
      session.createDataFrame(data.asJava, schema).createOrReplaceTempView("varka_cal")
      session.catalog.cacheTable("varka_cal")
    }
    try {
      checkDifferential(spark, varkaSpark,
        "SELECT year(d) AS a, month(d) AS b, dayofmonth(d) AS c, quarter(d) AS e, " +
          "dayofyear(d) AS g, year(date_add(d, 1)) AS f FROM varka_cal " +
          "ORDER BY a, b, c, e, g, f",
        expectFused = true)
      // EXTRACT desugars to the same nodes, so it must fuse the same way.
      checkDifferential(spark, varkaSpark,
        "SELECT EXTRACT(YEAR FROM d) AS a, EXTRACT(QUARTER FROM d) AS b " +
          "FROM varka_cal ORDER BY a, b",
        expectFused = true)
      // The TPC-H q7/q8/q9 shape: year(date) beside a filter on the same column.
      checkDifferential(spark, varkaSpark,
        "SELECT year(d) AS a FROM varka_cal WHERE d >= DATE '1900-01-01' ORDER BY a",
        expectFused = true)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_cal"))
    }
  }

  test("task 35: trunc matches the row engine at every date level, and its date output feeds " +
      "further arithmetic in the same chain") {
    // The calendar family's boundary rows plus the ones the four-way quarter select and the
    // WEEK rewrite care about: a Sunday and a Monday, the first days of each quarter, a date
    // in the first week of January whose Monday belongs to the previous year, and February
    // in a leap, common and century year. All four levels in one query, and the MONTH form
    // under a date_add so the DateType output is proved to survive a second operation, as the
    // milestone row asks.
    val rows = Seq("2024-01-01", "2024-01-07", "2024-01-08", "2024-02-29", "2024-03-01",
      "2024-04-01", "2024-06-30", "2024-07-01", "2024-09-30", "2024-10-01", "2024-12-31",
      "2023-01-01", "2023-01-02", "2023-12-31", "2025-01-05", "2025-01-06", "1969-12-31",
      "1970-01-01", "1900-02-28", "1900-03-01", "2000-02-29", "0001-01-01", "9999-12-31",
      null)
    Seq(spark, varkaSpark).foreach { session =>
      import scala.jdk.CollectionConverters._
      val schema = org.apache.spark.sql.types.StructType(Seq(
        org.apache.spark.sql.types.StructField("d", org.apache.spark.sql.types.DateType, true)))
      val data = rows.map(v =>
        org.apache.spark.sql.Row(if (v == null) null else java.sql.Date.valueOf(v)))
      session.createDataFrame(data.asJava, schema).createOrReplaceTempView("varka_trunc")
      session.catalog.cacheTable("varka_trunc")
    }
    try {
      checkDifferential(spark, varkaSpark,
        "SELECT trunc(d, 'YEAR') AS y, trunc(d, 'MONTH') AS m, trunc(d, 'QUARTER') AS q, " +
          "trunc(d, 'WEEK') AS w, date_add(trunc(d, 'MONTH'), 5) AS m5 " +
          "FROM varka_trunc ORDER BY y, m, q, w, m5",
        expectFused = true)
      // Every spelling parseTruncLevel accepts for the three levels, through SQL.
      checkDifferential(spark, varkaSpark,
        "SELECT trunc(d, 'yyyy') AS a, trunc(d, 'yy') AS b, trunc(d, 'mon') AS c, " +
          "trunc(d, 'mm') AS e FROM varka_trunc ORDER BY a, b, c, e",
        expectFused = true)
      // A format the date form does not cover returns a NULL column on the row engine, which
      // Varka cannot produce: the entry is residual and the answers still match.
      checkDifferential(spark, varkaSpark,
        "SELECT trunc(d, 'HOUR') AS h, trunc(d, 'QTR') AS q FROM varka_trunc ORDER BY h, q",
        expectFused = false)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_trunc"))
    }
  }

  test("last_day matches the row engine across the Gregorian range (task 36)") {
    // The same boundary set task 26's own test uses, since last_day shares emitChrono's
    // prefix and can get the same things wrong, plus two far-future century years a DATE
    // literal cannot name (the SQL parser's year field is 4 digits): 14500, not divisible by
    // 400, and 14400, which is. emitLeapFlag's magic constants once overflowed a 32-bit lane's
    // signed product past roughly year 12400 - silently, with no narrow boundary list catching
    // it - so these two rows are the end-to-end companion to the exhaustive unit-level sweep
    // in VarkaLoopEmitterSuite, over the same class of failure through actual SQL. They go
    // through the column, not a literal expression: a literal-only date_add(DATE '...', n)
    // constant-folds away before Varka ever sees it, which is a shape this test does not
    // want to depend on the optimizer leaving alone.
    val rows = Seq("2024-01-01", "2024-02-29", "2024-03-01", "2024-12-31", "1969-12-31",
      "1970-01-01", "1900-02-28", "1900-03-01", "2000-02-29", "2000-03-01", "1600-02-29",
      "1600-03-01", "0001-01-01", "9999-12-31", "2025-07-04", null)
    val farFuture = Seq(
      java.sql.Date.valueOf(java.time.LocalDate.of(14500, 2, 8)),
      java.sql.Date.valueOf(java.time.LocalDate.of(14400, 2, 29)))
    Seq(spark, varkaSpark).foreach { session =>
      import scala.jdk.CollectionConverters._
      val schema = org.apache.spark.sql.types.StructType(Seq(
        org.apache.spark.sql.types.StructField("d", org.apache.spark.sql.types.DateType, true)))
      val data = rows.map(v =>
        org.apache.spark.sql.Row(if (v == null) null else java.sql.Date.valueOf(v))) ++
        farFuture.map(org.apache.spark.sql.Row(_))
      session.createDataFrame(data.asJava, schema).createOrReplaceTempView("varka_last_day")
      session.catalog.cacheTable("varka_last_day")
    }
    try {
      checkDifferential(spark, varkaSpark,
        "SELECT last_day(d) AS a, date_add(last_day(d), 1) AS b " +
          "FROM varka_last_day ORDER BY a, b",
        expectFused = true)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_last_day"))
    }
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

  test("next_day matches the row engine across 1970, pre-1970 and nulls") {
    val rows = Seq("2024-01-01", "1969-12-31", "1970-01-01", "1900-02-28", "2100-07-04", null)
    Seq(spark, varkaSpark).foreach { session =>
      import scala.jdk.CollectionConverters._
      val schema = org.apache.spark.sql.types.StructType(Seq(
        org.apache.spark.sql.types.StructField("d", org.apache.spark.sql.types.DateType, true)))
      val data = rows.map(v =>
        org.apache.spark.sql.Row(if (v == null) null else java.sql.Date.valueOf(v)))
      session.createDataFrame(data.asJava, schema).createOrReplaceTempView("varka_next_day")
      session.catalog.cacheTable("varka_next_day")
    }
    try {
      // THURSDAY maps to k = -1 (DateTimeUtils.getDayOfWeekFromString's [0, 6] range has
      // THURSDAY = 0), the one weekday whose runtime literal is negative - included so the
      // end-to-end path, not only the emitter unit test, covers it.
      checkDifferential(spark, varkaSpark,
        "SELECT next_day(d, 'MO') AS a, next_day(d, 'SUNDAY') AS b, " +
          "next_day(d, 'THURSDAY') AS c FROM varka_next_day ORDER BY a, b, c",
        expectFused = true)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_next_day"))
    }
  }

  test("task 59: next_day with a weekday column matches the row engine over every spelling, " +
      "the non-names and nulls, through the projection and the filter, with no fallback") {
    cacheDatesWeekday(spark)
    cacheDatesWeekday(varkaSpark)
    withAnsi(false) {
      // Two next_day over one column share one derived input (the compiler suite pins the
      // plan); the literal form rides beside them unchanged. A non-name is a NULL here, as
      // the row engine's non-ANSI catch makes it, and the metrics say nothing fell back: the
      // leaf is the kernel path, not a row-path repair.
      val plan = checkDifferential(spark, varkaSpark,
        "SELECT next_day(d, s) AS a, next_day(d2, s) AS b, next_day(d, 'MO') AS c " +
          "FROM varka_dates_weekday ORDER BY a, b, c",
        expectFused = true)
      assert(varkaMetric(plan, "numFallbackBatchesRowPath") === 0L)
      assert(varkaMetric(plan, "numFallbackBatchesDeclined") === 0L)
      assert(varkaMetric(plan, "numFallbackBatchesNonArrow") === 0L)
      // The filter route reads the derived input through the same fillSources.
      checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c FROM varka_dates_weekday WHERE next_day(d, s) = d2",
        expectFused = true)
      checkDifferential(spark, varkaSpark,
        "SELECT d FROM varka_dates_weekday WHERE next_day(d, s) < d2 ORDER BY d",
        expectFused = true)
    }
  }

  test("task 59: a projection over a Varka filter's compacted batch answers through the row " +
      "path, because the filter compacts a string column generically (recorded limitation)") {
    // The filter's compaction (task 21) rebuilds fixed-width Arrow columns as Arrow and every
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

  test("task 59: under ANSI the valid rows fuse, a non-name beside a live date raises the " +
      "row engine's own error, and one beside a null date is NULL with no error") {
    cacheDatesWeekday(spark)
    cacheDatesWeekday(varkaSpark)
    cacheDatesWeekdayValid(spark)
    cacheDatesWeekdayValid(varkaSpark)
    cacheDatesWeekdayBadOnNulls(spark)
    cacheDatesWeekdayBadOnNulls(varkaSpark)
    withAnsi(true) {
      // Every name valid: fused, nothing declines.
      val valid = checkDifferential(spark, varkaSpark,
        "SELECT next_day(d, s) AS a, next_day(d2, s) AS b FROM varka_dates_weekday_valid " +
          "ORDER BY a, b",
        expectFused = true)
      assert(varkaMetric(valid, "numFallbackBatchesDeclined") === 0L)
      // The whole table: the leaf declines the batch on the first non-name and the row engine
      // raises ILLEGAL_DAY_OF_WEEK for the same row, so the two errors are one error.
      val q = "SELECT next_day(d, s) AS a FROM varka_dates_weekday ORDER BY a"
      val expected = intercept[SparkIllegalArgumentException](spark.sql(q).collect())
      val actual = intercept[SparkIllegalArgumentException](varkaSpark.sql(q).collect())
      assert(actual.getCondition === expected.getCondition)
      assert(actual.getMessage === expected.getMessage)
      // The rule that decided the route (PLAN_TASK_59.md 2): a non-name beside a null date is
      // NULL under ANSI, because the row engine never parses it. The leaf sees the non-name,
      // declines, and the row engine answers - counted as a decline, never as a failure.
      val nq = "SELECT next_day(d, s) AS a FROM varka_dates_weekday_bad_on_nulls ORDER BY a"
      val expectedNulls = spark.sql(nq)
      val actualNulls = varkaSpark.sql(nq)
      val plan = actualNulls.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actualNulls, expectedNulls)
      assert(varkaMetric(plan, "numFallbackBatchesDeclined") > 0L, "the leaf should decline")
      assert(varkaMetric(plan, "numFallbackBatchesRowPath") === 0L)
      assert(varkaMetric(plan, "numFallbackBatchesKernel") === 0L)
    }
  }

  test("task 59: a collated weekday column is admitted and parsed the same way") {
    cacheDatesWeekdayCollated(spark)
    cacheDatesWeekdayCollated(varkaSpark)
    withAnsi(false) {
      checkDifferential(spark, varkaSpark,
        "SELECT next_day(d, s) AS a FROM varka_dates_weekday_lcase ORDER BY a",
        expectFused = true)
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

  test("a calendar node inside a fused predicate is computed like any other") {
    // compileCond's compare() puts no type gate on its operands, so a calendar node reaches a
    // filter's mask kernel as readily as a projection's, which was not exercised until this
    // test.
    //
    // The shape has to be calendar-against-calendar. `year(d) = 2020` does NOT fuse: the
    // literal is an IntegerType one and the compiler's literal arm accepts DateType only, so
    // the whole predicate stays on the row path. Comparing two calendar nodes needs no
    // literal, and that is what reaches the mask kernel.
    cacheDatePairs(spark)
    cacheDatePairs(varkaSpark)
    try {
      val fused = checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c FROM varka_date_pairs WHERE year(d) = year(d2)",
        expectFused = true)
      // The predicate is in the kernel, not left above it as a row-level Filter.
      assert(!fused.toString.contains("Filter (year("),
        s"the calendar predicate should be fused, not residual:\n$fused")
      // Two calendar nodes per side, so the mask kernel carries roughly two hundred ops.
      checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c FROM varka_date_pairs " +
          "WHERE year(d) = year(d2) AND month(d) >= month(d2)",
        expectFused = true)
      // A calendar predicate under a calendar projection over the same columns, which is
      // where the filter's compaction and the projection's kernels meet.
      checkDifferential(spark, varkaSpark,
        "SELECT year(d) AS y FROM varka_date_pairs WHERE month(d) = month(d2) ORDER BY y",
        expectFused = true)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_date_pairs"))
    }
  }

  // Task 51 removed two tests here that drove a real out-of-range day through the
  // then per-extraction guard; task 52 restored them below, anchored on the producer the
  // guard now lives at (the "task 52" tests), so the routing is again reached with real
  // data and no hook.

  test("a declined batch falls back with the row engine's answers, counted as its own cause") {
    // Task 26: a partial lowering (the narrowed civil-from-days one) reports a batch it cannot
    // compute, and the evaluator recomputes it row by row. The task 52 tests reach that path
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
    // Until task 21 the WHERE below pinned expectFused = false - a filter blocked fusion
    // outright. It now fuses: the filter runs the mask kernel and the projection stacks on
    // the compacted batches.
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

  test("task 21: the survey's filter shapes match the row engine, warm cache included") {
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
    // IN - the task-20 lowering, now at a filter root (the benchmark's anchor shape).
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

  test("task 21: null-as-false, and the boundary selectivities") {
    cacheDatesBig(spark, 1024)
    cacheDatesBig(varkaSpark, 1024)
    // The null rows (one in 17) must be dropped by every compilable predicate - SQL's WHERE
    // treats a null predicate as false, and the mask root's rule is exactly that.
    checkDifferential(spark, varkaSpark,
      "SELECT d FROM varka_dates_big WHERE d >= DATE'1900-01-01' ORDER BY d",
      expectFused = true)
    // None selected - through the kernel. The predicate's interval contains no whole day, so
    // every row fails it, but each conjunct is satisfiable against the cache's min/max stats:
    // a range predicate entirely outside the data (d < 1900) would have the in-memory scan's
    // stat pruning drop every batch before the filter node ever saw one, and there would be
    // nothing to assert kernels ran on.
    checkDifferential(spark, varkaSpark,
      "SELECT d FROM varka_dates_big " +
        "WHERE d > DATE'2020-01-05' AND d < DATE'2020-01-06' ORDER BY d",
      expectFused = true)
    // All selected, null rows included: IS NULL OR IS NOT NULL is total and known
    // everywhere, so every row of the batch survives.
    checkDifferential(spark, varkaSpark,
      "SELECT i FROM varka_dates_big WHERE d IS NULL OR d IS NOT NULL ORDER BY i",
      expectFused = true)
    // IS NULL selects exactly the null rows - the known-false mask read through NOT.
    checkDifferential(spark, varkaSpark,
      "SELECT i FROM varka_dates_big WHERE d IS NULL ORDER BY i",
      expectFused = true)
  }

  test("task 21: a mixed predicate splits - the residual conjunct stays above, correct") {
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

  test("task 21 review: the driver-side residual count reaches the SQL UI store") {
    // The listener aggregates task-end updates and posted driver updates only: a driver-side
    // `+=` that is not posted is visible to plan.metrics (what the exec suites read) but
    // never to the UI. This goes through a real tracked execution on the Arrow-backed varka
    // session - the default cache serializer has no columnar output for DateType at all, so
    // only this harness can plan the Varka node - and asserts on the status store, the same
    // surface the SQL tab renders.
    cacheDates(varkaSpark)
    // One fused entry keeps the projection eligible; the int arithmetic is residual.
    varkaSpark.sql("SELECT date_add(d, 1) AS a, i + 1 AS b FROM varka_dates").collect()
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

  test("task 21: caching a view over fused Varka work keeps the work") {
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

  test("task 21 review: a nondeterministic conjunct keeps the whole filter unfused") {
    // The conjunct split would hoist the date predicate below rand(), changing which rows
    // the seeded stream sees; the compiler declines the whole predicate instead. Plan-shape
    // assertion only: an always-true rand comparison gets optimized away entirely (leaving a
    // deterministic filter that legitimately fuses - the first version of this test learned
    // that), and a live rand makes answers uncomparable; the reorder semantics themselves
    // are pinned in the compiler suite.
    cacheDatesBig(varkaSpark, 256)
    val plan = varkaSpark.sql(
      "SELECT i FROM varka_dates_big WHERE rand(42) < 0.5 AND d IS NOT NULL ORDER BY i")
      .queryExecution.executedPlan
    assertNotFused(plan)
  }

  test("task 21: filters over multiple batches and tasks share one mask kernel class") {
    val batchSize = "32"
    try {
      spark.conf.set(SQLConf.COLUMN_BATCH_SIZE.key, batchSize)
      varkaSpark.conf.set(SQLConf.COLUMN_BATCH_SIZE.key, batchSize)
      cacheDatesBig(spark, 1024, parts = 4)
      cacheDatesBig(varkaSpark, 1024, parts = 4)
      val plan = checkDifferential(spark, varkaSpark,
        "SELECT count(*) AS c FROM varka_dates_big WHERE d < DATE'2020-06-01'",
        expectFused = true)
      val batches = plan.collectFirst { case v if isVarkaNode(v) => v }
        .flatMap(_.metrics.get("numVarkaBatches")).map(_.value).getOrElse(0L)
      assert(batches > 1L, s"expected more than one kernel batch, got $batches")
    } finally {
      spark.conf.unset(SQLConf.COLUMN_BATCH_SIZE.key)
      varkaSpark.conf.unset(SQLConf.COLUMN_BATCH_SIZE.key)
    }
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
    // Task 18 inverted what this test proves. The hundred queries differ only in their
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

  test("task 18: near-miss shapes back to back in the warm cache stay distinct") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    // Same operand structure, different op kind: date_add vs date_sub must not share a class.
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 5) AS a FROM varka_dates ORDER BY a", expectFused = true)
    checkDifferential(spark, varkaSpark,
      "SELECT date_sub(d, 5) AS a FROM varka_dates ORDER BY a", expectFused = true)
    // Same shape, different constant: shares the class and must still answer with its own
    // literal, which travels as a runtime argument rather than in the bytes.
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 30) AS a FROM varka_dates ORDER BY a", expectFused = true)
    // Same structure with one more literal slot (two distinct offsets): a different shape,
    // because the slot count changes the emitted bytecode independently of the IR.
    checkDifferential(spark, varkaSpark,
      "SELECT date_add(d, 5) AS a, date_add(d, 6) AS b FROM varka_dates ORDER BY a",
      expectFused = true)
  }

}
