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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.Test;

/**
 * {@link VarkaVectorSupport#setValid} (VARKA-45), which is only useful if it is bit-exact.
 *
 * <p>The emitted dense path used to zero the bitmap and then OR lane-masked words into it, which
 * leaves the bits past {@code rows} in the final byte at zero. This sets them directly, and
 * {@code VarkaEmitterTestBase.assertSameOutput} compares dense against masked validity byte for
 * byte - so filling the whole last byte would be a difference the differential reports as a
 * failure. Everything here is about that last byte.
 */
public class VarkaVectorSupportSetValidTest {

  /** The buffer is pre-filled with 0xFF and one byte longer than the bitmap, so both directions
   *  of error are visible: bits that should have been cleared, and a write past the end. */
  @Test
  public void setsExactlyTheLowBitsAndNothingPastThem() {
    try (Arena arena = Arena.ofConfined()) {
      for (int rows = 0; rows <= 17; rows++) {
        int bitmapBytes = (rows + 7) / 8;
        MemorySegment buf = arena.allocate(bitmapBytes + 1L, 8);
        buf.fill((byte) 0xFF);
        VarkaVectorSupport.setValid(buf.asSlice(0L, bitmapBytes), rows);
        for (int bit = 0; bit < bitmapBytes * 8; bit++) {
          int b = buf.get(ValueLayout.JAVA_BYTE, bit / 8L) & 0xFF;
          boolean set = (b & (1 << (bit % 8))) != 0;
          assertEquals(bit < rows, set,
              "bit " + bit + " after setValid(" + rows + ") should be " + (bit < rows));
        }
        assertEquals((byte) 0xFF, buf.get(ValueLayout.JAVA_BYTE, bitmapBytes),
            "setValid(" + rows + ") wrote past the bitmap");
      }
    }
  }

  /** What the emitted loop produces today, for the same lengths: zero, then OR of all-ones
   *  words truncated to the batch. setValid has to be indistinguishable from it. */
  @Test
  public void matchesZeroThenOrForEveryLength() {
    try (Arena arena = Arena.ofConfined()) {
      for (int rows : new int[] {0, 1, 7, 8, 9, 15, 16, 17, 63, 64, 65, 1000, 4095, 4096}) {
        int bitmapBytes = (rows + 7) / 8;
        MemorySegment filled = arena.allocate(Math.max(bitmapBytes, 1), 8);
        MemorySegment ored = arena.allocate(Math.max(bitmapBytes, 1), 8);
        VarkaVectorSupport.zero(filled);
        VarkaVectorSupport.zero(ored);
        VarkaVectorSupport.setValid(filled, rows);
        // The loop's own form: every row's bit OR-ed in, which is what orValidityBitsAt does
        // once its lane mask has truncated the group to the rows that exist.
        for (int i = 0; i < rows; i++) {
          VarkaVectorSupport.setBit(ored, i);
        }
        for (int b = 0; b < bitmapBytes; b++) {
          assertEquals(ored.get(ValueLayout.JAVA_BYTE, b), filled.get(ValueLayout.JAVA_BYTE, b),
              "byte " + b + " differs at rows=" + rows);
        }
      }
    }
  }
}
