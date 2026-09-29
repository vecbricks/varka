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
import org.apache.spark.sql.execution.VarkaQ3Ranges

/**
 * TPC-DS `modified-q3`'s filter on vanilla Spark and on Varka: an Arrow-cached int column of date
 * keys filtered by the first n of the query's 200 `between` ranges joined by `or`, time per row
 * against n. Vanilla generates the whole chain into the scan's `processNext`, which grows with the
 * ranges and, past HotSpot's 8000-byte limit, is never compiled; the census found this the one
 * stage of TPC-DS and TPC-H that crosses it (`PLAN_TASK_193.md`).
 *
 * Varka compiles the ranges to one range set, evaluated by a loop over a table of bounds, so it
 * fuses the filter at every rung and its kernel stays the same size whatever the number of
 * ranges. Written as the tree of comparisons the query spells, the chain fit Varka's byte budget
 * only up to 48 ranges, which is why the rungs straddle 48 and 49; they reach the query's 200,
 * past vanilla's own crossing, which the notes locate (`PLAN_TASK_172.md`).
 *
 * A third arm times the general answer to the same limit, `splitConditions`: range sets off, so
 * the ranges reach Varka as the comparisons the query writes, and the filter split across
 * several selection outputs, each within the budget, instead of declined (`PLAN_TASK_172.md`
 * 3.1). It is what any disjunction too large for one method gets, not only one of ranges.
 *
 * Each rung writes, after its table, vanilla's largest generated method, whether it is past
 * `HugeMethodLimit`, whether Varka ran the filter as a kernel, and how many rows pass. A rung whose
 * Varka arm does not run the filter as a kernel fails the run rather than timing an arm that is
 * not what its name says.
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh sql VarkaRangeFilterBenchmark
 * }}}
 */
object VarkaRangeFilterBenchmark extends SqlBasedBenchmark {
  import VarkaArrowSessions.{cacheRangeKeys, createSession, splitConditionsDesign, vanillaMethodBytes,
    varkaFilters, withEmitOptions}

  private val numRows = 2000000

  /** Straddling Varka's boundary (48 and 49) and vanilla's crossing. */
  private val rungs = Seq(10, 48, 49, 100, 150, 200)

  private def note(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // The inherited session uses the default cache serializer; these arms own their
    // Arrow-backed sessions, as the size ladder's do.
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val baseline = createSession("VarkaRangeFilter-vanilla", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaRangeFilter-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    val limit = CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT
    try {
      cacheRangeKeys(baseline, numRows)
      cacheRangeKeys(varka, numRows)
      runBenchmark("modified-q3's filter: n date ranges joined by or") {
        for (n <- rungs) {
          val query = "SELECT ss_sold_date_sk FROM range_keys WHERE " +
            VarkaQ3Ranges.predicate(n, "ss_sold_date_sk")
          val bytes = vanillaMethodBytes(baseline, query)
          val (fused, plan) = varkaFilters(varka, query)
          require(fused, s"at $n ranges the Varka arm declined the filter:\n$plan")
          val (split, splitPlan) =
            withEmitOptions(splitConditionsDesign)(varkaFilters(varka, query))
          require(split, s"at $n ranges the split arm declined the filter:\n$splitPlan")
          val selected = baseline.sql(query).count()
          val benchmark = new Benchmark(s"$n ranges over $numRows Arrow-cached rows", numRows,
            minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
          benchmark.addCase("vanilla Spark (whole-stage codegen)") { _ =>
            baseline.sql(query).noop()
          }
          benchmark.addCase("Varka") { _ =>
            varka.sql(query).noop()
          }
          benchmark.addCase("Varka, split conditions") { _ =>
            withEmitOptions(splitConditionsDesign)(varka.sql(query).noop())
          }
          benchmark.run()
          note(s"rung $n: vanilla's largest generated method is $bytes bytes, " +
            (if (bytes > limit) "past" else "under") + s" HugeMethodLimit $limit; both Varka " +
            s"arms run the filter as a kernel; $selected of $numRows rows pass")
        }
      }
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
