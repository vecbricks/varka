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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;

/**
 * How one projection entry is served under partial eligibility: computed by the fused kernel,
 * computed by a further kernel, forwarded as the input's own vector, or evaluated per row by the
 * residual projection. A closed set, so every consumer is an exhaustive {@code switch}.
 *
 * <p>Internal to Spark SQL: public only because the evaluators in {@code sql/core} read it, and a
 * Java type cannot say {@code private[sql]}.
 */
public sealed interface VarkaOutputSpec {

  /** The one {@link ResidualOutput}; the record has no components, so every instance is equal. */
  ResidualOutput RESIDUAL = new ResidualOutput();

  /** A kernel column: output {@code fusedIndex} of the fused sub-projection. */
  record FusedOutput(int fusedIndex) implements VarkaOutputSpec { }

  /**
   * A column of a further kernel under {@link VarkaEmitOptions#severalKernels}: output
   * {@code fusedIndex} of {@code PartialVarkaProjection.more().get(kernel - 1)}. Kernel 0 is the
   * first one, whose outputs stay {@link FusedOutput}, so a projection that fits one kernel is
   * classified as it always was.
   */
  record KernelOutput(int kernel, int fusedIndex) implements VarkaOutputSpec {
    public KernelOutput {
      if (kernel < 1) {
        throw new IllegalArgumentException(
            "kernel 0's outputs are FusedOutput, not KernelOutput(" + kernel + ", ...)");
      }
    }
  }

  /**
   * A bare column reference, forwarded zero-copy from child output ordinal {@code childOrdinal}.
   * Any type, not just dates: forwarding never reads the values, so it does not care about lanes.
   */
  record ForwardedOutput(int childOrdinal) implements VarkaOutputSpec { }

  /** Everything else: evaluated per row, one pass for all residual entries together. */
  record ResidualOutput() implements VarkaOutputSpec { }
}
