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
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.function.LongBinaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Col;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Output;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * The {@code PORT} form's frame (`VARKA-345.md` 2.2, 3.2): each word helper of
 * {@link VarkaMathPort}, emitted as a kernel, against its definition on the raw bits, over
 * {@code varka.math.samples} lanes a helper, 2^24 by default, and an eighth of that a compare.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaMathPortSuite {

  private static final int SAMPLES = Integer.getInteger("varka.math.samples", 1 << 24);

  private static final int BATCH = 1 << 14;

  private static final ColumnRef X = new ColumnRef(0, LaneType.DOUBLE);

  private static final ColumnRef Y = new ColumnRef(1, LaneType.LONG);

  /** A port's root with its literal table. */
  private record Built(VarkaVectorIR root, long[] lits) {}

  // -------------------------------------------------------------------------------------------
  // Running a kernel
  // -------------------------------------------------------------------------------------------

  /** A kernel over the columns {@code X} and {@code Y}, loaded once and run batch by batch. */
  private static final class Kernel implements AutoCloseable {
    private final VarkaEmitterFixtures.Loaded loaded;
    private final long[] lits;

    Kernel(Built built, int lanes) {
      VarkaEmitOptions options = lanes == 0 ? VarkaMatrixRuns.BASE
          : VarkaMatrixRuns.BASE.withLanesOverride(lanes);
      this.loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(
          List.of(built.root()), 2, built.lits().length, options));
      this.lits = built.lits();
    }

    /** Runs one batch; the output's bits and validity land in {@code out}. */
    void run(Col x, Col y, Output out, int length) {
      VarkaFusedKernel kernel = loaded.kernel();
      int status = kernel.run(new long[] {x.data().address(), y.data().address()},
          new long[] {x.validityAddress(length), y.validityAddress(length)},
          new int[] {x.nullCount(), y.nullCount()}, new long[] {out.data().address()},
          new long[] {out.validity().address()}, new int[0], lits, length,
          VarkaEmitterTestSupport.scratch(kernel, length));
      if (status != 0) {
        fail("the kernel declined a batch, status " + status);
      }
    }

    @Override
    public void close() {
      loaded.loader().release();
    }
  }

  // -------------------------------------------------------------------------------------------
  // The helpers (3.2)
  // -------------------------------------------------------------------------------------------

  /** A helper as a kernel root over {@code X} and {@code Y}, and its definition on the bits. */
  private record Helper(String name, VarkaVectorIR root, long[] lits, LongBinaryOperator def) {}

  private static Helper helper(String name, Function<VarkaMathPort, VarkaVectorIR> build,
      LongBinaryOperator def) {
    var table = new VarkaMathPort.Table();
    VarkaVectorIR root = build.apply(new VarkaMathPort(table));
    return new Helper(name, root, table.values(), def);
  }

  /** A condition as a long value, 1 or 0, so a compare is checked as a helper's lanes are. */
  private static VarkaVectorIR asLong(VarkaMathPort p, Cond c) {
    return VarkaMathPort.blend(c, p.lit(1L), p.lit(0L));
  }

  private static long hx(long bits) {
    return (bits & Long.MAX_VALUE) >>> 32;
  }

  /** The high words the compares are tried at: fdlibm's thresholds and the ends of the range. */
  private static final int[] THRESHOLDS = {0, 0x3e300000, 0x3fd62e42, 0x40862E42, 0x7fefffff,
      0x7ff00000, 0x7ff00001, 0x7fffffff};

  private static List<Helper> helpers() {
    long low = 0xffff_ffffL;
    var list = new ArrayList<Helper>();
    list.add(helper("hi", p -> p.hi(X), (x, y) -> x >>> 32));
    list.add(helper("lo", p -> p.lo(X), (x, y) -> x & low));
    list.add(helper("withHi", p -> p.withHi(X, Y), (x, y) -> (x & low) | (y << 32)));
    list.add(helper("withLo", p -> p.withLo(X, Y), (x, y) -> (x & ~low) | (y & low)));
    list.add(helper("fromHiLo", p -> p.fromHiLo(new VarkaVectorIR.BitOp(
        VarkaVectorIR.BitOpKind.USHR, Y, p.lit(7L)), Y), (x, y) -> ((y >>> 7) << 32) | (y & low)));
    list.add(helper("absBits", p -> p.absBits(VarkaMathPort.bits(X)),
        (x, y) -> x & Long.MAX_VALUE));
    list.add(helper("addExponent", p -> VarkaMathPort.bits(p.addExponent(X, Y)),
        (x, y) -> x + (y << 52)));
    for (int h : THRESHOLDS) {
      list.add(helper("hiBelow " + Integer.toHexString(h),
          p -> asLong(p, p.hiBelow(p.absBits(VarkaMathPort.bits(X)), h)),
          (x, y) -> hx(x) < h ? 1 : 0));
      list.add(helper("hiAtLeast " + Integer.toHexString(h),
          p -> asLong(p, p.hiAtLeast(p.absBits(VarkaMathPort.bits(X)), h)),
          (x, y) -> hx(x) >= h ? 1 : 0));
      if (h != 0x7fffffff) {
        list.add(helper("hiAbove " + Integer.toHexString(h),
            p -> asLong(p, p.hiAbove(p.absBits(VarkaMathPort.bits(X)), h)),
            (x, y) -> hx(x) > h ? 1 : 0));
      }
    }
    return list;
  }

  /**
   * The x lanes of the helpers' draw {@code i}: uniform bit patterns, each tested threshold's
   * high word and its two neighbours each way in both signs, and every exponent in both signs
   * with its word's neighbours, NaNs included.
   */
  private static long helperInput(SplittableRandom rnd, int i) {
    if (i % 4 == 0) {
      return rnd.nextLong();
    }
    if (i % 4 == 1) {
      long word = THRESHOLDS[rnd.nextInt(THRESHOLDS.length)] + rnd.nextInt(-2, 3);
      long sign = rnd.nextBoolean() ? Long.MIN_VALUE : 0L;
      return sign | ((word & 0x7fff_ffffL) << 32) | (rnd.nextLong() & 0xffff_ffffL);
    }
    long hiWord = ((long) rnd.nextInt(1 << 12) << 20) + rnd.nextInt(-2, 3);
    return (hiWord << 32) | (rnd.nextLong() & 0xffff_ffffL);
  }

  @Test
  @DisplayName("each word helper answers its definition on the raw bits of every sampled lane")
  void helpersMatchTheirDefinitions() {
    var failures = new ArrayList<String>();
    for (Helper h : helpers()) {
      // 2^24 lanes a word helper; a compare, of which there are 23, an eighth of that each.
      int batches = Math.max(1, SAMPLES / BATCH / (h.name().contains(" ") ? 8 : 1));
      var rnd = new SplittableRandom(0x345bL + h.name().hashCode());
      try (Kernel k = new Kernel(new Built(h.root(), h.lits()), 0);
           Arena arena = Arena.ofConfined()) {
        long[] xs = new long[BATCH];
        long[] ys = new long[BATCH];
        Col x = VarkaEmitterFixtures.makeLongInput(arena, BATCH, i -> false, i -> xs[i], false);
        Col y = VarkaEmitterFixtures.makeLongInput(arena, BATCH, i -> false, i -> ys[i], false);
        Output out = VarkaEmitterFixtures.makeLongOutput(arena, BATCH);
        for (int b = 0; b < batches && failures.size() < 10; b++) {
          for (int i = 0; i < BATCH; i++) {
            xs[i] = helperInput(rnd, i);
            ys[i] = rnd.nextInt(4) == 0 ? rnd.nextInt(-2100, 2100) : rnd.nextLong();
            x.data().set(ValueLayout.JAVA_LONG, i * 8L, xs[i]);
            y.data().set(ValueLayout.JAVA_LONG, i * 8L, ys[i]);
          }
          k.run(x, y, out, BATCH);
          for (int i = 0; i < BATCH; i++) {
            long got = out.data().get(ValueLayout.JAVA_LONG, i * 8L);
            long want = h.def().applyAsLong(xs[i], ys[i]);
            if (got != want && failures.size() < 10) {
              failures.add(String.format(Locale.ROOT, "%s(0x%016x, 0x%016x): want 0x%016x, got "
                  + "0x%016x", h.name(), xs[i], ys[i], want, got));
            }
          }
        }
      }
    }
    assertEquals(List.of(), failures);
  }

  @Test
  @DisplayName("a view keeps its child's null lanes and values, at the base width and at two and "
      + "four lanes")
  void viewsKeepTheirChildsNulls() {
    // `hi` reads the double column's bits at the long lane, so the view over a column with null
    // lanes is what is run: a null lane stays null and a valid one is the bits' high word.
    Helper h = helper("hi", p -> p.hi(X), (x, y) -> x >>> 32);
    int n = 1000;
    var rnd = new SplittableRandom(0x345b1L);
    long[] xs = new long[n];
    for (int i = 0; i < n; i++) {
      xs[i] = rnd.nextLong();
    }
    for (int lanes : new int[] {0, 2, 4}) {
      try (Kernel k = new Kernel(new Built(h.root(), h.lits()), lanes);
           Arena arena = Arena.ofConfined()) {
        Col x = VarkaEmitterFixtures.makeLongInput(arena, n, i -> i % 3 == 1, i -> xs[i], false);
        Col y = VarkaEmitterFixtures.makeLongInput(arena, n, i -> false, i -> 0L, false);
        Output out = VarkaEmitterFixtures.makeLongOutput(arena, n);
        k.run(x, y, out, n);
        for (int i = 0; i < n; i++) {
          int bit = out.validity().get(ValueLayout.JAVA_BYTE, i >> 3L) >> (i & 7);
          boolean valid = (bit & 1) != 0;
          int lane = i;
          assertEquals(i % 3 != 1, valid, () -> "validity of lane " + lane + " at " + lanes);
          if (valid) {
            assertEquals(xs[i] >>> 32, out.data().get(ValueLayout.JAVA_LONG, i * 8L),
                "value of lane " + i + " at " + lanes);
          }
        }
      }
    }
  }
}
