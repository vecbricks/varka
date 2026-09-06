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
import org.apache.spark.sql.execution.{VarkaColumnarRule, VarkaColumnarToRowExec, VarkaProjectExec}
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
 * The `runQueries` cases write to `noop`, and `noop` accepts columnar batches, so their varka
 * sides hand the kernels' own Arrow batches to the sink through
 * [[org.apache.spark.sql.execution.VarkaProjectExec]] - no columnar-to-row conversion is inside
 * the measurement. That includes the mixed projection, Varka-eligible since task 12 (one fused
 * entry, one forwarded, one residual). The `runRowQueries` cases force the row path instead
 * (`toRdd`), measuring [[org.apache.spark.sql.execution.VarkaColumnarToRowExec]]'s batch
 * assembly plus the read back to rows - the number behind task 12's escape-hatch decision
 * (assemble-then-read vs merge-at-row, `PLAN_TASK_12.md` section 2.3).
 *
 * Task 14 added the milestone-2 fusion cases (nested chains, the shared subchain that DAG-CSE
 * serves, `CASE WHEN` on predictable and pseudo-random data, `dayofweek`) and the chain-depth
 * scaling pairs on both consumers, and moved every case to the committed-run methodology of
 * `PLAN_TASK_14.md` 2.1: five iterations minimum over two-second warmup and measurement windows,
 * replacing the single-run 2x1s settings whose day-to-day swing the debt register recorded.
 * The two `CASE WHEN` tables differ only in data: over `varka_date_pairs` the condition is
 * constant (`d2 - d` is a fixed 366 days), so a per-row branch predicts perfectly and the case
 * prices pure fusion; over `varka_date_pairs_rand` the condition flips pseudo-randomly, adding
 * the branch-free win. The gap between the two committed relatives is the misprediction cost.
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

  /** Task 42's table: the year, month and day of the dates `cacheDates` builds, as three ints. */
  private def cacheDateParts(session: SparkSession): Unit = {
    session.sql(
      """select year(d) as y, month(d) as m, day(d) as dd
        |from (select date_add(date'2020-01-01', cast(id as int) % 1460) as d
        |      from range(0, 2000000))""".stripMargin)
      .createOrReplaceTempView("varka_date_parts")
    session.catalog.cacheTable("varka_date_parts")
    session.sql("select count(*) from varka_date_parts").collect()
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

  /**
   * Like `varka_date_pairs` but with pseudo-random day offsets, so `d < d2` flips irregularly
   * row to row. On `varka_date_pairs` that comparison is constant (`d2 - d` is a fixed 366
   * days) and a per-row branch predicts perfectly; this table is the one where branchiness
   * costs, which is what the blend-based `CASE WHEN` case needs to show separately.
   */
  private def cacheRandomDatePairs(session: SparkSession): Unit = {
    session.sql(
      """select date_add(date'2020-01-01', pmod(hash(id), 1500)) as d,
        |       date_add(date'2020-01-01', pmod(hash(id + 7), 1500)) as d2,
        |       cast(id as int) as i
        |from range(0, 2000000)""".stripMargin)
      .createOrReplaceTempView("varka_date_pairs_rand")
    session.catalog.cacheTable("varka_date_pairs_rand")
    session.sql("select count(*) from varka_date_pairs_rand").collect()
  }

  /**
   * An alternating `date_add`/`date_sub` chain of the given depth over column `d`, every
   * literal distinct, so neither Catalyst constant-folding nor C2 reassociation can shorten
   * it - each depth really is `depth` dependent ops per row.
   */
  private def chainExpr(depth: Int): String = {
    (0 until depth).foldLeft("d") { (expr, k) =>
      if (k % 2 == 0) s"date_add($expr, ${k + 1})" else s"date_sub($expr, ${k + 1})"
    }
  }

  /**
   * Every varka-side case must actually run the kernels: a query the compiler declines would
   * run the stock plan, and one the emitter rejects at run time would take the ghost fallback -
   * either way the committed "varka" number would measure nothing. So this executes the query
   * once (row consumer; the kernels are the same on both consumers) and asserts the plan fused
   * *and* `numVarkaBatches` counted, before any timing starts.
   */
  private def requireFused(varka: SparkSession, name: String, query: String): Unit = {
    val df = varka.sql(query)
    df.queryExecution.toRdd.count()
    val plan = df.queryExecution.executedPlan
    val node = plan.collectFirst {
      case v: VarkaProjectExec => v.metrics("numVarkaBatches")
      case v: VarkaColumnarToRowExec => v.metrics("numVarkaBatches")
    }.getOrElse(throw new IllegalStateException(
      s"case '$name' did not fuse on the varka session:\n${plan.treeString}"))
    require(node.value > 0,
      s"case '$name' fused but fell back at run time (numVarkaBatches = ${node.value})")
  }

  private def runQueries(
      baseline: SparkSession,
      varka: SparkSession,
      name: String,
      query: String): Unit = {
    requireFused(varka, name, query)
    runBenchmark(name) {
      // The committed-run methodology of PLAN_TASK_14.md 2.1: at least five measured iterations
      // over two-second windows, so a committed number is a distribution, not a single draw.
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

  /**
   * Like [[runQueries]] but consuming rows (`toRdd` forces the row-output plan), so the varka
   * side runs `VarkaColumnarToRowExec`: kernels, batch assembly, then per-row read-back. The
   * assemble-then-read variant of the task 12 escape hatch is what this prices.
   */
  private def runRowQueries(
      baseline: SparkSession,
      varka: SparkSession,
      name: String,
      query: String): Unit = {
    requireFused(varka, name, query)
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
      cacheDateParts(baseline)
      cacheDateParts(varka)
      cacheRandomDatePairs(baseline)
      cacheRandomDatePairs(varka)

      runQueries(baseline, varka, "date_add", "SELECT date_add(d, 3) AS a FROM varka_dates")
      runQueries(baseline, varka, "date_sub", "SELECT date_sub(d, 5) AS a FROM varka_dates")
      // Task 56's pair: the same column-offset kernel with and without the evaluator's per-batch
      // bound check. `date_add(d, i)` (task 38) records no bound and is the control;
      // `d + CAST(i AS INTERVAL DAY)` compiles to the same node with a bound on `i`, because
      // Spark's cast throws past 106751991 days, so its varka row pays one vector compare pass
      // over the offset column before the kernel runs. The difference between the two varka rows
      // is the check's price on the cheapest shape that pays it.
      runQueries(baseline, varka, "date_add, column offset (task 56 control)",
        "SELECT date_add(d, i) AS a FROM varka_dates")
      runQueries(baseline, varka, "date + CAST(i AS INTERVAL DAY), bound checked (task 56)",
        "SELECT d + CAST(i AS INTERVAL DAY) AS a FROM varka_dates")
      // Task 42: a date built from three int columns, under the session's default (ANSI) mode.
      runQueries(baseline, varka, "make_date",
        "SELECT make_date(y, m, dd) AS a FROM varka_date_parts")
      runQueries(baseline, varka, "datediff",
        "SELECT datediff(d2, d) AS diff FROM varka_date_pairs")
      // The milestone-2 fusion cases (PLAN_TASK_14.md 2.2). The nested projection is the query
      // the milestone plan opens with - milestone 1's per-op kernels could not fuse it at all.
      runQueries(baseline, varka, "nested projection",
        "SELECT datediff(date_add(d, 1), d2) AS n FROM varka_date_pairs")
      // The interned subtree (`date_add(d, 1)`) is computed once per lane group across both
      // outputs by DAG-CSE; Janino's per-row subexpression elimination redoes it per row.
      runQueries(baseline, varka, "shared subchain (DAG-CSE)",
        "SELECT date_add(d, 1) AS a, datediff(date_add(d, 1), d2) AS b FROM varka_date_pairs")
      // The CASE WHEN pair: same query, two data patterns (see the class doc). The committed
      // headline is the unpredictable one; the predictable run prices pure fusion.
      runQueries(baseline, varka, "case when, predictable data",
        "SELECT CASE WHEN d < d2 THEN date_add(d, 7) ELSE date_sub(d2, 7) END AS c " +
          "FROM varka_date_pairs")
      runQueries(baseline, varka, "case when, unpredictable data",
        "SELECT CASE WHEN d < d2 THEN date_add(d, 7) ELSE date_sub(d2, 7) END AS c " +
          "FROM varka_date_pairs_rand")
      // The one case that replaces an allocating path (Janino's LocalDate round trip) rather
      // than just fusing arithmetic - the kernel-level 36x of PLAN_TASK_11.md at query level.
      runQueries(baseline, varka, "dayofweek", "SELECT dayofweek(d) AS dw FROM varka_dates")
      // Task 37: the ISO week by the Thursday rule, the widest single-field kernel.
      runQueries(baseline, varka, "weekofyear", "SELECT weekofyear(d) AS w FROM varka_dates")
      runQueries(baseline, varka, "yearofweek",
        "SELECT extract(YEAROFWEEK FROM d) AS y FROM varka_dates")
      runQueries(baseline, varka, "mixed projection (partial fusion)",
        "SELECT date_add(d, 3) AS a, i, i + 1 AS inc FROM varka_dates")
      // Chain-depth scaling (PLAN_TASK_14.md 2.3): the fused loop pays one load and one store
      // whatever the depth; Janino pays per-row per-op overhead. Columnar consumer here, the
      // same chains through the row consumer below - their crossing is the break-even depth
      // milestone 3's fuse-profitability item needs.
      Seq(1, 2, 4, 8).foreach { depth =>
        runQueries(baseline, varka, s"chain depth $depth",
          s"SELECT ${chainExpr(depth)} AS a FROM varka_dates")
      }
      Seq(1, 2, 4, 8).foreach { depth =>
        runRowQueries(baseline, varka, s"chain depth $depth, row consumer",
          s"SELECT ${chainExpr(depth)} AS a FROM varka_dates")
      }
      // The all-fused control for the row-consumer pair below: how much of their gap is the
      // per-row read-back this node always pays, as opposed to the merge itself.
      runRowQueries(baseline, varka, "date_add, row consumer",
        "SELECT date_add(d, 3) AS a FROM varka_dates")
      runRowQueries(baseline, varka, "mixed projection, row consumer",
        "SELECT date_add(d, 3) AS a, i, i + 1 AS inc FROM varka_dates")
      // Residual-heavy: the shape where merge-at-row would win if the extra materialisation
      // of assemble-then-read costs anything worth building it for.
      runRowQueries(baseline, varka, "residual-heavy projection, row consumer",
        "SELECT date_add(d, 3) AS a, i + 1 AS r1, i + 2 AS r2, i + 3 AS r3, i + 4 AS r4 " +
          "FROM varka_dates")
      // The heavy-op row twins (task 19): every row-consumer case above fuses only cheap
      // adds, where the ~6 ns/row read-back is most likely to dominate - deciding the
      // profitability rule on them alone would decide it on the worst case. These four reuse
      // their columnar cases' SQL verbatim, so each pair differs only in the consumer.
      runRowQueries(baseline, varka, "dayofweek, row consumer",
        "SELECT dayofweek(d) AS dw FROM varka_dates")
      runRowQueries(baseline, varka, "case when unpredictable, row consumer",
        "SELECT CASE WHEN d < d2 THEN date_add(d, 7) ELSE date_sub(d2, 7) END AS c " +
          "FROM varka_date_pairs_rand")
      runRowQueries(baseline, varka, "datediff, row consumer",
        "SELECT datediff(d2, d) AS diff FROM varka_date_pairs")
      runRowQueries(baseline, varka, "nested projection, row consumer",
        "SELECT datediff(date_add(d, 1), d2) AS n FROM varka_date_pairs")
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
