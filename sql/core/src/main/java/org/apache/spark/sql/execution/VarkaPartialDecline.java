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

import java.util.List;
import java.util.Optional;

import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;

/**
 * Whether a projection that fuses in part is left whole to Spark when its consumer wants rows
 * (VARKA-613), and why: when its residual entries outnumber its fused ones.
 *
 * <p>Under a row consumer such a projection runs its kernel over each batch, then builds every
 * output row in a merge projection over the input row and the kernel's row, which evaluates the
 * residual entries per row outside whole-stage code. Spark's own plan evaluates every entry in
 * one whole-stage loop over the batch's vectors. So the kernel wins only where the work it takes
 * off the row path outweighs the batch pass and the merge's indirection it adds, and with more
 * residual entries than fused ones it does not: the day-time baseline's {@code extract} of four
 * fields, of which only the day fuses, ran at 0.7x the row path at 256 bits and 0.5x at 128 on
 * the runners, and the laptop probe ({@code VARKA-613.md} 9) found three fused arithmetic entries
 * beside one residual entry over the same column a win, so whether the residual entries share
 * the fused ones' columns does not decide it, the count does.
 *
 * <p>The rule is asked only where the consumer is known to want rows, in
 * {@link VarkaColumnarRule}'s post stage; a columnar consumer keeps every partial projection. A
 * test's forced decline ({@link VarkaEmitOptions#forceResidualAt()}) is never declined here,
 * since the force exists to run the merge path.
 */
public final class VarkaPartialDecline {

  private VarkaPartialDecline() {
  }

  /**
   * Why {@code partial} is left to Spark under a row consumer, or empty when it keeps its
   * kernel: empty for a projection whose residual entries are no more than its fused ones, those
   * of every kernel together, and under a forced decline. A forwarded column counts as neither.
   */
  public static Optional<String> reason(PartialVarkaProjection partial, VarkaEmitOptions options) {
    if (options.forceResidualAt() != 0) {
      return Optional.empty();
    }
    int fused = 0;
    int residual = 0;
    List<VarkaOutputSpec> specs = partial.specs();
    for (VarkaOutputSpec spec : specs) {
      switch (spec) {
        case VarkaOutputSpec.FusedOutput f -> fused++;
        case VarkaOutputSpec.KernelOutput k -> fused++;
        case VarkaOutputSpec.ForwardedOutput f -> { }
        case VarkaOutputSpec.ResidualOutput r -> residual++;
      }
    }
    if (residual <= fused) {
      return Optional.empty();
    }
    return Optional.of("fuses in part under a row consumer, and its residual entries outnumber "
        + "its fused ones, " + residual + " to " + fused + ", so the per-row merge would cost more "
        + "than the kernel saves (VARKA-613)");
  }
}
