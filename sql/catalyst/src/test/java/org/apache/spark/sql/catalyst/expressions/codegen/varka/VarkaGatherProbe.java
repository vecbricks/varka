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

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.time.LocalDate;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Two ways to get a year from an epoch day on int lanes, so the parity benchmark can price a
 * <b>gather</b> against arithmetic. Written because a widely deployed row engine (Impala) reads
 * the year out of a day-indexed lookup table for dates in 1950-2049 and computes it only outside
 * that window, and the milestone's item 9 rests on an assumption about what a gather costs.
 *
 * <p>This is a capability probe, not a kernel. Varka cannot emit either of these: the emitter
 * has no gather, and - the reason that is not merely an omission - {@code IntVector} offers an
 * index-map overload only on {@code fromArray}, never on {@code fromMemorySegment}, while every
 * Varka input is an off-heap Arrow buffer. See {@code m4/PLAN.md} item 9.
 */
public final class VarkaGatherProbe {

  private VarkaGatherProbe() {
  }

  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

  /** 1 January 1950, the first day Impala's table covers. */
  public static final int MIN_DAY_MAPPED = -7305;

  /** 31 December 2049, the last. */
  public static final int MAX_DAY_MAPPED = 29219;

  /** The year of every day in that window: 36525 ints, about 143 KB. */
  public static final int[] YEAR_BY_DAY = buildTable();

  private static int[] buildTable() {
    int[] table = new int[MAX_DAY_MAPPED - MIN_DAY_MAPPED + 1];
    for (int i = 0; i < table.length; i++) {
      table[i] = LocalDate.ofEpochDay(MIN_DAY_MAPPED + i).getYear();
    }
    return table;
  }

  /**
   * The year of each day, read from {@link #YEAR_BY_DAY}. One index vector per lane group, spilled
   * to {@code indexScratch} because the gather takes its index map as an {@code int[]}, and one
   * {@code fromArray} with that map. Defined only over the mapped window.
   */
  public static void yearByLookup(int[] days, int[] out, int[] indexScratch) {
    for (int i = 0; i < days.length; i += SPECIES.length()) {
      IntVector.fromArray(SPECIES, days, i).sub(MIN_DAY_MAPPED).intoArray(indexScratch, 0);
      IntVector.fromArray(SPECIES, YEAR_BY_DAY, 0, indexScratch, 0).intoArray(out, i);
    }
  }

  /**
   * The year of each day by the civil-from-days arithmetic Varka emits: the narrowed era step,
   * the century and year-of-century divisions with their correction carries, and the March-based
   * month whose only use here is the January bit. Kept close to the emitted lowering so the
   * comparison is fair, and held to {@code LocalDate} by {@code VarkaGatherProbeSuite}.
   *
   * <p>This mirrors the lowering on master. VARKA-48 removes the month step from the year tail,
   * reading the January bit off the day of year instead, which takes four lane ops off this
   * body; the gather it is measured against is unaffected, so the ratio moves in arithmetic's
   * favour by that much once it lands.
   */
  public static void yearByArithmetic(int[] days, int[] out) {
    for (int i = 0; i < days.length; i += SPECIES.length()) {
      IntVector w = IntVector.fromArray(SPECIES, days, i).add(VarkaChrono.NARROW_BIAS);
      IntVector era = w.mul(VarkaChrono.NARROW_ERA_M)
          .lanewise(VectorOperators.LSHR, VarkaChrono.NARROW_ERA_K);
      IntVector rem = w.sub(era.mul(VarkaChrono.ERA_DAYS));
      VectorMask<Integer> carry = rem.compare(VectorOperators.GE, VarkaChrono.ERA_DAYS);
      era = era.add(1, carry);
      rem = rem.sub(VarkaChrono.ERA_DAYS, carry);

      IntVector century = rem.mul(VarkaChrono.CENTURY_M)
          .lanewise(VectorOperators.LSHR, VarkaChrono.CENTURY_K);
      IntVector doc = rem.sub(century.mul(VarkaChrono.CENTURY_DAYS));
      VectorMask<Integer> carry2 = doc.compare(VectorOperators.GE, VarkaChrono.CENTURY_DAYS);
      century = century.add(1, carry2);
      doc = doc.sub(VarkaChrono.CENTURY_DAYS, carry2);
      VectorMask<Integer> spill = century.compare(VectorOperators.EQ, 4);
      century = century.sub(1, spill);
      doc = doc.add(VarkaChrono.CENTURY_DAYS, spill);

      IntVector yoc = doc.mul(VarkaChrono.YEAR_M)
          .lanewise(VectorOperators.LSHR, VarkaChrono.YEAR_K);
      IntVector doy = doc.sub(yoc.mul(365).add(yoc.lanewise(VectorOperators.LSHR, 2)));
      VectorMask<Integer> over = doy.compare(VectorOperators.LT, 0);
      VectorMask<Integer> leap = yoc.and(3).compare(VectorOperators.EQ, 0).and(over);
      doy = doy.add(365, over).add(1, leap);
      yoc = yoc.sub(1, over);

      IntVector marchMonth = doy.mul(5).add(2).mul(VarkaChrono.MONTH_M)
          .lanewise(VectorOperators.LSHR, VarkaChrono.MONTH_K);
      era.mul(400).add(century.mul(100)).add(yoc).sub(VarkaChrono.NARROW_ERA_BIAS * 400)
          .add(1, marchMonth.compare(VectorOperators.GE, VarkaChrono.MARCH_YEAR_JANUARY))
          .intoArray(out, i);
    }
  }

  /**
   * The same table read by a plain scalar loop - no lanes, no index map, no spill. This is the
   * shape a row engine runs, and the one an emitter would produce if a calendar node lowered to
   * scalar code inside an otherwise vector kernel. It is here because the vector gather has to
   * pay for its index map twice, storing the index vector to an array and reading it back, and
   * that cost is an artifact of the API rather than of the hardware.
   */
  public static void yearByScalarLookup(int[] days, int[] out) {
    for (int i = 0; i < days.length; i++) {
      out[i] = YEAR_BY_DAY[days[i] - MIN_DAY_MAPPED];
    }
  }

  // --- Fused: year(d) = 1998, counted. The shape where lanes are supposed to pay. -----------

  /**
   * The filter Varka would emit today: the year computed in lanes, compared in lanes, and the
   * mask counted. Nothing leaves a vector register between the load and the count.
   */
  public static long fusedArithmetic(int[] days, int year) {
    long count = 0;
    for (int i = 0; i < days.length; i += SPECIES.length()) {
      count += yearVector(IntVector.fromArray(SPECIES, days, i))
          .compare(VectorOperators.EQ, year).trueCount();
    }
    return count;
  }

  /** The same filter with the year gathered from the table instead of computed. */
  public static long fusedGather(int[] days, int year, int[] indexScratch) {
    long count = 0;
    for (int i = 0; i < days.length; i += SPECIES.length()) {
      IntVector.fromArray(SPECIES, days, i).sub(MIN_DAY_MAPPED).intoArray(indexScratch, 0);
      count += IntVector.fromArray(SPECIES, YEAR_BY_DAY, 0, indexScratch, 0)
          .compare(VectorOperators.EQ, year).trueCount();
    }
    return count;
  }

  /** The same filter as a plain scalar loop - no lanes anywhere. */
  public static long fusedScalarLookup(int[] days, int year) {
    long count = 0;
    for (int i = 0; i < days.length; i++) {
      count += YEAR_BY_DAY[days[i] - MIN_DAY_MAPPED] == year ? 1 : 0;
    }
    return count;
  }

  /**
   * The shape an emitter would actually have to produce if one node in a fused vector kernel
   * lowered to scalar code: spill the lane group to an array, run the scalar lookup over it,
   * reload the result into a vector, and carry on in lanes. This is the case that decides
   * whether "emit scalar ops with a lookup table" is a real option, because a Varka kernel
   * never has its input in an {@code int[]} to begin with - it arrives in a vector register
   * from an off-heap segment, and the spill is the price of leaving it.
   */
  public static long fusedScalarInsideVector(int[] days, int year, int[] daysScratch,
      int[] yearScratch) {
    long count = 0;
    int lanes = SPECIES.length();
    for (int i = 0; i < days.length; i += lanes) {
      IntVector.fromArray(SPECIES, days, i).intoArray(daysScratch, 0);
      for (int j = 0; j < lanes; j++) {
        yearScratch[j] = YEAR_BY_DAY[daysScratch[j] - MIN_DAY_MAPPED];
      }
      count += IntVector.fromArray(SPECIES, yearScratch, 0)
          .compare(VectorOperators.EQ, year).trueCount();
    }
    return count;
  }

  // --- The era table: ClickHouse's shape, sized to the calendar's period ---------------------

  /**
   * The year within its era of every day of a 400-year era, indexed by day of era. 146097 entries,
   * about 571 KB.
   *
   * <p>The size is the idea, and it is ClickHouse's: its {@code DATE_LUT_SIZE} is {@code 0x23AB1},
   * which is 146097 - exactly one Gregorian era. ClickHouse anchors that window at 1900 and falls
   * back outside it, but 400 years is the calendar's <i>period</i>, so a table indexed by day of
   * era needs no fallback at all: every {@code int32} day reduces into it, and the reported year
   * is {@code 400 * (era - NARROW_ERA_BIAS) + table[dayOfEra]}. Varka's prefix already computes
   * both halves of that index - {@code emitEra} is the first thing it emits.
   */
  public static final int[] YEAR_OF_ERA = buildEraTable();

  /**
   * Day of era 0 is 1 March of an era-starting year, and 2000 is one, being divisible by 400. The
   * table holds the offset from that year, not the year, so the era term supplies the rest:
   * {@code 400 * (era - NARROW_ERA_BIAS)} is already 2000 for the era containing 2000-03-01, and
   * adding the base year again would double it.
   */
  private static final int ERA_TABLE_BASE_YEAR = 2000;

  private static int[] buildEraTable() {
    int[] table = new int[VarkaChrono.ERA_DAYS];
    LocalDate day = LocalDate.of(ERA_TABLE_BASE_YEAR, 3, 1);
    for (int i = 0; i < table.length; i++) {
      table[i] = day.getYear() - ERA_TABLE_BASE_YEAR;
      day = day.plusDays(1);
    }
    return table;
  }

  /**
   * {@code year(d) = year}, counted, over a column that lives <b>off-heap</b> - which is the shape
   * every Varka kernel actually has - with the year gathered from {@link #YEAR_OF_ERA}.
   *
   * <p>This is the case that distinguishes two things a single API fact was read as one. The
   * Vector API's index-map overload exists only on {@code fromArray}, so a gather cannot read
   * <i>from</i> an off-heap table; it says nothing about gathering an <i>on-heap</i> constant
   * table with indices derived from off-heap data, which is what a calendar table would be, and
   * which is what this measures.
   */
  public static long fusedEraTableFromSegment(MemorySegment days, int rows, int year,
      int[] indexScratch) {
    long count = 0;
    for (int i = 0; i < rows; i += SPECIES.length()) {
      IntVector w = IntVector.fromMemorySegment(SPECIES, days, i * 4L, ByteOrder.nativeOrder())
          .add(VarkaChrono.NARROW_BIAS);
      IntVector era = w.mul(VarkaChrono.NARROW_ERA_M)
          .lanewise(VectorOperators.LSHR, VarkaChrono.NARROW_ERA_K);
      IntVector doe = w.sub(era.mul(VarkaChrono.ERA_DAYS));
      VectorMask<Integer> carry = doe.compare(VectorOperators.GE, VarkaChrono.ERA_DAYS);
      era = era.add(1, carry);
      doe = doe.sub(VarkaChrono.ERA_DAYS, carry);
      doe.intoArray(indexScratch, 0);
      count += IntVector.fromArray(SPECIES, YEAR_OF_ERA, 0, indexScratch, 0)
          .add(era.sub(VarkaChrono.NARROW_ERA_BIAS).mul(400))
          .compare(VectorOperators.EQ, year).trueCount();
    }
    return count;
  }

  /** The same filter, same off-heap column, with the year computed rather than looked up. */
  public static long fusedArithmeticFromSegment(MemorySegment days, int rows, int year) {
    long count = 0;
    for (int i = 0; i < rows; i += SPECIES.length()) {
      count += yearVector(
          IntVector.fromMemorySegment(SPECIES, days, i * 4L, ByteOrder.nativeOrder()))
          .compare(VectorOperators.EQ, year).trueCount();
    }
    return count;
  }

  /** The civil-from-days year of one lane group, factored out so the fused cases share it. */
  private static IntVector yearVector(IntVector date) {
    IntVector w = date.add(VarkaChrono.NARROW_BIAS);
    IntVector era = w.mul(VarkaChrono.NARROW_ERA_M)
        .lanewise(VectorOperators.LSHR, VarkaChrono.NARROW_ERA_K);
    IntVector rem = w.sub(era.mul(VarkaChrono.ERA_DAYS));
    VectorMask<Integer> carry = rem.compare(VectorOperators.GE, VarkaChrono.ERA_DAYS);
    era = era.add(1, carry);
    rem = rem.sub(VarkaChrono.ERA_DAYS, carry);
    IntVector century = rem.mul(VarkaChrono.CENTURY_M)
        .lanewise(VectorOperators.LSHR, VarkaChrono.CENTURY_K);
    IntVector doc = rem.sub(century.mul(VarkaChrono.CENTURY_DAYS));
    VectorMask<Integer> carry2 = doc.compare(VectorOperators.GE, VarkaChrono.CENTURY_DAYS);
    century = century.add(1, carry2);
    doc = doc.sub(VarkaChrono.CENTURY_DAYS, carry2);
    VectorMask<Integer> spill = century.compare(VectorOperators.EQ, 4);
    century = century.sub(1, spill);
    doc = doc.add(VarkaChrono.CENTURY_DAYS, spill);
    IntVector yoc = doc.mul(VarkaChrono.YEAR_M)
        .lanewise(VectorOperators.LSHR, VarkaChrono.YEAR_K);
    IntVector doy = doc.sub(yoc.mul(365).add(yoc.lanewise(VectorOperators.LSHR, 2)));
    VectorMask<Integer> over = doy.compare(VectorOperators.LT, 0);
    VectorMask<Integer> leap = yoc.and(3).compare(VectorOperators.EQ, 0).and(over);
    doy = doy.add(365, over).add(1, leap);
    yoc = yoc.sub(1, over);
    IntVector marchMonth = doy.mul(5).add(2).mul(VarkaChrono.MONTH_M)
        .lanewise(VectorOperators.LSHR, VarkaChrono.MONTH_K);
    return era.mul(400).add(century.mul(100)).add(yoc).sub(VarkaChrono.NARROW_ERA_BIAS * 400)
        .add(1, marchMonth.compare(VectorOperators.GE, VarkaChrono.MARCH_YEAR_JANUARY));
  }

  /** Lanes this JVM runs at, for the benchmark's header. */
  public static int lanes() {
    return SPECIES.length();
  }
}
