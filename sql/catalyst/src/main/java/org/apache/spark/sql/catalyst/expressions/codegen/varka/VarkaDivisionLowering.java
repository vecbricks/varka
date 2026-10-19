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

package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorWalk.*;

import java.lang.classfile.CodeBuilder;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChronoLowering.ChronoDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;

/**
 * The lowerings of division by a constant, and {@link Divider}, the record that says which of
 * them an emission takes.
 *
 * <p>Two callers. The calendar prefix divides by a handful of constants whose dividends it can
 * bound, so its sites have a range-narrowed magic form to fall back on and come here only for
 * the double forms ({@link #emitDoubleDivide}); {@code ConstDivide} divides a dividend the
 * calendar's ranges do not bound, so {@link #emitConstDivide} chooses among the forms that are
 * exact over what the node admits: the multiply-high through 64-bit lanes at the int lane, the
 * conversion through double lanes, or the magic-number floor at the long lane on a host whose
 * converts do not become instructions. At the int lane that is the whole lane; at the long lane
 * it is a dividend under {@code ConstDivide.EXACT_DIVIDEND_BOUND}, which the node states and its
 * caller proves ({@link ConstDivide}'s javadoc). Every form's quotient is exact over that
 * domain, which is why none of them is followed by a carry, a guard or an overflow mask.
 */
final class VarkaDivisionLowering {

  private VarkaDivisionLowering() {
  }

  /**
   * Which lowering the calendar prefix's constant divisions take, plus the one thing the double
   * forms need to emit one: the name of the species constant to convert through.
   *
   * <p>It is threaded to the prefix's helpers the way {@code emitChronoTrunc} takes its
   * {@code TruncDateForm} - the resolved option rather than the whole {@link Analysis}, because
   * most of those helpers need nothing else from it.
   *
   * <p>One name serves both vector types because the species constants are named by the vector's
   * total width rather than by its lane count: {@code IntVector.SPECIES_256} is eight int lanes
   * and {@code DoubleVector.SPECIES_256} is the four double lanes occupying the same register,
   * which is exactly the pairing a conversion between them needs.
   */
  record Divider(VarkaEmitOptions.Division form, String species) {

    /** No conversion is possible here at all: the magic form, and nothing else. */
    static final Divider MAGIC = new Divider(VarkaEmitOptions.Division.MAGIC, "");

    /**
     * What this emission divides with. A double form is answered only where the width has a
     * named double species to convert through; everywhere else the option is accepted and
     * ignored, so asking for one can change how fast a kernel is but never what it computes.
     */
    static Divider of(Analysis analysis) {
      VarkaEmitOptions.Division form = analysis.options.division();
      // Two int lanes is the narrowest width whose double half has a lane at all. The long
      // lane converts same-width - one 64-bit lane pairs with one double lane - so every width
      // that names a species serves it.
      if (analysis.lane == Lane.INT && analysis.lanes != 0 && analysis.lanes < 2) {
        return MAGIC;
      }
      return new Divider(form, analysis.lane.speciesField(analysis.lanes));
    }

    /**
     * Whether a division that has no magic form at all can be emitted here. The calendar's
     * divisions always have one to fall back on; {@link VarkaVectorIR.ConstDivide} does not, so
     * it asks this and the analysis refuses the shape rather than the emitter producing
     * nothing.
     */
    boolean canConvert() {
      return !species.isEmpty();
    }

    /**
     * What this site is actually divided with: the requested form, except that a site the
     * reciprocal is not exact over falls back to {@code MAGIC} rather than computing a wrong
     * quotient. {@code emitDivide} and {@link #carries} both ask this, so the carry correction
     * can never disagree with the division it corrects.
     */
    VarkaEmitOptions.Division formFor(ChronoDivide div) {
      if (form == VarkaEmitOptions.Division.DOUBLE_RECIP && !div.recipExact) {
        return VarkaEmitOptions.Division.MAGIC;
      }
      return form;
    }

    /**
     * Whether the round-down carry that follows this division is still live. A magic quotient can
     * be one short and needs it; a double one is exact over the site's range, so the correction
     * is dead code rather than a redundant safety net, and emitting it would price the double
     * forms with an operation they do not need.
     *
     * <p>Only a site {@link ChronoDivide}'s table marks {@code carried} may ask: the proof of each
     * site's magic form (VARKA-242) is rendered from that table, so a carry emitted at a site the
     * table says has none would be a form the proof never stated.
     */
    boolean carries(ChronoDivide div) {
      if (!div.carried) {
        throw VarkaErrors.illegalState(div + " carries its quotient, but ChronoDivide's table "
            + "says it does not, so chrono_divide.smt2 proves a different form");
      }
      return formFor(div) == VarkaEmitOptions.Division.MAGIC;
    }
  }

  /**
   * {@code child / divisor}, the division over a dividend the calendar's ranges do not bound.
   *
   * <p>{@link VarkaVectorIR.ConstDivide} exists for dividends the emitter cannot bound, so the
   * calendar's range-narrowed magic is not an option however {@link VarkaEmitOptions#division()}
   * is set. At the int lane it takes the multiply-high through 64-bit lanes
   * ({@link #emitMulHiDivide}, VARKA-149), which is exact over the whole lane, or - with
   * {@link VarkaEmitOptions#mulHiDivide()} off, the reference arm - the conversion through
   * double lanes. At the long lane it is the conversion form, or the magic-number
   * form on a host whose converts do not intrinsify. The conversion is always the true divide:
   * the reciprocal is exact only for divisors a closed form admits, and choosing between them
   * per divisor is a performance question this node does not need to answer to be correct.
   *
   * <p>No carry, no guard and no overflow mask: every form's quotient is exact over what the node
   * admits - the whole int lane, and at the long lane a dividend under
   * {@link ConstDivide#EXACT_DIVIDEND_BOUND}, the bound the node states and its caller proves -
   * and dividing by a non-zero constant other than -1 cannot overflow.
   */
  static void emitConstDivide(CodeBuilder cb, ConstDivide n, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    emitValue(cb, n.child(), dense, analysis, s, computed);
    line(cb, analysis, n);
    if (n.divisor() == 1) {
      // The identity, which the compiler does not have to have folded for this to be correct.
      return;
    }
    if (takesMagicDivide(analysis, n)) {
      analysis.reach(VarkaEmitMechanism.DIVIDE_MAGIC_LONG);
      emitMagicDivide(cb, n, s);
      return;
    }
    if (takesMulHiDivide(analysis, n)) {
      analysis.reach(VarkaEmitMechanism.DIVIDE_MULTIPLY_HIGH);
      emitMulHiDivide(cb, analysis, n, s);
      return;
    }
    analysis.reach(VarkaEmitMechanism.DIVIDE_CONVERTED);
    emitDoubleDivide(cb, analysis.laneOf(n),
        new Divider(VarkaEmitOptions.Division.DOUBLE_DIV, analysis.divider.species()),
        n.divisor());
  }

  /**
   * Whether an int-lane constant division takes the multiply-high form (VARKA-149): the lane
   * is the int one, the divisor is not the identity, the option is on, and the width names a
   * species to widen into - the same condition under which the conversion form can convert,
   * since both widen each int half into a lane of twice the width.
   */
  static boolean takesMulHiDivide(Analysis analysis, ConstDivide n) {
    return analysis.laneOf(n) == Lane.INT && n.divisor() != 1
        && analysis.options.mulHiDivide() && analysis.divider.canConvert();
  }

  /**
   * The constants of a multiply-high division by the magnitude {@code divisor}: the unsigned
   * {@code multiplier} and the {@code shift}, which is {@code 32 + s}. One that exists meets
   * Granlund and Montgomery's Theorem 5.1 and fits the lanes
   * ({@link VarkaDivisionLowering#signedMagic}), so a pair that would be merely nearly exact
   * cannot reach the emitter.
   */
  record MulHiMagic(long divisor, long multiplier, int shift) {
    MulHiMagic {
      // The ranges first, so that the theorem's product below is exact in a long.
      if (divisor < 2 || divisor > 1L << 31 || multiplier < 1 || multiplier >= 1L << 32
          || shift < 32 || shift > 62 || !meetsTheorem51(divisor, multiplier, shift)) {
        throw VarkaErrors.illegalArgument("(" + multiplier + ", " + shift + ") is not a "
            + "multiply-high pair for " + divisor + ": it fails Theorem 5.1's inequality or "
            + "does not fit the lanes");
      }
    }
  }

  /**
   * The multiplier and shift of a signed 32-bit division by a constant: for a divisor of
   * magnitude {@code 2 <= d <= 2^31}, the pair {@code (Mu, 32 + s)} such that
   * {@code (n * Mu) >> (32 + s)}, plus one where {@code n} is negative, is {@code n / d} for
   * every int32 {@code n}. The caller adds the dividend's sign bit, and negates for a negative
   * divisor.
   *
   * <p>Exactness is Granlund and Montgomery's Theorem 5.1 ("Division by Invariant Integers
   * using Multiplication", PLDI 1994, p. 5), with {@code N = 32} and {@code l = s + 1}: if
   * {@code 0 < Mu * d - 2^(32 + s) <= 2^(s + 1)}, then {@code floor(n * Mu / 2^(32 + s))}, plus
   * one for a negative {@code n}, is {@code n / d} truncated, for every int32 {@code n}. The
   * {@link MulHiMagic} returned refuses a pair that misses the inequality, so every divisor this
   * derives for is one the theorem covers. Beside the theorem,
   * {@code sql/varka/proofs/int_mulhi_divide.smt2} proves the lanes' own arithmetic exact for
   * the divisors the emitter and the fuzz grammar use, and {@code VarkaEmitterDivisionSuite}'s
   * opt-in sweep runs it over every dividend for them.
   *
   * <p>The pair starts as Hacker's Delight's (10-6, {@code magic}): the smallest shift the
   * book's own condition admits, which is what C2 emits for a scalar {@code n / 12}. That
   * condition bounds the dividend by the largest one congruent to {@code d - 1}, where the
   * theorem bounds it by 2^31, so it admits more: for 327,741,950 divisors, the first 196611,
   * the book's smallest shift misses the theorem's inequality while still being exact by the
   * book's argument. There the shift is raised until the inequality holds, which it does by
   * the paper's own choice {@code l = ceil(log2 d)} at the latest. So the pair is the book's
   * below 196611 and may not be above it (VARKA-240.md 2.3).
   *
   * <p>Returned as the form the lanes compute rather than the form the book states. The book's
   * multiplier is a 32-bit pattern, and adds the dividend where that pattern is negative as an
   * int; with a 64-bit product the multiplier is simply taken unsigned, and
   * {@code (n * Mu) >> (32 + s)} is the quotient before its sign correction in one multiply and
   * one shift. That needs {@code Mu < 2^32}, so that with {@code |n| <= 2^31} the product fits a
   * long, and a shift of at most 62, inside the count a long shift keeps; the record checks both.
   */
  static MulHiMagic signedMagic(long d) {
    if (d < 2 || d > 1L << 31) {
      throw VarkaErrors.illegalArgument(
          "the signed magic is derived for divisor magnitudes from 2 to 2^31, not " + d);
    }
    long two31 = 1L << 31;
    long anc = two31 - 1 - two31 % d;
    int p = 31;
    long q1 = two31 / anc;
    long r1 = two31 - q1 * anc;
    long q2 = two31 / d;
    long r2 = two31 - q2 * d;
    long delta;
    do {
      p++;
      q1 *= 2;
      r1 *= 2;
      if (r1 >= anc) {
        q1++;
        r1 -= anc;
      }
      q2 *= 2;
      r2 *= 2;
      if (r2 >= d) {
        q2++;
        r2 -= d;
      }
      delta = d - r2;
    } while (q1 < delta || (q1 == delta && r1 == 0));
    // q2 + 1 is the book's M taken unsigned: in 64-bit arithmetic it already is the multiplier
    // the 32-bit pattern stands for, the "+ n" case folded in.
    long mu = q2 + 1;
    while (!meetsTheorem51(d, mu, p) && p < 62) {
      p++;
      mu = (1L << p) / d + 1;
    }
    // The record refuses a pair that misses the theorem or the lanes. Neither happens for a
    // magnitude in range - VARKA-240's census derives every one of them - but this is where the
    // pair is made, so this is where it is held to them.
    return new MulHiMagic(d, mu, p);
  }

  /**
   * Granlund and Montgomery's Theorem 5.1 hypothesis for a 32-bit division by the magnitude
   * {@code d}, with multiplier {@code mu} and shift {@code p = 32 + s}:
   * {@code 0 < mu * d - 2^p <= 2^(p - 31)}. Exact in a long for the pairs it is asked about:
   * {@code mu < 2^32} and {@code d <= 2^31}, and {@code p} is at most 62.
   */
  private static boolean meetsTheorem51(long d, long mu, int p) {
    long excess = mu * d - (1L << p);
    return excess > 0 && excess <= 1L << (p - 31);
  }

  /**
   * {@link #signedMagic}, for the suites that pin the constants and sweep the form, and for the
   * renderer of its proof.
   */
  static MulHiMagic signedMagicForTest(long d) {
    return signedMagic(d);
  }

  /**
   * {@code [v] -> [v / d]} at the int lane by a multiply-high through 64-bit lanes (VARKA-149),
   * the lowering a scalar compiler gives {@code n / 12} and the one the conversion form's
   * divider-bound rate asked for (`VARKA-149.md` 3).
   *
   * <p>Each int half widens with {@code I2L} into a long vector of half the lanes - the same
   * two-part split the conversion form makes into doubles - multiplies by the unsigned magic,
   * shifts the product right arithmetically by {@code 32 + s}, and narrows back with
   * {@code L2I} into the disjoint lanes the other half left at zero; {@code or} rejoins them.
   * That is the truncated quotient for a non-negative dividend and one below it for a
   * negative one, so the dividend's sign bit is added, which is the book's {@code q + (n >>>
   * 31)}. A negative divisor divides by its magnitude and negates, which is exact for every
   * divisor the analysis admits (it refuses -1, the one case where the negation could
   * overflow); {@code Integer.MIN_VALUE} divides by the magnitude 2^31, which
   * {@link #signedMagic} takes as a long.
   * Eleven lane operations at most, and no divide: two widenings, two multiplies, two shifts,
   * two narrowings, an or, a shift and an add, plus a multiply for a negative divisor.
   */
  private static void emitMulHiDivide(CodeBuilder cb, Analysis analysis, ConstDivide n,
      Slots s) {
    int dividend = s.constDivideTmp.get(n)[0];
    MulHiMagic magic = signedMagic(Math.abs(n.divisor()));
    String species = analysis.divider.species();
    cb.astore(dividend);                                        // []
    for (int half = 0; half < 2; half++) {
      cb.aload(dividend);                                       // [.., v]
      cb.getstatic(VECTOR_OPERATORS, "I2L", VO_CONVERSION);
      cb.getstatic(LONG_VECTOR, species, VECTOR_SPECIES);
      cb.loadConstant(half);
      cb.invokevirtual(VECTOR, "convertShape", CONVERT_SHAPE);
      cb.checkcast(LONG_VECTOR);                                // [.., (long) half]
      cb.loadConstant(magic.multiplier());
      cb.invokevirtual(LONG_VECTOR, "mul", Lane.LONG.lanewiseVI);   // [.., half * Mu]
      cb.getstatic(VECTOR_OPERATORS, "ASHR", VO_BINARY);
      // The long lane's shift count is a long, as every scalar convenience of that lane is.
      cb.loadConstant((long) magic.shift());
      cb.invokevirtual(LONG_VECTOR, "lanewise", Lane.LONG.lanewiseBinaryI); // [.., q']
      cb.getstatic(VECTOR_OPERATORS, "L2I", VO_CONVERSION);
      cb.getstatic(INT_VECTOR, species, VECTOR_SPECIES);
      cb.loadConstant(-half);
      cb.invokevirtual(VECTOR, "convertShape", CONVERT_SHAPE);
      cb.checkcast(INT_VECTOR);                                 // [.., q' in this half's lanes]
    }
    cb.invokevirtual(INT_VECTOR, "or", Lane.INT.lanewiseVV);    // [q' over all lanes]
    cb.aload(dividend);
    emitShift(cb, "LSHR", 31);                                  // [q', sign bit]
    cb.invokevirtual(INT_VECTOR, "add", Lane.INT.lanewiseVV);   // [q]
    if (n.divisor() < 0) {
      cb.loadConstant(-1);
      cb.invokevirtual(INT_VECTOR, "mul", Lane.INT.lanewiseVI); // [-q]
    }
  }

  /**
   * Whether this division takes the magic-number form instead of the conversion one.
   *
   * <p>Only at the long lane, and only where the JVM says its converts will not become
   * instructions. {@code L2D} and {@code D2L} do not intrinsify under {@code -XX:UseAVX=2}:
   * {@code dev/varka_canary/L2DProbe.java} reads 22 refused conversions there against none at
   * the default level, which is a fact about lowering and not about speed. Which of the two
   * forms is faster on such a host is VARKA-88 step 4's A/B and is not yet measured; this
   * predicate is written on the reading that a conversion falling back to Java puts scalar
   * code inside a vector loop, and step 4 is what confirms or overturns it.
   *
   * <p>A level the JVM did not report ({@link VarkaEmitOptions#USE_AVX_UNKNOWN}) keeps the
   * conversions: an aarch64 machine has no {@code UseAVX} flag and no evidence against its
   * converts, so assuming the worst there would slow it down on a guess.
   */
  static boolean takesMagicDivide(Analysis analysis, ConstDivide n) {
    return analysis.laneOf(n) == Lane.LONG && n.divisor() != 1
        && analysis.options.convertsFallBack();
  }

  /**
   * {@code 2^52} as a double, and its bit pattern: the constants the magic form is built on. The
   * two bit patterns are package-private for {@code VarkaProofFiles}, which renders them into the
   * form's proof (VARKA-241).
   */
  private static final double TWO_52 = 4503599627370496.0;
  static final long TWO_52_BITS = 0x4330000000000000L;
  static final long MANTISSA_52 = 0x000FFFFFFFFFFFFFL;

  /**
   * {@code [v] -> [v / d]} at the long lane with no conversion instruction at all.
   *
   * <p>The identity and the hand-built floor come from
   * {@code dev/varka_canary/MagicProbe.java}, and what is emitted here is <b>not</b> what that
   * probe measured: the probe multiplies by a precomputed reciprocal where this divides, and it
   * has neither the magnitude step nor the sign tail, because its stated domain is a
   * non-negative dividend. Its census - 6 vmulpd and no conversion, extraction or call - is
   * therefore evidence that the conversion-free identity vectorises, and not a measurement of
   * this sequence. Step 4's A/B is what measures this one.
   *
   * <p><b>The identity.</b> For {@code 0 <= u < 2^52}, {@code u | 0x4330000000000000} read as a
   * double is exactly {@code 2^52 + u}, so a subtraction recovers {@code u} as a double; and an
   * integer-valued double below {@code 2^52} plus {@code 2^52} carries that integer in its low
   * mantissa bits, so a mask recovers it. Neither direction is a conversion.
   *
   * <p><b>Why a floor has to be built.</b> The Vector API has no lanewise floor, so the
   * quotient is rounded to nearest by the same {@code 2^52} trick and stepped down in the lanes
   * where rounding went up - a compare and a masked subtract. The rounding is exact because the
   * quotient is below {@code 2^51}: the dividend is below {@code 2^52} by contract and the
   * divisor is at least 2 in magnitude, a division by one having returned already and by minus
   * one being refused.
   *
   * <p><b>Signs, which the probe's domain does not cover.</b> The identity needs a non-negative
   * operand - the OR corrupts the sign and exponent of a negative long outright - and it
   * produces a floor, which differs from Java's truncation on every negative non-multiple. So
   * the magnitude is divided and the sign applied afterwards: {@code trunc} is odd, so
   * {@code trunc(v/d) = sign(v)*sign(d)*floor(|v|/|d|)}. The divisor's sign is known at
   * emission and folds into which comparison selects the lanes to negate, so it costs nothing.
   * This is what lets the form serve a signed dividend rather than only {@code TIME}, and it is
   * the choice {@code VARKA-88.md} 3.1 left open between sign correction and declining.
   */
  private static void emitMagicDivide(CodeBuilder cb, ConstDivide n, Slots s) {
    int[] t = s.constDivideTmp.get(n);
    int quotient = t[0];
    int rounded = t[1];
    int roundedUp = t[2];
    int negative = t[3];
    long magnitude = Math.abs(n.divisor());

    // The dividend's sign, taken once and used twice: to reach the identity's non-negative
    // domain here, and to put the sign back at the end. A masked NEG is what takes the
    // magnitude - `abs()` would read better and is the wrong instruction, because the 64-bit
    // vector absolute value is AVX-512 (`vpabsq`) and has no AVX2 encoding, so on the very
    // host this form exists for it deoptimises to a per-lane Java loop. NEG is `SubVL` against
    // zero under a mask, which every level has, and it agrees with `abs()` on every input
    // including `Long.MIN_VALUE`.
    cb.dup();                                                   // [v, v]
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    cb.loadConstant(0L);
    cb.invokevirtual(LONG_VECTOR, "compare", Lane.LONG.compareVI);
    cb.astore(negative);                                        // [v]
    cb.getstatic(VECTOR_OPERATORS, "NEG", VO_UNARY);
    cb.aload(negative);
    cb.invokevirtual(LONG_VECTOR, "lanewise", LANEWISE_UNARY_L_MASKED);   // [|v|]

    cb.loadConstant(TWO_52_BITS);
    cb.invokevirtual(LONG_VECTOR, "or", Lane.LONG.lanewiseVI);  // [|v| | 2^52 bits]
    cb.invokevirtual(VECTOR, "reinterpretAsDoubles", REINTERPRET_D);
    cb.loadConstant(TWO_52);
    cb.invokevirtual(DOUBLE_VECTOR, "sub", LANEWISE_VD);        // [(double) |v|]
    cb.loadConstant((double) magnitude);
    cb.invokevirtual(DOUBLE_VECTOR, "div", LANEWISE_VD);        // [q]

    // The floor needs the quotient, its rounding and the mask between them alive at once, in
    // an order the operand stack cannot hold: each call wants its receiver below its arguments
    // and the mask is produced from the two values it then has to follow.
    cb.astore(quotient);                                        // []
    cb.aload(quotient);
    cb.loadConstant(TWO_52);
    cb.invokevirtual(DOUBLE_VECTOR, "add", LANEWISE_VD);
    cb.loadConstant(TWO_52);
    cb.invokevirtual(DOUBLE_VECTOR, "sub", LANEWISE_VD);        // [round(q)]
    cb.astore(rounded);                                         // []
    cb.aload(rounded);
    cb.getstatic(VECTOR_OPERATORS, "GT", VO_COMPARISON);
    cb.aload(quotient);
    cb.invokevirtual(DOUBLE_VECTOR, "compare", COMPARE_DD);     // [round(q) > q]
    cb.astore(roundedUp);                                       // []
    cb.aload(rounded);
    cb.getstatic(VECTOR_OPERATORS, "SUB", VO_BINARY);
    cb.loadConstant(1.0);
    cb.aload(roundedUp);
    cb.invokevirtual(DOUBLE_VECTOR, "lanewise", LANEWISE_VD_MASKED);   // [floor(q)]

    cb.loadConstant(TWO_52);
    cb.invokevirtual(DOUBLE_VECTOR, "add", LANEWISE_VD);
    cb.invokevirtual(VECTOR, "reinterpretAsLongs", REINTERPRET_L);
    cb.loadConstant(MANTISSA_52);
    cb.invokevirtual(LONG_VECTOR, "and", Lane.LONG.lanewiseVI); // [floor(|v| / |d|)]

    // The sign, folded with the divisor's: negate where the dividend is negative, or where it
    // is not, when the divisor itself is. The divisor's sign is a constant here, so the
    // complement costs an emission-time branch rather than a lane operation.
    cb.getstatic(VECTOR_OPERATORS, "NEG", VO_UNARY);
    cb.aload(negative);
    if (n.divisor() < 0) {
      // VectorMask is an abstract class, not an interface, so this is invokevirtual.
      cb.invokevirtual(VECTOR_MASK, "not", MASK_NOT);
    }
    cb.invokevirtual(LONG_VECTOR, "lanewise", LANEWISE_UNARY_L_MASKED);  // [v / d]
  }

  /**
   * {@code [v] -> [v / d]} through the double lane, in whichever shape the lane calls for.
   *
   * <p>At the <b>int lane</b> the vector converts into two double vectors of half the lanes
   * each, divides there, and converts back. The two contracted halves are lane-disjoint - each
   * fills the lanes the other left at zero, which is what the {@code 0} and {@code -1} parts
   * mean - so a plain {@code or} rejoins them and no blend or mask is needed. Seven operations
   * in all, against the magic form's two.
   *
   * <p>At the <b>long lane</b> a 64-bit lane and a double lane are the same width, so there is
   * one conversion in, one divide and one conversion back: three operations, no halves and no
   * join. There is no 64-bit multiply-high, so the range-narrowed magic the calendar uses has
   * no form at this lane; the alternative here is {@link #emitMagicDivide}, which a host whose
   * conversions do not intrinsify takes instead.
   *
   * <p>The quotient this leaves is exact over the range the site's dividend stays in, so the
   * round-down carry that follows a magic division is dead here rather than merely redundant;
   * {@link Divider#carries(ChronoDivide)} is what each site asks before emitting it.
   */
  static void emitDoubleDivide(CodeBuilder cb, Lane lane, Divider divider,
      long divisor) {
    if (lane == Lane.LONG) {
      // Same width in and out: one 64-bit lane pairs with one double lane, so `part` is 0 both
      // ways, there is no second half and nothing to rejoin. Three operations against the int
      // lane's seven, which is why the long lane has no magic alternative to want.
      emitDoubleConvert(cb, lane, divider, divisor, 0, 0);
      return;
    }
    cb.dup();                                               // [v, v]
    emitDoubleConvert(cb, lane, divider, divisor, 0, 0);    // [v, lo]
    cb.swap();                                              // [lo, v]
    emitDoubleConvert(cb, lane, divider, divisor, 1, -1);   // [lo, hi]
    cb.invokevirtual(lane.vector, "or", lane.lanewiseVV);   // [lo | hi]
  }

  /**
   * One conversion round trip of {@link #emitDoubleDivide}:
   * {@code [v] -> [that part of v / d, zero elsewhere]}.
   *
   * <p>{@code in} selects which part of the source widens - expanding conversions take parts
   * {@code 0..M-1} - and {@code out} where the narrowed result lands, contracting conversions
   * taking parts {@code -M+1..0}. At the int lane, pairing {@code 0} with {@code 0} and
   * {@code 1} with {@code -1} is what makes the two results disjoint; at the long lane the
   * conversion is same-width and both parts are {@code 0}, so one call covers the vector.
   */
  private static void emitDoubleConvert(CodeBuilder cb, Lane lane, Divider divider,
      long divisor, int in, int out) {
    cb.getstatic(VECTOR_OPERATORS, lane == Lane.LONG ? "L2D" : "I2D", VO_CONVERSION);
    cb.getstatic(DOUBLE_VECTOR, divider.species(), VECTOR_SPECIES);
    cb.loadConstant(in);
    cb.invokevirtual(VECTOR, "convertShape", CONVERT_SHAPE);
    cb.checkcast(DOUBLE_VECTOR);
    if (divider.form() == VarkaEmitOptions.Division.DOUBLE_RECIP) {
      cb.loadConstant(1.0 / divisor);
      cb.invokevirtual(DOUBLE_VECTOR, "mul", LANEWISE_VD);
    } else {
      cb.loadConstant((double) divisor);
      cb.invokevirtual(DOUBLE_VECTOR, "div", LANEWISE_VD);
    }
    cb.getstatic(VECTOR_OPERATORS, lane == Lane.LONG ? "D2L" : "D2I", VO_CONVERSION);
    cb.getstatic(lane.vector, divider.species(), VECTOR_SPECIES);
    cb.loadConstant(out);
    cb.invokevirtual(VECTOR, "convertShape", CONVERT_SHAPE);
    cb.checkcast(lane.vector);
  }
}
