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

import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.LongBinaryOperator;

import com.google.common.math.IntMath;

import org.apache.spark.sql.catalyst.expressions.BitmapExpressionUtils;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDivisionLowering.MulHiMagic;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaValueRange.Range;
import org.apache.spark.sql.catalyst.util.DateTimeConstants;

/**
 * The rendered files under {@code sql/varka/proofs/} (VARKA-240, VARKA-241): the proofs whose
 * constants come from the code, and the check of the prelude against the JVM.
 * {@code VarkaProofFilesSuite} fails when a committed file differs from what this renders, so a
 * proof cannot go on proving a constant the code no longer has. {@code java.smt2}, the prelude, is
 * written by hand and is not here.
 *
 * <p>Every check is an {@code (echo "<what it shows>: expect sat|unsat")} followed by its
 * {@code (check-sat)} between {@code push} and {@code pop}; {@code dev/varka_prove.sh} pairs each
 * verdict with the expectation before it.
 */
final class VarkaProofFiles {

  private VarkaProofFiles() {
  }

  /**
   * The int-lane constant divisors the multiply-high proof and {@code VarkaEmitterDivisionSuite}'s
   * sweep and matrix cover: {@code extract(YEAR FROM ym)}'s 12, the fuzz grammar's list
   * ({@code VarkaIrGrammar.Shapes.CONST_DIVIDE_DIVISORS}), 60 and 3600, and two edges of
   * {@link VarkaDivisionLowering#signedMagic}: 196611, the first divisor whose shift Theorem 5.1
   * raises past the book's, and {@code Integer.MIN_VALUE}, the magnitude 2^31.
   *
   * <p>No compiler site builds an int-lane division by 60 or 3600 (checked 27 October 2026,
   * VARKA-496): the only int-lane {@code ConstDivide}s are the interval compiler's, by 12 and by
   * {@code ym / d}'s literal, and {@code TIME}'s divisions by 60 are on the long lane. They were
   * put here for the {@code TIME} split form ({@code m9/SCOPE.md}'s catalogue row of a
   * {@code TIME} as seconds and nanoseconds), which is not built and would divide through
   * {@code BoundedDivide}, a lowering this proof does not state. They stay as two of the open set
   * {@code ym / d} can divide by, as every divisor here is, and because the rendered range proofs
   * and {@code VarkaEmitterDivisionSuite}'s sweep iterate this list; proving that set whole is
   * {@code m9/SCOPE.md} item 116.
   */
  static final List<Integer> INT_DIVISORS =
      List.of(12, 2, 3, 7, 100, -3, -12, 60, 3600, 196611, Integer.MIN_VALUE);

  /** Each rendered file's name under {@code sql/varka/proofs/}, and its text. */
  static Map<String, String> render() {
    var files = new LinkedHashMap<String, String>();
    files.put("java_check.smt2", javaCheck());
    files.put("int_mulhi_divide.smt2", intMulHiDivide());
    files.put("long_divide.smt2", longDivide());
    files.put("chrono_divide.smt2", chronoDivide());
    files.put("floor_mod7.smt2", floorMod7());
    files.put("leap_hash.smt2", leapHash());
    files.put("value_range.smt2", valueRange());
    files.put("range_analysis.smt2", rangeAnalysis());
    files.put("range_calendar.smt2", rangeCalendar());
    files.put("time_bounds.smt2", timeBounds());
    files.put("bitmap.smt2", bitmap());
    files.put("narrow_wrap.smt2", narrowWrap());
    files.put("narrow_overflow.smt2", narrowOverflow());
    files.put("narrow_range.smt2", narrowRange());
    files.put("narrow_deferred.smt2", narrowDeferred());
    files.put("ym_divide.smt2", ymDivide());
    files.put("decimal_slot.smt2", decimalSlot());
    files.put("refinement.smt2", VarkaRefinementFile.render());
    return files;
  }

  static final String LICENSE = """
      ; Licensed to the Apache Software Foundation (ASF) under one or more
      ; contributor license agreements.  See the NOTICE file distributed with
      ; this work for additional information regarding copyright ownership.
      ; The ASF licenses this file to You under the Apache License, Version 2.0
      ; (the "License"); you may not use this file except in compliance with
      ; the License.  You may obtain a copy of the License at
      ;
      ;    http://www.apache.org/licenses/LICENSE-2.0
      ;
      ; Unless required by applicable law or agreed to in writing, software
      ; distributed under the License is distributed on an "AS IS" BASIS,
      ; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      ; See the License for the specific language governing permissions and
      ; limitations under the License.

      """;

  private static final String RENDERED = rendered("QF_NIA");

  /**
   * The lines every rendered file shares after its own header, ending in its logic. A file
   * declares {@code ALL} where cvc5 decides its checks only under it (VARKA-242.md 2.2) or where
   * it reasons over bit-vectors, which {@code QF_NIA} does not admit.
   */
  static String rendered(String logic) {
    return """
        ;
        ; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
        ; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
        ; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

        (set-logic %s)
        """.formatted(logic);
  }

  // -----------------------------------------------------------------------------------------
  // The multiply-high division (VARKA-149), proven per divisor.
  // -----------------------------------------------------------------------------------------

  /**
   * {@code int_mulhi_divide.smt2}: for every divisor in {@link #INT_DIVISORS}, the form
   * {@code emitMulHiDivide} emits, with the pair {@code signedMagic} derives, is Java's
   * {@code n / d} for every int {@code n} - and is not with the multiplier lowered by one or the
   * shift raised by one, which shows the statement tells the real constants from their
   * neighbours.
   */
  static String intMulHiDivide() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The int lane's multiply-high division (VARKA-149) is exact: for each divisor d below and
        ; every int n, what the lanes of VarkaDivisionLowering.emitMulHiDivide compute is Java's
        ; n / d. Per lane, with (Mu, shift) = VarkaDivisionLowering.signedMagic(|d|):
        ;
        ;   long p = (long) n * Mu;        I2L, then LongVector.mul by the multiplier
        ;   int q = (int) (p >> shift);    ASHR by the shift, then L2I
        ;   q = q + (n >>> 31);            the dividend's sign bit, LSHR 31, added
        ;   if (d < 0) q = q * -1;         IntVector.mul(-1) for a negative divisor
        ;
        ; Each divisor has three checks: the form is n / d for every int n (unsat: no n where it
        ; is not), and it is not with the multiplier lowered by one, or with the shift raised by
        ; one (sat: a counterexample exists), which shows the statement can tell the derived
        ; constants from their neighbours.
        """);
    out.append(RENDERED);
    out.append("""

        (define-fun mulhi.divide ((n Int) (mu Int) (shift Int) (negate Bool)) Int
          (let ((q (jint.add (l2i (jlong.shr (jlong.mul (i2l n) mu) shift)) (jint.ushr n 31))))
            (ite negate (jint.mul q (- 1)) q)))

        (declare-const n Int)
        (assert (jint.in n))
        """);
    check(out, "the dividend's domain admits Integer.MIN_VALUE", "sat",
        List.of("(assert (= n jint.MIN))"));
    check(out, "the dividend's domain admits Integer.MAX_VALUE", "sat",
        List.of("(assert (= n jint.MAX))"));
    for (int d : INT_DIVISORS) {
      MulHiMagic magic = VarkaDivisionLowering.signedMagicForTest(Math.abs((long) d));
      String negate = d < 0 ? "true" : "false";
      out.append("\n; d = ").append(d).append(": signedMagic(").append(magic.divisor())
          .append(") = (").append(magic.multiplier()).append(", ").append(magic.shift())
          .append(")\n");
      check(out, "d = " + d + ": the form is n / d for every int n", "unsat",
          List.of(notDivides(magic.multiplier(), magic.shift(), negate, d)));
      check(out, "d = " + d + ": with the multiplier lowered by one it is not", "sat",
          List.of(notDivides(magic.multiplier() - 1, magic.shift(), negate, d)));
      check(out, "d = " + d + ": with the shift raised by one it is not", "sat",
          List.of(notDivides(magic.multiplier(), magic.shift() + 1, negate, d)));
    }
    return out.toString();
  }

  private static String notDivides(long mu, int shift, String negate, int d) {
    return "(assert (not (= (mulhi.divide n " + mu + " " + shift + " " + negate + ") (jint.div n "
        + lit(d) + "))))";
  }

  // -----------------------------------------------------------------------------------------
  // The long lane's two division forms (VARKA-241), proven per divisor.
  // -----------------------------------------------------------------------------------------

  /**
   * The odd part of a day's microseconds, {@code 86,400,000,000 = 2^13 * 10,546,875}: the
   * divisor of the {@code ConstDivide} under {@code CAST(ntz AS DATE)}'s floor division
   * ({@code VarkaNodeCompiler.floorDivLong}, VARKA-338).
   */
  static final long NTZ_DAY_ODD = DateTimeConstants.MICROS_PER_DAY
      >> Long.numberOfTrailingZeros(DateTimeConstants.MICROS_PER_DAY);

  /**
   * The long-lane constant divisors: every one the {@code TIME} compiler divides by
   * ({@code VarkaTimeCompiler}'s {@code subtractTimes}, {@code timeDiff}, {@code timeTrunc}, the
   * conversions to milliseconds and microseconds, the field extracts and
   * {@code remainderOfSixty}), which are all a long-lane {@code ConstDivide} the compiler builds,
   * -60, which takes the sign branch a positive divisor does not, and {@link #NTZ_DAY_ODD}.
   * {@code VarkaDivisorProofSuite} holds the list to the compiler (VARKA-496): it fails on a
   * long-lane divisor the coverage rows or the cast matrix compile that is missing here, and on
   * one here that none compiles, -60 apart.
   */
  static final List<Long> LONG_DIVISORS = List.of(
      DateTimeConstants.NANOS_PER_MICROS,
      DateTimeConstants.NANOS_PER_MILLIS,
      DateTimeConstants.NANOS_PER_SECOND,
      DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE,
      DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE
          * DateTimeConstants.MINUTES_PER_HOUR,
      (long) DateTimeConstants.SECONDS_PER_MINUTE,
      -60L,
      NTZ_DAY_ODD);

  private static final long P52 = 1L << 52;
  private static final long P53 = 1L << 53;

  /**
   * {@code long_divide.smt2}: for every divisor in {@link #LONG_DIVISORS}, both forms
   * {@code VarkaDivisionLowering.emitConstDivide} takes at the long lane are Java's {@code v / d}
   * for every dividend under {@code ConstDivide.EXACT_DIVIDEND_BOUND}; the conversion form is
   * exact to one below the first multiple of {@code d} past 2^53 and wrong there.
   *
   * <p>Every rounding is the prelude's {@code jdouble.rne} in a named binade, so each check is
   * linear: one check per binade the quotient can fall in, and a check that those binades cover
   * every quotient the dividend's range gives, without which the checks could pass by leaving a
   * quotient out.
   */
  static String longDivide() {
    if (VarkaVectorIR.ConstDivide.EXACT_DIVIDEND_BOUND != P52
        || VarkaDivisionLowering.MANTISSA_52 != P52 - 1
        || VarkaDivisionLowering.TWO_52_BITS != 0x433L << 52) {
      throw new IllegalStateException("long_divide.smt2 is stated for a dividend bound of 2^52 "
          + "and the magic form's constants 0x4330000000000000 and 2^52 - 1; restate it");
    }
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The long lane's constant division (VarkaVectorIR.ConstDivide) is exact under its
        ; dividend bound, ConstDivide.EXACT_DIVIDEND_BOUND = 2^52, in both of the forms
        ; VarkaDivisionLowering.emitConstDivide emits (VARKA-241). Per lane:
        ;
        ;   the conversion form, emitDoubleDivide:
        ;     double x = (double) v;          L2D
        ;     double q = x / d;               DoubleVector.div
        ;     long r = (long) q;              D2L
        ;
        ;   the magic form, emitMagicDivide, where L2D and D2L do not intrinsify:
        ;     long a = v < 0 ? -v : v;                                masked NEG
        ;     double x = longBitsToDouble(a | 0x4330000000000000L);   or, reinterpretAsDoubles
        ;     double q = (x - 2^52) / |d|;                            sub, div
        ;     double r1 = (q + 2^52) - 2^52;                          add, sub: q to nearest
        ;     double fl = r1 > q ? r1 - 1 : r1;                       compare, masked SUB
        ;     long y = doubleToRawLongBits(fl + 2^52) & (2^52 - 1);   add, reinterpretAsLongs, and
        ;     long r = (v < 0) != (d < 0) ? -y : y;                   masked NEG
        ;
        ; Every rounding is jdouble.rne in a named binade, so each check is linear: one check per
        ; binade a quotient can fall in, and one that the binades listed cover every quotient the
        ; dividend's range gives. The conversion form is also exact past the bound, to one below
        ; the first multiple of d above 2^53, and wrong there: the solver confirms the bound both
        ; ways, so the rule that renders it is checked too.
        """);
    out.append(RENDERED);
    out.append("""

        ; Lemma: an integer n with 0 < n <= 2^53 rounds to itself - L2D of it, and any sum or
        ; difference whose exact value it is. The checks below write such a value without its
        ; rounding.
        """);
    for (int j = 0; j <= 53; j++) {
      long[] s = {j >= 52 ? 1L << (j - 52) : 1, j >= 52 ? 1 : 1L << (52 - j)};
      check(out, "an integer in [2^" + j + ", 2^" + (j + 1) + ") rounds to itself", "unsat",
          List.of("(declare-const n Int)", "(declare-const M Int)",
              "(assert (and (<= " + (1L << j) + " n) (<= n " + Math.min((1L << (j + 1)) - 1, P53)
                  + ")))",
              "(assert (jdouble.rne n 1 M " + s[0] + " " + s[1] + "))",
              "(assert (not (= (* M " + s[0] + ") (* n " + s[1] + "))))"));
    }
    out.append("""

        ; Lemma: the magic form's OR is disjoint below 2^52, so the bits read back are 2^52 + a
        ; in 2^52's binade.
        """);
    check(out, "a | 0x4330000000000000L read as a double is 2^52 + a for 0 <= a < 2^52", "unsat",
        List.of("(declare-const a Int)", "(assert (and (<= 0 a) (< a " + P52 + ")))",
            "(define-fun xb () Int (jlong.or.disjoint a " + VarkaDivisionLowering.TWO_52_BITS
                + "))",
            "(assert (not (and (= (jdouble.fromBits.E xb) 0) (= (jdouble.fromBits.M xb) (+ "
                + P52 + " a)))))"));
    for (long d : LONG_DIVISORS) {
      long m = Math.abs(d);
      out.append("\n; d = ").append(d).append("\n");
      conversionExact(out, d, m);
      // Where the conversion form first goes wrong past 2^53 is a fact about the TIME divisors,
      // whose dividends reach the bound. The day's odd part is still exact at the dividend the
      // rule renders - both solvers say so - and its caller states a bound near 2^50.
      if (d != NTZ_DAY_ODD) {
        conversionBound(out, d, m);
      }
      magicExact(out, d, m);
    }
    floorDivDay(out);
    return out.toString();
  }

  /**
   * {@code CAST(ntz AS DATE)}'s floor division by a day's microseconds, as
   * {@code VarkaNodeCompiler.floorDivLong} builds it (VARKA-338): {@code y = x >> 13}, then
   * {@code y' = y + ((y >> 63) & (1 - m))} with {@code m} the odd part, then the truncating
   * {@code y' / m}. Over every long {@code x} it is {@code Math.floorDiv(x, 86.4e9)}, its dividend
   * {@code y'} is under the bound the compiler states to {@code ConstDivide}, which the checks
   * above prove the division exact under, and its quotient fits an int, so the narrowing to a
   * {@code DATE} is exact. The integer identity only: the shift and the AND are the prelude's
   * Java operators.
   */
  private static void floorDivDay(StringBuilder out) {
    long day = DateTimeConstants.MICROS_PER_DAY;
    int k = Long.numberOfTrailingZeros(day);
    long m = NTZ_DAY_ODD;
    long bound = (1L << (63 - k)) + m;
    out.append("\n; CAST(ntz AS DATE): floorDiv(x, ").append(day).append(") = (y' / ").append(m)
        .append("), y = x >> ").append(k).append(", y' = y + ((y >> 63) & ").append(1 - m)
        .append(")\n");
    check(out, "a long shifted right by 63 is -1 where it is negative and 0 elsewhere", "unsat",
        List.of("(declare-const y Int)", "(assert (jlong.in y))",
            "(assert (not (= (jlong.shr y 63) (ite (< y 0) (- 1) 0))))"));
    // The AND of -1 or 0 with a constant is the constant or 0: the product below.
    var steps = List.of("(declare-const x Int)", "(assert (jlong.in x))",
        "(define-fun y () Int (jlong.shr x " + k + "))",
        "(define-fun y1 () Int (jlong.add y (* (- (jlong.shr y 63)) " + lit(1 - m) + ")))",
        "(define-fun q () Int (jlong.div y1 " + m + "))");
    var identity = new ArrayList<>(steps);
    identity.add("(assert (not (= q (jlong.floorDiv x " + day + "))))");
    check(out, "it is Math.floorDiv(x, " + day + ") for every long x", "unsat", identity);
    var bounded = new ArrayList<>(steps);
    bounded.add("(assert (not (< (abs y1) " + bound + ")))");
    check(out, "its dividend y' is under " + bound + ", the bound the compiler states, which is "
        + "at most 2^52", "unsat", bounded);
    var fits = new ArrayList<>(steps);
    fits.add("(assert (not (and (<= " + lit(Long.MIN_VALUE / day - 1) + " q) (<= q "
        + Long.MAX_VALUE / day + "))))");
    check(out, "its quotient is a day in [" + (Long.MIN_VALUE / day - 1) + ", "
        + Long.MAX_VALUE / day + "], which an int holds", "unsat", fits);
    if (bound > P52) {
      throw new IllegalStateException("the floor division's dividend bound " + bound
          + " is past ConstDivide's 2^52");
    }
  }

  /** The conversion form, for every {@code 0 < |v| < 2^53}: one check per quotient binade. */
  private static void conversionExact(StringBuilder out, long d, long m) {
    int kLo = binade(BigInteger.valueOf(P53 - 1), BigInteger.valueOf(m)) - 1;
    int kHi = binade(BigInteger.ONE, BigInteger.valueOf(m));
    check(out, "d = " + d + ": the conversion form's quotients for 0 < v < 2^53 lie in binades 2^-"
        + (kLo + 1) + " to 2^-" + kHi, "unsat", List.of("(declare-const v Int)",
        "(assert (and (<= 1 v) (< v " + P53 + ")))",
        "(assert (not " + inBinades("v", m, kLo + 1, kHi) + "))"));
    for (int k = kLo; k <= kHi; k++) {
      String[] s = scale(-k);
      check(out, "d = " + d + ": the conversion form is v / d for 0 < |v| < 2^53, quotient in "
          + "binade 2^-" + k, "unsat", List.of(
          "(declare-const v Int)", "(declare-const M Int)", "(declare-const r Int)",
          "(assert (and (< 0 (abs v)) (< (abs v) " + P53 + ")))",
          "; L2D leaves v as it is (the lemma); |v| / |d| rounds to M * 2^-" + k
              + "; D2L truncates it",
          "(assert (jdouble.rne (abs v) " + m + " M " + s[0] + " " + s[1] + "))",
          "(assert (jdouble.d2l.mag r M " + s[0] + " " + s[1] + "))",
          "(assert (not (= " + signed("r", d) + " (jlong.div v " + lit(d) + "))))"));
    }
    check(out, "d = " + d + ": both forms take 0 to 0", "unsat",
        List.of("(assert (not (= 0 (jlong.div 0 " + lit(d) + "))))"));
  }

  /**
   * The conversion form past the bound: exact for {@code 2^53 <= |v| < B} and wrong at
   * {@code |v| = B}, where B is one less than the first multiple of {@code |d|} above 2^53. L2D
   * rounds there, into 2^53's binade or onto 2^54.
   */
  private static void conversionBound(StringBuilder out, long d, long m) {
    long b = (P53 / m + 1) * m - 1;
    int kLo = binade(BigInteger.valueOf(P53).shiftLeft(1), BigInteger.valueOf(m)) - 1;
    int kHi = binade(BigInteger.valueOf(P53), BigInteger.valueOf(m));
    String l2d = "(assert (or (and (jdouble.rne (abs v) 1 Mx 2 1) (= X (* 2 Mx))) "
        + "(and (jdouble.rne (abs v) 1 Mx 4 1) (= X (* 4 Mx)))))";
    var branches = new ArrayList<String>();
    for (int k = kLo; k <= kHi; k++) {
      String[] s = scale(-k);
      branches.add("(and (jdouble.rne X " + m + " M " + s[0] + " " + s[1]
          + ") (jdouble.d2l.mag r M " + s[0] + " " + s[1] + "))");
    }
    String rounds = "(assert (or " + String.join("\n  ", branches) + "))";
    var decl = List.of("(declare-const v Int)", "(declare-const Mx Int)", "(declare-const X Int)",
        "(declare-const M Int)", "(declare-const r Int)");
    check(out, "d = " + d + ": the conversion form's quotients for 2^53 <= |v| <= 2^54 lie in "
        + "binades 2^-" + (kLo + 1) + " to 2^-" + kHi, "unsat", List.of("(declare-const X Int)",
        "(assert (and (<= " + P53 + " X) (<= X " + 2 * P53 + ")))",
        "(assert (not " + inBinades("X", m, kLo + 1, kHi) + "))"));
    var exact = new ArrayList<>(decl);
    exact.add("(assert (and (<= " + P53 + " (abs v)) (< (abs v) " + b + ")))");
    exact.add(l2d);
    exact.add(rounds);
    exact.add("(assert (not (= " + signed("r", d) + " (jlong.div v " + lit(d) + "))))");
    check(out, "d = " + d + ": the conversion form is v / d for 2^53 <= |v| < " + b
        + ", one less than the first multiple of |d| past 2^53", "unsat", exact);
    for (long v : new long[] {b, -b}) {
      var wrong = new ArrayList<>(decl);
      wrong.add("(assert (= v " + lit(v) + "))");
      wrong.add(l2d);
      wrong.add(rounds);
      wrong.add("(assert (not (= " + signed("r", d) + " (jlong.div v " + lit(d) + "))))");
      check(out, "d = " + d + ": and it is not at v = " + v, "sat", wrong);
    }
  }

  /** The magic form, for every {@code 0 < |v| < 2^52}: one check per quotient binade. */
  private static void magicExact(StringBuilder out, long d, long m) {
    int kLo = binade(BigInteger.valueOf(P52 - 1), BigInteger.valueOf(m)) - 1;
    int kHi = binade(BigInteger.ONE, BigInteger.valueOf(m));
    check(out, "d = " + d + ": the magic form's quotients for 0 < a < 2^52 lie in binades 2^-"
        + (kLo + 1) + " to 2^-" + kHi, "unsat", List.of("(declare-const a Int)",
        "(assert (and (<= 1 a) (< a " + P52 + ")))",
        "(assert (not " + inBinades("a", m, kLo + 1, kHi) + "))"));
    String flip = d < 0 ? "(not (< v 0))" : "(< v 0)";
    for (int k = kLo; k <= kHi; k++) {
      String[] s = scale(-k);
      BigInteger p = BigInteger.ONE.shiftLeft(k);
      String sum = "(+ Mq " + BigInteger.valueOf(P52).shiftLeft(k) + ")";
      check(out, "d = " + d + ": the magic form is v / d for 0 < |v| < 2^52, quotient in binade 2^-"
          + k, "unsat", List.of(
          "(declare-const v Int)", "(declare-const Mq Int)", "(declare-const Ms Int)",
          "(declare-const s Int)",
          "(assert (and (< 0 (abs v)) (< (abs v) " + P52 + ")))",
          "; a = |v|; x = 2^52 + a (the second lemma); x - 2^52 = a (the first); a / |d| rounds to "
              + "Mq * 2^-" + k,
          "(define-fun a () Int (ite (< v 0) (jlong.neg v) v))",
          "(assert (jdouble.rne a " + m + " Mq " + s[0] + " " + s[1] + "))",
          "; q + 2^52 rounds into 2^52's binade or onto 2^53",
          "(assert (or (and (jdouble.rne " + sum + " " + p + " Ms 1 1) (= s Ms))",
          "            (and (jdouble.rne " + sum + " " + p + " Ms 2 1) (= s (* 2 Ms)))))",
          "; - 2^52 is exact, as are r1 - 1 and fl + 2^52 (the first lemma); y is fl + 2^52's bits",
          "; masked to the low 52",
          "(define-fun r1 () Int (- s " + P52 + "))",
          "(define-fun fl () Int (ite (> (* r1 " + p + ") Mq) (- r1 1) r1))",
          "(define-fun y () Int (jlong.and.low (jdouble.bits (+ fl " + P52 + ") 0) "
              + (VarkaDivisionLowering.MANTISSA_52 + 1) + "))",
          "(define-fun r () Int (ite " + flip + " (jlong.neg y) y))",
          "(assert (not (and (<= 0 r1) (<= r1 " + P52 + ") (<= 0 fl) (< fl " + P52 + ")",
          "  (= r (jlong.div v " + lit(d) + ")))))"));
    }
  }

  /** {@code |r|} with the quotient's sign: negative where exactly one of v and d is. */
  private static String signed(String r, long d) {
    return "(ite (= (< v 0) " + (d < 0) + ") " + r + " (- " + r + "))";
  }

  /** {@code 2^e} as the prelude's {@code sn} and {@code sd}. */
  private static String[] scale(int e) {
    return e >= 0 ? new String[] {BigInteger.ONE.shiftLeft(e).toString(), "1"}
        : new String[] {"1", BigInteger.ONE.shiftLeft(-e).toString()};
  }

  /** The binade k of {@code a / b > 0}: {@code 2^52 <= (a / b) * 2^k < 2^53}. */
  static int binade(BigInteger a, BigInteger b) {
    int k = 52 - (a.bitLength() - b.bitLength());
    // Now 2^51 < (a / b) * 2^k < 2^54; step k until the scaled quotient is in [2^52, 2^53).
    while (scaledBelow(a, b, k, 52)) {
      k++;
    }
    while (!scaledBelow(a, b, k, 53)) {
      k--;
    }
    return k;
  }

  /** Whether {@code (a / b) * 2^k < 2^e}. */
  private static boolean scaledBelow(BigInteger a, BigInteger b, int k, int e) {
    BigInteger left = k >= 0 ? a.shiftLeft(k) : a;
    BigInteger right = k >= 0 ? b.shiftLeft(e) : b.shiftLeft(e - k);
    return left.compareTo(right) < 0;
  }

  /** That {@code x / m} lies in binades {@code kLo..kHi}: {@code [2^(52-kHi), 2^(53-kLo))}. */
  private static String inBinades(String x, long m, int kLo, int kHi) {
    return "(and " + scaledAtLeast(x, m, kHi, 52) + " (not " + scaledAtLeast(x, m, kLo, 53) + "))";
  }

  /** {@code (x / m) * 2^k >= 2^e}, as a linear inequality. */
  private static String scaledAtLeast(String x, long m, int k, int e) {
    BigInteger left = BigInteger.ONE.shiftLeft(Math.max(k, 0));
    BigInteger right = BigInteger.valueOf(m).shiftLeft(k >= 0 ? e : e - k);
    return "(>= (* " + x + " " + left + ") " + right + ")";
  }

  // -----------------------------------------------------------------------------------------
  // The calendar's constant divisions (VARKA-242), proven per site.
  // -----------------------------------------------------------------------------------------

  /**
   * {@code chrono_divide.smt2}: for every {@link VarkaChronoLowering.ChronoDivide} site, the
   * magic form with its carry where the table says so is {@code v / d} over the site's dividends
   * up to one below the first that fails, and fails there; without its carry it fails inside the
   * site's range; and the two double forms, {@code DOUBLE_DIV} and {@code DOUBLE_RECIP}, are exact
   * over the range, or for a reciprocal the table marks inexact, wrong inside it.
   */
  static String chronoDivide() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The calendar's constant divisions (VarkaChronoLowering.ChronoDivide) are exact over the
        ; dividends each site sees (VARKA-242). Per lane, at the int lane, with (m, k) the site's
        ; magic pair and d its divisor:
        ;
        ;   the magic form, emitDivide and emitCarry:
        ;     int q = (v * m) >>> k;           IntVector.mul, LSHR
        ;     if (v - q * d >= d) q = q + 1;   the carry, at the sites the table marks carried
        ;
        ;   the double forms, emitDoubleDivide under DOUBLE_DIV and DOUBLE_RECIP:
        ;     int q = (int) ((double) v / d);           I2D, DoubleVector.div, D2I
        ;     int q = (int) ((double) v * (1.0 / d));   I2D, DoubleVector.mul, D2I
        ;
        ; A site's dividends are stride * x + residue, non-negative, up to its maxDividend: the
        ; Julian sites divide 4 * dayOfEra + 3 and what the map adds to it in fours, every other
        ; site an interval from 0. For each site the magic form is shown exact to one below the
        ; first dividend of that shape where it fails, which VarkaProofFiles found by scanning
        ; while rendering, and wrong there, so the solver confirms the scan; the rendering refuses
        ; a maxDividend at or past it. A carried site is shown wrong without its carry inside its
        ; range, so no site is marked carried for nothing. The double forms are stated per
        ; quotient binade, as in long_divide.smt2, with a check that the binades cover the range.
        """);
    out.append(RENDERED);
    out.append("""

        ; Lemma: an integer n with 0 < n < 2^31 rounds to itself, so I2D of a dividend is exact and
        ; the checks below write it without its rounding.
        """);
    for (int j = 0; j <= 30; j++) {
      long[] s = {1, 1L << (52 - j)};
      check(out, "an integer in [2^" + j + ", 2^" + (j + 1) + ") rounds to itself", "unsat",
          List.of("(declare-const n Int)", "(declare-const M Int)",
              "(assert (and (<= " + (1L << j) + " n) (< n " + (1L << (j + 1)) + ")))",
              "(assert (jdouble.rne n 1 M " + s[0] + " " + s[1] + "))",
              "(assert (not (= (* M " + s[0] + ") (* n " + s[1] + "))))"));
    }
    out.append("""

        (define-fun chrono.magic ((v Int) (m Int) (k Int) (d Int) (carry Bool)) Int
          (let ((q (jint.ushr (jint.mul v m) k)))
            (ite (and carry (>= (jint.sub v (jint.mul q d)) d)) (jint.add q 1) q)))
        """);
    for (VarkaChronoLowering.ChronoDivide site : VarkaChronoLowering.ChronoDivide.values()) {
      chronoMagic(out, site);
      chronoDouble(out, site, false);
      chronoDouble(out, site, true);
    }
    return out.toString();
  }

  /**
   * The first dividend of the site's shape where the scalar magic form, with its carry where the
   * table says so, is not {@code v / d}: {@code (v * m) >>> k} in Java's own int arithmetic.
   */
  static int chronoFirstWrong(VarkaChronoLowering.ChronoDivide site) {
    for (int v = site.residue; v >= 0; v += site.stride) {
      if (chronoMagicScalar(site, v, site.carried) != v / site.divisor) {
        return v;
      }
    }
    throw new IllegalStateException(site + "'s magic form is exact over every int of its shape");
  }

  private static int chronoMagicScalar(VarkaChronoLowering.ChronoDivide site, int v,
      boolean carry) {
    int q = (v * site.m) >>> site.k;
    if (carry && v - q * site.divisor >= site.divisor) {
      q++;
    }
    return q;
  }

  /** The first dividend of the site's shape where the reciprocal form is wrong, or -1. */
  private static int chronoRecipFirstWrong(VarkaChronoLowering.ChronoDivide site) {
    double recip = 1.0 / site.divisor;
    for (int v = site.residue; v >= 0 && v <= site.maxDividend; v += site.stride) {
      if ((int) ((double) v * recip) != v / site.divisor) {
        return v;
      }
    }
    return -1;
  }

  /** That {@code v} is of the site's shape: {@code stride * x + residue}, from 0 to {@code max}. */
  private static String chronoShape(VarkaChronoLowering.ChronoDivide site, long lo, long max) {
    String range = "(<= " + lo + " v) (<= v " + max + ")";
    return site.stride == 1 ? "(assert (and " + range + "))"
        : "(assert (and " + range + " (= (mod v " + site.stride + ") " + site.residue + ")))";
  }

  private static void chronoMagic(StringBuilder out, VarkaChronoLowering.ChronoDivide site) {
    int d = site.divisor;
    int first = chronoFirstWrong(site);
    if (site.maxDividend > first - site.stride) {
      throw new IllegalStateException(site + "'s dividends reach " + site.maxDividend
          + ", and its magic form first fails at " + first);
    }
    out.append("\n; ").append(site).append(": d = ").append(d).append(", (m, k) = (")
        .append(site.m).append(", ").append(site.k).append(")")
        .append(site.carried ? " and a carry" : "").append(", dividends ")
        .append(site.stride == 1 ? "0" : site.stride + "x + " + site.residue).append(" to ")
        .append(site.maxDividend).append(", first wrong ").append(first).append("\n");
    String form = "(chrono.magic v " + site.m + " " + site.k + " " + d + " " + site.carried + ")";
    check(out, site + ": the magic form is v / " + d + " for every dividend of its shape to "
        + (first - site.stride) + ", past its " + site.maxDividend, "unsat", List.of(
        "(declare-const v Int)", chronoShape(site, 0, first - site.stride),
        "(assert (not (= " + form + " (div v " + d + "))))"));
    check(out, site + ": and it is not at " + first, "sat", List.of("(declare-const v Int)",
        "(assert (= v " + first + "))", "(assert (not (= " + form + " (div v " + d + "))))"));
    if (site.carried) {
      check(out, site + ": without the carry it is not, inside the range", "sat", List.of(
          "(declare-const v Int)", chronoShape(site, 0, site.maxDividend),
          "(assert (not (= (chrono.magic v " + site.m + " " + site.k + " " + d + " false) (div v "
              + d + "))))"));
    }
  }

  /**
   * {@code DOUBLE_DIV} or {@code DOUBLE_RECIP} over the site's range: the exact quotient is
   * {@code (v * num) / den}, with {@code num / den} either {@code 1 / d} or the double nearest
   * {@code 1 / d}, and it rounds in a named binade.
   */
  private static void chronoDouble(StringBuilder out, VarkaChronoLowering.ChronoDivide site,
      boolean recip) {
    int d = site.divisor;
    String name = recip ? "DOUBLE_RECIP" : "DOUBLE_DIV";
    BigInteger num = BigInteger.ONE;
    BigInteger den = BigInteger.valueOf(d);
    String a = "v";
    if (recip) {
      double r = 1.0 / d;
      long raw = Double.doubleToRawLongBits(r);
      num = BigInteger.valueOf((raw & VarkaDivisionLowering.MANTISSA_52) | P52);
      den = BigInteger.ONE.shiftLeft(52 - Math.getExponent(r));
      a = "(* v " + num + ")";
      out.append("; ").append(site).append(": 1.0 / ").append(d).append(" is ").append(num)
          .append(" * 2^-").append(52 - Math.getExponent(r)).append("\n");
    }
    BigInteger lowest = BigInteger.valueOf(site.residue == 0 ? site.stride : site.residue);
    BigInteger max = BigInteger.valueOf(site.maxDividend);
    // The exact quotients lie in binades kLo to kHi; a rounding at the top of one lands on the
    // bottom of the next one up, so the rounded quotient can also be in kLo - 1.
    int kLo = binade(max.multiply(num), den);
    int kHi = binade(lowest.multiply(num), den);
    if (!recip || site.recipExact) {
      check(out, site + ": " + name + "'s quotients over its range lie in binades 2^-" + kLo
          + " to 2^-" + kHi, "unsat", List.of("(declare-const v Int)",
          chronoShape(site, lowest.longValue(), site.maxDividend),
          "(assert (not (and " + scaledAtLeast(a, den, kHi, 52) + " (not "
              + scaledAtLeast(a, den, kLo, 53) + "))))"));
      for (int k = kLo - 1; k <= kHi; k++) {
        String[] s = scale(-k);
        check(out, site + ": " + name + " is v / " + d + " over its range, quotient in binade 2^-"
            + k, "unsat", List.of("(declare-const v Int)", "(declare-const M Int)",
            "(declare-const r Int)", chronoShape(site, lowest.longValue(), site.maxDividend),
            "(assert (jdouble.rne " + a + " " + den + " M " + s[0] + " " + s[1] + "))",
            "(assert (jdouble.d2l.mag r M " + s[0] + " " + s[1] + "))",
            "(assert (not (= r (div v " + d + "))))"));
      }
      if (site.residue == 0) {
        check(out, site + ": " + name + " takes 0 to 0", "unsat",
            List.of("(assert (not (= 0 (div 0 " + d + "))))"));
      }
      return;
    }
    int wrong = chronoRecipFirstWrong(site);
    if (wrong < 0) {
      throw new IllegalStateException(site + " is marked recipExact = false, and the reciprocal "
          + "is exact over its range");
    }
    // The product rounds in the exact quotient's binade or, at its top, onto the next one up.
    int k = binade(BigInteger.valueOf(wrong).multiply(num), den);
    String[] s = scale(-k);
    String[] up = scale(-(k - 1));
    check(out, site + ": recipExact is false: " + name + " is not v / " + d + " at " + wrong
        + ", inside the range", "sat", List.of("(declare-const v Int)", "(declare-const M Int)",
        "(declare-const r Int)", "(assert (= v " + wrong + "))",
        "(assert (or (and (jdouble.rne " + a + " " + den + " M " + s[0] + " " + s[1]
            + ") (jdouble.d2l.mag r M " + s[0] + " " + s[1] + "))",
        "            (and (jdouble.rne " + a + " " + den + " M " + up[0] + " " + up[1]
            + ") (jdouble.d2l.mag r M " + up[0] + " " + up[1] + "))))",
        "(assert (not (= r (div v " + d + "))))"));
  }

  /** {@code (a / b) * 2^k >= 2^e} for an expression {@code a}, as a linear inequality. */
  private static String scaledAtLeast(String a, BigInteger b, int k, int e) {
    BigInteger left = BigInteger.ONE.shiftLeft(Math.max(k, 0));
    BigInteger right = b.shiftLeft(k >= 0 ? e : e - k);
    return "(>= (* " + a + " " + left + ") " + right + ")";
  }

  // -----------------------------------------------------------------------------------------
  // floorMod(v, 7) (VARKA-242), its three forms.
  // -----------------------------------------------------------------------------------------

  private static final long P32 = 1L << 32;

  /**
   * {@code floor_mod7.smt2}: each of {@code emitFloorMod7}'s three forms is
   * {@code Math.floorMod(v, 7)} for every int {@code v}, stated as lemmas over the digits each
   * fold splits its input into (VARKA-242.md 2.2), and a check per form that the lemmas compose.
   */
  static String floorMod7() {
    var folds = new ArrayList<VarkaChronoLowering.Fold>(VarkaChronoLowering.FLOOR_MOD7_FOLDS);
    folds.addAll(VarkaChronoLowering.DIGIT_SUM_FOLDS);
    for (VarkaChronoLowering.Fold f : folds) {
      if (f.mask() != (1 << f.shift()) - 1) {
        throw new IllegalStateException("floor_mod7.smt2 states a fold whose mask is its shift's "
            + "low bits, and " + f + " is not one");
      }
    }
    int fix = VarkaChronoLowering.FLOOR_MOD7_SIGN_FIX;
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; floorMod(v, 7) is what VarkaChronoLowering.emitFloorMod7 computes, in each of its three
        ; forms, for every int v (VARKA-242). Per lane:
        ;
        ;   a fold, VarkaChronoLowering.Fold:     y = (x & (2^k - 1)) + (x >>> k)
        ;   the shipped form (FloorMod7.MAGIC):   two folds of 15 bits; + 3 where v < 0;
        ;                                         r = g - ((g * 37450) >>> 18) * 7
        ;   FloorMod7.DIGIT_SUM:                  folds of 15, 15, 6, 3, 3 and 3 bits; + 3 where
        ;                                         v < 0; - 7 where that is at least 7
        ;   FloorMod7.DIV:                        r = v - (v / 7) * 7; + 7 where r < 0
        ;
        ; The folds read v unsigned, as u = v or v + 2^32. Each is stated by the digits of its
        ; input, u = 2^k * a + b with 0 <= b < 2^k, so every check is linear arithmetic over a few
        ; small variables: a lemma that the prelude's & and >>> give b and a, at both signs; a
        ; lemma per fold that a + b keeps the residue mod 7, since 2^k is 1 mod 7 for k = 15, 6
        ; and 3, and is at most the bound written; that + 3 restores a negative v's residue, since
        ; 2^32 is 4 mod 7; and the last step over the small value the folds leave. A check per
        ; form then takes the lemmas' conclusions as its premises and shows the result is v's
        ; residue. A residue kept is written as a difference that is a multiple of 7, and in the
        ; composition as 7 times a declared integer, with no mod at all: cvc5 decides each of
        ; these checks alone in milliseconds, but within the file its earlier checks left the mod
        ; forms of two of them unknown. The file declares ALL: under QF_NIA cvc5 leaves six of
        ; these checks unknown, under ALL it decides every one (VARKA-242.md 2.2).
        """);
    out.append(rendered("ALL"));
    out.append("""

        (define-fun fm7.unsigned ((x Int)) Int (ite (< x 0) (+ x 4294967296) x))
        """);
    out.append("\n; The prelude's & and >>> give a fold's digits.\n");
    var seen = new java.util.LinkedHashSet<VarkaChronoLowering.Fold>(folds);
    for (VarkaChronoLowering.Fold f : seen) {
      long base = 1L << f.shift();
      for (boolean negative : new boolean[] {false, true}) {
        String sign = negative ? "(< x 0)" : "(>= x 0)";
        String digits = "(assert (and (= (fm7.unsigned x) (+ (* " + base + " a) b)) (<= 0 b) (< b "
            + base + ")))";
        var decl = List.of("(declare-const x Int)", "(declare-const a Int)",
            "(declare-const b Int)", "(assert (and (jint.in x) " + sign + "))", digits);
        String which = negative ? "a negative" : "a non-negative";
        var mask = new ArrayList<>(decl);
        mask.add("(assert (not (= (jint.and.low x " + base + ") b)))");
        check(out, "x & " + f.mask() + " is the low digit b of " + which + " int's unsigned "
            + "reading", "unsat", mask);
        var shift = new ArrayList<>(decl);
        shift.add("(assert (not (= (jint.ushr x " + f.shift() + ") a)))");
        check(out, "x >>> " + f.shift() + " is the high digit a of " + which + " int's unsigned "
            + "reading", "unsat", shift);
      }
    }
    long shippedMax = foldResidues(out, "the shipped form", VarkaChronoLowering.FLOOR_MOD7_FOLDS);
    long digitMax = foldResidues(out, "DIGIT_SUM", VarkaChronoLowering.DIGIT_SUM_FOLDS);
    out.append("\n; The sign fixup, and the last steps.\n");
    check(out, "+ " + fix + " restores a negative int's residue from its unsigned reading", "unsat",
        List.of("(declare-const x Int)", "(assert (and (jint.in x) (< x 0)))",
            "(assert (not (= (mod (- (+ (fm7.unsigned x) " + fix + ") x) 7) 0)))"));
    for (long max : new long[] {shippedMax, digitMax}) {
      check(out, "+ " + fix + " does not wrap over [0, " + max + "]", "unsat",
          List.of("(declare-const y Int)", "(assert (and (<= 0 y) (<= y " + max + ")))",
              "(assert (not (= (jint.add y " + fix + ") (+ y " + fix + "))))"));
    }
    long g = shippedMax + fix;
    int m = VarkaChronoLowering.FLOOR_MOD7_M;
    int k = VarkaChronoLowering.FLOOR_MOD7_K;
    check(out, "the shipped form: (g * " + m + ") >>> " + k + " is g / 7 over [0, " + g + "]",
        "unsat", List.of("(declare-const g Int)", "(assert (and (<= 0 g) (<= g " + g + ")))",
            "(assert (not (= (jint.ushr (jint.mul g " + m + ") " + k + ") (div g 7))))"));
    check(out, "the shipped form: g - (g / 7) * 7 is g's residue over [0, " + g + "]", "unsat",
        List.of("(declare-const g Int)", "(assert (and (<= 0 g) (<= g " + g + ")))",
            "(assert (not (= (jint.sub g (jint.mul (div g 7) 7)) (mod g 7))))"));
    long h = digitMax + fix;
    check(out, "DIGIT_SUM: one subtract of 7 where at least 7 is the residue over [0, " + h + "]",
        "unsat", List.of("(declare-const g Int)", "(assert (and (<= 0 g) (<= g " + h + ")))",
            "(assert (not (= (ite (>= g 7) (jint.sub g 7) g) (mod g 7))))"));
    out.append("\n; Each form composed: the lemmas' conclusions as premises.\n");
    foldComposition(out, "the shipped form", VarkaChronoLowering.FLOOR_MOD7_FOLDS, fix);
    foldComposition(out, "DIGIT_SUM", VarkaChronoLowering.DIGIT_SUM_FOLDS, fix);
    // DIV through the truncated quotient q, named: stated in one check, cvc5 cannot decide it.
    for (boolean negative : new boolean[] {false, true}) {
      String which = negative ? "a negative" : "a non-negative";
      var decl = List.of("(declare-const x Int) (declare-const q Int) (declare-const t Int)",
          "(assert (and (jint.in x) " + (negative ? "(< x 0)" : "(>= x 0)") + "))",
          "(assert (and (= x (+ (* 7 q) t)) "
              + (negative ? "(< (- 7) t) (<= t 0)" : "(<= 0 t) (< t 7)") + "))");
      var quotient = new ArrayList<>(decl);
      quotient.add("(assert (not (= (jint.div x 7) q)))");
      check(out, "DIV: v / 7 is the quotient truncated toward zero, for " + which + " int",
          "unsat", quotient);
      var rem = new ArrayList<>(decl);
      rem.add("(assert (not (= (jint.sub x (jint.mul q 7)) t)))");
      check(out, "DIV: v - (v / 7) * 7 is the remainder t, without wrapping, for " + which
          + " int", "unsat", rem);
      var rest = new ArrayList<>(decl);
      rest.add(RESIDUE_OF_X);
      rest.add("(assert (not (= (ite (< t 0) (jint.add t 7) t) sx)))");
      check(out, "DIV: t, + 7 where negative, is the residue, for " + which + " int", "unsat",
          rest);
    }
    check(out, "Math.floorMod(v, 7) is v's non-negative residue for every int", "unsat",
        List.of("(declare-const x Int)", "(assert (jint.in x))", RESIDUE_OF_X,
            "(assert (not (= (jint.floorMod x 7) sx)))"));
    return out.toString();
  }

  /** {@code sx}, the residue of {@code x} mod 7, by its own quotient rather than by {@code mod}. */
  private static final String RESIDUE_OF_X = "(declare-const qx Int) (declare-const sx Int)\n"
      + "(assert (and (= x (+ (* 7 qx) sx)) (<= 0 sx) (< sx 7)))";

  /** The largest {@code (y >>> shift) + (y & (2^shift - 1))} over {@code 0 <= y <= max}. */
  private static long foldMax(long max, int shift) {
    long mask = (1L << shift) - 1;
    long q = max >>> shift;
    return Math.max(q + (max & mask), q >= 1 ? q - 1 + mask : 0);
  }

  /** A lemma per fold of the chain: the residue kept, the sum bounded. Returns the last bound. */
  private static long foldResidues(StringBuilder out, String form,
      List<VarkaChronoLowering.Fold> chain) {
    out.append("\n; ").append(form).append("'s folds keep the residue mod 7.\n");
    long max = P32 - 1;
    for (VarkaChronoLowering.Fold f : chain) {
      long next = foldMax(max, f.shift());
      long base = 1L << f.shift();
      check(out, form + ": a fold of " + f.shift() + " bits over [0, " + max + "] is at most "
          + next + " and keeps the residue", "unsat", List.of("(declare-const y Int)",
          "(declare-const a Int)", "(declare-const b Int)",
          "(assert (and (<= 0 y) (<= y " + max + ") (= y (+ (* " + base + " a) b)) (<= 0 b) (< b "
              + base + ")))",
          "(assert (not (and (<= (+ a b) " + next + ") (= (mod (- y (+ a b)) 7) 0))))"));
      max = next;
    }
    return max;
  }

  /**
   * That the lemmas compose: from each fold's residue and bound, the sign fixup and the last
   * step's equation, the form's result is {@code mod(v, 7)}.
   */
  private static void foldComposition(StringBuilder out, String form,
      List<VarkaChronoLowering.Fold> chain, int fix) {
    var body = new ArrayList<String>();
    body.add("(declare-const x Int)");
    body.add("(assert (jint.in x))");
    String prev = "(fm7.unsigned x)";
    long max = P32 - 1;
    for (int i = 0; i < chain.size(); i++) {
      max = foldMax(max, chain.get(i).shift());
      body.add("(declare-const y" + i + " Int) (declare-const c" + i + " Int)");
      body.add("(assert (and (<= 0 y" + i + ") (<= y" + i + " " + max + ") (= (- " + prev + " y"
          + i + ") (* 7 c" + i + "))))");
      prev = "y" + i;
    }
    body.add("(define-fun g () Int (ite (< x 0) (+ " + prev + " " + fix + ") " + prev + "))");
    body.add("(declare-const cf Int)");
    body.add("(assert (=> (< x 0) (= (- (+ (fm7.unsigned x) " + fix + ") x) (* 7 cf))))");
    body.add("; mod g 7 and mod x 7, as the residues sg and sx of their own quotients");
    body.add("(declare-const qg Int) (declare-const sg Int)");
    body.add("(assert (and (= g (+ (* 7 qg) sg)) (<= 0 sg) (< sg 7)))");
    body.add(RESIDUE_OF_X);
    body.add("(assert (not (= sg sx)))");
    check(out, form + ": the folds, the fixup and the last step give v's residue", "unsat", body);
  }

  // -----------------------------------------------------------------------------------------
  // The leap hash's unsigned compare (VARKA-242).
  // -----------------------------------------------------------------------------------------

  /**
   * {@code leap_hash.smt2}: {@link VarkaChrono#isLeapYear}'s hash, which
   * {@code VarkaChronoLowering}'s leap flag emits, is the Gregorian rule over every biased year to
   * {@link VarkaChrono#LEAP_HASH_MAX_BIASED_YEAR}, is not one year past it, and would not be with a
   * signed compare.
   */
  static String leapHash() {
    int max = VarkaChrono.LEAP_HASH_MAX_BIASED_YEAR;
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The leap flag's perfect hash (VarkaChrono.isLeapYear, VarkaChronoLowering's leap flag) is
        ; the Gregorian rule over its whole domain (VARKA-242). Per lane, y the year biased by
        ; YEAR_BIAS, a multiple of 400, so leapness is unchanged and y is non-negative:
        ;
        ;   leap = Integer.compareUnsigned((y * LEAP_HASH_M) & LEAP_HASH_MASK, LEAP_HASH_MAX) <= 0
        ;                                   IntVector.mul, and, compare ULE
        ;
        ; Over 32-bit vectors, whose bvmul, bvand and bvule are Java's int *, & and the unsigned
        ; compare exactly: the hash is the rule (y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)) for
        ; every biased year up to LEAP_HASH_MAX_BIASED_YEAR, is not one year past it, so the bound
        ; is exact, and a signed compare would be wrong inside the range. Over integers this ran
        ; past ten minutes under Z3 (VARKA-240); over bit-vectors it takes under a second. The file
        ; declares ALL so that the prelude, which it does not use, can be inserted.
        """);
    out.append(rendered("ALL"));
    out.append("\n(define-fun leap.hash ((y (_ BitVec 32))) (_ BitVec 32)\n  (bvand (bvmul y ")
        .append(bv(VarkaChrono.LEAP_HASH_M)).append(") ").append(bv(VarkaChrono.LEAP_HASH_MASK))
        .append("))\n");
    out.append("""
        (define-fun leap.gregorian ((y (_ BitVec 32))) Bool
          (and (= (bvurem y #x00000004) #x00000000)
               (or (not (= (bvurem y #x00000064) #x00000000))
                   (= (bvurem y #x00000190) #x00000000))))
        """);
    String unsigned = "(bvule (leap.hash y) " + bv(VarkaChrono.LEAP_HASH_MAX) + ")";
    String signed = "(bvsle (leap.hash y) " + bv(VarkaChrono.LEAP_HASH_MAX) + ")";
    check(out, "the hash, compared unsigned, is the Gregorian rule for every biased year to " + max,
        "unsat", List.of("(declare-const y (_ BitVec 32))", "(assert (bvule y " + bv(max) + "))",
            "(assert (not (= " + unsigned + " (leap.gregorian y))))"));
    check(out, "and it is not at " + (max + 1), "sat", List.of("(declare-const y (_ BitVec 32))",
        "(assert (= y " + bv(max + 1) + "))",
        "(assert (not (= " + unsigned + " (leap.gregorian y))))"));
    check(out, "compared signed, it is not the rule inside the range", "sat", List.of(
        "(declare-const y (_ BitVec 32))", "(assert (bvule y " + bv(max) + "))",
        "(assert (not (= " + signed + " (leap.gregorian y))))"));
    return out.toString();
  }

  /** An int as a 32-bit SMT-LIB bit-vector literal. */
  private static String bv(int v) {
    return String.format("#x%08x", v);
  }

  // -----------------------------------------------------------------------------------------
  // The range analyses (VARKA-281): the lattice, the analysis's arms, the calendar rules and
  // the TIME compiler's bounds.
  // -----------------------------------------------------------------------------------------

  /**
   * The intervals each stated lattice rule is pinned to the code at: both signs, a point, the
   * int32 extremes and the fuzz grammar's two bounds.
   */
  private static final long[][] SAMPLE_INTERVALS = {{-3, 5}, {2, 3}, {-7, -2}, {0, 0}, {-1, 1},
      {5, 9}, {-4000, 4000}, {-2500000, 2500000}, {Integer.MIN_VALUE, Integer.MAX_VALUE}};

  /** A member {@code x} of {@code [a, b]}, declared. */
  private static final String MEMBER =
      "(declare-const a Int) (declare-const b Int) (declare-const x Int)\n"
          + "(assert (and (<= a x) (<= x b)))";

  /** Members {@code x} of {@code [a, b]} and {@code y} of {@code [c, d]}, declared. */
  private static final String MEMBERS = MEMBER
      + "\n(declare-const c Int) (declare-const d Int) (declare-const y Int)\n"
      + "(assert (and (<= c y) (<= y d)))";

  /** A child's witness {@code m} in {@code [lo, hi]}, and its lane value {@code jc}. */
  private static final String DAY_CHILD =
      "(declare-const lo Int) (declare-const hi Int) (declare-const m Int)\n"
          + "(assert (and (<= lo m) (<= m hi)))\n"
          + "(define-fun jc () Int (jint.wrap m))";

  /** An {@code INT} child's witness {@code mx} in {@code [-m1, m1]}, and its lane value. */
  private static final String INT_CHILD =
      "(declare-const m1 Int) (declare-const mx Int)\n"
          + "(assert (and (<= (- m1) mx) (<= mx m1)))\n"
          + "(define-fun jx () Int (jint.wrap mx))";

  /** An {@code INT} child's witness {@code mx} in a general {@code [a, b]}, and its lane value. */
  private static final String INT_ONE =
      "(declare-const a Int) (declare-const b Int) (declare-const mx Int)\n"
          + "(assert (and (<= a mx) (<= mx b)))\n"
          + "(define-fun jx () Int (jint.wrap mx))";

  /** Two {@code INT} children's witnesses, {@code mx} in {@code [a, b]} and {@code my} in
   * {@code [c, d]}, and their lane values. */
  private static final String INT_GENERAL = INT_ONE
      + "\n(declare-const c Int) (declare-const d Int) (declare-const my Int)\n"
      + "(assert (and (<= c my) (<= my d)))\n"
      + "(define-fun jy () Int (jint.wrap my))";

  /**
   * A rule's three checks: {@code value} lies in {@code [lo, hi]} under the premises (unsat: no
   * member escapes), and does not with the low end raised by one, or the high end lowered by one
   * (sat), so the statement tells the rule from its neighbours. A rule in the witness form passes
   * the congruence of its witness with the lane value as {@code also}, which the neighbours keep.
   */
  private static void rule(StringBuilder out, String what, List<String> premises, String value,
      String lo, String hi, String also) {
    var body = new ArrayList<>(premises);
    body.add("(assert (not (and (<= " + lo + " " + value + ") (<= " + value + " " + hi + ")"
        + also + ")))");
    check(out, what, "unsat", body);
    var low = new ArrayList<>(premises);
    low.add("(assert (not (and (<= (+ " + lo + " 1) " + value + ") (<= " + value + " " + hi + ")"
        + also + ")))");
    check(out, what + ": with the low end raised by one it is not", "sat", low);
    var high = new ArrayList<>(premises);
    high.add("(assert (not (and (<= " + lo + " " + value + ") (<= " + value + " (- " + hi
        + " 1))" + also + ")))");
    check(out, what + ": with the high end lowered by one it is not", "sat", high);
  }

  private static void rule(StringBuilder out, String what, List<String> premises, String value,
      String lo, String hi) {
    rule(out, what, premises, value, lo, hi, "");
  }

  /** The witness form: {@code witness} in {@code [lo, hi]} and congruent to {@code lane}. */
  private static void witnessRule(StringBuilder out, String what, List<String> premises,
      String witness, String lane, String lo, String hi) {
    rule(out, what, premises, witness, lo, hi, " (= (jint.wrap " + witness + ") " + lane + ")");
  }

  /**
   * {@code (= (fn args) expected)} for both ends of {@code result}, which the samples are chosen
   * to keep bounded: a sample the code saturates at would lose its pin without a trace, so it is
   * refused instead.
   */
  private static void pinEnds(List<String> eqs, String fn, String args, Range result) {
    if (!(result instanceof VarkaValueRange.Bounded b)) {
      throw new IllegalStateException(fn + " answers UNKNOWN at the sample " + args
          + "; the pins' samples are chosen bounded, so choose another");
    }
    eqs.add("(= (" + fn + ".lo " + args + ") " + lit(b.lo()) + ")");
    eqs.add("(= (" + fn + ".hi " + args + ") " + lit(b.hi()) + ")");
  }

  /** An SMT-LIB real literal. */
  private static String rlit(long v) {
    return v >= 0 ? v + ".0" : "(- " + (-v) + ".0)";
  }

  private static String ends(long[] p) {
    return lit(p[0]) + " " + lit(p[1]);
  }

  private static String rends(long[] p) {
    return rlit(p[0]) + " " + rlit(p[1]);
  }

  /**
   * {@code value_range.smt2}: {@code VarkaValueRange.Bounded}'s eleven operations sound over
   * symbolic ends, each pinned to the code at the sample intervals; {@code mul} over the reals.
   */
  static String valueRange() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The value-range lattice (VarkaValueRange.Bounded) is sound (VARKA-281): for every member
        ; x of an interval [a, b], and y of [c, d], each operation's result lies in the interval
        ; the operation returns, stated over symbolic ends. The lattice is long arithmetic that
        ; saturates to UNKNOWN through Math.*Exact, so an end it returns is exact: the claims here
        ; are over mathematical integers, and Java's wrapping enters in range_analysis.smt2, where
        ; the lanes do.
        ;
        ; Each rule is a pair of functions of the ends, vr.<op>.lo and vr.<op>.hi, shared by its
        ; claim (unsat: no member escapes), by two neighbours (sat: the rule narrowed by one at
        ; either end admits a counterexample, so the statement can tell the rule from its
        ; neighbours), and by a pin: the ends Bounded computes for sample intervals, rendered from
        ; the code, equal the stated rule's, so a rule restated differently from the code fails
        ; before a proof rests on it. mul's two claims - a product lies within the four corner
        ; products, and |x| <= m1, |y| <= m2 give |x * y| <= m1 * m2 - are stated over the reals,
        ; which both solvers decide in a fraction of a second, where over the integers each
        ; decides one and leaves the other unknown (VARKA-281.md 2.3); every integer instance is
        ; a real one, so the integer claims follow. divideBy is Java's truncating /, stated per
        ; divisor the int lane divides by (VarkaProofFiles.INT_DIVISORS): with the divisor
        ; symbolic Z3 proves it and cvc5 does not. The file declares ALL for the reals.
        """);
    out.append(rendered("ALL"));
    out.append("""

        (define-fun vr.min ((p Int) (q Int)) Int (ite (<= p q) p q))
        (define-fun vr.max ((p Int) (q Int)) Int (ite (>= p q) p q))
        (define-fun vr.shift.lo ((a Int) (b Int) (s1 Int) (s2 Int)) Int (+ a s1))
        (define-fun vr.shift.hi ((a Int) (b Int) (s1 Int) (s2 Int)) Int (+ b s2))
        (define-fun vr.hull.lo ((a Int) (b Int) (c Int) (d Int)) Int (vr.min a c))
        (define-fun vr.hull.hi ((a Int) (b Int) (c Int) (d Int)) Int (vr.max b d))
        (define-fun vr.add.lo ((a Int) (b Int) (c Int) (d Int)) Int (+ a c))
        (define-fun vr.add.hi ((a Int) (b Int) (c Int) (d Int)) Int (+ b d))
        (define-fun vr.sub.lo ((a Int) (b Int) (c Int) (d Int)) Int (- a d))
        (define-fun vr.sub.hi ((a Int) (b Int) (c Int) (d Int)) Int (- b c))
        (define-fun vr.neg.lo ((a Int) (b Int)) Int (- b))
        (define-fun vr.neg.hi ((a Int) (b Int)) Int (- a))
        (define-fun vr.magnitude ((a Int) (b Int)) Int (vr.max (abs a) (abs b)))
        (define-fun vr.abs.lo ((a Int) (b Int)) Int (ite (>= a 0) a (ite (<= b 0) (- b) 0)))
        (define-fun vr.abs.hi ((a Int) (b Int)) Int
          (ite (>= a 0) b (ite (<= b 0) (- a) (vr.magnitude a b))))
        (define-fun vr.divideBy.lo ((a Int) (b Int) (d Int)) Int
          (vr.min (trunc.div a d) (trunc.div b d)))
        (define-fun vr.divideBy.hi ((a Int) (b Int) (d Int)) Int
          (vr.max (trunc.div a d) (trunc.div b d)))
        (define-fun vr.within ((a Int) (b Int) (L Int) (H Int)) Bool (and (>= a L) (<= b H)))
        ; Over the reals, for mul.
        (define-fun vr.rmin ((p Real) (q Real)) Real (ite (<= p q) p q))
        (define-fun vr.rmax ((p Real) (q Real)) Real (ite (>= p q) p q))
        (define-fun vr.mul.lo ((a Real) (b Real) (c Real) (d Real)) Real
          (vr.rmin (vr.rmin (* a c) (* a d)) (vr.rmin (* b c) (* b d))))
        (define-fun vr.mul.hi ((a Real) (b Real) (c Real) (d Real)) Real
          (vr.rmax (vr.rmax (* a c) (* a d)) (vr.rmax (* b c) (* b d))))
        """);
    rule(out, "shift: x in [a, b] and s in [s1, s2] give x + s in [a + s1, b + s2]",
        List.of(MEMBER, "(declare-const s1 Int) (declare-const s2 Int) (declare-const s Int)",
            "(assert (and (<= s1 s) (<= s s2)))"),
        "(+ x s)", "(vr.shift.lo a b s1 s2)", "(vr.shift.hi a b s1 s2)");
    rule(out, "hull: a member of either interval is in [min(a, c), max(b, d)]",
        List.of("(declare-const a Int) (declare-const b Int) (declare-const c Int)",
            "(declare-const d Int) (declare-const x Int)",
            "(assert (or (and (<= a x) (<= x b)) (and (<= c x) (<= x d))))"),
        "x", "(vr.hull.lo a b c d)", "(vr.hull.hi a b c d)");
    rule(out, "add: x + y in [a + c, b + d]", List.of(MEMBERS), "(+ x y)",
        "(vr.add.lo a b c d)", "(vr.add.hi a b c d)");
    rule(out, "sub: x - y in [a - d, b - c]", List.of(MEMBERS), "(- x y)",
        "(vr.sub.lo a b c d)", "(vr.sub.hi a b c d)");
    rule(out, "neg: -x in [-b, -a]", List.of(MEMBER), "(- x)", "(vr.neg.lo a b)",
        "(vr.neg.hi a b)");
    rule(out, "magnitude: |x| in [0, max(|a|, |b|)]", List.of(MEMBER), "(abs x)", "0",
        "(vr.magnitude a b)");
    rule(out, "abs: |x| in the interval the three arms give", List.of(MEMBER), "(abs x)",
        "(vr.abs.lo a b)", "(vr.abs.hi a b)");
    check(out, "within: an interval inside [L, H] has every member inside it", "unsat",
        List.of(MEMBER, "(declare-const L Int) (declare-const H Int)",
            "(assert (vr.within a b L H))", "(assert (not (and (<= L x) (<= x H))))"));
    check(out, "within: and one that reaches past H by one has a member outside", "sat",
        List.of(MEMBER, "(declare-const L Int) (declare-const H Int)",
            "(assert (vr.within a b L (+ H 1)))", "(assert (not (and (<= L x) (<= x H))))"));
    check(out, "fitsInt: an interval within the int range has every member an int", "unsat",
        List.of(MEMBER, "(assert (vr.within a b jint.MIN jint.MAX))",
            "(assert (not (jint.in x)))"));
    for (int d : INT_DIVISORS) {
      rule(out, "divideBy " + d + ": x / d, truncating, in [min(a / d, b / d), max(a / d, b / d)]",
          List.of(MEMBER), "(trunc.div x " + lit(d) + ")",
          "(vr.divideBy.lo a b " + lit(d) + ")", "(vr.divideBy.hi a b " + lit(d) + ")");
    }
    String reals = "(declare-const a Real) (declare-const b Real) (declare-const c Real)\n"
        + "(declare-const d Real) (declare-const x Real) (declare-const y Real)\n"
        + "(assert (and (<= a x) (<= x b) (<= c y) (<= y d)))";
    check(out, "mul, over the reals: x * y lies within the four corner products", "unsat",
        List.of(reals,
            "(assert (not (and (<= (vr.mul.lo a b c d) (* x y))",
            "  (<= (* x y) (vr.mul.hi a b c d)))))"));
    check(out, "mul, over the reals: with the corner b * d dropped it does not", "sat",
        List.of(reals,
            "(define-fun lo3 () Real (vr.rmin (vr.rmin (* a c) (* a d)) (* b c)))",
            "(define-fun hi3 () Real (vr.rmax (vr.rmax (* a c) (* a d)) (* b c)))",
            "(assert (not (and (<= lo3 (* x y)) (<= (* x y) hi3))))"));
    String symmetric = "(declare-const m1 Real) (declare-const m2 Real)\n"
        + "(declare-const x Real) (declare-const y Real)\n"
        + "(assert (and (<= (- m1) x) (<= x m1) (<= (- m2) y) (<= y m2)))";
    check(out, "mul, symmetric, over the reals: |x| <= m1 and |y| <= m2 give |x * y| <= m1 * m2",
        "unsat", List.of(symmetric, "(assert (not (<= (abs (* x y)) (* m1 m2))))"));
    check(out, "mul, symmetric, over the reals: and not m1 * m2 - 1", "sat",
        List.of(symmetric, "(assert (not (<= (abs (* x y)) (- (* m1 m2) 1))))"));
    out.append("\n; The stated rules against the code, at the sample intervals.\n");
    var shifts = new ArrayList<String>();
    var hulls = new ArrayList<String>();
    var adds = new ArrayList<String>();
    var subs = new ArrayList<String>();
    var muls = new ArrayList<String>();
    var unary = new ArrayList<String>();
    var divides = new ArrayList<String>();
    for (long[] p : SAMPLE_INTERVALS) {
      Range r = VarkaValueRange.of(p[0], p[1]);
      pinEnds(shifts, "vr.shift", ends(p) + " (- 5) 7", r.shift(-5, 7));
      pinEnds(unary, "vr.neg", ends(p), r.neg());
      pinEnds(unary, "vr.abs", ends(p), r.abs());
      unary.add("(= (vr.magnitude " + ends(p) + ") " + lit(r.magnitude().getAsLong()) + ")");
      unary.add("(= (vr.within " + ends(p) + " jint.MIN jint.MAX) " + r.fitsInt() + ")");
      for (int d : INT_DIVISORS) {
        pinEnds(divides, "vr.divideBy", ends(p) + " " + lit(d), r.divideBy(d));
      }
      for (long[] q : SAMPLE_INTERVALS) {
        Range s = VarkaValueRange.of(q[0], q[1]);
        pinEnds(hulls, "vr.hull", ends(p) + " " + ends(q), r.hull(s));
        pinEnds(adds, "vr.add", ends(p) + " " + ends(q), r.add(s));
        pinEnds(subs, "vr.sub", ends(p) + " " + ends(q), r.sub(s));
        if (!(r.mul(s) instanceof VarkaValueRange.Bounded m)) {
          throw new IllegalStateException("mul saturates at the samples " + ends(p) + " and "
              + ends(q) + "; the pins' samples are chosen bounded, so choose another");
        }
        muls.add("(= (vr.mul.lo " + rends(p) + " " + rends(q) + ") " + rlit(m.lo()) + ")");
        muls.add("(= (vr.mul.hi " + rends(p) + " " + rends(q) + ") " + rlit(m.hi()) + ")");
      }
    }
    equations(out, "shift as stated is Bounded.shift", shifts);
    equations(out, "hull as stated is Bounded.hull", hulls);
    equations(out, "add as stated is Bounded.add", adds);
    equations(out, "sub as stated is Bounded.sub", subs);
    equations(out, "mul as stated is Bounded.mul", muls);
    equations(out, "neg, abs, magnitude and fitsInt as stated are Bounded's", unary);
    equations(out, "divideBy as stated is Bounded.divideBy, for every int-lane divisor", divides);
    return out.toString();
  }

  /**
   * {@code range_analysis.smt2}: the arithmetic arms of {@code VarkaRangeAnalysis} in the witness
   * form, over Java's wrapping, each pinned to the analysis's own answer on a sample tree.
   */
  static String rangeAnalysis() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The compile-time range analysis (VarkaRangeAnalysis) is sound over Java's wrapping
        ; arithmetic (VARKA-281), in the form that is true: for every value of each child's
        ; interval there is a member of the node's interval congruent, modulo 2^32, to the lane
        ; value the node computes from the children's lane values. "The interval contains the
        ; lane value" is false past int32 - a literal shift of 2^31 - 1 carries the contract's
        ; interval past it while the lanes wrap - and two checks below keep that edge visible. The
        ; compiler reads an interval only when it lies within int32 (decomposesExactly, fitsInt, a
        ; magnitude at most Integer.MAX_VALUE), where the member is the lane value itself.
        ;
        ; A child with witness m in [lo, hi] has the lane value (jint.wrap m); the node's lane
        ; value is written with the prelude's operators as the lanes compute it, and its witness
        ; is written out, so every check is quantifier-free. Under DAY a shift moves both ends;
        ; under INT the compiler's trees produce symmetric answers, [-m, m], but a BoundedDivide
        ; or an int-lane GuardedRange answers an asymmetric interval, so the arithmetic arms are
        ; stated over two general intervals [a, b] and [c, d] - which is what found SUB answering
        ; the sum's interval (VARKA-281.md 9.4). Each rule's functions ra.<node>.* are shared by
        ; its claim, its two neighbours narrowed by one (sat), and a pin to
        ; VarkaRangeAnalysis.range's own answer on a sample tree. MUL's congruence goes in Fiat's
        ; two layers - the ring identity as a polynomial both solvers normalise, then the wrap's
        ; periodicity, which is linear - and its corners over the reals, since over the integers
        ; each solver leaves one of the two unknown (VARKA-281.md 2.3). The constants of the day
        ; rules are VarkaRangeAnalysis's own; the divisors are the int lane's
        ; (VarkaProofFiles.INT_DIVISORS); a divisor of -1 is refused by Analysis before any
        ; emission. The file declares ALL for the reals.
        """);
    out.append(rendered("ALL"));
    VarkaRangeAnalysis.Shift next = VarkaRangeAnalysis.NEXT_DAY_SHIFT;
    VarkaRangeAnalysis.Shift thursday = VarkaRangeAnalysis.THURSDAY_SHIFT;
    out.append("""

        (define-fun ra.lit.m ((v Int)) Int (abs v))
        (define-fun ra.dayshift.lo ((lo Int) (hi Int) (v Int)) Int (+ lo v))
        (define-fun ra.dayshift.hi ((lo Int) (hi Int) (v Int)) Int (+ hi v))
        """);
    out.append("(define-fun ra.nextday.lo ((lo Int) (hi Int)) Int (+ lo ").append(lit(next.lo()))
        .append("))\n(define-fun ra.nextday.hi ((lo Int) (hi Int)) Int (+ hi ")
        .append(lit(next.hi())).append("))\n");
    out.append("(define-fun ra.thursday.lo ((lo Int) (hi Int)) Int (+ lo ")
        .append(lit(thursday.lo())).append("))\n(define-fun ra.thursday.hi ((lo Int) (hi Int)) Int"
        + " (+ hi ").append(lit(thursday.hi())).append("))\n");
    out.append("""
        (define-fun ra.hull.lo ((a Int) (b Int) (c Int) (d Int)) Int (ite (<= a c) a c))
        (define-fun ra.hull.hi ((a Int) (b Int) (c Int) (d Int)) Int (ite (>= b d) b d))
        (define-fun ra.datediff.m ((elo Int) (ehi Int) (slo Int) (shi Int)) Int
          (ite (>= (abs (- ehi slo)) (abs (- elo shi))) (abs (- ehi slo)) (abs (- elo shi))))
        ; The int arithmetic, over two intervals [a, b] and [c, d]: the lattice's add, sub, neg,
        ; mul and divideBy.
        (define-fun ra.add.lo ((a Int) (b Int) (c Int) (d Int)) Int (+ a c))
        (define-fun ra.add.hi ((a Int) (b Int) (c Int) (d Int)) Int (+ b d))
        (define-fun ra.sub.lo ((a Int) (b Int) (c Int) (d Int)) Int (- a d))
        (define-fun ra.sub.hi ((a Int) (b Int) (c Int) (d Int)) Int (- b c))
        (define-fun ra.neg.lo ((a Int) (b Int)) Int (- b))
        (define-fun ra.neg.hi ((a Int) (b Int)) Int (- a))
        (define-fun ra.min ((p Int) (q Int)) Int (ite (<= p q) p q))
        (define-fun ra.max ((p Int) (q Int)) Int (ite (>= p q) p q))
        (define-fun ra.mul.lo ((a Int) (b Int) (c Int) (d Int)) Int
          (ra.min (ra.min (* a c) (* a d)) (ra.min (* b c) (* b d))))
        (define-fun ra.mul.hi ((a Int) (b Int) (c Int) (d Int)) Int
          (ra.max (ra.max (* a c) (* a d)) (ra.max (* b c) (* b d))))
        (define-fun ra.rmin ((p Real) (q Real)) Real (ite (<= p q) p q))
        (define-fun ra.rmax ((p Real) (q Real)) Real (ite (>= p q) p q))
        (define-fun ra.rmul.lo ((a Real) (b Real) (c Real) (d Real)) Real
          (ra.rmin (ra.rmin (* a c) (* a d)) (ra.rmin (* b c) (* b d))))
        (define-fun ra.rmul.hi ((a Real) (b Real) (c Real) (d Real)) Real
          (ra.rmax (ra.rmax (* a c) (* a d)) (ra.rmax (* b c) (* b d))))
        (define-fun ra.constdiv.lo ((a Int) (b Int) (d Int)) Int
          (ra.min (trunc.div a d) (trunc.div b d)))
        (define-fun ra.constdiv.hi ((a Int) (b Int) (d Int)) Int
          (ra.max (trunc.div a d) (trunc.div b d)))
        (define-fun ra.boundeddiv.hi ((bound Int) (d Int)) Int (div (- bound 1) d))
        """);
    // The literal.
    witnessRule(out, "LiteralSlot under INT: a literal v lies in [-|v|, |v|]",
        List.of("(declare-const v Int)", "(assert (jint.in v))"), "v", "v",
        "(- (ra.lit.m v))", "(ra.lit.m v)");
    check(out, "LiteralSlot under DAY: a literal is the point [v, v]", "unsat",
        List.of("(declare-const v Int)", "(assert (jint.in v))",
            "(assert (not (and (<= v v) (<= v v) (= (jint.wrap v) v))))"));
    // The literal day shifts.
    witnessRule(out, "AddDays by a literal v: the lane's d + v is congruent to m + v in "
        + "[lo + v, hi + v]", List.of(DAY_CHILD, "(declare-const v Int)", "(assert (jint.in v))"),
        "(+ m v)", "(jint.add jc v)", "(ra.dayshift.lo lo hi v)", "(ra.dayshift.hi lo hi v)");
    witnessRule(out, "SubDays by a literal v: the lane's d - v is congruent to m - v in "
        + "[lo - v, hi - v]", List.of(DAY_CHILD, "(declare-const v Int)", "(assert (jint.in v))"),
        "(- m v)", "(jint.sub jc v)", "(ra.dayshift.lo lo hi (- v))",
        "(ra.dayshift.hi lo hi (- v))");
    // next_day: d + 1 + floorMod(k - d, 7), k = dayOfWeek - 1 in [-1, 5].
    var nextDay = List.of(DAY_CHILD, "(declare-const k Int)",
        "(assert (and (<= (- 1) k) (<= k 5)))",
        "(define-fun r () Int (jint.floorMod (jint.sub k jc) 7))",
        "(define-fun jn () Int (jint.add (jint.add jc 1) r))");
    witnessRule(out, "NextDay: the lane's d + 1 + floorMod(k - d, 7) is congruent to m + 1 + r "
        + "in [lo + " + next.lo() + ", hi + " + next.hi() + "], whatever the child's wrap", nextDay,
        "(+ m 1 r)", "jn", "(ra.nextday.lo lo hi)", "(ra.nextday.hi lo hi)");
    check(out, "NextDay: the lane value itself can lie outside the interval, past int32", "sat",
        List.of(DAY_CHILD, "(declare-const k Int)", "(assert (and (<= (- 1) k) (<= k 5)))",
            "(define-fun r () Int (jint.floorMod (jint.sub k jc) 7))",
            "(define-fun jn () Int (jint.add (jint.add jc 1) r))",
            "(assert (not (and (<= (ra.nextday.lo lo hi) jn) (<= jn (ra.nextday.hi lo hi)))))"));
    check(out, "NextDay: and lies inside it whenever the interval lies within int32, which is "
        + "when the compiler reads it", "unsat",
        List.of(DAY_CHILD, "(declare-const k Int)", "(assert (and (<= (- 1) k) (<= k 5)))",
            "(assert (and (jint.in (ra.nextday.lo lo hi)) (jint.in (ra.nextday.hi lo hi))))",
            "(define-fun r () Int (jint.floorMod (jint.sub k jc) 7))",
            "(define-fun jn () Int (jint.add (jint.add jc 1) r))",
            "(assert (not (and (<= (ra.nextday.lo lo hi) jn) (<= jn (ra.nextday.hi lo hi)))))"));
    // The Thursday: d + 3 - floorMod(d + 3, 7).
    witnessRule(out, "ThursdayOf: the lane's d + 3 - floorMod(d + 3, 7) is congruent to m + 3 - w "
        + "in [lo " + thursday.lo() + ", hi + " + thursday.hi() + "]",
        List.of(DAY_CHILD, "(define-fun w () Int (jint.floorMod (jint.add jc 3) 7))",
            "(define-fun jn () Int (jint.sub (jint.add jc 3) w))"),
        "(- (+ m 3) w)", "jn", "(ra.thursday.lo lo hi)", "(ra.thursday.hi lo hi)");
    // The hull nodes: the lane takes one branch's value, whose witness is in the hull.
    String twoDays = "(declare-const a Int) (declare-const b Int) (declare-const m1 Int)\n"
        + "(declare-const c Int) (declare-const d Int) (declare-const m2 Int)\n"
        + "(assert (and (<= a m1) (<= m1 b) (<= c m2) (<= m2 d)))\n"
        + "(define-fun j1 () Int (jint.wrap m1)) (define-fun j2 () Int (jint.wrap m2))";
    // One check per branch taken, rather than an ite over the wrapped lanes: stated with the
    // ite, cvc5 decided Greatest and left Least unknown after ten seconds when run alone.
    witnessRule(out, "Greatest under DAY, the left lane at least the right: Math.max is the left "
        + "lane, congruent to the left witness, in the hull",
        List.of(twoDays, "(assert (>= j1 j2))"), "m1", "j1", "(ra.hull.lo a b c d)",
        "(ra.hull.hi a b c d)");
    witnessRule(out, "Greatest under DAY, the right lane above the left: Math.max is the right "
        + "lane, congruent to the right witness, in the hull",
        List.of(twoDays, "(assert (< j1 j2))"), "m2", "j2", "(ra.hull.lo a b c d)",
        "(ra.hull.hi a b c d)");
    witnessRule(out, "Least under DAY, the left lane at most the right: Math.min is the left lane, "
        + "congruent to the left witness, in the hull",
        List.of(twoDays, "(assert (<= j1 j2))"), "m1", "j1", "(ra.hull.lo a b c d)",
        "(ra.hull.hi a b c d)");
    witnessRule(out, "Least under DAY, the right lane below the left: Math.min is the right lane, "
        + "congruent to the right witness, in the hull",
        List.of(twoDays, "(assert (> j1 j2))"), "m2", "j2", "(ra.hull.lo a b c d)",
        "(ra.hull.hi a b c d)");
    witnessRule(out, "IfElse under DAY, the condition true: the then branch's lane, congruent to "
        + "its witness, in the hull", List.of(twoDays), "m1", "j1", "(ra.hull.lo a b c d)",
        "(ra.hull.hi a b c d)");
    witnessRule(out, "IfElse under DAY, the condition false or unknown: the else branch's lane, "
        + "congruent to its witness, in the hull", List.of(twoDays), "m2", "j2",
        "(ra.hull.lo a b c d)", "(ra.hull.hi a b c d)");
    // datediff: both operands' intervals within int32, the difference symmetric.
    witnessRule(out, "DateDiff: with both operands' intervals within int32, the lane's end - start "
        + "is congruent to the witnesses' difference in [-m, m]", List.of(
            "(declare-const elo Int) (declare-const ehi Int) (declare-const me Int)",
            "(declare-const slo Int) (declare-const shi Int) (declare-const ms Int)",
            "(assert (and (<= elo me) (<= me ehi) (<= slo ms) (<= ms shi)))",
            "(assert (and (jint.in elo) (jint.in ehi) (jint.in slo) (jint.in shi)))",
            "(define-fun jn () Int (jint.sub (jint.wrap me) (jint.wrap ms)))"),
        "(- me ms)", "jn", "(- (ra.datediff.m elo ehi slo shi))",
        "(ra.datediff.m elo ehi slo shi)");
    // Int arithmetic, over two general intervals.
    witnessRule(out, "IntArith ADD: the lane's x + y is congruent to mx + my in [a + c, b + d]",
        List.of(INT_GENERAL), "(+ mx my)", "(jint.add jx jy)", "(ra.add.lo a b c d)",
        "(ra.add.hi a b c d)");
    witnessRule(out, "IntArith SUB: the lane's x - y is congruent to mx - my in [a - d, b - c]",
        List.of(INT_GENERAL), "(- mx my)", "(jint.sub jx jy)", "(ra.sub.lo a b c d)",
        "(ra.sub.hi a b c d)");
    check(out, "IntArith SUB: the sum's interval, which the analysis answered until VARKA-281, "
        + "misses differences of asymmetric operands", "sat",
        List.of(INT_GENERAL, "(assert (and (<= 0 a) (<= 0 c)))",
            "(assert (not (and (<= (ra.add.lo a b c d) (- mx my)) (<= (- mx my) "
                + "(ra.add.hi a b c d)))))"));
    check(out, "IntArith SUB: and is the difference's over symmetric ones", "unsat",
        List.of(INT_GENERAL, "(assert (and (= a (- b)) (= c (- d))))",
            "(assert (not (and (= (ra.add.lo a b c d) (ra.sub.lo a b c d)) "
                + "(= (ra.add.hi a b c d) (ra.sub.hi a b c d)))))"));
    out.append("\n; IntArith MUL, in two layers: the congruence as a ring identity and the wrap's\n"
        + "; periodicity, and the corners over the reals.\n");
    check(out, "IntArith MUL, layer A: with jx = mx - 2^32 q1 and jy = my - 2^32 q2, "
        + "jx * jy - mx * my is 2^32 times Q = 2^32 q1 q2 - q1 my - q2 mx", "unsat",
        List.of("(declare-const mx Int) (declare-const my Int)",
            "(declare-const q1 Int) (declare-const q2 Int)",
            "(define-fun jx () Int (- mx (* 4294967296 q1)))",
            "(define-fun jy () Int (- my (* 4294967296 q2)))",
            "(define-fun Q () Int (- (* 4294967296 q1 q2) (* q1 my) (* q2 mx)))",
            "(assert (not (= (- (* jx jy) (* mx my)) (* 4294967296 Q))))"));
    check(out, "IntArith MUL, layer B: a lane value is its witness less 2^32 times an integer, and "
        + "jint.wrap(v + 2^32 Q) = jint.wrap(v)", "unsat",
        List.of("(declare-const m Int) (declare-const v Int) (declare-const Q Int)",
            "(assert (not (and (= (jint.wrap m) (- m (* 4294967296 (div (+ m 2147483648) "
                + "4294967296))))",
            "  (= (jint.wrap (+ v (* 4294967296 Q))) (jint.wrap v)))))"));
    check(out, "IntArith MUL, the layers composed: a product P of the lanes and M of the witnesses "
        + "with P - M = 2^32 Q and |M| <= K wrap alike, and M lies in [-K, K]", "unsat",
        List.of("(declare-const P Int) (declare-const M Int) (declare-const Q Int)",
            "(declare-const K Int)", "(assert (and (= (- P M) (* 4294967296 Q)) (<= (abs M) K)))",
            "(assert (not (and (= (jint.wrap P) (jint.wrap M)) (<= (- K) M) (<= M K))))"));
    String reals = "(declare-const a Real) (declare-const b Real) (declare-const c Real)\n"
        + "(declare-const d Real) (declare-const x Real) (declare-const y Real)\n"
        + "(assert (and (<= a x) (<= x b) (<= c y) (<= y d)))";
    check(out, "IntArith MUL, the corners over the reals: the witnesses' product lies within "
        + "the four corner products", "unsat",
        List.of(reals, "(assert (not (and (<= (ra.rmul.lo a b c d) (* x y))",
            "  (<= (* x y) (ra.rmul.hi a b c d)))))"));
    check(out, "IntArith MUL, the corners: with b * d dropped it does not", "sat",
        List.of(reals,
            "(define-fun lo3 () Real (ra.rmin (ra.rmin (* a c) (* a d)) (* b c)))",
            "(define-fun hi3 () Real (ra.rmax (ra.rmax (* a c) (* a d)) (* b c)))",
            "(assert (not (and (<= lo3 (* x y)) (<= (* x y) hi3))))"));
    witnessRule(out, "IntNeg: the lane's -x is congruent to -mx in [-b, -a], Integer.MIN_VALUE "
        + "included", List.of(INT_ONE), "(- mx)", "(jint.neg jx)", "(ra.neg.lo a b)",
        "(ra.neg.hi a b)");
    for (int d : INT_DIVISORS) {
      rule(out, "ConstDivide by " + d + ", over a symmetric interval, no hypothesis: the lane's "
          + "x / d lies in [-(m1 / |d|), m1 / |d|]",
          List.of(INT_CHILD, "(assert (>= m1 0))"), "(jint.div jx " + lit(d) + ")",
          "(ra.constdiv.lo (- m1) m1 " + lit(d) + ")", "(ra.constdiv.hi (- m1) m1 " + lit(d) + ")");
      rule(out, "ConstDivide by " + d + ", over an interval within int32, where the lane value is "
          + "the witness: x / d lies in divideBy's interval",
          List.of(INT_ONE, "(assert (and (jint.in a) (jint.in b)))"),
          "(jint.div jx " + lit(d) + ")", "(ra.constdiv.lo a b " + lit(d) + ")",
          "(ra.constdiv.hi a b " + lit(d) + ")");
    }
    for (int d : INT_DIVISORS) {
      if (d < 2) {
        continue;
      }
      rule(out, "BoundedDivide by " + d + ": x in [0, bound) gives x / d in [0, (bound - 1) / d]",
          List.of("(declare-const x Int) (declare-const bound Int)",
              "(assert (and (<= 0 x) (< x bound) (<= 2 bound) (jint.in bound)))"),
          "(jint.div x " + d + ")", "0", "(ra.boundeddiv.hi bound " + d + ")");
    }
    out.append("\n; The stated rules against VarkaRangeAnalysis.range, on sample trees.\n");
    equations(out, "each rule as stated is the analysis's own answer", analysisPins());
    return out.toString();
  }

  /** The analysis's answers on sample trees, as equations over the stated rules. */
  private static List<String> analysisPins() {
    int[] lits = {10, -3, 7};
    IntUnaryOperator table = i -> lits[i];
    var c0 = new VarkaVectorIR.ColumnRef(0);
    var c1 = new VarkaVectorIR.ColumnRef(1);
    var slot0 = new VarkaVectorIR.LiteralSlot(0);
    var slot1 = new VarkaVectorIR.LiteralSlot(1);
    var slot2 = new VarkaVectorIR.LiteralSlot(2);
    var eqs = new ArrayList<String>();
    Range contract = day(c0, table);
    String c = ends(contract);
    pinEnds(eqs, "ra.nextday", c, day(new VarkaVectorIR.NextDay(c0, c1), table));
    pinEnds(eqs, "ra.thursday", c, day(new VarkaVectorIR.ThursdayOf(c0), table));
    var added = new VarkaVectorIR.AddDays(c0, slot0);
    pinEnds(eqs, "ra.dayshift", c + " " + lits[0], day(added, table));
    pinEnds(eqs, "ra.dayshift", c + " " + lit(-lits[0]),
        day(new VarkaVectorIR.SubDays(c0, slot0), table));
    pinEnds(eqs, "ra.hull", c + " " + ends(day(added, table)),
        day(new VarkaVectorIR.Greatest(c0, added), table));
    eqs.add("(= (ra.lit.m " + lit(lits[1]) + ") " + magnitude(slot1, table) + ")");
    eqs.add("(= (ra.datediff.m " + c + " " + c + ") "
        + magnitude(new VarkaVectorIR.DateDiff(c0, c1), table) + ")");
    // The arithmetic, on symmetric operands (two literals) and on asymmetric ones (two bounded
    // divisions, [0, 52] and [0, 3]), which is where SUB's old answer was the sum's.
    var weeks = VarkaVectorIR.BoundedDivide.of(new VarkaVectorIR.DayOfYear(c0), 7, 367);
    var quarters = VarkaVectorIR.BoundedDivide.of(new VarkaVectorIR.Month(c0), 4, 13);
    for (VarkaVectorIR[] operands : new VarkaVectorIR[][] {{slot0, slot2}, {weeks, quarters}}) {
      String ab = ends(intRange(operands[0], table));
      String cd = ends(intRange(operands[1], table));
      for (VarkaVectorIR.IntOp op : VarkaVectorIR.IntOp.values()) {
        var node = new VarkaVectorIR.IntArith(op, VarkaVectorIR.Overflow.WRAP, operands[0],
            operands[1]);
        pinEnds(eqs, "ra." + op.name().toLowerCase(Locale.ROOT), ab + " " + cd,
            intRange(node, table));
      }
      pinEnds(eqs, "ra.neg", ab,
          intRange(new VarkaVectorIR.IntNeg(VarkaVectorIR.Overflow.FAIL, operands[0]), table));
    }
    var product = new VarkaVectorIR.IntArith(VarkaVectorIR.IntOp.MUL, VarkaVectorIR.Overflow.WRAP,
        new VarkaVectorIR.Year(c0), slot2);
    for (int d : INT_DIVISORS) {
      pinEnds(eqs, "ra.constdiv", ends(intRange(product, table)) + " " + lit(d),
          intRange(new VarkaVectorIR.ConstDivide(product, d), table));
      pinEnds(eqs, "ra.constdiv", ends(intRange(weeks, table)) + " " + lit(d),
          intRange(new VarkaVectorIR.ConstDivide(weeks, d), table));
    }
    eqs.add("(= (ra.boundeddiv.hi 367 7) " + lit(((VarkaValueRange.Bounded) intRange(weeks,
        table)).hi()) + ")");
    return eqs;
  }

  private static Range day(VarkaVectorIR node, IntUnaryOperator table) {
    return VarkaRangeAnalysis.range(node, VarkaRangeAnalysis.Kind.DAY,
        VarkaRangeAnalysis.GuardPolicy.NONE, table);
  }

  private static Range intRange(VarkaVectorIR node, IntUnaryOperator table) {
    return VarkaRangeAnalysis.range(node, VarkaRangeAnalysis.Kind.INT,
        VarkaRangeAnalysis.GuardPolicy.NONE, table);
  }

  private static long magnitude(VarkaVectorIR node, IntUnaryOperator table) {
    return VarkaRangeAnalysis.magnitude(node, table).getAsLong();
  }

  private static String ends(Range r) {
    VarkaValueRange.Bounded b = (VarkaValueRange.Bounded) r;
    return lit(b.lo()) + " " + lit(b.hi());
  }

  /** The years the calendar's closed forms are pinned to the JVM at: the sign, century and era
   * edges, the epoch, and the admitted and published ranges' ends. */
  private static List<Integer> pinYears() {
    var years = new LinkedHashSet<Integer>();
    for (int y : new int[] {LocalDate.ofEpochDay(VarkaChrono.NARROW_MIN_DAYS).getYear(),
        LocalDate.ofEpochDay(VarkaChrono.NARROW_MIN_DAYS).getYear() + 1, -401, -400, -399, -101,
        -100, -99, -5, -4, -1, 0, 1, 3, 4, 99, 100, 101, 399, 400, 401, 1969, 1970, 1972, 2000,
        2024, 9999, VarkaChrono.MAKE_DATE_MIN_YEAR, VarkaChrono.MAKE_DATE_MAX_YEAR,
        LocalDate.ofEpochDay(VarkaChrono.NARROW_MAX_DAYS).getYear(),
        LocalDate.ofEpochDay(VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS).getYear() - 1,
        LocalDate.ofEpochDay(VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS).getYear()}) {
      years.add(y);
    }
    return new ArrayList<>(years);
  }

  /**
   * {@code range_calendar.smt2}: the calendar rules of {@code VarkaRangeAnalysis} over
   * {@code java.time}'s own closed forms, which are pinned to the JVM.
   */
  static String rangeCalendar() {
    int yMin = LocalDate.ofEpochDay(VarkaChrono.NARROW_MIN_DAYS).getYear();
    int yMax = LocalDate.ofEpochDay(VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS).getYear();
    int kMin = VarkaChrono.MONTH_ARITH_MIN_MONTHS;
    int kMax = VarkaChrono.MONTH_ARITH_MAX_MONTHS;
    int dMin = VarkaRangeAnalysis.MONTH_DAYS_MIN;
    int dMax = VarkaRangeAnalysis.MONTH_DAYS_MAX;
    VarkaRangeAnalysis.Shift lastDay = VarkaRangeAnalysis.LAST_DAY_SHIFT;
    VarkaRangeAnalysis.Shift trunc = VarkaRangeAnalysis.TRUNC_SHIFT;
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The calendar rules of VarkaRangeAnalysis (VARKA-281), over java.time's own closed forms:
        ; LocalDate.isLeapYear, Month.length, LocalDate.toEpochDay with both of its sign branches
        ; and plusMonths's floorDiv and resolvePreviousValid, transcribed from the JDK's source and
        ; pinned to the JVM below at the sign, century and era edges and the ranges' ends. The
        ; day rules each move a child's interval by a shift whose ends are the analysis's own
        ; constants, the field rules answer a magnitude; the claims are over every valid date of
        ; the admitted years, which cover the days the compiler admits a calendar node for,
        ; [NARROW_MIN_DAYS, NARROW_DECOMPOSE_MAX_DAYS]. A rule narrowed by one is refuted beside
        ; each claim. add_months with the month count symbolic is one check split by the signs of
        ; the count, the year and the target year: as one check it takes Z3 12 s and cvc5 cannot
        ; decide it under QF_NIA (VARKA-281.md 2.3); the file declares ALL. The weekday fields and
        ; weekofyear are stated over the forms the lanes compute; that each form is java.time's
        ; field, like every rule here, is VarkaRangeRulesSuite's sweep of the admitted range.
        """);
    out.append(rendered("ALL"));
    out.append("""

        (define-fun cal.leap ((y Int)) Bool
          (and (= (mod y 4) 0) (or (not (= (mod y 100) 0)) (= (mod y 400) 0))))
        (define-fun cal.mlen ((y Int) (m Int)) Int
          (ite (= m 2) (ite (cal.leap y) 29 28)
               (ite (or (= m 4) (= m 6) (= m 9) (= m 11)) 30 31)))
        ; LocalDate.toEpochDay: 365 y plus the leap days, by floor for a non-negative year and by
        ; Java's truncating / of the negative year for a negative one; the month's days by
        ; (367 m - 362) / 12 less one or two past February; less DAYS_0000_TO_1970.
        (define-fun cal.epoch ((y Int) (m Int) (d Int)) Int
          (let ((yt (ite (>= y 0)
                         (+ (* 365 y) (- (div (+ y 3) 4) (div (+ y 99) 100)) (div (+ y 399) 400))
                         (- (* 365 y) (+ (- (trunc.div y (- 4)) (trunc.div y (- 100)))
                                         (trunc.div y (- 400)))))))
            (- (+ yt (div (- (* 367 m) 362) 12) (- d 1)
                  (ite (> m 2) (ite (cal.leap y) (- 1) (- 2)) 0))
               719528)))
        ; The Monday on or before an epoch day: 1970-01-01 is a Thursday.
        (define-fun cal.monday ((e Int)) Int (- e (jint.floorMod (jint.add e 3) 7)))
        """);
    var pins = new ArrayList<String>();
    for (int y : pinYears()) {
      for (int m = 1; m <= 12; m++) {
        int len = LocalDate.of(y, m, 1).lengthOfMonth();
        pins.add("(= (cal.mlen " + lit(y) + " " + m + ") " + len + ")");
        for (int d : new int[] {1, 28, len}) {
          pins.add("(= (cal.epoch " + lit(y) + " " + m + " " + d + ") "
              + lit(LocalDate.of(y, m, d).toEpochDay()) + ")");
        }
      }
    }
    equations(out, "cal.epoch and cal.mlen are LocalDate.toEpochDay and lengthOfMonth", pins);
    String date = "(declare-const y Int) (declare-const m Int) (declare-const d Int)\n"
        + "(assert (and (<= " + lit(yMin) + " y) (<= y " + yMax + ") (<= 1 m) (<= m 12) (<= 1 d) "
        + "(<= d (cal.mlen y m))))";
    String target = "(define-fun t () Int (+ (* 12 y) (- m 1) k))\n"
        + "(define-fun ty () Int (div t 12))\n"
        + "(define-fun tm () Int (+ (- t (* 12 ty)) 1))\n"
        + "(define-fun td () Int (ite (<= d (cal.mlen ty tm)) d (cal.mlen ty tm)))\n"
        + "(define-fun shift () Int (- (cal.epoch ty tm td) (cal.epoch y m d)))";
    String months = "(declare-const k Int)\n(assert (and (<= " + lit(kMin) + " k) (<= k " + kMax
        + ")))";
    out.append("\n; add_months by a literal k in [MONTH_ARITH_MIN_MONTHS, MONTH_ARITH_MAX_MONTHS]:"
        + " plusMonths moves a day by\n; " + dMin + "k to " + dMax + "k days, in the order the "
        + "sign puts them.\n");
    for (String[] ks : new String[][] {{"k >= 0", "(>= k 0)"}, {"k < 0", "(< k 0)"}}) {
      for (String[] ys : new String[][] {{"y >= 0", "(>= y 0)"}, {"y < 0", "(< y 0)"}}) {
        for (String[] ts : new String[][] {{"ty >= 0", "(>= ty 0)"}, {"ty < 0", "(< ty 0)"}}) {
          check(out, "add_months, " + ks[0] + ", " + ys[0] + ", " + ts[0] + ": the shift lies in ["
              + "min(" + dMin + "k, " + dMax + "k), max(" + dMin + "k, " + dMax + "k)]", "unsat",
              List.of(date, months, "(assert (and " + ks[1] + " " + ys[1] + "))", target,
                  "(assert " + ts[1] + ")",
                  "(define-fun lo () Int (ite (<= (* " + dMin + " k) (* " + dMax + " k)) (* " + dMin
                      + " k) (* " + dMax + " k)))",
                  "(define-fun hi () Int (ite (<= (* " + dMin + " k) (* " + dMax + " k)) (* " + dMax
                      + " k) (* " + dMin + " k)))",
                  "(assert (not (and (<= lo shift) (<= shift hi))))"));
        }
      }
    }
    // The neighbours name their witnesses - 31 January 2001 plus one month is 28 February, 1
    // January 2001 plus one month 1 February - because a satisfiable search over the closed
    // forms is what Z3 does not finish: left to find them it ran past 20 s alone, where cvc5
    // took a third of a second.
    check(out, "add_months: with " + (dMin + 1) + "k as the lower bound it does not, at 31 January "
        + "2001 plus one month", "sat",
        List.of(date, months, "(assert (and (= y 2001) (= m 1) (= d 31) (= k 1)))", target,
            "(assert (not (<= (* " + (dMin + 1) + " k) shift)))"));
    check(out, "add_months: with " + (dMax - 1) + "k as the upper bound it does not, at 1 January "
        + "2001 plus one month", "sat",
        List.of(date, months, "(assert (and (= y 2001) (= m 1) (= d 1) (= k 1)))", target,
            "(assert (not (<= shift (* " + (dMax - 1) + " k))))"));
    check(out, "add_months by a column count: the literal rule's interval over every count the "
        + "guard admits lies in [" + dMax + " * MONTH_ARITH_MIN_MONTHS, " + dMax
        + " * MONTH_ARITH_MAX_MONTHS]", "unsat",
        List.of(months, "(declare-const s Int)",
            "(assert (and (<= (ite (<= (* " + dMin + " k) (* " + dMax + " k)) (* " + dMin
                + " k) (* " + dMax + " k)) s)",
            "  (<= s (ite (<= (* " + dMin + " k) (* " + dMax + " k)) (* " + dMax + " k) (* "
                + dMin + " k)))))",
            "(assert (not (and (<= " + lit((long) dMax * kMin) + " s) (<= s "
                + lit((long) dMax * kMax) + "))))"));
    rule(out, "last_day: the last day of the month is " + lastDay.lo() + " to " + lastDay.hi()
        + " days on", List.of(date), "(- (cal.epoch y m (cal.mlen y m)) (cal.epoch y m d))",
        lit(lastDay.lo()), lit(lastDay.hi()));
    rule(out, "trunc to the year: 1 January is " + (-trunc.lo()) + " to " + (-trunc.hi())
        + " days back", List.of(date), "(- (cal.epoch y 1 1) (cal.epoch y m d))",
        lit(trunc.lo()), lit(trunc.hi()));
    check(out, "trunc to the quarter: its first day is inside the same bound", "unsat",
        List.of(date, "(define-fun q1 () Int (+ (* 3 (div (- m 1) 3)) 1))",
            "(define-fun shift () Int (- (cal.epoch y q1 1) (cal.epoch y m d)))",
            "(assert (not (and (<= " + lit(trunc.lo()) + " shift) (<= shift " + lit(trunc.hi())
                + "))))"));
    check(out, "trunc to the month: its first day is inside the same bound", "unsat",
        List.of(date, "(define-fun shift () Int (- (cal.epoch y m 1) (cal.epoch y m d)))",
            "(assert (not (and (<= " + lit(trunc.lo()) + " shift) (<= shift " + lit(trunc.hi())
                + "))))"));
    check(out, "trunc to the week: the Monday on or before any int epoch day is inside the same "
        + "bound", "unsat", List.of("(declare-const e Int)", "(assert (jint.in e))",
            "(define-fun shift () Int (- (cal.monday e) e))",
            "(assert (not (and (<= " + lit(trunc.lo()) + " shift) (<= shift " + lit(trunc.hi())
                + "))))"));
    VarkaValueRange.Bounded made = (VarkaValueRange.Bounded) VarkaRangeAnalysis.MAKE_DATE;
    check(out, "make_date: MAKE_DATE's ends are 1 January of MAKE_DATE_MIN_YEAR and 31 December "
        + "of MAKE_DATE_MAX_YEAR", "unsat", List.of(
            "(assert (not (and (= (cal.epoch " + lit(VarkaChrono.MAKE_DATE_MIN_YEAR) + " 1 1) "
                + lit(made.lo()) + ")",
            "  (= (cal.epoch " + VarkaChrono.MAKE_DATE_MAX_YEAR + " 12 31) " + lit(made.hi())
                + "))))"));
    rule(out, "make_date: a valid date of a year the kernel's guard admits lies in MAKE_DATE",
        List.of("(declare-const y Int) (declare-const m Int) (declare-const d Int)",
            "(assert (and (<= " + lit(VarkaChrono.MAKE_DATE_MIN_YEAR) + " y) (<= y "
                + VarkaChrono.MAKE_DATE_MAX_YEAR + ") (<= 1 m) (<= m 12) (<= 1 d) "
                + "(<= d (cal.mlen y m))))"),
        "(cal.epoch y m d)", lit(made.lo()), lit(made.hi()));
    out.append("\n; The fields, over a day in the admitted range.\n");
    String admitted = "(declare-const y Int) (declare-const m Int) (declare-const d Int)\n"
        + "(assert (and (<= " + lit(yMin - 1) + " y) (<= y " + (yMax + 1) + ") (<= 1 m) (<= m 12) "
        + "(<= 1 d) (<= d (cal.mlen y m))))\n"
        + "(assert (and (<= " + lit(VarkaChrono.NARROW_MIN_DAYS) + " (cal.epoch y m d)) "
        + "(<= (cal.epoch y m d) " + VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS + ")))";
    // The year's interval is symmetric, as every INT answer is, and the admitted years are not:
    // the magnitude is exact at the top and slack at the bottom (m9/SCOPE.md item 86), so the
    // neighbours are the top end's and the bottom's slack is recorded as it is.
    int yearMagnitude = VarkaChrono.YEAR_FIELD_MAGNITUDE;
    check(out, "year: the year of an admitted day has magnitude at most YEAR_FIELD_MAGNITUDE",
        "unsat", List.of(admitted,
            "(assert (not (and (<= " + lit(-yearMagnitude) + " y) (<= y " + yearMagnitude
                + "))))"));
    check(out, "year: with the magnitude lowered by one it does not", "sat",
        List.of(admitted, "(assert (not (<= y " + (yearMagnitude - 1) + ")))"));
    check(out, "year: the low end is slack, since the admitted years start at " + yMin
        + ": raising it to that year still holds", "unsat",
        List.of(admitted, "(assert (not (<= " + lit(yMin) + " y)))"));
    rule(out, "day_of_month: the day of the month lies in [1, "
        + VarkaRangeAnalysis.DAY_OF_MONTH_MAGNITUDE + "]", List.of(admitted), "d", "1",
        lit(VarkaRangeAnalysis.DAY_OF_MONTH_MAGNITUDE));
    rule(out, "quarter: (m + 2) / 3 lies in [1, " + VarkaRangeAnalysis.QUARTER_MAGNITUDE + "]",
        List.of(admitted), "(div (+ m 2) 3)", "1", lit(VarkaRangeAnalysis.QUARTER_MAGNITUDE));
    rule(out, "day_of_year: the days since 1 January, plus one, lie in [1, "
        + VarkaRangeAnalysis.DAY_OF_YEAR_MAGNITUDE + "]", List.of(admitted),
        "(+ (- (cal.epoch y m d) (cal.epoch y 1 1)) 1)", "1",
        lit(VarkaRangeAnalysis.DAY_OF_YEAR_MAGNITUDE));
    rule(out, "month: the month lies in [1, " + VarkaRangeAnalysis.MONTH_MAGNITUDE + "]",
        List.of(admitted), "m", "1", lit(VarkaRangeAnalysis.MONTH_MAGNITUDE));
    out.append("\n; The weekday fields and the week, over the forms the lanes compute, for every"
        + " int epoch day e;\n; that each form is java.time's field is VarkaRangeRulesSuite's"
        + " sweep.\n");
    String anyDay = "(declare-const e Int)\n(assert (jint.in e))";
    rule(out, "dayofweek: (floorMod(e, 7) + 4) % 7 + 1, Sunday 1 to Saturday 7, lies in [1, "
        + VarkaRangeAnalysis.DAY_OF_WEEK_MAGNITUDE + "]", List.of(anyDay),
        "(+ (mod (+ (jint.floorMod e 7) 4) 7) 1)", "1",
        lit(VarkaRangeAnalysis.DAY_OF_WEEK_MAGNITUDE));
    rule(out, "the ISO weekday: floorMod(e + 3, 7) + 1, Monday 1 to Sunday 7, lies in [1, "
        + VarkaRangeAnalysis.DAY_OF_WEEK_MAGNITUDE + "]", List.of(anyDay),
        "(+ (jint.floorMod (jint.add e 3) 7) 1)", "1",
        lit(VarkaRangeAnalysis.DAY_OF_WEEK_MAGNITUDE));
    rule(out, "weekday: (floorMod(e, 7) + 3) % 7, Monday 0 to Sunday 6, lies in [0, "
        + VarkaRangeAnalysis.WEEK_DAY_MAGNITUDE + "]", List.of(anyDay),
        "(mod (+ (jint.floorMod e 7) 3) 7)", "0", lit(VarkaRangeAnalysis.WEEK_DAY_MAGNITUDE));
    rule(out, "weekofyear: (doy - 1) / 7 + 1 over the Thursday's day of the year, in [1, "
        + VarkaRangeAnalysis.DAY_OF_YEAR_MAGNITUDE + "], lies in [1, "
        + VarkaRangeAnalysis.WEEK_OF_YEAR_MAGNITUDE + "]",
        List.of("(declare-const doy Int)", "(assert (and (<= 1 doy) (<= doy "
            + VarkaRangeAnalysis.DAY_OF_YEAR_MAGNITUDE + ")))"),
        "(+ (div (- doy 1) 7) 1)", "1", lit(VarkaRangeAnalysis.WEEK_OF_YEAR_MAGNITUDE));
    return out.toString();
  }

  /** A unit the {@code TIME} compiler divides or multiplies by, and its nanoseconds. */
  private record TimeUnit(String name, long nanos) {
  }

  private static final List<TimeUnit> TIME_UNITS = List.of(
      new TimeUnit("a microsecond", DateTimeConstants.NANOS_PER_MICROS),
      new TimeUnit("a millisecond", DateTimeConstants.NANOS_PER_MILLIS),
      new TimeUnit("a second", DateTimeConstants.NANOS_PER_SECOND),
      new TimeUnit("a minute",
          DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE),
      new TimeUnit("an hour", DateTimeConstants.NANOS_PER_SECOND
          * DateTimeConstants.SECONDS_PER_MINUTE * DateTimeConstants.MINUTES_PER_HOUR));

  /**
   * {@code ConstDivide.quotientBound()} as the {@code TIME} compiler's {@code remainderOfSixty}
   * reads it: the bound of a day's quotient by {@code d}.
   */
  private static long quotientBound(long day, long d) {
    var dividend = new VarkaVectorIR.ColumnRef(0, VarkaVectorIR.LaneType.LONG);
    return new VarkaVectorIR.ConstDivide(dividend, d, day).quotientBound();
  }

  /**
   * {@code time_bounds.smt2}: the six bounds {@code VarkaTimeCompiler} states on the long lane,
   * under the {@code TIME} type's range.
   */
  static String timeBounds() {
    long day = DateTimeConstants.NANOS_PER_DAY;
    long minute = DateTimeConstants.SECONDS_PER_MINUTE;
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The bounds VarkaTimeCompiler states on the long lane hold (VARKA-281), under the premise
        ; that a TIME is a count of nanoseconds of day in [0, NANOS_PER_DAY), which is the type's
        ; range (LocalTime.toNanoOfDay). The compiler's sites, restated with DateTimeConstants:
        ;
        ;   subtractTimes, timeDiff:  (end - start) / unit, bound DAY_OF_NANOS on the difference
        ;   timeTrunc:                (t / unit) * unit, both in wrapping long arithmetic
        ;   the field extracts:       t / HOUR, t / MINUTE, t / SECOND, each under its field's
        ;                             limit, the latter two then x - (x / 60) * 60 under
        ;                             quotientBound(x) = max(1, ceil(bound / divisor))
        ;   timeToUnit:               t / unit, bound DAY_OF_NANOS on t
        ;   timeFromUnits:            a count guarded to [0, (NANOS_PER_DAY - 1) / unit], times
        ;                             the unit
        ;
        ; Each claim is linear: every divisor and unit is a constant, and quotientBound's bound is
        ; symbolic. Beside each, its neighbour: the floor in place of the ceiling, a count one
        ; past the guard, a bound one too narrow.
        """);
    out.append(RENDERED);
    String time = "(declare-const t Int)\n(assert (and (<= 0 t) (< t " + day + ")))";
    String times = "(declare-const e Int) (declare-const s Int)\n(assert (and (<= 0 e) (< e " + day
        + ") (<= 0 s) (< s " + day + ")))";
    check(out, "the dividend bound is one the long lane's forms honour: DAY_OF_NANOS is at most "
        + "ConstDivide.EXACT_DIVIDEND_BOUND", "unsat",
        List.of("(assert (not (<= " + day + " " + VarkaVectorIR.ConstDivide.EXACT_DIVIDEND_BOUND
            + ")))"));
    check(out, "subtractTimes, timeDiff: a difference of two times does not wrap and is under "
        + "DAY_OF_NANOS in magnitude", "unsat",
        List.of(times, "(assert (not (and (= (jlong.sub e s) (- e s)) (< (abs (- e s)) " + day
            + "))))"));
    check(out, "subtractTimes, timeDiff: and the difference reaches DAY_OF_NANOS - 1", "sat",
        List.of(times, "(assert (= (- e s) " + (day - 1) + "))"));
    for (TimeUnit u : TIME_UNITS) {
      long unit = u.nanos();
      check(out, "timeTrunc to " + u.name() + ": (t / unit) * unit does not wrap and is at most t",
          "unsat", List.of(time,
              "(define-fun q () Int (jlong.div t " + unit + "))",
              "(assert (not (and (= (jlong.mul q " + unit + ") (* q " + unit + ")) (<= (* q " + unit
                  + ") t) (<= 0 q))))"));
    }
    long hour = TIME_UNITS.get(4).nanos();
    long min = TIME_UNITS.get(3).nanos();
    long sec = TIME_UNITS.get(2).nanos();
    rule(out, "hour: t / HOUR lies in [0, " + (day / hour - 1) + "]", List.of(time),
        "(jlong.div t " + hour + ")", "0", lit(day / hour - 1));
    rule(out, "minute: t / MINUTE lies in [0, " + (day / min - 1) + "]", List.of(time),
        "(jlong.div t " + min + ")", "0", lit(day / min - 1));
    rule(out, "second: t / SECOND lies in [0, " + (day / sec - 1) + "]", List.of(time),
        "(jlong.div t " + sec + ")", "0", lit(day / sec - 1));
    for (long d : new long[] {min, sec}) {
      long qb = quotientBound(day, d);
      check(out, "quotientBound: |x| < b gives |x / " + d + "| < max(1, ceil(b / " + d + ")), with "
          + "b symbolic", "unsat", List.of("(declare-const x Int) (declare-const b Int)",
              "(assert (and (>= b 1) (< (abs x) b) (jlong.in x)))",
              "(define-fun c () Int (div (+ b " + (d - 1) + ") " + d + "))",
              "(assert (not (< (abs (jlong.div x " + d + ")) (ite (>= c 1) c 1))))"));
      check(out, "quotientBound: with the floor in place of the ceiling it does not", "sat",
          List.of("(declare-const x Int) (declare-const b Int)",
              "(assert (and (>= b 1) (< (abs x) b) (jlong.in x)))",
              "(define-fun c () Int (div b " + d + "))",
              "(assert (not (< (abs (jlong.div x " + d + ")) (ite (>= c 1) c 1))))"));
      check(out, "quotientBound(DAY_OF_NANOS, " + d + ") is " + qb
          + ", as ConstDivide.quotientBound computes it", "unsat",
          List.of("(assert (not (= (div (+ " + day + " " + (d - 1) + ") " + d + ") " + qb
              + ")))"));
      rule(out, "remainderOfSixty: for x in [0, " + qb + "), x - (x / " + minute + ") * " + minute
          + " in wrapping arithmetic is x mod " + minute + ", in [0, " + (minute - 1) + "]",
          List.of("(declare-const x Int)", "(assert (and (<= 0 x) (< x " + qb + ")))",
              "(define-fun r () Int (jlong.sub x (jlong.mul (jlong.div x " + minute + ") " + minute
                  + ")))"),
          "r", "0", lit(minute - 1), " (= r (mod x " + minute + "))");
    }
    for (TimeUnit u : TIME_UNITS) {
      long unit = u.nanos();
      check(out, "timeToUnit by " + u.name() + ": a time's quotient lies in [0, "
          + ((day - 1) / unit) + "]", "unsat", List.of(time,
              "(assert (not (and (<= 0 (jlong.div t " + unit + ")) (<= (jlong.div t " + unit + ") "
                  + ((day - 1) / unit) + "))))"));
    }
    for (TimeUnit u : TIME_UNITS.subList(0, 3)) {
      long unit = u.nanos();
      long top = (day - 1) / unit;
      check(out, "timeFromUnits by " + u.name() + ": a count in [0, " + top + "] times the unit "
          + "does not wrap and is a time of day", "unsat",
          List.of("(declare-const n Int)", "(assert (and (<= 0 n) (<= n " + top + ")))",
              "(define-fun p () Int (* n " + unit + "))",
              "(assert (not (and (= (jlong.mul n " + unit + ") p) (<= 0 p) (< p " + day
                  + "))))"));
      check(out, "timeFromUnits by " + u.name() + ": one count past the guard is not a time of "
          + "day", "sat", List.of("(declare-const n Int)",
              "(assert (= n " + (top + 1) + "))",
              "(assert (not (< (* n " + unit + ") " + day + ")))"));
    }
    return out.toString();
  }

  // -----------------------------------------------------------------------------------------
  // The bitmap functions (VARKA-335).
  // -----------------------------------------------------------------------------------------

  /**
   * {@code bitmap.smt2}: {@code VarkaNodeCompiler.bitmap}'s lowering of
   * {@code bitmap_bucket_number} and {@code bitmap_bit_position} is
   * {@code BitmapExpressionUtils}' for every long, with the shift and the mask rendered from
   * Spark's {@code NUM_BITS} as the compiler derives them.
   */
  static String bitmap() {
    long n = BitmapExpressionUtils.NUM_BITS;
    int shift = Long.numberOfTrailingZeros(n);
    if (1L << shift != n) {
      throw new IllegalStateException("bitmap.smt2 states a shift and a mask for a power of two, "
          + "and NUM_BITS is " + n);
    }
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The bitmap functions' lowering (VARKA-335) is Spark's for every long. Spark's
        ; BitmapExpressionUtils, with N = NUM_BITS:
        ;
        ;   bitmapBucketNumber(v) = v > 0 ? 1 + (v - 1) / N : v / N
        ;   bitmapBitPosition(v)  = v > 0 ? (v - 1) % N : (-v) % N
        ;
        ; VarkaNodeCompiler.bitmap, over the long lane, with N = 2^shift:
        ;
        ;   w = v > 0 ? v - 1 : -v           a blend of a wrapping subtract and negate
        ;   position = w & (N - 1)
        ;   q = w >>> shift
        ;   bucket = v > 0 ? q + 1 : -q      a second blend on the same comparison
        ;
        ; w is never negative but at Long.MIN_VALUE, where -v wraps to itself, Spark's (-v) % N
        ; is 0 and so is the mask, and w >>> shift is 2^(63 - shift), whose negation is
        ; Long.MIN_VALUE / N. Every operation is by a constant, so each claim is linear. Beside
        ; them, that the domain admits Long.MIN_VALUE, and the neighbours: the shift one less, the
        ; mask one bit narrower, and the arithmetic shift in place of the logical one. The logic
        ; is ALL because cvc5 answers the position's claim unknown under QF_NIA and proves it in a
        ; fraction of a second under ALL.
        """);
    out.append(rendered("ALL"));
    String v = "(declare-const v Int)\n(assert (jlong.in v))";
    String w = "(define-fun w () Int (ite (> v 0) (jlong.sub v 1) (jlong.neg v)))";
    String bucketSpec = "(ite (> v 0) (jlong.add 1 (jlong.div (jlong.sub v 1) " + n
        + ")) (jlong.div v " + n + "))";
    String positionSpec = "(ite (> v 0) (jlong.rem (jlong.sub v 1) " + n
        + ") (jlong.rem (jlong.neg v) " + n + "))";
    check(out, "bitmap_bucket_number: the lowering is BitmapExpressionUtils.bitmapBucketNumber "
        + "for every long", "unsat",
        List.of(v, w, "(assert (not (= " + bitmapBucket("jlong.ushr", shift) + " " + bucketSpec
            + ")))"));
    check(out, "bitmap_bit_position: the lowering is BitmapExpressionUtils.bitmapBitPosition "
        + "for every long", "unsat",
        List.of(v, w, "(assert (not (= (jlong.and.low w " + n + ") " + positionSpec + ")))"));
    check(out, "the domain admits Long.MIN_VALUE, where -v wraps", "sat",
        List.of(v, w, "(assert (= v jlong.MIN))", "(assert (= w jlong.MIN))"));
    check(out, "bitmap_bucket_number: with the shift one less it is not", "sat",
        List.of(v, w, "(assert (not (= " + bitmapBucket("jlong.ushr", shift - 1) + " "
            + bucketSpec + ")))"));
    check(out, "bitmap_bucket_number: with the arithmetic shift it is not, at Long.MIN_VALUE",
        "sat", List.of(v, w, "(assert (not (= " + bitmapBucket("jlong.shr", shift) + " "
            + bucketSpec + ")))"));
    check(out, "bitmap_bit_position: with the mask a bit narrower it is not", "sat",
        List.of(v, w, "(assert (not (= (jlong.and.low w " + (n / 2) + ") " + positionSpec
            + ")))"));
    return out.toString();
  }

  /** The lowering's bucket over {@code w}, with {@code op} its shift by {@code k}. */
  private static String bitmapBucket(String op, int k) {
    return "(ite (> v 0) (jlong.add (" + op + " w " + k + ") 1) (jlong.neg (" + op + " w " + k
        + ")))";
  }

  // -----------------------------------------------------------------------------------------
  // The year-month interval division, HALF_UP on the int lane (VARKA-89).
  // -----------------------------------------------------------------------------------------

  /**
   * Every literal divisor {@code VarkaEmitterYmDivideSuite} sweeps over all 2^32 dividends:
   * small ones, powers of two and their neighbours up to 2^30, the extremes, and every
   * {@code Byte} divisor, in both signs, none of them 0, 1 or -1. The suite asserts its own list
   * is this one.
   */
  static final List<Integer> YM_DIVISORS = ymDivisors();

  private static List<Integer> ymDivisors() {
    var positive = new LinkedHashSet<Integer>();
    for (int d = 2; d <= 20; d++) {
      positive.add(d);
    }
    for (int d : new int[] {60, 100, 360, 1000, 3600, 12345, 32767, 65537, 12345679}) {
      positive.add(d);
    }
    for (int k = 1; k <= 30; k++) {
      positive.add((1 << k) - 1);
      positive.add(1 << k);
      positive.add((1 << k) + 1);
    }
    positive.add(Integer.MAX_VALUE);
    positive.add(Integer.MAX_VALUE - 1);
    positive.removeIf(d -> d < 2);
    var all = new LinkedHashSet<Integer>(positive);
    for (int d : positive) {
      all.add(-d);
    }
    all.add(Integer.MIN_VALUE);
    all.add(Integer.MIN_VALUE + 1);
    for (int d = -128; d <= 127; d++) {
      if (d != 0 && d != 1 && d != -1) {
        all.add(d);
      }
    }
    return List.copyOf(all);
  }

  /**
   * {@code ym_divide.smt2}: the quotient {@code VarkaIntervalCompiler.divide} emits for
   * {@code ym / d}, {@code d} an int-family literal with {@code |d| >= 2}, is
   * {@code IntMath.divide(months, d, RoundingMode.HALF_UP)} for every int dividend, with every
   * wrap of the lane's int arithmetic in place. The specification is stated in unbounded
   * integers, as Guava states HALF_UP: the magnitude of the quotient rounded up when twice the
   * remainder reaches the divisor's magnitude, with the sign of the product of the signs. It is
   * pinned to {@code IntMath} at sample pairs while rendering.
   */
  static String ymDivide() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The year-month interval division (VARKA-89), ym / d for an int-family literal d with
        ; |d| >= 2, is IntMath.divide(months, d, HALF_UP) for every int dividend. What
        ; VarkaIntervalCompiler.divide emits, per lane, with the int lane's wrapping arithmetic:
        ;
        ;   q = m / d                      truncating, ConstDivide
        ;   r = m - q * d                  wrapping multiply and subtract
        ;   magnitude = m < 0 ? -r : r     wrapping negate
        ;   bump = magnitude >= (|d| + 1) / 2 ? (m < 0 ? -away : away) : 0, away = d > 0 ? 1 : -1
        ;   result = q + bump              wrapping add
        ;
        ; The specification is Guava's HALF_UP in unbounded integers: with a = |m| and b = |d|,
        ; the quotient a / b is raised by one when 2 * (a mod b) >= b, and takes the sign of
        ; m * d. The form's one non-obvious step, that the wrapping q * d is exact because the
        ; true remainder fits an int, is not assumed: every wrap is the prelude's own, so the
        ; claim is over what the lane computes.
        ;
        ; The solver proves it for each divisor in VarkaProofFiles.YM_DIVISORS, the list
        ; VarkaEmitterYmDivideSuite sweeps over all 2^32 dividends, with the dividend symbolic.
        ; The divisor symbolic as well, with |d| >= 2, is out of reach: Z3 5.1.0 answers neither
        ; the whole claim in 100 seconds nor any of the four sign cases in 50. Beside them, for
        ; a few divisors, that neighbouring forms are wrong (a threshold one lower or one higher,
        ; the bump's direction not reversed for a negative dividend, and not reversed for a
        ; negative divisor), and that the specification equals IntMath at sample pairs.
        """);
    out.append(rendered("QF_NIA"));
    out.append("""

        (define-fun ym.half.up ((m Int) (d Int)) Int
          (let ((mag (+ (div (abs m) (abs d)) (ite (>= (* 2 (mod (abs m) (abs d))) (abs d)) 1 0))))
            (ite (= (>= m 0) (> d 0)) mag (- mag))))

        ; The emitted form. lo is the bump's threshold, up the bump for a positive dividend, dn
        ; the bump for a negative one.
        (define-fun ym.lowered ((m Int) (d Int) (lo Int) (up Int) (dn Int)) Int
          (let ((q (jint.div m d)))
            (let ((r (jint.sub m (jint.mul q d))))
              (let ((neg (< m 0)))
                (let ((mag (ite neg (jint.neg r) r)))
                  (jint.add q (ite (>= mag lo) (ite neg dn up) 0)))))))

        (declare-const m Int)
        (assert (jint.in m))
        """);
    for (int d : YM_DIVISORS) {
      int half = (int) ((Math.abs((long) d) + 1) / 2);
      int away = d > 0 ? 1 : -1;
      check(out, "d = " + d + ": the emitted form is HALF_UP for every int dividend", "unsat",
          List.of("(assert (not (= " + ymLowered(d, half, away, -away) + " (ym.half.up m "
              + lit(d) + "))))"));
    }
    for (int d : new int[] {2, 7, -7, 12, Integer.MIN_VALUE}) {
      int half = (int) ((Math.abs((long) d) + 1) / 2);
      int away = d > 0 ? 1 : -1;
      check(out, "d = " + d + ": with the threshold one lower it is not", "sat",
          List.of("(assert (not (= " + ymLowered(d, half - 1, away, -away) + " (ym.half.up m "
              + lit(d) + "))))"));
      check(out, "d = " + d + ": with the threshold one higher it is not", "sat",
          List.of("(assert (not (= " + ymLowered(d, half + 1, away, -away) + " (ym.half.up m "
              + lit(d) + "))))"));
      check(out, "d = " + d + ": with the bump's direction not reversed for a negative "
          + "dividend it is not", "sat",
          List.of("(assert (not (= " + ymLowered(d, half, away, away) + " (ym.half.up m "
              + lit(d) + "))))"));
      if (d < 0) {
        check(out, "d = " + d + ": with the bump's direction ignoring the divisor's sign it is "
            + "not", "sat",
            List.of("(assert (not (= " + ymLowered(d, half, 1, -1) + " (ym.half.up m " + lit(d)
                + "))))"));
      }
    }
    int[][] pairs = {{7, 2}, {-7, 2}, {5, -2}, {-5, -2}, {Integer.MIN_VALUE, 2},
        {Integer.MAX_VALUE, 2}, {Integer.MIN_VALUE, Integer.MIN_VALUE}, {Integer.MAX_VALUE, -3},
        {Integer.MIN_VALUE, 7}, {17, 12}, {-18, 12}, {6, 12}, {-6, 12}, {0, 5}};
    for (int[] p : pairs) {
      int expected = IntMath.divide(p[0], p[1], RoundingMode.HALF_UP);
      check(out, "the specification at m = " + p[0] + ", d = " + p[1] + " is IntMath's "
          + expected, "unsat",
          List.of("(assert (not (= (ym.half.up " + lit(p[0]) + " " + lit(p[1]) + ") "
              + lit(expected) + ")))"));
    }
    return out.toString();
  }

  private static String ymLowered(int d, int lo, int up, int dn) {
    return "(ym.lowered m " + lit(d) + " " + lit(lo) + " " + lit(up) + " " + lit(dn) + ")";
  }

  // -----------------------------------------------------------------------------------------
  // The prelude against the JVM.
  // -----------------------------------------------------------------------------------------

  private static final int[] INTS =
      {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -7, -1, 0, 1, 7, Integer.MAX_VALUE};

  private static final int[] INT_DIVISORS_CHECKED =
      {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -7, -1, 1, 7, Integer.MAX_VALUE};

  private static final long[] LONGS = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -(1L << 32), -7, -1, 0,
      1, 7, 1L << 32, Long.MAX_VALUE};

  private static final long[] LONG_DIVISORS_CHECKED = {Long.MIN_VALUE, Long.MIN_VALUE + 1,
      -(1L << 32), -7, -1, 1, 7, 1L << 32, Long.MAX_VALUE};

  /** Shift counts: in range, at and past the mask, and negative, whose low bits are all set. */
  private static final int[] COUNTS = {-1, 0, 1, 31, 32, 33, 63, 64};

  private static final long[] LONG_COUNTS = {-1, 0, 1, 31, 32, 33, 63, 64};

  /**
   * {@code java_check.smt2}: each of the prelude's definitions gives what the JVM gives, over
   * boundary operands - each type's extremes and the values next to them, small values of both
   * signs, and shift counts whose masked-off bits are set. The expected values are computed here,
   * by Java's own operators, while rendering. One check per operator, which names it when it
   * fails.
   */
  static String javaCheck() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The prelude, java.smt2, against the JVM: for each operator it defines, a check that its
        ; definition gives what Java gives over boundary operands - each type's extremes and the
        ; values next to them, small values of both signs, and shift counts whose masked-off bits
        ; are set. The expected values were computed by Java's own operators while rendering, so a
        ; definition with the right name and the wrong meaning fails here before a proof can rest
        ; on it. Each check is unsat when every equation in it holds.
        """);
    out.append(RENDERED);
    range(out, "jint.in", "int", Integer.MIN_VALUE, Integer.MAX_VALUE);
    range(out, "jlong.in", "long", Long.MIN_VALUE, Long.MAX_VALUE);
    ints(out, "jint.add", "int +", INTS, (a, b) -> a + b);
    ints(out, "jint.sub", "int -", INTS, (a, b) -> a - b);
    ints(out, "jint.mul", "int *", INTS, (a, b) -> a * b);
    ints(out, "jint.div", "int /", INT_DIVISORS_CHECKED, (a, b) -> a / b);
    ints(out, "jint.rem", "int %", INT_DIVISORS_CHECKED, (a, b) -> a % b);
    ints(out, "jint.floorDiv", "Math.floorDiv on ints", INT_DIVISORS_CHECKED, Math::floorDiv);
    ints(out, "jint.floorMod", "Math.floorMod on ints", INT_DIVISORS_CHECKED, Math::floorMod);
    ints(out, "jint.shl", "int <<, its count masked", COUNTS, (a, s) -> a << s);
    ints(out, "jint.shr", "int >>, its count masked", COUNTS, (a, s) -> a >> s);
    ints(out, "jint.ushr", "int >>>, its count masked", COUNTS, (a, s) -> a >>> s);
    var intUnary = new ArrayList<String>();
    for (int a : INTS) {
      intUnary.add(eq("jint.neg", lit(a), lit(-a)));
      intUnary.add(eq("jint.not", lit(a), lit(~a)));
      intUnary.add(eq("i2l", lit(a), lit((long) a)));
    }
    equations(out, "jint.neg, jint.not and i2l are Java's int -, int ~ and (long)", intUnary);
    var intMask = new ArrayList<String>();
    for (int a : INTS) {
      for (int k : new int[] {3, 6, 15, 16}) {
        intMask.add(eq("jint.and.low", lit(a) + " " + (1 << k), lit(a & ((1 << k) - 1))));
      }
    }
    equations(out, "jint.and.low is Java's int & with a low mask, at both signs", intMask);
    longs(out, "jlong.add", "long +", LONGS, (a, b) -> a + b);
    longs(out, "jlong.sub", "long -", LONGS, (a, b) -> a - b);
    longs(out, "jlong.mul", "long *", LONGS, (a, b) -> a * b);
    longs(out, "jlong.div", "long /", LONG_DIVISORS_CHECKED, (a, b) -> a / b);
    longs(out, "jlong.rem", "long %", LONG_DIVISORS_CHECKED, (a, b) -> a % b);
    longs(out, "jlong.floorDiv", "Math.floorDiv on longs", LONG_DIVISORS_CHECKED, Math::floorDiv);
    longs(out, "jlong.floorMod", "Math.floorMod on longs", LONG_DIVISORS_CHECKED, Math::floorMod);
    longs(out, "jlong.shl", "long <<, its count masked", LONG_COUNTS, (a, s) -> a << s);
    longs(out, "jlong.shr", "long >>, its count masked", LONG_COUNTS, (a, s) -> a >> s);
    longs(out, "jlong.ushr", "long >>>, its count masked", LONG_COUNTS, (a, s) -> a >>> s);
    var longUnary = new ArrayList<String>();
    for (long a : LONGS) {
      longUnary.add(eq("jlong.neg", lit(a), lit(-a)));
      longUnary.add(eq("jlong.not", lit(a), lit(~a)));
    }
    for (long a : new long[] {Long.MIN_VALUE, -(1L << 32) - 5, -(1L << 31) - 1, -(1L << 31), -1,
        0, (1L << 31) - 1, 1L << 31, (1L << 32) + 5, Long.MAX_VALUE}) {
      longUnary.add(eq("l2i", lit(a), lit((int) a)));
    }
    equations(out, "jlong.neg, jlong.not and l2i are Java's long -, long ~ and (int)", longUnary);
    doubles(out);
    return out.toString();
  }

  /**
   * The doubles (VARKA-241): {@code jdouble.rne} against Java's {@code (double)} of a long and
   * {@code /} of two exact doubles, and against the sum the magic form rounds at its ties; then
   * {@code jdouble.d2l.mag}, the bit layout, and the two bitwise operations the magic form uses.
   * Each rounding is checked both ways: the double Java computed satisfies the definition, and its
   * two neighbours in the same binade do not, so the definition pins one double rather than
   * admitting several.
   */
  private static void doubles(StringBuilder out) {
    var l2d = new ArrayList<String>();
    // 2^54 - 2 is a double, the last of its binade, and 2^54 - 1 is the tie between it and 2^54:
    // the two points where the bottom of 2^54's binade decides.
    for (long v : new long[] {1, 7, P52 - 1, P52, P52 + 1, P53 - 1, P53, P53 + 1, P53 + 2,
        P53 + 3, P53 + 5, 2 * P53 - 2, 2 * P53 - 1, 2 * P53 + 2, 2 * P53 + 6, 2 * P53 + 7,
        3 * P53 + 1, Long.MAX_VALUE}) {
      rounded(l2d, BigInteger.valueOf(v), BigInteger.ONE, (double) v);
    }
    equations(out, "jdouble.rne is Java's (double) of a long, ties included", l2d);
    var div = new ArrayList<String>();
    for (long b : new long[] {3, 49, 60, 1000, 1_000_000, 1_000_000_000, 60_000_000_000L,
        3_600_000_000_000L, 146097}) {
      for (long a : new long[] {1, 2, 7, b - 1, b + 1, 12_345_678_901L, P52 - 1, P52, P52 + 1,
          P53 - 1, P53}) {
        rounded(div, BigInteger.valueOf(a), BigInteger.valueOf(b), (double) a / (double) b);
      }
    }
    equations(out, "jdouble.rne is Java's / of two exact doubles", div);
    // The calendar's reciprocal form (VARKA-242): an int dividend times the double nearest 1 / d.
    var mul = new ArrayList<String>();
    for (long d : new long[] {3, 7, 12, 100, 365, 1461, 146097}) {
      double r = 1.0 / d;
      long raw = Double.doubleToRawLongBits(r);
      BigInteger mr = BigInteger.valueOf((raw & VarkaDivisionLowering.MANTISSA_52) | P52);
      BigInteger den = BigInteger.ONE.shiftLeft(52 - Math.getExponent(r));
      for (long v : new long[] {1, d - 1, d, d + 1, 3 * d, 584399, 20161385}) {
        rounded(mul, BigInteger.valueOf(v).multiply(mr), den, (double) v * r);
      }
    }
    equations(out, "jdouble.rne is Java's * of an int and the double nearest 1 / d", mul);
    // The magic form's q + 2^52 at a quotient ending in a half: the sum is a tie, and Java rounds
    // it to the even neighbour. As a rational, (2n + 1 + 2^53) / 2.
    var ties = new ArrayList<String>();
    for (long n : new long[] {0, 1, 2, 3, 1_000_000, (1L << 51) - 1}) {
      double sum = (n + 0.5) + 4503599627370496.0;
      rounded(ties, BigInteger.valueOf(2 * n + 1 + P53), BigInteger.TWO, sum);
    }
    equations(out, "jdouble.rne is Java's + at a tie, to the even neighbour", ties);
    var d2l = new ArrayList<String>();
    for (double q : new double[] {0.25, 0.5, 1.0, 1.5, 2.5, 7.999999999999999, 12345.678,
        4503599627370495.5, 4503599627370496.0, 9007199254740991.0, 9.2e18}) {
      long bits = Double.doubleToRawLongBits(q);
      int e = Math.getExponent(q) - 52;
      long mant = (bits & VarkaDivisionLowering.MANTISSA_52) | P52;
      String[] sc = scale(e);
      d2l.add("(jdouble.d2l.mag " + (long) q + " " + mant + " " + sc[0] + " " + sc[1] + ")");
      for (long wrong : new long[] {(long) q - 1, (long) q + 1}) {
        d2l.add("(not (jdouble.d2l.mag " + wrong + " " + mant + " " + sc[0] + " " + sc[1] + "))");
      }
    }
    equations(out, "jdouble.d2l.mag is Java's (long) of a double, toward zero", d2l);
    var bits = new ArrayList<String>();
    for (double q : new double[] {Double.MIN_NORMAL, 0.1, 1.0, 1.5, 4503599627370496.0,
        4503599627370497.0, 9007199254740991.0, 1e300}) {
      long raw = Double.doubleToRawLongBits(q);
      int e = Math.getExponent(q) - 52;
      long mant = (raw & VarkaDivisionLowering.MANTISSA_52) | P52;
      bits.add("(= (jdouble.bits " + mant + " " + lit(e) + ") " + raw + ")");
      bits.add("(= (jdouble.fromBits.M " + raw + ") " + mant + ")");
      bits.add("(= (jdouble.fromBits.E " + raw + ") " + lit(e) + ")");
    }
    equations(out, "jdouble.bits and jdouble.fromBits are Double.doubleToRawLongBits and "
        + "longBitsToDouble", bits);
    var bitwise = new ArrayList<String>();
    for (long x : LONGS) {
      bitwise.add("(= (jlong.and.low " + lit(x) + " " + P52 + ") "
          + lit(x & VarkaDivisionLowering.MANTISSA_52) + ")");
    }
    for (long a : new long[] {0, 1, 12_345_678_901L, P52 - 1}) {
      bitwise.add("(= (jlong.or.disjoint " + a + " " + VarkaDivisionLowering.TWO_52_BITS + ") "
          + (a | VarkaDivisionLowering.TWO_52_BITS) + ")");
    }
    equations(out, "jlong.and.low and jlong.or.disjoint are Java's & and |, where the magic form "
        + "uses them", bitwise);
  }

  /**
   * That {@code jdouble.rne} takes {@code a / b} to {@code q}, which Java computed, and to neither
   * neighbour of {@code q}: the next double up and the next down, each written in its own binade,
   * so a neighbour across a binade's edge is refused as well as one inside it.
   */
  private static void rounded(List<String> eqs, BigInteger a, BigInteger b, double q) {
    eqs.add(rne(a, b, q));
    eqs.add("(not " + rne(a, b, Math.nextUp(q)) + ")");
    eqs.add("(not " + rne(a, b, Math.nextDown(q)) + ")");
  }

  /** {@code (jdouble.rne a b M sn sd)} with the positive normal {@code q} as M and its binade. */
  private static String rne(BigInteger a, BigInteger b, double q) {
    long raw = Double.doubleToRawLongBits(q);
    long mant = (raw & VarkaDivisionLowering.MANTISSA_52) | P52;
    String[] sc = scale(Math.getExponent(q) - 52);
    return "(jdouble.rne " + a + " " + b + " " + mant + " " + sc[0] + " " + sc[1] + ")";
  }

  private static void range(StringBuilder out, String in, String type, long min, long max) {
    BigInteger below = BigInteger.valueOf(min).subtract(BigInteger.ONE);
    BigInteger above = BigInteger.valueOf(max).add(BigInteger.ONE);
    var eqs = List.of("(" + in + " " + lit(min) + ")", "(" + in + " " + lit(max) + ")",
        "(not (" + in + " " + lit(below) + "))", "(not (" + in + " " + lit(above) + "))");
    equations(out, in + " holds at the " + type + " range's ends and not one past them", eqs);
  }

  private static void ints(StringBuilder out, String name, String java, int[] rights,
      IntBinaryOperator op) {
    var eqs = new ArrayList<String>();
    for (int a : INTS) {
      for (int b : rights) {
        eqs.add(eq(name, lit(a) + " " + lit(b), lit(op.applyAsInt(a, b))));
      }
    }
    equations(out, name + " is Java's " + java, eqs);
  }

  private static void longs(StringBuilder out, String name, String java, long[] rights,
      LongBinaryOperator op) {
    var eqs = new ArrayList<String>();
    for (long a : LONGS) {
      for (long b : rights) {
        eqs.add(eq(name, lit(a) + " " + lit(b), lit(op.applyAsLong(a, b))));
      }
    }
    equations(out, name + " is Java's " + java, eqs);
  }

  private static String eq(String name, String args, String expected) {
    return "(= (" + name + " " + args + ") " + expected + ")";
  }

  /** One check that every equation holds: unsat for the negation of their conjunction. */
  private static void equations(StringBuilder out, String what, List<String> eqs) {
    var lines = new ArrayList<String>();
    lines.add("(assert (not (and");
    for (String e : eqs) {
      lines.add("  " + e);
    }
    lines.add(")))");
    check(out, what + ", on " + eqs.size() + " operand sets", "unsat", lines);
  }

  // -----------------------------------------------------------------------------------------
  // The narrow lanes' arithmetic (VARKA-330).
  // -----------------------------------------------------------------------------------------

  /** The two lanes a narrow value is stored in, whose wrap and checks this section states. */
  private static final List<Lane> NARROW_LANES = List.of(Lane.BYTE, Lane.SHORT);

  /**
   * {@code narrow_wrap.smt2}: the emitter's reduction of an int-lane value to a narrow lane, a
   * shift left by the lane's spare bits and an arithmetic shift right by the same count
   * ({@code VarkaVectorWalk.emitWrapToNarrow}), is Spark's wrap - sign extension of the int's
   * low {@code w} bits - for every int. And adding, subtracting or multiplying two widened
   * operands and then reducing is computing the operation at the narrow width, which is what
   * lets the widened arm leave an interior result unreduced. The shift is read off the lane as
   * the emitter derives it.
   */
  static String narrowWrap() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The narrow lanes' wrap (VARKA-330). A byte or short result is signExtend(r mod 2^w) of
        ; the exact result r, which is what Spark's ByteExactNumeric and ShortExactNumeric
        ; answer (VARKA-329.md 4.1). The widened arm computes at the int lane and reduces here
        ; where a consumer that is not a ring operation reads the value:
        ;
        ;   wrap(x) = (x << s) >>a s,   s = 32 - w        VarkaVectorWalk.emitWrapToNarrow
        ;
        ; w is the lane's width, 8 or 16, and s is read from the lane here. The first claim is
        ; that the pair is the sign extension of the low w bits for every int; the next three
        ; that the ring operations commute with it, so reducing once at the end of a chain of
        ; adds, subtracts and multiplies agrees with reducing at every node. Beside them, that
        ; the domain admits the lane's two ends, and the neighbours: the logical shift and the
        ; shift one less are not the wrap.
        ;
        ; The logic is ALL because the file reasons over bit-vectors.
        """);
    out.append(rendered("ALL"));
    for (Lane lane : NARROW_LANES) {
      int w = lane.bits;
      String kind = lane.laneType.name().toLowerCase(Locale.ROOT);
      String wide = "(declare-const x (_ BitVec 32))";
      String a = "(declare-const a (_ BitVec " + w + "))";
      String b = "(declare-const b (_ BitVec " + w + "))";
      check(out, "narrow_wrap: the shift pair is sign extension of the low " + w
          + " bits for every int", "unsat", List.of(wide,
          "(assert (not (= " + shiftPair("x", lane) + " " + narrowWrap("x", lane) + ")))"));
      for (String op : List.of("bvadd", "bvsub", "bvmul")) {
        String name = op.substring(2).toLowerCase(Locale.ROOT);
        String at32 = "(" + op + " ((_ sign_extend " + (32 - w) + ") a) ((_ sign_extend "
            + (32 - w) + ") b))";
        check(out, "narrow_wrap: the int lane's " + name + ", reduced, is the " + kind + "'s "
            + name, "unsat", List.of(a, b,
            "(assert (not (= " + shiftPair(at32, lane) + " "
                + narrowWrap("(" + op + " a b)", lane) + ")))"));
      }
      String min = bvLiteral(lane.mostNegative(), w);
      String max = bvLiteral(lane.mostPositive(), w);
      check(out, "narrow_wrap: the domain admits both ends of the " + kind
          + ", whose sum wraps to -1", "sat", List.of(a, b,
          "(assert (= a " + min + "))", "(assert (= b " + max + "))",
          "(assert (= (bvadd a b) " + bvLiteral(-1, w) + "))"));
      String s = bvLiteral(32 - w, 32);
      check(out, "narrow_wrap: with the logical shift it is not", "sat", List.of(wide,
          "(assert (not (= (bvlshr (bvshl x " + s + ") " + s + ") " + narrowWrap("x", lane)
              + ")))"));
      check(out, "narrow_wrap: with the shift one less it is not", "sat", List.of(wide,
          "(assert (not (= " + shiftPair("x", lane, 32 - w - 1) + " " + narrowWrap("x", lane)
              + ")))"));
      check(out, "narrow_wrap: with the shift one more it is not", "sat", List.of(wide,
          "(assert (not (= " + shiftPair("x", lane, 32 - w + 1) + " " + narrowWrap("x", lane)
              + ")))"));
    }
    return out.toString();
  }

  /**
   * {@code narrow_overflow.smt2}: the native arm's checked add and subtract, whose mask is
   * {@code add != sadd} ({@code sub != ssub}) on the narrow lane, holds at exactly the lanes
   * where the exact result leaves the type's range. The exact result is formed one bit wider,
   * where the sum of two narrow values cannot wind. Each operator's saturating form is the
   * Vector API's {@code SADD}/{@code SSUB}, modelled by its own overflow predicate; the claim
   * is the comparison of the wrapping and saturating results against the exact one, not the
   * modellings against each other. Beside them the domain's ends, and the neighbours: the
   * result's sign alone is not the test, nor is the other operator's saturating form.
   */
  static String narrowOverflow() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The checked narrow add and subtract on the native arm (VARKA-330, VARKA-329.md 4.3).
        ; The lanes are the narrow ones, so the wrapping operator is the lane's own add or
        ; subtract, and the mask is one more lanewise - the saturating form - and a compare:
        ;
        ;   overflow(a, b) = bvadd(a, b) != sadd(a, b)          subtract with ssub
        ;
        ; The claims: that mask is set at exactly the lanes where the exact result leaves
        ; [-2^(w-1), 2^(w-1) - 1]. The exact result is formed in a lane one bit wider, where
        ; the sum of two w-bit values cannot wrap, so "outside the range" there is a comparison
        ; and not another way of stating the overflow. The saturating forms are SMT-LIB's
        ; signed-overflow predicate and the saturated constant, which is what the Vector API's
        ; SADD and SSUB compute. Beside the claims, the domain's ends, and the neighbours: the
        ; result's sign alone is not the mask, and the other operator's saturating form is not
        ; the mask either.
        ;
        ; The logic is ALL because the file reasons over bit-vectors.
        """);
    out.append(rendered("ALL"));
    for (Lane lane : NARROW_LANES) {
      int w = lane.bits;
      String kind = lane.laneType.name().toLowerCase(Locale.ROOT);
      List<String> decl = List.of("(declare-const a (_ BitVec " + w + "))",
          "(declare-const b (_ BitVec " + w + "))");
      String min = bvLiteral(lane.mostNegative(), w);
      String max = bvLiteral(lane.mostPositive(), w);
      String zero = bvLiteral(0, w);
      String exactSum = "(bvadd " + signExtendWider("a", w) + " " + signExtendWider("b", w) + ")";
      String exactDiff = "(bvsub " + signExtendWider("a", w) + " " + signExtendWider("b", w) + ")";
      String outsideSum = "(or (bvslt " + exactSum + " " + bvLiteral(lane.mostNegative(), w + 1)
          + ") (bvsgt " + exactSum + " " + bvLiteral(lane.mostPositive(), w + 1) + "))";
      String outsideDiff = "(or (bvslt " + exactDiff + " " + bvLiteral(lane.mostNegative(), w + 1)
          + ") (bvsgt " + exactDiff + " " + bvLiteral(lane.mostPositive(), w + 1) + "))";
      String sadd = "(ite (bvsaddo a b) (ite (bvslt a " + zero + ") " + min + " " + max
          + ") (bvadd a b))";
      String ssub = "(ite (bvssubo a b) (ite (bvslt a " + zero + ") " + min + " " + max
          + ") (bvsub a b))";
      check(out, "narrow_overflow: the " + kind + " sum's mask is the exact sum leaving the range",
          "unsat", append(decl, "(assert (not (= (not (= (bvadd a b) " + sadd + ")) "
              + outsideSum + ")))"));
      check(out, "narrow_overflow: the " + kind
          + " difference's mask is the exact one leaving the range", "unsat",
          append(decl, "(assert (not (= (not (= (bvsub a b) " + ssub + ")) " + outsideDiff
              + ")))"));
      check(out, "narrow_overflow: the domain admits the top of the " + kind + " range, where "
          + "nothing overflows", "sat", append(decl, List.of("(assert (= a " + max + "))",
              "(assert (= b " + zero + "))",
              "(assert (= (bvadd a b) " + sadd + "))")));
      check(out, "narrow_overflow: one past the top is outside the range, and the " + kind
          + "'s add and sadd differ", "sat", append(decl, List.of("(assert (= a " + max + "))",
              "(assert (= b " + bvLiteral(1, w) + "))",
              "(assert (not (= (bvadd a b) " + sadd + ")))",
              "(assert (= " + exactSum + " " + bvLiteral(lane.mostPositive() + 1, w + 1) + "))")));
      check(out, "narrow_overflow: one past the bottom of the " + kind + " range is outside it, "
          + "and subtract differs", "sat", append(decl, List.of(
              "(assert (= a " + min + "))", "(assert (= b " + bvLiteral(1, w) + "))",
              "(assert (not (= (bvsub a b) " + ssub + ")))")));
      check(out, "narrow_overflow: the result's sign alone is not the sum's mask", "sat",
          append(decl, List.of("(assert (not " + outsideSum + "))",
              "(assert (bvslt (bvadd a b) " + zero + "))")));
      check(out, "narrow_overflow: the other operator's saturating form is not the sum's mask",
          "sat", append(decl, List.of("(assert (not " + outsideSum + "))",
              "(assert (not (= (bvadd a b) " + ssub + ")))")));
    }
    return out.toString();
  }

  /**
   * {@code narrow_range.smt2}: the widened arm's checked forms compare the exact int result
   * against the type's range - {@code LT} the minimum and {@code GT} the maximum - and that
   * mask holds at exactly the ints that are not values of the narrow type. The ends are tested
   * inclusive, so the claims cover a mask made non-strict at either end being wrong there. A
   * checked narrow multiply is formed in the int lane on either arm
   * ({@code VarkaVectorIR.holdsCheckedNarrowMultiply}), so these checks are its proof too; the
   * lane width is read from {@link Lane#INT}, the lane the emitter forms it in.
   *
   * <p>A {@code NULL} range guard disposes of the same mask by clearing it from its word,
   * {@code word & ~mask.toLong()}, and the file's last claim is that this keeps a lane valid
   * exactly when it was valid and in range, over {@link #NULL_GUARD_LANES} lanes at once, with
   * the word's bits past them unchanged. Its neighbours clear by the mask itself, and drop the
   * word.
   */
  static String narrowRange() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The checked narrow forms on the widened arm (VARKA-330, VARKA-329.md 4.2). The int
        ; lane's result of two narrow operands is exact, so the node's own check is two compares
        ; against the type's range, whose ends are the lane's (Lane.mostNegative and
        ; mostPositive, read here):
        ;
        ;   outside(r) = r < min || r > max
        ;
        ; The claim: outside(r) holds at exactly the ints that are not values of the narrow
        ; type, which is r differing from the sign extension of its low w bits. The sat checks
        ; place both ends inside and one past each end outside, and the neighbours narrow the
        ; compares to <= and >=, which is wrong at the end each names. A checked narrow multiply
        ; is formed in the int lane on either arm (VarkaVectorIR.holdsCheckedNarrowMultiply), so
        ; these checks cover it too.
        ;
        ; A NULL range guard clears the same mask from its validity word instead of declining
        ; the batch, the word being one bit a lane:
        ;
        ;   word' = word & ~toLong(outside(r))
        ;
        ; The last claims: a lane is valid in word' exactly when it was valid in word and in
        ; range, so an in-range lane stays valid, an out-of-range one becomes null and a null
        ; stays null, and the bits past the lanes are the word's. The neighbours clear by the
        ; mask itself, and drop the word.
        ;
        ; The logic is ALL because the file reasons over bit-vectors.
        """);
    out.append(rendered("ALL"));
    for (Lane lane : NARROW_LANES) {
      int bits = Lane.INT.bits;
      String kind = lane.laneType.name().toLowerCase(Locale.ROOT);
      String decl = "(declare-const r (_ BitVec " + bits + "))";
      String lo = bvLiteral(lane.mostNegative(), bits);
      String hi = bvLiteral(lane.mostPositive(), bits);
      String outside = outside("r", lane.mostNegative(), lane.mostPositive(), bits);
      check(out, "narrow_range: the widened arm's mask is the ints that are not " + kind
          + " values", "unsat", List.of(decl,
          "(assert (not (= " + outside + " (not (= r " + narrowWrap("r", lane) + ")))))"));
      check(out, "narrow_range: the " + kind + " range admits both of its ends", "sat", List.of(
          "(declare-const bottom (_ BitVec " + bits + "))",
          "(declare-const top (_ BitVec " + bits + "))",
          "(assert (= bottom " + lo + "))", "(assert (= top " + hi + "))",
          "(assert (not " + outside("bottom", lane.mostNegative(), lane.mostPositive(), bits)
              + "))",
          "(assert (not " + outside("top", lane.mostNegative(), lane.mostPositive(), bits)
              + "))"));
      check(out, "narrow_range: one past each end of the " + kind + " is outside it", "sat",
          List.of("(declare-const below (_ BitVec " + bits + "))",
              "(declare-const above (_ BitVec " + bits + "))",
              "(assert (= below " + bvLiteral(lane.mostNegative() - 1, bits) + "))",
              "(assert (= above " + bvLiteral(lane.mostPositive() + 1, bits) + "))",
              "(assert " + outside("below", lane.mostNegative(), lane.mostPositive(), bits)
                  + ")",
              "(assert " + outside("above", lane.mostNegative(), lane.mostPositive(), bits)
                  + ")"));
      check(out, "narrow_range: with <= at the bottom the mask is not the " + kind
          + "'s values", "sat", List.of(decl, "(assert (= r " + lo + "))",
          "(assert (not (= (or (bvsle r " + lo + ") (bvsgt r " + hi + ")) " + outside + ")))"));
      check(out, "narrow_range: with >= at the top the mask is not the " + kind + "'s values",
          "sat", List.of(decl, "(assert (= r " + hi + "))",
              "(assert (not (= (or (bvslt r " + lo + ") (bvsge r " + hi + ")) " + outside
                  + ")))"));
    }
    for (Lane lane : NARROW_LANES) {
      String kind = lane.laneType.name().toLowerCase(Locale.ROOT);
      List<String> decl = nullGuardDecl(lane);
      check(out, "narrow_range: a NULL guard keeps a lane valid exactly when it was valid and in "
          + "the " + kind + " range", "unsat", append(decl, "(assert (not "
              + nullGuardClaim(lane, "(bvand word (bvnot mask))") + "))"));
      check(out, "narrow_range: clearing by the mask itself is not the " + kind + " NULL guard",
          "sat", append(decl, "(assert (not " + nullGuardClaim(lane, "(bvand word mask)") + "))"));
      check(out, "narrow_range: the " + kind + " NULL guard without the word revives a null lane",
          "sat", append(decl, "(assert (not " + nullGuardClaim(lane, "(bvnot mask)") + "))"));
    }
    return out.toString();
  }

  /**
   * The lanes {@code narrow_range.smt2}'s {@code NULL} guard claims cover at once: the int lanes
   * of a 512-bit vector, the widest species, whose mask's {@code toLong} fills the low 16 bits
   * of the word. The claim is per lane, so a narrower species is a prefix of it.
   */
  static final int NULL_GUARD_LANES = 16;

  /**
   * One lane value {@code r<i>} a lane, the word, and the mask {@code toLong} makes of the lanes
   * outside {@code lane}'s range: bit {@code i} set where {@code r<i>} is outside, the bits past
   * the lanes zero.
   */
  private static List<String> nullGuardDecl(Lane lane) {
    int bits = Lane.INT.bits;
    var decl = new ArrayList<String>();
    decl.add("(declare-const word (_ BitVec 64))");
    var mask = new StringBuilder();
    for (int i = NULL_GUARD_LANES - 1; i >= 0; i--) {
      decl.add("(declare-const r" + i + " (_ BitVec " + bits + "))");
      mask.append(" (ite ").append(outside("r" + i, lane.mostNegative(), lane.mostPositive(), bits))
          .append(" #b1 #b0)");
    }
    decl.add("(define-fun mask () (_ BitVec 64) ((_ zero_extend " + (64 - NULL_GUARD_LANES)
        + ") (concat" + mask + ")))");
    return decl;
  }

  /**
   * The claim that {@code narrowed} keeps lane {@code i} valid exactly where {@code word} has it
   * valid and {@code r<i>} is inside {@code lane}'s range, and leaves the bits past the lanes as
   * {@code word} has them.
   */
  private static String nullGuardClaim(Lane lane, String narrowed) {
    var claim = new StringBuilder("(and");
    for (int i = 0; i < NULL_GUARD_LANES; i++) {
      String bit = "((_ extract " + i + " " + i + ") ";
      claim.append(" (= (= ").append(bit).append(narrowed).append(") #b1) (and (= ").append(bit)
          .append("word) #b1) (not ")
          .append(outside("r" + i, lane.mostNegative(), lane.mostPositive(), Lane.INT.bits))
          .append(")))");
    }
    claim.append(" (= ((_ extract 63 ").append(NULL_GUARD_LANES).append(") ").append(narrowed)
        .append(") ((_ extract 63 ").append(NULL_GUARD_LANES).append(") word)))");
    return claim.toString();
  }

  /**
   * {@code narrow_deferred.smt2}: under LEGACY an interior ring node of the widened arm keeps
   * its int result unreduced, and the first consumer that is not a ring operation reduces it.
   * The claim is the law that makes the deferral sound - reducing after an add, a subtract or a
   * multiply equals reducing the operands first and then reducing, for every int pair - so a
   * chain of ring operations and a chain that reduces at every node answer the same. The sat
   * checks show the edges are ints the law covers, and that a compare is not in the ring: its
   * result on an unreduced operand differs from its result on the reduced one.
   */
  static String narrowDeferred() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The deferred wrap of the widened arm under LEGACY (VARKA-330, VARKA-329.md 4.2). Only
        ; +, -, *, the bitwise operations and a left shift commute with reduction mod 2^w; every
        ; other consumer - a compare, abs, a greatest, a widening, an IfElse's condition, the
        ; store - reads the reduced value, so the wrap happens there and not at the interior
        ; node:
        ;
        ;   wrap(u op v) = wrap(wrap(u) op wrap(v))        op in { +, -, * }
        ;
        ; for every int u and v: the wrap is a ring homomorphism onto the w-bit values, and the
        ; int lane's own wraparound is a second reduction that does not change the residue.
        ; Induction over a tree of ring nodes follows. The sat checks place the law at the int
        ; lane's ends and show the set is not larger: a compare of an unreduced value differs
        ; from the compare of its reduction, which is why it forces the wrap.
        ;
        ; The logic is ALL because the file reasons over bit-vectors.
        """);
    out.append(rendered("ALL"));
    for (Lane lane : NARROW_LANES) {
      int w = lane.bits;
      String kind = lane.laneType.name().toLowerCase(Locale.ROOT);
      List<String> decl = List.of("(declare-const u (_ BitVec 32))",
          "(declare-const v (_ BitVec 32))");
      for (String op : List.of("bvadd", "bvsub", "bvmul")) {
        String name = op.substring(2).toLowerCase(Locale.ROOT);
        String deferred = "(" + op + " u v)";
        String perNode = "(" + op + " " + narrowWrap("u", lane) + " " + narrowWrap("v", lane)
            + ")";
        check(out, "narrow_deferred: reducing after the int lane's " + name + " is reducing "
            + "the operands first, at " + w + " bits", "unsat", append(decl,
            "(assert (not (= " + narrowWrap(deferred, lane) + " " + narrowWrap(perNode, lane)
                + ")))"));
      }
      String max = bvLiteral(lane.mostPositive(), 32);
      String min = bvLiteral(lane.mostNegative(), 32);
      String intMin = bvLiteral(Integer.MIN_VALUE, 32);
      String intMax = bvLiteral(Integer.MAX_VALUE, 32);
      check(out, "narrow_deferred: the law holds at the int lane's two ends, over " + kind
          + " bounds", "sat", append(decl, List.of("(assert (= u " + intMin + "))",
              "(assert (= v " + intMax + "))",
              "(assert (= " + narrowWrap("(bvmul u v)", lane) + " "
                  + narrowWrap("(bvmul " + narrowWrap("u", lane) + " " + narrowWrap("v", lane)
                      + ")", lane) + "))")));
      check(out, "narrow_deferred: a compare is not in the ring, and an unreduced value "
          + "changes it", "sat", append(decl, List.of("(declare-const c (_ BitVec 32))",
              "(assert (= u " + bvLiteral(lane.mostPositive() + 1, 32) + "))",
              "(assert (= c " + bvLiteral(0, 32) + "))",
              "(assert (not (= (bvslt u c) (bvslt " + narrowWrap("u", lane) + " c))))")));
      check(out, "narrow_deferred: the " + kind + " bounds are ints the law leaves alone", "sat",
          List.of("(declare-const s (_ BitVec 32))", "(declare-const t (_ BitVec 32))",
              "(assert (= s " + min + "))", "(assert (= t " + max + "))",
              "(assert (= " + narrowWrap("s", lane) + " s))",
              "(assert (= " + narrowWrap("t", lane) + " t))"));
    }
    return out.toString();
  }

  /** {@code x}'s low {@code from} bits, sign-extended to a {@code to}-bit lane. */
  private static String signExtendLow(String x, int from, int to) {
    return "((_ sign_extend " + (to - from) + ") ((_ extract " + (from - 1) + " 0) " + x + "))";
  }

  /** The narrow wrap: {@code x}'s low {@code w} bits, sign-extended to the int lane. */
  private static String narrowWrap(String x, Lane lane) {
    return signExtendLow(x, lane.bits, 32);
  }

  /** {@code x} as a w-bit operand widened into the lane one bit wider than {@code w}. */
  private static String signExtendWider(String x, int w) {
    return "((_ sign_extend 1) " + x + ")";
  }

  /** The emitter's wrap of {@code x}: left by {@code s}, then arithmetically right by it. */
  private static String shiftPair(String x, Lane lane, int shift) {
    String s = bvLiteral(shift, 32);
    return "(bvashr (bvshl " + x + " " + s + ") " + s + ")";
  }

  /** {@link #shiftPair} with the lane's own count, {@code 32 - w}. */
  private static String shiftPair(String x, Lane lane) {
    return shiftPair(x, lane, 32 - lane.bits);
  }

  /** {@code x} outside {@code [min, max]}, two {@code bits}-wide compares. */
  private static String outside(String x, long min, long max, int bits) {
    String lo = bvLiteral(min, bits);
    String hi = bvLiteral(max, bits);
    return "(or (bvslt " + x + " " + lo + ") (bvsgt " + x + " " + hi + "))";
  }

  /**
   * A {@code bits}-wide SMT-LIB bit-vector literal holding {@code v} in two's complement:
   * hexadecimal where the width is a whole number of hex digits, binary otherwise, as the
   * exact results one bit wider than a lane need.
   */
  private static String bvLiteral(long v, int bits) {
    long masked = v & ((1L << bits) - 1);
    if (bits % 4 == 0) {
      return String.format("#x%0" + (bits / 4) + "x", masked);
    }
    String binary = Long.toBinaryString(masked);
    return "#b" + "0".repeat(bits - binary.length()) + binary;
  }

  /** The declarations followed by one assertion. */
  private static List<String> append(List<String> decl, String assertion) {
    return append(decl, List.of(assertion));
  }

  /** The declarations followed by the assertions. */
  private static List<String> append(List<String> decl, List<String> assertions) {
    var all = new ArrayList<>(decl);
    all.addAll(assertions);
    return all;
  }

  // -----------------------------------------------------------------------------------------
  // The decimal leaf's digit check (VARKA-347).
  // -----------------------------------------------------------------------------------------

  /**
   * {@code decimal_slot.smt2}: the digit check of a {@code DecimalColumn}'s load, one unsigned
   * compare, condemns exactly the low words outside {@code [-B, B]} with {@code B = 10^p - 1},
   * the leaf's {@link VarkaVectorIR.DecimalColumn#bound}, at every precision the leaf admits; a
   * comparand one off either way is refuted. The identity needs no more of the bound than that
   * it is a non-negative long, which the last check shows over a symbolic bound.
   */
  static String decimalSlot() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; The decimal leaf's digit check (VarkaVectorWalk.emitDecimalLoad, VARKA-347). Per lane,
        ; lo the slot's low word as a long and B = 10^p - 1 the precision's largest unscaled value
        ; (DecimalColumn.bound), the walk condemns the lane where
        ;
        ;   Long.compareUnsigned(lo + B, 2 * B) > 0        LongVector.lanewise ADD, compare UGT
        ;
        ; in place of the two signed compares lo > B || lo < -B. Over 64-bit vectors, whose bvadd
        ; and bvugt are Java's long + and the unsigned compare exactly: the two agree for every
        ; long at every precision from 1 to MAX_LONG_PRECISION, and a comparand one off either way
        ; disagrees somewhere. A lo inside [-B, B] moves to [0, 2B] without wrapping; one above B
        ; lands past 2B, wrapping or not; one below -B wraps to at least 2^63 + B, past 2B. So the
        ; identity holds for any bound from 0 to 2^63 - 1, which the last check states over a
        ; symbolic B. The file declares ALL so that the prelude, which it does not use, can be
        ; inserted.
        """);
    out.append(rendered("ALL"));
    out.append("""

        (define-fun dec.signed ((lo (_ BitVec 64)) (b (_ BitVec 64))) Bool
          (or (bvsgt lo b) (bvslt lo (bvneg b))))
        (define-fun dec.unsigned ((lo (_ BitVec 64)) (b (_ BitVec 64)) (c (_ BitVec 64))) Bool
          (bvugt (bvadd lo b) c))
        """);
    String lo = "(declare-const lo (_ BitVec 64))";
    for (int p = 1; p <= VarkaVectorIR.DecimalColumn.MAX_LONG_PRECISION; p++) {
      long b = new VarkaVectorIR.DecimalColumn(0, VarkaVectorIR.LaneType.LONG, p).bound();
      for (long off : new long[] {0, -1, 1}) {
        String what = off == 0
            ? "precision " + p + ": (lo + " + b + ") UGT " + 2 * b + " is lo outside [-" + b
                + ", " + b + "] for every long"
            : "and with the comparand " + (2 * b + off) + " it is not";
        check(out, what, off == 0 ? "unsat" : "sat", List.of(lo,
            "(assert (not (= (dec.unsigned lo " + bv64(b) + " " + bv64(2 * b + off) + ") "
                + "(dec.signed lo " + bv64(b) + "))))"));
      }
    }
    check(out, "and for every bound B from 0 to 2^63 - 1, with 2B wrapped as the code's long "
        + "multiply wraps it", "unsat", List.of(lo, "(declare-const b (_ BitVec 64))",
            "(assert (bvsge b #x0000000000000000))",
            "(assert (not (= (dec.unsigned lo b (bvmul b #x0000000000000002)) "
                + "(dec.signed lo b))))"));
    return out.toString();
  }

  /** A long as a 64-bit SMT-LIB bit-vector literal. */
  private static String bv64(long v) {
    return String.format("#x%016x", v);
  }

  // -----------------------------------------------------------------------------------------
  // Shared.
  // -----------------------------------------------------------------------------------------

  static void check(StringBuilder out, String what, String expect, List<String> body) {
    out.append("\n(echo \"").append(what).append(": expect ").append(expect).append("\")\n");
    out.append("(push 1)\n");
    for (String line : body) {
      out.append(line).append('\n');
    }
    out.append("(check-sat)\n(pop 1)\n");
  }

  /** An SMT-LIB integer literal: a negative one is {@code (- n)}. */
  static String lit(long v) {
    return v >= 0 ? Long.toString(v) : "(- " + Long.toString(v).substring(1) + ")";
  }

  private static String lit(BigInteger v) {
    return v.signum() >= 0 ? v.toString() : "(- " + v.negate() + ")";
  }
}
