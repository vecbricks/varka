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

import java.time.LocalDate

import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions

/**
 * The evaluator's side of a materialized calendar prefix (VARKA-198, `VARKA-198.md` 8.3): a
 * projection whose calendar outputs the emitter splits across loop-method groups runs on the
 * row engine and on Varka over the same Arrow-cached dates, nulls included, and must answer the
 * same with the kernel having run. Such a kernel takes a scratch address per batch, which the
 * evaluator allocates from the task's Arrow allocator, grows to the largest batch it sees and
 * releases at task completion; the cached table has a partition of one small batch and one of a
 * large batch followed by a smaller, so one task allocates once and reuses, the other allocates
 * a smaller buffer, and both release.
 */
class VarkaMaterializedPrefixSuite extends QueryTest with VarkaSharedSessions {

  private val view = "varka_dates_materialized"

  private def cacheMaterializedDates(session: SparkSession): Unit = {
    def rows(n: Int, seed: Int): Seq[(java.sql.Date, Int)] = (0 until n).map { i =>
      val d = if ((i + seed) % 17 == 0) {
        null
      } else {
        java.sql.Date.valueOf(LocalDate.ofEpochDay((i.toLong * 7919 + seed) % 73049 - 36524))
      }
      (d, i)
    }
    val sc = session.sparkContext
    val partitions = sc.parallelize(rows(1000, 1), 1).union(sc.parallelize(rows(11000, 2), 1))
    session.createDataFrame(partitions).toDF("d", "i").createOrReplaceTempView(view)
    session.catalog.cacheTable(view)
  }

  /** The query on both sessions under `options`: the same rows, and on Varka a kernel that ran. */
  private def check(options: VarkaEmitOptions, query: String): Unit = {
    VarkaColumnarToRowExec.setEmitOptionsForTesting(options)
    try {
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, spark.sql(query))
      assertKernelsRan(plan)
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS)
    }
  }

  test("sixty make_date over a nullable date, split across groups, answer as the row engine") {
    cacheMaterializedDates(spark)
    cacheMaterializedDates(varkaSpark)
    try {
      // The shared-prefix benchmark's shape: one date, decomposed by every output.
      val outputs = (1 to 60).map { k =>
        val day = (k - 1) % 28 + 1
        val offset = (k - 1) / 28
        s"make_date(year(d) + $offset, month(d), $day) AS c$k"
      } :+ "year(d) + 1 AS y" :+ "i"
      val query = s"SELECT ${outputs.mkString(", ")} FROM $view"
      val on = VarkaEmitOptions.DEFAULTS.withMaterializeChronoPrefix(true)
      check(on, query)
      // Every output its own group: one producer and sixty consumers of the stored prefix.
      check(on.withGroupBudget(1).withFusedCeiling(1), query)
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable(view))
    }
  }
}
