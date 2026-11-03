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

import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * The conversions to a floating lane and between the two (VARKA-343):
 * {@link VarkaReference#convert}, Java's own cast, at the boundary cells of {@code VARKA-343.md}
 * 2.4, and the four conversions the emitter lowers, {@code I2F}, {@code I2D}, {@code F2D} and
 * {@code D2F}, each one {@code convertShape} held bit for bit to it over every length, null
 * pattern and both bodies. Beside them the kernels of two lane counts these conversions make (2.2):
 * a 32-bit value in a 64-bit kernel, read from and stored to the low half of its species, and two
 * kinds of one width side by side, with their literal tables, their epilogue masks and a guard on
 * the other lane, whose mask crosses to the drive by {@code VectorMask.cast}.
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

  /** The long cells: the extremes and values past the int range. */
  private static final long[] LONGS = {0, 1, -1, (1L << 53) + 1, Long.MAX_VALUE, Long.MIN_VALUE,
      4275361147L, 1L << 40, -(1L << 40) - 3};

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
   * {@code roots} emitted at every width of their drive and compared with the reference over
   * every length and null pattern, both bodies; a batch must not decline. The int literals go
   * to the int table, which the int and float lanes share, and the long ones to the long table,
   * which the long and double lanes share. The drive is read at four lanes, a count every lane
   * has a species for: the configuration's own override may name none for a 64-bit lane
   * ({@code lanesOverride=16}), and every width below is an override of its own.
   */
  private static void check(String name, List<VarkaVectorIR> roots, int numInputs,
      int[] intLits, long[] longLits, Data data) {
    Optional<LaneType> planned =
        VarkaKernelPlan.driveOf(roots, VarkaMatrixRuns.BASE.withLanesOverride(4));
    assertTrue(planned.isPresent(), () -> name + ": "
        + VarkaKernelPlan.refusalOf(roots, VarkaMatrixRuns.BASE.withLanesOverride(4)));
    LaneType drive = planned.get();
    int[] widths = drive.bits() == Long.SIZE ? WIDE_LANES : NARROW_LANES;
    for (int lanes : widths) {
      var options = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
      var emitted = VarkaEmitterFixtures.emit(roots, numInputs,
          intLits.length + longLits.length, options);
      for (int length : LENGTHS) {
        for (int pattern = 0; pattern < PATTERNS.size(); pattern++) {
          String context = name + " at " + lanes + " " + drive + " lanes, length " + length
              + ", pattern " + pattern;
          assertTrue(VarkaKernelChecks.runAndCompareMixed(context, emitted.className(),
              emitted.bytes(), roots, numInputs, intLits, longLits,
              batch(numInputs, length, pattern, data), false, null), context);
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
    // The int literal goes to the int table, the double one to the long table.
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
    // Both lanes read the int table: slot 0 is the int lane's, slot 1 the float lane's bits.
    var f = new ColumnRef(1, LaneType.FLOAT);
    var root = new IntArith(IntOp.MUL, Overflow.WRAP, convert(new IntArith(IntOp.ADD,
        Overflow.WRAP, I, new LiteralSlot(0, LaneType.INT)), LaneType.FLOAT),
        new IntArith(IntOp.ADD, Overflow.WRAP, f, new LiteralSlot(1, LaneType.FLOAT)));
    check("cast(i + 7 AS float) * (f + 0.25)", List.of(root), 2,
        new int[] {7, Float.floatToRawIntBits(0.25f)}, new long[0], cells(INTS, FLOATS));
  }

  @Test
  @DisplayName("a float comparison over a converted int beside an int comparison, two roots")
  void comparisonOverConversion() {
    // The float comparison's mask crosses to the int drive by VectorMask.cast. An And of the two
    // is not buildable: its constructor asks for one lane, so they are two roots of one kernel.
    var overFloat =
        new Compare(CompareOp.GT, convert(I, LaneType.FLOAT), new LiteralSlot(0, LaneType.FLOAT));
    var overInt = new Compare(CompareOp.LT, I, new LiteralSlot(1, LaneType.INT));
    check("cast(i AS float) > 1.5, i < 1 << 30", List.of(overFloat, overInt), 1,
        new int[] {Float.floatToRawIntBits(1.5f), 1 << 30}, new long[0], cells(INTS));
  }

  @Test
  @DisplayName("a narrow column widened and converted beside a float column, in an int kernel")
  void narrowBesideFloat() {
    var s = new ColumnRef(0, LaneType.SHORT);
    var f = new ColumnRef(1, LaneType.FLOAT);
    var root = new IntArith(IntOp.MUL, Overflow.WRAP,
        convert(new WidenLane(s, LaneType.INT), LaneType.FLOAT), f);
    List<VarkaVectorIR> roots = List.of(root);
    // Without a baked int lane count (validityByWidth off) the widening load has no narrow
    // species to name, and the plan declines the kernel, as it does every narrow column's: the
    // row engine answers it. The emitter is never handed a refused plan.
    if (Lane.emitLanes(VarkaMatrixRuns.BASE, Lane.INT) == 0) {
      assertEquals(Optional.of(VarkaKernelPlan.NO_BAKED_INT_LANES),
          VarkaKernelPlan.refusalOf(roots, VarkaMatrixRuns.BASE));
      return;
    }
    long[] shorts = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -32767, 255};
    // A short column is stored two bytes a row, so the batch goes through the narrow layout.
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
          String context = "cast(s AS float) * f at " + lanes + " lanes, length " + length
              + ", pattern " + pattern;
          assertTrue(VarkaKernelChecks.runAndCompareNarrow(context, emitted.className(),
              emitted.bytes(), roots, 2, new int[0], new VarkaKernelChecks.Batch(length,
                  wide.nulls(), data, wide.forceMasked()), false, null), context);
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
  @DisplayName("a bigint narrowed to INT and converted, in a long kernel of int, float and double "
      + "values")
  void narrowedLongConverted() {
    // CAST(CAST(l AS INT) AS FLOAT) and its DOUBLE twin: L2I into the low half of the int
    // species, then I2F into the low half of the float one and I2D at the drive's lane count.
    var narrowed = new NarrowLane(L, LaneType.INT);
    check("cast(cast(l AS int) AS float), cast(cast(l AS int) AS double)",
        List.of(convert(narrowed, LaneType.FLOAT), convert(narrowed, LaneType.DOUBLE)), 1,
        new int[0], new long[0], cells(LONGS));
  }

  @Test
  @DisplayName("an ANSI division on the double lane of a long kernel condemns only by its divisor")
  void guardOnTheOtherLane() {
    // The division's guard mask is the double lane's, at the long drive's lane count, and crosses
    // to it by VectorMask.cast (the crossing `VARKA-341.md` 5.1 left owed). With no zero divisor
    // no batch may decline; one zero declines the batch where its row is valid, in the loop or
    // the epilogue, and not where it is null, so a crossed mask of no lanes or all fails here.
    var x = new ColumnRef(1, LaneType.DOUBLE);
    var root = new IntArith(IntOp.DIV, Overflow.FAIL,
        convert(new NarrowLane(L, LaneType.INT), LaneType.DOUBLE), x);
    long[] divisors = doubleBits(1.5, -2.0, 1e-300, Double.NaN, Double.POSITIVE_INFINITY, 3.0);
    var roots = List.<VarkaVectorIR>of(root, new IntArith(IntOp.ADD, Overflow.WRAP, L, L));
    check("cast(cast(l AS int) AS double) / x under ANSI", roots, 2, new int[0], new long[0],
        cells(LONGS, divisors));
    var emitted = VarkaEmitterFixtures.emit(roots, 2, 0, VarkaMatrixRuns.BASE.withLanesOverride(4));
    for (int at : new int[] {2, 16}) {
      var values = new long[2][17];
      for (int i = 0; i < 17; i++) {
        values[1][i] = Double.doubleToRawLongBits(i == at ? -0.0 : 1.0 + i);
      }
      var nulls = new boolean[2][17];
      var batch = new VarkaKernelChecks.LongBatch(17, nulls, values, false);
      int status = VarkaKernelChecks.runAndCompareMixedStatus("a zero at row " + at,
          emitted.className(), emitted.bytes(), roots, 2, new int[0], new long[0], batch, true);
      assertEquals(VarkaFusedKernel.STATUS_DIVIDE_BY_ZERO, status);
      nulls[1][at] = true; // the same batch, its zero now null
      assertEquals(List.of(0, VarkaFusedKernel.STATUS_DIVIDE_BY_ZERO), VarkaKernelChecks
          .runEachBodyMixedStatus("a null zero at row " + at, emitted.className(),
              emitted.bytes(), roots, 2, new int[0], new long[0], batch));
    }
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
  @DisplayName("a bigint condition as a value converts from the int lane, never by L2D or L2F")
  void longConditionThroughTheIntLane() {
    // CAST(l > 5 AS DOUBLE) as the compiler builds it: the long lane's one or zero narrowed to
    // the int lane, then I2D or I2F, never the L2D or L2F the plan refuses.
    var cond = new Compare(CompareOp.GT, L, new LiteralSlot(0, LaneType.LONG));
    for (LaneType target : List.of(LaneType.DOUBLE, LaneType.FLOAT)) {
      var root = convert(new NarrowLane(new CondValue(cond, LaneType.LONG), LaneType.INT),
          target);
      check("cast(l > 5 AS " + target + ")", List.of(root), 1, new int[0], new long[] {5L},
          cells(LONGS));
    }
    // The form it replaces, the long one or zero converted directly, is the refused one.
    assertTrue(VarkaKernelPlan.refusalOf(List.of(convert(new CondValue(cond, LaneType.LONG),
        LaneType.DOUBLE)), VarkaMatrixRuns.BASE).isPresent());
  }

  @Test
  @DisplayName("a narrow column converted to DOUBLE in a double kernel; its null test alone "
      + "declines there and fuses as a FLOAT")
  void narrowUnderDouble() {
    // CAST(y AS DOUBLE): the column loaded as a long kernel loads it, widened into the low half
    // of the int species (VARKA-332), then I2D. The load needs a baked lane count: without one
    // (validityByWidth off) the plan declines it, as it does every narrow load at 64 bits.
    var y = new ColumnRef(0, LaneType.SHORT);
    var x = new ColumnRef(1, LaneType.DOUBLE);
    var widened = new WidenLane(y, LaneType.INT);
    var value = new IntArith(IntOp.ADD, Overflow.WRAP, convert(widened, LaneType.DOUBLE), x);
    long[] shorts = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -32767, 255};
    if (VarkaMatrixRuns.BASE.validityByWidth()) {
      check("cast(y AS double) + x", List.of(value), 2, new int[0], new long[0],
          cells(shorts, DOUBLES));
    } else {
      assertEquals(Optional.of(VarkaKernelPlan.NO_BAKED_LONG_LANES),
          VarkaKernelPlan.refusalOf(List.of(value), VarkaMatrixRuns.BASE));
    }
    // CAST(y IS NOT NULL AS DOUBLE), the test's one or zero converted: only the long drive marks
    // a narrow column as read for its word alone, so the double plan declines it rather than
    // the emitter throwing; the FLOAT twin drives at the int lane, which serves it.
    var known = new CondValue(new VarkaVectorIR.IsNotNull(widened), LaneType.INT);
    Optional<String> refusal = VarkaKernelPlan.refusalOf(List.of(convert(known,
        LaneType.DOUBLE)), VarkaMatrixRuns.BASE);
    assertTrue(refusal.isPresent() && refusal.get().contains("DOUBLE kernel"),
        refusal.toString());
    assertEquals(LaneType.INT,
        VarkaKernelPlan.driveOf(List.of(convert(known, LaneType.FLOAT)), VarkaMatrixRuns.BASE)
            .orElseThrow());
    // A narrow node other than a column - here a narrowing - has no lowering at the double lane.
    var narrowed = convert(new WidenLane(new NarrowLane(I, LaneType.SHORT), LaneType.INT),
        LaneType.DOUBLE);
    refusal = VarkaKernelPlan.refusalOf(List.of(narrowed), VarkaMatrixRuns.BASE);
    assertTrue(refusal.isPresent() && refusal.get().contains("DOUBLE kernel"),
        refusal.toString());
  }

  @Test
  @DisplayName("a narrow column read for its validity alone in a 64-bit kernel of two lanes fuses "
      + "and answers, and so does a narrow value where a long lane count is baked")
  void narrowValidityBesideTwoLanes() {
    // A column read for its validity alone touches no species or table, so these emit and
    // answer at every width: a long kernel of int values, and one of int and double values.
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
    var converted = convert(new NarrowLane(l, LaneType.INT), LaneType.DOUBLE);
    check("cast(cast(l AS int) AS double) beside y IS NOT NULL", List.of(converted,
        new VarkaVectorIR.BoolOutput(known)), 3, new int[0], new long[0], data);
    // A narrow value in the same kernel is loaded at the long width (VARKA-332), which needs a
    // baked long lane count: without one (validityByWidth off) the plan declines it.
    List<VarkaVectorIR> value = List.of(converted,
        new IntArith(IntOp.ADD, Overflow.WRAP, new WidenLane(y, LaneType.LONG), l));
    if (!VarkaMatrixRuns.BASE.validityByWidth()) {
      assertEquals(Optional.of(VarkaKernelPlan.NO_BAKED_LONG_LANES),
          VarkaKernelPlan.refusalOf(value, VarkaMatrixRuns.BASE));
      return;
    }
    check("cast(cast(l AS int) AS double) beside y + l", value, 3, new int[0], new long[0], data);
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
    assertEquals(LaneType.LONG, VarkaKernelPlan.emissionLane(
        convert(new NarrowLane(L, LaneType.INT), LaneType.FLOAT), options));
    assertEquals(LaneType.DOUBLE,
        VarkaKernelPlan.emissionLane(convert(F, LaneType.DOUBLE), options));
    assertEquals(LaneType.DOUBLE,
        VarkaKernelPlan.emissionLane(convert(X, LaneType.FLOAT), options));
  }

  @Test
  @DisplayName("the plan refuses a bigint source and a floating one converted to an integral lane")
  void conversionRefusal() {
    for (var c : List.of(convert(L, LaneType.FLOAT), convert(L, LaneType.DOUBLE),
        convert(F, LaneType.INT), convert(F, LaneType.LONG), convert(X, LaneType.INT),
        convert(X, LaneType.LONG))) {
      var refusal = VarkaKernelPlan.of(List.of(c), VarkaMatrixRuns.BASE).refusal();
      assertTrue(refusal.isPresent(), VarkaVectorIR.canonical(c));
      assertEquals(VarkaKernelPlan.Kind.CONVERSION, refusal.get().kind());
    }
  }
}
