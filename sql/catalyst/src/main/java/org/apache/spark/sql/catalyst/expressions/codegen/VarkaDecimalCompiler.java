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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenDecimal;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;

/**
 * The decimal values a kernel computes on the long lane (VARKA-347, VARKA-348): each is a
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
 * <p>What declines, each naming its row: a type wider than 18 digits (row 359, and row 360 where
 * a rescale is needed); a cast that lowers the scale or the integral digits, or one from an
 * integral type (row 350); a negative scale (the milestone plan's 2.8); and every operator no
 * arm here claims yet.
 *
 * <p>A decimal-valued output root, once a lowering builds one, is admitted by {@link #outputRoot}.
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
  }

  /** The largest unscaled value of {@code type}, {@code 10^precision - 1}. */
  private static long bound(DecimalType type) {
    return Decimal.POW_10()[type.precision()] - 1L;
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
    // One width check for every form, a new one included, so that no arm builds a Scaled its
    // record refuses. A column keeps the registry's reason, which names its own row.
    if (type.precision() > MAX_PRECISION && !(e instanceof BoundReference)) {
      return sink.decline(wideReason(e, type), e);
    }
    if (isLiteralTree(e)) {
      return literal(e, type, sink);
    }
    return switch (e) {
      case BoundReference br -> column(br, type, inputs, sink);
      case Cast c -> cast(c, type, inputs, literals, sink);
      default -> sink.decline("a decimal operator no arm builds yet: +, - and * are row 349's "
          + "and the rounding functions row 351's, and a quotient or a remainder is no row's in "
          + "this milestone", e);
    };
  }

  /**
   * Why a value of {@code type}, wider than the long lane, declines: the wide representation is
   * row 359's, and a cast that rescales into it, the one coercion puts over the operand of the
   * smaller scale, needs row 360's wide rescale as well.
   */
  private static String wideReason(Expression e, DecimalType type) {
    if (e instanceof Cast c && c.child().dataType() instanceof DecimalType from
        && from.scale() < type.scale()) {
      return "a rescale to a decimal wider than " + MAX_PRECISION + " digits is rows 359 and "
          + "360's";
    }
    return "a decimal wider than " + MAX_PRECISION + " digits is row 359's";
  }

  /**
   * {@code x} at {@code k} more digits of scale: the identity at {@code k = 0}, and otherwise a
   * wrapping multiply by the slot {@code 10^k}, which never wraps since
   * {@code |u * 10^k| <= 10^(p + k) - 10^k < 2^63}. The result is of type
   * {@code (p + k, s + k)}. A rescale past 18 digits has no long form and is refused by
   * throwing: the callers check the type rule first.
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
    return new Scaled(new IntArith(IntOp.MUL, Overflow.WRAP, x.unscaled(),
        sink.longSlot(Decimal.POW_10()[k])), type);
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
   * The output root of {@code value}, the unscaled long-lane value of the decimal-valued
   * {@code whole}, or empty with the reason noted: {@code WidenDecimal(value)}, whose store writes
   * each row's word and its sign extension into the output's sixteen-byte slot, where the
   * output's type admits that store ({@code VarkaTypes.decimalOutputDeclineReason}), and a
   * decline naming row 359 for a precision above 18, so such a shape never fuses rather than
   * reaching the evaluator's allocator, which refuses it too. The binding that knows an output's
   * type calls it, and only for an output root (`VARKA-157.md` 2.1); no lowering builds a
   * decimal-valued root before 157b's producers, so its tests are its only callers until then.
   */
  static Optional<VarkaVectorIR> outputRoot(Expression whole, VarkaVectorIR value,
      DeclineSink sink) {
    if (!(whole.dataType() instanceof DecimalType type)) {
      throw VarkaErrors.illegalArgument("a decimal output root over a "
          + whole.dataType().simpleString() + ": " + whole);
    }
    Optional<String> refused = VarkaTypes.decimalOutputDeclineReason(type);
    if (refused.isPresent()) {
      return sink.decline(refused.get(), whole);
    }
    return Optional.of(new WidenDecimal(value));
  }

  /**
   * The column a null test over a decimal reads, or empty with the reason noted. A widening cast
   * of the column is null exactly where the column is, so it is the column here too. The test
   * reads the column's validity word, and the leaf beneath it still loads and checks the slots:
   * Spark's {@code IS NULL} evaluates its child, which reads a valid row's value through
   * {@code ArrowColumnVector.getDecimal} and raises on a slot outside the precision, so the
   * kernel declines that batch as every other reader of the leaf does.
   */
  static Optional<VarkaVectorIR> nullTested(Expression whole, Expression child,
      VarkaSlotTable inputs, DeclineSink sink) {
    Expression column = stripWidening(child);
    if (column instanceof BoundReference br) {
      if (VarkaTypes.lane(br.dataType()).isEmpty()) {
        return sink.decline(VarkaTypes.declineReason(br.dataType())
            .orElse("a decimal column on no lane"), whole);
      }
      return Optional.of(leaf(br, inputs));
    }
    return sink.decline("a null test over a decimal that is not a column", whole);
  }

  /**
   * A decimal column the registry admits on the long lane, read by its leaf; its type is the
   * column's. A column the registry puts on another lane, or wider than 18 digits, has no
   * {@link Scaled} here and declines with the registry's reason, or else its own.
   */
  private static Optional<Scaled> column(BoundReference br, DecimalType type,
      VarkaSlotTable inputs, DeclineSink sink) {
    if (VarkaTypes.lane(type).orElse(null) != LaneType.LONG
        || type.precision() > MAX_PRECISION) {
      return sink.decline(VarkaTypes.declineReason(type).orElse(type.precision() > MAX_PRECISION
          ? "a decimal column wider than " + MAX_PRECISION + " digits is row 359's"
          : "a decimal column the registry puts off the long lane"), br);
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
    return value(c.child(), inputs, literals, sink)
        .map(x -> new Scaled(rescale(x, k, sink).unscaled(), to));
  }

  /** The leaf of a decimal column on the long lane, its precision the column's. */
  private static DecimalColumn leaf(BoundReference br, VarkaSlotTable inputs) {
    int precision = ((DecimalType) br.dataType()).precision();
    return new DecimalColumn(inputs.intern(br.ordinal()), LaneType.LONG, precision);
  }

  /**
   * {@code e} without the casts over it that keep its scale and widen its precision: each is the
   * identity on the unscaled value, and none can fail, in any mode.
   */
  private static Expression stripWidening(Expression e) {
    Expression at = e;
    while (at instanceof Cast c && c.dataType() instanceof DecimalType to
        && c.child().dataType() instanceof DecimalType from
        && to.scale() == from.scale() && to.precision() >= from.precision()) {
      at = c.child();
    }
    return at;
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
    long bound = bound(type);
    if (unscaled > bound || unscaled < -bound) {
      return sink.decline("a decimal literal outside its type's precision", e);
    }
    return Optional.of(new Scaled(sink.longSlot(unscaled), type));
  }
}
