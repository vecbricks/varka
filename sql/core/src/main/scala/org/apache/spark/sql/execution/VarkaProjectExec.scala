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
import org.apache.spark.sql.catalyst.expressions.{Attribute, NamedExpression, SortOrder, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.execution.vectorized.{OffHeapColumnVector, OnHeapColumnVector, WritableColumnVector}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.ArrayImplicits._

/**
 * The Varka projection with columnar output: it runs the SIMD kernels over an Arrow-backed
 * `ColumnarBatch` and passes the kernels' own output batch on, rather than converting it to rows.
 *
 * This is the node [[VarkaColumnarRule]] puts in the plan before transitions are inserted. What
 * happens next is Spark's decision, not Varka's: a consumer that takes batches - a DSv2 write
 * whose connector declares `supportsColumnarWrite`, for instance - gets them, and a consumer that
 * wants rows gets a to-row transition, which the rule then fuses back into a
 * [[VarkaColumnarToRowExec]]. So this node only survives into the executed plan where its batches
 * are actually consumed as batches.
 *
 * Batch ownership follows the convention in `SparkPlan.doExecuteColumnar`: the producer owns the
 * batch and closes it, so a consumer must not hold on to one past the call that gave it. This
 * node releases each batch when the next is requested - before asking the child for more input,
 * which the evaluator's ordering contract requires now that an output batch can hold forwarded
 * input vectors (task 12) - and [[VarkaKernelEvaluator]]'s
 * task-completion listener closes whatever is still open if the task stops early.
 *
 * A batch the kernels cannot serve - not Arrow-backed, empty, or a kernel failure - is projected
 * per row into a fresh writable batch, the same conversion [[RowToColumnarExec]] does. That is a
 * copy the row-output node avoids, but a columnar-out node has no way to avoid it, and it only
 * happens on the path that was already the slow one.
 */
case class VarkaProjectExec(
    projectList: Seq[NamedExpression],
    child: SparkPlan)
    extends UnaryExecNode
    with SafeForKWayMerge
    with PartitioningPreservingUnaryExecNode
    with OrderPreservingUnaryExecNode {

  override def output: Seq[Attribute] = projectList.map(_.toAttribute)

  override def supportsColumnar: Boolean = true

  // As in `VarkaColumnarToRowExec`: this node is a projection, so partitioning and ordering go
  // through the alias mapping rather than being reported verbatim.
  override protected def outputExpressions: Seq[NamedExpression] = projectList

  override protected def orderingExpressions: Seq[SortOrder] = child.outputOrdering

  override protected def withNewChildInternal(newChild: SparkPlan): VarkaProjectExec = {
    copy(child = newChild)
  }

  override lazy val metrics: Map[String, SQLMetric] =
    VarkaExecMetrics.projectionMetrics(sparkContext)

  // One driver-side compilation serves both EXPLAIN and the residual-entry count below
  // (task-21 review: the node used to re-run the same pure compile per consumer).
  @transient private lazy val classification =
    VarkaExpressionCompiler.compilePartial(projectList, child.output)

  // Task 16: verbose EXPLAIN answers "why didn't my projection fuse?" - every entry's
  // classification, and for a residual entry the reason the compiler declined it.
  override def verboseStringWithOperatorId(): String = {
    s"""
       |$formattedNodeName
       |${ExplainUtils.generateFieldString("Output", projectList)}
       |${ExplainUtils.generateFieldString("Input", child.output)}
       |${ExplainUtils.generateFieldString("Varka",
            VarkaFusionReport.lines(classification, projectList, child.output))}
       |""".stripMargin
  }

  // `supportsRowBased` is false because this node is columnar, so the transition rule never asks
  // it for rows: it inserts a to-row transition above instead, which the columnar rule then fuses
  // into `VarkaColumnarToRowExec`.
  override protected def doExecute(): RDD[InternalRow] = {
    throw SparkException.internalError(s"$nodeName does not produce rows")
  }

  override protected def doExecuteColumnar(): RDD[ColumnarBatch] = {
    // Task 22: the residual-entry count is a static plan property - added once, driver-side,
    // so the UI total does not multiply by task count. The per-entry reasons are in EXPLAIN.
    // Posted explicitly (task-21 review): the SQL listener aggregates task-end updates and
    // posted driver updates only, so without the post the UI would always read 0 while the
    // driver-local accumulator - the one tests read - carried the count.
    val residualMetric = longMetric("numResidualEntries")
    residualMetric += classification.map(_.declines.size.toLong).getOrElse(0L)
    SQLMetrics.postDriverMetricUpdates(sparkContext,
      sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY), Seq(residualMetric))
    val varkaMetrics = VarkaExecMetrics.fromNode(longMetric)
    val evaluatorFactory = new VarkaProjectEvaluatorFactory(
      projectList,
      child.output,
      conf.offHeapColumnVectorEnabled,
      conf.varkaClassDumpDirectory,
      longMetric("numOutputRows"),
      longMetric("numInputBatches"),
      varkaMetrics)
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

private[sql] class VarkaProjectEvaluatorFactory(
    projectList: Seq[NamedExpression],
    childOutput: Seq[Attribute],
    offHeapColumnVectorEnabled: Boolean,
    classDumpDirectory: Option[String],
    numOutputRows: SQLMetric,
    numInputBatches: SQLMetric,
    varkaMetrics: VarkaExecMetrics)
    extends PartitionEvaluatorFactory[ColumnarBatch, ColumnarBatch] with Logging {

  override def createEvaluator(): PartitionEvaluator[ColumnarBatch, ColumnarBatch] = {
    new VarkaProjectEvaluator
  }

  private class VarkaProjectEvaluator extends PartitionEvaluator[ColumnarBatch, ColumnarBatch] {

    private val kernels = new VarkaKernelEvaluator(
      projectList, childOutput, offHeapColumnVectorEnabled, operatorName = "Project",
      classDumpDirectory, varkaMetrics)

    // The per-row projection behind the fallback, and the schema its rows are written back into.
    // Lazy (task 15): a task the kernels serve end to end never compiles it, so the Janino
    // compile is paid only by tasks that actually fall back.
    private lazy val fallbackProjection = UnsafeProjection.create(projectList, childOutput)
    private val outputSchema: StructType =
      DataTypeUtils.fromAttributes(projectList.map(_.toAttribute))
    private val converter = new RowToColumnConverter(outputSchema)

    override def eval(
        partitionIndex: Int,
        inputs: Iterator[ColumnarBatch]*): Iterator[ColumnarBatch] = {
      assert(inputs.length == 1)
      val batches = inputs.head
      new Iterator[ColumnarBatch] {
        // The batch handed to the consumer last, released when the next one is asked for -
        // before `batches.next()` below, per the evaluator's ordering contract: it may hold
        // forwarded vectors of the input batch the child is about to reclaim. Holding
        // it until then is what lets the consumer read it; holding it any longer would keep two
        // batches of kernel output alive at once.
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
          numOutputRows += input.numRows()
          current = project(input)
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
    private def project(input: ColumnarBatch): ColumnarBatch = {
      kernels.serveBatch(input) {
        val batch = kernels.project(input)
        varkaMetrics.varkaBatches.foreach(_ += 1)
        batch
      } {
        fallback(input)
      }
    }

    /**
     * Projects the input batch row by row into a fresh writable batch, the conversion
     * [[RowToColumnConverter]] exists for. The batch is tracked by the kernel evaluator so that
     * one task-completion listener covers both kinds of output batch.
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
          converter.convert(fallbackProjection(rows.next()), writable)
          rowCount += 1
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
