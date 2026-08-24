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

import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.arrow.memory.{ArrowBuf, BufferAllocator}
import org.apache.arrow.vector.{BaseFixedWidthVector, DateDayVector, IntVector, ValueVector}

import org.apache.spark.TaskContext
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, BindReferences, BoundReference, DateAdd, DateDiff, DateSub, DateVarkaSupport, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{ClassFileCodegenSupport, ClassFileGenOp, VarkaBinaryKernel, VarkaClassFileGen, VarkaGeneratedClassLoader, VarkaUnaryKernel}
import org.apache.spark.sql.types.{DataType, DateType, IntegerType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}
import org.apache.spark.util.Utils

/**
 * The kernel half of the Varka projection, for one partition: it turns an input `ColumnarBatch`
 * into a batch of kernel output, and owns everything that costs a task to set up - the dispatch
 * plan, the assembled runner classes, the Arrow allocator and the batches handed out.
 *
 * Two nodes share it, and they share only this. [[VarkaColumnarToRowExec]] converts the result
 * batch to rows; [[VarkaProjectExec]] passes it on as a batch. What each does when the kernels
 * cannot serve a batch is deliberately not shared: the row node projects the input's rows one by
 * one, which is cheaper than materialising a batch just to read rows back out of it, while a
 * columnar-out node has no such option and has to materialise.
 *
 * One instance per partition, created inside the task: it registers a task-completion listener
 * on first use, and its state must not be shared across partitions (see [[SafeForKWayMerge]]).
 */
private[sql] class VarkaKernelEvaluator(
    projectList: Seq[NamedExpression],
    childOutput: Seq[Attribute])
    extends Logging {

  // Per-output-column dispatch plan; None when the projection is not fully Varka-eligible
  // (should not happen given [[VarkaColumnarRule]], but be safe).
  private lazy val outputPlan: Option[Seq[OutputOp]] = buildOutputPlan()

  // One Arrow child allocator for the whole task, created on the first kernel batch. Allocating
  // one per batch - and registering a task-completion listener per batch to close it - would
  // hold every result batch off-heap until the task ended, which is exactly what the streaming
  // iterator model exists to avoid.
  private var kernelAllocator: BufferAllocator = null

  // Batches handed out and not released yet. A batch is normally released by the caller as soon
  // as it is done with it; this set is the safety net for a task that stops early (a LIMIT, a
  // failure) and is drained by the task-completion listener.
  private val openBatches = mutable.Set.empty[ColumnarBatch]

  private var cleanupRegistered = false

  // Task-lifetime assembled runner classes, one per distinct op. None when assembly failed,
  // in which case every batch takes the caller's fallback path.
  private lazy val kernelRunners: Option[KernelRunners] = {
    outputPlan.flatMap { ops =>
      try {
        Some(new KernelRunners(ops.map(_.op).distinct))
      } catch {
        case e if isCatchable(e) =>
          logWarning("Failed to assemble the Varka kernel runners; falling back to the " +
            "per-row projection.", e)
          None
      }
    }
  }

  /** Whether [[project]] can serve this batch, or the caller has to fall back. */
  def canRun(input: ColumnarBatch): Boolean = {
    (outputPlan, kernelRunners) match {
      case (Some(ops), Some(_)) => input.numRows() > 0 && isArrowBacked(ops, input)
      case _ => false
    }
  }

  /**
   * Runs the kernels over the input batch and returns a new batch of their output, tracked here
   * until the caller [[release]]s it. Callers must have asked [[canRun]] first, and must treat a
   * throw as "this batch could not be served": nothing is left allocated by a failed call.
   */
  def project(input: ColumnarBatch): ColumnarBatch = {
    val ops = outputPlan.get
    val runners = kernelRunners.get
    val len = input.numRows()
    val alloc = taskAllocator()
    val vectors = new java.util.ArrayList[ColumnVector]()
    var batch: ColumnarBatch = null
    try {
      ops.foreach { op =>
        vectors.add(buildVector(op, runners, input, len, alloc))
      }
      batch = new ColumnarBatch(vectors.toArray(new Array[ColumnVector](vectors.size())))
      batch.setNumRows(len)
    } catch {
      case e: Throwable =>
        if (batch != null) {
          batch.close()
        } else {
          vectors.forEach(_.close())
        }
        throw e
    }
    track(batch)
  }

  /**
   * Takes ownership of a batch the caller built itself - a fallback batch - so that the same
   * task-completion listener closes it if the task stops before the caller releases it.
   */
  def track(batch: ColumnarBatch): ColumnarBatch = {
    ensureCleanup()
    openBatches += batch
    batch
  }

  /** Closes a batch obtained from [[project]] or handed to [[track]]. */
  def release(batch: ColumnarBatch): Unit = {
    openBatches -= batch
    batch.close()
  }

  /** A kernel failure worth falling back on, rather than one that has to fail the task. */
  def isCatchable(e: Throwable): Boolean = {
    NonFatal(e) || e.isInstanceOf[LinkageError]
  }

  /**
   * Whether the kernels can run over this batch: every referenced column must be an Arrow
   * `DateDayVector` holding exactly the batch's rows, no more.
   *
   * The row count matters because the kernels take a null count for the rows they are given,
   * while a vector's null count covers all `valueCount` of its rows. A vector longer than the
   * batch would hand them a count for rows that are not in it - and a vector whose extra rows
   * happen to hold every null would make that count equal the batch's row count, tripping the
   * kernels' all-null shortcut over rows that are not null at all. Such a batch takes the
   * caller's fallback; serving it from the kernels would mean counting nulls over `[0, len)`
   * here instead.
   */
  private def isArrowBacked(ops: Seq[OutputOp], input: ColumnarBatch): Boolean = {
    ops.forall { op =>
      op.inputOrdinals.forall { ordinal =>
        input.column(ordinal) match {
          case acv: ArrowColumnVector =>
            acv.getValueVector() match {
              case ddv: DateDayVector => ddv.getValueCount() == input.numRows()
              case _ => false
            }
          case _ => false
        }
      }
    }
  }

  /**
   * Registers the single task-completion listener that closes any batch still open and then the
   * allocator. Both this and [[taskAllocator]] are called from the task thread only.
   */
  private def ensureCleanup(): Unit = {
    if (!cleanupRegistered) {
      cleanupRegistered = true
      TaskContext.get().addTaskCompletionListener[Unit] { _ =>
        openBatches.foreach(_.close())
        openBatches.clear()
        if (kernelAllocator != null) {
          kernelAllocator.close()
          kernelAllocator = null
        }
      }
    }
  }

  /** Returns the task's Arrow child allocator, creating it on first use. */
  private def taskAllocator(): BufferAllocator = {
    ensureCleanup()
    if (kernelAllocator == null) {
      kernelAllocator =
        ArrowUtils.rootAllocator.newChildAllocator("varka-kernels", 0, Long.MaxValue)
    }
    kernelAllocator
  }

  /**
   * Allocates the destination Arrow vector and runs the op's kernel directly into its validity
   * and data buffers (zero-copy), then wraps it for the result batch. The kernel zeroes the
   * destination validity buffer first, so only the valid rows get set bits; null lanes of the
   * data buffer are undefined, matching the engine contract.
   */
  private def buildVector(
      op: OutputOp,
      runners: KernelRunners,
      input: ColumnarBatch,
      len: Int,
      allocator: BufferAllocator): ArrowColumnVector = {
    val vector: ValueVector = op.dataType match {
      case DateType => new DateDayVector(s"varka${op.inputOrdinals.mkString}", allocator)
      case IntegerType => new IntVector(s"varka${op.inputOrdinals.mkString}", allocator)
    }
    val fixed = vector.asInstanceOf[BaseFixedWidthVector]
    try {
      fixed.allocateNew(len)
      val morsels = op.inputOrdinals.map { ordinal =>
        val acv = input.column(ordinal).asInstanceOf[ArrowColumnVector]
        extractMorsel(acv.getValueVector().asInstanceOf[DateDayVector], len)
      }
      invokeKernel(runners, op, morsels,
        fixed.getDataBuffer().memoryAddress(), fixed.getValidityBuffer().memoryAddress(), len)
      fixed.setValueCount(len)
    } catch {
      case e: Throwable =>
        vector.close()
        throw e
    }
    new ArrowColumnVector(vector)
  }

  private def invokeKernel(
      runners: KernelRunners,
      op: OutputOp,
      morsels: Seq[Morsel],
      dstData: Long,
      dstValidity: Long,
      len: Int): Unit = {
    if (VarkaColumnarToRowExec.isFailKernelForTesting) {
      // scalastyle:off throwerror
      throw new NoClassDefFoundError("injected Varka kernel failure")
      // scalastyle:on throwerror
    }
    op.kind match {
      case AddDays | SubDays =>
        val m = morsels.head
        runners.unary(op.op).run(
          m.data.address(), m.validityAddress, m.nullCount.toInt,
          dstData, dstValidity, len, op.daysOffset.get)
      case DateDiffKernel =>
        val end = morsels(0)
        val start = morsels(1)
        runners.binary(op.op).run(
          end.data.address(), end.validityAddress, end.nullCount.toInt,
          start.data.address(), start.validityAddress, start.nullCount.toInt,
          dstData, dstValidity, len)
    }
  }

  /**
   * Maps a `DateDayVector` to its data and validity segments (zero-copy), mirroring the
   * engine's `VarkaMorsel.extractDate` contract: the validity segment is null for an all-null
   * column, and callers pass a `0L` address in that case because the kernels never
   * dereference it then.
   *
   * The vector must hold exactly the batch's rows, which `isArrowBacked` has already checked -
   * that is what makes the vector's null count the batch's null count, and so what makes the
   * all-null test below sound.
   */
  private def extractMorsel(ddv: DateDayVector, len: Int): Morsel = {
    require(len == ddv.getValueCount(),
      s"rowCount $len does not match the vector value count ${ddv.getValueCount()}")
    val data = ofAddress(ddv.getDataBuffer())
    val nullCount = ddv.getNullCount()
    val validity = if (nullCount == len) null else ofAddress(ddv.getValidityBuffer())
    Morsel(data, validity, nullCount)
  }

  private def ofAddress(buf: ArrowBuf): MemorySegment = {
    MemorySegment.ofAddress(buf.memoryAddress()).reinterpret(buf.capacity())
  }

  /**
   * The kernel plan of the whole projection, or `None` if any expression in it is not one the
   * kernels can serve. All or nothing: a partially eligible projection takes the fallback,
   * because the fallback projects the entire list in one pass anyway.
   */
  private def buildOutputPlan(): Option[Seq[OutputOp]] = {
    val ops = projectList.map(outputOp)
    Option.when(ops.forall(_.isDefined))(ops.flatten)
  }

  /** The kernel this projection expression maps to, or `None` if it maps to none. */
  private def outputOp(expr: NamedExpression): Option[OutputOp] = {
    val bound: Expression = BindReferences.bindReference(expr, childOutput)
    val inner = bound match {
      case Alias(child, _) => child
      case e => e
    }
    // The conditions must agree with the expressions' own `isClassFileGenEligible`, which is
    // what `VarkaColumnarRule` matched on when it put one of the Varka nodes in the plan. The day
    // offset is folded with `DateVarkaSupport.foldDaysOffset` for that reason: a second copy
    // of the folding rule here could drift from the one the rule consulted.
    inner match {
      case DateAdd(br: BoundReference, days) if br.dataType == DateType
          && DateVarkaSupport.foldDaysOffset(days).isDefined =>
        Some(OutputOp(inner.asInstanceOf[ClassFileCodegenSupport].classFileGenOp, AddDays,
          Seq(br.ordinal), DateVarkaSupport.foldDaysOffset(days), DateType))
      case DateSub(br: BoundReference, days) if br.dataType == DateType
          && DateVarkaSupport.foldDaysOffset(days).isDefined =>
        Some(OutputOp(inner.asInstanceOf[ClassFileCodegenSupport].classFileGenOp, SubDays,
          Seq(br.ordinal), DateVarkaSupport.foldDaysOffset(days), DateType))
      case DateDiff(endBr: BoundReference, startBr: BoundReference)
          if endBr.dataType == DateType && startBr.dataType == DateType =>
        Some(OutputOp(inner.asInstanceOf[ClassFileCodegenSupport].classFileGenOp, DateDiffKernel,
          Seq(endBr.ordinal, startBr.ordinal), None, IntegerType))
      case _ => None
    }
  }

  /**
   * The assembled kernel dispatchers of one task, one per distinct op. Each is an instance of
   * a freshly generated class implementing the kernel-shape interface for its op, so a call
   * goes straight into the kernel with a primitive stack - see
   * `VarkaClassFileGen.assembleKernelClass`. The runners and the classes behind them live for
   * the task: the loader is released on completion so they unload from Metaspace.
   *
   * The instance is stored untyped because the two shapes have no common supertype; `unary`
   * and `binary` are the typed views, and which one is right for an op is decided once, when
   * the plan is built, by `OutputOp.kind`.
   */
  private class KernelRunners(ops: Seq[ClassFileGenOp]) {
    private val loader = new VarkaGeneratedClassLoader(Utils.getContextOrSparkClassLoader)
    private val runners = new ConcurrentHashMap[ClassFileGenOp, AnyRef]()
    private var index = 0

    ops.foreach { op =>
      val className = s"org.apache.spark.sql.varka.execution.VarkaKernelRunner$index"
      index += 1
      loader.defineGeneratedClass(className, VarkaClassFileGen.assembleKernelClass(className, op))
      val clazz = loader.loadClass(className)
      runners.put(op, clazz.getConstructor().newInstance().asInstanceOf[AnyRef])
    }

    TaskContext.get().addTaskCompletionListener[Unit] { _ =>
      loader.release()
    }

    def unary(op: ClassFileGenOp): VarkaUnaryKernel = {
      runners.get(op).asInstanceOf[VarkaUnaryKernel]
    }

    def binary(op: ClassFileGenOp): VarkaBinaryKernel = {
      runners.get(op).asInstanceOf[VarkaBinaryKernel]
    }
  }
}

private sealed trait KernelKind
private case object AddDays extends KernelKind
private case object SubDays extends KernelKind
private case object DateDiffKernel extends KernelKind

private case class OutputOp(
    op: ClassFileGenOp,
    kind: KernelKind,
    inputOrdinals: Seq[Int],
    daysOffset: Option[Int],
    dataType: DataType)

private case class Morsel(data: MemorySegment, validity: MemorySegment, nullCount: Long) {
  def validityAddress: Long = if (validity == null) 0L else validity.address()
}
