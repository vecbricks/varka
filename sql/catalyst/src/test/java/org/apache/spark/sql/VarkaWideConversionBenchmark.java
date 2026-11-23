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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.NarrowConversionForm;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.WideConversionForm;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * What each AVX2 form of a {@code BIGINT} cast to {@code DOUBLE} and to {@code FLOAT} costs a row
 * (VARKA-583, VARKA-600): the kernel emitted at {@code useAVX=2}, where C2 on x86 has no
 * instruction for the Vector API's {@code L2D} and {@code L2F} without AVX512DQ, so the
 * conversion is built from integer operations and double arithmetic
 * ({@code VarkaConversionLowering}).
 *
 * <p>The arms, each its own kernel class over the same column:
 * <ul>
 *   <li>{@code DOUBLE}: the XOR split (the default), part 343b1's split, which differs by an
 *     arithmetic shift and a multiply, and the bounded magic, five lane calls and a range
 *     guard;</li>
 *   <li>{@code FLOAT}: the round-to-odd form with the default sign and arm selection, and with
 *     part 343b1's ({@code roundToOddByShifts}); the windowed form, the bounded magic and one
 *     {@code D2F} for a group inside {@code -2^51 <= v < 2^51} and the round to odd for any
 *     other; and the scalar form, a lane read, Java's cast and a lane insert a lane
 *     ({@code narrowConversionForm});</li>
 *   <li>a scalar loop of Java's own cast over the same rows, the arithmetic a row engine does
 *     without its row format.</li>
 * </ul>
 * On the first three rungs the values spread over every bit length up to {@code 2^51}, inside
 * the bounded magic's window, so that no arm declines and the windowed form takes its magic arm
 * on every group. The fourth rung, sized for L2, spreads them from {@code 2^52} up to
 * {@code 2^62} of either sign, past the window on every lane, so the windowed form takes its
 * round to odd on every group, and the bounded magic, which would decline every batch, is left
 * out there; every other form has no data-dependent branch, so the values do not change its
 * cost. Every kernel's first answer is checked against Java's cast on every row, so the fourth
 * rung also checks {@code FLOAT}'s large arm.
 *
 * <p>On a host with AVX-512 the forms run as written only under {@code -XX:UseAVX=2}; the EPYC
 * 7763 runners have no AVX-512, so they need no flag:
 * <pre>
 *   build/sbt "project catalyst" 'set Test/javaOptions += "-XX:UseAVX=2"' \
 *     "Test/runMain org.apache.spark.sql.VarkaWideConversionBenchmark"
 *   gh workflow run benchmark.yml -f class=VarkaWideConversionBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 */
public class VarkaWideConversionBenchmark extends VarkaBenchmarkBase {

  /** A rung of the ladder: a row count, what it is for, and whether its values pass 2^51. */
  private record Rung(int numRows, String level, boolean pastWindow) { }

  /** The column's 8 bytes a row and a double's 8: 16 a row at most, so 16384 rows are 256 KB. */
  private static final List<Rung> LADDER = List.of(
      new Rung(16384, "every arm in L2", false),
      new Rung(262144, "every arm in L3", false),
      new Rung(8388608, "every arm past L3", false),
      new Rung(16384, "in L2, every value past 2^51", true));

  private static final VarkaEmitOptions AVX2 = VarkaEmitOptions.DEFAULTS.withUseAVX(2)
      .withWideConversionForm(WideConversionForm.XOR_SPLIT).withRoundToOddByShifts(false)
      .withNarrowConversionForm(NarrowConversionForm.ROUND_TO_ODD);

  /** One timed arm: its label, its conversion's target and the options its kernel emits under. */
  private record Arm(String label, LaneType target, VarkaEmitOptions options) { }

  private static final List<Arm> ARMS = List.of(
      new Arm("cast(l AS double), the XOR split", LaneType.DOUBLE, AVX2),
      new Arm("cast(l AS double), 343b1's split", LaneType.DOUBLE,
          AVX2.withWideConversionForm(WideConversionForm.SPLIT)),
      new Arm("cast(l AS double), the bounded magic", LaneType.DOUBLE,
          AVX2.withWideConversionForm(WideConversionForm.MAGIC)),
      new Arm("cast(l AS float), round to odd", LaneType.FLOAT, AVX2),
      new Arm("cast(l AS float), round to odd by 343b1's shifts", LaneType.FLOAT,
          AVX2.withRoundToOddByShifts(true)),
      new Arm("cast(l AS float), windowed", LaneType.FLOAT,
          AVX2.withNarrowConversionForm(NarrowConversionForm.WINDOWED)),
      new Arm("cast(l AS float), scalar lanes", LaneType.FLOAT,
          AVX2.withNarrowConversionForm(NarrowConversionForm.SCALAR)));

  private final AtomicInteger classCounter = new AtomicInteger(0);

  private VarkaFusedKernel kernel(VarkaVectorIR root, VarkaEmitOptions options,
      VarkaGeneratedClassLoader loader) {
    String name = "org.apache.spark.sql.varka.bench.WideConversion"
        + classCounter.incrementAndGet();
    byte[] bytes = VarkaLoopEmitter.emit(name, List.of(root), 1, 0, null, null, options);
    return defineKernel(loader, name, bytes);
  }

  /**
   * {@code numRows} longs of either sign: of every bit length up to {@code 2^51}, or, past the
   * window, magnitudes from {@code 2^52} up to {@code 2^62}.
   */
  private static MemorySegment column(Arena arena, int numRows, boolean pastWindow) {
    MemorySegment data = arena.allocate(numRows * 8L, 8);
    for (int i = 0; i < numRows; i++) {
      long hash = (i + 1) * 0x9E3779B97F4A7C15L;
      long magnitude = ((hash >>> 2) >>> (i % 10)) | (1L << 52);
      long v = !pastWindow ? (hash >> 12) >> (i % 52) : hash < 0 ? -magnitude : magnitude;
      data.set(ValueLayout.JAVA_LONG, i * 8L, v);
    }
    return data;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var loader = new VarkaGeneratedClassLoader(getClass().getClassLoader());
    try {
      section("a bigint cast to double and float by the AVX2 forms", () -> {
        for (Rung rung : LADDER) {
          runRung(rung, loader);
        }
      });
    } finally {
      loader.release();
    }
  }

  private void runRung(Rung rung, VarkaGeneratedClassLoader loader) {
    int numRows = rung.numRows();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment longs = column(arena, numRows, rung.pastWindow());
      MemorySegment dst = arena.allocate(numRows * 8L, 8);
      MemorySegment dstValidity = arena.allocate(((numRows + 63) / 64) * 8L, 8);
      long[] src = {longs.address()};
      long[] noValidity = {0L};
      int[] noNulls = {0};
      long[] dsts = {dst.address()};
      long[] validity = {dstValidity.address()};
      var benchmark = benchmark(numRows + " rows - " + rung.level(), numRows).timed().build();
      for (Arm arm : ARMS) {
        if (rung.pastWindow()
            && arm.options().wideConversionForm() == WideConversionForm.MAGIC
            && arm.target() == LaneType.DOUBLE) {
          // Past 2^51 the bounded magic declines every batch, so it has no rate there.
          continue;
        }
        VarkaFusedKernel k = kernel(new ConvertLane(new ColumnRef(0, LaneType.LONG),
            arm.target()), arm.options(), loader);
        run(k, src, noValidity, noNulls, dsts, validity, numRows);
        check(arm, longs, dst, numRows);
        benchmark.addCase(arm.label(),
            iteration -> run(k, src, noValidity, noNulls, dsts, validity, numRows));
      }
      benchmark.addCase("cast(l AS double), a scalar loop", iteration -> {
        for (int r = 0; r < numRows; r++) {
          dst.set(ValueLayout.JAVA_DOUBLE, r * 8L, longs.get(ValueLayout.JAVA_LONG, r * 8L));
        }
      });
      benchmark.addCase("cast(l AS float), a scalar loop", iteration -> {
        for (int r = 0; r < numRows; r++) {
          dst.set(ValueLayout.JAVA_FLOAT, r * 4L, longs.get(ValueLayout.JAVA_LONG, r * 8L));
        }
      });
      runCases(benchmark);
    }
  }

  /** A run of the long-driven kernel, whose run takes the long literal table; none may decline. */
  private static void run(VarkaFusedKernel k, long[] src, long[] noValidity, int[] noNulls,
      long[] dsts, long[] validity, int numRows) {
    int status = k.run(src, noValidity, noNulls, dsts, validity, new int[0], new long[0],
        numRows);
    if (status != 0) {
      throw new IllegalStateException("assertion failed: the kernel declined a batch: " + status);
    }
  }

  /** The kernel's answer on every row against Java's cast, bit for bit. */
  private static void check(Arm arm, MemorySegment longs, MemorySegment dst, int numRows) {
    for (int r = 0; r < numRows; r++) {
      long v = longs.get(ValueLayout.JAVA_LONG, r * 8L);
      // A long converts to no NaN, so the bits are the whole answer.
      long want = arm.target() == LaneType.DOUBLE
          ? Double.doubleToRawLongBits((double) v) : Float.floatToRawIntBits((float) v);
      long got = arm.target() == LaneType.DOUBLE
          ? dst.get(ValueLayout.JAVA_LONG, r * 8L) : dst.get(ValueLayout.JAVA_INT, r * 4L);
      if (got != want) {
        throw new IllegalStateException("assertion failed: " + arm.label() + " at row " + r
            + " of " + v);
      }
    }
  }
}
