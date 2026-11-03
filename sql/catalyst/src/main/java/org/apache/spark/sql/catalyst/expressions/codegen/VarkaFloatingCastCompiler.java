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
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * The floating casts family (VARKA-343): Spark's casts to a floating type, of which this part
 * builds the ones whose kernel keeps one lane count, {@code INT}, {@code SMALLINT} and
 * {@code TINYINT} to {@code FLOAT}.
 *
 * <p>Spark computes each with Java's primitive cast, {@code (float) c}, in every mode: a
 * conversion to a floating type never raises, so TRY's answer is LEGACY's and ANSI's
 * ({@code Cast.castToFloatCode}). So each is one {@link ConvertLane} from the int lane, which
 * rounds once to nearest, ties to even, on every lane, as the JVM's {@code i2f} does. A narrow
 * source is the {@code INT} value its load widens to, which is exact, then converted. The kernel
 * stays at the int lane's count, the float values beside the int ones.
 *
 * <p>Every other cast with a floating side declines by {@link VarkaNodeCompiler}'s
 * {@code floatingDecline}, which admits exactly the pairs {@link #admits} names: the casts to
 * {@code DOUBLE}, from {@code BIGINT} and between the floating types need the kernel of two lane
 * counts or a host's conversion forms, the later parts of row 343.
 */
final class VarkaFloatingCastCompiler {

  private VarkaFloatingCastCompiler() {
  }

  /** The family's claim: a cast {@link #admits} names, or null. */
  static @Nullable VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (!(e instanceof Cast c) || !admits(c)) {
      return null;
    }
    return () -> convert(c, inputs, literals, sink);
  }

  /** Whether {@code c} is one of this family's pairs: an int or narrow source to {@code FLOAT}. */
  static boolean admits(Cast c) {
    if (!DataTypes.FloatType.equals(c.dataType())) {
      return false;
    }
    DataType from = c.child().dataType();
    return DataTypes.ByteType.equals(from) || DataTypes.ShortType.equals(from)
        || DataTypes.IntegerType.equals(from);
  }

  private static Optional<VarkaVectorIR> convert(
      Cast c, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Expression child = c.child();
    // An int is read through the int operand rule, the one place a bare INT column or literal
    // becomes its leaf; a narrow source is a node compileNode lowers.
    Optional<VarkaVectorIR> x = DataTypes.IntegerType.equals(child.dataType())
        ? VarkaNodeCompiler.intOperand(child, inputs, literals, sink)
        : VarkaNodeCompiler.compileNode(child, inputs, literals, sink);
    if (x.isEmpty()) {
      return x;
    }
    VarkaVectorIR value = x.get();
    if (value.laneType().isNarrow()) {
      // A TINYINT or SMALLINT value: the int its load widens it to, exact, then the conversion.
      value = new WidenLane(value, LaneType.INT);
    }
    return Optional.of(new ConvertLane(value, LaneType.FLOAT));
  }
}
