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
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.IsNull;
import org.apache.spark.sql.catalyst.optimizer.BooleanSimplification$;
import org.apache.spark.sql.catalyst.optimizer.InferFiltersFromConstraints$;
import org.apache.spark.sql.catalyst.optimizer.NullDownPropagation$;
import org.apache.spark.sql.catalyst.optimizer.NullPropagation$;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.BooleanType;

/**
 * The partition oracle of the coverage table's predicate rows (VARKA-138), below the session
 * (VARKA-509): a predicate {@code p} splits a fixture into the rows where it is true, false and
 * unknown. Each of {@code WHERE p}, {@code WHERE NOT (p)}, {@code WHERE (p) IS NULL} and
 * {@code WHERE (p) IS NOT NULL} runs through Varka's filter evaluator and must keep Spark's
 * rows; and Spark's own three must share no row and leave none out, with the known rows exactly
 * the true and the false. That last check is the point: Varka and Spark share the optimizer, so
 * a rewrite of {@code NOT (p)} both get wrong agrees with itself in a differential and still
 * breaks the partition.
 */
public final class VarkaPartitionOracle {

  private VarkaPartitionOracle() {}

  /**
   * The optimizer rules that rewrite the partitions before Varka sees them:
   * {@code BooleanSimplification} and {@code NullDownPropagation} turn {@code NOT (d < d2)} and
   * {@code (d < d2) IS NULL} into tests on the columns, and {@code InferFiltersFromConstraints}
   * adds {@code isnotnull} conjuncts beside a predicate that rejects nulls. With them excluded,
   * {@code NOT (p)} reaches Varka as written.
   */
  public static final String REWRITES = String.join(",",
      BooleanSimplification$.MODULE$.ruleName(), NullDownPropagation$.MODULE$.ruleName(),
      InferFiltersFromConstraints$.MODULE$.ruleName());

  /**
   * {@link #REWRITES} and {@code NullPropagation}, for the null-safe rows (VARKA-336): a
   * null-safe equality is never null, so by default {@code NullPropagation} folds
   * {@code (p) IS NULL} to false before any filter holds it.
   */
  public static final String NULL_SAFE_REWRITES =
      REWRITES + "," + NullPropagation$.MODULE$.ruleName();

  /** Whether {@code sql} is a null-safe equality or an {@code IS [NOT] TRUE|FALSE}. */
  public static boolean isNullSafe(String sql) {
    return List.of("<=>", "equal_null", " IS TRUE", " IS FALSE", " IS NOT TRUE", " IS NOT FALSE",
        " IS DISTINCT FROM").stream().anyMatch(sql::contains);
  }

  /**
   * What is wrong with Spark's three partitions of {@code all} - shared rows, rows in none - or
   * nothing. Varka's are held to Spark's batch by batch before this runs.
   */
  public static List<String> partitionFindings(Set<Integer> t, Set<Integer> f, Set<Integer> u,
      Set<Integer> all) {
    var out = new ArrayList<String>();
    shared(t, f, "true and false", out);
    shared(t, u, "true and unknown", out);
    shared(f, u, "false and unknown", out);
    var neither = new TreeSet<>(all);
    neither.removeAll(t);
    neither.removeAll(f);
    neither.removeAll(u);
    if (!neither.isEmpty()) {
      out.add("rows neither true, false nor unknown: rid " + join(neither));
    }
    return out;
  }

  private static void shared(Set<Integer> a, Set<Integer> b, String what, List<String> out) {
    var both = new TreeSet<>(a);
    both.retainAll(b);
    if (!both.isEmpty()) {
      out.add("rows both " + what + ": rid " + join(both));
    }
  }

  private static String join(SortedSet<Integer> ids) {
    return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
  }

  /** Each predicate row of the coverage table as the value {@link #nullable} plans. */
  static List<String> values() {
    return Values.ALL;
  }

  /** Read from the table on first use, so the suite is set up before the oracle reads it. */
  private static final class Values {
    static final List<String> ALL = VarkaCoverageHarnessSuite.predicates().stream()
        .map(r -> "(" + r.executable() + ")").toList();
  }

  /**
   * Whether {@code p} can be null over the fixture's columns: the optimizer's nullability of the
   * value {@code (p)}, which is remembered, every predicate row's planned together on the first
   * ask under a conf ({@link VarkaHarnessPlans#remembered}).
   */
  static boolean nullable(String p, VarkaHarnessPlans.Fixture fixture) {
    var list = CollectionConverters.asJava(
        VarkaHarnessPlans.remembered("(" + p + ")", values(), fixture.columns()));
    return ((Expression) list.get(0)).nullable();
  }

  /**
   * Whether {@code condition} holds a null test over a computed value, neither a column nor a
   * predicate: {@code NullDownPropagation} turns {@code (month(d) > i) IS NULL} into
   * {@code isnull(month(d)) OR isnull(i)}, which Varka declines by design, since a computed
   * value's validity word exists only during its value walk.
   */
  static boolean nullTestOverValue(Expression condition) {
    return condition.exists(e -> {
      Expression child = e instanceof IsNull n ? n.child()
          : e instanceof IsNotNull n ? n.child() : null;
      return child != null && !(child instanceof Attribute)
          && !(child.dataType() instanceof BooleanType);
    });
  }

  /**
   * One filter's outcome: Spark's true rows, what the harness found about Varka's (null where
   * the optimizer left no filter), and whether its condition is a null test over a computed
   * value, which no kernel serves.
   */
  private record Filtered(SortedSet<Integer> spark, VarkaExpressionHarness.Outcome outcome,
      boolean overValue) {}

  private static Filtered filter(String context, String where, VarkaHarnessPlans.Query q,
      VarkaHarnessPlans.Fixture fixture, SQLConf conf) {
    var all = new TreeSet<Integer>();
    int rid = fixture.ordinal("rid");
    for (InternalRow row : fixture.rows()) {
      all.add(row.getInt(rid));
    }
    if (q instanceof VarkaHarnessPlans.Query.Selection s) {
      var outcome = VarkaExpressionHarness.filter(context + ", WHERE " + where, s.condition(),
          fixture, conf);
      return new Filtered(outcome.sparkTrue, outcome, nullTestOverValue(s.condition()));
    }
    boolean every = q instanceof VarkaHarnessPlans.Query.Constant c ? c.all()
        : ((VarkaHarnessPlans.Query.Projection) q).forwardsOnly();
    return new Filtered(every ? all : new TreeSet<>(), null, false);
  }

  /** The oracle's four filters of {@code p}, in the order {@link #check} reads them. */
  static List<String> wheres(String p) {
    return List.of(p, "NOT (" + p + ")", "(" + p + ") IS NULL", "(" + p + ") IS NOT NULL");
  }

  /**
   * {@code SELECT rid FROM t WHERE <where>} for each of {@link #wheres}, under the thread's conf,
   * each planned alone. They are not planned together: a {@code Filter} holding them all would
   * have the optimizer merge and reorder their conjuncts, and a {@code UNION ALL} of the four
   * filters, which keeps each condition as it is alone, costs the optimizer as much as the four
   * runs it replaces, since its cost is in the plan's nodes, not in the run
   * ({@code m8/results/VARKA-510-rest-local.txt}).
   */
  static List<VarkaHarnessPlans.Query> plan(String p, VarkaHarnessPlans.Fixture fixture) {
    var out = new ArrayList<VarkaHarnessPlans.Query>();
    for (String where : wheres(p)) {
      out.add(VarkaHarnessPlans.query("SELECT rid FROM t WHERE " + where, fixture.columns()));
    }
    return out;
  }

  /**
   * The partition oracle for predicate {@code p} on {@code fixture} under the thread's conf -
   * its optimizer rules included - over {@code planned}, {@link #plan}'s queries under that
   * conf. {@code WHERE p} and {@code WHERE NOT (p)} must each be a filter a kernel serves whole;
   * a null test may be folded away, or turned into a test over a computed value, where {@code p}
   * cannot be null, unless {@code mustFuse}, a null-safe row with {@code NullPropagation}
   * excluded, whose null tests the kernel must answer.
   */
  public static List<String> check(String context, String p, VarkaHarnessPlans.Fixture fixture,
      SQLConf conf, boolean mustFuse, List<VarkaHarnessPlans.Query> planned) {
    var out = new ArrayList<String>();
    boolean canBeNull = mustFuse || nullable(p, fixture);
    List<String> wheres = wheres(p);
    var filtered = new ArrayList<Filtered>();
    for (int i = 0; i < wheres.size(); i++) {
      filtered.add(filter(context, wheres.get(i), planned.get(i), fixture, conf));
    }
    Filtered t = filtered.get(0);
    Filtered f = filtered.get(1);
    Filtered u = filtered.get(2);
    Filtered k = filtered.get(3);
    String[] names = {"WHERE p", "WHERE NOT (p)", "WHERE (p) IS NULL", "WHERE (p) IS NOT NULL"};
    Filtered[] all = {t, f, u, k};
    for (int i = 0; i < all.length; i++) {
      Filtered x = all[i];
      if (x.outcome() != null) {
        out.addAll(x.outcome().findings);
      }
      boolean mustServe = i < 2 || canBeNull && !x.overValue();
      if (mustServe && (x.outcome() == null || !x.outcome().served())) {
        out.add(context + ": no kernel served all of " + names[i] + (x.outcome() == null
            ? " (the optimizer left no filter)"
            : " (" + x.outcome().answered + " answered, " + x.outcome().declined + " declined)"));
      }
    }
    var rows = new TreeSet<Integer>();
    int rid = fixture.ordinal("rid");
    for (InternalRow row : fixture.rows()) {
      rows.add(row.getInt(rid));
    }
    for (String finding : partitionFindings(t.spark(), f.spark(), u.spark(), rows)) {
      out.add(context + ": " + finding);
    }
    var trueOrFalse = new TreeSet<>(t.spark());
    trueOrFalse.addAll(f.spark());
    if (!k.spark().equals(trueOrFalse)) {
      out.add(context + ": WHERE (p) IS NOT NULL is not the true and the false rows");
    }
    return out;
  }
}
