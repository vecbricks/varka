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


package org.apache.spark.sql.execution.varka;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAllocationSampler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrorContext;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFallbackEvent;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelAllocationEvent;
import org.apache.spark.sql.execution.metric.SQLMetric;

/**
 * Per-batch fallback accounting, shared by every Varka evaluator (VARKA-21 review: the nodes
 * carried byte-identical copies of these blocks, which had already begun to drift). Each method
 * counts and events one batch under its actual cause; the caller then takes its own fallback
 * path. Also the species-pollution check: a kernel that boxes still answers correctly, so no
 * fallback path and no differential test can see it - only its allocation rate can, sampled on
 * the schedule {@link VarkaAllocationSampler} explains, never on every batch.
 *
 * <p>The kernel's identity is rendered only when a line or an event needs it: rendering hashes the
 * canonical IR, which the metered-but-uneventful path must not pay. So it arrives as a supplier,
 * built once per evaluator, and so do the emit options the event names.
 *
 * <p>A failure's warning ends with the context record's block ({@link VarkaErrorContext},
 * VARKA-314), which the evaluator builds in its catch and passes in: the options the kernel was
 * built under and the batch it failed on, beside the identity the sentence already names.
 */
public final class VarkaFallbackAccounting {

  private static final SparkLogger LOG =
      SparkLoggerFactory.getLogger(VarkaFallbackAccounting.class);

  /**
   * The counters each cause adds to, each null where the node registered none, and the declined
   * batches by reason, in {@link #REASONS}' order or empty, each a {@link VarkaReasonMetric}.
   */
  public record Counters(
      SQLMetric kernelFailures,
      SQLMetric rowPathFailures,
      SQLMetric declined,
      SQLMetric nonArrow,
      SQLMetric suspectAllocationSamples,
      List<SQLMetric> declinedByReason) {

    public Counters {
      declinedByReason = declinedByReason == null ? List.of() : List.copyOf(declinedByReason);
    }

    /** The counters without the reason metrics: what a suite that reads the tallies passes. */
    public Counters(SQLMetric kernelFailures, SQLMetric rowPathFailures, SQLMetric declined,
        SQLMetric nonArrow, SQLMetric suspectAllocationSamples) {
      this(kernelFailures, rowPathFailures, declined, nonArrow, suspectAllocationSamples,
          List.of());
    }
  }

  private final Counters counters;
  private final boolean exactStatus;
  private final Supplier<String> kernelIdentity;
  private final Supplier<String> options;
  private final VarkaAllocationSampler.Tracker allocationTracker =
      new VarkaAllocationSampler.Tracker();
  private final boolean allocationSampling = VarkaAllocationSampler.supported();
  private long kernelBatches;
  /** Declined batches by status bit, {@link #STATUS_NAMES}' order; see {@link #declinedBatch}. */
  private final long[] declinedByBit = new long[STATUS_BITS.length];
  /**
   * The failures already warned of, one key a kernel and cause: a kernel that fails on every
   * batch warns once, with its stack trace, and every later batch is counted and evented but
   * logged at debug, so a failing kernel over a million one-row batches cannot write a million
   * stack traces.
   */
  private final Set<String> warned = new HashSet<>();

  /**
   * The decline status bits, the kernels' refusal reasons ({@code VarkaFusedKernel}, VARKA-304)
   * and then the evaluator's own ({@link VarkaKernelRunner}), with the name a log line and a
   * count give each.
   */
  private static final int[] STATUS_BITS = {
      VarkaFusedKernel.STATUS_DAY_PRODUCER, VarkaFusedKernel.STATUS_MONTH_COUNT,
      VarkaFusedKernel.STATUS_MAKE_DATE, VarkaFusedKernel.STATUS_INT_OVERFLOW,
      VarkaFusedKernel.STATUS_DAY_RANGE, VarkaFusedKernel.STATUS_VALUE_RANGE,
      VarkaFusedKernel.STATUS_DECIMAL_SLOT,
      VarkaKernelRunner.STATUS_INPUT_BOUND, VarkaKernelRunner.STATUS_DERIVED_INPUT,
      VarkaKernelRunner.STATUS_FORCED};
  private static final String[] STATUS_NAMES = {
      "day-producer", "month-count", "make-date", "int-overflow", "day-range", "value-range",
      "decimal-slot", "input-bound", "derived-input", "forced"};

  /** The reason a status bit outside {@link #STATUS_BITS} is counted under. */
  public static final String UNRECOGNISED = "unrecognised";

  /**
   * The refusal reasons' names, in the order of the reason metrics: the status bits', then
   * {@link #UNRECOGNISED} for a bit no name covers, so that a declined batch always lands under
   * some reason rather than leaving every reason at 0.
   */
  public static final List<String> REASONS;

  private static final int KNOWN_BITS;

  static {
    var names = new ArrayList<>(List.of(STATUS_NAMES));
    names.add(UNRECOGNISED);
    REASONS = List.copyOf(names);
    int known = 0;
    for (int bit : STATUS_BITS) {
      known |= bit;
    }
    KNOWN_BITS = known;
  }

  /**
   * @param exactStatus whether the kernels were emitted under {@code exactRefusalStatus}, so that
   *                    every bit of a status names a reason that fired
   * @param options the emit options that differ from the defaults, rendered as
   *                {@link VarkaErrorContext#renderOptions} renders them, for the event
   */
  public VarkaFallbackAccounting(Counters counters, boolean exactStatus,
      Supplier<String> kernelIdentity, Supplier<String> options) {
    this.counters = counters;
    this.exactStatus = exactStatus;
    this.kernelIdentity = kernelIdentity;
    this.options = options;
  }

  /** The accounting of kernels emitted without {@code exactRefusalStatus}. */
  public VarkaFallbackAccounting(
      Counters counters, Supplier<String> kernelIdentity, Supplier<String> options) {
    this(counters, false, kernelIdentity, options);
  }

  private static void add(SQLMetric metric) {
    if (metric != null) {
      metric.add(1L);
    }
  }

  /**
   * The ghost fallback's bookkeeping: an error from the emitted kernel itself. {@code kernel}
   * names a further kernel of the projection when that is the one that failed, and is null for
   * the evaluator's own. {@code ctx} is the failure's context record, logged after the sentence;
   * null logs the sentence alone.
   */
  public void kernelFailure(Throwable e, String kernel, @Nullable VarkaErrorContext ctx) {
    String identity = kernel != null ? kernel : kernelIdentity.get();
    log("kernel:" + identity, "The Varka SIMD kernels " + identity
        + " failed on this batch; falling back to the per-row path.", ctx, e);
    add(counters.kernelFailures());
    fallbackEvent(VarkaFallbackEvent.KERNEL_FAILURE, () -> identity, options,
        e.getClass().getName());
  }

  /**
   * A catchable failure from the per-row machinery running beside the kernel - the residual or
   * merge projection's compile or evaluation - which the VARKA-21 review split out of the kernel
   * metric: it is not the kernel's failure, and a throwing lazy re-runs its initializer, so
   * counting it there would inflate the ghost-fallback metric on every batch. Counted under its
   * own bounded cause metric, evented and logged, with {@code ctx} as {@link #kernelFailure}
   * logs it.
   */
  public void rowPathFailure(Throwable e, @Nullable VarkaErrorContext ctx) {
    String identity = kernelIdentity.get();
    log("row-path:" + identity, "The per-row machinery beside the Varka kernel " + identity
        + " failed on this batch; falling back to the per-row path.", ctx, e);
    add(counters.rowPathFailures());
    fallbackEvent(VarkaFallbackEvent.ROW_PATH_FAILURE, kernelIdentity, options,
        e.getClass().getName());
  }

  /**
   * A failure's sentence and context block: a warning the first time {@code key} fails in this
   * evaluator, which says that the later ones are counted rather than logged, and debug after.
   */
  private void log(String key, String sentence, @Nullable VarkaErrorContext ctx, Throwable e) {
    if (warned.add(key)) {
      LOG.warn(sentence + " Later failures of this kind in this evaluator are counted in the"
          + " fallback metrics and logged at debug." + block(ctx), e);
    } else if (LOG.isDebugEnabled()) {
      LOG.debug(sentence + block(ctx), e);
    }
  }

  /** The context record's block on lines of its own after a warning's sentence; empty for none. */
  public static String block(@Nullable VarkaErrorContext ctx) {
    return ctx == null ? "" : "\n" + ctx.render();
  }

  /**
   * A batch the kernel itself declined: a lowering that is correct only over part of its input
   * domain met a value outside it and reported it rather than publishing an answer it does not
   * have. Logged at debug rather than warning: unlike the ghost fallback this is a designed
   * outcome, not a defect, and a batch of far-future dates would otherwise fill the log. The
   * event's third field is its exceptionClass, and a declined batch has no exception: the status
   * is in the log line, where it belongs.
   */
  public void declinedBatch(int status, String kernel) {
    if (LOG.isDebugEnabled()) {
      String identity = kernel != null ? kernel : kernelIdentity.get();
      LOG.debug("The Varka SIMD kernels " + identity + " declined this batch (status " + status
          + ": " + describeStatus(status) + "); falling back to the per-row path.");
    }
    add(counters.declined());
    int unrecognised = status & ~KNOWN_BITS;
    // One reason is exact under either setting: a method that folds reasons reports all it
    // holds, so a lone bit comes from a method of one reason, or from the evaluator, which raises
    // its own one at a time. Several are exact only under exactRefusalStatus. The unrecognised
    // bits count as one reason, so that a batch naming only them still lands under one.
    int named = Integer.bitCount(status & KNOWN_BITS) + (unrecognised != 0 ? 1 : 0);
    long weight = exactStatus || named == 1 ? 1L : VarkaReasonMetric.UNATTRIBUTED;
    List<SQLMetric> metrics = counters.declinedByReason();
    for (int i = 0; i < STATUS_BITS.length; i++) {
      if ((status & STATUS_BITS[i]) != 0) {
        declinedByBit[i]++;
        if (!metrics.isEmpty()) {
          metrics.get(i).add(weight);
        }
      }
    }
    if (unrecognised != 0 && !metrics.isEmpty()) {
      metrics.get(STATUS_BITS.length).add(weight);
    }
    fallbackEvent(VarkaFallbackEvent.RANGE_DECLINED, kernel != null ? () -> kernel : kernelIdentity,
        options, "", status);
  }

  /**
   * How many declined batches carried each status bit, by its name: a batch counts once against
   * every bit its status sets. A method that folds guards of several reasons into one mask
   * reports them all, so under the default emit options a count is an upper bound for a reason
   * that shares a method with another, and exact under {@code exactRefusalStatus}. The reason
   * metrics keep the exact and the unattributed declines apart ({@link VarkaReasonMetric}).
   */
  public Map<String, Long> declinedByReason() {
    var counts = new LinkedHashMap<String, Long>();
    for (int i = 0; i < STATUS_BITS.length; i++) {
      counts.put(STATUS_NAMES[i], declinedByBit[i]);
    }
    return counts;
  }

  /** The names of the bits {@code status} sets, joined by {@code |}; unknown bits in hex. */
  public static String describeStatus(int status) {
    var names = new StringJoiner("|");
    int rest = status;
    for (int i = 0; i < STATUS_BITS.length; i++) {
      if ((status & STATUS_BITS[i]) != 0) {
        names.add(STATUS_NAMES[i]);
        rest &= ~STATUS_BITS[i];
      }
    }
    if (rest != 0) {
      names.add("0x" + Integer.toHexString(rest));
    }
    return names.toString();
  }

  /** A non-empty batch whose referenced columns are not Arrow vectors the kernels read. */
  public void nonArrowBatch() {
    add(counters.nonArrow());
    fallbackEvent(VarkaFallbackEvent.NON_ARROW_BATCH, kernelIdentity, options, "");
  }

  /**
   * Counts one kernel batch and says whether the allocation sampler measures it, on the schedule
   * suites can set ({@code VarkaEvaluatorBase.allocationSchedule}).
   */
  public boolean sampleDue(VarkaAllocationSampler.Schedule schedule) {
    kernelBatches++;
    return allocationSampling && schedule.due(kernelBatches);
  }

  /** One allocation sample of the kernel call: evented always, counted and warned when suspect. */
  public void allocationSample(long allocatedBytes, int rows) {
    boolean suspect = VarkaAllocationSampler.suspect(allocatedBytes, rows);
    allocationEvent(kernelIdentity, kernelBatches, rows, allocatedBytes, suspect);
    if (suspect) {
      add(counters.suspectAllocationSamples());
      if (allocationTracker.record(true)) {
        LOG.warn("The Varka SIMD kernels " + kernelIdentity.get() + " allocated " + allocatedBytes
            + " bytes over a " + rows + "-row batch (batch " + kernelBatches + "), and did so on"
            + " the previous sample too. A kernel that runs as emitted allocates nothing per row;"
            + " this rate means the Vector API is boxing its vectors, most likely because two"
            + " vector species of one lane type ran hot in this JVM (SKILLS.md, the"
            + " species-pollution section). Results are still correct; the kernel is several"
            + " times slower than it should be.");
      }
    } else {
      allocationTracker.record(false);
    }
  }

  /**
   * Emits the VARKA-22 fallback JFR event, populated only while a recording has it enabled;
   * {@code exceptionClass} is empty for the non-Arrow and declined causes, which are not errors.
   */
  public static void fallbackEvent(String cause, Supplier<String> kernelIdentity,
      Supplier<String> options, String exceptionClass) {
    fallbackEvent(cause, kernelIdentity, options, exceptionClass, 0);
  }

  /** The fallback event with a declined batch's status word; zero for every other cause. */
  public static void fallbackEvent(String cause, Supplier<String> kernelIdentity,
      Supplier<String> options, String exceptionClass, int status) {
    var event = new VarkaFallbackEvent();
    if (event.isEnabled()) {
      event.cause = cause;
      event.kernelIdentity = kernelIdentity.get();
      event.options = options.get();
      event.exceptionClass = exceptionClass;
      event.status = status;
      event.commit();
    }
  }

  /** The allocation-sample event; populated only while a recording has it enabled. */
  private static void allocationEvent(Supplier<String> kernelIdentity, long batchIndex, int rows,
      long allocatedBytes, boolean suspect) {
    var event = new VarkaKernelAllocationEvent();
    if (event.isEnabled()) {
      event.kernelIdentity = kernelIdentity.get();
      event.batchIndex = batchIndex;
      event.rows = rows;
      event.allocatedBytes = allocatedBytes;
      event.suspect = suspect;
      event.commit();
    }
  }
}
