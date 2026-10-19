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

import java.lang.foreign.*;
import java.nio.ByteOrder;
import jdk.incubator.vector.*;

/**
 * Five hand-written forms of `cast(i AS bigint) + l` over a batch, with nothing between the loads
 * and the store but what the emitted kernel does: the long lane alone, the int load under the
 * low-half mask, unmasked, the int-driven pair, and the bounded loop the emitter now takes.
 * `VARKA-28.md` 9.3 quotes the results. Usage: `java --add-modules jdk.incubator.vector
 * -Dn=<rows> Hand28.java <longs|masked|unmasked|pair|bounded>`.
 */
public class Hand28 {
  static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
  static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
  static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

  // Long-driven: int loaded into the low half under a mask, one I2L part 0.
  static void masked(MemorySegment a, MemorySegment b, MemorySegment o, int n) {
    int ll = LS.length();
    VectorMask<Integer> m = IS.indexInRange(0, ll);
    for (int i = 0; i < LS.loopBound(n); i += ll) {
      IntVector iv = IntVector.fromMemorySegment(IS, a, i * 4L, LE, m);
      LongVector w = (LongVector) iv.convertShape(VectorOperators.I2L, LS, 0);
      w.add(LongVector.fromMemorySegment(LS, b, i * 8L, LE)).intoMemorySegment(o, i * 8L, LE);
    }
  }

  // The same with an unmasked load, safe here only because the int column has slack.
  static void unmasked(MemorySegment a, MemorySegment b, MemorySegment o, int n) {
    int ll = LS.length();
    for (int i = 0; i < LS.loopBound(n); i += ll) {
      IntVector iv = IntVector.fromMemorySegment(IS, a, i * 4L, LE);
      LongVector w = (LongVector) iv.convertShape(VectorOperators.I2L, LS, 0);
      w.add(LongVector.fromMemorySegment(LS, b, i * 8L, LE)).intoMemorySegment(o, i * 8L, LE);
    }
  }

  // Int-driven pair: one int load per two long groups, I2L parts 0 and 1.
  static void pair(MemorySegment a, MemorySegment b, MemorySegment o, int n) {
    int ll = LS.length(), il = IS.length();
    for (int i = 0; i < IS.loopBound(n); i += il) {
      IntVector iv = IntVector.fromMemorySegment(IS, a, i * 4L, LE);
      for (int h = 0; h < 2; h++) {
        LongVector w = (LongVector) iv.convertShape(VectorOperators.I2L, LS, h);
        long off = (i + h * ll) * 8L;
        w.add(LongVector.fromMemorySegment(LS, b, off, LE)).intoMemorySegment(o, off, LE);
      }
    }
  }

  // Long-driven, the loop stopped where a whole int vector still fits, the rest masked.
  static void bounded(MemorySegment a, MemorySegment b, MemorySegment o, int n) {
    int ll = LS.length(), il = IS.length();
    int bound = n >= ll ? LS.loopBound(n - ll) : 0;
    int i = 0;
    for (; i < bound; i += ll) {
      IntVector iv = IntVector.fromMemorySegment(IS, a, i * 4L, LE);
      LongVector w = (LongVector) iv.convertShape(VectorOperators.I2L, LS, 0);
      w.add(LongVector.fromMemorySegment(LS, b, i * 8L, LE)).intoMemorySegment(o, i * 8L, LE);
    }
    for (; i < n; i += ll) {
      VectorMask<Integer> m = IS.indexInRange(0, Math.min(ll, n - i));
      VectorMask<Long> lm = LS.indexInRange(i, n);
      IntVector iv = IntVector.fromMemorySegment(IS, a, i * 4L, LE, m);
      LongVector w = (LongVector) iv.convertShape(VectorOperators.I2L, LS, 0);
      w.add(LongVector.fromMemorySegment(LS, b, i * 8L, LE, lm))
          .intoMemorySegment(o, i * 8L, LE, lm);
    }
  }

  static void longs(MemorySegment a, MemorySegment b, MemorySegment o, int n) {
    int ll = LS.length();
    for (int i = 0; i < LS.loopBound(n); i += ll) {
      LongVector.fromMemorySegment(LS, a, i * 8L, LE)
          .add(LongVector.fromMemorySegment(LS, b, i * 8L, LE)).intoMemorySegment(o, i * 8L, LE);
    }
  }

  public static void main(String[] args) {
    int n = Integer.getInteger("n", 16384);
    Arena ar = Arena.ofShared();
    // The int column has a row count of slack, which only `unmasked` reads: its last whole load
    // runs a long lane group past the batch.
    MemorySegment a = ar.allocate(n * 8L, 64), b = ar.allocate(n * 8L, 64),
        o = ar.allocate(n * 8L, 64);
    String which = args[0];
    for (int r = 0; r < 5; r++) {
      long t0 = System.nanoTime();
      int calls = (int) (327680000L / n);
      for (int c = 0; c < calls; c++) {
        switch (which) {
          case "masked" -> masked(a, b, o, n);
          case "unmasked" -> unmasked(a, b, o, n);
          case "pair" -> pair(a, b, o, n);
          case "bounded" -> bounded(a, b, o, n);
          default -> longs(a, b, o, n);
        }
      }
      long t1 = System.nanoTime();
      if (r == 4) {
        System.out.printf("%s %.0f Mrows/s%n", which, (double) calls * n / (t1 - t0) * 1000);
      }
    }
  }
}
