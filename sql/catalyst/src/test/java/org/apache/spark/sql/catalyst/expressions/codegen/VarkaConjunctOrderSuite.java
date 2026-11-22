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
package org.apache.spark.sql.catalyst.expressions.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import scala.Enumeration;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.And;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Coalesce;
import org.apache.spark.sql.catalyst.expressions.DateAdd;
import org.apache.spark.sql.catalyst.expressions.Divide;
import org.apache.spark.sql.catalyst.expressions.EqualNullSafe;
import org.apache.spark.sql.catalyst.expressions.EqualTo;
import org.apache.spark.sql.catalyst.expressions.EvalMode$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GreaterThan;
import org.apache.spark.sql.catalyst.expressions.GreaterThanOrEqual;
import org.apache.spark.sql.catalyst.expressions.In;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.IsNull;
import org.apache.spark.sql.catalyst.expressions.LessThan;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.NamedExpression$;
import org.apache.spark.sql.catalyst.expressions.Not;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext$;
import org.apache.spark.sql.catalyst.expressions.Year;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAdmission;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.types.ByteType$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DateType$;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.DoubleType$;
import org.apache.spark.sql.types.IntegerType$;
import org.apache.spark.sql.types.LongType$;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.ShortType$;
import org.apache.spark.sql.types.StringType$;
import org.apache.spark.sql.types.TimestampType$;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * The order rule of VARKA-380: which conjuncts can raise ({@link VarkaConjunctOrder#canRaise}),
 * the order whole-stage codegen evaluates them in ({@link VarkaConjunctOrder#codegenOrder}), and,
 * through {@link VarkaExpressionCompiler#explainPredicate}, which fused conjuncts the compiler
 * demotes behind a residual conjunct that can raise. The answers against Spark are
 * {@code VarkaDifferentialSuite}'s and the reproducers'.
 */
@VarkaSuite
public final class VarkaConjunctOrderSuite {

  private static final Enumeration.Value ANSI = EvalMode$.MODULE$.ANSI();
  private static final Enumeration.Value LEGACY = EvalMode$.MODULE$.LEGACY();

  private static AttributeReference column(String name, DataType type) {
    return new AttributeReference(name, type, true, Metadata.empty(),
        NamedExpression$.MODULE$.newExprId(), CollectionConverters.asScala(List.<String>of())
            .toSeq());
  }

  private final AttributeReference d = column("d", DateType$.MODULE$);
  private final AttributeReference i = column("i", IntegerType$.MODULE$);
  private final AttributeReference l = column("l", LongType$.MODULE$);
  private final AttributeReference l2 = column("l2", LongType$.MODULE$);
  private final AttributeReference s = column("s", StringType$.MODULE$);
  private final AttributeReference x = column("x", DoubleType$.MODULE$);
  private final AttributeReference ym = column("ym", YearMonthIntervalType.apply());
  /** No integer digits: its cast to INT is always 0. */
  private final AttributeReference fraction = column("fraction", DecimalType.apply(18, 18));
  /** Ten integer digits: its cast to INT overflows past 2147483647 under ANSI. */
  private final AttributeReference wide = column("wide", DecimalType.apply(18, 8));
  /**
   * Nineteen integer digits: its cast to BIGINT overflows past 9223372036854775807 under ANSI,
   * though {@code Cast.canUpCast} counts it a widening.
   */
  private final AttributeReference digits19 = column("digits19", DecimalType.apply(19, 0));
  private final Seq<Attribute> output = seq(d, i, l, l2, s, x, ym, fraction, wide, digits19);

  private static NumericEvalContext ctx(Enumeration.Value mode) {
    return NumericEvalContext$.MODULE$.apply(mode, false);
  }

  @SafeVarargs
  private static <T> Seq<T> seq(T... items) {
    return CollectionConverters.asScala(List.of(items)).toSeq();
  }

  private static Expression and(Expression... conjuncts) {
    Expression folded = conjuncts[0];
    for (int k = 1; k < conjuncts.length; k++) {
      folded = new And(folded, conjuncts[k]);
    }
    return folded;
  }

  private static Literal lit(Object value, DataType type) {
    return Literal.create(value, type);
  }

  /** {@code d < DATE '2021-06-01'}, a conjunct the compiler fuses. */
  private Expression beforeJune() {
    return new LessThan(d, lit(18779, DateType$.MODULE$));
  }

  /** {@code d > DATE '2021-02-01'}, another. */
  private Expression afterFebruary() {
    return new GreaterThan(d, lit(18659, DateType$.MODULE$));
  }

  /** {@code (l + 1) > 0} in {@code mode}: BIGINT arithmetic, which raises only under ANSI. */
  private Expression sumOfL(Enumeration.Value mode) {
    return new GreaterThan(new Add(l, lit(1L, LongType$.MODULE$), ctx(mode)),
        lit(0L, LongType$.MODULE$));
  }

  /**
   * {@code (l * 3) > 0} in {@code mode}: a checked BIGINT product over a column, which the
   * compiler leaves residual under ANSI and TRY, since no bound clears it (VARKA-104).
   */
  private Expression productOfL(Enumeration.Value mode) {
    return new GreaterThan(new Multiply(l, lit(3L, LongType$.MODULE$), ctx(mode)),
        lit(0L, LongType$.MODULE$));
  }

  /**
   * {@code l + l2} in {@code mode}: null when either column is, all the way down, so codegen
   * treats its null test as one it moves. A literal is not null-intolerant, so {@code l + 1} is
   * not.
   */
  private Expression sumOfLAndL2(Enumeration.Value mode) {
    return new Add(l, l2, ctx(mode));
  }

  /** {@code (l2 + 1) > 0} in {@code mode}. */
  private Expression sumOfL2(Enumeration.Value mode) {
    return new GreaterThan(new Add(l2, lit(1L, LongType$.MODULE$), ctx(mode)),
        lit(0L, LongType$.MODULE$));
  }

  /** {@code CAST(decimal AS type)} in {@code mode}. */
  private static Cast cast(Expression decimal, DataType type, Enumeration.Value mode) {
    return new Cast(decimal, type, scala.Option.empty(), mode);
  }

  /** {@code CAST(decimal AS INT) IN (0, 2147483647)} under ANSI, which the compiler leaves. */
  private static Expression castIn(Expression decimal) {
    return new In(cast(decimal, IntegerType$.MODULE$, ANSI),
        seq(lit(0, IntegerType$.MODULE$), lit(Integer.MAX_VALUE, IntegerType$.MODULE$)));
  }

  /** {@code date_add(d, offset) > DATE '2000-01-01'}, a conjunct the compiler fuses. */
  private Expression dateAddAfter2000(Expression offset) {
    return new GreaterThan(new DateAdd(d, offset), lit(10957, DateType$.MODULE$));
  }

  /** Which conjuncts fused, in written order. */
  private List<Boolean> fused(Expression condition) {
    var flags = new ArrayList<Boolean>();
    for (VarkaConjunctSpec spec : VarkaExpressionCompiler.explainPredicate(condition, output,
        VarkaEmitOptions.DEFAULTS, VarkaAdmission.NO_HOST_REFUSAL)) {
      flags.add(spec.fused());
    }
    return flags;
  }

  @Test
  @DisplayName("comparisons, null tests and string matches cannot raise, and integer arithmetic "
      + "can only under ANSI")
  void canRaise() {
    assertFalse(VarkaConjunctOrder.canRaise(new EqualTo(s, lit("x", StringType$.MODULE$))));
    assertFalse(VarkaConjunctOrder.canRaise(new IsNotNull(l)));
    assertFalse(VarkaConjunctOrder.canRaise(beforeJune()));
    assertFalse(VarkaConjunctOrder.canRaise(sumOfL(LEGACY)));
    assertTrue(VarkaConjunctOrder.canRaise(sumOfL(ANSI)));
  }

  @Test
  @DisplayName("floating point arithmetic cannot raise in any mode, a division can, and so can "
      + "year-month interval arithmetic under LEGACY")
  void canRaiseByType() {
    var xPlus = new Add(x, lit(1.0, DoubleType$.MODULE$), ctx(ANSI));
    assertFalse(VarkaConjunctOrder.canRaise(new GreaterThan(xPlus, lit(0.0,
        DoubleType$.MODULE$))));
    var divide = new Divide(x, x, ctx(ANSI));
    assertTrue(VarkaConjunctOrder.canRaise(new GreaterThan(divide, lit(0.0,
        DoubleType$.MODULE$))));
    var ymSum = new Add(ym, ym, ctx(LEGACY));
    assertTrue(VarkaConjunctOrder.canRaise(new IsNotNull(ymSum)));
  }

  @Test
  @DisplayName("a widening cast cannot raise, and a narrowing one or a date to a timestamp can")
  void casts() {
    assertTrue(VarkaConjunctOrder.canRaise(new IsNotNull(
        new Cast(d, TimestampType$.MODULE$, scala.Option.apply("UTC"), ANSI))));
    assertFalse(VarkaConjunctOrder.canRaise(new GreaterThan(
        new Cast(i, LongType$.MODULE$, scala.Option.empty(), ANSI),
        l)));
    assertTrue(VarkaConjunctOrder.canRaise(new GreaterThan(
        new Cast(l, IntegerType$.MODULE$, scala.Option.empty(), ANSI),
        i)));
  }

  @Test
  @DisplayName("codegen evaluates each null test before the first conjunct that reads its "
      + "column, and the unread ones last")
  void codegenOrder() {
    // isnotnull(d), isnotnull(l), (l + 1) > 0, d < ...: the null test of d moves to just before
    // the conjunct that reads d, behind the arithmetic, and the null test of l stays before it.
    List<Expression> conjuncts = List.of(new IsNotNull(d), new IsNotNull(l), sumOfL(ANSI),
        beforeJune());
    assertEquals(steps(1, 2, 0, 3), VarkaConjunctOrder.codegenOrder(conjuncts, output, false));
    // A null test nothing reads goes to the end.
    List<Expression> unread = List.of(new IsNotNull(i), sumOfL(ANSI));
    assertEquals(steps(1, 0), VarkaConjunctOrder.codegenOrder(unread, output, false));
  }

  @Test
  @DisplayName("codegen checks a column that a larger null test implies before the first "
      + "conjunct that reads it")
  void impliedNullCheck() {
    // isnotnull(l + l2) implies l is not null, so codegen checks l before coalesce(l, 0) >= 0.
    List<Expression> conjuncts = List.of(new IsNotNull(sumOfLAndL2(LEGACY)), coalesceL());
    var order = VarkaConjunctOrder.codegenOrder(conjuncts, output, false);
    assertEquals(3, order.size(), order.toString());
    assertTrue(order.get(0) instanceof VarkaConjunctOrder.NullCheck(var column)
        && column.semanticEquals(l), order.toString());
    assertEquals(steps(1, 0), order.subList(1, 3));
  }

  @Test
  @DisplayName("a null-safe equality implies no column is not null, as an equality does")
  void nullSafeEqualityIsNotNullIntolerant() {
    // isnotnull(l = l2) implies l is not null, so codegen checks l before coalesce(l, 0) >= 0.
    var implied = VarkaConjunctOrder.codegenOrder(
        List.of(new IsNotNull(new EqualTo(l, l2)), coalesceL()), output, false);
    assertEquals(3, implied.size(), implied.toString());
    assertTrue(implied.get(0) instanceof VarkaConjunctOrder.NullCheck, implied.toString());
    // isnotnull(l <=> l2) is true whatever the columns are, so no column is checked ahead of
    // the conjunct that reads it, and the order is the written one.
    var none = VarkaConjunctOrder.codegenOrder(
        List.of(new IsNotNull(new EqualNullSafe(l, l2)), coalesceL()), output, false);
    assertEquals(steps(0, 1), none);
  }

  /** {@code coalesce(l, 0) >= 0}: reads l, and is not null when l is. */
  private Expression coalesceL() {
    return new GreaterThanOrEqual(new Coalesce(seq(l, lit(0L, LongType$.MODULE$))),
        lit(0L, LongType$.MODULE$));
  }

  private static List<VarkaConjunctOrder.Step> steps(int... indices) {
    var steps = new ArrayList<VarkaConjunctOrder.Step>();
    for (int index : indices) {
      steps.add(new VarkaConjunctOrder.Conjunct(index));
    }
    return steps;
  }

  @Test
  @DisplayName("a fused conjunct that pulls a residual null test ahead of a raising conjunct is "
      + "demoted, unless it drops the null rows itself")
  void puller() {
    // Codegen: isnotnull(l2) before coalesce(l2, 0) >= 0, which reads l2, so before the sum of
    // l. The residual filter without the coalesce places isnotnull(l2) last, behind the sum: a row
    // whose l2 is null and whose sum overflows would raise there and not in Spark, since the
    // coalesce lets a null l2 through.
    Expression coalesce = new GreaterThanOrEqual(
        new Coalesce(seq(l2, lit(0L, LongType$.MODULE$))), lit(0L, LongType$.MODULE$));
    List<Expression> conjuncts = List.of(coalesce, new IsNotNull(l2), sumOfL(ANSI));
    assertEquals(List.of(0),
        List.copyOf(VarkaConjunctOrder.behindRaising(conjuncts, List.of(true, false, false),
            output).keySet()));
    // With the null test fused beside it nothing moves.
    assertTrue(VarkaConjunctOrder.behindRaising(conjuncts, List.of(true, true, false), output)
        .isEmpty());
    // A puller that is null when its column is drops the same rows itself: nothing moves.
    List<Expression> intolerant = List.of(new GreaterThan(l2, lit(0L, LongType$.MODULE$)),
        new IsNotNull(l2), sumOfL(ANSI));
    assertTrue(VarkaConjunctOrder.behindRaising(intolerant, List.of(true, false, false), output)
        .isEmpty());
  }

  @Test
  @DisplayName("a puller that is demoted can raise where it stands, so the fused conjunct after "
      + "it is demoted for that, not for an order the rule does not follow")
  void demotedPullerMovesTheLine() {
    // (coalesce(l2, 0) + 1) > 0 (fused, can raise) pulls isnotnull(l2) ahead of (l + 1) > 0 in
    // codegen, which the residual filter would place last, so it is demoted. Residual now, it
    // raises where it stands, and d IS NULL, which is never NULL and so is demoted for no other
    // reason, comes behind a raising conjunct: that is the reason it gets, and not that the port
    // of codegen's order drifted.
    Expression puller = new GreaterThan(new Add(new Coalesce(seq(l2, lit(0L, LongType$.MODULE$))),
        lit(1L, LongType$.MODULE$), ctx(ANSI)), lit(0L, LongType$.MODULE$));
    List<Expression> conjuncts = List.of(puller, new IsNull(d), new IsNotNull(l2), sumOfL(ANSI));
    var late = VarkaConjunctOrder.behindRaising(conjuncts, List.of(true, true, false, false),
        output);
    assertEquals(java.util.Set.of(0, 1), late.keySet());
    assertTrue(late.get(1).contains(VarkaConjunctOrder.OUT_OF_ORDER), late.get(1));
  }

  @Test
  @DisplayName("a fused conjunct that a codegen null check puts behind is demoted when the "
      + "residual filter would not check the column")
  void impliedPuller() {
    // coalesce(l, 0) >= 0 (fused), (l2 + 1) > 0 (raising), isnotnull(l + l2) (residual): codegen
    // checks l before the coalesce, so a row whose l is null never reaches the sum. The residual
    // filter alone reads l in no conjunct before the sum, so it would not check it there.
    List<Expression> conjuncts = List.of(coalesceL(), sumOfL2(ANSI),
        new IsNotNull(sumOfLAndL2(LEGACY)));
    assertEquals(List.of(0),
        List.copyOf(VarkaConjunctOrder.behindRaising(conjuncts, List.of(true, false, false),
            output).keySet()));
  }

  @Test
  @DisplayName("a decimal cast to an integer type cannot raise when the type holds its integer "
      + "digits, or outside ANSI")
  void decimalCasts() {
    // Under ANSI the cast truncates, so a DECIMAL(p, s) raises only past p - s integer digits:
    // 2 for a TINYINT (99 fits, 999 not), 4 for a SMALLINT, 9 for an INT, 18 for a BIGINT
    // (999999999999999999 fits, 9999999999999999999 is past 9223372036854775807). Scale 0 pins
    // the BIGINT bound against Cast.canUpCast, which admits a DECIMAL(19, 0) though it overflows.
    DataType[] types = {ByteType$.MODULE$, ShortType$.MODULE$, IntegerType$.MODULE$,
        LongType$.MODULE$};
    int[] held = {2, 4, 9, 18};
    for (int k = 0; k < types.length; k++) {
      for (int scale : new int[] {0, 2}) {
        var fits = column("fits", DecimalType.apply(held[k] + scale, scale));
        var over = column("over", DecimalType.apply(held[k] + scale + 1, scale));
        assertFalse(VarkaConjunctOrder.canRaise(new IsNotNull(cast(fits, types[k], ANSI))),
            types[k].sql() + " " + fits.dataType().sql());
        assertTrue(VarkaConjunctOrder.canRaise(new IsNotNull(cast(over, types[k], ANSI))),
            types[k].sql() + " " + over.dataType().sql());
      }
      var over = column("over", DecimalType.apply(held[k] + 3, 2));
      // LEGACY wraps and TRY answers null, so neither raises.
      assertFalse(VarkaConjunctOrder.canRaise(new IsNotNull(cast(over, types[k], LEGACY))));
      assertFalse(VarkaConjunctOrder.canRaise(
          new IsNotNull(cast(over, types[k], EvalMode$.MODULE$.TRY()))));
    }
    assertFalse(VarkaConjunctOrder.canRaise(castIn(fraction)));
    assertTrue(VarkaConjunctOrder.canRaise(castIn(wide)));
    // A decimal to a decimal is not a truncation into an integer type.
    assertTrue(VarkaConjunctOrder.canRaise(new IsNotNull(
        cast(wide, DecimalType.apply(5, 0), ANSI))));
  }

  @Test
  @DisplayName("a residual decimal cast that cannot raise leaves the date conjunct after it fused, "
      + "with a column or a literal offset, and one that can raise demotes it")
  void castInBeforeDateAdd() {
    for (Expression offset : List.of(i, lit(3, IntegerType$.MODULE$))) {
      assertEquals(List.of(false, true), fused(and(castIn(fraction), dateAddAfter2000(offset))));
      assertEquals(List.of(true, false), fused(and(dateAddAfter2000(offset), castIn(fraction))));
      // date_add(d, offset) is NULL on a null d, where Spark goes on to the cast and raises, so it
      // stays fused only behind the null tests of what it reads.
      assertEquals(List.of(false, false), fused(and(dateAddAfter2000(offset), castIn(wide))));
      var tested = new ArrayList<Expression>(List.of(new IsNotNull(d)));
      if (offset instanceof Attribute column) {
        tested.add(new IsNotNull(column));
      }
      tested.add(dateAddAfter2000(offset));
      tested.add(castIn(wide));
      assertEquals(List.of(true, true, true, false).subList(4 - tested.size(), 4),
          fused(and(tested.toArray(new Expression[0]))));
      Expression raising = and(castIn(wide), dateAddAfter2000(offset));
      assertEquals(List.of(false, false), fused(raising));
      var spec = VarkaExpressionCompiler.explainPredicate(raising, output,
          VarkaEmitOptions.DEFAULTS, VarkaAdmission.NO_HOST_REFUSAL).get(1);
      assertTrue(spec.decline().orElseThrow().reason().contains(VarkaConjunctOrder.OUT_OF_ORDER),
          spec.toString());
      Expression bigint = and(new In(cast(digits19, LongType$.MODULE$, ANSI),
          seq(lit(0L, LongType$.MODULE$), lit(7L, LongType$.MODULE$))), dateAddAfter2000(offset));
      assertEquals(List.of(false, false), fused(bigint));
    }
  }

  @Test
  @DisplayName("the fallback's condition is folded in codegen order")
  void inCodegenOrder() {
    Expression folded =
        VarkaConjunctOrder.inCodegenOrder(and(new IsNotNull(d), sumOfL(ANSI)), output, false);
    assertTrue(folded.semanticEquals(and(sumOfL(ANSI), new IsNotNull(d))), folded.toString());
  }

  @Test
  @DisplayName("under the legacy NULL rule the fallback's fold stops at a NULL step: every step "
      + "but a null test is wrapped")
  void inCodegenOrderStopsAtNull() {
    Expression folded =
        VarkaConjunctOrder.inCodegenOrder(and(new IsNotNull(d), sumOfL(ANSI)), output, true);
    Expression expected = and(
        new Coalesce(seq(sumOfL(ANSI), Literal.FalseLiteral())), new IsNotNull(d));
    assertTrue(folded.semanticEquals(expected), folded.toString());
  }

  /** {@code NOT (year(d) = 2021 AND i > 0)}: NULL on a row in 2021 whose {@code i} is null. */
  private Expression notBoth() {
    return new Not(new And(new EqualTo(new Year(d), lit(2021, IntegerType$.MODULE$)),
        new GreaterThan(i, lit(0, IntegerType$.MODULE$))));
  }

  /** {@code s = 'x'}: a residual conjunct that cannot raise. */
  private Expression isX() {
    return new EqualTo(s, lit("x", StringType$.MODULE$));
  }

  /** The conjunct indices of {@code order}, without the null checks codegen adds. */
  private static List<Integer> conjunctsOf(List<VarkaConjunctOrder.Step> order) {
    var indices = new ArrayList<Integer>();
    for (VarkaConjunctOrder.Step step : order) {
      if (step instanceof VarkaConjunctOrder.Conjunct(int index)) {
        indices.add(index);
      }
    }
    return indices;
  }

  /** The conjunct indices of codegen's order of {@code conjuncts}, by default and in legacy. */
  private List<List<Integer>> bothOrders(Expression... conjuncts) {
    return List.of(
        conjunctsOf(VarkaConjunctOrder.codegenOrder(List.of(conjuncts), output, false)),
        conjunctsOf(VarkaConjunctOrder.codegenOrder(List.of(conjuncts), output, true)));
  }

  @Test
  @DisplayName("after a NULL conjunct codegen places the null tests written before a raising "
      + "conjunct ahead of it, and the legacy order keeps them where it reads their column")
  void precedingNullTests() {
    // SPARK-60315's `x IS NOT NULL AND NOT (a > 0 AND b > 0) AND y + 1 > 0`: the null test
    // nothing reads comes before the sum by default and last in legacy.
    assertEquals(List.of(List.of(1, 0, 2), List.of(1, 2, 0)),
        bothOrders(new IsNotNull(x), notBoth(), sumOfL(ANSI)));
    // `... AND y + 1 > 0 AND x > 0` with the inferred `isnotnull(x)` first: by default before
    // the sum, in legacy before `x > 0`, the conjunct that reads x.
    Expression xPositive = new GreaterThan(x, lit(0.0, DoubleType$.MODULE$));
    assertEquals(List.of(List.of(1, 0, 2, 3), List.of(1, 2, 0, 3)),
        bothOrders(new IsNotNull(x), notBoth(), sumOfL(ANSI), xPositive));
    // A null test that may raise, written first, comes before the conjunct after the NULL one
    // even when that conjunct cannot raise (the upstream `cast(s AS INT) IS NOT NULL` case).
    assertEquals(List.of(List.of(1, 0, 2), List.of(1, 2, 0)),
        bothOrders(new IsNotNull(sumOfLAndL2(ANSI)), notBoth(), isX()));
  }

  @Test
  @DisplayName("codegen keeps the lazy placement when nothing after the NULL conjunct may raise, "
      + "or when no conjunct can be NULL")
  void noNullConjunctFlag() {
    // `s = 'x'` and the null test cannot raise: the evaluation after a NULL conjunct is not
    // observable, and the order is the legacy one.
    assertEquals(List.of(List.of(1, 2, 0), List.of(1, 2, 0)),
        bothOrders(new IsNotNull(x), notBoth(), isX()));
    // With i and d tested, `NOT (year(d) = 2021 AND i > 0)` cannot be NULL, and the sum, NULL
    // on a null l, has nothing after it: the null test of x stays last.
    var orders = bothOrders(new IsNotNull(x), new IsNotNull(i), new IsNotNull(d), notBoth(),
        sumOfL(ANSI));
    assertEquals(orders.get(1), orders.get(0));
    assertEquals(List.of(3, 4, 0), orders.get(0).subList(2, 5), orders.toString());
  }

  @Test
  @DisplayName("after a NULL conjunct codegen leaves the null test of a larger expression written "
      + "after the conjunct containing it to the end")
  void nestedNullTestAfterNull() {
    // SPARK-60315's `coalesce(b, cast(s AS INT)) < 2 AND cast(s AS INT) IS NOT NULL` after a
    // NULL conjunct: by default the null test of l + l2 stays after the coalesce, as written;
    // the legacy order puts it before (SPARK-51356). Both check l and l2 before the coalesce.
    Expression sum = sumOfLAndL2(ANSI);
    Expression coalesce = new GreaterThanOrEqual(
        new Coalesce(seq(sum, lit(0L, LongType$.MODULE$))), lit(0L, LongType$.MODULE$));
    assertEquals(List.of(List.of(0, 1, 2), List.of(0, 2, 1)),
        bothOrders(notBoth(), coalesce, new IsNotNull(sum)));
    var order = VarkaConjunctOrder.codegenOrder(List.of(notBoth(), coalesce,
        new IsNotNull(sum)), output, false);
    assertEquals(5, order.size(), order.toString());
    assertTrue(order.get(1) instanceof VarkaConjunctOrder.NullCheck
        && order.get(2) instanceof VarkaConjunctOrder.NullCheck, order.toString());
  }

  @Test
  @DisplayName("a fused conjunct that can be NULL before a raising residual one is demoted, with "
      + "the reason, since Spark goes on past a NULL conjunct")
  void nullableAhead() {
    Expression condition = and(notBoth(), productOfL(ANSI));
    assertEquals(List.of(false, false), fused(condition));
    var spec = VarkaExpressionCompiler.explainPredicate(condition, output,
        VarkaEmitOptions.DEFAULTS, VarkaAdmission.NO_HOST_REFUSAL).get(0);
    String reason = spec.decline().orElseThrow().reason();
    assertTrue(reason.contains("can be NULL") && reason.contains(VarkaConjunctOrder.OUT_OF_ORDER),
        reason);
    // Without a raising residual conjunct the NULL row is only dropped, fused or not.
    assertEquals(List.of(true, false), fused(and(notBoth(), isX())));
  }

  @Test
  @DisplayName("each of Spark's three orders demotes a fused conjunct the other two keep")
  void eachOrderAlone() {
    // Written alone: the null tests after the sum run before it only in codegen's orders, which
    // place them before the conjunct reading their columns; codegen off evaluates the sum on the
    // row whose i is null.
    List<Expression> written = List.of(notBoth(), sumOfL(ANSI), new IsNotNull(i),
        new IsNotNull(d));
    assertEquals(List.of(0), List.copyOf(VarkaConjunctOrder.behindRaising(written,
        List.of(true, false, false, false), output).keySet()));
    for (List<Integer> order : bothOrders(written.toArray(new Expression[0]))) {
      assertEquals(List.of(0, 1), order.subList(2, 4), order.toString());
    }
    // Codegen alone: a null test written first runs after the product, which does not read d.
    List<Expression> codegen = List.of(new IsNotNull(d), productOfL(ANSI));
    assertEquals(List.of(0), List.copyOf(VarkaConjunctOrder.behindRaising(codegen,
        List.of(true, false), output).keySet()));
    // Legacy alone: by default the null test of x comes before the sum, after the NULL conjunct,
    // as written; in legacy codegen nothing reads x, so it comes last, behind the sum.
    List<Expression> legacy = List.of(new IsNotNull(x), notBoth(), sumOfL(ANSI));
    assertEquals(List.of(0), List.copyOf(VarkaConjunctOrder.behindRaising(legacy,
        List.of(true, false, false), output).keySet()));
    assertEquals(List.of(1, 0, 2),
        conjunctsOf(VarkaConjunctOrder.codegenOrder(legacy, output, false)));
    // No case demotes by the default codegen order alone: it places a null test no later than the
    // legacy order, and the conjuncts that are not null tests as written (VARKA-386.md 10.2).
  }

  @Test
  @DisplayName("a residual conjunct that can raise demotes every fused conjunct Spark evaluates "
      + "after it, with the reason")
  void raisingFirst() {
    Expression condition = and(productOfL(ANSI), beforeJune());
    assertEquals(List.of(false, false), fused(condition));
    var spec = VarkaExpressionCompiler.explainPredicate(condition, output,
        VarkaEmitOptions.DEFAULTS, VarkaAdmission.NO_HOST_REFUSAL).get(1);
    assertTrue(spec.decline().orElseThrow().reason().contains(VarkaConjunctOrder.OUT_OF_ORDER),
        spec.toString());
    assertTrue(VarkaExpressionCompiler.compilePredicate(condition, output,
        VarkaEmitOptions.DEFAULTS, VarkaAdmission.NO_HOST_REFUSAL).isEmpty());
  }

  @Test
  @DisplayName("a fused conjunct written before the raising residual one stays fused when it "
      + "cannot be NULL there, and the one after it is demoted")
  void raisingBetween() {
    // The null test of d is what the optimizer infers for a comparison over d; without it the
    // comparison could be NULL ahead of the product, and is demoted (nullableAhead).
    assertEquals(List.of(true, true, false, false),
        fused(and(new IsNotNull(d), afterFebruary(), productOfL(ANSI), beforeJune())));
    assertEquals(List.of(false, false, false),
        fused(and(afterFebruary(), productOfL(ANSI), beforeJune())));
  }

  @Test
  @DisplayName("a null test written first is demoted when codegen evaluates it after the "
      + "raising conjunct")
  void nullTestAfterRaising() {
    assertEquals(List.of(false, false), fused(and(new IsNotNull(d), productOfL(ANSI))));
  }

  @Test
  @DisplayName("a residual conjunct that cannot raise demotes nothing, a TRY product's "
      + "arithmetic among them")
  void safeResidual() {
    assertEquals(List.of(false, true),
        fused(and(new EqualTo(s, lit("x", StringType$.MODULE$)), beforeJune())));
    assertEquals(List.of(false, true),
        fused(and(productOfL(EvalMode$.MODULE$.TRY()), beforeJune())));
  }
}
