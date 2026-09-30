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
 * The evaluator's side of several kernels per projection (`VarkaEmitOptions.severalKernels`,
 * `PLAN_TASK_190.md` 11): a projection past what one kernel serves - more columns than a kernel
 * reads, or a driver past the byte budget - runs its kernels in turn over each batch, and must
 * answer as the row engine does over the same Arrow-cached data, nulls included, with forwarded
 * and row-evaluated entries beside the kernels' columns.
 */
class VarkaSeveralKernelsSuite extends QueryTest with VarkaSharedSessions {

  private val view = "varka_several_kernels"
  private val columns = 70

  private def cacheWideDates(session: SparkSession): Unit = {
    val rows = (0 until 5000).map { i =>
      val dates = (0 until columns).map { c =>
        if ((i + c) % 13 == 0) null
        else java.sql.Date.valueOf(LocalDate.ofEpochDay((i.toLong * 7919 + c * 31) % 73049 - 36524))
      }
      org.apache.spark.sql.Row.fromSeq(dates :+ i)
    }
    val schema = org.apache.spark.sql.types.StructType(
      (0 until columns).map(c => org.apache.spark.sql.types.StructField(s"c$c",
        org.apache.spark.sql.types.DateType)) :+
        org.apache.spark.sql.types.StructField("i", org.apache.spark.sql.types.IntegerType))
    session.createDataFrame(session.sparkContext.parallelize(rows, 2), schema)
      .createOrReplaceTempView(view)
    session.catalog.cacheTable(view)
  }

  /** The query on both sessions: the same rows, and on Varka two kernels that ran. */
  private def check(options: VarkaEmitOptions, query: String): Unit = {
    VarkaColumnarToRowExec.setEmitOptionsForTesting(options)
    try {
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      assert(actual.queryExecution.explainString(FormattedMode).contains("kernel 2 of 2"),
        actual.queryExecution.explainString(FormattedMode))
      checkAnswer(actual, spark.sql(query))
      assertKernelsRan(plan)
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS)
    }
  }

  private def withWideDates(f: => Unit): Unit = {
    cacheWideDates(spark)
    cacheWideDates(varkaSpark)
    try f finally Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable(view))
  }

  test("seventy date columns, past the sixty-four a kernel reads, answer from two kernels") {
    withWideDates {
      val outputs = (0 until columns).map(c => s"date_add(c$c, ${c % 9 - 4}) AS a$c") :+
        "c3" :+ "i" :+ "concat(cast(i AS STRING), 'x') AS s"
      check(VarkaEmitOptions.DEFAULTS.withSeveralKernels(true),
        s"SELECT ${outputs.mkString(", ")} FROM $view")
    }
  }

  test("two hundred greatest entries, past the unrolled driver's ceiling, answer from two " +
      "kernels") {
    withWideDates {
      val outputs = (1 to 200).map { k =>
        s"greatest(add_months(c0, $k), date_add(c0, $k), last_day(c0)) AS g$k"
      } :+ "i"
      check(VarkaEmitOptions.DEFAULTS.withDriverOutputTable(false).withSeveralKernels(true),
        s"SELECT ${outputs.mkString(", ")} FROM $view")
    }
  }
}
