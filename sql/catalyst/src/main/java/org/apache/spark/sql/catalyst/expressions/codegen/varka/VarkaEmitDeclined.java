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

/**
 * The emitter's refusal of a shape on a limit the JVM enforces, with the reason and the
 * outputs it names.
 *
 * <p>Thrown by {@link VarkaLoopEmitter#emit} when a method is still over a limit after every
 * regroup the emitter can make: under {@link VarkaEmitOptions#methodByteBudget}, a group of one
 * output whose method exceeds the budget or a driver over it; and whatever the budget, a method
 * the class-file format cannot hold - which the Class-File API refuses while the class is
 * assembled, and the emitter reads as the measurement it never got to take
 * ({@code VARKA-219.md}) - or a class over the format's other caps. It is an
 * {@link IllegalArgumentException} so that every caller's existing contract
 * holds - the emitter refused, fall back to the row engine - and a type of its own so that a
 * caller can tell a size decline from a structural one and report it as such. {@link #outputs}
 * is what {@code VarkaExpressionCompiler} acts on at plan time: the outputs whose own group
 * cannot fit, which it demotes to the row path with this reason so that the rest of the
 * projection fuses without them; it is empty for a class-wide limit, where no output is to blame
 * on its own. The emitter's other refusals are not declines: they reject IR the compiler never
 * builds, and stay plain {@code IllegalArgumentException}s ({@code VARKA-169.md} 2.1).
 *
 * <p>Under {@link VarkaEmitOptions#planSize} a class-wide decline on the driver also carries
 * {@link #plannedCut}: how many of the outputs, from the first, one class serves by the plan's
 * reading of the driver over the grouping the emitter formed, so that the compiler cuts the
 * projection there in one step rather than bisecting it (VARKA-236). It is -1 where the plan has
 * no cut: a decline on a limit the plan cannot read, such as the class-file caps.
 */
public final class VarkaEmitDeclined extends IllegalArgumentException {

  private final List<Integer> outputs;
  private final int plannedCut;

  VarkaEmitDeclined(String reason, List<Integer> outputs) {
    this(reason, outputs, -1);
  }

  VarkaEmitDeclined(String reason, List<Integer> outputs, int plannedCut) {
    super(reason);
    this.outputs = List.copyOf(outputs);
    this.plannedCut = plannedCut;
  }

  /** The indices of the outputs whose own group is over the limit; empty for a class-wide one. */
  public List<Integer> outputs() {
    return outputs;
  }

  /**
   * How many leading outputs one class serves by the plan, for the compiler to keep in this
   * kernel; -1 where the plan has no cut. Positive and fewer than the outputs when present.
   */
  public int plannedCut() {
    return plannedCut;
  }
}
