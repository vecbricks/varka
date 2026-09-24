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
 * <p>Thrown by {@link VarkaLoopEmitter#emit} under {@link VarkaEmitOptions#methodByteBudget}
 * when a method is still over a limit after every regroup the emitter can make: a group of one
 * output whose method exceeds the budget, a driver over it, or a class over the class-file
 * caps. It is an {@link IllegalArgumentException} so that every caller's existing contract
 * holds - the emitter refused, fall back to the row engine - and a type of its own so that a
 * caller can tell a size decline from a structural one and report it as such. {@link #outputs}
 * is what {@code VarkaExpressionCompiler} acts on at plan time: the outputs whose own group
 * cannot fit, which it demotes to the row path with this reason so that the rest of the
 * projection fuses without them; it is empty for a class-wide limit, where no output is to blame
 * on its own. The emitter's other refusals are not declines: they reject IR the compiler never
 * builds, and stay plain {@code IllegalArgumentException}s ({@code PLAN_TASK_169.md} 2.1).
 */
public final class VarkaEmitDeclined extends IllegalArgumentException {

  private final List<Integer> outputs;

  VarkaEmitDeclined(String reason, List<Integer> outputs) {
    super(reason);
    this.outputs = List.copyOf(outputs);
  }

  /** The indices of the outputs whose own group is over the limit; empty for a class-wide one. */
  public List<Integer> outputs() {
    return outputs;
  }
}
