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

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.{And, AttributeReference, Expression,
  GreaterThanOrEqual, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.IntegerType

/**
 * Pins how much of TPC-DS `modified-q3`'s filter Varka fuses today: a chain of ranges joined by
 * `or` is one conjunct, one conjunct is one condition, and one condition is emitted into one
 * method, so the chain fuses while that method fits the byte budget and is declined, with a
 * reason, from the range that would take it past 8000 bytes. That is 48 ranges fused and 49
 * declined. A design that lets Varka take more of it moves this boundary, and this test with it
 * (`PLAN_TASK_172.md`).
 */
class VarkaRangeFilterBoundarySuite extends SparkFunSuite {

  private val s = AttributeReference("ss_sold_date_sk", IntegerType)()

  /**
   * `s >= 0 and (range 1 or ... or range n)`, the first conjunct there to fuse and so show the
   * second. The chain is parsed, because the shape matters: Spark's parser builds a long chain
   * of one connective as a balanced tree, and a hand-built left-deep chain of 48 would be
   * declined for its depth before its size was asked. Each range is written as the two
   * comparisons the optimizer rewrites the query's `between` into.
   */
  private def condition(n: Int): Expression = {
    val chain = CatalystSqlParser.parseExpression(VarkaQ3Ranges.comparisons(n, "ss_sold_date_sk"))
      .transform { case _: UnresolvedAttribute => s }
    And(GreaterThanOrEqual(s, Literal(0)), chain)
  }

  /** Whether the range chain fused, and the reason it did not if it did not. */
  private def chain(n: Int): (Boolean, Option[String]) = {
    val options = VarkaColumnarToRowExec.emitOptions(SQLConf.get.varkaEmitUseAVX)
    val compiled = VarkaExpressionCompiler.compilePredicate(condition(n), Seq(s), options)
    assert(compiled.isDefined, s"$n ranges: not even `s >= 0` fused")
    val spec = compiled.get.specs(1)
    (spec.fused, spec.decline.map(_.reason))
  }

  test("modified-q3 has 200 ranges") {
    assert(VarkaQ3Ranges.ranges.size == 200)
  }

  test("the first 48 ranges fuse into one method") {
    assert(chain(48) === (true, None))
  }

  test("the 49th range takes the method past the byte budget, and the chain is declined") {
    val (fused, decline) = chain(49)
    assert(!fused)
    assert(decline.exists(_.contains("over the emitter's method budget")), decline)
  }

  test("the whole filter is declined") {
    assert(!chain(200)._1)
  }
}
