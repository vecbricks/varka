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

import java.util.List;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;

/**
 * The operand positions whose node kinds the emitter restricts, which kinds each admits, and
 * which operand of each IR node sits in one - one statement that the compiler asks when it builds
 * an operand and the emitter asks when it accepts one (VARKA-86). Stated twice, the rule drifted:
 * {@code date_add(d, weekday(d2) + 1)} was fused in EXPLAIN and refused at emit time, which the
 * evaluator turns into a silent per-batch fallback.
 *
 * <p>{@link #admits}, {@link #takes} and {@link #restrictedOperands} are switches with no
 * default, over the positions and over every node kind, so a new position or node kind does not
 * compile until each says what it takes. A position checks its operand's own kind, not the
 * subtree under it: that a day count is not a date further down is the compiler arms' to build
 * right. Narrowing a position here narrows both sides; widening one also needs the compiler arm
 * that builds the operand, which filters Catalyst expressions before it asks.
 *
 * <p>The other operands take any int-valued node and the emitter checks nothing there, with one
 * structural exception outside this enum: {@code WeekOfYear} is defined over a {@code ThursdayOf}
 * child only.
 */
public enum VarkaOperandPosition {
  /**
   * The day count of {@code AddDays}/{@code SubDays}: a literal slot, a column, or int arithmetic
   * over those, {@code date_add(d, i * 7)}.
   */
  DAY_OFFSET,

  /**
   * The month count of {@code AddMonths}: a literal slot, a column, or int arithmetic -
   * {@code d - ym_col} is an {@code IntNeg} over the interval column, {@code CAST(i AS INTERVAL
   * YEAR)} that column times twelve. A derived count is safe here because {@code AddMonths}
   * guards the count's own value at run time ({@code Analysis.Refusal.MONTH_COUNT}).
   */
  MONTH_COUNT,

  /**
   * {@code NextDay}'s weekday: a literal slot or a column. Its range comes from a compile-time
   * fold or from the derived weekday leaf, and no runtime guard stands behind it, so a derived
   * value here would reach {@code emitFloorMod7} unchecked.
   */
  WEEKDAY,

  /**
   * {@code TruncDateDynamic}'s level: a column, the evaluator's derived int32 level column. A
   * literal level is the literal {@code TruncDate} node, which the compiler builds instead.
   */
  TRUNC_LEVEL;

  /** An operand in a restricted position, with its name in a refusal. */
  public record Restricted(VarkaOperandPosition position, VarkaVectorIR operand, String name) {
  }

  /** Whether this position takes {@code node}. */
  public boolean admits(VarkaVectorIR node) {
    return switch (node) {
      case ColumnRef _ -> true;
      // A decimal's unscaled value is no day count, month count, weekday or level.
      case DecimalColumn _ -> false;
      case LiteralSlot _ -> switch (this) {
        case DAY_OFFSET, MONTH_COUNT, WEEKDAY -> true;
        case TRUNC_LEVEL -> false;
      };
      case IntArith _, IntNeg _ -> switch (this) {
        case DAY_OFFSET, MONTH_COUNT -> true;
        case WEEKDAY, TRUNC_LEVEL -> false;
      };
      case AddDays _, SubDays _, DateDiff _, IfElse _, Greatest _, Least _, DayOfWeek _,
           WeekDay _, DayOfWeekIso _, NextDay _, ThursdayOf _, AddMonths _, MakeDate _,
           ConstDivide _, BoundedDivide _, BitOp _, BitCount _, GuardedDay _, GuardedRange _,
           WidenLane _,
           NarrowLane _, Year _, Month _, DayOfMonth _, Quarter _, DayOfYear _, LastDay _,
           TruncDate _, TruncDateDynamic _, WeekOfYear _,
           Compare _, NullSafeEqual _, And _, Or _, Not _, IsNotNull _, InRanges _,
           BoolColumn _, CondConst _, BoolCompare _, CondIf _, BoolNullSafeEq _,
           CondValue _, BoolOutput _ -> false;
    };
  }

  /** What this position takes, for a refusal; {@link #admits} in words. */
  public String takes() {
    return switch (this) {
      case DAY_OFFSET, MONTH_COUNT -> "a literal slot, a column or int arithmetic";
      case WEEKDAY -> "a literal slot or a column";
      case TRUNC_LEVEL -> "a column";
    };
  }

  /** The operands of {@code node} that sit in a restricted position; none for most nodes. */
  public static List<Restricted> restrictedOperands(VarkaVectorIR node) {
    return switch (node) {
      case AddDays n -> List.of(
          new Restricted(DAY_OFFSET, n.offset(), "date_add's day offset"));
      case SubDays n -> List.of(
          new Restricted(DAY_OFFSET, n.offset(), "date_sub's day offset"));
      case AddMonths n -> List.of(
          new Restricted(MONTH_COUNT, n.months(), "add_months' month count"));
      case NextDay n -> List.of(
          new Restricted(WEEKDAY, n.offset(), "next_day's weekday"));
      case TruncDateDynamic n -> List.of(
          new Restricted(TRUNC_LEVEL, n.level(), "trunc's level"));
      case ColumnRef _, DecimalColumn _, LiteralSlot _, DateDiff _, IfElse _, Greatest _, Least _,
           DayOfWeek _,
           WeekDay _, DayOfWeekIso _, ThursdayOf _, MakeDate _, IntArith _, IntNeg _,
           ConstDivide _, BoundedDivide _, BitOp _, BitCount _, GuardedDay _, GuardedRange _,
           WidenLane _,
           NarrowLane _, Year _, Month _, DayOfMonth _, Quarter _, DayOfYear _, LastDay _,
           TruncDate _, WeekOfYear _, Compare _, NullSafeEqual _, And _, Or _, Not _, IsNotNull _,
           InRanges _, BoolColumn _, CondConst _, BoolCompare _, CondIf _, BoolNullSafeEq _,
           CondValue _, BoolOutput _ -> List.of();
    };
  }
}
