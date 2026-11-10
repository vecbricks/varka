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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.HashSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark.Arm;
import org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark.Data;
import org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark.Form;
import org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark.Op;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;

/**
 * The decimal remainder spike's loops against {@code BigInteger}, in every build (VARKA-577), at
 * a small row count and in whatever code the JVM runs the loops in, so that a wrong loop fails a
 * pull request. Which form is faster is not this suite's question.
 */
@VarkaSuite
public final class VarkaDecimalRemainderSpikeSuite {

  /** Several 64-row words, and a row count no lane group straddles at any width. */
  private static final int ROWS = 64 * 97;

  @Test
  @DisplayName("every loop in every form agrees with BigInteger")
  void everyLoopAgrees() {
    VarkaDecimalRemainderSpikeBenchmark.checkAll(ROWS);
  }

  @Test
  @DisplayName("each operation is built in the three forms of section 2.3, a loop an arm")
  void armsCoverThePlan() {
    // A loop of its own an arm, so that each operation times its own compiled code.
    var methods = new HashSet<String>();
    for (Op op : Op.values()) {
      var forms = EnumSet.noneOf(Form.class);
      for (Arm arm : VarkaDecimalRemainderSpikeBenchmark.arms()) {
        if (arm.op() == op) {
          assertTrue(forms.add(arm.form()), arm.label() + " twice");
          assertTrue(arm.method().startsWith("loop"), arm.method() + " is not a loop method");
          assertTrue(methods.add(arm.method()), arm.method() + " serves two arms");
        }
      }
      assertEquals(EnumSet.allOf(Form.class), forms, op.title);
    }
  }

  @Test
  @DisplayName("the columns reach every case the remainder's rules decide")
  void dataReachesEveryCase() {
    try (Arena arena = Arena.ofConfined();
        Data d = VarkaDecimalRemainderSpikeBenchmark.data(arena, ROWS)) {
      // By the signs of x and y (0 at or above zero, 1 below), rows with a nonzero remainder.
      int[][] bySigns = new int[2][2];
      int zeroDivisor = 0;
      int exact = 0;
      int largest = 0;
      int unitDivisor = 0;
      int twoWordDivisor = 0;
      int bigQuotient = 0;
      for (int i = 0; i < ROWS; i++) {
        BigInteger x = VarkaDecimalRemainderSpikeBenchmark.slot(d.x(), i);
        BigInteger y = VarkaDecimalRemainderSpikeBenchmark.slot(d.y(), i);
        if (y.signum() == 0) {
          zeroDivisor++;
          continue;
        }
        BigInteger r = x.remainder(y);
        if (r.signum() != 0) {
          bySigns[x.signum() < 0 ? 1 : 0][y.signum() < 0 ? 1 : 0]++;
        } else if (x.signum() != 0) {
          exact++;
        }
        largest += r.abs().equals(y.abs().subtract(BigInteger.ONE)) && r.signum() != 0 ? 1 : 0;
        unitDivisor += y.abs().equals(BigInteger.ONE) ? 1 : 0;
        twoWordDivisor += y.abs().bitLength() > 64 ? 1 : 0;
        boolean wideQuotient = x.divide(y).abs().bitLength() > 106;
        bigQuotient += wideQuotient && y.abs().compareTo(BigInteger.TWO) >= 0 ? 1 : 0;
      }
      for (int sx = 0; sx < 2; sx++) {
        for (int sy = 0; sy < 2; sy++) {
          assertTrue(bySigns[sx][sy] > ROWS / 32, "few nonzero remainders at signs " + sx + sy);
        }
      }
      assertTrue(zeroDivisor >= ROWS / 64, zeroDivisor + " zero divisors");
      assertTrue(exact >= ROWS / 64, exact + " exact multiples");
      assertTrue(largest >= ROWS / 64, largest + " remainders of |y| - 1");
      assertTrue(unitDivisor >= ROWS / 64, unitDivisor + " divisors of 1 or -1");
      assertTrue(twoWordDivisor > ROWS / 4, twoWordDivisor + " divisors past one word");
      // A quotient past 106 bits needs all three of E's rounds; a divisor of 1 or -1 is its own
      // case of the exactness argument, so these count only divisors of at least 2.
      assertTrue(bigQuotient >= ROWS / 64, bigQuotient + " quotients past 106 bits by |y| >= 2");
    }
  }
}
