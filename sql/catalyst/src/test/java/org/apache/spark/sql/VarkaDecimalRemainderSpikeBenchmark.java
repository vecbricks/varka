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

import static jdk.incubator.vector.VectorOperators.ASHR;
import static jdk.incubator.vector.VectorOperators.LSHL;
import static jdk.incubator.vector.VectorOperators.LSHR;
import static jdk.incubator.vector.VectorOperators.XOR;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.SerializedLambda;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.ObjectName;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport;

/**
 * What the remainder of one 128-bit decimal by another costs in a Vector API loop under three
 * division forms, written by hand with no emitter (VARKA-577's spike, section 2.3 of its plan,
 * which picks the form on a measurement before the emitter is touched).
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
 * <p>At two long lanes on x86 C2 refuses every rearrange and every mask op (VARKA-359.md 0, item
 * 9), so E and D run the Java fallback there. E's variants of VARKA-577.md 10.4 try the two ways
 * around it, each alone and both together: F is E with every compare, blend and mask replaced by
 * bit arithmetic on 0/1 lanes, and the {@link Access} other than {@code SHUFFLE} reads the low
 * and the high words with no de-interleave, by a gather, by scalar loads inserted into lanes, from
 * a column in the split layout, or from 64-row blocks transposed into it by scalar moves.
 *
 * <p>Every loop is checked against {@code BigInteger} before it is timed and again in compiled
 * code after its drive (the edge rows are at {@link #row}), and each arm is driven to level-4
 * code first by VARKA-359's harness. Each form's body is one method that both of its operations'
 * loops call with a constant {@code pmod}, and that is forced inline into each, so each loop is
 * its own compile unit with the other operation's code folded away.
 *
 * <p>Level 4 is not proof that the vector code is timed: a Vector API call C2 does not intrinsify
 * (no lowering on this machine, or a vector it cannot unbox) stays a call to the Java fallback,
 * which allocates every vector, and the code list shows that at level 4 too. So before timing,
 * {@link #intrinsicRefusals} compiles each loop once more in a child JVM with the run's flags and
 * reads its inlining tree. An arm with a fallback is timed, as it is what such a host would run,
 * but its label says so and the results file lists the calls beside the tables.
 *
 * <p>Run it with {@link #DONTINLINE_LOOPS} and {@link #INLINE_BODIES}, which the run requires,
 * at {@code -XX:MaxVectorSize=64}, {@code 32} and {@code 16} for the three widths:
 * <pre>
 *   build/sbt 'project catalyst' 'set Test/javaOptions ++= Seq("-XX:MaxVectorSize=32",
 *       "-XX:CompileCommand=dontinline," +
 *       "org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark::loop*",
 *       "-XX:CompileCommand=inline," +
 *       "org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark::body*")' \
 *     'Test/runMain org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark'
 * </pre>
 * {@code -Dvarka.bench.only} narrows a run to the arms whose label holds a string.
 */
public class VarkaDecimalRemainderSpikeBenchmark extends VarkaBenchmarkBase {

  // ---------------------------------------------------------------------------------------
  // The ladder and the harness's constants
  // ---------------------------------------------------------------------------------------

  /** A rung of the ladder: a row count and where its working set lies. */
  private record Rung(int numRows, String level) {}

  /** VARKA-359's rungs: 48 bytes a row an arm touches, in L2, in L3 and past it. */
  private static final List<Rung> LADDER = List.of(
      new Rung(4096, "in L2"),
      new Rung(262144, "in L3"),
      new Rung(4194304, "past L3"));

  /**
   * {@code -Dvarka.remainder.rungs=k} times the ladder's first {@code k} rungs only, for a
   * reading on a shared laptop, where D's 128-bit fallback past L3 would take most of an hour;
   * such a run must not write the results file, which holds the whole ladder.
   */
  private static final String RUNGS_PROPERTY = "varka.remainder.rungs";

  private static List<Rung> ladder() {
    int k = Integer.getInteger(RUNGS_PROPERTY, LADDER.size());
    require(k >= 1 && k <= LADDER.size(), "-D" + RUNGS_PROPERTY + "=" + k + " is not 1 to "
        + LADDER.size());
    return LADDER.subList(0, k);
  }

  /** The compile command that keeps every loop of this class a compile unit of its own. */
  static final String DONTINLINE_LOOPS = "-XX:CompileCommand=dontinline,"
      + VarkaDecimalRemainderSpikeBenchmark.class.getName() + "::loop*";

  /** The compile command that folds a form's body into each of its two loops. */
  static final String INLINE_BODIES = "-XX:CompileCommand=inline,"
      + VarkaDecimalRemainderSpikeBenchmark.class.getName() + "::body*";

  /**
   * Rows a timed iteration covers at the least, in whole passes over the rung: 2^20, which is
   * 256 passes in L2 and four in L3, and one pass of the 2^22 rows past L3.
   */
  private static final long ROWS_PER_ITERATION = 1L << 20;

  private static final int MIN_WARM_DRIVES = 8;
  private static final double STEADY_FRACTION = 0.3;
  private static final long WARM_WAIT_MILLIS = 30_000;
  private static final long IDLE_WAIT_MILLIS = 10_000;

  /** The child JVM of the intrinsic check: its rows a call, and its limits. */
  private static final int PROBE_ROWS = 64;
  private static final int PROBE_CALLS = 20_000;
  private static final long PROBE_TIMEOUT_SECONDS = 300;

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

  /** Lane j's low word in a slot array, at 2j from a group's first, for the gathered loads. */
  private static final int[] EVEN_INDEX = new int[L];

  /** Lane j's index, by which a 0/1 lane is shifted into bit j of a word. */
  private static final long[] IOTA = new long[L];

  static {
    for (int j = 0; j < L; j++) {
      EVEN_INDEX[j] = 2 * j;
      IOTA[j] = j;
    }
  }

  /**
   * A transposed block's longs: 64 rows' low words, then their high words, of {@code x}, of
   * {@code y} and of the result.
   */
  private static final int BLOCK_LONGS = 6 * 64;

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
  // The loops. Each has the signature of Loop: the dividend and divisor columns, the output, a
  // selection word a 64 rows of the zero divisors, and the row count (a multiple of 64). The
  // columns and the output are Arrow's slots, in native memory or (GATHER) in a heap array, or
  // the split layout (SPLIT), as the arm's form's Layout says.
  // A loop calls its form's body with its operation's pmod as a constant, and INLINE_BODIES folds
  // the body in, at the start of the compile where no inlining cutoff is near. Past their loads
  // the vector bodies call no helper of this class, which C2 may refuse to inline in a long loop
  // and so box every vector it passes (VARKA-359's note).
  // ---------------------------------------------------------------------------------------

  /** A loop over {@code x} and {@code y} into {@code out} and {@code words}. */
  @FunctionalInterface
  interface Loop extends Serializable {
    void apply(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n);
  }

  static void loopRemainderE(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, false, Access.SHUFFLE);
  }

  static void loopPmodE(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, true, Access.SHUFFLE);
  }

  static void loopRemainderEG(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, false, Access.GATHER);
  }

  static void loopPmodEG(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, true, Access.GATHER);
  }

  static void loopRemainderEI(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, false, Access.INSERT);
  }

  static void loopPmodEI(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, true, Access.INSERT);
  }

  static void loopRemainderES(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, false, Access.SPLIT);
  }

  static void loopPmodES(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyE(x, y, out, words, n, true, Access.SPLIT);
  }

  static void loopRemainderF(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, false, Access.SHUFFLE);
  }

  static void loopPmodF(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, true, Access.SHUFFLE);
  }

  static void loopRemainderFG(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, false, Access.GATHER);
  }

  static void loopPmodFG(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, true, Access.GATHER);
  }

  static void loopRemainderFI(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, false, Access.INSERT);
  }

  static void loopPmodFI(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, true, Access.INSERT);
  }

  static void loopRemainderFS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, false, Access.SPLIT);
  }

  static void loopPmodFS(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, true, Access.SPLIT);
  }

  static void loopRemainderFT(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, false, Access.BLOCK);
  }

  static void loopPmodFT(MemorySegment x, MemorySegment y, MemorySegment out, long[] words,
      int n) {
    bodyF(x, y, out, words, n, true, Access.BLOCK);
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
   * / b}, from {@code r = a}; then {@code r -= b} twice where {@code r >= b}. Its signs, its
   * shift selects and its corrections are compares, masks and blends; {@code access} says how
   * it reads its operands and writes its result (a constant in each loop, so C2 folds the other
   * ways away).
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
      boolean pmod, Access access) {
    var one = DoubleVector.broadcast(DS, 1.0);
    long[] xs = access == Access.GATHER ? (long[]) x.heapBase().orElseThrow() : null;
    long[] ys = access == Access.GATHER ? (long[]) y.heapBase().orElseThrow() : null;
    long[] block = access == Access.BLOCK ? new long[BLOCK_LONGS] : null;
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      if (access == Access.BLOCK) {
        for (int k = 0; k < 64; k++) {
          long a = (i + k) * 16L;
          block[k] = x.get(WORD, a);
          block[64 + k] = x.get(WORD, a + 8);
          block[128 + k] = y.get(WORD, a);
          block[192 + k] = y.get(WORD, a + 8);
        }
      }
      for (int g = 0; g < GROUPS; g++) {
        // The group's rows start at slot byte at, split byte lo and block index b.
        long at = (i + g * L) * 16L;
        long lo = (i + g * L) * 8L;
        int b = g * L;
        LongVector xl;
        LongVector xh;
        LongVector yl;
        LongVector yh;
        if (access == Access.SHUFFLE) {
          var xa = LongVector.fromMemorySegment(LS, x, at, LE);
          var xb = LongVector.fromMemorySegment(LS, x, at + VB, LE);
          var ya = LongVector.fromMemorySegment(LS, y, at, LE);
          var yb = LongVector.fromMemorySegment(LS, y, at + VB, LE);
          xl = xa.rearrange(EVEN, xb);
          xh = xa.rearrange(ODD, xb);
          yl = ya.rearrange(EVEN, yb);
          yh = ya.rearrange(ODD, yb);
        } else if (access == Access.GATHER) {
          int k = 2 * (i + g * L);
          xl = LongVector.fromArray(LS, xs, k, EVEN_INDEX, 0);
          xh = LongVector.fromArray(LS, xs, k + 1, EVEN_INDEX, 0);
          yl = LongVector.fromArray(LS, ys, k, EVEN_INDEX, 0);
          yh = LongVector.fromArray(LS, ys, k + 1, EVEN_INDEX, 0);
        } else if (access == Access.INSERT) {
          xl = LongVector.zero(LS);
          xh = xl;
          yl = xl;
          yh = xl;
          for (int j = 0; j < L; j++) {
            long a = at + 16L * j;
            xl = xl.withLane(j, x.get(WORD, a));
            xh = xh.withLane(j, x.get(WORD, a + 8));
            yl = yl.withLane(j, y.get(WORD, a));
            yh = yh.withLane(j, y.get(WORD, a + 8));
          }
        } else if (access == Access.SPLIT) {
          xl = LongVector.fromMemorySegment(LS, x, lo, LE);
          xh = LongVector.fromMemorySegment(LS, x, n * 8L + lo, LE);
          yl = LongVector.fromMemorySegment(LS, y, lo, LE);
          yh = LongVector.fromMemorySegment(LS, y, n * 8L + lo, LE);
        } else {
          xl = LongVector.fromArray(LS, block, b);
          xh = LongVector.fromArray(LS, block, 64 + b);
          yl = LongVector.fromArray(LS, block, 128 + b);
          yh = LongVector.fromArray(LS, block, 192 + b);
        }
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
        if (access == Access.SHUFFLE) {
          rl.rearrange(ZIP1, rh).intoMemorySegment(out, at, LE);
          rl.rearrange(ZIP2, rh).intoMemorySegment(out, at + VB, LE);
        } else if (access == Access.GATHER || access == Access.INSERT) {
          for (int j = 0; j < L; j++) {
            out.set(WORD, at + 16L * j, rl.lane(j));
            out.set(WORD, at + 16L * j + 8, rh.lane(j));
          }
        } else if (access == Access.SPLIT) {
          rl.intoMemorySegment(out, lo, LE);
          rh.intoMemorySegment(out, n * 8L + lo, LE);
        } else {
          rl.intoArray(block, 256 + b);
          rh.intoArray(block, 320 + b);
        }
        word |= zero.toLong() << (g * L);
      }
      if (access == Access.BLOCK) {
        for (int k = 0; k < 64; k++) {
          out.set(WORD, (i + k) * 16L, block[256 + k]);
          out.set(WORD, (i + k) * 16L + 8, block[320 + k]);
        }
      }
      words[w] = word;
    }
  }

  /**
   * F: E's arithmetic with no mask anywhere, so that no compare, blend or mask op is left for
   * C2 to refuse at two long lanes on x86 (VARKA-359.md 0, item 9). A condition is a lane of 0
   * or 1, {@code c}, or of 0 or -1, {@code -c}; a select of {@code y} over {@code x} where {@code
   * c} is {@code x ^ ((x ^ y) & -c)}. The unsigned borrow of {@code x - y}, with {@code d} that
   * difference less any borrow in, is the top bit of {@code (~x & y) | ((~x | y) & d)}, the carry
   * of {@code x + y} into {@code s} that of {@code (x & y) | ((x | y) & ~s)} (Hacker's Delight
   * 2-13), and {@code v == 0} the top bit of {@code ~(v | -v)}. The zero divisors' word is the
   * 0/1 lanes shifted by their lane index and or-reduced. Every value is E's, in the same order,
   * so E's exactness argument holds for F unchanged; {@code access} is as for E.
   */
  static void bodyF(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n,
      boolean pmod, Access access) {
    var one = DoubleVector.broadcast(DS, 1.0);
    var iota = LongVector.fromArray(LS, IOTA, 0);
    long[] xs = access == Access.GATHER ? (long[]) x.heapBase().orElseThrow() : null;
    long[] ys = access == Access.GATHER ? (long[]) y.heapBase().orElseThrow() : null;
    long[] block = access == Access.BLOCK ? new long[BLOCK_LONGS] : null;
    for (int i = 0, w = 0; i < n; i += 64, w++) {
      long word = 0;
      if (access == Access.BLOCK) {
        for (int k = 0; k < 64; k++) {
          long a = (i + k) * 16L;
          block[k] = x.get(WORD, a);
          block[64 + k] = x.get(WORD, a + 8);
          block[128 + k] = y.get(WORD, a);
          block[192 + k] = y.get(WORD, a + 8);
        }
      }
      for (int g = 0; g < GROUPS; g++) {
        long at = (i + g * L) * 16L;
        long lo = (i + g * L) * 8L;
        int b = g * L;
        LongVector xl;
        LongVector xh;
        LongVector yl;
        LongVector yh;
        if (access == Access.SHUFFLE) {
          var xa = LongVector.fromMemorySegment(LS, x, at, LE);
          var xb = LongVector.fromMemorySegment(LS, x, at + VB, LE);
          var ya = LongVector.fromMemorySegment(LS, y, at, LE);
          var yb = LongVector.fromMemorySegment(LS, y, at + VB, LE);
          xl = xa.rearrange(EVEN, xb);
          xh = xa.rearrange(ODD, xb);
          yl = ya.rearrange(EVEN, yb);
          yh = ya.rearrange(ODD, yb);
        } else if (access == Access.GATHER) {
          int k = 2 * (i + g * L);
          xl = LongVector.fromArray(LS, xs, k, EVEN_INDEX, 0);
          xh = LongVector.fromArray(LS, xs, k + 1, EVEN_INDEX, 0);
          yl = LongVector.fromArray(LS, ys, k, EVEN_INDEX, 0);
          yh = LongVector.fromArray(LS, ys, k + 1, EVEN_INDEX, 0);
        } else if (access == Access.INSERT) {
          xl = LongVector.zero(LS);
          xh = xl;
          yl = xl;
          yh = xl;
          for (int j = 0; j < L; j++) {
            long a = at + 16L * j;
            xl = xl.withLane(j, x.get(WORD, a));
            xh = xh.withLane(j, x.get(WORD, a + 8));
            yl = yl.withLane(j, y.get(WORD, a));
            yh = yh.withLane(j, y.get(WORD, a + 8));
          }
        } else if (access == Access.SPLIT) {
          xl = LongVector.fromMemorySegment(LS, x, lo, LE);
          xh = LongVector.fromMemorySegment(LS, x, n * 8L + lo, LE);
          yl = LongVector.fromMemorySegment(LS, y, lo, LE);
          yh = LongVector.fromMemorySegment(LS, y, n * 8L + lo, LE);
        } else {
          xl = LongVector.fromArray(LS, block, b);
          xh = LongVector.fromArray(LS, block, 64 + b);
          yl = LongVector.fromArray(LS, block, 128 + b);
          yh = LongVector.fromArray(LS, block, 192 + b);
        }
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
          // As in E: m << k for k >= 0, m >>> -k below, and 0 for a right shift past 63, whose
          // count the lanes would otherwise take modulo 64.
          var k = bits.lanewise(LSHR, 52).sub(1075L);
          var below = k.lanewise(ASHR, 63);
          var right = k.neg();
          var far = LongVector.broadcast(LS, 63L).sub(right).lanewise(ASHR, 63);
          var m = bits.and(MASK52).or(1L << 52);
          var shifted = m.lanewise(LSHR, right).and(far.not());
          m = m.lanewise(XOR, m.lanewise(XOR, shifted).and(below));
          var s = k.and(below.not());
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
        if (access == Access.SHUFFLE) {
          rl.rearrange(ZIP1, rh).intoMemorySegment(out, at, LE);
          rl.rearrange(ZIP2, rh).intoMemorySegment(out, at + VB, LE);
        } else if (access == Access.GATHER || access == Access.INSERT) {
          for (int j = 0; j < L; j++) {
            out.set(WORD, at + 16L * j, rl.lane(j));
            out.set(WORD, at + 16L * j + 8, rh.lane(j));
          }
        } else if (access == Access.SPLIT) {
          rl.intoMemorySegment(out, lo, LE);
          rh.intoMemorySegment(out, n * 8L + lo, LE);
        } else {
          rl.intoArray(block, 256 + b);
          rh.intoArray(block, 320 + b);
        }
        word |= z.lanewise(LSHL, iota).reduceLanes(VectorOperators.OR) << (g * L);
      }
      if (access == Access.BLOCK) {
        for (int k = 0; k < 64; k++) {
          out.set(WORD, (i + k) * 16L, block[256 + k]);
          out.set(WORD, (i + k) * 16L + 8, block[320 + k]);
        }
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

  /** Where an arm's columns and output are, which the harness prepares and reads back. */
  enum Layout {
    /** Arrow's 16-byte slots, the low word then the high word of a row, in native memory. */
    SLOTS,
    /** The slots in a heap {@code long[]}, which a gather needs; the output stays native slots. */
    HEAP,
    /** All the low words, then all the high words, in native memory, the output so too. */
    SPLIT
  }

  /** How a vector body reads its operands and writes its result (VARKA-577.md 10.4). */
  enum Access {
    /** From the slots, two loads de-interleaved by a two-vector rearrange, zipped back. */
    SHUFFLE,
    /** From the slots in a heap array, each word gathered by an index map; stores a lane each. */
    GATHER,
    /** From the slots, each lane's word inserted from a scalar load; stores a lane each. */
    INSERT,
    /** From the split layout, by plain vector loads and stores. */
    SPLIT,
    /** From the slots, 64 rows at a time transposed by scalar moves into a split block. */
    BLOCK
  }

  /**
   * The three forms of section 2.3, (a) E, (b) D and (c) B, and E's variants of 10.4: E with
   * each other access (idea 2), F, which is E with no mask (idea 1), and F with each access.
   */
  enum Form {
    E("E", Layout.SLOTS),
    D("D", Layout.SLOTS),
    B("B", Layout.SLOTS),
    EG("E gathered", Layout.HEAP),
    EI("E inserted", Layout.SLOTS),
    ES("E split", Layout.SPLIT),
    F("F", Layout.SLOTS),
    FG("F gathered", Layout.HEAP),
    FI("F inserted", Layout.SLOTS),
    FS("F split", Layout.SPLIT),
    FT("F transposed", Layout.SLOTS);

    final String title;
    final Layout layout;

    Form(String title, Layout layout) {
      this.title = title;
      this.layout = layout;
    }
  }

  /** One arm: an operation in a form, the loop's method name (for the code list), the loop. */
  record Arm(Op op, Form form, String method, Loop loop) {

    Arm(Op op, Form form, Loop loop) {
      this(op, form, methodOf(loop), loop);
    }

    String label() {
      return op.title + ", " + form.title;
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
        new Arm(Op.REMAINDER, Form.E, VarkaDecimalRemainderSpikeBenchmark::loopRemainderE),
        new Arm(Op.REMAINDER, Form.D, VarkaDecimalRemainderSpikeBenchmark::loopRemainderD),
        new Arm(Op.REMAINDER, Form.B, VarkaDecimalRemainderSpikeBenchmark::loopRemainderB),
        new Arm(Op.REMAINDER, Form.EG, VarkaDecimalRemainderSpikeBenchmark::loopRemainderEG),
        new Arm(Op.REMAINDER, Form.EI, VarkaDecimalRemainderSpikeBenchmark::loopRemainderEI),
        new Arm(Op.REMAINDER, Form.ES, VarkaDecimalRemainderSpikeBenchmark::loopRemainderES),
        new Arm(Op.REMAINDER, Form.F, VarkaDecimalRemainderSpikeBenchmark::loopRemainderF),
        new Arm(Op.REMAINDER, Form.FG, VarkaDecimalRemainderSpikeBenchmark::loopRemainderFG),
        new Arm(Op.REMAINDER, Form.FI, VarkaDecimalRemainderSpikeBenchmark::loopRemainderFI),
        new Arm(Op.REMAINDER, Form.FS, VarkaDecimalRemainderSpikeBenchmark::loopRemainderFS),
        new Arm(Op.REMAINDER, Form.FT, VarkaDecimalRemainderSpikeBenchmark::loopRemainderFT),
        new Arm(Op.PMOD, Form.E, VarkaDecimalRemainderSpikeBenchmark::loopPmodE),
        new Arm(Op.PMOD, Form.D, VarkaDecimalRemainderSpikeBenchmark::loopPmodD),
        new Arm(Op.PMOD, Form.B, VarkaDecimalRemainderSpikeBenchmark::loopPmodB),
        new Arm(Op.PMOD, Form.EG, VarkaDecimalRemainderSpikeBenchmark::loopPmodEG),
        new Arm(Op.PMOD, Form.EI, VarkaDecimalRemainderSpikeBenchmark::loopPmodEI),
        new Arm(Op.PMOD, Form.ES, VarkaDecimalRemainderSpikeBenchmark::loopPmodES),
        new Arm(Op.PMOD, Form.F, VarkaDecimalRemainderSpikeBenchmark::loopPmodF),
        new Arm(Op.PMOD, Form.FG, VarkaDecimalRemainderSpikeBenchmark::loopPmodFG),
        new Arm(Op.PMOD, Form.FI, VarkaDecimalRemainderSpikeBenchmark::loopPmodFI),
        new Arm(Op.PMOD, Form.FS, VarkaDecimalRemainderSpikeBenchmark::loopPmodFS),
        new Arm(Op.PMOD, Form.FT, VarkaDecimalRemainderSpikeBenchmark::loopPmodFT));
  }

  // ---------------------------------------------------------------------------------------
  // The data and the BigInteger answers
  // ---------------------------------------------------------------------------------------

  private static final BigInteger MAX38 = TEN38.subtract(BigInteger.ONE);

  /**
   * The byte an output is filled with before a checked run: 16 of them are {@code 0x7F7F...7F},
   * above {@code 10^38}, so a slot a loop left unwritten matches no answer.
   */
  static final byte UNWRITTEN = (byte) 0x7F;

  /**
   * The columns of one rung, in native memory as a kernel finds them, with their copies in a
   * heap array and in the split layout, and each operation's answers, computed as the rows are
   * drawn so that no row's values outlive it.
   */
  record Data(int numRows, MemorySegment x, MemorySegment y, MemorySegment heapX,
      MemorySegment heapY, MemorySegment splitX, MemorySegment splitY,
      Map<Op, Expected> expected) implements AutoCloseable {

    /** {@code x} as {@code arm}'s loop reads it. */
    MemorySegment x(Arm arm) {
      return switch (arm.form().layout) {
        case SLOTS -> x;
        case HEAP -> heapX;
        case SPLIT -> splitX;
      };
    }

    /** {@code y} as {@code arm}'s loop reads it. */
    MemorySegment y(Arm arm) {
      return switch (arm.form().layout) {
        case SLOTS -> y;
        case HEAP -> heapY;
        case SPLIT -> splitY;
      };
    }

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
    var heapX = MemorySegment.ofArray(new long[2 * n]).copyFrom(x);
    var heapY = MemorySegment.ofArray(new long[2 * n]).copyFrom(y);
    var splitX = arena.allocate(n * 16L, 64);
    var splitY = arena.allocate(n * 16L, 64);
    transpose(x, splitX, n, true);
    transpose(y, splitY, n, true);
    return new Data(n, x, y, heapX, heapY, splitX, splitY, expected);
  }

  /** Copies {@code n} rows of slots into the split layout, or ({@code !toSplit}) back. */
  static void transpose(MemorySegment from, MemorySegment to, int n, boolean toSplit) {
    for (int i = 0; i < n; i++) {
      long slot = i * 16L;
      long split = i * 8L;
      if (toSplit) {
        to.set(WORD, split, from.get(WORD, slot));
        to.set(WORD, n * 8L + split, from.get(WORD, slot + 8));
      } else {
        to.set(WORD, slot, from.get(WORD, split));
        to.set(WORD, slot + 8, from.get(WORD, n * 8L + split));
      }
    }
  }

  /** Runs {@code arm}'s loop once over {@code d}'s columns as it reads them. */
  static void apply(Arm arm, Data d, MemorySegment out, long[] words) {
    arm.loop().apply(d.x(arm), d.y(arm), out, words, d.numRows());
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

  /**
   * Whether {@code arm}'s last run left what {@code e} says; a wrong row is named. A split
   * output is read back into slots first.
   */
  static void requireAgrees(Arm arm, Expected e, MemorySegment out, long[] words, int n,
      String when) {
    if (arm.form().layout == Layout.SPLIT) {
      try (Arena own = Arena.ofConfined()) {
        var slots = own.allocate(n * 16L, 64);
        transpose(out, slots, n, false);
        requireAgreesAsSlots(arm, e, slots, words, n, when);
      }
    } else {
      requireAgreesAsSlots(arm, e, out, words, n, when);
    }
  }

  private static void requireAgreesAsSlots(Arm arm, Expected e, MemorySegment out, long[] words,
      int n, String when) {
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
   * Runs every arm once over {@code n} rows and checks it against {@code BigInteger}: the check
   * the timed run makes before it times an arm, and what {@code VarkaDecimalRemainderSpikeSuite}
   * asserts.
   */
  static void checkAll(int n) {
    try (Arena arena = Arena.ofConfined(); Data d = data(arena, n)) {
      var out = arena.allocate(n * 16L, 64);
      for (Op op : Op.values()) {
        try (Expected e = d.expected().get(op)) {
          for (Arm arm : arms()) {
            if (arm.op() == op) {
              // Filled first with what no row answers: a loop that left a slot or a word
              // unwritten would otherwise pass on the previous arm's bytes, or on a zero that is
              // the right answer for a zero divisor or an exact multiple.
              out.fill(UNWRITTEN);
              long[] words = new long[n / 64];
              Arrays.fill(words, -1L);
              apply(arm, d, out, words);
              requireAgrees(arm, e, out, words, n, "at " + n + " rows");
            }
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // Driving an arm to compiled code (VARKA-359's harness)
  // ---------------------------------------------------------------------------------------

  /**
   * The highest tier of {@code method}'s code in use by {@code Compiler.codelist}, 0 if none, -1
   * if the list cannot be read; the drive's steady times show a shared loop's rung settled. The
   * list does not mark an on-stack-replacement version, so a level-4 OSR compile, whose boxed
   * vectors {@link #fallbacks} passes over, also reads as 4: at the rungs past L2 a drive is four
   * calls or one, and a loop whose method code was made not entrant there may be timed in it.
   */
  private static int levelInUse(String method) {
    try {
      String prefix = VarkaDecimalRemainderSpikeBenchmark.class.getName() + "." + method + "(";
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

  /** Refuses a run that cannot read the code list, or that lacks either compile command. */
  private static void requireHarness() {
    try {
      codeList();
    } catch (Exception | LinkageError e) {
      throw new IllegalStateException("Compiler.codelist cannot be read (" + e + "), so no loop"
          + " can be shown compiled; run on a HotSpot JVM with the DiagnosticCommand MBean", e);
    }
    List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
    require(args.stream().anyMatch(VarkaDecimalRemainderSpikeBenchmark::keepsTheLoopsOutOfLine),
        "no " + DONTINLINE_LOOPS + " is set, so a loop may be inlined into the drive and show no"
        + " code of its own in the code list");
    require(args.stream().anyMatch(VarkaDecimalRemainderSpikeBenchmark::inlinesTheBodies),
        "no " + INLINE_BODIES + " is set, so a form's body may be compiled once for both"
        + " operations, its pmod a variable");
  }

  /** Whether {@code arg} is {@link #DONTINLINE_LOOPS}, the class dotted or slashed. */
  static boolean keepsTheLoopsOutOfLine(String arg) {
    return isCommand(arg, "dontinline", "loop*");
  }

  /** Whether {@code arg} is {@link #INLINE_BODIES}, the class dotted or slashed. */
  static boolean inlinesTheBodies(String arg) {
    return isCommand(arg, "inline", "body*");
  }

  private static boolean isCommand(String arg, String command, String methods) {
    String prefix = "-XX:CompileCommand=" + command + ",";
    if (!arg.startsWith(prefix)) {
      return false;
    }
    String pattern = arg.substring(prefix.length()).strip().replace('/', '.').replace("::", ".");
    return pattern.equals(VarkaDecimalRemainderSpikeBenchmark.class.getName() + "." + methods);
  }

  private static void drive(Arm arm, Data d, MemorySegment out, long[] words, int reps) {
    for (int r = 0; r < reps; r++) {
      apply(arm, d, out, words);
    }
  }

  /** Drives {@code arm} until its loop method is level-4 code and its drive time is steady. */
  private static void driveToCompiledCode(Arm arm, Data d, MemorySegment out, long[] words,
      int reps) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARM_WAIT_MILLIS);
    long previous = 0;
    int drives = 0;
    String name = "VarkaDecimalRemainderSpikeBenchmark: " + arm.label() + " at " + d.numRows()
        + " rows";
    while (true) {
      long start = System.nanoTime();
      drive(arm, d, out, words, reps);
      long took = System.nanoTime() - start;
      drives++;
      boolean idle = VarkaEmitterTestSupport.awaitC2Idle(IDLE_WAIT_MILLIS);
      int level = levelInUse(arm.method());
      boolean steady = previous > 0 && Math.abs(took - previous) <= previous * STEADY_FRACTION;
      if (drives >= MIN_WARM_DRIVES && idle && steady && level == 4) {
        System.out.println(name + " settled after " + drives + " drives, level " + level);
        return;
      }
      if (System.nanoTime() > deadline) {
        System.out.println(name + " did not settle in " + WARM_WAIT_MILLIS + " ms (" + drives
            + " drives, last " + TimeUnit.NANOSECONDS.toMicros(took) + " us after "
            + TimeUnit.NANOSECONDS.toMicros(previous) + " us, level " + level + ", C2 "
            + (idle ? "idle" : "busy") + "); it is timed as it is");
        return;
      }
      previous = took;
    }
  }

  // ---------------------------------------------------------------------------------------
  // The intrinsic check: the Vector API calls C2 leaves in Java, read in a child JVM
  // ---------------------------------------------------------------------------------------

  /**
   * The Vector API calls C2 left in Java in {@code arm}'s loop under this run's flags, empty
   * where every one was intrinsified. A child JVM of {@link IntrinsicProbe} calls the loop alone,
   * with this JVM's arguments (the width, any {@code UseAVX}, the compile commands) but its
   * agents, plus {@code -Xbatch}, {@code PrintCompilation} and a {@code PrintInlining} command
   * over the loops; {@link #fallbacks} reads the answer from what it prints.
   */
  static List<String> intrinsicRefusals(Arm arm) {
    String name = VarkaDecimalRemainderSpikeBenchmark.class.getName();
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (!arg.startsWith("-agentlib") && !arg.startsWith("-agentpath")
          && !arg.startsWith("-javaagent") && !arg.startsWith("-Xrun")) {
        command.add(arg);
      }
    }
    command.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-Xbatch", "-XX:+PrintCompilation",
        "-XX:CompileCommand=quiet", "-XX:CompileCommand=PrintInlining," + name + "::loop*",
        "-cp", childClassPath(), IntrinsicProbe.class.getName(),
        arm.method()));
    Path log = null;
    try {
      log = Files.createTempFile("varka-remainder-probe", ".log");
      Process child = new ProcessBuilder(command).redirectErrorStream(true)
          .redirectOutput(log.toFile()).start();
      if (!child.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        child.destroyForcibly();
        throw requirementFailed("the intrinsic check of " + arm.method() + " did not finish in "
            + PROBE_TIMEOUT_SECONDS + " s");
      }
      List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
      require(child.exitValue() == 0, "the intrinsic check of " + arm.method() + " failed (exit "
          + child.exitValue() + "); its last lines:\n"
          + String.join("\n", lines.subList(Math.max(0, lines.size() - 20), lines.size())));
      return fallbacks(lines, arm.method());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } finally {
      if (log != null) {
        try {
          Files.deleteIfExists(log);
        } catch (IOException e) {
          System.out.println("could not delete " + log + ": " + e);
        }
      }
    }
  }

  /**
   * The child's class path: this JVM's own, plus every local jar and directory its context class
   * loaders were given. Under {@code spark-submit}, which is how the benchmark workflow runs a
   * benchmark, the test jars that hold the probe come in through {@code --jars} and are only there.
   */
  private static String childClassPath() {
    var entries = new LinkedHashSet<String>();
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (!entry.isEmpty()) {
        entries.add(entry);
      }
    }
    for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null;
        loader = loader.getParent()) {
      if (loader instanceof URLClassLoader urls) {
        for (URL url : urls.getURLs()) {
          if (url.getProtocol().equals("file")) {
            try {
              entries.add(new File(url.toURI()).getPath());
            } catch (URISyntaxException e) {
              throw new IllegalArgumentException("not a file URL: " + url, e);
            }
          }
        }
      }
    }
    return String.join(File.pathSeparator, entries);
  }

  /** A {@code PrintCompilation} record: its flags ({@code %} for an OSR), level and method. */
  private static final Pattern COMPILE = Pattern.compile(
      "^\\s*\\d+\\s+\\d+\\s+([%sbn! ]*)\\s+(\\d)\\s+(\\S+::\\S+)");

  /**
   * The fallbacks in {@code method}'s last level-4 compile that is not an on-stack replacement,
   * the code later calls run, from a child's {@code PrintCompilation} and {@code PrintInlining}
   * lines; under {@code -Xbatch} a compile's inlining tree follows its record. A {@code
   * VectorSupport} call site whose last message is not {@code (intrinsic)} or {@code late inline
   * succeeded} is a call to the Java fallback, which boxes its vectors. The on-stack replacement
   * is passed over: it enters the loop with vectors the interpreter boxed, and C2 then fails to
   * unbox some of them (VARKA-577.md 10.2), but no call after the method's own compile runs it.
   * A Vector API method that failed to inline is a call too, with its vectors boxed, though no
   * {@code VectorSupport} site sits under it. A compile C2 skipped is passed over. With no such
   * compile, or one the body was not inlined into, whose tree would hold none of the body's
   * sites, the check throws, since an empty answer would prove nothing.
   */
  static List<String> fallbacks(List<String> lines, String method) {
    String name = VarkaDecimalRemainderSpikeBenchmark.class.getName();
    List<String> last = null;
    List<String> current = null;
    boolean inlined = false;
    // The compile before the one being read, which a skipped compile hands the answer back to.
    List<String> kept = null;
    boolean keptInlined = false;
    for (String line : lines) {
      Matcher m = COMPILE.matcher(line);
      boolean record = m.find();
      boolean ours = record && m.group(3).equals(name + "::" + method)
          && m.group(2).equals("4") && !m.group(1).contains("%");
      if (line.contains("made not entrant")) {
        continue;
      } else if (line.contains("COMPILE SKIPPED")) {
        // Printed after the skipped compile's tree, with its record's fields.
        if (ours && current == last) {
          last = kept;
          inlined = keptInlined;
        }
        current = null;
      } else if (record) {
        current = new ArrayList<>();
        if (ours) {
          kept = last;
          keptInlined = inlined;
          last = current;
          inlined = false;
        }
      } else if (current != null && current == last
          && line.strip().matches("@ \\d+\\s+" + Pattern.quote(name) + "::body\\w+ .*")) {
        // The body's own call site; a later site's type profile may name the body too.
        inlined |= line.contains("force inline by CompileCommand");
      } else if (current != null && (line.contains("jdk.internal.vm.vector.VectorSupport::")
          || line.contains("jdk.incubator.vector."))) {
        // A site's messages are separated by runs of spaces, its verdict the last of them.
        String[] said = line.strip().split("\\s{2,}");
        String verdict = said[said.length - 1];
        boolean fallback = line.contains("VectorSupport::")
            ? !verdict.equals("(intrinsic)") && !verdict.equals("late inline succeeded")
            : verdict.startsWith("failed to inline");
        if (fallback) {
          current.add(line.strip().replaceAll("\\s+", " "));
        }
      }
    }
    require(last != null, "the intrinsic check found no level-4 compile of " + method);
    require(inlined, "the intrinsic check found the body not inlined into " + method
        + "'s compile");
    return last;
  }

  /** The fallbacks counted by the method each calls. */
  private static String summary(List<String> refusals) {
    var counts = new TreeMap<String, Integer>();
    for (String r : refusals) {
      // A site reads "@ <bci> <class>::<method> (<n> bytes) <verdicts>".
      String site = r.split(" ")[2];
      counts.merge(site.substring(site.lastIndexOf('.') + 1), 1, Integer::sum);
    }
    return refusals.size() + " Vector API call(s) left in Java, by method: " + counts;
  }

  /** The intrinsic check's child JVM: calls the loop it names until C2 has compiled it. */
  static final class IntrinsicProbe {

    private IntrinsicProbe() {
    }

    public static void main(String[] args) {
      Arm arm = arms().stream().filter(a -> a.method().equals(args[0])).findFirst()
          .orElseThrow(() -> requirementFailed("no arm's loop is " + args[0]));
      try (Arena arena = Arena.ofConfined(); Data d = data(arena, PROBE_ROWS)) {
        var out = arena.allocate(PROBE_ROWS * 16L, 64);
        long[] words = new long[PROBE_ROWS / 64];
        for (int c = 0; c < PROBE_CALLS; c++) {
          apply(arm, d, out, words);
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // The benchmark
  // ---------------------------------------------------------------------------------------

  /** Refuses a run of part of the ladder that would write the results file, then runs it. */
  @Override
  public void main(String[] args) {
    // Before the base's main, which opens the results file and so empties it.
    require(ladder().size() == LADDER.size()
        || !"1".equals(System.getenv("SPARK_GENERATE_BENCHMARK_FILES")),
        "-D" + RUNGS_PROPERTY + " times part of the ladder, which must not be written to the"
        + " results file");
    super.main(args);
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var arms = arms().stream().filter(a -> only().map(a.label()::contains).orElse(true))
        .toList();
    require(!arms.isEmpty(), "no arm's label holds \"" + only().orElse("") + "\"");
    if (dryRun()) {
      return;
    }
    requireHarness();
    section("decimal remainder hand loops at " + LS.vectorBitSize() + " bits", () -> {
      // An arm whose loop keeps a call to a Vector API fallback is timed as such a host runs
      // it, but its label says so and the file lists the calls.
      var labels = new HashMap<Arm, String>();
      for (Arm arm : arms) {
        List<String> refusals = intrinsicRefusals(arm);
        labels.put(arm, arm.label() + (refusals.isEmpty() ? "" : " [Java fallback]"));
        note("intrinsic check, " + arm.method() + ": " + (refusals.isEmpty()
            ? "every Vector API call intrinsified" : summary(refusals)));
      }
      for (Rung rung : ladder()) {
        runRung(rung, arms, labels);
      }
    });
  }

  private void runRung(Rung rung, List<Arm> arms, Map<Arm, String> labels) {
    int n = rung.numRows();
    int reps = (int) Math.max(1, ROWS_PER_ITERATION / n);
    try (Arena arena = Arena.ofConfined(); Data d = data(arena, n)) {
      // One output, which the arms take in turn; each fills it with no answer before the run it
      // checks.
      var out = arena.allocate(n * 16L, 64);
      for (Op op : Op.values()) {
        // A table an operation, so that its answers are freed once its arms are timed.
        try (Expected e = d.expected().get(op)) {
          var opArms = arms.stream().filter(a -> a.op() == op).toList();
          if (opArms.isEmpty()) {
            continue;
          }
          var benchmark = benchmark(n + " rows - " + rung.level() + ", " + op.title,
              (long) n * reps).timed().build();
          for (Arm arm : opArms) {
            long[] words = new long[n / 64];
            Arrays.fill(words, -1L);
            out.fill(UNWRITTEN);
            apply(arm, d, out, words);
            requireAgrees(arm, e, out, words, n, "before it is timed");
            var settled = new AtomicBoolean();
            benchmark.addTimerCase(labels.get(arm), timer -> {
              if (settled.compareAndSet(false, true)) {
                out.fill(UNWRITTEN);
                Arrays.fill(words, -1L);
                driveToCompiledCode(arm, d, out, words, reps);
                // The compiled code's output, not the first call's, which ran in the
                // interpreter.
                requireAgrees(arm, e, out, words, n, "in compiled code");
              }
              timer.startTiming();
              drive(arm, d, out, words, reps);
              timer.stopTiming();
            });
          }
          runCases(benchmark);
        }
      }
    }
  }
}
