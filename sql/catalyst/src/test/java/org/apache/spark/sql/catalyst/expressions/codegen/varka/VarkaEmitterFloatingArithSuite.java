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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.SparkException;
import org.apache.spark.sql.catalyst.expressions.Abs$;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus$;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecline;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Col;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Output;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.ValidityFill;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathFn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathUnary;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.FloatType;

/**
 * Floating arithmetic (VARKA-342): `+`, `-`, `*`, negation and `abs` at the `DOUBLE` and `FLOAT`
 * lanes, and `/`, `sqrt`, `signum`, `degrees` and `radians` at the `DOUBLE` one, against the
 * reference evaluator and against Spark's own expressions.
 *
 * <p>Every form is the IEEE operation Java computes, so the kernel's answer is held bit for bit,
 * with one exception, the NaN rule: a NaN an operation computes from two NaN operands of
 * different payloads is compared by being NaN, since which payload the row engine returns
 * depends on how C2 ordered the operands (`VARKA-342.md` 0.6). Negation, `abs` and `signum`
 * move their operand's bits and are compared bit for bit, NaN payloads included.
 *
 * <p>The division's mode is its zero divisor's disposal: null under LEGACY and TRY, a declined
 * batch under ANSI, which the row engine raises `DIVIDE_BY_ZERO` on. Both zeros are a zero
 * divisor and NaN is not, as in Spark's `DivModLike`.
 *
 * <p>The negation and `abs` of every `FLOAT` pattern run on every build. The binary sweeps, `+`,
 * `-` and `*` of every `FLOAT` pattern against each of sixteen operands, run under
 * `-Dvarka.sweep=true` (the nightly's sweep step).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterFloatingArithSuite {

  /**
   * The DOUBLE special values as bits: three NaNs (quiet, negative, a payload), both zeros, both
   * infinities, the extremes, the smallest subnormal and the smallest normal in both signs, and
   * three normals.
   */
  private static final long[] DOUBLE_SPECIALS = {
      0x7ff8000000000000L, 0xfff8000000000000L, 0x7ff8000000000001L,
      Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(-0.0),
      Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
      Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY),
      Double.doubleToRawLongBits(Double.MAX_VALUE), Double.doubleToRawLongBits(-Double.MAX_VALUE),
      Double.doubleToRawLongBits(Double.MIN_VALUE),
      Double.doubleToRawLongBits(-Double.MIN_VALUE),
      Double.doubleToRawLongBits(Double.MIN_NORMAL),
      Double.doubleToRawLongBits(-Double.MIN_NORMAL),
      Double.doubleToRawLongBits(1.5), Double.doubleToRawLongBits(-2.5),
      Double.doubleToRawLongBits(Math.sqrt(Double.MAX_VALUE))};

  /** The same at FLOAT, and the sixteen operands of the binary sweeps. */
  private static final int[] FLOAT_SPECIALS = {
      0x7fc00000, 0xffc00000, 0x7fc00001,
      Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(-0.0f),
      Float.floatToRawIntBits(Float.POSITIVE_INFINITY),
      Float.floatToRawIntBits(Float.NEGATIVE_INFINITY),
      Float.floatToRawIntBits(Float.MAX_VALUE), Float.floatToRawIntBits(-Float.MAX_VALUE),
      Float.floatToRawIntBits(Float.MIN_VALUE),
      Float.floatToRawIntBits(-Float.MIN_VALUE),
      Float.floatToRawIntBits(Float.MIN_NORMAL), Float.floatToRawIntBits(-Float.MIN_NORMAL),
      Float.floatToRawIntBits(1.5f), Float.floatToRawIntBits(-2.5f),
      Float.floatToRawIntBits(3.0f)};

  private static final int[] SWEEP_OPERANDS = FLOAT_SPECIALS;

  private static final ColumnRef X = new ColumnRef(0, LaneType.DOUBLE);
  private static final ColumnRef Y = new ColumnRef(1, LaneType.DOUBLE);
  private static final ColumnRef F = new ColumnRef(0, LaneType.FLOAT);
  private static final ColumnRef G = new ColumnRef(1, LaneType.FLOAT);

  /** A long column's value at row `i` of column `col`: the long matrix's data. */
  private interface LongData {
    long at(int col, int i);
  }

  /** An int column's value at row `i` of column `col`: the int matrix's data. */
  private interface IntData {
    int at(int col, int i);
  }

  // -------------------------------------------------------------------------------------------
  // The reference matrices: every (length, per-column null pattern) case against VarkaReference
  // -------------------------------------------------------------------------------------------

  /**
   * The four null patterns the emitter suites share: null-free, mixed (every fifth row),
   * alternating (every odd row) and all-null.
   */
  private static final List<IntPredicate> NULL_PATTERNS = List.of(
      i -> false,
      i -> i % 5 == 0,
      i -> i % 2 == 1,
      i -> true);

  /** Every pair of the four null patterns, as per-column combinations. */
  private static List<List<IntPredicate>> combos2() {
    var pairs = new ArrayList<List<IntPredicate>>();
    for (IntPredicate a : NULL_PATTERNS) {
      for (IntPredicate b : NULL_PATTERNS) {
        pairs.add(List.of(a, b));
      }
    }
    return pairs;
  }

  /**
   * Emits the outputs once at `lanes` lanes, then runs every (length, per-column null pattern)
   * case at both validity fills through the long lane's `run`, the one whose second scalar array
   * carries a 64-bit literal, and holds every row to {@link VarkaReference#longValue} bit for bit.
   * The shapes here hold no condition root, so every output is a value.
   */
  private static void checkLongMatrix(List<VarkaVectorIR> roots, int numInputs, long[] lits,
      int[] caseLengths, List<List<IntPredicate>> patternCombos, LongData data, String ctx,
      int lanes) {
    VarkaEmitOptions options = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(roots, numInputs, lits.length, options));
    VarkaFusedKernel kernel = loaded.kernel();
    try {
      for (int length : caseLengths) {
        for (int comboId = 0; comboId < patternCombos.size(); comboId++) {
          List<IntPredicate> combo = patternCombos.get(comboId);
          try (Arena arena = Arena.ofConfined()) {
            var cols = new Col[numInputs];
            for (int c = 0; c < numInputs; c++) {
              int col = c;
              cols[c] = VarkaEmitterFixtures.makeLongInput(arena, length, combo.get(c),
                  i -> data.at(col, i), true);
            }
            // Each case at both validity fills (VARKA-138), as the int matrix runs them.
            for (ValidityFill fill : ValidityFill.BOTH) {
              var outs = new Output[roots.size()];
              for (int o = 0; o < outs.length; o++) {
                outs[o] = VarkaEmitterFixtures.makeLongOutput(arena, length, fill);
              }
              int status = kernel.run(dataAddresses(cols), validityAddresses(cols, length),
                  nullCounts(cols), outputData(roots, outs), outputValidity(outs), new int[0],
                  lits, length, VarkaEmitterTestSupport.scratch(kernel, length));
              if (status != 0) {
                fail(ctx + " at " + lanes + " lanes: the kernel declined a batch it should have "
                    + "computed (length " + length + ", combo " + comboId + ", " + fill
                    + ", status " + status + ")");
              }
              var row = new long[numInputs];
              var isNull = new boolean[numInputs];
              for (int i = 0; i < length; i++) {
                for (int c = 0; c < numInputs; c++) {
                  isNull[c] = combo.get(c).test(i);
                  row[c] = isNull[c] ? 0L : data.at(c, i);
                }
                for (int o = 0; o < roots.size(); o++) {
                  boolean bit = validBit(outs[o].validity(), i);
                  OptionalLong expected = VarkaReference.longValue(roots.get(o), row, isNull, lits);
                  String where = ctx + " at " + lanes + " lanes, len=" + length + " combo="
                      + comboId + " out=" + o + " row=" + i + " fill=" + fill;
                  if (bit != expected.isPresent()) {
                    fail(where + ": validity differs (want " + expected + ")");
                  }
                  if (expected.isPresent()) {
                    long got = outs[o].data().get(ValueLayout.JAVA_LONG, i * 8L);
                    if (got != expected.getAsLong()) {
                      fail(where + ": value (want " + expected.getAsLong() + ", got " + got + ")");
                    }
                  }
                }
              }
            }
          }
        }
      }
    } finally {
      loaded.loader().release();
    }
  }

  /**
   * {@link #checkLongMatrix}'s twin at the int lane, where a FLOAT's bits travel: the int `run`,
   * the output validity sized for `options`, and {@link VarkaReference#value} as the reference.
   */
  private static void checkMatrix(List<VarkaVectorIR> roots, int numInputs, int[] lits,
      int[] caseLengths, List<List<IntPredicate>> patternCombos, IntData data, String ctx,
      VarkaEmitOptions options) {
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(roots, numInputs, lits.length, options));
    VarkaFusedKernel kernel = loaded.kernel();
    try {
      for (int length : caseLengths) {
        for (int comboId = 0; comboId < patternCombos.size(); comboId++) {
          List<IntPredicate> combo = patternCombos.get(comboId);
          try (Arena arena = Arena.ofConfined()) {
            var cols = new Col[numInputs];
            for (int c = 0; c < numInputs; c++) {
              int col = c;
              cols[c] = VarkaEmitterFixtures.makeInputData(arena, length, combo.get(c),
                  i -> data.at(col, i), true);
            }
            for (ValidityFill fill : ValidityFill.BOTH) {
              var outs = new Output[roots.size()];
              for (int o = 0; o < outs.length; o++) {
                outs[o] = VarkaEmitterFixtures.makeOutput(arena, length,
                    VarkaEmitterFixtures.outputValidityBytes(options, length), fill);
              }
              // The status is asserted, not discarded: a guard that declines every batch leaves
              // the destination values correct, so without it the matrix stays green while the
              // kernel computes nothing in production.
              int status = kernel.run(dataAddresses(cols), validityAddresses(cols, length),
                  nullCounts(cols), outputData(roots, outs), outputValidity(outs), lits, length,
                  VarkaEmitterTestSupport.scratch(kernel, length));
              if (status != 0) {
                fail(ctx + ": the kernel declined a batch it should have computed (length "
                    + length + ", combo " + comboId + ", " + fill + ", status " + status + ")");
              }
              var row = new int[numInputs];
              var isNull = new boolean[numInputs];
              for (int i = 0; i < length; i++) {
                for (int c = 0; c < numInputs; c++) {
                  isNull[c] = combo.get(c).test(i);
                  row[c] = isNull[c] ? 0 : data.at(c, i);
                }
                for (int o = 0; o < roots.size(); o++) {
                  boolean bit = validBit(outs[o].validity(), i);
                  var expected = VarkaReference.value(roots.get(o), row, isNull, lits);
                  String where = ctx + " len=" + length + " combo=" + comboId + " out=" + o
                      + " row=" + i + " fill=" + fill;
                  if (bit != expected.isPresent()) {
                    fail(where + ": validity differs (want " + expected + ")");
                  }
                  if (expected.isPresent()) {
                    int got = outs[o].data().get(ValueLayout.JAVA_INT, i * 4L);
                    if (got != expected.getAsInt()) {
                      fail(where + ": value (want " + expected.getAsInt() + ", got " + got + ")");
                    }
                  }
                }
              }
            }
          }
        }
      }
    } finally {
      loaded.loader().release();
    }
  }

  private static long[] dataAddresses(Col[] cols) {
    var addresses = new long[cols.length];
    for (int c = 0; c < cols.length; c++) {
      addresses[c] = cols[c].data().address();
    }
    return addresses;
  }

  private static long[] validityAddresses(Col[] cols, int length) {
    var addresses = new long[cols.length];
    for (int c = 0; c < cols.length; c++) {
      addresses[c] = cols[c].validityAddress(length);
    }
    return addresses;
  }

  private static int[] nullCounts(Col[] cols) {
    var counts = new int[cols.length];
    for (int c = 0; c < cols.length; c++) {
      counts[c] = cols[c].nullCount();
    }
    return counts;
  }

  /** A condition root's data address is 0L per the kernel contract; a value's is its buffer. */
  private static long[] outputData(List<VarkaVectorIR> roots, Output[] outs) {
    var addresses = new long[outs.length];
    for (int o = 0; o < outs.length; o++) {
      addresses[o] = roots.get(o) instanceof Cond ? 0L : outs[o].data().address();
    }
    return addresses;
  }

  private static long[] outputValidity(Output[] outs) {
    var addresses = new long[outs.length];
    for (int o = 0; o < outs.length; o++) {
      addresses[o] = outs[o].validity().address();
    }
    return addresses;
  }

  private static boolean validBit(MemorySegment validity, int i) {
    return (validity.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
  }

  // -------------------------------------------------------------------------------------------
  // The IR against the reference evaluator
  // -------------------------------------------------------------------------------------------

  /**
   * Row `i`'s operand pair from the specials: every ordered pair in turn. Two NaNs of different
   * payloads are not paired, since the NaN rule compares their sum by NaN-ness and the matrix
   * compares bits; the second takes the first's bits instead.
   */
  private static long doubleData(int col, int i) {
    int n = DOUBLE_SPECIALS.length;
    long a = DOUBLE_SPECIALS[i % n];
    long b = DOUBLE_SPECIALS[(i / n + i) % n];
    boolean bothNaN = Double.isNaN(Double.longBitsToDouble(a))
        && Double.isNaN(Double.longBitsToDouble(b));
    return col == 0 || bothNaN ? a : b;
  }

  private static int floatData(int col, int i) {
    int n = FLOAT_SPECIALS.length;
    int a = FLOAT_SPECIALS[i % n];
    int b = FLOAT_SPECIALS[(i / n + i) % n];
    boolean bothNaN = Float.isNaN(Float.intBitsToFloat(a)) && Float.isNaN(Float.intBitsToFloat(b));
    return col == 0 || bothNaN ? a : b;
  }

  private static final int[] MATRIX_LENGTHS = {1, 7, 64, 129, 400};

  @Test
  @DisplayName("the floating operations compute what the reference says at the DOUBLE lane, "
      + "at both widths")
  public void floatingOperationsComputeWhatTheReferenceSaysAtTheDoubleLane() {
    var k = new LiteralSlot(0, LaneType.DOUBLE);
    var roots = new ArrayList<VarkaVectorIR>();
    for (IntOp op : IntOp.values()) {
      roots.add(new IntArith(op, Overflow.WRAP, X, Y));
    }
    roots.addAll(List.of(
        new IntArith(IntOp.DIV, Overflow.NULL, X, Y),
        new IntArith(IntOp.DIV, Overflow.WRAP, Y, X),
        new IntNeg(Overflow.WRAP, X), new IntNeg(Overflow.WRAP, Y, true),
        new MathUnary(MathFn.SQRT, X), new IntArith(IntOp.MUL, Overflow.WRAP, X, k)));
    long[] lits = {Double.doubleToRawLongBits(Math.toDegrees(1.0))};
    for (int lanes : new int[] {2, 8}) {
      checkLongMatrix(roots, 2, lits, MATRIX_LENGTHS, combos2(),
          VarkaEmitterFloatingArithSuite::doubleData, "double", lanes);
    }
  }

  @Test
  @DisplayName("the floating operations compute what the reference says at the FLOAT lane, "
      + "at both widths")
  public void floatingOperationsComputeWhatTheReferenceSaysAtTheFloatLane() {
    var roots = new ArrayList<VarkaVectorIR>();
    for (IntOp op : List.of(IntOp.ADD, IntOp.SUB, IntOp.MUL)) {
      roots.add(new IntArith(op, Overflow.WRAP, F, G));
    }
    roots.addAll(List.of(
        new IntArith(IntOp.SUB, Overflow.WRAP, G, F), new IntNeg(Overflow.WRAP, F),
        new IntNeg(Overflow.WRAP, G, true)));
    for (int lanes : new int[] {4, 16}) {
      checkMatrix(roots, 2, new int[0], MATRIX_LENGTHS, combos2(),
          VarkaEmitterFloatingArithSuite::floatData, "float lanes=" + lanes,
          VarkaMatrixRuns.BASE.withLanesOverride(lanes));
    }
  }

  @Test
  @DisplayName("a floating add, subtract or multiply has no checked form, and an integral lane "
      + "no divide")
  public void floatingArithmeticHasNoCheckedFormAndAnIntegralLaneNoDivide() {
    for (IntOp op : List.of(IntOp.ADD, IntOp.SUB, IntOp.MUL)) {
      for (Overflow mode : List.of(Overflow.FAIL, Overflow.NULL)) {
        assertThrows(IllegalArgumentException.class, () -> new IntArith(op, mode, X, Y));
        assertThrows(IllegalArgumentException.class, () -> new IntArith(op, mode, F, G));
      }
    }
    assertThrows(IllegalArgumentException.class, () -> new IntNeg(Overflow.FAIL, X));
    for (Overflow mode : Overflow.values()) {
      assertThrows(IllegalArgumentException.class,
          () -> new IntArith(IntOp.DIV, mode, new ColumnRef(0), new ColumnRef(1)));
      assertThrows(IllegalArgumentException.class, () -> new IntArith(IntOp.DIV, mode,
          new ColumnRef(0, LaneType.LONG), new ColumnRef(1, LaneType.LONG)));
    }
    assertThrows(IllegalArgumentException.class,
        () -> new MathUnary(MathFn.SQRT, new ColumnRef(0)));
  }

  // -------------------------------------------------------------------------------------------
  // The division's zero divisor
  // -------------------------------------------------------------------------------------------

  /** A double-lane kernel's status and its first output's values, empty for a null. */
  private record Ran(int status, List<OptionalLong> values) {}

  /** One double-lane kernel over two columns, a null element a null: its status and output. */
  private static Ran runDouble(VarkaVectorIR root, Double[] xs, Double[] ys, int lanes) {
    var loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(List.of(root), 2, 0,
        VarkaMatrixRuns.BASE.withLanesOverride(lanes)));
    VarkaFusedKernel kernel = loaded.kernel();
    int n = xs.length;
    try (Arena arena = Arena.ofConfined()) {
      var cols = new Col[] {doubleColumn(arena, xs), doubleColumn(arena, ys)};
      Output out = VarkaEmitterFixtures.makeLongOutput(arena, n);
      int status = kernel.run(dataAddresses(cols), validityAddresses(cols, n), nullCounts(cols),
          new long[] {out.data().address()}, new long[] {out.validity().address()}, new int[0],
          new long[0], n, VarkaEmitterTestSupport.scratch(kernel, n));
      var values = new ArrayList<OptionalLong>();
      for (int i = 0; i < n; i++) {
        values.add(validBit(out.validity(), i)
            ? OptionalLong.of(out.data().get(ValueLayout.JAVA_LONG, i * 8L))
            : OptionalLong.empty());
      }
      return new Ran(status, values);
    } finally {
      loaded.loader().release();
    }
  }

  private static Ran runDouble(VarkaVectorIR root, Double[] xs, Double[] ys) {
    return runDouble(root, xs, ys, 0);
  }

  private static Col doubleColumn(Arena arena, Double[] vs) {
    return VarkaEmitterFixtures.makeLongInput(arena, vs.length, i -> vs[i] == null,
        i -> Double.doubleToRawLongBits(vs[i] == null ? 0.0 : vs[i]), true);
  }

  private static OptionalLong bits(double d) {
    return OptionalLong.of(Double.doubleToRawLongBits(d));
  }

  /** `count` copies of `v` followed by `last`. */
  private static Double[] filled(int count, Double v, Double last) {
    var vs = new Double[count + 1];
    Arrays.fill(vs, v);
    vs[count] = last;
    return vs;
  }

  /** `vs` with element `i` replaced by `v`. */
  private static Double[] updated(Double[] vs, int i, Double v) {
    Double[] copy = vs.clone();
    copy[i] = v;
    return copy;
  }

  @Test
  @DisplayName("a zero divisor of either sign nulls a division under NULL, and NaN does not")
  public void zeroDivisorOfEitherSignNullsADivisionUnderNull() {
    var div = new IntArith(IntOp.DIV, Overflow.NULL, X, Y);
    Double[] xs = {1.0, 0.0, Double.NaN, 1.0, -1.0, null, 6.0, 1.0};
    Double[] ys = {-0.0, 0.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0, 3.0, null};
    for (int lanes : new int[] {2, 4, 8}) {
      Ran ran = runDouble(div, xs, ys, lanes);
      assertEquals(0, ran.status());
      assertEquals(List.of(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
          bits(Double.NaN), bits(-0.0), OptionalLong.empty(), bits(2.0), OptionalLong.empty()),
          ran.values(), "lanes=" + lanes);
    }
  }

  @Test
  @DisplayName("a live zero divisor of either sign declines a FAIL division, and a null dividend "
      + "over it does not")
  public void liveZeroDivisorDeclinesAFailDivision() {
    var div = new IntArith(IntOp.DIV, Overflow.FAIL, X, Y);
    for (int lanes : new int[] {2, 4, 8}) {
      for (double zero : new double[] {0.0, -0.0}) {
        // A zero divisor on a live row, at the end of a batch longer than a vector.
        Double[] xs = filled(9, 1.0, 5.0);
        Double[] ys = filled(9, 2.0, zero);
        assertEquals(VarkaFusedKernel.STATUS_DIVIDE_BY_ZERO, runDouble(div, xs, ys, lanes).status(),
            "lanes=" + lanes + " zero=" + zero);
        // The same zero under a null dividend: null, as Spark's ANSI division answers.
        Ran ran = runDouble(div, updated(xs, 9, null), ys, lanes);
        assertEquals(0, ran.status(), "lanes=" + lanes + " zero=" + zero);
        var want = new ArrayList<OptionalLong>();
        for (int i = 0; i < 9; i++) {
          want.add(bits(0.5));
        }
        want.add(OptionalLong.empty());
        assertEquals(want, ran.values());
        // A NaN divisor is no zero, under ANSI as anywhere.
        assertEquals(bits(Double.NaN),
            runDouble(div, xs, updated(ys, 9, Double.NaN), lanes).values().get(9));
      }
    }
  }

  @Test
  @DisplayName("a WRAP division answers a zero divisor's infinity or NaN, the IEEE quotient")
  public void wrapDivisionAnswersTheIeeeQuotient() {
    // The form a literal non-zero divisor takes, and a math composition dividing by a computed
    // value (row 356's `1 / sin(x)`): no test at all.
    var div = new IntArith(IntOp.DIV, Overflow.WRAP, X, Y);
    Ran ran = runDouble(div, new Double[] {1.0, -1.0, 0.0, 1.0},
        new Double[] {0.0, 0.0, -0.0, -0.0});
    assertEquals(0, ran.status());
    assertEquals(List.of(bits(Double.POSITIVE_INFINITY), bits(Double.NEGATIVE_INFINITY),
        OptionalLong.of(Double.doubleToRawLongBits(quotient(0.0, -0.0))),
        bits(Double.NEGATIVE_INFINITY)), ran.values());
  }

  /**
   * {@code a / b} computed when the test runs: javac folds a constant {@code 0.0 / -0.0} to the
   * canonical NaN, where the hardware's division answers its own default NaN, as the kernel does.
   */
  private static double quotient(double a, double b) {
    return a / b;
  }

  // -------------------------------------------------------------------------------------------
  // The compiled forms against Spark, in every mode
  // -------------------------------------------------------------------------------------------

  private static final List<Attribute> DOUBLE_COLS = List.of(
      VarkaSqlResolve.column("x", DataTypes.DoubleType),
      VarkaSqlResolve.column("y", DataTypes.DoubleType),
      VarkaSqlResolve.column("z", DataTypes.DoubleType));
  private static final List<Attribute> FLOAT_COLS = List.of(
      VarkaSqlResolve.column("f", DataTypes.FloatType),
      VarkaSqlResolve.column("g", DataTypes.FloatType));

  private static scala.collection.immutable.Seq<Attribute> seq(List<Attribute> cols) {
    return CollectionConverters.asScala(cols).toSeq();
  }

  private static scala.collection.immutable.Seq<NamedExpression> entry(Expression e) {
    return CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq();
  }

  private static Expression resolve(String sql, List<Attribute> cols, String mode) {
    SQLConf conf = SQLConf.get().clone();
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), Boolean.toString(mode.equals("ANSI")));
    String text = mode.equals("TRY") ? sql.replace("x / y", "try_divide(x, y)") : sql;
    return SQLConf$.MODULE$.withExistingConf(conf, () -> VarkaSqlResolve.parse(text, seq(cols)));
  }

  private static CompiledVarkaProjection compile(Expression e, List<Attribute> cols) {
    Optional<CompiledVarkaProjection> plan =
        VarkaExpressionCompiler.compile(entry(e), seq(cols), VarkaMatrixRuns.BASE);
    if (plan.isEmpty()) {
      fail(e.sql() + " declines: "
          + VarkaExpressionCompiler.declines(entry(e), seq(cols), VarkaMatrixRuns.BASE));
    }
    return plan.get();
  }

  /** Whether `b`, a floating answer's bits, is a NaN. */
  private static boolean nanBits(long b, boolean isFloat) {
    return isFloat ? Float.isNaN(Float.intBitsToFloat((int) b))
        : Double.isNaN(Double.longBitsToDouble(b));
  }

  /** A cell's lane bits: a DOUBLE's raw long bits, a FLOAT's raw int bits. */
  private static long cellBits(Object cell) {
    return switch (cell) {
      case Double d -> Double.doubleToRawLongBits(d);
      case Float f -> Float.floatToRawIntBits(f);
      default -> throw new IllegalArgumentException("not a floating cell: " + cell);
    };
  }

  /**
   * Whether `row` holds two NaN inputs of different bits, the one case where Java leaves the
   * payload of an operation's NaN open (the interpreter takes one operand's, C2 may commute).
   */
  private static boolean twoUnlikeNaNs(Object[] row) {
    boolean seen = false;
    long first = 0L;
    for (Object cell : row) {
      if (cell != null && nanBits(cellBits(cell), cell instanceof Float)) {
        long b = cellBits(cell);
        if (!seen) {
          seen = true;
          first = b;
        } else if (first != b) {
          return true;
        }
      }
    }
    return false;
  }

  private static String shown(Object[] row) {
    var parts = new ArrayList<String>();
    for (Object cell : row) {
      parts.add(String.valueOf(cell));
    }
    return String.join(", ", parts);
  }

  private static String shown(OptionalLong v) {
    return v.isPresent() ? "Some(" + Long.toHexString(v.getAsLong()) + ")" : "None";
  }

  /**
   * Runs `sql` compiled over `rows` and holds every row to Spark's `eval` of the same resolved
   * expression: the same bits, or under the NaN rule both NaN where `movesBits` is false and the
   * row holds two NaN inputs of different bits ({@link #twoUnlikeNaNs}). A row
   * Spark raises on must decline the batch with `status`; the rows are then run again without
   * it. Returns how many rows were compared.
   */
  private static int checkAgainstSpark(String sql, List<Attribute> cols, List<Object[]> rows,
      String mode, boolean movesBits, int status) {
    Expression e = resolve(sql, cols, mode);
    CompiledVarkaProjection plan = compile(e, cols);
    boolean isFloat = e.dataType() instanceof FloatType;
    long mask = isFloat ? 0xffffffffL : -1L;
    // Spark's interpreted eval of the expression over every column, its RuntimeReplaceable
    // forms (try_add, try_divide) evaluated as their replacements, as the optimizer puts them.
    var oracle = new VarkaSparkOracle(
        CollectionConverters.asScala(List.of(e)).toSeq(), seq(cols));
    var spark = new ArrayList<VarkaSparkOracle.Answer>();
    for (Object[] r : rows) {
      var row = new ArrayList<Option<Object>>();
      for (Object cell : r) {
        row.add(cell == null ? Option.empty() : Option.<Object>apply(cellBits(cell)));
      }
      spark.add(oracle.answer(0, CollectionConverters.asScala(row).toSeq()));
    }
    List<Integer> inputs = plan.inputOrdinals();
    var loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(plan.outputs(),
        inputs.size(), plan.numLiterals(), VarkaMatrixRuns.BASE));
    VarkaFusedKernel kernel = loaded.kernel();
    int[] ints = plan.literals().stream().mapToInt(Integer::intValue).toArray();
    long[] longs = plan.longLiterals().stream().mapToLong(Long::longValue).toArray();
    try {
      var raising = new ArrayList<Integer>();
      Throwable firstRaised = null;
      for (int i = 0; i < spark.size(); i++) {
        if (spark.get(i) instanceof VarkaSparkOracle.Raised r) {
          raising.add(i);
          if (firstRaised == null) {
            firstRaised = r.e();
          }
        }
      }
      if (!raising.isEmpty()) {
        assertNotEquals(0, status, sql + " (" + mode + "): Spark raises, and no status is "
            + "expected");
        assertTrue(firstRaised instanceof SparkException
            || firstRaised instanceof ArithmeticException,
            sql + " (" + mode + "): Spark raises " + firstRaised.getClass());
        assertEquals(status, runSpark(kernel, inputs, isFloat, ints, longs, rows).status(),
            sql + " (" + mode + "): the batch Spark raises on is declined");
      }
      var kept = new ArrayList<Integer>();
      var keptRows = new ArrayList<Object[]>();
      for (int i = 0; i < rows.size(); i++) {
        if (!raising.contains(i)) {
          kept.add(i);
          keptRows.add(rows.get(i));
        }
      }
      Ran ran = runSpark(kernel, inputs, isFloat, ints, longs, keptRows);
      assertEquals(0, ran.status(), sql + " (" + mode + "): declined a batch Spark answers");
      for (int j = 0; j < kept.size(); j++) {
        int i = kept.get(j);
        OptionalLong want = switch (spark.get(i)) {
          case VarkaSparkOracle.Value v -> OptionalLong.of(v.v() & mask);
          case VarkaSparkOracle.Raised r -> throw new IllegalStateException("a raising row kept");
          // The oracle's third answer is the Null singleton, which has no case class to name.
          default -> OptionalLong.empty();
        };
        OptionalLong got = ran.values().get(j);
        boolean agree;
        if (want.isPresent() && got.isPresent()) {
          long a = want.getAsLong();
          long b = got.getAsLong();
          agree = a == b || !movesBits && twoUnlikeNaNs(rows.get(i))
              && nanBits(a, isFloat) && nanBits(b, isFloat);
        } else {
          agree = want.isEmpty() && got.isEmpty();
        }
        if (!agree) {
          fail(sql + " (" + mode + ") over " + shown(rows.get(i)) + ": Spark " + shown(want)
              + ", the kernel " + shown(got));
        }
      }
      return kept.size();
    } finally {
      loaded.loader().release();
    }
  }

  private static int checkAgainstSpark(String sql, List<Attribute> cols, List<Object[]> rows,
      String mode, boolean movesBits) {
    return checkAgainstSpark(sql, cols, rows, mode, movesBits, 0);
  }

  /**
   * One batch of `rs` through a compiled kernel: kernel input `k` reads the row's column
   * `inputs.get(k)`, as FLOAT bits on the int lane or DOUBLE bits on the long one.
   */
  private static Ran runSpark(VarkaFusedKernel kernel, List<Integer> inputs, boolean isFloat,
      int[] ints, long[] longs, List<Object[]> rs) {
    int n = rs.size();
    try (Arena arena = Arena.ofConfined()) {
      var cols = new Col[inputs.size()];
      for (int k = 0; k < cols.length; k++) {
        int c = inputs.get(k);
        IntPredicate isNull = i -> rs.get(i)[c] == null;
        cols[k] = isFloat
            ? VarkaEmitterFixtures.makeInputData(arena, n, isNull,
                i -> Float.floatToRawIntBits((Float) rs.get(i)[c]), true)
            : VarkaEmitterFixtures.makeLongInput(arena, n, isNull,
                i -> Double.doubleToRawLongBits((Double) rs.get(i)[c]), true);
      }
      Output out = isFloat ? VarkaEmitterFixtures.makeOutput(arena, n, -1L)
          : VarkaEmitterFixtures.makeLongOutput(arena, n);
      long[] outData = {out.data().address()};
      long[] outValidity = {out.validity().address()};
      long scratch = VarkaEmitterTestSupport.scratch(kernel, n);
      int st = isFloat
          ? kernel.run(dataAddresses(cols), validityAddresses(cols, n), nullCounts(cols), outData,
              outValidity, ints, n, scratch)
          : kernel.run(dataAddresses(cols), validityAddresses(cols, n), nullCounts(cols), outData,
              outValidity, ints, longs, n, scratch);
      var values = new ArrayList<OptionalLong>();
      for (int i = 0; i < n; i++) {
        if (!validBit(out.validity(), i)) {
          values.add(OptionalLong.empty());
        } else if (isFloat) {
          values.add(OptionalLong.of(out.data().get(ValueLayout.JAVA_INT, i * 4L) & 0xffffffffL));
        } else {
          values.add(OptionalLong.of(out.data().get(ValueLayout.JAVA_LONG, i * 8L)));
        }
      }
      return new Ran(st, values);
    }
  }

  private static final List<String> MODES = List.of("LEGACY", "ANSI", "TRY");

  /** 2.7's cells as operand pairs, and the special values against each other. */
  private static final List<Object[]> DOUBLE_CELLS = doubleCells();

  private static final List<Object[]> FLOAT_CELLS = floatCells();

  private static List<Object[]> doubleCells() {
    double[][] cells = {
        {0.0, -1.0}, {-0.0, 0.0}, {-0.0, -0.0},
        {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
        {0.0, Double.POSITIVE_INFINITY}, {1.0, -0.0}, {0.0, 0.0}, {Double.NaN, 0.0},
        {1.0, Double.NaN}, {-1.0, Double.POSITIVE_INFINITY}, {Double.MAX_VALUE, 2.0},
        {Double.MIN_NORMAL, 2.0}, {Double.MIN_VALUE, 2.0}, {-0.0, 1.0},
        {Double.MAX_VALUE, Double.MAX_VALUE}, {-Double.MAX_VALUE, 0.5}, {3.0, 7.0}};
    var rows = new ArrayList<Object[]>();
    for (double[] cell : cells) {
      rows.add(new Object[] {cell[0], cell[1], 1.5});
    }
    for (long a : DOUBLE_SPECIALS) {
      for (long b : DOUBLE_SPECIALS) {
        rows.add(new Object[] {Double.longBitsToDouble(a), Double.longBitsToDouble(b), 1.5});
      }
    }
    rows.add(new Object[] {null, 0.0, 1.5});
    rows.add(new Object[] {1.0, null, 1.5});
    rows.add(new Object[] {null, null, 1.5});
    return List.copyOf(rows);
  }

  private static List<Object[]> floatCells() {
    float[][] cells = {
        {0.0f, -1.0f}, {-0.0f, 0.0f}, {-0.0f, -0.0f},
        {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY}, {0.0f, Float.POSITIVE_INFINITY},
        {Float.MAX_VALUE, 2.0f}, {Float.MIN_NORMAL, 0.5f}, {Float.MIN_VALUE, 0.5f}};
    var rows = new ArrayList<Object[]>();
    for (float[] cell : cells) {
      rows.add(new Object[] {cell[0], cell[1]});
    }
    for (int a : FLOAT_SPECIALS) {
      for (int b : FLOAT_SPECIALS) {
        rows.add(new Object[] {Float.intBitsToFloat(a), Float.intBitsToFloat(b)});
      }
    }
    rows.add(new Object[] {null, 0.0f});
    rows.add(new Object[] {1.0f, null});
    return List.copyOf(rows);
  }

  /** A form that computes a value: a NaN from two NaNs is compared by NaN-ness. */
  private static void doubleMatchesSpark(String sql) {
    for (String m : MODES) {
      checkAgainstSpark(sql, DOUBLE_COLS, DOUBLE_CELLS, m, false);
    }
  }

  /** A form that moves its operand's bits, compared bit for bit, NaN payloads included. */
  private static void doubleMatchesSparkBitForBit(String sql) {
    for (String m : MODES) {
      checkAgainstSpark(sql, DOUBLE_COLS, DOUBLE_CELLS, m, true);
    }
  }

  private static void floatMatchesSpark(String sql) {
    for (String m : MODES) {
      checkAgainstSpark(sql, FLOAT_COLS, FLOAT_CELLS, m, false);
    }
  }

  private static void floatMatchesSparkBitForBit(String sql) {
    for (String m : MODES) {
      checkAgainstSpark(sql, FLOAT_COLS, FLOAT_CELLS, m, true);
    }
  }

  @Test
  @DisplayName("DOUBLE x + y fuses and matches Spark in every mode")
  public void doubleAdd() {
    doubleMatchesSpark("x + y");
  }

  @Test
  @DisplayName("DOUBLE x - y fuses and matches Spark in every mode")
  public void doubleSubtract() {
    doubleMatchesSpark("x - y");
  }

  @Test
  @DisplayName("DOUBLE x * y fuses and matches Spark in every mode")
  public void doubleMultiply() {
    doubleMatchesSpark("x * y");
  }

  @Test
  @DisplayName("DOUBLE y - x fuses and matches Spark in every mode")
  public void doubleSubtractReversed() {
    doubleMatchesSpark("y - x");
  }

  @Test
  @DisplayName("DOUBLE x * 2.5D fuses and matches Spark in every mode")
  public void doubleMultiplyByLiteral() {
    doubleMatchesSpark("x * 2.5D");
  }

  @Test
  @DisplayName("DOUBLE -1.0D * x fuses and matches Spark in every mode")
  public void doubleLiteralTimesColumn() {
    doubleMatchesSpark("-1.0D * x");
  }

  @Test
  @DisplayName("DOUBLE sqrt(x) fuses and matches Spark in every mode")
  public void doubleSqrt() {
    doubleMatchesSpark("sqrt(x)");
  }

  @Test
  @DisplayName("DOUBLE degrees(x) fuses and matches Spark in every mode")
  public void doubleDegrees() {
    doubleMatchesSpark("degrees(x)");
  }

  @Test
  @DisplayName("DOUBLE radians(x) fuses and matches Spark in every mode")
  public void doubleRadians() {
    doubleMatchesSpark("radians(x)");
  }

  @Test
  @DisplayName("DOUBLE x / 3.0D fuses and matches Spark in every mode")
  public void doubleDivideByLiteral() {
    doubleMatchesSpark("x / 3.0D");
  }

  @Test
  @DisplayName("DOUBLE x / -0.5D fuses and matches Spark in every mode")
  public void doubleDivideByNegativeLiteral() {
    doubleMatchesSpark("x / -0.5D");
  }

  @Test
  @DisplayName("DOUBLE x / CAST('NaN' AS DOUBLE) fuses and matches Spark in every mode")
  public void doubleDivideByNaN() {
    doubleMatchesSpark("x / CAST('NaN' AS DOUBLE)");
  }

  @Test
  @DisplayName("DOUBLE try_add(x, y) fuses and matches Spark in every mode")
  public void doubleTryAdd() {
    doubleMatchesSpark("try_add(x, y)");
  }

  @Test
  @DisplayName("DOUBLE try_multiply(x, y) fuses and matches Spark in every mode")
  public void doubleTryMultiply() {
    doubleMatchesSpark("try_multiply(x, y)");
  }

  @Test
  @DisplayName("DOUBLE (x + y) * z - x fuses and matches Spark in every mode")
  public void doubleChain() {
    doubleMatchesSpark("(x + y) * z - x");
  }

  @Test
  @DisplayName("DOUBLE -x fuses and matches Spark bit for bit in every mode")
  public void doubleNegate() {
    doubleMatchesSparkBitForBit("-x");
  }

  @Test
  @DisplayName("DOUBLE abs(x) fuses and matches Spark bit for bit in every mode")
  public void doubleAbs() {
    doubleMatchesSparkBitForBit("abs(x)");
  }

  @Test
  @DisplayName("DOUBLE signum(x) fuses and matches Spark bit for bit in every mode")
  public void doubleSignum() {
    doubleMatchesSparkBitForBit("signum(x)");
  }

  @Test
  @DisplayName("DOUBLE -(-x) fuses and matches Spark bit for bit in every mode")
  public void doubleDoubleNegate() {
    doubleMatchesSparkBitForBit("-(-x)");
  }

  @Test
  @DisplayName("DOUBLE abs(-x) fuses and matches Spark bit for bit in every mode")
  public void doubleAbsOfNegate() {
    doubleMatchesSparkBitForBit("abs(-x)");
  }

  @Test
  @DisplayName("DOUBLE x / y is null on a zero divisor under LEGACY and TRY, and declines under "
      + "ANSI")
  public void doubleDivisionByAZeroDivisorFollowsTheMode() {
    for (String m : List.of("LEGACY", "TRY")) {
      checkAgainstSpark("x / y", DOUBLE_COLS, DOUBLE_CELLS, m, false);
    }
    int compared = checkAgainstSpark("x / y", DOUBLE_COLS, DOUBLE_CELLS, "ANSI", false,
        VarkaFusedKernel.STATUS_DIVIDE_BY_ZERO);
    assertTrue(compared < DOUBLE_CELLS.size(), "the cells hold zero divisors Spark raises on");
  }

  @Test
  @DisplayName("a choice that guards the divisor fuses under ANSI over a batch holding zeros")
  public void choiceThatGuardsTheDivisorFusesUnderAnsi() {
    for (String sql : List.of("IF(y = 0.0D, 0.0D, x / y)",
        "CASE WHEN y <> 0.0D THEN x / y ELSE 0.0D END")) {
      int compared = checkAgainstSpark(sql, DOUBLE_COLS, DOUBLE_CELLS, "ANSI", false);
      assertEquals(DOUBLE_CELLS.size(), compared, sql);
    }
  }

  @Test
  @DisplayName("a division by a literal zero of either sign declines by name")
  public void divisionByALiteralZeroDeclinesByName() {
    for (String sql : List.of("x / 0.0D", "x / -0.0D")) {
      for (String m : MODES) {
        Expression e = resolve(sql, DOUBLE_COLS, m);
        assertTrue(VarkaExpressionCompiler.compile(entry(e), seq(DOUBLE_COLS),
            VarkaMatrixRuns.BASE).isEmpty(), sql);
        var reasons = new ArrayList<String>();
        for (VarkaDecline d : VarkaExpressionCompiler.declines(entry(e), seq(DOUBLE_COLS),
            VarkaMatrixRuns.BASE).values()) {
          reasons.add(d.reason());
        }
        boolean named = false;
        for (String reason : reasons) {
          named |= reason.contains("a division by a literal zero");
        }
        assertTrue(named, sql + ": " + reasons);
      }
    }
  }

  @Test
  @DisplayName("a division by a non-zero literal carries no zero test")
  public void divisionByANonZeroLiteralCarriesNoZeroTest() {
    for (String m : MODES) {
      CompiledVarkaProjection plan = compile(resolve("x / 3.0D", DOUBLE_COLS, m), DOUBLE_COLS);
      if (plan.outputs().get(0) instanceof IntArith a) {
        assertTrue(a.op() == IntOp.DIV && a.mode() == Overflow.WRAP);
      } else {
        fail("x / 3.0D compiles to " + plan.outputs().get(0));
      }
    }
  }

  @Test
  @DisplayName("FLOAT f + g fuses and matches Spark in every mode")
  public void floatAdd() {
    floatMatchesSpark("f + g");
  }

  @Test
  @DisplayName("FLOAT f - g fuses and matches Spark in every mode")
  public void floatSubtract() {
    floatMatchesSpark("f - g");
  }

  @Test
  @DisplayName("FLOAT f * g fuses and matches Spark in every mode")
  public void floatMultiply() {
    floatMatchesSpark("f * g");
  }

  @Test
  @DisplayName("FLOAT g - f fuses and matches Spark in every mode")
  public void floatSubtractReversed() {
    floatMatchesSpark("g - f");
  }

  @Test
  @DisplayName("FLOAT f * 0.5F fuses and matches Spark in every mode")
  public void floatMultiplyByLiteral() {
    floatMatchesSpark("f * 0.5F");
  }

  @Test
  @DisplayName("FLOAT -f fuses and matches Spark bit for bit in every mode")
  public void floatNegate() {
    floatMatchesSparkBitForBit("-f");
  }

  @Test
  @DisplayName("FLOAT abs(f) fuses and matches Spark bit for bit in every mode")
  public void floatAbs() {
    floatMatchesSparkBitForBit("abs(f)");
  }

  @Test
  @DisplayName("signum keeps a zero's sign and a NaN's payload, and answers null for a null")
  public void signumKeepsAZerosSignAndANaNsPayload() {
    List<Object[]> rows = List.of(new Object[] {0.0, 0.0, 0.0}, new Object[] {-0.0, 0.0, 0.0},
        new Object[] {Double.POSITIVE_INFINITY, 0.0, 0.0},
        new Object[] {Double.NEGATIVE_INFINITY, 0.0, 0.0},
        new Object[] {Double.NaN, 0.0, 0.0},
        new Object[] {Double.longBitsToDouble(0xfff8000000000000L), 0.0, 0.0},
        new Object[] {Double.longBitsToDouble(0x7ff8000000000001L), 0.0, 0.0},
        new Object[] {null, 0.0, 0.0},
        new Object[] {Double.MIN_VALUE, 0.0, 0.0}, new Object[] {-Double.MIN_VALUE, 0.0, 0.0});
    for (String m : MODES) {
      assertEquals(rows.size(), checkAgainstSpark("signum(x)", DOUBLE_COLS, rows, m, true));
    }
  }

  @Test
  @DisplayName("no multiply and add is contracted: (x * y) + z is 0.0 where an FMA gives -2^-54")
  public void noMultiplyAndAddIsContracted() {
    double a = 1.0 + Math.scalb(1.0, -27);
    double b = 1.0 - Math.scalb(1.0, -27);
    assertTrue(Math.fma(a, b, -1.0) == -Math.scalb(1.0, -54), "the operands are the named case");
    // a * b rounds to 1.0, a tie between 1 - 2^-53 and 1.0 broken to even, so the sum is 0.0.
    assertTrue(a * b + -1.0 == 0.0);
    var rows = new ArrayList<Object[]>();
    for (int i = 0; i < 17; i++) {
      rows.add(new Object[] {a, b, -1.0});
    }
    for (String m : MODES) {
      assertEquals(17, checkAgainstSpark("(x * y) + z", DOUBLE_COLS, rows, m, true));
    }
  }

  // -------------------------------------------------------------------------------------------
  // The JDK's constants, and the sweeps
  // -------------------------------------------------------------------------------------------

  /** Asserts that `Math.toDegrees` and `Math.toRadians` of `v` are one multiply each. */
  private static void checkConversions(double v, double toDeg, double toRad) {
    if (Double.doubleToRawLongBits(Math.toDegrees(v))
        != Double.doubleToRawLongBits(v * toDeg)) {
      fail("toDegrees(" + v + ")");
    }
    if (Double.doubleToRawLongBits(Math.toRadians(v))
        != Double.doubleToRawLongBits(v * toRad)) {
      fail("toRadians(" + v + ")");
    }
  }

  @Test
  @DisplayName("Math.toDegrees and Math.toRadians are one multiply by their value at 1.0")
  public void toDegreesAndToRadiansAreOneMultiply() {
    double toDeg = Math.toDegrees(1.0);
    double toRad = Math.toRadians(1.0);
    for (long b : DOUBLE_SPECIALS) {
      checkConversions(Double.longBitsToDouble(b), toDeg, toRad);
    }
    // Every exponent band, a seeded draw of patterns in each; and widened FLOAT patterns.
    var random = new SplittableRandom(342L);
    for (int exp = 0; exp < 2048; exp++) {
      for (int draw = 0; draw < 64; draw++) {
        long bitsOf = ((long) exp << 52) | (random.nextLong() & 0xfffffffffffffL);
        checkConversions(Double.longBitsToDouble(bitsOf), toDeg, toRad);
        checkConversions(-Double.longBitsToDouble(bitsOf), toDeg, toRad);
      }
    }
    for (long p = 0L; p < (1L << 32); p += 4099) {
      checkConversions(Float.intBitsToFloat((int) p), toDeg, toRad);
    }
  }

  private static final int CHUNK = 1 << 16;

  /**
   * Every FLOAT pattern through `root`, a kernel over one FLOAT column with `lits` its int
   * literal table, each row against `want` of the pattern: the same bits, or both NaN where
   * `nanByNess`. A strided slice is also held to Spark's `spark` expression over the column.
   */
  private static void sweepFloat(String ctx, VarkaVectorIR root, int[] lits,
      IntUnaryOperator want, boolean nanByNess, Optional<Expression> spark) {
    var emitted = VarkaEmitterFixtures.emit(List.of(root), 1, lits.length, VarkaMatrixRuns.BASE);
    var loaders = new ConcurrentLinkedQueue<VarkaGeneratedClassLoader>();
    ThreadLocal<VarkaFusedKernel> kernels = ThreadLocal.withInitial(() -> {
      var loaded = VarkaEmitterFixtures.load(emitted);
      loaders.add(loaded.loader());
      return loaded.kernel();
    });
    var failure = new AtomicReference<String>();
    long chunks = (1L << 32) / CHUNK;
    try {
      LongStream.range(0, chunks).parallel().forEach(k -> {
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
          Output out = VarkaEmitterFixtures.makeOutput(arena, CHUNK, -1L);
          int status = kernel.run(new long[] {in.address()}, new long[] {0L}, new int[] {0},
              new long[] {out.data().address()}, new long[] {out.validity().address()}, lits,
              CHUNK, VarkaEmitterTestSupport.scratch(kernel, CHUNK));
          if (status != 0) {
            failure.compareAndSet(null, ctx + ": status " + status + " at pattern " + first);
          }
          for (int i = 0; i < CHUNK && failure.get() == null; i++) {
            int pattern = (int) (first + i);
            int got = out.data().get(ValueLayout.JAVA_INT, i * 4L);
            int expected = want.applyAsInt(pattern);
            boolean same = got == expected || nanByNess
                && Float.isNaN(Float.intBitsToFloat(got))
                && Float.isNaN(Float.intBitsToFloat(expected));
            if (!same) {
              failure.compareAndSet(null, String.format(
                  "%s: over %08x the kernel gave %08x and the definition %08x",
                  ctx, pattern, got, expected));
            }
            if ((pattern & 0xfff) == 0x5a5 && spark.isPresent()) {
              float v = (Float) spark.get().eval(
                  new GenericInternalRow(new Object[] {Float.intBitsToFloat(pattern)}));
              int sb = Float.floatToRawIntBits(v);
              if (sb != got && !(nanByNess && Float.isNaN(v))) {
                failure.compareAndSet(null, String.format(
                    "%s: over %08x the kernel gave %08x and Spark %08x", ctx, pattern, got, sb));
              }
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

  private static final BoundReference FLOAT_COL =
      new BoundReference(0, DataTypes.FloatType, true);

  @Test
  @DisplayName("the negation and abs of every FLOAT pattern are Spark's bits")
  public void negationAndAbsOfEveryFloatPatternAreSparksBits() {
    // Spark's UnaryMinus and Abs take their failOnError from the session's ANSI flag by default;
    // at FLOAT neither checks anything, so the flag only keeps the expressions Spark's own.
    boolean ansi = SQLConf.get().ansiEnabled();
    sweepFloat("-f", new IntNeg(Overflow.WRAP, F), new int[0],
        p -> Float.floatToRawIntBits(-Float.intBitsToFloat(p)), false,
        Optional.of(UnaryMinus$.MODULE$.apply(FLOAT_COL, ansi)));
    sweepFloat("abs(f)", new IntNeg(Overflow.WRAP, F, true), new int[0],
        p -> Float.floatToRawIntBits(Math.abs(Float.intBitsToFloat(p))), false,
        Optional.of(Abs$.MODULE$.apply(FLOAT_COL, ansi)));
  }

  @Test
  @DisplayName("+, - and * of every FLOAT pattern against sixteen operands are Java's float "
      + "operators (opt-in: -Dvarka.sweep=true; VARKA-342)")
  public void binaryOperatorsOfEveryFloatPatternAreJavasFloatOperators() {
    assumeTrue("true".equals(System.getProperty("varka.sweep")),
        "set -Dvarka.sweep=true to sweep the emitted kernels");
    var k = new LiteralSlot(0, LaneType.FLOAT);
    for (int operand : SWEEP_OPERANDS) {
      for (IntOp op : List.of(IntOp.ADD, IntOp.SUB, IntOp.MUL)) {
        float b = Float.intBitsToFloat(operand);
        IntUnaryOperator want = p -> {
          float a = Float.intBitsToFloat(p);
          return Float.floatToRawIntBits(switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case DIV -> a / b;
          });
        };
        sweepFloat(String.format("%s by %08x", op, operand),
            new IntArith(op, Overflow.WRAP, F, k), new int[] {operand}, want, true,
            Optional.empty());
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Planted faults: each check above fails on a plausible wrong lowering (milestone 8 row 504)
  // -------------------------------------------------------------------------------------------

  /** Runs {@code check} with {@code fault} planted, and requires it to fail. */
  private static void failsUnder(VarkaFloatingFault fault, String what, Runnable check) {
    VarkaFloatingFault.plantForTesting(fault);
    try {
      assertThrows(AssertionError.class, check::run, what + " passes with " + fault + " planted");
    } finally {
      VarkaFloatingFault.plantForTesting(VarkaFloatingFault.NONE);
    }
  }

  @Test
  @DisplayName("a checked division that never tests a -0.0 divisor fails the zero-divisor "
      + "checks and the guard verdict")
  public void divisionWithoutNegativeZeroTestFails() {
    // The kernels are emitted directly, never through the class cache, so no faulty class
    // outlives the test.
    failsUnder(VarkaFloatingFault.DIVISOR_POSITIVE_ZERO_ONLY, "the NULL division's cells",
        this::zeroDivisorOfEitherSignNullsADivisionUnderNull);
    failsUnder(VarkaFloatingFault.DIVISOR_POSITIVE_ZERO_ONLY, "the FAIL division's cells",
        this::liveZeroDivisorDeclinesAFailDivision);
    // The guard verdict requires a live -0.0 divisor to decline; the faulty kernel answers it.
    var div = new IntArith(IntOp.DIV, Overflow.FAIL, X, Y);
    long[][] values = {{Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(-0.0)}};
    boolean[][] isNull = {{false, false}};
    VarkaGuardSpecs.Verdict verdict = VarkaGuardSpecs.verdict(List.of(div), 2, new long[0],
        VarkaMatrixRuns.BASE, true, values, isNull, List.of());
    assertEquals(VarkaFusedKernel.STATUS_DIVIDE_BY_ZERO, verdict.must());
    failsUnder(VarkaFloatingFault.DIVISOR_POSITIVE_ZERO_ONLY, "the guard verdict", () -> {
      int status = runDouble(div, new Double[] {1.0}, new Double[] {-0.0}).status();
      assertTrue(verdict.admits(status), "status " + status + " where the verdict is "
          + verdict);
    });
  }

  @Test
  @DisplayName("negation built as 0.0 - x fails the bit-for-bit check against Spark at 0.0")
  public void negationAsSubtractionFails() {
    failsUnder(VarkaFloatingFault.NEGATION_AS_SUBTRACTION, "DOUBLE -x",
        () -> doubleMatchesSparkBitForBit("-x"));
    failsUnder(VarkaFloatingFault.NEGATION_AS_SUBTRACTION, "FLOAT -f",
        () -> floatMatchesSparkBitForBit("-f"));
  }

  @Test
  @DisplayName("degrees multiplying by a rounded constant fails the check against Spark")
  public void degreesByARoundedConstantFails() {
    failsUnder(VarkaFloatingFault.DEGREES_ROUNDED, "DOUBLE degrees(x)",
        () -> doubleMatchesSpark("degrees(x)"));
  }
}
