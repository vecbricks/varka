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
package org.apache.spark.sql.execution;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import scala.Option;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.BaseFixedWidthVector;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection$;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler$;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.SelectionVectorOps;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMemorySanitizer;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSegments;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSelectionBitmap;
import org.apache.spark.sql.catalyst.types.DataTypeUtils$;
import org.apache.spark.sql.execution.varka.VarkaBatchLedger;
import org.apache.spark.sql.execution.varka.VarkaKernelRunner;
import org.apache.spark.sql.execution.vectorized.OffHeapColumnVector;
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector;
import org.apache.spark.sql.execution.vectorized.WritableColumnVector;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.vectorized.ArrowColumnVector;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * The kernel half of the Varka filter, for one partition: it runs the mask kernel - a fused loop
 * whose output roots are the predicate's condition, usually one root and several when the
 * predicate was split - over an Arrow-backed batch and hands back the selection bitmap, leaving
 * what to do with it (compact a fresh batch, or skip rows at the row boundary) to the exec node.
 * Shares every task-lifetime mechanism with the projection evaluator through
 * {@link VarkaEvaluatorBase}.
 *
 * <p>The condition must be fully fused: the columnar rule splits a mixed predicate and keeps the
 * residual conjuncts in a row {@code FilterExec} above, so a condition with residual conjuncts
 * reaching this evaluator would mean silently dropping them - the compile is therefore accepted
 * only when every conjunct fused, and anything else makes every batch take the caller's row
 * fallback.
 */
public class VarkaFilterEvaluator extends VarkaEvaluatorBase {

  private static final SparkLogger LOG = SparkLoggerFactory.getLogger(VarkaFilterEvaluator.class);

  private final Expression condition;
  private final boolean offHeapColumnVectorEnabled;

  private boolean compiledResolved;
  private CompiledVarkaPredicate compiled;
  private int[][] clauses;

  // The selection buffer, one whole-word bitmap per kernel output, reused across batches and
  // grown on demand; released by the task-completion listener before the allocator closes. The
  // kernel writes the leading (len + 7) / 8 bytes of each bitmap itself, so a stale tail from a
  // longer earlier batch is never read - the bitmap readers stop at len bits. Two facts keep that
  // true which stopped the driver from zeroing the validity of an output its bitmap pass serves: a
  // filter's roots are conditions, which the pass never serves, so this buffer is still zeroed by
  // the driver; and every pass arm writes exactly (len + 7) / 8 bytes anyway.
  private ArrowBuf maskBuf;

  // The generic-column compaction machinery, rebuilt only when the set of generic positions
  // changes (in practice once per partition: which columns take the fixed-width Arrow copy is a
  // property of the child's batch layout, not of the row values). Building an UnsafeProjection
  // per batch would put a Janino compile on the per-batch path.
  private int[] genericPositions;
  private StructType genericSchema;
  private UnsafeProjection genericProjection;
  private RowToColumnConverter genericConverter;

  public VarkaFilterEvaluator(
      Expression condition,
      Seq<Attribute> childOutput,
      boolean offHeapColumnVectorEnabled,
      String operatorName,
      Option<String> classDumpDirectory,
      VarkaExecMetrics metrics,
      int emitUseAVX,
      boolean warmupEnabled) {
    super(childOutput, operatorName, classDumpDirectory, metrics, emitUseAVX, warmupEnabled);
    this.condition = condition;
    this.offHeapColumnVectorEnabled = offHeapColumnVectorEnabled;
  }

  /** The defaults a suite or diagnostic builds with: no class dump, no metrics, no warm-up. */
  public VarkaFilterEvaluator(
      Expression condition, Seq<Attribute> childOutput, boolean offHeapColumnVectorEnabled,
      String operatorName) {
    this(condition, childOutput, offHeapColumnVectorEnabled, operatorName, Option.empty(),
        VarkaExecMetrics.NONE, VarkaEmitOptions.USE_AVX_UNKNOWN, false);
  }

  private CompiledVarkaPredicate predicate() {
    if (!compiledResolved) {
      Option<CompiledVarkaPredicate> all = VarkaExpressionCompiler$.MODULE$
          .compilePredicate(condition, childAttributes(), emitOptions());
      if (all.isDefined() && all.get().residualConjuncts().isEmpty()) {
        compiled = all.get();
        List<List<Integer>> byClause = compiled.clauses();
        clauses = new int[byClause.size()][];
        for (int c = 0; c < clauses.length; c++) {
          List<Integer> outputs = byClause.get(c);
          clauses[c] = new int[outputs.size()];
          for (int k = 0; k < clauses[c].length; k++) {
            clauses[c][k] = outputs.get(k);
          }
        }
        // The account for a filter, once per task at debug level.
        if (LOG.isDebugEnabled()) {
          LOG.debug("Varka " + evaluatorOperator() + " fusion: "
              + String.join("; ", CollectionConverters.asJava(
                  VarkaFusionReport.predicateLines(condition, childAttributes(), emitOptions()))));
        }
      }
      compiledResolved = true;
    }
    return compiled;
  }

  @Override
  protected Option<CompiledVarkaProjection> fusedPlan() {
    CompiledVarkaPredicate p = predicate();
    return p == null ? Option.empty() : Option.apply(p.fused());
  }

  @Override
  protected Iterator<String> identityEntries() {
    return List.of(condition.toString()).iterator();
  }

  @Override
  protected void onTaskCleanup() {
    // Clear the field before closing, as the scratch release does: a throw from close() must not
    // leave a released buffer referenced for a later maskBuffer to size itself against.
    ArrowBuf buf = maskBuf;
    maskBuf = null;
    if (buf != null) {
      buf.close();
    }
  }

  private ArrowBuf maskBuffer(long needed) {
    if (maskBuf == null || maskBuf.capacity() < needed) {
      // Allocate before closing: a transient allocation failure then costs nothing at all, and
      // the evaluator keeps the buffer it had.
      // Room for the sanitizer's canary past the bitmaps, when it is on.
      long room = VarkaMemorySanitizer.ENABLED ? VarkaMemorySanitizer.CANARY_BYTES : 0;
      ArrowBuf fresh = taskAllocator().buffer(needed + room);
      ArrowBuf old = maskBuf;
      maskBuf = fresh;
      if (old != null) {
        old.close();
      }
    }
    return maskBuf;
  }

  /**
   * Runs the mask kernel over the input batch and returns its selection. Callers must have asked
   * {@link #canRun} first, must treat a throw as "this batch could not be served", and must finish
   * reading the bitmap before the next call - the buffer is task state, not batch state, which is
   * safe under the nodes' one-batch-at-a-time iteration and allocates nothing per batch.
   */
  public VarkaSelection filterMask(ColumnarBatch input) {
    int len = input.numRows();
    VarkaKernelRunner runner = fusedRunner();
    CompiledVarkaPredicate predicate = predicate();
    // The memory sanitizer's window: the bitmaps' buffer is registered below, and the mapping of
    // it after the kernel runs is checked against its real capacity.
    VarkaMemorySanitizer.begin();
    try {
      runner.fill(input, len);
      // One bitmap per output, whole words each, output 0's first. A mask output's data slot is
      // unused by contract (the emitted body never touches it); its validity slot receives its
      // selection bitmap.
      int outputs = predicate.fused().outputs().size();
      long stride = ((len + 63) / 64) * 8L;
      ArrowBuf buf = maskBuffer(stride * outputs);
      if (VarkaMemorySanitizer.ENABLED) {
        VarkaMemorySanitizer.guard("selection bitmaps", 0, buf, stride * outputs);
      }
      for (int o = 0; o < outputs; o++) {
        runner.dstData[o] = 0L;
        runner.dstWidth[o] = 0;
        runner.dstValidity[o] = buf.memoryAddress() + o * stride;
      }
      runner.invoke(len);
      MemorySegment base = VarkaSegments.map(buf.memoryAddress(), stride * outputs);
      if (outputs > 1) {
        // A split predicate (see CompiledVarkaPredicate.clauses): each clause's partial roots OR
        // into its first bitmap, and the clauses AND into output 0's, which the first clause
        // starts with.
        for (int[] clause : clauses) {
          for (int k = 1; k < clause.length; k++) {
            VarkaSelectionBitmap.orInto(
                base.asSlice(clause[0] * stride, stride), base.asSlice(clause[k] * stride, stride),
                len);
          }
        }
        for (int c = 1; c < clauses.length; c++) {
          VarkaSelectionBitmap.andInto(
              base.asSlice(0, stride), base.asSlice(clauses[c][0] * stride, stride), len);
        }
      }
      MemorySegment mask = base.asSlice(0, (len + 7) / 8);
      return new VarkaSelection(mask, VarkaSelectionBitmap.countSet(mask, len));
    } finally {
      VarkaMemorySanitizer.end();
    }
  }

  private void genericMachinery(int[] positions) {
    if (!Arrays.equals(positions, genericPositions)) {
      genericPositions = positions;
      var attrs = new ArrayList<Attribute>();
      for (int position : positions) {
        attrs.add(childAttributes().apply(position));
      }
      Seq<Attribute> attributes = CollectionConverters.asScala(attrs).toSeq();
      genericSchema = DataTypeUtils$.MODULE$.fromAttributes(attributes);
      genericProjection = UnsafeProjection$.MODULE$.create(
          expressions(attributes), childAttributes());
      genericConverter = VarkaRowToColumn.apply(genericSchema);
    }
  }

  @SuppressWarnings("unchecked")
  private static Seq<Expression> expressions(Seq<? extends Expression> items) {
    return (Seq<Expression>) (Seq<?>) items;
  }

  /**
   * Runs the mask kernel and compacts the selected rows into a fresh output batch - the columnar
   * filter's whole batch path, and the v1 selected-batch contract: the batch that leaves the
   * <em>compacting</em> path is an ordinary dense batch, so every consumer's invariants hold
   * unchanged - {@code canRun}'s valueCount-equals-numRows check included, which is what lets a
   * Varka projection stack right on top. The {@code count == len} forwarding path below makes a
   * narrower promise: the child's columns pass through exactly as they arrived, normalized only as
   * much as the child normalized them - {@code canRun} vets the plan-referenced columns per batch,
   * so an unreferenced column could in principle arrive non-Arrow or short and leave the same way.
   * Sound today because a stacked Varka node runs its own per-batch {@code canRun} and falls back
   * on what it cannot serve; it just means the forwarding path relies on the downstream gate
   * rather than on this method's output shape.
   *
   * <p>Columns whose input is a fixed-width Arrow vector with exactly the batch's rows compact by a
   * typed scalar copy into a fresh Arrow vector of the same type (keeping them kernel-servable
   * upstream of the next Varka node), the 4-byte ones by the {@code compress(mask)} lane move;
   * every other column goes through one per-row pass with the standard row-to-column converter,
   * the same machinery as the projection residuals.
   *
   * <p>Two selectivity extremes skip that work entirely. At {@code count == len} nothing has to be
   * shortened, and a vector that does not have to be shortened does not have to be copied, so the
   * child's columns are forwarded: the earlier rule that a compacting filter owns every output
   * column holds only where the compaction is real. At {@code count == 0} the per-row scans are
   * skipped - they are O(len) either way today, which is why the 0%-selected rung is the weakest of
   * the committed ladder.
   */
  public ColumnarBatch filterCompact(ColumnarBatch input) {
    VarkaSelection selection = filterMask(input);
    int len = input.numRows();
    int count = selection.count();
    int width = childAttributes().size();
    if (count == len) {
      // Every row survives: forward the child's columns rather than copy them. The ownership
      // contract carries this by itself - trackOwned is told the batch owns nothing, so release()
      // leaves each vector to the input batch it came from, exactly as the projection node's
      // forwarding does. Sound under the one-batch-at-a-time discipline both filter nodes run:
      // the output batch is released before the next input is requested.
      var forwarded = new ColumnVector[width];
      for (int j = 0; j < width; j++) {
        forwarded[j] = input.column(j);
      }
      var batch = new ColumnarBatch(forwarded);
      batch.setNumRows(count);
      trackOwned(batch, VarkaBatchLedger.NO_VECTORS);
      return batch;
    }
    var owned = new ArrayList<ColumnVector>();
    try {
      var columns = new ColumnVector[width];
      var generic = new int[width];
      int genericCount = 0;
      for (int j = 0; j < width; j++) {
        if (input.column(j) instanceof ArrowColumnVector acv
            && acv.getValueVector() instanceof BaseFixedWidthVector src
            && src.getValueCount() == len) {
          // One arm serves every fixed-width Arrow type: getTransferPair conjures an empty vector
          // of the source's own type, and copyFromSafe copies value and validity together.
          var dst = (BaseFixedWidthVector) src.getTransferPair("varka" + j, taskAllocator())
              .getTo();
          columns[j] = src.getTypeWidth() == 4
              ? compactInt32(dst, src, selection, len, count, owned)
              : compactFixed(dst, src, selection, len, count, owned);
        } else {
          generic[genericCount++] = j;
        }
      }
      if (genericCount > 0) {
        compactGeneric(input, columns, Arrays.copyOf(generic, genericCount), selection, count,
            owned);
      }
      var batch = new ColumnarBatch(columns);
      batch.setNumRows(count);
      trackOwned(batch, owned.toArray(new ColumnVector[0]));
      return batch;
    } catch (Throwable e) {
      closeAllQuietly(owned, "a Varka output vector after a failed projection");
      throw e;
    }
  }

  /** The columns with no typed copy, through one per-row pass over the selected rows. */
  private void compactGeneric(ColumnarBatch input, ColumnVector[] columns, int[] positions,
      VarkaSelection selection, int count, List<ColumnVector> owned) {
    genericMachinery(positions);
    int rows = Math.max(count, 1);
    WritableColumnVector[] vectors = offHeapColumnVectorEnabled
        ? OffHeapColumnVector.allocateColumns(rows, genericSchema)
        : OnHeapColumnVector.allocateColumns(rows, genericSchema);
    owned.addAll(Arrays.asList(vectors));
    if (count > 0) {
      Iterator<InternalRow> source = input.rowIterator();
      int i = 0;
      while (source.hasNext()) {
        InternalRow row = source.next();
        if (VarkaSelectionBitmap.isSet(selection.mask(), i)) {
          genericConverter.convert(genericProjection.apply(row), vectors);
        }
        i++;
      }
    }
    for (int k = 0; k < positions.length; k++) {
      columns[positions[k]] = vectors[k];
    }
  }

  /**
   * The {@code compress(mask)} compaction for 4-byte fixed-width Arrow vectors - date32 and int32,
   * every width Varka produces today. Width 8 would arrive with a new lane type and everything
   * else keeps the per-row typed copy below. It is a width check rather than a type check on
   * purpose: a future Arrow type of the right width is served correctly by a bit-for-bit lane move.
   *
   * <p>The destination is allocated with one whole lane group of slack past {@code count} so that
   * {@code SelectionVectorOps.compactInts} can store unmasked - a masked store costs 2.3x-2.9x and
   * this would otherwise pay one per lane group - and {@code setValueCount(count)} afterwards is
   * what makes the slack invisible to every consumer.
   */
  private ColumnVector compactInt32(BaseFixedWidthVector dst, BaseFixedWidthVector src,
      VarkaSelection selection, int len, int count, List<ColumnVector> owned) {
    try {
      dst.allocateNew(count + SelectionVectorOps.intLanes());
    } catch (Throwable e) {
      closeQuietly(dst, "a Varka compaction vector after a failed allocation");
      throw e;
    }
    var wrapped = new VarkaOwnedArrowColumnVector(dst);
    owned.add(wrapped);
    if (count > 0) {
      boolean hasNulls = src.getNullCount() > 0;
      // The memory sanitizer's window for the compaction, over its own four buffers.
      VarkaMemorySanitizer.begin();
      try {
        if (VarkaMemorySanitizer.ENABLED) {
          VarkaMemorySanitizer.register("compaction source data", 0, src.getDataBuffer());
          if (hasNulls) {
            VarkaMemorySanitizer.register("compaction source validity", 0, src.getValidityBuffer());
          }
          VarkaMemorySanitizer.register("compaction output data", 0, dst.getDataBuffer());
          VarkaMemorySanitizer.register("compaction output validity", 0, dst.getValidityBuffer());
        }
        SelectionVectorOps.compactInts(
            src.getDataBuffer().memoryAddress(),
            hasNulls ? src.getValidityBuffer().memoryAddress() : 0L,
            hasNulls,
            selection.mask(),
            len,
            count,
            dst.getDataBuffer().memoryAddress(),
            dst.getDataBuffer().capacity(),
            dst.getValidityBuffer().memoryAddress(),
            dst.getValidityBuffer().capacity());
      } finally {
        VarkaMemorySanitizer.end();
      }
    }
    dst.setValueCount(count);
    return wrapped;
  }

  /**
   * Allocates {@code dst} for {@code count} rows, copies the selected rows from {@code src}, and
   * wraps it; the vector joins {@code owned} as soon as it can leak.
   */
  private ColumnVector compactFixed(BaseFixedWidthVector dst, BaseFixedWidthVector src,
      VarkaSelection selection, int len, int count, List<ColumnVector> owned) {
    try {
      dst.allocateNew(Math.max(count, 1));
    } catch (Throwable e) {
      closeQuietly(dst, "a Varka compaction vector after a failed allocation");
      throw e;
    }
    var wrapped = new VarkaOwnedArrowColumnVector(dst);
    owned.add(wrapped);
    // Nothing selected means nothing to scan for: the bitmap walk below is O(len) whatever it
    // finds, and at 0% selectivity that walk was the whole cost of the column.
    if (count > 0) {
      int pos = 0;
      for (int i = 0; i < len; i++) {
        if (VarkaSelectionBitmap.isSet(selection.mask(), i)) {
          dst.copyFromSafe(i, pos, src);
          pos++;
        }
      }
    }
    dst.setValueCount(count);
    return wrapped;
  }
}
