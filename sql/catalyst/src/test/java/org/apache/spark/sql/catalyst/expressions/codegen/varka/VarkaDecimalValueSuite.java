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
import java.util.Optional;
import java.util.Set;

import scala.jdk.javaapi.CollectionConverters;

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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.types.Decimal;
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
  record Pair(Long a, Long b) { }

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
    // In the analyzed form coercion compares at DECIMAL(10, 3): the column is multiplied by 10,
    // and the literal's unscaled value at scale 3 is its slot. The optimizer would rewrite this
    // tree (UnwrapCastInBinaryComparison); the analyzed tree is what this test compiles.
    assertEquals(List.of(10L, 425L), compiled.fused().longLiterals().stream().sorted().toList(),
        () -> "the long table: " + compiled.fused());
  }

  @Test
  @DisplayName("a literal with more integral digits than the column multiplies the column by 100 "
      + "and keeps its own unscaled value as its slot")
  void literalKeptAtItsOwnScale() {
    Attribute a = VarkaSqlResolve.column("a", new DecimalType(9, 2));
    CompiledVarkaPredicate compiled = compile("a < 10000000.0125", List.of(a));
    // Coercion compares at DECIMAL(12, 4): the column times 10^2, the literal 100000000125. The
    // optimizer keeps this tree too, since the literal does not fit the column's type, and over a
    // column below 10^7 the comparison is true either way, so only this table pins the factor.
    assertEquals(List.of(100L, 100_000_000_125L),
        compiled.fused().longLiterals().stream().sorted().toList(),
        () -> "the long table: " + compiled.fused());
  }

  @Test
  @DisplayName("p + k = 19 declines at compile time naming rows 359 and 360, whichever side "
      + "carries the rescale")
  void nineteenDigitsDecline() {
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(18, 0)),
        VarkaSqlResolve.column("b", new DecimalType(18, 1)));
    for (String sql : List.of("a < b", "b < a")) {
      Expression e = VarkaSqlResolve.parse(sql, seq(columns));
      List<VarkaConjunctSpec> specs =
          VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE,
              VarkaAdmission.NO_HOST_REFUSAL);
      assertEquals(1, specs.size());
      String reason = specs.get(0).decline().map(d -> d.reason()).orElse("fused");
      assertTrue(reason.contains("359") && reason.contains("360"),
          () -> sql + " declined: " + reason);
    }
  }

  @Test
  @DisplayName("a choice of a wide type whose branch is rescaled up declines naming rows 359 and "
      + "360, whichever side of the comparison it is on, and under a negation or abs")
  void wideChoiceOfARescaleDeclines() {
    // The branches' common type is DECIMAL(20, 4): coercion casts a up by two digits of scale.
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(18, 2)),
        VarkaSqlResolve.column("b", new DecimalType(9, 4)),
        VarkaSqlResolve.column("c", new DecimalType(9, 4)));
    for (String choice : List.of("IF(c > 0.0, a, b)", "CASE WHEN c > 0.0 THEN a ELSE b END",
        "coalesce(a, b)", "greatest(a, b)", "least(b, a)", "-IF(c > 0.0, a, b)",
        "abs(coalesce(a, b))", "-abs(greatest(a, b))")) {
      for (String sql : List.of(choice + " < c", "c < " + choice)) {
        Expression e = VarkaSqlResolve.parse(sql, seq(columns));
        List<VarkaConjunctSpec> specs =
            VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE,
                VarkaAdmission.NO_HOST_REFUSAL);
        assertEquals(1, specs.size());
        String reason = specs.get(0).decline().map(d -> d.reason()).orElse("fused");
        assertTrue(reason.contains("359") && reason.contains("360") && !reason.contains("350"),
            () -> sql + " declined: " + reason);
      }
    }
  }

  @Test
  @DisplayName("a cast that lowers the scale of a wide choice whose branch is rescaled up declines "
      + "naming rows 350, 359 and 360, alone or under coercion's rescale")
  void loweringCastOfAWideRescaleDeclines() {
    // IF(c > 0.0, a, b) is DECIMAL(20, 4), its a branch rescaled up; the cast to DECIMAL(20, 2)
    // lowers that by two digits of scale. Against d it is the side as written; against c
    // coercion casts it up again to DECIMAL(22, 4).
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(18, 2)),
        VarkaSqlResolve.column("b", new DecimalType(9, 4)),
        VarkaSqlResolve.column("c", new DecimalType(9, 4)),
        VarkaSqlResolve.column("d", new DecimalType(9, 2)));
    String lowered = "CAST(IF(c > 0.0, a, b) AS DECIMAL(20, 2))";
    for (String sql : List.of(lowered + " < d", "d < " + lowered, lowered + " < c",
        "-" + lowered + " < d")) {
      Expression e = VarkaSqlResolve.parse(sql, seq(columns));
      List<VarkaConjunctSpec> specs =
          VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE,
              VarkaAdmission.NO_HOST_REFUSAL);
      assertEquals(1, specs.size());
      String reason = specs.get(0).decline().map(d -> d.reason()).orElse("fused");
      assertTrue(reason.contains("350") && reason.contains("359") && reason.contains("360"),
          () -> sql + " declined: " + reason);
    }
  }

  @Test
  @DisplayName("a null test through a cast that only widens the precision past 18 digits fuses "
      + "and answers as Spark, on nulls and values at the precision's bounds")
  void nullTestThroughAWideningCast() {
    long top18 = 999_999_999_999_999_999L;
    long top9 = 999_999_999L;
    record Shape(DecimalType type, String cast, long top) { }
    for (Shape s : List.of(new Shape(new DecimalType(18, 0), "CAST(a AS DECIMAL(20, 0))", top18),
        new Shape(new DecimalType(9, 2), "CAST(a AS DECIMAL(38, 2))", top9),
        new Shape(new DecimalType(9, 2), "CAST(CAST(a AS DECIMAL(20, 2)) AS DECIMAL(38, 2))",
            top9))) {
      List<Attribute> columns =
          List.of(VarkaSqlResolve.column("a", s.type()), VarkaSqlResolve.column("b", s.type()));
      var rows = List.of(new Pair(s.top(), 0L), new Pair(-s.top(), 0L), new Pair(0L, 0L),
          new Pair(null, 0L), new Pair(1L, null), new Pair(null, null));
      for (String sql : List.of(s.cast() + " IS NULL", s.cast() + " IS NOT NULL")) {
        long[] got = answers(compile(sql, columns), rows, s.type(), s.type());
        Expression bound = VarkaSqlResolve.bind(VarkaSqlResolve.parse(sql, seq(columns)),
            seq(columns));
        for (int i = 0; i < rows.size(); i++) {
          Pair r = rows.get(i);
          var row = new GenericInternalRow(new Object[] {decimal(r.a(), s.type()),
              decimal(r.b(), s.type())});
          long want = Boolean.TRUE.equals(bound.eval(row)) ? 1L : 0L;
          assertEquals(want, got[i], () -> sql + " over " + s.type() + ", row " + r);
        }
      }
    }
  }

  @Test
  @DisplayName("the null family over decimals of two scales answers as Spark's eval, the null of "
      + "nullif, nullifzero and a missing ELSE the long lane's")
  void nullFamilyAnswersAsSpark() {
    DecimalType ta = new DecimalType(9, 2);
    DecimalType tb = new DecimalType(9, 4);
    List<Attribute> columns =
        List.of(VarkaSqlResolve.column("a", ta), VarkaSqlResolve.column("b", tb));
    long top = 999_999_999L;
    // Equal after the rescale (0.10 and 0.1000), one unit apart, zeros, the bounds and nulls.
    var rows = List.of(new Pair(10L, 1000L), new Pair(10L, 1001L), new Pair(0L, 0L),
        new Pair(0L, 1L), new Pair(top, top), new Pair(-top, -top), new Pair(top, 0L),
        new Pair(null, 0L), new Pair(0L, null), new Pair(null, null), new Pair(-10L, -1000L));
    for (String sql : List.of("nullif(a, b) IS NULL", "nullif(a, b) <=> a", "nullif(b, a) < 0.05",
        "nullifzero(a) IS NOT NULL", "nullifzero(b) <=> b", "CASE WHEN a < b THEN a END <=> b",
        "CASE WHEN a < b THEN b WHEN a > b THEN a END IS NULL", "if(a IS NULL, b, NULL) = b",
        "zeroifnull(a) = 0.00", "nvl(a, b) <=> b", "nvl2(a, b, a) > 0.0", "a BETWEEN b AND 1.00",
        "equal_null(a, b)", "greatest(a, NULL, b) <=> b")) {
      long[] got = answers(compile(sql, columns), rows, ta, tb);
      Expression bound = VarkaSqlResolve.bind(
          VarkaSqlResolve.replaced(VarkaSqlResolve.parse(sql, seq(columns))), seq(columns));
      for (int i = 0; i < rows.size(); i++) {
        Pair r = rows.get(i);
        var row = new GenericInternalRow(new Object[] {decimal(r.a(), ta), decimal(r.b(), tb)});
        long want = Boolean.TRUE.equals(bound.eval(row)) ? 1L : 0L;
        assertEquals(want, got[i], () -> sql + ", row " + r);
      }
    }
  }

  @Test
  @DisplayName("the null family over a decimal past 18 digits fuses under a null test, the null "
      + "holding no words, and as a compared value declines naming row 359, or 360 for a rescale")
  void wideNullFamilyFusesOrDeclines() {
    // c and d are DECIMAL(20, 2), row 359's pair; nullif(a, e) compares at DECIMAL(25, 9), and
    // zeroifnull(e) coalesces e with the int zero's DECIMAL(10, 0) at DECIMAL(19, 9).
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(18, 2)),
        VarkaSqlResolve.column("c", new DecimalType(20, 2)),
        VarkaSqlResolve.column("d", new DecimalType(20, 2)),
        VarkaSqlResolve.column("e", new DecimalType(16, 9)));
    // A null test reads a wide choice's validity alone, where the NULL of nullif and nullifzero
    // and a missing ELSE are never valid, so those fuse beside the comparisons and <=> of row
    // 359's pair (VarkaDecimalPairSuite holds their answers to Spark's). A wide choice or null
    // as a compared value is the pair's value half, row 359's part 359b, and a rescale into the
    // wide type is row 360's.
    String fused = "fused";
    String wideValue = "a decimal wider than 18 digits is row 359's";
    record Want(String sql, String reason) { }
    for (Want w : List.of(
        new Want("nullif(c, d) IS NULL", fused),
        new Want("CASE WHEN c < d THEN c END IS NULL", fused),
        new Want("nullifzero(c) IS NULL", fused),
        new Want("equal_null(c, d)", fused),
        new Want("c BETWEEN d AND 1.00", fused),
        new Want("zeroifnull(e) IS NULL", fused),
        new Want("nvl(c, d) > 0.00", wideValue),
        new Want("zeroifnull(c) = d", wideValue),
        new Want("nullif(c, d) <=> c", wideValue),
        new Want("nullif(c, d) IN (c, d)", wideValue),
        new Want("c < CAST(NULL AS DECIMAL(20, 2))",
            "a null decimal constant wider than 18 digits as a value is row 359's"),
        new Want("nullif(a, e) IS NULL", "rows 359 and 360's"))) {
      String sql = w.sql();
      String want = w.reason();
      Expression e = VarkaSqlResolve.parse(sql, seq(columns));
      List<VarkaConjunctSpec> specs =
          VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE,
              VarkaAdmission.NO_HOST_REFUSAL);
      String reason = specs.stream().flatMap(s -> s.decline().stream()).map(d -> d.reason())
          .findFirst().orElse(fused);
      assertTrue(want.equals(fused) ? reason.equals(fused) : reason.contains(want),
          () -> sql + ": want " + want + ", got " + reason);
    }
  }

  static Decimal decimal(Long unscaled, DecimalType type) {
    return unscaled == null ? null : Decimal.apply(unscaled, type.precision(), type.scale());
  }

  @Test
  @DisplayName("a null test over greatest records the int arithmetic family its pick belongs to, "
      + "and declines under that family's switch")
  void nullTestOverAPickKeepsItsFamily() {
    // VarkaFamilySwitchSuite's rule: a switch declines every shape holding its family's node,
    // and the record lists each. The null test's probe of the value notes the pick's family, and
    // the probe's rollback keeps that note.
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(9, 2)),
        VarkaSqlResolve.column("b", new DecimalType(9, 2)));
    Expression e = VarkaSqlResolve.parse("greatest(a, b) IS NULL", seq(columns));
    var on = VarkaExpressionCompiler.explainAndCompilePredicate(e, seq(columns),
        VarkaMatrixRuns.BASE, VarkaAdmission.NO_HOST_REFUSAL).compiled();
    assertTrue(on.isPresent(), "greatest(a, b) IS NULL fuses");
    assertTrue(on.get().fused().families().contains(VarkaFamily.INT_ARITHMETIC),
        () -> "the families: " + on.get().fused().families());
    List<VarkaConjunctSpec> off = VarkaExpressionCompiler.explainPredicate(e, seq(columns),
        VarkaMatrixRuns.BASE,
        new VarkaAdmission(Set.of(VarkaFamily.INT_ARITHMETIC), false));
    assertEquals(1, off.size());
    assertEquals(Optional.of(VarkaFamily.INT_ARITHMETIC.offReason()),
        off.get(0).decline().map(d -> d.reason()));
  }

  @Test
  @DisplayName("a null test, a CASE and a coalesce nested deep through their conditions compile "
      + "each condition once")
  void nestedConditionsCompileOnce() {
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(9, 2)),
        VarkaSqlResolve.column("b", new DecimalType(9, 2)),
        VarkaSqlResolve.column("c", new DecimalType(9, 2)));
    // Each level's condition reads the level below, so a compile that compiles a condition twice
    // per level, once for the value and once for its validity, makes 2^depth compiles: counted,
    // not timed. The coalesce's IR shares each condition between a value and its validity, which
    // planning pays once per node since each IR node caches its hash (VARKA-559).
    String nullTest = "a";
    String caseWhen = "a";
    String coalesce = "a";
    for (int i = 0; i < 20; i++) {
      nullTest = "IF((" + nullTest + ") IS NOT NULL, b, c)";
      caseWhen = "CASE WHEN (" + caseWhen + ") IS NULL THEN a WHEN b > c THEN b ELSE c END";
      coalesce = "coalesce(IF((" + coalesce + ") > b, a, c), b)";
    }
    // Each with its conditions compiled once: one a level, and the CASE's two.
    List<String> sqls = List.of("(" + nullTest + ") IS NULL", "(" + caseWhen + ") IS NOT NULL",
        "(" + coalesce + ") > c");
    List<Integer> conditions = List.of(20, 40, 20);
    for (int s = 0; s < sqls.size(); s++) {
      String sql = sqls.get(s);
      int expected = conditions.get(s);
      Expression e = VarkaSqlResolve.parse(sql, seq(columns));
      assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
        VarkaExpressionCompiler.decimalConditionsCompiledForTesting();
        List<VarkaConjunctSpec> specs =
            VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE,
                VarkaAdmission.NO_HOST_REFUSAL);
        assertEquals(expected, VarkaExpressionCompiler.decimalConditionsCompiledForTesting(),
            () -> "conditions compiled in " + sql.substring(0, 40));
        assertEquals(1, specs.size());
        // The levels are past the emitter's fused budget, a decline made after the compile.
        String reason = specs.get(0).decline().map(d -> d.reason()).orElse("");
        assertTrue(reason.isEmpty() || reason.startsWith("exceeds the emitter's fused budget"),
            () -> "declined in the compile: " + reason);
      }, () -> "compiling " + sql.substring(0, 40) + " took over 20 s");
    }
  }

  static scala.collection.immutable.Seq<Attribute> seq(List<Attribute> columns) {
    return CollectionConverters.asScala(columns).toSeq();
  }

  static CompiledVarkaPredicate compile(String sql, List<Attribute> columns) {
    Expression e = VarkaSqlResolve.parse(sql, seq(columns));
    var compiled = VarkaExpressionCompiler.compilePredicate(e, seq(columns), VarkaMatrixRuns.BASE,
            VarkaAdmission.NO_HOST_REFUSAL)
        .orElseThrow(() -> new AssertionError(sql + " does not fuse: "
            + VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE,
                VarkaAdmission.NO_HOST_REFUSAL)));
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
    long[] got = answers(compiled, rows, ta, tb);
    for (int i = 0; i < rows.size(); i++) {
      Pair r = rows.get(i);
      long want = selects(op, r, ta.scale(), tb.scale()) ? 1L : 0L;
      int row = i;
      assertEquals(want, got[i], () -> sql + " over " + ta + ", " + tb + ", row " + r
          + " at " + row);
    }
  }

  /**
   * Runs the one condition of {@code compiled} as a kernel over the columns {@code a} of type
   * {@code ta} and {@code b} of type {@code tb} holding {@code rows}, and returns per row 1 where
   * it is true and 0 otherwise.
   */
  static long[] answers(CompiledVarkaPredicate compiled, List<Pair> rows,
      DecimalType ta, DecimalType tb) {
    var fused = compiled.fused();
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
    return run(kernel, fused.inputOrdinals(), rows, ta, tb, lits);
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
