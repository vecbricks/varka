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

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.config.Configurator

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.{ByteCodeStats, CodeGenerator}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaKernelWarmup,
  VarkaShapeCache}
import org.apache.spark.sql.execution.{ColumnarToRowExec, ProjectExec, SQLExecution,
  VarkaColumnarToRowExec, WholeStageCodegenExec}
import org.apache.spark.sql.internal.SQLConf

/**
 * What a new shape's batches cost on each path they could take before its kernel is compiled,
 * the admission check of task 228 (see `PLAN_TASK_228.md`).
 *
 * While a shape's kernel warms, the Varka node serves its batches on Spark's row path: an
 * `UnsafeProjection` applied row by row over each batch's `ColumnarBatchRow`, so every reference
 * to an input column in the projection is a read through the batch's `ArrowColumnVector`.
 * Vanilla Spark scans the same cache row by row - the Arrow cache's row reader writes each row's
 * fields into an `UnsafeRow` once - and projects in one whole-stage method, which reads each
 * field once per row and which HotSpot compiles only while the method is at most 8000 bytes of
 * bytecode (`-XX:HugeMethodLimit`). Task 228 proposes whole-stage code as the cold path wherever
 * it compiles. This benchmark measures what the proposal rests on, at the size ladder's rungs
 * over the cold-start benchmark's hundred thousand Arrow-cached rows:
 *
 *  - **first run** and **steady state** of three paths: vanilla's whole-stage code, the shape
 *    of the proposed cold path; vanilla with whole-stage codegen off, the same projection
 *    applied row by row but over the reader's `UnsafeRow`s; and Varka's row path alone. The last
 *    is the Varka node with no kernel and so no warm-up, forced by the evaluator's
 *    emission-failure test hook, so that nothing compiles beside it. [[VarkaColdStartBenchmark]]
 *    times the same path while a warm-up competes with it for the compiler, and the difference
 *    between the two is the price of that competition. The steady state also times the compiled
 *    kernel, the level a cold path hands over to.
 *  - **the whole-stage class's compile**: Janino's compile of vanilla's generated class for the
 *    rung's projection, from new source every iteration, which vanilla's first run pays and a
 *    whole-stage cold path would pay once per shape; and the size of the class's largest method,
 *    which decides whether HotSpot compiles it at all.
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
  import VarkaSizeLadder.{cacheDates, entry, quiesce, varkaFused}

  private val smoke = sys.env.get("VARKA_COLDPATH_SMOKE").contains("true")

  private val numRows = if (smoke) 10000 else 100000
  private val repetitions = if (smoke) 2 else 5
  private val rungs = if (smoke) Seq(16) else VarkaSizeLadder.rungs
  private val steadyWarmup = if (smoke) 500.millis else 2.seconds

  /**
   * The rung's query at an offset family's iteration, as [[VarkaColdStartBenchmark]] builds it:
   * a hundred months between iterations, so no two share a query, and every executed offset
   * within the kernel's month bound.
   */
  private def query(n: Int, iteration: Int): String = {
    val base = 100 * (iteration + 1)
    s"SELECT ${(1 to n).map(k => entry(base + k)).mkString(", ")} FROM ladder_dates"
  }

  /**
   * The offset families: one per arm's first run, so each compiles its own source, and one for
   * the steady state, which every arm shares. The compile section's queries are generated and
   * never run, so they count up from `compiles` without regard to the month bound.
   */
  private val vanillaFirst = 0
  private val rowwiseFirst = 10
  private val rowPathFirst = 20
  private val steady = 30
  private val compiles = 1000

  /** How long to wait for a warm-up's verdict: a hundred-entry kernel takes seconds. */
  private val warmupTimeoutMillis = 120000L

  /** The logger of the evaluator, whose failed emissions the row-path arm provokes. */
  private val evaluatorLogger = "org.apache.spark.sql.execution.VarkaKernelEvaluator"

  /** A line into the results file, and onto the console. */
  private def report(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  /**
   * Runs `body` with the Varka node unable to obtain a kernel, so that every batch takes its
   * row path and no warm-up starts.
   */
  private def rowPathOnly[T](body: => T): T = {
    VarkaColumnarToRowExec.setFailEmissionForTesting(true)
    try body finally VarkaColumnarToRowExec.setFailEmissionForTesting(false)
  }

  /** Runs `body` with whole-stage codegen off in `session`. */
  private def rowByRow[T](session: SparkSession)(body: => T): T = {
    session.conf.set(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key, false)
    try body finally session.conf.unset(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key)
  }

  private def wholeStage(session: SparkSession, q: String): Option[WholeStageCodegenExec] =
    session.sql(q).queryExecution.executedPlan.collectFirst { case w: WholeStageCodegenExec => w }

  /** Runs `q` once as the timed queries run and returns the Varka node's metrics. */
  private def nodeMetrics(session: SparkSession, q: String): Map[String, Long] = {
    val qe = session.sql(q).queryExecution
    SQLExecution.withNewExecutionId(qe, Some("check"))(qe.toRdd.count())
    val node = qe.executedPlan.collectFirst { case v: VarkaColumnarToRowExec => v }.getOrElse(
      throw new IllegalStateException(s"no Varka node:\n${qe.executedPlan.treeString}"))
    node.metrics.map { case (k, m) => k -> m.value }
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

  /** Every batch of the row-path arm took the row path, with no kernel and no warm-up. */
  private def checkRowPath(varka: SparkSession, q: String): Unit = {
    val counts = rowPathOnly(nodeMetrics(varka, q))
    require(counts("numInputBatches") > 0 && counts("numVarkaBatches") == 0 &&
      counts("numWarmupBatches") == 0 && counts("numEmissionFailures") > 0,
      s"Varka's row path alone did not take the row path for every batch of $q: $counts")
  }

  /** The compiled kernel served every batch: none on the row path, warming or falling back. */
  private def checkKernelServed(session: SparkSession, q: String): Unit = {
    val counts = nodeMetrics(session, q)
    require(counts("numVarkaBatches") > 0 && counts("numWarmupBatches") == 0 &&
      fallbacks(counts) == 0, s"the compiled kernel did not serve every batch of $q: $counts")
  }

  private def awaitVerdict(): VarkaKernelWarmup.Outcome = {
    require(VarkaKernelWarmup.awaitIdle(warmupTimeoutMillis),
      s"no warm-up verdict in ${warmupTimeoutMillis / 1000} seconds")
    VarkaKernelWarmup.recentOutcomes().asScala.last
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
    // The row-path arm's emissions fail on purpose, and each task would log the failure with its
    // stack trace: a cost of the injection, not of the path, so the evaluator's warnings are off
    // for the run. The checks below read the node's metrics instead.
    val evaluatorLevel = LogManager.getLogger(evaluatorLogger).getLevel
    Configurator.setLevel(evaluatorLogger, Level.ERROR)
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
          checkRowPath(varka, steadyQuery)

          val first = new Benchmark(s"$n entries over $numRows Arrow-cached rows, first run",
            numRows, minNumIters = repetitions, warmupTime = 0.seconds, minTime = 0.seconds,
            outputPerIteration = true, output = output)
          first.addTimerCase("vanilla Spark, whole-stage code") { timer =>
            val q = query(n, vanillaFirst + timer.iteration)
            quiesce(warmupTimeoutMillis)
            timer.startTiming()
            baseline.sql(q).noop()
            timer.stopTiming()
          }
          first.addTimerCase("vanilla Spark, whole-stage codegen off") { timer =>
            val q = query(n, rowwiseFirst + timer.iteration)
            quiesce(warmupTimeoutMillis)
            rowByRow(baseline) {
              timer.startTiming()
              baseline.sql(q).noop()
              timer.stopTiming()
            }
          }
          first.addTimerCase("Varka's row path alone") { timer =>
            val q = query(n, rowPathFirst + timer.iteration)
            quiesce(warmupTimeoutMillis)
            rowPathOnly {
              timer.startTiming()
              varka.sql(q).noop()
              timer.stopTiming()
            }
          }
          first.run()

          quiesce(warmupTimeoutMillis)
          val steadyState = new Benchmark(s"$n entries over $numRows Arrow-cached rows, " +
            "steady state", numRows, minNumIters = repetitions, warmupTime = steadyWarmup,
            output = output)
          steadyState.addTimerCase("vanilla Spark, whole-stage code") { timer =>
            timer.startTiming()
            baseline.sql(steadyQuery).noop()
            timer.stopTiming()
          }
          steadyState.addTimerCase("vanilla Spark, whole-stage codegen off") { timer =>
            rowByRow(baseline) {
              timer.startTiming()
              baseline.sql(steadyQuery).noop()
              timer.stopTiming()
            }
          }
          steadyState.addTimerCase("Varka's row path alone") { timer =>
            rowPathOnly {
              timer.startTiming()
              varka.sql(steadyQuery).noop()
              timer.stopTiming()
            }
          }
          // The kernel is warmed on the first call, inside the harness's warmup, so that no
          // case before this one runs beside the warm-up's compiles.
          var verdict: Option[VarkaKernelWarmup.Outcome] = None
          steadyState.addTimerCase("Varka, the kernel once compiled") { timer =>
            if (verdict.isEmpty) {
              VarkaShapeCache.invalidateAll()
              warmup.sql(steadyQuery).noop()
              verdict = Some(awaitVerdict())
              quiesce(warmupTimeoutMillis)
            }
            timer.startTiming()
            warmup.sql(steadyQuery).noop()
            timer.stopTiming()
          }
          steadyState.run()
          checkKernelServed(warmup, steadyQuery)
          report(s"rung $n: the kernel's warm-up verdict: " + verdict.map(o =>
            s"${o.state()} after ${o.runNanos() / 1000000} ms and ${o.calls()} calls").get)
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
      Configurator.setLevel(evaluatorLogger, evaluatorLevel)
      baseline.stop()
      varka.stop()
      warmup.stop()
    }
  }
}
