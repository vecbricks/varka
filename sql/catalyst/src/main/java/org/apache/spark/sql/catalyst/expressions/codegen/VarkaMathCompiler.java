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


import org.apache.spark.sql.catalyst.expressions.Acos;
import org.apache.spark.sql.catalyst.expressions.Acosh;
import org.apache.spark.sql.catalyst.expressions.Asin;
import org.apache.spark.sql.catalyst.expressions.Asinh;
import org.apache.spark.sql.catalyst.expressions.Atan;
import org.apache.spark.sql.catalyst.expressions.Atan2;
import org.apache.spark.sql.catalyst.expressions.Atanh;
import org.apache.spark.sql.catalyst.expressions.Cbrt;
import org.apache.spark.sql.catalyst.expressions.Cos;
import org.apache.spark.sql.catalyst.expressions.Cosh;
import org.apache.spark.sql.catalyst.expressions.Cot;
import org.apache.spark.sql.catalyst.expressions.Csc;
import org.apache.spark.sql.catalyst.expressions.Exp;
import org.apache.spark.sql.catalyst.expressions.Expm1;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Hypot;
import org.apache.spark.sql.catalyst.expressions.Log;
import org.apache.spark.sql.catalyst.expressions.Log10;
import org.apache.spark.sql.catalyst.expressions.Log1p;
import org.apache.spark.sql.catalyst.expressions.Log2;
import org.apache.spark.sql.catalyst.expressions.Logarithm;
import org.apache.spark.sql.catalyst.expressions.Pow;
import org.apache.spark.sql.catalyst.expressions.Sec;
import org.apache.spark.sql.catalyst.expressions.Sin;
import org.apache.spark.sql.catalyst.expressions.Sinh;
import org.apache.spark.sql.catalyst.expressions.Tan;
import org.apache.spark.sql.catalyst.expressions.Tanh;

/**
 * The math family (VARKA-345): Spark's 27 math functions over {@code DOUBLE} operands, the 26
 * classes of {@code mathExpressions.scala} that compute them ({@code log} with one argument and
 * with two are both {@link Logarithm}, {@code ln} is {@link Log}).
 *
 * <p>By default each function fuses bit for bit Spark's answer, in an exact form its row of the
 * plan defines ({@code VARKA-345.md} 2.1); with
 * {@code spark.sql.codegen.varka.math.ulpContract.enabled} a function may instead take the vector
 * math library within a contracted bound (2.5). No function fuses yet: the family claims each
 * class so that {@code floatingDecline} lets it through to a reason of its own, and each declines
 * naming the row that fuses it - 356 the trigonometric functions, 357 the hyperbolic ones, 358
 * the exponential, logarithmic, power and root ones ({@code VARKA-356.md} 2.1). A row that fuses
 * a function replaces its reason here with its lowering.
 */
final class VarkaMathCompiler {

  private VarkaMathCompiler() {
  }

  /** A math function's SQL name and the row of the milestone plan that fuses it. */
  record Owner(String function, int row) {
    /** The reason the function declines with until its row lands. */
    String reason() {
      return function + " is row " + row + "'s: no math function fuses yet";
    }
  }

  /**
   * The function {@code e} computes and its row, or empty for a node that is not one of the 26
   * classes. {@code floatingDecline} lets through exactly these. Every operand is a
   * {@code DOUBLE}: each class takes {@code DOUBLE} inputs by {@code ImplicitCastInputTypes}, so
   * the analyzer has cast any other operand before a plan reaches the compiler.
   */
  static Optional<Owner> ownerOf(Expression e) {
    Owner owner = switch (e) {
      case Sin _ -> new Owner("sin", 356);
      case Cos _ -> new Owner("cos", 356);
      case Tan _ -> new Owner("tan", 356);
      case Asin _ -> new Owner("asin", 356);
      case Acos _ -> new Owner("acos", 356);
      case Atan _ -> new Owner("atan", 356);
      case Atan2 _ -> new Owner("atan2", 356);
      case Sec _ -> new Owner("sec", 356);
      case Csc _ -> new Owner("csc", 356);
      case Cot _ -> new Owner("cot", 356);
      case Sinh _ -> new Owner("sinh", 357);
      case Cosh _ -> new Owner("cosh", 357);
      case Tanh _ -> new Owner("tanh", 357);
      case Asinh _ -> new Owner("asinh", 357);
      case Acosh _ -> new Owner("acosh", 357);
      case Atanh _ -> new Owner("atanh", 357);
      case Exp _ -> new Owner("exp", 358);
      case Expm1 _ -> new Owner("expm1", 358);
      case Log _ -> new Owner("ln", 358);
      case Logarithm _ -> new Owner("log", 358);
      case Log10 _ -> new Owner("log10", 358);
      case Log1p _ -> new Owner("log1p", 358);
      case Log2 _ -> new Owner("log2", 358);
      case Pow _ -> new Owner("pow", 358);
      case Hypot _ -> new Owner("hypot", 358);
      case Cbrt _ -> new Owner("cbrt", 358);
      default -> null;
    };
    return Optional.ofNullable(owner);
  }

  /** The family's claim, or null. */
  static @Nullable VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return ownerOf(e).<VarkaFamilyArm>map(owner -> () -> sink.decline(owner.reason(), e))
        .orElse(null);
  }
}
