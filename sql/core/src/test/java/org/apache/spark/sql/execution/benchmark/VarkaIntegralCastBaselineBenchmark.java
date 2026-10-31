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
 * The baseline for the integral, boolean and interval casts before Varka fuses them
 * (VARKA-332): Spark's row path over an Arrow-cached table of {@code INT}, {@code BIGINT},
 * {@code TINYINT}, {@code BOOLEAN} and {@code INTERVAL DAY TO SECOND} columns, one section per
 * cast and null density, so that the row reports what it changes against a committed file.
 *
 * <p>The casts are {@code CAST(... AS TINYINT)} from an {@code INT} in the three modes: LEGACY
 * over {@code i}, the whole int range, which wraps, ANSI over {@code j}, inside the byte range,
 * since a value past it would raise and end the run, and {@code try_cast} over {@code k}, which
 * is past the range at one row in a hundred; then {@code CAST(l AS SMALLINT)} under LEGACY,
 * which wraps, {@code CAST(y AS BIGINT)} from a {@code TINYINT} (the narrow value under a long
 * kernel of {@code VARKA-332.md} 2.5, not a year-month interval), {@code CAST(b AS INT)} from a
 * {@code BOOLEAN}, {@code coalesce(CAST(i AS BOOLEAN), false)} as a filter's condition,
 * {@code CAST(m AS INTERVAL YEAR)}, which raises past {@code +-178956970} in every mode, so it
 * reads a column inside that range, and {@code CAST(dt AS BIGINT)}, the whole seconds of a
 * day-time interval, each with no nulls and with one row in ten null. The casts other than the
 * narrowings have one answer in every mode, and run in the session's default one. A control
 * section, {@code CAST(i AS BIGINT)}, fuses already and is listed so.
 *
 * <p>Each section has two arms, named so the labels survive the row landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Today no section but the control plans a Varka node, so both arms of the
 * others are the row path and should tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@code fuses} flag, the list of
 * the sections a row has fused, and fails when an unlisted section fuses or a listed one does not;
 * the results file notes each section that fuses. Only the control is listed. The filter's
 * condition is the cast under {@code coalesce} rather than the bare cast: from a bare
 * {@code WHERE CAST(i AS BOOLEAN)} over the column with nulls the optimizer infers
 * {@code i IS NOT NULL}, since the cast is null-intolerant, and that conjunct alone fuses into a
 * {@code VarkaFilterColumnarToRow} under Spark's {@code Filter}, which would put a Varka node in
 * the plan while the cast declines, so the check could not see the row start fusing the cast
 * itself. {@code coalesce} is not null-intolerant, so nothing is inferred, and it keeps the same
 * rows, a null cast being false. A row that fuses a section lists it, so that its fusion cannot
 * turn a baseline section into a fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaIntegralCastBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses unlisted sections against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaIntegralCastBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaIntegralCastBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaIntegralCastBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_integral_cast";

  /**
   * A section: its title, its query, the SQL settings it runs under on both sessions, empty for a
   * cast whose answer does not depend on them, and whether the Varka session fuses it, which the
   * run checks before it times anything.
   */
  record Section(String title, String query, Map<String, String> conf, boolean fuses) {}

  /**
   * The table: {@code i}, {@code j}, {@code k} and {@code m} ints, {@code l} a {@code BIGINT},
   * {@code y} a {@code TINYINT}, {@code b} a {@code BOOLEAN} and {@code dt} an
   * {@code INTERVAL DAY TO SECOND}, with no null, and {@code i_n} to {@code dt_n} the same values
   * with one row in ten null, at a different row in each column and never at a row planted
   * below. Each column is a hash of the row's id, so any few thousand rows reach every region
   * below, as the queries suite checks.
   *
   * <ul>
   *   <li>{@code i} is {@code hash(id)}, over the whole int range, so nearly every value is past
   *       the byte range on one side or the other, where LEGACY's cast keeps the low eight bits.
   *       It is {@code 0} at one row in eight, so the boolean filter keeps seven rows in
   *       eight.</li>
   *   <li>{@code j} is {@code pmod(hash(id, 1), 256) - 128}, every value of the byte range and
   *       none past it, so ANSI's {@code CAST(j AS TINYINT)} never raises.</li>
   *   <li>{@code k} is {@code j}'s form over {@code hash(id, 2)}, but one row in two hundred is
   *       moved past {@code 127}, up to {@code Integer.MAX_VALUE}, and another past {@code -128},
   *       down to {@code Integer.MIN_VALUE}: there {@code try_cast(k AS TINYINT)} answers null,
   *       and Spark's TRY path raises and catches an exception per row, so the share is kept
   *       to one in a hundred, as {@code VarkaFloatingCastBaselineBenchmark}'s {@code w} is.</li>
   *   <li>{@code l} is {@code xxhash64(id)} shifted right by {@code id % 64}, so its magnitudes
   *       spread evenly over every bit length of the long: about one in four inside the short
   *       range, the rest past it, most of them past the int range too.</li>
   *   <li>{@code y} is {@code pmod(hash(id, 4), 256) - 128} as a {@code TINYINT}, the whole
   *       byte range.</li>
   *   <li>{@code b} is {@code hash(id, 5) % 2 = 0}, true at about one row in two.</li>
   *   <li>{@code m} is inside {@code [-178956970, 178956970]}, the ints whose product by twelve
   *       fits an int, and is each end of it at one row in a thousand: past it
   *       {@code CAST(m AS INTERVAL YEAR)} raises in every mode.</li>
   *   <li>{@code dt} is {@code hash(id, 6)} seconds and a fraction of
   *       {@code pmod(hash(id, 7), 10^6)} microseconds, so a negative value with a fraction
   *       truncates toward zero, and is minus half a second at one row in a thousand, which
   *       answers {@code 0}. Every value is under {@code 2^31} seconds, inside the
   *       {@code 2^52} microseconds that the first fused form of {@code VARKA-332.md} 2.4
   *       divides exactly, so this section times that form's kernel once it lands, not its
   *       declined batches.</li>
   * </ul>
   */
  static String tableSql(int rows) {
    return tableSql("range(0, " + rows + ")");
  }

  /**
   * {@link #tableSql(int)} over the ids of {@code ids}, a relation with a {@code BIGINT} column
   * {@code id}.
   */
  static String tableSql(String ids) {
    return """
        select i, j, k, l, y, b, m, dt,
               case when id %% 10 = 1 then null else i end as i_n,
               case when id %% 10 = 3 then null else j end as j_n,
               case when id %% 10 = 0 then null else k end as k_n,
               case when id %% 10 = 5 then null else l end as l_n,
               case when id %% 10 = 7 then null else y end as y_n,
               case when id %% 10 = 9 then null else b end as b_n,
               case when id %% 10 = 4 then null else m end as m_n,
               case when id %% 10 = 6 then null else dt end as dt_n
        from (select id,
                     case when id %% 8 = 4 then 0 else hash(id) end as i,
                     pmod(hash(id, 1), 256) - 128 as j,
                     case when id %% 200 = 41 then 128 + pmod(hash(id, 2), 2147483520)
                          when id %% 200 = 141 then -129 - pmod(hash(id, 2), 2147483520)
                          else pmod(hash(id, 2), 256) - 128 end as k,
                     shiftright(xxhash64(id), cast(id %% 64 as int)) as l,
                     cast(pmod(hash(id, 4), 256) - 128 as tinyint) as y,
                     hash(id, 5) %% 2 = 0 as b,
                     case when id %% 1000 = 7 then 178956970
                          when id %% 1000 = 8 then -178956970
                          else pmod(hash(id, 3), 357913941) - 178956970 end as m,
                     make_dt_interval(0, 0, 0,
                         case when id %% 1000 = 9 then cast(-0.5 as decimal(18, 6))
                              else cast(cast(hash(id, 6) as decimal(18, 6))
                                        + pmod(hash(id, 7), 1000000) * 0.000001
                                        as decimal(18, 6)) end) as dt
              from %s)""".formatted(ids);
  }

  /** Every section, in the order they run: each null density, then each cast. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    Map<String, String> legacy = Map.of(SQLConf.ANSI_ENABLED().key(), "false");
    Map<String, String> ansi = Map.of(SQLConf.ANSI_ENABLED().key(), "true");
    for (String nulls : new String[] {"", "_n"}) {
      String i = "i" + nulls;
      String j = "j" + nulls;
      String k = "k" + nulls;
      String l = "l" + nulls;
      String y = "y" + nulls;
      String b = "b" + nulls;
      String m = "m" + nulls;
      String dt = "dt" + nulls;
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " AS a FROM " + VIEW;
      out.add(new Section("INT CAST(i AS TINYINT), LEGACY" + density,
          "SELECT CAST(" + i + " AS TINYINT)" + from, legacy, false));
      out.add(new Section("INT CAST(j AS TINYINT), ANSI" + density,
          "SELECT CAST(" + j + " AS TINYINT)" + from, ansi, false));
      out.add(new Section("INT try_cast(k AS TINYINT)" + density,
          "SELECT try_cast(" + k + " AS TINYINT)" + from, Map.of(), false));
      out.add(new Section("BIGINT CAST(l AS SMALLINT), LEGACY" + density,
          "SELECT CAST(" + l + " AS SMALLINT)" + from, legacy, false));
      out.add(new Section("TINYINT CAST(y AS BIGINT)" + density,
          "SELECT CAST(" + y + " AS BIGINT)" + from, Map.of(), false));
      out.add(new Section("BOOLEAN CAST(b AS INT)" + density,
          "SELECT CAST(" + b + " AS INT)" + from, Map.of(), false));
      out.add(new Section("INT filter coalesce(CAST(i AS BOOLEAN), false)" + density,
          "SELECT " + i + from + " WHERE coalesce(CAST(" + i + " AS BOOLEAN), false)", Map.of(),
          false));
      out.add(new Section("INT CAST(m AS INTERVAL YEAR)" + density,
          "SELECT CAST(" + m + " AS INTERVAL YEAR)" + from, Map.of(), false));
      out.add(new Section("INTERVAL DAY TO SECOND CAST(dt AS BIGINT)" + density,
          "SELECT CAST(" + dt + " AS BIGINT)" + from, Map.of(), false));
      out.add(new Section("INT CAST(i AS BIGINT), control, fuses today" + density,
          "SELECT CAST(" + i + " AS BIGINT)" + from, Map.of(), true));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-integral-cast-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-integral-cast-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView(VIEW);
        VarkaArrowSessions.cache(session, VIEW);
      }
      for (Section s : sections()) {
        withConf(varka, s.conf(), () -> requireBaseline(varka, s.title(), s.query(), s.fuses()));
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
