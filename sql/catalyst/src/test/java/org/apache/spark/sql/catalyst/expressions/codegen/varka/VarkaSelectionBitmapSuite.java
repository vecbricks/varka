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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VarkaSelectionBitmap#countSet}, which counts a 64-bit word at a time, against the byte
 * loop it replaced (VARKA-27), kept here as the oracle. Each bitmap is exactly the whole words
 * that hold its rows, the slice the filter evaluator passes, so a read past the last word fails
 * the test. The bytes the oracle never reads - past {@code (length + 7) / 8}, and the high bits of
 * the last byte - are filled too, so a count that let a stale bit past {@code length} in would
 * differ from the oracle's.
 */
@VarkaSuite
public final class VarkaSelectionBitmapSuite {

  /** The lengths every case runs at: 0 to 200, which crosses three word boundaries, and 4096. */
  private static int[] lengths() {
    return IntStream.concat(IntStream.rangeClosed(0, 200), IntStream.of(4096)).toArray();
  }

  /** The byte loop countSet was before VARKA-27: whole bytes, then the last partial byte. */
  private static int byteLoopCount(MemorySegment mask, int length) {
    int count = 0;
    int fullBytes = length >>> 3;
    for (long i = 0; i < fullBytes; i++) {
      count += Integer.bitCount(mask.get(ValueLayout.JAVA_BYTE, i) & 0xFF);
    }
    int rest = length & 7;
    if (rest != 0) {
      int lastByte = mask.get(ValueLayout.JAVA_BYTE, fullBytes) & 0xFF;
      count += Integer.bitCount(lastByte & ((1 << rest) - 1));
    }
    return count;
  }

  /** A bitmap of exactly the whole words that hold {@code length} rows. */
  private static byte[] wholeWords(int length) {
    return new byte[((length + 63) / 64) * 8];
  }

  /** Asserts countSet agrees with the oracle on {@code bytes} over {@code length} rows. */
  private static void check(byte[] bytes, int length, String what) {
    MemorySegment mask = MemorySegment.ofArray(bytes);
    assertEquals(byteLoopCount(mask, length), VarkaSelectionBitmap.countSet(mask, length),
        what + " at length " + length);
  }

  @Test
  @DisplayName("countSet counts as the byte loop did over random bits")
  void randomBits() {
    Random random = new Random(27);
    for (int length : lengths()) {
      for (int trial = 0; trial < 8; trial++) {
        byte[] bytes = wholeWords(length);
        random.nextBytes(bytes);
        check(bytes, length, "random bits, trial " + trial);
      }
    }
  }

  @Test
  @DisplayName("countSet ignores stale bits past the length")
  void staleBitsPastTheEnd() {
    Random random = new Random(2700);
    for (int length : lengths()) {
      // Every bit set: the count must be the length itself, whatever the stale tail holds.
      byte[] ones = wholeWords(length);
      Arrays.fill(ones, (byte) -1);
      check(ones, length, "every bit set");
      assertEquals(length, VarkaSelectionBitmap.countSet(MemorySegment.ofArray(ones), length));
      // No bit set below the length and every bit set at and above it: the count must be 0.
      byte[] tail = wholeWords(length);
      Arrays.fill(tail, (byte) -1);
      for (int row = 0; row < length; row++) {
        tail[row >>> 3] &= (byte) ~(1 << (row & 7));
      }
      check(tail, length, "only stale bits set");
      assertEquals(0, VarkaSelectionBitmap.countSet(MemorySegment.ofArray(tail), length));
      // Random bits below the length, a stale tail of ones above it.
      byte[] mixed = wholeWords(length);
      random.nextBytes(mixed);
      for (int bit = length; bit < mixed.length * 8; bit++) {
        mixed[bit >>> 3] |= (byte) (1 << (bit & 7));
      }
      check(mixed, length, "random bits under a stale tail");
    }
  }
}
