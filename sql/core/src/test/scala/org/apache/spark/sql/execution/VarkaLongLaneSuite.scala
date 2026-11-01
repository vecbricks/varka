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

import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.expressions.{Expression, LessThan}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.internal.SQLConf

/**
 * The long lane, end to end (milestone 5 VARKA-29): a `bigint`, a `TIME(p)` or a day-time
 * interval column read from Varka's Arrow cache, compared in a kernel, and written back as a
 * long column - checked against the row engine over three null patterns and under both
 * consumers, the row transition and the cache builder.
 *
 * The kernel-level proof that the long lane computes what the reference says is VARKA-85's
 * (`VarkaEmitterLongLaneSuite`). What only this suite can catch is a lane that reads the right bits
 * in the wrong unit - a `TIME(3)` compared against a literal Catalyst cast from another
 * precision, an interval read as nanoseconds - because the kernel never sees the unit; and a
 * destination vector the row path cannot read back. The fixtures therefore carry every precision
 * of `TIME`, both signs and the extremes of `bigint` and the interval, and each query is asserted
 * to have fused, since a differential passes trivially when everything falls back.
 */
class VarkaLongLaneSuite extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

  /**
   * The three fixtures - nulls mixed in, none, all - each a cached view with the columns the
   * tests read: `d`, `d2` (dates, for the mixed-lane shapes), `l`, `l2` (`bigint`, across the
   * whole range), `t3`, `t3b` (`TIME(3)`), `t6`, `t9` (the same times at wider precisions),
   * `dt`, `dt2` (day-time intervals, both signs) and the two timestamps the compiler declines.
   */
  private val fixtures: Seq[(String, String)] = {
    def big(v: String) = s"CAST('$v' AS BIGINT)"
    val dense = Seq(
      s"(DATE'2024-01-31', DATE'2024-02-29', ${big("0")}, ${big("1")}, TIME'00:00:00', " +
        "TIME'12:34:56.789', INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "INTERVAL '1 02:03:04.5' DAY TO SECOND, TIMESTAMP'2021-03-14 01:30:00', " +
        "TIMESTAMP_NTZ'2021-03-14 01:30:00')",
      s"(DATE'2024-02-29', DATE'2024-02-29', ${big("5000000000")}, ${big("5000000000")}, " +
        "TIME'12:34:56.789', TIME'12:34:56.789', INTERVAL '1 02:03:04.5' DAY TO SECOND, " +
        "INTERVAL '1 02:03:04.5' DAY TO SECOND, TIMESTAMP'2021-11-07 01:30:00', " +
        "TIMESTAMP_NTZ'2021-11-07 01:30:00')",
      s"(DATE'2023-12-27', DATE'2024-01-02', ${big("-5000000000")}, ${big("42")}, " +
        "TIME'23:59:59.999', TIME'00:00:00.001', INTERVAL '-3 00:00:00.000001' DAY TO SECOND, " +
        "INTERVAL '0 00:00:01' DAY TO SECOND, TIMESTAMP'2021-03-14 03:30:00', " +
        "TIMESTAMP_NTZ'2021-03-14 03:30:00')",
      s"(DATE'2021-01-01', DATE'2020-12-31', ${big("9223372036854775807")}, " +
        s"${big("-9223372036854775808")}, TIME'06:00:00', TIME'18:00:00', " +
        "INTERVAL '100000 00:00:00' DAY TO SECOND, INTERVAL '-100000 00:00:00' DAY TO SECOND, " +
        "TIMESTAMP'1969-12-31 23:59:59', TIMESTAMP_NTZ'1969-12-31 23:59:59')",
      s"(DATE'2021-06-01', DATE'2021-06-01', ${big("-9223372036854775808")}, " +
        s"${big("9223372036854775807")}, TIME'18:00:00', TIME'06:00:00', " +
        "INTERVAL '-100000 00:00:00' DAY TO SECOND, INTERVAL '100000 00:00:00' DAY TO SECOND, " +
        "TIMESTAMP'2000-02-29 12:00:00', TIMESTAMP_NTZ'2000-02-29 12:00:00')",
      s"(DATE'2021-03-15', DATE'2021-03-14', ${big("2147483648")}, ${big("2147483647")}, " +
        "TIME'12:00:00', TIME'12:00:00.001', INTERVAL '0 00:00:00.000001' DAY TO SECOND, " +
        "INTERVAL '0 00:00:00' DAY TO SECOND, TIMESTAMP'2021-03-15 00:00:00', " +
        "TIMESTAMP_NTZ'2021-03-15 00:00:00')",
      s"(DATE'1969-12-31', DATE'1970-01-01', ${big("-2147483649")}, ${big("-2147483648")}, " +
        "TIME'01:02:03.456', TIME'01:02:03.457', INTERVAL '7 12:00:00' DAY TO SECOND, " +
        "INTERVAL '7 12:00:00' DAY TO SECOND, TIMESTAMP'1970-01-01 00:00:00', " +
        "TIMESTAMP_NTZ'1970-01-01 00:00:00')",
      s"(DATE'2000-02-29', DATE'1999-12-31', ${big("42")}, ${big("-42")}, TIME'09:30:00', " +
        "TIME'09:29:59.999', INTERVAL '-0 00:00:00.5' DAY TO SECOND, " +
        "INTERVAL '0 00:00:00.5' DAY TO SECOND, TIMESTAMP'2024-06-30 23:59:59.999999', " +
        "TIMESTAMP_NTZ'2024-06-30 23:59:59.999999')")
    val allNullRow = "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
    val mixed = dense.zipWithIndex.map { case (row, k) =>
      // A different column is null on each row, so every column meets a null somewhere and
      // no row is null in every column - the all-null row is its own fixture.
      val cells = row.stripPrefix("(").stripSuffix(")")
      // The cells are split on ", " outside quotes; the interval and time literals hold no
      // comma, so a plain split is exact here.
      val parts = cells.split(", ").toSeq
      require(parts.size == 10, s"$row split into ${parts.size} cells")
      parts.updated(k % 10, "NULL").mkString("(", ", ", ")")
    }
    val allNull = Seq.fill(8)(
      "(CAST(NULL AS DATE), CAST(NULL AS DATE), CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), " +
        "CAST(NULL AS TIME(6)), CAST(NULL AS TIME(6)), " +
        "CAST(NULL AS INTERVAL DAY TO SECOND), CAST(NULL AS INTERVAL DAY TO SECOND), " +
        "CAST(NULL AS TIMESTAMP), CAST(NULL AS TIMESTAMP_NTZ))")
    def view(values: Seq[String], where: String = ""): String =
      s"""SELECT d, d2, l, l2,
         |       CAST(t AS TIME(3)) AS t3, CAST(tb AS TIME(3)) AS t3b,
         |       CAST(t AS TIME(6)) AS t6, CAST(t AS TIME(9)) AS t9,
         |       dt, dt2, ts, ntz
         |FROM VALUES ${values.mkString(",\n  ")}
         |AS v(d, d2, l, l2, t, tb, dt, dt2, ts, ntz)$where""".stripMargin
    // As in the coverage fixtures: the dense view carries the all-null row and filters it out,
    // so its columns stay nullable and the optimizer leaves the null predicates to Varka.
    Seq("varka_long_mixed" -> view(mixed),
      "varka_long_dense" -> view(dense :+ allNullRow,
        "\nWHERE l IS NOT NULL AND l2 IS NOT NULL AND t IS NOT NULL AND tb IS NOT NULL AND " +
          "dt IS NOT NULL AND dt2 IS NOT NULL"),
      "varka_long_nulls" -> view(allNull))
  }

  private def cacheFixtures(session: SparkSession): Unit = fixtures.foreach { case (name, sql) =>
    session.sql(sql).createOrReplaceTempView(name)
    session.catalog.cacheTable(name)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cacheFixtures(spark)
    cacheFixtures(varkaSpark)
  }

  private def isColumnarVarkaNode(plan: SparkPlan): Boolean = plan match {
    case _: VarkaProjectExec | _: VarkaFilterExec => true
    case _ => false
  }

  /**
   * One query against one fixture, both consumers. The row consumer must show a Varka node,
   * answer what the row engine answers, and - unless the predicate selects nothing on this
   * fixture, in which case the scan prunes the batch before any node sees it - have run a
   * kernel over at least one batch. The columnar consumer caches a view over the query and
   * checks the cached plan was built from a columnar Varka node, which is the path that writes
   * a fused long output into an Arrow batch.
   */
  private def checkFused(query: String, fixture: String, predicate: Boolean): Unit = {
    val actual = varkaSpark.sql(query)
    val expected = expectedSql(query, actual)
    val plan = actual.queryExecution.executedPlan
    withClue(s"row consumer, $fixture: $query") {
      assertFused(plan)
      checkAnswer(actual, expected)
      if (!(predicate && expected.isEmpty)) {
        assertKernelsRan(plan)
      }
    }
    val view = "varka_long_out"
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(query).createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
    }
    try {
      withClue(s"columnar consumer, $fixture: $query") {
        val cached = varkaSpark.table(view).queryExecution.executedPlan.collectFirst {
          case scan: InMemoryTableScanExec => scan.relation.cachedPlan
        }
        assert(cached.isDefined, "expected the view to be served from the cache")
        assert(cached.get.find(isColumnarVarkaNode).isDefined,
          s"expected a columnar Varka node under the cache:\n${cached.get.treeString}")
        val served = varkaSpark.table(view)
        checkAnswer(served, expectedOf(spark.table(view), served, view))
      }
    } finally {
      for (session <- Seq(spark, varkaSpark)) session.catalog.uncacheTable(view)
    }
  }

  private def predicateQuery(where: String, fixture: String): String =
    s"SELECT l, l2, t3, dt FROM $fixture WHERE $where"

  private def projectionQuery(expr: String, fixture: String): String =
    s"SELECT $expr AS v FROM $fixture"

  private def checkPredicates(family: String, predicates: Seq[String]): Unit = {
    predicates.foreach { where =>
      test(s"$family: WHERE $where fuses and matches the row engine") {
        fixtures.foreach { case (fixture, _) =>
          checkFused(predicateQuery(where, fixture), fixture, predicate = true)
        }
      }
    }
  }

  private def checkProjections(family: String, expressions: Seq[String]): Unit = {
    expressions.foreach { expr =>
      test(s"$family: SELECT $expr fuses and matches the row engine") {
        fixtures.foreach { case (fixture, _) =>
          checkFused(projectionQuery(expr, fixture), fixture, predicate = false)
        }
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // bigint. The literals sit past the int range on purpose, where a slot in the wrong table
  // could not hold them.
  // -----------------------------------------------------------------------------------------

  checkPredicates("bigint", Seq(
    "l > l2", "l = l2", "l <= l2", "l >= 5000000000", "l < -5000000000",
    "l IS NULL", "l IS NOT NULL AND l2 < 0", "NOT (l <= l2)", "l > l2 OR l = 0"))

  checkProjections("bigint", Seq(
    "greatest(l, l2)", "least(l, 5000000000)", "CASE WHEN l < l2 THEN l ELSE l2 END",
    "if(l IS NULL, l2, l)"))

  // -----------------------------------------------------------------------------------------
  // TIME. The precisions matter: `t3 < t6` and `t9 >= t3` reach the compiler through the cast
  // type coercion inserts, and a comparison against a literal of another precision through the
  // same cast - the unit is nanoseconds whatever `p` is, and the answer must say so.
  // -----------------------------------------------------------------------------------------

  checkPredicates("TIME", Seq(
    "t3 < t3b", "t3 = TIME'12:34:56.789'", "t6 > TIME'00:00:00.000001'", "t3 < t6",
    "t9 >= t3", "t3 IS NULL", "t3 >= TIME'12:00:00' AND t3b < TIME'12:00:00'"))

  checkProjections("TIME", Seq(
    "greatest(t3, t3b)", "least(t3, TIME'06:00:00')",
    "CASE WHEN t3 < TIME'12:00:00' THEN t3 ELSE t3b END"))

  // -----------------------------------------------------------------------------------------
  // Day-time intervals: microseconds, both signs, out to a hundred thousand days.
  // -----------------------------------------------------------------------------------------

  checkPredicates("INTERVAL DAY TO SECOND", Seq(
    "dt > dt2", "dt = dt2", "dt < INTERVAL '0' SECOND", "dt IS NOT NULL",
    "dt >= INTERVAL '1' DAY OR dt2 <= INTERVAL '-1' DAY"))

  checkProjections("INTERVAL DAY TO SECOND", Seq(
    "greatest(dt, dt2)", "least(dt, INTERVAL '1' DAY)",
    "CASE WHEN dt > INTERVAL '0' SECOND THEN dt ELSE dt2 END"))

  // -----------------------------------------------------------------------------------------
  // The edges of the lane rule, end to end.
  // -----------------------------------------------------------------------------------------

  test("a projection that mixes lanes fuses one lane and computes the other on the row engine") {
    // The compiler-level half - which entry fuses and why the other does not - is pinned in
    // VarkaExpressionCompilerSuite; this is the end-to-end half: the answer is right and a Varka
    // node is in the plan for the lane it kept.
    fixtures.foreach { case (fixture, _) =>
      val query = s"SELECT greatest(d, d2) AS a, greatest(l, l2) AS b FROM $fixture"
      val actual = varkaSpark.sql(query)
      withClue(s"$fixture: $query") {
        assertFused(actual.queryExecution.executedPlan)
        checkAnswer(actual, expectedSql(query, actual))
      }
    }
  }

  test("a zoned timestamp comparison stays on the row engine, and an NTZ one fuses") {
    // Both timestamp types are the same eight-byte count physically. The zoned one is read by no
    // lane, by decision (VarkaTypes): a predicate on it alone fuses nothing, and beside a date
    // conjunct it stays as the residual row filter above the Varka node. TIMESTAMP_NTZ is read
    // on the long lane (VARKA-338), so its predicate fuses alone and beside the date conjunct.
    // The rows straddle a DST transition in the session zone, which is the case a wrongly
    // admitted zoned kernel would get wrong, and which an NTZ value, having no zone, ignores.
    withSQLConf("spark.sql.session.timeZone" -> "America/Los_Angeles") {
      fixtures.foreach { case (fixture, _) =>
        for ((col, prefix) <- Seq("ts" -> "TIMESTAMP", "ntz" -> "TIMESTAMP_NTZ")) {
          val alone = s"SELECT l FROM $fixture WHERE $col > $prefix'2021-03-14 02:00:00'"
          val aloneActual = varkaSpark.sql(alone)
          withClue(s"$fixture: $alone") {
            if (col == "ntz") {
              assertFused(aloneActual.queryExecution.executedPlan)
            } else {
              assertNotFused(aloneActual.queryExecution.executedPlan)
            }
            checkAnswer(aloneActual, expectedSql(alone, aloneActual))
          }
          val beside = s"SELECT l FROM $fixture WHERE d > d2 AND $col > " +
            s"$prefix'2021-03-14 02:00:00'"
          val besideActual = varkaSpark.sql(beside)
          withClue(s"$fixture: $beside") {
            val plan = besideActual.queryExecution.executedPlan
            assertFused(plan)
            // A conjunct the kernel does not take is a row filter above the Varka node.
            val residual = collect(plan) { case f: FilterExec => f }
            assert(residual.isEmpty === (col == "ntz"), plan.treeString)
            checkAnswer(besideActual, expectedSql(beside, besideActual))
          }
        }
      }
    }
  }

  test("where long conditions decline, the long conjunct is a row filter above the fused date " +
      "one, and the answers hold (VARKA-503)") {
    // The shared sessions set the refusal off, so the kernels' answers are checked at 128 bits
    // too; this test turns it on, as an x86 host whose long species has two lanes gets it by
    // default, and checks the plan it gives and its answers against the refusal off and against
    // Varka off, over the three null patterns.
    val key = SQLConf.VARKA_DECLINE_TWO_LANE_CONDITIONS.key
    // The column names each `<` of a condition compares.
    def comparisons(condition: Expression): Seq[Set[String]] =
      condition.collect { case lt: LessThan => lt.references.map(_.name).toSet }
    val dates = Set("d", "d2")
    val longs = Set("l", "l2")
    def varkaConditions(plan: SparkPlan): Seq[Expression] = collect(plan) {
      case f: VarkaFilterExec => f.condition
      case f: VarkaFilterColumnarToRowExec => f.condition
    }
    fixtures.foreach { case (fixture, _) =>
      val query = s"SELECT d, l, l2 FROM $fixture WHERE d < d2 AND l < l2"
      val expected = spark.sql(query)
      val before = varkaSpark.conf.getOption(key)
      varkaSpark.conf.set(key, "true")
      try {
        val declined = varkaSpark.sql(query)
        val plan = declined.queryExecution.executedPlan
        withClue(s"declined, $fixture: $query\n${plan.treeString}") {
          val fused = varkaConditions(plan)
          assert(fused.size === 1)
          assert(comparisons(fused.head) === Seq(dates))
          // The long conjunct is a row FilterExec, and the Varka filter is below it.
          val residual = collect(plan) {
            case f: FilterExec if comparisons(f.condition).contains(longs) => f
          }
          assert(residual.size === 1)
          assert(varkaConditions(residual.head.child).size === 1)
          checkAnswer(declined, expected)
          if (!expected.isEmpty) {
            assertKernelsRan(plan)
          }
        }
      } finally {
        before match {
          case Some(v) => varkaSpark.conf.set(key, v)
          case None => varkaSpark.conf.unset(key)
        }
      }
      val kept = varkaSpark.sql(query)
      val plan = kept.queryExecution.executedPlan
      withClue(s"not declined, $fixture: $query\n${plan.treeString}") {
        // With the refusal off both comparisons are one kernel's, and no row filter is left.
        assert(varkaConditions(plan).flatMap(comparisons).toSet === Set(dates, longs))
        assert(collect(plan) { case f: FilterExec => f }.isEmpty)
        checkAnswer(kept, expected)
      }
    }
  }

  test("a long filter forwards an unread bigint column through the per-row compaction") {
    // The filter's kernel writes a selection bitmap; every surviving column is then compacted
    // to the selected rows, and an eight-byte column takes the per-row copy rather than the
    // four-byte `compress` path (VARKA-128). Correct, and checked here with nulls in the column
    // the filter never reads.
    fixtures.foreach { case (fixture, _) =>
      val query = s"SELECT l2, t9, dt2 FROM $fixture WHERE l > 0"
      val actual = varkaSpark.sql(query)
      withClue(s"$fixture: $query") {
        assertFused(actual.queryExecution.executedPlan)
        checkAnswer(actual, expectedSql(query, actual))
      }
    }
  }
}
