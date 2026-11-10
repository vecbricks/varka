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

package org.apache.spark.sql;

import static org.apache.spark.sql.VarkaBenchmarkBase.require;
import static org.apache.spark.sql.VarkaBenchmarkBase.requirementFailed;

import java.io.Serializable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.SerializedLambda;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * The remainder of one 128-bit decimal by another in a Vector API loop under three division
 * forms, written by hand with no emitter (VARKA-577's spike, section 2.3 of its plan, which picks
 * the form on a measurement before the emitter is touched).
 *
 * <p>The operands are two columns of {@code DECIMAL(38, s)} slots at one scale, so the remainder
 * is that of their unscaled values: {@code x % y} has the sign of {@code x} and {@code |x % y| <
 * |y|}, and {@code pmod(x, y)} is Spark's rule over it, {@code r < 0 ? (r + y) % y : r}, which
 * adds {@code y} to a negative {@code r} when {@code y > 0} and leaves it otherwise. A zero
 * divisor sets the row's bit in the selection words (the lanes a kernel nulls or raises on) and
 * stores 0. The vector forms work in the de-interleaved pair layout VARKA-359 chose (P: the low
 * words of a lane group in one vector, the high words in another), on the magnitudes {@code a =
 * |x|} and {@code b = |y|}, both below {@code 10^38 < 2^127}, the sign put back at the end:
 * <ul>
 *   <li>E, the estimate: three rounds of a floating quotient estimate and an exact
 *       multiply-subtract, then two conditional subtractions of {@code b} (why that is exact is
 *       at {@link #bodyE}). It converts by the {@code 0x4330} identity and reads the
 *       estimate's bits, never {@code L2D} or {@code D2L}, which C2 refuses without AVX-512DQ;</li>
 *   <li>D, the long division at base 2: 127 rounds of shift, compare and conditional subtract.
 *       Knuth's algorithm D divides two digits by one per quotient digit, which no vector
 *       instruction set here has, so base 2 is the one long division with no divider;</li>
 *   <li>B, a scalar call a row, {@code BigInteger.remainder}, as per-lane {@code Math} calls
 *       are made.</li>
 * </ul>
 *
 * <p>Every loop is checked against {@code BigInteger} by {@link #checkAll} (the edge rows are at
 * {@link #row}). Each form's body is one method that both of its operations' loops call with a
 * constant {@code pmod}, so that a run which forces the bodies inline and keeps the loops out of
 * line compiles each loop as its own unit, with the other operation's code folded away.
 */
public class VarkaDecimalRemainderSpikeBenchmark {

  // ---------------------------------------------------------------------------------------
  // The species, the shuffles and the constants
  // ---------------------------------------------------------------------------------------

  private static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;
  private static final int L = LS.length();
  private static final long VB = LS.vectorByteSize();
  private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
  private static final ValueLayout.OfLong WORD = ValueLayout.JAVA_LONG.withOrder(LE);

  /** Rows of a lane group, and lane groups of a 64-row selection word. */
  private static final int GROUPS = 64 / L;

  /** The low words of two interleaved vectors, then their high words. */
  private static final VectorShuffle<Long> EVEN = VectorShuffle.fromOp(LS, i -> 2 * i);
  private static final VectorShuffle<Long> ODD = VectorShuffle.fromOp(LS, i -> 2 * i + 1);

  /** The inverse: the first and the second interleaved vector of a low and a high vector. */
  private static final VectorShuffle<Long> ZIP1 =
      VectorShuffle.fromOp(LS, j -> (j & 1) == 0 ? j / 2 : L + j / 2);
  private static final VectorShuffle<Long> ZIP2 =
      VectorShuffle.fromOp(LS, j -> (j & 1) == 0 ? L / 2 + j / 2 : L + L / 2 + j / 2);

  private static final long MASK32 = 0xFFFF_FFFFL;
  private static final long MASK40 = (1L << 40) - 1;
  private static final long MASK52 = (1L << 52) - 1;

  /** The bits of {@code 2^52}: or'ed onto {@code c < 2^52} they are the double {@code 2^52 + c}. */
  private static final long EXP52 = 0x4330_0000_0000_0000L;
  private static final double TWO52 = 0x1p52;
  private static final double TWO104 = 0x1p104;

  /** E's shrink of the estimate, {@code 1 - 2^-49}, so that it never exceeds the quotient. */
  private static final double SHRINK = 1 - 0x1p-49;

  /** E's rounds of estimate and multiply-subtract. */
  private static final int ROUNDS = 3;

  /** D's rounds: one a bit of a magnitude below {@code 2^127}. */
  private static final int BITS = 127;

  /** {@code 10^38}, Spark's largest precision's bound. */
  static final BigInteger TEN38 = BigInteger.TEN.pow(38);

  static {
    require(TEN38.bitLength() <= BITS, "10^38 is wider than " + BITS + " bits");
    require(L >= 2 && L <= 64 && DS.length() == L, L + " long lanes");
  }

  // ---------------------------------------------------------------------------------------
  // The loops. Each has the signature of Loop: the dividend and divisor slot columns, the output
  // slots, a selection word a 64 rows of the zero divisors, and the row count (a multiple of 64).
  // A loop calls its form's body with its operation's pmod as a constant, and a run that forces
  // the bodies inline folds it in, at the start of the compile where no inlining cutoff is near.
  // Past their loads the vector bodies call no helper of this class, which C2 may refuse to
  // inline in a long loop and so box every vector it passes (VARKA-359's note).
  // ---------------------------------------------------------------------------------------

  /** A loop over {@code x} and {@code y} into {@code out} and {@code words}. */
  @FunctionalInterface
  interface Loop extends Serializable {
    void apply(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n);
  }

  static void loopRemainderE(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, false);
  }

  static void loopPmodE(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, true);
  }

  static void loopRemainderD(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyD(x, y, out, words, n, false);
  }

  static void loopPmodD(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyD(x, y, out, words, n, true);
  }

  static void loopRemainderB(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyB(x, y, out, words, n, false);
  }

  static void loopPmodB(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyB(x, y, out, words, n, true);
  }

  /**
   * E: three rounds of {@code r -= q * b}, {@code q} the floor of a floating estimate of {@code r
   * / b}, from {@code r = a}; then {@code r -= b} twice where {@code r >= b}.
   *
   * <p>Why that is exact. With {@code u = 2^-53}, the conversions of {@code r} and {@code b} to
   * doubles (three exact 52-bit chunks, two additions of non-negative terms) are each within a
   * relative {@code 2u + u^2}, and the reciprocal, the shrink by {@code C = 1 - 2^-49 = 1 - 16u}
   * and the product are one rounding each. So the estimate {@code e} of {@code t = r / b} lies in
   * {@code t (1 - 23u - O(u^2))} to {@code t (1 - 9u + O(u^2))}: always below {@code t}, so
   * {@code q = floor(e) <= t} and {@code r - q * b >= 0}, and above {@code t (1 - d)} for {@code d
   * = 24u < 2^-48}, so {@code q > t (1 - d) - 1} and the new {@code r} is below {@code d r + b}.
   * From {@code r0 = a < 2^127}, after three rounds {@code r3 < d^3 2^127 + b (1 + d + d^2) <
   * 2^-17 + b (1 + 2^-47)}: below {@code 2b} when {@code b >= 2}, and at most 2 when {@code b =
   * 1}; so two conditional subtractions leave {@code r < b}, the remainder. Each round's
   * {@code q} is an integer-valued double {@code m 2^s}, {@code m < 2^53}, taken from its bits: the
   * product {@code m b} is formed modulo {@code 2^128} by seven limb products and shifted left by
   * {@code s <= 74}, exact because {@code q b <= r < 2^127}.
   */
  static void bodyE(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n,
      boolean pmod) {
    var one = DoubleVector.broadcast(DS, 1.0);
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int g = 0; g < GROUPS; g++) {
        long at = (i + g * L) * 16L;
        var xa = LongVector.fromMemorySegment(LS, x, at, LE);
        var xb = LongVector.fromMemorySegment(LS, x, at + VB, LE);
        var ya = LongVector.fromMemorySegment(LS, y, at, LE);
        var yb = LongVector.fromMemorySegment(LS, y, at + VB, LE);
        var xl = xa.rearrange(EVEN, xb);
        var xh = xa.rearrange(ODD, xb);
        var yl = ya.rearrange(EVEN, yb);
        var yh = ya.rearrange(ODD, yb);
        // The magnitudes: -v is ~v + 1, its high word -hi less the borrow of a nonzero low word.
        var sx = xh.compare(VectorOperators.LT, 0L);
        var sy = yh.compare(VectorOperators.LT, 0L);
        var zero = yh.compare(VectorOperators.EQ, 0L).and(yl.compare(VectorOperators.EQ, 0L));
        var rl = xl.blend(xl.neg(), sx);
        var rh = xh.blend(xh.neg().sub(1L, xl.compare(VectorOperators.NE, 0L)), sx);
        // A zero divisor divides by 1 and is stored as 0.
        var bl = yl.blend(yl.neg(), sy).blend(1L, zero);
        var bh = yh.blend(yh.neg().sub(1L, yl.compare(VectorOperators.NE, 0L)), sy);
        var b0 = bl.and(MASK32);
        var b1 = bl.lanewise(VectorOperators.LSHR, 32);
        var b2 = bh.and(MASK32);
        var b3 = bh.lanewise(VectorOperators.LSHR, 32);
        // b as a double: three 52-bit chunks, each exact through the 0x4330 identity.
        var d0 = bl.and(MASK52).or(EXP52).reinterpretAsDoubles().sub(TWO52);
        var d1 = bl.lanewise(VectorOperators.LSHR, 52)
            .or(bh.and(MASK40).lanewise(VectorOperators.LSHL, 12))
            .or(EXP52).reinterpretAsDoubles().sub(TWO52);
        var d2 = bh.lanewise(VectorOperators.LSHR, 40).or(EXP52).reinterpretAsDoubles().sub(TWO52);
        var reciprocal = one.div(d2.mul(TWO104).add(d1.mul(TWO52)).add(d0)).mul(SHRINK);
        for (int round = 0; round < ROUNDS; round++) {
          var e0 = rl.and(MASK52).or(EXP52).reinterpretAsDoubles().sub(TWO52);
          var e1 = rl.lanewise(VectorOperators.LSHR, 52)
              .or(rh.and(MASK40).lanewise(VectorOperators.LSHL, 12))
              .or(EXP52).reinterpretAsDoubles().sub(TWO52);
          var e2 = rh.lanewise(VectorOperators.LSHR, 40).or(EXP52).reinterpretAsDoubles()
              .sub(TWO52);
          var bits = e2.mul(TWO104).add(e1.mul(TWO52)).add(e0).mul(reciprocal)
              .viewAsIntegralLanes();
          // The estimate is m 2^k, m the mantissa with its leading bit; its floor is m << k for
          // k >= 0 and m >>> -k otherwise, a shift of 63 or more (a zero estimate too) giving 0.
          var k = bits.lanewise(VectorOperators.LSHR, 52).sub(1075L);
          var below = k.compare(VectorOperators.LT, 0L);
          var right = k.neg();
          right = right.blend(63L, right.compare(VectorOperators.GT, 63L));
          var m = bits.and(MASK52).or(1L << 52);
          m = m.blend(m.lanewise(VectorOperators.LSHR, right), below);
          var s = k.blend(0L, below);
          // m * b modulo 2^128 in 32-bit columns; m's high limb is under 2^21.
          var m0 = m.and(MASK32);
          var m1 = m.lanewise(VectorOperators.LSHR, 32);
          var p00 = m0.mul(b0);
          var p01 = m0.mul(b1);
          var p10 = m1.mul(b0);
          var p02 = m0.mul(b2);
          var p11 = m1.mul(b1);
          var p03 = m0.mul(b3);
          var p12 = m1.mul(b2);
          var c1 = p00.lanewise(VectorOperators.LSHR, 32).add(p01.and(MASK32))
              .add(p10.and(MASK32));
          var c2 = p01.lanewise(VectorOperators.LSHR, 32).add(p10.lanewise(VectorOperators.LSHR,
              32)).add(p02.and(MASK32)).add(p11.and(MASK32))
              .add(c1.lanewise(VectorOperators.LSHR, 32));
          var c3 = p02.lanewise(VectorOperators.LSHR, 32).add(p11.lanewise(VectorOperators.LSHR,
              32)).add(p03.and(MASK32)).add(p12.and(MASK32))
              .add(c2.lanewise(VectorOperators.LSHR, 32));
          var pl = p00.and(MASK32).or(c1.lanewise(VectorOperators.LSHL, 32));
          var ph = c2.and(MASK32).or(c3.lanewise(VectorOperators.LSHL, 32));
          // Shifted left by s: the counts are taken modulo 64, so at s >= 64 the low word's
          // shift by s is its shift by s - 64, and the high word's term from it is discarded.
          var wide = s.compare(VectorOperators.GE, 64L);
          var plS = pl.lanewise(VectorOperators.LSHL, s);
          var ql = plS.blend(0L, wide);
          var qh = ph.lanewise(VectorOperators.LSHL, s)
              .or(pl.lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.LSHR,
                  s.neg().add(63L)))
              .blend(plS, wide);
          var borrow = rl.compare(VectorOperators.ULT, ql);
          rl = rl.sub(ql);
          rh = rh.sub(qh).sub(1L, borrow);
        }
        for (int fix = 0; fix < 2; fix++) {
          var lt = rh.compare(VectorOperators.ULT, bh)
              .or(rh.compare(VectorOperators.EQ, bh).and(rl.compare(VectorOperators.ULT, bl)));
          var ge = lt.not();
          var borrow = rl.compare(VectorOperators.ULT, bl);
          rh = rh.blend(rh.sub(bh).sub(1L, borrow), ge);
          rl = rl.blend(rl.sub(bl), ge);
        }
        // The dividend's sign, a zero divisor's 0, and pmod's y added to a negative r.
        rh = rh.blend(rh.neg().sub(1L, rl.compare(VectorOperators.NE, 0L)), sx).blend(0L, zero);
        rl = rl.blend(rl.neg(), sx).blend(0L, zero);
        if (pmod) {
          var add = rh.compare(VectorOperators.LT, 0L).and(sy.not());
          var sl = rl.add(yl);
          var carry = sl.compare(VectorOperators.ULT, rl);
          rh = rh.blend(rh.add(yh).add(1L, carry), add);
          rl = rl.blend(sl, add);
        }
        rl.rearrange(ZIP1, rh).intoMemorySegment(out, at, LE);
        rl.rearrange(ZIP2, rh).intoMemorySegment(out, at + VB, LE);
        word |= zero.toLong() << (g * L);
      }
      words[w] = word;
    }
  }

  /**
   * D: the restoring division at base 2. The dividend is shifted left one bit at a time into
   * {@code r}, and {@code b} subtracted where {@code r >= b}; from {@code r < b} the shift leaves
   * {@code 2r + 1 < 2b < 2^128}, so no word overflows, and after the 127 bits of {@code a} the
   * quotient's bits are spent and {@code r} is the remainder.
   */
  static void bodyD(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n,
      boolean pmod) {
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int g = 0; g < GROUPS; g++) {
        long at = (i + g * L) * 16L;
        var xa = LongVector.fromMemorySegment(LS, x, at, LE);
        var xb = LongVector.fromMemorySegment(LS, x, at + VB, LE);
        var ya = LongVector.fromMemorySegment(LS, y, at, LE);
        var yb = LongVector.fromMemorySegment(LS, y, at + VB, LE);
        var xl = xa.rearrange(EVEN, xb);
        var xh = xa.rearrange(ODD, xb);
        var yl = ya.rearrange(EVEN, yb);
        var yh = ya.rearrange(ODD, yb);
        var sx = xh.compare(VectorOperators.LT, 0L);
        var sy = yh.compare(VectorOperators.LT, 0L);
        var zero = yh.compare(VectorOperators.EQ, 0L).and(yl.compare(VectorOperators.EQ, 0L));
        var al = xl.blend(xl.neg(), sx);
        var ah = xh.blend(xh.neg().sub(1L, xl.compare(VectorOperators.NE, 0L)), sx);
        var bl = yl.blend(yl.neg(), sy).blend(1L, zero);
        var bh = yh.blend(yh.neg().sub(1L, yl.compare(VectorOperators.NE, 0L)), sy);
        // a's bit 126 first: a shifted left by one, so its top bit is the next to bring down.
        var ul = al.lanewise(VectorOperators.LSHL, 1);
        var uh = ah.lanewise(VectorOperators.LSHL, 1).or(al.lanewise(VectorOperators.LSHR, 63));
        var rl = LongVector.zero(LS);
        var rh = LongVector.zero(LS);
        for (int bit = 0; bit < BITS; bit++) {
          rh = rh.lanewise(VectorOperators.LSHL, 1).or(rl.lanewise(VectorOperators.LSHR, 63));
          rl = rl.lanewise(VectorOperators.LSHL, 1).or(uh.lanewise(VectorOperators.LSHR, 63));
          uh = uh.lanewise(VectorOperators.LSHL, 1).or(ul.lanewise(VectorOperators.LSHR, 63));
          ul = ul.lanewise(VectorOperators.LSHL, 1);
          var ge = rh.compare(VectorOperators.ULT, bh)
              .or(rh.compare(VectorOperators.EQ, bh).and(rl.compare(VectorOperators.ULT, bl)))
              .not();
          var borrow = rl.compare(VectorOperators.ULT, bl);
          rh = rh.blend(rh.sub(bh).sub(1L, borrow), ge);
          rl = rl.blend(rl.sub(bl), ge);
        }
        rh = rh.blend(rh.neg().sub(1L, rl.compare(VectorOperators.NE, 0L)), sx).blend(0L, zero);
        rl = rl.blend(rl.neg(), sx).blend(0L, zero);
        if (pmod) {
          var add = rh.compare(VectorOperators.LT, 0L).and(sy.not());
          var sl = rl.add(yl);
          var carry = sl.compare(VectorOperators.ULT, rl);
          rh = rh.blend(rh.add(yh).add(1L, carry), add);
          rl = rl.blend(sl, add);
        }
        rl.rearrange(ZIP1, rh).intoMemorySegment(out, at, LE);
        rl.rearrange(ZIP2, rh).intoMemorySegment(out, at + VB, LE);
        word |= zero.toLong() << (g * L);
      }
      words[w] = word;
    }
  }

  /** B: a scalar call a row, {@link #remainderBig}. */
  static void bodyB(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n,
      boolean pmod) {
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int k = 0; k < 64; k++) {
        long at = (i + k) * 16L;
        boolean zero = remainderBig(x.get(WORD, at + 8), x.get(WORD, at), y.get(WORD, at + 8),
            y.get(WORD, at), pmod, out, at);
        word |= (zero ? 1L : 0L) << k;
      }
      words[w] = word;
    }
  }

  private static final BigInteger WORD_MASK = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);

  /** The value of the two words {@code hi} and {@code lo}, signed. */
  private static BigInteger value(long hi, long lo) {
    return BigInteger.valueOf(hi).shiftLeft(64).or(BigInteger.valueOf(lo).and(WORD_MASK));
  }

  /** One row's remainder, stored at {@code at} (0 for a zero divisor); whether {@code y} is 0. */
  static boolean remainderBig(long xh, long xl, long yh, long yl, boolean pmod,
      MemorySegment out, long at) {
    BigInteger d = value(yh, yl);
    BigInteger r = BigInteger.ZERO;
    if (d.signum() != 0) {
      r = value(xh, xl).remainder(d);
      if (pmod && r.signum() < 0) {
        r = r.add(d).remainder(d);
      }
    }
    out.set(WORD, at, r.longValue());
    out.set(WORD, at + 8, r.shiftRight(64).longValue());
    return d.signum() == 0;
  }

  // ---------------------------------------------------------------------------------------
  // The operations, the forms and the arms
  // ---------------------------------------------------------------------------------------

  /** The two operations, {@code x % y} and {@code pmod(x, y)}. */
  enum Op {
    REMAINDER("x % y", false),
    PMOD("pmod(x, y)", true);

    final String title;
    final boolean pmod;

    Op(String title, boolean pmod) {
      this.title = title;
      this.pmod = pmod;
    }
  }

  /** The three forms of section 2.3: (a), (b) and (c). */
  enum Form { E, D, B }

  /** One arm: an operation in a form, the loop's method name, the loop. */
  record Arm(Op op, Form form, String method, Loop loop) {

    Arm(Op op, Form form, Loop loop) {
      this(op, form, methodOf(loop), loop);
    }

    String label() {
      return op.title + ", " + form;
    }
  }

  /** The name of the static method a method reference to one of this class's loops names. */
  private static String methodOf(Loop loop) {
    try {
      var writeReplace = loop.getClass().getDeclaredMethod("writeReplace");
      writeReplace.setAccessible(true);
      return ((SerializedLambda) writeReplace.invoke(loop)).getImplMethodName();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read the method a loop reference names", e);
    }
  }

  /** Every arm, an operation's three forms in turn. */
  static List<Arm> arms() {
    return List.of(
        new Arm(Op.REMAINDER, Form.E, VarkaDecimalRemainderSpikeBenchmark::loopRemainderE),
        new Arm(Op.REMAINDER, Form.D, VarkaDecimalRemainderSpikeBenchmark::loopRemainderD),
        new Arm(Op.REMAINDER, Form.B, VarkaDecimalRemainderSpikeBenchmark::loopRemainderB),
        new Arm(Op.PMOD, Form.E, VarkaDecimalRemainderSpikeBenchmark::loopPmodE),
        new Arm(Op.PMOD, Form.D, VarkaDecimalRemainderSpikeBenchmark::loopPmodD),
        new Arm(Op.PMOD, Form.B, VarkaDecimalRemainderSpikeBenchmark::loopPmodB));
  }

  // ---------------------------------------------------------------------------------------
  // The data and the BigInteger answers
  // ---------------------------------------------------------------------------------------

  private static final BigInteger MAX38 = TEN38.subtract(BigInteger.ONE);

  /**
   * The columns of one rung, in native memory as a kernel finds them, and each operation's
   * answers, computed as the rows are drawn so that no row's values outlive it.
   */
  record Data(int numRows, MemorySegment x, MemorySegment y, Map<Op, Expected> expected)
      implements AutoCloseable {
    @Override
    public void close() {
      expected.values().forEach(Expected::close);
    }
  }

  /**
   * What an arm of an operation must leave, the slots and the zero divisors' words, in an arena
   * of its own, closed once the operation's arms are done.
   */
  record Expected(Arena arena, MemorySegment out, long[] words) implements AutoCloseable {
    @Override
    public void close() {
      if (arena.scope().isAlive()) {
        arena.close();
      }
    }
  }

  /** A value of 1 to {@code digits} digits, either sign. */
  private static BigInteger anyValue(Random r, int digits) {
    BigInteger v = new BigInteger(130, r).mod(BigInteger.TEN.pow(1 + r.nextInt(digits)));
    return r.nextBoolean() ? v.negate() : v;
  }

  private static BigInteger signed(Random r, BigInteger v) {
    return r.nextBoolean() ? v.negate() : v;
  }

  /**
   * Row {@code i}'s dividend and divisor, below {@code 10^38}: among every 32 rows the edges, a
   * zero divisor, divisors of 1, {@code 10^k}, {@code 2^k}, {@code 2^k +- 1} and the extremes,
   * the dividend and its negation, exact multiples and remainders of {@code |y| - 1}; otherwise
   * two values of any digit count.
   */
  static BigInteger[] row(int i, Random r) {
    BigInteger x = anyValue(r, 38);
    BigInteger y = anyValue(r, 38);
    switch (i % 32) {
      case 0 -> {
        x = signed(r, MAX38);
        y = signed(r, BigInteger.ONE);
      }
      case 1 -> y = BigInteger.ZERO;
      case 2 -> y = x;
      case 3 -> y = x.signum() == 0 ? BigInteger.ONE : x.negate();
      case 4 -> y = signed(r, BigInteger.TEN.pow(r.nextInt(38)));
      case 5 -> x = signed(r, MAX38);
      case 6 -> y = signed(r, MAX38);
      case 7, 8 -> {
        // An exact multiple of y, or one short of the next, so the remainder is 0 or |y| - 1.
        y = anyValue(r, 37);
        y = y.signum() == 0 ? BigInteger.TWO : y;
        BigInteger q = new BigInteger(127, r).mod(MAX38.divide(y.abs()));
        BigInteger m = q.multiply(y.abs());
        x = signed(r, i % 32 == 7 ? m : m.add(y.abs()).subtract(BigInteger.ONE));
      }
      case 9 -> y = signed(r, BigInteger.ONE.shiftLeft(r.nextInt(BITS)));
      case 10 -> y = signed(r, BigInteger.ONE.shiftLeft(1 + r.nextInt(BITS - 1))
          .add(BigInteger.valueOf(r.nextBoolean() ? 1 : -1)));
      case 11 -> y = signed(r, new BigInteger(62, r).add(BigInteger.ONE).shiftLeft(64));
      case 12 -> x = anyValue(r, 18);
      case 13 -> y = anyValue(r, 18);
      default -> { }
    }
    return new BigInteger[] {x, y};
  }

  private static void setSlot(MemorySegment s, int row, BigInteger v) {
    s.set(WORD, row * 16L, v.longValue());
    s.set(WORD, row * 16L + 8, v.shiftRight(64).longValue());
  }

  /** The signed value of the slot at {@code row}. */
  static BigInteger slot(MemorySegment s, int row) {
    return value(s.get(WORD, row * 16L + 8), s.get(WORD, row * 16L));
  }

  /** The columns of {@code n} rows, a multiple of 64, and the answers to both operations. */
  static Data data(Arena arena, int n) {
    require(n % 64 == 0, n + " rows is not a multiple of 64");
    var r = new Random(577);
    var x = arena.allocate(n * 16L, 64);
    var y = arena.allocate(n * 16L, 64);
    var expected = new EnumMap<Op, Expected>(Op.class);
    for (Op op : Op.values()) {
      Arena own = Arena.ofConfined();
      expected.put(op, new Expected(own, own.allocate(n * 16L, 64), new long[n / 64]));
    }
    for (int i = 0; i < n; i++) {
      BigInteger[] pair = row(i, r);
      if (pair[0].abs().compareTo(TEN38) >= 0 || pair[1].abs().compareTo(TEN38) >= 0) {
        throw requirementFailed("row " + i + " is out of DECIMAL(38)");
      }
      setSlot(x, i, pair[0]);
      setSlot(y, i, pair[1]);
      for (Op op : Op.values()) {
        answer(op, expected.get(op), i, pair[0], pair[1]);
      }
    }
    return new Data(n, x, y, expected);
  }

  /**
   * Row {@code i}'s answer to {@code op} by Spark's rules, written apart from {@link
   * #remainderBig}: {@code x % y} is {@code BigInteger.remainder}, which keeps the dividend's sign
   * as {@code BigDecimal.remainder} does, and {@code pmod} is {@code r < 0 ? (r + y) % y : r}.
   */
  private static void answer(Op op, Expected e, int i, BigInteger x, BigInteger y) {
    if (y.signum() == 0) {
      e.words()[i >>> 6] |= 1L << (i & 63);
      setSlot(e.out(), i, BigInteger.ZERO);
      return;
    }
    BigInteger r = x.remainder(y);
    setSlot(e.out(), i, op.pmod && r.signum() < 0 ? r.add(y).remainder(y) : r);
  }

  /** Whether {@code arm}'s last run left what {@code e} says; a wrong row is named. */
  static void requireAgrees(Arm arm, Expected e, MemorySegment out, long[] words, int n,
      String when) {
    for (int w = 0; w < n / 64; w++) {
      if (words[w] != e.words()[w]) {
        int row = w * 64 + Long.numberOfTrailingZeros(words[w] ^ e.words()[w]);
        throw new IllegalStateException(arm.label() + " disagrees with BigInteger " + when
            + " on the zero divisor of row " + row);
      }
    }
    long at = out.asSlice(0, n * 16L).mismatch(e.out().asSlice(0, n * 16L));
    if (at >= 0) {
      int row = (int) (at / 16);
      throw new IllegalStateException(arm.label() + " disagrees with BigInteger " + when
          + " at row " + row + ": " + slot(out, row) + " for " + slot(e.out(), row));
    }
  }

  /**
   * Runs every arm once over {@code n} rows and checks it against {@code BigInteger}, what
   * {@code VarkaDecimalRemainderSpikeSuite} asserts.
   */
  static void checkAll(int n) {
    try (Arena arena = Arena.ofConfined(); Data d = data(arena, n)) {
      var out = arena.allocate(n * 16L, 64);
      for (Op op : Op.values()) {
        try (Expected e = d.expected().get(op)) {
          for (Arm arm : arms()) {
            if (arm.op() == op) {
              // Cleared first: a loop that wrote nothing would otherwise pass on the previous
              // arm's bytes.
              out.fill((byte) 0);
              long[] words = new long[n / 64];
              arm.loop().apply(d.x(), d.y(), out, words, n);
              requireAgrees(arm, e, out, words, n, "at " + n + " rows");
            }
          }
        }
      }
    }
  }
}
