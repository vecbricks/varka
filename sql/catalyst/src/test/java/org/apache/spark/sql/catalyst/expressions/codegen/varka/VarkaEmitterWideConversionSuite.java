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

package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.WideConversionForm;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

/**
 * The AVX2 forms of {@code BIGINT} to {@code DOUBLE} and {@code FLOAT} (`VARKA-343.md` 2.3,
 * {@link VarkaConversionLowering}): emitted at {@code useAVX=2}, the level of a host without
 * AVX512DQ, and held bit for bit to Java's {@code (double) v} and {@code (float) v} through the
 * reference evaluator. The forms are vector arithmetic every x86 host has, so this machine runs
 * them whatever its own level; what C2 makes of them on an AVX2 host is read with
 * {@code -XX:UseAVX=2} (section 10 of the plan). Both arms of {@code wideConversionForm}, the
 * bounded one's decline past {@code 2^51}, the cells where a form that rounds twice or drops its
 * sticky bit is wrong, a seeded draw per binade, and 3.4's named failing case.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterWideConversionSuite {

  private static final ColumnRef L = new ColumnRef(0, LaneType.LONG);
  private static final VarkaEmitOptions AVX2 = VarkaMatrixRuns.BASE.withUseAVX(2);
  private static final int[] WIDE_LANES = {2, 4, 8};
  private static final int[] LENGTHS = {1, 2, 7, 8, 9, 17, 129};
  private static final List<IntPredicate> PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  /**
   * The whole-range cells and their negations: the arms' boundary at {@code 2^52} and its
   * neighbours; the float ties {@code 2^52 + 2^28} and {@code 2^62 + 2^38}, which round to even,
   * and their neighbours one sticky bit above, which a form without the sticky bit rounds down;
   * the double ties past {@code 2^53}; {@code 2^53 + 2^29 + 1}, which {@code (float) (double) v}
   * rounds to {@code 2^53} against {@code 2^53 + 2^30}; the top of the range, which rounds to
   * {@code 2^63}; both extremes; and 3.4's named case.
   */
  private static final long[] CELLS = withMinimum(withNegations(0, 1, 3, (1L << 24) + 1,
      (1L << 24) + 3, 1L << 31, (1L << 32) - 1, 1L << 32, (1L << 52) - 1, 1L << 52, (1L << 52) + 1,
      (1L << 52) + (1L << 28), (1L << 52) + (1L << 28) + 1, (1L << 52) + (3L << 28),
      (1L << 53) + 1, (1L << 53) + 3, (1L << 54) + 2, (1L << 53) + (1L << 29) + 1,
      (1L << 62) + (1L << 38), (1L << 62) + (1L << 38) + (1L << 11), (1L << 62) + (1L << 38) + 1,
      Long.MAX_VALUE - (1L << 38), Long.MAX_VALUE, 4275361147L, 0x123456789ABCDEFL));

  /** The bounded form's window, its edges, and cells well inside it. */
  private static final long[] WINDOW = withNegations(0, 1, (1L << 24) + 1, (1L << 32) - 1,
      4275361147L, (1L << 51) - 1, VarkaConversionLowering.MAGIC_WINDOW);

  private static long[] withNegations(long... values) {
    var all = new long[values.length * 2];
    for (int k = 0; k < values.length; k++) {
      all[2 * k] = values[k];
      all[2 * k + 1] = -values[k];
    }
    return all;
  }

  /** {@code values} and {@code Long.MIN_VALUE}, whose magnitude is {@code 2^63} unsigned. */
  private static long[] withMinimum(long[] values) {
    var all = Arrays.copyOf(values, values.length + 1);
    all[values.length] = Long.MIN_VALUE;
    return all;
  }

  private static ConvertLane convert(LaneType target) {
    return new ConvertLane(L, target);
  }

  private static VarkaKernelChecks.LongBatch batch(int length, int pattern, long[] cells,
      int offset) {
    var nulls = new boolean[1][length];
    var values = new long[1][length];
    IntPredicate p = PATTERNS.get(pattern);
    for (int i = 0; i < length; i++) {
      nulls[0][i] = p.test(i);
      values[0][i] = cells[(offset + i) % cells.length];
    }
    return new VarkaKernelChecks.LongBatch(length, nulls, values, length > 1 && pattern == 0);
  }

  /**
   * {@code root} over the long column emitted under {@code options} at {@code lanes} and held to
   * the reference over {@code data}, both bodies; no batch may decline.
   */
  private static void check(String name, VarkaVectorIR root, VarkaEmitOptions options, int lanes,
      VarkaKernelChecks.LongBatch data) {
    var emitted = VarkaEmitterFixtures.emit(List.of(root), 1, 0,
        options.withLanesOverride(lanes));
    String context = name + " under " + options.canonical() + " at " + lanes + " lanes, length "
        + data.length();
    assertTrue(VarkaKernelChecks.runAndCompareMixed(context, emitted.className(), emitted.bytes(),
        List.of(root), 1, new int[0], new long[0], data, false, null), context);
  }

  /** {@code root} over {@code cells} at every width, length and null pattern. */
  private static void checkCells(String name, VarkaVectorIR root, VarkaEmitOptions options,
      long[] cells) {
    for (int lanes : WIDE_LANES) {
      for (int length : LENGTHS) {
        for (int pattern = 0; pattern < PATTERNS.size(); pattern++) {
          check(name, root, options, lanes, batch(length, pattern, cells, pattern * 3));
        }
      }
    }
  }

  @Test
  @DisplayName("a bigint to DOUBLE by the exact split, at its cells, every width and both bodies")
  void splitCells() {
    checkCells("cast(l AS double)", convert(LaneType.DOUBLE), AVX2, CELLS);
  }

  @Test
  @DisplayName("a bigint to FLOAT by rounding to odd, at its cells, every width and both bodies")
  void roundToOddCells() {
    checkCells("cast(l AS float)", convert(LaneType.FLOAT), AVX2, CELLS);
    checkCells("cast(l AS float)", convert(LaneType.FLOAT),
        AVX2.withWideConversionForm(WideConversionForm.MAGIC), CELLS);
  }

  @Test
  @DisplayName("a bigint to DOUBLE by the bounded magic inside its window, beside a float root")
  void magicCells() {
    var magic = AVX2.withWideConversionForm(WideConversionForm.MAGIC);
    checkCells("cast(l AS double)", convert(LaneType.DOUBLE), magic, WINDOW);
    // Both conversions of one long in one kernel, under an arithmetic on the double.
    var sum = new IntArith(IntOp.ADD, Overflow.WRAP, convert(LaneType.DOUBLE),
        convert(LaneType.DOUBLE));
    checkCells("cast(l AS double) + cast(l AS double)", sum, magic, WINDOW);
    checkCells("cast(l AS double) + cast(l AS double)", sum, AVX2, CELLS);
  }

  @Test
  @DisplayName("the bounded magic declines a batch with a live lane past 2^51, and no other")
  void magicDeclines() {
    var magic = AVX2.withWideConversionForm(WideConversionForm.MAGIC);
    VarkaVectorIR root = convert(LaneType.DOUBLE);
    for (int lanes : WIDE_LANES) {
      var emitted = VarkaEmitterFixtures.emit(List.of(root), 1, 0,
          magic.withLanesOverride(lanes));
      for (long outside : new long[] {VarkaConversionLowering.MAGIC_WINDOW + 1,
          -VarkaConversionLowering.MAGIC_WINDOW - 1, Long.MIN_VALUE, Long.MAX_VALUE}) {
        for (int length : new int[] {1, 9, 33}) {
          long[][] values = new long[1][length];
          boolean[][] nulls = new boolean[1][length];
          values[0][length - 1] = outside;
          String context = outside + " at " + lanes + " lanes, length " + length;
          int status = VarkaKernelChecks.runAndCompareMixedStatus(context, emitted.className(),
              emitted.bytes(), List.of(root), 1, new int[0], new long[0],
              new VarkaKernelChecks.LongBatch(length, nulls, values, false), true);
          assertEquals(VarkaFusedKernel.STATUS_VALUE_RANGE, status, context);
          // The same lane null: in the masked body its stored value condemns nothing, and the
          // batch is answered. The harness's second run is the dense body, all lanes live.
          nulls[0][length - 1] = true;
          List<Integer> each = VarkaKernelChecks.runEachBodyMixedStatus(context + ", null",
              emitted.className(), emitted.bytes(), List.of(root), 1, new int[0], new long[0],
              new VarkaKernelChecks.LongBatch(length, nulls, values, true));
          assertEquals(0, each.get(0), context + ", null");
        }
      }
    }
  }

  /**
   * A seeded draw per binade of {@code |v|}, both signs, with each binade's first value and the
   * float and double ties at its scale, in batches of 4096 rows: every row held to Java's casts.
   */
  @Test
  @DisplayName("a seeded draw over every binade of both signs, ties among it, for each form")
  void binades() {
    var random = new Random(343_0002L);
    var values = new ArrayList<Long>();
    for (int j = 0; j < Long.SIZE - 1; j++) {
      long low = 1L << j;
      for (int k = 0; k < 512; k++) {
        long v = j == 0 ? 1 : low | (random.nextLong() & (low - 1));
        values.add(v);
        // The ties: v's bits below the float's (and the double's) last kept bit set to its half.
        for (int precision : new int[] {Float.PRECISION, Double.PRECISION}) {
          int cut = j + 1 - precision;
          if (cut > 0) {
            values.add((v & -(1L << cut)) | (1L << (cut - 1)));
          }
        }
      }
    }
    int n = values.size();
    long[] all = new long[2 * n + 1];
    for (int k = 0; k < n; k++) {
      all[2 * k] = values.get(k);
      all[2 * k + 1] = -values.get(k);
    }
    all[2 * n] = Long.MIN_VALUE;
    var window = new ArrayList<Long>();
    for (long v : all) {
      if (Math.abs(v) <= VarkaConversionLowering.MAGIC_WINDOW && v != Long.MIN_VALUE) {
        window.add(v);
      }
    }
    long[] inWindow = window.stream().mapToLong(Long::longValue).toArray();
    var magic = AVX2.withWideConversionForm(WideConversionForm.MAGIC);
    int rows = 4096;
    for (int lanes : new int[] {4, 8}) {
      for (int offset = 0; offset < all.length; offset += rows) {
        var data = batch(rows, 0, all, offset);
        check("cast(l AS double)", convert(LaneType.DOUBLE), AVX2, lanes, data);
        check("cast(l AS float)", convert(LaneType.FLOAT), AVX2, lanes, data);
      }
      for (int offset = 0; offset < inWindow.length; offset += rows) {
        check("cast(l AS double)", convert(LaneType.DOUBLE), magic, lanes,
            batch(rows, 0, inWindow, offset));
      }
    }
  }

  /**
   * 3.4's named failing case (`READING_0X80.md` item 3): converting an unsigned 32-bit value
   * past {@code 2^31} as its low 31 bits plus {@code 2^31} rounds twice, so 4275361147 gives
   * 4275361280 against one rounding's 4275361024, and about one value in six of
   * {@code [2^31, 2^32)} misrounds. The kernel's {@code L2F} rounds once.
   */
  @Test
  @DisplayName("the split-and-add conversion misrounds 4275361147 and about 16.5% of [2^31, "
      + "2^32); the kernel's L2F does not")
  void splitAndAddFails() {
    long named = 4275361147L;
    assertEquals(4275361280.0f, splitAndAdd(named));
    assertEquals(4275361024.0f, (float) named);
    var random = new Random(343_0004L);
    int draws = 200_000;
    int wrong = 0;
    for (int k = 0; k < draws; k++) {
      long u = (1L << 31) | (random.nextInt() & 0x7FFFFFFFL);
      if (splitAndAdd(u) != (float) u) {
        wrong++;
      }
    }
    double share = wrong / (double) draws;
    assertTrue(share > 0.16 && share < 0.17, "the split-and-add form misrounds " + share);
    // Two roundings to nearest through the double are the other wrong form 2.3 replaces.
    long twice = (1L << 53) + (1L << 29) + 1;
    assertEquals((float) (1L << 53), (float) (double) twice);
    assertEquals((float) ((1L << 53) + (1L << 30)), (float) twice);
    checkCells("cast(l AS float)", convert(LaneType.FLOAT), AVX2,
        new long[] {named, twice, -named, -twice});
  }

  /** The failing form: the low 31 bits converted and {@code 2^31} added, in float arithmetic. */
  private static float splitAndAdd(long u) {
    return (float) (u & 0x7FFFFFFFL) + 0x1p31f;
  }

  @Test
  @DisplayName("below level 3 the kernel holds no 64-bit conversion; at level 3 it is one")
  void formsHoldNoConversion() {
    for (LaneType target : List.of(LaneType.DOUBLE, LaneType.FLOAT)) {
      String op = target == LaneType.DOUBLE ? "L2D" : "L2F";
      for (var options : List.of(AVX2, AVX2.withWideConversionForm(WideConversionForm.MAGIC),
          VarkaMatrixRuns.BASE.withUseAVX(0))) {
        String text = new String(VarkaEmitterFixtures.emit(List.of(convert(target)), 1, 0,
            options.withLanesOverride(4)).bytes(), StandardCharsets.ISO_8859_1);
        assertTrue(!text.contains(op), op + " under " + options.canonical());
      }
      String atDq = new String(VarkaEmitterFixtures.emit(List.of(convert(target)), 1, 0,
          VarkaMatrixRuns.BASE.withUseAVX(VarkaEmitOptions.AVX512_LEVEL).withLanesOverride(4))
          .bytes(), StandardCharsets.ISO_8859_1);
      assertTrue(atDq.contains(op), op + " at level 3");
    }
  }
}
