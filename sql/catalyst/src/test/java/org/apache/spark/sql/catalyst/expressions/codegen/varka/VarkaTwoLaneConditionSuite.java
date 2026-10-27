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

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan.Kind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan.Refusal;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * The compiler's refusal of a long-lane condition on an x86 host whose long species has two
 * lanes (VARKA-503). The host is one fact, passed in here, so both answers are checked on any
 * machine; {@code VarkaFilterExecSuite} checks what EXPLAIN says on the machine it runs on, which
 * under the gate's narrow step is the two-lane host.
 */
@VarkaSuite
public final class VarkaTwoLaneConditionSuite {

  private static final ColumnRef L = new ColumnRef(0, LaneType.LONG);
  private static final ColumnRef L2 = new ColumnRef(1, LaneType.LONG);
  private static final ColumnRef I = new ColumnRef(2, LaneType.INT);
  private static final ColumnRef I2 = new ColumnRef(3, LaneType.INT);

  private static Optional<Refusal> admit(VarkaVectorIR root, boolean twoLaneHost) {
    return VarkaKernelPlan.admit(List.of(root), VarkaMatrixRuns.BASE, twoLaneHost);
  }

  @Test
  @DisplayName("the host test is x86 at two long lanes, at every AVX level and nowhere else")
  void hostTest() {
    for (int level = 0; level <= 3; level++) {
      assertTrue(VarkaKernelPlan.twoLaneMasksFallBack(level, 2), "UseAVX=" + level);
      assertFalse(VarkaKernelPlan.twoLaneMasksFallBack(level, 4), "UseAVX=" + level);
      assertFalse(VarkaKernelPlan.twoLaneMasksFallBack(level, 8), "UseAVX=" + level);
    }
    // aarch64 and any JVM without the flag: two-lane masks are lowered there.
    assertFalse(VarkaKernelPlan.twoLaneMasksFallBack(VarkaEmitOptions.USE_AVX_UNKNOWN, 2));
    assertEquals(VarkaKernelPlan.twoLaneMasksFallBack(VarkaEmitOptions.HOST_USE_AVX,
        Lane.LONG.preferredLanes), VarkaKernelPlan.HOST_TWO_LANE_MASKS_FALL_BACK);
  }

  @Test
  @DisplayName("a long condition root is refused on the two-lane host and admitted elsewhere")
  void longCondition() {
    var lt = new Compare(CompareOp.LT, L, L2);
    Optional<Refusal> refused = admit(lt, true);
    assertTrue(refused.isPresent());
    assertEquals(Kind.TWO_LANE_CONDITION, refused.get().kind());
    assertEquals(VarkaKernelPlan.TWO_LANE_CONDITION_REASON, refused.get().reason());
    assertTrue(admit(lt, false).isEmpty());
    // A conversion to the long lane makes the condition a long one: `i < l`.
    assertTrue(admit(new Compare(CompareOp.LT, new WidenLane(I, LaneType.LONG), L), true)
        .isPresent());
  }

  @Test
  @DisplayName("an int condition and a long value root are admitted on the two-lane host")
  void notRefused() {
    assertTrue(admit(new Compare(CompareOp.LT, I, I2), true).isEmpty());
    // The blends make long masks too, and still beat the row path at two lanes.
    assertTrue(admit(new IfElse(new Compare(CompareOp.LT, L, L2), L, L2), true).isEmpty());
    assertTrue(admit(new Greatest(L, L2), true).isEmpty());
  }

  @Test
  @DisplayName("the plan itself never refuses for the host, so the emitter still emits")
  void planIsPure() {
    var lt = new Compare(CompareOp.LT, L, L2);
    assertTrue(VarkaKernelPlan.of(List.of(lt), VarkaMatrixRuns.BASE).refusal().isEmpty());
  }
}
