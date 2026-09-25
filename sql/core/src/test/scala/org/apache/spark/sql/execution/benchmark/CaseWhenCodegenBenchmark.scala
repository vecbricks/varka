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
import org.apache.spark.sql.catalyst.expressions.{BindReferences, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeAndComment, CodeFormatter,
  CodeGenerator, GenerateUnsafeProjection}
import org.apache.spark.sql.execution.{ProjectExec, WholeStageCodegenExec}
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
 * Interpreted, `CaseWhen.eval` indexes its branches by position. Each rung prints, above its
 * timings, the largest method of the stage and the largest method of the projection Spark
 * compiles outside a stage, so a step can be read against the limit that caused it.
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
 * environment variable `SPARK_TESTING`, which cannot be cleared from inside the JVM; there a
 * rung whose stage fails to compile leaves out its whole-stage case and says so in the output,
 * so the file is complete but not the production measurement. The runners use the first way.
 * The rungs and the row count can be given as arguments.
 */
object CaseWhenCodegenBenchmark extends SqlBasedBenchmark {

  private def query(branches: Int, rows: Long): DataFrame = {
    val whens = (1 to branches).map(k => s"WHEN v = $k THEN v * $k").mkString(" ")
    spark.sql(
      s"SELECT CASE $whens ELSE 0 END AS c FROM (SELECT id % $branches AS v FROM range($rows))")
  }

  /** The largest method of `code` in bytes, or why it has none, from the compile's own error. */
  private def largestMethod(code: => CodeAndComment): String = {
    try {
      val (_, stats) = CodeGenerator.compile(code)
      s"${stats.maxMethodCodeSize} bytes"
    } catch {
      case NonFatal(e) =>
        val chain = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).toSeq
        if (chain.exists(t => String.valueOf(t.getMessage).contains("grows beyond 64 KB"))) {
          "fails to compile, past 64 KB"
        } else {
          s"fails to compile: ${chain.last.getClass.getSimpleName}"
        }
    }
  }

  /** The largest method of each whole-stage codegen stage of `df`'s plan. */
  private def stageMethodSize(df: DataFrame): String = {
    val stages = df.queryExecution.executedPlan.collect { case w: WholeStageCodegenExec => w }
    if (stages.isEmpty) "no stage"
    else stages.map(s => largestMethod(s.doCodeGen()._2)).mkString(", ")
  }

  /**
   * The largest method of the projection Spark compiles for `df` outside a stage: the class
   * `UnsafeProjection.create` builds, assembled here the way `GenerateUnsafeProjection` does so
   * that the compile's statistics can be read.
   */
  private def projectionMethodSize(df: DataFrame): String = {
    val project = df.queryExecution.executedPlan.collectFirst { case p: ProjectExec => p }.get
    val exprs = BindReferences.bindReferences(project.projectList, project.child.output)
    val ctx = GenerateUnsafeProjection.newCodeGenContext()
    val eval = GenerateUnsafeProjection.createCode(ctx, exprs, conf.subexpressionEliminationEnabled)
    val body =
      s"""
         |public java.lang.Object generate(Object[] references) {
         |  return new SpecificUnsafeProjection(references);
         |}
         |class SpecificUnsafeProjection extends ${classOf[UnsafeProjection].getName} {
         |  private Object[] references;
         |  ${ctx.declareMutableStates()}
         |  public SpecificUnsafeProjection(Object[] references) {
         |    this.references = references;
         |    ${ctx.initMutableStates()}
         |  }
         |  public void initialize(int partitionIndex) {
         |    ${ctx.initPartition()}
         |  }
         |  ${CodeGenerator.function1ApplyBridge(ctx.INPUT_ROW)}
         |  public UnsafeRow apply(InternalRow ${ctx.INPUT_ROW}) {
         |    ${eval.code}
         |    return ${eval.value};
         |  }
         |  ${ctx.declareAddedFunctions()}
         |}
       """.stripMargin
    largestMethod(CodeFormatter.stripOverlappingComments(
      new CodeAndComment(body, ctx.getPlaceHolderToComments())))
  }

  private def caseWhenLadder(branches: Int, rows: Long): Unit = {
    val benchmark = new Benchmark(s"CASE WHEN of $branches branches", rows, output = output)
    val stage = stageMethodSize(query(branches, rows))
    val outside = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
      projectionMethodSize(query(branches, rows))
    }
    // Under sbt `SPARK_TESTING` turns a failed stage compile into an error; see the class doc.
    val stageThrows = stage.startsWith("fails") && sys.env.contains("SPARK_TESTING")
    // scalastyle:off println
    benchmark.out.println(s"largest method of the stage: $stage")
    benchmark.out.println(s"largest method of the projection outside a stage: $outside")
    if (stageThrows) {
      benchmark.out.println("whole-stage case left out: SPARK_TESTING is set, so the failed " +
        "compile would throw instead of falling back")
    }
    // scalastyle:on println

    if (!stageThrows) {
      benchmark.addCase("whole-stage codegen on", numIters = 5) { _ =>
        withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
          query(branches, rows).noop()
        }
      }
    }
    benchmark.addCase("whole-stage codegen off", numIters = 5) { _ =>
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        query(branches, rows).noop()
      }
    }
    benchmark.addCase("interpreted, factoryMode=NO_CODEGEN", numIters = 5) { _ =>
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
    // The JVM's first queries also compile Spark's own code paths, which a case's two-second
    // warm-up does not cover for the first rung: run as the first rung, 30 branches measured
    // the stage at about twice its cost when run again later in the same JVM. So the smallest
    // rung runs once under each configuration, unrecorded, before the ladder.
    Seq(
      SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true",
      SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false",
      SQLConf.CODEGEN_FACTORY_MODE.key -> "NO_CODEGEN").foreach { setting =>
      withSQLConf(setting) {
        (1 to 3).foreach(_ => query(rungs.min, rows).noop())
      }
    }
    rungs.foreach { branches =>
      runBenchmark(s"CASE WHEN of $branches branches over $rows rows") {
        caseWhenLadder(branches, rows)
      }
    }
  }
}
