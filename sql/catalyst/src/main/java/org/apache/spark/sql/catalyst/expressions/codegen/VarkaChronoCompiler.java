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

package org.apache.spark.sql.catalyst.expressions.codegen;

import java.util.ArrayList;
import java.util.Optional;

import scala.jdk.javaapi.OptionConverters;
import scala.util.control.NonFatal;

import org.apache.spark.SparkIllegalArgumentException;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.AddMonths;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.DateAdd;
import org.apache.spark.sql.catalyst.expressions.DateAddYMInterval;
import org.apache.spark.sql.catalyst.expressions.DateDiff;
import org.apache.spark.sql.catalyst.expressions.DateFromUnixDate;
import org.apache.spark.sql.catalyst.expressions.DateSub;
import org.apache.spark.sql.catalyst.expressions.DateVarkaSupport$;
import org.apache.spark.sql.catalyst.expressions.DayOfMonth;
import org.apache.spark.sql.catalyst.expressions.DayOfWeek;
import org.apache.spark.sql.catalyst.expressions.DayOfYear;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.ExtractANSIIntervalDays;
import org.apache.spark.sql.catalyst.expressions.LastDay;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.MakeDate;
import org.apache.spark.sql.catalyst.expressions.Month;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.NextDay;
import org.apache.spark.sql.catalyst.expressions.Quarter;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.SubtractDates;
import org.apache.spark.sql.catalyst.expressions.TruncDate;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.UnixDate;
import org.apache.spark.sql.catalyst.expressions.WeekDay;
import org.apache.spark.sql.catalyst.expressions.WeekOfYear;
import org.apache.spark.sql.catalyst.expressions.Year;
import org.apache.spark.sql.catalyst.expressions.YearOfWeek;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChrono;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDerivedKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaOperandPosition;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaRangeAnalysis;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaValueRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeekIso;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ThursdayOf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncLevel;
import org.apache.spark.sql.catalyst.util.DateTimeConstants;
import org.apache.spark.sql.catalyst.util.DateTimeUtils;
import org.apache.spark.sql.catalyst.util.DateTimeUtils$;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.StringType;
import org.apache.spark.sql.types.YearMonthIntervalType;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * The calendar family of the compiler: date arithmetic, the day-of-week nodes, {@code next_day},
 * the civil-field extractions and {@code make_date}, {@code last_day}, the ISO week, {@code trunc},
 * and month arithmetic - every Catalyst expression that lowers onto {@code VarkaChronoLowering}'s
 * nodes - with the range analysis that admits a day producer under a calendar node
 * ({@link #admitCalendar}, {@link #rearm}) and the compile-time folds of a weekday name and a
 * trunc level.
 *
 * <p>{@link #arm} is the family's one entry from the chain {@code VarkaExpressionCompiler}
 * dispatches through, in the form {@code VarkaIntervalCompiler} set: a {@code switch} that tests
 * and deconstructs a node and returns the lowering as a deferred call, or {@code null} for a node
 * the family does not claim. The helpers below it are this family's alone; the shared operand
 * helpers and the recursion are {@code VarkaNodeCompiler}'s.
 *
 * <p>The literal and input tables are the classifier's {@link VarkaSlotTable}s.
 */
final class VarkaChronoCompiler {


  private VarkaChronoCompiler() {
  }

  /**
   * The arm that claims {@code e}, or {@code null}: the calendar arms of {@code compileNode}, in
   * their original order.
   */
  static VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (e) {
      // unix_date/date_from_unix_date are Spark's own `input.asInstanceOf[Int]` in full - a date IS
      // a day count, so both are a pure type relabel with nothing to compute. Unwrapping to the
      // child rather than adding an IR node means `SELECT unix_date(d)` and `SELECT d` compile to
      // the same IR and share a shape hash - correct, since kernel identity is about lane math and
      // theirs is identical; the entry's output type still comes from the Catalyst expression, not
      // the IR. `date_from_unix_date`'s child is an integer column, and no value leaf reads a bare
      // int column, so this arm declines through the ordinary non-date-column path.
      case UnixDate n -> () -> VarkaNodeCompiler.compileNode(n.child(), inputs, literals, sink);
      case DateFromUnixDate n -> () -> VarkaNodeCompiler.compileNode(
          n.child(), inputs, literals, sink);
      case DateAdd n -> () -> dateAdd(n, inputs, literals, sink);
      case DateSub n -> () -> {
        var node = VarkaNodeCompiler.compileNode(n.startDate(), inputs, literals, sink);
        if (node.isEmpty()) {
          return node;
        }
        var offset = compileOffset(n.days(), inputs, literals, sink);
        return offset.map(v -> new SubDays(node.get(), v));
      };
      case DateDiff n -> () -> {
        var end = VarkaNodeCompiler.compileNode(n.endDate(), inputs, literals, sink);
        if (end.isEmpty()) {
          return end;
        }
        var start = VarkaNodeCompiler.compileNode(n.startDate(), inputs, literals, sink);
        return start.isEmpty()
            ? start : Optional.of(new VarkaVectorIR.DateDiff(end.get(), start.get()));
      };
      // date - date is a day-time interval in microseconds, Spark's
      // multiplyExact(subtractExact(end, start), MICROS_PER_DAY) with no ANSI switch. Both dates
      // are widened first, so the day count is a long subtraction that cannot wrap; the only
      // failure left is the multiply, and the guard on the count is that multiply's exact range, so
      // inside it the wrapping multiply is exact. A batch with a count outside it is declined and
      // the row engine raises Spark's own error on the row. The legacy CalendarInterval result is
      // a different type, which no kernel carries.
      case SubtractDates n when !n.legacyInterval() ->
          () -> subtractDates(n, inputs, literals, sink);
      case SubtractDates n -> () -> {
        sink.note("legacy CalendarInterval subtraction", n);
        return Optional.empty();
      };
      case DayOfWeek n ->
          () -> over(n.child(), VarkaVectorIR.DayOfWeek::new, inputs, literals, sink);
      case WeekDay n -> () -> over(n.child(), VarkaVectorIR.WeekDay::new, inputs, literals, sink);
      case Add a when dayOfWeekIsoChild(a).isPresent() -> () -> over(
          dayOfWeekIsoChild(a).get(), DayOfWeekIso::new, inputs, literals, sink);
      // next_day: a foldable weekday is resolved at compile time and travels as a
      // runtime literal. An unrecognized or null one declines rather than throws - it is the
      // row engine's business, and it has two different behaviours for it depending on ANSI
      // mode which Varka must not try to reproduce. Evaluating a foldable-but-computed weekday
      // expression (not only a bare Literal) can itself throw for reasons unrelated to the
      // weekday name - that must decline too, per the ghost-fallback contract, rather than
      // crash planning.
      case NextDay n when n.dayOfWeek().foldable() -> () -> {
        var k = foldWeekday(n.dayOfWeek(), sink);
        if (k.isEmpty()) {
          return Optional.empty();
        }
        var d = VarkaNodeCompiler.compileNode(n.startDate(), inputs, literals, sink);
        return d.map(v -> new VarkaVectorIR.NextDay(
            v, VarkaNodeCompiler.intSlot(k.getAsInt(), literals)));
      };
      // A weekday column: the kernel reads an int32 column the evaluator derives
      // from the names, per batch, by the row engine's own parser (WeekdayLeaf), so the node
      // is the same and only the offset's origin differs. ANSI mode is part of the derived
      // input's kind, since NextDay fixes failOnError at construction. Any collation is
      // admitted because the parser ignores it. An expression over the column stays the row
      // engine's: the leaf reads a stored column.
      case NextDay n when n.dayOfWeek() instanceof BoundReference br
          && br.dataType() instanceof StringType -> () -> {
        var kind = n.failOnError() ? VarkaDerivedKind.WEEKDAY_ANSI : VarkaDerivedKind.WEEKDAY;
        var d = VarkaNodeCompiler.compileNode(n.startDate(), inputs, literals, sink);
        return d.map(v -> new VarkaVectorIR.NextDay(
            v, VarkaNodeCompiler.derivedRef(br, kind, inputs)));
      };
      case NextDay n ->
          () -> sink.decline("next_day with a weekday that is neither a literal nor a column", n);
      // The calendar extractions. One civil-from-days decomposition per node, so two
      // fields of the same date are computed twice - see VarkaVectorIR.Year for why. The child
      // goes through `calendarInput`: the decomposition is exact only over
      // VarkaChrono's narrowed range, and the compiler is where a shift that can leave it is
      // known before anything runs.
      case Year n ->
          () -> calendarOver(n.child(), n, VarkaVectorIR.Year::new, inputs, literals, sink);
      case Month n ->
          () -> calendarOver(n.child(), n, VarkaVectorIR.Month::new, inputs, literals, sink);
      case DayOfMonth n ->
          () -> calendarOver(n.child(), n, VarkaVectorIR.DayOfMonth::new, inputs, literals, sink);
      case Quarter n ->
          () -> calendarOver(n.child(), n, VarkaVectorIR.Quarter::new, inputs, literals, sink);
      case DayOfYear n ->
          () -> calendarOver(n.child(), n, VarkaVectorIR.DayOfYear::new, inputs, literals, sink);
      // make_date(y, m, d): three int operands, each a column or a literal, and the
      // evaluation mode captured on the expression - two modes are two shapes.
      case MakeDate n -> () -> {
        var yy = VarkaNodeCompiler.compileIntOperand(
            n.year(), "make_date's year", inputs, literals, sink);
        if (yy.isEmpty()) {
          return yy;
        }
        var mm = VarkaNodeCompiler.compileIntOperand(
            n.month(), "make_date's month", inputs, literals, sink);
        if (mm.isEmpty()) {
          return mm;
        }
        var dd = VarkaNodeCompiler.compileIntOperand(
            n.day(), "make_date's day", inputs, literals, sink);
        return dd.map(v ->
            new VarkaVectorIR.MakeDate(yy.get(), mm.get(), v, n.failOnError()));
      };
      case LastDay n ->
          () -> calendarOver(n.startDate(), n, VarkaVectorIR.LastDay::new, inputs, literals, sink);
      // weekofyear, extract(WEEK) and date_part: the ISO week by the Thursday rule - the
      // week tail over the Thursday of the day's week, two nodes so the prefix runs over the
      // shifted day and so extract(YEAROFWEEK) is Year over the same ThursdayOf. The
      // calendar node's child is the shift, so the range analysis admits the shift, not the day.
      case WeekOfYear n -> () -> thursdayOver(
          n.child(), n, VarkaVectorIR.WeekOfYear::new, inputs, literals, sink);
      // extract(YEAROFWEEK) / date_part('YEAROFWEEK'): the ISO week-based year is the
      // calendar year of the same Thursday, so Year over the same shift - one prefix for both
      // fields under CSE, and nothing in the emitter.
      case YearOfWeek n -> () -> thursdayOver(
          n.child(), n, VarkaVectorIR.Year::new, inputs, literals, sink);
      // trunc(date, fmt): the format resolves at compile time, like next_day's weekday, because the
      // level chooses which code is emitted. YEAR, MONTH and QUARTER are one node with the level as
      // a shape-bearing field; WEEK is Spark's own definition, next_day(d - 7, 'MONDAY'),
      // rewritten onto the nodes the compiler already has - the unix_date pattern of retiring an
      // expression onto existing IR. A stored string column is the dynamic node below. Everything
      // else declines, each for its own reason: the row engine answers those with a NULL column,
      // which no IR node can produce.
      case TruncDate n when n.format().foldable() -> () -> truncFolded(n, inputs, literals, sink);
      // A format column: the level is read per batch by the evaluator's derived leaf
      // (TruncLevelLeaf) into an int32 column of parseTruncLevel's codes, on next_day's pattern,
      // and the kernel computes every period and selects on it. No ANSI twin in the
      // kind: TruncDate has no error path, so a null, unrecognised or sub-day format is a NULL
      // row in either mode - the leaf's null lane, through the node's word. Any collation is
      // admitted because the parser ignores it; an expression over the column stays the row
      // engine's, since the leaf reads a stored column.
      case TruncDate n when n.format() instanceof BoundReference br
          && br.dataType() instanceof StringType -> () -> {
        var date = calendarInput(n.date(), n, inputs, literals, sink);
        return date.map(v -> new VarkaVectorIR.TruncDateDynamic(
            v, VarkaNodeCompiler.derivedRef(br, VarkaDerivedKind.TRUNC_LEVEL, inputs)));
      };
      case TruncDate n -> () -> sink.decline("trunc with a non-foldable format", n);
      // Month arithmetic: add_months(d, n) and d +- INTERVAL n MONTH/YEAR are the same
      // node - AddMonthsBase's two subclasses differ only in where the month count comes from,
      // both physically an Int. `d - INTERVAL n MONTH` arrives as DatetimeSub, already replaced
      // by its DateAddYMInterval(l, UnaryMinus(r)) by the time a real query reaches here.
      // The date child compiles before the month count, matching DateAdd's rule above: ordinals
      // register in reading order, so add_months(d, m) puts d at ordinal 0 and m at ordinal 1 -
      // and when both decline, DeclineSink's "first note wins" rule reports the date's reason.
      case AddMonths n ->
          () -> addMonths(n, n.startDate(), n.numMonths(), inputs, literals, sink);
      case DateAddYMInterval n ->
          () -> addMonths(n, n.date(), n.interval(), inputs, literals, sink);
      default -> null;
    };
  }

  /** {@code build} over the compiled child, or the decline if the child declined. */
  private static Optional<VarkaVectorIR> over(
      Expression child,
      java.util.function.Function<VarkaVectorIR, VarkaVectorIR> build,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> node = VarkaNodeCompiler.compileNode(child, inputs, literals, sink);
    return node.map(build);
  }

  /** {@code build} over the child admitted under a calendar node. */
  private static Optional<VarkaVectorIR> calendarOver(
      Expression child,
      Expression calendar,
      java.util.function.Function<VarkaVectorIR, VarkaVectorIR> build,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> node = calendarInput(child, calendar, inputs, literals, sink);
    return node.map(build);
  }

  /** {@code build} over the Thursday of the compiled child's week, admitted as one shift. */
  private static Optional<VarkaVectorIR> thursdayOver(
      Expression child,
      Expression calendar,
      java.util.function.Function<VarkaVectorIR, VarkaVectorIR> build,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> node = VarkaNodeCompiler.compileNode(child, inputs, literals, sink);
    if (node.isEmpty()) {
      return node;
    }
    Optional<VarkaVectorIR> admitted =
        admitCalendar(new ThursdayOf(node.get()), calendar, literals, sink);
    return admitted.map(build);
  }

  private static Optional<VarkaVectorIR> addMonths(
      Expression whole,
      Expression date,
      Expression months,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> node = calendarInput(date, whole, inputs, literals, sink);
    if (node.isEmpty()) {
      return node;
    }
    Optional<VarkaVectorIR> count = compileMonths(months, inputs, literals, sink);
    return count.map(v -> new VarkaVectorIR.AddMonths(node.get(), v));
  }

  /**
   * {@code end - start} over dates as microseconds: {@code (end - start) * MICROS_PER_DAY} on the
   * long lane. The two dates are widened before they are subtracted, so the difference, which can
   * need 33 bits, is exact; it is then guarded to {@code [-MAX_DAYS, MAX_DAYS]}, where {@code
   * MAX_DAYS = Long.MAX_VALUE / MICROS_PER_DAY} is the largest count whose product is a long, and
   * {@code Math.abs} is not used because the guard's two bounds are the edges themselves.
   */
  private static Optional<VarkaVectorIR> subtractDates(
      SubtractDates n,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    var end = VarkaNodeCompiler.compileNode(n.left(), inputs, literals, sink);
    if (end.isEmpty()) {
      return end;
    }
    var start = VarkaNodeCompiler.compileNode(n.right(), inputs, literals, sink);
    if (start.isEmpty()) {
      return start;
    }
    var days = new VarkaVectorIR.IntArith(IntOp.SUB, Overflow.WRAP,
        new VarkaVectorIR.WidenLane(end.get()), new VarkaVectorIR.WidenLane(start.get()));
    long maxDays = Long.MAX_VALUE / DateTimeConstants.MICROS_PER_DAY;
    var guarded = new VarkaVectorIR.GuardedRange(days, -maxDays, maxDays);
    return Optional.of(new VarkaVectorIR.IntArith(IntOp.MUL, Overflow.WRAP, guarded,
        sink.longSlot(DateTimeConstants.MICROS_PER_DAY)));
  }

  private static Optional<VarkaVectorIR> dateAdd(
      DateAdd n,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    // The date child compiles before the offset, matching CaseWhen's rule a few cases below:
    // ordinals and literal slots register in reading order. A foldable literal offset registers
    // no ordinal, so the ordering is observable only for a column offset: when BOTH operands are
    // unfusable, DeclineSink's "first note wins" rule reports the
    // child's reason, not the offset's (pinned by VarkaExpressionCompilerSuite's "with two
    // independently unfusable operands, the child's reason is reported" test).
    Expression days = n.days();
    Optional<BoundReference> negated = days instanceof UnaryMinus u
        ? dayIntervalOffset(u.child()) : Optional.empty();
    Optional<VarkaVectorIR> node = VarkaNodeCompiler.compileNode(
        n.startDate(), inputs, literals, sink);
    if (node.isEmpty()) {
      return node;
    }
    if (negated.isPresent()) {
      // `date - INTERVAL n DAY`: the analyzer spells it as an add of the negated
      // day count, `DateAdd(d, UnaryMinus(ExtractANSIIntervalDays(r)))`. Inside the cast's
      // own bound the negation cannot overflow, so it is absorbed into SubDays - no
      // UnaryMinus node exists and none is needed. The offset is the same bounded column
      // `compileOffset` makes of the cast form.
      return Optional.of(new SubDays(node.get(), dayIntervalColumn(negated.get(), inputs, sink)));
    }
    Optional<VarkaVectorIR> offset = compileOffset(days, inputs, literals, sink);
    return offset.map(v -> new AddDays(node.get(), v));
  }

  /**
   * The {@code extract(DAYOFWEEK_ISO)} shape, which keeps its own node rather than becoming int
   * arithmetic over a {@code weekday} output. Either operand order, exactly as that arm reads.
   */
  static boolean isDayOfWeekIso(Add a) {
    return dayOfWeekIsoChild(a).isPresent();
  }

  /** The {@code weekday} argument of the {@code DAYOFWEEK_ISO} shape, in either operand order. */
  private static Optional<Expression> dayOfWeekIsoChild(Add a) {
    if (a.left() instanceof WeekDay w && isLiteralOne(a.right())) {
      return Optional.of(w.child());
    }
    if (isLiteralOne(a.left()) && a.right() instanceof WeekDay w) {
      return Optional.of(w.child());
    }
    return Optional.empty();
  }

  private static boolean isLiteralOne(Expression e) {
    return e instanceof Literal l && l.value() instanceof Integer v && v == 1
        && l.dataType().equals(DataTypes.IntegerType);
  }

  /**
   * The epoch days a day-valued node can produce: {@link VarkaRangeAnalysis}'s {@code DAY} query,
   * under {@code ARMED} for a calendar consumer - which arms the runtime guard on every
   * column-offset producer below it - and {@code NONE} for anything else.
   */
  static VarkaValueRange.Range dayRange(
      VarkaVectorIR node,
      VarkaSlotTable literals,
      VarkaRangeAnalysis.GuardPolicy policy) {
    return VarkaRangeAnalysis.range(
        node, VarkaRangeAnalysis.Kind.DAY, policy, VarkaNodeCompiler.literalAt(literals));
  }

  /**
   * Whether every day in the interval decomposes exactly. Asymmetric on purpose. Downward,
   * {@code NARROW_MIN_DAYS} binds: below it the narrowing is undefined and no correction rescues
   * it. Upward, the binding limit is not {@code NARROW_MAX_DAYS} - that is the era step's
   * <i>shift</i> domain and the range the runtime guards enforce on a producer's own result - but
   * how far the decomposition stays exact on a value already in hand, which {@code eraOf}'s
   * one-era correction carries about 9,266 years further. So an upward shift over a guarded day
   * producer is admitted where it is genuinely exact, rather than declined at the narrower bound.
   */
  private static boolean decomposesExactly(VarkaValueRange.Bounded b) {
    return b.within(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS);
  }

  /**
   * The day offset of a {@code date_add}/{@code date_sub}: a folded literal keeps today's
   * {@code LiteralSlot} shape (existing plans and their cached kernels are untouched), a
   * non-foldable offset is a bare {@code IntegerType} column, and it may also be int arithmetic
   * over those - {@code date_add(d, i * 7)}. It is still deliberately not a general
   * {@code compileNode} recursion. {@code compileNode}'s {@code BoundReference} leaf stays
   * {@code DateType}-only: widening it instead of this dedicated path would let an int column
   * reach every other position that calls {@code compileNode} too ({@code Compare},
   * {@code DateDiff}, {@code Coalesce}, {@code Greatest}...), fusing plain integer-vs-integer
   * predicates that are outside this path's scope ("do not open it wider",
   * {@code VARKA-38.md} 6).
   */
  static Optional<VarkaVectorIR> compileOffset(
      Expression days,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<Object> folded =
        OptionConverters.toJava(DateVarkaSupport$.MODULE$.foldDaysOffset(days));
    if (folded.isPresent()) {
      return Optional.of(VarkaNodeCompiler.intSlot((Integer) folded.get(), literals));
    }
    if (days instanceof BoundReference br) {
      if (br.dataType().equals(DataTypes.IntegerType)) {
        return Optional.of(VarkaNodeCompiler.columnRef(br, inputs, LaneType.INT));
      }
      return sink.decline("non-integer day offset column of type " + br.dataType().simpleString(),
          br);
    }
    // A day interval built from an int column: `CAST(i AS INTERVAL DAY)`. The
    // cast multiplies by a day's micros and the extractor divides them back out, so the
    // day count is `i` itself - wherever the cast does not throw. Past
    // INTERVAL_DAY_LIMIT_DAYS it throws in every mode, where a kernel would wrap, so the
    // column carries a bound the evaluator checks per batch and declines the batch to
    // the row engine when a live lane is outside.
    Optional<BoundReference> interval = dayIntervalOffset(days);
    if (interval.isPresent()) {
      return Optional.of(dayIntervalColumn(interval.get(), inputs, sink));
    }
    if (days instanceof ExtractANSIIntervalDays e) {
      // A stored INTERVAL DAY column is int64 microseconds, which no int32 lane can read;
      // this is scoped to the int-cast form.
      return sink.decline("day interval is not an int column cast to days", e);
    }
    // Arithmetic over an int column as the offset, `date_add(d, i * 7)`. What makes this safe
    // above rather than only here is `dayRange`, which reads any non-literal offset as a
    // column shift: a calendar node over such a producer still gets the runtime range guard,
    // exactly as it does for a bare column offset. Only the four arithmetic shapes, not every
    // `IntegerType` expression, enter this arm, and what they compile to must be a kind
    // `VarkaOperandPosition.DAY_OFFSET` admits - the rule the emitter checks too.
    if ((days instanceof Add || days instanceof Subtract || days instanceof Multiply
        || days instanceof UnaryMinus) && days.dataType().equals(DataTypes.IntegerType)) {
      // The compiled root has to be a shape the offset position takes, not merely something
      // built from an arithmetic expression: `weekday(d) + 1` is an `Add` that lowers to
      // `DayOfWeekIso`, which the offset position does not accept. Admitting it here would
      // put an entry through `compilePartial` as fused and let the emitter's refusal fire at
      // emit time, where the evaluator turns it into a silent per-batch fallback while
      // EXPLAIN still claims fusion - the ghost fallback `sql/varka/AGENTS.md` forbids. The
      // test is the emitter's own predicate rather than a copy of its list, so the two cannot
      // drift apart again.
      return VarkaNodeCompiler.compileNode(days, inputs, literals, sink).flatMap(v ->
          VarkaNodeCompiler.admitted(VarkaOperandPosition.DAY_OFFSET, v, days,
              "day offset arithmetic that lowers to a node the offset position does not take",
              sink));
    }
    return sink.decline("day offset is not a foldable literal, an integer column or int arithmetic",
        days);
  }

  /** The bounded int column a {@code CAST(i AS INTERVAL DAY)} offset reads. */
  private static VarkaVectorIR.ColumnRef dayIntervalColumn(
      BoundReference br, VarkaSlotTable inputs, DeclineSink sink) {
    sink.bound(br.ordinal(), -VarkaChrono.INTERVAL_DAY_LIMIT_DAYS,
        VarkaChrono.INTERVAL_DAY_LIMIT_DAYS);
    return VarkaNodeCompiler.columnRef(br, inputs, LaneType.INT);
  }

  /**
   * "An int column, as a day interval", the one spelling of it that stays a date-lane
   * expression: {@code ExtractANSIIntervalDays} over {@code CAST(i AS INTERVAL DAY)}, which is
   * exactly {@code i} inside the cast's bound. {@code i * INTERVAL '1' DAY} is not a second
   * spelling: a multiplied interval widens to DAY TO SECOND, so the analyzer casts the date to a
   * timestamp and the expression leaves the date lane ({@code TimestampAddInterval}, milestone
   * 5).
   */
  private static Optional<BoundReference> dayIntervalOffset(Expression e) {
    if (e instanceof ExtractANSIIntervalDays x && x.child() instanceof Cast c
        && c.child() instanceof BoundReference br
        && c.dataType() instanceof DayTimeIntervalType t
        && t.startField() == DayTimeIntervalType.DAY()
        && t.endField() == DayTimeIntervalType.DAY()
        && br.dataType().equals(DataTypes.IntegerType)) {
      return Optional.of(br);
    }
    return Optional.empty();
  }

  /**
   * "An int column, as a year-month interval", {@code dayIntervalOffset}'s twin for months:
   * {@code CAST(i AS INTERVAL MONTH)} reaches the compiler as the cast itself, with no extraction
   * wrapper - unlike {@code dayIntervalOffset}, whose micros-typed cast needs
   * {@code ExtractANSIIntervalDays} to read back out - because
   * {@code Cast.intToYearMonthInterval} returns {@code v} unchanged for an end field of
   * {@code MONTH} ({@code VARKA-60.md} section 2), so the cast node's own evaluated value already
   * is the month count. {@code i * INTERVAL '1' MONTH} is not a second spelling, for the same
   * reason {@code dayIntervalOffset}'s doc gives for days: a multiplied interval leaves the date
   * lane.
   */
  private static Optional<BoundReference> monthIntervalOffset(Expression e) {
    if (e instanceof Cast c && c.child() instanceof BoundReference br
        && c.dataType() instanceof YearMonthIntervalType t
        && t.startField() == YearMonthIntervalType.MONTH()
        && t.endField() == YearMonthIntervalType.MONTH()
        && br.dataType().equals(DataTypes.IntegerType)) {
      return Optional.of(br);
    }
    return Optional.empty();
  }

  /**
   * The month count of {@code add_months}/{@code date +- INTERVAL n MONTH/YEAR}: a foldable count
   * folds to a bounded {@code LiteralSlot}, the same two reasons as before - not foldable, or
   * foldable but outside {@code VarkaChrono}'s {@code MONTH_ARITH_MIN/MAX_MONTHS}, the range the
   * emitter's {@code / 12} magic multiply covers ({@code VARKA-40.md} section 2.2). A
   * non-foldable count is a {@code ColumnRef} when it is a bare {@code IntegerType} column
   * ({@code add_months(d, m)}) or the {@code MONTH}-end interval cast above
   * ({@code d + CAST(m AS INTERVAL MONTH)}) - the emitter bounds it lanewise at run time instead
   * (the runtime guard on {@code AddMonths} itself, since the exactness domain is the count's
   * alone, {@code VARKA-60.md} section 2). A {@code YearMonthIntervalType} column with no such
   * cast declines by name: the Arrow cache holds it as an {@code IntervalYearVector}, which
   * {@code isArrowBacked} does not read, so admitting it would fuse at plan time and then refuse
   * every batch. A negated interval ({@code d - ym_col}, {@code d - INTERVAL m MONTH}) arrives as
   * {@code UnaryMinus} over it and builds an {@code IntNeg} over its count. Whatever an arm
   * builds must be a kind {@code VarkaOperandPosition.MONTH_COUNT} admits, which the emitter
   * checks too; the arms are {@code buildMonths}.
   */
  static Optional<VarkaVectorIR> compileMonths(
      Expression months,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    // Every arm below builds a kind the position takes; the gate is what keeps a future arm
    // from fusing a count the emitter would refuse (VARKA-86), as compileOffset's does.
    return buildMonths(months, inputs, literals, sink).flatMap(v ->
        VarkaNodeCompiler.admitted(VarkaOperandPosition.MONTH_COUNT, v, months,
            "month count that lowers to a node the month-count position does not take", sink));
  }

  private static Optional<VarkaVectorIR> buildMonths(
      Expression months,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<Object> folded =
        OptionConverters.toJava(DateVarkaSupport$.MODULE$.foldDaysOffset(months));
    if (folded.isPresent()) {
      int m = (Integer) folded.get();
      if (m < VarkaChrono.MONTH_ARITH_MIN_MONTHS || m > VarkaChrono.MONTH_ARITH_MAX_MONTHS) {
        return sink.decline("month count outside the range the emitter's magic multiply covers",
            months);
      }
      return Optional.of(VarkaNodeCompiler.intSlot(m, literals));
    }
    if (months instanceof BoundReference br && br.dataType().equals(DataTypes.IntegerType)) {
      return Optional.of(VarkaNodeCompiler.columnRef(br, inputs, LaneType.INT));
    }
    Optional<BoundReference> interval = monthIntervalOffset(months);
    if (interval.isPresent()) {
      return Optional.of(VarkaNodeCompiler.columnRef(interval.get(), inputs, LaneType.INT));
    }
    // `Cast.intToYearMonthInterval` returns `12 * v` for a YEAR end field, so the value
    // under this cast is a count of years and the node needs months - unlike the MONTH-end
    // cast monthIntervalOffset matches, which returns `v` unchanged.
    //
    // The month-count position takes int arithmetic (`VarkaOperandPosition.MONTH_COUNT`),
    // unlike `next_day`'s weekday, which shares no guard with it. So this is that
    // multiply, always checked because `IntervalUtils.intToYearMonthInterval` uses
    // `Math.multiplyExact` whatever the session's ANSI mode, and `arithOver`'s bound is what
    // removes the check - `CAST(year(d) AS INTERVAL YEAR)` fuses on its 40000 bound while a
    // bare column declines, as every unbounded checked multiply does. A foldable year count
    // never reaches this arm at all: `foldDaysOffset` above folds `CAST(5 AS INTERVAL YEAR)`
    // to 60 before the match.
    if (months instanceof Cast c && c.dataType() instanceof YearMonthIntervalType t
        && t.startField() == YearMonthIntervalType.YEAR()
        && t.endField() == YearMonthIntervalType.YEAR()
        && c.child().dataType().equals(DataTypes.IntegerType)) {
      // The years-to-months cast is the interval family's node, built here rather than through
      // the chain, so it runs under that family's switch, as the negation below (VARKA-318).
      return VarkaNodeCompiler.inFamily(VarkaFamily.INTERVAL,
          () -> VarkaIntervalCompiler.yearsToMonths(c, inputs, literals, sink), c, sink);
    }
    // `d - ym_col`, which the analyzer spells `DateAddYMInterval(d, UnaryMinus(ym))`, so the
    // count is a negation of an interval column. Its check comes off wherever a negation's
    // does, which is any bound at all - and a bare interval column has none, so this keeps
    // its check and is guarded on the count's value at run time exactly as a plain column
    // count is.
    if (months instanceof UnaryMinus u && u.child().dataType() instanceof YearMonthIntervalType) {
      // The negation is the interval family's node, built here rather than through the chain.
      return VarkaNodeCompiler.inFamily(VarkaFamily.INTERVAL,
          () -> VarkaIntervalCompiler.intervalOperand(
              u.child(), "the negated month count", inputs, literals, sink)
              .map(v -> new IntNeg(VarkaIntervalCompiler.negationMode(v, literals, sink), v)),
          u, sink);
    }
    // `d + ym_col`. The stored value is the month count in every unit, so this is the
    // column-count `AddMonths` exactly, with the same runtime guard on the count's lanes -
    // the guard reads the value and not the column's Spark type; the evaluator reads the
    // vector because this arm asks for it.
    if (months instanceof BoundReference br
        && br.dataType() instanceof YearMonthIntervalType) {
      return Optional.of(VarkaNodeCompiler.columnRef(br, inputs, LaneType.INT));
    }
    // AddMonths.inputTypes is Seq(DateType, IntegerType) exactly - unlike DateAdd, which
    // accepts a TypeCollection - so the analyzer widens a Short/Byte count with a cast and
    // a bare non-integer column never reaches here. The cast is what arrives, and it gets
    // a reason naming the column's own type: the int32 lanes read an IntegerType column,
    // and a SmallIntVector is not one, so this declines rather than fusing at plan time
    // and refusing every batch. Fusing it needs a task-59-style derived leaf to widen the
    // column ahead of the kernel, which is its own task.
    if (months instanceof Cast c && c.child() instanceof BoundReference br
        && c.dataType().equals(DataTypes.IntegerType)
        && !br.dataType().equals(DataTypes.IntegerType)) {
      return sink.decline("month count column of type " + br.dataType().simpleString()
          + " reaches the compiler behind a widening cast; the int32 lanes read only an "
          + "integer column", br);
    }
    return sink.decline("month count is neither a foldable literal nor an integer column",
        months);
  }

  /**
   * Compiles a calendar node's child and admits it only if {@code dayRange} says the
   * decomposition will see a day inside the narrowed range. A bounded interval that leaves it
   * declines the entry - free at run time, and the row engine computes it correctly. A
   * column-driven producer contributes the interval its own runtime guard establishes (their
   * runtime halves) rather than a special verdict, so a shift above such a producer is tested here
   * like any other. An unknown producer declines, so a node this analysis has not been taught
   * fails safe as a residual entry rather than as a wrong answer.
   */
  private static Optional<VarkaVectorIR> calendarInput(
      Expression child,
      Expression calendar,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> node = VarkaNodeCompiler.compileNode(child, inputs, literals, sink);
    return node.flatMap(v -> admitCalendar(v, calendar, literals, sink));
  }

  /**
   * The result of re-arming a subtree: the rewritten node, the interval it now produces, and
   * whether a runtime-valued shift has contributed to that interval since the last
   * {@link GuardedDay} - which is what decides whether a further overflow can be guarded or has to
   * decline.
   */
  private record Rearmed(VarkaVectorIR node, VarkaValueRange.Range range, boolean runtime) {
  }

  /** A node rebuilt over re-armed children, and the two runtime facts about it. */
  private record Rebuilt(VarkaVectorIR node, boolean ownRuntime, boolean childRuntime) {
  }

  /**
   * Insert {@link GuardedDay} wherever the running interval would leave the range the calendar
   * lowering decomposes exactly, resetting the interval there.
   *
   * <p>The producer guard covers one producer, and promises the whole narrowed range - so a second
   * guarded shift above it has no budget left and {@link #admitCalendar} must decline the
   * expression, although both shifts are individually fine. Re-arming spends the range again: at a
   * node whose interval overflows, the emitted check makes everything above it start from
   * {@code [NARROW_MIN_DAYS, NARROW_MAX_DAYS]} once more.
   *
   * <p>It is a rewrite rather than a set of positions because the emitter cannot be told where
   * to check: it never sees literal values, so it cannot run this arithmetic, and
   * {@code VarkaShapeKey} keys a cached kernel on the IR without them - so a placement carried
   * beside the IR would let one shape be served another's guards. In the IR, the shape key
   * separates them ({@code VARKA-93.md} 3.3.1).
   *
   * <p><b>What must still decline, and the rule is narrower than it first looks.</b> A node is
   * re-armed only when <i>its own</i> shift is runtime-valued - a column offset, a column month
   * count - and not merely when something below it was. A literal shift that leaves the range
   * leaves it for every row, so a check above it would emit a kernel that reports every batch:
   * a slower way to decline than declining once, here, for free. A rule testing "did any runtime
   * value contribute" would admit {@code year(date_add(date_add(d, i), 20000000))} on the
   * strength of the inner column offset, which is exactly that mistake.
   *
   * <p>Descends only the day-typed children the analysis bounds, which is exactly
   * {@link #dayRange}'s own set; anything else is returned untouched and its interval speaks for
   * itself.
   */
  private static Rearmed rearm(VarkaVectorIR node, VarkaSlotTable literals) {
    Rebuilt rebuilt = switch (node) {
      case AddDays n -> {
        Rearmed d = rearm(n.days(), literals);
        yield new Rebuilt(new AddDays(d.node(), n.offset()), shiftIsRuntime(n.offset()),
            d.runtime());
      }
      case SubDays n -> {
        Rearmed d = rearm(n.days(), literals);
        yield new Rebuilt(new SubDays(d.node(), n.offset()), shiftIsRuntime(n.offset()),
            d.runtime());
      }
      case VarkaVectorIR.AddMonths n -> {
        Rearmed d = rearm(n.days(), literals);
        yield new Rebuilt(new VarkaVectorIR.AddMonths(d.node(), n.months()),
            shiftIsRuntime(n.months()), d.runtime());
      }
      case VarkaVectorIR.LastDay n -> {
        Rearmed d = rearm(n.days(), literals);
        yield new Rebuilt(new VarkaVectorIR.LastDay(d.node()), false, d.runtime());
      }
      case VarkaVectorIR.TruncDate n -> {
        Rearmed d = rearm(n.days(), literals);
        yield new Rebuilt(new VarkaVectorIR.TruncDate(d.node(), n.level()), false, d.runtime());
      }
      case VarkaVectorIR.NextDay n -> {
        Rearmed d = rearm(n.days(), literals);
        yield new Rebuilt(new VarkaVectorIR.NextDay(d.node(), n.offset()), false, d.runtime());
      }
      case ThursdayOf n -> {
        Rearmed d = rearm(n.days(), literals);
        yield new Rebuilt(new ThursdayOf(d.node()), false, d.runtime());
      }
      // The hull nodes: both operands are day-typed, so both are re-armed and either's runtime
      // contribution counts for the pair.
      case VarkaVectorIR.Greatest n -> {
        Rearmed a = rearm(n.left(), literals);
        Rearmed b = rearm(n.right(), literals);
        yield new Rebuilt(new VarkaVectorIR.Greatest(a.node(), b.node()), false,
            a.runtime() || b.runtime());
      }
      case VarkaVectorIR.Least n -> {
        Rearmed a = rearm(n.left(), literals);
        Rearmed b = rearm(n.right(), literals);
        yield new Rebuilt(new VarkaVectorIR.Least(a.node(), b.node()), false,
            a.runtime() || b.runtime());
      }
      case IfElse n -> {
        Rearmed a = rearm(n.thenNode(), literals);
        Rearmed b = rearm(n.elseNode(), literals);
        yield new Rebuilt(new IfElse(n.cond(), a.node(), b.node()), false,
            a.runtime() || b.runtime());
      }
      // A leaf of the analysis, or a node it does not bound: nothing to descend into, and its
      // own interval is whatever `dayRange` already says. Listed, not defaulted, so that a new
      // IR node is a compile error here until it is classified as one that carries a day
      // interval (above) or one that does not.
      case VarkaVectorIR.ColumnRef _, LiteralSlot _, GuardedDay _, VarkaVectorIR.GuardedRange _,
          VarkaVectorIR.WidenLane _, VarkaVectorIR.NarrowLane _, VarkaVectorIR.DateDiff _,
          VarkaVectorIR.DayOfWeek _, VarkaVectorIR.WeekDay _, DayOfWeekIso _, VarkaVectorIR.Year _,
          VarkaVectorIR.Month _, VarkaVectorIR.DayOfMonth _, VarkaVectorIR.Quarter _,
          VarkaVectorIR.DayOfYear _, VarkaVectorIR.TruncDateDynamic _, VarkaVectorIR.WeekOfYear _,
          VarkaVectorIR.MakeDate _, VarkaVectorIR.Compare _, VarkaVectorIR.And _,
          VarkaVectorIR.Or _, VarkaVectorIR.Not _, VarkaVectorIR.IsNotNull _,
          VarkaVectorIR.InRanges _, VarkaVectorIR.IntArith _, IntNeg _, VarkaVectorIR.ConstDivide _,
          VarkaVectorIR.BoundedDivide _, VarkaVectorIR.BitOp _, VarkaVectorIR.BitCount _ ->
          new Rebuilt(node, false, false);
    };
    VarkaValueRange.Range range =
        dayRange(rebuilt.node(), literals, VarkaRangeAnalysis.GuardPolicy.ARMED);
    if (range instanceof VarkaValueRange.Bounded b && rebuilt.ownRuntime()
        && !decomposesExactly(b)) {
      return new Rearmed(new GuardedDay(rebuilt.node()), VarkaRangeAnalysis.NARROW, false);
    }
    return new Rearmed(rebuilt.node(), range, rebuilt.ownRuntime() || rebuilt.childRuntime());
  }

  private static boolean shiftIsRuntime(VarkaVectorIR offset) {
    return !(offset instanceof LiteralSlot);
  }

  /**
   * The admission half of {@link #calendarInput} over an already-built IR node, for a calendar
   * node whose child is not the compiled expression itself - the week tail runs over the Thursday
   * shift the compiler wraps around the date, so the shift is what the analysis bounds.
   */
  private static Optional<VarkaVectorIR> admitCalendar(
      VarkaVectorIR node,
      Expression calendar,
      VarkaSlotTable literals,
      DeclineSink sink) {
    // The columns the interval rests on, noted only if the node is admitted on it.
    var columns = new ArrayList<Integer>();
    VarkaValueRange.Range range = VarkaRangeAnalysis.range(node, VarkaRangeAnalysis.Kind.DAY,
        VarkaRangeAnalysis.GuardPolicy.ARMED, VarkaNodeCompiler.literalAt(literals), columns::add);
    return switch (range) {
      case VarkaValueRange.Bounded b when decomposesExactly(b) -> {
        columns.forEach(sink::dayColumnBound);
        yield Optional.of(node);
      }
      case VarkaValueRange.Bounded b -> {
        // The interval ran out, but if a runtime-valued shift is what carried it out then a check
        // can spend it again. `rearm` rewrites the subtree with the checks in it and answers the
        // interval that results; only a literal overflow, which no check can rescue, still
        // declines.
        Rearmed fixed = rearm(node, literals);
        if (fixed.range() instanceof VarkaValueRange.Bounded f && decomposesExactly(f)) {
          yield Optional.of(boundColumns(fixed.node(), literals, sink));
        }
        yield sink.decline("day range [" + b.lo() + ", " + b.hi()
            + "] leaves the calendar lowering's range", calendar);
      }
      case VarkaValueRange.Unknown u ->
          sink.decline("day producer the calendar range analysis does not bound", calendar);
    };
  }

  /**
   * {@code admitted}, a node {@link #rearm} rewrote and the admission then rested on: every date
   * column its interval read as {@link VarkaRangeAnalysis#CONTRACT} gets that range noted as an
   * input bound, which the entry's compile turns into a guard in the kernel, declining a batch
   * with a day outside it, stored under a null row or not, to the row engine. Without it the
   * column bound is an assumption, and a cached column holding a day past the range the lowering
   * is exact over was served wrong (VARKA-379). Asked once more, on the rewritten node, rather
   * than collected while the rewrite explores: a column below a {@link GuardedDay} it placed is
   * checked by that guard instead.
   */
  private static VarkaVectorIR boundColumns(
      VarkaVectorIR admitted, VarkaSlotTable literals, DeclineSink sink) {
    VarkaRangeAnalysis.range(admitted, VarkaRangeAnalysis.Kind.DAY,
        VarkaRangeAnalysis.GuardPolicy.ARMED, VarkaNodeCompiler.literalAt(literals),
        sink::dayColumnBound);
    return admitted;
  }

  /**
   * Resolves {@code next_day}'s weekday operand to the runtime literal {@code k = dayOfWeek - 1}
   * the emitted lowering needs. {@code dayOfWeek} comes from
   * {@code DateTimeUtils.getDayOfWeekFromString}, whose range is {@code [0, 6]}
   * ({@code THURSDAY = 0 .. WEDNESDAY = 6}), so {@code k} ranges over {@code {-1, 0, ..., 5}}.
   * Unlike {@code foldOffset}, the operand need not be a bare {@code Literal} - {@code next_day}'s
   * weekday is any foldable expression - so it is evaluated eagerly, and every way that can fail
   * declines rather than throws: a null result, an unrecognized weekday name
   * ({@code SparkIllegalArgumentException}), or any other exception {@code dow.eval()} itself
   * raises while evaluating a computed (not just literal) expression.
   */
  private static java.util.OptionalInt foldWeekday(Expression dow, DeclineSink sink) {
    try {
      Object name = dow.eval(null);
      if (name == null) {
        sink.note("next_day with a null weekday", dow);
        return java.util.OptionalInt.empty();
      }
      return java.util.OptionalInt.of(
          DateTimeUtils.getDayOfWeekFromString((UTF8String) name) - 1);
    } catch (SparkIllegalArgumentException e) {
      sink.note("next_day with an unrecognized weekday", dow);
      return java.util.OptionalInt.empty();
    } catch (Throwable t) {
      if (!NonFatal.apply(t)) {
        throw t;
      }
      sink.note("next_day weekday failed to evaluate: " + t.getMessage(), dow);
      return java.util.OptionalInt.empty();
    }
  }

  /** Where {@code trunc(date, fmt)} compiles to: a {@code TruncDate} level or the WEEK rewrite. */
  private sealed interface TruncTarget {
  }

  private record ToLevel(TruncLevel level) implements TruncTarget {
  }

  private record ToWeek() implements TruncTarget {
  }

  /**
   * The lowering of {@code trunc} with a foldable format: the level is resolved first, then the
   * date; WEEK is Spark's own definition, {@code next_day(d - 7, 'MONDAY')}.
   */
  private static Optional<VarkaVectorIR> truncFolded(
      TruncDate n,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<TruncTarget> target = foldTruncLevel(n.format(), sink);
    if (target.isEmpty()) {
      return Optional.empty();
    }
    return switch (target.get()) {
      case ToLevel l -> {
        Optional<VarkaVectorIR> date = calendarInput(n.date(), n, inputs, literals, sink);
        yield date.map(v -> new VarkaVectorIR.TruncDate(v, l.level()));
      }
      case ToWeek w -> {
        Optional<VarkaVectorIR> date = VarkaNodeCompiler.compileNode(
            n.date(), inputs, literals, sink);
        if (date.isEmpty()) {
          yield date;
        }
        LiteralSlot week = VarkaNodeCompiler.intSlot(7, literals);
        // next_day's slot holds dayOfWeek - 1; Monday through the same parser
        // foldWeekday uses, so the constant is the definition's, not a retyped 3.
        LiteralSlot monday = VarkaNodeCompiler.intSlot(
            DateTimeUtils.getDayOfWeekFromString(UTF8String.fromString("MONDAY")) - 1, literals);
        yield Optional.of(new VarkaVectorIR.NextDay(new SubDays(date.get(), week), monday));
      }
    };
  }

  /**
   * Resolves {@code trunc}'s format operand through {@code DateTimeUtils.parseTruncLevel} - the
   * definition, never a re-implementation of its spellings and case folding - to one of the three
   * date levels or the WEEK rewrite, or empty with the reason noted. Like {@code foldWeekday}, the
   * operand is any foldable expression, so it is evaluated eagerly and every way that can fail
   * declines rather than throws: a null format, an unrecognized string, a level below a day
   * ({@code 'DAY'}, {@code 'HOUR'}... - {@code truncDate} is undefined there and the row engine
   * returns NULL), or an exception from evaluating a computed format.
   */
  private static Optional<TruncTarget> foldTruncLevel(Expression format, DeclineSink sink) {
    try {
      Object fmt = format.eval(null);
      if (fmt == null) {
        sink.note("trunc with a null format", format);
        return Optional.empty();
      }
      int level = DateTimeUtils.parseTruncLevel((UTF8String) fmt);
      if (level == DateTimeUtils$.MODULE$.TRUNC_TO_YEAR()) {
        return Optional.of(new ToLevel(TruncLevel.YEAR));
      } else if (level == DateTimeUtils$.MODULE$.TRUNC_TO_MONTH()) {
        return Optional.of(new ToLevel(TruncLevel.MONTH));
      } else if (level == DateTimeUtils$.MODULE$.TRUNC_TO_QUARTER()) {
        return Optional.of(new ToLevel(TruncLevel.QUARTER));
      } else if (level == DateTimeUtils$.MODULE$.TRUNC_TO_WEEK()) {
        return Optional.of(new ToWeek());
      } else if (level == DateTimeUtils$.MODULE$.TRUNC_INVALID()) {
        sink.note("trunc with an unrecognized format", format);
        return Optional.empty();
      }
      sink.note("trunc to a level below a day, which is null for a date", format);
      return Optional.empty();
    } catch (Throwable t) {
      if (!NonFatal.apply(t)) {
        throw t;
      }
      sink.note("trunc format failed to evaluate: " + t.getMessage(), format);
      return Optional.empty();
    }
  }
}
