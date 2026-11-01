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
package org.apache.spark.sql.execution.benchmark;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;
import org.apache.spark.sql.internal.SQLConf;

/**
 * The baseline for the {@code TIMESTAMP_NTZ} fields and the casts between {@code TIMESTAMP_NTZ}
 * and {@code TIME} before Varka fuses them (VARKA-340 5, step 1): Spark's row path over an
 * Arrow-cached table, in six sections per null density, so that the row's later steps report what
 * they change against a committed file.
 *
 * <p>The sections, each with no nulls and with one row in ten null in each column, and the step
 * of VARKA-340 whose measurement diffs against each. Each null column is null at its own row of
 * ten, so {@code make_timestamp_ntz} over two of them is null on two rows in ten, and the null
 * sections are compared only with their own after-run. No form here has an evaluation mode, so
 * the class runs in the session's mode only:
 * <ul>
 *   <li>{@code year(ntz)}, a date field over the timestamp's day (step 3, the plan's 2.2);</li>
 *   <li>{@code weekofyear(ntz)}, a date field whose Thursday shift the day guard leaves room for
 *       (step 3, 2.2);</li>
 *   <li>{@code hour(ntz)}, a field of the time of day (step 4, 2.3);</li>
 *   <li>{@code CAST(ntz AS TIME)}, the nanosecond of the day at precision 6 (step 4, 2.3);</li>
 *   <li>{@code CAST(tm AS TIMESTAMP_NTZ)}, which the optimizer rewrites to
 *       {@code make_timestamp_ntz} over the query's current date (step 5, 2.4);</li>
 *   <li>{@code make_timestamp_ntz(d, tm)} over a date column and a time column (step 5, 2.4).</li>
 * </ul>
 * Step 6 is the measurement itself: this class rerun with the fused sections listed (below), its
 * results diffed against the files committed with step 1.
 *
 * <p>Each section has two arms, named so the labels survive the kernels landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. On the code this baseline is committed against, both are the row path, so they
 * should tie.
 *
 * <p>A baseline is only one while nothing fuses: before anything is timed, the run checks each
 * section's plan on the Varka session against the section's {@code fuses} flag, the list of the
 * sections a step of VARKA-340 has fused, and fails when an unlisted section fuses or a listed one
 * does not ({@link VarkaSqlBenchmarkBase#requireBaseline}). No section is listed today; each step
 * lists the sections it fuses, so the same class and the same labels time the fused code. A listed
 * section must also run as a kernel over every batch, none handed back to the row path
 * ({@link VarkaSizeLadder#varkaFused}), or the run fails rather than time the row path under the
 * {@code varka on} label. That holds with {@value VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY} set
 * to {@code false} too, which lifts only the plan check.
 *
 * <p>The data reaches the cases the plan's kernels treat apart, and no value raises:
 * <ul>
 *   <li>{@code ntz} lies within {@link #MAX_DAYS} days of the epoch either way, so about half the
 *       timestamps are before it, where the day is a floor division and the time of day a floor
 *       modulus rather than a truncation, and about half the days are before the Gregorian
 *       reform of 15 October 1582. Every day is inside the date fields' guard
 *       ({@code VarkaChrono.NARROW_MIN_DAYS} plus a year to {@code NARROW_MAX_DAYS}, 1 March
 *       -12799 to 15 August 33134), so in the after-run every batch of those sections fuses
 *       rather than going back to the row engine.</li>
 *   <li>The time of day of {@code ntz} and {@code tm} is drawn at a microsecond's grain over the
 *       whole day, and two rows in a thousand of each are pinned to its ends, midnight and
 *       23:59:59.999999, the day boundaries a floor modulus and a truncation must keep.</li>
 *   <li>{@code d} lies within {@link #MAX_DAYS} days of the epoch either way, far inside the
 *       {@code +-106,751,991} days over which {@code make_timestamp_ntz} stays inside a long.</li>
 * </ul>
 * {@code VarkaTimestampNtzFieldsBaselineQueriesSuite} checks that the first rows reach each of
 * these, runs every section at a small scale on both sessions and fails if one raises, answers a
 * null over the null-free columns, or answers differently on the two.
 *
 * <p>The table holds a {@code TIME} column, so both sessions run with the {@code TIME} type
 * enabled, which a test JVM has by default and a benchmark JVM may not.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaTimestampNtzFieldsBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a step which fuses unlisted sections against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaTimestampNtzFieldsBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaTimestampNtzFieldsBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaTimestampNtzFieldsBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_ntz_fields";

  /** The farthest a timestamp's or a date's day lies from the epoch, either sign. */
  static final long MAX_DAYS = 3_000_000L;

  /** Microseconds in a day. */
  static final long MICROS_PER_DAY = 86_400_000_000L;

  /** The rows, by {@code id % 1000}, whose {@code ntz} is at midnight and at the day's end. */
  static final int NTZ_MIDNIGHT = 13;
  static final int NTZ_DAY_END = 19;

  /** The rows, by {@code id % 1000}, whose {@code tm} is midnight and the day's last micro. */
  static final int TM_MIDNIGHT = 17;
  static final int TM_DAY_END = 27;

  /**
   * A section: its title, its query, and whether the Varka session fuses it, the entry a step of
   * VARKA-340 sets for each section it fuses.
   */
  record Section(String title, String query, boolean fuses) {}

  /**
   * A whole number of days spread evenly over {@code -MAX_DAYS} to {@code MAX_DAYS}, from
   * {@code xxhash64(id, tag)}, which hashes the row id and a per-column tag together under the
   * function's one fixed seed, 42. Each column passes a different tag, so each column's values are
   * independent of the others'.
   */
  static String daysSql(int tag) {
    return "cast(pmod(xxhash64(id, " + tag + "), " + (2 * MAX_DAYS + 1) + ") - " + MAX_DAYS
        + " as int)";
  }

  /**
   * A microsecond of the day in {@code [0, MICROS_PER_DAY)} from {@code xxhash64(id, tag)}, except
   * {@code 0} on the rows whose {@code id % 1000} is {@code midnight} and the day's last
   * microsecond on those where it is {@code dayEnd}.
   */
  static String microsOfDaySql(int tag, int midnight, int dayEnd) {
    return "case when id % 1000 = " + midnight + " then 0L when id % 1000 = " + dayEnd + " then "
        + (MICROS_PER_DAY - 1) + "L else pmod(xxhash64(id, " + tag + "), " + MICROS_PER_DAY
        + ") end";
  }

  /**
   * The table: {@code ntz} a timestamp within {@link #MAX_DAYS} days of the epoch, {@code tm} a
   * {@code TIME(6)}, {@code d} a date within {@link #MAX_DAYS} days of the epoch, none of them
   * null; then {@code ntzn}, {@code tmn} and {@code dn}, the same values with one row in ten null,
   * at a different row in each column.
   */
  static String tableSql(int rows) {
    return "select ntz, tm, d, "
        + "case when id % 10 = 1 then null else ntz end as ntzn, "
        + "case when id % 10 = 3 then null else tm end as tmn, "
        + "case when id % 10 = 5 then null else d end as dn "
        + "from (select id, "
        + "timestamp_ntz'1970-01-01 00:00:00' + make_dt_interval(" + daysSql(0) + ", 0, 0, "
        + "cast(cast(" + microsOfDaySql(1, NTZ_MIDNIGHT, NTZ_DAY_END)
        + " as decimal(18, 0)) * 0.000001 as decimal(18, 6))) as ntz, "
        + "time_from_micros(" + microsOfDaySql(2, TM_MIDNIGHT, TM_DAY_END) + ") as tm, "
        + "date_from_unix_date(" + daysSql(3) + ") as d "
        + "from range(0, " + rows + "))";
  }

  /** Every section, in the order they run: each null density, then each form. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    for (String nulls : new String[] {"", "n"}) {
      String ntz = "ntz" + nulls;
      String tm = "tm" + nulls;
      String d = "d" + nulls;
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " AS v FROM " + VIEW;
      out.add(new Section("TIMESTAMP_NTZ year(ntz)" + density,
          "SELECT year(" + ntz + ")" + from, false));
      out.add(new Section("TIMESTAMP_NTZ weekofyear(ntz)" + density,
          "SELECT weekofyear(" + ntz + ")" + from, false));
      out.add(new Section("TIMESTAMP_NTZ hour(ntz)" + density,
          "SELECT hour(" + ntz + ")" + from, false));
      out.add(new Section("TIMESTAMP_NTZ CAST(ntz AS TIME)" + density,
          "SELECT CAST(" + ntz + " AS TIME)" + from, false));
      out.add(new Section("TIMESTAMP_NTZ CAST(tm AS TIMESTAMP_NTZ)" + density,
          "SELECT CAST(" + tm + " AS TIMESTAMP_NTZ)" + from, false));
      out.add(new Section("TIMESTAMP_NTZ make_timestamp_ntz(d, tm)" + density,
          "SELECT make_timestamp_ntz(" + d + ", " + tm + ")" + from, false));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-ntz-fields-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-ntz-fields-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.conf().set(SQLConf.TIME_TYPE_ENABLED().key(), "true");
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView(VIEW);
        VarkaArrowSessions.cache(session, VIEW);
      }
      // A section fuses exactly when it is listed, and a listed one runs every batch as a kernel.
      for (Section s : sections()) {
        requireBaseline(varka, s.title(), s.query(), s.fuses());
        if (s.fuses()) {
          try {
            VarkaSizeLadder.varkaFused(varka, s.query());
          } catch (RuntimeException e) {
            throw new IllegalStateException(s.title() + " is listed as fusing but does not run "
                + "fused, so its varka on arm would time the row path: " + s.query(), e);
          }
        }
      }
      for (Section s : sections()) {
        section(s.title(), () -> {
          VarkaBenchmark benchmark = benchmark(
              s.title() + " over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS).timed().build();
          benchmark.addCase("spark row path (varka off)", i -> noop(baseline.sql(s.query())));
          benchmark.addCase("varka on", i -> noop(varka.sql(s.query())));
          runCases(benchmark);
        });
      }
    } finally {
      baseline.stop();
      varka.stop();
    }
  }
}
