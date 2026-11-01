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

package org.apache.spark.sql.execution;

import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.ANSWERS;
import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.DECLINES;
import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.SERVED;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.catalyst.util.SparkDateTimeUtils$;
import org.apache.spark.sql.catalyst.util.SparkIntervalUtils$;
import org.apache.spark.sql.execution.VarkaHarnessCases.Case;
import org.apache.spark.sql.execution.VarkaHarnessCases.Shape;
import org.apache.spark.sql.execution.VarkaHarnessCases.Source;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * VARKA-102's TIME kernels held to Spark's answer over every second of a day, with no session
 * (VARKA-509 step 3): the shapes {@code VarkaTimeArithmeticSuite} ran through two sessions, under
 * the names it gave them. That suite keeps what needs a session: the errors a user sees when a
 * sum leaves the day or a count lies outside it, and the session switch that selects the
 * division's magic form.
 *
 * <p>{@code t1 - t2}, {@code time_diff} and {@code time_trunc} are a subtraction and a constant
 * division on the long lane; {@code t + dt} is a wrapping add under two range guards, because
 * Spark's {@code DateTimeUtils.timeAddInterval} throws where the sum leaves the day and a lane
 * cannot throw, so the batch declines to the row path instead. The day's table chooses each
 * row's interval so that the sum stays inside the day; the crossing table's rows leave it.
 */
@VarkaSuite
public final class VarkaTimeHarnessSuite {

  private static final int SECONDS_PER_DAY = 86400;

  private static final String DAY_SCHEMA = "SELECT * FROM VALUES (0, CAST(NULL AS TIME(6)), "
      + "CAST(NULL AS TIME(6)), CAST(NULL AS TIME(3)), CAST(NULL AS INTERVAL DAY TO SECOND), "
      + "CAST(NULL AS INTERVAL DAY TO SECOND), CAST(NULL AS BIGINT)) AS v(rid, t, t2, t3, dt, "
      + "dt2, s)";

  private static final String CROSSING_SCHEMA = "SELECT * FROM VALUES (0, CAST(NULL AS TIME(6)), "
      + "CAST(NULL AS INTERVAL DAY TO SECOND)) AS v(rid, t, dt)";

  private static long nanos(LocalTime t) {
    return SparkDateTimeUtils$.MODULE$.localTimeToNanos(t);
  }

  private static long micros(Duration d) {
    return SparkIntervalUtils$.MODULE$.durationToMicros(d);
  }

  /**
   * Every second of the day, plus a sub-second edge on every thirteenth row: one microsecond,
   * half a second, the last microsecond before the next second. Every 31st {@code t} and every
   * 47th {@code dt} is null.
   */
  private static List<Object[]> dayRows() {
    var rows = new ArrayList<Object[]>(SECONDS_PER_DAY);
    long[] fractions = {1000L, 500000000L, 999999000L};
    for (int i = 0; i < SECONDS_PER_DAY; i++) {
      long fraction = i % 13 == 0 ? fractions[i / 13 % 3] : 0L;
      LocalTime t = LocalTime.ofSecondOfDay(i).plusNanos(fraction);
      // Shifted by twelve hours and thirty-seven minutes, modulo the day.
      LocalTime t2 = t.plusSeconds(12 * 3600 + 37 * 60);
      // Truncated to milliseconds, so the TIME(3) column exercises the widening cast.
      LocalTime t3 = LocalTime.ofNanoOfDay(t.toNanoOfDay() / 1000000L * 1000000L);
      // An hour forward before noon and back after it stays inside the day for every row; the
      // half-second one exercises the sub-second path and the microsecond precision step.
      boolean forward = t.isBefore(LocalTime.NOON);
      Duration dt = forward ? Duration.ofHours(1) : Duration.ofHours(-1);
      Duration dt2 = forward ? Duration.ofMillis(500) : Duration.ofMillis(-500);
      // `s` is the second of day as a bigint, the count the `time_from_*` conversions take.
      boolean noT = i % 31 == 30;
      rows.add(new Object[] {i, noT ? null : nanos(t), nanos(t2), nanos(t3),
          i % 47 == 46 ? null : micros(dt), micros(dt2), noT ? null : (long) t.toSecondOfDay()});
    }
    return rows;
  }

  /** Three rows whose sum leaves the day, one that stays, and a null. */
  private static List<Object[]> crossingRows() {
    return List.of(
        new Object[] {0, nanos(LocalTime.of(23, 30)), micros(Duration.ofHours(1))},
        new Object[] {1, nanos(LocalTime.of(0, 15)), micros(Duration.ofHours(-1))},
        new Object[] {2, nanos(LocalTime.of(23, 59, 59, 999999000)),
            micros(Duration.ofNanos(1000))},
        new Object[] {3, nanos(LocalTime.of(12, 0)), micros(Duration.ofHours(1))},
        new Object[] {4, null, micros(Duration.ofHours(1))});
  }

  private static final Source DAY = VarkaHarnessCases.source(DAY_SCHEMA, dayRows());

  private static final Source CROSSING =
      VarkaHarnessCases.source(CROSSING_SCHEMA, crossingRows());

  /** {@code select} as the one output of a query over the day's table, served by a kernel. */
  private static Shape served(String select) {
    return new Shape("SELECT " + select + " AS v FROM t", SERVED);
  }

  private static List<Shape> served(List<String> selects) {
    return selects.stream().map(VarkaTimeHarnessSuite::served).toList();
  }

  private static final List<Case> CASES = cases();

  /** The cases, which {@link VarkaHarnessParitySuite} plans in a session too. */
  static List<Case> all() {
    return CASES;
  }

  private static List<Case> cases() {
    var out = new ArrayList<Case>();
    // The subtraction of two nanosecond counts and the constant division that follows it,
    // including a TIME(3) operand reached through the widening cast, and every unit
    // DateTimeUtils.getNanosPerTimeUnit accepts, in both spellings the compiler reads.
    var diffs = new ArrayList<>(List.of("t - t2", "t2 - t", "t - t3"));
    for (String unit : List.of("HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND",
        "second")) {
      diffs.add("time_diff('" + unit + "', t, t2)");
    }
    out.add(new Case("t1 - t2 and time_diff at every unit agree with the row engine over every "
        + "second", DAY, served(diffs)));
    // `truncatedTo(level)` as a division and a multiply, at both precisions the table holds.
    var truncs = new ArrayList<String>();
    for (String level : List.of("HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND")) {
      truncs.add("time_trunc('" + level + "', t)");
      truncs.add("time_trunc('" + level + "', t3)");
    }
    out.add(new Case("time_trunc at every level agrees with the row engine over every second",
        DAY, served(truncs)));
    // No sum crosses midnight, which makes this the check of the arithmetic and the guard's
    // pass-through: a column interval of whole hours, one of half seconds, a literal, and a
    // TIME(3) time whose result widens to TIME(6). The precision step Spark applies after the
    // add is provably the identity for these types and is not emitted; agreement here is what
    // that proof is held to.
    out.add(new Case("t + dt agrees with the row engine where the sum stays inside the day", DAY,
        served(List.of("t + dt", "t + dt2", "t3 + dt2",
            "t2 + INTERVAL '0 00:00:00.000001' DAY TO SECOND"))));
    // The smallest day-time interval is -106751991 days 04:00:54.775808, Long.MIN_VALUE
    // microseconds. The multiply by 1000 wraps to 0 in a lane, so a kernel that admitted it
    // answered every time unchanged, where Spark's multiplyExact throws on every row
    // (VARKA-293).
    out.add(new Case("t + the interval of Long.MinValue microseconds raises Spark's error, not "
        + "t unchanged", CROSSING, List.of(new Shape(
            "SELECT t + INTERVAL '-106751991 04:00:54.775808' DAY TO SECOND AS v FROM t",
            ANSWERS))));
    // The emitter confines a guard under a CASE arm to the lanes the arm is selected for
    // (VarkaEmitOptions.guardUnderArm), so a crossing row in the untaken arm neither throws on
    // the row path nor declines the kernel: the batch is served. A guard that fired for every
    // lane would have declined here for nothing.
    out.add(new Case("a guard under a CASE arm fires only for the lanes the arm is taken for",
        CROSSING, List.of(new Shape(
            "SELECT CASE WHEN t = TIME'12:00:00' THEN t + dt ELSE t END AS v FROM t", SERVED))));
    // AND is short-circuit on the row path, so a false conjunct to the left spares the add,
    // while a kernel evaluates every conjunct of a fused predicate for every lane: the guard
    // fires on the crossing lanes and the batch declines. The selection is a range and not
    // `t = TIME'12:00:00'` on purpose: the optimizer's constant propagation would rewrite the
    // sum's `t` to the literal, and noon plus any of these intervals stays inside the day.
    out.add(new Case("a crossing lane under a conjunction declines to the row engine, which "
        + "answers what it would", CROSSING, List.of(new Shape("SELECT t FROM t "
            + "WHERE t > TIME'11:59:59' AND t < TIME'12:00:01' AND t + dt < TIME'23:00:00'",
            DECLINES))));
    // Group C's extracts (VARKA-102.md 8.3): LocalTime's field reads against long-lane
    // divisions narrowed to an int at the kernel's store. The TIME(3) column puts the widening
    // cast in front of the division, and the sub-second columns reach the edges where a
    // rounding division would answer the next second.
    var fields = new ArrayList<String>();
    for (String column : List.of("t", "t2", "t3")) {
      for (String field : List.of("hour", "minute", "second")) {
        fields.add(field + "(" + column + ")");
      }
    }
    out.add(new Case("hour, minute and second agree with the row engine over every second of "
        + "the day", DAY, served(fields)));
    // VARKA-28: `hour(t) + 1` narrows inside the tree, so both entries are one kernel's, which
    // holds both lanes.
    out.add(new Case("an extract under another expression fuses beside a fused one", DAY,
        List.of(new Shape("SELECT hour(t) + 1 AS h, minute(t) AS m FROM t", SERVED))));
    // Group E (VARKA-102.md 9.3): the two divisions at three precisions, and the three
    // multiplies from a bigint count and from their own divisions' results. Every row is
    // inside the day, so every batch is the kernel's.
    var conversions = new ArrayList<String>();
    for (String column : List.of("t", "t2", "t3")) {
      for (String field : List.of("time_to_millis", "time_to_micros")) {
        conversions.add(field + "(" + column + ")");
      }
    }
    conversions.addAll(List.of("time_from_seconds(s)", "time_from_millis(time_to_millis(t))",
        "time_from_micros(time_to_micros(t2))"));
    out.add(new Case("time_to_millis, time_to_micros and the time_from_* conversions agree with "
        + "the row engine over every second of the day", DAY, served(conversions)));
    return out;
  }

  @BeforeAll
  static void matrixOptions() {
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE);
  }

  @AfterAll
  static void defaultOptions() {
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS);
  }

  @TestFactory
  Stream<DynamicTest> everyCase() {
    return CASES.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
      List<String> findings = VarkaHarnessCases.check(c);
      assertTrue(findings.isEmpty(), String.join("\n", findings));
    }));
  }
}
