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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BinaryOperator;

import scala.Enumeration;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.EqualTo;
import org.apache.spark.sql.catalyst.expressions.EvalMode$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext$;
import org.apache.spark.sql.catalyst.expressions.RuntimeReplaceable;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.util.ArrowUtils;

/**
 * Decimal {@code +}, {@code -} and {@code *} on the long lane (VARKA-349 3 items 1 and 2): the
 * compiler's fuse-or-decline decision held to Spark's own result type over every pair of
 * operand types within 18 digits, and kernels run over the edges and a sample of every fused
 * class, each answer held to {@link BigDecimal}'s exact result.
 *
 * <p>A kernel is checked through the predicate {@code a op b = c}, {@code c} a column of the
 * result's type holding the exact result or one unit off it, so a row is selected exactly where
 * the kernel's value is Spark's.
 */
@VarkaSuite
public final class VarkaDecimalArithmeticSuite {

  private static final int MAX = VarkaVectorIR.DecimalColumn.MAX_LONG_PRECISION;

  /** One operator: its SQL, Spark's node over two operands, and its exact result. */
  private enum Op {
    ADD("+", BigDecimal::add),
    SUB("-", BigDecimal::subtract),
    MUL("*", BigDecimal::multiply);

    final String sql;
    final BinaryOperator<BigDecimal> exact;

    Op(String sql, BinaryOperator<BigDecimal> exact) {
      this.sql = sql;
      this.exact = exact;
    }

    Expression node(Expression a, Expression b, Enumeration.Value mode) {
      NumericEvalContext ctx = NumericEvalContext$.MODULE$.apply(mode, false);
      return switch (this) {
        case ADD -> new Add(a, b, ctx);
        case SUB -> new Subtract(a, b, ctx);
        case MUL -> new Multiply(a, b, ctx);
      };
    }

    /** The precision of the exact result before Spark caps it at 38. */
    int rawPrecision(DecimalType t1, DecimalType t2) {
      return this == MUL ? t1.precision() + t2.precision() + 1
          : Math.max(t1.precision() - t1.scale(), t2.precision() - t2.scale())
              + Math.max(t1.scale(), t2.scale()) + 1;
    }

    /** The row a result past 18 digits and within 38 declines naming. */
    String wideRow() {
      return this == MUL ? "row 361" : "row 360";
    }
  }

  /** One row of the three columns' unscaled values, {@code null} for a null. */
  private record Triple(Long a, Long b, Long c) { }

  @Test
  @DisplayName("every pair of operand types within 18 digits fuses exactly where Spark's result "
      + "type is within 18 digits, and otherwise declines naming the wider row")
  void typeRuleSweep() {
    var types = new ArrayList<DecimalType>();
    for (int p = 1; p <= MAX; p++) {
      for (int s = 0; s <= p; s++) {
        types.add(new DecimalType(p, s));
      }
    }
    int fused = 0;
    int declined = 0;
    var wrong = new ArrayList<String>();
    for (Op op : Op.values()) {
      for (DecimalType t1 : types) {
        Attribute a = VarkaSqlResolve.column("a", t1);
        for (DecimalType t2 : types) {
          Attribute b = VarkaSqlResolve.column("b", t2);
          Expression e = op.node(a, b, EvalMode$.MODULE$.LEGACY());
          DecimalType spark = (DecimalType) e.dataType();
          int raw = op.rawPrecision(t1, t2);
          if (raw <= DecimalType.MAX_PRECISION() && spark.precision() != raw) {
            wrong.add(t1 + " " + op.sql + " " + t2 + ": Spark types it " + spark
                + ", the rule gives precision " + raw);
            continue;
          }
          List<VarkaConjunctSpec> specs = VarkaExpressionCompiler.explainPredicate(
              new EqualTo(e, e), seq(List.of(a, b)), VarkaMatrixRuns.BASE,
              VarkaAdmission.NO_HOST_REFUSAL);
          String reason = specs.size() == 1
              ? specs.get(0).decline().map(d -> d.reason()).orElse("") : "specs " + specs;
          boolean fuses = reason.isEmpty();
          if (spark.precision() <= MAX) {
            fused++;
            if (!fuses) {
              wrong.add(t1 + " " + op.sql + " " + t2 + " is " + spark + " and declines: "
                  + reason);
            }
            if (fuses) {
              String reduction = reduction(compile(new EqualTo(e, e), List.of(a, b)).fused(),
                  spark.precision());
              if (!reduction.isEmpty()) {
                wrong.add(t1 + " " + op.sql + " " + t2 + " is " + spark + ": " + reduction);
              }
            }
          } else {
            declined++;
            if (fuses || !reason.contains(op.wideRow())) {
              wrong.add(t1 + " " + op.sql + " " + t2 + " is " + spark + " and "
                  + (fuses ? "fuses" : "declines: " + reason));
            }
          }
        }
      }
    }
    assertTrue(wrong.isEmpty(), () -> wrong.size() + " pairs disagree, the first: "
        + wrong.subList(0, Math.min(10, wrong.size())));
    assertEquals(3 * types.size() * types.size(), fused + declined);
    String counts = fused + " fused, " + declined + " declined";
    assertTrue(fused > 0 && declined > 0, counts);
  }

  /**
   * Where the compiled arithmetic of a result of {@code precision} digits leaves the reduction
   * decimal64.smt2's lemmas rest on, or empty: each operand of a sum or difference at most
   * {@code precision - 1} digits after its rescale, and a product's two operands as many between
   * them. The digits are read from the IR, not from Spark's rule: a column's precision, plus
   * {@code k} for each multiply by a slot holding {@code 10^k}. A multiply whose right operand is
   * a slot is read as that rescale and skipped, so "every fused pair" the sweep checks means every
   * pair of column types, a product by a literal not among them.
   */
  private static String reduction(CompiledVarkaProjection fused, int precision) {
    var nodes = new ArrayList<VarkaVectorIR.IntArith>();
    for (VarkaVectorIR out : fused.outputs()) {
      arithmetic(out, nodes);
    }
    var found = new ArrayList<String>();
    int sites = 0;
    for (VarkaVectorIR.IntArith n : nodes) {
      boolean rescale = n.op() == VarkaVectorIR.IntOp.MUL && n.right() instanceof LiteralSlot;
      if (rescale) {
        continue;
      }
      sites++;
      int left = digits(n.left(), fused.longLiterals());
      int right = digits(n.right(), fused.longLiterals());
      int operands = n.op() == VarkaVectorIR.IntOp.MUL ? left + right : Math.max(left, right);
      if (left < 0 || right < 0) {
        found.add(n.op() + " over an operand the check cannot read: " + n);
      } else if (operands > precision - 1) {
        found.add(n.op() + " over operands of " + left + " and " + right + " digits, past the "
            + "lemmas' " + (precision - 1));
      }
    }
    if (sites == 0) {
      found.add("no arithmetic in the IR: " + fused.outputs());
    }
    return String.join("; ", found);
  }

  /** Every {@code IntArith} under {@code node}, found through the records' components. */
  private static void arithmetic(VarkaVectorIR node, List<VarkaVectorIR.IntArith> into) {
    if (node instanceof VarkaVectorIR.IntArith a) {
      into.add(a);
    }
    for (var component : node.getClass().getRecordComponents()) {
      Object child;
      try {
        child = component.getAccessor().invoke(node);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException(ex);
      }
      if (child instanceof VarkaVectorIR c) {
        arithmetic(c, into);
      } else if (child instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof VarkaVectorIR c) {
            arithmetic(c, into);
          }
        }
      }
    }
  }

  /** The digits of an unscaled operand as the IR builds it, or -1 for a form it does not. */
  private static int digits(VarkaVectorIR node, List<Long> literals) {
    if (node instanceof VarkaVectorIR.DecimalColumn c) {
      return c.precision();
    }
    if (node instanceof VarkaVectorIR.IntArith a && a.op() == VarkaVectorIR.IntOp.MUL
        && a.right() instanceof LiteralSlot slot) {
      long factor = literals.get(slot.index());
      int k = 0;
      for (long f = factor; f > 1 && f % 10 == 0; f /= 10) {
        k++;
      }
      int inner = digits(a.left(), literals);
      return factor == Decimal.POW_10()[k] && inner >= 0 ? inner + k : -1;
    }
    return -1;
  }

  @Test
  @DisplayName("an operand past 18 digits declines naming rows 359 and 360 under + and -, and "
      + "rows 362 and 363 under *")
  void wideOperandsDecline() {
    var narrow = new DecimalType(9, 2);
    var wide = new DecimalType(20, 2);
    for (Op op : Op.values()) {
      String reason = declineOf(op, wide, narrow);
      String want = op == Op.MUL ? "rows 362 and 363" : "rows 359 and 360";
      assertTrue(reason.contains(want), () -> op.sql + " over " + wide + ": " + reason);
    }
  }

  @Test
  @DisplayName("a sum past 38 digits declines naming rows 360 and 363 and a product rows 362 and "
      + "363, and a cast, a negation or a choice over wide values names every one's rows, in "
      + "either operand order")
  void wideArithmeticReasons() {
    var widest = new DecimalType(38, 0);
    for (Op op : Op.values()) {
      String reason = declineOf(op, widest, widest);
      String want = op == Op.MUL ? "rows 362 and 363" : "rows 360 and 363";
      assertTrue(reason.contains(want), () -> op.sql + ": " + reason);
    }
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(9, 2)),
        VarkaSqlResolve.column("b", new DecimalType(9, 2)),
        VarkaSqlResolve.column("x", new DecimalType(18, 2)),
        VarkaSqlResolve.column("y", new DecimalType(18, 2)),
        VarkaSqlResolve.column("c", new DecimalType(9, 1)),
        VarkaSqlResolve.column("e", new DecimalType(10, 1)),
        VarkaSqlResolve.column("d", new DecimalType(20, 2)));
    for (String[] pair : new String[][] {
        // Each literal is of the node's scale, so that no rescale of it names rows 359 and 360.
        {"CAST(a * b AS DECIMAL(20, 4)) > 0.0000", "row 361"},
        {"-(x + y) > 0.00", "row 360"},
        {"CAST(x + y AS DECIMAL(19, 1)) > 0.0", "rows 350 and 360"},
        {"IF(a > b, x + y, x) > 0.00", "rows 359 and 360"},
        {"COALESCE(d, x + y) > 0.00", "rows 359 and 360"},
        {"IF(a > b, x + y, c * e) > 0.00", "rows 360 and 361"},
        {"IF(a > b, c * e, x + y) > 0.00", "rows 360 and 361"}}) {
      String reason = predicateDecline(pair[0], columns);
      assertTrue(reason.contains(pair[1]), () -> pair[0] + ": " + reason);
    }
    assertEquals(predicateDecline("IF(a > b, x + y, c * e) > 0.00", columns),
        predicateDecline("IF(a > b, c * e, x + y) > 0.00", columns));
  }

  /** The decline reason of the one conjunct {@code sql} parses to, or "fused". */
  private static String predicateDecline(String sql, List<Attribute> columns) {
    Expression e = VarkaSqlResolve.parse(sql, seq(columns));
    List<VarkaConjunctSpec> specs = VarkaExpressionCompiler.explainPredicate(e, seq(columns),
        VarkaMatrixRuns.BASE, VarkaAdmission.NO_HOST_REFUSAL);
    return specs.get(0).decline().map(d -> d.reason()).orElse("fused");
  }

  @Test
  @DisplayName("a hand-built product typed DECIMAL(10, 0) whose rule gives 41 digits declines as "
      + "mistyped, its message rendering a type no DecimalType can hold")
  void mistypedPastThirtyEightDeclines() {
    var wide = new DecimalType(20, 0);
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", wide),
        VarkaSqlResolve.column("b", wide));
    // Spark types every product it builds by its rule, capped at 38, so only a node that states
    // another type reaches the mistyped check with a rule past 38 digits: a spy, since javac
    // cannot subclass a Scala tree node.
    Multiply m = Mockito.spy(new Multiply(new BoundReference(0, wide, true),
        new BoundReference(1, wide, true),
        NumericEvalContext$.MODULE$.apply(EvalMode$.MODULE$.LEGACY(), false)));
    Mockito.doReturn(new DecimalType(10, 0)).when(m).dataType();
    List<VarkaConjunctSpec> specs = VarkaExpressionCompiler.explainPredicate(new EqualTo(m, m),
        seq(columns), VarkaMatrixRuns.BASE, VarkaAdmission.NO_HOST_REFUSAL);
    assertEquals(1, specs.size());
    String reason = specs.get(0).decline().map(d -> d.reason()).orElse("fused");
    assertTrue(reason.contains("typed decimal(10,0) where Spark's rule gives decimal(41,0)"),
        () -> "declined: " + reason);
  }

  @Test
  @DisplayName("P = 18: DECIMAL(17, 2) + and - DECIMAL(17, 2) at +-(10^17 - 1) in every sign "
      + "pair")
  void sumAtEighteenDigits() {
    long top = 99_999_999_999_999_999L;
    var t = new DecimalType(17, 2);
    var rows = new ArrayList<long[]>();
    for (long sa : new long[] {1, -1}) {
      for (long sb : new long[] {1, -1}) {
        rows.add(new long[] {sa * top, sb * top});
        rows.add(new long[] {sa * top, sb * 1});
      }
    }
    rows.add(new long[] {0, 0});
    check(Op.ADD, t, t, rows);
    check(Op.SUB, t, t, rows);
  }

  @Test
  @DisplayName("P = 17: DECIMAL(9, 2) + and - DECIMAL(16, 9) rescale the left operand by 10^7")
  void sumWithARescaleOfSeven() {
    var t1 = new DecimalType(9, 2);
    var t2 = new DecimalType(16, 9);
    long a = 999_999_999L;
    long b = 9_999_999_999_999_999L;
    var rows = new ArrayList<long[]>();
    for (long sa : new long[] {1, -1}) {
      for (long sb : new long[] {1, -1}) {
        rows.add(new long[] {sa * a, sb * b});
        rows.add(new long[] {sa * a, sb * 1});
        rows.add(new long[] {sa * 1, sb * b});
      }
    }
    check(Op.ADD, t1, t2, rows);
    check(Op.SUB, t1, t2, rows);
    check(Op.ADD, t2, t1, rows.stream().map(r -> new long[] {r[1], r[0]}).toList());
  }

  @Test
  @DisplayName("P = 18: DECIMAL(8, 2) * DECIMAL(9, 2) at +-(10^8 - 1) and +-(10^9 - 1), and "
      + "DECIMAL(9, 2) * DECIMAL(9, 2) declines naming row 361")
  void productAtEighteenDigits() {
    var t1 = new DecimalType(8, 2);
    var t2 = new DecimalType(9, 2);
    long a = 99_999_999L;
    long b = 999_999_999L;
    var rows = new ArrayList<long[]>();
    for (long sa : new long[] {1, -1}) {
      for (long sb : new long[] {1, -1}) {
        rows.add(new long[] {sa * a, sb * b});
        rows.add(new long[] {sa * a, sb * 1});
        rows.add(new long[] {sa * 1, sb * b});
      }
    }
    rows.add(new long[] {0, -b});
    check(Op.MUL, t1, t2, rows);
    String reason = declineOf(Op.MUL, t2, t2);
    assertTrue(reason.contains("row 361"), () -> "DECIMAL(9, 2) * DECIMAL(9, 2): " + reason);
  }

  @Test
  @DisplayName("a literal on either side takes its unscaled value in a slot: a + 1.5 rescales "
      + "the literal at compile time and 2.25 * a multiplies by its unscaled 225")
  void literalOperands() {
    var t = new DecimalType(9, 2);
    var rows = new ArrayList<long[]>();
    for (long a : new long[] {999_999_999L, -999_999_999L, 0L, 1L, -7L}) {
      rows.add(new long[] {a});
    }
    // 1.5 is DECIMAL(2, 1): the sum is DECIMAL(10, 2), the literal's slot 150.
    checkLiteral("a + 1.5", t, new DecimalType(10, 2), new BigDecimal("1.5"), Op.ADD, false,
        rows, 150L);
    checkLiteral("1.5 - a", t, new DecimalType(10, 2), new BigDecimal("1.5"), Op.SUB, true,
        rows, 150L);
    // 2.25 is DECIMAL(3, 2): the product is DECIMAL(13, 4), the literal's slot 225.
    checkLiteral("2.25 * a", t, new DecimalType(13, 4), new BigDecimal("2.25"), Op.MUL, true,
        rows, 225L);
  }

  @Test
  @DisplayName("a constant of any shape takes one slot: abs(-1.25) and 1.5 + 2.25 fold whole, "
      + "with no negation or add left in the kernel")
  void constantsFoldWhole() {
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", new DecimalType(9, 2)));
    for (Object[] row : new Object[][] {{"a < abs(-1.25)", 125L}, {"a < 1.5 + 2.25", 375L}}) {
      String sql = (String) row[0];
      CompiledVarkaPredicate compiled = compile(parse(sql, columns, false), columns);
      assertEquals(List.of(row[1]), compiled.fused().longLiterals(), sql);
      String tree = compiled.fused().outputs().stream().map(VarkaVectorIR::canonical).toList()
          .toString();
      assertTrue(!tree.contains("(abs:") && !tree.contains("(int:"), () -> sql + ": " + tree);
    }
  }

  @Test
  @DisplayName("a sample of every fused class, at the operands' bounds, zero, one and random "
      + "values, gives BigDecimal's exact result")
  void sampledClasses() {
    var random = new Random(349L);
    int checked = 0;
    for (Op op : Op.values()) {
      for (int draw = 0; draw < 40; draw++) {
        DecimalType t1 = randomType(random);
        DecimalType t2 = randomType(random);
        if (op.rawPrecision(t1, t2) > MAX) {
          draw--;
          continue;
        }
        long b1 = bound(t1);
        long b2 = bound(t2);
        var rows = new ArrayList<long[]>();
        for (long x : new long[] {b1, -b1, 0, 1}) {
          for (long y : new long[] {b2, -b2, -1}) {
            rows.add(new long[] {x, y});
          }
        }
        for (int i = 0; i < 8; i++) {
          rows.add(new long[] {randomIn(random, b1), randomIn(random, b2)});
        }
        check(op, t1, t2, rows);
        checked++;
      }
    }
    assertEquals(120, checked);
  }

  @Test
  @DisplayName("a null test over a sum or a product is null exactly where an operand is, at the "
      + "operands' bounds too, in the LEGACY, ANSI and TRY modes")
  void nullTestOverArithmetic() {
    var t1 = new DecimalType(8, 2);
    var t2 = new DecimalType(9, 2);
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", t1),
        VarkaSqlResolve.column("b", t2), VarkaSqlResolve.column("c", t2));
    long a = 99_999_999L;
    long b = 999_999_999L;
    var rows = List.of(new Triple(a, b, 0L), new Triple(-a, b, 0L), new Triple(null, b, 0L),
        new Triple(a, null, 0L), new Triple(null, null, 0L), new Triple(0L, -b, 0L));
    for (boolean ansi : new boolean[] {false, true}) {
      for (String sql : List.of("try_multiply(a, b) IS NULL", "a * b IS NULL",
          "try_add(a, b) IS NULL", "a - b IS NOT NULL")) {
        boolean not = sql.endsWith("NOT NULL");
        Expression e = parse(sql, columns, ansi);
        Enumeration.Value want = sql.startsWith("try_") ? EvalMode$.MODULE$.TRY()
            : ansi ? EvalMode$.MODULE$.ANSI() : EvalMode$.MODULE$.LEGACY();
        assertEquals(want, arithmeticMode(e), sql);
        run(e, sql, columns, rows, r -> (r.a() == null || r.b() == null) != not, null);
      }
    }
  }

  @Test
  @DisplayName("a try_ form and an ANSI form compile to the LEGACY form's tree and literals")
  void oneTreeInEveryMode() {
    var t1 = new DecimalType(8, 2);
    var t2 = new DecimalType(9, 2);
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", t1),
        VarkaSqlResolve.column("b", t2), VarkaSqlResolve.column("c", new DecimalType(18, 4)));
    for (String[] pair : new String[][] {
        {"a + b = c", "try_add(a, b) = c"},
        {"a - b = c", "try_subtract(a, b) = c"},
        {"a * b = c", "try_multiply(a, b) = c"}}) {
      Expression legacyTree = parse(pair[0], columns, false);
      assertEquals(EvalMode$.MODULE$.LEGACY(), arithmeticMode(legacyTree), pair[0]);
      assertEquals(EvalMode$.MODULE$.ANSI(), arithmeticMode(parse(pair[0], columns, true)),
          pair[0]);
      for (boolean ansi : new boolean[] {false, true}) {
        assertEquals(EvalMode$.MODULE$.TRY(), arithmeticMode(parse(pair[1], columns, ansi)),
            pair[1]);
      }
      CompiledVarkaPredicate legacy = compile(legacyTree, columns);
      for (CompiledVarkaPredicate other : List.of(
          compile(parse(pair[0], columns, true), columns),
          compile(parse(pair[1], columns, false), columns),
          compile(parse(pair[1], columns, true), columns))) {
        assertEquals(legacy.fused().outputs(), other.fused().outputs(), pair[1]);
        assertEquals(legacy.fused().longLiterals(), other.fused().longLiterals(), pair[1]);
        assertEquals(legacy.fused().inputOrdinals(), other.fused().inputOrdinals(), pair[1]);
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------------------------

  private static DecimalType randomType(Random random) {
    int p = 1 + random.nextInt(MAX);
    return new DecimalType(p, random.nextInt(p + 1));
  }

  private static long bound(DecimalType t) {
    return BigInteger.TEN.pow(t.precision()).longValueExact() - 1;
  }

  private static long randomIn(Random random, long bound) {
    return random.nextLong(-bound, bound + 1);
  }

  private static scala.collection.immutable.Seq<Attribute> seq(List<Attribute> columns) {
    return CollectionConverters.asScala(columns).toSeq();
  }

  private static Expression parse(String sql, List<Attribute> columns, boolean ansi) {
    SQLConf conf = SQLConf.get().clone();
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), Boolean.toString(ansi));
    return SQLConf$.MODULE$.withExistingConf(conf,
        () -> VarkaSqlResolve.parse(sql, seq(columns)));
  }

  /**
   * The eval mode of the first {@code +}, {@code -} or {@code *} in {@code e}, read through a
   * {@code try_} form's replacement as the compiler reads it.
   */
  private static Enumeration.Value arithmeticMode(Expression e) {
    switch (e) {
      case Add a -> {
        return a.evalMode();
      }
      case Subtract a -> {
        return a.evalMode();
      }
      case Multiply m -> {
        return m.evalMode();
      }
      case RuntimeReplaceable r -> {
        return arithmeticMode(r.replacement());
      }
      default -> {
        for (Expression child : CollectionConverters.asJava(e.children())) {
          Enumeration.Value mode = arithmeticMode(child);
          if (mode != null) {
            return mode;
          }
        }
        return null;
      }
    }
  }

  private static String declineOf(Op op, DecimalType t1, DecimalType t2) {
    Attribute a = VarkaSqlResolve.column("a", t1);
    Attribute b = VarkaSqlResolve.column("b", t2);
    Expression e = op.node(a, b, EvalMode$.MODULE$.LEGACY());
    List<VarkaConjunctSpec> specs = VarkaExpressionCompiler.explainPredicate(new EqualTo(e, e),
        seq(List.of(a, b)), VarkaMatrixRuns.BASE, VarkaAdmission.NO_HOST_REFUSAL);
    assertEquals(1, specs.size());
    return specs.get(0).decline().map(d -> d.reason()).orElse("fused");
  }

  private static CompiledVarkaPredicate compile(Expression e, List<Attribute> columns) {
    var compiled = VarkaExpressionCompiler.compilePredicate(e, seq(columns), VarkaMatrixRuns.BASE,
            VarkaAdmission.NO_HOST_REFUSAL)
        .orElseThrow(() -> new AssertionError(e.sql() + " does not fuse: "
            + VarkaExpressionCompiler.explainPredicate(e, seq(columns), VarkaMatrixRuns.BASE,
                VarkaAdmission.NO_HOST_REFUSAL)));
    assertTrue(compiled.residualConjuncts().isEmpty(), () -> e.sql() + " leaves a residual");
    return compiled;
  }

  /**
   * Runs {@code a op b = c} over columns of types {@code t1}, {@code t2} and the result's type,
   * each operand row given three ways: {@code c} the exact result and, where it stays in the
   * type, one unit either side; and a null in each column.
   */
  private static void check(Op op, DecimalType t1, DecimalType t2, List<long[]> operands) {
    Attribute a = VarkaSqlResolve.column("a", t1);
    Attribute b = VarkaSqlResolve.column("b", t2);
    DecimalType result = (DecimalType) op.node(a, b, EvalMode$.MODULE$.LEGACY()).dataType();
    List<Attribute> columns = List.of(a, b, VarkaSqlResolve.column("c", result));
    String sql = "a " + op.sql + " b = c";
    var rows = new ArrayList<Triple>();
    for (long[] r : operands) {
      long exact = op.exact.apply(BigDecimal.valueOf(r[0], t1.scale()),
          BigDecimal.valueOf(r[1], t2.scale())).setScale(result.scale()).unscaledValue()
          .longValueExact();
      rows.add(new Triple(r[0], r[1], exact));
      long cBound = bound(result);
      if (exact < cBound) {
        rows.add(new Triple(r[0], r[1], exact + 1));
      }
      if (exact > -cBound) {
        rows.add(new Triple(r[0], r[1], exact - 1));
      }
    }
    rows.add(new Triple(null, 0L, 0L));
    rows.add(new Triple(0L, null, 0L));
    rows.add(new Triple(0L, 0L, null));
    run(sql, columns, rows, row -> row.a() != null && row.b() != null && row.c() != null
        && op.exact.apply(BigDecimal.valueOf(row.a(), t1.scale()),
            BigDecimal.valueOf(row.b(), t2.scale()))
            .compareTo(BigDecimal.valueOf(row.c(), result.scale())) == 0, null);
  }

  /**
   * Runs {@code sql = c} with one decimal column {@code a} and the literal {@code lit} on the
   * left of the operator when {@code litLeft}, checking that the literal's unscaled value at the
   * result's scale, {@code slot}, is in the long table.
   */
  private static void checkLiteral(String sql, DecimalType t, DecimalType result, BigDecimal lit,
      Op op, boolean litLeft, List<long[]> operands, long slot) {
    List<Attribute> columns = List.of(VarkaSqlResolve.column("a", t),
        VarkaSqlResolve.column("b", t), VarkaSqlResolve.column("c", result));
    var rows = new ArrayList<Triple>();
    for (long[] r : operands) {
      BigDecimal av = BigDecimal.valueOf(r[0], t.scale());
      long exact = (litLeft ? op.exact.apply(lit, av) : op.exact.apply(av, lit))
          .setScale(result.scale()).unscaledValue().longValueExact();
      rows.add(new Triple(r[0], 0L, exact));
      rows.add(new Triple(r[0], 0L, exact + 1));
    }
    rows.add(new Triple(null, 0L, 0L));
    run(sql + " = c", columns, rows, row -> {
      if (row.a() == null || row.c() == null) {
        return false;
      }
      BigDecimal av = BigDecimal.valueOf(row.a(), t.scale());
      BigDecimal got = litLeft ? op.exact.apply(lit, av) : op.exact.apply(av, lit);
      return got.compareTo(BigDecimal.valueOf(row.c(), result.scale())) == 0;
    }, slot);
  }

  private interface Expect {
    boolean selects(Triple row);
  }

  /**
   * Compiles {@code sql} over {@code columns}, runs its kernel over {@code rows} as Arrow decimal
   * vectors, and holds each row's selection to {@code expect}; where {@code slot} is given, the
   * long table must hold it.
   */
  private static void run(String sql, List<Attribute> columns, List<Triple> rows, Expect expect,
      Long slot) {
    run(VarkaSqlResolve.parse(sql, seq(columns)), sql, columns, rows, expect, slot);
  }

  /** {@link #run(String, List, List, Expect, Long)} over {@code e}, already parsed. */
  private static void run(Expression e, String sql, List<Attribute> columns, List<Triple> rows,
      Expect expect, Long slot) {
    CompiledVarkaPredicate compiled = compile(e, columns);
    var fused = compiled.fused();
    if (slot != null) {
      assertTrue(fused.longLiterals().contains(slot),
          () -> sql + ": the literal's slot " + slot + " is in the long table: " + fused);
    }
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
    var emitted = VarkaEmitterFixtures.emit(List.of(root), fused.inputOrdinals().size(),
        lits.length, VarkaMatrixRuns.BASE);
    VarkaFusedKernel kernel = VarkaEmitterFixtures.load(emitted).kernel();
    long[] got = kernel(kernel, fused.inputOrdinals(), columns, rows, lits);
    for (int i = 0; i < rows.size(); i++) {
      Triple r = rows.get(i);
      long want = expect.selects(r) ? 1L : 0L;
      int at = i;
      assertEquals(want, got[i], () -> sql + " over " + columns + ", row " + r + " at " + at);
    }
  }

  /**
   * Runs {@code kernel} over the three columns as Arrow decimal vectors, input {@code i} being
   * the column at {@code ordinals.get(i)}, and returns its one long output per row.
   */
  private static long[] kernel(VarkaFusedKernel kernel, List<Integer> ordinals,
      List<Attribute> columns, List<Triple> rows, long[] lits) {
    int length = rows.size();
    int inputs = ordinals.size();
    try (BufferAllocator allocator = ArrowUtils.rootAllocator()
            .newChildAllocator("varka-decimal-arith", 0, Long.MAX_VALUE);
        DecimalVector va = vector("a", rows, 0, columns.get(0), allocator);
        DecimalVector vb = vector("b", rows, 1, columns.get(1), allocator);
        DecimalVector vc = vector("c", rows, 2, columns.get(2), allocator);
        var arena = Arena.ofConfined()) {
      DecimalVector[] byOrdinal = {va, vb, vc};
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

  private static DecimalVector vector(String name, List<Triple> rows, int column,
      Attribute attribute, BufferAllocator allocator) {
    var type = (DecimalType) attribute.dataType();
    var v = new DecimalVector(name, allocator, type.precision(), type.scale());
    v.allocateNew(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      Triple r = rows.get(i);
      Long u = switch (column) {
        case 0 -> r.a();
        case 1 -> r.b();
        default -> r.c();
      };
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
