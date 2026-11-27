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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark.Arm;
import org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark.Data;
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
    var labels = new HashSet<String>();
    for (Op op : Op.values()) {
      var forms = new ArrayList<String>();
      for (Arm arm : VarkaDecimalPairSpikeBenchmark.arms()) {
        if (arm.op() == op) {
          assertTrue(labels.add(arm.label()), arm.label() + " twice");
          forms.add((arm.form() + " " + arm.variant()).strip());
          assertTrue(arm.method().startsWith("loop"), arm.method() + " is not a loop method");
        }
      }
      // Row 360's loops are P in its two carry forms, and S (`VARKA-360.md` 2.1).
      List<String> expected = switch (op) {
        case PRODUCT, PRODUCT_CHECK, RESCALE -> List.of("P", "S");
        case ADD, SUB, ADD_CHECK -> List.of("P COMPARE", "P BITS", "S");
        case CHECK, LT, NEG, ABS, CHOICE -> List.of("P", "A", "S");
      };
      assertEquals(expected, forms, op.title);
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
      // Rows where y has x's high word only, by x's sign (0 at or above zero, 1 below) and then
      // by whether x < y, so the low words decide the comparison both ways at both signs.
      int[][] sameHighBySignAndLt = new int[2][2];
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
        if (!x.equals(y) && x.shiftRight(64).equals(y.shiftRight(64))) {
          sameHigh++;
          sameHighBySignAndLt[x.signum() < 0 ? 1 : 0][x.compareTo(y) < 0 ? 1 : 0]++;
        }
        overflow += x.multiply(y).abs().compareTo(bound) >= 0 ? 1 : 0;
        lowZeroNegative += x.signum() < 0 && x.longValue() == 0 ? 1 : 0;
        ties += x.abs().mod(tenK).equals(half) ? 1 : 0;
      }
      assertTrue(wild > n / 32 && wild < n / 8, wild + " wild rows");
      assertTrue(lt > n / 4 && lt < 3 * n / 4, lt + " rows with x < y");
      assertTrue(equal > n / 16, equal + " rows with x = y");
      assertTrue(sameHigh > n / 16, sameHigh + " rows where y has x's high word only");
      for (int sign = 0; sign < 2; sign++) {
        for (int less = 0; less < 2; less++) {
          assertTrue(sameHighBySignAndLt[sign][less] > 0, "no row where y has x's high word only,"
              + " x " + (sign == 0 ? ">= 0" : "< 0") + " and x < y " + (less == 1));
        }
      }
      assertTrue(overflow > n / 4 && overflow < 3 * n / 4, overflow + " products past 10^38");
      assertTrue(lowZeroNegative > 0, "no negative value with a zero low word");
      assertTrue(ties > n / 97, ties + " HALF_UP ties");
      // Row 360's columns: within DECIMAL(20), and their low words carrying and borrowing at
      // both signs of the sum and the difference, with a sum past 10^38 among the wide ones.
      // A sum in [10^38, 2^127) overflows DECIMAL(38) without wrapping the 128 bits, so it does
      // not reach what ADD_CHECK's lemma is about: the sums at or past 2^127 and below -2^127,
      // whose stored words are the exact sum less or plus 2^128, are counted on their own.
      BigInteger bound20 = BigInteger.TEN.pow(20);
      BigInteger two64 = BigInteger.ONE.shiftLeft(64);
      int[][] carriesBySign = new int[2][2];
      int[][] borrowsBySign = new int[2][2];
      int wideOverflow = 0;
      int wrapsAbove = 0;
      int wrapsBelow = 0;
      BigInteger two127 = BigInteger.ONE.shiftLeft(127);
      for (int i = 0; i < n; i++) {
        BigInteger x = d.x20s()[i];
        BigInteger y = d.y20s()[i];
        assertTrue(x.abs().compareTo(bound20) < 0, "x20 out of precision at row " + i);
        assertTrue(y.abs().compareTo(bound20) < 0, "y20 out of precision at row " + i);
        assertEquals(x, VarkaDecimalPairSpikeBenchmark.slot(d.x20(), i));
        assertEquals(y, VarkaDecimalPairSpikeBenchmark.slot(d.y20(), i));
        boolean carry = x.mod(two64).add(y.mod(two64)).compareTo(two64) >= 0;
        boolean borrow = x.mod(two64).compareTo(y.mod(two64)) < 0;
        carriesBySign[x.add(y).signum() < 0 ? 1 : 0][carry ? 1 : 0]++;
        borrowsBySign[x.subtract(y).signum() < 0 ? 1 : 0][borrow ? 1 : 0]++;
        BigInteger wideSum = d.xs()[i].add(d.ys()[i]);
        wideOverflow += wideSum.abs().compareTo(bound) >= 0 ? 1 : 0;
        wrapsAbove += wideSum.compareTo(two127) >= 0 ? 1 : 0;
        wrapsBelow += wideSum.compareTo(two127.negate()) < 0 ? 1 : 0;
      }
      for (int sign = 0; sign < 2; sign++) {
        for (int bit = 0; bit < 2; bit++) {
          assertTrue(carriesBySign[sign][bit] > 0, "no sum of sign " + sign + " and carry " + bit);
          assertTrue(borrowsBySign[sign][bit] > 0, "no difference of sign " + sign + " and borrow "
              + bit);
        }
      }
      assertTrue(wideOverflow > 0, "no DECIMAL(38) sum past 10^38");
      assertTrue(wrapsAbove > 0, "no DECIMAL(38) sum at or past 2^127: without one no row's sum"
          + " wraps 128 bits upward, and ADD_CHECK's lemma, that the wrapped sum fails the 10^38"
          + " check exactly where the exact one does, is checked on unwrapped sums only");
      assertTrue(wrapsBelow > 0, "no DECIMAL(38) sum below -2^127: without one no row's sum"
          + " wraps 128 bits downward, and ADD_CHECK's lemma, that the wrapped sum fails the"
          + " 10^38 check exactly where the exact one does, is checked on unwrapped sums only");
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
