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

import java.util.function.ToIntFunction;

/**
 * The vector IR a fused Varka loop is emitted from (milestone 2, task 9). A node is a value over
 * int32 lanes; {@link VarkaLoopEmitter} walks a tree of them post-order, leaving intermediates on
 * the JVM operand stack so they live in vector registers, never in memory.
 *
 * <p>Literal values never appear in the IR. A folded literal occupies a {@link LiteralSlot} - an
 * index into the {@code scalarArgs} array of {@link VarkaFusedKernel#run} - so one emitted class
 * serves every literal a query might use, and a chain's identity is its shape, not its
 * constants. That identity is what a future cross-task cache will key on (milestone 3).
 *
 * <p>Every node reports a {@link LaneType}; only {@code INT} exists in this milestone, and the
 * emitter rejects anything else. The field is carried from day one so that wider lanes extend
 * the IR instead of reworking it.
 *
 * <p>The IR is a DAG in effect if not in shape: the records carry structural
 * {@code equals}/{@code hashCode}, and the emitter memoizes on them, so a subtree appearing in
 * several outputs is computed once per lane group no matter how the caller built the trees
 * (task 10).
 *
 * <p>Task 11 splits the IR into values and <i>conditions</i>: a {@link Cond} node is
 * mask-valued - per lane a known-true and a known-false bit, SQL's three-valued logic.
 * {@link IfElse#cond} is typed {@code Cond}, so a value cannot appear where a condition
 * belongs; the reverse direction (a condition in a value position) is rejected by the
 * emitter's analysis, since {@code Cond} must extend {@code VarkaVectorIR} for the shared
 * memo machinery to see condition nodes at all. A condition <i>as an output root</i> is legal
 * since task 21 and means a selection bitmap - see {@link Cond} for the null rule there.
 */
public sealed interface VarkaVectorIR
    permits VarkaVectorIR.ColumnRef, VarkaVectorIR.LiteralSlot,
            VarkaVectorIR.AddDays, VarkaVectorIR.SubDays, VarkaVectorIR.DateDiff,
            VarkaVectorIR.IfElse, VarkaVectorIR.Greatest, VarkaVectorIR.Least,
            VarkaVectorIR.DayOfWeek, VarkaVectorIR.WeekDay, VarkaVectorIR.DayOfWeekIso,
            VarkaVectorIR.NextDay, VarkaVectorIR.ThursdayOf, VarkaVectorIR.Chrono,
            VarkaVectorIR.AddMonths, VarkaVectorIR.MakeDate, VarkaVectorIR.Cond {

  /** The lane type a node evaluates to. Only 32-bit int lanes exist in milestone 2. */
  enum LaneType { INT }

  default LaneType laneType() {
    return LaneType.INT;
  }

  /** The comparison a {@link Compare} node performs; lane math is signed int ordering. */
  enum CompareOp { LT, LE, GT, GE, EQ }

  /**
   * The period {@link TruncDate} rounds a date down to (task 35). {@code WEEK} is deliberately
   * absent: Spark defines it as {@code next_day(d - 7, 'MONDAY')}, and the compiler rewrites it
   * onto {@link NextDay} over {@link SubDays} rather than giving it a lowering of its own.
   */
  enum TruncLevel { YEAR, MONTH, QUARTER }

  /**
   * A mask-valued node (task 11): per lane group it evaluates to a known-true and a
   * known-false mask, and an unknown lane (a null input somewhere below) is neither - which is
   * what makes {@code CASE WHEN}'s null condition fall through to ELSE.
   *
   * <p>Interior until task 21; as an output root a condition is a <i>selection bitmap</i>,
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
      permits Compare, And, Or, Not, IsNotNull {}

  /** The input column at {@code ordinal}, loaded once per lane group however often it is used. */
  record ColumnRef(int ordinal) implements VarkaVectorIR {}

  /**
   * The runtime scalar argument at {@code index}, broadcast into every lane once per call,
   * outside the loop.
   */
  record LiteralSlot(int index) implements VarkaVectorIR {}

  /**
   * {@code days + offset}, lane-wise, wrapping on overflow exactly as Spark's {@code DateAdd}
   * does. {@code offset} is a {@link LiteralSlot} for a foldable day count, or (since task 38) a
   * {@link ColumnRef} for an {@code IntegerType} column - a nullable one makes the result's
   * validity the AND of both children's, not just {@code days}' (see
   * {@code VarkaLoopEmitter.planWordRef}).
   */
  record AddDays(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {}

  /** {@code days - offset}, lane-wise; the {@code DateSub} counterpart of {@link AddDays}. */
  record SubDays(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {}

  /**
   * {@code end - start}, lane-wise, over two date operands - Spark's {@code DateDiff}
   * (task 10). Lane math is the same {@code isub} as {@link SubDays}; the difference is at the
   * Spark level, where the result is an {@code IntegerType} day count rather than a date, which
   * the compiler tracks per output so the evaluator allocates the right vector.
   */
  record DateDiff(VarkaVectorIR end, VarkaVectorIR start) implements VarkaVectorIR {}

  /**
   * {@code left OP right} over two date-valued operands (task 11). Null-intolerant: the result
   * is known (true or false) exactly where both operands are valid, unknown elsewhere.
   */
  record Compare(CompareOp op, VarkaVectorIR left, VarkaVectorIR right) implements Cond {}

  /**
   * Three-valued AND: known-true where both sides are known true, known-false where either
   * side is known false.
   */
  record And(Cond left, Cond right) implements Cond {}

  /** Three-valued OR, the dual of {@link And}. */
  record Or(Cond left, Cond right) implements Cond {}

  /** Three-valued NOT: swaps the known-true and known-false masks - why known-false exists. */
  record Not(Cond child) implements Cond {}

  /**
   * The validity predicate (task 20): true exactly where {@code child} is non-null. The first
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
   * SQL's if/else over the {@code cond}'s <i>known-true</i> mask (task 11): a lane takes
   * {@code thenNode} where the condition is known true and {@code elseNode} everywhere else,
   * unknown included. Validity follows the chosen branch lane-wise; nothing is ANDed globally.
   */
  record IfElse(Cond cond, VarkaVectorIR thenNode, VarkaVectorIR elseNode)
      implements VarkaVectorIR {}

  /**
   * Spark's null-skipping {@code greatest} over two operands (task 11): null only where both
   * inputs are null; where one side is null the other's value is taken, so the lane math is a
   * substitute-then-max.
   */
  record Greatest(VarkaVectorIR left, VarkaVectorIR right) implements VarkaVectorIR {}

  /** The {@code least} counterpart of {@link Greatest}. */
  record Least(VarkaVectorIR left, VarkaVectorIR right) implements VarkaVectorIR {}

  /**
   * Spark's {@code dayofweek} (task 11): {@code floorMod(days + 4, 7) + 1}, Sunday = 1 -
   * computed as {@code (floorMod(days, 7) + 4) mod 7 + 1} so the offset can never overflow the
   * int days. An {@code IntegerType} output at the Spark level.
   */
  record DayOfWeek(VarkaVectorIR days) implements VarkaVectorIR {}

  /** Spark's {@code weekday} (task 11): {@code floorMod(days + 3, 7)}, Monday = 0. */
  record WeekDay(VarkaVectorIR days) implements VarkaVectorIR {}

  /**
   * {@code extract(DAYOFWEEK_ISO FROM d)} / {@code date_part('DOW_ISO', d)} (task 57): Monday 1
   * to Sunday 7, which the analyzer spells {@code Add(WeekDay(d), Literal(1))}. One node rather
   * than a general integer add: the value cannot overflow (a constant one over {@code 0..6}),
   * and integer arithmetic over an output is milestone 5's task 30. The tail is {@link WeekDay}'s
   * plus one lanewise add, the same op that separates {@link DayOfWeek} from {@link WeekDay}.
   */
  record DayOfWeekIso(VarkaVectorIR days) implements VarkaVectorIR {}

  /**
   * Spark's {@code next_day(date, day_of_week)} (task 33): the first date strictly later than
   * {@code days} falling on the weekday {@code offset} names. {@code offset} is
   * {@code dayOfWeek - 1}, where {@code dayOfWeek} is the {@code [0, 6]} value
   * ({@code THURSDAY = 0 .. WEDNESDAY = 6}) {@code DateTimeUtils#getDayOfWeekFromString}
   * parses from the weekday argument - so {@code offset} itself ranges over {@code [-1, 5]},
   * not {@code [0, 6]}. A literal weekday resolves at compile time to a {@link LiteralSlot},
   * so one emitted class serves every weekday; a weekday column is a {@link ColumnRef} over
   * the int32 column the evaluator derives from the names before the kernel runs (task 59,
   * {@code WeekdayLeaf}), and the lowering is the same either way, exact for every int
   * {@code offset} since it reproduces Spark's wrapping arithmetic.
   */
  record NextDay(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {}

  /**
   * The Thursday of the ISO week {@code days} falls in (task 37): {@code d + 3 - weekday0(d)}
   * with a Monday-based weekday, so {@code t} lies in {@code [d - 3, d + 3]} and is the day
   * whose calendar year and ordinal define the ISO week and week-based year. A day-typed
   * producer, not a {@link Chrono} member: it is the child the week tail's prefix runs over,
   * which is why it is a node of its own rather than a step inside {@link WeekOfYear} -
   * {@code Year} over the same node is {@code extract(YEAROFWEEK)} (task 58), sharing the
   * prefix. Costs {@code NextDay}'s mod-7 plus four ops.
   */
  record ThursdayOf(VarkaVectorIR days) implements VarkaVectorIR {}

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
      TruncDate, WeekOfYear {}

  /**
   * {@code date +- INTERVAL n MONTH/YEAR} and {@code add_months(date, n)} (task 40): month
   * arithmetic over a decomposed date, then Hinnant's {@code days_from_civil} recompose -
   * {@link VarkaChrono#daysFromCivil} is the scalar twin. {@code months} carries the (possibly
   * negative) month count in a {@link LiteralSlot}; it must be a compile-time literal, the way
   * {@link AddDays#offset} is. Not a member of {@link Chrono} - it decomposes a date into
   * fields <i>and</i> recomposes one, roughly twice a {@link Chrono} node's cost - but the
   * emitter treats it identically for weighing and guarding, since both concerns are about
   * "does this node run a civil-from-days decomposition", which this one does.
   */
  record AddMonths(VarkaVectorIR days, VarkaVectorIR months) implements VarkaVectorIR {}

  /**
   * Spark's {@code make_date(year, month, day)} (task 42): a date built from three int lanes,
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
      boolean failOnError) implements VarkaVectorIR {}

  /**
   * Spark's {@code year} (task 26): the proleptic Gregorian year of a date, as
   * {@code LocalDate#getYear} gives it. An {@code IntegerType} output at the Spark level.
   *
   * <p>The five calendar nodes below are unlike every other node here in one way worth naming:
   * each expands to about fifty lane ops or more rather than one or two, because there is no
   * vector divide and a civil-from-days decomposition is mostly division. {@link VarkaChrono}
   * holds the arithmetic and the constants; {@link VarkaLoopEmitter} weighs these nodes
   * accordingly when it partitions outputs into loop methods, so no two of them land in one
   * method.
   *
   * <p>Each node carries the whole decomposition rather than sharing it: two calendar fields of
   * the same date compute it twice, in two sibling methods, which is the trade task 17 measured
   * and chose. Sharing it would need a multi-value node, which the IR has no shape for.
   */
  record Year(VarkaVectorIR days) implements Chrono {}

  /** Spark's {@code month}, 1-12; see {@link Year} for what the node costs and why. */
  record Month(VarkaVectorIR days) implements Chrono {}

  /** Spark's {@code dayofmonth}, 1-31; see {@link Year}. */
  record DayOfMonth(VarkaVectorIR days) implements Chrono {}

  /** Spark's {@code quarter}, 1-4 - the month's own division by three; see {@link Year}. */
  record Quarter(VarkaVectorIR days) implements Chrono {}

  /**
   * Spark's {@code dayofyear}, 1-365 or 1-366: the January-based day of year, one comparison
   * away from the March-based {@code doy} {@link VarkaChrono} already computes; see
   * {@link Year} for what the node costs and why.
   */
  record DayOfYear(VarkaVectorIR days) implements Chrono {}

  /**
   * Spark's {@code last_day} (task 36): the last date of the month {@code days} falls in - a
   * {@link org.apache.spark.sql.types.DateType} output, unlike {@link Year}'s three siblings,
   * which all return an int. See {@link Year} for what a chrono node costs and why.
   */
  record LastDay(VarkaVectorIR days) implements Chrono {}

  /**
   * Spark's {@code trunc(date, fmt)} at its three date levels (task 35): the first day of the
   * year, month or quarter {@code days} falls in - a {@link org.apache.spark.sql.types.DateType}
   * output like {@link LastDay}'s. The level is a record component rather than a literal slot
   * because it selects which code is emitted, not which value is used: two levels are two
   * shapes, and the shape hash must tell them apart. {@link Compare}'s {@link CompareOp} is the
   * precedent. See {@link Year} for what a chrono node costs and why.
   */
  record TruncDate(VarkaVectorIR days, TruncLevel level) implements Chrono {}

  /**
   * Spark's {@code weekofyear} (task 37): the ISO-8601 week of {@code days}, 1 to 53. The
   * lowering is {@code (dayOfYear - 1) / 7 + 1} over the January day of year, which is the
   * ISO week exactly when {@code days} is the Thursday of its week - so the emitter requires
   * the child to be a {@link ThursdayOf}, and the compiler only ever builds the pair
   * {@code WeekOfYear(ThursdayOf(d))}. The node's meaning is the definition, not the
   * lowering: the reference oracle is {@code IsoFields.WEEK_OF_WEEK_BASED_YEAR} of the child's
   * value. See {@link Year} for what a chrono node costs and why.
   */
  record WeekOfYear(VarkaVectorIR days) implements Chrono {}

  /**
   * A canonical rendering of a node, pinned by hand because the shape hash (task 18) is
   * derived from it and must be stable across JVMs, restarts and JDK releases - one shape,
   * one {@code VarkaFusedProjection_<hash>} name, everywhere. {@link Record#toString} makes
   * no such promise: its spec fixes only what the string mentions, not the exact format.
   * The switch is exhaustive over the sealed interface, so adding a node type refuses to
   * compile until it renders here; changing an existing rendering changes every committed
   * hash and is caught by the pinned-hash tests in {@code VarkaShapeCacheSuite} - one over a
   * plain chain, one over a key that uses every node type, so no rendering is unguarded.
   *
   * <p>Task 23 pointed the evaluator's kernel identity at it as well - already reachable, since
   * an interface member is public - so a fallback warning names the shape the same way the
   * class's own {@link VarkaDebugInfo} does: a log line and the bytes it names agree, and
   * neither rides {@link Record#toString}.
   */
  static String canonical(VarkaVectorIR node) {
    return switch (node) {
      case ColumnRef n -> "col:" + n.ordinal();
      case LiteralSlot n -> "lit:" + n.index();
      case AddDays n -> "(addDays " + canonical(n.days()) + " " + canonical(n.offset()) + ")";
      case SubDays n -> "(subDays " + canonical(n.days()) + " " + canonical(n.offset()) + ")";
      case DateDiff n -> "(dateDiff " + canonical(n.end()) + " " + canonical(n.start()) + ")";
      case Compare n ->
          "(cmp:" + n.op().name() + " " + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case And n -> "(and " + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case Or n -> "(or " + canonical(n.left()) + " " + canonical(n.right()) + ")";
      case Not n -> "(not " + canonical(n.child()) + ")";
      case IsNotNull n -> "(isNotNull " + canonical(n.child()) + ")";
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
      case WeekOfYear n -> "(weekOfYear " + canonical(n.days()) + ")";
      case AddMonths n ->
          "(addMonths " + canonical(n.days()) + " " + canonical(n.months()) + ")";
      case MakeDate n -> "(makeDate:" + (n.failOnError() ? "ANSI" : "NULL") + " "
          + canonical(n.year()) + " " + canonical(n.month()) + " " + canonical(n.day()) + ")";
    };
  }

  /**
   * The same vocabulary as {@link #canonical}, rendering one node only: children appear as the
   * numbers {@code lineOf} gives them rather than inlined. Added by task 23 for the
   * {@code LineNumberTable} decoding key {@link VarkaDebugInfo} carries, which rendered its nodes
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
   * {@code VarkaLoopEmitterSuite} holds a committed line map over every node type. The switch is
   * exhaustive over the sealed interface, so a new node type refuses to compile until it renders
   * here too.
   *
   * @param node the node to render.
   * @param lineOf the line number already assigned to a child node.
   */
  static String canonicalShallow(VarkaVectorIR node, ToIntFunction<VarkaVectorIR> lineOf) {
    return switch (node) {
      case ColumnRef n -> "col:" + n.ordinal();
      case LiteralSlot n -> "lit:" + n.index();
      case AddDays n -> "(addDays " + lineOf.applyAsInt(n.days()) + " "
          + lineOf.applyAsInt(n.offset()) + ")";
      case SubDays n -> "(subDays " + lineOf.applyAsInt(n.days()) + " "
          + lineOf.applyAsInt(n.offset()) + ")";
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
      case WeekOfYear n -> "(weekOfYear " + lineOf.applyAsInt(n.days()) + ")";
      case AddMonths n -> "(addMonths " + lineOf.applyAsInt(n.days()) + " "
          + lineOf.applyAsInt(n.months()) + ")";
      case MakeDate n -> "(makeDate:" + (n.failOnError() ? "ANSI" : "NULL") + " "
          + lineOf.applyAsInt(n.year()) + " " + lineOf.applyAsInt(n.month()) + " "
          + lineOf.applyAsInt(n.day()) + ")";
    };
  }
}
