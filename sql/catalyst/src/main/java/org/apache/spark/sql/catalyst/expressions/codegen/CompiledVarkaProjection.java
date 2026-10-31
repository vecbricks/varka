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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.BooleanType;
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
    // and, where it converts between lanes (VARKA-28), the int table of its int values. A narrow
    // kernel reads only the int table too, under either of the narrow arm's options, so it is on
    // the same side as an int one here. The defaults answer the lane for the narrow trees: the
    // arm chooses between INT and a narrow lane, and both read the int table.
    if (!longLiterals.isEmpty() && !outputs.isEmpty()
        && !drivesLong(VarkaKernelPlan.of(outputs, VarkaEmitOptions.DEFAULTS))) {
      throw VarkaErrors.illegalArgument(
          "a kernel driven at the int lane reads no long literal table");
    }
    // An output's element width has two sources: the emitter maps and stores the destination at
    // the root's lane, and the evaluator allocates the Arrow vector from the Spark type. They
    // agree because every compiler arm builds matching roots, and nothing downstream checks it:
    // a mismatch writes past a narrower vector or stores a byte a row into a wider one, with no
    // throw. So it is checked once here, where both are known (VARKA-329).
    //
    // A root that writes bitmaps (VarkaVectorIR.writesBitmaps) has no value width, and its type is
    // BOOLEAN, the only type whose vector is bitmaps: a filter's condition root, whose selection
    // bitmap is the predicate's, or a boolean output, which a BOOLEAN column's BitVector holds.
    // The rule holds both ways. A value root typed BOOLEAN would be handed a BitVector, and the
    // evaluator's bit-output width with it, and store a value a row past the bitmap, where a
    // narrow root's one byte a row matches BOOLEAN's default size; so it is refused here before
    // the width compare. That a projection's bitmap root is a BoolOutput rather than a filter's
    // Cond, which writes one bitmap where the column needs two, is a projection's own rule,
    // checked in PartialVarkaProjection, since a filter's kernel is this record too.
    if (outputs.size() == outputTypes.size()) {
      for (int i = 0; i < outputs.size(); i++) {
        VarkaVectorIR root = outputs.get(i);
        boolean bitmaps = VarkaVectorIR.writesBitmaps(root);
        if (bitmaps != outputTypes.get(i) instanceof BooleanType) {
          throw VarkaErrors.illegalArgument("output " + i + " is "
              + (bitmaps ? "a bitmap root, " + root.getClass().getSimpleName() + "," : "a value")
              + " but its type is " + outputTypes.get(i).simpleString()
              + ": a root writes bitmaps exactly when its type is boolean");
        }
        if (bitmaps) {
          continue;
        }
        int stored = switch (root.laneType()) {
          case BYTE -> 1;
          case SHORT -> 2;
          case INT, FLOAT -> 4;
          case LONG, DOUBLE -> 8;
        };
        if (stored != outputTypes.get(i).defaultSize()) {
          throw VarkaErrors.illegalArgument("output " + i + " is stored " + stored
              + " bytes a row at the " + root.laneType() + " lane, but its type "
              + outputTypes.get(i).simpleString() + " is " + outputTypes.get(i).defaultSize());
        }
      }
    }
  }

  /**
   * The lane the kernel's loop runs at, and so the {@code run} overload the evaluator calls: the
   * roots' shared emission lane, which is a root's own except for a narrowing root, whose 32-bit
   * column is computed in the 64-bit lane, and a native narrow root beside an int one, which runs
   * at the int lane ({@code VarkaKernelPlan.drive}). The options are the caller's because the
   * narrow arm's lane is theirs ({@link VarkaEmitOptions#narrowLanes()}), and they are the options
   * the shape is keyed on.
   */
  public LaneType lane(VarkaEmitOptions options) {
    VarkaKernelPlan plan = VarkaKernelPlan.of(outputs, options);
    if (plan.refusal().isPresent()) {
      throw VarkaErrors.illegalArgument(
          "no kernel of these outputs: " + plan.refusal().get().reason() + ": " + outputs);
    }
    return plan.drive();
  }

  /**
   * Whether a plan drives its kernel at a 64-bit lane, the long or the {@code DOUBLE} one, whose
   * {@code run} takes the long table; a refused one drives at none.
   */
  private static boolean drivesLong(VarkaKernelPlan plan) {
    return plan.refusal().isEmpty() && plan.drive().bits() == Long.SIZE;
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
