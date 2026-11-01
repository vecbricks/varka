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

import java.time.LocalDate

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, Attribute, Cast, CreateMap,
  DateAdd, DateFormatClass, EvalMode, Literal, TryEval, TryMultiply}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaReachWindow
import org.apache.spark.sql.catalyst.plans.logical.{Limit, LocalRelation, LogicalPlan,
  OneRowRelation, Project}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{IntegerType, StringType, YearMonthIntervalType}

/**
 * The golden-file suite's literal lift (`VarkaArrowSource.LiftRule`): which literals of a
 * `SELECT` with no `FROM` become columns of a seventeen-row relation, and which stay. The rule
 * asks the compiler whether each lifted entry fuses, so the suite compiles kernels, and its reach
 * window keeps them its own rather than every test's in the option matrix's record.
 */
class VarkaLiteralLiftSuite extends SparkFunSuite with VarkaReachWindow {

  private val date = Literal(DateTimeUtils.localDateToDays(LocalDate.of(2021, 1, 31)),
    org.apache.spark.sql.types.DateType)

  private def select(exprs: (String, org.apache.spark.sql.catalyst.expressions.Expression)*) =
    Project(exprs.map { case (name, e) => Alias(e, name)() }, OneRowRelation())

  /** The lifted plan's relation, or a failure naming what the rule returned instead. */
  private def liftedRelation(plan: LogicalPlan): LocalRelation = plan match {
    case Limit(Literal(1, IntegerType), Project(_, relation: LocalRelation)) => relation
    case other => fail(s"expected a lifted plan, got:\n$other")
  }

  test("a fused expression over literals reads them from seventeen equal rows, one kept") {
    val plan = select("d" -> DateAdd(date, Literal(1)))
    val lifted = VarkaArrowSource.LiftRule(plan)
    val relation = liftedRelation(lifted)
    assert(relation.output.map(_.dataType) == Seq(date.dataType, IntegerType))
    assert(relation.data.size == VarkaArrowSource.LiftedRows)
    assert(relation.data.map(_.toSeq(relation.output.map(_.dataType))).distinct.size == 1)
    // The output keeps the name analysis gave it, so the golden file's schema still applies.
    assert(lifted.output.map(_.name) == plan.output.map(_.name))
  }

  test("one literal used twice is one column") {
    val relation = liftedRelation(VarkaArrowSource.LiftRule(
      select("a" -> DateAdd(date, Literal(1)), "b" -> DateAdd(date, Literal(1)))))
    assert(relation.output.size == 2)
  }

  test("two literals of different types holding one value are two columns of one row") {
    // INTERVAL '-1-1' YEAR TO MONTH + INTERVAL '-13' MONTH as the analyzer leaves it: the same
    // month count in two types, the second cast to the first. (The comparison of the two, a
    // boolean projection, is no kernel's, and the lift leaves it alone.)
    val yearToMonth = YearMonthIntervalType(0, 1)
    val relation = liftedRelation(VarkaArrowSource.LiftRule(select("e" -> Add(
      Literal(-13, yearToMonth),
      Cast(Literal(-13, YearMonthIntervalType(1, 1)), yearToMonth, Some("UTC"))))))
    assert(relation.output.size == 2)
    assert(relation.data.forall(_.numFields == 2))
  }

  test("a literal where a column would change the meaning, or of a type no kernel reads, stays") {
    // A map's key position is not one the compiler fuses, and a format string is not a type a
    // kernel reads: both plans come back as they were.
    val interval = Literal(1, YearMonthIntervalType())
    for (plan <- Seq(
        select("m" -> CreateMap(Seq(Literal(1), interval))),
        select("f" -> DateFormatClass(date, Literal("yyyy"))))) {
      val result = VarkaArrowSource.LiftRule(plan)
      assert(result.collectFirst { case l: LocalRelation => l }.isEmpty, result)
    }
  }

  test("nothing under a try_ function is lifted") {
    // An error inside one reports the context of the expression Spark evaluates, which differs
    // between constant folding and running the plan.
    for (plan <- Seq(
        select("t" -> TryEval(Add(Literal(2147483647), Literal(1)))),
        // As the analyzer leaves try_multiply(1, 2147483647 + 1): a runtime-replaceable whose one
        // child is its replacement, Multiply in TRY mode over numbers, with no TryEval.
        select("m" -> new TryMultiply(Literal(1), Add(Literal(2147483647), Literal(1)))),
        // try_cast is a Cast in TRY mode, neither of the two above.
        select("c" -> Cast(DateAdd(date, Literal(1)), StringType, Some("UTC"), EvalMode.TRY)))) {
      assert(VarkaArrowSource.LiftRule(plan).collectFirst { case l: LocalRelation => l }.isEmpty,
        plan)
    }
  }

  test("each scan partition holds seventeen rows, so the kernel's batch is seventeen long") {
    // A local relation is scanned in the session's leaf parallelism, and LIMIT 1 reads the first
    // partition: with two, thirty-four rows give each partition seventeen.
    val relation = liftedRelation(new VarkaArrowSource.LiftRule(() => 2)(
      select("d" -> DateAdd(date, Literal(1)))))
    assert(relation.data.size == 2 * VarkaArrowSource.LiftedRows)
  }

  test("a query with a FROM is left to the cache rule") {
    val relation = LocalRelation(Seq[Attribute](
      org.apache.spark.sql.catalyst.expressions.AttributeReference("d", date.dataType)()))
    val plan = Project(Seq(Alias(DateAdd(relation.output.head, Literal(1)), "x")()), relation)
    assert(VarkaArrowSource.LiftRule(plan) eq plan)
  }
}
