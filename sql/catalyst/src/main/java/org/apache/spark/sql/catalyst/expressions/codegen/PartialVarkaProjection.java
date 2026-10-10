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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.ForwardedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.FusedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.KernelOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.ResidualOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;

/**
 * A projection classified entry by entry: {@code specs} has one entry per projectList position,
 * in order, and {@code fused} is the sub-projection of just the {@link FusedOutput} entries - their
 * kernel-input and literal tables cover only what the fused trees reference, so a residual entry
 * constrains neither the emitted loop nor {@code canRun}'s Arrow check. {@code more} holds the
 * further kernels under {@link VarkaEmitOptions#severalKernels}, whose entries are
 * {@link KernelOutput}.
 *
 * <p>{@code declines} maps the position of each {@link ResidualOutput} entry to why it declined,
 * for the exec nodes' verbose {@code EXPLAIN}; it is diagnostics only and no execution path reads
 * it.
 *
 * <p>Internal to Spark SQL; public for {@code sql/core}.
 */
public record PartialVarkaProjection(
    List<VarkaOutputSpec> specs,
    CompiledVarkaProjection fused,
    Map<Integer, VarkaDecline> declines,
    List<CompiledVarkaProjection> more) {

  public PartialVarkaProjection {
    specs = List.copyOf(specs);
    java.util.Objects.requireNonNull(fused, "fused");
    declines = Map.copyOf(declines);
    more = List.copyOf(more);
  }

  /**
   * Every kernel the projection runs, the first one first: one unless the entries were over what
   * one kernel serves and {@link VarkaEmitOptions#severalKernels} split them
   * ({@code VARKA-190.md} 11).
   */
  public List<CompiledVarkaProjection> kernels() {
    var all = new ArrayList<CompiledVarkaProjection>(1 + more.size());
    all.add(fused);
    all.addAll(more);
    return List.copyOf(all);
  }

  /**
   * The position of a kernel column among every kernel's columns laid end to end, kernel by kernel
   * - how the row node's merge and {@code projectFused} number them - or empty for an entry no
   * kernel computes.
   */
  public OptionalInt columnIndex(VarkaOutputSpec spec) {
    return switch (spec) {
      case FusedOutput f -> OptionalInt.of(f.fusedIndex());
      case KernelOutput k -> {
        int before = fused.outputs().size();
        for (int j = 0; j < k.kernel() - 1; j++) {
          before += more.get(j).outputs().size();
        }
        yield OptionalInt.of(before + k.fusedIndex());
      }
      case ForwardedOutput f -> OptionalInt.empty();
      case ResidualOutput r -> OptionalInt.empty();
    };
  }
}
