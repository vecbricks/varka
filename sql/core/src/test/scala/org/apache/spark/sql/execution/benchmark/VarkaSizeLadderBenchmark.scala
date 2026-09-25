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

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeGenerator, FusedOutput,
  VarkaExpressionCompiler}
import org.apache.spark.sql.execution.{VarkaColumnarToRowExec, VarkaProjectExec}

/**
 * The size ladder's rungs, data and query, shared by [[VarkaSizeLadderBenchmark]] and
 * [[VarkaSizeLadderTuningBenchmark]]. A plain object, not the benchmark's, for the reason
 * [[VarkaArrowSessions]] gives.
 */
object VarkaSizeLadder {

  /**
   * Two million rows, as `VarkaThroughputBenchmark` reads: each case plans its query afresh, and
   * planning a wide projection plus its first row costs tens of milliseconds, which over a
   * hundred thousand rows was most of the Varka arm's time per row and most of its kernel's
   * warmup (`PLAN_TASK_171.md` 9).
   */
  private[benchmark] val numRows = 2000000

  /** Straddling the vanilla crossing; see [[VarkaSizeLadderBenchmark]]. */
  private[benchmark] val rungs = Seq(16, 32, 48, 52, 54, 56, 64, 80, 100)

  private[benchmark] def entry(k: Int): String =
    s"greatest(add_months(d, $k), date_add(d, $k), last_day(d)) AS c$k"

  private[benchmark] def cacheDates(session: SparkSession, rows: Int = numRows): Unit = {
    session.sql(
      s"""select case when id % 31 = 0 then null
         |       else date_add(date'2020-01-01', cast(id as int) % 1460) end as d
         |from range(0, $rows)""".stripMargin)
      .createOrReplaceTempView("ladder_dates")
    VarkaArrowSessions.cache(session, "ladder_dates")
  }
}

/**
 * The size ladder (task 171): one projection widened rung by rung, on stock Spark and on
 * Varka, time per row against the number of entries.
 *
 * Every entry is `greatest(add_months(d, k), date_add(d, k), last_day(d))` for its own `k`,
 * over one Arrow-cached date column, and the projection ends in `noop()`. Vanilla Spark
 * generates the whole projection into one method, the projection's consume function, which
 * grows by about 190 bytes an entry and crosses HotSpot's `HugeMethodLimit`, 8000 bytes,
 * between 52 and 54 entries; past it the method is loaded and never compiled, and Spark logs
 * nothing (`VarkaSizeLadderJitSuite`). Varka emits the same
 * projection as a kernel whose every method is held under that limit by the byte budget, so no
 * rung crosses anything. The rungs straddle the crossing rather than being round, and stop at a
 * hundred: past it `spark.sql.codegen.maxFields` turns vanilla's whole-stage codegen off, which
 * is a different cliff (`PLAN_TASK_171.md` 2.2).
 *
 * Each rung writes what it is into the results file after its timed cases: vanilla's largest
 * generated method, whether that is past `HugeMethodLimit`, and how many entries Varka fused.
 * The x-axis's meaning is then in the same file as its timings. A rung whose Varka arm does
 * not fuse every entry, or falls back at run time, fails the run rather than timing a partly
 * per-row Varka arm.
 *
 * The two arms read the same Arrow cache - the vanilla arm through a column-to-row conversion
 * and whole-stage codegen, the Varka arm through its kernel - so they differ in the engine and
 * nothing else, as `VarkaThroughputBenchmark`'s baseline does.
 *
 * `VarkaSizeLadderTuningBenchmark` times vanilla Spark's own settings against the same rungs,
 * data and query, which it takes from here.
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh sql VarkaSizeLadderBenchmark
 * }}}
 */
object VarkaSizeLadderBenchmark extends SqlBasedBenchmark {
  import VarkaArrowSessions.{createSession, vanillaMethodBytes}
  import VarkaSizeLadder.{cacheDates, entry, numRows, rungs}

  /** How many entries the Varka arm fused, after checking that it ran as a kernel. */
  private def varkaFused(varka: SparkSession, query: String): Int = {
    val df = varka.sql(query)
    df.queryExecution.toRdd.count()
    val plan = df.queryExecution.executedPlan
    val options = VarkaColumnarToRowExec.emitOptions(varka.sessionState.conf.varkaEmitUseAVX)
    val (fused, batches) = plan.collectFirst {
      case v: VarkaProjectExec =>
        (VarkaExpressionCompiler.compilePartial(v.projectList, v.child.output, options),
          v.metrics("numVarkaBatches").value)
      case v: VarkaColumnarToRowExec =>
        (VarkaExpressionCompiler.compilePartial(v.projectList, v.child.output, options),
          v.metrics("numVarkaBatches").value)
    }.getOrElse(throw new IllegalStateException(
      s"the Varka arm did not fuse:\n${plan.treeString}"))
    require(batches > 0, s"the Varka arm fused but fell back at run time: $query")
    fused.map(_.specs.count(_.isInstanceOf[FusedOutput])).getOrElse(0)
  }

  private def note(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // The inherited session uses the default cache serializer; these arms own their
    // Arrow-backed sessions, as VarkaThroughputBenchmark's do.
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val baseline = createSession("VarkaSizeLadder-vanilla", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaSizeLadder-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    try {
      cacheDates(baseline)
      cacheDates(varka)
      runBenchmark("the size ladder: greatest(add_months(d, k), date_add(d, k), last_day(d))") {
        for (n <- rungs) {
          val query = s"SELECT ${(1 to n).map(entry).mkString(", ")} FROM ladder_dates"
          val bytes = vanillaMethodBytes(baseline, query)
          val fused = varkaFused(varka, query)
          require(fused == n, s"the Varka arm fused $fused of $n entries")
          val benchmark = new Benchmark(s"$n entries over $numRows Arrow-cached rows", numRows,
            minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
          benchmark.addCase("vanilla Spark (whole-stage codegen)") { _ =>
            baseline.sql(query).noop()
          }
          benchmark.addCase("Varka") { _ =>
            varka.sql(query).noop()
          }
          benchmark.run()
          // After the table rather than before it: the 128-bit companion file is cut from the
          // console from the first table on, and a note ahead of the first table was lost.
          note(s"rung $n: vanilla's largest generated method is $bytes bytes, " +
            (if (bytes > CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT) "past" else "under") +
            s" HugeMethodLimit ${CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT}; " +
            s"Varka fuses $fused of $n entries")
        }
      }
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
