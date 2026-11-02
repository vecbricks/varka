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

package org.apache.spark.sql.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import scala.jdk.javaapi.CollectionConverters;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.expressions.And;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.execution.benchmark.VarkaArrowSessions;
import org.apache.spark.sql.execution.metric.SQLMetric;

/**
 * The two-lane refusal at its production default, on the host it is for (VARKA-503): a JVM of its
 * own under AVX2 at 128 bits, where the 64-bit species have two lanes, with sessions that leave
 * {@code spark.sql.codegen.varka.declineTwoLaneConditions} unset. Every other sql/core suite that
 * checks the kernels' answers sets the refusal off ({@code VarkaSharedSessions}), so that the
 * gate's 128-bit step still tests kernels, and nothing else would run the refusal as a user meets
 * it.
 *
 * <p>Each query runs over columns with no nulls and over columns with one row in ten null; both
 * are nullable in type, so Spark infers an {@code IS NOT NULL} beside each comparison, which is
 * refused with it where the column is long or {@code DOUBLE}. It checks the answers against a
 * session with Varka off, that no conjunct over a long or a {@code DOUBLE} column is a Varka
 * filter's, and that the plan's classification and the tasks' agree: the Varka node's own compile
 * of its condition under the plan's settings fuses every conjunct, and its kernels served the
 * batches, which the filter evaluator in a task does only where its compile, under the settings
 * the plan carried to it, fused every conjunct too ({@code VarkaFilterEvaluator.predicate}).
 *
 * <p>On a JVM that is not x86 the flags are ignored and nothing is refused; the session's default
 * is checked false there and the queries are not run.
 */
@VarkaSuite(ownJvm = true,
    jvmFlags = {"-XX:+IgnoreUnrecognizedVMOptions", "-XX:UseAVX=2", "-XX:MaxVectorSize=16"})
public final class VarkaTwoLaneDefault128Suite {

  private static final int ROWS = 20_000;

  /** The columns a Varka filter may hold on this host: dates and ints. */
  private static final Set<String> ADMITTED = Set.of("d", "d2", "i", "i2");

  private static SparkSession varka;
  private static SparkSession off;

  @BeforeAll
  static void start() {
    if (!VarkaTwoLaneHostCheck.isX86()) {
      return;
    }
    off = VarkaArrowSessions.createSession("two-lane-default-off", false, false, "local[2]");
    SparkSession.clearActiveSession();
    SparkSession.clearDefaultSession();
    varka = VarkaArrowSessions.createSession("two-lane-default-on", true, false, "local[2]");
    SparkSession.clearActiveSession();
    SparkSession.clearDefaultSession();
    for (SparkSession session : List.of(off, varka)) {
      session.sql(tableSql()).createOrReplaceTempView("two_lane_default");
      VarkaArrowSessions.cache(session, "two_lane_default");
    }
  }

  @AfterAll
  static void stop() {
    if (varka != null) {
      varka.stop();
    }
  }

  /**
   * Dates, longs, ints and doubles from {@code xxhash64} of the row id with a seed per column,
   * then each column again with {@code n} appended, null one row in ten.
   */
  private static String tableSql() {
    List<String> names = List.of("d", "d2", "l", "l2", "i", "i2", "x", "x2");
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
        + String.join(", ", values) + " from range(0, " + ROWS + "))";
  }

  @Test
  @DisplayName("the session's default is the host's: the refusal on x86 at 128 bits")
  void sessionDefault() {
    VarkaTwoLaneHostCheck.check(0, true);
  }

  @Test
  @DisplayName("long and DOUBLE filters are the row filter's at the default, with Varka's answers")
  void sixtyFourBitFiltersDecline() {
    for (String n : List.of("", "n")) {
      check("SELECT l" + n + " FROM two_lane_default WHERE l" + n + " < l2" + n, false);
      check("SELECT x" + n + " FROM two_lane_default WHERE x" + n + " < x2" + n, false);
      check("SELECT x" + n + " FROM two_lane_default WHERE x" + n + " > 1.5D", false);
      check("SELECT x" + n + " FROM two_lane_default WHERE x" + n + " > 1.5D AND l" + n
          + " > l2" + n, false);
    }
  }

  @Test
  @DisplayName("a date or int conjunct beside them keeps its kernel, and the tasks agree")
  void otherConjunctsFuse() {
    for (String n : List.of("", "n")) {
      check("SELECT i" + n + " FROM two_lane_default WHERE i" + n + " < i2" + n, true);
      // `i < l` compares on the long lane and is the row filter's; the `isnotnull(i)` Spark
      // infers beside it is an int kernel of its own (m8/VARKA-503.md 9).
      check("SELECT l" + n + " FROM two_lane_default WHERE i" + n + " < l" + n, true);
      check("SELECT d" + n + " FROM two_lane_default WHERE d" + n + " < d2" + n + " AND l" + n
          + " < l2" + n, true);
      check("SELECT d" + n + " FROM two_lane_default WHERE d" + n + " < d2" + n + " AND x" + n
          + " > 1.5D", true);
    }
  }

  /**
   * Runs {@code query} in both sessions and checks the Varka session's plan: a Varka filter
   * exactly when {@code fuses}, holding only date and int conjuncts, classified alike by the
   * driver and the tasks; and the answers, against Varka off.
   */
  private static void check(String query, boolean fuses) {
    assumeTrue(varka != null, "not x86: the refusal does not apply");
    Dataset<Row> actual = varka.sql(query);
    List<String> got = sorted(actual.collectAsList());
    List<String> expected = sorted(off.sql(query).collectAsList());
    SparkPlan plan = actual.queryExecution().executedPlan();
    String clue = query + "\n" + plan.treeString();
    assertEquals(expected, got, clue);
    List<VarkaFilterExecBase> filters = new ArrayList<>();
    collect(plan, filters);
    assertEquals(fuses, !filters.isEmpty(), clue);
    for (VarkaFilterExecBase filter : filters) {
      assertTrue(filter.sessionEmit().declineTwoLaneConditions(), clue);
      for (Expression conjunct : conjuncts(filter.condition())) {
        for (Attribute a : CollectionConverters.asJava(conjunct.references().toSeq())) {
          assertTrue(ADMITTED.contains(a.name().replaceAll("n$", "")),
              "a conjunct over " + a.name() + " is a Varka filter's: " + clue);
        }
      }
      // The driver's compile of the node's condition, as the tasks compile it.
      assertTrue(filter.classification().isPresent(), clue);
      assertTrue(filter.classification().get().residualConjuncts().isEmpty(), clue);
      // The tasks' compile: an evaluator whose compile left a conjunct out serves no batch.
      SQLMetric batches = ((SparkPlan) filter).metrics().get("numVarkaBatches").get();
      assertTrue(batches.value() > 0, "the kernels served no batch: " + clue);
    }
  }

  private static void collect(SparkPlan plan, List<VarkaFilterExecBase> out) {
    if (plan instanceof VarkaFilterExecBase filter) {
      out.add(filter);
    }
    for (SparkPlan child : CollectionConverters.asJava(plan.children())) {
      collect(child, out);
    }
  }

  private static List<Expression> conjuncts(Expression condition) {
    if (condition instanceof And and) {
      List<Expression> out = new ArrayList<>(conjuncts(and.left()));
      out.addAll(conjuncts(and.right()));
      return out;
    }
    return List.of(condition);
  }

  private static List<String> sorted(List<Row> rows) {
    return rows.stream().map(Row::toString).sorted().toList();
  }
}
