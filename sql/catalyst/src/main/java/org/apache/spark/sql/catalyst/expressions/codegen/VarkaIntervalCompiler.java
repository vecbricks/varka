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

import scala.Option;
import scala.collection.mutable.LinkedHashMap;

import org.apache.spark.sql.catalyst.expressions.Abs;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.ExtractANSIIntervalMonths;
import org.apache.spark.sql.catalyst.expressions.ExtractANSIIntervalYears;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.MakeYMInterval;
import org.apache.spark.sql.catalyst.expressions.MultiplyYMInterval;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * The year-month interval family of the compiler: the interval column and literal leaves, the
 * casts between an interval and an int and between two interval units, and the interval algebra -
 * add, subtract, negate, {@code abs}, {@code make_ym_interval}, the extracts and the multiply -
 * which are the int32 arithmetic nodes with an interval-typed output and every one of them
 * checked, since Spark computes them with the exact methods in every evaluation mode.
 *
 * <p>{@link #arm} is the family's one entry from the chain {@code VarkaExpressionCompiler}
 * dispatches through: a {@code switch} whose cases test and deconstruct a node and return the
 * lowering as a deferred call, or {@code null} for a node the family does not claim. Testing has
 * no side effects, so the chain can ask a family whether it claims a node - which
 * {@code VarkaFamilyChainSuite} does for every family - before anything is compiled, and every
 * guard is written once. The shared operand helpers and the recursion are the facade's.
 *
 * <p>The literal and input tables are the facade's {@code mutable.LinkedHashMap[Int, Int]}. Java
 * sees their type arguments erased to {@code Object}, and Scala does not pass an
 * {@code [Int, Int]} map where an {@code [Object, Object]} one is declared, so the methods Scala
 * calls take them as {@code LinkedHashMap<?, ?>} and {@link #table} restores the declared type
 * once, for the facade's own methods.
 */
final class VarkaIntervalCompiler {

  /**
   * The facade, whose helpers the arms call. It is a Scala {@code private[sql] object}, which
   * scalac compiles to its module class alone, with no class of static forwarders, so Java
   * reaches its methods through the module's one instance.
   */
  private static final VarkaExpressionCompiler$ FACADE = VarkaExpressionCompiler$.MODULE$;

  /** {@code INTERVAL YEAR}, which the YEAR casts compare against. */
  private static final YearMonthIntervalType YEAR_INTERVAL = new YearMonthIntervalType(
      YearMonthIntervalType.YEAR(), YearMonthIntervalType.YEAR());

  /** {@code INTERVAL MONTH}, the interval whose value is the month count unchanged. */
  private static final YearMonthIntervalType MONTH_INTERVAL = new YearMonthIntervalType(
      YearMonthIntervalType.MONTH(), YearMonthIntervalType.MONTH());

  private VarkaIntervalCompiler() {
  }

  /** A claimed node's lowering, run when the chain applies the arm. */
  @FunctionalInterface
  interface Arm {
    Option<VarkaVectorIR> compile();
  }

  /**
   * The arm that claims {@code e}, or {@code null}: the year-month interval arms of
   * {@code compileNode}, in their original order.
   */
  static Arm arm(
      Expression e,
      LinkedHashMap<?, ?> inputTable,
      LinkedHashMap<?, ?> literalTable,
      DeclineSink sink) {
    LinkedHashMap<Object, Object> inputs = table(inputTable);
    LinkedHashMap<Object, Object> literals = table(literalTable);
    return switch (e) {
      // A year-month interval column, on the same lane. Its value is a count of months in every
      // unit, so nothing about the lowering changes; what makes widening the leaf safe rather
      // than "do not open it wider" is that Spark's own typing decides where the value may
      // appear. An interval only type-checks into DateAddYMInterval, the ordered comparisons and
      // IN, the same-typed Least/Greatest/Coalesce/If/CaseWhen, and Cast - never into date_add's
      // offset, datediff, a calendar extraction or AddMonths' date operand, all of which are typed
      // DateType or IntegerType. So an interval in a date position is a type error the analyzer
      // rejected before the compiler ran, and the leaf cannot put one there.
      case BoundReference br when br.dataType() instanceof YearMonthIntervalType ->
          () -> Option.apply(FACADE.columnRef(br, inputs, LaneType.INT));
      // The interval literal, beside the date literal and for the same reason: the value is
      // already the int the lane holds, so `ym > INTERVAL '6' MONTH` and
      // `coalesce(ym, INTERVAL '0' MONTH)` become a slot rather than a decline.
      case Literal l when l.value() instanceof Integer
          && l.dataType() instanceof YearMonthIntervalType ->
          () -> Option.apply(FACADE.intSlot((Integer) l.value(), literals));
      // The interval relabels, on `unix_date`'s pattern: a cast that returns its operand
      // unchanged is the child alone, with no node emitted. `intToYearMonthInterval` returns `v`
      // for a MONTH end field and `yearMonthIntervalToInt` returns `v` for a MONTH-ended
      // interval, so both directions of the MONTH unit are the identity on the lane; only the
      // Spark type on the outside differs, and that rides on `outputTypes`. The YEAR unit is
      // neither direction's identity - it multiplies or divides by twelve. Its outbound half is
      // the arm below; its inbound half, `CAST(ym AS INT)` over a YEAR-ended interval, is a
      // division by twelve, which is not supported yet - it belongs with the year-month extracts.
      case Cast c when endsIn(c, YearMonthIntervalType.MONTH())
          && c.child().dataType().equals(DataTypes.IntegerType) ->
          () -> FACADE.compileIntOperand(
              c.child(), "the month count", inputs, literals, sink);
      // The unit relabel between two year-month intervals, which is not a cast a user writes but
      // the one type coercion inserts whenever two units meet - `ymm + ymy` widens both operands
      // to YEAR TO MONTH before the add. `Cast.castToYearMonthInterval` computes
      // `periodToMonths(monthsToPeriod(v), endField)`, which splits the count into whole years
      // and a remainder and puts it back together: exactly `v` again for a MONTH end field, at
      // every int including `Int.MinValue`, since the reassembly's `multiplyExact` is over
      // `v / 12`. So this direction emits nothing and only `outputTypes` moves. The YEAR-ended
      // direction drops the remainder, which is a division by twelve, and declines below.
      case Cast c when endsIn(c, YearMonthIntervalType.MONTH())
          && c.child().dataType() instanceof YearMonthIntervalType ->
          () -> FACADE.compileNode(c.child(), inputs, literals, sink);
      // `CAST(i AS INTERVAL YEAR)` in a value position, which is `12 * i` with an interval
      // output. `IntervalUtils.intToYearMonthInterval` computes it with `Math.multiplyExact`
      // whatever the session's ANSI mode, so the multiply is checked and the bound is the only
      // thing that removes it. The calendar family lowers the same cast the same way in
      // `add_months`' month-count position, through `yearsToMonths`.
      case Cast c when c.dataType().equals(YEAR_INTERVAL)
          && c.child().dataType().equals(DataTypes.IntegerType) ->
          () -> yearsToMonths(c, inputs, literals, sink);
      // The truncating half of the pair above, named rather than left to the generic decline:
      // narrowing a year-month interval to a YEAR-ended unit keeps only the whole years, which is
      // `v - v % 12` and so a division. Type coercion never produces this - it widens the end
      // field - so it reaches here only from a cast the user wrote, and it is not supported yet,
      // belonging with `extract(YEAR FROM ym)` and `ym / k`.
      case Cast c when endsIn(c, YearMonthIntervalType.YEAR())
          && c.child().dataType() instanceof YearMonthIntervalType ->
          () -> decline(
              "year-month interval narrowed to a YEAR-ended unit, which divides by twelve", c,
              sink);
      case Cast c when c.dataType().equals(DataTypes.IntegerType)
          && c.child().dataType().equals(MONTH_INTERVAL) ->
          () -> FACADE.compileNode(c.child(), inputs, literals, sink);
      // Group A: the year-month interval algebra, on the int32 arithmetic nodes with an
      // interval-typed output. The int arms keep their `IntegerType` gate and these are siblings
      // rather than a widening of it, because int arithmetic is an int-typed concept and a
      // widened gate is how an interval reaches a position that reads it as a day count. Every
      // one of them is checked in every evaluation mode - Spark computes them with `addExact`,
      // `subtractExact`, `negateExact` and `multiplyExact`, and there is no `LEGACY` or `try_`
      // spelling for an interval - so the mode is `FAIL` and the bound is the only thing that
      // takes the check off.
      case Add a when a.dataType() instanceof YearMonthIntervalType ->
          () -> intervalArith(IntOp.ADD, a.left(), a.right(), a, inputs, literals, sink);
      case Subtract s when s.dataType() instanceof YearMonthIntervalType ->
          () -> intervalArith(IntOp.SUB, s.left(), s.right(), s, inputs, literals, sink);
      case UnaryMinus n when n.dataType() instanceof YearMonthIntervalType ->
          () -> negate(n.child(), inputs, literals, sink);
      case Abs n when n.dataType() instanceof YearMonthIntervalType ->
          () -> absolute(n.child(), inputs, literals, sink);
      case MakeYMInterval m -> () -> makeInterval(m, inputs, literals, sink);
      // `IntervalUtils.getYears(months)` is `months / 12` - Java's `/`, truncating toward zero -
      // over a stored month count that nothing bounds. The int-lane magic multiply the calendar
      // uses is exact over 0..49,151, about one forty-thousandth of the type, so this division
      // goes through the double lane instead: exact for every int32, and truncating already, so
      // it needs neither a range guard nor a correction step.
      // `sql/varka/plans/verify_ym_division.py` checks both claims over all 2^32 month counts.
      case ExtractANSIIntervalYears x ->
          () -> intervalOperand(x.child(), "the interval", inputs, literals, sink)
              .map(child -> (VarkaVectorIR) new ConstDivide(child, 12));
      // `(months % 12).toByte`. The remainder is one multiply and one subtract away from the
      // quotient above, so the division is not what blocks this: the result is a `ByteType`, and
      // Varka has neither a byte lane nor an Arrow vector to store one into. It declines until a
      // narrowing store exists, which is its own question (PLAN_MILESTONE_5.md 2.20).
      case ExtractANSIIntervalMonths x ->
          () -> decline("extract(MONTH FROM ym) returns a byte, which has no lane", x, sink);
      case MultiplyYMInterval m -> () -> multiply(m, inputs, literals, sink);
      default -> null;
    };
  }

  /**
   * An operand of the year-month interval algebra: a value whose lane is a count of months. The
   * interval column and the interval literal are {@code compileNode}'s own leaves, and nested
   * interval arithmetic is the arms above, so this is a type gate over the same walk - the
   * interval counterpart of {@code intOperand}, and separate for the same reason the arms are
   * separate rather than the int arms' type gate being widened.
   */
  static Option<VarkaVectorIR> intervalOperand(
      Expression e,
      String position,
      LinkedHashMap<?, ?> inputs,
      LinkedHashMap<?, ?> literals,
      DeclineSink sink) {
    if (!(e.dataType() instanceof YearMonthIntervalType)) {
      return decline(position + " of type " + e.dataType().simpleString()
          + " is not a year-month interval", e, sink);
    }
    return FACADE.compileNode(e, table(inputs), table(literals), sink);
  }

  /**
   * The slot holding {@code 12}, the months in a year - {@code make_ym_interval} and the YEAR
   * casts.
   */
  static LiteralSlot twelve(LinkedHashMap<?, ?> literals) {
    return FACADE.intSlot(12, table(literals));
  }

  /**
   * {@code 12 * i}, checked, for {@code CAST(i AS INTERVAL YEAR)}: this family's arm, and the
   * calendar family's in {@code add_months}' month-count position.
   */
  static Option<VarkaVectorIR> yearsToMonths(
      Cast c,
      LinkedHashMap<?, ?> inputTable,
      LinkedHashMap<?, ?> literalTable,
      DeclineSink sink) {
    LinkedHashMap<Object, Object> inputs = table(inputTable);
    LinkedHashMap<Object, Object> literals = table(literalTable);
    int mark = literals.size();
    Option<VarkaVectorIR> built = FACADE.intOperand(c.child(), inputs, literals, sink);
    if (built.isDefined()) {
      built = FACADE.arithOver(
          IntOp.MUL, Overflow.FAIL, built.get(), twelve(literals), c, literals, mark, sink);
    }
    return rolledBack(built, literals, mark);
  }

  /**
   * {@code IntervalMathUtils.negateExact}, which throws on {@code Int.MinValue} alone, so any
   * bound at all rules it out - {@code IntNeg}'s reasoning over an interval operand.
   */
  private static Option<VarkaVectorIR> negate(
      Expression child,
      LinkedHashMap<Object, Object> inputs,
      LinkedHashMap<Object, Object> literals,
      DeclineSink sink) {
    return intervalOperand(child, "the negated interval", inputs, literals, sink)
        .map(x -> (VarkaVectorIR) new IntNeg(negationMode(x, literals), x));
  }

  /**
   * There is no abs op in the IR, and none is needed: {@code abs(x)} is the blend
   * {@code if (x < 0) -x else x}, which is the int negate node under {@code IfElse}. The only
   * input that overflows a negation is {@code Int.MinValue}, and it is negative, so it takes the
   * {@code IntNeg} arm - the check fires exactly where {@code IntegerExactNumeric} throws. That
   * puts a checked node under a {@code CASE} arm, and that is deliberate: the guard is qualified
   * by the arm, so only the lanes that actually negate can condemn the batch.
   */
  private static Option<VarkaVectorIR> absolute(
      Expression child,
      LinkedHashMap<Object, Object> inputs,
      LinkedHashMap<Object, Object> literals,
      DeclineSink sink) {
    return intervalOperand(child, "the absolute interval", inputs, literals, sink).map(x -> {
      LiteralSlot zero = FACADE.intSlot(0, literals);
      return (VarkaVectorIR) new IfElse(new Compare(CompareOp.LT, x, zero),
          new IntNeg(negationMode(x, literals), x), x);
    });
  }

  /**
   * Whether negating {@code x} needs its check: unless its magnitude is known to be at most
   * {@code Int.MaxValue}, the one value whose negation overflows may be there. This family's
   * negate and {@code abs}, and the calendar family's negated month count.
   */
  static Overflow negationMode(VarkaVectorIR x, LinkedHashMap<?, ?> literals) {
    Option<Object> magnitude = FACADE.magnitude(x, table(literals));
    boolean checked = magnitude.isEmpty() || (Long) magnitude.get() > Integer.MAX_VALUE;
    return checked ? Overflow.FAIL : Overflow.WRAP;
  }

  /**
   * {@code toIntExact(addExact(months, multiplyExact(years, 12)))} - two of the int32 arithmetic
   * nodes composed, both checked. Over bounded operands the bound removes both checks, which is
   * what makes {@code make_ym_interval(year(d), month(d))} fuse with none; over an unbounded int
   * column the multiply keeps its check and declines, as every checked multiply does.
   */
  private static Option<VarkaVectorIR> makeInterval(
      MakeYMInterval m,
      LinkedHashMap<Object, Object> inputs,
      LinkedHashMap<Object, Object> literals,
      DeclineSink sink) {
    int mark = literals.size();
    Option<VarkaVectorIR> years =
        FACADE.intOperand(m.years(), inputs, literals, sink);
    if (years.isEmpty()) {
      return rolledBack(years, literals, mark);
    }
    Option<VarkaVectorIR> months =
        FACADE.intOperand(m.months(), inputs, literals, sink);
    if (months.isEmpty()) {
      return rolledBack(months, literals, mark);
    }
    Option<VarkaVectorIR> scaled = FACADE.arithOver(
        IntOp.MUL, Overflow.FAIL, years.get(), twelve(literals), m, literals, mark, sink);
    if (scaled.isEmpty()) {
      return rolledBack(scaled, literals, mark);
    }
    return rolledBack(FACADE.arithOver(
        IntOp.ADD, Overflow.FAIL, months.get(), scaled.get(), m, literals, mark, sink),
        literals, mark);
  }

  /**
   * {@code Math.multiplyExact(months, num)} for the int-family arms. Both operands have to be
   * bounded before the check comes off, and a stored interval column never is, so a literal
   * multiplier alone does not buy it: {@code ym * 2} declines, while
   * {@code make_ym_interval(year(d), month(d)) * 2} fuses over a bounded interval. The
   * {@code Long}, {@code Decimal} and {@code Double} arms are not int32 lanes and decline by type
   * rather than reaching {@code intOperand}, which would report them as "not an int column or
   * literal" and hide which of the two is wrong.
   */
  private static Option<VarkaVectorIR> multiply(
      MultiplyYMInterval m,
      LinkedHashMap<Object, Object> inputs,
      LinkedHashMap<Object, Object> literals,
      DeclineSink sink) {
    if (!m.num().dataType().equals(DataTypes.IntegerType)) {
      return decline("interval multiplier of type " + m.num().dataType().simpleString()
          + " is not an int32 lane", m, sink);
    }
    int mark = literals.size();
    Option<VarkaVectorIR> x =
        intervalOperand(m.interval(), "the multiplied interval", inputs, literals, sink);
    if (x.isEmpty()) {
      return rolledBack(x, literals, mark);
    }
    Option<VarkaVectorIR> k = FACADE.intOperand(m.num(), inputs, literals, sink);
    if (k.isEmpty()) {
      return rolledBack(k, literals, mark);
    }
    return rolledBack(FACADE.arithOver(
        IntOp.MUL, Overflow.FAIL, x.get(), k.get(), m, literals, mark, sink), literals, mark);
  }

  /**
   * The shared body of the binary interval arms. Spark computes {@code ym + ym} and
   * {@code ym - ym} with {@code IntervalMathUtils.addExact}/{@code subtractExact}, which throw in
   * every evaluation mode - there is no {@code LEGACY} wrapping form for an interval and no
   * {@code try_} spelling - so the declared mode is {@code FAIL} unconditionally rather than read
   * off {@code evalMode}, and {@code arithOver}'s bound is the only thing that takes the check
   * off.
   */
  private static Option<VarkaVectorIR> intervalArith(
      IntOp op,
      Expression l,
      Expression r,
      Expression whole,
      LinkedHashMap<Object, Object> inputs,
      LinkedHashMap<Object, Object> literals,
      DeclineSink sink) {
    int mark = literals.size();
    Option<VarkaVectorIR> x =
        intervalOperand(l, "the left interval operand", inputs, literals, sink);
    if (x.isEmpty()) {
      return rolledBack(x, literals, mark);
    }
    Option<VarkaVectorIR> y =
        intervalOperand(r, "the right interval operand", inputs, literals, sink);
    if (y.isEmpty()) {
      return rolledBack(y, literals, mark);
    }
    return rolledBack(FACADE.arithOver(
        op, Overflow.FAIL, x.get(), y.get(), whole, literals, mark, sink), literals, mark);
  }

  /** Whether {@code c} casts to a year-month interval whose end field is {@code endField}. */
  private static boolean endsIn(Cast c, byte endField) {
    return c.dataType() instanceof YearMonthIntervalType ym && ym.endField() == endField;
  }

  /** Notes {@code reason} against {@code e} and declines it. */
  private static Option<VarkaVectorIR> decline(String reason, Expression e, DeclineSink sink) {
    sink.note(reason, e);
    return Option.empty();
  }

  /**
   * {@code built}, after dropping the literals a failed lowering appended past {@code mark}: a
   * declining arm leaves the table as it found it.
   */
  private static Option<VarkaVectorIR> rolledBack(
      Option<VarkaVectorIR> built, LinkedHashMap<Object, Object> literals, int mark) {
    if (built.isEmpty()) {
      FACADE.truncate(literals, mark);
    }
    return built;
  }

  /** A facade table as the facade's own methods declare it; see the class doc. */
  @SuppressWarnings("unchecked")
  private static LinkedHashMap<Object, Object> table(LinkedHashMap<?, ?> table) {
    return (LinkedHashMap<Object, Object>) table;
  }
}
