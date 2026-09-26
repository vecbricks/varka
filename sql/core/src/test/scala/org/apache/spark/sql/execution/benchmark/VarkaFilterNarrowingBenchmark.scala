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
 * Whether a Varka filter keeps the columnar path when it narrows its output (task 145).
 *
 * `VarkaFilterColumnarToRowExec` carries `narrowing`: the projection above it, absorbed, and
 * `Some` exactly when the node's output differs from the columns it was given. Task 144's
 * crossed experiment found queries differing only in that clause running eight times apart
 * under a `noop` sink, and this file was written to price the narrowing. It prices something
 * else, which is why it is committed: with the row read-back forced, narrowing costs nothing -
 * `toRdd` puts the narrowed and un-narrowed shapes within 1% of each other - and task 78's
 * `VarkaNarrowingBenchmark` had already measured that shape at three selectivities and both
 * widths, finding the narrowed form slightly *faster* than the two-column control.
 *
 * What the gap actually is: under a columnar sink the un-narrowed shapes stay columnar end to
 * end while the narrowed one does not, so it pays a read-back the others avoid - task 19's
 * floor, arriving through a plan difference rather than through the lane or the column count.
 * The `toRdd` arms are the control that says so, and they are the reason this file exists
 * beside task 78's rather than repeating it.
 *
 * The cases vary one thing at a time over one cached table:
 *
 *   A  one column, filtered and output       - no narrowing
 *   B  two columns, the other one output     - narrowing
 *   C  two columns, both output              - no narrowing, two columns out
 *   D  two columns, both in the predicate    - narrowing
 *   E  one column, no output at all          - an aggregate above the filter, a third path
 *   F  A at about one per cent selectivity
 *   G  B at about one per cent selectivity
 *   and A, B, C again through `toRdd`, which forces the row path for all three
 *
 * To run this benchmark:
 * {{{
 *   SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/Test/runMain
 *     org.apache.spark.sql.execution.benchmark.VarkaFilterNarrowingBenchmark"
 * }}}
 */
object VarkaFilterNarrowingBenchmark extends SqlBasedBenchmark {

  private val numRows = 20000000

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val varka = SparkSession.builder()
      .master("local[1]")
      .appName("varka-filter-narrowing")
      .config(UI_ENABLED.key, false)
      .config(SQLConf.SHUFFLE_PARTITIONS.key, 1)
      .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
      .config(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
        classOf[ArrowCachedBatchSerializer].getName)
      .config(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
      .config(SQLConf.VARKA_ENABLED.key, "true")
      // Steady state, checked to have run on the kernel before timing: a new shape's
      // first query must not wait on the row path for its kernel to compile.
      .config(SQLConf.VARKA_WARMUP_ENABLED.key, "false")
      .withExtensions(_.injectColumnar(_ => VarkaColumnarRule))
      .getOrCreate()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    try {
      varka.sql(
        s"""select cast(id % 100000 as int) as i,
           |       cast((id * 7) % 100000 as int) as i2
           |from range(0, $numRows)""".stripMargin)
        .createOrReplaceTempView("varka_narrowing")
      varka.catalog.cacheTable("varka_narrowing")
      varka.sql("select count(*) from varka_narrowing").collect()

      val cases = Seq(
        "A one column, filtered and output (no narrowing)" ->
          "SELECT i FROM varka_narrowing WHERE i > 50000",
        "B two columns, the other output (narrowing)" ->
          "SELECT i2 FROM varka_narrowing WHERE i > 50000",
        "C two columns, both output (no narrowing)" ->
          "SELECT i, i2 FROM varka_narrowing WHERE i > 50000",
        "D two columns, both in the predicate (narrowing)" ->
          "SELECT i FROM varka_narrowing WHERE i > 50000 AND i2 >= 0",
        "E one column, no output (count)" ->
          "SELECT count(*) FROM varka_narrowing WHERE i > 50000",
        "F A at about 1% selectivity" ->
          "SELECT i FROM varka_narrowing WHERE i > 99000",
        "G B at about 1% selectivity" ->
          "SELECT i2 FROM varka_narrowing WHERE i > 99000")

      // The control that decides what the gap above is: `toRdd` forces the row read-back for
      // every shape, so a difference that survives it is the narrowing's and one that does not
      // is the columnar path's.
      val rowCases = Seq(
        "A through toRdd (row path forced)" -> "SELECT i FROM varka_narrowing WHERE i > 50000",
        "B through toRdd (row path forced)" -> "SELECT i2 FROM varka_narrowing WHERE i > 50000",
        "C through toRdd (row path forced)" ->
          "SELECT i, i2 FROM varka_narrowing WHERE i > 50000")

      cases.foreach { case (name, query) =>
        val fused = varka.sql(query).queryExecution.executedPlan.find {
          case _: org.apache.spark.sql.execution.VarkaColumnarToRowExec
              | _: org.apache.spark.sql.execution.VarkaProjectExec
              | _: org.apache.spark.sql.execution.VarkaFilterExec
              | _: org.apache.spark.sql.execution.VarkaFilterColumnarToRowExec => true
          case _ => false
        }.isDefined
        if (!fused) {
          throw new IllegalStateException(s"$name did not fuse: $query")
        }
      }

      runBenchmark("a filter that narrows its output") {
        val benchmark = new Benchmark(s"over $numRows Arrow-cached rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        cases.foreach { case (name, query) =>
          benchmark.addCase(name) { _ => varka.sql(query).noop() }
        }
        rowCases.foreach { case (name, query) =>
          benchmark.addCase(name) { _ => varka.sql(query).queryExecution.toRdd.count() }
        }
        benchmark.run()
      }
    } finally {
      varka.stop()
    }
  }
}
