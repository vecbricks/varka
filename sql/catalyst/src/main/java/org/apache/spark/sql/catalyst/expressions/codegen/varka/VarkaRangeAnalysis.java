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

import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.function.IntUnaryOperator;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaValueRange.Range;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DateDiff;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfMonth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeek;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeekIso;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LastDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Least;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MakeDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Month;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NextDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Quarter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ThursdayOf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDateDynamic;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * The compile-time value-range analysis over the IR: one traversal, with the compiler's two
 * questions as queries on it.
 *
 * <p>The compiler asks {@link #range} for a node and reads the {@link Range} it returns to
 * decide two things. For a calendar node - {@code year}, {@code last_day}, {@code add_months}
 * and the rest of the {@link VarkaVectorIR.Chrono} family - whether the epoch day it will
 * decompose can leave {@code [VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS]},
 * the range the civil-from-days lowering is exact in; if it cannot, the node fuses with no
 * per-lane check. For a checked int operation ({@code ANSI} or {@code try_} arithmetic) whether
 * the result can leave int32; if it cannot, the check comes off.
 *
 * <p><b>Kind.</b> The IR says which lane a node is on and nothing else about its type: a
 * {@link ColumnRef} is an ordinal and a lane width, and
 * {@link Greatest}, {@link Least} and {@link IfElse} are the same node whether they hold epoch
 * days or ints. So the query says what it is asking about. Under {@link Kind#DAY} a column is
 * the project's column contract, {@code [CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]}, and a hull node
 * is the hull of its branches; under {@link Kind#INT} a column is unknown and so is a hull. The
 * parent decides the child's kind: both {@link DateDiff} operands and every {@code days()} child
 * are days, both {@link IntArith} operands and {@link IntNeg}'s child are ints, and a hull node
 * passes its own kind down. A node asked for the kind it does not produce answers
 * {@link VarkaValueRange#UNKNOWN}, as does any node on a lane wider than int32, whose values
 * this analysis has neither a contract nor a literal table for.
 *
 * <p><b>Policy.</b> A column-driven day offset ({@code date_add(d, i)} with a column {@code i})
 * is bounded only by the runtime guard a calendar consumer above it arms, which declines a batch
 * whose lane leaves the narrowed range. Under {@link GuardPolicy#ARMED} such a producer answers
 * that range; under {@link GuardPolicy#NONE} - the policy a {@code datediff} asks with, since it
 * is not a calendar node and arms nothing - it answers unknown. A calendar consumer re-arms the
 * policy for its own subtree whatever the caller's policy was, so a producer under a
 * {@code last_day} inside a {@code datediff} is still bounded.
 *
 * <p><b>Under {@code INT} every answer is an interval symmetric about zero</b>, {@code [-m, m]}.
 * That reproduces the magnitude arithmetic the compiler used before this class exactly: a sum
 * bounds as the sum of magnitudes, a product as their product, and the hull nodes stay unknown.
 * Both are looser than interval arithmetic could be and are registered as debt rather than
 * changed here, because the refactor's contract is that every shape is admitted or declined as
 * before.
 *
 * <p>The switch over the sealed IR is exhaustive, so a node type without a transfer function
 * does not compile - which is the protection the two traversals this replaces did not have. The
 * literal table crosses in as a function from slot index to value and is read at call time,
 * never snapshotted: the compiler's table grows as compilation proceeds and is truncated on
 * every decline.
 */
public final class VarkaRangeAnalysis {

  private VarkaRangeAnalysis() {}

  /** What a query asks about: epoch days or int32 values. */
  public enum Kind { DAY, INT }

  /** Whether a calendar consumer above the queried node has armed the producer guards. */
  public enum GuardPolicy { ARMED, NONE }

  /** The column contract: every bare date column lies in it. */
  public static final Range CONTRACT =
      VarkaValueRange.of(VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS);

  /** The range a guarded day producer's own result is held to, and {@link GuardedDay}'s. */
  public static final Range NARROW =
      VarkaValueRange.of(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS);

  /** The dates {@code make_date} publishes: the whole calendar years of the narrow range. */
  public static final Range MAKE_DATE = VarkaValueRange.of(
      LocalDate.of(VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1).toEpochDay(),
      LocalDate.of(VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31).toEpochDay());

  /**
   * The range of {@code node}'s value, asked for {@code kind}, under {@code policy}, reading
   * literal slots through {@code literals}.
   */
  public static Range range(VarkaVectorIR node, Kind kind, GuardPolicy policy,
      IntUnaryOperator literals) {
    // A leaf on a wider lane has neither of the two things this analysis reads: the epoch-day
    // contract is an int32 statement, and `literals` hands back an int, which cannot carry a
    // 64-bit constant. Answering UNKNOWN is the safe direction - it keeps every guard the
    // caller would otherwise elide - and it is what a wider lane gets until the analysis has a
    // contract and a literal table of its own.
    if (node.laneType() != VarkaVectorIR.LaneType.INT) {
      return VarkaValueRange.UNKNOWN;
    }
    return switch (node) {
      case ColumnRef c -> kind == Kind.DAY ? CONTRACT : VarkaValueRange.UNKNOWN;
      case LiteralSlot s -> {
        long v = literals.applyAsInt(s.index());
        yield kind == Kind.DAY ? VarkaValueRange.point(v) : VarkaValueRange.symmetric(Math.abs(v));
      }
      case AddDays n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : dayShift(n.days(), n.offset(), 1, policy, literals);
      case SubDays n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : dayShift(n.days(), n.offset(), -1, policy, literals);
      case NextDay n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : range(n.days(), Kind.DAY, policy, literals).shift(1, 7);
      case ThursdayOf n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : range(n.days(), Kind.DAY, policy, literals).shift(-3, 3);
      case AddMonths n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN : addMonths(n, literals);
      // The calendar consumers re-arm the guards below them: their child is asked under ARMED.
      case LastDay n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : range(n.days(), Kind.DAY, GuardPolicy.ARMED, literals).shift(0, 30);
      // A truncated date is its input or an earlier day of the same period: at most 365 back,
      // the 31st of December of a leap year truncated to its year. The level-column form has
      // the same bound, its week result at most six days back.
      case TruncDate n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : range(n.days(), Kind.DAY, GuardPolicy.ARMED, literals).shift(-365, 0);
      case TruncDateDynamic n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : range(n.days(), Kind.DAY, GuardPolicy.ARMED, literals).shift(-365, 0);
      // make_date publishes only whole years of the narrow range; a year outside declines the
      // batch before any consumer sees it, so its operands are not asked.
      case MakeDate n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN : MAKE_DATE;
      // Whatever the child's interval was, what leaves a GuardedDay is inside the range its
      // check enforces: a lane outside is reported and the batch recomputed on the row engine.
      case GuardedDay n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN : NARROW;
      // A range guard's own bounds are what leaves it, whatever the child was - the same
      // promise the day guard makes, with the bounds the node names. Answered only for the
      // int kinds this lattice tracks; the long lane is UNKNOWN throughout, and its callers
      // prove their bounds structurally (task 102) or through a guard like this one.
      case GuardedRange n -> kind == Kind.INT
          ? VarkaValueRange.of(n.lo(), n.hi()) : VarkaValueRange.UNKNOWN;
      case Greatest n -> hull(n.left(), n.right(), kind, policy, literals);
      case Least n -> hull(n.left(), n.right(), kind, policy, literals);
      case IfElse n -> hull(n.thenNode(), n.elseNode(), kind, policy, literals);
      case DateDiff n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN : dateDiff(n, literals);
      // The calendar fields, over a day the compiler has admitted: the child is not asked. The
      // year bound is the widest year over the whole admitted range, not only the guard's own
      // ceiling (VarkaChrono.YEAR_FIELD_MAGNITUDE says why the two differ).
      case Year n -> field(kind, VarkaChrono.YEAR_FIELD_MAGNITUDE);
      case Month n -> field(kind, 12);
      case DayOfMonth n -> field(kind, 31);
      case Quarter n -> field(kind, 4);
      case DayOfYear n -> field(kind, 366);
      case WeekOfYear n -> field(kind, 53);
      case DayOfWeek n -> field(kind, 7);
      case DayOfWeekIso n -> field(kind, 7);
      case WeekDay n -> field(kind, 6);
      case IntArith n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN : arith(n, policy, literals);
      case IntNeg n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN
          : range(n.child(), Kind.INT, policy, literals).neg();
      // A constant division only ever shrinks a range, so a bounded child stays bounded and an
      // unbounded one is no worse off - which is the point of the node: it needs no bound to be
      // correct, and it hands one on where it had one.
      case ConstDivide n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN
          : range(n.child(), Kind.INT, policy, literals).divideBy(n.divisor());
      // A condition has no value of its own.
      case Cond c -> VarkaValueRange.UNKNOWN;
    };
  }

  /** The magnitude a node's {@code INT} range allows, or empty when unknown. */
  public static OptionalLong magnitude(VarkaVectorIR node, IntUnaryOperator literals) {
    return range(node, Kind.INT, GuardPolicy.NONE, literals).magnitude();
  }

  private static Range field(Kind kind, long magnitude) {
    return kind == Kind.INT ? VarkaValueRange.symmetric(magnitude) : VarkaValueRange.UNKNOWN;
  }

  private static Range hull(VarkaVectorIR a, VarkaVectorIR b, Kind kind, GuardPolicy policy,
      IntUnaryOperator literals) {
    if (kind != Kind.DAY) {
      return VarkaValueRange.UNKNOWN;
    }
    return range(a, Kind.DAY, policy, literals).hull(range(b, Kind.DAY, policy, literals));
  }

  /**
   * A day shifted by an offset: a literal moves the interval by exactly its value; a column
   * offset is bounded only by the runtime guard, which is armed by a calendar consumer above -
   * so under {@code NONE} it is unknown, and under {@code ARMED} it is the narrowed range, on
   * condition that the child is a shape the analysis knows at all. Stating the guard's range
   * rather than a distinct verdict is what lets a further shift above compose: it widens a
   * known interval and the calendar admission tests the widened one.
   */
  private static Range dayShift(VarkaVectorIR days, VarkaVectorIR offset, int sign,
      GuardPolicy policy, IntUnaryOperator literals) {
    if (offset instanceof LiteralSlot slot) {
      long v = sign * (long) literals.applyAsInt(slot.index());
      return range(days, Kind.DAY, policy, literals).shift(v, v);
    }
    if (policy == GuardPolicy.NONE) {
      return VarkaValueRange.UNKNOWN;
    }
    Range child = range(days, Kind.DAY, policy, literals);
    return child instanceof VarkaValueRange.Unknown ? VarkaValueRange.UNKNOWN : NARROW;
  }

  /**
   * {@code add_months}: a literal count moves the day by 28 to 31 days per month, in whichever
   * order the sign puts them; a column count is bounded by the emitter's own runtime guard to
   * {@code [MONTH_ARITH_MIN_MONTHS, MONTH_ARITH_MAX_MONTHS]}, so it moves the day by the same
   * 31-day-month over-approximation at the guard's extremes. Either way the node is a calendar
   * consumer and its child is asked under {@code ARMED}.
   */
  private static Range addMonths(AddMonths n, IntUnaryOperator literals) {
    Range child = range(n.days(), Kind.DAY, GuardPolicy.ARMED, literals);
    if (n.months() instanceof LiteralSlot slot) {
      long m = literals.applyAsInt(slot.index());
      return child.shift(Math.min(28 * m, 31 * m), Math.max(28 * m, 31 * m));
    }
    return child.shift(
        31L * VarkaChrono.MONTH_ARITH_MIN_MONTHS, 31L * VarkaChrono.MONTH_ARITH_MAX_MONTHS);
  }

  /**
   * {@code datediff}: bounded only where both operands' days are, each asked with no guard
   * assumed, because a {@code datediff} is not a calendar node and arms none - a calendar node
   * inside an operand still does. An operand interval that leaves int32 is refused, because a
   * lane that produced it wrapped on the way in.
   */
  private static Range dateDiff(DateDiff n, IntUnaryOperator literals) {
    Range end = range(n.end(), Kind.DAY, GuardPolicy.NONE, literals);
    Range start = range(n.start(), Kind.DAY, GuardPolicy.NONE, literals);
    if (end instanceof VarkaValueRange.Bounded e && start instanceof VarkaValueRange.Bounded s
        && e.fitsInt() && s.fitsInt()) {
      OptionalLong m = e.sub(s).magnitude();
      return m.isPresent() ? VarkaValueRange.symmetric(m.getAsLong()) : VarkaValueRange.UNKNOWN;
    }
    return VarkaValueRange.UNKNOWN;
  }

  /** Int arithmetic over the operands' magnitudes: a product for {@code MUL}, a sum otherwise. */
  private static Range arith(IntArith n, GuardPolicy policy, IntUnaryOperator literals) {
    Range l = range(n.left(), Kind.INT, policy, literals);
    Range r = range(n.right(), Kind.INT, policy, literals);
    return n.op() == IntOp.MUL ? l.mul(r) : l.add(r);
  }
}
