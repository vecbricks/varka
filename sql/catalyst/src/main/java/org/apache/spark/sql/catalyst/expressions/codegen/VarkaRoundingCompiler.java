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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;

import javax.annotation.Nullable;

import org.apache.spark.sql.catalyst.expressions.BRound;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Ceil;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Floor;
import org.apache.spark.sql.catalyst.expressions.Rint;
import org.apache.spark.sql.catalyst.expressions.Round;
import org.apache.spark.sql.catalyst.expressions.RoundBase;
import org.apache.spark.sql.catalyst.expressions.Truncate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes.IntegralRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondValue;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.RoundMode;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.RoundToIntegral;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DecimalType;

/**
 * The rounding family (VARKA-344): {@code rint} over a {@code DOUBLE}; {@code floor},
 * {@code ceil} and {@code ceiling} over the integral operands whose answer is the operand itself;
 * and {@code round}, {@code bround} and {@code truncate} over the integral types.
 *
 * <p>{@code rint} is a {@link RoundToIntegral}, one answer in every mode. {@code floor} and
 * {@code ceil} of a {@code BIGINT} are the operand, Spark's own identity. An {@code INT} operand
 * reaches them as {@code FLOOR(CAST(i AS DOUBLE))}, the first member of their input types the
 * int casts to; an int converts to a double exactly, is its own floor and ceiling, and lies
 * inside the long range that {@code MathUtils.doubleToLong} checks under ANSI, so Spark's answer
 * in both modes is the int widened to the long. That widening is built directly, from the cast's
 * int operand, so the cast itself is never compiled. A {@code DOUBLE} operand needs the
 * double-to-long conversion (row 344b), a {@code SMALLINT} or {@code TINYINT} one the narrow load
 * in a {@code BIGINT} kernel and a {@code FLOAT} one the cast to a double (both row 344c), and a
 * {@code DECIMAL} one the decimal floor and ceiling (row 351); each declines naming that row. A
 * cast from any other type, such as a {@code STRING}, declines naming no row, since no rounding
 * row plans it.
 *
 * <p>{@code round}, {@code bround} and {@code truncate} over {@code TINYINT}, {@code SMALLINT},
 * {@code INT} and {@code BIGINT} at a constant scale are Spark's {@code HALF_UP},
 * {@code HALF_EVEN} and {@code DOWN} of {@code BigDecimal.setScale}. A scale {@code >= 0} is the
 * identity in every mode. A scale {@code -k} over the int lane - an {@code INT}, or a narrow
 * operand widened to it and the answer narrowed back - rounds to a multiple of {@code d = 10^k}
 * by the signed remainder of {@link ConstDivide}'s truncating quotient (see
 * {@link #remainderForm}); the answer wraps, the low bits of the exact rounded integer, which is
 * what Spark's {@code intValue()} (or {@code shortValue()}, {@code byteValue()}) gives under
 * LEGACY. Under ANSI Spark raises where the rounded value leaves the type; the rounding is
 * monotone in the operand, so those operands are two tails, found at compile time by bisection
 * over {@code BigDecimal}'s own answer ({@link #window}), and a {@link GuardedRange} declines a
 * batch holding one, so the row engine raises. A {@code BIGINT} at a negative scale needs the
 * long lane's pre-shift, part 344a3, and declines naming it. {@code int_round.smt2} proves the
 * int lane's steps and windows.
 *
 * <p>Public for {@code VarkaProofFiles}, which renders {@code int_round.smt2} from
 * {@link #window}; the family is reached through {@link #arm} alone.
 */
public final class VarkaRoundingCompiler {

  /**
   * The deepest negative scale admitted. Every integral value rounds to 0 long before it, while
   * {@code BigDecimal.setScale} raises at scales near {@code Integer.MIN_VALUE}, an error the
   * kernel would not reproduce.
   */
  static final int MAX_NEGATIVE_SCALE = 100;

  private VarkaRoundingCompiler() {
  }

  /** The family's claim, or null. */
  static @Nullable VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (e) {
      case Rint r -> () -> operand(r.child(), LaneType.DOUBLE, inputs, literals, sink)
          .map(x -> new RoundToIntegral(RoundMode.RINT, x));
      case Floor f -> () -> floorOrCeil(f, f.child(), inputs, literals, sink);
      case Ceil c -> () -> floorOrCeil(c, c.child(), inputs, literals, sink);
      case Round r -> () -> integral(r, RoundingMode.HALF_UP, r.ansiEnabled(), inputs, literals,
          sink);
      case BRound r -> () -> integral(r, RoundingMode.HALF_EVEN, r.ansiEnabled(), inputs,
          literals, sink);
      case Truncate t -> () -> integral(t, RoundingMode.DOWN, t.ansiEnabled(), inputs, literals,
          sink);
      default -> null;
    };
  }

  private static Optional<VarkaVectorIR> floorOrCeil(
      Expression whole, Expression child, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    DataType t = child.dataType();
    if (DataTypes.LongType.equals(t)) {
      return operand(child, LaneType.LONG, inputs, literals, sink);
    }
    if (child instanceof Cast c && DataTypes.DoubleType.equals(t)) {
      DataType from = c.child().dataType();
      if (DataTypes.IntegerType.equals(from)) {
        return operand(c.child(), LaneType.INT, inputs, literals, sink)
            .map(x -> (VarkaVectorIR) new WidenLane(x, LaneType.LONG));
      }
      if (DataTypes.ShortType.equals(from) || DataTypes.ByteType.equals(from)) {
        return sink.decline("floor or ceil over a cast from " + from.simpleString()
            + " to double, which needs the narrow load in a BIGINT kernel (row 344c)", whole);
      }
      if (DataTypes.FloatType.equals(from)) {
        return sink.decline("floor or ceil over a cast from float to double, which needs the"
            + " cast to a double (row 344c)", whole);
      }
      if (!DataTypes.DoubleType.equals(from)) {
        return sink.decline("floor or ceil over a cast from " + from.simpleString()
            + " to double, which no rounding form serves", whole);
      }
    }
    if (DataTypes.DoubleType.equals(t)) {
      return sink.decline("floor or ceil over double (row 344b)", whole);
    }
    if (t instanceof DecimalType) {
      return sink.decline("floor or ceil over a decimal (row 351)", whole);
    }
    return sink.decline("floor or ceil over " + t.simpleString(), whole);
  }

  /** An operand compiled to {@code lane}, or empty with the lane named. */
  private static Optional<VarkaVectorIR> operand(
      Expression e, LaneType lane, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    // An int column or literal is no family's node, so compileNode leaves it to the int
    // operand rule, which reads it as the int leaf.
    Optional<VarkaVectorIR> x = lane == LaneType.INT
        ? VarkaNodeCompiler.intOperand(e, inputs, literals, sink)
        : VarkaNodeCompiler.compileNode(e, inputs, literals, sink);
    if (x.isPresent() && x.get().laneType() != lane) {
      return sink.decline("a rounding operand computed on the " + x.get().laneType()
          + " lane, not the " + lane + " one", e);
    }
    return x;
  }

  private static Optional<VarkaVectorIR> integral(
      RoundBase whole, RoundingMode mode, boolean ansi, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    Expression child = whole.left();
    // A decimal is declined by its type, before the lane: a DECIMAL of precision 18 or less is
    // read on the long lane as its unscaled value, and the integer rounding of that value would
    // ignore the scale and the result's type, at a non-negative scale as much as a negative one.
    if (child.dataType() instanceof DecimalType) {
      return sink.decline("round, bround or truncate over " + child.dataType().simpleString()
          + " (rows 351, 363)", whole);
    }
    // A floating operand never reaches here: VarkaNodeCompiler.floatingDecline declines it first.
    Optional<LaneType> typeLane = VarkaTypes.lane(child.dataType()).filter(l -> !l.isFloating());
    if (typeLane.isEmpty()) {
      return sink.decline("round, bround or truncate over " + child.dataType().simpleString(),
          whole);
    }
    Expression scaleExpr = whole.right();
    if (!scaleExpr.foldable()) {
      return sink.decline("a scale that is not a constant", whole);
    }
    if (!(scaleExpr.eval(null) instanceof Integer scale)) {
      return sink.decline("a null scale", whole);
    }
    LaneType lane = typeLane.get();
    if (scale >= 0) {
      return operand(child, lane, inputs, literals, sink);
    }
    if (scale < -MAX_NEGATIVE_SCALE) {
      return sink.decline("a scale below -" + MAX_NEGATIVE_SCALE, whole);
    }
    if (lane == LaneType.LONG) {
      return sink.decline("round, bround or truncate over bigint at a negative scale, which "
          + "needs the long lane's pre-shift (row 344, part 344a3)", whole);
    }
    return operand(child, lane, inputs, literals, sink)
        .map(x -> roundInt(x, lane, -scale, mode, ansi, literals));
  }

  /**
   * The rounding at scale {@code -k} at the int lane, a narrow operand widened to it and the
   * answer narrowed back, which is the wrap {@code byteValue()} and {@code shortValue()} give.
   */
  private static VarkaVectorIR roundInt(VarkaVectorIR read, LaneType lane, int k,
      RoundingMode mode, boolean ansi, VarkaSlotTable literals) {
    VarkaVectorIR x = lane == LaneType.INT ? read : new WidenLane(read, LaneType.INT);
    if (ansi) {
      Optional<IntegralRange> w = window(lane, k, mode);
      if (w.isPresent()) {
        x = new GuardedRange(x, w.get().min(), w.get().max(), Overflow.FAIL);
      }
    }
    VarkaVectorIR out;
    if (k > digits(lane)) {
      // 10^k is more than twice every magnitude of the type, so every value rounds to 0, which
      // keeps the operand's validity as a multiply by zero.
      out = new IntArith(IntOp.MUL, Overflow.WRAP, x,
          VarkaNodeCompiler.slot(0, LaneType.INT, literals));
    } else {
      out = remainderForm(x, BigInteger.TEN.pow(k).intValueExact(), mode, literals);
    }
    return lane == LaneType.INT ? out : new NarrowLane(out, lane);
  }

  /**
   * {@code q * d} with {@code q} the truncating quotient {@code q0 = x / d} stepped away from
   * zero by the remainder {@code r = x - q0 * d}, which has {@code x}'s sign and {@code |r| < d}:
   * up where {@code r} passes the half {@code h = d / 2} and down where it passes {@code -h} - at
   * it too for {@code HALF_UP}, at it only for an odd {@code q0} for {@code HALF_EVEN}, never for
   * {@code DOWN}. Half away from zero and half to even are symmetric in the sign, which is why the
   * remainder's sign picks the direction. Every value is on the int lane and wraps.
   */
  private static VarkaVectorIR remainderForm(VarkaVectorIR x, int d, RoundingMode mode,
      VarkaSlotTable literals) {
    VarkaVectorIR q0 = new ConstDivide(x, d);
    VarkaVectorIR dSlot = VarkaNodeCompiler.slot(d, LaneType.INT, literals);
    if (mode == RoundingMode.DOWN) {
      return new IntArith(IntOp.MUL, Overflow.WRAP, q0, dSlot);
    }
    VarkaVectorIR r = new IntArith(IntOp.SUB, Overflow.WRAP, x,
        new IntArith(IntOp.MUL, Overflow.WRAP, q0, dSlot));
    int h = d / 2;
    VarkaVectorIR half = VarkaNodeCompiler.slot(h, LaneType.INT, literals);
    VarkaVectorIR negHalf = VarkaNodeCompiler.slot(-h, LaneType.INT, literals);
    Compare up;
    Compare down;
    if (mode == RoundingMode.HALF_UP) {
      up = new Compare(CompareOp.GE, r, half);
      down = new Compare(CompareOp.LE, r, negHalf);
    } else {
      // The tie test folded into one compare per side: r and h are integers and q0 & 1 is 0 or
      // 1 (1 for a negative odd q0 too, in two's complement), so r + (q0 & 1) > h is r > h, or
      // r == h with q0 odd, and r - (q0 & 1) < -h its mirror. |r| < d <= 10^9, so neither
      // wraps. The chain is a node shallower than an Or of an And, which leaves room for a
      // CASE or a comparison above a narrow operand under ANSI within the emitter's depth.
      VarkaVectorIR odd = new BitOp(BitOpKind.AND, q0,
          VarkaNodeCompiler.slot(1, LaneType.INT, literals));
      up = new Compare(CompareOp.GT, new IntArith(IntOp.ADD, Overflow.WRAP, r, odd), half);
      down = new Compare(CompareOp.LT, new IntArith(IntOp.SUB, Overflow.WRAP, r, odd), negHalf);
    }
    // The steps as values, q0 + [up] - [down], rather than as IfElse arms: a guard below a
    // conditional's arms is qualified by their conditions, and an arm-free tree keeps the ANSI
    // guard qualified by an enclosing CASE alone. CondValue is null where its condition is
    // unknown, which is where the operand is null.
    VarkaVectorIR q = new IntArith(IntOp.SUB, Overflow.WRAP,
        new IntArith(IntOp.ADD, Overflow.WRAP, q0, new CondValue(up, LaneType.INT)),
        new CondValue(down, LaneType.INT));
    return new IntArith(IntOp.MUL, Overflow.WRAP, q, dSlot);
  }

  /** The most decimal digits {@code 10^k} may have before every value of the type rounds to 0. */
  private static int digits(LaneType lane) {
    return switch (lane) {
      case BYTE -> 2;
      case SHORT -> 4;
      case INT -> 9;
      case LONG -> 18;
      case FLOAT, DOUBLE -> throw VarkaErrors.illegalArgument("a floating lane: " + lane);
    };
  }

  /**
   * The operands of {@code lane}'s type whose rounding at scale {@code -k} fits the type, or
   * empty when every operand does. Bisected over {@code BigDecimal}'s exact rounding, which is
   * monotone in the operand, so the bounds are Spark's own.
   */
  public static Optional<IntegralRange> window(LaneType lane, int k, RoundingMode mode) {
    IntegralRange type = VarkaTypes.integralRange(lane);
    long lo = type.min();
    long hi = type.max();
    if (!fits(hi, k, mode, type)) {
      long a = 0;
      long b = hi;
      while (a < b) {
        // The difference read unsigned: over a long's range it passes 2^63.
        long m = a + ((b - a + 1) >>> 1);
        if (fits(m, k, mode, type)) {
          a = m;
        } else {
          b = m - 1;
        }
      }
      hi = a;
    }
    if (!fits(lo, k, mode, type)) {
      long a = lo;
      long b = 0;
      while (a < b) {
        long m = a + ((b - a) >>> 1);
        if (fits(m, k, mode, type)) {
          b = m;
        } else {
          a = m + 1;
        }
      }
      lo = a;
    }
    return lo == type.min() && hi == type.max() ? Optional.empty()
        : Optional.of(new IntegralRange(lo, hi));
  }

  private static boolean fits(long v, int k, RoundingMode mode, IntegralRange type) {
    BigInteger r = BigDecimal.valueOf(v).setScale(-k, mode).toBigInteger();
    return r.compareTo(BigInteger.valueOf(type.min())) >= 0
        && r.compareTo(BigInteger.valueOf(type.max())) <= 0;
  }
}
