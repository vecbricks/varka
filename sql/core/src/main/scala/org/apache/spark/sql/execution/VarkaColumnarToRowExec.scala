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
import org.apache.spark.sql.catalyst.expressions.codegen.{ForwardedOutput, FusedOutput, ResidualOutput, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.CompletionIterator

/**
 * A Varka node wearing the [[ColumnarToRowTransition]] tag while carrying fused work inside it
 * (task-21 review, second pass): every consumer that strips the tag to reach a columnar plan -
 * the cache builder's `convertToColumnarPlanIfPossible` is the one in-tree - must convert such
 * a node to [[columnarSibling]], the columnar-out node running identical kernels, instead of
 * dropping the work. The serializer handles this trait, not a hardcoded node list, so the next
 * fused transition node is kept out of the task-6 cached-view bug by the compiler: extending
 * the trait forces the sibling.
 */
private[sql] trait VarkaFusedTransition extends ColumnarToRowTransition {

  /** The columnar-out node computing exactly what this fused transition computes. */
  def columnarSibling: SparkPlan
}

/**
 * The Varka columnar-to-row transition (Task 6). It projects an Arrow-backed `ColumnarBatch`
 * with the Varka SIMD kernels instead of per-row codegen, and hands the result on as rows: it is
 * what [[VarkaColumnarRule]] leaves in the plan when the consumer above the projection wants
 * rows, by fusing the to-row transition that would otherwise sit above a [[VarkaProjectExec]].
 *
 * Per batch: when `spark.sql.codegen.varka.enabled` is set, every input column the fused
 * entries reference is an [[org.apache.spark.sql.vectorized.ArrowColumnVector]] over an Arrow
 * `DateDayVector` (or, for a day-offset column since task 38, an `IntVector`), and the
 * projection is Varka-eligible (since task 12: at least one entry compiles),
 * [[VarkaKernelEvaluator]] runs the fused kernel into freshly allocated Arrow
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
 * '''The transition tag carries a caveat.''' This node extends [[ColumnarToRowTransition]] so
 * the transition-insertion pass (and its AQE re-runs) treats it as the to-row boundary it is -
 * but unlike the stock transition it is NOT semantics-free: it carries the whole fused
 * projection. Machinery that strips a topmost transition to reach the columnar plan
 * underneath must convert this node to its columnar sibling ([[VarkaProjectExec]], identical
 * kernels) instead of dropping it - `ArrowCachedBatchSerializer.convertToColumnarPlanIfPossible`
 * does exactly that, after task 21 found the default strip silently discarding the fused work
 * on the cache-population path.
 *
 * The engine module (`varka-engine`) is deliberately kept off the main compile classpath: only
 * its kernel descriptors (strings) and Arrow classes are referenced here. The engine jar is a
 * test-scoped dependency; at runtime it is deployed externally (`--jars`) and its absence only
 * degrades the kernel path to the per-row fallback.
 */
case class VarkaColumnarToRowExec(
    projectList: Seq[NamedExpression],
    child: SparkPlan)
    extends VarkaFusedTransition
    with SafeForKWayMerge
    with PartitioningPreservingUnaryExecNode
    with OrderPreservingUnaryExecNode {

  override def output: Seq[Attribute] = projectList.map(_.toAttribute)

  override def columnarSibling: SparkPlan = VarkaProjectExec(projectList, child)

  // This node is a projection: it renames and drops columns, so it cannot report the child's
  // partitioning and ordering verbatim the way `ColumnarToRowExec` does. The two alias-aware
  // traits above map them through the projection's alias mapping and drop whatever is no longer
  // in `output`, which is what the `ProjectExec` fused away here would have done.
  override protected def outputExpressions: Seq[NamedExpression] = projectList

  override protected def orderingExpressions: Seq[SortOrder] = child.outputOrdering

  override protected def withNewChildInternal(newChild: SparkPlan): VarkaColumnarToRowExec = {
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

  override def doExecute(): RDD[InternalRow] = {
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
    val evaluatorFactory = new VarkaColumnarToRowEvaluatorFactory(
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

  // Test-only hook that makes every kernel invocation decline its batch (task 26), as a kernel
  // whose lowering met an out-of-range value does. The shipped calendar lowering is the
  // narrowed one, so a real out-of-range date reaches this path too and the differential
  // drives it that way; the hook exists to reach it without depending on any one expression's
  // range, and to make a whole-query fallback cheap to assert. Same discipline as the hook
  // above - static, because Spark runs tasks on other threads, and reset in a finally block.
  @volatile private var declineKernelForTesting = false

  private[sql] def setDeclineKernelForTesting(decline: Boolean): Unit = {
    declineKernelForTesting = decline
  }

  private[sql] def isDeclineKernelForTesting: Boolean = declineKernelForTesting

  // Test-only hook that fails the kernel-class lookup, simulating an emission failure, so every
  // batch takes the fallback path with `numEmissionFailures` counted once. Same discipline as
  // the one above - static because Spark runs tasks on other threads, reset in a finally block.
  //
  // It exists because task 23 removed the emitter's static test hooks, and with them the shape
  // cache's JVM-wide refusal to serve any lookup while one was set. Two suites used that refusal
  // as a fault injector: they set a hook and relied on the cache throwing. Emit options ride the
  // key now, so such an emission succeeds - correctly - and the injection has to happen at the
  // seam that actually produces an emission failure, which is where the evaluator resolves the
  // class. Injecting here rather than through a new SQLConf entry keeps this out of the
  // production configuration surface, and puts it beside the fault injector this file already
  // owns. Read by [[VarkaKernelEvaluator]], so it covers every Varka exec node.
  @volatile private var failEmissionForTesting = false

  private[sql] def setFailEmissionForTesting(fail: Boolean): Unit = {
    failEmissionForTesting = fail
  }

  private[sql] def isFailEmissionForTesting: Boolean = failEmissionForTesting

  // Test-only hook that emits every kernel with these options instead of the defaults, so an
  // end-to-end suite can drive a reference variant - task 52's guard-off bytes, whose only
  // observable difference is a metric - through the real evaluator. The options ride the shape
  // key (task 23), so a variant is cached under its own identity and nothing has to be flushed
  // when the hook is reset. Same discipline as the three above: static because Spark runs
  // tasks on other threads, reset in a finally block, and here rather than a SQLConf entry so
  // the production configuration surface stays free of emitter knobs.
  @volatile private var emitOptionsForTesting: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS

  private[sql] def setEmitOptionsForTesting(options: VarkaEmitOptions): Unit = {
    emitOptionsForTesting = options
  }

  private[sql] def currentEmitOptions: VarkaEmitOptions = emitOptionsForTesting
}

private[sql] class VarkaColumnarToRowEvaluatorFactory(
    projectList: Seq[NamedExpression],
    childOutput: Seq[Attribute],
    offHeapColumnVectorEnabled: Boolean,
    classDumpDirectory: Option[String],
    numOutputRows: SQLMetric,
    numInputBatches: SQLMetric,
    varkaMetrics: VarkaExecMetrics)
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
      classDumpDirectory, varkaMetrics)

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

    // The evaluator's serveBatch runs the shared per-batch dispatch and cause accounting
    // (task-21 review, both passes) and routes every degradation to the fallback.
    private def process(input: ColumnarBatch): Iterator[InternalRow] = {
      kernels.serveBatch(input) {
        val rows = runKernels(input)
        varkaMetrics.varkaBatches.foreach(_ += 1)
        rows
      } {
        fallback(input)
      }
    }

    private def fallback(input: ColumnarBatch): Iterator[InternalRow] = {
      input.rowIterator().asScala.map(fallbackProjection)
    }

    private def runKernels(input: ColumnarBatch): Iterator[InternalRow] = {
      val fusedBatch = kernels.projectFused(input)
      // `projectFused` has already registered the batch, and everything between here and the
      // CompletionIterator below can still throw: `mergeProjection` and `toRow` are lazy vals
      // whose initialisers are Janino compiles. Without this the batch would stay registered
      // until the task ended - and because a lazy val that throws re-runs its initialiser, the
      // next batch would fail the same way and retain its own, so the retention grew with the
      // partition instead of being bounded. The failure itself is handled upstream, where
      // `serveBatch` counts it and falls back to the row path.
      val rows = try {
        mergeProjection match {
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
      } catch {
        case e: Throwable =>
          kernels.release(fusedBatch)
          throw e
      }
      // The rows stream lazily, so the batch is released when its rows run out rather than in a
      // finally here; the kernel evaluator's task-completion listener closes it if the task stops
      // reading early.
      CompletionIterator[InternalRow, Iterator[InternalRow]](rows, kernels.release(fusedBatch))
    }
  }
}
