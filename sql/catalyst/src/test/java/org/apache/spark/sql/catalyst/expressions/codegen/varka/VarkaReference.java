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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;
import org.apache.spark.sql.catalyst.util.DateTimeUtils;
import org.apache.spark.sql.catalyst.util.DateTimeUtils$;

/**
 * The reference evaluator (VARKA-11): an independent implementation of the IR's semantics -
 * three-valued conditions, blend, null-skipping greatest/least, floorMod - that the emitted loops
 * are checked against row for row. Every calendar oracle is the definition ({@code java.time},
 * {@code DateTimeUtils}) rather than {@code VarkaChrono}, the model the lowerings were derived
 * from, so the emitted bytes are held to the definition and not to themselves.
 * {@code VarkaSparkOracle} in turn holds this class to Spark's own interpreted {@code eval}.
 *
 * <p>A row is a column's values and its null flags: {@code row[c]} is column {@code c}'s value
 * where {@code isNull[c]} is false, and is not read where it is true. An empty answer is a null.
 *
 * <p>Each entry point is one exhaustive {@code switch} over the sealed IR with no {@code default},
 * so a node added to the IR does not compile until it is placed here: answered, or refused with
 * the reason it has no meaning on that lane (VARKA-310). Operands are evaluated left to right and
 * a null operand ends the evaluation, so an operand that would throw is never reached past a null
 * before it.
 */
public final class VarkaReference {

  private VarkaReference() {
  }

  /** Kleene's three truth values; only {@link #TRUE} selects a THEN branch. */
  public enum Truth {
    TRUE, FALSE, UNKNOWN;

    public boolean isTrue() {
      return this == TRUE;
    }

    static Truth of(boolean b) {
      return b ? TRUE : FALSE;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The int lane.
  // ---------------------------------------------------------------------------------------------

  /** The value of an int-lane {@code node} on a row, empty for a null. */
  public static OptionalInt value(VarkaVectorIR node, int[] row, boolean[] isNull, int[] lits) {
    return switch (node) {
      case ColumnRef c -> isNull[c.ordinal()] ? OptionalInt.empty()
          : OptionalInt.of(row[c.ordinal()]);
      case LiteralSlot l -> OptionalInt.of(lits[l.index()]);
      case AddDays(var days, var offset) -> both(days, offset, row, isNull, lits, (d, o) -> d + o);
      case SubDays(var days, var offset) -> both(days, offset, row, isNull, lits, (d, o) -> d - o);
      // VARKA-93's range check is a status report, not a value change: the reference has no
      // notion of a declined batch, and a lane the guard would report is one the kernel does not
      // answer at all, so the differential never compares against it. The value passes through.
      case GuardedDay g -> value(g.days(), row, isNull, lits);
      // A range guard changes no value; a lane outside it declines the batch, which the suites
      // assert on the status rather than on a value this evaluator could spell.
      case GuardedRange g -> value(g.child(), row, isNull, lits);
      // A conversion reads rows of the other lane: a narrowing root alone is a long-lane shape,
      // answered by `longValue`, and any tree that holds a conversion by `mixedValue`.
      case NarrowLane n -> throw new IllegalArgumentException(
          "a narrowing is evaluated through longValue or mixedValue: " + n);
      case WidenLane n -> throw new IllegalArgumentException(
          "a widening is evaluated through mixedValue: " + n);
      case DateDiff(var end, var start) -> both(end, start, row, isNull, lits, (e, s) -> e - s);
      case DayOfWeek n -> map(n.days(), row, isNull, lits, v -> (Math.floorMod(v, 7) + 4) % 7 + 1);
      case WeekDay n -> map(n.days(), row, isNull, lits, v -> (Math.floorMod(v, 7) + 3) % 7);
      // The definition: Spark's own weekday plus one, not the emitter's offset arithmetic.
      case DayOfWeekIso n -> map(n.days(), row, isNull, lits, v -> DateTimeUtils.getWeekDay(v) + 1);
      // The oracle is Spark's own getNextDateForDayOfWeek, quoted directly, not the lowering:
      // Java's int arithmetic wraps exactly as the lanes do, so this is exact even at
      // Integer.MIN_VALUE, and it is byte-for-byte what the row engine evaluates.
      case NextDay(var days, var offset) ->
          both(days, offset, row, isNull, lits, (d, k) -> d + 1 + Math.floorMod(k - d, 7));
      // The Thursday of the day's ISO (Monday-based) week, by java.time's own adjuster - not
      // d + 3 - weekday0, which is what the emitter computes.
      case ThursdayOf n -> map(n.days(), row, isNull, lits, v -> (int) LocalDate.ofEpochDay(v)
          .with(java.time.DayOfWeek.THURSDAY).toEpochDay());
      case Chrono n -> chrono(n, row, isNull, lits);
      // The oracle is DateTimeUtils.dateAddMonths - the definition AddMonthsBase's nullSafeEval
      // calls - not VarkaChrono.daysFromCivil, which is the model this node's own arithmetic was
      // derived from and checked against; using it here would test the lowering against itself.
      case AddMonths(var days, var months) ->
          both(days, months, row, isNull, lits, DateTimeUtils::dateAddMonths);
      case MakeDate m -> makeDate(m, row, isNull, lits);
      case IntArith n -> intArith(n, row, isNull, lits);
      case IntNeg n -> {
        var c = value(n.child(), row, isNull, lits);
        if (c.isEmpty()) {
          yield c;
        }
        int v = c.getAsInt();
        yield switch (n.mode()) {
          case WRAP -> OptionalInt.of(-v);
          case FAIL, NULL -> v == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(-v);
        };
      }
      // Java's own `/`, which is the definition the node claims to compute: the oracle divides
      // rather than reproducing the emitter's conversion, so the two agree only if the lowering
      // is right. The divisor is declared at the wider lane and narrowed here, which the node's
      // own constructor makes safe: an int-lane division whose divisor does not fit in an int is
      // refused where it is built.
      case ConstDivide n -> map(n.child(), row, isNull, lits, v -> v / (int) n.divisor());
      // The definition, a true division: the kernel's single multiply must agree with it over
      // the bound, and a dividend outside the bound is one the suites never hand the kernel.
      case BoundedDivide n -> map(n.child(), row, isNull, lits, v -> v / n.divisor());
      // Java's own operators, which are what Spark's bitwise and shift expressions evaluate:
      // the shifts mask their count to five bits here, as `x << n` does.
      case BitOp n -> both(n.left(), n.right(), row, isNull, lits, (a, b) -> switch (n.op()) {
        case AND -> a & b;
        case OR -> a | b;
        case XOR -> a ^ b;
        case SHL -> a << b;
        case SHR -> a >> b;
        case USHR -> a >>> b;
      });
      // BitwiseCount's definition: the popcount of the value widened to a long.
      case BitCount n -> map(n.child(), row, isNull, lits, v -> Long.bitCount((long) v));
      case Greatest(var left, var right) ->
          pick(value(left, row, isNull, lits), value(right, row, isNull, lits), Math::max);
      case Least(var left, var right) ->
          pick(value(left, row, isNull, lits), value(right, row, isNull, lits), Math::min);
      case IfElse(var c, var thenNode, var elseNode) -> cond(c, row, isNull, lits).isTrue()
          ? value(thenNode, row, isNull, lits) : value(elseNode, row, isNull, lits);
      case Cond c -> throw new IllegalArgumentException("condition " + c + " evaluated as a value");
    };
  }

  /**
   * The calendar nodes. The oracle is java.time, which is what DateTimeUtils.getYear and its
   * siblings call - not VarkaChrono, so the emitted bytes are checked against the definition
   * rather than against the model they were derived from.
   */
  private static OptionalInt chrono(Chrono node, int[] row, boolean[] isNull, int[] lits) {
    return switch (node) {
      case Year n -> map(n.days(), row, isNull, lits, v -> LocalDate.ofEpochDay(v).getYear());
      case Month n ->
          map(n.days(), row, isNull, lits, v -> LocalDate.ofEpochDay(v).getMonthValue());
      case DayOfMonth n ->
          map(n.days(), row, isNull, lits, v -> LocalDate.ofEpochDay(v).getDayOfMonth());
      // IsoFields.QUARTER_OF_YEAR, which is what DateTimeUtils.getQuarter calls - not
      // (month + 2) / 3, which is what the emitter computes. An oracle that restates the
      // implementation is not an oracle.
      case Quarter n -> map(n.days(), row, isNull, lits,
          v -> LocalDate.ofEpochDay(v).get(IsoFields.QUARTER_OF_YEAR));
      case DayOfYear n ->
          map(n.days(), row, isNull, lits, v -> LocalDate.ofEpochDay(v).getDayOfYear());
      // The definition, not the linear-form-plus-leap-flag the lowering computes.
      case LastDay n -> map(n.days(), row, isNull, lits, DateTimeUtils::getLastDayOfMonth);
      // DateTimeUtils.truncDate, which is what Spark's TruncDate evaluates - through LocalDate,
      // not through either of the emitter's two forms.
      case TruncDate n -> {
        int level = switch (n.level()) {
          case YEAR -> DateTimeUtils$.MODULE$.TRUNC_TO_YEAR();
          case MONTH -> DateTimeUtils$.MODULE$.TRUNC_TO_MONTH();
          case QUARTER -> DateTimeUtils$.MODULE$.TRUNC_TO_QUARTER();
        };
        yield map(n.days(), row, isNull, lits, v -> DateTimeUtils.truncDate(v, level));
      }
      // The same definition over the level lane (VARKA-61). The leaf hands the kernel no code
      // outside the four date levels - anything else is a null lane - so a live lane with one is
      // not a shape the kernel is ever asked, and truncDate's throw on it is the right answer.
      case TruncDateDynamic(var days, var level) ->
          both(days, level, row, isNull, lits, DateTimeUtils::truncDate);
      // The definition (what DateTimeUtils.getWeekOfYear calls), over whatever the child is; it
      // agrees with the emitter's (doy - 1) / 7 + 1 exactly because the child is a Thursday,
      // which the analysis enforces.
      case WeekOfYear n -> map(n.days(), row, isNull, lits,
          v -> LocalDate.ofEpochDay(v).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    };
  }

  /**
   * The definition: LocalDate.of, null where the calendar rejects the triple - never the length
   * rule the emitter computes. The year limit is the kernel's business, not the oracle's: a year
   * outside it is a declined batch, which the status assertion catches.
   */
  private static OptionalInt makeDate(MakeDate m, int[] row, boolean[] isNull, int[] lits) {
    var y = value(m.year(), row, isNull, lits);
    if (y.isEmpty()) {
      return y;
    }
    var mo = value(m.month(), row, isNull, lits);
    if (mo.isEmpty()) {
      return mo;
    }
    var d = value(m.day(), row, isNull, lits);
    if (d.isEmpty()) {
      return d;
    }
    try {
      return OptionalInt.of(
          (int) LocalDate.of(y.getAsInt(), mo.getAsInt(), d.getAsInt()).toEpochDay());
    } catch (DateTimeException e) {
      return OptionalInt.empty();
    }
  }

  /**
   * VARKA-63. The oracle is Java's own arithmetic, not the emitter's sign test: Math.addExact and
   * friends define overflow, and catching their throw is what says a lane overflowed. WRAP is the
   * wrapping operator, which is what Spark's LEGACY mode and the JVM both do. FAIL declines the
   * batch rather than returning a value, so a row that overflows has no expected value at all; the
   * suite asserts the status, and reaching this arm with an overflowing row means the kernel did
   * not decline when it had to.
   */
  private static OptionalInt intArith(IntArith n, int[] row, boolean[] isNull, int[] lits) {
    var l = value(n.left(), row, isNull, lits);
    if (l.isEmpty()) {
      return l;
    }
    var r = value(n.right(), row, isNull, lits);
    if (r.isEmpty()) {
      return r;
    }
    int a = l.getAsInt();
    int b = r.getAsInt();
    if (n.mode() == Overflow.WRAP) {
      return OptionalInt.of(switch (n.op()) {
        case ADD -> a + b;
        case SUB -> a - b;
        case MUL -> a * b;
      });
    }
    try {
      return OptionalInt.of(switch (n.op()) {
        case ADD -> Math.addExact(a, b);
        case SUB -> Math.subtractExact(a, b);
        case MUL -> Math.multiplyExact(a, b);
      });
    } catch (ArithmeticException e) {
      return OptionalInt.empty();
    }
  }

  /** Kleene three-valued logic over the int lane. */
  public static Truth cond(Cond cond, int[] row, boolean[] isNull, int[] lits) {
    return switch (cond) {
      case Compare(var op, var left, var right) -> {
        var l = value(left, row, isNull, lits);
        if (l.isEmpty()) {
          yield Truth.UNKNOWN;
        }
        var r = value(right, row, isNull, lits);
        if (r.isEmpty()) {
          yield Truth.UNKNOWN;
        }
        yield Truth.of(compare(op, l.getAsInt(), r.getAsInt()));
      }
      case And(var left, var right) ->
          and(cond(left, row, isNull, lits), cond(right, row, isNull, lits));
      case Or(var left, var right) ->
          or(cond(left, row, isNull, lits), cond(right, row, isNull, lits));
      case Not n -> not(cond(n.child(), row, isNull, lits));
      // Written from the node's meaning, not from its emission: some range contains the value.
      case InRanges(var child, var bounds) -> {
        var v = value(child, row, isNull, lits);
        yield v.isEmpty() ? Truth.UNKNOWN : Truth.of(inRanges(v.getAsInt(), bounds));
      }
      // The first total condition (VARKA-20): IS NOT NULL never returns unknown - a null operand
      // is a definite false, not a missing answer.
      case IsNotNull n -> n.child() instanceof Cond c
          ? Truth.of(cond(c, row, isNull, lits) != Truth.UNKNOWN)
          : Truth.of(value(n.child(), row, isNull, lits).isPresent());
    };
  }

  // ---------------------------------------------------------------------------------------------
  // The long lane.
  // ---------------------------------------------------------------------------------------------

  /**
   * The reference at the long lane, for the subset VARKA-85 ships there: the two leaves, the
   * arithmetic and its three overflow modes, the negate, the hull ops and the conditional.
   *
   * <p>A second method rather than a widening of {@link #value} because the two answer different
   * questions: a {@code DateDiff} or a {@code Year} has no meaning over 64-bit lanes and must not
   * silently produce one, so every calendar node is refused here. Java's long arithmetic wraps
   * exactly as the lanes do, which makes this exact at {@code Long.MIN_VALUE}.
   */
  public static OptionalLong longValue(VarkaVectorIR node, long[] row, boolean[] isNull,
      long[] lits) {
    return switch (node) {
      case ColumnRef c -> isNull[c.ordinal()] ? OptionalLong.empty()
          : OptionalLong.of(row[c.ordinal()]);
      case LiteralSlot l -> OptionalLong.of(lits[l.index()]);
      case IntArith n -> longArith(n, row, isNull, lits);
      case IntNeg n -> {
        var c = longValue(n.child(), row, isNull, lits);
        if (c.isEmpty()) {
          yield c;
        }
        long v = c.getAsLong();
        yield switch (n.mode()) {
          case WRAP -> OptionalLong.of(-v);
          // The lane's own most negative value, whose negation is itself.
          case FAIL, NULL -> v == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(-v);
        };
      }
      case ConstDivide n -> {
        var c = longValue(n.child(), row, isNull, lits);
        yield c.isEmpty() ? c : OptionalLong.of(c.getAsLong() / n.divisor());
      }
      case GuardedRange g -> longValue(g.child(), row, isNull, lits);
      // The narrowing is the kernel's store, not a value change: the reference answers the child
      // in full and the suite narrows it the way the store does, so a value that does not fit an
      // int shows as a difference rather than being truncated on both sides.
      case NarrowLane n -> longValue(n.child(), row, isNull, lits);
      case WidenLane n -> throw new IllegalArgumentException(
          "a widening reads int rows, and is evaluated through mixedValue: " + n);
      case Greatest(var left, var right) ->
          pick(longValue(left, row, isNull, lits), longValue(right, row, isNull, lits), Math::max);
      case Least(var left, var right) ->
          pick(longValue(left, row, isNull, lits), longValue(right, row, isNull, lits), Math::min);
      // Unknown takes the else branch, as at the int lane and as CASE WHEN does.
      case IfElse(var c, var thenNode, var elseNode) -> longCond(c, row, isNull, lits).isTrue()
          ? longValue(thenNode, row, isNull, lits) : longValue(elseNode, row, isNull, lits);
      case BoundedDivide n -> throw new IllegalArgumentException(
          "a bounded division is an int-lane node: " + n);
      // Java's long operators: the shifts mask their count to six bits.
      case BitOp n -> {
        var l = longValue(n.left(), row, isNull, lits);
        if (l.isEmpty()) {
          yield l;
        }
        var r = longValue(n.right(), row, isNull, lits);
        if (r.isEmpty()) {
          yield r;
        }
        long a = l.getAsLong();
        long b = r.getAsLong();
        yield OptionalLong.of(switch (n.op()) {
          case AND -> a & b;
          case OR -> a | b;
          case XOR -> a ^ b;
          case SHL -> a << b;
          case SHR -> a >> b;
          case USHR -> a >>> b;
        });
      }
      case BitCount n -> {
        var c = longValue(n.child(), row, isNull, lits);
        yield c.isEmpty() ? c : OptionalLong.of(Long.bitCount(c.getAsLong()));
      }
      case AddDays n -> noLongReference(n);
      case SubDays n -> noLongReference(n);
      case DateDiff n -> noLongReference(n);
      case DayOfWeek n -> noLongReference(n);
      case WeekDay n -> noLongReference(n);
      case DayOfWeekIso n -> noLongReference(n);
      case NextDay n -> noLongReference(n);
      case ThursdayOf n -> noLongReference(n);
      case Chrono n -> noLongReference(n);
      case AddMonths n -> noLongReference(n);
      case MakeDate n -> noLongReference(n);
      case GuardedDay n -> noLongReference(n);
      case Cond c -> noLongReference(c);
    };
  }

  private static OptionalLong noLongReference(VarkaVectorIR node) {
    throw new IllegalArgumentException("no long-lane reference for "
        + node.getClass().getSimpleName() + "; VARKA-85 ships the lane-generic subset only");
  }

  /** As {@link #intArith} at the long lane, which has no checked multiply. */
  private static OptionalLong longArith(IntArith n, long[] row, boolean[] isNull, long[] lits) {
    var l = longValue(n.left(), row, isNull, lits);
    if (l.isEmpty()) {
      return l;
    }
    var r = longValue(n.right(), row, isNull, lits);
    if (r.isEmpty()) {
      return r;
    }
    long a = l.getAsLong();
    long b = r.getAsLong();
    if (n.mode() == Overflow.WRAP) {
      return OptionalLong.of(switch (n.op()) {
        case ADD -> a + b;
        case SUB -> a - b;
        case MUL -> a * b;
      });
    }
    try {
      return OptionalLong.of(switch (n.op()) {
        case ADD -> Math.addExact(a, b);
        case SUB -> Math.subtractExact(a, b);
        // No checked multiply exists at either lane - the int one has no 64-bit product to test
        // with and the long one would need 128 bits, which is VARKA-104's.
        case MUL -> throw new IllegalArgumentException(
            "a checked multiply has no long-lane overflow test: " + n);
      });
    } catch (ArithmeticException e) {
      return OptionalLong.empty();
    }
  }

  /** {@link #cond}'s counterpart over long lanes, for the conditions the same subset ships. */
  public static Truth longCond(Cond cond, long[] row, boolean[] isNull, long[] lits) {
    return switch (cond) {
      case Compare(var op, var left, var right) -> {
        var l = longValue(left, row, isNull, lits);
        if (l.isEmpty()) {
          yield Truth.UNKNOWN;
        }
        var r = longValue(right, row, isNull, lits);
        if (r.isEmpty()) {
          yield Truth.UNKNOWN;
        }
        yield Truth.of(compare(op, l.getAsLong(), r.getAsLong()));
      }
      case And(var left, var right) ->
          and(longCond(left, row, isNull, lits), longCond(right, row, isNull, lits));
      case Or(var left, var right) ->
          or(longCond(left, row, isNull, lits), longCond(right, row, isNull, lits));
      case Not n -> not(longCond(n.child(), row, isNull, lits));
      case IsNotNull n -> n.child() instanceof Cond c
          ? Truth.of(longCond(c, row, isNull, lits) != Truth.UNKNOWN)
          : Truth.of(longValue(n.child(), row, isNull, lits).isPresent());
      case InRanges n -> throw new IllegalArgumentException(
          "a range set is on the int lane only: " + n);
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Both lanes (VARKA-28).
  // ---------------------------------------------------------------------------------------------

  /**
   * The value of {@code node} over a row of both lanes, sign-extended where the node is on the int
   * lane; empty for a null. {@code row[c]} is column {@code c}'s value, an int column's widened,
   * and {@code intLits} and {@code longLits} are the two literal tables, each lane's slots
   * indexing its own.
   *
   * <p>Written from the conversions' meaning, on top of the two single-lane evaluators rather
   * than beside them: each conversion is answered first - a widening is its int child's value,
   * a narrowing its long child's low 32 bits, Spark's {@code CAST(bigint AS int)} under
   * {@code LEGACY} - and stands in the tree as a column of its own lane holding that answer, so
   * that every other node is answered by {@link #value} or {@link #longValue} exactly as in a
   * single-lane tree. A narrowing that the ANSI cast guards is a declined batch wherever the value
   * does not fit, which the suites assert on the status, as for every guard.
   */
  public static OptionalLong mixedValue(VarkaVectorIR node, long[] row, boolean[] isNull,
      int[] intLits, long[] longLits) {
    var rows = new MixedRow(row, isNull);
    VarkaVectorIR flat = rows.flatten(node, intLits, longLits);
    if (flat.laneType() == LaneType.INT) {
      OptionalInt v = value(flat, rows.ints(), rows.nulls(), intLits);
      return v.isPresent() ? OptionalLong.of(v.getAsInt()) : OptionalLong.empty();
    }
    return longValue(flat, rows.longs(), rows.nulls(), longLits);
  }

  /** {@link #mixedValue}'s counterpart for a condition. */
  public static Truth mixedCond(Cond cond, long[] row, boolean[] isNull, int[] intLits,
      long[] longLits) {
    var rows = new MixedRow(row, isNull);
    Cond flat = (Cond) rows.flatten(cond, intLits, longLits);
    return flat.laneType() == LaneType.INT
        ? cond(flat, rows.ints(), rows.nulls(), intLits)
        : longCond(flat, rows.longs(), rows.nulls(), longLits);
  }

  /**
   * One row of a tree of both lanes, with a column appended per conversion answered: the
   * conversion's value, null where it is, at the conversion's own lane.
   */
  private static final class MixedRow {
    private final List<Long> values = new ArrayList<>();
    private final List<Boolean> nulls = new ArrayList<>();

    MixedRow(long[] row, boolean[] isNull) {
      for (int c = 0; c < row.length; c++) {
        values.add(row[c]);
        nulls.add(isNull[c]);
      }
    }

    /** {@code node} with every conversion replaced by a column holding its answer. */
    VarkaVectorIR flatten(VarkaVectorIR node, int[] intLits, long[] longLits) {
      if (node instanceof WidenLane w) {
        // Exact: the int child's value, which `mixedValue` already sign-extends.
        return column(mixedOf(w.child(), intLits, longLits), LaneType.LONG);
      }
      if (node instanceof NarrowLane n) {
        OptionalLong v = mixedOf(n.child(), intLits, longLits);
        return column(v.isPresent() ? OptionalLong.of((int) v.getAsLong()) : v, LaneType.INT);
      }
      VarkaVectorIR[] children = VarkaVectorIR.childrenOf(node);
      if (children.length == 0) {
        return node;
      }
      VarkaVectorIR[] flat = new VarkaVectorIR[children.length];
      for (int k = 0; k < children.length; k++) {
        flat[k] = flatten(children[k], intLits, longLits);
      }
      return VarkaVectorIR.withChildren(node, flat);
    }

    private OptionalLong mixedOf(VarkaVectorIR child, int[] intLits, long[] longLits) {
      VarkaVectorIR flat = flatten(child, intLits, longLits);
      if (flat.laneType() == LaneType.INT) {
        OptionalInt v = value(flat, ints(), nulls(), intLits);
        return v.isPresent() ? OptionalLong.of(v.getAsInt()) : OptionalLong.empty();
      }
      return longValue(flat, longs(), nulls(), longLits);
    }

    private VarkaVectorIR column(OptionalLong v, LaneType lane) {
      values.add(v.orElse(0L));
      nulls.add(v.isEmpty());
      return new ColumnRef(values.size() - 1, lane);
    }

    long[] longs() {
      long[] out = new long[values.size()];
      for (int c = 0; c < out.length; c++) {
        out[c] = values.get(c);
      }
      return out;
    }

    int[] ints() {
      int[] out = new int[values.size()];
      for (int c = 0; c < out.length; c++) {
        out[c] = (int) (long) values.get(c);
      }
      return out;
    }

    boolean[] nulls() {
      boolean[] out = new boolean[nulls.size()];
      for (int c = 0; c < out.length; c++) {
        out[c] = nulls.get(c);
      }
      return out;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Shared rules.
  // ---------------------------------------------------------------------------------------------

  private interface IntFn {
    int apply(int v);
  }

  /** {@code f} of the child's value, null where the child is. */
  private static OptionalInt map(VarkaVectorIR child, int[] row, boolean[] isNull, int[] lits,
      IntFn f) {
    var v = value(child, row, isNull, lits);
    return v.isEmpty() ? v : OptionalInt.of(f.apply(v.getAsInt()));
  }

  /** {@code f} of two operands, left first; null where either is, the right not evaluated past
   *  a null left. */
  private static OptionalInt both(VarkaVectorIR left, VarkaVectorIR right, int[] row,
      boolean[] isNull, int[] lits, IntBinaryOperator f) {
    var l = value(left, row, isNull, lits);
    if (l.isEmpty()) {
      return l;
    }
    var r = value(right, row, isNull, lits);
    return r.isEmpty() ? r : OptionalInt.of(f.applyAsInt(l.getAsInt(), r.getAsInt()));
  }

  /** {@code greatest}/{@code least}: a null operand is skipped, both null is null. */
  private static OptionalInt pick(OptionalInt a, OptionalInt b, IntBinaryOperator op) {
    if (a.isPresent() && b.isPresent()) {
      return OptionalInt.of(op.applyAsInt(a.getAsInt(), b.getAsInt()));
    }
    return a.isPresent() ? a : b;
  }

  private static OptionalLong pick(OptionalLong a, OptionalLong b, LongBinaryOperator op) {
    if (a.isPresent() && b.isPresent()) {
      return OptionalLong.of(op.applyAsLong(a.getAsLong(), b.getAsLong()));
    }
    return a.isPresent() ? a : b;
  }

  private static boolean compare(CompareOp op, long l, long r) {
    return switch (op) {
      case LT -> l < r;
      case LE -> l <= r;
      case GT -> l > r;
      case GE -> l >= r;
      case EQ -> l == r;
    };
  }

  private static boolean inRanges(int v, List<Integer> bounds) {
    // The node's constructor keeps the bounds in sorted, disjoint pairs.
    for (int i = 0; i < bounds.size(); i += 2) {
      if (bounds.get(i) <= v && v <= bounds.get(i + 1)) {
        return true;
      }
    }
    return false;
  }

  private static Truth and(Truth l, Truth r) {
    if (l == Truth.FALSE || r == Truth.FALSE) {
      return Truth.FALSE;
    }
    return l == Truth.TRUE && r == Truth.TRUE ? Truth.TRUE : Truth.UNKNOWN;
  }

  private static Truth or(Truth l, Truth r) {
    if (l == Truth.TRUE || r == Truth.TRUE) {
      return Truth.TRUE;
    }
    return l == Truth.FALSE && r == Truth.FALSE ? Truth.FALSE : Truth.UNKNOWN;
  }

  private static Truth not(Truth t) {
    return switch (t) {
      case TRUE -> Truth.FALSE;
      case FALSE -> Truth.TRUE;
      case UNKNOWN -> Truth.UNKNOWN;
    };
  }
}
