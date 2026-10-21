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
import org.apache.spark.sql.catalyst.expressions.Divide;
import org.apache.spark.sql.catalyst.expressions.EqualNullSafe;
import org.apache.spark.sql.catalyst.expressions.EqualTo;
import org.apache.spark.sql.catalyst.expressions.EvalMode$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GreaterThan;
import org.apache.spark.sql.catalyst.expressions.GreaterThanOrEqual;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.LessThan;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.NamedExpression$;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext$;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DateType$;
import org.apache.spark.sql.types.DoubleType$;
import org.apache.spark.sql.types.IntegerType$;
import org.apache.spark.sql.types.LongType$;
import org.apache.spark.sql.types.Metadata;
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
  private final Seq<Attribute> output = seq(d, i, l, l2, s, x, ym);

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

  /** {@code (l + 1) > 0} in {@code mode}: BIGINT arithmetic, which the compiler leaves residual. */
  private Expression sumOfL(Enumeration.Value mode) {
    return new GreaterThan(new Add(l, lit(1L, LongType$.MODULE$), ctx(mode)),
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

  /** Which conjuncts fused, in written order. */
  private List<Boolean> fused(Expression condition) {
    var flags = new ArrayList<Boolean>();
    for (VarkaConjunctSpec spec : VarkaExpressionCompiler.explainPredicate(condition, output)) {
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
    assertEquals(steps(1, 2, 0, 3), VarkaConjunctOrder.codegenOrder(conjuncts));
    // A null test nothing reads goes to the end.
    List<Expression> unread = List.of(new IsNotNull(i), sumOfL(ANSI));
    assertEquals(steps(1, 0), VarkaConjunctOrder.codegenOrder(unread));
  }

  @Test
  @DisplayName("codegen checks a column that a larger null test implies before the first "
      + "conjunct that reads it")
  void impliedNullCheck() {
    // isnotnull(l + l2) implies l is not null, so codegen checks l before coalesce(l, 0) >= 0.
    List<Expression> conjuncts = List.of(new IsNotNull(sumOfLAndL2(LEGACY)), coalesceL());
    var order = VarkaConjunctOrder.codegenOrder(conjuncts);
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
        List.of(new IsNotNull(new EqualTo(l, l2)), coalesceL()));
    assertEquals(3, implied.size(), implied.toString());
    assertTrue(implied.get(0) instanceof VarkaConjunctOrder.NullCheck, implied.toString());
    // isnotnull(l <=> l2) is true whatever the columns are, so no column is checked ahead of
    // the conjunct that reads it, and the order is the written one.
    var none = VarkaConjunctOrder.codegenOrder(
        List.of(new IsNotNull(new EqualNullSafe(l, l2)), coalesceL()));
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
        List.copyOf(VarkaConjunctOrder.behindRaising(conjuncts, List.of(true, false, false))
            .keySet()));
    // With the null test fused beside it nothing moves.
    assertTrue(VarkaConjunctOrder.behindRaising(conjuncts, List.of(true, true, false)).isEmpty());
    // A puller that is null when its column is drops the same rows itself: nothing moves.
    List<Expression> intolerant = List.of(new GreaterThan(l2, lit(0L, LongType$.MODULE$)),
        new IsNotNull(l2), sumOfL(ANSI));
    assertTrue(VarkaConjunctOrder.behindRaising(intolerant, List.of(true, false, false))
        .isEmpty());
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
        List.copyOf(VarkaConjunctOrder.behindRaising(conjuncts, List.of(true, false, false))
            .keySet()));
  }

  @Test
  @DisplayName("the fallback's condition is folded in codegen order")
  void inCodegenOrder() {
    Expression folded = VarkaConjunctOrder.inCodegenOrder(and(new IsNotNull(d), sumOfL(ANSI)));
    assertTrue(folded.semanticEquals(and(sumOfL(ANSI), new IsNotNull(d))), folded.toString());
  }

  @Test
  @DisplayName("a residual conjunct that can raise demotes every fused conjunct Spark evaluates "
      + "after it, with the reason")
  void raisingFirst() {
    Expression condition = and(sumOfL(ANSI), beforeJune());
    assertEquals(List.of(false, false), fused(condition));
    var spec = VarkaExpressionCompiler.explainPredicate(condition, output).get(1);
    assertTrue(spec.decline().orElseThrow().reason().contains(VarkaConjunctOrder.OUT_OF_ORDER),
        spec.toString());
    assertTrue(VarkaExpressionCompiler.compilePredicate(condition, output).isEmpty());
  }

  @Test
  @DisplayName("a fused conjunct written before the raising residual one stays fused, and the "
      + "one after it is demoted")
  void raisingBetween() {
    assertEquals(List.of(true, false, false),
        fused(and(afterFebruary(), sumOfL(ANSI), beforeJune())));
  }

  @Test
  @DisplayName("a null test written first is demoted when codegen evaluates it after the "
      + "raising conjunct")
  void nullTestAfterRaising() {
    assertEquals(List.of(false, false), fused(and(new IsNotNull(d), sumOfL(ANSI))));
  }

  @Test
  @DisplayName("a residual conjunct that cannot raise demotes nothing, in either mode's "
      + "arithmetic")
  void safeResidual() {
    assertEquals(List.of(false, true),
        fused(and(new EqualTo(s, lit("x", StringType$.MODULE$)), beforeJune())));
    assertEquals(List.of(false, true), fused(and(sumOfL(LEGACY), beforeJune())));
  }
}
