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

package org.apache.spark.sql.execution

import org.apache.logging.log4j.Level

import org.apache.spark.sql.{Observation, QueryTest, SparkSession}
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.functions.{count, lit}
import org.apache.spark.sql.internal.SQLConf

/**
 * The schema-width cliff at the cache (task 185, the census's G3). Whether an
 * `InMemoryTableScanExec` produces columnar batches is decided by `spark.sql.codegen.maxFields`
 * (100), which vanilla Spark counts over the whole cached relation's schema, not over the columns
 * a query reads; Varka's rule rewrites a projection or a filter only over a columnar child, so a
 * cached table of more than a hundred columns gave Varka nothing to fuse, whatever the query read.
 *
 * With Varka on and the cache in Varka's Arrow serializer, the scan counts the limit over the
 * columns it reads instead (`InMemoryTableScanExec.fieldCountedSchema`), so the real scan produces
 * batches, stays in the plan - inside its query stage under adaptive execution - and Varka's
 * ordinary arms fuse over it. The first test pins vanilla's side at 100 and 101 columns; the rest
 * check Varka's, and the reason it logs where a cause a user can act on still keeps the batches
 * away.
 */
class VarkaSchemaWidthSuite extends QueryTest with VarkaSharedSessions {

  /**
   * Caches `varka_wide_<n>`: a date `d` and `n - 1` int columns, with nulls in both, so the
   * relation has exactly `n` fields.
   */
  private def cacheWide(session: SparkSession, n: Int): String = {
    val name = s"varka_wide_$n"
    val ints = (1 until n).map(k => s"cast(if(id % 11 = $k % 11, null, id + $k) as int) AS i$k")
    session.range(0, 200)
      .selectExpr("if(id % 13 = 0, null, date_add(date'2020-01-01', cast(id * 37 as int))) AS d"
        +: ints: _*)
      .createOrReplaceTempView(name)
    session.catalog.cacheTable(name)
    name
  }

  private def withWide[T](n: Int)(body: String => T): T = {
    val name = cacheWide(spark, n)
    cacheWide(varkaSpark, n)
    try body(name) finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable(name))
    }
  }

  private def cacheScan(plan: SparkPlan): InMemoryTableScanExec =
    collectFirst(plan) { case s: InMemoryTableScanExec => s }
      .getOrElse(fail(s"no cache scan in the plan:\n${plan.treeString}"))

  /** The query on both sessions: the same rows, and on Varka a kernel that ran. */
  private def checkFused(query: String): SparkPlan = {
    val actual = varkaSpark.sql(query)
    checkAnswer(actual, spark.sql(query))
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    assertKernelsRan(plan)
    assert(cacheScan(plan).supportsColumnar, plan.treeString)
    plan
  }

  private def withVarkaConf[T](key: String, value: String)(body: => T): T = {
    val saved = varkaSpark.conf.getOption(key)
    varkaSpark.conf.set(key, value)
    try body finally saved.fold(varkaSpark.conf.unset(key))(varkaSpark.conf.set(key, _))
  }

  test("G3: vanilla's cache scan is columnar over 100 fields and produces rows over 101") {
    for ((n, columnar) <- Seq(100 -> true, 101 -> false)) {
      withWide(n) { name =>
        val plan = spark.sql(s"SELECT date_add(d, 1) FROM $name").queryExecution.executedPlan
        assert(cacheScan(plan).supportsColumnar == columnar, s"$n fields")
      }
    }
  }

  test("under Varka the cache scan counts the columns it reads, so it is columnar over 101") {
    withWide(101) { name =>
      val plan = varkaSpark.sql(s"SELECT date_add(d, 1) FROM $name").queryExecution.executedPlan
      assert(cacheScan(plan).supportsColumnar)
    }
  }

  test("Varka fuses a projection, a filter, and both, over caches of 100 and 101 fields") {
    for (n <- Seq(100, 101)) {
      withWide(n) { name =>
        checkFused(s"SELECT date_add(d, 1) AS e FROM $name")
        checkFused(s"SELECT i1 FROM $name WHERE d > date'2025-01-01'")
        checkFused(s"SELECT date_add(d, 7) AS e, i2 FROM $name WHERE d < date'2030-01-01'")
      }
    }
  }

  test("under adaptive execution the scan is in its query stage, and Varka fuses over it") {
    // Spark's default: the scan is wrapped in a query stage before the columnar rules run, and
    // the stage passes the scan's `supportsColumnar` through.
    withWide(101) { name =>
      withVarkaConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true") {
        checkFused(s"SELECT date_add(d, 1) AS e FROM $name ORDER BY e")
        checkFused(s"SELECT count(*) FROM $name WHERE d > date'2025-01-01'")
      }
    }
  }

  test("a query Varka does not fuse answers as vanilla does over a cache of 101 fields") {
    withWide(101) { name =>
      val query = s"SELECT cast(i1 AS string) AS s FROM $name"
      val actual = varkaSpark.sql(query)
      assertNotFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, spark.sql(query))
    }
  }

  test("observed metrics of a wide cached DataFrame reach the query that reads it") {
    // The scan stays in the plan, where the metrics' collection finds the cached plan.
    val observation = Observation("wide")
    val ints = (1 until 101).map(k => s"cast(id + $k as int) AS i$k")
    val df = varkaSpark.range(0, 200)
      .selectExpr("date_add(date'2020-01-01', cast(id as int)) AS d" +: ints: _*)
      .observe(observation, count(lit(1)).as("n"))
    df.cache()
    try {
      val result = df.selectExpr("date_add(d, 1) AS e")
      assert(result.collect().length == 200)
      assertFused(result.queryExecution.executedPlan)
      assert(observation.get == Map("n" -> 200L))
    } finally {
      df.unpersist()
    }
  }

  /** The lines `VarkaColumnarRule` logs at `level` and above while `body` plans and runs. */
  private def ruleLines(level: Level)(body: => Unit): Seq[(Level, String)] = {
    val appender = new LogAppender("the rule's reasons")
    withLogAppender(appender, loggerNames = Seq("org.apache.spark.sql.execution.VarkaColumnarRule"),
        Some(level))(body)
    appender.loggingEvents.toSeq.map(e => (e.getLevel, e.getMessage.getFormattedMessage))
  }

  test("a projection that reads more columns than maxFields is left to Spark, and says so") {
    withWide(101) { name =>
      val columns = (1 until 101).map(k => s"i$k").mkString(", ")
      val query = s"SELECT date_add(d, 1) AS e, $columns FROM $name"
      val lines = ruleLines(Level.INFO) {
        checkAnswer(varkaSpark.sql(query), spark.sql(query))
      }
      assert(lines.exists { case (level, l) => level == Level.INFO &&
        l.contains("Varka left a projection to Spark") && l.contains("reads more than 100 columns")
      }, lines)
    }
  }

  test("with the vectorized cache reader off, the reason names the reader and not the width") {
    withWide(101) { name =>
      withVarkaConf(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "false") {
        val query = s"SELECT date_add(d, 1) AS e FROM $name"
        val lines = ruleLines(Level.INFO) {
          assertNotFused(varkaSpark.sql(query).queryExecution.executedPlan)
          checkAnswer(varkaSpark.sql(query), spark.sql(query))
        }
        assert(lines.exists { case (_, l) => l.contains("Varka left a projection to Spark") &&
          l.contains(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key) && !l.contains("columns")
        }, lines)
      }
    }
  }

  test("an input that is not columnar for a reason no user can act on logs nothing at INFO") {
    // A projection Varka fuses, over a range, which never produces batches: there is no reason to
    // give, so the rule writes at most a DEBUG line, which this suite's appender does not receive.
    val df = varkaSpark.range(10).selectExpr("id >= 5000000000 AS big")
    val lines = ruleLines(Level.INFO)(df.collect())
    assert(!lines.exists(_._2.contains("Varka left")), lines)
    val range = collectFirst(df.queryExecution.executedPlan) { case r: RangeExec => r }
      .getOrElse(fail(s"no range in the plan:\n${df.queryExecution.executedPlan.treeString}"))
    assert(VarkaColumnarRule.notColumnarReason(range).isEmpty)
  }
}
