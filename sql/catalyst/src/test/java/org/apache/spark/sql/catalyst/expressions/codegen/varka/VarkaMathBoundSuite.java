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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The opt-in mode's comparator ({@code VARKA-345.md} 3.5): {@link VarkaMathBound} counts the
 * representable doubles between two values of one sign, and refuses at every bound the pairs a
 * multiple of {@code Math.ulp} or the probe's raw-bit distance wrongly admit; and the canary's
 * copy of it, {@code dev/varka_canary/MathSteps.java}, answers as it does.
 */
@VarkaSuite
public final class VarkaMathBoundSuite {

  @Test
  @DisplayName("at a bound of k a lane k steps out passes, and k + 1 and k + 2 steps out fail")
  void boundIsAStepCount() {
    var random = new SplittableRandom(345);
    for (int i = 0; i < 10_000; i++) {
      // A finite reference of either sign and a lane k steps further from zero, both of one sign.
      long magnitude = random.nextLong() & 0x7fffffffffffffffL;
      if (magnitude >= 0x7ff0000000000000L) {
        continue;
      }
      double r = Double.longBitsToDouble(magnitude) * (random.nextBoolean() ? 1 : -1);
      int k = random.nextInt(8);
      double sign = Math.copySign(1.0, r);
      long bits = Double.doubleToRawLongBits(Math.abs(r));
      for (int out = 0; out <= k + 2; out++) {
        double lane = sign * Double.longBitsToDouble(bits + out);
        // Past MAX_VALUE the walk reaches infinity and then NaN bit patterns.
        if (!Double.isFinite(lane)) {
          continue;
        }
        assertEquals(out, VarkaMathBound.steps(lane, r));
        assertEquals(out <= k, VarkaMathBound.within(lane, r, k), r + " " + lane + " " + k);
        assertEquals(out <= k, VarkaMathBound.within(r, lane, k), "symmetric");
      }
    }
  }

  @Test
  @DisplayName("NaN against a number, an infinity against its neighbour and opposite signs fail")
  void refusedPairs() {
    double nan = Double.NaN;
    double otherNan = Double.longBitsToDouble(0x7ff0000000000001L);
    double negativeNan = Double.longBitsToDouble(0xfff8000000000000L);
    List<double[]> refused = List.of(
        new double[] {nan, 1.0}, new double[] {0.0, nan},
        new double[] {nan, Double.POSITIVE_INFINITY},
        new double[] {Double.POSITIVE_INFINITY, Double.MAX_VALUE},
        new double[] {Double.NEGATIVE_INFINITY, -Double.MAX_VALUE},
        new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY},
        new double[] {-0.0, 0.0}, new double[] {-Double.MIN_VALUE, Double.MIN_VALUE},
        new double[] {-1.0, 1.0}, new double[] {-Double.MAX_VALUE, Double.MAX_VALUE});
    for (double[] pair : refused) {
      for (double[] p : List.of(pair, new double[] {pair[1], pair[0]})) {
        String what = p[0] + " against " + p[1];
        assertEquals(VarkaMathBound.REFUSED, VarkaMathBound.steps(p[0], p[1]), what);
        assertFalse(VarkaMathBound.within(p[0], p[1], Integer.MAX_VALUE), what);
      }
    }
    // Two NaNs pass whatever their payloads and signs, and an infinity passes against itself.
    for (double a : List.of(nan, otherNan, negativeNan)) {
      for (double b : List.of(nan, otherNan, negativeNan)) {
        assertEquals(0, VarkaMathBound.steps(a, b));
        assertTrue(VarkaMathBound.within(a, b, 0));
      }
    }
    assertTrue(VarkaMathBound.within(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0));
    assertTrue(VarkaMathBound.within(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0));
    assertTrue(VarkaMathBound.within(-0.0, -0.0, 0));
    assertThrows(IllegalArgumentException.class, () -> VarkaMathBound.within(1.0, 1.0, -1));
  }

  @Test
  @DisplayName("the count is exact either side of a power of two, where an ulp multiple is not")
  void acrossAPowerOfTwo() {
    for (double sign : List.of(1.0, -1.0)) {
      for (int e : List.of(-1073, -1022, -1, 0, 1, 52, 1023)) {
        double p = sign * Math.scalb(1.0, e);
        double below = Math.nextDown(Math.abs(p)) * sign;
        double twoBelow = Math.nextDown(Math.abs(below)) * sign;
        double above = Math.nextUp(Math.abs(p)) * sign;
        assertEquals(1, VarkaMathBound.steps(below, p), "below " + p);
        assertEquals(2, VarkaMathBound.steps(twoBelow, p), "two below " + p);
        assertEquals(1, VarkaMathBound.steps(above, p), "above " + p);
        assertEquals(3, VarkaMathBound.steps(twoBelow, above), "across " + p);
      }
    }
    // A multiple of Math.ulp at r = 1.0 admits the double two steps below; the count does not.
    double twoBelowOne = Math.nextDown(Math.nextDown(1.0));
    assertTrue(Math.abs(twoBelowOne - 1.0) <= Math.ulp(1.0));
    assertFalse(VarkaMathBound.within(twoBelowOne, 1.0, 1));
    // The smallest subnormal is one step from zero of its own sign, and the largest finite value
    // one step from the one below it.
    assertEquals(1, VarkaMathBound.steps(Double.MIN_VALUE, 0.0));
    assertEquals(1, VarkaMathBound.steps(-Double.MIN_VALUE, -0.0));
    assertEquals(1, VarkaMathBound.steps(Math.nextDown(Double.MAX_VALUE), Double.MAX_VALUE));
  }

  @Test
  @DisplayName("the canary's copy MathSteps.steps answers as VarkaMathBound.steps on every pair")
  void canaryCopyAgrees(@TempDir Path classes) throws Throwable {
    // The extended math probe runs with no Spark build, so it carries a copy; compile it here.
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    Path source = Path.of(home, "dev", "varka_canary", "MathSteps.java");
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d",
        classes.toString(), source.toString()), "MathSteps.java does not compile");
    try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null)) {
      MethodHandle copy = MethodHandles.publicLookup().findStatic(loader.loadClass("MathSteps"),
          "steps", MethodType.methodType(long.class, double.class, double.class));
      var random = new SplittableRandom(367);
      double[] fixed = {Double.NaN, Double.longBitsToDouble(0xfff8000000000001L), 0.0, -0.0,
          Double.MIN_VALUE, -Double.MIN_VALUE, 1.0, -1.0, Double.MAX_VALUE,
          Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
      for (double a : fixed) {
        for (double b : fixed) {
          assertEquals(VarkaMathBound.steps(a, b), (long) copy.invokeExact(a, b), a + " " + b);
        }
      }
      for (int i = 0; i < 1 << 24; i++) {
        // Half the pairs a few steps apart, the rest any two bit patterns.
        long bits = random.nextLong();
        long other = i % 2 == 0 ? bits + random.nextInt(-8, 9) : random.nextLong();
        double a = Double.longBitsToDouble(bits);
        double b = Double.longBitsToDouble(other);
        long expected = VarkaMathBound.steps(a, b);
        long actual = (long) copy.invokeExact(a, b);
        if (expected != actual) {
          assertEquals(expected, actual, Long.toHexString(bits) + " " + Long.toHexString(other));
        }
      }
    }
  }
}
