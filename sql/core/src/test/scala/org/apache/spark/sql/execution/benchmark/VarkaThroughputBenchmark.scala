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
import org.apache.spark.sql.execution.VarkaColumnarRule
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}

/**
 * End-to-end throughput benchmark (Task 7): rows/sec for `date_add` / `date_sub` / `datediff`
 * over ~2M Arrow-cached date rows with Varka on (SIMD kernels, [[VarkaColumnarRule]] fused into
 * [[org.apache.spark.sql.execution.VarkaColumnarToRowExec]]) vs the standard Janino row path, plus
 * a mixed projection that is not Varka-eligible (fallback) to show the non-fused path has no
 * regression.
 *
 * The cache serializer is resolved process-wide on first use, so this benchmark manages its own
 * sessions (two Arrow-backed sessions on a shared context: a baseline without the Varka rule and
 * a fused varka session) and stops the inherited `SqlBasedBenchmark` session first. The active
 * and default session are cleared around each `getOrCreate`, without which the second call
 * returns the first session with the Varka config applied to it - two names for one session, and
 * a "baseline" that is not one.
 *
 * Every case writes to `noop`, and `noop` accepts columnar batches, so the varka cases hand the
 * kernels' own Arrow batches to the sink through
 * [[org.apache.spark.sql.execution.VarkaProjectExec]] - no columnar-to-row conversion is inside
 * the measurement. The baseline writes rows, as it must, and so does the mixed projection on both
 * sides: it is not Varka-eligible, so nothing about it changes.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt
 *        "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaThroughputBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain ..."
 *      Results will be written to "benchmarks/VarkaThroughputBenchmark-jdk<NN>-results.txt".
 * }}}
 */
object VarkaThroughputBenchmark extends SqlBasedBenchmark {

  private val numRows = 2000000 // 2M Arrow-cached date rows

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

  private def cacheDates(session: SparkSession): Unit = {
    session.sql(
      """select case when id % 31 = 0 then null
        |       else date_add(date'2020-01-01', cast(id as int) % 1460) end as d,
        |       cast(id as int) as i
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_dates")
    session.catalog.cacheTable("varka_dates")
    // `cacheTable` is lazy, and the first case measured would otherwise pay for building the
    // whole 2M-row Arrow cache.
    session.sql("select count(*) from varka_dates").collect()
  }

  private def cacheDatePairs(session: SparkSession): Unit = {
    session.sql(
      """select date_add(date'2020-01-01', cast(id as int) % 1500) as d,
        |       date_add(date'2021-01-01', cast(id as int) % 1500) as d2,
        |       cast(id as int) as i
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_date_pairs")
    session.catalog.cacheTable("varka_date_pairs")
    session.sql("select count(*) from varka_date_pairs").collect()
  }

  private def runQueries(
      baseline: SparkSession,
      varka: SparkSession,
      name: String,
      query: String): Unit = {
    runBenchmark(name) {
      val benchmark = new Benchmark(s"$name over $numRows Arrow-cached rows", numRows,
        minNumIters = 2, warmupTime = 1.seconds, minTime = 1.seconds, output = output)
      benchmark.addCase("baseline (Janino)", numIters = 3) { _ =>
        baseline.sql(query).noop()
      }
      benchmark.addCase("varka (SIMD)", numIters = 3) { _ =>
        varka.sql(query).noop()
      }
      benchmark.run()
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // The inherited base session uses the default serializer; these benchmarks own their
    // Arrow-backed sessions (see the class javadoc).
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()

    val baseline = createSession("VarkaThroughputTrace-baseline", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaThroughputTrace-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    try {
      cacheDates(baseline)
      cacheDates(varka)
      cacheDatePairs(baseline)
      cacheDatePairs(varka)

      runQueries(baseline, varka, "date_add", "SELECT date_add(d, 3) AS a FROM varka_dates")
      runQueries(baseline, varka, "date_sub", "SELECT date_sub(d, 5) AS a FROM varka_dates")
      runQueries(baseline, varka, "datediff",
        "SELECT datediff(d2, d) AS diff FROM varka_date_pairs")
      runQueries(baseline, varka, "mixed projection (fallback)",
        "SELECT date_add(d, 3) AS a, i, i + 1 AS inc FROM varka_dates")
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
