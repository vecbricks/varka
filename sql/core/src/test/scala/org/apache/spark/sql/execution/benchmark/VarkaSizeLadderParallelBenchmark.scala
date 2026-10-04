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
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator

/**
 * The size ladder on every core (VARKA-197): [[VarkaSizeLadderBenchmark]]'s rungs, data and
 * query, with both sessions on `local[*]` instead of `local[1]`, to ask whether the gap the
 * one-core ladder measures survives parallelism.
 *
 * Why it might not. Vanilla Spark's consume method, past `HugeMethodLimit`, runs interpreted,
 * and an interpreter costs the same on every core, so its time divides by the cores. Varka's
 * kernels read the Arrow cache and write one column per entry at a rate the cores together may
 * saturate: at a hundred entries a kernel writes four hundred bytes a row, and what one core
 * did at a few gigabytes a second, four cores may find bound by the machine rather than by the
 * kernel. Where that happens the ratio between the arms shrinks, and by how much is the
 * measurement.
 *
 * The cached table comes in as many partitions as the session has cores, since `range` follows
 * the default parallelism, and that is what puts a task on every core; each rung's note records
 * the core count and the partition count beside vanilla's method size, so a reader knows what
 * "every core" was on the machine that wrote the file. Run in one dispatch with the one-core
 * ladder, so both files come from the same machine.
 *
 * Its own class and files rather than a switch on the ladder, so the one-core file stays what
 * it is and the two are read side by side (`VARKA-197.md`).
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh sql VarkaSizeLadderParallelBenchmark
 * }}}
 */
object VarkaSizeLadderParallelBenchmark extends SqlBasedBenchmark {
  import VarkaArrowSessions.{createSession, vanillaMethodBytes}
  import VarkaSizeLadder.{cacheDates, entry, numRows, rungs, varkaFused}

  private def note(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // The inherited session uses the default cache serializer; these arms own their
    // Arrow-backed sessions, as the one-core ladder's do. Both join one SparkContext, so the
    // master of the first is the master of both.
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val baseline =
      createSession("VarkaSizeLadderParallel-vanilla", varkaEnabled = false, master = "local[*]")
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka =
      createSession("VarkaSizeLadderParallel-varka", varkaEnabled = true, master = "local[*]")
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    val cores = baseline.sparkContext.defaultParallelism
    try {
      cacheDates(baseline)
      cacheDates(varka)
      val partitions = baseline.table("ladder_dates").rdd.getNumPartitions
      runBenchmark(s"the size ladder on local[*], $cores cores: " +
          "greatest(add_months(d, k), date_add(d, k), last_day(d))") {
        for (n <- rungs) {
          val query = s"SELECT ${(1 to n).map(entry).mkString(", ")} FROM ladder_dates"
          val bytes = vanillaMethodBytes(baseline, query)
          val fused = varkaFused(varka, query)
          require(fused == n, s"the Varka arm fused $fused of $n entries")
          val benchmark = new Benchmark(
            s"$n entries over $numRows Arrow-cached rows on $cores cores", numRows,
            minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
          benchmark.addCase("vanilla Spark (whole-stage codegen)") { _ =>
            baseline.sql(query).noop()
          }
          benchmark.addCase("Varka") { _ =>
            varka.sql(query).noop()
          }
          benchmark.run()
          note(s"rung $n: vanilla's largest generated method is $bytes bytes, " +
            (if (bytes > CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT) "past" else "under") +
            s" HugeMethodLimit ${CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT}; " +
            s"Varka fuses $fused of $n entries; $cores cores, $partitions cached partitions")
        }
      }
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
