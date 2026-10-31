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

import java.io.Serializable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.SerializedLambda;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.ObjectName;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport;

/**
 * What a {@code DECIMAL} of precision 19 to 38 costs in a Vector API loop under the three ways
 * its sixteen-byte Arrow slots can be computed on, written by hand with no emitter (VARKA-359's
 * spike, section 2.1 of its plan, which picks the lane pair's representation on a measurement
 * before the emitter is touched).
 *
 * <p>The three forms:
 * <ul>
 *   <li>P, the de-interleaved pair: two vectors of slots loaded, the low words taken by a
 *       two-vector {@code rearrange} of the even lanes and the high words by the odd one, one row
 *       a lane in each, as row 347's load does; every operator lanewise, a borrow a compare in the
 *       same lane; a stored value interleaved back by the two zip shuffles.</li>
 *   <li>A, adjacent lanes: the slots as they lie, {@code (lo0, hi0, lo1, hi1, ...)}, half a row a
 *       lane, no shuffle at the load or the store. A compare XORs the high lanes' sign bit so one
 *       unsigned compare serves both words, and combines each even lane's result with its odd
 *       neighbour's in the mask's bits (Highway's {@code Lt128}, `READING_HIGHWAY.md` 3.5, here on
 *       the {@code toLong} word rather than with a lane shift); a row's condition is the odd bit
 *       of its two lanes, gathered by {@code Long.compress}; a borrow moves up a lane as a mask
 *       shifted by one bit.</li>
 *   <li>S, the scalar loop: one row at a time, the two words read at {@code 16 * i} and computed
 *       in {@code long}s, {@code Long.compareUnsigned}, {@code Math.multiplyHigh} and
 *       {@code Math.unsignedMultiplyHigh}.</li>
 * </ul>
 *
 * <p>The loops, over two columns {@code x} and {@code y} of {@code DECIMAL(38, s)} slots:
 * <ol>
 *   <li>CHECK: the load with its precision check alone, a bit a row set where {@code |x| >=
 *       10^38}, over a column one row in sixteen of which is out of precision;</li>
 *   <li>LT: {@code x < y} into a selection word, a bit a row;</li>
 *   <li>NEG and ABS: {@code -x} and {@code abs(x)} stored into slots;</li>
 *   <li>CHOICE: {@code CASE WHEN x < y THEN x ELSE y END} stored into slots;</li>
 *   <li>PRODUCT: the 128 by 128 product into 256 bits, stored as four word columns. P is the
 *       column sum of sixteen 32 by 32 limb products (row 362's form C, the multiplies written so
 *       that C2 can emit {@code vpmuludq}), the operands' signs folded into the upper columns
 *       (362's {@code TWOS}); S is {@code multiplyHigh} and {@code unsignedMultiplyHigh};</li>
 *   <li>PRODUCT_CHECK: the product's {@code 10^38} check, a bit a row set where {@code |x * y| >=
 *       10^38}, over the four word columns of the product, so its cost is the check's alone;</li>
 *   <li>RESCALE: {@code HALF_UP} of {@code x / 10^6} stored into slots, by row 363's route (2.3 of
 *       its plan): the magnitude pre-shifted right by 5, multiplied by {@code c = ceil(2^135 /
 *       5^6)}, the product's bits from 135 up kept, then {@code (q + 1) >>> 1} and the sign put
 *       back. P computes the product by sixteen limb products, S by four 64-bit ones.</li>
 * </ol>
 * The first four are timed in all three forms; the three arithmetic loops in P and S only, as
 * section 2.1 names them, since an adjacent-lane product has every limb product cross a lane.
 *
 * <p>The two arithmetic loops of P are long enough that C2 passes its parse-time node cutoff
 * for inlining ({@code NodeCountInliningCutoff}, 18000 nodes, a develop flag) partway through
 * them: a call to one of this class's helpers after that point is refused with that reason, stays
 * a call, and boxes every vector it passes, so the loop runs many times slower than its
 * operations. So past their loads those loops call no helper of this class; the Vector API's own
 * methods are inlined late, under the live-node cutoff, and are not refused.
 * {@code -XX:CompileCommand=PrintInlining} shows it.
 *
 * <p>Every loop's output is checked against {@code BigInteger} before it is timed, and again in
 * the compiled code after the drive. The columns hold every digit count from 1 to 38 at both
 * signs, the extremes {@code +-(10^38 - 1)}, slots whose low word is zero (the negation's
 * borrow), rows where {@code y} equals {@code x} or shares its high word, and {@code HALF_UP}
 * ties; about half the products are past {@code 10^38}.
 *
 * <p>Every arm is driven to compiled code at its own turn, just before it is timed, as row 329's
 * spike does: at least eight drives, then two consecutive drives within 30 percent, C2 idle after
 * each, and a loop method that {@code Compiler.codelist} shows at level 4. An arm that does not
 * settle in 30 seconds says so on the console and is timed as it is; at 128 bits on x86 every P
 * and A loop is the Java fallback (`VARKA-359.md` 0, item 9) and may never settle. Row counts are
 * multiples of 64, so no loop has a tail.
 *
 * <p>Run it with {@code -XX:CompileCommand=dontinline,<this class>::loop*} so that each loop is a
 * compile unit of its own, at {@code -XX:MaxVectorSize=64}, {@code 32} and {@code 16} for the
 * three widths; the run refuses to start without that command. {@code -Dvarka.pair.forms=p,a,s}
 * (a subset) and {@code -Dvarka.pair.rungs=l2,l3,pastl3} (a subset) narrow a run; {@code
 * -Dvarka.bench.only} narrows it to the arms whose label holds a string. Each is for
 * investigation only, refused when {@code SPARK_GENERATE_BENCHMARK_FILES} would write the results
 * file, and a selection that leaves no arm fails the run.
 *
 * <pre>
 *   build/sbt 'set Test/javaOptions ++= Seq("-XX:MaxVectorSize=32", "-XX:CompileCommand=\
 *     dontinline,org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark::loop*")' \
 *     'catalyst/Test/runMain org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark'
 * </pre>
 */
public class VarkaDecimalPairSpikeBenchmark extends VarkaBenchmarkBase {

  // ---------------------------------------------------------------------------------------
  // The ladder and the harness's constants
  // ---------------------------------------------------------------------------------------

  /**
   * A rung of the ladder: its name for {@code -Dvarka.pair.rungs}, a row count, and what the two
   * input columns of sixteen bytes a row and the largest output, a product's thirty-two, take.
   */
  private record Rung(String name, int numRows, String level) {}

  /**
   * The rungs, by the working set of the largest arm, PRODUCT's: two input columns of 16 bytes a
   * row and a product of 32, 64 bytes a row. At 4096 rows that is 64 * 4096 = 256 KB, half the
   * 512 KB L2 of an EPYC 7763 core and a quarter of the laptop's 1 MB, so the rung stays in L2
   * with room for the stack, the selection words and the hardware prefetcher's overshoot (8192
   * rows would fill the 7763's L2 exactly). Of 262144 rows it is 16 MB, within the 32 MB L3 of an
   * EPYC 7763 core complex and at the edge of the laptop's 16 MB; of 4194304 rows 256 MB, past
   * either.
   *
   * <p>A rung allocates more native memory than its largest arm touches, 240 bytes a row: the
   * columns {@code x}, {@code y} and {@code wild} of 16 bytes a row and the product's four words
   * of 32 (80 in all); one output for each operation that stores, reused by its arms, 16 bytes a
   * row for each of the four that store slots and 32 for PRODUCT (96); and the {@code BigInteger}
   * answer of each of those four, 16 bytes a row each (64). The outputs and answers live until
   * the rung's cases have run, so at 4194304 rows the rung holds 960 MB.
   */
  private static final List<Rung> LADDER = List.of(
      new Rung("l2", 4096, "in L2, 256 KB"),
      new Rung("l3", 262144, "in L3, 16 MB"),
      new Rung("pastl3", 4194304, "past L3, 256 MB"));

  private static final String RUNGS_PROPERTY = "varka.pair.rungs";
  private static final String FORMS_PROPERTY = "varka.pair.forms";

  /** Rows a timed iteration covers, so that one is long enough for a millisecond clock. */
  private static final long ROWS_PER_ITERATION = 1L << 24;

  private static final int MIN_WARM_DRIVES = 8;
  private static final double STEADY_FRACTION = 0.3;
  private static final long WARM_WAIT_MILLIS = 30_000;
  private static final long IDLE_WAIT_MILLIS = 10_000;

  // ---------------------------------------------------------------------------------------
  // The species, the shuffles and the constants
  // ---------------------------------------------------------------------------------------

  private static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
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

  /** The lanes of a vector's mask word: all, the even ones (low words), the odd ones (high). */
  private static final long LANES = L == 64 ? -1L : (1L << L) - 1;
  private static final long EVEN_BITS = 0x5555_5555_5555_5555L & LANES;
  private static final long ODD_BITS = 0xAAAA_AAAA_AAAA_AAAAL & LANES;

  /** A's sign flip: the sign bit in the odd lanes, so the high words compare unsigned. */
  private static final LongVector SIGN_ODD = LongVector.fromArray(LS, alternate(0, Long.MIN_VALUE),
      0);

  private static final long MASK32 = 0xFFFF_FFFFL;

  /** {@code 10^38}, Spark's largest precision's bound, and its negation, as 128-bit words. */
  static final BigInteger TEN38 = BigInteger.TEN.pow(38);
  private static final long BH = TEN38.shiftRight(64).longValue();
  private static final long BL = TEN38.longValue();
  private static final long NBH = TEN38.negate().shiftRight(64).longValue();
  private static final long NBL = TEN38.negate().longValue();

  /** The two bounds interleaved as A holds them, the high lanes' sign flipped. */
  private static final LongVector A_B = LongVector.fromArray(LS,
      alternate(BL, BH ^ Long.MIN_VALUE), 0);
  private static final LongVector A_NB = LongVector.fromArray(LS,
      alternate(NBL, NBH ^ Long.MIN_VALUE), 0);

  /** The two bounds' words broadcast, as P compares a lane group's high and low words. */
  private static final LongVector P_BH = LongVector.broadcast(LS, BH);
  private static final LongVector P_BL = LongVector.broadcast(LS, BL);
  private static final LongVector P_NBH = LongVector.broadcast(LS, NBH);
  private static final LongVector P_NBL = LongVector.broadcast(LS, NBL);

  /**
   * The rescale's constants (`VARKA-363.md` 2.3; `results/VARKA-363-magic.txt`, the row
   * {@code k = 6} of a 128-bit dividend): the magnitude {@code n <= 10^38 - 1} shifted right by
   * {@code k - 1 = 5} is {@code N}, and {@code floor(N / 5^6) = floor(N * c / 2^135)} with
   * {@code c = ceil(2^135 / 5^6)}. The class's initializer checks the sufficient condition
   * {@code (c * 5^6 - 2^135) * Nmax < 2^135} itself.
   */
  static final int RESCALE_K = 6;
  private static final int PRE_SHIFT = RESCALE_K - 1;
  private static final int MAGIC_L = 135;
  private static final BigInteger FIVE_K = BigInteger.valueOf(5).pow(RESCALE_K);
  private static final BigInteger MAGIC = BigInteger.ONE.shiftLeft(MAGIC_L)
      .add(FIVE_K).subtract(BigInteger.ONE).divide(FIVE_K);
  private static final long CL = MAGIC.longValue();
  private static final long CH = MAGIC.shiftRight(64).longValue();
  private static final long C0 = CL & MASK32;
  private static final long C1 = CL >>> 32;
  private static final long C2 = CH & MASK32;
  private static final long C3 = CH >>> 32;

  static {
    BigInteger nMax = TEN38.subtract(BigInteger.ONE).shiftRight(PRE_SHIFT);
    BigInteger e = MAGIC.multiply(FIVE_K).subtract(BigInteger.ONE.shiftLeft(MAGIC_L));
    require(e.multiply(nMax).compareTo(BigInteger.ONE.shiftLeft(MAGIC_L)) < 0,
        "the rescale's magic constant does not meet its bound");
    require(MAGIC.bitLength() <= 128, "the rescale's magic constant is wider than two words");
    require(L >= 2 && L <= 64, L + " long lanes");
  }

  private static long[] alternate(long even, long odd) {
    long[] out = new long[L];
    for (int j = 0; j < L; j++) {
      out[j] = (j & 1) == 0 ? even : odd;
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------
  // P's lanewise helpers, inlined into the loops
  // ---------------------------------------------------------------------------------------

  private static LongVector load(MemorySegment s, long offset) {
    return LongVector.fromMemorySegment(LS, s, offset, LE);
  }

  /** The signed 128-bit {@code x < y}: the high words signed, the low words unsigned. */
  private static VectorMask<Long> lt128(LongVector xh, LongVector xl, LongVector yh,
      LongVector yl) {
    return xh.compare(VectorOperators.LT, yh)
        .or(xh.compare(VectorOperators.EQ, yh).and(xl.compare(VectorOperators.ULT, yl)));
  }

  /** The rows of a lane group outside {@code (-10^38, 10^38)}. */
  private static VectorMask<Long> outOfPrecision(LongVector hi, LongVector lo) {
    return lt128(hi, lo, P_BH, P_BL).and(lt128(P_NBH, P_NBL, hi, lo)).not();
  }

  /** The high word of {@code -x}: {@code -hi}, less the borrow of a nonzero low word. */
  private static LongVector negHigh(LongVector hi, LongVector lo) {
    return hi.neg().sub(1L, lo.compare(VectorOperators.NE, 0L));
  }

  private static void storePair(MemorySegment out, long at, LongVector lo, LongVector hi) {
    lo.rearrange(ZIP1, hi).intoMemorySegment(out, at, LE);
    lo.rearrange(ZIP2, hi).intoMemorySegment(out, at + VB, LE);
  }

  // ---------------------------------------------------------------------------------------
  // A's helpers: a mask word of one interleaved vector, its odd bits a row's
  // ---------------------------------------------------------------------------------------

  /**
   * The odd bits of {@code a < b} for interleaved, sign-flipped {@code a} and {@code b}: the high
   * lane's unsigned less, or its equality and the low lane's unsigned less.
   */
  private static long ltOdd(LongVector a, LongVector b) {
    long ult = a.compare(VectorOperators.ULT, b).toLong();
    long eq = a.compare(VectorOperators.EQ, b).toLong();
    return (ult | (eq & (ult << 1))) & ODD_BITS;
  }

  /** The odd bits spread to both lanes of their row: the mask of a blend by row. */
  private static VectorMask<Long> byRow(long odd) {
    return VectorMask.fromLong(LS, odd | (odd >>> 1));
  }

  /** {@code -v} of interleaved {@code v}: each high lane less the borrow of its low lane. */
  private static LongVector negAdjacent(LongVector v) {
    long nonzeroLow = v.compare(VectorOperators.NE, 0L).toLong() & EVEN_BITS;
    return v.neg().sub(1L, VectorMask.fromLong(LS, nonzeroLow << 1));
  }

  // ---------------------------------------------------------------------------------------
  // The loops. Each has the signature of Loop: two slot columns (or the product's four word
  // columns), an output segment, the output words, and the row count (a multiple of 64).
  // ---------------------------------------------------------------------------------------

  /** A loop over {@code x} and {@code y} into {@code out} or {@code words}. */
  @FunctionalInterface
  interface Loop extends Serializable {
    void apply(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n);
  }

  // Loop 1, CHECK: a bit a row set where |x| >= 10^38.

  static void loopCheckP(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int g = 0; g < GROUPS; g++) {
        long at = (i + g * L) * 16L;
        var a = load(x, at);
        var b = load(x, at + VB);
        word |= outOfPrecision(a.rearrange(ODD, b), a.rearrange(EVEN, b)).toLong() << (g * L);
      }
      words[w] = word;
    }
  }

  static void loopCheckA(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int g = 0; g < GROUPS; g++) {
        long at = (i + g * L) * 16L;
        for (int h = 0; h < 2; h++) {
          var v = load(x, at + h * VB).lanewise(VectorOperators.XOR, SIGN_ODD);
          long ok = ltOdd(v, A_B) & ltOdd(A_NB, v);
          word |= Long.compress(~ok & ODD_BITS, ODD_BITS) << (g * L + h * (L / 2));
        }
      }
      words[w] = word;
    }
  }

  static void loopCheckS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int k = 0; k < 64; k++) {
        long at = (i + k) * 16L;
        long lo = x.get(WORD, at);
        long hi = x.get(WORD, at + 8);
        boolean below = hi < BH || (hi == BH && Long.compareUnsigned(lo, BL) < 0);
        boolean above = NBH < hi || (NBH == hi && Long.compareUnsigned(NBL, lo) < 0);
        word |= (below && above ? 0L : 1L) << k;
      }
      words[w] = word;
    }
  }

  // Loop 2, LT: x < y into a selection word.

  static void loopLtP(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n) {
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int g = 0; g < GROUPS; g++) {
        long at = (i + g * L) * 16L;
        var xa = load(x, at);
        var xb = load(x, at + VB);
        var ya = load(y, at);
        var yb = load(y, at + VB);
        word |= lt128(xa.rearrange(ODD, xb), xa.rearrange(EVEN, xb), ya.rearrange(ODD, yb),
            ya.rearrange(EVEN, yb)).toLong() << (g * L);
      }
      words[w] = word;
    }
  }

  static void loopLtA(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n) {
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int g = 0; g < GROUPS; g++) {
        long at = (i + g * L) * 16L;
        for (int h = 0; h < 2; h++) {
          var xv = load(x, at + h * VB).lanewise(VectorOperators.XOR, SIGN_ODD);
          var yv = load(y, at + h * VB).lanewise(VectorOperators.XOR, SIGN_ODD);
          word |= Long.compress(ltOdd(xv, yv), ODD_BITS) << (g * L + h * (L / 2));
        }
      }
      words[w] = word;
    }
  }

  static void loopLtS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n) {
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int k = 0; k < 64; k++) {
        long at = (i + k) * 16L;
        long xh = x.get(WORD, at + 8);
        long yh = y.get(WORD, at + 8);
        boolean lt = xh < yh
            || (xh == yh && Long.compareUnsigned(x.get(WORD, at), y.get(WORD, at)) < 0);
        word |= (lt ? 1L : 0L) << k;
      }
      words[w] = word;
    }
  }

  // Loop 3, NEG and ABS: -x and abs(x) stored.

  static void loopNegP(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i += L) {
      long at = i * 16L;
      var a = load(x, at);
      var b = load(x, at + VB);
      var lo = a.rearrange(EVEN, b);
      var hi = a.rearrange(ODD, b);
      storePair(out, at, lo.neg(), negHigh(hi, lo));
    }
  }

  static void loopNegA(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i += L / 2) {
      long at = i * 16L;
      negAdjacent(load(x, at)).intoMemorySegment(out, at, LE);
    }
  }

  static void loopNegS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i++) {
      long at = i * 16L;
      long lo = x.get(WORD, at);
      long hi = x.get(WORD, at + 8);
      out.set(WORD, at, -lo);
      out.set(WORD, at + 8, -hi - (lo != 0 ? 1 : 0));
    }
  }

  static void loopAbsP(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i += L) {
      long at = i * 16L;
      var a = load(x, at);
      var b = load(x, at + VB);
      var lo = a.rearrange(EVEN, b);
      var hi = a.rearrange(ODD, b);
      var negative = hi.compare(VectorOperators.LT, 0L);
      storePair(out, at, lo.blend(lo.neg(), negative), hi.blend(negHigh(hi, lo), negative));
    }
  }

  static void loopAbsA(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i += L / 2) {
      long at = i * 16L;
      var v = load(x, at);
      long negative = v.compare(VectorOperators.LT, 0L).toLong() & ODD_BITS;
      v.blend(negAdjacent(v), byRow(negative)).intoMemorySegment(out, at, LE);
    }
  }

  static void loopAbsS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i++) {
      long at = i * 16L;
      long lo = x.get(WORD, at);
      long hi = x.get(WORD, at + 8);
      if (hi < 0) {
        hi = -hi - (lo != 0 ? 1 : 0);
        lo = -lo;
      }
      out.set(WORD, at, lo);
      out.set(WORD, at + 8, hi);
    }
  }

  // Loop 4, CHOICE: CASE WHEN x < y THEN x ELSE y END stored.

  static void loopChoiceP(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i += L) {
      long at = i * 16L;
      var xa = load(x, at);
      var xb = load(x, at + VB);
      var ya = load(y, at);
      var yb = load(y, at + VB);
      var xl = xa.rearrange(EVEN, xb);
      var xh = xa.rearrange(ODD, xb);
      var yl = ya.rearrange(EVEN, yb);
      var yh = ya.rearrange(ODD, yb);
      var lt = lt128(xh, xl, yh, yl);
      storePair(out, at, yl.blend(xl, lt), yh.blend(xh, lt));
    }
  }

  static void loopChoiceA(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i += L / 2) {
      long at = i * 16L;
      var xv = load(x, at);
      var yv = load(y, at);
      long lt = ltOdd(xv.lanewise(VectorOperators.XOR, SIGN_ODD),
          yv.lanewise(VectorOperators.XOR, SIGN_ODD));
      yv.blend(xv, byRow(lt)).intoMemorySegment(out, at, LE);
    }
  }

  static void loopChoiceS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i++) {
      long at = i * 16L;
      long xl = x.get(WORD, at);
      long xh = x.get(WORD, at + 8);
      long yl = y.get(WORD, at);
      long yh = y.get(WORD, at + 8);
      boolean lt = xh < yh || (xh == yh && Long.compareUnsigned(xl, yl) < 0);
      out.set(WORD, at, lt ? xl : yl);
      out.set(WORD, at + 8, lt ? xh : yh);
    }
  }

  // Loop 5, PRODUCT: x * y in 256 bits, word k of row r at k * n * 8 + r * 8.

  static void loopProductP(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    long column = n * 8L;
    for (int i = 0; i < n; i += L) {
      long at = i * 16L;
      var xa = load(x, at);
      var xb = load(x, at + VB);
      var ya = load(y, at);
      var yb = load(y, at + VB);
      var xl = xa.rearrange(EVEN, xb);
      var xh = xa.rearrange(ODD, xb);
      var yl = ya.rearrange(EVEN, yb);
      var yh = ya.rearrange(ODD, yb);
      // The limbs: each masked to 32 bits or shifted right by 32, so C2 sees a 32-bit input.
      var x0 = xl.and(MASK32);
      var x1 = xl.lanewise(VectorOperators.LSHR, 32);
      var x2 = xh.and(MASK32);
      var x3 = xh.lanewise(VectorOperators.LSHR, 32);
      var y0 = yl.and(MASK32);
      var y1 = yl.lanewise(VectorOperators.LSHR, 32);
      var y2 = yh.and(MASK32);
      var y3 = yh.lanewise(VectorOperators.LSHR, 32);
      var p00 = x0.mul(y0);
      var p01 = x0.mul(y1);
      var p10 = x1.mul(y0);
      var p02 = x0.mul(y2);
      var p11 = x1.mul(y1);
      var p20 = x2.mul(y0);
      var p03 = x0.mul(y3);
      var p12 = x1.mul(y2);
      var p21 = x2.mul(y1);
      var p30 = x3.mul(y0);
      var p13 = x1.mul(y3);
      var p22 = x2.mul(y2);
      var p31 = x3.mul(y1);
      var p23 = x2.mul(y3);
      var p32 = x3.mul(y2);
      var p33 = x3.mul(y3);
      // The low and the high half of each limb product.
      var l00 = p00.and(MASK32);
      var h00 = p00.lanewise(VectorOperators.LSHR, 32);
      var l01 = p01.and(MASK32);
      var h01 = p01.lanewise(VectorOperators.LSHR, 32);
      var l10 = p10.and(MASK32);
      var h10 = p10.lanewise(VectorOperators.LSHR, 32);
      var l02 = p02.and(MASK32);
      var h02 = p02.lanewise(VectorOperators.LSHR, 32);
      var l11 = p11.and(MASK32);
      var h11 = p11.lanewise(VectorOperators.LSHR, 32);
      var l20 = p20.and(MASK32);
      var h20 = p20.lanewise(VectorOperators.LSHR, 32);
      var l03 = p03.and(MASK32);
      var h03 = p03.lanewise(VectorOperators.LSHR, 32);
      var l12 = p12.and(MASK32);
      var h12 = p12.lanewise(VectorOperators.LSHR, 32);
      var l21 = p21.and(MASK32);
      var h21 = p21.lanewise(VectorOperators.LSHR, 32);
      var l30 = p30.and(MASK32);
      var h30 = p30.lanewise(VectorOperators.LSHR, 32);
      var l13 = p13.and(MASK32);
      var h13 = p13.lanewise(VectorOperators.LSHR, 32);
      var l22 = p22.and(MASK32);
      var h22 = p22.lanewise(VectorOperators.LSHR, 32);
      var l31 = p31.and(MASK32);
      var h31 = p31.lanewise(VectorOperators.LSHR, 32);
      var l23 = p23.and(MASK32);
      var h23 = p23.lanewise(VectorOperators.LSHR, 32);
      var l32 = p32.and(MASK32);
      var h32 = p32.lanewise(VectorOperators.LSHR, 32);
      var l33 = p33.and(MASK32);
      var h33 = p33.lanewise(VectorOperators.LSHR, 32);
      // Column c gets the low halves of the products x_i * y_j with i + j = c and the high
      // halves of those with i + j = c - 1; the carries come after the signs.
      var c0 = l00;
      var c1 = h00.add(l01).add(l10);
      var c2 = h01.add(h10).add(l02).add(l11).add(l20);
      var c3 = h02.add(h11).add(h20).add(l03).add(l12).add(l21).add(l30);
      var c4 = h03.add(h12).add(h21).add(h30).add(l13).add(l22).add(l31);
      var c5 = h13.add(h22).add(h31).add(l23).add(l32);
      var c6 = h23.add(h32).add(l33);
      var c7 = h33;
      // The signs (362's TWOS): X * Y = Xu * Yu - 2^128 ([X < 0] Yu + [Y < 0] Xu) mod 2^256, and
      // -2^128 Yu is 2^128 (~Yu + 1), so the limbs of ~Yu under x's sign word, and a one, go to
      // columns 4 to 7, and the same for ~Xu under y's.
      var sx = xh.lanewise(VectorOperators.ASHR, 63);
      var sy = yh.lanewise(VectorOperators.ASHR, 63);
      var nyl = yl.not().and(sx);
      var nyh = yh.not().and(sx);
      var nxl = xl.not().and(sy);
      var nxh = xh.not().and(sy);
      c4 = c4.add(nyl.and(MASK32)).add(nxl.and(MASK32)).sub(sx).sub(sy);
      c5 = c5.add(nyl.lanewise(VectorOperators.LSHR, 32))
          .add(nxl.lanewise(VectorOperators.LSHR, 32));
      c6 = c6.add(nyh.and(MASK32)).add(nxh.and(MASK32));
      c7 = c7.add(nyh.lanewise(VectorOperators.LSHR, 32))
          .add(nxh.lanewise(VectorOperators.LSHR, 32));
      // The carries, once, low to high; the carry out of column 7 is past 2^256 and dropped.
      c1 = c1.add(c0.lanewise(VectorOperators.LSHR, 32));
      c2 = c2.add(c1.lanewise(VectorOperators.LSHR, 32));
      c3 = c3.add(c2.lanewise(VectorOperators.LSHR, 32));
      c4 = c4.add(c3.lanewise(VectorOperators.LSHR, 32));
      c5 = c5.add(c4.lanewise(VectorOperators.LSHR, 32));
      c6 = c6.add(c5.lanewise(VectorOperators.LSHR, 32));
      c7 = c7.add(c6.lanewise(VectorOperators.LSHR, 32));
      // Each word from the low halves of two columns.
      long row = i * 8L;
      c0.and(MASK32).or(c1.lanewise(VectorOperators.LSHL, 32)).intoMemorySegment(out, row, LE);
      c2.and(MASK32).or(c3.lanewise(VectorOperators.LSHL, 32))
          .intoMemorySegment(out, column + row, LE);
      c4.and(MASK32).or(c5.lanewise(VectorOperators.LSHL, 32))
          .intoMemorySegment(out, 2 * column + row, LE);
      c6.and(MASK32).or(c7.lanewise(VectorOperators.LSHL, 32))
          .intoMemorySegment(out, 3 * column + row, LE);
    }
  }

  static void loopProductS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    long column = n * 8L;
    for (int i = 0; i < n; i++) {
      long at = i * 16L;
      long xl = x.get(WORD, at);
      long xh = x.get(WORD, at + 8);
      long yl = y.get(WORD, at);
      long yh = y.get(WORD, at + 8);
      // xl * yl unsigned; xl * yh and xh * yl unsigned by signed; xh * yh signed.
      long t1 = Math.unsignedMultiplyHigh(xl, yl);
      long lo1 = xl * yh;
      long hi1 = Math.multiplyHigh(xl, yh) + ((xl >> 63) & yh);
      long lo2 = xh * yl;
      long hi2 = Math.multiplyHigh(xh, yl) + ((yl >> 63) & xh);
      long lo3 = xh * yh;
      long hi3 = Math.multiplyHigh(xh, yh);
      long s1 = t1 + lo1;
      long carry1 = Long.compareUnsigned(s1, lo1) < 0 ? 1 : 0;
      long w1 = s1 + lo2;
      carry1 += Long.compareUnsigned(w1, lo2) < 0 ? 1 : 0;
      long s2 = hi1 + hi2;
      long carry2 = Long.compareUnsigned(s2, hi2) < 0 ? 1 : 0;
      long s3 = s2 + lo3;
      carry2 += Long.compareUnsigned(s3, lo3) < 0 ? 1 : 0;
      long w2 = s3 + carry1;
      carry2 += Long.compareUnsigned(w2, carry1) < 0 ? 1 : 0;
      long w3 = hi3 + (hi1 >> 63) + (hi2 >> 63) + carry2;
      long row = i * 8L;
      out.set(WORD, row, xl * yl);
      out.set(WORD, column + row, w1);
      out.set(WORD, 2 * column + row, w2);
      out.set(WORD, 3 * column + row, w3);
    }
  }

  // Loop 6, PRODUCT_CHECK: over the product's four word columns in x, a bit a row set where
  // |x * y| >= 10^38: the top 129 bits not one sign, or the low 128 outside the bound.

  static void loopProductCheckP(MemorySegment x, MemorySegment y, MemorySegment out,
      long[] words, int n) {
    long column = n * 8L;
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int g = 0; g < GROUPS; g++) {
        long row = (i + g * L) * 8L;
        var w0 = load(x, row);
        var w1 = load(x, column + row);
        var w2 = load(x, 2 * column + row);
        var w3 = load(x, 3 * column + row);
        var sign = w1.lanewise(VectorOperators.ASHR, 63);
        var wide = w2.compare(VectorOperators.NE, sign).or(w3.compare(VectorOperators.NE, sign));
        word |= wide.or(outOfPrecision(w1, w0)).toLong() << (g * L);
      }
      words[w] = word;
    }
  }

  static void loopProductCheckS(MemorySegment x, MemorySegment y, MemorySegment out,
      long[] words, int n) {
    long column = n * 8L;
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      for (int k = 0; k < 64; k++) {
        long row = (i + k) * 8L;
        long w0 = x.get(WORD, row);
        long w1 = x.get(WORD, column + row);
        long sign = w1 >> 63;
        boolean fits = x.get(WORD, 2 * column + row) == sign
            && x.get(WORD, 3 * column + row) == sign
            && (w1 < BH || (w1 == BH && Long.compareUnsigned(w0, BL) < 0))
            && (NBH < w1 || (NBH == w1 && Long.compareUnsigned(NBL, w0) < 0));
        word |= (fits ? 0L : 1L) << k;
      }
      words[w] = word;
    }
  }

  // Loop 7, RESCALE: HALF_UP of x / 10^6 stored.

  static void loopRescaleP(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i += L) {
      long at = i * 16L;
      var a = load(x, at);
      var b = load(x, at + VB);
      var lo = a.rearrange(EVEN, b);
      var hi = a.rearrange(ODD, b);
      var negative = hi.compare(VectorOperators.LT, 0L);
      var ml = lo.blend(lo.neg(), negative);
      var mh = hi.blend(negHigh(hi, lo), negative);
      // N = |x| >>> 5, then its limbs.
      var nl = ml.lanewise(VectorOperators.LSHR, PRE_SHIFT)
          .or(mh.lanewise(VectorOperators.LSHL, 64 - PRE_SHIFT));
      var nh = mh.lanewise(VectorOperators.LSHR, PRE_SHIFT);
      var n0 = nl.and(MASK32);
      var n1 = nl.lanewise(VectorOperators.LSHR, 32);
      var n2 = nh.and(MASK32);
      var n3 = nh.lanewise(VectorOperators.LSHR, 32);
      // The constant's limbs, each a broadcast masked to 32 bits, the form C2's vpmuludq rule
      // reads (`VARKA-362.md` 2.8's note on constant limbs).
      var k0 = LongVector.broadcast(LS, C0).and(MASK32);
      var k1 = LongVector.broadcast(LS, C1).and(MASK32);
      var k2 = LongVector.broadcast(LS, C2).and(MASK32);
      var k3 = LongVector.broadcast(LS, C3).and(MASK32);
      var p00 = n0.mul(k0);
      var p01 = n0.mul(k1);
      var p10 = n1.mul(k0);
      var p02 = n0.mul(k2);
      var p11 = n1.mul(k1);
      var p20 = n2.mul(k0);
      var p03 = n0.mul(k3);
      var p12 = n1.mul(k2);
      var p21 = n2.mul(k1);
      var p30 = n3.mul(k0);
      var p13 = n1.mul(k3);
      var p22 = n2.mul(k2);
      var p31 = n3.mul(k1);
      var p23 = n2.mul(k3);
      var p32 = n3.mul(k2);
      var p33 = n3.mul(k3);
      // The low and the high half of each limb product.
      var l00 = p00.and(MASK32);
      var h00 = p00.lanewise(VectorOperators.LSHR, 32);
      var l01 = p01.and(MASK32);
      var h01 = p01.lanewise(VectorOperators.LSHR, 32);
      var l10 = p10.and(MASK32);
      var h10 = p10.lanewise(VectorOperators.LSHR, 32);
      var l02 = p02.and(MASK32);
      var h02 = p02.lanewise(VectorOperators.LSHR, 32);
      var l11 = p11.and(MASK32);
      var h11 = p11.lanewise(VectorOperators.LSHR, 32);
      var l20 = p20.and(MASK32);
      var h20 = p20.lanewise(VectorOperators.LSHR, 32);
      var l03 = p03.and(MASK32);
      var h03 = p03.lanewise(VectorOperators.LSHR, 32);
      var l12 = p12.and(MASK32);
      var h12 = p12.lanewise(VectorOperators.LSHR, 32);
      var l21 = p21.and(MASK32);
      var h21 = p21.lanewise(VectorOperators.LSHR, 32);
      var l30 = p30.and(MASK32);
      var h30 = p30.lanewise(VectorOperators.LSHR, 32);
      var l13 = p13.and(MASK32);
      var h13 = p13.lanewise(VectorOperators.LSHR, 32);
      var l22 = p22.and(MASK32);
      var h22 = p22.lanewise(VectorOperators.LSHR, 32);
      var l31 = p31.and(MASK32);
      var h31 = p31.lanewise(VectorOperators.LSHR, 32);
      var l23 = p23.and(MASK32);
      var h23 = p23.lanewise(VectorOperators.LSHR, 32);
      var l32 = p32.and(MASK32);
      var h32 = p32.lanewise(VectorOperators.LSHR, 32);
      var l33 = p33.and(MASK32);
      var h33 = p33.lanewise(VectorOperators.LSHR, 32);
      // The low four columns are summed for their carries only.
      var c0 = l00;
      var c1 = h00.add(l01).add(l10).add(c0.lanewise(VectorOperators.LSHR, 32));
      var c2 = h01.add(h10).add(l02).add(l11).add(l20).add(c1.lanewise(VectorOperators.LSHR, 32));
      var c3 = h02.add(h11).add(h20).add(l03).add(l12).add(l21).add(l30)
          .add(c2.lanewise(VectorOperators.LSHR, 32));
      var c4 = h03.add(h12).add(h21).add(h30).add(l13).add(l22).add(l31)
          .add(c3.lanewise(VectorOperators.LSHR, 32));
      var c5 = h13.add(h22).add(h31).add(l23).add(l32).add(c4.lanewise(VectorOperators.LSHR, 32));
      var c6 = h23.add(h32).add(l33).add(c5.lanewise(VectorOperators.LSHR, 32));
      var c7 = h33.add(c6.lanewise(VectorOperators.LSHR, 32));
      // q = the product >> 135 = (w3:w2) >> 7, then (q + 1) >>> 1.
      var w2 = c4.and(MASK32).or(c5.lanewise(VectorOperators.LSHL, 32));
      var w3 = c6.and(MASK32).or(c7.lanewise(VectorOperators.LSHL, 32));
      int drop = MAGIC_L - 128;
      var ql = w2.lanewise(VectorOperators.LSHR, drop)
          .or(w3.lanewise(VectorOperators.LSHL, 64 - drop)).add(1L);
      var qh = w3.lanewise(VectorOperators.LSHR, drop)
          .add(1L, ql.compare(VectorOperators.EQ, 0L));
      var hl = ql.lanewise(VectorOperators.LSHR, 1).or(qh.lanewise(VectorOperators.LSHL, 63));
      var hh = qh.lanewise(VectorOperators.LSHR, 1);
      // The sign put back and the pair stored, written out rather than through negHigh and
      // storePair, as the class comment says.
      var rl = hl.blend(hl.neg(), negative);
      var rh = hh.blend(hh.neg().sub(1L, hl.compare(VectorOperators.NE, 0L)), negative);
      rl.rearrange(ZIP1, rh).intoMemorySegment(out, at, LE);
      rl.rearrange(ZIP2, rh).intoMemorySegment(out, at + VB, LE);
    }
  }

  static void loopRescaleS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    for (int i = 0; i < n; i++) {
      long at = i * 16L;
      long lo = x.get(WORD, at);
      long hi = x.get(WORD, at + 8);
      boolean negative = hi < 0;
      if (negative) {
        hi = -hi - (lo != 0 ? 1 : 0);
        lo = -lo;
      }
      long nl = (lo >>> PRE_SHIFT) | (hi << (64 - PRE_SHIFT));
      long nh = hi >>> PRE_SHIFT;
      // The words 2 and 3 of N * c; word 0 is never needed, word 1 only for its carry.
      long t0 = Math.unsignedMultiplyHigh(nl, CL);
      long a1 = nl * CH;
      long a2 = Math.unsignedMultiplyHigh(nl, CH);
      long b1 = nh * CL;
      long b2 = Math.unsignedMultiplyHigh(nh, CL);
      long d2 = nh * CH;
      long d3 = Math.unsignedMultiplyHigh(nh, CH);
      long s1 = t0 + a1;
      long carry1 = Long.compareUnsigned(s1, a1) < 0 ? 1 : 0;
      long w1 = s1 + b1;
      carry1 += Long.compareUnsigned(w1, b1) < 0 ? 1 : 0;
      long s2 = a2 + b2;
      long carry2 = Long.compareUnsigned(s2, b2) < 0 ? 1 : 0;
      long s3 = s2 + d2;
      carry2 += Long.compareUnsigned(s3, d2) < 0 ? 1 : 0;
      long w2 = s3 + carry1;
      carry2 += Long.compareUnsigned(w2, carry1) < 0 ? 1 : 0;
      long w3 = d3 + carry2;
      int drop = MAGIC_L - 128;
      long ql = ((w2 >>> drop) | (w3 << (64 - drop))) + 1;
      long qh = (w3 >>> drop) + (ql == 0 ? 1 : 0);
      long hl = (ql >>> 1) | (qh << 63);
      long hh = qh >>> 1;
      if (negative) {
        hh = -hh - (hl != 0 ? 1 : 0);
        hl = -hl;
      }
      out.set(WORD, at, hl);
      out.set(WORD, at + 8, hh);
    }
  }

  // ---------------------------------------------------------------------------------------
  // The operations, the forms and the arms
  // ---------------------------------------------------------------------------------------

  /** What a loop writes: a selection word a 64 rows, sixteen-byte slots, or four word columns. */
  enum Output { WORDS, SLOTS, PRODUCT }

  /** What a loop reads as its {@code x}: the in-precision column, the wild one, the product. */
  enum Input { X, WILD, PRODUCT }

  /** The loops of section 2.1, by the number the plan gives them. */
  enum Op {
    CHECK("1. the load and its 10^38 check", Input.WILD, Output.WORDS),
    LT("2. x < y into a selection word", Input.X, Output.WORDS),
    NEG("3. -x stored", Input.X, Output.SLOTS),
    ABS("3. abs(x) stored", Input.X, Output.SLOTS),
    CHOICE("4. CASE WHEN x < y THEN x ELSE y END stored", Input.X, Output.SLOTS),
    PRODUCT("5. x * y into 256 bits, stored", Input.X, Output.PRODUCT),
    PRODUCT_CHECK("6. the product's 10^38 check", Input.PRODUCT, Output.WORDS),
    RESCALE("7. HALF_UP of x / 10^6 stored", Input.X, Output.SLOTS);

    final String title;
    final Input input;
    final Output output;

    Op(String title, Input input, Output output) {
      this.title = title;
      this.input = input;
      this.output = output;
    }
  }

  /** The three representations, selected by lower-case name. */
  enum Form {
    P, A, S;

    String token() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** One arm: an operation in a form, the loop's method name (for the code list), the loop. */
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

  /** Every arm, in the order they are timed. */
  static List<Arm> arms() {
    return List.of(
        new Arm(Op.CHECK, Form.P, VarkaDecimalPairSpikeBenchmark::loopCheckP),
        new Arm(Op.CHECK, Form.A, VarkaDecimalPairSpikeBenchmark::loopCheckA),
        new Arm(Op.CHECK, Form.S, VarkaDecimalPairSpikeBenchmark::loopCheckS),
        new Arm(Op.LT, Form.P, VarkaDecimalPairSpikeBenchmark::loopLtP),
        new Arm(Op.LT, Form.A, VarkaDecimalPairSpikeBenchmark::loopLtA),
        new Arm(Op.LT, Form.S, VarkaDecimalPairSpikeBenchmark::loopLtS),
        new Arm(Op.NEG, Form.P, VarkaDecimalPairSpikeBenchmark::loopNegP),
        new Arm(Op.NEG, Form.A, VarkaDecimalPairSpikeBenchmark::loopNegA),
        new Arm(Op.NEG, Form.S, VarkaDecimalPairSpikeBenchmark::loopNegS),
        new Arm(Op.ABS, Form.P, VarkaDecimalPairSpikeBenchmark::loopAbsP),
        new Arm(Op.ABS, Form.A, VarkaDecimalPairSpikeBenchmark::loopAbsA),
        new Arm(Op.ABS, Form.S, VarkaDecimalPairSpikeBenchmark::loopAbsS),
        new Arm(Op.CHOICE, Form.P, VarkaDecimalPairSpikeBenchmark::loopChoiceP),
        new Arm(Op.CHOICE, Form.A, VarkaDecimalPairSpikeBenchmark::loopChoiceA),
        new Arm(Op.CHOICE, Form.S, VarkaDecimalPairSpikeBenchmark::loopChoiceS),
        new Arm(Op.PRODUCT, Form.P, VarkaDecimalPairSpikeBenchmark::loopProductP),
        new Arm(Op.PRODUCT, Form.S, VarkaDecimalPairSpikeBenchmark::loopProductS),
        new Arm(Op.PRODUCT_CHECK, Form.P, VarkaDecimalPairSpikeBenchmark::loopProductCheckP),
        new Arm(Op.PRODUCT_CHECK, Form.S, VarkaDecimalPairSpikeBenchmark::loopProductCheckS),
        new Arm(Op.RESCALE, Form.P, VarkaDecimalPairSpikeBenchmark::loopRescaleP),
        new Arm(Op.RESCALE, Form.S, VarkaDecimalPairSpikeBenchmark::loopRescaleS));
  }

  // ---------------------------------------------------------------------------------------
  // The data and the BigInteger answers
  // ---------------------------------------------------------------------------------------

  private static final BigInteger TWO64 = BigInteger.ONE.shiftLeft(64);
  private static final BigInteger MAX38 = TEN38.subtract(BigInteger.ONE);
  private static final BigInteger HALF = BigInteger.TEN.pow(RESCALE_K).shiftRight(1);
  private static final BigInteger TEN_K = BigInteger.TEN.pow(RESCALE_K);

  /** The columns of one rung, in native memory as a kernel finds them, and their values. */
  record Data(int numRows, MemorySegment x, MemorySegment y, MemorySegment wild,
      MemorySegment product, BigInteger[] xs, BigInteger[] ys, BigInteger[] wilds) {

    MemorySegment input(Input in) {
      return switch (in) {
        case X -> x;
        case WILD -> wild;
        case PRODUCT -> product;
      };
    }
  }

  /** A value of 1 to 38 digits, either sign, below {@code 10^38}. */
  private static BigInteger inPrecision(Random r) {
    BigInteger bound = BigInteger.TEN.pow(1 + r.nextInt(38));
    BigInteger v = new BigInteger(130, r).mod(bound);
    return r.nextBoolean() ? v.negate() : v;
  }

  /**
   * Row {@code i}'s {@code x}: the extremes and the small values first among every 97 rows,
   * a value whose low word is zero, a {@code HALF_UP} tie of the rescale, and otherwise any
   * digit count.
   */
  private static BigInteger columnX(int i, Random r) {
    return switch (i % 97) {
      case 0 -> MAX38;
      case 1 -> MAX38.negate();
      case 2 -> BigInteger.ZERO;
      case 3 -> BigInteger.ONE.negate();
      case 4 -> TWO64.negate();
      case 5, 6 -> BigInteger.valueOf(r.nextLong() >> 2).shiftLeft(64);
      case 7, 8 -> inPrecision(r).divide(TEN_K).multiply(TEN_K).add(r.nextBoolean() ? HALF
          : HALF.negate());
      default -> inPrecision(r);
    };
  }

  /**
   * Row {@code i}'s {@code y}: {@code x} itself one row in eight, its high word with another low
   * word one in eight, and otherwise independent. The other low word is drawn on the side of
   * {@code x}'s that is toward zero, at most it for a positive {@code x} and at least it for a
   * negative one, so {@code |y| <= |x|} and {@code y} stays within {@code DECIMAL(38)} even when
   * {@code x} is an extreme.
   */
  private static BigInteger columnY(int i, BigInteger x, Random r) {
    return switch (i % 8) {
      case 0 -> x;
      case 1 -> {
        BigInteger high = x.shiftRight(64).shiftLeft(64);
        BigInteger low = x.subtract(high);
        BigInteger room = x.signum() < 0 ? TWO64.subtract(low) : low.add(BigInteger.ONE);
        BigInteger step = new BigInteger(64, r).mod(room);
        yield x.signum() < 0 ? x.add(step) : x.subtract(step);
      }
      default -> inPrecision(r);
    };
  }

  /** Row {@code i} of the wild column: one row in sixteen at or past {@code 10^38}. */
  private static BigInteger columnWild(int i, BigInteger x, Random r) {
    if (i % 16 != 5) {
      return x;
    }
    BigInteger room = BigInteger.ONE.shiftLeft(127).subtract(TEN38);
    BigInteger past = switch ((i / 16) % 4) {
      case 0 -> TEN38;
      case 1 -> BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
      default -> TEN38.add(new BigInteger(127, r).mod(room));
    };
    return r.nextBoolean() ? past.negate() : past;
  }

  private static void setSlot(MemorySegment s, int row, BigInteger v) {
    s.set(WORD, row * 16L, v.longValue());
    s.set(WORD, row * 16L + 8, v.shiftRight(64).longValue());
  }

  /** The signed value of the slot at {@code row}. */
  static BigInteger slot(MemorySegment s, int row) {
    return BigInteger.valueOf(s.get(WORD, row * 16L + 8)).shiftLeft(64)
        .or(new BigInteger(Long.toUnsignedString(s.get(WORD, row * 16L))));
  }

  /** The columns of {@code n} rows, a multiple of 64, with a vector of padding past the end. */
  static Data data(Arena arena, int n) {
    require(n % 64 == 0, n + " rows is not a multiple of 64");
    var r = new Random(359);
    var x = arena.allocate(n * 16L + 64, 64);
    var y = arena.allocate(n * 16L + 64, 64);
    var wild = arena.allocate(n * 16L + 64, 64);
    var product = arena.allocate(n * 32L + 64, 64);
    var xs = new BigInteger[n];
    var ys = new BigInteger[n];
    var wilds = new BigInteger[n];
    for (int i = 0; i < n; i++) {
      xs[i] = columnX(i, r);
      ys[i] = columnY(i, xs[i], r);
      wilds[i] = columnWild(i, xs[i], r);
      setSlot(x, i, xs[i]);
      setSlot(y, i, ys[i]);
      setSlot(wild, i, wilds[i]);
      BigInteger p = xs[i].multiply(ys[i]);
      for (int k = 0; k < 4; k++) {
        product.set(WORD, k * n * 8L + i * 8L, p.shiftRight(64 * k).longValue());
      }
    }
    return new Data(n, x, y, wild, product, xs, ys, wilds);
  }

  /** {@code HALF_UP} of {@code v / 10^6}: half away from zero. */
  private static BigInteger halfUp(BigInteger v) {
    BigInteger q = v.abs().add(HALF).divide(TEN_K);
    return v.signum() < 0 ? q.negate() : q;
  }

  /** What an arm of {@code op} must leave, from {@code BigInteger}: words, or a segment. */
  record Expected(long[] words, MemorySegment out) {}

  static Expected expected(Op op, Data d, Arena arena) {
    int n = d.numRows();
    return switch (op.output) {
      case WORDS -> {
        long[] words = new long[n / 64];
        for (int i = 0; i < n; i++) {
          boolean bit = switch (op) {
            case CHECK -> d.wilds()[i].abs().compareTo(TEN38) >= 0;
            case LT -> d.xs()[i].compareTo(d.ys()[i]) < 0;
            case PRODUCT_CHECK -> d.xs()[i].multiply(d.ys()[i]).abs().compareTo(TEN38) >= 0;
            case NEG, ABS, CHOICE, PRODUCT, RESCALE ->
                throw new IllegalStateException(op + " writes no words");
          };
          words[i >>> 6] |= (bit ? 1L : 0L) << (i & 63);
        }
        yield new Expected(words, null);
      }
      case SLOTS -> {
        var out = arena.allocate(n * 16L + 64, 64);
        for (int i = 0; i < n; i++) {
          BigInteger xv = d.xs()[i];
          BigInteger v = switch (op) {
            case NEG -> xv.negate();
            case ABS -> xv.abs();
            case CHOICE -> xv.compareTo(d.ys()[i]) < 0 ? xv : d.ys()[i];
            case RESCALE -> halfUp(xv);
            case CHECK, LT, PRODUCT, PRODUCT_CHECK ->
                throw new IllegalStateException(op + " writes no slots");
          };
          setSlot(out, i, v);
        }
        yield new Expected(null, out);
      }
      case PRODUCT -> new Expected(null, d.product());
    };
  }

  /**
   * The output segment the arms of {@code op} write into, of the expected's size, which each arm
   * of the operation reuses; none, {@code MemorySegment.NULL}, for an operation that writes
   * selection words.
   */
  static MemorySegment outputFor(Op op, int n, Arena arena) {
    return switch (op.output) {
      case WORDS -> MemorySegment.NULL;
      case SLOTS -> arena.allocate(n * 16L + 64, 64);
      case PRODUCT -> arena.allocate(n * 32L + 64, 64);
    };
  }

  /**
   * Whether {@code arm}'s last run left what {@code e} says: the words, or the output's bytes
   * over its {@code n} rows. A wrong row is named with both values.
   */
  static void requireAgrees(Arm arm, Expected e, MemorySegment out, long[] words, int n,
      String when) {
    if (arm.op().output == Output.WORDS) {
      for (int w = 0; w < n / 64; w++) {
        if (words[w] != e.words()[w]) {
          int row = w * 64 + Long.numberOfTrailingZeros(words[w] ^ e.words()[w]);
          throw new IllegalStateException(arm.label() + " disagrees with BigInteger " + when
              + " at row " + row + ": bit " + ((words[w] >>> (row & 63)) & 1));
        }
      }
      return;
    }
    long bytes = arm.op().output == Output.PRODUCT ? n * 32L : n * 16L;
    long at = out.asSlice(0, bytes).mismatch(e.out().asSlice(0, bytes));
    if (at >= 0) {
      throw new IllegalStateException(arm.label() + " disagrees with BigInteger " + when
          + " at byte " + at + " of " + bytes + " (row " + at / (arm.op().output == Output.PRODUCT
              ? 8 : 16) % n + ")");
    }
  }

  /**
   * Runs every arm once over {@code n} rows and checks it against {@code BigInteger}: the check
   * the timed run makes before it times an arm, and all of what {@code
   * VarkaDecimalPairSpikeSuite} asserts.
   */
  static void checkAll(int n) {
    try (Arena arena = Arena.ofConfined()) {
      Data d = data(arena, n);
      for (Op op : Op.values()) {
        Expected e = expected(op, d, arena);
        MemorySegment out = outputFor(op, n, arena);
        for (Arm arm : arms()) {
          if (arm.op() == op) {
            // The output is shared, so it is cleared first: a loop that wrote nothing would
            // otherwise pass on the previous arm's bytes.
            out.fill((byte) 0);
            long[] words = new long[n / 64];
            arm.loop().apply(d.input(op.input), d.y(), out, words, n);
            requireAgrees(arm, e, out, words, n, "at " + n + " rows");
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // Selecting a run
  // ---------------------------------------------------------------------------------------

  /** The forms {@code -Dvarka.pair.forms} names, comma separated; all three by default. */
  private static Set<Form> selectedForms() {
    var forms = EnumSet.noneOf(Form.class);
    for (String token : System.getProperty(FORMS_PROPERTY, "p,a,s").split(",", -1)) {
      String name = token.strip();
      Form form = Arrays.stream(Form.values()).filter(f -> f.token().equals(name)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("-D" + FORMS_PROPERTY + " names \""
              + name + "\", which is none of p, a, s"));
      require(forms.add(form), "-D" + FORMS_PROPERTY + " names " + name + " twice");
    }
    return forms;
  }

  /** The rungs {@code -Dvarka.pair.rungs} names, comma separated; all three by default. */
  private static List<Rung> selectedRungs() {
    var rungs = new ArrayList<Rung>();
    for (String token : System.getProperty(RUNGS_PROPERTY, "l2,l3,pastl3").split(",", -1)) {
      String name = token.strip();
      Rung rung = LADDER.stream().filter(g -> g.name().equals(name)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("-D" + RUNGS_PROPERTY + " names \""
              + name + "\", which is none of l2, l3, pastl3"));
      require(!rungs.contains(rung), "-D" + RUNGS_PROPERTY + " names " + name + " twice");
      rungs.add(rung);
    }
    return rungs;
  }

  // ---------------------------------------------------------------------------------------
  // Driving an arm to compiled code (row 329's harness)
  // ---------------------------------------------------------------------------------------

  /**
   * The highest tier of {@code method}'s code in use according to {@code Compiler.codelist}, 0 if
   * it has none, -1 if the list cannot be read. An on-stack-replacement version is not marked as
   * one in the list, and a loop method is shared by every rung, so from the second rung on this
   * is true on arrival: the steady drive times are what show a rung's loop is settled.
   */
  private static int levelInUse(String method) {
    try {
      String prefix = VarkaDecimalPairSpikeBenchmark.class.getName() + "." + method + "(";
      int level = 0;
      for (String line : String.valueOf(codeList()).split("\\R")) {
        // "<id> <tier> <state> <class>.<method><descriptor> [<addresses>]"; state 0 is in use.
        String[] fields = line.strip().split(" ", 5);
        if (fields.length >= 4 && fields[3].startsWith(prefix) && "0".equals(fields[2])) {
          level = Math.max(level, Integer.parseInt(fields[1]));
        }
      }
      return level;
    } catch (Exception | LinkageError e) {
      return -1;
    }
  }

  private static Object codeList() throws Exception {
    return ManagementFactory.getPlatformMBeanServer().invoke(
        new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerCodelist",
        new Object[] {null}, new String[] {String[].class.getName()});
  }

  /**
   * Refuses a run that cannot tell whether a loop is compiled: one where {@code
   * Compiler.codelist} is unreadable, or where no {@code dontinline} compile command is set.
   */
  private static void requireHarness() {
    try {
      codeList();
    } catch (Exception | LinkageError e) {
      throw new IllegalStateException("Compiler.codelist cannot be read (" + e + "), so no loop"
          + " can be shown compiled; run on a HotSpot JVM with the DiagnosticCommand MBean", e);
    }
    boolean dontInline = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .anyMatch(VarkaDecimalPairSpikeBenchmark::keepsTheLoopsOutOfLine);
    require(dontInline, "no " + DONTINLINE_LOOPS + " is set, so a loop may be inlined into the"
        + " drive and show no code of its own in the code list");
  }

  /** The compile command that keeps every loop of this class a compile unit of its own. */
  static final String DONTINLINE_LOOPS = "-XX:CompileCommand=dontinline,"
      + VarkaDecimalPairSpikeBenchmark.class.getName() + "::loop*";

  /**
   * Whether the JVM argument {@code arg} is {@link #DONTINLINE_LOOPS}, with the class named in
   * HotSpot's slash form or its dotted one and the method after {@code ::} or a dot. A
   * {@code dontinline} of some other pattern does not keep these loops out of line, and is not it.
   */
  static boolean keepsTheLoopsOutOfLine(String arg) {
    String prefix = "-XX:CompileCommand=dontinline,";
    if (!arg.startsWith(prefix)) {
      return false;
    }
    String pattern = arg.substring(prefix.length()).strip().replace('/', '.').replace("::", ".");
    return pattern.equals(VarkaDecimalPairSpikeBenchmark.class.getName() + ".loop*");
  }

  private static void drive(Arm arm, MemorySegment in, MemorySegment y, MemorySegment out,
      long[] words, int n, int reps) {
    for (int r = 0; r < reps; r++) {
      arm.loop().apply(in, y, out, words, n);
    }
  }

  /** Drives {@code arm} until its loop method is level-4 code and its drive time is steady. */
  private static void driveToCompiledCode(Arm arm, MemorySegment in, MemorySegment y,
      MemorySegment out, long[] words, int n, int reps) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARM_WAIT_MILLIS);
    long previous = 0;
    int drives = 0;
    while (true) {
      long start = System.nanoTime();
      drive(arm, in, y, out, words, n, reps);
      long took = System.nanoTime() - start;
      drives++;
      boolean idle = VarkaEmitterTestSupport.awaitC2Idle(IDLE_WAIT_MILLIS);
      int level = levelInUse(arm.method());
      boolean steady = previous > 0 && Math.abs(took - previous) <= previous * STEADY_FRACTION;
      if (drives >= MIN_WARM_DRIVES && idle && steady && level == 4) {
        System.out.println("VarkaDecimalPairSpikeBenchmark: " + arm.label() + " at " + n
            + " rows settled after " + drives + " drives, level " + level);
        return;
      }
      if (System.nanoTime() > deadline) {
        System.out.println("VarkaDecimalPairSpikeBenchmark: " + arm.label() + " at " + n
            + " rows did not settle in " + WARM_WAIT_MILLIS + " ms (" + drives + " drives, last "
            + TimeUnit.NANOSECONDS.toMicros(took) + " us after "
            + TimeUnit.NANOSECONDS.toMicros(previous) + " us, level " + level + ", C2 "
            + (idle ? "idle" : "busy") + "); it is timed as it is");
        return;
      }
      previous = took;
    }
  }

  // ---------------------------------------------------------------------------------------
  // The benchmark
  // ---------------------------------------------------------------------------------------

  /**
   * Refuses a run narrowed by {@code -Dvarka.pair.forms} or {@code -Dvarka.pair.rungs} that would
   * write the results file, as the base class does for its own selectors, then runs the base's.
   * The check comes first because the base's {@code main} empties the committed file on opening.
   */
  @Override
  public void main(String[] args) {
    boolean writesResults = "1".equals(System.getenv("SPARK_GENERATE_BENCHMARK_FILES"));
    for (String property : List.of(FORMS_PROPERTY, RUNGS_PROPERTY)) {
      String value = System.getProperty(property);
      if (writesResults && value != null) {
        throw new IllegalArgumentException("-D" + property + "=" + value + " runs part of a"
            + " benchmark, which must not be written to its results file: unset"
            + " SPARK_GENERATE_BENCHMARK_FILES, or drop the property");
      }
    }
    super.main(args);
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var forms = selectedForms();
    var rungs = selectedRungs();
    // The arms to time: only those a -Dvarka.bench.only names, and none in a dry run, which
    // still builds every arm (so that a loop reference that names no method fails there).
    var arms = arms().stream()
        .filter(a -> forms.contains(a.form()))
        .filter(a -> only().map(a.label()::contains).orElse(true)).toList();
    require(!arms.isEmpty(), "no arm is in forms " + forms.stream().map(Form::token).toList()
        + only().map(o -> " with a label holding \"" + o + "\"").orElse("")
        + ", so the run would time nothing");
    if (dryRun()) {
      return;
    }
    requireHarness();
    section("decimal lane pair hand loops at " + LS.vectorBitSize() + " bits, forms "
        + forms.stream().map(Form::token).sorted().toList(), () -> {
      for (Rung rung : rungs) {
        runRung(rung, arms);
      }
    });
  }

  private void runRung(Rung rung, List<Arm> arms) {
    int n = rung.numRows();
    int reps = (int) Math.max(1, ROWS_PER_ITERATION / n);
    try (Arena arena = Arena.ofConfined()) {
      Data d = data(arena, n);
      var benchmark = benchmark(n + " rows - " + rung.level(), (long) n * reps)
          .minIterations(3).warmup(Duration.ofSeconds(1)).minTime(Duration.ofSeconds(1)).build();
      for (Op op : Op.values()) {
        List<Arm> ofOp = arms.stream().filter(a -> a.op() == op).toList();
        if (ofOp.isEmpty()) {
          continue;
        }
        Expected e = expected(op, d, arena);
        // One output for the operation, which its arms take in turn; each clears it before the
        // run it checks, so that an arm is never credited with the previous arm's bytes.
        MemorySegment out = outputFor(op, n, arena);
        for (Arm arm : ofOp) {
          MemorySegment in = d.input(op.input);
          long[] words = new long[n / 64];
          out.fill((byte) 0);
          arm.loop().apply(in, d.y(), out, words, n);
          requireAgrees(arm, e, out, words, n, "before it is timed");
          var settled = new AtomicBoolean();
          benchmark.addTimerCase(arm.label(), timer -> {
            if (settled.compareAndSet(false, true)) {
              out.fill((byte) 0);
              Arrays.fill(words, 0L);
              driveToCompiledCode(arm, in, d.y(), out, words, n, reps);
              // The output the compiled code wrote, not the first call's: the check before the
              // timing ran the loop in the interpreter, where the Vector API is its Java code.
              requireAgrees(arm, e, out, words, n, "in compiled code");
            }
            timer.startTiming();
            drive(arm, in, d.y(), out, words, n, reps);
            timer.stopTiming();
          });
        }
      }
      runCases(benchmark);
    }
  }
}
