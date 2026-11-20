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

/*
 * The exp and expm1 expansions below follow fdlibm 5.3's e_exp.c and s_expm1.c, which carry
 * this notice:
 *
 * ====================================================
 * Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 */

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
 * {@link #expm1} follows it, on the same reduction, from {@code Expm1.expm1Blended}.
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
   * planted faults of each function ({@code Exp.DROP_CORRECTION}, {@code CONSTANT_OFF_BY_ONE_ULP},
   * {@code THRESHOLD_MOVED}, and {@code Expm1}'s of the same names), here as arguments of a
   * package-private overload, so the production expansion has none.
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
  // The reduction e_exp.c and s_expm1.c share
  // -------------------------------------------------------------------------------------------

  /**
   * {@code x = k*ln2 + r}, {@code r = hi - lo}: {@code k} at the long lane, {@code t} its double,
   * and {@code hi}, {@code lo} and {@code r} at the double lane.
   */
  record Reduction(VarkaVectorIR k, VarkaVectorIR t, VarkaVectorIR hi, VarkaVectorIR lo,
      VarkaVectorIR r) {}

  /**
   * The far form of the reduction for every lane, as e_exp.c's lines 128-142 and s_expm1.c's
   * lines 162-175 both write it: {@code k = (int)(invln2*x + (xsb==0 ? 0.5 : -0.5))},
   * {@code t = k}, {@code hi = x - t*ln2_hi}, {@code lo = t*ln2_lo}. A lane whose {@code |x|} is
   * not above {@code 0.5 ln2} gets {@code k = 0} from the blend before the cast, so
   * {@code t = +0}, {@code hi = x} and {@code lo = +0} ({@code vfdlibm}'s L1); the near band takes
   * the far form's values exactly (L2, L10); and {@code (xsb==0 ? 0.5 : -0.5)} is 0.5 with
   * {@code x}'s sign bit (L5). {@code D2I} truncates toward zero as the C's {@code (int)} does; a
   * NaN or a huge {@code |x|} is a lane the filter answers, whose {@code k} is never kept.
   * {@code k} passes through the int lane as the C's {@code int k} does, {@code D2I} then
   * {@code I2D} and {@code I2L}: x86 lowers {@code D2L} only with AVX512DQ.
   */
  Reduction reduce(VarkaVectorIR x, VarkaVectorIR bits, VarkaVectorIR absBits, double ln2hi) {
    Cond reduced = hiAbove(absBits, HX_HALF_LN2);
    VarkaVectorIR halF = fromBits(new BitOp(BitOpKind.OR,
        new BitOp(BitOpKind.AND, bits, lit(Long.MIN_VALUE)), lit(HALF_BITS)));
    VarkaVectorIR kArg = blend(reduced, add(mul(x, lit(INVLN2)), halF), lit(0.0));
    VarkaVectorIR kInt = new ConvertLane(kArg, LaneType.INT);
    VarkaVectorIR t = new ConvertLane(kInt, LaneType.DOUBLE);
    VarkaVectorIR k = new WidenLane(kInt, LaneType.LONG);
    VarkaVectorIR hi = sub(x, mul(t, lit(ln2hi)));
    VarkaVectorIR lo = mul(t, lit(LN2LO_0));
    return new Reduction(k, t, hi, lo, sub(hi, lo));
  }

  // -------------------------------------------------------------------------------------------
  // exp, from e_exp.c
  // -------------------------------------------------------------------------------------------

  // The constants of e_exp.c, each in its shortest decimal form (errorprone wants no digit the
  // double does not hold) with the C's two words beside it; VarkaMathPortSuite checks the bits.
  static final double ONE = 1.0;
  static final double TWOM1000 = 9.332636185032189e-302; // 2**-1000=0x01700000,0
  static final double O_THRESHOLD = 709.782712893384; // 0x40862E42, 0xFEFA39EF
  static final double U_THRESHOLD = -745.1332191019411; // 0xc0874910, 0xD52D3051
  static final double LN2HI_0 = 0.6931471803691238; // 0x3fe62e42, 0xfee00000
  static final double LN2LO_0 = 1.9082149292705877e-10; // 0x3dea39ef, 0x35793c76
  static final double INVLN2 = 1.4426950408889634; // 0x3ff71547, 0x652b82fe
  static final double P1 = 0.16666666666666602; // 0x3FC55555, 0x5555553E
  static final double P2 = -0.0027777777777015593; // 0xBF66C16C, 0x16BEBD93
  static final double P3 = 6.613756321437934e-05; // 0x3F11566A, 0xAF25DE2C
  static final double P4 = -1.6533902205465252e-06; // 0xBEBBBD41, 0xC5D26BF1
  static final double P5 = 4.1381367970572385e-08; // 0x3E663769, 0x72BEA4D0

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
    Cond isTiny = hiBelow(absBits, HX_TINY);
    Cond nonFinite = hiAtLeast(absBits, HX_NON_FINITE);

    // Lines 128-142 in the far form for every lane: k = (int)(invln2*x+halF[xsb]); t = k;
    // hi = x - t*ln2HI[0]; lo = t*ln2LO[0].
    Reduction red = reduce(x, bits, absBits, ln2hi);
    VarkaVectorIR k = red.k();
    VarkaVectorIR hi = red.hi();
    VarkaVectorIR lo = red.lo();
    VarkaVectorIR r = red.r();

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

  // -------------------------------------------------------------------------------------------
  // expm1, from s_expm1.c
  // -------------------------------------------------------------------------------------------

  // The constants of s_expm1.c, lines 115-127, that e_exp.c does not share; its o_threshold,
  // ln2_hi, ln2_lo and invln2 are e_exp.c's O_THRESHOLD, LN2HI_0, LN2LO_0 and INVLN2.
  static final double HUGE = 1.0e+300;
  static final double TINY = 1.0e-300;
  static final double Q1 = -3.33333333333331316428e-02; // BFA11111 111110F4
  static final double Q2 = 1.58730158725481460165e-03; // 3F5A01A0 19FE5585
  static final double Q3 = -7.93650757867487942473e-05; // BF14CE19 9EAADBB7
  static final double Q4 = 4.00821782732936239552e-06; // 3ED0CFCA 86E65239
  static final double Q5 = -2.01099218183624371326e-07; // BE8AFDB7 6E09C32D

  /** {@code |x| >= 56*ln2}: the filter's outer test (line 146). */
  static final int HX_56_LN2 = 0x4043687A;
  /** {@code |x| < 2^-54}: the lanes whose result is {@code x} (line 177). */
  static final int HX_TINY_EXPM1 = 0x3c900000;
  /** {@code k > 56}: with {@code k <= -2}, the form that returns {@code 2^k*y - 1} (line 197). */
  static final long K_FAR = 56;
  /** {@code k < 20}: the form with {@code t = 1-2^-k}; from it on, {@code t = 2^-k} (line 203). */
  static final long K_SPLIT = 20;
  /** {@code __HI(1.0)}, the high word of {@code t} on the lanes neither form rewrites. */
  static final long ONE_HI = 0x3ff00000L;

  /** {@code StrictMath.expm1(x)} in every lane of the double node {@code x}, bit for bit. */
  public VarkaVectorIR expm1(VarkaVectorIR x) {
    return expm1(x, Fault.NONE);
  }

  /**
   * The expansion, node for node {@code vfdlibm}'s {@code Expm1.expm1Blended}, with its line
   * references to s_expm1.c and its lemmas in {@code vfdlibm}'s {@code PROOFS.md}: the reduction is
   * exp's ({@link #reduce}, L10), the {@code k == 0} return {@code x - (x*e-hxs)} is the other
   * forms' {@code x - e} with {@code c = +0} on those lanes (L11), the seven forms chosen by
   * {@code k} are computed in every lane and blended in the C's order, and the early returns, the
   * filter and {@code |x| < 2^-54}, are blended over them last.
   *
   * <p>The library holds the C's {@code int} words in int lanes; here they are long lanes, as in
   * {@link #exp}: the high-word tests are signed compares of {@code |x|}'s bits (L3), {@code k}'s
   * tests are long compares of {@code k} widened, and the words {@code 0x3ff00000-(0x200000>>k)}
   * and {@code (0x3ff-k)<<20} are long arithmetic, shifted by {@code k} lane by lane. On a lane
   * whose form reads a word the two agree: {@code 2 <= k < 20} keeps {@code 0x200000>>k} positive,
   * so the unsigned shift written here is the C's signed one, and {@code 20 <= k <= 56} keeps
   * {@code (0x3ff-k)<<20} in {@code [0, 2^31)}; {@code __HI(t) = w} on a zero low word is
   * {@code w << 32}, whose bits above the word shift out. A word on a lane that does not read it
   * is never kept, whatever the shift count there.
   *
   * <p>A null {@code x} answers null as {@code exp}'s does: the reduction's blend makes {@code k}
   * a valid 0 there, so each test of {@code k} is known and the {@code k == 0} blend takes
   * {@code x - e}, which reads {@code x}; every other condition reads {@code x}, is unknown, and
   * takes its {@code otherwise} arm.
   */
  VarkaVectorIR expm1(VarkaVectorIR x, Fault fault) {
    if (x.laneType() != LaneType.DOUBLE) {
      throw VarkaErrors.illegalArgument("expm1 takes a DOUBLE operand, not " + x.laneType());
    }
    double ln2hi = fault == Fault.CONSTANT_OFF_BY_ONE_ULP
        ? Math.nextUp(LN2HI_0) : LN2HI_0;
    long kFar = fault == Fault.THRESHOLD_MOVED ? K_FAR - 1 : K_FAR;
    VarkaVectorIR bits = bits(x);
    // Lines 140-143: hx = __HI(x); xsb = hx&0x80000000; hx &= 0x7fffffff.
    VarkaVectorIR absBits = absBits(bits);
    Cond negative = new Compare(CompareOp.LT, bits, lit(0L));
    // Lines 146, 147, 148 and 177; line 162's test is the reduction's, and line 163's near band
    // needs no test of its own (L10).
    Cond big = hiAtLeast(absBits, HX_56_LN2);
    Cond filter = hiAtLeast(absBits, HX_FILTER);
    Cond nonFinite = hiAtLeast(absBits, HX_NON_FINITE);
    Cond isTiny = hiBelow(absBits, HX_TINY_EXPM1);

    // Lines 162-175: the reduction, then x = hi - lo (here r) and c = (hi-x)-lo.
    Reduction red = reduce(x, bits, absBits, ln2hi);
    VarkaVectorIR k = red.k();
    VarkaVectorIR r = red.r();
    VarkaVectorIR c = fault == Fault.DROP_CORRECTION
        ? lit(0.0) : sub(sub(red.hi(), r), red.lo());

    // Lines 184-188: hfx = 0.5*x; hxs = x*hfx; r1 = one+hxs*(Q1+hxs*(Q2+hxs*(Q3+hxs*(Q4+hxs*
    // Q5)))); t = 3.0-r1*hfx; e = hxs*((r1-t)/(6.0 - x*t)), each product with the library's
    // operand order, the same rounding of the same exact product.
    VarkaVectorIR hfx = mul(r, lit(0.5));
    VarkaVectorIR hxs = mul(r, hfx);
    VarkaVectorIR r1 = add(lit(ONE), mul(add(mul(add(mul(add(mul(add(mul(hxs, lit(Q5)),
        lit(Q4)), hxs), lit(Q3)), hxs), lit(Q2)), hxs), lit(Q1)), hxs));
    VarkaVectorIR tt = sub(lit(3.0), mul(r1, hfx));
    VarkaVectorIR e = mul(hxs, div(sub(r1, tt), sub(lit(6.0), mul(r, tt))));
    // Lines 191-192: e = (x*(e-c)-c); e -= hxs; and x - e, line 189's return on k == 0 (L11).
    VarkaVectorIR em = sub(sub(mul(r, sub(e, c)), c), hxs);
    VarkaVectorIR rMinusE = sub(r, em);

    // The masks of k. Line 197: k <= -2 || k > 56. Line 203: k < 20, among the rest of k >= 2.
    Cond far = new VarkaVectorIR.Or(new Compare(CompareOp.LE, k, lit(-2L)),
        new Compare(CompareOp.GT, k, lit(kFar)));
    Cond mid = new VarkaVectorIR.And(new Compare(CompareOp.GE, k, lit(2L)),
        new Compare(CompareOp.LT, k, lit(K_SPLIT)));
    Cond high = new VarkaVectorIR.And(new Compare(CompareOp.GE, k, lit(K_SPLIT)), new Not(far));
    // Lines 202-208: __HI(t) = 0x3ff00000 - (0x200000>>k) below k = 20, (0x3ff-k)<<20 from it
    // on, and one's high word on the far lanes; t's low word is one's, 0.
    VarkaVectorIR midWord = sub(lit(ONE_HI), new BitOp(BitOpKind.USHR, lit(0x200000L), k));
    VarkaVectorIR highWord = new BitOp(BitOpKind.SHL, sub(lit(0x3ffL), k), lit(20L));
    VarkaVectorIR tWord = blend(high, highWord, blend(mid, midWord, lit(ONE_HI)));
    VarkaVectorIR tv = fromBits(new BitOp(BitOpKind.SHL, tWord, lit(32L)));
    // Line 198, y = one-(e-x), and line 205, y = t-(e-x): one operation, t's word picked per
    // lane. Lines 209-210: y = x-(e+t); y += one.
    VarkaVectorIR y = blend(high, add(sub(r, add(em, tv)), lit(ONE)), sub(tv, sub(em, r)));
    // Lines 199, 206 and 211: __HI(y) += (k<<20); line 200: the far lanes return y-one.
    VarkaVectorIR scaled = addExponent(y, k);
    VarkaVectorIR result = blend(far, sub(scaled, lit(ONE)), scaled);
    // Lines 194-196, k == 1: -2.0*(e-(x+0.5)) for x < -0.25, else one+2.0*(x-e).
    VarkaVectorIR kOne = blend(new Compare(CompareOp.IEEE_LT, r, lit(-0.25)),
        mul(sub(em, add(r, lit(0.5))), lit(-2.0)), add(mul(rMinusE, lit(2.0)), lit(ONE)));
    result = blend(new Compare(CompareOp.EQ, k, lit(1L)), kOne, result);
    // Line 193, k == -1: 0.5*(x-e)-0.5.
    result = blend(new Compare(CompareOp.EQ, k, lit(-1L)),
        sub(mul(rMinusE, lit(0.5)), lit(0.5)), result);
    // Line 189, k == 0: x - (x*e-hxs), here x-e (L11).
    result = blend(new Compare(CompareOp.EQ, k, lit(0L)), rMinusE, result);

    // The early returns, lines 146-158 and 177-179, a later blend winning where two overlap, as
    // the C's earlier return does. Lines 155-157: x < -56*ln2 returns tiny-one (-1.0) where
    // x+tiny < 0.0, which every such finite x is.
    result = blend(new VarkaVectorIR.And(at(new VarkaVectorIR.And(negative, big),
        LaneType.DOUBLE), new Compare(CompareOp.IEEE_LT, add(x, lit(TINY)), lit(0.0))),
        lit(TINY - ONE), result);
    // Line 153: x > o_threshold returns huge*huge, +inf.
    result = blend(new VarkaVectorIR.And(at(filter, LaneType.DOUBLE),
        new Compare(CompareOp.IEEE_LT, lit(O_THRESHOLD), x)), lit(HUGE * HUGE), result);
    // Lines 148-151: x+x for a NaN, x for +inf and -1.0 for -inf.
    Cond isNaN = new Not(new Compare(CompareOp.IEEE_EQ, x, x));
    result = blend(nonFinite, blend(isNaN, add(x, x), blend(negative, lit(-1.0), x)), result);
    // Lines 177-179, |x| < 2^-54: t = huge+x; return x - (t-(huge+x)).
    VarkaVectorIR tinyT = add(x, lit(HUGE));
    return blend(isTiny, sub(x, sub(tinyT, add(x, lit(HUGE)))), result);
  }
}
