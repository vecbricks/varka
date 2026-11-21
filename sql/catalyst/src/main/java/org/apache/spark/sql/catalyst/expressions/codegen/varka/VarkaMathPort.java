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
 *
 * The exp expansion below follows fdlibm 5.3's e_exp.c, which carries this notice:
 *
 * ====================================================
 * Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 */
package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CrossLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ReinterpretLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * The {@code PORT} form's expansion frame (`VARKA-345.md` 2.2): an fdlibm 5.3 function written
 * as ordinary IR nodes over a {@code DOUBLE} operand, which the emitter lowers inline in the
 * kernel's method as it lowers any other node. Nothing here runs per batch: each method builds
 * nodes once, when a shape compiles, and the kernel holds no call into a library.
 *
 * <p>The helpers are fdlibm's word access on the long view of a lane ({@link ReinterpretLane}):
 * {@code __HI(x)} and {@code __LO(x)} as a shift and a mask, {@code __HI(x) = h} and
 * {@code __LO(x) = l} as a mask and an or, a double from two words, and fdlibm's compares of
 * {@code |x|}'s high word against a constant as one signed compare of {@code |x|}'s bits. They are
 * the lane helpers of the vector fdlibm library, {@code vfdlibm}'s {@code Lanes}, node for node,
 * and its {@code PROOFS.md} lemma L3 is the proof of the compares.
 *
 * <p>A port's constants are literals of the kernel's 64-bit table, interned through
 * {@link Constants}: a double as its raw bits on the {@code DOUBLE} lane, as the compiler interns
 * a double literal, and a long on the {@code LONG} lane, so a port drives its kernel at the long
 * lane ({@code VarkaKernelPlan.emissionLane}).
 *
 * <p>{@link #exp} is the template: the sequence {@code vfdlibm} proves for {@code StrictMath.exp},
 * its arm with every branch blended ({@code Exp.expBlended}), so no lane is finished by a call.
 */
public final class VarkaMathPort {

  /**
   * Where a port's constants go: the slot of {@code bits} on {@code lane}, one of the 64-bit
   * lanes. The compiler passes its decline sink's long table ({@code DeclineSink.longSlot}); a
   * kernel built outside the compiler passes a {@link Table}.
   */
  @FunctionalInterface
  public interface Constants {
    LiteralSlot slot(long bits, LaneType lane);
  }

  /** A literal table of its own, for a kernel a test or a benchmark emits from a port. */
  public static final class Table implements Constants {
    private final Map<Long, Integer> index = new HashMap<>();
    private final List<Long> values = new ArrayList<>();

    @Override
    public LiteralSlot slot(long bits, LaneType lane) {
      Integer slot = index.get(bits);
      if (slot == null) {
        slot = values.size();
        index.put(bits, slot);
        values.add(bits);
      }
      return new LiteralSlot(slot, lane);
    }

    /** The table's values in slot order: the kernel's long literals. */
    public long[] values() {
      return values.stream().mapToLong(Long::longValue).toArray();
    }
  }

  /**
   * A fault an expansion can be built with, for the cross-checks to catch: {@code vfdlibm}'s own
   * planted faults ({@code Exp.DROP_CORRECTION}, {@code CONSTANT_OFF_BY_ONE_ULP},
   * {@code THRESHOLD_MOVED}), here as arguments of a package-private overload, so the production
   * expansion has none.
   */
  enum Fault { NONE, DROP_CORRECTION, CONSTANT_OFF_BY_ONE_ULP, THRESHOLD_MOVED }

  /** fdlibm's {@code __LO} word of a long lane, its low 32 bits. */
  static final long LOW_WORD = 0xffff_ffffL;

  private final Constants constants;

  public VarkaMathPort(Constants constants) {
    this.constants = Objects.requireNonNull(constants, "constants");
  }

  // -------------------------------------------------------------------------------------------
  // Leaves and lane arithmetic
  // -------------------------------------------------------------------------------------------

  /** The double {@code v} as a literal, by its raw bits. */
  VarkaVectorIR lit(double v) {
    return constants.slot(Double.doubleToRawLongBits(v), LaneType.DOUBLE);
  }

  /** The long {@code v} as a literal. */
  VarkaVectorIR lit(long v) {
    return constants.slot(v, LaneType.LONG);
  }

  /** {@code a + b}, IEEE's at a floating lane and wrapping at an integral one. */
  static VarkaVectorIR add(VarkaVectorIR a, VarkaVectorIR b) {
    return new IntArith(IntOp.ADD, Overflow.WRAP, a, b);
  }

  static VarkaVectorIR sub(VarkaVectorIR a, VarkaVectorIR b) {
    return new IntArith(IntOp.SUB, Overflow.WRAP, a, b);
  }

  static VarkaVectorIR mul(VarkaVectorIR a, VarkaVectorIR b) {
    return new IntArith(IntOp.MUL, Overflow.WRAP, a, b);
  }

  /** IEEE's {@code a / b} at the double lane; a zero divisor gives the IEEE result, not a null. */
  static VarkaVectorIR div(VarkaVectorIR a, VarkaVectorIR b) {
    return new IntArith(IntOp.DIV, Overflow.WRAP, a, b);
  }

  /**
   * {@code cond ? then : otherwise}, lane by lane, with {@code cond} read at the values' lane:
   * a long compare of a port's bits crosses to the double lane by {@link CrossLane}, which at one
   * width is the mask's cast. Where {@code cond} is unknown, over a null operand, the lane takes
   * {@code otherwise}, as {@link IfElse} does, and the lane's validity is that branch's. So a
   * port keeps a null operand null only where the {@code otherwise} chain of each of its blends
   * ends in a value computed from the operand, never in a literal alone: a port written on this
   * frame has to check that rule itself over null lanes, as the helpers' suite does for a view.
   */
  static VarkaVectorIR blend(Cond cond, VarkaVectorIR then, VarkaVectorIR otherwise) {
    return new IfElse(at(cond, then.laneType()), then, otherwise);
  }

  /** {@code cond} read at {@code lane}: itself on its own lane, crossed to another. */
  static Cond at(Cond cond, LaneType lane) {
    return cond.laneType() == lane ? cond : new CrossLane(cond, lane);
  }

  // -------------------------------------------------------------------------------------------
  // The word helpers (vfdlibm's Lanes)
  // -------------------------------------------------------------------------------------------

  /** The raw bits of each double lane, {@code Double.doubleToRawLongBits}. */
  static VarkaVectorIR bits(VarkaVectorIR x) {
    return new ReinterpretLane(x, LaneType.LONG);
  }

  /** The doubles whose raw bits are the long lanes {@code b}. */
  static VarkaVectorIR fromBits(VarkaVectorIR b) {
    return new ReinterpretLane(b, LaneType.DOUBLE);
  }

  /**
   * {@code __HI(x)}: each lane's high word, unsigned, its sign bit included, at the long lane.
   * fdlibm's is a signed {@code int}, so arithmetic or a shift transcribed from the C onto this
   * word differs for a negative {@code x}: take {@link #absBits} first, as the compares do.
   */
  VarkaVectorIR hi(VarkaVectorIR x) {
    return new BitOp(BitOpKind.USHR, bits(x), lit(32L));
  }

  /** {@code __LO(x)}: each lane's low word, unsigned, at the long lane. */
  VarkaVectorIR lo(VarkaVectorIR x) {
    return new BitOp(BitOpKind.AND, bits(x), lit(LOW_WORD));
  }

  /** {@code __HI(x) = h}: {@code x} with its high word replaced by {@code h}'s low 32 bits. */
  VarkaVectorIR withHi(VarkaVectorIR x, VarkaVectorIR h) {
    return fromBits(new BitOp(BitOpKind.OR, new BitOp(BitOpKind.AND, bits(x), lit(LOW_WORD)),
        new BitOp(BitOpKind.SHL, h, lit(32L))));
  }

  /** {@code __LO(x) = l}: {@code x} with its low word replaced by {@code l}'s low 32 bits. */
  VarkaVectorIR withLo(VarkaVectorIR x, VarkaVectorIR l) {
    return fromBits(new BitOp(BitOpKind.OR, new BitOp(BitOpKind.AND, bits(x), lit(~LOW_WORD)),
        new BitOp(BitOpKind.AND, l, lit(LOW_WORD))));
  }

  /** The doubles whose high and low words are the low 32 bits of {@code h} and {@code l}. */
  VarkaVectorIR fromHiLo(VarkaVectorIR h, VarkaVectorIR l) {
    return fromBits(new BitOp(BitOpKind.OR, new BitOp(BitOpKind.SHL, h, lit(32L)),
        new BitOp(BitOpKind.AND, l, lit(LOW_WORD))));
  }

  /** The bits of {@code |x|}: {@code bits} with the sign cleared, a NaN's included. */
  VarkaVectorIR absBits(VarkaVectorIR bits) {
    return new BitOp(BitOpKind.AND, bits, lit(Long.MAX_VALUE));
  }

  /** The bits of the double whose high word is {@code h} and whose low word is zero. */
  static long fromHiBits(int h) {
    return (h & LOW_WORD) << 32;
  }

  /**
   * fdlibm's {@code hx < h} on {@code |x|}'s bits, one signed 64-bit compare. {@code absBits} must
   * be non-negative, that is {@link #absBits} of the bits: a negative double's raw bits compare
   * below every threshold. The same holds for {@link #hiAtLeast} and {@link #hiAbove}.
   */
  Cond hiBelow(VarkaVectorIR absBits, int h) {
    requireHighWord(h);
    return new Compare(CompareOp.LT, absBits, lit(fromHiBits(h)));
  }

  /**
   * fdlibm's {@code hx >= h} on {@code |x|}'s bits, as {@code absBits > fromHiBits(h) - 1}: exact
   * for every lane, NaN lanes included, for {@code h} from 0 to {@code 0x7fffffff}
   * ({@code vfdlibm}'s {@code PROOFS.md}, L3).
   */
  Cond hiAtLeast(VarkaVectorIR absBits, int h) {
    requireHighWord(h);
    return new Compare(CompareOp.GT, absBits, lit(fromHiBits(h) - 1));
  }

  /** fdlibm's {@code hx > h}, as {@code hx >= h + 1}, for {@code h} below {@code 0x7fffffff}. */
  Cond hiAbove(VarkaVectorIR absBits, int h) {
    if (h == Integer.MAX_VALUE) {
      throw VarkaErrors.illegalArgument("no high word of |x| is above 0x7fffffff");
    }
    return hiAtLeast(absBits, h + 1);
  }

  /**
   * The compares read {@code |x|}'s high word, which is never negative; fdlibm's {@code hx} of
   * {@code |x|} compared with a negative constant is a compare this frame does not write.
   */
  private static void requireHighWord(int h) {
    if (h < 0) {
      throw VarkaErrors.illegalArgument("a high word of |x| is at least 0, not "
          + Integer.toHexString(h));
    }
  }

  /**
   * {@code __HI(y) += k << 20}: {@code k}, a long lane, added to each lane's binary exponent by
   * adding {@code k << 52} to {@code y}'s bits. The low word of {@code k << 52} is zero, so the
   * high word is the C's {@code int} sum modulo 2^32 and the low word is unchanged.
   */
  VarkaVectorIR addExponent(VarkaVectorIR y, VarkaVectorIR k) {
    return fromBits(add(bits(y), new BitOp(BitOpKind.SHL, k, lit(52L))));
  }

  // -------------------------------------------------------------------------------------------
  // exp, from e_exp.c
  // -------------------------------------------------------------------------------------------

  // The constants of e_exp.c as its source writes them; VarkaMathPortSuite checks their bits.
  static final double ONE = 1.0;
  static final double TWOM1000 = 9.33263618503218878990e-302; // 2**-1000=0x01700000,0
  static final double O_THRESHOLD = 7.09782712893383973096e+02; // 0x40862E42, 0xFEFA39EF
  static final double U_THRESHOLD = -7.45133219101941108420e+02; // 0xc0874910, 0xD52D3051
  static final double LN2HI_0 = 6.93147180369123816490e-01; // 0x3fe62e42, 0xfee00000
  static final double LN2LO_0 = 1.90821492927058770002e-10; // 0x3dea39ef, 0x35793c76
  static final double INVLN2 = 1.44269504088896338700e+00; // 0x3ff71547, 0x652b82fe
  static final double P1 = 1.66666666666666019037e-01; // 0x3FC55555, 0x5555553E
  static final double P2 = -2.77777777770155933842e-03; // 0xBF66C16C, 0x16BEBD93
  static final double P3 = 6.61375632143793436117e-05; // 0x3F11566A, 0xAF25DE2C
  static final double P4 = -1.65339022054652515390e-06; // 0xBEBBBD41, 0xC5D26BF1
  static final double P5 = 4.13813679705723846039e-08; // 0x3E663769, 0x72BEA4D0

  /** {@code |x| >= 709.78...}: the filter on non-finite, overflowing and underflowing lanes. */
  static final int HX_FILTER = 0x40862E42;
  /** {@code |x| > 0.5 ln2}: the lanes that are reduced. */
  static final int HX_HALF_LN2 = 0x3fd62e42;
  /** {@code |x| < 2**-28}: the lanes whose result is {@code one + x}. */
  static final int HX_TINY = 0x3e300000;
  /** {@code hx >= 0x7ff00000}: NaN or an infinity. */
  static final int HX_NON_FINITE = 0x7ff00000;
  /** The smallest k whose scale-back is a plain exponent add; below it the result is tiny. */
  static final long K_MIN_NORMAL = -1021;
  /** The raw bits of 0.5, {@code halF[0]}; {@code halF[1]} is the same with the sign bit set. */
  static final long HALF_BITS = 0x3fe00000_00000000L;

  /** {@code StrictMath.exp(x)} in every lane of the double node {@code x}, bit for bit. */
  public VarkaVectorIR exp(VarkaVectorIR x) {
    return exp(x, Fault.NONE);
  }

  /**
   * The expansion, node for node {@code vfdlibm}'s {@code Exp.expBlended}, with its line
   * references to e_exp.c and its lemmas in {@code vfdlibm}'s {@code PROOFS.md}: every lane takes
   * the far reduction and the general tail (L1, L2), the high-word tests are signed compares of
   * {@code |x|}'s bits (L3), {@code halF[xsb]} is 0.5 with {@code x}'s sign (L5), the tiny lanes
   * keep {@code one + x} (L4), and the filter and the subnormal scale-back are computed in every
   * lane and blended in the C's order of precedence, the last blend winning.
   *
   * <p>Two compares are the IEEE forms the IR has, with the same masks as the library's: the
   * library's {@code x > O_THRESHOLD} is {@code O_THRESHOLD < x}, and its {@code IS_NAN} test is
   * {@code !(x == x)}, both false or true at exactly the same lanes, NaN included. And {@code k}
   * passes through the int lane as the C's {@code int k} does, {@code D2I} then {@code I2D} and
   * {@code I2L}, which the library writes the same way for the same reason: x86 lowers
   * {@code D2L} only with AVX512DQ.
   *
   * <p>A null {@code x} answers null because the arm each blend chooses for it reads {@code x}:
   * a condition over {@code x} is unknown there, so the lane takes the {@code otherwise} arm,
   * which reads {@code x}; and the scale-back's condition reads {@code k}, which the reduction's
   * blend makes a valid 0 for a null lane, so it is known false and chooses
   * {@code addExponent(y, k)}, whose {@code y} reads {@code x}.
   */
  VarkaVectorIR exp(VarkaVectorIR x, Fault fault) {
    if (x.laneType() != LaneType.DOUBLE) {
      throw VarkaErrors.illegalArgument("exp takes a DOUBLE operand, not " + x.laneType());
    }
    double ln2hi = fault == Fault.CONSTANT_OFF_BY_ONE_ULP
        ? Math.nextUp(LN2HI_0) : LN2HI_0;
    long kMin = fault == Fault.THRESHOLD_MOVED ? K_MIN_NORMAL - 1 : K_MIN_NORMAL;
    VarkaVectorIR bits = bits(x);
    // Lines 112-114: xsb and hx, here the bits of |x| the long compares read (L3).
    VarkaVectorIR absBits = absBits(bits);
    // Lines 117, 118, 128 and 139: the filter, the reduced lanes, the tiny ones and the
    // non-finite ones; the near band, line 129, needs no test (L2).
    Cond filter = hiAtLeast(absBits, HX_FILTER);
    Cond reduced = hiAbove(absBits, HX_HALF_LN2);
    Cond isTiny = hiBelow(absBits, HX_TINY);
    Cond nonFinite = hiAtLeast(absBits, HX_NON_FINITE);
    // halF[xsb]: 0.5 with x's sign bit.
    VarkaVectorIR halF = fromBits(new BitOp(BitOpKind.OR,
        new BitOp(BitOpKind.AND, bits, lit(Long.MIN_VALUE)), lit(HALF_BITS)));

    // Lines 128-142 in the far form for every lane: k = (int)(invln2*x+halF[xsb]); t = k;
    // hi = x - t*ln2HI[0]; lo = t*ln2LO[0]. A lane that is not reduced gets k = 0 from the blend
    // before the cast, so t = +0, hi = x, lo = +0 (L1). D2I truncates toward zero as the C's
    // (int) does; a NaN or a huge |x| is a filter lane, whose k is never used.
    VarkaVectorIR kArg = blend(reduced, add(mul(x, lit(INVLN2)), halF), lit(0.0));
    VarkaVectorIR kInt = new ConvertLane(kArg, LaneType.INT);
    VarkaVectorIR t = new ConvertLane(kInt, LaneType.DOUBLE);
    VarkaVectorIR k = new WidenLane(kInt, LaneType.LONG);
    VarkaVectorIR hi = sub(x, mul(t, lit(ln2hi)));
    VarkaVectorIR lo = mul(t, lit(LN2LO_0));
    VarkaVectorIR r = sub(hi, lo);

    // Lines 145-146: t = x*x; c = x - t*(P1+t*(P2+t*(P3+t*(P4+t*P5)))).
    VarkaVectorIR tt = mul(r, r);
    VarkaVectorIR poly = add(mul(tt, add(mul(tt, add(mul(tt, add(mul(tt, lit(P5)), lit(P4))),
        lit(P3))), lit(P2))), lit(P1));
    VarkaVectorIR c = sub(r, mul(tt, poly));
    // Line 148, y = one-((lo-(x*c)/(2.0-c))-hi), for every lane (L1).
    VarkaVectorIR loTerm = fault == Fault.DROP_CORRECTION ? lit(0.0) : lo;
    VarkaVectorIR y = sub(lit(ONE),
        sub(sub(loTerm, div(mul(r, c), sub(lit(2.0), c))), hi));

    // Lines 149-155, the scale-back: k >= -1021 adds k to y's exponent; below, k + 1000, then
    // the product with twom1000. Lines 139-141: |x| < 2**-28 returns one+x (L4).
    VarkaVectorIR result = addExponent(y, k);
    VarkaVectorIR subnormal = mul(addExponent(y, add(k, lit(1000L))), lit(TWOM1000));
    result = blend(new Compare(CompareOp.LT, k, lit(kMin)), subnormal, result);
    result = blend(isTiny, add(lit(ONE), x), result);

    // The filter, lines 117-125, in the C's order of precedence: overflow to +inf (huge*huge),
    // underflow to +0 (twom1000*twom1000), and the non-finite lanes last: x+x for a NaN, x for
    // +inf and 0.0 for -inf.
    Cond filterAtDouble = at(filter, LaneType.DOUBLE);
    result = blend(new VarkaVectorIR.And(filterAtDouble,
        new Compare(CompareOp.IEEE_LT, lit(O_THRESHOLD), x)), lit(Double.POSITIVE_INFINITY),
        result);
    result = blend(new VarkaVectorIR.And(filterAtDouble,
        new Compare(CompareOp.IEEE_LT, x, lit(U_THRESHOLD))), lit(0.0), result);
    Cond isNaN = new Not(new Compare(CompareOp.IEEE_EQ, x, x));
    VarkaVectorIR special = blend(isNaN, add(x, x),
        blend(new Compare(CompareOp.LT, bits, lit(0L)), lit(0.0), x));
    return blend(nonFinite, special, result);
  }
}
