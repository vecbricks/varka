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

import scala.jdk.CollectionConverters._

import org.apache.spark.{SparkArithmeticException, SparkFunSuite}
import org.apache.spark.sql.catalyst.analysis.BinaryArithmeticWithDatetimeResolver
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry
import org.apache.spark.sql.catalyst.expressions.{Abs, Add, AddMonths, Alias, And, Attribute, AttributeReference, CaseWhen, Cast, Coalesce, Concat, CurrentTime, DateAdd, DateAddYMInterval, DateDiff, DateFromUnixDate, DateSub, DayOfMonth, DayOfWeek, DayOfYear, Divide, EqualNullSafe, EqualTo, EvalMode, Expression, Extract, ExtractANSIIntervalDays, ExtractANSIIntervalMonths, ExtractANSIIntervalYears, GreaterThan, Greatest, HoursOfTime, If, In, InSet, IsNotNull, IsNull, LastDay, Least, LessThan, LessThanOrEqual, Literal, MakeDate, MakeTime, MakeYMInterval, MinutesOfTime, Month, Multiply, MultiplyYMInterval, NamedExpression, NextDay, Not, NumericEvalContext, Nvl, Nvl2, Or, Quarter, Remainder, SecondsOfTime, SecondsOfTimeWithFraction, Subtract, SubtractTimes, TimeAddInterval, TimeDiff, TimeExpression, TimeFromMicros, TimeFromMillis, TimeFromSeconds, TimestampAddInterval, TimeToMicros, TimeToMillis, TimeToSeconds, TimeTrunc, ToTime, TruncDate, UnaryMinus, UnixDate, Upper, WeekDay, WeekOfYear, Year, YearOfWeek}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaDerivedKind, VarkaEmitOptions, VarkaEmitterTestSupport, VarkaLoopEmitter, VarkaShapeCache, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays, AddMonths => IRAddMonths, And => IRAnd, ColumnRef, Compare, CompareOp, ConstDivide, DateDiff => IRDateDiff, DayOfMonth => IRDayOfMonth, DayOfWeek => IRDayOfWeek, DayOfWeekIso, DayOfYear => IRDayOfYear, Greatest => IRGreatest, GuardedRange, IfElse, IntArith, IntNeg, IntOp, IsNotNull => IRIsNotNull, LaneType, LastDay => IRLastDay, Least => IRLeast, LiteralSlot, MakeDate => IRMakeDate, Month => IRMonth, NarrowLane, NextDay => IRNextDay, Not => IRNot, Or => IROr, Overflow, Quarter => IRQuarter, SubDays, ThursdayOf, TruncDate => IRTruncDate, TruncDateDynamic => IRTruncDateDynamic, TruncLevel, WeekDay => IRWeekDay, WeekOfYear => IRWeekOfYear, Year => IRYear}
import org.apache.spark.sql.catalyst.expressions.objects.StaticInvoke
import org.apache.spark.sql.catalyst.optimizer.ReplaceExpressions
import org.apache.spark.sql.catalyst.plans.logical.{OneRowRelation, Project}
import org.apache.spark.sql.catalyst.util.IntervalUtils
import org.apache.spark.sql.types.{ByteType, DateType, DayTimeIntervalType, Decimal, DecimalType, IntegerType, LongType, ShortType, StringType, TimestampNTZType, TimestampType, TimeType, YearMonthIntervalType}
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.Utils

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

  /** The bound every `TIME` division states: a time of day is a count of nanoseconds in one. */
  private val nanosPerDay = 86400000000000L

  private val d = AttributeReference("d", DateType)()
  private val d2 = AttributeReference("d2", DateType)()
  private val i = AttributeReference("i", IntegerType)()
  private val sh = AttributeReference("sh", ShortType)()
  private val by = AttributeReference("by", ByteType)()
  // Task 67: one column per year-month unit. The stored value is a month count in all three,
  // so they differ only in the Spark type the analyzer reasons with and the compiler carries
  // out - which is exactly what the tests below check the compiler does not confuse.
  private val ymm = AttributeReference("ymm",
    YearMonthIntervalType(YearMonthIntervalType.MONTH, YearMonthIntervalType.MONTH))()
  private val ymy = AttributeReference("ymy",
    YearMonthIntervalType(YearMonthIntervalType.YEAR, YearMonthIntervalType.YEAR))()
  private val ym = AttributeReference("ym",
    YearMonthIntervalType(YearMonthIntervalType.YEAR, YearMonthIntervalType.MONTH))()

  private val childOutput: Seq[Attribute] = Seq(d, d2, i, sh, by)

  /** A second `IntegerType` column, for the comparisons that need two of them (task 122). */
  private val sh2 = AttributeReference("i2", IntegerType)()

  /** `childOutput` with that second int column, on its own list for the reason below. */
  private val intPairOutput: Seq[Attribute] = childOutput :+ sh2

  /**
   * `childOutput` plus the interval columns, as its own list rather than three more entries in
   * it: several suites here append their own column to `childOutput` and assert the ordinal it
   * lands on, so widening the shared list renumbers them.
   */
  private val withIntervals: Seq[Attribute] = childOutput ++ Seq(ymm, ymy, ym)

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

  test("next_day with a literal weekday compiles to a literal slot") {
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, Literal("MO"), false))), childOutput).get
    assert(compiled.outputs === Seq(new IRNextDay(new ColumnRef(0), new LiteralSlot(0))))
    assert(compiled.outputTypes === Seq(DateType))
    // MONDAY = 4 in DateTimeUtils's private weekday numbering, so k = dayOfWeek - 1 = 3.
    assert(compiled.literals === Seq(3))
    assert(compiled.derivedInputs.isEmpty)
  }

  private val dow = AttributeReference("dow", StringType)()
  private val withDow: Seq[Attribute] = childOutput :+ dow

  test("next_day with a weekday column compiles to a derived input, ANSI in its kind") {
    // Until task 59 a column weekday declined; now the column is read through a derived
    // input: the kernel input is a ColumnRef like any other, inputOrdinals names the string
    // column, and the note tells the evaluator to fill that input from it before the kernel.
    val lenient = VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, dow, false))), withDow).get
    assert(lenient.outputs === Seq(new IRNextDay(new ColumnRef(0), new ColumnRef(1))))
    assert(lenient.outputTypes === Seq(DateType))
    assert(lenient.inputOrdinals === Seq(0, 5))
    assert(lenient.literals === Nil)
    assert(lenient.derivedInputs === Seq(VarkaDerivedInput(1, 5, VarkaDerivedKind.WEEKDAY)))
    assert(lenient.derivedAt(1).isDefined && lenient.derivedAt(0).isEmpty)
    val ansi = VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, dow, true))), withDow).get
    assert(ansi.derivedInputs === Seq(VarkaDerivedInput(1, 5, VarkaDerivedKind.WEEKDAY_ANSI)))
    // The parser ignores collation, so a collated column is admitted the same way.
    val lcase = AttributeReference("lc", StringType("UTF8_LCASE"))()
    val collated = VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, lcase, false))), childOutput :+ lcase).get
    assert(collated.derivedInputs === Seq(VarkaDerivedInput(1, 5, VarkaDerivedKind.WEEKDAY)))
  }

  test("two next_day over one weekday column share one derived input, and the " +
      "date column keeps its own slot beside it") {
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, dow, false)), out(NextDay(d2, dow, false)), out(DateAdd(d, Literal(1)))),
      withDow).get
    assert(compiled.outputs === Seq(
      new IRNextDay(new ColumnRef(0), new ColumnRef(1)),
      new IRNextDay(new ColumnRef(2), new ColumnRef(1)),
      new AddDays(new ColumnRef(0), new LiteralSlot(0))))
    assert(compiled.inputOrdinals === Seq(0, 5, 1))
    assert(compiled.derivedInputs === Seq(VarkaDerivedInput(1, 5, VarkaDerivedKind.WEEKDAY)))
  }

  test("a declining entry rolls its derived input back with the plain columns") {
    // The synthetic key must obey the mark-and-truncate discipline: next_day interns the
    // leaf, then the entry declines on its other operand, and the accepted entry's plan
    // must carry neither the string column nor the note.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(DateDiff(NextDay(d, dow, false), i)), out(DateAdd(d, Literal(1)))), withDow).get
    assert(partial.declines.contains(0))
    assert(partial.fused.inputOrdinals === Seq(0))
    assert(partial.fused.derivedInputs.isEmpty)
  }

  test("a predicate over next_day with a weekday column carries the derived input") {
    val predicate = VarkaExpressionCompiler.compilePredicate(
      EqualTo(NextDay(d, dow, false), d2), withDow).get
    assert(predicate.specs.forall(_.fused))
    assert(predicate.fused.inputOrdinals === Seq(0, 5, 1))
    assert(predicate.fused.derivedInputs === Seq(VarkaDerivedInput(1, 5, VarkaDerivedKind.WEEKDAY)))
  }

  test("a weekday that is an expression over the column declines with its reason") {
    val upper = org.apache.spark.sql.catalyst.expressions.Upper(dow)
    assert(!upper.foldable)
    assert(declineReason(NextDay(d, upper, false), withDow) ===
      "next_day with a weekday that is neither a literal nor a column")
    assert(VarkaDerivedInput.key(5, VarkaDerivedKind.WEEKDAY) !==
      VarkaDerivedInput.key(5, VarkaDerivedKind.WEEKDAY_ANSI))
    assert(VarkaDerivedInput.sourceOrdinal(VarkaDerivedInput.key(7, VarkaDerivedKind.WEEKDAY_ANSI))
      === 7)
  }

  test("next_day's weekday range is [-1, 5], not [0, 6] - THURSDAY is the negative") {
    // DateTimeUtils.getDayOfWeekFromString returns [0, 6] with THURSDAY = 0, so
    // k = dayOfWeek - 1 = -1 for THURSDAY: the one weekday a naive [0, 6] assumption misses.
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, Literal("THURSDAY"), false))), childOutput).get
    assert(compiled.outputs === Seq(new IRNextDay(new ColumnRef(0), new LiteralSlot(0))))
    assert(compiled.literals === Seq(-1))
  }

  test("next_day declines cleanly on a null weekday, without crashing planning") {
    assert(VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, Literal.create(null, StringType), false))), childOutput).isEmpty)
  }

  test("next_day declines cleanly on an unrecognized weekday name") {
    assert(VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, Literal("ZZ"), false))), childOutput).isEmpty)
  }

  test("next_day declines, rather than crashes planning, when the weekday " +
      "expression itself throws on eval") {
    // A computed (not bare-Literal) foldable expression whose eval() throws for a reason
    // that has nothing to do with the weekday name - forcing ANSI's divide-by-zero error
    // via an explicit NumericEvalContext so this does not depend on session configuration.
    val throwsOnEval = Divide(
      Literal(1.0), Literal(0.0), NumericEvalContext(EvalMode.ANSI))
    assert(throwsOnEval.foldable)
    assert(VarkaExpressionCompiler.compile(
      Seq(out(NextDay(d, throwsOnEval, false))), childOutput).isEmpty)
  }

  test("the four calendar extractions compile with IntegerType outputs") {
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(Year(d)), out(Month(d)), out(DayOfMonth(d)), out(Quarter(DateAdd(d, Literal(3))))),
      childOutput).get
    assert(compiled.outputs === Seq(
      new IRYear(new ColumnRef(0)),
      new IRMonth(new ColumnRef(0)),
      new IRDayOfMonth(new ColumnRef(0)),
      new IRQuarter(new AddDays(new ColumnRef(0), new LiteralSlot(0)))))
    assert(compiled.outputTypes === Seq(IntegerType, IntegerType, IntegerType, IntegerType))
  }

  test("dayofyear compiles with an IntegerType output") {
    val compiled = VarkaExpressionCompiler.compile(Seq(out(DayOfYear(d))), childOutput).get
    assert(compiled.outputs === Seq(new IRDayOfYear(new ColumnRef(0))))
    assert(compiled.outputTypes === Seq(IntegerType))
  }

  test("make_date compiles over int columns and literals in either mode, with a " +
      "DateType output, and a non-int argument declines with its position") {
    val y = AttributeReference("y", IntegerType)()
    val m = AttributeReference("m", IntegerType)()
    val dd = AttributeReference("dd", IntegerType)()
    val ints: Seq[Attribute] = Seq(d, y, m, dd)
    for (ansi <- Seq(false, true)) {
      val compiled = VarkaExpressionCompiler.compile(
        Seq(out(MakeDate(y, m, dd, ansi)), out(MakeDate(y, Literal(2), Literal(29), ansi))),
        ints).get
      assert(compiled.outputs === Seq(
        new IRMakeDate(new ColumnRef(0), new ColumnRef(1), new ColumnRef(2), ansi),
        new IRMakeDate(new ColumnRef(0), new LiteralSlot(0), new LiteralSlot(1), ansi)))
      assert(compiled.outputTypes === Seq(DateType, DateType))
      assert(compiled.inputOrdinals === Seq(1, 2, 3))
      assert(compiled.literals === Seq(2, 29))
    }
    // The two modes are two shapes: the same tree under each renders differently.
    assert(VarkaVectorIR.canonical(new IRMakeDate(new ColumnRef(0), new ColumnRef(1),
      new ColumnRef(2), true)) !== VarkaVectorIR.canonical(new IRMakeDate(new ColumnRef(0),
      new ColumnRef(1), new ColumnRef(2), false)))
    // compilePartial answers None when nothing fuses, so a fused sibling rides along. The
    // declining operand is a date, not an int expression: since task 63 widened this position
    // to any `IntegerType` tree, only the wrong *type* still reaches the position-named reason.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(MakeDate(y, m, d, false)), out(Year(d))), ints).get
    assert(partial.declines(0).reason === "make_date's day is not an int column or literal")
    // And the widening itself: arithmetic over the operands fuses, as one tree.
    val overArithmetic = VarkaExpressionCompiler.compile(
      Seq(out(MakeDate(Add(y, Literal(1), EvalMode.LEGACY), m, dd, false))), ints).get
    assert(overArithmetic.outputs === Seq(new IRMakeDate(
      new IntArith(IntOp.ADD, Overflow.WRAP, new ColumnRef(0), new LiteralSlot(0)),
      new ColumnRef(1), new ColumnRef(2), false)))
    // Under the range analysis the node is a bounded producer: a calendar node over it fuses.
    assert(VarkaExpressionCompiler.compile(Seq(out(Year(MakeDate(y, m, dd, false)))), ints)
      .isDefined)
  }

  test("weekofyear compiles to the week tail over the Thursday shift, IntegerType") {
    val compiled = VarkaExpressionCompiler.compile(Seq(out(WeekOfYear(d))), childOutput).get
    assert(compiled.outputs === Seq(new IRWeekOfYear(new ThursdayOf(new ColumnRef(0)))))
    assert(compiled.outputTypes === Seq(IntegerType))
  }

  test("extract(WEEK FROM d) resolves to the same node, and two weekofyear outputs " +
      "over one date are one tree under CSE") {
    // Extract desugars WEEK, W and WEEKS to WeekOfYear before the compiler sees it; the
    // compiler is not asked to know the spellings. Two entries build the same pair, which the
    // emitter's CSE computes once - the compiler's job is only to build equal trees.
    val extracted = Extract(Literal("WEEK"), d, WeekOfYear(d))
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(extracted), out(WeekOfYear(d))), childOutput).get
    val pair = new IRWeekOfYear(new ThursdayOf(new ColumnRef(0)))
    assert(compiled.outputs === Seq(pair, pair))
  }

  test("a fused int field compared with an int literal is a predicate, and an int " +
      "literal is still not a value operand") {
    val week53 = VarkaExpressionCompiler.compilePredicate(
      EqualTo(WeekOfYear(d), Literal(53)), childOutput)
    assert(week53.isDefined, "weekofyear(d) = 53 should compile")
    assert(week53.get.specs.forall(_.fused))
    val month = VarkaExpressionCompiler.compilePredicate(
      GreaterThan(Literal(6), Month(d)), childOutput)
    assert(month.isDefined && month.get.specs.forall(_.fused))
    // The value side is unchanged: an int literal where a date is expected declines.
    assert(VarkaExpressionCompiler.compile(Seq(out(DateAdd(Literal(5), Literal(3)))),
      childOutput).isEmpty)
  }

  test("a bare int column compares in the kernel, alone and as a conjunct") {
    // Task 122. `compare` used to send every non-literal operand through `compileNode`, whose
    // value leaves are date columns and fused int fields, so a bare `IntegerType` column
    // declined with "not a date column" and left a residual row filter above the fused part -
    // which is what task 120's coverage suite found when it began requiring every conjunct of
    // a predicate row to fuse. The int32 lane already owned the column: arithmetic reads it
    // through `intOperand`, and a comparison needs no overflow mode because it yields a mask.
    for ((name, predicate) <- Seq(
        "i > 0" -> GreaterThan(i, Literal(0)),
        "i = 5" -> EqualTo(i, Literal(5)),
        "i < i2" -> LessThan(i, sh2),
        "0 < i" -> LessThan(Literal(0), i),
        "month(d) > i" -> GreaterThan(Month(d), i),
        "i <= month(d)" -> LessThanOrEqual(i, Month(d)))) {
      val compiled = VarkaExpressionCompiler.compilePredicate(predicate, intPairOutput)
      assert(compiled.isDefined, s"$name should compile")
      assert(compiled.get.specs.forall(_.fused), s"$name should fuse")
    }
    // The whole conjunct fuses now, so nothing is left for a row filter above the kernel.
    val conjunct = VarkaExpressionCompiler.compilePredicate(
      And(EqualTo(Year(d), Literal(2021)), GreaterThan(i, Literal(0))), intPairOutput)
    assert(conjunct.isDefined && conjunct.get.specs.forall(_.fused),
      "year(d) = 2021 AND i > 0 should fuse whole")
    assert(conjunct.get.specs.size === 2 && conjunct.get.fusedConjuncts.size === 2,
      "both conjuncts belong to the kernel, not one fused and one residual")
    assert(conjunct.get.fused.outputs.head.isInstanceOf[IRAnd],
      "and they are one fused condition rather than two kernels")
  }

  test("an int column's validity predicate fuses, because the optimizer infers it") {
    // Task 122's second half. Spark infers `isnotnull(i)` beside any null-intolerant predicate
    // on `i`, so admitting the comparison without admitting this would leave a row filter over
    // every fused int comparison - the kernel doing the compare and the row engine still
    // visiting every row for the null. The word is the same one a date column's validity reads.
    val alone = VarkaExpressionCompiler.compilePredicate(IsNotNull(i), childOutput)
    assert(alone.isDefined && alone.get.specs.forall(_.fused), "i IS NOT NULL should fuse")
    val inferred = VarkaExpressionCompiler.compilePredicate(
      And(IsNotNull(i), GreaterThan(i, Literal(0))), childOutput)
    assert(inferred.isDefined && inferred.get.specs.forall(_.fused),
      "the shape the optimizer actually produces should fuse whole")
    // Still a bare column only: a validity predicate over a computed node declines as before.
    assert(VarkaExpressionCompiler.compilePredicate(
      IsNotNull(Month(d)), childOutput).isEmpty)
  }

  test("a comparison over a column the lane does not hold still declines") {
    // The rule admits `IntegerType` and nothing else: a short or a byte column is a narrower
    // lane the kernel does not read, and admitting one here would compare whatever the
    // evaluator happened to place in the int column beside it.
    for ((name, predicate) <- Seq(
        "sh > 0" -> GreaterThan(sh, Literal(0)),
        "by > 0" -> GreaterThan(by, Literal(0)))) {
      assert(VarkaExpressionCompiler.compilePredicate(predicate, childOutput).isEmpty,
        s"$name should decline")
    }
  }

  test("extract(YEAROFWEEK) compiles to Year over the Thursday shift and shares the " +
      "shift with weekofyear over the same date") {
    val viaExtract = Extract(Literal("YEAROFWEEK"), d, YearOfWeek(d))
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(viaExtract), out(WeekOfYear(d))), childOutput).get
    val thursday = new ThursdayOf(new ColumnRef(0))
    assert(compiled.outputs === Seq(new IRYear(thursday), new IRWeekOfYear(thursday)))
    assert(compiled.outputTypes === Seq(IntegerType, IntegerType))
    // The same admission as weekofyear's: three days short of a bare date's last shift.
    val shiftHiWeek = VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS - VarkaChrono.CONTRACT_MAX_DAYS - 3
    assert(VarkaExpressionCompiler.compile(
      Seq(out(YearOfWeek(DateAdd(d, Literal(shiftHiWeek))))), childOutput).isDefined)
    assert(VarkaExpressionCompiler.compile(
      Seq(out(YearOfWeek(DateAdd(d, Literal(shiftHiWeek + 1))))), childOutput).isEmpty)
  }

  test("the range analysis bounds the Thursday shift at three days either way") {
    // weekofyear over a date shifted to the last three days the analysis admits fuses; one more
    // day and the Thursday of the shifted day can leave the calendar range, so it declines.
    val shiftHiWeek = VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS - VarkaChrono.CONTRACT_MAX_DAYS - 3
    assert(VarkaExpressionCompiler.compile(
      Seq(out(WeekOfYear(DateAdd(d, Literal(shiftHiWeek))))), childOutput).isDefined)
    assert(VarkaExpressionCompiler.compile(
      Seq(out(WeekOfYear(DateAdd(d, Literal(shiftHiWeek + 1))))), childOutput).isEmpty)
  }

  test("extract(DAYOFWEEK_ISO) compiles to DayOfWeekIso in either operand order, and " +
      "no other Add does") {
    // Through Extract itself, so the assertion is on the analyzer's spelling as much as on
    // the arm; the reversed order by hand, since the arm accepts it too.
    val viaExtract = Extract(Literal("DAYOFWEEK_ISO"), d, Add(WeekDay(d), Literal(1)))
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(viaExtract), out(Add(Literal(1), WeekDay(d)))), childOutput).get
    val node = new DayOfWeekIso(new ColumnRef(0))
    assert(compiled.outputs === Seq(node, node))
    assert(compiled.outputTypes === Seq(IntegerType, IntegerType))
    // Since task 63 these do compile - as int arithmetic over a fused field - so the claim
    // this test defends is narrower than "nothing else fuses": no other Add becomes the
    // dedicated DayOfWeekIso node, which is what would silently change the value.
    for (other <- Seq(Add(WeekDay(d), Literal(2)), Add(DayOfWeek(d), Literal(1)),
        Add(DateDiff(d, d2), Literal(1)))) {
      val outs = VarkaExpressionCompiler.compile(Seq(out(other)), childOutput).get.outputs
      assert(!outs.exists(_.isInstanceOf[DayOfWeekIso]), other)
      assert(outs.forall(_.isInstanceOf[IntArith]), other)
    }
  }

  test("last_day compiles with a DateType output, unlike its four siblings") {
    val compiled = VarkaExpressionCompiler.compile(Seq(out(LastDay(d))), childOutput).get
    assert(compiled.outputs === Seq(new IRLastDay(new ColumnRef(0))))
    assert(compiled.outputTypes === Seq(DateType))
  }

  test("add_months and date +- INTERVAL n MONTH/YEAR compile to the same node") {
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(AddMonths(d, Literal(3))),
        out(DateAddYMInterval(d, Literal.create(-5, YearMonthIntervalType())))),
      childOutput).get
    assert(compiled.outputs === Seq(
      new IRAddMonths(new ColumnRef(0), new LiteralSlot(0)),
      new IRAddMonths(new ColumnRef(0), new LiteralSlot(1))))
    assert(compiled.literals === Seq(3, -5))
    assert(compiled.outputTypes === Seq(DateType, DateType))
  }

  test("declines a literal month count past the magic's range") {
    assert(VarkaExpressionCompiler.compile(
      Seq(out(AddMonths(d, Literal(VarkaChrono.MONTH_ARITH_MAX_MONTHS + 1)))),
      childOutput).isEmpty)
    assert(VarkaExpressionCompiler.compile(
      Seq(out(AddMonths(d, Literal(VarkaChrono.MONTH_ARITH_MIN_MONTHS - 1)))),
      childOutput).isEmpty)
    // The bound itself still compiles - it is the largest literal covered, not the smallest
    // one declined.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(AddMonths(d, Literal(VarkaChrono.MONTH_ARITH_MAX_MONTHS)))), childOutput).isDefined)
  }

  // Task 60: add_months' month count widened to a column, the way task 38 widened date_add's
  // day offset - a runtime guard on the count takes over from the compile-time bound above.

  test("add_months(d, i) and d + CAST(i AS INTERVAL MONTH) compile to the same " +
      "column-count node, with no literal slot") {
    val monthInterval = YearMonthIntervalType(YearMonthIntervalType.MONTH,
      YearMonthIntervalType.MONTH)
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(AddMonths(d, i)), out(DateAddYMInterval(d, Cast(i, monthInterval)))),
      childOutput).get
    val shared = new IRAddMonths(new ColumnRef(0), new ColumnRef(1))
    assert(compiled.outputs === Seq(shared, shared))
    assert(compiled.inputOrdinals === Seq(0, 2))
    assert(compiled.literals === Nil)
    assert(compiled.outputTypes === Seq(DateType, DateType))
  }

  test("declines a further-folded count and a widened one, each with its own reason") {
    // The YEAR-end interval cast and the negated MONTH cast used to decline here. Task 68
    // widened the emitter's month-count position to hold task 63's arithmetic, so the first now
    // declines only when its multiply is unbounded and the second fuses outright; both moved to
    // that task's tests rather than being deleted, and this comment is the pointer, since a
    // reader chasing "why did add_months stop declining these" should land somewhere.
    //
    // A column read through further arithmetic is not the bare-column or bare-cast shape
    // compileMonths matches; it is not foldable either, so it declines the same way.
    assert(declineReason(AddMonths(d, Add(i, Literal(1)))) ===
      "month count is neither a foldable literal nor an integer column")
    // The stored year-month interval column used to decline here, because isArrowBacked would
    // not read an IntervalYearVector. Task 67 admits it end to end, so the case moved to that
    // task's own test rather than being deleted: this comment is the pointer, since a reader
    // chasing "why did add_months stop declining an interval" should land somewhere.
    // A Short/Byte count: AddMonths.inputTypes demands IntegerType exactly, so the analyzer
    // wraps the column in a widening cast and the bare-column arm never sees it. The reason
    // names the column's real type rather than reporting it as "neither literal nor column",
    // which is what a reader chasing why add_months(d, smallint) will not fuse needs to see.
    val s = AttributeReference("s", ShortType)()
    assert(declineReason(AddMonths(d, Cast(s, IntegerType)), childOutput :+ s) ===
      "month count column of type smallint reaches the compiler behind a widening cast; " +
        "the int32 lanes read only an integer column")
  }

  test("a column count composes with dayRange like a literal count does") {
    // year(add_months(d, m)) fuses: a column count is Bounded by the emitter's own runtime
    // guard (task 60's correction to PLAN_MILESTONE_4.md 2.27), not left unbounded.
    assert(fuses(Year(AddMonths(d, i))))
    // The bound composes in both directions: a date already shifted to the edge of add_months'
    // own guarded range, plus the guarded range itself, still leaves the narrowed range by one
    // day. The two extremes are different magnitudes (31 * MIN is not -(31 * MAX)), so each is
    // derived from its own constant rather than retyped - a copy of the MAX magnitude into the
    // low arm would under-approximate the backward shift, which is the corrupting direction.
    val atHi = DateAdd(d, Literal((shiftHi - 31L * VarkaChrono.MONTH_ARITH_MAX_MONTHS).toInt))
    assert(fuses(Year(AddMonths(atHi, i))))
    assert(!ir(Year(AddMonths(atHi, i))).contains("guardedDay"),
      "a shape already inside the range must not gain a check")
    val atLo = DateSub(d, Literal((31L * VarkaChrono.MONTH_ARITH_MIN_MONTHS - shiftLo).toInt))
    assert(fuses(Year(AddMonths(atLo, i))))
    assert(!ir(Year(AddMonths(atLo, i))).contains("guardedDay"))
    // One day past either edge the interval leaves the range - and since task 93 the shape is
    // re-armed rather than declined, because what carries it out is `add_months`' own column
    // count, whose worst case most batches do not reach. The bound arithmetic above is what
    // decides *where* the check goes, so it is still what these two lines pin; they assert the
    // check's presence now instead of a decline.
    assert(ir(Year(AddMonths(DateAdd(atHi, Literal(1)), i))).contains("guardedDay"))
    assert(ir(Year(AddMonths(DateSub(atLo, Literal(1)), i))).contains("guardedDay"))
  }

  test("a column count over a column day offset is re-armed, not declined") {
    // The day-offset guard bounds date_add's result to [NARROW_MIN_DAYS, NARROW_MAX_DAYS], and
    // add_months can then move it 2047 years further, where `narrowed` is undefined - so the
    // day the calendar tail decomposes is out of range. Task 60 recorded that dayRange must
    // widen the guarded producer's interval by the shift above it and decline, "rather than
    // treat the subtree as unbounded-but-guarded and admit on the producer's promise", and it
    // was right: admitting on the promise is a wrong answer.
    //
    // Task 93 admits it on something else - a second check, inserted where the interval ran
    // out, which makes the day the calendar tail decomposes in range as a fact rather than as
    // a promise. The distinction the comment above draws is exactly the one that makes this
    // safe, so it is quoted rather than deleted.
    assert(fuses(Year(AddMonths(DateAdd(d, i), i))))
    assert(ir(Year(AddMonths(DateAdd(d, i), i))) ===
      "(year (guardedDay (addMonths (addDays col:0 col:1) col:1)))",
      "the check belongs between the shift that overflowed and the node that decomposes")
    // The sibling arms take the same path. `trunc` shifts backward by a literal 365, so its
    // own shift is not runtime-valued and it still declines - the asymmetry task 93 turns on.
    assert(!fuses(Year(TruncDate(DateAdd(d, i), Literal("YEAR")))))
    // A guarded producer directly under the calendar node is admitted with no second check:
    // its guarded result is already exactly the range the lowering covers.
    assert(fuses(Year(DateAdd(d, i))))
    assert(!ir(Year(DateAdd(d, i))).contains("guardedDay"))
  }

  test("an upward shift over a guarded day offset fuses again, and the downward " +
      "siblings still do not") {
    // Written by task 60's review as four pinned declines, with the note that flipping them
    // was the follow-up's deliverable. This is that flip, and three of the four take it; the
    // fourth is below, with its own reason. The declines were correct and over-conservative:
    // each shifts the guarded producer's result *upward*, and the civil-from-days lowering
    // does not stop being exact at NARROW_MAX_DAYS - that constant is the ceiling of the era
    // step's `w < 2 ^ NARROW_ERA_K` shift domain, not of the decomposition. dayRange had only
    // the one constant, so it declined them.
    //
    // Task 69 measured where the decomposition does stop being exact - past the multiply's own
    // overflow too, because `eraOf`'s one-era correction carries it further - and gave the
    // upward direction its own bound. `NARROW_MAX_DAYS` still governs what a runtime guard
    // enforces on a producer's own result; `NARROW_DECOMPOSE_MAX_DAYS` governs what an
    // intermediate may reach and still decompose.
    assert(fuses(Year(LastDay(DateAdd(d, i)))))
    assert(fuses(Year(NextDay(DateAdd(d, i), Literal("MON")))))
    assert(fuses(Year(DateAdd(DateAdd(d, i), Literal(5)))))
    // The fourth does not flip, and PLAN_TASK_69.md 1 says why while listing it: `ThursdayOf`
    // shifts +-3, so only its +3 side is this task's to recover. Its -3 side reaches below
    // NARROW_MIN_DAYS, where the lowering is undefined, and a shape declines on the union of
    // its directions - so loosening the upward bound alone cannot admit it.
    assert(!fuses(WeekOfYear(DateAdd(d, i))))
    // The downward siblings must keep declining whatever that follow-up does: below
    // NARROW_MIN_DAYS the lowering really is undefined, and trunc over a column offset was
    // answering wrongly before this fix, on master too.
    assert(!fuses(Year(TruncDate(DateAdd(d, i), Literal("MONTH")))))
    assert(!fuses(Year(DateSub(DateAdd(d, i), Literal(5)))))
  }

  test("a runtime shift that runs out of range is re-armed, a literal one declines") {
    // The distinction the whole task turns on. Both shapes leave the range; only one of them
    // leaves it for a reason a runtime check can rescue.
    //
    // add_months' column count can move a guarded day 2047 years, but that is a worst case
    // most batches do not reach, so a check above it fuses the shape and reports the batches
    // that do. A literal shift moves every row by the same amount, so a check above *it* would
    // report every batch - a slower way to decline than declining here, for free.
    assert(fuses(Year(AddMonths(DateAdd(d, i), i))))
    assert(!fuses(Year(DateAdd(DateAdd(d, i), Literal(20000000)))))
    assert(!fuses(Year(DateAdd(d, Literal(20000000)))))
    // The trap the first version of the rule fell into: it asked "did any runtime value
    // contribute below", saw the inner column offset, and admitted the literal shape above.
    assert(declineReason(Year(DateAdd(DateAdd(d, i), Literal(20000000))))
      .startsWith("day range ["))
  }

  test("the check is re-armed as often as the range runs out") {
    // A rule phrased around one node cannot express this, which is why the plan's first draft
    // said "guard the outermost producer" and this test exists: after the first check resets
    // the interval to the narrowed range, `last_day` and a second column count carry it out
    // again, and a shape with one check would decompose a day nothing bounded.
    val twice = DayOfYear(AddMonths(LastDay(AddMonths(DateAdd(d, i), i)), i))
    assert(fuses(twice))
    val rendered = ir(twice)
    assert(rendered.split("guardedDay", -1).length - 1 === 2,
      s"expected two checks, got: $rendered")
    // And where one is enough, exactly one is emitted.
    val once = Year(AddMonths(DateAdd(d, i), i))
    assert(ir(once).split("guardedDay", -1).length - 1 === 1, ir(once))
  }

  test("nothing that fuses today gains a check") {
    // The cost side. Every shape here was admitted before the task and must be emitted exactly
    // as it was - the interval never runs out, so there is nothing to re-arm.
    Seq[Expression](
      Year(d),
      Year(DateAdd(d, i)),
      Year(DateSub(d, i)),
      Year(AddMonths(d, i)),
      Year(LastDay(DateAdd(d, i))),
      Year(NextDay(DateAdd(d, i), Literal("MON"))),
      Year(DateAdd(d, Literal(shiftHi)))).foreach { e =>
      assert(fuses(e), s"$e should fuse")
      assert(!ir(e).contains("guardedDay"), s"$e gained a check it does not need: ${ir(e)}")
    }
  }

  test("the new ceiling is where the upward shift stops, to the day") {
    // The headroom the task bought, stated as the number of days a guarded producer's result
    // may be shifted up and still decompose. Derived from the two constants, never retyped:
    // the guard leaves the producer in [NARROW_MIN_DAYS, NARROW_MAX_DAYS], so a `+k` above it
    // reaches NARROW_MAX_DAYS + k, and the admission check now compares that against
    // NARROW_DECOMPOSE_MAX_DAYS rather than against NARROW_MAX_DAYS.
    val headroom = VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS - VarkaChrono.NARROW_MAX_DAYS
    assert(headroom > 0, "task 69 rests on the decomposition outliving the guards' range")
    assert(fuses(Year(DateAdd(DateAdd(d, i), Literal(headroom)))))
    assert(declineReason(Year(DateAdd(DateAdd(d, i), Literal(headroom + 1)))) ===
      s"day range [${VarkaChrono.NARROW_MIN_DAYS + headroom + 1}, " +
        s"${VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS + 1}] leaves the calendar lowering's range")
    // Downward the guard's own constant still binds, and one day is enough to show it: the
    // check is asymmetric on purpose, NARROW_MIN_DAYS on the low side unchanged.
    assert(!fuses(Year(DateSub(DateAdd(d, i), Literal(1)))))
    // The two constants are also the two directions of a single shape: `next_day` shifts
    // +1..7, which fits, while the same producer under `trunc` reaches -365 and does not.
    assert(fuses(Year(NextDay(DateAdd(d, i), Literal("MON")))))
    assert(!fuses(Year(TruncDate(DateAdd(d, i), Literal("YEAR")))))
  }

  // Task 52's compile-time range guard. `HI` and `LO` are the largest literal shifts that keep
  // a contract column inside the narrowed range, derived from the constants rather than
  // retyped, so the tests below sit at +-1 of the real bound whatever it is.
  //
  // The two directions take different constants since task 69. Downward the narrowing is
  // undefined below `NARROW_MIN_DAYS` and nothing rescues it. Upward the binding limit is how
  // far the decomposition stays exact on a value already in hand, which is further than the
  // era step's shift domain - so `shiftHi` derives from `NARROW_DECOMPOSE_MAX_DAYS` and the
  // guards keep enforcing `NARROW_MAX_DAYS` on a producer's own result.
  private val shiftHi = VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS - VarkaChrono.CONTRACT_MAX_DAYS
  private val shiftLo = VarkaChrono.NARROW_MIN_DAYS - VarkaChrono.CONTRACT_MIN_DAYS

  private def fuses(e: Expression, output: Seq[Attribute] = childOutput): Boolean =
    VarkaExpressionCompiler.compile(Seq(out(e)), output).isDefined

  /** The compiled IR of a single output, rendered canonically - for asserting what is in it. */
  private def ir(e: Expression, output: Seq[Attribute] = childOutput): String = {
    val compiled = VarkaExpressionCompiler.compile(Seq(out(e)), output)
    assert(compiled.isDefined, s"$e declined; expected it to fuse")
    VarkaVectorIR.canonical(compiled.get.outputs.head)
  }

  // -------------------------------------------------------------------------------------------
  // Task 102: the StaticInvoke table, which is how any TIME expression is recognised at all.
  // -------------------------------------------------------------------------------------------

  /** The nine and the SQL that produces each, so the table's coverage is checked against a list. */
  private val everyTimeExpression: Seq[(Expression, String)] = {
    val t = Literal.create(0L, TimeType(TimeType.MICROS_PRECISION))
    val i = Literal.create(0, IntegerType)
    val dec = Literal.create(Decimal(0), DecimalType(16, 6))
    val dti = Literal.create(0L, DayTimeIntervalType())
    val unit = Literal.create(UTF8String.fromString("HOUR"), StringType)
    val l = Literal.create(0L, LongType)
    Seq(
      HoursOfTime(t) -> "hour(t)",
      MinutesOfTime(t) -> "minute(t)",
      SecondsOfTime(t) -> "second(t)",
      SecondsOfTimeWithFraction(t) -> "second(t) with its fraction",
      MakeTime(i, i, dec) -> "make_time",
      TimeTrunc(unit, t) -> "time_trunc",
      SubtractTimes(t, t) -> "t1 - t2",
      TimeDiff(unit, t, t) -> "timediff",
      TimeAddInterval(t, dti) -> "t + interval",
      TimeToSeconds(t) -> "time_to_seconds",
      TimeToMillis(t) -> "time_to_millis",
      TimeToMicros(t) -> "time_to_micros",
      TimeFromSeconds(l) -> "time_from_seconds",
      TimeFromMillis(l) -> "time_from_millis",
      TimeFromMicros(l) -> "time_from_micros")
  }

  /**
   * The list above against the registry, so a TIME function Spark adds cannot stay out of the
   * table unnoticed: every built-in function whose expression class is a `TimeExpression` must
   * be represented in `everyTimeExpression`, and so in the table, by an instance of its class.
   * Section 9.3 of `PLAN_TASK_102.md` is what this guards against - five conversions that were
   * in the registry and declined as `unsupported expression` because the table's own list did
   * not know them.
   */
  test("every TIME function in the registry is in the table's list") {
    val registered = FunctionRegistry.builtin.listFunction().flatMap { name =>
      FunctionRegistry.builtin.lookupFunction(name).map(_.getClassName)
    }.distinct.filter { className =>
      classOf[TimeExpression].isAssignableFrom(Utils.classForName(className))
    }.toSet
    val listed = everyTimeExpression.map(_._1.getClass.getName).toSet
    // The two the table has no business with, named so a third cannot join them unnoticed:
    // `current_time` is a per-query constant the optimizer folds before any projection reaches
    // the compiler, and `to_time` parses a string, which no lane holds and which the leaf
    // declines as a non-date column - neither is a StaticInvoke over a TIME value.
    val outsideTheTableByDesign = Set(classOf[CurrentTime], classOf[ToTime]).map(_.getName)
    assert(registered -- listed === outsideTheTableByDesign,
      "TIME functions in the registry that the table's list does not hold, beyond the two " +
        "named here")
  }

  /**
   * The guard the whole mechanism rests on: the table must describe what the *optimizer*
   * produces, not what this suite constructs.
   *
   * <p>Every `TIME` expression is `RuntimeReplaceable`, so `ReplaceExpressions` rewrites it into
   * a `StaticInvoke` long before physical planning and the compiler never sees the original
   * class. If upstream renames a `DateTimeUtils` helper, or replaces one of these with something
   * that is not a `StaticInvoke`, the table stops matching - and the only symptom in production
   * would be a kernel that quietly stopped fusing. This runs the real rewrite rule and requires
   * the result to be a key the table holds.
   */
  test("the table matches what ReplaceExpressions actually produces, for every TIME expression") {
    everyTimeExpression.foreach { case (expr, label) =>
      val rewritten = ReplaceExpressions(Project(Seq(out(expr)), OneRowRelation()))
        .asInstanceOf[Project].projectList.head.asInstanceOf[Alias].child
      rewritten match {
        case si: StaticInvoke =>
          // The pair is not bound to a val: `Class[_]` in a tuple infers an existential the
          // compiler refuses without the language import, and the table's own key type is the
          // one that matters.
          assert(
            VarkaTimeCompiler.timeTargets.contains((si.staticObject, si.functionName)),
            s"$label rewrote to ${si.staticObject.getName}.${si.functionName}, which the table " +
              "does not hold - a rename upstream, or a new expression")
        case other =>
          fail(s"$label no longer rewrites to a StaticInvoke but to " +
            other.getClass.getSimpleName)
      }
    }
  }

  test("the table holds every TIME expression and keys them distinctly") {
    // Coverage in both directions: nothing in the list is missing from the table, and no two
    // expressions share a key - a collision would silently make one of them report as the other.
    assert(VarkaTimeCompiler.timeTargets.size === everyTimeExpression.size,
      "the table and the list of TIME expressions disagree in size, so one has a duplicate key")
  }

  test("t1 - t2 and time_diff lower to a subtraction and a constant division on the long lane") {
    // DateTimeUtils.subtractTimes is `(end - start) / NANOS_PER_MICROS` and timeDiff is the
    // same over the unit's nanoseconds. Both operands are nanoseconds of day, below 2^47, so
    // the subtraction cannot overflow and wraps, and the dividend is far inside the double
    // route's exact range - the bound is the type's, not the data's, and no per-batch check
    // is registered. The end operand is compiled first, which is why it takes input 0.
    val sub = VarkaExpressionCompiler.compile(Seq(out(SubtractTimes(t6, t3))), withLong).get
    assert(sub.outputs === Seq(new ConstDivide(
      new IntArith(IntOp.SUB, Overflow.WRAP, longCol, longCol2), 1000L, nanosPerDay)))
    assert(sub.inputOrdinals === Seq(8, 7))
    assert(sub.outputTypes === Seq(DayTimeIntervalType(DayTimeIntervalType.HOUR,
      DayTimeIntervalType.SECOND)))
    assert(sub.lane === LaneType.LONG)
    val diff = VarkaExpressionCompiler.compile(
      Seq(out(TimeDiff(Literal("hour"), t3, t6))), withLong).get
    assert(diff.outputs === Seq(new ConstDivide(
      new IntArith(IntOp.SUB, Overflow.WRAP, longCol, longCol2), 3600000000000L, nanosPerDay)))
    assert(diff.inputOrdinals === Seq(8, 7))
    assert(diff.outputTypes === Seq(LongType))
    // The unit is read the way DateTimeUtils reads it: case-insensitively.
    val upper = VarkaExpressionCompiler.compile(
      Seq(out(TimeDiff(Literal("MILLISECOND"), t3, t6))), withLong).get
    assert(upper.outputs.head.asInstanceOf[ConstDivide].divisor() === 1000000L)
  }

  test("time_trunc lowers to a division and a multiply by the level's nanoseconds") {
    // `truncatedTo(unit)` on a non-negative nanosecond count is `(n / u) * u`; the multiply's
    // product is at most the dividend, so it wraps without ever needing to. The level becomes
    // both the division's shape constant and a literal slot for the multiply.
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(TimeTrunc(Literal("MINUTE"), t6))), withLong).get
    assert(compiled.outputs === Seq(new IntArith(IntOp.MUL, Overflow.WRAP,
      new ConstDivide(longCol, 60000000000L, nanosPerDay), longSlot(0))))
    assert(compiled.longLiterals === Seq(60000000000L))
    assert(compiled.inputOrdinals === Seq(8))
    assert(compiled.outputTypes === Seq(TimeType(6)))
  }

  test("hour, minute and second lower to long-lane divisions under a narrowing root") {
    // Group C's route A (`PLAN_TASK_102.md` 8.3): the three extracts are `LocalTime` field
    // reads on the row engine and constant divisions of the nanoseconds of day here, `minute`
    // and `second` taking the remainder of a further division by sixty. Each is an int column
    // computed in the long lane, so the kernel is a long-lane one whose output type is an int,
    // and the narrowing root is what tells the store so.
    val hour = VarkaExpressionCompiler.compile(Seq(out(HoursOfTime(t6))), withLong).get
    assert(hour.outputs === Seq(
      new NarrowLane(new ConstDivide(longCol, 3600000000000L, nanosPerDay))))
    assert(hour.lane === LaneType.LONG)
    assert(hour.outputTypes === Seq(IntegerType))
    assert(hour.inputOrdinals === Seq(8))
    def remainderOfSixty(x: ConstDivide): VarkaVectorIR =
      new IntArith(IntOp.SUB, Overflow.WRAP, x,
        new IntArith(IntOp.MUL, Overflow.WRAP,
          new ConstDivide(x, 60L, x.dividendBound() / math.abs(x.divisor())), longSlot(0)))
    val minute = VarkaExpressionCompiler.compile(Seq(out(MinutesOfTime(t6))), withLong).get
    assert(minute.outputs === Seq(new NarrowLane(
      remainderOfSixty(new ConstDivide(longCol, 60000000000L, nanosPerDay)))))
    assert(minute.longLiterals === Seq(60L))
    assert(minute.outputTypes === Seq(IntegerType))
    val second = VarkaExpressionCompiler.compile(Seq(out(SecondsOfTime(t6))), withLong).get
    assert(second.outputs === Seq(new NarrowLane(
      remainderOfSixty(new ConstDivide(longCol, 1000000000L, nanosPerDay)))))
    assert(second.lane === LaneType.LONG)
    // A narrowed root beside a wide one is one kernel: both are computed in the long lane, and
    // the output types say which store each takes.
    val both = VarkaExpressionCompiler.compile(
      Seq(out(HoursOfTime(t6)), out(TimeTrunc(Literal("HOUR"), t6))), withLong).get
    assert(both.lane === LaneType.LONG)
    assert(both.outputTypes === Seq(IntegerType, TimeType(6)))
  }

  test("an extract under another expression declines, until task 28 narrows inside a tree") {
    // The narrowing is the kernel's store, so `hour(t) + 1` would put a 32-bit value under a
    // node of a 64-bit tree, which the emitter refuses; the compiler declines it first, with
    // the reason, and the entry beside it still fuses.
    val reason = declineReason(Add(HoursOfTime(t6), Literal(1)), withLong)
    assert(reason.contains("only an output can take it"), reason)
  }

  test("time_to_millis and time_to_micros lower to a constant division of the nanoseconds") {
    // Group E (`PLAN_TASK_102.md` 9.3): `floorDiv` of a non-negative count is the truncating
    // division the lane has, and the result is a bigint on the same lane.
    val millis = VarkaExpressionCompiler.compile(Seq(out(TimeToMillis(t6))), withLong).get
    assert(millis.outputs === Seq(new ConstDivide(longCol, 1000000L, nanosPerDay)))
    assert(millis.outputTypes === Seq(LongType))
    val micros = VarkaExpressionCompiler.compile(Seq(out(TimeToMicros(t3))), withLong).get
    assert(micros.outputs === Seq(new ConstDivide(longCol, 1000L, nanosPerDay)))
    assert(micros.inputOrdinals === Seq(7))
  }

  test("time_from_seconds, millis and micros lower to a guarded multiply into the day") {
    // `multiplyExact` under the conversion's range check: the count is guarded to the day's
    // worth of its unit, inside which the wrapping multiply is exact and the result is a TIME;
    // a count outside declines the batch and the row engine raises Spark's error.
    def form(unit: Long): VarkaVectorIR = new IntArith(IntOp.MUL, Overflow.WRAP,
      new GuardedRange(longCol, 0L, (86400000000000L - 1) / unit), longSlot(0))
    val seconds = VarkaExpressionCompiler.compile(Seq(out(TimeFromSeconds(l))), withLong).get
    assert(seconds.outputs === Seq(form(1000000000L)))
    assert(seconds.longLiterals === Seq(1000000000L))
    assert(seconds.outputTypes === Seq(TimeType(6)))
    val millis = VarkaExpressionCompiler.compile(Seq(out(TimeFromMillis(l))), withLong).get
    assert(millis.outputs === Seq(form(1000000L)))
    val micros = VarkaExpressionCompiler.compile(Seq(out(TimeFromMicros(l))), withLong).get
    assert(micros.outputs === Seq(form(1000L)))
    // A round trip composes: the conversion's division under the conversion's multiply.
    val trip = VarkaExpressionCompiler.compile(
      Seq(out(TimeFromMicros(TimeToMicros(t6)))), withLong).get
    assert(trip.outputs === Seq(new IntArith(IntOp.MUL, Overflow.WRAP,
      new GuardedRange(new ConstDivide(longCol, 1000L, nanosPerDay), 0L, 86399999999L),
      longSlot(0))))
  }

  test("the decimal-valued TIME expressions decline by naming the representation") {
    // Section 2.3 asked for this: a reader who sees "not lowered yet" concludes the division is
    // missing, where the value is an unscaled long the lane holds and the column is what waits.
    for (e <- Seq(SecondsOfTimeWithFraction(t6), TimeToSeconds(t6))) {
      val reason = declineReason(e, withLong)
      assert(reason.contains("returns a decimal"), reason)
      assert(reason.contains("task 157"), reason)
    }
  }

  test("a TIME unit or level that is not a literal, or not a unit, declines with the reason") {
    // The divisor is part of the kernel's shape, so a unit that is not known at compile time
    // would need a kernel per distinct value - the same rule trunc(d, fmt) applies. An unknown
    // unit is the row engine's error to raise, not a kernel's to approximate.
    val notLiteral = declineReason(TimeDiff(Upper(Literal("hour")), t3, t6), withLong)
    assert(notLiteral.contains("is not a literal"), notLiteral)
    val unknown = declineReason(TimeTrunc(Literal("FORTNIGHT"), t6), withLong)
    assert(unknown.contains("unknown level 'FORTNIGHT'"), unknown)
    // And a day is not a TIME unit: DateTimeUtils stops at HOUR, so this table does too.
    val day = declineReason(TimeDiff(Literal("DAY"), t3, t6), withLong)
    assert(day.contains("unknown unit 'DAY'"), day)
  }

  test("t + dt lowers to a wrapping add under two range guards, and no precision step") {
    // DateTimeUtils.timeAddInterval is addExact(t, multiplyExact(dt, 1000)), a throw if the
    // sum leaves [0, NANOS_PER_DAY), then a truncation to the target precision. The interval
    // is held to a day first - beyond that every sum is out of the day and Spark throws on
    // every such row - which is also what keeps the multiply and the add under 2^48, so they
    // wrap without ever needing to. The sum is then held to the day, which is the throw as a
    // decline. Both guards carry their bounds, since two shapes with different bounds must
    // not share a kernel.
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(TimeAddInterval(t6, dt))), withLong).get
    val micros = new GuardedRange(longCol2, -86400000000L, 86400000000L)
    val nanos = new IntArith(IntOp.MUL, Overflow.WRAP, micros, longSlot(0))
    assert(compiled.outputs === Seq(new GuardedRange(
      new IntArith(IntOp.ADD, Overflow.WRAP, longCol, nanos), 0L, 86399999999999L)))
    assert(compiled.longLiterals === Seq(1000L))
    assert(compiled.inputOrdinals === Seq(8, 9))
    assert(compiled.outputTypes === Seq(TimeType(6)))
  }

  test("t + a literal interval folds the interval to a slot and guards only the sum") {
    // The interval's own guard is a compile-time question when the interval is a literal: one
    // inside a day is already nanoseconds to add, and one beyond it crosses midnight for every
    // time, which declines - the row engine raises the same error on every row.
    val hour = Literal(3600000000L, DayTimeIntervalType(DayTimeIntervalType.HOUR))
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(TimeAddInterval(t6, hour))), withLong).get
    assert(compiled.outputs === Seq(new GuardedRange(
      new IntArith(IntOp.ADD, Overflow.WRAP, longCol, longSlot(0)), 0L, 86399999999999L)))
    assert(compiled.longLiterals === Seq(3600000000000L))
    assert(compiled.inputOrdinals === Seq(8))
    val twoDays = Literal(2L * 86400000000L, DayTimeIntervalType(DayTimeIntervalType.DAY))
    val reason = declineReason(TimeAddInterval(t6, twoDays), withLong)
    assert(reason.contains("longer than a day"), reason)
  }

  test("a comparison over t + dt fuses whole, with the guard inside the predicate") {
    // The comparison's operand rule falls through to `compileNode`, so a lowered TIME
    // expression is an operand like a column is; the guard rides inside the predicate, where
    // a condition gives it the empty arm chain and it condemns the batch for any lane outside
    // the day. What makes this worth pinning is the end-to-end decline test in
    // VarkaTimeArithmeticSuite, which depends on every conjunct fusing.
    val noon = Literal(12L * 3600 * 1000000000L, TimeType(6))
    val predicate = VarkaExpressionCompiler.compilePredicate(
      And(GreaterThan(t6, noon), LessThan(TimeAddInterval(t6, dt), noon)), withLong).get
    assert(predicate.specs.forall(_.fused), predicate.specs.flatMap(_.decline).map(_.reason))
  }

  test("timeAddInterval's precision truncation is the identity for every admitted type") {
    // The argument the lowering rests on, checked against the types rather than assumed: the
    // time is a multiple of 10^(9 - p), the interval's nanoseconds a multiple of 10^3 - or of
    // a whole minute for an end field coarser than SECOND - and the target is what
    // TimeAddInterval.replacement computes, so the sum already has nothing below the target.
    for (p <- Seq(0, 1, 3, 6, 9)) {
      val fine = DayTimeIntervalType(DayTimeIntervalType.DAY, DayTimeIntervalType.SECOND)
      val coarse = DayTimeIntervalType(DayTimeIntervalType.DAY, DayTimeIntervalType.MINUTE)
      assert(!VarkaTimeCompiler.timeAddIntervalTruncates(TimeType(p), fine, math.max(p, 6)),
        s"TIME($p) + a second-ended interval")
      assert(!VarkaTimeCompiler.timeAddIntervalTruncates(TimeType(p), coarse, p),
        s"TIME($p) + a minute-ended interval")
    }
    // And the check is not vacuous: a target coarser than the sum's granularity truncates -
    // a nanosecond time plus a microsecond interval, asked for at six digits.
    assert(VarkaTimeCompiler.timeAddIntervalTruncates(TimeType(9),
      DayTimeIntervalType(DayTimeIntervalType.DAY, DayTimeIntervalType.SECOND), 6))
  }

  test("a TIME expression declines by name, not as `unsupported expression`") {
    // Task 102 lowers these one group at a time, and the difference between "not lowered yet"
    // and "unsupported" is what tells a reader where the work stands. The same distinction task
    // 89 drew for `extract(MONTH FROM ym)`.
    // make_time is the one still waiting on task 28's widening, now that the extracts and the
    // conversions are lowered and the decimal-valued two decline by their own reason.
    val reason = declineReason(
      MakeTime(Literal(1), Literal(2), Literal(Decimal(3), DecimalType(16, 6))), withLong)
    assert(reason.contains("make_time"), reason)
    assert(reason.contains("task 102"), reason)
  }

  private def declineReason(e: Expression, output: Seq[Attribute] = childOutput): String = {
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(e), out(DateAdd(d, Literal(1)))), output).get
    assert(partial.declines.contains(0), s"$e fused; expected it to decline")
    partial.declines(0).reason
  }

  test("a year-month interval column is a date-lane leaf, whatever its unit") {
    // The stored value is a count of months in every unit, so `d + ym` is task 60's
    // column-count AddMonths with no conversion - one arm, three units, the same IR. The unit
    // survives only on the Spark type, which is what `outputTypes` carries.
    for (col <- Seq(ymm, ymy, ym)) {
      val compiled = VarkaExpressionCompiler.compile(
        Seq(out(DateAddYMInterval(d, col))), withIntervals).get
      assert(compiled.outputs === Seq(new IRAddMonths(new ColumnRef(0), new ColumnRef(1))),
        s"unit ${col.dataType.simpleString} did not compile to a column-count add_months")
      assert(compiled.outputTypes === Seq(DateType))
    }
    // The leaf and the literal, in the positions Spark's typing lets an interval reach: an
    // ordered comparison, and a same-typed coalesce whose output is the interval itself.
    val months = Literal(6, YearMonthIntervalType(YearMonthIntervalType.MONTH,
      YearMonthIntervalType.MONTH))
    // Through the predicate path, not as a projected value: a boolean projection output is
    // scope item 5 and no interval makes it one.
    val cmp = VarkaExpressionCompiler.compilePredicate(GreaterThan(ymm, months), withIntervals).get
    assert(cmp.fusedConjuncts.size === 1)
    val coalesced = VarkaExpressionCompiler.compile(
      Seq(out(Coalesce(Seq(ymm, months)))), withIntervals).get
    assert(coalesced.outputTypes === Seq(ymm.dataType),
      "the fused output keeps the interval type, which is what allocateVector reads")
  }

  test("the MONTH-unit casts are relabels, and the YEAR-unit ones decline") {
    // `intToYearMonthInterval` returns its operand unchanged for a MONTH end field and
    // `yearMonthIntervalToInt` does the same for a MONTH-ended interval, so both directions
    // are the identity on the lane and neither emits a node - the `unix_date` pattern.
    val toInterval = VarkaExpressionCompiler.compile(
      Seq(out(Cast(i, ymm.dataType))), withIntervals).get
    assert(toInterval.outputs === Seq(new ColumnRef(0)))
    assert(toInterval.outputTypes === Seq(ymm.dataType))
    val toInt = VarkaExpressionCompiler.compile(
      Seq(out(Cast(ymm, IntegerType))), withIntervals).get
    assert(toInt.outputs === Seq(new ColumnRef(0)))
    assert(toInt.outputTypes === Seq(IntegerType))

    // The YEAR unit is neither direction's identity: 12x one way, /12 the other. Task 68
    // widened the emitter's month-count position and takes the 12x; the reverse direction is a
    // division, which nothing lowers and which task 89 owns.
    assert(declineReason(Cast(ymy, IntegerType), withIntervals).nonEmpty)
  }

  test("SPARK-VARKA-84: year's bound covers the whole admitted day range, not the guard's") {
    // `admitCalendar` admits a date up to NARROW_DECOMPOSE_MAX_DAYS, year 42400, through a
    // literal shift above a guarded column offset. The bound `year` reported was a typed-in
    // 40000, so `year(...) * 53000` proved itself safe at 2.12e9 while a row in year 42400
    // would have wrapped at 2.25e9 - a checked multiply emitted unchecked, which is the one
    // class of failure the ghost fallback cannot catch. Derived from the range now.
    assert(VarkaChrono.YEAR_FIELD_MAGNITUDE ===
      java.time.LocalDate.ofEpochDay(VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS).getYear)
    val farYear = Year(DateAdd(DateAdd(d, i), Literal(3000000)))
    assert(declineReason(Multiply(farYear, Literal(53000), EvalMode.ANSI), childOutput) ===
      "checked int multiply whose operands do not rule out overflow")
    // The corrected bound still proves the products it should: 42400 * 50000 is inside int32.
    val safe = VarkaExpressionCompiler.compile(
      Seq(out(Multiply(farYear, Literal(50000), EvalMode.ANSI))), childOutput).get
    assert(safe.outputs.head.isInstanceOf[IntArith])
    assert(safe.outputs.head.asInstanceOf[IntArith].mode() === Overflow.WRAP)
  }

  test("the YEAR-unit cast is a checked 12x, in both positions, bound permitting") {
    // `intToYearMonthInterval` multiplies by twelve with `Math.multiplyExact` whatever the
    // session's ANSI mode, so the multiply is checked and only a bound removes it. `year(d)`
    // is bounded (`YEAR_FIELD_MAGNITUDE`, 42400) and 42400 * 12 is inside int32; a bare int
    // column is not bounded and
    // declines like every other unbounded checked multiply.
    val bounded = VarkaExpressionCompiler.compile(
      Seq(out(Cast(Year(d), ymy.dataType))), withIntervals).get
    assert(bounded.outputs === Seq(
      new IntArith(IntOp.MUL, Overflow.WRAP, new IRYear(new ColumnRef(0)), new LiteralSlot(0))))
    assert(bounded.outputTypes === Seq(ymy.dataType))
    assert(declineReason(Cast(i, ymy.dataType), withIntervals) ===
      "checked int multiply whose operands do not rule out overflow")

    // The same expression in `add_months`' month-count position, which task 68's emitter split
    // opened. Task 67 pinned this as declining; that it now fuses is the evidence group C
    // landed, and the emitter's `requireMonthCountShape` is what has to agree.
    val inCount = VarkaExpressionCompiler.compile(
      Seq(out(DateAddYMInterval(d, Cast(Year(d), ymy.dataType)))), withIntervals).get
    assert(inCount.outputs === Seq(new IRAddMonths(new ColumnRef(0),
      new IntArith(IntOp.MUL, Overflow.WRAP, new IRYear(new ColumnRef(0)),
        new LiteralSlot(0)))))
    assert(declineReason(DateAddYMInterval(d, Cast(i, ymy.dataType)), withIntervals) ===
      "checked int multiply whose operands do not rule out overflow")
  }

  test("`d - ym` fuses, its count a checked negation the guard covers") {
    // Spark rewrites `d - ym` to DateAddYMInterval(d, UnaryMinus(ym)), so the count is a
    // negated interval column. Task 63 lowers the negation and task 68's emitter split lets
    // the month-count position hold it - the position `next_day`'s weekday no longer shares,
    // because task 60's lanewise guard on the count's value covers a derived count and
    // `next_day` has no such guard. Task 67 pinned this as declining.
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(DateAddYMInterval(d, UnaryMinus(ymm, false)))), withIntervals).get
    assert(compiled.outputs === Seq(new IRAddMonths(new ColumnRef(0),
      new IntNeg(Overflow.FAIL, new ColumnRef(1)))))
    assert(compiled.outputTypes === Seq(DateType))
  }

  test("the interval algebra rides the int32 arithmetic nodes, always checked") {
    // None of these has a LEGACY wrapping form - Spark computes every one with addExact,
    // subtractExact, negateExact or multiplyExact in every mode - so the declared mode is FAIL
    // and only `intBound` takes the check off. Over two unbounded interval columns it stays.
    val add = VarkaExpressionCompiler.compile(
      Seq(out(Add(ymm, ym))), withIntervals).get
    assert(add.outputs === Seq(
      new IntArith(IntOp.ADD, Overflow.FAIL, new ColumnRef(0), new ColumnRef(1))))
    assert(add.outputTypes === Seq(ymm.dataType))
    val sub = VarkaExpressionCompiler.compile(
      Seq(out(Subtract(ymm, ym))), withIntervals).get
    assert(sub.outputs === Seq(
      new IntArith(IntOp.SUB, Overflow.FAIL, new ColumnRef(0), new ColumnRef(1))))
    val neg = VarkaExpressionCompiler.compile(
      Seq(out(UnaryMinus(ymm, false))), withIntervals).get
    assert(neg.outputs === Seq(new IntNeg(Overflow.FAIL, new ColumnRef(0))))

    // `abs` is not an op the IR has: it is the blend `if (x < 0) -x else x`, whose IntNeg arm
    // takes exactly the one input that overflows a negation. Since task 79 the guard under a
    // CASE arm is qualified by that arm, so only the lanes that negate can condemn.
    // `d - CAST(i AS INTERVAL MONTH)`, which the analyzer spells as a negation over the
    // relabel cast, fuses on the same arm - task 60 pinned it as declining.
    val negCast = VarkaExpressionCompiler.compile(
      Seq(out(DateAddYMInterval(d, UnaryMinus(Cast(i, ymm.dataType))))), withIntervals).get
    assert(negCast.outputs === Seq(new IRAddMonths(new ColumnRef(0),
      new IntNeg(Overflow.FAIL, new ColumnRef(1)))))

    val abs = VarkaExpressionCompiler.compile(
      Seq(out(Abs(ymm, false))), withIntervals).get
    assert(abs.outputs === Seq(new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0)),
      new IntNeg(Overflow.FAIL, new ColumnRef(0)), new ColumnRef(0))))

    // make_ym_interval is `m + 12 * y`, two checked nodes composed; bounded operands remove
    // both checks, which is the shape that fuses with none at all.
    val mk = VarkaExpressionCompiler.compile(
      Seq(out(MakeYMInterval(Year(d), Month(d)))), withIntervals).get
    assert(mk.outputs === Seq(new IntArith(IntOp.ADD, Overflow.WRAP,
      new IRMonth(new ColumnRef(0)),
      new IntArith(IntOp.MUL, Overflow.WRAP, new IRYear(new ColumnRef(0)),
        new LiteralSlot(0)))))
    assert(mk.outputTypes === Seq(YearMonthIntervalType()))
    assert(declineReason(MakeYMInterval(i, i), withIntervals) ===
      "checked int multiply whose operands do not rule out overflow")
  }

  test("the unit relabel type coercion inserts is admitted, the truncating one not") {
    // Two year-month intervals of different units do not meet directly: `TypeCoercion` widens
    // both to the hull, `YearMonthIntervalType(min(start), max(end))`, so `ymm + ymy` reaches
    // the compiler as an add over two casts to YEAR TO MONTH. Without an arm for that cast the
    // plainest spelling of group A's binary algebra declines, which is what this test would
    // have caught earlier than the differential did.
    val hull = YearMonthIntervalType(YearMonthIntervalType.YEAR, YearMonthIntervalType.MONTH)
    val coerced = Add(Cast(ymm, hull), Cast(ymy, hull))
    val compiled = VarkaExpressionCompiler.compile(Seq(out(coerced)), withIntervals).get
    assert(compiled.outputs === Seq(
      new IntArith(IntOp.ADD, Overflow.FAIL, new ColumnRef(0), new ColumnRef(1))))
    assert(compiled.outputTypes === Seq(hull))
    // The relabel on its own, in a value position, emitting nothing at all.
    val bare = VarkaExpressionCompiler.compile(Seq(out(Cast(ymm, hull))), withIntervals).get
    assert(bare.outputs === Seq(new ColumnRef(0)))
    assert(bare.outputTypes === Seq(hull))

    // The other direction truncates - a YEAR end field keeps only the whole years, which is a
    // division by twelve - and is named rather than left to the generic decline. Coercion never
    // produces it, since it widens the end field; only a cast the user wrote reaches here.
    val years = YearMonthIntervalType(YearMonthIntervalType.YEAR, YearMonthIntervalType.YEAR)
    for (narrowing <- Seq(Cast(ym, years), Cast(ymm, years))) {
      assert(declineReason(narrowing, withIntervals) ===
        "year-month interval narrowed to a YEAR-ended unit, which divides by twelve")
    }
  }

  test("`extract(YEAR FROM ym)` fuses over a stored column, which no magic could serve") {
    // The point of the row: the operand is a stored month count with no bound at all, so the
    // calendar's range-narrowed magic is exact over about one forty-thousandth of it and cannot
    // be used. The double-lane division is exact over the whole type, so this fuses where every
    // earlier Varka would have declined - and its baseline is therefore the row engine rather
    // than another lowering.
    for (col <- Seq(ymm, ymy, ym)) {
      val compiled = VarkaExpressionCompiler.compile(
        Seq(out(ExtractANSIIntervalYears(col))), withIntervals).get
      assert(compiled.outputTypes === Seq(IntegerType))
      // The ordinal the column lands on is the compiler's to choose, so the assertion is on the
      // shape: one division by twelve, directly over a column.
      compiled.outputs match {
        case Seq(div: ConstDivide) =>
          assert(div.divisor() === 12)
          assert(div.child().isInstanceOf[ColumnRef])
        case other => fail(s"expected one ConstDivide over a column, got $other")
      }
    }
  }

  test("`extract(MONTH FROM ym)` declines on its output type, not on its division") {
    // `(months % 12).toByte`: the remainder is one multiply and one subtract from the quotient
    // above, so nothing about the arithmetic blocks it. The `ByteType` result does - Varka has
    // no byte lane and no Arrow vector to store one into - and the reason says so, because a
    // reader who saw "declined" here would otherwise conclude the division was the problem and
    // that task 89 had not landed.
    assert(declineReason(ExtractANSIIntervalMonths(ymm), withIntervals) ===
      "extract(MONTH FROM ym) returns a byte, which has no lane")
  }

  test("`ym * num` takes the int lane and names the types that are not one") {
    // A literal multiplier is bounded and the check comes off; an int column is an unbounded
    // checked multiply and declines as every other does. Long, Decimal and Double are not
    // int32 lanes and decline by type, rather than reaching intOperand and being reported as
    // "not an int column or literal", which would hide which of the two is wrong.
    val byLiteral = VarkaExpressionCompiler.compile(
      Seq(out(MultiplyYMInterval(Cast(Year(d), ymy.dataType), Literal(3)))), withIntervals).get
    // The result widens to YEAR TO MONTH whatever the operand's unit, which is Spark's own
    // typing and correct: scaling a year interval can produce months.
    assert(byLiteral.outputTypes === Seq(YearMonthIntervalType()))
    assert(declineReason(MultiplyYMInterval(ymm, i), withIntervals) ===
      "checked int multiply whose operands do not rule out overflow")
    for ((mult, name) <- Seq(Literal(3L) -> "bigint", Literal(2.5d) -> "double")) {
      assert(declineReason(MultiplyYMInterval(ymm, mult), withIntervals) ===
        s"interval multiplier of type $name is not an int32 lane")
    }
  }

  test("a literal day shift fuses at the bound and declines one day past it") {
    assert(fuses(Year(DateAdd(d, Literal(shiftHi)))))
    assert(declineReason(Year(DateAdd(d, Literal(shiftHi + 1)))) ===
      s"day range [${VarkaChrono.CONTRACT_MIN_DAYS + shiftHi + 1}, " +
        s"${VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS + 1}] leaves the calendar lowering's range")
    assert(fuses(Year(DateSub(d, Literal(-shiftLo)))))
    assert(declineReason(Month(DateSub(d, Literal(-shiftLo + 1)))) ===
      s"day range [${VarkaChrono.NARROW_MIN_DAYS - 1}, " +
        s"${VarkaChrono.CONTRACT_MAX_DAYS + shiftLo - 1}] leaves the calendar lowering's range")
    // Every calendar arm checks: the same shift declines under each of the seven.
    val far = DateAdd(d, Literal(shiftHi + 1))
    Seq[Expression](Year(far), Month(far), DayOfMonth(far), Quarter(far), DayOfYear(far),
      LastDay(far), AddMonths(far, Literal(1)),
      DateAddYMInterval(far, Literal.create(1, YearMonthIntervalType()))).foreach { e =>
      assert(!fuses(e), s"$e should decline")
    }
    // The same literal that the removed runtime guard's differential used, and the exact
    // query PLAN_TASK_52.md names as fusing wrongly after task 51.
    assert(!fuses(Year(DateAdd(d, Literal(20000000)))))
  }

  test("no calendar consumer, no bound - and the analysis composes") {
    // date_add alone produces whatever int addition produces, as Spark's DateAdd does.
    assert(fuses(DateAdd(d, Literal(shiftHi + 1))))
    assert(fuses(DateDiff(DateAdd(d, Literal(shiftHi + 1)), d2)))
    // Two literals each under the bound whose sum is over it. Both derive from the bound
    // rather than being written out: task 69 widened the ceiling by nine thousand years, and
    // a pair of hand-picked constants that used to straddle it now fits under it silently.
    val halfShift = shiftHi / 2 + 1
    assert(!fuses(Year(DateAdd(DateAdd(d, Literal(halfShift)), Literal(halfShift)))))
    assert(fuses(Year(DateAdd(DateSub(d, Literal(halfShift)), Literal(halfShift)))))
    // Long arithmetic: two Int.MaxValue offsets must not wrap back into range.
    assert(!fuses(Year(DateAdd(DateAdd(d, Literal(Int.MaxValue)), Literal(Int.MaxValue)))))
    // The identity cast and unix_date/date_from_unix_date unwrap to the child, so the
    // analysis sees through them.
    assert(!fuses(Year(Cast(DateAdd(d, Literal(shiftHi + 1)), DateType))))
    assert(!fuses(Year(DateFromUnixDate(UnixDate(DateAdd(d, Literal(shiftHi + 1)))))))
  }

  test("pass-through nodes take the hull of their date operands") {
    assert(fuses(Year(Greatest(Seq(DateAdd(d, Literal(5000000)), d)))))
    assert(!fuses(Year(Greatest(Seq(DateAdd(d, Literal(shiftHi + 1)), d)))))
    assert(!fuses(Year(Least(Seq(d, d2, DateSub(d, Literal(-shiftLo + 1)))))))
    assert(fuses(Year(If(LessThan(d, d2), DateAdd(d, Literal(shiftHi)), d))))
    assert(!fuses(Year(If(LessThan(d, d2), DateAdd(d, Literal(shiftHi + 1)), d))))
    assert(!fuses(Year(CaseWhen(Seq((LessThan(d, d2), d)),
      Some(DateAdd(d2, Literal(shiftHi + 1)))))))
    assert(!fuses(Year(Coalesce(Seq(d, DateAdd(d2, Literal(shiftHi + 1)))))))
    assert(fuses(Year(Coalesce(Seq(d, DateAdd(d2, Literal(shiftHi)))))))
    // A date literal is itself: in range when the parser wrote it, out of range when a test
    // builds one by hand - read back, not assumed.
    assert(fuses(Year(If(LessThan(d, d2), Literal(0, DateType), d))))
    assert(!fuses(Year(If(LessThan(d, d2),
      Literal(VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS + 1, DateType), d))))
    assert(fuses(Year(If(LessThan(d, d2),
      Literal(VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS, DateType), d))))
  }

  test("the date-typed calendar outputs carry their own bound") {
    // add_months's month count is bounded by task 40's decline, and inside that bound the
    // analysis charges up to 31 days a month on top of the child's interval.
    assert(fuses(Year(AddMonths(d, Literal(VarkaChrono.MONTH_ARITH_MAX_MONTHS)))))
    assert(fuses(Year(AddMonths(DateAdd(d, Literal(shiftHi - 12 * 31)), Literal(12)))))
    assert(!fuses(Year(AddMonths(DateAdd(d, Literal(shiftHi - 12 * 31 + 1)), Literal(12)))))
    assert(!fuses(Year(AddMonths(DateAdd(d, Literal(shiftHi - 100)), Literal(12)))))
    // add_months by a negative count shifts the low end.
    assert(fuses(Year(AddMonths(DateSub(d, Literal(-shiftLo - 31)), Literal(-1)))))
    assert(!fuses(Year(AddMonths(DateSub(d, Literal(-shiftLo - 30)), Literal(-1)))))
    // last_day's output is up to 30 days past an input that itself passed the check.
    assert(fuses(Year(LastDay(DateAdd(d, Literal(shiftHi - 30))))))
    assert(!fuses(Year(LastDay(DateAdd(d, Literal(shiftHi - 29))))))
    // A trunc output (task 35) is up to 365 days before its input, so it can only leave the
    // range at the bottom: the last shift that fuses is 365 short of date_sub's own.
    assert(fuses(Year(TruncDate(DateSub(d, Literal(-shiftLo - 365)), Literal("YEAR")))))
    assert(!fuses(Year(TruncDate(DateSub(d, Literal(-shiftLo - 364)), Literal("YEAR")))))
    // next_day shifts by 1 to 7.
    assert(fuses(Year(NextDay(DateAdd(d, Literal(shiftHi - 7)), Literal("MON")))))
    assert(!fuses(Year(NextDay(DateAdd(d, Literal(shiftHi - 6)), Literal("MON")))))
  }

  test("a column offset is admitted - the emitter guards that producer") {
    val compiled = VarkaExpressionCompiler.compile(Seq(out(Year(DateAdd(d, i)))), childOutput).get
    assert(compiled.outputs === Seq(new IRYear(new AddDays(new ColumnRef(0), new ColumnRef(1)))))
    assert(fuses(Year(DateSub(DateAdd(d, Literal(shiftHi)), i))))
    assert(fuses(Year(Greatest(Seq(DateAdd(d, i), d2)))))
    // A column offset above an out-of-range literal shift is admitted too: the runtime guard
    // checks the producer's actual result lanes, so an intermediate the offset brings back
    // into range is fine and one it leaves outside is caught per batch. Only an unknown
    // producer under the column offset still declines.
    assert(fuses(Year(DateAdd(Greatest(Seq(DateAdd(d, Literal(shiftHi + 1)), d)), i))))
    val ts = AttributeReference("t", TimestampType)()
    assert(!fuses(Year(DateAdd(Cast(ts, DateType), i)), childOutput :+ ts))
  }

  test("trunc compiles to one node per date level with a DateType output, under every " +
      "spelling parseTruncLevel accepts") {
    for ((spelling, level) <- Seq("YEAR" -> TruncLevel.YEAR, "yyyy" -> TruncLevel.YEAR,
        "YY" -> TruncLevel.YEAR, "MONTH" -> TruncLevel.MONTH, "mon" -> TruncLevel.MONTH,
        "MM" -> TruncLevel.MONTH, "QUARTER" -> TruncLevel.QUARTER,
        "quarter" -> TruncLevel.QUARTER)) {
      val compiled = VarkaExpressionCompiler.compile(
        Seq(out(TruncDate(d, Literal(spelling)))), childOutput).get
      assert(compiled.outputs === Seq(new IRTruncDate(new ColumnRef(0), level)), spelling)
      assert(compiled.outputTypes === Seq(DateType), spelling)
      assert(compiled.literals.isEmpty, s"$spelling: the level is a field, not a slot")
    }
    // Two levels over one date are two nodes, so CSE cannot merge them and the shape hash
    // tells them apart - the reason the level is a record component.
    val two = VarkaExpressionCompiler.compile(
      Seq(out(TruncDate(d, Literal("YEAR"))), out(TruncDate(d, Literal("MONTH")))),
      childOutput).get
    assert(two.outputs(0) !== two.outputs(1))
  }

  test("trunc to WEEK is next_day over date_sub by seven, on the nodes already there") {
    // Spark defines truncDate(d, WEEK) as getNextDateForDayOfWeek(d - 7, MONDAY); task 33's
    // next_day slot holds dayOfWeek - 1, and Monday is 4 in DateTimeUtils' numbering, so the
    // literal is 3. The shape is the assertion: if the rewrite is wrong, this is where it shows.
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(TruncDate(d, Literal("WEEK")))), childOutput).get
    assert(compiled.outputs === Seq(
      new IRNextDay(new SubDays(new ColumnRef(0), new LiteralSlot(0)), new LiteralSlot(1))))
    assert(compiled.literals === Seq(7, 3))
    assert(compiled.outputTypes === Seq(DateType))
  }

  test("declines a non-foldable, null, unrecognized or sub-day trunc format, each " +
      "with its own reason") {
    val fmt = AttributeReference("fmt", StringType)()
    def reason(format: Expression, output: Seq[Attribute] = childOutput): String = {
      val partial = VarkaExpressionCompiler.compilePartial(
        Seq(out(TruncDate(d, format)), out(DateAdd(d, Literal(1)))), output).get
      partial.declines(0).reason
    }
    // A stored string column fuses since task 61 (the dynamic node); an expression over one
    // is what "non-foldable" declines now.
    assert(reason(Upper(fmt), childOutput :+ fmt) === "trunc with a non-foldable format")
    assert(reason(Literal.create(null, StringType)) === "trunc with a null format")
    // QTR is not a spelling parseTruncLevel accepts (the recipe said it was); the row engine
    // answers it with NULL, which no IR node can produce.
    assert(reason(Literal("QTR")) === "trunc with an unrecognized format")
    assert(reason(Literal("DAY")) === "trunc to a level below a day, which is null for a date")
    assert(reason(Literal("HOUR")) === "trunc to a level below a day, which is null for a date")
  }

  test("trunc with a format column compiles to the dynamic node over a derived " +
      "input, collated or not, beside the literal node") {
    // A stored string column is read through the TRUNC_LEVEL leaf per batch: the kernel input
    // is a ColumnRef like any other, inputOrdinals names the string column, and the note tells
    // the evaluator to fill it before the kernel. No ANSI kind: TruncDate has no error path.
    val compiled = VarkaExpressionCompiler.compile(Seq(out(TruncDate(d, dow))), withDow).get
    assert(compiled.outputs === Seq(new IRTruncDateDynamic(new ColumnRef(0), new ColumnRef(1))))
    assert(compiled.outputTypes === Seq(DateType))
    assert(compiled.inputOrdinals === Seq(0, 5))
    assert(compiled.literals === Nil)
    assert(compiled.derivedInputs === Seq(VarkaDerivedInput(1, 5, VarkaDerivedKind.TRUNC_LEVEL)))
    val lcase = AttributeReference("lc", StringType("UTF8_LCASE"))()
    val collated = VarkaExpressionCompiler.compile(
      Seq(out(TruncDate(d, lcase))), childOutput :+ lcase).get
    assert(collated.derivedInputs === Seq(VarkaDerivedInput(1, 5, VarkaDerivedKind.TRUNC_LEVEL)))
    // Beside the literal node in one projection: the date column is shared, the literal
    // spelling stays task 35's node, and one derived input serves both dynamic entries.
    val both = VarkaExpressionCompiler.compile(Seq(out(TruncDate(d, dow)),
      out(TruncDate(d, Literal("MONTH"))), out(TruncDate(d2, dow))), withDow).get
    assert(both.outputs === Seq(
      new IRTruncDateDynamic(new ColumnRef(0), new ColumnRef(1)),
      new IRTruncDate(new ColumnRef(0), TruncLevel.MONTH),
      new IRTruncDateDynamic(new ColumnRef(2), new ColumnRef(1))))
    assert(both.inputOrdinals === Seq(0, 5, 1))
    assert(both.derivedInputs === Seq(VarkaDerivedInput(1, 5, VarkaDerivedKind.TRUNC_LEVEL)))
  }

  test("a format that is an expression over the column declines with the trunc " +
      "reason, and the dynamic node composes under a calendar function") {
    def reason(format: Expression): String = {
      val partial = VarkaExpressionCompiler.compilePartial(
        Seq(out(TruncDate(d, format)), out(DateAdd(d, Literal(1)))), withDow).get
      partial.declines(0).reason
    }
    assert(reason(Upper(dow)) === "trunc with a non-foldable format")
    assert(reason(Concat(Seq(dow, Literal("")))) === "trunc with a non-foldable format")
    // The range analysis knows the node - at most 365 days back - so year(trunc(d, fmt)) fuses.
    val composed = VarkaExpressionCompiler.compile(Seq(out(Year(TruncDate(d, dow)))), withDow).get
    assert(composed.outputs === Seq(
      new IRYear(new IRTruncDateDynamic(new ColumnRef(0), new ColumnRef(1)))))
  }

  test("declines year over a timestamp, which the analyzer casts") {
    // GetDateField's input type is DateType, so year(timestamp) arrives as a Cast the compiler
    // does not unwrap - only the identity DateType-to-DateType cast is transparent. It declines
    // at the cast rather than at the extraction, exactly as dayofweek(timestamp) does today.
    val ts = AttributeReference("t", TimestampType)()
    val bound = Seq(out(Year(Cast(ts, DateType))))
    assert(VarkaExpressionCompiler.compile(bound, Seq(ts)).isEmpty)
  }

  test("unix_date/date_from_unix_date relabel rather than compiling to a node") {
    // unix_date's child is a date column, readable today: the relabel vanishes and the IR is
    // a bare ColumnRef, with the output type coming from the Catalyst expression (IntegerType)
    // rather than from anything the IR rendered.
    val unixDate = VarkaExpressionCompiler.compile(Seq(out(UnixDate(d))), childOutput).get
    assert(unixDate.outputs === Seq(new ColumnRef(0)))
    assert(unixDate.outputTypes === Seq(IntegerType))
    // date_from_unix_date's child is an integer column, which no general leaf can read, so
    // this declines through the ordinary non-date-column path exactly as any other read of
    // `i` would. Task 38 has since landed and does not change that: it opens IntegerType
    // columns through compileOffset only - deliberately not through compileNode, per that
    // method's own javadoc - so the offset of a date_add is readable and this is not.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateFromUnixDate(i))), childOutput).isEmpty)
    // The actual argument for the task: a relabelled entry must not demote the rest of the
    // projection to the row path. Before this task UnixDate itself declined, taking `a` with it.
    val mixed = VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, Literal(1))), out(UnixDate(d))), childOutput).get
    assert(mixed.outputs === Seq(new AddDays(new ColumnRef(0), new LiteralSlot(0)),
      new ColumnRef(0)))
    assert(mixed.outputTypes === Seq(DateType, IntegerType))
    // A relabel compiles to a bare ColumnRef, the same IR shape a bare column produces -
    // compileCoalesce and compileValidity both use that shape as their proxy for "this
    // operand is a bare column" (their own doc comments now say so), and a relabel is safe
    // to guard exactly because it is a null-intolerant identity like the column it wraps.
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    val guarded = VarkaExpressionCompiler.compile(
      Seq(out(If(IsNotNull(UnixDate(d)), UnixDate(d), UnixDate(d2)))), childOutput).get
    assert(guarded.outputs === Seq(new IfElse(new IRIsNotNull(c0), c0, c1)))
    assert(guarded.outputTypes === Seq(IntegerType))
    val coalesced = VarkaExpressionCompiler.compile(
      Seq(out(Coalesce(Seq(UnixDate(d), UnixDate(d2))))), childOutput).get
    assert(coalesced.outputs === Seq(new IfElse(new IRIsNotNull(c0), c0, c1)))
    assert(coalesced.outputTypes === Seq(IntegerType))
  }

  test("date_add/date_sub with an IntegerType column offset compile to a two-column " +
      "AddDays/SubDays, and a foldable offset still compiles to a LiteralSlot") {
    val addCompiled = VarkaExpressionCompiler.compile(Seq(out(DateAdd(d, i))), childOutput).get
    assert(addCompiled.outputs === Seq(new AddDays(new ColumnRef(0), new ColumnRef(1))))
    assert(addCompiled.inputOrdinals === Seq(0, 2))
    assert(addCompiled.outputTypes === Seq(DateType))
    val subCompiled = VarkaExpressionCompiler.compile(Seq(out(DateSub(d, i))), childOutput).get
    assert(subCompiled.outputs === Seq(new SubDays(new ColumnRef(0), new ColumnRef(1))))
    // A foldable offset keeps today's LiteralSlot shape - existing plans and their cached
    // kernels are untouched by the fallback path this task adds.
    val literalCompiled =
      VarkaExpressionCompiler.compile(Seq(out(DateAdd(d, Literal(3)))), childOutput).get
    assert(literalCompiled.outputs === Seq(new AddDays(new ColumnRef(0), new LiteralSlot(0))))
  }

  test("declines a ShortType or ByteType offset column, and an interval column") {
    // DateAdd.inputTypes accepts ShortType/ByteType with no cast, so a short or byte column
    // arrives as a bare BoundReference the leaf arm must not accept - its Arrow vector is 2 or
    // 1 bytes wide, which an int32 lane load would read as garbage rather than decline.
    assert(VarkaExpressionCompiler.compile(Seq(out(DateAdd(d, sh))), childOutput).isEmpty)
    assert(VarkaExpressionCompiler.compile(Seq(out(DateAdd(d, by))), childOutput).isEmpty)
    // `d + <non-foldable INTERVAL DAY column>` resolves to
    // DateAdd(d, ExtractANSIIntervalDays(intervalCol)) (BinaryArithmeticWithDatetimeResolver);
    // ExtractANSIIntervalDays has no compiler arm, so this declines through the ordinary
    // unsupported-expression path rather than needing its own guard.
    val iv = AttributeReference("iv", DayTimeIntervalType(DayTimeIntervalType.DAY))()
    val withInterval = Seq(out(DateAdd(d, ExtractANSIIntervalDays(iv))))
    assert(VarkaExpressionCompiler.compile(withInterval, childOutput :+ iv).isEmpty)
  }

  test("with two independently unfusable operands, the child's reason is reported") {
    // date_add compiles its date child before its offset (VarkaExpressionCompiler's own
    // reading-order rule, the same one CaseWhen documents), so when BOTH operands are
    // unfusable, DeclineSink's "first note wins" rule surfaces the child's reason here, not
    // the offset's - pinning that as intentional rather than an accident of evaluation order.
    val s = AttributeReference("s", StringType)()
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(DateAdd(Cast(s, DateType), sh)), out(DateAdd(d, Literal(1)))),
      s +: childOutput).get
    assert(partial.declines(0).reason === "unsupported expression")
  }

  test("declines null-safe equality, bare boolean outputs") {
    // <=> on two nulls is true, which breaks the null-intolerant comparison rule.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(If(EqualNullSafe(d, d2), d2, DateAdd(d, Literal(1))))), childOutput).isEmpty)
    // A comparison as a projection output is a boolean column - out of scope, interior only.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(LessThan(d, d2))), childOutput).isEmpty)
  }

  test("IN dedups and sorts date literals into a balanced OR of EQ") {
    val expr = If(
      In(d, Seq(Literal(20, DateType), Literal(5, DateType), Literal(20, DateType),
        Literal(11, DateType))),
      d, d2)
    val compiled = VarkaExpressionCompiler.compile(Seq(out(expr)), childOutput).get
    val c0 = new ColumnRef(0)
    def eq(slot: Int): Compare = new Compare(CompareOp.EQ, c0, new LiteralSlot(slot))
    // Slots in sorted-day order (5, 11, 20), the duplicate collapsed; the fold is balanced
    // pairwise, so three leaves become Or(Or(e0, e1), e2) - the shape the cap arithmetic
    // and the shape hash both depend on.
    assert(compiled.literals === Seq(5, 11, 20))
    assert(compiled.outputs === Seq(new IfElse(
      new IROr(new IROr(eq(0), eq(1)), eq(2)), c0, new ColumnRef(1))))
    // InSet hands the same values over as an unordered set and must compile identically.
    val viaInSet = VarkaExpressionCompiler.compile(
      Seq(out(If(InSet(d, Set[Any](20, 5, 11)), d, d2))), childOutput).get
    assert(viaInSet.outputs === compiled.outputs)
    assert(viaInSet.literals === compiled.literals)
    // And at the cap size - the shape that actually arrives as InSet past the optimizer's
    // threshold of 10 - the full sorted slot sequence is pinned: sixteen elements handed
    // over in descending order must register ascending, or the shape hash drifts run to run.
    val days16 = (1 to 16).map(_ * 7)
    val atCap = VarkaExpressionCompiler.compile(
      Seq(out(If(InSet(d, Set[Any](days16.reverse: _*)), d, d2))), childOutput).get
    assert(atCap.literals === days16)
  }

  test("the IN cap - 16 literals fuse, 17 decline with the recorded reason") {
    def inIf(n: Int): NamedExpression =
      out(If(In(d, (1 to n).map(k => Literal(k * 3, DateType))), d, d2))
    assert(VarkaExpressionCompiler.compile(Seq(inIf(16)), childOutput).isDefined)
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(inIf(17), out(DateAdd(d, Literal(1)))), childOutput).get
    assert(partial.specs === Seq(ResidualOutput, FusedOutput(0)))
    assert(partial.declines(0).reason === "IN list longer than the fused cap of 16")
    // A null element can never match by SQL's IN semantics but makes the no-match result
    // unknown; it stays declined rather than modeled.
    val withNull = out(If(In(d, Seq(Literal(1, DateType), Literal(null, DateType))), d, d2))
    val p2 = VarkaExpressionCompiler.compilePartial(
      Seq(withNull, out(DateAdd(d, Literal(1)))), childOutput).get
    assert(p2.declines(0).reason === "IN list has a null or non-literal element")
  }

  test("coalesce lowers onto the validity condition; guarded operands are columns") {
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(Coalesce(Seq(d, d2, Literal(7, DateType))))), childOutput).get
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    assert(compiled.outputs === Seq(new IfElse(new IRIsNotNull(c0), c0,
      new IfElse(new IRIsNotNull(c1), c1, new LiteralSlot(0)))))
    // A computed operand before the last cannot be guarded - its validity word is not live
    // before value emission - and declines with its own reason.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(Coalesce(Seq(DateAdd(d, Literal(1)), d2))), out(DateAdd(d, Literal(1)))),
      childOutput).get
    assert(partial.declines(0).reason ===
      "coalesce operand before the last is not a bare date column")
  }

  test("IS [NOT] NULL compile; nvl and nvl2 arrive through their replacements") {
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(If(IsNotNull(d), d, d2)), out(If(IsNull(d), d2, d))), childOutput).get
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    assert(compiled.outputs === Seq(
      new IfElse(new IRIsNotNull(c0), c0, c1),
      new IfElse(new IRNot(new IRIsNotNull(c0)), c1, c0)))
    // Hand-built RuntimeReplaceables compile through their replacement - the same trees a
    // real query hands over after the optimizer's ReplaceExpressions.
    val viaNvl = VarkaExpressionCompiler.compile(Seq(out(new Nvl(d, d2))), childOutput).get
    assert(viaNvl.outputs === Seq(new IfElse(new IRIsNotNull(c0), c0, c1)))
    val viaNvl2 = VarkaExpressionCompiler.compile(
      Seq(out(new Nvl2(d, d2, DateAdd(d2, Literal(1))))), childOutput).get
    assert(viaNvl2.outputs === Seq(new IfElse(new IRIsNotNull(c0), c1,
      new AddDays(c1, new LiteralSlot(0)))))
    // A validity predicate over a computed operand declines: the emitter reads the child's
    // per-input validity word, which only a column has before value emission.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(If(IsNotNull(DateAdd(d, Literal(1))), d, d2)), out(DateAdd(d, Literal(1)))),
      childOutput).get
    assert(partial.declines(0).reason === "validity predicate over a non-column operand")
  }

  test("the identity date cast unwraps; a string-column cast still declines") {
    val compiled = VarkaExpressionCompiler.compile(
      Seq(out(Cast(DateAdd(d, Literal(3)), DateType))), childOutput).get
    assert(compiled.outputs === Seq(new AddDays(new ColumnRef(0), new LiteralSlot(0))))
    // A string column cast is a per-row parse with no string lane.
    val s = AttributeReference("s", StringType)()
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(Cast(s, DateType)), out(DateAdd(d, Literal(1)))), s +: childOutput).get
    assert(partial.declines(0).reason === "unsupported expression")
  }

  test("the compiler mirrors the emitter budgets and demotes the overflow entry") {
    def inIf(base: Int): NamedExpression =
      out(If(In(d, (1 to 16).map(k => Literal(base + k, DateType))), d, d2))
    // Two 16-literal INs are exactly 64 distinct ops (2 x (16 EQ + 15 OR + 1 IfElse)); a
    // third entry's single op would be the 65th. Before task 20 this shape reached the
    // emitter and lost the whole kernel to a silent per-batch fallback; now the overflow
    // entry demotes to residual with a recorded reason.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(inIf(0), inIf(1000), out(DateAdd(d, Literal(9999)))), childOutput).get
    assert(partial.specs === Seq(FusedOutput(0), FusedOutput(1), ResidualOutput))
    assert(partial.declines(2).reason === "exceeds the emitter's fused budget")
    // The depth budget is mirrored the same way: a 17-deep chain compiled fine before task
    // 20 and then failed at emission.
    val deep = out((0 until 17).foldLeft[Expression](d)((e, k) => DateAdd(e, Literal(k + 1))))
    val deepPartial = VarkaExpressionCompiler.compilePartial(
      Seq(deep, out(DateAdd(d, Literal(1)))), childOutput).get
    assert(deepPartial.specs === Seq(ResidualOutput, FusedOutput(0)))
    assert(deepPartial.declines(0).reason === "exceeds the emitter's fused budget")
    // The input-column budget is mirrored too: 33 shallow datediff entries over 66 distinct
    // columns are only 33 ops at height 1, but 66 kernel inputs - the 33rd entry (the one
    // that pushes past 64 columns) demotes instead of blowing up at emission.
    val wide = (0 until 66).map(k => AttributeReference(s"w$k", DateType)())
    val wideEntries = (0 until 33).map { k =>
      out(DateDiff(wide(2 * k), wide(2 * k + 1)))
    }
    val widePartial = VarkaExpressionCompiler.compilePartial(wideEntries, wide).get
    assert(widePartial.specs.count(_ == ResidualOutput) === 1)
    assert(widePartial.specs.last === ResidualOutput)
    assert(widePartial.declines(32).reason === "exceeds the emitter's fused budget")
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
    // An IntegerType column offset now compiles (task 38); a ShortType one still declines,
    // so `compile` still declines the whole projection over it.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, i))), childOutput).isDefined)
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, sh))), childOutput).isEmpty)
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
        // Residual since task 63 for a narrower reason than before: an int column is
        // unbounded, so a checked multiply over it has no int-lane overflow test. `i + 1`
        // fuses now, with the check. The mode is spelled out rather than taken from
        // `SQLConf.get`, which this suite never sets: under `SPARK_ANSI_SQL_MODE=false` the
        // ambient default is LEGACY, the multiply fuses, and the test would fail for a reason
        // that has nothing to do with what it checks.
        out(Multiply(i, Literal(7), EvalMode.ANSI)),
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
    // `i * 7` under ANSI is the residual here: task 63 made `i + 1` fusible, but a checked
    // multiply over an unbounded column still has no int-lane overflow test.
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(out(Multiply(i, Literal(7), EvalMode.ANSI))), childOutput).isEmpty)
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(d.asInstanceOf[NamedExpression], i.asInstanceOf[NamedExpression]),
      childOutput).isEmpty)
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(d.asInstanceOf[NamedExpression], out(Multiply(i, Literal(7), EvalMode.ANSI))),
      childOutput).isEmpty)
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

  test("a fully fusible predicate compiles to one condition root") {
    // The survey's BETWEEN shape, post-optimizer: paired comparisons on the AND spine.
    val condition = org.apache.spark.sql.catalyst.expressions.And(
      GreaterThan(d, Literal(10, DateType)), LessThan(d, Literal(20, DateType)))
    val predicate = VarkaExpressionCompiler.compilePredicate(condition, childOutput).get
    assert(predicate.specs.forall(_.fused))
    assert(predicate.residualConjuncts.isEmpty)
    assert(predicate.fused.outputs === Seq(new VarkaVectorIR.And(
      new Compare(CompareOp.GT, new ColumnRef(0), new LiteralSlot(0)),
      new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(1)))))
    assert(predicate.fused.outputTypes === Seq(org.apache.spark.sql.types.BooleanType))
    assert(predicate.fused.inputOrdinals === Seq(0))
    assert(predicate.fused.literals === Seq(10, 20))
  }

  test("a mixed predicate splits - fusible conjuncts in, the rest residual") {
    // The corpus norm: a date predicate AND a non-date one AND a validity guard. The middle
    // conjunct is over a `ShortType` column, which is a narrower lane the kernel does not
    // read, so it declines while the date ones fuse and the residual keeps its reason for the
    // report. It was `i > 5` until task 122 made a bare int column comparable in the kernel -
    // the shape this test needs is one that is still out of the lane's reach, not one that
    // merely was.
    val condition = org.apache.spark.sql.catalyst.expressions.And(
      org.apache.spark.sql.catalyst.expressions.And(
        LessThan(d, d2), GreaterThan(sh, Literal(5.toShort))),
      IsNotNull(d))
    val predicate = VarkaExpressionCompiler.compilePredicate(condition, childOutput).get
    assert(predicate.specs.map(_.fused) === Seq(true, false, true))
    assert(predicate.fusedConjuncts === Seq(LessThan(d, d2), IsNotNull(d)))
    assert(predicate.residualConjuncts === Seq(GreaterThan(sh, Literal(5.toShort))))
    val decline = predicate.specs(1).decline.get
    assert(decline.reason === "non-date column of type smallint")
    // The fused root is the balanced AND of the two fused conjuncts, in query order.
    assert(predicate.fused.outputs === Seq(new VarkaVectorIR.And(
      new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1)),
      new IRIsNotNull(new ColumnRef(0)))))
  }

  test("a declining conjunct rolls the shared tables back") {
    // The first conjunct registers d2 and the literal 9 before its int operand declines it;
    // the second fuses. The kernel must read only what the fused conjunct references.
    val condition = org.apache.spark.sql.catalyst.expressions.And(
      LessThan(DateDiff(DateAdd(d2, Literal(9)), i), Literal(3)),
      GreaterThan(d, Literal(11, DateType)))
    val predicate = VarkaExpressionCompiler.compilePredicate(condition, childOutput).get
    assert(predicate.specs.map(_.fused) === Seq(false, true))
    assert(predicate.fused.inputOrdinals === Seq(0),
      "the declined conjunct's column registration must be rolled back")
    assert(predicate.fused.literals === Seq(11),
      "the declined conjunct's literal registration must be rolled back")
  }

  test("predicates with nothing to fuse, or no columns, are not eligible") {
    // No conjunct compiles. A short column, not an int one: task 122 admitted `i > 5`.
    assert(VarkaExpressionCompiler.compilePredicate(
      GreaterThan(sh, Literal(5.toShort)), childOutput).isEmpty)
    // A conjunct compiles but references no column: nothing to vectorize over.
    assert(VarkaExpressionCompiler.compilePredicate(
      LessThan(Literal(1, DateType), Literal(2, DateType)), childOutput).isEmpty)
  }

  test("the balanced AND fold keeps many conjuncts inside the depth budget") {
    // 20 distinct comparisons: a left fold would be 21 deep and trip MAX_CHAIN_DEPTH = 16;
    // the balanced fold is ceil(log2 20) + 2 deep and every conjunct fuses.
    val condition = (1 to 20)
      .map(k => GreaterThan(d, Literal(k, DateType)): Expression)
      .reduceLeft(org.apache.spark.sql.catalyst.expressions.And(_, _))
    val predicate = VarkaExpressionCompiler.compilePredicate(condition, childOutput).get
    assert(predicate.specs.size === 20)
    assert(predicate.specs.forall(_.fused))
  }

  test("a nondeterministic conjunct declines the whole predicate") {
    // The split hoists fused conjuncts below residual ones, reordering evaluation; a seeded
    // rand must see every row (Spark's own pushdown stops at the first nondeterministic
    // conjunct), so one nondeterministic conjunct declines the whole predicate.
    val condition = org.apache.spark.sql.catalyst.expressions.And(
      LessThan(d, Literal(10, DateType)),
      LessThan(org.apache.spark.sql.catalyst.expressions.Rand(Literal(42L)), Literal(0.5)))
    assert(VarkaExpressionCompiler.compilePredicate(condition, childOutput).isEmpty)
  }

  test("the budget mirror demotes conjuncts past MAX_FUSED_NODES to residual") {
    // Each conjunct is one Compare op and the fold adds one And per accepted conjunct, so k
    // accepted conjuncts cost 2k - 1 distinct ops: 32 fit the 64-op budget, the 33rd would
    // make 65. The overflow conjuncts demote with the recorded budget reason.
    val condition = (1 to 40)
      .map(k => GreaterThan(d, Literal(k, DateType)): Expression)
      .reduceLeft(org.apache.spark.sql.catalyst.expressions.And(_, _))
    val predicate = VarkaExpressionCompiler.compilePredicate(condition, childOutput).get
    assert(predicate.fusedConjuncts.size === 32)
    assert(predicate.residualConjuncts.size === 8)
    val decline = predicate.specs.reverse.head.decline.get
    assert(decline.reason === "exceeds the emitter's fused budget")
  }

  // Task 56: date +- INTERVAL n DAY with a column interval, through the analyzer's own resolver so
  // the shapes asserted are the ones a query produces, not ones this suite invented.
  private val dayInterval = DayTimeIntervalType(DayTimeIntervalType.DAY)
  private def resolved(e: Expression): Expression = BinaryArithmeticWithDatetimeResolver.resolve(e)
  private val limit = VarkaChrono.INTERVAL_DAY_LIMIT_DAYS

  /** The reason the first of two entries declined, the second being a known-good fused one. */
  private def intervalDeclineReason(e: Expression, output: Seq[Attribute] = childOutput): String = {
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(e), out(DateAdd(d, Literal(1)))), output).get
    assert(partial.declines.contains(0), s"$e fused; expected it to decline")
    partial.declines(0).reason
  }

  test("the arithmetic arms carry the evaluation mode, and the operand leaves") {
    // One tree per mode, built explicitly rather than through SQLConf, so the test says which
    // mode it means. LEGACY wraps, ANSI condemns the batch, TRY nulls the lane. The left
    // operand is an int column on purpose: it carries no bound, so the declared mode survives
    // - over a bounded operand the compiler proves the check away, which the next test covers.
    for ((mode, overflow) <- Seq(EvalMode.LEGACY -> Overflow.WRAP, EvalMode.ANSI -> Overflow.FAIL,
        EvalMode.TRY -> Overflow.NULL)) {
      val compiled = VarkaExpressionCompiler.compile(
        Seq(out(Add(i, Literal(1), mode))), childOutput).get
      assert(compiled.outputs === Seq(new IntArith(IntOp.ADD, overflow,
        new ColumnRef(0), new LiteralSlot(0))))
      assert(compiled.outputTypes === Seq(IntegerType))
    }
    // And the contrast, in one line: the same add over `datediff`, whose range the date
    // contract bounds, needs no check even under ANSI.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Add(DateDiff(d, d2), Literal(1), EvalMode.ANSI))), childOutput).get.outputs ===
      Seq(new IntArith(IntOp.ADD, Overflow.WRAP,
        new IRDateDiff(new ColumnRef(0), new ColumnRef(1)), new LiteralSlot(0))))
    // Subtract and multiply take the same route; unary minus has no TRY spelling in Spark, so
    // its mode is only ever the two.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Subtract(i, Literal(1), EvalMode.LEGACY))), childOutput).get.outputs ===
      Seq(new IntArith(IntOp.SUB, Overflow.WRAP, new ColumnRef(0), new LiteralSlot(0))))
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Multiply(i, Literal(7), EvalMode.LEGACY))), childOutput).get.outputs ===
      Seq(new IntArith(IntOp.MUL, Overflow.WRAP, new ColumnRef(0), new LiteralSlot(0))))
    assert(VarkaExpressionCompiler.compile(
      Seq(out(UnaryMinus(i, false))), childOutput).get.outputs ===
      Seq(new IntNeg(Overflow.WRAP, new ColumnRef(0))))
    assert(VarkaExpressionCompiler.compile(
      Seq(out(UnaryMinus(i, true))), childOutput).get.outputs ===
      Seq(new IntNeg(Overflow.FAIL, new ColumnRef(0))))
    // The operand leaves: an int column is the task 38 leaf, an int literal a slot, and a
    // fused int field the node itself - three kinds, one arm.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Add(Year(d), i, EvalMode.LEGACY))), childOutput).get.outputs ===
      Seq(new IntArith(IntOp.ADD, Overflow.WRAP, new IRYear(new ColumnRef(0)),
        new ColumnRef(1))))
  }

  test("a bound that rules out overflow removes the check, and an unprovable checked " +
      "multiply declines") {
    // year(d) * 100 + month(d) under ANSI: both operands are bounded by the calendar, so the
    // product and the sum are proved to stay in the int range and every node emits as WRAP.
    // This is the shape PLAN_TASK_63.md section 6 measures - it is fast because there is no
    // check in it, not because the check is cheap.
    val key = Add(Multiply(Year(d), Literal(100), EvalMode.ANSI), Month(d), EvalMode.ANSI)
    val compiled = VarkaExpressionCompiler.compile(Seq(out(key)), childOutput).get
    assert(compiled.outputs === Seq(new IntArith(IntOp.ADD, Overflow.WRAP,
      new IntArith(IntOp.MUL, Overflow.WRAP, new IRYear(new ColumnRef(0)),
        new LiteralSlot(0)),
      new IRMonth(new ColumnRef(0)))))
    // An int column carries no bound, so the same multiply over one keeps its declared mode -
    // and a checked multiply has no int-lane overflow test, so it declines with that reason.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(Multiply(i, Literal(3), EvalMode.ANSI)), out(DateAdd(d, Literal(1)))),
      childOutput).get
    assert(partial.declines(0).reason ===
      "checked int multiply whose operands do not rule out overflow")
    // The same tree under LEGACY has nothing to check and fuses.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Multiply(i, Literal(3), EvalMode.LEGACY))), childOutput).isDefined)
  }

  test("the bound is refused where it would be a fiction, not merely large") {
    // Three ways the bound analysis was unsound, each of which removed a check that Spark's
    // row engine performs, so the kernel answered where Spark raises. Every case below must
    // keep its declared mode - FAIL for add and subtract, a decline for multiply.

    // (1) A bound of exactly 2^31 is one past the largest int, so it may not pass. The old
    // test compared against MIN_VALUE's magnitude and admitted it.
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(out(Multiply(Quarter(d), Literal(536870912), EvalMode.ANSI)), out(DateAdd(d,
        Literal(1)))), childOutput).get.declines(0).reason ===
      "checked int multiply whose operands do not rule out overflow",
      "a bound of 4 * 2^29 = 2^31 overflows and must not prove the multiply safe")
    // One below it still fuses, so the boundary is where it should be and not merely moved.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Multiply(Quarter(d), Literal(536870911), EvalMode.ANSI))), childOutput).isDefined)

    // (2) `datediff` is bounded by the date contract only when its operands are. A literal
    // shift big enough to wrap an int32 lane makes the difference anything at all, and the
    // contract width would be a fiction over it.
    val wrapped = DateDiff(DateAdd(d, Literal(2147483647)), d2)
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Add(wrapped, Literal(1), EvalMode.ANSI))), childOutput).get.outputs.head
      .asInstanceOf[IntArith].mode() === Overflow.FAIL,
      "a datediff over a wrapping shift is unbounded, so the check has to stay")
    // The ordinary shapes still lose their check: two date columns, and a bounded shift.
    for (shape <- Seq(DateDiff(d, d2), DateDiff(DateAdd(d, Literal(30)), d2))) {
      assert(VarkaExpressionCompiler.compile(
        Seq(out(Add(shape, Literal(1), EvalMode.ANSI))), childOutput).get.outputs.head
        .asInstanceOf[IntArith].mode() === Overflow.WRAP, s"$shape should still be bounded")
    }
    // A column offset is not bounded here either: task 52's guard is armed by a calendar
    // consumer, and `datediff` is not one, so nothing keeps its operand in range.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Add(DateDiff(DateAdd(d, i), d2), Literal(1), EvalMode.ANSI))), childOutput)
      .get.outputs.head.asInstanceOf[IntArith].mode() === Overflow.FAIL,
      "an unguarded column-shifted operand leaves the datediff unbounded")
    // But a calendar node *inside* the operand does arm that guard on the producer below it,
    // so this one is bounded and keeps its check off. Reading "datediff arms no guard" as
    // "nothing under a datediff is ever guarded" would cost this shape its fusion.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Add(DateDiff(LastDay(DateAdd(d, i)), d2), Literal(1), EvalMode.ANSI))),
      childOutput).get.outputs.head.asInstanceOf[IntArith].mode() === Overflow.WRAP,
      "a producer under last_day is guarded, so the datediff over it is bounded")
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Add(DateDiff(TruncDate(DateAdd(d, i), Literal("MONTH")), d2), Literal(1),
        EvalMode.ANSI))), childOutput).get.outputs.head.asInstanceOf[IntArith].mode() ===
      Overflow.WRAP, "the same for a producer under trunc")

    // (3) Bounds are computed exactly: one that wraps `Long` used to come back small and
    // positive, and prove anything at all. This chain's product of bounds passes 2^63.
    val wide = Subtract(Add(DateDiff(d, d2), Literal(2147483647), EvalMode.LEGACY),
      Literal(2143831591), EvalMode.LEGACY)
    assert(VarkaExpressionCompiler.compilePartial(
      Seq(out(Multiply(wide, wide, EvalMode.ANSI)), out(DateAdd(d, Literal(1)))), childOutput)
      .get.declines(0).reason ===
      "checked int multiply whose operands do not rule out overflow",
      "a bound that wraps Long must not prove a multiply safe")
  }

  test("a bounded negation needs no check, and an unbounded one keeps it") {
    // Negation overflows on exactly one value, so any bound rules it out. `-month(d)` was
    // emitted checked, which is the mask disposal for nothing.
    for (bounded <- Seq(Month(d), DateDiff(d, d2), Add(Multiply(Year(d), Literal(100),
        EvalMode.ANSI), Month(d), EvalMode.ANSI))) {
      assert(VarkaExpressionCompiler.compile(
        Seq(out(UnaryMinus(bounded, true))), childOutput).get.outputs.head
        .asInstanceOf[IntNeg].mode() === Overflow.WRAP, s"-($bounded) cannot overflow")
    }
    // An int column carries no bound, so its negation keeps the check.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(UnaryMinus(i, true))), childOutput).get.outputs.head
      .asInstanceOf[IntNeg].mode() === Overflow.FAIL)
  }

  test("a day offset that lowers to a non-arithmetic node declines rather than " +
      "reaching the emitter") {
    // `weekday(d2) + 1` is an Add, but it lowers to task 57's dedicated DayOfWeekIso node,
    // which the emitter's day-offset check does not take. Admitting it here would mark the
    // entry fused and let the refusal fire at emit time, where it becomes a silent per-batch
    // fallback under an EXPLAIN that still claims fusion.
    for (offset <- Seq(Add(WeekDay(d2), Literal(1)), Add(Literal(1), WeekDay(d2)))) {
      val partial = VarkaExpressionCompiler.compilePartial(
        Seq(out(DateAdd(d, offset)), out(DateAdd(d, Literal(1)))), childOutput).get
      assert(partial.specs === Seq(ResidualOutput, FusedOutput(0)), s"$offset should decline")
      assert(partial.declines(0).reason ===
        "day offset arithmetic that lowers to a node the offset position does not take")
    }
    // The arithmetic that does lower to an arithmetic node is unaffected.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, Multiply(i, Literal(7), EvalMode.LEGACY)))), childOutput).isDefined)
  }

  test("declines a long add, a short column, a divide, a modulo and a mixed operand") {
    // All four reach the same reason, and that is the point: the arms are guarded on
    // `dataType == IntegerType`, so anything else never enters them and declines as the
    // unsupported expression it is, rather than through an arithmetic-specific message.
    for (e <- Seq(
        Add(Cast(i, LongType), Literal(1L), EvalMode.LEGACY),
        Add(sh, Literal(1.toShort), EvalMode.LEGACY),
        Divide(i, Literal(2), EvalMode.LEGACY),
        Remainder(i, Literal(7)),
        // An operand of the wrong type inside an arm the compiler does take. `intOperand` has
        // a reason of its own for this ("int arithmetic operand of type ..."), but a resolved
        // tree cannot reach it: `BinaryOperator` requires both operands to share a type, so an
        // `Add` of `IntegerType` has two int operands by construction. The cast is what
        // declines, one level down.
        Add(i, Cast(d, IntegerType), EvalMode.LEGACY))) {
      val partial = VarkaExpressionCompiler.compilePartial(
        Seq(out(e), out(DateAdd(d, Literal(1)))), childOutput).get
      assert(partial.specs === Seq(ResidualOutput, FusedOutput(0)), s"$e should be residual")
      assert(partial.declines(0).reason === "unsupported expression", s"$e")
    }
  }

  test("int arithmetic is admitted as a day offset, and a calendar node over it is " +
      "guarded rather than declined") {
    // The offset was a foldable literal (task 38's predecessor) or a bare int column; task 63
    // adds arithmetic over those. The emitter's own shape check on this operand admits the
    // same three kinds.
    val shifted = VarkaExpressionCompiler.compile(
      Seq(out(DateAdd(d, Multiply(i, Literal(7), EvalMode.LEGACY)))), childOutput).get
    assert(shifted.outputs === Seq(new AddDays(new ColumnRef(0),
      new IntArith(IntOp.MUL, Overflow.WRAP, new ColumnRef(1), new LiteralSlot(0)))))
    assert(shifted.outputTypes === Seq(DateType))
    // A calendar node over such a producer still fuses: task 52's range analysis reads any
    // non-literal offset as a column shift, so the emitter guards the producer's own result at
    // run time instead of the compiler declining the shape.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(Year(DateAdd(d, Multiply(i, Literal(7), EvalMode.LEGACY))))), childOutput)
      .isDefined)
    // date_sub takes the same route.
    assert(VarkaExpressionCompiler.compile(
      Seq(out(DateSub(d, Add(i, Literal(1), EvalMode.LEGACY)))), childOutput).get.outputs ===
      Seq(new SubDays(new ColumnRef(0),
        new IntArith(IntOp.ADD, Overflow.WRAP, new ColumnRef(1), new LiteralSlot(0)))))
    // An offset built from an operator no arm lowers still declines, with the offset's own
    // reason rather than a generic one - the message names this position.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(DateAdd(d, Remainder(i, Literal(7)))), out(DateAdd(d, Literal(1)))),
      childOutput).get
    assert(partial.declines(0).reason ===
      "day offset is not a foldable literal, an integer column or int arithmetic")
  }

  test("the int-to-day-interval cast is exact inside its limit and throws one past it") {
    // The admission check, held to Spark's own code: the rewrite assumes getDays undoes the
    // cast wherever the cast does not throw, and that it throws in every mode past the limit.
    assert(limit === 106751991)
    for (v <- Seq(0, 1, -1, 365, -366, limit, -limit)) {
      val micros = IntervalUtils.intToDayTimeInterval(v, DayTimeIntervalType.DAY,
        DayTimeIntervalType.DAY)
      assert(IntervalUtils.getDays(micros) === v)
    }
    for (v <- Seq(limit + 1, -limit - 1, Int.MaxValue, Int.MinValue)) {
      intercept[SparkArithmeticException] {
        IntervalUtils.intToDayTimeInterval(v, DayTimeIntervalType.DAY, DayTimeIntervalType.DAY)
      }
    }
  }

  test("date + CAST(i AS INTERVAL DAY) compiles to AddDays with a bound on " +
      "the offset input") {
    val plus = resolved(Add(d, Cast(i, dayInterval)))
    assert(plus.isInstanceOf[DateAdd], plus)
    val compiled = VarkaExpressionCompiler.compile(Seq(out(plus)), childOutput).get
    assert(compiled.outputs === Seq(new AddDays(new ColumnRef(0), new ColumnRef(1))))
    assert(compiled.inputOrdinals === Seq(0, 2))
    assert(compiled.inputBounds === Seq(VarkaInputBound(1, -limit, limit)))
    assert(compiled.outputTypes === Seq(DateType))
    // The same shape from a plain int column records no bound: date_add wraps in Spark too.
    val plain = VarkaExpressionCompiler.compile(Seq(out(DateAdd(d, i))), childOutput).get
    assert(plain.outputs === compiled.outputs)
    assert(plain.inputBounds === Nil)
  }

  test("date - CAST(i AS INTERVAL DAY) is the negated extraction, compiled to SubDays " +
      "under the same bound") {
    val minus = resolved(Subtract(d, Cast(i, dayInterval)))
    assert(minus.isInstanceOf[DateAdd], minus)
    assert(minus.asInstanceOf[DateAdd].days.isInstanceOf[UnaryMinus], minus)
    val compiled = VarkaExpressionCompiler.compile(Seq(out(minus)), childOutput).get
    assert(compiled.outputs === Seq(new SubDays(new ColumnRef(0), new ColumnRef(1))))
    assert(compiled.inputBounds === Seq(VarkaInputBound(1, -limit, limit)))
  }

  test("i * INTERVAL '1' DAY leaves the date lane - the product widens to DAY TO " +
      "SECOND and the analyzer casts the date to a timestamp") {
    // The admission check's second finding: a multiplied interval is not a day interval,
    // whatever the literal, so the resolver's timestamp branch takes the whole expression and
    // it declines here on the cast, not on the offset. Recorded so the multiply form is not
    // mistaken for a gap in this task's arm.
    val oneDay = Literal.create(java.time.Duration.ofDays(1), dayInterval)
    for (product <- Seq(Multiply(i, oneDay), Multiply(oneDay, i))) {
      val scaled = resolved(product)
      assert(scaled.dataType !== dayInterval, scaled)
      val plus = resolved(Add(d, scaled))
      assert(plus.isInstanceOf[TimestampAddInterval], plus)
      assert(intervalDeclineReason(plus) === "unsupported expression")
    }
  }

  test("declines a stored INTERVAL DAY column, a short column cast to an interval, and " +
      "the rollback of a declining entry's bound") {
    val iv = AttributeReference("iv", dayInterval)()
    val stored = resolved(Add(d, iv))
    assert(intervalDeclineReason(stored, childOutput :+ iv) ===
      "day interval is not an int column cast to days")
    // A short column under the cast is not the int leaf task 38 admits; it declines through
    // the extractor arm rather than being read as an int.
    val short = resolved(Add(d, Cast(sh, dayInterval)))
    assert(intervalDeclineReason(short) === "day interval is not an int column cast to days")
    // Two entries: the first declines after noting nothing, the second is bounded; the bound
    // is keyed on the surviving input table, not on the position the declining entry would
    // have taken.
    val partial = VarkaExpressionCompiler.compilePartial(Seq(
      out(resolved(Add(d, Cast(sh, dayInterval)))),
      out(resolved(Add(d2, Cast(i, dayInterval))))), childOutput).get
    assert(partial.specs.head === ResidualOutput)
    assert(partial.fused.inputOrdinals === Seq(1, 2))
    assert(partial.fused.inputBounds === Seq(VarkaInputBound(1, -limit, limit)))
  }

  test("a bounded offset inside a filter predicate carries the bound on the predicate") {
    val pred = VarkaExpressionCompiler.compilePredicate(
      GreaterThan(resolved(Add(d, Cast(i, dayInterval))), d2), childOutput).get
    assert(pred.fused.inputBounds === Seq(VarkaInputBound(1, -limit, limit)))
  }


  // ---------------------------------------------------------------------------------------------
  // The long lane (task 29): three types, comparisons only
  // ---------------------------------------------------------------------------------------------

  private val l = AttributeReference("l", LongType)()
  private val l2 = AttributeReference("l2", LongType)()
  private val t3 = AttributeReference("t3", TimeType(3))()
  private val t6 = AttributeReference("t6", TimeType(6))()
  private val dt = AttributeReference("dt", DayTimeIntervalType())()
  private val ts = AttributeReference("ts", TimestampType)()
  private val ntz = AttributeReference("ntz", TimestampNTZType)()

  /** `childOutput` plus the long-lane and timestamp columns, on its own list (see above). */
  private val withLong: Seq[Attribute] = childOutput ++ Seq(l, l2, t3, t6, dt, ts, ntz)

  private val longCol = new ColumnRef(0, LaneType.LONG)
  private val longCol2 = new ColumnRef(1, LaneType.LONG)
  private def longSlot(i: Int) = new LiteralSlot(i, LaneType.LONG)

  private val laneReason = "the LONG lane in a kernel on the INT lane: one kernel holds one lane"

  test("a bigint comparison compiles on the long lane, with its own literal table") {
    // The literal is past the int range on purpose: a slot in the int table could not hold it,
    // so a compiler that had quietly put a long literal there would fail here on the value
    // rather than agree by accident.
    val predicate = VarkaExpressionCompiler.compilePredicate(
      GreaterThan(l, Literal(5000000000L)), withLong).get
    assert(predicate.specs.forall(_.fused))
    val fused = predicate.fused
    assert(fused.outputs === Seq(new Compare(CompareOp.GT, longCol, longSlot(0))))
    assert(fused.lane === LaneType.LONG)
    assert(fused.literals === Nil, "the int table stays empty on a long kernel")
    assert(fused.longLiterals === Seq(5000000000L))
    assert(fused.numLiterals === 1)
    // The same value twice is one slot, as in the int table.
    val twice = VarkaExpressionCompiler.compilePredicate(
      And(GreaterThan(l, Literal(7L)), LessThan(l2, Literal(7L))), withLong).get.fused
    assert(twice.longLiterals === Seq(7L))
    assert(twice.inputOrdinals === Seq(5, 6))
  }

  test("greatest, least, CASE WHEN and the validity predicates fuse over long columns") {
    val compiled = VarkaExpressionCompiler.compile(Seq(
      out(Greatest(Seq(l, l2))),
      out(Least(Seq(l, Literal(0L)))),
      out(CaseWhen(Seq((GreaterThan(l, l2), l)), Some(l2))),
      out(If(IsNull(l), l2, l))), withLong).get
    assert(compiled.outputs === Seq(
      new IRGreatest(longCol, longCol2),
      new IRLeast(longCol, longSlot(0)),
      new IfElse(new Compare(CompareOp.GT, longCol, longCol2), longCol, longCol2),
      new IfElse(new IRNot(new IRIsNotNull(longCol)), longCol2, longCol)))
    assert(compiled.outputTypes === Seq(LongType, LongType, LongType, LongType))
    assert(compiled.lane === LaneType.LONG)
    val validity = VarkaExpressionCompiler.compilePredicate(
      And(IsNotNull(l), Not(IsNull(dt))), withLong).get.fused
    assert(validity.outputs === Seq(new IRAnd(new IRIsNotNull(longCol),
      new IRNot(new IRNot(new IRIsNotNull(longCol2))))))
  }

  test("TIME literals take a long slot, a widening precision cast is the child, a narrowing " +
      "one declines") {
    // 12:34:56.789 as nanoseconds of day; type coercion casts the TIME(3) column up to the
    // literal's TIME(6) before comparing, and that cast returns its operand unchanged.
    val nanos = ((12L * 3600 + 34 * 60 + 56) * 1000000000L) + 789000000L
    val widened = VarkaExpressionCompiler.compilePredicate(
      LessThan(Cast(t3, TimeType(6)), Literal(nanos, TimeType(6))), withLong).get.fused
    assert(widened.outputs === Seq(new Compare(CompareOp.LT, longCol, longSlot(0))))
    assert(widened.longLiterals === Seq(nanos))
    assert(widened.inputOrdinals === Seq(7))
    val narrowed = VarkaExpressionCompiler.compilePredicate(
      LessThan(Cast(t6, TimeType(3)), Literal(nanos, TimeType(3))), withLong)
    assert(narrowed.isEmpty)
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(Greatest(Seq(Cast(t6, TimeType(3)), t3)))), withLong)
    assert(partial.isEmpty)
    // The reason is visible through the projection path, where the decline map is kept.
    val mixed = VarkaExpressionCompiler.compilePartial(
      Seq(out(DateAdd(d, Literal(1))), out(Greatest(Seq(Cast(t6, TimeType(3)), t3)))), withLong).get
    assert(mixed.declines(1).reason === "TIME narrowed to a lower precision, which truncates")
  }

  test("a day-time interval literal of a narrower unit reaches the column through the cast " +
      "type coercion inserts; a coarser end field declines") {
    // What the analyzer builds for `dt < INTERVAL '0' SECOND` before the optimizer folds it:
    // the SECOND-typed literal cast up to the column's DAY TO SECOND, which keeps the
    // microseconds whole. The coverage suite compiles the analyzed form, so this is the shape
    // that decides whether the row counts as covered.
    val second = DayTimeIntervalType(DayTimeIntervalType.SECOND, DayTimeIntervalType.SECOND)
    val widened = VarkaExpressionCompiler.compilePredicate(
      LessThan(dt, Cast(Literal(0L, second), DayTimeIntervalType())), withLong).get.fused
    assert(widened.outputs === Seq(new Compare(CompareOp.LT, longCol, longSlot(0))))
    assert(widened.longLiterals === Seq(0L))
    val coarser = DayTimeIntervalType(DayTimeIntervalType.DAY, DayTimeIntervalType.MINUTE)
    val narrowed = VarkaExpressionCompiler.compilePartial(
      Seq(out(DateAdd(d, Literal(1))), out(Greatest(Seq(Cast(dt, coarser), Cast(dt, coarser))))),
      withLong).get
    assert(narrowed.specs === Seq(FusedOutput(0), ResidualOutput))
    assert(narrowed.declines(1).reason ===
      "day-time interval narrowed to a coarser end field, which truncates")
  }

  test("a timestamp column declines with the milestone's reason, in both forms") {
    for (col <- Seq(ts, ntz)) {
      val predicate = VarkaExpressionCompiler.compilePredicate(
        And(GreaterThan(d, d2), GreaterThan(col, col)), withLong).get
      assert(predicate.specs.map(_.fused) === Seq(true, false))
      assert(predicate.specs(1).decline.get.reason === "a timestamp column is outside milestone 5")
      val projection = VarkaExpressionCompiler.compilePartial(
        Seq(out(DateAdd(d, Literal(1))), out(Greatest(Seq(col, col)))), withLong).get
      assert(projection.specs === Seq(FusedOutput(0), ResidualOutput))
      assert(projection.declines(1).reason === "a timestamp column is outside milestone 5")
    }
  }

  test("a projection that mixes lanes fuses the first lane and names the lane for the other") {
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(Greatest(Seq(d, d2))), out(Greatest(Seq(l, l2))), out(DateAdd(d, Literal(1)))),
      withLong).get
    assert(partial.specs === Seq(FusedOutput(0), ResidualOutput, FusedOutput(1)))
    assert(partial.declines(1).reason === laneReason)
    // The demoted entry left nothing behind: no long literal, no long input.
    assert(partial.fused.longLiterals === Nil)
    assert(partial.fused.inputOrdinals === Seq(0, 1))
    assert(partial.fused.lane === LaneType.INT)
    // The first entry fixes the lane, whichever lane it is.
    val longFirst = VarkaExpressionCompiler.compilePartial(
      Seq(out(Greatest(Seq(l, Literal(3L)))), out(Greatest(Seq(d, d2)))), withLong).get
    assert(longFirst.specs === Seq(FusedOutput(0), ResidualOutput))
    assert(longFirst.declines(1).reason ===
      "the INT lane in a kernel on the LONG lane: one kernel holds one lane")
    assert(longFirst.fused.literals === Nil)
    assert(longFirst.fused.longLiterals === Seq(3L))
  }

  test("a predicate that mixes lanes fuses the first lane's conjuncts and names the lane") {
    val predicate = VarkaExpressionCompiler.compilePredicate(
      And(And(GreaterThan(d, d2), GreaterThan(l, l2)), LessThan(d, d2)), withLong).get
    assert(predicate.specs.map(_.fused) === Seq(true, false, true))
    assert(predicate.specs(1).decline.get.reason === laneReason)
    assert(predicate.fused.lane === LaneType.INT)
    assert(predicate.fused.inputOrdinals === Seq(0, 1))
  }

  test("a CASE whose condition and branches disagree on lane declines with the lane reason") {
    // `CASE WHEN l > 0 THEN d ELSE d2` type-checks: the condition is on the long lane and the
    // branches on the int one, and the blend's constructor would refuse the mix. The compiler
    // asks first and records the reason instead of throwing.
    val partial = VarkaExpressionCompiler.compilePartial(
      Seq(out(DateAdd(d, Literal(1))), out(If(GreaterThan(l, Literal(0L)), d, d2))), withLong).get
    assert(partial.specs === Seq(FusedOutput(0), ResidualOutput))
    assert(partial.declines(1).reason ===
      "one kernel holds one lane, and this mixes the LONG and INT lanes")
    val orMix = VarkaExpressionCompiler.compilePredicate(
      Or(GreaterThan(l, Literal(0L)), GreaterThan(d, d2)), withLong)
    assert(orMix.isEmpty)
  }

  test("long arithmetic and IN stay declined: they are tasks 104 and 102, not this one") {
    val add = VarkaExpressionCompiler.compilePartial(
      Seq(out(DateAdd(d, Literal(1))), out(Add(l, Literal(1L)))), withLong).get
    assert(add.specs === Seq(FusedOutput(0), ResidualOutput))
    assert(add.declines(1).reason === "unsupported expression")
    val in = VarkaExpressionCompiler.compilePredicate(
      And(GreaterThan(d, d2), In(l, Seq(Literal(1L), Literal(2L)))), withLong).get
    assert(in.specs.map(_.fused) === Seq(true, false))
    assert(in.specs(1).decline.get.reason === "unsupported predicate")
  }

  // --- Task 169: the emitter's size decline, taken at plan time -------------------------------

  /** A balanced `greatest` over `add_months(d, lo..hi)`: one output, as heavy as it is wide. */
  private def heavy(lo: Int, hi: Int): Expression =
    if (lo == hi) AddMonths(d, Literal(lo))
    else Greatest(Seq(heavy(lo, (lo + hi) / 2), heavy((lo + hi) / 2 + 1, hi)))

  /** The largest method of the kernel `fused` makes under `budget`, measured from its bytes. */
  private def largestMethod(fused: CompiledVarkaProjection, budget: Int): Int = {
    val bytes = VarkaLoopEmitter.emit(
      s"org.apache.spark.sql.varka.execution.VarkaCompilerSizeProbe${System.nanoTime()}",
      fused.outputs.asJava,
      fused.inputOrdinals.size, fused.numLiterals, null, null,
      VarkaEmitOptions.DEFAULTS.withMethodByteBudget(budget))
    val methods = VarkaEmitterTestSupport.methodNames(bytes).asScala.filter(_ != "<init>")
    methods.map(VarkaEmitterTestSupport.codeSize(bytes, _)).max
  }

  test("an output the emitter declines in bytes is residual at plan time, with the reason, " +
      "and the rest of the projection still fuses (task 169)") {
    // PLAN_TASK_169.md 3.1: the weight caps admit a balanced greatest over thirty-two
    // add_months - sixty-three ops, one under MAX_FUSED_NODES - and its one group's loop method
    // is past HugeMethodLimit, which no regroup can shrink. The compiler asks the emitter and
    // demotes exactly that entry; the entries beside it fuse. Without the byte budget the
    // emitter serves it, into a method HotSpot never compiles, and so does the compiler - and
    // then month(d) is the one left out, because year(d) and the tree already fill the op cap.
    // Demoting the tree is what makes room for it.
    val list = Seq(out(Year(d)), out(heavy(1, 32)), out(Month(d)))
    val partial = VarkaExpressionCompiler.compilePartial(list, childOutput).get
    assert(partial.specs === Seq(FusedOutput(0), ResidualOutput, FusedOutput(1)))
    val reason = partial.declines(1).reason
    assert(reason.startsWith("over the emitter's method budget (") &&
      reason.contains("HugeMethodLimit"), reason)
    assert(partial.fused.outputs.size === 2)
    val legacy = VarkaExpressionCompiler.compilePartial(list, childOutput,
      VarkaEmitOptions.DEFAULTS.withMethodByteBudget(0)).get
    assert(legacy.specs === Seq(FusedOutput(0), FusedOutput(1), ResidualOutput))
    assert(legacy.declines(2).reason === "exceeds the emitter's fused budget")
    // The heavy output alone: nothing is left to fuse, so the projection does not fuse at all,
    // and the tools still say why.
    assert(VarkaExpressionCompiler.compilePartial(Seq(out(heavy(1, 32))), childOutput).isEmpty)
    assert(VarkaExpressionCompiler.declines(Seq(out(heavy(1, 32))), childOutput)(0).reason
      .startsWith("over the emitter's method budget ("))
  }

  test("a class-wide decline demotes the last-admitted outputs until the driver fits " +
      "(task 169)") {
    // The driver sets up every output and cannot be regrouped, so over a budget the
    // single-output groups meet it is the driver that declines, naming no output, and the
    // compiler demotes from the end. Sixty make_date outputs put the driver past 2000 bytes.
    val list = (1 to 60).map { k =>
      out(MakeDate(Year(d), Month(d), Literal((k - 1) % 28 + 1), failOnError = true))
    }
    val budget = VarkaEmitOptions.DEFAULTS.withMethodByteBudget(2000)
    val partial = VarkaExpressionCompiler.compilePartial(list, childOutput, budget).get
    val fused = partial.specs.count(_.isInstanceOf[FusedOutput])
    assert(fused > 1 && fused < 60, s"$fused of 60 fused under a 2000-byte budget")
    assert(partial.specs.take(fused).forall(_.isInstanceOf[FusedOutput]) &&
      partial.specs.drop(fused).forall(_ == ResidualOutput), "the residual ones are a suffix")
    for (at <- fused until 60) {
      assert(partial.declines(at).reason.startsWith("over the emitter's method budget (run"),
        partial.declines(at).reason)
    }
    assert(largestMethod(partial.fused, 2000) <= 2000)
  }

  test("a filter whose folded condition is over the budget demotes its last-admitted " +
      "conjunct (task 169)") {
    // The fused conjuncts fold into one condition root, which the emitter cannot split by name,
    // so the compiler drops the last conjunct it admitted and asks again. The budget is set
    // between what two of these conjuncts and all three emit, measured rather than assumed.
    val conjuncts = (1 to 3).map(k => GreaterThan(heavy(k * 10, k * 10 + 3), d2))
    val condition = conjuncts.reduceLeft[Expression](And(_, _))
    val two = VarkaExpressionCompiler.compilePredicate(
      conjuncts.take(2).reduceLeft[Expression](And(_, _)), childOutput,
      VarkaEmitOptions.DEFAULTS.withMethodByteBudget(0)).get
    val three = VarkaExpressionCompiler.compilePredicate(condition, childOutput,
      VarkaEmitOptions.DEFAULTS.withMethodByteBudget(0)).get
    val limit = largestMethod(two.fused, 60000)
    assert(largestMethod(three.fused, 60000) > limit, "the third conjunct has to add bytes")
    val predicate = VarkaExpressionCompiler.compilePredicate(condition, childOutput,
      VarkaEmitOptions.DEFAULTS.withMethodByteBudget(limit)).get
    assert(predicate.specs.map(_.fused) === Seq(true, true, false))
    assert(predicate.specs(2).decline.get.reason.startsWith("over the emitter's method budget"))
  }

  test("asking the emitter at plan time costs one emission per shape: a second compile of " +
      "the same projection is a cache hit (task 169)") {
    // PLAN_TASK_169.md 2.3: the compiler runs at planning, for EXPLAIN and once per task on the
    // executor, so the question goes through the shape cache with the key the evaluator builds.
    val list = Seq(out(Year(d)), out(DayOfMonth(DateAdd(d, Literal(4321)))))
    VarkaExpressionCompiler.compilePartial(list, childOutput)
    val misses = VarkaShapeCache.missCount
    VarkaExpressionCompiler.compilePartial(list, childOutput)
    VarkaExpressionCompiler.compilePartial(list, childOutput)
    assert(VarkaShapeCache.missCount === misses, "a repeated compile emitted again")
  }
}
