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
 * The exp, expm1, log and log10 expansions below follow fdlibm 5.3's e_exp.c, s_expm1.c, e_log.c
 * and e_log10.c, which carry this notice:
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

import javax.annotation.Nullable;

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
 * {@link #expm1} follows it, on the same reduction, from {@code Expm1.expm1Blended}, and
 * {@link #log} and {@link #log10} from {@code Log.logBlended} and {@code Log10.log10Blended}, on
 * e_log.c's common path ({@link #logCore}).
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
   * {@code THRESHOLD_MOVED}, and {@code Expm1}'s, {@code Log}'s and {@code Log10}'s of the same
   * names, with {@code Log10}'s fourth), here as arguments of a package-private overload, so the
   * production expansion has none.
   */
  enum Fault {
    NONE, DROP_CORRECTION, CONSTANT_OFF_BY_ONE_ULP, THRESHOLD_MOVED,
    /** {@code Log10.SUBNORMAL_THRESHOLD_MOVED}: line 80's subnormal bound one high word down. */
    SUBNORMAL_THRESHOLD_MOVED
  }

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
  static final double Q1 = -0.03333333333333313; // 0xBFA11111, 0x111110F4
  static final double Q2 = 0.0015873015872548146; // 0x3F5A01A0, 0x19FE5585
  static final double Q3 = -7.93650757867488e-05; // 0xBF14CE19, 0x9EAADBB7
  static final double Q4 = 4.008217827329362e-06; // 0x3ED0CFCA, 0x86E65239
  static final double Q5 = -2.0109921818362437e-07; // 0xBE8AFDB7, 0x6E09C32D

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

  // -------------------------------------------------------------------------------------------
  // log, from e_log.c
  // -------------------------------------------------------------------------------------------

  // The constants of e_log.c that e_exp.c does not share, in their shortest decimal forms with
  // the C's words beside them; its ln2_hi and ln2_lo are e_exp.c's LN2HI_0 and LN2LO_0.
  static final double TWO54 = 1.8014398509481984e16; // 0x43500000, 0x00000000
  static final double LG1 = 0.6666666666666735; // 0x3FE55555, 0x55555593
  static final double LG2 = 0.3999999999940942; // 0x3FD99999, 0x9997FA04
  static final double LG3 = 0.2857142874366239; // 0x3FD24924, 0x94229359
  static final double LG4 = 0.22222198432149784; // 0x3FCC71C5, 0x1D8E78AF
  static final double LG5 = 0.1818357216161805; // 0x3FC74664, 0x96CB03DE
  static final double LG6 = 0.15313837699209373; // 0x3FC39A09, 0xD078C69F
  static final double LG7 = 0.14798198605116586; // 0x3FC2F112, 0xDF3E5244
  /** Line 117's {@code 0.33333333333333333}, the double 0x3fd5555555555555. */
  static final double THIRD = 0.3333333333333333;

  /** The bits of {@code Double.MIN_NORMAL}, the lowest common lane: high word 0x00100000. */
  static final long COMMON_LO = 0x00100000_00000000L;
  /** The bits of +inf, above the highest common lane: high word {@code 0x7ff00000}. */
  static final long COMMON_HI = 0x7ff00000_00000000L;
  /** Line 110's {@code 0x95f64}: it carries into bit 20 when {@code 1+f >= sqrt(2)}. */
  static final long SQRT2_SPLIT = 0x95f64;
  /** Line 124's {@code 0x6147a}: the lowest {@code hx} with {@code i > 0}. */
  static final long HX_BAND_LO = 0x6147a;
  /** Line 126's {@code 0x6b851}: the highest {@code hx} with {@code i > 0}. */
  static final long HX_BAND_HI = 0x6b851;
  /** {@code 0x1.8p52}, whose ulp is 1, and its bits: {@code (double)k} without {@code L2D}. */
  static final double DK_MAGIC = 0x1.8p52;
  static final long DK_MAGIC_BITS = 0x43380000_00000000L;
  /** The significand bits of a high word, {@code 0x000fffff}. */
  static final long HI_SIGNIFICAND = 0x000fffffL;

  /** {@code StrictMath.log(x)} in every lane of the double node {@code x}, bit for bit. */
  public VarkaVectorIR log(VarkaVectorIR x) {
    return log(x, Fault.NONE);
  }

  /** {@code lo <= b < hi} for the long node {@code b}, one signed compare (L9). */
  private Cond inRange(VarkaVectorIR b, long lo, long hi) {
    return new Compare(CompareOp.LT, add(b, lit(Long.MIN_VALUE - lo)),
        lit(Long.MIN_VALUE + (hi - lo)));
  }

  /** The double {@code k} for the long node {@code kBits = k + DK_MAGIC_BITS}, exactly (L7). */
  private VarkaVectorIR dk(VarkaVectorIR kBits) {
    return sub(fromBits(kBits), lit(DK_MAGIC));
  }

  /**
   * The expansion, node for node {@code vfdlibm}'s {@code Log.logBlended}, with its line
   * references to e_log.c and its lemmas: the C's int words in long lanes (L8), {@code dk} without
   * {@code L2D} (L7), the common and subnormal lanes by one biased compare (L9), and every return
   * in its {@code k != 0} form, which with {@code dk = +0} gives the {@code k == 0} form's bits
   * (L6). The rare lanes, {@code x <= 0}, the subnormals, the NaNs and {@code +inf}, are computed
   * in every lane and blended last; a subnormal is scaled by {@code 2^54} first, as the C does.
   *
   * <p>A null {@code x} answers null: every condition reads {@code x}, so it is unknown there and
   * each blend takes its {@code otherwise} arm, and the chain of those arms ends in the general
   * form, which reads {@code x}.
   */
  VarkaVectorIR log(VarkaVectorIR x, Fault fault) {
    if (x.laneType() != LaneType.DOUBLE) {
      throw VarkaErrors.illegalArgument("log takes a DOUBLE operand, not " + x.laneType());
    }
    VarkaVectorIR bits = bits(x);
    // Lines 100 and 107: the common lanes, 0x00100000 <= hx < 0x7ff00000 for the C's signed hx;
    // line 104: the subnormals, bits in [1, COMMON_LO), scaled by two54 with k starting at -54.
    Cond common = inRange(bits, COMMON_LO, COMMON_HI);
    Cond subnormal = inRange(bits, 1L, COMMON_LO);
    VarkaVectorIR xs = blend(subnormal, mul(x, lit(TWO54)), x);
    VarkaVectorIR result = logCore(xs, subnormal, fault);
    // The rare paths, in the C's order of precedence, the last blend winning. Line 107: x+x for
    // +inf and a positive NaN. Line 103: (x-x)/zero for a set sign bit, -inf and a negative NaN
    // among them. Line 102: -two54/zero, -inf, for +-0.
    Cond negative = new Compare(CompareOp.LT, bits, lit(0L));
    Cond positiveNonFinite = new Not(new VarkaVectorIR.Or(
        new VarkaVectorIR.Or(common, subnormal), negative));
    result = blend(positiveNonFinite, add(x, x), result);
    result = blend(negative, div(sub(x, x), lit(0.0)), result);
    return blend(new Compare(CompareOp.IEEE_EQ, x, lit(0.0)), lit(-TWO54 / 0.0), result);
  }

  /**
   * e_log.c's common path in every lane, {@code vfdlibm}'s {@code Log.logCore}: the C's result for
   * each lane holding a positive normal finite {@code xs}, an unspecified value elsewhere.
   * {@code scaled}, when not null, marks the lanes whose {@code xs} is a subnormal scaled by
   * {@code 2^54}, whose {@code k} starts at -54.
   */
  VarkaVectorIR logCore(VarkaVectorIR xs, @Nullable Cond scaled, Fault fault) {
    double ln2hi = fault == Fault.CONSTANT_OFF_BY_ONE_ULP ? Math.nextUp(LN2HI_0) : LN2HI_0;
    long split = fault == Fault.THRESHOLD_MOVED ? SQRT2_SPLIT + 1 : SQRT2_SPLIT;
    // Lines 105-112 in the long lanes of the bits (L8): hx = __HI(x); k += (hx>>20)-1023, held
    // as k plus the bits of 0x1.8p52 (L7), the two constants added as one; hx &= 0x000fffff;
    // i = (hx+0x95f64)&0x100000; __HI(x) = hx|(i^0x3ff00000); k += (i>>20). The shifts are
    // logical: hx and i are never negative as longs.
    VarkaVectorIR xsBits = bits(xs);
    VarkaVectorIR hx = new BitOp(BitOpKind.USHR, xsBits, lit(32L));
    VarkaVectorIR kBits = add(new BitOp(BitOpKind.USHR, hx, lit(20L)),
        lit(DK_MAGIC_BITS - 1023));
    if (scaled != null) {
      kBits = blend(scaled, add(kBits, lit(-54L)), kBits);
    }
    VarkaVectorIR hxm = new BitOp(BitOpKind.AND, hx, lit(HI_SIGNIFICAND));
    VarkaVectorIR i = new BitOp(BitOpKind.AND, add(hxm, lit(split)), lit(0x100000L));
    VarkaVectorIR xn = fromBits(new BitOp(BitOpKind.OR,
        new BitOp(BitOpKind.AND, xsBits, lit(LOW_WORD)),
        new BitOp(BitOpKind.SHL, new BitOp(BitOpKind.OR, hxm,
            new BitOp(BitOpKind.XOR, i, lit(ONE_HI))), lit(32L))));
    kBits = add(kBits, new BitOp(BitOpKind.USHR, i, lit(20L)));
    // Line 114: (0x000fffff&(2+hx))<3, |f| < 2**-20. Lines 124-131: i = hx-0x6147a;
    // j = 0x6b851-hx; i |= j; i > 0.
    Cond small = new Compare(CompareOp.LT,
        new BitOp(BitOpKind.AND, add(hxm, lit(2L)), lit(HI_SIGNIFICAND)), lit(3L));
    Cond band = new Compare(CompareOp.GT, new BitOp(BitOpKind.OR, sub(hxm, lit(HX_BAND_LO)),
        sub(lit(HX_BAND_HI), hxm)), lit(0L));
    VarkaVectorIR dk = dk(kBits);

    // Line 113: f = x-1.0, and k*ln2 in two parts.
    VarkaVectorIR f = sub(xn, lit(ONE));
    VarkaVectorIR dkHi = mul(dk, lit(ln2hi));
    VarkaVectorIR dkLo = fault == Fault.DROP_CORRECTION ? lit(0.0) : mul(dk, lit(LN2LO_0));
    // Lines 121-130: s = f/(2.0+f); z = s*s; w = z*z; t1 = w*(Lg2+w*(Lg4+w*Lg6));
    // t2 = z*(Lg1+w*(Lg3+w*(Lg5+w*Lg7))); R = t2+t1.
    VarkaVectorIR s = div(f, add(lit(2.0), f));
    VarkaVectorIR z = mul(s, s);
    VarkaVectorIR w = mul(z, z);
    VarkaVectorIR t1 = mul(w, add(mul(w, add(mul(w, lit(LG6)), lit(LG4))), lit(LG2)));
    VarkaVectorIR t2 = mul(z, add(mul(w, add(mul(w, add(mul(w, lit(LG7)), lit(LG5))),
        lit(LG3))), lit(LG1)));
    VarkaVectorIR r = add(t2, t1);

    // Every return in its k != 0 form (L6). Lines 136-137, i <= 0:
    // dk*ln2_hi-((s*(f-R)-dk*ln2_lo)-f).
    VarkaVectorIR result = sub(dkHi, sub(sub(mul(s, sub(f, r)), dkLo), f));
    // Lines 132-134, i > 0: hfsq = 0.5*f*f; dk*ln2_hi-((hfsq-(s*(hfsq+R)+dk*ln2_lo))-f).
    VarkaVectorIR hfsq = mul(mul(f, lit(0.5)), f);
    result = blend(band, sub(dkHi, sub(sub(hfsq, add(mul(s, add(hfsq, r)), dkLo)), f)), result);
    // Lines 117-119, |f| < 2**-20: R = f*f*(0.5-0.33333333333333333*f);
    // dk*ln2_hi-((R-dk*ln2_lo)-f). Lines 115-116, f == 0: dk*ln2_hi+dk*ln2_lo.
    VarkaVectorIR rSmall = mul(mul(f, f), sub(lit(0.5), mul(f, lit(THIRD))));
    result = blend(small, sub(dkHi, sub(sub(rSmall, dkLo), f)), result);
    Cond fIsZero = new VarkaVectorIR.And(new Compare(CompareOp.IEEE_EQ, f, lit(0.0)),
        at(small, LaneType.DOUBLE));
    return blend(fIsZero, add(dkHi, dkLo), result);
  }

  // -------------------------------------------------------------------------------------------
  // log10, from e_log10.c
  // -------------------------------------------------------------------------------------------

  // The constants of e_log10.c, lines 54-57, in their shortest decimal forms; its two54 is
  // e_log.c's TWO54.
  static final double IVLN10 = 0.4342944819032518; // 0x3FDBCB7B, 0x1526E50E
  static final double LOG10_2HI = 0.30102999566361177; // 0x3FD34413, 0x509F6000
  static final double LOG10_2LO = 3.694239077158931e-13; // 0x3D59FEF3, 0x11F12B36

  /** {@code StrictMath.log10(x)} in every lane of the double node {@code x}, bit for bit. */
  public VarkaVectorIR log10(VarkaVectorIR x) {
    return log10(x, Fault.NONE);
  }

  /**
   * The expansion, node for node {@code vfdlibm}'s {@code Log10.log10Blended} with the C's int
   * words in long lanes, as {@link #logCore} holds them (L8): read {@code k} from the high word,
   * set {@code i = 1} when {@code k < 0}, rewrite the high word's exponent so the lane lies in
   * {@code [1, 2)} or {@code [0.5, 1)}, and return
   * {@code (y*log10_2lo + ivln10*log(x)) + y*log10_2hi} with {@code y = k + i}, {@code log} being
   * e_log.c's common path of the rescaled lane, a positive normal double in every lane.
   *
   * <p>Three forms differ from the library's int lanes, on the lanes whose result is kept, the
   * positive finite ones, without changing a value: {@code hx} is the bits' high word by a logical
   * shift, the C's signed one there; {@code i = ((unsigned)k&0x80000000)>>31} is {@code k}'s sign
   * bit as a long, 1 exactly where {@code k < 0}; and {@code y = (double)(k+i)} is L7's exact add
   * and subtract of {@code 0x1.8p52}, where the library writes an exact {@code I2D}. The rare
   * tests are on the scaled lane's bits: {@code hx >= 0x7ff00000} for the signed {@code hx} is the
   * signed {@code bits >= 0x7ff00000_00000000}, and {@code hx < 0} is {@code bits < 0} (L9's
   * argument on the raw bits).
   *
   * <p>A null {@code x} answers null: every condition reads {@code x}, so each blend takes its
   * {@code otherwise} arm, and those arms end in the general form, which reads {@code x}.
   */
  VarkaVectorIR log10(VarkaVectorIR x, Fault fault) {
    if (x.laneType() != LaneType.DOUBLE) {
      throw VarkaErrors.illegalArgument("log10 takes a DOUBLE operand, not " + x.laneType());
    }
    double ivln10 = fault == Fault.CONSTANT_OFF_BY_ONE_ULP ? Math.nextUp(IVLN10) : IVLN10;
    double subnormalBound = fault == Fault.SUBNORMAL_THRESHOLD_MOVED
        ? Double.longBitsToDouble(fromHiBits(0x000fffff)) : Double.MIN_NORMAL;
    // Line 80: a subnormal is scaled by two54, and k starts at -54.
    Cond subnormal = new VarkaVectorIR.And(new Compare(CompareOp.IEEE_LT, lit(0.0), x),
        new Compare(CompareOp.IEEE_LT, x, lit(subnormalBound)));
    VarkaVectorIR xs = blend(subnormal, mul(x, lit(TWO54)), x);
    // Lines 81 and 84: hx = __HI(x); k += (hx>>20)-1023.
    VarkaVectorIR xsBits = bits(xs);
    VarkaVectorIR hx = new BitOp(BitOpKind.USHR, xsBits, lit(32L));
    VarkaVectorIR k = sub(new BitOp(BitOpKind.USHR, hx, lit(20L)), lit(1023L));
    k = blend(subnormal, add(k, lit(-54L)), k);
    // Line 85: i = ((unsigned)k&0x80000000)>>31.
    VarkaVectorIR i = new BitOp(BitOpKind.USHR,
        fault == Fault.THRESHOLD_MOVED ? sub(k, lit(1L)) : k, lit(63L));
    // Lines 86 and 88: hx = (hx&0x000fffff)|((0x3ff-i)<<20); __HI(x) = hx.
    VarkaVectorIR hxn = new BitOp(BitOpKind.OR, new BitOp(BitOpKind.AND, hx, lit(HI_SIGNIFICAND)),
        new BitOp(BitOpKind.SHL, sub(lit(0x3ffL), i), lit(20L)));
    VarkaVectorIR xn = fromBits(new BitOp(BitOpKind.OR,
        new BitOp(BitOpKind.AND, xsBits, lit(LOW_WORD)),
        new BitOp(BitOpKind.SHL, hxn, lit(32L))));
    // Line 89's __ieee754_log(x) of the rescaled x, and line 87's y = (double)(k+i) (L7).
    VarkaVectorIR logx = logCore(xn, null, Fault.NONE);
    VarkaVectorIR y = dk(add(add(k, i), lit(DK_MAGIC_BITS)));
    // Lines 89-90: z = y*log10_2lo + ivln10*log(x); return z+y*log10_2hi, the product written
    // log(x)*ivln10 as the library writes it, the same rounding of the same exact product.
    VarkaVectorIR yLo = fault == Fault.DROP_CORRECTION ? lit(0.0) : mul(y, lit(LOG10_2LO));
    VarkaVectorIR result = add(add(yLo, mul(logx, lit(ivln10))), mul(y, lit(LOG10_2HI)));
    // The rare paths, the last blend winning. Line 83: x+x for +inf and a positive NaN. Line 79:
    // (x-x)/zero for a set sign bit. Line 78: -two54/zero, -inf, for +-0.
    result = blend(new Compare(CompareOp.GT, xsBits, lit(COMMON_HI - 1)), add(x, x), result);
    result = blend(new Compare(CompareOp.LT, xsBits, lit(0L)), div(sub(x, x), lit(0.0)), result);
    return blend(new Compare(CompareOp.IEEE_EQ, x, lit(0.0)), lit(-TWO54 / 0.0), result);
  }
}
