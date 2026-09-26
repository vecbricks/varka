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

package org.apache.spark.sql.execution.benchmark

import scala.concurrent.duration._

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeCache

/**
 * What the first query costs (task 195): a query's first run against its steady state, on stock
 * Spark and on Varka, at the size ladder's rungs.
 *
 * Every number the ladder publishes is steady state: the harness warms each case for two seconds
 * and reports the best of its iterations. A reader who runs a query once pays something else -
 * planning, the compile of the generated code, and the JVM's own warmup of it - and on Varka the
 * compile is an emission and a class definition per shape, then C2's work on the kernel's
 * methods. This benchmark prices that once, so the post can say it beside the headline.
 *
 * Each rung is one projection of `n` entries of `greatest(add_months(d, k), date_add(d, k),
 * last_day(d))` over an Arrow-cached date column, the ladder's own, over fewer rows than the
 * ladder reads so that the steady state does not drown the first run. Three cases per arm:
 *
 *  - **plan only**: analysis, optimization and physical planning of a shape this JVM has not
 *    seen, up to the executed plan and without running it. On the Varka arm the planner asks
 *    the compiler, which classifies the projection and emits the kernel, so this case holds
 *    the emission; on the vanilla arm code generation happens at execution and is not here.
 *  - **first run**: the same fresh shape, planned and run. Every iteration takes fresh offsets,
 *    so vanilla's generated source is new and Janino compiles it again; the Varka arm also
 *    clears the shape cache first, because literal values are not part of a shape and the same
 *    tree with new constants would be a hit.
 *  - **second run**: the same query again in the same session, which is what the steady state
 *    is on the way to; the difference from the first run is the first run's price, and the
 *    difference from the plan-only case is what running costs.
 *
 * A rung whose Varka arm does not fuse every entry fails the run rather than timing a partly
 * per-row Varka arm, as the ladder does.
 *
 * Iterations are printed one by one rather than summarised, because a first run is one event
 * and its spread is the finding. The rungs and data are `VarkaSizeLadder`'s.
 *
 * `VARKA_COLDSTART_SMOKE=true` in the environment, which the forked benchmark JVM inherits
 * where a `-D` on sbt's command line does not, runs one rung over ten thousand rows twice, to
 * check the benchmark itself and not to measure anything.
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh sql VarkaColdStartBenchmark
 * }}}
 */
object VarkaColdStartBenchmark extends SqlBasedBenchmark {
  import VarkaArrowSessions.createSession
  import VarkaSizeLadder.{cacheDates, entry, varkaFused}

  private val smoke = sys.env.get("VARKA_COLDSTART_SMOKE").contains("true")

  /**
   * Rows per query: enough that a run is a query with batches and a JIT warmup rather than a
   * plan, and a twentieth of the ladder's so the first run is not lost in the steady state.
   */
  private val numRows = if (smoke) 10000 else 100000
  private val repetitions = if (smoke) 2 else 5
  private val rungs = if (smoke) Seq(16) else VarkaSizeLadder.rungs

  /** The rung's query with offsets no earlier iteration used, so nothing about it is cached. */
  private def query(n: Int, iteration: Int): String = {
    val base = 10000 * (iteration + 1)
    s"SELECT ${(1 to n).map(k => entry(base + k)).mkString(", ")} FROM ladder_dates"
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // The inherited session uses the default cache serializer; these arms own their
    // Arrow-backed sessions, as the ladder's do.
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val baseline = createSession("VarkaColdStart-vanilla", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaColdStart-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    try {
      cacheDates(baseline, numRows)
      cacheDates(varka, numRows)
      runBenchmark("the first query: greatest(add_months(d, k), date_add(d, k), last_day(d))") {
        for (n <- rungs) {
          val fused = varkaFused(varka, query(n, 0))
          require(fused == n, s"the Varka arm fused $fused of $n entries")
          val benchmark = new Benchmark(s"$n entries over $numRows Arrow-cached rows", numRows,
            minNumIters = repetitions, warmupTime = 0.seconds, minTime = 0.seconds,
            outputPerIteration = true, output = output)
          benchmark.addTimerCase("vanilla Spark, plan only") { timer =>
            val q = query(n, 200 + timer.iteration)
            timer.startTiming()
            baseline.sql(q).queryExecution.executedPlan
            timer.stopTiming()
          }
          benchmark.addTimerCase("vanilla Spark, first run") { timer =>
            val q = query(n, timer.iteration)
            timer.startTiming()
            baseline.sql(q).noop()
            timer.stopTiming()
          }
          benchmark.addTimerCase("vanilla Spark, second run") { timer =>
            val q = query(n, 100 + timer.iteration)
            baseline.sql(q).noop()
            timer.startTiming()
            baseline.sql(q).noop()
            timer.stopTiming()
          }
          benchmark.addTimerCase("Varka, plan only") { timer =>
            val q = query(n, 200 + timer.iteration)
            VarkaShapeCache.invalidateAll()
            timer.startTiming()
            varka.sql(q).queryExecution.executedPlan
            timer.stopTiming()
          }
          benchmark.addTimerCase("Varka, first run") { timer =>
            val q = query(n, timer.iteration)
            VarkaShapeCache.invalidateAll()
            timer.startTiming()
            varka.sql(q).noop()
            timer.stopTiming()
          }
          benchmark.addTimerCase("Varka, second run") { timer =>
            val q = query(n, 100 + timer.iteration)
            VarkaShapeCache.invalidateAll()
            varka.sql(q).noop()
            timer.startTiming()
            varka.sql(q).noop()
            timer.stopTiming()
          }
          benchmark.run()
        }
      }
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
