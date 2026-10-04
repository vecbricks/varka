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
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * An open question (`m4/PLAN.md` section 2.2): does an outer-loop
 * unroll factor above 1 pay on a compute-bound chain, or does C2 plus the out-of-order engine
 * already collect the available overlap on a single-lane-group body?
 *
 * <p>Each {@code chainD<depth>K<k>} method is the straight-line shape a real emitted kernel would
 * carry for a fixed-depth alternating add/sub chain (the {@code AddDays}/{@code SubDays} shape
 * {@code VarkaEmitterParityBenchmark} already prices) at outer-loop unroll factor K: K independent
 * lane groups, each running the identical depth-D dependency chain, with every op of lane group 0
 * interleaved against the matching op of lane groups 1..K-1 in source order - the bytecode shape a
 * real unrolled emission would produce, not K sequential single-group passes back to back.
 * Broadcasts are emitted at each use rather than hoisted (the `SKILLS.md` recommendation once a
 * chain is not tiny), so this isolates K from the separately-settled broadcast question.
 *
 * <p>Depths 8 and 20 are the two named candidates in section 2.2: the depth-8 chain and a
 * dayofweek-shaped 20-op fold (this file uses a plain alternating add/sub chain of that length,
 * not the actual mod-7 magic multiply, to isolate the unroll question from that algorithm choice).
 *
 * <p>Run: {@code ./build/mvn -f sql/varka/engine/pom.xml test -Dvarka.jmh=true}. As with
 * {@link DateVectorOpsBenchmark}, JMH is driven from a test because maven-jmh-plugin does not
 * resolve on this environment's Maven mirror, and forks one JVM per benchmark.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
// One JVM per benchmark, forked by VarkaUnrollFactorBenchmarkTest with the surefire argLine.
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
public class VarkaUnrollFactorBenchmark {

  /** One Spark {@code COLUMN_BATCH_SIZE}: the working set every real Varka kernel runs at. */
  static final int N = 4096;

  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
  private static final int LANES = SPECIES.length();
  private static final ByteOrder ORDER = ByteOrder.nativeOrder();

  // 20 literals cover both the depth-8 and depth-20 chains; values are arbitrary and distinct so
  // a transposition bug in the interleaving would change the result.
  private static final int[] LIT = {
      3, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79
  };

  private Arena arena;
  private MemorySegment src;
  private MemorySegment dst;

  @Setup(Level.Trial)
  public void setUp() {
    arena = Arena.ofShared();
    src = arena.allocate((long) N * 4, 64);
    dst = arena.allocate((long) N * 4, 64);
    Random r = new Random(42);
    for (int i = 0; i < N; i++) {
      src.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt(1_000_000) - 500_000);
    }
    checkCorrectness();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (arena != null) {
      arena.close();
    }
  }

  private void checkCorrectness() {
    chainD8K1(null);
    verify(8);
    chainD8K2(null);
    verify(8);
    chainD8K4(null);
    verify(8);
    chainD20K1(null);
    verify(20);
    chainD20K2(null);
    verify(20);
    chainD20K4(null);
    verify(20);
  }

  private void verify(int depth) {
    for (int i = 0; i < N; i++) {
      int expected = src.getAtIndex(ValueLayout.JAVA_INT, i);
      for (int op = 0; op < depth; op++) {
        expected = (op % 2 == 0) ? expected + LIT[op] : expected - LIT[op];
      }
      int got = dst.getAtIndex(ValueLayout.JAVA_INT, i);
      if (got != expected) {
        throw new AssertionError("depth " + depth + " mismatch at " + i
            + ": expected=" + expected + " got=" + got);
      }
    }
  }

  private static IntVector applyChain8(IntVector y) {
    y = y.add(IntVector.broadcast(SPECIES, LIT[0]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[1]));
    y = y.add(IntVector.broadcast(SPECIES, LIT[2]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[3]));
    y = y.add(IntVector.broadcast(SPECIES, LIT[4]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[5]));
    y = y.add(IntVector.broadcast(SPECIES, LIT[6]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[7]));
    return y;
  }

  private static IntVector applyChain20(IntVector y) {
    y = applyChain8(y);
    y = y.add(IntVector.broadcast(SPECIES, LIT[8]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[9]));
    y = y.add(IntVector.broadcast(SPECIES, LIT[10]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[11]));
    y = y.add(IntVector.broadcast(SPECIES, LIT[12]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[13]));
    y = y.add(IntVector.broadcast(SPECIES, LIT[14]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[15]));
    y = y.add(IntVector.broadcast(SPECIES, LIT[16]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[17]));
    y = y.add(IntVector.broadcast(SPECIES, LIT[18]));
    y = y.sub(IntVector.broadcast(SPECIES, LIT[19]));
    return y;
  }

  // =====================================================================================
  // Depth 8, K = 1, 2, 4
  // =====================================================================================

  @Benchmark
  public void chainD8K1(Blackhole bh) {
    int bound = SPECIES.loopBound(N);
    for (int i = 0; i < bound; i += LANES) {
      long off = (long) i * 4;
      IntVector y0 = IntVector.fromMemorySegment(SPECIES, src, off, ORDER);
      y0 = applyChain8(y0);
      y0.intoMemorySegment(dst, off, ORDER);
    }
    consume(bh);
  }

  @Benchmark
  public void chainD8K2(Blackhole bh) {
    int step = LANES * 2;
    int bound = N - (N % step);
    for (int i = 0; i < bound; i += step) {
      long off0 = (long) i * 4;
      long off1 = (long) (i + LANES) * 4;
      IntVector y0 = IntVector.fromMemorySegment(SPECIES, src, off0, ORDER);
      IntVector y1 = IntVector.fromMemorySegment(SPECIES, src, off1, ORDER);
      IntVector l0 = IntVector.broadcast(SPECIES, LIT[0]);
      y0 = y0.add(l0); y1 = y1.add(l0);
      IntVector l1 = IntVector.broadcast(SPECIES, LIT[1]);
      y0 = y0.sub(l1); y1 = y1.sub(l1);
      IntVector l2 = IntVector.broadcast(SPECIES, LIT[2]);
      y0 = y0.add(l2); y1 = y1.add(l2);
      IntVector l3 = IntVector.broadcast(SPECIES, LIT[3]);
      y0 = y0.sub(l3); y1 = y1.sub(l3);
      IntVector l4 = IntVector.broadcast(SPECIES, LIT[4]);
      y0 = y0.add(l4); y1 = y1.add(l4);
      IntVector l5 = IntVector.broadcast(SPECIES, LIT[5]);
      y0 = y0.sub(l5); y1 = y1.sub(l5);
      IntVector l6 = IntVector.broadcast(SPECIES, LIT[6]);
      y0 = y0.add(l6); y1 = y1.add(l6);
      IntVector l7 = IntVector.broadcast(SPECIES, LIT[7]);
      y0 = y0.sub(l7); y1 = y1.sub(l7);
      y0.intoMemorySegment(dst, off0, ORDER);
      y1.intoMemorySegment(dst, off1, ORDER);
    }
    consume(bh);
  }

  @Benchmark
  public void chainD8K4(Blackhole bh) {
    int step = LANES * 4;
    int bound = N - (N % step);
    for (int i = 0; i < bound; i += step) {
      long off0 = (long) i * 4;
      long off1 = (long) (i + LANES) * 4;
      long off2 = (long) (i + 2 * LANES) * 4;
      long off3 = (long) (i + 3 * LANES) * 4;
      IntVector y0 = IntVector.fromMemorySegment(SPECIES, src, off0, ORDER);
      IntVector y1 = IntVector.fromMemorySegment(SPECIES, src, off1, ORDER);
      IntVector y2 = IntVector.fromMemorySegment(SPECIES, src, off2, ORDER);
      IntVector y3 = IntVector.fromMemorySegment(SPECIES, src, off3, ORDER);
      IntVector l0 = IntVector.broadcast(SPECIES, LIT[0]);
      y0 = y0.add(l0); y1 = y1.add(l0); y2 = y2.add(l0); y3 = y3.add(l0);
      IntVector l1 = IntVector.broadcast(SPECIES, LIT[1]);
      y0 = y0.sub(l1); y1 = y1.sub(l1); y2 = y2.sub(l1); y3 = y3.sub(l1);
      IntVector l2 = IntVector.broadcast(SPECIES, LIT[2]);
      y0 = y0.add(l2); y1 = y1.add(l2); y2 = y2.add(l2); y3 = y3.add(l2);
      IntVector l3 = IntVector.broadcast(SPECIES, LIT[3]);
      y0 = y0.sub(l3); y1 = y1.sub(l3); y2 = y2.sub(l3); y3 = y3.sub(l3);
      IntVector l4 = IntVector.broadcast(SPECIES, LIT[4]);
      y0 = y0.add(l4); y1 = y1.add(l4); y2 = y2.add(l4); y3 = y3.add(l4);
      IntVector l5 = IntVector.broadcast(SPECIES, LIT[5]);
      y0 = y0.sub(l5); y1 = y1.sub(l5); y2 = y2.sub(l5); y3 = y3.sub(l5);
      IntVector l6 = IntVector.broadcast(SPECIES, LIT[6]);
      y0 = y0.add(l6); y1 = y1.add(l6); y2 = y2.add(l6); y3 = y3.add(l6);
      IntVector l7 = IntVector.broadcast(SPECIES, LIT[7]);
      y0 = y0.sub(l7); y1 = y1.sub(l7); y2 = y2.sub(l7); y3 = y3.sub(l7);
      y0.intoMemorySegment(dst, off0, ORDER);
      y1.intoMemorySegment(dst, off1, ORDER);
      y2.intoMemorySegment(dst, off2, ORDER);
      y3.intoMemorySegment(dst, off3, ORDER);
    }
    consume(bh);
  }

  // =====================================================================================
  // Depth 20, K = 1, 2, 4
  // =====================================================================================

  @Benchmark
  public void chainD20K1(Blackhole bh) {
    int bound = SPECIES.loopBound(N);
    for (int i = 0; i < bound; i += LANES) {
      long off = (long) i * 4;
      IntVector y0 = IntVector.fromMemorySegment(SPECIES, src, off, ORDER);
      y0 = applyChain20(y0);
      y0.intoMemorySegment(dst, off, ORDER);
    }
    consume(bh);
  }

  @Benchmark
  public void chainD20K2(Blackhole bh) {
    int step = LANES * 2;
    int bound = N - (N % step);
    for (int i = 0; i < bound; i += step) {
      long off0 = (long) i * 4;
      long off1 = (long) (i + LANES) * 4;
      IntVector y0 = IntVector.fromMemorySegment(SPECIES, src, off0, ORDER);
      IntVector y1 = IntVector.fromMemorySegment(SPECIES, src, off1, ORDER);
      IntVector l0 = IntVector.broadcast(SPECIES, LIT[0]);
      y0 = y0.add(l0); y1 = y1.add(l0);
      IntVector l1 = IntVector.broadcast(SPECIES, LIT[1]);
      y0 = y0.sub(l1); y1 = y1.sub(l1);
      IntVector l2 = IntVector.broadcast(SPECIES, LIT[2]);
      y0 = y0.add(l2); y1 = y1.add(l2);
      IntVector l3 = IntVector.broadcast(SPECIES, LIT[3]);
      y0 = y0.sub(l3); y1 = y1.sub(l3);
      IntVector l4 = IntVector.broadcast(SPECIES, LIT[4]);
      y0 = y0.add(l4); y1 = y1.add(l4);
      IntVector l5 = IntVector.broadcast(SPECIES, LIT[5]);
      y0 = y0.sub(l5); y1 = y1.sub(l5);
      IntVector l6 = IntVector.broadcast(SPECIES, LIT[6]);
      y0 = y0.add(l6); y1 = y1.add(l6);
      IntVector l7 = IntVector.broadcast(SPECIES, LIT[7]);
      y0 = y0.sub(l7); y1 = y1.sub(l7);
      IntVector l8 = IntVector.broadcast(SPECIES, LIT[8]);
      y0 = y0.add(l8); y1 = y1.add(l8);
      IntVector l9 = IntVector.broadcast(SPECIES, LIT[9]);
      y0 = y0.sub(l9); y1 = y1.sub(l9);
      IntVector l10 = IntVector.broadcast(SPECIES, LIT[10]);
      y0 = y0.add(l10); y1 = y1.add(l10);
      IntVector l11 = IntVector.broadcast(SPECIES, LIT[11]);
      y0 = y0.sub(l11); y1 = y1.sub(l11);
      IntVector l12 = IntVector.broadcast(SPECIES, LIT[12]);
      y0 = y0.add(l12); y1 = y1.add(l12);
      IntVector l13 = IntVector.broadcast(SPECIES, LIT[13]);
      y0 = y0.sub(l13); y1 = y1.sub(l13);
      IntVector l14 = IntVector.broadcast(SPECIES, LIT[14]);
      y0 = y0.add(l14); y1 = y1.add(l14);
      IntVector l15 = IntVector.broadcast(SPECIES, LIT[15]);
      y0 = y0.sub(l15); y1 = y1.sub(l15);
      IntVector l16 = IntVector.broadcast(SPECIES, LIT[16]);
      y0 = y0.add(l16); y1 = y1.add(l16);
      IntVector l17 = IntVector.broadcast(SPECIES, LIT[17]);
      y0 = y0.sub(l17); y1 = y1.sub(l17);
      IntVector l18 = IntVector.broadcast(SPECIES, LIT[18]);
      y0 = y0.add(l18); y1 = y1.add(l18);
      IntVector l19 = IntVector.broadcast(SPECIES, LIT[19]);
      y0 = y0.sub(l19); y1 = y1.sub(l19);
      y0.intoMemorySegment(dst, off0, ORDER);
      y1.intoMemorySegment(dst, off1, ORDER);
    }
    consume(bh);
  }

  @Benchmark
  public void chainD20K4(Blackhole bh) {
    int step = LANES * 4;
    int bound = N - (N % step);
    for (int i = 0; i < bound; i += step) {
      long off0 = (long) i * 4;
      long off1 = (long) (i + LANES) * 4;
      long off2 = (long) (i + 2 * LANES) * 4;
      long off3 = (long) (i + 3 * LANES) * 4;
      IntVector y0 = IntVector.fromMemorySegment(SPECIES, src, off0, ORDER);
      IntVector y1 = IntVector.fromMemorySegment(SPECIES, src, off1, ORDER);
      IntVector y2 = IntVector.fromMemorySegment(SPECIES, src, off2, ORDER);
      IntVector y3 = IntVector.fromMemorySegment(SPECIES, src, off3, ORDER);
      IntVector l0 = IntVector.broadcast(SPECIES, LIT[0]);
      y0 = y0.add(l0); y1 = y1.add(l0); y2 = y2.add(l0); y3 = y3.add(l0);
      IntVector l1 = IntVector.broadcast(SPECIES, LIT[1]);
      y0 = y0.sub(l1); y1 = y1.sub(l1); y2 = y2.sub(l1); y3 = y3.sub(l1);
      IntVector l2 = IntVector.broadcast(SPECIES, LIT[2]);
      y0 = y0.add(l2); y1 = y1.add(l2); y2 = y2.add(l2); y3 = y3.add(l2);
      IntVector l3 = IntVector.broadcast(SPECIES, LIT[3]);
      y0 = y0.sub(l3); y1 = y1.sub(l3); y2 = y2.sub(l3); y3 = y3.sub(l3);
      IntVector l4 = IntVector.broadcast(SPECIES, LIT[4]);
      y0 = y0.add(l4); y1 = y1.add(l4); y2 = y2.add(l4); y3 = y3.add(l4);
      IntVector l5 = IntVector.broadcast(SPECIES, LIT[5]);
      y0 = y0.sub(l5); y1 = y1.sub(l5); y2 = y2.sub(l5); y3 = y3.sub(l5);
      IntVector l6 = IntVector.broadcast(SPECIES, LIT[6]);
      y0 = y0.add(l6); y1 = y1.add(l6); y2 = y2.add(l6); y3 = y3.add(l6);
      IntVector l7 = IntVector.broadcast(SPECIES, LIT[7]);
      y0 = y0.sub(l7); y1 = y1.sub(l7); y2 = y2.sub(l7); y3 = y3.sub(l7);
      IntVector l8 = IntVector.broadcast(SPECIES, LIT[8]);
      y0 = y0.add(l8); y1 = y1.add(l8); y2 = y2.add(l8); y3 = y3.add(l8);
      IntVector l9 = IntVector.broadcast(SPECIES, LIT[9]);
      y0 = y0.sub(l9); y1 = y1.sub(l9); y2 = y2.sub(l9); y3 = y3.sub(l9);
      IntVector l10 = IntVector.broadcast(SPECIES, LIT[10]);
      y0 = y0.add(l10); y1 = y1.add(l10); y2 = y2.add(l10); y3 = y3.add(l10);
      IntVector l11 = IntVector.broadcast(SPECIES, LIT[11]);
      y0 = y0.sub(l11); y1 = y1.sub(l11); y2 = y2.sub(l11); y3 = y3.sub(l11);
      IntVector l12 = IntVector.broadcast(SPECIES, LIT[12]);
      y0 = y0.add(l12); y1 = y1.add(l12); y2 = y2.add(l12); y3 = y3.add(l12);
      IntVector l13 = IntVector.broadcast(SPECIES, LIT[13]);
      y0 = y0.sub(l13); y1 = y1.sub(l13); y2 = y2.sub(l13); y3 = y3.sub(l13);
      IntVector l14 = IntVector.broadcast(SPECIES, LIT[14]);
      y0 = y0.add(l14); y1 = y1.add(l14); y2 = y2.add(l14); y3 = y3.add(l14);
      IntVector l15 = IntVector.broadcast(SPECIES, LIT[15]);
      y0 = y0.sub(l15); y1 = y1.sub(l15); y2 = y2.sub(l15); y3 = y3.sub(l15);
      IntVector l16 = IntVector.broadcast(SPECIES, LIT[16]);
      y0 = y0.add(l16); y1 = y1.add(l16); y2 = y2.add(l16); y3 = y3.add(l16);
      IntVector l17 = IntVector.broadcast(SPECIES, LIT[17]);
      y0 = y0.sub(l17); y1 = y1.sub(l17); y2 = y2.sub(l17); y3 = y3.sub(l17);
      IntVector l18 = IntVector.broadcast(SPECIES, LIT[18]);
      y0 = y0.add(l18); y1 = y1.add(l18); y2 = y2.add(l18); y3 = y3.add(l18);
      IntVector l19 = IntVector.broadcast(SPECIES, LIT[19]);
      y0 = y0.sub(l19); y1 = y1.sub(l19); y2 = y2.sub(l19); y3 = y3.sub(l19);
      y0.intoMemorySegment(dst, off0, ORDER);
      y1.intoMemorySegment(dst, off1, ORDER);
      y2.intoMemorySegment(dst, off2, ORDER);
      y3.intoMemorySegment(dst, off3, ORDER);
    }
    consume(bh);
  }

  private void consume(Blackhole bh) {
    if (bh != null) {
      bh.consume(dst.get(ValueLayout.JAVA_INT, 0L));
    }
  }
}
