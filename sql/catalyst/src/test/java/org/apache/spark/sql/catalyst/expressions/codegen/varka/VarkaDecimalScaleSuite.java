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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.analysis.DecimalPrecisionTypeCoercion;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalScale.Entry;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.DecimalType$;

/**
 * The constant table of the {@code HALF_UP} rescale down and its arithmetic, with no kernel
 * (VARKA-363 3 items 1 and 4, the parts that need no 128-bit pair): the table against the figures
 * the plan recorded, each constant against its own first failure, the rescale over the table's
 * values against {@link BigDecimal}, and Spark's result types against the cut each caller needs.
 */
@VarkaSuite
public final class VarkaDecimalScaleSuite {

  private static final BigInteger TEN_38_LESS_1 = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);

  private static BigInteger pow10(int k) {
    return BigInteger.TEN.pow(k);
  }

  private static String planFile(String name) throws IOException {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    return Files.readString(Path.of(home, "sql", "varka", "plans", "m8", "results", name),
        StandardCharsets.UTF_8);
  }

  // -----------------------------------------------------------------------------------------
  // The table.
  // -----------------------------------------------------------------------------------------

  private static final Pattern MAGIC_ROW = Pattern.compile(
      "^\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+) x (\\d+) =\\s+(\\d+)\\s+\\d+ ok$");

  @Test
  @DisplayName("the table gives the shift, width and limb products of results/VARKA-363-magic.txt")
  void tableMatchesMagicFile() throws IOException {
    var bounds = List.of(TEN_38_LESS_1, TEN_38_LESS_1.shiftLeft(1),
        TEN_38_LESS_1.multiply(TEN_38_LESS_1));
    String[] sections = planFile("VARKA-363-magic.txt").split("(?m)^== ");
    assertEquals(bounds.size() + 1, sections.length, "the file has three tables");
    for (int s = 0; s < bounds.size(); s++) {
      int rows = 0;
      for (String line : sections[s + 1].split("\n")) {
        Matcher m = MAGIC_ROW.matcher(line);
        if (!m.matches()) {
          continue;
        }
        int k = Integer.parseInt(m.group(1));
        Entry entry = VarkaDecimalScale.of(k, bounds.get(s));
        String what = "k = " + k + " at " + bounds.get(s).bitLength() + " bits";
        assertEquals(bounds.get(s), entry.bound(), what);
        assertEquals(Integer.parseInt(m.group(2)), entry.maxPreShifted().bitLength(), what);
        assertEquals(Integer.parseInt(m.group(3)), entry.shift(), what);
        assertEquals(Integer.parseInt(m.group(4)), entry.multiplier().bitLength(), what);
        assertEquals(Integer.parseInt(m.group(5)), entry.dividendLimbs(), what);
        assertEquals(Integer.parseInt(m.group(6)), entry.limbs().length, what);
        assertEquals(Integer.parseInt(m.group(7)), entry.products(), what);
        rows++;
      }
      assertEquals(VarkaDecimalScale.MAX_K, rows, "one row per k in table " + s);
    }
  }

  @Test
  @DisplayName("a shape takes the entry of the least rung at or above its bound")
  void lookupTakesTheLeastRung() {
    for (int k = VarkaDecimalScale.MIN_K; k <= VarkaDecimalScale.MAX_K; k++) {
      assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
          VarkaDecimalScale.of(k, pow10(18).subtract(BigInteger.ONE)).bound());
      assertEquals(BigInteger.ONE.shiftLeft(96).subtract(BigInteger.ONE),
          VarkaDecimalScale.of(k, BigInteger.ONE.shiftLeft(64)).bound());
      assertEquals(TEN_38_LESS_1, VarkaDecimalScale.of(k, TEN_38_LESS_1).bound());
      assertEquals(TEN_38_LESS_1.shiftLeft(1),
          VarkaDecimalScale.of(k, TEN_38_LESS_1.add(BigInteger.ONE)).bound());
      assertEquals(k, VarkaDecimalScale.of(k, TEN_38_LESS_1).k());
      // Each rung's edge: its own bound takes it, one more takes the next.
      for (int r = 0; r < VarkaDecimalScale.BOUNDS.size(); r++) {
        BigInteger rung = VarkaDecimalScale.BOUNDS.get(r);
        Entry at = VarkaDecimalScale.of(k, rung);
        assertEquals(rung, at.bound(), "k = " + k + " at rung " + r);
        assertEquals(k, at.k(), "k = " + k + " at rung " + r);
        if (r > 0) {
          BigInteger below = VarkaDecimalScale.BOUNDS.get(r - 1).add(BigInteger.ONE);
          assertEquals(rung, VarkaDecimalScale.of(k, below).bound(), "k = " + k + " past rung "
              + (r - 1));
        }
      }
    }
    assertThrows(IllegalArgumentException.class,
        () -> VarkaDecimalScale.of(1, BigInteger.ONE.shiftLeft(256)));
  }

  @Test
  @DisplayName("a k outside 1..39 has no entry, at the first, an inner and the last rung")
  void lookupRefusesAKOutsideTheTable() {
    for (BigInteger rung : List.of(VarkaDecimalScale.BOUNDS.get(0), BigInteger.ONE.shiftLeft(70),
        VarkaDecimalScale.BOUNDS.get(VarkaDecimalScale.BOUNDS.size() - 1))) {
      for (int k : List.of(VarkaDecimalScale.MIN_K - 1, VarkaDecimalScale.MAX_K + 1)) {
        assertThrows(IllegalArgumentException.class, () -> VarkaDecimalScale.of(k, rung),
            "k = " + k + " at " + rung.bitLength() + " bits");
      }
    }
  }

  @Test
  @DisplayName("an entry whose multiplier is not the round-up or whose shift is short is refused")
  void entryRefusesAWrongConstant() {
    Entry entry = VarkaDecimalScale.of(19, TEN_38_LESS_1);
    assertThrows(IllegalArgumentException.class, () -> new Entry(entry.k(), entry.bound(),
        entry.shift(), entry.multiplier().add(BigInteger.ONE)));
    BigInteger d = entry.divisor();
    BigInteger shorter = BigInteger.ONE.shiftLeft(entry.shift() - 1).add(d)
        .subtract(BigInteger.ONE).divide(d);
    assertThrows(IllegalArgumentException.class,
        () -> new Entry(entry.k(), entry.bound(), entry.shift() - 1, shorter));
  }

  @Test
  @DisplayName("every constant has the least shift that meets e * Nmax < 2^L")
  void shiftIsTheLeast() {
    for (Entry entry : VarkaDecimalScale.TABLE) {
      assertTrue(entry.error().multiply(entry.maxPreShifted())
          .compareTo(BigInteger.ONE.shiftLeft(entry.shift())) < 0, entry.toString());
      BigInteger power = BigInteger.ONE.shiftLeft(entry.shift() - 1);
      BigInteger d = entry.divisor();
      BigInteger smaller = power.add(d).subtract(BigInteger.ONE).divide(d);
      BigInteger error = smaller.multiply(d).subtract(power);
      assertTrue(error.multiply(entry.maxPreShifted()).compareTo(power) >= 0, entry.toString());
    }
  }

  private static boolean exact(Entry entry, BigInteger n) {
    return n.multiply(entry.multiplier()).shiftRight(entry.shift())
        .equals(n.divide(entry.divisor()));
  }

  @Test
  @DisplayName("a constant is exact to its first failure, which is past the bound, and fails there")
  void firstFailureIsPastTheBound() {
    var random = new Random(363);
    for (Entry entry : VarkaDecimalScale.TABLE) {
      BigInteger first = entry.firstFailure();
      assertTrue(first.compareTo(entry.maxPreShifted()) > 0, entry.toString());
      assertTrue(!exact(entry, first), "fails at " + first + " for " + entry);
      assertTrue(exact(entry, first.subtract(BigInteger.ONE)), entry.toString());
      for (int i = 0; i < 50; i++) {
        BigInteger below = new BigInteger(first.bitLength(), random).mod(first);
        assertTrue(exact(entry, below), below + " for " + entry);
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // The rescale over the reference arithmetic.
  // -----------------------------------------------------------------------------------------

  /**
   * The values the division is held to at {@code k} under a bound: the edges of the divisor
   * table (VARKA-363 3 item 1), {@code 10^k - 1}, {@code 10^k}, {@code 10^k + 1}, multiples of
   * {@code 5^k} and one either side, the ties {@code m * 10^k + 10^k / 2} and one either side, the
   * bound itself, zero and one, and seeded random draws.
   */
  private static List<BigInteger> values(int k, BigInteger bound, Random random) {
    BigInteger ten = pow10(k);
    BigInteger five = BigInteger.valueOf(5).pow(k);
    BigInteger half = ten.shiftRight(1);
    var candidates = new ArrayList<BigInteger>(List.of(BigInteger.ZERO, BigInteger.ONE, bound,
        bound.subtract(BigInteger.ONE), ten.subtract(BigInteger.ONE), ten,
        ten.add(BigInteger.ONE)));
    for (BigInteger m : List.of(BigInteger.ONE, BigInteger.TWO, bound.divide(five),
        bound.divide(five).subtract(BigInteger.ONE))) {
      for (int t = -1; t <= 1; t++) {
        candidates.add(five.multiply(m).add(BigInteger.valueOf(t)));
      }
    }
    for (BigInteger m : List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, bound.divide(ten),
        bound.divide(ten).subtract(BigInteger.ONE))) {
      for (int t = -1; t <= 1; t++) {
        candidates.add(ten.multiply(m).add(half).add(BigInteger.valueOf(t)));
      }
    }
    for (int i = 0; i < 200; i++) {
      candidates.add(new BigInteger(bound.bitLength(), random).mod(bound.add(BigInteger.ONE)));
    }
    return candidates.stream().filter(v -> v.signum() >= 0 && v.compareTo(bound) <= 0).toList();
  }

  @Test
  @DisplayName("the reference rescale equals BigDecimal's HALF_UP at every k and bound, both signs")
  void rescaleTableMatchesBigDecimal() {
    var random = new Random(363);
    for (Entry entry : VarkaDecimalScale.TABLE) {
      for (BigInteger v : values(entry.k(), entry.bound(), random)) {
        assertEquals(VarkaDecimalScale.floorDivideByFives(entry, v),
            VarkaDecimalScale.floorDivideByFivesLimbs(entry, v), v + " for " + entry);
        for (BigInteger signed : List.of(v, v.negate())) {
          BigInteger expected = new BigDecimal(signed, entry.k())
              .setScale(0, RoundingMode.HALF_UP).toBigIntegerExact();
          assertEquals(expected, VarkaDecimalScale.halfUpDivide(entry, signed),
              signed + " for " + entry);
        }
      }
    }
  }

  @Test
  @DisplayName("10^38 - 1 rescaled down by 1 and by 38 rounds as BigDecimal does")
  void nines() {
    for (int k : List.of(1, 38)) {
      Entry entry = VarkaDecimalScale.of(k, TEN_38_LESS_1);
      BigInteger expected = new BigDecimal(TEN_38_LESS_1, k).setScale(0, RoundingMode.HALF_UP)
          .toBigIntegerExact();
      assertEquals(expected, VarkaDecimalScale.halfUpDivide(entry, TEN_38_LESS_1));
    }
    assertEquals(pow10(37), VarkaDecimalScale.halfUpDivide(
        VarkaDecimalScale.of(1, TEN_38_LESS_1), TEN_38_LESS_1));
    assertEquals(BigInteger.ONE, VarkaDecimalScale.halfUpDivide(
        VarkaDecimalScale.of(38, TEN_38_LESS_1), TEN_38_LESS_1));
  }

  @Test
  @DisplayName("a magnitude past the entry's bound or a negative one is refused")
  void refusesAValueOutsideTheBound() {
    Entry entry = VarkaDecimalScale.of(6, TEN_38_LESS_1);
    assertThrows(IllegalArgumentException.class,
        () -> VarkaDecimalScale.floorDivideByFivesLimbs(entry, TEN_38_LESS_1.add(BigInteger.ONE)));
    assertThrows(IllegalArgumentException.class,
        () -> VarkaDecimalScale.floorDivideByFives(entry, BigInteger.ONE.negate()));
  }

  // -----------------------------------------------------------------------------------------
  // Spark's result types against the cut each caller needs (VARKA-363 3 item 4).
  // -----------------------------------------------------------------------------------------

  private static final int MAX_PRECISION = DecimalType.MAX_PRECISION();

  /** Spark's rules are {@code private[sql]} members of the companion, reached through it. */
  private static final DecimalType$ SPARK_TYPES = DecimalType$.MODULE$;

  /** What the sweep counts for one rule, in the wording of {@code VARKA-363-shapes.txt}. */
  private static final class Counts {
    int cut;
    int maxK;
    int atMaxPlusOne;
    int fromK28;
    int dead;
    int live;
    int pastLimb127;
    int widest;
    int above128;
    int within128;
    int past129;
  }

  private interface Rule {
    DecimalType apply(int precision, int scale);
  }

  private static BigInteger[] pow10Table() {
    var table = new BigInteger[2 * MAX_PRECISION + 2];
    for (int i = 0; i < table.length; i++) {
      table[i] = pow10(i);
    }
    return table;
  }

  /** The entry a shape of this bound takes must exist, and its dividend limbs must hold it. */
  private static void checkEntry(int k, BigInteger bound) {
    Entry entry = VarkaDecimalScale.of(k, bound);
    BigInteger preShifted = bound.shiftRight(k - 1);
    assertTrue(entry.bound().compareTo(bound) >= 0);
    assertTrue(VarkaDecimalScale.limbsOf(preShifted).length <= entry.dividendLimbs());
    assertTrue(entry.maxPreShifted().compareTo(preShifted) >= 0);
  }

  /** {@code Multiply}'s cut shapes under a rule: scale cut by {@code k}, the check, the bound. */
  private static Counts multiply(Rule rule, BigInteger[] pow) {
    var counts = new Counts();
    for (int p1 = 1; p1 <= MAX_PRECISION; p1++) {
      for (int s1 = 0; s1 <= p1; s1++) {
        for (int p2 = 1; p2 <= MAX_PRECISION; p2++) {
          for (int s2 = 0; s2 <= p2; s2++) {
            DecimalType result = rule.apply(p1 + p2 + 1, s1 + s2);
            int k = s1 + s2 - result.scale();
            if (k == 0) {
              continue;
            }
            BigInteger product = pow[p1].subtract(BigInteger.ONE)
                .multiply(pow[p2].subtract(BigInteger.ONE));
            BigInteger quotient = product.add(pow[k].shiftRight(1)).divide(pow[k]);
            counts.cut++;
            counts.maxK = Math.max(counts.maxK, k);
            counts.atMaxPlusOne += k == 39 ? 1 : 0;
            counts.fromK28 += k >= 28 ? 1 : 0;
            if (quotient.compareTo(pow[result.precision()].subtract(BigInteger.ONE)) > 0) {
              counts.live++;
            } else {
              counts.dead++;
            }
            counts.pastLimb127 += quotient.bitLength() > 127 ? 1 : 0;
            counts.widest = Math.max(counts.widest, product.bitLength());
            if (product.bitLength() > 128) {
              counts.above128++;
            } else {
              counts.within128++;
              assertTrue(p1 + p2 <= MAX_PRECISION, "a 128-bit product has p1 + p2 <= 38");
            }
            checkEntry(k, product);
          }
        }
      }
    }
    return counts;
  }

  /** Class C, an {@code Add} or {@code Subtract} whose scale is cut under the default rule. */
  private static Counts classC(BigInteger[] pow) {
    var counts = new Counts();
    for (int p1 = 1; p1 <= MAX_PRECISION; p1++) {
      for (int s1 = 0; s1 <= p1; s1++) {
        for (int p2 = 1; p2 <= MAX_PRECISION; p2++) {
          for (int s2 = 0; s2 <= p2; s2++) {
            int scale = Math.max(s1, s2);
            int precision = Math.max(p1 - s1, p2 - s2) + scale + 1;
            if (precision <= MAX_PRECISION) {
              continue;
            }
            int k = scale - SPARK_TYPES.adjustPrecisionScale(precision, scale).scale();
            if (k == 0) {
              continue;
            }
            BigInteger sum = pow[p1].subtract(BigInteger.ONE).multiply(pow[scale - s1])
                .add(pow[p2].subtract(BigInteger.ONE).multiply(pow[scale - s2]));
            counts.cut++;
            counts.maxK = Math.max(counts.maxK, k);
            counts.widest = Math.max(counts.widest, sum.bitLength());
            counts.past129 += sum.bitLength() > 129 ? 1 : 0;
            if (s1 == s2) {
              assertTrue(sum.compareTo(TEN_38_LESS_1.shiftLeft(1)) <= 0);
              assertTrue(VarkaDecimalScale.of(k, sum).products() <= 16, "2 words, 16 products");
            }
            checkEntry(k, sum);
          }
        }
      }
    }
    return counts;
  }

  /**
   * The comparison and choice pairs: an operand with a scale above the common type's is cut, and
   * the cast never overflows because the digits it keeps, {@code p - k}, are fewer than the
   * common type's precision (VARKA-363 0 item 7).
   */
  private static int[] coercion() {
    int cut = 0;
    int maxK = 0;
    for (int p1 = 1; p1 <= MAX_PRECISION; p1++) {
      for (int s1 = 0; s1 <= p1; s1++) {
        for (int p2 = 1; p2 <= MAX_PRECISION; p2++) {
          for (int s2 = 0; s2 <= p2; s2++) {
            DecimalType wider = DecimalPrecisionTypeCoercion.widerDecimalType(p1, s1, p2, s2);
            if (Math.max(s1, s2) <= wider.scale()) {
              continue;
            }
            cut++;
            maxK = Math.max(maxK, Math.max(s1, s2) - wider.scale());
            int[][] operands = {{p1, s1}, {p2, s2}};
            for (int[] operand : operands) {
              int k = operand[1] - wider.scale();
              if (k > 0) {
                assertTrue(operand[0] - k < wider.precision(), "the cast of (" + operand[0] + ", "
                    + operand[1] + ") to " + wider + " can overflow");
                checkEntry(k, pow10(operand[0]).subtract(BigInteger.ONE));
              }
            }
          }
        }
      }
    }
    return new int[] {cut, maxK};
  }

  /** The lines of {@code VARKA-363-shapes.txt} a rule's counts must reproduce. */
  private static List<String> multiplyLines(Counts c) {
    return List.of(
        "scale cut (363): " + c.cut + ", first",
        "cut k: min 1, max " + c.maxK + "; k = 39 at " + c.atMaxPlusOne + " pairs",
        "k >= 28 (5^k past 2^63): " + c.fromK28 + " pairs",
        "10^38 check dead: " + c.dead,
        "10^38 check live: " + c.live,
        "quotient past 2^127: " + c.pastLimb127,
        "widest product bound: " + c.widest + " bits; above 128 bits at " + c.above128
            + " cut pairs, at most 128 bits at " + c.within128);
  }

  @Test
  @DisplayName("Spark's cut shapes agree with 2.5's table and results/VARKA-363-shapes.txt")
  void typeSweepMatchesTheRecordedCounts() throws IOException {
    String recorded = planFile("VARKA-363-shapes.txt");
    BigInteger[] pow = pow10Table();
    String[] sections = recorded.split("(?m)^== ");
    // Sections: 1 Multiply true, 2 Multiply false, 3 Add true, 4 Add false, 5 coercion.
    Counts loss = multiply(SPARK_TYPES::adjustPrecisionScale, pow);
    for (String line : multiplyLines(loss)) {
      assertTrue(sections[1].contains(line), line);
    }
    Counts exact = multiply(SPARK_TYPES::bounded, pow);
    for (String line : multiplyLines(exact)) {
      assertTrue(sections[2].contains(line), line);
    }
    Counts sums = classC(pow);
    assertTrue(sections[3].contains("class C, scale cut (363): " + sums.cut + ", first"));
    assertTrue(sections[3].contains("class C: max k " + sums.maxK + "; widest exact sum after the "
        + "rescale up: " + sums.widest + " bits; past 129 bits at " + sums.past129 + " pairs"));
    int[] cuts = coercion();
    assertTrue(sections[5].contains("pairs where an operand is rescaled down: " + cuts[0]
        + ", max k " + cuts[1]));
  }
}
