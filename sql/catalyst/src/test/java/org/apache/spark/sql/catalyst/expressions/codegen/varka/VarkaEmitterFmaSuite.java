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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Col;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Output;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.ValidityFill;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Fma;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

/**
 * The fused multiply-add node (VARKA-342, for VARKA-346's remainder and round-to-odd):
 * {@code Fma(a, b, c)} at the {@code DOUBLE} and {@code FLOAT} lanes against the reference
 * evaluator's {@code Math.fma}, at two widths each, over every per-column null pattern.
 *
 * <p>No Spark expression is an FMA, so the reference is the only oracle. A non-NaN answer is
 * held bit for bit, a zero's sign included: the fused operation is IEEE's on every host, rounded
 * once. A NaN answer is held by NaN-ness, through {@link VarkaSameAnswer}'s row level, whose
 * rule opens every NaN of the node ({@code VarkaVectorIR.Fma}).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterFmaSuite {

  private static final ColumnRef X = new ColumnRef(0, LaneType.DOUBLE);
  private static final ColumnRef Y = new ColumnRef(1, LaneType.DOUBLE);
  private static final ColumnRef Z = new ColumnRef(2, LaneType.DOUBLE);
  private static final ColumnRef F = new ColumnRef(0, LaneType.FLOAT);
  private static final ColumnRef G = new ColumnRef(1, LaneType.FLOAT);
  private static final ColumnRef H = new ColumnRef(2, LaneType.FLOAT);

  /**
   * The operands the cells draw from, as doubles: both zeros, both infinities, a NaN, the
   * extremes, the smallest subnormal and normal, and normals whose product is inexact; at the
   * {@code FLOAT} lane each is narrowed, the double extremes becoming the float infinities.
   */
  private static final double[] SPECIALS = {
      0.0, -0.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
      Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_NORMAL, 1.0, -1.0,
      1.0 + 0x1.0p-27, 1.0 - 0x1.0p-27, 1.0 + 0x1.0p-13, 1.0 - 0x1.0p-13, 3.0, -0.1};

  /** The four null patterns the emitter suites share. */
  private static final List<IntPredicate> NULL_PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  private static final int[] LENGTHS = {1, 7, 129, SPECIALS.length * SPECIALS.length * 2};

  /** Row {@code i}'s operand of column {@code col}: every ordered triple in turn. */
  private static double operand(int col, int i) {
    int n = SPECIALS.length;
    return SPECIALS[switch (col) {
      case 0 -> i % n;
      case 1 -> (i / n) % n;
      default -> (i / (n * n) + i) % n;
    }];
  }

  @Test
  @DisplayName("an FMA rounds once: (1 + 2^-27)(1 - 2^-27) - 1 is -2^-54, where a multiply and "
      + "an add give 0.0")
  public void anFmaRoundsOnce() {
    double a = 1.0 + 0x1.0p-27;
    double b = 1.0 - 0x1.0p-27;
    assertEquals(-0x1.0p-54, Math.fma(a, b, -1.0), "the operands are the named case");
    assertEquals(0.0, a * b - 1.0, "and the two roundings lose it");
    var fused = new Fma(X, Y, Z);
    var twice = new IntArith(IntOp.ADD, Overflow.WRAP, new IntArith(IntOp.MUL, Overflow.WRAP,
        X, Y), Z);
    for (int lanes : new int[] {2, 8}) {
      long[] got = runDouble(List.of(fused, twice), new double[][] {{a}, {b}, {-1.0}}, lanes);
      assertEquals(Double.doubleToRawLongBits(-0x1.0p-54), got[0], "fused at " + lanes);
      assertEquals(Double.doubleToRawLongBits(0.0), got[1], "unfused at " + lanes);
    }
    // The float twin: (1 + 2^-13)(1 - 2^-13) is 1 - 2^-26, which a float rounds to 1.
    float fa = 1.0f + 0x1.0p-13f;
    float fb = 1.0f - 0x1.0p-13f;
    assertEquals(-0x1.0p-26f, Math.fma(fa, fb, -1.0f), "the float operands are the named case");
    var ffused = new Fma(F, G, H);
    var ftwice = new IntArith(IntOp.ADD, Overflow.WRAP, new IntArith(IntOp.MUL, Overflow.WRAP,
        F, G), H);
    for (int lanes : new int[] {4, 16}) {
      int[] got = runFloat(List.of(ffused, ftwice), new float[][] {{fa}, {fb}, {-1.0f}}, lanes);
      assertEquals(Float.floatToRawIntBits(-0x1.0p-26f), got[0], "fused at " + lanes);
      assertEquals(Float.floatToRawIntBits(0.0f), got[1], "unfused at " + lanes);
    }
  }

  @Test
  @DisplayName("an FMA at the DOUBLE lane computes what the reference says, at both widths and "
      + "over every null pattern of its three operands")
  public void anFmaAtTheDoubleLaneComputesWhatTheReferenceSays() {
    var k = new LiteralSlot(0, LaneType.DOUBLE);
    // The node over three columns, in another operand order, over one column twice and a
    // literal, and under a negation that passes its NaN on.
    List<VarkaVectorIR> roots = List.of(new Fma(X, Y, Z), new Fma(Z, X, Y), new Fma(X, X, k),
        new IntNeg(Overflow.WRAP, new Fma(Y, k, X)));
    long[] lits = {Double.doubleToRawLongBits(-0.1)};
    for (int lanes : new int[] {2, 8}) {
      checkMatrix(roots, lits, LaneType.DOUBLE, lanes);
    }
  }

  @Test
  @DisplayName("an FMA at the FLOAT lane computes Math.fma's float form, at both widths and over "
      + "every null pattern of its three operands")
  public void anFmaAtTheFloatLaneComputesWhatTheReferenceSays() {
    var k = new LiteralSlot(0, LaneType.FLOAT);
    List<VarkaVectorIR> roots = List.of(new Fma(F, G, H), new Fma(H, F, G), new Fma(F, F, k),
        new IntNeg(Overflow.WRAP, new Fma(G, k, F)));
    long[] lits = {Float.floatToRawIntBits(-0.1f)};
    for (int lanes : new int[] {4, 16}) {
      checkMatrix(roots, lits, LaneType.FLOAT, lanes);
    }
  }

  @Test
  @DisplayName("an FMA takes three operands of one floating lane")
  public void anFmaTakesThreeOperandsOfOneFloatingLane() {
    var i = new ColumnRef(0);
    var l = new ColumnRef(0, LaneType.LONG);
    assertThrows(IllegalArgumentException.class, () -> new Fma(i, i, i));
    assertThrows(IllegalArgumentException.class, () -> new Fma(l, l, l));
    assertThrows(IllegalArgumentException.class, () -> new Fma(X, Y, H));
    assertThrows(IllegalArgumentException.class, () -> new Fma(F, Y, Z));
    assertEquals(LaneType.FLOAT, new Fma(F, G, H).laneType());
    var fma = new Fma(X, Y, new IntNeg(Overflow.WRAP, Z));
    assertEquals("(fma col:0:double col:1:double (neg:WRAP col:2:double))",
        VarkaVectorIR.canonical(fma));
    assertEquals(fma, VarkaCanonicalIr.parse(VarkaVectorIR.canonical(fma)),
        "the rendering parses back to the node");
  }

  @Test
  @DisplayName("every NaN an FMA answers is open, even over operands that are not NaNs, and a "
      + "multiply's NaN over the same operands is not")
  public void everyNaNAnFmaAnswersIsOpen() {
    double inf = Double.POSITIVE_INFINITY;
    var fma = new Fma(X, Y, Z);
    var product = new IntArith(IntOp.MUL, Overflow.WRAP, X, Y);
    // inf * 0 + 1: no operand is a NaN, and the instruction answers the host's default NaN
    // where Math.fma run in Java answers the canonical one.
    var row = irRow(Map.of(X, inf, Y, 0.0, Z, 1.0, fma, Double.NaN, product, Double.NaN));
    assertTrue(VarkaSameAnswer.openNaN(fma, row));
    assertFalse(VarkaSameAnswer.openNaN(product, row));
    long want = Double.doubleToRawLongBits(Double.NaN);
    long got = 0xfff8000000000000L;
    assertTrue(VarkaSameAnswer.sameAnswer(fma, want, got, row));
    assertFalse(VarkaSameAnswer.sameAnswer(product, want, got, row));
    // A number is never another number, nor a zero the other zero.
    var zeros = irRow(Map.of(X, 0.0, Y, 1.0, Z, -0.0, fma, 0.0));
    assertFalse(VarkaSameAnswer.openNaN(fma, zeros));
    assertFalse(VarkaSameAnswer.sameAnswer(fma, Double.doubleToRawLongBits(0.0),
        Double.doubleToRawLongBits(-0.0), zeros));
  }

  // -------------------------------------------------------------------------------------------
  // The harness
  // -------------------------------------------------------------------------------------------

  /** A row of the IR rule whose node values are given; a node not given is null. */
  private static VarkaSameAnswer.IrRow irRow(Map<VarkaVectorIR, Double> values) {
    return new VarkaSameAnswer.IrRow() {
      @Override public OptionalLong value(VarkaVectorIR node) {
        Double v = values.get(node);
        return v == null ? OptionalLong.empty()
            : OptionalLong.of(Double.doubleToRawLongBits(v));
      }

      @Override public boolean holds(VarkaVectorIR.Cond cond) {
        return false;
      }
    };
  }

  /** One kernel over three valid {@code DOUBLE} columns: each output's bits at row 0. */
  private static long[] runDouble(List<VarkaVectorIR> roots, double[][] cols, int lanes) {
    var options = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
    var loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(roots, 3, 0, options));
    try (Arena arena = Arena.ofConfined()) {
      var in = new Col[3];
      for (int c = 0; c < 3; c++) {
        double[] vs = cols[c];
        in[c] = VarkaEmitterFixtures.makeLongInput(arena, vs.length, i -> false,
            i -> Double.doubleToRawLongBits(vs[i]), true);
      }
      var outs = new Output[roots.size()];
      for (int o = 0; o < outs.length; o++) {
        outs[o] = VarkaEmitterFixtures.makeLongOutput(arena, 1);
      }
      int status = loaded.kernel().run(dataAddresses(in), validityAddresses(in, 1),
          nullCounts(in), outputData(outs), outputValidity(outs), new int[0], new long[0], 1,
          VarkaEmitterTestSupport.scratch(loaded.kernel(), 1));
      assertEquals(0, status, "no batch is declined");
      var got = new long[outs.length];
      for (int o = 0; o < outs.length; o++) {
        got[o] = outs[o].data().get(ValueLayout.JAVA_LONG, 0);
      }
      return got;
    } finally {
      loaded.loader().release();
    }
  }

  /** {@link #runDouble}'s twin at the {@code FLOAT} lane, the int kernel's {@code run}. */
  private static int[] runFloat(List<VarkaVectorIR> roots, float[][] cols, int lanes) {
    var options = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
    var loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(roots, 3, 0, options));
    try (Arena arena = Arena.ofConfined()) {
      var in = new Col[3];
      for (int c = 0; c < 3; c++) {
        float[] vs = cols[c];
        in[c] = VarkaEmitterFixtures.makeInputData(arena, vs.length, i -> false,
            i -> Float.floatToRawIntBits(vs[i]), true);
      }
      var outs = new Output[roots.size()];
      for (int o = 0; o < outs.length; o++) {
        outs[o] = VarkaEmitterFixtures.makeOutput(arena, 1,
            VarkaEmitterFixtures.outputValidityBytes(options, 1));
      }
      int status = loaded.kernel().run(dataAddresses(in), validityAddresses(in, 1),
          nullCounts(in), outputData(outs), outputValidity(outs), new int[0], 1,
          VarkaEmitterTestSupport.scratch(loaded.kernel(), 1));
      assertEquals(0, status, "no batch is declined");
      var got = new int[outs.length];
      for (int o = 0; o < outs.length; o++) {
        got[o] = outs[o].data().get(ValueLayout.JAVA_INT, 0);
      }
      return got;
    } finally {
      loaded.loader().release();
    }
  }

  /**
   * Emits the outputs once at {@code lanes} lanes over three columns of {@code lane}, then runs
   * every length and every per-column null pattern at both validity fills, holding each row to
   * the reference: the validity exactly, and the value by {@link VarkaSameAnswer#sameAnswer}.
   * A {@code FLOAT}'s literal is its bits in the low half of the long.
   */
  private static void checkMatrix(List<VarkaVectorIR> roots, long[] lits, LaneType lane,
      int lanes) {
    boolean isDouble = lane == LaneType.DOUBLE;
    var options = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(roots, 3, lits.length, options));
    int[] intLits = new int[lits.length];
    for (int j = 0; j < lits.length; j++) {
      intLits[j] = (int) lits[j];
    }
    try {
      for (int length : LENGTHS) {
        for (IntPredicate p0 : NULL_PATTERNS) {
          for (IntPredicate p1 : NULL_PATTERNS) {
            for (IntPredicate p2 : NULL_PATTERNS) {
              IntPredicate[] combo = {p0, p1, p2};
              String ctx = lane + " at " + lanes + " lanes, length " + length + ", nulls "
                  + NULL_PATTERNS.indexOf(p0) + NULL_PATTERNS.indexOf(p1)
                  + NULL_PATTERNS.indexOf(p2);
              runCase(loaded.kernel(), roots, lits, intLits, isDouble, options, length, combo,
                  ctx);
            }
          }
        }
      }
    } finally {
      loaded.loader().release();
    }
  }

  private static void runCase(VarkaFusedKernel kernel, List<VarkaVectorIR> roots, long[] lits,
      int[] intLits, boolean isDouble, VarkaEmitOptions options, int length,
      IntPredicate[] combo, String ctx) {
    try (Arena arena = Arena.ofConfined()) {
      var cols = new Col[3];
      for (int c = 0; c < 3; c++) {
        int col = c;
        cols[c] = isDouble
            ? VarkaEmitterFixtures.makeLongInput(arena, length, combo[c],
                i -> Double.doubleToRawLongBits(operand(col, i)), true)
            : VarkaEmitterFixtures.makeInputData(arena, length, combo[c],
                i -> Float.floatToRawIntBits((float) operand(col, i)), true);
      }
      for (ValidityFill fill : ValidityFill.BOTH) {
        var outs = new Output[roots.size()];
        for (int o = 0; o < outs.length; o++) {
          outs[o] = isDouble ? VarkaEmitterFixtures.makeLongOutput(arena, length, fill)
              : VarkaEmitterFixtures.makeOutput(arena, length,
                  VarkaEmitterFixtures.outputValidityBytes(options, length), fill);
        }
        int status = isDouble
            ? kernel.run(dataAddresses(cols), validityAddresses(cols, length), nullCounts(cols),
                outputData(outs), outputValidity(outs), new int[0], lits, length,
                VarkaEmitterTestSupport.scratch(kernel, length))
            : kernel.run(dataAddresses(cols), validityAddresses(cols, length), nullCounts(cols),
                outputData(outs), outputValidity(outs), intLits, length,
                VarkaEmitterTestSupport.scratch(kernel, length));
        if (status != 0) {
          fail(ctx + ": the kernel declined a batch (" + fill + ", status " + status + ")");
        }
        for (int i = 0; i < length; i++) {
          checkRow(roots, outs, lits, intLits, isDouble, combo, i, ctx + ", " + fill);
        }
      }
    }
  }

  private static void checkRow(List<VarkaVectorIR> roots, Output[] outs, long[] lits,
      int[] intLits, boolean isDouble, IntPredicate[] combo, int i, String ctx) {
    var isNull = new boolean[3];
    var longRow = new long[3];
    var intRow = new int[3];
    for (int c = 0; c < 3; c++) {
      isNull[c] = combo[c].test(i);
      longRow[c] = isNull[c] ? 0L : Double.doubleToRawLongBits(operand(c, i));
      intRow[c] = isNull[c] ? 0 : Float.floatToRawIntBits((float) operand(c, i));
    }
    VarkaSameAnswer.IrRow row = new VarkaSameAnswer.IrRow() {
      @Override public OptionalLong value(VarkaVectorIR node) {
        if (isDouble) {
          return VarkaReference.longValue(node, longRow, isNull, lits);
        }
        OptionalInt v = VarkaReference.value(node, intRow, isNull, intLits);
        return v.isPresent() ? OptionalLong.of(v.getAsInt()) : OptionalLong.empty();
      }

      @Override public boolean holds(VarkaVectorIR.Cond cond) {
        return false;
      }
    };
    for (int o = 0; o < roots.size(); o++) {
      OptionalLong want = row.value(roots.get(o));
      boolean bit = validBit(outs[o].validity(), i);
      String where = ctx + ", out " + o + ", row " + i;
      if (bit != want.isPresent()) {
        fail(where + ": validity differs (want " + want + ")");
      }
      if (want.isPresent()) {
        long got = isDouble ? outs[o].data().get(ValueLayout.JAVA_LONG, i * 8L)
            : outs[o].data().get(ValueLayout.JAVA_INT, i * 4L);
        if (!VarkaSameAnswer.sameAnswer(roots.get(o), want.getAsLong(), got, row)) {
          fail(where + ": value (want " + Long.toHexString(want.getAsLong()) + ", got "
              + Long.toHexString(got) + ")");
        }
      }
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

  private static long[] outputData(Output[] outs) {
    var addresses = new long[outs.length];
    for (int o = 0; o < outs.length; o++) {
      addresses[o] = outs[o].data().address();
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
}
