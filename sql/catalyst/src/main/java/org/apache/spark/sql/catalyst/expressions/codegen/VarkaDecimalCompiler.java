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
import java.util.Optional;

import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;

/**
 * The operands of the decimal predicates a kernel computes today (VARKA-347, its plan's 2.5): the
 * comparisons of two decimals of one scale, and the null tests. Each operand is an unscaled long
 * on the long lane, a decimal column's {@link DecimalColumn} leaf or a literal's slot.
 *
 * <p>Why the long compare is Spark's answer. Two values of {@code DECIMAL(p, s)} at the same
 * scale {@code s} are {@code u1 / 10^s} and {@code u2 / 10^s}, so they order and compare equal
 * exactly as their unscaled values {@code u1} and {@code u2} do, and {@code Decimal.compare}
 * compares by value (the long fields where both are compact, {@code BigDecimal.compareTo}
 * otherwise, which ignores the representation). Where the operands' precisions differ, Spark's
 * type coercion ({@code DecimalPrecisionTypeCoercion.decimalAndDecimal}) casts both to the wider
 * type, {@code DECIMAL(max(p1 - s, p2 - s) + s, s)} for one scale: a cast to the same scale and a
 * larger precision, which changes no unscaled value and cannot fail, so it is its child here, as
 * {@code TIME}'s widening precision cast is in {@code VarkaTimeCompiler}.
 *
 * <p>What stays declined, each naming its row: operands of two scales, whose comparison needs a
 * rescale (row 348); a comparison whose common type is wider than 18 digits, whose unscaled
 * values need not fit a long (row 359); and every other decimal operand, a cast that changes the
 * scale or an arithmetic result (rows 348 to 350). A decimal in a value position - a choice, an
 * arithmetic operand, an output - is declined by {@code VarkaTimeCompiler}'s decimal arm.
 */
final class VarkaDecimalCompiler {

  /** The largest unscaled value of 18 digits, {@code 10^18 - 1}. */
  private static final long MAX_UNSCALED = new DecimalColumn(0, LaneType.LONG,
      DecimalColumn.MAX_LONG_PRECISION).bound();

  private VarkaDecimalCompiler() {
  }

  /** Whether {@code e} is a decimal, which only this class compiles as an operand. */
  static boolean isDecimal(Expression e) {
    return e.dataType() instanceof DecimalType;
  }

  /**
   * The two operands of a comparison of decimals, as unscaled longs of one scale, or empty with
   * the reason noted. Type coercion gives both operands one type; the check is kept so that a
   * hand-built tree of two scales declines rather than comparing unscaled values of two scales.
   */
  static Optional<List<VarkaVectorIR>> operands(Expression whole, Expression left,
      Expression right, VarkaSlotTable inputs, DeclineSink sink) {
    DecimalType lt = (DecimalType) left.dataType();
    if (!(right.dataType() instanceof DecimalType rt) || lt.scale() != rt.scale()) {
      return sink.decline("a comparison of decimals of two scales, which needs a rescale, is "
          + "row 348's", whole);
    }
    if (lt.precision() > DecimalColumn.MAX_LONG_PRECISION
        || rt.precision() > DecimalColumn.MAX_LONG_PRECISION) {
      return sink.decline("a comparison of decimals wider than "
          + DecimalColumn.MAX_LONG_PRECISION + " digits is row 359's", whole);
    }
    Optional<VarkaVectorIR> l = operand(left, lt.scale(), inputs, sink);
    if (l.isEmpty()) {
      return Optional.empty();
    }
    Optional<VarkaVectorIR> r = operand(right, lt.scale(), inputs, sink);
    if (r.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(List.of(l.get(), r.get()));
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
      return Optional.of(column(br, inputs));
    }
    return sink.decline("a null test over a decimal that is not a column", whole);
  }

  /**
   * One operand of scale {@code scale}: a decimal column the registry admits, through any
   * widening casts, or a literal of the scale, whose unscaled value takes a slot in the long
   * table. A literal arrives folded in a real query; in a tree the analyzer alone resolved it is
   * the literal under the casts type coercion put over it, and is folded here by evaluating
   * them, which Spark's constant folding does the same way.
   */
  private static Optional<VarkaVectorIR> operand(Expression e, int scale, VarkaSlotTable inputs,
      DeclineSink sink) {
    Expression stripped = stripWidening(e);
    if (stripped instanceof BoundReference br) {
      if (VarkaTypes.lane(br.dataType()).isEmpty()) {
        return sink.decline(VarkaTypes.declineReason(br.dataType())
            .orElse("a decimal column on no lane"), e);
      }
      return Optional.of(column(br, inputs));
    }
    if (isLiteralTree(e)) {
      return literal(e, scale, sink);
    }
    return sink.decline("a decimal operand that is not a column or a literal: its operators "
        + "are rows 348 to 350's", e);
  }

  /** The leaf of a decimal column on the long lane, its precision the column's. */
  private static DecimalColumn column(BoundReference br, VarkaSlotTable inputs) {
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
   * A literal operand's unscaled value at {@code scale}, in a slot of the long table. A null
   * literal declines: the optimizer has folded the comparison to null before a real query gets
   * here. A cast that fails, or a value that does not fit 18 digits, declines too, and leaves the
   * row engine to raise or answer.
   */
  private static Optional<VarkaVectorIR> literal(Expression e, int scale, DeclineSink sink) {
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
      unscaled = exact.setScale(scale).unscaledValue().longValueExact();
    } catch (ArithmeticException notExact) {
      return sink.decline("a decimal literal with no exact long at the scale", e);
    }
    if (unscaled > MAX_UNSCALED || unscaled < -MAX_UNSCALED) {
      return sink.decline("a decimal literal wider than " + DecimalColumn.MAX_LONG_PRECISION
          + " digits is row 359's", e);
    }
    return Optional.of(sink.longSlot(unscaled));
  }
}
