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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.MakeTimestampNTZ;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecline;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser$;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.LongBatch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.SparkAnswers;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.catalyst.util.DateTimeConstants;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.TimeType;

/**
 * The casts between {@code DATE} and {@code TIMESTAMP_NTZ} at their edges (VARKA-338,
 * `VARKA-338.md` 2.3 and 3.3). {@code CAST(d AS TIMESTAMP_NTZ)} is a guarded wrapping multiply:
 * its lowering is {@code Math.multiplyExact} over every int day, which is what Spark computes in
 * every mode, and a batch with a day past the guard declines in LEGACY, ANSI and TRY alike, so
 * that the row engine raises or nulls on the row. {@code CAST(ntz AS DATE)} is the floor division
 * of {@code long_divide.smt2}, run here at the long range's extremes and at the day's boundaries,
 * against Spark and against {@code java.time}. The fields of an NTZ value (VARKA-340) are tested
 * here too, at the same widths: the calendar fields over the guarded date, the weekday forms and
 * the time of day over every long. And {@code make_timestamp_ntz(date, time)} with the cast of a
 * {@code TIME} to {@code TIMESTAMP_NTZ} it serves, at the ends of the days whose midnight is a
 * long.
 *
 * <p>In its own JVM: each cast runs a kernel of both lanes, whose int species is the long species'
 * width beside the int lane's own.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaTimestampNtzCastSuite {

  private static final long DAY = DateTimeConstants.MICROS_PER_DAY;

  /** The last day whose midnight is a long count of microseconds. */
  private static final long LAST_DAY = Long.MAX_VALUE / DAY;

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  private final Attribute d = VarkaSqlResolve.column("d", DataTypes.DateType);
  private final Attribute ntz = VarkaSqlResolve.column("ntz", DataTypes.TimestampNTZType);
  private final Attribute tm = VarkaSqlResolve.column("tm", new TimeType(9));
  private final List<Attribute> columns = List.of(d, ntz, tm);

  /** {@code sql} resolved over the two columns, ANSI on or off. */
  private Expression resolve(String sql, boolean ansi) {
    SQLConf conf = SQLConf.get().clone();
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), Boolean.toString(ansi));
    var cols = CollectionConverters.asScala(columns).toSeq();
    return SQLConf$.MODULE$.withExistingConf(conf, () -> VarkaSqlResolve.parse(sql, cols));
  }

  private Optional<CompiledVarkaProjection> compile(Expression e) {
    return VarkaExpressionCompiler.compile(
        CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq(),
        CollectionConverters.asScala(columns).toSeq(), VarkaMatrixRuns.BASE);
  }

  /** The three spellings of a cast, one per evaluation mode, resolved. */
  private List<Expression> modes(String operand, String target) {
    return List.of(resolve("CAST(" + operand + " AS " + target + ")", false),
        resolve("CAST(" + operand + " AS " + target + ")", true),
        resolve("try_cast(" + operand + " AS " + target + ")", true));
  }

  // -------------------------------------------------------------------------------------------
  // DATE to TIMESTAMP_NTZ
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the guard of DATE to NTZ admits exactly the days multiplyExact converts")
  void dateToNtzExhausted() {
    for (Expression cast : modes("d", "TIMESTAMP_NTZ")) {
      CompiledVarkaProjection plan = compile(cast).orElseThrow(() ->
          new AssertionError(cast.sql() + " declines"));
      // The tree the compiler builds, read rather than restated: the multiply, its mode, the
      // guard's bounds and the multiplier from the long literal table.
      if (!(plan.outputs().get(0) instanceof IntArith mul) || mul.op() != IntOp.MUL
          || mul.mode() != Overflow.WRAP || !(mul.left() instanceof GuardedRange guard)
          || !(guard.child() instanceof WidenLane) || !(mul.right() instanceof LiteralSlot slot)) {
        fail(cast.sql() + " compiles to " + plan.outputs().get(0)
            + ", not a wrapping multiply of the guarded day");
        return;
      }
      long lo = guard.lo();
      long hi = guard.hi();
      long factor = plan.longLiterals().get(slot.index());
      assertEquals(DAY, factor, cast.sql());
      // Every day: the guard admits exactly the days whose product does not overflow, by the
      // full 128-bit product's high half, which is the condition multiplyExact throws on.
      var bad = new AtomicLong();
      var first = new AtomicLong();
      int chunks = 256;
      long span = (1L << 32) / chunks;
      IntStream.range(0, chunks).parallel().forEach(c -> {
        long from = Integer.MIN_VALUE + c * span;
        for (long day = from; day < from + span; day++) {
          boolean admitted = lo <= day && day <= hi;
          long product = day * factor;
          boolean overflows = Math.multiplyHigh(day, factor) != (product >> 63);
          if (admitted == overflows && bad.getAndIncrement() == 0) {
            first.set(day);
          }
        }
      });
      assertEquals(0, bad.get(), cast.sql() + ": the guard [" + lo + ", " + hi + "] admits "
          + "a day whose product overflows, or refuses one whose product does not, at "
          + bad.get() + " days, first " + first.get());
      // And multiplyExact itself, which Spark calls, over a million days at each end of the
      // guard and in the middle: the product where the guard admits, a throw where it refuses.
      for (long centre : new long[] {lo, 0, hi}) {
        for (long day = centre - 500_000; day <= centre + 500_000; day++) {
          boolean admitted = lo <= day && day <= hi;
          try {
            long exact = Math.multiplyExact(day, factor);
            assertTrue(admitted && exact == day * factor, cast.sql() + " at day " + day);
          } catch (ArithmeticException e) {
            assertTrue(!admitted, cast.sql() + " admits day " + day + ", which overflows");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("a kernel converts the days at the guard's ends, and declines a batch holding one "
      + "past them, in LEGACY, ANSI and TRY")
  void dateToNtzAtTheEdges() {
    long[] inside = {-LAST_DAY, -LAST_DAY + 1, -1, 0, 1, LAST_DAY - 1, LAST_DAY};
    for (Expression cast : modes("d", "TIMESTAMP_NTZ")) {
      Kernel k = kernel(cast);
      assertTrue(k.answers(inside), cast.sql() + " declined the days inside the guard");
      for (long past : new long[] {-LAST_DAY - 1, LAST_DAY + 1}) {
        long[] values = {0, 1, past, -1};
        assertNotEquals(0, k.status(values), cast.sql() + " answered a batch holding day "
            + past + ", which Spark raises on or nulls");
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // TIMESTAMP_NTZ to DATE
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("NTZ to DATE answers the floor division at the long range's ends and at the day's "
      + "boundaries, as Spark and java.time do")
  void ntzToDateAtTheEdges() {
    var values = new ArrayList<Long>();
    for (long v : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, DAY, -DAY,
        LAST_DAY * DAY, -LAST_DAY * DAY}) {
      for (long delta = -2; delta <= 2; delta++) {
        long w = v + delta;
        // Only the neighbours on the range's own side: past an end is the other end.
        if ((v == Long.MIN_VALUE && delta < 0) || (v == Long.MAX_VALUE && delta > 0)) {
          continue;
        }
        values.add(w);
      }
    }
    long[] all = values.stream().mapToLong(Long::longValue).toArray();
    for (long v : all) {
      long seconds = Math.floorDiv(v, 1_000_000L);
      int nanos = (int) Math.floorMod(v, 1_000_000L) * 1000;
      long epochDay =
          LocalDateTime.ofEpochSecond(seconds, nanos, ZoneOffset.UTC).toLocalDate().toEpochDay();
      assertEquals(Math.floorDiv(v, DAY), epochDay, "java.time and floorDiv at " + v);
    }
    for (Expression cast : modes("ntz", "DATE")) {
      Kernel k = kernel(cast);
      assertTrue(k.answers(all), cast.sql() + " declined a batch; it cannot fail");
      for (long v : all) {
        var spark = k.oracle.answer(0, rowOf(v));
        long seconds = Math.floorDiv(v, 1_000_000L);
        long epochDay = LocalDateTime.ofEpochSecond(seconds,
            (int) Math.floorMod(v, 1_000_000L) * 1000, ZoneOffset.UTC).toLocalDate().toEpochDay();
        assertEquals(new VarkaSparkOracle.Value(epochDay), spark, cast.sql() + " at " + v);
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // The fields of a TIMESTAMP_NTZ (VARKA-340)
  // -------------------------------------------------------------------------------------------

  /** The long lane's counts the fields run at: the default, and 128 and 512 bits. */
  private static final int[] LANES = {0, 2, 8};

  /** The calendar fields: exact over the date only inside the guard {@code ntzDay} puts on it. */
  private static final List<String> CALENDAR_FIELDS = List.of("year(ntz)", "month(ntz)",
      "day(ntz)", "quarter(ntz)", "dayofyear(ntz)", "weekofyear(ntz)",
      "extract(YEAROFWEEK FROM ntz)", "last_day(ntz)", "trunc(ntz, 'YEAR')",
      "trunc(ntz, 'MONTH')", "trunc(ntz, 'QUARTER')");

  /** The fields that answer every long: the weekday forms, and the time of day. */
  private static final List<String> OPEN_FIELDS = List.of("dayofweek(ntz)", "weekday(ntz)",
      "extract(DAYOFWEEK_ISO FROM ntz)", "next_day(ntz, 'MONDAY')", "trunc(ntz, 'WEEK')",
      "hour(ntz)", "minute(ntz)", "second(ntz)", "CAST(ntz AS TIME(6))", "CAST(ntz AS TIME(3))",
      "CAST(ntz AS TIME(0))");

  /** The least and greatest count the date guard admits: its first and last day, whole. */
  private static final long GUARD_LO = (long) VarkaRangeAnalysis.NTZ_MIN_DAYS * DAY;
  private static final long GUARD_HI = ((long) VarkaChrono.NARROW_MAX_DAYS + 1) * DAY - 1;

  /** The dates the fields are read at: the calendar's seams and the guard's own ends. */
  private static final List<LocalDate> SEAMS = List.of(LocalDate.of(1970, 1, 1),
      LocalDate.of(1969, 12, 31), LocalDate.of(2000, 2, 29), LocalDate.of(1900, 3, 1),
      LocalDate.of(2100, 3, 1), LocalDate.of(1582, 10, 15), LocalDate.of(1582, 10, 4),
      LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31), LocalDate.of(2021, 1, 3),
      LocalDate.of(2018, 12, 31), LocalDate.of(2020, 12, 31), LocalDate.of(2024, 2, 29),
      LocalDate.of(-12799, 3, 1), LocalDate.of(-12799, 3, 2), LocalDate.of(33134, 8, 15),
      LocalDate.of(33134, 8, 14), LocalDate.of(-4, 2, 29));

  /** The times of a day: its first and last microsecond, the seams of the hour, minute, second. */
  private static final long[] TIMES = {0, 1, 999_999, 1_000_000, 59_999_999, 60_000_000,
      3_599_999_999L, 3_600_000_000L, 43_200_000_000L, 45_296_789_012L, DAY - 1};

  /** The counts inside the guard: each seam date at each time, before and after the epoch. */
  private static long[] insideGuard() {
    var values = new ArrayList<Long>();
    for (LocalDate date : SEAMS) {
      for (long time : TIMES) {
        values.add(date.toEpochDay() * DAY + time);
      }
    }
    for (long v : new long[] {-1, -DAY, -DAY - 1, GUARD_LO, GUARD_HI}) {
      values.add(v);
    }
    return values.stream().mapToLong(Long::longValue).toArray();
  }

  /** {@link #insideGuard} and the long range's ends, for the fields that answer every long. */
  private static long[] wholeLong() {
    var values = new ArrayList<Long>();
    for (long v : insideGuard()) {
      values.add(v);
    }
    // The lowest and highest whole days of the long range: the days its ends fall in are partial.
    long lowDay = Math.floorDiv(Long.MIN_VALUE, DAY) + 1;
    long highDay = Math.floorDiv(Long.MAX_VALUE, DAY) - 1;
    for (long v : new long[] {Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE,
        Long.MAX_VALUE - 1, GUARD_LO - 1, GUARD_HI + 1, Math.multiplyExact(lowDay, DAY) - 1,
        Math.multiplyExact(lowDay, DAY), Math.multiplyExact(highDay + 1, DAY) - 1,
        Math.multiplyExact(highDay + 1, DAY)}) {
      values.add(v);
    }
    return values.stream().mapToLong(Long::longValue).toArray();
  }

  @Test
  @DisplayName("a calendar field of an NTZ value answers every count inside the date guard, as "
      + "Spark does, and declines a batch holding a live count past either end")
  void calendarFieldsAtTheGuard() {
    long[] inside = insideGuard();
    for (boolean ansi : new boolean[] {false, true}) {
      for (String sql : CALENDAR_FIELDS) {
        for (int lanes : LANES) {
          Kernel k = kernel(resolve(sql, ansi), lanes);
          String where = sql + " at " + lanes + " long lanes, ansi " + ansi;
          assertTrue(k.answers(inside), where + " declined a count inside the guard");
          for (long past : new long[] {GUARD_LO - 1, GUARD_HI + 1, Long.MIN_VALUE,
              Long.MAX_VALUE}) {
            assertNotEquals(0, k.status(new long[] {0, past, 1}),
                where + " answered a batch holding the count " + past);
          }
          // A null row holding a count past the guard does not decline its batch, which is the
          // masked body's; the dense body, handed the same values with the null dropped, does.
          var statuses = k.eachBodyStatus(new long[] {0, 1}, Long.MIN_VALUE);
          assertEquals(0, statuses.get(0), where + " declined a null row's stored value");
          assertNotEquals(0, statuses.get(1), where + " ignored a live count past the guard");
        }
      }
    }
  }

  @Test
  @DisplayName("the weekday forms and the time of day of an NTZ value answer every long, the "
      + "long range's ends included, as Spark does")
  void openFieldsAtTheLongRange() {
    long[] values = wholeLong();
    for (boolean ansi : new boolean[] {false, true}) {
      for (String sql : OPEN_FIELDS) {
        for (int lanes : LANES) {
          Kernel k = kernel(resolve(sql, ansi), lanes);
          assertTrue(k.answers(values), sql + " declined a batch at " + lanes + " long lanes, "
              + "ansi " + ansi + "; it cannot fail");
        }
      }
    }
  }

  @Test
  @DisplayName("the fields over a sampled walk of every day the guard admits, against java.time: "
      + "a day in 211, at a time of day drawn from the day")
  void fieldsAgainstJavaTime() {
    int first = VarkaRangeAnalysis.NTZ_MIN_DAYS;
    int last = VarkaChrono.NARROW_MAX_DAYS;
    var counts = new ArrayList<Long>();
    for (long day = first; day <= last; day += 211) {
      counts.add(day * DAY + Math.floorMod(day * 7_919_003_001L, DAY));
    }
    for (long day = first; day < first + 800; day++) {
      counts.add(day * DAY + (day % 2 == 0 ? 0 : DAY - 1));
      counts.add((last - (day - first)) * DAY + (day % 2 == 0 ? DAY - 1 : 0));
    }
    long[] values = counts.stream().mapToLong(Long::longValue).toArray();
    Map<String, ToLongFunction<LocalDateTime>> fields = new LinkedHashMap<>();
    fields.put("year(ntz)", LocalDateTime::getYear);
    fields.put("month(ntz)", LocalDateTime::getMonthValue);
    fields.put("day(ntz)", LocalDateTime::getDayOfMonth);
    fields.put("dayofyear(ntz)", LocalDateTime::getDayOfYear);
    fields.put("weekofyear(ntz)", t -> t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    fields.put("extract(YEAROFWEEK FROM ntz)", t -> t.get(IsoFields.WEEK_BASED_YEAR));
    fields.put("hour(ntz)", LocalDateTime::getHour);
    fields.put("minute(ntz)", LocalDateTime::getMinute);
    fields.put("second(ntz)", LocalDateTime::getSecond);
    fields.put("CAST(ntz AS TIME(6))", t -> t.toLocalTime().toNanoOfDay());
    fields.forEach((sql, field) -> {
      Kernel k = kernel(resolve(sql, true), 0);
      assertTrue(k.answersJavaTime(values, field), sql + " differs from java.time");
    });
  }

  // -------------------------------------------------------------------------------------------
  // make_timestamp_ntz(date, time) and CAST(time AS TIMESTAMP_NTZ) (VARKA-340)
  // -------------------------------------------------------------------------------------------

  /** 04:00:54.775807 in nanoseconds: the last time of day whose count is a long on LAST_DAY. */
  private static final long LAST_TIME = 14_454_775_807_000L;

  /** The last nanosecond of a day, a {@code TIME(9)} value the kernel truncates to micros. */
  private static final long LAST_NANO = DateTimeConstants.NANOS_PER_DAY - 1;

  @Test
  @DisplayName("make_timestamp_ntz of a date and a time answers every day whose midnight is a "
      + "long as Spark does, and declines a batch past those days or past the long")
  void makeTimestampNtzAtTheEdges() {
    long[][] inside = {{LAST_DAY, LAST_TIME}, {-LAST_DAY, 0}, {-LAST_DAY, LAST_NANO},
        {0, LAST_NANO}, {-1, 1}, {0, 999}, {19_000, 45_296_789_012_345L}, {LAST_DAY, 0}};
    // The last day after its last whole microsecond, which Spark raises on; the day before the
    // first, whose evening Spark answers and the kernel declines whole; days no long reaches.
    long[][] past = {{LAST_DAY, LAST_TIME + 1000}, {-LAST_DAY - 1, LAST_NANO},
        {-LAST_DAY - 1, 0}, {LAST_DAY + 1, 0}, {Integer.MIN_VALUE, 0}, {Integer.MAX_VALUE, 0}};
    for (boolean ansi : new boolean[] {false, true}) {
      Expression e = resolve("make_timestamp_ntz(d, tm)", ansi);
      for (int lanes : LANES) {
        String where = e.sql() + " at " + lanes + " long lanes, ansi " + ansi;
        assertTrue(answersPairs(e, lanes, inside), where + " declined a day inside the guard");
        for (long[] row : past) {
          assertTrue(!answersPairs(e, lanes, new long[][] {{0, 0}, row, {1, 1}}),
              where + " answered day " + row[0] + " at " + row[1]);
        }
      }
    }
  }

  @Test
  @DisplayName("a date literal is checked when make_timestamp_ntz compiles, and every date in "
      + "range shares one kernel")
  void makeTimestampNtzOverALiteral() {
    for (long day : new long[] {-LAST_DAY, LAST_DAY}) {
      Expression e = new MakeTimestampNTZ(Literal.create((int) day, DataTypes.DateType), tm);
      // On the last day, nothing past LAST_TIME is a long.
      long[][] times = day == LAST_DAY ? new long[][] {{0, 0}, {0, 1}, {0, LAST_TIME}}
          : new long[][] {{0, 0}, {0, LAST_TIME}, {0, LAST_NANO}};
      for (int lanes : LANES) {
        assertTrue(answersPairs(e, lanes, times), e.sql() + " declined at " + lanes);
      }
      if (day == LAST_DAY) {
        assertTrue(!answersPairs(e, 0, new long[][] {{0, 0}, {0, LAST_TIME + 1000}}),
            e.sql() + " answered past the long, where Spark raises");
      }
    }
    for (long day : new long[] {-LAST_DAY - 1, LAST_DAY + 1}) {
      Expression e = new MakeTimestampNTZ(Literal.create((int) day, DataTypes.DateType), tm);
      String reason = declineReason(e);
      assertTrue(reason.contains("midnight is not a long"), e.sql() + " declined with " + reason);
    }
    byte[] first = emit(new MakeTimestampNTZ(Literal.create(0, DataTypes.DateType), tm), 0);
    byte[] other = emit(new MakeTimestampNTZ(Literal.create(20_000, DataTypes.DateType), tm), 0);
    assertTrue(Arrays.equals(first, other), "two date literals emitted two kernels");
  }

  @Test
  @DisplayName("CAST(time AS TIMESTAMP_NTZ) fuses as the query's date at the time of day, the "
      + "nanoseconds truncated, and the unrewritten cast declines by name")
  void timeToNtz() {
    long[][] times = {{0, 0}, {0, 1}, {0, 999}, {0, LAST_NANO}, {0, 45_296_789_012_345L}};
    for (boolean ansi : new boolean[] {false, true}) {
      Expression e = resolve("CAST(tm AS TIMESTAMP_NTZ)", ansi);
      for (int lanes : LANES) {
        assertTrue(answersPairs(e, lanes, times), e.sql() + " declined at " + lanes);
      }
    }
    Expression written = VarkaSqlResolve.resolveAsWritten(
        CatalystSqlParser$.MODULE$.parseExpression("CAST(tm AS TIMESTAMP_NTZ)"),
        CollectionConverters.asScala(columns).toSeq());
    String reason = declineReason(written);
    assertTrue(reason.contains("ComputeCurrentTime"), "declined with " + reason);
  }

  /** Why {@code e} declines, as the compiler reports it; empty if it fuses. */
  private String declineReason(Expression e) {
    var entry = CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq();
    return VarkaExpressionCompiler.declines(entry, CollectionConverters.asScala(columns).toSeq(),
        VarkaMatrixRuns.BASE).values().stream().findFirst().map(VarkaDecline::reason).orElse("");
  }

  private byte[] emit(Expression e, int lanes) {
    CompiledVarkaProjection plan = compile(e).orElseThrow(() ->
        new AssertionError(e.sql() + " declines"));
    return VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaNtzMake",
        plan.outputs(), plan.inputOrdinals().size(), plan.numLiterals(), null, null,
        lanes == 0 ? VarkaMatrixRuns.BASE : VarkaMatrixRuns.BASE.withLanesOverride(lanes));
  }

  /**
   * Whether the kernel of {@code e} answers a batch of {@code rows}, each a day and a time of day
   * in nanoseconds, every row compared with Spark, and a row of nulls after them; false when it
   * declines the batch. A form over a date literal reads the time alone.
   */
  private boolean answersPairs(Expression e, int lanes, long[][] rows) {
    CompiledVarkaProjection plan = compile(e).orElseThrow(() ->
        new AssertionError(e.sql() + " declines"));
    String className = "org.apache.spark.sql.varka.execution.VarkaNtzMake"
        + CLASS_COUNTER.incrementAndGet();
    byte[] bytes = VarkaLoopEmitter.emit(className, plan.outputs(), plan.inputOrdinals().size(),
        plan.numLiterals(), null, null,
        lanes == 0 ? VarkaMatrixRuns.BASE : VarkaMatrixRuns.BASE.withLanesOverride(lanes));
    var inputs = new ArrayList<Attribute>();
    for (int ordinal : plan.inputOrdinals()) {
      inputs.add(columns.get(ordinal));
    }
    int n = rows.length + 1;
    var data = new long[inputs.size()][n];
    var nulls = new boolean[inputs.size()][n];
    for (int c = 0; c < inputs.size(); c++) {
      int field = inputs.get(c) == d ? 0 : 1;
      for (int r = 0; r < rows.length; r++) {
        data[c][r] = rows[r][field];
      }
      nulls[c][n - 1] = true;
    }
    var oracle = new VarkaSparkOracle(CollectionConverters.asScala(List.of(e)).toSeq(),
        CollectionConverters.asScala(inputs).toSeq());
    return VarkaKernelChecks.runAndCompareLong(className + " " + e.sql(), className, bytes,
        plan.outputs(), inputs.size(),
        plan.longLiterals().stream().mapToLong(Long::longValue).toArray(),
        plan.literals().stream().mapToInt(Integer::intValue).toArray(),
        new LongBatch(n, nulls, data, false), true, sparkAnswers(oracle));
  }

  // -------------------------------------------------------------------------------------------
  // Running a kernel
  // -------------------------------------------------------------------------------------------

  /** One compiled cast, emitted, with Spark's evaluation of it for the answers. */
  private final class Kernel {
    final Expression expr;
    final CompiledVarkaProjection plan;
    final String className;
    final byte[] bytes;
    final VarkaSparkOracle oracle;

    Kernel(Expression expr, CompiledVarkaProjection plan, int lanes) {
      this.expr = expr;
      this.plan = plan;
      this.className = "org.apache.spark.sql.varka.execution.VarkaNtzCast"
          + CLASS_COUNTER.incrementAndGet();
      this.bytes = VarkaLoopEmitter.emit(className, plan.outputs(), plan.inputOrdinals().size(),
          plan.numLiterals(), null, null,
          lanes == 0 ? VarkaMatrixRuns.BASE : VarkaMatrixRuns.BASE.withLanesOverride(lanes));
      var input = columns.get(plan.inputOrdinals().get(0));
      this.oracle = new VarkaSparkOracle(CollectionConverters.asScala(List.of(expr)).toSeq(),
          CollectionConverters.asScala(List.of(input)).toSeq());
    }

    private LongBatch batch(long[] values) {
      // Each value once valid and once null, so both bodies and a null lane run.
      int n = values.length * 2;
      var data = new long[1][n];
      var nulls = new boolean[1][n];
      for (int i = 0; i < n; i++) {
        data[0][i] = values[i / 2];
        nulls[0][i] = i % 2 == 1;
      }
      return new LongBatch(n, nulls, data, false);
    }

    private long[] longLits() {
      return plan.longLiterals().stream().mapToLong(Long::longValue).toArray();
    }

    private int[] intLits() {
      return plan.literals().stream().mapToInt(Integer::intValue).toArray();
    }

    /** Whether the kernel answers {@code values}, every row compared with Spark. */
    boolean answers(long[] values) {
      return VarkaKernelChecks.runAndCompareLong(className + " " + expr.sql(), className, bytes,
          plan.outputs(), 1, longLits(), intLits(), batch(values), false, sparkAnswers(oracle));
    }

    /** Whether the kernel answers {@code values} as {@code field} of their date-time says. */
    boolean answersJavaTime(long[] values, ToLongFunction<LocalDateTime> field) {
      var answers = new SparkAnswers() {
        @Override
        public VarkaKernelChecks.Answer answer(int output, long[] row, boolean[] isNull) {
          if (isNull[0]) {
            return new VarkaKernelChecks.Answer.Null();
          }
          long seconds = Math.floorDiv(row[0], 1_000_000L);
          int nanos = (int) Math.floorMod(row[0], 1_000_000L) * 1000;
          return new VarkaKernelChecks.Answer.Value(
              field.applyAsLong(LocalDateTime.ofEpochSecond(seconds, nanos, ZoneOffset.UTC)));
        }

        @Override
        public String sql(int output) {
          return expr.sql();
        }
      };
      return VarkaKernelChecks.runAndCompareLong(className + " " + expr.sql(), className, bytes,
          plan.outputs(), 1, longLits(), intLits(), batch(values), false, answers);
    }

    /**
     * The status of each body over {@code valid} rows and then one null row holding
     * {@code underNull}: the masked body's first, then the dense body's over the same values with
     * the null dropped.
     */
    List<Integer> eachBodyStatus(long[] valid, long underNull) {
      int n = valid.length + 1;
      var data = new long[1][n];
      var nulls = new boolean[1][n];
      System.arraycopy(valid, 0, data[0], 0, valid.length);
      data[0][n - 1] = underNull;
      nulls[0][n - 1] = true;
      return VarkaKernelChecks.runEachBodyLongStatus(className + " " + expr.sql(), className,
          bytes, plan.outputs(), 1, longLits(), new LongBatch(n, nulls, data, false));
    }

    /** The kernel's status over {@code values}: non-zero when it declines the batch. */
    int status(long[] values) {
      return VarkaKernelChecks.runAndCompareLongStatus(className + " " + expr.sql(), className,
          bytes, plan.outputs(), 1, longLits(), batch(values), true);
    }
  }

  private Kernel kernel(Expression cast) {
    return kernel(cast, 0);
  }

  /** {@code expr} compiled and emitted at {@code lanes} long lanes, or at the default for 0. */
  private Kernel kernel(Expression expr, int lanes) {
    return new Kernel(expr, compile(expr).orElseThrow(() ->
        new AssertionError(expr.sql() + " declines")), lanes);
  }

  private static scala.collection.immutable.Seq<Option<Object>> rowOf(long v) {
    return CollectionConverters.asScala(List.of(Option.apply((Object) v))).toList();
  }

  private static SparkAnswers sparkAnswers(VarkaSparkOracle oracle) {
    return new SparkAnswers() {
      @Override
      public VarkaKernelChecks.Answer answer(int output, long[] values, boolean[] isNull) {
        var row = new ArrayList<Option<Object>>();
        for (int c = 0; c < values.length; c++) {
          row.add(isNull[c] ? Option.empty() : Option.apply((Object) values[c]));
        }
        VarkaSparkOracle.Answer a = oracle.answer(output,
            CollectionConverters.asScala(row).toList());
        if (a instanceof VarkaSparkOracle.Value v) {
          return new VarkaKernelChecks.Answer.Value(v.v());
        } else if (a instanceof VarkaSparkOracle.Raised r) {
          return new VarkaKernelChecks.Answer.Raised(r.e());
        }
        return new VarkaKernelChecks.Answer.Null();
      }

      @Override
      public String sql(int output) {
        return oracle.sql(output);
      }
    };
  }
}
