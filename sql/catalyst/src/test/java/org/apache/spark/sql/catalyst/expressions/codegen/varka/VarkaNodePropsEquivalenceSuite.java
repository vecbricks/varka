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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.WordExpr;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.WordOwner;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaNodeProps.Validity;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The analysis's word rules ({@code Analysis.ownerOf} and {@code Analysis.pureOf}) against the
 * fold of each node's {@link VarkaNodeProps#validity()}, in a real analysis of each of 4000 drawn
 * shapes under both values of {@code checkIntOverflow}: while a rule and the descriptor both
 * exist, they must give the same answer for every node, which is what lets the rule read the
 * descriptor instead. A disagreement is a wrong {@code props()} in the
 * record it names, not a reason to change the pass.
 *
 * <p>The suite lives only as long as the switches do (`VARKA-489.md` 5): each check goes with
 * the switch it compares, since a frozen copy of a switch is a per-node list a new node would
 * have to keep, the cost the descriptor removes.
 */
@VarkaSuite
public final class VarkaNodePropsEquivalenceSuite {

  private static final int SHAPES = 4000;

  /** The inputs and literals {@code roots} read, as the emitter's counts. */
  private static int[] counts(List<VarkaVectorIR> roots) {
    int[] c = new int[2];
    for (VarkaVectorIR root : roots) {
      VarkaVariants.walk(root, n -> {
        c[0] = Math.max(c[0], VarkaVectorIR.inputOrdinal(n) + 1);
        if (n instanceof LiteralSlot l) {
          c[1] = Math.max(c[1], l.index() + 1);
        }
      });
    }
    return c;
  }

  /** {@code Analysis.andOwner}'s fold over the operands' owners. */
  private static WordOwner andOwner(VarkaVectorIR node, Analysis a) {
    WordOwner owner = null;
    for (VarkaVectorIR c : VarkaVectorIR.childrenOf(node)) {
      WordOwner w = a.wordOwner.get(c);
      if (owner == null || owner == WordOwner.Const.ALL_TRUE) {
        owner = w;
      } else if (w != WordOwner.Const.ALL_TRUE && !owner.equals(w)) {
        return new WordOwner.Own(node);
      }
    }
    return owner;
  }

  /** The pure word {@code node}'s validity folds to over its operands' pure words. */
  private static WordExpr pureFold(VarkaVectorIR node, Analysis a) {
    WordExpr acc = null;
    boolean first = true;
    boolean or = node.props().validity() instanceof Validity.SkipsNull;
    for (VarkaVectorIR c : VarkaVectorIR.childrenOf(node)) {
      WordExpr w = a.pureWord.get(c);
      if (w == null) {
        return null;
      }
      if (first) {
        acc = w;
        first = false;
      } else if (or) {
        acc = acc == WordExpr.Const.ALL_TRUE || w == WordExpr.Const.ALL_TRUE
            ? WordExpr.Const.ALL_TRUE : acc.equals(w) ? acc : new WordExpr.Or(acc, w);
      } else {
        acc = acc == WordExpr.Const.ALL_TRUE ? w
            : w == WordExpr.Const.ALL_TRUE || acc.equals(w) ? acc : new WordExpr.And(acc, w);
      }
    }
    return acc;
  }

  @Test
  @DisplayName("the word rules agree with the descriptor in a real analysis")
  void analysisRulesAgree() {
    int analyzed = 0;
    int compared = 0;
    List<List<VarkaVectorIR>> shapes = VarkaNodePropsSuite.drawnShapes(SHAPES);
    for (int s = 0; s < shapes.size(); s++) {
      List<VarkaVectorIR> roots = shapes.get(s);
      int[] c = counts(roots);
      for (boolean check : new boolean[] {true, false}) {
        VarkaEmitOptions options = VarkaEmitOptions.DEFAULTS.withCheckIntOverflow(check);
        Analysis a;
        try {
          a = VarkaLoopEmitter.analyze(roots, c[0], c[1], options);
        } catch (IllegalArgumentException | IllegalStateException refused) {
          continue;
        }
        analyzed++;
        for (VarkaVectorIR node : a.topoOrder) {
          String at = "shape " + s + " checkIntOverflow=" + check + ": "
              + VarkaVectorIR.canonical(node);
          if (!a.wordOwner.containsKey(node)) {
            continue;
          }
          compared++;
          Validity v = node.props().validity();
          WordOwner owner = switch (v) {
            case Validity.Input in -> new WordOwner.Input(in.ordinal());
            case Validity.AllTrue _ -> WordOwner.Const.ALL_TRUE;
            case Validity.Strict _ -> andOwner(node, a);
            case Validity.SkipsNull _, Validity.Own _, Validity.NoWord _ ->
                new WordOwner.Own(node);
            case Validity.Operand op -> a.wordOwner.get(VarkaVectorIR.childrenOf(node)[op.index()]);
          };
          assertEquals(a.wordOwner.get(node), owner, "word owner of " + at);
          WordExpr pure = switch (v) {
            case Validity.Input in -> new WordExpr.Input(in.ordinal());
            case Validity.AllTrue _ -> WordExpr.Const.ALL_TRUE;
            case Validity.Strict _, Validity.SkipsNull _ -> pureFold(node, a);
            case Validity.Own _, Validity.NoWord _ -> null;
            case Validity.Operand op -> a.pureWord.get(VarkaVectorIR.childrenOf(node)[op.index()]);
          };
          assertEquals(a.pureWord.get(node), pure, "pure word of " + at);
        }
      }
    }
    assertTrue(analyzed > SHAPES, "analyzed only " + analyzed + " of " + 2 * SHAPES);
    System.out.println("VarkaNodePropsEquivalenceSuite: " + analyzed + " analyses, "
        + compared + " word entries agree");
  }
}
