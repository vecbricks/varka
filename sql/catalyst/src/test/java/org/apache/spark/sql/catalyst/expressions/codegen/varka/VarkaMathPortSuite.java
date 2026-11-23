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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BiFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.LongBinaryOperator;
import java.util.function.UnaryOperator;

import io.github.vecbricks.vfdlibm.VFdlibm;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Col;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Output;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * The {@code PORT} form's frame and its ports (`VARKA-345.md` 2.2, 2.3, 3.2): the word helpers
 * of {@link VarkaMathPort} against their definitions on the raw bits, and each port's expansion,
 * {@code exp}'s, {@code expm1}'s, {@code log}'s and {@code log10}'s, emitted as a kernel, against
 * its {@code StrictMath} method and against the vector fdlibm library's {@code VFdlibm} method
 * and blended arm on the same inputs, bit for bit.
 *
 * <p>The library is {@code io.github.vecbricks:vfdlibm}, a test-scope dependency: the
 * reference and the oracle, never a kernel's run-time call. The sampled draws are 2.3's four over
 * {@code varka.math.samples} inputs a function, 2^24 by default; the fixed vectors are every
 * threshold of its C file and its 64 neighbours each way, with the special values, over null
 * patterns, lengths and three widths. Each of the library's planted faults, built into the
 * expansion, fails the differential.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaMathPortSuite {

  private static final int SAMPLES = Integer.getInteger("varka.math.samples", 1 << 24);

  private static final int BATCH = 1 << 14;

  private static final ColumnRef X = new ColumnRef(0, LaneType.DOUBLE);

  private static final ColumnRef Y = new ColumnRef(1, LaneType.LONG);

  /** A port's root with its literal table. */
  private record Built(VarkaVectorIR root, long[] lits) {}

  /**
   * A ported function: its expansion, its oracles ({@code StrictMath}'s method, {@code VFdlibm}'s
   * and the library's blended arm, reached by reflection since its class is package-private), its
   * fixed vectors, the range of draw 1, the domain where its result is finite and not a limit
   * and, for {@code expm1}, where {@code k} takes each of its forms ({@code |x|} up to 56 ln 2),
   * and the library's planted faults it is built with.
   */
  private record Fn(String name, BiFunction<VarkaMathPort, VarkaMathPort.Fault, VarkaVectorIR> port,
      DoubleUnaryOperator strict, UnaryOperator<DoubleVector> library, Method blended,
      long[] fixed, double lowest, double highest, List<VarkaMathPort.Fault> faults) {

    Built build(VarkaMathPort.Fault fault) {
      var table = new VarkaMathPort.Table();
      VarkaVectorIR root = port.apply(new VarkaMathPort(table), fault);
      return new Built(root, table.values());
    }

    Built build() {
      return build(VarkaMathPort.Fault.NONE);
    }
  }

  /**
   * The matrix's configuration under test with what a port's size needs put back: an option arm
   * of {@code maxChainDepth} 16 or 32, or of a small or zero {@code methodByteBudget}, declines
   * {@code exp} by design (36 levels, 68 ops), which its own tests below state. So does the arm
   * with {@code cse} off, which the port is written for: its words and reductions are shared
   * nodes, and without sharing the 68 distinct ops expand into methods of about 15 KB, over the
   * 8000-byte budget that HotSpot compiles (measured: {@code loopDense0} 14620 bytes).
   */
  private static VarkaEmitOptions portBase() {
    return VarkaMatrixRuns.BASE.withMaxChainDepth(VarkaEmitBudget.AUTO_CHAIN_DEPTH)
        .withMethodByteBudget(VarkaEmitOptions.DEFAULTS.methodByteBudget())
        .withCse(true);
  }

  private static Method blendedArm(String type, String method) {
    try {
      Method m = Class.forName("io.github.vecbricks.vfdlibm." + type)
          .getDeclaredMethod(method, DoubleVector.class, VectorMask.class);
      m.setAccessible(true);
      return m;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The three faults every port's library class plants. */
  private static final List<VarkaMathPort.Fault> FAULTS = List.of(
      VarkaMathPort.Fault.DROP_CORRECTION, VarkaMathPort.Fault.CONSTANT_OFF_BY_ONE_ULP,
      VarkaMathPort.Fault.THRESHOLD_MOVED);

  private static final Fn EXP = new Fn("exp", (p, f) -> p.exp(X, f), StrictMath::exp,
      VFdlibm::exp, blendedArm("Exp", "expBlended"), expFixedVectors(), -745.2, 709.8, FAULTS);

  private static final Fn EXPM1 = new Fn("expm1", (p, f) -> p.expm1(X, f), StrictMath::expm1,
      VFdlibm::expm1, blendedArm("Expm1", "expm1Blended"), expm1FixedVectors(), -38.8, 38.8,
      FAULTS);

  private static final Fn LOG = new Fn("log", (p, f) -> p.log(X, f), StrictMath::log,
      VFdlibm::log, blendedArm("Log", "logBlended"), logFixedVectors(), 0.0, 4.0, FAULTS);

  private static final Fn LOG10 = new Fn("log10", (p, f) -> p.log10(X, f), StrictMath::log10,
      VFdlibm::log10, blendedArm("Log10", "log10Blended"), logFixedVectors(), 0.0, 1e6,
      List.of(VarkaMathPort.Fault.DROP_CORRECTION, VarkaMathPort.Fault.CONSTANT_OFF_BY_ONE_ULP,
          VarkaMathPort.Fault.THRESHOLD_MOVED, VarkaMathPort.Fault.SUBNORMAL_THRESHOLD_MOVED));

  private static final List<Fn> FUNCTIONS = List.of(EXP, EXPM1, LOG, LOG10);

  private static String hex(double v) {
    return String.format(Locale.ROOT, "%s (0x%016x)", v, Double.doubleToRawLongBits(v));
  }

  // -------------------------------------------------------------------------------------------
  // Running a kernel
  // -------------------------------------------------------------------------------------------

  /** A kernel over the columns {@code X} and {@code Y}, loaded once and run batch by batch. */
  private static final class Kernel implements AutoCloseable {
    private final VarkaEmitterFixtures.Loaded loaded;
    private final long[] lits;

    Kernel(Built built, int lanes) {
      this(built, lanes == 0 ? portBase() : portBase().withLanesOverride(lanes));
    }

    Kernel(Built built, VarkaEmitOptions options) {
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

  private static boolean valid(Output out, int i) {
    return (out.validity().get(ValueLayout.JAVA_BYTE, i / 8) & (1 << (i % 8))) != 0;
  }

  // -------------------------------------------------------------------------------------------
  // The constants
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the ports' constants are the doubles whose words their C files' comments give")
  void constantsMatchTheirWords() {
    Map<Double, Long> words = new HashMap<>();
    words.put(VarkaMathPort.TWOM1000, 0x01700000_00000000L);
    words.put(VarkaMathPort.O_THRESHOLD, 0x40862E42_FEFA39EFL);
    words.put(VarkaMathPort.U_THRESHOLD, 0xc0874910_D52D3051L);
    words.put(VarkaMathPort.LN2HI_0, 0x3fe62e42_fee00000L);
    words.put(VarkaMathPort.LN2LO_0, 0x3dea39ef_35793c76L);
    words.put(VarkaMathPort.INVLN2, 0x3ff71547_652b82feL);
    words.put(VarkaMathPort.P1, 0x3FC55555_5555553EL);
    words.put(VarkaMathPort.P2, 0xBF66C16C_16BEBD93L);
    words.put(VarkaMathPort.P3, 0x3F11566A_AF25DE2CL);
    words.put(VarkaMathPort.P4, 0xBEBBBD41_C5D26BF1L);
    words.put(VarkaMathPort.P5, 0x3E663769_72BEA4D0L);
    words.put(VarkaMathPort.Q1, 0xBFA11111_111110F4L);
    words.put(VarkaMathPort.Q2, 0x3F5A01A0_19FE5585L);
    words.put(VarkaMathPort.Q3, 0xBF14CE19_9EAADBB7L);
    words.put(VarkaMathPort.Q4, 0x3ED0CFCA_86E65239L);
    words.put(VarkaMathPort.Q5, 0xBE8AFDB7_6E09C32DL);
    words.put(VarkaMathPort.TWO54, 0x43500000_00000000L);
    words.put(VarkaMathPort.LG1, 0x3FE55555_55555593L);
    words.put(VarkaMathPort.LG2, 0x3FD99999_9997FA04L);
    words.put(VarkaMathPort.LG3, 0x3FD24924_94229359L);
    words.put(VarkaMathPort.LG4, 0x3FCC71C5_1D8E78AFL);
    words.put(VarkaMathPort.LG5, 0x3FC74664_96CB03DEL);
    words.put(VarkaMathPort.LG6, 0x3FC39A09_D078C69FL);
    words.put(VarkaMathPort.LG7, 0x3FC2F112_DF3E5244L);
    words.put(VarkaMathPort.THIRD, 0x3fd55555_55555555L);
    words.put(VarkaMathPort.IVLN10, 0x3FDBCB7B_1526E50EL);
    words.put(VarkaMathPort.LOG10_2HI, 0x3FD34413_509F6000L);
    words.put(VarkaMathPort.LOG10_2LO, 0x3D59FEF3_11F12B36L);
    for (var e : words.entrySet()) {
      assertEquals(e.getValue(), Double.doubleToRawLongBits(e.getKey()), hex(e.getKey()));
    }
    assertEquals(0x3fe00000_00000000L, Double.doubleToRawLongBits(0.5));
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

  // -------------------------------------------------------------------------------------------
  // exp: the oracles
  // -------------------------------------------------------------------------------------------

  private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

  /**
   * The three references of {@code xs}' lanes, as raw bits: {@code fn}'s {@code StrictMath}
   * method, the library's {@code VFdlibm} method and its blended arm, the library over
   * {@code SPECIES} with the tail padded by zeros.
   */
  private static long[][] references(Fn fn, long[] xs, int length) {
    long[] strict = new long[length];
    long[] library = new long[length];
    long[] blended = new long[length];
    double[] in = new double[SPECIES.length()];
    double[] out = new double[SPECIES.length()];
    for (int base = 0; base < length; base += in.length) {
      for (int j = 0; j < in.length; j++) {
        in[j] = base + j < length ? Double.longBitsToDouble(xs[base + j]) : 0.0;
      }
      DoubleVector v = DoubleVector.fromArray(SPECIES, in, 0);
      fn.library().apply(v).intoArray(out, 0);
      double[] outBlended;
      try {
        outBlended = ((DoubleVector) fn.blended().invoke(null, v, null)).toArray();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
      for (int j = 0; j < in.length && base + j < length; j++) {
        strict[base + j] = Double.doubleToRawLongBits(fn.strict().applyAsDouble(in[j]));
        library[base + j] = Double.doubleToRawLongBits(out[j]);
        blended[base + j] = Double.doubleToRawLongBits(outBlended[j]);
      }
    }
    return new long[][] {strict, library, blended};
  }

  private static final String[] ORACLES = {"StrictMath", "VFdlibm", "the blended arm"};

  /** Adds a line per oracle {@code got} differs from at row {@code i}, up to ten in all. */
  private static void compare(Fn fn, String where, long x, long got, long[][] refs, int i,
      List<String> failures) {
    for (int o = 0; o < refs.length; o++) {
      if (refs[o][i] != got && failures.size() < 10) {
        failures.add(where + ": " + fn.name() + "(" + hex(Double.longBitsToDouble(x)) + ") "
            + ORACLES[o]
            + " " + hex(Double.longBitsToDouble(refs[o][i])) + ", kernel "
            + hex(Double.longBitsToDouble(got)));
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // The sampled differential (2.3 test 1)
  // -------------------------------------------------------------------------------------------

  /**
   * Draw {@code d}: uniform bit patterns; {@code fn}'s finite-result domain; {@code [-2, 2]}; and
   * magnitudes down to {@code 2^-60} in both signs.
   */
  private static long draw(Fn fn, int d, SplittableRandom rnd) {
    return switch (d) {
      case 0 -> rnd.nextLong();
      case 1 -> Double.doubleToRawLongBits(rnd.nextDouble(fn.lowest(), fn.highest()));
      case 2 -> Double.doubleToRawLongBits(rnd.nextDouble(-2.0, 2.0));
      case 3 -> Double.doubleToRawLongBits((rnd.nextBoolean() ? 1 : -1)
          * Math.scalb(1.0 + rnd.nextDouble(), -rnd.nextInt(61)));
      default -> throw new IllegalArgumentException("draw " + d);
    };
  }

  /** The kernel's mismatches against every oracle over the four draws, the first few listed. */
  private static long sampledMismatches(Fn fn, Built built, List<String> firstFew) {
    long mismatches = 0;
    var rnd = new SplittableRandom(0x345bL);
    try (Kernel k = new Kernel(built, 0); Arena arena = Arena.ofConfined()) {
      long[] xs = new long[BATCH];
      Col x = VarkaEmitterFixtures.makeLongInput(arena, BATCH, i -> false, i -> 0L, false);
      Col y = VarkaEmitterFixtures.makeLongInput(arena, BATCH, i -> false, i -> 0L, false);
      Output out = VarkaEmitterFixtures.makeLongOutput(arena, BATCH);
      int perDraw = Math.max(1, SAMPLES / 4 / BATCH);
      for (int d = 0; d < 4; d++) {
        for (int b = 0; b < perDraw; b++) {
          for (int i = 0; i < BATCH; i++) {
            xs[i] = draw(fn, d, rnd);
            x.data().set(ValueLayout.JAVA_LONG, i * 8L, xs[i]);
          }
          k.run(x, y, out, BATCH);
          long[][] refs = references(fn, xs, BATCH);
          for (int i = 0; i < BATCH; i++) {
            long got = out.data().get(ValueLayout.JAVA_LONG, i * 8L);
            int before = firstFew.size();
            var lines = new ArrayList<String>();
            compare(fn, "draw " + d, xs[i], got, refs, i, lines);
            if (!lines.isEmpty()) {
              mismatches++;
              if (before < 5) {
                firstFew.add(lines.get(0));
              }
            }
          }
        }
      }
    }
    return mismatches;
  }

  @Test
  @DisplayName("each port's kernel answers StrictMath's and vfdlibm's bits on every sampled input")
  void sampledDifferential() {
    for (Fn fn : FUNCTIONS) {
      var firstFew = new ArrayList<String>();
      long n = sampledMismatches(fn, fn.build(), firstFew);
      assertEquals(0L, n, fn.name() + ": " + n + " mismatches, " + firstFew);
    }
  }

  // -------------------------------------------------------------------------------------------
  // The fixed vectors (2.3 test 2)
  // -------------------------------------------------------------------------------------------

  private static double fromHi(int h) {
    return Double.longBitsToDouble(VarkaMathPort.fromHiBits(h));
  }

  /**
   * Every threshold of e_exp.c in both signs with its 64 neighbours each way - the filter, the
   * overflow and underflow bounds, {@code 0.5 ln2} and {@code 1.5 ln2}, {@code 2^-28}, the
   * subnormal scale-back's start near {@code -1021 ln2} - with the special values.
   */
  static long[] expFixedVectors() {
    double ln2 = Math.log(2.0);
    return fixedVectors(VarkaMathPort.O_THRESHOLD, VarkaMathPort.U_THRESHOLD,
        fromHi(VarkaMathPort.HX_FILTER), fromHi(VarkaMathPort.HX_HALF_LN2), fromHi(0x3FF0A2B2),
        fromHi(VarkaMathPort.HX_TINY), fromHi(VarkaMathPort.HX_NON_FINITE), -1021 * ln2,
        -1021.5 * ln2, 0.5 * ln2, 1.5 * ln2);
  }

  /**
   * Every threshold of s_expm1.c in both signs with its 64 neighbours each way - the filter's
   * {@code 56 ln2} and {@code 709.78}, {@code 0.5 ln2} and {@code 1.5 ln2}, {@code 2^-54} - and
   * the edges of {@code k}'s forms: {@code k = 1}'s split at {@code r = -0.25}, and the first
   * lanes of {@code k = 2}, {@code 20}, {@code 56} and {@code 57} - with the special values.
   */
  static long[] expm1FixedVectors() {
    double ln2 = Math.log(2.0);
    return fixedVectors(VarkaMathPort.O_THRESHOLD, fromHi(VarkaMathPort.HX_56_LN2),
        fromHi(VarkaMathPort.HX_FILTER), fromHi(VarkaMathPort.HX_HALF_LN2), fromHi(0x3FF0A2B2),
        fromHi(VarkaMathPort.HX_TINY_EXPM1), fromHi(VarkaMathPort.HX_NON_FINITE), ln2 - 0.25,
        1.5 * ln2, 19.5 * ln2, 55.5 * ln2, 56.5 * ln2);
  }

  /**
   * The words e_log.c and e_log10.c branch on, in both signs with their 64 neighbours each way:
   * the subnormal and non-finite bounds, {@code 1.0} and its neighbouring binades, the band of
   * {@code i > 0} ({@code 0x6147a} and {@code 0x6b851}), the {@code sqrt(2)} split
   * ({@code 0x6a09c}), the {@code |f| < 2^-20} words around {@code 1.0} and {@code 0.5}, and the
   * subnormals' bottom and top - with the special values. Both ports take the same vectors.
   */
  static long[] logFixedVectors() {
    return fixedVectors(fromHi(0x3ff00000 | (int) VarkaMathPort.HX_BAND_LO),
        fromHi(0x3ff00000 | (int) VarkaMathPort.HX_BAND_HI), fromHi(0x3ff6a09c),
        fromHi(0x3fe6a09c), fromHi(0x3ff00002), fromHi(0x3feffffe), fromHi(0x3fe00002),
        fromHi(0x3fdffffe), fromHi(0x000fffff), fromHi(0x7fefffff), 2.0, 0.5, 10.0, 0.1,
        Math.sqrt(2.0), 0x1p-1060);
  }

  /**
   * {@code edges} in both signs with their 64 neighbours each way, and SLEEF's special values with
   * their neighbours: NaNs of both signs and several payloads, both infinities,
   * {@code +-DBL_MAX}, {@code +-DBL_MIN}, {@code +-denorm_min}, both zeros and {@code +-1}.
   */
  private static long[] fixedVectors(double... thresholds) {
    var all = new ArrayList<Double>();
    for (double t : thresholds) {
      all.add(t);
    }
    for (double v : new double[] {Double.MAX_VALUE, Double.MIN_NORMAL, Double.MIN_VALUE, 0.0,
        1.0}) {
      all.add(v);
    }
    var bits = new ArrayList<Long>();
    for (double e : all) {
      for (double signed : new double[] {e, -e}) {
        long b = Double.doubleToRawLongBits(signed);
        for (int k = -64; k <= 64; k++) {
          bits.add(b + k);
        }
      }
    }
    for (long nan : new long[] {0x7ff8000000000000L, 0xfff8000000000000L, 0x7ff8000000000001L,
        0xfff800000000beefL, 0x7ff0000000000001L, 0xfff0000000000001L, 0x7fffffffffffffffL,
        0xffffffffffffffffL}) {
      bits.add(nan);
    }
    return bits.stream().mapToLong(Long::longValue).toArray();
  }

  private static final List<IntPredicate> NULL_PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  @Test
  @DisplayName("each port's kernel answers the oracles' bits on its fixed vectors, over nulls, "
      + "lengths, three widths")
  void fixedVectorsAnswerTheOracles() {
    var failures = new ArrayList<String>();
    for (Fn fn : FUNCTIONS) {
      fixedVectorFailures(fn, failures);
    }
    assertEquals(List.of(), failures);
  }

  private static void fixedVectorFailures(Fn fn, List<String> failures) {
    long[] vectors = fn.fixed();
    long[][] refs = references(fn, vectors, vectors.length);
    var variants = List.of(portBase().withLanesOverride(2), portBase().withLanesOverride(4),
        portBase().withLanesOverride(8));
    for (VarkaEmitOptions options : variants) {
      String lanes = options.canonical();
      try (Kernel k = new Kernel(fn.build(), options)) {
        for (int length : new int[] {1, 7, 33, vectors.length}) {
          for (int p = 0; p < NULL_PATTERNS.size(); p++) {
            IntPredicate isNull = NULL_PATTERNS.get(p);
            try (Arena arena = Arena.ofConfined()) {
              Col x = VarkaEmitterFixtures.makeLongInput(arena, length, isNull,
                  i -> vectors[i], true);
              Col y = VarkaEmitterFixtures.makeLongInput(arena, length, i -> false, i -> 0L,
                  false);
              Output out = VarkaEmitterFixtures.makeLongOutput(arena, length);
              k.run(x, y, out, length);
              for (int i = 0; i < length; i++) {
                String where = "options '" + lanes + "', length " + length + ", pattern " + p
                    + ", row " + i;
                if (valid(out, i) == isNull.test(i)) {
                  failures.add(where + ": validity " + valid(out, i));
                } else if (!isNull.test(i)) {
                  compare(fn, where, vectors[i],
                      out.data().get(ValueLayout.JAVA_LONG, i * 8L), refs, i, failures);
                }
              }
            }
          }
        }
      }
    }
  }

  private static int depth(VarkaVectorIR node, Map<VarkaVectorIR, Integer> memo) {
    Integer known = memo.get(node);
    if (known != null) {
      return known;
    }
    int max = -1;
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      max = Math.max(max, depth(child, memo));
    }
    int d = max + 1;
    memo.put(node, d);
    return d;
  }

  @Test
  @DisplayName("each port is deeper than a projection's cap, which its auto cap admits")
  void depthIsWithinThePortCap() {
    var defaults = VarkaEmitOptions.DEFAULTS;
    for (Fn fn : FUNCTIONS) {
      var root = fn.build().root();
      int d = depth(root, new HashMap<>());
      assertTrue(d > VarkaEmitBudget.MAX_CHAIN_DEPTH && d <= VarkaEmitBudget.PORT_MAX_CHAIN_DEPTH,
          "the " + fn.name() + " port's depth is " + d);
      assertEquals(VarkaEmitBudget.PORT_MAX_CHAIN_DEPTH,
          VarkaEmitBudget.chainDepthCap(defaults, root));
      assertEquals(16, VarkaEmitBudget.chainDepthCap(defaults.withMaxChainDepth(16), root));
      // The tree passes the compiler's mirror at the default and fails it at 16.
      assertTrue(VarkaLoopEmitter.fitsBudgets(List.of(root), 1, defaults));
      assertFalse(VarkaLoopEmitter.fitsBudgets(List.of(root), 1,
          defaults.withMaxChainDepth(16)));
    }
    // A tree without a port keeps the lane's cap.
    assertEquals(VarkaEmitBudget.MAX_CHAIN_DEPTH, VarkaEmitBudget.chainDepthCap(defaults, X));
  }

  @Test
  @DisplayName("a port stops at the port cap, 64 levels, and its bytes are far under the budget")
  void portStopsAtTheCap() {
    var table = new VarkaMathPort.Table();
    var port = new VarkaMathPort(table);
    VarkaVectorIR root = port.exp(X);
    int d = depth(root, new HashMap<>());
    int added = 0;
    var defaults = VarkaEmitOptions.DEFAULTS;
    // Add one level at a time until the compiler's mirror declines: the stop is the cap.
    while (true) {
      VarkaVectorIR next = VarkaMathPort.add(root, port.lit(1.0));
      if (!VarkaLoopEmitter.fitsBudgets(List.of(next), 1, defaults)) {
        break;
      }
      root = next;
      added++;
    }
    assertEquals(VarkaEmitBudget.PORT_MAX_CHAIN_DEPTH, d + added);
    // The deepest tree the cap admits emits, and its largest method is far under the byte budget.
    var emitted = VarkaEmitterFixtures.emit(List.of(root), 2, table.values().length, defaults);
    int largest = VarkaEmittedClass.measure(emitted.bytes()).codeLength().values().stream()
        .mapToInt(Integer::intValue).max().orElse(0);
    assertTrue(largest < defaults.methodByteBudget(), "largest method " + largest + " bytes");
    // One level more is declined by the emitter itself, with the cap's value in its name.
    VarkaVectorIR over = VarkaMathPort.add(root, port.lit(1.0));
    var e = assertThrows(IllegalArgumentException.class,
        () -> VarkaEmitterFixtures.emit(List.of(over), 2, table.values().length, defaults));
    assertTrue(e.getMessage().contains("MAX_CHAIN_DEPTH=64"), e.getMessage());
  }

  @Test
  @DisplayName("each port is declined, with the op cap's name, in the form without a byte "
      + "budget")
  void portsDeclinedWithoutAByteBudget() {
    var options = portBase().withMethodByteBudget(0);
    for (Fn fn : FUNCTIONS) {
      var built = fn.build();
      assertFalse(VarkaLoopEmitter.fitsBudgets(List.of(built.root()), 1, options), fn.name());
      var e = assertThrows(IllegalArgumentException.class, () -> VarkaEmitterFixtures.emit(
          List.of(built.root()), 2, built.lits().length, options), fn.name());
      assertTrue(e.getMessage().contains("MAX_FUSED_NODES=64"), e.getMessage());
    }
  }

  @Test
  @DisplayName("the reference evaluator answers StrictMath's bits on each port's tree")
  void referenceEvaluatesThePorts() {
    var failures = new ArrayList<String>();
    for (Fn fn : FUNCTIONS) {
      Built built = fn.build();
      long[] vectors = fn.fixed();
      var rnd = new SplittableRandom(0x345bL);
      for (int i = 0; i < vectors.length + 4000 && failures.size() < 10; i++) {
        long x = i < vectors.length ? vectors[i] : draw(fn, i % 4, rnd);
        var got = VarkaReference.mixedValue(built.root(), new long[] {x, 0L},
            new boolean[] {false, false}, new int[0], built.lits());
        long want = Double.doubleToRawLongBits(
            fn.strict().applyAsDouble(Double.longBitsToDouble(x)));
        if (got.isEmpty() || got.getAsLong() != want) {
          failures.add(fn.name() + "(" + hex(Double.longBitsToDouble(x)) + "): want "
              + hex(Double.longBitsToDouble(want)) + ", reference " + got);
        }
      }
      var none = VarkaReference.mixedValue(built.root(), new long[] {0L, 0L},
          new boolean[] {true, false}, new int[0], built.lits());
      assertTrue(none.isEmpty(), fn.name() + " of a null is null, not " + none);
    }
    assertEquals(List.of(), failures);
  }

  @Test
  @DisplayName("D2I answers Java's (int) of a double, NaN, the infinities and the int range's "
      + "edges included, at three widths")
  void d2iIsJavasCast() {
    var vectors = new ArrayList<Long>();
    for (double e : new double[] {0.0, 0.5, 1.0, 0x1p31, 0x1p31 - 1, 0x1p32, 1e300,
        Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
      for (double v : new double[] {e, -e}) {
        for (int k = -3; k <= 3; k++) {
          vectors.add(Double.doubleToRawLongBits(v) + k);
        }
      }
    }
    vectors.add(0x7ff8000000000000L);
    vectors.add(0xfff800000000beefL);
    // As a long value, so the int is read by a consumer, the way a port reads its k.
    VarkaVectorIR root = new VarkaVectorIR.WidenLane(
        new VarkaVectorIR.ConvertLane(X, LaneType.INT), LaneType.LONG);
    var failures = new ArrayList<String>();
    for (int lanes : new int[] {2, 4, 8}) {
      try (Kernel k = new Kernel(new Built(root, new long[0]), lanes);
           Arena arena = Arena.ofConfined()) {
        int n = vectors.size();
        Col x = VarkaEmitterFixtures.makeLongInput(arena, n, i -> false, vectors::get, false);
        Col y = VarkaEmitterFixtures.makeLongInput(arena, n, i -> false, i -> 0L, false);
        Output out = VarkaEmitterFixtures.makeLongOutput(arena, n);
        k.run(x, y, out, n);
        for (int i = 0; i < n; i++) {
          double v = Double.longBitsToDouble(vectors.get(i));
          long got = out.data().get(ValueLayout.JAVA_LONG, i * 8L);
          if (got != (int) v) {
            failures.add(lanes + " lanes: (int) " + hex(v) + " is " + (int) v + ", got " + got);
          }
        }
      }
    }
    assertEquals(List.of(), failures);
  }

  // -------------------------------------------------------------------------------------------
  // The planted faults
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("each of vfdlibm's planted faults, built into each expansion, fails the "
      + "differential")
  void plantedFaultsAreCaught() {
    for (Fn fn : FUNCTIONS) {
      plantedFaultsAreCaught(fn);
    }
  }

  private static void plantedFaultsAreCaught(Fn fn) {
    long[] vectors = fn.fixed();
    long[][] refs = references(fn, vectors, vectors.length);
    for (VarkaMathPort.Fault fault : fn.faults()) {
      long n = 0;
      try (Kernel k = new Kernel(fn.build(fault), 0); Arena arena = Arena.ofConfined()) {
        Col x = VarkaEmitterFixtures.makeLongInput(arena, vectors.length, i -> false,
            i -> vectors[i], false);
        Col y = VarkaEmitterFixtures.makeLongInput(arena, vectors.length, i -> false, i -> 0L,
            false);
        Output out = VarkaEmitterFixtures.makeLongOutput(arena, vectors.length);
        k.run(x, y, out, vectors.length);
        for (int i = 0; i < vectors.length; i++) {
          if (out.data().get(ValueLayout.JAVA_LONG, i * 8L) != refs[0][i]) {
            n++;
          }
        }
      }
      if (n == 0) {
        n = sampledMismatches(fn, fn.build(fault), new ArrayList<>());
      }
      assertTrue(n > 0, fn.name() + ": " + fault + " went unseen");
    }
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
