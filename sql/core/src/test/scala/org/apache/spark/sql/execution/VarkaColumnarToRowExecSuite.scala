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

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.DateDayVector

import org.apache.spark.{Partition, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{QueryTest, Row}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, Ascending, Attribute, AttributeReference, DateAdd, DateDiff, DateSub, Literal, NamedExpression, SortOrder}
import org.apache.spark.sql.catalyst.expressions.codegen.ClassFileGenOp
import org.apache.spark.sql.catalyst.plans.physical.{HashPartitioning, Partitioning, UnknownPartitioning}
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DateType, IntegerType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch}

/**
 * Unit tests for [[VarkaColumnarToRowExec]] (Task 6): the SIMD kernel path over Arrow
 * `DateDayVector` batches, the per-row fallback for non-Arrow / ineligible inputs, and the
 * per-batch fallback on an injected kernel failure.
 *
 * The child plan serves batches built lazily inside the task from a serializable spec
 * (`ColumnarBatch` and Arrow vectors are not serializable), like a real columnar scan would.
 */
class VarkaColumnarToRowExecSuite extends QueryTest with SharedSparkSession {

  private val attrD = AttributeReference("d", DateType)()
  private val attrD2 = AttributeReference("d2", DateType)()
  private val intAttr = AttributeReference("i", IntegerType)()

  private def run(node: VarkaColumnarToRowExec): Array[Row] = {
    node.executeCollect().map(row => Row(row.toSeq(node.schema): _*))
  }

  private def values(rows: Array[Row]): Seq[Any] =
    rows.map(r => if (r.isNullAt(0)) null else r.getInt(0)).toSeq

  private def project(exprs: NamedExpression*): Seq[NamedExpression] = exprs

  test("date_add over an Arrow DateDayVector batch") {
    val dates: Seq[java.lang.Integer] = Seq(0, 1, -5, 20000)
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()), child)
    assert(run(node).map(_.getInt(0)) === dates.map(_ + 3))
    assert(node.metrics("numVarkaBatches").value === 1)
  }

  test("date_sub over an Arrow DateDayVector batch") {
    val dates: Seq[java.lang.Integer] = Seq(0, 1, -5, 20000)
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateSub(attrD, Literal(2)), "sub")()), child)
    assert(run(node).map(_.getInt(0)) === dates.map(_ - 2))
    assert(node.metrics("numVarkaBatches").value === 1)
  }

  test("date_diff over two Arrow DateDayVector batches") {
    val ends: Seq[java.lang.Integer] = Seq(10, 3, -5, 20000)
    val starts: Seq[java.lang.Integer] = Seq(1, 7, 0, -20000)
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("arrow", Seq(ends, starts))), Seq(attrD, attrD2))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateDiff(attrD, attrD2), "diff")()), child)
    assert(run(node).map(_.getInt(0)) === ends.indices.map(i => ends(i) - starts(i)))
    assert(node.metrics("numVarkaBatches").value === 1)
  }

  test("null patterns: mixed, all-null and null-free columns") {
    val mixed: Seq[java.lang.Integer] = Seq(0, null, -5, null, 10)
    val child = TestColumnarBatchPlan(
      Seq(
        BatchSpec("arrow", Seq(mixed)),
        BatchSpec("arrow", Seq(Seq[java.lang.Integer](null, null, null))),
        BatchSpec("arrow", Seq(Seq[java.lang.Integer](5, 6, 7, 8)))),
      Seq(attrD))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateAdd(attrD, Literal(1)), "add")()), child)
    val expected: Seq[Any] = Seq(1, null, -4, null, 11) ++ Seq(null, null, null) ++
      Seq(6, 7, 8, 9)
    assert(values(run(node)) === expected)
    assert(node.metrics("numVarkaBatches").value === 3)
  }

  test("empty batch takes the fallback path") {
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("arrow", Seq(Seq[java.lang.Integer]()))), Seq(attrD))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()), child)
    assert(run(node).isEmpty)
    assert(node.metrics("numVarkaBatches").value === 0)
  }

  test("offsets near Integer.MAX_VALUE wrap like the scalar date expressions") {
    val dates: Seq[java.lang.Integer] = Seq(Int.MaxValue - 1, Int.MinValue, 0)
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()), child)
    assert(run(node).map(_.getInt(0)) === dates.map(_ + 3))
  }

  test("non-Arrow batch falls back to the per-row projection") {
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("onheap", Seq(Seq[java.lang.Integer](0, 10, 20, 30)))), Seq(attrD))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()), child)
    assert(run(node).map(_.getInt(0)) === Seq(3, 13, 23, 33))
    assert(node.metrics("numVarkaBatches").value === 0)
  }

  test("ineligible projection is never rewritten and falls back") {
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("onheap", Seq(Seq[java.lang.Integer](100, 101, 102)))), Seq(intAttr))
    // `i + 1` is not a Varka date op, so outputPlan is None and every batch goes through the
    // per-row projection.
    val node = VarkaColumnarToRowExec(
      project(Alias(Add(intAttr, Literal(1)), "add")()), child)
    assert(run(node).map(_.getInt(0)) === Seq(101, 102, 103))
    assert(node.metrics("numVarkaBatches").value === 0)
  }

  test("a vector holding rows beyond the batch falls back instead of trusting its null count") {
    // The vector holds 20 rows - ten dates, then ten nulls - but the batch says ten. The whole
    // vector's null count is therefore 10, which equals the batch's row count, so handing that
    // count to the kernels would trip their all-null shortcut and emit ten nulls for ten rows
    // that are not null.
    val dates: Seq[java.lang.Integer] =
      (0 until 10).map(java.lang.Integer.valueOf) ++ Seq.fill[java.lang.Integer](10)(null)
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("arrow", Seq(dates), numRows = Some(10))), Seq(attrD))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()), child)
    assert(values(run(node)) === (0 until 10).map(_ + 3))
    assert(node.metrics("numVarkaBatches").value === 0)
  }

  test("rows are emitted uncopied, like the standard columnar-to-row path") {
    // Two batches: the Arrow one takes the kernel path, the on-heap one the per-row fallback.
    // Both must hand back the projection's own row, rewritten per row, the way
    // `ColumnarToRowEvaluatorFactory` does.
    val specs = Seq(
      BatchSpec("arrow", Seq((0 until 8).map(Int.box))),
      BatchSpec("onheap", Seq((8 until 16).map(Int.box))))
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val inputs = specs.map(VarkaColumnarToRowExecSuite.buildBatch(_, Seq(attrD), allocator))
      val varkaBatches = SQLMetrics.createMetric(sparkContext, "varkaBatches")
      val factory = new VarkaColumnarToRowEvaluatorFactory(
        project(Alias(DateAdd(attrD, Literal(3)), "add")()),
        Seq(attrD),
        SQLMetrics.createMetric(sparkContext, "rows"),
        SQLMetrics.createMetric(sparkContext, "batches"),
        varkaBatches)
      val rows = factory.createEvaluator().eval(0, inputs.iterator)

      val kernelRow = rows.next()
      assert(kernelRow.getInt(0) === 3)
      assert((rows.next() eq kernelRow), "the kernel path copied the row")
      assert(kernelRow.getInt(0) === 4, "the shared row was not rewritten in place")
      assert((2 until 8).map(_ => rows.next().getInt(0)) === (2 until 8).map(_ + 3))

      val fallbackRow = rows.next()
      assert(fallbackRow.getInt(0) === 11)
      assert((rows.next() eq fallbackRow), "the fallback path copied the row")
      assert(fallbackRow.getInt(0) === 12)
      assert(varkaBatches.value === 1, "the Arrow batch did not take the kernel path")
      // Pulling a fallback row drained the kernel iterator, so its result batch is closed by
      // now. The row it handed back holds its own bytes rather than a view of that batch, and
      // still reads back the last kernel row - which is what makes emitting it uncopied safe
      // and not merely cheaper.
      assert(kernelRow.getInt(0) === 10)

      inputs.foreach(_.close())
      context.markTaskCompleted(None)
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("an injected kernel failure falls back per batch without crashing") {
    val dates: Seq[java.lang.Integer] = Seq(0, 1, -5, 20000)
    val child = TestColumnarBatchPlan(
      Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()), child)
    VarkaColumnarToRowExec.setFailKernelForTesting(true)
    try {
      // The kernel path is attempted but fails, so the batch counts no successful kernel run;
      // the rows are still produced by the fallback and the result is identical.
      assert(run(node).map(_.getInt(0)) === dates.map(_ + 3))
      assert(node.metrics("numVarkaBatches").value === 0)
    } finally {
      VarkaColumnarToRowExec.setFailKernelForTesting(false)
    }
  }

  test("each kernel result batch is released as soon as its rows are consumed") {
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
      val factory = new VarkaColumnarToRowEvaluatorFactory(
        project(Alias(DateAdd(attrD, Literal(3)), "add")()),
        Seq(attrD),
        SQLMetrics.createMetric(sparkContext, "rows"),
        SQLMetrics.createMetric(sparkContext, "batches"),
        SQLMetrics.createMetric(sparkContext, "varkaBatches"))
      val rows = factory.createEvaluator().eval(0, inputs.iterator)

      var count = 0
      var peak = 0L
      rows.foreach { row =>
        assert(row.getInt(0) === count + 3)
        count += 1
        peak = math.max(peak, ArrowUtils.rootAllocator.getAllocatedMemory - baseline)
      }
      assert(count === numBatches * rowsPerBatch)
      // The iterator is drained, so every result batch has been closed - without waiting for the
      // task to complete. A per-batch task-completion listener would leave all of them open.
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === baseline,
        "the kernel result batches were not released when their rows ran out")
      // Only one result batch is live at a time; allow generous slack for Arrow's power-of-two
      // buffer rounding, but stay far below the numBatches-times figure the leak would reach.
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

  test("an unfinished kernel iterator is released when the task completes") {
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
      val factory = new VarkaColumnarToRowEvaluatorFactory(
        project(Alias(DateAdd(attrD, Literal(3)), "add")()),
        Seq(attrD),
        SQLMetrics.createMetric(sparkContext, "rows"),
        SQLMetrics.createMetric(sparkContext, "batches"),
        SQLMetrics.createMetric(sparkContext, "varkaBatches"))
      // Stop after the first row, like a LIMIT would: the batch stays open.
      val rows = factory.createEvaluator().eval(0, inputs.iterator)
      assert(rows.next().getInt(0) === 3)
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

  test("output partitioning and ordering are projected through the aliases") {
    val add = Alias(DateAdd(attrD, Literal(3)), "add")()
    // The child is partitioned and ordered by the very expression the projection aliases, so
    // both properties survive - restated in terms of the output attribute.
    val child = TestColumnarBatchPlan(
      Nil,
      Seq(attrD),
      HashPartitioning(Seq(DateAdd(attrD, Literal(3))), 5),
      Seq(SortOrder(DateAdd(attrD, Literal(3)), Ascending)))
    val node = VarkaColumnarToRowExec(project(add), child)

    assert(node.outputPartitioning === HashPartitioning(Seq(add.toAttribute), 5))
    assert(node.outputOrdering === Seq(SortOrder(add.toAttribute, Ascending)))
  }

  test("output partitioning and ordering drop attributes the projection does not output") {
    // `d` does not survive the projection, so neither property can be advertised: passing the
    // child's through verbatim would describe this node's output by a column it does not have.
    val child = TestColumnarBatchPlan(
      Nil,
      Seq(attrD),
      HashPartitioning(Seq(attrD), 5),
      Seq(SortOrder(attrD, Ascending)))
    val node = VarkaColumnarToRowExec(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()), child)

    assert(node.outputPartitioning === UnknownPartitioning(5))
    assert(node.outputOrdering === Nil)
  }

  test("VarkaColumnarRule rewrites only fully eligible projections when enabled") {
    val child = TestColumnarBatchPlan(Nil, Seq(attrD))
    val eligible = ProjectExec(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()),
      ColumnarToRowExec(child))
    val ineligible = ProjectExec(
      project(Alias(Add(attrD, Literal(3)), "add")()),
      ColumnarToRowExec(child))

    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      assert(VarkaColumnarRule.postColumnarTransitions(eligible)
        .isInstanceOf[VarkaColumnarToRowExec])
      assert(VarkaColumnarRule.postColumnarTransitions(ineligible).isInstanceOf[ProjectExec])
    }
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "false") {
      assert(VarkaColumnarRule.postColumnarTransitions(eligible).isInstanceOf[ProjectExec])
    }
  }

  test("Varka kernel descriptors match the engine signatures") {
    val owner = "org.apache.spark.sql.varka.vector.DateVectorOps"
    assert(DateAdd(attrD, Literal(3)).classFileGenOp ===
      ClassFileGenOp(owner, "vectorAddDays", "(JJIJJII)V"))
    assert(DateSub(attrD, Literal(3)).classFileGenOp ===
      ClassFileGenOp(owner, "vectorSubDays", "(JJIJJII)V"))
    assert(DateDiff(attrD, attrD2).classFileGenOp ===
      ClassFileGenOp(owner, "vectorDateDiff", "(JJIJJIJJI)V"))
  }
}

/**
 * A serializable description of one batch: its kind and the per-column integer values.
 *
 * `numRows` defaults to the number of values, which is the shape every real Arrow producer
 * builds; set it lower to describe a batch whose vectors hold rows beyond the batch's own.
 */
private[sql] case class BatchSpec(
    kind: String,
    columns: Seq[Seq[java.lang.Integer]],
    numRows: Option[Int] = None)

/**
 * A columnar-only child plan that builds batches from [[BatchSpec]]s inside the task, like a
 * real columnar scan would (`ColumnarBatch` and Arrow vectors are not serializable).
 */
private[sql] case class TestColumnarBatchPlan(
    specs: Seq[BatchSpec],
    output: Seq[Attribute],
    override val outputPartitioning: Partitioning = UnknownPartitioning(0),
    override val outputOrdering: Seq[SortOrder] = Nil)
    extends SparkPlan {
  override def supportsColumnar: Boolean = true
  override def children: Seq[SparkPlan] = Seq.empty
  override protected def withNewChildrenInternal(
      newChildren: IndexedSeq[SparkPlan]): SparkPlan = this
  override protected def doExecuteColumnar(): RDD[ColumnarBatch] = {
    new RDD[ColumnarBatch](sparkContext, Nil) {
      override protected def getPartitions: Array[Partition] =
        specs.indices.map(i => new Partition { override def index: Int = i }).toArray
      override def compute(split: Partition, context: TaskContext): Iterator[ColumnarBatch] = {
        val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
        val batch = VarkaColumnarToRowExecSuite.buildBatch(specs(split.index), output, allocator)
        context.addTaskCompletionListener[Unit] { _ =>
          batch.close()
          allocator.close()
        }
        Iterator.single(batch)
      }
    }
  }
  override protected def doExecute(): RDD[InternalRow] =
    throw new UnsupportedOperationException("TestColumnarBatchPlan is columnar-only")
}

object VarkaColumnarToRowExecSuite {

  /** Builds one batch from a spec, allocating Arrow vectors with the given allocator. */
  private[sql] def buildBatch(
      spec: BatchSpec,
      output: Seq[Attribute],
      allocator: BufferAllocator): ColumnarBatch = {
    val columns = output.indices.map { c =>
      val values = spec.columns(c)
      spec.kind match {
        case "arrow" =>
          val vector = ArrowUtils.toArrowField(output(c).name, output(c).dataType,
            nullable = true, null).createVector(allocator).asInstanceOf[DateDayVector]
          vector.allocateNew(values.length)
          values.zipWithIndex.foreach { case (v, i) =>
            if (v == null) vector.setNull(i) else vector.setSafe(i, v)
          }
          vector.setValueCount(values.length)
          new ArrowColumnVector(vector)
        case "onheap" =>
          val column = new OnHeapColumnVector(values.length, output(c).dataType)
          values.zipWithIndex.foreach { case (v, i) =>
            if (v == null) column.putNull(i) else column.putInt(i, v)
          }
          column
      }
    }
    val batch = new ColumnarBatch(columns.toArray)
    batch.setNumRows(spec.numRows.getOrElse(spec.columns.head.length))
    batch
  }
}
