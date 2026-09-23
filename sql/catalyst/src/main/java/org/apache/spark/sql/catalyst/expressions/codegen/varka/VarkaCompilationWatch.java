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

package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingStream;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;

/**
 * notices when C2 compiles the same generated kernel method to a materially different
 * size than it did earlier in this JVM.
 *
 * <p><b>Why this is worth a thread.</b> Six failed hypotheses once went on a kernel that ran
 * at either 165 or 236 M rows/s under {@code -XX:MaxVectorSize=16} - stdev 0 inside a run, 42%
 * between runs - before the cause turned out to be C2's register allocator. The two compilations
 * contain identical vector op counts; the whole difference is spill traffic, four stack moves
 * against seventy-four, and roughly 2x in compiled size. It costs 30-40% and nothing anywhere
 * reports that it happened. This does not prevent it - that is a structural question about how
 * many outputs share a loop method - it makes it visible.
 *
 * <p><b>The expectation is self-calibrating.</b> The obvious design is a committed table of
 * expected sizes per shape, and it is the wrong one: it has to come from somewhere and it drifts
 * every time the emitter changes. Varka already keys every kernel by a shape hash and the same
 * shape emits byte-identical bytecode, so the comparison is between compilations of the same
 * method rather than against any constant. The first compilation establishes the baseline; a
 * later one that differs by more than {@link #DIVERGENCE_RATIO} is the report. No table, no
 * drift, and it gets more accurate the longer a JVM lives.
 *
 * <p><b>The key is a method, not a shape</b> ( {@code PLAN_TASK_50.md} 2.1). A generated kernel is
 * not one method: it is deliberately split into {@code run}, {@code runDense},
 * {@code runMasked}, {@code loopDense}<i>g</i>, {@code loopMasked}<i>g</i>,
 * {@code epilogueDense} and {@code epilogueMasked} (per group, with a <i>g</i> suffix, under
 * the byte budget), whose compiled sizes differ from each other
 * by an order of magnitude. Keyed on the shape alone, the second method compiled for a shape
 * would be compared against the first and reported as a divergence, and the detector would fire
 * constantly on a healthy JVM. Measured on a throwaway probe, one method at one tier moved with
 * every part of the key: OSR 744 bytes against non-OSR 576, tier 3 576 against tier 4 696, and a
 * different method of the same class 10552. So the key is shape hash, method name and compile
 * level together, and on-stack-replacement compilations are dropped rather than keyed - they are
 * not what the steady-state path runs, and they are identical across both modes anyway.
 *
 * <p><b>It is a diagnostic and never a control loop.</b> Re-emitting a shape under a new class
 * name would give the allocator a fresh roll, and {@code PLAN_MILESTONE_4.md}'s debt register
 * records why that is not built: each resample costs another class, another compile and another
 * warm-up, against a shape a short query may run a handful of times.
 *
 * <p><b>What it can and cannot see.</b> A baseline needs two compilations of one key, and
 * measurement showed every key is normally compiled exactly once per JVM - so on a steady
 * workload there is nothing to compare, however badly C2 allocated. What produces a second
 * compilation is <i>re-emission</i>: the same shape emitted into a fresh class of the same name
 * under a different loader, which is what {@code maxEntries = 0} and any cache eviction already
 * do. Measured, emitting one shape twice in a JVM produced 16 compilations across 8 keys, each
 * compiled twice. So this reports that a re-emitted kernel compiled differently than the same
 * kernel did earlier in the same JVM; it cannot report that this JVM's allocation is worse than
 * another JVM's, which is the form the observed bimodality actually took.
 *
 * <p>Off unless {@code spark.sql.codegen.varka.compilationWatch.enabled} is set. When off, no
 * instance exists, so there is no stream, no thread and no map.
 */
public final class VarkaCompilationWatch implements AutoCloseable {

  private static final SparkLogger LOG =
      SparkLoggerFactory.getLogger(VarkaCompilationWatch.class);

  /**
   * How much larger (or smaller) a later compilation has to be before it is reported, as a
   * fraction of the baseline.
   *
   * <p>Two facts bound this and neither leaves much room for taste: healthy recompilations of the
   * same method at the same tier are compiling identical bytecode and should differ by nothing,
   * while the allocation failure this exists to catch is about 2x. Measured rather than assumed
   * ({@code PLAN_TASK_50.md} section 3): one {@code year} kernel across three JVMs, and across
   * two emissions inside one JVM, produced byte-identical sizes for every key - the healthy
   * spread is zero. So this sits four times above anything observed and four times below the
   * failure it hunts. It is not tightened, because three runs on one host does not prove the
   * spread is universally zero and the failure is nowhere near the boundary; a diagnostic that
   * cries wolf gets turned off.
   */
  public static final double DIVERGENCE_RATIO = 0.25;

  /** Per (shape, method, tier): the size of the first non-OSR compilation seen. */
  private final Map<String, Long> baselines = new ConcurrentHashMap<>();

  /**
   * Keys already reported. A method that recompiles in a loop must not be able to flood the log,
   * so each key speaks once however often it diverges; the counter keeps counting.
   */
  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  private final LongAdder divergences = new LongAdder();

  /**
   * Non-OSR kernel compilations seen. Not a health signal on its own - it is how the opt-in
   * end-to-end test tells "the stream is wired to the JVM that is compiling" apart from "nothing
   * diverged", which are the same observation through {@link #divergenceCount()} alone.
   */
  private final LongAdder observed = new LongAdder();

  private final RecordingStream stream;

  private VarkaCompilationWatch(RecordingStream stream) {
    this.stream = stream;
  }

  /**
   * Opens the stream, or returns a watch that does nothing.
   *
   * <p>JFR can be unavailable or disabled in a deployment, and a diagnostic must never be the
   * reason a JVM fails to start. Anything thrown here is logged once and swallowed; the returned
   * watch reports {@link #isRunning()} false and is inert.
   */
  public static VarkaCompilationWatch start() {
    try {
      RecordingStream stream = new RecordingStream();
      VarkaCompilationWatch watch = new VarkaCompilationWatch(stream);
      stream.enable("jdk.Compilation");
      stream.onEvent("jdk.Compilation", watch::onCompilation);
      stream.startAsync();
      LOG.info("Varka compilation watch started");
      return watch;
    } catch (Throwable t) {
      // Throwable rather than Exception: a missing jdk.jfr in a stripped runtime surfaces as an
      // Error, and this is exactly the case that must degrade to silence.
      LOG.warn("Varka compilation watch could not start; compiled-size divergence will not be "
          + "reported", t);
      return inert();
    }
  }

  /**
   * The watch {@link #start()} falls back to when JFR is unavailable: no stream, no thread, and
   * every method still safe to call. Package-private so the suite can assert on the object that
   * path actually produces, rather than on a mock of it.
   */
  static VarkaCompilationWatch inert() {
    return new VarkaCompilationWatch(null);
  }

  /**
   * Whether a stream is actually open. False for the inert watch {@link #start()}
   * falls back to when JFR is unavailable.
   */
  public boolean isRunning() {
    return stream != null;
  }

  /** How many distinct (shape, method, tier) keys have diverged, for the metrics surface. */
  public long divergenceCount() {
    return divergences.sum();
  }

  /** Non-OSR kernel compilations this watch has seen; see {@link #observed}. */
  long observedCount() {
    return observed.sum();
  }

  /**
   * The baseline size recorded for each (shape, method, tier) key so far, read-only.
   *
   * <p>Package-private, and it exists for the measurement rather than for the feature: section 3
   * of {@code PLAN_TASK_50.md} has to establish what the *healthy* spread of these sizes is
   * before {@link #DIVERGENCE_RATIO} can be defended, and that means reading them out of real
   * JVMs rather than reasoning about them.
   */
  Map<String, Long> baselines() {
    return java.util.Collections.unmodifiableMap(baselines);
  }

  @Override
  public void close() {
    if (stream != null) {
      stream.close();
    }
  }

  /**
   * The JFR side, kept as thin as it can be: {@code jdk.Compilation} fires for every method the
   * JVM compiles, so the filter has to run here and has to be cheap. Reading the fields is what
   * costs, and the class-name test comes first for that reason.
   *
   * <p>The success field is spelled {@code succeded} in the JDK's own event metadata. That is not
   * a typo here; asking for the correctly spelled name throws.
   */
  private void onCompilation(RecordedEvent event) {
    try {
      RecordedMethod method = event.getValue("method");
      if (method == null) {
        return;
      }
      String className = method.getType().getName();
      if (!className.startsWith(VarkaShapeCacheImpl.CLASS_NAME_PREFIX)) {
        return;
      }
      if (event.getBoolean("isOsr") || !event.getBoolean("succeded")) {
        return;
      }
      record(className, method.getName(), event.getInt("compileLevel"),
          event.getLong("codeSize"));
    } catch (Throwable t) {
      // A handler that throws would kill the stream for the rest of the JVM's life over a
      // diagnostic. One line, once, and carry on.
      if (reported.add("handler-error")) {
        LOG.warn("Varka compilation watch failed to read a jdk.Compilation event", t);
      }
    }
  }

  /**
   * The decision, separated from JFR so it can be tested without one. Package-private because the
   * suite drives it directly: everything above this is field plumbing, and everything that can be
   * wrong in an interesting way is below.
   */
  void record(String className, String methodName, int compileLevel, long codeSize) {
    String key = keyFor(className, methodName, compileLevel);
    if (key == null || codeSize <= 0) {
      return;
    }
    observed.increment();
    Long baseline = baselines.putIfAbsent(key, codeSize);
    if (baseline == null) {
      return;
    }
    long delta = Math.abs(codeSize - baseline);
    if (delta * 1.0 <= baseline * DIVERGENCE_RATIO) {
      return;
    }
    divergences.increment();
    String shapeHash = className.substring(VarkaShapeCacheImpl.CLASS_NAME_PREFIX.length());
    if (reported.add(key)) {
      // Concatenated rather than parameterised: SparkLogger's warn takes MDC values, not plain
      // objects, and none of these five fields has a LogKey worth inventing for a diagnostic
      // that fires at most once per method per JVM.
      LOG.warn("Varka kernel " + shapeHash + "::" + methodName + " compiled at tier "
          + compileLevel + " to " + codeSize + " bytes, against " + baseline + " for the same "
          + "bytecode earlier in this JVM. Identical bytecode compiling to a materially "
          + "different size is a register allocation difference, not a different lowering; "
          + "see PLAN_TASK_50.md.");
    }
    VarkaCompilationDivergenceEvent divergence = new VarkaCompilationDivergenceEvent();
    if (divergence.shouldCommit()) {
      divergence.shapeHash = shapeHash;
      divergence.methodName = methodName;
      divergence.compileLevel = compileLevel;
      divergence.baselineCodeSize = baseline;
      divergence.observedCodeSize = codeSize;
      divergence.commit();
    }
  }

  /**
   * The (shape, method, tier) key, or {@code null} when the class is not a generated kernel.
   *
   * <p>Package-private and tested directly: it is the part that breaks silently if the generated
   * naming scheme moves, and it is pure string handling, so it does not need a JVM compiling
   * anything to be checked.
   */
  static String keyFor(String className, String methodName, int compileLevel) {
    if (className == null || methodName == null
        || !className.startsWith(VarkaShapeCacheImpl.CLASS_NAME_PREFIX)) {
      return null;
    }
    String shapeHash = className.substring(VarkaShapeCacheImpl.CLASS_NAME_PREFIX.length());
    if (shapeHash.isEmpty()) {
      return null;
    }
    return shapeHash + '#' + methodName + '#' + compileLevel;
  }
}
