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
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, Attribute, AttributeReference, DateAdd, DateDiff, DateSub, Literal, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.ClassFileGenOp
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

/** A serializable description of one batch: its kind and the per-column integer values. */
private[sql] case class BatchSpec(kind: String, columns: Seq[Seq[java.lang.Integer]])

/**
 * A columnar-only child plan that builds batches from [[BatchSpec]]s inside the task, like a
 * real columnar scan would (`ColumnarBatch` and Arrow vectors are not serializable).
 */
private[sql] case class TestColumnarBatchPlan(
    specs: Seq[BatchSpec],
    output: Seq[Attribute])
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
    batch.setNumRows(spec.columns.head.length)
    batch
  }
}
