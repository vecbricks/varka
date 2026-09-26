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

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaKernelWarmup,
  VarkaShapeCache}
import org.apache.spark.sql.execution.{SQLExecution, VarkaColumnarToRowExec, VarkaProjectExec}

/**
 * What the first query costs (tasks 195 and 212): a query's first runs against its steady state,
 * on stock Spark and on Varka with and without the kernel warm-up, at the size ladder's rungs.
 *
 * Every number the ladder publishes is steady state: the harness warms each case for two seconds
 * and reports the best of its iterations. A reader who runs a query once pays something else -
 * planning, the compile of the generated code, and the JVM's own warmup of it - and on Varka the
 * compile is an emission and a class definition per shape, then C2's work on the kernel's
 * methods. This benchmark prices that, so the post can say it beside the headline.
 *
 * Each rung is one projection of `n` entries of `greatest(add_months(d, k), date_add(d, k),
 * last_day(d))` over an Arrow-cached date column, the ladder's own, over fewer rows than the
 * ladder reads so that the steady state does not drown the first run. Three arms, each with its
 * own session: vanilla Spark; Varka with `spark.sql.codegen.varka.warmup.enabled` off, where a new
 * kernel serves every batch from the first and runs interpreted until enough batches have passed
 * through it; and Varka with it on, where a new shape's batches take Spark's row path while a
 * background thread gets the kernel compiled (`PLAN_TASK_212.md` 10). Cases per arm:
 *
 *  - **plan only**: analysis, optimization and physical planning of a shape this JVM has not
 *    seen, up to the executed plan and without running it. On the Varka arms the planner asks
 *    the compiler, which classifies the projection and emits the kernel, so this case holds
 *    the emission; on the vanilla arm code generation happens at execution and is not here.
 *  - **first run**: the same fresh shape, planned and run. Every iteration takes fresh offsets,
 *    so vanilla's generated source is new and Janino compiles it again; the Varka arms also
 *    clear the shape cache first, because literal values are not part of a shape and the same
 *    tree with new constants would be a hit.
 *  - **second run**: the same query again in the same session, at once, which is what the steady
 *    state is on the way to; the difference from the first run is the first run's price.
 *  - **once compiled**, the warm-up arm only: the second run started only after the warm-up has
 *    its verdict, which is the query a shape's kernel serves from then on.
 *
 * Every timed iteration starts with the JVM quiet: no warm-up queued or running, and no JIT
 * compilation finished for a while ([[quiesce]]), so that no case pays for compiles an earlier
 * iteration requested.
 *
 * After each rung's table, a line says how long the warm-up took to its verdict in each
 * iteration of the once-compiled case, and how many kernel calls it made. A second section runs
 * one shape per chosen rung back to back on each arm and prints every query's time, which is
 * where a kernel's switch from the row path shows. A third asks whether the code C2 compiles from
 * the warm-up's short calls is as fast as the code it compiles from real batches: at those rungs,
 * over the ladder's two million rows, the kernel the warm-up compiled - timed straight after its
 * verdict - against the kernel its own batches compiled under the ladder's two-second warmup.
 *
 * A rung whose Varka arms do not fuse every entry fails the run rather than timing a partly
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

  /** The rungs the back-to-back section runs, and how many queries it runs on each arm. */
  private val seriesRungs = if (smoke) Seq(16) else Seq(16, 54, 100)
  private val seriesLength = if (smoke) 4 else 15

  /** Rows for the steady-state section: the ladder's own. */
  private val steadyRows = if (smoke) 20000 else VarkaSizeLadder.numRows

  /**
   * The rung's query with offsets no earlier iteration used, so nothing about it is cached. The
   * offsets are the `add_months` month counts too, and the Varka kernel runs a batch only while
   * a month count is within `VarkaChrono.MONTH_ARITH_MAX_MONTHS`: past it the batch is handed to
   * Spark's row path, and the Varka arm would time vanilla's code. So consecutive iterations are
   * a hundred months apart - more than any rung's width - and the largest one stays under the
   * bound.
   */
  private def query(n: Int, iteration: Int): String = {
    val base = 100 * (iteration + 1)
    s"SELECT ${(1 to n).map(k => entry(base + k)).mkString(", ")} FROM ladder_dates"
  }

  /**
   * The iteration offsets the cases use: `firstRun + t`, `secondRun + t`, `compiled + t` and
   * `planOnly + t` for iteration `t`, and `series` for the back-to-back section. Ten apart, so
   * no two cases share a query while every offset stays far inside the month bound.
   */
  private val firstRun = 0
  private val secondRun = 10
  private val compiled = 20
  private val planOnly = 30
  private val series = 40
  private val steady = 50

  /** How long to wait for a warm-up's verdict: a hundred-entry kernel takes seconds. */
  private val warmupTimeoutMillis = 120000L

  /**
   * Waits until no warm-up is queued or running and the JIT has finished no compilation for a
   * second, or a minute has passed. The JIT's accumulated compile time only moves when a
   * compilation ends, and a kernel method's C2 compile takes under a second, so a second without
   * movement means nothing an earlier iteration requested is still compiling.
   */
  private def quiesce(): Unit = {
    VarkaKernelWarmup.awaitIdle(warmupTimeoutMillis)
    val jit = ManagementFactory.getCompilationMXBean
    val deadline = System.nanoTime() + 60.seconds.toNanos
    var last = jit.getTotalCompilationTime
    var stableSince = System.nanoTime()
    while (System.nanoTime() - stableSince < 1.second.toNanos && System.nanoTime() < deadline) {
      Thread.sleep(50)
      val now = jit.getTotalCompilationTime
      if (now != last) {
        last = now
        stableSince = System.nanoTime()
      }
    }
  }

  /** A line into the results file, and onto the console. */
  private def report(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  /** Waits for the warm-up's verdict and describes it; fails the run if it never came. */
  private def awaitVerdict(): VarkaKernelWarmup.Outcome = {
    require(VarkaKernelWarmup.awaitIdle(warmupTimeoutMillis),
      s"no warm-up verdict in ${warmupTimeoutMillis / 1000} seconds")
    VarkaKernelWarmup.recentOutcomes().asScala.last
  }

  private def describe(o: VarkaKernelWarmup.Outcome): String =
    s"${o.state()} after ${o.runNanos() / 1000000} ms and ${o.calls()} calls"

  /**
   * Runs `q` on `session` once more and checks that its kernel served every batch: none on the
   * row path, warming or falling back. The ladder's own check (`VarkaSizeLadder.varkaFused`) runs
   * its query outside an SQL execution, and so under the default class loader rather than the
   * session's own, which the timed queries run under - a different entry of the shape cache,
   * whose kernel no warm-up has touched. This one runs as the timed queries do.
   */
  private def checkKernelServed(session: SparkSession, q: String): Unit = {
    val qe = session.sql(q).queryExecution
    SQLExecution.withNewExecutionId(qe, Some("check"))(qe.toRdd.count())
    val node = qe.executedPlan.collectFirst {
      case v: VarkaColumnarToRowExec => v
      case v: VarkaProjectExec => v
    }.getOrElse(throw new IllegalStateException(s"no Varka node:\n${qe.executedPlan.treeString}"))
    val counts = node.metrics.collect { case (k, m) if k.endsWith("Batches") => k -> m.value }
    require(counts("numVarkaBatches") > 0 && counts("numWarmupBatches") == 0 &&
      counts.filter(_._1.startsWith("numFallbackBatches")).values.sum == 0,
      s"the compiled kernel did not serve every batch of $q: $counts")
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
    val warmup = createSession("VarkaColdStart-warmup", varkaEnabled = true, warmupEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(Seq(baseline, varka, warmup).distinct.size == 3,
      "the three sessions must be distinct or there is nothing to compare")
    require(repetitions <= secondRun - firstRun, "the offset families would overlap")
    val largest = 100 * (steady + 1) + rungs.max
    require(largest <= VarkaChrono.MONTH_ARITH_MAX_MONTHS,
      s"offsets up to $largest pass the kernel's month bound ${VarkaChrono.MONTH_ARITH_MAX_MONTHS}")
    try {
      cacheDates(baseline, numRows)
      cacheDates(varka, numRows)
      cacheDates(warmup, numRows)
      runBenchmark("the first query: greatest(add_months(d, k), date_add(d, k), last_day(d))") {
        for (n <- rungs) {
          // Every entry fuses and the kernel runs, at the largest offset each executed case uses:
          // the offsets grow with the iteration, so the last one is the one that could fall back.
          for (last <- Seq(firstRun, secondRun, compiled).map(_ + repetitions - 1)) {
            val fused = varkaFused(varka, query(n, last))
            require(fused == n, s"the Varka arm fused $fused of $n entries at iteration $last")
          }
          val benchmark = new Benchmark(s"$n entries over $numRows Arrow-cached rows", numRows,
            minNumIters = repetitions, warmupTime = 0.seconds, minTime = 0.seconds,
            outputPerIteration = true, output = output)
          benchmark.addTimerCase("vanilla Spark, plan only") { timer =>
            val q = query(n, planOnly + timer.iteration)
            quiesce()
            timer.startTiming()
            baseline.sql(q).queryExecution.executedPlan
            timer.stopTiming()
          }
          benchmark.addTimerCase("vanilla Spark, first run") { timer =>
            val q = query(n, firstRun + timer.iteration)
            quiesce()
            timer.startTiming()
            baseline.sql(q).noop()
            timer.stopTiming()
          }
          benchmark.addTimerCase("vanilla Spark, second run") { timer =>
            val q = query(n, secondRun + timer.iteration)
            quiesce()
            baseline.sql(q).noop()
            timer.startTiming()
            baseline.sql(q).noop()
            timer.stopTiming()
          }
          for ((arm, session) <- Seq("Varka" -> varka, "Varka with warm-up" -> warmup)) {
            benchmark.addTimerCase(s"$arm, plan only") { timer =>
              val q = query(n, planOnly + timer.iteration)
              VarkaShapeCache.invalidateAll()
              quiesce()
              timer.startTiming()
              session.sql(q).queryExecution.executedPlan
              timer.stopTiming()
            }
            benchmark.addTimerCase(s"$arm, first run") { timer =>
              val q = query(n, firstRun + timer.iteration)
              VarkaShapeCache.invalidateAll()
              quiesce()
              timer.startTiming()
              session.sql(q).noop()
              timer.stopTiming()
            }
            benchmark.addTimerCase(s"$arm, second run") { timer =>
              val q = query(n, secondRun + timer.iteration)
              VarkaShapeCache.invalidateAll()
              quiesce()
              session.sql(q).noop()
              timer.startTiming()
              session.sql(q).noop()
              timer.stopTiming()
            }
          }
          val verdicts = Seq.newBuilder[VarkaKernelWarmup.Outcome]
          benchmark.addTimerCase("Varka with warm-up, once compiled") { timer =>
            val q = query(n, compiled + timer.iteration)
            VarkaShapeCache.invalidateAll()
            quiesce()
            warmup.sql(q).noop()
            verdicts += awaitVerdict()
            quiesce()
            timer.startTiming()
            warmup.sql(q).noop()
            timer.stopTiming()
          }
          benchmark.run()
          // The last once-compiled query's shape is compiled: check its kernel serves every batch.
          checkKernelServed(warmup, query(n, compiled + repetitions - 1))
          report(s"rung $n: the warm-up's verdicts, one per once-compiled iteration: " +
            verdicts.result().map(describe).mkString("; "))
        }
      }
      runBenchmark("the same shape back to back: ms per query") {
        for (n <- seriesRungs) {
          report(s"$n entries over $numRows Arrow-cached rows, $seriesLength queries of one " +
            "shape back to back, in ms:")
          for ((arm, session) <- Seq("vanilla Spark" -> baseline, "Varka" -> varka,
              "Varka with warm-up" -> warmup)) {
            VarkaShapeCache.invalidateAll()
            quiesce()
            val q = query(n, series)
            val times = (1 to seriesLength).map { _ =>
              val start = System.nanoTime()
              session.sql(q).noop()
              (System.nanoTime() - start) / 1000000
            }
            report(f"  $arm%-20s ${times.mkString(" ")}")
            if (session eq warmup) {
              report(s"  the warm-up's verdict: ${describe(awaitVerdict())}")
            }
          }
        }
      }
      Seq(varka, warmup).foreach { session =>
        session.catalog.uncacheTable("ladder_dates")
        cacheDates(session, steadyRows)
      }
      runBenchmark("steady state: the kernel the warm-up compiled against the batches' own") {
        for (n <- seriesRungs) {
          val q = query(n, steady)
          VarkaShapeCache.invalidateAll()
          quiesce()
          val byBatches = new Benchmark(s"$n entries over $steadyRows Arrow-cached rows, " +
            "compiled from its batches", steadyRows, minNumIters = repetitions,
            warmupTime = 2.seconds, output = output)
          byBatches.addCase("Varka, after two seconds of its own batches") { _ =>
            varka.sql(q).noop()
          }
          byBatches.run()
          VarkaShapeCache.invalidateAll()
          quiesce()
          warmup.sql(q).noop()
          val verdict = awaitVerdict()
          quiesce()
          val byWarmup = new Benchmark(s"$n entries over $steadyRows Arrow-cached rows, " +
            "compiled from the warm-up", steadyRows, minNumIters = repetitions,
            warmupTime = 0.seconds, minTime = 0.seconds, output = output)
          byWarmup.addCase("Varka with warm-up, straight after its verdict") { _ =>
            warmup.sql(q).noop()
          }
          byWarmup.run()
          report(s"rung $n: the warm-up's verdict: ${describe(verdict)}")
          checkKernelServed(warmup, q)
        }
      }
    } finally {
      baseline.stop()
      varka.stop()
      warmup.stop()
    }
  }
}
