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
import java.util.Objects;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan.LongNarrowRole;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

/**
 * The small facts about one IR node that the compiler's passes ask, answered by the node itself:
 * every record of {@link VarkaVectorIR} implements {@link VarkaVectorIR#props()}, which has no
 * default, so a new node cannot compile until it decides each of them. A pass reads the one it
 * needs instead of keeping a switch over every node type of its own.
 *
 * <p>Each answer reads the node's own fields and lane and the structure of its operands - their
 * record type and lane, a guard's mode and bounds - and never the emission's options or an
 * analysis. What depends on the options or on the operands' own analysis is the pass's to work
 * out from these answers: {@link Ring.Arith} says a node is ring arithmetic of a given overflow
 * mode, and the analysis decides from the options whether that mode's check is emitted.
 *
 * <p>It is a method of the node, not a record component: the records' structural
 * {@code equals} and {@code hashCode} are the emitter's memo keys and the shape cache's
 * identity, and a value derived from the components must not enter either.
 *
 * @param ring whether reduction modulo a narrow lane's width commutes with the node, which is
 *     what {@code Analysis.isNarrowRing} asks
 * @param validity how the node's validity word follows from its operands' words, and whether
 *     it is a pure function of input bitmaps; whether the analysis counts a node as skipping a
 *     null input stays {@code Analysis.analyze}'s own switch
 * @param weight what the node costs against the grouping budgets, in lane operations
 *     ({@code VarkaEmitBudget.weightOf})
 * @param costKind the feature the emit cost model counts the node under
 *     ({@code VarkaEmitCost.kindOf}): the record name and the parts of the node that choose
 *     between lowerings
 * @param fills the restricted operand positions this node may stand in
 *     ({@link VarkaOperandPosition#admits})
 * @param restricted the node's own operands that sit in a restricted position
 *     ({@link VarkaOperandPosition#restrictedOperands})
 * @param longNarrow what the node reads of its narrow children in a kernel driven at the long
 *     lane ({@code VarkaKernelPlan.loweredAtLong})
 */
public record VarkaNodeProps(Ring ring, Validity validity, int weight, String costKind,
    Set<VarkaOperandPosition> fills, List<VarkaOperandPosition.Restricted> restricted,
    LongNarrowReader longNarrow) {

  /** For a node that may stand in no restricted position. */
  public static final Set<VarkaOperandPosition> FILLS_NOTHING = Set.of();

  /** For a node none of whose operands is in a restricted position. */
  public static final List<VarkaOperandPosition.Restricted> RESTRICTS_NOTHING = List.of();

  public VarkaNodeProps {
    Objects.requireNonNull(ring, "ring");
    Objects.requireNonNull(validity, "validity");
    Objects.requireNonNull(costKind, "costKind");
    Objects.requireNonNull(longNarrow, "longNarrow");
    if (weight < 0) {
      throw VarkaErrors.illegalArgument("a node's weight is never negative, got " + weight);
    }
    fills = Set.copyOf(fills);
    restricted = List.copyOf(restricted);
  }

  /**
   * Whether reduction modulo a narrow width commutes with a node. Over sign-extended
   * {@code TINYINT} or {@code SMALLINT} values an int chain of such nodes needs one reduction, at
   * its first reader that is not one ({@code VARKA-329.md} 4.2).
   */
  public sealed interface Ring permits Ring.Never, Ring.Pick, Ring.Arith {
    /** Never a ring node, whatever its lane: a compare, a shift, a conversion, a leaf. */
    Ring NEVER = new Never();
    /** A ring node whenever it is on a narrow lane: a blend only picks lanes. */
    Ring PICK = new Pick();

    /** See {@link #NEVER}. */
    record Never() implements Ring {}

    /** See {@link #PICK}. */
    record Pick() implements Ring {}

    /**
     * Narrow add, subtract, multiply or negate declared {@code mode}: a ring node where the
     * emission does not check its overflow, since a checked node's range compare reads the exact
     * narrow value.
     */
    record Arith(Overflow mode) implements Ring {
      public Arith {
        Objects.requireNonNull(mode, "mode");
      }
    }
  }

  /**
   * How a node's validity word is made: the one rule that the analysis's word owners
   * ({@code Analysis.ownerOf}), its pure word algebra ({@code Analysis.pureOf}) and the slot
   * planner's word references ({@code Slots.planWordRef}) all fold, so the three agree by
   * construction. Folded over
   * the node's operands in {@code VarkaVectorIR.childrenOf} order, a {@link Strict} word is the
   * AND of the operands' words (a unary node's is its operand's), a {@link SkipsNull} word their
   * OR, computed in a slot of the node's own, an {@link Own} word the node's own slot,
   * computed from the words of its value operands and from values, and an {@link Operand} word
   * one operand's, aliased.
   */
  public sealed interface Validity
      permits Validity.Input, Validity.AllTrue, Validity.Strict, Validity.SkipsNull,
          Validity.Own, Validity.NoWord, Validity.Operand {
    /** A literal: valid in every lane. */
    Validity ALL_TRUE = new AllTrue();
    /** Null where any operand is null: the AND of its operands' words, or a unary alias. */
    Validity STRICT = new Strict();
    /** Null only where every operand is null: the OR of its operands' words. */
    Validity SKIPS_NULL = new SkipsNull();
    /** A word the node computes from values, not from its operands' words alone. */
    Validity OWN = new Own();
    /** A condition, whose known-true and known-false words stand where a validity word would. */
    Validity NO_WORD = new NoWord();

    /**
     * An input leaf: the validity word of the column at {@code ordinal}. The ordinal is not
     * checked here: the emitter's admission refuses one outside the kernel's inputs with the
     * reason a caller is shown, and a descriptor read first must not pre-empt it.
     */
    record Input(int ordinal) implements Validity {}

    /** See {@link #ALL_TRUE}. */
    record AllTrue() implements Validity {}

    /** See {@link #STRICT}. */
    record Strict() implements Validity {}

    /** See {@link #SKIPS_NULL}. */
    record SkipsNull() implements Validity {}

    /** See {@link #OWN}. */
    record Own() implements Validity {}

    /** See {@link #NO_WORD}. */
    record NoWord() implements Validity {}

    /**
     * The word of one operand, the one at {@code index} in {@code VarkaVectorIR.childrenOf}
     * order, aliased: a node whose other operands' nulls leave its value as it is, as a hash
     * step's word does its seed (VARKA-337). The node reads those operands' words as data, not
     * as its own validity.
     */
    record Operand(int index) implements Validity {
      public Operand {
        if (index < 0) {
          throw VarkaErrors.illegalArgument("an operand index is never negative, got " + index);
        }
      }
    }
  }

  /** What a node reads of its narrow children in a kernel driven at the long lane. */
  public enum LongNarrowReader {
    /** A widening, a comparison or a narrowing: a narrow value. */
    VALUE,
    /** A connective or a boolean output: a narrow condition. */
    COND,
    /** A null test: either. */
    VALUE_OR_COND,
    /** Any other node: no narrow child. */
    NONE;

    /** Whether a reader of this kind takes a narrow child of role {@code child}. */
    boolean takes(LongNarrowRole child) {
      return switch (this) {
        case VALUE -> child.isValue();
        case COND -> child == LongNarrowRole.COND;
        case VALUE_OR_COND -> child.isValue() || child == LongNarrowRole.COND;
        case NONE -> false;
      };
    }
  }
}
