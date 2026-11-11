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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;

/**
 * {@code decimal_spec_check.smt2} (VARKA-506 2.5): the hand-written specification of Spark's
 * decimal arithmetic, {@code decimal_spec.smt2}, against Spark's own {@link Decimal}. For each
 * {@code spec.dec.*} definition, one check that it gives what {@code Decimal} gives over boundary
 * operands - zero, the ends of a long and of 18 and 38 digits, the values one past a precision,
 * and the ties of a rounding - the expected values computed here, by Spark's code, while
 * rendering. So the specification a decimal refinement states is held to Spark and to nothing of
 * Varka's: no IR, emitter or compiler class is read here. Rendered by
 * {@link VarkaProofFiles#render}, whose suite holds the committed file to this.
 */
final class VarkaDecimalSpecFile {

  private VarkaDecimalSpecFile() {
  }

  /** Spark's widest decimal precision, 38: every definition is stated up to it. */
  private static final int MAX_PRECISION = DecimalType.MAX_PRECISION();

  /**
   * The unscaled operands of a one-operand definition: zero and one of both signs, the ties and
   * their neighbours of a rounding by one and two digits, the ends of 18 digits and of a long, a
   * value past a long, and the ends of 38 digits.
   */
  private static final List<BigInteger> UNSCALED = signed(BigInteger.ONE, BigInteger.valueOf(4),
      BigInteger.valueOf(5), BigInteger.valueOf(15), BigInteger.valueOf(149),
      BigInteger.valueOf(150), nines(18), BigInteger.valueOf(Long.MAX_VALUE),
      BigInteger.TWO.pow(64), nines(MAX_PRECISION));

  /**
   * The unscaled operands of a two-operand definition, every pair of them taken: the same ends,
   * fewer values between them.
   */
  private static final List<BigInteger> PAIRED = signed(BigInteger.ONE, BigInteger.valueOf(5),
      nines(18), BigInteger.TWO.pow(64), nines(MAX_PRECISION));

  /** The scales the operands are read at: an integer, two digits, and the widest long's. */
  private static final int[] SCALES = {0, 2, 18};

  /** The scale pairs of a two-operand definition: equal, and each side the wider. */
  private static final int[][] SCALE_PAIRS = {{2, 2}, {0, 18}, {18, 0}};

  /** Zero, each value and its negation, and the smallest long, whose negation is not one. */
  private static List<BigInteger> signed(BigInteger... values) {
    var out = new ArrayList<BigInteger>();
    out.add(BigInteger.ZERO);
    for (BigInteger v : values) {
      out.add(v);
      out.add(v.negate());
    }
    out.add(BigInteger.valueOf(Long.MIN_VALUE));
    return List.copyOf(out);
  }

  /** {@code 10^p - 1}, the largest unscaled value of {@code p} digits. */
  private static BigInteger nines(int p) {
    return BigInteger.TEN.pow(p).subtract(BigInteger.ONE);
  }

  static String render() {
    var out = new StringBuilder(VarkaProofFiles.LICENSE);
    out.append("""
        ; The decimal specification, decimal_spec.smt2, against Spark's Decimal (VARKA-506 2.5):
        ; for each spec.dec.* definition, a check that it gives what Spark's Decimal gives over
        ; boundary operands - zero, the ends of 18 and 38 digits and of a long, a value past a
        ; long, and the ties of a rounding, at scales 0, 2 and 18. The expected values were
        ; computed by Spark's own Decimal while rendering, and nothing of Varka's is read, so a
        ; refinement that states its specification with these definitions states Spark's. Each
        ; check is unsat when every equation in it holds.
        ;
        ; prelude: decimal_spec.smt2
        """);
    out.append(VarkaProofFiles.rendered("QF_NIA"));
    powers(out);
    fits(out);
    unary(out, "spec.dec.neg", "Decimal's unary minus", Decimal::unary_$minus);
    unary(out, "spec.dec.abs", "Decimal.abs", Decimal::abs);
    sums(out, "spec.dec.add", "Decimal's +", Decimal::$plus);
    sums(out, "spec.dec.sub", "Decimal's -", Decimal::$minus);
    products(out);
    compares(out);
    halfUp(out);
    return out.toString();
  }

  /** {@code 10^k} for every precision and scale a decimal can have, and 0 one past them. */
  private static void powers(StringBuilder out) {
    var eqs = new ArrayList<String>();
    for (int k = 0; k <= MAX_PRECISION; k++) {
      eqs.add("(= (spec.dec.pow10 " + k + ") " + lit(BigInteger.TEN.pow(k)) + ")");
    }
    eqs.add("(= (spec.dec.pow10 " + (MAX_PRECISION + 1) + ") 0)");
    equations(out, "spec.dec.pow10 is 10^k from 0 to " + MAX_PRECISION, eqs);
  }

  /**
   * {@code spec.dec.fits} is whether Spark's {@code toPrecision} keeps the value, the step
   * {@code checkDecimalOverflow} takes after every decimal operation: at every precision, the
   * largest value of it and of one digit fewer kept, and the smallest of one digit more not.
   */
  private static void fits(StringBuilder out) {
    var eqs = new ArrayList<String>();
    for (int p = 1; p <= MAX_PRECISION; p++) {
      BigInteger top = BigInteger.TEN.pow(p);
      for (BigInteger u : List.of(BigInteger.ZERO, top.subtract(BigInteger.ONE), top,
          top.divide(BigInteger.TEN).subtract(BigInteger.ONE))) {
        for (BigInteger v : List.of(u, u.negate())) {
          for (int s : new int[] {0, p / 2}) {
            boolean kept = spark(v, s).toPrecision(p, s, Decimal.ROUND_HALF_UP(), true, null)
                != null;
            eqs.add("(= (spec.dec.fits " + lit(v) + " " + p + ") " + kept + ")");
          }
        }
      }
    }
    equations(out, "spec.dec.fits is Decimal.toPrecision keeping the value, at every precision",
        eqs);
  }

  private static void unary(StringBuilder out, String name, String spark,
      UnaryOperator<Decimal> op) {
    var eqs = new ArrayList<String>();
    for (BigInteger u : UNSCALED) {
      for (int s : SCALES) {
        Decimal r = op.apply(spark(u, s));
        eqs.add("(= (" + name + " " + lit(u) + ") " + lit(unscaledAt(r, s)) + ")");
      }
    }
    equations(out, name + " is " + spark + ", at the operand's scale", eqs);
  }

  /** A sum or a difference, at the wider of the two scales, as Spark's operator gives it. */
  private static void sums(StringBuilder out, String name, String spark,
      BinaryOperator<Decimal> op) {
    var eqs = new ArrayList<String>();
    for (BigInteger a : PAIRED) {
      for (BigInteger b : PAIRED) {
        for (int[] s : SCALE_PAIRS) {
          Decimal r = op.apply(spark(a, s[0]), spark(b, s[1]));
          eqs.add("(= (" + name + " " + lit(a) + " " + s[0] + " " + lit(b) + " " + s[1] + ") "
              + lit(unscaledAt(r, Math.max(s[0], s[1]))) + ")");
        }
      }
    }
    equations(out, name + " is " + spark + ", at the wider scale", eqs);
  }

  /**
   * The product, at the sum of the scales, wherever it has fewer than 39 digits, past which
   * Spark's {@code *} rounds it toward zero ({@code MathContext(39, DOWN)}) and the definition,
   * exact, is not Spark's; a pair past that is left out here and refused by the shapes' rule.
   */
  private static void products(StringBuilder out) {
    var eqs = new ArrayList<String>();
    BigInteger limit = BigInteger.TEN.pow(MAX_PRECISION + 1);
    for (BigInteger a : PAIRED) {
      for (BigInteger b : PAIRED) {
        if (a.multiply(b).abs().compareTo(limit) >= 0) {
          continue;
        }
        for (int[] s : SCALE_PAIRS) {
          Decimal r = spark(a, s[0]).$times(spark(b, s[1]));
          eqs.add("(= (spec.dec.mul " + lit(a) + " " + lit(b) + ") "
              + lit(unscaledAt(r, s[0] + s[1])) + ")");
        }
      }
    }
    equations(out, "spec.dec.mul is Decimal's *, at the sum of the scales, below 10^39", eqs);
  }

  private static void compares(StringBuilder out) {
    var eqs = new ArrayList<String>();
    for (BigInteger a : PAIRED) {
      for (BigInteger b : PAIRED) {
        for (int[] s : SCALE_PAIRS) {
          int c = Integer.signum(spark(a, s[0]).compare(spark(b, s[1])));
          eqs.add("(= (spec.dec.cmp " + lit(a) + " " + s[0] + " " + lit(b) + " " + s[1] + ") "
              + lit(c) + ")");
        }
      }
    }
    equations(out, "spec.dec.cmp is the sign of Decimal.compare", eqs);
  }

  /**
   * {@code spec.dec.halfup} is Spark's {@code changePrecision} to a smaller scale under
   * {@code ROUND_HALF_UP}, through {@code toPrecision}: every operand at scale 18, cut by 0 to 3
   * digits and by 18, so the ties at 5, 15, 150 and their negations are taken.
   */
  private static void halfUp(StringBuilder out) {
    var eqs = new ArrayList<String>();
    int s = 18;
    for (BigInteger u : UNSCALED) {
      for (int k : new int[] {0, 1, 2, 3, s}) {
        Decimal r = spark(u, s).toPrecision(MAX_PRECISION, s - k, Decimal.ROUND_HALF_UP(), true,
            null);
        eqs.add("(= (spec.dec.halfup " + lit(u) + " " + k + ") " + lit(unscaledAt(r, s - k))
            + ")");
      }
    }
    equations(out, "spec.dec.halfup is Decimal.toPrecision to a smaller scale, ROUND_HALF_UP",
        eqs);
  }

  /** Spark's decimal of the unscaled {@code u} at scale {@code s}. */
  private static Decimal spark(BigInteger u, int s) {
    return Decimal.apply(new BigDecimal(u, s));
  }

  /**
   * The unscaled value of {@code d} at scale {@code s}, which must be exact: a result at another
   * scale than the definition states is a disagreement, and fails the render.
   */
  private static BigInteger unscaledAt(Decimal d, int s) {
    return d.toJavaBigDecimal().setScale(s).unscaledValue();
  }

  /** One check that every equation holds: unsat for the negation of their conjunction. */
  private static void equations(StringBuilder out, String what, List<String> eqs) {
    var lines = new ArrayList<String>();
    lines.add("(assert (not (and");
    for (String e : eqs) {
      lines.add("  " + e);
    }
    lines.add(")))");
    VarkaProofFiles.check(out, what + ", on " + eqs.size() + " operand sets", "unsat", lines);
  }

  private static String lit(long v) {
    return VarkaProofFiles.lit(v);
  }

  private static String lit(BigInteger v) {
    return v.signum() >= 0 ? v.toString() : "(- " + v.negate() + ")";
  }
}
