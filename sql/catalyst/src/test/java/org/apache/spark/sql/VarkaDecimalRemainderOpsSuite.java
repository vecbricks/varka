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

package org.apache.spark.sql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalRemainderOps;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;

/**
 * {@link VarkaDecimalRemainderOps}, split F behind its own transpose (VARKA-604), against
 * {@code BigInteger} over the remainder spike's edge rows ({@link
 * VarkaDecimalRemainderSpikeBenchmark#row}): one-word and two-word values, low words with the
 * sign bit set, divisors of 1, {@code 10^k} and {@code 2^k}, the dividend, zero, every sign pair.
 * The lengths cover one block, many, and a short last block, which the driver pads; a wrong word
 * of a slot, a pad that sets a zero-divisor bit or a copy past the batch fails here.
 */
@VarkaSuite
public final class VarkaDecimalRemainderOpsSuite {

  private static final ValueLayout.OfLong WORD =
      ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);

  /** The bytes past a batch's last slot, which the driver must leave as they are. */
  private static final int GUARD = 64;

  private static final byte UNWRITTEN = VarkaDecimalRemainderSpikeBenchmark.UNWRITTEN;

  /** One row, one block, many blocks, and lengths whose last block is short. */
  private static final int[] LENGTHS = {1, 3, 63, 64, 65, 4096, 4096 + 37};

  @Test
  @DisplayName("x % y and pmod agree with BigInteger at every length, short last block included")
  void agreesWithBigInteger() {
    for (boolean pmod : new boolean[] {false, true}) {
      for (int n : LENGTHS) {
        check(n, pmod, false);
      }
    }
  }

  @Test
  @DisplayName("a short block after a full one is padded again, its zero divisors masked")
  void reusedBlockIsPaddedAgain() {
    // The block is the thread's and reused: a full block of zero divisors, then a short one,
    // whose padding rows must read as 0 % 1 and set no bit, whatever the last call left.
    for (boolean pmod : new boolean[] {false, true}) {
      check(4096, pmod, false);
      check(5, pmod, false);
      check(128, pmod, false);
      check(70, pmod, false);
    }
  }

  @Test
  @DisplayName("the output may be the dividend's own slots")
  void outputMayBeTheDividend() {
    check(4096 + 37, false, true);
    check(4096 + 37, true, true);
  }

  @Test
  @DisplayName("too few zero-divisor words are refused, and no rows is nothing to do")
  void refusesTooFewWords() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment s = arena.allocate(65 * 16L, 64);
      long a = s.address();
      assertThrows(IllegalArgumentException.class,
          () -> VarkaDecimalRemainderOps.remainder(a, a, a, new long[1], 65, false));
      VarkaDecimalRemainderOps.remainder(a, a, a, new long[0], 0, false);
    }
  }

  @Test
  @DisplayName("the 64-row loop is too large for C2 to inline into the driver")
  void loopStaysOutOfLine() throws IOException {
    // The loop runs 64 rows a call only while it is a compile of its own (the class doc, "Why
    // 64 rows a call"); C2 inlines no method past FreqInlineSize, 325 bytes on x86, at a hot
    // call site, and no larger default exists on any platform C2 runs on.
    String resource = VarkaDecimalRemainderOps.class.getName().replace('.', '/') + ".class";
    byte[] bytes;
    try (InputStream in = VarkaDecimalRemainderOps.class.getClassLoader()
        .getResourceAsStream(resource)) {
      bytes = in.readAllBytes();
    }
    ClassModel model = ClassFile.of().parse(bytes);
    int length = model.methods().stream()
        .filter(m -> m.methodName().equalsString("loop64"))
        .map(m -> m.findAttribute(Attributes.code()).orElseThrow().codeLength())
        .findFirst().orElseThrow();
    assertTrue(length > 4 * 325, "loop64 is " + length + " bytes of bytecode");
  }

  /**
   * Runs {@code n} rows of the spike's edge rows through the driver and checks every slot, every
   * zero-divisor word and the bytes past the batch.
   */
  private static void check(int n, boolean pmod, boolean inPlace) {
    var r = new Random(604 + n);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment x = arena.allocate(n * 16L + GUARD, 64);
      MemorySegment y = arena.allocate(n * 16L + GUARD, 64);
      MemorySegment out = inPlace ? x : arena.allocate(n * 16L + GUARD, 64);
      if (!inPlace) {
        out.fill(UNWRITTEN);
      } else {
        x.asSlice(n * 16L).fill(UNWRITTEN);
      }
      var expected = new BigInteger[n];
      long[] expectedWords = new long[(n + 63) / 64];
      for (int i = 0; i < n; i++) {
        BigInteger[] pair = VarkaDecimalRemainderSpikeBenchmark.row(i, r);
        setSlot(x, i, pair[0]);
        setSlot(y, i, pair[1]);
        expected[i] = answer(pair[0], pair[1], pmod);
        if (pair[1].signum() == 0) {
          expectedWords[i >>> 6] |= 1L << (i & 63);
        }
      }
      long[] words = new long[expectedWords.length];
      Arrays.fill(words, -1L);
      VarkaDecimalRemainderOps.remainder(x.address(), y.address(), out.address(), words, n,
          pmod);
      String what = (pmod ? "pmod" : "x % y") + " at " + n + " rows";
      for (int i = 0; i < n; i++) {
        assertEquals(expected[i], VarkaDecimalRemainderSpikeBenchmark.slot(out, i),
            what + ", row " + i);
      }
      assertArrayEquals(expectedWords, words, what + ": the zero divisors' words");
      for (long b = n * 16L; b < n * 16L + GUARD; b++) {
        assertEquals(UNWRITTEN, out.get(ValueLayout.JAVA_BYTE, b), what + ": byte " + b
            + " past the batch was written");
      }
    }
  }

  /** Spark's answer: {@code x % y} keeps the dividend's sign, pmod is {@code (r + y) % y}. */
  private static BigInteger answer(BigInteger x, BigInteger y, boolean pmod) {
    if (y.signum() == 0) {
      return BigInteger.ZERO;
    }
    BigInteger rem = x.remainder(y);
    return pmod && rem.signum() < 0 ? rem.add(y).remainder(y) : rem;
  }

  private static void setSlot(MemorySegment s, int row, BigInteger v) {
    s.set(WORD, row * 16L, v.longValue());
    s.set(WORD, row * 16L + 8, v.shiftRight(64).longValue());
  }
}
