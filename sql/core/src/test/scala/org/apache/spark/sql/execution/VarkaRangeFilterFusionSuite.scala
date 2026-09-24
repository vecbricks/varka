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
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitterTestSupport,
  VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.IntegerType

/**
 * How Varka fuses TPC-DS `modified-q3`'s filter: its ranges, joined by `or`, compile to one range
 * set, whose kernel loops over a table of bounds, so the whole filter fuses however many ranges it
 * has and the kernel's methods stay small. Written as the tree of comparisons the query spells,
 * one condition is emitted into one method, and the chain fit the 8000-byte budget only up to 48
 * ranges (`PLAN_TASK_172.md` 9.1); this pins that the range set removed that limit.
 */
class VarkaRangeFilterFusionSuite extends SparkFunSuite {

  private val s = AttributeReference("ss_sold_date_sk", IntegerType)()

  /**
   * `s >= 0 and (range 1 or ... or range n)`, the first conjunct there to fuse beside the range
   * set. The chain is parsed, because the shape matters: Spark's parser builds a long chain of
   * one connective as a balanced tree. Each range is written as the two comparisons the
   * optimizer rewrites the query's `between` into.
   */
  private def condition(n: Int): Expression = {
    val chain = CatalystSqlParser.parseExpression(VarkaQ3Ranges.comparisons(n, "ss_sold_date_sk"))
      .transform { case _: UnresolvedAttribute => s }
    And(GreaterThanOrEqual(s, Literal(0)), chain)
  }

  private val options = VarkaColumnarToRowExec.emitOptions(SQLConf.get.varkaEmitUseAVX)

  test("modified-q3 has 200 ranges") {
    assert(VarkaQ3Ranges.ranges.size == 200)
  }

  test("the filter fuses at every number of ranges, the whole query's 200 included") {
    for (n <- Seq(2, 10, 48, 49, 100, 200)) {
      val compiled = VarkaExpressionCompiler.compilePredicate(condition(n), Seq(s), options)
      assert(compiled.exists(_.specs.forall(_.fused)), s"$n ranges: ${compiled.map(_.specs)}")
    }
  }

  test("the 200 ranges are one range set, and every method of its kernel is small") {
    val compiled = VarkaExpressionCompiler.compilePredicate(condition(200), Seq(s), options).get
    val sets = compiled.fused.outputs.flatMap { root =>
      def find(n: VarkaVectorIR): Seq[VarkaVectorIR.InRanges] = n match {
        case r: VarkaVectorIR.InRanges => Seq(r)
        case a: VarkaVectorIR.And => find(a.left()) ++ find(a.right())
        case _ => Nil
      }
      find(root)
    }
    assert(sets.map(_.ranges()) === Seq(200))
    val bytes = VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.RangeFilterFusion",
      compiled.fused.outputs.asJava, compiled.fused.inputOrdinals.size, compiled.fused.numLiterals,
      null, null, options)
    val sizes = VarkaEmitterTestSupport.methodNames(bytes).asScala.distinct
      .filter(_ != "<clinit>").map(m => m -> VarkaEmitterTestSupport.codeSize(bytes, m))
    // The loop's code does not grow with the ranges: every method is a small fraction of the
    // 8000-byte budget the tree of comparisons exceeded at 49 ranges.
    assert(sizes.forall(_._2 < 2000), sizes)
  }
}
