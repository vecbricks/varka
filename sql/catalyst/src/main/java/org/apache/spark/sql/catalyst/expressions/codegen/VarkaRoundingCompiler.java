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

import javax.annotation.Nullable;

import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Ceil;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Floor;
import org.apache.spark.sql.catalyst.expressions.Rint;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.RoundMode;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.RoundToIntegral;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * The rounding family (VARKA-344): {@code rint} over a {@code DOUBLE}, and {@code floor},
 * {@code ceil} and {@code ceiling} over the integral operands whose answer is the operand itself.
 *
 * <p>{@code rint} is a {@link RoundToIntegral}, one answer in every mode. {@code floor} and
 * {@code ceil} of a {@code BIGINT} are the operand, Spark's own identity. An {@code INT} operand
 * reaches them as {@code FLOOR(CAST(i AS DOUBLE))}, the first member of their input types the
 * int casts to; an int converts to a double exactly, is its own floor and ceiling, and lies
 * inside the long range that {@code MathUtils.doubleToLong} checks under ANSI, so Spark's answer
 * in both modes is the int widened to the long. That widening is built directly, from the cast's
 * int operand, so the cast itself is never compiled. A {@code DOUBLE} operand needs the
 * double-to-long conversion and a narrow or {@code FLOAT} one the narrow load and the floating
 * casts; each declines with the row that owns it.
 */
final class VarkaRoundingCompiler {

  private VarkaRoundingCompiler() {
  }

  /** The family's claim, or null. */
  static @Nullable VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (e) {
      case Rint r -> () -> operand(r.child(), LaneType.DOUBLE, inputs, literals, sink)
          .map(x -> new RoundToIntegral(RoundMode.RINT, x));
      case Floor f -> () -> floorOrCeil(f, f.child(), inputs, literals, sink);
      case Ceil c -> () -> floorOrCeil(c, c.child(), inputs, literals, sink);
      default -> null;
    };
  }

  private static Optional<VarkaVectorIR> floorOrCeil(
      Expression whole, Expression child, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    DataType t = child.dataType();
    if (DataTypes.LongType.equals(t)) {
      return operand(child, LaneType.LONG, inputs, literals, sink);
    }
    if (child instanceof Cast c && DataTypes.DoubleType.equals(t)) {
      DataType from = c.child().dataType();
      if (DataTypes.IntegerType.equals(from)) {
        return operand(c.child(), LaneType.INT, inputs, literals, sink)
            .map(x -> (VarkaVectorIR) new WidenLane(x, LaneType.LONG));
      }
      if (!DataTypes.DoubleType.equals(from)) {
        return sink.decline("floor or ceil over a cast from " + from.simpleString()
            + " to double, which needs the narrow load in a BIGINT kernel or the floating casts"
            + " (row 344c)", whole);
      }
    }
    return sink.decline("floor or ceil over " + t.simpleString() + " (row 344b)", whole);
  }

  /** An operand compiled to {@code lane}, or empty with the lane named. */
  private static Optional<VarkaVectorIR> operand(
      Expression e, LaneType lane, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    // An int column or literal is no family's node, so compileNode leaves it to the int
    // operand rule, which reads it as the int leaf.
    Optional<VarkaVectorIR> x = lane == LaneType.INT
        ? VarkaNodeCompiler.intOperand(e, inputs, literals, sink)
        : VarkaNodeCompiler.compileNode(e, inputs, literals, sink);
    if (x.isPresent() && x.get().laneType() != lane) {
      return sink.decline("a rounding operand computed on the " + x.get().laneType()
          + " lane, not the " + lane + " one", e);
    }
    return x;
  }
}
