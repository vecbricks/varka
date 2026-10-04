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
 * How does C2 lower a long->double->long conversion inside a Vector API loop, at a given
 * -XX:UseAVX level? Three loops, each hot enough to reach C2:
 *   convOnly : L2D then D2L, no arithmetic - isolates the casts
 *   divide   : (double) v / d, floor, back to long  - 2.19's "true division" form
 *   recip    : (double) v * (1.0/d), floor, back      - 2.19's reciprocal form
 * Output is a checksum so nothing is dead. The diagnosis is read from -XX:+PrintIntrinsics
 * (does the cast intrinsify at this width?) and, with hsdis, -XX:CompileCommand=print.
 *
 * How to run (m5/PLAN.md section 7, question 3):
 *   javac --add-modules jdk.incubator.vector -d /tmp/p dev/varka_canary/L2DProbe.java
 *   java --add-modules jdk.incubator.vector -XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics \
 *     [-XX:UseAVX=2] -cp /tmp/p L2DProbe 3000 2>&1 | grep 'VectorSupport::convert'
 * On 15 September 2026, Zen 5: the convert intrinsic inlines at the host width and emits
 * vcvtqq2pd / vcvttpd2qq; under -XX:UseAVX=2 it fails to inline every time and the loop
 * degrades to scalar vcvttsd2si / vcvtsi2sdq with reboxing. MagicProbe is the AVX2 answer.
 */
public class L2DProbe {
  static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
  static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;
  static final long D = 3_600_000_000_000L; // nanos per hour
  static final double RD = 1.0 / D;

  static long convOnly(long[] in, long[] out) {
    long acc = 0;
    for (int i = 0; i < in.length; i += LS.length()) {
      LongVector v = LongVector.fromArray(LS, in, i);
      DoubleVector d = (DoubleVector) v.convertShape(VectorOperators.L2D, DS, 0);
      LongVector back = (LongVector) d.convertShape(VectorOperators.D2L, LS, 0);
      back.intoArray(out, i);
      acc += out[i];
    }
    return acc;
  }
  static long divide(long[] in, long[] out) {
    long acc = 0;
    DoubleVector dd = DoubleVector.broadcast(DS, (double) D);
    for (int i = 0; i < in.length; i += LS.length()) {
      LongVector v = LongVector.fromArray(LS, in, i);
      DoubleVector q = ((DoubleVector) v.convertShape(VectorOperators.L2D, DS, 0)).div(dd);
      LongVector h = (LongVector) q.convertShape(VectorOperators.D2L, LS, 0); // D2L truncates
      h.intoArray(out, i);
      acc += out[i];
    }
    return acc;
  }
  static long recip(long[] in, long[] out) {
    long acc = 0;
    DoubleVector rd = DoubleVector.broadcast(DS, RD);
    for (int i = 0; i < in.length; i += LS.length()) {
      LongVector v = LongVector.fromArray(LS, in, i);
      DoubleVector q = ((DoubleVector) v.convertShape(VectorOperators.L2D, DS, 0)).mul(rd);
      LongVector h = (LongVector) q.convertShape(VectorOperators.D2L, LS, 0);
      h.intoArray(out, i);
      acc += out[i];
    }
    return acc;
  }
  public static void main(String[] a) {
    int n = 1 << 16;
    long[] in = new long[n], out = new long[n];
    java.util.Random r = new java.util.Random(7);
    for (int i = 0; i < n; i++) in[i] = (long) (r.nextDouble() * 86_399_999_999_999L);
    long s = 0;
    int rounds = a.length > 0 ? Integer.parseInt(a[0]) : 3000;
    for (int k = 0; k < rounds; k++) {
      s += convOnly(in, out);
      s += divide(in, out);
      s += recip(in, out);
    }
    // exactness cross-check of both division forms against integer division, whole array
    long bad = 0;
    for (int i = 0; i < n; i++) {
      long q1 = (long) ((double) in[i] / D), q2 = (long) ((double) in[i] * RD), q = in[i] / D;
      if (q1 != q || q2 != q) bad++;
    }
    System.out.println("species long=" + LS.length() + " lanes, double=" + DS.length()
        + " lanes; checksum=" + s + "; inexact quotients over " + n
        + " nanos-of-day values: " + bad);
  }
}
