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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * The vector IR a fused Varka loop is emitted from. A node is a value over
 * int32 lanes; {@link VarkaLoopEmitter} walks a tree of them post-order, leaving intermediates on
 * the JVM operand stack so they live in vector registers, never in memory.
 *
 * <p>Literal values never appear in the IR. A folded literal occupies a {@link LiteralSlot} - an
 * index into the {@code scalarArgs} array of {@link VarkaFusedKernel#run} - so one emitted class
 * serves every literal a query might use, and a chain's identity is its shape, not its
 * constants. That identity is what a future cross-task cache will key on (milestone 3).
 *
 * <p>Every node reports a {@link LaneType}: the two leaves carry one, every other node derives
 * it from its children, and the constructors refuse a tree whose lanes do not fit - a calendar
 * node over a 64-bit child, or a binary node whose operands disagree. {@link VarkaLoopEmitter}
 * emits both lanes, the calendar lowerings excepted: those decompose a 32-bit epoch day and
 * have no meaning at another width, which is why their constructors refuse a wider child here
 * rather than the emitter refusing the tree later.
 *
 * <p>The IR is a DAG in effect if not in shape: the records carry structural
 * {@code equals}/{@code hashCode}, and the emitter memoizes on them, so a subtree appearing in
 * several outputs is computed once per lane group no matter how the caller built the trees.
 *
 * <p>The IR splits into values and <i>conditions</i>: a {@link Cond} node is
 * mask-valued - per lane a known-true and a known-false bit, SQL's three-valued logic.
 * {@link IfElse#cond} is typed {@code Cond}, so a value cannot appear where a condition
 * belongs; the reverse direction (a condition in a value position) is rejected by the
 * emitter's analysis, since {@code Cond} must extend {@code VarkaVectorIR} for the shared
 * memo machinery to see condition nodes at all. A condition <i>as an output root</i> is legal
 * and means a selection bitmap - see {@link Cond} for the null rule there.
 */
public sealed interface VarkaVectorIR
    permits VarkaVectorIR.ColumnRef, VarkaVectorIR.LiteralSlot,
            VarkaVectorIR.AddDays, VarkaVectorIR.SubDays, VarkaVectorIR.DateDiff,
            VarkaVectorIR.IfElse, VarkaVectorIR.Greatest, VarkaVectorIR.Least,
            VarkaVectorIR.DayOfWeek, VarkaVectorIR.WeekDay, VarkaVectorIR.DayOfWeekIso,
            VarkaVectorIR.NextDay, VarkaVectorIR.ThursdayOf, VarkaVectorIR.Chrono,
            VarkaVectorIR.AddMonths, VarkaVectorIR.MakeDate,
            VarkaVectorIR.IntArith, VarkaVectorIR.IntNeg, VarkaVectorIR.ConstDivide,
            VarkaVectorIR.BoundedDivide,
            VarkaVectorIR.Cond,
            VarkaVectorIR.GuardedDay, VarkaVectorIR.GuardedRange, VarkaVectorIR.NarrowLane {

  /**
   * The physical lane a node's value occupies: {@code INT} is a 32-bit lane, {@code LONG} a
   * 64-bit one. It is a property of the representation and not of the Spark type - {@code DATE}
   * is epoch days, {@code INT} is an int and a year-month interval is a month count, and all
   * three are the same 32-bit lane - which is why the lane rides the IR rather than being
   * inferred from the type above it.
   */
  enum LaneType { INT, LONG }

  /**
   * The lane this node's value occupies, carried by the leaves and derived everywhere else. A
   * value node answers its operands' lane, a condition answers the lane it compares in, and
   * every calendar node answers {@code INT}, because it consumes epoch days and those are
   * 32 bits by definition. The switch is exhaustive over the sealed interface, so a new node
   * type refuses to compile until it says which lane it is on.
   */
  default LaneType laneType() {
    return switch (this) {
      case ColumnRef n -> n.lane();
      case LiteralSlot n -> n.lane();
      case IntArith n -> n.left().laneType();
      case IntNeg n -> n.child().laneType();
      case ConstDivide n -> n.child().laneType();
      case BoundedDivide n -> LaneType.INT;
      case Greatest n -> n.left().laneType();
      case Least n -> n.left().laneType();
      case IfElse n -> n.thenNode().laneType();
      case Compare n -> n.left().laneType();
      case And n -> n.left().laneType();
      case Or n -> n.left().laneType();
      case Not n -> n.child().laneType();
      case IsNotNull n -> n.child().laneType();
      case InRanges n -> LaneType.INT;
      case AddDays n -> LaneType.INT;
      case SubDays n -> LaneType.INT;
      case DateDiff n -> LaneType.INT;
      case GuardedDay n -> LaneType.INT;
      case GuardedRange n -> n.child().laneType();
      case NarrowLane n -> LaneType.INT;
      case DayOfWeek n -> LaneType.INT;
      case WeekDay n -> LaneType.INT;
      case DayOfWeekIso n -> LaneType.INT;
      case NextDay n -> LaneType.INT;
      case ThursdayOf n -> LaneType.INT;
      case AddMonths n -> LaneType.INT;
      case MakeDate n -> LaneType.INT;
      // The nine calendar extractions are named one by one rather than caught by a `Chrono`
      // arm: an umbrella would let a member added later inherit the int lane silently, which is
      // the opposite of what an exhaustive switch is here for. `canonical` and
      // `canonicalShallow` enumerate them for the same reason.
      case Year n -> LaneType.INT;
      case Month n -> LaneType.INT;
      case DayOfMonth n -> LaneType.INT;
      case Quarter n -> LaneType.INT;
      case DayOfYear n -> LaneType.INT;
      case LastDay n -> LaneType.INT;
      case TruncDate n -> LaneType.INT;
      case TruncDateDynamic n -> LaneType.INT;
      case WeekOfYear n -> LaneType.INT;
    };
  }

  /**
   * The lane an emission with this root drives its loop at. It is the root's own lane for every
   * node but {@link NarrowLane}, whose value is a 32-bit lane computed in a 64-bit one: the
   * loop runs at the child's species and the root narrows at its store. The emitter, its
   * budget mirror and the compiled plan all read this rather than {@link #laneType()} when
   * they ask which species a kernel is, so the three cannot disagree about it.
   */
  static LaneType emissionLane(VarkaVectorIR root) {
    return root instanceof NarrowLane n ? n.child().laneType() : root.laneType();
  }

  /**
   * Refuses a child on any lane but the int one. The calendar lowerings decompose a 32-bit
   * epoch day: a wider value reaching them would be reinterpreted rather than converted, so it
   * has to be narrowed by a node above them instead.
   */
  private static void requireInt(String what, VarkaVectorIR... children) {
    for (VarkaVectorIR child : children) {
      if (child.laneType() != LaneType.INT) {
        // The child's type name, not `canonical(child)`: that rendering recurses without a memo
        // over what is a DAG in effect, so a shared subtree is re-rendered once per edge and a
        // deep tree would cost exponential time to build a message nobody needs it in.
        throw new IllegalArgumentException(what + " takes int lanes, not " + child.laneType()
            + ", from a " + child.getClass().getSimpleName());
      }
    }
  }

  /**
   * Refuses operands that disagree on their lane. A node is emitted as vector instructions over
   * one species - a blend and a comparison need their mask and their values to agree as much as
   * an add needs its two operands to - so two lanes inside one node have no lowering. Mixing
   * them is a conversion, which is a node in its own right rather than a silent widening here.
   */
  private static void requireSameLane(String what, VarkaVectorIR first, VarkaVectorIR... rest) {
    LaneType lane = first.laneType();
    for (VarkaVectorIR other : rest) {
      if (other.laneType() != lane) {
        throw new IllegalArgumentException(
            what + " mixes lanes: " + lane + " and " + other.laneType());
      }
    }
  }

  /**
   * How a leaf's lane renders in {@link #canonical}. The int lane renders as nothing, so every
   * shape hash committed before the lane existed still holds - the same elision, and for the
   * same reason, as {@link VarkaEmitOptions#canonical()} rendering empty for the defaults.
   */
  private static String laneTag(LaneType lane) {
    return lane == LaneType.INT ? "" : ":" + lane.name().toLowerCase(Locale.ROOT);
  }

  /** The comparison a {@link Compare} node performs; lane math is signed integer ordering. */
  enum CompareOp { LT, LE, GT, GE, EQ }

  /**
   * The period {@link TruncDate} rounds a date down to. {@code WEEK} is deliberately
   * absent: Spark defines it as {@code next_day(d - 7, 'MONDAY')}, and the compiler rewrites it
   * onto {@link NextDay} over {@link SubDays} rather than giving it a lowering of its own.
   */
  enum TruncLevel { YEAR, MONTH, QUARTER }

  /** The lane-wise integer operation an {@link IntArith} performs. */
  enum IntOp { ADD, SUB, MUL }

  /**
   * What an integer operation does when its result leaves the int32 range, which is
   * Spark's {@code EvalMode} under a different name: {@code WRAP} is {@code LEGACY}, where the
   * lane wraps exactly as the JVM's own {@code iadd} does and as Spark's non-ANSI arithmetic
   * does; {@code FAIL} is {@code ANSI}, where an overflowing live lane declines the batch so
   * the row engine can raise Spark's own error; {@code NULL} is {@code TRY}, where an
   * overflowing lane becomes a null output and the batch runs on.
   *
   * <p>The mode rides the node rather than the emit options because it is semantics, not a
   * lowering choice: two projections that differ only in it must not share a kernel, which is
   * what putting it in {@link #canonical} and so in the shape hash guarantees.
   */
  enum Overflow { WRAP, FAIL, NULL }

  /**
   * A mask-valued node: per lane group it evaluates to a known-true and a
   * known-false mask, and an unknown lane (a null input somewhere below) is neither - which is
   * what makes {@code CASE WHEN}'s null condition fall through to ELSE.
   *
   * <p>As an output root a condition is a <i>selection bitmap</i>,
   * and the rule at the root is written down once, here: <b>unknown is false</b>. A row is
   * selected exactly where the condition is known true - SQL's {@code WHERE} semantics, where
   * a NULL predicate drops the row - and the emitter gets it for free, because the known-true
   * mask is a subset of the operands' validity by construction. Interior semantics are
   * unchanged: {@code IfElse} still distinguishes unknown from known false (both take ELSE
   * today, but validity follows the branch), and the known-false mask keeps {@link Not} a
   * zero-cost slot swap. In the emitter's dense body every input is valid, so no lane is
   * unknown and the degenerate single-mask form gives the same answer.
   */
  sealed interface Cond extends VarkaVectorIR
      permits Compare, And, Or, Not, IsNotNull, InRanges {}

  /**
   * The input column at {@code ordinal}, loaded once per lane group however often it is used,
   * into a lane of {@code lane}'s width.
   *
   * <p>The one-argument form is the int lane, which every column the compiler admits today is
   * on; it keeps the several hundred int-lane trees in the suites reading as they did.
   */
  record ColumnRef(int ordinal, LaneType lane) implements VarkaVectorIR {
    public ColumnRef {
      Objects.requireNonNull(lane, "lane");
    }

    public ColumnRef(int ordinal) {
      this(ordinal, LaneType.INT);
    }
  }

  /**
   * The runtime scalar argument at {@code index}, broadcast into every lane once per call,
   * outside the loop, into a lane of {@code lane}'s width.
   *
   * <p><b>One index space per lane.</b> {@code index} addresses the table its own lane owns -
   * the {@code int[]} of the seven-argument {@link VarkaFusedKernel#run} at the int lane, and
   * the {@code long[]} of the eight-argument one at the long lane - and {@link VarkaShapeKey}
   * carries one literal count, because a class holds one species and so reads one table.
   *
   * <p>The one-argument form is the int lane, as {@link ColumnRef}'s is.
   */
  record LiteralSlot(int index, LaneType lane) implements VarkaVectorIR {
    public LiteralSlot {
      Objects.requireNonNull(lane, "lane");
    }

    public LiteralSlot(int index) {
      this(index, LaneType.INT);
    }
  }

  /**
   * {@code days}, checked at runtime to lie in the range the calendar lowering decomposes
   * exactly, and reported through {@code STATUS_CHRONO_RANGE} where it does not.
   *
   * <p><b>Why the compiler inserts a node instead of the emitter finding the place.</b> The
   * range analysis lives in {@code VarkaChronoCompiler.dayRange}, and it needs literal
   * values to run: a literal day shift moves the interval by its own amount. The emitter never
   * sees those - {@link LiteralSlot} carries an index and {@code emit} is handed
   * {@code numLiterals}, not the values - so it cannot decide where a check belongs. Nor can it
   * be told out of band, because {@code VarkaShapeKey} keys the emitted class on
   * {@code (outputs, numInputs, numLiterals, options)}: two plans with this same IR and
   * different literals share one kernel, so a placement carried beside the IR rather than
   * inside it would let one shape be served the other's guards. Inside the IR, the shape key
   * separates them for free.
   *
   * <p><b>What it means for the interval above it.</b> Everything above a {@code GuardedDay}
   * may assume {@code [NARROW_MIN_DAYS, NARROW_MAX_DAYS]}, which is what lets a second shift
   * compose where the analysis would otherwise have run out of range and declined the whole
   * expression. That is the node's entire purpose: a producer guard covers one producer, and a
   * second guarded shift above it has no budget left, because the first already promised the whole
   * range (see {@code PLAN_TASK_93.md} 2).
   *
   * <p>Its check is unconditional, not behind {@link VarkaEmitOptions#guardDayProducers}, for
   * the reason the column-count {@code AddMonths} is: the compiler admits the expression on the
   * strength of this check, so a flag that removed it would leave the compile-time bound
   * standing over a value nothing bounds - a wrong answer rather than a slower one.
   */
  record GuardedDay(VarkaVectorIR days) implements VarkaVectorIR {
    public GuardedDay {
      requireInt("guardedDay", days);
    }
  }

  /**
   * {@code child}, passed through unchanged, with the batch declined if any live lane is
   * outside {@code [lo, hi]}. {@link GuardedDay}'s sibling for a range the caller names and a
   * lane the caller chooses: the day guard's range is the calendar's and its lane is the int
   * one by construction, where this one exists for values the row engine would <i>throw</i> on
   * rather than compute - {@code TIME + INTERVAL} past midnight is the first - so that the
   * batch goes to the row engine, which raises the identical error on the identical row.
   *
   * <p>The bounds are part of the node, for the reason {@link GuardedDay}'s placement is: two
   * plans with the same tree and different bounds must not share a kernel, and the shape key
   * separates them only if the bounds are inside the IR. They are {@code long} because the
   * node serves both lanes; at the int lane they must fit an int, which the emitter checks
   * when it pushes them.
   *
   * <p>Like the day guard, the check is unconditional: the compiler admits the expression on
   * the strength of it, so an option that removed it would produce a wrong answer, not a
   * slower one.
   */
  record GuardedRange(VarkaVectorIR child, long lo, long hi) implements VarkaVectorIR {
    public GuardedRange {
      if (lo > hi) {
        throw new IllegalArgumentException("an empty range guards nothing: [" + lo + ", " + hi
            + "]");
      }
    }
  }

  /**
   * {@code (int) child}: a value computed in 64-bit lanes, delivered as a 32-bit column. The
   * {@code TIME} extracts are the first case - {@code hour(t)} is a division of nanoseconds of
   * day whose quotient is an {@code IntegerType} (task 102 group C, {@code PLAN_TASK_102.md}
   * 8.3) - and the node is task 28's, admitted ahead of that task's bi-lane kernel under one
   * restriction the emitter enforces: it may only be an <b>output root</b>. The loop then
   * still runs at one species, the child's, and the root narrows once, at its store, into a
   * four-byte-per-row destination under a mask of the low half of the int lanes. Task 28
   * lifts the restriction rather than adding a node.
   *
   * <p>The narrowing is a truncation of the low 32 bits, with no overflow check: the compiler
   * builds it only over values it has proven to fit - a field of a time is at most 86399 - and
   * Spark's own {@code CAST(bigint AS int)}, which needs the ANSI decline, is task 28's to
   * build over the same node with a guard.
   *
   * <p>The child must be on the long lane: a narrowing of an int lane is the identity and
   * would only hide a missing widening somewhere below it.
   */
  record NarrowLane(VarkaVectorIR child) implements VarkaVectorIR {
    public NarrowLane {
      if (child.laneType() != LaneType.LONG) {
        throw new IllegalArgumentException("narrowLane takes a LONG child, not "
            + child.laneType() + ", from a " + child.getClass().getSimpleName());
      }
    }
  }

  /**
   * {@code days + offset}, lane-wise, wrapping on overflow exactly as Spark's {@code DateAdd}
   * does. {@code offset} is a {@link LiteralSlot} for a foldable day count, or a {@link ColumnRef}
   * for an {@code IntegerType} column - a nullable one makes the result's
   * validity the AND of both children's, not just {@code days}' (see
   * {@code VarkaLoopEmitter.planWordRef}).
   */
  record AddDays(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {
    public AddDays {
      requireInt("addDays", days, offset);
    }
  }

  /** {@code days - offset}, lane-wise; the {@code DateSub} counterpart of {@link AddDays}. */
  record SubDays(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {
    public SubDays {
      requireInt("subDays", days, offset);
    }
  }

  /**
   * {@code end - start}, lane-wise, over two date operands - Spark's {@code DateDiff}.
   * Lane math is the same {@code isub} as {@link SubDays}; the difference is at the
   * Spark level, where the result is an {@code IntegerType} day count rather than a date, which
   * the compiler tracks per output so the evaluator allocates the right vector.
   */
  record DateDiff(VarkaVectorIR end, VarkaVectorIR start) implements VarkaVectorIR {
    public DateDiff {
      requireInt("dateDiff", end, start);
    }
  }

  /**
   * {@code left OP right} over two lanes of one width - Spark's {@code Add}, {@code Subtract}
   * and {@code Multiply}. The node's lane is its operands', which the constructor requires to
   * agree; the emitter serves the int lane, where both operands and the result are
   * {@code IntegerType}. The
   * operands are int-valued nodes - a fused field such as {@link Year} or {@link DateDiff}, an
   * {@code IntegerType} column, an int literal, or nested arithmetic - never a date, which is
   * what separates this from {@link AddDays}, whose left operand is a date and whose result is
   * one.
   *
   * <p>{@code mode} carries the overflow behaviour and is part of the node's identity; see
   * {@link Overflow} for why it belongs here rather than in the emit options. Under
   * {@code WRAP} and {@code FAIL} the result's validity is the AND of the operands', the
   * null-intolerant rule every other binary node follows; under {@code NULL} it is that AND
   * with the overflowing lanes cleared, which makes this the second node after
   * {@link MakeDate} that can null a lane both of whose inputs are valid.
   */
  record IntArith(IntOp op, Overflow mode, VarkaVectorIR left, VarkaVectorIR right)
      implements VarkaVectorIR {
    public IntArith {
      requireSameLane("int arithmetic", left, right);
    }
  }

  /**
   * {@code -child} over one lane, Spark's {@code UnaryMinus}. Only
   * {@link Overflow#WRAP} and {@link Overflow#FAIL} occur: Spark has no {@code try_negative},
   * so a negation never nulls a valid lane, and the emitter rejects {@link Overflow#NULL}
   * here rather than emitting a form nothing can produce.
   *
   * <p>The one overflowing input is the lane's most negative value, whose negation is itself:
   * {@link Integer#MIN_VALUE} at the int lane, {@link Long#MIN_VALUE} at a wider one. A guard
   * written against a constant rather than against the lane is wrong on both sides - it misses
   * the overflow it exists for, and condemns a value the wider lane represents exactly.
   */
  record IntNeg(Overflow mode, VarkaVectorIR child) implements VarkaVectorIR {}

  /**
   * {@code child / divisor} by a compile-time constant, truncating toward zero the way Java's
   * {@code /} does.
   *
   * <p>The lane has neither an integer divide nor a multiply-high, so a constant division has
   * only two lowerings: a range-narrowed magic multiply, which the calendar prefix uses over
   * dividends it can prove bounded, and a conversion through double lanes, which is exact for
   * every dividend the int lane can hold, and for a 64-bit one under the bound below. This
   * node is the second one. It exists for the
   * divisions Varka cannot bound - {@code extract(YEAR FROM ym)} over a stored month count
   * above all, where the magic's exact range covers about one forty-thousandth of the type -
   * and it is therefore emitted through the double route whatever
   * {@code VarkaEmitOptions.division} says, since that option chooses among lowerings the
   * calendar has and this node has only one.
   *
   * <p>Truncation rather than floor is the contract. Through the conversion route it is free:
   * {@code D2I} and {@code D2L} are the {@code (int)} and {@code (long)} casts, which truncate
   * toward zero, so a negative dividend needs no correction step and the round-down carry the
   * sibling calendar divisions run has nothing to correct here. The magic-number route a host
   * without those conversions takes is not free: it produces a floor, so it divides the
   * magnitude and restores the sign afterwards. Removing that tail would be correct for a
   * non-negative dividend and wrong for every negative non-multiple.
   * {@code sql/varka/plans/verify_ym_division.py} checks the equivalence against
   * {@code IntervalUtils.getYears} over all 2^32 month counts.
   *
   * <p>A zero divisor has no lowering and is refused here: a division by zero raises rather
   * than producing a value, which is the row engine's job through the ghost fallback.
   *
   * <p><b>At the 64-bit lane the route is exact only under a bound, and the caller owns it.</b>
   * Every int32 dividend converts to a double exactly, so the int lane needs no precondition at
   * all. A 64-bit dividend does not: the true divide's relative error is at most 2^-53 and a
   * non-multiple's quotient lies at least {@code 1/divisor} from an integer, so truncation
   * cannot cross one while the dividend stays under {@link #EXACT_DIVIDEND_BOUND}, and is
   * silently off by one above it. The magic-number form a host without the conversion
   * instructions takes fails far harder there and not by one: bit 52 of the dividend is the low
   * bit of the exponent field its {@code 0x4330000000000000} identity relies on, so past the
   * bound the OR drops the bit and the value read back is the dividend modulo {@code 2^52}.
   *
   * <p><b>So a long-lane division states the bound its caller proved</b>, in
   * {@code dividendBound}, and cannot be built without one: the two-argument constructor
   * serves the int lane alone and refuses a {@code LONG} child, and the compact constructor
   * refuses a bound above what a lowering can honour. No static check can know a column's
   * values, so what the node enforces is that a claim was made and is admissible; making it
   * true is the caller's, and there are two ways. Structurally, the way nanoseconds of day are
   * under {@code NANOS_PER_DAY} by the type of a {@code TIME} - which is every long-lane
   * division the compiler builds today. Or by guarding: a caller with nothing to prove wraps
   * its child in a {@link GuardedRange} over the bound it then states, and a batch with a lane
   * outside it goes to the row engine.
   *
   * <p>The bound is deliberately absent from {@link #canonical}. It changes no emitted byte -
   * the guard, where there is one, is a separate node the shape key already separates - so two
   * trees differing only in what their caller proved are one kernel. It is a component all the
   * same, and therefore part of this record's equality, so a caller must derive it from the
   * tree rather than from the call site: two equal subtrees carrying different bounds would
   * stop being common subexpressions.
   */
  record ConstDivide(VarkaVectorIR child, long divisor, long dividendBound)
      implements VarkaVectorIR {

    /**
     * The exclusive bound on a 64-bit dividend's magnitude that a caller must prove. It is a
     * bound on the <i>dividend</i> and does not depend on the divisor, which is what makes it
     * one number rather than a table.
     *
     * <p>It is the tighter of the two lowerings' own bounds, because which one emits is a
     * property of the machine and not of the tree. The conversion form is exact to
     * {@code 2^53}, where a true divide's relative error of {@code 2^-53} cannot carry
     * truncation across an integer. The magic-number form that a host without the conversion
     * instructions takes is exact to {@code 2^52}, which is where its
     * {@code v | 0x4330000000000000} identity stops holding. A tree is built before either is
     * chosen, so the contract is the bound that holds under both.
     *
     * <p>{@code sql/varka/plans/verify_double_division.py} derives the wider bound and checks
     * every divisor Varka divides by against the range that divisor's lowering actually sees.
     */
    public static final long EXACT_DIVIDEND_BOUND = 1L << 52;

    /**
     * The bound every int-lane dividend satisfies by its type, and what the two-argument
     * constructor fills in. It is one past the magnitude of {@code Integer.MIN_VALUE}, which
     * is the widest an int32 dividend can be. An int32 converts to a double exactly, so the
     * int lane has no precondition of its own; carrying the bound all the same keeps one
     * record rather than two.
     */
    public static final long INT_DIVIDEND_BOUND = (1L << 31) + 1;

    /**
     * What the two-argument form passes, so the checks below can tell it from a real bound.
     * Negative rather than zero: a bound of zero is nonsense but reachable, since a caller
     * that divides a bounded value far enough arrives there, and a sentinel it collides with
     * would report a missing bound where one was given.
     */
    private static final long UNSTATED = -1;

    /**
     * The int lane's form, where the bound is the lane's own and there is nothing to prove. It
     * refuses a {@code LONG} child rather than filling one in: at the long lane the bound is
     * the caller's undertaking and stating it is the whole point, so a call that leaves it out
     * must not quietly acquire one - that would be the gap this record's javadoc describes,
     * moved one level up.
     */
    public ConstDivide(VarkaVectorIR child, long divisor) {
      this(child, divisor, UNSTATED);
    }

    public ConstDivide {
      if (divisor == 0) {
        throw new IllegalArgumentException("a constant division by zero has no lowering");
      }
      // The lowering that divides by the magnitude cannot form this one: negating
      // Long.MIN_VALUE yields Long.MIN_VALUE, so the division would be by a negative
      // magnitude and the quotient would be neither Java's nor anything else's.
      if (divisor == Long.MIN_VALUE) {
        throw new IllegalArgumentException(
            "a constant division by Long.MIN_VALUE has no representable magnitude");
      }
      // A divisor wider than the dividend's lane quotients every input to zero, which is a
      // mistake in the tree rather than a shape worth emitting.
      if (child.laneType() == LaneType.INT && (int) divisor != divisor) {
        throw new IllegalArgumentException(
            "a constant division at the int lane needs an int divisor, not " + divisor);
      }
      // The bound is the caller's undertaking and this is where it becomes unforgeable: a node
      // claiming more than a lowering can honour does not exist, so no walk downstream has to
      // ask whether one does. A claim it cannot honour is refused; a claim it can is trusted,
      // because no static check can know a column's values. The two checks above run first so
      // that a division by zero or by -1 reports what is wrong with the divisor rather than
      // what is missing beside it.
      if (dividendBound == UNSTATED) {
        if (child.laneType() != LaneType.INT) {
          throw new IllegalArgumentException("a constant division at the long lane must state "
              + "the dividend bound its caller proved: / " + divisor + " over a "
              + child.getClass().getSimpleName());
        }
        dividendBound = INT_DIVIDEND_BOUND;
      }
      // One ceiling for both lanes: a claim above what the long lane's lowerings honour means
      // nothing anywhere, and an int-lane value is under INT_DIVIDEND_BOUND whatever is
      // claimed, so the int lane needs no ceiling of its own.
      if (dividendBound < 1 || dividendBound > EXACT_DIVIDEND_BOUND) {
        throw new IllegalArgumentException("a constant division's dividend bound must lie in "
            + "[1, " + EXACT_DIVIDEND_BOUND + "], not " + dividendBound);
      }
    }
  }

  /**
   * {@code child / divisor} for a non-negative int dividend the caller proves under
   * {@code bound}: one multiply and one logical shift, exact by construction.
   *
   * <p>The pair {@code (multiplier, shift)} is the largest shift whose multiplier fits an int
   * and whose unsigned product {@code (bound - 1) * multiplier} stays under {@code 2^32}, with
   * {@code (x * multiplier) >>> shift == x / divisor} proven by exhaustion over
   * {@code [0, bound)} where it is built ({@link #of}); a pair that does not exist is refused
   * there. So the lowering is the calendar prefix's magic multiply given a node - two lane
   * operations where {@link ConstDivide}'s conversion route costs seven - and its precondition
   * is the bound, which the caller discharges structurally: a seconds-of-day column is under
   * 86400 by the leaf that made it, the seconds after the hours under 3600 by arithmetic. No
   * guard rides the node and none should; a caller that cannot prove the bound guards below it
   * with {@link GuardedRange} or uses {@link ConstDivide} (`PLAN_TASK_102.md` 8.4).
   *
   * <p>Int lane only: the search is over 32-bit products, and the 64-bit lane has its own
   * division family. The components carry the pair so the record's equality and canonical
   * form are its arithmetic, not a search that has to be repeated to compare two nodes.
   */
  record BoundedDivide(VarkaVectorIR child, int divisor, int bound, int multiplier, int shift)
      implements VarkaVectorIR {

    /** The node over {@code child}, with the pair found by search and proven over the bound. */
    public static BoundedDivide of(VarkaVectorIR child, int divisor, int bound) {
      int[] pair = magic(divisor, bound);
      return new BoundedDivide(child, divisor, bound, pair[0], pair[1]);
    }

    public BoundedDivide {
      if (child.laneType() != LaneType.INT) {
        throw new IllegalArgumentException("boundedDivide takes an INT child, not "
            + child.laneType() + ", from a " + child.getClass().getSimpleName());
      }
      if (divisor < 2 || bound < 2) {
        throw new IllegalArgumentException(
            "a bounded division needs a divisor and a bound of at least 2, not / " + divisor
                + " over [0, " + bound + ")");
      }
      int[] pair = magic(divisor, bound);
      if (pair[0] != multiplier || pair[1] != shift) {
        throw new IllegalArgumentException("the pair (" + multiplier + ", " + shift
            + ") is not the exact single-multiply form of / " + divisor + " over [0, " + bound
            + "), which is (" + pair[0] + ", " + pair[1] + ")");
      }
    }

    /**
     * The exact single-multiply form of {@code / divisor} over {@code [0, bound)}: the largest
     * shift whose multiplier fits and whose product cannot overflow the unsigned 32-bit
     * product the lane computes, checked over every dividend rather than argued.
     */
    static int[] magic(int divisor, int bound) {
      for (int k = 31; k >= 1; k--) {
        long m = ((1L << k) + divisor - 1) / divisor;
        if (m > Integer.MAX_VALUE || (bound - 1L) * m >= (1L << 32)) {
          continue;
        }
        boolean exact = true;
        for (int x = 0; x < bound && exact; x++) {
          exact = ((x * m) >>> k) == x / divisor;
        }
        if (exact) {
          return new int[] {(int) m, k};
        }
      }
      throw new IllegalArgumentException(
          "no exact single-multiply form for / " + divisor + " over [0, " + bound + ")");
    }
  }

  /**
   * {@code left OP right} over two operands on one lane, which the constructor requires to
   * agree - dates at the int lane, and the mask this produces is of that lane's species.
   * Null-intolerant: the result is known (true or false) exactly where both operands are valid,
   * unknown elsewhere.
   */
  record Compare(CompareOp op, VarkaVectorIR left, VarkaVectorIR right) implements Cond {
    public Compare {
      requireSameLane("a comparison", left, right);
    }
  }

  /**
   * Three-valued AND: known-true where both sides are known true, known-false where either
   * side is known false.
   */
  record And(Cond left, Cond right) implements Cond {
    public And {
      requireSameLane("and", left, right);
    }
  }

  /** Three-valued OR, the dual of {@link And}. */
  record Or(Cond left, Cond right) implements Cond {
    public Or {
      requireSameLane("or", left, right);
    }
  }

  /** Three-valued NOT: swaps the known-true and known-false masks - why known-false exists. */
  record Not(Cond child) implements Cond {}

  /**
   * The validity predicate: true exactly where {@code child} is non-null. The first
   * condition that reads an input's <i>validity</i> rather than comparing lane values - and the
   * first <i>total</i> one: SQL's {@code IS [NOT] NULL} never returns unknown, so its
   * known-true and known-false masks cover every lane ({@code kT = valid(child)},
   * {@code kF = ~valid(child)}). {@link And}/{@link Or}'s pair rules combine only their
   * children's pairs, so totality composes without change; what it retires is the reading that
   * an unknown lane always means "some operand was null" - here no lane is ever unknown.
   * {@code IS NULL} is {@link Not} over this node (a slot swap, no emitted code). The child is
   * restricted to {@link ColumnRef} in milestone 3: a column's validity word is live at every
   * lane group unconditionally, while a computed node's word materializes only during its
   * value walk, which runs after condition emission.
   */
  record IsNotNull(VarkaVectorIR child) implements Cond {}

  /**
   * Whether {@code child} lies in one of a set of ranges: true where some range
   * {@code [bounds[2i], bounds[2i + 1]]} contains it, false where none does, unknown where
   * {@code child} is null. It is what a disjunction of ranges over one int value compiles to -
   * {@code c between a and b or c between d and e or ...}, the partition-key filter a BI tool
   * writes for a set of date ranges - instead of a tree of comparisons whose code grows with
   * every range. The emitter evaluates it with a loop over the bounds, which the class holds in a
   * static table, so its code is the same size whatever the number of ranges.
   *
   * <p>The bounds are part of the node, for {@link GuardedRange}'s reason: two plans with the same
   * tree and different ranges must not share a kernel. The compiler sorts and merges them, so the
   * node holds disjoint, non-adjacent ranges in ascending order, which the constructor checks;
   * that makes the set's rendering, and so the shape, the same however the query ordered its
   * ranges. The int lane only: the ranges are over an int value and the table is an
   * {@code int[]}.
   */
  record InRanges(VarkaVectorIR child, List<Integer> bounds) implements Cond {
    public InRanges {
      requireInt("inRanges", child);
      bounds = List.copyOf(bounds);
      if (bounds.isEmpty() || bounds.size() % 2 != 0) {
        throw new IllegalArgumentException(
            "a range set needs pairs of bounds, got " + bounds.size());
      }
      for (int i = 0; i < bounds.size(); i += 2) {
        if (bounds.get(i) > bounds.get(i + 1)) {
          throw new IllegalArgumentException("an empty range in a range set: [" + bounds.get(i)
              + ", " + bounds.get(i + 1) + "]");
        }
        if (i > 0 && (long) bounds.get(i - 1) + 1 >= bounds.get(i)) {
          throw new IllegalArgumentException("a range set's ranges must be sorted, disjoint and"
              + " not adjacent: [.., " + bounds.get(i - 1) + "] then [" + bounds.get(i) + ", ..]");
        }
      }
    }

    /** How many ranges the set has. */
    public int ranges() {
      return bounds.size() / 2;
    }

    /** The ranges as {@code lo-hi,lo-hi,...}, for the canonical form. */
    String renderBounds() {
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < bounds.size(); i += 2) {
        if (i > 0) {
          out.append(',');
        }
        out.append(bounds.get(i)).append('-').append(bounds.get(i + 1));
      }
      return out.toString();
    }
  }

  /**
   * SQL's if/else over the {@code cond}'s <i>known-true</i> mask: a lane takes
   * {@code thenNode} where the condition is known true and {@code elseNode} everywhere else,
   * unknown included. Validity follows the chosen branch lane-wise; nothing is ANDed globally.
   */
  record IfElse(Cond cond, VarkaVectorIR thenNode, VarkaVectorIR elseNode)
      implements VarkaVectorIR {
    public IfElse {
      requireSameLane("if", thenNode, elseNode, cond);
    }
  }

  /**
   * Spark's null-skipping {@code greatest} over two operands: null only where both
   * inputs are null; where one side is null the other's value is taken, so the lane math is a
   * substitute-then-max.
   */
  record Greatest(VarkaVectorIR left, VarkaVectorIR right) implements VarkaVectorIR {
    public Greatest {
      requireSameLane("greatest", left, right);
    }
  }

  /** The {@code least} counterpart of {@link Greatest}. */
  record Least(VarkaVectorIR left, VarkaVectorIR right) implements VarkaVectorIR {
    public Least {
      requireSameLane("least", left, right);
    }
  }

  /**
   * Spark's {@code dayofweek}: {@code floorMod(days + 4, 7) + 1}, Sunday = 1 -
   * computed as {@code (floorMod(days, 7) + 4) mod 7 + 1} so the offset can never overflow the
   * int days. An {@code IntegerType} output at the Spark level.
   */
  record DayOfWeek(VarkaVectorIR days) implements VarkaVectorIR {
    public DayOfWeek {
      requireInt("dayOfWeek", days);
    }
  }

  /** Spark's {@code weekday}: {@code floorMod(days + 3, 7)}, Monday = 0. */
  record WeekDay(VarkaVectorIR days) implements VarkaVectorIR {
    public WeekDay {
      requireInt("weekDay", days);
    }
  }

  /**
   * {@code extract(DAYOFWEEK_ISO FROM d)} / {@code date_part('DOW_ISO', d)}: Monday 1
   * to Sunday 7, which the analyzer spells {@code Add(WeekDay(d), Literal(1))}. One node rather
   * than a general integer add: the value cannot overflow (a constant one over {@code 0..6}),
   * and integer arithmetic over an output is not supported. The tail is {@link WeekDay}'s
   * plus one lanewise add, the same op that separates {@link DayOfWeek} from {@link WeekDay}.
   */
  record DayOfWeekIso(VarkaVectorIR days) implements VarkaVectorIR {
    public DayOfWeekIso {
      requireInt("dayOfWeekIso", days);
    }
  }

  /**
   * Spark's {@code next_day(date, day_of_week)}: the first date strictly later than
   * {@code days} falling on the weekday {@code offset} names. {@code offset} is
   * {@code dayOfWeek - 1}, where {@code dayOfWeek} is the {@code [0, 6]} value
   * ({@code THURSDAY = 0 .. WEDNESDAY = 6}) {@code DateTimeUtils#getDayOfWeekFromString}
   * parses from the weekday argument - so {@code offset} itself ranges over {@code [-1, 5]},
   * not {@code [0, 6]}. A literal weekday resolves at compile time to a {@link LiteralSlot},
   * so one emitted class serves every weekday; a weekday column is a {@link ColumnRef} over
   * the int32 column the evaluator derives from the names before the kernel runs (
   * {@code WeekdayLeaf} ), and the lowering is the same either way, exact for every int
   * {@code offset} since it reproduces Spark's wrapping arithmetic.
   */
  record NextDay(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {
    public NextDay {
      requireInt("nextDay", days, offset);
    }
  }

  /**
   * The Thursday of the ISO week {@code days} falls in: {@code d + 3 - weekday0(d)}
   * with a Monday-based weekday, so {@code t} lies in {@code [d - 3, d + 3]} and is the day
   * whose calendar year and ordinal define the ISO week and week-based year. A day-typed
   * producer, not a {@link Chrono} member: it is the child the week tail's prefix runs over,
   * which is why it is a node of its own rather than a step inside {@link WeekOfYear} -
   * {@code Year} over the same node is {@code extract(YEAROFWEEK)}, sharing the
   * prefix. Costs {@code NextDay}'s mod-7 plus four ops.
   */
  record ThursdayOf(VarkaVectorIR days) implements VarkaVectorIR {
    public ThursdayOf {
      requireInt("thursdayOf", days);
    }
  }

  /**
   * The civil-from-days extractions, as a sealed family rather than a set the emitter has to
   * recognise by hand. Two of the emitter's decisions key off "is this a calendar node" - the
   * {@code GROUP_BUDGET} weight, and whether a body needs a range-guard accumulator - and
   * before this interface existed both asked an {@code instanceof} chain, which a fifth
   * extraction would have silently answered "no": weight 1 instead of the real one, and no
   * guard at all, publishing wrong dates instead of declining them. Adding a member here
   * makes every exhaustive switch in {@link VarkaLoopEmitter} a compile error until it is
   * handled, which is the same protection {@link Cond} gives the condition nodes.
   */
  sealed interface Chrono extends VarkaVectorIR
      permits Year, Month, DayOfMonth, Quarter, DayOfYear, LastDay,
      TruncDate, TruncDateDynamic, WeekOfYear {}

  /**
   * {@code date +- INTERVAL n MONTH/YEAR} and {@code add_months(date, n)}: month
   * arithmetic over a decomposed date, then Hinnant's {@code days_from_civil} recompose -
   * {@link VarkaChrono#daysFromCivil} is the scalar twin. {@code months} carries the (possibly
   * negative) month count as a {@link LiteralSlot} for a foldable count, or a {@link ColumnRef} for
   * an {@code IntegerType} column - the same widening
   * {@link AddDays#offset} has, with a runtime range guard on the count in place of the
   * literal's compile-time bound. Not a member of {@link Chrono} - it decomposes a date into fields
   * <i>and</i> recomposes one, roughly twice a {@link Chrono} node's cost - but the emitter
   * treats it identically for weighing and guarding, since both concerns are about "does this
   * node run a civil-from-days decomposition", which this one does.
   */
  record AddMonths(VarkaVectorIR days, VarkaVectorIR months) implements VarkaVectorIR {
    public AddMonths {
      requireInt("addMonths", days, months);
    }
  }

  /**
   * Spark's {@code make_date(year, month, day)}: a date built from three int lanes,
   * each a column or a literal. The first node with three value children and the first whose
   * result is null for non-null inputs: an invalid month or day is a null output when
   * {@code failOnError} is false and a declined batch (the row engine raises Spark's error)
   * when it is true, and a year outside {@link VarkaChrono#MAKE_DATE_MIN_YEAR}..
   * {@link VarkaChrono#MAKE_DATE_MAX_YEAR} declines in both modes. The flag is a record
   * component because it selects which code is emitted - two modes are two shapes -
   * on {@link TruncDate}'s precedent. Not a {@link Chrono} member: it recomposes, like
   * {@link AddMonths}, but decomposes nothing.
   */
  record MakeDate(VarkaVectorIR year, VarkaVectorIR month, VarkaVectorIR day,
      boolean failOnError) implements VarkaVectorIR {
    public MakeDate {
      requireInt("makeDate", year, month, day);
    }
  }

  /**
   * Spark's {@code year}: the proleptic Gregorian year of a date, as
   * {@code LocalDate#getYear} gives it. An {@code IntegerType} output at the Spark level.
   *
   * <p>The calendar nodes below are unlike every other node here in one way worth naming:
   * each expands to thirty-odd lane ops or more rather than one or two, because there is no
   * vector divide and a civil-from-days decomposition is mostly division. {@link VarkaChrono}
   * holds the arithmetic and the constants; {@link VarkaLoopEmitter} weighs these nodes
   * accordingly when it partitions outputs into loop methods: siblings over one date share a
   * method and run the decomposition once between them, and anything else keeps a
   * calendar node in a method of its own.
   *
   * <p>The sharing is below the node level. Two calendar fields of the same date are two nodes,
   * and the emitter shares the thirty-odd ops in the middle of both their emissions as a
   * fragment keyed on the date, without the IR naming a multi-value node for it.
   */
  record Year(VarkaVectorIR days) implements Chrono {
    public Year {
      requireInt("year", days);
    }
  }

  /** Spark's {@code month}, 1-12; see {@link Year} for what the node costs and why. */
  record Month(VarkaVectorIR days) implements Chrono {
    public Month {
      requireInt("month", days);
    }
  }

  /** Spark's {@code dayofmonth}, 1-31; see {@link Year}. */
  record DayOfMonth(VarkaVectorIR days) implements Chrono {
    public DayOfMonth {
      requireInt("dayOfMonth", days);
    }
  }

  /** Spark's {@code quarter}, 1-4 - the month's own division by three; see {@link Year}. */
  record Quarter(VarkaVectorIR days) implements Chrono {
    public Quarter {
      requireInt("quarter", days);
    }
  }

  /**
   * Spark's {@code dayofyear}, 1-365 or 1-366: the January-based day of year, one comparison
   * away from the March-based {@code doy} {@link VarkaChrono} already computes; see
   * {@link Year} for what the node costs and why.
   */
  record DayOfYear(VarkaVectorIR days) implements Chrono {
    public DayOfYear {
      requireInt("dayOfYear", days);
    }
  }

  /**
   * Spark's {@code last_day}: the last date of the month {@code days} falls in - a
   * {@link org.apache.spark.sql.types.DateType} output, unlike {@link Year}'s three siblings,
   * which all return an int. See {@link Year} for what a chrono node costs and why.
   */
  record LastDay(VarkaVectorIR days) implements Chrono {
    public LastDay {
      requireInt("lastDay", days);
    }
  }

  /**
   * Spark's {@code trunc(date, fmt)} at its three date levels: the first day of the
   * year, month or quarter {@code days} falls in - a {@link org.apache.spark.sql.types.DateType}
   * output like {@link LastDay}'s. The level is a record component rather than a literal slot
   * because it selects which code is emitted, not which value is used: two levels are two
   * shapes, and the shape hash must tell them apart. {@link Compare}'s {@link CompareOp} is the
   * precedent. See {@link Year} for what a chrono node costs and why.
   */
  record TruncDate(VarkaVectorIR days, TruncLevel level) implements Chrono {
    public TruncDate {
      requireInt("truncDate", days);
    }
  }

  /**
   * {@code trunc(date, fmt)} with a format <i>column</i>: {@code level} is a
   * {@link ColumnRef} to the int32 column the evaluator derives per batch from the strings
   * ({@code TruncLevelLeaf}), holding {@code DateTimeUtils.parseTruncLevel}'s own code - 6
   * ({@code WEEK}) to 9 ({@code YEAR}) - in every valid lane and a null lane elsewhere. The
   * row picks its period after the fact, so the tail computes all four results off one prefix
   * and one mod-7 and blends on the level: roughly the three literal tails plus the week's,
   * which is why this is a separate node from {@link TruncDate} rather than a fourth level of
   * it, and why a literal format keeps compiling to the literal node. The output's validity
   * is the AND of both inputs', so a null or unrecognised format nulls the row exactly as the
   * row engine's NULL does. A {@link Chrono} member like {@link TruncDate}; its decomposed
   * child is {@code days}.
   */
  record TruncDateDynamic(VarkaVectorIR days, VarkaVectorIR level) implements Chrono {
    public TruncDateDynamic {
      requireInt("truncDateDynamic", days, level);
    }
  }

  /**
   * Spark's {@code weekofyear}: the ISO-8601 week of {@code days}, 1 to 53. The
   * lowering is {@code (dayOfYear - 1) / 7 + 1} over the January day of year, which is the
   * ISO week exactly when {@code days} is the Thursday of its week - so the emitter requires
   * the child to be a {@link ThursdayOf}, and the compiler only ever builds the pair
   * {@code WeekOfYear(ThursdayOf(d))}. The node's meaning is the definition, not the
   * lowering: the reference oracle is {@code IsoFields.WEEK_OF_WEEK_BASED_YEAR} of the child's
   * value. See {@link Year} for what a chrono node costs and why.
   */
  record WeekOfYear(VarkaVectorIR days) implements Chrono {
    public WeekOfYear {
      requireInt("weekOfYear", days);
    }
  }

  /**
   * A canonical rendering of a node, pinned by hand because the shape hash is
   * derived from it and must be stable across JVMs, restarts and JDK releases - one shape,
   * one {@code VarkaFusedProjection_<hash>} name, everywhere. {@link Record#toString} makes
   * no such promise: its spec fixes only what the string mentions, not the exact format.
   * The switch is exhaustive over the sealed interface, so adding a node type refuses to
   * compile until it renders here; changing an existing rendering changes every committed
   * hash and is caught by the pinned-hash tests in {@code VarkaShapeCacheSuite} - one over a
   * plain chain, one over a key that uses every node type, so no rendering is unguarded.
   *
   * <p>The evaluator's kernel identity points at it as well - already reachable, since
   * an interface member is public - so a fallback warning names the shape the same way the
   * class's own {@link VarkaDebugInfo} does: a log line and the bytes it names agree, and
   * neither rides {@link Record#toString}.
   */
  static String canonical(VarkaVectorIR node) {
    return switch (node) {
      case ColumnRef n -> "col:" + n.ordinal() + laneTag(n.lane());
      case LiteralSlot n -> "lit:" + n.index() + laneTag(n.lane());
      case AddDays n -> "(addDays " + canonical(n.days()) + " " + canonical(n.offset()) + ")";
      case SubDays n -> "(subDays " + canonical(n.days()) + " " + canonical(n.offset()) + ")";
      case GuardedDay n -> "(guardedDay " + canonical(n.days()) + ")";
      case GuardedRange n -> "(guardedRange " + canonical(n.child()) + " " + n.lo() + " "
          + n.hi() + ")";
      case NarrowLane n -> "(narrow " + canonical(n.child()) + ")";
      case DateDiff n -> "(dateDiff " + canonical(n.end()) + " " + canonical(n.start()) + ")";
      case Compare n ->
          "(cmp:" + n.op().name() + " " + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case And n -> "(and " + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case Or n -> "(or " + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case Not n -> "(not " + canonical(n.child()) + ")";
      case IsNotNull n -> "(isNotNull " + canonical(n.child()) + ")";
      case InRanges n -> "(inRanges " + canonical(n.child()) + " " + n.renderBounds() + ")";
      case IfElse n -> "(if " + canonical(n.cond()) + " " + canonical(n.thenNode()) + " "
          + canonical(n.elseNode()) + ")";
      case Greatest n -> "(greatest " + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case Least n -> "(least " + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case DayOfWeek n -> "(dayOfWeek " + canonical(n.days()) + ")";
      case WeekDay n -> "(weekDay " + canonical(n.days()) + ")";
      case DayOfWeekIso n -> "(dayOfWeekIso " + canonical(n.days()) + ")";
      case NextDay n -> "(nextDay " + canonical(n.days()) + " " + canonical(n.offset()) + ")";
      case ThursdayOf n -> "(thursdayOf " + canonical(n.days()) + ")";
      case Year n -> "(year " + canonical(n.days()) + ")";
      case Month n -> "(month " + canonical(n.days()) + ")";
      case DayOfMonth n -> "(dayOfMonth " + canonical(n.days()) + ")";
      case Quarter n -> "(quarter " + canonical(n.days()) + ")";
      case DayOfYear n -> "(dayOfYear " + canonical(n.days()) + ")";
      case LastDay n -> "(lastDay " + canonical(n.days()) + ")";
      case TruncDate n -> "(truncDate:" + n.level().name() + " " + canonical(n.days()) + ")";
      case TruncDateDynamic n ->
          "(truncDateDynamic " + canonical(n.days()) + " " + canonical(n.level()) + ")";
      case WeekOfYear n -> "(weekOfYear " + canonical(n.days()) + ")";
      case AddMonths n ->
          "(addMonths " + canonical(n.days()) + " " + canonical(n.months()) + ")";
      case MakeDate n -> "(makeDate:" + (n.failOnError() ? "ANSI" : "NULL") + " "
          + canonical(n.year()) + " " + canonical(n.month()) + " " + canonical(n.day()) + ")";
      case IntArith n -> "(int:" + n.op().name() + ":" + n.mode().name() + " "
          + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case IntNeg n -> "(neg:" + n.mode().name() + " " + canonical(n.child()) + ")";
      case ConstDivide n -> "(divc:" + n.divisor() + " " + canonical(n.child()) + ")";
      case BoundedDivide n ->
          "(divb:" + n.divisor() + "/" + n.bound() + " " + canonical(n.child()) + ")";
    };
  }

  /**
   * The same vocabulary as {@link #canonical}, rendering one node only: children appear as the
   * numbers {@code lineOf} gives them rather than inlined, for the {@code LineNumberTable} decoding
   * key {@link VarkaDebugInfo} carries, which rendered its nodes
   * through {@link Record#toString} until then - the very format {@link #canonical} exists to
   * avoid depending on, and which no JDK promises.
   *
   * <p>{@link #canonical} is not a drop-in there, because it recurses: every line of the key
   * would carry a whole subtree, repeating each shared node once per parent. Shallow rendering
   * plus the child's line number says the same thing once, and reconstructs the DAG rather than
   * a tree - a shared subexpression is one line that several lines point at, which is exactly
   * what {@code topoOrder} means. A leaf renders identically in both, since it has no children.
   *
   * <p>Pinned the same way and for the same reason as {@link #canonical}: the rendering travels
   * inside the class bytes and is read back by tooling that has no live session, so
   * {@code VarkaEmitterContractSuite} holds a committed line map over every node type. The
   * switch is exhaustive over the sealed interface, so a new node type refuses to compile until
   * it renders here too.
   *
   * @param node the node to render.
   * @param lineOf the line number already assigned to a child node.
   */
  static String canonicalShallow(VarkaVectorIR node, ToIntFunction<VarkaVectorIR> lineOf) {
    return switch (node) {
      case ColumnRef n -> "col:" + n.ordinal() + laneTag(n.lane());
      case LiteralSlot n -> "lit:" + n.index() + laneTag(n.lane());
      case AddDays n -> "(addDays " + lineOf.applyAsInt(n.days()) + " "
          + lineOf.applyAsInt(n.offset()) + ")";
      case SubDays n -> "(subDays " + lineOf.applyAsInt(n.days()) + " "
          + lineOf.applyAsInt(n.offset()) + ")";
      case GuardedDay n -> "(guardedDay " + lineOf.applyAsInt(n.days()) + ")";
      case GuardedRange n -> "(guardedRange " + lineOf.applyAsInt(n.child()) + " " + n.lo()
          + " " + n.hi() + ")";
      case NarrowLane n -> "(narrow " + lineOf.applyAsInt(n.child()) + ")";
      case DateDiff n -> "(dateDiff " + lineOf.applyAsInt(n.end()) + " "
          + lineOf.applyAsInt(n.start()) + ")";
      case Compare n -> "(cmp:" + n.op().name() + " " + lineOf.applyAsInt(n.left()) + " "
          + lineOf.applyAsInt(n.right()) + ")";
      case And n -> "(and " + lineOf.applyAsInt(n.left()) + " "
          + lineOf.applyAsInt(n.right()) + ")";
      case Or n -> "(or " + lineOf.applyAsInt(n.left()) + " "
          + lineOf.applyAsInt(n.right()) + ")";
      case Not n -> "(not " + lineOf.applyAsInt(n.child()) + ")";
      case IsNotNull n -> "(isNotNull " + lineOf.applyAsInt(n.child()) + ")";
      case InRanges n -> "(inRanges " + lineOf.applyAsInt(n.child()) + " " + n.ranges()
          + " ranges)";
      case IfElse n -> "(if " + lineOf.applyAsInt(n.cond()) + " "
          + lineOf.applyAsInt(n.thenNode()) + " " + lineOf.applyAsInt(n.elseNode()) + ")";
      case Greatest n -> "(greatest " + lineOf.applyAsInt(n.left()) + " "
          + lineOf.applyAsInt(n.right()) + ")";
      case Least n -> "(least " + lineOf.applyAsInt(n.left()) + " "
          + lineOf.applyAsInt(n.right()) + ")";
      case DayOfWeek n -> "(dayOfWeek " + lineOf.applyAsInt(n.days()) + ")";
      case WeekDay n -> "(weekDay " + lineOf.applyAsInt(n.days()) + ")";
      case DayOfWeekIso n -> "(dayOfWeekIso " + lineOf.applyAsInt(n.days()) + ")";
      case NextDay n -> "(nextDay " + lineOf.applyAsInt(n.days()) + " "
          + lineOf.applyAsInt(n.offset()) + ")";
      case ThursdayOf n -> "(thursdayOf " + lineOf.applyAsInt(n.days()) + ")";
      case Year n -> "(year " + lineOf.applyAsInt(n.days()) + ")";
      case Month n -> "(month " + lineOf.applyAsInt(n.days()) + ")";
      case DayOfMonth n -> "(dayOfMonth " + lineOf.applyAsInt(n.days()) + ")";
      case Quarter n -> "(quarter " + lineOf.applyAsInt(n.days()) + ")";
      case DayOfYear n -> "(dayOfYear " + lineOf.applyAsInt(n.days()) + ")";
      case LastDay n -> "(lastDay " + lineOf.applyAsInt(n.days()) + ")";
      case TruncDate n ->
          "(truncDate:" + n.level().name() + " " + lineOf.applyAsInt(n.days()) + ")";
      case TruncDateDynamic n -> "(truncDateDynamic " + lineOf.applyAsInt(n.days()) + " "
          + lineOf.applyAsInt(n.level()) + ")";
      case WeekOfYear n -> "(weekOfYear " + lineOf.applyAsInt(n.days()) + ")";
      case AddMonths n -> "(addMonths " + lineOf.applyAsInt(n.days()) + " "
          + lineOf.applyAsInt(n.months()) + ")";
      case MakeDate n -> "(makeDate:" + (n.failOnError() ? "ANSI" : "NULL") + " "
          + lineOf.applyAsInt(n.year()) + " " + lineOf.applyAsInt(n.month()) + " "
          + lineOf.applyAsInt(n.day()) + ")";
      case IntArith n -> "(int:" + n.op().name() + ":" + n.mode().name() + " "
          + lineOf.applyAsInt(n.left()) + " " + lineOf.applyAsInt(n.right()) + ")";
      case IntNeg n -> "(neg:" + n.mode().name() + " " + lineOf.applyAsInt(n.child()) + ")";
      case ConstDivide n -> "(divc:" + n.divisor() + " " + lineOf.applyAsInt(n.child()) + ")";
      case BoundedDivide n ->
          "(divb:" + n.divisor() + "/" + n.bound() + " " + lineOf.applyAsInt(n.child()) + ")";
    };
  }
}
