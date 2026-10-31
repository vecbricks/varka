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
import java.util.Optional;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;
import org.apache.spark.sql.execution.SparkPlan;

/**
 * Boolean expressions as projection outputs (VARKA-27): {@code SELECT d < d2 AS c} rather than
 * {@code WHERE d < d2}. Varka fuses such a condition in a filter but declines it as an output
 * today, so a query that returns a boolean runs on Spark's row path. This class is committed first
 * on that unchanged code, so the rows that build the output report what they change against a
 * committed results file.
 *
 * <p>The six outputs cover each lane a condition runs on: {@code d < d2} (DATE, the int lane),
 * {@code l < l2} (BIGINT, the long lane), {@code y1 < y2} (TINYINT, a narrow condition widened to
 * the int lane), {@code NOT b} and {@code b AND b2} (BOOLEAN columns read as conditions) and
 * {@code (d < d2) AND (l < l2)} (an int and a long condition joined). Each runs over columns with
 * no null, where the kernel takes its dense body, and over columns with one row in ten null, where
 * it takes the masked one.
 *
 * <p>Beside each output is its filter control: the same condition as a {@code WHERE} clause,
 * selecting its first column. The kernel's work is the same in both, the condition over every
 * row, but what follows it is not: an output hands all its rows to the consumer, a filter only
 * the rows it keeps, about half for a comparison and a quarter for an {@code AND}. So a fused
 * output is expected to sit above its control's {@code varka on} time by that downstream cost,
 * and a diff between the two reads the kernel's share only with it taken out.
 *
 * <p>Each section has two arms, named so the labels survive the kernels landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Today the two arms of an output section are both the row path, so they should
 * tie, while a control's {@code varka on} arm is Varka's fused filter.
 *
 * <p>A baseline is only one while nothing fuses: before timing, the run fails if the Varka
 * session's plan for any output section holds a Varka node. The filter controls are held to the
 * opposite, since they are the comparison, not the baseline: the run fails if a control's plan
 * holds no Varka node, so a control that declined cannot pass as Varka's filter.
 *
 * <p>The later diff partners, by the steps of row 27's plan ({@code VARKA-27.md} 5):
 * <ul>
 *   <li>every output section against the same section after step 4, which makes the condition a
 *     projection root written as a packed bitmap, measured in step 6 at both writers (per lane
 *     group and per word);
 *   <li>the dense output sections also against step 5's materialized form, which applies to the
 *     dense body only;
 *   <li>each filter control against its own output section, by the {@code varka on} arm, at every
 *     step.
 * </ul>
 * Step 4 makes the output sections fuse, so the class's check then fails by design; step 6 drops
 * the check and times the same sections. At 128 bits on x86 the long sections ({@code l < l2} and
 * the joined one) will then pay the Java fallback of {@code VectorMask.toLong} in their dense
 * body, which the plan records as an expected result rather than a regression.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaBooleanOutputBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 */
public class VarkaBooleanOutputBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /**
   * A section: its title, its query, and whether it is a filter control, which the baseline
   * check exempts.
   */
  record Section(String title, String query, boolean control) {}

  /**
   * A condition the class measures: its name in the titles, the SQL of the condition over the
   * columns with no null and over the columns with nulls, and the column a filter control selects.
   */
  record Condition(String name, String dense, String masked, String selected) {}

  /**
   * A column pair of the table: the two names, their SQL type, and the expression of a value from
   * a hash value {@code h} of the row id (with {@code %s} standing for {@code h}).
   */
  record Pair(String first, String second, String type, String value) {}

  /**
   * The column pairs. Every value comes from {@code xxhash64} of the row id and a number of the
   * column's own, hashed as a second column, so the two columns of a pair are independent and
   * each comparison selects about half the rows. Dates spread over about 55 years from 2000, longs
   * over the whole range of a long, tinyints over the whole range of a byte, and booleans are true
   * for about half the rows.
   */
  static final List<Pair> PAIRS = List.of(
      new Pair("d", "d2", "DATE",
          "date_add(date '2000-01-01', cast(pmod(%s, 20000) as int))"),
      new Pair("l", "l2", "BIGINT", "%s"),
      new Pair("y1", "y2", "TINYINT", "cast(pmod(%s, 256) - 128 as tinyint)"),
      new Pair("b", "b2", "BOOLEAN", "pmod(%s, 2) = 0"));

  /**
   * The conditions. A column with nulls is the column's name with {@code n} appended, so the
   * masked form of a condition is the dense one over those columns.
   */
  static final List<Condition> CONDITIONS = List.of(
      new Condition("d < d2", "d < d2", "dn < d2n", "d"),
      new Condition("l < l2", "l < l2", "ln < l2n", "l"),
      new Condition("y1 < y2", "y1 < y2", "y1n < y2n", "y1"),
      new Condition("NOT b", "NOT b", "NOT bn", "b"),
      new Condition("b AND b2", "b AND b2", "bn AND b2n", "b"),
      new Condition("(d < d2) AND (l < l2)", "(d < d2) AND (l < l2)", "(dn < d2n) AND (ln < l2n)",
          "d"));

  /**
   * The table, {@code varka_boolean}: each column of {@link #PAIRS} with no null, then each again
   * with {@code n} appended and null one row in ten, at a different row of each ten for every
   * column (the row id modulo ten is 0 for {@code dn}, 1 for {@code d2n}, and so on), so a
   * condition over two columns meets rows where one, the other or neither is null.
   */
  static String tableSql(int rows) {
    List<String> values = new ArrayList<>();
    List<String> plain = new ArrayList<>();
    List<String> nullable = new ArrayList<>();
    int seed = 0;
    for (Pair p : PAIRS) {
      for (String name : List.of(p.first(), p.second())) {
        values.add(p.value().formatted("xxhash64(id, " + seed + ")") + " as " + name);
        plain.add(name);
        nullable.add("case when id % 10 = " + seed + " then null else " + name + " end as "
            + name + "n");
        seed++;
      }
    }
    List<String> columns = new ArrayList<>(plain);
    columns.addAll(nullable);
    return "select " + String.join(", ", columns) + " from (select id, "
        + String.join(", ", values) + " from range(0, " + rows + "))";
  }

  /**
   * Every section, in the order they run: each condition, then each null density, then the output
   * and its filter control.
   */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    for (Condition c : CONDITIONS) {
      for (boolean nulls : new boolean[] {false, true}) {
        String cond = nulls ? c.masked() : c.dense();
        String selected = c.selected() + (nulls ? "n" : "");
        String density = nulls ? "one row in ten null" : "no nulls";
        out.add(new Section(c.name() + " output, " + density,
            "SELECT " + cond + " AS c FROM varka_boolean", false));
        out.add(new Section(c.name() + " filter control, " + density,
            "SELECT " + selected + " FROM varka_boolean WHERE " + cond, true));
      }
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-boolean-output-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-boolean-output-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView("varka_boolean");
        VarkaArrowSessions.cache(session, "varka_boolean");
      }
      for (Section s : sections()) {
        Optional<SparkPlan> fused = fusedPlan(varka, s.query());
        if (fused.isPresent() != s.control()) {
          // A control that did not fuse comes back with no plan; planning it again for the
          // message costs nothing on a run that is about to fail.
          SparkPlan plan =
              fused.orElseGet(() -> varka.sql(s.query()).queryExecution().executedPlan());
          String why = s.control() ? " is a filter control that does not fuse, so its varka on "
              + "arm is not Varka's filter: " : " fuses, so it is no longer a baseline: ";
          throw new IllegalStateException(s.title() + why + s.query() + "\n" + plan.treeString());
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
