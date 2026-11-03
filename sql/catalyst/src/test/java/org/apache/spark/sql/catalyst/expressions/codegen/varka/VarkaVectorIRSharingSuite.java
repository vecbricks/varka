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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import scala.jdk.javaapi.CollectionConverters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoundedDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondIf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * A node the IR reaches by several paths is hashed once, not once per path (VARKA-559): every
 * node with a child caches its structural hash, so the planning walks' hash sets cost the DAG's
 * node count, where without it a chain whose levels each read their condition twice costs 2^depth.
 */
@VarkaSuite
public final class VarkaVectorIRSharingSuite {

  /**
   * {@code coalesce(IF(x > b, a, c), b)} nested {@code depth} times over {@code x = a}, as row
   * 348's decimal coalesce builds it: each level's value and validity read one condition, so the
   * condition is shared and the DAG has 2^depth paths to the bottom in a handful of nodes a level.
   */
  private static VarkaVectorIR coalesceChain(int depth, LaneType lane) {
    VarkaVectorIR a = new ColumnRef(0, lane);
    VarkaVectorIR b = new ColumnRef(1, lane);
    VarkaVectorIR c = new ColumnRef(2, lane);
    VarkaVectorIR x = a;
    for (int i = 0; i < depth; i++) {
      Cond cond = new Compare(CompareOp.GT, x, b);
      Cond valid = new CondIf(cond, new IsNotNull(a), new IsNotNull(c));
      x = new IfElse(valid, new IfElse(cond, a, c), b);
    }
    return new Compare(CompareOp.GT, x, c);
  }

  /** The distinct node objects under {@code root}, each counted once however it is reached. */
  private static List<VarkaVectorIR> objectsUnder(VarkaVectorIR root) {
    var seen = new IdentityHashMap<VarkaVectorIR, Boolean>();
    var order = new ArrayList<VarkaVectorIR>();
    var todo = new ArrayDeque<VarkaVectorIR>(List.of(root));
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (seen.put(node, Boolean.TRUE) == null) {
        order.add(node);
        for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
          todo.push(child);
        }
      }
    }
    return order;
  }

  @Test
  @DisplayName("a chain sharing each level's condition plans at a depth no per-path walk finishes")
  void sharedConditionsPlanInLinearTime() {
    // 2^60 paths: a walk that visits a shared node once per path would not finish, one that
    // visits it once takes milliseconds, so the bound is generous and still decides it.
    for (LaneType lane : List.of(LaneType.LONG, LaneType.INT, LaneType.SHORT)) {
      VarkaVectorIR root = coalesceChain(60, lane);
      assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
        List<VarkaVectorIR> roots = List.of(root);
        VarkaKernelPlan plan = VarkaKernelPlan.of(roots, VarkaMatrixRuns.BASE);
        VarkaKernelPlan.admit(roots, VarkaMatrixRuns.BASE, true);
        assertEquals(Set.of(lane), VarkaVectorIR.lanesIn(root));
        assertEquals(lane.isNarrow() ? Set.of(lane) : Set.of(),
            VarkaVectorIR.narrowValuesIn(root));
        VarkaVectorIR.needsNarrowSpecies(root);
        if (plan.refusal().isEmpty()) {
          // Over the budget's height, which the walk measures on the shared DAG.
          assertFalse(VarkaLoopEmitter.fitsBudgets(roots, 3, VarkaMatrixRuns.BASE));
        }
      }, () -> "planning a " + lane + " chain 60 levels deep took over 20 s");
    }
  }

  @Test
  @DisplayName("nested signum, interval abs and interval division explain at depth 20, not fused")
  void sharingExpressionsExplainAtDepth() {
    // Per level an interval's abs reaches its operand by three paths, its division by seven and
    // a signum by four: before the cached hash these took 25 s at depth 16, 10 s at 8 and 18 s
    // at 12 (results/VARKA-559-planning-cost.txt), so the bound decides it at 20.
    DataType ym = new YearMonthIntervalType((byte) 0, (byte) 1);
    List<Object[]> chains = List.of(new Object[] {DataTypes.DoubleType, "signum(X)"},
        new Object[] {ym, "abs(X)"}, new Object[] {ym, "(X) / 3"});
    for (Object[] chain : chains) {
      DataType type = (DataType) chain[0];
      var columns = CollectionConverters.asScala(List.<Attribute>of(
          VarkaSqlResolve.column("a", type), VarkaSqlResolve.column("c", type))).toSeq();
      String x = "a";
      for (int i = 0; i < 20; i++) {
        x = ((String) chain[1]).replace("X", x);
      }
      Expression e = VarkaSqlResolve.parse("(" + x + ") > c", columns);
      List<VarkaConjunctSpec> specs = assertTimeoutPreemptively(Duration.ofSeconds(20),
          () -> VarkaExpressionCompiler.explainPredicate(e, columns, VarkaMatrixRuns.BASE,
              VarkaAdmission.NO_HOST_REFUSAL), () -> "explaining " + chain[1] + " 20 deep");
      // Over the budget's height, a constant, so not fused under any configuration; the reason
      // is not asserted, since a configuration may decline the chain earlier for another.
      assertEquals(1, specs.size());
      assertFalse(specs.get(0).fused(), () -> chain[1] + " fused 20 deep");
    }
  }

  @Test
  @DisplayName("structurally equal nodes still collapse: the chain's distinct nodes grow by a "
      + "constant a level")
  void equalNodesStillCollapse() {
    // Built twice, so no object is shared between the two: equality and the hash are structural.
    // Shallow, since the equality of two such copies still compares a shared node once per path
    // (VARKA-559.md, "What exists now"); within one compile a shared node is one object.
    int previous = -1;
    for (int depth = 1; depth <= 10; depth++) {
      VarkaVectorIR one = coalesceChain(depth, LaneType.LONG);
      VarkaVectorIR two = coalesceChain(depth, LaneType.LONG);
      assertEquals(one, two);
      assertEquals(one.hashCode(), two.hashCode());
      var structural = new java.util.HashSet<>(objectsUnder(one));
      structural.addAll(objectsUnder(two));
      // Per level a compare, two null tests, a choice of tests and two choices; the null tests
      // are equal at every level, so a level adds four distinct nodes.
      int distinct = structural.size();
      if (previous >= 0) {
        assertEquals(4, distinct - previous, "distinct nodes added at depth " + depth);
      }
      previous = distinct;
    }
  }

  @Test
  @DisplayName("every node with a child caches the hash its components give, last")
  void everyInnerNodeCachesItsHash() throws ReflectiveOperationException {
    var types = new ArrayList<Class<?>>();
    var todo = new ArrayDeque<Class<?>>(List.of(VarkaVectorIR.class));
    while (!todo.isEmpty()) {
      Class<?> c = todo.pop();
      if (c.isRecord()) {
        types.add(c);
      } else if (c.getPermittedSubclasses() != null) {
        todo.addAll(List.of(c.getPermittedSubclasses()));
      }
    }
    // One node of every inner type, over leaves, beside a chain whose nodes have inner children.
    var nodes = new ArrayList<>(objectsUnder(coalesceChain(4, LaneType.LONG)));
    int inner = 0;
    for (Class<?> type : types) {
      RecordComponent[] components = type.getRecordComponents();
      if (Arrays.stream(components)
          .noneMatch(rc -> VarkaVectorIR.class.isAssignableFrom(rc.getType()))) {
        continue;
      }
      inner++;
      RecordComponent last = components[components.length - 1];
      assertEquals("hash", last.getName(), type.getSimpleName() + "'s last component");
      assertEquals(int.class, last.getType(), type.getSimpleName() + ".hash");
      assertEquals(type, type.getMethod("hashCode").getDeclaringClass(),
          type.getSimpleName() + " returns its cached hash");
      nodes.add(anyNodeOf(type));
    }
    assertTrue(inner >= 47, "inner node types found: " + inner);
    // The value: the records' own formula over every component but the hash, per node object.
    for (VarkaVectorIR node : nodes) {
      RecordComponent[] components = node.getClass().getRecordComponents();
      int h = 0;
      int n = components.length;
      n -= n > 0 && components[n - 1].getName().equals("hash") ? 1 : 0;
      for (int i = 0; i < n; i++) {
        h = 31 * h + Objects.hashCode(components[i].getAccessor().invoke(node));
      }
      assertEquals(h, node.hashCode(), () -> node.getClass().getSimpleName());
    }
  }

  /**
   * A node of {@code type} over leaves, by reflection as {@code VarkaIrFuzzSuite.admitsLongLanes}
   * builds one: each lane and enum constant in turn until the constructor accepts, so a new node
   * type is built here unedited; {@code BoundedDivide} checks a pair it derives, so by hand.
   */
  private static VarkaVectorIR anyNodeOf(Class<?> type) throws ReflectiveOperationException {
    if (type == BoundedDivide.class) {
      return BoundedDivide.of(new ColumnRef(0, LaneType.INT), 3, 10);
    }
    RecordComponent[] components = type.getRecordComponents();
    Class<?>[] signature =
        Arrays.stream(components).map(RecordComponent::getType).toArray(n -> new Class<?>[n]);
    Map<Class<?>, Object> fixed = Map.of(long.class, 1L, int.class, 1, boolean.class, false,
        List.class, List.of(1, 2));
    for (LaneType lane : LaneType.values()) {
      VarkaVectorIR column = new ColumnRef(0, lane);
      for (int k = 0; k < 6; k++) { // six: the constants of the IR's largest enum
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
          Class<?> t = signature[i];
          args[i] = t == Cond.class ? new Compare(CompareOp.LT, column, column)
              : VarkaVectorIR.class.isAssignableFrom(t) ? column
              : t.isEnum() ? t.getEnumConstants()[k % t.getEnumConstants().length]
              : Objects.requireNonNull(fixed.get(t), type.getSimpleName() + "."
                  + components[i].getName() + ": a component type this test cannot build");
        }
        try {
          return (VarkaVectorIR) type.getDeclaredConstructor(signature).newInstance(args);
        } catch (InvocationTargetException e) {
          if (!(e.getCause() instanceof IllegalArgumentException)) {
            throw e;
          }
        }
      }
    }
    throw new AssertionError(type.getSimpleName() + " refuses leaves on every lane");
  }

  @Test
  @DisplayName("the canonical constructor recomputes the hash it is given")
  void canonicalConstructorIgnoresItsHash() {
    VarkaVectorIR a = new ColumnRef(0, LaneType.INT);
    VarkaVectorIR b = new ColumnRef(1, LaneType.INT);
    assertEquals(new Compare(CompareOp.LT, a, b), new Compare(CompareOp.LT, a, b, 12345));
    assertEquals(new Compare(CompareOp.LT, a, b).hash(), new Compare(CompareOp.LT, a, b, 7).hash());
  }
}
