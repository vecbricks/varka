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
import org.apache.spark.sql.execution.datasources.v2.V2TableWriteExec
import org.apache.spark.sql.util.QueryExecutionListener

/**
 * What the Varka projection turns into depends on who consumes it. A consumer that wants rows
 * gets [[VarkaColumnarToRowExec]], exactly as before the projection was split in two; a consumer
 * that takes batches - here a `noop` write, whose connector declares `supportsColumnarWrite` -
 * gets [[VarkaProjectExec]] and the kernels' own Arrow batches, with no to-row transition in
 * between.
 *
 * The sessions come from [[VarkaSharedSessions]], so the source is a real Arrow-backed
 * `InMemoryTableScanExec`.
 */
class VarkaColumnarWriteSuite extends QueryTest with VarkaSharedSessions {

  private val query = "SELECT date_add(d, 3) AS a FROM varka_dates"

  /** Writes to `noop` and returns the executed plan, which a write does not hand back itself. */
  private def writeToNoop(session: SparkSession, sql: String): SparkPlan = {
    @volatile var executedPlan: SparkPlan = null
    val listener = new QueryExecutionListener {
      override def onSuccess(funcName: String, qe: QueryExecution, durationNs: Long): Unit = {
        executedPlan = qe.executedPlan
      }
      override def onFailure(funcName: String, qe: QueryExecution, e: Exception): Unit = {}
    }
    session.listenerManager.register(listener)
    try {
      session.sql(sql).write.format("noop").mode("append").save()
      session.sparkContext.listenerBus.waitUntilEmpty()
    } finally {
      session.listenerManager.unregister(listener)
    }
    assert(executedPlan != null, "the write did not report a query execution")
    executedPlan
  }

  private def writeNode(plan: SparkPlan): V2TableWriteExec = {
    plan.collectFirst { case w: V2TableWriteExec => w }
      .getOrElse(fail(s"no V2TableWriteExec in the executed plan:\n${plan.treeString}"))
  }

  test("a row consumer still gets the fused node and no columnar-out projection") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    val actual = varkaSpark.sql(query + " ORDER BY a")
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    assert(plan.find(_.isInstanceOf[VarkaProjectExec]).isEmpty,
      s"the columnar-out node was left in a plan whose consumer wants rows:\n${plan.treeString}")
    checkAnswer(actual, spark.sql(query + " ORDER BY a"))
    assertKernelsRan(plan)
  }

  test("a columnar-capable write consumes the kernel batches, with no to-row transition") {
    cacheDates(varkaSpark)
    val plan = writeToNoop(varkaSpark, query)
    val write = writeNode(plan)
    assert(write.supportsColumnarWrite, "the noop write must declare columnar support")
    assert(write.child.supportsColumnar,
      s"the plan under the write was made row-based:\n${plan.treeString}")
    assert(write.child.isInstanceOf[VarkaProjectExec],
      s"the write does not consume the Varka projection's batches:\n${plan.treeString}")
    assert(plan.find(_.isInstanceOf[ColumnarToRowTransition]).isEmpty,
      s"a to-row transition was inserted anyway:\n${plan.treeString}")
  }

  test("a columnar write reports the rows written and runs the kernels over every batch") {
    cacheDates(varkaSpark)
    val expected = varkaSpark.sql("SELECT count(*) FROM varka_dates").head().getLong(0)
    val plan = writeToNoop(varkaSpark, query)
    val write = writeNode(plan)
    assert(write.metrics("numOutputRows").value === expected)
    val varka = plan.collectFirst { case v: VarkaProjectExec => v }
      .getOrElse(fail(s"no VarkaProjectExec in the executed plan:\n${plan.treeString}"))
    assert(varka.metrics("numInputBatches").value > 0)
    assert(varka.metrics("numVarkaBatches").value === varka.metrics("numInputBatches").value,
      "some batches did not reach the SIMD kernels")
    assert(varka.metrics("numOutputRows").value === expected)
  }

  test("an ineligible projection is written as rows, as it was before") {
    cacheDates(varkaSpark)
    val plan = writeToNoop(varkaSpark, "SELECT date_add(d, i) AS a FROM varka_dates")
    val write = writeNode(plan)
    assert(!write.child.supportsColumnar,
      s"a projection the kernels cannot serve took the columnar path:\n${plan.treeString}")
    assert(plan.find(_.isInstanceOf[VarkaProjectExec]).isEmpty)
  }

  test("the varka config gate keeps the write on the row path when disabled") {
    cacheDates(disabledSpark)
    val plan = writeToNoop(disabledSpark, query)
    assert(plan.find(_.isInstanceOf[VarkaProjectExec]).isEmpty,
      s"the Varka node appeared with the config off:\n${plan.treeString}")
  }
}
