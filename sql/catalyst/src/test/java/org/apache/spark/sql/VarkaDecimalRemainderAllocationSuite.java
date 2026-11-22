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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.Random;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAllocationSampler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalRemainderOps;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;

/**
 * The shared-JVM allocation check of VARKA-604 (its test 5.3, at the helper's level): split F at
 * two long lanes, after other vector code in the same JVM, allocates within the allowance the
 * kernel warm-up's sampler grants ({@link VarkaAllocationSampler#suspect}). Beside vector code
 * that profiles the Vector API's shared templates over several species, the loop can keep one
 * {@code long[2]} a lane group, 16 bytes a row; the engine's template warm-up, which the helper's
 * first call runs, removes it while the loop runs 64 rows a call (VARKA-604.md 2 and 9.2, where
 * the control without the warm-up kept it in every JVM). The order is production's: the warm-up
 * before the other vector code, then the remainder.
 *
 * <p>Its own JVM at 128 bits under AVX2, the runners' setting the helper is for, with the
 * template warm-up pinned on; the parent's flags come first, these last.
 */
@VarkaSuite(ownJvm = true, jvmFlags = {"-XX:+IgnoreUnrecognizedVMOptions", "-XX:UseAVX=2",
    "-XX:MaxVectorSize=16", "-Dvarka.warmup.vectorTemplates=true"})
public final class VarkaDecimalRemainderAllocationSuite {

  private static final ValueLayout.OfLong WORD =
      ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);

  private static final int ROWS = 4096;

  /** The polluter's rounds, as the probes ran it. */
  private static final int POLLUTER_ROUNDS = 20_000;

  /** Calls of one block that take the loop to C2 at its invocation count, as the probes do. */
  private static final int PRIMING_CALLS = 20_000;

  private static final long DRIVE_NANOS = 8_000_000_000L;

  private static final int SAMPLES = 64;

  @Test
  @DisplayName("split F after other vector code allocates within the warm-up's allowance")
  void staysWithinTheAllowance() {
    assumeTrue(VarkaAllocationSampler.supported(), "no per-thread allocation counter");
    assumeTrue(LongVector.SPECIES_PREFERRED.length() == 2,
        "the preferred species is not two long lanes; the JVM flags did not take");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment x = arena.allocate(ROWS * 16L, 64);
      MemorySegment y = arena.allocate(ROWS * 16L, 64);
      MemorySegment out = arena.allocate(ROWS * 16L, 64);
      var r = new Random(604);
      for (int i = 0; i < ROWS; i++) {
        BigInteger[] pair = VarkaDecimalRemainderSpikeBenchmark.row(i, r);
        setSlot(x, i, pair[0]);
        setSlot(y, i, pair[1]);
      }
      long[] words = new long[ROWS / 64];
      long xa = x.address();
      long ya = y.address();
      long oa = out.address();
      // The first call runs the template warm-up, as the first vector code of a Varka JVM does.
      VarkaDecimalRemainderOps.remainder(xa, ya, oa, words, 64, false);
      pollute(arena);
      // x % y, pmod, then x % y again: the compile that serves the third pass has both arms of
      // loop64's pmod branch in its profile, the state a Varka JVM reaches.
      for (boolean pmod : new boolean[] {false, true, false}) {
        for (int c = 0; c < PRIMING_CALLS; c++) {
          VarkaDecimalRemainderOps.remainder(xa, ya, oa, words, 64, pmod);
        }
        long end = System.nanoTime() + DRIVE_NANOS;
        while (System.nanoTime() < end) {
          VarkaDecimalRemainderOps.remainder(xa, ya, oa, words, ROWS, pmod);
        }
        int suspects = 0;
        long worst = 0;
        for (int s = 0; s < SAMPLES; s++) {
          long before = VarkaAllocationSampler.allocatedBytes();
          VarkaDecimalRemainderOps.remainder(xa, ya, oa, words, ROWS, pmod);
          long bytes = VarkaAllocationSampler.allocatedBytes() - before;
          worst = Math.max(worst, bytes);
          suspects += VarkaAllocationSampler.suspect(bytes, ROWS) ? 1 : 0;
        }
        assertEquals(0, suspects, (pmod ? "pmod" : "x % y") + ": " + suspects + " of "
            + SAMPLES + " calls of " + ROWS + " rows over the allowance, the worst " + worst
            + " bytes");
      }
      // The answers are still the remainder's; a call that fell back would show it here.
      VarkaDecimalRemainderOps.remainder(xa, ya, oa, words, ROWS, false);
      var check = new Random(604);
      for (int i = 0; i < ROWS; i++) {
        BigInteger[] pair = VarkaDecimalRemainderSpikeBenchmark.row(i, check);
        BigInteger want = pair[1].signum() == 0 ? BigInteger.ZERO : pair[0].remainder(pair[1]);
        assertEquals(want, VarkaDecimalRemainderSpikeBenchmark.slot(out, i), "row " + i);
        assertFalse(pair[1].signum() == 0 && (words[i >>> 6] & (1L << (i & 63))) == 0,
            "row " + i + "'s zero divisor");
      }
    }
  }

  /**
   * Vector code that profiles the shared templates over seven species: a lanewise add, then a
   * max, through the generic {@link Vector} interface (VARKA-604.md 2, item 1's polluter).
   */
  private static void pollute(Arena arena) {
    MemorySegment a = arena.allocate(ROWS * 8L, 64);
    MemorySegment b = arena.allocate(ROWS * 8L, 64);
    MemorySegment c = arena.allocate(ROWS * 8L, 64);
    VectorSpecies<?>[] species = {IntVector.SPECIES_64, IntVector.SPECIES_128,
        IntVector.SPECIES_256, LongVector.SPECIES_128, LongVector.SPECIES_256,
        DoubleVector.SPECIES_128, DoubleVector.SPECIES_256};
    for (int round = 0; round < POLLUTER_ROUNDS; round++) {
      for (VectorSpecies<?> s : species) {
        mixed(s, a, b, c);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void mixed(VectorSpecies<?> s, MemorySegment a, MemorySegment b,
      MemorySegment c) {
    int step = s.length();
    long bytes = s.elementSize() / 8;
    for (int i = 0; i + step <= ROWS / 2; i += step) {
      var u = (Vector<Object>) s.fromMemorySegment(a, i * bytes, ByteOrder.LITTLE_ENDIAN);
      var v = (Vector<Object>) s.fromMemorySegment(b, i * bytes, ByteOrder.LITTLE_ENDIAN);
      u.lanewise(VectorOperators.ADD, v).lanewise(VectorOperators.MAX, v)
          .intoMemorySegment(c, i * bytes, ByteOrder.LITTLE_ENDIAN);
    }
  }

  private static void setSlot(MemorySegment s, int row, BigInteger v) {
    s.set(WORD, row * 16L, v.longValue());
    s.set(WORD, row * 16L + 8, v.shiftRight(64).longValue());
  }
}
