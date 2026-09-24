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
import org.apache.arrow.vector.{BaseFixedWidthVector, BigIntVector, DateDayVector, DurationVector,
  IntervalYearVector, IntVector, TimeNanoVector, VarCharVector}

import org.apache.spark.{TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{Attribute}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{IntRangeOps, TruncLevelLeaf,
  VarkaAllocationSampler, VarkaDerivedKind, VarkaEmitDeclined, VarkaEmitOptions, VarkaFallbackEvent,
  VarkaFusedKernel, VarkaShapeCache, VarkaShapeKey, VarkaVectorIR, WeekdayLeaf}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}

/**
 * Marks a throwable as coming from the emitted kernel invocation itself (task-21 review): the
 * exec nodes label their per-batch catch by it, so a catchable failure in the per-row
 * machinery running beside the kernel - a residual or merge projection's compile or
 * evaluation - is not metered as a kernel failure.
 */
private[execution] class VarkaKernelFailure(cause: Throwable) extends Exception(cause)

/**
 * The batch was declined to the row engine: the kernel ran and returned a non-zero status,
 * meaning some lane lay outside the range a partial lowering is defined over, or
 * the evaluator's own pre-check found an input lane outside a bound the compiler recorded
 * (see [[VarkaKernelEvaluator.STATUS_INPUT_BOUND]]). Not an error - it carries no cause
 * and no stack trace, because it is control flow on a designed path, and [[serveBatch]] turns
 * it into the row-engine fallback.
 */
private[execution] class VarkaBatchDeclined(val status: Int)
  extends Exception(null, null, false, false)

/**
 * The task-lifetime machinery shared by every Varka evaluator (split out of
 * [[VarkaKernelEvaluator]] when the filter evaluator became its second user): the
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
    metrics: VarkaExecMetrics,
    emitUseAVX: Int = VarkaEmitOptions.USE_AVX_UNKNOWN)
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
        case d: VarkaEmitDeclined =>
          // A shape over the emitter's method budget: the same reason on every task, so it is
          // logged once per JVM, and as a reason rather than a stack trace. The compiler asks the
          // emitter at plan time and demotes what it declines, so this is the last resort: a
          // shape the planning JVM's vector width admitted within the byte or so another width
          // adds (PLAN_TASK_169.md 2.2).
          if (VarkaKernelEvaluator.loggedDeclines.add(d.getMessage)) {
            logWarning(s"The Varka emitter declined $kernelIdentity: ${d.getMessage}; " +
              "falling back to the per-row path.")
          }
          metrics.emissionFailures.foreach(_ += 1)
          VarkaKernelEvaluator.emitFallbackEvent(VarkaFallbackEvent.EMISSION_FAILURE,
            kernelIdentity, d.getClass.getName)
          None
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
   * Whether this task tried and failed to obtain its kernel class: the plan
   * compiled but the runner could not be built. The exec nodes use it to keep the per-batch
   * fallback cause honest - after an emission failure every batch fails `canRun`, which
   * without this test would count as "input not Arrow-backed".
   */
  private[execution] def emissionFailed: Boolean = fusedPlan.nonEmpty && fusedRunner.isEmpty

  /**
   * This execution's identity - the operator and this task's stage - which goes
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

  /**
   * The cache key of the fused sub-plan: exactly the emitter inputs the bytes follow. The
   * session's `spark.sql.codegen.varka.emit.useAVX`, read on the driver and carried here,
   * is the one production knob on the options; it is applied over the test hook's options
   * rather than instead of them, so a suite that drives a variant and sets the level gets
   * both, and it is left alone at the default so that the hook's own level survives.
   */
  protected def shapeKey(plan: CompiledVarkaProjection): VarkaShapeKey =
    new VarkaShapeKey(plan.outputs.asJava, plan.inputOrdinals.size, plan.numLiterals, emitOptions)

  /** The options this evaluator emits with, which its compiler call must use too. */
  protected def emitOptions: VarkaEmitOptions = VarkaColumnarToRowExec.emitOptions(emitUseAVX)

  /**
   * The kernel named the way its telemetry names it: the
   * `SourceFile` of the shared class, the IR it computes, and this execution's operator and
   * stage. Every fallback warning - here and in the exec nodes - says which kernel it gave up
   * on, so a log line identifies both the class and the plan node without correlation.
   * Reading it forces no emission: the shape hash is computed from the IR, not the bytes.
   * A lazy val (task-21 review): the rendering hashes the canonical IR, and it is constant
   * per evaluator, so per-batch fallback paths must not recompute it.
   *
   * The IR renders through `VarkaVectorIR.canonical` rather than `Record.toString` (with the line
   * map): the same rendering the class's own `VarkaDebugInfo` carries, so a log
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
   * A batch the kernel itself declined: a lowering that is correct only over part of
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

  /**
   * The output batch for a projection that only forwards columns of its input: the input's own
   * vectors, selected and reordered by `ordinals`, with nothing copied and no kernel run.
   *
   * It is tracked owning nothing, so [[release]] unregisters it and closes none of its columns -
   * they belong to the input batch, exactly as a forwarded entry's column does on the kernel
   * path. A batch built with `new ColumnarBatch(...)` and not tracked would instead reach
   * `release`'s "not one of ours" arm and be closed whole, taking the input's vectors with it.
   */
  def forwardColumns(input: ColumnarBatch, ordinals: Array[Int]): ColumnarBatch = {
    val batch = new ColumnarBatch(ordinals.map(input.column), input.numRows())
    trackOwned(batch, Seq.empty)
    batch
  }

  protected def trackOwned(batch: ColumnarBatch, owned: Seq[ColumnVector]): Unit = {
    ensureCleanup()
    openBatches(batch) = owned
  }

  /**
   * Releases a batch obtained from this evaluator or handed to [[track]]: closes exactly the
   * vectors this evaluator owns in it, so a forwarded input vector is left to its input batch.
   *
   * Each close is guarded, even though this is the ordinary path with a caller above it that
   * could handle a throw. The registry entry is removed first, so by the time anything closes,
   * this call is the only route to those vectors: a throw part-way would strand the rest where
   * nothing - not a later `release`, not the task-completion listener - can reach them. The
   * task's allocator close then finds outstanding bytes and raises "Memory was leaked by
   * query" *instead of* completing, so the child allocator's accounting stays charged against
   * the shared root for the JVM's lifetime, which is the exact failure the listener's own guard
   * exists to prevent. Closing everything and logging what failed is strictly better here than
   * handing the caller an exception it can do nothing useful with.
   */
  def release(batch: ColumnarBatch): Unit = {
    openBatches.remove(batch) match {
      case Some(owned) => closeAllQuietly(owned, "a Varka output vector on release")
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
   * vector of a class the kernels read - four bytes wide (`DateDayVector`, `IntVector`,
   * `IntervalYearVector`) or eight (`BigIntVector`, `TimeNanoVector`, `DurationVector`) -
   * holding exactly the batch's
   * rows, no more - or, for an input the evaluator derives, an Arrow `VarCharVector`
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
            // a year-month interval is a count of months in an int32 buffer whatever
            // its unit, and `IntervalYearVector` is a BaseFixedWidthVector of width four - the
            // same buffer layout the kernels already read. The list is by vector class rather
            // than by Spark type, so admitting the type is exactly this line: the serializer
            // already stores such a column and `ArrowColumnVector` already reads it back.
            case (v: IntervalYearVector, None) => v.getValueCount() == rows
            // The long lane's three (task 29): a `bigint`, a `TIME(p)` - nanoseconds of day at
            // every precision - and a day-time interval in microseconds. All three are
            // `BaseFixedWidthVector`s of width eight, and task 116 proved the two datetime ones
            // map through `extractMorsel` exactly as the int vectors do. The timestamp vectors
            // are deliberately not here: the compiler never builds a leaf for them, so admitting
            // them would only decline the batch one layer later.
            case (v: BigIntVector, None) => v.getValueCount() == rows
            case (v: TimeNanoVector, None) => v.getValueCount() == rows
            case (v: DurationVector, None) => v.getValueCount() == rows
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

  // The derived inputs' scratch buffers, one data and one validity buffer per kernel
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
      growSlot(derivedData, i, dataNeeded)
    }
    if (derivedValidity(i) == null || derivedValidity(i).capacity() < validityNeeded) {
      growSlot(derivedValidity, i, validityNeeded)
    }
  }

  /**
   * Replaces `slots(i)` with a fresh buffer of `needed` bytes: allocate, store, and only then
   * release what was there, so that no step can leave a released buffer referenced.
   *
   * All three parts of that order matter, and each was got wrong in turn. Closing before
   * allocating was a use-after-free: `buffer` throws `OutOfMemoryException` when the allocator
   * cannot satisfy the request, a plain `RuntimeException`, so `serveBatch` catches it as a
   * per-batch failure and *the task keeps running* - with the slot holding a buffer that had
   * already been released, because the assignment that would have replaced it never ran.
   *
   * Storing through the caller (`slots(i) = grown(...)`) narrowed that window without closing
   * it: `close()` can throw too - `BufferLedger.release` raises on reference-count underflow,
   * and with assertions on it checks the allocator is open - and a throw there again unwinds
   * before the caller's store, stranding the released buffer in the slot and leaking the fresh
   * one. Doing the store inside the helper is what makes the release the last thing that can
   * fail, and makes this identical to `maskBuffer` rather than merely similar to it.
   *
   * Why a stranded slot is worse than it sounds: Arrow's `close()` only releases the reference,
   * while `capacity()` and `memoryAddress()` stay plain field reads it does not touch. So the
   * next, smaller batch finds the stale capacity still large enough, skips the regrow, and has
   * the leaf write through an address the allocator has already freed; the task's cleanup then
   * closes the same buffer a second time and the reference count goes negative.
   */
  private def growSlot(slots: Array[ArrowBuf], i: Int, needed: Long): Unit = {
    val fresh = taskAllocator().buffer(needed)
    val old = slots(i)
    slots(i) = fresh
    if (old != null) {
      old.close()
    }
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
      // The flag records a registration that happened, so it is set after the call and not
      // before it. Setting it first meant a throw from `addTaskCompletionListener` - outside a
      // task `TaskContext.get()` is null - left the evaluator believing it had a listener, and
      // the next `taskAllocator()` would then create a child allocator that nothing ever
      // closes. Not reachable from a query, where every evaluator is built inside a
      // `PartitionEvaluator`, but reachable from a harness that drives one directly.
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
      cleanupRegistered = true
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
   * Writes the emitted class to the configured dump directory under its `SourceFile` name,
   * so `javap -c -p` reaches a generated loop with no debugger. Diagnostics only:
   * every failure is logged and swallowed, because a query must not fail over a debug write.
   * Every task of a shape holds identical bytes, so a per-JVM memo makes the
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
   * column this reads. A derived input is computed here, before the kernel runs,
   * into the task's scratch buffers: the string column goes through the row engine's own
   * parser and the kernel reads the int32 result like any other input. The leaves never throw;
   * under ANSI an unrecognised weekday name declines the batch, and the row engine - which
   * parses a name only beside a non-null date - raises its own error where one is due. The
   * trunc level has no such route: an unrecognised format is a null lane in every
   * mode, as it is a NULL result on the row engine.
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
          val nulls = derived.kind match {
            case VarkaDerivedKind.TRUNC_LEVEL =>
              TruncLevelLeaf.fill(acv, len, data.memoryAddress(), validity.memoryAddress())
            case _ =>
              WeekdayLeaf.fill(acv, len, derived.kind.failOnError, WeekdayLeaf.DEFAULT_PARSER,
                data.memoryAddress(), validity.memoryAddress())
          }
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
    // an input the compiler bounded - today a day offset that came from
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
      // One emitted class is one lane, and each lane has its own `run`: the seven-argument
      // form reads the int literal table, the eight-argument one adds the long table. The
      // plan's lane is fixed at compile time, so this is a branch on a final field, not a
      // per-batch discovery. The wrong overload would not run a wrong kernel - each default
      // throws naming the lane - but that throw would be a fallback with a misleading cause.
      if (runner.lane == LaneType.LONG) {
        runner.kernel.run(runner.srcData, runner.srcValidity, runner.srcNullCount,
          runner.dstData, runner.dstValidity, runner.scalarArgs, runner.longArgs, len)
      } else {
        runner.kernel.run(runner.srcData, runner.srcValidity, runner.srcNullCount,
          runner.dstData, runner.dstValidity, runner.scalarArgs, len)
      }
    } catch {
      case e if isCatchable(e) => throw new VarkaKernelFailure(e)
    }
    if (sampled) recordAllocationSample(VarkaAllocationSampler.allocatedBytes() - before, len)
    // A non-zero status means the kernel met a value its lowering is not defined over and
    // declined the batch. The outputs it wrote are not answers; the batch takes the
    // caller's fallback path, which recomputes it row by row. Signalled by a throw because
    // that is the one path every caller of this method already routes to the fallback - the
    // vectors already allocated are released by the task-completion listener like any other.
    if (status != 0 || VarkaColumnarToRowExec.isDeclineKernelForTesting) {
      throw new VarkaBatchDeclined(if (status != 0) status else 1)
    }
  }

  /**
   * Maps a `DateDayVector` or `IntVector` to its data and validity segments
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
   * refilled per batch - nothing is allocated per call. the class comes from
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
    val longArgs: Array[Long] = plan.longLiterals.toArray
    val lane: LaneType = plan.lane
  }
}

private case class Morsel(data: MemorySegment, validity: MemorySegment, nullCount: Long) {
  def validityAddress: Long = if (validity == null) 0L else validity.address()
}
