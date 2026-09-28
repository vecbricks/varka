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

import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec

/**
 * Every row of the published coverage table, run through both engines (task 120).
 *
 * `VarkaCoverageSuite` proves each row of `sql/varka/coverage.json` compiles to a fused kernel
 * and that the table names every expression the compiler admits. This suite proves the kernel
 * behind each row answers what the row engine answers. It reads the committed JSON and nothing
 * else, so a row added to the coverage suite and regenerated into the file is run here without
 * a change to this file: adding an arm without a row fails the coverage suite, adding a row
 * without correctness fails this one, and the two together are the guarantee the table makes.
 *
 * Each row runs on three fixtures over the table's six columns - nulls sprinkled independently
 * per column, no nulls, and every column null in every row - and under both consumers a Varka
 * node can have: rows, the plan every direct query gets, and columns, which the Arrow cache
 * builder takes when a view over the query is cached. The hand-chosen shapes in
 * `VarkaDifferentialSuite` (extreme offsets, overflow, cache conversion) stay there; this is the
 * floor under them.
 */
class VarkaCoverageDifferentialSuite extends QueryTest with VarkaSharedSessions
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
    def view(values: Seq[String], where: String = ""): String =
      s"""SELECT d, d2, i,
         |       CAST(m AS INTERVAL MONTH) AS ymm,
         |       CAST(y AS INTERVAL YEAR) AS ymy,
         |       make_ym_interval(y, mm) AS ym,
         |       l, l2, CAST(t AS TIME(6)) AS t, CAST(t2 AS TIME(6)) AS t2, dt, dt2
         |FROM VALUES ${values.mkString(",\n  ")}
         |AS v(d, d2, i, m, y, mm, l, l2, t, t2, dt, dt2)$where""".stripMargin
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

  private def cacheFixtures(session: SparkSession): Unit = fixtures.foreach { case (name, sql) =>
    session.sql(sql).createOrReplaceTempView(name)
    session.catalog.cacheTable(name)
  }

  private def isColumnarVarkaNode(plan: SparkPlan): Boolean = plan match {
    case _: VarkaProjectExec | _: VarkaFilterExec => true
    case _ => false
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
}
