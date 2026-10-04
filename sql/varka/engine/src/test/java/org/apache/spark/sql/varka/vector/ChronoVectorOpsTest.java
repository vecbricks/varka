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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DateDayVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.varka.memory.VarkaMorsel;
import org.apache.spark.sql.varka.memory.VarkaMorsel.DateMorsel;

/**
 * Differential tests for {@link ChronoVectorOps}, VARKA-32's ceiling-measurement kernel: every
 * result is asserted against {@code java.time.LocalDate}, the same oracle {@code VarkaChrono}
 * and the emitted {@code year}/{@code month}/{@code dayofmonth}/{@code quarter} nodes are checked
 * against in {@code VARKA-26.md}.
 *
 * <p>This samples the narrowed range - roughly 100,000 values out of its 16,777,216 - rather than
 * sweeping it. "Sweep" is reserved in this project for {@code VarkaChronoSuite}'s exhaustive
 * opt-in check over every day of the range; nothing here is exhaustive.
 *
 * <p>Every field is asserted against a source {@code LocalDate} exposes independently of the
 * others. In particular the quarter oracle is {@code IsoFields.QUARTER_OF_YEAR}, not
 * {@code (month + 2) / 3}: the latter is the exact formula the kernel implements by magic
 * multiply, so a shared error in the month it derives from would agree with itself and pass.
 * {@code VarkaChronoSuite} carries the same warning about the same formula.
 *
 * <p>Every assertion runs against <b>both</b> scheduling variants,
 * {@link ChronoVectorOps#vectorFourFields} and
 * {@link ChronoVectorOps#vectorFourFieldsShortLive}. They differ only in where the year assembly
 * sits and when each output is stored, so any disagreement between them is a transcription bug
 * in one of the two rather than an arithmetic question.
 */
public class ChronoVectorOpsTest {

  /**
   * The range {@link ChronoVectorOps}'s narrowed lowering is defined over, copied from
   * {@code VarkaChrono} since the engine module cannot depend on catalyst: years -12800 to 33134.
   * Outside it the kernel declines the batch rather than returning wrong answers, which
   * {@link #outOfRangeDeclinesTheBatch()} pins.
   */
  private static final long NARROW_MIN_DAYS = -5394572L;
  private static final long NARROW_MAX_DAYS = 11382643L;
  private static final long NARROW_RANGE = NARROW_MAX_DAYS - NARROW_MIN_DAYS + 1; // 2^24

  private static final int[] SIZES =
      {1, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 1000, 100000};

  /** The signature both scheduling variants share; see the class doc. */
  @FunctionalInterface
  private interface FourFieldsKernel {
    int run(long srcData, long srcValidity, int srcNullCount, long[] dstData, long[] dstValidity,
        int length);
  }

  private static final FourFieldsKernel[] KERNELS = {
      ChronoVectorOps::vectorFourFields,
      ChronoVectorOps::vectorFourFieldsShortLive,
  };

  private static final String[] KERNEL_NAMES = {"vectorFourFields", "vectorFourFieldsShortLive"};

  private RootAllocator allocator;
  private final List<DateDayVector> vectors = new ArrayList<>();

  @BeforeEach
  void setUp() {
    allocator = new RootAllocator(Long.MAX_VALUE);
  }

  @AfterEach
  void tearDown() {
    for (DateDayVector v : vectors) {
      v.close();
    }
    allocator.close();
  }

  @Test
  void fourFieldsMatchesJavaTimeReference() {
    IntPredicate[] patterns = {
        i -> true,                       // no nulls
        i -> i % 2 == 0,                 // alternating
        i -> i % 7 == 0,                 // sparse
        i -> i > 3 && i < 40,            // dense middle
        i -> i == 0 || i == 63,          // first and last
    };
    for (int n : SIZES) {
      for (IntPredicate pattern : patterns) {
        fourFieldsForPattern(n, pattern);
      }
    }
  }

  @Test
  void fourFieldsBoundaryValuesMatchJavaTimeReference() {
    long[] days = {
        NARROW_MIN_DAYS, NARROW_MIN_DAYS + 1, NARROW_MAX_DAYS - 1, NARROW_MAX_DAYS,
        -1, 0, 1,                                       // the epoch
        LocalDate.of(1, 1, 1).toEpochDay(),
        LocalDate.of(1, 3, 1).toEpochDay(),
        LocalDate.of(400, 1, 1).toEpochDay(),
        LocalDate.of(401, 1, 1).toEpochDay(),
        LocalDate.of(1900, 2, 28).toEpochDay(),         // not a leap year (div 100, not 400)
        LocalDate.of(1900, 3, 1).toEpochDay(),
        LocalDate.of(2000, 2, 29).toEpochDay(),         // leap year (div 400)
        LocalDate.of(2000, 3, 1).toEpochDay(),
        LocalDate.of(2100, 2, 28).toEpochDay(),         // not a leap year
        LocalDate.of(2400, 2, 29).toEpochDay(),         // leap year (div 400)
        LocalDate.of(2023, 12, 31).toEpochDay(),
        LocalDate.of(2024, 1, 1).toEpochDay(),
        LocalDate.of(9999, 12, 31).toEpochDay(),
        LocalDate.of(33134, 1, 1).toEpochDay(),
        LocalDate.of(-12800, 3, 1).toEpochDay(),
    };
    int n = days.length;
    DateDayVector v = newVector(n);
    for (int i = 0; i < n; i++) {
      v.set(i, (int) days[i]);
    }
    v.setValueCount(n);
    assertFourFields(v, n, 0);
  }

  /**
   * A row past either end of the narrowed range declines the whole batch - the guard the first
   * version of this kernel omitted, which is what made its measured ceiling incomparable with the
   * four independently emitted nodes it was measured against. Both the main loop and the epilogue
   * carry a copy of the guard, so the out-of-range row is placed in each in turn.
   */
  @Test
  void outOfRangeDeclinesTheBatch() {
    long[] outOfRange = {NARROW_MIN_DAYS - 1, NARROW_MAX_DAYS + 1, Integer.MIN_VALUE,
        Integer.MAX_VALUE};
    // 65 rows is at least four full lane groups plus a partial one at every supported width, so
    // a low index lands in the loop and the last index lands in the epilogue.
    int n = 65;
    for (long bad : outOfRange) {
      for (int at : new int[] {0, 3, n - 1}) {
        DateDayVector v = newVector(n);
        for (int i = 0; i < n; i++) {
          v.set(i, (int) (i == at ? bad : value(i)));
        }
        v.setValueCount(n);
        assertEquals(ChronoVectorOps.STATUS_CHRONO_RANGE, runFourFields(v, n, 0),
            "expected a decline for day " + bad + " at row " + at);
      }
    }
  }

  /**
   * A null row's data bytes are undefined, so an out-of-range value under one must not condemn
   * the batch: the guard is ANDed with the row's validity for exactly this reason. A miss here
   * is a silent full-batch fallback - slow, not wrong - which no result assertion would catch.
   */
  @Test
  void outOfRangeUnderANullDoesNotDeclineTheBatch() {
    int n = 65;
    DateDayVector v = newVector(n);
    int nulls = 0;
    for (int i = 0; i < n; i++) {
      if (i % 5 == 0) {
        // Write the poison first, then clear the validity bit: Arrow's setNull leaves the data
        // buffer alone, so the row keeps an out-of-range value underneath a null.
        v.set(i, (int) NARROW_MAX_DAYS + 1000);
        v.setNull(i);
        nulls++;
      } else {
        v.set(i, (int) value(i));
      }
    }
    v.setValueCount(n);
    assertEquals(0, runFourFields(v, n, nulls), "a null row's data condemned the batch");
    assertFourFields(v, n, nulls);
  }

  private void fourFieldsForPattern(int n, IntPredicate validRows) {
    DateDayVector v = newVector(n);
    int nulls = 0;
    for (int i = 0; i < n; i++) {
      if (validRows.test(i)) {
        v.set(i, (int) value(i));
      } else {
        v.setNull(i);
        nulls++;
      }
    }
    v.setValueCount(n);
    assertFourFields(v, n, nulls);
  }

  /** A value spread pseudo-randomly across the whole narrowed range, deterministically. */
  private long value(int i) {
    return NARROW_MIN_DAYS + Math.floorMod((long) i * 500009L, NARROW_RANGE);
  }

  /** Runs every variant and discards the outputs; returns the status all of them agreed on. */
  private int runFourFields(DateDayVector v, int n, int nulls) {
    DateMorsel m = VarkaMorsel.extractDate(v, n);
    try (Arena arena = Arena.ofConfined()) {
      long[] dstData = new long[4];
      long[] dstValidity = new long[4];
      for (int o = 0; o < 4; o++) {
        dstData[o] = arena.allocate(n * 4L).address();
        dstValidity[o] = arena.allocate((n + 7) / 8L).address();
      }
      long srcValidity = (nulls == 0 || nulls == n) ? 0L : m.validity().address();
      int status = KERNELS[0].run(
          m.data().address(), srcValidity, nulls, dstData, dstValidity, n);
      for (int k = 1; k < KERNELS.length; k++) {
        assertEquals(status,
            KERNELS[k].run(m.data().address(), srcValidity, nulls, dstData, dstValidity, n),
            KERNEL_NAMES[k] + " disagreed with " + KERNEL_NAMES[0] + " on the batch status");
      }
      return status;
    }
  }

  private void assertFourFields(DateDayVector v, int n, int nulls) {
    for (int k = 0; k < KERNELS.length; k++) {
      assertFourFields(KERNELS[k], KERNEL_NAMES[k], v, n, nulls);
    }
  }

  private void assertFourFields(FourFieldsKernel kernel, String name, DateDayVector v, int n,
      int nulls) {
    DateMorsel m = VarkaMorsel.extractDate(v, n);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment[] data = new MemorySegment[4];
      MemorySegment[] valid = new MemorySegment[4];
      long[] dstData = new long[4];
      long[] dstValidity = new long[4];
      for (int o = 0; o < 4; o++) {
        data[o] = arena.allocate(n * 4L);
        valid[o] = arena.allocate((n + 7) / 8L);
        dstData[o] = data[o].address();
        dstValidity[o] = valid[o].address();
      }
      long srcValidity = (nulls == 0 || nulls == n) ? 0L : m.validity().address();
      int status = kernel.run(m.data().address(), srcValidity, nulls, dstData, dstValidity, n);
      assertEquals(0, status, name + " declined an in-range batch");
      for (int i = 0; i < n; i++) {
        boolean valid1 = !v.isNull(i);
        for (int o = 0; o < 4; o++) {
          assertEquals(valid1, VarkaVectorSupport.isBitSet(valid[o], i),
              name + ": validity mismatch at row " + i + " for output " + o);
        }
        if (valid1) {
          LocalDate expected = LocalDate.ofEpochDay(v.get(i));
          String where = " at row " + i + " for day " + v.get(i);
          assertEquals(expected.getYear(), at(data[ChronoVectorOps.YEAR], i),
              name + ": year" + where);
          assertEquals(expected.getMonthValue(), at(data[ChronoVectorOps.MONTH], i),
              name + ": month" + where);
          assertEquals(expected.getDayOfMonth(), at(data[ChronoVectorOps.DAY_OF_MONTH], i),
              name + ": day-of-month" + where);
          assertEquals(expected.get(IsoFields.QUARTER_OF_YEAR),
              at(data[ChronoVectorOps.QUARTER], i), name + ": quarter" + where);
        }
      }
      // The four outputs come from one source column, so their validity buffers must be
      // bit-identical; the kernel writes four physical buffers and this is what pins that it
      // writes all four rather than aliasing them.
      for (int o = 1; o < 4; o++) {
        for (int i = 0; i < n; i++) {
          assertTrue(
              VarkaVectorSupport.isBitSet(valid[0], i) == VarkaVectorSupport.isBitSet(valid[o], i),
              name + ": output " + o + " validity diverged from output 0 at row " + i);
        }
      }
    }
  }

  /**
   * {@link ChronoVectorOps#vectorFourFieldsNoValidity} against
   * {@link ChronoVectorOps#vectorFourFields} on the same null-free data: the arithmetic is a
   * byte-for-byte copy of the same lines, so the two must agree exactly, on every value and on
   * the range guard's verdict. This is what makes the pair usable for section 2.17's
   * measurement - a kernel that silently computed something else would size the wrong bound.
   */
  @Test
  void noValidityMatchesFourFieldsOnNullFreeData() {
    for (int n : SIZES) {
      DateDayVector v = newVector(n);
      for (int i = 0; i < n; i++) {
        v.set(i, (int) value(i));
      }
      v.setValueCount(n);
      assertNoValidityMatchesFourFields(v, n);
    }
  }

  /** The out-of-range guard fires identically with validity removed. */
  @Test
  void noValidityOutOfRangeDeclinesTheBatch() {
    int n = 65;
    for (long bad : new long[] {NARROW_MIN_DAYS - 1, NARROW_MAX_DAYS + 1, Integer.MIN_VALUE,
        Integer.MAX_VALUE}) {
      for (int at : new int[] {0, 3, n - 1}) {
        DateDayVector v = newVector(n);
        for (int i = 0; i < n; i++) {
          v.set(i, (int) (i == at ? bad : value(i)));
        }
        v.setValueCount(n);
        DateMorsel m = VarkaMorsel.extractDate(v, n);
        long[] dstData = new long[4];
        try (Arena arena = Arena.ofConfined()) {
          for (int o = 0; o < 4; o++) {
            dstData[o] = arena.allocate(n * 4L).address();
          }
          assertEquals(ChronoVectorOps.STATUS_CHRONO_RANGE,
              ChronoVectorOps.vectorFourFieldsNoValidity(m.data().address(), dstData, n),
              "expected a decline for day " + bad + " at row " + at);
        }
      }
    }
  }

  private void assertNoValidityMatchesFourFields(DateDayVector v, int n) {
    DateMorsel m = VarkaMorsel.extractDate(v, n);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment[] withValiditySeg = new MemorySegment[4];
      MemorySegment[] noValiditySeg = new MemorySegment[4];
      long[] withValidityData = new long[4];
      long[] withValidityValid = new long[4];
      long[] noValidityData = new long[4];
      for (int o = 0; o < 4; o++) {
        withValiditySeg[o] = arena.allocate(n * 4L);
        withValidityData[o] = withValiditySeg[o].address();
        withValidityValid[o] = arena.allocate((n + 7) / 8L).address();
        noValiditySeg[o] = arena.allocate(n * 4L);
        noValidityData[o] = noValiditySeg[o].address();
      }
      int status = ChronoVectorOps.vectorFourFields(
          m.data().address(), 0L, 0, withValidityData, withValidityValid, n);
      int noValidityStatus = ChronoVectorOps.vectorFourFieldsNoValidity(
          m.data().address(), noValidityData, n);
      assertEquals(status, noValidityStatus,
          "vectorFourFieldsNoValidity disagreed with vectorFourFields on the batch status");
      if (status == 0) {
        for (int o = 0; o < 4; o++) {
          for (int i = 0; i < n; i++) {
            assertEquals(at(withValiditySeg[o], i), at(noValiditySeg[o], i),
                "output " + o + " diverged at row " + i + " for day " + v.get(i));
          }
        }
      }
    }
  }

  private static int at(MemorySegment seg, int i) {
    return seg.get(ValueLayout.JAVA_INT, i * 4L);
  }

  private DateDayVector newVector(int rowCount) {
    DateDayVector v = new DateDayVector("date", allocator);
    v.allocateNew(rowCount);
    vectors.add(v);
    return v;
  }
}
