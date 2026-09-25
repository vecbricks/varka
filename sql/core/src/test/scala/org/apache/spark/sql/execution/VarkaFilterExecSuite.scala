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
import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, AttributeReference, DateAdd, GreaterThan, IsNotNull, LessThan, Literal, Rand}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaFallbackEvent, VarkaJfrTestSupport}
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DateType, IntegerType, ShortType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * Unit tests for the two Varka filter nodes (task 21): [[VarkaFilterExec]] - the mask kernel
 * plus compaction into a fresh dense batch - and [[VarkaFilterColumnarToRowExec]], which
 * consumes the selection bitmap at the row boundary with no compaction. Plus the
 * `VarkaColumnarRule` filter rewrites, the conjunct split included.
 *
 * The batch scaffolding - `BatchSpec`, `TestColumnarBatchPlan`, `buildBatch` - is shared with
 * [[VarkaColumnarToRowExecSuite]].
 */
class VarkaFilterExecSuite extends QueryTest with SharedSparkSession {

  private val attrD = AttributeReference("d", DateType)()
  private val intAttr = AttributeReference("i", IntegerType)()
  /**
   * A column on a lane the kernel does not read, so a predicate over it is residual - and a
   * list that carries it, kept separate from the two-column one several tests below number
   * their ordinals against. The tests whose subject is the *split* between fused and residual
   * conjuncts use these: task 122 made a bare int column compare in the kernel, so `i > 5`
   * stopped being an example of something that cannot fuse.
   */
  private val shortAttr = AttributeReference("sh", ShortType)()
  private val withShort: Seq[Attribute] = Seq(attrD, intAttr, shortAttr)
  private val shortResidual = GreaterThan(shortAttr, Literal(5.toShort))

  /** `d < DATE(epoch day 10)`: the workhorse predicate of these tests. */
  private def dLess10 = LessThan(attrD, Literal(10, DateType))

  private def columnarNode(condition: org.apache.spark.sql.catalyst.expressions.Expression,
      specs: Seq[BatchSpec], output: Seq[Attribute]): VarkaFilterExec = {
    VarkaFilterExec(condition, TestColumnarBatchPlan(specs, output))
  }

  /** Runs the columnar node and reads every output batch into per-row tuples of columns. */
  private def batchRows(node: VarkaFilterExec): Seq[Seq[Any]] = {
    node.executeColumnar().mapPartitions { batches =>
      batches.flatMap { batch =>
        (0 until batch.numRows()).map { r =>
          (0 until batch.numCols()).map { c =>
            if (batch.column(c).isNullAt(r)) null else Int.box(batch.column(c).getInt(r))
          }.toList
        }.toList.iterator
        // Materialised inside the partition: a batch belongs to its producer.
      }
    }.collect().toSeq
  }

  /**
   * Runs the row node over `specs` and reads every emitted row into a tuple of its columns -
   * the multi-column counterpart of [[rowValues]], which task 78 needs because the point of a
   * narrowing is which columns come out, not what the first one holds.
   */
  private def rowNodeRows(
      condition: org.apache.spark.sql.catalyst.expressions.Expression,
      specs: Seq[BatchSpec],
      output: Seq[Attribute],
      narrowing: Option[Seq[org.apache.spark.sql.catalyst.expressions.NamedExpression]] = None)
      : Seq[Seq[Any]] = {
    val node = VarkaFilterColumnarToRowExec(
      condition, TestColumnarBatchPlan(specs, output), narrowing)
    val width = node.output.size
    node.execute().map { row =>
      (0 until width).map(c => if (row.isNullAt(c)) null else Int.box(row.getInt(c))).toList
    }.collect().toSeq
  }

  /** Runs the row node and reads its rows' first column. */
  private def rowValues(node: VarkaFilterColumnarToRowExec): Seq[Any] = {
    node.execute().map { row =>
      if (row.isNullAt(0)) null else Int.box(row.getInt(0))
    }.collect().toSeq
  }

  test("the columnar node compacts to exactly the selected rows, null-as-false") {
    val dates = Seq(Int.box(1), null, Int.box(9), Int.box(10), Int.box(42))
    val plan = columnarNode(dLess10, Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD))
    // Row 1 is null: SQL's WHERE drops it, exactly as a false does.
    assert(batchRows(plan) === Seq(Seq(1), Seq(9)))
    assert(plan.metrics("numOutputRows").value === 2)
    assert(plan.metrics("numVarkaBatches").value === 1)
  }

  test("all-selected and none-selected batches compact to full and empty batches") {
    val dates = Seq(Int.box(1), Int.box(2), Int.box(3))
    val all = columnarNode(dLess10, Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD))
    assert(batchRows(all) === Seq(Seq(1), Seq(2), Seq(3)))
    val none = columnarNode(
      GreaterThan(attrD, Literal(100, DateType)),
      Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD))
    assert(batchRows(none) === Seq.empty)
    assert(none.metrics("numOutputRows").value === 0)
    assert(none.metrics("numVarkaBatches").value === 1)
  }

  test("an IS NOT NULL conjunction drops exactly the null rows") {
    // The view-caching shape that first exercised filters under a cache build: a two-column
    // validity conjunction, total (never unknown) - the mask must clear exactly the rows
    // where either side is null.
    val d = Seq(Int.box(1), null, Int.box(3), Int.box(4), null)
    val d2 = Seq(Int.box(10), Int.box(20), null, Int.box(40), Int.box(50))
    val attrD2 = AttributeReference("d2", DateType)()
    val plan = columnarNode(
      And(IsNotNull(attrD), IsNotNull(attrD2)),
      Seq(BatchSpec("arrow", Seq(d, d2))), Seq(attrD, attrD2))
    assert(batchRows(plan) === Seq(Seq(1, 10), Seq(4, 40)))
  }

  test("non-predicate columns ride the compaction: dates typed, ints typed") {
    // Both columns are Arrow fixed-width, so both take the typed compaction path; the int
    // column has a null on a surviving row, which must survive as a null.
    val dates = Seq(Int.box(1), Int.box(50), Int.box(3), Int.box(60))
    val ints = Seq(Int.box(7), Int.box(8), null, Int.box(10))
    val plan = columnarNode(
      dLess10, Seq(BatchSpec("arrow", Seq(dates, ints))), Seq(attrD, intAttr))
    assert(batchRows(plan) === Seq(Seq(1, 7), Seq(3, null)))
  }

  test("a column outside the typed compaction goes through the generic row pass") {
    // A batch whose predicate column is Arrow but whose second column is an on-heap vector:
    // canRun holds (only referenced columns must be Arrow) and the on-heap column compacts
    // through the generic converter pass. Built by hand - BatchSpec describes whole batches.
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val arrowBatch = VarkaColumnarToRowExecSuite.buildBatch(
        BatchSpec("arrow", Seq(Seq(Int.box(1), Int.box(50), Int.box(3)))),
        Seq(attrD), allocator)
      val onheap = new OnHeapColumnVector(3, IntegerType)
      onheap.putInt(0, 7)
      onheap.putNull(1)
      onheap.putInt(2, 9)
      val batch = new ColumnarBatch(Array(arrowBatch.column(0), onheap))
      batch.setNumRows(3)
      val kernels = new VarkaFilterEvaluator(
        dLess10, Seq(attrD, intAttr), offHeapColumnVectorEnabled = false,
        operatorName = "Filter")
      assert(kernels.canRun(batch))
      val out = kernels.filterCompact(batch)
      assert(out.numRows() === 2)
      assert(Seq(out.column(0).getInt(0), out.column(0).getInt(1)) === Seq(1, 3))
      assert(out.column(1).getInt(0) === 7)
      assert(out.column(1).getInt(1) === 9)
      kernels.release(out)
      arrowBatch.close()
      onheap.close()
    } finally {
      // In the finally (task-21 review, second pass): a failed assertion above must not
      // skip the evaluator's task cleanup, or the allocator close below reports leaked
      // buffers instead of the real failure. markTaskCompleted is idempotent.
      context.markTaskCompleted(None)
      TaskContext.unset()
      allocator.close()
    }
  }

  test("a batch the kernel cannot serve falls back to the per-row filter, counted") {
    val plan = columnarNode(dLess10, Seq(
      BatchSpec("arrow", Seq(Seq(Int.box(1), Int.box(42)))),
      BatchSpec("onheap", Seq(Seq(Int.box(2), null, Int.box(77))))),
      Seq(attrD))
    assert(batchRows(plan) === Seq(Seq(1), Seq(2)))
    assert(plan.metrics("numInputBatches").value === 2)
    assert(plan.metrics("numVarkaBatches").value === 1)
    assert(plan.metrics("numFallbackBatchesNonArrow").value === 1)
    assert(plan.metrics("numFallbackBatchesKernel").value === 0)
    assert(plan.metrics("numOutputRows").value === 2)
  }

  test("the ghost fallback: a kernel failure filters the batch per row, counted") {
    VarkaColumnarToRowExec.setFailKernelForTesting(true)
    try {
      val plan = columnarNode(dLess10,
        Seq(BatchSpec("arrow", Seq(Seq(Int.box(1), null, Int.box(42))))), Seq(attrD))
      assert(batchRows(plan) === Seq(Seq(1)))
      assert(plan.metrics("numVarkaBatches").value === 0)
      assert(plan.metrics("numFallbackBatchesKernel").value === 1)
    } finally {
      VarkaColumnarToRowExec.setFailKernelForTesting(false)
    }
  }

  test("an emission failure counts once per task, evented, not mislabeled") {
    val (_, recorded) = VarkaJfrTestSupport.withJfrRecording(classOf[VarkaFallbackEvent]) {
      VarkaColumnarToRowExec.setFailEmissionForTesting(true)
      try {
        val plan = columnarNode(dLess10,
          Seq(BatchSpec("arrow", Seq(Seq(Int.box(1), null, Int.box(42))))), Seq(attrD))
        assert(batchRows(plan) === Seq(Seq(1)))
        assert(plan.metrics("numVarkaBatches").value === 0)
        assert(plan.metrics("numEmissionFailures").value === 1)
        assert(plan.metrics("numFallbackBatchesNonArrow").value === 0)
        assert(plan.metrics("numFallbackBatchesKernel").value === 0)
      } finally {
        VarkaColumnarToRowExec.setFailEmissionForTesting(false)
      }
    }
    val causes = recorded
      .filter(VarkaJfrTestSupport.isEvent(_, classOf[VarkaFallbackEvent]))
      .filter(_.getString("kernelIdentity").contains("Varka_Filter_"))
      .map(_.getString("cause"))
    assert(causes.contains(VarkaFallbackEvent.EMISSION_FAILURE), causes.mkString("; "))
  }

  test("each compacted batch is released when the next one is requested") {
    val numBatches = 8
    val rowsPerBatch = 512
    // Every fourth row is null, so the always-true predicate below selects 384 of 512 rows
    // and the batch takes the *compacting* path. This used to be all-selected, which was fine
    // until task 24 taught that case to forward the child's columns instead - after which an
    // all-selected run of this test would cover forwarding (which has its own test) and leave
    // the compaction's allocation and release, the thing this test exists for, unexercised.
    val selectedPerBatch = (0 until rowsPerBatch).count(_ % 4 != 3)
    val specs = (0 until numBatches).map { b =>
      BatchSpec("arrow", Seq((0 until rowsPerBatch).map(i =>
        if (i % 4 == 3) null else Int.box(b * rowsPerBatch + i))))
    }
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val inputs = specs.map(VarkaColumnarToRowExecSuite.buildBatch(_, Seq(attrD), allocator))
      val baseline = ArrowUtils.rootAllocator.getAllocatedMemory
      // Three quarters of every batch selects, so each compacted batch is nearly as large as
      // its input - close to the worst case for a leak to show, while still compacting.
      val factory = new VarkaFilterEvaluatorFactory(
        GreaterThan(attrD, Literal(-1, DateType)),
        Seq(attrD),
        offHeapColumnVectorEnabled = false,
        classDumpDirectory = None,
        numOutputRows = org.apache.spark.sql.execution.metric.SQLMetrics
          .createMetric(sparkContext, "rows"),
        numInputBatches = org.apache.spark.sql.execution.metric.SQLMetrics
          .createMetric(sparkContext, "batches"),
        varkaMetrics = VarkaExecMetrics())
      val batches = factory.createEvaluator().eval(0, inputs.iterator)
      var seen = 0
      var peak = 0L
      batches.foreach { batch =>
        assert(batch.numRows() === selectedPerBatch)
        assert(batch.column(0).getInt(0) === seen * rowsPerBatch)
        seen += 1
        peak = math.max(peak, ArrowUtils.rootAllocator.getAllocatedMemory - baseline)
      }
      assert(seen === numBatches)
      // Unlike the projection's ledger, task state legitimately survives the drain here: the
      // reused selection buffer lives until task completion. Everything batch-shaped must be
      // gone, so the residue is bounded by that one small buffer.
      val residue = ArrowUtils.rootAllocator.getAllocatedMemory - baseline
      assert(residue >= 0 && residue <= 4096,
        s"the compacted batches were not released as the iterator advanced ($residue bytes)")
      val oneBatch = 4L * rowsPerBatch
      assert(peak < 4 * oneBatch,
        s"peak Varka off-heap use was $peak bytes for a $oneBatch-byte batch")
      inputs.foreach(_.close())
      context.markTaskCompleted(None)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the task-completion listener did not release the Varka state")
    } finally {
      // Idempotent re-completion (task-21 review, second pass): an assertion failing before
      // the in-try markTaskCompleted must still run the evaluator's cleanup, or the
      // allocator close below masks the real failure with leaked-buffer errors.
      context.markTaskCompleted(None)
      TaskContext.unset()
      allocator.close()
    }
  }

  test("an all-selected batch forwards the child's columns instead of copying") {
    // The one case where compaction has nothing to shorten, and the only place a Varka filter
    // hands back a vector it does not own. Two things are asserted, and the second is the
    // hazard: the output column must be the *same object* as the input's, and releasing the
    // output must not close it - a batch that owns nothing must leave its vectors to the input
    // batch, or the next read of that input is a use-after-free.
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-forward", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val spec = BatchSpec("arrow", Seq((0 until 64).map(Int.box)))
      val input = VarkaColumnarToRowExecSuite.buildBatch(spec, Seq(attrD), allocator)
      val factory = new VarkaFilterEvaluatorFactory(
        GreaterThan(attrD, Literal(-1, DateType)),
        Seq(attrD),
        offHeapColumnVectorEnabled = false,
        classDumpDirectory = None,
        numOutputRows = org.apache.spark.sql.execution.metric.SQLMetrics
          .createMetric(sparkContext, "rows"),
        numInputBatches = org.apache.spark.sql.execution.metric.SQLMetrics
          .createMetric(sparkContext, "batches"),
        varkaMetrics = VarkaExecMetrics())
      val batches = factory.createEvaluator().eval(0, Iterator(input))
      val out = batches.next()
      assert(out.numRows() === 64)
      assert(out.column(0) eq input.column(0),
        "an all-selected batch must forward the child's column, not copy it")
      // Draining releases the output batch, which is where a wrongly-owned vector is closed.
      assert(!batches.hasNext)
      // The forwarded vector is still the input's to read afterwards.
      assert(input.column(0).getInt(63) === 63)
      input.close()
      context.markTaskCompleted(None)
    } finally {
      context.markTaskCompleted(None)
      TaskContext.unset()
      allocator.close()
    }
  }

  test("the row node counts rows as they are emitted, not per batch") {
    // Pre-charging the batch's whole selected count overcounts under early termination
    // (a LIMIT) and double-counts when a later throw routes the batch to the fallback.
    val dates = Seq(Int.box(1), Int.box(2), Int.box(3), Int.box(4))
    val plan = VarkaFilterColumnarToRowExec(
      dLess10, TestColumnarBatchPlan(Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD)))
    // Consume one row and stop, like a LIMIT would.
    plan.execute().mapPartitions(rows => Iterator.single(rows.next())).count()
    assert(plan.metrics("numOutputRows").value === 1)
  }

  test("a columnar child declaring non-Arrow vectors is not rewritten") {
    // supportsColumnar alone is satisfied by Parquet/ORC vectorized scans whose batches
    // fail canRun every time; the plan-time vectorTypes gate keeps such filters on the
    // stock plan instead of a permanently falling-back Varka node.
    val onheapChild = TestColumnarBatchPlan(Nil, Seq(attrD, intAttr),
      declaredVectorTypes = Some(Seq(
        classOf[OnHeapColumnVector].getName, classOf[OnHeapColumnVector].getName)))
    val filter = FilterExec(dLess10, onheapChild)
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      assert(VarkaColumnarRule.preColumnarTransitions(filter) === filter)
      assert(VarkaColumnarRule.postColumnarTransitions(filter) === filter)
    }
  }

  test("the row node emits exactly the selected rows, null-as-false, no compaction") {
    val dates = Seq(Int.box(1), null, Int.box(9), Int.box(10), Int.box(42))
    val plan = VarkaFilterColumnarToRowExec(
      dLess10, TestColumnarBatchPlan(Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD)))
    assert(rowValues(plan) === Seq(1, 9))
    assert(plan.metrics("numOutputRows").value === 2)
    assert(plan.metrics("numVarkaBatches").value === 1)
  }

  test("the row node falls back per row on a batch the kernel cannot serve") {
    val plan = VarkaFilterColumnarToRowExec(
      dLess10,
      TestColumnarBatchPlan(Seq(
        BatchSpec("arrow", Seq(Seq(Int.box(1), Int.box(42)))),
        BatchSpec("onheap", Seq(Seq(Int.box(2), null, Int.box(77))))), Seq(attrD)))
    assert(rowValues(plan) === Seq(1, 2))
    assert(plan.metrics("numFallbackBatchesNonArrow").value === 1)
    assert(plan.metrics("numOutputRows").value === 2)
  }

  test("both filter nodes tighten output nullability like FilterExec") {
    val child = TestColumnarBatchPlan(Nil, Seq(attrD, intAttr))
    val condition = And(IsNotNull(attrD), dLess10)
    val columnar = VarkaFilterExec(condition, child)
    val row = VarkaFilterColumnarToRowExec(condition, child)
    val filter = FilterExec(condition, child)
    assert(columnar.output.map(_.nullable) === filter.output.map(_.nullable))
    assert(row.output.map(_.nullable) === filter.output.map(_.nullable))
    assert(!columnar.output.head.nullable, "the IsNotNull-guarded column must read non-null")
    assert(columnar.output(1).nullable)
  }

  test("formatted EXPLAIN renders both filter nodes without throwing") {
    // Caught while writing the PR description: ExplainUtils.generateFieldString rejects a
    // bare expression, so formatted EXPLAIN of any Varka filter node threw. The condition
    // renders as a plain line, exactly as FilterExec renders its own.
    val child = TestColumnarBatchPlan(Nil, Seq(attrD, intAttr))
    val rendered = VarkaFilterExec(dLess10, child).verboseStringWithOperatorId() +
      VarkaFilterColumnarToRowExec(dLess10, child).verboseStringWithOperatorId()
    assert(rendered.contains("Condition : "), rendered)
    assert(rendered.contains(": fused"), rendered)
  }

  test("EXPLAIN reports the predicate's conjuncts through the fusion report") {
    val fused = VarkaFusionReport.predicateLines(And(dLess10, IsNotNull(attrD)),
      withShort)
    assert(fused === Seq("(d < DATE '1970-01-11'): fused", "(d IS NOT NULL): fused"))
    val mixed = VarkaFusionReport.predicateLines(
      And(dLess10, shortResidual), withShort)
    assert(mixed.head === "(d < DATE '1970-01-11'): fused")
    assert(mixed(1).startsWith("(sh > 5S): residual (non-date column of type smallint"))
    // A filter no conjunct of which fuses still says why, conjunct by conjunct.
    val none = VarkaFusionReport.predicateLines(shortResidual, withShort)
    assert(none.size == 1 && none.head.startsWith("(sh > 5S): residual (non-date column"), none)
  }

  test("a nondeterministic filter is declined with its reason, conjunct by conjunct") {
    val lines = VarkaFusionReport.predicateLines(
      And(dLess10, GreaterThan(Rand(Literal(7L)), Literal(0.5))), withShort)
    assert(lines.size == 2, lines)
    assert(lines.forall(_.contains("residual (the condition is nondeterministic")), lines)
    assert(VarkaExpressionCompiler.compilePredicate(
      And(dLess10, GreaterThan(Rand(Literal(7L)), Literal(0.5))), withShort).isEmpty)
  }

  test("VarkaColumnarRule: the pre stage rewrites an eligible filter, splitting conjuncts") {
    val child = TestColumnarBatchPlan(Nil, withShort)
    val eligible = FilterExec(dLess10, child)
    val mixed = FilterExec(And(dLess10, shortResidual), child)
    val ineligible = FilterExec(shortResidual, child)
    val rowChild = FilterExec(dLess10, ColumnarToRowExec(child))

    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      assert(VarkaColumnarRule.preColumnarTransitions(eligible) ===
        VarkaFilterExec(dLess10, child))
      // The mixed predicate splits: the fused conjunct in the Varka node, the int conjunct
      // in a row FilterExec above it - which then sees only the surviving rows.
      assert(VarkaColumnarRule.preColumnarTransitions(mixed) ===
        FilterExec(shortResidual, VarkaFilterExec(dLess10, child)))
      assert(VarkaColumnarRule.preColumnarTransitions(ineligible) === ineligible)
      // A row child is left for the post stage, which absorbs the transition.
      assert(VarkaColumnarRule.preColumnarTransitions(rowChild) === rowChild)
    }
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "false") {
      assert(VarkaColumnarRule.preColumnarTransitions(eligible) === eligible)
    }
  }

  test("VarkaColumnarRule: the post stage fuses the transition, or absorbs one") {
    val child = TestColumnarBatchPlan(Nil, withShort)
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      // The transition the pre-stage node received is fused into the row-out filter.
      assert(VarkaColumnarRule.postColumnarTransitions(
        ColumnarToRowExec(VarkaFilterExec(dLess10, child))) ===
        VarkaFilterColumnarToRowExec(dLess10, child))
      // A filter the pre stage did not see absorbs its to-row transition.
      assert(VarkaColumnarRule.postColumnarTransitions(
        FilterExec(dLess10, ColumnarToRowExec(child))) ===
        VarkaFilterColumnarToRowExec(dLess10, child))
      // The split applies there too.
      assert(VarkaColumnarRule.postColumnarTransitions(
        FilterExec(And(dLess10, shortResidual),
          ColumnarToRowExec(child))) ===
        FilterExec(shortResidual,
          VarkaFilterColumnarToRowExec(dLess10, child)))
      // The residual filter the pre stage left above a (now fused) Varka filter is not
      // touched again: its child is a row node.
      val residualOverFused = FilterExec(shortResidual,
        VarkaFilterColumnarToRowExec(dLess10, child))
      assert(VarkaColumnarRule.postColumnarTransitions(residualOverFused)
        === residualOverFused)
    }
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "false") {
      val transition = ColumnarToRowExec(VarkaFilterExec(dLess10, child))
      assert(VarkaColumnarRule.postColumnarTransitions(transition) === transition)
    }
  }

  test("a narrowing projection is absorbed into the row-out filter") {
    val child = TestColumnarBatchPlan(Nil, Seq(attrD, intAttr))
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      // `SELECT d FROM t WHERE d < ...` after transitions: a Project the optimizer could not
      // remove, because the predicate reads two columns and the consumer wants one.
      val plan = ProjectExec(Seq(attrD), VarkaFilterColumnarToRowExec(dLess10, child))
      val rewritten = VarkaColumnarRule.postColumnarTransitions(plan)
      assert(rewritten === VarkaFilterColumnarToRowExec(dLess10, child, Some(Seq(attrD))),
        s"expected the projection absorbed, got:\n$rewritten")
      // The absorbed node answers with the projected schema, not the filter's own.
      assert(rewritten.output.map(_.name) === Seq("d"))

      // A rename rides along; anything computed does not, because a computed entry belongs to
      // the eligibility test above this arm - it may fuse, and then it wants a kernel.
      val renamed = ProjectExec(Seq(Alias(attrD, "day")()),
        VarkaFilterColumnarToRowExec(dLess10, child))
      assert(VarkaColumnarRule.postColumnarTransitions(renamed)
        .isInstanceOf[VarkaFilterColumnarToRowExec])
      val computed = ProjectExec(Seq(Alias(DateAdd(attrD, Literal(3)), "add")()),
        VarkaFilterColumnarToRowExec(dLess10, child))
      assert(VarkaColumnarRule.postColumnarTransitions(computed).isInstanceOf[ProjectExec])

      // Idempotent: a second pass over an already-absorbed node leaves it alone rather than
      // nesting a second narrowing inside the first.
      assert(VarkaColumnarRule.postColumnarTransitions(rewritten) === rewritten)
    }
  }

  test("a narrowing projection keeps the columnar path through the pre stage") {
    // The bug row 145 was opened on: `SELECT i2 FROM t WHERE i > k` fuses nothing in its
    // projection, so before this arm the only node that could narrow was the filter's to-row
    // node, and a consumer that wanted batches was handed rows anyway. The pre stage now
    // builds the same pair `columnarSibling` builds, so the plan stays columnar to the sink.
    val child = TestColumnarBatchPlan(Nil, Seq(attrD, intAttr))
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      val plan = ProjectExec(Seq(intAttr), FilterExec(dLess10, child))
      val rewritten = VarkaColumnarRule.preColumnarTransitions(plan)
      assert(rewritten === VarkaProjectExec(Seq(intAttr), VarkaFilterExec(dLess10, child)),
        s"expected a columnar narrowing pair, got:\n$rewritten")
      assert(rewritten.supportsColumnar)
      assert(rewritten.output.map(_.name) === Seq(intAttr.name))
    }
  }

  test("a transition over that pair collapses back to the fused row node") {
    // The other half of the arm above, and the reason it changes no row-consumer plan: where
    // a to-row transition was inserted anyway, the pair becomes exactly the node a row
    // consumer has always had - one that reads the selection bitmap at the row boundary
    // rather than compacting first and projecting after.
    val child = TestColumnarBatchPlan(Nil, Seq(attrD, intAttr))
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      val pair = VarkaProjectExec(Seq(intAttr), VarkaFilterExec(dLess10, child))
      assert(VarkaColumnarRule.postColumnarTransitions(ColumnarToRowExec(pair)) ===
        VarkaFilterColumnarToRowExec(dLess10, child, Some(Seq(intAttr))))
    }
  }

  test("a projection that fuses keeps its own node, narrowing or not") {
    // The ordering the two arms depend on. A projection with anything to fuse is matched by
    // the eligibility arm first and must keep a kernel of its own; only a projection that
    // fuses nothing reaches the narrowing arm. Asserted rather than left to the reader,
    // because the arms are adjacent and a reordering would silently take a kernel away.
    val child = TestColumnarBatchPlan(Nil, withShort)
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      val computed = Seq(Alias(DateAdd(attrD, Literal(3)), "add")())
      val pre = VarkaColumnarRule.preColumnarTransitions(
        ProjectExec(computed, FilterExec(dLess10, child)))
      assert(pre.isInstanceOf[VarkaProjectExec])
      // and its transition is the projection's own fused node, not the filter's
      val post = VarkaColumnarRule.postColumnarTransitions(
        ColumnarToRowExec(pre.asInstanceOf[VarkaProjectExec]))
      assert(post.isInstanceOf[VarkaColumnarToRowExec],
        s"expected the projection's fused transition, got:\n$post")
    }
  }

  test("the absorbed projection travels to the columnar sibling") {
    // VarkaFusedTransition promises "the columnar-out node computing exactly what this fused
    // transition computes", and the cache serializer swaps one for the other when a cached
    // view's top is this node. A sibling that dropped the narrowing would hand the cache a
    // two-column schema where the plan promised one - the same class of error as task 21's
    // dropped filter, which is why this is asserted rather than assumed.
    val child = TestColumnarBatchPlan(Nil, Seq(attrD, intAttr))
    val narrowed = VarkaFilterColumnarToRowExec(dLess10, child, Some(Seq(attrD)))
    val sibling = narrowed.columnarSibling
    assert(sibling.output.map(_.name) === Seq("d"))
    assert(sibling.isInstanceOf[VarkaProjectExec])
    assert(sibling.children.head.isInstanceOf[VarkaFilterExec])
    // Without a narrowing the sibling is what it always was.
    assert(VarkaFilterColumnarToRowExec(dLess10, child).columnarSibling ===
      VarkaFilterExec(dLess10, child))
  }

  test("the row node emits only the narrowed columns, values unchanged") {
    val dates = Seq(Int.box(1), Int.box(50), Int.box(3))
    val ints = Seq(Int.box(7), Int.box(8), Int.box(9))
    // Unnarrowed, the node emits both columns; narrowed to `d`, only the first - and the same
    // rows either way, since the predicate is untouched.
    assert(rowNodeRows(dLess10, Seq(BatchSpec("arrow", Seq(dates, ints))),
      Seq(attrD, intAttr)) === Seq(Seq(1, 7), Seq(3, 9)))
    assert(rowNodeRows(dLess10, Seq(BatchSpec("arrow", Seq(dates, ints))),
      Seq(attrD, intAttr), Some(Seq(attrD))) === Seq(Seq(1), Seq(3)))
    // The narrowing may also reorder and drop, which is what a projection is.
    assert(rowNodeRows(dLess10, Seq(BatchSpec("arrow", Seq(dates, ints))),
      Seq(attrD, intAttr), Some(Seq(intAttr, attrD))) === Seq(Seq(7, 1), Seq(9, 3)))
  }

  test("VarkaColumnarRule: a Varka projection stacks on a Varka filter in one pre pass") {
    val child = TestColumnarBatchPlan(Nil, withShort)
    val plan = ProjectExec(
      Seq(Alias(DateAdd(attrD, Literal(3)), "add")()),
      FilterExec(dLess10, child))
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      val rewritten = VarkaColumnarRule.preColumnarTransitions(plan)
      assert(rewritten.isInstanceOf[VarkaProjectExec],
        s"expected a stacked Varka projection, got:\n$rewritten")
      assert(rewritten.children.head.isInstanceOf[VarkaFilterExec])
    }
    // With a residual conjunct the stack breaks by design: the row FilterExec sits between.
    val mixedPlan = ProjectExec(
      Seq(Alias(DateAdd(attrD, Literal(3)), "add")()),
      FilterExec(And(dLess10, shortResidual), child))
    withSQLConf(SQLConf.VARKA_ENABLED.key -> "true") {
      val rewritten = VarkaColumnarRule.preColumnarTransitions(mixedPlan)
      assert(rewritten.isInstanceOf[ProjectExec])
      assert(rewritten.children.head.isInstanceOf[FilterExec])
    }
  }

  test("stacked filter and projection execute end to end on the kernels") {
    val dates = Seq(Int.box(1), null, Int.box(9), Int.box(42))
    val child = TestColumnarBatchPlan(Seq(BatchSpec("arrow", Seq(dates))), Seq(attrD))
    val stacked = VarkaProjectExec(
      Seq(Alias(DateAdd(attrD, Literal(100)), "add")()),
      VarkaFilterExec(dLess10, child))
    val rows = stacked.executeColumnar().mapPartitions { batches =>
      batches.flatMap { batch =>
        (0 until batch.numRows()).map { r =>
          if (batch.column(0).isNullAt(r)) null else Int.box(batch.column(0).getInt(r))
        }.toList.iterator
      }
    }.collect().toSeq
    assert(rows === Seq(101, 109))
    // The compacted batch keeps the Arrow invariant, so the projection above ran on the
    // kernels rather than falling back.
    assert(stacked.metrics("numVarkaBatches").value === 1)
    assert(stacked.metrics("numFallbackBatchesNonArrow").value === 0)
  }
}
