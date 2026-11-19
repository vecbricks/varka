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
// Row 604's admission probe (VARKA-604.md 2): split F of VarkaDecimalRemainderSpikeBenchmark
// (loopRemainderFS) fed by a transpose of Arrow-layout slots into reused split blocks, in methods
// of their own, after optional polluting vector code and after the engine's template warm-up.
// Not Varka code. It compiles beside a copy of the spike benchmark whose harness methods are
// stubbed (results/VARKA-604-admission.txt says how) and runs from run.sh.
package org.apache.spark.sql;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.WarmBridge;

/** Row 604's admission probe: split F behind an out-of-line transpose with reused blocks. */
public class Probe604 {
  static final ValueLayout.OfLong WORD =
      ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
  static final int N = 4096;

  // Plain add loops, the 10.4 "other vector code".
  static void addInt(MemorySegment a, MemorySegment b, MemorySegment c, int n) {
    var s = IntVector.SPECIES_PREFERRED;
    for (int i = 0; i < n; i += s.length()) {
      IntVector.fromMemorySegment(s, a, i * 4L, ByteOrder.LITTLE_ENDIAN)
          .add(IntVector.fromMemorySegment(s, b, i * 4L, ByteOrder.LITTLE_ENDIAN))
          .intoMemorySegment(c, i * 4L, ByteOrder.LITTLE_ENDIAN);
    }
  }
  static void addLong(MemorySegment a, MemorySegment b, MemorySegment c, int n) {
    var s = LongVector.SPECIES_PREFERRED;
    for (int i = 0; i < n; i += s.length()) {
      LongVector.fromMemorySegment(s, a, i * 8L, ByteOrder.LITTLE_ENDIAN)
          .add(LongVector.fromMemorySegment(s, b, i * 8L, ByteOrder.LITTLE_ENDIAN))
          .intoMemorySegment(c, i * 8L, ByteOrder.LITTLE_ENDIAN);
    }
  }
  static void addDouble(MemorySegment a, MemorySegment b, MemorySegment c, int n) {
    var s = DoubleVector.SPECIES_PREFERRED;
    for (int i = 0; i < n; i += s.length()) {
      DoubleVector.fromMemorySegment(s, a, i * 8L, ByteOrder.LITTLE_ENDIAN)
          .add(DoubleVector.fromMemorySegment(s, b, i * 8L, ByteOrder.LITTLE_ENDIAN))
          .intoMemorySegment(c, i * 8L, ByteOrder.LITTLE_ENDIAN);
    }
  }

  @SuppressWarnings("unchecked")
  static void mixed(jdk.incubator.vector.VectorSpecies<?> s, MemorySegment a, MemorySegment b,
      MemorySegment c, int n) {
    int step = s.length();
    long bytes = s.elementSize() / 8;
    for (int i = 0; i + step <= n / 2; i += step) {
      var x = s.fromMemorySegment(a, i * bytes, ByteOrder.LITTLE_ENDIAN);
      var y = s.fromMemorySegment(b, i * bytes, ByteOrder.LITTLE_ENDIAN);
      var vy = (jdk.incubator.vector.Vector<Object>) y;
      ((jdk.incubator.vector.Vector<Object>) x)
          .lanewise(jdk.incubator.vector.VectorOperators.ADD, vy)
          .lanewise(jdk.incubator.vector.VectorOperators.MAX, vy)
          .intoMemorySegment(c, i * bytes, ByteOrder.LITTLE_ENDIAN);
    }
  }

  // The reused split blocks: row 604's "reuses its block buffer".
  static final int B = Integer.getInteger("p604.block", 64);
  static final Arena BLOCKS = Arena.ofAuto();
  static final MemorySegment SX = BLOCKS.allocate(B * 16L, 64);
  static final MemorySegment SY = BLOCKS.allocate(B * 16L, 64);
  static final MemorySegment SO = BLOCKS.allocate(B * 16L, 64);
  static final long[] BW = new long[B / 64];
  static final long[][] SUBW =
      new long[B / Integer.getInteger("p604.sub", B)][Integer.getInteger("p604.sub", B) / 64];

  // -Dp604.sub=<rows> splits a block into sub-blocks of that many rows, each in the split
  // layout of its own, and calls the loop once a sub-block: the block's size and the loop's
  // rows a call then differ.
  static final int SUB = Integer.getInteger("p604.sub", B);

  static void tIn(MemorySegment from, MemorySegment to, int row) {
    for (int k = 0; k < B; k++) {
      long slot = (row + k) * 16L;
      long base = (k / SUB) * SUB * 16L;
      int j = k % SUB;
      to.set(WORD, base + j * 8L, from.get(WORD, slot));
      to.set(WORD, base + SUB * 8L + j * 8L, from.get(WORD, slot + 8));
    }
  }
  static void tOut(MemorySegment out, int row, long[] words) {
    for (int k = 0; k < B; k++) {
      long slot = (row + k) * 16L;
      long base = (k / SUB) * SUB * 16L;
      int j = k % SUB;
      out.set(WORD, slot, SO.get(WORD, base + j * 8L));
      out.set(WORD, slot + 8, SO.get(WORD, base + SUB * 8L + j * 8L));
    }
    for (int w = 0; w < B / 64; w++) {
      words[(row >>> 6) + w] = BW[w];
    }
  }
  static void splitF(MemorySegment x, MemorySegment y, MemorySegment out, long[] words, int n) {
    for (int i = 0; i < n; i += B) {
      tIn(x, SX, i);
      tIn(y, SY, i);
      if (SUB == B) {
        // No slices: a slice is an object, about 40 bytes, which the reading would count.
        java.util.Arrays.fill(BW, 0);
        VarkaDecimalRemainderSpikeBenchmark.loopRemainderFS(SX, SY, SO, BW, B);
        tOut(out, i, words);
        continue;
      }
      for (int k = 0; k < B; k += SUB) {
        long at = k * 16L;
        long len = SUB * 16L;
        long[] sw = SUBW[k / SUB];
        java.util.Arrays.fill(sw, 0);
        VarkaDecimalRemainderSpikeBenchmark.loopRemainderFS(SX.asSlice(at, len),
            SY.asSlice(at, len), SO.asSlice(at, len), sw, SUB);
        System.arraycopy(sw, 0, BW, k / 64, SUB / 64);
      }
      tOut(out, i, words);
    }
  }

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (mode.contains("warm")) {
      System.out.println("template warm-up ms " + WarmBridge.warm() / 1_000_000);
    }
    try (Arena arena = Arena.ofConfined()) {
      if (mode.contains("adds")) {
        var a = arena.allocate(N * 8L, 64);
        var b = arena.allocate(N * 8L, 64);
        var c = arena.allocate(N * 8L, 64);
        for (int r = 0; r < 30_000; r++) {
          addInt(a, b, c, N);
          addLong(a, b, c, N);
          addDouble(a, b, c, N);
        }
      }
      if (mode.contains("mixed")) {
        var a = arena.allocate(N * 8L, 64);
        var b = arena.allocate(N * 8L, 64);
        var c = arena.allocate(N * 8L, 64);
        jdk.incubator.vector.VectorSpecies<?>[] sp = {
            IntVector.SPECIES_64, IntVector.SPECIES_128, IntVector.SPECIES_256,
            LongVector.SPECIES_128, LongVector.SPECIES_256,
            DoubleVector.SPECIES_128, DoubleVector.SPECIES_256};
        for (int r = 0; r < 20_000; r++) {
          for (var s : sp) {
            mixed(s, a, b, c, N);
          }
        }
      }
      if (mode.contains("arms")) {
        var d0 = VarkaDecimalRemainderSpikeBenchmark.data(arena, N);
        var o0 = arena.allocate(N * 16L, 64);
        long[] w0 = new long[N / 64];
        for (int r = 0; r < 3_000; r++) {
          VarkaDecimalRemainderSpikeBenchmark.loopRemainderFI(d0.x(), d0.y(), o0, w0, N);
          VarkaDecimalRemainderSpikeBenchmark.loopRemainderEI(d0.x(), d0.y(), o0, w0, N);
          VarkaDecimalRemainderSpikeBenchmark.loopRemainderFT(d0.x(), d0.y(), o0, w0, N);
        }
      }
      var d = VarkaDecimalRemainderSpikeBenchmark.data(arena, N);
      var e = d.expected().get(VarkaDecimalRemainderSpikeBenchmark.Op.REMAINDER);
      var out = arena.allocate(N * 16L, 64);
      long[] words = new long[N / 64];
      // Prime to C2 at its invocation count, as the spike's harness does.
      for (int c = 0; c < 20_000; c++) {
        splitF(d.x(), d.y(), out, words, 64);
      }
      long end = System.nanoTime() + 8_000_000_000L;
      while (System.nanoTime() < end) {
        splitF(d.x(), d.y(), out, words, N);
      }
      int reps = 256;
      long before = threads.getCurrentThreadAllocatedBytes();
      long t0 = System.nanoTime();
      for (int r = 0; r < reps; r++) {
        splitF(d.x(), d.y(), out, words, N);
      }
      long ns = System.nanoTime() - t0;
      long bytes = threads.getCurrentThreadAllocatedBytes() - before;
      boolean ok = java.util.Arrays.equals(words, e.words())
          && out.mismatch(e.out()) == -1;
      System.out.printf("mode=%s block=%d L=%d bytes/row=%.2f ns/row=%.1f exact=%s%n", mode, B,
          LongVector.SPECIES_PREFERRED.length(), bytes / (double) (N * reps),
          ns / (double) (N * reps), ok);
    }
  }
}
