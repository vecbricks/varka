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

import scala.util.control.NonFatal

import org.apache.spark.{PartitionEvaluator, PartitionEvaluatorFactory, SparkException}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, BasePredicate, Expression, IsNotNull, Predicate, PredicateHelper, SortOrder, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSelectionBitmap
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.execution.vectorized.{OffHeapColumnVector, OnHeapColumnVector, WritableColumnVector}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.ArrayImplicits._

/**
 * What the two Varka filter nodes share (task 21): the `FilterExec`-compatible output contract
 * and the ordering guarantee. The nullability tightening is copied from `FilterExec` rule for
 * rule - a column an `IS NOT NULL` conjunct guards reads as non-nullable downstream - because
 * the rewrite must not change what the planner believes about the columns.
 *
 * The node's `condition` holds only the conjuncts the mask kernel serves:
 * [[VarkaColumnarRule]] splits a mixed predicate and keeps the residual conjuncts in a row
 * `FilterExec` above, so within a Varka filter node the predicate is fully fused by
 * construction (and the evaluator falls back per batch if that ever fails to hold).
 */
private[sql] trait VarkaFilterExecBase extends UnaryExecNode with PredicateHelper {

  def condition: Expression

  // Split out all the IsNotNulls from condition, exactly as FilterExec does.
  private lazy val notNullPreds = splitConjunctivePredicates(condition).filter {
    case IsNotNull(a) => isNullIntolerant(a) && a.references.subsetOf(child.outputSet)
    case _ => false
  }

  // The columns that will be filtered out by `IsNotNull` could be considered as not nullable.
  private lazy val notNullAttributes = notNullPreds.flatMap(_.references).distinct.map(_.exprId)

  override def output: Seq[Attribute] = outputWithNullability(child.output, notNullAttributes)

  // A filter neither reorders, renames nor repartitions: ordering AND partitioning forward
  // verbatim, exactly as FilterExec and ColumnarToRowExec forward them. (Neither is a
  // default: SparkPlan falls back to UnknownPartitioning - the task-21 review's second pass
  // corrected a comment here that claimed a pass-through default - and losing the child's
  // partitioning would reintroduce shuffles above a cached filtered relation.)
  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override def outputPartitioning: Partitioning = child.outputPartitioning

  // One driver-side compilation serves every EXPLAIN render (task-21 review, second pass:
  // parity with the projection nodes' memoized classification).
  @transient private lazy val fusionLines =
    VarkaFusionReport.predicateLines(condition, child.output)

  // Task 16's question for a filter is "why didn't my predicate fuse?", answered per conjunct.
  // The condition renders as FilterExec renders its own - a plain line, because
  // ExplainUtils.generateFieldString does not accept a bare expression (formatted EXPLAIN of
  // any Varka filter node used to throw on exactly that; caught writing the PR description).
  override def verboseStringWithOperatorId(): String = {
    s"""
       |$formattedNodeName
       |${ExplainUtils.generateFieldString("Input", child.output)}
       |Condition : $condition
       |${ExplainUtils.generateFieldString("Varka", fusionLines)}
       |""".stripMargin
  }
}

/**
 * The Varka filter with columnar output (task 21): per batch it runs the mask kernel - one
 * fused loop whose single output is the predicate's selection bitmap - and compacts the
 * selected rows into a fresh dense batch. That compaction is the v1 selected-batch contract
 * (milestone open question 2): what leaves this node is an ordinary `ColumnarBatch`, so every
 * consumer's invariants hold - a Varka projection stacks directly on top, and a row consumer
 * gets its transition fused into [[VarkaFilterColumnarToRowExec]], which skips the compaction
 * entirely by consuming the bitmap at the row boundary.
 *
 * Batch ownership follows [[VarkaProjectExec]]'s convention, with one difference the class doc
 * of the compaction records: a compacting filter owns every output column it actually
 * compacts, because a forwarded vector cannot be shortened. The exception is the one case
 * where nothing needs shortening - every row selected - where task 24 forwards the child's
 * columns untouched and the batch owns nothing.
 *
 * A batch the kernel cannot serve falls back to the per-row predicate into a fresh writable
 * batch, mirroring [[VarkaProjectExec]]'s fallback.
 */
case class VarkaFilterExec(condition: Expression, child: SparkPlan)
    extends VarkaFilterExecBase
    with SafeForKWayMerge {

  override def supportsColumnar: Boolean = true

  override protected def withNewChildInternal(newChild: SparkPlan): VarkaFilterExec = {
    copy(child = newChild)
  }

  // The shared node vocabulary; no residual-entry metric - a filter's residual is a visible
  // row FilterExec above it - and numOutputRows counts selected rows.
  override lazy val metrics: Map[String, SQLMetric] =
    VarkaExecMetrics.nodeMetrics(sparkContext)

  // `supportsRowBased` is false because this node is columnar, so the transition rule never
  // asks it for rows: it inserts a to-row transition above instead, which the columnar rule
  // then fuses into `VarkaFilterColumnarToRowExec`.
  override protected def doExecute(): RDD[InternalRow] = {
    throw SparkException.internalError(s"$nodeName does not produce rows")
  }

  override protected def doExecuteColumnar(): RDD[ColumnarBatch] = {
    val evaluatorFactory = new VarkaFilterEvaluatorFactory(
      condition,
      child.output,
      conf.offHeapColumnVectorEnabled,
      conf.varkaClassDumpDirectory,
      longMetric("numOutputRows"),
      longMetric("numInputBatches"),
      VarkaExecMetrics.fromNode(longMetric))
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

private[sql] class VarkaFilterEvaluatorFactory(
    condition: Expression,
    childOutput: Seq[Attribute],
    offHeapColumnVectorEnabled: Boolean,
    classDumpDirectory: Option[String],
    numOutputRows: SQLMetric,
    numInputBatches: SQLMetric,
    varkaMetrics: VarkaExecMetrics)
    extends PartitionEvaluatorFactory[ColumnarBatch, ColumnarBatch] with Logging {

  override def createEvaluator(): PartitionEvaluator[ColumnarBatch, ColumnarBatch] = {
    new PartitionFilterEvaluator
  }

  private class PartitionFilterEvaluator
      extends PartitionEvaluator[ColumnarBatch, ColumnarBatch] {

    private val kernels = new VarkaFilterEvaluator(
      condition, childOutput, offHeapColumnVectorEnabled, operatorName = "Filter",
      classDumpDirectory, varkaMetrics)

    // The per-row predicate and converter behind the fallback. Lazy (task 15): a task the
    // kernel serves end to end never pays the Janino compile.
    private lazy val fallbackPredicate: BasePredicate = {
      val predicate = Predicate.create(condition, childOutput)
      predicate.initialize(partitionIdx)
      predicate
    }
    private lazy val identityProjection = UnsafeProjection.create(childOutput, childOutput)
    private val outputSchema: StructType = DataTypeUtils.fromAttributes(childOutput)
    private lazy val converter = new RowToColumnConverter(outputSchema)
    private var partitionIdx: Int = 0

    override def eval(
        partitionIndex: Int,
        inputs: Iterator[ColumnarBatch]*): Iterator[ColumnarBatch] = {
      assert(inputs.length == 1)
      partitionIdx = partitionIndex
      val batches = inputs.head
      new Iterator[ColumnarBatch] {
        // Same one-batch-at-a-time discipline as VarkaProjectExec: released before the next
        // input batch is requested. Two things now depend on that ordering, not one. The
        // selection buffer is task state, so the previous batch must be done before the mask
        // is overwritten - and since task 24 an all-selected batch *forwards* the child's
        // vectors rather than copying them, so an output batch can alias input memory that a
        // buffer-reusing child recycles on its next(). Release-before-next is what makes both
        // sound; reordering it (prefetching input, holding two batches) is a use-after-free
        // on exactly the all-selected batches.
        private var current: ColumnarBatch = null

        override def hasNext: Boolean = {
          val more = batches.hasNext
          if (!more) {
            releaseCurrent()
          }
          more
        }

        override def next(): ColumnarBatch = {
          releaseCurrent()
          val input = batches.next()
          numInputBatches += 1
          current = filterBatch(input)
          numOutputRows += current.numRows()
          current
        }

        private def releaseCurrent(): Unit = {
          if (current != null) {
            kernels.release(current)
            current = null
          }
        }
      }
    }

    // The evaluator's serveBatch runs the shared per-batch dispatch and cause accounting
    // (task-21 review, both passes) and routes every degradation to the fallback.
    private def filterBatch(input: ColumnarBatch): ColumnarBatch = {
      kernels.serveBatch(input) {
        val batch = kernels.filterCompact(input)
        varkaMetrics.varkaBatches.foreach(_ += 1)
        batch
      } {
        fallback(input)
      }
    }

    /**
     * Filters the input batch row by row into a fresh writable batch - the same conversion
     * [[VarkaProjectExec]]'s fallback does, gated by the predicate. Tracked by the kernel
     * evaluator so one task-completion listener covers both kinds of output batch.
     */
    private def fallback(input: ColumnarBatch): ColumnarBatch = {
      val capacity = math.max(input.numRows(), 1)
      val vectors: Seq[WritableColumnVector] = if (offHeapColumnVectorEnabled) {
        OffHeapColumnVector.allocateColumns(capacity, outputSchema).toImmutableArraySeq
      } else {
        OnHeapColumnVector.allocateColumns(capacity, outputSchema).toImmutableArraySeq
      }
      val batch = new ColumnarBatch(vectors.toArray)
      try {
        val writable = vectors.toArray[WritableColumnVector]
        val rows = input.rowIterator()
        var rowCount = 0
        while (rows.hasNext) {
          val row = rows.next()
          if (fallbackPredicate.eval(row)) {
            converter.convert(identityProjection(row), writable)
            rowCount += 1
          }
        }
        batch.setNumRows(rowCount)
      } catch {
        case e: Throwable =>
          // Attach a cleanup failure to the error being reported rather than replacing it:
          // ColumnarBatch.close loops its columns unguarded, so one throwing column would
          // otherwise strand the rest and lose the conversion error that actually matters.
          try {
            batch.close()
          } catch {
            case NonFatal(c) => e.addSuppressed(c)
          }
          throw e
      }
      kernels.track(batch)
    }
  }
}

/**
 * The Varka filter fused with its to-row transition (task 21): the plan a row consumer gets,
 * the way [[VarkaColumnarToRowExec]] is the row form of [[VarkaProjectExec]]. Per batch it
 * runs the mask kernel and then emits only the selected rows during the row conversion -
 * '''no compaction at all''': the selection bitmap is consumed at the row boundary, which is
 * the second half of the v1 selected-batch contract and the cheap path for the survey's
 * dominant `WHERE`-plus-aggregate shape.
 *
 * The node is not `CodegenSupport`; whole-stage codegen splits at this boundary, exactly as at
 * [[VarkaColumnarToRowExec]] - the read-back cost task 19 measured and accepted. And exactly as
 * there, the [[ColumnarToRowTransition]] tag carries the caveat that this transition is NOT
 * semantics-free: machinery that strips a topmost transition must convert this node to
 * [[VarkaFilterExec]] instead (the cache serializer does), or it silently drops the filter -
 * the wrong-cached-view bug task 21 found and fixed.
 */
case class VarkaFilterColumnarToRowExec(condition: Expression, child: SparkPlan)
    extends VarkaFilterExecBase
    with VarkaFusedTransition
    with SafeForKWayMerge {

  override def columnarSibling: SparkPlan = VarkaFilterExec(condition, child)

  override protected def withNewChildInternal(newChild: SparkPlan): VarkaFilterColumnarToRowExec = {
    copy(child = newChild)
  }

  // The shared node vocabulary; no residual-entry metric - a filter's residual is a visible
  // row FilterExec above it - and numOutputRows counts selected rows.
  override lazy val metrics: Map[String, SQLMetric] =
    VarkaExecMetrics.nodeMetrics(sparkContext)

  override def doExecute(): RDD[InternalRow] = {
    val evaluatorFactory = new VarkaFilterToRowEvaluatorFactory(
      condition,
      child.output,
      conf.varkaClassDumpDirectory,
      longMetric("numOutputRows"),
      longMetric("numInputBatches"),
      VarkaExecMetrics.fromNode(longMetric))
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

private[sql] class VarkaFilterToRowEvaluatorFactory(
    condition: Expression,
    childOutput: Seq[Attribute],
    classDumpDirectory: Option[String],
    numOutputRows: SQLMetric,
    numInputBatches: SQLMetric,
    varkaMetrics: VarkaExecMetrics)
    extends PartitionEvaluatorFactory[ColumnarBatch, InternalRow] with Logging {

  override def createEvaluator(): PartitionEvaluator[ColumnarBatch, InternalRow] = {
    new PartitionFilterToRowEvaluator
  }

  private class PartitionFilterToRowEvaluator
      extends PartitionEvaluator[ColumnarBatch, InternalRow] {

    // The filter evaluator never compacts here, so the off-heap flag is moot; passing false
    // keeps the constructor honest about what this node allocates (nothing but the bitmap).
    private val kernels = new VarkaFilterEvaluator(
      condition, childOutput, offHeapColumnVectorEnabled = false,
      operatorName = "FilterToRow", classDumpDirectory, varkaMetrics)

    // The emitted rows hold their own bytes (an UnsafeProjection copy), so they outlive the
    // input batch exactly as VarkaColumnarToRowExec's rows do; the fallback predicate is the
    // per-row form of the same condition. Both lazy (task 15).
    private lazy val toUnsafe = UnsafeProjection.create(childOutput, childOutput)
    private lazy val fallbackPredicate: BasePredicate = {
      val predicate = Predicate.create(condition, childOutput)
      predicate.initialize(partitionIdx)
      predicate
    }
    private var partitionIdx: Int = 0

    override def eval(
        partitionIndex: Int,
        inputs: Iterator[ColumnarBatch]*): Iterator[InternalRow] = {
      assert(inputs.length == 1)
      partitionIdx = partitionIndex
      inputs.head.flatMap { input =>
        numInputBatches += 1
        process(input)
      }
    }

    // The evaluator's serveBatch runs the shared per-batch dispatch and cause accounting
    // (task-21 review, both passes) and routes every degradation to the fallback.
    private def process(input: ColumnarBatch): Iterator[InternalRow] = {
      kernels.serveBatch(input) {
        val selection = kernels.filterMask(input)
        varkaMetrics.varkaBatches.foreach(_ += 1)
        selectedRows(input, selection)
      } {
        fallback(input)
      }
    }

    /**
     * The selected rows of the batch, in order: one sequential pass over the batch's rows,
     * skipping every row whose selection bit is unset. The conversion to the caller's own
     * bytes happens before the iterator advances, because `rowIterator` reuses its row.
     * The bitmap is read fully before the next batch's mask overwrites it - guaranteed by
     * `flatMap`'s one-batch-at-a-time pull.
     */
    private def selectedRows(input: ColumnarBatch, selection: VarkaSelection)
        : Iterator[InternalRow] = {
      val rows = input.rowIterator()
      new Iterator[InternalRow] {
        private var index = -1
        private var pending: InternalRow = null
        advance()

        private def advance(): Unit = {
          pending = null
          while (pending == null && rows.hasNext) {
            val row = rows.next()
            index += 1
            if (VarkaSelectionBitmap.isSet(selection.mask, index)) {
              pending = row
            }
          }
        }

        override def hasNext: Boolean = pending != null

        override def next(): InternalRow = {
          if (pending == null) {
            throw new NoSuchElementException("next on an exhausted selection iterator")
          }
          val result = toUnsafe(pending)
          // Counted as emitted, not per batch (task-21 review, second pass): pre-charging
          // the batch's whole selected count overcounts under early termination and
          // double-counts when a later throw routes the batch to the fallback.
          numOutputRows += 1
          advance()
          result
        }
      }
    }

    private def fallback(input: ColumnarBatch): Iterator[InternalRow] = {
      import scala.jdk.CollectionConverters._
      input.rowIterator().asScala.filter { row =>
        val selected = fallbackPredicate.eval(row)
        if (selected) numOutputRows += 1
        selected
      }.map(toUnsafe)
    }
  }
}
