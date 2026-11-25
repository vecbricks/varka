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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.FloatingFault;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathFn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathUnary;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NullValue;

/**
 * The log family's domain null (VARKA-358, {@code VARKA-356.md} 2.3): Spark's {@code ln},
 * {@code log10}, {@code log1p}, {@code log2} and {@code log} answer null where the operand is at or
 * below an asymptote ({@code if (c <= 0.0) isNull = true}, {@code -1.0} for {@code log1p}) and
 * otherwise the {@code StrictMath} call. Java's {@code <=}, so {@code -0.0} is null and a NaN is
 * not: a NaN operand reaches the call, which answers a NaN.
 *
 * <p>The null is composed from nodes the emitter already lowers, rather than carried by a field of
 * {@link MathUnary}: {@code IfElse(x <= t, NULL, fn(x))}, the compare the IEEE one
 * ({@link CompareOp#IEEE_LE}), which is Java's on every input. A null operand makes the compare
 * unknown, so its lane takes the call, whose validity is the operand's: null, as in Spark. Every
 * lane is called, a null lane's too, as every per-lane call is; the call is pure and the blend
 * discards what such a lane answers.
 */
public final class VarkaMathDomain {

  private VarkaMathDomain() {
  }

  /**
   * {@code fn(x)}, null where {@code x <= threshold}; {@code threshold} a {@code DOUBLE} literal
   * slot holding Spark's asymptote; {@code fault} the emission's planted floating fault, which
   * {@link FloatingFault#MATH_DOMAIN_LT} makes the compare {@code <}.
   */
  public static VarkaVectorIR nullAtOrBelow(MathFn fn, VarkaVectorIR x,
      VarkaVectorIR threshold, FloatingFault fault) {
    if (!fn.laneCall()) {
      throw VarkaErrors.illegalArgument(fn + " has no domain null: it is a lanewise operator");
    }
    CompareOp op = fault == FloatingFault.MATH_DOMAIN_LT ? CompareOp.IEEE_LT : CompareOp.IEEE_LE;
    return new IfElse(new Compare(op, x, threshold), new NullValue(LaneType.DOUBLE),
        new MathUnary(fn, x));
  }
}
