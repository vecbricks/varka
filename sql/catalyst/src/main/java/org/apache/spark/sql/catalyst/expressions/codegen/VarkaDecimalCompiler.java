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
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.RuntimeReplaceable;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Truth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenDecimal;
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
 * a rescale's by the bound above, and a choice's or a pick's by every branch being of the node's
 * type, which Spark itself holds: each of those nodes is a {@code ComplexTypeMergingExpression},
 * whose {@code dataType} throws unless its branches' types are equal.
 *
 * <p>Why {@code +}, {@code -} and {@code *} need no overflow check. Spark types a decimal sum or
 * difference {@code (P, S)} with {@code S = max(s1, s2)} and
 * {@code P = max(p1 - s1, p2 - s2) + S + 1}, a product {@code (p1 + p2 + 1, s1 + s2)}
 * ({@code DecimalType}'s {@code resultDecimalType} in each node), and never casts the operands.
 * Where {@code P <= 18} the exact result always fits the type: each operand rescaled to
 * {@code S} has at most {@code P - 1} digits, so their sum has at most {@code P}; a product of
 * values below {@code 10^p1} and {@code 10^p2} is below {@code 10^(P - 1)}. So Spark's precision
 * check never fires, LEGACY's null, ANSI's raise and TRY's null are dead, and one wrapping long
 * operation, which cannot wrap below {@code 10^18}, is Spark's answer in every mode. A wider
 * result is a decline, never a check.
 *
 * <p>What declines, each naming its row: a type wider than 18 digits (row 359, and row 360 where
 * a rescale is needed); a sum or difference past 18 digits (row 360, and 363 past 38), a product
 * past 18 digits (row 361, and 362 and 363 past 38 or over an operand past 18); a cast that
 * lowers the scale or the integral digits, or one from an integral type (row 350); a negative
 * scale (the milestone plan's 2.8); and every operator no arm here claims yet.
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
   * The value of the decimal expression {@code e}, or empty with the reason noted. A constant
   * ({@code foldable}: a literal under coercion's cast, or any tree over literals alone) folds
   * first, so it takes one slot already at its type's scale.
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
    if (e.foldable()) {
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
      // The choices and the picks are under the switch of the family that claims them in the
      // chain, the condition family and the int arithmetic, as their int forms are: this path
      // never passes through compileNode, so it asks the switch and notes the family itself.
      case If i -> VarkaNodeCompiler.inFamily(VarkaFamily.CONDITION, () -> choice(i,
          List.of(i.predicate()), List.of(i.trueValue()), i.falseValue(), type, inputs,
          literals, sink), i, sink);
      case CaseWhen c -> VarkaNodeCompiler.inFamily(VarkaFamily.CONDITION,
          () -> caseWhen(c, type, inputs, literals, sink), c, sink);
      case Coalesce c -> VarkaNodeCompiler.inFamily(VarkaFamily.CONDITION,
          () -> coalesce(c, type, inputs, literals, sink), c, sink);
      case Greatest g -> VarkaNodeCompiler.inFamily(VarkaFamily.INT_ARITHMETIC,
          () -> pick(g, CollectionConverters.asJava(g.children()), type, inputs, literals, sink,
              VarkaVectorIR.Greatest::new), g, sink);
      case Least l -> VarkaNodeCompiler.inFamily(VarkaFamily.INT_ARITHMETIC,
          () -> pick(l, CollectionConverters.asJava(l.children()), type, inputs, literals, sink,
              VarkaVectorIR.Least::new), l, sink);
      // try_add, try_subtract and try_multiply over two numerics are Add, Subtract and Multiply
      // in TRY mode, which an analysed tree carries as their replacement.
      case RuntimeReplaceable r -> value(r.replacement(), inputs, literals, sink);
      case Add a -> sum(a, IntOp.ADD, a.left(), a.right(), type, inputs, literals, sink);
      case Subtract a -> sum(a, IntOp.SUB, a.left(), a.right(), type, inputs, literals, sink);
      case Multiply m -> product(m, type, inputs, literals, sink);
      default -> sink.decline("a decimal operator no arm builds yet: the rounding functions are "
          + "row 351's, and a quotient or a remainder is no row's in this milestone", e);
    };
  }

  /**
   * Why a value of {@code type}, wider than the long lane, declines: the wide representation is
   * row 359's, and a cast that rescales into it, the one coercion puts over the operand of the
   * smaller scale or over a choice's branch of the smaller scale, needs row 360's wide rescale as
   * well, and a cast over that rescale which lowers the scale or the integral digits row 350's
   * too. A sum, difference or product names the row of its own wide form instead, which is the
   * more precise answer, and so does a cast, a negation, a choice or a pick over one that needs
   * no rescale.
   */
  private static String wideReason(Expression e, DecimalType type) {
    return switch (e) {
      case RuntimeReplaceable r when r.replacement().dataType() instanceof DecimalType ->
          wideReason(r.replacement(), type);
      case Add a when a.left().dataType() instanceof DecimalType t1
          && a.right().dataType() instanceof DecimalType t2 -> sumReason("sum", t1, t2);
      case Subtract a when a.left().dataType() instanceof DecimalType t1
          && a.right().dataType() instanceof DecimalType t2 -> sumReason("difference", t1, t2);
      case Multiply m when m.left().dataType() instanceof DecimalType t1
          && m.right().dataType() instanceof DecimalType t2 -> productReason(t1, t2);
      default -> {
        if (rescalesUp(e, type)) {
          if (lowersOverRescale(e)) {
            yield "a decimal cast that lowers the scale or the integral digits is row 350's, "
                + "over a rescale to a decimal wider than " + MAX_PRECISION + " digits, rows 359 "
                + "and 360's";
          }
          yield "a rescale to a decimal wider than " + MAX_PRECISION + " digits is rows 359 and "
              + "360's";
        }
        yield wideArithmeticBelow(e)
            .orElse("a decimal wider than " + MAX_PRECISION + " digits is row 359's");
      }
    };
  }

  /**
   * The reason of the first sum, difference or product past 18 digits under {@code e}, reached
   * through the casts, negations, choices and picks over it, as {@code -(x + y)} reaches its sum.
   */
  private static Optional<String> wideArithmeticBelow(Expression e) {
    if (!(e instanceof Cast || e instanceof UnaryMinus || e instanceof Abs || e instanceof If
        || e instanceof CaseWhen || e instanceof Coalesce || e instanceof Greatest
        || e instanceof Least)) {
      return Optional.empty();
    }
    for (Expression child : CollectionConverters.asJava(e.children())) {
      Expression at = child instanceof RuntimeReplaceable r ? r.replacement() : child;
      if (at.dataType() instanceof DecimalType t && t.precision() > MAX_PRECISION) {
        if (at instanceof Add || at instanceof Subtract || at instanceof Multiply) {
          return Optional.of(wideReason(at, t));
        }
        Optional<String> below = wideArithmeticBelow(at);
        if (below.isPresent()) {
          return below;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * The exact result type Spark's rule gives an arithmetic node over two decimal operands, before
   * any cap at 38 digits: a pair of ints, since {@code DecimalType} refuses a precision past 38.
   */
  private record ResultType(int precision, int scale) {

    /** A sum's or difference's: {@code (max(p1 - s1, p2 - s2) + S + 1, S)}, {@code S} the
     *  larger scale. */
    static ResultType ofSum(DecimalType t1, DecimalType t2) {
      int s = Math.max(t1.scale(), t2.scale());
      return new ResultType(Math.max(t1.precision() - t1.scale(),
          t2.precision() - t2.scale()) + s + 1, s);
    }

    /** A product's: {@code (p1 + p2 + 1, s1 + s2)}. */
    static ResultType ofProduct(DecimalType t1, DecimalType t2) {
      return new ResultType(t1.precision() + t2.precision() + 1, t1.scale() + t2.scale());
    }

    /** As {@code DecimalType.simpleString} renders a type, whatever the precision. */
    String simpleString() {
      return "decimal(" + precision + "," + scale + ")";
    }
  }

  /**
   * Why a sum or difference past 18 digits declines: its result's rows past 38 digits, which only
   * an operand past 18 digits reaches, else an operand's, else its result's.
   */
  private static String sumReason(String what, DecimalType t1, DecimalType t2) {
    int p = ResultType.ofSum(t1, t2).precision();
    if (p <= DecimalType.MAX_PRECISION()
        && (t1.precision() > MAX_PRECISION || t2.precision() > MAX_PRECISION)) {
      return "a decimal " + what + " over an operand wider than " + MAX_PRECISION
          + " digits is rows 359 and 360's";
    }
    return arithmeticReason(what, p, "360", "360 and 363");
  }

  /** Why a product past 18 digits declines: an operand's, or its result's, row. */
  private static String productReason(DecimalType t1, DecimalType t2) {
    if (ResultType.ofProduct(t1, t2).precision() <= DecimalType.MAX_PRECISION()
        && (t1.precision() > MAX_PRECISION || t2.precision() > MAX_PRECISION)) {
      return "a decimal product over an operand wider than " + MAX_PRECISION
          + " digits is rows 362 and 363's";
    }
    return arithmeticReason("product", ResultType.ofProduct(t1, t2).precision(), "361",
        "362 and 363");
  }

  /**
   * Why an arithmetic node typed past 18 digits, its exact result of {@code p} digits, has no
   * long form: it is the wider rows' ({@code to38} up to 38 digits, {@code past38} where Spark
   * caps it). A node typed wide whose rule gives 18 digits or fewer, which no analysed tree
   * holds, has only the wide type's reason.
   */
  private static String arithmeticReason(String what, int p, String to38, String past38) {
    if (p <= MAX_PRECISION) {
      return "a decimal wider than " + MAX_PRECISION + " digits is row 359's";
    }
    if (p > DecimalType.MAX_PRECISION()) {
      return "a decimal " + what + " of " + p + " digits, capped at "
          + DecimalType.MAX_PRECISION() + ", is rows " + past38 + "'s";
    }
    return "a decimal " + what + " of " + p + " digits is row " + to38 + "'s";
  }

  /**
   * Whether {@code e}, of {@code type}, rescales up into a type wider than 18 digits: a cast to it
   * from a decimal of a smaller scale, or any other cast, a negation, an {@code abs}, a choice or a
   * pick over a wide value that does - {@code IF(p, d18, d9s4)} at {@code DECIMAL(22, 4)} casts
   * its {@code d18} branch up by two digits of scale, and {@code -IF(p, d18, d9s4)} negates that.
   */
  private static boolean rescalesUp(Expression e, DecimalType type) {
    return switch (e) {
      case Cast c when c.child().dataType() instanceof DecimalType from ->
          from.scale() < type.scale() || rescalesIntoWide(c.child());
      case UnaryMinus m -> rescalesIntoWide(m.child());
      case Abs a -> rescalesIntoWide(a.child());
      case If _, CaseWhen _, Coalesce _, Greatest _, Least _ -> {
        for (Expression child : CollectionConverters.asJava(e.children())) {
          if (rescalesIntoWide(child)) {
            yield true;
          }
        }
        yield false;
      }
      default -> false;
    };
  }

  /**
   * Whether, on the path {@link #rescalesUp} walks in {@code e}, a cast that lowers the scale or
   * the integral digits sits at or over the rescale - {@code CAST(IF(p, d18s2, d9s4) AS
   * DECIMAL(20, 2))} lowers by two digits of scale the choice whose branch was rescaled up - so
   * that the cast is row 350's beside the wide rescale.
   */
  private static boolean lowersOverRescale(Expression e) {
    return switch (e) {
      case Cast c when c.child().dataType() instanceof DecimalType from
          && c.dataType() instanceof DecimalType to ->
          (!keepsDigits(from, to) && rescalesUp(c, to)) || wideLowersOverRescale(c.child());
      case UnaryMinus m -> wideLowersOverRescale(m.child());
      case Abs a -> wideLowersOverRescale(a.child());
      case If _, CaseWhen _, Coalesce _, Greatest _, Least _ -> {
        for (Expression child : CollectionConverters.asJava(e.children())) {
          if (wideLowersOverRescale(child)) {
            yield true;
          }
        }
        yield false;
      }
      default -> false;
    };
  }

  /** {@link #lowersOverRescale} of {@code e} where it is a decimal wider than 18 digits. */
  private static boolean wideLowersOverRescale(Expression e) {
    return e.dataType() instanceof DecimalType t && t.precision() > MAX_PRECISION
        && lowersOverRescale(e);
  }

  /** Whether {@code e} is a decimal wider than 18 digits that {@link #rescalesUp} into. */
  private static boolean rescalesIntoWide(Expression e) {
    return e.dataType() instanceof DecimalType t && t.precision() > MAX_PRECISION
        && rescalesUp(e, t);
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
    sink.enterDecimal();
    try {
      return compiledOperands(whole, left, right, inputs, literals, sink);
    } finally {
      sink.exitDecimal();
    }
  }

  private static Optional<List<VarkaVectorIR>> compiledOperands(Expression whole,
      Expression left, Expression right, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    // A rescale into a wide common type names rows 359 and 360 whichever side carries it, so
    // both sides are read before either declines on its own.
    for (Expression side : List.of(left, right)) {
      if (rescalesIntoWide(side)) {
        return sink.decline(wideReason(side, (DecimalType) side.dataType()), side);
      }
    }
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
   * {@code child IS NOT NULL} over a decimal value, or empty with the reason noted: the value is
   * compiled under every cast over it that only widens its precision ({@link #stripWidening}),
   * so a form no arm builds declines, and its validity is then derived from the leaves
   * by {@link #valid}, since an IR null test reads a column's word or a condition's known-ness and
   * never a computed value's word. Every leaf of the value is a leaf of the test, so its slots are
   * still loaded and checked, on every lane: Spark's {@code IS NULL} evaluates its child, which
   * reads a valid row's value through {@code ArrowColumnVector.getDecimal} and raises on a slot
   * outside the precision, and the kernel declines that batch as every other reader of the leaf
   * does. Spark's generated filter reads every leaf too, a {@code coalesce}'s later operands and
   * a {@code CASE}'s untaken branches included: it evaluates every column a predicate references
   * before the predicate ({@code FilterExec.genPredicate}). Only its interpreted path, with
   * whole-stage codegen off, stops at a {@code coalesce}'s first non-null operand and a
   * {@code CASE}'s taken branch, and there a decline hands the batch to the row engine, which
   * answers as Spark does.
   *
   * <p>The value is compiled as a probe, for its declines alone: a choice's condition is not
   * compiled in it, and what it appended to the tables - its leaves, and its own long literals,
   * such as a rescale's factor, which the test does not read - is rolled back, so the test holds
   * {@link #valid}'s leaves and literals in {@link #valid}'s order. Each condition is then
   * compiled once, by {@link #valid}, and a null test nested in a condition costs its own size
   * rather than doubling at every level. The families the probe notes are kept: a family's switch
   * declines every shape that holds one of its nodes, the null test over a pick or a choice
   * included, and the record lists the families so held ({@code VarkaFamilySwitchSuite}).
   */
  static Optional<Cond> nullTested(Expression child, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    // Read before the probe, whose width check would otherwise decline a widening cast to more
    // than 18 digits over a value the long lane holds.
    Expression tested = stripWidening(child);
    sink.enterDecimal();
    try {
      int inputMark = inputs.size();
      int literalMark = literals.size();
      int longMark = sink.longMark();
      boolean wasProbe = sink.decimalProbe(true);
      Optional<Scaled> probed;
      try {
        probed = value(tested, inputs, literals, sink);
      } finally {
        sink.decimalProbe(wasProbe);
      }
      inputs.truncate(inputMark);
      literals.truncate(literalMark);
      sink.truncateLong(longMark);
      if (probed.isEmpty()) {
        return Optional.empty();
      }
      return valid(tested, inputs, literals, sink);
    } finally {
      sink.exitDecimal();
    }
  }

  /**
   * Where the decimal value {@code e}, which {@link #value} compiles, is not null, as a condition
   * over its leaves: a column's validity word, through every null-intolerant form over it (a
   * rescale, a negation, {@code abs}); always for a constant; the choice's condition picking its
   * branches' validity for {@code If} and {@code CASE}, whose unknown condition takes the ELSE as
   * {@link VarkaVectorIR.CondIf} does; and any child's for {@code coalesce}, {@code greatest} and
   * {@code least}, which are null only where every child is. Every condition here is total.
   */
  static Optional<Cond> valid(Expression e, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    if (e.foldable()) {
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
        // Compiled in the order value() compiles the choice, branch by branch and the ELSE last,
        // so that the leaves take their slots in one order, and folded from the right.
        var conds = new ArrayList<Cond>();
        var branchesValid = new ArrayList<Cond>();
        for (Tuple2<Expression, Expression> branch
            : CollectionConverters.asJava(c.branches())) {
          Optional<Cond> k = condition(branch._1(), c, inputs, literals, sink);
          if (k.isEmpty()) {
            yield Optional.empty();
          }
          Optional<Cond> t = valid(branch._2(), inputs, literals, sink);
          if (t.isEmpty()) {
            yield Optional.empty();
          }
          conds.add(k.get());
          branchesValid.add(t.get());
        }
        Optional<Cond> otherwise = valid(c.elseValue().get(), inputs, literals, sink);
        if (otherwise.isEmpty()) {
          yield Optional.empty();
        }
        Cond folded = otherwise.get();
        for (int b = conds.size() - 1; b >= 0; b--) {
          folded = new VarkaVectorIR.CondIf(conds.get(b), branchesValid.get(b), folded);
        }
        yield Optional.of(folded);
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

  /** How many choice conditions this thread compiled, which a suite reads and resets. */
  static final ThreadLocal<int[]> CONDITIONS_COMPILED = ThreadLocal.withInitial(() -> new int[1]);

  /**
   * A choice's condition on the long lane, where its values are: a condition over int or narrow
   * values widens there, and one with no long-lane form, a floating null test, declines. A
   * condition is compiled once in a call of this class: a {@code coalesce} reads its operand's
   * conditions for the operand's value and again for its validity, and the second read is the
   * first's node. Under {@link #nullTested}'s probe no condition is compiled, and a constant
   * stands in for it.
   */
  private static Optional<Cond> condition(Expression predicate, Expression whole,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    if (sink.decimalProbe()) {
      return Optional.of(new CondConst(Truth.TRUE, LaneType.LONG));
    }
    Cond compiled = sink.decimalCondition(predicate);
    if (compiled != null) {
      return Optional.of(compiled);
    }
    CONDITIONS_COMPILED.get()[0]++;
    Optional<Cond> cond = VarkaConditionCompiler.compileCond(predicate, inputs, literals, sink);
    if (cond.isEmpty()) {
      return cond;
    }
    Optional<Cond> lifted = VarkaConditionCompiler.onLongLane(cond.get());
    if (lifted.isEmpty()) {
      return sink.decline("a choice of decimals under a condition with no long-lane form", whole);
    }
    sink.decimalCondition(predicate, lifted.get());
    return lifted;
  }

  /**
   * {@code IF} and {@code CASE WHEN} with an {@code ELSE}: the right fold of
   * {@code IfElse(cond, value, rest)} over the branches. After coercion every branch has the
   * node's type, a branch that was cast carrying its rescale inside, so the branches are of one
   * scale and pick as their unscaled values (see {@link #branch}).
   */
  private static Optional<Scaled> choice(Expression whole, List<Expression> predicates,
      List<Expression> values, Expression otherwise, DecimalType type, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
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

  /** {@code CASE WHEN}, which needs an {@code ELSE}, as the {@link #choice} of its branches. */
  private static Optional<Scaled> caseWhen(CaseWhen c, DecimalType type, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    if (c.elseValue().isEmpty()) {
      return sink.decline("a CASE without an ELSE needs a typed null value, which is row 333's",
          c);
    }
    List<Tuple2<Expression, Expression>> branches = CollectionConverters.asJava(c.branches());
    var predicates = new ArrayList<Expression>(branches.size());
    var values = new ArrayList<Expression>(branches.size());
    for (Tuple2<Expression, Expression> branch : branches) {
      predicates.add(branch._1());
      values.add(branch._2());
    }
    return choice(c, predicates, values, c.elseValue().get(), type, inputs, literals, sink);
  }

  /**
   * {@code coalesce}: the right fold of {@code IfElse(valid(head), head, rest)}, the last child
   * the end of the fold. {@code nvl} and {@code ifnull} arrive as it.
   */
  private static Optional<Scaled> coalesce(Coalesce c, DecimalType type, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
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

  /**
   * One branch of a choice, of the choice's scale, or empty with the reason noted. Every branch
   * has the node's type, precision included, in any tree that has a type at all: {@code If},
   * {@code CaseWhen}, {@code Coalesce}, {@code Greatest} and {@code Least} are
   * {@code ComplexTypeMergingExpression}s, whose {@code dataType} throws on branches of two types,
   * and a branch's {@link Scaled} is of its expression's type. So the bound
   * {@code |u| <= 10^P - 1} that a rescale over the choice relies on holds of every branch; the
   * scale check is a guard against a value arm returning another type, not a tree Spark builds.
   */
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
    if (!keepsDigits(from, to)) {
      return sink.decline("a decimal cast that lowers the scale or the integral digits rounds "
          + "or can overflow, and is row 350's", c);
    }
    return value(c.child(), inputs, literals, sink)
        .map(x -> new Scaled(rescale(x, k, sink).unscaled(), to));
  }

  /**
   * Whether a cast from {@code from} to {@code to} keeps the scale or raises it and keeps every
   * integral digit, so that it multiplies by {@code 10^(S - s)} and cannot round or overflow.
   */
  private static boolean keepsDigits(DecimalType from, DecimalType to) {
    return to.scale() >= from.scale()
        && to.precision() - to.scale() >= from.precision() - from.scale();
  }

  /**
   * {@code e} under every cast over it that keeps the scale and only widens the precision. Such a
   * cast multiplies by nothing and cannot null or fail in any mode, so a null test reads through
   * it: {@code CAST(d18 AS DECIMAL(20, 0)) IS NULL} is {@code d18}'s null test, though the cast's
   * type is past the long lane.
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

  /**
   * {@code x + y} or {@code x - y} (VARKA-349 2.2): both operands rescaled to the result's scale
   * {@code S}, then one wrapping long add or subtract, in every mode. Each rescaled operand has
   * at most {@code P - 1 <= 17} digits, so neither rescale leaves the long lane and the result,
   * at most {@code 2 * (10^(P - 1) - 1) < 10^P}, is within the node's type, which is Spark's
   * {@code resultDecimalType}. A constant one has been folded by {@link #value}.
   */
  private static Optional<Scaled> sum(Expression whole, IntOp op, Expression left,
      Expression right, DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    String what = op == IntOp.ADD ? "sum" : "difference";
    if (!(left.dataType() instanceof DecimalType t1)
        || !(right.dataType() instanceof DecimalType t2)) {
      return sink.decline("a decimal " + what + " with an operand that is not a decimal", whole);
    }
    ResultType rule = ResultType.ofSum(t1, t2);
    Optional<String> mistyped = mistyped(whole, what, rule, type);
    if (mistyped.isPresent()) {
      return sink.decline(mistyped.get(), whole);
    }
    int s = rule.scale();
    Optional<Scaled> a = atScale(left, t1, s, inputs, literals, sink);
    if (a.isEmpty()) {
      return Optional.empty();
    }
    Optional<Scaled> b = atScale(right, t2, s, inputs, literals, sink);
    if (b.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Scaled(new IntArith(op, Overflow.WRAP, a.get().unscaled(),
        b.get().unscaled()), type));
  }

  /**
   * The operand {@code e} of type {@code t} rescaled to scale {@code s >= t.scale()}. A constant
   * takes its unscaled value at {@code s} in a slot of its own, folded at compile time, as
   * no coercion cast sits over an arithmetic operand to fold it there; any other operand is its
   * value times the slot {@code 10^(s - t.scale())}.
   */
  private static Optional<Scaled> atScale(Expression e, DecimalType t, int s,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    if (e.foldable() && t.scale() >= 0) {
      return literal(e, new DecimalType(t.precision() + s - t.scale(), s), sink);
    }
    return value(e, inputs, literals, sink).map(x -> rescale(x, s - x.type().scale(), sink));
  }

  /**
   * {@code x * y} (VARKA-349 2.3): one wrapping long multiply of the unscaled values, in every
   * mode, with no rescale, since the scales add: the product of {@code u1 / 10^s1} and
   * {@code u2 / 10^s2} is {@code u1 * u2 / 10^(s1 + s2)}. Its magnitude is at most
   * {@code (10^p1 - 1)(10^p2 - 1) < 10^(p1 + p2)}, within the node's type
   * {@code (p1 + p2 + 1, s1 + s2)} and below {@code 2^63} wherever that type is within 18 digits.
   * A constant one has been folded by {@link #value}.
   */
  private static Optional<Scaled> product(Multiply m, DecimalType type, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    if (!(m.left().dataType() instanceof DecimalType t1)
        || !(m.right().dataType() instanceof DecimalType t2)) {
      return sink.decline("a decimal product with an operand that is not a decimal", m);
    }
    Optional<String> mistyped = mistyped(m, "product", ResultType.ofProduct(t1, t2), type);
    if (mistyped.isPresent()) {
      return sink.decline(mistyped.get(), m);
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
   * Why an arithmetic node within 18 digits is typed other than the exact {@code (p, s)} of
   * Spark's rule, or empty where it is not. No analysed tree holds such a node, and one is
   * declined rather than computed at a type it does not have. A node typed past 18 digits does
   * not get here: {@link #value} declines it first, naming its row ({@link #wideReason}); one
   * typed narrow whose rule gives more than 18 digits does, and is declined as mistyped, the
   * rule's type rendered from its two ints, since past 38 digits it is no {@code DecimalType}.
   */
  private static Optional<String> mistyped(Expression whole, String what, ResultType rule,
      DecimalType type) {
    if (type.precision() != rule.precision() || type.scale() != rule.scale()) {
      return Optional.of("a decimal " + what + " typed " + type.simpleString() + " where Spark's "
          + "rule gives " + rule.simpleString() + ": " + whole.nodeName());
    }
    return Optional.empty();
  }

  /** The leaf of a decimal column on the long lane, its precision the column's. */
  private static DecimalColumn leaf(BoundReference br, VarkaSlotTable inputs) {
    int precision = ((DecimalType) br.dataType()).precision();
    return new DecimalColumn(inputs.intern(br.ordinal()), LaneType.LONG, precision);
  }

  /**
   * A constant's unscaled value at its type's scale, in a slot of the long table. A literal
   * arrives folded in a real query; in a tree the analyzer alone resolved it is the literal under
   * the casts type coercion put over it, or another tree over literals alone, and is folded here
   * by evaluating it, which Spark's constant folding does the same way. A null constant declines
   * naming row 333, whose typed null value it needs: a choice keeps one in a real query, as
   * {@code IF(c, NULL, d)}, a {@code CASE} branch of {@code NULL} and {@code nullif}, which the
   * analyzer rewrites to an {@code IF}, do. An evaluation that fails, or a value that does not fit
   * 18 digits, declines too, and leaves the row engine to raise or answer.
   */
  private static Optional<Scaled> literal(Expression e, DecimalType type, DeclineSink sink) {
    Object value;
    try {
      value = e.eval(null);
    } catch (RuntimeException failed) {
      return sink.decline("a decimal constant whose evaluation fails", e);
    }
    if (!(value instanceof Decimal d)) {
      return sink.decline("a null decimal literal needs a typed null value, which is row "
          + "333's", e);
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
