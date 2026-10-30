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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import scala.Tuple2;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.catalyst.expressions.Alias;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.catalyst.plans.logical.Filter;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.Project;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.test.TestSparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The harness's expression is the one a session hands Varka (VARKA-509, {@code m8/VARKA-509.md}
 * section 4): for every row of the coverage table on every answer fixture, the projection or
 * condition {@link VarkaHarnessPlans} optimizes with no session equals, bound to the fixture's
 * columns and canonicalized, the one in a session's optimized plan over the cached fixture.
 * The session plans and executes nothing - the fixtures are cached lazily and never read - so
 * this costs the analyzer and the optimizer only, and it fails the day the harness's optimizer
 * and a session's part. The hand-written queries of {@link VarkaHarnessCases} are held to a
 * session's plan the same way, over a view with their fixture's columns.
 */
@VarkaSuite
public final class VarkaHarnessParitySuite {

  private static TestSparkSession spark;

  @BeforeAll
  static void session() {
    spark = new TestSparkSession(new SparkConf(), 1, 1);
    for (Tuple2<String, String> f :
        CollectionConverters.asJava(VarkaCoverageDifferentialSuite$.MODULE$.fixtures())) {
      spark.sql(f._2()).createOrReplaceTempView(f._1());
      spark.catalog().cacheTable(f._1());
    }
  }

  @AfterAll
  static void stop() {
    if (spark != null) {
      spark.stop();
    }
  }

  /** {@code e} bound to {@code columns} by ordinal and canonicalized: ids and names erased. */
  private static Expression canonical(Expression e, Seq<Attribute> columns) {
    return VarkaSqlResolve.bind(e, columns).canonicalized();
  }

  @Test
  @DisplayName("expressions planned together are each the one planned alone")
  void projectionsPlannedTogetherAreEachPlannedAlone() {
    // The null-test conditions plan a fixture's eight conditions in one run of the optimizer
    // (VARKA-510), under the partition rewrites' exclusion they run under.
    SQLConf conf = VarkaCoverageHarnessSuite.excluding(VarkaCoverageHarnessSuite.conf(false),
        VarkaPartitionOracle.REWRITES);
    var differences = new ArrayList<String>();
    int compared = VarkaCoverageHarnessSuite.withConf(conf, () -> {
      int n = 0;
      for (Tuple2<String, String> f :
          CollectionConverters.asJava(VarkaCoverageDifferentialSuite$.MODULE$.fixtures())) {
        Seq<Attribute> columns = VarkaHarnessPlans.fixture(f._2()).columns();
        for (VarkaCoverageHarnessSuite.Row row : VarkaCoverageHarnessSuite.predicates()) {
          List<String> sqls = VarkaCoverageHarnessSuite.nullTestSqls(row.executable());
          List<Seq<NamedExpression>> together = VarkaHarnessPlans.projections(sqls, columns);
          for (int k = 0; k < sqls.size(); k++) {
            Expression a = ((Alias) CollectionConverters.asJava(together.get(k)).get(0)).child();
            Expression b = ((Alias) CollectionConverters.asJava(
                VarkaHarnessPlans.projection(sqls.get(k), columns)).get(0)).child();
            n++;
            Expression ca = canonical(a, columns);
            Expression cb = canonical(b, columns);
            // An InSet holding NaN never equals another; the printed forms are compared too, as
            // harnessExpressionIsTheSessions does.
            if (!ca.equals(cb) && !ca.toString().equals(cb.toString())) {
              differences.add(f._1() + ", " + sqls.get(k) + ": together " + a.sql()
                  + ", alone " + b.sql());
            }
          }
        }
      }
      return n;
    });
    assertTrue(compared > 0, "nothing compared");
    assertTrue(differences.isEmpty(), String.join("\n", differences));
  }

  /** Whether two plans of one query are the same: the same kind, with the same expressions. */
  private static boolean same(VarkaHarnessPlans.Query a, VarkaHarnessPlans.Query b,
      Seq<Attribute> columns) {
    return switch (a) {
      case VarkaHarnessPlans.Query.Selection s -> b instanceof VarkaHarnessPlans.Query.Selection t
          && same(s.condition(), t.condition(), columns);
      case VarkaHarnessPlans.Query.Projection p ->
          b instanceof VarkaHarnessPlans.Query.Projection q && p.list().size() == q.list().size()
              && sameLists(p.list(), q.list(), columns);
      case VarkaHarnessPlans.Query.Constant c -> b.equals(c);
    };
  }

  private static boolean sameLists(Seq<NamedExpression> a, Seq<NamedExpression> b,
      Seq<Attribute> columns) {
    List<NamedExpression> x = CollectionConverters.asJava(a);
    List<NamedExpression> y = CollectionConverters.asJava(b);
    for (int k = 0; k < x.size(); k++) {
      if (!same(unaliased(x.get(k)), unaliased(y.get(k)), columns)) {
        return false;
      }
    }
    return true;
  }

  private static boolean same(Expression a, Expression b, Seq<Attribute> columns) {
    Expression ca = canonical(a, columns);
    Expression cb = canonical(b, columns);
    // An InSet holding NaN never equals another; the printed forms are compared too, as
    // harnessExpressionIsTheSessions does.
    return ca.equals(cb) || ca.toString().equals(cb.toString());
  }

  /**
   * Where {@code texts} planned together ({@link VarkaHarnessPlans#projections}) under
   * {@code conf} differ from each planned alone, one line each; {@code n} counts the compared.
   */
  private static List<String> togetherAgainstAlone(String where, List<String> texts,
      Seq<Attribute> columns, SQLConf conf, int[] n) {
    return VarkaCoverageHarnessSuite.withConf(conf, () -> {
      var differences = new ArrayList<String>();
      List<Seq<NamedExpression>> together = VarkaHarnessPlans.projections(texts, columns);
      for (int k = 0; k < texts.size(); k++) {
        Seq<NamedExpression> alone = VarkaHarnessPlans.projection(texts.get(k), columns);
        n[0]++;
        if (!sameLists(together.get(k), alone, columns)) {
          differences.add(where + ", " + texts.get(k) + ": together " + together.get(k)
              + ", alone " + alone);
        }
      }
      return differences;
    });
  }

  @Test
  @DisplayName("the values the harness remembers, planned together, are each the one planned "
      + "alone")
  void rememberedValuesAreEachPlannedAlone() {
    // The partition oracle's predicate values under each optimizer setting of
    // VarkaCoverageHarnessSuite.partitions and nullTestConditions, at the thread's ANSI, and the
    // ANSI form's row values on the extremes fixture.
    SQLConf base = VarkaCoverageHarnessSuite.conf(SQLConf.get().ansiEnabled());
    var settings = List.of(base,
        VarkaCoverageHarnessSuite.excluding(base, VarkaPartitionOracle.REWRITES),
        VarkaCoverageHarnessSuite.excluding(base, VarkaPartitionOracle.NULL_SAFE_REWRITES));
    var differences = new ArrayList<String>();
    int[] n = {0};
    for (Tuple2<String, String> f :
        CollectionConverters.asJava(VarkaCoverageDifferentialSuite$.MODULE$.fixtures())) {
      Seq<Attribute> columns = VarkaHarnessPlans.fixture(f._2()).columns();
      for (SQLConf conf : settings) {
        differences.addAll(togetherAgainstAlone(f._1() + ", " + conf.getConfString(
            SQLConf.OPTIMIZER_EXCLUDED_RULES().key(), "no rule excluded"),
            VarkaPartitionOracle.values(), columns, conf, n));
      }
    }
    differences.addAll(togetherAgainstAlone("extremes, ANSI", VarkaAnsiForm.values(),
        extremes().columns(), VarkaCoverageHarnessSuite.conf(true), n));
    assertTrue(n[0] > 0, "nothing compared");
    assertTrue(differences.isEmpty(), differences.size() + " of " + n[0] + " differ:\n"
        + String.join("\n", differences));
  }

  /** The extremes fixture the ANSI form runs on. */
  private static VarkaHarnessPlans.Fixture extremes() {
    return VarkaHarnessPlans.fixture(VarkaCoverageDifferentialSuite$.MODULE$.extremes()._2());
  }

  @Test
  @DisplayName("the ANSI form's projections planned together are each the query planned alone")
  void ansiPositionsPlannedTogetherAreEachPlannedAlone() {
    SQLConf conf = VarkaCoverageHarnessSuite.conf(true);
    VarkaHarnessPlans.Fixture fixture = extremes();
    var differences = new ArrayList<String>();
    int compared = VarkaCoverageHarnessSuite.withConf(conf, () -> {
      int n = 0;
      for (VarkaCoverageHarnessSuite.Row row : VarkaCoverageHarnessSuite.rows()) {
        var queries = VarkaAnsiForm.queries(row, fixture);
        var plans = VarkaAnsiForm.plans(queries, fixture);
        for (var q : queries.entrySet()) {
          if (q.getValue().isEmpty()) {
            continue;
          }
          String sql = q.getValue().get().sql();
          VarkaHarnessPlans.Query alone = VarkaHarnessPlans.query(sql, fixture.columns());
          n++;
          if (!same(plans.get(q.getKey()), alone, fixture.columns())) {
            differences.add(row.sql() + ", " + q.getKey() + ", " + sql + ": together "
                + plans.get(q.getKey()) + ", alone " + alone);
          }
        }
      }
      return n;
    });
    assertTrue(compared > 0, "nothing compared");
    assertTrue(differences.isEmpty(), String.join("\n", differences));
  }

  @Test
  @DisplayName("the conditions Spark raises on expressions planned together are each the one "
      + "raised planned alone")
  void sparkConditionsPlannedTogetherAreEachPlannedAlone() {
    SQLConf conf = VarkaCoverageHarnessSuite.conf(true);
    VarkaHarnessPlans.Fixture fixture = extremes();
    List<String> expressions = VarkaCoverageHarnessSuite.rows().stream()
        .map(VarkaCoverageHarnessSuite.Row::executable).toList();
    var differences = new ArrayList<String>();
    VarkaCoverageHarnessSuite.withConf(conf, () -> {
      List<Optional<String>> together = VarkaAnsiForm.sparkConditions(expressions, fixture);
      for (int i = 0; i < expressions.size(); i++) {
        Optional<String> alone = VarkaAnsiForm.sparkCondition(expressions.get(i), fixture);
        if (!together.get(i).equals(alone)) {
          differences.add(expressions.get(i) + ": together " + together.get(i) + ", alone "
              + alone);
        }
      }
      return null;
    });
    assertTrue(differences.isEmpty(), String.join("\n", differences));
  }

  @Test
  @DisplayName("every plan the harness stores comes back from its bytes as the plan planned "
      + "afresh")
  void storedPlansComeBackAsPlanned() {
    // The plans of every coverage row on every fixture the coverage harness reads, each kind of
    // check's, as a writing store (the defaults' run's) hears them. Each is read back from the
    // bytes it was written as over a second making of its fixture, whose columns have other
    // expression ids, as a configuration's JVM reads it, and must print, ids included, as that
    // fixture's plan planned afresh: the same tree, types, literals to the bit and origins.
    var twins = new java.util.IdentityHashMap<Seq<Attribute>, Seq<Attribute>>();
    var differences = new ArrayList<String>();
    int[] n = {0};
    VarkaHarnessStore store = VarkaHarnessStore.writing().watched(
        (what, columns, value, written, plan) -> {
          Seq<Attribute> twin = twins.get(columns);
          if (twin == null) {
            return;
          }
          n[0]++;
          if (written.bytes() == null) {
            differences.add(what + ": not written, " + written.reason());
            return;
          }
          Object back = VarkaHarnessStore.fromOrdinals(
              VarkaHarnessStore.deserialize(written.bytes()), twin);
          Object afresh = VarkaHarnessStore.with(null, () -> plan.apply(twin));
          if (!VarkaHarnessStore.describe(back, true).equals(
              VarkaHarnessStore.describe(afresh, true))) {
            differences.add(what + ": read back " + back + ", planned afresh " + afresh);
          }
        });
    VarkaHarnessStore.with(store, () -> {
      for (Tuple2<String, String> f :
          CollectionConverters.asJava(VarkaCoverageDifferentialSuite$.MODULE$.fixtures())) {
        VarkaHarnessPlans.Fixture fixture = twin(f._2(), twins);
        for (boolean ansi : new boolean[] {false, true}) {
          SQLConf base = VarkaCoverageHarnessSuite.conf(ansi);
          VarkaCoverageHarnessSuite.withConf(base, () -> {
            for (VarkaCoverageHarnessSuite.Row row : VarkaCoverageHarnessSuite.rows()) {
              planAsTheChecksDo(row, fixture);
            }
            return null;
          });
          for (SQLConf conf : List.of(base,
              VarkaCoverageHarnessSuite.excluding(base, VarkaPartitionOracle.REWRITES),
              VarkaCoverageHarnessSuite.excluding(base, VarkaPartitionOracle.NULL_SAFE_REWRITES))) {
            VarkaCoverageHarnessSuite.withConf(conf, () -> {
              for (VarkaCoverageHarnessSuite.Row row : VarkaCoverageHarnessSuite.predicates()) {
                VarkaPartitionOracle.plan(row.executable(), fixture);
                VarkaPartitionOracle.nullable(row.executable(), fixture);
                VarkaHarnessPlans.projections(
                    VarkaCoverageHarnessSuite.nullTestSqls(row.executable()), fixture.columns());
              }
              return null;
            });
          }
        }
      }
      SQLConf ansi = VarkaCoverageHarnessSuite.conf(true);
      VarkaHarnessPlans.Fixture extremes =
          twin(VarkaCoverageDifferentialSuite$.MODULE$.extremes()._2(), twins);
      VarkaHarnessPlans.Fixture edges = twin(VarkaDomains.edgesSql(), twins);
      VarkaCoverageHarnessSuite.withConf(ansi, () -> {
        for (VarkaCoverageHarnessSuite.Row row : VarkaCoverageHarnessSuite.rows()) {
          VarkaAnsiForm.plans(VarkaAnsiForm.queries(row, extremes), extremes);
          if (row.domain().isPresent()) {
            planAsTheChecksDo(row, edges);
          }
        }
        VarkaAnsiForm.sparkConditions(VarkaCoverageHarnessSuite.rows().stream()
            .map(VarkaCoverageHarnessSuite.Row::executable).toList(), extremes);
        return null;
      });
      return null;
    });
    assertTrue(n[0] > 0, "no plan heard");
    assertTrue(differences.isEmpty(), differences.size() + " of " + n[0] + " plans differ:\n"
        + String.join("\n", differences));
  }

  /** {@code sql}'s fixture, made twice: the first returned, the second its twin's columns. */
  private static VarkaHarnessPlans.Fixture twin(String sql,
      java.util.Map<Seq<Attribute>, Seq<Attribute>> twins) {
    VarkaHarnessPlans.Fixture fixture = VarkaHarnessPlans.fixture(sql);
    twins.put(fixture.columns(), VarkaHarnessPlans.fixture(sql).columns());
    return fixture;
  }

  /** What the coverage harness's answer check plans for {@code row}: its condition or list. */
  private static void planAsTheChecksDo(VarkaCoverageHarnessSuite.Row row,
      VarkaHarnessPlans.Fixture fixture) {
    if (row.form().equals("predicate")) {
      VarkaHarnessPlans.condition(row.executable(), fixture.columns());
    } else {
      VarkaHarnessPlans.projection(row.executable(), fixture.columns());
    }
  }

  @Test
  @DisplayName("every coverage row's harness expression is the one a session's optimizer plans")
  void harnessExpressionIsTheSessions() {
    var differences = new ArrayList<String>();
    int compared = 0;
    for (Tuple2<String, String> f :
        CollectionConverters.asJava(VarkaCoverageDifferentialSuite$.MODULE$.fixtures())) {
      VarkaHarnessPlans.Fixture fixture = VarkaHarnessPlans.fixture(f._2());
      Seq<Attribute> columns = fixture.columns();
      for (VarkaCoverageHarnessSuite.Row row : VarkaCoverageHarnessSuite.rows()) {
        boolean predicate = row.form().equals("predicate");
        LogicalPlan plan = spark.sql(predicate
                ? "SELECT d, d2, i FROM " + f._1() + " WHERE " + row.executable()
                : "SELECT " + row.executable() + " AS v FROM " + f._1())
            .queryExecution().optimizedPlan();
        String where = f._1() + ", " + row.sql();
        Expression mine;
        Expression theirs;
        Seq<Attribute> theirColumns;
        if (predicate) {
          LogicalPlan child = plan instanceof Project p ? p.child() : plan;
          if (!(child instanceof Filter filter)) {
            differences.add(where + ": the session's plan has no filter:\n" + plan.treeString());
            continue;
          }
          mine = VarkaHarnessPlans.condition(row.executable(), columns);
          theirs = filter.condition();
          theirColumns = filter.child().output();
        } else {
          if (!(plan instanceof Project p)) {
            differences.add(where + ": the session's plan is no projection:\n"
                + plan.treeString());
            continue;
          }
          List<NamedExpression> list = CollectionConverters.asJava(
              VarkaHarnessPlans.projection(row.executable(), columns));
          // The alias's id is the plan's own, which canonicalization keeps: its child is compared.
          mine = ((Alias) list.get(0)).child();
          theirs = ((Alias) CollectionConverters.asJava(p.projectList()).get(0)).child();
          theirColumns = p.child().output();
        }
        compared++;
        Expression a = canonical(mine, columns);
        Expression b = canonical(theirs, theirColumns);
        // An InSet's literals are a Scala set, whose boxed numbers compare numerically, so a set
        // holding NaN never equals another: the canonical forms are compared as printed too.
        if (!a.equals(b) && !a.toString().equals(b.toString())) {
          differences.add(where + ": the harness has " + mine.sql() + ", the session "
              + theirs.sql());
        }
      }
    }
    assertTrue(compared > 0, "no row compared");
    assertTrue(differences.isEmpty(), differences.size() + " of " + compared
        + " expressions differ:\n" + String.join("\n", differences));
  }

  /** {@code e} itself, or an alias's child: the alias's id is the plan's own. */
  private static Expression unaliased(NamedExpression e) {
    return e instanceof Alias a ? a.child() : (Expression) e;
  }

  @Test
  @DisplayName("every hand-written harness query's expressions are the ones a session's "
      + "optimizer plans")
  void handWrittenQueriesAreTheSessions() {
    var differences = new ArrayList<String>();
    int compared = 0;
    var cases = new ArrayList<VarkaHarnessCases.Case>(VarkaTimeHarnessSuite.all());
    cases.addAll(VarkaDifferentialHarnessSuite.all());
    String ansiKey = SQLConf.ANSI_ENABLED().key();
    String ansiBefore = spark.conf().get(ansiKey);
    for (VarkaHarnessCases.Case c : cases) {
      for (VarkaHarnessCases.Part part : c.parts()) {
        // The cache is lazy and never read: it keeps the optimizer from folding the view's rows.
        if (spark.catalog().tableExists("t")) {
          spark.catalog().uncacheTable("t");
        }
        spark.sql(part.source().schema()).createOrReplaceTempView("t");
        spark.catalog().cacheTable("t");
        spark.conf().set(ansiKey, part.ansi().map(String::valueOf).orElse(ansiBefore));
        Seq<Attribute> columns = part.source().fixture().columns();
        for (VarkaHarnessCases.Shape shape : part.shapes()) {
          String where = c.name() + ", " + shape.sql();
          LogicalPlan plan = spark.sql(shape.sql()).queryExecution().optimizedPlan();
          LogicalPlan below = plan instanceof Project p && p.child() instanceof Filter
              ? p.child() : plan;
          var mine = new ArrayList<Expression>();
          var theirs = new ArrayList<Expression>();
          Seq<Attribute> theirColumns;
          SQLConf conf = VarkaCoverageHarnessSuite.conf(
              part.ansi().orElse(Boolean.parseBoolean(ansiBefore)));
          switch (VarkaCoverageHarnessSuite.withConf(conf,
              () -> VarkaHarnessPlans.query(shape.sql(), columns))) {
            case VarkaHarnessPlans.Query.Selection s when below instanceof Filter f -> {
              mine.add(s.condition());
              theirs.add(f.condition());
              theirColumns = f.child().output();
            }
            case VarkaHarnessPlans.Query.Projection p when plan instanceof Project q
                && !(q.child() instanceof Filter) -> {
              CollectionConverters.asJava(p.list()).forEach(e -> mine.add(unaliased(e)));
              CollectionConverters.asJava(q.projectList()).forEach(e -> theirs.add(unaliased(e)));
              theirColumns = q.child().output();
            }
            default -> {
              differences.add(where + ": the session plans another shape:\n"
                  + plan.treeString());
              continue;
            }
          }
          compared++;
          if (mine.size() != theirs.size()) {
            differences.add(where + ": the harness has " + mine.size() + " outputs, the session "
                + theirs.size());
            continue;
          }
          for (int k = 0; k < mine.size(); k++) {
            Expression a = canonical(mine.get(k), columns);
            Expression b = canonical(theirs.get(k), theirColumns);
            if (!a.equals(b) && !a.toString().equals(b.toString())) {
              differences.add(where + ": the harness has " + mine.get(k).sql() + ", the session "
                  + theirs.get(k).sql());
            }
          }
        }
      }
    }
    spark.conf().set(ansiKey, ansiBefore);
    spark.catalog().uncacheTable("t");
    spark.catalog().dropTempView("t");
    assertTrue(compared > 0, "no query compared");
    assertTrue(differences.isEmpty(), differences.size() + " of " + compared
        + " queries differ:\n" + String.join("\n", differences));
  }
}
