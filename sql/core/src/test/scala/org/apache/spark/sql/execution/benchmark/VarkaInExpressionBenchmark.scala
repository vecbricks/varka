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
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.{VarkaColumnarRule, VarkaColumnarToRowExec, VarkaFilterColumnarToRowExec, VarkaFilterExec, VarkaProjectExec}
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}

/**
 * The columnar-terminal `IN` benchmark (task 20): Spark's own `InExpressionBenchmark` measures
 * `WHERE id IN (...)` as a row-source filter, where the milestone survey found `DateType` the
 * slowest primitive at short lists; this benchmark prices what Varka can fuse today - `IN` in
 * condition position, `CASE WHEN d IN (...) THEN ... ELSE ... END`, over Arrow-cached dates
 * with a columnar terminal - against the same Janino session, list sizes drawn from the stock
 * benchmark's ladder (5, 50, 200, 500) plus the fused cap (16).
 *
 * What the two files do and do not share: the stock committed results
 * (`InExpressionBenchmark-*-results.txt`) are upstream Azure EPYC runs (the JDK-25 file reads
 * 31.2 M rows/s at 5 dates decaying to 8.6 at 500; the milestone quoted the JDK-17 file's
 * 27.4 -> 8.3), while every number here is this machine's, so the same-run `baseline (Janino)`
 * cases are the real baseline and the stock file is a shape reference only. The fused case
 * also does strictly more work than the stock filter - it computes a branch per row - so the
 * two are not a like-for-like ratio; the anchor cases below tie the shapes together instead:
 *
 *  - Fused pair: `CASE WHEN d IN (<n>) ...` at n = 5 (arrives as `In`), n = 16 (the compiler
 *    cap, arrives as `InSet` past the optimizer's threshold of 10), guarded by
 *    [[requireFused]].
 *  - Above the cap: n = 50, guarded by [[requireDeclined]] and still measured - the
 *    no-regression proof that an over-cap list keeps stock performance on the varka session.
 *  - Anchor: the stock-shaped `SELECT COUNT(*) WHERE d IN (<n>)` filter at 5/50/200/500 on
 *    both sessions, tying this file's list shapes to the upstream benchmark's. Until task 21
 *    all four anchors were unfused by design (Varka rewrote projections only); the in-cap
 *    anchor now runs the mask kernel and is guarded fused, while 50/200/500 stay declined
 *    over the literal cap.
 *
 * Sessions, tables and methodology follow `VarkaThroughputBenchmark` (Arrow cache serializer,
 * session hygiene around `getOrCreate`, 2M rows with a null every 31, five iterations over
 * two-second windows).
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt
 *        "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaInExpressionBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain ..."
 *      Results will be written to "benchmarks/VarkaInExpressionBenchmark-jdk<NN>-results.txt".
 * }}}
 */
object VarkaInExpressionBenchmark extends SqlBasedBenchmark {

  private val numRows = 2000000

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
        // Steady state, checked to have run on the kernel before timing: a new shape's
        // first query must not wait on the row path for its kernel to compile.
        .config(SQLConf.VARKA_WARMUP_ENABLED.key, "false")
        .withExtensions(_.injectColumnar(_ => VarkaColumnarRule))
    }
    builder.getOrCreate()
  }

  private def cacheDates(session: SparkSession): Unit = {
    session.sql(
      s"""select case when id % 31 = 0 then null
        |       else date_add(date'2020-01-01', cast(id as int) % 1460) end as d,
        |       cast(id as int) as i
        |from range(0, $numRows)""".stripMargin)
      .createOrReplaceTempView("varka_dates")
    session.catalog.cacheTable("varka_dates")
    session.sql("select count(*) from varka_dates").collect()
  }

  /** `n` distinct date literals spread across the data's 4-year range, as SQL text. */
  private def literals(n: Int): String = (0 until n).map { k =>
    s"date'${java.time.LocalDate.of(2020, 1, 2).plusDays(k * 1460L / n)}'"
  }.mkString(", ")

  private def fusedQuery(n: Int): String =
    s"SELECT CASE WHEN d IN (${literals(n)}) THEN date_add(d, 1) ELSE d END AS a " +
      "FROM varka_dates"

  private def anchorQuery(n: Int): String =
    s"SELECT COUNT(*) AS c FROM varka_dates WHERE d IN (${literals(n)})"

  private def requireFused(varka: SparkSession, name: String, query: String): Unit = {
    val df = varka.sql(query)
    df.queryExecution.toRdd.count()
    val plan = df.queryExecution.executedPlan
    val node = plan.collectFirst {
      case v: VarkaProjectExec => v.metrics("numVarkaBatches")
      case v: VarkaColumnarToRowExec => v.metrics("numVarkaBatches")
      case v: VarkaFilterExec => v.metrics("numVarkaBatches")
      case v: VarkaFilterColumnarToRowExec => v.metrics("numVarkaBatches")
    }.getOrElse(throw new IllegalStateException(
      s"case '$name' did not fuse on the varka session:\n${plan.treeString}"))
    require(node.value > 0,
      s"case '$name' fused but fell back at run time (numVarkaBatches = ${node.value})")
  }

  /** The over-cap guard: the varka session must have declined, not silently fallen back. */
  private def requireDeclined(varka: SparkSession, name: String, query: String): Unit = {
    val plan = varka.sql(query).queryExecution.executedPlan
    val fused = plan.collectFirst {
      case v: VarkaProjectExec => v
      case v: VarkaColumnarToRowExec => v
      case v: VarkaFilterExec => v
      case v: VarkaFilterColumnarToRowExec => v
    }
    require(fused.isEmpty,
      s"case '$name' was expected to decline but fused:\n${plan.treeString}")
  }

  private def runPair(
      baseline: SparkSession,
      varka: SparkSession,
      name: String,
      query: String): Unit = {
    runBenchmark(name) {
      val benchmark = new Benchmark(s"$name over $numRows Arrow-cached rows", numRows,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      benchmark.addCase("baseline (Janino)") { _ =>
        baseline.sql(query).noop()
      }
      benchmark.addCase("varka (SIMD)") { _ =>
        varka.sql(query).noop()
      }
      benchmark.run()
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()

    val baseline = createSession("VarkaIn-baseline", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaIn-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    try {
      cacheDates(baseline)
      cacheDates(varka)

      for (n <- Seq(5, 16)) {
        requireFused(varka, s"case-when IN, $n literals", fusedQuery(n))
        runPair(baseline, varka, s"case-when IN, $n literals, fused", fusedQuery(n))
      }
      requireDeclined(varka, "case-when IN, 50 literals", fusedQuery(50))
      runPair(baseline, varka, "case-when IN, 50 literals, declined (over the cap)",
        fusedQuery(50))
      // Task 21 flipped the in-cap anchor: the filter itself now runs the mask kernel.
      requireFused(varka, "filter IN anchor, 5 literals", anchorQuery(5))
      runPair(baseline, varka, "filter IN anchor, 5 literals (fused since task 21)",
        anchorQuery(5))
      for (n <- Seq(50, 200, 500)) {
        requireDeclined(varka, s"filter IN anchor, $n literals", anchorQuery(n))
        runPair(baseline, varka, s"filter IN anchor, $n literals (unfused on both)",
          anchorQuery(n))
      }
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
