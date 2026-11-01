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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;

import jdk.incubator.vector.LongVector;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.DecimalStore;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenDecimal;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.util.ArrowUtils;
import org.apache.spark.sql.vectorized.ArrowColumnVector;

/**
 * The store table of the decimal store (VARKA-157 3 items 1 and 2), the mirror of the decimal
 * load's slot table: a {@link WidenDecimal} kernel run over long columns into the data buffer of
 * an Arrow {@code DecimalVector}, its slots held byte for byte to what Arrow's own writer,
 * {@code DecimalVector.set(int, long)}, writes for the same value, and read back through Spark's
 * {@link ArrowColumnVector} as the same {@code Decimal}.
 *
 * <p>The values are 0, -1, {@code 10^18 - 1}, {@code -(10^18 - 1)} and random values of both
 * signs within eighteen digits, planted in the first lane group, a middle one and the last row.
 * No producer the compiler builds reaches a negative value yet, so this table is where the high
 * word's {@code -1} is exercised; and {@code Decimal.equals} compares by value, so only the byte
 * comparison sees a wrong high word as anything but an error.
 *
 * <p>Every test here loops over the lane widths {@link #widths()} names, each case in the dense
 * body (no null) and the masked one (nulls on every third row), under both arms of
 * {@code decimalStore}; a test whose subject does not depend on the width is
 * {@link VarkaDecimalStoreSuite}'s alone. Two classes run the tables, each in a JVM of its own:
 * {@code VarkaDecimalStoreSuite} at two, four and eight long lanes, baked, and at the host's
 * preferred species unbaked, which are several species of the long lane and so refused by the
 * shared JVM (VARKA-246); and
 * {@link VarkaDecimalStoreOneLaneSuite} at the preferred species of a JVM where it is one long
 * lane, where the interleave's index has no second half to step into.
 */
abstract class VarkaDecimalStoreTables {

  /**
   * As the load's table: under one group, a last row in the epilogue at every baked width, none.
   * At one lane every length is whole groups, so none reaches an epilogue there.
   */
  private static final int[] LENGTHS = {1, 3, 37, 64};
  private static final long MAX = 999_999_999_999_999_999L;
  private static final int PRECISION = 18;
  private static final long SLOT = 16L;

  /** The values planted at each position, beside the batch's random ones. */
  private static final long[] PLANTED = {0L, -1L, MAX, -MAX, 1L, -10L};

  /** A batch of long columns: each row's values and whether the row is null in every column. */
  private record Batch(long[][] columns, boolean[] nulls) {
    int length() {
      return nulls.length;
    }
  }

  /** {@code columns} columns of values within eighteen digits, of both signs. */
  private static Batch batch(int length, int columns, boolean masked, long seed) {
    var random = new Random(seed);
    var data = new long[columns][length];
    var nulls = new boolean[length];
    for (int i = 0; i < length; i++) {
      nulls[i] = masked && i % 3 == 1;
      for (int c = 0; c < columns; c++) {
        data[c][i] = Math.floorMod(random.nextLong(), 2 * MAX + 1) - MAX;
      }
    }
    return new Batch(data, nulls);
  }

  /** The rows a value is planted at: the first group, a middle one and the last row. */
  private static int[] positions(int length) {
    return IntStream.of(0, length / 2, length - 1).distinct().toArray();
  }

  /** {@code roots} over {@code inputs} long columns under {@code options}, verified and loaded. */
  private static VarkaFusedKernel kernel(List<VarkaVectorIR> roots, int inputs, int literals,
      VarkaEmitOptions options) {
    var emitted = VarkaEmitterFixtures.emit(roots, inputs, literals, options);
    List<String> errors = VarkaEmitterTestSupport.verify(emitted.bytes());
    assertTrue(errors.isEmpty(), () -> "the class does not verify: " + errors);
    return VarkaEmitterFixtures.load(emitted).kernel();
  }

  /**
   * The lane overrides the tables run at: baked widths, or 1, which bakes none and so runs the
   * preferred species ({@code Lane.emitLanes}).
   */
  abstract int[] widths();

  /** A width as a case names it: the baked lane count, or the preferred species' for 1. */
  static String label(int lanes) {
    return lanes == 1
        ? "the preferred species' " + LongVector.SPECIES_PREFERRED.length() + " long lanes"
        : lanes + " long lanes";
  }

  private static VarkaEmitOptions options(int lanes, DecimalStore arm) {
    return VarkaMatrixRuns.BASE.withLanesOverride(lanes).withDecimalStore(arm);
  }

  /**
   * Where the kernel writes: each output's slots and its validity, at addresses the caller owns.
   * The run fills the slots with a pattern first, so a slot the kernel leaves unwritten shows.
   */
  private record Destination(long[] data, long[] validity) { }

  /**
   * Runs {@code kernel} over {@code b}'s columns, int columns where {@code intColumns} marks them
   * (four bytes a row) and long ones otherwise, into {@code out}; returns the status.
   */
  private static int run(VarkaFusedKernel kernel, Batch b, boolean[] intColumns,
      long[] literals, Destination out) {
    int length = b.length();
    int inputs = b.columns().length;
    long wordBytes = ((length + 63) / 64) * 8L;
    int nulls = (int) IntStream.range(0, length).filter(i -> b.nulls()[i]).count();
    try (var arena = Arena.ofConfined()) {
      long[] srcData = new long[inputs];
      long[] srcValidity = new long[inputs];
      int[] nullCounts = new int[inputs];
      long[] inStride = new long[inputs];
      for (int c = 0; c < inputs; c++) {
        boolean ints = intColumns != null && intColumns[c];
        inStride[c] = ints ? 4L : 8L;
        MemorySegment data = arena.allocate(Math.max(1, length) * inStride[c], 64);
        MemorySegment valid = arena.allocate(wordBytes, 64);
        for (int i = 0; i < length; i++) {
          if (ints) {
            data.set(ValueLayout.JAVA_INT, i * 4L, (int) b.columns()[c][i]);
          } else {
            data.set(ValueLayout.JAVA_LONG, i * 8L, b.columns()[c][i]);
          }
          if (!b.nulls()[i]) {
            long word = valid.get(ValueLayout.JAVA_LONG, (i >>> 6) * 8L);
            valid.set(ValueLayout.JAVA_LONG, (i >>> 6) * 8L, word | 1L << (i & 63));
          }
        }
        srcData[c] = data.address();
        srcValidity[c] = valid.address();
        nullCounts[c] = nulls;
      }
      long[] outStride = new long[out.data().length];
      java.util.Arrays.fill(outStride, SLOT);
      return VarkaKernelChecks.sanitized(kernel, length, inStride, outStride, srcData,
          srcValidity, out.data(), out.validity(),
          scratch -> kernel.run(srcData, srcValidity, nullCounts, out.data(), out.validity(),
              new int[0], literals, length, scratch));
    }
  }

  /**
   * Runs {@code kernel}, with {@code outputs} decimal outputs, over {@code b} into Arrow decimal
   * vectors and holds every output to {@code expected}: each valid slot's sixteen bytes equal
   * the slot {@code DecimalVector.set(int, long)} writes for the value, Spark's reader reads it
   * back as {@code Decimal(u, 18, 0)}, and each null row is null.
   */
  private static void check(String context, VarkaFusedKernel kernel, Batch b,
      boolean[] intColumns, long[] literals, long[][] expected) {
    int length = b.length();
    int outputs = expected.length;
    try (BufferAllocator allocator =
        ArrowUtils.rootAllocator().newChildAllocator("varka-decimal-store", 0, Long.MAX_VALUE)) {
      var got = new DecimalVector[outputs];
      var want = new DecimalVector[outputs];
      try {
        long[] data = new long[outputs];
        long[] validity = new long[outputs];
        for (int o = 0; o < outputs; o++) {
          got[o] = new DecimalVector("got" + o, allocator, PRECISION, 0);
          got[o].allocateNew(length);
          // A pattern whose high word is no sign extension: a slot left unwritten fails both
          // the byte comparison and Spark's reader.
          for (int i = 0; i < length; i++) {
            got[o].getDataBuffer().setLong(i * SLOT, 0x5A5A5A5A5A5A5A5AL);
            got[o].getDataBuffer().setLong(i * SLOT + 8, 0x0123456789ABCDEFL);
          }
          data[o] = got[o].getDataBuffer().memoryAddress();
          validity[o] = got[o].getValidityBuffer().memoryAddress();
        }
        assertEquals(0, run(kernel, b, intColumns, literals, new Destination(data, validity)),
            () -> context + ": the store never declines");
        for (int o = 0; o < outputs; o++) {
          got[o].setValueCount(length);
          want[o] = new DecimalVector("want" + o, allocator, PRECISION, 0);
          want[o].allocateNew(length);
          for (int i = 0; i < length; i++) {
            if (b.nulls()[i]) {
              want[o].setNull(i);
            } else {
              want[o].set(i, expected[o][i]);
            }
          }
          want[o].setValueCount(length);
          var spark = new ArrowColumnVector(got[o]);
          for (int i = 0; i < length; i++) {
            String at = context + ", output " + o + ", row " + i;
            assertEquals(b.nulls()[i], spark.isNullAt(i), at + ": validity");
            if (b.nulls()[i]) {
              continue;
            }
            assertEquals(want[o].getDataBuffer().getLong(i * SLOT),
                got[o].getDataBuffer().getLong(i * SLOT), at + ": the low word");
            assertEquals(want[o].getDataBuffer().getLong(i * SLOT + 8),
                got[o].getDataBuffer().getLong(i * SLOT + 8), at + ": the high word");
            Decimal d = spark.getDecimal(i, PRECISION, 0);
            assertEquals(expected[o][i], d.toUnscaledLong(), at + ": Spark's reading");
            assertEquals(0, d.scale(), at + ": Spark's scale");
          }
        }
      } finally {
        for (int o = 0; o < outputs; o++) {
          if (got[o] != null) {
            got[o].close();
          }
          if (want[o] != null) {
            want[o].close();
          }
        }
      }
    }
  }

  /** The store's value of each row of column 0, for a table over the plain store. */
  private static long[][] column0(Batch b) {
    return new long[][] {b.columns()[0].clone()};
  }

  @Test
  @DisplayName("a decimal output's slots are Arrow's writer's bytes at each width, in both "
      + "bodies and both interleaves, with every value planted at the first group, a middle one "
      + "and the last row")
  void storeTable() {
    var roots = List.<VarkaVectorIR>of(new WidenDecimal(new ColumnRef(0, LaneType.LONG)));
    int planted = 0;
    for (int lanes : widths()) {
      for (DecimalStore arm : DecimalStore.values()) {
        VarkaFusedKernel k = kernel(roots, 1, 0, options(lanes, arm));
        for (int length : LENGTHS) {
          for (boolean masked : new boolean[] {false, true}) {
            String at = String.format(Locale.ROOT, "%s at %s, length %d, %s", arm,
                label(lanes), length, masked ? "with nulls" : "no null");
            long seed = 31L * lanes + length + (masked ? 1 : 0) + 7L * arm.ordinal();
            Batch b = batch(length, 1, masked, seed);
            check(at, k, b, null, new long[0], column0(b));
            for (int row : positions(length)) {
              for (long value : PLANTED) {
                Batch p = batch(length, 1, masked, seed);
                p.columns()[0][row] = value;
                p.nulls()[row] = false;
                check(at + ", " + value + " at row " + row, k, p, null, new long[0], column0(p));
                planted++;
              }
            }
          }
        }
      }
    }
    assertTrue(planted > 0, "the table planted no value");
  }

  @Test
  @DisplayName("a decimal output over long arithmetic stores its value, as any long root would "
      + "compute it")
  void storeTableUnderArithmetic() {
    var roots = List.<VarkaVectorIR>of(new WidenDecimal(new IntArith(IntOp.SUB, Overflow.WRAP,
        new ColumnRef(0, LaneType.LONG), new LiteralSlot(0, LaneType.LONG))));
    long[] literals = {123_456_789L};
    for (int lanes : widths()) {
      for (DecimalStore arm : DecimalStore.values()) {
        VarkaFusedKernel k = kernel(roots, 1, 1, options(lanes, arm));
        for (int length : LENGTHS) {
          for (boolean masked : new boolean[] {false, true}) {
            Batch b = batch(length, 1, masked, 97L * lanes + length);
            // Within eighteen digits after the subtraction too.
            for (int i = 0; i < length; i++) {
              b.columns()[0][i] /= 2;
            }
            long[][] expected = {new long[length]};
            for (int i = 0; i < length; i++) {
              expected[0][i] = b.columns()[0][i] - literals[0];
            }
            check(arm + " at " + label(lanes) + ", length " + length + ", masked " + masked, k,
                b, null, literals, expected);
          }
        }
      }
    }
  }

  @Test
  @DisplayName("an int column widened beside the store takes the mixed epilogue and stores its "
      + "slots")
  void mixedLanesBesideTheStore() {
    var roots = List.<VarkaVectorIR>of(new WidenDecimal(new IntArith(IntOp.ADD, Overflow.WRAP,
        new ColumnRef(0, LaneType.LONG), new WidenLane(new ColumnRef(1), LaneType.LONG))));
    boolean[] intColumns = {false, true};
    for (int lanes : widths()) {
      for (DecimalStore arm : DecimalStore.values()) {
        var options = options(lanes, arm);
        assertTrue(VarkaKernelPlan.of(roots, options).mixed(), "not a mixed kernel");
        VarkaFusedKernel k = kernel(roots, 2, 0, options);
        for (int length : LENGTHS) {
          for (boolean masked : new boolean[] {false, true}) {
            Batch b = batch(length, 2, masked, 13L * lanes + length);
            long[][] expected = {new long[length]};
            for (int i = 0; i < length; i++) {
              b.columns()[0][i] /= 2;
              b.columns()[1][i] = (int) b.columns()[1][i];
              expected[0][i] = b.columns()[0][i] + b.columns()[1][i];
            }
            check(arm + " at " + label(lanes) + ", length " + length + ", masked " + masked, k,
                b, intColumns, new long[0], expected);
          }
        }
      }
    }
  }

  @Test
  @DisplayName("six decimal outputs split into groups by the method byte budget each build the "
      + "interleave in their own body")
  void perGroupBodiesUnderTheBudget() {
    int outputs = 6;
    var column = new ColumnRef(0, LaneType.LONG);
    var roots = new ArrayList<VarkaVectorIR>();
    long[] literals = new long[outputs];
    for (int o = 0; o < outputs; o++) {
      roots.add(new WidenDecimal(new IntArith(IntOp.ADD, Overflow.WRAP, column,
          new LiteralSlot(o, LaneType.LONG))));
      literals[o] = 1000L * (o + 1) - 3500L;
    }
    for (int lanes : widths()) {
      for (DecimalStore arm : DecimalStore.values()) {
        var whole = options(lanes, arm).withSeveralKernels(false);
        // Halfway between the longest method of one output's class and of the six outputs'
        // unsplit one: a group of one fits, and the six need more than one group.
        int budget = (longestMethod(VarkaEmitterFixtures.emit(roots.subList(0, 1), 1, 1, whole)
            .bytes()) + longestMethod(VarkaEmitterFixtures.emit(roots, 1, outputs, whole)
            .bytes())) / 2;
        var split = whole.withMethodByteBudget(budget);
        byte[] bytes = VarkaEmitterFixtures.emit(roots, 1, outputs, split).bytes();
        List<String> loops = VarkaEmitterTestSupport.methodNames(bytes).stream()
            .filter(m -> m.startsWith("loopDense")).toList();
        assertTrue(loops.size() >= 2,
            () -> "no split at " + label(lanes) + " under " + budget + " bytes: " + loops);
        for (String loop : loops) {
          // Two interleaves a body, each from its own iota.
          assertEquals(2, invocations(bytes, loop, "addIndex"),
              () -> loop + " does not build the two interleaves at " + label(lanes));
        }
        VarkaFusedKernel k = kernel(roots, 1, outputs, split);
        for (int length : LENGTHS) {
          for (boolean masked : new boolean[] {false, true}) {
            Batch b = batch(length, 1, masked, 17L * lanes + length);
            long[][] expected = new long[outputs][length];
            for (int i = 0; i < length; i++) {
              b.columns()[0][i] /= 2;
              for (int o = 0; o < outputs; o++) {
                expected[o][i] = b.columns()[0][i] + literals[o];
              }
            }
            check(arm + " at " + label(lanes) + ", length " + length + ", masked " + masked, k,
                b, null, literals, expected);
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

  /** How many instructions of {@code method} invoke a method named {@code callee}. */
  private static int invocations(byte[] bytes, String method, String callee) {
    int count = 0;
    for (MethodModel m : ClassFile.of().parse(bytes).methods()) {
      if (!m.methodName().equalsString(method) || m.code().isEmpty()) {
        continue;
      }
      for (CodeElement e : m.code().get()) {
        if (e instanceof InvokeInstruction invoke && invoke.name().equalsString(callee)) {
          count++;
        }
      }
    }
    return count;
  }

  @Test
  @DisplayName("the mapping fault injector moves a decimal output's segment: mapped short the "
      + "last group's store fails, mapped long the sanitizer names the output")
  void misdescribedMapping() {
    // A literal root reads no column, so the decimal output's is the only data segment the kernel
    // maps, and a failure can only be that segment's.
    var roots = List.<VarkaVectorIR>of(new WidenDecimal(new LiteralSlot(0, LaneType.LONG)));
    long[] literals = {-42L};
    // A whole number of lane groups at every width: the dense loop's last stores end at the
    // batch's last byte, which a short mapping cuts.
    int length = 64;
    for (int lanes : widths()) {
      Batch b = batch(length, 1, false, lanes);
      long[][] expected = {new long[length]};
      java.util.Arrays.fill(expected[0], literals[0]);
      var base = options(lanes, DecimalStore.REARRANGE);
      check("exact at " + label(lanes), kernel(roots, 1, 1, base), b, null, literals, expected);
      VarkaFusedKernel shortMapped = kernel(roots, 1, 1, base.withMisdescribeDataBytes(-4));
      assertThrows(IndexOutOfBoundsException.class,
          () -> check("short at " + label(lanes), shortMapped, b, null, literals, expected));
      if (VarkaMemorySanitizer.ENABLED) {
        VarkaFusedKernel longMapped = kernel(roots, 1, 1, base.withMisdescribeDataBytes(4096));
        var violation = assertThrows(VarkaMemoryViolation.class,
            () -> check("long at " + label(lanes), longMapped, b, null, literals, expected));
        assertTrue(violation.getMessage().contains("the nearest is output data 0"),
            violation.getMessage());
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // The guard page (VARKA-157 3 item 2)
  // ------------------------------------------------------------------------------------------

  private static final int PROT_NONE = 0;
  private static final int PROT_READ_WRITE = 3;
  private static final int MAP_PRIVATE_ANONYMOUS = 0x02 | 0x20;
  private static final long PAGE = 4096L;

  /** libc's {@code mmap}, {@code mprotect} and {@code munmap}, through the native linker. */
  private static final class Libc {
    static final Linker LINKER = Linker.nativeLinker();
    static final MethodHandle MMAP = handle("mmap", FunctionDescriptor.of(ValueLayout.ADDRESS,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
    static final MethodHandle MPROTECT = handle("mprotect", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    static final MethodHandle MUNMAP = handle("munmap", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
      return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor);
    }
  }

  @Test
  @DisplayName("the epilogue's two masked stores end on the batch's last slot: with the next "
      + "page unmapped, a store past length * 16 would fault")
  void guardPage() throws Throwable {
    assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux"),
        "the guard page maps memory through Linux's mmap");
    var roots = List.<VarkaVectorIR>of(new WidenDecimal(new ColumnRef(0, LaneType.LONG)));
    for (int lanes : widths()) {
      for (DecimalStore arm : DecimalStore.values()) {
        VarkaFusedKernel k = kernel(roots, 1, 0, options(lanes, arm));
        for (int length : LENGTHS) {
          for (boolean masked : new boolean[] {false, true}) {
            String at = arm + " at " + label(lanes) + ", length " + length + ", masked " + masked;
            Batch b = batch(length, 1, masked, 41L * lanes + length);
            long slots = length * SLOT;
            long mapped = (slots + PAGE - 1) / PAGE * PAGE;
            var region = (MemorySegment) Libc.MMAP.invokeExact(MemorySegment.NULL,
                mapped + PAGE, PROT_READ_WRITE, MAP_PRIVATE_ANONYMOUS, -1, 0L);
            assertNotEquals(-1L, region.address(), "mmap failed");
            try {
              var guard = MemorySegment.ofAddress(region.address() + mapped);
              assertEquals(0, (int) Libc.MPROTECT.invokeExact(guard, PAGE, PROT_NONE),
                  "mprotect failed");
              // The slots end exactly where the unmapped page begins.
              long start = region.address() + mapped - slots;
              try (var arena = Arena.ofConfined()) {
                MemorySegment validity = arena.allocate(((length + 63) / 64) * 8L, 64);
                assertEquals(0, run(k, b, null, new long[0],
                    new Destination(new long[] {start}, new long[] {validity.address()})), at);
                var out = VarkaSegments.map(start, slots);
                for (int i = 0; i < length; i++) {
                  long value = b.columns()[0][i];
                  assertEquals(value, out.get(ValueLayout.JAVA_LONG, i * SLOT), at + ", row " + i);
                  assertEquals(value >> 63, out.get(ValueLayout.JAVA_LONG, i * SLOT + 8),
                      at + ", row " + i);
                }
              }
            } finally {
              int unmapped = (int) Libc.MUNMAP.invokeExact(region, mapped + PAGE);
              assertEquals(0, unmapped, "munmap failed");
            }
          }
        }
      }
    }
  }
}
