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

import static jdk.incubator.vector.VectorOperators.ASHR;
import static jdk.incubator.vector.VectorOperators.LSHL;
import static jdk.incubator.vector.VectorOperators.LSHR;
import static jdk.incubator.vector.VectorOperators.OR;
import static jdk.incubator.vector.VectorOperators.XOR;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * The remainder of one {@code DECIMAL(38, s)} column by another at the same scale, {@code x % y}
 * and {@code pmod(x, y)}, as split F (VARKA-604): the form that stays vector code at two long
 * lanes on x86, where C2 lowers no rearrange, compare, blend or mask op (VARKA-577.md 10.4).
 *
 * <p><b>The arithmetic.</b> Of unscaled values at one scale, {@code x % y} has the sign of
 * {@code x} and {@code |x % y| < |y|}, and {@code pmod} is Spark's rule over it, {@code r < 0 ?
 * (r + y) % y : r}, which adds {@code y} to a negative {@code r} when {@code y > 0}. The loop
 * works on the magnitudes {@code a = |x|} and {@code b = |y|}, both below {@code 10^38 < 2^127},
 * by three rounds of a floating quotient estimate and an exact multiply-subtract, then two
 * conditional subtractions of {@code b}; the sign goes back at the end. That is form E of the
 * remainder spike ({@code VarkaDecimalRemainderSpikeBenchmark.bodyE}, whose comment carries the
 * exactness argument, VARKA-577.md 2.3 and 10.2) with every compare, blend and mask replaced by
 * bit arithmetic on lanes of 0 or 1: a condition is a lane {@code c} of 0 or 1, or {@code -c} of 0
 * or -1, and a select of {@code v} over {@code u} where {@code c} is {@code u ^ ((u ^ v) & -c)}.
 * The unsigned borrow of {@code u - v}, {@code d} that difference less any borrow in, is the top
 * bit of {@code (~u & v) | ((~u | v) & d)}, the carry of {@code u + v} into {@code s} that of
 * {@code (u & v) | ((u | v) & ~s)} (Hacker's Delight 2-13), and {@code v == 0} the top bit of
 * {@code ~(v | -v)}. Every value is E's, in E's order, so E's argument holds unchanged.
 *
 * <p><b>The layout.</b> Arrow keeps a decimal as a 16-byte slot, the low word then the high
 * word, and the loop wants a lane group's low words in one vector and its high words in another.
 * At two lanes the rearrange that would split them is not lowered, so {@link #remainder} copies
 * 64 rows at a time by scalar moves into a block in the split layout, runs {@link #loop64} over
 * it and copies the result back, each in a method of its own. The block is one per thread and
 * reused, so a call allocates nothing beyond the three segments it maps.
 *
 * <p><b>Why 64 rows a call.</b> After other vector code in the JVM that profiles the Vector API's
 * shared templates over several species, the compiled loop can keep one {@code long[L]} a lane
 * group allocated, 16 bytes a row at two lanes. The engine's template warm-up removes that when
 * the loop runs 64 rows a call and does not when it runs 1,024 (VARKA-604.md 2, item 3), so the
 * loop is called once a block and must stay out of line from the driver: it does, by its size,
 * which is far past what C2 inlines at a hot call site ({@code FreqInlineSize}, 325 bytes of
 * bytecode on x86), and the suite checks the size.
 *
 * <p><b>The zero divisors.</b> A row whose divisor is 0 stores 0 and sets its bit in the words
 * the caller passes, the row's bit {@code i & 63} of word {@code i >>> 6}; the caller nulls or
 * raises on them. A short last block is padded with dividend 0 and divisor 1, on which nothing
 * traps, and its word is masked to its live rows besides.
 *
 * <p>Like {@link IntRangeOps}, it runs vector code beside a kernel rather than inside one, so
 * the shared templates are warmed before its first use (VARKA-389).
 */
public final class VarkaDecimalRemainderOps {

  static {
    VarkaVectorTemplateWarmup.beforeVectorCode();
  }

  private static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;
  private static final int L = LS.length();
  private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
  private static final ValueLayout.OfLong WORD = ValueLayout.JAVA_LONG.withOrder(LE);

  /** The rows of a block, and so of one call of {@link #loop64}. */
  public static final int BLOCK_ROWS = 64;

  /** The lane groups of a block. */
  private static final int GROUPS = BLOCK_ROWS / L;

  /** A block's six columns of 64 words, in bytes from its start: x, y, the result. */
  private static final long XL = 0;
  private static final long XH = BLOCK_ROWS * 8L;
  private static final long YL = 2 * XH;
  private static final long YH = 3 * XH;
  private static final long RL = 4 * XH;
  private static final long RH = 5 * XH;
  static final long BLOCK_BYTES = 6 * XH;

  /** Lane j's index, by which a 0/1 lane is shifted into bit j of a word. */
  private static final long[] IOTA = new long[L];

  static {
    if (L < 2 || BLOCK_ROWS % L != 0 || DS.length() != L) {
      throw VarkaErrors.illegalState(L + " long lanes do not divide a 64-row block");
    }
    for (int j = 0; j < L; j++) {
      IOTA[j] = j;
    }
  }

  private static final long MASK32 = 0xFFFF_FFFFL;
  private static final long MASK40 = (1L << 40) - 1;
  private static final long MASK52 = (1L << 52) - 1;

  /** The bits of {@code 2^52}: or'ed onto {@code c < 2^52} they are the double {@code 2^52 + c}. */
  private static final long EXP52 = 0x4330_0000_0000_0000L;
  private static final double TWO52 = 0x1p52;
  private static final double TWO104 = 0x1p104;

  /** The shrink of the estimate, {@code 1 - 2^-49}, so that it never exceeds the quotient. */
  private static final double SHRINK = 1 - 0x1p-49;

  /** The rounds of estimate and multiply-subtract. */
  private static final int ROUNDS = 3;

  /**
   * This thread's block, from the global arena and never freed: 3 KB a thread that has run a
   * remainder, which is what keeps a call from allocating one.
   */
  private static final ThreadLocal<MemorySegment> BLOCK =
      ThreadLocal.withInitial(() -> Arena.global().allocate(BLOCK_BYTES, 64));

  private VarkaDecimalRemainderOps() {}

  /**
   * {@code x % y}, or {@code pmod(x, y)}, of {@code length} rows of 16-byte decimal slots.
   *
   * @param xData address of the dividends' slots
   * @param yData address of the divisors' slots
   * @param outData address of the results' slots, which may be either operand's
   * @param zeroDivisors the zero divisors' words, {@code (length + 63) / 64} of them, each
   *                     overwritten with its rows' bits
   * @param length the row count
   * @param pmod {@code pmod(x, y)} where true, {@code x % y} where false
   */
  public static void remainder(long xData, long yData, long outData, long[] zeroDivisors,
      int length, boolean pmod) {
    if (length <= 0) {
      return;
    }
    if (zeroDivisors.length < (length + BLOCK_ROWS - 1) / BLOCK_ROWS) {
      throw VarkaErrors.illegalArgument(zeroDivisors.length + " zero-divisor words for "
          + length + " rows");
    }
    long bytes = length * 16L;
    MemorySegment x = VarkaSegments.map(xData, bytes);
    MemorySegment y = VarkaSegments.map(yData, bytes);
    MemorySegment out = VarkaSegments.map(outData, bytes);
    MemorySegment block = BLOCK.get();
    for (int row = 0; row < length; row += BLOCK_ROWS) {
      int live = Math.min(BLOCK_ROWS, length - row);
      transposeIn(x, y, block, row, live);
      long word = loop64(block, pmod);
      zeroDivisors[row >>> 6] = live == BLOCK_ROWS ? word : word & ((1L << live) - 1);
      transposeOut(block, out, row, live);
    }
  }

  /**
   * Copies rows {@code row .. row + live} of {@code x} and {@code y} into {@code block}'s split
   * columns, and pads the rest of the block with {@code 0 % 1}.
   */
  static void transposeIn(MemorySegment x, MemorySegment y, MemorySegment block, int row,
      int live) {
    for (int k = 0; k < live; k++) {
      long slot = (row + k) * 16L;
      long at = k * 8L;
      block.set(WORD, XL + at, x.get(WORD, slot));
      block.set(WORD, XH + at, x.get(WORD, slot + 8));
      block.set(WORD, YL + at, y.get(WORD, slot));
      block.set(WORD, YH + at, y.get(WORD, slot + 8));
    }
    for (int k = live; k < BLOCK_ROWS; k++) {
      long at = k * 8L;
      block.set(WORD, XL + at, 0L);
      block.set(WORD, XH + at, 0L);
      block.set(WORD, YL + at, 1L);
      block.set(WORD, YH + at, 0L);
    }
  }

  /** Copies the block's first {@code live} results into {@code out}'s slots from {@code row}. */
  static void transposeOut(MemorySegment block, MemorySegment out, int row, int live) {
    for (int k = 0; k < live; k++) {
      long slot = (row + k) * 16L;
      long at = k * 8L;
      out.set(WORD, slot, block.get(WORD, RL + at));
      out.set(WORD, slot + 8, block.get(WORD, RH + at));
    }
  }

  /**
   * The remainders of the block's 64 rows into its result columns, and their zero divisors'
   * word. The class doc says why it is one method of its own a block.
   */
  static long loop64(MemorySegment block, boolean pmod) {
    var one = DoubleVector.broadcast(DS, 1.0);
    var iota = LongVector.fromArray(LS, IOTA, 0);
    long word = 0;
    for (int g = 0; g < GROUPS; g++) {
      long at = g * (long) L * 8L;
      var xl = LongVector.fromMemorySegment(LS, block, XL + at, LE);
      var xh = LongVector.fromMemorySegment(LS, block, XH + at, LE);
      var yl = LongVector.fromMemorySegment(LS, block, YL + at, LE);
      var yh = LongVector.fromMemorySegment(LS, block, YH + at, LE);
      // The signs as 0/1 and as 0/-1, and a zero divisor as 0/1.
      var sx = xh.lanewise(LSHR, 63);
      var mx = sx.neg();
      var sy = yh.lanewise(LSHR, 63);
      var my = sy.neg();
      var yo = yl.or(yh);
      var z = yo.or(yo.neg()).not().lanewise(LSHR, 63);
      // The magnitudes, (v ^ m) + s over two words: the low word carries out where it is 0
      // and s is 1. A zero divisor's magnitude is 0, so or-ing z makes it 1.
      var rl = xl.lanewise(XOR, mx).add(sx);
      var rh = xh.lanewise(XOR, mx).add(sx.and(xl.or(xl.neg()).not().lanewise(LSHR, 63)));
      var bl = yl.lanewise(XOR, my).add(sy).or(z);
      var bh = yh.lanewise(XOR, my).add(sy.and(yl.or(yl.neg()).not().lanewise(LSHR, 63)));
      var b0 = bl.and(MASK32);
      var b1 = bl.lanewise(LSHR, 32);
      var b2 = bh.and(MASK32);
      var b3 = bh.lanewise(LSHR, 32);
      // b as a double from three exact pieces of 52, 52 and 24 bits, by the 0x4330 identity,
      // since C2 lowers no long-to-double conversion without AVX-512DQ.
      var d0 = bl.and(MASK52).or(EXP52).reinterpretAsDoubles().sub(TWO52);
      var d1 = bl.lanewise(LSHR, 52).or(bh.and(MASK40).lanewise(LSHL, 12))
          .or(EXP52).reinterpretAsDoubles().sub(TWO52);
      var d2 = bh.lanewise(LSHR, 40).or(EXP52).reinterpretAsDoubles().sub(TWO52);
      var reciprocal = one.div(d2.mul(TWO104).add(d1.mul(TWO52)).add(d0)).mul(SHRINK);
      for (int round = 0; round < ROUNDS; round++) {
        var e0 = rl.and(MASK52).or(EXP52).reinterpretAsDoubles().sub(TWO52);
        var e1 = rl.lanewise(LSHR, 52).or(rh.and(MASK40).lanewise(LSHL, 12))
            .or(EXP52).reinterpretAsDoubles().sub(TWO52);
        var e2 = rh.lanewise(LSHR, 40).or(EXP52).reinterpretAsDoubles().sub(TWO52);
        var bits = e2.mul(TWO104).add(e1.mul(TWO52)).add(e0).mul(reciprocal)
            .viewAsIntegralLanes();
        // The estimate q = m * 2^k from its bits: m << k for k >= 0, m >>> -k below, and 0 for
        // a right shift past 63, whose count the lanes would otherwise take modulo 64.
        var k = bits.lanewise(LSHR, 52).sub(1075L);
        var below = k.lanewise(ASHR, 63);
        var right = k.neg();
        var far = LongVector.broadcast(LS, 63L).sub(right).lanewise(ASHR, 63);
        var m = bits.and(MASK52).or(1L << 52);
        var shifted = m.lanewise(LSHR, right).and(far.not());
        m = m.lanewise(XOR, m.lanewise(XOR, shifted).and(below));
        var s = k.and(below.not());
        // m * b over 32-bit pieces, its low 128 bits, then shifted left by s.
        var m0 = m.and(MASK32);
        var m1 = m.lanewise(LSHR, 32);
        var p00 = m0.mul(b0);
        var p01 = m0.mul(b1);
        var p10 = m1.mul(b0);
        var p02 = m0.mul(b2);
        var p11 = m1.mul(b1);
        var p03 = m0.mul(b3);
        var p12 = m1.mul(b2);
        var c1 = p00.lanewise(LSHR, 32).add(p01.and(MASK32)).add(p10.and(MASK32));
        var c2 = p01.lanewise(LSHR, 32).add(p10.lanewise(LSHR, 32)).add(p02.and(MASK32))
            .add(p11.and(MASK32)).add(c1.lanewise(LSHR, 32));
        var c3 = p02.lanewise(LSHR, 32).add(p11.lanewise(LSHR, 32)).add(p03.and(MASK32))
            .add(p12.and(MASK32)).add(c2.lanewise(LSHR, 32));
        var pl = p00.and(MASK32).or(c1.lanewise(LSHL, 32));
        var ph = c2.and(MASK32).or(c3.lanewise(LSHL, 32));
        // s <= 74, so s >>> 6 is 1 exactly where s >= 64.
        var wide = s.lanewise(LSHR, 6).neg();
        var plS = pl.lanewise(LSHL, s);
        var ql = plS.and(wide.not());
        var qh = ph.lanewise(LSHL, s).or(pl.lanewise(LSHR, 1).lanewise(LSHR, s.neg().add(63L)));
        qh = qh.lanewise(XOR, qh.lanewise(XOR, plS).and(wide));
        var dl = rl.sub(ql);
        var borrow = rl.not().and(ql).or(rl.not().or(ql).and(dl)).lanewise(LSHR, 63);
        rl = dl;
        rh = rh.sub(qh).sub(borrow);
      }
      for (int fix = 0; fix < 2; fix++) {
        // r - b over two words; its borrow out is r < b, and r is replaced where it is not.
        var dl = rl.sub(bl);
        var borrow = rl.not().and(bl).or(rl.not().or(bl).and(dl)).lanewise(LSHR, 63);
        var dh = rh.sub(bh).sub(borrow);
        var lt = rh.not().and(bh).or(rh.not().or(bh).and(dh)).lanewise(LSHR, 63);
        var ge = lt.sub(1L);
        rl = rl.lanewise(XOR, rl.lanewise(XOR, dl).and(ge));
        rh = rh.lanewise(XOR, rh.lanewise(XOR, dh).and(ge));
      }
      // The dividend's sign put back as the magnitudes were taken, then a zero divisor's 0.
      var keep = z.sub(1L);
      var nh = rh.lanewise(XOR, mx).add(sx.and(rl.or(rl.neg()).not().lanewise(LSHR, 63)));
      rl = rl.lanewise(XOR, mx).add(sx).and(keep);
      rh = nh.and(keep);
      if (pmod) {
        // y where r < 0 and y >= 0 (a zero y left r at 0), added with its carry.
        var add = rh.and(yh.not()).lanewise(ASHR, 63);
        var al = yl.and(add);
        var sl = rl.add(al);
        var carry = rl.and(al).or(rl.or(al).and(sl.not())).lanewise(LSHR, 63);
        rh = rh.add(yh.and(add)).add(carry);
        rl = sl;
      }
      rl.intoMemorySegment(block, RL + at, LE);
      rh.intoMemorySegment(block, RH + at, LE);
      word |= z.lanewise(LSHL, iota).reduceLanes(OR) << (g * L);
    }
    return word;
  }
}
