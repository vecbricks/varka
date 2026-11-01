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
import org.apache.spark.sql.execution.ColumnarToRowTransition;
import org.apache.spark.sql.execution.InputAdapter;
import org.apache.spark.sql.execution.LeafExecNode;
import org.apache.spark.sql.execution.ProjectExec;
import org.apache.spark.sql.execution.RowToColumnarTransition;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.VarkaColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFilterColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFilterExec;
import org.apache.spark.sql.execution.VarkaProjectExec;
import org.apache.spark.sql.execution.WholeStageCodegenExec;
import org.apache.spark.sql.execution.adaptive.AQEShuffleReadExec;
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec;
import org.apache.spark.sql.execution.adaptive.QueryStageExec;
import org.apache.spark.sql.execution.exchange.Exchange;
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
 *       much of a plan Varka fuses, and {@link #requireBaseline}, a baseline benchmark's check
 *       that a section fuses only as its class lists it.</li>
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
   * Under adaptive execution the plan is searched as {@link #operators} reads it.
   */
  public static Optional<SparkPlan> fusedPlan(SparkSession session, String query) {
    SparkPlan plan = session.sql(query).queryExecution().executedPlan();
    boolean fused = operators(plan).stream().anyMatch(VarkaSqlBenchmarkBase::isVarkaNode);
    return fused ? Optional.of(plan) : Optional.empty();
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
   *       NULL} and {@code IS NULL} tests, such as those the optimizer infers from a condition
   *       that is null-intolerant, and a row operator ({@link #computesOnRows}) still evaluates
   *       the rest of the section. The varka on arm then times a null-test kernel under the row
   *       path's expression, and the spark row path arm is the baseline.</li>
   *   <li>{@code PARTIAL}: no row operator evaluates any of the section, but a Varka projection
   *       leaves an entry to its own per-row fallback ({@link #leavesEntriesToRows}): the entry
   *       its kernel declines is evaluated row by row inside the Varka node, so the varka on arm
   *       times a kernel and the row path's expression together.</li>
   *   <li>{@code WHOLE}: the plan holds a Varka node, no row operator, and no Varka projection
   *       with an entry left to rows, so the varka on arm times a kernel and not the row
   *       path.</li>
   * </ul>
   *
   * <p>A plan that is none of these, a Varka node fusing more than null tests while a row
   * operator still computes part of the section, has no class: {@link #fusion} answers empty for
   * it, and the check refuses it whatever the section expects.
   *
   * <p>A row operator decides first. Without one, a plan is {@code PARTIAL} or {@code WHOLE} by
   * its Varka projections, whatever its Varka filters fuse, null tests only included. With one, it
   * is {@code NULL_TESTS} only when every Varka node is a null-test filter, so a Varka projection
   * beside a row operator, one that leaves entries to rows or one that does not, leaves the plan
   * with no class rather than {@code PARTIAL}.
   *
   * <p>One limit: a Varka node also serves a batch the kernel cannot, a non-Arrow or an empty one
   * or a kernel that fails, by the row path. That is decided per batch when the plan runs, so no
   * plan shows it; a timed run checks its batches by the node's metrics where it needs to.
   */
  public enum Fusion { NONE, NULL_TESTS, PARTIAL, WHOLE }

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
    if (operators.stream().noneMatch(VarkaSqlBenchmarkBase::computesOnRows)) {
      return operators.stream().noneMatch(VarkaSqlBenchmarkBase::leavesEntriesToRows)
          ? Optional.of(Fusion.WHOLE) : Optional.of(Fusion.PARTIAL);
    }
    boolean beyondNullTests =
        operators.stream().anyMatch(p -> isVarkaNode(p) && !fusesOnlyNullTests(p));
    return beyondNullTests ? Optional.empty() : Optional.of(Fusion.NULL_TESTS);
  }

  /**
   * Every operator of {@code plan}, the plan adaptive execution runs included. Its wrappers are
   * leaves, so a plain search stops at them: an {@code AdaptiveSparkPlanExec} is read through its
   * final plan, and a query stage through the plan it runs. The final plan is the one with the
   * Varka nodes, since adaptive execution applies the columnar rules as it creates each stage
   * rather than to its initial plan; reading it materializes the stages under the final one, as
   * running the query would. A stage adaptive execution finds empty is replaced by an empty
   * relation, which holds no Varka node, since none runs.
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
      default -> {
        out.add(p);
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
   * them: a leaf, which for a baseline is the scan of its cached table; an exchange; a
   * transition between columns and rows; adaptive execution's read of a shuffle's partitions;
   * the whole-stage codegen node and its input adapter, which wrap the operators they compile,
   * each found on its own; and a projection whose every entry is a column passed through or
   * renamed. So an operator this list does not name counts as row work, and a plan that gains
   * one fails the check rather than reading as fused. Adaptive execution's own wrappers never
   * reach here: {@link #operators} reads through them.
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
      case ProjectExec project -> project.projectList().exists(e -> !passesThrough(e));
      default -> true;
    };
  }

  /** Whether a projection entry is a column passed through, or renamed by an alias. */
  private static boolean passesThrough(NamedExpression entry) {
    return entry instanceof Attribute
        || entry instanceof Alias alias && alias.child() instanceof Attribute;
  }

  /**
   * Whether {@code p} is a Varka projection that leaves an entry to its per-row fallback: an
   * entry its kernel declines ({@code ResidualOutput}), which the node evaluates row by row
   * beside the kernel's. It reads the node's own classification, the compile that also serves
   * its EXPLAIN and its residual-entry metric, so the check compiles nothing of its own.
   */
  private static boolean leavesEntriesToRows(SparkPlan p) {
    return switch (p) {
      case VarkaProjectExec project ->
          leavesEntriesToRows(project.projectList(), project.classification());
      case VarkaColumnarToRowExec project ->
          leavesEntriesToRows(project.projectList(), project.classification());
      default -> false;
    };
  }

  private static boolean leavesEntriesToRows(
      Seq<NamedExpression> projectList, Optional<PartialVarkaProjection> classification) {
    // A projection that only forwards or renames columns computes nothing, and compiles to no
    // kernel: the rule's pre stage plans one over a Varka filter whose output it narrows.
    if (projectList.forall(VarkaSqlBenchmarkBase::passesThrough)) {
      return false;
    }
    // Any other projection none of whose entries compiles is not planned as a Varka node, so an
    // empty classification here would mean the two disagree; it counts as left to rows, the safe
    // side.
    return classification.map(projection -> projection.specs().stream()
        .anyMatch(spec -> spec instanceof VarkaOutputSpec.ResidualOutput)).orElse(true);
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
   * how. A row that changes how a section fuses, from nothing to its null tests, from its null
   * tests to part or the whole of the section, or from part to the whole, sets the section's new
   * class, so a later row that fuses more of a section than listed fails the run rather than
   * timing a kernel under the label of the row path, and a section that fuses less than listed
   * fails too. {@link #FUSE_CHECK_PROPERTY} set to {@code false} lifts the check for the run that
   * measures a row, and every section that fuses is still noted with its class.
   */
  protected final void requireBaseline(
      SparkSession varka, String title, String query, Fusion expected) {
    SparkPlan plan = varka.sql(query).queryExecution().executedPlan();
    Optional<Fusion> measured = fusion(plan);
    if (fuseCheck() && !measured.equals(Optional.of(expected))) {
      throw new IllegalStateException(title + " is listed as " + expected + " but "
          + measured.map(f -> "plans as " + f).orElse("fits no fusion class")
          + "; a row that changes how it fuses lists its new class: " + query + "\n"
          + plan.treeString());
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
      case PARTIAL -> note(title + ": fuses in part on the varka on arm, so it times a kernel "
          + "there beside the row path's evaluation of the entries the kernel declined");
      case WHOLE -> note(title + ": fuses whole on the varka on arm, so it times a kernel there, "
          + "not the row path");
    }
  }
}
