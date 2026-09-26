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
import org.apache.spark.sql.execution.{VarkaColumnarRule, VarkaColumnarToRowExec,
  VarkaFilterColumnarToRowExec, VarkaFilterExec, VarkaProjectExec}
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}

/**
 * What the long lane costs end to end, against the int lane and against the row engine
 * (milestone 5, the baseline task 29's `PLAN_TASK_29.md` 6.1.2 registered and could not score).
 *
 * Task 142 priced the lane at the kernel: a 64-bit lane costs 1.5x to 2.0x an int one in cache
 * and a flat 2.11x to 2.20x out of it, measured over memory segments with no Spark above them.
 * This is the same question one layer up, where the Arrow cache, the batch machinery and the
 * per-batch fixed costs are included and only some of them double with the lane. Every pair of
 * cases below is one shape at two widths - `i > i2` beside `l > l2`, and nothing else moved -
 * so the ratio of the two Varka rows is the lane's end-to-end price, and the ratio of each
 * Varka row to its baseline is what the engine is worth on that shape.
 *
 * Only comparisons, `greatest`/`least` and `CASE WHEN` appear, because that is what task 29
 * admits on the long lane: arithmetic over `bigint` is task 104, the `TIME` fields are 102 and
 * the interval arithmetic is 103. A `TIME` and a day-time interval case run beside the `bigint`
 * ones because all three share the lane and none of them shares a Catalyst expression, so a
 * lowering that was accidentally type-specific would show as one row out of line.
 *
 * Each Varka case asserts that its query actually fused before it is timed: a benchmark whose
 * subject silently fell back to the row engine would report the row engine twice and call the
 * ratio 1.0.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt "sql/Test/runMain
 *        org.apache.spark.sql.execution.benchmark.VarkaLongLaneThroughputBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/Test/runMain ..."
 *      Results land in
 *      "sql/core/benchmarks/VarkaLongLaneThroughputBenchmark-jdk<NN>-results.txt".
 * }}}
 */
object VarkaLongLaneThroughputBenchmark extends SqlBasedBenchmark {

  private val numRows = 2000000

  /**
   * The second scale, for the one question a single row count cannot answer. Task 142 found the
   * lane's kernel cost depends on where the working set sits: 1.5x to 2.0x while both arms are
   * in cache and a flat 2.11x to 2.20x once they are not. End to end the per-batch fixed costs
   * do not double with the lane, so the ratio should sit *below* the kernel's at a small scale
   * and climb toward it as the columns leave cache. This table carries only the four columns
   * the filter pairs read, so twenty million rows is about half a gigabyte rather than four.
   */
  private val bigRows = 20000000

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
      // `TIME` is gated by a flag whose default is "is this a test JVM"; a benchmark is not
      // one, so both sessions turn it on explicitly and measure the same feature set.
      .config("spark.sql.timeType.enabled", "true")
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

  /**
   * One cached table carrying the same values at both widths: `i`, `i2` as ints and `l`, `l2`
   * as the same numbers in `bigint`, so the two arms of a pair compare identical data and
   * differ only in the lane. `d`, `d2` are dates, which are the int lane's *value* leaves:
   * a bare `int` column is admitted as a comparison or arithmetic operand (task 122) and not
   * as a value, so `greatest(i, i2)` declines where `greatest(l, l2)` fuses, and the int-lane
   * twin of a long projection has to be the date columns. Same lane, same lane count, one
   * fewer byte of nothing - the pairing is about the width, not the Spark type.
   * `t`, `t2` are `TIME(6)` spread over the day and `dt`, `dt2` are day-time
   * intervals of both signs. Every 31st row is null in each column, the null pattern
   * the date benchmarks use, so the validity word is live rather than trivially absent.
   */
  private def cacheTable(session: SparkSession): Unit = {
    session.sql(
      s"""select
         |  case when id % 31 = 0 then null
         |       else date_add(date'2020-01-01', cast(id % 100000 as int)) end as d,
         |  case when id % 31 = 7 then null
         |       else date_add(date'2021-01-01', cast((id * 7) % 100000 as int)) end as d2,
         |  case when id % 31 = 0 then null else cast(id % 100000 as int) end as i,
         |  case when id % 31 = 7 then null else cast((id * 7) % 100000 as int) end as i2,
         |  case when id % 31 = 0 then null
         |       else cast(id % 100000 as bigint) + 5000000000L end as l,
         |  case when id % 31 = 7 then null
         |       else cast((id * 7) % 100000 as bigint) + 5000000000L end as l2,
         |  case when id % 31 = 0 then null
         |       else make_time(cast(id % 24 as int), cast((id * 7) % 60 as int),
         |                      cast((id * 13) % 60 as decimal(16, 6))) end as t,
         |  case when id % 31 = 7 then null
         |       else make_time(cast((id * 5) % 24 as int), cast((id * 11) % 60 as int),
         |                      cast((id * 17) % 60 as decimal(16, 6))) end as t2,
         |  case when id % 31 = 0 then null
         |       else make_dt_interval(0, 0, 0,
         |                             cast(id % 100000 as decimal(16, 6))) end as dt,
         |  case when id % 31 = 7 then null
         |       else make_dt_interval(0, 0, 0,
         |                             -cast((id * 7) % 100000 as decimal(16, 6))) end as dt2
         |from range(0, $numRows)""".stripMargin)
      .createOrReplaceTempView("varka_long_pairs")
    session.catalog.cacheTable("varka_long_pairs")
    // cacheTable is lazy, and the first case measured would otherwise pay for the whole cache.
    session.sql("select count(*) from varka_long_pairs").collect()
  }

  /**
   * The four node classes `VarkaSharedSessions.isVarkaNode` names, not a guess: a filter that
   * feeds a row consumer is a `VarkaFilterColumnarToRowExec`, and a check that looked only for
   * `VarkaFilterExec` would call a fused plan unfused.
   */
  /** `cacheTable`'s four-column twin at [[bigRows]], for the scale question. */
  private def cacheBigTable(session: SparkSession): Unit = {
    session.sql(
      s"""select
         |  case when id % 31 = 0 then null else cast(id % 100000 as int) end as i,
         |  case when id % 31 = 7 then null else cast((id * 7) % 100000 as int) end as i2,
         |  case when id % 31 = 0 then null
         |       else cast(id % 100000 as bigint) + 5000000000 end as l,
         |  case when id % 31 = 7 then null
         |       else cast((id * 7) % 100000 as bigint) + 5000000000 end as l2
         |from range(0, $bigRows)""".stripMargin)
      .createOrReplaceTempView("varka_long_pairs_big")
    session.catalog.cacheTable("varka_long_pairs_big")
    session.sql("select count(*) from varka_long_pairs_big").collect()
  }

  private def isVarkaNode(session: SparkSession, query: String): Boolean =
    session.sql(query).queryExecution.executedPlan.find {
      case _: VarkaColumnarToRowExec | _: VarkaProjectExec
          | _: VarkaFilterExec | _: VarkaFilterColumnarToRowExec => true
      case _ => false
    }.isDefined

  private def requireFused(session: SparkSession, name: String, query: String): Unit = {
    if (!isVarkaNode(session, query)) {
      throw new IllegalStateException(
        s"$name did not fuse, so this case would time the row engine twice: $query\n" +
          session.sql(query).queryExecution.executedPlan.treeString)
    }
  }

  /**
   * One shape at both widths: four rows, the two baselines and the two Varka arms, in one
   * table so the reader can take any ratio without crossing a JVM. `intQuery` and `longQuery`
   * must be the same shape over the same values.
   */
  private def runPair(
      baseline: SparkSession,
      varka: SparkSession,
      name: String,
      intQuery: String,
      longQuery: String,
      rows: Int = numRows): Unit = {
    requireFused(varka, s"$name (int lane)", intQuery)
    requireFused(varka, s"$name (long lane)", longQuery)
    runBenchmark(name) {
      val benchmark = new Benchmark(s"$name over $rows Arrow-cached rows", rows,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      benchmark.addCase("int32 lane, baseline (Janino)") { _ => baseline.sql(intQuery).noop() }
      benchmark.addCase("int32 lane, varka (SIMD)") { _ => varka.sql(intQuery).noop() }
      benchmark.addCase("int64 lane, baseline (Janino)") { _ => baseline.sql(longQuery).noop() }
      benchmark.addCase("int64 lane, varka (SIMD)") { _ => varka.sql(longQuery).noop() }
      benchmark.run()
    }
  }

  /** A long-lane shape with no int twin - the `TIME` and interval columns. */
  private def runSingle(
      baseline: SparkSession,
      varka: SparkSession,
      name: String,
      query: String): Unit = {
    requireFused(varka, name, query)
    runBenchmark(name) {
      val benchmark = new Benchmark(s"$name over $numRows Arrow-cached rows", numRows,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      benchmark.addCase("baseline (Janino)") { _ => baseline.sql(query).noop() }
      benchmark.addCase("varka (SIMD)") { _ => varka.sql(query).noop() }
      benchmark.run()
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val baseline = createSession("varka-long-baseline", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("varka-long-fused", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    try {
      cacheTable(baseline)
      cacheTable(varka)

      // The pair PLAN_TASK_29.md 6.1.2 predicts: a comparison filter, one column against
      // another, at both widths. The predicted band is 0.45x to 0.60x of the int arm.
      runPair(baseline, varka, "filter, column against column",
        "SELECT i FROM varka_long_pairs WHERE i > i2",
        "SELECT l FROM varka_long_pairs WHERE l > l2")
      runPair(baseline, varka, "filter, column against literal",
        "SELECT i FROM varka_long_pairs WHERE i > 50000",
        "SELECT l FROM varka_long_pairs WHERE l > 5000050000")
      runPair(baseline, varka, "filter, two conjuncts and a null check",
        "SELECT i FROM varka_long_pairs WHERE i > i2 AND i2 IS NOT NULL",
        "SELECT l FROM varka_long_pairs WHERE l > l2 AND l2 IS NOT NULL")
      // The int-lane arm of a projection pair is the date columns, for the reason `cacheTable`
      // gives: a bare int column is not a value leaf at the int lane.
      runPair(baseline, varka, "projection, greatest",
        "SELECT greatest(d, d2) AS a FROM varka_long_pairs",
        "SELECT greatest(l, l2) AS a FROM varka_long_pairs")
      runPair(baseline, varka, "projection, CASE WHEN over a comparison",
        "SELECT CASE WHEN d < d2 THEN d ELSE d2 END AS a FROM varka_long_pairs",
        "SELECT CASE WHEN l < l2 THEN l ELSE l2 END AS a FROM varka_long_pairs")

      // The other two types on the same lane, which share no Catalyst expression with bigint.
      runSingle(baseline, varka, "filter, TIME comparison",
        "SELECT t FROM varka_long_pairs WHERE t < t2")
      runSingle(baseline, varka, "projection, greatest over TIME",
        "SELECT greatest(t, t2) AS a FROM varka_long_pairs")
      runSingle(baseline, varka, "filter, day-time interval comparison",
        "SELECT dt FROM varka_long_pairs WHERE dt > dt2")
      runSingle(baseline, varka, "projection, least over day-time intervals",
        "SELECT least(dt, dt2) AS a FROM varka_long_pairs")

      // The same two filters ten times larger, which is the only way to tell a lane cost
      // from a per-batch fixed cost: the kernel's share of the work grows with the rows
      // and the fixed costs do not.
      cacheBigTable(baseline)
      cacheBigTable(varka)
      runPair(baseline, varka, s"filter, column against column, $bigRows rows",
        "SELECT i FROM varka_long_pairs_big WHERE i > i2",
        "SELECT l FROM varka_long_pairs_big WHERE l > l2", bigRows)
      runPair(baseline, varka, s"filter, column against literal, $bigRows rows",
        "SELECT i FROM varka_long_pairs_big WHERE i > 50000",
        "SELECT l FROM varka_long_pairs_big WHERE l > 5000050000", bigRows)

      // Which half of a filter costs the lane: the comparison, or the surviving column's
      // compaction? The four cases cross them. A Varka filter compacts every forwarded column
      // to the selected rows, and the vectorised `compress` path serves four-byte vectors only
      // (`VarkaKernelEvaluator`, the finding `PLAN_TASK_29.md` 2 recorded as task 128's), so an
      // eight-byte column takes a per-row copy. If the cost follows the *output* column rather
      // than the compared one, that is the compaction and not the lane.
      runBenchmark(s"filter: where the lane's cost is, $bigRows rows") {
        val benchmark = new Benchmark(
          s"compare and output crossed over $bigRows Arrow-cached rows", bigRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val cases = Seq(
          "compare int32, output int32" ->
            "SELECT i FROM varka_long_pairs_big WHERE i > 50000",
          "compare int64, output int64" ->
            "SELECT l FROM varka_long_pairs_big WHERE l > 5000050000",
          "compare int64, output int32" ->
            "SELECT i FROM varka_long_pairs_big WHERE l > 5000050000",
          "compare int32, output int64" ->
            "SELECT l FROM varka_long_pairs_big WHERE i > 50000",
          "compare int64, output none (count)" ->
            "SELECT count(*) FROM varka_long_pairs_big WHERE l > 5000050000",
          "compare int32, output none (count)" ->
            "SELECT count(*) FROM varka_long_pairs_big WHERE i > 50000")
        cases.foreach { case (name, query) =>
          requireFused(varka, name, query)
          benchmark.addCase(s"$name, varka (SIMD)") { _ => varka.sql(query).noop() }
        }
        benchmark.run()
      }
    } finally {
      baseline.stop()
      varka.stop()
    }
  }
}
