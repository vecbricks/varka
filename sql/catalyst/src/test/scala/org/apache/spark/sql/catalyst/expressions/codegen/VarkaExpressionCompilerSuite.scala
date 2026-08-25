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

package org.apache.spark.sql.catalyst.expressions.codegen

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Cast, DateAdd, DateDiff, DateSub, Literal, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays, ColumnRef, DateDiff => IRDateDiff, LiteralSlot, SubDays}
import org.apache.spark.sql.types.{DateType, IntegerType}

/**
 * Unit tests for [[VarkaExpressionCompiler]] (milestone 2, task 10): the recursive
 * Catalyst-to-IR compiler that both `VarkaColumnarRule` (eligibility) and
 * `VarkaKernelEvaluator` (execution) call. End-to-end coverage lives in
 * `VarkaDifferentialSuite`; here the compiled shape itself is pinned - dense input mapping,
 * literal slots deduplicated by value (what makes the emitter's CSE able to see two
 * `date_add(d, 1)` as one computation), output Spark types, and the all-or-nothing failures.
 */
class VarkaExpressionCompilerSuite extends SparkFunSuite {

  private val d = AttributeReference("d", DateType)()
  private val d2 = AttributeReference("d2", DateType)()
  private val i = AttributeReference("i", IntegerType)()
  private val childOutput: Seq[Attribute] = Seq(d, d2, i)

  private def out(e: org.apache.spark.sql.catalyst.expressions.Expression): NamedExpression =
    Alias(e, "c")()

  test("a nested chain compiles recursively with literal slots in first-occurrence order") {
    val expr = DateSub(DateAdd(d, Literal(1)), Literal(2))
    val compiled = VarkaExpressionCompiler.compile(Seq(out(expr)), childOutput).get
    assert(compiled.outputs === Seq(
      new SubDays(new AddDays(new ColumnRef(0), new LiteralSlot(0)), new LiteralSlot(1))))
    assert(compiled.outputTypes === Seq(DateType))
    assert(compiled.inputOrdinals === Seq(0))
    assert(compiled.literals === Seq(1, 2))
  }

  test("literal slots are assigned per distinct value, so equal subtrees compile equal") {
    val a = DateAdd(d, Literal(1))
    val b = DateDiff(DateAdd(d, Literal(1)), d2)
    val compiled = VarkaExpressionCompiler.compile(Seq(out(a), out(b)), childOutput).get
    val sharedNode = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    assert(compiled.outputs === Seq(
      sharedNode, new IRDateDiff(sharedNode, new ColumnRef(1))))
    assert(compiled.outputs.head === compiled.outputs(1).asInstanceOf[IRDateDiff].end(),
      "the two occurrences of date_add(d, 1) must compile to equal records or CSE cannot fire")
    assert(compiled.outputTypes === Seq(DateType, IntegerType))
    assert(compiled.literals === Seq(1))
  }

  test("input ordinals map densely in first-occurrence order") {
    // d2 (child ordinal 1) is referenced first, so it becomes kernel input 0.
    val expr = DateDiff(d2, DateAdd(d, Literal(3)))
    val compiled = VarkaExpressionCompiler.compile(Seq(out(expr)), childOutput).get
    assert(compiled.inputOrdinals === Seq(1, 0))
    assert(compiled.outputs === Seq(new IRDateDiff(
      new ColumnRef(0), new AddDays(new ColumnRef(1), new LiteralSlot(0)))))
  }

  test("uncompilable shapes fail the whole projection") {
    // A bare column output: deliberately not compiled - task 12 forwards it zero-copy instead.
    assert(VarkaExpressionCompiler.compile(Seq(d.asInstanceOf[NamedExpression]),
      childOutput).isEmpty)
    // One ineligible entry poisons the projection (all-or-nothing until task 12).
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, Literal(1))), out(i)), childOutput).isEmpty)
    // A non-literal day offset.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, i))), childOutput).isEmpty)
    // A cast in the tree (how `date_add` over a `datediff` result reaches the planner).
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(Cast(DateDiff(d, d2), DateType), Literal(1)))), childOutput).isEmpty)
    // An empty projection.
    assert(VarkaExpressionCompiler.compile(Seq.empty, childOutput).isEmpty)
  }
}
