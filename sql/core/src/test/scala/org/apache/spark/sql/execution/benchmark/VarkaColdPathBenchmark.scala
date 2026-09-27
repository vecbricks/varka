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

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.{ByteCodeStats, CodeGenerator}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaKernelWarmup,
  VarkaShapeCache}
import org.apache.spark.sql.execution.{ColumnarToRowExec, ProjectExec, QueryExecution, SparkPlan,
  VarkaColumnarToRowExec, VarkaProjectExec, WholeStageCodegenExec}
import org.apache.spark.sql.util.QueryExecutionListener

/**
 * What a new shape's batches cost on each path they can take before its kernel is compiled,
 * the admission check of task 228 (see row 228 of `PLAN_MILESTONE_6.md`).
 *
 * While a shape's kernel warms, and whenever a batch falls back, a Varka node evaluates its
 * projection row by row, with Spark's `UnsafeProjection` over each batch's `ColumnarBatchRow`.
 * Which node that is depends on the consumer. Under a consumer of rows the planner puts
 * `VarkaColumnarToRowExec`, which hands the projected rows on; under a consumer of batches it
 * keeps `VarkaProjectExec`, which writes them back into column vectors. Vanilla Spark scans the
 * same Arrow cache row by row - the cache's row reader writes each row's fields into an
 * `UnsafeRow` - and projects in one whole-stage method, which HotSpot compiles only while the
 * method is at most 8000 bytes of bytecode (`-XX:HugeMethodLimit`).
 *
 * At the size ladder's rungs, over the cold-start benchmark's hundred thousand Arrow-cached rows:
 *
 *  - **rows** (`toRdd` in an SQL execution, every row consumed as a row, the control the other
 *    Varka benchmarks use to force the to-row node): first run and steady state of vanilla's
 *    whole-stage code, of vanilla with whole-stage codegen off, and of `VarkaColumnarToRowExec`
 *    with no kernel; the steady state also times the node with its compiled kernel.
 *  - **a columnar sink** (`noop`, which takes batches, the sink the other Varka benchmarks write
 *    to, the cold-start one included): the steady state of the same vanilla arms, and of
 *    `VarkaProjectExec` with no kernel and with its compiled kernel.
 *  - **the whole-stage class's compile**: Janino's compile of vanilla's generated class for the
 *    rung's projection, from new source every iteration, which vanilla's first run pays, and the
 *    size of the class's largest method, which decides whether HotSpot compiles it at all.
 *
 * "No kernel" is the evaluator's emission-failure test hook: the node cannot obtain a kernel,
 * every batch takes its row path, and no warm-up runs beside it. [[VarkaColdStartBenchmark]]
 * times the same paths while a warm-up competes with them for the compiler. Before each rung
 * the checks run the plans the timed cases run and read their nodes' metrics, so a case that
 * timed another node or another path fails the run.
 *
 * A first run takes offsets no earlier query used, so its generated source is new and Janino
 * compiles it, and each arm has offsets of its own, so that no arm finds another arm's class in
 * the code cache. Every first run starts with the JIT quiet ([[VarkaSizeLadder.quiesce]]).
 *
 * `VARKA_COLDPATH_SMOKE=true` in the environment, which the forked benchmark JVM inherits where
 * a `-D` on sbt's command line does not, runs one rung over ten thousand rows twice, to check
 * the benchmark itself and not to measure anything.
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh sql VarkaColdPathBenchmark --no-narrow
 * }}}
 */
object VarkaColdPathBenchmark extends SqlBasedBenchmark {
  import VarkaArrowSessions.createSession
  import VarkaColdPath._
  import VarkaSizeLadder.{cacheDates, quiesce, varkaFused}

  private val smoke = sys.env.get("VARKA_COLDPATH_SMOKE").contains("true")

  private val numRows = if (smoke) 10000 else 100000
  private val repetitions = if (smoke) 2 else 5
  private val rungs = if (smoke) Seq(16) else VarkaSizeLadder.rungs
  private val steadyWarmup = if (smoke) 500.millis else 2.seconds

  /**
   * The offset families: one per arm's first run, so each compiles its own source; the steady
   * state's, which every arm shares, is [[VarkaColdPath.steady]]. The compile section's queries
   * are generated and never run, so they count up from `compiles` without regard to the month
   * bound.
   */
  private val vanillaFirst = 0
  private val rowwiseFirst = 10
  private val rowPathFirst = 20
  private val compiles = 1000

  /** How long to wait for a warm-up's verdict: a hundred-entry kernel takes seconds. */
  private val warmupTimeoutMillis = 120000L

  /** A line into the results file, and onto the console. */
  private def report(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  private def wholeStage(session: SparkSession, q: String): Option[WholeStageCodegenExec] =
    session.sql(q).queryExecution.executedPlan.collectFirst { case w: WholeStageCodegenExec => w }

  /** Runs `q` as [[toNoop]] does and returns the write's executed plan, from its listener. */
  private def noopPlan(session: SparkSession, q: String): SparkPlan = {
    val captured = new AtomicReference[SparkPlan]()
    val listener = new QueryExecutionListener {
      override def onSuccess(funcName: String, qe: QueryExecution, durationNs: Long): Unit =
        captured.set(qe.executedPlan)
      override def onFailure(funcName: String, qe: QueryExecution, error: Exception): Unit = ()
    }
    session.listenerManager.register(listener)
    try {
      toNoop(session, q)
      session.sparkContext.listenerBus.waitUntilEmpty()
    } finally {
      session.listenerManager.unregister(listener)
    }
    Option(captured.get).getOrElse(throw new IllegalStateException(s"no plan for the write: $q"))
  }

  /** The metrics of the plan's one Varka node, which must be of the class the sink plans. */
  private def nodeMetrics(plan: SparkPlan, expected: Class[_ <: SparkPlan]): Map[String, Long] = {
    val nodes = plan.collect {
      case v: VarkaColumnarToRowExec => v
      case v: VarkaProjectExec => v
    }
    require(nodes.size == 1 && expected.isInstance(nodes.head),
      s"expected one ${expected.getSimpleName}:\n${plan.treeString}")
    nodes.head.metrics.map { case (k, m) => k -> m.value }
  }

  private def fallbacks(counts: Map[String, Long]): Long =
    counts.filter(_._1.startsWith("numFallbackBatches")).values.sum

  /**
   * The whole-stage arm compiles the projection over the scan's rows, with no columnar
   * transition between them; the row-by-row arm generates no stage.
   */
  private def checkVanilla(baseline: SparkSession, q: String): Unit = {
    val plan = baseline.sql(q).queryExecution.executedPlan
    require(plan.collectFirst { case w: WholeStageCodegenExec => w }
      .exists(_.child.isInstanceOf[ProjectExec]) &&
      !plan.exists(_.isInstanceOf[ColumnarToRowExec]),
      s"vanilla's plan is not a whole-stage projection over the scan's rows:\n${plan.treeString}")
    require(rowByRow(baseline)(wholeStage(baseline, q)).isEmpty,
      s"whole-stage codegen off still generated a stage: $q")
  }

  /** Under each sink, the no-kernel arm's node took its row path for every batch. */
  private def checkNoKernel(varka: SparkSession, q: String): Unit = {
    for ((plan, node) <- Seq(
        noKernel(rowsPlan(varka, q)) -> classOf[VarkaColumnarToRowExec],
        noKernel(noopPlan(varka, q)) -> classOf[VarkaProjectExec])) {
      val counts = nodeMetrics(plan, node)
      require(counts("numInputBatches") > 0 && counts("numVarkaBatches") == 0 &&
        counts("numWarmupBatches") == 0 && counts("numEmissionFailures") > 0,
        s"${node.getSimpleName} with no kernel did not take its row path for every batch: $counts")
    }
  }

  /** Under each sink, the compiled kernel served every batch: none warming or falling back. */
  private def checkKernelServed(warmup: SparkSession, q: String): Unit = {
    for ((plan, node) <- Seq(
        rowsPlan(warmup, q) -> classOf[VarkaColumnarToRowExec],
        noopPlan(warmup, q) -> classOf[VarkaProjectExec])) {
      val counts = nodeMetrics(plan, node)
      require(counts("numVarkaBatches") > 0 && counts("numWarmupBatches") == 0 &&
        fallbacks(counts) == 0,
        s"the compiled kernel did not serve every batch of ${node.getSimpleName}: $counts")
    }
  }

  /** Runs `start` and waits for the warm-up it queued: its verdict, or None if it queued none. */
  private def warmUp(start: => Unit): Option[VarkaKernelWarmup.Outcome] = {
    val before = VarkaKernelWarmup.recentOutcomes().asScala.lastOption
    start
    require(VarkaKernelWarmup.awaitIdle(warmupTimeoutMillis),
      s"no warm-up verdict in ${warmupTimeoutMillis / 1000} seconds")
    VarkaKernelWarmup.recentOutcomes().asScala.lastOption.filterNot(o => before.exists(_ eq o))
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // The inherited session uses the default cache serializer; these arms own their
    // Arrow-backed sessions, as the ladder's do.
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val baseline = createSession("VarkaColdPath-vanilla", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaColdPath-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val warmup = createSession("VarkaColdPath-warmup", varkaEnabled = true, warmupEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(Seq(baseline, varka, warmup).distinct.size == 3,
      "the three sessions must be distinct or there is nothing to compare")
    require(repetitions <= rowwiseFirst - vanillaFirst, "the offset families would overlap")
    val largest = 100 * (steady + 1) + rungs.max
    require(largest <= VarkaChrono.MONTH_ARITH_MAX_MONTHS,
      s"offsets up to $largest pass the kernel's month bound ${VarkaChrono.MONTH_ARITH_MAX_MONTHS}")
    val restoreEvaluator = silenceEvaluator()
    try {
      cacheDates(baseline, numRows)
      cacheDates(varka, numRows)
      cacheDates(warmup, numRows)
      runBenchmark("the cold path: greatest(add_months(d, k), date_add(d, k), last_day(d))") {
        for (n <- rungs) {
          val steadyQuery = query(n, steady)
          for (q <- Seq(rowPathFirst + repetitions - 1, steady).map(query(n, _))) {
            val fused = varkaFused(varka, q)
            require(fused == n, s"the Varka arm fused $fused of $n entries: $q")
          }
          checkVanilla(baseline, steadyQuery)
          checkNoKernel(varka, steadyQuery)

          val first = new Benchmark(s"$n entries over $numRows Arrow-cached rows, rows, " +
            "first run", numRows, minNumIters = repetitions, warmupTime = 0.seconds,
            minTime = 0.seconds, outputPerIteration = true, output = output)
          first.addTimerCase("vanilla Spark, whole-stage code") { timer =>
            val q = query(n, vanillaFirst + timer.iteration)
            quiesce(warmupTimeoutMillis)
            timer.startTiming()
            toRows(baseline, q)
            timer.stopTiming()
          }
          first.addTimerCase("vanilla Spark, whole-stage codegen off") { timer =>
            val q = query(n, rowwiseFirst + timer.iteration)
            quiesce(warmupTimeoutMillis)
            rowByRow(baseline) {
              timer.startTiming()
              toRows(baseline, q)
              timer.stopTiming()
            }
          }
          first.addTimerCase("Varka to rows, no kernel") { timer =>
            val q = query(n, rowPathFirst + timer.iteration)
            quiesce(warmupTimeoutMillis)
            noKernel {
              timer.startTiming()
              toRows(varka, q)
              timer.stopTiming()
            }
          }
          first.run()

          // The kernel is warmed on the first call of its case, inside the harness's warmup, so
          // that no case before it runs beside the warm-up's compiles; the columnar table's
          // kernel case then finds the same kernel compiled, since the two nodes share it.
          var verdict: Option[Option[VarkaKernelWarmup.Outcome]] = None
          for ((sink, sinkRun, node) <- Seq(
              ("rows", toRows _, "Varka to rows"),
              ("a columnar sink", toNoop _, "Varka columnar"))) {
            quiesce(warmupTimeoutMillis)
            val table = new Benchmark(s"$n entries over $numRows Arrow-cached rows, $sink, " +
              "steady state", numRows, minNumIters = repetitions, warmupTime = steadyWarmup,
              output = output)
            table.addTimerCase("vanilla Spark, whole-stage code") { timer =>
              timer.startTiming()
              sinkRun(baseline, steadyQuery)
              timer.stopTiming()
            }
            table.addTimerCase("vanilla Spark, whole-stage codegen off") { timer =>
              rowByRow(baseline) {
                timer.startTiming()
                sinkRun(baseline, steadyQuery)
                timer.stopTiming()
              }
            }
            table.addTimerCase(s"$node, no kernel") { timer =>
              noKernel {
                timer.startTiming()
                sinkRun(varka, steadyQuery)
                timer.stopTiming()
              }
            }
            table.addTimerCase(s"$node, the kernel once compiled") { timer =>
              if (verdict.isEmpty) {
                VarkaShapeCache.invalidateAll()
                verdict = Some(warmUp(sinkRun(warmup, steadyQuery)))
                quiesce(warmupTimeoutMillis)
              }
              timer.startTiming()
              sinkRun(warmup, steadyQuery)
              timer.stopTiming()
            }
            table.run()
          }
          checkKernelServed(warmup, steadyQuery)
          report(s"rung $n: the kernel's warm-up verdict: " + verdict.flatten.map(o =>
            s"${o.state()} after ${o.runNanos() / 1000000} ms and ${o.calls()} calls")
            .getOrElse("none, the kernel was compiled already"))
        }
      }
      runBenchmark("the whole-stage class's compile: Janino, from new source every iteration") {
        for (n <- rungs) {
          var fresh = compiles
          var stats: Option[ByteCodeStats] = None
          val benchmark = new Benchmark(s"$n entries: vanilla's whole-stage class", 1,
            minNumIters = repetitions, warmupTime = steadyWarmup, output = output)
          benchmark.addTimerCase("Janino compile of the generated class") { timer =>
            fresh += 1
            val q = query(n, fresh)
            val source = wholeStage(baseline, q).getOrElse(
              throw new IllegalStateException(s"no whole-stage class for $q")).doCodeGen()._2
            timer.startTiming()
            val (_, compiled) = CodeGenerator.compile(source)
            timer.stopTiming()
            stats = Some(compiled)
          }
          benchmark.run()
          val bytes = stats.get.maxMethodCodeSize
          report(s"rung $n: the whole-stage class's largest method is $bytes bytes, " +
            (if (bytes > CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT) "past" else "under") +
            s" HugeMethodLimit ${CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT}")
        }
      }
    } finally {
      restoreEvaluator()
      baseline.stop()
      varka.stop()
      warmup.stop()
    }
  }
}
