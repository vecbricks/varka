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
 * The long-lane shapes that do and do not make a {@code VectorMask} at the long species, against
 * Spark's row path, for the host where that species has two lanes (VARKA-503). On x86 C2 lowers
 * no mask of two 64-bit lanes ({@code m7/VARKA-374.md} 2), so a long kernel whose loop compares,
 * blends or checks overflow runs those operations as the Vector API's Java fallback; a long
 * kernel whose loop only computes lanewise does not. The class times one of each kind, so the
 * compiler's rule for which long kernels to decline at two lanes rests on a committed file:
 * <ul>
 *   <li>{@code l < l2} as a filter, a long condition and nothing else;
 *   <li>{@code (d < d2) AND (l < l2)} as a filter, an int and a long conjunct, which meet on the
 *     long lane when both fuse;
 *   <li>{@code l > 500000000000} and {@code i < l} as filters, against a literal and against a
 *     widened int column;
 *   <li>{@code l + l2} as a projection, under ANSI and under legacy arithmetic, which Varka does
 *     not fuse at all, as a control: both arms are the row path;
 *   <li>{@code l ^ l2} as a projection, lanewise with no mask in the loop;
 *   <li>{@code CASE WHEN l < l2 THEN l ELSE l2 END}, {@code greatest(l, l2)} and
 *     {@code if(l IS NULL, l2, l)}, the blends.
 * </ul>
 * Each runs over columns with no null, where the kernel takes its dense body, and over columns
 * with one row in ten null, where it takes the masked one.
 *
 * <p>Two arms per section: {@code spark row path (varka off)}, a session without the Varka rule,
 * and {@code varka on}. The class asserts nothing about what fuses: the same file is the before
 * and the after of the decline, and after it a declined shape's {@code varka on} arm is the row
 * path, or, for the mixed filter, the int conjunct's kernel with the long one on the row filter
 * behind it. Each section's note says which the {@code varka on} arm ran.
 *
 * <p>Its subject is x86 at two long lanes. The regeneration script's narrow run gives two lanes
 * but keeps the host's instruction set, so on this project's AVX-512 laptop the class is run under
 * {@code -XX:UseAVX=2 -XX:MaxVectorSize=16}, the EPYC 7763 runner's AVX2 at 128 bits. The before
 * and after of the decline are under {@code sql/varka/plans/m8/results/VARKA-503/}, each headed by
 * the command that took it:
 * <pre>
 *   build/sbt "project sql" \
 *     'set Test/javaOptions ++= Seq("-XX:MaxVectorSize=16", "-XX:UseAVX=2")' \
 *     "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaTwoLaneLongBenchmark"
 * </pre>
 */
public class VarkaTwoLaneLongBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** A section: its title, its query, and whether it runs under legacy (wrapping) arithmetic. */
  record Section(String title, String query, boolean legacy) {}

  /**
   * The table, {@code varka_two_lane}: dates over about 55 years, longs below 10^12 and ints below
   * 2*10^9, from {@code xxhash64} of the row id hashed with a seed per column, so the columns are
   * independent, each comparison selects about half the rows and no sum of two longs overflows;
   * then each column again with {@code n} appended, null one row in ten at a different row for
   * each column.
   */
  static String tableSql(int rows) {
    List<String> names = List.of("d", "d2", "l", "l2", "i");
    List<String> values = new ArrayList<>();
    List<String> columns = new ArrayList<>(names);
    for (int seed = 0; seed < names.size(); seed++) {
      String h = "xxhash64(id, " + seed + ")";
      String name = names.get(seed);
      String value = switch (name.charAt(0)) {
        case 'd' -> "date_add(date '2000-01-01', cast(pmod(" + h + ", 20000) as int))";
        case 'i' -> "cast(pmod(" + h + ", 2000000000) as int)";
        default -> "pmod(" + h + ", 1000000000000)";
      };
      values.add(value + " as " + name);
      columns.add("case when id % 10 = " + seed + " then null else " + name + " end as "
          + name + "n");
    }
    return "select " + String.join(", ", columns) + " from (select id, "
        + String.join(", ", values) + " from range(0, " + rows + "))";
  }

  /** Every section, each shape with no null and then with nulls. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    for (boolean nulls : new boolean[] {false, true}) {
      String n = nulls ? "n" : "";
      String density = nulls ? ", one row in ten null" : ", no nulls";
      String l = "l" + n;
      String l2 = "l2" + n;
      out.add(new Section("l < l2 filter" + density,
          "SELECT " + l + " FROM varka_two_lane WHERE " + l + " < " + l2, false));
      out.add(new Section("l > 500000000000 filter" + density,
          "SELECT " + l + " FROM varka_two_lane WHERE " + l + " > 500000000000", false));
      out.add(new Section("i < l filter" + density,
          "SELECT " + l + " FROM varka_two_lane WHERE i" + n + " < " + l, false));
      out.add(new Section("(d < d2) AND (l < l2) filter" + density,
          "SELECT d" + n + " FROM varka_two_lane WHERE (d" + n + " < d2" + n + ") AND ("
              + l + " < " + l2 + ")", false));
      out.add(new Section("l + l2 projection, ANSI" + density,
          "SELECT " + l + " + " + l2 + " AS c FROM varka_two_lane", false));
      out.add(new Section("l + l2 projection, legacy" + density,
          "SELECT " + l + " + " + l2 + " AS c FROM varka_two_lane", true));
      out.add(new Section("l ^ l2 projection" + density,
          "SELECT " + l + " ^ " + l2 + " AS c FROM varka_two_lane", false));
      out.add(new Section("CASE WHEN l < l2 THEN l ELSE l2 END projection" + density,
          "SELECT CASE WHEN " + l + " < " + l2 + " THEN " + l + " ELSE " + l2
              + " END AS c FROM varka_two_lane", false));
      out.add(new Section("greatest(l, l2) projection" + density,
          "SELECT greatest(" + l + ", " + l2 + ") AS c FROM varka_two_lane", false));
      out.add(new Section("if(l IS NULL, l2, l) projection" + density,
          "SELECT if(" + l + " IS NULL, " + l2 + ", " + l + ") AS c FROM varka_two_lane",
          false));
    }
    return out;
  }

  /** What the Varka session's plan for {@code query} runs: a kernel, or Spark's row path. */
  static String runs(SparkSession session, String query) {
    return holdsVarkaNode(session.sql(query).queryExecution().executedPlan())
        ? "a Varka kernel" : "Spark's row path";
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-two-lane-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-two-lane-on", true, false, "local[1]");
    clearSessions();
    require(baseline != varka, "the two sessions must be distinct or there is no baseline");
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView("varka_two_lane");
        VarkaArrowSessions.cache(session, "varka_two_lane");
      }
      for (Section s : sections()) {
        Map<String, String> settings = s.legacy()
            ? Map.of(SQLConf.ANSI_ENABLED().key(), "false") : Map.of();
        withConf(baseline, settings, () -> withConf(varka, settings, () ->
            section(s.title(), () -> {
              VarkaBenchmark benchmark = benchmark(
                  s.title() + " over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS)
                  .timed().build();
              benchmark.addCase("spark row path (varka off)",
                  i -> noop(baseline.sql(s.query())));
              benchmark.addCase("varka on", i -> noop(varka.sql(s.query())));
              runCases(benchmark);
              note(s.title() + ": the varka on arm runs " + runs(varka, s.query()));
            })));
      }
    } finally {
      baseline.stop();
      varka.stop();
    }
  }
}
