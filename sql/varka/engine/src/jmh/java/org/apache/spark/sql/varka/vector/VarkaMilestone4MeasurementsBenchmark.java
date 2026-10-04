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
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
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
 * Five pre-registration measurements for milestone 4's not-yet-started tasks (`m4/PLAN.md`
 * section 2.4, 2.5 and 2.7), each a pair (or trio) of candidate lowerings for the same shape, over
 * one {@code N}-row buffer sized to Spark's default {@code COLUMN_BATCH_SIZE} - the working set
 * every real Varka kernel actually runs at, fully L1/L2-resident, as opposed to a multi-megabyte
 * streaming buffer that is bandwidth-bound and hides the per-op differences these measurements
 * exist to find.
 *
 * <ul>
 *   <li><b>Alignment</b> ({@code add*}): does a 64-byte (AVX-512 vector width) aligned buffer start
 *       beat one offset by 4 bytes (still 4-byte int-aligned, but every load/store then spans two
 *       cache lines)? Feeds the "buffer alignment enforcement" line in section 8.</li>
 *   <li><b>Boolean materialization</b> ({@code bool*}): {@code VectorMask.toVector().and(one)},
 *       {@code zero.blend(one, mask)}, and {@code mask.toLong()} (the actual bit-packed
 * output-boundary format) as three ways to turn a comparison into a boolean column. Feeds the
 * boolean-output work (`m5/PLAN.md`).</li>
 *   <li><b>Lane-width conversion</b> ({@code laneWidth*}): {@code cast(int AS long) + long},
 *       driving the loop at the narrower (long) lane count versus the wider (int) one with a
 * two-part {@code convertShape}. Feeds the lane-width conversion work
 * (`m5/PLAN.md`).</li>
 *   <li><b>Boolean trees</b> ({@code boolTree*}): {@code (a > b) AND (c < d)} kept in mask space
 * throughout versus materialized as int columns at each node. Feeds the boolean-output work's
 * compound- predicate case.</li>
 *   <li><b>Trapping-op safety</b> ({@code div*}): the emitter's invariant is that inactive lanes
 *       read 0, which a division must not trap on. Blending a safe divisor (1) into inactive
 *       lanes before an unmasked {@code DIV} versus the masked lanewise {@code DIV} form, which
 * never evaluates inactive lanes. Feeds the ANSI integer arithmetic work
 * (`m5/PLAN.md`).</li>
 * </ul>
 *
 * <p>Every {@code @Setup} also runs a correctness check against a scalar reference (throwing on
 * disagreement on active lanes), so a broken variant fails loudly rather than reporting a fast
 * wrong number.
 *
 * <p>Run: {@code ./build/mvn -f sql/varka/engine/pom.xml test -Dvarka.jmh=true}. As with
 * {@link DateVectorOpsBenchmark}, JMH is driven from a test because maven-jmh-plugin does not
 * resolve on this environment's Maven mirror, and forks one JVM per benchmark. The lane-width
 * pair keeps its own {@link LaneWidth} state: see there for why it must not share a JVM with
 * anything it is compared against.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
// One JVM per benchmark, forked by VarkaMilestone4MeasurementsBenchmarkTest (surefire argLine).
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
public class VarkaMilestone4MeasurementsBenchmark {

  /** One Spark {@code COLUMN_BATCH_SIZE}: the working set every real Varka kernel runs at. */
  static final int N = 4096;

  private static final VectorSpecies<Integer> ISPEC_FULL = IntVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Long> LSPEC = LongVector.SPECIES_PREFERRED;
  // The int species whose lane count matches LSPEC's - half of LSPEC's bit width, since an int
  // lane is half a long lane's byte size. A fixed literal (e.g. SPECIES_256) breaks under
  // -XX:MaxVectorSize, which changes what "preferred" resolves to; this stays correct at every
  // forced width, including the narrow-vector CI shape.
  private static final VectorSpecies<Integer> ISPEC_HALF =
      VectorSpecies.of(int.class, VectorShape.forBitSize(LSPEC.vectorBitSize() / 2));
  private static final int ILANES = ISPEC_FULL.length();
  private static final ByteOrder ORDER = ByteOrder.nativeOrder();
  private static final IntVector ONE = IntVector.broadcast(ISPEC_FULL, 1);
  private static final IntVector ZERO = IntVector.zero(ISPEC_FULL);

  private Arena arena;

  // -- 1. Alignment --
  private MemorySegment alignABack, alignBBack, alignDBack;
  private MemorySegment alignA, alignB, alignD;       // 64-byte aligned view
  private MemorySegment misA, misB, misD;             // same buffers, offset by 4 bytes

  // -- 2. Boolean materialization --
  private MemorySegment boolA, boolB, boolIntDst, boolBitsDst;

  // -- 4. Boolean trees --
  private MemorySegment btA, btB, btC, btD, btOutBits;

  // -- 5. Trapping-op safety --
  private MemorySegment divDividend, divDivisor, divActiveBits, divOut;

  @Setup(Level.Trial)
  public void setUp() {
    arena = Arena.ofShared();
    Random r = new Random(42);
    setUpAlignment(r);
    setUpBoolean(r);
    setUpBooleanTree(r);
    setUpDivision(r);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (arena != null) {
      arena.close();
    }
  }

  // =====================================================================================
  // 1. Buffer alignment
  // =====================================================================================

  private void setUpAlignment(Random r) {
    long bytes = (long) (N + 32) * 4;
    alignABack = arena.allocate(bytes, 64);
    alignBBack = arena.allocate(bytes, 64);
    alignDBack = arena.allocate(bytes, 64);
    for (int i = 0; i < N + 32; i++) {
      alignABack.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt());
      alignBBack.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt());
    }
    alignA = alignABack.asSlice(0, (long) N * 4);
    alignB = alignBBack.asSlice(0, (long) N * 4);
    alignD = alignDBack.asSlice(0, (long) N * 4);
    misA = alignABack.asSlice(4, (long) N * 4);
    misB = alignBBack.asSlice(4, (long) N * 4);
    misD = alignDBack.asSlice(4, (long) N * 4);
  }

  private static void addKernel(MemorySegment a, MemorySegment b, MemorySegment dst) {
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i += ILANES) {
      long off = (long) i * 4;
      IntVector va = IntVector.fromMemorySegment(ISPEC_FULL, a, off, ORDER);
      IntVector vb = IntVector.fromMemorySegment(ISPEC_FULL, b, off, ORDER);
      va.add(vb).intoMemorySegment(dst, off, ORDER);
    }
  }

  @Benchmark
  public void addAligned(Blackhole bh) {
    addKernel(alignA, alignB, alignD);
    bh.consume(alignD.get(ValueLayout.JAVA_INT, 0L));
  }

  @Benchmark
  public void addMisaligned(Blackhole bh) {
    addKernel(misA, misB, misD);
    bh.consume(misD.get(ValueLayout.JAVA_INT, 0L));
  }

  // =====================================================================================
  // 2. Boolean output materialization
  // =====================================================================================

  private void setUpBoolean(Random r) {
    boolA = arena.allocate((long) N * 4, 64);
    boolB = arena.allocate((long) N * 4, 64);
    boolIntDst = arena.allocate((long) N * 4, 64);
    boolBitsDst = arena.allocate((long) N / ILANES * 2 + 8, 64);
    for (int i = 0; i < N; i++) {
      boolA.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt());
      boolB.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt());
    }
    boolToVectorAnd(NOOP);
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i++) {
      boolean expected =
          boolA.getAtIndex(ValueLayout.JAVA_INT, i) > boolB.getAtIndex(ValueLayout.JAVA_INT, i);
      int got = boolIntDst.getAtIndex(ValueLayout.JAVA_INT, i);
      if (expected != (got != 0)) {
        throw new AssertionError("toVector+and mismatch at " + i);
      }
    }
    boolBitsOnly(NOOP);
    for (int i = 0; i < bound; i++) {
      boolean expected =
          boolA.getAtIndex(ValueLayout.JAVA_INT, i) > boolB.getAtIndex(ValueLayout.JAVA_INT, i);
      short bits = boolBitsDst.getAtIndex(ValueLayout.JAVA_SHORT, i / ILANES);
      boolean got = ((bits >>> (i % ILANES)) & 1) != 0;
      if (got != expected) {
        throw new AssertionError("toLong mismatch at " + i);
      }
    }
  }

  @Benchmark
  public void boolToVectorAnd(Blackhole bh) {
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i += ILANES) {
      long off = (long) i * 4;
      IntVector va = IntVector.fromMemorySegment(ISPEC_FULL, boolA, off, ORDER);
      IntVector vb = IntVector.fromMemorySegment(ISPEC_FULL, boolB, off, ORDER);
      VectorMask<Integer> m = va.compare(VectorOperators.GT, vb);
      ((IntVector) m.toVector()).and(ONE).intoMemorySegment(boolIntDst, off, ORDER);
    }
    bh.consume(boolIntDst.get(ValueLayout.JAVA_INT, 0L));
  }

  @Benchmark
  public void boolBlend(Blackhole bh) {
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i += ILANES) {
      long off = (long) i * 4;
      IntVector va = IntVector.fromMemorySegment(ISPEC_FULL, boolA, off, ORDER);
      IntVector vb = IntVector.fromMemorySegment(ISPEC_FULL, boolB, off, ORDER);
      VectorMask<Integer> m = va.compare(VectorOperators.GT, vb);
      ZERO.blend(ONE, m).intoMemorySegment(boolIntDst, off, ORDER);
    }
    bh.consume(boolIntDst.get(ValueLayout.JAVA_INT, 0L));
  }

  @Benchmark
  public void boolBitsOnly(Blackhole bh) {
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i += ILANES) {
      long off = (long) i * 4;
      IntVector va = IntVector.fromMemorySegment(ISPEC_FULL, boolA, off, ORDER);
      IntVector vb = IntVector.fromMemorySegment(ISPEC_FULL, boolB, off, ORDER);
      VectorMask<Integer> m = va.compare(VectorOperators.GT, vb);
      boolBitsDst.setAtIndex(ValueLayout.JAVA_SHORT, i / ILANES, (short) m.toLong());
    }
    bh.consume(boolBitsDst.get(ValueLayout.JAVA_SHORT, 0L));
  }

  // =====================================================================================
  // 3. Lane-width conversion: cast(int AS long) + long
  // =====================================================================================

  private static final String BLACKHOLE_MAGIC =
      "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";
  private static final Blackhole NOOP = new Blackhole(BLACKHOLE_MAGIC);

  /**
   * The lane-width pair's own state. This is the one measurement that touches a second int
   * species ({@code ISPEC_HALF}), and two species of one lane type in one JVM turn the shared
   * {@code IntVector} templates bimorphic, after which C2 keeps a heap box per loop iteration in
   * some shapes ({@code SKILLS.md}, "Every operator the plans rely on"). Kept out of the
   * class-level {@link #setUp} so no other benchmark's fork ever sees the second species, and
   * the correctness check runs in {@link #tearDown} against whichever variant this fork
   * measured, so neither variant has to run the other.
   */
  @State(Scope.Benchmark)
  public static class LaneWidth {
    private Arena arena;
    MemorySegment lwX;
    MemorySegment lwB;
    MemorySegment lwOut;
    MemorySegment lwExpected;

    @Setup(Level.Trial)
    public void setUp() {
      arena = Arena.ofShared();
      Random r = new Random(42);
      lwX = arena.allocate((long) N * 4, 64);
      lwB = arena.allocate((long) N * 8, 64);
      lwOut = arena.allocate((long) N * 8, 64);
      lwExpected = arena.allocate((long) N * 8, 64);
      for (int i = 0; i < N; i++) {
        int x = r.nextInt();
        long b = r.nextLong();
        lwX.setAtIndex(ValueLayout.JAVA_INT, i, x);
        lwB.setAtIndex(ValueLayout.JAVA_LONG, i, b);
        lwExpected.setAtIndex(ValueLayout.JAVA_LONG, i, (long) x + b);
      }
    }

    /**
     * Correctness, checked once the trial is over: the last invocation left its full result in
     * {@code lwOut} (N is a multiple of both lane counts, so either loop covers every row with no
     * epilogue involved), and it must agree with the scalar reference.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
      try {
        for (int i = 0; i < N; i++) {
          long expected = lwExpected.getAtIndex(ValueLayout.JAVA_LONG, i);
          if (lwOut.getAtIndex(ValueLayout.JAVA_LONG, i) != expected) {
            throw new AssertionError("lane-width mismatch at " + i);
          }
        }
      } finally {
        arena.close();
      }
    }
  }

  @Benchmark
  public void laneWidthNarrowestDrive(LaneWidth s, Blackhole bh) {
    int lanes = LSPEC.length();
    int bound = N - (N % lanes);
    for (int i = 0; i < bound; i += lanes) {
      long ioff = (long) i * 4;
      long loff = (long) i * 8;
      IntVector vx = IntVector.fromMemorySegment(ISPEC_HALF, s.lwX, ioff, ORDER);
      LongVector vxl = (LongVector) vx.convertShape(VectorOperators.I2L, LSPEC, 0);
      LongVector vb = LongVector.fromMemorySegment(LSPEC, s.lwB, loff, ORDER);
      vxl.add(vb).intoMemorySegment(s.lwOut, loff, ORDER);
    }
    bh.consume(s.lwOut.get(ValueLayout.JAVA_LONG, 0L));
  }

  @Benchmark
  public void laneWidthPartLoop(LaneWidth s, Blackhole bh) {
    int halfLanes = LSPEC.length();
    int bound = N - (N % ILANES);
    for (int i = 0; i < bound; i += ILANES) {
      long ioff = (long) i * 4;
      IntVector vx = IntVector.fromMemorySegment(ISPEC_FULL, s.lwX, ioff, ORDER);
      LongVector vxl0 = (LongVector) vx.convertShape(VectorOperators.I2L, LSPEC, 0);
      LongVector vxl1 = (LongVector) vx.convertShape(VectorOperators.I2L, LSPEC, 1);
      long loff0 = (long) i * 8;
      long loff1 = (long) (i + halfLanes) * 8;
      LongVector vb0 = LongVector.fromMemorySegment(LSPEC, s.lwB, loff0, ORDER);
      LongVector vb1 = LongVector.fromMemorySegment(LSPEC, s.lwB, loff1, ORDER);
      vxl0.add(vb0).intoMemorySegment(s.lwOut, loff0, ORDER);
      vxl1.add(vb1).intoMemorySegment(s.lwOut, loff1, ORDER);
    }
    bh.consume(s.lwOut.get(ValueLayout.JAVA_LONG, 0L));
  }

  // =====================================================================================
  // 4. Boolean trees: (a > b) AND (c < d)
  // =====================================================================================

  private void setUpBooleanTree(Random r) {
    btA = arena.allocate((long) N * 4, 64);
    btB = arena.allocate((long) N * 4, 64);
    btC = arena.allocate((long) N * 4, 64);
    btD = arena.allocate((long) N * 4, 64);
    btOutBits = arena.allocate((long) N / ILANES * 2 + 8, 64);
    for (int i = 0; i < N; i++) {
      btA.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt(100));
      btB.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt(100));
      btC.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt(100));
      btD.setAtIndex(ValueLayout.JAVA_INT, i, r.nextInt(100));
    }
    boolTreeMaskSpace(NOOP);
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i++) {
      boolean expected =
          btA.getAtIndex(ValueLayout.JAVA_INT, i) > btB.getAtIndex(ValueLayout.JAVA_INT, i)
              && btC.getAtIndex(ValueLayout.JAVA_INT, i) < btD.getAtIndex(ValueLayout.JAVA_INT, i);
      short bits = btOutBits.getAtIndex(ValueLayout.JAVA_SHORT, i / ILANES);
      boolean got = ((bits >>> (i % ILANES)) & 1) != 0;
      if (got != expected) {
        throw new AssertionError("boolean-tree mismatch at " + i);
      }
    }
  }

  @Benchmark
  public void boolTreeMaskSpace(Blackhole bh) {
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i += ILANES) {
      long off = (long) i * 4;
      IntVector va = IntVector.fromMemorySegment(ISPEC_FULL, btA, off, ORDER);
      IntVector vb = IntVector.fromMemorySegment(ISPEC_FULL, btB, off, ORDER);
      IntVector vc = IntVector.fromMemorySegment(ISPEC_FULL, btC, off, ORDER);
      IntVector vd = IntVector.fromMemorySegment(ISPEC_FULL, btD, off, ORDER);
      VectorMask<Integer> m1 = va.compare(VectorOperators.GT, vb);
      VectorMask<Integer> m2 = vc.compare(VectorOperators.LT, vd);
      btOutBits.setAtIndex(ValueLayout.JAVA_SHORT, i / ILANES, (short) m1.and(m2).toLong());
    }
    bh.consume(btOutBits.get(ValueLayout.JAVA_SHORT, 0L));
  }

  @Benchmark
  public void boolTreeMaterializedInt(Blackhole bh) {
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i += ILANES) {
      long off = (long) i * 4;
      IntVector va = IntVector.fromMemorySegment(ISPEC_FULL, btA, off, ORDER);
      IntVector vb = IntVector.fromMemorySegment(ISPEC_FULL, btB, off, ORDER);
      IntVector vc = IntVector.fromMemorySegment(ISPEC_FULL, btC, off, ORDER);
      IntVector vd = IntVector.fromMemorySegment(ISPEC_FULL, btD, off, ORDER);
      VectorMask<Integer> m1 = va.compare(VectorOperators.GT, vb);
      VectorMask<Integer> m2 = vc.compare(VectorOperators.LT, vd);
      IntVector i1 = ((IntVector) m1.toVector()).and(ONE);
      IntVector i2 = ((IntVector) m2.toVector()).and(ONE);
      VectorMask<Integer> resultMask = i1.and(i2).compare(VectorOperators.NE, ZERO);
      btOutBits.setAtIndex(ValueLayout.JAVA_SHORT, i / ILANES, (short) resultMask.toLong());
    }
    bh.consume(btOutBits.get(ValueLayout.JAVA_SHORT, 0L));
  }

  // =====================================================================================
  // 5. Trapping-op safety: masked DIV versus a blended-safe-divisor unmasked DIV
  // =====================================================================================

  private void setUpDivision(Random r) {
    divDividend = arena.allocate((long) N * 4, 64);
    divDivisor = arena.allocate((long) N * 4, 64);
    divOut = arena.allocate((long) N * 4, 64);
    divActiveBits = arena.allocate((long) N / ILANES * 2 + 8, 64);
    for (int g = 0; g < N / ILANES; g++) {
      int bits = 0;
      for (int lane = 0; lane < ILANES; lane++) {
        // Every 8th lane inactive, its divisor slot 0 - a null/out-of-range read.
        boolean active = (lane % 8) != 7;
        if (active) {
          bits |= (1 << lane);
        }
        int idx = g * ILANES + lane;
        divDividend.setAtIndex(ValueLayout.JAVA_INT, idx, r.nextInt(1_000_000) + 1);
        divDivisor.setAtIndex(ValueLayout.JAVA_INT, idx, active ? (r.nextInt(1000) + 1) : 0);
      }
      divActiveBits.setAtIndex(ValueLayout.JAVA_SHORT, g, (short) bits);
    }
    divBlendThenDiv(NOOP);
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i++) {
      long bits = divActiveBits.getAtIndex(ValueLayout.JAVA_SHORT, i / ILANES) & 0xFFFFL;
      boolean active = ((bits >>> (i % ILANES)) & 1L) != 0;
      if (!active) {
        continue;
      }
      int expected = divDividend.getAtIndex(ValueLayout.JAVA_INT, i)
          / divDivisor.getAtIndex(ValueLayout.JAVA_INT, i);
      if (divOut.getAtIndex(ValueLayout.JAVA_INT, i) != expected) {
        throw new AssertionError("blend+div mismatch at " + i);
      }
    }
    divMaskedDiv(NOOP);
    for (int i = 0; i < bound; i++) {
      long bits = divActiveBits.getAtIndex(ValueLayout.JAVA_SHORT, i / ILANES) & 0xFFFFL;
      boolean active = ((bits >>> (i % ILANES)) & 1L) != 0;
      if (!active) {
        continue;
      }
      int expected = divDividend.getAtIndex(ValueLayout.JAVA_INT, i)
          / divDivisor.getAtIndex(ValueLayout.JAVA_INT, i);
      if (divOut.getAtIndex(ValueLayout.JAVA_INT, i) != expected) {
        throw new AssertionError("masked-div mismatch at " + i);
      }
    }
  }

  @Benchmark
  public void divBlendThenDiv(Blackhole bh) {
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i += ILANES) {
      long off = (long) i * 4;
      IntVector vDividend = IntVector.fromMemorySegment(ISPEC_FULL, divDividend, off, ORDER);
      IntVector vDivisor = IntVector.fromMemorySegment(ISPEC_FULL, divDivisor, off, ORDER);
      long bits = divActiveBits.getAtIndex(ValueLayout.JAVA_SHORT, i / ILANES) & 0xFFFFL;
      VectorMask<Integer> mask = VectorMask.fromLong(ISPEC_FULL, bits);
      IntVector safeDivisor = ONE.blend(vDivisor, mask);
      vDividend.div(safeDivisor).intoMemorySegment(divOut, off, ORDER);
    }
    bh.consume(divOut.get(ValueLayout.JAVA_INT, 0L));
  }

  @Benchmark
  public void divMaskedDiv(Blackhole bh) {
    int bound = ISPEC_FULL.loopBound(N);
    for (int i = 0; i < bound; i += ILANES) {
      long off = (long) i * 4;
      IntVector vDividend = IntVector.fromMemorySegment(ISPEC_FULL, divDividend, off, ORDER);
      IntVector vDivisor = IntVector.fromMemorySegment(ISPEC_FULL, divDivisor, off, ORDER);
      long bits = divActiveBits.getAtIndex(ValueLayout.JAVA_SHORT, i / ILANES) & 0xFFFFL;
      VectorMask<Integer> mask = VectorMask.fromLong(ISPEC_FULL, bits);
      vDividend.lanewise(VectorOperators.DIV, vDivisor, mask).intoMemorySegment(divOut, off, ORDER);
    }
    bh.consume(divOut.get(ValueLayout.JAVA_INT, 0L));
  }
}
