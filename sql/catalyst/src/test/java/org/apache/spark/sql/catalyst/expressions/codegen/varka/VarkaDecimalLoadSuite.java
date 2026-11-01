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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.function.LongUnaryOperator;
import java.util.stream.IntStream;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.util.ArrowUtils;
import org.apache.spark.sql.vectorized.ArrowColumnVector;

/**
 * The slot table of the decimal load (VARKA-347 3 item 1): a {@link DecimalColumn} kernel run
 * over Arrow decimal vectors whose sixteen-byte slots are written directly, so that a slot can
 * hold what Spark's own writer never writes - a high word that is not its low word's sign, a value
 * with more digits than the precision, garbage under a null.
 *
 * <p>The oracle is Spark's column reader: the same vector wrapped in {@link ArrowColumnVector},
 * whose decimal accessor raises on a valid slot that holds no value of the type and never reads a
 * null one. The kernel must decline the batch with {@code STATUS_DECIMAL_SLOT} exactly where some
 * valid row raises there, and otherwise answer each valid row's unscaled value.
 *
 * <p>Every case runs at two, four and eight long lanes, in the dense body (no null) and the masked
 * one (nulls in every group), with the planted slot in the first lane group, a middle one and the
 * epilogue, at precision 1, 9, 10 and 18. In a JVM of its own: the widths are three species of the
 * long lane, which the shared JVM refuses (VARKA-246).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDecimalLoadSuite {

  private static final int[] WIDTHS = {2, 4, 8};
  private static final int[] PRECISIONS = {1, 9, 10, 18};
  /**
   * Batches shorter than one lane group at every width but the narrowest, so no loop runs and the
   * epilogue's second vector is all masked off; one whose last row is in the epilogue at every
   * width; and one with no epilogue.
   */
  private static final int[] LENGTHS = {1, 3, 37, 64};
  private static final int SCALE = 0;

  /** One slot as Arrow holds it: the low word, then the high word. */
  private record Slot(long lo, long hi) {
    static Slot of(long value) {
      return new Slot(value, value >> 63);
    }
  }

  /** What a batch holds: each row's slot and whether it is null. */
  private record Batch(Slot[] slots, boolean[] nulls) {
    int length() {
      return slots.length;
    }
  }

  private static long power(int precision) {
    long p = 1L;
    for (int i = 0; i < precision; i++) {
      p *= 10L;
    }
    return p;
  }

  /**
   * A batch of in-precision slots, with nulls on every third row when {@code masked}, each null
   * holding garbage: a random low word and a random or all-ones high word.
   */
  private static Batch batch(int length, int precision, boolean masked, long seed) {
    var random = new Random(seed);
    long bound = power(precision) - 1;
    var slots = new Slot[length];
    var nulls = new boolean[length];
    for (int i = 0; i < length; i++) {
      nulls[i] = masked && i % 3 == 1;
      if (nulls[i]) {
        slots[i] = new Slot(random.nextLong(), i % 2 == 0 ? -1L : random.nextLong());
      } else {
        slots[i] = Slot.of(Math.floorMod(random.nextLong(), 2 * bound + 1) - bound);
      }
    }
    return new Batch(slots, nulls);
  }

  /** The rows a planted slot is tried at: the first group, a middle one and the last row. */
  private static int[] positions(int length) {
    return IntStream.of(0, length / 2, length - 1).distinct().toArray();
  }

  /** The slots each of which must decline the batch where it is valid. */
  private static List<Slot> declining(int precision) {
    long p = power(precision);
    return List.of(Slot.of(p), Slot.of(-p),
        // A wrong high word: zero under a negative low word, minus one under a positive one, one.
        new Slot(-5L, 0L), new Slot(5L, -1L), new Slot(5L, 1L), new Slot(0L, 1L),
        new Slot(Long.MIN_VALUE, 0L), new Slot(Long.MAX_VALUE, -1L));
  }

  /** The slots at the bounds, which pass. */
  private static List<Slot> passing(int precision) {
    long bound = power(precision) - 1;
    return List.of(Slot.of(bound), Slot.of(-bound), Slot.of(0L), Slot.of(-1L));
  }

  /** Spark's reading of a batch: each valid row's unscaled value, or that some row raised. */
  private record SparkReading(long[] unscaled, boolean raised) { }

  /** The vector of a batch, its slots written word by word and its nulls marked. */
  private static DecimalVector vector(Batch b, int precision, BufferAllocator allocator) {
    var v = new DecimalVector("d", allocator, precision, SCALE);
    v.allocateNew(b.length());
    for (int i = 0; i < b.length(); i++) {
      v.getDataBuffer().setLong(i * 16L, b.slots()[i].lo());
      v.getDataBuffer().setLong(i * 16L + 8, b.slots()[i].hi());
      if (b.nulls()[i]) {
        v.setNull(i);
      } else {
        v.setIndexDefined(i);
      }
    }
    v.setValueCount(b.length());
    return v;
  }

  private static SparkReading spark(DecimalVector v, int precision) {
    var column = new ArrowColumnVector(v);
    long[] unscaled = new long[v.getValueCount()];
    boolean raised = false;
    for (int i = 0; i < unscaled.length; i++) {
      if (column.isNullAt(i)) {
        continue;
      }
      try {
        Decimal d = column.getDecimal(i, precision, SCALE);
        unscaled[i] = d.toUnscaledLong();
      } catch (ArithmeticException e) {
        raised = true;
      }
    }
    return new SparkReading(unscaled, raised);
  }

  /** The kernel's answer: its status, then each row's value and validity, per output. */
  private record KernelAnswer(int status, long[][] values, boolean[][] valid) { }

  private static KernelAnswer run(VarkaFusedKernel kernel, DecimalVector v, long[] literals) {
    return run(kernel, v, null, 1, literals);
  }

  /**
   * Runs {@code kernel} over the decimal column {@code v} at ordinal 0 and, when {@code ints} is
   * not null, an int column at ordinal 1 holding those values, all valid, with {@code outputs}
   * long outputs.
   */
  private static KernelAnswer run(VarkaFusedKernel kernel, DecimalVector v, int[] ints,
      int outputs, long[] literals) {
    int length = v.getValueCount();
    int nulls = v.getNullCount();
    int inputs = ints == null ? 1 : 2;
    long wordBytes = ((length + 63) / 64) * 8L;
    try (var arena = Arena.ofConfined()) {
      long[] srcData = new long[inputs];
      long[] srcValidity = new long[inputs];
      int[] nullCounts = new int[inputs];
      long[] inStride = new long[inputs];
      srcData[0] = v.getDataBuffer().memoryAddress();
      srcValidity[0] = nulls == length ? 0L : v.getValidityBuffer().memoryAddress();
      nullCounts[0] = nulls;
      inStride[0] = 16L;
      if (ints != null) {
        MemorySegment data = arena.allocate(Math.max(1, length) * 4L, 64);
        MemorySegment valid = arena.allocate(wordBytes, 64);
        for (int i = 0; i < length; i++) {
          data.set(ValueLayout.JAVA_INT, i * 4L, ints[i]);
        }
        valid.fill((byte) -1);
        srcData[1] = data.address();
        srcValidity[1] = valid.address();
        inStride[1] = 4L;
      }
      long[] outData = new long[outputs];
      long[] outValidity = new long[outputs];
      long[] outStride = new long[outputs];
      MemorySegment[] out = new MemorySegment[outputs];
      MemorySegment[] outValid = new MemorySegment[outputs];
      for (int o = 0; o < outputs; o++) {
        out[o] = arena.allocate(length * 8L, 64);
        outValid[o] = arena.allocate(wordBytes, 64);
        outData[o] = out[o].address();
        outValidity[o] = outValid[o].address();
        outStride[o] = 8L;
      }
      int status = VarkaKernelChecks.sanitized(kernel, length, inStride, outStride, srcData,
          srcValidity, outData, outValidity,
          scratch -> kernel.run(srcData, srcValidity, nullCounts, outData, outValidity,
              new int[0], literals, length, scratch));
      long[][] values = new long[outputs][length];
      boolean[][] validRows = new boolean[outputs][length];
      for (int o = 0; o < outputs; o++) {
        for (int i = 0; i < length; i++) {
          values[o][i] = out[o].get(ValueLayout.JAVA_LONG, i * 8L);
          validRows[o][i] =
              (outValid[o].get(ValueLayout.JAVA_LONG, (i >>> 6) * 8L) >>> (i & 63) & 1L) != 0;
        }
      }
      return new KernelAnswer(status, values, validRows);
    }
  }

  /** {@code roots} over one decimal column at {@code lanes} long lanes, loaded. */
  private static VarkaEmitterFixtures.Loaded kernel(List<VarkaVectorIR> roots, int literals,
      int lanes) {
    return kernel(roots, 1, literals, VarkaMatrixRuns.BASE.withLanesOverride(lanes));
  }

  /** {@code roots} over {@code inputs} columns under {@code options}, loaded. */
  private static VarkaEmitterFixtures.Loaded kernel(List<VarkaVectorIR> roots, int inputs,
      int literals, VarkaEmitOptions options) {
    var emitted = VarkaEmitterFixtures.emit(roots, inputs, literals, options);
    List<String> errors = VarkaEmitterTestSupport.verify(emitted.bytes());
    assertTrue(errors.isEmpty(), () -> "the class does not verify: " + errors);
    return VarkaEmitterFixtures.load(emitted);
  }

  /**
   * Runs the kernel of {@code root} (a function {@code f} of the unscaled value) over {@code b}
   * and holds it to Spark's reading; returns whether the batch declined.
   */
  private static boolean check(String context, VarkaFusedKernel kernel, Batch b, int precision,
      long[] literals, LongUnaryOperator f) {
    try (BufferAllocator allocator =
        ArrowUtils.rootAllocator().newChildAllocator("varka-decimal-load", 0, Long.MAX_VALUE);
        DecimalVector v = vector(b, precision, allocator)) {
      SparkReading expected = spark(v, precision);
      KernelAnswer got = run(kernel, v, literals);
      if (expected.raised()) {
        assertEquals(VarkaFusedKernel.STATUS_DECIMAL_SLOT, got.status(),
            () -> context + ": Spark's reader raises, so the kernel must decline");
        return true;
      }
      assertEquals(0, got.status(), () -> context + ": Spark's reader reads every row");
      for (int i = 0; i < b.length(); i++) {
        int row = i;
        assertEquals(!b.nulls()[i], got.valid()[0][i],
            () -> context + ": validity of row " + row);
        if (!b.nulls()[i]) {
          assertEquals(f.applyAsLong(expected.unscaled()[i]), got.values()[0][i],
              () -> context + ": row " + row);
        }
      }
      return false;
    }
  }

  /**
   * The slot table over {@code roots}: an in-precision batch, the bounds, garbage under nulls, and
   * every declining slot planted at each position, valid (declines) and null (does not).
   */
  private static void slotTable(String name, IntFunction<List<VarkaVectorIR>>
      roots, int literals, long[] literalValues, LongUnaryOperator f) {
    int declined = 0;
    for (int lanes : WIDTHS) {
      for (int precision : PRECISIONS) {
        var loaded = kernel(roots.apply(precision), literals, lanes);
        VarkaFusedKernel k = loaded.kernel();
        for (int length : LENGTHS) {
          for (boolean masked : new boolean[] {false, true}) {
            String at = name + " at " + lanes + " long lanes, precision " + precision
                + ", length " + length + (masked ? ", with nulls" : ", no null");
            long seed = 31L * lanes + 7L * precision + length + (masked ? 1 : 0);
            assertFalse(check(at, k, batch(length, precision, masked, seed), precision,
                literalValues, f), at + ": an in-precision batch must not decline");
            for (int row : positions(length)) {
              for (Slot slot : passing(precision)) {
                Batch b = batch(length, precision, masked, seed);
                b.slots()[row] = slot;
                b.nulls()[row] = false;
                assertFalse(check(at + ", " + slot + " at row " + row, k, b, precision,
                    literalValues, f), at + ": a slot at the bound passes");
              }
              for (Slot slot : declining(precision)) {
                Batch b = batch(length, precision, masked, seed);
                b.slots()[row] = slot;
                b.nulls()[row] = false;
                String context = at + ", " + slot + " at row " + row;
                assertTrue(check(context, k, b, precision, literalValues, f),
                    context + ": Spark's reader must raise on the planted slot");
                declined++;
                // The same slot under a null is never read, by Spark or by the kernel.
                b.nulls()[row] = true;
                assertFalse(check(context + " under a null", k, b, precision, literalValues, f),
                    context + ": a null slot must not decline");
              }
            }
          }
        }
      }
    }
    assertTrue(declined > 0, "the table planted no declining slot");
  }

  @Test
  @DisplayName("a decimal column's slots load as their low words, and a valid slot Spark's reader "
      + "raises on declines the batch")
  void slotTableOfTheLoad() {
    slotTable("d", precision -> List.of(new DecimalColumn(0, LaneType.LONG, precision)), 0,
        new long[0], x -> x);
  }

  @Test
  @DisplayName("the loaded value is an operand like a long column's, its check kept")
  void slotTableUnderArithmetic() {
    slotTable("d + 3", precision -> List.of(new IntArith(IntOp.ADD, Overflow.WRAP,
        new DecimalColumn(0, LaneType.LONG, precision), new LiteralSlot(0, LaneType.LONG))), 1,
        new long[] {3L}, x -> x + 3);
  }

  @Test
  @DisplayName("a leaf read twice is loaded and checked once, through its shared local")
  void slotTableReadTwice() {
    slotTable("d + d", precision -> {
      var d = new DecimalColumn(0, LaneType.LONG, precision);
      return List.of(new IntArith(IntOp.ADD, Overflow.WRAP, d, d));
    }, 0, new long[0], x -> x + x);
  }

  // ------------------------------------------------------------------------------------------
  // The first reader (VARKA-347 step 5): a comparison and a null test over the leaf
  // ------------------------------------------------------------------------------------------

  /** A condition's three answers for one row of the decimal column: true, false or unknown. */
  private enum Truth { TRUE, FALSE, UNKNOWN }

  /** What a condition answers for a row holding {@code unscaled}, or null. */
  private interface RowTruth {
    Truth of(long unscaled, boolean isNull);
  }

  /**
   * The slot table over a condition: the kernel of {@code IfElse(cond, 1, 0)}, which stores 1
   * where the condition is known true and 0 where it is false or unknown, always valid. The
   * condition's leaf must decline a batch with a valid slot Spark's reader raises on whatever
   * reads it, a null test included, and answer {@code truth} per row otherwise, in both bodies.
   */
  private static void conditionTable(String name, IntFunction<VarkaVectorIR.Cond> cond,
      long[] literals, RowTruth truth) {
    long[] lits = new long[literals.length + 2];
    System.arraycopy(literals, 0, lits, 0, literals.length);
    lits[literals.length] = 1L;
    lits[literals.length + 1] = 0L;
    int declined = 0;
    for (int lanes : WIDTHS) {
      for (int precision : PRECISIONS) {
        VarkaVectorIR root = new VarkaVectorIR.IfElse(cond.apply(precision),
            new LiteralSlot(literals.length, LaneType.LONG),
            new LiteralSlot(literals.length + 1, LaneType.LONG));
        VarkaFusedKernel k = kernel(List.of(root), lits.length, lanes).kernel();
        for (int length : LENGTHS) {
          for (boolean masked : new boolean[] {false, true}) {
            String at = name + " at " + lanes + " long lanes, precision " + precision
                + ", length " + length + (masked ? ", with nulls" : ", no null");
            long seed = 17L * lanes + 5L * precision + length + (masked ? 1 : 0);
            assertFalse(checkCondition(at, k, batch(length, precision, masked, seed),
                precision, lits, truth), at + ": an in-precision batch must not decline");
            for (int row : positions(length)) {
              for (Slot slot : declining(precision)) {
                Batch b = batch(length, precision, masked, seed);
                b.slots()[row] = slot;
                b.nulls()[row] = false;
                String context = at + ", " + slot + " at row " + row;
                assertTrue(checkCondition(context, k, b, precision, lits, truth),
                    context + ": Spark's reader raises on the planted slot");
                declined++;
                b.nulls()[row] = true;
                assertFalse(checkCondition(context + " under a null", k, b, precision, lits,
                    truth), context + ": a null slot must not decline");
              }
            }
          }
        }
      }
    }
    assertTrue(declined > 0, "the table planted no declining slot");
  }

  /** Runs a condition table's kernel over {@code b}; returns whether the batch declined. */
  private static boolean checkCondition(String context, VarkaFusedKernel kernel, Batch b,
      int precision, long[] literals, RowTruth truth) {
    try (BufferAllocator allocator =
        ArrowUtils.rootAllocator().newChildAllocator("varka-decimal-cond", 0, Long.MAX_VALUE);
        DecimalVector v = vector(b, precision, allocator)) {
      SparkReading expected = spark(v, precision);
      KernelAnswer got = run(kernel, v, literals);
      if (expected.raised()) {
        assertEquals(VarkaFusedKernel.STATUS_DECIMAL_SLOT, got.status(),
            () -> context + ": Spark's reader raises, so the kernel must decline");
        return true;
      }
      assertEquals(0, got.status(), () -> context + ": Spark's reader reads every row");
      for (int i = 0; i < b.length(); i++) {
        int row = i;
        long want = truth.of(expected.unscaled()[i], b.nulls()[i]) == Truth.TRUE ? 1L : 0L;
        assertEquals(want, got.values()[0][i], () -> context + ": row " + row);
      }
      return false;
    }
  }

  @Test
  @DisplayName("a null test over the leaf reads the validity word and still checks the slots, as "
      + "Spark's IS NULL reads a valid row's value")
  void conditionTableOfTheNullTest() {
    conditionTable("d IS NOT NULL",
        precision -> new VarkaVectorIR.IsNotNull(new DecimalColumn(0, LaneType.LONG, precision)),
        new long[0], (u, isNull) -> isNull ? Truth.FALSE : Truth.TRUE);
    conditionTable("d IS NULL", precision -> new VarkaVectorIR.Not(
        new VarkaVectorIR.IsNotNull(new DecimalColumn(0, LaneType.LONG, precision))),
        new long[0], (u, isNull) -> isNull ? Truth.TRUE : Truth.FALSE);
  }

  @Test
  @DisplayName("a comparison of the leaf with a literal is the unscaled long compare")
  void conditionTableOfAComparison() {
    conditionTable("d < 5", precision -> new VarkaVectorIR.Compare(VarkaVectorIR.CompareOp.LT,
        new DecimalColumn(0, LaneType.LONG, precision), new LiteralSlot(0, LaneType.LONG)),
        new long[] {5L}, (u, isNull) -> isNull ? Truth.UNKNOWN : u < 5 ? Truth.TRUE : Truth.FALSE);
    conditionTable("d <=> 0", precision -> new VarkaVectorIR.NullSafeEqual(
        new DecimalColumn(0, LaneType.LONG, precision), new LiteralSlot(0, LaneType.LONG)),
        new long[] {0L}, (u, isNull) -> !isNull && u == 0 ? Truth.TRUE : Truth.FALSE);
  }

  @Test
  @DisplayName("the slot check under an IfElse arm is not narrowed by the arm: a filter's code "
      + "reads the column before the predicate")
  void slotCheckIgnoresTheArm() {
    // CASE WHEN i > 0 THEN d < 5 ELSE false END, with i never positive: the arm reading the leaf
    // is never taken, and the batch with a bad valid slot declines all the same.
    for (int lanes : WIDTHS) {
      int precision = 9;
      var d = new DecimalColumn(0, LaneType.LONG, precision);
      VarkaVectorIR.Cond inArm = new VarkaVectorIR.Compare(VarkaVectorIR.CompareOp.LT, d,
          new LiteralSlot(0, LaneType.LONG));
      VarkaVectorIR.Cond taken = new VarkaVectorIR.Compare(VarkaVectorIR.CompareOp.GT,
          new WidenLane(new ColumnRef(1, LaneType.INT)), new LiteralSlot(1, LaneType.LONG));
      VarkaVectorIR root = new VarkaVectorIR.IfElse(taken,
          new VarkaVectorIR.IfElse(inArm, new LiteralSlot(2, LaneType.LONG),
              new LiteralSlot(1, LaneType.LONG)),
          new LiteralSlot(1, LaneType.LONG));
      VarkaFusedKernel k = kernel(List.of(root), 2, 3,
          VarkaMatrixRuns.BASE.withLanesOverride(lanes)).kernel();
      for (int length : LENGTHS) {
        Batch b = batch(length, precision, false, 41L + length);
        b.slots()[length - 1] = Slot.of(power(precision));
        KernelAnswer got = runBatch(k, b, precision, new int[length], 1, new long[] {5L, 0L, 1L});
        assertEquals(VarkaFusedKernel.STATUS_DECIMAL_SLOT, got.status(),
            () -> "an untaken arm's bad slot at " + lanes + " long lanes, length " + length);
      }
    }
  }

  /** {@code b} as a vector, read by the kernel; the vector is closed before the answer returns. */
  private static KernelAnswer runBatch(VarkaFusedKernel k, Batch b, int precision, int[] ints,
      int outputs, long[] literals) {
    try (BufferAllocator allocator =
        ArrowUtils.rootAllocator().newChildAllocator("varka-decimal-load", 0, Long.MAX_VALUE);
        DecimalVector v = vector(b, precision, allocator)) {
      return run(k, v, ints, outputs, literals);
    }
  }

  /** {@code length} in-precision slots, row 5 null and holding zero when {@code masked}. */
  private static Batch plain(int length, boolean masked, long value) {
    var slots = new Slot[length];
    var nulls = new boolean[length];
    for (int i = 0; i < length; i++) {
      slots[i] = Slot.of(value + i);
      nulls[i] = masked && i == 5;
    }
    if (masked) {
      slots[5] = Slot.of(0L);
    }
    return new Batch(slots, nulls);
  }

  @Test
  @DisplayName("under exactRefusalStatus a body of the slot check and a range guard names the "
      + "reason that fired, and by default both")
  void twoReasonsUnderExactStatus() {
    int precision = 9;
    var roots = List.<VarkaVectorIR>of(
        new GuardedRange(new DecimalColumn(0, LaneType.LONG, precision), -1000L, 1000L));
    int slot = VarkaFusedKernel.STATUS_DECIMAL_SLOT;
    int range = VarkaFusedKernel.STATUS_VALUE_RANGE;
    // A wrong high word over a low word inside the range, so that only the slot check fires.
    Slot badSlot = new Slot(5L, 1L);
    Slot farValue = Slot.of(5000L);
    for (int lanes : WIDTHS) {
      for (boolean exact : new boolean[] {false, true}) {
        var options = VarkaMatrixRuns.BASE.withLanesOverride(lanes).withExactRefusalStatus(exact);
        VarkaFusedKernel k = kernel(roots, 1, 0, options).kernel();
        for (boolean masked : new boolean[] {false, true}) {
          for (int row : new int[] {0, 36}) {
            String at = lanes + " long lanes, exact " + exact + ", masked " + masked + ", row "
                + row;
            assertEquals(0, runBatch(k, plain(37, masked, -10L), precision, null, 1,
                new long[0]).status(), at);
            Batch b = plain(37, masked, -10L);
            b.slots()[row] = badSlot;
            assertEquals(exact ? slot : slot | range,
                runBatch(k, b, precision, null, 1, new long[0]).status(), at + ", a bad slot");
            b = plain(37, masked, -10L);
            b.slots()[row] = farValue;
            assertEquals(exact ? range : slot | range,
                runBatch(k, b, precision, null, 1, new long[0]).status(), at + ", a far value");
            b.slots()[row == 0 ? 36 : 0] = badSlot;
            assertEquals(slot | range,
                runBatch(k, b, precision, null, 1, new long[0]).status(), at + ", both");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("a kernel split into groups by the method byte budget loads and checks the leaf "
      + "in each group's body")
  void perGroupBodiesUnderTheBudget() {
    int precision = 18;
    int outputs = 6;
    var d = new DecimalColumn(0, LaneType.LONG, precision);
    var roots = new ArrayList<VarkaVectorIR>();
    long[] literals = new long[outputs];
    for (int o = 0; o < outputs; o++) {
      roots.add(new IntArith(IntOp.ADD, Overflow.WRAP, d, new LiteralSlot(o, LaneType.LONG)));
      literals[o] = 100L * (o + 1);
    }
    for (int lanes : WIDTHS) {
      var whole = VarkaMatrixRuns.BASE.withLanesOverride(lanes).withSeveralKernels(false);
      // Halfway between the longest method of one output's class and of the six outputs' unsplit
      // one: a group of one fits, and the six need more than one group.
      int budget = (longestMethod(VarkaEmitterFixtures.emit(roots.subList(0, 1), 1, 1, whole)
          .bytes()) + longestMethod(VarkaEmitterFixtures.emit(roots, 1, outputs, whole).bytes()))
          / 2;
      var options = whole.withMethodByteBudget(budget);
      var emitted = VarkaEmitterFixtures.emit(roots, 1, outputs, options);
      List<String> loops = VarkaEmitterTestSupport.methodNames(emitted.bytes()).stream()
          .filter(m -> m.startsWith("loopDense")).toList();
      assertTrue(loops.size() >= 2,
          () -> "no split at " + lanes + " lanes under " + budget + " bytes: " + loops);
      for (String loop : loops) {
        assertTrue(VarkaEmitterTestSupport.invocationCount(emitted.bytes(), loop,
            "jdk.incubator.vector.VectorShuffle", List.of()) > 0,
            () -> loop + " builds no decimal shuffle at " + lanes + " lanes");
      }
      VarkaFusedKernel k = kernel(roots, 1, outputs, options).kernel();
      for (int length : LENGTHS) {
        for (boolean masked : new boolean[] {false, true}) {
          String at = lanes + " long lanes, length " + length + ", masked " + masked;
          Batch b = batch(length, precision, masked, 17L * lanes + length);
          KernelAnswer got = runBatch(k, b, precision, null, outputs, literals);
          assertEquals(0, got.status(), at);
          for (int o = 0; o < outputs; o++) {
            for (int i = 0; i < length; i++) {
              if (!b.nulls()[i]) {
                assertEquals(b.slots()[i].lo() + literals[o], got.values()[o][i],
                    at + ", output " + o + ", row " + i);
              }
            }
          }
          for (int row : positions(length)) {
            Batch bad = batch(length, precision, masked, 17L * lanes + length);
            bad.slots()[row] = new Slot(5L, 1L);
            bad.nulls()[row] = false;
            assertEquals(VarkaFusedKernel.STATUS_DECIMAL_SLOT,
                runBatch(k, bad, precision, null, outputs, literals).status(),
                at + ", a bad slot at row " + row);
          }
        }
      }
    }
  }

  /** The bytecode length of the class's longest method. */
  private static int longestMethod(byte[] bytes) {
    int longest = 0;
    for (MethodModel m : ClassFile.of().parse(bytes).methods()) {
      if (m.code().orElse(null) instanceof CodeAttribute code) {
        longest = Math.max(longest, code.codeLength());
      }
    }
    return longest;
  }

  @Test
  @DisplayName("an int column widened beside the leaf takes the mixed epilogue and keeps the check")
  void mixedLanesBesideTheLeaf() {
    for (int precision : PRECISIONS) {
      var roots = List.<VarkaVectorIR>of(new IntArith(IntOp.ADD, Overflow.WRAP,
          new DecimalColumn(0, LaneType.LONG, precision),
          new WidenLane(new ColumnRef(1), LaneType.LONG)));
      for (int lanes : WIDTHS) {
        var options = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
        assertTrue(VarkaKernelPlan.of(roots, options).mixed(), "not a mixed kernel");
        VarkaFusedKernel k = kernel(roots, 2, 0, options).kernel();
        for (int length : LENGTHS) {
          int[] ints = IntStream.range(0, length).map(i -> i * 7919 - 50_000).toArray();
          for (boolean masked : new boolean[] {false, true}) {
            String at = "precision " + precision + ", " + lanes + " long lanes, length " + length
                + ", masked " + masked;
            long seed = 13L * lanes + precision + length;
            Batch b = batch(length, precision, masked, seed);
            KernelAnswer got = runBatch(k, b, precision, ints, 1, new long[0]);
            assertEquals(0, got.status(), at);
            for (int i = 0; i < length; i++) {
              assertEquals(!b.nulls()[i], got.valid()[0][i], at + ", validity of row " + i);
              if (!b.nulls()[i]) {
                assertEquals(b.slots()[i].lo() + ints[i], got.values()[0][i], at + ", row " + i);
              }
            }
            for (int row : positions(length)) {
              for (Slot slot : declining(precision)) {
                Batch bad = batch(length, precision, masked, seed);
                bad.slots()[row] = slot;
                bad.nulls()[row] = false;
                assertEquals(VarkaFusedKernel.STATUS_DECIMAL_SLOT,
                    runBatch(k, bad, precision, ints, 1, new long[0]).status(),
                    at + ", " + slot + " at row " + row);
                bad.nulls()[row] = true;
                assertEquals(0, runBatch(k, bad, precision, ints, 1, new long[0]).status(),
                    at + ", " + slot + " under a null at row " + row);
              }
            }
          }
        }
      }
    }
  }

  @Test
  @DisplayName("an all-null batch reads no slot and does not decline")
  void allNull() {
    for (int lanes : WIDTHS) {
      var k = kernel(List.of(new DecimalColumn(0, LaneType.LONG, 9)), 0, lanes).kernel();
      Slot[] slots = new Slot[37];
      boolean[] nulls = new boolean[37];
      for (int i = 0; i < slots.length; i++) {
        slots[i] = new Slot(power(9), 1L);
        nulls[i] = true;
      }
      assertFalse(check("all null at " + lanes + " lanes", k, new Batch(slots, nulls), 9,
          new long[0], x -> x));
    }
  }

  @Test
  @DisplayName("a decimal column renders apart from a long column of its ordinal, and by its "
      + "precision")
  void shapeKey() {
    String dec9 = VarkaVectorIR.canonical(new DecimalColumn(0, LaneType.LONG, 9));
    String dec18 = VarkaVectorIR.canonical(new DecimalColumn(0, LaneType.LONG, 18));
    String bigint = VarkaVectorIR.canonical(new ColumnRef(0, LaneType.LONG));
    assertEquals("dec:0:long:p9", dec9);
    assertNotEquals(dec9, dec18);
    assertNotEquals(dec9, bigint);
  }

  @Test
  @DisplayName("a decimal column is refused off the long lane, past precision 18, and beside a "
      + "long read of the same column")
  void refusals() {
    assertThrows(IllegalArgumentException.class,
        () -> new DecimalColumn(0, LaneType.INT, 9));
    assertThrows(IllegalArgumentException.class,
        () -> new DecimalColumn(0, LaneType.LONG, 19));
    assertThrows(IllegalArgumentException.class,
        () -> new DecimalColumn(0, LaneType.LONG, 0));
    var both = List.<VarkaVectorIR>of(new DecimalColumn(0, LaneType.LONG, 9),
        new ColumnRef(0, LaneType.LONG));
    // The check runs once after the walk and names the lowest column read at two strides, here
    // column 1 although the walk meets column 2's second read first, and whether the two reads
    // share a root or not.
    var twoColumns = List.<VarkaVectorIR>of(new ColumnRef(2, LaneType.LONG),
        new DecimalColumn(2, LaneType.LONG, 9),
        new IntArith(IntOp.ADD, Overflow.WRAP, new DecimalColumn(1, LaneType.LONG, 18),
            new ColumnRef(1, LaneType.LONG)));
    for (var roots : List.of(both, twoColumns)) {
      int inputs = roots == both ? 1 : 3;
      String column = roots == both ? "column 0 " : "column 1 ";
      try {
        VarkaEmitterFixtures.emit(roots, inputs, 0, VarkaMatrixRuns.BASE);
        fail("one column read at two strides was emitted");
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage().contains(column + "is read both as a decimal and as a long "
            + "column"), e.getMessage());
      }
    }
  }

  @Test
  @DisplayName("the mapping fault injector moves a decimal column's segment: mapped short the last "
      + "group's load fails, mapped long the sanitizer names the column")
  void misdescribedMapping() {
    // A condition root writes its selection bitmap alone, so the decimal column's is the only
    // data segment the kernel maps, and a failure can only be that segment's.
    List<VarkaVectorIR> roots = List.of(new Compare(CompareOp.GT,
        new DecimalColumn(0, LaneType.LONG, 9), new LiteralSlot(0, LaneType.LONG)));
    // A whole number of lane groups at every width: the dense loop's last loads end at the
    // batch's last byte, which a short mapping cuts.
    int length = 64;
    for (int lanes : WIDTHS) {
      for (boolean masked : new boolean[] {false, true}) {
        Batch b = batch(length, 9, masked, 31L * lanes + (masked ? 1 : 0));
        var base = VarkaMatrixRuns.BASE.withLanesOverride(lanes);
        try (BufferAllocator allocator =
                 ArrowUtils.rootAllocator().newChildAllocator("dec-fault", 0, Long.MAX_VALUE);
             DecimalVector v = vector(b, 9, allocator)) {
          String at = lanes + " lanes, masked " + masked;
          VarkaFusedKernel exact = kernel(roots, 1, 1, base).kernel();
          assertEquals(0, run(exact, v, new long[] {0L}).status(), at);
          VarkaFusedKernel shortMapped =
              kernel(roots, 1, 1, base.withMisdescribeDataBytes(-4)).kernel();
          assertThrows(IndexOutOfBoundsException.class,
              () -> run(shortMapped, v, new long[] {0L}), at);
          if (VarkaMemorySanitizer.ENABLED) {
            VarkaFusedKernel longMapped =
                kernel(roots, 1, 1, base.withMisdescribeDataBytes(4096)).kernel();
            var violation = assertThrows(VarkaMemoryViolation.class,
                () -> run(longMapped, v, new long[] {0L}), at);
            assertTrue(violation.getMessage().contains("the nearest is input data 0"),
                violation.getMessage());
          }
        }
      }
    }
  }

  /** The bound a precision admits, read back from the leaf. */
  @Test
  @DisplayName("the bound of precision p is 10^p - 1")
  void bounds() {
    var got = new ArrayList<Long>();
    for (int p : new int[] {1, 9, 18}) {
      got.add(new DecimalColumn(0, LaneType.LONG, p).bound());
    }
    assertEquals(List.of(9L, 999_999_999L, 999_999_999_999_999_999L), got);
  }
}
