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

package org.apache.spark.sql.execution.datasources.noop

import org.apache.spark.sql.classic.DataFrame
import org.apache.spark.sql.execution.{ColumnarToRowTransition, QueryExecution, SparkPlan}
import org.apache.spark.sql.execution.datasources.v2.V2TableWriteExec
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.util.QueryExecutionListener

class NoopSuite extends SharedSparkSession {
  import testImplicits._

  /**
   * Appends `df` to the noop sink and returns the executed plan of that write, captured through
   * a [[QueryExecutionListener]] because a write returns no DataFrame to inspect.
   */
  private def writeToNoop(df: DataFrame): SparkPlan = {
    @volatile var executedPlan: SparkPlan = null
    val listener = new QueryExecutionListener {
      override def onSuccess(funcName: String, qe: QueryExecution, durationNs: Long): Unit = {
        executedPlan = qe.executedPlan
      }
      override def onFailure(funcName: String, qe: QueryExecution, e: Exception): Unit = {}
    }
    spark.listenerManager.register(listener)
    try {
      df.write.format("noop").mode("append").save()
      sparkContext.listenerBus.waitUntilEmpty()
    } finally {
      spark.listenerManager.unregister(listener)
    }
    assert(executedPlan != null, "the write did not report a query execution")
    executedPlan
  }

  private def writeNode(plan: SparkPlan): V2TableWriteExec = {
    plan.collectFirst { case w: V2TableWriteExec => w }
      .getOrElse(fail(s"no V2TableWriteExec in the executed plan:\n$plan"))
  }

  test("materialisation of all rows") {
    val numElems = 10
    val accum = spark.sparkContext.longAccumulator
    spark.range(numElems)
      .map { x =>
        accum.add(1)
        x
      }
      .write
      .format("noop")
      .mode("append")
      .save()
    assert(accum.value == numElems)
  }

  test("a columnar plan is written as batches, with no to-row transition") {
    val numElems = 100
    withTempPath { dir =>
      val path = dir.getCanonicalPath
      spark.range(numElems).write.parquet(path)

      withSQLConf(SQLConf.PARQUET_VECTORIZED_READER_ENABLED.key -> "true") {
        val plan = writeToNoop(spark.read.parquet(path))
        val write = writeNode(plan)
        assert(write.supportsColumnarWrite, "the noop write must declare columnar support")
        assert(write.child.supportsColumnar,
          s"the plan under the write was made row-based:\n$plan")
        assert(plan.find(_.isInstanceOf[ColumnarToRowTransition]).isEmpty,
          s"a to-row transition was inserted anyway:\n$plan")
        assert(write.metrics("numOutputRows").value == numElems)
      }
    }
  }

  test("a columnar write counts rows, not batches") {
    val numElems = 100
    val batchSize = 10
    withTempPath { dir =>
      val path = dir.getCanonicalPath
      // One file, so the row count is split into batches rather than into partitions.
      spark.range(numElems).repartition(1).write.parquet(path)

      withSQLConf(
        SQLConf.PARQUET_VECTORIZED_READER_ENABLED.key -> "true",
        SQLConf.PARQUET_VECTORIZED_READER_BATCH_SIZE.key -> batchSize.toString) {
        val plan = writeToNoop(spark.read.parquet(path))
        val write = writeNode(plan)
        assert(write.child.supportsColumnar, s"this test needs the columnar path:\n$plan")
        // The batch count is numElems / batchSize, which is what a records-not-rows count
        // would report here.
        assert(write.metrics("numOutputRows").value == numElems)
      }
    }
  }

  test("a row-based plan still writes rows into the same sink") {
    val numElems = 10
    val accum = spark.sparkContext.longAccumulator
    val plan = writeToNoop(spark.range(numElems).map { x =>
      accum.add(1)
      x
    }.toDF())
    val write = writeNode(plan)
    assert(write.supportsColumnarWrite, "the noop write declares columnar support either way")
    assert(!write.child.supportsColumnar, s"this plan is row-based:\n$plan")
    assert(accum.value == numElems, "the rows must still reach the sink")
    assert(write.metrics("numOutputRows").value == numElems)
  }

  test("read partitioned data") {
    val numElems = 100
    withTempPath { dir =>
      val path = dir.getCanonicalPath
      spark.range(numElems)
        .select($"id" mod 10 as "key", $"id" as "value")
        .write
        .partitionBy("key")
        .parquet(path)

      val accum = spark.sparkContext.longAccumulator
      spark.read
        .parquet(path)
        .as[(Long, Long)]
        .map { x =>
          accum.add(1)
          x
        }
        .write.mode("append").format("noop").save()
      assert(accum.value == numElems)
    }
  }
}

