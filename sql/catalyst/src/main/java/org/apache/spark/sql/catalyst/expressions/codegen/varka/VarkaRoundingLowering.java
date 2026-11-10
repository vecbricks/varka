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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorWalk.*;

import java.lang.classfile.CodeBuilder;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Objects;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.RoundToIntegral;

/**
 * The lowering of {@link RoundToIntegral}. No host offers a lane rounding instruction through the
 * Vector API (HotSpot's rounding vector node is made only by SuperWord from the scalar
 * intrinsics), so the rounding is built from IEEE additions, which are correctly rounded in a
 * lane exactly as in scalar code and which C2 neither reassociates nor contracts.
 *
 * <p><b>{@code RINT}.</b> With {@code C = 2^52} and {@code a = |x|}: for {@code a < C} the sum
 * {@code a + C} lies in {@code [2^52, 2^53)}, where doubles are spaced exactly 1 apart, so the
 * addition rounds {@code a} to the nearest integer and sends a tie to the even sum, which is the
 * even integer because {@code C} is even; the subtraction {@code (a + C) - C} is then exact. That
 * is {@code rint(a)}, and {@code +0.0} when {@code a} is zero or subnormal. The sign is put back
 * by OR-ing the operand's sign bit into the result's bits, so {@code rint(-0.3)} is {@code -0.0}
 * as {@code Math.rint} answers. From {@code 2^52} on every double is an integer and a NaN is not
 * below {@code C}, so the compare {@code a < C} (false for NaN) keeps {@code x} with its bits.
 * The bitwise operators refuse a floating lane, so the sign handling reinterprets the lanes as
 * longs; a reinterpretation changes no bits and emits no instruction. {@code fp_round.smt2}
 * proves the construction against {@code fp.roundToIntegral RNE} at the formats it decides.
 *
 * <p><b>The NaN.</b> Spark's {@code Rint} answers {@code MathUtils.rint}, which returns the
 * canonical {@code Double.NaN} ({@code 0x7ff8000000000000}) for every NaN, whatever its sign or
 * payload (SPARK-61175). The construction keeps a NaN's bits, so one compare, {@code x != x}
 * (true exactly on the NaN lanes, an unordered {@code vcmppd} on x86), and one blend of the
 * canonical NaN into those lanes follow it, wherever the operand can be a NaN of other bits
 * ({@link #mayHoldOtherNaN}).
 */
final class VarkaRoundingLowering {

  /**
   * {@code 2^52}: from here on every double is an integer. {@code VarkaDivisionLowering}'s magic
   * form rounds its quotient by it too ({@link #emitRoundToNearest}), and {@code VarkaProofFiles}
   * renders it into {@code fp_round.smt2}.
   */
  static final double TWO_52 = 0x1.0p52;

  /** {@code DoubleVector.blend(double, VectorMask)}: a scalar into the mask's lanes. */
  private static final MethodTypeDesc BLEND_SCALAR =
      MethodTypeDesc.of(DOUBLE_VECTOR, ConstantDescs.CD_double, VECTOR_MASK);

  private VarkaRoundingLowering() {
  }

  /**
   * Whether {@code n}'s operand can be a NaN other than the canonical one, which is where
   * {@link #emitRint} blends the canonical NaN in. A pure function of the node's shape, so that
   * the budget's weight ({@code VarkaEmitBudget}) and the emitted code agree and a cached kernel
   * holds for every batch: the range lattice tracks no floating value, so the shape is the one
   * fact there is. Two operands cannot: a conversion from an integral lane, which is never a NaN,
   * and another {@code rint}, whose NaN is already the canonical one, which the construction
   * keeps. Any other operand, a node added to the IR among them, takes the blend, which is
   * never wrong, only one compare and one blend more than needed.
   */
  static boolean mayHoldOtherNaN(RoundToIntegral n) {
    VarkaVectorIR child = n.child();
    if (child instanceof VarkaVectorIR.ConvertLane c) {
      return c.child().laneType().isFloating();
    }
    return !(child instanceof RoundToIntegral);
  }

  /**
   * Spark's {@code rint(child)} per lane: {@code Math.rint}'s bits, and the canonical NaN for a
   * NaN. The operand is parked in the node's scratch local, since the construction reads it
   * three times: as the blend's receiver, whose lanes the false lanes of {@code |x| < 2^52}
   * keep, for the magnitude and for the sign, and the NaN compare reads it twice more. The
   * magnitude is taken once and duplicated, for the compare and for the rounding. The validity
   * is the child's.
   */
  static void emitRint(CodeBuilder cb, RoundToIntegral n, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    int x = Objects.requireNonNull(s.roundTmp.get(n), "the node's scratch local")[0];
    emitValue(cb, n.child(), dense, analysis, s, computed);
    line(cb, analysis, n);
    cb.astore(x);                                               // []
    cb.aload(x);
    cb.aload(x);
    emitAbs(cb);                                                // [x, a]
    cb.dup();
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    cb.loadConstant(TWO_52);
    cb.invokevirtual(DOUBLE_VECTOR, "compare", Lane.DOUBLE.compareVI);  // [x, a, a<C]
    cb.swap();                                                  // [x, a<C, a]
    emitRoundToNearest(cb);                                     // [x, a<C, rint(a)]
    cb.invokevirtual(VECTOR, "reinterpretAsLongs", REINTERPRET_L);
    cb.aload(x);
    cb.invokevirtual(VECTOR, "reinterpretAsLongs", REINTERPRET_L);
    cb.loadConstant(Long.MIN_VALUE);
    cb.invokevirtual(LONG_VECTOR, "and", Lane.LONG.lanewiseVI); // [x, a<C, rint(a), sign of x]
    cb.invokevirtual(LONG_VECTOR, "or", Lane.LONG.lanewiseVV);
    cb.invokevirtual(VECTOR, "reinterpretAsDoubles", REINTERPRET_D); // [x, a<C, rint(x)]
    cb.swap();                                                  // [x, rint(x), a<C]
    cb.invokevirtual(DOUBLE_VECTOR, "blend", Lane.DOUBLE.blend); // [r]
    if (mayHoldOtherNaN(n)) {
      cb.loadConstant(Double.NaN);
      cb.aload(x);
      cb.getstatic(VECTOR_OPERATORS, "NE", VO_COMPARISON);
      cb.aload(x);
      cb.invokevirtual(DOUBLE_VECTOR, "compare", Lane.DOUBLE.compareVV); // [r, NaN, x != x]
      cb.invokevirtual(DOUBLE_VECTOR, "blend", BLEND_SCALAR);   // [r, a NaN canonical]
    }
  }

  /**
   * {@code [v] -> [(v + 2^52) - 2^52]}: for {@code 0 <= v < 2^52}, {@code v} rounded to the
   * nearest integer with a tie to the even one, exactly (the class doc says why).
   */
  static void emitRoundToNearest(CodeBuilder cb) {
    cb.loadConstant(TWO_52);
    cb.invokevirtual(DOUBLE_VECTOR, "add", LANEWISE_VD);
    cb.loadConstant(TWO_52);
    cb.invokevirtual(DOUBLE_VECTOR, "sub", LANEWISE_VD);
  }

  /** {@code [v] -> [|v|]}: the sign bit cleared, NaN included, as {@code Math.abs}. */
  private static void emitAbs(CodeBuilder cb) {
    cb.getstatic(VECTOR_OPERATORS, "ABS", VO_UNARY);
    cb.invokevirtual(DOUBLE_VECTOR, "lanewise", MethodTypeDesc.of(DOUBLE_VECTOR, VO_UNARY));
  }
}
