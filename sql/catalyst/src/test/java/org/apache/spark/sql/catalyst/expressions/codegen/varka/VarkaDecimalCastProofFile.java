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

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.apache.spark.sql.types.Decimal;

/**
 * {@code sql/varka/proofs/decimal_casts.smt2}: the integer rules of the 64-bit decimal casts
 * ({@code VarkaDecimalCastCompiler}, `VARKA-350.md` 3 item 4), over integers under
 * {@code java.smt2}'s operators, rendered from the constants the compiler reads - Spark's
 * {@code Decimal.POW_10} and {@link VarkaVectorIR.ConstDivide#EXACT_DIVIDEND_BOUND} - and from
 * {@link #divisions()}, the table of every division by a power of ten the compiler builds, which
 * {@code VarkaDecimalCastTableSuite} holds to the IR the compiler builds. It proves:
 *
 * <ul>
 *   <li>in {@code decimal_divide.smt2} ({@link #renderDivide()}), each divisor of the table
 *       {@code long_divide.smt2} does not state, both forms
 *       {@code VarkaDivisionLowering.emitConstDivide} takes at the long lane, exact for every
 *       dividend below {@code 2^52} ({@code VarkaDivisorProofSuite} reads {@link #DIVISORS});
 *   <li>per division, its dividend within the bound it states to the constant division, and its
 *       answer Spark's: Java's {@code /} for the truncation ({@code Decimal.toLong} and
 *       {@code Cast}'s integral casts), {@code changePrecision}'s {@code HALF_UP} for the rounding;
 *       where the magnitude form runs, the logical shift and the division by {@code 5^k} with the
 *       sign put back, and where its shifted magnitude can reach {@code 2^52}, the guard needed
 *       and one wider wrong;
 *   <li>the rescale down's precision check dead exactly where {@code p - k < P}, and the rescale
 *       up's input guard the target's precision check with no wrap under it;
 *   <li>the other order of the rescale up, the multiply first and the check after (Trino's),
 *       wrong at Trino's value.
 * </ul>
 *
 * <p>The casts to {@code TINYINT}, {@code SMALLINT} and {@code INT} and the casts into a decimal
 * from an integral type or {@code BOOLEAN} decline in this part; their claims join this file with
 * the parts that build them ({@code VARKA-350.md} 10.4).
 */
public final class VarkaDecimalCastProofFile {

  private VarkaDecimalCastProofFile() {
  }

  /** The widest precision on the long lane. */
  private static final int MAX = VarkaVectorIR.DecimalColumn.MAX_LONG_PRECISION;

  /** The exclusive bound on a long-lane constant division's dividend magnitude. */
  private static final long BOUND = VarkaVectorIR.ConstDivide.EXACT_DIVIDEND_BOUND;

  /** No guard. */
  public static final long NO_GUARD = -1L;

  /**
   * One division of an unscaled value {@code |u| <= 10^p - 1} by {@code 10^k} as the compiler
   * builds it: truncating, or {@code HALF_UP} ({@code halfUp}); by one {@code ConstDivide} of the
   * signed value by {@code divisor = 10^k}, or, where {@code magnitude}, of the magnitude (plus
   * the half) shifted right by {@code k}, by {@code divisor = 5^k}, behind {@code guard}, the
   * magnitude's inclusive upper bound, where it is not {@link #NO_GUARD}. {@code dividendBound}
   * is the exclusive bound stated to the constant division.
   */
  public record Division(int p, int k, boolean halfUp, boolean magnitude, long divisor,
      long dividendBound, long guard) {
  }

  /** {@code 5^k}. */
  private static long fivePow(int k) {
    long r = 1;
    for (int i = 0; i < k; i++) {
      r *= 5;
    }
    return r;
  }

  /** Every division the compiler builds, for every {@code 1 <= k <= p <= 18}. */
  public static List<Division> divisions() {
    long[] pow = Decimal.POW_10();
    var out = new ArrayList<Division>();
    for (int p = 1; p <= MAX; p++) {
      for (int k = 1; k <= p; k++) {
        for (boolean halfUp : new boolean[] {false, true}) {
          long most = pow[p] - 1 + (halfUp ? pow[k] / 2 : 0);
          if (most < BOUND) {
            out.add(new Division(p, k, halfUp, false, pow[k], most + 1, NO_GUARD));
            continue;
          }
          long shifted = most >>> k;
          boolean guarded = shifted >= BOUND;
          out.add(new Division(p, k, halfUp, true, fivePow(k), guarded ? BOUND : shifted + 1,
              guarded ? (BOUND << k) - 1 : NO_GUARD));
        }
      }
    }
    long guards = out.stream().filter(d -> d.guard() != NO_GUARD).map(d -> d.p() * 100 + d.k())
        .distinct().count();
    if (guards != 12) {
      throw new IllegalStateException("the division guard fires at " + guards + " (p, k), not "
          + "the twelve of VARKA-350.md item 0.15; restate the proof's header");
    }
    return out;
  }

  /** The divisors of {@link #divisions()} that {@code long_divide.smt2} does not state. */
  static final List<Long> DIVISORS = divisors();

  private static List<Long> divisors() {
    var out = new TreeSet<Long>();
    for (Division d : divisions()) {
      if (!VarkaProofFiles.LONG_DIVISORS.contains(d.divisor())) {
        out.add(d.divisor());
      }
    }
    return List.copyOf(out);
  }

  private static void check(StringBuilder out, String what, String expect, String... body) {
    VarkaProofFiles.check(out, "decimal_casts: " + what, expect, List.of(body));
  }

  private static final String U = "(declare-const u Int)";

  private static String in(long most) {
    return "(assert (dec.in u " + most + "))";
  }

  static String render() {
    long[] pow = Decimal.POW_10();
    var out = new StringBuilder(VarkaProofFiles.LICENSE);
    out.append("""
        ; The 64-bit decimal casts (VarkaDecimalCastCompiler, VARKA-350). A DECIMAL(p, s) value is
        ; an unscaled long u with |u| <= 10^p - 1 (decimal_slot.smt2). Its divisions by 10^k:
        ;
        ;   truncation, 10^p - 1 < 2^52:      ConstDivide(u, 10^k)
        ;   HALF_UP, 10^p - 1 + h < 2^52:     ConstDivide(u < 0 ? u - h : u + h, 10^k), h = 10^k / 2
        ;   above, m = |u| or |u| + h:        q = ConstDivide(m >>> k, 5^k), u < 0 ? -q : q
        ;
        ; where m >>> k can still reach 2^52 - the twelve (p, k) at precision 16 to 18 and k up to
        ; 7 - behind GuardedRange(m, 0, 2^(52 + k) - 1, FAIL), whose failing batch the row engine
        ; computes. Spark's truncation is Java's / (Decimal.toLong, Cast's integral casts), its
        ; HALF_UP changePrecision's long path: q = u / 10^k, r = u % 10^k, q + sign(r) where
        ; 2|r| >= 10^k. Into DECIMAL(P, S), with k the rise or fall in scale:
        ;
        ;   rescale up:     GuardedRange(u, +-(10^(P - k) - 1)) * 10^k    guard-the-inputs
        ;   rescale down:   GuardedRange(HALF_UP(u, k), +-(10^P - 1))     dead where p - k < P
        ;
        ; The constant divisions are exact below 2^52 in both lowered forms: long_divide.smt2 for
        ; its divisors, decimal_divide.smt2 for the others. The sat checks are each guard needed
        ; and one wider wrong, the bounds reached, and the check after the multiply wrong.
        """);
    out.append(VarkaProofFiles.rendered("QF_NIA"));
    out.append("""

        (define-fun dec.in ((u Int) (b Int)) Bool (and (<= (- b) u) (<= u b)))
        (define-fun dec.abs ((u Int)) Int (ite (< u 0) (jlong.neg u) u))
        (define-fun dec.signed ((u Int) (q Int)) Int (ite (< u 0) (jlong.neg q) q))
        (define-fun spark.halfUp ((u Int) (d Int)) Int
          (let ((q (jlong.div u d)) (r (jlong.rem u d)))
            (ite (>= (* 2 (abs r)) d) (+ q (ite (< r 0) (- 1) 1)) q)))
        """);
    out.append("\n; Each division: its dividend under the bound it states, its answer Spark's.\n");
    for (Division d : divisions()) {
      division(out, d, pow);
    }
    neighbours(out, pow);
    rescales(out, pow);
    return out.toString();
  }

  /**
   * {@code decimal_divide.smt2}: for each of {@link #DIVISORS}, both forms of the long lane's
   * constant division exact below {@code 2^52}, as {@code long_divide.smt2} states them for its
   * own divisors. Its 2352 checks take Z3 83 s and cvc5 24 s on the laptop, so the linters' job
   * runs it under cvc5 ({@code CVC5_IN_LINT} in {@code dev/varka_prove.sh}).
   */
  static String renderDivide() {
    var out = new StringBuilder(VarkaProofFiles.LICENSE);
    out.append("""
        ; The long lane's constant division (VarkaVectorIR.ConstDivide) by the divisors of the
        ; 64-bit decimal casts (VarkaDecimalCastCompiler, VARKA-350) that long_divide.smt2 does
        ; not state: 10^k for k from 10 to 15, below 2^52 where a value of up to 15 digits is
        ; divided whole, and 5^k for k from 1 to 18 but 2, 7 and 9, the magnitude form's divisor
        ; after its shift by k. Both forms VarkaDivisionLowering.emitConstDivide takes at the long
        ; lane are Java's v / d for every dividend below ConstDivide.EXACT_DIVIDEND_BOUND = 2^52,
        ; stated as long_divide.smt2 states them, whose header describes the forms; the lemmas
        ; they rest on are its own: an integer below 2^53 rounds to itself, and the magic form's
        ; OR is disjoint below 2^52. decimal_casts.smt2 proves each division's dividend under that
        ; bound.
        """);
    out.append(VarkaProofFiles.rendered("QF_NIA"));
    for (long d : DIVISORS) {
      out.append("\n; d = ").append(d).append("\n");
      VarkaProofFiles.conversionExact(out, d, d);
      VarkaProofFiles.magicExact(out, d, d);
    }
    return out.toString();
  }

  /** {@code d}'s claims: the dividend bound, Spark's answer, and its guard needed. */
  private static void division(StringBuilder out, Division d, long[] pow) {
    String at = "p = " + d.p() + ", k = " + d.k() + ", " + (d.halfUp() ? "HALF_UP" : "truncation");
    long half = d.halfUp() ? pow[d.k()] / 2 : 0;
    String spark = d.halfUp() ? "(spark.halfUp u " + pow[d.k()] + ")"
        : "(jlong.div u " + pow[d.k()] + ")";
    String in = in(pow[d.p()] - 1);
    if (!d.magnitude()) {
      String dividend = d.halfUp()
          ? "(ite (< u 0) (jlong.sub u " + half + ") (jlong.add u " + half + "))" : "u";
      check(out, at + ": the dividend is below " + d.dividendBound() + " and its quotient by "
          + d.divisor() + " is Spark's", "unsat", U, in,
          "(define-fun v () Int " + dividend + ")",
          "(assert (not (and (< (abs v) " + d.dividendBound() + ") (= (jlong.div v "
              + d.divisor() + ") " + spark + "))))");
      return;
    }
    String m = d.halfUp() ? "(jlong.add (dec.abs u) " + half + ")" : "(dec.abs u)";
    String guard = d.guard() == NO_GUARD ? "" : "(assert (<= m " + d.guard() + "))";
    check(out, at + ": the magnitude shifted by " + d.k() + (d.guard() == NO_GUARD ? ""
        : " under the guard") + " is below " + d.dividendBound() + ", and its quotient by "
        + d.divisor() + " with the sign put back is Spark's", "unsat", U, in,
        "(define-fun m () Int " + m + ")", guard,
        "(define-fun t () Int (jlong.ushr m " + d.k() + "))",
        "(assert (not (and (<= 0 t) (< t " + d.dividendBound() + ") (= (dec.signed u (jlong.div t "
            + d.divisor() + ")) " + spark + "))))");
    if (d.guard() != NO_GUARD) {
      check(out, at + ": a value within the precision exceeds the guard, so it is needed", "sat",
          U, in, "(define-fun m () Int " + m + ")", "(assert (> m " + d.guard() + "))");
      check(out, at + ": one wider, the guard lets the shifted magnitude reach " + BOUND, "sat",
          U, in, "(define-fun m () Int " + m + ")", "(assert (<= m " + (d.guard() + 1) + "))",
          "(assert (>= (jlong.ushr m " + d.k() + ") " + BOUND + "))");
    }
  }

  /** The forms a constant changed by one, or a step left out, makes wrong. */
  private static void neighbours(StringBuilder out, long[] pow) {
    out.append("\n; The neighbours: a step left out, or a constant one off, is wrong.\n");
    check(out, "p = 18, k = 1: an arithmetic shift of the signed value floors, and is not the "
        + "truncation (-19 gives -2)", "sat", U, in(pow[MAX] - 1),
        "(assert (not (= (jlong.div (jlong.shr u 1) 5) (jlong.div u 10))))");
    for (int k = 1; k <= MAX; k++) {
      long half = pow[k] / 2;
      check(out, "p = 18, k = " + k + ": with the half one less the rounding is not HALF_UP",
          "sat", U, in(pow[MAX] - 1), "(assert (not (= (jlong.div (ite (< u 0) (- u " + (half - 1)
              + ") (+ u " + (half - 1) + ")) " + pow[k] + ") (spark.halfUp u " + pow[k] + "))))");
    }
  }

  /** The rescales' guards, and the rescale up's other order refuted. */
  private static void rescales(StringBuilder out, long[] pow) {
    out.append("\n; The rescale down: |HALF_UP(u, k)| <= 10^(p - k), reached, so the precision "
        + "check into P digits\n; is dead exactly where p - k < P.\n");
    for (int p = 1; p <= MAX; p++) {
      for (int k = 1; k <= p; k++) {
        String q = "(spark.halfUp u " + pow[k] + ")";
        check(out, "p = " + p + ", k = " + k + ": the rounded quotient is within "
            + pow[p - k], "unsat", U, in(pow[p] - 1),
            "(assert (not (dec.in " + q + " " + pow[p - k] + ")))");
        check(out, "p = " + p + ", k = " + k + ": and reaches it", "sat", U, in(pow[p] - 1),
            "(assert (= " + q + " " + pow[p - k] + "))");
      }
    }
    out.append("\n; The rescale up by k into P digits: |u| <= 10^(P - k) - 1 exactly where "
        + "u * 10^k has P digits,\n; with no wrap under the guard, and one wider admits a "
        + "product past them.\n");
    for (int target = 1; target <= MAX; target++) {
      for (int k = 1; k <= target; k++) {
        long guard = pow[target - k] - 1;
        long d = pow[k];
        String product = "(dec.in (* u " + d + ") " + (pow[target] - 1) + ")";
        check(out, "P = " + target + ", k = " + k + ": |u| <= " + guard + " is exactly u * " + d
            + " within " + target + " digits, with no wrap", "unsat", U,
            "(assert (jlong.in u))", "(assert (not (and (=> (dec.in u " + guard + ") (= "
                + "(jlong.mul u " + d + ") (* u " + d + "))) (= (dec.in u " + guard + ") "
                + product + "))))");
        check(out, "P = " + target + ", k = " + k + ": |u| <= " + (guard + 1) + " admits a "
            + "product past " + target + " digits", "sat", U, "(assert (dec.in u " + (guard + 1)
            + "))", "(assert (not " + product + "))");
      }
    }
    long trino = 184467440737095517L;
    long bound = pow[MAX] - 1;
    out.append("\n; The rescale up's other order, the check after the multiply (Trino's "
        + "shortToShortCast),\n; reads a wrapped product.\n");
    check(out, "the check after the multiply admits " + trino + " into DECIMAL(18, 2) as "
        + trino * 100, "sat", U, "(assert (= u " + trino + "))",
        "(assert (= (jlong.mul u 100) " + trino * 100 + "))",
        "(assert (dec.in (jlong.mul u 100) " + bound + "))",
        "(assert (not (dec.in (* u 100) " + bound + ")))");
  }
}
