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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondIf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;

/**
 * The lane lifts rebuild a condition on another lane. A condition reached by several paths is
 * lifted once and the copies share it, so a shared chain costs its nodes, not its paths
 * (VARKA-630; measured in {@code results/VARKA-630-lane-lifts.txt}).
 */
class VarkaConditionLiftSuite {

  /** Level k is {@code level(k-1) AND NOT level(k-1)}, 2^k paths over k + 1 distinct nodes. */
  private static Cond sharedChain(int depth, LaneType lane) {
    Cond c = new BoolColumn(0, lane);
    for (int i = 0; i < depth; i++) {
      c = new And(c, new Not(c));
    }
    return c;
  }

  /** The two children of the root's two operands are one object where the lift shared them. */
  private static void assertShared(Cond lifted) {
    And root = (And) lifted;
    assertSame(root.left(), ((Not) root.right()).child());
  }

  @Test
  @DisplayName("the int-lane lift of a shared chain shares what the chain shares")
  void intLaneLiftSharesNodes() {
    Cond lifted = assertTimeoutPreemptively(Duration.ofSeconds(10),
        () -> VarkaConditionCompiler.onIntLane(sharedChain(64, LaneType.SHORT)));
    assertEquals(LaneType.INT, lifted.laneType());
    assertShared(lifted);
  }

  @Test
  @DisplayName("the long-lane lift of a shared chain shares what the chain shares")
  void longLaneLiftSharesNodes() {
    Cond lifted = assertTimeoutPreemptively(Duration.ofSeconds(10),
        () -> VarkaConditionCompiler.onLongLane(sharedChain(64, LaneType.SHORT))).orElseThrow();
    assertEquals(LaneType.LONG, lifted.laneType());
    assertShared(lifted);
  }

  @Test
  @DisplayName("the lift of a lane-free shared chain to a lane shares what the chain shares")
  void lanelessLiftSharesNodes() {
    Cond lifted = assertTimeoutPreemptively(Duration.ofSeconds(10),
        () -> VarkaIntegralCastCompiler.onLane(sharedChain(64, LaneType.INT), LaneType.LONG))
        .orElseThrow();
    assertEquals(LaneType.LONG, lifted.laneType());
    assertShared(lifted);
  }

  @Test
  @DisplayName("a shared chain through CondIf lifts once, and a list of conjuncts shares one lift")
  void condIfAndListLiftShareNodes() {
    Cond c = new BoolColumn(0, LaneType.SHORT);
    for (int i = 0; i < 64; i++) {
      c = new CondIf(c, c, new Not(c));
    }
    Cond chain = c;
    Cond lifted = assertTimeoutPreemptively(Duration.ofSeconds(10),
        () -> VarkaConditionCompiler.onIntLane(chain));
    CondIf root = (CondIf) lifted;
    assertSame(root.cond(), root.thenCond());
    var both = VarkaConditionCompiler.onIntLaneAll(java.util.List.of(chain, chain));
    assertSame(both.get(0), both.get(1));
  }

  @Test
  @DisplayName("a lift of a shallow chain is the chain rebuilt on the lane, as before")
  void shallowLiftKeepsItsShape() {
    Cond lifted = VarkaConditionCompiler.onIntLane(sharedChain(3, LaneType.BYTE));
    assertEquals(sharedChain(3, LaneType.INT), lifted);
    assertTrue(VarkaConditionCompiler.onLongLane(sharedChain(3, LaneType.BYTE)).isPresent());
  }
}
