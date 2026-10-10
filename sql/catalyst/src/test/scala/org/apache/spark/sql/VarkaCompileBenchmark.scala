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

package org.apache.spark.sql

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference,
  Expression, HoursOfTime, MinutesOfTime, NamedExpression, SecondsOfTime}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.types.{DateType, IntegerType, TimeType}

/**
 * What it costs to *compile* an expression into the IR - the work of the compiler families, before
 * the emitter sees anything - which `VarkaEmissionBenchmark` cannot show: that benchmark builds IR
 * by hand and times only `VarkaLoopEmitter.emit`, so no change to a family compiler can move it
 * (VARKA-215 9).
 *
 * One case per shape, each a call of `VarkaExpressionCompiler.compile` (a projection) or
 * `compilePredicate` (a filter's condition) over expressions built once. The shapes are chosen
 * to run each family: the calendar arms, the TIME arms, the conditionals (`CASE WHEN`, `IF`,
 * `coalesce`, `greatest`), the predicate compiler (comparisons, the connectives, `IN`, a range
 * set, the validity predicates), and a wide projection that mixes them. Every shape is checked to
 * fuse before it is timed, so a case measures compilation and not a decline - which is why the
 * `CASE WHEN` has six branches: one of sixteen does not fuse as a whole.
 *
 * Time per call, in the file's "per row" column, since one iteration is one call. The numbers
 * move with the machine; compare two runs of the same day (`dev/varka_bench_regen.sh` refuses a
 * busy machine).
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh catalyst VarkaCompileBenchmark
 * }}}
 */
object VarkaCompileBenchmark extends BenchmarkBase {

  private val d = AttributeReference("d", DateType)()
  private val d2 = AttributeReference("d2", DateType)()
  private val i = AttributeReference("i", IntegerType)()
  private val t = AttributeReference("t", TimeType(6))()
  private val columns: Seq[Attribute] = Seq(d, d2, i, t)

  private def sql(text: String): Expression =
    VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(text), columns)

  private def named(exprs: Seq[Expression]): Seq[NamedExpression] =
    exprs.zipWithIndex.map { case (e, k) => Alias(e, s"c$k")() }

  private val caseWhen6: String = "CASE " + (1 to 6).map { k =>
    s"WHEN d < DATE'20${10 + k}-01-01' THEN date_add(d, $k)"
  }.mkString(" ") + " ELSE d2 END"

  /** The projections: a title and the expressions of one projection. */
  private val projections: Seq[(String, Seq[Expression])] = Seq(
    "calendar: year, month, make_date, add_months, last_day, weekofyear" -> Seq(
      sql("year(d)"), sql("month(d)"), sql("make_date(year(d), month(d), 1)"),
      sql("add_months(d, 3)"), sql("last_day(d)"), sql("weekofyear(d)")),
    "TIME: hour, minute, second" -> Seq(HoursOfTime(t), MinutesOfTime(t), SecondsOfTime(t)),
    "conditionals: IF, coalesce, greatest, CASE WHEN of 6 branches" -> Seq(
      sql("IF(d < d2, d, d2)"), sql("coalesce(d, d2)"), sql("greatest(d, d2)"),
      sql(caseWhen6)),
    "a wide projection of 60 mixed outputs" -> (1 to 60).map { k =>
      k % 4 match {
        case 0 => sql(s"year(d) + $k")
        case 1 => sql(s"IF(d < DATE'2020-01-01', date_add(d, $k), d2)")
        case 2 => sql(s"add_months(d, $k)")
        case _ => sql(s"coalesce(d, date_add(d2, $k))")
      }
    })

  /** The filters: a title and one condition. */
  private val predicates: Seq[(String, Expression)] = Seq(
    "predicate: a range, IS NOT NULL and an int comparison" -> sql(
      "d >= DATE'2020-01-01' AND d < DATE'2021-01-01' AND d IS NOT NULL AND i > 0"),
    "predicate: IN over eight dates" -> sql(
      "d IN (" + (1 to 8).map(k => s"DATE'2020-0$k-15'").mkString(", ") + ")"),
    "predicate: a set of three date ranges" -> sql(
      "(d >= DATE'2020-01-01' AND d <= DATE'2020-03-31') OR " +
        "(d >= DATE'2020-07-01' AND d <= DATE'2020-09-30') OR " +
        "(d >= DATE'2021-01-01' AND d <= DATE'2021-01-31')"),
    "predicate: NOT, OR and IS NULL over two dates" -> sql(
      "NOT (d < d2) OR i IS NULL OR d2 IS NOT NULL"))

  private val callsPerIteration = 200

  /** Keeps the compiler's results live. */
  @volatile private var blackhole = 0

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    var sink = 0
    runBenchmark("compiling an expression into the IR") {
      val benchmark = new Benchmark("one compile", callsPerIteration,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      for ((title, exprs) <- projections) {
        val projectList = named(exprs)
        for (e <- exprs) {
          require(VarkaExpressionCompiler.compile(named(Seq(e)), columns).isDefined,
            s"the shape does not fuse, so it would time a decline: $title: ${e.sql}: " +
              VarkaExpressionCompiler.compilePartial(named(Seq(e)),
                  columns).map(_.declines.asScala))
        }
        require(VarkaExpressionCompiler.compile(projectList, columns).isDefined,
          s"the shape does not fuse, so it would time a decline: $title")
        benchmark.addCase(title) { _ =>
          var k = 0
          while (k < callsPerIteration) {
            sink += VarkaExpressionCompiler.compile(projectList, columns).size
            k += 1
          }
        }
      }
      for ((title, condition) <- predicates) {
        require(VarkaExpressionCompiler.compilePredicate(condition, columns).isDefined,
          s"the shape does not fuse, so it would time a decline: $title")
        benchmark.addCase(title) { _ =>
          var k = 0
          while (k < callsPerIteration) {
            sink += VarkaExpressionCompiler.compilePredicate(condition, columns).size
            k += 1
          }
        }
      }
      benchmark.run()
    }
    blackhole = sink
  }
}
