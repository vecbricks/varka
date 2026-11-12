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
// Row 603: which masked stores C2 intrinsifies at -XX:UseAVX=2 -XX:MaxVectorSize=16, by element
// type, each in its own JVM (argument: byte64, short64, int128, long128). Not Varka code; run
// with --add-modules jdk.incubator.vector. Committed as run, re-wrapped to 100 columns and with
// its wildcard import spelled out; nothing else changed.
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;

public class MaskedStores603 {
  static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
  static void byte64(MemorySegment s, int n, int k) {
    var sp = ByteVector.SPECIES_64;
    var v = ByteVector.broadcast(sp, (byte) 3);
    for (int i = 0; i + 8 <= n; i += 4) {
      v.intoMemorySegment(s, i, LE, sp.indexInRange(0, k));
    }
  }
  static void short64(MemorySegment s, int n, int k) {
    var sp = ShortVector.SPECIES_64;
    var v = ShortVector.broadcast(sp, (short) 3);
    for (int i = 0; i + 4 <= n / 2; i += 2) {
      v.intoMemorySegment(s, i * 2L, LE, sp.indexInRange(0, k));
    }
  }
  static void int128(MemorySegment s, int n, int k) {
    var sp = IntVector.SPECIES_128;
    var v = IntVector.broadcast(sp, 3);
    for (int i = 0; i + 4 <= n / 4; i += 2) {
      v.intoMemorySegment(s, i * 4L, LE, sp.indexInRange(0, k));
    }
  }
  static void long128(MemorySegment s, int n, int k) {
    var sp = LongVector.SPECIES_128;
    var v = LongVector.broadcast(sp, 3L);
    for (int i = 0; i + 2 <= n / 8; i += 1) {
      v.intoMemorySegment(s, i * 8L, LE, sp.indexInRange(0, k));
    }
  }
  public static void main(String[] a) {
    int n = 4096;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment s = arena.allocate(n, 64);
      var mx = (com.sun.management.ThreadMXBean)
          java.lang.management.ManagementFactory.getThreadMXBean();
      Runnable r = switch (a[0]) {
        case "byte64" -> () -> byte64(s, n, 4);
        case "short64" -> () -> short64(s, n, 2);
        case "int128" -> () -> int128(s, n, 2);
        default -> () -> long128(s, n, 1);
      };
      for (int w = 0; w < 30000; w++) r.run();
      long a0 = mx.getCurrentThreadAllocatedBytes();
      for (int w = 0; w < 1000; w++) r.run();
      long perCall = (mx.getCurrentThreadAllocatedBytes() - a0) / 1000;
      System.out.println("MASKED " + a[0] + " alloc bytes/call " + perCall);
    }
  }
}
