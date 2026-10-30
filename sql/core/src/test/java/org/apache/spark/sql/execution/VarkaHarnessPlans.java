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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.conf.Configuration;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.ExperimentalMethods;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.analysis.FakeV2SessionCatalog$;
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry$;
import org.apache.spark.sql.catalyst.analysis.SimpleAnalyzer$;
import org.apache.spark.sql.catalyst.analysis.TableFunctionRegistry$;
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation;
import org.apache.spark.sql.catalyst.catalog.InMemoryCatalog;
import org.apache.spark.sql.catalyst.catalog.SessionCatalog;
import org.apache.spark.sql.catalyst.expressions.Alias;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser$;
import org.apache.spark.sql.catalyst.plans.logical.Filter;
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.Project;
import org.apache.spark.sql.connector.catalog.DefaultCatalogManager;
import org.apache.spark.sql.internal.SQLConf;

/**
 * The plans a session would build for a Varka differential check, built with no session
 * (VARKA-509, {@code m8/VARKA-509.md} section 2): the SQL parsed, resolved by Spark's analyzer
 * and rewritten by {@code SparkOptimizer}, the optimizer a session runs, over an in-memory
 * catalog with the builtin functions.
 *
 * <p>A query reads {@link VarkaHarnessScan}, an opaque leaf with the fixture's columns, as a
 * session's query reads its cached view's scan: over a {@code LocalRelation} the optimizer would
 * fold the projection or the filter into a relation of results, and nothing would be left for
 * Varka to compile. A fixture, on the other hand, is meant to fold: its {@code VALUES} rows and
 * derived columns become the {@code LocalRelation} whose rows a check runs on.
 */
public final class VarkaHarnessPlans {

  private VarkaHarnessPlans() {}

  private static final SessionCatalog CATALOG = new SessionCatalog(
      new InMemoryCatalog(new SparkConf(), new Configuration()),
      FunctionRegistry$.MODULE$.builtin(), TableFunctionRegistry$.MODULE$.builtin());

  private static final SparkOptimizer OPTIMIZER = new SparkOptimizer(
      new DefaultCatalogManager(FakeV2SessionCatalog$.MODULE$, CATALOG), CATALOG,
      new ExperimentalMethods());

  /** A fixture's columns and rows, from evaluating its SQL. */
  public record Fixture(Seq<Attribute> columns, List<InternalRow> rows) {
    /** The ordinal of the column named {@code name}. */
    public int ordinal(String name) {
      List<Attribute> all = CollectionConverters.asJava(columns);
      for (int i = 0; i < all.size(); i++) {
        if (all.get(i).name().equals(name)) {
          return i;
        }
      }
      throw new IllegalArgumentException("no column " + name + " in " + all);
    }
  }

  /** {@code plan} resolved and optimized as a session's query would be. */
  public static LogicalPlan optimize(LogicalPlan plan) {
    LogicalPlan analyzed = SimpleAnalyzer$.MODULE$.execute(plan);
    SimpleAnalyzer$.MODULE$.checkAnalysis(analyzed);
    return OPTIMIZER.execute(analyzed);
  }

  /**
   * What {@code plan} plans for {@code text} over {@code columns}, through this JVM's
   * {@link VarkaHarnessStore} where it has one: taken from the defaults' run under a
   * configuration, written by the defaults' run.
   */
  private static <T> T stored(String kind, String text, Seq<Attribute> columns,
      java.util.function.Function<Seq<Attribute>, T> plan) {
    VarkaHarnessStore store = VarkaHarnessStore.current();
    return store == null ? plan.apply(columns) : store.plan(kind, text, columns, plan);
  }

  /** {@code sql}'s rows, evaluated by Catalyst to the relation the query folds to. */
  public static Fixture fixture(String sql) {
    LogicalPlan plan = optimize(CatalystSqlParser$.MODULE$.parsePlan(sql));
    if (!(plan instanceof LocalRelation local)) {
      throw new IllegalArgumentException("the fixture did not fold to its rows:\n"
          + plan.treeString());
    }
    var rows = new ArrayList<InternalRow>();
    for (InternalRow row : CollectionConverters.asJava(local.data())) {
      rows.add(row.copy());
    }
    return new Fixture(local.output(), rows);
  }

  /**
   * What a query over a fixture asks of Varka, as the optimizer leaves it: a projection list over
   * the scan, a filter's condition with the columns it keeps, or a constant answer where the
   * optimizer folded the filter away - every row, for a condition it proved true, none for one
   * it proved false.
   */
  public sealed interface Query {
    /**
     * {@code list} over the scan. A list of the scan's own columns only is what a filter the
     * optimizer proved true leaves: {@link #forwardsOnly}.
     */
    record Projection(Seq<NamedExpression> list) implements Query {
      public boolean forwardsOnly() {
        return CollectionConverters.asJava(list).stream().allMatch(e -> e instanceof Attribute);
      }
    }

    /** The rows {@code condition} is true on. */
    record Selection(Expression condition) implements Query {}

    /** Every row, or none, with no expression left to compile. */
    record Constant(boolean all) implements Query {}
  }

  /**
   * {@code sql}, a query over a table named {@code t}, with {@code t} read as a scan of
   * {@code columns}, resolved and optimized: the plan a session builds over its cached view.
   */
  public static Query query(String sql, Seq<Attribute> columns) {
    return stored("query", sql, columns, c -> planQuery(sql, c));
  }

  private static Query planQuery(String sql, Seq<Attribute> columns) {
    LogicalPlan parsed = CatalystSqlParser$.MODULE$.parsePlan(sql);
    LogicalPlan scanned = parsed.transformUp(new scala.runtime.AbstractPartialFunction<>() {
      @Override
      public boolean isDefinedAt(LogicalPlan plan) {
        return plan instanceof UnresolvedRelation r && r.tableName().equals("t");
      }

      @Override
      public LogicalPlan apply(LogicalPlan plan) {
        return isDefinedAt(plan) ? new VarkaHarnessScan(columns) : plan;
      }
    });
    LogicalPlan plan = optimize(scanned);
    // The selected columns over a filter are the scan's own, forwarded: the filter is the work.
    LogicalPlan below = plan instanceof Project p && p.child() instanceof Filter ? p.child() : plan;
    if (below instanceof Filter f && f.child() instanceof VarkaHarnessScan) {
      return new Query.Selection(f.condition());
    }
    if (plan instanceof Project p && p.child() instanceof VarkaHarnessScan) {
      return new Query.Projection(p.projectList());
    }
    LogicalPlan leaf = plan instanceof Project p ? p.child() : plan;
    if (leaf instanceof LocalRelation local && local.data().isEmpty()) {
      return new Query.Constant(false);
    }
    if (leaf instanceof VarkaHarnessScan) {
      return new Query.Constant(true);
    }
    throw new IllegalArgumentException(sql + " is no projection or filter over the scan:\n"
        + plan.treeString());
  }

  /**
   * {@code SELECT <expression> AS v} over a scan of {@code columns}, as the optimizer leaves it:
   * the projection list Varka's projection evaluator compiles.
   */
  public static Seq<NamedExpression> projection(String expression, Seq<Attribute> columns) {
    return stored("projection", expression, columns, c -> planProjection(expression, c));
  }

  private static Seq<NamedExpression> planProjection(String expression, Seq<Attribute> columns) {
    Expression parsed = CatalystSqlParser$.MODULE$.parseExpression(expression);
    NamedExpression alias = VarkaSqlResolve.named(parsed, "v");
    var list = CollectionConverters.asScala(List.of(alias)).toList();
    LogicalPlan plan = optimize(new Project(list, new VarkaHarnessScan(columns)));
    if (plan instanceof Project p && p.child() instanceof VarkaHarnessScan) {
      return p.projectList();
    }
    throw new IllegalArgumentException(expression + " did not stay a projection over the "
        + "scan:\n" + plan.treeString());
  }

  /**
   * {@link #projection} for each of {@code expressions}, planned together as one projection list
   * in one run of the analyzer and the optimizer instead of one run each: the optimizer rewrites
   * each entry of a list on its own, so each comes out as it would alone. A list the optimizer
   * does not leave as one entry an expression over the scan - a {@code BETWEEN}'s operand, say,
   * pulled into a projection of its own below - is halved and each half planned again, down to
   * the expressions that must be planned alone. {@code VarkaHarnessParitySuite} holds the two
   * ways equal.
   */
  public static List<Seq<NamedExpression>> projections(List<String> expressions,
      Seq<Attribute> columns) {
    List<Seq<NamedExpression>> out = together(expressions, columns);
    for (int i = 0; i < out.size(); i++) {
      if (out.get(i) == null) {
        out.set(i, projection(expressions.get(i), columns));
      }
    }
    return out;
  }

  /**
   * What {@link #projections} plans together: each expression's projection list, or null for one
   * that only planning alone gives - one whose plan is no single projection over the scan, or
   * whose planning raises.
   */
  static List<Seq<NamedExpression>> together(List<String> expressions, Seq<Attribute> columns) {
    var text = new StringBuilder();
    for (String e : expressions) {
      text.append(e.length()).append(':').append(e).append(';');
    }
    return stored("together", text.toString(), columns, c -> planTogether(expressions, c));
  }

  private static List<Seq<NamedExpression>> planTogether(List<String> expressions,
      Seq<Attribute> columns) {
    var out = new ArrayList<Seq<NamedExpression>>(
        Collections.nCopies(expressions.size(), null));
    together(expressions, 0, expressions.size(), columns, out);
    return out;
  }

  private static void together(List<String> expressions, int from, int to,
      Seq<Attribute> columns, List<Seq<NamedExpression>> out) {
    if (to - from < 2) {
      return;
    }
    var aliases = new ArrayList<NamedExpression>();
    for (int i = from; i < to; i++) {
      aliases.add(VarkaSqlResolve.named(
          CatalystSqlParser$.MODULE$.parseExpression(expressions.get(i)), "v" + i));
    }
    LogicalPlan plan;
    try {
      plan = optimize(new Project(CollectionConverters.asScala(aliases).toList(),
          new VarkaHarnessScan(columns)));
    } catch (RuntimeException e) {
      plan = null;
    }
    var planned = new ArrayList<Seq<NamedExpression>>();
    if (plan instanceof Project p && p.child() instanceof VarkaHarnessScan
        && p.projectList().size() == to - from) {
      for (NamedExpression e : CollectionConverters.asJava(p.projectList())) {
        if (!(e instanceof Alias a)) {
          break;
        }
        // Named as the expression alone is, so a list reads the same either way.
        NamedExpression v = VarkaSqlResolve.named(a.child(), "v");
        planned.add(CollectionConverters.asScala(List.of(v)).toList());
      }
    }
    if (planned.size() == to - from) {
      for (int i = from; i < to; i++) {
        out.set(i, planned.get(i - from));
      }
      return;
    }
    int mid = (from + to) >>> 1;
    together(expressions, from, mid, columns, out);
    together(expressions, mid, to, columns, out);
  }

  /** What {@link #remembered} keeps a projection under: its text, columns and conf. */
  private record Remembered(String expression, Seq<Attribute> columns,
      scala.collection.immutable.Map<String, String> conf) {}

  /**
   * How many projections {@link #remembered} keeps: a few thousand small trees, the coverage
   * table's rows on each fixture under each optimizer setting the harness runs.
   */
  private static final int REMEMBERED_LIMIT = 8192;

  private static final Map<Remembered, Seq<NamedExpression>> REMEMBERED =
      new ConcurrentHashMap<>();

  /**
   * {@link #projection}, remembered for its text, its columns and every SQL configuration the
   * thread sets, which is all the analyzer and the optimizer read. On a miss every text of
   * {@code among} is planned with it, together ({@link #projections}), and remembered, so a
   * caller that asks for one text of a list after another pays about one run of the optimizer
   * for the list. For a caller that reads only what was planned - a type, a nullability, the
   * columns referenced - and compiles nothing from it: the partition oracle asks a predicate's
   * nullability under each optimizer setting of every test that reads the predicate, and the
   * ANSI form a row's type.
   */
  public static Seq<NamedExpression> remembered(String expression, List<String> among,
      Seq<Attribute> columns) {
    scala.collection.immutable.Map<String, String> conf = SQLConf.get().getAllConfs();
    Seq<NamedExpression> planned = REMEMBERED.get(new Remembered(expression, columns, conf));
    if (planned != null) {
      return planned;
    }
    List<String> texts = among.contains(expression) ? among : List.of(expression);
    List<Seq<NamedExpression>> lists = projections(texts, columns);
    if (REMEMBERED.size() + texts.size() > REMEMBERED_LIMIT) {
      REMEMBERED.clear();
    }
    for (int i = 0; i < texts.size(); i++) {
      REMEMBERED.put(new Remembered(texts.get(i), columns, conf), lists.get(i));
    }
    return lists.get(texts.indexOf(expression));
  }

  /**
   * {@code WHERE <predicate>} over a scan of {@code columns}, as the optimizer leaves it: the
   * condition Varka's filter evaluator compiles, with the conjuncts the optimizer infers beside
   * it.
   */
  public static Expression condition(String predicate, Seq<Attribute> columns) {
    return stored("condition", predicate, columns, c -> planCondition(predicate, c));
  }

  private static Expression planCondition(String predicate, Seq<Attribute> columns) {
    Expression parsed = CatalystSqlParser$.MODULE$.parseExpression(predicate);
    LogicalPlan plan = optimize(new Filter(parsed, new VarkaHarnessScan(columns)));
    if (plan instanceof Filter f && f.child() instanceof VarkaHarnessScan) {
      return f.condition();
    }
    throw new IllegalArgumentException(predicate + " did not stay a filter over the scan:\n"
        + plan.treeString());
  }
}
