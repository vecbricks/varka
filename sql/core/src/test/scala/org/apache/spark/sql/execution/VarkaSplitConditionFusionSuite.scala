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

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.{And, AttributeReference, Expression,
  GreaterThanOrEqual, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaPredicate,
  VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitterTestSupport,
  VarkaLoopEmitter}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.IntegerType

/**
 * How the compiler splits a filter predicate that one method cannot hold (`splitConditions`,
 * `PLAN_TASK_172.md` 3.1). A condition root is emitted into one method, so a predicate past the
 * 8000-byte method budget declines unless it is split across several selection outputs, which
 * the filter then combines: conjuncts packed into several conjunction roots, and a disjunction
 * too large alone cut into partial roots. These tests pin when the split happens, the clause
 * shape it produces, that every method of the result fits, and that a predicate one method can
 * hold compiles exactly as it does without the option. TPC-DS `modified-q3`'s ranges are the
 * realistic case, compiled with range sets off so that they reach the split as comparisons.
 */
class VarkaSplitConditionFusionSuite extends SparkFunSuite {

  private val s = AttributeReference("ss_sold_date_sk", IntegerType)()
  private val t = AttributeReference("t", IntegerType)()

  private def parse(sql: String): Expression = CatalystSqlParser.parseExpression(sql).transform {
    case u: UnresolvedAttribute if u.name == "t" => t
    case _: UnresolvedAttribute => s
  }

  /** `s >= 0 and (range 1 or ... or range n)`, as `VarkaRangeFilterFusionSuite` builds it. */
  private def q3(n: Int): Expression =
    And(GreaterThanOrEqual(s, Literal(0)), parse(VarkaQ3Ranges.comparisons(n, "ss_sold_date_sk")))

  private val comparisons =
    VarkaColumnarToRowExec.emitOptions(SQLConf.get.varkaEmitUseAVX).withRangeSets(false)
  private val split = comparisons.withSplitConditions(true)

  private def predicate(condition: Expression, splitOn: Boolean): Option[CompiledVarkaPredicate] =
    VarkaExpressionCompiler.compilePredicate(
      condition, Seq(s, t), if (splitOn) split else comparisons)

  private def fullyFused(p: Option[CompiledVarkaPredicate]): Boolean =
    p.exists(_.specs.forall(_.fused))

  /** Every method of the predicate's kernel, emitted and measured, with its bytecode length. */
  private def methodSizes(p: CompiledVarkaPredicate): Seq[(String, Int)] = {
    val bytes = VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.SplitCondition",
      p.fused.outputs.asJava, p.fused.inputOrdinals.size, p.fused.numLiterals, null, null, split)
    VarkaEmitterTestSupport.methodNames(bytes).asScala.distinct.toSeq
      .map(m => m -> VarkaEmitterTestSupport.codeSize(bytes, m))
  }

  test("without the split, the comparisons fuse up to 48 ranges and decline from 49") {
    assert(fullyFused(predicate(q3(48), splitOn = false)))
    val at49 = VarkaExpressionCompiler.explainPredicate(q3(49), Seq(s, t), comparisons)
    assert(!at49.forall(_.fused))
    assert(at49.flatMap(_.decline).exists(_.reason.contains("method budget")), at49)
  }

  test("a predicate one method holds compiles exactly as it does without the split") {
    assert(predicate(q3(48), splitOn = true) == predicate(q3(48), splitOn = false))
    assert(predicate(q3(48), splitOn = true).get.clauses == Seq(Seq(0)))
  }

  test("modified-q3's ranges split, and every method of the kernel stays under the budget") {
    for (n <- Seq(49, 100, 200)) {
      val p = predicate(q3(n), splitOn = true)
      assert(fullyFused(p), s"$n ranges: ${p.map(_.specs)}")
      // `s >= 0` is one clause, and the ranges, too large for one method, are the other,
      // cut into partial disjunctions.
      val clauses = p.get.clauses
      assert(clauses.size == 2 && clauses.head.size == 1 && clauses(1).size >= 2, clauses)
      val sizes = methodSizes(p.get)
      assert(sizes.forall(_._2 <= 8000), s"$n ranges: $sizes")
    }
  }

  test("the fusion report says when the predicate is split, and only then") {
    val lines = VarkaFusionReport.predicateLines(q3(200), Seq(s, t), split)
    assert(lines.count(_.startsWith("split across")) == 1, lines)
    assert(!VarkaFusionReport.predicateLines(q3(48), Seq(s, t), split)
      .exists(_.startsWith("split across")))
  }

  test("a disjunction that is not a range set splits the same way") {
    val disjuncts =
      (0 until 150).map(j => s"(ss_sold_date_sk = ${2415022 + j * 487} and t >= $j)")
    val condition = parse(disjuncts.mkString(" or "))
    assert(!fullyFused(predicate(condition, splitOn = false)))
    val p = predicate(condition, splitOn = true)
    assert(fullyFused(p) && p.get.clauses.size == 1 && p.get.clauses.head.size >= 2, p)
    assert(methodSizes(p.get).forall(_._2 <= 8000))
  }

  test("a long conjunction is split into several conjunction roots") {
    def conjunction(n: Int): Expression =
      parse((0 until n).map(j => s"ss_sold_date_sk <> ${2415022 + j * 211}").mkString(" and "))
    // Without the split each conjunct past the budget is demoted and the rest asked again, one
    // emission per demotion, so the declining arm is checked at a size that demotes few.
    assert(!fullyFused(predicate(conjunction(120), splitOn = false)))
    val p = predicate(conjunction(300), splitOn = true)
    assert(fullyFused(p) && p.get.clauses.size >= 2 && p.get.clauses.forall(_.size == 1), p)
    assert(methodSizes(p.get).forall(_._2 <= 8000))
  }

  test("a conjunct too large alone that is not a disjunction still declines with the reason") {
    val condition = parse(s"not (${VarkaQ3Ranges.comparisons(200, "ss_sold_date_sk")})")
    val specs = VarkaExpressionCompiler.explainPredicate(condition, Seq(s, t), split)
    assert(!specs.forall(_.fused))
    assert(specs.flatMap(_.decline).exists(_.reason.contains("method budget")), specs)
  }
}
