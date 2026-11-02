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
import java.util.Map;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;
import org.apache.spark.sql.internal.SQLConf;

/**
 * The baseline for the day-time interval expressions before Varka fuses them (VARKA-103):
 * Spark's row path over an Arrow-cached table of two {@code INTERVAL DAY TO SECOND} columns,
 * three {@code INT} columns and a {@code DECIMAL(18, 6)} column, one section per expression and
 * null density, so that the row reports what it changes against a committed file.
 *
 * <p>The sections are those of {@code VARKA-103.md} 4: the checked arithmetic
 * {@code dt + dt2 - dt} and {@code abs(dt)}; the product by an integral literal,
 * {@code dt * 3}, and by a decimal one, {@code dt * 1.5}; the quotient {@code dt / 24}, whose
 * divisor divides a day; {@code extract(DAY FROM dt)}, {@code extract(HOUR FROM dt)} and the
 * four fields of {@code dt} in one projection; {@code make_dt_interval(i, i2, i3)} and the same
 * with the decimal seconds {@code s}; {@code CAST(dt AS INTERVAL HOUR)}, which truncates to whole
 * hours; and the filter {@code extract(HOUR FROM dt) >= 12}. Each runs with no nulls and with one
 * row in ten null. Every one of these forms raises on overflow in every mode, so none depends on
 * the session's mode, and the data keeps every result and every partial sum inside the long. A
 * control section, the filter {@code dt > dt2}, fuses already and is {@code WHOLE}.
 *
 * <p>Each section has two arms, named so the labels survive the row landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. The sections that are {@code NONE} plan no Varka node, so both of their arms
 * are the row path and should tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@link Fusion} class, the
 * record of how much of it a row has fused, and fails when the plan's class differs
 * ({@link VarkaSqlBenchmarkBase#requireBaseline(SparkSession, String, String, Fusion)}); the
 * results file notes each section that fuses. A row that fuses more of a section sets its new
 * class, so that its fusion cannot turn a baseline section into a fused one unnoticed. Every
 * section but the control is {@code NONE}.
 *
 * <p>The filters run under {@code spark.sql.constraintPropagation.enabled=false} on both
 * sessions. With constraint propagation on, the optimizer infers {@code dt_n IS NOT NULL} from
 * the null-intolerant condition over {@code dt_n}, and that conjunct alone fuses, as a long-lane
 * null test, into a {@code VarkaFilterColumnarToRow} under Spark's {@code Filter}: the plan
 * would hold a Varka node while the extract declines, and the check could not see the row start
 * fusing the extract itself. The setting changes only that inference, so both arms at both
 * densities time Spark's own plan of the bare condition. Each section's {@link Fusion} class
 * is the one a probe of its plan found.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaDayTimeIntervalBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses unlisted sections against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaDayTimeIntervalBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaDayTimeIntervalBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaDayTimeIntervalBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_day_time_interval";

  /**
   * A section: its title, its query, the SQL settings it runs under on both sessions (the
   * filters' constraint propagation), empty for a projection, and how much of it the Varka
   * session fuses today, the {@link VarkaSqlBenchmarkBase.Fusion} class the run checks its plan
   * against before it times anything.
   */
  record Section(String title, String query, Map<String, String> conf, Fusion fusion) {}

  /**
   * The table: {@code dt} and {@code dt2} {@code INTERVAL DAY TO SECOND}, {@code i}, {@code i2}
   * and {@code i3} ints and {@code s} a {@code DECIMAL(18, 6)}, with no null, and {@code dt_n},
   * {@code i_n} and {@code s_n} the same values with one row in ten null, at a different row in
   * each column and never at a row planted below. Each column is a hash of the row's id, so any
   * few thousand rows reach every region below, as the queries suite checks.
   *
   * <ul>
   *   <li>{@code dt} is {@code hash(id, 1)} seconds and a fraction of
   *       {@code pmod(hash(id, 2), 10^6)} microseconds: both signs, up to about 24855 days, so
   *       every field of an extract takes all its values, and a negative value with a fraction
   *       truncates toward zero in every field. At one row in a thousand it is minus one
   *       microsecond, whose fields are all zero but the seconds, and at another minus one day
   *       exactly, whose fields below the day are zero: the cells where a floor and Spark's
   *       truncation part.</li>
   *   <li>{@code dt2} is the same form over {@code hash(id, 3)} and {@code hash(id, 4)}. Both are
   *       under {@code 2^31} seconds, so {@code dt + dt2}, {@code dt * 3} and {@code dt * 1.5}
   *       stay far inside the long and never raise.</li>
   *   <li>{@code i}, the days of {@code make_dt_interval}, is inside {@code [-100000, 100000]};
   *       {@code i2}, the hours, inside {@code [-1000, 1000]}, past a day's 23 on purpose, which
   *       Spark accepts; {@code i3}, the minutes, inside {@code [-100000, 100000]}. So every
   *       partial sum, taken in Spark's order, is under {@code 2^54} in magnitude and none
   *       raises.</li>
   *   <li>{@code s}, the seconds, is {@code hash(id, 7)} and a fraction of
   *       {@code pmod(hash(id, 8), 10^6)} millionths, both signs, as a {@code DECIMAL(18, 6)},
   *       the type {@code make_dt_interval} takes them in. The four-argument section's null
   *       density reads {@code s_n} with the other arguments whole, and the three-argument one
   *       reads {@code i_n}, so each is null at one row in ten.</li>
   * </ul>
   */
  static String tableSql(int rows) {
    return """
        select dt, dt2, i, i2, i3, s,
               case when id %% 10 = 1 then null else dt end as dt_n,
               case when id %% 10 = 3 then null else i end as i_n,
               case when id %% 10 = 5 then null else s end as s_n
        from (select id,
                     case when id %% 1000 = 7 then INTERVAL '-0.000001' SECOND
                          when id %% 1000 = 8 then INTERVAL '-1' DAY
                          else make_dt_interval(0, 0, 0,
                              cast(cast(hash(id, 1) as decimal(18, 6))
                                   + pmod(hash(id, 2), 1000000) * 0.000001
                                   as decimal(18, 6))) end as dt,
                     make_dt_interval(0, 0, 0,
                         cast(cast(hash(id, 3) as decimal(18, 6))
                              + pmod(hash(id, 4), 1000000) * 0.000001
                              as decimal(18, 6))) as dt2,
                     pmod(hash(id, 5), 200001) - 100000 as i,
                     pmod(hash(id, 6), 2001) - 1000 as i2,
                     pmod(hash(id, 9), 200001) - 100000 as i3,
                     cast(cast(hash(id, 7) as decimal(18, 6))
                          + pmod(hash(id, 8), 1000000) * 0.000001 as decimal(18, 6)) as s
              from range(0, %d))""".formatted(rows);
  }

  /** Every section, in the order they run: each null density, then each expression. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    Map<String, String> noConstraints =
        Map.of(SQLConf.CONSTRAINT_PROPAGATION_ENABLED().key(), "false");
    for (String nulls : new String[] {"", "_n"}) {
      String dt = "dt" + nulls;
      String i = "i" + nulls;
      String s = "s" + nulls;
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " FROM " + VIEW;
      out.add(new Section("dt + dt2 - dt" + density,
          "SELECT " + dt + " + dt2 - " + dt + " AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("abs(dt)" + density,
          "SELECT abs(" + dt + ") AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("dt * 3" + density,
          "SELECT " + dt + " * 3 AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("dt * 1.5" + density,
          "SELECT " + dt + " * 1.5 AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("dt / 24" + density,
          "SELECT " + dt + " / 24 AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("extract(DAY FROM dt)" + density,
          "SELECT extract(DAY FROM " + dt + ") AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("extract(HOUR FROM dt)" + density,
          "SELECT extract(HOUR FROM " + dt + ") AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("extract DAY, HOUR, MINUTE and SECOND together" + density,
          "SELECT extract(DAY FROM " + dt + ") AS a, extract(HOUR FROM " + dt + ") AS b, "
              + "extract(MINUTE FROM " + dt + ") AS c, extract(SECOND FROM " + dt + ") AS d"
              + from, Map.of(), Fusion.NONE));
      out.add(new Section("make_dt_interval(i, i2, i3)" + density,
          "SELECT make_dt_interval(" + i + ", i2, i3) AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("make_dt_interval(i, i2, i3, s), DECIMAL(18, 6) seconds" + density,
          "SELECT make_dt_interval(i, i2, i3, " + s + ") AS a" + from, Map.of(), Fusion.NONE));
      out.add(new Section("CAST(dt AS INTERVAL HOUR)" + density,
          "SELECT CAST(" + dt + " AS INTERVAL HOUR) AS a" + from, Map.of(), Fusion.NONE));
      // The bare condition at both densities, with no IS NOT NULL inferred (the class javadoc).
      out.add(new Section("filter extract(HOUR FROM dt) >= 12" + density,
          "SELECT " + dt + " AS a" + from + " WHERE extract(HOUR FROM " + dt + ") >= 12",
          noConstraints, Fusion.NONE));
      out.add(new Section("filter dt > dt2, control, fuses today" + density,
          "SELECT " + dt + " AS a" + from + " WHERE " + dt + " > dt2", noConstraints,
          Fusion.WHOLE));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-day-time-interval-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-day-time-interval-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView(VIEW);
        VarkaArrowSessions.cache(session, VIEW);
      }
      for (Section s : sections()) {
        withConf(varka, s.conf(), () -> requireBaseline(varka, s.title(), s.query(), s.fusion()));
      }
      for (Section s : sections()) {
        section(s.title(), () -> withConf(baseline, s.conf(), () -> withConf(varka, s.conf(),
            () -> {
              VarkaBenchmark benchmark = benchmark(
                  s.title() + " over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS)
                  .timed().build();
              benchmark.addCase("spark row path (varka off)", i -> noop(baseline.sql(s.query())));
              benchmark.addCase("varka on", i -> noop(varka.sql(s.query())));
              runCases(benchmark);
            })));
      }
    } finally {
      baseline.stop();
      varka.stop();
    }
  }
}
