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
 *   <li>{@code x < x2} and {@code x > 1.5D} as filters over {@code DOUBLE} columns, whose species
 *     at 128 bits also has two lanes, so the same masks fall back;
 *   <li>{@code l < l2} and {@code x < x2} as projections, a {@code BOOLEAN} output column
 *     (VARKA-27): the same compare to a mask as the filters', written as a bitmap rather than
 *     a selection, with no row work behind it to offset the fallback;
 *   <li>{@code l + l2} as a projection, under ANSI and under legacy arithmetic, which Varka does
 *     not fuse at all, as a control: both arms are the row path;
 *   <li>{@code l ^ l2} as a projection, lanewise with no mask in the loop;
 *   <li>{@code CASE WHEN l < l2 THEN l ELSE l2 END}, {@code greatest(l, l2)} and
 *     {@code if(l IS NULL, l2, l)}, the blends;
 *   <li>{@code x < y} as a {@code BOOLEAN} output over {@code DECIMAL(9, 2)}, one compare on the
 *     long lane, and over {@code DECIMAL(38, 10)} and {@code DECIMAL(20, 2)}, the lane pair's
 *     compare of two words a value (VARKA-596), over the decimal baseline's columns.
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
 * Beside them, {@code two-lane-refusal-off-128bit.txt} and its four-lane control
 * {@code two-lane-refusal-off-256bit.txt} time every filter fused, the refusal forced off with
 * {@code -Dspark.sql.codegen.varka.declineTwoLaneConditions=false}, the {@code DOUBLE} filters
 * among them, and {@code two-lane-boolean-output-128bit.txt} and {@code -256bit.txt} the
 * boolean outputs beside them ({@code m8/VARKA-503.md} 12).
 */
public class VarkaTwoLaneLongBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The decimal baseline's table, cut to the columns the decimal sections read. */
  static final String DECIMAL_VIEW = "varka_two_lane_decimal";

  /** The decimal sections' precisions: one on the long lane, then the lane pair's two. */
  static final List<VarkaDecimalBaselineBenchmark.Precision> DECIMAL_PRECISIONS = List.of(
      VarkaDecimalBaselineBenchmark.precision(9),
      VarkaDecimalBaselineBenchmark.PAIR_PRECISIONS.get(0),
      VarkaDecimalBaselineBenchmark.PAIR_PRECISIONS.get(1));

  /** A section: its title, its query, and whether it runs under legacy (wrapping) arithmetic. */
  record Section(String title, String query, boolean legacy) {}

  /**
   * The table, {@code varka_two_lane}: dates over about 55 years, longs below 10^12, ints below
   * 2*10^9 and doubles in [0, 3) in steps of 10^-6, from {@code xxhash64} of the row id hashed
   * with a seed per column, so the columns are independent, each comparison selects about half
   * the rows and no sum of two longs overflows;
   * then each column again with {@code n} appended, null one row in ten at a different row for
   * each column.
   */
  static String tableSql(int rows) {
    List<String> names = List.of("d", "d2", "l", "l2", "i", "x", "x2");
    List<String> values = new ArrayList<>();
    List<String> columns = new ArrayList<>(names);
    for (int seed = 0; seed < names.size(); seed++) {
      String h = "xxhash64(id, " + seed + ")";
      String name = names.get(seed);
      String value = switch (name.charAt(0)) {
        case 'd' -> "date_add(date '2000-01-01', cast(pmod(" + h + ", 20000) as int))";
        case 'i' -> "cast(pmod(" + h + ", 2000000000) as int)";
        case 'x' -> "cast(pmod(" + h + ", 3000000) as double) / 1000000D";
        default -> "pmod(" + h + ", 1000000000000)";
      };
      values.add(value + " as " + name);
      columns.add("case when id % 10 = " + seed + " then null else " + name + " end as "
          + name + "n");
    }
    return "select " + String.join(", ", columns) + " from (select id, "
        + String.join(", ", values) + " from range(0, " + rows + "))";
  }

  /** The query of {@link #DECIMAL_VIEW}: {@code x} and {@code y} of each decimal section. */
  static String decimalTableSql(int rows) {
    List<String> columns = new ArrayList<>();
    for (VarkaDecimalBaselineBenchmark.Precision p : DECIMAL_PRECISIONS) {
      for (String c : List.of("x", "y")) {
        columns.add(c + p.suffix());
        columns.add(c + p.suffix() + "n");
      }
    }
    return "SELECT " + String.join(", ", columns) + " FROM ("
        + VarkaDecimalBaselineBenchmark.tableSql(rows) + ")";
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
      out.add(new Section("x < x2 filter" + density,
          "SELECT x" + n + " FROM varka_two_lane WHERE x" + n + " < x2" + n, false));
      out.add(new Section("x > 1.5D filter" + density,
          "SELECT x" + n + " FROM varka_two_lane WHERE x" + n + " > 1.5D", false));
      out.add(new Section("(d < d2) AND (l < l2) filter" + density,
          "SELECT d" + n + " FROM varka_two_lane WHERE (d" + n + " < d2" + n + ") AND ("
              + l + " < " + l2 + ")", false));
      out.add(new Section("l < l2 boolean output projection" + density,
          "SELECT " + l + " < " + l2 + " AS c FROM varka_two_lane", false));
      out.add(new Section("x < x2 boolean output projection" + density,
          "SELECT x" + n + " < x2" + n + " AS c FROM varka_two_lane", false));
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
      // The decimal comparisons as values (VARKA-596), over the decimal baseline's columns: at
      // DECIMAL(9, 2) one compare on the long lane, at DECIMAL(38, 10) and DECIMAL(20, 2) the
      // lane pair's three word compares and two mask joins (`DecimalWords.compare`).
      for (VarkaDecimalBaselineBenchmark.Precision p : DECIMAL_PRECISIONS) {
        String x = "x" + p.suffix() + n;
        String y = "y" + p.suffix() + n;
        out.add(new Section(p.type() + " x < y boolean output projection" + density,
            "SELECT " + x + " < " + y + " AS c FROM " + DECIMAL_VIEW, false));
      }
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
        // The decimal baseline's table holds a TIME column, which needs the type switched on.
        session.conf().set("spark.sql.timeType.enabled", "true");
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView("varka_two_lane");
        VarkaArrowSessions.cache(session, "varka_two_lane");
        session.sql(decimalTableSql(NUM_ROWS)).createOrReplaceTempView(DECIMAL_VIEW);
        VarkaArrowSessions.cache(session, DECIMAL_VIEW);
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
