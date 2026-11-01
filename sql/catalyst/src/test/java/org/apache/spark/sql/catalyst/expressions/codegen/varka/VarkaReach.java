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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The option matrix's reach filter for the suites that run IR trees (VARKA-507): under a
 * configuration, a case the defaults run has already run - the same tree over the same inputs,
 * emitted to the same bytes - is not run again.
 *
 * <p>A tree case's answer is a function of its emitted class and its inputs: the reference
 * evaluator and the Spark answers it is compared with read the tree and the batch, never the
 * options. So when a case's bytes under this JVM's configuration equal its bytes under the options
 * the defaults run gave the same case, the defaults run has compared exactly this class on exactly
 * these inputs, and every configuration still meets every tree. The caller says which options the
 * defaults run gave the case, by deriving them from {@link VarkaEmitOptions#DEFAULTS} the way it
 * derives the case's own from {@code VarkaMatrix.base}: a fuzzer that draws options per case draws
 * them again over the defaults. Options that are equal need no second emission. Equal bytes are
 * enough only where nothing reads the options as the kernel runs: {@code VarkaKernelChecks} reads
 * none, while {@code VarkaCompiledCheck} and Spark's evaluator read {@code exactRefusalStatus}, so
 * the fuzzer, which runs its cases through the first, compares options alone.
 *
 * <p>The filter is a caller's choice, made where a test asserts that its cases agree with the
 * reference: a test that expects a planted fault to be found must run its cases. It is off in the
 * defaults run, which runs everything, and with {@code -Dvarka.matrix.reach=false}. What C2 makes
 * of a class can depend on what it compiled before (rows 383 and 404), and that is not filtered:
 * the defaults run's order is the one the class was checked in.
 */
public final class VarkaReach {

  /** The system property that turns the filter off: {@code -Dvarka.matrix.reach=false}. */
  public static final String PROPERTY = "varka.matrix.reach";

  private static final boolean ON = !"false".equals(System.getProperty(PROPERTY));

  private VarkaReach() {}

  /** How a suite's cases fared under the filter, printed once the suite has run. */
  public static final class Tally {
    final AtomicLong sameOptions = new AtomicLong();
    final AtomicLong sameBytes = new AtomicLong();
    final AtomicLong moved = new AtomicLong();

    /**
     * One line for the run's log: the cases the defaults run had run, by equal options and by
     * equal bytes, and the ones this configuration moved, which ran.
     */
    public String line(String suite) {
      return "VARKA_REACH\t" + suite + "\t" + VarkaMatrixRuns.CONFIG + "\tsame options "
          + sameOptions.get() + "\tsame bytes " + sameBytes.get() + "\tmoved " + moved.get();
    }

    /** The cases that ran under the configuration because it moved them. */
    public long moved() {
      return moved.get();
    }

    /** The cases skipped as the defaults run's own. */
    public long skipped() {
      return sameOptions.get() + sameBytes.get();
    }
  }

  /**
   * Whether the defaults run has run this case, so that this JVM need not. {@code options} are the
   * case's, {@code atDefaults} the ones the defaults run gave it, {@code bytes} its class under
   * {@code options}, and {@code emit} emits it under other options - to the same class name, so
   * that only the options can tell two emissions apart - throwing where the emitter declines.
   * {@code bytes} may be null for a caller that compares options alone.
   */
  public static boolean ranAtDefaults(VarkaEmitOptions options, VarkaEmitOptions atDefaults,
      byte[] bytes, Function<VarkaEmitOptions, byte[]> emit, Tally tally) {
    return ranAtDefaults(VarkaMatrixRuns.CONFIG, ON, options, atDefaults, bytes, emit, tally);
  }

  /** {@link #ranAtDefaults} with the configuration and the switch named: the seam its test uses. */
  static boolean ranAtDefaults(String config, boolean on, VarkaEmitOptions options,
      VarkaEmitOptions atDefaults, byte[] bytes, Function<VarkaEmitOptions, byte[]> emit,
      Tally tally) {
    if (!on || config.isEmpty()) {
      return false;
    }
    if (options.equals(atDefaults)) {
      tally.sameOptions.incrementAndGet();
      return true;
    }
    if (bytes != null && emit != null) {
      byte[] theirs;
      try {
        theirs = emit.apply(atDefaults);
      } catch (RuntimeException declined) {
        // A form the defaults decline is one this configuration has moved into the emitter.
        theirs = null;
      }
      if (theirs != null && Arrays.equals(bytes, theirs)) {
        tally.sameBytes.incrementAndGet();
        return true;
      }
    }
    tally.moved.incrementAndGet();
    return false;
  }
}
