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
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

/**
 * The conversions to a floating lane (VARKA-343): {@link VarkaReference#convert}, Java's own
 * cast, at the boundary cells of {@code VARKA-343.md} 2.4, and the one conversion this part
 * emits, {@code I2F}, as one {@code convertShape} held bit for bit to it over every length, null
 * pattern and both bodies, in a kernel of the int and float lanes at one lane count, with the
 * int literal table both lanes share and a float mask crossed to the int drive.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterFloatingCastSuite {

  private static final ColumnRef I = new ColumnRef(0, LaneType.INT);
  private static final ColumnRef L = new ColumnRef(0, LaneType.LONG);
  private static final ColumnRef F = new ColumnRef(0, LaneType.FLOAT);
  private static final ColumnRef X = new ColumnRef(0, LaneType.DOUBLE);

  /** The lane counts of a 32-bit drive's species at 128, 256 and 512 bits. */
  private static final int[] LANES = {4, 8, 16};
  private static final int[] LENGTHS = {1, 2, 7, 8, 9, 15, 16, 17, 33, 129};
  private static final List<IntPredicate> PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  /** The int cells: the float rounding boundaries and the extremes. */
  private static final long[] INTS = {0, 1, -1, (1 << 24) + 1, -((1 << 24) + 1), (1 << 24) + 3,
      Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE - 1, 16777217, 123456789,
      -987654321, 3, 1 << 30};

  /** The float cells as bits: NaNs, zeros, infinities, the extremes and the subnormals. */
  private static final long[] FLOATS = bitsOf(
      Float.NaN, -Float.NaN, 0.0f, -0.0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
      Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL,
      0.1f, -1.5f, 16777217.0f, 3.4e38f, 1.17e-38f);

  private static long[] bitsOf(float... values) {
    var bits = new long[values.length];
    for (int k = 0; k < values.length; k++) {
      bits[k] = Float.floatToRawIntBits(values[k]);
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
   * {@code roots}, which must drive at the int lane, emitted at every width and compared with the
   * reference over every length and null pattern, both bodies; a batch must not decline.
   */
  private static void check(String name, List<VarkaVectorIR> roots, int numInputs,
      int[] intLits, Data data) {
    assertEquals(LaneType.INT,
        VarkaKernelPlan.driveOf(roots, VarkaMatrixRuns.BASE).orElseThrow(), name);
    for (int lanes : LANES) {
      var options = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
      var emitted = VarkaEmitterFixtures.emit(roots, numInputs, intLits.length, options);
      for (int length : LENGTHS) {
        for (int pattern = 0; pattern < PATTERNS.size(); pattern++) {
          String context = name + " at " + lanes + " lanes, length " + length + ", pattern "
              + pattern;
          assertTrue(VarkaKernelChecks.runAndCompareMixed(context, emitted.className(),
              emitted.bytes(), roots, numInputs, intLits, new long[0],
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
  // The kernels of one lane count
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an int column to FLOAT, a kernel of the int and float lanes at one lane count")
  void intToFloat() {
    check("cast(i AS float)", List.of(convert(I, LaneType.FLOAT)), 1, new int[0], cells(INTS));
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
        new int[] {7, Float.floatToRawIntBits(0.25f)}, cells(INTS, FLOATS));
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
        new int[] {Float.floatToRawIntBits(1.5f), 1 << 30}, cells(INTS));
  }

  @Test
  @DisplayName("every conversion but I2F, and I2F beside a 64-bit lane, is refused by the plan")
  void widthRefusal() {
    for (var c : List.of(convert(I, LaneType.DOUBLE), convert(L, LaneType.FLOAT),
        convert(F, LaneType.DOUBLE), convert(X, LaneType.FLOAT), convert(F, LaneType.INT),
        convert(X, LaneType.LONG), convert(L, LaneType.DOUBLE), convert(X, LaneType.INT),
        convert(new NarrowLane(L, LaneType.INT), LaneType.FLOAT),
        new IntArith(IntOp.MUL, Overflow.WRAP, convert(new NarrowLane(L, LaneType.INT),
            LaneType.FLOAT), new ColumnRef(1, LaneType.FLOAT)))) {
      var refusal = VarkaKernelPlan.of(List.of(c), VarkaMatrixRuns.BASE).refusal();
      assertTrue(refusal.isPresent(), VarkaVectorIR.canonical(c));
      assertEquals(VarkaKernelPlan.Kind.CONVERSION, refusal.get().kind());
    }
  }
}
