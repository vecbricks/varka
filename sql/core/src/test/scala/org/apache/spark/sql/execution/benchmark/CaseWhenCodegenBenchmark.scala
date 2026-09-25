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

import scala.util.control.NonFatal

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.internal.config.Tests.IS_TESTING
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator
import org.apache.spark.sql.execution.WholeStageCodegenExec
import org.apache.spark.sql.internal.SQLConf

/**
 * A `CASE WHEN` of many branches under each of Spark's three evaluation paths: inside a
 * whole-stage codegen stage, outside one, and interpreted.
 *
 * The three paths meet the JVM's method limits differently. Inside a stage the branches are not
 * split into methods, so the stage's per-row method passes the 8000 bytes HotSpot compiles
 * (`-XX:+DontCompileHugeMethods`) and runs interpreted, and past 64 KB it fails to compile and
 * the stage falls back to its row-by-row operators. Outside a stage the expression's code is
 * split into methods of `spark.sql.codegen.methodSplitThreshold` characters, which leaves one
 * call per method in the caller, and with enough branches the caller itself passes 8000 bytes.
 * Interpreted, `CaseWhen.eval` indexes its branches by position. Each rung prints the size of
 * the stage's largest method beside the timings, so a step can be read against the limit that
 * caused it.
 *
 * To run this benchmark:
 * {{{
 *   1. without sbt:
 *      bin/spark-submit --class <this class> --jars <spark core test jar> <spark sql test jar>
 *   2. build/sbt "sql/Test/runMain <this class>"
 *   3. generate result:
 *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/Test/runMain <this class>"
 *      Results will be written to "benchmarks/CaseWhenCodegenBenchmark-results.txt".
 * }}}
 *
 * `BenchmarkBase.main` sets `spark.testing`, and under that flag a stage that fails to compile
 * is an error rather than a fallback to the row-by-row operators. The fallback is what this
 * benchmark measures at 1000 branches, so it clears the flag. Under sbt the flag is also the
 * environment variable `SPARK_TESTING`, which cannot be cleared from inside the JVM, so the
 * 1000-branch rung throws there; a full ladder is run the first way. The rungs and the row
 * count can be given as arguments.
 */
object CaseWhenCodegenBenchmark extends SqlBasedBenchmark {

  private def query(branches: Int, rows: Long): DataFrame = {
    val whens = (1 to branches).map(k => s"WHEN v = $k THEN v * $k").mkString(" ")
    spark.sql(
      s"SELECT CASE $whens ELSE 0 END AS c FROM (SELECT id % $branches AS v FROM range($rows))")
  }

  /** The stage's largest method in bytes from Spark's own compile of it, or why it has none. */
  private def stageMethodSize(df: DataFrame): String = {
    val stages = df.queryExecution.executedPlan.collect { case w: WholeStageCodegenExec => w }
    if (stages.isEmpty) {
      "no stage"
    } else {
      stages.map { stage =>
        try {
          val (_, stats) = CodeGenerator.compile(stage.doCodeGen()._2)
          s"${stats.maxMethodCodeSize} bytes"
        } catch {
          case NonFatal(_) => "fails to compile, past 64 KB"
        }
      }.mkString(", ")
    }
  }

  private def caseWhenLadder(branches: Int, rows: Long): Unit = {
    val benchmark = new Benchmark(s"CASE WHEN of $branches branches", rows, output = output)
    // scalastyle:off println
    benchmark.out.println(s"largest method of the stage: ${stageMethodSize(query(branches, rows))}")
    // scalastyle:on println

    benchmark.addCase("whole-stage codegen on", numIters = 5) { _ =>
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
        query(branches, rows).noop()
      }
    }
    benchmark.addCase("whole-stage codegen off", numIters = 5) { _ =>
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        query(branches, rows).noop()
      }
    }
    benchmark.addCase("interpreted, factoryMode=NO_CODEGEN", numIters = 2) { _ =>
      withSQLConf(SQLConf.CODEGEN_FACTORY_MODE.key -> "NO_CODEGEN") {
        query(branches, rows).noop()
      }
    }
    benchmark.run()
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // Production behaviour past 64 KB is the fallback, not the error `spark.testing` makes of it.
    System.clearProperty(IS_TESTING.key)
    val rungs = if (mainArgs.length > 0) mainArgs(0).split(",").map(_.trim.toInt).toSeq
      else Seq(30, 60, 100, 300, 1000)
    val rows = if (mainArgs.length > 1) mainArgs(1).toLong else 200000L
    rungs.foreach { branches =>
      runBenchmark(s"CASE WHEN of $branches branches over $rows rows") {
        caseWhenLadder(branches, rows)
      }
    }
  }
}
