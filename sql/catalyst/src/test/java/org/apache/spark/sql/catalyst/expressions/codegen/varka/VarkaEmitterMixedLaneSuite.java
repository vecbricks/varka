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
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Least;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Or;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * Kernels that hold both lanes (VARKA-28): a widening of an int value into the long lane, a
 * narrowing below another node, and the trees the casts and Catalyst's implicit promotions build
 * from them, run against {@link VarkaReference#mixedValue} at the long lane's two widths, over
 * both of the kernel's bodies, both validity fills and poisoned null lanes
 * ({@link VarkaKernelChecks#runAndCompareMixed}). Such a kernel is driven at the long lane and
 * holds its int values in the low half of the int species of the same width; the lengths are
 * chosen so that the epilogue holds no row, a few, and all but one of a lane group, at both
 * widths.
 *
 * <p>In a JVM of its own: the two widths are two species of each lane type, which the shared JVM
 * refuses (VARKA-246).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterMixedLaneSuite {

  private static final ColumnRef I = new ColumnRef(0);
  private static final ColumnRef L = new ColumnRef(1, LaneType.LONG);
  private static final ColumnRef I2 = new ColumnRef(2);
  private static final LiteralSlot INT_LIT = new LiteralSlot(0);
  private static final LiteralSlot LONG_LIT = new LiteralSlot(0, LaneType.LONG);

  /** The long lane's counts at 128 and 512 bits. */
  private static final int[] WIDTHS = {2, 8};
  private static final int[] LENGTHS = {1, 2, 7, 8, 9, 15, 16, 17, 64, 129};

  /** Column values: int extremes and neighbours in the int columns, long ones in the long. */
  private static long value(int c, int i) {
    long[] ints = {Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1, 7,
        -7, 1 << 20, -(1 << 20), Integer.MAX_VALUE - 1, 12345};
    long[] longs = {Long.MIN_VALUE, Long.MAX_VALUE, 0, -1, 1, 1L << 40, -(1L << 40),
        Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE + 1L, Integer.MIN_VALUE - 1L,
        0xFFFFFFFFL, 3L << 31, -5};
    return c == 1 ? longs[(i * 3 + c) % longs.length] : ints[(i + c * 5) % ints.length];
  }

  /** The same, kept where no narrowing of a long column overflows an int. */
  private static long fitting(int c, int i) {
    long v = value(c, i);
    return c == 1 ? (int) v : v;
  }

  private static final List<IntPredicate> PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  private interface Data {
    long at(int c, int i);
  }

  private static VarkaKernelChecks.LongBatch batch(int numInputs, int length, int pattern,
      boolean forceMasked, Data data) {
    var nulls = new boolean[numInputs][length];
    var values = new long[numInputs][length];
    for (int c = 0; c < numInputs; c++) {
      IntPredicate p = PATTERNS.get((pattern + c) % PATTERNS.size());
      for (int i = 0; i < length; i++) {
        nulls[c][i] = p.test(i);
        values[c][i] = data.at(c, i);
      }
    }
    return new VarkaKernelChecks.LongBatch(length, nulls, values, forceMasked);
  }

  /**
   * {@code roots} emitted at both widths and compared over every length and null pattern; with
   * {@code declines} the kernel may decline a batch, which is then not compared.
   */
  private static void check(String name, List<VarkaVectorIR> roots, int numInputs,
      int[] intLits, long[] longLits, Data data, boolean declines) {
    for (int lanes : WIDTHS) {
      var options = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
      var emitted = VarkaEmitterFixtures.emit(roots, numInputs,
          intLits.length + longLits.length, options);
      for (int length : LENGTHS) {
        for (int pattern = 0; pattern < PATTERNS.size(); pattern++) {
          String context = name + " at " + lanes + " long lanes, length " + length
              + ", pattern " + pattern;
          VarkaKernelChecks.runAndCompareMixed(context, emitted.className(), emitted.bytes(),
              roots, numInputs, intLits, longLits,
              batch(numInputs, length, pattern, length > 1 && pattern == 0, data), declines,
              null);
        }
      }
    }
  }

  private static WidenLane widen(VarkaVectorIR child) {
    return new WidenLane(child);
  }

  private static NarrowLane narrow(VarkaVectorIR child) {
    return new NarrowLane(child);
  }

  @Test
  @DisplayName("a widened int column is the column sign-extended, at both widths")
  void widenColumn() {
    check("cast(i AS bigint)", List.of(widen(I)), 1, new int[0], new long[0], (c, i) ->
        value(0, i), false);
  }

  @Test
  @DisplayName("an int computed and then widened is the int computation sign-extended")
  void widenComputed() {
    check("cast(i + 7 AS bigint)", List.of(widen(new IntArith(IntOp.ADD, Overflow.WRAP, I,
        INT_LIT))), 1, new int[] {7}, new long[0], (c, i) -> value(0, i), false);
    check("cast(year(d) AS bigint)", List.of(widen(new Year(I))), 1, new int[0], new long[0],
        (c, i) -> VarkaEmitterFixtures.calendarDays(c, i), false);
    check("cast(i / 7 AS bigint)", List.of(widen(new ConstDivide(I, 7))), 1, new int[0],
        new long[0], (c, i) -> value(0, i), false);
  }

  @Test
  @DisplayName("a narrowing below an int node is the low 32 bits, computed on at the int lane")
  void narrowBelow() {
    check("cast(l AS int) + i", List.of(new IntArith(IntOp.ADD, Overflow.WRAP, narrow(L), I)), 2,
        new int[0], new long[0], VarkaEmitterMixedLaneSuite::value, false);
    check("cast(l AS int) as a root read again by another node",
        List.of(narrow(L), new IntArith(IntOp.SUB, Overflow.WRAP, narrow(L), I)), 2, new int[0],
        new long[0], VarkaEmitterMixedLaneSuite::value, false);
  }

  @Test
  @DisplayName("the promotions an int meets a bigint through compare in the long lane")
  void promotions() {
    var lt = new Compare(CompareOp.LT, widen(I), L);
    var eq = new Compare(CompareOp.EQ, L, widen(I));
    check("i < l as a selection", List.of(lt), 2, new int[0], new long[0],
        VarkaEmitterMixedLaneSuite::value, false);
    check("l = i as a selection", List.of(eq), 2, new int[0], new long[0],
        VarkaEmitterMixedLaneSuite::value, false);
    check("CASE WHEN i < l THEN i ELSE l END", List.of(new IfElse(lt, widen(I), L)), 2,
        new int[0], new long[0], VarkaEmitterMixedLaneSuite::value, false);
    check("greatest(i, l) and least(l, i2)", List.of(new Greatest(widen(I), L),
        new Least(L, widen(I2))), 3, new int[0], new long[0], VarkaEmitterMixedLaneSuite::value,
        false);
    check("i < l OR i2 IS NOT NULL", List.of(new Or(lt, new IsNotNull(new ColumnRef(2,
        LaneType.LONG)))), 3, new int[0], new long[0], VarkaEmitterMixedLaneSuite::value, false);
  }

  @Test
  @DisplayName("a tree that widens, computes and narrows again, with literals of both lanes")
  void roundTrip() {
    var widened = widen(new IntArith(IntOp.ADD, Overflow.WRAP, I, INT_LIT));
    check("cast(greatest(i + 3, l) AS int)", List.of(narrow(new Greatest(widened, L))), 2,
        new int[] {3}, new long[0], VarkaEmitterMixedLaneSuite::value, false);
    check("cast(least(cast(i + 3 AS bigint), 5000000000) AS int) + i",
        List.of(new IntArith(IntOp.ADD, Overflow.WRAP, narrow(new Least(widened, LONG_LIT)), I)),
        2, new int[] {3}, new long[] {5000000000L}, VarkaEmitterMixedLaneSuite::value, false);
    check("cast(cast(i AS bigint) AS int) is i", List.of(narrow(widen(I))), 1, new int[0],
        new long[0], (c, i) -> value(0, i), false);
  }

  /** The ANSI narrowing: the range guard of an int over the long child, and the narrowing. */
  private static VarkaVectorIR ansiNarrow(VarkaVectorIR child) {
    return narrow(new GuardedRange(child, Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  @Test
  @DisplayName("the ANSI narrowing answers every value that fits an int and declines any other")
  void ansiNarrowing() {
    // Over values that all fit, it is the narrowing and never declines.
    check("ANSI cast(l AS int)", List.of(ansiNarrow(L)), 2, new int[0], new long[0],
        VarkaEmitterMixedLaneSuite::fitting, false);
    check("ANSI cast(l AS int) + i", List.of(new IntArith(IntOp.ADD, Overflow.WRAP,
        ansiNarrow(L), I)), 2, new int[0], new long[0], VarkaEmitterMixedLaneSuite::fitting,
        false);
    // Over values that do not, a batch holding a valid one past the int range declines with the
    // value-range status, and a batch whose every such value is null is answered.
    var roots = List.<VarkaVectorIR>of(new IntArith(IntOp.ADD, Overflow.WRAP, ansiNarrow(L), I));
    for (int lanes : WIDTHS) {
      var emitted = VarkaEmitterFixtures.emit(roots, 2, 0,
          VarkaMatrixRuns.BASE.withLanesOverride(lanes));
      for (int length : LENGTHS) {
        for (int at = 0; at < length; at += Math.max(1, length / 4)) {
          final int row = at;
          var over = batch(2, length, 0, false,
              (c, i) -> c == 1 && i == row ? Integer.MAX_VALUE + 1L : fitting(c, i));
          int status = VarkaKernelChecks.runAndCompareMixedStatus("ANSI over at row " + row
              + ", length " + length + ", " + lanes + " lanes", emitted.className(),
              emitted.bytes(), roots, 2, new int[0], new long[0], over, true);
          assertEquals(VarkaFusedKernel.STATUS_VALUE_RANGE, status,
              "a value past the int range at row " + row + " of " + length + " must decline");
        }
      }
    }
  }

  @Test
  @DisplayName("an int-lane guard in a kernel driven at the long lane condemns only the group's "
      + "rows, never the int species' upper lanes")
  void intGuardInLongKernel() {
    // `i - lit` with the literal at Integer.MIN_VALUE overflows exactly where i >= 0. The
    // upper int lanes of a long-driven kernel's epilogue read zero, which would overflow and
    // condemn every batch if they were collected; every drawn value here is negative, so
    // nothing may decline.
    var checked = widen(new IntArith(IntOp.SUB, Overflow.FAIL, I, INT_LIT));
    check("cast(i - MIN_VALUE AS bigint) over negative i", List.of(checked), 1,
        new int[] {Integer.MIN_VALUE}, new long[0], (c, i) -> -1 - (i * 7919L % 100000), false);
    // And where a row does overflow, the batch declines.
    var roots = List.<VarkaVectorIR>of(checked);
    var emitted = VarkaEmitterFixtures.emit(roots, 1, 1, VarkaMatrixRuns.BASE.withLanesOverride(8));
    int status = VarkaKernelChecks.runAndCompareMixedStatus("an overflowing row",
        emitted.className(), emitted.bytes(), roots, 1, new int[] {Integer.MIN_VALUE},
        new long[0], batch(1, 17, 0, false, (c, i) -> i == 11 ? 5 : -3), true);
    assertEquals(VarkaFusedKernel.STATUS_INT_OVERFLOW, status);
    // The loop reads a whole int vector, so at eight long lanes and 17 rows its one iteration
    // loads rows 8 to 15 into the upper lanes too. Row 11 overflows but is null: in the masked
    // body neither that load nor its own group may decline the batch. The dense body, run on the
    // same values with no nulls, sees row 11 valid and declines.
    var nulls = new boolean[1][17];
    nulls[0][11] = true;
    var values = new long[1][17];
    for (int i = 0; i < 17; i++) {
      values[0][i] = i == 11 ? 5 : -3;
    }
    var statuses = VarkaKernelChecks.runEachBodyMixedStatus("an overflowing null row",
        emitted.className(), emitted.bytes(), roots, 1, new int[] {Integer.MIN_VALUE},
        new long[0], new VarkaKernelChecks.LongBatch(17, nulls, values, true));
    assertEquals(List.of(0, VarkaFusedKernel.STATUS_INT_OVERFLOW), statuses);
  }

  @Test
  @DisplayName("a conjunction of int comparisons over a narrowing, a calendar field and two dates "
      + "verifies and answers, its date column guarded by the column bound")
  void conjunctionWithCalendar() {
    var d = new ColumnRef(2);
    var d2 = new ColumnRef(3);
    var bounded = new GuardedRange(d, VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS);
    var root = new And(new And(new Compare(CompareOp.GT, ansiNarrow(L), I),
        new Compare(CompareOp.EQ, new Year(bounded), INT_LIT)), new Compare(CompareOp.GT, d, d2));
    check("CAST(l AS INT) > i AND year(d) = 2021 AND d > d2", List.of(root), 4,
        new int[] {2021}, new long[0], (c, i) -> c == 1 ? fitting(c, i)
            : c >= 2 ? (i * 37L + c * 101L) % 40000 - 20000 : value(c, i), false);
  }

  @Test
  @DisplayName("a kernel of both lanes is driven at the long lane, and a single-lane kernel at "
      + "its own")
  void drivingLane() {
    assertEquals(LaneType.LONG, VarkaVectorIR.emissionLane(widen(I)));
    assertEquals(LaneType.LONG, VarkaVectorIR.emissionLane(
        new IntArith(IntOp.ADD, Overflow.WRAP, narrow(L), I)));
    assertEquals(LaneType.LONG, VarkaVectorIR.emissionLane(narrow(L)));
    assertEquals(LaneType.INT, VarkaVectorIR.emissionLane(new IntArith(IntOp.ADD,
        Overflow.WRAP, I, I2)));
    assertEquals(LaneType.LONG, VarkaVectorIR.emissionLane(new Compare(CompareOp.LT, L, L)));
  }

  @Test
  @DisplayName("the conversions refuse a child already on their target lane")
  void refusals() {
    assertThrows(IllegalArgumentException.class, () -> widen(L));
    assertThrows(IllegalArgumentException.class, () -> narrow(I));
    // A column read at two lanes is two columns, which one kernel cannot map at once.
    assertThrows(IllegalArgumentException.class, () -> VarkaEmitterFixtures.emit(
        List.of(new Compare(CompareOp.LT, widen(I), new ColumnRef(0, LaneType.LONG))), 1, 0,
        VarkaMatrixRuns.BASE));
  }

  @Test
  @Tag(VarkaSuite.PINS_DEFAULTS)
  @DisplayName("a kernel of both lanes names one species per lane type, the width's own")
  void oneSpeciesPerLane() {
    // The int values of a long-driven kernel sit in the int species of the long species' width,
    // never a half-width one, which would put a second int species in the JVM (VARKA-28.md 2.2).
    for (int lanes : WIDTHS) {
      var emitted = VarkaEmitterFixtures.emit(List.of(narrow(new Greatest(widen(I), L))), 2, 0,
          VarkaMatrixRuns.BASE.withLanesOverride(lanes));
      var named = new TreeSet<String>();
      for (PoolEntry entry : ClassFile.of().parse(emitted.bytes()).constantPool()) {
        if (entry instanceof FieldRefEntry field
            && field.name().stringValue().startsWith("SPECIES_")) {
          named.add(field.owner().asInternalName().replace("jdk/incubator/vector/", "") + "."
              + field.name().stringValue());
        }
      }
      String bits = Integer.toString(lanes * 64);
      assertEquals(Set.of("IntVector.SPECIES_" + bits, "LongVector.SPECIES_" + bits), named);
    }
  }

  /**
   * Every int, widened (opt-in: {@code -Dvarka.sweep=true}): the int lane's whole range through
   * the widening kernel at the long lane's widest count, each output the input sign-extended, and
   * each int value as a long through the ANSI narrowing, each the value back without a decline.
   * The operands total 32 bits, so exhaustion is the proof (`m8/PLAN.md` 5).
   */
  @Test
  @DisplayName("every int widens to itself and narrows back to itself under ANSI "
      + "(opt-in: -Dvarka.sweep=true; VARKA-28)")
  void sweep() {
    assumeTrue("true".equals(System.getProperty("varka.sweep")),
        "set -Dvarka.sweep=true to sweep the conversions");
    int rows = 1 << 20;
    var widenKernel = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(List.of(widen(I)), 1,
        0, VarkaMatrixRuns.BASE.withLanesOverride(8)));
    // The narrowing under an add of zero, so it converts in the tree rather than at the store.
    var narrowKernel = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(
        List.of(new IntArith(IntOp.ADD, Overflow.WRAP, ansiNarrow(new ColumnRef(0, LaneType.LONG)),
            INT_LIT)), 1, 1, VarkaMatrixRuns.BASE.withLanesOverride(8)));
    try (var arena = Arena.ofConfined()) {
      MemorySegment ints = VarkaEmitterFixtures.alloc(arena, rows * 4L);
      MemorySegment longs = VarkaEmitterFixtures.alloc(arena, rows * 8L);
      MemorySegment back = VarkaEmitterFixtures.alloc(arena, rows * 4L);
      MemorySegment validity = VarkaEmitterFixtures.alloc(arena, rows / 8L);
      long[] none = {0L};
      int[] noNulls = {0};
      for (long start = Integer.MIN_VALUE; start <= Integer.MAX_VALUE; start += rows) {
        for (int i = 0; i < rows; i++) {
          ints.set(ValueLayout.JAVA_INT, i * 4L, (int) (start + i));
        }
        int status = widenKernel.kernel().run(new long[] {ints.address()}, none, noNulls,
            new long[] {longs.address()}, new long[] {validity.address()}, new int[0],
            new long[0], rows, VarkaEmitterTestSupport.scratch(widenKernel.kernel(), rows));
        assertEquals(0, status);
        for (int i = 0; i < rows; i++) {
          if (longs.get(ValueLayout.JAVA_LONG, i * 8L) != start + i) {
            fail("cast(" + (start + i) + " AS bigint) is "
                + longs.get(ValueLayout.JAVA_LONG, i * 8L));
          }
        }
        status = narrowKernel.kernel().run(new long[] {longs.address()}, none, noNulls,
            new long[] {back.address()}, new long[] {validity.address()}, new int[] {0},
            new long[0], rows, VarkaEmitterTestSupport.scratch(narrowKernel.kernel(), rows));
        assertEquals(0, status, "the ANSI narrowing declined a batch of ints from " + start);
        for (int i = 0; i < rows; i++) {
          if (back.get(ValueLayout.JAVA_INT, i * 4L) != (int) (start + i)) {
            fail("cast(" + (start + i) + "L AS int) is "
                + back.get(ValueLayout.JAVA_INT, i * 4L));
          }
        }
      }
    } finally {
      widenKernel.loader().release();
      narrowKernel.loader().release();
    }
  }
}
