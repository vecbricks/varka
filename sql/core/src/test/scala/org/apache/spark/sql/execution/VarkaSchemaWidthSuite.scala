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

import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec

/**
 * The schema-width cliff at the cache (task 185, the census's G3). Whether an
 * `InMemoryTableScanExec` produces columnar batches is decided by
 * `spark.sql.codegen.maxFields` (100) counted over the whole cached relation's schema, not over
 * the columns a query reads, and Varka's rule rewrites a projection or a filter only over a
 * columnar child. Without [[VarkaCacheScanExec]] a cached table of more than a hundred columns
 * would give Varka nothing to fuse, whatever the query reads, even under Varka's own Arrow
 * serializer, which produces batches at any width.
 *
 * The first test pins Spark's side at 100 and 101 columns. The rest check Varka's answer,
 * [[VarkaCacheScanExec]]: where the scan is kept from columnar output by its width alone, a
 * projection or a filter Varka fuses reads the same cached batches as columns and answers as the
 * row engine does, and a query Varka does not fuse keeps the scan as it was.
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

  test("G3: the cache scan is columnar over 100 fields and produces rows over 101") {
    for ((n, columnar) <- Seq(100 -> true, 101 -> false)) {
      withWide(n) { name =>
        val plan = spark.sql(s"SELECT date_add(d, 1) FROM $name").queryExecution.executedPlan
        assert(cacheScan(plan).supportsColumnar == columnar, s"$n fields")
      }
    }
  }

  /** The query on both sessions: the same rows, and on Varka a kernel that ran. */
  private def checkFused(query: String): SparkPlan = {
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, spark.sql(query))
    assertKernelsRan(plan)
    plan
  }

  private def wideScans(plan: SparkPlan): Seq[VarkaCacheScanExec] =
    collect(plan) { case w: VarkaCacheScanExec => w }

  test("Varka fuses a projection over a cache of 100 fields and of 101, as it reads one column") {
    withWide(100) { name =>
      // Columnar already: the scan is used as it is.
      assert(wideScans(checkFused(s"SELECT date_add(d, 1) AS e FROM $name")).isEmpty)
    }
    withWide(101) { name =>
      // The scan reads rows because of the other hundred columns; Varka reads the same cached
      // batches as columns through the wide scan, which keeps the scan's pruning and metrics.
      val plan = checkFused(s"SELECT date_add(d, 1) AS e FROM $name")
      assert(wideScans(plan).size == 1, plan.treeString)
    }
  }

  test("Varka fuses a filter over a cache of 101 fields, and a filter with a projection") {
    withWide(101) { name =>
      assert(wideScans(checkFused(
        s"SELECT i1 FROM $name WHERE d > date'2025-01-01'")).size == 1)
      assert(wideScans(checkFused(
        s"SELECT date_add(d, 7) AS e, i2 FROM $name WHERE d < date'2030-01-01'")).size == 1)
    }
  }

  test("a query Varka does not fuse keeps the cache scan as it was, over 101 fields") {
    withWide(101) { name =>
      val query = s"SELECT cast(i1 AS string) AS s FROM $name"
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertNotFused(plan)
      assert(wideScans(plan).isEmpty)
      checkAnswer(actual, spark.sql(query))
    }
  }

  test("the cache stays cached: a second query over the wide cache reads the same batches") {
    withWide(101) { name =>
      checkFused(s"SELECT date_add(d, 1) AS e FROM $name")
      checkFused(s"SELECT date_add(d, 2) AS e FROM $name WHERE i3 IS NOT NULL OR d IS NULL")
      assert(varkaSpark.table(name).queryExecution.withCachedData.collectFirst {
        case r: org.apache.spark.sql.execution.columnar.InMemoryRelation => r
      }.exists(_.cacheBuilder.isCachedColumnBuffersLoaded))
    }
  }
}
