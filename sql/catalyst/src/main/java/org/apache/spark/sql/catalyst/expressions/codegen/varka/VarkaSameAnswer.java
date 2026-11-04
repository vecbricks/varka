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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.IntPredicate;
import javax.annotation.Nullable;

import scala.Tuple2;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.BinaryArithmetic;
import org.apache.spark.sql.catalyst.expressions.CaseWhen;
import org.apache.spark.sql.catalyst.expressions.Coalesce;
import org.apache.spark.sql.catalyst.expressions.Divide;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Greatest;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.Least;
import org.apache.spark.sql.catalyst.expressions.NaNvl;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.DoubleType;
import org.apache.spark.sql.types.FloatType;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.sql.types.TimestampNTZType;
import org.apache.spark.sql.types.TimestampType;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * The one definition of "the same answer" every Varka comparator uses (VARKA-556): the
 * both-bodies check in sql/core main, the kernel checks against the reference evaluator and
 * Spark in the catalyst tests, and the expression harness's comparison with Spark in the sql/core
 * tests. It lives in catalyst main because that is the one tree all three see.
 *
 * <p>Two answers agree when they are the same bits at the output's width: the sign of a zero
 * counts, a null's data is never read, a decimal is its unscaled value at the output's scale, a
 * bit output is its data bit under its validity bit. The one question the levels answer
 * differently is two NaNs of different bits. Neither the JLS nor Spark fixes which NaN an add or
 * a multiply over two NaNs returns, and HotSpot's tiers answer it differently (the interpreter's
 * {@code dadd} returns the right operand's payload, C1 the left's unless it moved a constant
 * left operand right, C2 either, see {@code sql/varka/skills/the-jit.md}), so two engines, two
 * bodies, or one method before and after a compile can return different NaNs there and be
 * equally right. Everywhere else a NaN's payload is fixed - a subtraction or a division returns
 * its left operand's on every tier, and a pick, a negation, an {@code abs} or an arithmetic over
 * one NaN returns a NaN whose bits follow from its inputs - and a different NaN is a wrong answer.
 */
public final class VarkaSameAnswer {

  private VarkaSameAnswer() {
  }

  /**
   * What a comparator accepts of two NaNs of different bits, from strictest to loosest. Each
   * comparator names its level, and the contract suite ({@code VarkaSameAnswerContractSuite})
   * holds it to that level's verdict on one table of tricky pairs.
   */
  public enum Level {
    /** Never: bytes at the output's width. A non-floating output under any comparator. */
    EXACT,
    /**
     * Any NaN agrees with any NaN on a {@code FLOAT} or {@code DOUBLE} output, whatever the
     * expression: {@code VarkaKernelRunner.compareBodies}, which sees only the output's type, so
     * it also excuses a pick that returned the wrong one of two column NaNs.
     */
    TYPE,
    /**
     * Where the row's answer is a NaN whose payload is open by its provenance ({@link #openNaN}),
     * over the IR ({@code VarkaKernelChecks.sameAnswer}) or over the Catalyst expression
     * ({@link #sameRow} with {@link #openNaN(Expression, InternalRow)}): one rule, two front ends.
     */
    ROW,
    /** Any NaN agrees with any NaN: Spark's {@code checkAnswer} and {@code VarkaSparkFuzz}. */
    ANY_NAN
  }

  // ---------------------------------------------------------------------------------------------
  // The type level: two raw values of one floating width.
  // ---------------------------------------------------------------------------------------------

  /**
   * Whether {@code a} and {@code b}, the raw bits of one row of a {@code width}-byte output, are
   * both NaNs: 8 bytes a {@code DOUBLE}, 4 a {@code FLOAT} in the low 32 bits; false at any other
   * width. The {@link Level#TYPE} exception to byte equality; it allocates nothing, as the
   * both-bodies check runs it per row.
   */
  public static boolean bothNaN(int width, long a, long b) {
    return switch (width) {
      case Double.BYTES -> Double.isNaN(Double.longBitsToDouble(a))
          && Double.isNaN(Double.longBitsToDouble(b));
      case Float.BYTES -> Float.isNaN(Float.intBitsToFloat((int) a))
          && Float.isNaN(Float.intBitsToFloat((int) b));
      default -> false;
    };
  }

  // ---------------------------------------------------------------------------------------------
  // The row level: one rule, written once over a node of either tree.
  // ---------------------------------------------------------------------------------------------

  /** How a node's value on the row is made from its children's, as far as a NaN's bits go. */
  private sealed interface Step<N> {
    /**
     * A two-operand floating arithmetic; {@code eitherPayload} where its NaN over two NaNs may be
     * either operand's: an add or a multiply, which a tier may swap, and a remainder, which no
     * tier pins. A subtraction or a division returns its left operand's.
     */
    record Arith<N>(N left, N right, boolean eitherPayload) implements Step<N> {}

    /** A node of one child that passes its child's payload on: a negation, a cast, a root. */
    record Pass<N>(N child) implements Step<N> {}

    /** A pick that returns {@code taken}'s value on the row, or nothing (null). */
    record Pick<N>(@Nullable N taken) implements Step<N> {}

    /** {@code greatest} or {@code least}: the first child whose value is a NaN is returned. */
    record FirstNaN<N>(List<N> children) implements Step<N> {}

    /** Any other node: it may return any floating child's value. */
    record Other<N>(List<N> children) implements Step<N> {}
  }

  /** One tree's view of a node on one row, all the rule reads. */
  private interface Front<N> {
    boolean floating(N node);

    Step<N> step(N node);

    /** The raw bits of the node's value where it is a NaN; null where it is not or is null. */
    @Nullable Long nanBits(N node);
  }

  /**
   * Whether {@code node}'s value on the row is a NaN whose payload Java leaves open: one made by
   * an add, a multiply or a remainder from two NaN operands of different bits, or passed on from
   * one. A node whose own value is not a NaN is never open, whatever its children. An arithmetic
   * over one NaN returns that NaN, quieted, on every tier, and a subtraction or a division over
   * two returns its left one, so its payload is fixed by that operand's; a node of one child
   * passes its child's on; a pick passes on the payload of the one child it returns on the row,
   * and of no other: an {@code IF} or a {@code CASE} the branch its conditions take, a
   * {@code coalesce} its first non-null child, a {@code nanvl} its second where its first is a
   * NaN, a {@code greatest} or {@code least} its first child whose value is a NaN (Spark's fold
   * keeps the first of equal values, and every NaN compares equal to every other). Any other node
   * is open where a floating child whose value is a NaN is.
   *
   * <p>The rule reads no payload it decides is open. Which values are NaNs is the same on every
   * tier, and an arithmetic compares its operands' bits only once neither is open, when each is
   * a column's, a literal's or a NaN whose payload this rule fixes: the same on every tier too.
   * It never compares the bits of a node's value with a child's, which would read two
   * evaluations of an open payload that need not agree, and the conditions that choose a branch
   * read no payload: a comparison, {@code isnan} or a null test holds alike of every NaN.
   */
  private static <N> boolean open(N node, Front<N> front) {
    if (!front.floating(node) || front.nanBits(node) == null) {
      return false;
    }
    return switch (front.step(node)) {
      case Step.Arith<N> a -> {
        Long l = front.nanBits(a.left());
        Long r = front.nanBits(a.right());
        if (l != null && r != null && a.eitherPayload()) {
          yield open(a.left(), front) || open(a.right(), front) || !l.equals(r);
        }
        yield l != null ? open(a.left(), front) : r != null && open(a.right(), front);
      }
      case Step.Pass<N> p -> open(p.child(), front);
      case Step.Pick<N> p ->
          p.taken() != null && front.nanBits(p.taken()) != null && open(p.taken(), front);
      case Step.FirstNaN<N> f -> {
        for (N child : f.children()) {
          if (front.nanBits(child) != null) {
            yield open(child, front);
          }
        }
        yield false;
      }
      case Step.Other<N> o -> {
        for (N child : o.children()) {
          if (front.nanBits(child) != null && open(child, front)) {
            yield true;
          }
        }
        yield false;
      }
    };
  }

  // ---------------------------------------------------------------------------------------------
  // The row level over the IR.
  // ---------------------------------------------------------------------------------------------

  /**
   * The row as the IR rule reads it: each node's value, as a reference evaluator computes it
   * (empty where it is null), and whether a condition is known true. A condition that is false
   * or unknown sends an {@code IfElse} to its else branch, as the kernel's blend does.
   */
  public interface IrRow {
    OptionalLong value(VarkaVectorIR node);

    boolean holds(VarkaVectorIR.Cond cond);
  }

  /**
   * Whether two answers of output {@code root} agree at {@link Level#ROW}: the same bits, or two
   * NaNs where the row's answer is a NaN whose payload is open ({@link #openNaN}). {@code want}
   * and {@code got} are lane values, a {@code FLOAT}'s in the low 32 bits.
   */
  public static boolean sameAnswer(VarkaVectorIR root, long want, long got, IrRow row) {
    if (want == got) {
      return true;
    }
    VarkaVectorIR.LaneType lane = root.laneType();
    return lane.isFloating() && isNaN(lane, want) && isNaN(lane, got) && openNaN(root, row);
  }

  /** {@link #open}'s rule over the IR: whether {@code node}'s NaN on {@code row} is open. */
  public static boolean openNaN(VarkaVectorIR node, IrRow row) {
    return open(node, new Front<VarkaVectorIR>() {
      @Override public boolean floating(VarkaVectorIR n) {
        return !(n instanceof VarkaVectorIR.Cond) && n.laneType().isFloating();
      }

      @Override public Step<VarkaVectorIR> step(VarkaVectorIR n) {
        return switch (n) {
          case VarkaVectorIR.IntArith a -> new Step.Arith<>(a.left(), a.right(),
              a.op() == VarkaVectorIR.IntOp.ADD || a.op() == VarkaVectorIR.IntOp.MUL);
          case VarkaVectorIR.IfElse i ->
              new Step.Pick<>(row.holds(i.cond()) ? i.thenNode() : i.elseNode());
          case VarkaVectorIR.Greatest g -> new Step.FirstNaN<>(List.of(g.left(), g.right()));
          case VarkaVectorIR.Least l -> new Step.FirstNaN<>(List.of(l.left(), l.right()));
          // Listed, not defaulted, so that a new value node is a compile error here until it says
          // how its value is made from its children's (a condition or a calendar node, never
          // floating, is matched by its family).
          case VarkaVectorIR.ColumnRef _, VarkaVectorIR.DecimalColumn _,
              VarkaVectorIR.LiteralSlot _, VarkaVectorIR.NullValue _, VarkaVectorIR.AddDays _,
              VarkaVectorIR.SubDays _, VarkaVectorIR.DateDiff _, VarkaVectorIR.CondValue _,
              VarkaVectorIR.BoolOutput _, VarkaVectorIR.DayOfWeek _, VarkaVectorIR.WeekDay _,
              VarkaVectorIR.DayOfWeekIso _, VarkaVectorIR.NextDay _, VarkaVectorIR.ThursdayOf _,
              VarkaVectorIR.Chrono _, VarkaVectorIR.AddMonths _, VarkaVectorIR.MakeDate _,
              VarkaVectorIR.IntNeg _, VarkaVectorIR.ConstDivide _, VarkaVectorIR.BoundedDivide _,
              VarkaVectorIR.BitOp _, VarkaVectorIR.BitCount _, VarkaVectorIR.MathUnary _,
              VarkaVectorIR.Cond _, VarkaVectorIR.GuardedDay _, VarkaVectorIR.GuardedRange _,
              VarkaVectorIR.WidenLane _, VarkaVectorIR.ConvertLane _, VarkaVectorIR.NarrowLane _,
              VarkaVectorIR.WidenDecimal _ -> byValueChildren(n);
        };
      }

      // A node of one value child passes its payload on; a node of more may return any.
      private Step<VarkaVectorIR> byValueChildren(VarkaVectorIR n) {
        var values = new ArrayList<VarkaVectorIR>();
        for (VarkaVectorIR child : VarkaVectorIR.childrenOf(n)) {
          if (!(child instanceof VarkaVectorIR.Cond)) {
            values.add(child);
          }
        }
        return values.size() == 1 ? new Step.Pass<>(values.get(0)) : new Step.Other<>(values);
      }

      @Override public @Nullable Long nanBits(VarkaVectorIR n) {
        if (!floating(n)) {
          return null;
        }
        OptionalLong v = row.value(n);
        return v.isPresent() && isNaN(n.laneType(), v.getAsLong()) ? v.getAsLong() : null;
      }
    });
  }

  /** Whether {@code bits} at {@code lane} are a NaN, a {@code FLOAT}'s in the low 32 bits. */
  private static boolean isNaN(VarkaVectorIR.LaneType lane, long bits) {
    return lane == VarkaVectorIR.LaneType.DOUBLE ? Double.isNaN(Double.longBitsToDouble(bits))
        : Float.isNaN(Float.intBitsToFloat((int) bits));
  }

  // ---------------------------------------------------------------------------------------------
  // The row level over a Catalyst expression, and the row comparison built on it.
  // ---------------------------------------------------------------------------------------------

  /**
   * {@link #open}'s rule over a Catalyst expression: whether {@code e}'s value on {@code row} (a
   * row {@code e} is bound to) is a NaN whose payload is open. A subexpression that raises on the
   * row decides nothing.
   */
  public static boolean openNaN(Expression e, InternalRow row) {
    return open(e, new Front<Expression>() {
      @Override public boolean floating(Expression n) {
        return isFloating(n.dataType());
      }

      @Override public Step<Expression> step(Expression n) {
        List<Expression> children = CollectionConverters.asJava(n.children());
        if (n instanceof BinaryArithmetic a) {
          return new Step.Arith<>(a.left(), a.right(),
              !(a instanceof Subtract || a instanceof Divide));
        }
        if (children.size() == 1) {
          return new Step.Pass<>(children.get(0));
        }
        if (n instanceof If || n instanceof CaseWhen || n instanceof Coalesce
            || n instanceof NaNvl) {
          return new Step.Pick<>(returned(n, row));
        }
        if (n instanceof Greatest || n instanceof Least) {
          return new Step.FirstNaN<>(children);
        }
        return new Step.Other<>(children);
      }

      @Override public @Nullable Long nanBits(Expression n) {
        return VarkaSameAnswer.nanBits(n, row);
      }
    });
  }

  /**
   * The child whose value the pick {@code e} returns on {@code row}: an {@code IF}'s or a
   * {@code CASE}'s branch its conditions take, a {@code coalesce}'s first non-null child, a
   * {@code nanvl}'s second where its first is a NaN and its first elsewhere; null where none is
   * (a {@code CASE} of no else that takes no branch, a {@code coalesce} of nulls) or a condition
   * raises.
   */
  private static @Nullable Expression returned(Expression e, InternalRow row) {
    try {
      if (e instanceof If i) {
        return Boolean.TRUE.equals(i.predicate().eval(row)) ? i.trueValue() : i.falseValue();
      }
      if (e instanceof CaseWhen c) {
        for (Tuple2<Expression, Expression> b : CollectionConverters.asJava(c.branches())) {
          if (Boolean.TRUE.equals(b._1().eval(row))) {
            return b._2();
          }
        }
        return c.elseValue().isDefined() ? c.elseValue().get() : null;
      }
      if (e instanceof NaNvl n) {
        return nanBits(n.left(), row) != null ? n.right() : n.left();
      }
      for (Expression child : CollectionConverters.asJava(e.children())) {
        if (child.eval(row) != null) {
          return child;
        }
      }
      return null;
    } catch (Exception | AssertionError ex) {
      return null;
    }
  }

  private static boolean isFloating(DataType t) {
    return t instanceof DoubleType || t instanceof FloatType;
  }

  /**
   * The raw bits of {@code e}'s floating value on {@code row} where it is a NaN; null where it is
   * not, is null, is not floating or raises. Raising covers Spark's own errors, the internal
   * {@code SparkException} of an expression that cannot be evaluated among them, and the
   * {@code AssertionError} of a {@code RuntimeReplaceable} evaluated over a row.
   */
  private static @Nullable Long nanBits(Expression e, InternalRow row) {
    Object v;
    try {
      v = e.eval(row);
    } catch (Exception | AssertionError ex) {
      return null;
    }
    if (v instanceof Double d && d.isNaN()) {
      return Double.doubleToRawLongBits(d);
    }
    if (v instanceof Float f && f.isNaN()) {
      return (long) Float.floatToRawIntBits(f);
    }
    return null;
  }

  /**
   * Null when {@code want} and {@code got} hold the same value in every column, else what
   * differs. Floating values compare by their raw bits, so a NaN's payload and a zero's sign
   * count; two NaNs in column {@code o} agree whatever their payloads only where {@code open}
   * holds for {@code o} ({@link Level#ROW}, with {@link #openNaN(Expression, InternalRow)}), and
   * a null {@code open} relaxes no column ({@link Level#EXACT}). A decimal compares its unscaled
   * value at the column's type ({@link #unscaledAt}). A null's data is not read.
   */
  public static @Nullable String sameRow(InternalRow want, InternalRow got, DataType[] types,
      @Nullable IntPredicate open) {
    for (int o = 0; o < types.length; o++) {
      boolean wantNull = want.isNullAt(o);
      boolean gotNull = got.isNullAt(o);
      if (wantNull || gotNull) {
        if (wantNull != gotNull) {
          return "output " + o + ": Spark " + (wantNull ? "null" : valueOf(want, o, types[o]))
              + ", Varka " + (gotNull ? "null" : valueOf(got, o, types[o]));
        }
        continue;
      }
      DataType t = types[o];
      boolean same;
      if (t instanceof DoubleType) {
        same = Double.doubleToRawLongBits(want.getDouble(o))
            == Double.doubleToRawLongBits(got.getDouble(o))
            || (Double.isNaN(want.getDouble(o)) && Double.isNaN(got.getDouble(o))
                && open != null && open.test(o));
      } else if (t instanceof FloatType) {
        same = Float.floatToRawIntBits(want.getFloat(o))
            == Float.floatToRawIntBits(got.getFloat(o))
            || (Float.isNaN(want.getFloat(o)) && Float.isNaN(got.getFloat(o))
                && open != null && open.test(o));
      } else if (t instanceof DecimalType d) {
        BigInteger w = unscaledAt(want.getDecimal(o, d.precision(), d.scale()), d);
        same = w != null && w.equals(unscaledAt(got.getDecimal(o, d.precision(), d.scale()), d));
      } else {
        same = Objects.equals(valueOf(want, o, t), valueOf(got, o, t));
      }
      if (!same) {
        return "output " + o + ": Spark " + valueOf(want, o, t) + ", Varka " + valueOf(got, o, t);
      }
    }
    return null;
  }

  /**
   * {@code d}'s unscaled value at {@code type}'s scale, which is what a column of the type
   * stores; null where {@code d} is no value of the type, needing rounding to reach the scale or
   * more digits than the precision. Normalizing first lets a row that carries a {@code Decimal}
   * at another internal scale, as an interpreted expression may, agree with the same value at
   * the type's. On every row a caller hands it this gives {@code Decimal.equals}' verdict: Spark's
   * side comes through an {@code UnsafeProjection} at the type's scale, and a Varka writer's
   * wrong scale is caught by the readers that build the other side, not here.
   */
  static @Nullable BigInteger unscaledAt(Decimal d, DecimalType type) {
    BigDecimal at;
    try {
      at = d.toJavaBigDecimal().setScale(type.scale());
    } catch (ArithmeticException e) {
      return null;
    }
    BigInteger unscaled = at.unscaledValue();
    return unscaled.abs().toString().length() > type.precision() ? null : unscaled;
  }

  /**
   * Column {@code o}'s value by its physical type: a columnar batch's row reads no {@code TIME}
   * through {@code get}, so the temporal types are read as the int or long they are stored as.
   */
  public static Object valueOf(InternalRow row, int o, DataType t) {
    if (t instanceof TimeType || t instanceof DayTimeIntervalType || t instanceof TimestampType
        || t instanceof TimestampNTZType) {
      return row.getLong(o);
    }
    if (t instanceof DateType || t instanceof YearMonthIntervalType) {
      return row.getInt(o);
    }
    return row.get(o, t);
  }
}
