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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import scala.Option;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarCharVector;

import org.apache.spark.TaskContext;
import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDerivedInput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaInputBound;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAllocationSampler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDerivedKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitDeclined;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrorContext;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFallbackEvent;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmup;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMemorySanitizer;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMemoryViolation;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaReproduction;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeCache;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeEntry;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeKey;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeLookup;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.execution.metric.SQLMetric;
import org.apache.spark.sql.execution.varka.VarkaBatchDeclined;
import org.apache.spark.sql.execution.varka.VarkaBatchLedger;
import org.apache.spark.sql.execution.varka.VarkaClassDump;
import org.apache.spark.sql.execution.varka.VarkaFallbackAccounting;
import org.apache.spark.sql.execution.varka.VarkaKernelFailure;
import org.apache.spark.sql.execution.varka.VarkaKernelRunner;
import org.apache.spark.sql.execution.varka.VarkaKernelScratch;
import org.apache.spark.sql.execution.varka.VarkaWarmupGate;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.vectorized.ArrowColumnVector;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * The task-lifetime machinery shared by every Varka evaluator, as a composition of the Java
 * components that do the work (VARKA-251):
 *
 * <ul>
 *   <li>{@link VarkaBatchLedger}: the task's Arrow allocator, the open-batch ledger and its
 *       task-completion safety net;
 *   <li>{@link VarkaKernelScratch}: the derived inputs' buffers and the kernel's prefix scratch;
 *   <li>{@link VarkaKernelRunner}: the shape-cached kernel, its argument arrays, and filling and
 *       running them per batch;
 *   <li>{@link VarkaWarmupGate}: whether a batch waits on the row path while the shape's kernel
 *       warms;
 *   <li>{@link VarkaFallbackAccounting}: each fallback counted and evented under its actual
 *       cause;
 *   <li>{@link VarkaClassDump}: the emitted class written for {@code javap}.
 * </ul>
 *
 * <p>What stays here is what the evaluators share: the Arrow-backed {@link #canRun} test, the
 * telemetry names, and {@link #serveBatch}, whose kernel and fallback paths are the caller's
 * ({@link VarkaBatchPath}). A concrete evaluator supplies the compiled fused sub-plan the kernel
 * follows and what its identity reads as in the shape cache's side table; the projection and
 * filter evaluators own everything specific to their output shape - vector allocation and batch
 * assembly there, the selection bitmap here. The ownership and ordering contracts documented on
 * the projection evaluator are implemented here and in the components, and hold for every
 * subclass.
 *
 * <p>The per-batch methods read arrays derived once from the compiled plan (the input ordinals,
 * which of them are derived, the output types), so a batch costs no Scala collection access and no
 * allocation beyond what the kernel and the caller's paths make.
 */
public abstract class VarkaEvaluatorBase {

  private static final SparkLogger LOG = SparkLoggerFactory.getLogger(VarkaEvaluatorBase.class);

  /** Allocates one destination vector: the type, its output position, rows, and the allocator. */
  @FunctionalInterface
  public interface VectorAllocator {
    BaseFixedWidthVector allocate(
        DataType dataType, int ordinal, int rows, BufferAllocator allocator);
  }

  // The batches Varka nodes sent down their row path while a kernel warmed, held weakly by
  // identity; see markWarmupBatch.
  private static final Map<ColumnarBatch, Boolean> WARMUP_BATCHES =
      Collections.synchronizedMap(new WeakHashMap<>());

  // The emitter's size declines this JVM has logged, by reason, so a shape declined on every task
  // logs one warning rather than one per task. A reason names the method and its bytes, so it is
  // per shape; the set grows with the declined shapes a JVM sees, which are few.
  private static final Set<String> LOGGED_DECLINES = ConcurrentHashMap.newKeySet();

  /**
   * Which kernel batches the allocation sampler measures. The production schedule skips the JIT
   * warm-up (see {@code VarkaAllocationSampler}); suites set a dense one so a short query samples,
   * and restore the default in a finally.
   */
  public static volatile VarkaAllocationSampler.Schedule allocationSchedule =
      VarkaAllocationSampler.Schedule.DEFAULT;

  /** The runner's test hooks, read per batch from the exec nodes' test switches. */
  private static final VarkaKernelRunner.Hooks RUNNER_HOOKS = new VarkaKernelRunner.Hooks() {
    @Override
    public boolean failKernel() {
      return VarkaColumnarToRowExec$.MODULE$.isFailKernelForTesting();
    }

    @Override
    public int declineStatus() {
      return VarkaColumnarToRowExec$.MODULE$.declineStatusForTesting();
    }

    @Override
    public VarkaAllocationSampler.Schedule allocationSchedule() {
      return allocationSchedule;
    }
  };

  /**
   * Remembers that {@code result} - a Varka node's row-path output - is on the row path because a
   * kernel is warming, and returns it. A Varka node that consumes such a batch cannot run its
   * kernel on it, which is not Arrow, and counts it as a warm-up batch rather than a non-Arrow
   * fallback ({@link #serveBatch}): the format follows from the warm-up above it, not from the
   * data. Only columnar results are remembered; a row iterator has no consumer that asks.
   */
  static <T> T markWarmupBatch(T result) {
    if (result instanceof ColumnarBatch batch) {
      WARMUP_BATCHES.put(batch, Boolean.TRUE);
    }
    return result;
  }

  /** Whether {@code batch} came down a Varka node's row path while a kernel warmed. */
  static boolean isWarmupBatch(ColumnarBatch batch) {
    return WARMUP_BATCHES.containsKey(batch);
  }

  private final Seq<Attribute> childOutput;
  private final String operatorName;
  private final String classDumpDirectory;
  private final VarkaExecMetrics metrics;
  private final VarkaSessionEmit sessionEmit;
  private final VarkaEmitOptions emitOptions;
  private final boolean warmupEnabled;
  private final VarkaBatchLedger ledger = new VarkaBatchLedger();

  // Built on first use, since its size is the fused plan's, which a subclass defines after this
  // constructor has run. It grows through taskAllocator, which a suite may override to cap.
  private VarkaKernelScratch scratch;
  private VarkaFallbackAccounting accounting;
  private VarkaWarmupGate warmupGate;

  // The compiled plan and what the per-batch methods read of it, resolved on first use.
  private boolean planResolved;
  private CompiledVarkaProjection plan;
  private int[] inputOrdinals;
  private boolean[] derivedInput;
  /** Each kernel input's decimal scale, or -1 for an input that is not a decimal. */
  private int[] decimalScale;
  private DataType[] outputTypes;

  private boolean runnerResolved;
  private VarkaKernelRunner runner;
  private Boolean warmed;
  private String kernelIdentity;
  private String kernelShapeHash;
  private String renderedOptions;

  /**
   * @param warmupEnabled whether a shape's batches wait on the row path while its new kernel
   *                      compiles ({@code spark.sql.codegen.varka.warmup.enabled}); off unless
   *                      the exec node passes the session's setting, so an evaluator built
   *                      directly by a suite runs its kernel on the first batch
   */
  protected VarkaEvaluatorBase(
      Seq<Attribute> childOutput,
      String operatorName,
      Option<String> classDumpDirectory,
      VarkaExecMetrics metrics,
      VarkaSessionEmit sessionEmit,
      boolean warmupEnabled) {
    this.childOutput = childOutput;
    this.operatorName = operatorName;
    this.classDumpDirectory = classDumpDirectory.isDefined() ? classDumpDirectory.get() : null;
    this.metrics = metrics;
    this.sessionEmit = sessionEmit;
    // Derived once: the compile, every shape key and the fusion lines of one task then read the
    // same options, whatever the test hook does meanwhile.
    this.emitOptions = VarkaColumnarToRowExec$.MODULE$.emitOptions(sessionEmit);
    this.warmupEnabled = warmupEnabled;
    // The task-completion listener closes the open batches, then runs these in order, each
    // guarded on its own, then closes the allocator.
    ledger.onTaskCompletion(this::releaseTaskScratch);
    ledger.onTaskCompletion(this::onTaskCleanup);
  }

  /** The fused sub-plan the kernel computes; None when nothing is Varka-eligible. */
  protected abstract Optional<CompiledVarkaProjection> fusedPlan();

  /**
   * The entries rendered into the shape cache's side-table identity, in order - a projection's
   * entries, a filter's condition. Consumed lazily so a wide list is rendered only up to the
   * table's length cap.
   */
  protected abstract java.util.Iterator<String> identityEntries();

  /** The {@code toString} of each item, lazily, for {@link #identityEntries}. */
  protected static java.util.Iterator<String> render(Seq<?> items) {
    java.util.Iterator<?> it = CollectionConverters.asJava(items).iterator();
    return new java.util.Iterator<String>() {
      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public String next() {
        return it.next().toString();
      }
    };
  }

  protected final String evaluatorOperator() {
    return operatorName;
  }

  protected final Seq<Attribute> childAttributes() {
    return childOutput;
  }

  /**
   * A further kernel of this evaluator's projection, built with this evaluator's settings - the
   * class dump, the metrics, the AVX level and the warm-up - and allocating from its allocator, so
   * that the first kernel and the further ones cannot drift apart.
   */
  protected final VarkaKernelPart kernelPart(
      CompiledVarkaProjection plan, Seq<NamedExpression> entries) {
    return new VarkaKernelPart(plan, entries, childOutput, operatorName,
        Option.apply(classDumpDirectory), metrics, sessionEmit, warmupEnabled, this::taskAllocator);
  }

  /** The options this evaluator emits with, which its compiler call must use too. */
  protected VarkaEmitOptions emitOptions() {
    return emitOptions;
  }

  /**
   * The families the session switched off (VARKA-318), which this evaluator's compiler call
   * passes beside {@link #emitOptions()} so that it declines what the plan's compile declined.
   */
  protected Set<VarkaFamily> familiesOff() {
    return sessionEmit.familiesOff();
  }

  // ---- the plan ------------------------------------------------------------------------------

  private CompiledVarkaProjection plan() {
    if (!planResolved) {
      // Marked resolved only once the subclass's compile has returned, so a throw (a compile that
      // fails the task) is met again by the next call, as the Scala lazy val it replaces met it.
      Optional<CompiledVarkaProjection> fused = fusedPlan();
      if (fused.isPresent()) {
        plan = fused.get();
        List<Integer> ordinals = plan.inputOrdinals();
        inputOrdinals = new int[ordinals.size()];
        derivedInput = new boolean[ordinals.size()];
        decimalScale = new int[ordinals.size()];
        for (int i = 0; i < inputOrdinals.length; i++) {
          inputOrdinals[i] = ordinals.get(i);
          derivedInput[i] = plan.derivedAt(i).isPresent();
          // The scale the batch's decimal field must carry (VARKA-347), from the child's own
          // attribute, which is the column the kernel input reads.
          decimalScale[i] = childOutput.apply(inputOrdinals[i]).dataType() instanceof DecimalType d
              ? d.scale() : -1;
        }
        outputTypes = plan.outputTypes().toArray(new DataType[0]);
      }
      planResolved = true;
    }
    return plan;
  }

  private VarkaKernelScratch scratch() {
    if (scratch == null) {
      plan();
      scratch = new VarkaKernelScratch(this::taskAllocator,
          inputOrdinals == null ? 0 : inputOrdinals.length);
    }
    return scratch;
  }

  private VarkaFallbackAccounting accounting() {
    if (accounting == null) {
      accounting = new VarkaFallbackAccounting(
          new VarkaFallbackAccounting.Counters(
              metrics.fallbackBatchesKernel(),
              metrics.fallbackBatchesRowPath(),
              metrics.fallbackBatchesDeclined(),
              metrics.fallbackBatchesNonArrow(),
              metrics.suspectAllocationSamples(),
              metrics.declinedByReason()),
          emitOptions.exactRefusalStatus(),
          this::kernelIdentity,
          this::renderedOptions);
    }
    return accounting;
  }

  /**
   * Whether this evaluator's kernels are warmed before they serve batches: the warm-up is on and
   * this JVM can warm. A warmed kernel is its own class, under the name the C1-exclusion directive
   * matches, so a session with the warm-up off - or a JVM that cannot warm - emits and compiles
   * its kernels exactly as it would without the warm-up.
   */
  private boolean warmed() {
    if (warmed == null) {
      warmed = VarkaKernelWarmup.warms(warmupEnabled);
    }
    return warmed;
  }

  // ---- the kernel ----------------------------------------------------------------------------

  /**
   * The task-lifetime emitted fused loop and its reused argument arrays; null when emission failed
   * - an IR shape past the emitter's caps, or any linkage problem - in which case every batch takes
   * the caller's fallback path.
   */
  protected final VarkaKernelRunner fusedRunner() {
    if (!runnerResolved) {
      CompiledVarkaProjection compiled = plan();
      if (compiled != null) {
        try {
          runner = newRunner(compiled);
        } catch (VarkaEmitDeclined d) {
          // A shape over the emitter's method budget: the same reason on every task, so it is
          // logged once per JVM, and as a reason rather than a stack trace. The compiler asks the
          // emitter at plan time and demotes what it declines, so this is the last resort: a
          // shape the planning JVM's vector width admitted within the byte or so another width
          // adds (VARKA-169.md 2.2).
          if (LOGGED_DECLINES.add(d.getMessage())) {
            LOG.warn("The Varka emitter declined " + kernelIdentity() + ": " + d.getMessage()
                + "; falling back to the per-row path.");
          }
          emissionFailed(d);
        } catch (Throwable e) {
          if (!isCatchable(e)) {
            throw e;
          }
          // No runner exists, so the shape hash is the one kernelIdentity() computed from the IR.
          VarkaErrorContext ctx = errorContext(VarkaFallbackEvent.EMISSION_FAILURE, e, null,
              VarkaErrorContext.safely(this::kernelShapeHash), ownFamilies(), null, false);
          LOG.warn("Failed to emit the Varka fused kernel " + ctx.identity()
              + "; falling back to the per-row path." + VarkaFallbackAccounting.block(ctx), e);
          // A class that fails to verify or define fails here, and no class dump has it.
          try {
            VarkaReproduction.write(() -> ctx, null, e, shapeKey(compiled), compiled.literals(),
                compiled.longLiterals(), null, null);
          } catch (Throwable t) {
            VarkaReproduction.unwritten(t);
          }
          emissionFailed(e);
          refuseUndeclaredFallback("emission of " + ctx.identity(), ctx);
        }
      }
      // Marked resolved after a success or a failure the fallback answers, and not after one that
      // propagates: a later call builds the runner again instead of reading a null as "emission
      // failed" and falling back silently.
      runnerResolved = true;
    }
    return runner;
  }

  private void emissionFailed(Throwable e) {
    VarkaExecMetrics.inc(metrics.emissionFailures());
    VarkaFallbackAccounting.fallbackEvent(VarkaFallbackEvent.EMISSION_FAILURE,
        this::kernelIdentity, this::renderedOptions, e.getClass().getName());
  }

  /**
   * The runner for {@code compiled}: the shape-named class from {@link VarkaShapeCache}, whose
   * lookup records this execution (operator, stage, the evaluator's leading entries) in the cache's
   * side table so the class joins back to the plan nodes that ran it; the class dumped where a
   * directory is configured, on hit and miss alike, so a session that configured it after the
   * shape was cached still gets its file; and the plan handed over as arrays, read per batch
   * without boxing.
   */
  private VarkaKernelRunner newRunner(CompiledVarkaProjection compiled) {
    if (VarkaColumnarToRowExec$.MODULE$.isFailEmissionForTesting()) {
      throw new IllegalStateException("injected Varka emission failure");
    }
    VarkaShapeLookup lookup = VarkaShapeCache.getOrEmit(shapeKey(compiled), executionIdentity());
    VarkaExecMetrics.inc(lookup.hit() ? metrics.cacheHits() : metrics.cacheMisses());
    VarkaShapeEntry entry = lookup.entry();
    VarkaClassDump.dump(classDumpDirectory, entry.sourceFile(), entry.classBytes());
    int n = inputOrdinals.length;
    var derived = new VarkaDerivedKind[n];
    for (int i = 0; i < n; i++) {
      derived[i] = compiled.derivedAt(i).map(VarkaDerivedInput::kind).orElse(null);
    }
    List<VarkaInputBound> declared = compiled.inputBounds();
    var bounds = new VarkaKernelRunner.Bound[declared.size()];
    for (int i = 0; i < bounds.length; i++) {
      VarkaInputBound b = declared.get(i);
      bounds[i] = new VarkaKernelRunner.Bound(b.inputIndex(), b.lo(), b.hi());
    }
    return new VarkaKernelRunner(entry, compiled.lane(emitOptions()), inputOrdinals, derived,
        bounds, compiled.outputs().size(), ints(compiled.literals()),
        longs(compiled.longLiterals()), scratch(), accounting(), RUNNER_HOOKS);
  }

  private static int[] ints(List<Integer> values) {
    int[] out = new int[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = values.get(i);
    }
    return out;
  }

  private static long[] longs(List<Long> values) {
    long[] out = new long[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = values.get(i);
    }
    return out;
  }

  /**
   * Whether this task tried and failed to obtain its kernel class: the plan compiled but the
   * runner could not be built. The exec nodes use it to keep the per-batch fallback cause honest -
   * after an emission failure every batch fails {@link #canRun}, which without this test would
   * count as "input not Arrow-backed".
   */
  boolean emissionFailed() {
    return plan() != null && fusedRunner() == null;
  }

  /**
   * This execution's identity - the operator and this task's stage - which goes to
   * {@link VarkaShapeCache}'s side table rather than into the shared class bytes. Outside a task
   * (diagnostics, tests) the stage reads as -1 rather than throwing.
   */
  private String executionName() {
    TaskContext task = TaskContext.get();
    int stage = task == null ? -1 : task.stageId();
    return "Varka_" + operatorName + "_Stage" + stage;
  }

  /**
   * The identity recorded in the cache's side table: the execution name, then as much of the
   * evaluator's entries as the table keeps ({@code MAX_EXECUTION_IDENTITY_LENGTH}). Bounded while
   * building: rendering all of a wide projection on every task's setup path would be paid only to
   * be truncated on arrival, or discarded outright when the cache is disabled.
   */
  private String executionIdentity() {
    var sb = new StringBuilder(executionName()).append(": ");
    var it = identityEntries();
    while (it.hasNext() && sb.length() <= VarkaShapeCache.MAX_EXECUTION_IDENTITY_LENGTH) {
      sb.append(it.next());
      if (it.hasNext()) {
        sb.append(", ");
      }
    }
    return sb.toString();
  }

  /**
   * The cache key of the fused sub-plan: exactly the emitter inputs the bytes follow. The
   * session's emit settings ({@link VarkaSessionEmit}: {@code useAVX} and
   * {@code exactRefusalStatus}), read on the driver and carried here, are the production knobs on
   * the options; they are applied over the test hook's options rather than instead of them, so a
   * suite that drives a variant and sets one gets both, and each is left alone when unset so that
   * the hook's own value survives.
   */
  protected VarkaShapeKey shapeKey(CompiledVarkaProjection compiled) {
    return new VarkaShapeKey(compiled.outputs(),
        compiled.inputOrdinals().size(), compiled.numLiterals(), emitOptions(), warmed());
  }

  /**
   * The kernel named the way its telemetry names it: the {@code SourceFile} of the shared class,
   * the IR it computes, and this execution's operator and stage. Every fallback warning - here and
   * in the exec nodes - says which kernel it gave up on, so a log line identifies both the class
   * and the plan node without correlation. Reading it forces no emission: the shape hash is
   * computed from the IR, not the bytes. Memoized, since the rendering hashes the canonical IR and
   * is constant per evaluator, so per-batch fallback paths must not recompute it. The IR renders
   * through {@code VarkaVectorIR.canonical}, the same rendering the class's own
   * {@code VarkaDebugInfo} carries, so a log line and the bytes it names describe the shape the
   * same way.
   */
  String kernelIdentity() {
    if (kernelIdentity == null) {
      CompiledVarkaProjection compiled = plan();
      if (compiled == null) {
        kernelIdentity = "[no compiled projection] (" + executionName() + ")";
      } else {
        var ir = new StringBuilder();
        for (VarkaVectorIR output : compiled.outputs()) {
          if (ir.length() > 0) {
            ir.append(", ");
          }
          ir.append(VarkaVectorIR.canonical(output));
        }
        String hash = VarkaShapeCache.shapeHash(shapeKey(compiled));
        kernelShapeHash = hash;
        kernelIdentity = VarkaShapeCache.sourceFileFor(hash) + " [" + ir + "] ("
            + executionName() + ")";
      }
    }
    return kernelIdentity;
  }

  /**
   * The emitted fused-kernel class's bytes, exactly as defined - the diagnostics hook behind the
   * telemetry note on the projection evaluator: {@code VarkaDebugInfo.read} and
   * {@code ClassFile.parse} recover the IR, the plan fragment and the {@code SourceFile} name from
   * them. Forces emission if no batch has done so yet; None when the plan is ineligible or
   * emission failed.
   */
  Option<byte[]> emittedClassBytes() {
    VarkaKernelRunner r = fusedRunner();
    return r == null ? Option.empty() : Option.apply(r.classBytes);
  }

  /**
   * Runs this evaluator's kernel over the input batch into vectors {@code allocate} makes from
   * {@code allocator}, appending each to {@code owned} as it is created (the caller closes
   * {@code owned} on failure), and returns them by the kernel's output index. The projection
   * evaluator calls it on itself and on each further kernel of a projection several kernels serve,
   * so every kernel's columns come from the one allocator and join the one output batch. Callers
   * must have asked {@link #canRun} first.
   */
  ColumnVector[] runKernel(
      ColumnarBatch input,
      int len,
      List<ColumnVector> owned,
      BufferAllocator allocator,
      VectorAllocator allocate) {
    plan();
    VarkaKernelRunner kernel = fusedRunner();
    // Under the memory sanitizer every buffer the kernel is handed is registered in this window,
    // and a mapping outside them fails; off, begin and end do nothing.
    VarkaMemorySanitizer.begin();
    try {
      kernel.fill(input, len);
      var fixed = new BaseFixedWidthVector[outputTypes.length];
      var columns = new ColumnVector[outputTypes.length];
      // Under the sanitizer an output has a tail of rows past len for its canary to sit in; the
      // vector's value count is still len, so nothing downstream sees them.
      int rows = VarkaMemorySanitizer.ENABLED ? len + VarkaMemorySanitizer.CANARY_ROWS : len;
      for (int o = 0; o < outputTypes.length; o++) {
        BaseFixedWidthVector vector = allocate.allocate(outputTypes[o], o, rows, allocator);
        fixed[o] = vector;
        columns[o] = new VarkaOwnedArrowColumnVector(vector);
        owned.add(columns[o]);
        kernel.dstData[o] = vector.getDataBuffer().memoryAddress();
        // A boolean output's BitVector has a type width of 0, which means a selection bitmap
        // with no data; its data is a bitmap of whole words, which the marker says (VARKA-27).
        kernel.dstWidth[o] = vector instanceof BitVector
            ? VarkaReproduction.KernelCall.BIT_OUTPUT : vector.getTypeWidth();
        kernel.dstValidity[o] = vector.getValidityBuffer().memoryAddress();
        if (VarkaMemorySanitizer.ENABLED) {
          // The kernel's own bytes: len values (a bit output's bitmap to its last whole word),
          // and the validity bitmap to its last whole word.
          VarkaMemorySanitizer.guard("output data", o, vector.getDataBuffer(),
              VarkaReproduction.KernelCall.outputDataBytes(kernel.dstWidth[o], len));
          VarkaMemorySanitizer.guard("output validity", o, vector.getValidityBuffer(),
              ((len + 63) / 64) * 8L);
        }
      }
      kernel.invoke(len);
      for (BaseFixedWidthVector vector : fixed) {
        vector.setValueCount(len);
      }
      return columns;
    } catch (VarkaKernelFailure e) {
      // Written here, inside the sanitizer's window and with this kernel's runner, then
      // rethrown unchanged for serveBatch to fall back on (VARKA-315).
      reproduceKernelFailure(VarkaFallbackEvent.KERNEL_FAILURE, e.getCause(), kernel, input, len,
          false);
      throw e;
    } catch (VarkaMemoryViolation e) {
      // A violation fails the task and passes every catch above unrecorded, so this is the one
      // place it is written down.
      reproduceKernelFailure(VarkaErrorContext.MEMORY_VIOLATION, e, kernel, input, len, false);
      throw e;
    } finally {
      VarkaMemorySanitizer.end();
    }
  }

  /**
   * The reproduction file of a failure inside this evaluator's kernel (VARKA-315): its shape key,
   * its literals, the call it failed in and its class. The projection's {@link #runKernel} and the
   * filter's mask call it from their catches. Nothing here may throw, since the failure being
   * handled is rethrown after it: {@link VarkaReproduction#write} catches its own failures, and
   * this catches what building its arguments could throw. The record and the call are built only
   * when a file is written, so a kernel that fails on every batch pays for them once.
   */
  protected final void reproduceKernelFailure(String kind, Throwable failure,
      VarkaKernelRunner kernel, ColumnarBatch input, int len, boolean selectionBitmaps) {
    try {
      CompiledVarkaProjection compiled = plan();
      VarkaReproduction.write(
          () -> errorContext(kind, failure, null, kernel.shapeHash, ownFamilies(), input, true),
          kernel.shapeHash, failure, shapeKey(compiled), compiled.literals(),
          compiled.longLiterals(),
          maxBatchBytes -> kernel.capture(input, len, maxBatchBytes, selectionBitmaps),
          kernel.classBytes);
    } catch (Throwable t) {
      VarkaReproduction.unwritten(t);
    }
  }

  /**
   * Whether the kernel can serve this batch, or the caller has to fall back. The Arrow check
   * covers only the columns the fused sub-plan references: other entries put no constraint on the
   * input format beyond what {@code rowIterator} needs.
   */
  public boolean canRun(ColumnarBatch input) {
    CompiledVarkaProjection compiled = plan();
    if (compiled == null || fusedRunner() == null) {
      return false;
    }
    return input.numRows() > 0 && isArrowBacked(input);
  }

  /**
   * The per-batch dispatch every exec node runs: the kernel path under the shared cause
   * accounting ({@link VarkaFallbackAccounting}), with every degradation routed to the caller's
   * fallback.
   *
   * <p>With the warm-up on, a batch the kernel could serve still takes the row path while the
   * shape's kernel is not compiled yet ({@link VarkaWarmupGate}). That is not a fallback and is
   * counted apart from them: the row path is the faster of the two until C2 has the kernel. A
   * batch the evaluator declines while it is copied for the warm-up is counted as the declined
   * batch it is, as it would be on the kernel path. A batch that is not Arrow because a Varka node
   * below sent it down its own row path while its kernel warmed is a warm-up batch here too, and
   * so is this node's output for it, for the node above.
   *
   * <p>Each batch's rows count under the path that answered it, and the time in that path is
   * added where it can be measured ({@link VarkaExecMetrics.Paths}): the kernel path's time only
   * for a batch it served, since a declined or failed attempt's output is thrown away.
   */
  public <T> T serveBatch(
      ColumnarBatch input, VarkaBatchPath<T> kernelPath, VarkaBatchPath<T> fallbackPath) {
    if (!canRun(input)) {
      if (recordRefusedBatch(input)) {
        return markWarmupBatch(fallback(input, fallbackPath));
      }
      return fallback(input, fallbackPath);
    }
    boolean ready;
    try {
      ready = kernelReady(input);
    } catch (VarkaBatchDeclined declined) {
      accounting().declinedBatch(declined.status, declined.kernel);
      return fallback(input, fallbackPath);
    }
    if (!ready) {
      VarkaExecMetrics.inc(metrics.warmupBatches());
      return markWarmupBatch(fallback(input, fallbackPath));
    }
    try {
      VarkaExecMetrics.Paths paths = metrics.paths();
      return timed(paths.kernelTime(), paths.kernelRows(), input, kernelPath);
    } catch (VarkaBatchDeclined e) {
      // Not a failure: the kernel ran and said it could not answer for this batch.
      accounting().declinedBatch(e.status, e.kernel);
    } catch (VarkaKernelFailure e) {
      // A genuine kernel error is told apart from a failure in the per-row machinery sharing the
      // try by the marker the runner wraps it in.
      VarkaErrorContext ctx = errorContext(VarkaFallbackEvent.KERNEL_FAILURE, e.getCause(),
          e.kernel, e.shapeHash, e.kernel == null ? ownFamilies() : e.families, input,
          e.kernel == null);
      accounting().kernelFailure(e.getCause(), e.kernel, ctx);
      refuseUndeclaredFallback("the kernel " + (e.kernel != null ? e.kernel : kernelIdentity()),
          ctx);
    } catch (Throwable e) {
      if (!isCatchable(e)) {
        throw e;
      }
      // The per-row machinery is no family's: its record names none.
      VarkaErrorContext ctx = errorContext(VarkaFallbackEvent.ROW_PATH_FAILURE, e, null,
          runner != null ? runner.shapeHash : null, List.of(), input, true);
      accounting().rowPathFailure(e, ctx);
      refuseUndeclaredFallback("the per-row machinery beside " + kernelIdentity(), ctx);
    }
    return fallback(input, fallbackPath);
  }

  /** Runs the caller's fallback for {@code input}, counting its rows and, where eager, its time. */
  private <T> T fallback(ColumnarBatch input, VarkaBatchPath<T> fallbackPath) {
    VarkaExecMetrics.Paths paths = metrics.paths();
    return timed(paths.fallbackTime(), paths.fallbackRows(), input, fallbackPath);
  }

  /**
   * Runs {@code path} for {@code input}; when it returns, adds the nanoseconds it took to
   * {@code time} and the batch's rows to {@code rows}, each where present. A path that throws
   * adds neither.
   */
  private static <T> T timed(@Nullable SQLMetric time, @Nullable SQLMetric rows,
      ColumnarBatch input, VarkaBatchPath<T> path) {
    long start = time != null ? System.nanoTime() : 0L;
    T result = path.run();
    if (time != null) {
      time.add(System.nanoTime() - start);
    }
    VarkaExecMetrics.add(rows, input.numRows());
    return result;
  }

  /**
   * Under test, fails the task over a failure fallback no test declared (VARKA-275), after it has
   * been counted, evented and logged as any fallback is; see
   * {@code VarkaColumnarToRowExec.failureFallbackForbidden}. A task already being killed is left
   * alone: when one task's error cancels its stage, a sibling interrupted while it compiles its
   * row machinery fails with a class-loading error that is the kill's, not the kernel's
   * ({@code VARKA-275.md} 2). The refusal is Spark's {@code INTERNAL_ERROR} with the failure's
   * context record, whose cause is the failure, so a test that fails over a broken kernel shows
   * the kernel, its options and the batch (VARKA-314).
   */
  private static void refuseUndeclaredFallback(String what, VarkaErrorContext ctx) {
    TaskContext task = TaskContext.get();
    if (task != null && task.isInterrupted()) {
      return;
    }
    if (VarkaColumnarToRowExec$.MODULE$.failureFallbackForbidden()) {
      throw VarkaErrors.illegalState("A failure of " + what + " was about to be answered by the "
          + "per-row path, and no test declared one; a test that causes it on purpose sets a "
          + "failure hook or VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting "
          + "(VARKA-275)", ctx);
    }
  }

  /**
   * The context record of a failure this evaluator caught (VARKA-314), built in the catch and
   * only there, from what the evaluator and the runner already hold: nothing on the per-batch
   * path is added for it. Every field is built defensively, since an exception here would
   * replace the failure being reported.
   *
   * <p>It allocates, on a failed batch only: that batch then runs the row path, which costs far
   * more, and a batch that does not fail never reaches it.
   *
   * @param identity the further kernel that failed, as it names itself; null for this one
   * @param shapeHash the failing kernel's shape hash, or null where none is known
   * @param families the families whose nodes the failing kernel holds (VARKA-318)
   * @param input the batch it failed on; null for a failure outside a batch
   * @param ownInputs whether the failing kernel reads this evaluator's inputs, so that their null
   *                  counts describe it; a further kernel of the projection reads others
   */
  private VarkaErrorContext errorContext(String kind, Throwable cause, @Nullable String identity,
      @Nullable String shapeHash, List<VarkaFamily> families, @Nullable ColumnarBatch input,
      boolean ownInputs) {
    return new VarkaErrorContext(kind,
        identity != null ? identity : VarkaErrorContext.safely(this::kernelIdentity),
        shapeHash,
        VarkaErrorContext.safely(this::renderedOptions),
        families,
        batchFacts(input, ownInputs),
        cause);
  }

  /**
   * The families whose nodes this evaluator's kernel holds, read off the plan already resolved
   * and never compiling one: it is asked inside a catch, where a compile could throw.
   */
  private List<VarkaFamily> ownFamilies() {
    return planResolved && plan != null ? plan.families() : List.of();
  }

  /** The shape hash {@link #kernelIdentity} computed from the IR; null without a plan. */
  private String kernelShapeHash() {
    kernelIdentity();
    return kernelShapeHash;
  }

  /**
   * The batch's row count and, for {@code ownInputs}, the null count of the column each of this
   * kernel's inputs reads, read at the catch; null when there is no batch or a column cannot say.
   */
  private VarkaErrorContext.Batch batchFacts(@Nullable ColumnarBatch input, boolean ownInputs) {
    if (input == null) {
      return null;
    }
    try {
      var nulls = new ArrayList<Integer>();
      if (ownInputs && inputOrdinals != null) {
        for (int ordinal : inputOrdinals) {
          nulls.add(input.column(ordinal).numNulls());
        }
      }
      return new VarkaErrorContext.Batch(input.numRows(), nulls);
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * The emit options that differ from the defaults, as the context record and the fallback
   * event name them; memoized, since the options are fixed per evaluator and a per-batch
   * fallback path must not render them again.
   */
  private String renderedOptions() {
    if (renderedOptions == null) {
      renderedOptions = VarkaErrorContext.renderOptions(emitOptions);
    }
    return renderedOptions;
  }

  /** Whether this batch goes to the kernel; see {@link VarkaWarmupGate}. */
  boolean kernelReady(ColumnarBatch input) {
    if (!warmed()) {
      return true;
    }
    if (warmupGate == null) {
      warmupGate = new VarkaWarmupGate(fusedRunner(), anyNullableInput(), this::inputWidths,
          this::kernelIdentity);
    }
    return warmupGate.kernelReady(input);
  }

  /**
   * Whether any kernel input can hold a null, so that a batch can reach the kernel's masked
   * driver: a column the child declares nullable, or a derived input, whose derivation may produce
   * one. The warm-up compiles the masked driver only then.
   */
  private boolean anyNullableInput() {
    plan();
    for (int i = 0; i < inputOrdinals.length; i++) {
      if (derivedInput[i] || childOutput.apply(inputOrdinals[i]).nullable()) {
        return true;
      }
    }
    return false;
  }

  /** Each kernel input's bytes per row: its Arrow vector's width, four for a derived input. */
  private int[] inputWidths(ColumnarBatch input) {
    var widths = new int[inputOrdinals.length];
    for (int i = 0; i < widths.length; i++) {
      widths[i] = derivedInput[i]
          ? 4
          : VarkaKernelRunner.warmupWidth((BaseFixedWidthVector)
              ((ArrowColumnVector) input.column(inputOrdinals[i])).getValueVector());
    }
    return widths;
  }

  /**
   * A batch {@link #canRun} refused, counted under its actual cause: an emission failure was
   * already counted once per task by the emission catch; an empty batch is served trivially and is
   * no fallback at all; an ineligible plan (defensive - the rule should not have fused it) is not
   * a data-format property. Only a non-empty batch whose referenced columns fail the Arrow check
   * is the non-Arrow cause the metric names - unless a Varka node below produced it on its row
   * path while its kernel warmed, which is a warm-up batch here as well. Returns whether it was
   * that.
   */
  private boolean recordRefusedBatch(ColumnarBatch input) {
    if (!emissionFailed() && plan() != null && input.numRows() > 0) {
      if (isWarmupBatch(input)) {
        VarkaExecMetrics.inc(metrics.warmupBatches());
        return true;
      }
      accounting().nonArrowBatch();
    }
    return false;
  }

  /**
   * Whether the kernel can run over this batch: every referenced column must be an Arrow vector
   * of a class a lane reads ({@link VarkaLaneArrow}, the one table of them) holding exactly the
   * batch's rows, no more - or a boolean column's {@code BitVector}, or, for an input the
   * evaluator derives, an Arrow {@code VarCharVector} of the same row count, the one string vector
   * the Arrow cache produces and the derived leaf reads; the large and view string vectors refuse
   * the batch like any other column type. A decimal column's field must also say 128 bits and the
   * column's scale ({@link VarkaLaneArrow#decimalFieldMatches}).
   *
   * <p>The row count matters because the kernel takes a null count for the rows it is given, while
   * a vector's null count covers all {@code valueCount} of its rows. A vector longer than the
   * batch would hand it a count for rows that are not in it - and a vector whose extra rows happen
   * to hold every null would make that count equal the batch's row count, tripping the all-null
   * shortcut over rows that are not null at all. Such a batch takes the caller's fallback; serving
   * it from the kernels would mean counting nulls over {@code [0, len)} here instead.
   *
   * <p>Arrow's vector classes are not a sealed set, so the {@code switch} below ends in a
   * {@code default}; but what that arm admits is {@link VarkaLaneArrow}'s table, a switch over the
   * lanes with no {@code default}, so a lane cannot be added without naming its vectors there.
   */
  private boolean isArrowBacked(ColumnarBatch input) {
    int rows = input.numRows();
    for (int i = 0; i < inputOrdinals.length; i++) {
      if (!(input.column(inputOrdinals[i]) instanceof ArrowColumnVector column)) {
        return false;
      }
      ValueVector vector = column.getValueVector();
      boolean ok = switch (vector) {
        // The two vectors that belong to no lane: a derived input's string, and a boolean
        // column's data bitmap, in the layout of a validity bitmap (BoolColumn).
        case VarCharVector v -> derivedInput[i] && v.getValueCount() == rows;
        case BitVector v -> !derivedInput[i] && v.getValueCount() == rows;
        default -> !derivedInput[i] && VarkaLaneArrow.reads(vector)
            && VarkaLaneArrow.decimalFieldMatches(vector, decimalScale[i])
            && vector.getValueCount() == rows;
      };
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  // ---- the ledger ----------------------------------------------------------------------------

  /** Takes ownership of a batch the caller built itself; see {@link VarkaBatchLedger#track}. */
  public ColumnarBatch track(ColumnarBatch batch) {
    return ledger.track(batch);
  }

  /**
   * The output batch for a projection that only forwards columns of its input; see
   * {@link VarkaBatchLedger#forwardColumns}.
   */
  public ColumnarBatch forwardColumns(ColumnarBatch input, int[] ordinals) {
    return ledger.forwardColumns(input, ordinals);
  }

  protected final void trackOwned(ColumnarBatch batch, ColumnVector[] owned) {
    ledger.trackOwned(batch, owned);
  }

  /**
   * Releases a batch obtained from this evaluator or handed to {@link #track}; see
   * {@link VarkaBatchLedger#release}.
   */
  public void release(ColumnarBatch batch) {
    ledger.release(batch);
  }

  /** A kernel failure worth falling back on, rather than one that has to fail the task. */
  public boolean isCatchable(Throwable e) {
    return VarkaKernelRunner.isCatchable(e);
  }

  /**
   * A subclass's extra cleanup, run by the task-completion listener before the allocator closes -
   * the filter evaluator releases its selection buffer here.
   */
  protected void onTaskCleanup() {
  }

  /**
   * Releases this evaluator's task-lifetime scratch - the derived inputs' buffers and the kernel's
   * prefix scratch - for an owner whose cleanup does it: a further kernel of a projection
   * allocates from the projection's allocator and registers no listener of its own, so the
   * projection's evaluator releases its scratch before it closes that allocator.
   */
  void releaseTaskScratch() {
    if (scratch != null) {
      scratch.release();
    }
  }

  /** See {@link VarkaBatchLedger#closeQuietly}. */
  protected final void closeQuietly(AutoCloseable resource, String what) {
    VarkaBatchLedger.closeQuietly(resource, what);
  }

  /** {@link #closeQuietly} over a collection, guarding each element separately. */
  protected final void closeAllQuietly(Iterable<? extends AutoCloseable> resources, String what) {
    for (AutoCloseable resource : resources) {
      closeQuietly(resource, what);
    }
  }

  /** Registers the single task-completion listener; see {@link VarkaBatchLedger#ensureCleanup}. */
  protected final void ensureCleanup() {
    ledger.ensureCleanup();
  }

  /** Returns the task's Arrow child allocator, creating it on first use. */
  protected BufferAllocator taskAllocator() {
    return ledger.allocator();
  }
}
