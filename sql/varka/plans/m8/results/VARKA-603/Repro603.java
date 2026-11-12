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
// Row 603 diagnosis: a JDK-only model of the 128-bit byte store of a kernel driven at four int
// lanes (the shape VarkaBodyEmitter.emitNarrowTypeStore emits for shapes h and i), beside the
// store forms a fix could use. Not Varka code; run with --add-modules jdk.incubator.vector.
//
//   masked : per int group, convertShape(I2B, ByteVector.SPECIES_64, 0), then
//            intoMemorySegment(..., SPECIES_64.indexInRange(0, 4)) -- what the emitter does now
//   whole  : the iteration's two int groups combined into one 8-lane byte vector, stored whole
//   over   : per int group, the 8-lane byte vector stored whole (the upper four lanes are written
//            over by the next group; the loop stops one group early so nothing passes the end)
//   native : the NATIVE arm's byte store, ByteVector.SPECIES_128 loaded, negated, stored whole
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public class Repro603 {
  static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;
  static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;
  static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;
  static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

  // The checked multiply at the int lane: product and a range flag, as shapes h and i compute.
  static int masked(MemorySegment a, MemorySegment b, MemorySegment out, int n) {
    int lanes = I128.length();
    int bound = n & -(2 * lanes);
    int status = 0;
    for (int i = 0; i < bound; ) {
      ByteVector x = ByteVector.fromMemorySegment(B64, a, i, LE);
      ByteVector y = ByteVector.fromMemorySegment(B64, b, i, LE);
      for (int g = 0; g < 2; g++) {
        IntVector xi = (IntVector) x.convertShape(VectorOperators.B2I, I128, g);
        IntVector yi = (IntVector) y.convertShape(VectorOperators.B2I, I128, g);
        IntVector p = xi.mul(yi);
        VectorMask<Integer> bad = p.compare(VectorOperators.LT, -128)
            .or(p.compare(VectorOperators.GT, 127));
        status |= bad.anyTrue() ? 1 : 0;
        ByteVector s = (ByteVector) p.convertShape(VectorOperators.I2B, B64, 0);
        s.intoMemorySegment(out, i, LE, B64.indexInRange(0, lanes));
        i += lanes;
      }
    }
    return status;
  }

  static int whole(MemorySegment a, MemorySegment b, MemorySegment out, int n) {
    int lanes = I128.length();
    int bound = n & -(2 * lanes);
    int status = 0;
    for (int i = 0; i < bound; i += 2 * lanes) {
      ByteVector x = ByteVector.fromMemorySegment(B64, a, i, LE);
      ByteVector y = ByteVector.fromMemorySegment(B64, b, i, LE);
      IntVector p0 = ((IntVector) x.convertShape(VectorOperators.B2I, I128, 0))
          .mul((IntVector) y.convertShape(VectorOperators.B2I, I128, 0));
      IntVector p1 = ((IntVector) x.convertShape(VectorOperators.B2I, I128, 1))
          .mul((IntVector) y.convertShape(VectorOperators.B2I, I128, 1));
      VectorMask<Integer> bad = p0.compare(VectorOperators.LT, -128)
          .or(p0.compare(VectorOperators.GT, 127))
          .or(p1.compare(VectorOperators.LT, -128))
          .or(p1.compare(VectorOperators.GT, 127));
      status |= bad.anyTrue() ? 1 : 0;
      // part 0 fills lanes 0..3, part -1 fills lanes 4..7; the other lanes are zero
      ByteVector s = ((ByteVector) p0.convertShape(VectorOperators.I2B, B64, 0))
          .or((ByteVector) p1.convertShape(VectorOperators.I2B, B64, -1));
      s.intoMemorySegment(out, i, LE);
    }
    return status;
  }

  static int over(MemorySegment a, MemorySegment b, MemorySegment out, int n) {
    int lanes = I128.length();
    // one group early, so the last group's whole 8-byte store stays inside the segment
    int bound = Math.max(0, (n - lanes)) & -(2 * lanes);
    int status = 0;
    for (int i = 0; i < bound; ) {
      ByteVector x = ByteVector.fromMemorySegment(B64, a, i, LE);
      ByteVector y = ByteVector.fromMemorySegment(B64, b, i, LE);
      for (int g = 0; g < 2; g++) {
        IntVector xi = (IntVector) x.convertShape(VectorOperators.B2I, I128, g);
        IntVector yi = (IntVector) y.convertShape(VectorOperators.B2I, I128, g);
        IntVector p = xi.mul(yi);
        VectorMask<Integer> bad = p.compare(VectorOperators.LT, -128)
            .or(p.compare(VectorOperators.GT, 127));
        status |= bad.anyTrue() ? 1 : 0;
        ByteVector s = (ByteVector) p.convertShape(VectorOperators.I2B, B64, 0);
        s.intoMemorySegment(out, i, LE);
        i += lanes;
      }
    }
    return status;
  }

  static int nativeNeg(MemorySegment a, MemorySegment b, MemorySegment out, int n) {
    int bound = B128.loopBound(n);
    for (int i = 0; i < bound; i += B128.length()) {
      ByteVector.fromMemorySegment(B128, a, i, LE).neg().intoMemorySegment(out, i, LE);
    }
    return 0;
  }

  interface K { int run(MemorySegment a, MemorySegment b, MemorySegment out, int n); }

  public static void main(String[] args) {
    String which = args.length > 0 ? args[0] : "all";
    int n = 16384;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment a = arena.allocate(n, 64);
      MemorySegment b = arena.allocate(n, 64);
      MemorySegment out = arena.allocate(n, 64);
      for (int i = 0; i < n; i++) {
        a.set(ValueLayout.JAVA_BYTE, i, (byte) ((i * 7) % 23 - 11));
        b.set(ValueLayout.JAVA_BYTE, i, (byte) ((i * 5) % 23 - 11));
      }
      String[] names = {"masked", "whole", "over", "native"};
      K[] ks = {Repro603::masked, Repro603::whole, Repro603::over, Repro603::nativeNeg};
      for (int k = 0; k < ks.length; k++) {
        if (!which.equals("all") && !which.equals(names[k])) {
          continue;
        }
        long sink = 0;
        for (int w = 0; w < 20000; w++) {
          sink += ks[k].run(a, b, out, n);
        }
        var mx = (com.sun.management.ThreadMXBean)
            java.lang.management.ManagementFactory.getThreadMXBean();
        long alloc0 = mx.getCurrentThreadAllocatedBytes();
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 5; rep++) {
          long t0 = System.nanoTime();
          int iters = 2000;
          for (int w = 0; w < iters; w++) {
            sink += ks[k].run(a, b, out, n);
          }
          best = Math.min(best, (System.nanoTime() - t0) / iters);
        }
        long allocPerRow = (mx.getCurrentThreadAllocatedBytes() - alloc0) / (5L * 2000 * n);
        System.out.printf("ALLOC %-7s %d bytes/row%n", names[k], allocPerRow);
        // check the product for the multiply kernels
        boolean ok = true;
        if (k < 3) {
          int checkTo = k == 2 ? (n - 4) & -8 : n;
          for (int i = 0; i < checkTo; i++) {
            byte e = (byte) (a.get(ValueLayout.JAVA_BYTE, i) * b.get(ValueLayout.JAVA_BYTE, i));
            if (out.get(ValueLayout.JAVA_BYTE, i) != e) {
              ok = false;
              break;
            }
          }
        }
        System.out.printf("REPRO %-7s %8.1f M rows/s  ok=%b sink=%d%n", names[k],
            n * 1000.0 / best, ok, sink);
      }
    }
  }
}
