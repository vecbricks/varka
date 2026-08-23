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

import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.classic.{SparkSession => ClassicSparkSession}
import org.apache.spark.sql.execution.columnar.{ArrowCachedBatchSerializer, InMemoryRelation}
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Task 8: config-driven activation of Varka. [[VarkaColumnarRule]] is registered by
 * `BaseSessionStateBuilder.columnarRules`, so a user enables Varka purely with
 * `spark.sql.codegen.varka.enabled` - no manual extension injection. This suite builds its
 * session through the public builder (unlike the base test session) and asserts that an eligible
 * projection is fused into [[VarkaColumnarToRowExec]] and runs the SIMD kernels only when the
 * config is on, and that the rule is inert otherwise.
 *
 * The registration point matters as much as the registration: the rule used to be injected into
 * the `SparkSessionExtensions` inside `SparkSession.Builder.getOrCreate`, which skipped sessions
 * built any other way, registered twice when `getOrCreate` was called twice on one builder, and
 * mutated a user-supplied extensions object. The last three tests pin those down.
 */
class VarkaAutoRegistrationSuite extends QueryTest with SharedSparkSession {

  private var session: SparkSession = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    InMemoryRelation.clearSerializer()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    // Built through the public builder with no `withExtensions`: auto-registration must make the
    // rule available, and it is inert unless the config is set.
    session = SparkSession.builder()
      .sparkContext(spark.sparkContext)
      .config(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
        classOf[ArrowCachedBatchSerializer].getName)
      .config(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
      .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
      .config(SQLConf.SHUFFLE_PARTITIONS.key, "1")
      .getOrCreate()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
  }

  override def afterAll(): Unit = {
    InMemoryRelation.clearSerializer()
    session = null
    super.afterAll()
  }

  private def date(value: String): java.sql.Date = java.sql.Date.valueOf(value)

  private def cacheDates(): Unit = {
    val dates = Seq(date("2024-01-01"), date("2024-01-02"), date("2023-12-27"),
      date("1969-12-31"), null).zipWithIndex
    session.createDataFrame(dates.map { case (d, i) => (d, i) }).toDF("d", "i")
      .createOrReplaceTempView("varka_dates")
    session.catalog.cacheTable("varka_dates")
  }

  private def varkaRuleCount(s: SparkSession): Int =
    s.sessionState.columnarRules.count(_ eq VarkaColumnarRule)

  private def withVarkaEnabled(enabled: Boolean)(f: => Unit): Unit = {
    session.conf.set(SQLConf.VARKA_ENABLED.key, enabled.toString)
    try f finally session.conf.unset(SQLConf.VARKA_ENABLED.key)
  }

  test("spark.sql.codegen.varka.enabled defaults to false") {
    val conf = session.sessionState.conf
    assert(!conf.varkaEnabled)
    assert(conf.getConf(SQLConf.VARKA_ENABLED) === false)
  }

  test("varka.enabled=true fuses the projection and runs the kernels, with no manual injection") {
    cacheDates()
    withVarkaEnabled(enabled = true) {
      val df = session.sql("SELECT date_add(d, 3) AS a FROM varka_dates ORDER BY a")
      val plan = df.queryExecution.executedPlan
      // The rule is auto-registered by the SparkSession builder, so an eligible projection fuses.
      val node = plan.collectFirst { case v: VarkaColumnarToRowExec => v }
        .getOrElse(fail(s"expected a fused VarkaColumnarToRowExec:\n${plan.treeString}"))
      // date_add matches the row engine (null sorts first for ASC).
      checkAnswer(df, Seq(
        Row(null),
        Row(date("1970-01-03")),
        Row(date("2023-12-30")),
        Row(date("2024-01-04")),
        Row(date("2024-01-05"))))
      assert(node.metrics("numVarkaBatches").value > 0,
        "the SIMD kernels must process the cached Arrow batches")
    }
  }

  test("a cloned session gets the rule exactly once") {
    val cloned = session.asInstanceOf[ClassicSparkSession].cloneSession()
    assert(varkaRuleCount(cloned) === 1, "a cloned session must carry one VarkaColumnarRule")
    cacheDates()
    cloned.conf.set(SQLConf.VARKA_ENABLED.key, "true")
    try {
      val plan = cloned.sql("SELECT date_add(d, 3) AS a FROM varka_dates ORDER BY a")
        .queryExecution.executedPlan
      assert(plan.find(_.isInstanceOf[VarkaColumnarToRowExec]).isDefined,
        s"expected a fused plan on the cloned session:\n${plan.treeString}")
    } finally {
      cloned.conf.unset(SQLConf.VARKA_ENABLED.key)
    }
  }

  test("a builder reused for a second session does not register the rule twice") {
    // The classic builder holds one `SparkSessionExtensions` for its whole lifetime, so it is the
    // shape that exposes a per-build injection. (`org.apache.spark.sql.SparkSession.builder()`
    // makes a fresh classic builder per `getOrCreate`, and so hides it.)
    val builder = ClassicSparkSession.builder()
      .sparkContext(spark.sparkContext)
      .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    try {
      val first = builder.getOrCreate()
      assert(varkaRuleCount(first) === 1, "the first session must carry one VarkaColumnarRule")
      // Dropping the default session makes the same builder - and so the same extensions object -
      // build a second session. Injecting the rule per build would give this one two copies.
      SparkSession.clearActiveSession()
      SparkSession.clearDefaultSession()
      val second = builder.getOrCreate()
      assert(varkaRuleCount(second) === 1, "the second session must carry one VarkaColumnarRule")
    } finally {
      SparkSession.clearActiveSession()
      SparkSession.clearDefaultSession()
    }
  }

  test("building a session does not mutate its SparkSessionExtensions") {
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    try {
      val built = SparkSession.builder()
        .sparkContext(spark.sparkContext)
        .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
        .getOrCreate()
        .asInstanceOf[ClassicSparkSession]
      assert(varkaRuleCount(built) === 1, "the built session must carry one VarkaColumnarRule")
      // `spark.extensions` must list only what the user added: the built-in rule comes from the
      // session state, not from an extensions object Spark mutated behind the user's back.
      assert(!built.extensions.buildColumnarRules(built).exists(_ eq VarkaColumnarRule),
        "the user-visible extensions must not carry the built-in Varka rule")
    } finally {
      SparkSession.clearActiveSession()
      SparkSession.clearDefaultSession()
    }
  }

  test("varka.enabled=false leaves the plan untouched") {
    cacheDates()
    withVarkaEnabled(enabled = false) {
      val df = session.sql("SELECT date_add(d, 3) AS a FROM varka_dates ORDER BY a")
      assert(df.queryExecution.executedPlan.find(_.isInstanceOf[VarkaColumnarToRowExec]).isEmpty,
        "the rule must be inert when the config is off:\n" +
          df.queryExecution.executedPlan.treeString)
      assert(df.collect().length == 5, "the row engine must still produce all cached rows")
    }
  }
}
