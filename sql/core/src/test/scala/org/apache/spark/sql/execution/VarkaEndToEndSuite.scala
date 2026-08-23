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

import org.apache.spark.sql.QueryTest

/**
 * End-to-end tests for the Varka columnar execution path (Task 6). The three sessions are set up
 * by [[VarkaSharedSessions]]: data is cached with the Arrow serializer and the vectorized reader
 * so that `InMemoryTableScanExec` feeds real Arrow `DateDayVector` batches into a columnar-to-row
 * transition, mirroring a production columnar scan. The varka session (rule injected and
 * `spark.sql.codegen.varka.enabled` set) must fuse eligible projections into
 * [[VarkaColumnarToRowExec]] and produce results identical to the row-based engine, while
 * ineligible projections and the disabled config must leave the plan untouched.
 */
class VarkaEndToEndSuite extends QueryTest with VarkaSharedSessions {

  test("date_add and date_sub over a cached Arrow source are fused and match the row engine") {
    cacheDates(spark)
    val query =
      "SELECT date_add(d, 3) AS a, date_sub(d, 5) AS b FROM varka_dates ORDER BY a"
    val expected = spark.sql(query)
    cacheDates(varkaSpark)
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, expected)
    assertKernelsRan(plan)
  }

  test("datediff over a cached Arrow source is fused and matches the row engine") {
    cacheDatePairs(spark)
    val query = "SELECT datediff(d2, d) AS diff FROM varka_date_pairs ORDER BY diff"
    val expected = spark.sql(query)
    cacheDatePairs(varkaSpark)
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, expected)
    assertKernelsRan(plan)
  }

  test("a non-foldable offset is not fused but still returns correct results") {
    cacheDates(spark)
    val query = "SELECT date_add(d, i) AS a FROM varka_dates ORDER BY a"
    val expected = spark.sql(query)
    cacheDates(varkaSpark)
    val actual = varkaSpark.sql(query)
    assertNotFused(actual.queryExecution.executedPlan)
    checkAnswer(actual, expected)
  }

  test("the varka config gate keeps the plan untouched when disabled") {
    cacheDates(spark)
    val query = "SELECT date_add(d, 3) AS a FROM varka_dates ORDER BY a"
    val expected = spark.sql(query)
    cacheDates(disabledSpark)
    val actual = disabledSpark.sql(query)
    assertNotFused(actual.queryExecution.executedPlan)
    checkAnswer(actual, expected)
  }
}
