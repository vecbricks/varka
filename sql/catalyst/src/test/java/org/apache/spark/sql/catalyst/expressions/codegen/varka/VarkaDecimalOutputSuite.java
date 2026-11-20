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
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalValueSuite.seq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
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
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenDecimal;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.util.ArrowUtils;

/**
 * A decimal value within 18 digits as a projection's output (VARKA-348 2.4, part 348b): every
 * form {@code VarkaDecimalCompiler.value} builds is written by the decimal store, so the output
 * root is {@code WidenDecimal} over the value a comparison would read. The store's bytes are
 * {@link VarkaDecimalStoreSuite}'s; here the value under it is run as a long output, at one, two,
 * four and eight long lanes, over the precision's ends, values one unit inside them, zero, one
 * each way and every null pattern of two operands, and held row by row to Spark's own
 * {@code eval} of the same expression at the output's scale. In a JVM of its own, for the several
 * species (VARKA-246).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDecimalOutputSuite {

  private static final int[] WIDTHS = {1, 2, 4, 8};
  private static final DecimalType D9 = new DecimalType(9, 2);
  private static final DecimalType D9S4 = new DecimalType(9, 4);
  private static final DecimalType D18 = new DecimalType(18, 2);

  /** A row of the two columns: each one's unscaled value, or null. */
  private record Row(Long a, Long b) { }

  /** A form: its SQL over {@code a} and {@code b}, and the two columns' types. */
  private record Shape(String sql, DecimalType ta, DecimalType tb) { }

  /** The plan's projections (VARKA-348 3 item 4) and the choices and picks beside them. */
  private static final List<Shape> SHAPES = List.of(
      new Shape("CASE WHEN a < b THEN a ELSE b END", D9, D9),
      new Shape("CASE WHEN a < b THEN a END", D9, D9),
      new Shape("if(a > b, a, b)", D18, D18),
      new Shape("coalesce(a, b)", D9, D9S4),
      new Shape("coalesce(a, 1.5)", D18, D18),
      new Shape("greatest(a, b)", D18, D18),
      new Shape("least(a, b)", D9, D9S4),
      new Shape("nullif(a, b)", D9, D9),
      new Shape("-a", D18, D18),
      new Shape("abs(a)", D18, D18),
      new Shape("abs(b)", D9, D9S4),
      new Shape("-greatest(a, b)", D9, D9S4));

  /** The sums and differences within 18 digits, row 349's arithmetic stored by the same path. */
  private static final List<Shape> ARITHMETIC = List.of(
      new Shape("a + b", D9, D9S4),
      new Shape("a - b", D9, D9),
      new Shape("try_subtract(a, b)", D9, D9S4));

  private static long bound(DecimalType t) {
    return Decimal.POW_10()[t.precision()] - 1L;
  }

  /** The precision's ends, one unit inside them, zero and one each way. */
  private static List<Long> edges(DecimalType t) {
    long top = bound(t);
    return List.copyOf(new LinkedHashSet<>(List.of(0L, 1L, -1L, top, -top, top - 1, -top + 1)));
  }

  /** Every ordered pair of the two types' edges, then every null pattern beside a few. */
  private static List<Row> rows(DecimalType ta, DecimalType tb) {
    var rows = new ArrayList<Row>();
    for (long a : edges(ta)) {
      for (long b : edges(tb)) {
        rows.add(new Row(a, b));
      }
    }
    for (long v : List.of(0L, 1L, -1L)) {
      rows.add(new Row(null, v));
      rows.add(new Row(v, null));
    }
    rows.add(new Row(null, null));
    return rows;
  }

  @Test
  @DisplayName("each decimal value is a projection's output through the decimal store, at the "
      + "expression's type, and answers as Spark at every width")
  void valuesAnswerAsSpark() {
    for (Shape s : SHAPES) {
      check(s, VarkaMatrixRuns.BASE);
    }
  }

  @Test
  @DisplayName("abs as an output answers as Spark under both lowerings, the blend and the "
      + "lanewise ABS of decimalAbsLanewise")
  void absUnderBothLowerings() {
    VarkaEmitOptions lanewise = VarkaMatrixRuns.BASE.toBuilder().decimalAbsLanewise(true).build();
    for (Shape s : List.of(new Shape("abs(a)", D18, D18), new Shape("abs(b)", D9, D9S4),
        new Shape("abs(-a)", D9, D9))) {
      check(s, VarkaMatrixRuns.BASE);
      check(s, lanewise);
    }
  }

  @Test
  @DisplayName("a sum or difference within 18 digits is an output by the same path, its rescale "
      + "inside")
  void arithmeticAnswersAsSpark() {
    for (Shape s : ARITHMETIC) {
      check(s, VarkaMatrixRuns.BASE);
    }
  }

  @Test
  @DisplayName("an output whose type passes 18 digits declines naming row 359, before its value "
      + "is compiled")
  void wideOutputDeclines() {
    var wide = new DecimalType(18, 0);
    for (Shape s : List.of(new Shape("coalesce(a, b)", wide, D18),
        new Shape("a + b", D18, D18), new Shape("if(a < b, a, b)", wide, D9S4))) {
      List<Attribute> columns = columns(s);
      Expression e = VarkaSqlResolve.parse(s.sql(), seq(columns));
      assertTrue(((DecimalType) e.dataType()).precision() > 18, () -> s + ": " + e.dataType());
      String reason = decline(e, columns);
      assertTrue(reason.contains("359"), () -> s + ": " + reason);
    }
  }

  private static List<Attribute> columns(Shape s) {
    return List.of(VarkaSqlResolve.column("a", s.ta()), VarkaSqlResolve.column("b", s.tb()));
  }

  private static scala.collection.immutable.Seq<NamedExpression> out(Expression e) {
    return scala.jdk.javaapi.CollectionConverters.asScala(
        List.of(VarkaSqlResolve.named(e, "c"))).toSeq();
  }

  private static String decline(Expression e, List<Attribute> columns) {
    var declines = VarkaExpressionCompiler.declines(out(e), seq(columns), VarkaMatrixRuns.BASE);
    return declines.isEmpty() ? "fused" : declines.get(declines.firstKey()).reason();
  }

  /**
   * Compiles {@code s} as a projection's one output under {@code options}, checks its root is
   * the store over a long value at the expression's type, runs that value at every width over
   * {@link #rows}, and holds each row to Spark's evaluation, {@code nullif} replaced as it has no
   * evaluation of its own.
   */
  private static void check(Shape s, VarkaEmitOptions options) {
    List<Attribute> columns = columns(s);
    Expression e = VarkaSqlResolve.parse(s.sql(), seq(columns));
    var type = (DecimalType) e.dataType();
    CompiledVarkaProjection compiled = VarkaExpressionCompiler.compile(out(e), seq(columns),
        options).orElseThrow(() -> new AssertionError(s + " does not fuse: "
            + decline(e, columns)));
    assertEquals(List.of(type), compiled.outputTypes(), () -> s.toString());
    assertTrue(compiled.literals().isEmpty(), () -> s + " holds int literals: " + compiled);
    WidenDecimal root = assertInstanceOf(WidenDecimal.class, compiled.outputs().get(0),
        () -> s.toString());
    Expression bound = VarkaSqlResolve.bind(VarkaSqlResolve.replaced(e), seq(columns));
    List<Row> rows = rows(s.ta(), s.tb());
    for (int lanes : WIDTHS) {
      Long[] got = run(compiled, root.child(), rows, s, options.withLanesOverride(lanes));
      for (int i = 0; i < rows.size(); i++) {
        Row r = rows.get(i);
        Object spark = bound.eval(new GenericInternalRow(new Object[] {decimal(r.a(), s.ta()),
            decimal(r.b(), s.tb())}));
        Long want = spark == null ? null : ((Decimal) spark).toJavaBigDecimal()
            .setScale(type.scale()).unscaledValue().longValueExact();
        int row = i;
        assertEquals(want, got[i], () -> s + " under " + options.canonical() + " at "
            + label(lanes) + ", row " + r + " at " + row);
      }
    }
  }

  private static Decimal decimal(Long unscaled, DecimalType t) {
    return unscaled == null ? null
        : Decimal.apply(BigDecimal.valueOf(unscaled, t.scale()), t.precision(), t.scale());
  }

  /**
   * Runs {@code value}, the long under the store, as the kernel's one long output over the
   * columns {@code a} and {@code b} holding {@code rows}, and returns each row's long, or null
   * where it is not valid.
   */
  private static Long[] run(CompiledVarkaProjection compiled, VarkaVectorIR value,
      List<Row> rows, Shape s, VarkaEmitOptions options) {
    long[] lits = new long[compiled.longLiterals().size()];
    for (int i = 0; i < lits.length; i++) {
      lits[i] = compiled.longLiterals().get(i);
    }
    List<Integer> ordinals = compiled.inputOrdinals();
    var emitted = VarkaEmitterFixtures.emit(List.of(value), ordinals.size(), lits.length,
        options);
    VarkaFusedKernel kernel = VarkaEmitterFixtures.load(emitted).kernel();
    int length = rows.size();
    int inputs = ordinals.size();
    try (BufferAllocator allocator =
        ArrowUtils.rootAllocator().newChildAllocator("varka-decimal-output", 0, Long.MAX_VALUE);
        DecimalVector va = vector("a", rows, true, s.ta(), allocator);
        DecimalVector vb = vector("b", rows, false, s.tb(), allocator);
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

  private static DecimalVector vector(String name, List<Row> rows, boolean left,
      DecimalType type, BufferAllocator allocator) {
    var v = new DecimalVector(name, allocator, type.precision(), type.scale());
    v.allocateNew(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      Long u = left ? rows.get(i).a() : rows.get(i).b();
      if (u == null) {
        v.setNull(i);
      } else {
        v.getDataBuffer().setLong(i * 16L, u);
        v.getDataBuffer().setLong(i * 16L + 8, u >> 63);
        v.setIndexDefined(i);
      }
    }
    v.setValueCount(rows.size());
    return v;
  }
}
