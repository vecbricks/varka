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
 * The baseline for the rounding functions before Varka fuses them (VARKA-344): Spark's row path
 * over an Arrow-cached table of {@code DOUBLE}, {@code INT} and {@code BIGINT} columns, one section
 * per function and null density, so that the row reports what it changes against a committed
 * file.
 *
 * <p>The functions are {@code rint(x)}; {@code floor(x)} and {@code ceil(x)} over a double, which
 * answer a {@code BIGINT}, under LEGACY and under ANSI; {@code floor(i)} over an {@code INT}, which
 * Spark plans as {@code FLOOR(CAST(i AS DOUBLE))}; {@code round(i, -2)} and {@code bround(i, -2)};
 * {@code truncate(l, -3)}; {@code round(l, -3)} under ANSI; {@code factorial(k)} over values in
 * {@code [-2, 22]}, null outside {@code [0, 20]}; and {@code width_bucket(x, -2.0E8, 2.0E8, 10)}
 * with literal bounds; each with no nulls and with one row in ten null. The functions other than
 * the double {@code floor} and {@code ceil} and the {@code BIGINT} {@code round} have one answer in
 * every mode over this table and run in the session's default one: no {@code INT} overflows when
 * rounded to a multiple of 100, and a truncation never increases a magnitude.
 *
 * <p>Each section has two arms, named so the labels survive the row landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Today no section plans a Varka node, so both arms are the row path and should
 * tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@code fuses} flag, the list of
 * the sections a row has fused, and fails when an unlisted section fuses or a listed one does not;
 * the results file notes each section that fuses. No section is listed. A row that fuses a
 * section lists it, so that its fusion cannot turn a baseline section into a fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaRoundingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses unlisted sections against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaRoundingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaRoundingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaRoundingBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_rounding";

  /** The literal bounds of the {@code width_bucket} section, inside {@code x}'s range. */
  static final String BUCKET_BOUNDS = "-2.0E8D, 2.0E8D, 10";

  /**
   * A section: its title, its query, the SQL settings it runs under on both sessions, empty for a
   * function whose answer does not depend on them, and whether the Varka session fuses it, which
   * the run checks before it times anything.
   */
  record Section(String title, String query, Map<String, String> conf, boolean fuses) {}

  /**
   * The table: {@code x} a {@code DOUBLE}, {@code i} and {@code k} {@code INT}s and {@code l} a
   * {@code BIGINT}, with no null, and {@code x_n} to {@code l_n} the same values with one row in
   * ten null, at a different row in each column. Each column is a hash of the row's id, so any
   * few thousand rows reach every region below, as the queries suite checks.
   *
   * <ul>
   *   <li>{@code x} is {@code hash(id, 1) / 7.0}, within {@code +-2^31 / 7}, so its fractions
   *       keep many bits and its values lie far inside the long range, where ANSI's
   *       {@code floor} and {@code ceil} never raise, and inside {@code 2^51}, past which the
   *       bounded AVX2 form of row 344 declines a batch: a share past it, however small, would
   *       decline every batch of that arm and leave nothing to measure. One row in eight is
   *       instead a tie, an integer plus {@code 0.5}, where {@code rint} picks the even
   *       neighbour; one row in eight is {@code 0.0} and another {@code -0.0}, whose sign
   *       {@code rint} keeps; and one row in a thousand is NaN, which {@code floor} and
   *       {@code ceil} answer 0 for in both modes and {@code width_bucket} null, none of them
   *       among {@code x_n}'s nulls. About three rows in four lie inside the bucket bounds, and
   *       the rest below or above them. There is no infinity, on which ANSI's {@code floor}
   *       would raise and end the run.</li>
   *   <li>{@code i} is {@code hash(id)}, over the whole int range.</li>
   *   <li>{@code l} is {@code xxhash64(id)} shifted right by {@code 9 + id % 55}, so its
   *       magnitudes spread evenly over the bit lengths up to 54: about one row in forty past
   *       {@code 2^52}, where the long lane's division by a constant stops being exact, and none
   *       past {@code 2^54}, so none near the long's edge, where ANSI's {@code round(l, -3)}
   *       would raise, and none past the {@code 2^55} guard row 344 plans for {@code k = 3}.</li>
   *   <li>{@code k} is {@code pmod(hash(id, 4), 25) - 2}, in {@code [-2, 22]}, so about four
   *       rows in twenty-five are outside {@code [0, 20]}, where {@code factorial} answers
   *       null.</li>
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
        select x, i, l, k,
               case when id %% 10 = 5 then null else x end as x_n,
               case when id %% 10 = 3 then null else i end as i_n,
               case when id %% 10 = 1 then null else l end as l_n,
               case when id %% 10 = 7 then null else k end as k_n
        from (select id,
                     case when id %% 1000 = 11 then double('NaN')
                          when id %% 8 = 0 then 0.0d
                          when id %% 8 = 4 then -0.0d
                          when id %% 8 = 2 then cast(hash(id, 1) div 7 as double) + 0.5d
                          else hash(id, 1) / 7.0d end as x,
                     hash(id) as i,
                     shiftright(xxhash64(id), cast(9 + id %% 55 as int)) as l,
                     pmod(hash(id, 4), 25) - 2 as k
              from %s)""".formatted(ids);
  }

  /** Every section, in the order they run: each null density, then each function. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    Map<String, String> legacy = Map.of(SQLConf.ANSI_ENABLED().key(), "false");
    Map<String, String> ansi = Map.of(SQLConf.ANSI_ENABLED().key(), "true");
    for (String nulls : new String[] {"", "_n"}) {
      String x = "x" + nulls;
      String i = "i" + nulls;
      String l = "l" + nulls;
      String k = "k" + nulls;
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " AS a FROM " + VIEW;
      out.add(new Section("DOUBLE rint(x)" + density,
          "SELECT rint(" + x + ")" + from, Map.of(), false));
      out.add(new Section("DOUBLE floor(x), LEGACY" + density,
          "SELECT floor(" + x + ")" + from, legacy, false));
      out.add(new Section("DOUBLE floor(x), ANSI" + density,
          "SELECT floor(" + x + ")" + from, ansi, false));
      out.add(new Section("DOUBLE ceil(x), LEGACY" + density,
          "SELECT ceil(" + x + ")" + from, legacy, false));
      out.add(new Section("DOUBLE ceil(x), ANSI" + density,
          "SELECT ceil(" + x + ")" + from, ansi, false));
      out.add(new Section("INT floor(i)" + density,
          "SELECT floor(" + i + ")" + from, Map.of(), false));
      out.add(new Section("INT round(i, -2)" + density,
          "SELECT round(" + i + ", -2)" + from, Map.of(), false));
      out.add(new Section("INT bround(i, -2)" + density,
          "SELECT bround(" + i + ", -2)" + from, Map.of(), false));
      out.add(new Section("BIGINT truncate(l, -3)" + density,
          "SELECT truncate(" + l + ", -3)" + from, Map.of(), false));
      out.add(new Section("BIGINT round(l, -3), ANSI" + density,
          "SELECT round(" + l + ", -3)" + from, ansi, false));
      out.add(new Section("INT factorial(k)" + density,
          "SELECT factorial(" + k + ")" + from, Map.of(), false));
      out.add(new Section("DOUBLE width_bucket(x, lo, hi, 10)" + density,
          "SELECT width_bucket(" + x + ", " + BUCKET_BOUNDS + ")" + from, Map.of(), false));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-rounding-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-rounding-baseline-on", true, false, "local[1]");
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
