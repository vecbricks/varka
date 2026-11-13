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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import scala.jdk.javaapi.CollectionConverters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.InSet;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalValueSuite.Pair;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;

/**
 * Decimal {@code IN} and {@code InSet} within precision 18 (VARKA-348 2.3, part 348a4): the one
 * {@code IN} arm reads each element as its unscaled long at the value's scale, the value through
 * the decimal compiler. Each kernel runs over a batch with nulls and the same batch without, so
 * both bodies answer, and every row is held to Spark's own {@code eval} of the bound condition.
 */
@VarkaSuite
public final class VarkaDecimalInSuite {

  private static final long TOP18 = 999_999_999_999_999_999L;

  @Test
  @DisplayName("IN and NOT IN at every scale of precisions 9 and 18, the precision's ends, "
      + "negative values and one unit either side of each element, answer as Spark")
  void atEveryScale() {
    for (int p : new int[] {9, 18}) {
      long top = p == 18 ? TOP18 : 999_999_999L;
      for (int s : new int[] {0, 2, p / 2, p}) {
        var type = new DecimalType(p, s);
        List<Long> elements = List.of(top, -top, 0L, 7L, -125L);
        String list = elements.stream()
            .map(u -> BigDecimal.valueOf(u, s).toPlainString() + "BD")
            .collect(Collectors.joining(", "));
        var rows = new ArrayList<Pair>();
        for (long u : elements) {
          for (long d : new long[] {-1, 0, 1}) {
            if (Math.abs(u + d) <= top) {
              rows.add(new Pair(u + d, 0L));
            }
          }
        }
        for (String sql : List.of("a IN (" + list + ")", "a NOT IN (" + list + ")")) {
          check(sql, type, rows);
        }
      }
    }
  }

  @Test
  @DisplayName("a literal at a larger scale than the column: coercion's DECIMAL(10, 3) multiplies "
      + "the column by 10, and the optimizer's unwrapped form drops the literal no row equals")
  void literalAtALargerScale() {
    var type = new DecimalType(9, 2);
    var rows = List.of(new Pair(42L, 0L), new Pair(43L, 0L), new Pair(150L, 0L),
        new Pair(-150L, 0L), new Pair(225L, 0L), new Pair(0L, 0L));
    String sql = "a IN (0.425, 1.5, 2.25)";
    CompiledVarkaPredicate analyzed = check(sql, type, rows);
    assertEquals(List.of(10L, 425L, 1500L, 2250L),
        analyzed.fused().longLiterals().stream().sorted().toList(),
        () -> "the long table: " + analyzed.fused());
    // The optimizer unwraps the cast (UnwrapCastInBinaryComparison): 0.425 does not fit the
    // column's type, so no row equals it and it is dropped, and the rest are at its scale.
    List<Attribute> columns = columns(type);
    Expression optimized = VarkaSqlResolve.optimizeIn(VarkaSqlResolve.parse(sql, seq(columns)),
        seq(columns));
    assertEquals("(a IN (1.50BD, 2.25BD))", optimized.sql());
    check(optimized, sql + ", optimized to " + optimized.sql(), columns, type, rows);
  }

  @Test
  @DisplayName("past spark.sql.optimizer.inSetConversionThreshold the list is an InSet, which "
      + "fuses and answers as Spark, at scale 0 and at scale 9 of precision 18")
  void inSetPastTheThreshold() {
    for (int s : new int[] {0, 9}) {
      var type = new DecimalType(18, s);
      // Eleven literals, past the default threshold of ten.
      List<Long> elements = LongStream.rangeClosed(-5, 5).map(k -> k * 1_000_003L).boxed()
          .toList();
      String list = elements.stream()
          .map(u -> BigDecimal.valueOf(u, s).toPlainString() + "BD")
          .collect(Collectors.joining(", "));
      List<Attribute> columns = columns(type);
      Expression inSet = VarkaSqlResolve.optimizeIn(
          VarkaSqlResolve.parse("a IN (" + list + ")", seq(columns)), seq(columns));
      assertInstanceOf(InSet.class, inSet, () -> "the optimized list: " + inSet);
      var rows = new ArrayList<Pair>();
      for (long u : elements) {
        rows.add(new Pair(u, 0L));
        rows.add(new Pair(u + 1, 0L));
      }
      rows.add(new Pair(TOP18, 0L));
      rows.add(new Pair(-TOP18, 0L));
      check(inSet, inSet.sql(), columns, type, rows);
    }
  }

  @Test
  @DisplayName("the coverage table's InSet rewrite is the optimizer's, for an int and a decimal "
      + "row")
  void inSetRewriteIsTheOptimizers() {
    var cols = seq(List.of(VarkaSqlResolve.column("i", DataTypes.IntegerType),
        VarkaSqlResolve.column("d18", new DecimalType(18, 2))));
    for (String text : List.of(
        "i IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)",
        "d18 IN (0.42, 50000000.00, -50000000.00, 1.01, 2.02, 3.03, 4.04, 5.05, 6.06, 7.07, "
            + "8.08)")) {
      Expression parsed = VarkaSqlResolve.parse(text, cols);
      assertEquals(VarkaSqlResolve.optimizeIn(parsed, cols),
          VarkaSqlResolve.inSetAsClaimed(parsed, true), text);
      assertSame(parsed, VarkaSqlResolve.inSetAsClaimed(parsed, false), text);
    }
  }

  @Test
  @DisplayName("a row that claims an InSet and resolves to anything else is refused")
  void inSetClaimIsRefused() {
    var cols = seq(List.of(VarkaSqlResolve.column("i", DataTypes.IntegerType),
        VarkaSqlResolve.column("d18", new DecimalType(18, 2))));
    var comparison = assertThrows(IllegalArgumentException.class,
        () -> VarkaSqlResolve.inSetAsClaimed(VarkaSqlResolve.parse("d18 > 0.42", cols), true));
    assertTrue(comparison.getMessage().contains("resolved to GreaterThan"),
        comparison.getMessage());
    var mixed = assertThrows(IllegalArgumentException.class,
        () -> VarkaSqlResolve.inSetAsClaimed(VarkaSqlResolve.parse("i IN (1, i)", cols), true));
    assertTrue(mixed.getMessage().contains("resolved to In rather than an In of foldable"),
        mixed.getMessage());
  }

  @Test
  @DisplayName("an InSet's Decimal that no value of the type equals is dropped as false, and a "
      + "set left with none declines as an empty IN")
  void inSetDropsWhatNoValueEquals() {
    var type = new DecimalType(9, 2);
    List<Attribute> columns = columns(type);
    var a = columns.get(0);
    // 1.230 is 1.23 at scale 2; 1.234 has a third digit and 10^7 is past nine digits.
    Expression kept = new InSet(a, scalaSet(Decimal.apply("1.230"), Decimal.apply("1.234"),
        Decimal.apply("10000000.00")));
    var rows = List.of(new Pair(123L, 0L), new Pair(124L, 0L), new Pair(999_999_999L, 0L));
    CompiledVarkaPredicate compiled = check(kept, kept.sql(), columns, type, rows);
    assertEquals(List.of(123L), compiled.fused().longLiterals(),
        () -> "the long table: " + compiled.fused());
    Expression none = new InSet(a, scalaSet(Decimal.apply("1.234")));
    assertEquals("IN list is empty", decline(none, columns));
  }

  @Test
  @DisplayName("a null in the list declines, IN and NOT IN alike, as on every other lane")
  void nullInTheList() {
    List<Attribute> columns = columns(new DecimalType(9, 2));
    for (String sql : List.of("a IN (1.25, NULL)", "a NOT IN (NULL, 1.25)")) {
      assertEquals("IN list has a null or non-literal element",
          decline(VarkaSqlResolve.parse(sql, seq(columns)), columns), sql);
    }
  }

  @Test
  @DisplayName("a common type past 18 digits declines naming row 359, and 360 where the value is "
      + "rescaled into it")
  void wideCommonTypeDeclines() {
    List<Attribute> eighteen = columns(new DecimalType(18, 0));
    String rescaled = decline(VarkaSqlResolve.parse("a IN (0.5, 1)", seq(eighteen)), eighteen);
    assertTrue(rescaled.contains("359") && rescaled.contains("360"), rescaled);
    List<Attribute> twenty = columns(new DecimalType(20, 2));
    String wide = decline(VarkaSqlResolve.parse("a IN (0.50, 1.00)", seq(twenty)), twenty);
    assertTrue(wide.contains("359"), wide);
  }

  @Test
  @DisplayName("a decimal IN fuses beside a comparison in one predicate")
  void fusesBesideAComparison() {
    List<Attribute> columns = columns(new DecimalType(9, 2));
    VarkaDecimalValueSuite.compile("a IN (0.42, 1.25) AND a < b", columns);
    VarkaDecimalValueSuite.compile("a IN (0.42, 1.25) OR b IS NULL", columns);
  }

  private static scala.collection.immutable.Seq<Attribute> seq(List<Attribute> columns) {
    return VarkaDecimalValueSuite.seq(columns);
  }

  private static List<Attribute> columns(DecimalType type) {
    return List.of(VarkaSqlResolve.column("a", type), VarkaSqlResolve.column("b", type));
  }

  private static scala.collection.immutable.Set<Object> scalaSet(Object... values) {
    return CollectionConverters.asScala(new LinkedHashSet<>(List.of(values))).toSet();
  }

  private static CompiledVarkaPredicate check(String sql, DecimalType type, List<Pair> rows) {
    List<Attribute> columns = columns(type);
    Expression e = VarkaSqlResolve.parse(sql, seq(columns));
    return check(e, sql, columns, type, rows);
  }

  /**
   * Compiles {@code e}, runs it over {@code rows} and the same rows with a null added, the
   * masked body and the dense one, and holds each row to Spark's answer.
   */
  private static CompiledVarkaPredicate check(Expression e, String what, List<Attribute> columns,
      DecimalType type, List<Pair> rows) {
    var compiled = VarkaExpressionCompiler.compilePredicate(e, seq(columns), VarkaMatrixRuns.BASE,
            VarkaAdmission.NO_HOST_REFUSAL)
        .orElseThrow(() -> new AssertionError(what + " does not fuse: " + decline(e, columns)));
    assertTrue(compiled.residualConjuncts().isEmpty(), () -> what + " leaves a residual");
    Expression bound = VarkaSqlResolve.bind(e, seq(columns));
    var withNull = new ArrayList<>(rows);
    withNull.add(new Pair(null, 0L));
    for (List<Pair> batch : List.of(rows, withNull)) {
      long[] got = VarkaDecimalValueSuite.answers(compiled, batch, type, type);
      for (int i = 0; i < batch.size(); i++) {
        Pair r = batch.get(i);
        var row = new GenericInternalRow(new Object[] {
            VarkaDecimalValueSuite.decimal(r.a(), type),
            VarkaDecimalValueSuite.decimal(r.b(), type)});
        long want = Boolean.TRUE.equals(bound.eval(row)) ? 1L : 0L;
        assertEquals(want, got[i], () -> what + " over " + type + ", row " + r);
      }
    }
    return compiled;
  }

  private static String decline(Expression e, List<Attribute> columns) {
    List<VarkaConjunctSpec> specs = VarkaExpressionCompiler.explainPredicate(e, seq(columns),
        VarkaMatrixRuns.BASE, VarkaAdmission.NO_HOST_REFUSAL);
    assertEquals(1, specs.size(), () -> "the specs: " + specs);
    return specs.get(0).decline().map(d -> d.reason()).orElse("fused");
  }
}
