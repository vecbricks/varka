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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.LongBatch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.SparkAnswers;
import org.apache.spark.sql.catalyst.util.DateTimeConstants;
import org.apache.spark.sql.catalyst.util.DateTimeUtils;
import org.apache.spark.sql.types.DataTypes;

/**
 * The month forms of a {@code TIMESTAMP_NTZ} value (VARKA-339, `VARKA-339.md` 2.3 and 3.5):
 * {@code ntz +/- ym} and {@code timestampadd} by MONTH, QUARTER and YEAR move the date of the count
 * by {@code AddMonths} under the date guard and add the change of day, in microseconds, to the
 * count. The first test holds that composition to Spark's {@code timestampAddMonths} and to
 * {@code LocalDateTime.plusMonths} over the days the guard admits, for the twelve counts
 * {@code VarkaRangeRulesSuite} sweeps {@code add_months} with; the second runs the emitted kernel
 * over the same days and counts. At pull-request time both walk a sample of the days, a day in
 * seven for the scalar form and in 211 for the kernel, beside every day within 400 of the guard's
 * ends; {@code -Dvarka.sweep=true} walks every day at three times of day.
 *
 * <p>In its own JVM: the kernels hold both lanes, whose int species is the long species' width.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaTimestampNtzMonthSuite {

  private static final long DAY = DateTimeConstants.MICROS_PER_DAY;

  /** The first and last day the date guard admits. */
  private static final int FIRST = VarkaRangeAnalysis.NTZ_MIN_DAYS;
  private static final int LAST = VarkaChrono.NARROW_MAX_DAYS;

  /** The counts at the month range's ends and around zero, as the range rules sweep them. */
  private static final int[] COUNTS = {VarkaChrono.MONTH_ARITH_MIN_MONTHS,
      VarkaChrono.MONTH_ARITH_MIN_MONTHS + 1, -13, -12, -1, 0, 1, 11, 12, 13,
      VarkaChrono.MONTH_ARITH_MAX_MONTHS - 1, VarkaChrono.MONTH_ARITH_MAX_MONTHS};

  private static final boolean WHOLE = "true".equals(System.getProperty("varka.sweep"));

  /** A time of day drawn from the day, so that the walk meets every part of a day. */
  private static long drawnTime(long day) {
    return Math.floorMod(day * 7_919_003_001L, DAY);
  }

  /**
   * The counts the walk visits: every {@code stride}-th admitted day and every day within 400 of
   * the guard's ends, each at its drawn time of day, or under {@code -Dvarka.sweep=true} every
   * admitted day at its first and last microsecond and its drawn time.
   */
  private static long[] walk(int stride) {
    LongStream days = WHOLE ? LongStream.rangeClosed(FIRST, LAST) : LongStream.concat(
        LongStream.iterate(FIRST, d -> d <= LAST, d -> d + stride),
        LongStream.concat(LongStream.rangeClosed(FIRST, FIRST + 400),
            LongStream.rangeClosed(LAST - 400, LAST)));
    return days.flatMap(d -> WHOLE
        ? LongStream.of(d * DAY, d * DAY + drawnTime(d), d * DAY + DAY - 1)
        : LongStream.of(d * DAY + drawnTime(d))).toArray();
  }

  private static LocalDateTime dateTime(long micros) {
    return LocalDateTime.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
        (int) Math.floorMod(micros, 1_000_000L) * 1000, ZoneOffset.UTC);
  }

  /** {@code LocalDateTime.plusMonths} of the count, as a count again. */
  private static long javaTime(long t, int months) {
    LocalDateTime moved = dateTime(t).plusMonths(months);
    return ChronoUnit.MICROS.between(LocalDateTime.of(1970, 1, 1, 0, 0), moved);
  }

  /** The kernel's composition, in scalar form: the change of the date, as a day count, added. */
  private static long composed(long t, int months) {
    int day = (int) Math.floorDiv(t, DAY);
    return t + (DateTimeUtils.dateAddMonths(day, months) - (long) day) * DAY;
  }

  @Test
  @DisplayName("the month shift of an NTZ count is its date's add_months plus the time of day, "
      + "as Spark's timestampAddMonths and java.time's plusMonths answer, over the guard's days")
  void scalarSweep() {
    long[] counts = walk(7);
    long failures = Arrays.stream(counts).parallel().filter(t -> {
      for (int m : COUNTS) {
        long ours = composed(t, m);
        if (ours != DateTimeUtils.timestampAddMonths(t, m, ZoneOffset.UTC)
            || ours != javaTime(t, m)) {
          return true;
        }
      }
      return false;
    }).count();
    assertEquals(0, failures, failures + " of " + counts.length + " counts differ");
    assertTrue(counts.length > (WHOLE ? 3 * (LAST - FIRST) : (LAST - FIRST) / 7),
        "walked " + counts.length);
  }

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  private final Attribute ntz = VarkaSqlResolve.column("ntz", DataTypes.TimestampNTZType);

  /** {@code sql} over the NTZ column, compiled and emitted at the default width. */
  private record Kernel(String sql, CompiledVarkaProjection plan, String className,
      byte[] bytes) {

    /** Whether the kernel answers every row of {@code values} as {@code spark} does. */
    boolean answers(long[] values, SparkAnswers spark) {
      int n = values.length * 2;
      var data = new long[1][n];
      var nulls = new boolean[1][n];
      for (int i = 0; i < n; i++) {
        data[0][i] = values[i / 2];
        nulls[0][i] = i % 2 == 1;
      }
      return VarkaKernelChecks.runAndCompareLong(className + " " + sql, className, bytes,
          plan.outputs(), 1, plan.longLiterals().stream().mapToLong(Long::longValue).toArray(),
          plan.literals().stream().mapToInt(Integer::intValue).toArray(),
          new LongBatch(n, nulls, data, false), true, spark);
    }
  }

  private Kernel kernel(String sql) {
    var cols = CollectionConverters.asScala(List.of(ntz)).toSeq();
    Expression e = VarkaSqlResolve.parse(sql, cols);
    CompiledVarkaProjection plan = VarkaExpressionCompiler.compile(
        CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq(), cols,
        VarkaMatrixRuns.BASE).orElseThrow(() -> new AssertionError(sql + " declines"));
    String className = "org.apache.spark.sql.varka.execution.VarkaNtzMonths"
        + CLASS_COUNTER.incrementAndGet();
    return new Kernel(sql, plan, className, VarkaLoopEmitter.emit(className, plan.outputs(),
        plan.inputOrdinals().size(), plan.numLiterals(), null, null, VarkaMatrixRuns.BASE));
  }

  /** {@code plusMonths} by {@code months}, the answer each row is held to. */
  private static SparkAnswers plusMonths(String sql, int months) {
    return new SparkAnswers() {
      @Override
      public VarkaKernelChecks.Answer answer(int output, long[] row, boolean[] isNull) {
        return isNull[0] ? new VarkaKernelChecks.Answer.Null()
            : new VarkaKernelChecks.Answer.Value(javaTime(row[0], months));
      }

      @Override
      public String sql(int output) {
        return sql;
      }
    };
  }

  @Test
  @DisplayName("the emitted month shift answers the guard's days as java.time's plusMonths does, "
      + "for the twelve counts, and declines a batch with a count one past the guard")
  void emittedSweep() {
    long[] counts = walk(211);
    long below = (long) FIRST * DAY - 1;
    long above = ((long) LAST + 1) * DAY;
    for (int m : COUNTS) {
      String sql = "timestampadd(MONTH, " + m + ", ntz)";
      Kernel k = kernel(sql);
      SparkAnswers oracle = plusMonths(sql, m);
      for (int from = 0; from < counts.length; from += 4096) {
        long[] batch = Arrays.copyOfRange(counts, from,
            Math.min(counts.length, from + 4096));
        assertTrue(k.answers(batch, oracle), sql + " declined a batch inside the guard, from "
            + dateTime(batch[0]));
      }
      for (long past : new long[] {below, above}) {
        assertFalse(k.answers(new long[] {0, past, 1}, oracle),
            sql + " answered a batch holding " + past);
      }
    }
    // The other spellings build the same kernel with the count scaled or negated.
    List<String> spellings = new ArrayList<>(List.of("ntz + INTERVAL '13' MONTH",
        "ntz - INTERVAL '2-1' YEAR TO MONTH", "timestampadd(QUARTER, -5, ntz)",
        "timestampadd(YEAR, 2047, ntz)", "timestampadd(year, -2048, ntz)"));
    int[] months = {13, -25, -15, 2047 * 12, -2048 * 12};
    for (int i = 0; i < spellings.size(); i++) {
      Kernel k = kernel(spellings.get(i));
      long[] sample = LongStream.iterate(FIRST, d -> d <= LAST, d -> d + 7919)
          .map(d -> d * DAY + drawnTime(d)).toArray();
      assertTrue(k.answers(sample, plusMonths(spellings.get(i), months[i])), spellings.get(i));
    }
  }
}
