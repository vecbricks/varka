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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CanonicalFloat;
import org.apache.spark.sql.catalyst.optimizer.NormalizeFloatingNumbers$;

/**
 * {@code CanonicalFloat} (VARKA-337 2.1): the kernel's answer held bit for bit to Spark's own
 * normalisers, {@code NormalizeFloatingNumbers.FLOAT_NORMALIZER} and {@code DOUBLE_NORMALIZER},
 * which {@code NormalizeNaNAndZero} evaluates. Over the regions the plan names (3.2): both zeros,
 * NaNs of both signs with payloads, signalling ones and the four NaN range endpoints, the
 * infinities, the subnormal edges and the extremes, then a seeded draw of raw bits, every cell on
 * a valid lane and the nulls among the rest, where the output is null. Every {@code FLOAT}
 * pattern, through the kernel once and again over its own output, behind
 * {@code -Dvarka.sweep=true} (3.1), a run by hand in the nightly and not on every build. The
 * planted fault of 3.9 that drops the add of zero is failed by the cells.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterCanonicalFloatSuite {

  private static final VarkaVectorIR FLOAT_CANON =
      CanonicalFloat.of(new VarkaVectorIR.ColumnRef(0, VarkaVectorIR.LaneType.FLOAT));

  private static final VarkaVectorIR DOUBLE_CANON =
      CanonicalFloat.of(new VarkaVectorIR.ColumnRef(0, VarkaVectorIR.LaneType.DOUBLE));

  /** The {@code FLOAT} cells, as raw bits: the regions of 3.2 at the 32-bit width. */
  private static final int[] FLOAT_CELLS = {
      0x00000000, 0x80000000, 0x7fc00000, 0xffc00000, 0x7f800001, 0xff800001, 0x7fffffff,
      0xffffffff, 0x7fa00000, 0xffa00001, 0x7fc00001, 0x7f800000, 0xff800000, 0x00000001,
      0x80000001, 0x007fffff, 0x807fffff, 0x00800000, 0x80800000, 0x7f7fffff, 0xff7fffff,
      0x3f800000, 0xbf800000};

  /**
   * The {@code DOUBLE} cells: the four NaN range endpoints, the canonical NaN and payload and
   * signalling NaNs of both signs, both zeros, both infinities, the subnormal edges and the
   * extremes.
   */
  private static final long[] DOUBLE_CELLS = {
      0x7ff0000000000001L, 0x7fffffffffffffffL, 0xfff0000000000001L, 0xffffffffffffffffL,
      0x7ff8000000000000L, 0xfff8000000000000L, 0x7ff8000000000001L, 0xfff8000000000001L,
      0x7ff4000000000000L, 0xfffc000000000000L, 0x0000000000000000L, 0x8000000000000000L,
      0x7ff0000000000000L, 0xfff0000000000000L, 0x0000000000000001L, 0x8000000000000001L,
      0x000fffffffffffffL, 0x800fffffffffffffL, 0x0010000000000000L, 0x8010000000000000L,
      0x7fefffffffffffffL, 0xffefffffffffffffL, 0x3ff0000000000000L, 0xbff0000000000000L};

  /** Rows after the cells: a seeded draw of raw bits, NaN payloads among them by chance. */
  private static final int DRAWN = 20000;

  /** Whether row {@code i} is null: none of the {@code cells}, every seventh after. */
  private static boolean nullAt(int i, int cells) {
    return i >= cells && i % 7 == 3;
  }

  /** Spark's {@code FLOAT_NORMALIZER} over raw bits. */
  static int sparkFloat(int bits) {
    Object v = NormalizeFloatingNumbers$.MODULE$.FLOAT_NORMALIZER()
        .apply(Float.intBitsToFloat(bits));
    return Float.floatToRawIntBits((Float) v);
  }

  /** Spark's {@code DOUBLE_NORMALIZER} over raw bits. */
  static long sparkDouble(long bits) {
    Object v = NormalizeFloatingNumbers$.MODULE$.DOUBLE_NORMALIZER()
        .apply(Double.longBitsToDouble(bits));
    return Double.doubleToRawLongBits((Double) v);
  }

  private static int[] floatData() {
    var rnd = new SplittableRandom(337);
    int[] out = new int[FLOAT_CELLS.length + DRAWN];
    System.arraycopy(FLOAT_CELLS, 0, out, 0, FLOAT_CELLS.length);
    for (int i = FLOAT_CELLS.length; i < out.length; i++) {
      // Half the draws in the NaN space of either sign, so payloads meet the blend often.
      int bits = rnd.nextInt();
      out[i] = (i & 1) == 0 ? bits : (bits | 0x7f800001) & ~(rnd.nextBoolean() ? 0 : 0x80000000);
    }
    return out;
  }

  private static long[] doubleData() {
    var rnd = new SplittableRandom(338);
    long[] out = new long[DOUBLE_CELLS.length + DRAWN];
    System.arraycopy(DOUBLE_CELLS, 0, out, 0, DOUBLE_CELLS.length);
    for (int i = DOUBLE_CELLS.length; i < out.length; i++) {
      long bits = rnd.nextLong();
      out[i] = (i & 1) == 0 ? bits : bits | 0x7ff0000000000001L;
    }
    return out;
  }

  /** Each row at which the {@code FLOAT} kernel differs from Spark, or validity is wrong. */
  private static List<String> floatMismatches() {
    int[] values = floatData();
    int n = values.length;
    int cells = FLOAT_CELLS.length;
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(List.of(FLOAT_CANON), 1, 0, VarkaMatrixRuns.BASE));
    var bad = new ArrayList<String>();
    try (Arena arena = Arena.ofConfined()) {
      var col = VarkaEmitterFixtures.makeInputData(arena, n, i -> nullAt(i, cells),
          i -> values[i], true);
      var out = VarkaEmitterFixtures.makeOutput(arena, n, -1);
      int status = loaded.kernel().run(new long[] {col.data().address()},
          new long[] {col.validityAddress(n)}, new int[] {col.nullCount()},
          new long[] {out.data().address()}, new long[] {out.validity().address()}, new int[0],
          n, VarkaEmitterTestSupport.scratch(loaded.kernel(), n));
      assertEquals(0, status, "the kernel declined a batch it computes");
      for (int i = 0; i < n; i++) {
        boolean valid = (out.validity().get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
        if (valid == nullAt(i, cells)) {
          bad.add("validity of row " + i);
        } else if (valid) {
          int want = sparkFloat(values[i]);
          int got = out.data().get(ValueLayout.JAVA_INT, i * 4L);
          if (want != got) {
            bad.add("0x" + Integer.toHexString(values[i]) + ": want 0x"
                + Integer.toHexString(want) + ", got 0x" + Integer.toHexString(got));
          }
        }
      }
    } finally {
      loaded.loader().release();
    }
    return bad;
  }

  @Test
  @DisplayName("a FLOAT canonicalisation is Spark's FLOAT_NORMALIZER over the regions and a draw, "
      + "nulls staying null")
  void floatMatchesSparkNormalizer() {
    assertEquals(List.of(), floatMismatches());
  }

  @Test
  @DisplayName("a DOUBLE canonicalisation is Spark's DOUBLE_NORMALIZER over the NaN range "
      + "endpoints, the payloads, both zeros and a draw, nulls staying null")
  void doubleMatchesSparkNormalizer() {
    long[] values = doubleData();
    int n = values.length;
    int cells = DOUBLE_CELLS.length;
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(List.of(DOUBLE_CANON), 1, 0, VarkaMatrixRuns.BASE));
    var bad = new ArrayList<String>();
    try (Arena arena = Arena.ofConfined()) {
      var col = VarkaEmitterFixtures.makeLongInput(arena, n, i -> nullAt(i, cells),
          i -> values[i], true);
      var out = VarkaEmitterFixtures.makeLongOutput(arena, n);
      int status = loaded.kernel().run(new long[] {col.data().address()},
          new long[] {col.validityAddress(n)}, new int[] {col.nullCount()},
          new long[] {out.data().address()}, new long[] {out.validity().address()}, new int[0],
          new long[0], n, VarkaEmitterTestSupport.scratch(loaded.kernel(), n));
      assertEquals(0, status, "the kernel declined a batch it computes");
      for (int i = 0; i < n; i++) {
        boolean valid = (out.validity().get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
        if (valid == nullAt(i, cells)) {
          bad.add("validity of row " + i);
        } else if (valid) {
          long want = sparkDouble(values[i]);
          long got = out.data().get(ValueLayout.JAVA_LONG, i * 8L);
          if (want != got) {
            bad.add("0x" + Long.toHexString(values[i]) + ": want 0x" + Long.toHexString(want)
                + ", got 0x" + Long.toHexString(got));
          }
        }
      }
    } finally {
      loaded.loader().release();
    }
    assertEquals(List.of(), bad);
  }

  @Test
  @DisplayName("a canonicalisation of a canonicalisation is the one node, and its constructor "
      + "refuses an integral child")
  void idempotentByConstruction() {
    assertSame(FLOAT_CANON, CanonicalFloat.of(FLOAT_CANON));
    assertEquals(DOUBLE_CANON, CanonicalFloat.of(
        new VarkaVectorIR.ColumnRef(0, VarkaVectorIR.LaneType.DOUBLE)));
    var refused = assertThrows(IllegalArgumentException.class,
        () -> CanonicalFloat.of(new VarkaVectorIR.ColumnRef(0)));
    assertTrue(refused.getMessage().contains("floating child"), refused.getMessage());
  }

  @Test
  @DisplayName("the planted fault that drops the add of zero is failed by the zeros, and the "
      + "same check passes without it")
  void droppedZeroStepIsCaught() {
    VarkaFloatingFault.plantForTesting(VarkaFloatingFault.CANONICAL_ZERO_DROPPED);
    List<String> planted;
    try {
      planted = floatMismatches();
    } finally {
      VarkaFloatingFault.plantForTesting(VarkaFloatingFault.NONE);
    }
    assertTrue(planted.contains("0x80000000: want 0x0, got 0x80000000"), planted.toString());
    assertEquals(List.of(), floatMismatches());
  }

  /** Rows per chunk of the 2^32 sweep. */
  private static final int CHUNK = 1 << 16;

  @Test
  @DisplayName("every FLOAT pattern canonicalises to Spark's FLOAT_NORMALIZER, once and again "
      + "over its own output")
  void everyFloatPattern() {
    assumeTrue(Boolean.getBoolean("varka.sweep"), "an opt-in 2^32 sweep: -Dvarka.sweep=true");
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(List.of(FLOAT_CANON), 1, 0, VarkaMatrixRuns.BASE));
    try {
      VarkaFusedKernel kernel = loaded.kernel();
      long bad = IntStream.range(0, (int) ((1L << 32) / CHUNK)).parallel().mapToLong(c -> {
        long failures = 0;
        try (Arena arena = Arena.ofConfined()) {
          MemorySegment in = VarkaEmitterFixtures.alloc(arena, CHUNK * 4L);
          MemorySegment once = VarkaEmitterFixtures.alloc(arena, CHUNK * 4L);
          MemorySegment twice = VarkaEmitterFixtures.alloc(arena, CHUNK * 4L);
          MemorySegment validity = VarkaEmitterFixtures.alloc(arena, CHUNK / 8L);
          for (int i = 0; i < CHUNK; i++) {
            in.set(ValueLayout.JAVA_INT, i * 4L, c * CHUNK + i);
          }
          long scratch = VarkaEmitterTestSupport.scratch(kernel, CHUNK);
          for (MemorySegment[] pass : new MemorySegment[][] {{in, once}, {once, twice}}) {
            validity.fill((byte) 0);
            int status = kernel.run(new long[] {pass[0].address()}, new long[] {0L},
                new int[] {0}, new long[] {pass[1].address()}, new long[] {validity.address()},
                new int[0], CHUNK, scratch);
            if (status != 0) {
              return CHUNK;
            }
          }
          for (int i = 0; i < CHUNK; i++) {
            int want = sparkFloat(c * CHUNK + i);
            if (once.get(ValueLayout.JAVA_INT, i * 4L) != want
                || twice.get(ValueLayout.JAVA_INT, i * 4L) != want) {
              failures++;
            }
          }
        }
        return failures;
      }).sum();
      assertEquals(0L, bad, "FLOAT patterns whose canonicalisation differs from Spark's");
    } finally {
      loaded.loader().release();
    }
  }
}
