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
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalValueSuite.compile;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalValueSuite.seq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondValue;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.util.ArrowUtils;

/**
 * The comparisons and null tests of decimals wider than 18 digits (`m8/VARKA-359.md` 3 item 2),
 * compiled from SQL by the real compiler, each value read as the two words of its unscaled value
 * ({@code DecimalWords.compare}), and run over Arrow decimal vectors at the word edges: a low
 * word with its sign bit set under a high word of 0 (where a signed low compare is wrong), equal
 * high words with low words either side of {@code 2^63}, a high word of -1, a low word of 0 under
 * each high word, the precision's bounds, and every null pattern of two operands.
 *
 * <p>The oracle is Spark's own expression, evaluated over the same values, three-valued: the
 * kernel's condition is written as its one or zero, null where it is unknown. Every kernel runs
 * at two, four and eight long lanes and at the preferred species, so the edges meet the loop and
 * the epilogue. In a JVM of its own, for the several species (VARKA-246).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDecimalPairSuite {

  private static final int[] WIDTHS = {1, 2, 4, 8};
  private static final List<String> OPS = List.of("<", "<=", ">", ">=", "=", "<>", "<=>");
  private static final BigInteger TWO_63 = BigInteger.ONE.shiftLeft(63);
  private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

  /** A row of the two columns: each one's unscaled value, or null. */
  private record Row(BigInteger a, BigInteger b) { }

  private static BigInteger bound(DecimalType t) {
    return BigInteger.TEN.pow(t.precision()).subtract(BigInteger.ONE);
  }

  /** The word edges a value of {@code t} can hold, each at most {@code 10^p - 1} in magnitude. */
  private static List<BigInteger> edges(DecimalType t) {
    BigInteger one = BigInteger.ONE;
    var all = List.of(BigInteger.ZERO, one, one.negate(), TWO_63.subtract(one), TWO_63,
        TWO_63.add(one), TWO_63.negate(), TWO_63.negate().subtract(one), TWO_64.subtract(one),
        TWO_64, TWO_64.add(TWO_63).subtract(one), TWO_64.add(TWO_63), TWO_64.negate(),
        TWO_64.negate().subtract(one), TWO_64.negate().add(TWO_63), bound(t), bound(t).negate());
    var held = new LinkedHashSet<BigInteger>();
    for (BigInteger v : all) {
      if (v.abs().compareTo(bound(t)) <= 0) {
        held.add(v);
      }
    }
    return List.copyOf(held);
  }

  /** Every ordered pair of the two types' edges, then every null pattern beside a few. */
  private static List<Row> rows(DecimalType ta, DecimalType tb) {
    var rows = new ArrayList<Row>();
    for (BigInteger a : edges(ta)) {
      for (BigInteger b : edges(tb)) {
        rows.add(new Row(a, b));
      }
    }
    BigInteger both = bound(ta).min(bound(tb));
    for (BigInteger v : List.of(BigInteger.ZERO, both, both.negate())) {
      rows.add(new Row(null, v));
      rows.add(new Row(v, null));
    }
    rows.add(new Row(null, null));
    return rows;
  }

  private static DecimalVector vector(String name, List<Row> rows, boolean left,
      DecimalType type, BufferAllocator allocator) {
    var v = new DecimalVector(name, allocator, type.precision(), type.scale());
    v.allocateNew(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      BigInteger u = left ? rows.get(i).a() : rows.get(i).b();
      if (u == null) {
        v.setNull(i);
      } else {
        DecimalWords w = DecimalWords.of(u);
        v.getDataBuffer().setLong(i * 16L, w.lo());
        v.getDataBuffer().setLong(i * 16L + 8, w.hi());
        v.setIndexDefined(i);
      }
    }
    v.setValueCount(rows.size());
    return v;
  }

  private static Decimal decimal(BigInteger unscaled, DecimalType t) {
    return unscaled == null ? null
        : Decimal.apply(new BigDecimal(unscaled, t.scale()), t.precision(), t.scale());
  }

  /**
   * Runs {@code compiled}'s one condition as its one or zero over the columns {@code a} and
   * {@code b} at {@code lanes}, and returns per row 1, 0, or null where it is unknown.
   */
  private static Long[] answers(CompiledVarkaPredicate compiled, List<Row> rows, DecimalType ta,
      DecimalType tb, int lanes) {
    var fused = compiled.fused();
    assertEquals(1, fused.outputs().size());
    long[] lits = new long[fused.longLiterals().size()];
    for (int i = 0; i < lits.length; i++) {
      lits[i] = fused.longLiterals().get(i);
    }
    VarkaVectorIR root = new CondValue((Cond) fused.outputs().get(0), LaneType.LONG);
    List<Integer> ordinals = fused.inputOrdinals();
    var emitted = VarkaEmitterFixtures.emit(List.of(root), ordinals.size(), lits.length,
        VarkaMatrixRuns.BASE.withLanesOverride(lanes));
    VarkaFusedKernel kernel = VarkaEmitterFixtures.load(emitted).kernel();
    int length = rows.size();
    int inputs = ordinals.size();
    try (BufferAllocator allocator =
        ArrowUtils.rootAllocator().newChildAllocator("varka-decimal-pair", 0, Long.MAX_VALUE);
        DecimalVector va = vector("a", rows, true, ta, allocator);
        DecimalVector vb = vector("b", rows, false, tb, allocator);
        var arena = Arena.ofConfined()) {
      DecimalVector[] byOrdinal = {va, vb};
      long[] srcData = new long[inputs];
      long[] srcValidity = new long[inputs];
      int[] nullCounts = new int[inputs];
      long[] inStride = new long[inputs];
      for (int i = 0; i < inputs; i++) {
        DecimalVector v = byOrdinal[ordinals.get(i)];
        srcData[i] = v.getDataBuffer().memoryAddress();
        nullCounts[i] = v.getNullCount();
        srcValidity[i] = nullCounts[i] == length ? 0L : v.getValidityBuffer().memoryAddress();
        inStride[i] = 16L;
      }
      long wordBytes = ((length + 63) / 64) * 8L;
      MemorySegment out = arena.allocate(length * 8L, 64);
      MemorySegment outValid = arena.allocate(wordBytes, 64);
      long[] outData = {out.address()};
      long[] outValidity = {outValid.address()};
      long[] outStride = {8L};
      int status = VarkaKernelChecks.sanitized(kernel, length, inStride, outStride, srcData,
          srcValidity, outData, outValidity,
          scratch -> kernel.run(srcData, srcValidity, nullCounts, outData, outValidity,
              new int[0], lits, length, scratch));
      assertEquals(0, status, "an in-precision batch runs");
      var got = new Long[length];
      for (int i = 0; i < length; i++) {
        boolean valid = (outValid.get(ValueLayout.JAVA_LONG, (i >>> 6) * 8L) >>> (i & 63) & 1L)
            != 0;
        got[i] = valid ? out.get(ValueLayout.JAVA_LONG, i * 8L) : null;
      }
      return got;
    }
  }

  /**
   * Compiles {@code sql} over {@code a} of {@code ta} and {@code b} of {@code tb}, runs it over
   * {@link #rows} at every width, and holds each row's answer to Spark's evaluation of the same
   * expression, its {@code nullif} and {@code nullifzero} replaced, as they have no evaluation of
   * their own.
   */
  private static void check(String sql, DecimalType ta, DecimalType tb) {
    List<Attribute> columns =
        List.of(VarkaSqlResolve.column("a", ta), VarkaSqlResolve.column("b", tb));
    CompiledVarkaPredicate compiled = compile(sql, columns);
    Expression bound = VarkaSqlResolve.bind(
        VarkaSqlResolve.replaced(VarkaSqlResolve.parse(sql, seq(columns))), seq(columns));
    List<Row> rows = rows(ta, tb);
    for (int lanes : WIDTHS) {
      Long[] got = answers(compiled, rows, ta, tb, lanes);
      for (int i = 0; i < rows.size(); i++) {
        Row r = rows.get(i);
        Object spark = bound.eval(new GenericInternalRow(new Object[] {decimal(r.a(), ta),
            decimal(r.b(), tb)}));
        Long want = spark == null ? null : ((Boolean) spark ? 1L : 0L);
        int row = i;
        assertEquals(want, got[i], () -> sql + " over " + ta.simpleString() + ", "
            + tb.simpleString() + " " + label(lanes) + ", row " + row + ": " + r);
      }
    }
  }

  @Test
  @DisplayName("the comparisons of two wide columns answer as Spark on the word edges, at 19, "
      + "20 and 38 digits")
  void comparisonsOfTwoWideColumns() {
    for (int p : new int[] {19, 20, 38}) {
      var t = new DecimalType(p, 2);
      for (String op : OPS) {
        check("a " + op + " b", t, t);
      }
    }
  }

  @Test
  @DisplayName("a narrower side at one scale is widened into the pair: a 64-bit decimal by its "
      + "sign extension, a wide one by keeping its words")
  void widenedSides() {
    var wide = new DecimalType(38, 2);
    for (DecimalType narrow : List.of(new DecimalType(18, 2), new DecimalType(19, 2))) {
      for (String op : OPS) {
        check("a " + op + " b", narrow, wide);
        check("b " + op + " a", narrow, wide);
      }
    }
  }

  @Test
  @DisplayName("a literal wider than a long takes its two words in two slots of the long table, "
      + "and one of a smaller scale is folded at the column's")
  void wideLiterals() {
    var t = new DecimalType(38, 2);
    for (BigInteger u : List.of(TWO_64.add(TWO_63), TWO_63.negate().subtract(BigInteger.ONE),
        bound(t).negate())) {
      String literal = new BigDecimal(u, t.scale()).toPlainString();
      for (String op : OPS) {
        check("a " + op + " " + literal, t, t);
      }
      DecimalWords w = DecimalWords.of(u);
      List<Attribute> columns = List.of(VarkaSqlResolve.column("a", t));
      List<Long> slots = compile("a < " + literal, columns).fused().longLiterals();
      assertTrue(slots.contains(w.lo()) && slots.contains(w.hi()), () -> literal + ": " + slots);
    }
    // A literal of a smaller scale arrives under coercion's cast up, folded with it.
    for (String op : OPS) {
      check("a " + op + " 1.5", t, t);
    }
  }

  @Test
  @DisplayName("the null tests of a wide column, of a widening cast, a choice or a pick over one "
      + "read its validity, and a wide negation, abs, sum or lowering cast declines")
  void nullTests() {
    var t = new DecimalType(20, 2);
    for (String sql : List.of("a IS NULL", "a IS NOT NULL", "CAST(a AS DECIMAL(38, 2)) IS NULL",
        "b IS NOT NULL", "IF(a > 0, a, b) IS NULL", "coalesce(a, b) IS NULL",
        "greatest(a, b) IS NOT NULL", "least(b, a) IS NULL",
        "CASE WHEN a > 0 THEN b WHEN a < 0 THEN a ELSE b END IS NULL")) {
      check(sql, t, new DecimalType(38, 2));
    }
    // Each can raise, or null a row whose leaves are valid: a sum by overflowing, a lowering
    // cast likewise, and Spark's negation and abs of a 38-digit value, which round it to 34
    // digits through Scala's BigDecimal and can overflow the type (Decimal.unary_-).
    for (String sql : List.of("(a + b) IS NULL", "CAST(b AS DECIMAL(20, 2)) IS NULL",
        "-b IS NULL", "abs(b) IS NOT NULL")) {
      String reason = decline(sql, t, new DecimalType(38, 2));
      assertTrue(reason.contains("row"), () -> sql + ": " + reason);
    }
  }

  @Test
  @DisplayName("the null family's null tests over wide decimals answer as Spark: the NULL of "
      + "nullif and nullifzero and a missing ELSE are never valid, under a choice, a coalesce or "
      + "a pick")
  void nullFamilyNullTests() {
    // The null constant and the missing ELSE hold no words; their validity is false, and the
    // value's is the choice's condition picking its branches' (VARKA-578). The word edges make
    // nullif's a = b and the CASE's a < b turn on the high word and on the low word alone.
    var t = new DecimalType(20, 2);
    for (DecimalType tb : List.of(t, new DecimalType(38, 2))) {
      for (String sql : List.of("nullif(a, b) IS NULL", "nullif(b, a) IS NOT NULL",
          "nullifzero(a) IS NULL", "nullifzero(b) IS NOT NULL",
          "CASE WHEN a < b THEN a END IS NULL",
          "CASE WHEN a < b THEN b WHEN a > b THEN a END IS NOT NULL",
          "IF(a IS NULL, b, NULL) IS NULL", "nvl2(a, NULL, b) IS NOT NULL",
          "coalesce(nullif(a, b), b) IS NULL", "coalesce(a, NULL) IS NULL",
          "coalesce(CASE WHEN a > 0 THEN a END, nullif(b, a)) IS NOT NULL",
          "greatest(nullif(a, b), NULL) IS NULL", "least(a, NULL, b) IS NOT NULL")) {
        check(sql, t, tb);
      }
    }
  }

  @Test
  @DisplayName("a wide value cast down to 18 digits names the rows of the same cast to 38")
  void wideCastDownNamesTheWideRows() {
    var t = new DecimalType(38, 10);
    String cast = decline("CAST(a AS DECIMAL(18, 2)) > 0", t, t);
    assertEquals(decline("CAST(a AS DECIMAL(38, 2)) > 0", t, t), cast);
    assertTrue(cast.contains("rows 350, 359 and 363"), cast);
  }

  @Test
  @DisplayName("two wide scales decline naming the rescale's rows: up 360's, down 363's")
  void unequalScalesDecline() {
    record Case(DecimalType ta, DecimalType tb, String row) { }
    for (Case c : List.of(new Case(new DecimalType(20, 2), new DecimalType(20, 4), "360"),
        new Case(new DecimalType(38, 10), new DecimalType(38, 2), "363"))) {
      String reason = decline("a < b", c.ta(), c.tb());
      assertTrue(reason.contains(c.row()), () -> c + ": " + reason);
    }
  }

  /** The decline reason of the one conjunct {@code sql} over {@code a}, {@code b}, or "fused". */
  private static String decline(String sql, DecimalType ta, DecimalType tb) {
    List<Attribute> columns =
        List.of(VarkaSqlResolve.column("a", ta), VarkaSqlResolve.column("b", tb));
    Expression e = VarkaSqlResolve.parse(sql, seq(columns));
    List<VarkaConjunctSpec> specs = VarkaExpressionCompiler.explainPredicate(e, seq(columns),
        VarkaMatrixRuns.BASE, VarkaAdmission.NO_HOST_REFUSAL);
    assertEquals(1, specs.size(), sql);
    return specs.get(0).decline().map(d -> d.reason()).orElse("fused");
  }
}
