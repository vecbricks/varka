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

package org.apache.spark.sql.catalyst.expressions.codegen;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import scala.util.control.NonFatal;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitDeclined;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrorContext;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmup;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaReproduction;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeCache;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeKey;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.internal.SQLConf;

/**
 * Whether the emitter serves a compiled kernel in bytes, asked of the emitter itself. The weight
 * caps admit an entry before anything is built, and weight does not bound size
 * ({@code VARKA-87.md}), so a shape the caps admit can still be one the emitter's method budget
 * declines - a single output whose own method is past it, or a driver over it. Asking at
 * classification moves that decline from every task on the executor to the plan, where EXPLAIN
 * shows it ({@code VARKA-169.md}).
 *
 * <p>The question goes through the shape cache with the key the evaluator will build, so a shape
 * is built once per JVM whoever asks first: the compiler runs at planning, for EXPLAIN and once
 * per task on the executor, and a direct emission here would put a class build on every one of
 * those. It is answered without defining a class (VARKA-237): the cache builds and measures the
 * shape and holds its bytes, and the evaluator's first lookup defines the class from them, so
 * planning on the Spark driver loads nothing, and a bisection's probes leave no class behind but
 * the one that runs. A decline names the outputs whose own group cannot fit; a class-wide one
 * names none, and the caller demotes outputs from the end, since the driver it leaves over the
 * budget grows with their number ({@code VarkaExpressionCompiler} bisects, or cuts in one step
 * where the decline carries the plan's cut, VARKA-236). Any other failure of the build admits the
 * shape, and is logged once per JVM: the executor meets it behind the ghost fallback. A class
 * that builds but fails to define or link is not met here at all, since nothing is defined: the
 * executors meet it on its first batch, the same fallback; the
 * tests verify every admitted shape's bytes instead ({@link VarkaShapeCache#admit}).
 */
final class VarkaSizeAdmission {

  // Under the classifier's name, as when this was a part of it, so that a logging configuration
  // that names the classifier still governs the warning.
  private static final SparkLogger LOG =
      SparkLoggerFactory.getLogger(VarkaExpressionCompiler.class);

  /**
   * The plan-time emitter failures already logged in this JVM, by exception class and the frame
   * that threw: bounded by the emitter's code, where a key by message would grow with every
   * distinct shape a long-lived driver plans.
   */
  private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

  private VarkaSizeAdmission() {
  }

  /**
   * The emitter's decline of a kernel: the fused outputs it names as over a limit, empty for a
   * class-wide decline; the reason to record against each demoted entry; and, under
   * {@code planSize}, how many of the fused outputs one class serves, read off the driver (see
   * {@link VarkaEmitDeclined#plannedCut}).
   */
  record Decline(List<Integer> outputs, String reason, int plannedCut) {
    Decline {
      outputs = List.copyOf(outputs);
    }
  }

  /** Empty when the emitter serves {@code fused}, and otherwise its decline. */
  static Optional<Decline> admit(CompiledVarkaProjection fused, VarkaEmitOptions options) {
    // Asked with the budget off as well: the legacy form is built once and never measured, so
    // the only decline it can give is the class-file cap's, and that one is worth a residual at
    // plan time rather than a per-task fallback on the executor (VARKA-219.md 10).
    VarkaShapeKey key = new VarkaShapeKey(fused.outputs(), fused.inputOrdinals().size(),
        fused.numLiterals(), options,
        VarkaKernelWarmup.warms(SQLConf.get().varkaWarmupEnabled()));
    try {
      VarkaShapeCache.admit(key);
      return Optional.empty();
    } catch (VarkaEmitDeclined d) {
      // The message's first clause, up to its first "; ".
      String message = d.getMessage();
      int cut = message.indexOf("; ");
      String reason = "over the emitter's method budget ("
          + (cut < 0 ? message : message.substring(0, cut)) + ")";
      return Optional.of(new Decline(d.outputs(), reason, d.plannedCut()));
    } catch (Throwable t) {
      if (t instanceof VerifyError) {
        // Under test the admission verifies the bytes it built, so a class the verifier rejects
        // fails the query here, at planning, before any evaluator could record it: the
        // reproduction file is written here instead (VARKA-315), and the error rethrown as it is.
        reproduce(t, fused, key);
      }
      if (!NonFatal.apply(t)) {
        throw t;
      }
      // Not a decline, so not a shape the emitter refuses by design: an emitter bug, or a
      // failure of the JVM's, which the executor meets behind the ghost fallback as before.
      // Said once per JVM here as well, so that a plan admitting a shape the emitter cannot
      // build shows on the driver and not only in a task's log (VARKA-219.md 3.1).
      StackTraceElement[] frames = t.getStackTrace();
      String where = frames.length > 0 ? frames[0].toString() : "";
      if (LOGGED_FAILURES.add(t.getClass().getName() + "@" + where)) {
        LOG.warn("The Varka emitter failed at plan time on a shape the compiler admitted, "
            + "which the executor will meet behind the fallback: "
            + Objects.requireNonNullElse(VarkaErrors.siteText(t), String.valueOf(t.getMessage())),
            t);
      }
      return Optional.empty();
    }
  }

  /**
   * The reproduction file of a class the admission's verification rejected. It cannot throw: the
   * error it records is rethrown after it.
   */
  private static void reproduce(Throwable failure, CompiledVarkaProjection fused,
      VarkaShapeKey key) {
    try {
      String hash = VarkaShapeCache.shapeHash(key);
      var ir = new StringBuilder();
      for (VarkaVectorIR output : key.outputs()) {
        ir.append(ir.length() > 0 ? ", " : "").append(VarkaVectorIR.canonicalExact(output));
      }
      var ctx = new VarkaErrorContext(VarkaErrorContext.PLANNING,
          VarkaShapeCache.sourceFileFor(hash) + " [" + ir + "] (planning)", hash,
          VarkaErrorContext.renderOptions(key.options()), null, failure);
      VarkaReproduction.write(() -> ctx, hash, failure, key, fused.literals(),
          fused.longLiterals(), null, null);
    } catch (Throwable t) {
      VarkaReproduction.unwritten(t);
    }
  }
}
