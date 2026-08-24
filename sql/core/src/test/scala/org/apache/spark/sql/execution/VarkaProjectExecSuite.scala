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

import org.apache.spark.TaskContext
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, Attribute, AttributeReference, DateAdd, DateDiff, DateSub, Literal, NamedExpression}
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DateType, IntegerType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * Unit tests for [[VarkaProjectExec]] (the columnar-out half of the Varka projection): the SIMD
 * kernels over Arrow `DateDayVector` batches, the materialising fallback that a columnar-out node
 * needs where [[VarkaColumnarToRowExec]] can project rows one by one, and the batch ownership the
 * node owes its consumer.
 *
 * The batch scaffolding - `BatchSpec`, `TestColumnarBatchPlan`, `buildBatch` - is shared with
 * [[VarkaColumnarToRowExecSuite]].
 */
class VarkaProjectExecSuite extends QueryTest with SharedSparkSession {

  private val attrD = AttributeReference("d", DateType)()
  private val attrD2 = AttributeReference("d2", DateType)()
  private val intAttr = AttributeReference("i", IntegerType)()

  private def project(exprs: NamedExpression*): Seq[NamedExpression] = exprs

  /** Runs the node and reads every output batch into plain values, one column. */
  private def values(node: VarkaProjectExec): Seq[Any] = {
    node.executeColumnar().mapPartitions { batches =>
      batches.flatMap { batch =>
        val column = batch.column(0)
        (0 until batch.numRows()).map { i =>
          if (column.isNullAt(i)) null else Int.box(column.getInt(i))
        }.toList.iterator
      }
      // The rows are materialised into a List above: a batch belongs to the node that produced
      // it, so nothing may read it after the next batch is asked for.
    }.collect().toSeq
  }

  private def node(projectList: Seq[NamedExpression], specs: Seq[BatchSpec],
      output: Seq[Attribute]): VarkaProjectExec = {
    VarkaProjectExec(projectList, TestColumnarBatchPlan(specs, output))
  }

  test("the node is columnar and never asked for rows") {
    val plan = node(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()),
      Seq(BatchSpec("arrow", Seq(Seq(Int.box(1))))),
      Seq(attrD))
    assert(plan.supportsColumnar)
    assert(!plan.supportsRowBased)
    intercept[Exception](plan.execute())
  }

  test("date_add, date_sub and date_diff over Arrow batches") {
    val days = Seq(0, 1, 100).map(Int.box)
    assert(values(node(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()),
      Seq(BatchSpec("arrow", Seq(days))), Seq(attrD))) === Seq(3, 4, 103))
    assert(values(node(
      project(Alias(DateSub(attrD, Literal(2)), "sub")()),
      Seq(BatchSpec("arrow", Seq(days))), Seq(attrD))) === Seq(-2, -1, 98))
    assert(values(node(
      project(Alias(DateDiff(attrD2, attrD), "diff")()),
      Seq(BatchSpec("arrow", Seq(days, Seq(10, 10, 10).map(Int.box)))),
      Seq(attrD2, attrD))) === Seq(-10, -9, 90))
  }

  test("null patterns: mixed, all-null and null-free columns") {
    val mixed = Seq(Int.box(1), null, Int.box(3))
    assert(values(node(
      project(Alias(DateAdd(attrD, Literal(1)), "add")()),
      Seq(BatchSpec("arrow", Seq(mixed))), Seq(attrD))) === Seq(2, null, 4))

    val allNull = Seq(null, null, null)
    assert(values(node(
      project(Alias(DateAdd(attrD, Literal(1)), "add")()),
      Seq(BatchSpec("arrow", Seq(allNull))), Seq(attrD))) === Seq(null, null, null))
  }

  test("a non-Arrow batch is materialised by the fallback, not dropped") {
    val plan = node(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()),
      Seq(BatchSpec("onheap", Seq(Seq(Int.box(1), null, Int.box(5))))),
      Seq(attrD))
    assert(values(plan) === Seq(4, null, 8))
  }

  test("an empty batch produces an empty batch") {
    val plan = node(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()),
      Seq(BatchSpec("arrow", Seq(Seq.empty[java.lang.Integer]))),
      Seq(attrD))
    assert(values(plan) === Seq.empty)
  }

  test("an ineligible projection still produces the right batches") {
    // `i + 1` is not a kernel op, so every batch goes through the fallback - the node is only
    // ever planned for eligible projections, but it must not produce wrong data if it is not.
    val plan = node(
      project(Alias(Add(intAttr, Literal(1)), "add")()),
      Seq(BatchSpec("onheap", Seq(Seq(Int.box(100), Int.box(101))))),
      Seq(intAttr))
    assert(values(plan) === Seq(101, 102))
    assert(plan.metrics("numVarkaBatches").value === 0)
  }

  test("an injected kernel failure falls back per batch without crashing") {
    VarkaColumnarToRowExec.setFailKernelForTesting(true)
    try {
      val plan = node(
        project(Alias(DateAdd(attrD, Literal(3)), "add")()),
        Seq(BatchSpec("arrow", Seq(Seq(Int.box(1), null, Int.box(5))))),
        Seq(attrD))
      assert(values(plan) === Seq(4, null, 8))
    } finally {
      VarkaColumnarToRowExec.setFailKernelForTesting(false)
    }
  }

  test("each output batch is released when the next one is requested") {
    val numBatches = 16
    val rowsPerBatch = 512
    val specs = (0 until numBatches).map { b =>
      BatchSpec("arrow", Seq((0 until rowsPerBatch).map(i => Int.box(b * rowsPerBatch + i))))
    }
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val inputs = specs.map(VarkaColumnarToRowExecSuite.buildBatch(_, Seq(attrD), allocator))
      // Everything allocated from here on is the kernel path's own output.
      val baseline = ArrowUtils.rootAllocator.getAllocatedMemory
      val batches = evaluate(inputs.iterator)

      var seen = 0
      var peak = 0L
      batches.foreach { batch =>
        assert(batch.numRows() === rowsPerBatch)
        assert(batch.column(0).getInt(0) === seen * rowsPerBatch + 3)
        seen += 1
        peak = math.max(peak, ArrowUtils.rootAllocator.getAllocatedMemory - baseline)
      }
      assert(seen === numBatches)
      // The iterator is drained, so the last batch was released too - without waiting for the
      // task to complete.
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === baseline,
        "the output batches were not released as the iterator advanced")
      // Only one output batch is live at a time; allow slack for Arrow's power-of-two buffer
      // rounding, but stay far below the numBatches-times figure a leak would reach.
      val oneBatch = 4L * rowsPerBatch
      assert(peak < 4 * oneBatch,
        s"peak Varka off-heap use was $peak bytes for a ${oneBatch}-byte batch")

      inputs.foreach(_.close())
      context.markTaskCompleted(None)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the task-completion listener did not release the Varka child allocator")
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("a consumer that stops early leaves nothing open after the task completes") {
    val specs = Seq(
      BatchSpec("arrow", Seq((0 until 128).map(Int.box))),
      BatchSpec("arrow", Seq((128 until 256).map(Int.box))))
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val inputs = specs.map(VarkaColumnarToRowExecSuite.buildBatch(_, Seq(attrD), allocator))
      val baseline = ArrowUtils.rootAllocator.getAllocatedMemory
      // Take one batch and walk away, like a LIMIT would: it stays open until the task ends.
      val batches = evaluate(inputs.iterator)
      assert(batches.next().numRows() === 128)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory > baseline)

      inputs.foreach(_.close())
      context.markTaskCompleted(None)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the task-completion listener did not release the open Varka batch")
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("metrics count rows and the batches the kernels served") {
    val plan = node(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()),
      Seq(
        BatchSpec("arrow", Seq(Seq(Int.box(1), Int.box(2)))),
        BatchSpec("onheap", Seq(Seq(Int.box(3))))),
      Seq(attrD))
    plan.executeColumnar().foreach(_ => ())
    assert(plan.metrics("numOutputRows").value === 3)
    assert(plan.metrics("numInputBatches").value === 2)
    // Only the Arrow batch reaches the kernels; the on-heap one takes the fallback.
    assert(plan.metrics("numVarkaBatches").value === 1)
  }

  /** Drives the evaluator directly, so a test can control when the next batch is requested. */
  private def evaluate(inputs: Iterator[ColumnarBatch]): Iterator[ColumnarBatch] = {
    val factory = new VarkaProjectEvaluatorFactory(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()),
      Seq(attrD),
      offHeapColumnVectorEnabled = false,
      SQLMetrics.createMetric(sparkContext, "rows"),
      SQLMetrics.createMetric(sparkContext, "batches"),
      SQLMetrics.createMetric(sparkContext, "varkaBatches"))
    factory.createEvaluator().eval(0, inputs)
  }
}
