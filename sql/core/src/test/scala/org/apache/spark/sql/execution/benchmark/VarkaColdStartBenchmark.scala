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
import org.apache.spark.internal.config.UI.UI_ENABLED
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.execution.{VarkaColumnarRule, VarkaColumnarToRowExec}
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}

/**
 * Cold-query latency benchmark (Task 14): the first execution of a fresh plan shape, Janino
 * projection compile vs Varka kernel emission, at query level. `VarkaCodegenBenchmark` measures
 * the class-generation gap in isolation (hundreds of times); this benchmark asks what that gap
 * is worth for a whole query, where the scan and the framework also spend time.
 *
 * The harness is built not to lie (`PLAN_TASK_14.md` 2.4 and section 6):
 *
 *  - Every iteration projects a chain over a *different* column of a cached wide table, with
 *    iteration-distinct literals, so each iteration is a fresh source shape to Janino's global
 *    compile cache and a fresh emission for Varka. The two sides use disjoint column ranges
 *    because that Janino cache is process-wide, not per-session.
 *  - There is *no warmup* (`warmupTime = 0`): warmup iterations would consume the fresh shapes
 *    and every timed iteration would measure a cache hit. `numIters` pins one execution per
 *    pre-built query.
 *  - Each side runs one untimed guard query first (again on its own reserved column). That
 *    warms the shared scan shape and the execution framework on both sides equally, and on the
 *    varka side its `numVarkaBatches` metric is asserted positive - so the timed varka numbers
 *    cannot silently measure the Janino fallback.
 *  - Query planning is forced outside the timer (`executedPlan` in the setup) and the timed
 *    action is `toRdd.count()` on that same query execution - a `noop` write would re-plan a
 *    fresh write command inside the timer. The timed region is therefore execution only:
 *    Janino compile or kernel emission, then the ~100K-row compute, which is small enough that
 *    compilation dominates the first run. `toRdd` is the row consumer, so the varka side also
 *    pays its per-row read-back (task 12's known cost); at this row count that is well under
 *    the compile-time gap being measured.
 *
 * The table is 100K rows by 24 date columns: 10 timed shapes per side, one guard column per
 * side, and the remainder headroom. Times are per query (best and average over the 10 fresh
 * shapes); the rate column is rows per unit time and is not the point here.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt
 *        "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaColdStartBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain ..."
 *      Results will be written to "benchmarks/VarkaColdStartBenchmark-jdk<NN>-results.txt".
 * }}}
 */
object VarkaColdStartBenchmark extends SqlBasedBenchmark {

  private val numRows = 100000
  private val numCols = 24
  private val shapesPerSide = 10
  // Disjoint column ranges: baseline times c0..c9, varka times c10..c19, guards use c20/c21.
  private val baselineCols = 0 until shapesPerSide
  private val varkaCols = shapesPerSide until 2 * shapesPerSide
  private val baselineGuardCol = 2 * shapesPerSide
  private val varkaGuardCol = 2 * shapesPerSide + 1

  private def createSession(appName: String, varkaEnabled: Boolean): SparkSession = {
    val builder = SparkSession.builder()
      .master("local[1]")
      .appName(appName)
      .config(UI_ENABLED.key, false)
      .config(SQLConf.SHUFFLE_PARTITIONS.key, 1)
      .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
      .config(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
        classOf[ArrowCachedBatchSerializer].getName)
      .config(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
    if (varkaEnabled) {
      builder
        .config(SQLConf.VARKA_ENABLED.key, "true")
        .withExtensions(_.injectColumnar(_ => VarkaColumnarRule))
    }
    builder.getOrCreate()
  }

  private def cacheWideTable(session: SparkSession): Unit = {
    val columns = (0 until numCols).map { j =>
      s"date_add(date'2020-01-01', cast((id + $j * 37) % 1460 as int)) as c$j"
    }
    session.sql(s"select ${columns.mkString(", ")} from range(0, $numRows)")
      .createOrReplaceTempView("varka_wide")
    session.catalog.cacheTable("varka_wide")
    session.sql("select count(*) from varka_wide").collect()
  }

  /** The fresh plan shape for column `col`: a nested chain, literals distinct per column. */
  private def query(col: Int): String = {
    s"SELECT datediff(date_add(c$col, ${col + 1}), date_sub(c$col, ${col + 2})) AS x " +
      "FROM varka_wide"
  }

  /** Pre-builds and pre-plans the query for each column, leaving only execution to the timer. */
  private def prebuild(session: SparkSession, cols: Seq[Int]): IndexedSeq[DataFrame] = {
    cols.toIndexedSeq.map { col =>
      val df = session.sql(query(col))
      df.queryExecution.executedPlan // force analysis, optimization and physical planning
      df
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()

    val baseline = createSession("VarkaColdStart-baseline", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaColdStart-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    try {
      cacheWideTable(baseline)
      cacheWideTable(varka)

      // The untimed guards: warm the shared scan shape and framework on both sides, and prove
      // the varka side actually runs the kernels before any of its numbers are recorded.
      baseline.sql(query(baselineGuardCol)).queryExecution.toRdd.count()
      val guard = varka.sql(query(varkaGuardCol))
      guard.queryExecution.toRdd.count()
      val guardNode = guard.queryExecution.executedPlan
        .collectFirst { case v: VarkaColumnarToRowExec => v }
        .getOrElse(throw new IllegalStateException(
          s"expected a fused VarkaColumnarToRowExec:\n" +
            guard.queryExecution.executedPlan.treeString))
      val guardBatches = guardNode.metrics("numVarkaBatches").value
      require(guardBatches > 0,
        s"the guard query must run the kernels, got numVarkaBatches = $guardBatches")

      val baselineQueries = prebuild(baseline, baselineCols)
      val varkaQueries = prebuild(varka, varkaCols)

      runBenchmark("cold start: first execution of a fresh plan shape") {
        // warmupTime = 0 on purpose: see the class doc. Each timer iteration executes its own
        // pre-planned query exactly once, so every measurement is a genuinely cold shape.
        val benchmark = new Benchmark(
          s"first execution over $numRows Arrow-cached rows", numRows,
          minNumIters = shapesPerSide, warmupTime = 0.seconds, minTime = 0.seconds,
          output = output)
        benchmark.addTimerCase("baseline (Janino compile)", numIters = shapesPerSide) { timer =>
          if (timer.iteration >= 0) {
            val qe = baselineQueries(timer.iteration).queryExecution
            timer.startTiming()
            qe.toRdd.count()
            timer.stopTiming()
          }
        }
        benchmark.addTimerCase("varka (kernel emission)", numIters = shapesPerSide) { timer =>
          if (timer.iteration >= 0) {
            val qe = varkaQueries(timer.iteration).queryExecution
            timer.startTiming()
            qe.toRdd.count()
            timer.stopTiming()
          }
        }
        benchmark.run()
      }
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
