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
import org.apache.spark.sql.catalyst.expressions.CodegenObjectFactoryMode
import org.apache.spark.sql.internal.SQLConf

/**
 * What a projection costs once its generated code has failed to compile and Spark evaluates it
 * with the interpreter instead (`PLAN_TASK_233.md` 12).
 *
 * A projection outside a stage whose class passes the JVM's 64 KB limit logs
 * `Expr codegen error and falling back to interpreter mode` and still answers; the stock Spark
 * script `sql/varka/demo/silent-giveups/fallback.scala` shows it. The interpreter it falls back
 * to is the object `spark.sql.codegen.factoryMode=NO_CODEGEN` builds, so this benchmark prices
 * the fallback by building it directly rather than by provoking the compile failure, which test
 * runs turn into an error. For 100, 300 and 1000 entries `x + k` over a nullable column, the
 * shape the script needs past 64 KB, both arms run outside a stage (`wholeStage=false`): the
 * projection compiled, as it is when it fits, and the same projection interpreted.
 *
 * To run this benchmark:
 * {{{
 *   1. without sbt:
 *      bin/spark-submit --class <this class> --jars <spark core test jar> <spark sql test jar>
 *   2. build/sbt "sql/Test/runMain <this class>"
 *   3. generate result:
 *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/Test/runMain <this class>"
 *      Results will be written to "benchmarks/CodegenInterpreterFallbackBenchmark-results.txt".
 * }}}
 */
object CodegenInterpreterFallbackBenchmark extends SqlBasedBenchmark {

  private val numRows = 200000L

  private def cacheTable(): Unit = {
    // Every seventh value null, so that each entry carries its null check, as in the script.
    spark.sql(s"select if(id % 7 = 0, null, id) AS x from range(0, $numRows)")
      .createOrReplaceTempView("rows")
    spark.sql("cache table rows")
    spark.table("rows").noop()
  }

  private def projection(n: Int): Unit =
    spark.sql(s"select ${(1 to n).map(k => s"x + $k AS c$k").mkString(", ")} from rows").noop()

  private def rung(n: Int): Unit = {
    val benchmark = new Benchmark(s"$n entries x + k over $numRows cached rows, outside a stage",
      numRows, output = output)
    benchmark.addCase("compiled", numIters = 5) { _ =>
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        projection(n)
      }
    }
    benchmark.addCase("interpreted, what a failed compile falls back to", numIters = 5) { _ =>
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false",
          SQLConf.CODEGEN_FACTORY_MODE.key -> CodegenObjectFactoryMode.NO_CODEGEN.toString) {
        projection(n)
      }
    }
    benchmark.run()
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    cacheTable()
    runBenchmark("a projection evaluated by the interpreter after its compile fails") {
      Seq(100, 300, 1000).foreach(rung)
    }
  }
}
