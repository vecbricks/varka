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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CrossLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ReinterpretLane;

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
   * ends in a value computed from the operand, never in a literal alone; {@code exp}'s does, and
   * its suite runs it over null lanes.
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

  /** {@code __HI(x)}: each lane's high word, unsigned, its sign bit included, at the long lane. */
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

  /** fdlibm's {@code hx < h} on {@code |x|}'s bits, one signed 64-bit compare. */
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
}
