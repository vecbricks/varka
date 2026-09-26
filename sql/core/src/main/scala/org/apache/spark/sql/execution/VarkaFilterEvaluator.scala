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

import scala.collection.mutable

import org.apache.arrow.memory.{ArrowBuf}
import org.apache.arrow.vector.{BaseFixedWidthVector}

import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection,
  VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{SelectionVectorOps,
  VarkaEmitOptions, VarkaSelectionBitmap}
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.vectorized.{OffHeapColumnVector, OnHeapColumnVector, WritableColumnVector}
import org.apache.spark.sql.types.{StructType}
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}

/**
 * One batch's selection: the bitmap the filter kernel wrote - valid until the
 * evaluator's next [[VarkaFilterEvaluator.filterMask]] call, since the buffer is reused - and
 * the number of selected rows. Read through `VarkaSelectionBitmap`.
 */
private[sql] case class VarkaSelection(mask: MemorySegment, count: Int)

/**
 * The kernel half of the Varka filter, for one partition: it runs the mask kernel -
 * a fused loop whose output roots are the predicate's condition, usually one root and several
 * when the predicate was split - over an Arrow-backed batch and hands back the selection
 * bitmap, leaving what to do with it (compact a fresh batch, or skip rows at the row boundary)
 * to the exec node. Shares every task-lifetime mechanism with the projection evaluator through
 * [[VarkaEvaluatorBase]].
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
    metrics: VarkaExecMetrics = VarkaExecMetrics(),
    emitUseAVX: Int = VarkaEmitOptions.USE_AVX_UNKNOWN,
    warmupEnabled: Boolean = false)
    extends VarkaEvaluatorBase(childOutput, operatorName, classDumpDirectory, metrics,
      emitUseAVX, warmupEnabled) {

  private lazy val compiled = {
    val predicate = VarkaExpressionCompiler.compilePredicate(condition, childOutput, emitOptions)
      .filter(_.residualConjuncts.isEmpty)
    // The account for a filter, once per task at debug level - the projection
    // evaluator's counterpart, which the base-class split had dropped (task-21 review,
    // second pass) although the docs promise it.
    predicate.foreach { _ =>
      logDebug(s"Varka $operatorName fusion: " +
        VarkaFusionReport.predicateLines(condition, childOutput, emitOptions).mkString("; "))
    }
    predicate
  }

  override protected def fusedPlan: Option[CompiledVarkaProjection] = compiled.map(_.fused)

  override protected def identityEntries: Iterator[String] = Iterator(condition.toString)

  // The selection buffer, one whole-word bitmap per kernel output, reused across batches and
  // grown on demand; released by the task-completion listener before the allocator closes.
  // The kernel writes the leading (len + 7) / 8 bytes of each bitmap itself, so a stale tail
  // from a longer earlier batch is never read - the bitmap readers stop at `len` bits. Two facts
  // keep that true which stopped the driver from zeroing the validity of an output its bitmap
  // pass serves: a filter's roots are `Cond`s, which the pass never serves, so this buffer is
  // still zeroed by the driver; and every pass arm writes exactly (len + 7) / 8 bytes anyway.
  private var maskBuf: ArrowBuf = null

  override protected def onTaskCleanup(): Unit = {
    // Clear the field before closing, as `closeScratch` does: a throw from `close()` must not
    // leave a released buffer referenced for a later `maskBuffer` to size itself against.
    val buf = maskBuf
    maskBuf = null
    if (buf != null) {
      buf.close()
    }
  }

  private def maskBuffer(needed: Long): ArrowBuf = {
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
    // One bitmap per output, whole words each, output 0's first. A mask output's data slot is
    // unused by contract (the emitted body never touches it); its validity slot receives its
    // selection bitmap.
    val predicate = compiled.get
    val outputs = predicate.fused.outputs.size
    val stride = ((len + 63) / 64) * 8L
    val buf = maskBuffer(stride * outputs)
    for (o <- 0 until outputs) {
      runner.dstData(o) = 0L
      runner.dstValidity(o) = buf.memoryAddress() + o * stride
    }
    invokeFused(runner, len)
    val base = MemorySegment.ofAddress(buf.memoryAddress()).reinterpret(stride * outputs)
    if (outputs > 1) {
      // A split predicate (see `CompiledVarkaPredicate.clauses`): each clause's partial roots
      // OR into its first bitmap, and the clauses AND into output 0's, which the first clause
      // starts with.
      def bitmap(o: Int): MemorySegment = base.asSlice(o * stride, stride)
      predicate.clauses.foreach { clause =>
        clause.tail.foreach(o => VarkaSelectionBitmap.orInto(bitmap(clause.head), bitmap(o), len))
      }
      predicate.clauses.tail.foreach { clause =>
        VarkaSelectionBitmap.andInto(bitmap(0), bitmap(clause.head), len)
      }
    }
    val mask = base.asSlice(0, (len + 7) / 8)
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
   * Two selectivity extremes skip that work entirely. At `count == len` nothing has
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
   * The `compress(mask)` compaction (see milestone 4 item 11) for 4-byte fixed-width
   * Arrow vectors - date32 and int32, every width Varka produces today. Width 8 would arrive with a
   * new lane type and everything else keeps the per-row typed copy below. It is a width
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
    // finds, and at 0% selectivity that walk was the whole cost of the column.
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
