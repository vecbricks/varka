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

import scala.jdk.OptionConverters._

import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaMatrix}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DateType, IntegerType, StructField, StructType}

/**
 * The order in which a Varka filter's error and the rows before it reach the operator above
 * (VARKA-387). Spark runs each row through a stage's filter and the operators above it before it
 * reads the next row, so where a filter raises on row k, a projection above raises its own error
 * on an earlier row first, an aggregate meets the earlier rows first, and a `LIMIT` satisfied by
 * the earlier rows answers without ever meeting row k. Varka's filter works on batches, and
 * delivers the rows that passed before k as a shorter batch before it raises. Each case compares
 * Varka's outcome - rows, or the error with its condition and parameters - with vanilla Spark's,
 * under ANSI, over one partition so that the row order is fixed.
 *
 * The fixture's rows, in order (`y + 1 > 0` overflows on row 4 and `make_date(2021, m3, 1)`
 * raises there, the two filters' errors; `make_date(2021, m1, 1)` raises on row 1 and
 * `make_date(2021, m2, 1)` on row 5; `sum(x)` overflows on row 2):
 *
 * {{{
 *   row  y              k  m1  m2  m3  x
 *   1    0              1  13  1   1   Long.MaxValue
 *   2    0              2  1   1   1   1
 *   3    0              3  1   1   1   0
 *   4    Int.MaxValue   4  1   1   13  0
 *   5    0              5  1   13  1   0
 *   6    0              6  1   1   1   0
 * }}}
 *
 * Every case runs at three Arrow batch sizes: 3 puts row 4 first in its batch, so the filter has
 * no earlier row to deliver; 4 puts it last in its batch; and the default puts every row in one
 * batch, row 4 in the middle.
 */
class VarkaFilterRaiseOrderSuite extends QueryTest with VarkaMaybeThrows with VarkaTestWatchdog {
  import VarkaMaybeThrows._

  private val view = "varka_raise_order"
  private val wideView = "varka_raise_order_wide"
  private val wideColumns = 70

  private val batchSizes =
    Seq(3, 4, SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.defaultValue.get)

  // (y, k, m1, m2, m3, x), in the order the class doc's table gives.
  private val rows = Seq(
    (0, 1, 13, 1, 1, Long.MaxValue),
    (0, 2, 1, 1, 1, 1L),
    (0, 3, 1, 1, 1, 0L),
    (Int.MaxValue, 4, 1, 1, 13, 0L),
    (0, 5, 1, 13, 1, 0L),
    (0, 6, 1, 1, 1, 0L))

  private def cacheRows(session: SparkSession): Unit = {
    session.createDataFrame(rows).toDF("y", "k", "m1", "m2", "m3", "x").coalesce(1)
      .createOrReplaceTempView(view)
    session.catalog.cacheTable(view)
  }

  // The same y and m1 beside seventy date columns: past the sixty-four columns one kernel reads,
  // so a projection over all of them runs two kernels.
  private def cacheWide(session: SparkSession): Unit = {
    val wide = rows.map { case (y, k, m1, _, _, _) =>
      Row.fromSeq(Seq(y, m1) ++ (0 until wideColumns).map { c =>
        java.sql.Date.valueOf(java.time.LocalDate.of(2020, 1, 1).plusDays(k * 31L + c))
      })
    }
    val schema = StructType(Seq(StructField("y", IntegerType), StructField("m1", IntegerType)) ++
      (0 until wideColumns).map(c => StructField(s"c$c", DateType)))
    session.createDataFrame(session.sparkContext.parallelize(wide, 1), schema)
      .createOrReplaceTempView(wideView)
    session.catalog.cacheTable(wideView)
  }

  private def kind(node: Class[_]): String = node.getSimpleName

  /**
   * Runs `query` on both engines at every batch size, over the view `cache` builds and caches as
   * `name`, with Varka's kernels emitted under `options`. At each size Varka's plan must hold a
   * `node`, and the outcomes must agree; `expect` then checks the shared outcome, so that a case
   * cannot pass by both engines doing something other than what it is about. All sizes run
   * before the test fails, so that a failure lists every size that disagreed.
   */
  private def check(
      query: String,
      node: Class[_ <: SparkPlan],
      cache: SparkSession => Unit = cacheRows,
      name: String = view,
      options: VarkaEmitOptions = VarkaMatrix.base,
      planCheck: SparkPlan => Unit = _ => ())(expect: Outcome => Unit): Unit = {
    VarkaColumnarToRowExec.setEmitOptionsForTesting(options)
    try {
      val differences = batchSizes.flatMap { size =>
        withAnsi(true) {
          withBothConf(SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.key, size.toString) {
            cache(spark)
            cache(varkaSpark)
            try {
              val plan = varkaSpark.sql(query).queryExecution.executedPlan
              assert(find(plan)(node.isInstance).isDefined,
                s"expected a ${kind(node)} in the plan:\n${plan.treeString}")
              planCheck(plan)
              val onSpark = outcome(spark, query)
              val diff = difference(onSpark, outcome(varkaSpark, query))
              if (diff.isEmpty) {
                withClue(s"batch size $size: ") {
                  expect(onSpark)
                }
              }
              diff.map(d => s"batch size $size: $d")
            } finally {
              Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable(name))
            }
          }
        }
      }
      if (differences.nonEmpty) {
        fail(s"$query\n${differences.mkString("\n")}")
      }
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaMatrix.base)
    }
  }

  private def raises(condition: String)(outcome: Outcome): Unit = outcome match {
    case r: Raised => assert(r.condition == condition, r)
    case a: Answered => fail(s"expected $condition, both answered $a")
  }

  private def answers(expected: Seq[Row])(outcome: Outcome): Unit = outcome match {
    case Answered(got) => assert(QueryTest.sameRows(expected, got).isEmpty, got)
    case r: Raised => fail(s"expected rows, both raised $r")
  }

  private def days(from: Int, until: Int): Seq[Row] =
    (from until until).map(k => Row(java.sql.Date.valueOf(s"2021-01-0${1 + k}")))

  private val overflow = "ARITHMETIC_OVERFLOW"
  private val month = "DATETIME_FIELD_OUT_OF_BOUNDS.WITHOUT_SUGGESTION"

  test("a projection that raises on an earlier row raises first, over a columnar filter that " +
      "raises on a later one") {
    check(s"SELECT make_date(2021, m1, 1) AS c FROM $view WHERE y + 1 > 0",
      classOf[VarkaFilterExec])(raises(month))
  }

  test("a LIMIT the rows before the filter's raising row satisfy answers, and one they do not " +
      "raises the filter's error") {
    def q(n: Int): String =
      s"SELECT date_add(DATE'2021-01-01', k) AS c FROM $view WHERE y + 1 > 0 LIMIT $n"
    for (n <- 1 to 3) {
      check(q(n), classOf[VarkaFilterExec])(answers(days(1, 1 + n)))
    }
    check(q(4), classOf[VarkaFilterExec])(raises(overflow))
  }

  test("an aggregate above a projection over the columnar filter meets the earlier rows first") {
    check(s"SELECT count(c), max(c) FROM (SELECT make_date(2021, m1, 1) AS c FROM $view " +
      "WHERE y + 1 > 0)", classOf[VarkaFilterExec])(raises(month))
  }

  test("an aggregate that overflows on the rows before the filter's raising row raises its " +
      "own error, over either filter node") {
    // The filter raises a datetime error on row 4 and sum(x) overflows on row 2, so the two
    // errors' conditions tell which row an engine met first. Over a projection whose column the
    // aggregate reads twice, so that CollapseProject keeps the projection and the filter is the
    // columnar node; and directly, over the row-out node.
    val where = "WHERE make_date(2021, m3, 1) > DATE'2000-01-01'"
    check(s"SELECT sum(x), min(c), max(c) FROM (SELECT x, date_add(DATE'2021-01-01', k) AS c " +
      s"FROM $view $where)", classOf[VarkaFilterExec])(raises(overflow))
    check(s"SELECT sum(x) FROM $view $where", classOf[VarkaFilterColumnarToRowExec])(
      raises(overflow))
  }

  test("with no error above, the filter's own error is the answer") {
    check(s"SELECT date_add(DATE'2021-01-01', k) AS c FROM $view WHERE y + 1 > 0",
      classOf[VarkaFilterExec])(raises(overflow))
  }

  test("a filter and a projection that raise nowhere answer every row, from the kernels") {
    val query = s"SELECT date_add(DATE'2021-01-01', k) AS c FROM $view WHERE y >= 0"
    check(query, classOf[VarkaFilterExec])(answers(days(1, 7)))
    withAnsi(true) {
      cacheRows(spark)
      cacheRows(varkaSpark)
      try {
        val df = varkaSpark.sql(query)
        df.collect()
        assertKernelsRan(df.queryExecution.executedPlan)
      } finally {
        Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable(view))
      }
    }
  }

  test("a projection that would raise on a row after the filter's raising row never sees it: " +
      "the filter's error") {
    check(s"SELECT make_date(2021, m2, 1) AS c FROM $view WHERE y + 1 > 0",
      classOf[VarkaFilterExec])(raises(overflow))
  }

  test("over the row-out filter an aggregate and a LIMIT meet the rows in Spark's order") {
    val toRow = classOf[VarkaFilterColumnarToRowExec]
    check(s"SELECT max(make_date(2021, m1, 1)) FROM $view WHERE y + 1 > 0", toRow)(raises(month))
    check(s"SELECT k FROM $view WHERE y + 1 > 0 LIMIT 3", toRow)(answers(Seq(Row(1), Row(2),
      Row(3))))
  }

  test("a fused raising conjunct beside a residual one: the projection's earlier error") {
    // The residual conjunct stays in a row FilterExec above the Varka filter, so the filter is
    // always the row-out node here, its fallback evaluating the whole condition.
    check(s"SELECT make_date(2021, m1, 1) AS c FROM $view " +
      "WHERE y + 1 > 0 AND length(concat(CAST(k AS STRING), 'x')) > 0",
      classOf[VarkaFilterColumnarToRowExec])(raises(month))
  }

  test("a split predicate's raising conjunct: the projection's earlier error") {
    // Eighty ranges over k, past what one method holds, so that splitConditions compiles the
    // predicate into several selection outputs; the first range holds every row's k. The split
    // needs the byte budget: with it off the emitter caps a kernel at 64 distinct ops, which these
    // 160 comparisons are past, so the budget is pinned with the other two options the shape
    // rests on.
    val ranges = (0 until 80).map(i => s"(k >= ${10 * i + 1} AND k <= ${10 * i + 6})")
      .mkString(" OR ")
    val options = VarkaMatrix.base.withRangeSets(false).withSplitConditions(true)
      .withMethodByteBudget(VarkaEmitOptions.DEFAULTS.methodByteBudget())
    check(s"SELECT make_date(2021, m1, 1) AS c FROM $view WHERE y + 1 > 0 AND ($ranges)",
      classOf[VarkaFilterExec], options = options,
      planCheck = plan => {
        val filter = collectFirst(plan) { case f: VarkaFilterExec => f }.get
        val compiled = VarkaExpressionCompiler.compilePredicate(
          filter.condition, filter.child.output, options).toScala
        assert(compiled.exists(_.fused.outputs.size > 1), compiled)
      })(raises(month))
  }

  test("a projection of two kernels above the columnar filter raises its earlier error first") {
    val outputs = "make_date(2021, m1, 1) AS p" +:
      (0 until wideColumns).map(c => s"date_add(c$c, 1) AS a$c")
    val query = s"SELECT ${outputs.mkString(", ")} FROM $wideView WHERE y + 1 > 0"
    check(query, classOf[VarkaFilterExec], cacheWide, wideView,
      VarkaMatrix.base.withSeveralKernels(true),
      planCheck = _ => {
        val explain = varkaSpark.sql(query).queryExecution.explainString(FormattedMode)
        assert(explain.contains("kernel 2 of 2"), explain)
      })(raises(month))
  }
}
