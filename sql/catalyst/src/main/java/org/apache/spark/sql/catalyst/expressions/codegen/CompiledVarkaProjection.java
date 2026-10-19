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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.DataType;

/**
 * A whole projection compiled to the Varka vector IR: the trees {@code VarkaLoopEmitter} turns
 * into one fused loop, plus everything the evaluator needs to drive the emitted class - which
 * child columns it reads (dense kernel input index = position in {@code inputOrdinals}), the
 * runtime {@code scalarArgs} values (slot index = position in {@code literals} for an int-lane
 * slot, in {@code longLiterals} for a long-lane one), and each output's Spark type, which is
 * what tells a {@code datediff} day-count column ({@code IntegerType}) apart from a date column
 * when the output vectors are allocated. {@code families} are the switchable expression families
 * whose nodes the accepted entries hold, in declaration order (VARKA-318): the switches a failure
 * of this kernel names, which no kernel byte depends on.
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
    List<Long> longLiterals,
    List<VarkaFamily> families) {

  public CompiledVarkaProjection {
    outputs = List.copyOf(outputs);
    outputTypes = List.copyOf(outputTypes);
    inputOrdinals = List.copyOf(inputOrdinals);
    literals = List.copyOf(literals);
    inputBounds = List.copyOf(inputBounds);
    derivedInputs = List.copyOf(derivedInputs);
    longLiterals = List.copyOf(longLiterals);
    families = List.copyOf(families);
    // Every output root agrees on the lane its kernel is driven at, which the emitter enforces. A
    // kernel driven at the int lane reads only the int table, so a long literal beside one would
    // mean an entry of the other lane left its literals behind when it was demoted, which the
    // per-entry rollback rules out. A kernel driven at the long lane may read both: the long table
    // and, where it converts between lanes (VARKA-28), the int table of its int values.
    if (!longLiterals.isEmpty() && !outputs.isEmpty()
        && VarkaVectorIR.emissionLane(outputs.get(0)) == LaneType.INT) {
      throw VarkaErrors.illegalArgument(
          "a kernel driven at the int lane reads no long literal table");
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

  /**
   * Both tables' slot counts together - what the emitter is told, and keyed on: a single-lane
   * kernel reads one table, and a kernel that converts between lanes indexes each lane's own.
   */
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
