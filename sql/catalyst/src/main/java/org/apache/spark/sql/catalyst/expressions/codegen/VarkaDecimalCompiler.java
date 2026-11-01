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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BinaryOperator;

import scala.Tuple2;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Abs;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.CaseWhen;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Coalesce;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Greatest;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.Least;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.RuntimeReplaceable;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondConst;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Truth;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;

/**
 * The decimal values a kernel computes on the long lane (VARKA-347 to VARKA-349): each is a
 * {@link Scaled}, an unscaled long node and the {@code DECIMAL(p, s)} it is a value of, built by
 * {@link #value}, which every decimal consumer calls.
 *
 * <p>Why a long compare is Spark's answer. Two values of one scale {@code s} are {@code u1 / 10^s}
 * and {@code u2 / 10^s}, so they order and compare equal exactly as their unscaled values do, and
 * {@code Decimal.compare} compares by value (the long fields where both are compact,
 * {@code BigDecimal.compareTo} otherwise, which ignores the representation). Two operands of two
 * scales never meet in a comparison Spark analysed: type coercion
 * ({@code DecimalPrecisionTypeCoercion.decimalAndDecimal}) casts both to the wider type
 * {@code (P, S)}, {@code S = max(s1, s2)}, keeping every integral digit, so the operand of the
 * smaller scale arrives under a cast that multiplies its unscaled value by {@code 10^(S - s)}.
 * {@link #rescale} is that multiply, exact because {@code |u| <= 10^p - 1} and
 * {@code p + k <= P <= 18} keep the product below {@code 10^18 < 2^63}
 * ({@code sql/varka/proofs/decimal64.smt2}).
 *
 * <p>The invariant every rule here reads is that a {@link Scaled}'s unscaled value is within its
 * type's precision on every valid lane: a column's by its leaf's slot check, which declines a
 * batch holding a valid slot outside it, a literal's by the test made when its slot is taken,
 * and a rescale's by the bound above.
 *
 * <p>Why {@code +}, {@code -} and {@code *} need no overflow check. Spark types a decimal sum or
 * difference {@code (P, S)} with {@code S = max(s1, s2)} and
 * {@code P = max(p1 - s1, p2 - s2) + S + 1}, a product {@code (p1 + p2 + 1, s1 + s2)}
 * ({@code DecimalType}'s {@code resultDecimalType} in each node), and never casts the operands.
 * Where {@code P <= 18} the exact result always fits the type: each operand rescaled to
 * {@code S} has at most {@code P - 1} digits, so their sum has at most {@code P}; a product of
 * values below {@code 10^p1} and {@code 10^p2} is below {@code 10^(P - 1)}. So Spark's precision
 * check never fires, LEGACY's null, ANSI's raise and TRY's null are dead, and one wrapping long
 * operation, which cannot wrap below {@code 10^18}, is Spark's answer in every mode
 * ({@code decimal64.smt2}). A wider result is a decline, never a check.
 *
 * <p>What declines, each naming its row: a type wider than 18 digits (row 359, and row 360 where
 * a rescale is needed); a sum or difference past 18 digits (row 360, and 363 past 38), a product
 * past 18 digits (row 361, and 362 and 363 past 38 or over an operand past 18); a cast that
 * lowers the scale or the integral digits, or one from an integral type (row 350); a negative
 * scale (the milestone plan's 2.8); and every operator no arm here claims yet.
 */
final class VarkaDecimalCompiler {

  /** The widest precision on the long lane, 18. */
  private static final int MAX_PRECISION = DecimalColumn.MAX_LONG_PRECISION;

  private VarkaDecimalCompiler() {
  }

  /**
   * A decimal value on the long lane: {@code unscaled} holds {@code u} per lane, the value being
   * {@code u / 10^type.scale()}, with {@code |u| <= 10^type.precision() - 1} on every valid lane.
   * A compile-time record, never an IR node: the scale lives here, and the kernel sees only the
   * unscaled longs.
   */
  record Scaled(VarkaVectorIR unscaled, DecimalType type) {
    Scaled {
      Objects.requireNonNull(unscaled, "unscaled");
      Objects.requireNonNull(type, "type");
      if (unscaled.laneType() != LaneType.LONG) {
        throw VarkaErrors.illegalArgument("a decimal value is on the long lane, not "
            + unscaled.laneType());
      }
      if (type.precision() < 1 || type.precision() > MAX_PRECISION || type.scale() < 0) {
        throw VarkaErrors.illegalArgument("a decimal value on the long lane has a precision "
            + "from 1 to " + MAX_PRECISION + " and a scale of at least 0, not " + type);
      }
    }

    /** The largest unscaled value of the type, {@code 10^precision - 1}. */
    long bound() {
      return Decimal.POW_10()[type.precision()] - 1L;
    }
  }

  /** Whether {@code e} is a decimal, which only this class compiles as an operand. */
  static boolean isDecimal(Expression e) {
    return e.dataType() instanceof DecimalType;
  }

  /**
   * The value of the decimal expression {@code e}, or empty with the reason noted. A literal tree
   * folds first, so a literal under coercion's cast takes its slot already at the cast's scale.
   */
  static Optional<Scaled> value(Expression e, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    if (!(e.dataType() instanceof DecimalType type)) {
      return sink.decline("a decimal operator over a " + e.dataType().simpleString()
          + " operand", e);
    }
    if (type.scale() < 0) {
      return sink.decline("a decimal of negative scale is declined everywhere (the milestone "
          + "plan's 2.8)", e);
    }
    if (isLiteralTree(e)) {
      return literal(e, type, sink);
    }
    return switch (e) {
      case BoundReference br -> column(br, type, inputs, sink);
      case Cast c -> cast(c, type, inputs, literals, sink);
      // Neither can fail or leave the precision, |-u| = |u| (VARKA-348 0.5), so both wrap: the
      // one overflowing long, Long.MIN_VALUE, is outside every decimal's bound.
      case UnaryMinus m -> value(m.child(), inputs, literals, sink)
          .map(x -> new Scaled(new IntNeg(Overflow.WRAP, x.unscaled()), x.type()));
      case Abs a -> value(a.child(), inputs, literals, sink)
          .map(x -> new Scaled(new IntNeg(Overflow.WRAP, x.unscaled(), true), x.type()));
      case If i -> choice(i, List.of(i.predicate()), List.of(i.trueValue()), i.falseValue(),
          type, inputs, literals, sink);
      case CaseWhen c when c.elseValue().isEmpty() -> sink.decline("a CASE without an ELSE "
          + "needs a typed null value, which is row 333's", c);
      case CaseWhen c -> choice(c, CollectionConverters.asJava(c.branches()).stream()
              .map(Tuple2::_1).toList(),
          CollectionConverters.asJava(c.branches()).stream().map(Tuple2::_2).toList(),
          c.elseValue().get(), type, inputs, literals, sink);
      case Coalesce c -> coalesce(c, type, inputs, literals, sink);
      case Greatest g -> pick(g, CollectionConverters.asJava(g.children()), type, inputs,
          literals, sink, VarkaVectorIR.Greatest::new);
      case Least l -> pick(l, CollectionConverters.asJava(l.children()), type, inputs,
          literals, sink, VarkaVectorIR.Least::new);
      // try_add, try_subtract and try_multiply over two numerics are Add, Subtract and Multiply
      // in TRY mode, which an analysed tree carries as their replacement.
      case RuntimeReplaceable r -> value(r.replacement(), inputs, literals, sink);
      case Add a -> sum(a, IntOp.ADD, a.left(), a.right(), type, inputs, literals, sink);
      case Subtract a -> sum(a, IntOp.SUB, a.left(), a.right(), type, inputs, literals, sink);
      case Multiply m -> product(m, type, inputs, literals, sink);
      default -> sink.decline("a decimal operator no arm builds yet: its rows are 348 to 351",
          e);
    };
  }

  /**
   * {@code x} at {@code k} more digits of scale: the identity at {@code k = 0}, a literal's
   * multiplied constant in a slot of its own, and otherwise a wrapping multiply by the slot
   * {@code 10^k}, which never wraps since {@code |u * 10^k| <= 10^(p + k) - 10^k < 2^63}. The
   * result is of type {@code (p + k, s + k)}. A rescale past 18 digits has no long form and is
   * refused by throwing: the callers check the type rule first.
   */
  static Scaled rescale(Scaled x, int k, DeclineSink sink) {
    int p = x.type().precision();
    if (k < 0 || p + k > MAX_PRECISION) {
      throw VarkaErrors.illegalArgument("a rescale of " + x.type() + " by " + k
          + " digits leaves the long lane");
    }
    if (k == 0) {
      return x;
    }
    var type = new DecimalType(p + k, x.type().scale() + k);
    long factor = Decimal.POW_10()[k];
    if (x.unscaled() instanceof LiteralSlot slot && slot.lane() == LaneType.LONG) {
      long u = sink.longLiteralValues().get(slot.index());
      return new Scaled(sink.longSlot(Math.multiplyExact(u, factor)), type);
    }
    return new Scaled(new IntArith(IntOp.MUL, Overflow.WRAP, x.unscaled(),
        sink.longSlot(factor)), type);
  }

  /**
   * The two operands of a comparison of decimals, as unscaled longs of one scale, or empty with
   * the reason noted. Type coercion gives both operands one type, a rescale sitting inside the
   * operand that was cast. Two values of one scale compare as their unscaled values whatever
   * their precisions, so only the scales are held equal: a hand-built tree of two scales
   * declines rather than comparing unscaled values of two scales.
   */
  static Optional<List<VarkaVectorIR>> operands(Expression whole, Expression left,
      Expression right, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Optional<Scaled> l = value(left, inputs, literals, sink);
    if (l.isEmpty()) {
      return Optional.empty();
    }
    Optional<Scaled> r = value(right, inputs, literals, sink);
    if (r.isEmpty()) {
      return Optional.empty();
    }
    if (l.get().type().scale() != r.get().type().scale()) {
      return sink.decline("a comparison of decimals of two scales with no cast to one", whole);
    }
    return Optional.of(List.of(l.get().unscaled(), r.get().unscaled()));
  }

  /**
   * {@code child IS NOT NULL} over a decimal value, or empty with the reason noted: the value is
   * compiled, so a form no arm builds declines, and its validity is then derived from the leaves
   * by {@link #valid}, since an IR null test reads a column's word or a condition's known-ness and
   * never a computed value's word. Every leaf of the value is a leaf of the test, so its slots are
   * still loaded and checked: Spark's {@code IS NULL} evaluates its child, which reads a valid
   * row's value through {@code ArrowColumnVector.getDecimal} and raises on a slot outside the
   * precision, and the kernel declines that batch as every other reader of the leaf does.
   */
  static Optional<Cond> nullTested(Expression child, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    // The value's own long literals, a rescale's factor, are not the test's: rolled back.
    int mark = sink.longMark();
    if (value(child, inputs, literals, sink).isEmpty()) {
      return Optional.empty();
    }
    sink.truncateLong(mark);
    return valid(child, inputs, literals, sink);
  }

  /**
   * Where the decimal value {@code e}, which {@link #value} compiles, is not null, as a condition
   * over its leaves: a column's validity word, through every null-intolerant form over it (a
   * rescale, a negation, {@code abs}); always for a literal; the choice's condition picking its
   * branches' validity for {@code If} and {@code CASE}, whose unknown condition takes the ELSE as
   * {@link VarkaVectorIR.CondIf} does; and any child's for {@code coalesce}, {@code greatest} and
   * {@code least}, which are null only where every child is. Every condition here is total.
   */
  static Optional<Cond> valid(Expression e, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    if (isLiteralTree(e)) {
      return Optional.of(new CondConst(Truth.TRUE, LaneType.LONG));
    }
    return switch (e) {
      case BoundReference br -> Optional.of(new VarkaVectorIR.IsNotNull(leaf(br, inputs)));
      case Cast c -> valid(c.child(), inputs, literals, sink);
      case UnaryMinus m -> valid(m.child(), inputs, literals, sink);
      case Abs a -> valid(a.child(), inputs, literals, sink);
      case If i -> condition(i.predicate(), i, inputs, literals, sink).flatMap(k ->
          valid(i.trueValue(), inputs, literals, sink).flatMap(t ->
              valid(i.falseValue(), inputs, literals, sink)
                  .map(f -> (Cond) new VarkaVectorIR.CondIf(k, t, f))));
      case CaseWhen c when c.elseValue().isDefined() -> {
        Optional<Cond> acc = valid(c.elseValue().get(), inputs, literals, sink);
        List<Tuple2<Expression, Expression>> branches = CollectionConverters.asJava(c.branches());
        for (int b = branches.size() - 1; b >= 0 && acc.isPresent(); b--) {
          Tuple2<Expression, Expression> branch = branches.get(b);
          Cond rest = acc.get();
          acc = condition(branch._1(), c, inputs, literals, sink).flatMap(k ->
              valid(branch._2(), inputs, literals, sink)
                  .map(t -> (Cond) new VarkaVectorIR.CondIf(k, t, rest)));
        }
        yield acc;
      }
      case Coalesce c -> anyValid(CollectionConverters.asJava(c.children()), inputs, literals,
          sink);
      case Greatest g -> anyValid(CollectionConverters.asJava(g.children()), inputs, literals,
          sink);
      case Least l -> anyValid(CollectionConverters.asJava(l.children()), inputs, literals,
          sink);
      // A sum, difference or product within 18 digits never overflows its type, so in every mode
      // it is null exactly where an operand is (VARKA-349).
      case RuntimeReplaceable r -> valid(r.replacement(), inputs, literals, sink);
      case Add a -> allValid(List.of(a.left(), a.right()), inputs, literals, sink);
      case Subtract a -> allValid(List.of(a.left(), a.right()), inputs, literals, sink);
      case Multiply m -> allValid(List.of(m.left(), m.right()), inputs, literals, sink);
      default -> sink.decline("the validity of a decimal value no arm builds", e);
    };
  }

  /** Where every one of {@code children} is not null, as a balanced AND of their validity. */
  private static Optional<Cond> allValid(List<Expression> children, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    var each = new ArrayList<Cond>();
    for (Expression child : children) {
      Optional<Cond> v = valid(child, inputs, literals, sink);
      if (v.isEmpty()) {
        return Optional.empty();
      }
      each.add(v.get());
    }
    return Optional.of(VarkaConditionCompiler.andFold(each));
  }

  /** Where some of {@code children} is not null, as a balanced OR of their validity. */
  private static Optional<Cond> anyValid(List<Expression> children, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    var each = new ArrayList<Cond>();
    for (Expression child : children) {
      Optional<Cond> v = valid(child, inputs, literals, sink);
      if (v.isEmpty()) {
        return Optional.empty();
      }
      each.add(v.get());
    }
    return Optional.of(VarkaConditionCompiler.orFold(each));
  }

  /**
   * A choice's condition on the long lane, where its values are: a condition over int or narrow
   * values widens there, and one with no long-lane form, a floating null test, declines.
   */
  private static Optional<Cond> condition(Expression predicate, Expression whole,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Optional<Cond> cond = VarkaConditionCompiler.compileCond(predicate, inputs, literals, sink);
    if (cond.isEmpty()) {
      return cond;
    }
    Optional<Cond> lifted = VarkaConditionCompiler.onLongLane(cond.get());
    if (lifted.isEmpty()) {
      return sink.decline("a choice of decimals under a condition with no long-lane form", whole);
    }
    return lifted;
  }

  /**
   * {@code IF} and {@code CASE WHEN} with an {@code ELSE}: the right fold of
   * {@code IfElse(cond, value, rest)} over the branches. After coercion every branch has the
   * node's type, a branch that was cast carrying its rescale inside, so the branches are of one
   * scale and pick as their unscaled values; a hand-built tree of two scales declines.
   */
  private static Optional<Scaled> choice(Expression whole, List<Expression> predicates,
      List<Expression> values, Expression otherwise, DecimalType type, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    if (type.precision() > MAX_PRECISION) {
      return sink.decline("a choice of decimals wider than " + MAX_PRECISION + " digits is "
          + "row 359's", whole);
    }
    var conds = new ArrayList<Cond>();
    var branches = new ArrayList<VarkaVectorIR>();
    for (int b = 0; b < predicates.size(); b++) {
      Optional<Cond> k = condition(predicates.get(b), whole, inputs, literals, sink);
      if (k.isEmpty()) {
        return Optional.empty();
      }
      Optional<VarkaVectorIR> v = branch(whole, values.get(b), type, inputs, literals, sink);
      if (v.isEmpty()) {
        return Optional.empty();
      }
      conds.add(k.get());
      branches.add(v.get());
    }
    Optional<VarkaVectorIR> acc = branch(whole, otherwise, type, inputs, literals, sink);
    if (acc.isEmpty()) {
      return Optional.empty();
    }
    VarkaVectorIR folded = acc.get();
    for (int b = conds.size() - 1; b >= 0; b--) {
      folded = new IfElse(conds.get(b), branches.get(b), folded);
    }
    return Optional.of(new Scaled(folded, type));
  }

  /**
   * {@code coalesce}: the right fold of {@code IfElse(valid(head), head, rest)}, the last child
   * the end of the fold. {@code nvl} and {@code ifnull} arrive as it.
   */
  private static Optional<Scaled> coalesce(Coalesce c, DecimalType type, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    if (type.precision() > MAX_PRECISION) {
      return sink.decline("a coalesce of decimals wider than " + MAX_PRECISION + " digits is "
          + "row 359's", c);
    }
    List<Expression> children = CollectionConverters.asJava(c.children());
    if (children.isEmpty()) {
      return sink.decline("a coalesce with no operand", c);
    }
    var values = new ArrayList<VarkaVectorIR>();
    var tests = new ArrayList<Cond>();
    for (int i = 0; i < children.size(); i++) {
      Optional<VarkaVectorIR> v = branch(c, children.get(i), type, inputs, literals, sink);
      if (v.isEmpty()) {
        return Optional.empty();
      }
      values.add(v.get());
      if (i < children.size() - 1) {
        Optional<Cond> t = valid(children.get(i), inputs, literals, sink);
        if (t.isEmpty()) {
          return Optional.empty();
        }
        tests.add(t.get());
      }
    }
    VarkaVectorIR folded = values.get(values.size() - 1);
    for (int i = tests.size() - 1; i >= 0; i--) {
      folded = new IfElse(tests.get(i), values.get(i), folded);
    }
    return Optional.of(new Scaled(folded, type));
  }

  /**
   * {@code greatest} and {@code least}: the null-skipping picks folded from the left, which order
   * unscaled values of one scale as Spark's ordering orders the values.
   */
  private static Optional<Scaled> pick(Expression whole, List<Expression> children,
      DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink,
      BinaryOperator<VarkaVectorIR> combine) {
    if (type.precision() > MAX_PRECISION) {
      return sink.decline("a pick of decimals wider than " + MAX_PRECISION + " digits is row "
          + "359's", whole);
    }
    VarkaVectorIR folded = null;
    for (Expression child : children) {
      Optional<VarkaVectorIR> v = branch(whole, child, type, inputs, literals, sink);
      if (v.isEmpty()) {
        return Optional.empty();
      }
      folded = folded == null ? v.get() : combine.apply(folded, v.get());
    }
    if (folded == null) {
      return sink.decline("a pick with no operand", whole);
    }
    return Optional.of(new Scaled(folded, type));
  }

  /** One branch of a choice, of the choice's scale, or empty with the reason noted. */
  private static Optional<VarkaVectorIR> branch(Expression whole, Expression e,
      DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Optional<Scaled> v = value(e, inputs, literals, sink);
    if (v.isEmpty()) {
      return Optional.empty();
    }
    if (v.get().type().scale() != type.scale()) {
      return sink.decline("a choice of decimals of two scales with no cast to one", whole);
    }
    return Optional.of(v.get().unscaled());
  }

  /** A decimal column the registry admits, read by its leaf; its type is the column's. */
  private static Optional<Scaled> column(BoundReference br, DecimalType type,
      VarkaSlotTable inputs, DeclineSink sink) {
    if (VarkaTypes.lane(type).isEmpty()) {
      return sink.decline(VarkaTypes.declineReason(type)
          .orElse("a decimal column on no lane"), br);
    }
    return Optional.of(new Scaled(leaf(br, inputs), type));
  }

  /**
   * {@code CAST(x AS DECIMAL(P, S))} of a decimal {@code x} of {@code (p, s)}: exact and unable to
   * fail in any mode when it keeps every integral digit ({@code P - S >= p - s}) and does not
   * lower the scale ({@code S >= s}), since {@code changePrecision} then multiplies by
   * {@code 10^(S - s)} and its precision check passes; within 18 digits that is
   * {@link #rescale}. Such a cast is the only one type coercion puts over a decimal operand of a
   * comparison or a choice within 18 digits, so every other cast declines naming its row.
   */
  private static Optional<Scaled> cast(Cast c, DecimalType to, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    if (!(c.child().dataType() instanceof DecimalType from)) {
      return sink.decline("a cast from " + c.child().dataType().simpleString()
          + " to a decimal is row 350's", c);
    }
    int k = to.scale() - from.scale();
    if (k < 0 || to.precision() - to.scale() < from.precision() - from.scale()) {
      return sink.decline("a decimal cast that lowers the scale or the integral digits rounds "
          + "or can overflow, and is row 350's", c);
    }
    if (to.precision() > MAX_PRECISION) {
      return sink.decline(k == 0
          ? "a decimal wider than " + MAX_PRECISION + " digits is row 359's"
          : "a rescale to a decimal wider than " + MAX_PRECISION + " digits is rows 359 and "
              + "360's", c);
    }
    return value(c.child(), inputs, literals, sink)
        .map(x -> new Scaled(rescale(x, k, sink).unscaled(), to));
  }

  /**
   * {@code x + y} or {@code x - y} (VARKA-349 2.2): both operands rescaled to the result's scale
   * {@code S}, then one wrapping long add or subtract, in every mode. Each rescaled operand has
   * at most {@code P - 1 <= 17} digits, so neither rescale leaves the long lane and the result,
   * at most {@code 2 * (10^(P - 1) - 1) < 10^P}, is within the node's type, which is Spark's
   * {@code resultDecimalType}. A two-literal tree folds to its constant.
   */
  private static Optional<Scaled> sum(Expression whole, IntOp op, Expression left,
      Expression right, DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    String what = op == IntOp.ADD ? "sum" : "difference";
    if (!(left.dataType() instanceof DecimalType t1)
        || !(right.dataType() instanceof DecimalType t2)) {
      return sink.decline("a decimal " + what + " with an operand that is not a decimal", whole);
    }
    if (t1.precision() > MAX_PRECISION || t2.precision() > MAX_PRECISION) {
      return sink.decline("a decimal " + what + " over an operand wider than " + MAX_PRECISION
          + " digits is rows 359 and 360's", whole);
    }
    int s = Math.max(t1.scale(), t2.scale());
    int p = Math.max(t1.precision() - t1.scale(), t2.precision() - t2.scale()) + s + 1;
    Optional<String> wide = wide(whole, what, p, s, type, "360", "360 and 363");
    if (wide.isPresent()) {
      return sink.decline(wide.get(), whole);
    }
    if (whole.foldable()) {
      return literal(whole, type, sink);
    }
    Optional<Scaled> x = value(left, inputs, literals, sink);
    if (x.isEmpty()) {
      return Optional.empty();
    }
    Optional<Scaled> y = value(right, inputs, literals, sink);
    if (y.isEmpty()) {
      return Optional.empty();
    }
    Scaled a = rescale(x.get(), s - x.get().type().scale(), sink);
    Scaled b = rescale(y.get(), s - y.get().type().scale(), sink);
    return Optional.of(new Scaled(new IntArith(op, Overflow.WRAP, a.unscaled(), b.unscaled()),
        type));
  }

  /**
   * {@code x * y} (VARKA-349 2.3): one wrapping long multiply of the unscaled values, in every
   * mode, with no rescale, since the scales add: the product of {@code u1 / 10^s1} and
   * {@code u2 / 10^s2} is {@code u1 * u2 / 10^(s1 + s2)}. Its magnitude is at most
   * {@code (10^p1 - 1)(10^p2 - 1) < 10^(p1 + p2)}, within the node's type
   * {@code (p1 + p2 + 1, s1 + s2)} and below {@code 2^63} wherever that type is within 18 digits.
   * A two-literal tree folds to its constant.
   */
  private static Optional<Scaled> product(Multiply m, DecimalType type, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    if (!(m.left().dataType() instanceof DecimalType t1)
        || !(m.right().dataType() instanceof DecimalType t2)) {
      return sink.decline("a decimal product with an operand that is not a decimal", m);
    }
    if (t1.precision() > MAX_PRECISION || t2.precision() > MAX_PRECISION) {
      return sink.decline("a decimal product over an operand wider than " + MAX_PRECISION
          + " digits is rows 362 and 363's", m);
    }
    int p = t1.precision() + t2.precision() + 1;
    int s = t1.scale() + t2.scale();
    Optional<String> wide = wide(m, "product", p, s, type, "361", "362 and 363");
    if (wide.isPresent()) {
      return sink.decline(wide.get(), m);
    }
    if (m.foldable()) {
      return literal(m, type, sink);
    }
    Optional<Scaled> x = value(m.left(), inputs, literals, sink);
    if (x.isEmpty()) {
      return Optional.empty();
    }
    Optional<Scaled> y = value(m.right(), inputs, literals, sink);
    if (y.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Scaled(new IntArith(IntOp.MUL, Overflow.WRAP, x.get().unscaled(),
        y.get().unscaled()), type));
  }

  /**
   * Why an arithmetic result of the exact type {@code (p, s)} has no long form, or empty where it
   * has: past 18 digits it is the wider rows' ({@code to38} up to 38 digits, {@code past38} where
   * Spark caps it), and a node typed other than {@code (p, s)} within 18 digits, which no
   * analysed tree holds, is declined rather than computed at a type it does not have.
   */
  private static Optional<String> wide(Expression whole, String what, int p, int s,
      DecimalType type, String to38, String past38) {
    if (p > DecimalType.MAX_PRECISION()) {
      return Optional.of("a decimal " + what + " of " + p + " digits, capped at "
          + DecimalType.MAX_PRECISION() + ", is rows " + past38 + "'s");
    }
    if (p > MAX_PRECISION) {
      return Optional.of("a decimal " + what + " of " + p + " digits is row " + to38 + "'s");
    }
    if (type.precision() != p || type.scale() != s) {
      return Optional.of("a decimal " + what + " typed " + type.simpleString() + " where Spark's "
          + "rule gives " + new DecimalType(p, s).simpleString() + ": " + whole.nodeName());
    }
    return Optional.empty();
  }

  /** The leaf of a decimal column on the long lane, its precision the column's. */
  private static DecimalColumn leaf(BoundReference br, VarkaSlotTable inputs) {
    int precision = ((DecimalType) br.dataType()).precision();
    return new DecimalColumn(inputs.intern(br.ordinal()), LaneType.LONG, precision);
  }

  /** Whether {@code e} is a literal under zero or more casts, so evaluating it reads nothing. */
  private static boolean isLiteralTree(Expression e) {
    Expression at = e;
    while (at instanceof Cast c) {
      at = c.child();
    }
    return at instanceof Literal;
  }

  /**
   * A literal tree's unscaled value at its type's scale, in a slot of the long table. A literal
   * arrives folded in a real query; in a tree the analyzer alone resolved it is the literal under
   * the casts type coercion put over it, and is folded here by evaluating them, which Spark's
   * constant folding does the same way. A null literal declines: the optimizer has folded its
   * consumer to null before a real query gets here. A cast that fails, or a value that does not
   * fit 18 digits, declines too, and leaves the row engine to raise or answer.
   */
  private static Optional<Scaled> literal(Expression e, DecimalType type, DeclineSink sink) {
    if (type.precision() > MAX_PRECISION) {
      return sink.decline("a decimal literal wider than " + MAX_PRECISION + " digits is row "
          + "359's", e);
    }
    Object value;
    try {
      value = e.eval(null);
    } catch (RuntimeException failed) {
      return sink.decline("a decimal literal whose cast fails", e);
    }
    if (!(value instanceof Decimal d)) {
      return sink.decline("a null decimal literal", e);
    }
    BigDecimal exact = d.toJavaBigDecimal();
    long unscaled;
    try {
      unscaled = exact.setScale(type.scale()).unscaledValue().longValueExact();
    } catch (ArithmeticException notExact) {
      return sink.decline("a decimal literal with no exact long at the scale", e);
    }
    long bound = Decimal.POW_10()[type.precision()] - 1L;
    if (unscaled > bound || unscaled < -bound) {
      return sink.decline("a decimal literal outside its type's precision", e);
    }
    return Optional.of(new Scaled(sink.longSlot(unscaled), type));
  }
}
