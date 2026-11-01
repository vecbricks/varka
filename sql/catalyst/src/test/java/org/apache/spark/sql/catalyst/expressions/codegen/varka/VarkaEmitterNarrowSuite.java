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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntBinaryOperator;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Emitted;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Loaded;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Output;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.Batch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.ValidityFill;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.InRanges;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Least;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * TINYINT and SMALLINT columns, stored narrow and computed either on the int lane (the widened
 * arm, the default) or on the narrow lane itself (the native arm, `narrowLanes`), VARKA-329.
 *
 * <p>Every value of a byte column is written and read back at every tail length and on a metre
 * batch that puts each value on every lane position; every short value is sampled across the
 * domain with its two extremes; every ordered byte pair runs through the comparisons, a range set,
 * `greatest`, `least` and a choice, 2^16 rows in one batch; a narrow column read beside an int
 * column is widened on load in an int-lane kernel; and the two arms answer the same, since each
 * is checked against {@link VarkaReference} on the same batch.
 *
 * <p>In its own JVM: the suite sweeps `lanesOverride` and holds narrow and int species at once.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterNarrowSuite {

  /** The options as the matrix configured them, with the arm's {@code narrowLanes} set. */
  private static VarkaEmitOptions arm(boolean narrowLanes) {
    return VarkaMatrixRuns.BASE.toBuilder().narrowLanes(narrowLanes).build();
  }

  /**
   * The default arm: narrow values stored narrow, computed on the int lane. The option is set
   * explicitly rather than left to the matrix's base, which is this suite's own subject: under
   * `narrowLanes=true` the base has the other arm, and the pair must still be the pair.
   */
  private static final VarkaEmitOptions WIDENED = arm(false);

  /** The native arm: a tree whose every value is narrow stays on the narrow lane. */
  private static final VarkaEmitOptions NATIVE = arm(true);

  private record Arm(String name, VarkaEmitOptions options) {}

  private record NullPattern(String name, IntPredicate isNull, boolean forceMasked) {}

  /** A column's null function over a row: {@code (column, row) -> is the row null}. */
  private interface RowNulls {
    boolean test(int column, int row);
  }

  private static final List<Arm> ARMS =
      List.of(new Arm("the widened arm", WIDENED), new Arm("the native arm", NATIVE));

  /**
   * The widened arm forced to four and two int lanes: the widths a 128-bit host and a host whose
   * widest vector is 64 bits emit at with no override, where a widening load spans two or four
   * parts and no other suite draws the width.
   */
  private static final VarkaEmitOptions FOUR_LANES =
      WIDENED.toBuilder().lanesOverride(4).build();
  private static final VarkaEmitOptions TWO_LANES =
      WIDENED.toBuilder().lanesOverride(2).build();

  private static final VarkaVectorIR BYTE_COL = new ColumnRef(0, LaneType.BYTE);
  private static final VarkaVectorIR BYTE_COL_2 = new ColumnRef(1, LaneType.BYTE);
  private static final VarkaVectorIR SHORT_COL = new ColumnRef(0, LaneType.SHORT);
  private static final VarkaVectorIR SHORT_COL_2 = new ColumnRef(1, LaneType.SHORT);

  private static final List<Integer> ROUND_TRIP_LENGTHS = List.of(
      1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 257);

  /**
   * The null patterns a narrow column runs at: none, every seventh row, 32-row groups, and the
   * same all-valid batch with a mask forced. The last one makes the masked body run over no null
   * lane, and the first the dense body, which is the pair of bodies the kernel must keep apart.
   */
  private static List<NullPattern> patterns(int length) {
    var all = new ArrayList<NullPattern>();
    all.add(new NullPattern("with no nulls", row -> false, false));
    all.add(new NullPattern("with every seventh row null", row -> row % 7 == 3, false));
    all.add(new NullPattern("with null 32-row groups", row -> (row / 32) % 3 == 0, false));
    // A forced mask reports one null over a full bitmap, which at a single row is
    // indistinguishable from an all-null column: the kernel's all-null shortcut would return
    // before writing anything, as the morsel contract lets it, so one-row batches skip it.
    if (length > 1) {
      all.add(new NullPattern("with a forced mask", row -> false, true));
    }
    return all;
  }

  /** {@code v} as {@code lane} stores it, sign-extended - what the reference reads back. */
  private static int narrow(LaneType lane, int v) {
    return switch (lane) {
      case BYTE -> (byte) v;
      case SHORT -> (short) v;
      case INT, LONG, FLOAT, DOUBLE -> v;
    };
  }

  private static long width(LaneType lane) {
    return switch (lane) {
      case BYTE -> 1L;
      case SHORT -> 2L;
      case INT, FLOAT -> 4L;
      case LONG, DOUBLE -> 8L;
    };
  }

  /**
   * Cancels the test when the compiler bakes no int lane count at all, which is the one
   * configuration left that cannot name a narrow species: `validityByWidth=false` bakes no lane
   * count for the conversion to read. A width where the narrow species of the int species' own
   * width does not exist no longer refuses - `lanesOverride=4` asks for a 32-bit byte species
   * the Vector API does not have, and the emitter reads the next width up in its parts instead
   * (VARKA-329) - so those shapes emit and are checked against the reference like any other.
   *
   * <p>The cancel is not a silent skip: it names the refusal, and `sql/varka/matrix/skips.tsv`
   * carries a `declines` line for each configuration that reaches it, so the matrix's own report
   * records the same verdict rather than an unfused test. Only the missing-lane-count refusal is
   * expected; a refusal for any other reason fails the test, since a skip would hide a newly
   * refused shape behind a configuration that was not supposed to reach it.
   */
  private static void refuseForNoSpecies(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    Optional<String> reason = VarkaKernelPlan.refusalOf(roots, options);
    if (reason.isPresent()) {
      assertEquals("a narrow column needs a baked int lane count to be widened", reason.get(),
          "unexpected refusal: " + reason.get());
      assumeTrue(false, "this configuration bakes no narrow species for the shape ("
          + reason.get() + "), so the compiler declines it and the row engine answers; the "
          + "matrix records the decline in sql/varka/matrix/skips.tsv (VARKA-329)");
    }
  }

  private static void put(LaneType lane, MemorySegment seg, int row, int v) {
    switch (lane) {
      case BYTE -> seg.set(ValueLayout.JAVA_BYTE, row, (byte) v);
      case SHORT -> seg.set(ValueLayout.JAVA_SHORT, row * 2L, (short) v);
      case INT, FLOAT -> seg.set(ValueLayout.JAVA_INT, row * 4L, v);
      case LONG, DOUBLE -> seg.set(ValueLayout.JAVA_LONG, row * 8L, v);
    }
  }

  private static int[] tabulate(int length, IntUnaryOperator f) {
    var values = new int[length];
    for (int i = 0; i < length; i++) {
      values[i] = f.applyAsInt(i);
    }
    return values;
  }

  /**
   * Runs the condition {@code roots} over columns of the given lanes and compares every row's
   * selection bit with {@link VarkaReference}. A condition's output is its validity bitmap alone,
   * so the data address is 0, as the kernel contract says; the columns are stored at their lanes'
   * widths, which is how a narrow column sits beside an int one. A batch with no null runs twice,
   * through the dense body and again with its null counts forced to one over the full bitmap, so
   * the masked body is held to the reference on the same rows; a batch with nulls takes the masked
   * body only, as production does.
   */
  private static void checkConds(String ctx, List<? extends VarkaVectorIR> roots,
      List<LaneType> lanes, int length, IntBinaryOperator value, RowNulls isNull,
      VarkaEmitOptions options) {
    var kernelRoots = new ArrayList<VarkaVectorIR>(roots);
    refuseForNoSpecies(kernelRoots, options);
    Emitted emitted = VarkaEmitterFixtures.emit(kernelRoots, lanes.size(), 0, options);
    Loaded loaded = VarkaEmitterFixtures.load(emitted);
    VarkaFusedKernel kernel = loaded.kernel();
    long bytes = (length + 7) / 8;
    try (Arena arena = Arena.ofConfined()) {
      var data = new ArrayList<MemorySegment>();
      for (LaneType lane : lanes) {
        data.add(VarkaEmitterFixtures.alloc(arena, length * width(lane)));
      }
      var valid = new ArrayList<MemorySegment>();
      for (int c = 0; c < lanes.size(); c++) {
        MemorySegment validity = VarkaEmitterFixtures.alloc(arena, bytes);
        validity.fill((byte) 0);
        valid.add(validity);
      }
      var nullCounts = new int[lanes.size()];
      for (int c = 0; c < lanes.size(); c++) {
        for (int row = 0; row < length; row++) {
          if (isNull.test(c, row)) {
            nullCounts[c]++;
          } else {
            put(lanes.get(c), data.get(c), row, value.applyAsInt(c, row));
            int bit = 1 << (row % 8);
            long off = row / 8;
            valid.get(c).set(ValueLayout.JAVA_BYTE, off,
                (byte) (valid.get(c).get(ValueLayout.JAVA_BYTE, off) | bit));
          }
        }
      }
      long outBytes = VarkaEmitterFixtures.outputValidityBytes(options, length);
      long[] dataAddrs = data.stream().mapToLong(MemorySegment::address).toArray();
      long[] validAddrs = valid.stream().mapToLong(MemorySegment::address).toArray();
      var bodies = new ArrayList<int[]>();
      bodies.add(nullCounts);
      // A forced mask at one row is indistinguishable from an all-null column, which the kernel's
      // all-null shortcut may answer without writing, so one-row batches keep the dense body only.
      if (Arrays.stream(nullCounts).allMatch(n -> n == 0) && length > 1) {
        var forced = new int[lanes.size()];
        Arrays.fill(forced, 1);
        bodies.add(forced);
      }
      for (int[] counts : bodies) {
        for (ValidityFill fill : ValidityFill.BOTH) {
          var outs = new ArrayList<Output>();
          for (int o = 0; o < kernelRoots.size(); o++) {
            outs.add(VarkaEmitterFixtures.makeOutput(arena, length, outBytes, fill));
          }
          long[] outData = outs.stream().mapToLong(out -> out.data().address()).toArray();
          long[] outValid = outs.stream().mapToLong(out -> out.validity().address()).toArray();
          String body = counts == nullCounts ? "" : ", masked body forced";
          int status = kernel.run(dataAddrs, validAddrs, counts, outData, outValid, new int[0],
              length, VarkaEmitterTestSupport.scratch(kernel, length));
          assertEquals(0, status, ctx + ": declined (length " + length + ", " + fill + body + ")");
          for (int row = 0; row < length; row++) {
            var ints = new int[lanes.size()];
            var nulls = new boolean[lanes.size()];
            for (int c = 0; c < lanes.size(); c++) {
              nulls[c] = isNull.test(c, row);
              ints[c] = nulls[c] ? 0 : narrow(lanes.get(c), value.applyAsInt(c, row));
            }
            for (int o = 0; o < kernelRoots.size(); o++) {
              boolean want = VarkaReference.cond((Cond) kernelRoots.get(o), ints, nulls, new int[0])
                  .isTrue();
              boolean got =
                  (outs.get(o).validity().get(ValueLayout.JAVA_BYTE, (row / 8)) & (1 << (row % 8)))
                      != 0;
              assertEquals(want, got, ctx + ": cond " + o + " "
                  + VarkaVectorIR.canonical(kernelRoots.get(o)) + " row " + row + " of " + length
                  + " " + fill + body);
            }
          }
        }
      }
    }
  }

  /** Runs the value {@code roots} over one column of the drawn values, as the arms compare it. */
  private static void checkValues(String ctx, List<VarkaVectorIR> roots, int numInputs,
      int[] lits, int length, int[][] data, IntPredicate pattern, boolean forceMasked,
      VarkaEmitOptions options) {
    refuseForNoSpecies(roots, options);
    Emitted emitted = VarkaEmitterFixtures.emit(roots, numInputs, lits.length, options);
    var nulls = new boolean[numInputs][length];
    for (int c = 0; c < numInputs; c++) {
      for (int row = 0; row < length; row++) {
        nulls[c][row] = pattern.test(row);
      }
    }
    assertTrue(VarkaKernelChecks.runAndCompareNarrow(ctx + " (length " + length + ")",
        emitted.className(), emitted.bytes(), roots, numInputs, lits,
        new Batch(length, nulls, data, forceMasked), false, null));
  }

  @Test
  @DisplayName("the option picks the narrow lane only where every value is narrow")
  public void theOptionPicksTheNarrowLane() {
    assertEquals(LaneType.INT, VarkaKernelPlan.emissionLane(BYTE_COL, WIDENED));
    assertEquals(LaneType.BYTE, VarkaKernelPlan.emissionLane(BYTE_COL, NATIVE));
    assertEquals(LaneType.INT, VarkaKernelPlan.emissionLane(SHORT_COL, WIDENED));
    assertEquals(LaneType.SHORT, VarkaKernelPlan.emissionLane(SHORT_COL, NATIVE));
    // A comparison of two narrow columns is itself narrow - its lane is its left operand's -
    // so the native arm takes it to the narrow lane too, and only the widened arm's int lane
    // stands between the leaves and the mask.
    Cond cmp = new Compare(CompareOp.LT, BYTE_COL, BYTE_COL_2);
    assertEquals(LaneType.INT, VarkaKernelPlan.emissionLane(cmp, WIDENED));
    assertEquals(LaneType.BYTE, VarkaKernelPlan.emissionLane(cmp, NATIVE));
  }

  @Test
  @DisplayName("roots that prefer different lanes share the int lane unless one is long")
  public void rootsThatPreferDifferentLanesShareTheIntLane() {
    VarkaVectorIR byteSum = new IntArith(IntOp.ADD, Overflow.WRAP, BYTE_COL, BYTE_COL_2);
    VarkaVectorIR shortAbs = new IntNeg(Overflow.WRAP, new ColumnRef(2, LaneType.SHORT), true);
    VarkaVectorIR intCol = new ColumnRef(3, LaneType.INT);
    VarkaVectorIR intSum = new IntArith(IntOp.ADD, Overflow.WRAP, intCol, intCol);
    assertEquals(Optional.of(LaneType.BYTE), VarkaKernelPlan.driveOf(List.of(byteSum), NATIVE));
    // Each root alone would run native; together they have no one narrow species, and the int
    // lane serves both through the widened lowering, as it serves an int root beside them.
    assertDrivesAtTheIntLane(List.of(byteSum, shortAbs));
    assertDrivesAtTheIntLane(List.of(byteSum, intSum));
    assertEquals(Optional.empty(), VarkaKernelPlan.driveOf(
        List.of(byteSum, new ColumnRef(4, LaneType.LONG)), NATIVE));
    int length = 300;
    int[][] data = {tabulate(length, row -> ((row * 7) % 256) - 128),
      tabulate(length, row -> 127 - ((row * 11) % 256)),
      tabulate(length, row -> row % 3 == 0 ? Short.MIN_VALUE : ((row * 4099) % 65536) - 32768),
      tabulate(length, row -> row * 1000003)};
    for (Arm arm : ARMS) {
      checkValues("a byte sum, a short abs and an int sum in one kernel " + arm.name(),
          List.of(byteSum, shortAbs, intSum), 4, new int[0], length, data, row -> row % 9 == 2,
          false, arm.options());
    }
  }

  /**
   * The plan of {@code roots} on the native arm drives at the int lane: it does, or it is refused
   * because this configuration bakes no int lane count to widen at, a refusal only an int-lane
   * kernel meets.
   */
  private static void assertDrivesAtTheIntLane(List<VarkaVectorIR> roots) {
    VarkaKernelPlan plan = VarkaKernelPlan.of(roots, NATIVE);
    if (plan.refusal().isPresent()) {
      assertEquals("a narrow column needs a baked int lane count to be widened",
          plan.refusal().get().reason());
    } else {
      assertEquals(LaneType.INT, plan.drive());
    }
  }

  @Test
  @DisplayName("a narrow value lifted to the long lane is refused, not emitted")
  public void narrowValueLiftedToTheLongLaneIsRefused() {
    // The third refusal: a bigint guard makes the kernel long-lane, and no long-lane form of a
    // narrow value exists in either arm. The compiler asks the same question, so a query that
    // would build this - `CASE WHEN l < 0 THEN y1 ELSE y2 END` - answers from the row engine.
    Cond longCond = new Compare(CompareOp.LT, new ColumnRef(1, LaneType.LONG),
        new ColumnRef(2, LaneType.LONG));
    VarkaVectorIR longNarrow = new IfElse(longCond, new WidenLane(BYTE_COL, LaneType.LONG),
        new WidenLane(BYTE_COL, LaneType.LONG));
    for (Arm arm : ARMS) {
      assertEquals(Optional.of("a narrow value under a bigint kernel has no lowering"),
          VarkaKernelPlan.refusalOf(List.of(longNarrow), arm.options()), arm.name());
    }
    // The same choice on the int lane is the widened arm's ordinary shape: where a configuration
    // bakes no lane count it is refused, but for that width and never for the long lane, which
    // is the distinction this test pins.
    VarkaVectorIR intNarrow = new IfElse(
        new Compare(CompareOp.LT, new ColumnRef(1, LaneType.INT),
            new ColumnRef(2, LaneType.INT)),
        new WidenLane(BYTE_COL, LaneType.INT), new WidenLane(BYTE_COL, LaneType.INT));
    Optional<String> intRefusal = VarkaKernelPlan.refusalOf(List.of(intNarrow), WIDENED);
    assertTrue(intRefusal.isEmpty()
            || intRefusal.get().equals(
                "a narrow column needs a baked int lane count to be widened"),
        intRefusal.map(Object::toString).orElse(""));
  }

  @Test
  @DisplayName("a validity-only narrow column needs no species at any lane, a narrow value does "
      + "at the bigint lane")
  public void validityOnlyNarrowColumnNeedsNoSpecies() {
    // An IsNotNull over a value reads that input's validity word and never its data, so it needs
    // no narrow species at any lane count or width, the bigint lane's included: the word is a bit
    // per row at every lane (VARKA-480), so a null test lifted beside a bigint conjunct fuses. A
    // narrow value has no lowering at the long lane, so the value form of the same widening
    // declines and the row engine answers it.
    Cond validityOnly = new IsNotNull(BYTE_COL);
    Cond lifted = new IsNotNull(new WidenLane(BYTE_COL, LaneType.LONG));
    VarkaVectorIR value = new WidenLane(BYTE_COL, LaneType.LONG);
    assertEquals(LaneType.INT, VarkaKernelPlan.emissionLane(validityOnly, WIDENED));
    assertEquals(LaneType.LONG, VarkaKernelPlan.emissionLane(lifted, WIDENED));
    for (Arm arm : ARMS) {
      assertTrue(VarkaKernelPlan.refusalOf(List.of(validityOnly), arm.options()).isEmpty(),
          arm.name());
      // The lifted test reads the word alone, one bit per row at every lane, so a bigint kernel
      // takes it (VARKA-480); a narrow value under one still has no lowering.
      assertTrue(VarkaKernelPlan.refusalOf(List.of(lifted), arm.options()).isEmpty(),
          arm.name());
      assertEquals(Optional.of("a narrow value under a bigint kernel has no lowering"),
          VarkaKernelPlan.refusalOf(List.of(value), arm.options()), arm.name());
    }
  }

  @Test
  @DisplayName("a byte column round-trips every value at every length and tail, in both arms")
  public void byteColumnRoundTripsEveryValue() {
    int[] lits = {-56};
    List<VarkaVectorIR> roots = List.of(BYTE_COL, new LiteralSlot(0, LaneType.BYTE));
    for (Arm arm : ARMS) {
      for (int length : ROUND_TRIP_LENGTHS) {
        for (NullPattern np : patterns(length)) {
          int[][] data = {tabulate(length, i -> ((i * 97 + 13) % 256) - 128)};
          checkValues("a byte column " + arm.name() + " " + np.name(), roots, 1, lits, length,
              data, np.isNull(), np.forceMasked(), arm.options());
        }
      }
    }
    // The metre batch: every byte value once, and again at every lane position of a 64-lane
    // group, so a species that reads the wrong part of the word cannot hide behind a tail.
    int[][] data = {tabulate(1024, i -> ((i + 1024 * (i / 256)) % 256) - 128)};
    for (Arm arm : ARMS) {
      checkValues("every byte value " + arm.name(), roots, 1, lits, 1024, data, row -> false,
          false, arm.options());
      checkValues("every byte value masked " + arm.name(), roots, 1, lits, 1024, data,
          row -> false, true, arm.options());
    }
  }

  @Test
  @DisplayName("a short column round-trips its extremes and a spread of its domain, in both arms")
  public void shortColumnRoundTripsItsExtremes() {
    int[] lits = {12345};
    List<VarkaVectorIR> roots = List.of(SHORT_COL, new LiteralSlot(0, LaneType.SHORT));
    for (Arm arm : ARMS) {
      for (int length : ROUND_TRIP_LENGTHS) {
        for (NullPattern np : patterns(length)) {
          int[][] data = {tabulate(length, i -> ((i * 4051 + 17) % 65536) - 32768)};
          if (length > 2) {
            data[0][0] = Short.MIN_VALUE;
            data[0][1] = Short.MAX_VALUE;
            data[0][2] = -1;
          }
          checkValues("a short column " + arm.name() + " " + np.name(), roots, 1, lits, length,
              data, np.isNull(), np.forceMasked(), arm.options());
        }
      }
    }
  }

  @Test
  @DisplayName("every byte pair gives the reference's answer through every comparison and choice")
  public void everyBytePairGivesTheReferencesAnswer() {
    List<Integer> inBounds = List.of(-100, -50, -1, 1, 100, 120);
    List<Cond> conds = List.of(
        new Compare(CompareOp.LT, BYTE_COL, BYTE_COL_2),
        new Compare(CompareOp.LE, BYTE_COL, BYTE_COL_2),
        new Compare(CompareOp.EQ, BYTE_COL, BYTE_COL_2),
        new Compare(CompareOp.GT, BYTE_COL, BYTE_COL_2),
        new Compare(CompareOp.GE, BYTE_COL, BYTE_COL_2),
        new InRanges(BYTE_COL, inBounds));
    List<VarkaVectorIR> values = List.of(
        new Greatest(BYTE_COL, BYTE_COL_2),
        new Least(BYTE_COL, BYTE_COL_2),
        new IfElse(new Compare(CompareOp.GT, BYTE_COL, BYTE_COL_2), BYTE_COL, BYTE_COL_2),
        new IfElse(new Compare(CompareOp.LT, BYTE_COL, BYTE_COL_2), BYTE_COL_2, BYTE_COL));
    // Row `a * 256 + b` holds the pair `(a, b)` over the whole byte square, once each.
    int length = 65536;
    IntBinaryOperator data = (c, row) -> c == 0 ? (row / 256) - 128 : (row % 256) - 128;
    for (Arm arm : ARMS) {
      checkConds("the byte square's comparisons " + arm.name(), conds,
          List.of(LaneType.BYTE, LaneType.BYTE), length, data, (c, row) -> false,
          arm.options());
      checkValues("the byte square's choices " + arm.name(), values, 2, new int[0], length,
          new int[][] {tabulate(length, row -> data.applyAsInt(0, row)),
            tabulate(length, row -> data.applyAsInt(1, row))},
          row -> false, false, arm.options());
    }
  }

  @Test
  @DisplayName("a narrow column read beside an int column is widened on load")
  public void narrowColumnReadBesideAnIntColumnIsWidenedOnLoad() {
    // The shape the compiler builds for an int-lane kernel that also reads a byte column: the
    // byte leaf widens into the int lane inside an int-lane tree, so its load is one narrow
    // species - the int width's, or one width up read in parts - and the widening itself is an
    // identity.
    Cond mixedCmp = new Compare(CompareOp.LT, new WidenLane(BYTE_COL, LaneType.INT),
        new ColumnRef(1, LaneType.INT));
    VarkaVectorIR mixedValue = new IfElse(
        new Compare(CompareOp.GT, new WidenLane(BYTE_COL, LaneType.INT),
            new ColumnRef(1, LaneType.INT)),
        new WidenLane(BYTE_COL, LaneType.INT), new ColumnRef(1, LaneType.INT));
    int length = 300;
    IntUnaryOperator byteValue = row -> ((row * 7) % 256) - 128;
    IntUnaryOperator intValue = row -> ((row * 13) % 256) - 128;
    RowNulls isNull = (c, row) -> c == 0 && row % 11 == 5;
    for (Arm arm : ARMS) {
      checkConds("a widened byte beside an int column " + arm.name(), List.of(mixedCmp),
          List.of(LaneType.BYTE, LaneType.INT), length,
          (c, row) -> c == 0 ? byteValue.applyAsInt(row) : intValue.applyAsInt(row), isNull,
          arm.options());
      checkValues("a widened byte in a choice " + arm.name(), List.of(mixedValue), 2, new int[0],
          length, new int[][] {tabulate(length, byteValue), tabulate(length, intValue)},
          row -> isNull.test(0, row), false, arm.options());
    }
  }

  @Test
  @DisplayName("a narrow-typed value computed on the int lane is stored narrow")
  public void narrowTypedValueComputedOnTheIntLaneIsStoredNarrow() {
    // The shape `storedNarrow` builds for a narrow-typed choice whose guard is int-typed: the
    // branches widen into the int lane, the root narrows once at its store, and one byte a row
    // comes out. Storing four bytes there is the answer a batch of these rows got wrong before
    // the store knew the root's target (VARKA-329); the reference narrows with the store, so a
    // wrong width shows row by row.
    Cond guard = new Compare(CompareOp.LT, new ColumnRef(2, LaneType.INT),
        new ColumnRef(3, LaneType.INT));
    VarkaVectorIR stored = new NarrowLane(new IfElse(guard,
        new WidenLane(BYTE_COL, LaneType.INT), new WidenLane(BYTE_COL_2, LaneType.INT)),
        LaneType.BYTE);
    int length = 300;
    IntUnaryOperator first = row -> ((row * 7) % 256) - 128;
    IntUnaryOperator second = row -> 127 - ((row * 11) % 256);
    IntUnaryOperator left = row -> (row % 5) - 2;
    IntUnaryOperator right = row -> ((row * 3) % 7) - 3;
    int[][] data = {tabulate(length, first), tabulate(length, second), tabulate(length, left),
      tabulate(length, right)};
    for (Arm arm : ARMS) {
      // Both arms: an int node in the tree drives this kernel at the int lane either way, so the
      // shape is the same one and the option only decides how the leaves are read.
      checkValues("a choice stored narrow " + arm.name(), List.of(stored), 4, new int[0], length,
          data, row -> row % 13 == 4, false, arm.options());
    }
    // The store truncates as it narrows: a value past the target's range keeps its low bits,
    // which is the I2B/I2S the store emits, and the reference's `(byte)`/`(short)` has to agree
    // row by row. Spark's byte typing never hands the store such a value, so the roots are hand
    // built; what they pin is the store's width and truncation, not a Spark shape.
    VarkaVectorIR toByte = new NarrowLane(new ColumnRef(0, LaneType.INT), LaneType.BYTE);
    VarkaVectorIR toShort = new NarrowLane(new ColumnRef(0, LaneType.INT), LaneType.SHORT);
    IntUnaryOperator outOfByte = row -> ((row * 37) % 1024) - 512;
    IntUnaryOperator outOfShort = row -> ((row * 7919) % 200000) - 100000;
    for (Arm arm : ARMS) {
      checkValues("an int value stored byte " + arm.name(), List.of(toByte), 1, new int[0],
          length, new int[][] {tabulate(length, outOfByte)}, row -> false, false,
          arm.options());
      checkValues("an int value stored short " + arm.name(), List.of(toShort), 1, new int[0],
          length, new int[][] {tabulate(length, outOfShort)}, row -> false, false,
          arm.options());
    }
  }

  @Test
  @DisplayName("an int value narrowed away from a store truncates as the store would")
  public void anIntValueNarrowedAwayFromAStoreTruncates() {
    // The shape a drawn narrow root wraps itself in (`VarkaIrGrammar.converted`, VARKA-329
    // finding 9): an int value cut to a byte or short and widened back, with the root's store
    // reading an int lane and so doing no narrowing of its own. The walk's conversion is the
    // only truncation in that kernel, and the reference's `(byte)`/`(short)` is the oracle.
    int length = 300;
    IntUnaryOperator outOfByte = row -> ((row * 37) % 1024) - 512;
    IntUnaryOperator outOfShort = row -> ((row * 7919) % 200000) - 100000;
    VarkaVectorIR toByte = new WidenLane(
        new NarrowLane(new ColumnRef(0, LaneType.INT), LaneType.BYTE), LaneType.INT);
    VarkaVectorIR toShort = new WidenLane(
        new NarrowLane(new ColumnRef(0, LaneType.INT), LaneType.SHORT), LaneType.INT);
    for (Arm arm : ARMS) {
      checkValues("an int value narrowed to byte away from a store " + arm.name(),
          List.of(toByte), 1, new int[0], length, new int[][] {tabulate(length, outOfByte)},
          row -> false, false, arm.options());
      checkValues("an int value narrowed to short away from a store " + arm.name(),
          List.of(toShort), 1, new int[0], length, new int[][] {tabulate(length, outOfShort)},
          row -> false, false, arm.options());
    }
  }

  /**
   * {@code try_multiply} of two narrow columns in the form the checked narrow multiply takes:
   * the operands widened to the int lane, where their product is exact, a wrapping multiply, a
   * {@code NULL} range guard of {@code lane}'s range, and the narrowing at the store.
   */
  private static VarkaVectorIR nullingMultiply(LaneType lane, VarkaVectorIR left,
      VarkaVectorIR right) {
    long[] range = lane == LaneType.BYTE
        ? new long[] {Byte.MIN_VALUE, Byte.MAX_VALUE}
        : new long[] {Short.MIN_VALUE, Short.MAX_VALUE};
    return new NarrowLane(new GuardedRange(new IntArith(IntOp.MUL, Overflow.WRAP,
        new WidenLane(left, LaneType.INT), new WidenLane(right, LaneType.INT)), range[0],
        range[1], Overflow.NULL), lane);
  }

  /**
   * Holds the reference to Spark's {@code TRY} rule on the batch before the kernel is held to the
   * reference: a row is null where an operand is, null where the exact product leaves
   * {@code lane}'s range, and the product elsewhere.
   */
  private static void assertTryProduct(String ctx, VarkaVectorIR root, LaneType lane,
      int[][] data, IntPredicate isNull) {
    long lo = lane == LaneType.BYTE ? Byte.MIN_VALUE : Short.MIN_VALUE;
    long hi = lane == LaneType.BYTE ? Byte.MAX_VALUE : Short.MAX_VALUE;
    for (int row = 0; row < data[0].length; row++) {
      boolean nul = isNull.test(row);
      long exact = (long) data[0][row] * data[1][row];
      var want = nul || exact < lo || exact > hi ? OptionalInt.empty()
          : OptionalInt.of((int) exact);
      var got = VarkaReference.value(root, new int[] {data[0][row], data[1][row]},
          new boolean[] {nul, nul}, new int[0]);
      assertEquals(want, got, ctx + ": the reference on " + data[0][row] + " * "
          + data[1][row] + (nul ? " (null)" : ""));
    }
  }

  @Test
  @DisplayName("a nulling range guard keeps an in-range product, nulls the rest and keeps a null, "
      + "over every byte pair and the short edges, in both arms")
  public void nullingRangeGuardOverNarrowProducts() {
    // Every ordered byte pair in one 2^16-row batch, every seventh row null: the guard fires on
    // the products past [-128, 127], such as 16 * 8 and -128 * -1, and nowhere else. The shorts
    // are the pairs of the values around the square root of each end and the ends themselves,
    // 181 * 181 inside and 182 * 181 outside, repeated past one lane group's tail.
    int pairs = 1 << 16;
    int[][] bytes = {tabulate(pairs, row -> (row >> 8) - 128),
      tabulate(pairs, row -> (row & 0xFF) - 128)};
    int[] edges = {Short.MIN_VALUE, Short.MIN_VALUE + 1, -182, -181, -2, -1, 0, 1, 2, 181, 182,
      255, 256, Short.MAX_VALUE - 1, Short.MAX_VALUE};
    int shortRows = edges.length * edges.length * 3;
    int[][] shorts = {tabulate(shortRows, row -> edges[(row / edges.length) % edges.length]),
      tabulate(shortRows, row -> edges[row % edges.length])};
    IntPredicate seventh = row -> row % 7 == 3;
    VarkaVectorIR byteRoot = nullingMultiply(LaneType.BYTE, BYTE_COL, BYTE_COL_2);
    VarkaVectorIR shortRoot = nullingMultiply(LaneType.SHORT, SHORT_COL, SHORT_COL_2);
    assertTryProduct("bytes", byteRoot, LaneType.BYTE, bytes, seventh);
    assertTryProduct("shorts", shortRoot, LaneType.SHORT, shorts, seventh);
    for (Arm arm : ARMS) {
      for (IntPredicate pattern : List.<IntPredicate>of(row -> false, seventh)) {
        checkValues("try_multiply of every byte pair " + arm.name(), List.of(byteRoot), 2,
            new int[0], pairs, bytes, pattern, false, arm.options());
        checkValues("try_multiply of the short edges " + arm.name(), List.of(shortRoot), 2,
            new int[0], shortRows, shorts, pattern, false, arm.options());
      }
    }
  }

  @Test
  @DisplayName("a forced two-int-lane emission serves both narrow loads in parts")
  public void forcedTwoIntLaneEmissionServesBothNarrowLoadsInParts() {
    // The width a host whose widest vector is 64 bits emits at with no override (Lane.hasSpecies
    // refuses a species below 64 bits): a byte load is an eight-lane 64-bit species converted in
    // four parts, a short load a four-lane one in two, and a narrow store's species is wider
    // than its group, so it is always masked. No other suite draws this width, and the reference
    // is the oracle on every length and null pattern.
    Cond byteCmp = new Compare(CompareOp.LT, BYTE_COL, BYTE_COL_2);
    Cond shortCmp = new Compare(CompareOp.LT, SHORT_COL, SHORT_COL_2);
    IntUnaryOperator byteValue = row -> ((row * 7) % 256) - 128;
    IntUnaryOperator shortValue = row -> ((row * 7919) % 65536) - 32768;
    for (int length : List.of(1, 7, 8, 9, 17, 31, 33, 65)) {
      for (NullPattern np : patterns(length)) {
        // checkConds forces the masked body itself on a null-free batch, so the pattern that
        // only forces the mask would repeat the no-null run.
        if (np.forceMasked()) {
          continue;
        }
        RowNulls nulls = (c, row) -> np.isNull().test(row);
        checkConds("a byte compare at two int lanes (" + np.name() + ", length " + length + ")",
            List.of(byteCmp), List.of(LaneType.BYTE, LaneType.BYTE), length,
            (c, row) -> c == 0 ? byteValue.applyAsInt(row) : byteValue.applyAsInt(row + 13),
            nulls, TWO_LANES);
        checkConds("a short compare at two int lanes (" + np.name() + ", length " + length + ")",
            List.of(shortCmp), List.of(LaneType.SHORT, LaneType.SHORT), length,
            (c, row) -> c == 0 ? shortValue.applyAsInt(row) : shortValue.applyAsInt(row + 13),
            nulls, TWO_LANES);
      }
    }
    VarkaVectorIR stored = new IfElse(new Compare(CompareOp.GT, BYTE_COL, BYTE_COL_2),
        BYTE_COL, BYTE_COL_2);
    int length = 301;
    for (NullPattern np : patterns(length)) {
      checkValues("a byte store at two int lanes (" + np.name() + ")", List.of(stored), 2,
          new int[0], length, new int[][] {tabulate(length, byteValue),
            tabulate(length, i -> byteValue.applyAsInt(i + 13))},
          np.isNull(), np.forceMasked(), TWO_LANES);
    }
  }

  @Test
  @DisplayName("a kernel reading both narrow lanes sizes its iteration by the wider load")
  public void kernelReadingBothNarrowLanesSizesItsIterationByTheWiderLoad() {
    // The iteration's group count is a max over the narrow lanes the kernel reads as values: at
    // four int lanes a byte load spans two groups where a short load spans one, so a kernel with
    // both bodies must run two groups and give the short body its rows through a part. Picking
    // the first or the narrowest lane instead would size the byte load's species one width down
    // and read past the batch at the last iteration. Both forced widths are used because the
    // default one bakes sixteen int lanes, where every load is whole and a max cannot be told
    // from a min.
    Cond byteCmp = new Compare(CompareOp.LT, BYTE_COL, BYTE_COL_2);
    Cond shortCmp = new Compare(CompareOp.LT, new ColumnRef(2, LaneType.SHORT),
        new ColumnRef(3, LaneType.SHORT));
    IntUnaryOperator byteValue = row -> ((row * 7) % 256) - 128;
    IntUnaryOperator shortValue = row -> ((row * 7919) % 65536) - 32768;
    for (var forced : List.of(new Arm("four int lanes", FOUR_LANES),
        new Arm("two int lanes", TWO_LANES))) {
      // With nulls (the masked body) and without (the dense body, then the masked one forced).
      for (RowNulls nulls : List.<RowNulls>of((c, row) -> row % 11 == 5, (c, row) -> false)) {
        checkConds("a byte body and a short body in one kernel at " + forced.name(),
            List.of(byteCmp, shortCmp),
            List.of(LaneType.BYTE, LaneType.BYTE, LaneType.SHORT, LaneType.SHORT), 300,
            (c, row) -> c < 2 ? byteValue.applyAsInt(row + c * 13)
                : shortValue.applyAsInt(row + c * 13),
            nulls, forced.options());
      }
    }
  }

  @Test
  @DisplayName("the reference narrows a stored byte and short as the lane does")
  public void theReferenceNarrowsAStoredByteAndShortAsTheLaneDoes() {
    // The oracle itself: a value written at a narrow width reads back sign-extended, which is
    // what the suites above rest on.
    assertEquals(-56, narrow(LaneType.BYTE, 200));
    assertEquals(-25536, narrow(LaneType.SHORT, 40000));
    assertEquals(1L, width(LaneType.BYTE));
    assertEquals(2L, width(LaneType.SHORT));
  }
}
