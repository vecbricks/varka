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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.Test;

/**
 * Tests the bit-packed validity arithmetic behind the SIMD loops of {@link DateVectorOps}.
 *
 * <p>Each kernel builds its {@code VectorMask} from the lowest {@code SPECIES.length()} bits of
 * the bitmap at row {@code i}, so that group has to be shifted to bit 0 first, and the resulting
 * mask has to be shifted back when the output validity is written. That only degenerates into a
 * plain byte-indexed read when a lane group happens to start on a byte boundary:
 * {@code IntVector.SPECIES_PREFERRED} is 8 lanes on AVX2 and 16 on AVX-512, but 4 on aarch64
 * NEON and on x86 without AVX2, and a 4-lane group does not.
 *
 * <p>The helpers must also stay inside a nominally sized {@code (length + 7) / 8}-byte bitmap for
 * every group the vector loop can reach, which is what lets that loop run to
 * {@code SPECIES.loopBound(length)} instead of stopping at the last whole 64-bit word. Every
 * bitmap here is allocated at exactly that size, so an overrun fails the test rather than
 * silently reading a neighbouring allocation.
 *
 * <p>{@link DateVectorOpsTest} covers the kernels end to end, but only ever at the host's own
 * species, so it is blind to the widths that host cannot produce. This suite closes that gap
 * without depending on the hardware: every lane width from 1 to 64 is walked over the same
 * bitmaps the kernels would see.
 */
public class DateVectorOpsBitmapTest {

  /** Every {@code IntVector} lane count the Vector API can produce, from 128-bit to 2048-bit. */
  private static final int[] LANE_WIDTHS = {1, 2, 4, 8, 16, 32, 64};
  private static final int[] LENGTHS = {64, 65, 128, 200, 1000};

  @Test
  void validityBitsAtShiftsEachLaneGroupToBitZero() {
    try (Arena arena = Arena.ofConfined()) {
      for (int length : LENGTHS) {
        MemorySegment bitmap = bitmapOf(arena, length);
        for (int width : LANE_WIDTHS) {
          for (long row = 0; row + width <= length; row += width) {
            long actual = DateVectorOps.validityBitsAt(bitmap, row, width) & laneMask(width);
            long expected = 0L;
            for (int lane = 0; lane < width; lane++) {
              if (isBitSet(bitmap, (int) row + lane)) {
                expected |= 1L << lane;
              }
            }
            assertEquals(expected, actual,
                "lane width " + width + ", length " + length + ", row " + row);
          }
        }
      }
    }
  }

  /**
   * Reads every lane group and writes it straight back, which is what a kernel does when it ORs
   * its mask into the destination validity. A group written at the wrong bit offset both loses
   * its own rows and corrupts an earlier group's, so comparing the whole bitmap catches either.
   */
  @Test
  void orValidityBitsAtRoundTripsEveryLaneGroup() {
    try (Arena arena = Arena.ofConfined()) {
      for (int length : LENGTHS) {
        MemorySegment source = bitmapOf(arena, length);
        for (int width : LANE_WIDTHS) {
          MemorySegment target = arena.allocate(bitmapBytes(length));
          long covered = (length / width) * (long) width;
          for (long row = 0; row + width <= length; row += width) {
            long laneBits = DateVectorOps.validityBitsAt(source, row, width);
            DateVectorOps.orValidityBitsAt(target, row, laneBits, width);
          }
          for (int bit = 0; bit < covered; bit++) {
            assertEquals(isBitSet(source, bit), isBitSet(target, bit),
                "lane width " + width + ", length " + length + ", bit " + bit);
          }
        }
      }
    }
  }

  /**
   * Every lane group the vector loop can reach must be readable and writable inside a bitmap
   * sized to the batch and nothing more - including the batches under 57 rows whose bitmap is
   * shorter than a single 64-bit word, which is precisely the range the old word-addressed
   * helpers could not serve. A `MemorySegment` sized to {@code bitmapBytes(length)} throws on
   * any overrun, so reaching the end of the loop is the assertion.
   */
  @Test
  void everyLaneGroupStaysInsideABitmapSizedToTheBatch() {
    try (Arena arena = Arena.ofConfined()) {
      for (int length = 1; length <= 200; length++) {
        MemorySegment source = bitmapOf(arena, length);
        for (int width : LANE_WIDTHS) {
          MemorySegment target = arena.allocate(bitmapBytes(length));
          for (long row = 0; row + width <= length; row += width) {
            DateVectorOps.orValidityBitsAt(
                target, row, DateVectorOps.validityBitsAt(source, row, width), width);
          }
          long covered = (length / width) * (long) width;
          for (int bit = 0; bit < covered; bit++) {
            assertEquals(isBitSet(source, bit), isBitSet(target, bit),
                "lane width " + width + ", length " + length + ", bit " + bit);
          }
          // Nothing beyond the groups that ran may have been set.
          for (long bit = covered; bit < (long) bitmapBytes(length) * 8; bit++) {
            assertFalse(isBitSet(target, (int) bit),
                "lane width " + width + ", length " + length + " set bit " + bit
                    + " beyond the " + covered + " rows its groups cover");
          }
        }
      }
    }
  }

  private static int bitmapBytes(int length) {
    return (length + 7) / 8;
  }

  /**
   * A deterministic, irregular validity pattern; bit set means valid. Irregular so that a bitmap
   * read or written at the wrong bit offset cannot coincidentally match.
   */
  private static boolean validAt(int bit) {
    return (((bit * 0x9E3779B9) >>> 16) & 1) == 1;
  }

  private static MemorySegment bitmapOf(Arena arena, int length) {
    MemorySegment bitmap = arena.allocate(bitmapBytes(length));
    for (int bit = 0; bit < bitmapBytes(length) * 8; bit++) {
      if (validAt(bit)) {
        setBit(bitmap, bit);
      }
    }
    return bitmap;
  }

  private static void setBit(MemorySegment bitmap, int bit) {
    long off = bit / 8;
    bitmap.set(ValueLayout.JAVA_BYTE, off,
        (byte) (bitmap.get(ValueLayout.JAVA_BYTE, off) | (1 << (bit % 8))));
  }

  private static boolean isBitSet(MemorySegment bitmap, int bit) {
    return (bitmap.get(ValueLayout.JAVA_BYTE, bit / 8L) & (1 << (bit % 8))) != 0;
  }

  private static long laneMask(int width) {
    return width == 64 ? -1L : (1L << width) - 1;
  }
}
