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

import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.exceptions.TestFailedException

import org.apache.spark.SparkArithmeticException
import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.catalyst.util.DateTimeConstants
import org.apache.spark.sql.execution.VarkaMaybeThrows.{difference => outcomeDifference, Answered, Raised}
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.internal.SQLConf

/**
 * Every row of the published coverage table, run through both engines (VARKA-120).
 *
 * `VarkaCoverageSuite` proves each row of `sql/varka/coverage.json` compiles to a fused kernel
 * and that the table names every expression the compiler admits. This suite and
 * `VarkaCoverageHarnessSuite` prove the kernel behind each row answers what the row engine
 * answers. Both read the committed JSON and nothing else, so a row added to the coverage suite
 * and regenerated into the file is run without a change to either: adding an arm without a row
 * fails the coverage suite, adding a row without correctness fails these, and together they are
 * the guarantee the table makes.
 *
 * The fixtures cover the table's sixteen source columns, plus the derived BOOLEAN columns `b`
 * and `b2` - nulls sprinkled per column, with a narrow pair null on the left only and another on
 * the right only, no nulls, and every column null in every row. Every row's answer under both
 * consumers a Varka node can have - rows, the plan every direct query gets, and columns, which
 * the Arrow cache builder takes when a view over the query is cached - runs below the session,
 * in `VarkaCoverageHarnessSuite` (VARKA-509), with the stronger forms over the rows: the
 * partition oracle of every predicate row (VARKA-138), the ANSI form, where each row runs where
 * Spark evaluates it and where it skips it over a fixture of the types' extremes, and the
 * domains of the rows whose kernel can decline a batch (VARKA-279). This suite keeps the answer
 * form for the first row of each family, the plumbing only a session has: the columnar rule's
 * plan, the cache and the two nodes; and the error comparison a user meets over a session,
 * [[VarkaMaybeThrows]]'s (VARKA-317): two tests of the comparison itself, and one row through it
 * for each error condition the table's rows raise. The hand-chosen shapes in
 * `VarkaDifferentialSuite` (extreme offsets, overflow, cache conversion) stay there;
 * this is the floor under them.
 */
class VarkaCoverageDifferentialSuite extends QueryTest with VarkaMaybeThrows
  with VarkaTestWatchdog {

  /** One row of the table: what it prints, what runs, and which form it takes. */
  private case class CoverageRow(sql: String, executable: String, family: String, form: String)

  private lazy val rows: Seq[CoverageRow] = {
    val file = getWorkspaceFilePath("sql", "varka", "coverage.json").toFile
    val doc = new ObjectMapper().readTree(file)
    doc.get("expressions").elements().asScala.toSeq.map { e =>
      CoverageRow(e.get("sql").asText(), e.get("executable").asText(),
        e.get("family").asText(), e.get("form").asText())
    }
  }

  /** The three answer fixtures, shared with the harness's suite (VARKA-509). */
  private val fixtures: Seq[(String, String)] = VarkaCoverageDifferentialSuite.fixtures

  /** The ANSI form's fixture, shared with the harness's suite (VARKA-509). */
  private val extremes: (String, String) = VarkaCoverageDifferentialSuite.extremes

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
    val actual = varkaSpark.sql(query)
    val expected = expectedSql(query, actual)
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
        val served = varkaSpark.table(view)
        checkAnswer(served, expectedOf(spark.table(view), served, view))
      }
    } finally {
      for (session <- Seq(spark, varkaSpark)) session.catalog.uncacheTable(view)
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cacheFixtures(spark)
    cacheFixtures(varkaSpark)
  }

  test("the coverage table has rows to run") {
    assert(rows.size >= 57, s"only ${rows.size} rows read from sql/varka/coverage.json")
    assert(rows.forall(_.executable.nonEmpty))
  }

  /**
   * The first row of each family, which this suite still runs through a session under both
   * consumers: the end-to-end layer over the harness (VARKA-509), whose
   * `VarkaCoverageHarnessSuite` holds every row's answers to Spark's below the session and so
   * leaves this suite the plumbing only a session has - the columnar rule's plan, the cache, the
   * row and the columnar nodes.
   */
  private lazy val sampleRows: Seq[CoverageRow] =
    rows.filter(r => rows.find(_.family == r.family).contains(r))

  // varka-session-lint: allow one row a family through both nodes and the cache; every row
  // is answered in VarkaCoverageHarnessSuite
  sampleRows.foreach { row =>
    test(s"${row.family}: ${row.sql} matches the row engine under both consumers") {
      withFloatingPruningOff(row) {
        fixtures.foreach { case (fixture, _) =>
          checkRowConsumer(row, fixture)
          checkColumnarConsumer(row, fixture)
        }
      }
    }
  }

  /** A reference to a floating column of the fixtures: `x`, `x2`, `f`, `f2`, `xrid`, `frid`. */
  private val floatingColumn = """\b(x|x2|f|f2|xrid|frid)\b""".r

  /**
   * Runs `body` with both sessions reading the cached fixtures without the batch pruning of
   * their column statistics when `row` reads a floating column. The statistics leave NaN out
   * (SPARK-58482): a batch whose only value is NaN records an empty range, so a filter a NaN
   * row passes, `x IN (..., NaN)`, prunes that batch, and at one row a batch
   * (`arrowBatchSize=1`) the kernel never sees the row the test is about. The expected side
   * may also plan over the uncached rows, which prunes nothing, so the two answers would
   * differ on a fact about the cache, not about either engine. The wrap is not a workaround
   * for a lost cache: with both sides cached they would agree on the pruned answer and leave
   * the NaN row untested.
   */
  private def withFloatingPruningOff[T](row: CoverageRow)(body: => T): T =
    if (floatingColumn.findFirstIn(row.executable).isEmpty) body
    else withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false")(body)

  // The partition oracle (VARKA-138) and the null tests as CASE conditions run below the
  // session, in VarkaCoverageHarnessSuite and VarkaPartitionOracle (VARKA-509).

  // ---------------------------------------------------------------------------------------------
  // The ANSI form (VARKA-138)
  // ---------------------------------------------------------------------------------------------

  private val extremesTable = extremes._1

  // Each row's ANSI form - where Spark evaluates it and where it skips it, and the reach check
  // of the skipped positions with its declined list - runs below the session, in
  // VarkaCoverageHarnessSuite and VarkaAnsiForm (VARKA-509). The tests below keep the error
  // comparison a user meets, VarkaMaybeThrows' over a session: two of the comparison itself, and
  // one row a condition.

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

  /**
   * One row a condition: each error condition a coverage row raises on the extremes fixture, by
   * the first row that raises it, the list `VarkaCoverageHarnessSuite` holds to the table. Each
   * runs through both sessions, so the error a user meets - condition, SQLSTATE, parameters,
   * message and exception class - is Spark's for every condition, and a Varka node must be in
   * the plan, or the row engine alone would pass it.
   */
  // varka-session-lint: allow one row an error condition, the error as a user meets it
  VarkaAnsiForm.RAISES.asScala.toSeq.sortBy(_._1).foreach { case (condition, sql) =>
    test(s"$condition reaches the user as the row engine raises it, from $sql") {
      val row = rows.find(_.sql == sql).getOrElse(fail(s"no coverage row $sql"))
      val q = s"SELECT ${row.executable} AS v FROM $extremesTable"
      withAnsi(enabled = true) {
        withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false") {
          val (_, plan) = runOutcome(varkaSpark, q)
          assert(plan.exists(p => find(p)(isVarkaNode).isDefined),
            plan.map(_.treeString).getOrElse("(no plan)"))
          checkSparkAnswerMaybeThrows(q) match {
            case r: Raised => assert(r.condition === condition, r)
            case other => fail(s"expected $condition, got $other")
          }
        }
      }
    }
  }

  // The domains (VARKA-279), and date_add(d, i)'s lack of one, run below the session, in
  // VarkaCoverageHarnessSuite and VarkaDomains (VARKA-509).
}

private object VarkaCoverageDifferentialSuite {

  /**
   * The ANSI form's fixture: one row at the extremes of every column's type beside a second row
   * that carries the narrow columns' other bound, so the byte and short arithmetic has a
   * raising row for each of its two directions. Under ANSI, the extremes make the rows that can
   * overflow or leave a range raise, so the form can tell a skipped position from an evaluated
   * one. Column `i` at `Int.MaxValue` is not
   * a valid month, so `make_date(2021, i, 1)` raises too, which is a fair cell: both engines
   * must raise it alone and neither where it is skipped.
   *
   * The two rows hold no null, so `coalesce(<column>, e)` never needs `e` on Spark. A third row
   * of nulls is filtered out again by `rid`, which keeps every column nullable: over a column
   * Spark knows to be non-nullable the optimizer would fold that COALESCE to the column, and
   * Varka would never see `e` (the dense fixture's trick above).
   */
  lazy val extremes: (String, String) = {
    val rows = Seq(
      "(DATE'9999-12-31', DATE'0001-01-01', 2147483647, -2147483648, 178956970, 7, " +
        "CAST('9223372036854775807' AS BIGINT), CAST('-9223372036854775808' AS BIGINT), " +
        "TIME'23:59:59.999999', TIME'00:00:00', " +
        "INTERVAL '106751991 04:00:54.775807' DAY TO SECOND, " +
        "INTERVAL '-106751991 04:00:54.775808' DAY TO SECOND, " +
        "CAST(-128 AS TINYINT), CAST(-128 AS TINYINT), " +
        "CAST(-32768 AS SMALLINT), CAST(-32768 AS SMALLINT))",
      "(DATE'2024-02-29', DATE'2024-01-31', 3, 3, 1, 2, CAST('5000000000' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'12:34:56.789', TIME'00:00:00', " +
        "INTERVAL '1 02:03:04.5' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "CAST(127 AS TINYINT), CAST(-128 AS TINYINT), " +
        "CAST(32767 AS SMALLINT), CAST(-32768 AS SMALLINT))",
      "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, " +
        "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
    // `lrid` is `rid` as a BIGINT, the guard of a long-lane expression: one kernel holds one
    // lane, so `CASE WHEN rid < 0 THEN greatest(l, l2) ...` mixes the int and long lanes and is
    // never fused, where `CASE WHEN lrid < 0 ...` is.
    val view = fixtureView(rows, "\nWHERE rid < 2")
    // `xrid` and `frid` are its DOUBLE and FLOAT twins (VARKA-341): a floating kernel holds no
    // integral value until a mask crosses between the kinds.
    "varka_coverage_extremes" -> (s"SELECT *, CAST(rid AS BIGINT) AS lrid, " +
      s"CAST(rid AS DOUBLE) AS xrid, CAST(rid AS FLOAT) AS frid FROM ($view)")
  }

  /**
   * The three fixtures, each a view with the table's columns: `d`, `d2` (dates), `i` (int),
   * `ymm`, `ymy`, `ym` (the three year-month interval units), the long lane's `l`, `l2`
   * (bigint, across its range), `t`, `t2` (`TIME(6)`) and `dt`, `dt2` (day-time intervals of
   * both signs), and the narrow lanes' `y1`, `y2` (`TINYINT`) and `s1`, `s2` (`SMALLINT`) -
   * built through SQL because an interval column has no plain Scala literal. The
   * dates are the ones the other fixtures use
   * - leap days, a 31st, 1969-12-31, the dates the table's `IN` rows name, 2021 dates for its
   * `year(d) = 2021` rows, a far date inside the contract - `d2` is shifted against `d` or
   * equal to it, `i` stays in 1..12 (inside the day-offset guard, and a valid month for
   * `make_date(2021, i, 1)`), and the month counts stay inside the emitter's guard. The narrow
   * pairs stay inside their type under `+`, `-`, `*` and the negation: the form that compares
   * the two engines runs under the session's ANSI mode, where a pair that leaves the type is an
   * error on the row engine rather than an answer to compare (the narrow rows' domains are cut
   * on `VarkaDomains`' edges fixture, which holds the values that leave it).
   */
  lazy val fixtures: Seq[(String, String)] = {
    // `i` stays in 1..12: it doubles as the month of `make_date(2021, i, 1)`, and under the
    // session's default ANSI mode an invalid month is an error on the row engine rather than
    // a null, which is `VarkaDifferentialSuite`'s `varka_date_parts` business, not a shape in
    // the table.
    val dense = Seq(
      "(DATE'2024-01-31', DATE'2024-02-29',  3,   3,  1,  2, CAST('5000000000' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'12:34:56.789', TIME'00:00:00', " +
        "INTERVAL '1 02:03:04.5' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "CAST(3 AS TINYINT), CAST(4 AS TINYINT), " +
        "CAST(18 AS SMALLINT), CAST(-4 AS SMALLINT))",
      "(DATE'2024-02-29', DATE'2024-02-29',  1, -14, -2,  0, CAST('-5000000000' AS BIGINT), " +
        "CAST('-5000000000' AS BIGINT), TIME'00:00:00', TIME'23:59:59.999999', " +
        "INTERVAL '-3 00:00:00.000001' DAY TO SECOND, INTERVAL '0 00:00:01' DAY TO SECOND, " +
        "CAST(-2 AS TINYINT), CAST(6 AS TINYINT), " +
        "CAST(-300 AS SMALLINT), CAST(7 AS SMALLINT))",
      "(DATE'2023-12-27', DATE'2024-01-02', 12,  24,  3,  1, " +
        "CAST('9223372036854775807' AS BIGINT), CAST('-9223372036854775808' AS BIGINT), " +
        "TIME'23:59:59.999999', TIME'12:34:56.789', INTERVAL '100000 00:00:00' DAY TO SECOND, " +
        "INTERVAL '-100000 00:00:00' DAY TO SECOND, " +
        "CAST(-11 AS TINYINT), CAST(11 AS TINYINT), " +
        "CAST(-181 AS SMALLINT), CAST(181 AS SMALLINT))",
      "(DATE'2021-01-01', DATE'2020-12-31',  4,   0,  0, 11, CAST('0' AS BIGINT), " +
        "CAST('0' AS BIGINT), TIME'06:00:00', TIME'06:00:00', " +
        "INTERVAL '0 00:00:00' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "CAST(10 AS TINYINT), CAST(-11 AS TINYINT), " +
        "CAST(4 AS SMALLINT), CAST(3 AS SMALLINT))",
      "(DATE'2021-06-01', DATE'2021-06-01',  6,   7,  2,  3, CAST('2147483648' AS BIGINT), " +
        "CAST('2147483647' AS BIGINT), TIME'12:00:00', TIME'12:00:00.000001', " +
        "INTERVAL '7 12:00:00' DAY TO SECOND, INTERVAL '7 12:00:00' DAY TO SECOND, " +
        "CAST(-11 AS TINYINT), CAST(9 AS TINYINT), " +
        "CAST(-181 AS SMALLINT), CAST(3 AS SMALLINT))",
      "(DATE'2021-03-15', DATE'2021-03-14', 11,   1,  1,  5, CAST('-1' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'01:02:03.456', TIME'01:02:03.457', " +
        "INTERVAL '-0 00:00:00.5' DAY TO SECOND, INTERVAL '0 00:00:00.5' DAY TO SECOND, " +
        "CAST(11 AS TINYINT), CAST(11 AS TINYINT), " +
        "CAST(181 AS SMALLINT), CAST(-1 AS SMALLINT))",
      "(DATE'1969-12-31', DATE'1970-01-01',  5,  -1,  0,  0, CAST('42' AS BIGINT), " +
        "CAST('-42' AS BIGINT), TIME'18:00:00', TIME'06:00:00', " +
        "INTERVAL '1 00:00:00' DAY TO SECOND, INTERVAL '-1 00:00:00' DAY TO SECOND, " +
        "CAST(0 AS TINYINT), CAST(0 AS TINYINT), " +
        "CAST(0 AS SMALLINT), CAST(0 AS SMALLINT))",
      "(DATE'2000-02-29', DATE'1999-12-31',  2,  12,  4,  6, CAST('-2147483649' AS BIGINT), " +
        "CAST('-2147483648' AS BIGINT), TIME'09:30:00', TIME'09:29:59.999', " +
        "INTERVAL '0 00:00:00.000001' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "CAST(5 AS TINYINT), CAST(-6 AS TINYINT), " +
        "CAST(5 AS SMALLINT), CAST(-7 AS SMALLINT))",
      "(DATE'9999-12-01', DATE'0001-01-15',  7,   5,  1,  1, CAST('12345678901234' AS BIGINT), " +
        "CAST('12345678901234' AS BIGINT), TIME'12:34:56.789', TIME'12:34:56.789', " +
        "INTERVAL '2 00:00:00' DAY TO SECOND, INTERVAL '2 00:00:00' DAY TO SECOND, " +
        "CAST(-1 AS TINYINT), CAST(1 AS TINYINT), " +
        "CAST(-1 AS SMALLINT), CAST(1 AS SMALLINT))",
      "(DATE'2021-11-01', DATE'2021-11-01',  9, 100,  2,  4, CAST('7' AS BIGINT), " +
        "CAST('11' AS BIGINT), TIME'00:00:00.000001', TIME'00:00:00', " +
        "INTERVAL '0 00:01:00' DAY TO SECOND, INTERVAL '0 00:00:59' DAY TO SECOND, " +
        "CAST(9 AS TINYINT), CAST(7 AS TINYINT), " +
        "CAST(8191 AS SMALLINT), CAST(2 AS SMALLINT))")
    val allNullRow =
      "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, " +
        "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
    val mixed = Seq(
      "(DATE'2024-01-31', DATE'2024-02-29',  3,   3,  1,  2, CAST('5000000000' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'12:34:56.789', TIME'00:00:00', " +
        "INTERVAL '1 02:03:04.5' DAY TO SECOND, INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "CAST(3 AS TINYINT), CAST(4 AS TINYINT), " +
        "CAST(18 AS SMALLINT), CAST(-4 AS SMALLINT))",
      "(NULL,             DATE'2024-02-29',  1, -14, -2,  0, NULL, " +
        "CAST('-5000000000' AS BIGINT), TIME'00:00:00', TIME'23:59:59.999999', " +
        "INTERVAL '-3 00:00:00.000001' DAY TO SECOND, INTERVAL '0 00:00:01' DAY TO SECOND, " +
        "NULL, CAST(-61 AS TINYINT), " +
        "NULL, CAST(300 AS SMALLINT))",
      "(DATE'2023-12-27', NULL,             12,  24,  3,  1, " +
        "CAST('9223372036854775807' AS BIGINT), CAST('-9223372036854775808' AS BIGINT), " +
        "TIME'23:59:59.999999', TIME'12:34:56.789', INTERVAL '100000 00:00:00' DAY TO SECOND, " +
        "INTERVAL '-100000 00:00:00' DAY TO SECOND, " +
        "CAST(-11 AS TINYINT), CAST(11 AS TINYINT), " +
        "CAST(-181 AS SMALLINT), CAST(181 AS SMALLINT))",
      "(DATE'2021-01-01', DATE'2020-12-31', NULL, 0,  0, 11, CAST('0' AS BIGINT), " +
        "CAST('0' AS BIGINT), NULL, TIME'06:00:00', INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "CAST(10 AS TINYINT), CAST(-11 AS TINYINT), " +
        "CAST(4 AS SMALLINT), CAST(3 AS SMALLINT))",
      "(DATE'2021-06-01', DATE'2021-06-01',  6, NULL, 2,  3, CAST('2147483648' AS BIGINT), " +
        "CAST('2147483647' AS BIGINT), TIME'12:00:00', TIME'12:00:00.000001', " +
        "INTERVAL '7 12:00:00' DAY TO SECOND, INTERVAL '7 12:00:00' DAY TO SECOND, " +
        "CAST(-11 AS TINYINT), NULL, " +
        "CAST(-181 AS SMALLINT), NULL)",
      "(DATE'2021-03-15', DATE'2021-03-14', 11,   1, NULL, 5, CAST('-1' AS BIGINT), " +
        "CAST('1' AS BIGINT), TIME'01:02:03.456', TIME'01:02:03.457', NULL, " +
        "INTERVAL '0 00:00:00.5' DAY TO SECOND, " +
        "CAST(11 AS TINYINT), CAST(11 AS TINYINT), " +
        "CAST(181 AS SMALLINT), CAST(-1 AS SMALLINT))",
      "(DATE'1969-12-31', DATE'1970-01-01',  5,  -1,  0, NULL, CAST('42' AS BIGINT), " +
        "CAST('-42' AS BIGINT), TIME'18:00:00', TIME'06:00:00', " +
        "INTERVAL '1 00:00:00' DAY TO SECOND, INTERVAL '-1 00:00:00' DAY TO SECOND, " +
        "CAST(0 AS TINYINT), CAST(0 AS TINYINT), " +
        "CAST(0 AS SMALLINT), CAST(0 AS SMALLINT))",
      allNullRow,
      "(DATE'9999-12-01', DATE'0001-01-15',  7,   5,  1,  1, CAST('-2147483649' AS BIGINT), " +
        "NULL, TIME'09:30:00', TIME'09:29:59.999', INTERVAL '0 00:00:00.000001' DAY TO SECOND, " +
        "INTERVAL '0 00:00:00' DAY TO SECOND, " +
        "CAST(5 AS TINYINT), CAST(-6 AS TINYINT), " +
        "CAST(5 AS SMALLINT), CAST(-7 AS SMALLINT))",
      "(DATE'2021-11-01', DATE'2021-11-01',  9, 100,  2,  4, CAST('12345678901234' AS BIGINT), " +
        "CAST('12345678901234' AS BIGINT), TIME'12:34:56.789', TIME'12:34:56.789', " +
        "INTERVAL '2 00:00:00' DAY TO SECOND, INTERVAL '2 00:00:00' DAY TO SECOND, " +
        "CAST(-1 AS TINYINT), CAST(1 AS TINYINT), " +
        "CAST(-1 AS SMALLINT), CAST(1 AS SMALLINT))")
    val allNull = Seq.fill(8)(
      "(CAST(NULL AS DATE), CAST(NULL AS DATE), CAST(NULL AS INT), CAST(NULL AS INT), " +
        "CAST(NULL AS INT), CAST(NULL AS INT), CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), " +
        "CAST(NULL AS TIME(6)), CAST(NULL AS TIME(6)), " +
        "CAST(NULL AS INTERVAL DAY TO SECOND), CAST(NULL AS INTERVAL DAY TO SECOND), " +
        "CAST(NULL AS TINYINT), CAST(NULL AS TINYINT), " +
        "CAST(NULL AS SMALLINT), CAST(NULL AS SMALLINT))")
    // Each row gets its position as `rid`, so the partition test can tell rows apart by id -
    // the all-null fixture's rows are otherwise identical.
    def view(values: Seq[String], where: String = ""): String =
      fixtureView(values, where)
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

  /** The last day whose midnight is a long count of microseconds: the NTZ cast's bound. */
  val lastNtzDay: Long = Long.MaxValue / DateTimeConstants.MICROS_PER_DAY

  /** How many values each floating column picks from: `floatingColumns`' lists. */
  val floatingPicks: Int = 10

  /**
   * The floating columns of a fixture (VARKA-341), `x`, `x2` (`DOUBLE`) and `f`, `f2` (`FLOAT`),
   * as SELECT entries: Spark's special values, each column's picked by the int under `picks`, in
   * `0 until floatingPicks`, and null where that is. Over the same pick the pairs meet NaN
   * against NaN and against negative infinity, a NaN with its sign bit set against the
   * canonical one, both zeros both ways round, the two infinities, an equal pair, the extremes
   * and the smallest subnormal; `1.5`, `-2.5` and `0.0` are literals the coverage rows name.
   * Every ordered pair of the special values, NaN against positive infinity among them, is
   * `VarkaFloatingLaneSuite`'s, and the Spark fuzz picks each column by a different value.
   * Other NaN payloads cannot be written in SQL: `VarkaFloatingLaneSuite` builds them from
   * their bits, and the catalyst fuzzers draw them.
   */
  def floatingColumns(picks: Seq[String]): String = {
    def dbl(v: String): String = v match {
      case "NaN" | "Infinity" | "-Infinity" => s"CAST('$v' AS DOUBLE)"
      case "-NaN" => "-CAST('NaN' AS DOUBLE)"
      case _ => s"${v}D"
    }
    def flt(v: String): String = v match {
      case "NaN" | "Infinity" | "-Infinity" => s"CAST('$v' AS FLOAT)"
      case "-NaN" => "-CAST('NaN' AS FLOAT)"
      case _ => s"CAST(${v}D AS FLOAT)"
    }
    val first = Seq("NaN", "-0.0", "0.0", "Infinity", "-Infinity", "1.5", "-2.5", "-NaN")
    val second = Seq("NaN", "0.0", "-0.0", "-Infinity", "NaN", "1.5", "1.5", "NaN", "-0.0")
    val (dMax, dMin) = ("1.7976931348623157E308", "4.9E-324")
    val (fMax, fMin) = ("3.4028235E38", "1.4E-45")
    val lists = Seq(
      "x" -> (first :+ dMax :+ dMin).map(dbl), "x2" -> (second :+ s"-$dMax").map(dbl),
      "f" -> (first :+ fMax :+ fMin).map(flt), "f2" -> (second :+ s"-$fMax").map(flt))
    lists.zip(picks).map { case ((name, values), pick) =>
      assert(values.size == floatingPicks)
      s"element_at(array(${values.mkString(", ")}), ($pick) + 1) AS $name"
    }.mkString(",\n       ")
  }

  /**
   * A fixture's view over `values`, rows of the sixteen source columns in parentheses, each
   * given its position as an int `rid` that no coverage row names; `where` filters the rows.
   * The TIMESTAMP_NTZ columns are derived: `ntz` is `d` at the time of day `t`, `ntz2` is `d2`
   * at `t2`, so they span the calendar and are null where a source is. A day the conversion
   * cannot take, which only `VarkaDomains`' edges fixture holds, gives a null rather than an
   * error. Both sides read the cached view ([[sameCacheReads]]), so neither evaluates the CASE
   * again: an uncached row engine would inline it, infer no `isnotnull(ntz)`, and raise on a
   * cast the cached plans never evaluate (VARKA-498).
   *
   * The floating columns are derived too (VARKA-341), `floatingColumns` picked by `rid`, null
   * where `d`, `d2`, `i` and `l2` are. The DECIMAL columns are derived from the bigints:
   * `d9` and `d18` from `l`, `d9b` and `d18b` from `l2`.
   */
  def fixtureView(values: Seq[String], where: String = ""): String = {
    val withIds = values.zipWithIndex.map { case (v, rid) => s"($rid, ${v.stripPrefix("(")}" }
    def ntz(day: String, time: String): String =
      s"CASE WHEN abs(unix_date($day)) < $lastNtzDay " +
        s"THEN CAST($day AS TIMESTAMP_NTZ) + ($time - TIME'00:00:00') END"
    val floating = floatingColumns(Seq("d", "d2", "i", "l2").map(c =>
      s"CASE WHEN $c IS NOT NULL THEN rid % $floatingPicks END"))
    // A DECIMAL(p, 2) column (VARKA-347) is the bigint's last p digits as hundredths: an exact
    // product that always fits the type, null where the bigint is, and of both signs.
    def decimal(long: String, precision: Int): String =
      s"CAST(CAST($long % ${BigInt(10).pow(precision)} AS DECIMAL(${precision + 1}, 0)) * 0.01 " +
        s"AS DECIMAL($precision, 2))"
    s"""SELECT rid, d, d2, i,
       |       CAST(m AS INTERVAL MONTH) AS ymm,
       |       CAST(y AS INTERVAL YEAR) AS ymy,
       |       make_ym_interval(y, mm) AS ym,
       |       l, l2, CAST(t AS TIME(6)) AS t, CAST(t2 AS TIME(6)) AS t2, dt, dt2,
       |       y1, y2, s1, s2,
       |       CASE WHEN rid % 4 = 3 THEN NULL ELSE rid % 2 = 0 END AS b,
       |       CASE WHEN rid % 5 = 4 THEN NULL ELSE rid % 3 = 0 END AS b2,
       |       ${ntz("d", "t")} AS ntz,
       |       ${ntz("d2", "t2")} AS ntz2,
       |       $floating,
       |       ${decimal("l", 9)} AS d9,
       |       ${decimal("l2", 9)} AS d9b,
       |       ${decimal("l", 18)} AS d18,
       |       ${decimal("l2", 18)} AS d18b
       |FROM VALUES ${withIds.mkString(",\n  ")}
       |AS v(rid, d, d2, i, m, y, mm, l, l2, t, t2, dt, dt2, y1, y2, s1, s2)$where""".stripMargin
  }
}
