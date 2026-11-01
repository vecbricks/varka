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
 * The baseline for the floating casts before Varka fuses them (VARKA-343): Spark's row path over
 * an Arrow-cached table of {@code BIGINT}, {@code INT} and {@code DOUBLE} columns, one section per
 * cast and null density, so that the row reports what it changes against a committed file.
 * {@link VarkaFloatingBaselineBenchmark} already times {@code DOUBLE} to {@code BIGINT} under the
 * session's mode and {@code FLOAT} to {@code DOUBLE}; this class times the pairs it does not.
 *
 * <p>The casts are {@code CAST(l AS FLOAT)} and {@code CAST(l AS DOUBLE)} from a {@code BIGINT},
 * {@code CAST(i AS DOUBLE)} from an {@code INT}, {@code CAST(w AS INT)} under LEGACY, which
 * saturates the values past the int range and answers 0 for NaN, and {@code CAST(x AS INT)} under
 * ANSI, which would raise on them, {@code try_cast(w AS BIGINT)}, {@code CAST(x AS FLOAT)},
 * {@code coalesce(CAST(x AS BOOLEAN), false)} as a filter's condition, and
 * {@code time_from_seconds(s)} with the {@code TIME} type enabled, each with no nulls and with one
 * row in ten null. The casts other than {@code CAST(... AS INT)} have one answer in every mode,
 * and run in the session's default one.
 *
 * <p>Each section has two arms, named so the labels survive the row landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Today no section plans a Varka node, so both arms are the row path and should
 * tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@code fuses} flag, the list of
 * the sections a row has fused, and fails when an unlisted section fuses or a listed one does not;
 * the results file notes each section that fuses. No section is listed. The filter's condition
 * is the cast under {@code coalesce} rather than the bare cast: from a bare
 * {@code WHERE CAST(x AS BOOLEAN)} the optimizer infers {@code x IS NOT NULL}, since the cast is
 * null-intolerant, and that conjunct alone fuses into a {@code VarkaFilterColumnarToRow} under
 * Spark's {@code Filter}, which would put a Varka node in the plan while the cast declines, so
 * the check could not see the row start fusing the cast itself. {@code coalesce} is not
 * null-intolerant, so nothing is inferred, and it keeps the same rows, a null cast being false.
 * A row that fuses a section lists it, so that its fusion cannot turn a baseline section into a
 * fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaFloatingCastBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses unlisted sections against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaFloatingCastBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaFloatingCastBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaFloatingCastBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_floating_cast";

  /**
   * A section: its title, its query, the SQL settings it runs under on both sessions, empty for a
   * cast whose answer does not depend on them, and whether the Varka session fuses it, which the
   * run checks before it times anything.
   */
  record Section(String title, String query, Map<String, String> conf, boolean fuses) {}

  /**
   * The table: {@code l} a {@code BIGINT}, {@code i} an {@code INT}, and {@code x}, {@code w} and
   * {@code s} doubles, with no null, and {@code l_n} to {@code s_n} the same values with one row
   * in ten null, at a different row in each column. Each column is a hash of the row's id, so any
   * few thousand rows reach every region below, as the queries suite checks.
   *
   * <ul>
   *   <li>{@code l} is {@code xxhash64(id)} shifted right by {@code id % 64}, so its magnitudes
   *       spread evenly over every bit length of the long: about five in eight are past
   *       {@code 2^24}, where a cast to {@code FLOAT} rounds, about one in six past {@code 2^53},
   *       where one to {@code DOUBLE} does, and about one in five past {@code 2^51}, where the
   *       bounded AVX2 conversion of row 343 declines.</li>
   *   <li>{@code i} is {@code hash(id)}, over the whole int range; every value is exact as a
   *       double.</li>
   *   <li>{@code x} is {@code hash(id, 1) / 7.0}, within {@code +-2^31 / 7}, so no value leaves
   *       the int range and ANSI's {@code CAST(x AS INT)} never raises, and its sevenths keep
   *       more bits than a float has. It is {@code 0.0} at one row in eight and {@code -0.0} at
   *       another, so the boolean filter keeps three rows in four.</li>
   *   <li>{@code w} is {@code hash(id, 2) / 7.0} scaled by {@code 2^(id % 35)}, within
   *       {@code +-2^62.2}, from values with a fraction to whole values past {@code 2^53}, and
   *       about six in seven past the int range, where LEGACY's {@code CAST(w AS INT)}
   *       saturates. One row in a hundred is scaled by {@code 2^40} instead, up to about
   *       {@code 2^68}, so about one row in a hundred is past the long range: there
   *       {@code try_cast(w AS BIGINT)} answers null, and Spark's TRY path raises and catches an
   *       exception per row, so a share as large as the earlier one in ten made the section time
   *       mostly exceptions. One row in a thousand each is NaN, {@code +Infinity} and
   *       {@code -Infinity}, which LEGACY's cast answers 0, {@code Integer.MAX_VALUE} and
   *       {@code Integer.MIN_VALUE} for, and {@code try_cast} null. None of these rows is among
   *       {@code w_n}'s nulls.</li>
   *   <li>{@code s} is {@code pmod(hash(id, 3), 86400000) / 1000.0}, seconds of the day from
   *       {@code 0} to {@code 86399.999}: {@code time_from_seconds} raises outside
   *       {@code [0, 86400)} in every mode.</li>
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
        select l, i, x, w, s,
               case when id %% 10 = 1 then null else l end as l_n,
               case when id %% 10 = 3 then null else i end as i_n,
               case when id %% 10 = 5 then null else x end as x_n,
               case when id %% 10 = 7 then null else w end as w_n,
               case when id %% 10 = 9 then null else s end as s_n
        from (select id,
                     shiftright(xxhash64(id), cast(id %% 64 as int)) as l,
                     hash(id) as i,
                     case when id %% 8 = 0 then 0.0d
                          when id %% 8 = 4 then -0.0d
                          else hash(id, 1) / 7.0d end as x,
                     case when id %% 1000 = 11 then double('NaN')
                          when id %% 1000 = 333 then double('Infinity')
                          when id %% 1000 = 555 then double('-Infinity')
                          else hash(id, 2) / 7.0d
                               * power(2.0d, if(id %% 100 = 41, 40, id %% 35)) end as w,
                     pmod(hash(id, 3), 86400000) / 1000.0d as s
              from %s)""".formatted(ids);
  }

  /** Every section, in the order they run: each null density, then each cast. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    Map<String, String> legacy = Map.of(SQLConf.ANSI_ENABLED().key(), "false");
    Map<String, String> ansi = Map.of(SQLConf.ANSI_ENABLED().key(), "true");
    Map<String, String> time = Map.of(SQLConf.TIME_TYPE_ENABLED().key(), "true");
    for (String nulls : new String[] {"", "_n"}) {
      String l = "l" + nulls;
      String i = "i" + nulls;
      String x = "x" + nulls;
      String w = "w" + nulls;
      String s = "s" + nulls;
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " AS a FROM " + VIEW;
      out.add(new Section("BIGINT CAST(l AS FLOAT)" + density,
          "SELECT CAST(" + l + " AS FLOAT)" + from, Map.of(), false));
      out.add(new Section("BIGINT CAST(l AS DOUBLE)" + density,
          "SELECT CAST(" + l + " AS DOUBLE)" + from, Map.of(), false));
      out.add(new Section("INT CAST(i AS DOUBLE)" + density,
          "SELECT CAST(" + i + " AS DOUBLE)" + from, Map.of(), false));
      out.add(new Section("DOUBLE CAST(w AS INT), LEGACY" + density,
          "SELECT CAST(" + w + " AS INT)" + from, legacy, false));
      out.add(new Section("DOUBLE CAST(x AS INT), ANSI" + density,
          "SELECT CAST(" + x + " AS INT)" + from, ansi, false));
      out.add(new Section("DOUBLE try_cast(w AS BIGINT)" + density,
          "SELECT try_cast(" + w + " AS BIGINT)" + from, Map.of(), false));
      out.add(new Section("DOUBLE CAST(x AS FLOAT)" + density,
          "SELECT CAST(" + x + " AS FLOAT)" + from, Map.of(), false));
      out.add(new Section("DOUBLE filter coalesce(CAST(x AS BOOLEAN), false)" + density,
          "SELECT " + x + from + " WHERE coalesce(CAST(" + x + " AS BOOLEAN), false)", Map.of(),
          false));
      out.add(new Section("DOUBLE time_from_seconds(s)" + density,
          "SELECT time_from_seconds(" + s + ")" + from, time, false));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-floating-cast-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-floating-cast-baseline-on", true, false, "local[1]");
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
