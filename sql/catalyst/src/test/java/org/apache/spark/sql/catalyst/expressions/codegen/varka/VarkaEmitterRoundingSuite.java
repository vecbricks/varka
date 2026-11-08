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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code RoundToIntegral} with mode {@code RINT} (VARKA-344): the kernel's answer is held to
 * {@code Math.rint} bit for bit over the cells that decide the 2^52 construction, every binary
 * exponent from -1074 to 1023 in both signs and a seeded draw, with a NaN compared by being one
 * (its sign is the implementation's, {@code VARKA-344.md} 0.10). Every cell is on a valid lane and
 * the nulls fall among the rest, where a null lane's output is null; the construction's binary32
 * twin is exhausted over all 2^32 patterns behind {@code -Dvarka.sweep=true}.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterRoundingSuite {

  private static final VarkaVectorIR.ColumnRef X =
      new VarkaVectorIR.ColumnRef(0, VarkaVectorIR.LaneType.DOUBLE);

  private static final VarkaVectorIR RINT =
      new VarkaVectorIR.RoundToIntegral(VarkaVectorIR.RoundMode.RINT, X);

  /** How many of {@link #data}'s rows are the cells, which no null covers. */
  private static final int CELLS = cells().length;

  /** The cells of {@code VARKA-344.md} 3.1 for {@code rint}, as doubles. */
  private static double[] cells() {
    double c = 0x1.0p52;
    return new double[] {
        -0.3, -0.0, 0.0, 0.5, -0.5, 1.5, 2.5, -1.5, -2.5, 0.49999999999999994, 1.0, -1.0,
        c - 0.5, c - 1.5, -(c - 0.5), c, c + 1, -c, -(c + 1), 0x1.0p53, Double.MAX_VALUE,
        -Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
        Double.longBitsToDouble(0xfff8000000000000L), Double.longBitsToDouble(0x7ff8000000000001L),
        Math.nextDown(c), Math.nextUp(c), Math.nextDown(0.5), Math.nextUp(0.5), 4503599627370495.5,
        2147483647.5, -2147483648.5, 9007199254740991.0};
  }

  /**
   * The cells, then each power of two {@code 2^e} for every {@code e} from -1074 (the smallest
   * subnormal) to 1023, with {@code 1.5 * 2^e} and both neighbours, in both signs, the ties below
   * {@code 2^52} and their neighbours, and a seeded draw.
   */
  private static double[] data() {
    var out = new java.util.ArrayList<Double>();
    for (double d : cells()) {
      out.add(d);
    }
    for (int e = -1074; e <= 1023; e++) {
      double p = Math.scalb(1.0, e);
      for (double v : new double[] {p, 1.5 * p, Math.nextUp(p), Math.nextDown(p)}) {
        out.add(v);
        out.add(-v);
      }
    }
    for (int k = 0; k < 64; k++) {
      double tie = Math.scalb(1.0, 52 - k) + 0.5;
      out.add(tie);
      out.add(-tie);
      out.add(Math.nextUp(tie));
      out.add(Math.nextDown(tie));
    }
    var rnd = new SplittableRandom(344);
    for (int i = 0; i < 4000; i++) {
      long bits = rnd.nextLong();
      out.add(Double.longBitsToDouble(bits));
      out.add(Math.floor(Double.longBitsToDouble(bits & 0x433fffffffffffffL)) + 0.5);
    }
    return out.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /** Whether row {@code i} of {@link #data} is null: none of the cells, every seventh after. */
  private static boolean nullAt(int i) {
    return i >= CELLS && i % 7 == 3;
  }

  @Test
  @DisplayName("rint is Math.rint for the cells, every exponent and a draw, nulls staying null")
  void rintMatchesMathRint() {
    double[] values = data();
    int n = values.length;
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(List.of(RINT), 1, 0, VarkaMatrixRuns.BASE));
    try (Arena arena = Arena.ofConfined()) {
      var col = VarkaEmitterFixtures.makeLongInput(arena, n, VarkaEmitterRoundingSuite::nullAt,
          i -> Double.doubleToRawLongBits(values[i]), true);
      var out = VarkaEmitterFixtures.makeLongOutput(arena, n);
      int status = loaded.kernel().run(new long[] {col.data().address()},
          new long[] {col.validityAddress(n)}, new int[] {col.nullCount()},
          new long[] {out.data().address()}, new long[] {out.validity().address()}, new int[0],
          new long[0], n, VarkaEmitterTestSupport.scratch(loaded.kernel(), n));
      assertEquals(0, status, "the kernel declined a batch it computes");
      for (int i = 0; i < n; i++) {
        boolean valid = (out.validity().get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
        assertEquals(!nullAt(i), valid, "validity of row " + i);
        if (valid) {
          double want = Math.rint(values[i]);
          double got = Double.longBitsToDouble(out.data().get(ValueLayout.JAVA_LONG, i * 8L));
          if (Double.isNaN(want)) {
            assertTrue(Double.isNaN(got), "rint(" + values[i] + ") should be NaN, not " + got);
          } else {
            assertEquals(Double.doubleToRawLongBits(want), Double.doubleToRawLongBits(got),
                "rint(" + values[i] + ") = " + want + ", got " + got);
          }
        }
      }
    } finally {
      loaded.loader().release();
    }
  }

  @Test
  @DisplayName("a rounding takes a double child only")
  void rejectsAnIntegralChild() {
    assertThrows(IllegalArgumentException.class, () -> new VarkaVectorIR.RoundToIntegral(
        VarkaVectorIR.RoundMode.RINT, new VarkaVectorIR.ColumnRef(0)));
  }

  /** The construction of {@code VarkaRoundingLowering} at {@code float}'s precision. */
  private static float rintTwin(float x) {
    float a = Math.abs(x);
    float c = 0x1.0p23f;
    if (!(a < c)) {
      return x;
    }
    float r = (a + c) - c;
    return Float.floatToRawIntBits(x) < 0 ? Float.intBitsToFloat(
        Float.floatToRawIntBits(r) | 0x80000000) : r;
  }

  @Test
  @DisplayName("the binary32 twin of the construction is rint for all 2^32 patterns")
  void binary32TwinIsRintForEveryPattern() {
    assumeTrue(Boolean.getBoolean("varka.sweep"), "an opt-in 2^32 sweep: -Dvarka.sweep=true");
    long bad = IntStream.range(0, 1 << 16).parallel().mapToLong(hi -> {
      long failures = 0;
      for (int lo = 0; lo < (1 << 16); lo++) {
        float x = Float.intBitsToFloat((hi << 16) | lo);
        float want = (float) StrictMath.rint(x);
        float got = rintTwin(x);
        // The twin against StrictMath's rint: two Java results, a NaN held to being one.
        if (Float.isNaN(want) ? !Float.isNaN(got)
            : Float.floatToRawIntBits(want) != Float.floatToRawIntBits(got)) { // nan-compare: ok
          failures++;
        }
      }
      return failures;
    }).sum();
    if (bad != 0) {
      fail(bad + " binary32 patterns differ from rint");
    }
  }
}
