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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import scala.Option;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.IntervalYearVector;
import org.apache.arrow.vector.ValueVector;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection$;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler$;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.ForwardedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.FusedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.KernelOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.ResidualOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.types.DataTypeUtils$;
import org.apache.spark.sql.execution.vectorized.OffHeapColumnVector;
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector;
import org.apache.spark.sql.execution.vectorized.WritableColumnVector;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.sql.types.YearMonthIntervalType;
import org.apache.spark.sql.util.ArrowUtils;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * The kernel half of the Varka projection, for one partition: it turns an input
 * {@code ColumnarBatch} into a batch of the projection's output, and owns everything that costs a
 * task to set up - the compiled IR, the fused-loop kernel instance, the Arrow allocator and the
 * batches handed out.
 *
 * <p>The compute is one fused kernel emitted by {@code VarkaLoopEmitter} for the whole projection
 * - every output computed in a single pass with intermediates in vector registers. The projection
 * is compiled to IR by {@code VarkaExpressionCompiler}, the same call {@code VarkaColumnarRule}
 * decided eligibility with, so the plan the rule fused is by construction a plan this evaluator
 * serves. The emitted <em>class</em> is not per-task state: it comes from
 * {@code VarkaShapeCache}, the JVM-wide cache keyed on the kernel's structural shape, so tasks (and
 * sessions) computing the same shape share one loaded class and skip its per-task JIT warm-up. Only
 * the kernel <em>instance</em> and its argument arrays stay per-task.
 *
 * <p>Eligibility is partial and the output batch is assembled column by column in projection
 * order: fused entries come from the kernel's freshly allocated Arrow vectors, bare-column entries
 * are <b>forwarded</b> - the output batch references {@code input.column(ordinal)} itself, zero
 * copy - and the remaining (<b>residual</b>) entries are evaluated in one per-row pass over the
 * input into writable vectors.
 *
 * <p><b>Ownership.</b> The evaluator owns the vectors it allocated - kernel outputs and residual
 * columns - and never the forwarded ones, which belong to whoever owns the input batch. Every
 * release path (the caller's {@link #release}, and the task-completion listener that drains
 * abandoned batches) closes exactly the owned vectors of a batch and never calls
 * {@code ColumnarBatch.close()}, which would close every column unconditionally, forwarded ones
 * included. This follows Spark's own two-tier convention ({@code closeIfFreeable} and its no-op
 * overrides) rather than a wrapper class: the borrowed vector simply stays off the owned list.
 *
 * <p><b>Ordering contract.</b> Forwarded vectors make the output batch valid only as long as its
 * input batch: both exec nodes therefore release the output batch <em>before</em> requesting the
 * next input batch from the child, so a forwarded vector can never outlive its input. The nodes'
 * iterators already obeyed this order for memory reasons; with forwarding it is load-bearing for
 * correctness.
 *
 * <p><b>Telemetry.</b> The emitted class is named by its shape ({@code
 * VarkaFusedProjection_<hash>}, {@code SourceFile} to match), and its {@code VarkaDebugInfo}
 * attribute and {@code LineNumberTable} describe the shape - the vector IR, the line-to-node map -
 * because the bytes are shared and must not replay one query's identity for another. The
 * per-execution identity (operator, stage, this projection's expression list) is recorded in {@code
 * VarkaShapeCache}'s side table on every lookup, keyed by the shape hash, and every fallback this
 * class logs names both halves ({@link #kernelIdentity}: the shape name, the IR, and the operator
 * and stage). The bytes are kept behind {@link #emittedClassBytes} so diagnostics read the
 * attributes off exactly what ran, and {@code spark.sql.codegen.varka.classDumpDirectory} writes
 * them to disk under the {@code SourceFile} name, so {@code javap} reaches a generated loop with no
 * debugger attached.
 *
 * <p>One instance per partition, created inside the task: it registers a task-completion listener
 * on first use, and its state must not be shared across partitions.
 */
public class VarkaKernelEvaluator extends VarkaEvaluatorBase {

  private static final SparkLogger LOG = SparkLoggerFactory.getLogger(VarkaKernelEvaluator.class);

  private static final ColumnVector[] NO_COLUMNS = new ColumnVector[0];

  /** Where each output column of a projection comes from. */
  private enum Source { FUSED, KERNEL, FORWARDED, RESIDUAL }

  private final Seq<NamedExpression> projectList;
  private final boolean offHeapColumnVectorEnabled;

  // Made once, so that no batch allocates the functional object that hands the kernel its outputs.
  private final VectorAllocator vectorAllocator = this::allocateVector;

  // The projection classified entry by entry and its fused sub-projection compiled to vector IR;
  // None when no entry is Varka-eligible (which the columnar rule rules out, but a suite may ask).
  // Marked resolved only once the compile has returned, so a throw is met again by the next call.
  private boolean compiledResolved;
  private Option<PartialVarkaProjection> compiled;

  // The further kernels of a projection several kernels serve ({@code
  // VarkaEmitOptions.severalKernels}, {@code VARKA-190.md} 11), each an evaluator of its own for
  // its runner, warm-up and scratch. This evaluator runs the first kernel and asks every one of
  // them before a batch takes the kernels. Empty for a projection one kernel serves.
  private VarkaKernelPart[] parts;

  // The layout of an output batch, read once from the classification and then walked per batch:
  // for each projection position where its column comes from, and the index it has there.
  private Source[] sources;
  private int[] kernelOf;
  private int[] indexOf;
  // The entries each further kernel computes and the residual entries, from the same pass, for the
  // parts' identities and the residual projection; dropped once those are built.
  private List<List<NamedExpression>> kernelEntries;
  private List<NamedExpression> residualEntries;

  // Per-batch scratch reused across batches: the vectors a batch allocates, closed if it fails and
  // handed to the batch's tracking once it succeeds, and each kernel's columns.
  private final ArrayList<ColumnVector> owned = new ArrayList<>();
  private ColumnVector[][] fusedColumns;

  // The residual entries and their per-row machinery. A kernel-only projection has none, and even a
  // mixed one pays the Janino compile only when the first batch actually reaches projectResiduals.
  private boolean residualResolved;
  private int residualCount;
  private StructType residualSchema;
  private UnsafeProjection residualProjection;
  private RowToColumnConverter residualConverter;

  /**
   * @param operatorName the exec node this evaluator serves, for the telemetry names above
   * @param classDumpDirectory where to write each emitted class, or None to write none
   * @param metrics the exec node's Varka metric set; every field is optional, and suites or
   *                diagnostics that construct the evaluator directly pass {@code NONE}
   */
  public VarkaKernelEvaluator(
      Seq<NamedExpression> projectList,
      Seq<Attribute> childOutput,
      boolean offHeapColumnVectorEnabled,
      String operatorName,
      Option<String> classDumpDirectory,
      VarkaExecMetrics metrics,
      int emitUseAVX,
      boolean warmupEnabled) {
    super(childOutput, operatorName, classDumpDirectory, metrics, emitUseAVX, warmupEnabled);
    this.projectList = projectList;
    this.offHeapColumnVectorEnabled = offHeapColumnVectorEnabled;
  }

  /** The defaults a suite or diagnostic builds with: no class dump, no metrics, no warm-up. */
  public VarkaKernelEvaluator(
      Seq<NamedExpression> projectList,
      Seq<Attribute> childOutput,
      boolean offHeapColumnVectorEnabled,
      String operatorName) {
    this(projectList, childOutput, offHeapColumnVectorEnabled, operatorName, Option.empty(),
        VarkaExecMetrics.NONE, VarkaEmitOptions.USE_AVX_UNKNOWN, false);
  }

  private Option<PartialVarkaProjection> compiled() {
    if (!compiledResolved) {
      Option<PartialVarkaProjection> partial = VarkaExpressionCompiler$.MODULE$
          .compilePartial(projectList, childAttributes(), emitOptions());
      // The same per-entry account verbose EXPLAIN prints, once per task at debug level.
      if (partial.isDefined() && LOG.isDebugEnabled()) {
        LOG.debug("Varka " + evaluatorOperator() + " fusion: "
            + String.join("; ", CollectionConverters.asJava(
                VarkaFusionReport.lines(partial.get(), projectList, childAttributes()))));
      }
      compiled = partial;
      compiledResolved = true;
    }
    return compiled;
  }

  @Override
  protected Option<CompiledVarkaProjection> fusedPlan() {
    Option<PartialVarkaProjection> partial = compiled();
    return partial.isDefined() ? Option.apply(partial.get().fused()) : Option.empty();
  }

  @Override
  protected java.util.Iterator<String> identityEntries() {
    return render(projectList);
  }

  /** The classified projection, for the row node's merge-at-row read-back. */
  Option<PartialVarkaProjection> partialPlan() {
    return compiled();
  }

  // ---- the further kernels -------------------------------------------------------------------

  private VarkaKernelPart[] parts() {
    if (parts == null) {
      if (compiled().isEmpty()) {
        parts = new VarkaKernelPart[0];
      } else {
        layout();
        List<CompiledVarkaProjection> more = compiled().get().more();
        var built = new VarkaKernelPart[more.size()];
        for (int k = 0; k < built.length; k++) {
          // Its identity renders the entries it computes, not the projection's first ones.
          built[k] = kernelPart(more.get(k),
              CollectionConverters.asScala(kernelEntries.get(k)).toSeq());
        }
        parts = built;
      }
    }
    return parts;
  }

  // A further kernel allocates from this evaluator's allocator and has no listener of its own, so
  // its scratch is released here, before the base's cleanup closes that allocator.
  @Override
  protected void onTaskCleanup() {
    if (parts != null) {
      for (VarkaKernelPart part : parts) {
        part.releaseTaskScratch();
      }
    }
  }

  /** Every kernel can serve the batch: the first one, as ever, and each further one. */
  @Override
  public boolean canRun(ColumnarBatch input) {
    if (!super.canRun(input)) {
      return false;
    }
    for (VarkaKernelPart part : parts()) {
      if (!part.canRun(input)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Every kernel is ready. Each one is asked, so each claims its own warm-up on the batch that
   * finds it cold, and the batch takes the kernels only once all of them are compiled. A further
   * kernel names itself in a decline or failure it raises ({@link VarkaKernelPart}).
   */
  @Override
  boolean kernelReady(ColumnarBatch input) {
    boolean first = super.kernelReady(input);
    boolean all = true;
    for (VarkaKernelPart part : parts()) {
      all &= part.kernelReady(input);
    }
    return all && first;
  }

  @Override
  boolean emissionFailed() {
    if (super.emissionFailed()) {
      return true;
    }
    for (VarkaKernelPart part : parts()) {
      if (part.emissionFailed()) {
        return true;
      }
    }
    return false;
  }

  // ---- the batch -----------------------------------------------------------------------------

  /**
   * Runs the fused kernel over the input batch, evaluates the residual entries per row, forwards
   * the bare-column entries, and returns the assembled output batch, tracked here until the caller
   * {@linkplain #release releases} it. Callers must have asked {@link #canRun} first, and must
   * treat a throw as "this batch could not be served": nothing is left allocated by a failed call.
   */
  public ColumnarBatch project(ColumnarBatch input) {
    layout();
    int len = input.numRows();
    // Everything allocated for this batch - kernel outputs, then residual columns - is closed on
    // any failure here, and by release() or the listener once the batch is handed out. Forwarded
    // input vectors never join this list: they stay owned by the input batch.
    owned.clear();
    try {
      computeFused(input, len);
      ColumnVector[] residualColumns = projectResiduals(input, len);
      for (ColumnVector column : residualColumns) {
        owned.add(column);
      }
      var columns = new ColumnVector[sources.length];
      int residual = 0;
      for (int i = 0; i < columns.length; i++) {
        columns[i] = switch (sources[i]) {
          case FUSED -> fusedColumns[0][indexOf[i]];
          case KERNEL -> fusedColumns[kernelOf[i]][indexOf[i]];
          case FORWARDED -> input.column(indexOf[i]);
          case RESIDUAL -> residualColumns[residual++];
        };
      }
      return handOut(columns, len);
    } catch (Throwable e) {
      closeAllQuietly(owned, "a Varka output vector after a failed projection");
      throw e;
    } finally {
      owned.clear();
    }
  }

  /** The output batch over {@code columns}, tracked with the vectors this batch allocated. */
  private ColumnarBatch handOut(ColumnVector[] columns, int len) {
    var batch = new ColumnarBatch(columns);
    batch.setNumRows(len);
    trackOwned(batch, owned.toArray(new ColumnVector[owned.size()]));
    return batch;
  }

  /**
   * Runs only the fused kernels and returns a batch of just their columns, tracked like
   * {@link #project}'s. This is the row node's entry point (merge-at-row, {@code VARKA-12.md} 2.3):
   * it reads fused values from this batch and evaluates residual entries during its own row pass,
   * so materialising them into vectors here would be pure waste. Nothing in it is borrowed - fused
   * columns are always freshly allocated.
   */
  public ColumnarBatch projectFused(ColumnarBatch input) {
    int len = input.numRows();
    owned.clear();
    try {
      computeFused(input, len);
      int total = 0;
      for (ColumnVector[] kernelColumns : fusedColumns) {
        total += kernelColumns.length;
      }
      var all = new ColumnVector[total];
      int at = 0;
      for (ColumnVector[] kernelColumns : fusedColumns) {
        System.arraycopy(kernelColumns, 0, all, at, kernelColumns.length);
        at += kernelColumns.length;
      }
      return handOut(all, len);
    } catch (Throwable e) {
      closeAllQuietly(owned, "a Varka output vector after a failed projection");
      throw e;
    } finally {
      owned.clear();
    }
  }

  /**
   * Reads the classification once: into the arrays {@link #project} walks per batch, and into the
   * entries each further kernel computes and the residual entries.
   */
  private void layout() {
    if (sources == null) {
      PartialVarkaProjection partial = compiled().get();
      List<VarkaOutputSpec> specs = partial.specs();
      List<NamedExpression> named = new ArrayList<>(CollectionConverters.asJava(projectList));
      var kinds = new Source[specs.size()];
      var kernels = new int[specs.size()];
      var indexes = new int[specs.size()];
      var byKernel = new ArrayList<List<NamedExpression>>();
      for (int k = 0; k < partial.more().size(); k++) {
        byKernel.add(new ArrayList<>());
      }
      var residual = new ArrayList<NamedExpression>();
      for (int i = 0; i < kinds.length; i++) {
        switch (specs.get(i)) {
          case FusedOutput fused -> {
            kinds[i] = Source.FUSED;
            indexes[i] = fused.fusedIndex();
          }
          case KernelOutput kernel -> {
            kinds[i] = Source.KERNEL;
            kernels[i] = kernel.kernel();
            indexes[i] = kernel.fusedIndex();
            byKernel.get(kernel.kernel() - 1).add(named.get(i));
          }
          case ForwardedOutput forwarded -> {
            kinds[i] = Source.FORWARDED;
            indexes[i] = forwarded.childOrdinal();
          }
          case ResidualOutput r -> {
            kinds[i] = Source.RESIDUAL;
            residual.add(named.get(i));
          }
        }
      }
      kernelOf = kernels;
      indexOf = indexes;
      kernelEntries = byKernel;
      residualEntries = residual;
      sources = kinds;
    }
  }

  /**
   * Runs every kernel over the input batch, in turn, into freshly allocated Arrow vectors from this
   * task's one allocator, appending them to {@code owned} as they are created (the caller closes
   * {@code owned} on failure), and leaves each kernel's columns by its fused index in
   * {@link #fusedColumns}, the first kernel's first. A kernel that declines the batch throws, and
   * the whole batch falls back, as it does with one kernel: the kernels are one projection,
   * answered whole or not at all.
   */
  private void computeFused(ColumnarBatch input, int len) {
    BufferAllocator allocator = taskAllocator();
    VarkaKernelPart[] kernelParts = parts();
    if (fusedColumns == null) {
      fusedColumns = new ColumnVector[kernelParts.length + 1][];
    }
    fusedColumns[0] = runKernel(input, len, owned, allocator, vectorAllocator);
    for (int k = 0; k < kernelParts.length; k++) {
      fusedColumns[k + 1] = kernelParts[k].runKernel(input, len, owned, allocator, vectorAllocator);
    }
  }

  // ---- the residual entries ------------------------------------------------------------------

  private void resolveResidual() {
    if (!residualResolved) {
      layout();
      residualCount = residualEntries.size();
      if (residualCount > 0) {
        var expressions = new ArrayList<Expression>();
        var attributes = new ArrayList<Attribute>();
        for (NamedExpression entry : residualEntries) {
          expressions.add((Expression) entry);
          attributes.add(entry.toAttribute());
        }
        residualSchema = DataTypeUtils$.MODULE$.fromAttributes(
            CollectionConverters.asScala(attributes).toSeq());
        residualProjection = UnsafeProjection$.MODULE$.create(
            CollectionConverters.asScala(expressions).toSeq(), childAttributes());
        residualConverter = VarkaRowToColumn.apply(residualSchema);
      }
      residualResolved = true;
    }
  }

  /**
   * Evaluates all residual entries in one per-row pass over the input, into writable vectors sized
   * to the batch. Returns the columns in residual-entry order; empty when the projection has no
   * residual entries.
   */
  private ColumnVector[] projectResiduals(ColumnarBatch input, int len) {
    resolveResidual();
    if (residualCount == 0) {
      return NO_COLUMNS;
    }
    WritableColumnVector[] vectors = offHeapColumnVectorEnabled
        ? OffHeapColumnVector.allocateColumns(len, residualSchema)
        : OnHeapColumnVector.allocateColumns(len, residualSchema);
    try {
      Iterator<InternalRow> rows = input.rowIterator();
      while (rows.hasNext()) {
        residualConverter.convert(residualProjection.apply(rows.next()), vectors);
      }
    } catch (Throwable e) {
      closeAllQuietly(Arrays.asList(vectors), "a Varka residual vector after a failed conversion");
      throw e;
    }
    return vectors;
  }

  // ---- the destination vectors ---------------------------------------------------------------

  /**
   * Allocates one destination Arrow vector: a {@code DateDayVector} for a date output, an {@code
   * IntVector} for a {@code datediff} day count. The fused loop writes its validity and data
   * buffers directly (zero copy), and every valid row's bit is set exactly once per batch however
   * the kernel gets there: an output whose validity is a pure AND or OR of the input bitmaps has
   * its whole bitmap written by the driver's bitmap pass, and the outputs that keep the per-group
   * write have their validity zeroed by the driver first. Null lanes of the data buffer are
   * undefined either way, matching the engine contract.
   */
  private BaseFixedWidthVector allocateVector(
      DataType dataType, int ordinal, int len, BufferAllocator allocator) {
    String name = "varka" + ordinal;
    ValueVector vector;
    if (dataType == DataTypes.DateType) {
      vector = new DateDayVector(name, allocator);
    } else if (dataType == DataTypes.IntegerType) {
      vector = new IntVector(name, allocator);
    } else if (dataType instanceof YearMonthIntervalType) {
      // The unit rides on the Spark type and never on the buffer, so every year-month unit writes
      // one vector class; the row path reads it back through the accessor ArrowColumnVector has.
      vector = new IntervalYearVector(name, allocator);
    } else if (dataType == DataTypes.LongType || dataType instanceof TimeType
        || dataType instanceof DayTimeIntervalType) {
      // The long lane's destinations, built from the same Arrow field Spark's own writer would
      // build for the type - BigIntVector, TimeNanoVector with the precision in its field metadata,
      // DurationVector in microseconds - so the row path reads them back through the accessors
      // ArrowColumnVector has, and the cache serializer sees the field it expects. A destination is
      // needed even though this task adds no long arithmetic: greatest, least and CASE WHEN produce
      // a long column.
      vector = ArrowUtils.toArrowField(name, dataType, true, null, false,
          org.apache.spark.sql.types.Metadata.empty(), false).createVector(allocator);
    } else {
      throw new IllegalStateException("no Varka output vector for " + dataType);
    }
    var fixed = (BaseFixedWidthVector) vector;
    try {
      fixed.allocateNew(len);
    } catch (Throwable e) {
      vector.close();
      throw e;
    }
    return fixed;
  }
}
