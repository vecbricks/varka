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

import scala.collection.mutable

import org.apache.arrow.memory.{BufferAllocator}
import org.apache.arrow.vector.{BaseFixedWidthVector, DateDayVector, IntervalYearVector, IntVector,
  ValueVector}

import org.apache.spark.sql.catalyst.expressions.{Attribute, NamedExpression, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection, ForwardedOutput, FusedOutput, KernelOutput, PartialVarkaProjection, ResidualOutput, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaAllocationSampler,
  VarkaEmitOptions, VarkaFallbackEvent, VarkaKernelAllocationEvent}
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.vectorized.{OffHeapColumnVector, OnHeapColumnVector, WritableColumnVector}
import org.apache.spark.sql.types.{DataType, DateType, DayTimeIntervalType, IntegerType, LongType, StructType, TimeType, YearMonthIntervalType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}

/**
 * The kernel half of the Varka projection, for one partition: it turns an input `ColumnarBatch`
 * into a batch of the projection's output, and owns everything that costs a task to set up - the
 * compiled IR, the fused-loop kernel instance, the Arrow allocator and the batches handed out.
 *
 * the compute is one [[VarkaFusedKernel]] emitted by
 * `VarkaLoopEmitter` for the whole projection - every output computed in a single pass with
 * intermediates in vector registers - instead of one dispatcher call per output op. The
 * projection is compiled to IR by [[VarkaExpressionCompiler]], the same call
 * `VarkaColumnarRule` decided eligibility with, so the plan the rule fused is by construction a
 * plan this evaluator serves. the emitted ''class'' is not per-task state: it
 * comes from [[VarkaShapeCache]], the JVM-wide cache keyed on the kernel's structural shape,
 * so tasks (and sessions) computing the same shape share one loaded class and skip its
 * per-task JIT warm-up - the fixed 13-50 ms `PLAN_TASK_14.md` 7.5 diagnosed. Only the kernel
 * ''instance'' and its argument arrays stay per-task.
 *
 * eligibility is partial and the output batch is assembled column by column in
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
 * '''Telemetry''' (reconciled with the shared class). The emitted
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
 * @param metrics the exec node's Varka metric set; every field is optional, and
 *                suites or diagnostics that construct the evaluator directly pass none.
 */
private[sql] class VarkaKernelEvaluator(
    projectList: Seq[NamedExpression],
    childOutput: Seq[Attribute],
    offHeapColumnVectorEnabled: Boolean,
    operatorName: String,
    classDumpDirectory: Option[String] = None,
    metrics: VarkaExecMetrics = VarkaExecMetrics(),
    emitUseAVX: Int = VarkaEmitOptions.USE_AVX_UNKNOWN,
    warmupEnabled: Boolean = false)
    extends VarkaEvaluatorBase(childOutput, operatorName, classDumpDirectory, metrics,
      emitUseAVX, warmupEnabled) {

  // The projection classified entry by entry and its fused sub-projection compiled to vector
  // IR; None when no entry is Varka-eligible (should not happen given [[VarkaColumnarRule]],
  // but be safe).
  private lazy val compiled: Option[PartialVarkaProjection] = {
    val partial = VarkaExpressionCompiler.compilePartial(projectList, childOutput, emitOptions)
    // the same per-entry account verbose EXPLAIN prints, once per task at debug level.
    partial.foreach { plan =>
      logDebug(s"Varka $operatorName fusion: " +
        VarkaFusionReport.lines(plan, projectList, childOutput).mkString("; "))
    }
    partial
  }

  override protected def fusedPlan: Option[CompiledVarkaProjection] = compiled.map(_.fused)

  // The further kernels of a projection several kernels serve (`VarkaEmitOptions.severalKernels`,
  // `PLAN_TASK_190.md` 11), each an evaluator of its own for its runner, warm-up and scratch; this
  // evaluator runs the first kernel and asks every one of them before a batch takes the kernels.
  // Empty for a projection one kernel serves, which then runs exactly as before.
  private lazy val parts: Seq[VarkaKernelPart] = {
    partsBuilt = true
    compiled.toSeq.flatMap { partial =>
      partial.more.zipWithIndex.map { case (plan, k) =>
        // Its identity renders the entries it computes, not the projection's first ones.
        val entries = partial.specs.zip(projectList).collect {
          case (KernelOutput(kernel, _), named) if kernel == k + 1 => named
        }
        new VarkaKernelPart(plan, entries, childOutput, operatorName, classDumpDirectory,
          metrics, emitUseAVX, warmupEnabled, () => taskAllocator())
      }
    }
  }
  private var partsBuilt = false

  // A further kernel allocates from this evaluator's allocator and has no listener of its own,
  // so its scratch is released here, before the base's cleanup closes that allocator.
  override protected def onTaskCleanup(): Unit = {
    if (partsBuilt) {
      parts.foreach(_.releaseTaskScratch())
    }
  }

  /** Every kernel can serve the batch: the first one, as ever, and each further one. */
  override def canRun(input: ColumnarBatch): Boolean =
    super.canRun(input) && parts.forall(_.canRun(input))

  /**
   * Every kernel is ready. Each one is asked, so each claims its own warm-up on the batch that
   * finds it cold, and the batch takes the kernels only once all of them are compiled.
   */
  override private[execution] def kernelReady(input: ColumnarBatch): Boolean = {
    val first = super.kernelReady(input)
    parts.map(part => blamed(part)(part.kernelReady(input))).forall(identity) && first
  }

  /**
   * Runs `body` for a further kernel, naming that kernel in a decline or failure it raises, so the
   * fallback's log line and event name the kernel that declined rather than the first one.
   */
  private def blamed[T](part: VarkaKernelPart)(body: => T): T = {
    try {
      body
    } catch {
      case d: VarkaBatchDeclined if d.kernel == null =>
        throw new VarkaBatchDeclined(d.status, part.kernelIdentity)
      case f: VarkaKernelFailure if f.kernel == null =>
        throw new VarkaKernelFailure(f.getCause, part.kernelIdentity)
    }
  }

  override private[execution] def emissionFailed: Boolean =
    super.emissionFailed || parts.exists(_.emissionFailed)

  override protected def identityEntries: Iterator[String] = projectList.iterator.map(_.toString)

  // The residual entries and their per-row machinery. All lazy: a // kernel-only projection has no
  // residual entries, and even a mixed one pays the Janino
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
        case FusedOutput(index) => fusedColumns(0)(index)
        case KernelOutput(kernel, index) => fusedColumns(kernel)(index)
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
      val fusedColumns = computeFused(input, len, owned).flatten
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
   * Runs every kernel over the input batch, in turn, into freshly allocated Arrow vectors from
   * this task's one allocator, appending them to `owned` as they are created (the caller closes
   * `owned` on failure). Returns each kernel's columns by its fused index, the first kernel's
   * first. A kernel that declines the batch throws, and the whole batch falls back, as it does
   * with one kernel: the kernels are one projection, answered whole or not at all.
   */
  private def computeFused(
      input: ColumnarBatch,
      len: Int,
      owned: mutable.ArrayBuffer[ColumnVector]): Array[Array[ColumnVector]] = {
    val alloc = taskAllocator()
    (runKernel(input, len, owned, alloc, allocateVector) +:
      parts.map(part => blamed(part)(part.runKernel(input, len, owned, alloc, allocateVector))))
      .toArray
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
   * (zero-copy), and every valid row's bit is set exactly once per batch however the kernel
   * gets there: an output whose validity is a pure AND/OR of the input bitmaps
   * has its whole bitmap written by the driver's bitmap pass, and the outputs that keep the
   * per-group write have their validity zeroed by the driver first. Null lanes of the data
   * buffer are undefined either way, matching the engine contract.
   */
  private def allocateVector(
      dataType: DataType,
      ordinal: Int,
      len: Int,
      allocator: BufferAllocator): BaseFixedWidthVector = {
    val vector: ValueVector = dataType match {
      case DateType => new DateDayVector(s"varka$ordinal", allocator)
      case IntegerType => new IntVector(s"varka$ordinal", allocator)
      // the output side of the same admission. The unit rides on the Spark type and
      // never on the buffer, so every year-month unit writes one vector class; the row path
      // reads it back through the accessor `ArrowColumnVector` already has.
      case _: YearMonthIntervalType => new IntervalYearVector(s"varka$ordinal", allocator)
      // The long lane's destinations (task 29), built from the same Arrow field Spark's own
      // writer would build for the type - `BigIntVector`, `TimeNanoVector` with the precision in
      // its field metadata, `DurationVector` in microseconds - so the row path reads them back
      // through the accessors `ArrowColumnVector` already has, and the cache serializer sees
      // the field it expects. A destination is needed even though this task adds no long
      // arithmetic: `greatest`, `least` and `CASE WHEN` produce a long column.
      case LongType | _: TimeType | _: DayTimeIntervalType =>
        ArrowUtils.toArrowField(s"varka$ordinal", dataType, nullable = true, timeZoneId = null)
          .createVector(allocator)
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
 * An Arrow-backed column vector the Varka evaluator owns: `closeIfFreeable` is a
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

private[execution] object VarkaKernelEvaluator {

  // The batches Varka nodes sent down their row path while a kernel warmed, held weakly by
  // identity; see markWarmupBatch.
  private val warmupBatches = java.util.Collections.synchronizedMap(
    new java.util.WeakHashMap[ColumnarBatch, java.lang.Boolean]())

  /**
   * Remembers that `result` - a Varka node's row-path output - is on the row path because a
   * kernel is warming, and returns it. A Varka node that consumes such a batch cannot run its
   * kernel on it, which is not Arrow, and counts it as a warm-up batch rather than a non-Arrow
   * fallback ([[VarkaEvaluatorBase.serveBatch]]): the format follows from the warm-up above it,
   * not from the data. Only columnar results are remembered; a row iterator has no consumer that
   * asks.
   */
  private[execution] def markWarmupBatch[T](result: T): T = {
    result match {
      case batch: ColumnarBatch => warmupBatches.put(batch, java.lang.Boolean.TRUE)
      case _ =>
    }
    result
  }

  /** Whether `batch` came down a Varka node's row path while a kernel warmed. */
  private[execution] def isWarmupBatch(batch: ColumnarBatch): Boolean =
    warmupBatches.containsKey(batch)

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
   * the compiler recorded - bit 1, beside the kernels' `STATUS_CHRONO_RANGE` (bit 0),
   * so a log line tells the two apart. Never returned by an emitted kernel.
   */
  private[execution] val STATUS_INPUT_BOUND: Int = 2

  /**
   * The decline status the evaluator reports when a derived input met a value its
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

  // The emitter's size declines this JVM has logged, by reason, so a shape declined on every
  // task logs one warning rather than one per task. A reason names the method and its bytes,
  // so it is per shape; the set grows with the declined shapes a JVM sees, which are few.
  private[execution] val loggedDeclines =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
}
