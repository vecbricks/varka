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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import scala.Tuple2;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Alias;
import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every row of the published coverage table held to Spark's answer with no session (VARKA-509,
 * {@code m8/VARKA-509.md}), on {@code VarkaCoverageDifferentialSuite}'s three answer fixtures,
 * through {@link VarkaExpressionHarness}: each row's answer under both consumers - a projection
 * row through the columnar node's {@code project} and the row node's {@code projectFused}, a
 * predicate row through the filter evaluator - each predicate row's partition oracle
 * ({@link VarkaPartitionOracle}), its null tests as CASE conditions and its answer as a
 * {@code BOOLEAN} output column (VARKA-27), each row's ANSI form
 * ({@link VarkaAnsiForm}), and the domains of the rows whose kernel can decline a batch
 * ({@link VarkaDomains}), the last two on fixtures of their own. Each row of each form is one
 * test under the name the end-to-end suite gave it, so the option matrix's record of what a
 * configuration moves keeps a row's granularity and the skip list's lines read alike.
 *
 * <p>The kernel must answer some batch of a row on some fixture: a row whose kernel declined
 * everything would otherwise pass on the row path's answers alone.
 */
@VarkaSuite
public final class VarkaCoverageHarnessSuite {

  /**
   * One row of {@code sql/varka/coverage.json}: what it prints, what runs, which form it takes,
   * and, for a row whose kernel can decline a batch, the domain its kernel answers inside.
   */
  record Row(String sql, String executable, String family, String form,
      Optional<String> domain) {}

  private static final List<Row> ROWS = readRows();

  /** The table's rows, which {@link VarkaHarnessParitySuite} plans in a session too. */
  static List<Row> rows() {
    return ROWS;
  }

  private static final Map<String, VarkaHarnessPlans.Fixture> FIXTURES = readFixtures();

  private static List<Row> readRows() {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    if (home == null) {
      throw new IllegalStateException("spark.test.home or SPARK_HOME is not set");
    }
    try {
      JsonNode doc = new ObjectMapper().readTree(
          Path.of(home, "sql", "varka", "coverage.json").toFile());
      var rows = new ArrayList<Row>();
      for (JsonNode e : doc.get("expressions")) {
        JsonNode domain = e.get("domain");
        rows.add(new Row(e.get("sql").asText(), e.get("executable").asText(),
            e.get("family").asText(), e.get("form").asText(),
            Optional.ofNullable(domain).map(JsonNode::asText).filter(d -> !d.isEmpty())));
      }
      return rows;
    } catch (IOException e) {
      throw new IllegalStateException("cannot read sql/varka/coverage.json", e);
    }
  }

  private static Map<String, VarkaHarnessPlans.Fixture> readFixtures() {
    var out = new LinkedHashMap<String, VarkaHarnessPlans.Fixture>();
    for (Tuple2<String, String> f :
        CollectionConverters.asJava(VarkaCoverageDifferentialSuite$.MODULE$.fixtures())) {
      out.put(f._1(), VarkaHarnessPlans.fixture(f._2()));
    }
    return out;
  }

  @BeforeAll
  static void matrixOptions() {
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE);
  }

  @AfterAll
  static void defaultOptions() {
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS);
  }

  /**
   * The conf the harness's batches and Spark's answers read: the thread's, with the matrix's
   * rows per Arrow batch laid over it where the configuration sets one. The sessions of the
   * end-to-end suites take it from {@code VarkaSharedSessions}; here the harness's own batching
   * stands in for the cache's, so without it {@code arrowBatchSize=1}, {@code 3} and {@code 17}
   * would run each fixture as one batch, as the defaults do.
   */
  static SQLConf conf(boolean ansi) {
    SQLConf conf = SQLConf.get().clone();
    VarkaMatrixRuns.ARROW_BATCH_SIZE_ROWS.ifPresent(rows -> conf.setConfString(
        SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH().key(), Integer.toString(rows)));
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), Boolean.toString(ansi));
    return conf;
  }

  /** {@code body} with {@code conf} as the thread's conf, which the analyzer reads ANSI from. */
  static <T> T withConf(SQLConf conf, java.util.function.Supplier<T> body) {
    return SQLConf.withExistingConf(conf, body::get);
  }

  /** {@code row} on every answer fixture: what the checks found, and whether a kernel answered. */
  private static VarkaExpressionHarness.Outcome check(Row row) {
    SQLConf conf = conf(SQLConf.get().ansiEnabled());
    return withConf(conf, () -> checkUnder(row, conf));
  }

  private static VarkaExpressionHarness.Outcome checkUnder(Row row, SQLConf conf) {
    var all = new VarkaExpressionHarness.Outcome();
    for (Map.Entry<String, VarkaHarnessPlans.Fixture> f : FIXTURES.entrySet()) {
      String context = f.getKey() + ", " + row.family() + ": " + row.sql();
      VarkaHarnessPlans.Fixture fixture = f.getValue();
      var outcomes = new ArrayList<VarkaExpressionHarness.Outcome>();
      if (row.form().equals("predicate")) {
        outcomes.add(VarkaExpressionHarness.filter(context,
            VarkaHarnessPlans.condition(row.executable(), fixture.columns()), fixture, conf));
      } else {
        var list = VarkaHarnessPlans.projection(row.executable(), fixture.columns());
        for (VarkaExpressionHarness.Consumer consumer : VarkaExpressionHarness.Consumer.values()) {
          outcomes.add(VarkaExpressionHarness.projection(context + ", " + consumer, list,
              fixture, conf, consumer));
        }
      }
      for (VarkaExpressionHarness.Outcome one : outcomes) {
        all.findings.addAll(one.findings);
        all.answered += one.answered;
        all.declined += one.declined;
      }
    }
    return all;
  }

  @Test
  @DisplayName("the coverage table has rows to run")
  void tableHasRows() {
    assertTrue(ROWS.size() >= 57, "only " + ROWS.size() + " rows read from coverage.json");
  }

  @TestFactory
  Stream<DynamicTest> everyRow() {
    return ROWS.stream().map(row -> DynamicTest.dynamicTest(
        row.family() + ": " + row.sql() + " matches the row engine under both consumers", () -> {
          VarkaExpressionHarness.Outcome outcome = check(row);
          assertTrue(outcome.ok(), String.join("\n", outcome.findings));
          assertTrue(outcome.answered > 0, "the kernel answered no batch of " + row.sql()
              + " on any fixture (" + outcome.declined + " declined)");
        }));
  }

  // ---------------------------------------------------------------------------------------------
  // The partition oracle (VARKA-138)
  // ---------------------------------------------------------------------------------------------

  static List<Row> predicates() {
    return ROWS.stream().filter(r -> r.form().equals("predicate")).toList();
  }

  /** {@code conf} with {@code rules} excluded from the optimizer. */
  static SQLConf excluding(SQLConf conf, String rules) {
    SQLConf out = conf.clone();
    out.setConfString(SQLConf.OPTIMIZER_EXCLUDED_RULES().key(), rules);
    return out;
  }

  @TestFactory
  Stream<DynamicTest> partitions() {
    return predicates().stream().map(row -> DynamicTest.dynamicTest(row.family() + ": "
        + row.sql() + " splits every fixture into true, false and unknown rows", () -> {
          SQLConf base = conf(SQLConf.get().ansiEnabled());
          var settings = new LinkedHashMap<String, SQLConf>();
          settings.put("default optimizer", base);
          settings.put("rewrites excluded", excluding(base, VarkaPartitionOracle.REWRITES));
          boolean nullSafe = VarkaPartitionOracle.isNullSafe(row.executable());
          if (nullSafe) {
            settings.put("NullPropagation excluded too",
                excluding(base, VarkaPartitionOracle.NULL_SAFE_REWRITES));
          }
          var findings = new ArrayList<String>();
          for (Map.Entry<String, SQLConf> setting : settings.entrySet()) {
            SQLConf conf = setting.getValue();
            boolean mustFuse = setting.getKey().equals("NullPropagation excluded too");
            for (Map.Entry<String, VarkaHarnessPlans.Fixture> f : FIXTURES.entrySet()) {
              String context = f.getKey() + ", " + setting.getKey();
              // The four filters, planned once for the oracle and the Not check both.
              List<VarkaHarnessPlans.Query> planned =
                  withConf(conf, () -> VarkaPartitionOracle.plan(row.executable(), f.getValue()));
              if (!setting.getKey().equals("default optimizer")) {
                findings.addAll(notAsWritten(context, row.executable(), planned.get(1)));
              }
              findings.addAll(withConf(conf, () -> VarkaPartitionOracle.check(context,
                  row.executable(), f.getValue(), conf, mustFuse, planned)));
            }
          }
          assertTrue(findings.isEmpty(), String.join("\n", findings));
        }));
  }

  /**
   * What is wrong with {@code q}, {@code WHERE NOT (p)} as planned with the partition rewrites
   * excluded: the condition must reach Varka as the {@code Not} it was written as, or the
   * exclusion did not take and the oracle would check the rewrite it means to bypass.
   */
  private static List<String> notAsWritten(String context, String p, VarkaHarnessPlans.Query q) {
    if (q instanceof VarkaHarnessPlans.Query.Selection s
        && s.condition() instanceof org.apache.spark.sql.catalyst.expressions.Not) {
      return List.of();
    }
    return List.of(context + ": WHERE NOT (" + p + ") did not reach Varka as a Not: " + q);
  }

  /** The arm pairs of {@link #nullTestConditions}: two dates, bigints, doubles and floats. */
  private static final String[][] NULL_TEST_ARMS = {{"d", "d2"}, {"l", "l2"}, {"x", "x2"},
      {"f", "f2"}};

  /** A predicate's null tests as CASE conditions, in {@link #nullTestConditions}' order. */
  static List<String> nullTestSqls(String predicate) {
    var sqls = new ArrayList<String>();
    for (String test : List.of("IS NULL", "IS NOT NULL")) {
      for (String[] pair : NULL_TEST_ARMS) {
        sqls.add("CASE WHEN (" + predicate + ") " + test + " THEN " + pair[0] + " ELSE "
            + pair[1] + " END");
      }
    }
    return sqls;
  }

  /**
   * Each predicate row's null tests as the condition of a CASE, with the partition rewrites
   * excluded, since {@code NullDownPropagation} would otherwise turn them into null tests on the
   * columns. The arms are two dates, two bigints, two doubles or two floats: which lane a
   * condition lands on is the compiler's, so where {@code p} can be null one pair of arms must
   * have its kernel serve the whole projection. Every answer is Spark's.
   */
  @TestFactory
  Stream<DynamicTest> nullTestConditions() {
    String[][] arms = NULL_TEST_ARMS;
    return predicates().stream().map(row -> DynamicTest.dynamicTest(row.family() + ": "
        + row.sql() + " under IS NULL and IS NOT NULL fuses as a CASE condition", () -> {
          SQLConf conf = excluding(conf(SQLConf.get().ansiEnabled()),
              VarkaPartitionOracle.REWRITES);
          var findings = new ArrayList<String>();
          withConf(conf, () -> {
            for (Map.Entry<String, VarkaHarnessPlans.Fixture> f : FIXTURES.entrySet()) {
              VarkaHarnessPlans.Fixture fixture = f.getValue();
              boolean canBeNull = VarkaPartitionOracle.nullable(row.executable(), fixture);
              // The eight conditions of a fixture planned in one run of the optimizer, each as
              // it would be alone (VarkaHarnessPlans.projections, VARKA-510).
              List<String> sqls = nullTestSqls(row.executable());
              List<Seq<NamedExpression>> planned =
                  VarkaHarnessPlans.projections(sqls, fixture.columns());
              int k = 0;
              for (String test : List.of("IS NULL", "IS NOT NULL")) {
                boolean served = false;
                for (String[] pair : arms) {
                  String sql = sqls.get(k);
                  VarkaExpressionHarness.Outcome o = VarkaExpressionHarness.projection(
                      f.getKey() + ", " + sql, planned.get(k++), fixture, conf);
                  findings.addAll(o.findings);
                  served |= o.served();
                }
                if (canBeNull && !served) {
                  findings.add(f.getKey() + ": no kernel served all of either arm pair under "
                      + test);
                }
              }
            }
            return null;
          });
          assertTrue(findings.isEmpty(), String.join("\n", findings));
        }));
  }

  @Test
  @DisplayName("a boolean column read at the int and the long lane in one query answers Spark's")
  void booleanAtBothLanes() {
    // The leaf takes the lane of what it meets, so one query can hold it on both: the shapes
    // below decline or fuse, and either way the rows are Spark's. At least one must fuse, or the
    // test would pass with the leaf never reaching a kernel.
    SQLConf conf = conf(SQLConf.get().ansiEnabled());
    VarkaHarnessPlans.Fixture fixture = FIXTURES.get("varka_coverage_mixed");
    List<String> queries = List.of(
        "SELECT rid, IF(b, l, l2) AS x, IF(b, d, d2) AS y FROM t",
        "SELECT rid FROM t WHERE IF(b, l, l2) > 0 AND IF(b, d, d2) > DATE'2020-01-01'",
        "SELECT rid FROM t WHERE b AND l < l2 AND d < d2",
        "SELECT rid, CASE WHEN b THEN l ELSE l2 END AS x, CASE WHEN b THEN d ELSE d2 END AS y "
            + "FROM t");
    var findings = new ArrayList<String>();
    boolean[] served = {false};
    withConf(conf, () -> {
      for (String sql : queries) {
        VarkaHarnessPlans.Query q = VarkaHarnessPlans.query(sql, fixture.columns());
        VarkaExpressionHarness.Outcome o = q instanceof VarkaHarnessPlans.Query.Selection s
            ? VarkaExpressionHarness.filter(sql, s.condition(), fixture, conf)
            : VarkaExpressionHarness.projection(sql,
                ((VarkaHarnessPlans.Query.Projection) q).list(), fixture, conf);
        findings.addAll(o.findings);
        served[0] |= o.served();
      }
      return null;
    });
    assertTrue(findings.isEmpty(), String.join("\n", findings));
    assertTrue(served[0], "no query reached a kernel:\n" + String.join("\n", queries));
  }

  @Test
  @DisplayName("the partition oracle fails a filter that answers unknown where it should answer "
      + "false")
  void partitionOracleFails() {
    // No kernel can be broken this way on purpose, so the fault is planted in the answers: a
    // filter that swapped known-false and unknown would keep Spark's IS NULL rows for NOT (p).
    var t = java.util.Set.of(0, 1);
    var f = java.util.Set.of(2, 3);
    var u = java.util.Set.of(4);
    var all = java.util.Set.of(0, 1, 2, 3, 4);
    assertTrue(VarkaPartitionOracle.partitionFindings(t, f, u, all).isEmpty());
    List<String> findings = VarkaPartitionOracle.partitionFindings(t, u, u, all);
    assertTrue(findings.stream().anyMatch(x -> x.startsWith("rows both false and unknown")),
        String.join("\n", findings));
    assertTrue(findings.stream().anyMatch(x -> x.startsWith("rows neither true, false nor")),
        String.join("\n", findings));
  }

  // ---------------------------------------------------------------------------------------------
  // Every predicate row as a boolean output (VARKA-27)
  // ---------------------------------------------------------------------------------------------

  /**
   * Whether the output {@code c} of {@code list} is a bare column, which a projection forwards
   * rather than fuses: read from the planned list, as {@link #folded} is, so that a row the
   * optimizer reduces to its column counts as one too.
   */
  static boolean bareColumn(Seq<NamedExpression> list) {
    for (NamedExpression e : CollectionConverters.asJava(list)) {
      if (e instanceof Alias a && a.name().equals("c") && a.child() instanceof AttributeReference) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the optimizer folded the output {@code c} of {@code list} to a constant, leaving
   * nothing for Varka to fuse: over a column it knows to be non-nullable, {@code d IS NULL} is
   * false.
   */
  static boolean folded(Seq<NamedExpression> list) {
    for (NamedExpression e : CollectionConverters.asJava(list)) {
      if (e instanceof Alias a && a.name().equals("c") && a.child().foldable()) {
        return true;
      }
    }
    return false;
  }

  /**
   * One predicate row as {@code SELECT rid, (p) AS c} on one fixture, under both consumers: the
   * rows Varka answers must be Spark's, a true, a false and a null each kept apart, and a kernel
   * must have answered and left no output to the row path - but for a bare boolean column, which
   * fuses nothing beside the forwarded {@code rid} and so must leave Spark's projection to run,
   * and for an output Spark folded to a constant.
   */
  private static List<String> outputFindings(Row row, String name,
      VarkaHarnessPlans.Fixture fixture, SQLConf conf) {
    String sql = "SELECT rid, (" + row.executable() + ") AS c FROM t";
    String context = name + ", " + sql;
    VarkaHarnessPlans.Query q = withConf(conf,
        () -> VarkaHarnessPlans.query(sql, fixture.columns()));
    if (!(q instanceof VarkaHarnessPlans.Query.Projection p)) {
      return List.of(context + ": did not stay a projection over the scan: " + q);
    }
    Seq<NamedExpression> list = p.list();
    if (bareColumn(list)) {
      // Under the conf the row runs with, since the emit options are read from it.
      boolean fused = withConf(conf, () -> VarkaExpressionCompiler.compilePartial(list,
          fixture.columns(), VarkaColumnarToRowExec$.MODULE$.currentEmitOptions()).isPresent());
      return fused ? List.of(context + ": a bare column fused, where Spark's projection runs")
          : List.of();
    }
    boolean mustServe = !folded(list);
    var findings = new ArrayList<String>();
    withConf(conf, () -> {
      // The columnar consumer is the batch the Arrow cache stores: the kernel's BitVector.
      for (VarkaExpressionHarness.Consumer consumer : VarkaExpressionHarness.Consumer.values()) {
        VarkaExpressionHarness.Outcome o = VarkaExpressionHarness.projection(
            context + ", " + consumer, list, fixture, conf, consumer);
        findings.addAll(o.findings);
        if (mustServe && (o.answered == 0 || o.residual > 0)) {
          findings.add(context + ", " + consumer + ": no kernel served all of it ("
              + o.answered + " answered, " + o.declined + " declined, " + o.residual
              + " residual)");
        }
      }
      return null;
    });
    return findings;
  }

  @TestFactory
  Stream<DynamicTest> booleanOutputs() {
    return predicates().stream().map(row -> DynamicTest.dynamicTest(row.family() + ": "
        + row.sql() + " as a boolean output matches the row engine", () -> {
          // Under the partition oracle's exclusions, so that the predicate reaches Varka as
          // written: NullDownPropagation would turn (d < d2) IS NULL into null tests on the
          // columns, and NullPropagation fold a null-safe row's null test.
          SQLConf conf = excluding(conf(SQLConf.get().ansiEnabled()),
              VarkaPartitionOracle.isNullSafe(row.executable())
                  ? VarkaPartitionOracle.NULL_SAFE_REWRITES : VarkaPartitionOracle.REWRITES);
          var findings = new ArrayList<String>();
          for (Map.Entry<String, VarkaHarnessPlans.Fixture> f : FIXTURES.entrySet()) {
            findings.addAll(outputFindings(row, f.getKey(), f.getValue(), conf));
          }
          assertTrue(findings.isEmpty(), String.join("\n", findings));
        }));
  }

  @Test
  @DisplayName("the boolean output check fails a null answered as false, and tells a bare "
      + "column and a folded output apart")
  void booleanOutputCheckFails() {
    // No kernel answers a null as false on purpose, so the fault is planted in the answers: a
    // kernel that wrote the validity as the data would give [rid, false] for Spark's
    // [rid, null], which the row comparison must report.
    DataType[] types = {DataTypes.IntegerType, DataTypes.BooleanType};
    InternalRow want = new GenericInternalRow(new Object[] {4, null});
    assertNull(VarkaExpressionHarness.sameRow(want, want, types));
    assertNotNull(VarkaExpressionHarness.sameRow(want,
        new GenericInternalRow(new Object[] {4, false}), types));
    SQLConf conf = conf(SQLConf.get().ansiEnabled());
    VarkaHarnessPlans.Fixture fixture = FIXTURES.get("varka_coverage_mixed");
    withConf(conf, () -> {
      assertTrue(bareColumn(projectionOf("SELECT rid, (b) AS c FROM t", fixture)));
      assertFalse(bareColumn(projectionOf("SELECT rid, (NOT b) AS c FROM t", fixture)));
      assertFalse(bareColumn(projectionOf("SELECT rid, (b <=> b2) AS c FROM t", fixture)));
      assertTrue(folded(projectionOf("SELECT rid, (rid IS NULL) AS c FROM t", fixture)));
      assertFalse(folded(projectionOf("SELECT rid, (d < d2) AS c FROM t", fixture)));
      return null;
    });
  }

  private static Seq<NamedExpression> projectionOf(String sql,
      VarkaHarnessPlans.Fixture fixture) {
    return ((VarkaHarnessPlans.Query.Projection) VarkaHarnessPlans.query(sql, fixture.columns()))
        .list();
  }

  // ---------------------------------------------------------------------------------------------
  // The ANSI form (VARKA-138, VARKA-317)
  // ---------------------------------------------------------------------------------------------

  private static final VarkaHarnessPlans.Fixture EXTREMES = VarkaHarnessPlans.fixture(
      VarkaCoverageDifferentialSuite$.MODULE$.extremes()._2());

  @TestFactory
  Stream<DynamicTest> ansiForm() {
    return ROWS.stream().map(row -> DynamicTest.dynamicTest(row.family() + ": " + row.sql()
        + " raises under ANSI only where the row engine does", () -> {
          SQLConf conf = conf(true);
          List<String> findings = withConf(conf, () -> VarkaAnsiForm.check(row, EXTREMES, conf,
              VarkaMatrixRuns.CONFIG.isEmpty()));
          assertTrue(findings.isEmpty(), String.join("\n\n", findings));
        }));
  }

  @Test
  @DisplayName("the ANSI form's declined list names only positions its reach check reads")
  void ansiDeclinedListIsRead() {
    // An entry for a row the table lacks, for a position the row's form never runs, or for the
    // lone position, which has no reach check, could never go stale.
    SQLConf conf = conf(true);
    var unread = new ArrayList<String>();
    withConf(conf, () -> {
      for (String key : VarkaAnsiForm.DECLINED.keySet()) {
        String[] parts = key.split("\t", 2);
        boolean read = !parts[1].equals(VarkaAnsiForm.ALONE) && ROWS.stream()
            .filter(r -> r.sql().equals(parts[0]))
            .anyMatch(r -> VarkaAnsiForm.queries(r, EXTREMES).getOrDefault(parts[1],
                java.util.Optional.empty()).isPresent());
        if (!read) {
          unread.add(key);
        }
      }
      return null;
    });
    assertTrue(unread.isEmpty(), "declined entries no reach check reads: " + unread);
  }

  @Test
  @DisplayName("the ANSI form's raising rows name each condition a coverage row raises, by its "
      + "first row")
  void raisesListIsCurrent() {
    // The end-to-end suite runs these rows through a session, one an error condition; a
    // condition no entry names would never reach a user's test, and an entry whose row no longer
    // raises it would test nothing.
    SQLConf conf = conf(true);
    var actual = new java.util.TreeMap<String, String>();
    List<Optional<String>> raised = withConf(conf, () -> VarkaAnsiForm.sparkConditions(
        ROWS.stream().map(Row::executable).toList(), EXTREMES));
    for (int i = 0; i < ROWS.size(); i++) {
      String sql = ROWS.get(i).sql();
      raised.get(i).ifPresent(c -> actual.putIfAbsent(c, sql));
    }
    assertTrue(actual.equals(new java.util.TreeMap<>(VarkaAnsiForm.RAISES)),
        "the rows that raise each condition first:\n" + actual.entrySet().stream()
            .map(e -> "Map.entry(\"" + e.getKey() + "\", \"" + e.getValue() + "\")")
            .collect(java.util.stream.Collectors.joining(",\n")));
  }

  @Test
  @DisplayName("a kernel planted to answer an overflowing row fails the harness")
  void plantedKernelFails() {
    // The fault the end-to-end suite's ANSI form plants (VARKA-317): with checkIntOverflow off,
    // a checked int add emits as LEGACY and wraps, so the kernel answers Int.MaxValue + 1 where
    // Spark raises ARITHMETIC_OVERFLOW. Checked first with the add checked, which must pass.
    SQLConf conf = conf(true);
    withConf(conf, () -> {
      plantUnder(conf);
      return null;
    });
  }

  @Test
  @DisplayName("a filter kernel planted to answer an overflowing row fails the harness")
  void plantedFilterFails() {
    // The same plant through the filter path: WHERE i + 1 > 0 keeps the wrapped row's answer.
    SQLConf conf = conf(true);
    withConf(conf, () -> {
      VarkaHarnessPlans.Fixture fixture = VarkaHarnessPlans.fixture(
          "SELECT rid, i FROM VALUES (0, 2147483647), (1, 1) AS v(rid, i)");
      Expression condition = VarkaHarnessPlans.condition("i + 1 > 0", fixture.columns());
      try {
        VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(
            VarkaMatrixRuns.BASE.withCheckIntOverflow(false));
        VarkaExpressionHarness.Outcome planted =
            VarkaExpressionHarness.filter("planted", condition, fixture, conf);
        assertTrue(planted.answered > 0, "the planted filter kernel answered no batch");
        assertTrue(planted.findings.stream().anyMatch(f -> f.contains("Spark raises on")
            && f.contains("ARITHMETIC_OVERFLOW")), "the harness missed the planted filter: "
            + planted.findings);
      } finally {
        VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE);
      }
      return null;
    });
  }

  private static void plantUnder(SQLConf conf) {
    VarkaHarnessPlans.Fixture fixture = VarkaHarnessPlans.fixture(
        "SELECT rid, i FROM VALUES (0, 2147483647), (1, 1) AS v(rid, i)");
    var list = VarkaHarnessPlans.projection("i + 1", fixture.columns());
    // Both halves set the check themselves, so the test means the same under the matrix's
    // checkIntOverflow=false configuration as at the defaults.
    try {
      VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(
          VarkaMatrixRuns.BASE.withCheckIntOverflow(true));
      VarkaExpressionHarness.Outcome checked =
          VarkaExpressionHarness.projection("checked", list, fixture, conf);
      assertTrue(checked.ok(), String.join("\n", checked.findings));
      VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(
          VarkaMatrixRuns.BASE.withCheckIntOverflow(false));
      VarkaExpressionHarness.Outcome planted =
          VarkaExpressionHarness.projection("planted", list, fixture, conf);
      assertTrue(planted.answered > 0, "the planted kernel answered no batch");
      assertTrue(planted.findings.stream().anyMatch(f -> f.contains("Spark raises on")
          && f.contains("ARITHMETIC_OVERFLOW")), "the harness missed the planted answer: "
          + planted.findings);
    } finally {
      VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The domains (VARKA-279)
  // ---------------------------------------------------------------------------------------------

  private static final VarkaHarnessPlans.Fixture EDGES =
      VarkaHarnessPlans.fixture(VarkaDomains.edgesSql());

  private static List<Row> guardedRows() {
    return ROWS.stream().filter(r -> r.domain().isPresent()).toList();
  }

  @TestFactory
  Stream<DynamicTest> domains() {
    return guardedRows().stream().map(row -> DynamicTest.dynamicTest(row.family() + ": "
        + row.sql() + " is answered inside its domain and where it is unknown, and declined "
        + "outside with the verdict's status, in a projection and a filter", () -> {
          // The domain is ANSI's where the modes differ, and a batch's status names exactly the
          // guards that fired only under exact refusal statuses. The cache's batch pruning,
          // which the end-to-end suite turned off, has no counterpart: no batch statistics
          // stand between the harness and the evaluator.
          SQLConf conf = conf(true);
          VarkaDomains.Result result;
          try {
            VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(
                VarkaMatrixRuns.BASE.withExactRefusalStatus(true));
            result = withConf(conf, () -> VarkaDomains.check(row, EDGES, conf));
          } finally {
            VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE);
          }
          assertTrue(result.findings().isEmpty(), result.report());
        }));
  }

  @Test
  @DisplayName("the total domains are the guarded rows the fixture cannot take outside, and no "
      + "other")
  void totalDomainsAreRead() {
    // The list is read only by guarded rows' tests, so an entry for a row the table lacks, or for
    // an unguarded row, could never go stale.
    var unread = VarkaDomains.TOTAL.keySet().stream()
        .filter(sql -> guardedRows().stream().noneMatch(r -> r.sql().equals(sql))).toList();
    assertTrue(unread.isEmpty(), "total domains no guarded row's test reads: " + unread);
  }

  @Test
  @DisplayName("date_add(d, i) carries no guard: at the int extremes its day wraps as the row "
      + "engine's does")
  void dateAddCarriesNoGuard() {
    // The row's note once promised "a per-batch bound on its range" that its kernel does not
    // carry, the open question of VARKA-279's admission check: the compiler records a bound only
    // for the day-interval cast, and Spark's DateAdd is a plain int add. So the row has no domain,
    // and this holds it to that over the edges fixture, i at both int extremes and d of year
    // 9999 among its rows: the kernels serve every batch and the answer - the wrapped day,
    // outside any calendar - is Spark's.
    assertTrue(ROWS.stream().anyMatch(r -> r.sql().equals("date_add(d, i)")
        && r.domain().isEmpty()), "date_add(d, i) is no domainless row of the table");
    SQLConf conf = conf(SQLConf.get().ansiEnabled());
    withConf(conf, () -> {
      VarkaExpressionHarness.Outcome outcome = VarkaExpressionHarness.projection(
          "date_add(d, i)", VarkaHarnessPlans.projection("date_add(d, i)", EDGES.columns()),
          EDGES, conf);
      assertTrue(outcome.ok(), String.join("\n", outcome.findings));
      assertTrue(outcome.served(), outcome.answered + " batches answered, " + outcome.declined
          + " declined, " + outcome.residual + " outputs residual");
      long first = java.time.LocalDate.of(1, 1, 1).toEpochDay();
      long last = java.time.LocalDate.of(9999, 12, 31).toEpochDay();
      assertTrue(VarkaDomains.sparkValues("date_add(d, i)", EDGES).stream()
          .anyMatch(v -> !v.isNullAt(0) && (v.getInt(0) < first || v.getInt(0) > last)),
          "no row wrapped past the calendar");
      return null;
    });
  }
}
