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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * The compiler's refusal of a long-lane condition on an x86 host whose long species has two
 * lanes (VARKA-503). The host is one fact, passed in here, so both answers are checked on any
 * machine. What this JVM's fact is, under flags that make it the two-lane host and a four-lane
 * one, is checked in JVMs of their own by sql/core's {@code VarkaTwoLaneHost128Suite} and
 * {@code VarkaTwoLaneHost256Suite}, with the session's default beside it.
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
  @DisplayName("an int condition in a kernel driven at the long lane is refused on the two-lane "
      + "host and admitted elsewhere")
  void intConditionOverLongRead() {
    // `CAST(l AS INT) < i`: the compare is at the int lane, but the kernel reads a long column
    // and is driven at the long lane, whose own masks - the narrowing's range guard among them -
    // run the Java fallback at two lanes.
    var narrowed = new Compare(CompareOp.LT, new NarrowLane(L), I);
    assertEquals(LaneType.LONG, VarkaKernelPlan.of(List.of(narrowed), VarkaMatrixRuns.BASE)
        .drive());
    assertTrue(admit(narrowed, true).isPresent());
    assertTrue(admit(narrowed, false).isEmpty());
    // A test over a long compare's known-ness is a long condition: `(l < l2) IS NOT NULL`.
    assertTrue(admit(new IsNotNull(new Compare(CompareOp.LT, L, L2)), true).isPresent());
  }

  @Test
  @DisplayName("the null test Spark infers for a nullable long column is refused on the two-lane "
      + "host")
  void inferredNullTest() {
    // `isnotnull(l)`, which the optimizer adds beside every comparison of a nullable bigint,
    // TIME, interval or short decimal: with it refused too, a filter over such columns alone
    // has no conjunct left to fuse there.
    assertTrue(admit(new IsNotNull(L), true).isPresent());
    assertTrue(admit(new IsNotNull(L), false).isEmpty());
  }

  @Test
  @DisplayName("the plan itself never refuses for the host, so the emitter still emits")
  void planIsPure() {
    var lt = new Compare(CompareOp.LT, L, L2);
    assertTrue(VarkaKernelPlan.of(List.of(lt), VarkaMatrixRuns.BASE).refusal().isEmpty());
  }
}
