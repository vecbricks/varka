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
import static org.apache.spark.sql.varka.vector.VarkaVectorSupport.orPartialValidityBitsAt;
import static org.apache.spark.sql.varka.vector.VarkaVectorSupport.orValidityBitsAt;
import static org.apache.spark.sql.varka.vector.VarkaVectorSupport.partialValidityBitsAt;
import static org.apache.spark.sql.varka.vector.VarkaVectorSupport.validityBitsAt;
import static org.apache.spark.sql.varka.vector.VarkaVectorSupport.zero;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * A hand-written measurement kernel (see {@code VARKA-32.md}): the ceiling on
 * computing {@code year}, {@code month}, {@code dayofmonth} and {@code quarter} from ONE
 * civil-from-days decomposition per row, against the 445.7 M rows/s four independently emitted
 * nodes reach today ({@code VarkaEmitterParityBenchmark-jdk25-results.txt}).
 *
 * <p><b>Everything in the lane path is written out by hand, with no method call of any kind.</b>
 * That is not a style choice, it is the whole validity of the measurement. The first version of
 * this file factored the decomposition into a {@code computeFields} helper returning a record of
 * four {@link IntVector}s; that helper compiled to 376 bytes of bytecode, past C2's 325-byte
 * {@code FreqInlineSize}, so it never inlined into the loop. Once it does not inline, escape
 * analysis cannot see the allocation and its consumers in one compilation unit, so the record and
 * its four vectors were really heap-allocated once per lane group - and three of the six calls to
 * a 12-byte {@code magic} helper stopped inlining too, once the enclosing method was over budget.
 * The kernel measured 225.8 M rows/s and the change was declined on that number. It was measuring
 * the cost of a Java abstraction, not the cost of sharing.
 *
 * <p>{@code VarkaLoopEmitter.emitChrono} - the path this kernel exists to model - emits zero call
 * boundaries in the lane path: every intermediate is a local and every op is a
 * {@code jdk.incubator.vector} intrinsic in one method. So this file matches that shape, at the
 * cost of writing the decomposition out twice (main loop and epilogue), which is exactly what the
 * emitter does with its {@code loopDense}/{@code epilogueDense} pair. <b>Do not refactor the
 * arithmetic below into a shared helper.</b> If it must be touched, re-check with
 * {@code -XX:+PrintInlining} that no call survives in the loop, and with {@code javap -c -p} that
 * no method holding lane arithmetic exceeds 325 bytes.
 *
 * <p>This is deliberately not production code and is not wired into {@code VarkaFusedKernel} -
 * the engine module cannot depend on catalyst, which owns that interface. It exists only to answer
 * that ceiling question. But it now pays what a shippable shared lowering would pay,
 * because a ceiling has to charge both sides of the comparison the same things:
 *
 * <ul>
 *   <li><b>The narrow-range guard</b> ({@code VarkaChrono.NARROW_MIN_DAYS}..
 *       {@code NARROW_MAX_DAYS}), emitted once for all four fields - two compares, an OR, an AND
 *       with the row's validity, and an OR into an accumulator, with one {@code anyTrue} after
 *       the loop. Sharing the decomposition shares the guard, so a shared lowering pays one where
 *       the four-node baseline pays four; charging zero here, as the first version did, flattered
 *       the baseline instead.</li>
 *   <li><b>Four destination validity buffers</b>, one per output, since four Arrow output vectors
 *       have four physical validity buffers however correlated their contents are. The first
 *       version wrote one shared buffer, which flattered the ceiling.</li>
 * </ul>
 *
 * <p>Two places where this kernel is still slightly better than what the emitter would produce,
 * both known and both small: {@code januaryTurned} is computed once and read by the year and
 * month tails, and {@code quarter} reads the {@code month} the month tail already built. The
 * emitter's per-node tails recompute both, about 5 vector ops out of ~65. The number below is
 * therefore a ceiling in the strict sense - the emitted shared path should land just under it.
 *
 * <p>The arithmetic is a lane-for-lane transcription of {@code VarkaChrono.narrowed} /
 * {@code VarkaChrono.fromEra} and of the bytecode {@code VarkaLoopEmitter.emitChrono} emits for
 * each field today - same magic constants, same correction steps, same order of operations - so
 * that the only thing this measures is sharing the decomposition, not a different algorithm.
 */
public final class ChronoVectorOps {

  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
  private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

  /** Output order of the {@code dstData}/{@code dstValidity} arrays. */
  public static final int YEAR = 0;
  public static final int MONTH = 1;
  public static final int DAY_OF_MONTH = 2;
  public static final int QUARTER = 3;

  /**
   * Mirrors {@code VarkaFusedKernel.STATUS_CHRONO_RANGE} (bit 0), which this module cannot
   * reference; a non-zero return means the batch must be recomputed on the row engine.
   */
  public static final int STATUS_CHRONO_RANGE = 1;

  // The narrowed civil-from-days constants, copied from VarkaChrono (catalyst) rather than
  // referenced - the engine module cannot depend on catalyst. Keep these in lockstep with
  // VarkaChrono by hand; ChronoVectorOpsTest checks this kernel against java.time directly,
  // so a drift here fails a test rather than silently mismeasuring the ceiling.
  private static final int ERA_DAYS = 146097;
  private static final int CENTURY_DAYS = 36524;
  private static final int MARCH_EPOCH_SHIFT = 719468;
  private static final int NARROW_ERA_BIAS = 32;
  private static final int NARROW_BIAS = MARCH_EPOCH_SHIFT + NARROW_ERA_BIAS * ERA_DAYS;
  private static final int NARROW_MIN_DAYS = -NARROW_BIAS;
  private static final int NARROW_MAX_DAYS = NARROW_MIN_DAYS + (1 << 24) - 1;
  private static final int NARROW_ERA_M = 114;
  private static final int NARROW_ERA_K = 24;
  private static final int CENTURY_M = 7349;
  private static final int CENTURY_K = 28;
  private static final int YEAR_M = 45966;
  private static final int YEAR_K = 24;
  private static final int MONTH_M = 877241;
  private static final int MONTH_K = 27;
  private static final int DAY_M = 838861;
  private static final int DAY_K = 22;
  private static final int QUARTER_M = 89478486;
  private static final int QUARTER_K = 28;
  private static final int MARCH_YEAR_JANUARY = 10;

  private ChronoVectorOps() {}

  /**
   * dst[YEAR|MONTH|DAY_OF_MONTH|QUARTER][i] = the four fields of {@code src[i]}'s
   * civil-from-days decomposition; every dst null iff src is null.
   *
   * <p>The parameter shape mirrors {@code VarkaFusedKernel.run} minus its scalar arguments -
   * per-output address arrays read once in the prologue, exactly as the emitted kernel reads
   * them - so the ceiling is not measured against a friendlier ABI than the baseline's.
   *
   * @param srcData address of the source int32 day values.
   * @param srcValidity address of the source bit-packed validity; ignored (may be 0L) when
   *        {@code srcNullCount == 0} or {@code srcNullCount == length}.
   * @param srcNullCount number of null rows in the source.
   * @param dstData four addresses of int32 destination values (length * 4 bytes each).
   * @param dstValidity four addresses of bit-packed destination validity
   *        ((length + 7) / 8 bytes each).
   * @param length number of rows.
   * @return 0 when every row was in the narrowed range, {@link #STATUS_CHRONO_RANGE} otherwise,
   *         in which case the outputs are not valid and the batch belongs on the row engine.
   */
  public static int vectorFourFields(long srcData, long srcValidity, int srcNullCount,
      long[] dstData, long[] dstValidity, int length) {
    if (length <= 0) {
      return 0;
    }
    MemorySegment src = ofAddress(srcData, length * 4L);
    MemorySegment yearSeg = ofAddress(dstData[YEAR], length * 4L);
    MemorySegment monthSeg = ofAddress(dstData[MONTH], length * 4L);
    MemorySegment daySeg = ofAddress(dstData[DAY_OF_MONTH], length * 4L);
    MemorySegment quarterSeg = ofAddress(dstData[QUARTER], length * 4L);
    long validityBytes = (length + 7) / 8L;
    MemorySegment yearValSeg = ofAddress(dstValidity[YEAR], validityBytes);
    MemorySegment monthValSeg = ofAddress(dstValidity[MONTH], validityBytes);
    MemorySegment dayValSeg = ofAddress(dstValidity[DAY_OF_MONTH], validityBytes);
    MemorySegment quarterValSeg = ofAddress(dstValidity[QUARTER], validityBytes);
    zero(yearValSeg);
    zero(monthValSeg);
    zero(dayValSeg);
    zero(quarterValSeg);
    if (srcNullCount == length) {
      return 0;
    }
    boolean hasNulls = srcNullCount > 0;
    MemorySegment validity = hasNulls ? ofAddress(srcValidity, validityBytes) : null;

    // One accumulator for the whole batch, as the emitter allocates one guard accumulator per
    // body method rather than one per node: the caller acts on the batch, not on the lane.
    VectorMask<Integer> guard = VectorMask.fromLong(SPECIES, 0L);

    long loopBound = SPECIES.loopBound(length);
    int lanes = SPECIES.length();
    long i = 0;
    for (; i < loopBound; i += lanes) {
      VectorMask<Integer> mask = hasNulls
          ? VectorMask.fromLong(SPECIES, validityBitsAt(validity, i, lanes))
          : VectorMask.fromLong(SPECIES, -1L);
      long byteOffset = i * 4L;
      IntVector days = IntVector.fromMemorySegment(SPECIES, src, byteOffset, ORDER, mask);

      // ---- the shared decomposition, hand-inlined; see the class doc before refactoring ----

      // The guard, narrowed by the row's validity: a null row's data bytes are undefined, so an
      // out-of-range value under one must not condemn the batch.
      guard = guard.or(days.compare(VectorOperators.LT, NARROW_MIN_DAYS)
          .or(days.compare(VectorOperators.GT, NARROW_MAX_DAYS))
          .and(mask));

      // w = days + BIAS, non-negative throughout the range, so one round-down magic and one
      // carry give the era - and the bias's whole eras come back off in the year assembly.
      IntVector rem = days.add(NARROW_BIAS);
      IntVector era = rem.mul(NARROW_ERA_M).lanewise(VectorOperators.LSHR, NARROW_ERA_K);
      rem = rem.sub(era.mul(ERA_DAYS));
      VectorMask<Integer> carry = rem.compare(VectorOperators.GE, ERA_DAYS);
      era = era.add(1, carry);
      rem = rem.sub(ERA_DAYS, carry);
      era = era.sub(NARROW_ERA_BIAS);

      // rem is now the day of era, in [0, 146096].
      // century = (doe * M) >>> K, then doc = doe - century * 36524, with one carry.
      IntVector century = rem.mul(CENTURY_M).lanewise(VectorOperators.LSHR, CENTURY_K);
      rem = rem.sub(century.mul(CENTURY_DAYS));
      carry = rem.compare(VectorOperators.GE, CENTURY_DAYS);
      century = century.add(1, carry);
      rem = rem.sub(CENTURY_DAYS, carry);

      // An era's fourth century holds one extra day - its leap day - so the quotient can land
      // on 4 for exactly one day of each era. Fold that back into century 3.
      carry = century.compare(VectorOperators.EQ, 4);
      century = century.sub(1, carry);
      rem = rem.add(CENTURY_DAYS, carry);

      // yoc = doc / 365 - exact here, because the split into centuries left a dividend under
      // 44859. It ignores leap days, so it can name the following year; the fix is below.
      IntVector yearOfCentury = rem.mul(YEAR_M).lanewise(VectorOperators.LSHR, YEAR_K);

      // doy = doc - (365 * yoc + yoc / 4). Negative exactly where yoc overshot.
      rem = rem.sub(yearOfCentury.mul(365)
          .add(yearOfCentury.lanewise(VectorOperators.LSHR, 2)));

      // Where it overshot, step back a year and give the days back - one more when the year we
      // step into is a leap year, which in a March-based year is simply yoc divisible by four.
      VectorMask<Integer> negDoy = rem.compare(VectorOperators.LT, 0);
      VectorMask<Integer> leap =
          yearOfCentury.and(3).compare(VectorOperators.EQ, 0).and(negDoy);
      rem = rem.add(365, negDoy).add(1, leap);
      yearOfCentury = yearOfCentury.sub(1, negDoy);

      // mp = (5 * doy + 2) / 153: the March-based month, 0 for March through 11 for February.
      IntVector marchMonth = rem.mul(5).add(2)
          .mul(MONTH_M).lanewise(VectorOperators.LSHR, MONTH_K);
      VectorMask<Integer> januaryTurned =
          marchMonth.compare(VectorOperators.GE, MARCH_YEAR_JANUARY);

      // ---- the four tails ----

      IntVector year = era.mul(400).add(century.mul(100)).add(yearOfCentury)
          .add(1, januaryTurned);
      IntVector month = marchMonth.add(3).sub(12, januaryTurned);
      IntVector dayOfMonth = rem.sub(marchMonth.mul(153).add(2)
          .mul(DAY_M).lanewise(VectorOperators.LSHR, DAY_K)).add(1);
      IntVector quarter = month.add(2)
          .mul(QUARTER_M).lanewise(VectorOperators.LSHR, QUARTER_K);

      year.intoMemorySegment(yearSeg, byteOffset, ORDER, mask);
      month.intoMemorySegment(monthSeg, byteOffset, ORDER, mask);
      dayOfMonth.intoMemorySegment(daySeg, byteOffset, ORDER, mask);
      quarter.intoMemorySegment(quarterSeg, byteOffset, ORDER, mask);
      long bits = mask.toLong();
      orValidityBitsAt(yearValSeg, i, bits, lanes);
      orValidityBitsAt(monthValSeg, i, bits, lanes);
      orValidityBitsAt(dayValSeg, i, bits, lanes);
      orValidityBitsAt(quarterValSeg, i, bits, lanes);
    }
    boolean declined = guard.anyTrue();
    if (i < length) {
      declined |= fourFieldsEpilogue(src, yearSeg, monthSeg, daySeg, quarterSeg,
          yearValSeg, monthValSeg, dayValSeg, quarterValSeg, validity, hasNulls, i, length);
    }
    return declined ? STATUS_CHRONO_RANGE : 0;
  }

  /**
   * {@link #vectorFourFields}'s final partial lane group; see {@code DateVectorOps}'s class doc,
   * step 5, for why this is its own method rather than inlined into the loop's. It runs once per
   * call rather than once per lane group, so its own inlining does not matter - but the
   * decomposition is still written out by hand here, because the emitter's
   * {@code epilogueDense}/{@code epilogueMasked} pair carries its own copy of the same bytes
   * rather than calling the loop's.
   *
   * @return whether any in-bounds, non-null row of this group was outside the narrowed range.
   */
  private static boolean fourFieldsEpilogue(MemorySegment src, MemorySegment yearSeg,
      MemorySegment monthSeg, MemorySegment daySeg, MemorySegment quarterSeg,
      MemorySegment yearValSeg, MemorySegment monthValSeg, MemorySegment dayValSeg,
      MemorySegment quarterValSeg, MemorySegment validity, boolean hasNulls,
      long i, int length) {
    int rows = (int) (length - i);
    VectorMask<Integer> bounds = SPECIES.indexInRange((int) i, length);
    VectorMask<Integer> mask = hasNulls
        ? VectorMask.fromLong(SPECIES, partialValidityBitsAt(validity, i, rows)).and(bounds)
        : bounds;
    long byteOffset = i * 4L;
    IntVector days = IntVector.fromMemorySegment(SPECIES, src, byteOffset, ORDER, mask);

    // The guard, narrowed by validity AND the bounds mask. Both narrowings are load-bearing;
    // VarkaLoopEmitter.emitEra's javadoc has the failure each one prevents. A masked load fills
    // the lanes past `length` with 0, which is in range, but a computed input maps 0 wherever it
    // likes - so the bounds mask is not redundant there, and is kept here for the same reason.
    VectorMask<Integer> guard = days.compare(VectorOperators.LT, NARROW_MIN_DAYS)
        .or(days.compare(VectorOperators.GT, NARROW_MAX_DAYS))
        .and(mask);

    IntVector rem = days.add(NARROW_BIAS);
    IntVector era = rem.mul(NARROW_ERA_M).lanewise(VectorOperators.LSHR, NARROW_ERA_K);
    rem = rem.sub(era.mul(ERA_DAYS));
    VectorMask<Integer> carry = rem.compare(VectorOperators.GE, ERA_DAYS);
    era = era.add(1, carry);
    rem = rem.sub(ERA_DAYS, carry);
    era = era.sub(NARROW_ERA_BIAS);

    IntVector century = rem.mul(CENTURY_M).lanewise(VectorOperators.LSHR, CENTURY_K);
    rem = rem.sub(century.mul(CENTURY_DAYS));
    carry = rem.compare(VectorOperators.GE, CENTURY_DAYS);
    century = century.add(1, carry);
    rem = rem.sub(CENTURY_DAYS, carry);

    carry = century.compare(VectorOperators.EQ, 4);
    century = century.sub(1, carry);
    rem = rem.add(CENTURY_DAYS, carry);

    IntVector yearOfCentury = rem.mul(YEAR_M).lanewise(VectorOperators.LSHR, YEAR_K);
    rem = rem.sub(yearOfCentury.mul(365)
        .add(yearOfCentury.lanewise(VectorOperators.LSHR, 2)));

    VectorMask<Integer> negDoy = rem.compare(VectorOperators.LT, 0);
    VectorMask<Integer> leap =
        yearOfCentury.and(3).compare(VectorOperators.EQ, 0).and(negDoy);
    rem = rem.add(365, negDoy).add(1, leap);
    yearOfCentury = yearOfCentury.sub(1, negDoy);

    IntVector marchMonth = rem.mul(5).add(2)
        .mul(MONTH_M).lanewise(VectorOperators.LSHR, MONTH_K);
    VectorMask<Integer> januaryTurned =
        marchMonth.compare(VectorOperators.GE, MARCH_YEAR_JANUARY);

    IntVector year = era.mul(400).add(century.mul(100)).add(yearOfCentury)
        .add(1, januaryTurned);
    IntVector month = marchMonth.add(3).sub(12, januaryTurned);
    IntVector dayOfMonth = rem.sub(marchMonth.mul(153).add(2)
        .mul(DAY_M).lanewise(VectorOperators.LSHR, DAY_K)).add(1);
    IntVector quarter = month.add(2)
        .mul(QUARTER_M).lanewise(VectorOperators.LSHR, QUARTER_K);

    year.intoMemorySegment(yearSeg, byteOffset, ORDER, mask);
    month.intoMemorySegment(monthSeg, byteOffset, ORDER, mask);
    dayOfMonth.intoMemorySegment(daySeg, byteOffset, ORDER, mask);
    quarter.intoMemorySegment(quarterSeg, byteOffset, ORDER, mask);
    long bits = mask.toLong();
    orPartialValidityBitsAt(yearValSeg, i, bits, rows);
    orPartialValidityBitsAt(monthValSeg, i, bits, rows);
    orPartialValidityBitsAt(dayValSeg, i, bits, rows);
    orPartialValidityBitsAt(quarterValSeg, i, bits, rows);
    return guard.anyTrue();
  }

  /**
   * The same four fields, the same arithmetic and the same op count as
   * {@link #vectorFourFields}, scheduled to keep fewer values live at once. The shared
   * decomposition measures ~1.5x at AVX-512 and nothing reliable at 128-bit, where 16 vector
   * registers must also hold the masks; this variant exists to measure how much of that gap is
   * the schedule rather than the sharing.
   *
   * <p>Two differences, both of which only move code, never change it:
   *
   * <ol>
   *   <li><b>The year assembly is hoisted.</b> {@code 400 * era + 100 * century + yoc} is formed
   *       as soon as {@code yoc} is final, before the March-month step, so {@code era} and
   *       {@code century} die there instead of staying live across every tail. Three live values
   *       become one.</li>
   *   <li><b>Each output is stored as soon as it exists</b>, rather than computing all four and
   *       then storing all four - so at most one finished output is live at a time instead of
   *       four. This is also what {@code VarkaLoopEmitter.emitLaneGroup} does, which emits a
   *       store and a validity OR per output before it walks the next one, so on this point
   *       {@link #vectorFourFields} is the variant that does <i>not</i> mirror the emitter.</li>
   * </ol>
   *
   * <p>The tails are ordered by what they retire: {@code dayofmonth} first because it is the only
   * one that reads the day of year, then {@code year}, then {@code month} and the {@code quarter}
   * that reads it.
   *
   * <p>Everything in the class doc about hand-inlining applies here too: no method call in the
   * lane path, and do not refactor the arithmetic into a helper.
   */
  public static int vectorFourFieldsShortLive(long srcData, long srcValidity, int srcNullCount,
      long[] dstData, long[] dstValidity, int length) {
    if (length <= 0) {
      return 0;
    }
    MemorySegment src = ofAddress(srcData, length * 4L);
    MemorySegment yearSeg = ofAddress(dstData[YEAR], length * 4L);
    MemorySegment monthSeg = ofAddress(dstData[MONTH], length * 4L);
    MemorySegment daySeg = ofAddress(dstData[DAY_OF_MONTH], length * 4L);
    MemorySegment quarterSeg = ofAddress(dstData[QUARTER], length * 4L);
    long validityBytes = (length + 7) / 8L;
    MemorySegment yearValSeg = ofAddress(dstValidity[YEAR], validityBytes);
    MemorySegment monthValSeg = ofAddress(dstValidity[MONTH], validityBytes);
    MemorySegment dayValSeg = ofAddress(dstValidity[DAY_OF_MONTH], validityBytes);
    MemorySegment quarterValSeg = ofAddress(dstValidity[QUARTER], validityBytes);
    zero(yearValSeg);
    zero(monthValSeg);
    zero(dayValSeg);
    zero(quarterValSeg);
    if (srcNullCount == length) {
      return 0;
    }
    boolean hasNulls = srcNullCount > 0;
    MemorySegment validity = hasNulls ? ofAddress(srcValidity, validityBytes) : null;

    VectorMask<Integer> guard = VectorMask.fromLong(SPECIES, 0L);

    long loopBound = SPECIES.loopBound(length);
    int lanes = SPECIES.length();
    long i = 0;
    for (; i < loopBound; i += lanes) {
      VectorMask<Integer> mask = hasNulls
          ? VectorMask.fromLong(SPECIES, validityBitsAt(validity, i, lanes))
          : VectorMask.fromLong(SPECIES, -1L);
      long byteOffset = i * 4L;
      IntVector days = IntVector.fromMemorySegment(SPECIES, src, byteOffset, ORDER, mask);

      guard = guard.or(days.compare(VectorOperators.LT, NARROW_MIN_DAYS)
          .or(days.compare(VectorOperators.GT, NARROW_MAX_DAYS))
          .and(mask));

      IntVector rem = days.add(NARROW_BIAS);
      IntVector era = rem.mul(NARROW_ERA_M).lanewise(VectorOperators.LSHR, NARROW_ERA_K);
      rem = rem.sub(era.mul(ERA_DAYS));
      VectorMask<Integer> carry = rem.compare(VectorOperators.GE, ERA_DAYS);
      era = era.add(1, carry);
      rem = rem.sub(ERA_DAYS, carry);
      era = era.sub(NARROW_ERA_BIAS);

      IntVector century = rem.mul(CENTURY_M).lanewise(VectorOperators.LSHR, CENTURY_K);
      rem = rem.sub(century.mul(CENTURY_DAYS));
      carry = rem.compare(VectorOperators.GE, CENTURY_DAYS);
      century = century.add(1, carry);
      rem = rem.sub(CENTURY_DAYS, carry);

      carry = century.compare(VectorOperators.EQ, 4);
      century = century.sub(1, carry);
      rem = rem.add(CENTURY_DAYS, carry);

      IntVector yearOfCentury = rem.mul(YEAR_M).lanewise(VectorOperators.LSHR, YEAR_K);
      rem = rem.sub(yearOfCentury.mul(365)
          .add(yearOfCentury.lanewise(VectorOperators.LSHR, 2)));

      VectorMask<Integer> negDoy = rem.compare(VectorOperators.LT, 0);
      VectorMask<Integer> leap =
          yearOfCentury.and(3).compare(VectorOperators.EQ, 0).and(negDoy);
      rem = rem.add(365, negDoy).add(1, leap);
      yearOfCentury = yearOfCentury.sub(1, negDoy);

      // The year, all but its January correction, formed here so that era, century and the year
      // of century all die before the tails rather than staying live across them.
      IntVector year = era.mul(400).add(century.mul(100)).add(yearOfCentury);

      IntVector marchMonth = rem.mul(5).add(2)
          .mul(MONTH_M).lanewise(VectorOperators.LSHR, MONTH_K);
      VectorMask<Integer> januaryTurned =
          marchMonth.compare(VectorOperators.GE, MARCH_YEAR_JANUARY);
      long bits = mask.toLong();

      // dayofmonth first: the only tail that reads the day of year, so `rem` dies here.
      rem.sub(marchMonth.mul(153).add(2)
              .mul(DAY_M).lanewise(VectorOperators.LSHR, DAY_K)).add(1)
          .intoMemorySegment(daySeg, byteOffset, ORDER, mask);
      orValidityBitsAt(dayValSeg, i, bits, lanes);

      year.add(1, januaryTurned).intoMemorySegment(yearSeg, byteOffset, ORDER, mask);
      orValidityBitsAt(yearValSeg, i, bits, lanes);

      IntVector month = marchMonth.add(3).sub(12, januaryTurned);
      month.add(2).mul(QUARTER_M).lanewise(VectorOperators.LSHR, QUARTER_K)
          .intoMemorySegment(quarterSeg, byteOffset, ORDER, mask);
      orValidityBitsAt(quarterValSeg, i, bits, lanes);
      month.intoMemorySegment(monthSeg, byteOffset, ORDER, mask);
      orValidityBitsAt(monthValSeg, i, bits, lanes);
    }
    boolean declined = guard.anyTrue();
    if (i < length) {
      declined |= shortLiveEpilogue(src, yearSeg, monthSeg, daySeg, quarterSeg,
          yearValSeg, monthValSeg, dayValSeg, quarterValSeg, validity, hasNulls, i, length);
    }
    return declined ? STATUS_CHRONO_RANGE : 0;
  }

  /** {@link #vectorFourFieldsShortLive}'s final partial lane group; see
   * {@link #fourFieldsEpilogue} for why it is its own method and still hand-inlined. */
  private static boolean shortLiveEpilogue(MemorySegment src, MemorySegment yearSeg,
      MemorySegment monthSeg, MemorySegment daySeg, MemorySegment quarterSeg,
      MemorySegment yearValSeg, MemorySegment monthValSeg, MemorySegment dayValSeg,
      MemorySegment quarterValSeg, MemorySegment validity, boolean hasNulls,
      long i, int length) {
    int rows = (int) (length - i);
    VectorMask<Integer> bounds = SPECIES.indexInRange((int) i, length);
    VectorMask<Integer> mask = hasNulls
        ? VectorMask.fromLong(SPECIES, partialValidityBitsAt(validity, i, rows)).and(bounds)
        : bounds;
    long byteOffset = i * 4L;
    IntVector days = IntVector.fromMemorySegment(SPECIES, src, byteOffset, ORDER, mask);

    VectorMask<Integer> guard = days.compare(VectorOperators.LT, NARROW_MIN_DAYS)
        .or(days.compare(VectorOperators.GT, NARROW_MAX_DAYS))
        .and(mask);

    IntVector rem = days.add(NARROW_BIAS);
    IntVector era = rem.mul(NARROW_ERA_M).lanewise(VectorOperators.LSHR, NARROW_ERA_K);
    rem = rem.sub(era.mul(ERA_DAYS));
    VectorMask<Integer> carry = rem.compare(VectorOperators.GE, ERA_DAYS);
    era = era.add(1, carry);
    rem = rem.sub(ERA_DAYS, carry);
    era = era.sub(NARROW_ERA_BIAS);

    IntVector century = rem.mul(CENTURY_M).lanewise(VectorOperators.LSHR, CENTURY_K);
    rem = rem.sub(century.mul(CENTURY_DAYS));
    carry = rem.compare(VectorOperators.GE, CENTURY_DAYS);
    century = century.add(1, carry);
    rem = rem.sub(CENTURY_DAYS, carry);

    carry = century.compare(VectorOperators.EQ, 4);
    century = century.sub(1, carry);
    rem = rem.add(CENTURY_DAYS, carry);

    IntVector yearOfCentury = rem.mul(YEAR_M).lanewise(VectorOperators.LSHR, YEAR_K);
    rem = rem.sub(yearOfCentury.mul(365)
        .add(yearOfCentury.lanewise(VectorOperators.LSHR, 2)));

    VectorMask<Integer> negDoy = rem.compare(VectorOperators.LT, 0);
    VectorMask<Integer> leap =
        yearOfCentury.and(3).compare(VectorOperators.EQ, 0).and(negDoy);
    rem = rem.add(365, negDoy).add(1, leap);
    yearOfCentury = yearOfCentury.sub(1, negDoy);

    IntVector year = era.mul(400).add(century.mul(100)).add(yearOfCentury);

    IntVector marchMonth = rem.mul(5).add(2)
        .mul(MONTH_M).lanewise(VectorOperators.LSHR, MONTH_K);
    VectorMask<Integer> januaryTurned =
        marchMonth.compare(VectorOperators.GE, MARCH_YEAR_JANUARY);
    long bits = mask.toLong();

    rem.sub(marchMonth.mul(153).add(2)
            .mul(DAY_M).lanewise(VectorOperators.LSHR, DAY_K)).add(1)
        .intoMemorySegment(daySeg, byteOffset, ORDER, mask);
    orPartialValidityBitsAt(dayValSeg, i, bits, rows);

    year.add(1, januaryTurned).intoMemorySegment(yearSeg, byteOffset, ORDER, mask);
    orPartialValidityBitsAt(yearValSeg, i, bits, rows);

    IntVector month = marchMonth.add(3).sub(12, januaryTurned);
    month.add(2).mul(QUARTER_M).lanewise(VectorOperators.LSHR, QUARTER_K)
        .intoMemorySegment(quarterSeg, byteOffset, ORDER, mask);
    orPartialValidityBitsAt(quarterValSeg, i, bits, rows);
    month.intoMemorySegment(monthSeg, byteOffset, ORDER, mask);
    orPartialValidityBitsAt(monthValSeg, i, bits, rows);
    return guard.anyTrue();
  }

  /**
   * The same four fields, the same arithmetic, the same guard and the same op count as
   * {@link #vectorFourFields}, with every destination validity buffer and every
   * {@code orValidityBitsAt}/{@code orPartialValidityBitsAt} call removed. Section 2.17 of
   * {@code m4/PLAN.md} fits three throughput points to a per-vector-op cost and finds
   * three quarters of the shared kernel's time unaccounted for by the decomposition - the
   * suspect named there is the validity write, on the strength of {@code -XX:+PrintInlining}
   * showing {@code orValidityBitsAt} does not inline in a wide loop, not on a timing. This
   * variant is the bound that turns the suspicion into a number: whatever time it does not
   * spend against {@link #vectorFourFields} is validity bookkeeping, not arithmetic, since
   * every other line here is identical.
   *
   * <p>Every row is assumed non-null and every output is reported valid, unconditionally - this
   * is not a shippable null-free fast path (that would still owe one write per batch), it is a
   * kernel with the validity work deleted rather than made cheap, built to
   * measure a ceiling rather than to ship.
   *
   * @param srcData address of the source int32 day values; every row is assumed non-null.
   * @param dstData four addresses of int32 destination values (length * 4 bytes each).
   * @param length number of rows.
   * @return 0 when every row was in the narrowed range, {@link #STATUS_CHRONO_RANGE} otherwise.
   */
  public static int vectorFourFieldsNoValidity(long srcData, long[] dstData, int length) {
    if (length <= 0) {
      return 0;
    }
    MemorySegment src = ofAddress(srcData, length * 4L);
    MemorySegment yearSeg = ofAddress(dstData[YEAR], length * 4L);
    MemorySegment monthSeg = ofAddress(dstData[MONTH], length * 4L);
    MemorySegment daySeg = ofAddress(dstData[DAY_OF_MONTH], length * 4L);
    MemorySegment quarterSeg = ofAddress(dstData[QUARTER], length * 4L);

    VectorMask<Integer> guard = VectorMask.fromLong(SPECIES, 0L);

    long loopBound = SPECIES.loopBound(length);
    int lanes = SPECIES.length();
    long i = 0;
    for (; i < loopBound; i += lanes) {
      long byteOffset = i * 4L;
      IntVector days = IntVector.fromMemorySegment(SPECIES, src, byteOffset, ORDER);

      // ---- the shared decomposition, hand-inlined; see the class doc before refactoring ----

      guard = guard.or(days.compare(VectorOperators.LT, NARROW_MIN_DAYS))
          .or(days.compare(VectorOperators.GT, NARROW_MAX_DAYS));

      IntVector rem = days.add(NARROW_BIAS);
      IntVector era = rem.mul(NARROW_ERA_M).lanewise(VectorOperators.LSHR, NARROW_ERA_K);
      rem = rem.sub(era.mul(ERA_DAYS));
      VectorMask<Integer> carry = rem.compare(VectorOperators.GE, ERA_DAYS);
      era = era.add(1, carry);
      rem = rem.sub(ERA_DAYS, carry);
      era = era.sub(NARROW_ERA_BIAS);

      IntVector century = rem.mul(CENTURY_M).lanewise(VectorOperators.LSHR, CENTURY_K);
      rem = rem.sub(century.mul(CENTURY_DAYS));
      carry = rem.compare(VectorOperators.GE, CENTURY_DAYS);
      century = century.add(1, carry);
      rem = rem.sub(CENTURY_DAYS, carry);

      carry = century.compare(VectorOperators.EQ, 4);
      century = century.sub(1, carry);
      rem = rem.add(CENTURY_DAYS, carry);

      IntVector yearOfCentury = rem.mul(YEAR_M).lanewise(VectorOperators.LSHR, YEAR_K);
      rem = rem.sub(yearOfCentury.mul(365)
          .add(yearOfCentury.lanewise(VectorOperators.LSHR, 2)));

      VectorMask<Integer> negDoy = rem.compare(VectorOperators.LT, 0);
      VectorMask<Integer> leap =
          yearOfCentury.and(3).compare(VectorOperators.EQ, 0).and(negDoy);
      rem = rem.add(365, negDoy).add(1, leap);
      yearOfCentury = yearOfCentury.sub(1, negDoy);

      IntVector marchMonth = rem.mul(5).add(2)
          .mul(MONTH_M).lanewise(VectorOperators.LSHR, MONTH_K);
      VectorMask<Integer> januaryTurned =
          marchMonth.compare(VectorOperators.GE, MARCH_YEAR_JANUARY);

      // ---- the four tails ----

      IntVector year = era.mul(400).add(century.mul(100)).add(yearOfCentury)
          .add(1, januaryTurned);
      IntVector month = marchMonth.add(3).sub(12, januaryTurned);
      IntVector dayOfMonth = rem.sub(marchMonth.mul(153).add(2)
          .mul(DAY_M).lanewise(VectorOperators.LSHR, DAY_K)).add(1);
      IntVector quarter = month.add(2)
          .mul(QUARTER_M).lanewise(VectorOperators.LSHR, QUARTER_K);

      year.intoMemorySegment(yearSeg, byteOffset, ORDER);
      month.intoMemorySegment(monthSeg, byteOffset, ORDER);
      dayOfMonth.intoMemorySegment(daySeg, byteOffset, ORDER);
      quarter.intoMemorySegment(quarterSeg, byteOffset, ORDER);
    }
    boolean declined = guard.anyTrue();
    if (i < length) {
      declined |= noValidityEpilogue(src, yearSeg, monthSeg, daySeg, quarterSeg, i, length);
    }
    return declined ? STATUS_CHRONO_RANGE : 0;
  }

  /** {@link #vectorFourFieldsNoValidity}'s final partial lane group; see
   * {@link #fourFieldsEpilogue}. */
  private static boolean noValidityEpilogue(MemorySegment src, MemorySegment yearSeg,
      MemorySegment monthSeg, MemorySegment daySeg, MemorySegment quarterSeg,
      long i, int length) {
    VectorMask<Integer> mask = SPECIES.indexInRange((int) i, length);
    long byteOffset = i * 4L;
    IntVector days = IntVector.fromMemorySegment(SPECIES, src, byteOffset, ORDER, mask);

    VectorMask<Integer> guard = days.compare(VectorOperators.LT, NARROW_MIN_DAYS)
        .or(days.compare(VectorOperators.GT, NARROW_MAX_DAYS))
        .and(mask);

    IntVector rem = days.add(NARROW_BIAS);
    IntVector era = rem.mul(NARROW_ERA_M).lanewise(VectorOperators.LSHR, NARROW_ERA_K);
    rem = rem.sub(era.mul(ERA_DAYS));
    VectorMask<Integer> carry = rem.compare(VectorOperators.GE, ERA_DAYS);
    era = era.add(1, carry);
    rem = rem.sub(ERA_DAYS, carry);
    era = era.sub(NARROW_ERA_BIAS);

    IntVector century = rem.mul(CENTURY_M).lanewise(VectorOperators.LSHR, CENTURY_K);
    rem = rem.sub(century.mul(CENTURY_DAYS));
    carry = rem.compare(VectorOperators.GE, CENTURY_DAYS);
    century = century.add(1, carry);
    rem = rem.sub(CENTURY_DAYS, carry);

    carry = century.compare(VectorOperators.EQ, 4);
    century = century.sub(1, carry);
    rem = rem.add(CENTURY_DAYS, carry);

    IntVector yearOfCentury = rem.mul(YEAR_M).lanewise(VectorOperators.LSHR, YEAR_K);
    rem = rem.sub(yearOfCentury.mul(365)
        .add(yearOfCentury.lanewise(VectorOperators.LSHR, 2)));

    VectorMask<Integer> negDoy = rem.compare(VectorOperators.LT, 0);
    VectorMask<Integer> leap =
        yearOfCentury.and(3).compare(VectorOperators.EQ, 0).and(negDoy);
    rem = rem.add(365, negDoy).add(1, leap);
    yearOfCentury = yearOfCentury.sub(1, negDoy);

    IntVector marchMonth = rem.mul(5).add(2)
        .mul(MONTH_M).lanewise(VectorOperators.LSHR, MONTH_K);
    VectorMask<Integer> januaryTurned =
        marchMonth.compare(VectorOperators.GE, MARCH_YEAR_JANUARY);

    IntVector year = era.mul(400).add(century.mul(100)).add(yearOfCentury)
        .add(1, januaryTurned);
    IntVector month = marchMonth.add(3).sub(12, januaryTurned);
    IntVector dayOfMonth = rem.sub(marchMonth.mul(153).add(2)
        .mul(DAY_M).lanewise(VectorOperators.LSHR, DAY_K)).add(1);
    IntVector quarter = month.add(2)
        .mul(QUARTER_M).lanewise(VectorOperators.LSHR, QUARTER_K);

    year.intoMemorySegment(yearSeg, byteOffset, ORDER, mask);
    month.intoMemorySegment(monthSeg, byteOffset, ORDER, mask);
    dayOfMonth.intoMemorySegment(daySeg, byteOffset, ORDER, mask);
    quarter.intoMemorySegment(quarterSeg, byteOffset, ORDER, mask);
    return guard.anyTrue();
  }
}
