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
// Row 604's step-2 probe (VARKA-604.md 9.2): the committed VarkaDecimalRemainderOps, driven with
// no compile commands after an optional polluter, with the template warm-up or without it. Not
// Varka code; run-step2.sh compiles and runs it against catalyst's test classpath.
package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.util.Random;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** VARKA-604 step 2 on the laptop: the committed helper, no compile commands. */
public class Probe604b {
  static final int N = 4096;

  @SuppressWarnings("unchecked")
  static void mixed(VectorSpecies<?> s, MemorySegment a, MemorySegment b, MemorySegment c, int n) {
    int step = s.length();
    long bytes = s.elementSize() / 8;
    for (int i = 0; i + step <= n / 2; i += step) {
      var x = s.fromMemorySegment(a, i * bytes, ByteOrder.LITTLE_ENDIAN);
      var y = (Vector<Object>) s.fromMemorySegment(b, i * bytes, ByteOrder.LITTLE_ENDIAN);
      ((Vector<Object>) x).lanewise(VectorOperators.ADD, y).lanewise(VectorOperators.MAX, y)
          .intoMemorySegment(c, i * bytes, ByteOrder.LITTLE_ENDIAN);
    }
  }

  public static void main(String[] args) {
    String mode = args[0];
    boolean pmod = args.length > 1 && args[1].equals("pmod");
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    boolean late = mode.contains("late");
    if (mode.contains("warm") && !late) {
      System.out.println("template warm-up ms " + VarkaVectorTemplateWarmup.ensure() / 1_000_000);
    }
    try (Arena arena = Arena.ofConfined()) {
      if (mode.contains("mixed")) {
        var a = arena.allocate(N * 8L, 64);
        var b = arena.allocate(N * 8L, 64);
        var c = arena.allocate(N * 8L, 64);
        VectorSpecies<?>[] sp = {IntVector.SPECIES_64, IntVector.SPECIES_128,
            IntVector.SPECIES_256, LongVector.SPECIES_128, LongVector.SPECIES_256,
            DoubleVector.SPECIES_128, DoubleVector.SPECIES_256};
        for (int r = 0; r < 20_000; r++) {
          for (var s : sp) {
            mixed(s, a, b, c, N);
          }
        }
      }
      if (late) {
        System.out.println("template warm-up ms " + VarkaVectorTemplateWarmup.ensure() / 1_000_000);
      }
      var x = arena.allocate(N * 16L, 64);
      var y = arena.allocate(N * 16L, 64);
      var out = arena.allocate(N * 16L, 64);
      var r = new Random(604);
      for (int i = 0; i < N; i++) {
        x.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, 2 * i, r.nextLong());
        x.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, 2 * i + 1, r.nextLong() >> 20);
        long ylo = i % 32 == 1 ? 0 : r.nextLong();
        y.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, 2 * i, ylo);
        y.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, 2 * i + 1,
            i % 32 == 1 ? 0 : r.nextLong() >> (21 + r.nextInt(42)));
      }
      long[] words = new long[N / 64];
      long xa = x.address();
      long ya = y.address();
      long oa = out.address();
      for (int c = 0; c < 20_000; c++) {
        VarkaDecimalRemainderOps.remainder(xa, ya, oa, words, 64, pmod);
      }
      long end = System.nanoTime() + 8_000_000_000L;
      while (System.nanoTime() < end) {
        VarkaDecimalRemainderOps.remainder(xa, ya, oa, words, N, pmod);
      }
      int reps = 256;
      long before = threads.getCurrentThreadAllocatedBytes();
      for (int k = 0; k < reps; k++) {
        VarkaDecimalRemainderOps.remainder(xa, ya, oa, words, N, pmod);
      }
      long bytes = threads.getCurrentThreadAllocatedBytes() - before;
      System.out.printf("mode=%s op=%s L=%d bytes/row=%.3f bytes/call=%.1f%n", mode,
          pmod ? "pmod" : "rem", LongVector.SPECIES_PREFERRED.length(),
          bytes / (double) (N * reps), bytes / (double) reps);
    }
  }
}
