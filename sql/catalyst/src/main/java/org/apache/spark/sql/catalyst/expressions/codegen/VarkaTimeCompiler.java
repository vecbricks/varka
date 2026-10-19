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

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.HoursOfTime;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.MakeTime;
import org.apache.spark.sql.catalyst.expressions.MinutesOfTime;
import org.apache.spark.sql.catalyst.expressions.RuntimeReplaceable;
import org.apache.spark.sql.catalyst.expressions.SecondsOfTime;
import org.apache.spark.sql.catalyst.expressions.SecondsOfTimeWithFraction;
import org.apache.spark.sql.catalyst.expressions.SubtractTimes;
import org.apache.spark.sql.catalyst.expressions.TimeAddInterval;
import org.apache.spark.sql.catalyst.expressions.TimeDiff;
import org.apache.spark.sql.catalyst.expressions.TimeFromMicros;
import org.apache.spark.sql.catalyst.expressions.TimeFromMillis;
import org.apache.spark.sql.catalyst.expressions.TimeFromSeconds;
import org.apache.spark.sql.catalyst.expressions.TimeToMicros;
import org.apache.spark.sql.catalyst.expressions.TimeToMillis;
import org.apache.spark.sql.catalyst.expressions.TimeToSeconds;
import org.apache.spark.sql.catalyst.expressions.TimeTrunc;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
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
 * {@link #TIME_TARGETS}.
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


  private static final String TIMESTAMP_OUT_OF_MILESTONE =
      "a timestamp column is outside milestone 5";

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
   * The nanoseconds in each unit {@code time_diff} and {@code time_trunc} accept, spelled as
   * {@code DateTimeUtils.getNanosPerTimeUnit} and {@code parseTimeTruncLevel} spell them - the
   * same five names, and nothing coarser than an hour, because a {@code TIME} has no day.
   */
  private static final Map<String, Long> NANOS_PER_TIME_UNIT = Map.of(
      "MICROSECOND", DateTimeConstants.NANOS_PER_MICROS,
      "MILLISECOND", DateTimeConstants.NANOS_PER_MILLIS,
      "SECOND", DateTimeConstants.NANOS_PER_SECOND,
      "MINUTE", DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE,
      "HOUR", DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE
          * DateTimeConstants.MINUTES_PER_HOUR);

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
      // The long lane's column leaf: a `bigint`, a `TIME(p)` and a day-time interval are one
      // eight-byte lane, holding the value, nanoseconds of day and microseconds respectively (task
      // 29). As with the interval leaf, Spark's own typing decides where such a value may
      // appear - never in a date or an int position - so the leaf cannot put one there, and the
      // IR's constructors refuse a tree that mixes it with the int lane anyway.
      case BoundReference br when VarkaNodeCompiler.onLongLane(br.dataType()) ->
          () -> Optional.of(VarkaNodeCompiler.columnRef(br, inputs, LaneType.LONG));
      // Ahead of the generic "non-date column" decline, so the reason is the decision.
      case BoundReference br when isTimestamp(br.dataType()) ->
          () -> sink.decline(TIMESTAMP_OUT_OF_MILESTONE, br);
      // The long lane's literals, beside the int ones: the value is already the long the lane
      // holds, so `l > 5000000000`, `t < TIME'12:00'` and `dt > INTERVAL '1' DAY` take a slot.
      case Literal l when l.value() instanceof Long
          && VarkaNodeCompiler.onLongLane(l.dataType()) ->
          () -> Optional.of(sink.longSlot((Long) l.value()));
      // TIME's precision cast. A `TIME(p)` value is stored truncated to `p` digits and
      // `Cast.castToTime` truncates again to the target precision, so a cast to an equal or wider
      // precision - the one type coercion inserts when two precisions meet in a comparison -
      // returns its operand unchanged and compiles to the child, as the year-month MONTH relabel
      // does in the interval family. Narrowing drops digits, which is a floor division the lane
      // has no exact form of yet (VARKA-88); it declines with its reason rather than falling
      // through as unsupported.
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
      // A TIME expression, which arrives as the StaticInvoke its RuntimeReplaceable rewrote
      // itself into (see `TIME_TARGETS`). The lowered ones are matched by the method they
      // invoke; the rest decline by name through the same table.
      case StaticInvoke si when isTimeTarget(si) ->
          () -> compileTime(si, inputs, literals, sink);
      default -> null;
    };
  }

  /** Whether {@code si} is the replacement of one of Spark's TIME expressions. */
  static boolean isTimeTarget(StaticInvoke si) {
    return TIME_TARGETS.containsKey(new TimeTarget(si.staticObject(), si.functionName()));
  }

  /** The number of TIME expressions the table holds, one key each. */
  static int timeTargetCount() {
    return TIME_TARGETS.size();
  }

  /**
   * Whether the type is one of the two timestamps, which milestone 5 leaves out by decision
   * ({@code m9/SCOPE.md} item 31): a zoned {@code TIMESTAMP}'s differences and interval additions
   * are computed on local date-times in the session zone and are not lane arithmetic, and the
   * NTZ family, whose arithmetic would be plain, waits with it. The decline names the milestone
   * so EXPLAIN shows a decision rather than a gap.
   */
  private static boolean isTimestamp(DataType dataType) {
    return dataType.equals(DataTypes.TimestampType) || dataType.equals(DataTypes.TimestampNTZType);
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
   * The reason the two decimal-valued {@code TIME} expressions decline, which is the
   * representation and not the arithmetic: their value is an unscaled long the lane already
   * computes, and the column Arrow holds it in is sixteen bytes a row, which no Varka output
   * writes yet ({@code VARKA-102.md} section 9, VARKA-157).
   */
  private static String decimalColumnNotYet(String label) {
    return label + " returns a decimal, whose Arrow column is sixteen bytes a row and which no "
        + "Varka output writes yet; the value is an unscaled long the lane holds (VARKA-157)";
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
          Optional<VarkaVectorIR> t = longOperand(args.get(0), inputs, literals, sink);
          return t.map(v -> new NarrowLane(
              new ConstDivide(v, NANOS_PER_TIME_UNIT.get("HOUR"), DAY_OF_NANOS)));
        }
      }
      case "getMinutesOfTime" -> {
        if (args.size() == 1) {
          Optional<VarkaVectorIR> t = longOperand(args.get(0), inputs, literals, sink);
          return t.map(v -> new NarrowLane(remainderOfSixty(
              new ConstDivide(v, NANOS_PER_TIME_UNIT.get("MINUTE"), DAY_OF_NANOS), sink)));
        }
      }
      case "getSecondsOfTime" -> {
        if (args.size() == 1) {
          Optional<VarkaVectorIR> t = longOperand(args.get(0), inputs, literals, sink);
          return t.map(v -> new NarrowLane(remainderOfSixty(
              new ConstDivide(v, NANOS_PER_TIME_UNIT.get("SECOND"), DAY_OF_NANOS), sink)));
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
          { return sink.decline(decimalColumnNotYet(label), si); }
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
