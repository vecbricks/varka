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

package org.apache.spark.sql.catalyst.expressions.codegen

import java.util.Locale

import scala.collection.mutable

import org.apache.spark.sql.catalyst.expressions.{BoundReference, Cast, Expression, HoursOfTime,
  Literal, MakeTime, MinutesOfTime, RuntimeReplaceable, SecondsOfTime, SecondsOfTimeWithFraction,
  SubtractTimes, TimeAddInterval, TimeDiff, TimeFromMicros, TimeFromMillis, TimeFromSeconds,
  TimeToMicros, TimeToMillis, TimeToSeconds, TimeTrunc}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{ConstDivide,
  GuardedRange, IntArith, IntOp, LaneType, NarrowLane, Overflow}
import org.apache.spark.sql.catalyst.expressions.objects.StaticInvoke
import org.apache.spark.sql.catalyst.util.{DateTimeConstants}
import org.apache.spark.sql.types.{ByteType, DataType, DayTimeIntervalType, Decimal, DecimalType,
  IntegerType, LongType, StringType, TimestampNTZType, TimestampType, TimeType}
import org.apache.spark.unsafe.types.UTF8String

/**
 * The long-lane and TIME family of the compiler: the `bigint`, `TIME(p)` and day-time interval
 * leaves, the precision and unit casts that are the identity on the lane, and the TIME functions,
 * which reach the compiler as the `StaticInvoke` their `RuntimeReplaceable` rewrote into and are
 * matched by the method they invoke through `timeTargets`.
 *
 * The arms are a partial function `VarkaExpressionCompiler.compileNode` chains after the interval
 * family's; `compileRoot` calls `compileTime` directly for the functions whose int result only an
 * output can take.
 */
private[codegen] object VarkaTimeCompiler {

  /**
   * The long-lane and TIME arms of `compileNode`, in their original order: the leaves and casts,
   * and the `StaticInvoke` of a TIME function.
   */
  private[codegen] def arms(
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): PartialFunction[Expression, Option[VarkaVectorIR]] = {
    // The long lane's column leaf: a `bigint`, a `TIME(p)` and a day-time interval are one
    // eight-byte lane, holding the value, nanoseconds of day and microseconds respectively (task
    // 29). As with the interval leaf above, Spark's own typing decides where such a value may
    // appear - never in a date or an int position - so the leaf cannot put one there, and the
    // IR's constructors refuse a tree that mixes it with the int lane anyway.
    case br: BoundReference if laneOf(br.dataType).contains(LaneType.LONG) =>
      Some(columnRef(br, inputs, LaneType.LONG))
    // Ahead of the generic "non-date column" decline below, so the reason is the decision.
    case br: BoundReference if isTimestamp(br.dataType) =>
      sink.note(timestampOutOfMilestone, br)
      None
    // The long lane's literals, beside the int ones: the value is already the long the lane
    // holds, so `l > 5000000000`, `t < TIME'12:00'` and `dt > INTERVAL '1' DAY` take a slot.
    case Literal(v: Long, t) if laneOf(t).contains(LaneType.LONG) =>
      Some(sink.longSlot(v))
    // TIME's precision cast. A `TIME(p)` value is stored truncated to `p` digits and
    // `Cast.castToTime` truncates again to the target precision, so a cast to an equal or wider
    // precision - the one type coercion inserts when two precisions meet in a comparison -
    // returns its operand unchanged and compiles to the child, as the year-month MONTH relabel
    // does above. Narrowing drops digits, which is a floor division the lane has no exact form
    // of yet (task 88); it declines with its reason rather than falling through as unsupported.
    case Cast(child, TimeType(to), _, _)
        if child.dataType.isInstanceOf[TimeType]
          && to >= child.dataType.asInstanceOf[TimeType].precision =>
      compileNode(child, inputs, literals, sink)
    case c @ Cast(child, _: TimeType, _, _) if child.dataType.isInstanceOf[TimeType] =>
      sink.note("TIME narrowed to a lower precision, which truncates", c)
      None
    // The day-time interval's unit relabel, the twin of the year-month MONTH arm above: type
    // coercion casts `INTERVAL '0' SECOND` to the column's DAY TO SECOND before comparing, and
    // `castToDayTimeInterval` keeps the microseconds whole for a SECOND end field
    // (`SparkIntervalUtils.durationToMicros`), so the cast is the child. A coarser end field
    // truncates to that unit - `micros - micros % unit`, a division - and declines with its
    // reason rather than falling through as unsupported.
    case Cast(child, DayTimeIntervalType(_, DayTimeIntervalType.SECOND), _, _)
        if child.dataType.isInstanceOf[DayTimeIntervalType] =>
      compileNode(child, inputs, literals, sink)
    case c @ Cast(child, _: DayTimeIntervalType, _, _)
        if child.dataType.isInstanceOf[DayTimeIntervalType] =>
      sink.note("day-time interval narrowed to a coarser end field, which truncates", c)
      None
    // A TIME expression, which arrives as the StaticInvoke its RuntimeReplaceable rewrote itself
    // into (see `timeTargets`). The lowered ones are matched by the method they invoke; the
    // rest decline by name through the same table.
    case si: StaticInvoke if timeTargets.contains((si.staticObject, si.functionName)) =>
      compileTime(si, inputs, literals, sink)
  }

  /**
   * Whether the type is one of the two timestamps, which milestone 5 leaves out by decision
   * (`SCOPE_MILESTONE_8.md` item 31): a zoned `TIMESTAMP`'s differences and interval additions
   * are computed on local date-times in the session zone and are not lane arithmetic, and the
   * NTZ family, whose arithmetic would be plain, waits with it. The decline names the milestone
   * so EXPLAIN shows a decision rather than a gap.
   */
  private def isTimestamp(dataType: DataType): Boolean = dataType match {
    case TimestampType | TimestampNTZType => true
    case _ => false
  }

  private val timestampOutOfMilestone = "a timestamp column is outside milestone 5"

  /**
   * Every `TIME` expression Spark has, keyed by the `StaticInvoke` it actually arrives as.
   *
   * <p>None of them reaches this compiler under its own class name. All nine are
   * `RuntimeReplaceable` and rewrite themselves into a `StaticInvoke` on `DateTimeUtils` before
   * physical planning, so an arm matching `case HoursOfTime(child)` would never fire in a real
   * query - the optimizer's `ReplaceExpressions` has long since run.
   *
   * <p>The key is not written down. Each expression is constructed once here and asked for its
   * own `replacement`, and the `(staticObject, functionName)` pair is read off that. Both sides
   * therefore move together: if upstream renames `getHoursOfTime`, this table renames with it,
   * where a hardcoded string would have stopped matching silently and left nothing behind but a
   * benchmark that got slower. `PLAN_TASK_102.md` 2.1 is the argument; `VarkaTimeTargetsSuite`
   * is the check that the table still describes what Spark produces for real SQL.
   *
   * <p>A replacement that stops being a `StaticInvoke` fails here, at class initialisation,
   * rather than disappearing from the table unnoticed.
   */
  private[codegen] val timeTargets: Map[(Class[_], String), String] = {
    val t = Literal.create(0L, TimeType(TimeType.MICROS_PRECISION))
    val i = Literal.create(0, IntegerType)
    val d = Literal.create(Decimal(0), DecimalType(16, 6))
    val dt = Literal.create(0L, DayTimeIntervalType())
    val u = Literal.create(UTF8String.fromString("HOUR"), StringType)
    val l = Literal.create(0L, LongType)
    Seq[(Expression, String)](
      HoursOfTime(t) -> "hour(t)",
      MinutesOfTime(t) -> "minute(t)",
      SecondsOfTime(t) -> "second(t)",
      SecondsOfTimeWithFraction(t) -> "second(t) with its fraction",
      MakeTime(i, i, d) -> "make_time",
      TimeTrunc(u, t) -> "time_trunc",
      SubtractTimes(t, t) -> "t1 - t2",
      TimeDiff(u, t, t) -> "timediff",
      TimeAddInterval(t, dt) -> "t + interval",
      // Group E (task 158): the conversions, and the one of them that returns a decimal.
      TimeToSeconds(t) -> "time_to_seconds",
      TimeToMillis(t) -> "time_to_millis",
      TimeToMicros(t) -> "time_to_micros",
      TimeFromSeconds(l) -> "time_from_seconds",
      TimeFromMillis(l) -> "time_from_millis",
      TimeFromMicros(l) -> "time_from_micros").map {
      case (e, label) =>
        e.asInstanceOf[RuntimeReplaceable].replacement match {
          case si: StaticInvoke => (si.staticObject, si.functionName) -> label
          case other =>
            throw new IllegalStateException(
              s"$label no longer replaces into a StaticInvoke but into " +
                s"${other.getClass.getSimpleName}; VarkaExpressionCompiler.timeTargets must be " +
                "rewritten rather than quietly stop matching")
        }
    }.toMap
  }

  /**
   * The reason a `TIME` expression declines, naming the expression rather than reporting it as
   * unsupported.
   *
   * <p>Task 102 lowers these one group at a time, and the difference between "not lowered yet"
   * and "unsupported" is what tells a reader which. The same distinction task 89 drew for
   * `extract(MONTH FROM ym)`, where a bare decline would have suggested the division was still
   * missing when the output type was the blocker.
   */
  private def timeNotLoweredYet(label: String): String =
    s"$label is a TIME expression Varka does not lower yet (task 102)"

  /**
   * The reason the two decimal-valued `TIME` expressions decline, which is the representation
   * and not the arithmetic: their value is an unscaled long the lane already computes, and the
   * column Arrow holds it in is sixteen bytes a row, which no Varka output writes yet
   * (`PLAN_TASK_102.md` section 9, task 157).
   */
  private def decimalColumnNotYet(label: String): String =
    s"$label returns a decimal, whose Arrow column is sixteen bytes a row and which no Varka " +
      "output writes yet; the value is an unscaled long the lane holds (task 157)"

  private[codegen] def compileTime(
      si: StaticInvoke,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink,
      atRoot: Boolean = false): Option[VarkaVectorIR] = {
    val label = timeTargets((si.staticObject, si.functionName))
    def long(e: Expression): Option[VarkaVectorIR] =
      compileNode(e, inputs, literals, sink).filter { ir =>
        // The arguments are TIME and interval columns, literals and their widening casts, all
        // of which the leaf arms above put on the long lane; anything else declined already.
        ir.laneType() == LaneType.LONG
      }
    // An int computed in the long lane, which only an output root can deliver (see
    // `compileRoot`): the narrowing is the kernel's store, so under another node the entry
    // declines and says why.
    def narrowed(time: Expression)(build: VarkaVectorIR => VarkaVectorIR): Option[VarkaVectorIR] =
      if (atRoot) {
        long(time).map(t => new NarrowLane(build(t)))
      } else {
        sink.note(s"$label is an int computed in the long lane, which the kernel narrows at " +
          "its store: only an output can take it until task 28 narrows inside a tree", si)
        None
      }
    def literalUnit(e: Expression, table: Map[String, Long], what: String): Option[Long] =
      e match {
        case Literal(u: UTF8String, _) =>
          val found = table.get(u.toString.toUpperCase(Locale.ROOT))
          if (found.isEmpty) sink.note(s"$label: unknown $what '$u'", si)
          found
        case other =>
          sink.note(s"$label: the $what is not a literal, and the divisor is part of the " +
            "kernel's shape", other)
          None
      }
    (si.functionName, si.arguments) match {
      case ("subtractTimes", Seq(end, start)) =>
        for (e <- long(end); st <- long(start)) yield
          new ConstDivide(new IntArith(IntOp.SUB, Overflow.WRAP, e, st),
            DateTimeConstants.NANOS_PER_MICROS, dayOfNanos)
      case ("timeDiff", Seq(unit, start, end)) =>
        for {
          nanos <- literalUnit(unit, nanosPerTimeUnit, "unit")
          e <- long(end)
          st <- long(start)
        } yield new ConstDivide(new IntArith(IntOp.SUB, Overflow.WRAP, e, st), nanos,
          dayOfNanos)
      case ("timeTrunc", Seq(level, time)) =>
        for {
          unit <- literalUnit(level, nanosPerTimeUnit, "level")
          t <- long(time)
        } yield new IntArith(IntOp.MUL, Overflow.WRAP,
          new ConstDivide(t, unit, dayOfNanos), sink.longSlot(unit))
      // The three field extracts (group C): hour is one division of the nanoseconds of day,
      // minute and second a division and the remainder of a further division by sixty, each
      // built the way `DateTimeUtils` computes it through `LocalTime` and delivered as an int
      // column by a narrowing root - the value stays in the long lane and narrows at the store
      // (`PLAN_TASK_102.md` 8.3). Every dividend is nanoseconds of day or a quotient of it,
      // under the type's bound and so under `ConstDivide.EXACT_DIVIDEND_BOUND` structurally,
      // and every result is under 86400, so nothing overflows and nothing is guarded.
      case ("getHoursOfTime", Seq(time)) =>
        narrowed(time)(t => new ConstDivide(t, nanosPerTimeUnit("HOUR"), dayOfNanos))
      case ("getMinutesOfTime", Seq(time)) =>
        narrowed(time)(t =>
          remainderOfSixty(new ConstDivide(t, nanosPerTimeUnit("MINUTE"), dayOfNanos), sink))
      case ("getSecondsOfTime", Seq(time)) =>
        narrowed(time)(t =>
          remainderOfSixty(new ConstDivide(t, nanosPerTimeUnit("SECOND"), dayOfNanos), sink))
      // timeAddInterval(t, p, dt, endField, target): addExact(t, multiplyExact(dt, 1000)),
      // thrown out of if the sum leaves [0, NANOS_PER_DAY), then truncated to `target` digits.
      // Two guards make the lane's wrapping arithmetic exact and the throw a decline. The
      // interval is held to one day either way first: any |dt| beyond that puts every t's sum
      // outside the day, so Spark throws on every such row and the row engine may as well
      // raise it; inside it, dt * 1000 and the sum both stay under 2^48 and cannot overflow.
      // The sum is then held to the day, which is the throw itself. The precision truncation
      // is the identity and is not emitted - see `timeAddIntervalTruncates`, which proves it.
      case ("timeAddInterval",
          Seq(time, Literal(_, IntegerType), interval, Literal(_, ByteType),
            Literal(target: Int, IntegerType))) =>
        if (timeAddIntervalTruncates(time.dataType, interval.dataType, target)) {
          // Not reachable for any type Spark admits today; a decline rather than a wrong
          // answer if that ever changes.
          sink.note(s"$label: the precision truncation is not the identity for these types",
            si)
          return None
        }
        // A literal interval's guard is decided here rather than per lane: one outside a day
        // crosses midnight for every time, which is the row engine's error to raise for every
        // row, and one inside it is already nanoseconds the kernel can add. A column takes the
        // guard and the multiply.
        // A def, not a val: the time is compiled first, so the inputs keep the expression's
        // argument order, as every other lowering's do.
        def nanos: Option[VarkaVectorIR] = interval match {
          case Literal(micros: Long, _: DayTimeIntervalType) =>
            if (math.abs(micros) > DateTimeConstants.MICROS_PER_DAY) {
              sink.note(s"$label: the interval is longer than a day, so every time crosses " +
                "midnight and the row engine raises the error", si)
              None
            } else {
              Some(sink.longSlot(micros * DateTimeConstants.NANOS_PER_MICROS))
            }
          case _ =>
            long(interval).map { dt =>
              val micros = new GuardedRange(dt, -DateTimeConstants.MICROS_PER_DAY,
                DateTimeConstants.MICROS_PER_DAY)
              new IntArith(IntOp.MUL, Overflow.WRAP, micros,
                sink.longSlot(DateTimeConstants.NANOS_PER_MICROS))
            }
        }
        for (t <- long(time); n <- nanos) yield
          new GuardedRange(new IntArith(IntOp.ADD, Overflow.WRAP, t, n), 0L,
            DateTimeConstants.NANOS_PER_DAY - 1)
      // Group E (task 158): the conversions. `timeToMillis` and `timeToMicros` are floor
      // divisions of a non-negative count, so a truncating constant division; the three
      // `timeFrom*` are `multiplyExact` under the conversion's own range check, which throws
      // unless the result is inside the day - so the count is guarded to the day's worth of its
      // unit, where the product cannot overflow and the result is a TIME, and a count outside
      // declines the batch to the row engine, which raises Spark's error. A count that is not on
      // the long lane - an int column, a decimal, a double - declines where its leaf does.
      case ("timeToMillis", Seq(time)) =>
        long(time).map(t => new ConstDivide(t, DateTimeConstants.NANOS_PER_MILLIS, dayOfNanos))
      case ("timeToMicros", Seq(time)) =>
        long(time).map(t => new ConstDivide(t, DateTimeConstants.NANOS_PER_MICROS, dayOfNanos))
      case ("timeFromSeconds", Seq(count)) =>
        timeFromUnits(count, DateTimeConstants.NANOS_PER_SECOND, sink, long)
      case ("timeFromMillis", Seq(count)) =>
        timeFromUnits(count, DateTimeConstants.NANOS_PER_MILLIS, sink, long)
      case ("timeFromMicros", Seq(count)) =>
        timeFromUnits(count, DateTimeConstants.NANOS_PER_MICROS, sink, long)
      case ("getSecondsOfTimeWithFraction", _) | ("timeToSeconds", _) =>
        sink.note(decimalColumnNotYet(label), si)
        None
      case _ =>
        sink.note(timeNotLoweredYet(label), si)
        None
    }
  }

  /**
   * `count * nanosPerUnit` as a TIME: the count guarded to `[0, (NANOS_PER_DAY - 1) / unit]`,
   * inside which the wrapping multiply is exact and the result is inside the day, so the guard
   * is the conversion's own range check and its decline is the row engine's error.
   */
  private def timeFromUnits(count: Expression, nanosPerUnit: Long, sink: DeclineSink,
      long: Expression => Option[VarkaVectorIR]): Option[VarkaVectorIR] =
    long(count).map { n =>
      val guarded = new GuardedRange(n, 0L, (DateTimeConstants.NANOS_PER_DAY - 1) / nanosPerUnit)
      new IntArith(IntOp.MUL, Overflow.WRAP, guarded, sink.longSlot(nanosPerUnit))
    }

  /**
   * Whether `truncateTimeToPrecision(sum, target)` inside `timeAddInterval` can change the sum,
   * which decides whether the lowering must emit it. It cannot, for every input type Spark
   * admits, and this is the argument the kernel rests on rather than a re-derivation per call:
   * the time is a multiple of 10^(9 - p) by its type, and the interval in nanoseconds is a
   * multiple of 10^3 - or of a whole minute when its end field is coarser than SECOND. The
   * target is max(p, 6) in the first case and p in the second, and in both the sum is a
   * multiple of 10^(9 - target), which is exactly what the truncation removes nothing from.
   * Kept as a function so the claim is checked against the types at compile time and a
   * future TimeType or interval that breaks the argument refuses to lower rather than lowering
   * wrongly.
   */
  private[codegen] def timeAddIntervalTruncates(
      time: DataType, interval: DataType, target: Int): Boolean = {
    val p = time.asInstanceOf[TimeType].precision
    val endField = interval.asInstanceOf[DayTimeIntervalType].endField
    val sumGranularity = if (endField < DayTimeIntervalType.SECOND) {
      // Whole minutes at least: 6e10 nanoseconds, which every 10^(9 - p) divides.
      math.min(9 - p, 10)
    } else {
      // Microseconds: 10^3 nanoseconds.
      math.min(9 - p, 3)
    }
    // The truncation is the identity iff the sum's granularity is at least the target's.
    sumGranularity < 9 - target
  }

  /**
   * The nanoseconds in each unit `time_diff` and `time_trunc` accept, spelled as
   * `DateTimeUtils.getNanosPerTimeUnit` and `parseTimeTruncLevel` spell them - the same five
   * names, and nothing coarser than an hour, because a `TIME` has no day.
   */
  private val nanosPerTimeUnit: Map[String, Long] = Map(
    "MICROSECOND" -> DateTimeConstants.NANOS_PER_MICROS,
    "MILLISECOND" -> DateTimeConstants.NANOS_PER_MILLIS,
    "SECOND" -> DateTimeConstants.NANOS_PER_SECOND,
    "MINUTE" -> DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE,
    "HOUR" -> DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE
      * DateTimeConstants.MINUTES_PER_HOUR)

  /**
   * `x % 60` for a non-negative long-lane `x`, as `x - (x / 60) * 60`: the IR has no remainder
   * node, and the subtraction cannot go wrong for a count that is a quotient of the day.
   */
  private def remainderOfSixty(x: ConstDivide, sink: DeclineSink): VarkaVectorIR =
    new IntArith(IntOp.SUB, Overflow.WRAP, x,
      new IntArith(IntOp.MUL, Overflow.WRAP,
        new ConstDivide(x, DateTimeConstants.SECONDS_PER_MINUTE, quotientBound(x)),
        sink.longSlot(DateTimeConstants.SECONDS_PER_MINUTE)))

  /**
   * The bound a quotient inherits from its dividend's: `|x| < b` divided by `d` gives
   * `|x / d| < ceil(b / |d|)`. Derived from the node rather than restated beside it, because
   * the bound is part of `ConstDivide`'s equality: two equal subtrees carrying bounds that were
   * written out separately would stop being one common subexpression.
   */
  private def quotientBound(x: ConstDivide): Long = {
    val d = math.abs(x.divisor)
    math.max(1L, (x.dividendBound + d - 1) / d)
  }

  /** Every `TIME` is a count of nanoseconds of day, which is the bound its divisions state. */
  private val dayOfNanos: Long = DateTimeConstants.NANOS_PER_DAY
}
