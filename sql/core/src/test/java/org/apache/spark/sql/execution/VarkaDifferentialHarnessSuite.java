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

import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.DECLINES;
import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.FUSED;
import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.NOT_FUSED;
import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.RUNS;
import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.SERVED;
import static org.apache.spark.sql.execution.VarkaHarnessCases.Expect.WHOLE;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChrono;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.execution.VarkaHarnessCases.Case;
import org.apache.spark.sql.execution.VarkaHarnessCases.Expect;
import org.apache.spark.sql.execution.VarkaHarnessCases.Part;
import org.apache.spark.sql.execution.VarkaHarnessCases.Shape;
import org.apache.spark.sql.execution.VarkaHarnessCases.Source;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The date, interval and integer kernels held to Spark's answer with no session (VARKA-509 step
 * 3): the hand-written shapes {@code VarkaDifferentialSuite} ran through two sessions, under the
 * names it gave them, over its tables. Each query states what that suite asserts of the plan
 * and the node's metrics, read where the harness sees it: whether the compiler fuses the query,
 * whether a kernel answered or declined a batch, and what verbose {@code EXPLAIN} says of an
 * entry. That suite keeps what needs a session: the plan's shape above and around the Varka
 * nodes, the fallback hooks and their metrics, the cache, AQE, tasks and the SQL UI.
 *
 * <p>A query that suite sorts is unsorted here, the harness comparing row by row; one it
 * counted is the filter it counted over; and one that stacked a projection on a filter is the
 * filter and the projection, each over the whole table, since how the projection reads the
 * filter's compacted batch is the session's.
 */
@VarkaSuite
public final class VarkaDifferentialHarnessSuite {

  // ---------------------------------------------------------------------------------------------
  // The tables
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code rows} as a table of {@code columns} with a leading {@code rid}. Spark reads a column's
   * nullability off its values, so where that suite's table declares a column nullable that
   * holds no null, {@code nullRow} is a row of NULLs for them, in the VALUES and filtered out
   * again, as the coverage suite's dense fixture keeps its columns nullable.
   */
  private static Source table(String columns, String nullRow, String... rows) {
    var values = new ArrayList<String>();
    for (int i = 0; i < rows.length; i++) {
      values.add("(" + i + ", " + rows[i] + ")");
    }
    if (nullRow != null) {
      values.add("(NULL, " + nullRow + ")");
    }
    return VarkaHarnessCases.source("SELECT * FROM VALUES " + String.join(", ", values)
        + " AS v(rid, " + columns + ")" + (nullRow != null ? " WHERE rid IS NOT NULL" : ""));
  }

  /** Each of {@code dates} a row of one nullable date column {@code d}, NULL for a null. */
  private static Source dateColumn(List<String> dates) {
    return table("d", null, dates.stream()
        .map(v -> v == null ? "CAST(NULL AS DATE)" : "DATE'" + v + "'").toArray(String[]::new));
  }

  private static int day(String date) {
    return (int) LocalDate.parse(date).toEpochDay();
  }

  /** `varka_dates`: four dates and a null, beside the int `i` the rows were numbered by. */
  private static final Source DATES = table("d, i", null,
      "DATE'2024-01-01', 0", "DATE'2024-01-02', 1", "DATE'2023-12-27', 2",
      "DATE'1969-12-31', 3", "CAST(NULL AS DATE), 4");

  /** `varka_date_pairs`: two dates, each null on its own row, and the row number. */
  private static final Source PAIRS = table("d, d2, i", null,
      "DATE'2024-03-01', DATE'2024-01-01', 0", "DATE'2024-01-01', DATE'2024-01-01', 1",
      "DATE'2023-12-01', DATE'2024-01-01', 2", "NULL, DATE'2024-01-01', 3",
      "DATE'2024-01-01', NULL, 4");

  /** The pairs whose dates are both there, the columns nullable as that suite's view's are. */
  private static final Source NULL_FREE = table("d, d2", "NULL, NULL",
      "DATE'2024-03-01', DATE'2024-01-01'", "DATE'2024-01-01', DATE'2024-01-01'",
      "DATE'2023-12-01', DATE'2024-01-01'");

  /** Five rows of two null dates. */
  private static final Source ALL_NULL = table("d, d2", null,
      Stream.generate(() -> "CAST(NULL AS DATE), CAST(NULL AS DATE)").limit(5)
          .toArray(String[]::new));

  /** `varka_ints_overflow`: `big` at and beside the int extremes, so a checked add overflows. */
  private static final Source INTS_OVERFLOW = table("d, i, big", null,
      "DATE'2024-01-01', 1, 2147483647", "DATE'2024-06-15', -1, -2147483648",
      "DATE'1999-12-31', 7, 2147483644", "DATE'2024-02-29', 0, 0",
      "DATE'2024-03-01', NULL, 2147483647", "NULL, 5, NULL");

  /** `varka_ints_safe`: the same shape with every value small, so no checked operation fires. */
  private static final Source INTS_SAFE = table("d, i, big", null,
      "DATE'2024-01-01', 1, 10", "DATE'2024-06-15', -3, -20", "DATE'1999-12-31', 7, 30",
      "DATE'2024-02-29', NULL, 40", "NULL, 5, NULL");

  /** `varka_dates_nullable_offset`: a date and an int offset, each null independently. */
  private static final Source NULLABLE_OFFSET = table("d, off", null,
      "DATE'2024-01-01', 3", "DATE'2024-01-02', NULL", "NULL, -5", "NULL, NULL",
      "DATE'1969-12-31', 100");

  /** The same rows with the offset stored as a day interval, out of the date lane. */
  private static final Source INTERVAL_COLUMN = VarkaHarnessCases.source(
      "SELECT rid, d, CAST(off AS INTERVAL DAY) AS iv FROM VALUES (0, DATE'2024-01-01', 3), "
      + "(1, DATE'2024-01-02', NULL), (2, NULL, -5), (3, NULL, NULL), (4, DATE'1969-12-31', 100) "
      + "AS v(rid, d, off)");

  /**
   * `varka_dates_intervals`: a year-month interval column per unit, from month counts that
   * straddle the month guard's range, and `ymm2`, whose `Int.MaxValue` overflows `ymm + ymm2`.
   */
  private static final Source INTERVALS = VarkaHarnessCases.source("""
      SELECT rid, d,
             CAST(m AS INTERVAL MONTH) AS ymm,
             CAST(y AS INTERVAL YEAR) AS ymy,
             make_ym_interval(y, mm) AS ym,
             CAST(m2 AS INTERVAL MONTH) AS ymm2,
             m, m2
      FROM VALUES
        (0, DATE'2024-01-31',  3,       1,  2, 10),
        (1, DATE'2024-02-29', -14,     -2,  0, -20),
        (2, DATE'2020-05-05',  24565,   3,  1, 2147483647),
        (3, DATE'1969-12-31', -300000,  0, 11, 5),
        (4, DATE'2024-01-01',  NULL,    1,  1, 100),
        (5, NULL,              7,       2,  3, NULL),
        (6, DATE'9999-12-01',  1,       0,  1, -3)
      AS t(rid, d, m, y, mm, m2)""");

  /** `varka_dates_far_offset`: two offsets of twenty million days beside in-range ones. */
  private static final Source FAR_OFFSET = table("d, d2, off, small", null,
      "DATE'2024-01-01', DATE'2024-01-01', 20000000, 3",
      "DATE'2024-01-02', DATE'2020-05-05', -20000000, -5",
      "DATE'2023-12-27', NULL, 3, NULL", "NULL, DATE'2024-01-01', NULL, 7",
      "DATE'1969-12-31', DATE'1969-12-31', 100, 100",
      "DATE'2024-02-29', DATE'2024-02-29', NULL, 0");

  /**
   * `varka_dates_guard_compose`: rows where the day guard and the month guard each pass and
   * their composition still leaves the range the calendar lowering is exact over.
   */
  private static final Source GUARD_COMPOSE = table("d, off, m", null,
      "DATE'1970-01-01', " + VarkaChrono.NARROW_MIN_DAYS + ", "
          + VarkaChrono.MONTH_ARITH_MIN_MONTHS,
      "DATE'1970-01-01', " + VarkaChrono.NARROW_MIN_DAYS + ", -1",
      "DATE'2024-03-15', 3, 12", "DATE'2024-03-15', NULL, 12", "NULL, 3, 12",
      "DATE'2024-03-15', 3, NULL");

  /** `varka_dates_narrow_ceiling`: day offsets landing on each end of the day guard's range. */
  private static final Source NARROW_CEILING = table("d, off", null,
      "DATE'1970-01-01', " + VarkaChrono.NARROW_MAX_DAYS,
      "DATE'1970-01-01', " + VarkaChrono.NARROW_MIN_DAYS,
      "DATE'2024-03-15', 3", "DATE'2024-03-15', NULL", "NULL, 3", "DATE'2024-02-29', 0");

  /**
   * `varka_date_months`: a month count `m` across both ends of the month guard and 30000 months
   * past each, and `m_small`, inside it on every row.
   */
  private static final Source DATE_MONTHS = table("d, m, m_small", null,
      "DATE'2024-01-01', 3, 3", "DATE'2024-01-02', NULL, -5", "NULL, -5, NULL", "NULL, NULL, 0",
      "DATE'1969-12-31', " + VarkaChrono.MONTH_ARITH_MAX_MONTHS + ", "
          + VarkaChrono.MONTH_ARITH_MAX_MONTHS,
      "DATE'1969-12-31', " + VarkaChrono.MONTH_ARITH_MIN_MONTHS + ", "
          + VarkaChrono.MONTH_ARITH_MIN_MONTHS,
      "DATE'2000-06-15', " + (VarkaChrono.MONTH_ARITH_MAX_MONTHS + 30000) + ", 12",
      "DATE'2000-06-15', " + (VarkaChrono.MONTH_ARITH_MIN_MONTHS - 30000) + ", -12");

  /** `varka_date_parts`: valid and invalid date parts, nulls in each, and the valid rows' date. */
  private static final Source DATE_PARTS = table("d, y, m, dd", null,
      "DATE'2024-01-01', 2024, 1, 1", "DATE'2024-02-29', 2024, 2, 29",
      "DATE'1969-12-31', 1969, 12, 31", "DATE'0001-01-01', 1, 1, 1",
      "DATE'9999-12-31', 9999, 12, 31", "NULL, 2023, 2, 29", "NULL, 2024, 4, 31",
      "NULL, 2024, 13, 1", "NULL, 2024, 6, 0", "NULL, NULL, 6, 15", "NULL, 2024, NULL, 15",
      "NULL, 2024, 6, NULL");

  /** The weekday names `varka_dates_weekday` holds, its non-names and nulls included. */
  private static final List<String> WEEKDAY_ROWS = List.of(
      "DATE'2024-01-01', DATE'2024-01-08', 'Mon'",
      "DATE'2024-01-02', DATE'2024-01-09', 'tuesday'",
      "DATE'2024-01-03', DATE'2024-01-10', 'WE'",
      "DATE'2024-01-04', DATE'2024-01-11', 'th'",
      "DATE'2024-01-05', DATE'2024-01-12', 'Fri'",
      "DATE'1969-12-31', DATE'1970-01-07', 'WEDNESDAY'",
      "DATE'2024-02-29', DATE'2024-03-07', 'thu'",
      "DATE'2024-01-10', DATE'2024-01-11', 'sunday'",
      "DATE'2024-01-06', NULL, 'xyz'",
      "DATE'2024-01-07', NULL, ''",
      "DATE'2024-01-08', NULL, ' MON'",
      "DATE'2024-01-09', DATE'2024-01-16', NULL",
      "NULL, DATE'2024-01-17', 'TUE'",
      "NULL, NULL, 'SAT'");

  private static final Source WEEKDAY =
      table("d, d2, s", null, WEEKDAY_ROWS.toArray(String[]::new));

  /** The weekday rows whose `d2` is there: every name valid. */
  private static final Source WEEKDAY_VALID = table("d, d2, s", "NULL, NULL, NULL",
      WEEKDAY_ROWS.stream().filter(r -> !r.contains(", NULL, '")).toArray(String[]::new));

  /** Non-names beside null dates only, which the row engine never parses. */
  private static final Source WEEKDAY_BAD_ON_NULLS = table("d, d2, s", "NULL, NULL, NULL",
      "DATE'2024-01-01', DATE'2024-01-08', 'Mon'", "NULL, DATE'2024-01-09', 'bogus'",
      "DATE'2024-01-03', DATE'2024-01-10', 'wednesday'", "NULL, NULL, ''",
      "DATE'2024-01-05', NULL, 'fri'");

  /** The weekday rows with `s` collated UTF8_LCASE. */
  private static final Source WEEKDAY_LCASE = VarkaHarnessCases.source(
      "SELECT rid, d, d2, CAST(s AS STRING COLLATE UTF8_LCASE) AS s FROM VALUES "
      + IntStream.range(0, WEEKDAY_ROWS.size())
          .mapToObj(i -> "(" + i + ", " + WEEKDAY_ROWS.get(i) + ")")
          .collect(Collectors.joining(", "))
      + " AS v(rid, d, d2, s)");

  /** `varka_dates_trunc_formats`: dates beside format strings, accepted and not, and nulls. */
  private static final Source TRUNC_FORMATS = table("d, fmt", null,
      "DATE'2024-03-15', 'YEAR'", "DATE'2024-03-15', 'yy'", "DATE'2024-03-15', 'Month'",
      "DATE'2024-03-15', 'mm'", "DATE'2024-03-15', 'QUARTER'", "DATE'2024-03-15', 'week'",
      "DATE'2024-03-15', 'HOUR'", "DATE'2024-03-15', 'QTR'", "DATE'2024-03-15', ''",
      "DATE'2024-03-15', NULL",
      "DATE'2023-12-31', 'yyyy'", "DATE'2023-12-31', 'MON'", "DATE'2023-12-31', 'quarter'",
      "DATE'2023-12-31', 'WEEK'", "DATE'2024-02-29', 'Week'", "DATE'2024-02-29', 'DD'",
      "DATE'1969-12-31', 'WEEK'", "DATE'1970-01-01', 'MONTH'", "DATE'2024-01-01', 'week'",
      "DATE'2024-07-01', 'Quarter'", "DATE'2024-05-20', 'MICROSECOND'",
      "DATE'2024-05-20', ' YEAR'", "DATE'0001-01-01', 'YEAR'", "DATE'9999-12-31', 'week'",
      "NULL, 'YEAR'", "NULL, 'xyz'", "NULL, NULL");

  /** An offset one day past the day-interval cast's limit beside in-range ones. */
  private static final Source FAR_INTERVAL = table("d, off", "NULL, NULL",
      "DATE'2024-01-01', 3",
      "DATE'2024-01-02', " + (VarkaChrono.INTERVAL_DAY_LIMIT_DAYS + 1),
      "NULL, 5");

  private static final String DATES_BIG_SCHEMA =
      "SELECT * FROM VALUES (0, CAST(NULL AS DATE), 0) AS v(rid, d, i)";

  /** `varka_dates_big`: `rows` days of 2020 round the year, one null every 17 rows. */
  private static Source datesBig(int rows) {
    var out = new ArrayList<Object[]>(rows);
    int start = day("2020-01-01");
    for (int i = 0; i < rows; i++) {
      out.add(new Object[] {i, i % 17 == 0 ? null : start + i % 365, i});
    }
    return VarkaHarnessCases.source(DATES_BIG_SCHEMA, out);
  }

  private static final Source DATES_1024 = datesBig(1024);

  /** The calendar family's boundary rows: leap days, centuries, the era, the range's ends. */
  private static final List<String> CALENDAR_DATES = java.util.Arrays.asList("2024-01-01",
      "2024-02-29", "2024-03-01", "2024-12-31", "1969-12-31", "1970-01-01", "1900-02-28",
      "1900-03-01", "2000-02-29", "2000-03-01", "1600-02-29", "1600-03-01", "0001-01-01",
      "9999-12-31", "2025-07-04", null);

  // ---------------------------------------------------------------------------------------------
  // The cases
  // ---------------------------------------------------------------------------------------------

  private static Shape shape(String select, Expect expect, String... explains) {
    return new Shape("SELECT " + select + " FROM t", expect, List.of(explains));
  }

  private static Shape where(String condition, Expect expect) {
    return new Shape("SELECT * FROM t WHERE " + condition, expect);
  }

  private static Part ansi(boolean ansi, Source source, Shape... shapes) {
    return new Part(source, Optional.of(ansi), UnaryOperator.identity(), List.of(shapes));
  }

  private static Part options(UnaryOperator<VarkaEmitOptions> options, Source source,
      Shape... shapes) {
    return new Part(source, Optional.empty(), options, List.of(shapes));
  }

  private static Part part(Source source, Shape... shapes) {
    return new Part(source, List.of(shapes));
  }

  /** {@code shapes} under each ANSI mode, off first. */
  private static List<Part> bothModes(Source source, Shape... shapes) {
    return List.of(ansi(false, source, shapes), ansi(true, source, shapes));
  }

  /** {@code n} date literals from 2023-12-27 in steps of three days, so some hit the table. */
  private static String literals(int n) {
    return IntStream.range(0, n)
        .mapToObj(k -> "DATE'" + LocalDate.of(2023, 12, 27).plusDays(k * 3L) + "'")
        .collect(Collectors.joining(", "));
  }

  private static final List<Case> CASES = cases();

  /** The cases, which {@link VarkaHarnessParitySuite} plans in a session too. */
  static List<Case> all() {
    return CASES;
  }

  private static List<Case> cases() {
    var out = new ArrayList<Case>();
    // Literal offsets, and the two that wrap the int32 day arithmetic: Spark's DateAdd is a
    // plain int add that wraps, and the harness's oracle is that expression, so the wrapped
    // days are compared as days rather than decoded as dates.
    var literalOffsets = new ArrayList<Shape>();
    for (int off : new int[] {0, 3, -5, 100, Integer.MAX_VALUE - 1, Integer.MIN_VALUE}) {
      literalOffsets.add(shape("date_add(d, " + off + ") AS a, date_sub(d, " + off + ") AS b",
          RUNS));
    }
    out.add(new Case("date_add and date_sub match the row engine across literal offsets", DATES,
        literalOffsets));
    out.add(new Case("date_add/date_sub with a column offset match the row engine", PAIRS,
        List.of(shape("date_add(d, i) AS a, d + i AS b, date_sub(d, i) AS c", RUNS))));
    out.add(new Case("a null offset nulls out its row even when the date beside it is not null",
        NULLABLE_OFFSET, List.of(shape("date_add(d, off) AS a, date_sub(d, off) AS b", RUNS))));
    // A non-foldable interval is rewritten to DateAdd(d, ExtractANSIIntervalDays(...)), and the
    // int-cast form is VARKA-38's column-offset node under a per-batch bound (VARKA-56).
    out.add(new Case("an int column cast to a day interval fuses as the column offset it is",
        DATES, List.of(shape("d + CAST(i AS INTERVAL DAY) AS a", RUNS))));
    // The mask kernel's side of the column-offset path: the predicate fuses whole.
    out.add(new Case("a column-offset date_add fuses inside a filter predicate too", PAIRS,
        List.of(where("date_add(d, i) > d2", WHOLE))));
    // The type's admission: `d + ym` is VARKA-60's column-count add_months with the same guard,
    // so the rows outside MONTH_ARITH_MIN/MAX_MONTHS decline their batch; the rest of the
    // surface Spark's typing reaches fuses; and the YEAR cast of an unbounded int column stays
    // residual for its checked multiply by twelve.
    out.add(new Case("a year-month interval column fuses in every unit, and its far counts "
        + "decline", INTERVALS, List.of(
            shape("d + ymm AS a", DECLINES), shape("d + ymy AS a", FUSED),
            shape("d + ym AS a", FUSED),
            where("ymm > INTERVAL '6' MONTH", FUSED),
            where("ymm IN (INTERVAL '3' MONTH, INTERVAL '7' MONTH)", FUSED),
            shape("greatest(ymm, INTERVAL '0' MONTH) AS a", FUSED),
            shape("coalesce(ymm, INTERVAL '0' MONTH) AS a", FUSED),
            shape("CASE WHEN d < DATE'2024-01-01' THEN ymm ELSE INTERVAL '1' MONTH END AS a",
                FUSED),
            shape("CAST(m AS INTERVAL MONTH) AS a", FUSED), shape("CAST(ymm AS INT) AS a", FUSED),
            shape("d - ymm AS a", FUSED),
            shape("d + CAST(m AS INTERVAL YEAR) AS a", NOT_FUSED))));
    // Group A, the interval-to-interval arithmetic: Spark computes every one with addExact and
    // its kin whatever the ANSI mode, so both modes fuse alike and nothing declines. Group C,
    // the same expressions as a month count, declines the far rows' batch in both. A checked
    // multiply over an unbounded column, and try_add, stay on the row engine.
    var algebra = new ArrayList<Part>();
    for (boolean mode : new boolean[] {false, true}) {
      algebra.add(ansi(mode, INTERVALS,
          shape("ymm - ymm2 AS a", SERVED), shape("ymm + ymy AS a, ymm - ym AS b", SERVED),
          shape("-ymm AS a, abs(ymm) AS b", SERVED),
          shape("CAST(month(d) AS INTERVAL YEAR) * 3 AS a", SERVED),
          shape("make_ym_interval(year(d), month(d)) AS a", SERVED),
          shape("d - CAST(m AS INTERVAL MONTH) AS a", FUSED),
          shape("d + CAST(month(d) AS INTERVAL YEAR) AS a", FUSED),
          shape("ymm * 3 AS a", NOT_FUSED), shape("try_add(ymm, ymm2) AS a", NOT_FUSED)));
    }
    out.add(new Case("the year-month interval algebra fuses and agrees in both ANSI modes",
        algebra));
    // The kernel's sign test condemns the batch holding Int.MaxValue months, so no answered
    // batch holds the row Spark raises on, in both modes.
    out.add(new Case("an overflowing interval add raises the row engine's own error in both "
        + "modes", bothModes(INTERVALS, shape("ymm + ymm2 AS a", FUSED))));
    // Each shape twice: the condition routing the extreme rows to the arm without the guarded
    // node, where nothing declines, and into it, where the batch declines - or, for the checked
    // add, the batch Spark raises on is not answered.
    out.add(new Case("a guard under a CASE arm condemns from the taken arm only", List.of(
        part(FAR_OFFSET,
            shape("CASE WHEN d < DATE'2000-01-01' THEN year(date_add(d, off)) ELSE year(d) END "
                + "AS a", SERVED),
            shape("CASE WHEN d >= DATE'2000-01-01' THEN year(date_add(d, off)) ELSE year(d) END "
                + "AS a", DECLINES)),
        part(INTS_OVERFLOW,
            shape("CASE WHEN d = DATE'2024-02-29' THEN big + i ELSE year(d) END AS a", SERVED),
            shape("CASE WHEN d <> DATE'2024-02-29' THEN big + i ELSE year(d) END AS a",
                FUSED)))));
    // The compile-time half of the range guard: the far shift is residual with its reason, the
    // near entry fuses, and the largest shift the analysis admits fuses and answers.
    int shiftHi = VarkaChrono.NARROW_MAX_DAYS - VarkaChrono.CONTRACT_MAX_DAYS;
    out.add(new Case("a literal day shift past the calendar range is residual, with its reason, "
        + "and an in-range one fuses", DATES, List.of(
            shape("year(date_add(d, 20000000)) AS far, year(d) AS near", RUNS,
                "far: residual (day range [", "near: fused"),
            shape("year(date_add(d, " + shiftHi + ")) AS y, month(date_sub(d, 30)) AS m",
                RUNS))));
    // The runtime half: the far rows put date_add(d, off) twenty million days past the range,
    // the producer's guard declines the batch, and an in-range offset never declines.
    out.add(new Case("a column-offset date_add under a calendar node declines the batch that "
        + "leaves the range, and the row engine answers it", FAR_OFFSET, List.of(
            shape("year(date_add(d, off)) AS y, month(date_sub(d, off)) AS m, "
                + "dayofmonth(d + off) AS dm", DECLINES),
            shape("year(date_add(d, small)) AS y, month(date_sub(d, small)) AS m", SERVED))));
    // VARKA-46's other arm, the general validity pair, against the specialised one.
    var widths = new ArrayList<Part>();
    for (boolean byWidth : new boolean[] {true, false}) {
      widths.add(options(o -> o.withValidityByWidth(byWidth), NULLABLE_OFFSET,
          shape("year(date_add(d, off)) AS y, dayofweek(d) AS w", RUNS),
          where("date_add(d, off) > DATE'2000-01-01'", RUNS)));
    }
    out.add(new Case("the width-specialised validity helpers answer what the general pair did",
        widths));
    // 5000 rows at the default 4096-row batch: one batch of 64 whole words, one of fourteen
    // words and eight rows. A blend, a filter, the filter's projection, and a shape the word
    // writer must not touch, under both settings.
    Source dates5000 = datesBig(5000);
    var words = new ArrayList<Part>();
    for (boolean byWord : new boolean[] {false, true}) {
      words.add(options(o -> o.withValidityByWord(byWord), dates5000,
          shape("greatest(d, date_add(d, i)) AS g", RUNS),
          where("d > DATE'2020-06-01'", RUNS),
          shape("greatest(d, date_add(d, i)) AS g, i", RUNS),
          shape("year(d) AS y, month(d) AS m", RUNS)));
    }
    out.add(new Case("the word writer answers what the per-group write answered, over batches "
        + "whose length is not a whole number of words", words));
    // One query per shape the bitmap pass treats differently, on two nullable tables.
    var bitmaps = new ArrayList<Part>();
    for (boolean on : new boolean[] {true, false}) {
      bitmaps.add(options(o -> o.withValidityByBitmap(on), NULLABLE_OFFSET,
          shape("year(d) AS y, month(d) AS m, day(d) AS dd, quarter(d) AS q", RUNS),
          shape("year(date_add(d, off)) AS y, dayofweek(d) AS w", RUNS),
          shape("greatest(d, date_add(d, off)) AS g", RUNS),
          shape("datediff(d, date_add(d, off)) AS dd", RUNS),
          where("date_add(d, off) > DATE'2000-01-01'", RUNS)));
      bitmaps.add(options(o -> o.withValidityByBitmap(on), PAIRS,
          shape("greatest(d, d2) AS g, least(d, d2) AS l", RUNS)));
    }
    out.add(new Case("the bitmap pass answers what the per-group write answered, end to end, "
        + "over nulls on either side", bitmaps));
    out.add(new Case("the producer guard reaches a filter predicate through the same route",
        FAR_OFFSET, List.of(where("year(date_add(d, off)) = year(d2)", DECLINES))));
    // `m` declines the batch in full, so its values are the row engine's on both sides;
    // `m_small`, inside the bound on every row, runs the guarded kernel.
    out.add(new Case("a column month count matches the row engine, declining the batch whose "
        + "count leaves the emitter's guarded range - the same route as VARKA-52's day producer",
        DATE_MONTHS, List.of(
            shape("add_months(d, m) AS a, d + CAST(m AS INTERVAL MONTH) AS b", DECLINES),
            shape("add_months(d, m_small) AS a, d + CAST(m_small AS INTERVAL MONTH) AS b",
                SERVED))));
    // VARKA-93: a check between the month add and the decomposition bounds the day the calendar
    // tail reads, so the entry fuses and the batch whose composition leaves the range declines.
    out.add(new Case("a calendar function over a column count above a column day offset is "
        + "fused, and the composition it cannot bound declines the batch instead", GUARD_COMPOSE,
        List.of(shape("year(add_months(date_add(d, off), m)) AS y, date_add(d, 1) AS keep",
            DECLINES, "y: fused", "keep: fused"))));
    // VARKA-69: `headroom` days above NARROW_MAX_DAYS is NARROW_DECOMPOSE_MAX_DAYS, the last day
    // the narrowed lowering decomposes; one more is residual with the interval it reports.
    int headroom = VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS - VarkaChrono.NARROW_MAX_DAYS;
    out.add(new Case("a shift above a guarded day offset fuses up to the decomposition's own "
        + "ceiling, and is residual one day past it", NARROW_CEILING, List.of(
            shape("year(date_add(date_add(d, off), " + headroom + ")) AS y, "
                + "month(date_add(date_add(d, off), " + headroom + ")) AS m", SERVED),
            shape("year(date_add(date_add(d, off), " + (headroom + 1) + ")) AS y, "
                + "date_add(d, 1) AS keep", RUNS,
                "residual (day range [" + (VarkaChrono.NARROW_MIN_DAYS + headroom + 1) + ", "
                    + (VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS + 1) + "]", "keep: fused"),
            shape("year(date_sub(date_add(d, off), 1)) AS y, date_add(d, 1) AS keep", RUNS,
                "y: residual (day range ["))));
    out.add(new Case("the producer's own guard reaches a filter predicate through the same "
        + "route as VARKA-52's day producer", DATE_MONTHS,
        List.of(where("year(add_months(d, m)) = year(d)", DECLINES))));
    out.add(new Case("an existing nullable int column, in range, still nulls its row on either "
        + "side independently when read as a month count, and the guard stays silent",
        NULLABLE_OFFSET, List.of(shape("add_months(d, off) AS a", SERVED))));
    out.add(new Case("date +- CAST(i AS INTERVAL DAY) matches the row engine on the projection "
        + "and filter paths, and a stored interval column stays residual", List.of(
            part(NULLABLE_OFFSET,
                shape("d + CAST(off AS INTERVAL DAY) AS a, d - CAST(off AS INTERVAL DAY) AS b",
                    RUNS),
                where("d + CAST(off AS INTERVAL DAY) > DATE'2024-01-01'", WHOLE)),
            part(INTERVAL_COLUMN, shape("d + iv AS a", NOT_FUSED)))));
    // Spark's CAST(i AS INTERVAL DAY) throws past INTERVAL_DAY_LIMIT_DAYS in every mode; the
    // batch holding that row is not the kernel's to answer.
    out.add(new Case("an offset past the cast's limit raises the row engine's error through "
        + "Varka", FAR_INTERVAL, List.of(shape("d + CAST(off AS INTERVAL DAY) AS a", FUSED))));
    out.add(new Case("datediff matches the row engine in both argument orders with nulls", PAIRS,
        List.of(shape("datediff(d2, d) AS diff", RUNS), shape("datediff(d, d2) AS diff", RUNS))));
    out.add(new Case("datediff matches the row engine on null-free and all-null inputs", List.of(
        part(NULL_FREE, shape("datediff(d2, d) AS diff", RUNS)),
        part(ALL_NULL, shape("datediff(d2, d) AS diff", RUNS)))));
    // One ineligible entry does not poison the projection: the date entry runs on the kernel,
    // the bare `i` forwards, and `i % 7` is evaluated per row beside them.
    out.add(new Case("a mixed-eligibility projection fuses partially and matches the row engine",
        DATES, List.of(shape("date_add(d, 3) AS a, i, i % 7 AS inc", RUNS))));
    out.add(new Case("a projection of forwards and residuals alone stays unfused", DATES,
        List.of(shape("i, i % 7 AS inc", NOT_FUSED))));
    out.add(new Case("a predicated entry fuses beside residual and forwarded ones", PAIRS,
        List.of(shape("CASE WHEN d < d2 THEN date_add(d, 1) ELSE d2 END AS a, i, i % 7 AS inc",
            RUNS))));
    out.add(new Case("constant-folded offsets are fused and match the row engine", DATES,
        List.of(shape("date_add(d, 1 + 2) AS a", RUNS),
            shape("date_add(d, CAST(3 AS INT)) AS a", RUNS))));
    out.add(new Case("nested date expressions are fused and match the row engine", DATES,
        List.of(shape("date_add(date_add(d, 1), 2) AS a", RUNS),
            shape("date_sub(date_add(d, 5), 5) AS a", RUNS),
            shape("date_sub(date_add(date_sub(date_add(d, 1), 2), 3), 4) AS a", RUNS))));
    out.add(new Case("datediff over nested chains is fused in both argument orders with nulls",
        PAIRS, List.of(shape("datediff(date_add(d, 7), d2) AS diff", RUNS),
            shape("datediff(d2, date_sub(d, 7)) AS diff", RUNS),
            shape("datediff(date_add(d, 3), date_sub(d2, 3)) AS diff", RUNS))));
    out.add(new Case("a shared subchain across outputs is fused and matches the row engine",
        PAIRS, List.of(shape("date_add(d, 1) AS a, datediff(date_add(d, 1), d2) AS b", RUNS))));
    // The inner add leaves the date range and the outer sub wraps it back.
    out.add(new Case("a nested chain wraps int32 day arithmetic exactly like the row engine",
        DATES, List.of(shape("date_sub(date_add(d, " + (Integer.MAX_VALUE - 1) + "), "
            + (Integer.MAX_VALUE - 1) + ") AS a", RUNS))));
    out.add(new Case("a projection with a bare date column fuses and forwards the column "
        + "zero-copy", DATES, List.of(shape("date_add(d, 3) AS a, d", RUNS))));
    // Three branches, the first match winning; a CASE with no ELSE has a null-literal branch
    // and stays on the row engine.
    out.add(new Case("CASE WHEN over dates is fused and matches the row engine, three-valued "
        + "nulls included", PAIRS, List.of(
            shape("CASE WHEN d < d2 THEN date_add(d, 3) ELSE date_sub(d2, 1) END AS a", RUNS),
            shape("CASE WHEN d < d2 THEN d WHEN d = d2 THEN date_add(d, 1) "
                + "ELSE date_sub(d2, 2) END AS a", RUNS),
            shape("CASE WHEN d < d2 THEN date_add(d, 3) END AS a", NOT_FUSED))));
    out.add(new Case("IF with BETWEEN and date literals is fused and matches the row engine",
        DATES, List.of(shape("IF(d BETWEEN DATE'2023-12-01' AND DATE'2024-01-01', "
            + "date_add(d, 7), d) AS a", RUNS))));
    // 5 literals arrive as In, 16 as InSet; duplicates collapse before the cap is counted;
    // above the cap, or with a non-literal element, the entry stays on the row engine.
    out.add(new Case("IN over date literals fuses to the cap and declines above it", List.of(
        part(DATES,
            shape("CASE WHEN d IN (" + literals(5) + ") THEN date_add(d, 1) ELSE d END AS a",
                RUNS),
            shape("CASE WHEN d IN (" + literals(16) + ") THEN date_add(d, 1) ELSE d END AS a",
                RUNS),
            shape("CASE WHEN d IN (" + literals(5) + ", " + literals(5) + ") THEN d "
                + "ELSE date_add(d, 2) END AS a", RUNS),
            shape("CASE WHEN d IN (" + literals(17) + ") THEN date_add(d, 1) ELSE d END AS a",
                NOT_FUSED),
            shape("CASE WHEN d IN (" + literals(50) + ") THEN date_add(d, 1) ELSE d END AS a",
                NOT_FUSED)),
        part(PAIRS, shape("CASE WHEN d IN (d2, " + literals(3) + ") THEN d "
            + "ELSE date_add(d, 4) END AS a", NOT_FUSED)))));
    // A computed operand before the last cannot be guarded and declines.
    out.add(new Case("coalesce, nvl, ifnull and nvl2 fuse and match the row engine", PAIRS,
        List.of(shape("coalesce(d, d2) AS a", RUNS),
            shape("coalesce(d, d2, DATE'1999-09-09') AS a", RUNS),
            shape("nvl(d, date_add(d2, 1)) AS a", RUNS), shape("ifnull(d, d2) AS a", RUNS),
            shape("nvl2(d, date_add(d2, 3), date_sub(d2, 3)) AS a", RUNS),
            shape("coalesce(date_add(d, 1), d2) AS a", NOT_FUSED))));
    out.add(new Case("coalesce over all-null and null-free inputs", List.of(
        part(NULL_FREE, shape("coalesce(d, d2) AS a", RUNS)),
        part(ALL_NULL, shape("coalesce(d, d2) AS a", RUNS)))));
    out.add(new Case("IS NULL and IS NOT NULL fuse as conditions, connectives included", PAIRS,
        List.of(shape("CASE WHEN d IS NULL THEN d2 ELSE d END AS a", RUNS),
            shape("CASE WHEN d IS NOT NULL AND d < d2 THEN date_add(d, 1) ELSE d2 END AS a",
                RUNS),
            shape("IF(d IS NULL OR d2 IS NULL, DATE'1970-01-01', greatest(d, d2)) AS a",
                RUNS))));
    out.add(new Case("cast-wrapped date expressions fuse, folded or unwrapped before the kernel",
        DATES, List.of(shape("date_add(CAST(d AS DATE), 2) AS a, "
            + "IF(d < CAST('2024-01-02' AS DATE), d, date_sub(d, 1)) AS b", RUNS))));
    // date_from_unix_date's child is a bare int column, which no leaf reads.
    out.add(new Case("unix_date and date_from_unix_date fuse as a pure relabel", DATES,
        List.of(shape("unix_date(d) AS u", RUNS),
            shape("date_from_unix_date(i) AS x", NOT_FUSED),
            shape("date_add(d, 1) AS a, unix_date(d) AS b", RUNS))));
    out.add(new Case("AND, OR and NOT conditions follow three-valued logic like the row engine",
        PAIRS, List.of(
            shape("CASE WHEN NOT(d < d2) OR d = d2 THEN date_add(d, 1) ELSE d2 END AS a", RUNS),
            shape("CASE WHEN d <= d2 AND NOT(d = d2) THEN d ELSE date_sub(d2, 3) END AS a",
                RUNS))));
    out.add(new Case("greatest and least skip nulls and fuse, nested chains included", PAIRS,
        List.of(shape("greatest(d, d2) AS a, least(d, d2) AS b", RUNS),
            shape("greatest(date_add(d, 7), d2) AS a, least(d, d2, DATE'2024-01-15') AS b",
                RUNS))));
    // December 28 to January 4 of years whose week 1 starts in the year before and of years where
    // it does not, the century years and the range's ends, with a null.
    var turns = new ArrayList<String>();
    for (int y : new int[] {2004, 2018, 2020, 2022, 2026}) {
      for (int dd = 28; dd <= 31; dd++) {
        turns.add(y + "-12-" + dd);
      }
      for (int dd = 1; dd <= 4; dd++) {
        turns.add((y + 1) + "-01-0" + dd);
      }
    }
    turns.addAll(java.util.Arrays.asList("1900-01-01", "1900-12-31", "2000-01-01", "2000-12-31",
        "0001-01-01", "9999-12-31", null));
    out.add(new Case("extract(YEAROFWEEK) matches the row engine on the rows the ISO year moves "
        + "on, beside weekofyear and year, under both spellings and on the filter path",
        dateColumn(turns), List.of(
            shape("extract(YEAROFWEEK FROM d) AS y, date_part('YEAROFWEEK', d) AS y2, "
                + "weekofyear(d) AS w, year(d) AS a", RUNS),
            where("extract(YEAROFWEEK FROM d) <> year(d)", RUNS))));
    // Invalid dates are NULL with ANSI off; with it on, the batch holding them is not the
    // kernel's to answer, and the valid rows' date feeds further work.
    out.add(new Case("make_date matches the row engine in both modes - nulls for invalid dates "
        + "with ANSI off, the row engine's error with ANSI on, the date feeding further work",
        List.of(
            ansi(false, DATE_PARTS,
                shape("make_date(y, m, dd) AS a, year(make_date(y, m, dd)) AS b, "
                    + "date_add(make_date(y, m, dd), 7) AS c, make_date(y, 2, 29) AS e", RUNS),
                where("make_date(y, m, dd) = d", RUNS)),
            ansi(true, DATE_PARTS, shape("make_date(y, m, dd) AS a", FUSED)))));
    // Every day from 1990-12-20 to 2030-01-10, beside dayofyear and year, and the Velox
    // Spark-compatibility fixtures with a null; week 53 exists in five years of the span.
    var isoDays = new ArrayList<Object[]>();
    int first = day("1990-12-20");
    for (int k = 0; k < 14631; k++) {
      isoDays.add(new Object[] {k, first + k});
    }
    Source days = VarkaHarnessCases.source(
        "SELECT * FROM VALUES (0, DATE'2000-01-01') AS v(rid, d)", isoDays);
    out.add(new Case("weekofyear matches the row engine on every day across forty year "
        + "boundaries, at Velox's fixtures, under every spelling and on the filter path", List.of(
            part(days, shape("weekofyear(d) AS w, dayofyear(d) AS y, year(d) AS a", RUNS),
                where("weekofyear(d) = 53", RUNS)),
            part(dateColumn(java.util.Arrays.asList("1919-12-31", "1969-12-31", "1960-01-01",
                    "0001-01-01", "9999-12-31", "2020-12-31", "2004-12-31", "2016-12-31",
                    "2016-01-01", "2019-12-30", null)),
                shape("weekofyear(d) AS w, EXTRACT(WEEKS FROM d) AS w2, "
                    + "EXTRACT(WEEK FROM d) AS w3, date_part('W', d) AS w4", RUNS)))));
    // weekday(d) + 2 fuses too, as int arithmetic over the weekday field rather than as the ISO
    // node, and the value is what says so.
    out.add(new Case("extract(DAYOFWEEK_ISO), date_part('DOW_ISO') and weekday(d) + 1 match the "
        + "row engine as one node, with nulls", DATES, List.of(
            shape("extract(DAYOFWEEK_ISO FROM d) AS a, date_part('DOW_ISO', d) AS b, "
                + "weekday(d) + 1 AS c, dayofweek(d) AS e, weekday(d) AS f", RUNS),
            shape("weekday(d) + 2 AS a", RUNS))));
    // The TPC-H q7/q8/q9 shape, year(date) beside a filter on the same column, is the filter
    // and the projection.
    out.add(new Case("the calendar extractions match the row engine across the Gregorian range",
        dateColumn(CALENDAR_DATES), List.of(
            shape("year(d) AS a, month(d) AS b, dayofmonth(d) AS c, quarter(d) AS e, "
                + "dayofyear(d) AS g, year(date_add(d, 1)) AS f", RUNS),
            shape("EXTRACT(YEAR FROM d) AS a, EXTRACT(QUARTER FROM d) AS b", RUNS),
            where("d >= DATE '1900-01-01'", RUNS), shape("year(d) AS a", RUNS))));
    // A format the date form does not cover is a NULL column, which the entry leaves residual.
    out.add(new Case("trunc matches the row engine at every date level, and its date output "
        + "feeds further arithmetic in the same chain", dateColumn(java.util.Arrays.asList(
            "2024-01-01", "2024-01-07", "2024-01-08", "2024-02-29", "2024-03-01", "2024-04-01",
            "2024-06-30", "2024-07-01", "2024-09-30", "2024-10-01", "2024-12-31", "2023-01-01",
            "2023-01-02", "2023-12-31", "2025-01-05", "2025-01-06", "1969-12-31", "1970-01-01",
            "1900-02-28", "1900-03-01", "2000-02-29", "0001-01-01", "9999-12-31", null)),
        List.of(
            shape("trunc(d, 'YEAR') AS y, trunc(d, 'MONTH') AS m, trunc(d, 'QUARTER') AS q, "
                + "trunc(d, 'WEEK') AS w, date_add(trunc(d, 'MONTH'), 5) AS m5", RUNS),
            shape("trunc(d, 'yyyy') AS a, trunc(d, 'yy') AS b, trunc(d, 'mon') AS c, "
                + "trunc(d, 'mm') AS e", RUNS),
            shape("trunc(d, 'HOUR') AS h, trunc(d, 'QTR') AS q", NOT_FUSED))));
    // Two far-future century years no DATE literal can name: 14500, not divisible by 400, and
    // 14400, which is - where a leap test whose constants overflowed a lane would go wrong.
    // The two are a table of their own: past the range the calendar lowering is exact over,
    // they decline the batch they are in, as they do in that suite's last partition.
    Shape lastDay = shape("last_day(d) AS a, date_add(last_day(d), 1) AS b", RUNS);
    out.add(new Case("last_day matches the row engine across the Gregorian range", List.of(
        part(dateColumn(CALENDAR_DATES), lastDay),
        part(table("d", "NULL", "make_date(14500, 2, 8)", "make_date(14400, 2, 29)"),
            shape("last_day(d) AS a, date_add(last_day(d), 1) AS b", FUSED)))));
    out.add(new Case("dayofweek and weekday match the row engine across 1970 and nulls",
        dateColumn(java.util.Arrays.asList("2024-01-01", "1969-12-31", "1969-01-05",
            "1900-02-28", "2100-07-04", null)),
        List.of(shape("dayofweek(d) AS a, weekday(d) AS b, dayofweek(date_add(d, 1)) AS c",
            RUNS))));
    // THURSDAY maps to k = -1, the one weekday whose runtime literal is negative.
    out.add(new Case("next_day matches the row engine across 1970, pre-1970 and nulls",
        dateColumn(java.util.Arrays.asList("2024-01-01", "1969-12-31", "1970-01-01",
            "1900-02-28", "2100-07-04", null)),
        List.of(shape("next_day(d, 'MO') AS a, next_day(d, 'SUNDAY') AS b, "
            + "next_day(d, 'THURSDAY') AS c", RUNS))));
    // A non-name is a NULL with ANSI off, and the leaf is the kernel path, not a repair.
    out.add(new Case("next_day with a weekday column matches the row engine over every "
        + "spelling, the non-names and nulls, through the projection and the filter, with no "
        + "fallback", List.of(ansi(false, WEEKDAY,
            shape("next_day(d, s) AS a, next_day(d2, s) AS b, next_day(d, 'MO') AS c", SERVED),
            where("next_day(d, s) = d2", RUNS), where("next_day(d, s) < d2", RUNS)))));
    // Every name valid: nothing declines. The whole table: the batch holding the first
    // non-name beside a live date is not the kernel's. A non-name beside a null date is NULL,
    // and the leaf declines rather than parse it. The error a user sees stays end to end.
    out.add(new Case("under ANSI the valid rows fuse, a non-name beside a live date raises the "
        + "row engine's own error, and one beside a null date is NULL with no error", List.of(
            ansi(true, WEEKDAY_VALID,
                shape("next_day(d, s) AS a, next_day(d2, s) AS b", SERVED)),
            ansi(true, WEEKDAY, shape("next_day(d, s) AS a", FUSED)),
            ansi(true, WEEKDAY_BAD_ON_NULLS, shape("next_day(d, s) AS a", DECLINES)))));
    // Values that cannot overflow, so every mode takes the same path.
    out.add(new Case("int arithmetic fuses in all three modes and agrees with the row engine",
        bothModes(INTS_SAFE, shape("i + 1 AS a, i - 1 AS b, big + i AS c", SERVED),
            shape("-i AS a, -big AS b", SERVED), shape("year(d) * 100 + month(d) AS k", SERVED),
            shape("datediff(d, DATE'2000-01-01') + 1 AS a", SERVED))));
    // `year(d) * 100` is bounded by the narrowed range; `i * 3` over an unbounded int column
    // has no int-lane overflow test, so ANSI leaves it on the row engine.
    out.add(new Case("a multiply fuses only where the operands' bounds rule out overflow",
        List.of(ansi(false, INTS_SAFE, shape("year(d) * 100 AS a", RUNS),
                shape("i * 3 AS a", RUNS)),
            ansi(true, INTS_SAFE, shape("year(d) * 100 AS a", RUNS),
                shape("i * 3 AS a", NOT_FUSED)))));
    // The kernel's sign test condemns the batch, and negation overflows on one value.
    out.add(new Case("under ANSI an overflowing row raises the row engine's own error",
        List.of(ansi(true, INTS_OVERFLOW, shape("big + i AS a", FUSED),
            shape("-big AS a", FUSED)))));
    // Each lost its ANSI check to a wrong bound once: the multiply now declines at compile
    // time, and the add keeps the check the bound does not remove. With ANSI off both wrap
    // alike.
    out.add(new Case("the shapes whose bound was a fiction raise the row engine's error, not an "
        + "answer", List.of(
            ansi(true, DATES, shape("quarter(d) * 536870912 AS a", NOT_FUSED),
                shape("datediff(date_add(d, 2147483647), d) + 1 AS a", FUSED)),
            ansi(false, DATES, shape("quarter(d) * 536870912 AS a, "
                + "datediff(date_add(d, 2147483647), d) + 1 AS b", RUNS)))));
    // The TRY mode nulls the overflowing lane rather than declining.
    out.add(new Case("try_add gives NULL for the row that would have raised, and the batch runs "
        + "on", bothModes(INTS_OVERFLOW,
            shape("try_add(big, i) AS a, try_subtract(big, i) AS b", SERVED))));
    out.add(new Case("without ANSI an overflowing row wraps exactly as the row engine wraps",
        List.of(ansi(false, INTS_OVERFLOW,
            shape("big + i AS a, big - i AS b, -big AS c", SERVED)))));
    // The offset is an expression, and a calendar node over it leans on the runtime range
    // guard; a multiplied offset fuses where it wraps and not where ANSI wants a check.
    var dayOffsets = new ArrayList<>(bothModes(NULLABLE_OFFSET,
        shape("date_add(d, off + 1) AS a, date_sub(d, off - 1) AS b", RUNS),
        shape("year(date_add(d, off + 1)) AS a", RUNS)));
    dayOffsets.add(ansi(false, NULLABLE_OFFSET, shape("date_add(d, off * 7) AS a", RUNS)));
    dayOffsets.add(ansi(true, NULLABLE_OFFSET, shape("date_add(d, off * 7) AS a", NOT_FUSED)));
    out.add(new Case("arithmetic is admitted as a day offset, and a calendar node over it is "
        + "guarded rather than declined", dayOffsets));
    out.add(new Case("a collated weekday column is admitted and parsed the same way",
        List.of(ansi(false, WEEKDAY_LCASE, shape("next_day(d, s) AS a", RUNS)))));
    // A calendar node reaches a filter's mask kernel as readily as a projection's; the third
    // query is the filter and the projection over the same columns.
    out.add(new Case("a calendar node inside a fused predicate is computed like any other",
        PAIRS, List.of(where("year(d) = year(d2)", WHOLE),
            where("year(d) = year(d2) AND month(d) >= month(d2)", RUNS),
            where("month(d) = month(d2)", RUNS), shape("year(d) AS y", RUNS))));
    // The null rows, one in 17, are dropped by every compilable predicate; none selected, all
    // selected, and exactly the null rows.
    out.add(new Case("null-as-false, and the boundary selectivities", DATES_1024, List.of(
        where("d >= DATE'1900-01-01'", RUNS),
        where("d > DATE'2020-01-05' AND d < DATE'2020-01-06'", RUNS),
        where("d IS NULL OR d IS NOT NULL", RUNS), where("d IS NULL", RUNS))));
    // Same structure, different op kind; same shape, different constant; one more literal slot.
    out.add(new Case("near-miss shapes back to back in the warm cache stay distinct", DATES,
        List.of(shape("date_add(d, 5) AS a", RUNS), shape("date_sub(d, 5) AS a", RUNS),
            shape("date_add(d, 30) AS a", RUNS),
            shape("date_add(d, 5) AS a, date_add(d, 6) AS b", RUNS))));
    // TruncDate has no error path, so a null, unrecognised or sub-day format is NULL in both
    // modes and nothing declines.
    out.add(new Case("trunc with a format column matches the row engine over every spelling, "
        + "the sub-day and unrecognised formats and nulls, through the projection and the "
        + "filter, composed under a calendar function, with no fallback, in both ANSI modes",
        bothModes(TRUNC_FORMATS,
            shape("trunc(d, fmt) AS a, trunc(d, 'MONTH') AS b", SERVED),
            where("trunc(d, fmt) = trunc(d, 'MONTH')", RUNS),
            where("trunc(d, fmt) < trunc(d, 'MONTH')", RUNS),
            shape("year(trunc(d, fmt)) AS y, dayofweek(trunc(d, fmt)) AS w", RUNS))));
    // Every value against every count: the counts straddle both masks and the values hold the
    // sign bit; a null in each operand.
    var shiftRows = new ArrayList<String>();
    for (String l : List.of("0L", "1L", "-1L", "9223372036854775807L",
        "-9223372036854775808L", "4294967296L", "-4294967297L", "6148914691236517205L",
        "CAST(NULL AS BIGINT)")) {
      for (String c : List.of("-1", "0", "1", "31", "32", "33", "63", "64", "65", "127", "128",
          String.valueOf(Integer.MIN_VALUE), String.valueOf(Integer.MAX_VALUE),
          "CAST(NULL AS INT)")) {
        shiftRows.add(l + ", " + c);
      }
    }
    var shifts = new ArrayList<Shape>();
    for (String e : List.of("shiftleft(l, c)", "shiftright(l, c)", "shiftrightunsigned(l, c)",
        "shiftleft(l, c ^ 1)", "shiftrightunsigned(l ^ 255L, c)", "bit_count(l) + 1",
        "bit_count(l) & c", "shiftleft(l, bit_count(l))", "shiftright(l, bit_count(l >> 7))",
        "bit_count(shiftleft(l, c))")) {
      shifts.add(shape("l, c, " + e + " AS r", RUNS));
    }
    out.add(new Case("a bigint shifted by an int column and bit_count of a bigint under another "
        + "node match the row engine for every edge count and value, nulls in either operand",
        table("l, c", null, shiftRows.toArray(String[]::new)), shifts));
    // VARKA-480: the bigint entry cannot share the first kernel's narrow lane, so it fuses in a
    // second kernel rather than going to the row engine.
    out.add(new Case("a projection of a narrow entry and then a bigint one answers from a kernel "
        + "per lane", table("y, l", null, "CAST(-50 AS TINYINT), 0L",
            "CAST(50 AS TINYINT), 4294967296L", "CAST(NULL AS TINYINT), -5L",
            "CAST(7 AS TINYINT), CAST(NULL AS BIGINT)",
            "CAST(NULL AS TINYINT), CAST(NULL AS BIGINT)"), List.of(
            shape("y + y AS a, greatest(l, 5000000000) AS b", RUNS, "kernel 2 of 2"),
            shape("CAST(y AS INT) + 1 AS a, greatest(l, 5000000000) AS b", RUNS,
                "kernel 2 of 2"))));
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
