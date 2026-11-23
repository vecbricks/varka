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
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecimalCompiler.Scaled;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;

/**
 * The casts from a decimal of precision 18 or less to the integral types, another such decimal
 * and {@code BOOLEAN} (VARKA-350), the {@link
 * org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily#DECIMAL_CAST} family. Each
 * is integer arithmetic on the unscaled long of {@link Scaled}, with Spark's answer in every mode
 * as the row engine reads a decimal of precision 18 or less, long-backed (`VARKA-365.md` 11):
 *
 * <ul>
 *   <li><b>to an integral type</b>: {@code t = trunc(u / 10^s)} ({@link #truncated}), Spark's
 *       {@code Decimal.toLong} in every mode, which at precision 18 or less cannot leave a long.
 *       {@code BIGINT} is {@code t}. The three narrow targets are {@code NarrowLane(t)} under
 *       LEGACY, the low bits of the long as Spark's {@code toInt} keeps them, and under ANSI and
 *       TRY {@code NarrowLane(GuardedRange(t, MIN, MAX))}, the exact rule of
 *       {@code roundToNumeric} over a long-backed value, the guard declining the batch under
 *       ANSI, where Spark raises {@code CAST_OVERFLOW}, and nulling the lane under TRY;
 *   <li><b>to {@code BOOLEAN}</b>: {@code NOT(u = 0)} as a condition, which cannot fail;
 *   <li><b>to a decimal</b> of scale {@code S} and precision {@code P}, from {@code (p, s)}: a
 *       rescale up by {@code k = S - s} digits is a multiply by {@code 10^k} behind a guard of
 *       {@code |u| <= 10^(P - k) - 1}, the precision check moved before the multiply
 *       (guard-the-inputs: {@code |u * 10^k| <= 10^P - 1} exactly when the guard holds, so the
 *       multiply never sees a value whose product could wrap); a rescale down by {@code k}
 *       digits is {@code HALF_UP} rounding ({@link #roundedHalfUp}) followed by the check
 *       {@code |q| <= 10^P - 1}, which reads a quotient below {@code 10^18} and so no value that
 *       could have wrapped; a precision change alone is the check.
 * </ul>
 *
 * <p>A guard into a decimal fails under ANSI, where Spark raises
 * {@code NUMERIC_VALUE_OUT_OF_RANGE}, so the row engine raises it, and nulls the lane under
 * LEGACY and TRY, where Spark answers null. Each guard is left out where the types prove it can
 * never fire: a narrow target whose range holds {@code 10^(p - s) - 1}, a rescale up with
 * {@code p + k <= P} (coercion's widening rescale, which stays {@code VarkaDecimalCompiler}'s and
 * so outside this family's switch), and a rescale down with {@code p - k < P}.
 *
 * <p>The casts from and to the floating types are rows 346, 350b, 350c and 366's and decline in
 * {@code VarkaNodeCompiler.floatingDecline}; a decimal wider than 18 digits is rows 359 and 364's,
 * and a cast into a decimal from an integral type or {@code BOOLEAN} declines in
 * {@code VarkaDecimalCompiler}.
 */
final class VarkaDecimalCastCompiler {

  /** The widest precision on the long lane, 18. */
  private static final int MAX_PRECISION = 18;

  /**
   * The exclusive bound on a long-lane constant division's dividend magnitude, {@code 2^52}
   * ({@link ConstDivide#EXACT_DIVIDEND_BOUND}).
   */
  private static final long DIVIDEND_BOUND = ConstDivide.EXACT_DIVIDEND_BOUND;

  /**
   * Why a decimal cast, value or test, declines on an x86 host whose 64-bit species have two
   * lanes or fewer ({@link DeclineSink#declinesTwoLaneChecks}, the session's setting of rows 104
   * and 503).
   * C2 lowers no mask and no two-vector rearrange at two 64-bit lanes: in the dense body of
   * {@code CAST(x AS BIGINT)} over {@code DECIMAL(9, 2)} and {@code DECIMAL(16, 9)} at 128 bits
   * the decimal slots' rearranges, the compares, blends and mask {@code or} are calls into the
   * Vector API's Java fallback, which allocates ({@code VARKA-350.md} 10.3), the load row 596
   * measured at 0.2X of the row path for a decimal compare written as a value.
   */
  static final String TWO_LANE_REASON = "a decimal cast on an x86 host whose 64-bit species have "
      + "two lanes or fewer: C2 lowers no mask or two-vector rearrange there, so the decimal "
      + "load, the division's sign and the guards would run the Vector API's Java fallback";

  private VarkaDecimalCastCompiler() {
  }

  // ---------------------------------------------------------------------------------------
  // The family's claims
  // ---------------------------------------------------------------------------------------

  /**
   * The value arm: a decimal cast to an integral type, or {@code null}. A cast into a decimal
   * is claimed too, so that the family's switch holds it wherever it sits, but its value is
   * read only by the decimal compiler's readers - a comparison, an {@code IN}, a choice - and as
   * an output it declines naming row 157's store; coercion's widening rescale, which this family
   * does not hold, is not claimed.
   */
  static @Nullable VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (!(e instanceof Cast c)) {
      return null;
    }
    if (c.child().dataType() instanceof DecimalType from) {
      Optional<LaneType> target = integralLane(c.dataType());
      if (target.isPresent()) {
        return () -> toIntegral(c, from, target.get(), inputs, literals, sink);
      }
    }
    if (claimsIntoDecimal(c)) {
      return () -> sink.decline("a decimal cast's value as an output: the decimal store writes "
          + "it once row 348b admits decimal values (VARKA-157)", c);
    }
    return null;
  }

  /** The condition arm: a decimal cast to {@code BOOLEAN}, or {@code null}. */
  static @Nullable Supplier<Optional<Cond>> condArm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (e instanceof Cast c && c.child().dataType() instanceof DecimalType
        && DataTypes.BooleanType.equals(c.dataType())) {
      return () -> test(c, inputs, literals, sink);
    }
    return null;
  }

  /**
   * Whether {@code c}, a cast into a decimal, is this family's: from a decimal when it rounds or
   * can overflow ({@code VarkaDecimalCompiler.keepsDigits} fails).
   */
  static boolean claimsIntoDecimal(Cast c) {
    return c.dataType() instanceof DecimalType to && c.child().dataType() instanceof DecimalType f
        && !VarkaDecimalCompiler.keepsDigits(f, to);
  }

  /** The lane of {@code TINYINT}, {@code SMALLINT}, {@code INT} or {@code BIGINT}. */
  private static Optional<LaneType> integralLane(DataType type) {
    if (DataTypes.ByteType.equals(type) || DataTypes.ShortType.equals(type)
        || DataTypes.IntegerType.equals(type) || DataTypes.LongType.equals(type)) {
      return VarkaTypes.lane(type);
    }
    return Optional.empty();
  }

  // ---------------------------------------------------------------------------------------
  // From a decimal
  // ---------------------------------------------------------------------------------------

  /** {@code c}'s decimal operand, compiled from outside the decimal compiler. */
  private static Optional<Scaled> operand(Cast c, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    sink.enterDecimal();
    try {
      return VarkaDecimalCompiler.value(c.child(), inputs, literals, sink);
    } finally {
      sink.exitDecimal();
    }
  }

  /**
   * {@code CAST(x AS target)} for a decimal {@code x} of {@code from}: the truncated quotient,
   * narrowed for a narrow target, under ANSI and TRY behind the target's range.
   */
  private static Optional<VarkaVectorIR> toIntegral(Cast c, DecimalType from, LaneType target,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    if (from.precision() > MAX_PRECISION) {
      return sink.decline("a cast of a decimal wider than " + MAX_PRECISION + " digits to an "
          + "integral type is row 364's", c);
    }
    if (sink.declinesTwoLaneChecks()) {
      return sink.decline(TWO_LANE_REASON, c);
    }
    Overflow mode = VarkaNodeCompiler.overflowOf(c.evalMode());
    return operand(c, inputs, literals, sink).map(x -> {
      VarkaVectorIR t = truncated(x.unscaled(), x.type().precision(), x.type().scale(), sink);
      if (target == LaneType.LONG) {
        return t;
      }
      VarkaTypes.IntegralRange range = VarkaTypes.integralRange(target);
      VarkaVectorIR guarded = mode == Overflow.WRAP || fits(x.type(), target)
          ? t : new GuardedRange(t, range.min(), range.max(), mode);
      return new NarrowLane(guarded, target);
    });
  }

  /** Whether every truncated value of {@code from} fits the integral {@code target}'s range. */
  static boolean fits(DecimalType from, LaneType target) {
    return Decimal.POW_10()[from.precision() - from.scale()] - 1L
        <= VarkaTypes.integralRange(target).max();
  }

  /** {@code NOT(u = 0)}, Spark's {@code !isZero}. */
  private static Optional<Cond> test(Cast c, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    if (c.child().dataType() instanceof DecimalType from && from.precision() > MAX_PRECISION) {
      return sink.decline("a cast of a decimal wider than " + MAX_PRECISION + " digits to "
          + "BOOLEAN is row 364's", c);
    }
    if (sink.declinesTwoLaneChecks()) {
      return sink.decline(TWO_LANE_REASON, c);
    }
    return operand(c, inputs, literals, sink).map(x ->
        new Not(new Compare(CompareOp.EQ, x.unscaled(), sink.longSlot(0L))));
  }

  // ---------------------------------------------------------------------------------------
  // Into a decimal
  // ---------------------------------------------------------------------------------------

  /**
   * The value of {@code c}, a cast into {@code to} that {@link #claimsIntoDecimal} holds, or
   * empty with the reason noted. The decimal compiler calls it under the family's switch.
   */
  static Optional<Scaled> intoDecimal(Cast c, DecimalType to, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    if (sink.declinesTwoLaneChecks()) {
      return sink.decline(TWO_LANE_REASON, c);
    }
    // LEGACY's null on overflow is TRY's: both are changePrecision with nullOnOverflow.
    Overflow mode = VarkaNodeCompiler.overflowOf(c.evalMode()) == Overflow.FAIL
        ? Overflow.FAIL : Overflow.NULL;
    return VarkaDecimalCompiler.value(c.child(), inputs, literals, sink)
        .map(x -> rescaled(x, to, mode, sink));
  }

  /**
   * {@code x} at {@code to}'s scale and precision: the rescale up behind its input guard, or the
   * rounding down then the precision check, or the precision check alone.
   */
  private static Scaled rescaled(Scaled x, DecimalType to, Overflow mode, DeclineSink sink) {
    int p = x.type().precision();
    int k = to.scale() - x.type().scale();
    VarkaVectorIR u = x.unscaled();
    boolean guarded = canOverflow(x.type(), to);
    if (k > 0) {
      // Guard-the-inputs: |u| <= 10^(P - k) - 1 exactly when |u * 10^k| <= 10^P - 1.
      VarkaVectorIR in = guarded ? guardDigits(u, to.precision() - k, mode) : u;
      return new Scaled(timesPowerOfTen(in, k, sink), to);
    }
    if (k < 0) {
      // |q| <= 10^(p + k), which fits P digits when p + k < P.
      VarkaVectorIR q = roundedHalfUp(u, p, -k, sink);
      return new Scaled(guarded ? guardDigits(q, to.precision(), mode) : q, to);
    }
    return new Scaled(guarded ? guardDigits(u, to.precision(), mode) : u, to);
  }

  /** {@code v} behind {@code |v| <= 10^digits - 1}, in {@code mode}. */
  private static VarkaVectorIR guardDigits(VarkaVectorIR v, int digits, Overflow mode) {
    long bound = Decimal.POW_10()[digits] - 1L;
    return new GuardedRange(v, -bound, bound, mode);
  }

  /** {@code v * 10^k}, the identity at {@code k = 0}; the caller has bounded the product. */
  private static VarkaVectorIR timesPowerOfTen(VarkaVectorIR v, int k, DeclineSink sink) {
    if (k == 0) {
      return v;
    }
    return new IntArith(IntOp.MUL, Overflow.WRAP, v, sink.longSlot(Decimal.POW_10()[k]));
  }

  /**
   * Whether {@code c}, a cast between two decimals, has no guard: coercion's widening rescale, or
   * a cast that {@link #claimsIntoDecimal} holds whose source's widest value fits the target, so
   * that it can neither null nor fail and is valid where its operand is.
   */
  static boolean unguardedIntoDecimal(Cast c) {
    return c.dataType() instanceof DecimalType to && c.child().dataType() instanceof DecimalType f
        && (VarkaDecimalCompiler.keepsDigits(f, to) || !canOverflow(f, to));
  }

  /**
   * Whether a cast from {@code from} into {@code to} can overflow, so that it is built behind a
   * guard: a rescale up whose integral digits shrink, a rescale down that leaves no more integral
   * digits than the source had (its rounding can carry into one more), or a precision cut at one
   * scale.
   */
  static boolean canOverflow(DecimalType from, DecimalType to) {
    int p = from.precision();
    int k = to.scale() - from.scale();
    if (k > 0) {
      return p + k > to.precision();
    }
    return k < 0 ? p + k >= to.precision() : p > to.precision();
  }

  // ---------------------------------------------------------------------------------------
  // The two divisions by a power of ten
  // ---------------------------------------------------------------------------------------

  /**
   * {@code trunc(u / 10^k)} for {@code |u| <= 10^p - 1}, Java's {@code /}. Below {@code 2^52}
   * that is one {@link ConstDivide}, whose truncation is Java's. Above it, the magnitude is
   * divided by {@code 2^k} first, a logical shift, and then by {@code 5^k}, since
   * {@code floor(floor(m / 2^k) / 5^k) = floor(m / 10^k)} for {@code m >= 0}, and the sign is
   * put back; the shift is logical over the magnitude because an arithmetic shift of a negative
   * {@code u} floors ({@code -19} at {@code k = 1} would answer -2, not -1).
   */
  static VarkaVectorIR truncated(VarkaVectorIR u, int p, int k, DeclineSink sink) {
    if (k == 0) {
      return u;
    }
    long most = Decimal.POW_10()[p] - 1L;
    if (most < DIVIDEND_BOUND) {
      return new ConstDivide(u, Decimal.POW_10()[k], most + 1);
    }
    VarkaVectorIR q = magnitudeQuotient(new IntNeg(Overflow.WRAP, u, true), most, k, sink);
    return withSign(u, q, sink);
  }

  /**
   * {@code u / 10^k} rounded half away from zero, Spark's {@code ROUND_HALF_UP} on the dropped
   * digits ({@code Decimal.changePrecision}), for {@code |u| <= 10^p - 1} and {@code k >= 1}:
   * the half {@code h = 10^k / 2} added to the magnitude, then truncated. Below {@code 2^52} the
   * half is added with the value's sign (DuckDB's signed half) and one {@link ConstDivide}
   * truncates; above it the magnitude takes {@link #truncated}'s shift and the sign is put back.
   * {@code |u| + h} is below {@code 10^18 + 10^18 / 2 < 2^61}, so no addition wraps.
   */
  static VarkaVectorIR roundedHalfUp(VarkaVectorIR u, int p, int k, DeclineSink sink) {
    long half = Decimal.POW_10()[k] / 2;
    long most = Decimal.POW_10()[p] - 1L + half;
    if (most < DIVIDEND_BOUND) {
      Cond negative = new Compare(CompareOp.LT, u, sink.longSlot(0L));
      VarkaVectorIR halved = new IfElse(negative,
          new IntArith(IntOp.SUB, Overflow.WRAP, u, sink.longSlot(half)),
          new IntArith(IntOp.ADD, Overflow.WRAP, u, sink.longSlot(half)));
      return new ConstDivide(halved, Decimal.POW_10()[k], most + 1);
    }
    VarkaVectorIR m = new IntArith(IntOp.ADD, Overflow.WRAP, new IntNeg(Overflow.WRAP, u, true),
        sink.longSlot(half));
    return withSign(u, magnitudeQuotient(m, most, k, sink), sink);
  }

  /**
   * {@code floor(m / 10^k)} for {@code 0 <= m <= most}: {@code (m >>> k) / 5^k}. Where
   * {@code most >> k} still reaches {@code 2^52} - the twelve {@code (p, k)} of `VARKA-350.md`
   * item 0.15, precision 16 to 18 at the small {@code k} - the magnitude is first guarded below
   * {@code 2^(52 + k)}, failing the batch past it, which the row engine then computes.
   */
  private static VarkaVectorIR magnitudeQuotient(VarkaVectorIR m, long most, int k,
      DeclineSink sink) {
    long shiftedMost = most >>> k;
    VarkaVectorIR guarded = m;
    long bound = shiftedMost + 1;
    if (shiftedMost >= DIVIDEND_BOUND) {
      guarded = new GuardedRange(m, 0, (DIVIDEND_BOUND << k) - 1, Overflow.FAIL);
      bound = DIVIDEND_BOUND;
    }
    VarkaVectorIR shifted = new BitOp(BitOpKind.USHR, guarded, sink.longSlot(k));
    return new ConstDivide(shifted, fivePow(k), bound);
  }

  /** {@code q} with {@code u}'s sign: {@code -q} where {@code u < 0}. */
  private static VarkaVectorIR withSign(VarkaVectorIR u, VarkaVectorIR q, DeclineSink sink) {
    return new IfElse(new Compare(CompareOp.LT, u, sink.longSlot(0L)),
        new IntNeg(Overflow.WRAP, q), q);
  }

  /** {@code 5^k}, exact for {@code k <= 18}. */
  private static long fivePow(int k) {
    long r = 1;
    for (int i = 0; i < k; i++) {
      r *= 5;
    }
    return r;
  }
}
