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

package org.apache.spark.sql.varka.vector;

import static org.apache.spark.sql.varka.vector.VarkaVectorSupport.ofAddress;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The scalar baseline this project never measured: {@code year(date)} as an ordinary Java loop
 * over the Arrow buffer, with no Vector API, no lane groups and no species.
 *
 * <p>It exists to answer two questions the emitter's own numbers cannot.
 *
 * <p><b>One: how much of the vector kernel's advantage is vectors?</b> The committed parity
 * anchor for "the path Spark uses today" is a per-row {@code LocalDate.ofEpochDay(d).getYear()}
 * loop at roughly 480 M rows/s, and that number is not a scalar-arithmetic baseline at all - it
 * allocates a {@code LocalDate} per row, so it prices allocation. An allocation-free scalar
 * civil-from-days is a different thing entirely and had never been measured here.
 *
 * <p><b>Two: can C2 vectorize it on its own?</b> This is why there are two methods computing
 * exactly the same values by exactly the same algorithm, differing only in how each division is
 * spelled:
 *
 * <ul>
 *   <li>{@link #yearByDivision} writes {@code x / d}. C2 lowers a long division by a constant to
 *       {@code MulHiL} - a multiply keeping the <i>high</i> 64 bits - which it can emit as one
 *       instruction but for which no vector node exists anywhere in HotSpot. So this loop can
 *       never auto-vectorize, whatever else is true of it.</li>
 *   <li>{@link #yearByMagic} writes {@code (x * M) >>> k} with the magic chosen so the product
 *       fits a signed 64-bit lane. That is {@code MulL} and {@code URShiftL}, both of which
 *       <i>do</i> have vector counterparts ({@code MulVL}, {@code URShiftVL}), so SuperWord is
 *       free to vectorize it. The body is branchless for the same reason: the January turn and
 *       the month fixup are arithmetic, not {@code if}, because a branch in the loop body ends
 *       auto-vectorization.</li>
 * </ul>
 *
 * <p>Comparing the two, and comparing {@link #yearByMagic} against itself under
 * {@code -XX:-UseSuperWord}, answers "did C2 vectorize this" without needing a disassembler.
 *
 * <p>The arithmetic is Howard Hinnant's {@code civil_from_days} with every division replaced by
 * an exact magic multiply, and unlike the emitted lowering it is exact over <b>every</b> int32
 * day: the bias is a whole number of eras ({@code 719468 + 14699 * 146097}), which puts the
 * dividend in {@code [0, 2^32 + 719468)} where the {@code /146097} magic is exact with three
 * bits of headroom. So there is no narrow range and no guard here - the same property
 * `m5/PLAN.md` wants for the vector path, reachable in scalar code today
 * because a 64-bit multiply is ordinary. The constants are the ones
 * {@code sql/varka/plans/verify_long_lane_magic.py} derives and checks, and the algorithm was
 * swept against a reference over every day of years 1 to 9999 before it was written here;
 * {@code ChronoScalarOpsTest} re-checks both methods against {@code java.time.LocalDate} and
 * against each other.
 *
 * <p>Both methods write the destination validity as one all-ones fill per batch rather than per
 * row, which is what a null-free scalar path would do. The emitted kernel this is compared
 * against ORs validity per lane group instead, so the two are not paying the same price for
 * that part - see m4/PLAN.md section 2.17, which is about exactly that cost.
 */
public final class ChronoScalarOps {

  // Exact magic (multiplier, shift) pairs, each valid over the dividend range the algorithm
  // actually produces. Derived and checked by verify_long_lane_magic.py; do not edit one
  // without re-running it, since a pair that is exact over a wider range is a different pair.
  private static final long ERA_M = 963315389L;
  private static final int ERA_K = 47;              // / 146097 over [0, 2^32 + 719468)
  private static final long D1460_M = 45965L;
  private static final int D1460_K = 26;            // / 1460   over [0, 146097)
  private static final long D36524_M = 235187L;
  private static final int D36524_K = 33;           // / 36524  over [0, 146097)
  private static final long D146096_M = 235187L;
  private static final int D146096_K = 35;          // / 146096 over [0, 146097)
  private static final long D365_M = 45965L;
  private static final int D365_K = 24;             // / 365    over [0, 146097)
  private static final long D100_M = 41L;
  private static final int D100_K = 12;             // / 100    over [0, 400)
  private static final long D153_M = 857L;
  private static final int D153_K = 17;             // / 153    over [0, 2000)

  private static final int ERA_DAYS = 146097;

  /**
   * Whole eras, so that subtracting {@link #BIAS_ERAS} from the era count is exact. 14699 is the
   * smallest count whose days exceed {@code 2^31}, which is what makes the biased dividend
   * non-negative for every int32 day.
   */
  private static final int BIAS_ERAS = 14699;
  private static final long BIAS = 719468L + (long) BIAS_ERAS * ERA_DAYS;

  private ChronoScalarOps() {}

  /**
   * dst[i] = the calendar year of src[i], with each division written as {@code /} so that C2
   * lowers it to a high-half multiply and the loop cannot auto-vectorize. See the class doc.
   *
   * @param srcData address of the source int32 day values.
   * @param dstData address of the destination int32 year values.
   * @param dstValidity address of the destination bit-packed validity, filled all-ones.
   * @param length number of rows; every row is assumed non-null.
   */
  public static void yearByDivision(long srcData, long dstData, long dstValidity, int length) {
    if (length <= 0) {
      return;
    }
    MemorySegment src = ofAddress(srcData, length * 4L);
    MemorySegment dst = ofAddress(dstData, length * 4L);
    fillValid(dstValidity, length);
    for (int i = 0; i < length; i++) {
      long w = src.get(ValueLayout.JAVA_INT, i * 4L) + BIAS;
      long era = w / ERA_DAYS;
      long doe = w - era * ERA_DAYS;
      long u = doe - doe / 1460 + doe / 36524 - doe / 146096;
      long yoe = u / 365;
      long doy = doe - (365 * yoe + (yoe >>> 2) - yoe / 100);
      long mp = (5 * doy + 2) / 153;
      // m = mp < 10 ? mp + 3: mp - 9, branchless; then the year turns where m <= 2.
      long lt10 = (mp - 10) >>> 63;
      long m = mp + 3 - 12 * (1 - lt10);
      long turned = ((2 - m) >>> 63) ^ 1L;
      dst.set(ValueLayout.JAVA_INT, i * 4L, (int) (400 * (era - BIAS_ERAS) + yoe + turned));
    }
  }

  /**
   * dst[i] = the calendar year of src[i], identical to {@link #yearByDivision} value for value,
   * with each division written as an exact magic multiply so that only {@code MulL} and
   * {@code URShiftL} appear and SuperWord may vectorize the loop. See the class doc.
   *
   * @param srcData address of the source int32 day values.
   * @param dstData address of the destination int32 year values.
   * @param dstValidity address of the destination bit-packed validity, filled all-ones.
   * @param length number of rows; every row is assumed non-null.
   */
  public static void yearByMagic(long srcData, long dstData, long dstValidity, int length) {
    if (length <= 0) {
      return;
    }
    MemorySegment src = ofAddress(srcData, length * 4L);
    MemorySegment dst = ofAddress(dstData, length * 4L);
    fillValid(dstValidity, length);
    for (int i = 0; i < length; i++) {
      long w = src.get(ValueLayout.JAVA_INT, i * 4L) + BIAS;
      long era = (w * ERA_M) >>> ERA_K;
      long doe = w - era * ERA_DAYS;
      long u = doe - ((doe * D1460_M) >>> D1460_K)
          + ((doe * D36524_M) >>> D36524_K) - ((doe * D146096_M) >>> D146096_K);
      long yoe = (u * D365_M) >>> D365_K;
      long doy = doe - (365 * yoe + (yoe >>> 2) - ((yoe * D100_M) >>> D100_K));
      long mp = ((5 * doy + 2) * D153_M) >>> D153_K;
      long lt10 = (mp - 10) >>> 63;
      long m = mp + 3 - 12 * (1 - lt10);
      long turned = ((2 - m) >>> 63) ^ 1L;
      dst.set(ValueLayout.JAVA_INT, i * 4L, (int) (400 * (era - BIAS_ERAS) + yoe + turned));
    }
  }

  /** Every row valid, written once per batch rather than per lane group; see the class doc. */
  private static void fillValid(long dstValidity, int length) {
    MemorySegment validity = ofAddress(dstValidity, (length + 7) / 8L);
    validity.fill((byte) -1);
  }

  // -------------------------------------------------------------------------------------------
  // Auto-vectorization probes
  //
  // yearByMagic is written so SuperWord *could* vectorize it - only MulL and URShiftL, no
  // branches - and it measurably does not (-XX:-UseSuperWord moves it 0.1%). These four methods
  // separate the two candidate reasons by bisection, since TraceAutoVectorization is a develop
  // flag and this JDK is a product build with no hsdis to disassemble with.
  //
  //                        trivial body            full civil-from-days body
  //   int[] array          probeArrayTrivial       probeArrayYear
  //   MemorySegment        probeSegmentTrivial     yearByMagic (above)
  //
  // Run each with and without -XX:-UseSuperWord. A case SuperWord vectorizes slows down when it
  // is disabled; a case it never touched does not move. If the segment-trivial case responds and
  // the segment-year case does not, the arithmetic is what blocks it; if the array cases respond
  // and the segment cases do not, MemorySegment addressing is what blocks it.
  // -------------------------------------------------------------------------------------------

  /** Probe: the simplest vectorizable body there is, over an int array. The control. */
  public static void probeArrayTrivial(int[] src, int[] dst, int length) {
    for (int i = 0; i < length; i++) {
      dst[i] = src[i] * 3 + 1;
    }
  }

  /** Probe: the same body over a MemorySegment, isolating the addressing. */
  public static void probeSegmentTrivial(long srcData, long dstData, int length) {
    MemorySegment src = ofAddress(srcData, length * 4L);
    MemorySegment dst = ofAddress(dstData, length * 4L);
    for (int i = 0; i < length; i++) {
      dst.set(ValueLayout.JAVA_INT, i * 4L, src.get(ValueLayout.JAVA_INT, i * 4L) * 3 + 1);
    }
  }

  /** Probe: {@link #yearByMagic}'s exact body over int arrays, isolating the arithmetic. */
  public static void probeArrayYear(int[] src, int[] dst, int length) {
    for (int i = 0; i < length; i++) {
      long w = src[i] + BIAS;
      long era = (w * ERA_M) >>> ERA_K;
      long doe = w - era * ERA_DAYS;
      long u = doe - ((doe * D1460_M) >>> D1460_K)
          + ((doe * D36524_M) >>> D36524_K) - ((doe * D146096_M) >>> D146096_K);
      long yoe = (u * D365_M) >>> D365_K;
      long doy = doe - (365 * yoe + (yoe >>> 2) - ((yoe * D100_M) >>> D100_K));
      long mp = ((5 * doy + 2) * D153_M) >>> D153_K;
      long lt10 = (mp - 10) >>> 63;
      long m = mp + 3 - 12 * (1 - lt10);
      long turned = ((2 - m) >>> 63) ^ 1L;
      dst[i] = (int) (400 * (era - BIAS_ERAS) + yoe + turned);
    }
  }
}
