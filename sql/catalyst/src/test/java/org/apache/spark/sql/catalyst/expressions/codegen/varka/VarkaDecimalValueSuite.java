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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.util.ArrowUtils;

/**
 * The decimal values of {@code VarkaDecimalCompiler} compiled from SQL, run as kernels, and held
 * to {@link BigDecimal} (VARKA-348 3 item 2): the rescale at {@code p + k = 18} at its extremes,
 * operands equal after the rescale and one unit apart, and the common type of 19 digits, which
 * declines naming rows 359 and 360.
 */
@VarkaSuite
public final class VarkaDecimalValueSuite {

  private static final String[] OPS = {"<", "<=", ">", ">=", "=", "<>", "<=>"};

  /** One row of two operands' unscaled values, {@code null} for a null. */
  private record Pair(Long a, Long b) { }

  @Test
  @DisplayName("p + k = 18: DECIMAL(9, 0) against DECIMAL(18, 9) rescales by 10^9 and compares as "
      + "BigDecimal does, at both ends of the precision and one unit either side of equal")
  void rescaleAtEighteenDigits() {
    long a = 999_999_999L;
    long scaled = a * 1_000_000_000L;
    long top = 999_999_999_999_999_999L;
    var rows = new ArrayList<Pair>();
    for (long s : new long[] {1, -1}) {
      rows.add(new Pair(s * a, s * scaled));
      rows.add(new Pair(s * a, s * scaled - 1));
      rows.add(new Pair(s * a, s * scaled + 1));
      rows.add(new Pair(s * a, s * top));
      rows.add(new Pair(s * a, -s * top));
      rows.add(new Pair(0L, s * top));
    }
    rows.add(new Pair(null, 0L));
    rows.add(new Pair(0L, null));
    rows.add(new Pair(null, null));
    for (String op : OPS) {
      check(new DecimalType(9, 0), new DecimalType(18, 9), op, rows, 1_000_000_000L);
    }
  }

  @Test
  @DisplayName("0.10 equals 0.100 and is below 0.101 after the rescale, in both operand orders")
  void equalAfterTheRescale() {
    var rows = List.of(new Pair(10L, 100L), new Pair(10L, 101L), new Pair(10L, 99L),
        new Pair(-10L, -100L), new Pair(-10L, -101L));
    for (String op : OPS) {
      check(new DecimalType(3, 2), new DecimalType(4, 3), op, rows, 10L);
      var swapped = rows.stream().map(r -> new Pair(r.b(), r.a())).toList();
      check(new DecimalType(4, 3), new DecimalType(3, 2), op, swapped, 10L);
    }
  }

  @Test
  @DisplayName("a literal at a larger scale takes its slot already rescaled, with no multiply")
  void literalAtTheLargerScale() {
    Attribute a = VarkaSqlResolve.column("a", new DecimalType(9, 2));
    CompiledVarkaPredicate compiled = compile("a = 0.425", List.of(a));
    // Coercion compares at DECIMAL(10, 3): the column is multiplied by 10, and the literal's
    // unscaled value at scale 3 is its slot.
    assertEquals(List.of(10L, 425L), compiled.fused().longLiterals().stream().sorted().toList(),
        () -> "the long table: " + compiled.fused());
  }

  @Test
  @DisplayName("p + k = 19 declines at compile time naming rows 359 and 360")
  void nineteenDigitsDecline() {
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(18, 0)),
        VarkaSqlResolve.column("b", new DecimalType(18, 1)));
    Expression e = VarkaSqlResolve.parse("a < b", seq(columns));
    List<VarkaConjunctSpec> specs =
        VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE);
    assertEquals(1, specs.size());
    String reason = specs.get(0).decline().map(d -> d.reason()).orElse("fused");
    assertTrue(reason.contains("359") && reason.contains("360"), () -> "declined: " + reason);
  }

  @Test
  @DisplayName("a null test, a CASE and a coalesce nested twenty deep through their conditions "
      + "compile in time linear in the depth, each condition compiled once")
  void nestedConditionsCompileOnce() {
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(9, 2)),
        VarkaSqlResolve.column("b", new DecimalType(9, 2)),
        VarkaSqlResolve.column("c", new DecimalType(9, 2)));
    int depth = 20;
    // Each level's condition reads the level below, so a compile that compiles a condition twice
    // per level, once for the value and once for its validity, takes 2^20 steps.
    String nullTest = "a";
    String caseWhen = "a";
    String coalesce = "a";
    for (int i = 0; i < depth; i++) {
      nullTest = "IF((" + nullTest + ") IS NOT NULL, b, c)";
      caseWhen = "CASE WHEN (" + caseWhen + ") IS NULL THEN a WHEN b > c THEN b ELSE c END";
      coalesce = "coalesce(IF((" + coalesce + ") > b, a, c), b)";
    }
    for (String sql : List.of("(" + nullTest + ") IS NULL", "(" + caseWhen + ") IS NOT NULL",
        "(" + coalesce + ") > c")) {
      Expression e = VarkaSqlResolve.parse(sql, seq(columns));
      assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
        List<VarkaConjunctSpec> specs =
            VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE);
        assertEquals(1, specs.size());
        // Twenty levels are past the emitter's fused budget, a decline made after the compile.
        String reason = specs.get(0).decline().map(d -> d.reason()).orElse("");
        assertTrue(reason.isEmpty() || reason.startsWith("exceeds the emitter's fused budget"),
            () -> "declined in the compile: " + reason);
      }, () -> "compiling " + depth + " levels of " + sql.substring(0, 40) + " took over 20 s");
    }
  }

  private static scala.collection.immutable.Seq<Attribute> seq(List<Attribute> columns) {
    return CollectionConverters.asScala(columns).toSeq();
  }

  private static CompiledVarkaPredicate compile(String sql, List<Attribute> columns) {
    Expression e = VarkaSqlResolve.parse(sql, seq(columns));
    var compiled = VarkaExpressionCompiler.compilePredicate(e, seq(columns), VarkaMatrixRuns.BASE)
        .orElseThrow(() -> new AssertionError(sql + " does not fuse: "
            + VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE)));
    assertTrue(compiled.residualConjuncts().isEmpty(), () -> sql + " leaves a residual");
    return compiled;
  }

  /**
   * Compiles {@code a op b} over columns of types {@code ta} and {@code tb}, checks that the long
   * table holds {@code factor}, the rescale's {@code 10^k}, and runs the kernel over {@code rows},
   * each row's selection held to {@link BigDecimal}'s comparison of the two values.
   */
  private static void check(DecimalType ta, DecimalType tb, String op, List<Pair> rows,
      long factor) {
    List<Attribute> columns =
        List.of(VarkaSqlResolve.column("a", ta), VarkaSqlResolve.column("b", tb));
    String sql = "a " + op + " b";
    CompiledVarkaPredicate compiled = compile(sql, columns);
    var fused = compiled.fused();
    assertTrue(fused.longLiterals().contains(factor),
        () -> sql + ": the rescale's factor " + factor + " is a slot: " + fused);
    assertEquals(1, fused.outputs().size());
    long[] lits = new long[fused.longLiterals().size() + 2];
    for (int i = 0; i < fused.longLiterals().size(); i++) {
      lits[i] = fused.longLiterals().get(i);
    }
    lits[lits.length - 2] = 1L;
    lits[lits.length - 1] = 0L;
    VarkaVectorIR root = new VarkaVectorIR.IfElse((VarkaVectorIR.Cond) fused.outputs().get(0),
        new LiteralSlot(lits.length - 2, LaneType.LONG),
        new LiteralSlot(lits.length - 1, LaneType.LONG));
    int inputs = fused.inputOrdinals().size();
    var emitted = VarkaEmitterFixtures.emit(List.of(root), inputs, lits.length,
        VarkaMatrixRuns.BASE);
    VarkaFusedKernel kernel = VarkaEmitterFixtures.load(emitted).kernel();
    long[] got = run(kernel, fused.inputOrdinals(), rows, ta, tb, lits);
    for (int i = 0; i < rows.size(); i++) {
      Pair r = rows.get(i);
      long want = selects(op, r, ta.scale(), tb.scale()) ? 1L : 0L;
      int row = i;
      assertEquals(want, got[i], () -> sql + " over " + ta + ", " + tb + ", row " + r
          + " at " + row);
    }
  }

  /** Whether {@code a op b} is true by {@link BigDecimal}, SQL's null rules applied. */
  private static boolean selects(String op, Pair r, int sa, int sb) {
    if (op.equals("<=>")) {
      return r.a() == null || r.b() == null ? r.a() == null && r.b() == null
          : BigDecimal.valueOf(r.a(), sa).compareTo(BigDecimal.valueOf(r.b(), sb)) == 0;
    }
    if (r.a() == null || r.b() == null) {
      return false;
    }
    int c = BigDecimal.valueOf(r.a(), sa).compareTo(BigDecimal.valueOf(r.b(), sb));
    return switch (op) {
      case "<" -> c < 0;
      case "<=" -> c <= 0;
      case ">" -> c > 0;
      case ">=" -> c >= 0;
      case "=" -> c == 0;
      case "<>" -> c != 0;
      default -> throw new IllegalArgumentException(op);
    };
  }

  /**
   * Runs {@code kernel} over the two columns as Arrow decimal vectors, input {@code i} being the
   * column at {@code ordinals.get(i)}, and returns its one long output per row.
   */
  private static long[] run(VarkaFusedKernel kernel, List<Integer> ordinals, List<Pair> rows,
      DecimalType ta, DecimalType tb, long[] lits) {
    int length = rows.size();
    int inputs = ordinals.size();
    try (BufferAllocator allocator =
        ArrowUtils.rootAllocator().newChildAllocator("varka-decimal-value", 0, Long.MAX_VALUE);
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
      long[] values = new long[length];
      for (int i = 0; i < length; i++) {
        values[i] = out.get(ValueLayout.JAVA_LONG, i * 8L);
      }
      return values;
    }
  }

  private static DecimalVector vector(String name, List<Pair> rows, boolean left,
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
