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
import static jdk.incubator.vector.VectorOperators.*;

import jdk.incubator.vector.*;

/**
 * Do the Vector API's math operators compute the same bits as the row engine would?
 *
 * Spark's math expressions call one of two scalar libraries: {@code java.lang.Math} for
 * sin, cos, tan, the inverse and hyperbolic trig, cbrt, sqrt, atan2 and hypot, and
 * {@code StrictMath} - fdlibm - for exp, expm1, log, log10, log1p and pow. A Varka kernel
 * would compute the same function with {@code DoubleVector.lanewise(VectorOperators.SIN)}
 * and its siblings, which C2 lowers to a vector math library: Intel's SVML on x86
 * ({@code libjsvml.so}), a SLEEF derivative on aarch64. Whether the lanes agree with the
 * scalar call bit for bit decides whether such a kernel can hold Varka's contract that a
 * fused result equals the row engine's, or needs a ULP contract instead
 * (SCOPE_FUNCTIONS.md section 3).
 *
 * Each operator runs over the same inputs enough times to reach C2, and every lane is then
 * compared against both scalar libraries. The count of differing lanes and the largest
 * difference in units in the last place are printed per operator. The inputs are positive and
 * moderate so every operator is defined on all of them; the point is agreement on ordinary
 * arguments, not edge behaviour.
 *
 * How to run:
 *   java --add-modules jdk.incubator.vector dev/varka_canary/MathLaneProbe.java
 * The control that shows the library was reached at all - the same run with the per-lane
 * scalar fallback forced, which must come out several times slower per element and agree
 * with java.lang.Math on every lane:
 *   java --add-modules jdk.incubator.vector -Djdk.incubator.vector.VectorMathLibrary=java \
 *     dev/varka_canary/MathLaneProbe.java
 * -Djdk.incubator.vector.DEBUG=true prints which library and which symbol each operator was
 * bound to, and -XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics shows whether C2 then
 * inlined the call ("late inline succeeded") or refused it ("missing constant").
 *
 * The readings, 19 September 2026, JDK 25.0.4, with the library reached on every operator
 * that has a symbol (the control pass several times slower per element, PrintIntrinsics
 * reporting "late inline succeeded"): on Zen 5 at AVX-512, on EPYC 7763 at AVX2 and on
 * Neoverse N2 with NEON, no operator agrees with either scalar library on every input.
 * Against the library Spark calls, the lanes differ by one ULP on up to thirteen percent of
 * inputs, by two for log10 and, on x86, tanh; and the three library builds differ from one
 * another. The outputs are committed beside this file as mathlane-*.txt, the tables and
 * their consequence for Spark's function set are in SCOPE_FUNCTIONS.md section 3, and
 * varka-canary.yml re-takes the two runner readings on every push that touches this
 * directory.
 */
public class MathLaneProbe {
  static final VectorSpecies<Double> S = DoubleVector.SPECIES_PREFERRED;
  static final int L = S.length();

  static DoubleVector ld(double[] a, int i) {
    return DoubleVector.fromArray(S, a, i);
  }

  /**
   * One loop per operator, with the operator written as a constant in each. C2 only emits
   * the call into the vector math library when the operator is a compile-time constant; an
   * operator that arrives as a method parameter makes it fall back to a per-lane scalar call,
   * silently, and the lanes then agree with {@code java.lang.Math} by construction. The
   * nanoseconds-per-element column exists to make that fallback visible: a library call is
   * several times faster than the scalar one.
   */
  static void run(int k, double[] x, double[] v) {
    int n = x.length;
    switch (k) {
      case 0 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(SIN).intoArray(v, i); } }
      case 1 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(COS).intoArray(v, i); } }
      case 2 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(TAN).intoArray(v, i); } }
      case 3 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(EXP).intoArray(v, i); } }
      case 4 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(LOG).intoArray(v, i); } }
      case 5 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(LOG10).intoArray(v, i); } }
      case 6 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(EXPM1).intoArray(v, i); } }
      case 7 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(LOG1P).intoArray(v, i); } }
      case 8 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(ATAN).intoArray(v, i); } }
      case 9 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(TANH).intoArray(v, i); } }
      case 10 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(CBRT).intoArray(v, i); } }
      default -> throw new IllegalArgumentException("no unary operator " + k);
    }
  }

  static void run2(int k, double[] x, double[] y, double[] v) {
    int n = x.length;
    switch (k) {
      case 0 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(POW, ld(y, i))
          .intoArray(v, i); } }
      case 1 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(ATAN2, ld(y, i))
          .intoArray(v, i); } }
      case 2 -> { for (int i = 0; i < n; i += L) { ld(x, i).lanewise(HYPOT, ld(y, i))
          .intoArray(v, i); } }
      default -> throw new IllegalArgumentException("no binary operator " + k);
    }
  }

  static double math(String name, double x) {
    return switch (name) {
      case "SIN" -> Math.sin(x); case "COS" -> Math.cos(x); case "TAN" -> Math.tan(x);
      case "EXP" -> Math.exp(x); case "LOG" -> Math.log(x); case "LOG10" -> Math.log10(x);
      case "EXPM1" -> Math.expm1(x); case "LOG1P" -> Math.log1p(x); case "ATAN" -> Math.atan(x);
      case "TANH" -> Math.tanh(x); default -> Math.cbrt(x);
    };
  }

  static double strict(String name, double x) {
    return switch (name) {
      case "SIN" -> StrictMath.sin(x); case "COS" -> StrictMath.cos(x);
      case "TAN" -> StrictMath.tan(x); case "EXP" -> StrictMath.exp(x);
      case "LOG" -> StrictMath.log(x); case "LOG10" -> StrictMath.log10(x);
      case "EXPM1" -> StrictMath.expm1(x); case "LOG1P" -> StrictMath.log1p(x);
      case "ATAN" -> StrictMath.atan(x); case "TANH" -> StrictMath.tanh(x);
      default -> StrictMath.cbrt(x);
    };
  }

  /** The binary operators Spark reaches: pow through StrictMath, atan2 and hypot through Math. */
  static double math2(String name, double x, double y) {
    return switch (name) {
      case "POW" -> Math.pow(x, y); case "ATAN2" -> Math.atan2(x, y); default -> Math.hypot(x, y);
    };
  }

  static double strict2(String name, double x, double y) {
    return switch (name) {
      case "POW" -> StrictMath.pow(x, y); case "ATAN2" -> StrictMath.atan2(x, y);
      default -> StrictMath.hypot(x, y);
    };
  }

  /**
   * What machine this ran on, printed first so a result can be attributed: the JVM's own view
   * of the architecture and vector width, and the AVX level where the flag exists. A row of
   * results without this line is the thing VARKA-150 exists to prevent.
   */
  static String machine() {
    String avx;
    try {
      avx = java.lang.management.ManagementFactory
          .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
          .getVMOption("UseAVX").getValue();
    } catch (RuntimeException e) {
      avx = "n/a";
    }
    String mvs;
    try {
      mvs = java.lang.management.ManagementFactory
          .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
          .getVMOption("MaxVectorSize").getValue();
    } catch (RuntimeException e) {
      mvs = "n/a";
    }
    return "arch=" + System.getProperty("os.arch") + " jvm=" + System.getProperty("java.vm.version")
        + " UseAVX=" + avx + " MaxVectorSize=" + mvs + " species double=" + S.length()
        + " library=" + System.getProperty("jdk.incubator.vector.VectorMathLibrary", "default");
  }

  static void report(String name, double nsPerElem, double[] v, double[] x, double[] y) {
    long diffMath = 0, diffStrict = 0, ulpMath = 0, ulpStrict = 0;
    for (int i = 0; i < v.length; i++) {
      long b = Double.doubleToLongBits(v[i]);
      double m = y == null ? math(name, x[i]) : math2(name, x[i], y[i]);
      double s = y == null ? strict(name, x[i]) : strict2(name, x[i], y[i]);
      long em = Math.abs(b - Double.doubleToLongBits(m));
      long es = Math.abs(b - Double.doubleToLongBits(s));
      if (em != 0) { diffMath++; ulpMath = Math.max(ulpMath, em); }
      if (es != 0) { diffStrict++; ulpStrict = Math.max(ulpStrict, es); }
    }
    System.out.printf("%-6s %5.1f ns/elem  vs Math: %6d lanes differ (max %d ulp)"
        + "  vs StrictMath: %6d lanes differ (max %d ulp)%n",
        name, nsPerElem, diffMath, ulpMath, diffStrict, ulpStrict);
  }

  public static void main(String[] a) {
    int n = 1 << 18;
    int rounds = a.length > 0 ? Integer.parseInt(a[0]) : 200;
    int timed = 20;
    double[] x = new double[n];
    double[] v = new double[n];
    java.util.Random r = new java.util.Random(7);
    for (int i = 0; i < n; i++) {
      x[i] = Math.abs((r.nextDouble() - 0.5) * 60);
    }
    String[] names = {"SIN", "COS", "TAN", "EXP", "LOG", "LOG10", "EXPM1", "LOG1P", "ATAN",
        "TANH", "CBRT"};
    String[] names2 = {"POW", "ATAN2", "HYPOT"};
    // Touch every operator once, in the interpreter, before anything is hot. The JDK binds an
    // operator to its library symbol on first use and C2 folds that binding into compiled
    // code only if it exists at compile time; a method compiled while an operator it names
    // is still unbound keeps a memory load and the scalar fallback for good. Warming the
    // operators one at a time had exactly that effect on some hosts.
    double[] one = new double[L];
    java.util.Arrays.fill(one, 1.5);
    for (int k = 0; k < names.length; k++) {
      run(k, one, one.clone());
    }
    for (int k = 0; k < names2.length; k++) {
      run2(k, one, one, one.clone());
    }
    System.out.println(machine() + "; inputs=" + n + "; rounds before measuring=" + rounds);
    for (int k = 0; k < names.length; k++) {
      for (int w = 0; w < rounds; w++) {
        run(k, x, v);
      }
      long t0 = System.nanoTime();
      for (int w = 0; w < timed; w++) {
        run(k, x, v);
      }
      report(names[k], (System.nanoTime() - t0) / (double) timed / n, v, x, null);
    }
    // The binary operators, over a second positive operand in a moderate range.
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      y[i] = 0.5 + r.nextDouble() * 4;
    }
    for (int k = 0; k < names2.length; k++) {
      for (int w = 0; w < rounds; w++) {
        run2(k, x, y, v);
      }
      long t0 = System.nanoTime();
      for (int w = 0; w < timed; w++) {
        run2(k, x, y, v);
      }
      report(names2[k], (System.nanoTime() - t0) / (double) timed / n, v, x, y);
    }
  }
}
