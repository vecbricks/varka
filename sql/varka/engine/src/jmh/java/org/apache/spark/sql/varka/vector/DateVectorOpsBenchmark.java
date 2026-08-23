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

package org.apache.spark.sql.varka.vector;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Kernel microbenchmark for {@link DateVectorOps} (Task 2 follow-up, Task 7): the three SIMD
 * kernels against scalar-loop baselines over int32 date buffers laid out exactly as Arrow
 * {@code DateDayVector} (int32 days since epoch + bit-packed validity, bit set = valid), over
 * ~10k and ~1M rows with null-free and mixed-null validity.
 *
 * <p>The buffers are native Panama segments with the same in-memory layout and kernel entry
 * points (raw addresses) the engine uses, so the measured hot loop is identical to the Arrow
 * path without dragging Arrow/Netty into the fork. Dst buffers are consumed via a
 * {@link Blackhole} to defeat dead-code elimination.
 *
 * <p>Run: {@code ./build/mvn -f sql/varka/engine/pom.xml test -Dvarka.jmh=true}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1) // Driven in-process (forks=0) by DateVectorOpsBenchmarkTest on the surefire JVM.
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@State(Scope.Benchmark)
public class DateVectorOpsBenchmark {

  // 32 is a batch smaller than a single 64-bit word of validity bitmap: the size range the
  // vector loop used to skip entirely, before it was bounded per lane group rather than per word.
  @Param({"32", "10000", "1000000"})
  public int size;

  @Param({"NULL_FREE", "MIXED_NULL"})
  public String nullPattern;

  private static final int DAYS = 5;

  private Arena arena;
  private MemorySegment dataA;
  private MemorySegment dataB;
  private MemorySegment validity;
  private MemorySegment dstData;
  private MemorySegment dstValidity;
  private int nullCount;

  @Setup(Level.Trial)
  public void setUp() {
    arena = Arena.ofShared();
    dataA = arena.allocate(size * 4L);
    dataB = arena.allocate(size * 4L);
    dstData = arena.allocate(size * 4L);
    dstValidity = arena.allocate((size + 7) / 8L);
    validity = arena.allocate((size + 7) / 8L);
    boolean withNulls = "MIXED_NULL".equals(nullPattern);
    int nulls = 0;
    for (int i = 0; i < size; i++) {
      dataA.set(ValueLayout.JAVA_INT, i * 4L, value(i));
      dataB.set(ValueLayout.JAVA_INT, i * 4L, value(i) - 1000);
      boolean valid = !withNulls || (i & 1) == 0;
      if (valid) {
        setBit(validity, i);
      } else {
        nulls++;
      }
    }
    nullCount = nulls;
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (arena != null) {
      arena.close();
    }
  }

  private int value(int i) {
    return i * 1234567 - 1000000000;
  }

  /** Mirror of the engine contract: a null-free source validity pointer may be 0L. */
  private long srcValidity() {
    return nullCount == 0 ? 0L : validity.address();
  }

  @Benchmark
  public void vectorAddDays(Blackhole bh) {
    DateVectorOps.vectorAddDays(dataA.address(), srcValidity(), nullCount,
        dstData.address(), dstValidity.address(), size, DAYS);
    bh.consume(dstData.get(ValueLayout.JAVA_INT, 0L));
    bh.consume(dstValidity.get(ValueLayout.JAVA_BYTE, 0L));
  }

  @Benchmark
  public void scalarAddDays(Blackhole bh) {
    for (int i = 0; i < size; i++) {
      if (nullCount == 0 || isBitSet(validity, i)) {
        dstData.set(ValueLayout.JAVA_INT, i * 4L,
            dataA.get(ValueLayout.JAVA_INT, i * 4L) + DAYS);
        setBit(dstValidity, i);
      }
    }
    bh.consume(dstData.get(ValueLayout.JAVA_INT, 0L));
    bh.consume(dstValidity.get(ValueLayout.JAVA_BYTE, 0L));
  }

  @Benchmark
  public void vectorSubDays(Blackhole bh) {
    DateVectorOps.vectorSubDays(dataA.address(), srcValidity(), nullCount,
        dstData.address(), dstValidity.address(), size, DAYS);
    bh.consume(dstData.get(ValueLayout.JAVA_INT, 0L));
    bh.consume(dstValidity.get(ValueLayout.JAVA_BYTE, 0L));
  }

  @Benchmark
  public void scalarSubDays(Blackhole bh) {
    for (int i = 0; i < size; i++) {
      if (nullCount == 0 || isBitSet(validity, i)) {
        dstData.set(ValueLayout.JAVA_INT, i * 4L,
            dataA.get(ValueLayout.JAVA_INT, i * 4L) - DAYS);
        setBit(dstValidity, i);
      }
    }
    bh.consume(dstData.get(ValueLayout.JAVA_INT, 0L));
    bh.consume(dstValidity.get(ValueLayout.JAVA_BYTE, 0L));
  }

  @Benchmark
  public void vectorDateDiff(Blackhole bh) {
    DateVectorOps.vectorDateDiff(dataA.address(), srcValidity(), nullCount,
        dataB.address(), srcValidity(), nullCount,
        dstData.address(), dstValidity.address(), size);
    bh.consume(dstData.get(ValueLayout.JAVA_INT, 0L));
    bh.consume(dstValidity.get(ValueLayout.JAVA_BYTE, 0L));
  }

  @Benchmark
  public void scalarDateDiff(Blackhole bh) {
    for (int i = 0; i < size; i++) {
      if (nullCount == 0 || isBitSet(validity, i)) {
        dstData.set(ValueLayout.JAVA_INT, i * 4L,
            dataA.get(ValueLayout.JAVA_INT, i * 4L) - dataB.get(ValueLayout.JAVA_INT, i * 4L));
        setBit(dstValidity, i);
      }
    }
    bh.consume(dstData.get(ValueLayout.JAVA_INT, 0L));
    bh.consume(dstValidity.get(ValueLayout.JAVA_BYTE, 0L));
  }

  private static boolean isBitSet(MemorySegment validity, int i) {
    return (validity.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
  }

  private static void setBit(MemorySegment validity, int i) {
    long off = i / 8L;
    validity.set(ValueLayout.JAVA_BYTE, off,
        (byte) (validity.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))));
  }
}