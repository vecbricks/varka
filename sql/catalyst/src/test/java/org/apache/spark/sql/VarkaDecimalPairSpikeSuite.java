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

package org.apache.spark.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.math.BigInteger;
import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark.Arm;
import org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark.Data;
import org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark.Form;
import org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark.Op;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;

/**
 * The decimal lane pair spike's loops against {@code BigInteger}, in every build (VARKA-359): the
 * check the benchmark makes before it times an arm, here at a small row count and in whatever
 * code the JVM runs the loops in, so a wrong loop fails a pull request rather than the runner
 * dispatch that times it. Which code is faster is the benchmark's question, not this suite's.
 */
@VarkaSuite
public final class VarkaDecimalPairSpikeSuite {

  @Test
  @DisplayName("every loop in every form agrees with BigInteger")
  void everyLoopAgrees() {
    // Several 64-row words, and a row count no lane group straddles at any width.
    VarkaDecimalPairSpikeBenchmark.checkAll(64 * 97);
  }

  @Test
  @DisplayName("each loop is built in the forms section 2.1 names")
  void armsCoverThePlan() {
    for (Op op : Op.values()) {
      var forms = EnumSet.noneOf(Form.class);
      for (Arm arm : VarkaDecimalPairSpikeBenchmark.arms()) {
        if (arm.op() == op) {
          assertTrue(forms.add(arm.form()), arm.label() + " twice");
          assertTrue(arm.method().startsWith("loop"), arm.method() + " is not a loop method");
        }
      }
      boolean arithmetic = op == Op.PRODUCT || op == Op.PRODUCT_CHECK || op == Op.RESCALE;
      assertEquals(arithmetic ? EnumSet.of(Form.P, Form.S) : EnumSet.allOf(Form.class), forms,
          op.title);
    }
  }

  @Test
  @DisplayName("the columns reach every outcome each loop decides")
  void dataReachesEveryOutcome() {
    try (Arena arena = Arena.ofConfined()) {
      int n = 64 * 97;
      Data d = VarkaDecimalPairSpikeBenchmark.data(arena, n);
      BigInteger bound = VarkaDecimalPairSpikeBenchmark.TEN38;
      BigInteger half = BigInteger.TEN.pow(VarkaDecimalPairSpikeBenchmark.RESCALE_K)
          .shiftRight(1);
      BigInteger tenK = BigInteger.TEN.pow(VarkaDecimalPairSpikeBenchmark.RESCALE_K);
      int wild = 0;
      int lt = 0;
      int equal = 0;
      int sameHigh = 0;
      int overflow = 0;
      int lowZeroNegative = 0;
      int ties = 0;
      for (int i = 0; i < n; i++) {
        BigInteger x = d.xs()[i];
        BigInteger y = d.ys()[i];
        assertTrue(x.abs().compareTo(bound) < 0, "x out of precision at row " + i);
        assertEquals(x, VarkaDecimalPairSpikeBenchmark.slot(d.x(), i));
        assertTrue(y.abs().compareTo(bound) < 0, "y out of precision at row " + i);
        assertEquals(y, VarkaDecimalPairSpikeBenchmark.slot(d.y(), i));
        wild += d.wilds()[i].abs().compareTo(bound) >= 0 ? 1 : 0;
        lt += x.compareTo(y) < 0 ? 1 : 0;
        equal += x.equals(y) ? 1 : 0;
        sameHigh += !x.equals(y) && x.shiftRight(64).equals(y.shiftRight(64)) ? 1 : 0;
        overflow += x.multiply(y).abs().compareTo(bound) >= 0 ? 1 : 0;
        lowZeroNegative += x.signum() < 0 && x.longValue() == 0 ? 1 : 0;
        ties += x.abs().mod(tenK).equals(half) ? 1 : 0;
      }
      assertTrue(wild > n / 32 && wild < n / 8, wild + " wild rows");
      assertTrue(lt > n / 4 && lt < 3 * n / 4, lt + " rows with x < y");
      assertTrue(equal > n / 16, equal + " rows with x = y");
      assertTrue(sameHigh > n / 16, sameHigh + " rows where y has x's high word only");
      assertTrue(overflow > n / 4 && overflow < 3 * n / 4, overflow + " products past 10^38");
      assertTrue(lowZeroNegative > 0, "no negative value with a zero low word");
      assertTrue(ties > n / 97, ties + " HALF_UP ties");
    }
  }

  @Test
  @DisplayName("the harness accepts only a dontinline command that names this class's loops")
  void harnessNamesTheLoops() {
    String name = VarkaDecimalPairSpikeBenchmark.class.getName();
    String slashed = name.replace('.', '/');
    assertTrue(VarkaDecimalPairSpikeBenchmark.keepsTheLoopsOutOfLine(
        VarkaDecimalPairSpikeBenchmark.DONTINLINE_LOOPS));
    assertTrue(VarkaDecimalPairSpikeBenchmark.keepsTheLoopsOutOfLine(
        "-XX:CompileCommand=dontinline," + slashed + ".loop*"));
    assertFalse(VarkaDecimalPairSpikeBenchmark.keepsTheLoopsOutOfLine(
        "-XX:CompileCommand=dontinline,java.lang.String::indexOf"));
    assertFalse(VarkaDecimalPairSpikeBenchmark.keepsTheLoopsOutOfLine(
        "-XX:CompileCommand=dontinline," + name + "::loopCheckP"));
    assertFalse(VarkaDecimalPairSpikeBenchmark.keepsTheLoopsOutOfLine(
        "-XX:CompileCommand=print," + name + "::loop*"));
  }
}
