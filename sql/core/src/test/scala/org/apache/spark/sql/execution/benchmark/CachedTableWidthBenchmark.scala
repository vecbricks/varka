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

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.functions.{col, sum}
import org.apache.spark.sql.internal.SQLConf

/**
 * One column summed over a cached table, at 100 and at 101 columns.
 *
 * `InMemoryTableScanExec` produces columnar batches only when the whole cached schema is within
 * `spark.sql.codegen.maxFields` (100 by default), whatever the query reads. So a query that sums
 * one column of a 101-column cached table is served row by row, while the same query over a
 * 100-column table, or over the 101-column table with the limit raised, is served in batches.
 * Each rung prints whether the scan is columnar beside the timings.
 *
 * To run this benchmark:
 * {{{
 *   1. without sbt:
 *      bin/spark-submit --class <this class> --jars <spark core test jar> <spark sql test jar>
 *   2. build/sbt "sql/Test/runMain <this class>"
 *   3. generate result:
 *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/Test/runMain <this class>"
 *      Results will be written to "benchmarks/CachedTableWidthBenchmark-results.txt".
 * }}}
 */
object CachedTableWidthBenchmark extends SqlBasedBenchmark with AdaptiveSparkPlanHelper {

  /** A cached table of `columns` int columns, materialized. */
  private def cachedTable(columns: Int, rows: Long): DataFrame = {
    val df = spark.range(0, rows, 1, 1)
      .select((1 to columns).map(k => (col("id") + k).cast("int").as(s"c$k")): _*)
      .cache()
    df.count()
    df
  }

  private def sumOfFirstColumn(table: DataFrame): DataFrame = table.agg(sum(col("c1")))

  private def scanIsColumnar(df: DataFrame): Boolean =
    collect(df.queryExecution.executedPlan) { case s: InMemoryTableScanExec => s }
      .exists(_.supportsColumnar)

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val rows = if (mainArgs.length > 0) mainArgs(0).toLong else 2000000L
    val maxFields = SQLConf.get.getConf(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS)
    runBenchmark(s"SUM of one column over a cached table of $rows rows") {
      val tables = Map(maxFields -> cachedTable(maxFields, rows),
        maxFields + 1 -> cachedTable(maxFields + 1, rows))
      // (case name, columns of the cached table, maxFields while the query runs)
      val cases = Seq(
        (s"$maxFields columns", maxFields, maxFields),
        (s"${maxFields + 1} columns", maxFields + 1, maxFields),
        (s"${maxFields + 1} columns, maxFields raised to ${maxFields + 1}",
          maxFields + 1, maxFields + 1))
      val benchmark = new Benchmark("sum(c1) over a cached table", rows, output = output)
      cases.foreach { case (name, columns, limit) =>
        withSQLConf(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key -> limit.toString) {
          val columnar = scanIsColumnar(sumOfFirstColumn(tables(columns)))
          val scan = if (columnar) "columnar" else "row-based"
          // scalastyle:off println
          benchmark.out.println(s"$name: the scan is $scan")
          // scalastyle:on println
        }
        benchmark.addCase(name, numIters = 5) { _ =>
          withSQLConf(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key -> limit.toString) {
            sumOfFirstColumn(tables(columns)).noop()
          }
        }
      }
      benchmark.run()
      tables.values.foreach(_.unpersist(blocking = true))
    }
  }
}
