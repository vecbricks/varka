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

import java.time.LocalTime

import org.apache.spark.SparkIllegalArgumentException
import org.apache.spark.sql.{QueryTest, Row}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaFamily, VarkaTestWatchdog}
import org.apache.spark.sql.types.{DateType, IntegerType, StructField, StructType, TimeType}

/**
 * The kill switch of each Varka expression family, set as a user sets it (VARKA-318): with
 * `spark.sql.codegen.varka.family.<name>.enabled` false, the entry of that family runs on
 * Spark's row engine with the answer Spark gives, the verbose EXPLAIN names the switch as the
 * entry's reason, and an entry of another family in the same projection still fuses - so the
 * setting reaches both the driver's compile and the task's. A failure inside a family at
 * planning names the switch, and setting it is the retry that plans the query.
 */
class VarkaFamilySwitchQuerySuite
    extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

  private val view = "varka_family_switches"

  private def cacheView(): Unit = {
    val schema = StructType(Seq(StructField("d", DateType), StructField("i", IntegerType),
      StructField("t", TimeType(6))))
    val rows = Seq(
      Row(date("2024-01-01"), 1, LocalTime.of(9, 30)),
      Row(date("2024-02-29"), -4, LocalTime.of(23, 59, 59)),
      Row(date("1969-12-31"), 7, LocalTime.MIDNIGHT),
      Row(null, null, null))
    Seq(spark, varkaSpark).foreach { session =>
      session.createDataFrame(session.sparkContext.parallelize(rows, 1), schema)
        .createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
    }
  }

  /** Each family, a projection whose entry `a` is that family's, and `b` another family's. */
  private val cases = Seq(
    VarkaFamily.CALENDAR -> "SELECT year(d) AS a, i + 1 AS b",
    VarkaFamily.INT_ARITHMETIC -> "SELECT i + 1 AS a, year(d) AS b",
    VarkaFamily.INTERVAL -> "SELECT make_ym_interval(year(d), month(d)) AS a, i + 1 AS b",
    VarkaFamily.CONDITION -> "SELECT coalesce(d, DATE'2000-01-01') AS a, year(d) AS b",
    // The comparison in a conditional's condition is the predicate family's, wherever it sits.
    VarkaFamily.PREDICATE ->
      "SELECT if(d > DATE'2000-01-01', d, DATE'2000-01-01') AS a, year(d) AS b",
    VarkaFamily.TIME -> "SELECT hour(t) AS a, year(d) AS b",
    VarkaFamily.INTEGRAL_CAST -> "SELECT CAST(i AS TINYINT) AS a, year(d) AS b")

  test("each family switched off runs its entry on the row engine, named in the verbose " +
      "EXPLAIN, while the other entry fuses") {
    cacheView()
    for ((family, select) <- cases) {
      val query = s"$select FROM $view"
      withBothConf(family.switchKey(), "false") {
        val df = varkaSpark.sql(query)
        checkAnswer(df, spark.sql(query).collect().toSeq)
        val explained = df.queryExecution.explainString(ExplainMode.fromString("formatted"))
        assert(explained.contains(s"a: residual (${family.offReason()}: "), explained)
        assert(explained.contains("b: fused"), explained)
        assertFused(df.queryExecution.executedPlan)
      }
      // Switched back on, the family's entry fuses again.
      val explained = varkaSpark.sql(query).queryExecution
        .explainString(ExplainMode.fromString("formatted"))
      assert(explained.contains("a: fused"), s"$family\n$explained")
    }
  }

  test("with the predicate family off no filter fuses, and the answer is Spark's; with the " +
      "condition family off the filter still fuses") {
    cacheView()
    val query = s"SELECT d, i FROM $view WHERE d > DATE'2000-01-01'"
    assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
    withBothConf(VarkaFamily.PREDICATE.switchKey(), "false") {
      val df = varkaSpark.sql(query)
      checkAnswer(df, spark.sql(query).collect().toSeq)
      assertNotFused(df.queryExecution.executedPlan)
    }
    withBothConf(VarkaFamily.CONDITION.switchKey(), "false") {
      val df = varkaSpark.sql(query)
      checkAnswer(df, spark.sql(query).collect().toSeq)
      assertFused(df.queryExecution.executedPlan)
    }
  }

  test("a failure inside a family at planning names its switch, and setting it plans the query") {
    cacheView()
    val query = s"SELECT year(d) AS a, i + 1 AS b FROM $view"
    VarkaExpressionCompiler.setFailInFamilyForTesting(VarkaFamily.CALENDAR)
    try {
      val e = intercept[SparkIllegalArgumentException](varkaSpark.sql(query).collect())
      assert(e.getCondition === "INTERNAL_ERROR")
      assert(e.getMessage.contains(VarkaFamily.CALENDAR.retry()), e.getMessage)
      assert(e.getMessage.contains(
        s"\n  families: calendar (${VarkaFamily.CALENDAR.switchKey()})\n"), e.getMessage)
      // The retry the message asks for: the calendar off, the query plans and answers.
      withBothConf(VarkaFamily.CALENDAR.switchKey(), "false") {
        val df = varkaSpark.sql(query)
        checkAnswer(df, spark.sql(query).collect().toSeq)
        assertFused(df.queryExecution.executedPlan)
      }
    } finally {
      VarkaExpressionCompiler.setFailInFamilyForTesting(null)
    }
  }
}
