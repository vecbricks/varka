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

/**
 * The baseline for {@code TIMESTAMP_NTZ} arithmetic before Varka fuses it (VARKA-339 5, step 1):
 * Spark's row path over an Arrow-cached table, in six sections per null density, so that the
 * row's later steps report what they change against a committed file.
 *
 * <p>The sections, each with no nulls and with one row in ten null in each column, and the step
 * of VARKA-339 whose measurement diffs against each. Each null column is null at its own row of
 * ten, so a section over two or three of them is null on two or three rows in ten, and the null
 * sections are compared only with their own after-run, not with each other. Every form here
 * computes the same in LEGACY and ANSI, so the class runs in the session's mode only:
 * <ul>
 *   <li>the arithmetic chain {@code ntz + (ntz2 - ntz3) - INTERVAL '1' HOUR}, the difference of
 *       two timestamps added and an interval literal subtracted (step 2, the plan's 2.1). It is
 *       a section of {@code VarkaTimestampNtzBaselineBenchmark} too, and is timed here again so
 *       that the row's after-run diffs every form it fuses against one file;</li>
 *   <li>{@code ntz + dt}, a timestamp plus a day-time interval column (step 2, 2.1);</li>
 *   <li>{@code ntz + INTERVAL '1' MONTH}, a month form (step 3, 2.3 and 2.4);</li>
 *   <li>{@code timestampadd(HOUR, q, ntz)} over an int quantity (step 3, 2.2);</li>
 *   <li>{@code time_bucket} at 15 minutes, a width that divides a day (step 4, 2.5);</li>
 *   <li>{@code time_bucket} at one day, a whole number of days (step 4, 2.5).</li>
 * </ul>
 * Step 5 is the measurement itself: this class rerun with the fused sections listed (below), its
 * results diffed against the files committed with step 1.
 *
 * <p>Each section has two arms, named so the labels survive the kernels landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. On the code this baseline is committed against, both are the row path, so they
 * should tie.
 *
 * <p>A baseline is only one while nothing fuses: before anything is timed, the run checks each
 * section's plan on the Varka session against the section's {@code fuses} flag, the list of the
 * sections a step of VARKA-339 has fused, and fails when an unlisted section fuses or a listed one
 * does not ({@link VarkaSqlBenchmarkBase#requireBaseline}). No section is listed today; each step
 * lists the sections it fuses, so the same class and the same labels time the fused code. A listed
 * section must also run as a kernel over every batch, none handed back to the row path
 * ({@link VarkaSizeLadder#varkaFused}), or the run fails rather than time the row path under the
 * {@code varka on} label. That holds with {@value VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY} set
 * to {@code false} too, which lifts only the plan check.
 *
 * <p>No value raises or overflows. Every timestamp lies within 3,000,000 days of the epoch (about
 * 8,200 years either way), well inside the days the month form's day guard admits
 * ({@code VarkaChrono.NARROW_MIN_DAYS} to {@code NARROW_MAX_DAYS}, 1 March -12800 to 15 August
 * 33134), so that in the after-run every batch of the month section fuses rather than going back
 * to the row engine. The intervals stay within 500 days and the quantities within 100,000 hours
 * (about 11 years), so every sum stays far from the ends of a long.
 * {@code VarkaTimestampNtzArithmeticBaselineQueriesSuite} runs every section at a small scale and
 * fails if one raises or answers a null over the null-free columns.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaTimestampNtzArithmeticBaselineBenchmark \
 *     -f jdk=25 -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 */
public class VarkaTimestampNtzArithmeticBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The farthest a timestamp lies from the epoch, in days. */
  static final long MAX_TIMESTAMP_DAYS = 3_000_000L;

  /** The longest a day-time interval is, in days, either sign. */
  static final long MAX_INTERVAL_DAYS = 500L;

  /** The largest {@code timestampadd} quantity, in hours, either sign. */
  static final long MAX_HOURS = 100_000L;

  /**
   * A section: its title, its query, and whether the Varka session fuses it, the entry a step of
   * VARKA-339 sets for each section it fuses.
   */
  record Section(String title, String query, boolean fuses) {}

  /**
   * A day-time value of {@code days} whole days spread evenly over {@code -maxDays} to
   * {@code maxDays}, plus a time of day at a microsecond's grain. Each comes from
   * {@code xxhash64(id, tag)}, which hashes the row id and a per-column tag together under the
   * function's one fixed seed, 42: the whole days from {@code tag} and the time of day from
   * {@code tag + 100}. Every column passes a different tag, 0 to 4, so each column's values are
   * independent of the others'.
   */
  static String dayTimeSql(long maxDays, int tag) {
    return "make_dt_interval(cast(pmod(xxhash64(id, " + tag + "), " + (2 * maxDays + 1) + ") - "
        + maxDays + " as int), 0, 0, cast(cast(pmod(xxhash64(id, " + (tag + 100)
        + "), 86400000000) as decimal(18, 0)) * 0.000001 as decimal(18, 6)))";
  }

  /**
   * The table: {@code ntz}, {@code ntz2} and {@code ntz3}, timestamps within
   * {@link #MAX_TIMESTAMP_DAYS} of the epoch; {@code dt}, a day-time interval within
   * {@link #MAX_INTERVAL_DAYS}; {@code q}, an int within {@link #MAX_HOURS}; none of them null.
   * Then {@code ntzn} to {@code qn}, the same values with one row in ten null, at a different row
   * in each column.
   */
  static String tableSql(int rows) {
    String epoch = "timestamp_ntz'1970-01-01 00:00:00' + ";
    return "select ntz, ntz2, ntz3, dt, q, "
        + "case when id % 10 = 1 then null else ntz end as ntzn, "
        + "case when id % 10 = 3 then null else ntz2 end as ntz2n, "
        + "case when id % 10 = 5 then null else ntz3 end as ntz3n, "
        + "case when id % 10 = 7 then null else dt end as dtn, "
        + "case when id % 10 = 9 then null else q end as qn "
        + "from (select id, "
        + epoch + dayTimeSql(MAX_TIMESTAMP_DAYS, 0) + " as ntz, "
        + epoch + dayTimeSql(MAX_TIMESTAMP_DAYS, 1) + " as ntz2, "
        + epoch + dayTimeSql(MAX_TIMESTAMP_DAYS, 2) + " as ntz3, "
        + dayTimeSql(MAX_INTERVAL_DAYS, 3) + " as dt, "
        + "cast(pmod(xxhash64(id, 4), " + (2 * MAX_HOURS + 1) + ") - " + MAX_HOURS
        + " as int) as q "
        + "from range(0, " + rows + "))";
  }

  /** Every section, in the order they run: each null density, then each shape. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    for (String nulls : new String[] {"", "n"}) {
      String a = "ntz" + nulls;
      String b = "ntz2" + nulls;
      String c = "ntz3" + nulls;
      String dt = "dt" + nulls;
      String q = "q" + nulls;
      String density = nulls.isEmpty() ? "no nulls" : "one row in ten null";
      String from = " AS v FROM varka_ntz_arith";
      out.add(new Section("TIMESTAMP_NTZ arithmetic chain, " + density,
          "SELECT " + a + " + (" + b + " - " + c + ") - INTERVAL '1' HOUR" + from, false));
      out.add(new Section("TIMESTAMP_NTZ plus a day-time interval column, " + density,
          "SELECT " + a + " + " + dt + from, false));
      out.add(new Section("TIMESTAMP_NTZ plus INTERVAL '1' MONTH, " + density,
          "SELECT " + a + " + INTERVAL '1' MONTH" + from, false));
      out.add(new Section("TIMESTAMP_NTZ timestampadd(HOUR, q, ntz), " + density,
          "SELECT timestampadd(HOUR, " + q + ", " + a + ")" + from, false));
      out.add(new Section("TIMESTAMP_NTZ time_bucket at 15 minutes, " + density,
          "SELECT time_bucket(INTERVAL '15' MINUTE, " + a + ")" + from, false));
      out.add(new Section("TIMESTAMP_NTZ time_bucket at one day, " + density,
          "SELECT time_bucket(INTERVAL '1' DAY, " + a + ")" + from, false));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-ntz-arith-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-ntz-arith-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView("varka_ntz_arith");
        VarkaArrowSessions.cache(session, "varka_ntz_arith");
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
