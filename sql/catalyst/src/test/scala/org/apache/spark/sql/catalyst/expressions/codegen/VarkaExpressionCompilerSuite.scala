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
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, Attribute, AttributeReference, CaseWhen, Cast, DateAdd, DateDiff, DateSub, DayOfWeek, EqualNullSafe, EqualTo, GreaterThan, Greatest, If, LessThan, Literal, NamedExpression, Not, Or, WeekDay}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays, ColumnRef, CompareOp, DateDiff => IRDateDiff, LiteralSlot, SubDays}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{Compare, DayOfWeek => IRDayOfWeek, Greatest => IRGreatest, IfElse, Not => IRNot, Or => IROr, WeekDay => IRWeekDay}
import org.apache.spark.sql.types.{DateType, IntegerType}

/**
 * Unit tests for [[VarkaExpressionCompiler]] (milestone 2, task 10): the recursive
 * Catalyst-to-IR compiler that both `VarkaColumnarRule` (eligibility) and
 * `VarkaKernelEvaluator` (execution) call. End-to-end coverage lives in
 * `VarkaDifferentialSuite`; here the compiled shape itself is pinned - dense input mapping,
 * literal slots deduplicated by value (what makes the emitter's CSE able to see two
 * `date_add(d, 1)` as one computation), output Spark types, the per-entry classification of
 * task 12's partial eligibility, and the shapes that decline.
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

  test("CASE WHEN right-folds into nested IfElse; no ELSE declines") {
    val expr = CaseWhen(
      Seq(
        LessThan(d, d2) -> DateAdd(d, Literal(1)),
        EqualTo(d, d2) -> DateAdd(d, Literal(2))),
      Some(d2))
    // Ineligible without task 11's recursion; now the first branch wins first, SQL's rule.
    val compiled = VarkaExpressionCompiler.compile(Seq(out(expr)), childOutput).get
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    assert(compiled.outputs === Seq(new IfElse(
      new Compare(CompareOp.LT, c0, c1),
      new AddDays(c0, new LiteralSlot(0)),
      new IfElse(
        new Compare(CompareOp.EQ, c0, c1),
        new AddDays(c0, new LiteralSlot(1)),
        c1))))
    assert(compiled.outputTypes === Seq(DateType))
    // No ELSE means a null-literal branch, which breaks the dense body's all-valid invariant.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(CaseWhen(Seq(LessThan(d, d2) -> DateAdd(d, Literal(1))), None))),
      childOutput).isEmpty)
  }

  test("n-ary greatest left-folds; connectives, NOT and date literals compile") {
    val expr = If(
      Or(Not(GreaterThan(d, d2)), EqualTo(d, Literal(19000, DateType))),
      Greatest(Seq(d, d2, DateAdd(d, Literal(19000)))),
      d2)
    val compiled = VarkaExpressionCompiler.compile(Seq(out(expr)), childOutput).get
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    // The date literal and the equal-valued day offset share one slot, by value.
    assert(compiled.literals === Seq(19000))
    assert(compiled.outputs === Seq(new IfElse(
      new IROr(
        new IRNot(new Compare(CompareOp.GT, c0, c1)),
        new Compare(CompareOp.EQ, c0, new LiteralSlot(0))),
      new IRGreatest(new IRGreatest(c0, c1), new AddDays(c0, new LiteralSlot(0))),
      c1)))
  }

  test("dayofweek and weekday compile with IntegerType outputs") {
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(DayOfWeek(d)), out(WeekDay(DateAdd(d, Literal(3))))), childOutput).get
    assert(compiled.outputs === Seq(
      new IRDayOfWeek(new ColumnRef(0)),
      new IRWeekDay(new AddDays(new ColumnRef(0), new LiteralSlot(0)))))
    assert(compiled.outputTypes === Seq(IntegerType, IntegerType))
  }

  test("task 11 declines: null-safe equality, bare boolean outputs") {
    // <=> on two nulls is true, which breaks the null-intolerant comparison rule.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(If(EqualNullSafe(d, d2), d2, DateAdd(d, Literal(1))))), childOutput).isEmpty)
    // A comparison as a projection output is a boolean column - out of scope, interior only.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(LessThan(d, d2))), childOutput).isEmpty)
  }

  test("compile is the all-entries-fused special case of compilePartial") {
    // A bare column output is never fused - it forwards - so `compile` declines the projection.
    assert(VarkaExpressionCompiler.compile(Seq(d.asInstanceOf[NamedExpression]),
      childOutput).isEmpty)
    // A forwarded entry beside a fused one: eligible partially, but not for `compile`.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, Literal(1))), out(i)), childOutput).isEmpty)
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(out(DateAdd(d, Literal(1))), out(i)), childOutput).isDefined)
    // A non-literal day offset: residual, so `compile` declines.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, i))), childOutput).isEmpty)
    // A cast in the tree (how `date_add` over a `datediff` result reaches the planner).
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(Cast(DateDiff(d, d2), DateType), Literal(1)))), childOutput).isEmpty)
    // An empty projection.
    assert(VarkaExpressionCompiler.compile(Seq.empty, childOutput).isEmpty)
    assert(VarkaExpressionCompiler.compilePartial(Seq.empty, childOutput).isEmpty)
  }

  test("compilePartial classifies fused, forwarded and residual entries in projection order") {
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(
        out(DateAdd(d, Literal(3))),
        i.asInstanceOf[NamedExpression],
        out(Add(i, Literal(1))),
        out(DateSub(d2, Literal(2)))),
      childOutput).get
    // The int column forwards - forwarding does not care about lane types - and the fused
    // indices count fused entries only.
    assert(partial.specs ===
      Seq(FusedOutput(0), ForwardedOutput(2), ResidualOutput, FusedOutput(1)))
    // The fused sub-projection covers exactly the fused entries: their trees, types, columns
    // and literals - nothing of the residual entry leaks in.
    assert(partial.fused.outputs === Seq(
      new AddDays(new ColumnRef(0), new LiteralSlot(0)),
      new SubDays(new ColumnRef(1), new LiteralSlot(1))))
    assert(partial.fused.outputTypes === Seq(DateType, DateType))
    assert(partial.fused.inputOrdinals === Seq(0, 1))
    assert(partial.fused.literals === Seq(3, 2))
  }

  test("a bare date column forwards like any other bare column") {
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(DateAdd(d, Literal(1))), d.asInstanceOf[NamedExpression]), childOutput).get
    assert(partial.specs === Seq(FusedOutput(0), ForwardedOutput(0)))
  }

  test("forwards and residuals alone are not eligible: nothing to fuse gains nothing") {
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(out(Add(i, Literal(1)))), childOutput).isEmpty)
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(d.asInstanceOf[NamedExpression], i.asInstanceOf[NamedExpression]),
      childOutput).isEmpty)
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(d.asInstanceOf[NamedExpression], out(Add(i, Literal(1)))), childOutput).isEmpty)
  }

  test("a declining entry rolls the shared tables back to their pre-entry state") {
    // The datediff entry compiles its end child - registering d2 and the literal 9 - before its
    // start child (an int column) declines the whole entry. Without the rollback, d2 and 9
    // would stay in the tables and widen the fused kernel's input set for no output.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(
        out(DateDiff(DateAdd(d2, Literal(9)), i)),
        out(DateAdd(d, Literal(1)))),
      childOutput).get
    assert(partial.specs === Seq(ResidualOutput, FusedOutput(0)))
    assert(partial.fused.inputOrdinals === Seq(0),
      "the declined entry's column registration must be rolled back")
    assert(partial.fused.literals === Seq(1),
      "the declined entry's literal registration must be rolled back")
    assert(partial.fused.outputs === Seq(new AddDays(new ColumnRef(0), new LiteralSlot(0))))
  }
}
