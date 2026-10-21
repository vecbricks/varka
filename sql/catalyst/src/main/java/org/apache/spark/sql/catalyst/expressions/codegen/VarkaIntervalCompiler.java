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

import java.util.Optional;

import org.apache.spark.sql.catalyst.expressions.Abs;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.DivideYMInterval;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.types.DataType;
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
 * guard is written once. The shared operand helpers and the recursion are
 * {@code VarkaNodeCompiler}'s.
 *
 * <p>The literal and input tables are the classifier's {@link VarkaSlotTable}s.
 */
final class VarkaIntervalCompiler {


  /** {@code INTERVAL YEAR}, which the YEAR casts compare against. */
  private static final YearMonthIntervalType YEAR_INTERVAL = new YearMonthIntervalType(
      YearMonthIntervalType.YEAR(), YearMonthIntervalType.YEAR());

  /** {@code INTERVAL MONTH}, the interval whose value is the month count unchanged. */
  private static final YearMonthIntervalType MONTH_INTERVAL = new YearMonthIntervalType(
      YearMonthIntervalType.MONTH(), YearMonthIntervalType.MONTH());

  private VarkaIntervalCompiler() {
  }

  /**
   * The arm that claims {@code e}, or {@code null}: the year-month interval arms of
   * {@code compileNode}, in their original order.
   */
  static VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
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
          () -> Optional.of(VarkaNodeCompiler.columnRef(br, inputs, LaneType.INT));
      // The interval literal, beside the date literal and for the same reason: the value is
      // already the int the lane holds, so `ym > INTERVAL '6' MONTH` and
      // `coalesce(ym, INTERVAL '0' MONTH)` become a slot rather than a decline.
      case Literal l when l.value() instanceof Integer
          && l.dataType() instanceof YearMonthIntervalType ->
          () -> Optional.of(VarkaNodeCompiler.intSlot((Integer) l.value(), literals));
      // The interval relabels, on `unix_date`'s pattern: a cast that returns its operand
      // unchanged is the child alone, with no node emitted. `intToYearMonthInterval` returns `v`
      // for a MONTH end field and `yearMonthIntervalToInt` returns `v` for a MONTH-ended
      // interval, so both directions of the MONTH unit are the identity on the lane; only the
      // Spark type on the outside differs, and that rides on `outputTypes`. The YEAR unit is
      // neither direction's identity - it multiplies or divides by twelve. Its outbound half is
      // the arm below; its inbound half, `CAST(ym AS INT)` over a YEAR-ended interval, is the
      // division by twelve two arms further down.
      case Cast c when endsIn(c, YearMonthIntervalType.MONTH())
          && c.child().dataType().equals(DataTypes.IntegerType) ->
          () -> VarkaNodeCompiler.compileIntOperand(
              c.child(), "the month count", inputs, literals, sink);
      // The unit relabel between two year-month intervals, which is not a cast a user writes but
      // the one type coercion inserts whenever two units meet - `ymm + ymy` widens both operands
      // to YEAR TO MONTH before the add. `Cast.castToYearMonthInterval` computes
      // `periodToMonths(monthsToPeriod(v), endField)`, which splits the count into whole years
      // and a remainder and puts it back together: exactly `v` again for a MONTH end field, at
      // every int including `Int.MinValue`, since the reassembly's `multiplyExact` is over
      // `v / 12`. So this direction emits nothing and only `outputTypes` moves. The YEAR-ended
      // direction drops the remainder, which is a division by twelve, and has its own arm below.
      case Cast c when endsIn(c, YearMonthIntervalType.MONTH())
          && c.child().dataType() instanceof YearMonthIntervalType ->
          () -> VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink);
      // `CAST(i AS INTERVAL YEAR)` in a value position, which is `12 * i` with an interval
      // output. `IntervalUtils.intToYearMonthInterval` computes it with `Math.multiplyExact`
      // whatever the session's ANSI mode, so the multiply is checked and the bound is the only
      // thing that removes it. The calendar family lowers the same cast the same way in
      // `add_months`' month-count position, through `yearsToMonths`.
      case Cast c when c.dataType().equals(YEAR_INTERVAL)
          && c.child().dataType().equals(DataTypes.IntegerType) ->
          () -> yearsToMonths(c, inputs, literals, sink);
      // The truncating half of the pair above: narrowing a year-month interval to a YEAR-ended
      // unit keeps only the whole years. `Cast.castToYearMonthInterval` computes
      // `periodToMonths(monthsToPeriod(v), YEAR)`, which is `months - months % 12`, i.e.
      // `(v / 12) * 12` over Java's truncating `/`. The product cannot overflow, since
      // `|(v / 12) * 12| <= |v|`, so the multiply wraps and nothing bounds the operand. Type
      // coercion never produces this cast (it widens the end field); it comes from a cast the
      // user wrote. A cast to the operand's own type is the identity in Spark, whatever the
      // stored count, so it is the child alone and must not truncate.
      case Cast c when endsIn(c, YearMonthIntervalType.YEAR())
          && c.child().dataType() instanceof YearMonthIntervalType ->
          () -> {
            int mark = literals.size();
            return rolledBack(
                intervalOperand(c.child(), "the narrowed interval", inputs, literals, sink)
                    .map(x -> c.child().dataType().equals(c.dataType())
                        ? x
                        : (VarkaVectorIR) new IntArith(IntOp.MUL, Overflow.WRAP,
                            new ConstDivide(x, 12), twelve(literals))),
                literals, mark);
          };
      // `CAST(ymy AS INT)`, the inbound half of the YEAR unit: `yearMonthIntervalToInt` is
      // `v / 12` for a YEAR end field, the same node `extract(YEAR FROM ym)` builds. Only a
      // single-field interval casts to an integral type, so a YEAR end field is a YEAR-only
      // interval.
      case Cast c when c.dataType().equals(DataTypes.IntegerType)
          && c.child().dataType() instanceof YearMonthIntervalType ym
          && ym.endField() == YearMonthIntervalType.YEAR() ->
          () -> {
            int mark = literals.size();
            return rolledBack(
                intervalOperand(c.child(), "the cast interval", inputs, literals, sink)
                    .map(x -> (VarkaVectorIR) new ConstDivide(x, 12)),
                literals, mark);
          };
      case Cast c when c.dataType().equals(DataTypes.IntegerType)
          && c.child().dataType().equals(MONTH_INTERVAL) ->
          () -> VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink);
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
      // over a stored month count that nothing bounds. The calendar's magic multiply is exact
      // over 0..49,151 only, about one forty-thousandth of the type, so this is a plain
      // `ConstDivide`, whose multiply-high form (widen, multiply, shift) is exact for every int32
      // at 12 (`int_mulhi_divide.smt2`) and truncates already, so it needs neither a range
      // guard nor a correction step. `sql/varka/plans/verify_ym_division.py` sweeps it over all
      // 2^32 month counts.
      case ExtractANSIIntervalYears x ->
          () -> intervalOperand(x.child(), "the interval", inputs, literals, sink)
              .map(child -> (VarkaVectorIR) new ConstDivide(child, 12));
      // `(months % 12).toByte`. The remainder is one multiply and one subtract away from the
      // quotient above, so the division is not what blocks this: the result is a `ByteType`, and
      // Varka has neither a byte lane nor an Arrow vector to store one into. It declines until a
      // narrowing store exists, which is its own question (m5/PLAN.md 2.20).
      case ExtractANSIIntervalMonths x ->
          () -> sink.decline("extract(MONTH FROM ym) returns a byte, which has no lane", x);
      case MultiplyYMInterval m -> () -> multiply(m, inputs, literals, sink);
      case DivideYMInterval m -> () -> divide(m, inputs, literals, sink);
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
  static Optional<VarkaVectorIR> intervalOperand(
      Expression e,
      String position,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (!(e.dataType() instanceof YearMonthIntervalType)) {
      return sink.decline(position + " of type " + e.dataType().simpleString()
          + " is not a year-month interval", e);
    }
    return VarkaNodeCompiler.compileNode(
        e, inputs, literals, sink);
  }

  /**
   * The slot holding {@code 12}, the months in a year - {@code make_ym_interval} and the YEAR
   * casts.
   */
  static LiteralSlot twelve(VarkaSlotTable literals) {
    return VarkaNodeCompiler.intSlot(12, literals);
  }

  /**
   * {@code 12 * i}, checked, for {@code CAST(i AS INTERVAL YEAR)}: this family's arm, and the
   * calendar family's in {@code add_months}' month-count position.
   */
  static Optional<VarkaVectorIR> yearsToMonths(
      Cast c,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    int mark = literals.size();
    Optional<VarkaVectorIR> built = VarkaNodeCompiler.intOperand(c.child(), inputs, literals, sink);
    if (built.isPresent()) {
      built = VarkaNodeCompiler.arithOver(
          IntOp.MUL, Overflow.FAIL, built.get(), twelve(literals), c, literals, mark, sink);
    }
    return rolledBack(built, literals, mark);
  }

  /**
   * {@code IntervalMathUtils.negateExact}, which throws on {@code Int.MinValue} alone, so any
   * bound at all rules it out - {@code IntNeg}'s reasoning over an interval operand.
   */
  private static Optional<VarkaVectorIR> negate(
      Expression child,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return intervalOperand(child, "the negated interval", inputs, literals, sink)
        .map(x -> (VarkaVectorIR) new IntNeg(negationMode(x, literals, sink), x));
  }

  /**
   * There is no abs op in the IR, and none is needed: {@code abs(x)} is the blend
   * {@code if (x < 0) -x else x}, which is the int negate node under {@code IfElse}. The only
   * input that overflows a negation is {@code Int.MinValue}, and it is negative, so it takes the
   * {@code IntNeg} arm - the check fires exactly where {@code IntegerExactNumeric} throws. That
   * puts a checked node under a {@code CASE} arm, and that is deliberate: the guard is qualified
   * by the arm, so only the lanes that actually negate can condemn the batch.
   */
  private static Optional<VarkaVectorIR> absolute(
      Expression child,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return intervalOperand(child, "the absolute interval", inputs, literals, sink).map(x -> {
      LiteralSlot zero = VarkaNodeCompiler.intSlot(0, literals);
      return (VarkaVectorIR) new IfElse(new Compare(CompareOp.LT, x, zero),
          new IntNeg(negationMode(x, literals, sink), x), x);
    });
  }

  /**
   * Whether negating {@code x} needs its check: unless its magnitude is known to be at most
   * {@code Int.MaxValue}, the one value whose negation overflows may be there. This family's
   * negate and {@code abs}, and the calendar family's negated month count.
   */
  static Overflow negationMode(VarkaVectorIR x, VarkaSlotTable literals, DeclineSink sink) {
    boolean bounded = VarkaNodeCompiler.boundedByIntMax(x, literals, sink);
    return bounded ? Overflow.WRAP : Overflow.FAIL;
  }

  /**
   * {@code toIntExact(addExact(months, multiplyExact(years, 12)))} - two of the int32 arithmetic
   * nodes composed, both checked. Over bounded operands the bound removes both checks, which is
   * what makes {@code make_ym_interval(year(d), month(d))} fuse with none; over an unbounded int
   * column the multiply keeps its check and declines, as every checked multiply does.
   */
  private static Optional<VarkaVectorIR> makeInterval(
      MakeYMInterval m,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    int mark = literals.size();
    Optional<VarkaVectorIR> years =
        VarkaNodeCompiler.intOperand(m.years(), inputs, literals, sink);
    if (years.isEmpty()) {
      return rolledBack(years, literals, mark);
    }
    Optional<VarkaVectorIR> months =
        VarkaNodeCompiler.intOperand(m.months(), inputs, literals, sink);
    if (months.isEmpty()) {
      return rolledBack(months, literals, mark);
    }
    Optional<VarkaVectorIR> scaled = VarkaNodeCompiler.arithOver(
        IntOp.MUL, Overflow.FAIL, years.get(), twelve(literals), m, literals, mark, sink);
    if (scaled.isEmpty()) {
      return rolledBack(scaled, literals, mark);
    }
    return rolledBack(VarkaNodeCompiler.arithOver(
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
  private static Optional<VarkaVectorIR> multiply(
      MultiplyYMInterval m,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (!m.num().dataType().equals(DataTypes.IntegerType)) {
      return sink.decline("interval multiplier of type " + m.num().dataType().simpleString()
          + " is not an int32 lane", m);
    }
    int mark = literals.size();
    Optional<VarkaVectorIR> x =
        intervalOperand(m.interval(), "the multiplied interval", inputs, literals, sink);
    if (x.isEmpty()) {
      return rolledBack(x, literals, mark);
    }
    Optional<VarkaVectorIR> k = VarkaNodeCompiler.intOperand(m.num(), inputs, literals, sink);
    if (k.isEmpty()) {
      return rolledBack(k, literals, mark);
    }
    return rolledBack(VarkaNodeCompiler.arithOver(
        IntOp.MUL, Overflow.FAIL, x.get(), k.get(), m, literals, mark, sink), literals, mark);
  }

  /**
   * {@code IntMath.divide(months, num, RoundingMode.HALF_UP)} for a literal {@code num} of the
   * int family. Spark raises before dividing, in every mode, on {@code num == 0} and on
   * {@code months == Int.MinValue && num == -1}; the compiler refuses both literals (the row
   * engine raises), because a negation that declined the batch on {@code Int.MinValue} raised
   * an error without the SQL context Spark's carries. A column, a fractional or a decimal
   * divisor, and a {@code Long} literal (which takes {@code LongMath.divide}), decline.
   *
   * <p>HALF_UP stays on the int lane for {@code |d| >= 2}, with no widening and no guard. With
   * {@code q = m / d} truncated and {@code r = m - q * d} (so {@code |r| < |d|}, and {@code r}
   * has the sign of {@code m} or is zero), the quotient moves one step away from zero iff
   * {@code 2 * |r| >= |d|}, i.e. iff {@code |r| >= ceil(|d| / 2)}, in the direction
   * {@code sign(m) * sign(d)}. The multiply wraps and is still exact, since the true remainder
   * fits an int; negating {@code r} cannot overflow, since {@code |r| < 2^31}; and
   * {@code q + 1} cannot overflow, since {@code |q| <= 2^30}. The rule is swept over every int
   * dividend in {@code VarkaEmitterYmDivideSuite} and {@code verify_ym_division.py}.
   */
  private static Optional<VarkaVectorIR> divide(
      DivideYMInterval m,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    DataType type = m.num().dataType();
    if (type.equals(DataTypes.LongType)) {
      return sink.decline("interval divisor of type bigint takes LongMath.divide, which the "
          + "int lane does not lower", m);
    }
    if (!type.equals(DataTypes.IntegerType) && !type.equals(DataTypes.ShortType)
        && !type.equals(DataTypes.ByteType)) {
      return sink.decline("interval divisor of type " + type.simpleString()
          + " is not an integral literal", m);
    }
    if (!(m.num() instanceof Literal l)) {
      return sink.decline("interval divisor is not a literal", m);
    }
    if (!(l.value() instanceof Number n)) {
      return sink.decline("interval divisor is a NULL literal", m);
    }
    int d = n.intValue();
    if (d == 0) {
      return sink.decline("interval divided by a literal zero raises", m);
    }
    if (d == -1) {
      return sink.decline("interval divided by -1 overflows at Int.MinValue, which the row "
          + "engine raises on", m);
    }
    int mark = literals.size();
    Optional<VarkaVectorIR> x =
        intervalOperand(m.interval(), "the divided interval", inputs, literals, sink);
    if (x.isEmpty()) {
      return rolledBack(x, literals, mark);
    }
    VarkaVectorIR months = x.get();
    if (d == 1) {
      return x;
    }
    int away = d > 0 ? 1 : -1;
    int half = (int) ((Math.abs((long) d) + 1) / 2);
    LiteralSlot zero = VarkaNodeCompiler.intSlot(0, literals);
    VarkaVectorIR q = new ConstDivide(months, d);
    VarkaVectorIR r = new IntArith(IntOp.SUB, Overflow.WRAP, months,
        new IntArith(IntOp.MUL, Overflow.WRAP, q, VarkaNodeCompiler.intSlot(d, literals)));
    Compare negative = new Compare(CompareOp.LT, months, zero);
    VarkaVectorIR magnitude = new IfElse(negative, new IntNeg(Overflow.WRAP, r), r);
    VarkaVectorIR step = new IfElse(negative, VarkaNodeCompiler.intSlot(-away, literals),
        VarkaNodeCompiler.intSlot(away, literals));
    VarkaVectorIR bump = new IfElse(
        new Compare(CompareOp.GE, magnitude, VarkaNodeCompiler.intSlot(half, literals)),
        step, zero);
    return Optional.of(new IntArith(IntOp.ADD, Overflow.WRAP, q, bump));
  }

  /**
   * The shared body of the binary interval arms. Spark computes {@code ym + ym} and
   * {@code ym - ym} with {@code IntervalMathUtils.addExact}/{@code subtractExact}, which throw in
   * every evaluation mode - there is no {@code LEGACY} wrapping form for an interval and no
   * {@code try_} spelling - so the declared mode is {@code FAIL} unconditionally rather than read
   * off {@code evalMode}, and {@code arithOver}'s bound is the only thing that takes the check
   * off.
   */
  private static Optional<VarkaVectorIR> intervalArith(
      IntOp op,
      Expression l,
      Expression r,
      Expression whole,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    int mark = literals.size();
    Optional<VarkaVectorIR> x =
        intervalOperand(l, "the left interval operand", inputs, literals, sink);
    if (x.isEmpty()) {
      return rolledBack(x, literals, mark);
    }
    Optional<VarkaVectorIR> y =
        intervalOperand(r, "the right interval operand", inputs, literals, sink);
    if (y.isEmpty()) {
      return rolledBack(y, literals, mark);
    }
    return rolledBack(VarkaNodeCompiler.arithOver(
        op, Overflow.FAIL, x.get(), y.get(), whole, literals, mark, sink), literals, mark);
  }

  /** Whether {@code c} casts to a year-month interval whose end field is {@code endField}. */
  private static boolean endsIn(Cast c, byte endField) {
    return c.dataType() instanceof YearMonthIntervalType ym && ym.endField() == endField;
  }

  /**
   * {@code built}, after dropping the literals a failed lowering appended past {@code mark}: a
   * declining arm leaves the table as it found it.
   */
  private static Optional<VarkaVectorIR> rolledBack(
      Optional<VarkaVectorIR> built, VarkaSlotTable literals, int mark) {
    if (built.isEmpty()) {
      literals.truncate(mark);
    }
    return built;
  }
}
