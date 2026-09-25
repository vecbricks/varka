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

import scala.concurrent.duration._

import org.apache.spark.benchmark.Benchmark
import org.apache.spark.internal.config.UI.UI_ENABLED
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.{VarkaColumnarToRowExec, VarkaFilterExecBase,
  VarkaProjectExec}
import org.apache.spark.sql.execution.columnar.{InMemoryRelation, InMemoryTableScanExec}
import org.apache.spark.sql.internal.SQLConf

/**
 * The schema-width cliff at the cache (`PLAN_TASK_185.md`): a query that reads one column of a
 * cached table, over tables of 100 and 101 fields. `InMemoryTableScanExec` produces columnar
 * batches only while the whole cached schema has at most `spark.sql.codegen.maxFields` (100)
 * fields, so the two tables, one column apart, are read by two different paths.
 *
 * Two questions, in two phases, since the cache serializer is fixed for the process:
 *
 *  - What the whole-schema count costs vanilla Spark, with its default cache serializer, over
 *    tables of int columns alone, the only kind that serializer produces batches for: a sum of
 *    one int column over 100 fields (columnar), over 101 (rows), and over 101 with `maxFields`
 *    raised past the width (columnar again), which is what counting the scan's own output instead
 *    of the whole schema would give.
 *  - Whether Varka costs the same at both widths, over the Arrow cache, where with Varka on the
 *    scan counts the limit over the columns it reads and so reads the 101-field table as batches
 *    too: `date_add` of the one date column, on vanilla Spark and on Varka.
 *
 * Each table writes, after it, how every arm's plan read the cache.
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh sql VarkaSchemaWidthBenchmark
 * }}}
 */
object VarkaSchemaWidthBenchmark extends SqlBasedBenchmark {

  private val numRows = 1000000

  private def note(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  /**
   * Caches `wide_<n>` with `n` fields, one null in 31 of each: `n` int columns, or with `date` a
   * date `d` and `n - 1` int columns. Spark's default cache serializer produces batches only for
   * primitive numeric types, so its phase caches ints alone.
   */
  private def cacheWide(session: SparkSession, n: Int, date: Boolean): String = {
    val name = s"wide_$n"
    val first = if (date) 1 else 0
    val ints = (first until n).map(k =>
      s"cast(if(id % 31 = ${k + 1} % 31, null, id + $k) as int) AS i${k + 1 - first}")
    val dateColumn =
      "if(id % 31 = 0, null, date_add(date'2000-01-01', cast(id % 20000 as int))) AS d"
    session.range(0, numRows)
      .selectExpr((if (date) dateColumn +: ints else ints): _*)
      .createOrReplaceTempView(name)
    session.catalog.cacheTable(name)
    session.sql(s"select count(*) from $name").collect()
    name
  }

  /** How `query`'s plan reads the cache, for the note after each table. */
  private def reads(session: SparkSession, query: String): String = {
    val plan = session.sql(query).queryExecution.executedPlan
    val scan = plan.collectFirst { case s: InMemoryTableScanExec => s }
    val varka = plan.collectFirst {
      case v @ (_: VarkaProjectExec | _: VarkaColumnarToRowExec | _: VarkaFilterExecBase) => v
    }.isDefined
    val columnar = if (scan.exists(_.supportsColumnar)) "columnar" else "rows"
    s"${if (varka) "Varka" else "Spark"}, cache scan $columnar"
  }

  private def session(name: String, varka: Boolean, arrow: Boolean): SparkSession = {
    if (arrow) {
      VarkaArrowSessions.createSession(name, varkaEnabled = varka)
    } else {
      SparkSession.builder().master("local[1]").appName(name)
        .config(UI_ENABLED.key, false)
        .config(SQLConf.SHUFFLE_PARTITIONS.key, 1)
        .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
        .getOrCreate()
    }
  }

  private def fresh(): Unit = {
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.stop()
    fresh()

    // Phase 1: vanilla Spark and its default cache serializer.
    InMemoryRelation.clearSerializer()
    val vanilla = session("VarkaSchemaWidth-default", varka = false, arrow = false)
    fresh()
    try {
      Seq(100, 101).foreach(cacheWide(vanilla, _, date = false))
      runBenchmark("vanilla Spark, default cache serializer: sum of one column") {
        val benchmark = new Benchmark(s"sum(i1) over $numRows cached rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val arms = Seq(
          ("100 fields", "wide_100", None),
          ("101 fields", "wide_101", None),
          ("101 fields, maxFields=1000", "wide_101", Some("1000")))
        arms.foreach { case (label, table, maxFields) =>
          benchmark.addCase(label) { _ =>
            maxFields.foreach(vanilla.conf.set(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key, _))
            try vanilla.sql(s"SELECT sum(i1) FROM $table").noop()
            finally vanilla.conf.unset(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key)
          }
        }
        benchmark.run()
        arms.foreach { case (label, table, maxFields) =>
          maxFields.foreach(vanilla.conf.set(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key, _))
          try note(s"$label: ${reads(vanilla, s"SELECT sum(i1) FROM $table")}")
          finally vanilla.conf.unset(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key)
        }
      }
    } finally {
      vanilla.stop()
      fresh()
      InMemoryRelation.clearSerializer()
    }

    // Phase 2: the Arrow cache, vanilla Spark against Varka, in one session whose Varka switch is
    // set per arm, so the two tables are cached once.
    val arrow = session("VarkaSchemaWidth-arrow", varka = true, arrow = true)
    fresh()
    def on(enabled: Boolean): Unit = arrow.conf.set(SQLConf.VARKA_ENABLED.key, enabled.toString)
    try {
      Seq(100, 101).foreach(cacheWide(arrow, _, date = true))
      runBenchmark("Arrow cache: date_add of one date column") {
        val benchmark = new Benchmark(s"date_add(d, 1) over $numRows cached rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val arms = Seq(
          ("vanilla Spark, 100 fields", false, "wide_100"),
          ("vanilla Spark, 101 fields", false, "wide_101"),
          ("Varka, 100 fields", true, "wide_100"),
          ("Varka, 101 fields", true, "wide_101"))
        arms.foreach { case (label, varka, table) =>
          benchmark.addCase(label) { _ =>
            on(varka)
            arrow.sql(s"SELECT date_add(d, 1) AS e FROM $table").noop()
          }
        }
        benchmark.run()
        arms.foreach { case (label, varka, table) =>
          on(varka)
          note(s"$label: ${reads(arrow, s"SELECT date_add(d, 1) AS e FROM $table")}")
        }
      }
    } finally {
      arrow.stop()
      InMemoryRelation.clearSerializer()
    }
  }
}
