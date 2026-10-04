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

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeAndComment, CodeGenerator}
import org.apache.spark.sql.execution.{SparkPlan, WholeStageCodegenExec}
import org.apache.spark.sql.execution.adaptive.{AdaptiveSparkPlanExec, QueryStageExec}
import org.apache.spark.sql.internal.SQLConf

/**
 * How long a whole-stage codegen stage new to the JVM runs before its code is fast, under the
 * default settings (`VARKA-233.md` 10.1). HotSpot compiles a hot method with C1 at once and
 * queues it for C2, and a stage runs C1's profiled code until C2's arrives; for a wide stage that
 * wait can be many seconds, and every executor JVM pays it again for every stage it compiles.
 *
 * Two families of shapes a query can have with nothing raised: projections of 25 to 99 cheap
 * entries `id + k`, under `spark.sql.codegen.maxFields` so that they are in a stage, and aggregates
 * of 10 to 60 `sum`s grouped by a key of ten values. Each shape's source is new to the JVM, so its
 * class is generated, compiled and JIT-compiled from cold. Every query of one shape runs back to
 * back for a fixed time with whole-stage codegen on, and again with it off, and the benchmark
 * prints each query's milliseconds, the stage's largest method in bytes, and the time to steady
 * state: the time before the first five queries in a row whose median is within 10% of the median
 * of the last ten.
 *
 * Not a `Benchmark` table: what it measures is a time series, and a table of best and average
 * times would hide the wait it exists to show. Run it under `-XX:+PrintCompilation` to see when
 * C2's compile of each stage method finished. Varka plays no part.
 *
 * To run this benchmark:
 * {{{
 *   1. without sbt:
 *      bin/spark-submit --class <this class> --jars <spark core test jar> <spark sql test jar>
 *   2. build/sbt "sql/Test/runMain <this class>"
 *   3. generate result:
 *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/Test/runMain <this class>"
 *      Results will be written to "benchmarks/CodegenCompileWaitBenchmark-results.txt".
 * }}}
 */
object CodegenCompileWaitBenchmark extends SqlBasedBenchmark {

  private val numRows = 1000000L
  private val seconds = sys.props.get("codegen.wait.seconds").map(_.toInt).getOrElse(45)

  /** A fresh offset per shape and arm, so that no two compile to the same class. */
  private var nextOffset = 1000

  private def freshOffset(): Int = { nextOffset += 1000; nextOffset }

  private def projection(n: Int, offset: Int): DataFrame =
    spark.sql(s"select ${(1 to n).map(k => s"id + ${k + offset} AS c$k").mkString(", ")} " +
      "from rows")

  private def aggregate(n: Int, offset: Int): DataFrame =
    spark.sql(s"select g, ${(1 to n).map(k => s"sum(id + ${k + offset}) AS s$k").mkString(", ")} " +
      "from rows group by g")

  /** Every whole-stage codegen stage of a plan, through adaptive query stages if it ran. */
  private def stagesOf(plan: SparkPlan): Seq[WholeStageCodegenExec] = plan match {
    case a: AdaptiveSparkPlanExec => stagesOf(a.executedPlan)
    case q: QueryStageExec => stagesOf(q.plan)
    case w: WholeStageCodegenExec => w +: w.children.flatMap(stagesOf)
    case p => p.children.flatMap(stagesOf)
  }

  /** The largest method of each of `df`'s stages, in bytes. */
  private def stageMethodSizes(df: DataFrame): String = {
    val stages = stagesOf(df.queryExecution.executedPlan)
    if (stages.isEmpty) return "no stage"
    stages.map { s =>
      try {
        val (_, stats) = CodeGenerator.compile(s.doCodeGen()._2: CodeAndComment)
        s"${stats.maxMethodCodeSize}"
      } catch {
        case NonFatal(e) => s"fails to compile (${e.getClass.getSimpleName})"
      }
    }.mkString(", ") + " bytes"
  }

  private def note(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  private def upperMedian(xs: Seq[Long]): Long = { val sorted = xs.sorted; sorted(sorted.size / 2) }

  /** Runs `query` back to back for `seconds` and reports what each query took. */
  private def series(label: String, query: () => DataFrame, wholeStage: Boolean): Unit = {
    val times = mutable.ArrayBuffer.empty[Long]
    var sizes = ""
    withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> wholeStage.toString) {
      val start = System.nanoTime()
      while (System.nanoTime() - start < seconds * 1000000000L) {
        val t = System.nanoTime()
        query().noop()
        times += (System.nanoTime() - t) / 1000000
      }
      // `noop()` runs the query through a write command with a query execution of its own, so
      // `last`'s plan never ran and an adaptive plan has no stages yet. The same query planned
      // with adaptive execution off has the same stage code; reading it after the series warms
      // nothing, since the timed classes are already compiled.
      if (wholeStage) {
        sizes = withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false") {
          stageMethodSizes(query())
        }
      }
    }
    // The median of the last ten queries is the steady state, and the wait is the time before
    // the first five queries in a row whose median is within 10% of it. The last slow query would
    // be a simpler mark, but one slow query late in a series, a collection most likely, puts it
    // late in every series on a runner.
    val median = upperMedian(times.takeRight(10).toSeq)
    val settled = (0 to times.size - 5).find { i =>
      upperMedian(times.slice(i, i + 5).toSeq) <= median * 1.1
    }
    val steady = settled.map(i => times.take(i).sum).getOrElse(times.sum)
    note(s"$label, whole-stage codegen ${if (wholeStage) "on" else "off"}" +
      (if (wholeStage) s", largest stage method: $sizes" else "") +
      s"; steady after $steady ms at a median of $median ms over ${times.size} queries")
    note(s"  ms per query: ${times.mkString(" ")}")
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.range(0, numRows).selectExpr("id", "id % 10 AS g").createOrReplaceTempView("source")
    spark.sql("cache table rows as select * from source")
    spark.table("rows").noop()
    // Spark's own code paths compile on the first queries; a small shape runs unrecorded first.
    Seq(true, false).foreach { on =>
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> on.toString) {
        (1 to 5).foreach { _ => projection(5, 1).noop(); aggregate(5, 1).noop() }
      }
    }
    runBenchmark(s"a new projection of n entries id + k, ${seconds}s back to back, $numRows rows") {
      Seq(25, 50, 75, 99).foreach { n =>
        series(s"$n entries", { val o = freshOffset(); () => projection(n, o) }, true)
        series(s"$n entries", { val o = freshOffset(); () => projection(n, o) }, false)
      }
    }
    runBenchmark(s"a new aggregate of n sums by a key of ten values, ${seconds}s back to back") {
      Seq(10, 20, 40, 60).foreach { n =>
        series(s"$n sums", { val o = freshOffset(); () => aggregate(n, o) }, true)
        series(s"$n sums", { val o = freshOffset(); () => aggregate(n, o) }, false)
      }
    }
  }
}
