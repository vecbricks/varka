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

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * What the Varka compiler and emitter are asked to do, shown to a test that asks (VARKA-507). The
 * option matrix's defaults run records, for each test, which configurations move anything the
 * test compiled or emitted, so that a configuration can skip the tests it cannot change. Three
 * places report: the two compile entries of {@code VarkaExpressionCompiler}, with a way to repeat
 * the compile under other options; the shape cache's lookups, hits included, since a hit emits
 * nothing; and the emitter itself, for a caller that builds its IR by hand.
 *
 * <p>No observer is set outside that recording, and then each report costs one volatile read. A
 * report made while the observer itself compiles or emits, to compare a configuration's result
 * with the defaults', is not made: see {@link #unobserved}.
 */
public final class VarkaCompileObserver {

  /** The receiver of the reports. */
  public interface Listener {
    /**
     * A compile of a plan's expressions under {@code options}, whose result is {@code result} (an
     * {@code Optional} of the compiled form); {@code inputs} are what was compiled, equal for two
     * compiles of the same expressions over the same columns, and {@code recompile} repeats the
     * compile under other options.
     */
    void compiled(Object inputs, VarkaEmitOptions options, Object result,
        Function<VarkaEmitOptions, Object> recompile);

    /** A kernel the emitter or the shape cache was asked for. */
    void emitted(List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
        VarkaEmitOptions options);
  }

  private static volatile @Nullable Listener listener;
  private static final ThreadLocal<Boolean> QUIET = ThreadLocal.withInitial(() -> false);

  private VarkaCompileObserver() {}

  /** Sets the listener, or clears it with {@code null}. */
  public static void set(@Nullable Listener l) {
    listener = l;
  }

  /** Whether a report would be heard now: a caller checks before building one. */
  public static boolean watching() {
    return active() != null;
  }

  /** The listener that should hear a report now, or null. */
  static @Nullable Listener active() {
    Listener l = listener;
    return l == null || QUIET.get() ? null : l;
  }

  /** Reports a compile; see {@link Listener#compiled}. */
  public static void compiled(Object inputs, VarkaEmitOptions options, Object result,
      Function<VarkaEmitOptions, Object> recompile) {
    Listener l = active();
    if (l != null) {
      l.compiled(inputs, options, result, recompile);
    }
  }

  /** Reports an emission or a cache lookup; see {@link Listener#emitted}. */
  static void emitted(List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
      VarkaEmitOptions options) {
    Listener l = active();
    if (l != null) {
      l.emitted(outputs, numInputs, numLiterals, options);
    }
  }

  /** Runs {@code work} with this thread's reports off: the listener's own compiles and emits. */
  public static <T> T unobserved(Supplier<T> work) {
    boolean was = QUIET.get();
    QUIET.set(true);
    try {
      return work.get();
    } finally {
      QUIET.set(was);
    }
  }
}
