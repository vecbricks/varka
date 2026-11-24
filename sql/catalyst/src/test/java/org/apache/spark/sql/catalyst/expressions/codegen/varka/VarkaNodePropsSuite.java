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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaNodeProps.Validity;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every IR node answers its own {@link VarkaNodeProps}: the interface method has no default, so a
 * new record that forgets it does not compile, and these tests keep that guarantee from being
 * softened by a default added later, on the interface or on one of its sealed families. The
 * second test holds the validity rules to the operand shapes the passes that fold them assume.
 */
@VarkaSuite
public final class VarkaNodePropsSuite {

  /** The IR fuzzer's grammars, each drawing the roots of one shape. */
  private static final List<Function<Random, List<VarkaVectorIR>>> GRAMMARS = List.of(
      r -> VarkaIrGrammar.drawShape(r).roots(),
      r -> VarkaIrGrammar.drawNullingShape(r).roots(),
      r -> VarkaIrGrammar.drawNarrowShape(r).roots(),
      r -> VarkaIrGrammar.drawWideShape(r).roots(),
      r -> List.of(VarkaIrGrammar.drawCastShape(r)),
      r -> List.of(VarkaIrGrammar.drawNullingLaneShape(r)),
      r -> VarkaIrGrammar.drawLongShape(r).roots(),
      r -> VarkaIrGrammar.drawNullingLongShape(r).roots(),
      r -> VarkaIrGrammar.drawUnsignedLongShape(r).roots(),
      r -> VarkaIrGrammar.drawNarrowLaneShape(r, LaneType.BYTE).roots(),
      r -> VarkaIrGrammar.drawNarrowLaneShape(r, LaneType.SHORT).roots(),
      r -> VarkaIrGrammar.drawFloatingLaneShape(r, LaneType.DOUBLE).roots(),
      r -> VarkaIrGrammar.drawFloatingLaneShape(r, LaneType.FLOAT).roots(),
      r -> VarkaIrGrammar.drawFloatingCastShape(r),
      r -> VarkaIrGrammar.drawDecimalShape(r).roots(),
      r -> VarkaIrGrammar.drawDecimalValueShape(r).roots(),
      r -> VarkaIrGrammar.drawWideLongShape(r).roots(),
      r -> VarkaIrGrammar.drawMixedShape(r).roots());

  /**
   * The roots of {@code shapes} drawn shapes, the grammars taken in turn, each seeded from the
   * shape's index so that a failure names a shape that replays.
   */
  static List<List<VarkaVectorIR>> drawnShapes(int shapes) {
    var drawn = new ArrayList<List<VarkaVectorIR>>();
    for (int seed = 0; seed < shapes; seed++) {
      drawn.add(GRAMMARS.get(seed % GRAMMARS.size()).apply(new Random(seed)));
    }
    return drawn;
  }

  /** Every distinct node of {@code shapes}. */
  static Set<VarkaVectorIR> nodesOf(List<List<VarkaVectorIR>> shapes) {
    var nodes = new LinkedHashSet<VarkaVectorIR>();
    for (List<VarkaVectorIR> roots : shapes) {
      for (VarkaVectorIR root : roots) {
        VarkaVariants.walk(root, nodes::add);
      }
    }
    return nodes;
  }

  /** The records under {@code type}, walking its sealed families. */
  static void leaves(Class<?> type, List<Class<?>> into) {
    if (type.isRecord()) {
      into.add(type);
      return;
    }
    for (Class<?> sub : type.getPermittedSubclasses()) {
      leaves(sub, into);
    }
  }

  @Test
  @DisplayName("every IR record declares its own props and no family supplies a default")
  void everyNodeDecides() throws ReflectiveOperationException {
    Method props = VarkaVectorIR.class.getMethod("props");
    assertTrue(Modifier.isAbstract(props.getModifiers()), "props() must stay abstract");
    assertFalse(props.isDefault(), "props() must have no default");
    for (Class<?> family : List.of(Cond.class, VarkaVectorIR.Chrono.class)) {
      for (Method m : family.getDeclaredMethods()) {
        assertFalse(m.getName().equals("props"), family.getSimpleName() + " defines props()");
      }
    }
    var records = new ArrayList<Class<?>>();
    leaves(VarkaVectorIR.class, records);
    assertTrue(records.size() >= 61, "the walk found only " + records.size() + " records");
    for (Class<?> r : records) {
      Method own = r.getDeclaredMethod("props");
      assertEquals(VarkaNodeProps.class, own.getReturnType(), r.getSimpleName());
    }
  }

  @Test
  @DisplayName("a strict or null-skipping node's operands are values, and a condition has no word")
  void validityShapes() {
    Set<VarkaVectorIR> nodes = nodesOf(drawnShapes(500));
    for (VarkaVectorIR n : nodes) {
      String at = VarkaVectorIR.canonical(n);
      VarkaVectorIR[] children = VarkaVectorIR.childrenOf(n);
      switch (n.props().validity()) {
        case Validity.Strict _, Validity.SkipsNull _ -> {
          assertTrue(children.length > 0, at);
          for (VarkaVectorIR c : children) {
            assertFalse(c instanceof Cond, at);
          }
        }
        case Validity.Input in -> {
          assertEquals(0, children.length, at);
          assertEquals(in.ordinal(), VarkaVectorIR.inputOrdinal(n), at);
        }
        case Validity.AllTrue _ -> assertEquals(0, children.length, at);
        case Validity.NoWord _ -> assertTrue(n instanceof Cond, at);
        case Validity.Own _ -> assertFalse(n instanceof Cond, at);
        case Validity.Operand op -> {
          assertTrue(op.index() < children.length, at);
          assertFalse(children[op.index()] instanceof Cond, at);
        }
      }
    }
    assertTrue(nodes.size() > 1000, "drew only " + nodes.size() + " distinct nodes");
  }
}
