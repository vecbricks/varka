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

import java.util.Optional;

import org.apache.spark.sql.catalyst.expressions.Abs;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.Divide;
import org.apache.spark.sql.catalyst.expressions.EvalMode$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.Signum;
import org.apache.spark.sql.catalyst.expressions.Sqrt;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.ToDegrees;
import org.apache.spark.sql.catalyst.expressions.ToRadians;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathFn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathUnary;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * The floating arithmetic family (VARKA-342): Spark's {@code +}, {@code -}, {@code *},
 * negation and {@code abs} over {@code DOUBLE} and {@code FLOAT}, and {@code /}, {@code sqrt},
 * {@code signum}, {@code degrees} and {@code radians} over {@code DOUBLE}, the only type Spark
 * gives the division and the math functions; another operand reaches them under a cast to
 * double, which declines until the floating casts exist (VARKA-343).
 *
 * <p>Every form has one answer, the IEEE operation {@code java.lang.Math} or Java's operator
 * computes, so each is a lanewise operation on the operand's own lane, with the operands in
 * Spark's order and no algebraic rewrite, and is exact by construction. The arithmetic is the
 * integral lanes' {@link IntArith} and {@link IntNeg} at a floating lane; the square root is a
 * {@link MathUnary}. Only the division has a mode, the zero divisor's disposal: null under
 * LEGACY and TRY ({@code try_divide} is LEGACY's division), the batch handed to the row engine
 * under ANSI, which raises {@code DIVIDE_BY_ZERO}; a non-zero literal divisor needs no test, as
 * in Spark, and a literal zero declines by name.
 */
final class VarkaFloatingCompiler {

  private VarkaFloatingCompiler() {
  }

  /** The family's claim: a node whose own value is floating and whose form it lowers, or null. */
  static VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<LaneType> lane = floatingLane(e.dataType());
    if (lane.isEmpty()) {
      return null;
    }
    LaneType l = lane.get();
    return switch (e) {
      case Add a -> () -> binary(IntOp.ADD, Overflow.WRAP, a.left(), a.right(), l, inputs,
          literals, sink);
      case Subtract a -> () -> binary(IntOp.SUB, Overflow.WRAP, a.left(), a.right(), l, inputs,
          literals, sink);
      case Multiply a -> () -> binary(IntOp.MUL, Overflow.WRAP, a.left(), a.right(), l, inputs,
          literals, sink);
      case Divide d when l == LaneType.DOUBLE -> () -> divide(d, inputs, literals, sink);
      case UnaryMinus n -> () -> operand(n.child(), l, inputs, literals, sink)
          .map(x -> new IntNeg(Overflow.WRAP, x));
      case Abs a -> () -> operand(a.child(), l, inputs, literals, sink)
          .map(x -> new IntNeg(Overflow.WRAP, x, true));
      case Sqrt q when l == LaneType.DOUBLE -> () -> operand(q.child(), l, inputs, literals, sink)
          .map(x -> new MathUnary(MathFn.SQRT, x));
      // `Math.toDegrees` and `Math.toRadians` are one multiply by a private constant, so the
      // constant is read from the JDK's own function at 1.0 rather than retyped: the product is
      // then the function's bits on every input, which a test pins against the JDK.
      case ToDegrees t when l == LaneType.DOUBLE -> () -> scale(t.child(), Math.toDegrees(1.0),
          inputs, literals, sink);
      case ToRadians t when l == LaneType.DOUBLE -> () -> scale(t.child(), Math.toRadians(1.0),
          inputs, literals, sink);
      case Signum g when l == LaneType.DOUBLE && floatingLane(g.child().dataType()).isPresent()
          -> () -> signum(g, inputs, literals, sink);
      default -> null;
    };
  }

  /** The floating lane of {@code t}, or empty for any other type. */
  static Optional<LaneType> floatingLane(DataType t) {
    if (DataTypes.DoubleType.equals(t)) {
      return Optional.of(LaneType.DOUBLE);
    }
    if (DataTypes.FloatType.equals(t)) {
      return Optional.of(LaneType.FLOAT);
    }
    return Optional.empty();
  }

  /**
   * An operand on {@code lane}: anything {@code compileNode} lowers to that lane. An operand
   * computed on another lane declines with its lane named, since the two kinds do not mix
   * without a conversion.
   */
  private static Optional<VarkaVectorIR> operand(
      Expression e, LaneType lane, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> x = VarkaNodeCompiler.compileNode(e, inputs, literals, sink);
    if (x.isPresent() && x.get().laneType() != lane) {
      return sink.decline("a floating operand computed on the " + x.get().laneType() + " lane",
          e);
    }
    return x;
  }

  private static Optional<VarkaVectorIR> binary(
      IntOp op, Overflow mode, Expression left, Expression right, LaneType lane,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Optional<VarkaVectorIR> x = operand(left, lane, inputs, literals, sink);
    if (x.isEmpty()) {
      return x;
    }
    Optional<VarkaVectorIR> y = operand(right, lane, inputs, literals, sink);
    if (y.isEmpty()) {
      return y;
    }
    return Optional.of(new IntArith(op, mode, x.get(), y.get()));
  }

  /**
   * {@code x / y}, Spark's {@code DivModLike} over doubles. A literal divisor is known at compile
   * time: zero, of either sign, declines by name, since every live lane would be null or raised;
   * any other value divides with no test, as Spark's own {@code isZero} check is dropped for it.
   * A computed divisor is tested lane by lane, the mode saying what a zero means.
   */
  private static Optional<VarkaVectorIR> divide(
      Divide d, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Overflow mode;
    if (d.right() instanceof Literal l && l.value() instanceof Double v) {
      if (v == 0.0) {
        return sink.decline("a division by a literal zero", d);
      }
      mode = Overflow.WRAP;
    } else {
      mode = EvalMode$.MODULE$.ANSI().equals(d.evalMode()) ? Overflow.FAIL : Overflow.NULL;
    }
    return binary(IntOp.DIV, mode, d.left(), d.right(), LaneType.DOUBLE, inputs, literals, sink);
  }

  /** {@code x * k}, {@code k} a double constant. */
  private static Optional<VarkaVectorIR> scale(
      Expression child, double k, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    return operand(child, LaneType.DOUBLE, inputs, literals, sink)
        .map(x -> new IntArith(IntOp.MUL, Overflow.WRAP, x, doubleSlot(k, sink)));
  }

  /**
   * {@code signum(x)}: {@code -1.0} where {@code x < 0.0}, {@code 1.0} where {@code x > 0.0} and
   * {@code x} is not NaN, and {@code x} itself everywhere else - both zeros, which keep their
   * sign, and NaN, which keeps its payload, as {@code Math.signum} returns its operand for
   * them. The comparisons are the lane's, in Spark's order, where NaN is greater than every
   * number, so {@code x > 0.0} alone would answer {@code 1.0} for NaN, and {@code -0.0 < 0.0}
   * is false. The last arm is {@code x} so that a null operand, whose conditions are unknown
   * and take the else arm, answers null.
   */
  private static Optional<VarkaVectorIR> signum(
      Signum g, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    return operand(g.child(), LaneType.DOUBLE, inputs, literals, sink).map(x -> {
      var zero = doubleSlot(0.0, sink);
      var positive = new And(new Compare(CompareOp.GT, x, zero), new Not(new Compare(CompareOp.EQ,
          x, VarkaNodeCompiler.nanSlot(LaneType.DOUBLE, literals, sink))));
      return new IfElse(new Compare(CompareOp.LT, x, zero), doubleSlot(-1.0, sink),
          new IfElse(positive, doubleSlot(1.0, sink), x));
    });
  }

  private static VarkaVectorIR doubleSlot(double v, DeclineSink sink) {
    return sink.longSlot(Double.doubleToRawLongBits(v), LaneType.DOUBLE);
  }
}
