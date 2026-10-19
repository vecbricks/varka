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

package org.apache.spark.sql;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * What a kernel of both lanes costs against the single-lane kernel doing the same work, for the
 * shapes the int-bigint casts and Catalyst's promotions build (VARKA-28 6).
 *
 * <p>A kernel that converts between lanes is driven at the long lane, and its int values fill the
 * low half of the int species of the same width (`VARKA-28.md` 9): an int column is a masked load
 * of half a register a lane group, and a widening one {@code I2L} conversion. So
 * {@code cast(i AS bigint) + l} should cost per row what {@code l2 + l} costs, a little less for
 * reading four bytes a row where the long column reads eight; and {@code cast(l AS int) + i}, an
 * int add computed at half the int species' lanes, about what the long lane costs per row rather
 * than what the int lane does. Each mixed arm sits beside the single-lane arm of the same work and
 * a scalar loop over the same rows, the row engine's arithmetic without its row format.
 *
 * <p>A ladder, because the arms read different bytes a row (8 and 4 for a mixed input pair against
 * 16 for two long columns): the rungs are {@code VarkaLongLaneBenchmark}'s.
 *
 * <pre>
 *   build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaLaneConversionBenchmark"
 *   dev/varka_bench_regen.sh catalyst VarkaLaneConversionBenchmark
 * </pre>
 */
public class VarkaLaneConversionBenchmark extends VarkaBenchmarkBase {

  /** A rung of the ladder: a row count and what it is for. */
  private record Rung(int numRows, String level) { }

  private static final List<Rung> LADDER = List.of(
      new Rung(16384, "every arm in L2"),
      new Rung(262144, "every arm in L3"),
      new Rung(1000000, "the long arms leave L3"),
      new Rung(8388608, "every arm past L3"));

  private final AtomicInteger classCounter = new AtomicInteger(0);

  private VarkaFusedKernel kernel(List<VarkaVectorIR> roots, VarkaGeneratedClassLoader loader) {
    String name = "org.apache.spark.sql.varka.bench.LaneConversion"
        + classCounter.incrementAndGet();
    byte[] bytes = VarkaLoopEmitter.emit(name, roots, 2, 0, null, null,
        VarkaEmitOptions.DEFAULTS);
    return defineKernel(loader, name, bytes);
  }

  private static MemorySegment column(Arena arena, int numRows, int bytesPerRow, int shift) {
    MemorySegment data = arena.allocate((long) numRows * bytesPerRow, 8);
    for (int i = 0; i < numRows; i++) {
      if (bytesPerRow == 4) {
        data.set(ValueLayout.JAVA_INT, i * 4L, (i * 7 + shift) % 100000 - 50000);
      } else {
        data.set(ValueLayout.JAVA_LONG, i * 8L, (1L << 40) + (i * 7L + shift) % 100000L - 50000L);
      }
    }
    return data;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var loader = new VarkaGeneratedClassLoader(getClass().getClassLoader());
    try {
      section("kernels of both lanes against one lane", () -> {
        for (Rung rung : LADDER) {
          runRung(rung.numRows(), rung.level(), loader);
        }
      });
    } finally {
      loader.release();
    }
  }

  private interface Run {
    int apply(VarkaFusedKernel kernel);
  }

  private void runRung(int numRows, String level, VarkaGeneratedClassLoader loader) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment ints = column(arena, numRows, 4, 0);
      MemorySegment ints2 = column(arena, numRows, 4, 137);
      MemorySegment longs = column(arena, numRows, 8, 0);
      MemorySegment longs2 = column(arena, numRows, 8, 137);
      MemorySegment dstInt = arena.allocate(numRows * 4L, 8);
      MemorySegment dstLong = arena.allocate(numRows * 8L, 8);
      MemorySegment dstValidity = arena.allocate(((numRows + 63) / 64) * 8L, 8);
      long[] noValidity = {0L, 0L};
      int[] noNulls = {0, 0};
      var benchmark = benchmark(numRows + " rows - " + level, numRows).timed().build();

      var i = new ColumnRef(0);
      var i2 = new ColumnRef(1);
      var l = new ColumnRef(1, LaneType.LONG);
      var l0 = new ColumnRef(0, LaneType.LONG);
      // Each arm: its label, its roots and its two inputs; the store follows the root's lane.
      record Arm(String label, VarkaVectorIR root, MemorySegment a, MemorySegment b) { }
      List<Arm> arms = List.of(
          new Arm("cast(i AS bigint) + l, both lanes",
              new IntArith(IntOp.ADD, Overflow.WRAP, new WidenLane(i), l), ints, longs),
          new Arm("l2 + l, the long lane", new IntArith(IntOp.ADD, Overflow.WRAP, l0, l), longs2,
              longs),
          new Arm("cast(i AS bigint) < l, both lanes, a selection",
              new Compare(CompareOp.LT, new WidenLane(i), l), ints, longs),
          new Arm("l2 < l, the long lane, a selection", new Compare(CompareOp.LT, l0, l), longs2,
              longs),
          new Arm("cast(l AS int) + i, both lanes",
              new IntArith(IntOp.ADD, Overflow.WRAP, i, new NarrowLane(l)), ints, longs),
          new Arm("i2 + i, the int lane", new IntArith(IntOp.ADD, Overflow.WRAP, i, i2), ints,
              ints2));
      for (Arm arm : arms) {
        VarkaFusedKernel k = kernel(List.of(arm.root()), loader);
        long dst = arm.root() instanceof Cond ? 0L
            : arm.root().laneType() == LaneType.INT ? dstInt.address() : dstLong.address();
        long[] src = {arm.a().address(), arm.b().address()};
        long[] dsts = {dst};
        long[] validity = {dstValidity.address()};
        // A kernel driven at the int lane has the seven-argument `run`, one at the long lane the
        // eight-argument one with its long table.
        Run run = VarkaVectorIR.emissionLane(arm.root()) == LaneType.INT
            ? kernel -> kernel.run(src, noValidity, noNulls, dsts, validity, new int[0], numRows)
            : kernel -> kernel.run(src, noValidity, noNulls, dsts, validity, new int[0],
                new long[0], numRows);
        benchmark.addCase(arm.label(), iteration -> {
          int status = run.apply(k);
          if (status != 0) {
            throw new IllegalStateException(
                "assertion failed: the kernel declined a batch: " + status);
          }
        });
      }
      // The scalar loop over the mixed arm's rows: the arithmetic a row engine does, no format.
      benchmark.addCase("cast(i AS bigint) + l, a scalar loop", iteration -> {
        for (int r = 0; r < numRows; r++) {
          dstLong.set(ValueLayout.JAVA_LONG, r * 8L, ints.get(ValueLayout.JAVA_INT, r * 4L)
              + longs.get(ValueLayout.JAVA_LONG, r * 8L));
        }
      });
      runCases(benchmark);
    }
  }
}
