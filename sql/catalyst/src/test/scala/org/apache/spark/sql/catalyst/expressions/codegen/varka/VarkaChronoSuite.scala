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


package org.apache.spark.sql.catalyst.expressions.codegen.varka

import java.time.LocalDate
import java.time.temporal.IsoFields

import scala.util.Random

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChrono.Fields
import org.apache.spark.sql.catalyst.util.DateTimeUtils

/**
 * The scalar half of task 26: `VarkaChrono`'s civil-from-days model, checked against
 * `java.time` before any of it is emitted as bytecode. The emitter loads the same constants
 * these methods use, so a disagreement between an emitted kernel and this model is an emission
 * bug, while a disagreement between this model and `LocalDate` is an arithmetic one - keeping
 * the two failures apart is the reason this suite exists separately from the emitter's.
 *
 * The everyday tests run a curated boundary set. The exhaustive sweep that actually justifies
 * the constants - all 16777216 days the lowering is defined over, against `LocalDate` - is
 * gated behind `-Dvarka.sweep=true`, and its result is recorded in
 * `sql/varka/plans/PLAN_TASK_26.md`. The nearest precedent for the gate is the engine module's
 * `varka.jmh` JUnit gate - no other catalyst Varka test is property-gated.
 *
 *   build/sbt 'catalyst/testOnly *VarkaChronoSuite'
 *   build/sbt "project catalyst" 'set Test/javaOptions += "-Dvarka.sweep=true"' \
 *     'testOnly *VarkaChronoSuite'
 */
class VarkaChronoSuite extends SparkFunSuite {

  /** `java.time`'s answer, which is exactly what Spark's `DateTimeUtils.getYear` and its three
   * siblings return for the same day. */
  private def reference(days: Int): Fields = {
    val date = LocalDate.ofEpochDay(days.toLong)
    // IsoFields.QUARTER_OF_YEAR, not (month + 2) / 3: the second is what the emitter computes,
    // and an oracle that restates the implementation checks nothing. DateTimeUtils.getQuarter
    // is the first form, so it is the definition this must be held to.
    new Fields(date.getYear, date.getMonthValue, date.getDayOfMonth,
      date.get(IsoFields.QUARTER_OF_YEAR), date.getDayOfYear)
  }

  /**
   * Every day the decomposition could plausibly get wrong: era and century starts, the
   * 400-year cycle's leap-day edges, every month-length boundary of a leap
   * and a common year, the March-based year's own turn, pre-1970, year 1, and the narrowed
   * range's endpoints. Days outside the narrowed range are marked so the narrowed model, which
   * is undefined there, is not asked about them.
   */
  private def boundaryDays: Seq[Int] = {
    val eras = (-13 to 13).flatMap { era =>
      val start = era * 146097 - 719468
      Seq(start - 1, start, start + 1)
    }
    val centuries = (-1 to 4).flatMap { century =>
      val start = century * 36524 - 719468
      Seq(start - 1, start, start + 1)
    }
    val chrono = Seq(
      LocalDate.of(1, 1, 1), LocalDate.of(1, 12, 31),
      LocalDate.of(1600, 2, 28), LocalDate.of(1600, 2, 29), LocalDate.of(1600, 3, 1),
      LocalDate.of(1700, 2, 28), LocalDate.of(1700, 3, 1),
      LocalDate.of(1900, 2, 28), LocalDate.of(1900, 3, 1),
      LocalDate.of(1969, 12, 31), LocalDate.of(1970, 1, 1), LocalDate.of(1970, 1, 2),
      LocalDate.of(2000, 2, 28), LocalDate.of(2000, 2, 29), LocalDate.of(2000, 3, 1),
      LocalDate.of(2024, 12, 31), LocalDate.of(2025, 1, 1),
      LocalDate.of(9999, 12, 31)).map(_.toEpochDay.toInt)
    val monthEnds = for {
      year <- Seq(2023, 2024)
      month <- 1 to 12
      day <- Seq(1, LocalDate.of(year, month, 1).lengthOfMonth)
    } yield LocalDate.of(year, month, day).toEpochDay.toInt
    val edges = Seq(
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS - 1, VarkaChrono.NARROW_MAX_DAYS)
    eras ++ centuries ++ chrono ++ monthEnds ++ edges
  }

  /** The structural boundaries that fall inside the narrowed range, plus a uniform sample of
   * that range - which is where the narrowed model is defined and nowhere else. */
  private def narrowDays: Seq[Int] = {
    val random = new Random(2600)
    val span = VarkaChrono.NARROW_MAX_DAYS.toLong - VarkaChrono.NARROW_MIN_DAYS + 1
    val sampled = Seq.fill(20000)(
      (VarkaChrono.NARROW_MIN_DAYS + (random.nextDouble() * span).toLong).toInt)
    boundaryDays.filter(VarkaChrono.inNarrowRange) ++ sampled
  }

  test("the narrowed model matches LocalDate over every calendar boundary in its range") {
    val days = narrowDays
    assert(days.forall(VarkaChrono.inNarrowRange), "the set must stay inside the range")
    for (day <- days) {
      assert(VarkaChrono.narrowed(day) === reference(day), s"disagreed on day $day")
    }
  }

  test("the narrowed range's bounds are the ones the constants imply") {
    assert(VarkaChrono.NARROW_MIN_DAYS === -VarkaChrono.NARROW_BIAS)
    assert(VarkaChrono.NARROW_MAX_DAYS ===
      (1 << VarkaChrono.NARROW_ERA_K) - 1 - VarkaChrono.NARROW_BIAS)
    assert(!VarkaChrono.inNarrowRange(VarkaChrono.NARROW_MIN_DAYS - 1))
    assert(VarkaChrono.inNarrowRange(VarkaChrono.NARROW_MIN_DAYS))
    assert(VarkaChrono.inNarrowRange(VarkaChrono.NARROW_MAX_DAYS))
    assert(!VarkaChrono.inNarrowRange(VarkaChrono.NARROW_MAX_DAYS + 1))
    // The range must contain every date SQL can write, which is what makes the guard's
    // fallback a corner case rather than a common path.
    assert(VarkaChrono.inNarrowRange(LocalDate.of(1, 1, 1).toEpochDay.toInt))
    assert(VarkaChrono.inNarrowRange(LocalDate.of(9999, 12, 31).toEpochDay.toInt))
  }

  test("task 52: the column contract's bounds lie strictly inside the narrowed range") {
    // The compile-time range analysis starts every column at the contract and admits a
    // calendar node only if the shifted interval stays inside the narrowed range; both facts
    // it rests on are pinned here, with the slack on each side stated as a number so a plan
    // quoting it can be checked against this test.
    assert(VarkaChrono.CONTRACT_MIN_DAYS === LocalDate.of(1, 1, 1).toEpochDay.toInt)
    assert(VarkaChrono.CONTRACT_MAX_DAYS === LocalDate.of(9999, 12, 31).toEpochDay.toInt)
    assert(VarkaChrono.CONTRACT_MIN_DAYS === -719162)
    assert(VarkaChrono.CONTRACT_MAX_DAYS === 2932896)
    assert(VarkaChrono.NARROW_MIN_DAYS < VarkaChrono.CONTRACT_MIN_DAYS)
    assert(VarkaChrono.CONTRACT_MAX_DAYS < VarkaChrono.NARROW_MAX_DAYS)
    assert(VarkaChrono.NARROW_MIN_DAYS - VarkaChrono.CONTRACT_MIN_DAYS === -4675410)
    assert(VarkaChrono.NARROW_MAX_DAYS - VarkaChrono.CONTRACT_MAX_DAYS === 8449747)
  }

  test("the January turn is the same test on the day of year as on the March month") {
    // The year tail reads doy >= MARCH_TO_JANUARY_DAYS where the month and day-of-month tails
    // read marchMonth >= MARCH_YEAR_JANUARY (task 48). The two are one integer identity apart,
    // and 366 cases are cheaper to run than three lines of algebra are to trust. The identity
    // rests on the month magic being exact over this whole domain, so that is asserted here
    // too rather than taken from the constant's javadoc.
    for (dayOfYear <- 0 to 365) {
      val marchMonth = ((5 * dayOfYear + 2) * VarkaChrono.MONTH_M) >>> VarkaChrono.MONTH_K
      assert(marchMonth === (5 * dayOfYear + 2) / 153,
        s"the month magic is not exact at day of year $dayOfYear")
      assert(marchMonth <= 11, s"the March month left its domain at day of year $dayOfYear")
      assert((marchMonth >= VarkaChrono.MARCH_YEAR_JANUARY) ===
        (dayOfYear >= VarkaChrono.MARCH_TO_JANUARY_DAYS),
        s"the two January tests disagreed at day of year $dayOfYear")
    }
  }

  test("task 53: one affine numerator carries both the month and the day of month") {
    // The whole domain, for the reason the January-turn test above gives: 366 cases are
    // cheaper to run than three lines of algebra are to trust. What is asserted is not that
    // the new block is plausible but that it is *the same function* as the two forms this file
    // ships today - the month's magic multiply, and the day recovered by running the month
    // start forwards and subtracting. If they ever disagree, the emitter's lowering and its
    // scalar twin have diverged, which is the failure VarkaChrono exists to make impossible.
    var maxNumerator = 0
    for (dayOfYear <- 0 to 365) {
      val num = VarkaChrono.MONTH_NUM_M * dayOfYear + VarkaChrono.MONTH_NUM_ADD
      maxNumerator = math.max(maxNumerator, num)
      val monthIndex3 = num >>> VarkaChrono.MONTH_NUM_K
      val dayOfMonth0 = ((num & 0xFFFF) * VarkaChrono.DOM_M) >>> VarkaChrono.DOM_K
      val marchMonth = (5 * dayOfYear + 2) / 153
      assert(monthIndex3 - 3 === marchMonth,
        s"the 3-based month index disagreed with the shipped form at day of year $dayOfYear")
      assert(dayOfMonth0 === dayOfYear - (153 * marchMonth + 2) / 5,
        s"the day of month disagreed with the shipped form at day of year $dayOfYear")
      assert(monthIndex3 >= 3 && monthIndex3 <= 14,
        s"the month index left its domain at day of year $dayOfYear")
      // The same January turn as task 48's, restated on the new axis. Both must move together
      // or a year computed on one axis and a month on the other disagree by a year.
      assert((monthIndex3 >= VarkaChrono.MONTH3_JANUARY) ===
        (dayOfYear >= VarkaChrono.MARCH_TO_JANUARY_DAYS),
        s"the two January tests disagreed at day of year $dayOfYear")
    }
    // The bound that makes this expressible on an int lane at all, where the paper's era and
    // year steps are not: see PLAN_TASK_53.md 2.2.
    assert(maxNumerator === 979378)
    assert(maxNumerator < Int.MaxValue)
  }

  test("task 53: the day-of-month magic is exact over every value a 16-bit remainder can take") {
    // Not sampled. The remainder is whatever the numerator's low half happens to be, so the
    // domain is all 65536 values and checking all of them costs milliseconds. The product
    // bound is asserted with it, because exactness is worthless if the multiply overflows.
    var maxProduct = 0L
    for (x <- 0 to 65535) {
      assert(((x * VarkaChrono.DOM_M) >>> VarkaChrono.DOM_K) === x / VarkaChrono.MONTH_NUM_M,
        s"the /2141 magic is not exact at $x")
      maxProduct = math.max(maxProduct, x.toLong * VarkaChrono.DOM_M)
    }
    assert(maxProduct === 2054194575L)
    assert(maxProduct < Int.MaxValue.toLong, "the magic multiply must fit a signed int lane")
  }

  test("task 53: the month-start map is a shift, and agrees with the magic it replaces") {
    // Twelve cases, and the numerator's sign matters as much as the values: it never goes
    // negative, which is what lets the emitter use a logical shift rather than an arithmetic
    // one and stops the choice being a silent correctness question.
    for (monthIndex3 <- 3 to 14) {
      val numerator = VarkaChrono.MONTH_START_M * monthIndex3 - VarkaChrono.MONTH_START_SUB
      assert(numerator >= 0, s"the month-start numerator went negative at $monthIndex3")
      assert((numerator >>> VarkaChrono.MONTH_START_K) ===
        (153 * (monthIndex3 - 3) + 2) / 5,
        s"the month-start shift disagreed with the shipped magic at $monthIndex3")
    }
  }

  test("the leap-year hash is exact over its whole domain, and only there") {
    // A perfect hash is exact inside its domain and arbitrary one step past it, so the domain
    // is the whole contract and the only honest test of it is all of it: 102,500 years, which
    // costs milliseconds. Both ends are asserted, because the bound being tight is what makes
    // "a caller outside this range needs a different bias, not a correction" true.
    val lo = -VarkaChrono.YEAR_BIAS
    val hi = VarkaChrono.LEAP_HASH_MAX_BIASED_YEAR - VarkaChrono.YEAR_BIAS
    def reference(year: Int): Boolean =
      Math.floorMod(year, 4) == 0 &&
        (Math.floorMod(year, 100) != 0 || Math.floorMod(year, 400) == 0)
    var mismatches = 0
    var year = lo
    while (year <= hi) {
      if (VarkaChrono.isLeapYear(year) != reference(year)) {
        mismatches += 1
      }
      year += 1
    }
    assert(mismatches === 0, s"the hash disagreed with the Gregorian rule on $mismatches years")
    // The first year past the domain, where it is allowed to be - and in fact is - wrong. If
    // this ever starts agreeing, the constants moved and the domain must be re-derived.
    assert(VarkaChrono.isLeapYear(hi + 1) !== reference(hi + 1),
      "the hash is now correct one year past its stated domain, so the domain is stale")
    // The range the emitter actually needs, called out so a future widening of month
    // arithmetic trips here rather than in a differential.
    assert(lo <= -14848 && hi >= 35181,
      "the covered range no longer contains what add_months and the interval arithmetic reach")
  }

  test("the leap flag agrees with java.time over the calendar boundaries") {
    for (day <- boundaryDays) {
      val year = LocalDate.ofEpochDay(day.toLong).getYear
      assert(VarkaChrono.isLeapYear(year) === LocalDate.of(year, 1, 1).isLeapYear,
        s"disagreed with java.time on year $year")
    }
  }

  test("task 54: the Julian map agrees with LocalDate and with the century-then-year form " +
      "over a whole era") {
    // The map's two divisions have a bounded input - the day of era, 146097 values - so the
    // new part of the prefix is verified over its entire domain here, committed rather than
    // opt-in, and against both oracles at once: LocalDate, and the form this replaces. The era
    // starting 1 March 2000 is as good as any; the era step in front of both forms is shared
    // and unchanged, so one era is the whole domain.
    val start = LocalDate.of(2000, 3, 1).toEpochDay.toInt
    for (dayOfEra <- 0 until VarkaChrono.ERA_DAYS) {
      val day = start + dayOfEra
      val julian = VarkaChrono.narrowedJulian(day)
      assert(julian === reference(day), s"the Julian map disagreed with LocalDate on day $day")
      assert(julian === VarkaChrono.narrowedCenturyYear(day),
        s"the two prefix forms disagreed on day $day")
    }
    // The no-overflow conditions the constants' javadoc states, checked at the extreme rather
    // than trusted: the largest scaled day and the largest mapped count times their magics.
    val quadMax = 4 * (VarkaChrono.ERA_DAYS - 1) + VarkaChrono.QUAD_DAY_ADD
    assert(quadMax.toLong * VarkaChrono.JULIAN_CENTURY_M < (1L << 31))
    assert((quadMax + 4 * 3).toLong * VarkaChrono.JULIAN_YEAR_M < (1L << 31))
    // And that the shipped twin is one of the two, whichever the default says.
    val probe = LocalDate.of(2024, 2, 29).toEpochDay.toInt
    assert(VarkaChrono.narrowed(probe) === reference(probe))
  }

  test("task 42: the make_date year limits are the whole years of the narrow range") {
    val lo = LocalDate.ofEpochDay(VarkaChrono.NARROW_MIN_DAYS.toLong)
    val hi = LocalDate.ofEpochDay(VarkaChrono.NARROW_MAX_DAYS.toLong)
    assert(VarkaChrono.MAKE_DATE_MIN_YEAR === lo.getYear + 1)
    assert(VarkaChrono.MAKE_DATE_MAX_YEAR === hi.getYear - 1)
    // Every day of the limit years is inside the range the calendar lowering is exact over.
    assert(LocalDate.of(VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1).toEpochDay >=
      VarkaChrono.NARROW_MIN_DAYS)
    assert(LocalDate.of(VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31).toEpochDay <=
      VarkaChrono.NARROW_MAX_DAYS)
  }

  test("task 42: the scalar makeDate is LocalDate.of at the corners, and names its two " +
      "non-answers apart") {
    def ref(y: Int, m: Int, d: Int): Int =
      try LocalDate.of(y, m, d).toEpochDay.toInt
      catch { case _: java.time.DateTimeException => VarkaChrono.MAKE_DATE_INVALID }
    val cases = Seq(
      (2024, 2, 29), (2023, 2, 29), (1900, 2, 29), (2000, 2, 29), (2024, 2, 30),
      (2024, 4, 31), (2024, 4, 30), (2024, 12, 31), (2024, 12, 32), (2024, 13, 1),
      (2024, 0, 1), (2024, 1, 0), (2024, -1, 15), (2024, 6, -1), (1, 1, 1), (9999, 12, 31),
      (1970, 1, 1), (1969, 12, 31))
    for ((y, m, d) <- cases) {
      assert(VarkaChrono.makeDate(y, m, d) === ref(y, m, d), s"$y-$m-$d")
    }
    assert(VarkaChrono.makeDate(VarkaChrono.MAKE_DATE_MIN_YEAR - 1, 1, 1) ===
      VarkaChrono.MAKE_DATE_OUT_OF_RANGE)
    assert(VarkaChrono.makeDate(VarkaChrono.MAKE_DATE_MAX_YEAR + 1, 12, 31) ===
      VarkaChrono.MAKE_DATE_OUT_OF_RANGE)
    // An out-of-range year with an invalid month is out of range first: the kernel declines it.
    assert(VarkaChrono.makeDate(VarkaChrono.MAKE_DATE_MAX_YEAR + 1, 13, 1) ===
      VarkaChrono.MAKE_DATE_OUT_OF_RANGE)
  }

  test("task 42: the scalar makeDate round-trips every day of the covered years " +
      "(opt-in: -Dvarka.sweep=true)") {
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to run the exhaustive sweep")
    var day = LocalDate.of(VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1).toEpochDay.toInt
    val end = LocalDate.of(VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31).toEpochDay.toInt
    var mismatches = 0
    while (day <= end) {
      val date = LocalDate.ofEpochDay(day.toLong)
      if (VarkaChrono.makeDate(date.getYear, date.getMonthValue, date.getDayOfMonth) != day) {
        mismatches += 1
      }
      day += 1
    }
    assert(mismatches === 0)
  }

  test("task 37: the week magic is exact over the day-of-year domain and one past it") {
    // (dayOfYear - 1) / 7 for dayOfYear in 1..366 is x / 7 for x in 0..365; the magic holds
    // to 684 and fails at 685, which is the number to write down rather than "it works".
    for (x <- 0 to 684) {
      assert(((x * VarkaChrono.WEEK_M) >>> VarkaChrono.WEEK_K) === x / 7, s"not exact at $x")
    }
    assert(((685 * VarkaChrono.WEEK_M) >>> VarkaChrono.WEEK_K) !== 685 / 7)
    assert(684L * VarkaChrono.WEEK_M < Int.MaxValue.toLong)
  }

  test("task 37: the scalar weekOfYear is DateTimeUtils.getWeekOfYear at the ISO corners") {
    val days = Seq(
      LocalDate.of(2015, 12, 28), LocalDate.of(2016, 1, 1), LocalDate.of(2019, 12, 30),
      LocalDate.of(2020, 12, 31), LocalDate.of(2021, 1, 1), LocalDate.of(2004, 12, 31),
      LocalDate.of(2009, 12, 31), LocalDate.of(2026, 12, 31), LocalDate.of(1919, 12, 31),
      LocalDate.of(1969, 12, 31), LocalDate.of(1960, 1, 1), LocalDate.of(1, 1, 1),
      LocalDate.of(9999, 12, 31), LocalDate.of(1970, 1, 1), LocalDate.of(2000, 2, 29))
    for (d <- days) {
      val days = d.toEpochDay.toInt
      assert(VarkaChrono.weekOfYear(days) === DateTimeUtils.getWeekOfYear(days), d.toString)
    }
  }

  test("task 37: the scalar weekOfYear matches DateTimeUtils over the covered range " +
      "(opt-in: -Dvarka.sweep=true)") {
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to run the exhaustive sweep")
    // Three days in from either end: the Thursday of a day at the edge can lie past it, and
    // the decomposition is only defined inside the range (task 52's arm for the shift is
    // [-3, +3] for the same reason).
    var day = VarkaChrono.NARROW_MIN_DAYS + 3
    var mismatches = 0
    while (day <= VarkaChrono.NARROW_MAX_DAYS - 3) {
      if (VarkaChrono.weekOfYear(day) != DateTimeUtils.getWeekOfYear(day)) mismatches += 1
      day += 1
    }
    assert(mismatches === 0)
  }

  test("the exhaustive sweep (opt-in: -Dvarka.sweep=true)") {
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to run the exhaustive sweep")

    // Both prefix forms (task 54): the era step in front of them is shared, but the sweep is
    // the one place the whole chain is held to LocalDate over every covered day, and the
    // reference variant is kept live by being held to the same standard.
    var mismatches = 0
    var julianMismatches = 0
    var day = VarkaChrono.NARROW_MIN_DAYS
    while (day <= VarkaChrono.NARROW_MAX_DAYS) {
      val want = reference(day)
      if (VarkaChrono.narrowedCenturyYear(day) != want) {
        mismatches += 1
      }
      if (VarkaChrono.narrowedJulian(day) != want) {
        julianMismatches += 1
      }
      day += 1
    }
    assert(mismatches === 0, s"the model disagreed with LocalDate on $mismatches days")
    assert(julianMismatches === 0,
      s"the Julian map disagreed with LocalDate on $julianMismatches days")
  }
}
