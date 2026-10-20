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
import java.util.IdentityHashMap;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.IntConsumer;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitCount;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoundedDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
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
 * the column bound, {@code [CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]} - checked per batch wherever
 * an answer rests on it, see {@link #CONTRACT} - and a hull node
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
 * <p><b>Under {@code INT} the answers the compiler's trees produce are intervals symmetric about
 * zero</b>, {@code [-m, m]}. That reproduces the magnitude arithmetic the compiler used before
 * this class exactly: a sum bounds as the sum of magnitudes, a product as their product, and the
 * hull nodes stay unknown. Both are looser than interval arithmetic could be and are registered
 * as debt rather than changed here, because the refactor's contract is that every shape is
 * admitted or declined as before. Two arms answer asymmetric intervals - a {@link GuardedRange}'s
 * own bounds and a {@link BoundedDivide}'s {@code [0, q]} - so the arithmetic arms are the
 * lattice's general operations, which {@code range_analysis.smt2} proves over two general
 * intervals (VARKA-281).
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

  /**
   * The column bound: the range a bare date column is read as, 0001-01-01 to 9999-12-31. It is
   * not a fact about the data - Spark stores any int32 day count, and a cached column can hold
   * one - but a check: an answer that rests on it reports the column, the compiler notes the bound
   * against that input, and {@link #guardColumns} wraps the column in a {@link GuardedRange} over
   * it, so the kernel declines a batch holding a day outside it to the row engine, a day stored
   * under a null row included ({@code Analysis.guardsStoredValues}, VARKA-379).
   * Until VARKA-379 it was assumed and never checked, and a calendar field over a cached column
   * holding a far day was served wrong.
   */
  public static final Range CONTRACT =
      VarkaValueRange.of(VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS);

  /** What a caller that does not act on the columns an answer rests on passes for them. */
  public static final IntConsumer NO_COLUMNS = slot -> { };

  /** The range a guarded day producer's own result is held to, and {@link GuardedDay}'s. */
  public static final Range NARROW =
      VarkaValueRange.of(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS);

  /** The dates {@code make_date} publishes: the whole calendar years of the narrow range. */
  public static final Range MAKE_DATE = VarkaValueRange.of(
      LocalDate.of(VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1).toEpochDay(),
      LocalDate.of(VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31).toEpochDay());

  // --- The rules' own constants, named so that their proofs render from them (VARKA-281) ------

  /**
   * How far a calendar rule lets a node's day lie from its child's, as a closed interval of day
   * counts. The transfer functions below apply one with {@link #over}; {@code VarkaProofFiles}
   * renders {@code range_calendar.smt2}'s obligations and {@code VarkaRangeRulesSuite}'s sweep
   * from the same constants, so a bound cannot be proven at one value and applied at another.
   */
  record Shift(long lo, long hi) {
    Shift {
      if (lo > hi) {
        throw VarkaErrors.illegalArgument("an empty shift [" + lo + ", " + hi + "]");
      }
    }

    /** The child's interval moved by this shift. */
    Range over(Range child) {
      return child.shift(lo, hi);
    }
  }

  /** {@code next_day} lands one to seven days on. */
  static final Shift NEXT_DAY_SHIFT = new Shift(1, 7);

  /** The Thursday of a day's Monday-based week is at most three days away either way. */
  static final Shift THURSDAY_SHIFT = new Shift(-3, 3);

  /** {@code last_day}: the last day of the month is the day itself or up to 30 days on. */
  static final Shift LAST_DAY_SHIFT = new Shift(0, 30);

  /**
   * A truncated date is its input or an earlier day of the same period: at most 365 back, the
   * 31st of December of a leap year truncated to its year. The level-column form has the same
   * bound, its week result at most six days back.
   */
  static final Shift TRUNC_SHIFT = new Shift(-365, 0);

  /** The shortest and the longest month: what one month of {@code add_months} moves a day by. */
  static final int MONTH_DAYS_MIN = 28;
  static final int MONTH_DAYS_MAX = 31;

  /**
   * The largest value each calendar field can take, which is the magnitude the {@code INT} query
   * answers for it; {@code year}'s is {@link VarkaChrono#YEAR_FIELD_MAGNITUDE}, derived from the
   * admitted day range.
   */
  static final int MONTH_MAGNITUDE = 12;
  static final int DAY_OF_MONTH_MAGNITUDE = 31;
  static final int QUARTER_MAGNITUDE = 4;
  static final int DAY_OF_YEAR_MAGNITUDE = 366;
  static final int WEEK_OF_YEAR_MAGNITUDE = 53;
  static final int DAY_OF_WEEK_MAGNITUDE = 7;
  static final int WEEK_DAY_MAGNITUDE = 6;

  /**
   * The range of {@code node}'s value, asked for {@code kind}, under {@code policy}, reading
   * literal slots through {@code literals}, and reporting to {@code columns} the kernel input
   * slot of every date column whose {@link #CONTRACT} the answer rests on. A caller that acts on
   * the answer - admits a calendar node, removes an overflow check - must have each reported
   * column checked against {@link #CONTRACT} per batch ({@link #guardColumns}), or the answer is
   * an assumption. A column the answer does not rest on is not reported: one under a
   * {@link GuardedDay}, or under a column-offset day shift, whose own result the producer guard
   * checks. That guard is the one this analysis cannot see the absence of: the reference
   * configuration {@code guardDayProducers=false} removes it and computes such a lane wrongly
   * rather than declining it, by design ({@link VarkaEmitOptions#guardDayProducers}).
   */
  public static Range range(VarkaVectorIR node, Kind kind, GuardPolicy policy,
      IntUnaryOperator literals, IntConsumer columns) {
    // A leaf on a wider lane has neither of the two things this analysis reads: the epoch-day
    // contract is an int32 statement, and `literals` hands back an int, which cannot carry a
    // 64-bit constant. Answering UNKNOWN is the safe direction - it keeps every guard the
    // caller would otherwise elide - and it is what a wider lane gets until the analysis has a
    // contract and a literal table of its own.
    if (node.laneType() != VarkaVectorIR.LaneType.INT) {
      return VarkaValueRange.UNKNOWN;
    }
    return switch (node) {
      // A day column is the column bound, which the answer rests on: the caller that relies on
      // the answer has the kernel check it per batch (`columns` names the column to it).
      case ColumnRef c -> {
        if (kind != Kind.DAY) {
          yield VarkaValueRange.UNKNOWN;
        }
        columns.accept(c.ordinal());
        yield CONTRACT;
      }
      case LiteralSlot s -> {
        long v = literals.applyAsInt(s.index());
        yield kind == Kind.DAY ? VarkaValueRange.point(v) : VarkaValueRange.symmetric(Math.abs(v));
      }
      case AddDays n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : dayShift(n.days(), n.offset(), 1, policy, literals, columns);
      case SubDays n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : dayShift(n.days(), n.offset(), -1, policy, literals, columns);
      case NextDay n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : NEXT_DAY_SHIFT.over(range(n.days(), Kind.DAY, policy, literals, columns));
      case ThursdayOf n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : THURSDAY_SHIFT.over(range(n.days(), Kind.DAY, policy, literals, columns));
      case AddMonths n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : addMonths(n, literals, columns);
      // The calendar consumers re-arm the guards below them: their child is asked under ARMED.
      case LastDay n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : LAST_DAY_SHIFT.over(range(n.days(), Kind.DAY, GuardPolicy.ARMED, literals, columns));
      case TruncDate n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : TRUNC_SHIFT.over(range(n.days(), Kind.DAY, GuardPolicy.ARMED, literals, columns));
      case TruncDateDynamic n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN
          : TRUNC_SHIFT.over(range(n.days(), Kind.DAY, GuardPolicy.ARMED, literals, columns));
      // make_date publishes only whole years of the narrow range; a year outside declines the
      // batch before any consumer sees it, so its operands are not asked.
      case MakeDate n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN : MAKE_DATE;
      // Whatever the child's interval was, what leaves a GuardedDay is inside the range its
      // check enforces: a lane outside is reported and the batch recomputed on the row engine.
      case GuardedDay n -> kind != Kind.DAY ? VarkaValueRange.UNKNOWN : NARROW;
      // A range guard's own bounds are what leaves it, whatever the child was - the same
      // promise the day guard makes, with the bounds the node names - asked as days (a date
      // column guarded by the column bound, VARKA-379) or as ints. The long lane is UNKNOWN
      // throughout, above, and its callers prove their bounds structurally (VARKA-102) or
      // through a guard like this one; the popcount's bound below is the int lane's alone.
      case GuardedRange n -> VarkaValueRange.of(n.lo(), n.hi());
      // A sign extension keeps every value, so an int's range is its widening's, asked as an
      // int: what a long-lane consumer such as a division by a constant needs to know of its
      // dividend.
      case WidenLane n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN
          : range(n.child(), Kind.INT, policy, literals, columns);
      // A 32-bit value computed in the long lane, whose ranges this lattice does not track.
      case NarrowLane n -> VarkaValueRange.UNKNOWN;
      case Greatest n -> hull(n.left(), n.right(), kind, policy, literals, columns);
      case Least n -> hull(n.left(), n.right(), kind, policy, literals, columns);
      case IfElse n -> hull(n.thenNode(), n.elseNode(), kind, policy, literals, columns);
      case DateDiff n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN
          : dateDiff(n, literals, columns);
      // The calendar fields, over a day the compiler has admitted: the child is not asked. The
      // year bound is the widest year over the whole admitted range, not only the guard's own
      // ceiling (VarkaChrono.YEAR_FIELD_MAGNITUDE says why the two differ).
      case Year n -> field(kind, VarkaChrono.YEAR_FIELD_MAGNITUDE);
      case Month n -> field(kind, MONTH_MAGNITUDE);
      case DayOfMonth n -> field(kind, DAY_OF_MONTH_MAGNITUDE);
      case Quarter n -> field(kind, QUARTER_MAGNITUDE);
      case DayOfYear n -> field(kind, DAY_OF_YEAR_MAGNITUDE);
      case WeekOfYear n -> field(kind, WEEK_OF_YEAR_MAGNITUDE);
      case DayOfWeek n -> field(kind, DAY_OF_WEEK_MAGNITUDE);
      case DayOfWeekIso n -> field(kind, DAY_OF_WEEK_MAGNITUDE);
      case WeekDay n -> field(kind, WEEK_DAY_MAGNITUDE);
      case IntArith n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN
          : arith(n, policy, literals, columns);
      case IntNeg n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN
          : range(n.child(), Kind.INT, policy, literals, columns).neg();
      // A constant division only ever shrinks a range, so a bounded child stays bounded and an
      // unbounded one is no worse off - which is the point of the node: it needs no bound to be
      // correct, and it hands one on where it had one.
      case ConstDivide n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN
          : range(n.child(), Kind.INT, policy, literals, columns).divideBy(n.divisor());
      // The bound is the node's contract, so the quotient's range follows from it alone.
      case BoundedDivide n -> kind != Kind.INT ? VarkaValueRange.UNKNOWN
          : VarkaValueRange.of(0, (n.bound() - 1) / n.divisor());
      // The lattice tracks no bitwise rule: a mask or a shift moves a value anywhere in the
      // lane, so a node over one keeps whatever overflow check it has.
      case BitOp n -> VarkaValueRange.UNKNOWN;
      // A count of the 64 bits of the widened value, whatever the operand; answered at the int
      // lane only, as the long lane's nodes are unknown throughout.
      case BitCount n -> kind != Kind.INT || n.laneType() != VarkaVectorIR.LaneType.INT
          ? VarkaValueRange.UNKNOWN : VarkaValueRange.of(0, Long.SIZE);
      // A condition has no value of its own.
      case Cond c -> VarkaValueRange.UNKNOWN;
    };
  }

  /**
   * {@link #range} for a caller that does not act on the answer, or acts on it with no date
   * column in reach: the columns the answer rests on are not reported.
   */
  public static Range range(VarkaVectorIR node, Kind kind, GuardPolicy policy,
      IntUnaryOperator literals) {
    return range(node, kind, policy, literals, NO_COLUMNS);
  }

  /**
   * The magnitude a node's {@code INT} range allows, or empty when unknown, reporting to
   * {@code columns} every date column the answer rests on, as {@link #range} does.
   */
  public static OptionalLong magnitude(VarkaVectorIR node, IntUnaryOperator literals,
      IntConsumer columns) {
    return range(node, Kind.INT, GuardPolicy.NONE, literals, columns).magnitude();
  }

  /** {@link #magnitude(VarkaVectorIR, IntUnaryOperator, IntConsumer)}, reporting nothing. */
  public static OptionalLong magnitude(VarkaVectorIR node, IntUnaryOperator literals) {
    return magnitude(node, literals, NO_COLUMNS);
  }

  /**
   * {@code node} with every use of a date column in {@code slots} read through a
   * {@link GuardedRange} over {@link #CONTRACT}: the in-kernel check of the column bound, for the
   * columns an entry's admission rested on (VARKA-379). The guard reads the vector the kernel
   * loads anyway, in the loop and the tail of both bodies - a min and a max in the loop, compared
   * once after it - and its status declines the batch, so the bound costs no pass of its own over
   * the column and no read of its validity.
   *
   * <p>Two uses are left bare. An {@link IsNotNull}'s column, whose validity is all it reads and
   * which must stay a column ({@code Analysis}); over a condition it is guarded as any condition
   * is, since whether a conjunction or a disjunction is known depends on its operands' values.
   * And a column in a restricted operand position ({@link VarkaOperandPosition}), none of which
   * admits a guard; a date column cannot sit in one - each takes an int count or a derived
   * column - but if one did, its slot is added to {@code unguarded}, and the caller keeps the
   * evaluator's per-batch check for it. A tree the compiler shares between parents stays shared:
   * each distinct node is rebuilt once.
   */
  public static VarkaVectorIR guardColumns(
      VarkaVectorIR node, Set<Integer> slots, Set<Integer> unguarded) {
    return slots.isEmpty() ? node : guardColumns(node, slots, unguarded, new IdentityHashMap<>());
  }

  // Identity on purpose: a child is rebuilt only where it changed, and a node is reused as the
  // very instance it was, so a subtree the compiler shares stays one instance.
  @SuppressWarnings("ReferenceEquality")
  private static VarkaVectorIR guardColumns(VarkaVectorIR node, Set<Integer> slots,
      Set<Integer> unguarded, IdentityHashMap<VarkaVectorIR, VarkaVectorIR> done) {
    VarkaVectorIR seen = done.get(node);
    if (seen != null) {
      return seen;
    }
    VarkaVectorIR rebuilt;
    if (node instanceof ColumnRef c) {
      // The analysis reports only int-lane columns, the column bound being an int32 statement;
      // a wider one would be left to the evaluator's check rather than guarded at a lane the
      // bound does not describe.
      if (!slots.contains(c.ordinal())) {
        rebuilt = c;
      } else if (c.lane() == VarkaVectorIR.LaneType.INT) {
        rebuilt = new GuardedRange(
            c, VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS);
      } else {
        unguarded.add(c.ordinal());
        rebuilt = c;
      }
    } else if (node instanceof IsNotNull n && !(n.child() instanceof Cond)) {
      rebuilt = node;
    } else {
      VarkaVectorIR[] children = VarkaVectorIR.childrenOf(node);
      boolean changed = false;
      for (int i = 0; i < children.length; i++) {
        VarkaVectorIR child = children[i];
        VarkaVectorIR guarded = child instanceof ColumnRef c && restricted(node, child)
            ? bare(c, slots, unguarded)
            : guardColumns(child, slots, unguarded, done);
        changed |= guarded != child;
        children[i] = guarded;
      }
      rebuilt = changed ? VarkaVectorIR.withChildren(node, children) : node;
    }
    done.put(node, rebuilt);
    return rebuilt;
  }

  /**
   * Whether {@code child} is one of {@code node}'s operands in a restricted position: the very
   * instance {@code restrictedOperands} names, since an equal column elsewhere in the node is not
   * in that position.
   */
  // Identity on purpose: the operand instance in the position, not an equal node.
  @SuppressWarnings("ReferenceEquality")
  private static boolean restricted(VarkaVectorIR node, VarkaVectorIR child) {
    for (VarkaOperandPosition.Restricted r : VarkaOperandPosition.restrictedOperands(node)) {
      if (r.operand() == child) {
        return true;
      }
    }
    return false;
  }

  /**
   * A column in a restricted position, which stays bare: its slot is noted if it is one the
   * caller asked to guard. Any other operand there was taken by its own kind, and the columns
   * under it are guarded as anywhere else.
   */
  private static ColumnRef bare(ColumnRef c, Set<Integer> slots, Set<Integer> unguarded) {
    if (slots.contains(c.ordinal())) {
      unguarded.add(c.ordinal());
    }
    return c;
  }

  private static Range field(Kind kind, long magnitude) {
    return kind == Kind.INT ? VarkaValueRange.symmetric(magnitude) : VarkaValueRange.UNKNOWN;
  }

  private static Range hull(VarkaVectorIR a, VarkaVectorIR b, Kind kind, GuardPolicy policy,
      IntUnaryOperator literals, IntConsumer columns) {
    if (kind != Kind.DAY) {
      return VarkaValueRange.UNKNOWN;
    }
    return range(a, Kind.DAY, policy, literals, columns)
        .hull(range(b, Kind.DAY, policy, literals, columns));
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
      GuardPolicy policy, IntUnaryOperator literals, IntConsumer columns) {
    if (offset instanceof LiteralSlot slot) {
      long v = sign * (long) literals.applyAsInt(slot.index());
      return range(days, Kind.DAY, policy, literals, columns).shift(v, v);
    }
    if (policy == GuardPolicy.NONE) {
      return VarkaValueRange.UNKNOWN;
    }
    // The guard checks the producer's own result, so the answer does not rest on the child's
    // interval beyond its being known: a column below needs no bound of its own here.
    Range child = range(days, Kind.DAY, policy, literals, NO_COLUMNS);
    return child instanceof VarkaValueRange.Unknown ? VarkaValueRange.UNKNOWN : NARROW;
  }

  /**
   * {@code add_months}: a literal count moves the day by {@link #MONTH_DAYS_MIN} to
   * {@link #MONTH_DAYS_MAX} days per month, in whichever order the sign puts them; a column count
   * is bounded by the emitter's own runtime guard to
   * {@code [MONTH_ARITH_MIN_MONTHS, MONTH_ARITH_MAX_MONTHS]}, so it moves the day by the same
   * longest-month over-approximation at the guard's extremes. Either way the node is a calendar
   * consumer and its child is asked under {@code ARMED}.
   */
  private static Range addMonths(AddMonths n, IntUnaryOperator literals, IntConsumer columns) {
    Range child = range(n.days(), Kind.DAY, GuardPolicy.ARMED, literals, columns);
    if (n.months() instanceof LiteralSlot slot) {
      long m = literals.applyAsInt(slot.index());
      return child.shift(
          Math.min(MONTH_DAYS_MIN * m, MONTH_DAYS_MAX * m),
          Math.max(MONTH_DAYS_MIN * m, MONTH_DAYS_MAX * m));
    }
    return child.shift(
        (long) MONTH_DAYS_MAX * VarkaChrono.MONTH_ARITH_MIN_MONTHS,
        (long) MONTH_DAYS_MAX * VarkaChrono.MONTH_ARITH_MAX_MONTHS);
  }

  /**
   * {@code datediff}: bounded only where both operands' days are, each asked with no guard
   * assumed, because a {@code datediff} is not a calendar node and arms none - a calendar node
   * inside an operand still does. An operand interval that leaves int32 is refused, because a
   * lane that produced it wrapped on the way in.
   */
  private static Range dateDiff(DateDiff n, IntUnaryOperator literals, IntConsumer columns) {
    Range end = range(n.end(), Kind.DAY, GuardPolicy.NONE, literals, columns);
    Range start = range(n.start(), Kind.DAY, GuardPolicy.NONE, literals, columns);
    if (end instanceof VarkaValueRange.Bounded e && start instanceof VarkaValueRange.Bounded s
        && e.fitsInt() && s.fitsInt()) {
      OptionalLong m = e.sub(s).magnitude();
      return m.isPresent() ? VarkaValueRange.symmetric(m.getAsLong()) : VarkaValueRange.UNKNOWN;
    }
    return VarkaValueRange.UNKNOWN;
  }

  /**
   * Int arithmetic over the operands' intervals: the lattice's product, sum or difference. Over
   * the symmetric intervals the compiler's int trees produce, the difference is the sum; over
   * asymmetric operands - a {@link BoundedDivide}'s {@code [0, q]}, an int-lane
   * {@link GuardedRange}'s bounds - the sum misses the negative differences, so {@code SUB}
   * takes the true difference (VARKA-281).
   */
  private static Range arith(IntArith n, GuardPolicy policy, IntUnaryOperator literals,
      IntConsumer columns) {
    Range l = range(n.left(), Kind.INT, policy, literals, columns);
    Range r = range(n.right(), Kind.INT, policy, literals, columns);
    return switch (n.op()) {
      case ADD -> l.add(r);
      case SUB -> l.sub(r);
      case MUL -> l.mul(r);
    };
  }
}
