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

import org.apache.spark.SparkThrowable
import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.internal.SQLConf

/**
 * Which rule Spark's ANSI and TRY casts of a `DECIMAL(p, s)` with `p <= 18` to `INT` follow over
 * an Arrow-cached table, on each path a query can take to the cast (VARKA-365).
 *
 * `Decimal.roundToInt` has two rules, chosen by the value's backing. A long-backed value
 * truncates its unscaled long and checks the result exactly; a `BigDecimal`-backed one converts
 * to a double and range-checks its floor and ceiling. They part on the two values this suite
 * reads: `0.999999999999999999` as `DECIMAL(18, 18)`, which truncates to 0 but rounds to the
 * double 1.0, and `2147483647.99999999` as `DECIMAL(18, 8)`, which truncates to `Int.MaxValue`
 * but rounds to the double 2^31, past it.
 *
 * The backing is decided by the path, not by the data. `ArrowColumnVector` builds a
 * `BigDecimal`-backed value at every precision, and an `UnsafeRow` holding a decimal of
 * precision 18 or less reads it back long-backed. With Varka off, every consumer of the Arrow
 * cache here reads rows: `InMemoryTableScanExec` serves rows to a row consumer even with the
 * vectorized reader on, through the cache's row reader, which writes the unscaled long into an
 * `UnsafeRow`, or, for a table the row reader cannot read, through an `UnsafeProjection` over
 * the batch. So vanilla Spark follows the exact rule on every path here. With Varka on, a
 * Varka node reads the batches through `ArrowColumnVector`, and its residual outputs, its
 * fallbacks and its filter's fallback read the rows through `VarkaRowView`, which hands a small
 * decimal long-backed as an `UnsafeRow` does; so Varka follows the exact rule on every path too,
 * as `VARKA-365.md` 11 records.
 *
 * Every cell runs the four casts and names the rule all four followed; a cell whose answers
 * follow neither rule fails with what it saw. LEGACY casts truncate on both backings and are
 * checked to agree everywhere.
 */
class VarkaDecimalReadPathSuite extends QueryTest with VarkaSharedSessions
  with VarkaTestWatchdog {

  /**
   * `a` and `b` the two decimals, `d` and `i` a date and an int that `date_add(d, i)` fuses over
   * (`rp_fused`), and `arr` an array, which the cache's row reader cannot read (`rp_array`).
   */
  private val fixtures = Seq(
    "rp_fused" -> """SELECT CAST(a AS DECIMAL(18, 18)) AS a, CAST(b AS DECIMAL(18, 8)) AS b,
      |  d, i FROM VALUES (0.999999999999999999, 2147483647.99999999, DATE'2024-01-01', 3)
      |AS v(a, b, d, i)""".stripMargin,
    "rp_array" -> """SELECT CAST(a AS DECIMAL(18, 18)) AS a, CAST(b AS DECIMAL(18, 8)) AS b,
      |  arr FROM VALUES (0.999999999999999999, 2147483647.99999999, array(1, 2))
      |AS v(a, b, arr)""".stripMargin)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    for (session <- Seq(spark, varkaSpark); (name, query) <- fixtures) {
      session.sql(query).createOrReplaceTempView(name)
      session.catalog.cacheTable(name)
    }
  }

  private val ExactRule = "the exact rule"
  private val DoubleRule = "the double rule"
  private val NoRow = "no row"

  /**
   * What a query shape answers under each rule: the LEGACY casts of `a` and `b`, then the ANSI
   * casts of `a` and `b` and the TRY casts of `a` and `b` on each rule.
   */
  private case class Answers(legacy: Seq[String], exact: Seq[String], double: Seq[String])

  /** A cast as an output column: its value. */
  private val asValue = Answers(
    legacy = Seq("0", "2147483647"),
    exact = Seq("0", "2147483647", "0", "2147483647"),
    double = Seq("1", "CAST_OVERFLOW", "1", "null"))

  /**
   * A cast tested in a filter against the values the exact rule gives, the query returning `i`
   * (3) when the row passes.
   */
  private val asFilter = Answers(
    legacy = Seq("3", "3"),
    exact = Seq("3", "3", "3", "3"),
    double = Seq(NoRow, "CAST_OVERFLOW", NoRow, NoRow))

  /**
   * The answer of a query of at most one row: its value, `null`, `no row`, or the condition it
   * raised.
   */
  private def answer(session: SparkSession, query: String): String =
    try {
      val rows = session.sql(query).collect()
      assert(rows.length <= 1, s"$query: ${rows.length} rows")
      rows.headOption.map(r => String.valueOf(r.get(0))).getOrElse(NoRow)
    } catch {
      case e: Exception =>
        Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).collectFirst {
          case t: SparkThrowable if t.getCondition != null => t.getCondition
        }.getOrElse(throw e)
    }

  /**
   * A read path: the session, the table, the SQL configurations it sets, how a query is built
   * around the cast, what that query answers on each rule, whether every Varka batch is declined,
   * and the check that the plan takes the path the cell names.
   */
  private case class Path(
      session: () => SparkSession,
      table: String,
      confs: Seq[(String, String)],
      shape: QueryShape,
      declineAll: Boolean,
      check: SparkPlan => Unit)

  /** A query around a cast, as the clauses after `SELECT`, the table put in by `FROM`. */
  private case class QueryShape(build: (String, String) => String, answers: Answers)

  private def withConfs[T](session: SparkSession, confs: Seq[(String, String)])(body: => T): T = {
    val before = confs.map { case (k, _) => k -> session.conf.getOption(k) }
    confs.foreach { case (k, v) => session.conf.set(k, v) }
    try body finally before.foreach {
      case (k, Some(v)) => session.conf.set(k, v)
      case (k, None) => session.conf.unset(k)
    }
  }

  /**
   * Runs the four casts on `path` and returns the rule they followed. The plan is checked on a
   * TRY query, which raises on neither rule, and the LEGACY casts are checked to truncate.
   */
  private def ruleOf(path: Path): String = {
    val session = path.session()
    def query(cast: String): String = path.shape.build(cast, path.table)
    val answers = path.shape.answers
    VarkaColumnarToRowExec.setDeclineKernelForTesting(path.declineAll)
    try withConfs(session, path.confs) {
      val probe = session.sql(query("try_cast(a AS INT)"))
      probe.collect()
      path.check(probe.queryExecution.executedPlan)
      assert(cachedReads(probe.queryExecution.executedPlan) == Seq(path.table),
        s"the query does not read the cached ${path.table}")
      val legacy = withConfs(session, Seq(SQLConf.ANSI_ENABLED.key -> "false")) {
        Seq(answer(session, query("CAST(a AS INT)")), answer(session, query("CAST(b AS INT)")))
      }
      assert(legacy == answers.legacy, s"LEGACY casts answered $legacy")
      val seen = withConfs(session, Seq(SQLConf.ANSI_ENABLED.key -> "true")) {
        Seq("CAST(a AS INT)", "CAST(b AS INT)", "try_cast(a AS INT)", "try_cast(b AS INT)")
          .map(c => answer(session, query(c)))
      }
      if (seen == answers.exact) {
        ExactRule
      } else if (seen == answers.double) {
        DoubleRule
      } else {
        fail(s"ANSI and TRY casts answered $seen, by neither rule")
      }
    } finally {
      VarkaColumnarToRowExec.setDeclineKernelForTesting(false)
    }
  }

  /** A plan that reads the cache as rows: no Varka node and no columnar-to-row transition. */
  private def rowRead(plan: SparkPlan): Unit = {
    assertNotFused(plan)
    assert(find(plan)(_.isInstanceOf[ColumnarToRowTransition]).isEmpty,
      s"expected the cache read as rows:\n${plan.treeString}")
  }

  private def varkaBatches(plan: SparkPlan): Long = {
    assertFused(plan)
    collect(plan) {
      case node if node.metrics.contains("numVarkaBatches") => node.metrics("numVarkaBatches").value
    }.sum
  }

  private val served: SparkPlan => Unit =
    p => assert(varkaBatches(p) > 0, s"the kernel served no batch:\n${p.treeString}")
  private val declined: SparkPlan => Unit =
    p => assert(varkaBatches(p) == 0, s"the kernel served a batch:\n${p.treeString}")

  /** A filter split into a row `FilterExec` over a Varka filter, and `batches` of the plan. */
  private def split(batches: SparkPlan => Unit): SparkPlan => Unit = { plan =>
    assert(find(plan) {
      case f: FilterExec => find(f.child)(_.isInstanceOf[VarkaFilterExecBase]).isDefined
      case _ => false
    }.isDefined, s"expected a FilterExec over a Varka filter:\n${plan.treeString}")
    batches(plan)
  }

  private val alone = QueryShape((cast, t) => s"SELECT $cast AS r FROM $t", asValue)
  private val besideFused =
    QueryShape((cast, t) => s"SELECT $cast AS r, date_add(d, i) AS f FROM $t", asValue)
  private val besideFusedTwice = QueryShape(
    (cast, t) => s"SELECT $cast AS r, a AS a2, b AS b2, date_add(d, i) AS f FROM $t", asValue)
  /**
   * The cast in a conjunct the filter kernel declines, beside one it fuses: the rule splits the
   * condition into a `FilterExec` over a Varka filter, whose fallback evaluates the whole
   * condition.
   */
  private val leftoverFilter = QueryShape(
    (cast, t) => s"SELECT i FROM $t WHERE date_add(d, 30) > DATE'2020-07-01' " +
      s"AND $cast IN (0, 2147483647)",
    asFilter)

  private val paths: Seq[(String, Path, String)] = Seq(
    ("Varka off, the vectorized reader on, the cache read as rows by the row reader",
      Path(() => spark, "rp_fused", Nil, alone, declineAll = false, rowRead), ExactRule),
    ("Varka off, the vectorized reader off",
      Path(() => spark, "rp_fused", Seq(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key -> "false"),
        alone, declineAll = false, rowRead), ExactRule),
    ("Varka off, the row reader's columnar fallback for a table with an array",
      Path(() => spark, "rp_array", Nil, alone, declineAll = false, rowRead), ExactRule),
    ("Varka off, without whole-stage code",
      Path(() => spark, "rp_fused", Seq(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false"),
        alone, declineAll = false, rowRead), ExactRule),
    ("Varka on, a cast nothing fuses beside, the cache read as rows",
      Path(() => varkaSpark, "rp_fused", Nil, alone, declineAll = false, rowRead), ExactRule),
    ("Varka on, a residual cast beside a fused output, the kernel serving the batch",
      Path(() => varkaSpark, "rp_fused", Nil, besideFused, declineAll = false, served), ExactRule),
    ("Varka on, a residual cast beside a column read twice, the kernel serving the batch",
      Path(() => varkaSpark, "rp_fused", Nil, besideFusedTwice, declineAll = false, served),
      ExactRule),
    ("Varka on, the fallback over the batch's own rows",
      Path(() => varkaSpark, "rp_fused", Nil, besideFused, declineAll = true, declined),
      ExactRule),
    ("Varka on, the fallback over a copied row, a column read twice",
      Path(() => varkaSpark, "rp_fused", Nil, besideFusedTwice, declineAll = true, declined),
      ExactRule),
    ("Varka off, the cast in a filter beside a fusable conjunct",
      Path(() => spark, "rp_fused", Nil, leftoverFilter, declineAll = false, rowRead), ExactRule),
    ("Varka on, the cast in a filter left beside a fused conjunct, the kernel serving the batch",
      Path(() => varkaSpark, "rp_fused", Nil, leftoverFilter, declineAll = false,
        split(served)), ExactRule),
    ("Varka on, the cast in a filter left beside a fused conjunct, the fallback",
      Path(() => varkaSpark, "rp_fused", Nil, leftoverFilter, declineAll = true,
        split(declined)), ExactRule))

  paths.foreach { case (name, path, expected) =>
    test(s"$name: the ANSI and TRY decimal-to-INT casts follow $expected") {
      assert(ruleOf(path) === expected)
    }
  }
}
