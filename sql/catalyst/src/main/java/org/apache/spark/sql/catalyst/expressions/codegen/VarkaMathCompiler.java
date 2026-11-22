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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathDomain;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathFn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathUnary;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

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
import org.apache.spark.sql.catalyst.expressions.EulerNumber;
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
 * math library within a contracted bound (2.5). The family claims each class so that
 * {@code floatingDecline} lets it through, and a function not yet built declines naming the row
 * that fuses it - 356 the trigonometric functions, 357 the hyperbolic ones, 358 the exponential,
 * logarithmic, power and root ones ({@code VARKA-356.md} 2.1).
 *
 * <p>Fused: {@code sin}, {@code cos}, {@code tan}, {@code asin}, {@code acos} and {@code atan},
 * each a {@link MathUnary} whose lanes call the method Spark's code calls; and {@code sec},
 * {@code csc} and {@code cot} as Spark writes them, {@code 1 / Math.cos(c)} and the rest: Java's
 * division of doubles, never Spark's {@code Divide}, so a zero divisor answers an infinity and is
 * no null, which is the floating division in {@code WRAP} over the call.
 *
 * <p>Row 358's functions but {@code pow} and {@code hypot} fuse the same way: {@code exp},
 * {@code expm1} and {@code cbrt} as a call; {@code ln}, {@code log10}, {@code log1p} and
 * {@code log} with one argument as a call under Spark's domain null ({@link VarkaMathDomain});
 * {@code log2} and {@code log} with a base as Spark writes them,
 * {@code StrictMath.log(c) / StrictMath.log(2)} and {@code StrictMath.log(x) / StrictMath.log(b)},
 * each logarithm under its null and the divide Java's, a constant divisor folded to the double
 * Spark computes a row.
 */
final class VarkaMathCompiler {

  private VarkaMathCompiler() {
  }

  /** A math function's SQL name and the row of the milestone plan that fuses it. */
  record Owner(String function, int row) {
    /** The reason the function declines with until its row lands. */
    String reason() {
      return function + " is row " + row + "'s: not fused yet";
    }
  }

  /**
   * The 26 classes and their functions' owners, in the order of {@code VARKA-356.md} 2.1. A table
   * of classes rather than a {@code switch}, so that {@code VarkaCoverageSuite}'s scan of this
   * file counts as admitted only the classes {@link #arm} lowers.
   */
  private static final List<Map.Entry<Class<? extends Expression>, Owner>> OWNERS = List.of(
      Map.entry(Sin.class, new Owner("sin", 356)),
      Map.entry(Cos.class, new Owner("cos", 356)),
      Map.entry(Tan.class, new Owner("tan", 356)),
      Map.entry(Asin.class, new Owner("asin", 356)),
      Map.entry(Acos.class, new Owner("acos", 356)),
      Map.entry(Atan.class, new Owner("atan", 356)),
      Map.entry(Atan2.class, new Owner("atan2", 356)),
      Map.entry(Sec.class, new Owner("sec", 356)),
      Map.entry(Csc.class, new Owner("csc", 356)),
      Map.entry(Cot.class, new Owner("cot", 356)),
      Map.entry(Sinh.class, new Owner("sinh", 357)),
      Map.entry(Cosh.class, new Owner("cosh", 357)),
      Map.entry(Tanh.class, new Owner("tanh", 357)),
      Map.entry(Asinh.class, new Owner("asinh", 357)),
      Map.entry(Acosh.class, new Owner("acosh", 357)),
      Map.entry(Atanh.class, new Owner("atanh", 357)),
      Map.entry(Exp.class, new Owner("exp", 358)),
      Map.entry(Expm1.class, new Owner("expm1", 358)),
      Map.entry(Log.class, new Owner("ln", 358)),
      Map.entry(Logarithm.class, new Owner("log", 358)),
      Map.entry(Log10.class, new Owner("log10", 358)),
      Map.entry(Log1p.class, new Owner("log1p", 358)),
      Map.entry(Log2.class, new Owner("log2", 358)),
      Map.entry(Pow.class, new Owner("pow", 358)),
      Map.entry(Hypot.class, new Owner("hypot", 358)),
      Map.entry(Cbrt.class, new Owner("cbrt", 358)));

  /**
   * The function {@code e} computes and its row, or empty for a node that is not one of the 26
   * classes. {@code floatingDecline} lets through exactly these. Every operand is a
   * {@code DOUBLE}: each class takes {@code DOUBLE} inputs by {@code ImplicitCastInputTypes}, so
   * the analyzer has cast any other operand before a plan reaches the compiler.
   */
  static Optional<Owner> ownerOf(Expression e) {
    for (var entry : OWNERS) {
      if (entry.getKey().isInstance(e)) {
        return Optional.of(entry.getValue());
      }
    }
    return Optional.empty();
  }

  /** The family's claim, or null. */
  static @Nullable VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (e) {
      case Sin f -> () -> call(MathFn.SIN, f.child(), inputs, literals, sink);
      case Cos f -> () -> call(MathFn.COS, f.child(), inputs, literals, sink);
      case Tan f -> () -> call(MathFn.TAN, f.child(), inputs, literals, sink);
      case Asin f -> () -> call(MathFn.ASIN, f.child(), inputs, literals, sink);
      case Acos f -> () -> call(MathFn.ACOS, f.child(), inputs, literals, sink);
      case Atan f -> () -> call(MathFn.ATAN, f.child(), inputs, literals, sink);
      case Sec f -> () -> reciprocal(MathFn.COS, f.child(), inputs, literals, sink);
      case Csc f -> () -> reciprocal(MathFn.SIN, f.child(), inputs, literals, sink);
      case Cot f -> () -> reciprocal(MathFn.TAN, f.child(), inputs, literals, sink);
      case Exp f -> () -> call(MathFn.EXP, f.child(), inputs, literals, sink);
      case Expm1 f -> () -> call(MathFn.EXPM1, f.child(), inputs, literals, sink);
      case Cbrt f -> () -> call(MathFn.CBRT, f.child(), inputs, literals, sink);
      case Log f -> () -> logOf(MathFn.LOG, 0.0, f.child(), inputs, literals, sink);
      case Log10 f -> () -> logOf(MathFn.LOG10, 0.0, f.child(), inputs, literals, sink);
      case Log1p f -> () -> logOf(MathFn.LOG1P, -1.0, f.child(), inputs, literals, sink);
      case Log2 f -> () -> logOf(MathFn.LOG, 0.0, f.child(), inputs, literals, sink)
          .map(v -> quotient(v, VarkaNodeCompiler.doubleSlot(StrictMath.log(2), sink)));
      case Logarithm f -> () -> logarithm(f, inputs, literals, sink);
      default -> ownerOf(e).<VarkaFamilyArm>map(owner -> () -> sink.decline(owner.reason(), e))
          .orElse(null);
    };
  }

  /**
   * {@code fn(x)}, a per-lane call over the double operand. The class's input type is
   * {@code DOUBLE}, so an operand of another type arrives under the analyzer's cast and fuses
   * where the floating casts family lowers it.
   */
  private static Optional<VarkaVectorIR> call(MathFn fn, Expression x, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    return operand(x, inputs, literals, sink).map(v -> new MathUnary(fn, v));
  }

  /** The double operand of a math function, compiled; a value on another lane declines. */
  private static Optional<VarkaVectorIR> operand(Expression x, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    Optional<VarkaVectorIR> operand = VarkaNodeCompiler.compileNode(x, inputs, literals, sink);
    if (operand.isPresent() && operand.get().laneType() != LaneType.DOUBLE) {
      return sink.decline("a math operand computed on the " + operand.get().laneType()
          + " lane", x);
    }
    return operand;
  }

  /**
   * {@code fn(x)} under Spark's domain null, null where {@code x <= asymptote}: {@code 0.0} for
   * {@code ln}, {@code log10} and {@code log2}, {@code -1.0} for {@code log1p}
   * ({@code UnaryLogExpression.yAsymptote}).
   */
  private static Optional<VarkaVectorIR> logOf(MathFn fn, double asymptote, Expression x,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    return operand(x, inputs, literals, sink).map(v -> VarkaMathDomain.nullAtOrBelow(fn, v,
        VarkaNodeCompiler.doubleSlot(asymptote, sink), sink.floatingFault()));
  }

  /** Java's {@code /} on doubles: the bare IEEE quotient, a zero divisor an infinity. */
  private static VarkaVectorIR quotient(VarkaVectorIR dividend, VarkaVectorIR divisor) {
    return new IntArith(IntOp.DIV, Overflow.WRAP, dividend, divisor);
  }

  /**
   * Spark's {@code log}: with {@code EulerNumber} on the left, as {@code log(x)} parses,
   * {@code StrictMath.log(x)} under its null and no divide, as Spark's code generator writes it;
   * otherwise {@code StrictMath.log(x) / StrictMath.log(b)}, null where either operand is at or
   * below {@code 0.0}, which is each logarithm's null carried through the divide. A constant base
   * folds: {@code StrictMath.log(b)} computed here is the double Spark computes a row, and a base
   * at or below {@code 0.0}, which nulls every row, declines. A base folded from
   * {@code EulerNumber} by the optimizer divides by {@code StrictMath.log(Math.E)}, as Spark's code
   * does then.
   */
  private static Optional<VarkaVectorIR> logarithm(Logarithm f, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    Expression base = f.left();
    if (base instanceof EulerNumber) {
      return logOf(MathFn.LOG, 0.0, f.right(), inputs, literals, sink);
    }
    if (base.foldable()) {
      Object b = base.eval(null);
      if (b == null) {
        return sink.decline("a log with a null base, null on every row", base);
      }
      double v = (Double) b;
      if (v <= 0.0) {
        return sink.decline("a log with a constant base at or below 0.0, null on every row",
            base);
      }
      return logOf(MathFn.LOG, 0.0, f.right(), inputs, literals, sink)
          .map(x -> quotient(x, VarkaNodeCompiler.doubleSlot(StrictMath.log(v), sink)));
    }
    Optional<VarkaVectorIR> x = logOf(MathFn.LOG, 0.0, f.right(), inputs, literals, sink);
    if (x.isEmpty()) {
      return x;
    }
    return logOf(MathFn.LOG, 0.0, base, inputs, literals, sink).map(b -> quotient(x.get(), b));
  }

  /**
   * {@code 1 / fn(x)}, Spark's {@code sec}, {@code csc} and {@code cot}: Java's {@code /} on
   * doubles, the literal {@code 1} on the left as Spark writes it, so {@code csc(0.0)} is
   * {@code +inf} and {@code csc(-0.0)} {@code -inf}.
   */
  private static Optional<VarkaVectorIR> reciprocal(MathFn fn, Expression x,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    return call(fn, x, inputs, literals, sink).map(v -> quotient(
        VarkaNodeCompiler.doubleSlot(1.0, sink), v));
  }
}
