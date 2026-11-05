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
 *
 * The overflow band. Beside the random inputs, a few fixed ones are pushed through the same
 * compiled EXP and POW loops and printed lane by lane next to StrictMath and Math. JDK 25
 * bundles SLEEF 3.6.1 (src/jdk.incubator.vector/unix/native/libsleef/README.md, tag
 * jdk-25-ga), and its exp returns +Inf for every argument above 709.78271114955742909
 * (libsleef/generated/sleefinline_advsimd.h line 3297, sleefinline_sve.h line 3384), while
 * the true overflow point is ln(Double.MAX_VALUE) = 709.782712893384. The 15,338,860 doubles
 * in between have a finite exp near 1.7977e308 that the SLEEF lane reports as infinity. Its
 * pow tests the same constant against y * ln|x| (advsimd line 3420, sve line 3507), so
 * pow(Double.MAX_VALUE, 1.0) is infinite there too. expm1 uses the true constant
 * (advsimd line 3805), so the band is exp's and pow's alone. The inputs are both ends of the
 * band, its middle and the double just outside each end. On the Neoverse N2 runner, 16 October
 * 2026, every EXP and POW lane inside the band was +Inf (mathlane-aarch64-neoverse-n2.txt).
 * SVML on x86 uses the true limit: its EXP lanes, and its POW lanes at AVX-512, equal
 * StrictMath across the band. At AVX2 SVML binds no POW, so those lanes are the fallback.
 *
 * The mask intrinsic. With the argument "fromlong" the probe instead runs a hot
 * VectorMask.fromLong loop and nothing else, meant to be run under
 * -XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics: the line for
 * VectorSupport::fromBitsCoerced says "(intrinsic)" when C2 emits VectorLongToMask, and
 * "** not supported: arity=0 op=broadcast ... ismask=1" when it refuses. On aarch64 the
 * node needs SVE's bit-permute extension (hotspot/cpu/aarch64/aarch64_vector.ad lines
 * 217-218 check supports_svebitperm), which the "svebitperm" entry in the CPU feature line
 * reports. Only the fromBitsCoerced call at bci 39 is fromLong's own; the ones at bci 25 are
 * maskAll's broadcasts inside the Java fallback. A "missing constant" refusal at bci 39 means
 * the species was not a constant in that compile, not that the hardware lacks the node.
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

  // ---- the overflow band ----

  /** SLEEF 3.6.1's exp and pow overflow threshold, the constant exactly as the header has it. */
  static final double SLEEF_EXP_MAX = 709.78271114955742909217217426;
  /** The largest double whose exp is finite: ln(Double.MAX_VALUE). */
  static final double EXP_MAX = 709.782712893384;

  /** Fixed EXP inputs around the band (SLEEF_EXP_MAX, EXP_MAX], with what each one is. */
  static final double[] EXP_BAND;
  static final String[] EXP_BAND_LABEL = {"below band", "SLEEF constant (still finite)",
      "first double above it", "middle of band", "709.7827112, inside the band",
      "last below EXP_MAX", "EXP_MAX (last finite)", "first true overflow"};

  static {
    long lo = Double.doubleToLongBits(SLEEF_EXP_MAX);
    long hi = Double.doubleToLongBits(EXP_MAX);
    EXP_BAND = new double[] {Math.nextDown(SLEEF_EXP_MAX), SLEEF_EXP_MAX,
        Math.nextUp(SLEEF_EXP_MAX), Double.longBitsToDouble(lo + (hi - lo) / 2), 709.7827112,
        Math.nextDown(EXP_MAX), EXP_MAX, Math.nextUp(EXP_MAX)};
  }

  /**
   * Fixed POW inputs. SLEEF's guard is on d = y * ln|x|, so the band is placed through y with
   * x = 2: y = 1023.999997 puts d just below the constant, 1023.9999975 just above it,
   * 1023.999999 in the middle; pow(MAX_VALUE, 1) has d = EXP_MAX, the top end; pow(2, 1024)
   * is a true overflow.
   */
  static final double[] POW_BAND_X = {2, 2, 2, 2, Double.MAX_VALUE, 2};
  static final double[] POW_BAND_Y = {1023, 1023.999997, 1023.9999975, 1023.999999, 1, 1024};
  static final String[] POW_BAND_LABEL = {"d far below band", "d just below band",
      "d just above SLEEF constant", "d in middle of band", "d = EXP_MAX (top end)",
      "first true overflow"};

  /** Ordinary inputs placed after the fixed ones in every tile; see {@link #tiled}. */
  static final int WITNESSES = 256;

  /**
   * The fixed inputs followed by WITNESSES ordinary ones (1.5 upwards in steps of
   * {@code step}, or {@code witness} itself when step is 0), padded to a whole number of
   * vectors and repeated to fill n elements. A short array would be no good: the loop that
   * runs it is compiled by C2 only as an on-stack replacement, so a call over a few elements
   * runs in the interpreter or C1, where the operator takes the per-lane scalar fallback and
   * agrees with Math by construction. Over n elements the loop migrates into the C2 code
   * long before the end, so the last tile is read. The witnesses check that it did: the
   * fallback calls Math and so agrees with it on every lane, while the library differs from
   * Math on some ordinary inputs: about one in ten for SLEEF on NEON, one in twenty for SVML at
   * AVX-512, but only one in two hundred for SVML's EXP at AVX2, where a zero count is weak
   * evidence. An operator with no library symbol (POW at AVX2, TANH on NEON) is always the
   * fallback, and its witnesses are always zero.
   */
  static double[] tiled(double[] in, double witness, double step, int n) {
    int period = (in.length + WITNESSES + L - 1) / L * L;
    double[] out = new double[n];
    for (int i = 0; i < n; i++) {
      int j = i % period;
      out[i] = j < in.length ? in[j] : witness + (j - in.length) * step;
    }
    return out;
  }

  /** Where the last whole tile of {@link #tiled} starts. */
  static int lastTile(int fixed, int n) {
    int period = (fixed + WITNESSES + L - 1) / L * L;
    return (n / period - 1) * period;
  }

  static String bandLine(String op, String in, double lane, double strict, double math,
      String label) {
    return String.format("%-4s %-34s lane=%-24s StrictMath=%-24s Math=%-24s %s%s", op, in,
        lane, strict, math, label,  // nan-compare: ok
        Double.doubleToLongBits(lane) == Double.doubleToLongBits(strict) ? "" : "  <- differs");
  }

  // ---- the mask intrinsic ----

  /**
   * VectorMask.fromLong in a loop hot enough for C2, its result consumed so the loop is not
   * dead code. Run under PrintIntrinsics, which reports whether fromBitsCoerced became
   * VectorLongToMask.
   */
  static long fromLongLoop(long[] bits) {
    VectorSpecies<Byte> b = ByteVector.SPECIES_128;
    long sink = 0;
    for (long x : bits) {
      sink += VectorMask.fromLong(S, x).trueCount();
      sink += VectorMask.fromLong(b, x).toLong();
    }
    return sink;
  }

  static void fromLong() {
    long[] bits = new long[1 << 12];
    java.util.Random r = new java.util.Random(11);
    for (int i = 0; i < bits.length; i++) {
      bits[i] = r.nextLong();
    }
    long sink = 0;
    for (int w = 0; w < 2000; w++) {
      sink += fromLongLoop(bits);
    }
    System.out.println(machine() + "; VectorMask.fromLong over " + S + " and "
        + ByteVector.SPECIES_128 + ", sink=" + sink);
  }

  public static void main(String[] a) {
    if (a.length > 0 && a[0].equals("fromlong")) {
      fromLong();
      return;
    }
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
    double[] expIn = tiled(EXP_BAND, 1.5, 0.0937, n);
    double[] powX = tiled(POW_BAND_X, 1.5, 0.0937, n);
    double[] powY = tiled(POW_BAND_Y, 0.5, 0.0613, n);
    double[] expOut = new double[n];
    double[] powOut = new double[n];
    for (int k = 0; k < names.length; k++) {
      for (int w = 0; w < rounds; w++) {
        run(k, x, v);
      }
      long t0 = System.nanoTime();
      for (int w = 0; w < timed; w++) {
        run(k, x, v);
      }
      report(names[k], (System.nanoTime() - t0) / (double) timed / n, v, x, null);
      if (names[k].equals("EXP")) {
        // The band goes through the loop just measured, while its C2 code exists.
        run(k, expIn, expOut);
      }
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
      if (names2[k].equals("POW")) {
        run2(k, powX, powY, powOut);
      }
    }
    System.out.println("overflow band: SLEEF 3.6.1 exp/pow threshold " + SLEEF_EXP_MAX
        + ", true exp overflow above " + EXP_MAX);
    int e0 = lastTile(EXP_BAND.length, n);
    for (int i = 0; i < EXP_BAND.length; i++) {
      double e = EXP_BAND[i];
      System.out.println(bandLine("EXP", e + " (" + Long.toHexString(Double.doubleToLongBits(e))
          + ")", expOut[e0 + i], StrictMath.exp(e), Math.exp(e), EXP_BAND_LABEL[i]));
    }
    int p0 = lastTile(POW_BAND_X.length, n);
    for (int i = 0; i < POW_BAND_X.length; i++) {
      double px = POW_BAND_X[i], py = POW_BAND_Y[i];
      System.out.println(bandLine("POW", px + "^" + py, powOut[p0 + i], StrictMath.pow(px, py),
          Math.pow(px, py), POW_BAND_LABEL[i]));
    }
    int expWit = 0, powWit = 0;
    for (int i = 0; i < WITNESSES; i++) {
      int ei = e0 + EXP_BAND.length + i, pi = p0 + POW_BAND_X.length + i;
      expWit += expOut[ei] == Math.exp(expIn[ei]) ? 0 : 1;
      powWit += powOut[pi] == Math.pow(powX[pi], powY[pi]) ? 0 : 1;
    }
    System.out.println("witnesses in the same tile (library reached if some differ): EXP "
        + expWit + " of " + WITNESSES + ", POW " + powWit + " of " + WITNESSES
        + " ordinary lanes differ from Math");
  }
}
