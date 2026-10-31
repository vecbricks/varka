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
 * The baseline for {@code DOUBLE} and {@code FLOAT} before Varka has a lane for them (VARKA-377):
 * Spark's row path over an Arrow-cached table, in four sections per type and null density, so that
 * the floating lanes' rows report what they change against a committed file.
 *
 * <p>The sections are a comparison ({@code x < y} as a filter), an arithmetic chain of three nodes
 * ({@code (x + y) * z - x}), a cast to the type's widest neighbour ({@code DOUBLE} to
 * {@code BIGINT}, {@code FLOAT} to {@code DOUBLE}) and a choice
 * ({@code CASE WHEN x < y THEN x ELSE y END}), each with no nulls and with one row in ten null.
 * Each has two arms, named so the labels survive the lanes landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Today both are the
 * row path, so they should tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@code fuses} flag, the list of
 * the sections a row has fused, and fails when an unlisted section fuses or a listed one does not.
 * The comparison and choice sections are listed, since the floating lanes order their values, and
 * the results file notes each of them as fusing; the chain and the cast are still the row path in
 * both arms. A row that starts to fuse another section lists it, so that its fusion cannot turn a
 * baseline section into a fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaFloatingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses unlisted sections against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaFloatingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaFloatingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaFloatingBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /**
   * A section: its title, its query, and whether the Varka session fuses it, which the run
   * checks before it times anything.
   */
  record Section(String title, String query, boolean fuses) {}

  /**
   * The table: {@code x}, {@code y}, {@code z} as doubles and {@code f}, {@code g}, {@code h} as
   * floats with no null, and {@code xn} to {@code hn} the same values with one row in ten null, at
   * a different row in each column. The values are spread over a few thousand either side of zero,
   * so the comparisons select about half the rows and no product or cast leaves its range.
   */
  static String tableSql(int rows) {
    return """
        select x, y, z, cast(x as float) as f, cast(y as float) as g, cast(z as float) as h,
               case when id %% 10 = 1 then null else x end as xn,
               case when id %% 10 = 4 then null else y end as yn,
               case when id %% 10 = 7 then null else z end as zn,
               case when id %% 10 = 2 then null else cast(x as float) end as fn,
               case when id %% 10 = 5 then null else cast(y as float) end as gn,
               case when id %% 10 = 8 then null else cast(z as float) end as hn
        from (select id,
                     (id %% 100003) / 7.0d - 7000.0d as x,
                     ((id * 7) %% 100019) / 11.0d - 4500.0d as y,
                     ((id * 13) %% 1009) / 3.0d as z
              from range(0, %d))""".formatted(rows);
  }

  /** Every section, in the order they run: each type, then each null density, then each shape. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    String[][] types = {{"DOUBLE", "x", "y", "z", "BIGINT"}, {"FLOAT", "f", "g", "h", "DOUBLE"}};
    for (String[] t : types) {
      for (String nulls : new String[] {"", "n"}) {
        String x = t[1] + nulls;
        String y = t[2] + nulls;
        String z = t[3] + nulls;
        String density = nulls.isEmpty() ? "no nulls" : "one row in ten null";
        String type = t[0];
        out.add(new Section(type + " comparison, " + density,
            "SELECT " + x + " FROM varka_floating WHERE " + x + " < " + y, true));
        out.add(new Section(type + " arithmetic chain, " + density,
            "SELECT (" + x + " + " + y + ") * " + z + " - " + x + " AS a FROM varka_floating",
            false));
        out.add(new Section(type + " cast to " + t[4] + ", " + density,
            "SELECT CAST(" + x + " AS " + t[4] + ") AS a FROM varka_floating", false));
        out.add(new Section(type + " choice, " + density,
            "SELECT CASE WHEN " + x + " < " + y + " THEN " + x + " ELSE " + y
                + " END AS a FROM varka_floating", true));
      }
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-floating-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-floating-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView("varka_floating");
        VarkaArrowSessions.cache(session, "varka_floating");
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
