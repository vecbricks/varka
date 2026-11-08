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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalStoreTables.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalWord;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Word;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.util.ArrowUtils;
import org.apache.spark.sql.vectorized.ArrowColumnVector;

/**
 * The 128-bit decimal's word leaves and their shared load and check (`m8/VARKA-359.md` 3 item 1),
 * run over Arrow decimal vectors whose sixteen-byte slots are written word by word, so that a
 * slot can hold a value with more digits than its precision, which Spark's writer never writes.
 *
 * <p>The oracle is Spark's column reader, the same vector wrapped in {@link ArrowColumnVector},
 * whose decimal accessor raises on a valid slot outside the precision and never reads a null one,
 * and {@link BigInteger} for the values. Every kernel runs at two, four and eight long lanes,
 * baked, and at the preferred species unbaked ({@code lanesOverride=1}), in the dense body and the
 * masked one. In a JVM of its own: the widths are several species of the long lane, which the
 * shared JVM refuses (VARKA-246).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDecimalWordSuite {

  private static final int[] WIDTHS = {1, 2, 4, 8};
  private static final int[] PRECISIONS = {19, 20, 30, 38};
  /**
   * Shorter than one lane group at every width but the narrowest, one whose last row is in the
   * epilogue at every baked width, and one with no epilogue.
   */
  private static final int[] LENGTHS = {1, 3, 37, 64};
  private static final int SCALE = 2;
  private static final BigInteger TWO_63 = BigInteger.ONE.shiftLeft(63);
  private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);
  private static final BigInteger TWO_127 = BigInteger.ONE.shiftLeft(127);

  /** A column's rows: each row's value (any 128-bit integer) and whether it is null. */
  private record Column(BigInteger[] values, boolean[] nulls, int precision) {
    int length() {
      return values.length;
    }
  }

  private static BigInteger bound(int precision) {
    return BigInteger.TEN.pow(precision).subtract(BigInteger.ONE);
  }

  /**
   * A column of in-precision values whose words both carry bits, with nulls on every third row
   * when {@code masked}, each null holding garbage, out-of-precision values included.
   */
  private static Column column(int length, int precision, boolean masked, long seed) {
    var random = new Random(seed);
    BigInteger b = bound(precision);
    var values = new BigInteger[length];
    var nulls = new boolean[length];
    for (int i = 0; i < length; i++) {
      nulls[i] = masked && i % 3 == 1;
      if (nulls[i]) {
        values[i] = new BigInteger(127, random).subtract(TWO_127.shiftRight(1));
      } else {
        BigInteger v = new BigInteger(b.bitLength() + 8, random).mod(b.shiftLeft(1).add(
            BigInteger.ONE)).subtract(b);
        values[i] = v;
      }
    }
    return new Column(values, nulls, precision);
  }

  /** The rows a planted slot is tried at: the first group, a middle one and the last row. */
  private static int[] positions(int length) {
    return IntStream.of(0, length / 2, length - 1).distinct().toArray();
  }

  /** The values each of which must decline the batch where it is valid. */
  private static List<BigInteger> declining(int precision) {
    BigInteger p = BigInteger.TEN.pow(precision);
    return List.of(p, p.negate(), TWO_127.subtract(BigInteger.ONE), TWO_127.negate());
  }

  /**
   * The values that pass: the bounds, zero and minus one, and a low word with its sign bit set
   * under a high word of 0 and of -1 (every precision from 19 holds {@code 2^63}).
   */
  private static List<BigInteger> passing(int precision) {
    BigInteger b = bound(precision);
    return List.of(b, b.negate(), BigInteger.ZERO, BigInteger.ONE.negate(), TWO_63,
        TWO_63.negate().subtract(BigInteger.ONE));
  }

  /** The vector of {@code c}, its slots written word by word and its nulls marked. */
  private static DecimalVector vector(Column c, BufferAllocator allocator) {
    var v = new DecimalVector("d", allocator, c.precision(), SCALE);
    v.allocateNew(Math.max(1, c.length()));
    for (int i = 0; i < c.length(); i++) {
      DecimalWords w = DecimalWords.of(c.values()[i]);
      v.getDataBuffer().setLong(i * 16L, w.lo());
      v.getDataBuffer().setLong(i * 16L + 8, w.hi());
      if (c.nulls()[i]) {
        v.setNull(i);
      } else {
        v.setIndexDefined(i);
      }
    }
    v.setValueCount(c.length());
    return v;
  }

  /** Spark's reading of a column: each valid row's unscaled value, or null if some row raised. */
  private static BigInteger[] spark(DecimalVector v, int precision) {
    var column = new ArrowColumnVector(v);
    var unscaled = new BigInteger[v.getValueCount()];
    for (int i = 0; i < unscaled.length; i++) {
      if (column.isNullAt(i)) {
        continue;
      }
      try {
        Decimal d = column.getDecimal(i, precision, SCALE);
        unscaled[i] = d.toJavaBigDecimal().setScale(SCALE).unscaledValue();
      } catch (ArithmeticException e) {
        return null;
      }
    }
    return unscaled;
  }

  /** The kernel's answer: its status, then each row's value and validity, per output. */
  private record Answer(int status, long[][] values, boolean[][] valid) { }

  /** Runs {@code kernel} over decimal vectors at ordinals 0, 1, ... with long outputs. */
  private static Answer run(VarkaFusedKernel kernel, List<DecimalVector> columns, int outputs,
      long[] literals) {
    int length = columns.get(0).getValueCount();
    int inputs = columns.size();
    long wordBytes = ((length + 63) / 64) * 8L;
    try (var arena = Arena.ofConfined()) {
      long[] srcData = new long[inputs];
      long[] srcValidity = new long[inputs];
      int[] nullCounts = new int[inputs];
      long[] inStride = new long[inputs];
      for (int c = 0; c < inputs; c++) {
        DecimalVector v = columns.get(c);
        srcData[c] = v.getDataBuffer().memoryAddress();
        srcValidity[c] = v.getNullCount() == length ? 0L : v.getValidityBuffer().memoryAddress();
        nullCounts[c] = v.getNullCount();
        inStride[c] = 16L;
      }
      long[] outData = new long[outputs];
      long[] outValidity = new long[outputs];
      long[] outStride = new long[outputs];
      MemorySegment[] out = new MemorySegment[outputs];
      MemorySegment[] outValid = new MemorySegment[outputs];
      for (int o = 0; o < outputs; o++) {
        out[o] = arena.allocate(Math.max(1, length) * 8L, 64);
        outValid[o] = arena.allocate(Math.max(8, wordBytes), 64);
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
      return new Answer(status, values, validRows);
    }
  }

  private static VarkaEmitterFixtures.Loaded kernel(List<VarkaVectorIR> roots, int inputs,
      int literals, VarkaEmitOptions options) {
    var emitted = VarkaEmitterFixtures.emit(roots, inputs, literals, options);
    List<String> errors = VarkaEmitterTestSupport.verify(emitted.bytes());
    assertTrue(errors.isEmpty(), () -> "the class does not verify: " + errors);
    return VarkaEmitterFixtures.load(emitted);
  }

  private static VarkaEmitOptions at(int lanes) {
    return VarkaMatrixRuns.BASE.withLanesOverride(lanes);
  }

  private static DecimalWord lo(int ordinal, int precision) {
    return new DecimalWord(ordinal, Word.LOW, precision);
  }

  private static DecimalWord hi(int ordinal, int precision) {
    return new DecimalWord(ordinal, Word.HIGH, precision);
  }

  // ------------------------------------------------------------------------------------------
  // The slot table: the load, its de-interleave and its 10^p check
  // ------------------------------------------------------------------------------------------

  /**
   * Runs the kernel of the column's two words as two outputs over {@code c}; returns a mismatch,
   * or null where the kernel declines exactly where Spark's reader raises and answers both
   * words of every valid row otherwise. {@code declined[0]} says whether it declined.
   */
  private static String checkWords(String at, VarkaFusedKernel k, Column c, boolean[] declined) {
    try (BufferAllocator allocator =
        ArrowUtils.rootAllocator().newChildAllocator("varka-decimal-pair", 0, Long.MAX_VALUE);
        DecimalVector v = vector(c, allocator)) {
      BigInteger[] expected = spark(v, c.precision());
      Answer got = run(k, List.of(v), 2, new long[0]);
      declined[0] = got.status() != 0;
      if (expected == null) {
        return got.status() == VarkaFusedKernel.STATUS_DECIMAL_SLOT ? null
            : at + ": Spark's reader raises, the kernel answers status " + got.status();
      }
      if (got.status() != 0) {
        return at + ": Spark's reader reads every row, the kernel declines " + got.status();
      }
      for (int i = 0; i < c.length(); i++) {
        if (got.valid()[0][i] == c.nulls()[i] || got.valid()[1][i] == c.nulls()[i]) {
          return at + ": validity of row " + i;
        }
        if (!c.nulls()[i]) {
          DecimalWords w = DecimalWords.of(expected[i]);
          if (got.values()[0][i] != w.lo() || got.values()[1][i] != w.hi()) {
            return at + ": row " + i + " holds " + expected[i] + ", the kernel's words are ("
                + got.values()[0][i] + ", " + got.values()[1][i] + ")";
          }
        }
      }
      return null;
    }
  }

  /** The slot table at {@code widths}: the first mismatch, or null. */
  private static String slotTable(int[] widths, int[] precisions) {
    boolean[] declined = new boolean[1];
    int planted = 0;
    for (int lanes : widths) {
      for (int precision : precisions) {
        VarkaFusedKernel k = kernel(List.of(lo(0, precision), hi(0, precision)), 1, 0,
            at(lanes)).kernel();
        for (int length : LENGTHS) {
          for (boolean masked : new boolean[] {false, true}) {
            String where = label(lanes) + ", precision " + precision + ", length " + length
                + (masked ? ", with nulls" : ", no null");
            long seed = 31L * lanes + 7L * precision + length + (masked ? 1 : 0);
            String m = checkWords(where, k, column(length, precision, masked, seed), declined);
            if (m != null) {
              return m;
            }
            for (int row : positions(length)) {
              for (BigInteger value : passing(precision)) {
                Column c = column(length, precision, masked, seed);
                c.values()[row] = value;
                c.nulls()[row] = false;
                m = checkWords(where + ", " + value + " at row " + row, k, c, declined);
                if (m != null || declined[0]) {
                  return m != null ? m : where + ": " + value + " passes, yet declined";
                }
              }
              for (BigInteger value : declining(precision)) {
                Column c = column(length, precision, masked, seed);
                c.values()[row] = value;
                c.nulls()[row] = false;
                String context = where + ", " + value + " at row " + row;
                m = checkWords(context, k, c, declined);
                if (m != null || !declined[0]) {
                  return m != null ? m : context + ": Spark raises, yet the kernel answered";
                }
                planted++;
                c.nulls()[row] = true;
                m = checkWords(context + " under a null", k, c, declined);
                if (m != null || declined[0]) {
                  return m != null ? m : context + ": a null slot declined";
                }
              }
            }
          }
        }
      }
    }
    return planted > 0 ? null : "the table planted no declining slot";
  }

  @Test
  @DisplayName("a 128-bit decimal column's slots load as their two words, and a valid slot "
      + "outside the precision declines the batch, a null one never")
  void slotTableOfThePair() {
    assertNull(slotTable(WIDTHS, PRECISIONS));
  }

  @Test
  @DisplayName("a body that reads both words of a column loads its slots once and checks them "
      + "once, in the loop and in the epilogue")
  void bothWordsShareOneLoad() {
    for (int lanes : WIDTHS) {
      var roots = List.<VarkaVectorIR>of(hi(0, 38), lo(0, 38),
          new IfElse(new IsNotNull(lo(0, 38)), hi(0, 38), lo(0, 38)));
      var emitted = VarkaEmitterFixtures.emit(roots, 1, 0, at(lanes));
      List<String> bodies = VarkaEmitterTestSupport.methodNames(emitted.bytes()).stream()
          .filter(m -> m.startsWith("loop") || m.startsWith("epilogue")).toList();
      assertTrue(bodies.stream().anyMatch(m -> m.startsWith("loop"))
          && bodies.stream().anyMatch(m -> m.startsWith("epilogue")), () -> "bodies " + bodies);
      for (String loop : bodies) {
        assertEquals(2, count(emitted.bytes(), loop, "rearrange"),
            () -> loop + " at " + label(lanes) + " takes the two rearranges once");
        assertEquals(2, count(emitted.bytes(), loop, "fromMemorySegment"),
            () -> loop + " at " + label(lanes) + " loads the two vectors of slots once");
      }
    }
  }

  @Test
  @DisplayName("the long-lane harness lays a word column out as sixteen-byte slots")
  void harnessReadsTheSlots() {
    var random = new Random(11L);
    for (var roots : List.<List<VarkaVectorIR>>of(List.of(lo(0, 38), hi(0, 38)),
        List.of(new IfElse(new IsNotNull(hi(0, 20)), lo(0, 20), hi(0, 20))))) {
      for (int lanes : WIDTHS) {
        var e = VarkaEmitterFixtures.emit(roots, 1, 0, at(lanes));
        for (int length : LENGTHS) {
          boolean[][] nulls = {new boolean[length]};
          for (int i = 0; i < length; i++) {
            nulls[0][i] = random.nextInt(4) == 0;
          }
          var batch = new VarkaKernelChecks.LongBatch(length, nulls,
              new long[][] {random.longs(length).toArray()}, false);
          assertTrue(VarkaKernelChecks.runAndCompareLong(label(lanes) + ", length " + length,
              e.className(), e.bytes(), roots, 1, new long[0], batch, false, null));
        }
      }
    }
  }

  /** How often {@code method} invokes a method named {@code name}. */
  private static int count(byte[] bytes, String method, String name) {
    int n = 0;
    for (var m : java.lang.classfile.ClassFile.of().parse(bytes).methods()) {
      if (!m.methodName().equalsString(method) || m.code().isEmpty()) {
        continue;
      }
      for (var element : m.code().get()) {
        if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
            && invoke.name().equalsString(name)) {
          n++;
        }
      }
    }
    return n;
  }


  @Test
  @DisplayName("a null test over a word reads the column's validity and still checks the slots")
  void nullTestChecksTheSlots() {
    for (int lanes : WIDTHS) {
      Cond c = new IsNotNull(lo(0, 38));
      var roots = List.<VarkaVectorIR>of(
          new IfElse(c, new LiteralSlot(0, LaneType.LONG), new LiteralSlot(1, LaneType.LONG)));
      VarkaFusedKernel k = kernel(roots, 1, 2, at(lanes)).kernel();
      for (int length : LENGTHS) {
        for (boolean masked : new boolean[] {false, true}) {
          Column col = column(length, 38, masked, 5L * lanes + length);
          try (BufferAllocator allocator =
              ArrowUtils.rootAllocator().newChildAllocator("varka-pair-null", 0, Long.MAX_VALUE);
              DecimalVector v = vector(col, allocator)) {
            Answer got = run(k, List.of(v), 1, new long[] {1L, 0L});
            assertEquals(0, got.status());
            for (int i = 0; i < length; i++) {
              assertEquals(col.nulls()[i] ? 0L : 1L, got.values()[0][i], "row " + i);
            }
          }
          col.values()[length - 1] = BigInteger.TEN.pow(38);
          col.nulls()[length - 1] = false;
          try (BufferAllocator allocator =
              ArrowUtils.rootAllocator().newChildAllocator("varka-pair-null", 0, Long.MAX_VALUE);
              DecimalVector v = vector(col, allocator)) {
            assertEquals(VarkaFusedKernel.STATUS_DECIMAL_SLOT,
                run(k, List.of(v), 1, new long[] {1L, 0L}).status(),
                label(lanes) + ", length " + length + ": a bad slot under IS NOT NULL");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("the planted fault: with the digit check dropped the pair computes where Spark's "
      + "reader raises, and the slot table fails")
  void plantedFaultIsCaught() {
    boolean[] declined = new boolean[1];
    for (int lanes : WIDTHS) {
      VarkaEmitOptions faulty = at(lanes).toBuilder().dropDecimalBound(true).build();
      VarkaFusedKernel k = kernel(List.of(lo(0, 38), hi(0, 38)), 1, 0, faulty).kernel();
      for (int length : LENGTHS) {
        Column c = column(length, 38, true, 3L * lanes + length);
        c.values()[length - 1] = BigInteger.TEN.pow(38);
        c.nulls()[length - 1] = false;
        String at = label(lanes) + ", length " + length;
        String m = checkWords(at, k, c, declined);
        assertTrue(m != null && !declined[0], () -> at + ": the fault computes, and is caught");
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // The leaf's record
  // ------------------------------------------------------------------------------------------


  @Test
  @DisplayName("a decimal word renders apart from the 64-bit leaf and is refused at 18 digits "
      + "or fewer")
  void wordRendersAndRefuses() {
    assertEquals("decw:0:lo:p38", VarkaVectorIR.canonical(lo(0, 38)));
    assertEquals("decw:3:hi:p19", VarkaVectorIR.canonical(hi(3, 19)));
    assertEquals(lo(0, 38), hi(0, 38).low());
    assertEquals(hi(0, 38), lo(0, 38).sibling());
    assertThrows(IllegalArgumentException.class, () -> lo(0, 18));
    assertThrows(IllegalArgumentException.class, () -> lo(0, 39));
    assertThrows(IllegalArgumentException.class, () -> lo(-1, 38));
    assertEquals(new BigDecimal(bound(38)).toBigInteger(), lo(0, 38).bound().value());
  }

  @Test
  @DisplayName("a column's words carry one precision, and a column is read as words or as the "
      + "64-bit leaf, never both")
  void oneReadingAColumn() {
    var asLong = new VarkaVectorIR.DecimalColumn(0, LaneType.LONG, 18);
    for (var roots : List.<List<VarkaVectorIR>>of(List.of(lo(0, 19), hi(0, 38)),
        List.of(lo(0, 38), asLong), List.of(asLong, hi(0, 38)))) {
      var e = assertThrows(IllegalArgumentException.class,
          () -> VarkaEmitterFixtures.emit(roots, 1, 0, at(2)), () -> "emitted " + roots);
      String expected = roots.contains(asLong) ? "column 0 is read both as a 64-bit decimal "
          + "and as a 128-bit decimal's words" : "column 0 is read as decimal words of precision "
          + "19 and of 38";
      assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
  }
}
