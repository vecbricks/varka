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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondValue;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * The conversions to a floating lane and between the two (VARKA-343, its first part): each
 * {@link ConvertLane} emitted as one {@code convertShape} and held, bit for bit, to
 * {@link VarkaReference#convert}, which is Java's own cast, over the boundary cells of
 * {@code VARKA-343.md} 2.4, the special values, every length and null pattern and both bodies;
 * and the kernels of two lanes these conversions make, a 32-bit value in a 64-bit kernel and two
 * kinds of one width side by side, with their literal tables, epilogue masks and a guard on the
 * other lane.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterFloatingCastSuite {

  private static final ColumnRef I = new ColumnRef(0, LaneType.INT);
  private static final ColumnRef L = new ColumnRef(0, LaneType.LONG);
  private static final ColumnRef F = new ColumnRef(0, LaneType.FLOAT);
  private static final ColumnRef X = new ColumnRef(0, LaneType.DOUBLE);

  /** The lane counts of a 64-bit drive's species, 128, 256 and 512 bits. */
  private static final int[] WIDE_LANES = {2, 4, 8};
  /** The lane counts of a 32-bit drive's species at the same widths. */
  private static final int[] NARROW_LANES = {4, 8, 16};
  private static final int[] LENGTHS = {1, 2, 7, 8, 9, 15, 16, 17, 33, 129};
  private static final List<IntPredicate> PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  /** The int cells: the float rounding boundaries and the extremes. */
  private static final long[] INTS = {0, 1, -1, (1 << 24) + 1, -((1 << 24) + 1), (1 << 24) + 3,
      Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE - 1, 16777217, 123456789,
      -987654321, 3, 1 << 30};

  /** The long cells: the double and float rounding boundaries, the extremes, 2.6's named case. */
  private static final long[] LONGS = {0, 1, -1, (1L << 53) + 1, -((1L << 53) + 1),
      (1L << 53) + (1L << 29) + 1, -((1L << 53) + (1L << 29) + 1), Long.MAX_VALUE,
      -Long.MAX_VALUE, Long.MIN_VALUE, 4275361147L, (1L << 24) + 1, (1L << 62) + 1,
      0x7FFFFFFFFFFFFC00L, 0x7FFFFFFFFFFFFDFFL, 1L << 40};

  /** The float cells as bits: NaNs, zeros, infinities, the extremes and the subnormals. */
  private static final long[] FLOATS = bitsOf(
      Float.NaN, -Float.NaN, 0.0f, -0.0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
      Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL,
      0.1f, -1.5f, 16777217.0f, 3.4e38f, 1.17e-38f);

  /**
   * The double cells as bits: the {@code D2F} cells of 2.4 (the half-ulp tie above
   * {@code Float.MAX_VALUE}, which rounds to infinity, and its neighbour below; the smallest
   * subnormal and its negation, which round to the two zeros; the float subnormals' range), and
   * the special values.
   */
  private static final long[] DOUBLES = doubleBits(
      Double.NaN, 0.0, -0.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
      Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE,
      maxFloatTie(), Math.nextDown(maxFloatTie()), -maxFloatTie(), (double) Float.MIN_VALUE,
      Float.MIN_VALUE / 2.0, Math.nextUp(Float.MIN_VALUE / 2.0), 0.1, 1.0 / 3.0, 1e300,
      Float.MIN_NORMAL * 0.75);

  private static long[] bitsOf(float... values) {
    var bits = new long[values.length];
    for (int k = 0; k < values.length; k++) {
      bits[k] = Float.floatToRawIntBits(values[k]);
    }
    return bits;
  }

  private static long[] doubleBits(double... values) {
    var bits = new long[values.length];
    for (int k = 0; k < values.length; k++) {
      bits[k] = Double.doubleToRawLongBits(values[k]);
    }
    return bits;
  }

  /** {@code Float.MAX_VALUE} plus half its ulp: the tie {@code D2F} rounds to infinity. */
  private static double maxFloatTie() {
    return (double) Float.MAX_VALUE + Math.ulp(Float.MAX_VALUE) / 2.0;
  }

  /** A column's data at row {@code i}: column {@code c} cycles through its cells. */
  private interface Data {
    long at(int c, int i);
  }

  private static Data cells(long[]... columns) {
    return (c, i) -> columns[c][(i * 7 + c * 3) % columns[c].length];
  }

  private static VarkaKernelChecks.LongBatch batch(int numInputs, int length, int pattern,
      Data data) {
    var nulls = new boolean[numInputs][length];
    var values = new long[numInputs][length];
    for (int c = 0; c < numInputs; c++) {
      IntPredicate p = PATTERNS.get((pattern + c) % PATTERNS.size());
      for (int i = 0; i < length; i++) {
        nulls[c][i] = p.test(i);
        values[c][i] = data.at(c, i);
      }
    }
    return new VarkaKernelChecks.LongBatch(length, nulls, values, length > 1 && pattern == 0);
  }

  /**
   * The base options at level 3, where a {@code BIGINT} conversion to a floating type is admitted:
   * the unknown level and every level below 3 decline it
   * ({@link VarkaEmitOptions#convertsMayFallBack()}), so a test of its lowering names the level.
   */
  private static final VarkaEmitOptions DQ =
      VarkaMatrixRuns.BASE.withUseAVX(VarkaEmitOptions.AVX512_LEVEL);

  /**
   * {@code roots} emitted at every width of their drive and compared with the reference over
   * every length and null pattern, both bodies; a batch must not decline.
   */
  private static void check(String name, List<VarkaVectorIR> roots, int numInputs,
      int[] intLits, long[] longLits, Data data) {
    check(name, roots, numInputs, intLits, longLits, data, VarkaMatrixRuns.BASE);
  }

  /** {@link #check(String, List, int, int[], long[], Data)} under {@code base}. */
  private static void check(String name, List<VarkaVectorIR> roots, int numInputs,
      int[] intLits, long[] longLits, Data data, VarkaEmitOptions base) {
    LaneType drive = VarkaKernelPlan.driveOf(roots, base).orElseThrow();
    int[] widths = drive.bits() == Long.SIZE ? WIDE_LANES : NARROW_LANES;
    for (int lanes : widths) {
      var options = base.withLanesOverride(lanes);
      var emitted = VarkaEmitterFixtures.emit(roots, numInputs,
          intLits.length + longLits.length, options);
      for (int length : LENGTHS) {
        for (int pattern = 0; pattern < PATTERNS.size(); pattern++) {
          String context = name + " at " + lanes + " " + drive + " lanes, length " + length
              + ", pattern " + pattern;
          VarkaKernelChecks.runAndCompareMixed(context, emitted.className(), emitted.bytes(),
              roots, numInputs, intLits, longLits, batch(numInputs, length, pattern, data),
              false, null);
        }
      }
    }
  }

  private static ConvertLane convert(VarkaVectorIR child, LaneType target) {
    return new ConvertLane(child, target);
  }

  // ---------------------------------------------------------------------------------------------
  // The definition the reference holds the kernels to
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the reference's conversions are Java's casts at the cells of VARKA-343 2.4")
  void referenceCells() {
    assertEquals((float) (1 << 24), toFloat(LaneType.INT, (1 << 24) + 1));
    assertEquals(0x1p31f, toFloat(LaneType.INT, Integer.MAX_VALUE));
    assertEquals(0x1p53, toDouble(LaneType.LONG, (1L << 53) + 1));
    // One rounding of the exact value, not two through the double: 2^53 + 2^30, where the
    // double route gives 2^53 (item 0.6).
    assertEquals(0x1p53f + 0x1p30f, toFloat(LaneType.LONG, (1L << 53) + (1L << 29) + 1));
    assertEquals(4275361024f, toFloat(LaneType.LONG, 4275361147L));
    assertEquals(0x1p63, toDouble(LaneType.LONG, Long.MAX_VALUE));
    assertEquals(-0x1p63f, toFloat(LaneType.LONG, Long.MIN_VALUE));
    assertEquals(Float.POSITIVE_INFINITY, Float.intBitsToFloat((int) VarkaReference.convert(
        LaneType.DOUBLE, LaneType.FLOAT, Double.doubleToRawLongBits(maxFloatTie()))));
    assertEquals(Float.MAX_VALUE, Float.intBitsToFloat((int) VarkaReference.convert(
        LaneType.DOUBLE, LaneType.FLOAT,
        Double.doubleToRawLongBits(Math.nextDown(maxFloatTie())))));
    assertEquals(Float.floatToRawIntBits(-0.0f), (int) VarkaReference.convert(LaneType.DOUBLE,
        LaneType.FLOAT, Double.doubleToRawLongBits(-Double.MIN_VALUE)));
    assertEquals(Float.floatToRawIntBits(0.0f), (int) VarkaReference.convert(LaneType.DOUBLE,
        LaneType.FLOAT, Double.doubleToRawLongBits(Double.MIN_VALUE)));
  }

  private static float toFloat(LaneType from, long v) {
    return Float.intBitsToFloat((int) VarkaReference.convert(from, LaneType.FLOAT, v));
  }

  private static double toDouble(LaneType from, long v) {
    return Double.longBitsToDouble(VarkaReference.convert(from, LaneType.DOUBLE, v));
  }

  // ---------------------------------------------------------------------------------------------
  // Each conversion as a root
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an int column to FLOAT, a kernel of the int and float lanes at one lane count")
  void intToFloat() {
    check("cast(i AS float)", List.of(convert(I, LaneType.FLOAT)), 1, new int[0], new long[0],
        cells(INTS));
  }

  @Test
  @DisplayName("an int column to DOUBLE, read from the low half of the int species")
  void intToDouble() {
    check("cast(i AS double)", List.of(convert(I, LaneType.DOUBLE)), 1, new int[0],
        new long[0], cells(INTS));
  }

  @Test
  @DisplayName("a bigint column to FLOAT, rounded once and stored from the low half")
  void longToFloat() {
    check("cast(l AS float)", List.of(convert(L, LaneType.FLOAT)), 1, new int[0], new long[0],
        cells(LONGS), DQ);
  }

  @Test
  @DisplayName("a bigint column to DOUBLE, a kernel of the long and double lanes")
  void longToDouble() {
    check("cast(l AS double)", List.of(convert(L, LaneType.DOUBLE)), 1, new int[0], new long[0],
        cells(LONGS), DQ);
  }

  @Test
  @DisplayName("a float column to DOUBLE, exact, NaN payloads and both zeros kept")
  void floatToDouble() {
    check("cast(f AS double)", List.of(convert(F, LaneType.DOUBLE)), 1, new int[0], new long[0],
        cells(FLOATS));
  }

  @Test
  @DisplayName("a double column to FLOAT at the overflow tie, the zeros and the float subnormals")
  void doubleToFloat() {
    check("cast(x AS float)", List.of(convert(X, LaneType.FLOAT)), 1, new int[0], new long[0],
        cells(DOUBLES));
  }

  // ---------------------------------------------------------------------------------------------
  // Kernels of two lanes
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a converted int beside a double column and a double literal, in a double kernel")
  void intBesideDouble() {
    var x = new ColumnRef(1, LaneType.DOUBLE);
    var root = new IntArith(IntOp.ADD, Overflow.WRAP, convert(new IntArith(IntOp.MUL,
        Overflow.WRAP, I, new LiteralSlot(0, LaneType.INT)), LaneType.DOUBLE),
        new IntArith(IntOp.MUL, Overflow.WRAP, x, new LiteralSlot(0, LaneType.DOUBLE)));
    check("cast(i * 3 AS double) + x * 0.5", List.of(root), 2, new int[] {3},
        new long[] {Double.doubleToRawLongBits(0.5)}, cells(INTS, DOUBLES));
  }

  @Test
  @DisplayName("a converted int beside a float column and a float literal, in an int kernel")
  void intBesideFloat() {
    var f = new ColumnRef(1, LaneType.FLOAT);
    var root = new IntArith(IntOp.MUL, Overflow.WRAP, convert(new IntArith(IntOp.ADD,
        Overflow.WRAP, I, new LiteralSlot(0, LaneType.INT)), LaneType.FLOAT),
        new IntArith(IntOp.ADD, Overflow.WRAP, f, new LiteralSlot(1, LaneType.FLOAT)));
    check("cast(i + 7 AS float) * (f + 0.25)", List.of(root), 2,
        new int[] {7, Float.floatToRawIntBits(0.25f)}, new long[0], cells(INTS, FLOATS));
  }

  @Test
  @DisplayName("a converted bigint beside a double column, in a long kernel, with a double pick")
  void longBesideDouble() {
    var x = new ColumnRef(1, LaneType.DOUBLE);
    var lx = convert(L, LaneType.DOUBLE);
    var root = new IfElse(new Compare(CompareOp.GT, x, new LiteralSlot(1, LaneType.DOUBLE)),
        new IntArith(IntOp.SUB, Overflow.WRAP, lx, x), lx);
    check("IF(x > 1.0, cast(l AS double) - x, cast(l AS double))",
        List.of(root, new IntArith(IntOp.ADD, Overflow.WRAP, L, new LiteralSlot(0,
            LaneType.LONG))), 2, new int[0], new long[] {5L, Double.doubleToRawLongBits(1.0)},
        cells(LONGS, DOUBLES), DQ);
  }

  @Test
  @DisplayName("a converted bigint beside a float column, in a long kernel of float values")
  void floatBesideLong() {
    var f = new ColumnRef(1, LaneType.FLOAT);
    var root = new IntArith(IntOp.ADD, Overflow.WRAP, convert(L, LaneType.FLOAT), f);
    check("cast(l AS float) + f", List.of(root), 2, new int[0], new long[0],
        cells(LONGS, FLOATS), DQ);
  }

  @Test
  @DisplayName("a narrow column widened and converted beside a float column, in an int kernel")
  void narrowBesideFloat() {
    var s = new ColumnRef(0, LaneType.SHORT);
    var f = new ColumnRef(1, LaneType.FLOAT);
    var root = new IntArith(IntOp.MUL, Overflow.WRAP,
        convert(new WidenLane(s, LaneType.INT), LaneType.FLOAT), f);
    long[] shorts = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -32767, 255};
    // A short column is stored two bytes a row, so the batch goes through the narrow layout.
    List<VarkaVectorIR> roots = List.of(root);
    for (int lanes : NARROW_LANES) {
      var emitted = VarkaEmitterFixtures.emit(roots, 2, 0,
          VarkaMatrixRuns.BASE.withLanesOverride(lanes));
      for (int length : LENGTHS) {
        for (int pattern = 0; pattern < PATTERNS.size(); pattern++) {
          var wide = batch(2, length, pattern, cells(shorts, FLOATS));
          var data = new int[2][length];
          for (int c = 0; c < 2; c++) {
            for (int i = 0; i < length; i++) {
              data[c][i] = (int) wide.data()[c][i];
            }
          }
          assertTrue(VarkaKernelChecks.runAndCompareNarrow("cast(s AS float) * f at " + lanes
              + " lanes, length " + length + ", pattern " + pattern, emitted.className(),
              emitted.bytes(), roots, 2, new int[0], new VarkaKernelChecks.Batch(length,
                  wide.nulls(), data, wide.forceMasked()), false, null));
        }
      }
    }
  }

  @Test
  @DisplayName("a double computed and narrowed to FLOAT beside a float column, in a double kernel")
  void doubleToFloatBesideFloat() {
    var f = new ColumnRef(1, LaneType.FLOAT);
    var root = new IntArith(IntOp.ADD, Overflow.WRAP, convert(new IntArith(IntOp.MUL,
        Overflow.WRAP, X, X), LaneType.FLOAT), f);
    check("cast(x * x AS float) + f", List.of(root, convert(f, LaneType.DOUBLE)), 2, new int[0],
        new long[0], cells(DOUBLES, FLOATS));
  }

  @Test
  @DisplayName("an ANSI division on the double lane of a long kernel condemns only by its divisor")
  void guardOnTheOtherLane() {
    var x = new ColumnRef(1, LaneType.DOUBLE);
    var root = new IntArith(IntOp.DIV, Overflow.FAIL, convert(L, LaneType.DOUBLE), x);
    // No zero divisor in the data, so no batch may decline: a mask crossed wrongly to the long
    // lane would condemn one.
    long[] divisors = doubleBits(1.5, -2.0, 1e-300, Double.NaN, Double.POSITIVE_INFINITY, 3.0);
    check("cast(l AS double) / x under ANSI",
        List.of(root, new IntArith(IntOp.ADD, Overflow.WRAP, L, L)), 2, new int[0], new long[0],
        cells(LONGS, divisors), DQ);
  }

  @Test
  @DisplayName("a filter of a double comparison over a converted float beside a double one")
  void filterOverConversions() {
    var x = new ColumnRef(0, LaneType.DOUBLE);
    var f = new ColumnRef(1, LaneType.FLOAT);
    var x2 = new ColumnRef(2, LaneType.DOUBLE);
    var i = new ColumnRef(3, LaneType.INT);
    var zero = new LiteralSlot(0, LaneType.DOUBLE);
    var filter = new VarkaVectorIR.And(new Compare(CompareOp.LT, x, zero),
        new Compare(CompareOp.EQ, convert(f, LaneType.DOUBLE), x2));
    check("x < 0.0 AND cast(f AS double) = x2", List.of(filter), 3, new int[0],
        new long[] {Double.doubleToRawLongBits(0.0)}, cells(DOUBLES, FLOATS, DOUBLES));
    var withInt = new VarkaVectorIR.And(new Compare(CompareOp.LT, x, zero),
        new Compare(CompareOp.EQ, convert(i, LaneType.DOUBLE), x2));
    check("x < 0.0 AND cast(i AS double) = x2", List.of(withInt), 4, new int[0],
        new long[] {Double.doubleToRawLongBits(0.0)}, cells(DOUBLES, FLOATS, DOUBLES, INTS));
  }

  @Test
  @DisplayName("a condition as a value, converted: CAST(i > 3 AS DOUBLE) over a double kernel")
  void conditionToDouble() {
    var cond = new Compare(CompareOp.GT, I, new LiteralSlot(0, LaneType.INT));
    check("cast(i > 3 AS double)", List.of(convert(new CondValue(cond, LaneType.INT),
        LaneType.DOUBLE)), 1, new int[] {3}, new long[0], cells(INTS));
    check("cast(i > 3 AS float)", List.of(convert(new CondValue(cond, LaneType.INT),
        LaneType.FLOAT)), 1, new int[] {3}, new long[0], cells(INTS));
  }

  @Test
  @DisplayName("a bigint condition as a value converts from the int lane, so it fuses where "
      + "convertsFallBack holds")
  void longConditionThroughTheIntLane() {
    // CAST(l > 5 AS DOUBLE) as the compiler builds it: the long lane's one or zero narrowed to
    // the int lane, then I2D or I2F, never the L2D or L2F a host without AVX512DQ refuses.
    var cond = new Compare(CompareOp.GT, L, new LiteralSlot(0, LaneType.LONG));
    var avx2 = VarkaMatrixRuns.BASE.withUseAVX(2);
    assertTrue(avx2.convertsFallBack());
    for (LaneType target : List.of(LaneType.DOUBLE, LaneType.FLOAT)) {
      var root = convert(new NarrowLane(new CondValue(cond, LaneType.LONG), LaneType.INT),
          target);
      assertEquals(Optional.empty(), VarkaKernelPlan.refusalOf(List.of(root), avx2));
      check("cast(l > 5 AS " + target + ")", List.of(root), 1, new int[0], new long[] {5L},
          cells(LONGS));
    }
    // The form it replaces, the long one or zero converted directly, is the refused one.
    assertTrue(VarkaKernelPlan.refusalOf(List.of(convert(new CondValue(cond, LaneType.LONG),
        LaneType.DOUBLE)), avx2).isPresent());
  }

  @Test
  @DisplayName("a narrow column read for its validity alone declines under a floating drive, "
      + "where the long drive serves it")
  void validityOnlyNarrowUnderFloating() {
    // CAST(y IS NOT NULL AS DOUBLE): the test lifted to the int lane, its one or zero converted.
    // The double emission admits no narrow node, so the plan declines it rather than the
    // emitter throwing; the float twin drives at the int lane, which widens the column.
    var y = new ColumnRef(0, LaneType.SHORT);
    var known = new CondValue(new VarkaVectorIR.IsNotNull(new WidenLane(y, LaneType.INT)),
        LaneType.INT);
    Optional<String> refusal = VarkaKernelPlan.refusalOf(List.of(convert(known,
        LaneType.DOUBLE)), VarkaMatrixRuns.BASE);
    assertTrue(refusal.isPresent() && refusal.get().contains("DOUBLE kernel"),
        refusal.toString());
    assertEquals(LaneType.INT,
        VarkaKernelPlan.driveOf(List.of(convert(known, LaneType.FLOAT)), VarkaMatrixRuns.BASE)
            .orElseThrow());
  }

  // ---------------------------------------------------------------------------------------------
  // The sweeps of 3.1: every 32-bit source
  // ---------------------------------------------------------------------------------------------

  private static final int CHUNK = 1 << 16;

  /**
   * Every {@code k}-th chunk of the 2^32 values of a 32-bit column through {@code root}, a kernel
   * over that one column, each row held to {@code want}, Java's cast, bit for bit. A 64-bit
   * result runs through the long entry point and is read eight bytes a row; a 32-bit one through
   * the int entry point, four.
   */
  private static void sweep(String ctx, VarkaVectorIR root, int every, IntToLongFunction want) {
    boolean wide = root.laneType().bits() == Long.SIZE;
    var emitted = VarkaEmitterFixtures.emit(List.of(root), 1, 0, VarkaMatrixRuns.BASE);
    var loaders = new ConcurrentLinkedQueue<VarkaGeneratedClassLoader>();
    ThreadLocal<VarkaFusedKernel> kernels = ThreadLocal.withInitial(() -> {
      var loaded = VarkaEmitterFixtures.load(emitted);
      loaders.add(loaded.loader());
      return loaded.kernel();
    });
    var failure = new AtomicReference<String>();
    long chunks = (1L << 32) / CHUNK;
    try {
      LongStream.range(0, chunks).filter(k -> k % every == 0).parallel().forEach(k -> {
        if (failure.get() != null) {
          return;
        }
        long first = k * CHUNK;
        VarkaFusedKernel kernel = kernels.get();
        try (Arena arena = Arena.ofConfined()) {
          MemorySegment in = VarkaEmitterFixtures.alloc(arena, CHUNK * 4L);
          for (int i = 0; i < CHUNK; i++) {
            in.set(ValueLayout.JAVA_INT, i * 4L, (int) (first + i));
          }
          var out = wide ? VarkaEmitterFixtures.makeLongOutput(arena, CHUNK)
              : VarkaEmitterFixtures.makeOutput(arena, CHUNK, -1L);
          long[] src = {in.address()};
          long[] dst = {out.data().address()};
          long[] validity = {out.validity().address()};
          long scratch = VarkaEmitterTestSupport.scratch(kernel, CHUNK);
          int status = wide
              ? kernel.run(src, new long[] {0L}, new int[] {0}, dst, validity, new int[0],
                  new long[0], CHUNK, scratch)
              : kernel.run(src, new long[] {0L}, new int[] {0}, dst, validity, new int[0], CHUNK,
                  scratch);
          if (status != 0) {
            failure.compareAndSet(null, ctx + ": status " + status + " at " + first);
          }
          for (int i = 0; i < CHUNK && failure.get() == null; i++) {
            int value = (int) (first + i);
            long got = wide ? out.data().get(ValueLayout.JAVA_LONG, i * 8L)
                : out.data().get(ValueLayout.JAVA_INT, i * 4L);
            long expected = want.applyAsLong(value);
            if (got != expected) {
              failure.compareAndSet(null, String.format(
                  "%s: over %08x the kernel gave %x and Java's cast %x", ctx, value, got,
                  expected));
            }
          }
        }
      });
    } finally {
      for (VarkaGeneratedClassLoader loader : loaders) {
        loader.release();
      }
    }
    if (failure.get() != null) {
      fail(failure.get());
    }
  }

  /** The three sweeps of a 32-bit source, every {@code every}-th chunk of each. */
  private static void sweeps(int every) {
    sweep("cast(i AS float)", convert(I, LaneType.FLOAT), every,
        v -> Float.floatToRawIntBits((float) v));
    sweep("cast(i AS double)", convert(I, LaneType.DOUBLE), every,
        v -> Double.doubleToRawLongBits((double) v));
    // F2D keeps every NaN's payload, so its bits are compared too.
    sweep("cast(f AS double)", convert(F, LaneType.DOUBLE), every,
        v -> Double.doubleToRawLongBits((double) Float.intBitsToFloat(v)));
  }

  @Test
  @DisplayName("every 256th chunk of the INT values to FLOAT and DOUBLE, and of the FLOAT "
      + "patterns to DOUBLE, is Java's cast")
  void sweepSlices() {
    sweeps(256);
  }

  @Test
  @DisplayName("every INT value to FLOAT and DOUBLE, and every FLOAT pattern to DOUBLE, is "
      + "Java's cast (opt-in: -Dvarka.sweep=true)")
  void sweepsWhole() {
    assumeTrue("true".equals(System.getProperty("varka.sweep")),
        "set -Dvarka.sweep=true to sweep the emitted kernels");
    sweeps(1);
  }

  // ---------------------------------------------------------------------------------------------
  // The rules
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a tree drives at 64 bits if it holds a 64-bit value, the long lane before double")
  void widthRule() {
    var options = VarkaEmitOptions.DEFAULTS;
    assertEquals(LaneType.DOUBLE,
        VarkaKernelPlan.emissionLane(convert(I, LaneType.DOUBLE), options));
    assertEquals(LaneType.INT, VarkaKernelPlan.emissionLane(convert(I, LaneType.FLOAT), options));
    assertEquals(LaneType.LONG, VarkaKernelPlan.emissionLane(convert(L, LaneType.FLOAT), options));
    assertEquals(LaneType.LONG,
        VarkaKernelPlan.emissionLane(convert(L, LaneType.DOUBLE), options));
    assertEquals(LaneType.DOUBLE,
        VarkaKernelPlan.emissionLane(convert(F, LaneType.DOUBLE), options));
    assertEquals(LaneType.DOUBLE,
        VarkaKernelPlan.emissionLane(convert(X, LaneType.FLOAT), options));
  }

  @Test
  @DisplayName("the conversion refuses an integral pair, an identity and a narrow side")
  void refusals() {
    assertThrows(IllegalArgumentException.class, () -> convert(I, LaneType.LONG));
    assertThrows(IllegalArgumentException.class, () -> convert(X, LaneType.DOUBLE));
    assertThrows(IllegalArgumentException.class,
        () -> convert(new ColumnRef(0, LaneType.SHORT), LaneType.FLOAT));
    assertThrows(IllegalArgumentException.class,
        () -> new NarrowLane(convert(I, LaneType.FLOAT), LaneType.BYTE));
  }

  @Test
  @DisplayName("a bigint conversion declines on a host without AVX512DQ, and no other does")
  void hostRefusal() {
    var avx2 = VarkaMatrixRuns.BASE.withUseAVX(2);
    for (LaneType target : List.of(LaneType.FLOAT, LaneType.DOUBLE)) {
      Optional<String> refusal = VarkaKernelPlan.refusalOf(List.of(convert(L, target)), avx2);
      assertTrue(refusal.isPresent() && refusal.get().contains("AVX512DQ"), refusal.toString());
      assertTrue(VarkaKernelPlan.refusalOf(List.of(convert(I, target)), avx2).isEmpty());
    }
    assertTrue(VarkaKernelPlan.refusalOf(List.of(convert(X, LaneType.FLOAT)), avx2).isEmpty());
    assertTrue(VarkaKernelPlan.refusalOf(List.of(convert(L, LaneType.DOUBLE)),
        VarkaMatrixRuns.BASE.withUseAVX(3)).isEmpty());
  }

  @Test
  @DisplayName("an unknown level declines a bigint conversion, and keeps the division's "
      + "conversion form")
  void unknownLevelRefusal() {
    // The defaults' level, which a session with the level unset runs at on every host: an AVX2
    // host there would run L2D and L2F as the Vector API's Java loop, so the plan declines them.
    // The division still takes the conversion form there, so no other shape's bytes move.
    var unknown = VarkaEmitOptions.DEFAULTS;
    assertEquals(VarkaEmitOptions.USE_AVX_UNKNOWN, unknown.useAVX());
    assertTrue(unknown.convertsMayFallBack());
    assertTrue(!unknown.convertsFallBack());
    for (LaneType target : List.of(LaneType.FLOAT, LaneType.DOUBLE)) {
      Optional<VarkaKernelPlan.Refusal> refusal =
          VarkaKernelPlan.of(List.of(convert(L, target)), unknown).refusal();
      assertEquals(Optional.of(VarkaKernelPlan.Kind.CONVERSION),
          refusal.map(VarkaKernelPlan.Refusal::kind), refusal.toString());
      assertTrue(VarkaKernelPlan.refusalOf(List.of(convert(I, target)), unknown).isEmpty());
    }
  }

  @Test
  @DisplayName("a narrow value under a double kernel declines: the narrow load at 64 bits is "
      + "unbuilt")
  void narrowUnderDouble() {
    var y = new ColumnRef(0, LaneType.SHORT);
    Optional<String> refusal = VarkaKernelPlan.refusalOf(
        List.of(convert(new WidenLane(y, LaneType.INT), LaneType.DOUBLE)), VarkaMatrixRuns.BASE);
    assertTrue(refusal.isPresent(), "a SMALLINT read by a double kernel");
  }

  @Test
  @DisplayName("a narrow column read for its validity alone in a 64-bit kernel of two lanes fuses "
      + "and answers; a narrow value there declines")
  void narrowValidityBesideTwoLanes() {
    // The plan names a narrow column's own lane for it in a 64-bit kernel of several lanes, a
    // lane with no species or literal table there. Only a column read for its validity alone
    // reaches that: the long drive's narrow refusal declines a narrow value, and a floating drive
    // declines every narrow node. A validity read touches no species or table, so these emit
    // and answer: a long kernel of int values, and one of double values.
    var y = new ColumnRef(0, LaneType.SHORT);
    var i = new ColumnRef(1, LaneType.INT);
    var l = new ColumnRef(2, LaneType.LONG);
    var known = new VarkaVectorIR.IsNotNull(new WidenLane(y, LaneType.LONG));
    long[] shorts = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345};
    Data data = cells(shorts, INTS, LONGS);
    check("IF(y IS NOT NULL, i, l)", List.of(new IfElse(known, new WidenLane(i, LaneType.LONG),
        l)), 3, new int[0], new long[0], data);
    check("y IS NOT NULL OR i > l", List.of(new VarkaVectorIR.BoolOutput(new VarkaVectorIR.Or(
        known, new Compare(CompareOp.GT, new WidenLane(i, LaneType.LONG), l)))), 3, new int[0],
        new long[0], data);
    check("cast(l AS double) beside y IS NOT NULL", List.of(convert(l, LaneType.DOUBLE),
        new VarkaVectorIR.BoolOutput(known)), 3, new int[0], new long[0], data, DQ);
    // A narrow value in the same kernel declines in the plan, before any lane is named for it.
    Optional<String> value = VarkaKernelPlan.refusalOf(List.of(convert(l, LaneType.DOUBLE),
        new IntArith(IntOp.ADD, Overflow.WRAP, new WidenLane(y, LaneType.LONG), l)), DQ);
    assertTrue(value.isPresent() && value.get().contains("bigint kernel"), value.toString());
  }
}
