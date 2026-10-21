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

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.exceptions.TestFailedException

import org.apache.spark.SparkArithmeticException
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, IsNotNull, IsNull, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaFallbackEvent, VarkaGuardSpec, VarkaJfrTestSupport, VarkaMatrix, VarkaTestWatchdog}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaGuardSpec.Verdict
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType
import org.apache.spark.sql.catalyst.optimizer.{BooleanSimplification, InferFiltersFromConstraints, NullDownPropagation}
import org.apache.spark.sql.catalyst.plans.logical.Project
import org.apache.spark.sql.execution.VarkaMaybeThrows.{difference => outcomeDifference, Answered, Raised}
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.execution.varka.{VarkaFallbackAccounting, VarkaKernelRunner}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{BooleanType, DataType, DateType, DayTimeIntervalType, IntegerType, LongType, TimeType, YearMonthIntervalType}

/**
 * Every row of the published coverage table, run through both engines (VARKA-120).
 *
 * `VarkaCoverageSuite` proves each row of `sql/varka/coverage.json` compiles to a fused kernel
 * and that the table names every expression the compiler admits. This suite proves the kernel
 * behind each row answers what the row engine answers. It reads the committed JSON and nothing
 * else, so a row added to the coverage suite and regenerated into the file is run here without
 * a change to this file: adding an arm without a row fails the coverage suite, adding a row
 * without correctness fails this one, and the two together are the guarantee the table makes.
 *
 * Each row runs on three fixtures over the table's twelve columns - nulls sprinkled
 * independently per column, no nulls, and every column null in every row - and under both
 * consumers a Varka node can have: rows, the plan every direct query gets, and columns, which
 * the Arrow cache builder takes when a view over the query is cached. The hand-chosen shapes in
 * `VarkaDifferentialSuite` (extreme offsets, overflow, cache conversion) stay there; this is the
 * floor under them.
 *
 * Two stronger forms run over the same rows (VARKA-138):
 *
 *  - **The partition oracle.** A predicate `p` splits a table into the rows where it is true,
 *    false and unknown. Each predicate row is run as `WHERE p`, `WHERE NOT (p)` and
 *    `WHERE (p) IS NULL` on Varka, on every fixture, under the default optimizer and with the
 *    three rules that rewrite those filters before Varka sees them excluded; each must be
 *    Spark's, and the three must share no row and leave none out. Comparing `WHERE p` alone, as
 *    the test per row does, cannot see a kernel that answers false where it should answer
 *    unknown. `WHERE (p) IS NOT NULL` must be exactly the true and the false rows, and both
 *    null tests run as the condition of a CASE, where the kernel serves the whole projection.
 *  - **The ANSI form.** A fourth fixture holds the types' extremes, and each row is run where
 *    Spark evaluates it and where Spark skips it (an untaken CASE arm, a later COALESCE
 *    argument, the right side of a false AND, of a true OR). Both engines must raise the same
 *    error - condition, SQLSTATE and message parameters, what `checkError` compares, and the
 *    message and exception class - or both answer the same rows: Varka must neither raise where
 *    Spark skips nor miss Spark's raise. The comparison is [[VarkaMaybeThrows]]'s (VARKA-317),
 *    and a kernel planted to wrap an overflowing row instead of declining fails it.
 *  - **The domains** (VARKA-279). A row whose kernel can decline a batch names in the table the
 *    predicate inside which it answers. Each such row runs over a fixture that straddles every
 *    guard's bound, cut as TLP cuts a table - the rows inside the domain, outside it and where
 *    it is unknown, each partition its own cached view - as a projection and as a filter. Inside
 *    and under IS NULL the kernel serves every batch and declines nothing; outside, every batch
 *    declines with exactly the status `VarkaGuardSpec`'s verdict calls for, read off the JFR
 *    fallback event, and the answer is Spark's, rows or the same error.
 */
class VarkaCoverageDifferentialSuite extends QueryTest with VarkaMaybeThrows
  with VarkaTestWatchdog {

  /**
   * One row of the table: what it prints, what runs, which form it takes, and the domain its
   * kernel answers inside, for a row whose kernel can decline a batch.
   */
  private case class CoverageRow(
      sql: String, executable: String, family: String, form: String, domain: Option[String])

  private lazy val rows: Seq[CoverageRow] = {
    val file = getWorkspaceFilePath("sql", "varka", "coverage.json").toFile
    val doc = new ObjectMapper().readTree(file)
    doc.get("expressions").elements().asScala.toSeq.map { e =>
      CoverageRow(e.get("sql").asText(), e.get("executable").asText(),
        e.get("family").asText(), e.get("form").asText(),
        Option(e.get("domain")).map(_.asText()).filter(_.nonEmpty))
    }
  }

  /**
   * The three fixtures, each a view with the table's columns: `d`, `d2` (dates), `i` (int),
   * `ymm`, `ymy`, `ym` (the three year-month interval units), and the long lane's `l`, `l2`
   * (bigint, across its range), `t`, `t2` (`TIME(6)`) and `dt`, `dt2` (day-time intervals of
   * both signs) - built through SQL because an interval column has no plain Scala literal. The
   * dates are the ones the other fixtures use
   * - leap days, a 31st, 1969-12-31, the dates the table's `IN` rows name, 2021 dates for its
   * `year(d) = 2021` rows, a far date inside the contract - `d2` is shifted against `d` or
   * equal to it, `i` stays in 1..12 (inside the day-offset guard, and a valid month for
   * `make_date(2021, i, 1)`), and the month counts stay inside the emitter's guard.
   */
  private val fixtures: Seq[(String, String)] = {
    // `i` stays in 1..12: it doubles as the month of `make_date(2021, i, 1)`, and under the
    // session's default ANSI mode an invalid month is an error on the row engine rather than
    // a null, which is `VarkaDifferentialSuite`'s `varka_date_parts` business, not a shape in
    // the table.
    val dense = Seq(
      "(DATE'2024-01-31', DATE'2024-02-29',  3,   3,  1,  2, CAST('5000000000' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'12:34:56.789', TIME'00:00:00', " +
        "INTERVAL '1 02:03:04.5' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND)",
      "(DATE'2024-02-29', DATE'2024-02-29',  1, -14, -2,  0, CAST('-5000000000' AS BIGINT), " +
        "CAST('-5000000000' AS BIGINT), TIME'00:00:00', TIME'23:59:59.999999', " +
        "INTERVAL '-3 00:00:00.000001' DAY TO SECOND, INTERVAL '0 00:00:01' DAY TO SECOND)",
      "(DATE'2023-12-27', DATE'2024-01-02', 12,  24,  3,  1, " +
        "CAST('9223372036854775807' AS BIGINT), CAST('-9223372036854775808' AS BIGINT), " +
        "TIME'23:59:59.999999', TIME'12:34:56.789', INTERVAL '100000 00:00:00' DAY TO SECOND, " +
        "INTERVAL '-100000 00:00:00' DAY TO SECOND)",
      "(DATE'2021-01-01', DATE'2020-12-31',  4,   0,  0, 11, CAST('0' AS BIGINT), " +
        "CAST('0' AS BIGINT), TIME'06:00:00', TIME'06:00:00', " +
        "INTERVAL '0 00:00:00' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND)",
      "(DATE'2021-06-01', DATE'2021-06-01',  6,   7,  2,  3, CAST('2147483648' AS BIGINT), " +
        "CAST('2147483647' AS BIGINT), TIME'12:00:00', TIME'12:00:00.000001', " +
        "INTERVAL '7 12:00:00' DAY TO SECOND, INTERVAL '7 12:00:00' DAY TO SECOND)",
      "(DATE'2021-03-15', DATE'2021-03-14', 11,   1,  1,  5, CAST('-1' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'01:02:03.456', TIME'01:02:03.457', " +
        "INTERVAL '-0 00:00:00.5' DAY TO SECOND, INTERVAL '0 00:00:00.5' DAY TO SECOND)",
      "(DATE'1969-12-31', DATE'1970-01-01',  5,  -1,  0,  0, CAST('42' AS BIGINT), " +
        "CAST('-42' AS BIGINT), TIME'18:00:00', TIME'06:00:00', " +
        "INTERVAL '1 00:00:00' DAY TO SECOND, INTERVAL '-1 00:00:00' DAY TO SECOND)",
      "(DATE'2000-02-29', DATE'1999-12-31',  2,  12,  4,  6, CAST('-2147483649' AS BIGINT), " +
        "CAST('-2147483648' AS BIGINT), TIME'09:30:00', TIME'09:29:59.999', " +
        "INTERVAL '0 00:00:00.000001' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND)",
      "(DATE'9999-12-01', DATE'0001-01-15',  7,   5,  1,  1, CAST('12345678901234' AS BIGINT), " +
        "CAST('12345678901234' AS BIGINT), TIME'12:34:56.789', TIME'12:34:56.789', " +
        "INTERVAL '2 00:00:00' DAY TO SECOND, INTERVAL '2 00:00:00' DAY TO SECOND)",
      "(DATE'2021-11-01', DATE'2021-11-01',  9, 100,  2,  4, CAST('7' AS BIGINT), " +
        "CAST('11' AS BIGINT), TIME'00:00:00.000001', TIME'00:00:00', " +
        "INTERVAL '0 00:01:00' DAY TO SECOND, INTERVAL '0 00:00:59' DAY TO SECOND)")
    val allNullRow =
      "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
    val mixed = Seq(
      "(DATE'2024-01-31', DATE'2024-02-29',  3,   3,  1,  2, CAST('5000000000' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'12:34:56.789', TIME'00:00:00', " +
        "INTERVAL '1 02:03:04.5' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND)",
      "(NULL,             DATE'2024-02-29',  1, -14, -2,  0, NULL, " +
        "CAST('-5000000000' AS BIGINT), TIME'00:00:00', TIME'23:59:59.999999', " +
        "INTERVAL '-3 00:00:00.000001' DAY TO SECOND, INTERVAL '0 00:00:01' DAY TO SECOND)",
      "(DATE'2023-12-27', NULL,             12,  24,  3,  1, " +
        "CAST('9223372036854775807' AS BIGINT), CAST('-9223372036854775808' AS BIGINT), " +
        "TIME'23:59:59.999999', TIME'12:34:56.789', INTERVAL '100000 00:00:00' DAY TO SECOND, " +
        "INTERVAL '-100000 00:00:00' DAY TO SECOND)",
      "(DATE'2021-01-01', DATE'2020-12-31', NULL, 0,  0, 11, CAST('0' AS BIGINT), " +
        "CAST('0' AS BIGINT), NULL, TIME'06:00:00', INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "INTERVAL '0 00:00:00' DAY TO SECOND)",
      "(DATE'2021-06-01', DATE'2021-06-01',  6, NULL, 2,  3, CAST('2147483648' AS BIGINT), " +
        "CAST('2147483647' AS BIGINT), TIME'12:00:00', TIME'12:00:00.000001', " +
        "INTERVAL '7 12:00:00' DAY TO SECOND, INTERVAL '7 12:00:00' DAY TO SECOND)",
      "(DATE'2021-03-15', DATE'2021-03-14', 11,   1, NULL, 5, CAST('-1' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'01:02:03.456', TIME'01:02:03.457', NULL, " +
        "INTERVAL '0 00:00:00.5' DAY TO SECOND)",
      "(DATE'1969-12-31', DATE'1970-01-01',  5,  -1,  0, NULL, CAST('42' AS BIGINT), " +
        "CAST('-42' AS BIGINT), TIME'18:00:00', TIME'06:00:00', " +
        "INTERVAL '1 00:00:00' DAY TO SECOND, INTERVAL '-1 00:00:00' DAY TO SECOND)",
      allNullRow,
      "(DATE'9999-12-01', DATE'0001-01-15',  7,   5,  1,  1, CAST('-2147483649' AS BIGINT), " +
        "NULL, TIME'09:30:00', TIME'09:29:59.999', INTERVAL '0 00:00:00.000001' DAY TO SECOND, " +
        "INTERVAL '0 00:00:00' DAY TO SECOND)",
      "(DATE'2021-11-01', DATE'2021-11-01',  9, 100,  2,  4, CAST('12345678901234' AS BIGINT), " +
        "CAST('12345678901234' AS BIGINT), TIME'12:34:56.789', TIME'12:34:56.789', " +
        "INTERVAL '2 00:00:00' DAY TO SECOND, INTERVAL '2 00:00:00' DAY TO SECOND)")
    val allNull = Seq.fill(8)(
      "(CAST(NULL AS DATE), CAST(NULL AS DATE), CAST(NULL AS INT), CAST(NULL AS INT), " +
        "CAST(NULL AS INT), CAST(NULL AS INT), CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), " +
        "CAST(NULL AS TIME(6)), CAST(NULL AS TIME(6)), " +
        "CAST(NULL AS INTERVAL DAY TO SECOND), CAST(NULL AS INTERVAL DAY TO SECOND))")
    // Each row gets its position as `rid`, so the partition test can tell rows apart by id -
    // the all-null fixture's rows are otherwise identical.
    def view(values: Seq[String], where: String = ""): String =
      VarkaCoverageDifferentialSuite.fixtureView(values, where)
    // The dense fixture carries the all-null row in its VALUES and filters it out again, so
    // its columns stay nullable: over a column Spark knows to be non-nullable, `d IS NULL`
    // folds to false, `coalesce(d, d2)` to `d`, and the optimizer leaves nothing for Varka to
    // fuse - a fact about the optimizer, not about the table.
    Seq("varka_coverage_mixed" -> view(mixed), "varka_coverage_dense" -> view(dense :+ allNullRow,
      "\nWHERE d IS NOT NULL AND d2 IS NOT NULL AND i IS NOT NULL AND m IS NOT NULL AND " +
        "y IS NOT NULL AND mm IS NOT NULL AND l IS NOT NULL AND l2 IS NOT NULL AND " +
        "t IS NOT NULL AND t2 IS NOT NULL AND dt IS NOT NULL AND dt2 IS NOT NULL"),
      "varka_coverage_nulls" -> view(allNull))
  }

  /**
   * The ANSI form's fixture: one row at the extremes of every column's type beside one ordinary
   * row. Under ANSI, the extremes make the rows that can overflow or leave a range raise, so the
   * form can tell a skipped position from an evaluated one. Column `i` at `Int.MaxValue` is not
   * a valid month, so `make_date(2021, i, 1)` raises too, which is a fair cell: both engines
   * must raise it alone and neither where it is skipped.
   *
   * The two rows hold no null, so `coalesce(<column>, e)` never needs `e` on Spark. A third row
   * of nulls is filtered out again by `rid`, which keeps every column nullable: over a column
   * Spark knows to be non-nullable the optimizer would fold that COALESCE to the column, and
   * Varka would never see `e` (the dense fixture's trick above).
   */
  private val extremes: (String, String) = {
    val rows = Seq(
      "(DATE'9999-12-31', DATE'0001-01-01', 2147483647, -2147483648, 178956970, 7, " +
        "CAST('9223372036854775807' AS BIGINT), CAST('-9223372036854775808' AS BIGINT), " +
        "TIME'23:59:59.999999', TIME'00:00:00', " +
        "INTERVAL '106751991 04:00:54.775807' DAY TO SECOND, " +
        "INTERVAL '-106751991 04:00:54.775808' DAY TO SECOND)",
      "(DATE'2024-02-29', DATE'2024-01-31', 3, 3, 1, 2, CAST('5000000000' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'12:34:56.789', TIME'00:00:00', " +
        "INTERVAL '1 02:03:04.5' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND)",
      "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
    // `lrid` is `rid` as a BIGINT, the guard of a long-lane expression: one kernel holds one
    // lane, so `CASE WHEN rid < 0 THEN greatest(l, l2) ...` mixes the int and long lanes and is
    // never fused, where `CASE WHEN lrid < 0 ...` is.
    val view = VarkaCoverageDifferentialSuite.fixtureView(rows, "\nWHERE rid < 2")
    "varka_coverage_extremes" -> s"SELECT *, CAST(rid AS BIGINT) AS lrid FROM ($view)"
  }

  private def cacheFixtures(session: SparkSession): Unit = {
    fixtures.foreach { case (name, sql) =>
      session.sql(sql).createOrReplaceTempView(name)
      session.catalog.cacheTable(name)
    }
    // One partition, so both rows are one batch and an error is the same row's on both engines
    // rather than whichever failing task reaches the driver first (VARKA-42's discipline).
    session.sql(extremes._2).coalesce(1).createOrReplaceTempView(extremes._1)
    session.catalog.cacheTable(extremes._1)
  }

  private def isColumnarVarkaNode(plan: SparkPlan): Boolean = plan match {
    case _: VarkaProjectExec | _: VarkaFilterExec => true
    case _ => false
  }

  /**
   * Whether Varka's kernels evaluated the whole of a plan that ran: they served a batch, and
   * nothing was left to the row engine - no projection entry declined to the residual, and no
   * row `FilterExec` holding a conjunct the rule split off. A Varka node in the plan is not
   * enough: it can decline every batch, or fuse a guard while Spark evaluates the rest.
   */
  private def kernelServed(plan: SparkPlan): Boolean = {
    val nodes = collect(plan) { case v if isVarkaNode(v) => v }
    def sum(metric: String): Long = nodes.flatMap(_.metrics.get(metric)).map(_.value).sum
    sum("numVarkaBatches") > 0 && sum("numResidualEntries") == 0 &&
      find(plan) { case _: FilterExec => true; case _ => false }.isEmpty
  }

  private def queryFor(row: CoverageRow, fixture: String): String = row.form match {
    case "predicate" => s"SELECT d, d2, i FROM $fixture WHERE ${row.executable}"
    case _ => s"SELECT ${row.executable} AS v FROM $fixture"
  }

  /** The row consumer: the plan a direct query gets, checked warm as well as cold. */
  private def checkRowConsumer(row: CoverageRow, fixture: String): Unit = {
    val query = queryFor(row, fixture)
    val context = s"row consumer, $fixture, ${row.family}: ${row.sql}"
    val expected = spark.sql(query)
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    withClue(context) {
      assertFused(plan)
      checkAnswer(actual, expected)
      // A predicate that selects nothing on a fixture may never reach the kernel: the in-memory
      // scan prunes a batch its column statistics rule out - an all-null batch under a
      // null-rejecting predicate, a null-free batch under `IS NULL` - before the Varka node
      // sees it. The node is in the plan and the empty answer is checked; only the batch
      // metric has nothing to count.
      if (!(row.form == "predicate" && expected.isEmpty)) {
        assertKernelsRan(plan)
      }
      checkAnswer(varkaSpark.sql(query), expected)
    }
  }

  /**
   * The columnar consumer: a cached view over the query, which the Arrow cache builder feeds
   * from the columnar Varka node rather than through rows.
   */
  private def checkColumnarConsumer(row: CoverageRow, fixture: String): Unit = {
    val query = queryFor(row, fixture)
    val context = s"columnar consumer, $fixture, ${row.family}: ${row.sql}"
    val view = "varka_coverage_out"
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(query).createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
    }
    try {
      withClue(context) {
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

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cacheFixtures(spark)
    cacheFixtures(varkaSpark)
    cacheEdges(spark)
    cacheEdges(varkaSpark)
  }

  test("the coverage table has rows to run") {
    assert(rows.size >= 57, s"only ${rows.size} rows read from sql/varka/coverage.json")
    assert(rows.forall(_.executable.nonEmpty))
  }

  rows.foreach { row =>
    test(s"${row.family}: ${row.sql} matches the row engine under both consumers") {
      fixtures.foreach { case (fixture, _) =>
        checkRowConsumer(row, fixture)
        checkColumnarConsumer(row, fixture)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The partition oracle (VARKA-138)
  // ---------------------------------------------------------------------------------------------

  /** Every column of a fixture, `rid` first, as the partition queries select them. */
  private val allColumns = "rid, d, d2, i, ymm, ymy, ym, l, l2, t, t2, dt, dt2"

  /**
   * The optimizer rules that rewrite the partitions before Varka sees them: `BooleanSimplification`
   * and `NullDownPropagation` turn `NOT (d < d2)` and `(d < d2) IS NULL` into tests on the
   * columns, and `InferFiltersFromConstraints` adds `isnotnull` conjuncts beside a predicate that
   * rejects nulls. With them excluded, `NOT (p)` reaches Varka as written.
   */
  private val partitionRewrites = Seq(BooleanSimplification, NullDownPropagation,
    InferFiltersFromConstraints).map(_.ruleName).mkString(",")

  private def isVarkaFilter(plan: SparkPlan): Boolean = plan match {
    case _: VarkaFilterExec | _: VarkaFilterColumnarToRowExec => true
    case _ => false
  }

  /**
   * What is wrong with Varka's partitions of a table under a predicate `p`, or nothing.
   * `varkaTrue`, `varkaFalse` and `varkaUnknown` are Varka's answers to `WHERE p`,
   * `WHERE NOT (p)` and `WHERE (p) IS NULL`, the rest Spark's: the same three and the whole
   * table; every row starts with its `rid`. Rows are compared as rows, never counted - a count
   * misses a wrong row standing in for a right one.
   *
   * With each of Varka's three equal to Spark's, the last checks hold Spark's own three filters
   * to each other: no row in two of them, and none in neither. That is the point of them: both
   * engines share the optimizer, so a rewrite of `NOT (p)` that both get wrong agrees with
   * itself in a differential and still breaks the partition.
   */
  private def partitionFindings(
      varkaTrue: Seq[Row],
      varkaFalse: Seq[Row],
      varkaUnknown: Seq[Row],
      sparkTrue: Seq[Row],
      sparkFalse: Seq[Row],
      sparkUnknown: Seq[Row],
      table: Seq[Row]): Seq[String] = {
    def ids(rows: Seq[Row]): Set[Int] = rows.map(_.getInt(0)).toSet
    val (t, f, u) = (ids(varkaTrue), ids(varkaFalse), ids(varkaUnknown))
    def shared(a: Set[Int], b: Set[Int], what: String): Option[String] = {
      val both = a.intersect(b)
      Option.when(both.nonEmpty)(s"rows both $what: rid ${both.toSeq.sorted.mkString(", ")}")
    }
    val neither = ids(table) -- t -- f -- u
    Seq(
      QueryTest.sameRows(sparkTrue, varkaTrue).map(d => s"WHERE p differs from Spark's:\n$d"),
      QueryTest.sameRows(sparkFalse, varkaFalse).map(d =>
        s"WHERE NOT (p) differs from Spark's:\n$d"),
      QueryTest.sameRows(sparkUnknown, varkaUnknown).map(d =>
        s"WHERE (p) IS NULL differs from Spark's:\n$d"),
      shared(t, f, "true and false"),
      shared(t, u, "true and unknown"),
      shared(f, u, "false and unknown"),
      Option.when(neither.nonEmpty)(
        s"rows neither true, false nor unknown: rid ${neither.toSeq.sorted.mkString(", ")}")
    ).flatten
  }

  /**
   * Whether `p` can be null on `fixture`. Where it cannot - a null test is never null - Spark's
   * `NullPropagation` folds `(p) IS NULL` to false and `(p) IS NOT NULL` to true before any plan
   * holds a filter or a condition over them, so there is nothing for Varka to fuse; the answers
   * are still compared.
   */
  private def nullable(p: String, fixture: String): Boolean =
    spark.sql(s"SELECT ($p) AS b FROM $fixture").schema("b").nullable

  /**
   * Whether Spark's optimized plan for `query` holds a null test over a computed value, neither
   * a column nor a predicate: `NullDownPropagation` turns `(month(d) > i) IS NULL` into
   * `isnull(month(d)) OR isnull(i)`. Varka declines such a test by design, since a computed
   * value's validity word exists only during its value walk, after the conditions are lowered.
   */
  private def nullTestOverValue(query: String): Boolean = {
    def overValue(child: Expression): Boolean =
      !child.isInstanceOf[Attribute] && child.dataType != BooleanType
    spark.sql(query).queryExecution.optimizedPlan.exists(_.expressions.exists(_.exists {
      case IsNull(child) => overValue(child)
      case IsNotNull(child) => overValue(child)
      case _ => false
    }))
  }

  /** The partition oracle for one predicate row on one fixture, under the session's optimizer. */
  private def partitionCheck(row: CoverageRow, fixture: String): Seq[String] = {
    val p = row.executable
    def query(where: String): String = s"SELECT $allColumns FROM $fixture WHERE $where"
    def onSpark(where: String): Seq[Row] = spark.sql(query(where)).collect().toSeq
    // Varka's answer, from a Varka filter whose kernel served the batches: a filter the rule
    // left to Spark, or one that declined, would make the comparison Spark against itself.
    // `fused = false` is a filter the optimizer removed or turned into a null test over a
    // computed value, whose answer is compared alone.
    def onVarka(where: String, fused: Boolean = true): Either[String, Seq[Row]] = {
      val df = varkaSpark.sql(query(where))
      val plan = df.queryExecution.executedPlan
      if (!fused) {
        Right(df.collect().toSeq)
      } else if (find(plan)(isVarkaFilter).isEmpty) {
        Left(s"no Varka filter for WHERE $where:\n${plan.treeString}")
      } else {
        val rows = df.collect().toSeq
        if (kernelServed(plan)) Right(rows)
        else Left(s"no kernel served all of WHERE $where:\n${plan.treeString}")
      }
    }
    val unknown = s"($p) IS NULL"
    val known = s"($p) IS NOT NULL"
    def fuses(where: String): Boolean = nullable(p, fixture) && !nullTestOverValue(query(where))
    (onVarka(p), onVarka(s"NOT ($p)"), onVarka(unknown, fuses(unknown))) match {
      case (Right(t), Right(f), Right(u)) =>
        partitionFindings(t, f, u, onSpark(p), onSpark(s"NOT ($p)"), onSpark(unknown),
          spark.sql(s"SELECT $allColumns FROM $fixture").collect().toSeq) ++
          // The known rows, as a filter of their own: Spark's, and exactly the true and false.
          (onVarka(known, fuses(known)) match {
            case Right(k) =>
              QueryTest.sameRows(onSpark(known), k).map(d =>
                s"WHERE (p) IS NOT NULL differs from Spark's:\n$d") ++
                Option.when(k.map(_.getInt(0)).toSet != (t ++ f).map(_.getInt(0)).toSet)(
                  "WHERE (p) IS NOT NULL is not the true and the false rows")
            case Left(finding) => Seq(finding)
          })
      case (t, f, u) => Seq(t, f, u).flatMap(_.left.toOption)
    }
  }

  rows.filter(_.form == "predicate").foreach { row =>
    test(s"${row.family}: ${row.sql} splits every fixture into true, false and unknown rows") {
      val settings =
        Seq("default optimizer" -> None, "rewrites excluded" -> Some(partitionRewrites))
      // The in-memory scan prunes a batch its statistics rule out - an all-null batch under a
      // predicate that rejects nulls - and a pruned batch meets no kernel, so pruning is off.
      val findings = withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false") {
        for {
          (setting, excluded) <- settings
          (fixture, _) <- fixtures
          finding <- excluded match {
            case Some(rules) => withBothConf(SQLConf.OPTIMIZER_EXCLUDED_RULES.key, rules) {
              partitionCheck(row, fixture)
            }
            case None => partitionCheck(row, fixture)
          }
        } yield s"$fixture, $setting: $finding"
      }
      assert(findings.isEmpty, findings.mkString("\n"))
    }
  }

  /**
   * Each predicate row's null tests as the condition of a CASE: `CASE WHEN (p) IS NULL THEN a
   * ELSE b END` and the same with `IS NOT NULL`. Run with the partition rewrites excluded, since
   * `NullDownPropagation` would otherwise turn the test into null tests on the columns, which the
   * per-row test already runs. The arms are two dates or two bigints: a kernel holds its
   * condition on the arms' lane, or lifts an int condition to meet bigint arms, and which lane a
   * predicate's condition lands on (`i < l` compares bigints, `hour(t) = 12` ints) is the
   * compiler's, not the SQL's, so one pair of arms must have its kernel serve the whole
   * projection. Every answer is Spark's.
   */
  private def nullTestConditionCheck(row: CoverageRow, fixture: String): Seq[String] = {
    val p = row.executable
    Seq("IS NULL", "IS NOT NULL").flatMap { test =>
      val outcomes = Seq(("d", "d2"), ("l", "l2")).map { case (a, b) =>
        val query = s"SELECT CASE WHEN ($p) $test THEN $a ELSE $b END AS v FROM $fixture"
        val actual = varkaSpark.sql(query)
        val plan = actual.queryExecution.executedPlan
        val rows = actual.collect().toSeq
        // Read after the run: the metric counts the batches the kernel served.
        val served = kernelServed(plan)
        val wrong = QueryTest.sameRows(spark.sql(query).collect().toSeq, rows).map(d =>
          s"$query differs from Spark's:\n$d")
        (served, s"$query:\n${plan.treeString}", wrong.toSeq)
      }
      outcomes.flatMap(_._3) ++ Option.when(nullable(p, fixture) && !outcomes.exists(_._1))(
        "no kernel served all of either arm pair:\n" + outcomes.map(_._2).mkString("\n"))
    }
  }

  rows.filter(_.form == "predicate").foreach { row =>
    test(s"${row.family}: ${row.sql} under IS NULL and IS NOT NULL fuses as a CASE condition") {
      val findings = withBothConf(SQLConf.OPTIMIZER_EXCLUDED_RULES.key, partitionRewrites) {
        fixtures.flatMap { case (fixture, _) =>
          nullTestConditionCheck(row, fixture).map(f => s"$fixture: $f")
        }
      }
      assert(findings.isEmpty, findings.mkString("\n"))
    }
  }

  test("the partition oracle fails a filter that answers unknown where it should answer false") {
    // No kernel can be broken this way on purpose, so the fault is planted in the answers: a
    // filter that swapped known-false and unknown would return Spark's `IS NULL` rows for
    // `NOT (p)`. The oracle must name both the wrong false rows and the wrong rest.
    val fixture = "varka_coverage_mixed"
    def answer(where: String): Seq[Row] =
      spark.sql(s"SELECT $allColumns FROM $fixture WHERE $where").collect().toSeq
    val (t, f, u) = (answer("d < d2"), answer("NOT (d < d2)"), answer("(d < d2) IS NULL"))
    assert(f.nonEmpty && u.nonEmpty, "the planted fault needs both partitions non-empty")
    val table = spark.sql(s"SELECT $allColumns FROM $fixture").collect().toSeq
    assert(partitionFindings(t, f, u, t, f, u, table).isEmpty)
    val findings = partitionFindings(t, u, u, t, f, u, table)
    assert(findings.exists(_.startsWith("WHERE NOT (p) differs")), findings.mkString("\n"))
    assert(findings.exists(_.startsWith("rows both false and unknown")), findings.mkString("\n"))
    assert(findings.exists(_.startsWith("rows neither true, false nor unknown")),
      findings.mkString("\n"))
  }

  // ---------------------------------------------------------------------------------------------
  // The ANSI form (VARKA-138)
  // ---------------------------------------------------------------------------------------------

  private val extremesTable = extremes._1

  /** The positions of the ANSI form; every one but the first is one Spark skips on both rows. */
  private val Alone = "alone"
  private val CaseArm = "the untaken arm of a CASE"
  private val CoalesceTail = "a later COALESCE argument"
  private val FalseAnd = "the right side of a false AND"
  private val TrueOr = "the right side of a true OR"

  /** Whether values of `t` live on Varka's long lane rather than its int lane. */
  private def onLongLane(t: DataType): Boolean = t match {
    case LongType | _: TimeType | _: DayTimeIntervalType => true
    case _ => false
  }

  /**
   * A value of the extremes fixture of `t`'s type that never raises, to stand beside an
   * expression of that type in a CASE (`caseArm`) or ahead of it in a COALESCE: a column, which
   * is non-null on both rows, so a COALESCE that starts with one never needs its second
   * argument. Where the fixture has the type's family but not its exact fields or precision -
   * `t - t2` is an HOUR TO SECOND interval beside `dt2`'s DAY TO SECOND - Spark widens the
   * expression to the common type with a cast, which is still evaluated only where `e` is.
   * Varka compiles a bare int column as no value, so the int CASE's other arm is `year(d2)`, an
   * int the kernel computes and that cannot raise; the int COALESCE keeps the bare column, and
   * the declined list says why it is not fused. The same column is what the false AND's right
   * side compares `e` with (`caseArm` false), where a bare int column is an operand Varka takes.
   */
  private def standIn(t: DataType, caseArm: Boolean): Option[String] = t match {
    case DateType => Some("d2")
    case IntegerType => Some(if (caseArm) "year(d2)" else "i")
    case LongType => Some("l2")
    case _: TimeType => Some("t2")
    case _: DayTimeIntervalType => Some("dt2")
    case YearMonthIntervalType(start, end) if start == end =>
      Some(if (start == YearMonthIntervalType.YEAR) "ymy" else "ymm")
    case _: YearMonthIntervalType => Some("ym")
    case _ => None
  }

  /**
   * The ANSI form's queries for a row: each position and its query, or None where the position
   * cannot be written for the row. A projection `e` runs alone, in the untaken arm of
   * `CASE WHEN rid < 0`, after a column in a COALESCE, and compared with a column behind
   * `rid < 0`;
   * a predicate `p` behind `rid < 0 AND` and `rid >= 0 OR`. `rid` is 0 and 1, so each guard
   * decides every row, and Spark never evaluates the guarded side. Where the guard and the
   * guarded side form one Varka expression - the CASE, the OR, the AND of a predicate - the
   * guard is `lrid` for a long-lane expression, so the two share a lane.
   */
  private def ansiQueries(row: CoverageRow): Seq[(String, Option[String])] = {
    val e = row.executable
    val from = s"FROM $extremesTable"
    row.form match {
      case "predicate" =>
        // The predicate's own columns, analyzed as a projection so no filter of the fixture's
        // view joins them.
        val read = spark.sql(s"SELECT ($e) AS v $from").queryExecution.analyzed match {
          case Project(list, _) => list.flatMap(_.references.toSeq)
          case other => fail(s"expected a projection, got\n$other")
        }
        val guard = if (read.exists(a => onLongLane(a.dataType))) "lrid" else "rid"
        Seq(
          FalseAnd -> Some(s"SELECT rid $from WHERE $guard < 0 AND ($e)"),
          TrueOr -> Some(s"SELECT rid $from WHERE $guard >= 0 OR ($e)"))
      case _ =>
        val t = spark.sql(s"SELECT $e AS v $from").schema.head.dataType
        val guard = if (onLongLane(t)) "lrid" else "rid"
        Seq(
          Alone -> Some(s"SELECT $e AS v $from"),
          CaseArm -> standIn(t, caseArm = true).map(c =>
            s"SELECT CASE WHEN $guard < 0 THEN $e ELSE $c END AS v $from"),
          CoalesceTail -> standIn(t, caseArm = false).map(c =>
            s"SELECT coalesce($c, $e) AS v $from"),
          // `e = <column>`, not `e IS NOT NULL`: Varka fuses no IS NOT NULL over a computed
          // value, so Spark's own filter would evaluate `e` above the kernel.
          FalseAnd -> standIn(t, caseArm = false).map(c =>
            s"SELECT rid $from WHERE $guard < 0 AND ($e) = $c"))
    }
  }

  /**
   * The positions where Varka does not serve a row's expression, with the reason, keyed by the
   * row's `sql` and the position. A skipped position not served by a kernel is a check that
   * compared Spark with itself, so it must be listed here; a listed one that is served fails as
   * stale. Checked under the defaults only: what a configuration of the option matrix fuses is
   * the matrix's own business, and the outcomes are compared under every configuration.
   */
  private val ansiDeclined: Map[(String, String), String] = {
    val intCoalesce = "Varka's COALESCE takes only a bare date column before its last operand, " +
      "and compiles no bare int column as a value (m9/SCOPE.md item 55)"
    val intValued = Seq("datediff(d2, d)", "unix_date(d)", "year(d)", "month(d)", "day(d)",
      "quarter(d)", "dayofyear(d)", "dayofweek(d)", "weekday(d)",
      "extract(DAYOFWEEK_ISO FROM d)", "weekofyear(d)", "extract(YEAROFWEEK FROM d)",
      "extract(YEAR FROM ym)", "extract(YEAR FROM ym) - 1", "i + 1", "i - 1", "year(d) * 2",
      "-i", "year(d) + i", "CAST(ymm AS INT)", "hour(t)", "minute(t2)", "second(t)",
      "hour(t) + 1", "CAST(time_to_millis(t) AS INT)", "i & 255", "i | 4096", "i ^ 21845", "~i",
      "i << 3", "shiftright(i, i)", "i >>> 28", "bit_count(i)", "bit_count(l)",
      "bit_count(l) + 1")
    // `CAST(i AS INTERVAL DAY)` throws past `INTERVAL_DAY_LIMIT_DAYS`, so the compiler puts a
    // bound on `i` that the evaluator checks before the kernel runs; the bound belongs to the
    // column, not to the arm reading it, and the extremes row's `Int.MaxValue` declines the
    // batch wherever the expression sits (m9/SCOPE.md item 96).
    val dayCast = "d + CAST(i AS INTERVAL DAY)"
    val columnBound = "the offset column's bound is checked per batch whichever arm reads it, " +
      "and Int.MaxValue is past it, so the batch declines (m9/SCOPE.md item 96)"
    // A check that condemns the batch - an overflow, a field out of range, a date leaving the
    // range the calendar lowering is exact over - is qualified by the condition of a CASE arm
    // it sits under (`guardUnderArm`), but not by the left side of an AND: the kernel computes
    // the right side on every lane, and the extremes row's lane condemns the batch although
    // `rid < 0` already rejected it (m9/SCOPE.md item 96).
    val unqualified = "a check on the right side of an AND condemns the batch from a lane the " +
      "left side rejected (m9/SCOPE.md item 96)"
    val checkedRight = Seq("make_date(2021, i, 1)", "add_months(d, i)", "d + ym", "d - ym",
      "ym - ymm", "i + 1", "year(d) + i")
    intValued.map(sql => (sql, CoalesceTail) -> intCoalesce).toMap ++
      Seq(CaseArm, CoalesceTail, FalseAnd).map(p => (dayCast, p) -> columnBound) ++
      checkedRight.map(sql => (sql, FalseAnd) -> unqualified)
  }

  /** What the ANSI form found for one row, and where a kernel served it or both engines raised. */
  private case class AnsiResult(findings: Seq[String], served: Seq[String], raised: Seq[String])

  /** The ANSI form for one row. */
  private def ansiCheck(row: CoverageRow): AnsiResult = {
    val strict = VarkaMatrix.config.isEmpty
    val results = ansiQueries(row).map { case (position, query) =>
      val declined = ansiDeclined.get((row.sql, position))
      query match {
        case None =>
          val finding = Option.when(strict && declined.isEmpty)(
            s"$position: no column of the expression's type, and not on the declined list")
          (finding.toSeq, None, None)
        case Some(q) =>
          val (varka, plan) = runOutcome(varkaSpark, q)
          val served = varka.isInstanceOf[Answered] && plan.exists(kernelServed)
          val difference = outcomeDifference(outcome(spark, q), varka)
          val reach = if (position == Alone || !strict) None
          else if (served && declined.isDefined) {
            Some("served by a kernel, but on the declined list: remove the stale entry")
          } else if (!served && declined.isEmpty) {
            val node = if (plan.exists(find(_)(isVarkaNode).isDefined)) {
              "a Varka node that served no batch, or left part of the query to the row engine"
            } else {
              "no Varka node"
            }
            Some(s"not served by a kernel ($node), and not on the declined list:\n" +
              plan.map(_.treeString).getOrElse("(no plan)"))
          } else {
            None
          }
          ((difference ++ reach).map(f => s"$position: $q\n$f").toSeq,
            Option.when(served)(position),
            Option.when(difference.isEmpty && varka.isInstanceOf[Raised])(position))
      }
    }
    AnsiResult(results.flatMap(_._1), results.flatMap(_._2), results.flatMap(_._3))
  }

  rows.foreach { row =>
    test(s"${row.family}: ${row.sql} raises under ANSI only where the row engine does") {
      // The in-memory scan prunes a batch whose statistics rule a filter out, and `rid < 0`
      // rules out every batch: pruned, the guarded side would never meet a kernel and the
      // check would pass by never running.
      val result = withAnsi(enabled = true) {
        withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false")(ansiCheck(row))
      }
      def list(positions: Seq[String]): String =
        if (positions.isEmpty) "none" else positions.mkString(", ")
      info(s"served by a kernel in: ${list(result.served)}")
      info(s"raised by both engines in: ${list(result.raised)}")
      assert(result.findings.isEmpty, result.findings.mkString("\n\n"))
    }
  }

  test("the ANSI form's declined list names only positions its reach check reads") {
    // An entry for a row the table lacks, for a position the row's form never runs, or for the
    // lone position, which has no reach check, could never go stale.
    val unread = ansiDeclined.keys.filterNot { case (sql, position) =>
      position != Alone && rows.find(_.sql == sql).exists(row =>
        ansiQueries(row).exists { case (p, q) => p == position && q.isDefined })
    }
    assert(unread.isEmpty, s"declined entries no reach check reads: $unread")
  }

  test("the ANSI form fails a raise where the row engine skips, and a missing one") {
    // No kernel raises on purpose, so the fault is planted in the outcomes, which are real:
    // `i + 1` overflows alone on the extremes row and is skipped in the untaken arm. A kernel
    // that evaluated the arm and raised would give the CASE the lone outcome, and one that
    // wrapped instead of raising would give the lone query rows.
    withAnsi(enabled = true) {
      withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false") {
        val alone = outcome(spark, s"SELECT i + 1 AS v FROM $extremesTable")
        val skipped = outcome(spark,
          s"SELECT CASE WHEN rid < 0 THEN i + 1 ELSE i END AS v FROM $extremesTable")
        val raised = alone match {
          case r: Raised => r
          case other => fail(s"expected i + 1 to overflow on the extremes row, got $other")
        }
        assert(raised.condition === "ARITHMETIC_OVERFLOW")
        assert(skipped.isInstanceOf[Answered])
        assert(outcomeDifference(skipped, skipped).isEmpty)
        assert(outcomeDifference(skipped, alone).exists(_.startsWith("only Varka raises")))
        assert(outcomeDifference(alone, skipped).exists(_.startsWith("only Spark raises")))
        // The same condition with other parameters, another message or another exception class
        // is a different error.
        for (moved <- Seq(raised.copy(parameters = raised.parameters + ("planted" -> "1")),
            raised.copy(message = raised.message + " (planted)"),
            raised.copy(exceptionClass = classOf[ArithmeticException].getName))) {
          assert(outcomeDifference(alone, moved).exists(_.startsWith("errors differ")), moved)
        }
        // Another condition, from a real query: `make_date(2021, i, 1)` leaves the month range
        // on the same row (VARKA-317).
        val otherCondition =
          outcome(spark, s"SELECT make_date(2021, i, 1) AS v FROM $extremesTable")
        otherCondition match {
          case r: Raised => assert(r.condition.startsWith("DATETIME_FIELD_OUT_OF_BOUNDS"), r)
          case other => fail(s"expected make_date to raise on the extremes row, got $other")
        }
        assert(outcomeDifference(alone, otherCondition).exists(_.startsWith("errors differ")))
      }
    }
  }

  test("the ANSI form fails a kernel planted to answer an overflowing row") {
    // The fault the form exists for, planted in a kernel rather than in the outcomes (VARKA-317):
    // with `checkIntOverflow` off, a checked int add emits as LEGACY and wraps, so the kernel
    // answers the extremes row's `Int.MaxValue + 1` where Spark raises ARITHMETIC_OVERFLOW. The
    // options are laid over the current base, which the option matrix sets, and they are part of
    // the kernel cache's key, so the checked kernel compiled by the row's own test is not reused.
    val q = s"SELECT i + 1 AS v FROM $extremesTable"
    withAnsi(enabled = true) {
      withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false") {
        val previous = VarkaColumnarToRowExec.currentEmitOptions
        if (previous.checkIntOverflow()) {
          // The shipped kernel declines the batch and the row engine raises: one error.
          assert(checkSparkRaisesTheSame[SparkArithmeticException](q).condition ===
            "ARITHMETIC_OVERFLOW")
        }
        VarkaColumnarToRowExec.setEmitOptionsForTesting(previous.withCheckIntOverflow(false))
        try {
          // The plant took: a kernel served the whole query and answered the wrapped value. An
          // inert plant - a reused checked kernel, a declined batch - fails here, not as a catch.
          val (planted, plan) = runOutcome(varkaSpark, q)
          assert(plan.exists(kernelServed), plan.map(_.treeString).getOrElse("(no plan)"))
          planted match {
            case Answered(rows) => assert(rows.exists(r => !r.isNullAt(0) &&
              r.getInt(0) == Int.MinValue), s"expected the wrapped Int.MinValue in $rows")
            case other => fail(s"expected the planted kernel to answer, got $other")
          }
          val e = intercept[TestFailedException](checkSparkAnswerMaybeThrows(q))
          assert(e.getMessage.contains("only Spark raises"), e.getMessage)
          assert(e.getMessage.contains("ARITHMETIC_OVERFLOW"), e.getMessage)
        } finally {
          VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The domains (VARKA-279)
  // ---------------------------------------------------------------------------------------------

  /**
   * The fixture the guarded rows' partitions are cut from: for every guard the table's rows carry,
   * its bound and one past it on both sides, in the column the guard reads. `i` at the int
   * extremes and one in from each, at the day-interval limit, at the month range and at the
   * months of `make_date`; `d` of year 9999 beside the `i` that takes `year(d) + i` to the int's
   * top and one past, and `d` at the column bound's ends and one past each (VARKA-379); `ym` at
   * the month range on both signs, at the one count whose negation overflows and one in from
   * it, and at the counts whose difference with `ymm` leaves the int, `ymm` at its most negative;
   * a time at each end of the day. Then rows null in those columns, one column group at a time
   * and all at once, which the `IS NULL` partitions select. The other columns hold the mixed
   * fixture's first row. One partition, so a partition view's rows are one batch at the default
   * batch size.
   */
  private val edges: (String, String) = {
    def row(d: String = "DATE'2024-01-31'", i: String = "3", m: String = "0", y: String = "0",
        mm: String = "0", t: String = "TIME'12:34:56.789'", t2: String = "TIME'00:00:00'"): String =
      s"($d, DATE'2024-02-29', $i, $m, $y, $mm, CAST('5000000000' AS BIGINT), " +
        s"CAST('1' AS BIGINT), $t, $t2, INTERVAL '1 02:03:04.5' DAY TO SECOND, " +
        "INTERVAL '0 00:00:00' DAY TO SECOND)"
    // A row whose `ym` is `months`, through the view's `make_ym_interval(y, mm)`; Java's
    // truncating division and remainder agree in sign, so the two parts add back to the count.
    def ymRow(months: Long, m: String = "0"): String =
      row(m = m, y = (months / 12).toString, mm = (months % 12).toString)
    def around(lo: Long, hi: Long): Seq[Long] = Seq(lo - 1, lo, hi, hi + 1)
    val dayLimit = VarkaChrono.INTERVAL_DAY_LIMIT_DAYS.toLong
    val minMonths = VarkaChrono.MONTH_ARITH_MIN_MONTHS.toLong
    val maxMonths = VarkaChrono.MONTH_ARITH_MAX_MONTHS.toLong
    val ints = Seq(Int.MinValue.toLong, Int.MinValue + 1L, Int.MaxValue - 1L,
      Int.MaxValue.toLong) ++ around(-dayLimit, dayLimit) ++ around(minMonths, maxMonths) ++
      around(1, 12)
    val rows = ints.map(i => row(i = i.toString)) ++
      Seq(Int.MaxValue - 9999L, Int.MaxValue - 9998L).map(i =>
        row(d = "DATE'9999-12-31'", i = i.toString)) ++
      around(VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS).map(v =>
        row(d = s"date_from_unix_date($v)")) ++
      (around(minMonths, maxMonths) ++ around(-maxMonths, -minMonths)).map(ymRow(_)) ++
      Seq(ymRow(Int.MinValue), ymRow(Int.MinValue, m = "1"), ymRow(Int.MinValue + 1L),
        ymRow(Int.MaxValue), ymRow(Int.MaxValue, m = "-1"), row(m = Int.MinValue.toString),
        row(t = "TIME'00:00:00'", t2 = "TIME'23:59:59.999999'"),
        row(t = "TIME'23:59:59.999999'", t2 = "TIME'00:00:00.000001'"),
        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
        row(i = "NULL"), row(d = "NULL"), row(m = "NULL", y = "NULL", mm = "NULL"),
        row(t = "NULL", t2 = "NULL"))
    "varka_coverage_edges" -> VarkaCoverageDifferentialSuite.fixtureView(rows)
  }

  /** The guarded rows, those with a domain, each with the index its partition views carry. */
  private lazy val guardedRows: Seq[(CoverageRow, Int)] =
    rows.filter(_.domain.isDefined).zipWithIndex

  /** The three partitions of a domain `d`, as TLP cuts a table: true, false and unknown. */
  private val partitions: Seq[(String, String => String)] = Seq(
    "inside" -> identity[String], "outside" -> (d => s"NOT ($d)"), "null" -> (d => s"($d) IS NULL"))

  private def partitionView(k: Int, partition: String): String =
    s"varka_coverage_edges_${k}_$partition"

  /**
   * The edges fixture and, per guarded row, its three partitions as cached views of their own:
   * the partition is Spark's, evaluated once when the view is built, and a kernel then sees
   * batches that lie wholly inside the domain, wholly outside, or wholly unknown. Each is built
   * here rather than on first use, because a view built lazily inside a test's JFR recording
   * would record its own filter kernel's events beside the query's.
   */
  private def cacheEdges(session: SparkSession): Unit = {
    val started = System.nanoTime()
    session.sql(edges._2).coalesce(1).createOrReplaceTempView(edges._1)
    session.catalog.cacheTable(edges._1)
    for ((row, k) <- guardedRows; (partition, where) <- partitions) {
      val view = partitionView(k, partition)
      session.sql(s"SELECT * FROM ${edges._1} WHERE ${where(row.domain.get)}").coalesce(1)
        .createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
      session.table(view).count()
    }
    logInfo(s"cached the edges fixture and ${guardedRows.size * partitions.size} partition " +
      s"views in ${(System.nanoTime() - started) / 1000000} ms")
  }

  /**
   * Rows whose outside partition is empty by construction - the guard's bound is the column
   * type's own range, or the guarded value cannot reach the bound from any value of the column -
   * with the reason. Checked both ways: an unlisted row with no row outside fails, so the check
   * cannot pass on a partition that selects nothing, and a listed row with rows outside fails as
   * stale.
   */
  private val totalDomains: Map[String, String] = Map(
    "extract(YEAR FROM ym) - 1" ->
      "the quotient is within +/-178956970, so the checked subtract cannot overflow",
    "time_from_millis(time_to_millis(t2))" ->
      "a TIME's millisecond count is inside the day by construction",
    "time_from_micros(time_to_micros(t))" ->
      "a TIME's microsecond count is inside the day by construction",
    "t + INTERVAL '0 00:00:00' DAY TO SECOND" ->
      "a zero interval leaves the sum where the time was, inside the day",
    "CAST(time_to_millis(t) AS INT)" ->
      "a day's milliseconds, under 86400000, always fit an int",
    "CAST(time_to_millis(t) AS INT) > i" ->
      "a day's milliseconds, under 86400000, always fit an int",
    "hour(t) + 1" -> "an hour is at most 23, so the checked add cannot overflow",
    "d2 - d" -> ("the fixture's dates are inside the calendar, under three million days apart, " +
      "far inside the 106751991 days the multiply allows; VarkaDateMinusDateSuite takes the " +
      "dates past it"))

  /** The statuses as the fallback accounting names them. */
  private def describe(statuses: Seq[Int]): String =
    if (statuses.isEmpty) "nothing"
    else statuses.map(VarkaFallbackAccounting.describeStatus).mkString("; ")

  /** A verdict in the accounting's words: the bits a batch must decline with, and may. */
  private def render(v: Verdict): String =
    if (v.answers) "answer"
    else describe(Seq(v.must)) + (if (v.may != 0) s" (may ${describe(Seq(v.may))})" else "")

  /**
   * Whether `statuses`, the declined batches' statuses in the order the batches ran, are what
   * `verdicts`, in batch order, call for: a batch that must decline takes the next status, which
   * its verdict must admit; one that may decline takes the next status only where that status is
   * one it admits; one that must answer takes none. Where the query raised, the task stopped in
   * the row path of the batch whose status is the last, and the batches after it never ran. The
   * may case is greedy: a may-only batch ahead of a must batch whose status it also admits would
   * take that status; no coverage row produces a may-only batch, so nothing decides it yet.
   * After a raise the check stops at the last status, so a raising batch that committed no event
   * is not told apart from an earlier batch that raised.
   */
  private def statusesAgree(
      verdicts: Seq[Verdict], statuses: Seq[Int], raised: Boolean): Boolean = {
    var next = 0
    var agree = true
    val remaining = verdicts.iterator
    while (agree && remaining.hasNext && !(raised && statuses.nonEmpty && next == statuses.size)) {
      val v = remaining.next()
      if (v.must != 0) {
        if (next < statuses.size && v.admits(statuses(next))) next += 1 else agree = false
      } else if (v.may != 0 && next < statuses.size && v.admits(statuses(next))) {
        next += 1
      }
    }
    agree && next == statuses.size
  }

  /**
   * The verdict on each batch of `view` for the kernel `node` runs. The node's own expressions
   * are compiled as the node compiles them - the optimized form, against the scan's pruned
   * output, under the emit options its session bound - and the view's rows are read as the kernel
   * reads them, epoch days, month counts and nanoseconds, in the cached order, cut into batches
   * of the session's Arrow batch size. The evaluator's per-batch input bound is applied first, as
   * the runner applies it: a live lane outside it declines the batch with `STATUS_INPUT_BOUND`
   * and the kernel's guards never run.
   */
  private def verdictsOf(node: SparkPlan, view: String): Seq[Verdict] = {
    val (expressions, child, sessionEmit, predicate) = node match {
      case p: VarkaColumnarToRowExec => (p.projectList, p.child, p.sessionEmit, false)
      case p: VarkaProjectExec => (p.projectList, p.child, p.sessionEmit, false)
      case f: VarkaFilterColumnarToRowExec => (Seq(f.condition), f.child, f.sessionEmit, true)
      case f: VarkaFilterExec => (Seq(f.condition), f.child, f.sessionEmit, true)
      case other => fail(s"not a Varka node: $other")
    }
    val options = VarkaColumnarToRowExec.emitOptions(sessionEmit)
    val kernel: CompiledVarkaProjection = if (predicate) {
      VarkaExpressionCompiler.compilePredicate(expressions.head, child.output, options).toScala
        .getOrElse(fail(s"the condition does not compile: ${expressions.head}")).fused
    } else {
      val list = expressions.map(_.asInstanceOf[NamedExpression])
      val partial = VarkaExpressionCompiler.compilePartial(list, child.output, options).toScala
        .getOrElse(fail(s"the projection does not compile: $list"))
      assert(partial.more.isEmpty && partial.declines.isEmpty,
        s"a verdict is per kernel, and this projection has several or a residual: $partial")
      partial.fused
    }
    assert(kernel.derivedInputs.isEmpty,
      "a derived input is the row engine's own map of a column, which this verdict does not model")
    val long = kernel.lane == LaneType.LONG
    val guards = new VarkaGuardSpec.Guards(kernel.outputs.asScala.toSeq,
      kernel.inputOrdinals.size, kernel.numLiterals, options, long)
    val lits: Array[Long] =
      if (long) kernel.longLiterals.asScala.map(_.longValue).toArray
      else kernel.literals.asScala.map(_.longValue).toArray
    // A kernel of both lanes (VARKA-28) reads the int table beside the long one.
    val intLits: Array[Int] =
      if (long) kernel.literals.asScala.map(_.intValue).toArray else Array.empty[Int]
    val ordinals = kernel.inputOrdinals.asScala.map(_.intValue).toSeq
    val types = child.output.map(_.dataType)
    val names = child.output.map(a => s"`${a.name}`").mkString(", ")
    val values = spark.sql(s"SELECT $names FROM $view").queryExecution.executedPlan
      .executeCollect().toSeq.map { r =>
        ordinals.map { o =>
          if (r.isNullAt(o)) None else Some(types(o) match {
            case DateType | IntegerType | _: YearMonthIntervalType => r.getInt(o).toLong
            case LongType | _: TimeType | _: DayTimeIntervalType => r.getLong(o)
            case other => fail(s"no lane reads a $other input")
          })
        }
      }
    val batchSize = varkaSpark.sessionState.conf.arrowMaxRecordsPerBatch
    values.grouped(if (batchSize > 0) batchSize else math.max(values.size, 1)).toSeq.map { batch =>
      val outside = kernel.inputBounds.asScala.exists(b =>
        batch.exists(_(b.inputIndex).exists(v => v < b.lo || v > b.hi)))
      if (outside) Verdict(VarkaKernelRunner.STATUS_INPUT_BOUND, 0)
      else VarkaGuardSpec.verdict(guards, lits, intLits, batch, Nil)
    }
  }

  /** What a row's domain check found, and what it saw, for the test's report. */
  private case class DomainResult(
      findings: Seq[String], declined: Seq[String], answered: Seq[String], mayBatches: Int)

  /**
   * The domain check for one guarded row: its three partitions, each as a projection of the
   * expression and as a filter comparing it with a column of its type (a predicate row as the
   * filter it is). Per query, Varka's outcome against Spark's, rows or the same error; the
   * declined batches' statuses, off the JFR fallback events the evaluator commits before the row
   * path runs - which a failed task's SQL metrics never report - against the verdict on each
   * batch, exact under `exactRefusalStatus`; and, where every batch must be answered, the
   * kernels serving the whole query. And first, on Spark, that the three partitions cut the
   * fixture and that each has rows, the outside one unless the domain is total.
   */
  private def domainCheck(row: CoverageRow, k: Int): DomainResult = {
    val e = row.executable
    val forms: Seq[(String, String => String)] = row.form match {
      case "predicate" => Seq("a filter" -> (v => s"SELECT rid FROM $v WHERE $e"))
      case _ =>
        val t = spark.sql(s"SELECT $e AS v FROM ${edges._1}").schema.head.dataType
        val c = standIn(t, caseArm = false).getOrElse(fail(s"no column of type $t beside $e"))
        Seq("a projection" -> (v => s"SELECT rid, $e AS v FROM $v"),
          "a filter" -> (v => s"SELECT rid FROM $v WHERE ($e) = $c"))
    }
    val findings = ArrayBuffer.empty[String]
    val declined = ArrayBuffer.empty[String]
    val answered = ArrayBuffer.empty[String]
    var mayBatches = 0
    def ids(table: String): Seq[Int] =
      spark.sql(s"SELECT rid FROM $table").collect().map(_.getInt(0)).toSeq
    val cut = partitions.map { case (p, _) => ids(partitionView(k, p)) }
    val all = ids(edges._1)
    if (cut.flatten.sorted != all.sorted) {
      findings += s"the partitions do not cut the fixture: ${cut.map(_.size)} of ${all.size} rows"
    }
    val Seq(inside, outside, unknown) = cut
    if (inside.isEmpty) findings += "no row inside the domain"
    if (unknown.isEmpty) findings += "no row where the domain is unknown"
    (totalDomains.get(row.sql), outside.isEmpty) match {
      case (None, true) =>
        findings += "no row outside the domain, and the row is not listed as a total domain"
      case (Some(reason), false) =>
        findings += s"rows outside the domain (rid ${outside.sorted.mkString(", ")}), but the " +
          s"row is listed as a total domain: $reason"
      case _ =>
    }
    // An empty partition - a total domain's outside - has no batch for a kernel to serve or
    // decline, so nothing runs over it; its emptiness is what the list above polices.
    for (((partition, _), rids) <- partitions.zip(cut) if rids.nonEmpty; (form, query) <- forms) {
      val view = partitionView(k, partition)
      val q = query(view)
      val where = s"$partition the domain, in $form"
      val expected = outcome(spark, q)
      val ((actual, plan), events) =
        VarkaJfrTestSupport.withJfrRecording(classOf[VarkaFallbackEvent])(runOutcome(varkaSpark, q))
      val fallbacks = events.filter(VarkaJfrTestSupport.isEvent(_, classOf[VarkaFallbackEvent]))
      val statuses = fallbacks.filter(_.getString("cause") == VarkaFallbackEvent.RANGE_DECLINED)
        .map(_.getInt("status"))
      val otherCauses = fallbacks.map(_.getString("cause"))
        .filterNot(_ == VarkaFallbackEvent.RANGE_DECLINED)
      outcomeDifference(expected, actual).foreach(d => findings += s"$where: $q\n$d")
      if (otherCauses.nonEmpty) findings += s"$where: fell back for ${otherCauses.mkString(", ")}"
      plan.flatMap(p => collectFirst(p) { case v if isVarkaNode(v) => v }) match {
        case None =>
          findings += s"$where: no Varka node:\n${plan.map(_.treeString).getOrElse("(no plan)")}"
        case Some(node) =>
          val verdicts = verdictsOf(node, view)
          val musts = verdicts.map(_.must).filter(_ != 0)
          mayBatches += verdicts.count(_.may != 0)
          val raised = actual.isInstanceOf[Raised]
          // The domain itself is what is held here: no batch inside it or under IS NULL may be
          // one the verdict condemns, and every batch outside it must be, so a domain that is
          // too wide or too narrow disagrees with the guards' own verdict.
          if (partition != "outside" && (musts.nonEmpty || verdicts.exists(_.may != 0))) {
            findings += s"$where: the domain admits batches the verdict condemns: " +
              verdicts.map(render).mkString("; ")
          }
          if (partition == "outside" && !verdicts.forall(_.must != 0)) {
            findings += s"$where: the domain excludes batches the verdict answers: " +
              verdicts.map(render).mkString("; ")
          }
          if (!statusesAgree(verdicts, statuses, raised)) {
            findings += s"$where: declined with ${describe(statuses)} where the verdicts, " +
              s"batch by batch, are ${verdicts.map(render).mkString("; ")}" +
              (if (raised) ", and the row path raised" else "")
          }
          val metric = (name: String) => node.metrics.get(name).map(_.value).getOrElse(-1L)
          (actual, musts.size) match {
            case (Answered(_), 0) =>
              if (!kernelServed(plan.get) || metric("numFallbackBatchesDeclined") != 0) {
                findings += s"$where: not served by a kernel, or a batch declined:\n" +
                  plan.get.treeString
              }
            case (Answered(_), d) =>
              if (metric("numVarkaBatches") != verdicts.size - d ||
                  metric("numFallbackBatchesDeclined") != d) {
                findings += s"$where: ${metric("numVarkaBatches")} batches served and " +
                  s"${metric("numFallbackBatchesDeclined")} declined, for ${verdicts.size} " +
                  s"batches of which $d must decline"
              }
            case (r: Raised, 0) =>
              findings += s"$where: raised $r where the verdict says every batch is answered"
            case _ =>
          }
          if (musts.isEmpty) answered += where else declined += s"$where (${describe(musts)})"
      }
    }
    DomainResult(findings.toSeq, declined.toSeq, answered.toSeq, mayBatches)
  }

  guardedRows.foreach { case (row, k) =>
    test(s"${row.family}: ${row.sql} is answered inside its domain and where it is unknown, " +
        "and declined outside with the verdict's status, in a projection and a filter") {
      // The domain is ANSI's where the modes differ, and a batch's status names exactly the
      // guards that fired only under exact refusal statuses; pruning off, since a cached view's
      // statistics could drop a batch before any kernel saw it.
      val result = withAnsi(enabled = true) {
        withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false") {
          withBothConf(SQLConf.VARKA_EMIT_EXACT_REFUSAL_STATUS.key, "true")(domainCheck(row, k))
        }
      }
      info(s"domain: ${row.domain.get}")
      info(s"answered: ${result.answered.mkString(", ")}")
      info("declined: " +
        (if (result.declined.isEmpty) "nothing" else result.declined.mkString(", ")))
      info(s"batches whose verdict leaves a guard free to fire: ${result.mayBatches}")
      assert(result.findings.isEmpty, result.findings.mkString("\n\n"))
    }
  }

  test("the total domains are the guarded rows the fixture cannot take outside, and no other") {
    // The list above is read only by guarded rows' tests, so an entry for a row the table lacks,
    // or for an unguarded row, could never go stale.
    val unread = totalDomains.keys.filterNot(sql => guardedRows.exists(_._1.sql == sql))
    assert(unread.isEmpty, s"total domains no guarded row's test reads: $unread")
  }

  test("date_add(d, i) carries no guard: at the int extremes its day wraps as the row " +
      "engine's does") {
    // The row's note once promised "a per-batch bound on its range" that its kernel does not
    // carry, the open question of VARKA-279's admission check: the compiler records a bound only
    // for the day-interval cast, and Spark's DateAdd is a plain int add. So the row has no domain,
    // and this holds it to that over the edges fixture, `i` at both int extremes and `d` of year
    // 9999 among its rows: the kernels serve every batch, nothing falls back, and the answer -
    // the wrapped day, outside any calendar - is the row engine's.
    assert(rows.find(_.sql == "date_add(d, i)").exists(_.domain.isEmpty))
    val q = s"SELECT rid, date_add(d, i) AS v FROM ${edges._1}"
    val ((actual, plan), events) =
      withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false") {
        VarkaJfrTestSupport.withJfrRecording(classOf[VarkaFallbackEvent])(runOutcome(varkaSpark, q))
      }
    assert(outcomeDifference(outcome(spark, q), actual).isEmpty)
    assert(!events.exists(VarkaJfrTestSupport.isEvent(_, classOf[VarkaFallbackEvent])))
    assert(plan.exists(kernelServed), plan.map(_.treeString).getOrElse("(no plan)"))
    val days = spark.sql(q).queryExecution.executedPlan.executeCollect()
      .filterNot(_.isNullAt(1)).map(_.getInt(1))
    val (first, last) = (java.time.LocalDate.of(1, 1, 1).toEpochDay,
      java.time.LocalDate.of(9999, 12, 31).toEpochDay)
    assert(days.exists(d => d < first || d > last), "no row wrapped past the calendar")
  }
}

private object VarkaCoverageDifferentialSuite {

  /**
   * A fixture's view over `values`, rows of the twelve source columns in parentheses, each
   * given its position as an int `rid` that no coverage row names; `where` filters the rows.
   */
  def fixtureView(values: Seq[String], where: String = ""): String = {
    val withIds = values.zipWithIndex.map { case (v, rid) => s"($rid, ${v.stripPrefix("(")}" }
    s"""SELECT rid, d, d2, i,
       |       CAST(m AS INTERVAL MONTH) AS ymm,
       |       CAST(y AS INTERVAL YEAR) AS ymy,
       |       make_ym_interval(y, mm) AS ym,
       |       l, l2, CAST(t AS TIME(6)) AS t, CAST(t2 AS TIME(6)) AS t2, dt, dt2
       |FROM VALUES ${withIds.mkString(",\n  ")}
       |AS v(rid, d, d2, i, m, y, mm, l, l2, t, t2, dt, dt2)$where""".stripMargin
  }
}
