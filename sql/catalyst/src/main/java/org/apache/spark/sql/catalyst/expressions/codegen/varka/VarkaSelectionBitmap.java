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
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * The read side of a selection bitmap: the bit-packed mask a filter kernel writes
 * into its {@code dstValidity} slot, one bit per row, set exactly where the predicate is known
 * true (see {@link VarkaFusedKernel}'s selection-output contract). The layout is Arrow's
 * validity layout - LSB of byte 0 is row 0 - because the kernel writes it with the same
 * {@code orValidityBitsAt} helpers that write output validity, so the two bitmaps cannot
 * drift apart in format.
 *
 * <p>{@link #isSet}, {@link #orInto} and {@link #andInto} hold for any segment of at least
 * {@code (length + 7) / 8} bytes, the size the kernel zeroes. {@link #countSet} reads whole
 * 64-bit words, so it needs {@code ((length + 63) / 64) * 8} bytes, the whole-word stride the
 * filter evaluator lays each bitmap out in. No helper lets a bit at or above {@code length}
 * change its answer: the first three never read one, and {@link #countSet} masks the last word,
 * so a reused buffer's stale tail cannot leak into a count. The two combining helpers write the
 * bytes they read, and a bit past {@code length} they compute is as stale as its operands.
 */
public final class VarkaSelectionBitmap {

  // A 64-bit word of the bitmap. Little-endian whatever the platform's order, so that row r is
  // bit r of the word - LSB of byte 0 is row 0 - which countSet's tail mask relies on.
  private static final ValueLayout.OfLong WORD =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

  private VarkaSelectionBitmap() {
  }

  /** Whether {@code row}'s bit is set. The caller keeps {@code row} within {@code [0, length)}. */
  public static boolean isSet(MemorySegment mask, int row) {
    byte b = mask.get(ValueLayout.JAVA_BYTE, row >>> 3);
    return (b >>> (row & 7) & 1) != 0;
  }

  /**
   * ORs {@code src} into {@code dst} over the first {@code length} rows: the selection of a
   * disjunction split into partial roots, one bitmap per root. Eight bytes at a time over the
   * whole words, then a byte at a time.
   */
  public static void orInto(MemorySegment dst, MemorySegment src, int length) {
    long bytes = (length + 7L) >>> 3;
    long words = bytes >>> 3;
    for (long w = 0; w < words; w++) {
      long off = w << 3;
      dst.set(ValueLayout.JAVA_LONG_UNALIGNED, off,
          dst.get(ValueLayout.JAVA_LONG_UNALIGNED, off)
              | src.get(ValueLayout.JAVA_LONG_UNALIGNED, off));
    }
    for (long b = words << 3; b < bytes; b++) {
      dst.set(ValueLayout.JAVA_BYTE, b,
          (byte) (dst.get(ValueLayout.JAVA_BYTE, b) | src.get(ValueLayout.JAVA_BYTE, b)));
    }
  }

  /** ANDs {@code src} into {@code dst} over the first {@code length} rows, as {@link #orInto}
   * ORs: the selection of a conjunction split into several roots. */
  public static void andInto(MemorySegment dst, MemorySegment src, int length) {
    long bytes = (length + 7L) >>> 3;
    long words = bytes >>> 3;
    for (long w = 0; w < words; w++) {
      long off = w << 3;
      dst.set(ValueLayout.JAVA_LONG_UNALIGNED, off,
          dst.get(ValueLayout.JAVA_LONG_UNALIGNED, off)
              & src.get(ValueLayout.JAVA_LONG_UNALIGNED, off));
    }
    for (long b = words << 3; b < bytes; b++) {
      dst.set(ValueLayout.JAVA_BYTE, b,
          (byte) (dst.get(ValueLayout.JAVA_BYTE, b) & src.get(ValueLayout.JAVA_BYTE, b)));
    }
  }

  /**
   * The number of set bits among the first {@code length} rows - the selected-row count. It counts
   * a 64-bit word at a time, 64 reads for 4096 rows, and masks the last partial word to its low
   * {@code length % 64} bits, so stale bits at and above {@code length} are not counted.
   *
   * @param mask the bitmap, at least {@code ((length + 63) / 64) * 8} bytes: the whole words that
   *        hold the first {@code length} bits, the last word included even where {@code length}
   *        ends inside it
   * @param length the number of rows to count over, at least 0
   */
  public static int countSet(MemorySegment mask, int length) {
    int words = length >>> 6;
    int count = 0;
    for (long w = 0; w < words; w++) {
      count += Long.bitCount(mask.get(WORD, w << 3));
    }
    int rest = length & 63;
    if (rest != 0) {
      // The last word holds rows [words * 64, length) in its low rest bits; the bits above them
      // are rows past the batch, stale from an earlier one, and the mask drops them.
      count += Long.bitCount(mask.get(WORD, (long) words << 3) & ((1L << rest) - 1));
    }
    return count;
  }
}
