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
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.execution.{ProjectExec, SparkPlan, WholeStageCodegenExec}
import org.apache.spark.sql.execution.adaptive.{AdaptiveSparkPlanExec, QueryStageExec}
import org.apache.spark.sql.internal.SQLConf

/**
 * Whether a wide projection's loss inside a whole-stage codegen stage holds for entries heavier
 * than an addition, and what a user can do about it (`VARKA-233.md` 12.5).
 *
 * A projection of 50 or more cheap entries `id + k` runs slower inside a stage than outside one,
 * because C2 does not inline the row writes into the stage's consume method once it passes a few
 * thousand bytes (`VARKA-233.md` 11.1, 11.2, 13.4). Two families of shape at 50 and 99 entries:
 * the cheap entries, and a mix of six kinds an analytic query has - an addition, `date_add`, a
 * string `concat`, a division over a cast, a null test over a nullable column and `substr` - whose
 * work per entry may hide the calls. Each runs three ways: under the defaults, in a stage; with
 * `spark.sql.codegen.maxFields` just below its width, which takes the projection alone out of its
 * stage (over a cached table that leaves no stage, since the scan alone is not wrapped in one, but
 * under a filter or an aggregate it is the narrower setting); and with whole-stage codegen off.
 * Every case warms up for fifteen seconds first, so that the times are C2's code, not the wait
 * for it (`CodegenCompileWaitBenchmark`). Above each table the benchmark prints where the
 * projection ran under each setting. Varka plays no part.
 *
 * To run this benchmark:
 * {{{
 *   1. without sbt:
 *      bin/spark-submit --class <this class> --jars <spark core test jar> <spark sql test jar>
 *   2. build/sbt "sql/Test/runMain <this class>"
 *   3. generate result:
 *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/Test/runMain <this class>"
 *      Results will be written to "benchmarks/CodegenWideProjectionBenchmark-results.txt".
 * }}}
 */
object CodegenWideProjectionBenchmark extends SqlBasedBenchmark {

  private val numRows = 1000000L

  private def cheap(k: Int): String = s"id + $k AS c$k"

  private def mixed(k: Int): String = (k % 6) match {
    case 0 => s"id + $k AS c$k"
    case 1 => s"date_add(d, $k) AS c$k"
    case 2 => s"concat(s, '$k') AS c$k"
    case 3 => s"cast(id * $k as double) / 7 AS c$k"
    case 4 => s"if(x is null, $k, x * $k) AS c$k"
    case _ => s"substr(s, ${1 + k % 3}, 4) AS c$k"
  }

  private def projection(n: Int, entry: Int => String): DataFrame =
    spark.sql(s"select ${(1 to n).map(entry).mkString(", ")} from rows")

  private def stagesOf(plan: SparkPlan): Seq[WholeStageCodegenExec] = plan match {
    case a: AdaptiveSparkPlanExec => stagesOf(a.executedPlan)
    case q: QueryStageExec => stagesOf(q.plan)
    case w: WholeStageCodegenExec => w +: w.children.flatMap(stagesOf)
    case p => p.children.flatMap(stagesOf)
  }

  /** Where the projection ran: inside a stage, outside one beside a stage, or with none at all. */
  private def placement(df: DataFrame): String = {
    val stages = stagesOf(df.queryExecution.executedPlan)
    if (stages.isEmpty) "no stage in the plan"
    else if (stages.exists(_.collectFirst { case p: ProjectExec => p }.isDefined)) "in a stage"
    else "the projection outside the stage"
  }

  private def rung(name: String, n: Int, entry: Int => String): Unit = {
    val settings = Seq(
      "the defaults" -> Seq.empty[(String, String)],
      s"maxFields=${n - 1}" -> Seq(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key -> (n - 1).toString),
      "wholeStage=false" -> Seq(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false"))
    val benchmark = new Benchmark(s"$n $name entries over $numRows cached rows", numRows,
      warmupTime = 15.seconds, output = output)
    settings.foreach { case (label, conf) =>
      val where = withSQLConf(conf: _*) {
        val df = projection(n, entry)
        df.noop()
        placement(df)
      }
      // scalastyle:off println
      benchmark.out.println(s"$label: $where")
      // scalastyle:on println
      benchmark.addCase(s"$label: $where", numIters = 5) { _ =>
        withSQLConf(conf: _*) {
          projection(n, entry).noop()
        }
      }
    }
    benchmark.run()
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.sql(
      s"""select id,
         |       date_add(date'2020-01-01', cast(id % 1460 as int)) as d,
         |       concat('row-', cast(id % 1000 as string)) as s,
         |       if(id % 7 = 0, null, id) as x
         |from range(0, $numRows)""".stripMargin).createOrReplaceTempView("source")
    spark.sql("cache table rows as select * from source")
    spark.table("rows").noop()
    runBenchmark("a wide projection in a stage, and taking it out") {
      Seq(50, 99).foreach { n =>
        rung("cheap", n, cheap)
        rung("mixed", n, mixed)
      }
    }
  }
}
