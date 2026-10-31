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
 * The baseline for {@code TIMESTAMP_NTZ} before Varka has a lane for it (VARKA-377): Spark's row
 * path over an Arrow-cached table, in four sections per null density, so that the lane's rows
 * report what they change against a committed file.
 *
 * <p>The sections are a comparison ({@code ntz < ntz2} as a filter), an arithmetic chain of three
 * nodes ({@code ntz + (ntz2 - ntz3) - INTERVAL '1' HOUR}, the timestamp arithmetic being with
 * intervals), a cast to {@code DATE}, the type's widest neighbour, and a choice
 * ({@code CASE WHEN ntz < ntz2 THEN ntz ELSE ntz2 END}), each with no nulls and with one row in ten
 * null. Each has two arms, named so the labels survive the lane landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Where a section is listed as fusing, the {@code varka on} arm times a kernel.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@code fuses} flag, the list of
 * the sections a row has fused, and fails when an unlisted section fuses or a listed one does not.
 * The comparison, the cast and the choice are listed, since the {@code TIMESTAMP_NTZ} lane fuses
 * them (VARKA-338), and the results file notes each of them as fusing; the arithmetic chain is
 * still the row path in both arms. A row that starts to fuse another section lists it, so that
 * its fusion cannot turn a baseline section into a fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaTimestampNtzBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 */
public class VarkaTimestampNtzBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** A section: its title, its query, and whether the Varka session fuses it today. */
  record Section(String title, String query, boolean fuses) {}

  /**
   * The table: {@code ntz}, {@code ntz2} and {@code ntz3} with no null, spread over ten years from
   * 2020 at a second's grain and a different stride each, and {@code ntzn} to {@code ntz3n} the
   * same values with one row in ten null, at a different row in each column.
   */
  static String tableSql(int rows) {
    return """
        select ntz, ntz2, ntz3,
               case when id %% 10 = 1 then null else ntz end as ntzn,
               case when id %% 10 = 4 then null else ntz2 end as ntz2n,
               case when id %% 10 = 7 then null else ntz3 end as ntz3n
        from (select id,
                     timestamp_ntz'2020-01-01 00:00:00' + make_dt_interval(
                         cast(id %% 3650 as int), 0, 0,
                         cast((id * 7) %% 86400 as decimal(18, 6))) as ntz,
                     timestamp_ntz'2020-01-01 00:00:00' + make_dt_interval(
                         cast((id * 11) %% 3650 as int), 0, 0,
                         cast((id * 13) %% 86400 as decimal(18, 6))) as ntz2,
                     timestamp_ntz'2020-01-01 00:00:00' + make_dt_interval(
                         cast((id * 17) %% 3650 as int), 0, 0,
                         cast((id * 19) %% 86400 as decimal(18, 6))) as ntz3
              from range(0, %d))""".formatted(rows);
  }

  /** Every section, in the order they run: each null density, then each shape. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    for (String nulls : new String[] {"", "n"}) {
      String a = "ntz" + nulls;
      String b = "ntz2" + nulls;
      String c = "ntz3" + nulls;
      String density = nulls.isEmpty() ? "no nulls" : "one row in ten null";
      out.add(new Section("TIMESTAMP_NTZ comparison, " + density,
          "SELECT " + a + " FROM varka_ntz WHERE " + a + " < " + b, true));
      out.add(new Section("TIMESTAMP_NTZ arithmetic chain, " + density,
          "SELECT " + a + " + (" + b + " - " + c + ") - INTERVAL '1' HOUR AS v FROM varka_ntz",
          false));
      out.add(new Section("TIMESTAMP_NTZ cast to DATE, " + density,
          "SELECT CAST(" + a + " AS DATE) AS v FROM varka_ntz", true));
      out.add(new Section("TIMESTAMP_NTZ choice, " + density,
          "SELECT CASE WHEN " + a + " < " + b + " THEN " + a + " ELSE " + b
              + " END AS v FROM varka_ntz", true));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-ntz-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-ntz-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView("varka_ntz");
        VarkaArrowSessions.cache(session, "varka_ntz");
      }
      for (Section s : sections()) {
        requireBaseline(varka, s.title(), s.query(), s.fuses());
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
