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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Hour;
import org.apache.spark.sql.catalyst.expressions.HoursOfTime;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.MakeTime;
import org.apache.spark.sql.catalyst.expressions.Minute;
import org.apache.spark.sql.catalyst.expressions.MinutesOfTime;
import org.apache.spark.sql.catalyst.expressions.RuntimeReplaceable;
import org.apache.spark.sql.catalyst.expressions.Second;
import org.apache.spark.sql.catalyst.expressions.SecondsOfTime;
import org.apache.spark.sql.catalyst.expressions.SecondsOfTimeWithFraction;
import org.apache.spark.sql.catalyst.expressions.SubtractTimes;
import org.apache.spark.sql.catalyst.expressions.SubtractTimestamps;
import org.apache.spark.sql.catalyst.expressions.TimeAddInterval;
import org.apache.spark.sql.catalyst.expressions.TimeDiff;
import org.apache.spark.sql.catalyst.expressions.TimeFromMicros;
import org.apache.spark.sql.catalyst.expressions.TimeFromMillis;
import org.apache.spark.sql.catalyst.expressions.TimeFromSeconds;
import org.apache.spark.sql.catalyst.expressions.TimeToMicros;
import org.apache.spark.sql.catalyst.expressions.TimeToMillis;
import org.apache.spark.sql.catalyst.expressions.TimeToSeconds;
import org.apache.spark.sql.catalyst.expressions.TimeTrunc;
import org.apache.spark.sql.catalyst.expressions.TimestampAdd;
import org.apache.spark.sql.catalyst.expressions.TimestampAddInterval;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.objects.StaticInvoke;
import org.apache.spark.sql.catalyst.util.DateTimeConstants;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * The long-lane and TIME family of the compiler: the {@code bigint}, {@code TIME(p)} and
 * day-time interval leaves, the precision and unit casts that are the identity on the lane, and
 * the TIME functions, which reach the compiler as the {@code StaticInvoke} their
 * {@code RuntimeReplaceable} rewrote into and are matched by the method they invoke through
 * {@link #TIME_TARGETS}; and the {@code TIMESTAMP_NTZ} arithmetic that is long arithmetic, the
 * difference of two NTZ values and an NTZ value plus or minus a day-time interval.
 *
 * <p>{@link #arm} is the family's one entry from the chain {@code VarkaExpressionCompiler}
 * dispatches through, in the form {@code VarkaIntervalCompiler} set: a {@code switch} that tests
 * and deconstructs a node and returns the lowering as a deferred call, or {@code null} for a node
 * the family does not claim, so that asking is side-effect free. {@code compileRoot} calls
 * {@link #compileTime} directly for the functions whose int result only an output can take.
 *
 * <p>The literal and input tables are the classifier's {@link VarkaSlotTable}s.
 */
final class VarkaTimeCompiler {

  /**
   * Every {@code TIME} is a count of nanoseconds of day, which is the bound its divisions
   * state.
   */
  private static final long DAY_OF_NANOS = DateTimeConstants.NANOS_PER_DAY;

  /**
   * The longest interval {@code timeAddInterval} lowers, in microseconds, either way: the literal's
   * range check and the column's runtime guard are this one bound, so the two paths admit the same
   * intervals.
   */
  private static final long ONE_DAY_MICROS = DateTimeConstants.MICROS_PER_DAY;

  /**
   * The units of a second a {@code TIME} and an NTZ's time of day count in: the field helpers
   * divide either.
   */
  private static final long SECOND_OF_NANOS = DateTimeConstants.NANOS_PER_SECOND;
  private static final long MICROS_PER_SECOND = DateTimeConstants.MICROS_PER_SECOND;

  /**
   * The nanoseconds in each unit {@code time_diff} and {@code time_trunc} accept, spelled as
   * {@code DateTimeUtils.getNanosPerTimeUnit} and {@code parseTimeTruncLevel} spell them - the
   * same five names, and nothing coarser than an hour, because a {@code TIME} has no day.
   */
  static final Map<String, Long> NANOS_PER_TIME_UNIT = Map.of(
      "MICROSECOND", DateTimeConstants.NANOS_PER_MICROS,
      "MILLISECOND", DateTimeConstants.NANOS_PER_MILLIS,
      "SECOND", DateTimeConstants.NANOS_PER_SECOND,
      "MINUTE", DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE,
      "HOUR", DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE
          * DateTimeConstants.MINUTES_PER_HOUR);

  /**
   * The microseconds in each {@code timestampadd} unit this family lowers, the units Spark's
   * {@code DateTimeUtils.timestampAdd} adds as a day-time amount, spelled as it matches them after
   * upper-casing. The month units are the calendar family's ({@code VarkaChronoCompiler}).
   */
  private static final Map<String, Long> MICROS_PER_TIMESTAMPADD_UNIT = Map.of(
      "MICROSECOND", 1L,
      "MILLISECOND", DateTimeConstants.MICROS_PER_MILLIS,
      "SECOND", DateTimeConstants.MICROS_PER_SECOND,
      "MINUTE", DateTimeConstants.MICROS_PER_MINUTE,
      "HOUR", DateTimeConstants.MICROS_PER_HOUR,
      "DAY", DateTimeConstants.MICROS_PER_DAY,
      "DAYOFYEAR", DateTimeConstants.MICROS_PER_DAY,
      "WEEK", DateTimeConstants.MICROS_PER_DAY * DateTimeConstants.DAYS_PER_WEEK);

  /** The class a {@code TIME} function's replacement invokes, and the method it calls. */
  private record TimeTarget(Class<?> owner, String function) {
  }

  /**
   * Every {@code TIME} expression Spark has, keyed by the {@code StaticInvoke} it actually
   * arrives as.
   *
   * <p>None of them reaches this compiler under its own class name. All fifteen are
   * {@code RuntimeReplaceable} and rewrite themselves into a {@code StaticInvoke} on
   * {@code DateTimeUtils} before physical planning, so an arm matching {@code HoursOfTime} would
   * never fire in a real query - the optimizer's {@code ReplaceExpressions} has long since run.
   *
   * <p>The key is not written down. Each expression is constructed once here and asked for its
   * own {@code replacement}, and the owner and function name are read off that. Both sides
   * therefore move together: if upstream renames {@code getHoursOfTime}, this table renames with
   * it, where a hardcoded string would have stopped matching silently and left nothing behind but
   * a benchmark that got slower. {@code VARKA-102.md} 2.1 is the argument;
   * {@code VarkaExpressionCompilerSuite} is the check that the table still describes what Spark
   * produces for real SQL.
   *
   * <p>A replacement that stops being a {@code StaticInvoke} fails here, at class
   * initialisation, rather than disappearing from the table unnoticed.
   */
  private static final Map<TimeTarget, String> TIME_TARGETS = timeTargets();

  private VarkaTimeCompiler() {
  }

  /** Microseconds in one unit of a day-time interval's end field. */
  private static long unitMicros(byte endField) {
    if (endField == DayTimeIntervalType.DAY()) {
      return DateTimeConstants.MICROS_PER_DAY;
    }
    if (endField == DayTimeIntervalType.HOUR()) {
      return DateTimeConstants.MICROS_PER_HOUR;
    }
    if (endField == DayTimeIntervalType.MINUTE()) {
      return DateTimeConstants.MICROS_PER_MINUTE;
    }
    if (endField == DayTimeIntervalType.SECOND()) {
      return DateTimeConstants.MICROS_PER_SECOND;
    }
    throw VarkaErrors.illegalArgument("not a day-time interval field: " + endField);
  }

  /** {@code CAST(dt AS BIGINT)}; see the arm in {@link #arm}. */
  private static Optional<VarkaVectorIR> dayTimeToLong(
      Cast c,
      DayTimeIntervalType from,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    long unit = unitMicros(from.endField());
    return VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink).flatMap(x -> {
      if (x.laneType() != LaneType.LONG) {
        return sink.decline("a day-time interval operand that is not on the long lane", c);
      }
      if (unit == DateTimeConstants.MICROS_PER_DAY) {
        return Optional.of(VarkaNodeCompiler.truncDivLong(x, unit, sink));
      }
      long bound = ConstDivide.EXACT_DIVIDEND_BOUND;
      return Optional.of((VarkaVectorIR) new ConstDivide(
          new GuardedRange(x, -(bound - 1), bound - 1), unit, bound));
    });
  }

  /**
   * The arm that claims {@code e}, or {@code null}: the long-lane and TIME arms of
   * {@code compileNode}, in their original order - the leaves and casts, and the
   * {@code StaticInvoke} of a TIME function.
   */
  static VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (e) {
      // The long lane's column leaf: a `bigint`, a `TIME(p)`, a day-time interval and a
      // `TIMESTAMP_NTZ` are one eight-byte lane, holding the value, nanoseconds of day,
      // microseconds and microseconds since the epoch respectively (VARKA-29, VARKA-338). As
      // with the interval leaf, Spark's own typing decides where such a value may appear - never
      // in a date or an int position - so the leaf cannot put one there, and the IR's
      // constructors refuse a tree that mixes it with the int lane anyway.
      // A decimal column is never a long column, whatever lane the registry gives it
      // (VARKA-347): its sixteen-byte slots are read by its own leaf, with the check Spark's
      // reader makes. Under a comparison or a null test, a decimal value - the column, a
      // rescale, a negation, abs, a choice or a pick over it (VARKA-348), a sum, a difference or
      // a product (VARKA-349) - is built by VarkaDecimalCompiler, so a decimal column reaching
      // here declines, ahead of the long column's arm below, which would read the slots as
      // eight-byte longs: with the registry's reason where it has one (a precision or scale off
      // the lane), and otherwise as a value outside a
      // condition - an output, row 348's (348b), or the operand of a cast to an integral
      // or a floating type, rows 350 and 366.
      case BoundReference br when br.dataType() instanceof DecimalType d ->
          () -> sink.decline(VarkaTypes.declineReason(d).orElse("a " + d.simpleString()
              + " column as a value: its comparisons, null tests and the values they read fuse, "
              + "+, - and * among them; a decimal output is row 348's, its casts to other types "
              + "rows 350 and 366"), br);
      case BoundReference br
          when VarkaTypes.lane(br.dataType()).orElse(null) == LaneType.LONG ->
          () -> Optional.of(VarkaNodeCompiler.columnRef(br, inputs, LaneType.LONG));
      // The long lane's literals, beside the int ones: the value is already the long the lane
      // holds, so `l > 5000000000`, `t < TIME'12:00'` and `dt > INTERVAL '1' DAY` take a slot.
      case Literal l when l.value() instanceof Long
          && VarkaTypes.lane(l.dataType()).orElse(null) == LaneType.LONG ->
          () -> Optional.of(sink.longSlot((Long) l.value()));
      // TIME's precision cast. A `TIME(p)` value is stored truncated to `p` digits and
      // `Cast.castToTime` truncates again to the target precision, so a cast to an equal or wider
      // precision - the one type coercion inserts when two precisions meet in a comparison -
      // returns its operand unchanged and compiles to the child, as the year-month MONTH relabel
      // does in the interval family. Narrowing drops digits: a floor of the non-negative count to
      // a multiple of 10^(9 - p), the exact form `ntzToTime` builds for CAST(ntz AS TIME(p))
      // (VARKA-340). This cast is not built over it yet (`m8/casts.tsv` has the pair
      // unclaimed), so it declines with its reason rather than falling through as unsupported.
      case Cast c when c.dataType() instanceof TimeType to
          && c.child().dataType() instanceof TimeType from
          && to.precision() >= from.precision() ->
          () -> VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink);
      case Cast c when c.dataType() instanceof TimeType
          && c.child().dataType() instanceof TimeType ->
          () -> sink.decline("TIME narrowed to a lower precision, which truncates", c);
      // The day-time interval's unit relabel, the twin of the year-month MONTH arm: type
      // coercion casts `INTERVAL '0' SECOND` to the column's DAY TO SECOND before comparing, and
      // `castToDayTimeInterval` keeps the microseconds whole for a SECOND end field
      // (`SparkIntervalUtils.durationToMicros`), so the cast is the child. A coarser end field
      // truncates to that unit - `micros - micros % unit`, a division - and declines with its
      // reason rather than falling through as unsupported.
      case Cast c when c.dataType() instanceof DayTimeIntervalType to
          && to.endField() == DayTimeIntervalType.SECOND()
          && c.child().dataType() instanceof DayTimeIntervalType ->
          () -> VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink);
      case Cast c when c.dataType() instanceof DayTimeIntervalType
          && c.child().dataType() instanceof DayTimeIntervalType ->
          () -> sink.decline(
              "day-time interval narrowed to a coarser end field, which truncates", c);
      // `CAST(dt AS BIGINT)`: the day-time interval's whole units, `v / unit` for the end field's
      // unit over Java's truncating `/` (`IntervalUtils.dayTimeIntervalToLong`). It cannot fail in
      // any mode, so LEGACY, ANSI and TRY are one kernel. A DAY end is the exact division over
      // every long (`truncDivLong`); a coarser unit has no exact full-range form, so its dividend
      // is guarded to |v| < 2^52 microseconds (about 142 years) and a batch holding a longer
      // interval goes to the row engine, which divides it (`VARKA-332.md` 2.4).
      case Cast c when c.dataType().equals(DataTypes.LongType)
          && c.child().dataType() instanceof DayTimeIntervalType from ->
          () -> dayTimeToLong(c, from, inputs, literals, sink);
      // A TIME expression, which arrives as the StaticInvoke its RuntimeReplaceable rewrote
      // itself into (see `TIME_TARGETS`). The lowered ones are matched by the method they
      // invoke; the rest decline by name through the same table.
      case StaticInvoke si when isTimeTarget(si) ->
          () -> compileTime(si, inputs, literals, sink);
      // TIMESTAMP_NTZ arithmetic (VARKA-339). Spark reads an NTZ value at UTC, where a day is
      // always 86,400 seconds, so `ntz - ntz` is `subtractExact` of the two counts and
      // `ntz + dt` is `addExact`, in every evaluation mode: the checked long add and subtract
      // compute them, and a batch with a lane that overflows goes to the row engine, which
      // raises Spark's error on the row. The legacy CalendarInterval difference is a type no
      // kernel carries. A zoned TIMESTAMP is no lane's, so these arms ask for NTZ operands.
      case SubtractTimestamps s when VarkaTypes.isNtz(s.left().dataType())
          && VarkaTypes.isNtz(s.right().dataType()) ->
          () -> s.legacyInterval()
              ? sink.decline("legacy CalendarInterval subtraction of two timestamps", s)
              : ntzDifference(s, inputs, literals, sink);
      case TimestampAddInterval a when VarkaTypes.isNtz(a.start().dataType())
          && a.interval().dataType() instanceof DayTimeIntervalType ->
          () -> ntzPlusInterval(a, inputs, literals, sink);
      // `timestampadd` up to a week over an NTZ value: the quantity times the unit, added. The
      // month units are the calendar family's arm; every other unit, NANOSECOND and an unknown
      // one among them, is this arm's.
      case TimestampAdd n when VarkaTypes.isNtz(n.timestamp().dataType())
          && !VarkaChronoCompiler.isMonthUnit(n.unit()) ->
          () -> ntzTimestampAdd(n, inputs, literals, sink);
      // The time of day of a TIMESTAMP_NTZ (VARKA-340): the count read at UTC, so the fields are
      // the TIME lowerings over the microsecond of the day. The analyzer fills in the session
      // zone these expressions carry, and an NTZ value does not read it.
      case Hour h when VarkaTypes.isNtz(h.child().dataType()) ->
          () -> ntzMicros(h.child(), inputs, literals, sink)
              .map(v -> hourOf(v, MICROS_PER_SECOND, ONE_DAY_MICROS));
      case Minute m when VarkaTypes.isNtz(m.child().dataType()) ->
          () -> ntzMicros(m.child(), inputs, literals, sink)
              .map(v -> minuteOf(v, MICROS_PER_SECOND, ONE_DAY_MICROS, sink));
      case Second s when VarkaTypes.isNtz(s.child().dataType()) ->
          () -> ntzMicros(s.child(), inputs, literals, sink)
              .map(v -> secondOf(v, MICROS_PER_SECOND, ONE_DAY_MICROS, sink));
      case Cast c when c.dataType() instanceof TimeType to
          && VarkaTypes.isNtz(c.child().dataType()) ->
          () -> ntzToTime(c, to, inputs, literals, sink);
      default -> null;
    };
  }

  /**
   * The hour of the day of a count of the day in units of which {@code perSecond} make a second,
   * under {@code day}: one division. A {@code TIME} counts nanoseconds, an NTZ's time of day
   * microseconds.
   */
  private static VarkaVectorIR hourOf(VarkaVectorIR ofDay, long perSecond, long day) {
    return new NarrowLane(new ConstDivide(ofDay, perSecond * DateTimeConstants.SECONDS_PER_HOUR,
        day));
  }

  /** The minute of the hour: a division and the remainder of a further one by sixty. */
  private static VarkaVectorIR minuteOf(
      VarkaVectorIR ofDay, long perSecond, long day, DeclineSink sink) {
    return new NarrowLane(remainderOfSixty(
        new ConstDivide(ofDay, perSecond * DateTimeConstants.SECONDS_PER_MINUTE, day), sink));
  }

  /** The second of the minute, built as the minute is. */
  private static VarkaVectorIR secondOf(
      VarkaVectorIR ofDay, long perSecond, long day, DeclineSink sink) {
    return new NarrowLane(remainderOfSixty(new ConstDivide(ofDay, perSecond, day), sink));
  }

  /**
   * The microsecond of the day of the NTZ operand {@code ntz}. It runs the floor division by a
   * day that {@code CAST(ntz AS DATE)} runs, but as a part of this family's own node: a switch
   * covers the nodes its family claims (VARKA-318), and {@code hour(ntz)} holds no cast for the
   * int arithmetic family to claim, as {@code year(ntz)} does.
   */
  private static Optional<VarkaVectorIR> ntzMicros(
      Expression ntz,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return longOperand(ntz, inputs, literals, sink)
        .map(t -> VarkaNodeCompiler.microsOfDay(t, sink));
  }

  /**
   * {@code CAST(ntz AS TIME(p))}: Spark's {@code truncateTimeToPrecision} of the nanosecond of
   * the day, which floors a non-negative count to a multiple of {@code 10^(9 - p)}. A microsecond
   * count has no digit past the sixth, so from precision 6 up it is the nanosecond itself; below,
   * the quotient and the product by the same power of ten. It cannot fail in any mode.
   */
  private static Optional<VarkaVectorIR> ntzToTime(
      Cast c,
      TimeType to,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    // The nanosecond of the day, under 2^47, so under the bound every TIME divisor is proven for.
    Optional<VarkaVectorIR> n = ntzMicros(c.child(), inputs, literals, sink).map(v ->
        new IntArith(IntOp.MUL, Overflow.WRAP, v,
            sink.longSlot(DateTimeConstants.NANOS_PER_MICROS)));
    if (n.isEmpty() || to.precision() >= TimeType.MICROS_PRECISION()) {
      return n;
    }
    long unit = Decimal.POW_10()[TimeType.NANOS_PRECISION() - to.precision()];
    return n.map(v -> new IntArith(IntOp.MUL, Overflow.WRAP,
        new ConstDivide(v, unit, DAY_OF_NANOS), sink.longSlot(unit)));
  }

  /**
   * {@code end - start} over two NTZ values: Spark's {@code subtractTimestamps} at UTC, which is
   * {@code ChronoUnit.MICROS.between} of the two wall clocks and equals
   * {@code Math.subtractExact(end, start)}, raising exactly where it does
   * ({@code VARKA-339.md} 0, item 1). The result is a day-time interval in microseconds.
   */
  private static Optional<VarkaVectorIR> ntzDifference(
      SubtractTimestamps s,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> end = longOperand(s.left(), inputs, literals, sink);
    if (end.isEmpty()) {
      return end;
    }
    return longOperand(s.right(), inputs, literals, sink)
        .map(start -> new IntArith(IntOp.SUB, Overflow.FAIL, end.get(), start));
  }

  /**
   * {@code t + dt} over an NTZ value and a day-time interval: Spark's {@code timestampAddDayTime}
   * at UTC, which is {@code Math.addExact(t, dt)} ({@code VARKA-339.md} 0, item 1).
   *
   * <p>{@code t - dt} reaches here as {@code t + UnaryMinus(dt)}, and the negation is absorbed
   * into a checked subtraction, as {@code date_sub} absorbs its own into {@code SubDays}. Spark
   * negates an ANSI interval with {@code negateExact} in every mode, so the one interval it
   * cannot negate, {@code Long.MIN_VALUE}, raises: the guard keeps the interval inside
   * {@code negateExact}'s domain, and inside it {@code t - dt} overflows exactly where
   * {@code t + (-dt)} does. A literal interval arrives already negated, a literal the plain sum
   * takes: the optimizer's {@code ConstantFolding} folds {@code UnaryMinus} over a literal. It
   * raises at planning on the one literal it cannot negate, except in a conditional branch, where
   * it leaves the negation for this guard to send each batch the branch reads to the row engine.
   */
  private static Optional<VarkaVectorIR> ntzPlusInterval(
      TimestampAddInterval a,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> t = longOperand(a.start(), inputs, literals, sink);
    if (t.isEmpty()) {
      return t;
    }
    if (a.interval() instanceof UnaryMinus n) {
      return longOperand(n.child(), inputs, literals, sink).map(dt -> new IntArith(IntOp.SUB,
          Overflow.FAIL, t.get(), new GuardedRange(dt, Long.MIN_VALUE + 1, Long.MAX_VALUE)));
    }
    return longOperand(a.interval(), inputs, literals, sink)
        .map(dt -> new IntArith(IntOp.ADD, Overflow.FAIL, t.get(), dt));
  }

  /**
   * {@code timestampadd(unit, q, t)} over an NTZ value for a unit up to a week
   * ({@code VARKA-339.md} 2.2): Spark's {@code DateTimeUtils.timestampAdd} at UTC, which adds
   * {@code q} times the unit's {@code f} microseconds and raises {@code DATETIME_OVERFLOW} where
   * the product or the sum leaves a long, in every mode. So it is {@code t + q * f}, the sum
   * checked and the product exact under a guard on {@code q} of {@code +-floor(Long.MAX_VALUE /
   * f)}. For MILLISECOND to HOUR the guard is {@code multiplyExact}'s domain exactly (no
   * {@code f} is a power of two, so the domain is symmetric). For DAY and WEEK it is the product's:
   * Spark also narrows {@code q} to an int, and for WEEK multiplies by seven in int, but every
   * {@code q} those raise on is past the guard, whose batch goes to the row engine, which answers
   * or raises. An int quantity, widened, is inside every bound from an hour down, so it takes no
   * guard; a literal one folds to its product, or declines when it is past the bound.
   */
  private static Optional<VarkaVectorIR> ntzTimestampAdd(
      TimestampAdd n,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Long f = MICROS_PER_TIMESTAMPADD_UNIT.get(n.unit().toUpperCase(Locale.ROOT));
    if (f == null) {
      return sink.decline("timestampadd by " + n.unit() + ", not a unit of a microsecond "
          + "timestamp", n);
    }
    long bound = Long.MAX_VALUE / f;
    Optional<VarkaVectorIR> amount;
    if (n.quantity().foldable()) {
      Optional<Long> folded = foldedQuantity(n.quantity());
      if (folded.isEmpty()) {
        return sink.decline("timestampadd's quantity folds to null or raises", n);
      }
      long q = folded.get();
      amount = q < -bound || q > bound
          ? sink.decline("timestampadd's literal quantity is past the range whose product by "
              + "the unit is a long", n)
          : Optional.of(sink.longSlot(q * f));
    } else {
      boolean widenedInt = n.quantity() instanceof Cast c
          && DataTypes.IntegerType.equals(c.child().dataType());
      amount = longOperand(n.quantity(), inputs, literals, sink).map(q -> {
        if (f == 1) {
          return q;
        }
        VarkaVectorIR guarded = widenedInt && bound >= -(long) Integer.MIN_VALUE ? q
            : new GuardedRange(q, -bound, bound);
        return new IntArith(IntOp.MUL, Overflow.WRAP, guarded, sink.longSlot(f));
      });
    }
    if (amount.isEmpty()) {
      return amount;
    }
    return longOperand(n.timestamp(), inputs, literals, sink)
        .map(t -> new IntArith(IntOp.ADD, Overflow.FAIL, t, amount.get()));
  }

  /**
   * A foldable {@code timestampadd} quantity's value, or empty where it is null or its
   * evaluation raises, which the row engine then raises on every row.
   */
  static Optional<Long> foldedQuantity(Expression q) {
    try {
      return q.eval(null) instanceof Long v ? Optional.of(v) : Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Whether {@code si} is the replacement of one of Spark's TIME expressions. */
  static boolean isTimeTarget(StaticInvoke si) {
    return TIME_TARGETS.containsKey(new TimeTarget(si.staticObject(), si.functionName()));
  }

  /** The number of TIME expressions the table holds, one key each. */
  static int timeTargetCount() {
    return TIME_TARGETS.size();
  }

  private static Map<TimeTarget, String> timeTargets() {
    Literal t = Literal.create(0L, new TimeType(TimeType.MICROS_PRECISION()));
    Literal i = Literal.create(0, DataTypes.IntegerType);
    Literal d = Literal.create(Decimal.apply(0), new DecimalType(16, 6));
    Literal dt = Literal.create(0L, DayTimeIntervalType.apply());
    Literal u = Literal.create(UTF8String.fromString("HOUR"), DataTypes.StringType);
    Literal l = Literal.create(0L, DataTypes.LongType);
    Map<Expression, String> expressions = new java.util.LinkedHashMap<>();
    expressions.put(new HoursOfTime(t), "hour(t)");
    expressions.put(new MinutesOfTime(t), "minute(t)");
    expressions.put(new SecondsOfTime(t), "second(t)");
    expressions.put(new SecondsOfTimeWithFraction(t), "second(t) with its fraction");
    expressions.put(new MakeTime(i, i, d), "make_time");
    expressions.put(new TimeTrunc(u, t), "time_trunc");
    expressions.put(new SubtractTimes(t, t), "t1 - t2");
    expressions.put(new TimeDiff(u, t, t), "timediff");
    expressions.put(new TimeAddInterval(t, dt), "t + interval");
    // Group E (VARKA-158): the conversions, and the one of them that returns a decimal.
    expressions.put(new TimeToSeconds(t), "time_to_seconds");
    expressions.put(new TimeToMillis(t), "time_to_millis");
    expressions.put(new TimeToMicros(t), "time_to_micros");
    expressions.put(new TimeFromSeconds(l), "time_from_seconds");
    expressions.put(new TimeFromMillis(l), "time_from_millis");
    expressions.put(new TimeFromMicros(l), "time_from_micros");
    Map<TimeTarget, String> targets = new HashMap<>();
    for (Map.Entry<Expression, String> entry : expressions.entrySet()) {
      String label = entry.getValue();
      Expression replacement = ((RuntimeReplaceable) entry.getKey()).replacement();
      if (replacement instanceof StaticInvoke si) {
        targets.put(new TimeTarget(si.staticObject(), si.functionName()), label);
      } else {
        throw VarkaErrors.illegalState(
            label + " no longer replaces into a StaticInvoke but into "
                + replacement.getClass().getSimpleName()
                + "; VarkaTimeCompiler.TIME_TARGETS must be "
                + "rewritten rather than quietly stop matching");
      }
    }
    return Map.copyOf(targets);
  }

  /**
   * The reason a {@code TIME} expression declines, naming the expression rather than reporting it
   * as unsupported.
   *
   * <p>VARKA-102 lowers these one group at a time, and the difference between "not lowered yet"
   * and "unsupported" is what tells a reader which. The same distinction VARKA-89 drew for
   * {@code extract(MONTH FROM ym)}, where a bare decline would have suggested the division was
   * still missing when the output type was the blocker.
   */
  private static String timeNotLoweredYet(String label) {
    return label + " is a TIME expression Varka does not lower yet (VARKA-102)";
  }

  /**
   * The reason the two decimal-valued {@code TIME} expressions decline below a root: at an output
   * root they fuse through the decimal store ({@link #decimalProducer}), and as the operand of
   * another expression their value is a decimal operand, which row 348 consumes
   * (`VARKA-157.md` 2.1).
   */
  private static String decimalOperandNotYet(String label) {
    return label + " returns a decimal, which fuses as a projection's output; as the operand of "
        + "another expression its value is row 348's";
  }

  /** The two {@code TIME} functions whose value is a decimal (`VARKA-157.md` 2.3). */
  private static final Set<String> DECIMAL_PRODUCERS =
      Set.of("timeToSeconds", "getSecondsOfTimeWithFraction");

  /** Whether {@code si} is one of the two decimal-valued {@code TIME} functions. */
  static boolean isDecimalProducer(StaticInvoke si) {
    return DECIMAL_PRODUCERS.contains(si.functionName())
        && TIME_TARGETS.containsKey(new TimeTarget(si.staticObject(), si.functionName()));
  }

  /**
   * The unscaled value of a decimal-valued {@code TIME} function at an output root, or empty with
   * the reason noted (`VARKA-157.md` 2.3). Both are non-negative and within their precision for
   * every time of day, so neither needs a guard, and every divisor is proven in
   * {@code long_divide.smt2}.
   *
   * <ul>
   *   <li>{@code time_to_seconds(t)}, a {@code DECIMAL(14, 6)}: {@code (n + 500) / 1000}, Spark's
   *     exact quotient by {@code 10^9} rounded half up to six digits; the dividend is under
   *     {@code NANOS_PER_DAY + 500}.
   *   <li>{@code extract(SECOND FROM t)} over {@code TIME(p)}, a {@code DECIMAL(2 + p, p)}:
   *     {@code s * 10^p + g}, with {@code s} the second of the minute, {@code r = n - q * 10^9}
   *     sharing {@code s}'s {@code q = n / 10^9}, and {@code g = r / 10^(9 - p)}; at
   *     {@code p = 0} it is {@code s} and at {@code p = 9} it is {@code s * 10^9 + r}. Spark
   *     computes {@code s + g / 10^p} as a {@code Double} and rounds it back to {@code p} digits;
   *     that the two agree on every {@code (s, g)} is {@code VarkaTimeFractionSuite}'s sweep.
   * </ul>
   */
  static Optional<VarkaDecimalCompiler.Scaled> decimalProducer(StaticInvoke si,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    List<Expression> args = CollectionConverters.asJava(si.arguments());
    DecimalType type = (DecimalType) si.dataType();
    Optional<VarkaVectorIR> t = longOperand(args.get(0), inputs, literals, sink);
    if (t.isEmpty()) {
      return Optional.empty();
    }
    VarkaVectorIR n = t.get();
    if (si.functionName().equals("timeToSeconds")) {
      long half = DateTimeConstants.NANOS_PER_MICROS / 2;
      return Optional.of(new VarkaDecimalCompiler.Scaled(new ConstDivide(
          new IntArith(IntOp.ADD, Overflow.WRAP, n, sink.longSlot(half)),
          DateTimeConstants.NANOS_PER_MICROS, DAY_OF_NANOS + half), type));
    }
    if (args.size() != 2 || !(args.get(1) instanceof Literal l)
        || !(l.value() instanceof Integer p) || p < 0 || p > 9 || type.scale() != p) {
      return sink.decline(timeNotLoweredYet(TIME_TARGETS.get(
          new TimeTarget(si.staticObject(), si.functionName()))), si);
    }
    long second = DateTimeConstants.NANOS_PER_SECOND;
    ConstDivide q = new ConstDivide(n, second, DAY_OF_NANOS);
    VarkaVectorIR s = remainderOfSixty(q, sink);
    if (p == 0) {
      return Optional.of(new VarkaDecimalCompiler.Scaled(s, type));
    }
    VarkaVectorIR r = new IntArith(IntOp.SUB, Overflow.WRAP, n,
        new IntArith(IntOp.MUL, Overflow.WRAP, q, sink.longSlot(second)));
    VarkaVectorIR g = p == 9 ? r : new ConstDivide(r, Decimal.POW_10()[9 - p], second);
    return Optional.of(new VarkaDecimalCompiler.Scaled(new IntArith(IntOp.ADD, Overflow.WRAP,
        new IntArith(IntOp.MUL, Overflow.WRAP, s, sink.longSlot(Decimal.POW_10()[p])), g),
        type));
  }

  /** The lowering of a TIME function's replacement. */
  static Optional<VarkaVectorIR> compileTime(
      StaticInvoke si,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    // A node outside the table fails here, as the Scala `timeTargets(key)` did, rather than
    // reaching a decline text as "null".
    String label = Objects.requireNonNull(
        TIME_TARGETS.get(new TimeTarget(si.staticObject(), si.functionName())),
        () -> si.staticObject().getName() + "." + si.functionName() + " is not a TIME target");
    List<Expression> args = CollectionConverters.asJava(si.arguments());
    switch (si.functionName()) {
      case "subtractTimes" -> {
        if (args.size() == 2) {
          Optional<VarkaVectorIR> end = longOperand(args.get(0), inputs, literals, sink);
          if (end.isEmpty()) {
            return end;
          }
          Optional<VarkaVectorIR> start = longOperand(args.get(1), inputs, literals, sink);
          if (start.isEmpty()) {
            return start;
          }
          return Optional.of(new ConstDivide(
              new IntArith(IntOp.SUB, Overflow.WRAP, end.get(), start.get()),
              DateTimeConstants.NANOS_PER_MICROS, DAY_OF_NANOS));
        }
      }
      case "timeDiff" -> {
        if (args.size() == 3) {
          OptionalLong nanos = literalUnit(
              args.get(0), NANOS_PER_TIME_UNIT, "unit", label, si, sink);
          if (nanos.isEmpty()) {
            return Optional.empty();
          }
          Optional<VarkaVectorIR> end = longOperand(args.get(2), inputs, literals, sink);
          if (end.isEmpty()) {
            return end;
          }
          Optional<VarkaVectorIR> start = longOperand(args.get(1), inputs, literals, sink);
          if (start.isEmpty()) {
            return start;
          }
          return Optional.of(new ConstDivide(
              new IntArith(IntOp.SUB, Overflow.WRAP, end.get(), start.get()),
              nanos.getAsLong(), DAY_OF_NANOS));
        }
      }
      case "timeTrunc" -> {
        if (args.size() == 2) {
          OptionalLong unit = literalUnit(
              args.get(0), NANOS_PER_TIME_UNIT, "level", label, si, sink);
          if (unit.isEmpty()) {
            return Optional.empty();
          }
          Optional<VarkaVectorIR> t = longOperand(args.get(1), inputs, literals, sink);
          if (t.isEmpty()) {
            return t;
          }
          return Optional.of(new IntArith(IntOp.MUL, Overflow.WRAP,
              new ConstDivide(t.get(), unit.getAsLong(), DAY_OF_NANOS),
              sink.longSlot(unit.getAsLong())));
        }
      }
      // The three field extracts (group C): hour is one division of the nanoseconds of day,
      // minute and second a division and the remainder of a further division by sixty, each
      // built the way `DateTimeUtils` computes it through `LocalTime` and delivered as an int by
      // a narrowing - at an output root the value stays in the long lane and narrows at the
      // store (`VARKA-102.md` 8.3), and under another node it narrows where it is (VARKA-28).
      // Every dividend is nanoseconds of day or a quotient of it, under the type's bound and so
      // under `ConstDivide.EXACT_DIVIDEND_BOUND` structurally, and every result is under 86400,
      // so nothing overflows and nothing is guarded.
      case "getHoursOfTime" -> {
        if (args.size() == 1) {
          return longOperand(args.get(0), inputs, literals, sink)
              .map(v -> hourOf(v, SECOND_OF_NANOS, DAY_OF_NANOS));
        }
      }
      case "getMinutesOfTime" -> {
        if (args.size() == 1) {
          return longOperand(args.get(0), inputs, literals, sink)
              .map(v -> minuteOf(v, SECOND_OF_NANOS, DAY_OF_NANOS, sink));
        }
      }
      case "getSecondsOfTime" -> {
        if (args.size() == 1) {
          return longOperand(args.get(0), inputs, literals, sink)
              .map(v -> secondOf(v, SECOND_OF_NANOS, DAY_OF_NANOS, sink));
        }
      }
      // timeAddInterval(t, p, dt, endField, target): addExact(t, multiplyExact(dt, 1000)),
      // thrown out of if the sum leaves [0, NANOS_PER_DAY), then truncated to `target` digits.
      // Two guards make the lane's wrapping arithmetic exact and the throw a decline. The
      // interval is held to one day either way first: any |dt| beyond that puts every t's sum
      // outside the day, so Spark throws on every such row and the row engine may as well
      // raise it; inside it, dt * 1000 and the sum both stay under 2^48 and cannot overflow.
      // The sum is then held to the day, which is the throw itself. The precision truncation
      // is the identity and is not emitted - see `timeAddIntervalTruncates`, which proves it.
      case "timeAddInterval" -> {
        if (args.size() == 5
            && args.get(1) instanceof Literal precision
            && precision.dataType().equals(DataTypes.IntegerType)
            && args.get(3) instanceof Literal endField
            && endField.dataType().equals(DataTypes.ByteType)
            && args.get(4) instanceof Literal targetLiteral
            && targetLiteral.value() instanceof Integer target
            && targetLiteral.dataType().equals(DataTypes.IntegerType)) {
          return timeAddInterval(
              si, label, args.get(0), args.get(2), target, inputs, literals, sink);
        }
      }
      // Group E (VARKA-158): the conversions. `timeToMillis` and `timeToMicros` are floor
      // divisions of a non-negative count, so a truncating constant division; the three
      // `timeFrom*` are `multiplyExact` under the conversion's own range check, which throws
      // unless the result is inside the day - so the count is guarded to the day's worth of its
      // unit, where the product cannot overflow and the result is a TIME, and a count outside
      // declines the batch to the row engine, which raises Spark's error. A count that is not on
      // the long lane - an int column, a decimal, a double - declines where its leaf does.
      case "timeToMillis" -> {
        if (args.size() == 1) {
          return timeToUnit(
              args.get(0), DateTimeConstants.NANOS_PER_MILLIS, inputs, literals, sink);
        }
      }
      case "timeToMicros" -> {
        if (args.size() == 1) {
          return timeToUnit(
              args.get(0), DateTimeConstants.NANOS_PER_MICROS, inputs, literals, sink);
        }
      }
      case "timeFromSeconds" -> {
        if (args.size() == 1) {
          return timeFromUnits(
              args.get(0), DateTimeConstants.NANOS_PER_SECOND, inputs, literals, sink);
        }
      }
      case "timeFromMillis" -> {
        if (args.size() == 1) {
          return timeFromUnits(
              args.get(0), DateTimeConstants.NANOS_PER_MILLIS, inputs, literals, sink);
        }
      }
      case "timeFromMicros" -> {
        if (args.size() == 1) {
          return timeFromUnits(
              args.get(0), DateTimeConstants.NANOS_PER_MICROS, inputs, literals, sink);
        }
      }
      case "getSecondsOfTimeWithFraction", "timeToSeconds" ->
          { return sink.decline(decimalOperandNotYet(label), si); }
      default -> { }
    }
    // A function of the table whose arguments are not the shape its arm takes, or one with no
    // arm yet: declined by name, never reported as unsupported.
    return sink.decline(timeNotLoweredYet(label), si);
  }

  /**
   * {@code timeAddInterval}'s lowering, after its argument shape matched. A literal interval's
   * guard is decided here rather than per lane: one outside a day crosses midnight for every
   * time, which is the row engine's error to raise for every row, and one inside it is already
   * nanoseconds the kernel can add. A column takes the guard and the multiply. The time is
   * compiled first, so the inputs keep the expression's argument order, as every other lowering's
   * do.
   */
  private static Optional<VarkaVectorIR> timeAddInterval(
      StaticInvoke si,
      String label,
      Expression time,
      Expression interval,
      int target,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (timeAddIntervalTruncates(time.dataType(), interval.dataType(), target)) {
      // Not reachable for any type Spark admits today; a decline rather than a wrong
      // answer if that ever changes.
      return sink.decline(label + ": the precision truncation is not the identity for these types",
          si);
    }
    Optional<VarkaVectorIR> t = longOperand(time, inputs, literals, sink);
    if (t.isEmpty()) {
      return t;
    }
    Optional<VarkaVectorIR> nanos;
    if (interval instanceof Literal literal && literal.value() instanceof Long micros
        && literal.dataType() instanceof DayTimeIntervalType) {
      // A range and not `Math.abs(micros) > day`: the absolute value of Long.MIN_VALUE is
      // negative, so that guard let the one interval whose multiply by 1000 wraps to zero
      // through to a kernel that then added nothing, where Spark's multiplyExact throws.
      if (micros < -ONE_DAY_MICROS || micros > ONE_DAY_MICROS) {
        nanos = sink.decline(label + ": the interval is longer than a day, so every time crosses "
            + "midnight and the row engine raises the error", si);
      } else {
        nanos = Optional.of(sink.longSlot(micros * DateTimeConstants.NANOS_PER_MICROS));
      }
    } else {
      Optional<VarkaVectorIR> dt = longOperand(interval, inputs, literals, sink);
      if (dt.isEmpty()) {
        nanos = dt;
      } else {
        var guarded = new GuardedRange(dt.get(), -ONE_DAY_MICROS, ONE_DAY_MICROS);
        nanos = Optional.of(new IntArith(IntOp.MUL, Overflow.WRAP, guarded,
            sink.longSlot(DateTimeConstants.NANOS_PER_MICROS)));
      }
    }
    if (nanos.isEmpty()) {
      return nanos;
    }
    return Optional.of(new GuardedRange(
        new IntArith(IntOp.ADD, Overflow.WRAP, t.get(), nanos.get()), 0L,
        DateTimeConstants.NANOS_PER_DAY - 1));
  }

  /**
   * The operand on the long lane. The arguments are TIME and interval columns, literals and their
   * widening casts, all of which the leaf arms above put on the long lane; anything else declined
   * already, and one on another lane is dropped here without a note of its own.
   */
  private static Optional<VarkaVectorIR> longOperand(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> compiled = VarkaNodeCompiler.compileNode(e, inputs, literals, sink);
    if (compiled.isPresent() && compiled.get().laneType() != LaneType.LONG) {
      return Optional.empty();
    }
    return compiled;
  }

  /** The nanoseconds of the unit a literal names, or empty after noting why it cannot. */
  private static OptionalLong literalUnit(
      Expression e,
      Map<String, Long> table,
      String what,
      String label,
      StaticInvoke si,
      DeclineSink sink) {
    if (e instanceof Literal literal && literal.value() instanceof UTF8String u) {
      Long found = table.get(u.toString().toUpperCase(Locale.ROOT));
      if (found == null) {
        sink.note(label + ": unknown " + what + " '" + u + "'", si);
        return OptionalLong.empty();
      }
      return OptionalLong.of(found);
    }
    sink.note(label + ": the " + what + " is not a literal, and the divisor is part of the "
        + "kernel's shape", e);
    return OptionalLong.empty();
  }

  /** {@code time_to_millis} and {@code time_to_micros}: a truncating division of the TIME. */
  private static Optional<VarkaVectorIR> timeToUnit(
      Expression time,
      long nanosPerUnit,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> t = longOperand(time, inputs, literals, sink);
    if (t.isEmpty()) {
      return t;
    }
    return Optional.of(new ConstDivide(t.get(), nanosPerUnit, DAY_OF_NANOS));
  }

  /**
   * {@code count * nanosPerUnit} as a TIME: the count guarded to
   * {@code [0, (NANOS_PER_DAY - 1) / unit]}, inside which the wrapping multiply is exact and the
   * result is inside the day, so the guard is the conversion's own range check and its decline is
   * the row engine's error.
   */
  private static Optional<VarkaVectorIR> timeFromUnits(
      Expression count,
      long nanosPerUnit,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> n = longOperand(count, inputs, literals, sink);
    if (n.isEmpty()) {
      return n;
    }
    var guarded = new GuardedRange(
        n.get(), 0L, (DateTimeConstants.NANOS_PER_DAY - 1) / nanosPerUnit);
    return Optional.of(new IntArith(
        IntOp.MUL, Overflow.WRAP, guarded, sink.longSlot(nanosPerUnit)));
  }

  /**
   * Whether {@code truncateTimeToPrecision(sum, target)} inside {@code timeAddInterval} can change
   * the sum, which decides whether the lowering must emit it. It cannot, for every input type
   * Spark admits, and this is the argument the kernel rests on rather than a re-derivation per
   * call: the time is a multiple of 10^(9 - p) by its type, and the interval in nanoseconds is a
   * multiple of 10^3 - or of a whole minute when its end field is coarser than SECOND. The
   * target is max(p, 6) in the first case and p in the second, and in both the sum is a
   * multiple of 10^(9 - target), which is exactly what the truncation removes nothing from.
   * Kept as a function so the claim is checked against the types at compile time and a
   * future TimeType or interval that breaks the argument refuses to lower rather than lowering
   * wrongly.
   */
  static boolean timeAddIntervalTruncates(DataType time, DataType interval, int target) {
    int p = ((TimeType) time).precision();
    byte endField = ((DayTimeIntervalType) interval).endField();
    int sumGranularity = endField < DayTimeIntervalType.SECOND()
        // Whole minutes at least: 6e10 nanoseconds, which every 10^(9 - p) divides.
        ? Math.min(9 - p, 10)
        // Microseconds: 10^3 nanoseconds.
        : Math.min(9 - p, 3);
    // The truncation is the identity iff the sum's granularity is at least the target's.
    return sumGranularity < 9 - target;
  }

  /**
   * {@code x % 60} for a non-negative long-lane {@code x}, as {@code x - (x / 60) * 60}: the IR
   * has no remainder node, and the subtraction cannot go wrong for a count that is a quotient of
   * the day.
   */
  private static VarkaVectorIR remainderOfSixty(ConstDivide x, DeclineSink sink) {
    return new IntArith(IntOp.SUB, Overflow.WRAP, x,
        new IntArith(IntOp.MUL, Overflow.WRAP,
            new ConstDivide(x, DateTimeConstants.SECONDS_PER_MINUTE, x.quotientBound()),
            sink.longSlot(DateTimeConstants.SECONDS_PER_MINUTE)));
  }
}
