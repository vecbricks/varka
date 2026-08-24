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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DateDayVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jdk.incubator.vector.IntVector;

import org.apache.spark.sql.varka.memory.VarkaMorsel;
import org.apache.spark.sql.varka.memory.VarkaMorsel.DateMorsel;

/**
 * Differential tests for {@link DateVectorOps}: every kernel result is asserted against Arrow's
 * own {@code DateDayVector.get(i)} / {@code isNull(i)} accessors as the oracle.
 */
public class DateVectorOpsTest {

  private static final int[] SIZES =
      {1, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 1000, 100000};
  private static final int[] OFFSETS = {0, 3, -5, Integer.MAX_VALUE - 10};

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

  /**
   * Guards the narrow-vector surefire execution, which caps the vector width so that the
   * kernels run at four int lanes - the shape of a 128-bit NEON machine, which no CI runner
   * is. That execution passes {@code varka.expected.int.lanes}; if the width it asks for is
   * not the width it got, the run silently repeats the default one instead of covering the
   * narrow-species path, and this fails rather than letting the coverage disappear. The
   * default execution does not set the property, so this is skipped there.
   */
  @Test
  void preferredSpeciesIsTheWidthTheRunAskedFor() {
    String expected = System.getProperty("varka.expected.int.lanes");
    assumeTrue(expected != null, "only checked by a run that pins the vector width");
    assertEquals(Integer.parseInt(expected), IntVector.SPECIES_PREFERRED.length(),
        "the JVM did not honour the requested vector width, so this run duplicates the "
            + "default one instead of exercising the narrow-species path");
  }

  @Test
  void addDaysMatchesScalarReference() {
    IntPredicate[] patterns = {
        i -> true,                       // no nulls
        i -> i % 2 == 0,                 // alternating
        i -> i % 7 == 0,                 // sparse
        i -> i > 3 && i < 40,            // dense middle
        i -> i == 0 || i == 63,          // first and last
    };
    for (int n : SIZES) {
      for (int offset : OFFSETS) {
        for (IntPredicate pattern : patterns) {
          addDaysForPattern(n, offset, pattern);
        }
      }
    }
  }

  @Test
  void subDaysMatchesScalarReference() {
    for (int n : new int[] {1, 17, 64, 65, 1000}) {
      for (int offset : OFFSETS) {
        subDaysForPattern(n, offset, i -> i % 3 == 0);
      }
    }
  }

  @Test
  void allNullInput() {
    for (int n : new int[] {1, 8, 17, 1000}) {
      DateDayVector v = newVector(n);
      for (int i = 0; i < n; i++) {
        v.setNull(i);
      }
      v.setValueCount(n);
      DateMorsel m = VarkaMorsel.extractDate(v, n);
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment dstData = arena.allocate(n * 4L);
        MemorySegment dstValidity = arena.allocate((n + 7) / 8L);
        // All-null: validity pointer may be 0L and must never be dereferenced.
        DateVectorOps.vectorAddDays(m.data().address(), 0L, n,
            dstData.address(), dstValidity.address(), n, 7);
        for (int i = 0; i < n; i++) {
          assertEquals(false, isBitSet(dstValidity, i), "row " + i + " must be null");
        }
      }
    }
  }

  @Test
  void dateDiffMatchesScalarReference() {
    int[][] combos = {
        {0, 0},   // both no nulls
        {0, 1},   // A no nulls, B alternating
        {2, 1},   // A sparse, B alternating
        {3, 4},   // A dense, B first/last
        {1, 1},   // both alternating
    };
    for (int n : new int[] {1, 7, 17, 64, 65, 1000}) {
      for (int[] combo : combos) {
        dateDiffForPatterns(n, PATTERNS[combo[0]], PATTERNS[combo[1]]);
      }
    }
  }

  @Test
  void dateDiffAllNullInput() {
    for (int n : new int[] {8, 65}) {
      DateDayVector va = newVector(n);
      for (int i = 0; i < n; i++) {
        va.setNull(i);
      }
      va.setValueCount(n);
      DateDayVector vb = newVector(n);
      for (int i = 0; i < n; i++) {
        vb.set(i, i);
      }
      vb.setValueCount(n);
      DateMorsel ma = VarkaMorsel.extractDate(va, n);
      DateMorsel mb = VarkaMorsel.extractDate(vb, n);
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment dstData = arena.allocate(n * 4L);
        MemorySegment dstValidity = arena.allocate((n + 7) / 8L);
        DateVectorOps.vectorDateDiff(ma.data().address(), 0L, n,
            mb.data().address(), mb.validity().address(), 0,
            dstData.address(), dstValidity.address(), n);
        for (int i = 0; i < n; i++) {
          assertEquals(false, isBitSet(dstValidity, i), "row " + i + " must be null");
        }
      }
    }
  }

  private static final IntPredicate[] PATTERNS = {
      i -> true,                 // no nulls
      i -> i % 2 == 0,           // alternating
      i -> i % 7 == 0,           // sparse
      i -> i > 3 && i < 40,      // dense middle
      i -> i == 0 || i == 63,    // first and last
  };

  private void addDaysForPattern(int n, int offset, IntPredicate validRows) {
    DateDayVector v = newVector(n);
    int nulls = fill(v, n, validRows);
    v.setValueCount(n);
    DateMorsel m = VarkaMorsel.extractDate(v, n);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment dstData = arena.allocate(n * 4L);
      MemorySegment dstValidity = arena.allocate((n + 7) / 8L);
      long srcValidity = (nulls == 0 || nulls == n) ? 0L : m.validity().address();
      DateVectorOps.vectorAddDays(m.data().address(), srcValidity, nulls,
          dstData.address(), dstValidity.address(), n, offset);
      assertUnaryResult(dstData, dstValidity, n, v, x -> x + offset);
    }
  }

  private void subDaysForPattern(int n, int offset, IntPredicate validRows) {
    DateDayVector v = newVector(n);
    int nulls = fill(v, n, validRows);
    v.setValueCount(n);
    DateMorsel m = VarkaMorsel.extractDate(v, n);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment dstData = arena.allocate(n * 4L);
      MemorySegment dstValidity = arena.allocate((n + 7) / 8L);
      long srcValidity = (nulls == 0 || nulls == n) ? 0L : m.validity().address();
      DateVectorOps.vectorSubDays(m.data().address(), srcValidity, nulls,
          dstData.address(), dstValidity.address(), n, offset);
      assertUnaryResult(dstData, dstValidity, n, v, x -> x - offset);
    }
  }

  private void dateDiffForPatterns(int n, IntPredicate validA, IntPredicate validB) {
    DateDayVector va = newVector(n);
    int nullsA = fill(va, n, validA);
    va.setValueCount(n);
    DateDayVector vb = newVector(n);
    int nullsB = fill(vb, n, validB);
    vb.setValueCount(n);
    DateMorsel ma = VarkaMorsel.extractDate(va, n);
    DateMorsel mb = VarkaMorsel.extractDate(vb, n);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment dstData = arena.allocate(n * 4L);
      MemorySegment dstValidity = arena.allocate((n + 7) / 8L);
      long validityA = (nullsA == 0 || nullsA == n) ? 0L : ma.validity().address();
      long validityB = (nullsB == 0 || nullsB == n) ? 0L : mb.validity().address();
      DateVectorOps.vectorDateDiff(ma.data().address(), validityA, nullsA,
          mb.data().address(), validityB, nullsB,
          dstData.address(), dstValidity.address(), n);
      assertDiffResult(dstData, dstValidity, n, va, vb);
    }
  }

  /**
   * Fills {@code v} of length {@code n} with value/pattern; returns the number of null rows.
   */
  private int fill(DateDayVector v, int n, IntPredicate validRows) {
    int nulls = 0;
    for (int i = 0; i < n; i++) {
      if (validRows.test(i)) {
        v.set(i, value(i));
      } else {
        v.setNull(i);
        nulls++;
      }
    }
    return nulls;
  }

  private int value(int i) {
    return i * 1234567 - 1000000000;
  }

  /**
   * Asserts {@code dst} equals the vector oracle under a scalar op applied to valid rows.
   */
  private void assertUnaryResult(MemorySegment dstData, MemorySegment dstValidity, int n,
      DateDayVector v, IntUnaryOperator op) {
    for (int i = 0; i < n; i++) {
      boolean valid = !v.isNull(i);
      assertEquals(valid, isBitSet(dstValidity, i), "validity mismatch at row " + i);
      if (valid) {
        assertEquals(op.applyAsInt(v.get(i)), dstData.get(ValueLayout.JAVA_INT, i * 4L),
            "data mismatch at row " + i);
      }
    }
  }

  /**
   * Asserts {@code dst} equals the vector oracle for DATEDIFF (null if either input is null).
   */
  private void assertDiffResult(MemorySegment dstData, MemorySegment dstValidity, int n,
      DateDayVector va, DateDayVector vb) {
    for (int i = 0; i < n; i++) {
      boolean valid = !va.isNull(i) && !vb.isNull(i);
      assertEquals(valid, isBitSet(dstValidity, i), "validity mismatch at row " + i);
      if (valid) {
        assertEquals(va.get(i) - vb.get(i), dstData.get(ValueLayout.JAVA_INT, i * 4L),
            "data mismatch at row " + i);
      }
    }
  }

  private static boolean isBitSet(MemorySegment validity, int i) {
    return (validity.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
  }

  private DateDayVector newVector(int rowCount) {
    DateDayVector v = new DateDayVector("date", allocator);
    v.allocateNew(rowCount);
    vectors.add(v);
    return v;
  }
}
