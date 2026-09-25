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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncLevel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.Slots.FragmentKey;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.Slots.fragmentKey;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitBudget.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorWalk.*;

import java.lang.classfile.CodeBuilder;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDivisionLowering.Divider;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Chrono;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfMonth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LastDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MakeDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Month;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Quarter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ThursdayOf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDateDynamic;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * The calendar lowerings: every node that turns a day count into civil fields or back.
 *
 * <p>The extractions ({@code year}, {@code month}, {@code day}, {@code quarter},
 * {@code dayofyear}, {@code weekofyear}, {@code last_day}, {@code trunc}) share one prefix,
 * {@link #emitChronoPrefix}: the era, year-of-era and March-based month decomposition of the
 * day count, run once per lane group and left in the {@code chronoTmp} locals, from which each
 * field's tail reads what it needs. {@code add_months} and {@code make_date} run the prefix or
 * its inverse ({@link #emitDaysFromCivil}) and recompose. The day-of-week family needs no
 * prefix: a floor-mod-7 of the day count and a per-node offset. All of it is int-lane by
 * construction, and its divisions are by constants whose dividends the prefix bounds, which is
 * what lets each site name a {@link ChronoDivide} - a divisor with the magic pair that stands
 * in for it - rather than a general division.
 */
final class VarkaChronoLowering {

  private VarkaChronoLowering() {
  }

  /**
   * {@code make_date(year, month, day)} (see PLAN_TASK_42.md 3.1). The three inputs go to slots;
   * the month is clamped into 1..12 for the length test; the length is the closed form
   * {@code 30 | (mc - (mc >>> 3))} - equal to the review's {@code 30 | (mc ^ (mc >>> 3))} on 1..12
   * without a vector XOR - blended with {@code 28 + leap} where the clamped month is 2;
   * {@code valid} is month in 1..12 and day in 1..length, {@code okY} the year inside
   * {@link VarkaChrono#MAKE_DATE_MIN_YEAR} .. {@link VarkaChrono#MAKE_DATE_MAX_YEAR}. Two masks,
   * two destinations: {@code !okY}, plus {@code !valid} under ANSI, goes to the guard accumulator
   * and declines the batch; under the NULL form {@code valid} is ANDed into the node's own validity
   * word instead. The value is {@code emitDaysFromCivil} over the year, the clamped month and the
   * day, garbage wherever a mask said so - a null lane's data is undefined and a declined batch is
   * recomputed whole. The node always computes its own word (the AND of its inputs', then the
   * validity), so the guard's AND sees the inputs' word.
   */
  static void emitMakeDate(CodeBuilder cb, MakeDate n, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    analysis.lane.requireInt(n);
    int[] t = s.makeDateTmp.get(n);
    int year = t[0];
    int month = t[1];
    int day = t[2];
    int clamped = t[3];
    int length = t[4];
    int valid = t[5];
    int okY = t[6];
    emitValue(cb, n.year(), dense, analysis, s, computed);
    cb.astore(year);
    emitValue(cb, n.month(), dense, analysis, s, computed);
    cb.astore(month);
    emitValue(cb, n.day(), dense, analysis, s, computed);
    cb.astore(day);
    line(cb, analysis, n);
    // clamped = min(max(m, 1), 12)
    cb.aload(month);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "max", LANEWISE_VI);
    cb.loadConstant(12);
    cb.invokevirtual(INT_VECTOR, "min", LANEWISE_VI);
    cb.astore(clamped);
    // length = blend(30 | (mc - (mc >>> 3)), 28 + L, mc == 2)
    cb.aload(clamped);
    cb.aload(clamped);
    emitShift(cb, "LSHR", 3);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.loadConstant(30);
    cb.invokevirtual(INT_VECTOR, "or", LANEWISE_VI);
    cb.aload(s.species);
    cb.loadConstant(28);
    cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
    cb.loadConstant(1);
    emitLeapFlag(cb, year);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    cb.aload(clamped);
    cb.getstatic(VECTOR_OPERATORS, "EQ", VO_COMPARISON);
    cb.loadConstant(2);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(INT_VECTOR, "blend", BLEND);
    cb.astore(length);
    // valid = (1 <= m <= 12) & (1 <= d <= length)
    cb.aload(month);
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.aload(month);
    cb.getstatic(VECTOR_OPERATORS, "LE", VO_COMPARISON);
    cb.loadConstant(12);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    cb.aload(day);
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    cb.aload(day);
    cb.getstatic(VECTOR_OPERATORS, "LE", VO_COMPARISON);
    cb.aload(length);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VV);
    cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    cb.astore(valid);
    // okY = MIN_YEAR <= y <= MAX_YEAR
    cb.aload(year);
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(VarkaChrono.MAKE_DATE_MIN_YEAR);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.aload(year);
    cb.getstatic(VECTOR_OPERATORS, "LE", VO_COMPARISON);
    cb.loadConstant(VarkaChrono.MAKE_DATE_MAX_YEAR);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    cb.astore(okY);
    // The node's word: the inputs' AND, in a masked body. Gated on `ownWord` like every other arm
    // rather than on `!dense` alone: liveness demands this word unconditionally today (the guard
    // below reads it), so the two conditions agree, but a rule that stopped demanding it - a
    // conditional guard, which is a possible future direction - would otherwise leave the store in
    // with no reader and `assertWordsLive` would refuse a correct emission.
    Integer own = dense ? null : s.wordRef.get(n);
    boolean ownLive = !dense && s.ownWord.contains(n);
    if (ownLive) {
      loadWord(cb, s, s.wordRef.get(n.year()));
      loadWord(cb, s, s.wordRef.get(n.month()));
      cb.land();
      loadWord(cb, s, s.wordRef.get(n.day()));
      cb.land();
      storeWord(cb, s, own);
    }
    // The decline mask: a year outside the limits, plus an invalid date under ANSI.
    cb.aload(okY);
    cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
    if (n.failOnError()) {
      cb.aload(valid);
      cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
      cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
    }
    emitGuardCollect(cb, n, own, dense, analysis, s);
    // The value, over the clamped month; garbage where a mask said so.
    emitDaysFromCivil(cb, year, clamped, day, t[7], t[8], t[9], t[10], t[11], t[12], t[13],
        t[14], t[15], t[16], t[17], analysis.divider);
    // Under the NULL form an invalid date is a null output: the validity joins the word. This
    // is the second of the emitter's two places that narrow a word after storing it; the other
    // is the NULL arm of {@code emitOverflowMask}, whose javadoc says why they are not shared.
    if (ownLive && !n.failOnError()) {
      loadWord(cb, s, own);
      cb.aload(valid);
      cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);
      cb.land();
      storeWord(cb, s, own);
    }
  }

  /**
   * Consumes the child's {@code IntVector} on the stack and leaves {@code floorMod(v, 7)}, full
   * range. The shipped variant (the follow-up) is two 15-bit digit-sum folds (
   * {@code 2^15 = 1 mod 7} ) followed by Granlund-Montgomery magic division: the folds leave
   * {@code v <= 32771} (unsigned reading), the +3-where-negative fixup ( {@code 2^32 = 4 mod 7} )
   * raises that to at most 32774, and in that range the magic is exact in the <i>low</i> 32 bits -
   * with {@code M = ceil(2^18 / 7) = 37450} and {@code e = 7 * M - 2^18 = 6}, {@code v * e < 2^18}
   * makes {@code q = (v * M) >>> 18} exactly {@code v / 7}, and {@code v * M < 2^31} keeps the
   * low-half multiply from overflowing, so {@code r = v - q * 7} needs no final fixup at all. The
   * multiply-high the classic trick wants is not expressible in the Vector API; pre-folding makes
   * the low half sufficient. Measured 1.6-1.8x the digit sum at buffer level and a ~10-op-smaller
   * loop method, which also shortens the per-task JIT warm-up (PLAN_TASK_14.md 7.5). The full digit
   * sum behind {@link VarkaEmitOptions.FloorMod7#DIGIT_SUM} and the lanewise DIV behind
   * {@link VarkaEmitOptions.FloorMod7#DIV} are the reference variants the parity benchmark prices
   * this one against.
   *
   * <p>Slot contract: {@code node}'s {@code dowTmp} entry supplies exactly the two scratch
   * locals this method uses as its own working storage ({@code tmp[0]} for the input value,
   * {@code tmp[1]} for the fold) - it touches no other local of the caller's. A caller needing
   * the pre-mod value again afterward (as {@link VarkaVectorIR.NextDay} does) must keep its
   * own copy some other way, since neither slot survives this call for that purpose.
   */
  static void emitFloorMod7(
      CodeBuilder cb, VarkaVectorIR node, Analysis analysis, Slots s) {
    int[] tmp = s.dowTmp.get(node);
    int orig = tmp[0];
    int fold = tmp[1];
    cb.astore(orig);
    if (analysis.options.floorMod7() == VarkaEmitOptions.FloorMod7.DIV) {
      // r = v - (v / 7) * 7; r += 7 where r < 0.
      cb.aload(orig);
      cb.aload(orig);
      cb.loadConstant(7);
      cb.invokevirtual(INT_VECTOR, "div", LANEWISE_VI);
      cb.loadConstant(7);
      cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
      cb.astore(fold);
      cb.aload(fold);
      cb.loadConstant(7);
      cb.aload(fold);
      cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
      cb.loadConstant(0);
      cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
      return;
    }
    if (analysis.options.floorMod7() == VarkaEmitOptions.FloorMod7.DIGIT_SUM) {
      // The shipped variant: folds of two 15-bit halves, one 6-bit, three 3-bit.
      emitFold(cb, orig, fold, 0x7FFF, 15);
      emitFold(cb, fold, fold, 0x7FFF, 15);
      emitFold(cb, fold, fold, 63, 6);
      emitFold(cb, fold, fold, 7, 3);
      emitFold(cb, fold, fold, 7, 3);
      emitFold(cb, fold, fold, 7, 3);
      // s += 3 where the original value was negative.
      cb.aload(fold);
      cb.loadConstant(3);
      cb.aload(orig);
      cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
      cb.loadConstant(0);
      cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
      // One conditional subtract lands [0, 12] in [0, 6].
      emitSubSevenWhereGe(cb, s);
      return;
    }
    // Two folds, the sign fixup, then the exact magic (the method comment has the bounds).
    emitFold(cb, orig, fold, 0x7FFF, 15);
    emitFold(cb, fold, fold, 0x7FFF, 15);
    cb.aload(fold);
    cb.loadConstant(3);
    cb.aload(orig);
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    cb.loadConstant(0);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    cb.astore(fold);
    // r = v - ((v * 37450) >>> 18) * 7.
    cb.aload(fold);
    cb.aload(fold);
    cb.loadConstant(37450);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.getstatic(VECTOR_OPERATORS, "LSHR", VO_BINARY);
    cb.loadConstant(18);
    cb.invokevirtual(INT_VECTOR, "lanewise", LANEWISE_BINARY_I);
    cb.loadConstant(7);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
  }

  /** {@code dst = src.and(mask).add(src >>> shift)}, all through locals. */
  private static void emitFold(CodeBuilder cb, int src, int dst, int mask, int shift) {
    cb.aload(src);
    cb.loadConstant(mask);
    cb.invokevirtual(INT_VECTOR, "and", LANEWISE_VI);
    cb.aload(src);
    cb.getstatic(VECTOR_OPERATORS, "LSHR", VO_BINARY);
    cb.loadConstant(shift);
    cb.invokevirtual(INT_VECTOR, "lanewise", LANEWISE_BINARY_I);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.astore(dst);
  }

  /** Consumes nothing: {@code [s] -> [s - 7 where s >= 7]} via one masked subtract. */
  private static void emitSubSevenWhereGe(CodeBuilder cb, Slots s) {
    cb.dup();
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(7);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.astore(s.maskTmp);
    cb.loadConstant(7);
    cb.aload(s.maskTmp);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI_MASKED);
  }

  /** {@code [r] -> [(r + k) mod 7]} for {@code r} in {@code [0, 6]}, {@code k} in 3..4. */
  static void emitModOffset(CodeBuilder cb, Slots s, int k) {
    cb.loadConstant(k);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    emitSubSevenWhereGe(cb, s);
  }

  /**
   * Consumes the child's {@code IntVector} of epoch days and leaves one of the five calendar fields
   * (including {@code dayOfYear}). {@link VarkaChrono} is the scalar twin of everything below - it
   * holds every constant this method loads, and its own javadoc carries the derivation - so the two
   * cannot drift and a disagreement between them is an emission bug rather than an arithmetic one.
   *
   * <p>The shape is a civil-from-days decomposition in a March-based year, where the leap day
   * is a year's last day rather than an interior one. There is no vector divide, so every
   * division is a magic multiply: the three small ones are exact, and the two large ones
   * ({@code / 146097} and {@code / 36524}) use a round-down magic that never overestimates,
   * followed by carries that are one compare and two masked adjustments each. That is the
   * whole reason this node weighs {@link VarkaEmitBudget#CHRONO_WEIGHT} rather than 1.
   *
   * <p>The temporaries are locals rather than operand-stack juggling because six values stay live
   * across the tail - era, century, year of century, day of year, the March month, and two masks -
   * which is past what the stack can hold legibly. The March month is the one of them a tail may
   * not need: {@link Year} reads the January turn off the day of year instead, so a body whose
   * calendar tails are all years never computes it (see {@code tailReadsMarchMonth}).
   */
  static void emitChrono(CodeBuilder cb, VarkaVectorIR node, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    analysis.lane.requireInt(node);
    int[] t = s.chronoTmp.get(node);
    int era = t[1];
    int rem = t[2];
    int century = t[3];
    int yearOfCentury = t[4];
    // t[5] under the affine-numerator switch is the affine numerator, not the March month; every
    // reader below takes the axis from here rather than deciding for itself.
    int marchMonth = t[5];
    boolean neri = analysis.options.neriSchneiderMonth();
    boolean julian = analysis.options.julianMap();

    if (node instanceof TruncDateDynamic n) {
      // The level column first, into the node's own slot, so the prefix's date is the last
      // child emitted before line() re-tags the instructions as this node's - the order
      // NextDay keeps for its two children.
      emitValue(cb, n.level(), dense, analysis, s, computed);
      cb.astore(t[TRUNC_DYNAMIC_LEVEL_SLOT]);
    }
    emitChronoPrefixOnce(cb, node, dense, analysis, s, t, computed);

    switch (node) {
      case Year n -> emitChronoYear(cb, era, century, yearOfCentury, rem, julian);
      case Month n -> emitChronoMonth(cb, marchMonth, neri);
      case DayOfMonth n ->
          emitChronoDayOfMonth(cb, rem, marchMonth, neri, analysis.divider);
      case Quarter n -> {
        emitChronoMonth(cb, marchMonth, neri);
        cb.loadConstant(2);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
        emitDivide(cb, analysis.divider, ChronoDivide.QUARTER);
      }
      case DayOfYear n -> {
        // t[6..8] are DayOfYear's own - a plain extraction's chronoTmp is only 8 long, so
        // nothing else in this switch may read past t[5]. t[6] and t[7] are the prefix's carry
        // scratch, dead by here, so only t[8] is genuinely extra.
        int mask = t[6];
        int leap = t[7];
        int year = t[8];
        // year - Year's own formula, recomputed here because the leap flag needs a plain
        // year and nothing upstream keeps one around. emitLeapFlag applies its own bias.
        // Like Year's own tail this reads the January bit off the day of year, so
        // this node is the second one whose prefix never needs the month step.
        emitChronoYear(cb, era, century, yearOfCentury, rem, julian);
        cb.astore(year);
        emitLeapFlag(cb, year);
        cb.astore(leap);
        emitJanuaryDayOfYear(cb, rem, leap, mask);
      }
      case LastDay n -> emitChronoLastDay(cb, s, t, neri, julian, analysis.divider);
      case TruncDate n -> emitChronoTrunc(cb, n, s, t, neri, julian,
          analysis.options.truncDate(), analysis.divider);
      case TruncDateDynamic n -> emitChronoTruncDynamic(cb, n, analysis, s, t, neri, julian);
      case WeekOfYear n ->
          emitChronoWeekOfYear(cb, t, era, century, yearOfCentury, rem, julian, analysis.divider);
      default -> throw new IllegalStateException("not a calendar node: " + node);
    }
  }

  /**
   * The prefix, and the date it consumes, emitted unless a sibling over that same date already left
   * the prefix in these very locals earlier in this lane group. This is the only place a value
   * node's child is emitted from anywhere but its own {@code emitValue} arm, and it has to be:
   * {@link #emitChronoPrefix} takes the date off the operand stack, so whether the date is emitted
   * at all is the same decision as whether the prefix is.
   *
   * <p>With {@link VarkaEmitOptions#shareChronoPrefix} off this is exactly what the four
   * extraction arms and {@link #emitAddMonths} used to do inline, in the same order and with
   * the same line-number marker, so the bytes are unchanged for every existing shape.
   */
  private static void emitChronoPrefixOnce(CodeBuilder cb, VarkaVectorIR node, boolean dense,
      Analysis analysis, Slots s, int[] t, Set<VarkaVectorIR> computed) {
    boolean shareChronoPrefix = analysis.options.shareChronoPrefix();
    FragmentKey key = shareChronoPrefix ? fragmentKey(node, dense, s) : null;
    if (shareChronoPrefix && !s.emittedFragments.add(key)) {
      // A sibling over this date already ran the prefix into these very locals earlier in this
      // lane group, so this node needs nothing but its own tail. The date itself is not loaded
      // either: emitChronoPrefix is the only consumer of it here, and a CSE'd child would
      // otherwise be loaded and dropped.
      line(cb, analysis, node);
      return;
    }
    emitValue(cb, chronoChild(node), dense, analysis, s, computed);
    line(cb, analysis, node);
    // Whether the run ends with the month step. Under sharing that is a question about every
    // consumer of this fragment, not about the node that happens to be emitting it; with
    // sharing off two nodes with equal keys name different locals, so it is per node and
    // year(d) does not pay for a month(d) it shares nothing with.
    boolean emitMonth = !analysis.options.elideChronoMonth()
        || (shareChronoPrefix ? s.fragmentsReadingMonth.contains(key)
            : tailReadsMarchMonth(node));
    emitChronoPrefix(cb, node, dense, analysis, s, t, emitMonth);
  }

  /**
   * The civil-from-days decomposition through the March-based month, shared by every field
   * {@link #emitChrono} computes and by {@link #emitAddMonths}, which needs three of the four
   * fields at once rather than one. Factored out of what was a single {@code emitChrono} method -
   * the split changes no emitted instruction for {@link Year}, {@link Month}, {@link DayOfMonth}
   * or {@link Quarter}, only where the Java source that emits them lives, so it moves no pinned
   * value.
   *
   * <p>Leaves {@code era}, {@code century}, {@code yearOfCentury} and {@code marchMonth} in
   * {@code t[1..5]} for a field's own tail to read, and the day of year in {@code t[2]} (
   * {@code rem}, reused across the prefix the way the original method reused it). All but
   * {@code marchMonth} unconditionally: {@code emitMonth} false drops the month step, which happens
   * exactly where no tail of this fragment reads it. Under {@link VarkaEmitOptions#julianMap}
   * {@code t[4]} holds the year of era rather than the year of century and {@code t[3]} is dead
   * once the prefix is done; see {@link #emitJulianYearOfEra}.
   *
   * <p>Those five locals outliving the call is what makes the run a shareable fragment, since
   * {@link #emitChronoYear}, {@link #emitChronoMonth} and {@link #emitChronoDayOfMonth} read
   * them from there rather than from the operand stack: a later sibling finds them intact. The
   * two masks in {@code t[6..7]} are the carries' own scratch and are deliberately not part of
   * that contract - {@link #emitAddMonths} reuses {@code t[6]} for its compares once the prefix
   * is done, which is sound precisely because no tail reads it.
   */
  private static void emitChronoPrefix(CodeBuilder cb, VarkaVectorIR node, boolean dense,
      Analysis analysis, Slots s, int[] t, boolean emitMonth) {
    int days = t[0];
    int era = t[1];
    int rem = t[2];
    int century = t[3];
    int yearOfCentury = t[4];
    int marchMonth = t[5];
    int mask = t[6];
    int leap = t[7];

    cb.astore(days);

    emitEra(cb, days, era, rem, mask, analysis.divider);

    if (analysis.options.julianMap()) {
      // the year of era and the day of year through the Julian map - one division stage fewer than
      // the split below, and no leap correction in the prefix at all.
      emitJulianYearOfEra(cb, rem, century, yearOfCentury, mask, leap, analysis.divider);
    } else {
      // rem is now the day of era, in [0, 146096]. Everything below works on that.
      // century = (doe * M) >>> K, then doc = doe - century * 36524, with one carry.
      cb.aload(rem);
      emitDivide(cb, analysis.divider, ChronoDivide.CENTURY);
      cb.astore(century);
      cb.aload(rem);
      cb.aload(century);
      cb.loadConstant(VarkaChrono.CENTURY_DAYS);
      cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
      cb.astore(rem);
      if (analysis.divider.carries(ChronoDivide.CENTURY)) {
        emitCarry(cb, century, rem, VarkaChrono.CENTURY_DAYS, mask);
      }

      // An era's fourth century holds one extra day - its leap day - so the quotient can land on
      // 4 for exactly one day of each era. Fold that back into century 3.
      cb.aload(century);
      cb.getstatic(VECTOR_OPERATORS, "EQ", VO_COMPARISON);
      cb.loadConstant(4);
      cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
      cb.astore(mask);
      cb.aload(century);
      cb.loadConstant(1);
      cb.aload(mask);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI_MASKED);
      cb.astore(century);
      cb.aload(rem);
      cb.loadConstant(VarkaChrono.CENTURY_DAYS);
      cb.aload(mask);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
      cb.astore(rem);

      // yoc = doc / 365 - exact here, because the split into centuries left a dividend under
      // 44859. It ignores leap days, so it can name the following year; the fix is below.
      cb.aload(rem);
      emitDivide(cb, analysis.divider, ChronoDivide.YEAR_OF_CENTURY);
      cb.astore(yearOfCentury);

      // doy = doc - (365 * yoc + yoc / 4). Negative exactly where yoc overshot.
      cb.aload(rem);
      cb.aload(yearOfCentury);
      cb.loadConstant(365);
      cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
      cb.aload(yearOfCentury);
      emitShift(cb, "LSHR", 2);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
      cb.astore(rem);

      // Where it overshot, step back a year and give the days back - one more when the year we
      // step into is a leap year, which in a March-based year is simply yoc divisible by four.
      cb.aload(rem);
      cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
      cb.loadConstant(0);
      cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
      cb.astore(mask);
      cb.aload(yearOfCentury);
      cb.loadConstant(3);
      cb.invokevirtual(INT_VECTOR, "and", LANEWISE_VI);
      cb.getstatic(VECTOR_OPERATORS, "EQ", VO_COMPARISON);
      cb.loadConstant(0);
      cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
      cb.aload(mask);
      cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
      cb.astore(leap);
      cb.aload(rem);
      cb.loadConstant(365);
      cb.aload(mask);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
      cb.loadConstant(1);
      cb.aload(leap);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
      cb.astore(rem);
      cb.aload(yearOfCentury);
      cb.loadConstant(1);
      cb.aload(mask);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI_MASKED);
      cb.astore(yearOfCentury);

    }

    // mp = (5 * doy + 2) / 153: the March-based month, 0 for March through 11 for February.
    // Skipped where no tail of this fragment reads it - four lane ops and a store
    // that a year-only kernel would compute and drop. t[5] stays allocated either way; an
    // elided prefix simply never writes it, and any reader of it that did not say so through
    // tailReadsMarchMonth is rejected by the verifier at class load rather than read as
    // garbage.
    if (emitMonth) {
      if (analysis.options.neriSchneiderMonth()) {
        // num = 2141 * doy + 197913. Two ops where the 0-based form takes four, and
        // what it leaves in t[5] is not a month but a numerator carrying both the month index
        // in its high half and the day of month in its low half - which is why the day tail
        // stops needing emitMonthStart run forwards.
        cb.aload(rem);
        cb.loadConstant(VarkaChrono.MONTH_NUM_M);
        cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
        cb.loadConstant(VarkaChrono.MONTH_NUM_ADD);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
        cb.astore(marchMonth);
      } else {
        cb.aload(rem);
        cb.loadConstant(5);
        cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
        cb.loadConstant(2);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
        emitDivide(cb, analysis.divider, ChronoDivide.MONTH);
        cb.astore(marchMonth);
      }
    }
  }

  /**
   * the day of era to the year of era and the March-based day of year through Ben Joffe's Julian
   * map, in place of the century-then-year split in {@link #emitChronoPrefix}. The scalar twin is
   * {@code VarkaChrono.narrowedJulian}; the constants' javadoc there says why each of the two
   * divisions needs exactly one carry.
   *
   * <p>Scale the day of era by four and add three; one round-down magic gives the century, and
   * a carry makes it exact. Add four back per century: the count is now in a calendar where
   * every fourth year is leap without exception, so one more magic and carry give the year of
   * era, and the remainder shifted right by two is the day of year with 29 February in the
   * right place. Nothing here tests for a leap year, and the era's last day needs no fold.
   *
   * <p>Slots: {@code rem} arrives as the day of era and leaves as the day of year; {@code century}
   * is written (the map needs it) but no tail reads it; {@code yearOfEra} is the slot the other
   * form leaves the year of century in - which is why {@link #emitChronoYear} takes the form as an
   * argument rather than deciding what the slot holds; {@code mask} and {@code scratch} are the
   * carries' scratch, the same two the other form uses.
   */
  private static void emitJulianYearOfEra(CodeBuilder cb, int rem, int century, int yearOfEra,
      int mask, int scratch, Divider divider) {
    // quad = 4 * doe + 3
    cb.aload(rem);
    emitShift(cb, "LSHL", 2);
    cb.loadConstant(VarkaChrono.QUAD_DAY_ADD);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.astore(rem);
    // century = quad / 146097, round-down plus one carry; the remainder is only scratch.
    cb.aload(rem);
    emitDivide(cb, divider, ChronoDivide.JULIAN_CENTURY);
    cb.astore(century);
    cb.aload(rem);
    cb.aload(century);
    cb.loadConstant(VarkaChrono.ERA_DAYS);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.astore(scratch);
    if (divider.carries(ChronoDivide.JULIAN_CENTURY)) {
      emitCarry(cb, century, scratch, VarkaChrono.ERA_DAYS, mask);
    }
    // jul = quad + 4 * century: the Julian map itself.
    cb.aload(rem);
    cb.aload(century);
    emitShift(cb, "LSHL", 2);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.astore(rem);
    // yearOfEra = jul / 1461, round-down plus one carry; the remainder stays in rem.
    cb.aload(rem);
    emitDivide(cb, divider, ChronoDivide.JULIAN_YEAR);
    cb.astore(yearOfEra);
    cb.aload(rem);
    cb.aload(yearOfEra);
    cb.loadConstant(VarkaChrono.JULIAN_CYCLE_DAYS);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.astore(rem);
    if (divider.carries(ChronoDivide.JULIAN_YEAR)) {
      emitCarry(cb, yearOfEra, rem, VarkaChrono.JULIAN_CYCLE_DAYS, mask);
    }
    // doy = rem / 4
    cb.aload(rem);
    emitShift(cb, "LSHR", 2);
    cb.astore(rem);
  }

  /** Leaves the reported (January-based) year - {@code 400 * era + 100 * century + yoc} under
   * the century-then-year form, {@code 400 * era + yearOfEra} under the Julian map,
   * where {@code t[4]} holds the year of era - plus one where the March year has turned
   * January. The {@link Year} tail, factored out so {@link #emitAddMonths} can call it too.
   *
   * <p>The January bit is read off the day of year rather than the March-based month (task
   * 48): the two are the same test, one step apart in the chain, so the year is the one field
   * of the four that never needs the month step. See
   * {@link VarkaChrono#MARCH_TO_JANUARY_DAYS}. */
  private static void emitChronoYear(CodeBuilder cb, int era, int century, int yearOfCentury,
      int dayOfYear, boolean julian) {
    cb.aload(era);
    cb.loadConstant(400);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    if (!julian) {
      cb.aload(century);
      cb.loadConstant(100);
      cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    }
    cb.aload(yearOfCentury);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.loadConstant(1);
    emitJanuaryMaskFromDayOfYear(cb, dayOfYear);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
  }

  /** Leaves the day of month: {@code doy - monthStart(mp) + 1}, the inverse of the month's own
   * linear form. The {@link DayOfMonth} tail, factored out so {@link #emitAddMonths} can call
   * it too. */
  private static void emitChronoDayOfMonth(CodeBuilder cb, int rem, int monthSlot,
      boolean neri, Divider divider) {
    emitZeroBasedDayOfMonth(cb, rem, monthSlot, neri, divider);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
  }

  /** The zero-based day of month, {@link #emitChronoDayOfMonth} one step before its increment
   * - which is exactly what {@code trunc(d, 'MONTH')} subtracts. */
  private static void emitZeroBasedDayOfMonth(CodeBuilder cb, int rem, int monthSlot,
      boolean neri, Divider divider) {
    if (neri) {
      // The numerator's low half divided by 2141 is the zero-based day of month, so this tail
      // never runs the month start forwards and never touches the day of year at all.
      cb.aload(monthSlot);
      cb.loadConstant(0xFFFF);
      cb.invokevirtual(INT_VECTOR, "and", LANEWISE_VI);
      emitDivide(cb, divider, ChronoDivide.DAY_OF_MONTH);
    } else {
      cb.aload(rem);
      emitMonthStart(cb, monthSlot, divider);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    }
  }

  /**
   * Leaves the day of the March-based year on which March-based month {@code mp} begins:
   * {@code (153 * mp + 2) / 5}, exact for every {@code mp} in {@code [0, 11]} - the same magic
   * multiply {@link #emitChronoDayOfMonth} runs in reverse. {@link #emitAddMonths} calls this twice
   * to get a month's length by subtraction, which is what makes a twelve-entry length table
   * unnecessary: every month but the year's last (February, here) is one subtraction between two
   * calls to this.
   */
  private static void emitMonthStart(CodeBuilder cb, int marchMonth, Divider divider) {
    cb.aload(marchMonth);
    cb.loadConstant(153);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.loadConstant(2);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    emitDivide(cb, divider, ChronoDivide.MONTH_START);
  }

  /**
   * {@code date +- INTERVAL n MONTH/YEAR} and {@code add_months}. Decomposes
   * {@code node.days()} via {@link #emitChronoPrefix} into year, month and day; does the month
   * arithmetic over a small, non-negative dividend (folding the year in would put it near
   * 400,000 - past the range any magic multiply admits, {@code PLAN_TASK_40.md} section 2.2);
   * then recomposes with {@link #emitDaysFromCivil}.
   *
   * <p>The day is clamped to the new month's length before recomposing, {@code min(dom,
   * length)}, matching {@code LocalDate#plusMonths}. The length is not a lookup table: every
   * month but the year's last is one subtraction between two {@link #emitMonthStart} calls
   * (see its javadoc); February - the March-based year's last month - needs the year's total
   * length instead, which is where {@link #emitLeapFlag} comes in, the same flag three of tasks
   * 34-37 need per {@code PLAN_TASK_34.md} section 2.1. Both branches are computed for every
   * lane and blended, since a vector lane cannot skip work the way a scalar branch would.
   *
   * <p> {@code node.months()} is a {@link LiteralSlot} or a column: when it is a column,
   * {@code emitRangeGuard} runs on it right after it loads, against
   * {@link VarkaChrono#MONTH_ARITH_MIN_MONTHS} / {@code MAX_MONTHS} - the same bound a literal
   * count is checked against at compile time - because the magic multiply a few lines below is
   * exact only there.
   */
  static void emitAddMonths(CodeBuilder cb, AddMonths node, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    analysis.lane.requireInt(node);
    int[] t = s.chronoTmp.get(node);
    int era = t[1];
    int rem = t[2];
    int century = t[3];
    int yearOfCentury = t[4];
    int marchMonth = t[5];
    int mask = t[6];
    int year = t[8];
    int month = t[9];
    int dayOfMonth = t[10];
    int k = t[11];
    int q = t[12];
    int nm = t[13];
    int ny = t[14];
    int mp2 = t[15];
    int monthStart = t[16];
    int mpNextClamped = t[17];
    int monthStartNext = t[18];
    int length = t[19];
    int clampedDay = t[20];
    int yy2 = t[21];
    int b2 = t[22];
    int era2 = t[23];
    int yoe = t[24];
    int doy2 = t[25];
    int doe2 = t[26];
    int civilScratch1 = t[27];
    int civilScratch2 = t[28];
    int civilMaskB = t[29];
    int nm1 = t[30];

    emitChronoPrefixOnce(cb, node, dense, analysis, s, t, computed);
    emitChronoYear(cb, era, century, yearOfCentury, rem, analysis.options.julianMap());
    cb.astore(year);
    emitChronoMonth(cb, marchMonth, analysis.options.neriSchneiderMonth());
    cb.astore(month);
    emitChronoDayOfMonth(cb, rem, marchMonth, analysis.options.neriSchneiderMonth(),
        analysis.divider);
    cb.astore(dayOfMonth);

    // k = (month - 1) + monthsOffset + MONTH_ARITH_BIAS: small and non-negative because the
    // compiler bounds monthsOffset (VarkaChrono.MONTH_ARITH_MIN/MAX_MONTHS).
    cb.aload(month);
    cb.loadConstant(-1);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    emitValue(cb, node.months(), dense, analysis, s, computed);
    // The node's own word (the AND of both children's) used to be computed after this method
    // returned, from the caller's dispatch; the guard below needs it already stored, and both
    // children's words are ready by now (the date's from the prefix just above, the count's from
    // the emitValue call just above), so it moves here instead - earlier than // strictly needed,
    // but the AND itself is unchanged.
    if (!dense && s.ownWord.contains(node)) {
      emitAndWord(cb, s, s.wordRef.get(node), s.wordRef.get(node.days()),
          s.wordRef.get(node.months()));
    }
    Integer monthsGuardTmp = s.guardTmp.get(node);
    if (monthsGuardTmp != null) {
      // The month-count guard covers this node's operand rather than its result - the value on the
      // stack here is node.months(). The word is still this node's, the AND of both children's,
      // stored just above: a lane whose date is null is not out of range even if its count is, and
      // the row is null either way.
      line(cb, analysis, node);
      emitRangeGuard(cb, node, dense ? null : s.wordRef.get(node), monthsGuardTmp, dense,
          analysis, s,
          VarkaChrono.MONTH_ARITH_MIN_MONTHS, VarkaChrono.MONTH_ARITH_MAX_MONTHS);
    }
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.loadConstant(VarkaChrono.MONTH_ARITH_BIAS);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.astore(k);

    // q = k / 12, exact; nm = k - q * 12, the new month, 0-11; ny = year + q - the bias's years.
    cb.aload(k);
    emitDivide(cb, analysis.divider, ChronoDivide.MONTH_ARITH);
    cb.astore(q);
    cb.aload(k);
    cb.aload(q);
    cb.loadConstant(12);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.astore(nm);
    cb.aload(year);
    cb.aload(q);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.loadConstant(VarkaChrono.MONTH_ARITH_BIAS / 12);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI);
    cb.astore(ny);

    // mp2 = nm - 2 + 12 where nm <= 1 (the new month is January or February) - the March-based
    // month for the length lookup below. emitDaysFromCivil redoes this test on its own terms for
    // the recompose itself; the two are independent, not shared, since // recomputing beats
    // threading a value across an unrelated boundary.
    cb.aload(nm);
    cb.getstatic(VECTOR_OPERATORS, "LE", VO_COMPARISON);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.astore(mask);
    cb.aload(nm);
    cb.loadConstant(-2);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.loadConstant(12);
    cb.aload(mask);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    cb.astore(mp2);

    // The new month's length: monthStartNext - monthStart, except February (the March-based
    // year's last month), which needs the year's own total length instead.
    emitMonthStart(cb, mp2, analysis.divider);
    cb.astore(monthStart);
    cb.aload(mp2);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.loadConstant(VarkaChrono.MARCH_YEAR_JANUARY + 1);
    cb.invokevirtual(INT_VECTOR, "min", LANEWISE_VI);
    cb.astore(mpNextClamped);
    emitMonthStart(cb, mpNextClamped, analysis.divider);
    cb.astore(monthStartNext);

    cb.aload(monthStartNext);
    cb.aload(monthStart);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.aload(s.species);
    cb.loadConstant(365);
    cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
    cb.loadConstant(1);
    emitLeapFlag(cb, ny);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    cb.aload(monthStart);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.aload(mp2);
    cb.getstatic(VECTOR_OPERATORS, "EQ", VO_COMPARISON);
    cb.loadConstant(VarkaChrono.MARCH_YEAR_JANUARY + 1);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(INT_VECTOR, "blend", BLEND);
    cb.astore(length);

    // Clamp, then recompose: days_from_civil(ny, nm + 1, clampedDay).
    cb.aload(dayOfMonth);
    cb.aload(length);
    cb.invokevirtual(INT_VECTOR, "min", LANEWISE_VV);
    cb.astore(clampedDay);
    cb.aload(nm);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.astore(nm1);
    // era2, yoe, mp2, doy2, doe2 and mask are dead past this point (the length computation
    // above was their only use), so emitDaysFromCivil reuses their slots for its own values.
    emitDaysFromCivil(cb, ny, nm1, clampedDay, yy2, b2, era2, yoe, mp2, doy2, doe2,
        civilScratch1, civilScratch2, mask, civilMaskB, analysis.divider);
  }

  /**
   * Hinnant's {@code days_from_civil}: the exact inverse of {@link #emitChronoPrefix}
   * plus a field tail, recomposing a date from its (January-based) {@code year}, (1-12)
   * {@code month} and already-clamped {@code day}. {@link VarkaChrono#daysFromCivil} is its
   * scalar twin, and this redoes the {@code month <= 2} split on its own terms rather than
   * reusing {@link #emitAddMonths}'s month-arithmetic test, so it is a real standalone helper
   * rather than one hiding a dependency on its only caller - {@code months_between},
   * {@code make_date} and {@code date_trunc('QUARTER')} all want to call this without doing
   * {@link #emitAddMonths}'s own month arithmetic first. Every division here is an exact magic
   * multiply because every dividend is small, unlike {@link #emitChronoPrefix}'s forward
   * direction, which needs two round-down magics with carries. That turned out to be wrong for
   * {@code / 400} and {@code / 100}: {@link VarkaChrono#YEAR_CENTURY_M}'s javadoc records why,
   * and both now take the one-correction shape {@link #emitChronoPrefix}'s own {@code / 146097}
   * and {@code / 36524} already use, via {@link #emitCarry}.
   *
   * <p>{@code yy}, {@code b}, {@code era}, {@code yoe}, {@code mp}, {@code doy}, {@code doe},
   * {@code century} and {@code mask} are locals the caller owns and this method is free to
   * overwrite.
   */
  private static void emitDaysFromCivil(CodeBuilder cb, int year, int month, int day, int yy,
      int b, int era, int yoe, int mp, int doy, int doe, int century, int centuryRem,
      int mask, int carryMask, Divider divider) {
    // yy = year - (month <= 2 ? 1: 0), the March-based year.
    cb.aload(month);
    cb.getstatic(VECTOR_OPERATORS, "LE", VO_COMPARISON);
    cb.loadConstant(2);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.astore(mask);
    cb.aload(year);
    cb.loadConstant(1);
    cb.aload(mask);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI_MASKED);
    cb.astore(yy);

    // era = (yy + YEAR_BIAS) / 400, yoe = that biased year mod 400 - round-down plus one
    // correction, per VarkaChrono.YEAR_CENTURY_M's javadoc.
    cb.aload(yy);
    cb.loadConstant(VarkaChrono.YEAR_BIAS);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.astore(b);
    cb.aload(b);
    emitDivide(cb, divider, ChronoDivide.YEAR_OF_ERA_400);
    cb.astore(era);
    cb.aload(b);
    cb.aload(era);
    cb.loadConstant(400);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.astore(yoe);
    if (divider.carries(ChronoDivide.YEAR_OF_ERA_400)) {
      emitCarry(cb, era, yoe, 400, carryMask);
    }

    // mp = month + (month <= 2 ? 9: -3), the March-based month; doy = monthStart(mp)+day-1.
    cb.aload(month);
    cb.loadConstant(-3);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.loadConstant(12);
    cb.aload(mask);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    cb.astore(mp);
    emitMonthStart(cb, mp, divider);
    cb.aload(day);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI);
    cb.astore(doy);

    // century = yoe / 100, round-down plus one correction (yoe is 0..399, but the same
    // round-down magic is used here for one shared constant rather than a second one).
    cb.aload(yoe);
    emitDivide(cb, divider, ChronoDivide.YEAR_OF_ERA_100);
    cb.astore(century);
    cb.aload(yoe);
    cb.aload(century);
    cb.loadConstant(100);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.astore(centuryRem);
    if (divider.carries(ChronoDivide.YEAR_OF_ERA_100)) {
      emitCarry(cb, century, centuryRem, 100, carryMask);
    }

    // doe = yoe * 365 + yoe / 4 - century + doy.
    cb.aload(yoe);
    cb.loadConstant(365);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.aload(yoe);
    emitShift(cb, "LSHR", 2);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.aload(century);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.aload(doy);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.astore(doe);

    // (era - YEAR_BIAS / 400) * ERA_DAYS + doe - MARCH_EPOCH_SHIFT.
    cb.aload(era);
    cb.loadConstant(VarkaChrono.YEAR_BIAS / 400);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI);
    cb.loadConstant(VarkaChrono.ERA_DAYS);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.aload(doe);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.loadConstant(VarkaChrono.MARCH_EPOCH_SHIFT);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI);
  }

  /**
   * Leaves the mask of lanes whose reported year {@code y} is a leap year, as one multiply, one
   * mask and one unsigned compare over a biased year (Falk Huffner's perfect hash; see
   * {@link VarkaChrono#LEAP_HASH_M}, which carries the constants, the domain and the two
   * properties that make this look wrong at a glance).
   *
   * <p>This replaced two magic divisions with a correction carry each - 19 int-vector ops and 3
   * mask ops, against 4 and 0 here - and with them five scratch locals and five of this
   * method's seven parameters. Two things in it are deliberate and must survive a future
   * reader: the multiply <b>overflows the lane</b>, which is the mechanism rather than a bug
   * since the identity is defined modulo 2^32; and the compare is <b>unsigned</b>
   * ({@code ULE}), because the mask keeps bits 30 and 31 and a signed compare would call every
   * year with a negative hash leap.
   *
   * <p>The hash is exact over its domain and arbitrary one year past it, so the domain is the
   * whole contract: reported years -15200..87299, which contains the roughly -14848..35181
   * that {@code add_months} and the interval arithmetic can reach. A caller outside that range
   * would need a different bias, not a correction.
   */
  private static void emitLeapFlag(CodeBuilder cb, int y) {
    cb.aload(y);
    cb.loadConstant(VarkaChrono.YEAR_BIAS);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.loadConstant(VarkaChrono.LEAP_HASH_M);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.loadConstant(VarkaChrono.LEAP_HASH_MASK);
    cb.invokevirtual(INT_VECTOR, "and", LANEWISE_VI);
    cb.getstatic(VECTOR_OPERATORS, "ULE", VO_COMPARISON);
    cb.loadConstant(VarkaChrono.LEAP_HASH_MAX);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
  }

  /**
   * The January-based day of year from the March-based one:
   * {@code doy >= 306 ? doy - 305: doy + 60 + L}, with {@code leap} the year's leap mask as
   * {@link #emitLeapFlag} leaves it and {@code mask} a scratch local for the branch select.
   * Factored out of the {@code DayOfYear} arm for {@link #emitChronoTrunc}'s {@code YEAR} and
   * {@code QUARTER} forms, instruction for instruction, so the extraction's bytes did not move.
   */
  private static void emitJanuaryDayOfYear(CodeBuilder cb, int rem, int leap, int mask) {
    cb.aload(rem);
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(VarkaChrono.MARCH_TO_JANUARY_DAYS);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.astore(mask);

    cb.aload(rem);
    cb.loadConstant(VarkaChrono.MARCH_DAY_OF_YEAR);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.loadConstant(1);
    cb.aload(leap);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);

    cb.aload(rem);
    cb.loadConstant(VarkaChrono.MARCH_TO_JANUARY_DAYS - 1);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI);
    cb.aload(mask);
    cb.invokevirtual(INT_VECTOR, "blend", BLEND);
  }

  /**
   * The ISO week tail: the {@code DayOfYear} tail over the prefix - which here ran
   * over a {@link ThursdayOf}, the analysis's rule - then {@code (doy - 1) / 7 + 1} by
   * {@link VarkaChrono#WEEK_M}, four ops. Same slots as {@code DayOfYear}: {@code t[6]} and
   * {@code t[7]} are the prefix's dead carry scratch, {@code t[8]} the node's own year.
   * Leaves the week, 1 to 53, on the stack.
   */
  private static void emitChronoWeekOfYear(CodeBuilder cb, int[] t, int era, int century,
      int yearOfCentury, int rem, boolean julian, Divider divider) {
    int mask = t[6];
    int leap = t[7];
    int year = t[8];
    emitChronoYear(cb, era, century, yearOfCentury, rem, julian);
    cb.astore(year);
    emitLeapFlag(cb, year);
    cb.astore(leap);
    emitJanuaryDayOfYear(cb, rem, leap, mask);                 // [doy]
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI);          // [doy - 1]
    emitDivide(cb, divider, ChronoDivide.WEEK);     // [(doy - 1) / 7]
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);          // [week]
  }

  /**
   * {@code trunc(date, level)}: the first day of the year, month or quarter, under
   * one of two lowerings selected by {@link VarkaEmitOptions#truncDate()}.
   *
   * <p><b>{@code SUBTRACT}</b> takes the elapsed part of the period off the date. {@code MONTH}
   * is {@code d - dom0}: the numerator's low half already is the zero-based day of month, so
   * this reads {@link #emitZeroBasedDayOfMonth} one step before the extraction's {@code + 1}
   * and stops - two ops on top of the prefix, no leap flag. {@code YEAR} is
   * {@code d - dayofyear + 1} over {@link #emitJanuaryDayOfYear}, and {@code QUARTER} is
   * {@code d - dayofyear + start}, with {@code start} the January-based day of year of the
   * quarter's first day: 1, 91 + L, 182 + L, 274 + L, built as a broadcast 1 plus three masked
   * adds on {@code quarter >= 2, 3, 4} and one masked add of the leap flag on
   * {@code quarter >= 2}. The quarter is the {@code Quarter} tail's, off {@code emitChronoMonth}'s
   * January-based month - never off the March month directly, which would be right from April
   * on and wrong for January to March.
   *
   * <p><b> {@code RECOMPOSE} </b> rebuilds the period's first day from its year and month through
   * {@link #emitDaysFromCivil}: {@code (year, 1, 1)}, {@code (year, month, 1)} and
   * {@code (year, 3 * quarter - 2, 1)}. No leap flag anywhere; the recomposition does its own era
   * arithmetic. Its value beyond the measurement is a second caller for the day-clamp helper, which
   * {@code add_months} 's own day clamp could otherwise mask a defect in.
   *
   * <p>Both leave the epoch-day vector on the operand stack and read nothing but the prefix's
   * results in {@code t[0..5]} plus this node's own slots from {@code t[6]} on
   * ({@code TRUNC_DATE_TMP_COUNT}); the two prefix carry masks {@code t[6..7]} are dead by here
   * and are reused, as {@code DayOfYear} reuses them.
   */
  private static void emitChronoTrunc(CodeBuilder cb, TruncDate node, Slots s, int[] t,
      boolean neri, boolean julian, VarkaEmitOptions.TruncDateForm form, Divider divider) {
    int days = t[0];
    int era = t[1];
    int rem = t[2];
    int century = t[3];
    int yearOfCentury = t[4];
    int marchMonth = t[5];
    int mask = t[6];
    int leap = t[7];
    int year = t[8];
    int month = t[9];
    int day = t[10];
    int dayOfYear = t[11];
    int quarter = t[12];
    switch (form) {
      case SUBTRACT -> {
        // The three results are factored into helpers so the dynamic node emits the same bytes for
        // each; the literal node's own bytes did not move (its register and the byte hashes in
        // PLAN_TASK_61.md 9).
        switch (node.level()) {
          case MONTH -> emitTruncMonth(cb, days, rem, marchMonth, neri, divider);
          case YEAR -> {
            emitTruncYearParts(cb, era, rem, century, yearOfCentury, mask, leap, year,
                dayOfYear, julian);
            emitTruncYear(cb, days, dayOfYear);
          }
          case QUARTER -> {
            emitTruncYearParts(cb, era, rem, century, yearOfCentury, mask, leap, year,
                dayOfYear, julian);
            emitTruncQuarter(cb, s, days, marchMonth, leap, dayOfYear, quarter, neri, divider);
          }
        }
      }
      case RECOMPOSE -> {
        emitChronoYear(cb, era, century, yearOfCentury, rem, julian);
        cb.astore(year);
        switch (node.level()) {
          case YEAR -> {
            cb.aload(s.species);
            cb.loadConstant(1);
            cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
          }
          case MONTH -> emitChronoMonth(cb, marchMonth, neri);
          case QUARTER -> {
            // 3 * quarter - 2, the quarter's first month
            emitChronoMonth(cb, marchMonth, neri);
            cb.loadConstant(2);
            cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
            emitDivide(cb, divider, ChronoDivide.QUARTER);
            cb.loadConstant(3);
            cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
            cb.loadConstant(2);
            cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI);
          }
        }
        cb.astore(month);
        cb.aload(s.species);
        cb.loadConstant(1);
        cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
        cb.astore(day);
        emitDaysFromCivil(cb, year, month, day, t[13], t[14], t[15], t[16], t[17], t[18], t[19],
            t[20], t[21], t[22], t[23], divider);
      }
    }
  }

  /** {@code SUBTRACT}'s {@code MONTH}: {@code [] -> [d - dom0]}, two ops over the prefix. */
  private static void emitTruncMonth(CodeBuilder cb, int days, int rem, int marchMonth,
      boolean neri, Divider divider) {
    cb.aload(days);
    emitZeroBasedDayOfMonth(cb, rem, marchMonth, neri, divider);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
  }

  /**
   * What {@code SUBTRACT}'s {@code YEAR} and {@code QUARTER} share: the plain year, its leap
   * flag and the January-based day of year, each left in its slot; nothing on the stack.
   */
  private static void emitTruncYearParts(CodeBuilder cb, int era, int rem, int century,
      int yearOfCentury, int mask, int leap, int year, int dayOfYear, boolean julian) {
    emitChronoYear(cb, era, century, yearOfCentury, rem, julian);
    cb.astore(year);
    emitLeapFlag(cb, year);
    cb.astore(leap);
    emitJanuaryDayOfYear(cb, rem, leap, mask);
    cb.astore(dayOfYear);
  }

  /** {@code [] -> [d - dayOfYear + 1]}, the year's first day. */
  private static void emitTruncYear(CodeBuilder cb, int days, int dayOfYear) {
    cb.aload(days);
    cb.aload(dayOfYear);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
  }

  /**
   * {@code [] -> [d - dayOfYear + start]}, the quarter's first day, with {@code start} the
   * January-based day of year of the quarter's first day as {@link #emitChronoTrunc}
   * describes; leaves the quarter in its slot.
   */
  private static void emitTruncQuarter(CodeBuilder cb, Slots s, int days, int marchMonth,
      int leap, int dayOfYear, int quarter, boolean neri, Divider divider) {
    emitChronoMonth(cb, marchMonth, neri);
    cb.loadConstant(2);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    emitDivide(cb, divider, ChronoDivide.QUARTER);
    cb.astore(quarter);
    // start = 1 (+90 if q >= 2) (+91 if q >= 3) (+92 if q >= 4) (+L if q >= 2)
    cb.aload(s.species);
    cb.loadConstant(1);
    cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
    int[] steps = {90, 91, 92};
    for (int q = 2; q <= 4; q++) {
      cb.loadConstant(steps[q - 2]);
      emitQuarterAtLeast(cb, quarter, q);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    }
    cb.loadConstant(1);
    cb.aload(leap);
    emitQuarterAtLeast(cb, quarter, 2);
    cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    cb.aload(days);
    cb.aload(dayOfYear);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
  }

  /**
   * {@code trunc(date, fmt)} with a format column: the level is a lane value, so the
   * tail computes every period's first day and selects afterwards. The three calendar results
   * are {@code SUBTRACT}'s own helpers over one prefix, one year and one day of year; the week
   * is {@code d - weekday0(d)} with Monday as 0, where {@code weekday0} is {@code WeekDay}'s
   * tail ({@code floorMod(d + 3, 7)}: 1970-01-01 was a Thursday) - Spark's
   * {@code getNextDateForDayOfWeek(d - 7, MONDAY)} reduced, checked against it by the sweep.
   * The select starts from the year and blends the quarter, the month and the week in on
   * {@code level == 8, 7, 6}; every other code was a null lane before the kernel ran
   * ({@code TruncLevelLeaf}), and the node's word carries that, so no lane the select does
   * not cover is ever published. The four results ride the operand stack: the helpers only
   * load and store named locals in between, so nothing is spilled.
   */
  private static void emitChronoTruncDynamic(CodeBuilder cb, TruncDateDynamic node,
      Analysis analysis, Slots s, int[] t, boolean neri, boolean julian) {
    int days = t[0];
    int era = t[1];
    int rem = t[2];
    int century = t[3];
    int yearOfCentury = t[4];
    int marchMonth = t[5];
    int mask = t[6];
    int leap = t[7];
    int year = t[8];
    int dayOfYear = t[11];
    int quarter = t[12];
    int level = t[TRUNC_DYNAMIC_LEVEL_SLOT];
    emitTruncYearParts(cb, era, rem, century, yearOfCentury, mask, leap, year, dayOfYear,
        julian);
    emitTruncYear(cb, days, dayOfYear);
    emitTruncQuarter(cb, s, days, marchMonth, leap, dayOfYear, quarter, neri,
        analysis.divider);
    emitBlendWhereLevel(cb, level, TruncLevelLeaf.QUARTER);
    emitTruncMonth(cb, days, rem, marchMonth, neri, analysis.divider);
    emitBlendWhereLevel(cb, level, TruncLevelLeaf.MONTH);
    cb.aload(days);
    cb.aload(days);
    emitFloorMod7(cb, node, analysis, s);
    emitModOffset(cb, s, 3);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    emitBlendWhereLevel(cb, level, TruncLevelLeaf.WEEK);
  }

  /** {@code [a, b] -> [a.blend(b, level == code)]}: {@code b} in the lanes at that level. */
  private static void emitBlendWhereLevel(CodeBuilder cb, int level, int code) {
    cb.aload(level);
    cb.getstatic(VECTOR_OPERATORS, "EQ", VO_COMPARISON);
    cb.loadConstant(code);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(INT_VECTOR, "blend", BLEND);
  }

  /** Leaves the mask {@code quarter >= q}, for {@link #emitChronoTrunc}'s start select. */
  private static void emitQuarterAtLeast(CodeBuilder cb, int quarter, int q) {
    cb.aload(quarter);
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(q);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
  }

  /**
   * {@code last_day(date)}: {@code days + length - dayOfMonth}, where {@code length} is the
   * current March-based month's own length and {@code dayOfMonth} is {@link #emitChronoDayOfMonth}
   * 's own value. The length reuses {@link #emitMonthStart} the same way {@link #emitAddMonths}
   * does for the month it lands on: every month but the March-based year's last (February) is one
   * subtraction between two calls to it, clamping the second call's input the same way
   * {@link #emitAddMonths} does, since {@link #emitMonthStart} 's magic is only exact up to
   * {@code mp} 11. February needs the year's own total length instead, which is where
   * {@link #emitLeapFlag} comes in - the same flag {@link #emitAddMonths} needs for its own
   * February case, and the same reason this reuses it rather than a second copy of the leap test.
   */
  private static void emitChronoLastDay(CodeBuilder cb, Slots s, int[] t, boolean neri,
      boolean julian, Divider divider) {
    int days = t[0];
    int era = t[1];
    int rem = t[2];
    int century = t[3];
    int yearOfCentury = t[4];
    int marchMonth = t[5];
    int mask = t[6];
    int year = t[8];
    int dayOfMonth = t[9];
    int monthStart = t[10];
    int mpNextClamped = t[11];
    int monthStartNext = t[12];
    int length = t[13];

    // The year is only wanted for the leap flag; emitChronoYear reads the January turn off the day
    // of year rather than off the month, so this passes `rem`. The month step still runs for this
    // node - the month-length arithmetic below is what needs it.
    emitChronoYear(cb, era, century, yearOfCentury, rem, julian);
    cb.astore(year);
    emitChronoDayOfMonth(cb, rem, marchMonth, neri, divider);
    cb.astore(dayOfMonth);

    // The current month's length: monthStart(mp + 1) - monthStart(mp), except February (the
    // March-based year's last month), which needs the year's own total length instead - the
    // same split emitAddMonths uses for the month it lands on.
    //
    // This is the one node whose month-length arithmetic reads the prefix slot directly rather than
    // through a tail, so it is the one place the affine numerator's axis has to be handled here:
    // under the numerator, mpNextClamped holds a 3-based index and the February test is against
    // MONTH3_JANUARY + 1 rather than MARCH_YEAR_JANUARY + 1. Both are "one past the year's last
    // month" on their own axis.
    int lastMonth = neri ? VarkaChrono.MONTH3_JANUARY + 1 : VarkaChrono.MARCH_YEAR_JANUARY + 1;
    if (neri) {
      emitMonthIndex3(cb, marchMonth);
      emitMonthStart3FromStack(cb);
    } else {
      emitMonthStart(cb, marchMonth, divider);
    }
    cb.astore(monthStart);
    if (neri) {
      emitMonthIndex3(cb, marchMonth);
    } else {
      cb.aload(marchMonth);
    }
    cb.loadConstant(1);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.loadConstant(lastMonth);
    cb.invokevirtual(INT_VECTOR, "min", LANEWISE_VI);
    cb.astore(mpNextClamped);
    if (neri) {
      cb.aload(mpNextClamped);
      emitMonthStart3FromStack(cb);
    } else {
      emitMonthStart(cb, mpNextClamped, divider);
    }
    cb.astore(monthStartNext);

    cb.aload(monthStartNext);
    cb.aload(monthStart);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.aload(s.species);
    cb.loadConstant(365);
    cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
    cb.loadConstant(1);
    emitLeapFlag(cb, year);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    cb.aload(monthStart);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    if (neri) {
      emitMonthIndex3(cb, marchMonth);
    } else {
      cb.aload(marchMonth);
    }
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(lastMonth);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(INT_VECTOR, "blend", BLEND);
    cb.astore(length);

    cb.aload(days);
    cb.aload(length);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.aload(dayOfMonth);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
  }

  /**
   * The day-of-era step: one round-down division and one carry over a biased day, which is
   * defined only over {@link VarkaChrono#NARROW_MIN_DAYS}..{@link VarkaChrono#NARROW_MAX_DAYS} -
   * years -12800 to 33134, which contains every date SQL can write but is reachable past by
   * {@code date_add}.
   *
   * <p><b>No guard here.</b> Every {@link Chrono} node used to carry a per-lane range check on this
   * step's input, declining the whole batch to the row engine when a lane fell outside the range
   * above. That guard re-verified the same fact at every calendar extraction downstream of a value,
   * including ones CSE and the calendar fragment sharing had already proven in range together -
   * real cost with no new information on the common path. It was replaced by two halves: the
   * compiler bounds the day shift under every calendar node and declines an entry whose interval
   * can leave this range (literal offsets, {@code next_day}, {@code add_months}, {@code last_day},
   * and their compositions), and the one producer it cannot bound - {@code date_add} /
   * {@code date_sub} with a column offset - carries the check on its own result (
   * {@code emitRangeGuard} ), once per producer instead of once per reader. This step therefore
   * trusts its input, and the {@link VarkaEmitOptions#guardDayProducers} reference variant is the
   * only way to hand it a day outside the range. (The month count reuses the same guard block on a
   * separate producer and a separate range - {@code AddMonths} ' own month count against the magic
   * multiply's bound. That one does bear on this step: when a calendar node reads an
   * {@code add_months} result, the day decomposed here is that result, and nothing checks it at run
   * time - it is inside the range only because {@code dayRange} bounded the count's shift at
   * compile time, which it can do only because the count guard fires.)
   */
  private static void emitEra(CodeBuilder cb, int days, int era, int rem, int mask,
      Divider divider) {
    // w = days + BIAS, non-negative throughout the range, so one round-down magic and one
    // carry give the era - and the bias's whole eras come back off in the year assembly.
    cb.aload(days);
    cb.loadConstant(VarkaChrono.NARROW_BIAS);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    cb.astore(rem);
    cb.aload(rem);
    emitDivide(cb, divider, ChronoDivide.ERA_NARROW);
    cb.astore(era);
    cb.aload(rem);
    cb.aload(era);
    cb.loadConstant(VarkaChrono.ERA_DAYS);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
    cb.astore(rem);
    if (divider.carries(ChronoDivide.ERA_NARROW)) {
      emitCarry(cb, era, rem, VarkaChrono.ERA_DAYS, mask);
    }
    cb.aload(era);
    cb.loadConstant(VarkaChrono.NARROW_ERA_BIAS);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI);
    cb.astore(era);
  }

  /**
   * Every constant division the calendar prefix performs, one constant per site: the divisor, the
   * Granlund-Montgomery pair that stands in for it, and whether the double lane's reciprocal form
   * is exact over the dividends that site can produce.
   *
   * <p>The table exists so that a division site names a division rather than a pair of magic
   * numbers. Two sites can share a multiplier and differ only in the shift -
   * {@link #YEAR_OF_ERA_400} and {@link #YEAR_OF_ERA_100} both use {@code YEAR_CENTURY_M} - so the
   * divisor is not recoverable from the constants at the call site, and neither is the range the
   * dividend stays in.
   *
   * <p>{@code recipExact} is transcribed from {@code sql/varka/plans/verify_double_division.py},
   * which decides it per (divisor, range) by exhaustive probe rather than by the size of the
   * divisor: {@link #ERA_NARROW} and {@link #JULIAN_CENTURY} divide by the same 146097 and answer
   * differently, because the Julian site's dividends are the values congruent to 3 mod 4 and the
   * multiple where the reciprocal form fails is not one of them. A site marked false falls back to
   * {@link VarkaEmitOptions.Division#MAGIC} under {@code DOUBLE_RECIP}, which is what a shipping
   * default would do; it is never emitted with a form that would compute a wrong quotient.
   */
  enum ChronoDivide {
    QUARTER(3, VarkaChrono.QUARTER_M, VarkaChrono.QUARTER_K, true),
    CENTURY(36524, VarkaChrono.CENTURY_M, VarkaChrono.CENTURY_K, true),
    YEAR_OF_CENTURY(365, VarkaChrono.YEAR_M, VarkaChrono.YEAR_K, true),
    MONTH(153, VarkaChrono.MONTH_M, VarkaChrono.MONTH_K, true),
    JULIAN_CENTURY(VarkaChrono.ERA_DAYS,
        VarkaChrono.JULIAN_CENTURY_M, VarkaChrono.JULIAN_CENTURY_K, true),
    JULIAN_YEAR(1461, VarkaChrono.JULIAN_YEAR_M, VarkaChrono.JULIAN_YEAR_K, true),
    DAY_OF_MONTH(2141, VarkaChrono.DOM_M, VarkaChrono.DOM_K, true),
    MONTH_START(5, VarkaChrono.DAY_M, VarkaChrono.DAY_K, true),
    MONTH_ARITH(12, VarkaChrono.MONTH_ARITH_M, VarkaChrono.MONTH_ARITH_K, true),
    YEAR_OF_ERA_400(400, VarkaChrono.YEAR_CENTURY_M, VarkaChrono.YEAR_QUATERCENTENNIAL_K, true),
    YEAR_OF_ERA_100(100, VarkaChrono.YEAR_CENTURY_M, VarkaChrono.YEAR_CENTURY_K, true),
    WEEK(7, VarkaChrono.WEEK_M, VarkaChrono.WEEK_K, true),
    ERA_NARROW(VarkaChrono.ERA_DAYS, VarkaChrono.NARROW_ERA_M, VarkaChrono.NARROW_ERA_K, false);

    final int divisor;
    final int m;
    final int k;
    final boolean recipExact;

    ChronoDivide(int divisor, int m, int k, boolean recipExact) {
      this.divisor = divisor;
      this.m = m;
      this.k = k;
      this.recipExact = recipExact;
    }
  }

  /**
   * {@code [v] -> [v / d]} for one of the prefix's constant divisors, under whichever lowering
   * {@link VarkaEmitOptions#division()} selected. Every division in {@link #emitChrono} goes
   * through here, which is what makes the A/B a single switch rather than fifteen edits.
   */
  private static void emitDivide(CodeBuilder cb, Divider divider, ChronoDivide div) {
    if (divider.formFor(div) == VarkaEmitOptions.Division.MAGIC) {
      emitMagic(cb, div.m, div.k);
      return;
    }
    // The calendar prefix is int-lane by construction, which `requireIntLane` states on entry
    // to every one of its helpers; the lane is passed rather than read for that reason.
    VarkaDivisionLowering.emitDoubleDivide(cb, Lane.INT, divider, div.divisor);
  }

  /** {@code [v] -> [(v * m) >>> k]}, the shape every division in {@link #emitChrono} takes. */
  private static void emitMagic(CodeBuilder cb, int m, int k) {
    cb.loadConstant(m);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    emitShift(cb, "LSHR", k);
  }

  /**
   * One correction step of a round-down magic division: where the remainder still reaches the
   * divisor, the quotient was one short. Consumes nothing and leaves nothing on the stack -
   * both operands are locals, because the pair is applied up to twice in a row.
   */
  private static void emitCarry(CodeBuilder cb, int quotient, int remainder, int divisor,
      int mask) {
    cb.aload(remainder);
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(divisor);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.astore(mask);
    cb.aload(quotient);
    cb.loadConstant(1);
    cb.aload(mask);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    cb.astore(quotient);
    cb.aload(remainder);
    cb.loadConstant(divisor);
    cb.aload(mask);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI_MASKED);
    cb.astore(remainder);
  }

  /**
   * Leaves the mask of lanes whose March-based year has already turned into January, read off
   * whichever axis {@code t[5]} carries: {@code monthIndex3 >= 13} under the affine numerator,
   * {@code marchMonth >= 10} under the 0-based form. The two are the same test, and
   * {@code VarkaChronoSuite} asserts that on all 366 days rather than leaving it here.
   */
  private static void emitJanuaryMask(CodeBuilder cb, int monthSlot, boolean neri) {
    if (neri) {
      emitMonthIndex3(cb, monthSlot);
      cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
      cb.loadConstant(VarkaChrono.MONTH3_JANUARY);
    } else {
      cb.aload(monthSlot);
      cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
      cb.loadConstant(VarkaChrono.MARCH_YEAR_JANUARY);
    }
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
  }

  /** The month index on Neri-Schneider's 3-based axis, out of the numerator's high half. */
  private static void emitMonthIndex3(CodeBuilder cb, int monthSlot) {
    cb.aload(monthSlot);
    emitShift(cb, "LSHR", VarkaChrono.MONTH_NUM_K);
  }

  /**
   * Leaves the day of the March-based year on which the month begins, from a 3-based index
   * already on the stack: {@code (979 * m3 - 2919) >>> 5}. A shift where the 0-based form needs
   * a magic multiply, and its numerator never goes negative (18 to 10787 over the twelve
   * months), which is what lets the shift be logical.
   */
  private static void emitMonthStart3FromStack(CodeBuilder cb) {
    cb.loadConstant(VarkaChrono.MONTH_START_M);
    cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
    cb.loadConstant(-VarkaChrono.MONTH_START_SUB);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    emitShift(cb, "LSHR", VarkaChrono.MONTH_START_K);
  }

  /** The same mask as {@link #emitJanuaryMask}, taken off the March-based day of year instead
   * of the month it would otherwise be derived from - {@code (5 * doy + 2) / 153 >= 10} is
   * {@code doy >= 306} exactly, see {@link VarkaChrono#MARCH_TO_JANUARY_DAYS}. This is what
   * lets a year tail run without the prefix's month step. */
  private static void emitJanuaryMaskFromDayOfYear(CodeBuilder cb, int dayOfYear) {
    cb.aload(dayOfYear);
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(VarkaChrono.MARCH_TO_JANUARY_DAYS);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
  }

  /**
   * Leaves the January-based month. On the 0-based axis that is {@code mp + 3}, less 12 once the
   * year has turned; on the 3-based axis it is {@code m3} itself, less 12 - one operation fewer,
   * which is the whole reason the axis changes rather than being converted back.
   *
   * <p>The 3-based path computes {@code m3} once and reaches it twice with {@code dup}/
   * {@code swap} rather than a scratch local: the mask needs the same vector the result is
   * built from, and a second shift would put this tail back where the 0-based one was.
   */
  private static void emitChronoMonth(CodeBuilder cb, int monthSlot, boolean neri) {
    if (neri) {
      emitMonthIndex3(cb, monthSlot);
      cb.dup();
      cb.loadConstant(12);
      cb.swap();
      cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
      cb.loadConstant(VarkaChrono.MONTH3_JANUARY);
      cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI_MASKED);
    } else {
      cb.aload(monthSlot);
      cb.loadConstant(3);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
      cb.loadConstant(12);
      emitJanuaryMask(cb, monthSlot, false);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI_MASKED);
    }
  }

  /** The date a calendar node decomposes - the one child its shared prefix depends on. */
  static VarkaVectorIR chronoChild(VarkaVectorIR node) {
    return switch (node) {
      case Year n -> n.days();
      case Month n -> n.days();
      case DayOfMonth n -> n.days();
      case Quarter n -> n.days();
      case DayOfYear n -> n.days();
      case AddMonths n -> n.days();
      case LastDay n -> n.days();
      case TruncDate n -> n.days();
      case TruncDateDynamic n -> n.days();
      case WeekOfYear n -> n.days();
      default -> throw new IllegalStateException("not a calendar node: " + node);
    };
  }

  /**
   * Whether {@code node}'s tail reads the March-based month the prefix would otherwise leave in
   * {@code t[5]} - an exhaustive switch over the same family {@link #chronoChild} covers, so a
   * new calendar node is a compile error here rather than a silent "yes" that quietly costs
   * five ops, or a silent "no" that reads an uninitialised local.
   *
   * <p>Only {@link Year} answers no today: it takes the January turn off the day of year, which
   * is the same test one step earlier in the chain ({@link VarkaChrono#MARCH_TO_JANUARY_DAYS}).
   * {@link Month} and {@link Quarter} go through {@code emitChronoMonth}, {@link DayOfMonth}
   * through {@code emitMonthStart}, and {@link AddMonths} needs both.
   */
  static boolean tailReadsMarchMonth(VarkaVectorIR node) {
    return switch (node) {
      case Year n -> false;
      case Month n -> true;
      case DayOfMonth n -> true;
      case Quarter n -> true;
      case DayOfYear n -> false;
      case AddMonths n -> true;
      case LastDay n -> true;
      // MONTH reads the numerator for the zero-based day of month, QUARTER goes through
      // emitChronoMonth for the quarter; YEAR takes the January turn off the day of year like
      // Year and DayOfYear do, under either lowering (the recompose form's January month is a
      // constant).
      case TruncDate n -> n.level() != TruncLevel.YEAR;
      // Its MONTH and QUARTER results are the literal tails', so it always reads the month.
      case TruncDateDynamic n -> true;
      // The week tail is the day-of-year tail plus a division: no month.
      case WeekOfYear n -> false;
      default -> throw new IllegalStateException("not a calendar node: " + node);
    };
  }
}
