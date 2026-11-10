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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalWord;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Word;

/**
 * The compiler's refusal of a condition in a kernel driven at a 64-bit lane, long or
 * {@code DOUBLE}, and of a boolean output over a decimal column (VARKA-596), on an x86 host
 * whose 64-bit species have two lanes (VARKA-503). The host is
 * one fact, passed in here, so both answers are checked on any
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
  private static final ColumnRef X = new ColumnRef(4, LaneType.DOUBLE);
  private static final ColumnRef X2 = new ColumnRef(5, LaneType.DOUBLE);
  private static final ColumnRef F = new ColumnRef(6, LaneType.FLOAT);
  private static final ColumnRef F2 = new ColumnRef(7, LaneType.FLOAT);

  private static Optional<Refusal> admit(VarkaVectorIR root, boolean twoLaneHost) {
    return VarkaKernelPlan.admit(List.of(root), VarkaMatrixRuns.BASE, twoLaneHost);
  }

  @Test
  @DisplayName("the host test is x86 at two long lanes or fewer, and nowhere else")
  void hostTest() {
    for (String arch : List.of("amd64", "x86_64")) {
      var x86 = new VarkaHostIsa.Reading(arch, "", "unread", 0);
      // One lane is -XX:MaxVectorSize=8, where C2 lowers no mask either.
      assertTrue(VarkaKernelPlan.twoLaneMasksFallBack(x86, 1), arch);
      assertTrue(VarkaKernelPlan.twoLaneMasksFallBack(x86, 2), arch);
      assertFalse(VarkaKernelPlan.twoLaneMasksFallBack(x86, 4), arch);
      assertFalse(VarkaKernelPlan.twoLaneMasksFallBack(x86, 8), arch);
    }
    // aarch64: two-lane masks are lowered there.
    var arm = new VarkaHostIsa.Reading("aarch64", "fp, asimd", null, 128);
    assertFalse(VarkaKernelPlan.twoLaneMasksFallBack(arm, 1));
    assertFalse(VarkaKernelPlan.twoLaneMasksFallBack(arm, 2));
    // The reading's form takes the architecture from the reading, whatever its feature list,
    // and the lanes from this JVM's long species.
    boolean fewLanes = Lane.LONG.preferredLanes <= 2;
    for (String arch : List.of("amd64", "x86_64")) {
      assertEquals(fewLanes, VarkaKernelPlan.twoLaneMasksFallBack(
          new VarkaHostIsa.Reading(arch, "", "unread", 0)), arch);
    }
    assertFalse(VarkaKernelPlan.twoLaneMasksFallBack(arm));
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
  @DisplayName("a DOUBLE condition root is refused on the two-lane host and admitted elsewhere, "
      + "a FLOAT one admitted on both")
  void doubleCondition() {
    // x86.ad's refusal at vlen == 2 does not depend on the element type, so a DOUBLE kernel at
    // 128 bits makes its masks as the Java fallback just as a long one does.
    var lt = new Compare(CompareOp.LT, X, X2);
    assertEquals(LaneType.DOUBLE, VarkaKernelPlan.of(List.of(lt), VarkaMatrixRuns.BASE).drive());
    Optional<Refusal> refused = admit(lt, true);
    assertTrue(refused.isPresent());
    assertEquals(Kind.TWO_LANE_CONDITION, refused.get().kind());
    assertTrue(admit(lt, false).isEmpty());
    // The null test Spark infers beside a comparison of a nullable double column.
    assertTrue(admit(new IsNotNull(X), true).isPresent());
    assertTrue(admit(new IsNotNull(X), false).isEmpty());
    // FLOAT has 32-bit lanes, four of them where a 64-bit species has two.
    assertTrue(admit(new Compare(CompareOp.LT, F, F2), true).isEmpty());
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
  @DisplayName("a boolean output over a decimal column is refused on the two-lane host and "
      + "admitted elsewhere, one over BIGINT or DOUBLE columns admitted on both")
  void decimalBooleanOutput() {
    // `d9 < d9b` on the long lane and `d38 < d38b` over the lane pair's words, as values.
    var d9 = new DecimalColumn(0, LaneType.LONG, 9);
    var d9b = new DecimalColumn(1, LaneType.LONG, 9);
    var d38 = new DecimalWord(2, Word.LOW, 38);
    var d38b = new DecimalWord(3, Word.LOW, 38);
    var pair = DecimalWords.compare(CompareOp.LT, d38, new DecimalWord(2, Word.HIGH, 38), d38b,
        new DecimalWord(3, Word.HIGH, 38));
    for (VarkaVectorIR value : List.of(new BoolOutput(new Compare(CompareOp.LT, d9, d9b)),
        new BoolOutput(pair))) {
      Optional<Refusal> refused = admit(value, true);
      assertTrue(refused.isPresent(), value.toString());
      assertEquals(Kind.TWO_LANE_CONDITION, refused.get().kind());
      assertEquals(VarkaKernelPlan.TWO_LANE_DECIMAL_VALUE_REASON, refused.get().reason());
      assertTrue(admit(value, false).isEmpty(), value.toString());
    }
    // Their loads are one plain vector: at two lanes BIGINT beat the row path and DOUBLE tied.
    assertTrue(admit(new BoolOutput(new Compare(CompareOp.LT, L, L2)), true).isEmpty());
    assertTrue(admit(new BoolOutput(new Compare(CompareOp.LT, X, X2)), true).isEmpty());
    // A decimal value that is not a condition is not this refusal's.
    assertTrue(admit(new Greatest(d9, d9b), true).isEmpty());
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
