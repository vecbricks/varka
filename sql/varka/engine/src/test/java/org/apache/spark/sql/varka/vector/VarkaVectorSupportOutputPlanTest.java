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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * {@link VarkaVectorSupport#prepareOutputValidity} and
 * {@link VarkaVectorSupport#everyOutputReadsAnAllNullColumn}, the table form of the driver's
 * per-output work (see {@code PLAN_TASK_190.md} 10). Each plan step must write exactly what the
 * entry point the unrolled driver calls for that output writes - so every test here runs a plan
 * through the helper and the same steps through those entry points, one output at a time, and
 * compares the destination bitmaps byte for byte, over lengths either side of a byte and a word
 * and over columns that are null-free, all-null and mixed.
 */
public class VarkaVectorSupportOutputPlanTest {

  private static final int[] ROWS = {1, 7, 8, 9, 63, 64, 65, 1000};

  /** Three columns: null-free, all-null, and mixed (every third row null). */
  private static final int COLUMNS = 3;

  /** A destination bitmap with room past the last whole word, pre-filled with a guard. */
  private static MemorySegment destination(Arena arena, int rows) {
    MemorySegment seg = arena.allocate(((rows + 63L) / 64) * 8 + 8);
    seg.fill((byte) 0xC3);
    return seg;
  }

  private record Columns(long[] addresses, int[] nullCounts) {}

  private static Columns columns(Arena arena, int rows) {
    long[] addresses = new long[COLUMNS];
    int[] nulls = new int[COLUMNS];
    nulls[1] = rows;
    MemorySegment mixed = arena.allocate((rows + 7) / 8);
    for (int i = 0; i < rows; i++) {
      if (i % 3 != 0) {
        VarkaVectorSupport.setBit(mixed, i);
      } else {
        nulls[2]++;
      }
    }
    addresses[2] = nulls[2] == 0 ? 0L : mixed.address();
    return new Columns(addresses, nulls);
  }

  private static byte[] bytes(MemorySegment seg) {
    return seg.toArray(ValueLayout.JAVA_BYTE);
  }

  @Test
  public void everyStepWritesWhatTheUnrolledDriverWrites() {
    // One output per step kind, the pass steps over each column state and a three-column chain.
    String plan = "" + VarkaVectorSupport.PLAN_ZERO + VarkaVectorSupport.PLAN_ZERO_WORDS
        + VarkaVectorSupport.PLAN_FILL
        + VarkaVectorSupport.PLAN_COPY + (char) 0 + VarkaVectorSupport.PLAN_COPY + (char) 1
        + VarkaVectorSupport.PLAN_COPY + (char) 2
        + VarkaVectorSupport.PLAN_AND + (char) 2 + (char) 0 + (char) 2
        + VarkaVectorSupport.PLAN_OR + (char) 2 + (char) 1 + (char) 2
        + VarkaVectorSupport.PLAN_AND + (char) 3 + (char) 2 + (char) 0 + (char) 1
        + VarkaVectorSupport.PLAN_OR + (char) 3 + (char) 1 + (char) 2 + (char) 0;
    int outputs = 10;
    for (int rows : ROWS) {
      try (Arena arena = Arena.ofConfined()) {
        Columns c = columns(arena, rows);
        MemorySegment[] viaPlan = new MemorySegment[outputs];
        MemorySegment[] direct = new MemorySegment[outputs];
        long[] dst = new long[outputs];
        for (int o = 0; o < outputs; o++) {
          viaPlan[o] = destination(arena, rows);
          direct[o] = destination(arena, rows);
          dst[o] = viaPlan[o].address();
        }
        VarkaVectorSupport.prepareOutputValidity(dst, c.addresses(), c.nullCounts(), plan, rows);

        long nominal = (rows + 7) / 8;
        long words = ((rows + 63L) / 64) * 8;
        VarkaVectorSupport.zero(direct[0].asSlice(0, nominal));
        VarkaVectorSupport.zero(direct[1].asSlice(0, words));
        VarkaVectorSupport.setValid(direct[2].asSlice(0, nominal), rows);
        for (int k = 0; k < COLUMNS; k++) {
          VarkaVectorSupport.copyColumnValidity(direct[3 + k].asSlice(0, nominal),
              c.addresses()[k], c.nullCounts()[k], rows);
        }
        VarkaVectorSupport.andColumnValidity(direct[6].asSlice(0, nominal), c.addresses()[0],
            c.nullCounts()[0], c.addresses()[2], c.nullCounts()[2], rows);
        VarkaVectorSupport.orColumnValidity(direct[7].asSlice(0, nominal), c.addresses()[1],
            c.nullCounts()[1], c.addresses()[2], c.nullCounts()[2], rows);
        MemorySegment and3 = direct[8].asSlice(0, nominal);
        VarkaVectorSupport.andColumnValidity(and3, c.addresses()[2], c.nullCounts()[2],
            c.addresses()[0], c.nullCounts()[0], rows);
        VarkaVectorSupport.andColumnValidityInto(and3, c.addresses()[1], c.nullCounts()[1], rows);
        MemorySegment or3 = direct[9].asSlice(0, nominal);
        VarkaVectorSupport.orColumnValidity(or3, c.addresses()[1], c.nullCounts()[1],
            c.addresses()[2], c.nullCounts()[2], rows);
        VarkaVectorSupport.orColumnValidityInto(or3, c.addresses()[0], c.nullCounts()[0], rows);

        for (int o = 0; o < outputs; o++) {
          assertArrayEquals(bytes(direct[o]), bytes(viaPlan[o]),
              "output " + o + " at " + rows + " rows");
        }
      }
    }
  }

  @Test
  public void planOutOfStepWithTheOutputsIsRefused() {
    // A plan and an output array out of step would write one output's bitmap by another's rule,
    // so both a short plan's unknown step and a long plan's leftover chars are errors.
    try (Arena arena = Arena.ofConfined()) {
      long[] dst = {destination(arena, 8).address()};
      assertThrows(IllegalArgumentException.class, () -> VarkaVectorSupport
          .prepareOutputValidity(dst, new long[0], new int[0], "q", 8));
      assertThrows(IllegalArgumentException.class, () -> VarkaVectorSupport
          .prepareOutputValidity(dst, new long[0], new int[0], "zz", 8));
    }
  }

  @Test
  public void theShortcutAsksWhetherEveryOutputReadsAnAllNullColumn() {
    // Per output the count of its columns and their ordinals; true only when each output has at
    // least one column whose null count is the batch's length - the unrolled AND of ORs.
    int rows = 17;
    int[] nulls = {0, rows, 4};
    Random rnd = new Random(190);
    for (int trial = 0; trial < 200; trial++) {
      int outputs = 1 + rnd.nextInt(5);
      StringBuilder table = new StringBuilder();
      boolean every = true;
      for (int o = 0; o < outputs; o++) {
        int n = 1 + rnd.nextInt(3);
        table.append((char) n);
        boolean any = false;
        for (int k = 0; k < n; k++) {
          int c = rnd.nextInt(COLUMNS);
          table.append((char) c);
          any |= nulls[c] == rows;
        }
        every &= any;
      }
      assertEquals(every,
          VarkaVectorSupport.everyOutputReadsAnAllNullColumn(nulls, table.toString(), rows),
          "table " + table.chars().boxed().toList());
    }
  }
}
