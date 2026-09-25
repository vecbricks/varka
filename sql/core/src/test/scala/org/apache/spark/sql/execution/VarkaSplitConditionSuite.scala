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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions

/**
 * Filters too large for one method, split across several selection outputs
 * (`splitConditions`, `PLAN_TASK_172.md` 3.1) and combined by the filter: each runs on the row
 * engine and on Varka over the same Arrow-cached rows, nulls and the int extremes included, and
 * must select the same rows with the kernel having run. Range sets are off, so `modified-q3`'s
 * ranges reach the split as the comparisons the query writes.
 */
class VarkaSplitConditionSuite extends QueryTest with VarkaSharedSessions {

  private def cacheKeys(session: SparkSession): Unit = {
    val values: Seq[Integer] = Seq[Integer](null, Int.MinValue, Int.MinValue + 1, -5, -1, 0, 1,
      4, 5, 6, 9, 10, 11, 99, 100, 101, Int.MaxValue - 1, Int.MaxValue) ++
      (0 until 3000).map(i => Integer.valueOf(2415022 + (i * 7919) % 73049)) ++
      Seq.fill[Integer](40)(null)
    session.createDataFrame(values.zipWithIndex.map { case (v, i) => (v, i) })
      .toDF("k", "i")
      .createOrReplaceTempView("varka_split_keys")
    session.catalog.cacheTable("varka_split_keys")
  }

  /** The query on both sessions: the same rows, and on Varka a filter kernel that ran. */
  private def check(where: String): Unit = {
    val query = s"SELECT i FROM varka_split_keys WHERE $where"
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, spark.sql(query))
    assertKernelsRan(plan)
  }

  private def withKeys(body: => Unit): Unit = {
    VarkaColumnarToRowExec.setEmitOptionsForTesting(
      VarkaEmitOptions.DEFAULTS.withRangeSets(false).withSplitConditions(true))
    try {
      cacheKeys(spark)
      cacheKeys(varkaSpark)
      try body finally {
        Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_split_keys"))
      }
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS)
    }
  }

  test("modified-q3's ranges, split into partial disjunctions, select what the row engine does") {
    withKeys {
      check(VarkaQ3Ranges.predicate(200, "k"))
      check(VarkaQ3Ranges.predicate(49, "k"))
      check(s"i >= 100 and (${VarkaQ3Ranges.predicate(200, "k")})")
    }
  }

  test("a disjunction over two columns, which is no range set") {
    withKeys {
      check((0 until 150).map(j => s"(k = ${2415022 + j * 487} and i >= $j)").mkString(" or "))
    }
  }

  test("a long conjunction, split into several conjunction roots") {
    withKeys {
      check((0 until 300).map(j => s"k <> ${2415022 + j * 211}").mkString(" and "))
    }
  }

  test("both at once: conjunction roots beside a split disjunction, at the int extremes") {
    withKeys {
      val ranges = VarkaQ3Ranges.predicate(120, "k")
      check(s"k <> ${Int.MinValue} and k <> ${Int.MaxValue} and " +
        (0 until 150).map(j => s"i <> ${j * 13}").mkString(" and ") + s" and ($ranges or k < 5)")
    }
  }
}
