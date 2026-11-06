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
import jdk.incubator.vector.*;

/**
 * Which NaN does a floating add, subtract, multiply or divide return when an operand is a NaN, on
 * this host (VARKA-576)? The payload and the quiet bit of the result are the hardware's rule, and
 * x86 and Arm differ: x86 returns the first operand's payload, quieted; Arm, a signalling
 * operand's, quieted, before a quiet one's. Varka's reference evaluator and `VarkaSameAnswer`
 * model one rule, so the answers of the host that runs them have to be read, not assumed.
 *
 * Every ordered pair of NaN patterns (quiet and signalling, both signs, several payloads), and
 * each NaN beside a number, goes through each operator at both widths, as scalar Java and as the
 * Vector API's lanes, once cold and once after both have been compiled by C2. A line prints the
 * result bits of each, and `DIFF` where the four disagree. The scalar answer is what Spark's own
 * `eval` gives; the lane answer is what a kernel gives.
 *
 * Run:
 *   java --add-modules jdk.incubator.vector dev/varka_canary/NaNPayloadProbe.java
 */
public class NaNPayloadProbe {
  static final String[] OPS = {"add", "sub", "mul", "div"};
  static final VectorOperators.Binary[] VOPS = {
    VectorOperators.ADD, VectorOperators.SUB, VectorOperators.MUL, VectorOperators.DIV
  };

  // FLOAT patterns: quiet and signalling NaNs, both signs, payloads; then numbers.
  static final int[] F_NAN = {
    0x7FC00000, 0x7FC00001, 0xFFC00000, 0xFFC00002, 0x7F800001, 0x7FA00000, 0xFF800001
  };
  static final int[] F_NUM = {0x3F800000, 0x7F800000, 0xFF800000, 0x00000000};
  // DOUBLE patterns, the same shapes.
  static final long[] D_NAN = {
    0x7FF8000000000000L, 0x7FF8000000000001L, 0xFFF8000000000000L, 0xFFF8000000000002L,
    0x7FF0000000000001L, 0x7FF4000000000000L, 0xFFF0000000000001L
  };
  static final long[] D_NUM = {0x3FF0000000000000L, 0x7FF0000000000000L, 0xFFF0000000000000L, 0L};

  static float sf(int op, float a, float b) {
    return switch (op) {
      case 0 -> a + b;
      case 1 -> a - b;
      case 2 -> a * b;
      default -> a / b;
    };
  }

  static double sd(int op, double a, double b) {
    return switch (op) {
      case 0 -> a + b;
      case 1 -> a - b;
      case 2 -> a * b;
      default -> a / b;
    };
  }

  public static void main(String[] args) {
    System.out.println("os.arch " + System.getProperty("os.arch") + "  java "
        + System.getProperty("java.version"));
    System.out.println("float species " + FloatVector.SPECIES_PREFERRED + "  double species "
        + DoubleVector.SPECIES_PREFERRED);
    int diffs = 0;
    int lines = 0;
    // FLOAT
    int[][] fp = pairs(F_NAN, F_NUM);
    int fn = fp[0].length;
    VectorSpecies<Float> fs = FloatVector.SPECIES_PREFERRED;
    int fpad = (fn + fs.length() - 1) / fs.length() * fs.length();
    float[] fa = new float[fpad];
    float[] fb = new float[fpad];
    for (int i = 0; i < fn; i++) {
      fa[i] = Float.intBitsToFloat(fp[0][i]);
      fb[i] = Float.intBitsToFloat(fp[1][i]);
    }
    for (int op = 0; op < 4; op++) {
      int[] cold = new int[fpad];
      int[] hot = new int[fpad];
      int[] vcold = new int[fpad];
      int[] vhot = new int[fpad];
      for (int i = 0; i < fn; i++) {
        cold[i] = Float.floatToRawIntBits(sf(op, fa[i], fb[i]));
      }
      vectorsF(op, fa, fb, vcold, fs);
      for (int round = 0; round < 30000; round++) {
        for (int i = 0; i < fn; i++) {
          hot[i] = Float.floatToRawIntBits(sf(op, fa[i], fb[i]));
        }
        vectorsF(op, fa, fb, vhot, fs);
      }
      for (int i = 0; i < fn; i++) {
        boolean diff = cold[i] != hot[i] || hot[i] != vhot[i] || vcold[i] != vhot[i];
        diffs += diff ? 1 : 0;
        lines++;
        System.out.printf("float %s %08x %08x scalar %08x/%08x lanes %08x/%08x%s%n", OPS[op],
            fp[0][i], fp[1][i], cold[i], hot[i], vcold[i], vhot[i], diff ? " DIFF" : "");
      }
    }
    // DOUBLE
    long[][] dp = pairsL(D_NAN, D_NUM);
    int dn = dp[0].length;
    VectorSpecies<Double> ds = DoubleVector.SPECIES_PREFERRED;
    int dpad = (dn + ds.length() - 1) / ds.length() * ds.length();
    double[] da = new double[dpad];
    double[] db = new double[dpad];
    for (int i = 0; i < dn; i++) {
      da[i] = Double.longBitsToDouble(dp[0][i]);
      db[i] = Double.longBitsToDouble(dp[1][i]);
    }
    for (int op = 0; op < 4; op++) {
      long[] cold = new long[dpad];
      long[] hot = new long[dpad];
      long[] vcold = new long[dpad];
      long[] vhot = new long[dpad];
      for (int i = 0; i < dn; i++) {
        cold[i] = Double.doubleToRawLongBits(sd(op, da[i], db[i]));
      }
      vectorsD(op, da, db, vcold, ds);
      for (int round = 0; round < 30000; round++) {
        for (int i = 0; i < dn; i++) {
          hot[i] = Double.doubleToRawLongBits(sd(op, da[i], db[i]));
        }
        vectorsD(op, da, db, vhot, ds);
      }
      for (int i = 0; i < dn; i++) {
        boolean diff = cold[i] != hot[i] || hot[i] != vhot[i] || vcold[i] != vhot[i];
        diffs += diff ? 1 : 0;
        lines++;
        System.out.printf("double %s %016x %016x scalar %016x/%016x lanes %016x/%016x%s%n",
            OPS[op], dp[0][i], dp[1][i], cold[i], hot[i], vcold[i], vhot[i],
            diff ? " DIFF" : "");
      }
    }
    System.out.println(
        "cells " + lines + "  cells where cold, hot, scalar and lanes differ " + diffs);
  }

  static void vectorsF(int op, float[] a, float[] b, int[] out, VectorSpecies<Float> s) {
    for (int i = 0; i < a.length; i += s.length()) {
      FloatVector r = FloatVector.fromArray(s, a, i).lanewise(VOPS[op],
          FloatVector.fromArray(s, b, i));
      r.reinterpretAsInts().intoArray(out, i);
    }
  }

  static void vectorsD(int op, double[] a, double[] b, long[] out, VectorSpecies<Double> s) {
    for (int i = 0; i < a.length; i += s.length()) {
      DoubleVector r = DoubleVector.fromArray(s, a, i).lanewise(VOPS[op],
          DoubleVector.fromArray(s, b, i));
      r.reinterpretAsLongs().intoArray(out, i);
    }
  }

  /** Every ordered pair of NaNs, and each NaN beside each number, in both orders. */
  static int[][] pairs(int[] nans, int[] nums) {
    java.util.List<int[]> ps = new java.util.ArrayList<>();
    for (int x : nans) {
      for (int y : nans) {
        ps.add(new int[] {x, y});
      }
      for (int n : nums) {
        ps.add(new int[] {x, n});
        ps.add(new int[] {n, x});
      }
    }
    int[][] out = new int[2][ps.size()];
    for (int i = 0; i < ps.size(); i++) {
      out[0][i] = ps.get(i)[0];
      out[1][i] = ps.get(i)[1];
    }
    return out;
  }

  static long[][] pairsL(long[] nans, long[] nums) {
    java.util.List<long[]> ps = new java.util.ArrayList<>();
    for (long x : nans) {
      for (long y : nans) {
        ps.add(new long[] {x, y});
      }
      for (long n : nums) {
        ps.add(new long[] {x, n});
        ps.add(new long[] {n, x});
      }
    }
    long[][] out = new long[2][ps.size()];
    for (int i = 0; i < ps.size(); i++) {
      out[0][i] = ps.get(i)[0];
      out[1][i] = ps.get(i)[1];
    }
    return out;
  }
}
