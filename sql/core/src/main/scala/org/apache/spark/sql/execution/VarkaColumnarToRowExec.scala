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

import scala.jdk.CollectionConverters._

import org.apache.spark.{PartitionEvaluator, PartitionEvaluatorFactory}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, JoinedRow, NamedExpression, SortOrder, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.{ForwardedOutput, FusedOutput, ResidualOutput}
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.CompletionIterator

/**
 * The Varka columnar-to-row transition (Task 6). It projects an Arrow-backed `ColumnarBatch`
 * with the Varka SIMD kernels instead of per-row codegen, and hands the result on as rows: it is
 * what [[VarkaColumnarRule]] leaves in the plan when the consumer above the projection wants
 * rows, by fusing the to-row transition that would otherwise sit above a [[VarkaProjectExec]].
 *
 * Per batch: when `spark.sql.codegen.varka.enabled` is set, every input column the fused
 * entries reference is an [[org.apache.spark.sql.vectorized.ArrowColumnVector]] over an Arrow
 * `DateDayVector`, and the projection is Varka-eligible (since task 12: at least one entry
 * compiles), [[VarkaKernelEvaluator]] runs the fused kernel into freshly allocated Arrow
 * vectors. An all-fused projection converts that batch to rows with the standard copy
 * projection; a mixed one merges at the row instead - forwarded and residual entries are read
 * and evaluated during the same per-row pass that produces the output row, because
 * materialising them into vectors just to read them back measured slower than Janino (the
 * task 12 escape-hatch decision; see `mergeProjection` below). The kernel batch is released as
 * soon as its rows are consumed, so only one is held at a time.
 * Anything else - a non-Arrow batch, an empty batch, a kernel
 * failure - falls back to the standard per-row projection over the input batch, which is cheaper
 * here than materialising a batch just to read rows back out of it.
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

  // Task 16: verbose EXPLAIN answers "why didn't my projection fuse?" - every entry's
  // classification, and for a residual entry the reason the compiler declined it.
  override def verboseStringWithOperatorId(): String = {
    s"""
       |$formattedNodeName
       |${ExplainUtils.generateFieldString("Output", projectList)}
       |${ExplainUtils.generateFieldString("Input", child.output)}
       |${ExplainUtils.generateFieldString("Varka",
            VarkaFusionReport.lines(projectList, child.output))}
       |""".stripMargin
  }

  override def doExecute(): RDD[InternalRow] = {
    val evaluatorFactory = new VarkaColumnarToRowEvaluatorFactory(
      projectList,
      child.output,
      conf.offHeapColumnVectorEnabled,
      conf.varkaClassDumpDirectory,
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
  // must reset it in a finally block. Read by [[VarkaKernelEvaluator]], so it covers both nodes.
  @volatile private var failKernelForTesting = false

  private[sql] def setFailKernelForTesting(fail: Boolean): Unit = {
    failKernelForTesting = fail
  }

  private[sql] def isFailKernelForTesting: Boolean = failKernelForTesting
}

private[sql] class VarkaColumnarToRowEvaluatorFactory(
    projectList: Seq[NamedExpression],
    childOutput: Seq[Attribute],
    offHeapColumnVectorEnabled: Boolean,
    classDumpDirectory: Option[String],
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
    // All three projections are lazy (task 15's discipline): a task compiles only the ones its
    // batches actually take - `toRow` on the all-fused kernel path, `mergeProjection` on the
    // mixed kernel path, `fallbackProjection` on the fallback path.
    private lazy val fallbackProjection = UnsafeProjection.create(projectList, childOutput)
    private lazy val toRow = {
      val outputAttrs = projectList.map(_.toAttribute)
      UnsafeProjection.create(outputAttrs, outputAttrs)
    }

    private val kernels = new VarkaKernelEvaluator(
      projectList, childOutput, offHeapColumnVectorEnabled, operatorName = "ProjectToRow",
      classDumpDirectory)

    // Merge-at-row (task 12, 2.3): for a projection with forwarded or residual entries the
    // kernels produce only the fused columns, and this projection - over the input row joined
    // with the fused-output row - reads fused values, copies forwarded ones and evaluates
    // residual expressions in the same per-row pass that produces the output row anyway.
    // The alternative (assembling a full output batch and reading it back) materialises the
    // residual columns into vectors for nothing; the head-to-head in `PLAN_TASK_12.md` 5
    // measured it at 0.5x-0.7x Janino end to end, which this shape recovers.
    // None when every entry fuses: the fused batch is then the output and `toRow` suffices.
    private lazy val mergeProjection: Option[UnsafeProjection] = kernels.partialPlan.flatMap {
      partial =>
        if (partial.specs.forall(_.isInstanceOf[FusedOutput])) {
          None
        } else {
          val fusedAttrs = partial.fused.outputTypes.zipWithIndex.map { case (dataType, i) =>
            AttributeReference(s"_varkaFused$i", dataType)()
          }
          val merged = partial.specs.zip(projectList).map {
            case (FusedOutput(index), _) => fusedAttrs(index)
            case (ForwardedOutput(ordinal), _) => childOutput(ordinal)
            case (ResidualOutput, named) => named
          }
          Some(UnsafeProjection.create(merged, childOutput ++ fusedAttrs))
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
      if (kernels.canRun(input)) {
        try {
          val rows = runKernels(input)
          numVarkaBatches += 1
          rows
        } catch {
          case e if kernels.isCatchable(e) =>
            logWarning(s"The Varka SIMD kernels ${kernels.kernelIdentity} failed on this " +
              "batch; falling back to the per-row projection.", e)
            fallback(input)
        }
      } else {
        fallback(input)
      }
    }

    private def fallback(input: ColumnarBatch): Iterator[InternalRow] = {
      input.rowIterator().asScala.map(fallbackProjection)
    }

    private def runKernels(input: ColumnarBatch): Iterator[InternalRow] = {
      val fusedBatch = kernels.projectFused(input)
      val rows = mergeProjection match {
        case None =>
          // Every entry fused: the fused batch is the whole output.
          fusedBatch.rowIterator().asScala.map(toRow)
        case Some(merge) =>
          val inputRows = input.rowIterator()
          val fusedRows = fusedBatch.rowIterator()
          val joined = new JoinedRow
          new Iterator[InternalRow] {
            override def hasNext: Boolean = fusedRows.hasNext
            override def next(): InternalRow =
              merge(joined(inputRows.next(), fusedRows.next()))
          }
      }
      // The rows stream lazily, so the batch is released when its rows run out rather than in a
      // finally here; the kernel evaluator's task-completion listener closes it if the task stops
      // reading early.
      CompletionIterator[InternalRow, Iterator[InternalRow]](rows, kernels.release(fusedBatch))
    }
  }
}
