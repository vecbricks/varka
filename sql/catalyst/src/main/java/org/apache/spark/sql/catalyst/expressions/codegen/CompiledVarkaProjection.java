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
import java.util.Optional;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.DataType;

/**
 * A whole projection compiled to the Varka vector IR: the trees {@code VarkaLoopEmitter} turns
 * into one fused loop, plus everything the evaluator needs to drive the emitted class - which
 * child columns it reads (dense kernel input index = position in {@code inputOrdinals}), the
 * runtime {@code scalarArgs} values (slot index = position in {@code literals}, or in
 * {@code longLiterals} for a kernel on the 64-bit lane), and each output's Spark type, which is
 * what tells a {@code datediff} day-count column ({@code IntegerType}) apart from a date column
 * when the output vectors are allocated.
 *
 * <p>Internal to Spark SQL; public for {@code sql/core}.
 */
public record CompiledVarkaProjection(
    List<VarkaVectorIR> outputs,
    List<DataType> outputTypes,
    List<Integer> inputOrdinals,
    List<Integer> literals,
    List<VarkaInputBound> inputBounds,
    List<VarkaDerivedInput> derivedInputs,
    List<Long> longLiterals) {

  public CompiledVarkaProjection {
    outputs = List.copyOf(outputs);
    outputTypes = List.copyOf(outputTypes);
    inputOrdinals = List.copyOf(inputOrdinals);
    literals = List.copyOf(literals);
    inputBounds = List.copyOf(inputBounds);
    derivedInputs = List.copyOf(derivedInputs);
    longLiterals = List.copyOf(longLiterals);
    // A kernel is single-lane - every output root agrees, which the emitter enforces - so it reads
    // exactly one of the two literal tables. Both non-empty would mean an entry of the other lane
    // left its literals behind when it was demoted, which the per-entry rollback rules out.
    if (!literals.isEmpty() && !longLiterals.isEmpty()) {
      throw new IllegalArgumentException(
          "a kernel is single-lane, so at most one of its literal tables is populated");
    }
  }

  /**
   * The lane the kernel's loop runs at, and so the {@code run} overload the evaluator calls: every
   * root's emission lane, which is the root's own except for a narrowing root, whose 32-bit column
   * is computed in the 64-bit lane ({@code VarkaVectorIR.emissionLane}).
   */
  public LaneType lane() {
    return VarkaVectorIR.emissionLane(outputs.get(0));
  }

  /** The slot count of the one literal table this kernel reads - what the emitter is told. */
  public int numLiterals() {
    return literals.size() + longLiterals.size();
  }

  /**
   * The derived-input note for kernel input {@code inputIndex}, if the evaluator derives it. A
   * scan: the evaluators ask once per input when they are built, never per batch.
   */
  public Optional<VarkaDerivedInput> derivedAt(int inputIndex) {
    for (VarkaDerivedInput d : derivedInputs) {
      if (d.inputIndex() == inputIndex) {
        return Optional.of(d);
      }
    }
    return Optional.empty();
  }
}
