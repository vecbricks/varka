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
 * columnar child. So a cached table of more than a hundred columns gives Varka nothing to fuse,
 * whatever the query reads, even under Varka's own Arrow serializer, which could produce batches
 * at any width.
 *
 * These tests pin that behaviour at 100 and 101 columns: the cache scan's columnar output, and
 * whether the Varka session fuses `date_add(d, 1)` over the one date column. They are the
 * baseline of the fix `PLAN_TASK_185.md` 3.2 plans, which should turn the 101-column Varka test
 * around.
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

  test("today Varka fuses over a cache of 100 fields and has no node over 101") {
    withWide(100) { name =>
      val query = s"SELECT date_add(d, 1) AS e FROM $name"
      val actual = varkaSpark.sql(query)
      assertFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, spark.sql(query))
      assertKernelsRan(actual.queryExecution.executedPlan)
    }
    withWide(101) { name =>
      val query = s"SELECT date_add(d, 1) AS e FROM $name"
      val actual = varkaSpark.sql(query)
      // The query reads one column of 101; the cache scan produces rows because of the other
      // hundred, and the rule leaves the projection to Spark without a reason.
      assertNotFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, spark.sql(query))
    }
  }
}
