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

import static org.apache.spark.sql.VarkaBenchmarkBase.require;

import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import scala.Option;
import scala.collection.immutable.Map;
import scala.collection.immutable.Seq;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.FusedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmup;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.VarkaColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaColumnarToRowExec$;
import org.apache.spark.sql.execution.VarkaProjectExec;
import org.apache.spark.sql.execution.VarkaSessionEmit;
import org.apache.spark.sql.execution.metric.SQLMetric;

/**
 * The size ladder's rungs, data and query, shared by {@link VarkaSizeLadderBenchmark}, {@link
 * VarkaSizeLadderTuningBenchmark}, {@link VarkaSizeLadderParallelBenchmark} and the benchmarks
 * that time a new shape's first queries over the same data ({@link VarkaColdStartBenchmark},
 * {@link VarkaColdPathBenchmark}), with the wait for a quiet JIT those start each timed iteration
 * with. A class of static helpers rather than members of one benchmark, since mains that are no
 * benchmark call them too: {@link VarkaColdPathProbe} and the child JVM of {@link
 * VarkaWarmupDirectiveBenchmark}.
 */
public final class VarkaSizeLadder {

  private VarkaSizeLadder() {}

  /**
   * Two million rows, as {@code VarkaThroughputBenchmark} reads: each case plans its query
   * afresh, and planning a wide projection plus its first row costs tens of milliseconds, which
   * over a hundred thousand rows was most of the Varka arm's time per row and most of its
   * kernel's warmup ({@code VARKA-171.md} 9).
   */
  public static final int NUM_ROWS = 2_000_000;

  /** Straddling the vanilla crossing; see {@link VarkaSizeLadderBenchmark}. */
  public static final List<Integer> RUNGS = List.of(16, 32, 48, 52, 54, 56, 64, 80, 100);

  /** The fallback counters of a Varka node, each a batch some path other than the kernel ran. */
  static final List<String> FALLBACK_METRICS = List.of("numFallbackBatchesNonArrow",
      "numFallbackBatchesKernel", "numFallbackBatchesRowPath", "numFallbackBatchesDeclined");

  /** The ladder's entry {@code k}, a column {@code c<k>} of three date functions of {@code d}. */
  public static String entry(int k) {
    return "greatest(add_months(d, " + k + "), date_add(d, " + k + "), last_day(d)) AS c" + k;
  }

  /** The rung of {@code n} entries: entries 1 to {@code n} over {@code ladder_dates}. */
  public static String query(int n) {
    return "SELECT "
        + IntStream.rangeClosed(1, n).mapToObj(VarkaSizeLadder::entry)
            .collect(Collectors.joining(", "))
        + " FROM ladder_dates";
  }

  /** {@link #cacheDates(SparkSession, int)} over the ladder's {@link #NUM_ROWS}. */
  public static void cacheDates(SparkSession session) {
    cacheDates(session, NUM_ROWS);
  }

  /**
   * Caches {@code ladder_dates}, {@code rows} dates over four years from 2020, one in 31 null,
   * in the session's Arrow cache.
   */
  public static void cacheDates(SparkSession session, int rows) {
    session.sql("""
        select case when id %% 31 = 0 then null
               else date_add(date'2020-01-01', cast(id as int) %% 1460) end as d
        from range(0, %d)""".formatted(rows))
        .createOrReplaceTempView("ladder_dates");
    VarkaArrowSessions.cache(session, "ladder_dates");
  }

  /** {@link #quiesce(long)} with two minutes for the kernel warm-up. */
  public static void quiesce() throws InterruptedException {
    quiesce(120_000L);
  }

  /**
   * Waits until no kernel warm-up is queued or running and the JIT has finished no compilation
   * for a second, or a minute has passed, so that a timed iteration pays for no compile an
   * earlier one requested. The JIT's accumulated compile time only moves when a compilation ends,
   * and a kernel method's C2 compile takes under a second, so a second without movement means
   * nothing is still compiling.
   */
  public static void quiesce(long warmupTimeoutMillis) throws InterruptedException {
    VarkaKernelWarmup.awaitIdle(warmupTimeoutMillis);
    CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
    long quiet = Duration.ofSeconds(1).toNanos();
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    long last = jit.getTotalCompilationTime();
    long stableSince = System.nanoTime();
    while (System.nanoTime() - stableSince < quiet && System.nanoTime() < deadline) {
      Thread.sleep(50);
      long now = jit.getTotalCompilationTime();
      if (now != last) {
        last = now;
        stableSince = System.nanoTime();
      }
    }
  }

  /**
   * {@link #quiesce()} where an {@link InterruptedException} cannot be declared, such as a
   * section's body: the interrupt is restored and the run fails.
   */
  public static void quiesceOrFail() {
    try {
      quiesce();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for a quiet JIT", e);
    }
  }

  /**
   * How many entries the Varka arm fused, after checking that it ran as a kernel and that no
   * batch fell back: a kernel's guard hands a batch it cannot compute - a value outside the range
   * it covers, say - to Spark's row path, and a timing of such batches is vanilla's.
   */
  public static int varkaFused(SparkSession varka, String query) {
    Dataset<Row> df = varka.sql(query);
    df.queryExecution().toRdd().count();
    SparkPlan plan = df.queryExecution().executedPlan();
    // `emitOptions` is private[sql], which scalac gives no static forwarder.
    VarkaEmitOptions options = VarkaColumnarToRowExec$.MODULE$.emitOptions(
        VarkaSessionEmit.of(varka.sessionState().conf()));
    Option<SparkPlan> node = plan.find(
        p -> p instanceof VarkaProjectExec || p instanceof VarkaColumnarToRowExec);
    if (node.isEmpty()) {
      throw new IllegalStateException("the Varka arm did not fuse:\n" + plan.treeString());
    }
    Outcome outcome = switch (node.get()) {
      case VarkaProjectExec v -> Outcome.of(v, v.projectList(), options);
      case VarkaColumnarToRowExec v -> Outcome.of(v, v.projectList(), options);
      default -> throw new IllegalStateException("unreachable: " + node.get().nodeName());
    };
    require(outcome.batches() > 0,
        "the Varka arm fused but fell back at run time: " + query);
    require(outcome.fallbacks() == 0,
        "the Varka arm handed " + outcome.fallbacks() + " batches to the row path: " + query);
    return outcome.fused()
        .map(p -> (int) p.specs().stream().filter(s -> s instanceof FusedOutput).count())
        .orElse(0);
  }

  /** What a Varka node planned and ran: its fused entries, its kernel batches, its fallbacks. */
  private record Outcome(Optional<PartialVarkaProjection> fused, long batches, long fallbacks) {
    static Outcome of(SparkPlan node, Seq<NamedExpression> projectList, VarkaEmitOptions options) {
      Map<String, SQLMetric> metrics = node.metrics();
      long fallbacks = 0;
      for (String name : FALLBACK_METRICS) {
        Option<SQLMetric> metric = metrics.get(name);
        if (metric.isDefined()) {
          fallbacks += metric.get().value();
        }
      }
      return new Outcome(
          VarkaExpressionCompiler.compilePartial(
              projectList, node.children().head().output(), options),
          metrics.apply("numVarkaBatches").value(), fallbacks);
    }
  }
}
