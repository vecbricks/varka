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

/**
 * The civil-from-days decomposition {@code year}, {@code month}, {@code dayofmonth} and
 * {@code quarter} are lowered from (task 26), as scalar Java, plus every magic constant the
 * emitter loads. This class is three things at once and is written to be all three:
 *
 * <ol>
 *   <li><b>The derivation record.</b> Each constant is named, and the comment beside it says
 *       which division it stands for and why that division admits the form it does. The whole
 *       argument is in {@code sql/varka/plans/PLAN_TASK_26.md} section 1.</li>
 *   <li><b>The single source of truth for the emitter.</b> {@link VarkaLoopEmitter} loads these
 *       fields rather than repeating their values, so a constant cannot drift between the
 *       emitted bytecode and the model that is swept against {@code java.time}.</li>
 *   <li><b>The test oracle's twin.</b> The methods below are the exact lane arithmetic the
 *       emitter emits, one lane at a time, so a sweep over them is a sweep over the algorithm
 *       and any disagreement with the emitted kernel is an emission bug rather than an
 *       arithmetic one.</li>
 * </ol>
 *
 * <p><b>Why magic multiplies and not division.</b> {@code VectorOperators} has no multiply-high
 * on any lane type, so full-range Granlund-Montgomery division is inexpressible on int lanes;
 * only a range-narrowed magic works, where the value is shrunk until the correctness condition
 * {@code v * e < 2^k} and the no-overflow condition {@code v * M < 2^31} both hold in the low 32
 * bits {@code mul} returns (task 14's follow-up; see the {@code SKILLS.md} entry). Worst-case
 * {@code e ~ d} forces {@code 2^k > d * v}, hence {@code M ~ v}, hence {@code v < 46341}: an
 * <i>exact</i> magic exists on int lanes only for dividends under roughly 46000. The two large
 * divisors here are past that, so they use a round-down magic - which never overestimates the
 * quotient - followed by a bounded number of correction steps, each one compare and two
 * adjustments on a remainder the decomposition wants anyway.
 *
 * <p><b>The range, and why it is bounded.</b> {@link #narrowed} reaches the day of era with one
 * division and one correction, and is valid only over
 * {@link #NARROW_MIN_DAYS}..{@link #NARROW_MAX_DAYS} - years -12800 to 33134, which contains
 * every date SQL can write but is reachable past by {@code date_add}. The emitted form therefore
 * carries a guard and declines a batch it cannot compute. A variant that split the dividend and
 * so covered the whole {@code int} range without a guard was built and measured against this one
 * before being dropped: it cost 14 to 24%, and the numbers are in {@code PLAN_TASK_26.md}
 * section 11.2.
 *
 * <p><b>The calendar this decomposes into.</b> All of it works in a March-based year, where the
 * leap day is the last day rather than an interior one, so a year's length is a function of its
 * index alone. {@link #fromEra} converts back to January-based years at the end, which is the
 * {@code mp >= 10} adjustment.
 */
public final class VarkaChrono {

  private VarkaChrono() {}

  /** Days in a 400-year Gregorian era: 400 * 365 + 97 leap days. */
  public static final int ERA_DAYS = 146097;

  /** Days in the first three centuries of an era; the fourth has one more. */
  public static final int CENTURY_DAYS = 36524;

  /** Days from 0000-03-01 to 1970-01-01, the shift into March-based years. */
  public static final int MARCH_EPOCH_SHIFT = 719468;

  // --- The day-of-era step: one division, one correction, a bounded input range -------------

  /** How many eras {@link #NARROW_BIAS} adds; subtracted again when the year is assembled. */
  public static final int NARROW_ERA_BIAS = 32;

  /**
   * {@link #MARCH_EPOCH_SHIFT} plus {@link #NARROW_ERA_BIAS} whole eras. The extra eras are what
   * let the range reach back past year zero while the value stays non-negative, so the division
   * never has to round toward negative infinity.
   */
  public static final int NARROW_BIAS = MARCH_EPOCH_SHIFT + NARROW_ERA_BIAS * ERA_DAYS;

  /**
   * {@code floor(2^24 / 146097)}, rounded down so the quotient is never overestimated. The
   * shortfall is {@code w * (2^24 - M * d) / (d * 2^24) < 1} for every {@code w < 2^24}, so one
   * correction step recovers the exact quotient - and {@code M * w <= 114 * (2^24 - 1)} is
   * comfortably inside {@code 2^31}.
   */
  public static final int NARROW_ERA_M = 114;

  /** The shift paired with {@link #NARROW_ERA_M}; {@code w < 2^24} is what bounds the range. */
  public static final int NARROW_ERA_K = 24;

  /** The first day {@link #narrowed} is defined for: {@code -NARROW_BIAS}, i.e. 0000-03-01 less
   * {@link #NARROW_ERA_BIAS} eras. In calendar terms, 1 March -12800. */
  public static final int NARROW_MIN_DAYS = -NARROW_BIAS;

  /** The last day {@link #narrowed} is defined for: the largest day with {@code w < 2^24}. In
   * calendar terms, 15 August 33134. */
  public static final int NARROW_MAX_DAYS = (1 << NARROW_ERA_K) - 1 - NARROW_BIAS;

  /**
   * The first epoch day a date column can hold under the project's column contract: 0001-01-01,
   * the smallest date Spark SQL can write. The contract is what task 52's compile-time range
   * analysis starts from: a bare column lies in {@code [CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]},
   * every producer between it and a calendar node widens that interval by a bound the compiler
   * knows, and the calendar node fuses only if the result stays inside
   * {@code [NARROW_MIN_DAYS, NARROW_MAX_DAYS]}. Derived from {@link LocalDate} rather than typed
   * in, so the number cannot drift from the calendar fact it states.
   */
  public static final int CONTRACT_MIN_DAYS = (int) LocalDate.of(1, 1, 1).toEpochDay();

  /** The last epoch day under the column contract: 9999-12-31. See {@link #CONTRACT_MIN_DAYS}. */
  public static final int CONTRACT_MAX_DAYS = (int) LocalDate.of(9999, 12, 31).toEpochDay();

  /**
   * The years {@code make_date} builds inside the kernel (task 42): the whole calendar years of
   * the narrow range, so every date the node publishes lies in
   * {@code [NARROW_MIN_DAYS, NARROW_MAX_DAYS]} and a calendar node over it is admitted at compile
   * time; a year outside declines the batch to the row engine in both evaluation modes. Both
   * lie inside the range {@link #daysFromCivil} and {@link #isLeapYear} are exact over
   * (roughly -14848..35181 and -15200..87299). Derived from the range, not typed in.
   */
  public static final int MAKE_DATE_MIN_YEAR = LocalDate.ofEpochDay(NARROW_MIN_DAYS).getYear() + 1;

  /** The last whole year of the narrow range; see {@link #MAKE_DATE_MIN_YEAR}. */
  public static final int MAKE_DATE_MAX_YEAR = LocalDate.ofEpochDay(NARROW_MAX_DAYS).getYear() - 1;

  /** {@link #makeDate}'s answer for a month or day the calendar rejects: the null lane. */
  public static final int MAKE_DATE_INVALID = Integer.MIN_VALUE;

  /** {@link #makeDate}'s answer for a year outside the limits: the declined batch. */
  public static final int MAKE_DATE_OUT_OF_RANGE = Integer.MIN_VALUE + 1;

  /**
   * The largest day count {@code CAST(int AS INTERVAL DAY)} can produce (task 56):
   * {@code Long.MAX_VALUE / MICROS_PER_DAY}, since Spark builds the interval as
   * {@code Math.multiplyExact(days, MICROS_PER_DAY)} and throws a cast-overflow error - in every
   * evaluation mode - one past it. A kernel adding such a day count to a date must therefore see
   * only values in {@code [-INTERVAL_DAY_LIMIT_DAYS, INTERVAL_DAY_LIMIT_DAYS]}; the evaluator
   * checks the offset column against this bound per batch and declines the batch to the row
   * engine, which raises the same error, when a live lane is outside. Derived in source rather
   * than typed in (106751991) so it cannot drift from the fact it states.
   */
  public static final int INTERVAL_DAY_LIMIT_DAYS =
      (int) (Long.MAX_VALUE / java.util.concurrent.TimeUnit.DAYS.toMicros(1));

  // --- Day of era to the five fields ---------------------------------------

  /** {@code floor(2^28 / 36524)}, round-down; one correction, dividend at most 146096. */
  public static final int CENTURY_M = 7349;

  /** The shift paired with {@link #CENTURY_M}. */
  public static final int CENTURY_K = 28;

  /**
   * The magic for {@code / 365}, one above the round-up form: {@code ceil(2^24 / 365)} is
   * 45965 (with {@code e = 9}), and this is 45966 (with {@code e = 374}). Both are exact over
   * the dividend this sees; 45965 would be exact far further, to 1864135 against 45966's
   * 44858, and either would do. The number is what it is because that is what was derived,
   * swept and committed - do not "fix" it to the tighter ceil without re-running the sweep,
   * and do not copy 45966 as though it were {@code ceil}, because for another divisor the
   * extra one may put {@code e} past the bound.
   *
   * <p>What matters is the bound: {@code v * e < 2^k} is strict, so this is exact for every
   * dividend up to 44858. The dividend here is the day of century, at most 36524 - the era's
   * spilling last day, one past a plain century's 36523 - so this division needs no correction
   * at all. It is the split into centuries that buys that: on the day of era, 146096 wide, no
   * exact magic for 365 exists at any k.
   */
  public static final int YEAR_M = 45966;

  /** The shift paired with {@link #YEAR_M}. */
  public static final int YEAR_K = 24;

  /** Exact magic for {@code / 153} over a dividend of at most {@code 5 * 365 + 2}. */
  public static final int MONTH_M = 877241;

  /** The shift paired with {@link #MONTH_M}. */
  public static final int MONTH_K = 27;

  /** Exact magic for {@code / 5} over a dividend of at most {@code 153 * 11 + 2}. */
  public static final int DAY_M = 838861;

  /** The shift paired with {@link #DAY_M}. */
  public static final int DAY_K = 22;

  /** Exact magic for {@code / 3} over a dividend of at most 14. */
  public static final int QUARTER_M = 89478486;

  /** The shift paired with {@link #QUARTER_M}. */
  public static final int QUARTER_K = 28;

  /** The day of the March-based year on which January arrives, and the year number turns. */
  public static final int MARCH_YEAR_JANUARY = 10;

  /**
   * The same turn as {@link #MARCH_YEAR_JANUARY}, one step earlier in the chain: the
   * March-based day of year at which the March year has become January, which is the count of
   * days from 1 March through 31 December. Because {@code marchMonth = (5 * dayOfYear + 2)
   * / 153} is exact over this domain, {@code marchMonth >= 10} is {@code 5 * dayOfYear + 2
   * >= 1530}, that is {@code dayOfYear >= 305.6}, that is {@code dayOfYear >= 306} on
   * integers - an identity, not an approximation, and {@code VarkaChronoSuite} asserts it
   * over all 366 values of the domain. Task 48 reads the year's January bit from here so a
   * kernel computing the year alone never computes the month.
   *
   * <p>Task 34 reads the same threshold as a conversion rather than a bit: past it, the
   * March-based day of year becomes the January-based one by subtracting
   * {@code MARCH_TO_JANUARY_DAYS - 1}.
   */
  public static final int MARCH_TO_JANUARY_DAYS = 306;

  // --- Task 53: the Neri-Schneider month block ----------------------------------------------

  /**
   * Multiplier of the single affine numerator the month index and the day of month both come
   * out of (Neri-Schneider 2022, Example 10 and Equation 20; the transcription is in
   * {@code sql/varka/papers/}).
   *
   * <p>Where {@link #MONTH_M} computes the month with a magic multiply and the day of month is
   * then recovered by running {@link #DAY_M}'s magic <i>forwards</i> and subtracting, this
   * computes {@code num = 2141 * dayOfYear + 197913} once and takes the month out of its high
   * half and the day of month out of its low half. Same answers, fewer operations, and the
   * intermediate that survives is one the day tail can use directly rather than one it has to
   * invert.
   *
   * <p>Verified over the whole domain rather than cited: for all 366 values of the March-based
   * day of year, {@code (num >>> 16) - 3} equals {@code (5 * dayOfYear + 2) / 153} and
   * {@code (num & 0xFFFF) / 2141} equals {@code dayOfYear - (153 * marchMonth + 2) / 5} - the
   * two forms this file ships today. The numerator peaks at 979378, comfortably inside a
   * signed 32-bit lane, which is what makes the block expressible on {@code IntVector} where
   * the paper's era and year steps are not (see {@code PLAN_TASK_53.md} 2.2).
   */
  public static final int MONTH_NUM_M = 2141;

  /** The addend paired with {@link #MONTH_NUM_M}. */
  public static final int MONTH_NUM_ADD = 197913;

  /** The shift that takes the month index out of {@link #MONTH_NUM_M}'s numerator. */
  public static final int MONTH_NUM_K = 16;

  /**
   * Exact magic for {@code / 2141} over the numerator's low half, which turns the remainder
   * into the zero-based day of month. Exact at every one of the 65536 values a 16-bit
   * remainder can take - checked at all of them, not sampled - with a maximum product of
   * 2054194575, under {@code 2^31 - 1} with room to spare.
   */
  public static final int DOM_M = 31345;

  /** The shift paired with {@link #DOM_M}. */
  public static final int DOM_K = 26;

  /**
   * Exact magic for {@code / 7} over {@code 0..684}, the domain of {@code dayOfYear - 1}
   * (task 37): {@code (x * WEEK_M) >>> WEEK_K} is {@code x / 7} for every {@code x} up to 684
   * and wrong at 685, with a maximum in-domain product of 200,412, nowhere near {@code 2^31}.
   * The ISO week of a Thursday is {@code (januaryDayOfYear - 1) / 7 + 1}, so this is the
   * whole of {@code weekofyear}'s arithmetic past the day of year: no correction step, no
   * floorMod, because the dividend is never negative. Checked over the whole domain and one
   * past it in {@code VarkaChronoSuite}, not sampled.
   */
  public static final int WEEK_M = 293;

  /** The shift paired with {@link #WEEK_M}. */
  public static final int WEEK_K = 11;

  /**
   * The month-start map on the 3-based axis: {@code (979 * monthIndex3 - 2919) >>> 5}, equal to
   * {@link #DAY_M}'s {@code (153 * marchMonth + 2) / 5} at all twelve months. A shift rather
   * than a magic multiply, and its numerator runs from 18 to 10787 - never negative, so the
   * shift may be arithmetic or logical.
   */
  public static final int MONTH_START_M = 979;

  /** The subtrahend paired with {@link #MONTH_START_M}. */
  public static final int MONTH_START_SUB = 2919;

  /** The shift paired with {@link #MONTH_START_M}. */
  public static final int MONTH_START_K = 5;

  /**
   * The month index at which the March-based year has turned January, on Neri-Schneider's
   * 3-based axis where March is 3 and February is 14 - the counterpart of
   * {@link #MARCH_YEAR_JANUARY} on the 0-based axis this file ships today.
   *
   * <p>13 rather than 10 because the axis is offset by three, and the reported month is then
   * {@code monthIndex3} itself below the turn and {@code monthIndex3 - 12} at or above it -
   * one operation fewer than the 0-based form's {@code + 3} / {@code - 9}, which is why the
   * convention changes rather than being converted back.
   *
   * <p>It agrees with {@link #MARCH_TO_JANUARY_DAYS}, which is the reassuring part: over the
   * whole domain the values of {@code monthIndex3} at or after day 306 are exactly 13 and 14
   * and those before it are 3 through 12, so {@code monthIndex3 >= 13} and
   * {@code dayOfYear >= 306} are the same test on two axes - task 48's identity, restated.
   *
   * <p>Both axes exist at once until task 53's emitter commit: the emitter still reads
   * {@link #MARCH_YEAR_JANUARY} in five places, so it cannot move until they move with it.
   */
  public static final int MONTH3_JANUARY = 13;

  // --- Task 54: the Julian map, one division stage fewer ------------------------------------

  /**
   * The constant term of the scaled day of era, {@code 4 * dayOfEra + 3} (Ben Joffe, after
   * Neri-Schneider's {@code 4 * N + 3}; the review is in {@code SKILLS.md}, "The Julian map").
   * Scaling by four makes {@code / 146097} the century (146097 / 4 is 36524.25, the mean
   * century) and puts the era's last day, 29 February of its 400th year, in century 3 rather
   * than 4 - the {@code + 3} is what does that - so the {@code century == 4} fold of the
   * century-then-year form has nothing left to fold.
   */
  public static final int QUAD_DAY_ADD = 3;

  /**
   * {@code floor(2^28 / 146097)}, round-down, for the century out of the scaled day of era.
   * The dividend is at most {@code 4 * 146096 + 3 = 584387}, and {@code 584387 * 1837} is
   * 1073518919, inside {@code 2^31}; the shortfall is under one for every dividend in range,
   * so one carry recovers the exact quotient. Checked over all 146097 days of an era rather
   * than argued: 46 of them need the carry, none needs two.
   */
  public static final int JULIAN_CENTURY_M = 1837;
  /** The shift paired with {@link #JULIAN_CENTURY_M}. */
  public static final int JULIAN_CENTURY_K = 28;

  /** Days in four Julian years, the cycle the map turns the Gregorian count into. */
  public static final int JULIAN_CYCLE_DAYS = 1461;

  /**
   * {@code floor(2^22 / 1461)}, round-down, for the year of era out of the mapped count. The
   * mapped count is at most {@code 584387 + 4 * 3 = 584399}, and {@code 584399 * 2870} is
   * 1677225130, inside {@code 2^31}; the shortfall is under one over the whole range, so one
   * carry recovers the exact quotient. Checked over all 146097 days of an era: 8627 of them
   * need the carry, none needs two.
   *
   * <p>Why this replaces two steps rather than one. In the mapped count every fourth year is
   * leap without exception, so the remainder of this division, shifted right by two, is the
   * March-based day of year with 29 February right by construction: no {@code / 365}, no
   * underflow correction, no leap test in the prefix at all.
   */
  public static final int JULIAN_YEAR_M = 2870;
  /** The shift paired with {@link #JULIAN_YEAR_M}. */
  public static final int JULIAN_YEAR_K = 22;

  // --- Task 40: the inverse direction, and the month arithmetic built on it ------------------

  /**
   * A year bias making a reported year non-negative over the range {@link #emitDaysFromCivil}
   * actually has to cover, so a division by 100 or 400 can use a magic multiply. That range is
   * wider than task 26's narrow day range: {@code add_months}/{@code date +- INTERVAL n
   * MONTH/YEAR} can push a year up to {@code MONTH_ARITH_MAX_MONTHS}/12 (about 2047 years)
   * past either end of it, so the covered year range is roughly -14848..35181, and 15200 - a
   * multiple of 400, so it changes neither leapness nor which 400-year cycle a year falls in -
   * is the smallest such multiple that keeps the biased value non-negative throughout.
   */
  public static final int YEAR_BIAS = 15200;

  /**
   * {@code floor(2^24 / 400)}, which is also {@code floor(2^22 / 100)} - the two divisors are in
   * a 1:4 ratio matching the shift, so one magic constant serves both {@link #YEAR_CENTURY_K}
   * and {@link #YEAR_QUATERCENTENNIAL_K}.
   *
   * <p><b>This is a round-down magic, not an exact one - unlike task 34's leap flag, which
   * covers only task 26's narrow day range and needs no correction.</b> The first version of
   * this class claimed exactness "to 199728" and was wrong: that bound came from checking
   * {@code (v * M) >> k == v / d} with arbitrary-precision arithmetic, which is the right check
   * for the shift but silently assumes the multiply itself does not overflow. The emitter's
   * lanes are 32-bit and {@code LSHR} is unsigned, so the multiply is safe up to {@code v * M <
   * 2^32}, not {@code 2^31} - but the biased year here reaches about 50381, and {@code 50381 *
   * 167773} is over four billion either way. The wrong constant produced a silently wrong
   * {@code era} for exactly the inputs task 40's own tests reached during development (a
   * four-digit year plus a multi-century month offset) and nothing smaller - the failure was
   * findable only by testing the actual range this class has to cover, not a plausible-looking
   * subrange of it. One correction step (the same shape {@link #CENTURY_M} already uses) fixes
   * it: {@code floor(v / d)} from this magic is short by at most one for both divisors over
   * every dividend the callers below feed it.
   */
  public static final int YEAR_CENTURY_M = 41943;

  /** The shift for {@code / 100} paired with {@link #YEAR_CENTURY_M}; one correction needed. */
  public static final int YEAR_CENTURY_K = 22;

  /** The shift for {@code / 400} paired with {@link #YEAR_CENTURY_M}; one correction needed. */
  public static final int YEAR_QUATERCENTENNIAL_K = 24;

  // --- The leap flag, as a perfect hash rather than two divisions ---------------------------

  /**
   * Multiplier of Falk Huffner's three-instruction leap-year test, which replaces the usual
   * pair of magic divisions ({@code % 100} and {@code % 400}, a correction carry each) with a
   * multiply, a mask and one unsigned compare: a year is leap exactly when
   * {@code ((y * LEAP_HASH_M) & LEAP_HASH_MASK) <= LEAP_HASH_MAX} as unsigned 32-bit
   * arithmetic. It is a perfect hash, not an approximation - it is exact over its whole domain
   * and arbitrary one step past it - so the domain is the whole contract, and
   * {@link #LEAP_HASH_MAX_BIASED_YEAR} states it.
   *
   * <p><b>The multiply overflows a 32-bit lane, deliberately.</b> The identity is defined
   * modulo 2^32, which is exactly what an {@code int} multiply computes, so the wrap is the
   * mechanism rather than a bug to be fixed.
   *
   * <p><b>The comparison must be unsigned.</b> {@link #LEAP_HASH_MASK} keeps bits 30 and 31, so
   * a little over half of the domain's years leave a negative {@code int}, and a signed compare
   * would call them leap. The emitter uses {@code VectorOperators.ULE} for this reason;
   * {@link #isLeapYear} uses {@link Integer#compareUnsigned}.
   */
  public static final int LEAP_HASH_M = 1073750999;

  /** Mask paired with {@link #LEAP_HASH_M}: {@code 0xC001F00F}, negative as a signed
   * {@code int}, which is why its compare is unsigned. */
  public static final int LEAP_HASH_MASK = 0xC001F00F;

  /** Threshold paired with {@link #LEAP_HASH_M}; the test is {@code <=}, unsigned. */
  public static final int LEAP_HASH_MAX = 126976;

  /**
   * The largest biased year {@link #LEAP_HASH_M} is exact for, and the bound is tight: 102500
   * is the first year the hash gets wrong. {@link #isLeapYear} biases by {@link #YEAR_BIAS},
   * so the covered reported years are {@code -YEAR_BIAS ..
   * LEAP_HASH_MAX_BIASED_YEAR - YEAR_BIAS}, that is -15200..87299 - wider than the roughly
   * -14848..35181 that {@code add_months} and the interval arithmetic can reach, which is the
   * range this has to cover.
   */
  public static final int LEAP_HASH_MAX_BIASED_YEAR = 102499;

  /**
   * Whether {@code year} is a leap year in the proleptic Gregorian calendar - the scalar twin
   * of the emitter's leap flag, computing it the same way so that a disagreement between the
   * two is an emission bug rather than an arithmetic one.
   *
   * <p>{@link #YEAR_BIAS} is a multiple of 400, so biasing changes neither leapness nor which
   * 400-year cycle a year falls in; it is here only to make the hash's input non-negative.
   * Defined for reported years {@code -YEAR_BIAS} through
   * {@code LEAP_HASH_MAX_BIASED_YEAR - YEAR_BIAS} and genuinely undefined outside that - see
   * {@link #LEAP_HASH_MAX_BIASED_YEAR}.
   */
  public static boolean isLeapYear(int year) {
    int hashed = (year + YEAR_BIAS) * LEAP_HASH_M & LEAP_HASH_MASK;
    return Integer.compareUnsigned(hashed, LEAP_HASH_MAX) <= 0;
  }

  /**
   * Exact magic for {@code / 12} (task 40's month arithmetic), over the dividend
   * {@code (month - 1) + monthsOffset + MONTH_ARITH_BIAS} - kept small by construction rather
   * than folding the year in, which would put the dividend near 400,000: past the ~46341 bound
   * an exact magic needs, and past the ~160,000 a round-down-plus-one-correction reaches.
   */
  public static final int MONTH_ARITH_M = 43691;

  /** The shift paired with {@link #MONTH_ARITH_M}; exact far past what {@link #MONTH_ARITH_M}
   * needs to stay inside {@code 2^31} for, which is the tighter of the two bounds. */
  public static final int MONTH_ARITH_K = 19;

  /** Whole years of headroom the month-arithmetic dividend is biased by, so it stays
   * non-negative for the most negative literal {@link #MONTH_ARITH_MIN_MONTHS} allows. */
  public static final int MONTH_ARITH_BIAS = 12 * 2048;

  /**
   * The largest {@code numMonths}/{@code interval} literal the emitter's magic multiply covers,
   * derived from {@code v * MONTH_ARITH_M < 2^31}: the dividend is
   * {@code (month - 1) + months + MONTH_ARITH_BIAS} with {@code month - 1} up to 11, so
   * {@code months} up to {@code floor((2^31 - 1) / MONTH_ARITH_M) - MONTH_ARITH_BIAS - 11}.
   * About 2000 years; a literal past this is declined rather than computed wrongly.
   */
  public static final int MONTH_ARITH_MAX_MONTHS = 24564;

  /** The smallest {@code numMonths}/{@code interval} literal covered - the negative mirror of
   * {@link #MONTH_ARITH_MAX_MONTHS}, bound only by {@link #MONTH_ARITH_BIAS} itself since
   * {@code month - 1} is never negative. */
  public static final int MONTH_ARITH_MIN_MONTHS = -MONTH_ARITH_BIAS;

  /**
   * The five calendar fields one decomposition yields, in the order the emitter's per-field
   * tails branch off the shared work.
   *
   * @param year the proleptic Gregorian year, as {@code java.time.LocalDate#getYear} gives it.
   * @param month 1-12.
   * @param dayOfMonth 1-31.
   * @param quarter 1-4.
   * @param dayOfYear the January-based day of year, 1-365 or 1-366.
   */
  public record Fields(int year, int month, int dayOfMonth, int quarter, int dayOfYear) {}

  /** Whether {@link #narrowed} is defined for {@code days} - the guard the emitted kernel
   * evaluates per lane when the narrowed lowering is in use. */
  public static boolean inNarrowRange(int days) {
    return days >= NARROW_MIN_DAYS && days <= NARROW_MAX_DAYS;
  }

  /**
   * The narrowed decomposition, through the prefix form the emitter ships by default
   * ({@link VarkaEmitOptions#DEFAULTS}). Undefined - not merely inaccurate - outside
   * {@link #inNarrowRange}, which is why the emitted form carries a guard and the batch falls
   * back to the row engine rather than publishing whatever this returns.
   */
  public static Fields narrowed(int days) {
    return VarkaEmitOptions.DEFAULTS.julianMap() ? narrowedJulian(days)
        : narrowedCenturyYear(days);
  }

  /** {@link #narrowed} through the century-then-year split (task 26). */
  public static Fields narrowedCenturyYear(int days) {
    return fromEra(eraOf(days), dayOfEraOf(days));
  }

  /** {@link #narrowed} through the Julian map (task 54). */
  public static Fields narrowedJulian(int days) {
    return fromEraJulian(eraOf(days), dayOfEraOf(days));
  }

  private static int eraOf(int days) {
    int w = days + NARROW_BIAS;
    int era = (w * NARROW_ERA_M) >>> NARROW_ERA_K;
    int rem = w - era * ERA_DAYS;
    if (rem >= ERA_DAYS) {
      era++;
    }
    return era - NARROW_ERA_BIAS;
  }

  private static int dayOfEraOf(int days) {
    int w = days + NARROW_BIAS;
    int era = (w * NARROW_ERA_M) >>> NARROW_ERA_K;
    int rem = w - era * ERA_DAYS;
    if (rem >= ERA_DAYS) {
      rem -= ERA_DAYS;
    }
    return rem;
  }

  /**
   * Day of era to the five fields - the half whose input domain ({@code [0, 146096]}) is small
   * enough to verify exhaustively on its own.
   *
   * <p>Two overshoot fixes earn their place here. The century magic can land on century 4,
   * which exists for exactly one day of each era (the fourth century holds the era's extra leap
   * day); it is folded back into century 3. And the exact {@code / 365} ignores leap days, so
   * it can name the next year when the day of century falls in one - detected by a negative day
   * of year, and undone by giving the day back, one more when the year we step back into is a
   * leap year.
   */
  private static Fields fromEra(int era, int dayOfEra) {
    int century = (dayOfEra * CENTURY_M) >>> CENTURY_K;
    int dayOfCentury = dayOfEra - century * CENTURY_DAYS;
    if (dayOfCentury >= CENTURY_DAYS) {
      century++;
      dayOfCentury -= CENTURY_DAYS;
    }
    if (century == 4) {
      century = 3;
      dayOfCentury += CENTURY_DAYS;
    }
    int yearOfCentury = (dayOfCentury * YEAR_M) >>> YEAR_K;
    int dayOfYear = dayOfCentury - (365 * yearOfCentury + (yearOfCentury >>> 2));
    if (dayOfYear < 0) {
      dayOfYear += 365 + ((yearOfCentury & 3) == 0 ? 1 : 0);
      yearOfCentury--;
    }
    return fields(era, 100 * century + yearOfCentury, dayOfYear);
  }

  /**
   * Day of era to the five fields through the Julian map (task 54): the same input domain as
   * {@link #fromEra}, one division stage fewer, and the exact lane arithmetic the emitter
   * emits under {@link VarkaEmitOptions#julianMap}. Scale the day by four, take the century by
   * one round-down magic and a carry, add four back per century, and the count now lives in a
   * calendar where every fourth year is leap without exception - so one more magic and carry
   * gives the year of era, and the remainder shifted right by two is the day of year with the
   * leap day in the right place. Neither the {@code century == 4} fold nor the year-step
   * underflow correction of {@link #fromEra} exists here; see {@link #QUAD_DAY_ADD} and
   * {@link #JULIAN_YEAR_M} for why.
   */
  private static Fields fromEraJulian(int era, int dayOfEra) {
    int quadDays = 4 * dayOfEra + QUAD_DAY_ADD;
    int century = (quadDays * JULIAN_CENTURY_M) >>> JULIAN_CENTURY_K;
    int quadRem = quadDays - century * ERA_DAYS;
    if (quadRem >= ERA_DAYS) {
      century++;
    }
    int julian = quadDays + 4 * century;
    int yearOfEra = (julian * JULIAN_YEAR_M) >>> JULIAN_YEAR_K;
    int rem = julian - yearOfEra * JULIAN_CYCLE_DAYS;
    if (rem >= JULIAN_CYCLE_DAYS) {
      yearOfEra++;
      rem -= JULIAN_CYCLE_DAYS;
    }
    return fields(era, yearOfEra, rem >>> 2);
  }

  /**
   * The tail both prefix forms share, from the year of era and the March-based day of year.
   * Task 53: one affine numerator carries both the month and the day of month, where the
   * 0-based form needed a magic multiply for the month and then DAY_M's magic run forwards to
   * recover the day.
   */
  private static Fields fields(int era, int yearOfEra, int dayOfYear) {
    int monthNumerator = MONTH_NUM_M * dayOfYear + MONTH_NUM_ADD;
    int monthIndex3 = monthNumerator >>> MONTH_NUM_K;
    int dayOfMonth = ((((monthNumerator & 0xFFFF) * DOM_M) >>> DOM_K)) + 1;
    int month = monthIndex3 < MONTH3_JANUARY ? monthIndex3 : monthIndex3 - 12;
    int year = 400 * era + yearOfEra + (dayOfYear >= MARCH_TO_JANUARY_DAYS ? 1 : 0);
    int quarter = ((month + 2) * QUARTER_M) >>> QUARTER_K;
    int januaryDayOfYear = dayOfYear >= MARCH_TO_JANUARY_DAYS
        ? dayOfYear - (MARCH_TO_JANUARY_DAYS - 1)
        : dayOfYear + MARCH_DAY_OF_YEAR + (isLeapYear(year) ? 1 : 0);
    return new Fields(year, month, dayOfMonth, quarter, januaryDayOfYear);
  }

  // --- The ISO week, by the Thursday rule (task 37) ------------------------------------------

  /**
   * Spark's {@code weekofyear} as the emitter computes it, over {@link #narrowed}'s fields:
   * the day is moved to the Thursday of its Monday-based week, {@code t = d + 3 - weekday0},
   * and the week is {@code (januaryDayOfYear(t) - 1) / 7 + 1}, since a Thursday's week is
   * always in the Thursday's own year. Both year-boundary corrections of the textbook rule
   * vanish by construction. The scalar twin of {@code emitChronoWeekOfYear}, held to
   * {@code DateTimeUtils.getWeekOfYear} over the whole narrow range by the suite's sweep.
   * {@code weekday0} is {@code floorMod(d + 3, 7)} because 1970-01-01 was a Thursday.
   */
  public static int weekOfYear(int days) {
    int weekday0 = Math.floorMod(days + 3, 7);
    int thursday = days + 3 - weekday0;
    int x = narrowed(thursday).dayOfYear() - 1;
    return ((x * WEEK_M) >>> WEEK_K) + 1;
  }

  // --- The January-based day of year, and the leap flag it needs (task 34) ------------------

  /**
   * The January-based day of year of 1 March in a common year ({@code 31 + 28 + 1}). A leap
   * year adds one, because its extra day (29 February) falls before March.
   */
  public static final int MARCH_DAY_OF_YEAR = 60;

  /**
   * Task 40: Hinnant's {@code days_from_civil}, the exact inverse of {@link #narrowed}, over a
   * biased (non-negative) March-based year. {@code / 4} is a shift and {@code / 5} (inside
   * {@code dayOfYear}) is an exact magic multiply over its small dividend, the same one
   * {@link #narrowed}'s day tail uses; {@code / 400} and {@code / 100} are round-down magics
   * with one correction each, {@link #YEAR_CENTURY_M}'s javadoc records why an exact one does
   * not reach far enough here even though the dividend (up to about 50381) is smaller than
   * task 26's forward-direction ones.
   *
   * <p>{@code month} must be 1-12 and {@code dayOfMonth} the already-clamped day; this method
   * does no clamping itself; {@link VarkaLoopEmitter}'s {@code emitAddMonths} does the clamp
   * before calling the equivalent lane-wise sequence. Round-trips with {@link #narrowed} over
   * every day from year 1 to year 9999, and over the wider year range {@code emitAddMonths}'s
   * month arithmetic can reach - see {@code verify_days_from_civil.py} and
   * {@code PLAN_TASK_40.md}.
   */
  /**
   * Spark's {@code make_date} as the emitter computes it (task 42), the scalar twin of the
   * kernel's arm: the month clamped into 1..12 for the length test, the length as the closed
   * form {@code 30 | (m ^ (m >>> 3))} except February's {@code 28 + leap}, validity as
   * "month in 1..12 and day in 1..length", and {@link #daysFromCivil} over the valid triple.
   * Returns {@link #MAKE_DATE_OUT_OF_RANGE} for a year outside
   * {@code [MAKE_DATE_MIN_YEAR, MAKE_DATE_MAX_YEAR]} (the batch declines) and
   * {@link #MAKE_DATE_INVALID} for an invalid month or day (a null in non-ANSI mode, a decline
   * in ANSI), in that order of precedence, as the kernel's two masks are.
   */
  public static int makeDate(int year, int month, int dayOfMonth) {
    if (year < MAKE_DATE_MIN_YEAR || year > MAKE_DATE_MAX_YEAR) {
      return MAKE_DATE_OUT_OF_RANGE;
    }
    int clamped = Math.min(Math.max(month, 1), 12);
    int length = clamped == 2
        ? 28 + (isLeapYear(year) ? 1 : 0)
        : (30 | (clamped ^ (clamped >>> 3)));
    boolean valid = month >= 1 && month <= 12 && dayOfMonth >= 1 && dayOfMonth <= length;
    return valid ? daysFromCivil(year, month, dayOfMonth) : MAKE_DATE_INVALID;
  }

  public static int daysFromCivil(int year, int month, int dayOfMonth) {
    int marchYear = year - (month <= 2 ? 1 : 0);
    int biased = marchYear + YEAR_BIAS;
    int era = (biased * YEAR_CENTURY_M) >>> YEAR_QUATERCENTENNIAL_K;
    int yearOfEra = biased - era * 400;
    if (yearOfEra >= 400) {
      era++;
      yearOfEra -= 400;
    }
    int marchMonth = month + (month <= 2 ? 9 : -3);
    int dayOfYear = (((153 * marchMonth + 2) * DAY_M) >>> DAY_K) + dayOfMonth - 1;
    int centuryOfEra = (yearOfEra * YEAR_CENTURY_M) >>> YEAR_CENTURY_K;
    if (yearOfEra - centuryOfEra * 100 >= 100) {
      centuryOfEra++;
    }
    int dayOfEra = yearOfEra * 365 + (yearOfEra >>> 2) - centuryOfEra + dayOfYear;
    return (era - YEAR_BIAS / 400) * ERA_DAYS + dayOfEra - MARCH_EPOCH_SHIFT;
  }
}
