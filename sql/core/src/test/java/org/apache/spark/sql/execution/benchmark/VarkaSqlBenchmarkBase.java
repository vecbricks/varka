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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import scala.Option;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;
import scala.runtime.BoxedUnit;

import org.apache.spark.SparkContext;
import org.apache.spark.SparkContext$;
import org.apache.spark.internal.config.UI;
import org.apache.spark.internal.config.package$;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RuntimeConfig;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmarkBase;
import org.apache.spark.sql.catalyst.expressions.Alias;
import org.apache.spark.sql.catalyst.expressions.And;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.IsNull;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.execution.BaseSubqueryExec;
import org.apache.spark.sql.execution.CoalesceExec;
import org.apache.spark.sql.execution.ColumnarToRowTransition;
import org.apache.spark.sql.execution.FilterExec;
import org.apache.spark.sql.execution.InputAdapter;
import org.apache.spark.sql.execution.LeafExecNode;
import org.apache.spark.sql.execution.LimitExec;
import org.apache.spark.sql.execution.ProjectExec;
import org.apache.spark.sql.execution.RowToColumnarTransition;
import org.apache.spark.sql.execution.ReusedSubqueryExec;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.UnionExec;
import org.apache.spark.sql.execution.VarkaColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFilterColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFilterExec;
import org.apache.spark.sql.execution.VarkaProjectExec;
import org.apache.spark.sql.execution.WholeStageCodegenExec;
import org.apache.spark.sql.execution.adaptive.AQEShuffleReadExec;
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec;
import org.apache.spark.sql.execution.adaptive.QueryStageExec;
import org.apache.spark.sql.execution.exchange.Exchange;
import org.apache.spark.sql.execution.exchange.ReusedExchangeExec;
import org.apache.spark.sql.internal.SQLConf;

/**
 * The base of a Varka benchmark over SQL sessions, written in Java: what Spark's Scala trait
 * {@code SqlBasedBenchmark} gives a Scala benchmark, which a Java class cannot mix in, on top of
 * {@link VarkaBenchmarkBase}'s sections, benchmarks, dry run and case filter.
 *
 * <ul>
 *   <li>{@link #spark()}, the default session with the trait's settings, made on first use rather
 *       than when the benchmark is: most Varka benchmarks build their own sessions, and a default
 *       session started first would fix the SparkContext they join (its master, its cache
 *       serializer) before they could set it. {@link #afterAll} stops it if it was made.</li>
 *   <li>{@link #withConf}, the trait's {@code withSQLConf} on a session named by the caller, since
 *       a Varka benchmark holds two: it restores every configuration it set, or unsets one that was
 *       not set before, so no case leaks a setting into the next.</li>
 *   <li>{@link #noop}, the trait's {@code noop()}: a query run to the end, its rows dropped.</li>
 *   <li>{@link #note}, a line of the results file outside a table, such as a rung's facts.</li>
 *   <li>{@link #fusedPlan}, a query's plan when it holds a Varka node, {@link #fusion}, how
 *       much of a plan Varka fuses, {@link #requireBaseline}, a baseline benchmark's check
 *       that a section fuses only as its class lists it and that a {@code WHOLE} one runs as a
 *       kernel, {@link #requireKernel}, the second half on its own, and {@link #requireSection},
 *       both made by a baseline's queries suite.</li>
 *   <li>{@link #stopLeftoverSessions} and {@link #clearSessions}, for a benchmark that makes its
 *       own sessions, and {@link #withEmitOptions} for one that compares emitter designs.</li>
 * </ul>
 *
 * <p>A subclass is run as {@link VarkaBenchmarkBase} says, with {@code sql} for the module: {@code
 * dev/varka_bench_regen.sh sql <class>}.
 */
public abstract class VarkaSqlBenchmarkBase extends VarkaBenchmarkBase {

  // ---------------------------------------------------------------------------------------
  // The default session
  // ---------------------------------------------------------------------------------------

  private SparkSession spark;

  /**
   * The default session, made by {@link #getSparkSession} on the first call. That call must come
   * before the benchmark makes a session of its own: {@code getOrCreate} would return that session
   * instead and write the defaults into a measured arm's configuration, so it is refused.
   */
  protected final SparkSession spark() {
    if (spark == null) {
      require(SparkSession.getActiveSession().isEmpty()
          && SparkSession.getDefaultSession().isEmpty(),
          "spark() is called after another session was made, and would return that one");
      spark = getSparkSession();
    }
    return spark;
  }

  /** The default session's settings, {@code SqlBasedBenchmark}'s; a subclass may override. */
  protected SparkSession getSparkSession() {
    return SparkSession.builder()
        .master("local[1]")
        .appName(getClass().getName())
        .config(SQLConf.SHUFFLE_PARTITIONS().key(), 1L)
        .config(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD().key(), 1L)
        .config(UI.UI_ENABLED().key(), false)
        .config(package$.MODULE$.MAX_RESULT_SIZE().key(), "3g")
        .getOrCreate();
  }

  /** Stops the default session if {@link #spark()} made one; Spark's {@code main} calls this. */
  @Override
  public void afterAll() {
    if (spark != null) {
      spark.stop();
    }
  }

  /**
   * Stops a session left active or default in this JVM, and with it its SparkContext, then any
   * SparkContext still running, and clears both sessions, for a benchmark about to make sessions
   * of its own. A Scala benchmark did this by stopping
   * the trait's default session, which had joined any context left running: under Spark's {@code
   * Benchmarks} runner a class that failed leaves its session running, and a session made after it
   * would join that context and keep its master, silently.
   */
  protected static void stopLeftoverSessions() {
    Option<SparkSession> active = SparkSession.getActiveSession();
    if (active.isDefined()) {
      active.get().stop();
    }
    Option<SparkSession> fallback = SparkSession.getDefaultSession();
    if (fallback.isDefined()) {
      fallback.get().stop();
    }
    // A benchmark clears its sessions as it makes them, so one that failed may leave its context
    // running with neither registered. `getActive` is private[spark], with no static forwarder.
    Option<SparkContext> context = SparkContext$.MODULE$.getActive();
    if (context.isDefined()) {
      context.get().stop();
    }
    clearSessions();
  }

  /**
   * Clears the active and the default session without stopping either, after a benchmark makes
   * one of its own: {@code getOrCreate} returns the active or default session when there is one,
   * so without this the next session the benchmark makes would be this one with the next one's
   * settings written into it - two names for one session, and a baseline that is not one.
   */
  protected static void clearSessions() {
    SparkSession.clearActiveSession();
    SparkSession.clearDefaultSession();
  }

  // ---------------------------------------------------------------------------------------
  // Queries and settings
  // ---------------------------------------------------------------------------------------

  /** Runs {@code ds} to the end and drops its rows, through the {@code noop} data source. */
  protected static void noop(Dataset<?> ds) {
    ds.write().format("noop").mode(SaveMode.Overwrite).save();
  }

  /**
   * The executed plan of {@code query} on {@code session} when it holds a Varka node - a fused
   * projection or filter - and empty when it does not. The plan comes back so that a caller
   * refusing a section that fuses can show the tree without planning the query a second time.
   * Under adaptive execution the plan is searched as {@link #operators} reads it, which runs its
   * stages once; the benchmarks' sessions ({@code VarkaArrowSessions}) run with it off.
   */
  public static Optional<SparkPlan> fusedPlan(SparkSession session, String query) {
    SparkPlan plan = session.sql(query).queryExecution().executedPlan();
    return holdsVarkaNode(plan) ? Optional.of(plan) : Optional.empty();
  }

  /**
   * Whether {@code plan} holds a Varka node, a fused projection or filter, the plan adaptive
   * execution runs included.
   */
  public static boolean holdsVarkaNode(SparkPlan plan) {
    return operators(plan).stream().anyMatch(VarkaSqlBenchmarkBase::isVarkaNode);
  }

  /**
   * Runs {@code body} with {@code settings} set on {@code session}'s configuration, then puts
   * back each one it set: the earlier value where there was one, unset where there was none. A
   * key's earlier state is read with {@code contains}, not {@code get}, since {@code get} answers
   * a registered key's default when nothing set it, and restoring that default would leave the
   * key set. The keys are SQL configurations: {@code set} refuses a static key, and a Spark core
   * key unless {@code spark.sql.legacy.setCommandRejectsSparkCoreConfs} is off, before {@code body}
   * runs, where Scala's {@code withSQLConf}, writing to {@code SQLConf} directly, takes the latter.
   */
  protected static void withConf(
      SparkSession session, Map<String, String> settings, Runnable body) {
    RuntimeConfig conf = session.conf();
    // Only the keys this call has set, so that a set refused part-way through still restores
    // the keys set ahead of it and touches none after it.
    var applied = new ArrayList<Previous>(settings.size());
    try {
      for (Map.Entry<String, String> setting : settings.entrySet()) {
        String key = setting.getKey();
        // `contains` is private[sql] in Scala, which this package is inside; javac sees it public.
        var previous = new Previous(key, conf.contains(key) ? conf.get(key) : null);
        conf.set(key, setting.getValue());
        applied.add(previous);
      }
      body.run();
    } finally {
      for (Previous previous : applied.reversed()) {
        if (previous.value() == null) {
          conf.unset(previous.key());
        } else {
          conf.set(previous.key(), previous.value());
        }
      }
    }
  }

  /** A key {@link #withConf} set, and the value it had before: null where it had none. */
  private record Previous(String key, String value) {}

  /**
   * Runs {@code body} with the Varka sessions planning and emitting under {@code options}, then
   * back under the defaults: {@code VarkaArrowSessions.withEmitOptions}, whose body is a Scala
   * function with a result, for a body that has none.
   */
  protected static void withEmitOptions(VarkaEmitOptions options, Runnable body) {
    VarkaArrowSessions.withEmitOptions(options, () -> {
      body.run();
      return BoxedUnit.UNIT;
    });
  }

  // ---------------------------------------------------------------------------------------
  // Baselines
  // ---------------------------------------------------------------------------------------

  /**
   * The system property that lifts the fuse check of {@link #requireBaseline}: {@code
   * -Dvarka.baseline.fuseCheck=false} is the run that measures a row against a baseline's
   * committed results, after the row has started to fuse the baseline's sections. It takes
   * {@code true}, the default, or {@code false}, in any case, and refuses anything else, so that
   * a misspelt value cannot lift the check unnoticed.
   */
  public static final String FUSE_CHECK_PROPERTY = "varka.baseline.fuseCheck";

  /** Whether {@link #FUSE_CHECK_PROPERTY} leaves the fuse check on. */
  static boolean fuseCheck() {
    String value = System.getProperty(FUSE_CHECK_PROPERTY);
    if (value == null) {
      return true;
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException(
          "-D" + FUSE_CHECK_PROPERTY + " must be true or false, not \"" + value + "\"");
    };
  }

  /**
   * How much of a baseline section the Varka session fuses: the expectation a section states and
   * {@link #requireBaseline(SparkSession, String, String, Fusion)} checks against its plan.
   *
   * <ul>
   *   <li>{@code NONE}: the plan holds no Varka node, so both arms run the row path.</li>
   *   <li>{@code NULL_TESTS}: every Varka node is a filter whose conjuncts are only {@code IS NOT
   *       NULL} and {@code IS NULL} tests, and a row operator ({@link #computesOnRows}) still
   *       evaluates the rest of the section. The {@code IS NOT NULL} tests the optimizer infers
   *       from a null-intolerant condition do not give this class alone: where nothing else
   *       of the filter fuses, it stays whole on the row path (VARKA-598), and the section is
   *       {@code NONE}. The varka on arm then times a null-test kernel under the row
   *       path's expression, and the spark row path arm is the baseline.</li>
   *   <li>{@code SPLIT}: a filter the rule split (VARKA-601). The plan's one Varka node is a
   *       to-row filter ({@code VarkaFilterColumnarToRowExec}) holding conjuncts that read a
   *       value, beside the whole condition it was split from, and the plan's one row operator
   *       is the {@code FilterExec} directly above it holding the residual conjuncts. The varka
   *       on arm then times the kernel, the copy of each row it keeps into an {@code UnsafeRow},
   *       and the row path's residual over those rows.</li>
   *   <li>{@code PARTIAL}: no row operator evaluates any of the section, but a Varka projection
   *       leaves an entry to its own per-row fallback ({@link #residue}): the entry
   *       its kernel declines is evaluated row by row inside the Varka node, so the varka on arm
   *       times a kernel and the row path's expression together.</li>
   *   <li>{@code WHOLE}: the plan holds a Varka node, no row operator, and no Varka projection
   *       with an entry left to rows, so the varka on arm times a kernel and not the row
   *       path.</li>
   * </ul>
   *
   * <p>A plan that is none of these has no class: a Varka node fusing more than null tests while a
   * row operator other than a split's residual filter still computes part of the section, or a
   * Varka projection that computes but whose classification is empty, which the rule never plans,
   * so the check cannot say what it fuses.
   * {@link #fusion} answers empty for it, and the check refuses it whatever the section expects.
   *
   * <p>A row operator decides first. Without one, a plan is {@code PARTIAL} or {@code WHOLE} by
   * its Varka projections, whatever its Varka filters fuse, null tests only included. With one, it
   * is {@code NULL_TESTS} when every Varka node is a null-test filter, and {@code SPLIT} only in
   * the one shape that class names, so a Varka projection beside a row operator, one that leaves
   * entries to rows or one that does not, leaves the plan with no class rather than
   * {@code PARTIAL}, and so does a split filter under a sort or an aggregate.
   *
   * <p>One limit: a Varka node also serves a batch the kernel cannot, a non-Arrow or an empty one
   * or a kernel that fails, by the row path. That is decided per batch when the plan runs, so no
   * plan shows it; {@link #requireKernel} runs a {@code WHOLE} section once and reads its batches
   * from the nodes' metrics.
   */
  public enum Fusion { NONE, NULL_TESTS, SPLIT, PARTIAL, WHOLE }

  /**
   * What the kernel check of a section that plans {@code WHOLE} expects of its batches:
   * {@code KERNEL}, that its kernel computes every one ({@link #requireKernel});
   * {@code MAY_FALL_BACK}, that it times a fallback on purpose where the arm the host or an
   * option picks declines its data, which no check reads, since another arm computes the same
   * batches; and {@code ALL_FALL_BACK}, that a guard of its kernel declines every batch of its
   * data on any host, so that a kernel batch means the section no longer times the fallback
   * ({@link #requireFallback}).
   */
  public enum BatchCheck { KERNEL, MAY_FALL_BACK, ALL_FALL_BACK }

  /**
   * The {@link Fusion} class of {@code plan}, or empty when it fits none, a row operator deciding
   * first as {@link Fusion} says. A row operator counts wherever it is in the plan, which for a
   * baseline's single filter or projection over a cached scan is above the Varka node. The plan
   * of a cached table, under the scan, is not searched: it runs when the cache is built, not when
   * the section is timed. The plan's operators are read as {@link #operators} reads them, through
   * adaptive execution's wrappers.
   */
  public static Optional<Fusion> fusion(SparkPlan plan) {
    List<SparkPlan> operators = operators(plan);
    if (operators.stream().noneMatch(VarkaSqlBenchmarkBase::isVarkaNode)) {
      return Optional.of(Fusion.NONE);
    }
    var residues = operators.stream().map(VarkaSqlBenchmarkBase::residue).toList();
    if (residues.contains(Residue.UNCLASSIFIED)) {
      return Optional.empty();
    }
    if (operators.stream().noneMatch(VarkaSqlBenchmarkBase::computesOnRows)) {
      return residues.contains(Residue.ENTRIES_LEFT_TO_ROWS)
          ? Optional.of(Fusion.PARTIAL) : Optional.of(Fusion.WHOLE);
    }
    boolean beyondNullTests =
        operators.stream().anyMatch(p -> isVarkaNode(p) && !fusesOnlyNullTests(p));
    if (!beyondNullTests) {
      return Optional.of(Fusion.NULL_TESTS);
    }
    return isSplit(operators) ? Optional.of(Fusion.SPLIT) : Optional.empty();
  }

  /**
   * Whether {@code operators} are a split filter's, as {@link Fusion#SPLIT} names it: one row
   * operator, a {@code FilterExec}, whose child is the one Varka node, read through the input
   * adapter whole-stage codegen puts between them, and that node a to-row filter split off a whole
   * condition whose own conjuncts are not only null tests. The split is read from the conjuncts:
   * the node's whole condition is exactly its own conjuncts and the {@code FilterExec}'s, with at
   * least one of the latter. A whole condition alone does not say so, since the rule passes one
   * too where nothing is residual but the condition is not its conjuncts' left fold (VARKA-608),
   * and that node may sit under some other row filter.
   */
  private static boolean isSplit(List<SparkPlan> operators) {
    List<SparkPlan> rowWork =
        operators.stream().filter(VarkaSqlBenchmarkBase::computesOnRows).toList();
    List<SparkPlan> varkaNodes =
        operators.stream().filter(VarkaSqlBenchmarkBase::isVarkaNode).toList();
    if (rowWork.size() != 1 || varkaNodes.size() != 1
        || !(rowWork.getFirst() instanceof FilterExec residual)
        || !(varkaNodes.getFirst() instanceof VarkaFilterColumnarToRowExec fused)) {
      return false;
    }
    SparkPlan below = residual.child() instanceof InputAdapter adapter
        ? adapter.child() : residual.child();
    return below == fused && fused.wholeCondition().isDefined()
        && splitInto(fused.wholeCondition().get(), fused.condition(), residual.condition())
        && !onlyNullTests(fused.condition());
  }

  /**
   * Whether the conjuncts of {@code whole} are those of {@code fused} and {@code residual}
   * together, each used once, so that {@code residual}, never empty, holds conjuncts beyond
   * {@code fused}'s.
   */
  private static boolean splitInto(Expression whole, Expression fused, Expression residual) {
    List<Expression> left = conjuncts(whole);
    List<Expression> parts = conjuncts(fused);
    parts.addAll(conjuncts(residual));
    for (Expression part : parts) {
      if (!left.remove(part)) {
        return false;
      }
    }
    return left.isEmpty();
  }

  /** The conjuncts of {@code condition}'s {@code AND} tree, left to right. */
  private static List<Expression> conjuncts(Expression condition) {
    var out = new ArrayList<Expression>();
    addConjuncts(condition, out);
    return out;
  }

  private static void addConjuncts(Expression condition, List<Expression> out) {
    if (condition instanceof And and) {
      addConjuncts(and.left(), out);
      addConjuncts(and.right(), out);
    } else {
      out.add(condition);
    }
  }

  /**
   * Every operator of {@code plan}, the plan adaptive execution runs included. Its wrappers are
   * leaves, so a plain search stops at them: an {@code AdaptiveSparkPlanExec} is read through its
   * final plan, and a query stage through the plan it runs. The final plan is the one with the
   * Varka nodes, since adaptive execution applies the columnar rules as it creates each stage
   * rather than to its initial plan; reading it materializes the stages under the final one, as
   * running the query would. A stage adaptive execution finds empty is replaced by an empty
   * relation, which holds no Varka node, since none runs.
   *
   * <p>The plans of an operator's subqueries are read too, a scalar subquery's or a pruning
   * filter's, since they run with the section; a reused subquery is read through the subquery
   * it reuses, and a reused exchange through the exchange it reuses, both leaves otherwise.
   */
  static List<SparkPlan> operators(SparkPlan plan) {
    var out = new ArrayList<SparkPlan>();
    addOperators(plan, out);
    return out;
  }

  private static void addOperators(SparkPlan p, List<SparkPlan> out) {
    switch (p) {
      case AdaptiveSparkPlanExec adaptive -> addOperators(adaptive.finalPhysicalPlan(), out);
      case QueryStageExec stage -> addOperators(stage.plan(), out);
      case ReusedExchangeExec reused -> {
        out.add(reused);
        addOperators(reused.child(), out);
      }
      case ReusedSubqueryExec reused -> {
        out.add(reused);
        addOperators(reused.child(), out);
      }
      default -> {
        out.add(p);
        for (SparkPlan subquery : CollectionConverters.asJava(p.subqueries())) {
          addOperators(subquery, out);
        }
        for (SparkPlan child : CollectionConverters.asJava(p.children())) {
          addOperators(child, out);
        }
      }
    }
  }

  /** Whether {@code p} is one of the nodes the Varka rule plans, which {@link #fusedPlan} finds. */
  private static boolean isVarkaNode(SparkPlan p) {
    return p instanceof VarkaColumnarToRowExec || p instanceof VarkaProjectExec
        || p instanceof VarkaFilterExec || p instanceof VarkaFilterColumnarToRowExec;
  }

  /**
   * Whether {@code p} is a row operator that evaluates part of a query. Every operator outside
   * Varka is one, a filter, an aggregate, a sort, a window, an expand, a generate or a join
   * alike, except those that only move rows or batches without evaluating an expression over
   * them: a leaf, the source both arms read alike, which for a baseline is the scan of its cached
   * table; an exchange, and a reused one; a transition between columns and rows; adaptive
   * execution's read of a shuffle's partitions; a limit, a union and a coalesce of partitions; a
   * subquery's node, which runs its plan and holds the result; the whole-stage codegen node and
   * its input adapter, which wrap the operators they compile, each found on its own; and a
   * projection whose every entry is a column passed through or renamed. So an operator this list
   * does not name counts as row work, and a plan that gains one fails the check rather than
   * reading as fused. Adaptive execution's own wrappers never reach here: {@link #operators}
   * reads through them.
   *
   * <p>Two choices made deliberately. A range is a leaf, exempt like a scan though it generates
   * its values: it is a source that no Varka node can read directly, since it is not Arrow, and a
   * baseline's lies under its cached table, which is not searched. A limit that sorts first,
   * {@code TakeOrderedAndProjectExec}, is not a {@code LimitExec} and counts as row work.
   */
  private static boolean computesOnRows(SparkPlan p) {
    if (isVarkaNode(p)) {
      return false;
    }
    return switch (p) {
      case LeafExecNode leaf -> false;
      case Exchange exchange -> false;
      case ColumnarToRowTransition transition -> false;
      case RowToColumnarTransition transition -> false;
      case WholeStageCodegenExec codegen -> false;
      case InputAdapter adapter -> false;
      case AQEShuffleReadExec read -> false;
      case LimitExec limit -> false;
      case UnionExec union -> false;
      case CoalesceExec coalesce -> false;
      case BaseSubqueryExec subquery -> false;
      case ProjectExec project -> project.projectList().exists(e -> !passesThrough(e));
      default -> true;
    };
  }

  /** Whether a projection entry is a column passed through, or renamed by an alias. */
  private static boolean passesThrough(NamedExpression entry) {
    return entry instanceof Attribute
        || entry instanceof Alias alias && alias.child() instanceof Attribute;
  }

  /** What a Varka projection leaves to its per-row fallback, read by {@link #residue}. */
  private enum Residue { NONE_LEFT, ENTRIES_LEFT_TO_ROWS, UNCLASSIFIED }

  /**
   * What {@code p} leaves to its per-row fallback when it is a Varka projection: an entry its
   * kernel declines ({@code ResidualOutput}), which the node evaluates row by row beside the
   * kernel's. It reads the node's own classification, the compile that also serves its EXPLAIN
   * and its residual-entry metric, so the check compiles nothing of its own. Any other operator
   * leaves nothing.
   */
  private static Residue residue(SparkPlan p) {
    return switch (p) {
      case VarkaProjectExec project -> residue(project.projectList(), project.classification());
      case VarkaColumnarToRowExec project ->
          residue(project.projectList(), project.classification());
      default -> Residue.NONE_LEFT;
    };
  }

  private static Residue residue(
      Seq<NamedExpression> projectList, Optional<PartialVarkaProjection> classification) {
    // A projection that only forwards or renames columns computes nothing, and compiles to no
    // kernel: the rule's pre stage plans one over a Varka filter whose output it narrows.
    if (projectList.forall(VarkaSqlBenchmarkBase::passesThrough)) {
      return Residue.NONE_LEFT;
    }
    // Any other projection none of whose entries compiles is not planned as a Varka node, so an
    // empty classification here means the two disagree, and the plan gets no class at all: any
    // class, PARTIAL included, could match what a section lists.
    if (classification.isEmpty()) {
      return Residue.UNCLASSIFIED;
    }
    return classification.get().specs().stream()
        .anyMatch(spec -> spec instanceof VarkaOutputSpec.ResidualOutput)
        ? Residue.ENTRIES_LEFT_TO_ROWS : Residue.NONE_LEFT;
  }

  /** Whether {@code p} is a Varka filter whose condition is only null tests. */
  private static boolean fusesOnlyNullTests(SparkPlan p) {
    return switch (p) {
      case VarkaFilterExec filter -> onlyNullTests(filter.condition());
      case VarkaFilterColumnarToRowExec filter -> onlyNullTests(filter.condition());
      default -> false;
    };
  }

  /** Whether every conjunct of {@code condition} is an {@code IS NOT NULL} or {@code IS NULL}. */
  private static boolean onlyNullTests(Expression condition) {
    return switch (condition) {
      case And and -> onlyNullTests(and.left()) && onlyNullTests(and.right());
      case IsNotNull test -> true;
      case IsNull test -> true;
      default -> false;
    };
  }

  /**
   * Checks that {@code varka} fuses {@code query} as much as {@code expected} says, the
   * section's {@link Fusion} class, and notes in the results file each section that fuses and
   * how. A row that changes how a section fuses, from nothing to its null tests or a split, from
   * either of those to part or the whole of the section, or from part to the whole, sets the
   * section's new class, so a later row that fuses more of a section than listed fails the run
   * rather than timing a kernel under the label of the row path, and a section that fuses less
   * than listed fails too. {@link #FUSE_CHECK_PROPERTY} set to {@code false} lifts the check for
   * the run that measures a row, and every section that fuses is still noted with its class.
   *
   * <p>A section that plans {@code WHOLE} is also checked to run as a kernel
   * ({@link #requireKernel}): a Varka node serves a batch its kernel cannot by another path, which
   * no plan shows, and a varka on arm timing such batches times the row path under a kernel's
   * label. The check keys on the class the plan has, not the one listed, so it runs with the fuse
   * check lifted too, on a section that has just started to fuse included. There a section whose
   * batches go to another path is noted in the results file with its counts rather than failing
   * the run, so the run that measures a row completes and its reader sees which arms timed what.
   *
   * <p>The check plans {@code query} once before the section is timed, and runs it once when it
   * plans {@code WHOLE}. Under adaptive execution reading the plan runs its stages
   * ({@link #operators}); the benchmarks' sessions ({@code VarkaArrowSessions}) run with it off,
   * so planning alone reads the plan there.
   */
  protected final void requireBaseline(
      SparkSession varka, String title, String query, Fusion expected) {
    requireBaseline(varka, title, query, expected, false);
  }

  /**
   * {@link #requireBaseline(SparkSession, String, String, Fusion)}, for a section that times a
   * fallback on purpose when {@code timesFallback} is true: one whose batches its kernel declines
   * by design, so that what its varka on arm measures is the fallback. Such a section's kernel
   * check is not made ({@link BatchCheck#MAY_FALL_BACK}), and the results file notes that the
   * arm times the fallback.
   */
  protected final void requireBaseline(SparkSession varka, String title, String query,
      Fusion expected, boolean timesFallback) {
    requireBaseline(varka, title, query, expected,
        timesFallback ? BatchCheck.MAY_FALL_BACK : BatchCheck.KERNEL);
  }

  /**
   * {@link #requireBaseline(SparkSession, String, String, Fusion)}, with the kernel check of a
   * {@code WHOLE} section as {@code check} says.
   */
  protected final void requireBaseline(SparkSession varka, String title, String query,
      Fusion expected, BatchCheck check) {
    SparkPlan plan = varka.sql(query).queryExecution().executedPlan();
    Optional<Fusion> measured = fusion(plan);
    if (fuseCheck() && !measured.equals(Optional.of(expected))) {
      throw fuseMismatch(title, query, expected, measured, plan);
    }
    if (measured.isEmpty()) {
      note(title + ": fuses more than its null tests on the varka on arm, under a row operator "
          + "that computes the rest");
      return;
    }
    switch (measured.get()) {
      case NONE -> { }
      case NULL_TESTS -> note(title + ": fuses only its null tests on the varka on arm, so it "
          + "times a null-test kernel there under the row path's condition");
      case SPLIT -> note(title + ": fuses the conjuncts that read a value on the varka on arm, so "
          + "it times a kernel there, the copy of the rows it keeps, and the row path's residual "
          + "filter over them");
      case PARTIAL -> note(title + ": fuses in part on the varka on arm, so it times a kernel "
          + "there beside the row path's evaluation of the entries the kernel declined");
      case WHOLE -> noteWhole(varka, title, query, check);
    }
  }

  /** The note of a section that plans {@code WHOLE}, after its kernel check where it has one. */
  private void noteWhole(SparkSession varka, String title, String query, BatchCheck check) {
    if (check == BatchCheck.MAY_FALL_BACK) {
      note(title + ": fuses whole on the varka on arm, and its kernel declines its batches by "
          + "design, so it times the fallback there");
      return;
    }
    KernelRun run = kernelRun(varka, query);
    if (check == BatchCheck.ALL_FALL_BACK) {
      if (run.ranAsFallback()) {
        note(title + ": fuses whole on the varka on arm, and its kernel's guard declines every "
            + "batch of its data, so it times the fallback there (" + run.counts() + ")");
      } else if (fuseCheck()) {
        throw run.kernelFailure(title, query);
      } else {
        note(title + ": fuses whole on the varka on arm and is listed as timing the fallback, "
            + "but its kernel ran " + run.batches() + " batches (" + run.counts() + ")");
      }
      return;
    }
    if (run.ranAsKernel()) {
      note(title + ": fuses whole on the varka on arm, so it times a kernel there, "
          + "not the row path");
    } else if (fuseCheck()) {
      throw run.failure(title, query);
    } else {
      note(title + ": fuses whole on the varka on arm, but the arm times the row path on "
          + run.fallbacks() + " of " + (run.batches() + run.fallbacks()) + " batches ("
          + run.counts() + ")");
    }
  }

  /** Why a section's plan does not have the class it lists. */
  private static IllegalStateException fuseMismatch(String title, String query, Fusion expected,
      Optional<Fusion> measured, SparkPlan plan) {
    return new IllegalStateException(title + " is listed as " + expected + " but "
        + measured.map(f -> "plans as " + f).orElse("fits no fusion class")
        + "; a row that changes how it fuses lists its new class: " + query + "\n"
        + plan.treeString());
  }

  /**
   * Runs {@code query} once on {@code varka} and fails unless its Varka nodes ran a kernel batch
   * and handed none to another path: a node gives a batch its kernel cannot compute, a non-Arrow
   * or an empty one, one a guard declines or a kernel that fails, to Spark's row path, and a
   * timing of such batches is the row path's. The plan's operators come from {@link #operators},
   * subqueries and reused exchanges included, and a Varka node is one with the kernel counter,
   * a projection's and a filter's alike.
   */
  public static void requireKernel(SparkSession varka, String title, String query) {
    KernelRun run = kernelRun(varka, query);
    if (!run.ranAsKernel()) {
      throw run.failure(title, query);
    }
  }

  /**
   * A baseline queries suite's check of one section over the suite's table, the benchmark's own:
   * that {@code varka} plans {@code query} as {@code expected} lists, and that a {@code WHOLE}
   * section runs as a kernel ({@link #requireKernel}). It fails naming the section either way.
   */
  public static void requireSection(
      SparkSession varka, String title, String query, Fusion expected) {
    requireSection(varka, title, query, expected, false);
  }

  /**
   * {@link #requireSection(SparkSession, String, String, Fusion)}, for a section that times a
   * fallback on purpose when {@code timesFallback} is true, whose kernel check is not made, as
   * {@link #requireBaseline(SparkSession, String, String, Fusion, boolean)} does not make it.
   */
  public static void requireSection(SparkSession varka, String title, String query,
      Fusion expected, boolean timesFallback) {
    requireSection(varka, title, query, expected,
        timesFallback ? BatchCheck.MAY_FALL_BACK : BatchCheck.KERNEL);
  }

  /**
   * {@link #requireSection(SparkSession, String, String, Fusion)}, with the kernel check of a
   * {@code WHOLE} section as {@code check} says.
   */
  public static void requireSection(SparkSession varka, String title, String query,
      Fusion expected, BatchCheck check) {
    SparkPlan plan = varka.sql(query).queryExecution().executedPlan();
    Optional<Fusion> measured = fusion(plan);
    if (!measured.equals(Optional.of(expected))) {
      throw fuseMismatch(title, query, expected, measured, plan);
    }
    if (expected == Fusion.WHOLE) {
      switch (check) {
        case KERNEL -> requireKernel(varka, title, query);
        case MAY_FALL_BACK -> { }
        case ALL_FALL_BACK -> requireFallback(varka, title, query);
      }
    }
  }

  /**
   * Runs {@code query} once on {@code varka} and fails unless its Varka nodes handed every batch
   * to another path and ran none as a kernel: the check of a section whose kernel's guard
   * declines every batch of its data ({@link BatchCheck#ALL_FALL_BACK}).
   */
  public static void requireFallback(SparkSession varka, String title, String query) {
    KernelRun run = kernelRun(varka, query);
    if (!run.ranAsFallback()) {
      throw run.kernelFailure(title, query);
    }
  }

  /** Runs {@code query} once and reads what its Varka nodes ran, for {@link #requireKernel}. */
  private static KernelRun kernelRun(SparkSession varka, String query) {
    Dataset<Row> df = varka.sql(query);
    df.queryExecution().toRdd().count();
    var nodes = new ArrayList<NodeRun>();
    for (SparkPlan p : operators(df.queryExecution().executedPlan())) {
      if (!p.metrics().contains("numVarkaBatches")) {
        continue;
      }
      long fallbacks = 0;
      for (String name : VarkaSizeLadder.FALLBACK_METRICS) {
        if (p.metrics().contains(name)) {
          fallbacks += p.metrics().apply(name).value();
        }
      }
      long inputs = p.metrics().contains("numInputBatches")
          ? p.metrics().apply("numInputBatches").value() : -1L;
      nodes.add(new NodeRun(p.nodeName(), inputs, p.metrics().apply("numVarkaBatches").value(),
          fallbacks));
    }
    return new KernelRun(List.copyOf(nodes));
  }

  /**
   * One Varka node's input batches, -1 where it has no such counter, its kernel batches and the
   * batches it handed to another path. The input count tells a node no batch reached, as when the
   * scan below prunes every cached batch, from one whose batches went elsewhere.
   */
  private record NodeRun(String name, long inputs, long batches, long fallbacks) {}

  /** What a section's Varka nodes ran in one execution, read by {@link #kernelRun}. */
  private record KernelRun(List<NodeRun> nodes) {

    long batches() {
      return nodes.stream().mapToLong(NodeRun::batches).sum();
    }

    long fallbacks() {
      return nodes.stream().mapToLong(NodeRun::fallbacks).sum();
    }

    /** At least one Varka node, at least one kernel batch, and no batch handed elsewhere. */
    boolean ranAsKernel() {
      return !nodes.isEmpty() && batches() > 0 && fallbacks() == 0;
    }

    /** At least one Varka node, no kernel batch, and at least one batch handed elsewhere. */
    boolean ranAsFallback() {
      return !nodes.isEmpty() && batches() == 0 && fallbacks() > 0;
    }

    /** The counts, node by node, as a failure or a note states them. */
    String counts() {
      if (nodes.isEmpty()) {
        return "no Varka node ran";
      }
      var out = new ArrayList<String>();
      for (NodeRun node : nodes) {
        out.add(node.name() + " read " + node.inputs() + " batches, ran " + node.batches()
            + " kernel batches and handed " + node.fallbacks() + " to another path");
      }
      return nodes.size() + " Varka nodes: " + String.join("; ", out);
    }

    IllegalStateException failure(String title, String query) {
      return new IllegalStateException(title + " is WHOLE but its varka on arm would time the "
          + "row path: " + counts() + ": " + query);
    }

    IllegalStateException kernelFailure(String title, String query) {
      return new IllegalStateException(title + " is listed as timing the fallback, but its "
          + "varka on arm no longer hands every batch to another path, so its flag is to be "
          + "removed and its results regenerated: " + counts() + ": " + query);
    }
  }
}
