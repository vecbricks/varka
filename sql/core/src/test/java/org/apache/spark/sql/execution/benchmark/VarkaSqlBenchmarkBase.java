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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import scala.Option;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.VarkaColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFilterColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFilterExec;
import org.apache.spark.sql.execution.VarkaProjectExec;
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
 *   <li>{@link #fusedPlan}, a query's plan when it holds a Varka node, and
 *       {@link #requireBaseline}, a baseline benchmark's check that a section fuses only when
 *       its class lists it as fusing.</li>
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
   */
  public static Optional<SparkPlan> fusedPlan(SparkSession session, String query) {
    SparkPlan plan = session.sql(query).queryExecution().executedPlan();
    boolean fused = plan.find(
        p -> p instanceof VarkaColumnarToRowExec || p instanceof VarkaProjectExec
            || p instanceof VarkaFilterExec || p instanceof VarkaFilterColumnarToRowExec)
        .isDefined();
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

  /**
   * Prints {@code line} and writes it to the results file, if the run writes one: a fact a
   * section's tables depend on, kept beside them so the file says what was measured.
   */
  protected final void note(String line) {
    System.out.println(line);
    Option<OutputStream> out = output();
    if (out.isDefined()) {
      try {
        out.get().write((line + "\n").getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
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
   * Checks that {@code varka} fuses {@code query} exactly when the section says it does, and
   * notes each section that fuses in the results file. {@code fuses} is the section's entry in
   * its class's list of expected fusers: a section a row has fused is listed by that row, so a
   * later row that starts to fuse an unlisted section fails the run rather than timing a kernel
   * under the label of the row path. A listed section that no longer fuses fails too, since the
   * list would then misstate the file. {@link #FUSE_CHECK_PROPERTY} set to {@code false} lifts
   * both checks for the run that measures a row, and every section that fuses is still noted.
   */
  protected final void requireBaseline(
      SparkSession varka, String title, String query, boolean fuses) {
    Optional<SparkPlan> plan = fusedPlan(varka, query);
    if (fuseCheck()) {
      if (plan.isPresent() && !fuses) {
        throw new IllegalStateException(title + " fuses, so it is no longer a baseline; a row "
            + "that fuses it lists it as fusing: " + query + "\n" + plan.get().treeString());
      }
      if (plan.isEmpty() && fuses) {
        throw new IllegalStateException(title + " is listed as fusing but runs on the row "
            + "path: " + query);
      }
    }
    if (plan.isPresent()) {
      note(title + ": fuses on the varka on arm, so it times a kernel there, not the row path");
    }
  }
}
