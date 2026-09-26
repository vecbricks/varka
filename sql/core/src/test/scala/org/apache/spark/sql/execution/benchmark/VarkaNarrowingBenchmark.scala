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
import org.apache.spark.sql.execution.{VarkaColumnarRule, VarkaFilterColumnarToRowExec}
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}

/**
 * Task 78's measurement: what a two-column predicate under a one-column consumer costs, which
 * was the one shape in milestone 4's debt register where Varka ran slower than stock Spark.
 *
 * It is a file of its own rather than rungs added to [[VarkaFilterBenchmark]], for two reasons.
 * That file's fixture has a single date column and every predicate in it reads one column, so
 * the shape priced here cannot be written against it without adding a column to the fixture -
 * which would move every number that file has committed, for a question it was not asked. And
 * the question here is not a filter's, it is a plan's: the same predicate and the same kernel
 * throughout, with only the *consumer's* column set changing.
 *
 * '''The three shapes, and what each isolates.''' Spark's column pruning has already run by the
 * time any columnar rule sees the plan, so a projection survives above a filter only when the
 * predicate reads more columns than the consumer wants:
 *
 *  - `SELECT d ... WHERE d < d2` - the losing shape. Two columns in the predicate, one wanted.
 *    Before task 78 a Janino `Project [d]` sat above the row-producing Varka filter, and the
 *    node converted `d2` to a row as well, whose value that projection discarded.
 *  - `SELECT d, d2 ... WHERE d < d2` - the same predicate and the same selectivity with nothing
 *    to narrow, so the projection is redundant and Spark removed it long before this rule ran.
 *    This is the control: it never had a residual projection to absorb, so it must not move.
 *  - `SELECT COUNT(*) ... WHERE d < d2` - the predicate with nothing crossing the read-back
 *    floor at all, which is what the kernel alone is worth on this data.
 *
 * The difference between the first two rows is the width of the row that crosses the floor;
 * the distance from either to its Janino baseline is what the plan shape is worth. Read the
 * `Relative` column against the baseline case in each block, not across blocks: the three
 * shapes do different amounts of work by construction.
 *
 * Selectivity is the ladder because the floor's cost is per '''selected''' row while the
 * kernel's advantage is per '''input''' row, so the two move in opposite directions and a
 * single rung would say whatever its rung said.
 *
 * The rung is set by the '''sign''' of `d2 - d`, one day either way, rather than by shifting
 * `d2` around the data's own cycle. The cycling version was written first and was wrong in a
 * way worth recording: with `d2` the cycle-shift of `d` by `s` days, `d < d2` holds for
 * `(cycle - s) / cycle` of the rows - the inverse of the shift, not the shift - so the ladder
 * ran backwards, and its top rung, `s = cycle`, made `d2` equal to `d` on every row and
 * selected nothing at all while being labelled 100%. It measured a real plan over real data
 * either way, which is exactly why nothing failed; what it did not measure is what its labels
 * said. [[requireSelectivity]] is here so that cannot happen twice - the rung asserts its own
 * fraction against the engine before anything is timed.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt
 *        "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaNarrowingBenchmark"
 *   2. generate result:
 *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *        "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaNarrowingBenchmark"
 *      Results will be written to "benchmarks/VarkaNarrowingBenchmark-jdk<NN>-results.txt".
 * }}}
 */
object VarkaNarrowingBenchmark extends SqlBasedBenchmark {

  private val numRows = 2000000

  /** The day cycle the fixture repeats over; the shift below is read against it. */
  private val cycle = 1460

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

  /**
   * Two date columns over the same cycle. `d2` is one day after `d` on `pct` percent of the
   * rows and one day before it on the rest, so `d < d2` selects exactly `pct` percent of the
   * rows where both are non-null, whatever the cycle does. Nulls every 31st row in `d` and
   * every 37th in `d2`, on different periods so the two null sets do not coincide - a WHERE
   * drops them on both engines, which is SQL's own rule and not a Varka behaviour.
   */
  private def cacheDates(session: SparkSession, pct: Int): Unit = {
    session.sql(
      s"""select d, case when i % 37 = 0 then null
        |            when i % 100 < $pct then date_add(d, 1)
        |            else date_sub(d, 1) end as d2, i
        |from (select case when id % 31 = 0 then null
        |             else date_add(date'2020-01-01', cast(id as int) % $cycle) end as d,
        |             cast(id as int) as i
        |      from range(0, $numRows))""".stripMargin)
      .createOrReplaceTempView("varka_pairs")
    session.catalog.cacheTable("varka_pairs")
    session.sql("select count(*) from varka_pairs").collect()
  }

  /**
   * The rung asserts the selectivity its name claims, before anything is timed. A benchmark
   * whose rungs are mislabelled still measures a real plan over real data, so nothing fails
   * and nothing looks wrong - it just answers a question other than the one on the label, and
   * the label is what gets quoted. The first version of this file did exactly that.
   */
  private def requireSelectivity(session: SparkSession, name: String, pct: Int): Unit = {
    val selected = session.sql(counted).collect()(0).getLong(0).toDouble
    val eligible = session.sql(
      "SELECT COUNT(*) FROM varka_pairs WHERE d IS NOT NULL AND d2 IS NOT NULL")
      .collect()(0).getLong(0).toDouble
    val actual = 100.0 * selected / eligible
    require(math.abs(actual - pct) < 1.0,
      f"rung '$name' selects $actual%.1f%% of the non-null rows, not $pct%%")
  }

  /** name -> the percentage of non-null rows `d < d2` selects, asserted per rung. */
  private val ladder = Seq(
    "10% selected" -> 10,
    "70% selected" -> 70,
    "100% selected" -> 100)

  private def narrowed = "SELECT d FROM varka_pairs WHERE d < d2"
  private def unnarrowed = "SELECT d, d2 FROM varka_pairs WHERE d < d2"
  private def counted = "SELECT COUNT(*) AS c FROM varka_pairs WHERE d < d2"

  /**
   * Fails the run rather than quietly pricing a plan nobody meant to measure: the varka side
   * must plan the row-out filter and actually run its kernel, and - for the narrowed shape -
   * must have absorbed the projection, which is the thing being measured. A benchmark that
   * silently prices the unfused plan is the failure mode `sql/varka/AGENTS.md` calls a ghost
   * fallback, and it is worth more here than usual because the absorbed and unabsorbed plans
   * differ by one node and answer identically.
   */
  private def requireShape(varka: SparkSession, name: String, query: String,
      absorbed: Boolean): Unit = {
    val df = varka.sql(query)
    df.queryExecution.toRdd.count()
    val plan = df.queryExecution.executedPlan
    val node = plan.collectFirst { case v: VarkaFilterColumnarToRowExec => v }
      .getOrElse(throw new IllegalStateException(
        s"case '$name' did not plan a row-out Varka filter:\n${plan.treeString}"))
    require(node.metrics("numVarkaBatches").value > 0,
      s"case '$name' fused but fell back at run time")
    require(node.narrowing.isDefined == absorbed,
      s"case '$name' expected narrowing absorbed = $absorbed:\n${plan.treeString}")
  }

  private def runPair(
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

    val baseline = createSession("VarkaNarrowing-baseline", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = createSession("VarkaNarrowing-varka", varkaEnabled = true)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    require(baseline ne varka, "the two sessions must be distinct or there is no baseline")
    try {
      for ((name, pct) <- ladder) {
        cacheDates(baseline, pct)
        cacheDates(varka, pct)
        try {
          requireSelectivity(baseline, name, pct)
          requireShape(varka, s"narrowed, $name", narrowed, absorbed = true)
          requireShape(varka, s"unnarrowed, $name", unnarrowed, absorbed = false)
          runPair(baseline, varka, s"SELECT d WHERE d < d2 (narrowed), $name", narrowed)
          runPair(baseline, varka, s"SELECT d, d2 WHERE d < d2 (control), $name", unnarrowed)
          runPair(baseline, varka, s"COUNT(*) WHERE d < d2 (no read-back), $name", counted)
        } finally {
          Seq(baseline, varka).foreach(_.catalog.uncacheTable("varka_pairs"))
        }
      }
    } finally {
      Seq(baseline, varka).foreach(_.stop())
    }
  }
}
