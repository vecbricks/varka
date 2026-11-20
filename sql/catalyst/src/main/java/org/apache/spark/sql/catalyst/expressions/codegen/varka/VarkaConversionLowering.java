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

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.NarrowConversionForm;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.WideConversionForm;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * The AVX2 forms of {@code L2D} and {@code L2F} (`VARKA-343.md` 2.3), for a level below 3
 * ({@link VarkaEmitOptions#convertsFallBack()}): C2 on x86 lowers those conversions only with
 * AVX512DQ, so without it {@code convertShape} runs as the Vector API's Java loop. Each form is
 * 64-bit integer operations, reinterpretations and IEEE double arithmetic, and gives Java's
 * {@code (double) v} or {@code (float) v} on every long ({@code round_to_odd.smt2} proves each
 * step from these constants). {@code bitsToDouble(n | bits(2^52)) - 2^52} is {@code n} exactly
 * for {@code 0 <= n < 2^52}, and {@code bitsToDouble(n + bits(M)) - M}, {@code M = 0x1.8p52},
 * for {@code |n| <= 2^51}.
 *
 * <ul>
 *   <li>{@code L2D}, {@link WideConversionForm#XOR_SPLIT}, the default (VARKA-583): the high
 *   word {@code v >>> 32}, XOR-ed into the bits of {@code 2^84 + 2^63}, is the double
 *   {@code 2^84 + (hi + 2^31) * 2^32}, where {@code hi = v >> 32}: the XOR flips the word's sign
 *   bit, which adds {@code 2^31} to a signed word, and {@code 2^84}'s ulp is {@code 2^32}, so no
 *   multiply follows. Subtracting {@code 2^84 + 2^63 + 2^52} leaves {@code hi * 2^32 - 2^52}
 *   exactly, and the low word read under {@code 2^52}'s exponent is {@code 2^52 + lo}: their sum
 *   is {@code v}, rounded once. No arithmetic shift, which AVX2 has no 64-bit instruction
 *   for.</li>
 *   <li>{@code L2D}, {@link WideConversionForm#SPLIT}, part 343b1's: {@code v >> 32} and
 *   {@code v & 0xFFFFFFFF} convert exactly, the first scaled by {@code 2^32}, and their sum is
 *   the one rounding of {@code v}.</li>
 *   <li>{@code L2D}, {@link WideConversionForm#MAGIC}: {@code bitsToDouble(v + bits(M)) - M}
 *   under a guard that declines a batch with a live lane past {@code |v| <= 2^51}.</li>
 *   <li>{@code L2F}: the magnitude {@code m = |v|}, {@code 2^63} at {@code Long.MIN_VALUE}, is
 *   converted exactly below {@code 2^52} and above it rounded to odd at the bit {@code 2^12}
 *   (truncated, a sticky bit or-ed in for what was cut), then {@code D2F}: rounding to odd at
 *   {@code p + k} bits and then to nearest at {@code p} is one rounding when {@code k >= 2}
 *   (Boldo and Melquiond 2008, Theorem 3), here {@code p = 24} and {@code k >= 17}. Through
 *   {@code (double) v} it would round twice: {@code 2^53 + 2^29 + 1} gives {@code 2^53}
 *   against {@code 2^53 + 2^30}. The form has no mask, since C2 on x86 lowers none at two
 *   64-bit lanes: {@code sel} is all ones where {@code m >= 2^52}, from integer arithmetic; the
 *   sign and {@code sel} take a logical shift where part 343b1's took an arithmetic one and
 *   more steps, kept as {@code roundToOddByShifts} (VARKA-583); the arm is
 *   {@code m ^ ((m ^ t12) & sel)}; and the magic number, {@code 2^64} on the large arm, whose ulp
 *   {@code 2^12} scales {@code t} as it converts, takes {@code v}'s sign bit. This is
 *   {@link NarrowConversionForm#ROUND_TO_ODD}, the default.</li>
 *   <li>{@code L2F}, {@link NarrowConversionForm#WINDOWED} (VARKA-600): {@code (v + 2^51) >>> 52}
 *   is zero on exactly the lanes with {@code -2^51 <= v < 2^51}, and its OR over the group's
 *   lanes is one scalar long, so the test needs no mask. Where it is zero the group takes the
 *   bounded magic, exact there, and one {@code D2F}, which is then the one rounding of {@code v};
 *   elsewhere it takes the rounding to odd. Both arms leave a float vector of the same species,
 *   so the join merges one type.</li>
 *   <li>{@code L2F}, {@link NarrowConversionForm#SCALAR} (VARKA-600): each long lane read,
 *   converted by Java's {@code l2f} and inserted into a zero float vector, which is the cast
 *   itself. C2 intrinsifies the read, the insert and the window test's reduction under
 *   {@code -XX:UseAVX=2} at 128 and 256 bits ({@code plans/m8/results/VARKA-600}).</li>
 * </ul>
 */
final class VarkaConversionLowering {

  private VarkaConversionLowering() {}

  /** {@code M}: the magic number whose ulp is 1 over the whole of {@code [2^52, 2^53)}. */
  static final double MAGIC = 0x1.8p52;
  static final long MAGIC_BITS = Double.doubleToRawLongBits(MAGIC);
  /** {@code 2^52}, the magic number of a non-negative integer below it. */
  static final double TWO_52 = 0x1p52;
  static final long TWO_52_BITS = Double.doubleToRawLongBits(TWO_52);
  /** The split's high word's weight, shift and low word. */
  static final double TWO_32 = 0x1p32;
  static final int SPLIT_SHIFT = 32;
  static final long LOW_WORD = 0xFFFFFFFFL;
  /**
   * {@link WideConversionForm#XOR_SPLIT}'s high word's magic, {@code 2^84 + 2^63}: its low 32
   * bits are the sign bit of a 32-bit word alone, so an XOR with {@code v >>> 32} flips that bit
   * and fills the rest, and its ulp is {@code 2^32}.
   */
  static final double HI_MAGIC = 0x1p84 + 0x1p63;
  static final long HI_MAGIC_BITS = Double.doubleToRawLongBits(HI_MAGIC);
  /** What the XOR split subtracts: the high word's magic and the low word's {@code 2^52}. */
  static final double XOR_SPLIT_BIAS = 0x1p84 + 0x1p63 + 0x1p52;
  /** The magic form's window: exact for {@code |v| <= MAGIC_WINDOW}, and not one past it. */
  static final long MAGIC_WINDOW = 1L << 51;
  /** {@code L2F} rounds to odd at the bit {@code 2^ODD_SHIFT} ... */
  static final int ODD_SHIFT = 12;
  /** ... where {@code m >>> SMALL_BITS != 0}, and converts {@code m} exactly below. */
  static final int SMALL_BITS = 52;
  /** What the large arm adds to the exponent of {@code 2^52}: {@code ODD_SHIFT}, in place. */
  static final long ODD_EXPONENT = (long) ODD_SHIFT << SMALL_BITS;
  /** The small arm's bound {@code 2^52} as a long, which the default {@code sel} subtracts. */
  static final long TWO_52_INT = 1L << SMALL_BITS;
  /**
   * {@link NarrowConversionForm#WINDOWED}'s test: {@code (v + MAGIC_WINDOW) >>> WINDOW_SHIFT} is
   * zero exactly where {@code -2^51 <= v < 2^51}, inside the magic's window.
   */
  static final int WINDOW_SHIFT = Long.numberOfTrailingZeros(MAGIC_WINDOW) + 1;

  /** The lane calls of each form, which {@code VarkaEmitBudget.weightOf} weighs them by. */
  static final int SPLIT_CALLS = 10;
  static final int XOR_SPLIT_CALLS = 8;
  static final int ODD_CALLS = 24;
  /** The window test's three calls, the bounded magic's three and its {@code D2F}. */
  static final int WINDOW_CALLS = 3;
  static final int WINDOWED_FAST_CALLS = 4;
  /** Both of {@link NarrowConversionForm#WINDOWED}'s arms, which share its method. */
  static final int WINDOWED_CALLS = WINDOW_CALLS + WINDOWED_FAST_CALLS + ODD_CALLS;

  /** {@link NarrowConversionForm#SCALAR}'s calls at {@code lanes} long lanes: a read and an
   *  insert a lane, and the zero vector. */
  static int scalarCalls(int lanes) {
    return 2 * lanes + 1;
  }

  /** Whether {@code n} takes an AVX2 form: Slots, the refusals and the emission all ask. */
  static boolean takesWideForm(Analysis analysis, ConvertLane n) {
    return n.child().laneType() == LaneType.LONG && n.target().isFloating()
        && analysis.options.convertsFallBack();
  }

  /** Whether {@code n} is the bounded {@code L2D}, whose guard declines a batch past 2^51. */
  static boolean takesMagic(Analysis analysis, ConvertLane n) {
    return takesWideForm(analysis, n) && n.target() == LaneType.DOUBLE
        && analysis.options.wideConversionForm() == WideConversionForm.MAGIC;
  }

  /**
   * How many scratch locals {@code n}'s form parks values in: the rounding to odd's four, which
   * the windowed form shares, the scalar form's one long vector, and none for {@code L2D}.
   */
  static int scratch(Analysis analysis, ConvertLane n) {
    if (!takesWideForm(analysis, n) || n.target() != LaneType.FLOAT) {
      return 0;
    }
    return switch (analysis.options.narrowConversionForm()) {
      case ROUND_TO_ODD, WINDOWED -> 4;
      case SCALAR -> 1;
    };
  }

  /** {@code [v] -> [d]}, a float filling the low half of its species. */
  static void emit(CodeBuilder cb, ConvertLane n, boolean dense, Analysis analysis, Slots s) {
    if (n.target() == LaneType.FLOAT) {
      int[] tmp = s.convertTmp.get(n);
      if (tmp == null) {
        throw VarkaErrors.illegalState("an L2F form with no scratch planned: " + n);
      }
      switch (analysis.options.narrowConversionForm()) {
        case ROUND_TO_ODD -> emitRoundToOdd(cb, tmp, analysis);
        case WINDOWED -> emitWindowed(cb, tmp, analysis);
        case SCALAR -> emitScalarLanes(cb, tmp[0], analysis);
      }
      return;
    }
    // Reached only where takesWideForm holds, so MAGIC here is takesMagic.
    switch (analysis.options.wideConversionForm()) {
      case MAGIC -> {
        Integer guardTmp = s.guardTmp.get(n);
        if (guardTmp == null) {
          // Refused by name: without its scratch the window's check would be dropped silently.
          throw VarkaErrors.illegalState("a bounded L2D with no guard scratch planned: " + n);
        }
        VarkaVectorWalk.emitRangeGuard(cb, n, dense ? null : s.wordRef.get(n.child()), guardTmp,
            dense, analysis, s, -MAGIC_WINDOW, MAGIC_WINDOW);
        emitMagic(cb, "add", MAGIC_BITS, MAGIC);
      }
      case SPLIT -> emitSplit(cb);
      case XOR_SPLIT -> emitXorSplit(cb);
    }
  }

  /** {@code [n] -> [bitsToDouble(n op bits) - magic]}, {@code op} {@code add} or {@code or}. */
  private static void emitMagic(CodeBuilder cb, String op, long bits, double magic) {
    emitScalar(cb, op, bits);
    cb.invokevirtual(VECTOR, "reinterpretAsDoubles", REINTERPRET_D);
    cb.loadConstant(magic);
    cb.invokevirtual(DOUBLE_VECTOR, "sub", LANEWISE_VD);
  }

  private static void emitSplit(CodeBuilder cb) {
    cb.dup();                                                   // [v, v]
    cb.getstatic(VECTOR_OPERATORS, "ASHR", VO_BINARY);
    cb.loadConstant((long) SPLIT_SHIFT);
    cb.invokevirtual(LONG_VECTOR, "lanewise", LONG_LANEWISE_BINARY);    // [v, hi]
    emitMagic(cb, "add", MAGIC_BITS, MAGIC);
    cb.loadConstant(TWO_32);
    cb.invokevirtual(DOUBLE_VECTOR, "mul", LANEWISE_VD);        // [v, dh]
    cb.swap();                                                  // [dh, v]
    cb.loadConstant(LOW_WORD);
    cb.invokevirtual(LONG_VECTOR, "and", Lane.LONG.lanewiseVI);
    emitMagic(cb, "or", TWO_52_BITS, TWO_52);                         // [dh, dl]
    cb.invokevirtual(DOUBLE_VECTOR, "add", Lane.DOUBLE.lanewiseVV);   // [dh + dl]
  }

  private static void emitXorSplit(CodeBuilder cb) {
    cb.dup();                                                   // [v, v]
    emitShift(cb, "LSHR", SPLIT_SHIFT);
    cb.getstatic(VECTOR_OPERATORS, "XOR", VO_ASSOCIATIVE);
    cb.loadConstant(HI_MAGIC_BITS);
    cb.invokevirtual(LONG_VECTOR, "lanewise", LONG_LANEWISE_BINARY);
    cb.invokevirtual(VECTOR, "reinterpretAsDoubles", REINTERPRET_D);
    cb.loadConstant(XOR_SPLIT_BIAS);
    cb.invokevirtual(DOUBLE_VECTOR, "sub", LANEWISE_VD);        // [v, hi * 2^32 - 2^52]
    cb.swap();
    cb.loadConstant(LOW_WORD);
    cb.invokevirtual(LONG_VECTOR, "and", Lane.LONG.lanewiseVI);
    emitScalar(cb, "or", TWO_52_BITS);
    cb.invokevirtual(VECTOR, "reinterpretAsDoubles", REINTERPRET_D);    // [.., 2^52 + lo]
    cb.invokevirtual(DOUBLE_VECTOR, "add", Lane.DOUBLE.lanewiseVV);
  }

  /** {@code [a] -> [a op b]} for a long vector {@code b} in a local. */
  private static void emitWith(CodeBuilder cb, String op, int b) {
    cb.getstatic(VECTOR_OPERATORS, op, VO_ASSOCIATIVE);
    cb.aload(b);
    cb.invokevirtual(LONG_VECTOR, "lanewise", Lane.LONG.integral().lanewiseBinaryV());
  }

  /** {@code [a] -> [a >>> by]} or {@code a >> by}. */
  private static void emitShift(CodeBuilder cb, String op, int by) {
    cb.getstatic(VECTOR_OPERATORS, op, VO_BINARY);
    cb.loadConstant((long) by);
    cb.invokevirtual(LONG_VECTOR, "lanewise", LONG_LANEWISE_BINARY);
  }

  private static void emitScalar(CodeBuilder cb, String op, long value) {
    cb.loadConstant(value);
    cb.invokevirtual(LONG_VECTOR, op, Lane.LONG.lanewiseVI);
  }

  /** {@code [a] -> [-a]}. */
  private static void emitNeg(CodeBuilder cb) {
    cb.getstatic(VECTOR_OPERATORS, "NEG", VO_UNARY);
    cb.invokevirtual(LONG_VECTOR, "lanewise", Lane.LONG.integral().lanewiseUnary());
  }

  /**
   * {@code [v] -> [f]} by {@link NarrowConversionForm#WINDOWED}: the window test on scalars, then
   * either arm. The rounding to odd parks {@code v} in the same local the test reads it from.
   */
  private static void emitWindowed(CodeBuilder cb, int[] tmp, Analysis analysis) {
    int v = tmp[0];
    Label outside = cb.newLabel();
    Label join = cb.newLabel();
    cb.astore(v);
    // w = OR over the lanes of (v + 2^51) >>> 52: zero exactly where every lane is in the window.
    cb.aload(v);
    emitScalar(cb, "add", MAGIC_WINDOW);
    emitShift(cb, "LSHR", WINDOW_SHIFT);
    cb.getstatic(VECTOR_OPERATORS, "OR", VO_ASSOCIATIVE);
    cb.invokevirtual(LONG_VECTOR, "reduceLanes", LONG_REDUCE_LANES);
    cb.lconst_0();
    cb.lcmp();
    cb.ifne(outside);
    // Inside: bitsToDouble(v + bits(M)) - M is v exactly, and D2F its one rounding.
    cb.aload(v);
    emitMagic(cb, "add", MAGIC_BITS, MAGIC);
    VarkaVectorWalk.emitShapeConversion(cb, Lane.DOUBLE, Lane.FLOAT, analysis);
    cb.goto_(join);
    cb.labelBinding(outside);
    cb.aload(v);
    emitRoundToOdd(cb, tmp, analysis);
    cb.labelBinding(join);
  }

  /**
   * {@code [v] -> [f]} by {@link NarrowConversionForm#SCALAR}: the float species of the kernel's
   * width, zero, with lane {@code k} replaced by {@code (float) v.lane(k)} for every long lane, so
   * the upper half is zero as {@code convertShape}'s part 0 leaves it.
   */
  private static void emitScalarLanes(CodeBuilder cb, int v, Analysis analysis) {
    cb.astore(v);
    cb.getstatic(Lane.FLOAT.vector, Lane.FLOAT.speciesField(analysis.lanesOf(Lane.FLOAT)),
        VECTOR_SPECIES);
    cb.invokestatic(Lane.FLOAT.vector, "zero", FLOAT_ZERO);
    for (int k = 0; k < analysis.lanesOf(Lane.LONG); k++) {
      cb.loadConstant(k);                                       // [f, k]
      cb.aload(v);
      cb.loadConstant(k);
      cb.invokevirtual(LONG_VECTOR, "lane", LONG_LANE);
      cb.l2f();                                                 // [f, k, (float) v.lane(k)]
      cb.invokevirtual(Lane.FLOAT.vector, "withLane", FLOAT_WITH_LANE);
    }
  }

  private static void emitRoundToOdd(CodeBuilder cb, int[] tmp, Analysis analysis) {
    int v = tmp[0];
    int a = tmp[1];
    int m = tmp[2];
    int sel = tmp[3];
    boolean byShifts = analysis.options.roundToOddByShifts();
    cb.astore(v);
    // m = (v ^ s) - s with s = v >> 63, all ones where v < 0, or -(v >>> 63), the same lanes
    // without the arithmetic shift: |v|, and 2^63 read unsigned at Long.MIN_VALUE.
    cb.aload(v);
    if (byShifts) {
      emitShift(cb, "ASHR", Long.SIZE - 1);
    } else {
      emitShift(cb, "LSHR", Long.SIZE - 1);
      emitNeg(cb);
    }
    cb.astore(a);
    cb.aload(v);
    emitWith(cb, "XOR", a);
    cb.aload(a);
    cb.invokevirtual(LONG_VECTOR, "sub", Lane.LONG.lanewiseVV);
    cb.astore(m);
    // sel, all ones where m >= 2^52: ((m - 2^52) >>> 63) - 1, the subtraction's sign being
    // m's place against 2^52 for every m up to 2^63, or -(((m >>> 52) + Long.MAX_VALUE) >>> 63).
    cb.aload(m);
    if (byShifts) {
      emitShift(cb, "LSHR", SMALL_BITS);
      emitScalar(cb, "add", Long.MAX_VALUE);
      emitShift(cb, "LSHR", Long.SIZE - 1);
      emitNeg(cb);
    } else {
      emitScalar(cb, "sub", TWO_52_INT);
      emitShift(cb, "LSHR", Long.SIZE - 1);
      emitScalar(cb, "sub", 1L);
    }
    cb.astore(sel);
    // t12 = (m >>> 12) | (((m & 0xFFF) + 0xFFF) >>> 12): m rounded to odd at the bit 2^12.
    cb.aload(m);
    emitScalar(cb, "and", (1L << ODD_SHIFT) - 1);
    emitScalar(cb, "add", (1L << ODD_SHIFT) - 1);
    emitShift(cb, "LSHR", ODD_SHIFT);
    cb.astore(a);
    cb.aload(m);
    emitShift(cb, "LSHR", ODD_SHIFT);
    emitWith(cb, "OR", a);
    // t = m ^ ((m ^ t12) & sel): t12 on the large arm, m below it.
    emitWith(cb, "XOR", m);
    emitWith(cb, "AND", sel);
    emitWith(cb, "XOR", m);
    cb.astore(m);
    // p = (bits(2^52) | (v & Long.MIN_VALUE)) + (sel & (12 << 52)): the arm's magic, signed;
    // the add raises the exponent by 12 on the large arm and carries into nothing else.
    cb.aload(v);
    emitScalar(cb, "and", Long.MIN_VALUE);
    emitScalar(cb, "or", TWO_52_BITS);
    cb.aload(sel);
    emitScalar(cb, "and", ODD_EXPONENT);
    cb.invokevirtual(LONG_VECTOR, "add", Lane.LONG.lanewiseVV);
    cb.astore(a);
    // d = bitsToDouble(t | p) - bitsToDouble(p), exact; then the one rounding to a float.
    cb.aload(m);
    emitWith(cb, "OR", a);
    cb.invokevirtual(VECTOR, "reinterpretAsDoubles", REINTERPRET_D);
    cb.aload(a);
    cb.invokevirtual(VECTOR, "reinterpretAsDoubles", REINTERPRET_D);
    cb.invokevirtual(DOUBLE_VECTOR, "sub", Lane.DOUBLE.lanewiseVV);
    VarkaVectorWalk.emitShapeConversion(cb, Lane.DOUBLE, Lane.FLOAT, analysis);
  }
}
