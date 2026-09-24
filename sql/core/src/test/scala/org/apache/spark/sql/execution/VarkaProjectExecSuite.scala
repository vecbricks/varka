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

import org.apache.spark.{SparkArithmeticException, TaskContext}
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.{AddMonths, Alias, Attribute, AttributeReference, Cast, DateAdd, DateDiff, DateSub, Expression, ExtractANSIIntervalDays, Greatest, Literal, NamedExpression, Remainder}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaAllocationSampler,
  VarkaChrono, VarkaFallbackEvent, VarkaJfrTestSupport, VarkaKernelAllocationEvent}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DateType, DayTimeIntervalType, IntegerType}
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

  test("a mixed projection produces fused, forwarded and residual columns in one batch") {
    val dates = Seq(Int.box(0), null, Int.box(20000))
    val ints = Seq(Int.box(7), Int.box(8), null)
    val plan = node(
      project(
        Alias(DateAdd(attrD, Literal(3)), "a")(),
        intAttr,
        Alias(Remainder(intAttr, Literal(7)), "inc")()),
      Seq(BatchSpec("arrow", Seq(dates, ints))),
      Seq(attrD, intAttr))
    val rows = plan.executeColumnar().mapPartitions { batches =>
      batches.flatMap { batch =>
        (0 until batch.numRows()).map { r =>
          (0 until batch.numCols()).map { c =>
            if (batch.column(c).isNullAt(r)) null else Int.box(batch.column(c).getInt(r))
          }.toList
        }.toList.iterator
      }
    }.collect().toSeq
    assert(rows === Seq(List(3, 7, 0), List(null, 8, 1), List(20003, null, null)))
    assert(plan.metrics("numVarkaBatches").value === 1)
    // Task 22: the residual entry (`inc`) is counted once, driver-side - a static plan
    // property, not multiplied by task count.
    assert(plan.metrics("numResidualEntries").value === 1)
  }

  test("a projection that only forwards columns selects them, and copies nothing") {
    // A projection that computes nothing compiles to no kernel, so the per-batch dispatch
    // would otherwise hand every batch to the row-by-row fallback and rebuild columns the
    // input already holds. It is a selection: the output batch references the input's own
    // vectors, reordered and dropped. Row 145 is why this path exists - the plan that needs
    // it is a narrowing projection over a Varka filter, and it was paying the copy.
    val dates = Seq(Int.box(0), null, Int.box(20000))
    val ints = Seq(Int.box(7), Int.box(8), null)
    val plan = node(
      project(intAttr, attrD),
      Seq(BatchSpec("arrow", Seq(dates, ints))),
      Seq(attrD, intAttr))
    val read = plan.executeColumnar().mapPartitions { batches =>
      batches.map { batch =>
        (batch.numCols(), (0 until batch.numRows()).map { r =>
          (0 until batch.numCols()).map { c =>
            if (batch.column(c).isNullAt(r)) null else Int.box(batch.column(c).getInt(r))
          }.toList
        }.toList)
      }.toList.iterator
    }.collect().toSeq
    // Reordered and narrowed: the int column first, the date second, and `d2` never read.
    assert(read === Seq((2, List(List(7, 0), List(8, null), List(null, 20000)))))
    // Served, not fallen back: a refused batch would count under a fallback cause instead.
    assert(plan.metrics("numVarkaBatches").value === 1)
    assert(plan.metrics("numResidualEntries").value === 0)
  }

  test("a forwarded-only batch is released without closing the input's vectors") {
    // The ownership half. `release` closes exactly what the evaluator owns, and a selection
    // owns nothing: every column in it belongs to the input batch, which the child will
    // reclaim. A batch that reached `release`'s "not one of ours" arm would be closed whole
    // and take the input's vectors with it, which is the same class of error as a dropped
    // filter - invisible in the answers and fatal to the next batch.
    val ints = Seq(Int.box(7), Int.box(8), Int.box(9))
    val plan = node(
      project(intAttr),
      Seq(BatchSpec("arrow", Seq(Seq(Int.box(1), Int.box(2), Int.box(3)), ints)),
        BatchSpec("arrow", Seq(Seq(Int.box(4), Int.box(5), Int.box(6)), ints))),
      Seq(attrD, intAttr))
    // Two batches, read in order: the second is only produced after the first was released,
    // so if releasing the first had closed the input's vectors the second would fail or
    // answer wrongly.
    assert(values(plan) === Seq(7, 8, 9, 7, 8, 9))
    assert(plan.metrics("numVarkaBatches").value === 2)
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
    // `i % 7` is not a kernel op, so every batch goes through the fallback - the node is only
    // ever planned for eligible projections, but it must not produce wrong data if it is not.
    // It replaced `i + 1` here when task 63 lowered int arithmetic and that shape started
    // fusing; `%` has no arm, so this stays an ineligible projection.
    val plan = node(
      project(Alias(Remainder(intAttr, Literal(7)), "add")()),
      Seq(BatchSpec("onheap", Seq(Seq(Int.box(100), Int.box(101))))),
      Seq(intAttr))
    assert(values(plan) === Seq(2, 3))
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
      // Task 22: the ghost fallback is counted under its own cause.
      assert(plan.metrics("numFallbackBatchesKernel").value === 1)
      assert(plan.metrics("numFallbackBatchesNonArrow").value === 0)
    } finally {
      VarkaColumnarToRowExec.setFailKernelForTesting(false)
    }
  }

  test("the fallback warning names the kernel it gave up on") {
    // Before task 16 this line carried only the exception, so a log could not say which plan
    // node or projection had fallen back.
    VarkaColumnarToRowExec.setFailKernelForTesting(true)
    try {
      val appender = new LogAppender("varka fallback")
      withLogAppender(appender) {
        val plan = node(
          project(Alias(DateAdd(attrD, Literal(3)), "add")()),
          Seq(BatchSpec("arrow", Seq(Seq(Int.box(1), null, Int.box(5))))),
          Seq(attrD))
        assert(values(plan) === Seq(4, null, 8))
      }
      val warning = appender.loggingEvents
        .map(_.getMessage.getFormattedMessage)
        .find(_.contains("failed on this batch"))
        .getOrElse(fail("no fallback warning was logged"))
      // The kernel's own telemetry name, plus the IR it computes.
      assert(warning.contains("Varka_Project_Stage"), warning)
      assert(warning.contains("(addDays "), warning)
    } finally {
      VarkaColumnarToRowExec.setFailKernelForTesting(false)
    }
  }

  test("an interval-cast offset past the cast's limit declines the batch, a null " +
      "offset with the same data does not, and an in-range batch runs on the kernel") {
    // The evaluator's pre-check: the compiler bounded the offset column because Spark's
    // CAST(i AS INTERVAL DAY) throws past INTERVAL_DAY_LIMIT_DAYS in every mode, so a batch
    // holding such a live lane must reach the row engine - which throws - rather than the
    // kernel, which would wrap. A null lane's data is undefined and must not count.
    val limit = VarkaChrono.INTERVAL_DAY_LIMIT_DAYS
    def plan(days: Seq[Integer], offsets: Seq[Integer]): VarkaProjectExec = node(
      project(Alias(DateAdd(attrD, ExtractANSIIntervalDays(
        Cast(intAttr, DayTimeIntervalType(DayTimeIntervalType.DAY)))), "shifted")()),
      Seq(BatchSpec("arrow", Seq(days, offsets))),
      Seq(attrD, intAttr))
    val inRange = plan(Seq(Int.box(1), Int.box(2), null), Seq(Int.box(limit), null, Int.box(3)))
    assert(values(inRange) === Seq(1 + limit, null, null))
    assert(inRange.metrics("numVarkaBatches").value === 1)
    assert(inRange.metrics("numFallbackBatchesDeclined").value === 0)
    // The violating value under a null offset: ignored, the batch runs on the kernel.
    val underNull = plan(Seq(Int.box(1), Int.box(2)), Seq(null, Int.box(3)))
    assert(values(underNull) === Seq(null, 5))
    assert(underNull.metrics("numFallbackBatchesDeclined").value === 0)
    // One live lane past the limit: declined, and the row engine raises the cast's own error.
    // The task fails, so its SQL metrics are never merged; the decline is read off the JFR
    // fallback event the evaluator emits before it hands the batch to the row path.
    val (_, recorded) = VarkaJfrTestSupport.withJfrRecording(classOf[VarkaFallbackEvent]) {
      val past = plan(Seq(Int.box(1), Int.box(2)), Seq(Int.box(limit + 1), Int.box(3)))
      intercept[SparkArithmeticException](values(past))
    }
    val causes = recorded
      .filter(VarkaJfrTestSupport.isEvent(_, classOf[VarkaFallbackEvent]))
      .filter(_.getString("kernelIdentity").contains("Varka_Project_"))
      .map(_.getString("cause"))
    assert(causes === Seq(VarkaFallbackEvent.RANGE_DECLINED), causes.mkString("; "))
  }

  test("verbose EXPLAIN accounts for every entry, with the residual entry's reason") {
    val plan = node(
      project(
        Alias(DateAdd(attrD, Literal(3)), "a")(),
        intAttr,
        Alias(Remainder(intAttr, Literal(7)), "inc")()),
      Seq(BatchSpec("arrow", Seq(Seq(Int.box(0)), Seq(Int.box(7))))),
      Seq(attrD, intAttr))
    val explained = plan.verboseStringWithOperatorId()
    assert(explained.contains("Varka"), explained)
    assert(explained.contains("a: fused"), explained)
    assert(explained.contains("i: forwarded from i"), explained)
    assert(explained.contains("inc: residual (unsupported expression:"), explained)
  }

  test("an entry the emitter declines in bytes is residual in the plan, and no task fails " +
      "to emit (task 169)") {
    // PLAN_TASK_169.md: a balanced greatest over thirty-two add_months is one output whose loop
    // method is past HugeMethodLimit. The compiler asks the emitter at planning and leaves it to
    // the row path with the reason, so EXPLAIN says so and the kernel the tasks emit is the one
    // the emitter serves: no emission failure, which before this task was one per task.
    def heavy(lo: Int, hi: Int): Expression =
      if (lo == hi) AddMonths(attrD, Literal(lo))
      else Greatest(Seq(heavy(lo, (lo + hi) / 2), heavy((lo + hi) / 2 + 1, hi)))
    val days = Seq(0, 1, 100, -4000)
    val plan = node(
      project(Alias(heavy(1, 32), "h")(), Alias(DateAdd(attrD, Literal(3)), "a")()),
      Seq(BatchSpec("arrow", Seq(days.map(Int.box)))),
      Seq(attrD))
    val explained = plan.verboseStringWithOperatorId()
    assert(explained.contains("h: residual (over the emitter's method budget ("), explained)
    assert(explained.contains("a: fused"), explained)
    // The greatest of add_months(d, k) over k in 1..32 is add_months(d, 32): the row path's
    // answer, computed here the way Spark computes it.
    assert(values(plan) === days.map(d => Int.box(DateTimeUtils.dateAddMonths(d, 32))))
    assert(plan.metrics("numEmissionFailures").value === 0)
    assert(plan.metrics("numResidualEntries").value === 1)
  }

  test("the fallback projection is compiled lazily, only when a batch falls back") {
    // Same construction as the VarkaColumnarToRowExecSuite counterpart: under CODEGEN_ONLY,
    // [[ExplodingCodegenExpression]] makes building the fallback projection throw, so the
    // evaluator constructor succeeding proves the compile is deferred (task 15), and the
    // failure surfacing on an ineligible batch proves it is deferred exactly to the fallback.
    withSQLConf(SQLConf.CODEGEN_FACTORY_MODE.key -> "CODEGEN_ONLY") {
      val factory = new VarkaProjectEvaluatorFactory(
        project(Alias(ExplodingCodegenExpression(), "boom")()), Seq(intAttr),
        offHeapColumnVectorEnabled = false,
        classDumpDirectory = None,
        SQLMetrics.createMetric(sparkContext, "rows"),
        SQLMetrics.createMetric(sparkContext, "batches"),
        VarkaExecMetrics())
      // Before task 15 this constructor compiled the fallback eagerly and threw.
      val evaluator = factory.createEvaluator()
      val column = new OnHeapColumnVector(1, IntegerType)
      column.putInt(0, 7)
      val batch = new ColumnarBatch(Array(column), 1)
      val e = intercept[Throwable] {
        evaluator.eval(0, Iterator(batch)).next()
      }
      val chain = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(10).toSeq
      assert(chain.exists(_.getMessage.contains("exploding-codegen")),
        s"expected the codegen failure to surface on the fallback path, got: $e")
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
        BatchSpec("onheap", Seq(Seq(Int.box(3)))),
        BatchSpec("arrow", Seq(Seq.empty))),
      Seq(attrD))
    plan.executeColumnar().foreach(_ => ())
    assert(plan.metrics("numOutputRows").value === 3)
    assert(plan.metrics("numInputBatches").value === 3)
    // Only the non-empty Arrow batch reaches the kernels; the on-heap one takes the fallback.
    assert(plan.metrics("numVarkaBatches").value === 1)
    // Task 18: each spec is its own partition, so three tasks looked the shape up - canRun
    // forces the runner on the fallback tasks too, which before the cache emitted a class it
    // never ran and now costs a hit. Hit or miss per task depends on what ran in this JVM.
    assert(plan.metrics("numVarkaCacheHits").value +
      plan.metrics("numVarkaCacheMisses").value === 3)
    // Task 22: the on-heap fallback batch is counted under its cause; nothing else fired -
    // in particular the EMPTY Arrow batch, which canRun also refuses, is served trivially
    // and must not read as "input not Arrow-backed" (the task-21 review's cause fix).
    assert(plan.metrics("numFallbackBatchesNonArrow").value === 1)
    assert(plan.metrics("numFallbackBatchesKernel").value === 0)
    assert(plan.metrics("numEmissionFailures").value === 0)
    assert(plan.metrics("numResidualEntries").value === 0)
  }


  test("a residual-machinery failure is counted under its own cause") {
    // Under CODEGEN_ONLY the residual projection's Janino compile throws inside the kernel
    // try; the VarkaKernelFailure marker keeps it out of the kernel-failure metric and it
    // lands under row-path-failure - once - before the fallback re-throws the same failure.
    withSQLConf(SQLConf.CODEGEN_FACTORY_MODE.key -> "CODEGEN_ONLY") {
      val rowPath = SQLMetrics.createMetric(sparkContext, "rowPath")
      val kernelFailures = SQLMetrics.createMetric(sparkContext, "kernel")
      val factory = new VarkaProjectEvaluatorFactory(
        project(
          Alias(DateAdd(attrD, Literal(3)), "a")(),
          Alias(ExplodingCodegenExpression(), "boom")()),
        Seq(attrD),
        offHeapColumnVectorEnabled = false,
        classDumpDirectory = None,
        SQLMetrics.createMetric(sparkContext, "rows"),
        SQLMetrics.createMetric(sparkContext, "batches"),
        VarkaExecMetrics(
          fallbackBatchesKernel = Some(kernelFailures),
          fallbackBatchesRowPath = Some(rowPath)))
      val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
      val context = TaskContext.empty()
      TaskContext.setTaskContext(context)
      try {
        val batch = VarkaColumnarToRowExecSuite.buildBatch(
          BatchSpec("arrow", Seq(Seq(Int.box(1)))), Seq(attrD), allocator)
        try {
          intercept[Throwable] {
            factory.createEvaluator().eval(0, Iterator(batch)).next()
          }
          assert(rowPath.value === 1)
          assert(kernelFailures.value === 0)
        } finally {
          batch.close()
        }
      } finally {
        context.markTaskCompleted(None)
        TaskContext.unset()
        allocator.close()
      }
    }
  }

  test("an emission failure counts once per task, evented, not mislabeled") {
    // The injected emission failure makes the class lookup throw, so the runner cannot be
    // built: the evaluator counts one emission failure and emits the JFR fallback event, and
    // the per-batch fallbacks are NOT counted as non-Arrow (the carve-out under test).
    val (_, recorded) = VarkaJfrTestSupport.withJfrRecording(classOf[VarkaFallbackEvent]) {
      VarkaColumnarToRowExec.setFailEmissionForTesting(true)
      try {
        val plan = node(
          project(Alias(DateAdd(attrD, Literal(3)), "add")()),
          Seq(BatchSpec("arrow", Seq(Seq(Int.box(1), null, Int.box(5))))),
          Seq(attrD))
        assert(values(plan) === Seq(4, null, 8))
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
      .filter(_.getString("kernelIdentity").contains("Varka_Project_"))
      .map(_.getString("cause"))
    assert(causes.contains(VarkaFallbackEvent.EMISSION_FAILURE), causes.mkString("; "))
  }

  test("the allocation sampler events every sampled batch, and the samples go clean once C2 " +
      "has the loop") {
    // The species-pollution check, wired: under a schedule that samples every batch, each
    // batch produces one event, and the suspect metric agrees with the events' verdicts. The
    // verdicts themselves show why the production schedule does not start at batch 1: the
    // first batches run the kernel interpreted, where every vector is a heap object, and only
    // once C2 has compiled the loop do the samples read clean. How many batches that takes is
    // the machine's business - this laptop compiled the loop inside 300 batches of 1024 rows,
    // GitHub's runner took 1112 - so the test runs rounds of batches until a whole round is
    // clean, and asserts only that this happens within a cap. The positive case - a compiled
    // loop that still boxes - cannot run here without making this JVM box (see
    // VarkaAllocationSamplerSuite). The test plan puts each batch in its own partition, so
    // every batch is its own evaluator's first: the samples are ordered by time, not by the
    // per-evaluator batch index.
    val batchesPerRound = 1200
    val maxRounds = 8
    val column = (0 until 1024).map(Int.box)
    val specs = Seq.fill(batchesPerRound)(BatchSpec("arrow", Seq(column)))
    VarkaKernelEvaluator.allocationSchedule = new VarkaAllocationSampler.Schedule(1, 1)
    val (plans, recorded) = try {
      VarkaJfrTestSupport.withJfrRecording(classOf[VarkaKernelAllocationEvent]) {
        val plans = Seq.newBuilder[VarkaProjectExec]
        var cleanRound = false
        var rounds = 0
        while (!cleanRound && rounds < maxRounds) {
          val plan = node(project(Alias(DateAdd(attrD, Literal(3)), "add")()), specs, Seq(attrD))
          assert(values(plan).length === batchesPerRound * column.length)
          assert(plan.metrics("numVarkaBatches").value === batchesPerRound)
          plans += plan
          rounds += 1
          cleanRound = plan.metrics("numSuspectAllocationSamples").value === 0
        }
        plans.result()
      }
    } finally {
      VarkaKernelEvaluator.allocationSchedule = VarkaAllocationSampler.Schedule.DEFAULT
    }
    val samples = recorded
      .filter(VarkaJfrTestSupport.isEvent(_, classOf[VarkaKernelAllocationEvent]))
      .filter(_.getString("kernelIdentity").contains("Varka_Project_"))
      .sortBy(_.getStartTime)
    val batches = plans.length * batchesPerRound
    assert(samples.length === batches)
    assert(samples.forall(_.getInt("rows") === column.length))
    assert(samples.forall(_.getLong("batchIndex") === 1L))
    val verdicts = samples.map(_.getBoolean("suspect"))
    val suspectMetric = plans.map(_.metrics("numSuspectAllocationSamples").value).sum
    assert(suspectMetric === verdicts.count(identity))
    val lastSuspect = verdicts.lastIndexOf(true)
    val bytes = samples.map(_.getLong("allocatedBytes"))
    assert(lastSuspect < batches - batchesPerRound,
      s"no clean round within $maxRounds rounds of $batchesPerRound batches; last suspect at " +
        s"sample $lastSuspect; tail bytes ${bytes.takeRight(20).mkString(" ")}")
    logInfo(s"allocation samples: ${verdicts.count(identity)} suspect, last at $lastSuspect " +
      s"of $batches, tail ${bytes.takeRight(5).mkString(" ")}")
  }

  /** Drives the evaluator directly, so a test can control when the next batch is requested. */
  private def evaluate(inputs: Iterator[ColumnarBatch]): Iterator[ColumnarBatch] = {
    val factory = new VarkaProjectEvaluatorFactory(
      project(Alias(DateAdd(attrD, Literal(3)), "add")()),
      Seq(attrD),
      offHeapColumnVectorEnabled = false,
      classDumpDirectory = None,
      SQLMetrics.createMetric(sparkContext, "rows"),
      SQLMetrics.createMetric(sparkContext, "batches"),
      VarkaExecMetrics())
    factory.createEvaluator().eval(0, inputs)
  }
}
