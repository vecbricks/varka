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

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets

import scala.concurrent.duration._

import com.sun.management.HotSpotDiagnosticMXBean

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator
import org.apache.spark.sql.execution.WholeStageCodegenExec
import org.apache.spark.sql.internal.SQLConf

/**
 * Can vanilla Spark tune its way off the size ladder's cliff? `VarkaSizeLadderBenchmark` shows
 * stock Spark's time per row stepping up several times over where its generated consume method
 * passes HotSpot's 8000-byte `HugeMethodLimit` and is never compiled. This benchmark runs the
 * same rungs, data and query on vanilla Spark only, under the settings a reader would name as
 * the remedy, so the size of what they buy, and what they cost below the cliff, is measured
 * rather than argued:
 *
 *  - `defaults`, the ladder's own vanilla arm;
 *  - `spark.sql.codegen.hugeMethodLimit=8000`: an internal setting, default 65535, whose own doc
 *    advises 8000 on HotSpot. Past it Spark keeps the whole-stage codegen node in the plan but
 *    executes its child with the per-operator code paths instead;
 *  - `spark.sql.codegen.wholeStage=false`: whole-stage codegen off for every stage.
 *
 * The fourth remedy, `-XX:-DontCompileHugeMethods`, is a JVM flag rather than a session setting,
 * so it is a second run of this class under the flag rather than an arm. The class reads the flag
 * back from the running JVM, names its results file after it
 * (`-dontcompilehugemethods-off-results.txt`) and states it in the file's header, so a file can
 * never claim a flag its JVM did not have.
 *
 * Before timing a rung, each arm runs the query once and the benchmark checks that the plan did
 * what the setting promises, failing the run otherwise: under the defaults whole-stage codegen
 * ran at every rung, under `wholeStage=false` the plan has no whole-stage stage, and under
 * `hugeMethodLimit=8000` it ran exactly at the rungs whose method is within 8000 bytes. Whether
 * a stage ran is read from its `pipelineTime` metric, which only the compiled pipeline updates:
 * the node is in the plan either way. After each table a note records vanilla's largest method
 * and what each arm executed.
 *
 * To run this benchmark (the flag run takes the same command with the option added to the sbt
 * test JVM, as `dev/varka_bench_regen.sh`'s narrow run adds `-XX:MaxVectorSize`):
 * {{{
 *   dev/varka_bench_regen.sh sql VarkaSizeLadderTuningBenchmark --no-narrow
 *   SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "project sql" \
 *     'set Test/javaOptions += "-XX:-DontCompileHugeMethods"' \
 *     "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaSizeLadderTuningBenchmark"
 * }}}
 * Vanilla arms only, so the vector width does not matter and there is no narrow companion.
 */
object VarkaSizeLadderTuningBenchmark extends SqlBasedBenchmark {
  import VarkaSizeLadderBenchmark.{cacheDates, createSession, entry, numRows, rungs,
    vanillaMethodBytes}

  /** The `hugeMethodLimit` arm's value: HotSpot's `HugeMethodLimit`, as Spark's doc advises. */
  private val tunedLimit = CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT

  /** The settings under test, by the name the results file shows. */
  private val arms: Seq[(String, Map[String, String])] = Seq(
    "defaults" -> Map.empty,
    s"hugeMethodLimit=$tunedLimit" ->
      Map(SQLConf.WHOLESTAGE_HUGE_METHOD_LIMIT.key -> tunedLimit.toString),
    "wholeStage=false" -> Map(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false"))

  private val armKeys = arms.flatMap(_._2.keys).distinct

  /** HotSpot's own answer, not the command line's: whether huge methods are left interpreted. */
  private lazy val dontCompileHugeMethods: Boolean =
    ManagementFactory.getPlatformMXBean(classOf[HotSpotDiagnosticMXBean])
      .getVMOption("DontCompileHugeMethods").getValue.toBoolean

  override def suffix: String =
    if (dontCompileHugeMethods) "" else "-dontcompilehugemethods-off"

  /** Puts the session in one arm's settings, clearing whatever the previous arm set. */
  private def use(session: SparkSession, settings: Map[String, String]): Unit = {
    armKeys.foreach(session.conf.unset)
    settings.foreach { case (k, v) => session.conf.set(k, v) }
  }

  /**
   * Runs the query once under the session's current settings and says how it executed: "whole-
   * stage" if a whole-stage codegen stage ran its compiled pipeline, "per-operator" if the plan
   * has a stage that fell back to its child, "no whole-stage" if the plan has none.
   */
  private def executedAs(session: SparkSession, query: String): String = {
    val df = session.sql(query)
    df.queryExecution.toRdd.count()
    val stages = df.queryExecution.executedPlan.collect { case w: WholeStageCodegenExec => w }
    if (stages.isEmpty) {
      "no whole-stage"
    } else if (stages.forall(_.metrics("pipelineTime").value > 0)) {
      "whole-stage"
    } else {
      "per-operator"
    }
  }

  private def note(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    // The inherited session uses the default cache serializer; the ladder's session reads the
    // Arrow cache, as its vanilla arm does.
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val session = createSession("VarkaSizeLadderTuning", varkaEnabled = false)
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    try {
      cacheDates(session)
      runBenchmark("vanilla Spark's settings on the size ladder " +
          s"(DontCompileHugeMethods=$dontCompileHugeMethods)") {
        for (n <- rungs) {
          val query = s"SELECT ${(1 to n).map(entry).mkString(", ")} FROM ladder_dates"
          use(session, Map.empty)
          val bytes = vanillaMethodBytes(session, query)
          val executed = arms.map { case (name, settings) =>
            use(session, settings)
            val as = executedAs(session, query)
            val expected = name match {
              case "defaults" => "whole-stage"
              case "wholeStage=false" => "no whole-stage"
              case _ => if (bytes > tunedLimit) "per-operator" else "whole-stage"
            }
            require(as == expected,
              s"rung $n, $name: expected $expected execution, the plan ran $as")
            s"$name $as"
          }
          val benchmark = new Benchmark(s"$n entries over $numRows Arrow-cached rows", numRows,
            minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
          for ((name, settings) <- arms) {
            benchmark.addCase(s"vanilla Spark, $name") { _ =>
              use(session, settings)
              session.sql(query).noop()
            }
          }
          benchmark.run()
          note(s"rung $n: vanilla's largest generated method is $bytes bytes, " +
            (if (bytes > tunedLimit) "past" else "under") + s" HugeMethodLimit $tunedLimit; " +
            executed.mkString(", "))
        }
      }
    } finally {
      session.stop()
    }
  }
}
