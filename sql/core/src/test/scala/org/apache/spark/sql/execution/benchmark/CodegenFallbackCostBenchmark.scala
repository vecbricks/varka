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

import scala.util.control.NonFatal

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeAndComment, CodeGenerator}
import org.apache.spark.sql.execution.{ProjectExec, WholeStageCodegenExec}
import org.apache.spark.sql.internal.SQLConf

/**
 * What the silent give-ups of whole-stage codegen cost: the cases where an operator leaves its
 * stage with nothing logged and only a missing `*(n)` in `EXPLAIN` to show for it
 * (`PLAN_TASK_233.md` 3).
 *
 * Two of them. **One `CodegenFallback` expression in a projection**: `from_json` has no
 * generated code, so one of it in a projection takes the whole operator out of its stage, where
 * the operator still compiles its projection but reads and writes rows at its boundary instead
 * of keeping values in locals. The size ladder's date expressions at 16, 32 and 48 entries,
 * below the 8000-byte cliff so that the stage is compiled, run alone in a stage, beside one
 * `from_json`, and beside one `get_json_object` over the same string, which generates code and
 * keeps the stage. The slope per entry says what losing the stage costs a compiled expression;
 * the difference between the two JSON arms at one rung is the fallback's own price against a
 * function that generates code. **A projection past `maxFields`**: a hundred and fifty entries
 * leave their operator out of a stage at the default of 100 and put it in one at 200, and the
 * stage's method may then pass 8000 bytes, which the first post warned of without a number:
 * the cheap entries `id + k` stay under it, `date_add(d, k)` entries do not.
 *
 * The second case has a third section, from what the first runs found: the 150 cheap entries in
 * a stage ran 2.4 times slower than outside one with a method of 5747 bytes, under the limit,
 * because C2 took about seventeen seconds to compile it and until then the stage ran C1's
 * profiled code. So each arm also runs a projection new to the JVM back to back for a minute,
 * and prints every query's time, which shows when C2's code arrives.
 *
 * The table is cached with Spark's own cache, and every arm reads the same cache. Above each
 * rung the benchmark prints where the projection landed, in a stage or outside one, and for the
 * wide projection the stage's largest method in bytes, so a step can be read against its cause.
 *
 * To run this benchmark:
 * {{{
 *   1. without sbt:
 *      bin/spark-submit --class <this class> --jars <spark core test jar> <spark sql test jar>
 *   2. build/sbt "sql/Test/runMain <this class>"
 *   3. generate result:
 *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/Test/runMain <this class>"
 *      Results will be written to "benchmarks/CodegenFallbackCostBenchmark-results.txt".
 * }}}
 */
object CodegenFallbackCostBenchmark extends SqlBasedBenchmark {

  private val numRows = 1000000L

  private def entry(k: Int): String =
    s"greatest(add_months(d, $k), date_add(d, $k), last_day(d)) AS c$k"

  private def cacheTable(): Unit = {
    // A date, and a JSON string that differs per row so that nothing folds it to a constant.
    spark.sql(
      s"""select date_add(date'2020-01-01', cast(id % 1460 as int)) as d,
         |       concat('{"a":', cast(id % 10 as string), '}') as js,
         |       id
         |from range(0, $numRows)""".stripMargin).createOrReplaceTempView("rows")
    spark.sql("cache table rows")
    spark.table("rows").noop()
  }

  /** Where `df`'s projection landed: inside a stage, or outside one. */
  private def placement(df: DataFrame): String = {
    val plan = df.queryExecution.executedPlan
    val inStage = plan.collect { case w: WholeStageCodegenExec =>
      w.collectFirst { case p: ProjectExec => p }.isDefined
    }.exists(identity)
    if (inStage) "the projection runs inside a whole-stage codegen stage"
    else "the projection runs outside any stage"
  }

  /** The largest method of `df`'s stage in bytes, or why there is none. */
  private def stageMethodSize(df: DataFrame): String = {
    val stages = df.queryExecution.executedPlan.collect { case w: WholeStageCodegenExec => w }
    if (stages.isEmpty) return "no stage"
    stages.map { s =>
      try {
        val (_, stats) = CodeGenerator.compile(s.doCodeGen()._2: CodeAndComment)
        s"${stats.maxMethodCodeSize} bytes"
      } catch {
        case NonFatal(e) => s"fails to compile: ${e.getClass.getSimpleName}"
      }
    }.mkString(", ")
  }

  private def ladder(n: Int, extra: Option[String]): DataFrame = {
    val entries = (1 to n).map(entry) ++ extra
    spark.sql(s"select ${entries.mkString(", ")} from rows")
  }

  private def fallbackLadder(n: Int): Unit = {
    val benchmark = new Benchmark(s"$n date entries over $numRows cached rows", numRows,
      output = output)
    val alone = ladder(n, None)
    val fromJson = ladder(n, Some("from_json(js, 'a INT').a AS j"))
    val getJson = ladder(n, Some("get_json_object(js, '$.a') AS j"))
    // scalastyle:off println
    benchmark.out.println(s"entries alone: ${placement(alone)}")
    benchmark.out.println(s"with from_json: ${placement(fromJson)}")
    benchmark.out.println(s"with get_json_object: ${placement(getJson)}")
    // scalastyle:on println
    benchmark.addCase("the entries alone, in a stage", numIters = 5)(_ => alone.noop())
    benchmark.addCase("the entries and one from_json: outside a stage", numIters = 5) { _ =>
      fromJson.noop()
    }
    benchmark.addCase("the entries and one get_json_object: in a stage", numIters = 5) { _ =>
      getJson.noop()
    }
    benchmark.run()
  }

  private def wide(entries: Seq[String]): DataFrame =
    spark.sql(s"select ${entries.mkString(", ")} from rows")

  private def maxFieldsRung(name: String, entries: Seq[String]): Unit = {
    val benchmark = new Benchmark(s"$name, ${entries.size} entries over $numRows cached rows",
      numRows, output = output)
    val atDefault = wide(entries)
    // scalastyle:off println
    benchmark.out.println(s"maxFields=100: ${placement(atDefault)}; " +
      s"largest method: ${stageMethodSize(atDefault)}")
    withSQLConf(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key -> "200") {
      val raised = wide(entries)
      benchmark.out.println(s"maxFields=200: ${placement(raised)}; " +
        s"largest method: ${stageMethodSize(raised)}")
    }
    // scalastyle:on println
    benchmark.addCase("maxFields=100, the default: outside a stage", numIters = 5) { _ =>
      wide(entries).noop()
    }
    benchmark.addCase("maxFields=200: in a stage", numIters = 5) { _ =>
      withSQLConf(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key -> "200") {
        wide(entries).noop()
      }
    }
    benchmark.run()
  }

  /**
   * Each query's time, back to back for `seconds`, of a projection whose generated class is new
   * to this JVM: the offset makes its source unlike any compiled before, so the class is
   * generated, compiled and JIT-compiled from cold, as on a fresh executor.
   */
  private def firstMinute(label: String, offset: Int, maxFields: String, seconds: Int): Unit = {
    val entries = (1 to 150).map(k => s"id + ${k + offset} AS c$k")
    val times = scala.collection.mutable.ArrayBuffer.empty[Long]
    withSQLConf(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key -> maxFields) {
      val start = System.nanoTime()
      while (System.nanoTime() - start < seconds * 1000000000L) {
        val t = System.nanoTime()
        wide(entries).noop()
        times += (System.nanoTime() - t) / 1000000
      }
    }
    // scalastyle:off println
    output.foreach(_.write(s"$label, ms per query: ${times.mkString(" ")}\n"
      .getBytes(StandardCharsets.UTF_8)))
    println(s"$label, ms per query: ${times.mkString(" ")}")
    // scalastyle:on println
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // `-Dcodegen.cost.sections=first-minute` runs one section alone, for a run under a JIT log
    // whose output should hold that section's compiles and nothing else's (PLAN_TASK_233.md 10.2).
    val sections = sys.props.get("codegen.cost.sections").map(_.split(",").toSet)
    def wanted(name: String): Boolean = sections.forall(_.contains(name))
    cacheTable()
    if (wanted("fallback")) {
      // The JVM's first queries also compile Spark's own code paths; the smallest rung runs once
      // under each arm, unrecorded, before anything is timed.
      (1 to 2).foreach { _ =>
        ladder(16, None).noop()
        ladder(16, Some("from_json(js, 'a INT').a AS j")).noop()
        ladder(16, Some("get_json_object(js, '$.a') AS j")).noop()
      }
      runBenchmark("one CodegenFallback expression in a projection: from_json") {
        Seq(16, 32, 48).foreach(fallbackLadder)
      }
    }
    if (wanted("first-minute")) {
      runBenchmark("the first minute of a new stage: 150 entries id + k, queries back to back") {
        firstMinute("maxFields=200, in a stage", 1000, "200", 60)
        firstMinute("maxFields=100, the default, outside a stage", 2000, "100", 60)
      }
    }
    if (wanted("max-fields")) {
      runBenchmark("a projection past maxFields") {
        maxFieldsRung("cheap entries, id + k", (1 to 150).map(k => s"id + $k AS c$k"))
        maxFieldsRung("date entries, date_add(d, k)",
          (1 to 150).map(k => s"date_add(d, $k) AS c$k"))
      }
    }
  }
}
