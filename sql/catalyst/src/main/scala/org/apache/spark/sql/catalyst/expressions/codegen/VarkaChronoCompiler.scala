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

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.spark.SparkIllegalArgumentException
import org.apache.spark.sql.catalyst.expressions.{Add, AddMonths, BoundReference, Cast, DateAdd,
  DateAddYMInterval, DateDiff, DateFromUnixDate, DateSub, DateVarkaSupport, DayOfMonth, DayOfWeek,
  DayOfYear, Expression, ExtractANSIIntervalDays, LastDay, Literal, MakeDate, Month, Multiply,
  NextDay, Quarter, Subtract, TruncDate, UnaryMinus, UnixDate, WeekDay, WeekOfYear, Year,
  YearOfWeek}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaDerivedKind,
  VarkaRangeAnalysis, VarkaValueRange, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaRangeAnalysis.{GuardPolicy,
  Kind}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays,
  AddMonths => IRAddMonths, DateDiff => IRDateDiff, DayOfMonth => IRDayOfMonth,
  DayOfWeek => IRDayOfWeek, DayOfWeekIso, DayOfYear => IRDayOfYear, Greatest => IRGreatest,
  GuardedDay, IfElse, IntNeg, LastDay => IRLastDay, Least => IRLeast, LiteralSlot,
  MakeDate => IRMakeDate, Month => IRMonth, NextDay => IRNextDay, Quarter => IRQuarter,
  SubDays, ThursdayOf, TruncDate => IRTruncDate, TruncDateDynamic => IRTruncDateDynamic, TruncLevel,
  WeekDay => IRWeekDay, WeekOfYear => IRWeekOfYear, Year => IRYear}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{DayTimeIntervalType, IntegerType, StringType,
  YearMonthIntervalType}
import org.apache.spark.unsafe.types.UTF8String

/**
 * The calendar family of the compiler: date arithmetic, the day-of-week nodes, `next_day`, the
 * civil-field extractions and `make_date`, `last_day`, the ISO week, `trunc`, and month arithmetic
 * - every Catalyst expression that lowers onto `VarkaChronoLowering`'s nodes - with the range
 * analysis that admits a day producer under a calendar node (`admitCalendar`, `rearm`) and the
 * compile-time folds of a weekday name and a trunc level.
 *
 * The arms are a partial function `VarkaExpressionCompiler.compileNode` chains after its leaves;
 * the helpers below them are this family's alone. The shared operand helpers and the recursion come
 * from `VarkaExpressionCompiler` through its import.
 */
private[codegen] object VarkaChronoCompiler {

  /**
   * The calendar arms of `compileNode`, in their original order.
   */
  private[codegen] def arms(
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): PartialFunction[Expression, Option[VarkaVectorIR]] = {
    // unix_date/date_from_unix_date are Spark's own `input.asInstanceOf[Int]` in full - a date IS a
    // day count, so both are a pure type relabel with nothing to compute. Unwrapping to the child
    // rather than adding an IR node means `SELECT unix_date(d)` and `SELECT d` compile to the same
    // IR and share a shape hash - correct, since kernel identity is about lane math and theirs is
    // identical; the entry's output type still comes from the Catalyst expression, not the IR.
    // `date_from_unix_date`'s child is an integer column, and no value leaf reads a bare int
    // column, so this arm declines through the ordinary non-date-column path below.
    case UnixDate(child) =>
      compileNode(child, inputs, literals, sink)
    case DateFromUnixDate(child) =>
      compileNode(child, inputs, literals, sink)
    case DateAdd(child, days) =>
      // The date child compiles before the offset, matching CaseWhen's rule a few cases below:
      // ordinals and literal slots register in reading order. A foldable literal offset registers
      // no ordinal, so this ordering is new in an observable way now that an offset can be a
      // column: when BOTH operands are unfusable, DeclineSink's "first note wins" rule reports the
      // child's reason, not the offset's (pinned by VarkaExpressionCompilerSuite's "with two
      // independently unfusable operands, the child's reason is reported" test).
      days match {
        // `date - INTERVAL n DAY`: the analyzer spells it as an add of the negated
        // day count, `DateAdd(d, UnaryMinus(ExtractANSIIntervalDays(r)))`. Inside the cast's
        // own bound the negation cannot overflow, so it is absorbed into SubDays - no
        // UnaryMinus node exists and none is needed.
        case UnaryMinus(DayIntervalOffset(br), _) =>
          for {
            node <- compileNode(child, inputs, literals, sink)
            offsetNode <- compileOffset(DayIntervalOffset.wrap(br), inputs, literals, sink)
          } yield new SubDays(node, offsetNode)
        case _ =>
          for {
            node <- compileNode(child, inputs, literals, sink)
            offsetNode <- compileOffset(days, inputs, literals, sink)
          } yield new AddDays(node, offsetNode)
      }
    case DateSub(child, days) =>
      for {
        node <- compileNode(child, inputs, literals, sink)
        offsetNode <- compileOffset(days, inputs, literals, sink)
      } yield new SubDays(node, offsetNode)
    case DateDiff(end, start) =>
      for {
        endNode <- compileNode(end, inputs, literals, sink)
        startNode <- compileNode(start, inputs, literals, sink)
      } yield new IRDateDiff(endNode, startNode)
    case DayOfWeek(child) =>
      compileNode(child, inputs, literals, sink).map(new IRDayOfWeek(_))
    case WeekDay(child) =>
      compileNode(child, inputs, literals, sink).map(new IRWeekDay(_))
    case Add(WeekDay(child), Literal(1, IntegerType), _) =>
      compileNode(child, inputs, literals, sink).map(new DayOfWeekIso(_))
    case Add(Literal(1, IntegerType), WeekDay(child), _) =>
      compileNode(child, inputs, literals, sink).map(new DayOfWeekIso(_))
    // next_day: a foldable weekday is resolved at compile time and travels as a
    // runtime literal. An unrecognized or null one declines rather than throws - it is the
    // row engine's business, and it has two different behaviours for it depending on ANSI
    // mode which Varka must not try to reproduce. Evaluating a foldable-but-computed weekday
    // expression (not only a bare Literal) can itself throw for reasons unrelated to the
    // weekday name - that must decline too, per the ghost-fallback contract, rather than
    // crash planning.
    case NextDay(start, dow, _) if dow.foldable =>
      for {
        k <- foldWeekday(dow, sink)
        d <- compileNode(start, inputs, literals, sink)
      } yield new IRNextDay(d, intSlot(k, literals))
    // A weekday column: the kernel reads an int32 column the evaluator derives
    // from the names, per batch, by the row engine's own parser (WeekdayLeaf), so the node
    // is the same and only the offset's origin differs. ANSI mode is part of the derived
    // input's kind, since NextDay fixes failOnError at construction. Any collation is
    // admitted because the parser ignores it. An expression over the column stays the row
    // engine's: the leaf reads a stored column.
    case NextDay(start, br: BoundReference, failOnError) if br.dataType.isInstanceOf[StringType] =>
      val kind = if (failOnError) VarkaDerivedKind.WEEKDAY_ANSI else VarkaDerivedKind.WEEKDAY
      compileNode(start, inputs, literals, sink).map(new IRNextDay(_, derivedRef(br, kind, inputs)))
    case n: NextDay =>
      sink.note("next_day with a weekday that is neither a literal nor a column", n)
      None
    // The calendar extractions. One civil-from-days decomposition per node, so two
    // fields of the same date are computed twice - see VarkaVectorIR.Year for why. The child
    // goes through `calendarInput`: the decomposition is exact only over
    // VarkaChrono's narrowed range, and the compiler is where a shift that can leave it is
    // known before anything runs.
    case expr @ Year(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRYear(_))
    case expr @ Month(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRMonth(_))
    case expr @ DayOfMonth(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRDayOfMonth(_))
    case expr @ Quarter(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRQuarter(_))
    case expr @ DayOfYear(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRDayOfYear(_))
    // make_date(y, m, d): three int operands, each a column or a literal, and the
    // evaluation mode captured on the expression - two modes are two shapes.
    case MakeDate(y, m, d, failOnError) =>
      for {
        yy <- compileIntOperand(y, "make_date's year", inputs, literals, sink)
        mm <- compileIntOperand(m, "make_date's month", inputs, literals, sink)
        dd <- compileIntOperand(d, "make_date's day", inputs, literals, sink)
      } yield new IRMakeDate(yy, mm, dd, failOnError)
    case expr @ LastDay(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRLastDay(_))
    // weekofyear, extract(WEEK) and date_part: the ISO week by the Thursday rule - the
    // week tail over the Thursday of the day's week, two nodes so the prefix runs over the
    // shifted day and so extract(YEAROFWEEK) is Year over the same ThursdayOf. The
    // calendar node's child is the shift, so the range analysis admits the shift, not the day.
    case expr @ WeekOfYear(child) =>
      compileNode(child, inputs, literals, sink)
        .flatMap(c => admitCalendar(new ThursdayOf(c), expr, literals, sink))
        .map(new IRWeekOfYear(_))
    // extract(YEAROFWEEK) / date_part('YEAROFWEEK'): the ISO week-based year is the
    // calendar year of the same Thursday, so Year over the same shift - one prefix for both
    // fields under CSE, and nothing in the emitter.
    case expr @ YearOfWeek(child) =>
      compileNode(child, inputs, literals, sink)
        .flatMap(c => admitCalendar(new ThursdayOf(c), expr, literals, sink))
        .map(new IRYear(_))
    // trunc(date, fmt): the format resolves at compile time, like next_day's weekday, because the
    // level chooses which code is emitted. YEAR, MONTH and QUARTER are one node with the level as a
    // shape-bearing field; WEEK is Spark's own definition, next_day(d - 7, 'MONDAY'), rewritten
    // onto the nodes the compiler already has - the unix_date pattern of retiring an expression
    // onto existing IR. A stored string column is the dynamic node below. Everything else declines,
    // each for its own reason: the row engine answers those with a NULL column, which no IR node
    // can produce.
    case expr @ TruncDate(date, format) if format.foldable =>
      foldTruncLevel(format, sink).flatMap {
        case ToLevel(level) =>
          calendarInput(date, expr, inputs, literals, sink).map(new IRTruncDate(_, level))
        case ToWeek =>
          compileNode(date, inputs, literals, sink).map { d =>
            val week = intSlot(7, literals)
            // next_day's slot holds dayOfWeek - 1; Monday through the same parser
            // foldWeekday uses, so the constant is the definition's, not a retyped 3.
            val monday = intSlot(
              DateTimeUtils.getDayOfWeekFromString(UTF8String.fromString("MONDAY")) - 1, literals)
            new IRNextDay(new SubDays(d, week), monday)
          }
      }
    // A format column: the level is read per batch by the evaluator's derived leaf
    // (TruncLevelLeaf) into an int32 column of parseTruncLevel's codes, on next_day's pattern,
    // and the kernel computes every period and selects on it. No ANSI twin in the
    // kind: TruncDate has no error path, so a null, unrecognised or sub-day format is a NULL
    // row in either mode - the leaf's null lane, through the node's word. Any collation is
    // admitted because the parser ignores it; an expression over the column stays the row
    // engine's, since the leaf reads a stored column.
    case expr @ TruncDate(date, br: BoundReference) if br.dataType.isInstanceOf[StringType] =>
      calendarInput(date, expr, inputs, literals, sink)
        .map(new IRTruncDateDynamic(_, derivedRef(br, VarkaDerivedKind.TRUNC_LEVEL, inputs)))
    case t: TruncDate =>
      sink.note("trunc with a non-foldable format", t)
      None
    // Month arithmetic: add_months(d, n) and d +- INTERVAL n MONTH/YEAR are the same
    // node - AddMonthsBase's two subclasses differ only in where the month count comes from,
    // both physically an Int. `d - INTERVAL n MONTH` arrives as DatetimeSub, already replaced
    // by its DateAddYMInterval(l, UnaryMinus(r)) by the time a real query reaches here.
    // The date child compiles before the month count, matching DateAdd's rule above: ordinals
    // register in reading order, so add_months(d, m) puts d at ordinal 0 and m at ordinal 1 -
    // and when both decline, DeclineSink's "first note wins" rule reports the date's reason.
    case expr @ AddMonths(startDate, numMonths) =>
      for {
        node <- calendarInput(startDate, expr, inputs, literals, sink)
        months <- compileMonths(numMonths, inputs, literals, sink)
      } yield new IRAddMonths(node, months)
    case expr @ DateAddYMInterval(date, interval) =>
      for {
        node <- calendarInput(date, expr, inputs, literals, sink)
        months <- compileMonths(interval, inputs, literals, sink)
      } yield new IRAddMonths(node, months)
  }

  /** The `extract(DAYOFWEEK_ISO)` shape, which keeps its own node rather than becoming
   *  int arithmetic over a `weekday` output. Either operand order, exactly as that arm reads. */
  private[codegen] def isDayOfWeekIso(a: Add): Boolean = (a.left, a.right) match {
    case (WeekDay(_), Literal(1, IntegerType)) => true
    case (Literal(1, IntegerType), WeekDay(_)) => true
    case _ => false
  }

  /**
   * The epoch days a day-valued node can produce: [[VarkaRangeAnalysis]]'s `DAY` query, under
   * `ARMED` for a calendar consumer - which arms the runtime guard on every column-offset
   * producer below it - and `NONE` for anything else.
   */
  private[codegen] def dayRange(node: VarkaVectorIR, literals: mutable.LinkedHashMap[Int, Int],
      policy: GuardPolicy): VarkaValueRange.Range =
    VarkaRangeAnalysis.range(node, Kind.DAY, policy, literalAt(literals))

  /**
   * Whether every day in the interval decomposes exactly. Asymmetric on purpose. Downward,
   * `NARROW_MIN_DAYS` binds: below it the narrowing is undefined and no correction rescues it.
   * Upward, the binding limit is not `NARROW_MAX_DAYS` - that is the era step's *shift* domain and
   * the range the runtime guards enforce on a producer's own result - but how far the
   * decomposition stays exact on a value already in hand, which `eraOf`'s one-era correction
   * carries about 9,266 years further. So an upward shift over a guarded day producer, which used
   * to decline conservatively, is admitted where it is genuinely exact.
   */
  private def decomposesExactly(b: VarkaValueRange.Bounded): Boolean =
    b.within(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS)

  /**
   * The day offset of a `date_add`/`date_sub`: a folded literal keeps today's `LiteralSlot` shape
   * (existing plans and their cached kernels are untouched), a non-foldable offset is a bare
   * `IntegerType` column, and it may also be int arithmetic over those - `date_add(d, i * 7)`. It
   * is still deliberately not a general `compileNode` recursion. `compileNode`'s `BoundReference`
   * leaf stays `DateType`-only: widening it instead of this dedicated path would let an int column
   * reach every other position that calls `compileNode` too (`Compare`, `DateDiff`, `Coalesce`,
   * `Greatest`...), fusing plain integer-vs-integer predicates that were never part of this task's
   * scope ("do not open it wider", `PLAN_TASK_38.md` 6).
   */
  private[codegen] def compileOffset(
      days: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    DateVarkaSupport.foldDaysOffset(days) match {
      case Some(offset) =>
        Some(intSlot(offset, literals))
      case None =>
        days match {
          case br: BoundReference if br.dataType == IntegerType =>
            Some(columnRef(br, inputs))
          case br: BoundReference =>
            sink.note(s"non-integer day offset column of type ${br.dataType.simpleString}", br)
            None
          // A day interval built from an int column: `CAST(i AS INTERVAL DAY)`. The
          // cast multiplies by a day's micros and the extractor divides them back out, so the
          // day count is `i` itself - wherever the cast does not throw. Past
          // INTERVAL_DAY_LIMIT_DAYS it throws in every mode, where a kernel would wrap, so the
          // column carries a bound the evaluator checks per batch and declines the batch to
          // the row engine when a live lane is outside.
          case DayIntervalOffset(br) =>
            sink.bound(br.ordinal, -VarkaChrono.INTERVAL_DAY_LIMIT_DAYS,
              VarkaChrono.INTERVAL_DAY_LIMIT_DAYS)
            Some(columnRef(br, inputs))
          case e: ExtractANSIIntervalDays =>
            // A stored INTERVAL DAY column is int64 microseconds, which no int32 lane can read;
            // this is scoped to the int-cast form.
            sink.note("day interval is not an int column cast to days", e)
            None
          // Arithmetic over an int column as the offset, `date_add(d, i * 7)`. What makes this safe
          // above rather than only here is `dayRange`, which reads any non-literal offset as a
          // column shift: a calendar node over such a producer still gets the runtime range guard,
          // exactly as it does for a bare column offset. Only the four arithmetic shapes, not every
          // `IntegerType` expression - the emitter's own check on this operand admits the same
          // three node kinds and nothing else, so the two stay a matched pair rather than one
          // silently outgrowing the other.
          case arith @ (_: Add | _: Subtract | _: Multiply | _: UnaryMinus)
              if arith.dataType == IntegerType =>
            // The compiled root has to be a shape the offset position takes, not merely something
            // built from an arithmetic expression: `weekday(d) + 1` is an `Add` that lowers to
            // `DayOfWeekIso`, which the offset position does not accept. Admitting it here would
            // put an entry through `compilePartial` as fused and let the emitter's refusal fire at
            // emit time, where the evaluator turns it into a silent per-batch fallback while
            // EXPLAIN still claims fusion - the ghost fallback `sql/varka/AGENTS.md` forbids. The
            // test is the emitter's own predicate rather than a copy of its list, so the two cannot
            // drift apart again.
            compileNode(arith, inputs, literals, sink).flatMap { n =>
              if (VarkaVectorIR.isDayOffsetShape(n)) {
                Some(n)
              } else {
                sink.note("day offset arithmetic that lowers to a node the offset " +
                  "position does not take", arith)
                None
              }
            }
          case other =>
            sink.note(
              "day offset is not a foldable literal, an integer column or int arithmetic",
              other)
            None
        }
    }
  }

  /**
   * "An int column, as a day interval", the one spelling of it that stays a date-lane
   * expression: `ExtractANSIIntervalDays` over `CAST(i AS INTERVAL DAY)`, which is
   * exactly `i` inside the cast's bound. `i * INTERVAL '1' DAY` is not a second spelling: a
   * multiplied interval widens to DAY TO SECOND, so the analyzer casts the date to a timestamp
   * and the expression leaves the date lane (`TimestampAddInterval`, milestone 5). `wrap`
   * rebuilds the cast form so the `DateAdd` arm can hand the negated case back to
   * `compileOffset` and share its bound and reason.
   */
  private object DayIntervalOffset {
    def unapply(e: Expression): Option[BoundReference] = e match {
      case ExtractANSIIntervalDays(
          Cast(br: BoundReference, DayTimeIntervalType(DayTimeIntervalType.DAY,
            DayTimeIntervalType.DAY), _, _)) if br.dataType == IntegerType => Some(br)
      case _ => None
    }
    def wrap(br: BoundReference): Expression =
      ExtractANSIIntervalDays(Cast(br, DayTimeIntervalType(DayTimeIntervalType.DAY)))
  }

  /**
   * "An int column, as a year-month interval", `DayIntervalOffset`'s twin for months: `CAST(i
   * AS INTERVAL MONTH)` reaches the compiler as the cast itself, with no extraction wrapper -
   * unlike `DayIntervalOffset`, whose micros-typed cast needs `ExtractANSIIntervalDays` to read
   * back out - because `Cast.intToYearMonthInterval` returns `v` unchanged for an end field of
   * `MONTH` (checked in `PLAN_TASK_60.md` section 2), so the cast node's own evaluated value
   * already is the month count. `i * INTERVAL '1' MONTH` is not a second spelling, for the same
   * reason `DayIntervalOffset`'s doc gives for days: a multiplied interval leaves the date lane.
   */
  private object MonthIntervalOffset {
    def unapply(e: Expression): Option[BoundReference] = e match {
      case Cast(br: BoundReference, YearMonthIntervalType(YearMonthIntervalType.MONTH,
          YearMonthIntervalType.MONTH), _, _) if br.dataType == IntegerType => Some(br)
      case _ => None
    }
  }

  /**
   * The month count of `add_months`/`date +- INTERVAL n MONTH/YEAR`: a foldable count folds to a
   * bounded `LiteralSlot`, the same two reasons as before - not foldable, or foldable but outside
   * `VarkaChrono`'s `MONTH_ARITH_MIN/MAX_MONTHS`, the range the emitter's `/ 12` magic multiply
   * covers (`PLAN_TASK_40.md` section 2.2). A non-foldable count is a `ColumnRef` when it is a bare
   * `IntegerType` column (`add_months(d, m)`) or the `MONTH`-end interval cast above (`d + CAST(m
   * AS INTERVAL MONTH)`) - the emitter bounds it lanewise at run time instead (the runtime guard on
   * `AddMonths` itself, since the exactness domain is the count's alone, `PLAN_TASK_60.md` section
   * 2). A `YearMonthIntervalType` column with no such cast declines by name: the Arrow cache holds
   * it as an `IntervalYearVector`, which `isArrowBacked` does not read, so admitting it would fuse
   * at plan time and then refuse every batch. `d - INTERVAL m MONTH` arrives as `UnaryMinus` over
   * the cast and is not matched here; it declines until the int negate node composes with it.
   */
  private[codegen] def compileMonths(
      months: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    DateVarkaSupport.foldDaysOffset(months) match {
      case Some(m) if m < VarkaChrono.MONTH_ARITH_MIN_MONTHS
          || m > VarkaChrono.MONTH_ARITH_MAX_MONTHS =>
        sink.note("month count outside the range the emitter's magic multiply covers", months)
        None
      case Some(m) =>
        Some(intSlot(m, literals))
      case None =>
        months match {
          case br: BoundReference if br.dataType == IntegerType =>
            Some(columnRef(br, inputs))
          case MonthIntervalOffset(br) =>
            Some(columnRef(br, inputs))
          // `Cast.intToYearMonthInterval` returns `12 * v` for a YEAR end field, so the value
          // under this cast is a count of years and the node needs months - unlike the MONTH-end
          // cast MonthIntervalOffset matches, which returns `v` unchanged.
          //
          // The emitter's month-count check accepts int arithmetic once `requireMonthCountShape`
          // has split it from `next_day`'s weekday, which shares no guard with it. So this is that
          // multiply, always checked because `IntervalUtils.intToYearMonthInterval` uses
          // `Math.multiplyExact` whatever the session's ANSI mode, and `arithOver`'s bound is what
          // removes the check - `CAST(year(d) AS INTERVAL YEAR)` fuses on its 40000 bound while a
          // bare column declines, as every unbounded checked multiply does. A foldable year count
          // never reaches this arm at all: `foldDaysOffset` above folds `CAST(5 AS INTERVAL YEAR)`
          // to 60 before the match.
          case c @ Cast(operand, YearMonthIntervalType(YearMonthIntervalType.YEAR,
              YearMonthIntervalType.YEAR), _, _) if operand.dataType == IntegerType =>
            VarkaIntervalCompiler.yearsToMonths(c, inputs, literals, sink)
          // `d - ym_col`, which the analyzer spells `DateAddYMInterval(d, UnaryMinus(ym))`, so the
          // count is a negation of an interval column. Its check comes off wherever a negation's
          // does, which is any bound at all - and a bare interval column has none, so this keeps
          // its check and is guarded on the count's value at run time exactly as a plain column
          // count is.
          case u @ UnaryMinus(operand, _)
              if operand.dataType.isInstanceOf[YearMonthIntervalType] =>
            VarkaIntervalCompiler.intervalOperand(operand, "the negated month count", inputs,
                literals, sink).map { x =>
              new IntNeg(VarkaIntervalCompiler.negationMode(x, literals), x)
            }
          // `d + ym_col`. The stored value is the month count in every unit, so this is the
          // column-count `AddMonths` exactly, with the same runtime guard on the count's lanes -
          // the guard reads the value and not the column's Spark type. It declined until now only
          // because the evaluator would not read the vector, and the evaluator would not read it
          // because no arm asked.
          case br: BoundReference if br.dataType.isInstanceOf[YearMonthIntervalType] =>
            Some(columnRef(br, inputs))
          // AddMonths.inputTypes is Seq(DateType, IntegerType) exactly - unlike DateAdd, which
          // accepts a TypeCollection - so the analyzer widens a Short/Byte count with a cast and
          // a bare non-integer column never reaches here. The cast is what arrives, and it gets
          // a reason naming the column's own type: the int32 lanes read an IntegerType column,
          // and a SmallIntVector is not one, so this declines rather than fusing at plan time
          // and refusing every batch. Fusing it needs a task-59-style derived leaf to widen the
          // column ahead of the kernel, which is its own task.
          case Cast(br: BoundReference, IntegerType, _, _) if br.dataType != IntegerType =>
            sink.note(s"month count column of type ${br.dataType.simpleString} reaches the " +
              "compiler behind a widening cast; the int32 lanes read only an integer column", br)
            None
          case other =>
            sink.note("month count is neither a foldable literal nor an integer column", other)
            None
        }
    }
  }

  /**
   * Compiles a calendar node's child and admits it only if `dayRange` says the decomposition will
   * see a day inside the narrowed range. A bounded interval that leaves it declines the entry -
   * free at run time, and the row engine computes it correctly. A column-driven producer
   * contributes the interval its own runtime guard establishes (their runtime halves) rather than a
   * special verdict, so a shift above such a producer is tested here like any other. An unknown
   * producer declines, so a node this analysis has not been taught fails safe as a residual entry
   * rather than as a wrong answer.
   */
  private def calendarInput(
      child: Expression,
      calendar: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    compileNode(child, inputs, literals, sink).flatMap(admitCalendar(_, calendar, literals, sink))
  }

  /**
   * The result of re-arming a subtree: the rewritten node, the interval it now produces, and
   * whether a runtime-valued shift has contributed to that interval since the last
   * [[GuardedDay]] - which is what decides whether a further overflow can be guarded or has to
   * decline.
   */
  private case class Rearmed(node: VarkaVectorIR, range: VarkaValueRange.Range, runtime: Boolean)

  /**
   * Insert [[GuardedDay]] wherever the running interval would leave the range the calendar
   * lowering decomposes exactly, resetting the interval there.
   *
   * <p>The producer guard covers one producer, and promises the whole narrowed range - so a second
   * guarded shift above it has no budget left and [[admitCalendar]] must decline the expression,
   * although both shifts are individually fine. Re-arming spends the range again: at a node whose
   * interval overflows, the emitted check makes everything above it start from `[NARROW_MIN_DAYS,
   * NARROW_MAX_DAYS]` once more.
   *
   * <p>It is a rewrite rather than a set of positions because the emitter cannot be told where
   * to check: it never sees literal values, so it cannot run this arithmetic, and
   * `VarkaShapeKey` keys a cached kernel on the IR without them - so a placement carried beside
   * the IR would let one shape be served another's guards. In the IR, the shape key separates
   * them (`PLAN_TASK_93.md` 3.3.1).
   *
   * <p><b>What must still decline, and the rule is narrower than it first looks.</b> A node is
   * re-armed only when *its own* shift is runtime-valued - a column offset, a column month
   * count - and not merely when something below it was. A literal shift that leaves the range
   * leaves it for every row, so a check above it would emit a kernel that reports every batch:
   * a slower way to decline than declining once, here, for free. The first version of this
   * rule tested "did any runtime value contribute", and admitted
   * `year(date_add(date_add(d, i), 20000000))` on the strength of the inner column offset,
   * which is exactly that mistake.
   *
   * <p>Descends only the day-typed children the analysis bounds, which is exactly [[dayRange]]'s
   * own set; anything else is returned untouched and its interval speaks for itself.
   */
  private def rearm(
      node: VarkaVectorIR,
      literals: mutable.LinkedHashMap[Int, Int]): Rearmed = {
    def shiftIsRuntime(offset: VarkaVectorIR): Boolean = !offset.isInstanceOf[LiteralSlot]
    // The node rebuilt over re-armed children, and whether its own shift is runtime-valued.
    val (rebuilt, ownRuntime, childRuntime) = node match {
      case n: AddDays =>
        val d = rearm(n.days(), literals)
        (new AddDays(d.node, n.offset()), shiftIsRuntime(n.offset()), d.runtime)
      case n: SubDays =>
        val d = rearm(n.days(), literals)
        (new SubDays(d.node, n.offset()), shiftIsRuntime(n.offset()), d.runtime)
      case n: IRAddMonths =>
        val d = rearm(n.days(), literals)
        (new IRAddMonths(d.node, n.months()), shiftIsRuntime(n.months()), d.runtime)
      case n: IRLastDay =>
        val d = rearm(n.days(), literals)
        (new IRLastDay(d.node), false, d.runtime)
      case n: IRTruncDate =>
        val d = rearm(n.days(), literals)
        (new IRTruncDate(d.node, n.level()), false, d.runtime)
      case n: IRNextDay =>
        val d = rearm(n.days(), literals)
        (new IRNextDay(d.node, n.offset()), false, d.runtime)
      case n: ThursdayOf =>
        val d = rearm(n.days(), literals)
        (new ThursdayOf(d.node), false, d.runtime)
      // The hull nodes: both operands are day-typed, so both are re-armed and either's runtime
      // contribution counts for the pair.
      case n: IRGreatest =>
        val a = rearm(n.left(), literals)
        val b = rearm(n.right(), literals)
        (new IRGreatest(a.node, b.node), false, a.runtime || b.runtime)
      case n: IRLeast =>
        val a = rearm(n.left(), literals)
        val b = rearm(n.right(), literals)
        (new IRLeast(a.node, b.node), false, a.runtime || b.runtime)
      case n: IfElse =>
        val a = rearm(n.thenNode(), literals)
        val b = rearm(n.elseNode(), literals)
        (new IfElse(n.cond(), a.node, b.node), false, a.runtime || b.runtime)
      // A leaf of the analysis, or a node it does not bound: nothing to descend into, and its
      // own interval is whatever `dayRange` already says.
      case other => (other, false, false)
    }
    dayRange(rebuilt, literals, GuardPolicy.ARMED) match {
      case b: VarkaValueRange.Bounded if ownRuntime && !decomposesExactly(b) =>
        Rearmed(new GuardedDay(rebuilt), VarkaRangeAnalysis.NARROW, runtime = false)
      case other => Rearmed(rebuilt, other, ownRuntime || childRuntime)
    }
  }

  /**
   * The admission half of [[calendarInput]] over an already-built IR node, for a calendar node
   * whose child is not the compiled expression itself - the week tail runs over the Thursday shift
   * the compiler wraps around the date, so the shift is what the analysis bounds.
   */
  private def admitCalendar(
      node: VarkaVectorIR,
      calendar: Expression,
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    dayRange(node, literals, GuardPolicy.ARMED) match {
      case b: VarkaValueRange.Bounded if decomposesExactly(b) => Some(node)
      case b: VarkaValueRange.Bounded =>
        // The interval ran out, but if a runtime-valued shift is what carried it out then a check
        // can spend it again. `rearm` rewrites the subtree with the checks in it and answers the
        // interval that results; only a literal overflow, which no check can rescue, still
        // declines.
        val fixed = rearm(node, literals)
        fixed.range match {
          case f: VarkaValueRange.Bounded if decomposesExactly(f) => Some(fixed.node)
          case _ =>
            sink.note(s"day range [${b.lo}, ${b.hi}] leaves the calendar lowering's range",
              calendar)
            None
        }
      case _: VarkaValueRange.Unknown =>
        sink.note("day producer the calendar range analysis does not bound", calendar)
        None
      // The range type is sealed in Java, which Scala cannot see; a third member would land
      // here, and answering for it would be a wrong answer rather than a decline.
      case other => throw new IllegalStateException(s"unexpected range $other")
    }
  }

  /**
   * Resolves `next_day`'s weekday operand to the runtime literal
   * `k = dayOfWeek - 1` the emitted lowering needs. `dayOfWeek` comes from
   * `DateTimeUtils.getDayOfWeekFromString`, whose range is `[0, 6]`
   * (`THURSDAY = 0 .. WEDNESDAY = 6`), so `k` ranges over `{-1, 0, ..., 5}`. Unlike
   * `foldOffset`, the operand need not be a bare `Literal` - `next_day`'s weekday is any
   * foldable expression - so it is evaluated eagerly, and every way that can fail declines
   * rather than throws: a null result, an unrecognized weekday name
   * (`SparkIllegalArgumentException`), or any other exception `dow.eval()` itself raises
   * while evaluating a computed (not just literal) expression.
   */
  private def foldWeekday(dow: Expression, sink: DeclineSink): Option[Int] = {
    try {
      val name = dow.eval()
      if (name == null) {
        sink.note("next_day with a null weekday", dow)
        None
      } else {
        Some(DateTimeUtils.getDayOfWeekFromString(name.asInstanceOf[UTF8String]) - 1)
      }
    } catch {
      case _: SparkIllegalArgumentException =>
        sink.note("next_day with an unrecognized weekday", dow)
        None
      case NonFatal(e) =>
        sink.note(s"next_day weekday failed to evaluate: ${e.getMessage}", dow)
        None
    }
  }

  /** Where a `trunc(date, fmt)` compiles to: a `TruncDate` level, or the `WEEK` rewrite. */
  private sealed trait TruncTarget

  private case class ToLevel(level: TruncLevel) extends TruncTarget

  private case object ToWeek extends TruncTarget

  /**
   * Resolves `trunc`'s format operand through `DateTimeUtils.parseTruncLevel` - the
   * definition, never a re-implementation of its spellings and case folding - to one of the
   * three date levels or the `WEEK` rewrite, or `None` with the reason noted. Like
   * `foldWeekday`, the operand is any foldable expression, so it is evaluated eagerly and every
   * way that can fail declines rather than throws: a null format, an unrecognized string, a
   * level below a day (`'DAY'`, `'HOUR'`... - `truncDate` is undefined there and the row engine
   * returns NULL), or an exception from evaluating a computed format.
   */
  private def foldTruncLevel(format: Expression, sink: DeclineSink): Option[TruncTarget] = {
    try {
      val fmt = format.eval()
      if (fmt == null) {
        sink.note("trunc with a null format", format)
        None
      } else {
        DateTimeUtils.parseTruncLevel(fmt.asInstanceOf[UTF8String]) match {
          case DateTimeUtils.TRUNC_TO_YEAR => Some(ToLevel(TruncLevel.YEAR))
          case DateTimeUtils.TRUNC_TO_MONTH => Some(ToLevel(TruncLevel.MONTH))
          case DateTimeUtils.TRUNC_TO_QUARTER => Some(ToLevel(TruncLevel.QUARTER))
          case DateTimeUtils.TRUNC_TO_WEEK => Some(ToWeek)
          case DateTimeUtils.TRUNC_INVALID =>
            sink.note("trunc with an unrecognized format", format)
            None
          case _ =>
            sink.note("trunc to a level below a day, which is null for a date", format)
            None
        }
      }
    } catch {
      case NonFatal(e) =>
        sink.note(s"trunc format failed to evaluate: ${e.getMessage}", format)
        None
    }
  }
}
