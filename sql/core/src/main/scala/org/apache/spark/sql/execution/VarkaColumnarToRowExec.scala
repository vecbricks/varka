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
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.apache.arrow.memory.{ArrowBuf, BufferAllocator}
import org.apache.arrow.vector.{BaseFixedWidthVector, DateDayVector, IntVector, ValueVector}

import org.apache.spark.{PartitionEvaluator, PartitionEvaluatorFactory, TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, BindReferences, BoundReference, DateAdd, DateDiff, DateSub, Expression, Literal, NamedExpression, SortOrder, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.{ClassFileCodegenSupport, ClassFileGenOp, VarkaClassFileGen, VarkaGeneratedClassLoader}
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.types.{DataType, DateType, IntegerType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}
import org.apache.spark.util.{CompletionIterator, Utils}

/**
 * The Varka columnar-to-row transition (Task 6). It projects an Arrow-backed `ColumnarBatch`
 * with the Varka SIMD kernels instead of per-row codegen: a fully Varka-eligible
 * [[ProjectExec]] sitting directly above a [[ColumnarToRowExec]] is fused into this node by
 * [[VarkaColumnarRule]].
 *
 * Per batch: when `spark.sql.codegen.varka.enabled` is set, every referenced input column is an
 * [[ArrowColumnVector]] over an Arrow `DateDayVector`, and the projection is fully Varka-eligible,
 * the node runs the kernels by writing each projected column directly into the buffers of a
 * freshly allocated Arrow vector (via a task-lifetime assembled runner class per distinct op,
 * invoked reflectively - see `VarkaClassFileGen.assembleKernelClass`). The result batch is
 * converted to rows with the standard copy projection and released as soon as its rows are
 * consumed, so only one batch of kernel output is held at a time. Anything else - a non-Arrow
 * batch, an empty batch, a kernel failure - falls back to the standard per-row projection over
 * the input batch.
 *
 * The node is not `CodegenSupport`; whole-stage codegen splits at this boundary (correctness
 * first, codegen support is a follow-up).
 *
 * The engine module (`varka-engine`) is deliberately kept off the main compile classpath: only
 * its kernel descriptors (strings) and Arrow classes are referenced here. The engine jar is a
 * test-scoped dependency; at runtime it is deployed externally (`--jars`) and its absence only
 * degrades the kernel path to the per-row fallback.
 */
case class VarkaColumnarToRowExec(
    projectList: Seq[NamedExpression],
    child: SparkPlan)
    extends ColumnarToRowTransition
    with SafeForKWayMerge
    with PartitioningPreservingUnaryExecNode
    with OrderPreservingUnaryExecNode {

  override def output: Seq[Attribute] = projectList.map(_.toAttribute)

  // This node is a projection: it renames and drops columns, so it cannot report the child's
  // partitioning and ordering verbatim the way `ColumnarToRowExec` does. The two alias-aware
  // traits above map them through the projection's alias mapping and drop whatever is no longer
  // in `output`, which is what the `ProjectExec` fused away here would have done.
  override protected def outputExpressions: Seq[NamedExpression] = projectList

  override protected def orderingExpressions: Seq[SortOrder] = child.outputOrdering

  override protected def withNewChildInternal(newChild: SparkPlan): VarkaColumnarToRowExec = {
    copy(child = newChild)
  }

  override lazy val metrics: Map[String, SQLMetric] = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
    "numInputBatches" -> SQLMetrics.createMetric(sparkContext, "number of input batches"),
    "numVarkaBatches" -> SQLMetrics.createMetric(
      sparkContext, "number of input batches processed by the Varka SIMD kernels"))

  override def doExecute(): RDD[InternalRow] = {
    val evaluatorFactory = new VarkaColumnarToRowEvaluatorFactory(
      projectList,
      child.output,
      longMetric("numOutputRows"),
      longMetric("numInputBatches"),
      longMetric("numVarkaBatches"))
    if (conf.usePartitionEvaluator) {
      child.executeColumnar().mapPartitionsWithEvaluator(evaluatorFactory)
    } else {
      child.executeColumnar().mapPartitionsWithIndexInternal { (index, batches) =>
        val evaluator = evaluatorFactory.createEvaluator()
        evaluator.eval(index, batches)
      }
    }
  }
}

private[sql] object VarkaColumnarToRowExec {

  // Test-only hook that makes the kernel invocation fail (simulating a linkage failure), forcing
  // the per-batch fallback. Static, not thread-local: Spark runs tasks on separate threads, so a
  // thread-local set by the test thread would never be visible to the task. Suites that use it
  // must reset it in a finally block.
  @volatile private var failKernelForTesting = false

  private[sql] def setFailKernelForTesting(fail: Boolean): Unit = {
    failKernelForTesting = fail
  }

  private[sql] def isFailKernelForTesting: Boolean = failKernelForTesting
}

private[sql] class VarkaColumnarToRowEvaluatorFactory(
    projectList: Seq[NamedExpression],
    childOutput: Seq[Attribute],
    numOutputRows: SQLMetric,
    numInputBatches: SQLMetric,
    numVarkaBatches: SQLMetric)
    extends PartitionEvaluatorFactory[ColumnarBatch, InternalRow] with Logging {

  override def createEvaluator(): PartitionEvaluator[ColumnarBatch, InternalRow] = {
    new VarkaColumnarToRowEvaluator
  }

  private class VarkaColumnarToRowEvaluator
      extends PartitionEvaluator[ColumnarBatch, InternalRow] {

    // The two projections that turn batch rows into rows: the standard per-row (Janino)
    // projection over the input batch, used as the fallback, and the copy projection that
    // converts the result batch to rows, mirroring `ColumnarToRowEvaluatorFactory`.
    //
    // Both hand back their own `UnsafeRow`, rewritten on every call, and the rows are emitted as
    // they come - uncopied, exactly as `ColumnarToRowExec` emits them. A `.copy()` per row would
    // add an allocation plus a memcpy to the hot path for a guarantee this operator's consumers
    // do not get from the standard path either. The projected row holds its own bytes rather
    // than a view of the batch, so it also outlives the release of the kernel result batch it
    // came from.
    private val fallbackProjection = UnsafeProjection.create(projectList, childOutput)
    private val outputAttrs = projectList.map(_.toAttribute)
    private val toRow = UnsafeProjection.create(outputAttrs, outputAttrs)

    // Per-output-column dispatch plan; None when the projection is not fully Varka-eligible
    // (should not happen given [[VarkaColumnarRule]], but be safe).
    private lazy val outputPlan: Option[Seq[OutputOp]] = buildOutputPlan()

    // One Arrow child allocator for the whole task, created on the first kernel batch. Allocating
    // one per batch - and registering a task-completion listener per batch to close it - would
    // hold every result batch off-heap until the task ended, which is exactly what the streaming
    // iterator model exists to avoid.
    private var kernelAllocator: BufferAllocator = null

    // Result batches whose rows have not been fully consumed yet. A batch is normally closed by
    // the `CompletionIterator` wrapping its rows; this set is the safety net for a task that
    // stops reading early (a LIMIT, a failure) and is drained by the task-completion listener.
    private val openBatches = mutable.Set.empty[ColumnarBatch]

    // Task-lifetime assembled runner classes, one per distinct op. None when assembly failed,
    // in which case every batch takes the fallback path.
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

    override def eval(
        partitionIndex: Int,
        inputs: Iterator[ColumnarBatch]*): Iterator[InternalRow] = {
      assert(inputs.length == 1)
      inputs.head.flatMap { input =>
        numInputBatches += 1
        numOutputRows += input.numRows()
        process(input)
      }
    }

    private def process(input: ColumnarBatch): Iterator[InternalRow] = {
      (outputPlan, kernelRunners) match {
        case (Some(ops), Some(runners)) if input.numRows() > 0 && isArrowBacked(ops, input) =>
          try {
            val rows = runKernels(ops, runners, input)
            numVarkaBatches += 1
            rows
          } catch {
            case e if isCatchable(e) =>
              logWarning("The Varka SIMD kernels failed on this batch; falling back to the " +
                "per-row projection.", e)
              fallback(input)
          }
        case _ =>
          fallback(input)
      }
    }

    private def fallback(input: ColumnarBatch): Iterator[InternalRow] = {
      input.rowIterator().asScala.map(fallbackProjection)
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
     * per-row fallback; serving it from the kernels would mean counting nulls over `[0, len)`
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
     * Returns the task's Arrow child allocator, creating it - and the single task-completion
     * listener that closes any batch still open and then the allocator itself - on first use.
     */
    private def taskAllocator(): BufferAllocator = {
      if (kernelAllocator == null) {
        kernelAllocator =
          ArrowUtils.rootAllocator.newChildAllocator("varka-kernels", 0, Long.MaxValue)
        TaskContext.get().addTaskCompletionListener[Unit] { _ =>
          openBatches.foreach(_.close())
          openBatches.clear()
          kernelAllocator.close()
          kernelAllocator = null
        }
      }
      kernelAllocator
    }

    private def runKernels(
        ops: Seq[OutputOp],
        runners: KernelRunners,
        input: ColumnarBatch): Iterator[InternalRow] = {
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
      // The rows stream lazily, so the batch is released when its rows run out rather than in a
      // finally here; the task-completion listener closes it if the task stops reading early.
      val resultBatch = batch
      openBatches += resultBatch
      CompletionIterator[InternalRow, Iterator[InternalRow]](
        resultBatch.rowIterator().asScala.map(toRow), {
          openBatches -= resultBatch
          resultBatch.close()
        })
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
          runners.invoke(op.op, Seq(
            Long.box(m.data.address()), Long.box(m.validityAddress), Int.box(m.nullCount.toInt),
            Long.box(dstData), Long.box(dstValidity), Int.box(len), Int.box(op.daysOffset.get)))
        case DateDiffKernel =>
          val end = morsels(0)
          val start = morsels(1)
          runners.invoke(op.op, Seq(
            Long.box(end.data.address()), Long.box(end.validityAddress),
            Int.box(end.nullCount.toInt),
            Long.box(start.data.address()), Long.box(start.validityAddress),
            Int.box(start.nullCount.toInt),
            Long.box(dstData), Long.box(dstValidity), Int.box(len)))
      }
    }

    private def isCatchable(e: Throwable): Boolean = {
      NonFatal(e) || e.isInstanceOf[LinkageError]
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

    private def foldDaysOffset(days: Expression): Option[Int] = days match {
      case Literal(value: Number, _) => Some(value.intValue())
      case _ => None
    }

    private def buildOutputPlan(): Option[Seq[OutputOp]] = {
      val ops = projectList.map { expr =>
        val bound: Expression = BindReferences.bindReference(expr, childOutput)
        val inner = bound match {
          case Alias(child, _) => child
          case e => e
        }
        inner match {
          case DateAdd(br: BoundReference, days) if br.dataType == DateType
              && foldDaysOffset(days).isDefined =>
            OutputOp(inner.asInstanceOf[ClassFileCodegenSupport].classFileGenOp, AddDays,
              Seq(br.ordinal), foldDaysOffset(days), DateType)
          case DateSub(br: BoundReference, days) if br.dataType == DateType
              && foldDaysOffset(days).isDefined =>
            OutputOp(inner.asInstanceOf[ClassFileCodegenSupport].classFileGenOp, SubDays,
              Seq(br.ordinal), foldDaysOffset(days), DateType)
          case DateDiff(endBr: BoundReference, startBr: BoundReference)
              if endBr.dataType == DateType && startBr.dataType == DateType =>
            OutputOp(inner.asInstanceOf[ClassFileCodegenSupport].classFileGenOp, DateDiffKernel,
              Seq(endBr.ordinal, startBr.ordinal), None, IntegerType)
          case _ => return None
        }
      }
      Some(ops)
    }

    private class KernelRunners(ops: Seq[ClassFileGenOp]) {
      private val loader = new VarkaGeneratedClassLoader(Utils.getContextOrSparkClassLoader)
      private val methods = new ConcurrentHashMap[ClassFileGenOp, Method]()
      private var index = 0

      ops.foreach { op =>
        val className = s"org.apache.spark.sql.varka.execution.VarkaKernelRunner$index"
        index += 1
        loader.defineGeneratedClass(className, VarkaClassFileGen.assembleKernelClass(className, op))
        val clazz = loader.loadClass(className)
        methods.put(op, clazz.getMethod("run", paramClasses(op.methodDescriptor): _*))
      }

      TaskContext.get().addTaskCompletionListener[Unit] { _ =>
        loader.release()
      }

      def invoke(op: ClassFileGenOp, args: Seq[Any]): Unit = {
        methods.get(op).invoke(null, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }

    private def paramClasses(descriptor: String): Seq[Class[_]] = {
      descriptor.substring(descriptor.indexOf('(') + 1, descriptor.indexOf(')')).map {
        case 'J' => java.lang.Long.TYPE
        case 'I' => java.lang.Integer.TYPE
        case c => throw new IllegalStateException(
          s"Unsupported Varka kernel parameter type '$c' in descriptor $descriptor")
      }
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
