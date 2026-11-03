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

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChronoLowering.ChronoDivide;
import org.apache.spark.sql.catalyst.util.DateTimeConstants;
import org.apache.spark.sql.types.Decimal;

/**
 * {@code refinement.smt2} (VARKA-245): the kernels as refinements of the specification under
 * {@code sql/varka/proofs/spec/} - {@code STANDARD.md}'s definitions, {@code DELTAS.md}'s patches
 * and {@code EXTENSIONS.md}'s definitions - in Spark mode and in standard mode, as the five queries
 * {@code m7/PLAN.md} 2.1 names. Rendered by {@link VarkaProofFiles#render}, whose suite holds the
 * committed file to this.
 *
 * <p>A kernel here is the arithmetic the emitter builds for a coverage row, written with
 * {@code java.smt2}'s operators; where a lowering proof holds an emitted form equal to an
 * operator - a calendar site's magic to its quotient ({@code chrono_divide.smt2}), the leap hash
 * to the Gregorian rule ({@code leap_hash.smt2}), the multiply-high and the long divisions to
 * {@code /} ({@code int_mulhi_divide.smt2}, {@code long_divide.smt2}) - the operator stands for the
 * form, and a check shows every dividend the refinement feeds that form lies where its proof
 * covers it.
 */
final class VarkaRefinementFile {

  private VarkaRefinementFile() {
  }

  // -----------------------------------------------------------------------------------------
  // The constants, from the code.
  // -----------------------------------------------------------------------------------------

  private static final long DAY = DateTimeConstants.NANOS_PER_DAY;
  private static final long MICROS_PER_DAY = DateTimeConstants.MICROS_PER_DAY;
  private static final long NANOS_PER_MICROS = DateTimeConstants.NANOS_PER_MICROS;
  private static final long SECOND = DateTimeConstants.NANOS_PER_SECOND;
  private static final long MINUTE = SECOND * DateTimeConstants.SECONDS_PER_MINUTE;
  private static final long HOUR = MINUTE * DateTimeConstants.MINUTES_PER_HOUR;
  private static final long SIXTY = DateTimeConstants.SECONDS_PER_MINUTE;

  /** The standard's dates, 0001-01-01 to 9999-12-31 (SQL-92 Table 10). */
  private static final long STD_MIN_DAYS = LocalDate.of(1, 1, 1).toEpochDay();
  private static final long STD_MAX_DAYS = LocalDate.of(9999, 12, 31).toEpochDay();

  /**
   * The standard's default interval leading precision, two digits (SQL-92 10.1 SR 5): a YEAR TO
   * MONTH interval of at most 99 years and 11 months, a DAY interval of at most 99 days, an HOUR
   * TO SECOND interval under 100 hours.
   */
  private static final long STD_MONTHS = 99 * 12 + 11;
  private static final long STD_DAYS = 99;
  private static final long STD_HOURS_MICROS = 100 * 3600 * DateTimeConstants.MICROS_PER_SECOND;

  /** The years {@code LocalDate.of} accepts, which Spark's {@code make_date} builds through. */
  private static final long LOCAL_DATE_MIN_YEAR = Year.MIN_VALUE;
  private static final long LOCAL_DATE_MAX_YEAR = Year.MAX_VALUE;

  /** The day count {@code VarkaChrono}'s era step is proven over. */
  private static final long DECOMPOSE_MIN = VarkaChrono.NARROW_MIN_DAYS;
  private static final long DECOMPOSE_MAX = VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS;

  /** The years of the days the era step is proven over: the prefix's years. */
  private static final long DECOMPOSE_MIN_YEAR = LocalDate.ofEpochDay(DECOMPOSE_MIN).getYear();
  private static final long DECOMPOSE_MAX_YEAR = LocalDate.ofEpochDay(DECOMPOSE_MAX).getYear();

  /** The years the inverse direction ({@code VarkaChrono.daysFromCivil}) is fed and proven for. */
  private static final long CIVIL_MIN_YEAR = 1 - VarkaChrono.YEAR_BIAS;
  private static final long CIVIL_MAX_YEAR =
      ChronoDivide.YEAR_OF_ERA_400.maxDividend - VarkaChrono.YEAR_BIAS;

  static String render() {
    var d = VarkaEmitOptions.DEFAULTS;
    if (!d.julianMap() || !d.neriSchneiderMonth()) {
      throw new IllegalStateException("refinement.smt2 restates the default prefix, the Julian map "
          + "with the Neri-Schneider month; the defaults have moved, and so must it");
    }
    if (STD_MIN_DAYS != VarkaChrono.CONTRACT_MIN_DAYS
        || STD_MAX_DAYS != VarkaChrono.CONTRACT_MAX_DAYS) {
      throw new IllegalStateException("the header says the column contract is the standard's "
          + "date range; it no longer is");
    }
    var out = new StringBuilder(VarkaProofFiles.LICENSE);
    out.append(HEADER);
    out.append(VarkaProofFiles.rendered("ALL"));
    out.append(SPEC);
    out.append(kernel());
    calendarRules(out);
    calendarLemmas(out);
    sites(out);
    integerArithmetic(out);
    calendarFields(out);
    addMonths(out);
    addDays(out);
    dayOfWeek(out);
    makeDate(out);
    intervals(out);
    times(out);
    decimals(out);
    return out.toString();
  }

  private static final String HEADER = """
      ; Each kernel below refines the specification under spec/ (VARKA-245): STANDARD.md's
      ; definition with DELTAS.md's patches in Spark mode, the definition alone in standard mode,
      ; EXTENSIONS.md's for the operations the standard does not define. Per lane a kernel's
      ; outcome is (decline, valid, data) and the specification's (raises, valid, value), the data
      ; under a clear bit free. For each operation and mode, after its precondition (each operand
      ; a value of its lane and its type's contract) and its bound (the guards' domain, an input
      ; bound), five checks (m7/PLAN.md 2.1):
      ;
      ;   1. sat    the precondition admits a valid lane the specification defines and the kernel
      ;             answers, so 2 to 5 are not vacuous (any one model shows it, so a check whose
      ;             model search is slow is pinned to one input);
      ;   2. unsat  the specification raises and the kernel does not decline;
      ;   3. unsat  neither, and the validity differs;
      ;   4. unsat  neither, the specification's lane valid, and the data differs from its value;
      ;   5. unsat  the kernel declines inside the bound where the specification does not raise.
      ;
      ; 2 to 4 are soundness, 5 guard completeness; a null lane is inside every bound, so 5 also
      ; says a null never declines. The bound is each guard's own, the range its lowering is
      ; proven over (the sites section), so 5 shows a kernel declines nothing else - no null, no
      ; value of another operand - and not that a guard is as wide as the values Spark answers:
      ; make_date's years, add_months's counts and time_from_micros's count decline by design
      ; past their bounds, where Spark may still answer. Where a refinement is false, the query
      ; that fails is written expect sat with the departure's own example as its witness, then
      ; again with the departure's inputs excluded, unsat: so the witness is the only way it
      ; fails. In standard
      ; mode those are the deltas DELTAS.md calls results; in Spark mode they are declines Spark
      ; does not need, a guard reading one operand's validity where Spark evaluates nothing.
      ;
      ; The kernels are the emitter's arithmetic in java.smt2's operators (VARKA-245.md 2.3 lists
      ; the IR each restates). Where a lowering proof holds an emitted form equal to an operator -
      ; a calendar site's magic to its quotient, the leap hash to the Gregorian rule, the
      ; multiply-high and the long divisions to / - the operator stands for the form, and the
      ; sites section shows every dividend fed to it is one its proof covers. The calendar is
      ; spec.days, one floor-division formula, tied to the standard's rules by the first checks
      ; (each valid date's successor one day on, 1970-01-01 day 0: by induction both ways it counts
      ; days) and pinned to java.time; then the decomposition's era tail, the 400-year period,
      ; their composition and the formula's injectivity, as lemmas whose instances the
      ; refinements assert. A date column holds the column contract, 0001-01-01 to 9999-12-31
      ; (VarkaRangeAnalysis.CONTRACT), which is the standard's date range and is not checked at
      ; ingestion; a check shows a date outside it feeds the era step past its proof.
      """;

  /** The specification: the calendar, and the two arithmetic notions the definitions use. */
  private static final String SPEC = """

      ; The specification. A date by C1's rules: the leap year, the month lengths, and spec.days,
      ; Hinnant's days_from_civil over SMT-LIB's flooring div, one formula for every year.
      (define-fun spec.leap ((y Int)) Bool
        (and (= (mod y 4) 0) (or (not (= (mod y 100) 0)) (= (mod y 400) 0))))
      (define-fun spec.mlen ((y Int) (m Int)) Int
        (ite (= m 2) (ite (spec.leap y) 29 28)
             (ite (or (= m 4) (= m 6) (= m 9) (= m 11)) 30 31)))
      (define-fun spec.date ((y Int) (m Int) (d Int)) Bool
        (and (<= 1 m) (<= m 12) (<= 1 d) (<= d (spec.mlen y m))))
      (define-fun spec.days ((y Int) (m Int) (d Int)) Int
        (let ((yy (ite (<= m 2) (- y 1) y)) (mm (ite (<= m 2) (+ m 9) (- m 3))))
          (+ (* 365 yy) (div yy 4) (- (div yy 100)) (div yy 400) (div (+ (* 153 mm) 2) 5)
             d (- 1) (- 719468))))
      ; quot(a, b) for b > 0, as STANDARD.md 2 defines it and not through the prelude's / : the
      ; remainder below b in magnitude, and zero or of the dividend's sign.
      (define-fun spec.quot ((a Int) (b Int) (q Int)) Bool
        (let ((r (- a (* b q))))
          (and (< r b) (< (- b) r) (or (= r 0) (and (> r 0) (> a 0)) (and (< r 0) (< a 0))))))
      ; overflow_wraps and day_count_wraps (DELTAS.md 1.1, 1.5): the exact result reduced into the
      ; lane modulo 2^32, stated as a congruence rather than through the prelude's wrap.
      (define-fun spec.wraps ((v Int) (e Int)) Bool
        (and (jint.in v) (= (mod (- v e) 4294967296) 0)))
      """;

  /** The kernels' calendar, every magic its quotient: the default prefix and the inverse. */
  private static String kernel() {
    long mnk = 1L << VarkaChrono.MONTH_NUM_K;
    return """

        ; The kernel's calendar (VarkaChrono.narrowedJulian and fields, the default prefix; and
        ; daysFromCivil and emitAddMonths), each magic and carry written as its quotient, which
        ; chrono_divide.smt2 proves it is over the dividends the sites section bounds; a shift right
        ; by k of a non-negative value is its quotient by 2^k and a mask of its low k bits the
        ; remainder.
        (define-fun k.era ((n Int)) Int (div (+ n %1$d) %2$d))
        (define-fun k.doe ((n Int)) Int (mod (+ n %1$d) %2$d))
        (define-fun t.quad ((e Int)) Int (+ (* 4 e) %3$d))
        (define-fun t.c ((e Int)) Int (div (t.quad e) %2$d))
        (define-fun t.jul ((e Int)) Int (+ (t.quad e) (* 4 (t.c e))))
        (define-fun t.yoe ((e Int)) Int (div (t.jul e) %4$d))
        (define-fun t.doy ((e Int)) Int (div (mod (t.jul e) %4$d) 4))
        (define-fun t.mn ((e Int)) Int (+ (* %5$d (t.doy e)) %6$d))
        (define-fun t.mi3 ((e Int)) Int (div (t.mn e) %7$d))
        (define-fun t.dom ((e Int)) Int (+ (div (mod (t.mn e) %7$d) %8$d) 1))
        (define-fun t.month ((e Int)) Int (ite (< (t.mi3 e) %9$d) (t.mi3 e) (- (t.mi3 e) 12)))
        (define-fun t.year ((e Int)) Int (+ (t.yoe e) (ite (>= (t.doy e) %10$d) 1 0)))
        (define-fun k.year ((n Int)) Int (+ (* 400 (- (k.era n) %11$d)) (t.year (k.doe n))))
        (define-fun k.month ((n Int)) Int (t.month (k.doe n)))
        (define-fun k.dom ((n Int)) Int (t.dom (k.doe n)))
        (define-fun k.ms ((x Int)) Int (div (+ (* 153 x) 2) 5))
        (define-fun k.civil ((y Int) (m Int) (d Int)) Int
          (let ((b (+ (- y (ite (<= m 2) 1 0)) %12$d)) (mp (+ (- m 3) (ite (<= m 2) 12 0))))
            (let ((yoe (mod b 400)))
              (- (+ (* (- (div b 400) %13$d) %2$d) (* 365 yoe) (div yoe 4) (- (div yoe 100))
                    (k.ms mp) d (- 1))
                 %14$d))))
        ; emitAddMonths after the prefix, over its year y, month mo and day of month dm
        (define-fun k.am.k ((mo Int) (m Int)) Int (+ (- mo 1) m %15$d))
        (define-fun k.am.nm ((mo Int) (m Int)) Int (mod (k.am.k mo m) 12))
        (define-fun k.am.ny ((y Int) (mo Int) (m Int)) Int (- (+ y (div (k.am.k mo m) 12)) %16$d))
        (define-fun k.len ((nm Int) (y Int)) Int
          (let ((mp2 (ite (<= nm 1) (+ nm 10) (- nm 2))))
            (ite (= mp2 11) (- (+ 365 (ite (spec.leap y) 1 0)) (k.ms 11))
                 (- (k.ms (ite (< (+ mp2 1) %17$d) (+ mp2 1) %17$d)) (k.ms mp2)))))
        (define-fun k.am.cd ((y Int) (mo Int) (dm Int) (m Int)) Int
          (let ((l (k.len (k.am.nm mo m) (k.am.ny y mo m)))) (ite (< dm l) dm l)))
        (define-fun k.addMonths ((y Int) (mo Int) (dm Int) (m Int)) Int
          (k.civil (k.am.ny y mo m) (+ (k.am.nm mo m) 1) (k.am.cd y mo dm m)))
        """.formatted(VarkaChrono.NARROW_BIAS, VarkaChrono.ERA_DAYS, VarkaChrono.QUAD_DAY_ADD,
        VarkaChrono.JULIAN_CYCLE_DAYS, VarkaChrono.MONTH_NUM_M, VarkaChrono.MONTH_NUM_ADD, mnk,
        ChronoDivide.DAY_OF_MONTH.divisor, VarkaChrono.MONTH3_JANUARY,
        VarkaChrono.MARCH_TO_JANUARY_DAYS, VarkaChrono.NARROW_ERA_BIAS, VarkaChrono.YEAR_BIAS,
        VarkaChrono.YEAR_BIAS / 400, VarkaChrono.MARCH_EPOCH_SHIFT, VarkaChrono.MONTH_ARITH_BIAS,
        VarkaChrono.MONTH_ARITH_BIAS / 12, VarkaChrono.MARCH_YEAR_JANUARY + 1);
  }

  // -----------------------------------------------------------------------------------------
  // The calendar: rules to formula, and the lemmas the refinements instantiate.
  // -----------------------------------------------------------------------------------------

  private static final String YMD =
      "(declare-const y Int) (declare-const m Int) (declare-const d Int)";

  private static void calendarRules(StringBuilder out) {
    section(out, "The calendar's rules, which make spec.days the count of days for every year");
    check(out, "rules: 1970-01-01 is day 0", "unsat",
        "(assert (not (= (spec.days 1970 1 1) 0)))");
    check(out, "rules: the day after a day of the month that is not its last is one day on",
        "unsat",
        YMD, "(assert (and (spec.date y m d) (< d (spec.mlen y m))))",
        "(assert (not (= (spec.days y m (+ d 1)) (+ (spec.days y m d) 1))))");
    check(out, "rules: the first of the next month is one day after a month's last", "unsat", YMD,
        "(assert (and (<= 1 m) (< m 12)))",
        "(assert (not (= (spec.days y (+ m 1) 1) (+ (spec.days y m (spec.mlen y m)) 1))))");
    check(out, "rules: 1 January is one day after 31 December", "unsat", YMD,
        "(assert (not (= (spec.days (+ y 1) 1 1) (+ (spec.days y 12 31) 1))))");
    check(out, "rules: and a February 29 exists exactly in the years C1 calls leap", "unsat", YMD,
        "(assert (not (= (spec.date y 2 29) (or (and (= (mod y 4) 0) (not (= (mod y 100) 0))) "
            + "(= (mod y 400) 0)))))");
    var pins = new ArrayList<String>();
    for (LocalDate p : pinDates()) {
      pins.add("(= (spec.days " + lit(p.getYear()) + " " + p.getMonthValue() + " "
          + p.getDayOfMonth() + ") " + lit(p.toEpochDay()) + ")");
    }
    equations(out, "spec.days is LocalDate.toEpochDay", pins);
  }

  /** Around year 0, the Gregorian change, 1970, 9999 and 10000, and the guarded range's ends. */
  private static List<LocalDate> pinDates() {
    return List.of(LocalDate.of(-1, 12, 31), LocalDate.of(0, 2, 29), LocalDate.of(0, 3, 1),
        LocalDate.of(1, 1, 1), LocalDate.of(1582, 10, 4), LocalDate.of(1582, 10, 15),
        LocalDate.of(1900, 3, 1), LocalDate.of(1969, 12, 31), LocalDate.of(1970, 1, 1),
        LocalDate.of(2000, 2, 29), LocalDate.of(2024, 1, 31), LocalDate.of(9999, 12, 31),
        LocalDate.of(10000, 1, 1), LocalDate.ofEpochDay(DECOMPOSE_MIN),
        LocalDate.ofEpochDay(DECOMPOSE_MAX), LocalDate.ofEpochDay(Integer.MIN_VALUE),
        LocalDate.ofEpochDay(Integer.MAX_VALUE));
  }

  private static final String DOE =
      "(declare-const e Int)\n(assert (and (<= 0 e) (< e " + VarkaChrono.ERA_DAYS + ")))";

  /** The decomposition at {@code n} is a valid date whose count is {@code n}: lemma 3's claim. */
  private static String decomposes(String n) {
    return "(and (spec.date (k.year " + n + ") (k.month " + n + ") (k.dom " + n + ")) "
        + "(= (spec.days (k.year " + n + ") (k.month " + n + ") (k.dom " + n + ")) " + n + "))";
  }

  private static String inDecomposed(String n) {
    return "(and (<= " + lit(DECOMPOSE_MIN) + " " + n + ") (<= " + n + " " + DECOMPOSE_MAX + "))";
  }

  /** Lemma 3's instance at {@code n}. */
  private static String decomposition(String n) {
    return "(assert (=> " + inDecomposed(n) + " " + decomposes(n) + "))";
  }

  /** Lemma 4's instance: two valid dates with one count are one date. */
  private static String injective(String y1, String m1, String d1, String y2, String m2,
      String d2) {
    return "(assert (=> (and (spec.date " + y1 + " " + m1 + " " + d1 + ") (spec.date " + y2 + " "
        + m2 + " " + d2 + ") (= (spec.days " + y1 + " " + m1 + " " + d1 + ") (spec.days " + y2
        + " " + m2 + " " + d2 + "))) (and (= " + y1 + " " + y2 + ") (= " + m1 + " " + m2
        + ") (= " + d1 + " " + d2 + "))))";
  }

  /** Lemma 5's instance: the inverse is spec.days on a valid date of the years it is fed. */
  private static String civil(String y, String m, String d) {
    return "(assert (=> (and (spec.date " + y + " " + m + " " + d + ") (<= " + lit(CIVIL_MIN_YEAR)
        + " " + y + ") (<= " + y + " " + CIVIL_MAX_YEAR + ")) (= (k.civil " + y + " " + m + " " + d
        + ") (spec.days " + y + " " + m + " " + d + "))))";
  }

  /**
   * The tail's quotients as variables, each pinned by its remainder's range, and the fields over
   * them: the same arithmetic as {@code t.year}, {@code t.month} and {@code t.dom}, which Z3
   * decides about three times faster in this form (VARKA-245.md 9).
   */
  private static String quotients() {
    int era = VarkaChrono.ERA_DAYS;
    int cyc = VarkaChrono.JULIAN_CYCLE_DAYS;
    long mnk = 1L << VarkaChrono.MONTH_NUM_K;
    int dom = ChronoDivide.DAY_OF_MONTH.divisor;
    return """
        (declare-const c Int) (declare-const yoe Int) (declare-const doy Int)
        (declare-const mi3 Int) (declare-const d0 Int)
        (define-fun quad () Int (+ (* 4 e) %1$d))
        (assert (and (<= 0 (- quad (* %2$d c))) (< (- quad (* %2$d c)) %2$d)))
        (define-fun jul () Int (+ quad (* 4 c)))
        (assert (and (<= 0 (- jul (* %3$d yoe))) (< (- jul (* %3$d yoe)) %3$d)))
        (define-fun rm () Int (- jul (* %3$d yoe)))
        (assert (and (<= 0 (- rm (* 4 doy))) (< (- rm (* 4 doy)) 4)))
        (define-fun mn () Int (+ (* %4$d doy) %5$d))
        (assert (and (<= 0 (- mn (* %6$d mi3))) (< (- mn (* %6$d mi3)) %6$d)))
        (define-fun lo () Int (- mn (* %6$d mi3)))
        (assert (and (<= 0 (- lo (* %7$d d0))) (< (- lo (* %7$d d0)) %7$d)))
        (define-fun ty () Int (+ yoe (ite (>= doy %8$d) 1 0)))
        (define-fun tm () Int (ite (< mi3 %9$d) mi3 (- mi3 12)))
        (define-fun td () Int (+ d0 1))""".formatted(VarkaChrono.QUAD_DAY_ADD, era, cyc,
        VarkaChrono.MONTH_NUM_M, VarkaChrono.MONTH_NUM_ADD, mnk, dom,
        VarkaChrono.MARCH_TO_JANUARY_DAYS, VarkaChrono.MONTH3_JANUARY);
  }

  /** Lemma 7's claim over {@code y}, {@code mo}, {@code m}, negated for its check. */
  private static final String MONTH_ARITH_GOAL = "(assert (not " + monthArith("y", "mo", "m")
      + "))";

  private static String monthArith(String y, String mo, String m) {
    String x = "(+ (- " + mo + " 1) " + m + ")";
    return "(and (= (k.am.ny " + y + " " + mo + " " + m + ") (+ " + y + " (div " + x + " 12))) "
        + "(= (k.am.nm " + mo + " " + m + ") (mod " + x + " 12)))";
  }

  private static void calendarLemmas(StringBuilder out) {
    section(out, "The decomposition and its inverse, as lemmas");
    String tail = "(and (spec.date ty tm td) (= (spec.days ty tm td) (- e "
        + VarkaChrono.MARCH_EPOCH_SHIFT + ")))";
    String defs = "(and (= (t.year e) ty) (= (t.month e) tm) (= (t.dom e) td))";
    // By century of the scaled day: whole, Z3 takes about three times as long (VARKA-245.md 9).
    check(out, "lemma 1a, the era's tail over its quotients: the century of the scaled day of era "
        + "is 0 to 3", "unsat", DOE, quotients(), "(assert (not (and (<= 0 c) (<= c 3))))");
    for (int century = 0; century <= 3; century++) {
      check(out, "lemma 1a, and in century " + century + " every day of era gives a valid date "
          + "whose count from 0000-03-01 is the day of era", "unsat", DOE, quotients(),
          "(assert (= c " + century + "))", "(assert (not " + tail + "))");
    }
    check(out, "lemma 1b, those quotients are the tail's divisions", "unsat", DOE, quotients(),
        "(assert (not " + defs + "))");
    check(out, "lemma 1, the era's tail: lemmas 1a and 1b together", "unsat", DOE, quotients(),
        "(assert " + tail + ")", "(assert " + defs + ")",
        "(assert (not (and (spec.date (t.year e) (t.month e) (t.dom e)) "
            + "(= (spec.days (t.year e) (t.month e) (t.dom e)) (- e "
            + VarkaChrono.MARCH_EPOCH_SHIFT + ")))))");
    check(out, "lemma 2, the period: 400 years on is " + VarkaChrono.ERA_DAYS
        + " days on, with the same month lengths, for every year", "unsat", YMD,
        "(declare-const k Int)",
        "(assert (not (and (= (spec.days (+ y (* 400 k)) m d) (+ (spec.days y m d) (* "
            + VarkaChrono.ERA_DAYS + " k))) (= (spec.mlen (+ y (* 400 k)) m) (spec.mlen y m)))))");
    check(out, "lemma 3, the composition: over the era step's range every day decomposes into a "
        + "valid date whose count is the day", "unsat",
        "(declare-const n Int)",
        "(assert " + inDecomposed("n") + ")",
        "(define-fun e () Int (k.doe n))",
        "(assert (and (spec.date (t.year e) (t.month e) (t.dom e)) (= (spec.days (t.year e) "
            + "(t.month e) (t.dom e)) (- e " + VarkaChrono.MARCH_EPOCH_SHIFT + "))))",
        "(define-fun kk () Int (- (k.era n) " + VarkaChrono.NARROW_ERA_BIAS + "))",
        "(assert (and (= (spec.days (+ (t.year e) (* 400 kk)) (t.month e) (t.dom e)) "
            + "(+ (spec.days (t.year e) (t.month e) (t.dom e)) (* " + VarkaChrono.ERA_DAYS
            + " kk))) (= (spec.mlen (+ (t.year e) (* 400 kk)) (t.month e)) "
            + "(spec.mlen (t.year e) (t.month e)))))",
        "(assert (not " + decomposes("n") + "))");
    check(out, "lemma 4, injectivity: two valid dates with one count are one date", "unsat",
        "(declare-const y1 Int) (declare-const m1 Int) (declare-const d1 Int)",
        "(declare-const y2 Int) (declare-const m2 Int) (declare-const d2 Int)",
        "(assert (and (spec.date y1 m1 d1) (spec.date y2 m2 d2) "
            + "(= (spec.days y1 m1 d1) (spec.days y2 m2 d2))))",
        "(assert (not (and (= y1 y2) (= m1 m2) (= d1 d2))))");
    // In two halves, January and February against March onwards, which is the inverse's own
    // split: whole, cvc5 decides it alone and comes back unknown after the checks above.
    String years = "(assert (and (spec.date y m d) (<= " + lit(CIVIL_MIN_YEAR) + " y) (<= y "
        + CIVIL_MAX_YEAR + ")))";
    String inverse = "(= (k.civil y m d) (spec.days y m d))";
    check(out, "lemma 5a, the inverse: daysFromCivil is spec.days on every valid January and "
        + "February date of years " + CIVIL_MIN_YEAR + " to " + CIVIL_MAX_YEAR, "unsat", YMD, years,
        "(assert (<= m 2))", "(assert (not " + inverse + "))");
    check(out, "lemma 5b, and on every valid date from March to December of those years", "unsat",
        YMD, years, "(assert (> m 2))", "(assert (not " + inverse + "))");
    check(out, "lemma 6, the month length emitAddMonths computes is the month's, every month of "
        + "every year", "unsat", "(declare-const nm Int) (declare-const y Int)",
        "(assert (and (<= 0 nm) (<= nm 11)))",
        "(assert (not (= (k.len nm y) (spec.mlen y (+ nm 1)))))");
    check(out, "lemma 7, the month arithmetic: the bias's years come off again, so the new year "
        + "and month are the carry and the month STANDARD.md 4.13 floors to", "unsat",
        "(declare-const y Int) (declare-const mo Int) (declare-const m Int)", MONTH_ARITH_GOAL);
    check(out, "lemma 8, the count of every valid date of those years is an int", "unsat", YMD,
        "(assert (and (spec.date y m d) (<= " + lit(CIVIL_MIN_YEAR) + " y) (<= y "
            + CIVIL_MAX_YEAR + ")))",
        "(assert (not (jint.in (spec.days y m d))))");
  }

  // -----------------------------------------------------------------------------------------
  // The sites: every dividend the refinements feed a lowering proof is one it covers.
  // -----------------------------------------------------------------------------------------

  /** Every value in the lane, and the dividend within {@code site}'s proven shape and range. */
  private static String covered(ChronoDivide site, String v) {
    return "(and (<= 0 " + v + ") (<= " + v + " " + site.maxDividend + ") (= (mod " + v + " "
        + site.stride + ") " + site.residue + "))";
  }

  private static void sites(StringBuilder out) {
    section(out,
        "The sites: each dividend inside the range its proof in chrono_divide.smt2 covers");
    String n = "(declare-const n Int)\n(assert " + inDecomposed("n") + ")";
    check(out, "ERA_NARROW: the biased day is a dividend its proof covers", "unsat", n,
        "(assert (not " + covered(ChronoDivide.ERA_NARROW, "(+ n " + VarkaChrono.NARROW_BIAS + ")")
            + "))");
    check(out, "JULIAN_CENTURY: the scaled day of era is a dividend its proof covers", "unsat", DOE,
        "(assert (not " + covered(ChronoDivide.JULIAN_CENTURY, "(t.quad e)") + "))");
    check(out, "JULIAN_YEAR: the mapped count is a dividend its proof covers", "unsat", DOE,
        "(assert (not " + covered(ChronoDivide.JULIAN_YEAR, "(t.jul e)") + "))");
    check(out, "DAY_OF_MONTH: the numerator's low bits are a dividend its proof covers, and the "
        + "numerator an int", "unsat", DOE,
        "(assert (not (and " + covered(ChronoDivide.DAY_OF_MONTH, "(mod (t.mn e) "
            + (1L << VarkaChrono.MONTH_NUM_K) + ")") + " (jint.in (t.mn e)) (<= 0 (t.mn e)))))");
    String nm = "(declare-const y Int) (declare-const mo Int) (declare-const m Int)\n"
        + "(assert (and (<= 1 mo) (<= mo 12) (<= " + lit(DECOMPOSE_MIN_YEAR) + " y) (<= y "
        + DECOMPOSE_MAX_YEAR + ") (<= " + lit(VarkaChrono.MONTH_ARITH_MIN_MONTHS) + " m) (<= m "
        + VarkaChrono.MONTH_ARITH_MAX_MONTHS + ")))";
    check(out, "MONTH_ARITH: under the month guard the biased month count is a dividend its proof "
        + "covers", "unsat", nm,
        "(assert (not " + covered(ChronoDivide.MONTH_ARITH, "(k.am.k mo m)") + "))");
    check(out, "MONTH_START: every March-based month is a dividend its proof covers", "unsat",
        "(declare-const mp Int) (assert (and (<= 0 mp) (<= mp 11)))",
        "(assert (not " + covered(ChronoDivide.MONTH_START, "(+ (* 153 mp) 2)") + "))");
    check(out, "YEAR_OF_ERA_400 and the leap hash: every year add_months recomposes, under the "
        + "month guard, is one the inverse and the leap hash are proven for", "unsat", nm,
        "(assert (not (and (<= " + lit(CIVIL_MIN_YEAR) + " (k.am.ny y mo m)) (<= (k.am.ny y mo m) "
            + CIVIL_MAX_YEAR + ") (<= 0 (+ (k.am.ny y mo m) " + VarkaChrono.YEAR_BIAS + ")) "
            + "(<= (+ (k.am.ny y mo m) " + VarkaChrono.YEAR_BIAS + ") "
            + VarkaChrono.LEAP_HASH_MAX_BIASED_YEAR + "))))");
    check(out, "YEAR_OF_ERA_400 and YEAR_OF_ERA_100: over those years the biased March year and "
        + "its year of era are dividends their proofs cover", "unsat", YMD,
        "(assert (and (<= 1 m) (<= m 12) (<= " + lit(CIVIL_MIN_YEAR) + " y) (<= y "
            + CIVIL_MAX_YEAR + ")))",
        "(define-fun b () Int (+ (- y (ite (<= m 2) 1 0)) " + VarkaChrono.YEAR_BIAS + "))",
        "(assert (not (and " + covered(ChronoDivide.YEAR_OF_ERA_400, "b") + " "
            + covered(ChronoDivide.YEAR_OF_ERA_100, "(mod b 400)") + ")))");
    check(out, "the leap hash and the inverse: every year make_date's guard admits is one they are "
        + "proven for", "unsat", "(declare-const y Int)",
        "(assert (and (<= " + VarkaChrono.MAKE_DATE_MIN_YEAR + " y) (<= y "
            + VarkaChrono.MAKE_DATE_MAX_YEAR + ")))",
        "(assert (not (and (<= " + lit(CIVIL_MIN_YEAR) + " y) (<= y " + CIVIL_MAX_YEAR
            + ") (<= 0 (+ y " + VarkaChrono.YEAR_BIAS + ")) (<= (+ y " + VarkaChrono.YEAR_BIAS
            + ") " + VarkaChrono.LEAP_HASH_MAX_BIASED_YEAR + "))))");
    check(out, "the column contract is load-bearing: a date Spark holds outside it "
        + "(proleptic_range), the first past NARROW_DECOMPOSE_MAX_DAYS, feeds ERA_NARROW the "
        + "dividend " + (DECOMPOSE_MAX + 1 + VarkaChrono.NARROW_BIAS) + ", past its proof's range, "
        + "at which chrono_divide.smt2 shows the magic form wrong", "sat",
        "(declare-const n Int)",
        "(assert (= n " + (DECOMPOSE_MAX + 1) + "))",
        "(assert (not " + covered(ChronoDivide.ERA_NARROW, "(+ n " + VarkaChrono.NARROW_BIAS + ")")
            + "))");
  }

  // -----------------------------------------------------------------------------------------
  // The refinements.
  // -----------------------------------------------------------------------------------------

  /**
   * A query the refinement fails, with the departure that explains it: {@code example} pins the
   * witness, and {@code inputs} is the departure's domain, which the restated query excludes.
   */
  private record Departure(int query, String why, List<String> example, String inputs) {
    Departure {
      if (query < 2 || query > 5) {
        throw new IllegalArgumentException("a departure fails one of queries 2 to 5, not " + query);
      }
      example = List.copyOf(example);
    }
  }

  /**
   * One operation in one mode. {@code setup} declares the operands and states what holds of them
   * (their lanes, the specification's value, the lemma instances); the rest are SMT-LIB terms over
   * those names. {@code definitions} gives a function the setup leaves unknown its definition, and
   * goes only into the checks that expect sat: an unsat check that holds with a function unknown,
   * constrained only by proven lemmas' instances, holds of its definition too, and the solver no
   * longer unfolds it. {@code admits} pins query 1 to one input where searching for a model is
   * slow: a satisfiable precondition is shown by any one model.
   */
  private record Refinement(String name, List<String> setup, List<String> definitions, String pre,
      String bound, String defined, String raises, String specValid, String decline,
      String kernelValid, String differs, List<Departure> departures, List<String> admits) {

    Refinement {
      setup = List.copyOf(setup);
      definitions = List.copyOf(definitions);
      departures = List.copyOf(departures);
      admits = List.copyOf(admits);
    }

    Refinement(String name, List<String> setup, List<String> definitions, String pre,
        String bound, String defined, String raises, String specValid, String decline,
        String kernelValid, String differs, List<Departure> departures) {
      this(name, setup, definitions, pre, bound, defined, raises, specValid, decline, kernelValid,
          differs, departures, List.of());
    }

    /** This refinement with query 1 pinned to the input {@code pins} name. */
    Refinement admitting(String... pins) {
      return new Refinement(name, setup, definitions, pre, bound, defined, raises, specValid,
          decline, kernelValid, differs, departures, List.of(pins));
    }

    Refinement(String name, List<String> setup, String pre, String bound, String defined,
        String raises, String specValid, String decline, String kernelValid, String differs,
        List<Departure> departures) {
      this(name, setup, List.of(), pre, bound, defined, raises, specValid, decline, kernelValid,
          differs, departures);
    }
  }

  private static void refine(StringBuilder out, Refinement r) {
    var q = new ArrayList<List<String>>();
    q.add(List.of(pre(r), "(assert " + r.bound() + ")", "(assert " + r.defined() + ")",
        "(assert (not " + r.raises() + "))", "(assert (not " + r.decline() + "))"));
    q.add(List.of(pre(r), "(assert " + r.raises() + ")", "(assert (not " + r.decline() + "))"));
    q.add(List.of(pre(r), "(assert (not " + r.raises() + "))", "(assert (not " + r.decline()
        + "))", "(assert (not (= " + r.kernelValid() + " " + r.specValid() + ")))"));
    q.add(List.of(pre(r), "(assert (not " + r.raises() + "))", "(assert (not " + r.decline()
        + "))", "(assert " + r.specValid() + ")", "(assert " + r.differs() + ")"));
    q.add(List.of(pre(r), "(assert " + r.bound() + ")", "(assert " + r.decline() + ")",
        "(assert (not " + r.raises() + "))"));
    String[] what = {
        "1. the precondition admits a valid lane the specification defines and the kernel answers",
        "2. a decline wherever the specification raises",
        "3. the validity equal where neither raises nor declines",
        "4. the values equal on valid lanes",
        "5. a decline only where the specification raises or the input leaves the bound"};
    for (int i = 0; i < 5; i++) {
      int query = i + 1;
      var departures = r.departures().stream().filter(dp -> dp.query() == query).toList();
      var body = new ArrayList<>(r.setup());
      body.addAll(q.get(i));
      if (departures.isEmpty()) {
        if (i == 0) {
          body.addAll(r.definitions());
          body.addAll(r.admits());
        }
        check(out, r.name() + ": " + what[i], i == 0 ? "sat" : "unsat", body);
        continue;
      }
      var excluded = new ArrayList<>(body);
      for (Departure dp : departures) {
        var witness = new ArrayList<>(body);
        witness.addAll(r.definitions());
        witness.addAll(dp.example());
        check(out, r.name() + ": not " + what[i].substring(3) + ", by " + dp.why(), "sat",
            witness);
        excluded.add("(assert (not " + dp.inputs() + "))");
      }
      check(out, r.name() + ": " + what[i] + ", outside "
          + (departures.size() == 1 ? "that departure's inputs" : "those departures' inputs"),
          "unsat", excluded);
    }
  }

  private static String pre(Refinement r) {
    return "(assert " + r.pre() + ")";
  }

  private static final String INT_AB = "(declare-const a Int) (declare-const va Bool) "
      + "(declare-const b Int) (declare-const vb Bool)\n(assert (and (jint.in a) (jint.in b)))";

  private static void integerArithmetic(StringBuilder out) {
    section(out, "STANDARD.md 4.1, 4.17, 4.18: integer and interval + - * and negation");
    record Op(String name, String exact, String java, String valid, String example) { }
    for (Op op : List.of(
        new Op("a + b", "(+ a b)", "(jint.add a b)", "(and va vb)", "(and (= a jint.MAX) (= b 1))"),
        new Op("a - b", "(- a b)", "(jint.sub a b)", "(and va vb)", "(and (= a jint.MIN) (= b 1))"),
        new Op("a * 2", "(* a 2)", "(jint.mul a 2)", "va", "(= a jint.MAX)"),
        new Op("-a", "(- a)", "(jint.neg a)", "va", "(= a jint.MIN)"))) {
      String overflow = "(and " + op.valid() + " (not (jint.in " + op.exact() + ")))";
      var wrapped = new ArrayList<String>(List.of(INT_AB, "(declare-const sv Int)",
          "(assert (spec.wraps sv " + op.exact() + "))"));
      refine(out, new Refinement(op.name() + ", Spark mode non-ANSI (overflow_wraps), the WRAP "
          + "kernel", wrapped, "true", "true", op.valid(), "false", op.valid(), "false", op.valid(),
          "(not (= " + op.java() + " sv))", List.of()));
      refine(out, new Refinement(op.name() + ", Spark mode ANSI and standard mode, the FAIL "
          + "kernel; and the interval rows, whose arithmetic is exact in every mode (ym - ymm, a "
          + "product, the negation in d - ym)", List.of(INT_AB), "true", "true", op.valid(),
          overflow, op.valid(), overflow, op.valid(), "(not (= " + op.java() + " " + op.exact()
          + "))", List.of()));
      refine(out, new Refinement(op.name() + ", standard mode, the WRAP kernel", List.of(INT_AB),
          "true", "true", op.valid(), overflow, op.valid(), "false", op.valid(),
          "(not (= " + op.java() + " " + op.exact() + "))",
          List.of(new Departure(2, "overflow_wraps, a result: " + op.name() + " past int32 wraps "
              + "where the standard raises 22003", List.of("(assert " + op.example() + ")"),
              "(not (jint.in " + op.exact() + "))"))));
    }
    refine(out, new Refinement("abs(ym), EXTENSIONS.md 3: the IfElse over a FAIL negation, in "
        + "every mode", List.of(INT_AB), "true", "true", "va",
        "(and va (= a jint.MIN))", "va", "(and va (< a 0) (not (jint.in (- a))))", "va",
        "(not (= (ite (< a 0) (jint.neg a) a) (abs a)))", List.of()));
  }

  /** A date operand under the column contract. */
  private static String contract(String v, String valid) {
    return "(=> " + valid + " (and (<= " + lit(VarkaChrono.CONTRACT_MIN_DAYS) + " " + v + ") (<= "
        + v + " " + VarkaChrono.CONTRACT_MAX_DAYS + ")))";
  }

  /** Declares {@code d} with the specification's date of it, and the kernel's lemmas at it. */
  private static List<String> dateOperand() {
    return List.of("(declare-const d Int) (declare-const vd Bool)\n(assert (jint.in d))",
        "(declare-const sy Int) (declare-const sm Int) (declare-const sd Int)",
        "(assert (=> vd (and (spec.date sy sm sd) (= (spec.days sy sm sd) d))))",
        decomposition("d"),
        injective("(k.year d)", "(k.month d)", "(k.dom d)", "sy", "sm", "sd"));
  }

  private static void calendarFields(StringBuilder out) {
    section(out, "STANDARD.md 4.10: EXTRACT of YEAR, MONTH and DAY from a date");
    refine(out, new Refinement("year(d), month(d), day(d), Spark mode and standard mode: one "
        + "statement, since the column contract the unguarded kernel assumes is the standard's "
        + "date range", dateOperand(), contract("d", "vd"), "true", "vd", "false", "vd", "false",
        "vd", "(or (not (= (k.year d) sy)) (not (= (k.month d) sm)) (not (= (k.dom d) sd)))",
        List.of()));
  }

  private static void addMonths(StringBuilder out) {
    section(out, "STANDARD.md 4.13: a date plus or minus a year-month interval, and add_months");
    // The prefix is year(d), month(d) and day(d)'s, whose refinement above shows it is the
    // specification's date of d; the tail is restated over that date.
    var date = dateOperand().subList(0, 3);
    var tail = new ArrayList<String>();
    tail.add("(define-fun sy2 () Int (+ sy (div (+ (- sm 1) m) 12)))");
    tail.add("(define-fun sm2 () Int (+ (mod (+ (- sm 1) m) 12) 1))");
    tail.add("(define-fun sl () Int (spec.mlen sy2 sm2))");
    tail.add("(define-fun cl () Int (ite (< sd sl) sd sl))");
    tail.add("(assert " + monthArith("sy", "sm", "m") + ")");
    tail.add("(assert (=> (and (<= 0 (k.am.nm sm m)) (<= (k.am.nm sm m) 11)) "
        + "(= (k.len (k.am.nm sm m) (k.am.ny sy sm m)) (spec.mlen (k.am.ny sy sm m) "
        + "(+ (k.am.nm sm m) 1)))))");
    // The recomposition and the day count left unknown: D for spec.days and KC for the inverse,
    // tied by lemma 5's instance, and D bounded by lemma 8's at the specification's result.
    tail.add("(declare-fun D (Int Int Int) Int) (declare-fun KC (Int Int Int) Int)");
    tail.add("(define-fun ny () Int (k.am.ny sy sm m))");
    tail.add("(define-fun nm1 () Int (+ (k.am.nm sm m) 1))");
    tail.add("(define-fun cd () Int (k.am.cd sy sm sd m))");
    tail.add("(assert (=> (and (spec.date ny nm1 cd) (<= " + lit(CIVIL_MIN_YEAR) + " ny) (<= ny "
        + CIVIL_MAX_YEAR + ")) (= (KC ny nm1 cd) (D ny nm1 cd))))");
    tail.add("(assert (=> (and (spec.date sy2 sm2 cl) (<= " + lit(CIVIL_MIN_YEAR) + " sy2) "
        + "(<= sy2 " + CIVIL_MAX_YEAR + ")) (jint.in (D sy2 sm2 cl))))");
    var definitions = List.of("(assert (= (D ny nm1 cd) (spec.days ny nm1 cd)))",
        "(assert (= (D sy2 sm2 cl) (spec.days sy2 sm2 cl)))",
        "(assert (= (D sy2 sm2 sd) (spec.days sy2 sm2 sd)))",
        "(assert (= (KC ny nm1 cd) (k.addMonths sy sm sd m)))");
    var add = new ArrayList<>(date);
    add.add("(declare-const m Int) (declare-const vm Bool)\n(assert (jint.in m))");
    add.addAll(tail);
    // d - ym: the count negated by a FAIL node first, whose guard reads the count's validity alone.
    var sub = new ArrayList<>(date);
    sub.add("(declare-const m0 Int) (declare-const vm Bool)\n(assert (jint.in m0))");
    sub.add("(define-fun m () Int (jint.neg m0))");
    sub.addAll(tail);
    String guard = "(and (<= " + lit(VarkaChrono.MONTH_ARITH_MIN_MONTHS) + " m) (<= m "
        + VarkaChrono.MONTH_ARITH_MAX_MONTHS + "))";
    String both = "(and vd vm)";
    String clamped = "(D sy2 sm2 cl)";
    String kernel = "(KC ny nm1 cd)";
    // Query 1 at 2024-01-15 and one month (minus one for d - ym), where a model search is slow.
    String admits = "(assert (and (= d " + LocalDate.of(2024, 1, 15).toEpochDay()
        + ") (= sy 2024) (= sm 1) (= sd 15) (or (= m 1) (= m (- 1)))))";
    refine(out, new Refinement("d + ym, add_months(d, i), Spark mode (clamp_to_month_end, "
        + "proleptic_range): the month guard", add, definitions, contract("d", "vd"),
        "(=> " + both + " " + guard + ")", both, "(and " + both + " (not (jint.in " + clamped
        + ")))", both, "(and " + both + " (not " + guard + "))", both,
        "(not (= " + kernel + " " + clamped + "))", List.of()).admitting(admits));
    String stdRaises = "(and " + both + " (or (> sd sl) (< sy2 1) (> sy2 9999)))";
    String clampInputs = "(> sd sl)";
    String rangeInputs = "(or (< sy2 1) (> sy2 9999))";
    String clampWhy = "clamp_to_month_end, a result: the day the target month lacks is its last "
        + "day, where the standard raises 22008";
    String rangeWhy = "proleptic_range: a year past 0001 to 9999 is a date, where the standard "
        + "raises 22008 and the kernel has no guard on its result";
    refine(out, new Refinement("d + ym, standard mode: the kernel as built, under the standard's "
        + "range for the interval", add, definitions, contract("d", "vd"),
        "(=> vm (and (<= " + lit(-STD_MONTHS) + " m) (<= m " + STD_MONTHS + ")))", both,
        stdRaises, both, "(and " + both + " (not " + guard + "))", both,
        "(not (= " + kernel + " (D sy2 sm2 sd)))",
        List.of(new Departure(2, clampWhy + ", at DATE '2024-01-31' + INTERVAL '1' MONTH",
                List.of("(assert (and vd vm (= d " + LocalDate.of(2024, 1, 31).toEpochDay()
                    + ") (= m 1)))"), clampInputs),
            new Departure(2, rangeWhy + ", at DATE '9999-12-15' + INTERVAL '1' MONTH",
                List.of("(assert (and vd vm (= d " + LocalDate.of(9999, 12, 15).toEpochDay()
                    + ") (= m 1)))"), rangeInputs))).admitting(admits));
    String negOverflow = "(and vm (= m0 jint.MIN))";
    var mayDecline = new Departure(5, "a decline Spark does not need: the negation's guard reads "
        + "the interval's validity alone, and Spark negates nothing on a row whose date is null",
        List.of("(assert (and (not vd) (= m0 jint.MIN) (= sy 1970) (= sm 1) (= sd 1)))"),
        "(not vd)");
    refine(out, new Refinement("d - ym, Spark mode: the FAIL negation and the month guard", sub,
        definitions, contract("d", "vd"),
        "(=> " + both + " (and (not (= m0 jint.MIN)) " + guard + "))", both,
        "(and " + both + " (or (= m0 jint.MIN) (not (jint.in " + clamped + "))))", both,
        "(or " + negOverflow + " (and " + both + " (not " + guard + ")))", both,
        "(not (= " + kernel + " " + clamped + "))", List.of(mayDecline)).admitting(admits));
    refine(out, new Refinement("d - ym, standard mode: the kernel as built, under the standard's "
        + "range for the interval", sub, definitions, contract("d", "vd"),
        "(=> vm (and (<= " + lit(-STD_MONTHS) + " m0) (<= m0 " + STD_MONTHS + ")))", both,
        stdRaises, both, "(or " + negOverflow + " (and " + both + " (not " + guard + ")))", both,
        "(not (= " + kernel + " (D sy2 sm2 sd)))",
        List.of(new Departure(2, clampWhy + ", at DATE '2024-03-31' - INTERVAL '1' MONTH",
                List.of("(assert (and vd vm (= d " + LocalDate.of(2024, 3, 31).toEpochDay()
                    + ") (= m0 1)))"), clampInputs),
            new Departure(2, rangeWhy + ", at DATE '0001-01-15' - INTERVAL '1' MONTH",
                List.of("(assert (and vd vm (= d " + LocalDate.of(1, 1, 15).toEpochDay()
                    + ") (= m0 1)))"), rangeInputs))).admitting(admits));
  }

  private static void addDays(StringBuilder out) {
    section(out, "STANDARD.md 4.14 and 4.19, EXTENSIONS.md 1: a date plus days, and datediff");
    String di = "(declare-const d Int) (declare-const vd Bool) (declare-const i Int) "
        + "(declare-const vi Bool)\n(assert (and (jint.in d) (jint.in i)))";
    var wraps = List.of(di, "(declare-const sv Int)", "(assert (spec.wraps sv (+ d i)))");
    String both = "(and vd vi)";
    refine(out, new Refinement("date_add(d, i), Spark mode (proleptic_range, day_count_wraps)",
        wraps, contract("d", "vd"), "true", both, "false", both, "false", both,
        "(not (= (jint.add d i) sv))", List.of()));
    String limit = "(and (<= " + lit(-VarkaChrono.INTERVAL_DAY_LIMIT_DAYS) + " i) (<= i "
        + VarkaChrono.INTERVAL_DAY_LIMIT_DAYS + "))";
    String castRaises = "(and " + both + " (not (jlong.in (* i " + MICROS_PER_DAY + "))))";
    String inputBound = "(and vi (not " + limit + "))";
    var mayDecline = new Departure(5, "a decline Spark does not need: the input bound reads the "
        + "offset column's validity alone, and Spark casts nothing on a row whose date is null",
        List.of("(assert (and (not vd) (= i " + (VarkaChrono.INTERVAL_DAY_LIMIT_DAYS + 1) + ")))"),
        "(not vd)");
    refine(out, new Refinement("d + CAST(i AS INTERVAL DAY), Spark mode (interval_overflow_state, "
        + "day_count_wraps): the input bound INTERVAL_DAY_LIMIT_DAYS, where the cast's multiply by "
        + "MICROS_PER_DAY overflows", wraps, contract("d", "vd"),
        "(=> " + both + " " + limit + ")", both,
        castRaises, both, inputBound, both, "(not (= (jint.add d i) sv))", List.of(mayDecline)));
    String inStd = "(and (<= " + lit(STD_MIN_DAYS) + " (+ d i)) (<= (+ d i) " + STD_MAX_DAYS
        + "))";
    refine(out, new Refinement("d + CAST(i AS INTERVAL DAY), standard mode: the kernel as built, "
        + "under the standard's range for the interval", List.of(di), contract("d", "vd"),
        "(=> vi (and (<= " + lit(-STD_DAYS) + " i) (<= i " + STD_DAYS + ")))", both,
        "(and " + both + " (or (not (and (<= " + lit(-STD_DAYS) + " i) (<= i " + STD_DAYS
        + "))) (not " + inStd + ")))", both, inputBound, both, "(not (= (jint.add d i) (+ d i)))",
        List.of(new Departure(2, "proleptic_range: DATE '9999-12-31' + INTERVAL '1' DAY is "
                + "+10000-01-01, where the standard raises 22008 and the kernel has no guard on "
                + "its result", List.of("(assert (and vd vi (= d " + STD_MAX_DAYS
                + ") (= i 1)))"), "(not " + inStd + ")"),
            new Departure(2, "interval_precision_unbounded, a range: CAST(100 AS INTERVAL DAY) "
                + "is a value, where the standard's two digits raise 22015, and the kernel's "
                + "input bound is Spark's", List.of("(assert (and vd vi (= d 0) (= i 100)))"),
                "(not (and (<= " + lit(-STD_DAYS) + " i) (<= i " + STD_DAYS + ")))"))));
    String ab = INT_AB;
    refine(out, new Refinement("datediff(a, b), Spark mode: EXTENSIONS.md 1's n_a - n_b",
        List.of(ab), "(and " + contract("a", "va") + " " + contract("b", "vb") + ")", "true",
        "(and va vb)", "false", "(and va vb)", "false", "(and va vb)",
        "(not (= (jint.sub a b) (- a b)))", List.of()));
    check(out, "datediff(a, b), outside the column contract: EXTENSIONS.md 1's n_a - n_b leaves "
        + "int32, where Spark's DateDiff, an Int subtraction, wraps as the kernel does", "sat", ab,
        "(assert (and (= a jint.MAX) (= b jint.MIN)))",
        "(assert (not (= (jint.sub a b) (- a b))))");
  }

  private static void dayOfWeek(StringBuilder out) {
    section(out, "EXTENSIONS.md 2 and 3: the days of the week, over every int32 day");
    // Through quotients and remainders, as the time's divisions are: the kernel's floorMod is r
    // by lemma F, and the specification's mod(n + 3, 7) and mod(n + 4, 7) are written as
    // STANDARD.md 2 defines mod.
    check(out, "lemma F, Math.floorMod(n, 7) is the remainder of n by 7 for every int, which "
        + "floor_mod7.smt2 proves emitFloorMod7 computes", "unsat",
        "(declare-const n Int) (assert (jint.in n))", quotient("n", 7, "q", "r", "true"),
        "(assert (not (= (jint.floorMod n 7) r)))");
    var n = List.of("(declare-const n Int) (declare-const vn Bool)\n(assert (jint.in n))",
        quotient("n", 7, "q", "r", "true"), quotient("(+ n 3)", 7, "q3", "r3", "true"),
        quotient("(+ n 4)", 7, "q4", "r4", "true"),
        "(assert (= (jint.floorMod n 7) r))",
        "(define-fun wd () Int (ite (>= (+ r 3) 7) (- (+ r 3) 7) (+ r 3)))",
        "(define-fun dw () Int (+ (ite (>= (+ r 4) 7) (- (+ r 4) 7) (+ r 4)) 1))");
    refine(out, new Refinement("weekday(d), dayofweek(d), Spark mode: floorMod(n, 7) and a "
        + "modular offset, no int add before the mod; extract(DAYOFWEEK_ISO FROM d) is weekday's "
        + "tail plus one in the emitter and in EXTENSIONS.md 2, so it follows", n,
        "true", "true", "vn", "false", "vn", "false", "vn",
        "(or (not (= wd r3)) (not (= dw (+ r4 1))))", List.of()));
  }

  private static void makeDate(StringBuilder out) {
    section(out, "EXTENSIONS.md 3: make_date (overflow_yields_null), non-ANSI and ANSI");
    var table = new StringBuilder("(define-fun k.len30 ((c Int)) Int");
    for (int c = 1; c <= 12; c++) {
      table.append(c < 12 ? " (ite (= c " + c + ") " : " ").append(30 | (c - (c >>> 3)));
    }
    table.append(")".repeat(11)).append(")");
    var setup = List.of("(declare-const y Int) (declare-const m Int) (declare-const dd Int) "
            + "(declare-const vy Bool) (declare-const vm Bool) (declare-const vdd Bool)\n"
            + "(assert (and (jint.in y) (jint.in m) (jint.in dd)))",
        "; emitMakeDate's month length, 30 | (c - (c >>> 3)), evaluated per month in Java",
        table.toString(),
        "(define-fun c () Int (ite (< m 1) 1 (ite (> m 12) 12 m)))",
        "(define-fun kdate () Bool (and (<= 1 m) (<= m 12) (<= 1 dd) (<= dd (ite (= c 2) "
            + "(+ 28 (ite (spec.leap y) 1 0)) (k.len30 c)))))",
        "(define-fun sdate () Bool (and (spec.date y m dd) (<= " + lit(LOCAL_DATE_MIN_YEAR)
            + " y) (<= y " + LOCAL_DATE_MAX_YEAR + ")))",
        civil("y", "m", "dd"));
    check(out, "make_date: the bit trick is every month's length but February's", "unsat",
        "(declare-const c Int) (declare-const y Int)",
        "(assert (and (<= 1 c) (<= c 12) (not (= c 2))))", table.toString(),
        "(assert (not (= (k.len30 c) (spec.mlen y c))))");
    String all = "(and vy vm vdd)";
    String years = "(and (<= " + VarkaChrono.MAKE_DATE_MIN_YEAR + " y) (<= y "
        + VarkaChrono.MAKE_DATE_MAX_YEAR + "))";
    String yearGuard = "(and " + all + " (not " + years + "))";
    String overflows = "(not (jint.in (spec.days y m dd)))";
    refine(out, new Refinement("make_date(y, m, dd), Spark mode non-ANSI: an invalid date is null; "
        + "the year guard MAKE_DATE_MIN_YEAR to MAKE_DATE_MAX_YEAR", setup, "true",
        "(=> " + all + " " + years + ")", "(and " + all + " sdate)",
        "(and " + all + " sdate " + overflows + ")", "(and " + all + " sdate)", yearGuard,
        "(and " + all + " kdate)", "(not (= (k.civil y m dd) (spec.days y m dd)))", List.of()));
    refine(out, new Refinement("make_date(y, m, dd), Spark mode ANSI: an invalid date raises "
        + "22023, which the kernel declines", setup, "true", "(=> " + all + " " + years + ")",
        "(and " + all + " sdate)", "(and " + all + " (or (not sdate) " + overflows + "))", all,
        "(or " + yearGuard + " (and " + all + " (not kdate)))", all,
        "(not (= (k.civil y m dd) (spec.days y m dd)))", List.of()));
  }

  private static void intervals(StringBuilder out) {
    section(out, "STANDARD.md 4.12: the YEAR of a year-month interval, and the day-time fields");
    refine(out, new Refinement("extract(YEAR FROM ym), Spark mode and standard mode: one "
        + "statement, since it holds over every month count and the standard's are among them; "
        + "the multiply-high is jint.div by int_mulhi_divide.smt2",
        List.of("(declare-const m Int) (declare-const vm Bool)\n(assert (jint.in m))",
            "(declare-const sq Int) (assert (spec.quot m 12 sq))"),
        "true", "true", "vm", "false", "vm", "false", "vm", "(not (= (jint.div m 12) sq))",
        List.of()));
    dayTimeBound(out);
  }

  /** {@code SCOPE_STANDARD_MODE.md} 3.2's bound, as a theorem over the long lane's divisions. */
  private static void dayTimeBound(StringBuilder out) {
    long bound = VarkaVectorIR.ConstDivide.EXACT_DIVIDEND_BOUND;
    check(out, "standard mode, SCOPE_STANDARD_MODE.md 3.2: a day-time interval of the standard's "
        + "two-digit days, under " + (STD_DAYS + 1) + " days of microseconds, lies under "
        + "ConstDivide.EXACT_DIVIDEND_BOUND, the dividend bound under which long_divide.smt2 "
        + "proves both long-lane division forms exact; a constant comparison, no field extract "
        + "restated", "unsat",
        "(declare-const u Int)",
        "(assert (< (abs u) " + ((STD_DAYS + 1) * MICROS_PER_DAY) + "))",
        "(assert (not (< (abs u) " + bound + ")))");
    check(out,
        "and Spark's own day-time range does not: a microsecond count of the lane is past it",
        "sat", "(declare-const u Int)", "(assert (jlong.in u))",
        "(assert (not (< (abs u) " + bound + ")))");
  }

  /** A {@code TIME} operand: nanoseconds of the day under a set bit, anything in the lane else. */
  private static String time(String t, String valid) {
    return "(declare-const " + t + " Int) (declare-const " + valid + " Bool)\n"
        + "(assert (and (jlong.in " + t + ") (=> " + valid + " (and (<= 0 " + t + ") (< " + t
        + " " + DAY + ")))))";
  }

  /** {@code q} and {@code r}, the floor quotient and remainder of {@code x} by {@code unit}. */
  private static String quotient(String x, long unit, String q, String r, String when) {
    return "(declare-const " + q + " Int) (declare-const " + r + " Int)\n(assert (=> " + when
        + " (and (= " + x + " (+ (* " + unit + " " + q + ") " + r + ")) (<= 0 " + r + ") (< " + r
        + " " + unit + "))))";
  }

  /** Lemma S's instance at {@code x}, under its own premise. */
  private static String sixtyInstance(String x, String r) {
    return "(assert (=> (and vt (<= 0 " + x + ") (< " + x + " " + (DAY / SECOND) + ")) (= "
        + sixty(x) + " " + r + ")))";
  }

  private static String sixty(String x) {
    return "(jlong.sub " + x + " (jlong.mul (jlong.div " + x + " " + SIXTY + ") " + SIXTY + "))";
  }

  private static void times(StringBuilder out) {
    section(out, "STANDARD.md 4.11, 4.15, 4.16 and EXTENSIONS.md 3: the time of day");
    // The divisions of a time, each through its quotient and remainder: the long lane's division
    // of a non-negative value is the floor quotient and remainderOfSixty the remainder (lemmas T
    // and S below), and the specification's mod is written as STANDARD.md 2 defines it. Through
    // SMT-LIB's div and mod instead, cvc5 decides these claims alone in milliseconds and comes back
    // unknown after other checks in a run (VARKA-245.md 9).
    for (long unit : new long[] {HOUR, MINUTE, SECOND, SIXTY}) {
      check(out, "lemma T, the long lane's division by " + unit + " of a non-negative long is its "
          + "floor quotient", "unsat", "(declare-const x Int)",
          "(assert (and (<= 0 x) (jlong.in x)))",
          quotient("x", unit, "q", "r", "true"), "(assert (not (= (jlong.div x " + unit + ") q)))");
    }
    check(out, "lemma S, remainderOfSixty of a non-negative x below a day's seconds is x's "
        + "remainder by sixty, with lemma T's instance for sixty", "unsat", "(declare-const x Int)",
        "(assert (and (<= 0 x) (< x " + (DAY / SECOND) + ")))",
        quotient("x", SIXTY, "q", "r", "true"),
        "(assert (= (jlong.div x " + SIXTY + ") q))", "(assert (not (= " + sixty("x") + " r)))");
    var hm = List.of(time("t", "vt"), quotient("t", HOUR, "qh", "rh", "vt"),
        quotient("rh", MINUTE, "qm", "rm", "vt"), quotient("t", MINUTE, "qn", "rn", "vt"),
        quotient("qn", SIXTY, "qs", "rs", "vt"),
        "(assert (=> vt (and (= (jlong.div t " + HOUR + ") qh) (= (jlong.div t " + MINUTE
            + ") qn))))", sixtyInstance("qn", "rs"));
    refine(out, new Refinement("hour(t), minute(t), Spark mode and standard mode: one statement; "
        + "the divisions are jlong.div by long_divide.smt2 and time_bounds.smt2", hm, "true",
        "true", "vt", "false", "vt", "false", "vt",
        "(or (not (= (jlong.div t " + HOUR + ") qh)) (not (= " + sixty("qn") + " qm)))",
        List.of()));
    var sec = List.of(time("t", "vt"), quotient("t", MINUTE, "qm", "rm", "vt"),
        quotient("rm", SECOND, "qs", "rs", "vt"), quotient("t", SECOND, "qn", "rn", "vt"),
        quotient("qn", SIXTY, "q6", "r6", "vt"),
        "(assert (=> vt (= (jlong.div t " + SECOND + ") qn)))", sixtyInstance("qn", "r6"));
    refine(out, new Refinement("second(t), Spark mode: EXTENSIONS.md 3's whole seconds", sec,
        "true", "true", "vt", "false", "vt", "false", "vt",
        "(not (= " + sixty("qn") + " qs))", List.of()));
    refine(out, new Refinement("t - t2, Spark mode and standard mode: one statement, the "
        + "difference truncated to microseconds",
        List.of(time("a", "va"), time("b", "vb"), "(declare-const sq Int)",
            "(assert (spec.quot (- a b) " + NANOS_PER_MICROS + " sq))"), "true", "true",
        "(and va vb)", "false", "(and va vb)", "false", "(and va vb)",
        "(not (= (jlong.div (jlong.sub a b) " + NANOS_PER_MICROS + ") sq))", List.of()));
    long fromMax = (DAY - 1) / NANOS_PER_MICROS;
    refine(out, new Refinement("time_from_micros(k), Spark mode: the count guarded to the day's "
        + "worth, where Spark's multiplyExact and range check raise 22023",
        List.of("(declare-const k Int) (declare-const vk Bool)\n(assert (jlong.in k))"), "true",
        "(=> vk (and (<= 0 k) (<= k " + fromMax + ")))", "vk",
        "(and vk (not (and (<= 0 (* k " + NANOS_PER_MICROS + ")) (< (* k " + NANOS_PER_MICROS
            + ") " + DAY + "))))", "vk", "(and vk (or (< k 0) (> k " + fromMax + ")))", "vk",
        "(not (= (jlong.mul k " + NANOS_PER_MICROS + ") (* k " + NANOS_PER_MICROS + ")))",
        List.of()));
    // t + dt: the interval held to a day, then the sum to the day (VarkaTimeCompiler).
    var setup = List.of(time("t", "vt"),
        "(declare-const u Int) (declare-const vu Bool)\n(assert (jlong.in u))",
        "(define-fun s () Int (+ t (* u " + NANOS_PER_MICROS + ")))",
        "(define-fun ks () Int (jlong.add t (jlong.mul u " + NANOS_PER_MICROS + ")))");
    String both = "(and vt vu)";
    String inDay = "(and (<= 0 s) (< s " + DAY + "))";
    String decline = "(or (and vu (or (< u " + lit(-MICROS_PER_DAY) + ") (> u " + MICROS_PER_DAY
        + "))) (and " + both + " (not (and (<= 0 ks) (<= ks " + (DAY - 1) + ")))))";
    var mayDecline = new Departure(5, "a decline Spark does not need: the interval's guard reads "
        + "its own validity alone, and Spark adds nothing on a row whose time is null",
        List.of("(assert (and (not vt) (= u " + (MICROS_PER_DAY + 1) + ")))"), "(not vt)");
    refine(out, new Refinement("t + dt, Spark mode (time_add_raises): the interval held to a day, "
        + "the sum to the day", setup, "true", "(=> vt (=> vu " + inDay + "))", both,
        "(and " + both + " (not " + inDay + "))", both, decline, both, "(not (= ks s))",
        List.of(mayDecline)));
    refine(out, new Refinement("t + dt, standard mode: the kernel as built, under an HOUR TO "
        + "SECOND interval of the standard's precision", setup, "true",
        "(=> vu (< (abs u) " + STD_HOURS_MICROS + "))", both, "false", both, decline, both,
        "(not (= ks (mod s " + DAY + ")))",
        List.of(new Departure(5, "time_add_raises, a result: TIME '23:30' + INTERVAL '1' HOUR is "
                + "00:30 to the standard, an error to Spark, so the kernel declines",
                List.of("(assert (and vt vu (= t " + (23 * HOUR + 30 * MINUTE) + ") (= u "
                    + (HOUR / NANOS_PER_MICROS) + ")))"), "(and vt (not " + inDay + "))"),
            mayDecline)));
  }

  // -----------------------------------------------------------------------------------------
  // Shared.
  // -----------------------------------------------------------------------------------------

  // -----------------------------------------------------------------------------------------
  // The 64-bit decimal (VARKA-348).
  // -----------------------------------------------------------------------------------------

  /** A decimal column operand: its lane value {@code u}, validity {@code v} and type. */
  private record Dec(String u, String v, int precision, int scale) {
    /** The precision's largest unscaled value, from Spark's own table of powers. */
    long bound() {
      return Decimal.POW_10()[precision] - 1L;
    }

    String declare() {
      return "(declare-const " + u + " Int) (declare-const " + v + " Bool)\n(assert (jlong.in "
          + u + "))";
    }

    /** Spark's column reader raises on a valid slot outside the precision. */
    String outside() {
      return "(and " + v + " (> (abs " + u + ") " + bound() + "))";
    }
  }

  private static long pow10(int k) {
    return Decimal.POW_10()[k];
  }

  /**
   * One decimal predicate row, Spark mode, which here is also standard mode: no decimal operation
   * of these rows wraps, rounds or raises past its operands' precision. Every operand is a column
   * whose lane value the specification reads as {@code u / 10^s} (STANDARD.md 4.1). The kernel
   * declines on a valid slot outside the precision in any of the row's columns, by its leaves'
   * slot checks ({@code decimal_slot.smt2}), and the specification raises where Spark's column
   * reader does, on such a slot in a column Spark reads: every column here, which the
   * comparisons all evaluate. The kernel's truth {@code k} and the specification's {@code t} are
   * conditions, compared on the lanes where the specification's answer is known.
   */
  private static void decimalRow(StringBuilder out, String name, List<Dec> cols,
      List<String> defs, String kernelValid, String specValid, String k, String t) {
    var setup = new ArrayList<String>();
    for (Dec c : cols) {
      setup.add(c.declare());
    }
    setup.addAll(defs);
    String outside = anyOutside(cols);
    refine(out, new Refinement(name, setup, "true", "true", specValid, outside, specValid,
        outside, kernelValid, "(not (= " + k + " " + t + "))", List.of()));
  }

  /** Whether some of {@code cols} holds a valid slot outside its precision. */
  private static String anyOutside(List<Dec> cols) {
    return cols.size() == 1 ? cols.get(0).outside() : "(or " + String.join(" ",
        cols.stream().map(Dec::outside).toList()) + ")";
  }

  private static void decimals(StringBuilder out) {
    section(out, "STANDARD.md 4.1 and 4.2: the 64-bit decimal's comparisons");
    var d9 = new Dec("a", "va", 9, 2);
    var d9b = new Dec("b", "vb", 9, 2);
    var d9s4 = new Dec("c", "vc", 9, 4);
    var d16s9 = new Dec("e", "ve", 16, 9);
    var d18 = new Dec("a", "va", 18, 2);
    String ab = "(and va vc)";
    // Two scales: the kernel compares u1 * 10^k with u2, the specification the rationals,
    // u1 / 10^s1 against u2 / 10^s2, as u1 * 10^s2 against u2 * 10^s1.
    for (String op : List.of("<", "=")) {
      decimalRow(out, "d9 " + op + " d9s4, Spark mode: at DECIMAL(11, 4), d9 times 100",
          List.of(d9, d9s4), List.of(), ab, ab, "(" + op + " (jlong.mul a 100) c)",
          "(" + op + " (* a " + pow10(4) + ") (* c " + pow10(2) + "))");
    }
    decimalRow(out, "d9 <=> d9s4, Spark mode: total, true where both are null", List.of(d9, d9s4),
        List.of(), "true", "true",
        "(or (and va vc (= (jlong.mul a 100) c)) (and (not va) (not vc)))",
        "(or (and va vc (= (* a " + pow10(4) + ") (* c " + pow10(2) + "))) "
            + "(and (not va) (not vc)))");
    decimalRow(out, "d16s9 >= d9s4, Spark mode: at DECIMAL(16, 9), d9s4 times 10^5",
        List.of(d16s9, d9s4), List.of(), "(and ve vc)", "(and ve vc)",
        "(>= e (jlong.mul c " + pow10(5) + "))",
        "(>= (* e " + pow10(4) + ") (* c " + pow10(9) + "))");
    decimalRow(out, "d18 > 1.5, Spark mode: the literal's slot at scale 2, 150", List.of(d18),
        List.of(), "va", "va", "(> a 150)", "(> (* a 10) (* 15 100))");
    decimalRow(out, "d9 < 0.425, Spark mode: in the analyzed form at DECIMAL(10, 3), the column "
        + "times 10 and the literal 425", List.of(d9), List.of(), "va", "va",
        "(< (jlong.mul a 10) 425)", "(< (* a 1000) (* 425 100))");
    // Proves without the factor too; VarkaDecimalValueSuite.literalKeptAtItsOwnScale pins it.
    decimalRow(out, "d9 < 10000000.0125, Spark mode: at DECIMAL(12, 4), the column times 100 "
        + "and the literal 100000000125", List.of(d9), List.of(), "va", "va",
        "(< (jlong.mul a 100) 100000000125)", "(< (* a " + pow10(4) + ") (* 100000000125 100))");
    decimalRow(out, "-d9 < d9b, Spark mode: the wrapping negation", List.of(d9, d9b), List.of(),
        "(and va vb)", "(and va vb)", "(< (jlong.neg a) b)", "(< (- a) b)");
    decimalRow(out, "abs(d9) >= d9b, Spark mode: the blend, which decimal64.smt2 shows the "
        + "lanewise ABS equal to", List.of(d9, d9b), List.of(), "(and va vb)", "(and va vb)",
        "(>= (ite (< a 0) (jlong.neg a) a) b)", "(>= (abs a) b)");
  }

  private static void section(StringBuilder out, String title) {
    out.append("\n; ").append("-".repeat(95)).append("\n; ").append(title).append("\n; ")
        .append("-".repeat(95)).append('\n');
  }

  private static void check(StringBuilder out, String what, String expect, String... body) {
    VarkaProofFiles.check(out, what, expect, List.of(body));
  }

  private static void check(StringBuilder out, String what, String expect, List<String> body) {
    VarkaProofFiles.check(out, what, expect, body);
  }

  private static void equations(StringBuilder out, String what, List<String> eqs) {
    var lines = new ArrayList<String>();
    lines.add("(assert (not (and");
    for (String e : eqs) {
      lines.add("  " + e);
    }
    lines.add(")))");
    check(out, what + ", on " + eqs.size() + " dates", "unsat", lines);
  }

  private static String lit(long v) {
    return VarkaProofFiles.lit(v);
  }
}
