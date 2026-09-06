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

import java.io.File
import java.lang.foreign.MemorySegment
import java.nio.file.Files

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.apache.arrow.memory.{ArrowBuf, BufferAllocator}
import org.apache.arrow.vector.{BaseFixedWidthVector, DateDayVector, IntVector, ValueVector, VarCharVector}

import org.apache.spark.{SparkContext, TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, NamedExpression, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection, ForwardedOutput, FusedOutput, PartialVarkaProjection, ResidualOutput, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{IntRangeOps, SelectionVectorOps,
  VarkaAllocationSampler, VarkaFallbackEvent, VarkaFusedKernel, VarkaKernelAllocationEvent,
  VarkaSelectionBitmap, VarkaShapeCache, VarkaShapeKey, VarkaVectorIR, WeekdayLeaf}
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.execution.vectorized.{OffHeapColumnVector, OnHeapColumnVector, WritableColumnVector}
import org.apache.spark.sql.types.{DateType, IntegerType, StructType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}

/**
 * The Varka-specific SQL metrics one exec node threads to its factory and evaluator (task 22),
 * bundled so the parameter lists stop growing metric by metric (task 18 threaded two options;
 * this task would have made it five). Every field is optional: suites and diagnostics
 * construct evaluators with none. Deliberately Scala rather than a Java record (the task-21
 * review's call, recorded): every construction site is forced-Scala code leaning on named
 * arguments and defaults over seven same-typed fields, where a record's positional constructor
 * would be a silent-swap hazard.
 */
private[sql] case class VarkaExecMetrics(
    varkaBatches: Option[SQLMetric] = None,
    cacheHits: Option[SQLMetric] = None,
    cacheMisses: Option[SQLMetric] = None,
    fallbackBatchesNonArrow: Option[SQLMetric] = None,
    fallbackBatchesKernel: Option[SQLMetric] = None,
    fallbackBatchesRowPath: Option[SQLMetric] = None,
    fallbackBatchesDeclined: Option[SQLMetric] = None,
    emissionFailures: Option[SQLMetric] = None,
    suspectAllocationSamples: Option[SQLMetric] = None)

private[sql] object VarkaExecMetrics {

  /**
   * The metric set every Varka node registers, defined once (task-21 review: the four nodes
   * carried byte-identical copies, where a changed key or description would compile clean and
   * fork the UI vocabularies). `numOutputRows` semantics stay per node: a projection counts
   * input rows, a filter counts selected rows.
   */
  def nodeMetrics(sparkContext: SparkContext): Map[String, SQLMetric] = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
    "numInputBatches" -> SQLMetrics.createMetric(sparkContext, "number of input batches"),
    "numVarkaBatches" -> SQLMetrics.createMetric(
      sparkContext, "number of input batches processed by the Varka SIMD kernels"),
    "numVarkaCacheHits" -> SQLMetrics.createMetric(
      sparkContext, "number of tasks served a kernel class by the Varka shape cache"),
    "numVarkaCacheMisses" -> SQLMetrics.createMetric(
      sparkContext, "number of tasks that emitted and defined the Varka kernel class"),
    "numFallbackBatchesNonArrow" -> SQLMetrics.createMetric(
      sparkContext, "batches falling back: input not Arrow-backed"),
    "numFallbackBatchesKernel" -> SQLMetrics.createMetric(
      sparkContext, "batches falling back: kernel failure (the ghost fallback)"),
    "numFallbackBatchesRowPath" -> SQLMetrics.createMetric(
      sparkContext, "batches falling back: per-row machinery failure beside the kernel"),
    "numFallbackBatchesDeclined" -> SQLMetrics.createMetric(
      sparkContext, "batches falling back: a value outside a lowering's range (task 26)"),
    "numEmissionFailures" -> SQLMetrics.createMetric(
      sparkContext, "tasks that could not emit or define the kernel class"),
    "numSuspectAllocationSamples" -> SQLMetrics.createMetric(
      sparkContext, "sampled kernel batches that allocated like a boxing Vector API loop"))

  /** [[nodeMetrics]] plus the projection nodes' static residual-entry count; a filter's
   * residual is a visible row `FilterExec` above it rather than a number. */
  def projectionMetrics(sparkContext: SparkContext): Map[String, SQLMetric] =
    nodeMetrics(sparkContext) + ("numResidualEntries" -> SQLMetrics.createMetric(
      sparkContext, "projection entries declined to the per-row residual (reasons in EXPLAIN)"))

  /** The evaluator-facing bundle built from a node's registered metrics. */
  def fromNode(metric: String => SQLMetric): VarkaExecMetrics = VarkaExecMetrics(
    varkaBatches = Some(metric("numVarkaBatches")),
    cacheHits = Some(metric("numVarkaCacheHits")),
    cacheMisses = Some(metric("numVarkaCacheMisses")),
    fallbackBatchesNonArrow = Some(metric("numFallbackBatchesNonArrow")),
    fallbackBatchesKernel = Some(metric("numFallbackBatchesKernel")),
    fallbackBatchesRowPath = Some(metric("numFallbackBatchesRowPath")),
    fallbackBatchesDeclined = Some(metric("numFallbackBatchesDeclined")),
    emissionFailures = Some(metric("numEmissionFailures")),
    suspectAllocationSamples = Some(metric("numSuspectAllocationSamples")))
}

/**
 * Marks a throwable as coming from the emitted kernel invocation itself (task-21 review): the
 * exec nodes label their per-batch catch by it, so a catchable failure in the per-row
 * machinery running beside the kernel - a residual or merge projection's compile or
 * evaluation - is not metered as a kernel failure.
 */
private[execution] class VarkaKernelFailure(cause: Throwable) extends Exception(cause)

/**
 * The batch was declined to the row engine: the kernel ran and returned a non-zero status
 * (task 26), meaning some lane lay outside the range a partial lowering is defined over, or
 * the evaluator's own pre-check found an input lane outside a bound the compiler recorded
 * (task 56, [[VarkaKernelEvaluator.STATUS_INPUT_BOUND]]). Not an error - it carries no cause
 * and no stack trace, because it is control flow on a designed path, and [[serveBatch]] turns
 * it into the row-engine fallback.
 */
private[execution] class VarkaBatchDeclined(val status: Int)
  extends Exception(null, null, false, false)

/**
 * The task-lifetime machinery shared by every Varka evaluator (split out of
 * [[VarkaKernelEvaluator]] in task 21, when the filter evaluator became its second user): the
 * shape-cached kernel runner and its argument arrays, the task's Arrow allocator, the
 * open-batch ledger with its task-completion safety net, the Arrow-backed `canRun` test, and
 * the telemetry names. A concrete evaluator supplies the compiled fused sub-plan the kernel
 * follows and what its identity reads as in the shape cache's side table; the projection and
 * filter evaluators below own everything specific to their output shape - vector allocation
 * and batch assembly there, the selection bitmap here.
 *
 * The ownership and ordering contracts documented on [[VarkaKernelEvaluator]] are implemented
 * here and hold for every subclass.
 */
private[sql] abstract class VarkaEvaluatorBase(
    childOutput: Seq[Attribute],
    operatorName: String,
    classDumpDirectory: Option[String],
    metrics: VarkaExecMetrics)
    extends Logging {

  /** The fused sub-plan the kernel computes; None when nothing is Varka-eligible. */
  protected def fusedPlan: Option[CompiledVarkaProjection]

  /**
   * The entries rendered into the shape cache's side-table identity, in order - a projection's
   * entries, a filter's condition. Consumed lazily so a wide list is rendered only up to the
   * table's length cap.
   */
  protected def identityEntries: Iterator[String]

  // One Arrow child allocator for the whole task, created on the first kernel batch. Allocating
  // one per batch - and registering a task-completion listener per batch to close it - would
  // hold every result batch off-heap until the task ended, which is exactly what the streaming
  // iterator model exists to avoid.
  private var kernelAllocator: BufferAllocator = null

  // Batches handed out and not released yet, each mapped to the vectors this evaluator owns in
  // it - never the forwarded input vectors (see the ownership note in the class doc). A batch
  // is normally released by the caller as soon as it is done with it; the map is the safety net
  // for a task that stops early (a LIMIT, a failure) and is drained by the task-completion
  // listener.
  private val openBatches = mutable.Map.empty[ColumnarBatch, Seq[ColumnVector]]

  private var cleanupRegistered = false

  // The task-lifetime emitted fused loop and its reused argument arrays. None when emission
  // failed - an IR shape past the emitter's caps, or any linkage problem - in which case every
  // batch takes the caller's fallback path.
  protected lazy val fusedRunner: Option[FusedRunner] = {
    fusedPlan.flatMap { plan =>
      try {
        Some(new FusedRunner(plan))
      } catch {
        case e if isCatchable(e) =>
          logWarning(s"Failed to emit the Varka fused kernel $kernelIdentity; falling back " +
            "to the per-row path.", e)
          metrics.emissionFailures.foreach(_ += 1)
          VarkaKernelEvaluator.emitFallbackEvent(VarkaFallbackEvent.EMISSION_FAILURE,
            kernelIdentity, e.getClass.getName)
          None
      }
    }
  }

  /**
   * Whether this task tried and failed to obtain its kernel class (task 22): the plan
   * compiled but the runner could not be built. The exec nodes use it to keep the per-batch
   * fallback cause honest - after an emission failure every batch fails `canRun`, which
   * without this test would count as "input not Arrow-backed".
   */
  private[execution] def emissionFailed: Boolean = fusedPlan.nonEmpty && fusedRunner.isEmpty

  /**
   * This execution's identity - the operator and this task's stage - which since task 18 goes
   * to [[VarkaShapeCache]]'s side table rather than into the shared class bytes. Outside a
   * task (diagnostics, tests) the stage reads as -1 rather than throwing.
   */
  private def executionName: String = {
    val stage = Option(TaskContext.get()).map(_.stageId()).getOrElse(-1)
    s"Varka_${operatorName}_Stage$stage"
  }

  /**
   * The identity recorded in the cache's side table: the execution name, then as much of the
   * evaluator's entries as the table keeps
   * ([[VarkaShapeCache.maxExecutionIdentityLength]]). Bounded while building: rendering all
   * of a wide projection on every task's setup path would be paid only to be truncated on
   * arrival, or discarded outright when the cache is disabled.
   */
  private def executionIdentity(): String = {
    val sb = new StringBuilder(executionName).append(": ")
    val it = identityEntries
    while (it.hasNext && sb.length <= VarkaShapeCache.maxExecutionIdentityLength) {
      sb.append(it.next())
      if (it.hasNext) sb.append(", ")
    }
    sb.toString
  }

  /** The cache key of the fused sub-plan: exactly the emitter inputs the bytes follow. */
  protected def shapeKey(plan: CompiledVarkaProjection): VarkaShapeKey =
    new VarkaShapeKey(plan.outputs.asJava, plan.inputOrdinals.size, plan.literals.size,
      VarkaColumnarToRowExec.currentEmitOptions)

  /**
   * The kernel named the way its telemetry names it (task 16, shape-based since task 18): the
   * `SourceFile` of the shared class, the IR it computes, and this execution's operator and
   * stage. Every fallback warning - here and in the exec nodes - says which kernel it gave up
   * on, so a log line identifies both the class and the plan node without correlation.
   * Reading it forces no emission: the shape hash is computed from the IR, not the bytes.
   * A lazy val (task-21 review): the rendering hashes the canonical IR, and it is constant
   * per evaluator, so per-batch fallback paths must not recompute it.
   *
   * The IR renders through `VarkaVectorIR.canonical` rather than `Record.toString` (task 23,
   * with the line map): the same rendering the class's own `VarkaDebugInfo` carries, so a log
   * line and the bytes it names describe the shape the same way - and neither depends on a
   * format no JDK promises.
   */
  private[execution] lazy val kernelIdentity: String = {
    fusedPlan match {
      case Some(plan) =>
        val ir = plan.outputs.map(VarkaVectorIR.canonical).mkString(", ")
        val hash = VarkaShapeCache.shapeHash(shapeKey(plan))
        s"${VarkaShapeCache.sourceFileFor(hash)} [$ir] ($executionName)"
      case None => s"[no compiled projection] ($executionName)"
    }
  }

  /**
   * The emitted fused-kernel class's bytes, exactly as defined - the diagnostics hook behind
   * the telemetry note in [[VarkaKernelEvaluator]]'s class doc: `VarkaDebugInfo.read` and
   * `ClassFile.parse` recover the IR, the plan fragment and the `SourceFile` name from them.
   * Forces emission if no batch has done so yet; None when the plan is ineligible or emission
   * failed.
   */
  private[execution] def emittedClassBytes: Option[Array[Byte]] = fusedRunner.map(_.classBytes)

  /**
   * Whether the kernel can serve this batch, or the caller has to fall back. The Arrow check
   * covers only the columns the fused sub-plan references: other entries put no constraint on
   * the input format beyond what `rowIterator` needs.
   */
  def canRun(input: ColumnarBatch): Boolean = {
    (fusedPlan, fusedRunner) match {
      case (Some(plan), Some(_)) => input.numRows() > 0 && isArrowBacked(plan, input)
      case _ => false
    }
  }

  // ---------------------------------------------------------------------------------------
  // Per-batch fallback accounting, shared by all four exec nodes (task-21 review: the nodes
  // carried byte-identical copies of these blocks, which had already begun to drift). Each
  // method counts and events one batch under its actual cause; the caller then takes its
  // own fallback path.
  // ---------------------------------------------------------------------------------------

  /**
   * The per-batch dispatch every exec node runs (task-21 review, second pass: the
   * canRun/catch/refuse skeleton had grown into four identical copies - the very drift
   * surface whose accounting half the first pass deduplicated): the kernel path under the
   * shared cause accounting, with every degradation routed to the caller's fallback.
   */
  private[execution] def serveBatch[T](input: ColumnarBatch)(kernelPath: => T)(
      fallbackPath: => T): T = {
    if (canRun(input)) {
      try {
        kernelPath
      } catch {
        // Not a failure: the kernel ran and said it could not answer for this batch.
        case e: VarkaBatchDeclined =>
          recordDeclinedBatch(e.status)
          fallbackPath
        // A genuine kernel error is told apart from a failure in the per-row machinery
        // sharing the try by the marker invokeFused wraps it in.
        case e: VarkaKernelFailure =>
          recordKernelFailure(e.getCause)
          fallbackPath
        case e if isCatchable(e) =>
          recordRowPathFailure(e)
          fallbackPath
      }
    } else {
      recordRefusedBatch(input)
      fallbackPath
    }
  }

  // The species-pollution check (SKILLS.md, "Every operator the plans rely on ..."): a kernel
  // that boxes still answers correctly, so no fallback path and no differential test can see
  // it - only its allocation rate can. Sampled on the schedule VarkaAllocationSampler explains,
  // never on every batch: the two management reads cost more than a short kernel call.
  private var kernelBatches = 0L
  private val allocationSampling = VarkaAllocationSampler.supported()
  private val allocationTracker = new VarkaAllocationSampler.Tracker

  /** One allocation sample of the kernel call: evented always, counted and warned when suspect. */
  private def recordAllocationSample(allocatedBytes: Long, rows: Int): Unit = {
    val suspect = VarkaAllocationSampler.suspect(allocatedBytes, rows)
    VarkaKernelEvaluator.emitAllocationEvent(kernelIdentity, kernelBatches, rows, allocatedBytes,
      suspect)
    if (suspect) {
      metrics.suspectAllocationSamples.foreach(_ += 1)
      if (allocationTracker.record(true)) {
        logWarning(s"The Varka SIMD kernels $kernelIdentity allocated $allocatedBytes bytes " +
          s"over a $rows-row batch (batch $kernelBatches), and did so on the previous sample " +
          "too. A kernel that runs as emitted allocates nothing per row; this rate means the " +
          "Vector API is boxing its vectors, most likely because two vector species of one " +
          "lane type ran hot in this JVM (SKILLS.md, the species-pollution section). Results " +
          "are still correct; the kernel is several times slower than it should be.")
      }
    } else {
      allocationTracker.record(false)
    }
  }

  /** The ghost fallback's bookkeeping: an error from the emitted kernel itself. */
  private def recordKernelFailure(e: Throwable): Unit = {
    logWarning(s"The Varka SIMD kernels $kernelIdentity failed on this batch; falling back " +
      "to the per-row path.", e)
    metrics.fallbackBatchesKernel.foreach(_ += 1)
    VarkaKernelEvaluator.emitFallbackEvent(VarkaFallbackEvent.KERNEL_FAILURE, kernelIdentity,
      e.getClass.getName)
  }

  /**
   * A catchable failure from the per-row machinery running beside the kernel - the residual
   * or merge projection's compile or evaluation - which the task-21 review split out of the
   * kernel metric: it is not the kernel's failure, and a throwing lazy re-runs its
   * initializer, so counting it there would inflate the ghost-fallback metric on every
   * batch. Counted under its own bounded cause metric (the review's second pass: with no
   * metric these batches vanished from the SQL UI entirely), evented and logged.
   */
  private def recordRowPathFailure(e: Throwable): Unit = {
    logWarning(s"The per-row machinery beside the Varka kernel $kernelIdentity failed on " +
      "this batch; falling back to the per-row path.", e)
    metrics.fallbackBatchesRowPath.foreach(_ += 1)
    VarkaKernelEvaluator.emitFallbackEvent(VarkaFallbackEvent.ROW_PATH_FAILURE, kernelIdentity,
      e.getClass.getName)
  }

  /**
   * A batch the kernel itself declined (task 26): a lowering that is correct only over part of
   * its input domain met a value outside it - a date beyond the narrowed civil-from-days range
   * - and reported it rather than publishing an answer it does not have. Logged at debug
   * rather than warning: unlike the ghost fallback this is a designed outcome, not a defect,
   * and a batch of far-future dates would otherwise fill the log.
   */
  private def recordDeclinedBatch(status: Int): Unit = {
    logDebug(s"The Varka SIMD kernels $kernelIdentity declined this batch (status $status); " +
      "falling back to the per-row path.")
    metrics.fallbackBatchesDeclined.foreach(_ += 1)
    // The third field is the event's exceptionClass, and a declined batch has no exception:
    // passing the status there would put "1" in a JFR column a dashboard groups by class
    // name. The status is in the log line above, where it belongs.
    VarkaKernelEvaluator.emitFallbackEvent(VarkaFallbackEvent.RANGE_DECLINED, kernelIdentity, "")
  }

  /**
   * A batch [[canRun]] refused, counted under its actual cause (task-21 review: the nodes
   * used to label every refusal "input not Arrow-backed"): an emission failure was already
   * counted once per task by the emission catch; an empty batch is served trivially and is
   * no fallback at all; an ineligible plan (defensive - the rule should not have fused it)
   * is not a data-format property. Only a non-empty batch whose referenced columns fail the
   * Arrow check is the non-Arrow cause the metric names.
   */
  private def recordRefusedBatch(input: ColumnarBatch): Unit = {
    if (!emissionFailed && fusedPlan.nonEmpty && input.numRows() > 0) {
      metrics.fallbackBatchesNonArrow.foreach(_ += 1)
      VarkaKernelEvaluator.emitFallbackEvent(VarkaFallbackEvent.NON_ARROW_BATCH,
        kernelIdentity, "")
    }
  }

  /**
   * Takes ownership of a batch the caller built itself - a fallback batch, every column the
   * caller's own - so that the same task-completion listener closes it if the task stops before
   * the caller releases it.
   */
  def track(batch: ColumnarBatch): ColumnarBatch = {
    trackOwned(batch, (0 until batch.numCols()).map(batch.column))
    batch
  }

  protected def trackOwned(batch: ColumnarBatch, owned: Seq[ColumnVector]): Unit = {
    ensureCleanup()
    openBatches(batch) = owned
  }

  /**
   * Releases a batch obtained from this evaluator or handed to [[track]]: closes exactly the
   * vectors this evaluator owns in it, so a forwarded input vector is left to its input batch.
   */
  def release(batch: ColumnarBatch): Unit = {
    openBatches.remove(batch) match {
      case Some(owned) => owned.foreach(_.close())
      // Not one of ours - nothing borrowed can be inside, so closing it whole is safe.
      case None => batch.close()
    }
  }

  /** A kernel failure worth falling back on, rather than one that has to fail the task. */
  def isCatchable(e: Throwable): Boolean = {
    NonFatal(e) || e.isInstanceOf[LinkageError]
  }

  /**
   * Whether the kernel can run over this batch: every referenced column must be an Arrow
   * `DateDayVector` or `IntVector` (task 38 - a day-offset column) holding exactly the batch's
   * rows, no more - or, for an input the evaluator derives (task 59), an Arrow `VarCharVector`
   * of the same row count, the one string vector the Arrow cache produces and the derived
   * leaf reads; the large and view string vectors refuse the batch like any other column type.
   *
   * The row count matters because the kernel takes a null count for the rows it is given,
   * while a vector's null count covers all `valueCount` of its rows. A vector longer than the
   * batch would hand it a count for rows that are not in it - and a vector whose extra rows
   * happen to hold every null would make that count equal the batch's row count, tripping the
   * all-null shortcut over rows that are not null at all. Such a batch takes the caller's
   * fallback; serving it from the kernels would mean counting nulls over `[0, len)` here
   * instead.
   */
  private def isArrowBacked(plan: CompiledVarkaProjection, input: ColumnarBatch): Boolean = {
    // Indexed rather than `zipWithIndex.forall`: this runs once per batch for every Varka
    // query, and zipping allocates a tuple per input column each time on a gate that was
    // otherwise allocation-free.
    val ordinals = plan.inputOrdinals
    val rows = input.numRows()
    var i = 0
    while (i < ordinals.length) {
      val ok = input.column(ordinals(i)) match {
        case acv: ArrowColumnVector =>
          (acv.getValueVector(), plan.derivedAt(i)) match {
            case (v: DateDayVector, None) => v.getValueCount() == rows
            case (v: IntVector, None) => v.getValueCount() == rows
            case (v: VarCharVector, Some(_)) => v.getValueCount() == rows
            case _ => false
          }
        case _ => false
      }
      if (!ok) {
        return false
      }
      i += 1
    }
    true
  }

  /** A subclass's extra cleanup, run by the task-completion listener before the allocator
   * closes - the filter evaluator releases its selection buffer here. */
  protected def onTaskCleanup(): Unit = {}

  // The derived inputs' scratch buffers (task 59), one data and one validity buffer per kernel
  // input the evaluator derives, reused across batches and grown on demand under the filter's
  // maskBuf discipline; released by the task-completion listener before the allocator closes.
  // Read only inside kernel.run, so a batch never sees another batch's fill.
  private var derivedData: Array[ArrowBuf] = null
  private var derivedValidity: Array[ArrowBuf] = null

  private def derivedScratch(i: Int, len: Int): Unit = {
    if (derivedData == null) {
      val n = fusedPlan.get.inputOrdinals.size
      derivedData = new Array[ArrowBuf](n)
      derivedValidity = new Array[ArrowBuf](n)
    }
    val dataNeeded = math.max(len * 4L, 8L)
    val validityNeeded = ((len + 63) / 64) * 8L
    if (derivedData(i) == null || derivedData(i).capacity() < dataNeeded) {
      derivedData(i) = grown(derivedData(i), dataNeeded)
    }
    if (derivedValidity(i) == null || derivedValidity(i).capacity() < validityNeeded) {
      derivedValidity(i) = grown(derivedValidity(i), validityNeeded)
    }
  }

  /**
   * Allocates `needed` bytes and only then closes `old` (if any), so that no freed buffer can
   * stay referenced.
   *
   * The order is the whole point, and the reverse of it was a use-after-free. `buffer` throws
   * `OutOfMemoryException` when the allocator cannot satisfy the request, and that is a plain
   * `RuntimeException`, so `serveBatch` catches it as a per-batch failure, falls back to the row
   * path and *keeps the task running*. Closing first meant that on the throwing path the caller's
   * assignment never happened - Scala evaluates the right-hand side before the array store - and
   * `derivedData(i)` was left holding a closed buffer. Arrow's `close()` only releases the
   * reference; `capacity()` and `memoryAddress()` are plain field reads it does not touch, so
   * the next, smaller batch found the stale capacity still large enough, skipped the regrow and
   * had the leaf write through an address the allocator had already freed - and the task's
   * cleanup then closed the same buffer a second time, taking the reference count negative.
   * Allocating first costs both buffers for the width of one assignment and cannot leave a
   * freed one behind.
   */
  private def grown(old: ArrowBuf, needed: Long): ArrowBuf = {
    val alloc = taskAllocator()
    val fresh = alloc.buffer(needed)
    if (old != null) {
      old.close()
    }
    fresh
  }

  /**
   * Closes every derived-input scratch buffer and drops the arrays.
   *
   * Each slot is cleared before its buffer is closed and each close is guarded on its own, so
   * that one throwing `close()` cannot leave the rest unclosed or leave a closed buffer
   * referenced - the `maskBuf` discipline this follows nulls its field before the close for the
   * same reason. The arrays go first, so even a failure part-way leaves the evaluator with no
   * scratch rather than with half-released scratch: the next batch reallocates, which is correct
   * if wasteful, where reusing a partly-closed array is not. Whatever a close throws is logged
   * and swallowed; this runs from the task-completion listener, where the allocator close below
   * it matters more than any one buffer, and a throw here would mask the task's real error.
   */
  private def releaseDerivedScratch(): Unit = {
    val data = derivedData
    val validity = derivedValidity
    derivedData = null
    derivedValidity = null
    closeScratch(data)
    closeScratch(validity)
  }

  private def closeScratch(buffers: Array[ArrowBuf]): Unit = {
    if (buffers != null) {
      var i = 0
      while (i < buffers.length) {
        val b = buffers(i)
        buffers(i) = null
        if (b != null) {
          closeQuietly(b, "a Varka derived-input scratch buffer")
        }
        i += 1
      }
    }
  }

  /**
   * Closes one resource on a path that must not be derailed by the close itself: whatever it
   * throws is logged and swallowed.
   *
   * Used where something has already gone wrong, or where the caller is on its way out. On a
   * failure path the original exception is the one worth keeping - a `foreach(_.close())` there
   * both strands every resource after the one that threw and replaces the error being reported
   * with a cleanup error, which is the same objection the task-completion listener's own guard
   * was written for.
   */
  protected def closeQuietly(c: AutoCloseable, what: String): Unit = {
    try {
      c.close()
    } catch {
      case NonFatal(e) => logWarning(s"Closing $what failed.", e)
    }
  }

  /** [[closeQuietly]] over a collection, guarding each element separately. */
  protected def closeAllQuietly(cs: Iterable[_ <: AutoCloseable], what: String): Unit = {
    cs.foreach(closeQuietly(_, what))
  }

  /**
   * Registers the single task-completion listener that closes any batch still open and then the
   * allocator. Both this and [[taskAllocator]] are called from the task thread only.
   */
  protected def ensureCleanup(): Unit = {
    if (!cleanupRegistered) {
      cleanupRegistered = true
      TaskContext.get().addTaskCompletionListener[Unit] { _ =>
        // Every stage here frees task-lifetime Arrow memory, and each is guarded separately so
        // that one failure cannot skip the others. The reason was written for the hook alone -
        // a throw must not skip the allocator close below, or the child allocator's accounting
        // leaks against the shared root for the JVM's lifetime and the task's real error is
        // masked (task-21 review, second pass) - and it applies word for word to its
        // neighbours, which a later review noticed close Arrow buffers too. One try around the
        // whole prologue would satisfy the letter of that and not its point: a throwing batch
        // close would still cost the scratch release and the hook.
        closeAllQuietly(openBatches.values.flatten, "a Varka batch left open at task completion")
        openBatches.clear()
        releaseDerivedScratch()
        try {
          onTaskCleanup()
        } catch {
          case NonFatal(e) => logWarning("Varka task-cleanup hook failed.", e)
        }
        if (kernelAllocator != null) {
          kernelAllocator.close()
          kernelAllocator = null
        }
      }
    }
  }

  /** Returns the task's Arrow child allocator, creating it on first use. */
  protected def taskAllocator(): BufferAllocator = {
    ensureCleanup()
    if (kernelAllocator == null) {
      kernelAllocator =
        ArrowUtils.rootAllocator.newChildAllocator("varka-kernels", 0, Long.MaxValue)
    }
    kernelAllocator
  }

  /**
   * Writes the emitted class to the configured dump directory under its `SourceFile` name
   * (task 16), so `javap -c -p` reaches a generated loop with no debugger. Diagnostics only:
   * every failure is logged and swallowed, because a query must not fail over a debug write.
   * Every task of a shape holds identical bytes (task 18), so a per-JVM memo makes the
   * shape's first task with the directory configured write the file once, instead of every
   * task re-writing it on the task-setup path. The memo is per-process on purpose: the file
   * name derives from the shape, not the bytes, so a file left by an *older* emitter must be
   * overwritten, not trusted - each JVM's first write refreshes it. (Two first tasks can
   * still race past the memo; they write the same bytes, so the race is benign.)
   */
  private def dumpClass(sourceFile: String, bytes: Array[Byte]): Unit = {
    classDumpDirectory.foreach { directory =>
      val memoKey = s"$directory|$sourceFile"
      if (VarkaKernelEvaluator.dumpedClassFiles.add(memoKey)) {
        try {
          val target = new File(directory, sourceFile.stripSuffix(".java") + ".class")
          Files.createDirectories(target.toPath.getParent)
          Files.write(target.toPath, bytes)
          logInfo(s"Wrote the Varka kernel class to ${target.getAbsolutePath}")
        } catch {
          case NonFatal(e) =>
            VarkaKernelEvaluator.dumpedClassFiles.remove(memoKey)
            logWarning(s"Could not dump the Varka kernel class to $directory; " +
              "execution is unaffected.", e)
        }
      }
    }
  }

  /**
   * Fills the runner's source-side argument arrays from the input batch - one morsel per
   * referenced input column, in dense kernel-input order. `canRun` has vouched for every
   * column this reads. A derived input (task 59) is computed here, before the kernel runs,
   * into the task's scratch buffers: the string column goes through the row engine's own
   * parser and the kernel reads the int32 result like any other input. The leaf never throws;
   * under ANSI an unrecognised name declines the batch, and the row engine - which parses a
   * name only beside a non-null date - raises its own error where one is due.
   */
  protected def fillSources(runner: FusedRunner, input: ColumnarBatch, len: Int): Unit = {
    val plan = fusedPlan.get
    var i = 0
    plan.inputOrdinals.foreach { ordinal =>
      val acv = input.column(ordinal).asInstanceOf[ArrowColumnVector]
      plan.derivedAt(i) match {
        case None =>
          val morsel =
            extractMorsel(acv.getValueVector().asInstanceOf[BaseFixedWidthVector], len)
          runner.srcData(i) = morsel.data.address()
          runner.srcValidity(i) = morsel.validityAddress
          runner.srcNullCount(i) = morsel.nullCount.toInt
        case Some(derived) =>
          derivedScratch(i, len)
          val data = derivedData(i)
          val validity = derivedValidity(i)
          val nulls = WeekdayLeaf.fill(acv, len, derived.kind.failOnError,
            WeekdayLeaf.DEFAULT_PARSER, data.memoryAddress(), validity.memoryAddress())
          if (nulls == WeekdayLeaf.DECLINED) {
            throw new VarkaBatchDeclined(VarkaKernelEvaluator.STATUS_DERIVED_INPUT)
          }
          // The leaf writes (len + 7) / 8 validity bytes; the rest of the words the kernel
          // reads is zeroed so a longer earlier batch's bits cannot read as lanes past `len`.
          // The bound is what `derivedScratch` sized the buffer to need, not its capacity: the
          // scratch grows and is never shrunk, so after one wide batch the capacity can be many
          // times the words in play and zeroing to it memsets a buffer nothing will read - the
          // kernel addresses validity at `row / 8` and never past the batch's own words. The
          // comment here used to say "the last word" while the code said "to capacity"; this is
          // the version the comment described.
          val written = (len + 7) / 8
          val readable = ((len + 63) / 64) * 8L
          validity.setZero(written, readable - written)
          runner.srcData(i) = data.memoryAddress()
          runner.srcValidity(i) = if (nulls == len) 0L else validity.memoryAddress()
          runner.srcNullCount(i) = nulls
      }
      i += 1
    }
    // Task 56: an input the compiler bounded - today a day offset that came from
    // CAST(i AS INTERVAL DAY), which Spark's cast throws on past the bound - is checked before
    // the kernel runs, over its live lanes only. A lane outside declines the batch the same
    // way a kernel status does: the row engine recomputes it and raises the error the kernel
    // cannot. One vector compare pass over the column, priced in VarkaThroughputBenchmark
    // against the unbounded date_add row.
    plan.inputBounds.foreach { b =>
      val k = b.inputIndex
      if (!IntRangeOps.allWithin(runner.srcData(k), runner.srcValidity(k), runner.srcNullCount(k),
          len, b.lo, b.hi)) {
        throw new VarkaBatchDeclined(VarkaKernelEvaluator.STATUS_INPUT_BOUND)
      }
    }
  }

  /**
   * Invokes the emitted loop, marking any catchable throw as [[VarkaKernelFailure]] so the
   * exec nodes' catch can tell a genuine kernel error from a failure in the per-row
   * machinery that shares the same try (task-21 review). A fatal error passes unmarked.
   */
  protected def invokeFused(runner: FusedRunner, len: Int): Unit = {
    kernelBatches += 1
    val sampled = allocationSampling && VarkaKernelEvaluator.allocationSchedule.due(kernelBatches)
    val before = if (sampled) VarkaAllocationSampler.allocatedBytes() else 0L
    val status = try {
      if (VarkaColumnarToRowExec.isFailKernelForTesting) {
        // scalastyle:off throwerror
        throw new NoClassDefFoundError("injected Varka kernel failure")
        // scalastyle:on throwerror
      }
      runner.kernel.run(runner.srcData, runner.srcValidity, runner.srcNullCount,
        runner.dstData, runner.dstValidity, runner.scalarArgs, len)
    } catch {
      case e if isCatchable(e) => throw new VarkaKernelFailure(e)
    }
    if (sampled) recordAllocationSample(VarkaAllocationSampler.allocatedBytes() - before, len)
    // A non-zero status means the kernel met a value its lowering is not defined over and
    // declined the batch (task 26). The outputs it wrote are not answers; the batch takes the
    // caller's fallback path, which recomputes it row by row. Signalled by a throw because
    // that is the one path every caller of this method already routes to the fallback - the
    // vectors already allocated are released by the task-completion listener like any other.
    if (status != 0 || VarkaColumnarToRowExec.isDeclineKernelForTesting) {
      throw new VarkaBatchDeclined(if (status != 0) status else 1)
    }
  }

  /**
   * Maps a `DateDayVector` or `IntVector` (task 38) to its data and validity segments
   * (zero-copy), mirroring the engine's `VarkaMorsel.extractDate` contract: the validity
   * segment is null for an all-null column, and callers pass a `0L` address in that case
   * because the kernels never dereference it then. Both vector kinds are four bytes wide with
   * the same buffer layout, so the body does not care which one it was handed.
   *
   * The vector must hold exactly the batch's rows, which `isArrowBacked` has already checked -
   * that is what makes the vector's null count the batch's null count, and so what makes the
   * all-null test below sound.
   */
  private def extractMorsel(v: BaseFixedWidthVector, len: Int): Morsel = {
    require(len == v.getValueCount(),
      s"rowCount $len does not match the vector value count ${v.getValueCount()}")
    val data = ofAddress(v.getDataBuffer())
    val nullCount = v.getNullCount()
    val validity = if (nullCount == len) null else ofAddress(v.getValidityBuffer())
    Morsel(data, validity, nullCount)
  }

  private def ofAddress(buf: ArrowBuf): MemorySegment = {
    MemorySegment.ofAddress(buf.memoryAddress()).reinterpret(buf.capacity())
  }

  /**
   * The fused loop serving one task, plus the `run` argument arrays, allocated once here and
   * refilled per batch - nothing is allocated per call. Since task 18 the class comes from
   * [[VarkaShapeCache]] - shared across tasks and released on cache eviction, so its C2 code
   * survives the task boundary - and only the kernel instance and these arrays are the
   * task's own. The cache owns the loader in every configuration: with `maxEntries` = 0 it
   * evicts (and releases) each entry as it is loaded, and this task's strong references
   * carry the class to task end - the pre-task-18 lifecycle through the same path.
   */
  protected class FusedRunner(plan: CompiledVarkaProjection) {
    // The lookup records this execution (operator, stage, the evaluator's leading entries)
    // in the cache's side table, so the shape-named class joins back to the plan nodes that
    // ran it.
    private val lookup = {
      if (VarkaColumnarToRowExec.isFailEmissionForTesting) {
        throw new IllegalStateException("injected Varka emission failure")
      }
      VarkaShapeCache.getOrEmit(shapeKey(plan), executionIdentity())
    }
    private val entry = lookup.entry
    (if (lookup.hit) metrics.cacheHits else metrics.cacheMisses).foreach(_ += 1)

    val sourceFile: String = entry.sourceFile

    val classBytes: Array[Byte] = {
      // dumpClass writes once per shape and directory (an existing file is left alone), and
      // runs on hit and miss alike so a session that configured the dump directory after the
      // shape was cached still gets its file.
      dumpClass(sourceFile, entry.classBytes)
      entry.classBytes
    }

    val kernel: VarkaFusedKernel = entry.newKernel()

    val srcData = new Array[Long](plan.inputOrdinals.size)
    val srcValidity = new Array[Long](plan.inputOrdinals.size)
    val srcNullCount = new Array[Int](plan.inputOrdinals.size)
    val dstData = new Array[Long](plan.outputs.size)
    val dstValidity = new Array[Long](plan.outputs.size)
    val scalarArgs: Array[Int] = plan.literals.toArray
  }
}

/**
 * The kernel half of the Varka projection, for one partition: it turns an input `ColumnarBatch`
 * into a batch of the projection's output, and owns everything that costs a task to set up - the
 * compiled IR, the fused-loop kernel instance, the Arrow allocator and the batches handed out.
 *
 * Since task 10 the compute is one [[VarkaFusedKernel]] emitted by
 * `VarkaLoopEmitter` for the whole projection - every output computed in a single pass with
 * intermediates in vector registers - instead of one dispatcher call per output op. The
 * projection is compiled to IR by [[VarkaExpressionCompiler]], the same call
 * `VarkaColumnarRule` decided eligibility with, so the plan the rule fused is by construction a
 * plan this evaluator serves. Since task 18 the emitted ''class'' is not per-task state: it
 * comes from [[VarkaShapeCache]], the JVM-wide cache keyed on the kernel's structural shape,
 * so tasks (and sessions) computing the same shape share one loaded class and skip its
 * per-task JIT warm-up - the fixed 13-50 ms `PLAN_TASK_14.md` 7.5 diagnosed. Only the kernel
 * ''instance'' and its argument arrays stay per-task.
 *
 * Since task 12 eligibility is partial and the output batch is assembled column by column in
 * projection order: fused entries come from the kernel's freshly allocated Arrow vectors,
 * bare-column entries are '''forwarded''' - the output batch references `input.column(ordinal)`
 * itself, zero copy - and the remaining ('''residual''') entries are evaluated in one per-row
 * pass over the input into writable vectors.
 *
 * '''Ownership.''' The evaluator owns the vectors it allocated - kernel outputs and residual
 * columns - and never the forwarded ones, which belong to whoever owns the input batch. Every
 * release path (the caller's [[release]], and the task-completion listener that drains
 * abandoned batches) closes exactly the owned vectors of a batch and never calls
 * `ColumnarBatch.close()`, which would close every column unconditionally, forwarded ones
 * included. This follows Spark's own two-tier convention (`closeIfFreeable` and its no-op
 * overrides) rather than a wrapper class: the borrowed vector simply stays off the owned list.
 *
 * '''Ordering contract.''' Forwarded vectors make the output batch valid only as long as its
 * input batch: both exec nodes therefore release the output batch '''before''' requesting the
 * next input batch from the child, so a forwarded vector can never outlive its input. The
 * nodes' iterators already obeyed this order for memory reasons; with forwarding it is
 * load-bearing for correctness.
 *
 * '''Telemetry''' (tasks 13 and 16, reconciled with the shared class in task 18). The emitted
 * class is named by its shape (`VarkaFusedProjection_<hash>`, `SourceFile` to match), and its
 * `VarkaDebugInfo` attribute and `LineNumberTable` describe the shape - the vector IR, the
 * line-to-node map - because the bytes are shared and must not replay one query's identity for
 * another. The per-execution identity that used to ride the bytes (operator, stage, this
 * projection's expression list) is recorded in [[VarkaShapeCache]]'s side table on every
 * lookup, keyed by the shape hash, and every fallback this class logs still names both halves
 * ([[kernelIdentity]]: the shape name, the IR, and the operator/stage). The bytes are kept
 * behind [[emittedClassBytes]] so diagnostics read the attributes off exactly what ran, and
 * `spark.sql.codegen.varka.classDumpDirectory` writes them to disk under the `SourceFile`
 * name, so `javap` reaches a generated loop with no debugger attached.
 *
 * One instance per partition, created inside the task: it registers a task-completion listener
 * on first use, and its state must not be shared across partitions (see [[SafeForKWayMerge]]).
 *
 * @param operatorName the exec node this evaluator serves, for the telemetry names above.
 * @param classDumpDirectory where to write each emitted class, or None to write none.
 * @param metrics the exec node's Varka metric set (task 22); every field is optional, and
 *                suites or diagnostics that construct the evaluator directly pass none.
 */
private[sql] class VarkaKernelEvaluator(
    projectList: Seq[NamedExpression],
    childOutput: Seq[Attribute],
    offHeapColumnVectorEnabled: Boolean,
    operatorName: String,
    classDumpDirectory: Option[String] = None,
    metrics: VarkaExecMetrics = VarkaExecMetrics())
    extends VarkaEvaluatorBase(childOutput, operatorName, classDumpDirectory, metrics) {

  // The projection classified entry by entry and its fused sub-projection compiled to vector
  // IR; None when no entry is Varka-eligible (should not happen given [[VarkaColumnarRule]],
  // but be safe).
  private lazy val compiled: Option[PartialVarkaProjection] = {
    val partial = VarkaExpressionCompiler.compilePartial(projectList, childOutput)
    // Task 16: the same per-entry account verbose EXPLAIN prints, once per task at debug level.
    partial.foreach { plan =>
      logDebug(s"Varka $operatorName fusion: " +
        VarkaFusionReport.lines(plan, projectList, childOutput).mkString("; "))
    }
    partial
  }

  override protected def fusedPlan: Option[CompiledVarkaProjection] = compiled.map(_.fused)

  override protected def identityEntries: Iterator[String] = projectList.iterator.map(_.toString)

  // The residual entries and their per-row machinery. All lazy (task 15's discipline): a
  // kernel-only projection has no residual entries, and even a mixed one pays the Janino
  // compile only when the first batch actually reaches [[project]].
  private lazy val residualExprs: Seq[NamedExpression] =
    compiled.toSeq.flatMap(_.specs.zip(projectList).collect {
      case (ResidualOutput, named) => named
    })
  private lazy val residualSchema: StructType =
    DataTypeUtils.fromAttributes(residualExprs.map(_.toAttribute))
  private lazy val residualProjection = UnsafeProjection.create(residualExprs, childOutput)
  private lazy val residualConverter = new RowToColumnConverter(residualSchema)

  /** The classified projection, for the row node's merge-at-row read-back (see 2.3). */
  private[execution] def partialPlan: Option[PartialVarkaProjection] = compiled

  /**
   * Runs the fused kernel over the input batch, evaluates the residual entries per row,
   * forwards the bare-column entries, and returns the assembled output batch, tracked here
   * until the caller [[release]]s it. Callers must have asked [[canRun]] first, and must treat
   * a throw as "this batch could not be served": nothing is left allocated by a failed call.
   */
  def project(input: ColumnarBatch): ColumnarBatch = {
    val partial = compiled.get
    val len = input.numRows()
    // Everything allocated for this batch - kernel outputs, then residual columns - closed on
    // any failure here, and by release()/the listener once the batch is handed out. Forwarded
    // input vectors never join this list: they stay owned by the input batch.
    val owned = mutable.ArrayBuffer.empty[ColumnVector]
    try {
      val fusedColumns = computeFused(input, len, owned)
      val residualColumns = projectResiduals(input, len)
      owned ++= residualColumns
      var residual = 0
      val columns = partial.specs.map {
        case FusedOutput(index) => fusedColumns(index)
        case ForwardedOutput(ordinal) => input.column(ordinal)
        case ResidualOutput =>
          residual += 1
          residualColumns(residual - 1)
      }.toArray
      val batch = new ColumnarBatch(columns)
      batch.setNumRows(len)
      trackOwned(batch, owned.toSeq)
      batch
    } catch {
      case e: Throwable =>
        closeAllQuietly(owned, "a Varka output vector after a failed projection")
        throw e
    }
  }

  /**
   * Runs only the fused kernel and returns a batch of just its columns, tracked like
   * [[project]]'s. This is the row node's entry point (merge-at-row, `PLAN_TASK_12.md` 2.3):
   * it reads fused values from this batch and evaluates residual entries during its own row
   * pass, so materialising them into vectors here would be pure waste. Nothing in it is
   * borrowed - fused columns are always freshly allocated.
   */
  def projectFused(input: ColumnarBatch): ColumnarBatch = {
    val len = input.numRows()
    val owned = mutable.ArrayBuffer.empty[ColumnVector]
    try {
      val fusedColumns = computeFused(input, len, owned)
      val batch = new ColumnarBatch(fusedColumns)
      batch.setNumRows(len)
      trackOwned(batch, owned.toSeq)
      batch
    } catch {
      case e: Throwable =>
        closeAllQuietly(owned, "a Varka output vector after a failed projection")
        throw e
    }
  }

  /**
   * Runs the fused kernel over the input batch into freshly allocated Arrow vectors, appending
   * them to `owned` as they are created (the caller closes `owned` on failure). Returns the
   * fused columns by fused index.
   */
  private def computeFused(
      input: ColumnarBatch,
      len: Int,
      owned: mutable.ArrayBuffer[ColumnVector]): Array[ColumnVector] = {
    val plan = compiled.get.fused
    val runner = fusedRunner.get
    val alloc = taskAllocator()
    fillSources(runner, input, len)
    val fixed = new Array[BaseFixedWidthVector](plan.outputs.size)
    val fusedColumns = new Array[ColumnVector](plan.outputs.size)
    var o = 0
    plan.outputTypes.foreach { dataType =>
      val vector = allocateVector(dataType, o, len, alloc)
      fixed(o) = vector
      fusedColumns(o) = new VarkaOwnedArrowColumnVector(vector)
      owned += fusedColumns(o)
      runner.dstData(o) = vector.getDataBuffer().memoryAddress()
      runner.dstValidity(o) = vector.getValidityBuffer().memoryAddress()
      o += 1
    }
    invokeFused(runner, len)
    fixed.foreach(_.setValueCount(len))
    fusedColumns
  }

  /**
   * Evaluates all residual entries in one per-row pass over the input, into writable vectors
   * sized to the batch. Returns the columns in residual-entry order; empty when the projection
   * has no residual entries.
   */
  private def projectResiduals(input: ColumnarBatch, len: Int): Seq[ColumnVector] = {
    if (residualExprs.isEmpty) {
      Seq.empty
    } else {
      val vectors: Array[WritableColumnVector] = if (offHeapColumnVectorEnabled) {
        OffHeapColumnVector.allocateColumns(len, residualSchema).toArray[WritableColumnVector]
      } else {
        OnHeapColumnVector.allocateColumns(len, residualSchema).toArray[WritableColumnVector]
      }
      try {
        val rows = input.rowIterator()
        while (rows.hasNext) {
          residualConverter.convert(residualProjection(rows.next()), vectors)
        }
      } catch {
        case e: Throwable =>
          closeAllQuietly(vectors, "a Varka residual vector after a failed conversion")
          throw e
      }
      vectors.toSeq
    }
  }

  /**
   * Allocates one destination Arrow vector: a `DateDayVector` for a date output, an `IntVector`
   * for a `datediff` day count. The fused loop writes its validity and data buffers directly
   * (zero-copy); it zeroes every destination validity first, so only the valid rows get set
   * bits, and null lanes of the data buffer are undefined, matching the engine contract.
   */
  private def allocateVector(
      dataType: org.apache.spark.sql.types.DataType,
      ordinal: Int,
      len: Int,
      allocator: BufferAllocator): BaseFixedWidthVector = {
    val vector: ValueVector = dataType match {
      case DateType => new DateDayVector(s"varka$ordinal", allocator)
      case IntegerType => new IntVector(s"varka$ordinal", allocator)
    }
    val fixed = vector.asInstanceOf[BaseFixedWidthVector]
    try {
      fixed.allocateNew(len)
    } catch {
      case e: Throwable =>
        vector.close()
        throw e
    }
    fixed
  }
}

/**
 * An Arrow-backed column vector the Varka evaluator owns (task 21): `closeIfFreeable` is a
 * no-op, per Spark's two-tier close convention, because the vector's lifecycle belongs to the
 * evaluator's release paths - and a consumer that frees the batches it drains (the Arrow
 * cache writer calls `ColumnarBatch.closeIfFreeable()` per batch) must not close what it
 * does not own: it would free the buffers under the evaluator's own later release, the
 * double-close the ownership doc forbids. `WritableColumnVector` makes exactly this override
 * for the same reason; plain `ArrowColumnVector` does not, because a scan's vectors really
 * are freed that way.
 */
private[execution] class VarkaOwnedArrowColumnVector(vector: ValueVector)
    extends ArrowColumnVector(vector) {
  override def closeIfFreeable(): Unit = {}
}

/**
 * One batch's selection (task 21): the bitmap the filter kernel wrote - valid until the
 * evaluator's next [[VarkaFilterEvaluator.filterMask]] call, since the buffer is reused - and
 * the number of selected rows. Read through `VarkaSelectionBitmap`.
 */
private[sql] case class VarkaSelection(mask: MemorySegment, count: Int)

/**
 * The kernel half of the Varka filter, for one partition (task 21): it runs the mask kernel -
 * a fused loop whose single output root is the predicate's condition - over an Arrow-backed
 * batch and hands back the selection bitmap, leaving what to do with it (compact a fresh
 * batch, or skip rows at the row boundary) to the exec node. Shares every task-lifetime
 * mechanism with the projection evaluator through [[VarkaEvaluatorBase]].
 *
 * The condition must be fully fused: [[VarkaColumnarRule]] splits a mixed predicate and keeps
 * the residual conjuncts in a row `FilterExec` above, so a condition with residual conjuncts
 * reaching this evaluator would mean silently dropping them - the compile is therefore
 * accepted only when every conjunct fused, and anything else makes every batch take the
 * caller's row fallback.
 */
private[sql] class VarkaFilterEvaluator(
    condition: Expression,
    childOutput: Seq[Attribute],
    offHeapColumnVectorEnabled: Boolean,
    operatorName: String,
    classDumpDirectory: Option[String] = None,
    metrics: VarkaExecMetrics = VarkaExecMetrics())
    extends VarkaEvaluatorBase(childOutput, operatorName, classDumpDirectory, metrics) {

  private lazy val compiled = {
    val predicate = VarkaExpressionCompiler.compilePredicate(condition, childOutput)
      .filter(_.residualConjuncts.isEmpty)
    // Task 16's account for a filter, once per task at debug level - the projection
    // evaluator's counterpart, which the base-class split had dropped (task-21 review,
    // second pass) although the docs promise it.
    predicate.foreach { _ =>
      logDebug(s"Varka $operatorName fusion: " +
        VarkaFusionReport.predicateLines(condition, childOutput).mkString("; "))
    }
    predicate
  }

  override protected def fusedPlan: Option[CompiledVarkaProjection] = compiled.map(_.fused)

  override protected def identityEntries: Iterator[String] = Iterator(condition.toString)

  // The selection buffer, reused across batches and grown on demand; released by the
  // task-completion listener before the allocator closes. The kernel zeroes the leading
  // (len + 7) / 8 bytes itself (its driver zeroes every dstValidity segment), so a stale
  // tail from a longer earlier batch is never read - the bitmap readers stop at `len` bits.
  private var maskBuf: ArrowBuf = null

  override protected def onTaskCleanup(): Unit = {
    if (maskBuf != null) {
      maskBuf.close()
      maskBuf = null
    }
  }

  private def maskBuffer(len: Int): ArrowBuf = {
    val needed = ((len + 63) / 64) * 8L
    if (maskBuf == null || maskBuf.capacity() < needed) {
      // Allocate before closing, the same order and for the same reason as `grown`. This used
      // to null the field first and then close - safe, because a throwing allocation left no
      // dangling reference to serve a later batch or be double-closed (task-21 review, second
      // pass), but it destroyed a usable buffer on the way out and left the evaluator with
      // none. Acquiring first is strictly better: a transient allocation failure now costs
      // nothing at all, and the two grow helpers in this file no longer answer the same hazard
      // two different ways - which is what let `grown` be written as a close-then-allocate and
      // still claim to follow this one.
      val fresh = taskAllocator().buffer(needed)
      val old = maskBuf
      maskBuf = fresh
      if (old != null) {
        old.close()
      }
    }
    maskBuf
  }

  /**
   * Runs the mask kernel over the input batch and returns its selection. Callers must have
   * asked [[canRun]] first, must treat a throw as "this batch could not be served", and must
   * finish reading the bitmap before the next call - the buffer is task state, not batch
   * state, which is safe under the nodes' one-batch-at-a-time iteration and allocates nothing
   * per batch.
   */
  def filterMask(input: ColumnarBatch): VarkaSelection = {
    val len = input.numRows()
    val runner = fusedRunner.get
    fillSources(runner, input, len)
    val buf = maskBuffer(len)
    // The mask output's data slot is unused by contract (the emitted body never touches it);
    // its validity slot receives the selection bitmap.
    runner.dstData(0) = 0L
    runner.dstValidity(0) = buf.memoryAddress()
    invokeFused(runner, len)
    val mask = MemorySegment.ofAddress(buf.memoryAddress()).reinterpret((len + 7) / 8)
    VarkaSelection(mask, VarkaSelectionBitmap.countSet(mask, len))
  }

  // The generic-column compaction machinery, rebuilt only when the set of generic positions
  // changes (in practice once per partition: which columns take the fixed-width Arrow copy is
  // a property of the child's batch layout, not of the row values). Building an
  // UnsafeProjection per batch would put a Janino compile on the per-batch path.
  private var genericPositions: Seq[Int] = null
  private var genericSchema: StructType = null
  private var genericProjection: UnsafeProjection = null
  private var genericConverter: RowToColumnConverter = null

  private def genericMachinery(positions: Seq[Int]): Unit = {
    if (positions != genericPositions) {
      genericPositions = positions
      val attrs = positions.map(childOutput)
      genericSchema = DataTypeUtils.fromAttributes(attrs)
      genericProjection = UnsafeProjection.create(attrs, childOutput)
      genericConverter = new RowToColumnConverter(genericSchema)
    }
  }

  /**
   * Runs the mask kernel and compacts the selected rows into a fresh output batch - the
   * columnar filter's whole batch path, and the v1 selected-batch contract (milestone open
   * question 2): the batch that leaves the *compacting* path is an ordinary dense batch, so
   * every consumer's invariants hold unchanged - `canRun`'s valueCount-equals-numRows check
   * included, which is what lets a Varka projection stack right on top. The `count == len`
   * forwarding path below makes a narrower promise: the child's columns pass through exactly
   * as they arrived, normalized only as much as the child normalized them - `canRun` vets the
   * plan-referenced columns per batch, so an unreferenced column could in principle arrive
   * non-Arrow or short and leave the same way. Sound today because a stacked Varka node runs
   * its own per-batch `canRun` and falls back on what it cannot serve; it just means the
   * forwarding path relies on the downstream gate rather than on this method's output shape.
   *
   * Columns whose input is an Arrow `DateDayVector` or `IntVector` compact by a typed scalar
   * copy into a fresh Arrow vector of the same type (keeping them kernel-servable upstream of
   * the next Varka node); every other column goes through one per-row pass with the standard
   * row-to-column converter, the same machinery as the projection residuals.
   *
   * Two selectivity extremes skip that work entirely (task 24). At `count == len` nothing has
   * to be shortened, and a vector that does not have to be shortened does not have to be
   * copied, so the child's columns are forwarded: the earlier rule that a compacting filter
   * owns every output column holds only where the compaction is real. At `count == 0` the
   * per-row scans are skipped - they are O(len) either way today, which is why the 0%-selected
   * rung is the weakest of the committed ladder.
   */
  def filterCompact(input: ColumnarBatch): ColumnarBatch = {
    val selection = filterMask(input)
    val len = input.numRows()
    val count = selection.count
    if (count == len) {
      // Every row survives: forward the child's columns rather than copy them. The ownership
      // contract carries this by itself - trackOwned is told the batch owns nothing, so
      // release() leaves each vector to the input batch it came from, exactly as the
      // projection node's forwarding does. Sound under the one-batch-at-a-time discipline both
      // filter nodes run: the output batch is released before the next input is requested.
      val batch = new ColumnarBatch(Array.tabulate(childOutput.length)(input.column))
      batch.setNumRows(count)
      trackOwned(batch, Seq.empty)
      return batch
    }
    val owned = mutable.ArrayBuffer.empty[ColumnVector]
    try {
      val columns = new Array[ColumnVector](childOutput.length)
      val generic = mutable.ArrayBuffer.empty[Int]
      var j = 0
      while (j < childOutput.length) {
        input.column(j) match {
          case acv: ArrowColumnVector =>
            acv.getValueVector() match {
              // One arm serves every fixed-width Arrow type (task-21 review, second pass:
              // a DateDay/Int-only switch would silently route a future lane type through
              // the generic pass and hand a stacked Varka node non-Arrow columns):
              // getTransferPair conjures an empty vector of the source's own type, and
              // copyFromSafe copies value and validity together.
              case src: BaseFixedWidthVector if src.getValueCount() == len =>
                val dst = src.getTransferPair(s"varka$j", taskAllocator()).getTo
                  .asInstanceOf[BaseFixedWidthVector]
                columns(j) = if (src.getTypeWidth() == 4) {
                  compactInt32(dst, src, selection, len, count, owned)
                } else {
                  compactFixed(dst, selection, len, count, owned) { (pos, i) =>
                    dst.copyFromSafe(i, pos, src)
                  }
                }
              case _ => generic += j
            }
          case _ => generic += j
        }
        j += 1
      }
      if (generic.nonEmpty) {
        genericMachinery(generic.toSeq)
        val vectors: Array[WritableColumnVector] = if (offHeapColumnVectorEnabled) {
          OffHeapColumnVector.allocateColumns(math.max(count, 1), genericSchema)
            .toArray[WritableColumnVector]
        } else {
          OnHeapColumnVector.allocateColumns(math.max(count, 1), genericSchema)
            .toArray[WritableColumnVector]
        }
        owned ++= vectors
        if (count > 0) {
          val rows = input.rowIterator()
          var i = 0
          while (rows.hasNext) {
            val row = rows.next()
            if (VarkaSelectionBitmap.isSet(selection.mask, i)) {
              genericConverter.convert(genericProjection(row), vectors)
            }
            i += 1
          }
        }
        generic.zipWithIndex.foreach { case (position, k) => columns(position) = vectors(k) }
      }
      val batch = new ColumnarBatch(columns)
      batch.setNumRows(count)
      trackOwned(batch, owned.toSeq)
      batch
    } catch {
      case e: Throwable =>
        closeAllQuietly(owned, "a Varka output vector after a failed projection")
        throw e
    }
  }

  /**
   * The `compress(mask)` compaction (task 24, milestone 4 item 11) for 4-byte fixed-width
   * Arrow vectors - date32 and int32, every width Varka produces today. Width 8 arrives with
   * task 29's lane type and everything else keeps the per-row typed copy below. It is a width
   * check rather than a type check on purpose: a future Arrow type of the right width is
   * served correctly by a bit-for-bit lane move.
   *
   * The destination is allocated with one whole lane group of slack past `count` so that
   * [[SelectionVectorOps.compactInts]] can store unmasked - a masked store costs 2.3x-2.9x and
   * this would otherwise pay one per lane group - and `setValueCount(count)` afterwards is
   * what makes the slack invisible to every consumer.
   */
  private def compactInt32(dst: BaseFixedWidthVector, src: BaseFixedWidthVector,
      selection: VarkaSelection, len: Int, count: Int,
      owned: mutable.ArrayBuffer[ColumnVector]): ColumnVector = {
    try {
      dst.allocateNew(count + SelectionVectorOps.intLanes())
    } catch {
      case e: Throwable =>
        closeQuietly(dst, "a Varka compaction vector after a failed allocation")
        throw e
    }
    val wrapped = new VarkaOwnedArrowColumnVector(dst)
    owned += wrapped
    if (count > 0) {
      val hasNulls = src.getNullCount() > 0
      SelectionVectorOps.compactInts(
        src.getDataBuffer().memoryAddress(),
        if (hasNulls) src.getValidityBuffer().memoryAddress() else 0L,
        hasNulls,
        selection.mask,
        len,
        count,
        dst.getDataBuffer().memoryAddress(),
        dst.getDataBuffer().capacity(),
        dst.getValidityBuffer().memoryAddress(),
        dst.getValidityBuffer().capacity())
    }
    dst.setValueCount(count)
    wrapped
  }

  /** Allocates `dst` for `count` rows, copies the selected rows via `copyRow(pos, i)`, and
   * wraps it; the vector joins `owned` as soon as it can leak. */
  private def compactFixed(dst: BaseFixedWidthVector, selection: VarkaSelection, len: Int,
      count: Int, owned: mutable.ArrayBuffer[ColumnVector])(
      copyRow: (Int, Int) => Unit): ColumnVector = {
    try {
      dst.allocateNew(math.max(count, 1))
    } catch {
      case e: Throwable =>
        closeQuietly(dst, "a Varka compaction vector after a failed allocation")
        throw e
    }
    val wrapped = new VarkaOwnedArrowColumnVector(dst)
    owned += wrapped
    // Nothing selected means nothing to scan for: the bitmap walk below is O(len) whatever it
    // finds, and at 0% selectivity that walk was the whole cost of the column (task 24).
    if (count > 0) {
      var i = 0
      var pos = 0
      while (i < len) {
        if (VarkaSelectionBitmap.isSet(selection.mask, i)) {
          copyRow(pos, i)
          pos += 1
        }
        i += 1
      }
    }
    dst.setValueCount(count)
    wrapped
  }
}

private[execution] object VarkaKernelEvaluator {

  /**
   * Emits the task-22 fallback JFR event; shared by the evaluator's emission-failure path and
   * the per-batch fallback accounting. Populates only while a recording has the event
   * enabled - the identity is by-name because rendering it computes the shape hash, which the
   * metered-but-uneventful path must not pay (task-21 review); `exceptionClass` is empty for
   * the non-Arrow cause, a data property rather than an error.
   */
  private[execution] def emitFallbackEvent(
      cause: String,
      kernelIdentity: => String,
      exceptionClass: String): Unit = {
    val event = new VarkaFallbackEvent
    if (event.isEnabled()) {
      event.cause = cause
      event.kernelIdentity = kernelIdentity
      event.exceptionClass = exceptionClass
      event.commit()
    }
  }

  /** The allocation-sample event; populated only while a recording has it enabled. */
  private[execution] def emitAllocationEvent(
      kernelIdentity: => String,
      batchIndex: Long,
      rows: Int,
      allocatedBytes: Long,
      suspect: Boolean): Unit = {
    val event = new VarkaKernelAllocationEvent
    if (event.isEnabled()) {
      event.kernelIdentity = kernelIdentity
      event.batchIndex = batchIndex
      event.rows = rows
      event.allocatedBytes = allocatedBytes
      event.suspect = suspect
      event.commit()
    }
  }

  // Which kernel batches the allocation sampler measures. The production schedule skips the
  // JIT warm-up (see VarkaAllocationSampler); suites set a dense one so a short query samples,
  // and restore the default in a finally.
  /**
   * The decline status the evaluator itself reports when an input lane lies outside a bound
   * the compiler recorded (task 56) - bit 1, beside the kernels' `STATUS_CHRONO_RANGE` (bit 0),
   * so a log line tells the two apart. Never returned by an emitted kernel.
   */
  private[execution] val STATUS_INPUT_BOUND: Int = 2

  /**
   * The decline status the evaluator reports when a derived input (task 59) met a value its
   * row-engine definition raises on under ANSI - an unrecognised weekday name - bit 2. The
   * row engine recomputes the batch and raises where a non-null date sits beside the name.
   */
  private[execution] val STATUS_DERIVED_INPUT: Int = 4

  @volatile private[execution] var allocationSchedule: VarkaAllocationSampler.Schedule =
    VarkaAllocationSampler.Schedule.DEFAULT

  // The (directory, SourceFile) pairs this JVM has dumped, so a shape's class file is
  // written once per process rather than once per task - and exactly once per process,
  // because a file left by an older emitter under the same shape name must be refreshed.
  private[execution] val dumpedClassFiles =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
}

private case class Morsel(data: MemorySegment, validity: MemorySegment, nullCount: Long) {
  def validityAddress: Long = if (validity == null) 0L else validity.address()
}
