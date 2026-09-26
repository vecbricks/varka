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
 * The filter benchmark (task 21): the survey's `d_date BETWEEN`-style shape - here `d < DATE`,
 * the same paired-comparison lowering with one leg - over Arrow-cached dates, at a selectivity
 * ladder from none-selected to all-selected, against the same Janino session. This is the
 * committed measurement behind the milestone's open question 2 (the selected-batch contract):
 * each selectivity runs in both consumer variants, so the two halves of the v1 contract are
 * priced separately -
 *
 *  - '''columnar terminal''' (`noop`, which accepts batches): the varka side is
 *    [[VarkaFilterExec]] alone - the mask kernel plus the scalar compaction into a fresh
 *    batch, the copy `compress(mask)` (milestone 4 item 11) would replace. Compaction cost
 *    grows with selected rows, so the high-selectivity rungs are its worst case.
 *  - '''row consumer''' (`toRdd`): the varka side is [[VarkaFilterColumnarToRowExec]] - the
 *    mask kernel plus the bitmap-guided row skip, no compaction at all. The dominant
 *    WHERE-plus-aggregate shape consumes this way.
 *
 * Two stacked cases price the filter-feeds-projection contract (the compacted batch keeps the
 * Arrow invariant, so [[VarkaProjectExec]] runs its kernels right on top), and two COUNT(*)
 * cases tie the ladder to the survey's end-to-end shape.
 *
 * Sessions, tables and methodology follow `VarkaThroughputBenchmark` (Arrow cache serializer,
 * session hygiene around `getOrCreate`, 2M rows with a null every 31 - the nulls are dropped
 * by every predicate here, on both engines, which is SQL's WHERE - five iterations over
 * two-second windows). The fused guards run through `toRdd` like the IN benchmark's, which
 * proves the row-variant plan fused; the columnar variant of the same predicate fuses by the
 * same rule match.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt
 *        "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaFilterBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain ..."
 *      Results will be written to "benchmarks/VarkaFilterBenchmark-jdk<NN>-results.txt".
 * }}}
 */
object VarkaFilterBenchmark extends SqlBasedBenchmark {

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

  /**
   * The ladder: name -> the `d < DATE` cutoff as days from 2020-01-01. The data cycles over
   * 1460 days, so the fraction of non-null rows selected is `days / 1460`; "all" uses a
   * cutoff past the range. The ~15% rung sits on the design note's compaction threshold.
   */
  private val ladder = Seq(
    ("0% selected" -> 0),
    ("1% selected" -> 15),
    ("15% selected" -> 219),
    ("50% selected" -> 730),
    ("85% selected" -> 1241),
    ("100% selected" -> 1500))

  private def cutoff(days: Int): String =
    s"date'${java.time.LocalDate.of(2020, 1, 1).plusDays(days)}'"

  /**
   * The rung's predicate. The 0% rung cannot be an always-false range (`d < <data minimum>`):
   * the in-memory scan's min/max stat pruning would drop every batch before the filter node
   * saw one, and the rung would price the pruning, not the mask. An interval containing no
   * whole day selects nothing while each conjunct stays satisfiable against the stats, so
   * the kernel actually runs - at the cost of one extra compare against the other rungs,
   * which is the honest price of an unprunable empty predicate.
   */
  private def predicate(days: Int): String =
    if (days == 0) "d > date'2020-01-05' AND d < date'2020-01-06'"
    else s"d < ${cutoff(days)}"

  private def filterQuery(days: Int): String =
    s"SELECT d FROM varka_dates WHERE ${predicate(days)}"

  private def stackedQuery(days: Int): String =
    s"SELECT date_add(d, 7) AS a FROM varka_dates WHERE ${predicate(days)}"

  private def countQuery(days: Int): String =
    s"SELECT COUNT(*) AS c FROM varka_dates WHERE ${predicate(days)}"

  private def requireFused(varka: SparkSession, name: String, query: String): Unit = {
    val df = varka.sql(query)
    df.queryExecution.toRdd.count()
    val plan = df.queryExecution.executedPlan
    val node = plan.collectFirst {
      case v: VarkaFilterExec => v.metrics("numVarkaBatches")
      case v: VarkaFilterColumnarToRowExec => v.metrics("numVarkaBatches")
      case v: VarkaProjectExec => v.metrics("numVarkaBatches")
      case v: VarkaColumnarToRowExec => v.metrics("numVarkaBatches")
    }.getOrElse(throw new IllegalStateException(
      s"case '$name' did not fuse on the varka session:\n${plan.treeString}"))
    require(node.value > 0,
      s"case '$name' fused but fell back at run time (numVarkaBatches = ${node.value})")
  }

  private def runColumnarPair(
      baseline: SparkSession, varka: SparkSession, name: String, query: String): Unit = {
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

  private def runRowPair(
      baseline: SparkSession, varka: SparkSession, name: String, query: String): Unit = {
    runBenchmark(name) {
      val benchmark = new Benchmark(s"$name over $numRows Arrow-cached rows", numRows,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      benchmark.addCase("baseline (Janino)") { _ =>
        baseline.sql(query).queryExecution.toRdd.count()
      }
      benchmark.addCase("varka (SIMD)") { _ =>
        varka.sql(query).queryExecution.toRdd.count()
      }
      benchmark.run()
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()

    val baseline = createSession("VarkaFilter-baseline", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaFilter-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    try {
      cacheDates(baseline)
      cacheDates(varka)

      for ((name, days) <- ladder) {
        requireFused(varka, s"filter, $name", filterQuery(days))
        runColumnarPair(baseline, varka, s"filter to batches (compacting), $name",
          filterQuery(days))
        runRowPair(baseline, varka, s"filter to rows (mask skip), $name", filterQuery(days))
      }
      for (days <- Seq(219, 1241)) {
        val name = if (days == 219) "15% selected" else "85% selected"
        requireFused(varka, s"stacked, $name", stackedQuery(days))
        runColumnarPair(baseline, varka, s"filter then fused projection, $name",
          stackedQuery(days))
        runRowPair(baseline, varka, s"COUNT(*) over the filter, $name", countQuery(days))
      }
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
