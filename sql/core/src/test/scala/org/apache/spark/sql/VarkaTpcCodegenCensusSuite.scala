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

package org.apache.spark.sql

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.mutable

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeFormatter, CodeGenerator}
import org.apache.spark.sql.catalyst.util.resourceToString
import org.apache.spark.sql.execution.{InputAdapter, SparkPlan, WholeStageCodegenExec}
import org.apache.spark.sql.internal.SQLConf

/**
 * The census of how often the TPC benchmark queries meet HotSpot's `HugeMethodLimit`.
 *
 * HotSpot never compiles a method whose bytecode is past 8000 bytes, so a whole-stage codegen
 * stage whose largest generated method is past it runs interpreted, and Spark says nothing: it
 * only logs, and falls back to its per-operator code, past `spark.sql.codegen.hugeMethodLimit`,
 * which defaults to 65535. Spark's own `TPCDSQuerySuite` asserts that every TPC-DS stage is within
 * 8000 bytes, after turning `spark.sql.readSideCharPadding` off and excluding `modified-q3`. This
 * census walks the same stages under the settings users run and records every stage's size
 * instead of asserting it (`PLAN_TASK_193.md`).
 *
 * Every whole-stage stage of every query, subqueries included, becomes one line: the query set,
 * the query, the stage id, the stage's operators top down, the largest method's bytes, the
 * largest constant pool, and the band - `ok` within 8000 bytes, `interpreted` past it (the silent
 * cliff), `fallback` past `hugeMethodLimit`, where Spark runs the stage without whole-stage
 * codegen, or `compile failed`. Plans are compiled over the empty tables the TPC bases create;
 * nothing is run, which is why adaptive execution is off: its stages exist only at run time.
 *
 * Each concrete suite below is one configuration. The census runs only when the system property
 * `varka.tpc.census.dir` names the directory for its files; otherwise its tables are not even
 * created and its one test cancels, so the suites cost CI nothing. When
 * `varka.tpc.census.sources` names a directory, the generated source of every stage past 8000
 * bytes is written there, for reading which operator made it big. `dev/varka_tpc_census.sh`
 * runs all six.
 */
trait VarkaTpcCodegenCensus extends BenchmarkQueryTest with TPCBase {

  /** The name of this configuration, which names its results file. */
  protected def configuration: String

  /** The query sets this configuration walks, by set name and resource directory. */
  protected def querySets: Seq[(String, String, Seq[String])]

  /**
   * Queries whose stages Spark's own suites assert are within 8000 bytes under this
   * configuration, with the set they belong to; empty where no suite of Spark's makes the claim.
   */
  protected def assertedBySpark: Set[(String, String)] = Set.empty

  // Adaptive execution off: under it `executedPlan` is an `AdaptiveSparkPlanExec` leaf whose
  // stages exist only once the query runs, so a plan that is compiled but never run has none to
  // walk. The census records how many stages the same walk finds with it on (`aqeStages`).
  override protected def sparkConf: SparkConf =
    super.sparkConf.set(SQLConf.ADAPTIVE_EXECUTION_ENABLED, false)

  private val outDir = sys.props.get("varka.tpc.census.dir").map(new File(_))
  private val sourcesDir = sys.props.get("varka.tpc.census.sources").map(new File(_))

  private case class Stage(
      set: String,
      query: String,
      id: Int,
      operators: String,
      bytes: Int,
      constantPool: Int,
      band: String)

  private val stages = mutable.ArrayBuffer.empty[Stage]

  /** Stages `checkGeneratedCode`'s walk finds with adaptive execution on, Spark's default. */
  private var aqeStages = 0

  /** Whether the census runs; without its directory the tables are not even created. */
  protected def enabled: Boolean = outDir.isDefined

  /** The stage's own operators, top down, stopping where another stage's input begins. */
  private def operatorsOf(stage: WholeStageCodegenExec): Seq[String] = {
    def walk(p: SparkPlan): Seq[String] = p match {
      case _: InputAdapter => Nil
      case other => other.nodeName +: other.children.flatMap(walk)
    }
    walk(stage.child)
  }

  /** The whole-stage stages of a plan, found the way `checkGeneratedCode` finds them. */
  private def stagesOf(plan: SparkPlan): Seq[WholeStageCodegenExec] = {
    val found = mutable.LinkedHashSet.empty[WholeStageCodegenExec]
    def collect(p: SparkPlan): Unit = p.foreach {
      case s: WholeStageCodegenExec => found += s
      case other => other.subqueries.foreach(collect)
    }
    collect(plan)
    found.toSeq
  }

  private def censusOf(set: String, query: String, plan: SparkPlan): Seq[Stage] = {
    val found = stagesOf(plan)
    val limit = CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT
    val sparkLimit = spark.sessionState.conf.hugeMethodLimit
    found.toSeq.map { stage =>
      val code = stage.doCodeGen()._2
      val operators = operatorsOf(stage).mkString(" < ")
      try {
        val stats = CodeGenerator.compile(code)._2
        val band =
          if (stats.maxMethodCodeSize > sparkLimit) "fallback"
          else if (stats.maxMethodCodeSize > limit) "interpreted"
          else "ok"
        if (stats.maxMethodCodeSize > limit) {
          sourcesDir.foreach { dir =>
            val name = s"$configuration/$set-$query-stage${stage.codegenStageId}.java"
            val file = new File(dir, name)
            file.getParentFile.mkdirs()
            Files.write(file.toPath, CodeFormatter.format(code).getBytes(StandardCharsets.UTF_8))
          }
        }
        Stage(set, query, stage.codegenStageId, operators, stats.maxMethodCodeSize,
          stats.maxConstPoolSize, band)
      } catch {
        case e: Exception =>
          Stage(set, query, stage.codegenStageId, operators, -1, -1,
            s"compile failed: ${e.getClass.getSimpleName}")
      }
    }
  }

  test("the census") {
    assume(outDir.isDefined, "the census runs only with -Dvarka.tpc.census.dir")
    for ((set, dir, queries) <- querySets; query <- queries) {
      val text = resourceToString(s"$dir/$query.sql",
        classLoader = Thread.currentThread().getContextClassLoader)
      stages ++= censusOf(set, query, sql(text).queryExecution.executedPlan)
      withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
        aqeStages += stagesOf(sql(text).queryExecution.executedPlan).size
      }
    }

    // Where Spark's own suite asserts every stage is within 8000 bytes, the census must agree,
    // or it is measuring something else and none of its lines can be read.
    val disagreeing = stages.filter { s =>
      assertedBySpark.contains((s.set, s.query)) && s.band != "ok"
    }
    assert(disagreeing.isEmpty, "stages Spark's own suite asserts are within 8000 bytes: " +
      disagreeing.map(s => s"${s.set} ${s.query} stage ${s.id}: ${s.bytes}").mkString(", "))

    write()
  }

  private def write(): Unit = {
    val limit = CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT
    val sqlConf = spark.sessionState.conf
    val out = new StringBuilder
    out ++= s"TPC codegen census, configuration: $configuration\n"
    out ++= s"commit:      ${sys.props.getOrElse("varka.tpc.census.commit", "unknown")}\n"
    out ++= s"java:        ${System.getProperty("java.version")}\n"
    out ++= s"settings:    ${SQLConf.READ_SIDE_CHAR_PADDING.key}=" +
      s"${sqlConf.getConf(SQLConf.READ_SIDE_CHAR_PADDING)}, statistics injected=$injectStats, " +
      s"${SQLConf.WHOLESTAGE_HUGE_METHOD_LIMIT.key}=${sqlConf.hugeMethodLimit}, " +
      s"${SQLConf.ADAPTIVE_EXECUTION_ENABLED.key}=false\n"
    out ++= s"under AQE:   the same walk with ${SQLConf.ADAPTIVE_EXECUTION_ENABLED.key}=true " +
      s"finds $aqeStages stages in these queries\n"
    out ++= s"bands:       ok <= $limit bytes < interpreted <= ${sqlConf.hugeMethodLimit} " +
      "bytes < fallback\n\n"
    out ++= "summary\n"
    for ((set, _, queries) <- querySets) {
      val ofSet = stages.filter(_.set == set)
      val counts = ofSet.groupBy(_.band).view.mapValues(_.size).toSeq.sortBy(_._1)
      val queriesPast = ofSet.filter(_.band != "ok").map(_.query).distinct
      out ++= f"  $set%-14s ${queries.size}%4d queries ${ofSet.size}%5d stages  " +
        counts.map { case (b, n) => s"$b $n" }.mkString(", ") +
        (if (queriesPast.isEmpty) "" else s"; past $limit: ${queriesPast.mkString(" ")}") + "\n"
    }
    out ++= "\nset\tquery\tstage\tbytes\tconstant pool\tband\toperators\n"
    for (s <- stages) {
      out ++= s"${s.set}\t${s.query}\t${s.id}\t${s.bytes}\t${s.constantPool}\t${s.band}\t" +
        s"${s.operators}\n"
    }
    outDir.foreach { dir =>
      dir.mkdirs()
      Files.write(new File(dir, s"tpc_codegen-$configuration.txt").toPath,
        out.toString.getBytes(StandardCharsets.UTF_8))
    }
  }
}

/** TPC-DS, all four query sets: v1.4 (all 103, none skipped), v2.7 and the modified ones. */
abstract class VarkaTpcdsCodegenCensusBase extends VarkaTpcCodegenCensus with TPCDSBase {

  override def createTables(): Unit = if (enabled) super.createTables()
  override def dropTables(): Unit = if (enabled) super.dropTables()

  // TPCDSQuerySuite skips six v1.4 queries that v2.7 repeats; the census keeps them.
  override protected def excludedTpcdsQueries: Set[String] = Set.empty

  protected def charPadding: Boolean

  override protected def sparkConf: SparkConf =
    super.sparkConf.set(SQLConf.READ_SIDE_CHAR_PADDING, charPadding)

  override protected def querySets: Seq[(String, String, Seq[String])] = Seq(
    ("tpcds-v1.4", "tpcds", tpcdsQueries),
    ("tpcds-v2.7", "tpcds-v2.7.0", tpcdsQueriesV2_7_0),
    ("tpcds-mod", "tpcds-modifiedQueries", modifiedTPCDSQueries))

  // What TPCDSQuerySuite and TPCDSQueryWithStatsSuite assert, with padding off: every stage of
  // the queries they run within 8000 bytes, except modified-q3's.
  override protected def assertedBySpark: Set[(String, String)] =
    if (charPadding) {
      Set.empty
    } else {
      val skipped = Set("q6", "q34", "q64", "q74", "q75", "q78")
      tpcdsQueries.filterNot(skipped).map(("tpcds-v1.4", _)).toSet ++
        tpcdsQueriesV2_7_0.map(("tpcds-v2.7", _)) ++
        modifiedTPCDSQueries.filterNot(_ == "q3").map(("tpcds-mod", _))
    }
}

/** TPC-DS under Spark's defaults: read-side char padding on. */
class VarkaTpcdsCodegenCensusSuite extends VarkaTpcdsCodegenCensusBase {
  override protected def configuration: String = "tpcds-defaults"
  override protected def charPadding: Boolean = true
}

/** TPC-DS under Spark's defaults, with table statistics, so joins are planned as a warehouse's. */
class VarkaTpcdsWithStatsCodegenCensusSuite extends VarkaTpcdsCodegenCensusBase {
  override protected def configuration: String = "tpcds-defaults-stats"
  override protected def charPadding: Boolean = true
  override def injectStats: Boolean = true
}

/** TPC-DS with padding off, as Spark's own `TPCDSQuerySuite` runs it. */
class VarkaTpcdsNoPaddingCodegenCensusSuite extends VarkaTpcdsCodegenCensusBase {
  override protected def configuration: String = "tpcds-nopadding"
  override protected def charPadding: Boolean = false
}

/** TPC-DS with padding off and statistics, as Spark's own `TPCDSQueryWithStatsSuite` runs it. */
class VarkaTpcdsNoPaddingWithStatsCodegenCensusSuite extends VarkaTpcdsCodegenCensusBase {
  override protected def configuration: String = "tpcds-nopadding-stats"
  override protected def charPadding: Boolean = false
  override def injectStats: Boolean = true
}

/** TPC-H's 22 queries. */
abstract class VarkaTpchCodegenCensusBase extends VarkaTpcCodegenCensus with TPCHBase {
  override def createTables(): Unit = if (enabled) super.createTables()
  override def dropTables(): Unit = if (enabled) super.dropTables()

  override protected def querySets: Seq[(String, String, Seq[String])] =
    Seq(("tpch", "tpch", tpchQueries))
}

/**
 * TPC-H. Its test schema declares no `CHAR` column, so padding changes nothing and only
 * statistics vary; Spark's `TPCHQuerySuite` asserts every stage within 8000 bytes without them.
 */
class VarkaTpchCodegenCensusSuite extends VarkaTpchCodegenCensusBase {
  override protected def configuration: String = "tpch"
  override protected def assertedBySpark: Set[(String, String)] =
    tpchQueries.map(("tpch", _)).toSet
}

/** TPC-H with table statistics. */
class VarkaTpchWithStatsCodegenCensusSuite extends VarkaTpchCodegenCensusBase {
  override protected def configuration: String = "tpch-stats"
  override def injectStats: Boolean = true
}
