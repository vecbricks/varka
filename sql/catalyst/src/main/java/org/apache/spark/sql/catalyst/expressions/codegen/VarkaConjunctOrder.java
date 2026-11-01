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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.And;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BinaryComparison;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.CaseWhen;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Coalesce;
import org.apache.spark.sql.catalyst.expressions.Contains;
import org.apache.spark.sql.catalyst.expressions.DateAdd;
import org.apache.spark.sql.catalyst.expressions.DateDiff;
import org.apache.spark.sql.catalyst.expressions.DateSub;
import org.apache.spark.sql.catalyst.expressions.EndsWith;
import org.apache.spark.sql.catalyst.expressions.EvalMode$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GetDateField;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.In;
import org.apache.spark.sql.catalyst.expressions.InSet;
import org.apache.spark.sql.catalyst.expressions.IsNaN;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.IsNull;
import org.apache.spark.sql.catalyst.expressions.KnownNotNull;
import org.apache.spark.sql.catalyst.expressions.Length;
import org.apache.spark.sql.catalyst.expressions.Like;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Lower;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.Not;
import org.apache.spark.sql.catalyst.expressions.Or;
import org.apache.spark.sql.catalyst.expressions.StartsWith;
import org.apache.spark.sql.catalyst.expressions.Substring;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.Upper;
import org.apache.spark.sql.types.ByteType$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.DoubleType$;
import org.apache.spark.sql.types.FloatType$;
import org.apache.spark.sql.types.IntegerType$;
import org.apache.spark.sql.types.IntegralType;
import org.apache.spark.sql.types.LongType$;
import org.apache.spark.sql.types.NumericType;
import org.apache.spark.sql.types.ShortType$;
import org.apache.spark.sql.types.StringType;

/**
 * Which fused conjuncts of a split filter would change the errors Spark raises (VARKA-380).
 *
 * <p>A split filter runs its fused conjuncts in the Varka node below and its residual conjuncts in
 * a row {@code FilterExec} above, so the residual ones see only the rows every fused conjunct
 * kept. Spark evaluates the conjuncts in its own order and stops at the first false one. Where a
 * residual conjunct that can raise comes before a fused one in Spark's order, a row the fused
 * conjunct rejects never reaches it: Spark raises and Varka returns rows. Such a fused conjunct is
 * demoted to the residual filter, which then evaluates it in Spark's order with the rest.
 *
 * <p>Spark has two orders, and the check holds a fused conjunct to both. The interpreted
 * predicate evaluates the {@code AND} tree as written. Whole-stage codegen
 * ({@code FilterExec.generatePredicateCode}) evaluates the other conjuncts as written but puts
 * each {@code IsNotNull} conjunct just before the first of them that reads its column, the ones
 * nothing reads at the end, and, before a conjunct reading a column that an {@code IsNotNull} of a
 * larger expression implies is not null ({@code isnotnull(l + 1)} implies {@code l}), a null check
 * of that column of its own. So an {@code isnotnull(l)} written first still runs after a raising
 * conjunct that does not read {@code l}. It also means the conjuncts decide where those null
 * checks run, and the residual filter, which orders its own conjuncts the same way, places them
 * by the residual conjuncts alone: a fused conjunct that put a null check ahead of a raising
 * residual conjunct is demoted too, unless the fused conjuncts drop the same null rows anyway.
 * {@link #codegenOrder} is a port of that algorithm and has to follow it.
 *
 * <p>Whether a conjunct can raise is answered conservatively by {@link #canRaise}: a conjunct is
 * error-free only when every node in it is one of a short list of expressions that never throw in
 * the mode they carry. Anything else counts as raising, which costs fusion and never an answer.
 *
 * <p>Internal to Spark SQL; public for {@code sql/core}, whose filter fallback evaluates the
 * condition in codegen order ({@link #inCodegenOrder}).
 */
public final class VarkaConjunctOrder {

  private VarkaConjunctOrder() {
  }

  /**
   * The phrase every reason of this rule carries: a demotion here is a decline of composition,
   * caused by the other conjuncts, which a suite tells from a decline of the conjunct itself.
   */
  public static final String OUT_OF_ORDER = "out of Spark's order";

  /** One step of codegen's order: a conjunct, or a null check codegen adds of a column. */
  sealed interface Step {
  }

  /** The conjunct of this index in the written order. */
  record Conjunct(int index) implements Step {
  }

  /** A null check of {@code column} that no conjunct writes, implied by a larger null test. */
  record NullCheck(Attribute column) implements Step {
  }

  /**
   * The fused conjuncts to demote so that no fused conjunct changes which errors Spark's orders
   * raise, by index, each with the reason its spec reports. Empty when the split is safe. The
   * demotions are iterated here to their fixed point - a demoted conjunct that can raise moves
   * the line again - so the compiler classifies once more, not once per demotion.
   *
   * @param conjuncts the condition's {@code AND} spine in written order
   * @param fused whether each conjunct fused, by the same index
   */
  static Map<Integer, String> behindRaising(List<Expression> conjuncts, List<Boolean> fused) {
    int size = conjuncts.size();
    var raises = new boolean[size];
    var isFused = new boolean[size];
    boolean anyResidualRaises = false;
    for (int index = 0; index < size; index++) {
      isFused[index] = fused.get(index);
      raises[index] = canRaise(conjuncts.get(index));
      anyResidualRaises |= raises[index] && !isFused[index];
    }
    Map<Integer, String> late = new LinkedHashMap<>();
    if (!anyResidualRaises) {
      return late;
    }
    var written = new ArrayList<Integer>(size);
    for (int index = 0; index < size; index++) {
      written.add(index);
    }
    var codegen = new ArrayList<Integer>(size);
    for (Step step : codegenOrder(conjuncts)) {
      if (step instanceof Conjunct(int index)) {
        codegen.add(index);
      }
    }
    boolean changed = true;
    while (changed) {
      // Both orders demote in every round, so neither call may be skipped.
      boolean byWritten = demoteBehind(written, conjuncts, raises, isFused, late);
      boolean byCodegen = demoteBehind(codegen, conjuncts, raises, isFused, late);
      changed = byWritten || byCodegen;
      if (!changed) {
        changed = demoteNullCheckMovers(conjuncts, raises, isFused, late);
      }
    }
    return late;
  }

  /** Demotes the fused conjuncts {@code order} places after its first raising residual one. */
  private static boolean demoteBehind(List<Integer> order, List<Expression> conjuncts,
      boolean[] raises, boolean[] isFused, Map<Integer, String> late) {
    Expression raising = null;
    boolean changed = false;
    for (int index : order) {
      if (raising != null && isFused[index]) {
        isFused[index] = false;
        late.putIfAbsent(index, "Spark evaluates it after " + raising.sql() + ", which can "
            + "raise an error; fused, it would run " + OUT_OF_ORDER + " and drop the rows that "
            + "raise it");
        changed = true;
      } else if (raising == null && !isFused[index] && raises[index]) {
        raising = conjuncts.get(index);
      }
    }
    return changed;
  }

  /**
   * Demotes the fused conjuncts that decide whether a null check runs before a raising residual
   * conjunct in codegen order. For each raising residual conjunct, the rows Spark lets reach it
   * are those every step before it in the full codegen order keeps; on Varka they are those every
   * fused conjunct and every step before it in the residual filter's own codegen order keeps. With
   * {@link #demoteBehind} done, the two sets of steps differ only in null tests and null checks.
   * A difference is harmless when the other side drops the same null rows anyway, and otherwise
   * the fused conjuncts that pulled the null test into place in the full order are demoted, so the
   * residual filter pulls it into the same place.
   */
  private static boolean demoteNullCheckMovers(List<Expression> conjuncts, boolean[] raises,
      boolean[] isFused, Map<Integer, String> late) {
    var residual = new ArrayList<Integer>();
    var residualConjuncts = new ArrayList<Expression>();
    var fusedSteps = new ArrayList<Step>();
    for (int index = 0; index < conjuncts.size(); index++) {
      if (isFused[index]) {
        fusedSteps.add(new Conjunct(index));
      } else {
        residual.add(index);
        residualConjuncts.add(conjuncts.get(index));
      }
    }
    List<Step> full = codegenOrder(conjuncts);
    var residualOrder = new ArrayList<Step>();
    for (Step step : codegenOrder(residualConjuncts)) {
      residualOrder.add(switch (step) {
        case Conjunct(int index) -> new Conjunct(residual.get(index));
        case NullCheck n -> n;
      });
    }
    boolean changed = false;
    boolean unexplained = false;
    for (int r : residual) {
      if (!raises[r]) {
        continue;
      }
      List<Step> onSpark = before(full, r);
      List<Step> onVarka = new ArrayList<>(fusedSteps);
      onVarka.addAll(before(residualOrder, r));
      for (List<List<Step>> sides : List.of(List.of(onSpark, onVarka),
          List.of(onVarka, onSpark))) {
        for (Step step : sides.get(0)) {
          if (holds(sides.get(1), step)) {
            continue;
          }
          Expression tested = nullTestOf(step, conjuncts);
          if (tested == null) {
            unexplained = true;
            continue;
          }
          if (dropsNullsOf(sides.get(1), tested, conjuncts)) {
            continue;
          }
          boolean demoted = false;
          for (int f = 0; f < conjuncts.size(); f++) {
            if (isFused[f] && nullTested(conjuncts.get(f)) == null
                && pulls(conjuncts.get(f), tested)) {
              isFused[f] = false;
              late.putIfAbsent(f, "it decides whether Spark tests " + tested.sql()
                  + " for null before " + conjuncts.get(r).sql() + ", which can raise an error; "
                  + "fused, it would leave the residual filter to test it " + OUT_OF_ORDER);
              demoted = true;
            }
          }
          changed |= demoted;
          unexplained |= !demoted;
        }
      }
    }
    if (unexplained && !changed) {
      // Unreachable while codegenOrder ports Spark's algorithm: the two sides can differ only in
      // null tests, and the fused conjuncts that pull one are what moves it. Should the port
      // drift, nothing stays fused, under a reason no suite takes for a composition decline.
      for (int f = 0; f < conjuncts.size(); f++) {
        if (isFused[f]) {
          isFused[f] = false;
          late.putIfAbsent(f, "the residual filter would order its conjuncts unlike Spark's "
              + "codegen, which VarkaConjunctOrder.codegenOrder no longer follows");
          changed = true;
        }
      }
    }
    return changed;
  }

  /** The steps of {@code order} before the conjunct {@code index}. */
  private static List<Step> before(List<Step> order, int index) {
    var steps = new ArrayList<Step>();
    for (Step step : order) {
      if (step instanceof Conjunct(int i) && i == index) {
        break;
      }
      steps.add(step);
    }
    return steps;
  }

  /** Whether {@code steps} holds {@code step}: the same conjunct, or a check of the same column. */
  private static boolean holds(List<Step> steps, Step step) {
    for (Step other : steps) {
      boolean same = switch (step) {
        case Conjunct(int index) -> other instanceof Conjunct(int i) && i == index;
        case NullCheck(Attribute column) ->
            other instanceof NullCheck(Attribute c) && c.semanticEquals(column);
      };
      if (same) {
        return true;
      }
    }
    return false;
  }

  /** What {@code step} tests for null, or null when it is a conjunct of another kind. */
  private static @Nullable Expression nullTestOf(Step step, List<Expression> conjuncts) {
    return switch (step) {
      case Conjunct(int index) -> nullTested(conjuncts.get(index));
      case NullCheck(Attribute column) -> column;
    };
  }

  /** Whether one of {@code steps} drops every row on which {@code tested} is null. */
  private static boolean dropsNullsOf(List<Step> steps, Expression tested,
      List<Expression> conjuncts) {
    for (Step step : steps) {
      boolean drops = switch (step) {
        case Conjunct(int index) -> {
          Expression conjunct = conjuncts.get(index);
          yield nullWhenNull(conjunct, tested)
              || (conjunct instanceof IsNotNull n && nullWhenNull(n.child(), tested));
        }
        case NullCheck(Attribute column) -> column.semanticEquals(tested);
      };
      if (drops) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code expr} is null on every row where {@code tested} is. */
  private static boolean nullWhenNull(Expression expr, Expression tested) {
    if (expr.semanticEquals(tested)) {
      return true;
    }
    if (!expr.nullIntolerant()) {
      return false;
    }
    for (Expression child : CollectionConverters.asJava(expr.children())) {
      if (nullWhenNull(child, tested)) {
        return true;
      }
    }
    return false;
  }

  /** The tested expression of a null test codegen moves, or null for any other conjunct. */
  private static @Nullable Expression nullTested(Expression conjunct) {
    return conjunct instanceof IsNotNull n && isNullIntolerant(n.child()) ? n.child() : null;
  }

  /** Whether codegen places the null test of {@code tested} just before {@code conjunct}. */
  private static boolean pulls(Expression conjunct, Expression tested) {
    if (tested instanceof Attribute column) {
      return references(conjunct, column);
    }
    return conjunct.exists(e -> e.semanticEquals(tested));
  }

  private static boolean references(Expression conjunct, Attribute column) {
    for (Attribute reference : CollectionConverters.asJava(conjunct.references().toSeq())) {
      if (column.semanticEquals(reference)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code condition} with its conjuncts, and the null checks codegen adds, in the order
   * whole-stage codegen evaluates them, for the per-row fallback of a Varka filter, whose
   * generated predicate evaluates an {@code AND} tree as written: folded in this order, it stops
   * where the codegen filter stops, and raises what it raises.
   */
  public static Expression inCodegenOrder(Expression condition) {
    List<Expression> conjuncts = VarkaExpressionCompiler.conjuncts(condition);
    @Nullable Expression folded = null;
    for (Step step : codegenOrder(conjuncts)) {
      Expression next = switch (step) {
        case Conjunct(int index) -> conjuncts.get(index);
        case NullCheck(Attribute column) -> new IsNotNull(column);
      };
      folded = folded == null ? next : new And(folded, next);
    }
    // A condition has at least one conjunct, so the fold has at least one step.
    return Objects.requireNonNull(folded);
  }

  /**
   * The order {@code FilterExec.generatePredicateCode} evaluates the conjuncts in: each conjunct
   * that is not a null test as written, preceded by the null tests of the columns it reads, the
   * null checks codegen adds of the columns a larger null test implies, and the null tests of the
   * larger expressions it contains; then the null tests left over.
   */
  static List<Step> codegenOrder(List<Expression> conjuncts) {
    var notNull = new ArrayList<Integer>();
    var other = new ArrayList<Integer>();
    var implied = new ArrayList<Attribute>();
    for (int index = 0; index < conjuncts.size(); index++) {
      Expression tested = nullTested(conjuncts.get(index));
      if (tested != null) {
        notNull.add(index);
        implied.addAll(CollectionConverters.asJava(tested.references().toSeq()));
      } else {
        other.add(index);
      }
    }
    var placed = new boolean[conjuncts.size()];
    var checked = new ArrayList<Attribute>();
    var order = new ArrayList<Step>(conjuncts.size());
    for (int c : other) {
      Expression conjunct = conjuncts.get(c);
      // Spark looks up the first null test of each column the conjunct reads, and places it
      // unless an earlier conjunct did; failing that, a column a null test implies gets a check
      // of its own, once.
      for (Attribute reference : CollectionConverters.asJava(conjunct.references().toSeq())) {
        int found = -1;
        for (int n : notNull) {
          // Every index in notNull has a tested expression.
          if (Objects.requireNonNull(nullTested(conjuncts.get(n))).semanticEquals(reference)) {
            found = n;
            break;
          }
        }
        if (found != -1 && !placed[found]) {
          placed[found] = true;
          order.add(new Conjunct(found));
        } else if (containsColumn(implied, reference) && !containsColumn(checked, reference)) {
          checked.add(reference);
          order.add(new NullCheck(reference));
        }
      }
      for (int n : notNull) {
        Expression tested = Objects.requireNonNull(nullTested(conjuncts.get(n)));
        if (!placed[n] && !(tested instanceof Attribute) && pulls(conjunct, tested)) {
          placed[n] = true;
          order.add(new Conjunct(n));
        }
      }
      order.add(new Conjunct(c));
    }
    for (int n : notNull) {
      if (!placed[n]) {
        order.add(new Conjunct(n));
      }
    }
    return order;
  }

  private static boolean containsColumn(List<Attribute> columns, Attribute column) {
    for (Attribute c : columns) {
      if (c.exprId().equals(column.exprId())) {
        return true;
      }
    }
    return false;
  }

  /** {@code PredicateHelper.isNullIntolerant}: null in, null out, all the way down. */
  private static boolean isNullIntolerant(Expression expr) {
    if (!expr.nullIntolerant()) {
      return false;
    }
    for (Expression child : CollectionConverters.asJava(expr.children())) {
      if (!isNullIntolerant(child)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether evaluating {@code expr} on some row can throw. False only when every node is on the
   * list below; the list is what residual conjuncts beside fused ones usually are - comparisons,
   * null tests, {@code IN}, string matches, arithmetic that cannot overflow in its mode, and casts
   * that widen or truncate a decimal into an integer type that holds it - and anything off it is
   * assumed to raise.
   */
  static boolean canRaise(Expression expr) {
    if (!errorFreeNode(expr)) {
      return true;
    }
    for (Expression child : CollectionConverters.asJava(expr.children())) {
      if (canRaise(child)) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code expr} itself, given children that do not throw, cannot throw. */
  private static boolean errorFreeNode(Expression expr) {
    return switch (expr) {
      case Attribute _, BoundReference _, Literal _ -> true;
      case IsNull _, IsNotNull _, KnownNotNull _, Not _, And _, Or _ -> true;
      case BinaryComparison _, In _, InSet _ -> true;
      case If _, CaseWhen _, Coalesce _ -> true;
      case StartsWith _, EndsWith _, Contains _, Upper _, Lower _, Length _, Substring _ -> true;
      case IsNaN _, DateAdd _, DateSub _, DateDiff _ -> true;
      // year(d), month(d) and the other fields of a date read a day count every date has.
      case GetDateField _ -> true;
      // A pattern that does not parse raises, so only a constant one, which Spark checks when it
      // generates the code - before any row, on Varka's plan as on its own.
      case Like l -> l.right().foldable();
      // Year-month and day-time interval arithmetic raises on overflow in every mode, and decimal
      // arithmetic is left off rather than reasoned about, so only the plain numeric types pass.
      case Add a -> arithmeticCannotOverflow(a.evalMode(), a.dataType());
      case Subtract a -> arithmeticCannotOverflow(a.evalMode(), a.dataType());
      case Multiply a -> arithmeticCannotOverflow(a.evalMode(), a.dataType());
      case UnaryMinus n -> arithmeticCannotOverflow(
          n.failOnError() ? EvalMode$.MODULE$.ANSI() : EvalMode$.MODULE$.LEGACY(), n.dataType());
      case Cast c -> wideningCast(c.child().dataType(), c.dataType())
          || decimalTruncationFits(c.evalMode(), c.child().dataType(), c.dataType());
      default -> false;
    };
  }

  /**
   * Whether {@code +}, {@code -}, {@code *} or a negation in {@code mode} over {@code type} cannot
   * throw: floating point never does, and integers do only under ANSI. Division and remainder
   * stay off the list, since a zero divisor raises under ANSI whatever the type.
   */
  private static boolean arithmeticCannotOverflow(Object mode, DataType type) {
    if (type.equals(DoubleType$.MODULE$) || type.equals(FloatType$.MODULE$)) {
      return true;
    }
    return type instanceof IntegralType && !EvalMode$.MODULE$.ANSI().equals(mode);
  }

  /**
   * A decimal cast to an integer type that cannot raise. Outside ANSI none raises: LEGACY wraps
   * ({@code Decimal.toInt} and its siblings) and TRY answers null. Under ANSI the cast truncates
   * toward zero and raises only on a value whose integer part the type cannot hold
   * ({@code Decimal.roundToInt} and its siblings), and a {@code DECIMAL(p, s)} has at most
   * {@code p - s} integer digits: {@code CAST(a AS INT)} of a {@code DECIMAL(18, 18)} is always 0,
   * though {@code Cast.canUpCast} says no, since the cast drops the fraction.
   */
  private static boolean decimalTruncationFits(Object mode, DataType from, DataType to) {
    if (!(from instanceof DecimalType decimal) || !(to instanceof IntegralType)) {
      return false;
    }
    if (!EvalMode$.MODULE$.ANSI().equals(mode)) {
      return true;
    }
    int held;
    if (to.equals(ByteType$.MODULE$)) {
      held = 2;
    } else if (to.equals(ShortType$.MODULE$)) {
      held = 4;
    } else if (to.equals(IntegerType$.MODULE$)) {
      held = 9;
    } else if (to.equals(LongType$.MODULE$)) {
      held = 18;
    } else {
      return false;
    }
    return decimal.precision() - decimal.scale() <= held;
  }

  /**
   * A cast that only widens: a number to a wider number, or anything to a string. A date to a
   * timestamp is a widening Spark allows but not one that cannot raise: the microseconds of a
   * date past the year 294247 overflow a long.
   */
  private static boolean wideningCast(DataType from, DataType to) {
    if (!Cast.canUpCast(from, to)) {
      return false;
    }
    return (from instanceof NumericType && to instanceof NumericType)
        || (to instanceof StringType && !(from instanceof StringType));
  }
}
