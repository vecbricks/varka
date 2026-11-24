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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The operand positions whose node kinds the emitter restricts, which kinds each admits, and
 * which operand of each IR node sits in one - one statement that the compiler asks when it builds
 * an operand and the emitter asks when it accepts one (VARKA-86). Stated twice, the rule drifted:
 * {@code date_add(d, weekday(d2) + 1)} was fused in EXPLAIN and refused at emit time, which the
 * evaluator turns into a silent per-batch fallback.
 *
 * <p>{@link #takes}, {@link #LITERAL_FILLS} and {@link #ARITH_FILLS} are switches with no default
 * over the positions, and {@link #admits} and {@link #restrictedOperands} read the node's
 * {@link VarkaNodeProps}, which every record must supply, so a new node kind does not compile until
 * it says what it takes, and a new position does not compile until the literal's and the
 * arithmetic's sets decide it. Elsewhere a new position compiles: a column's {@code EnumSet.allOf}
 * admits it, and the narrowings, which name the one position their shape serves, and every other
 * record refuse it until its {@code fills} names it, so its author reviews each. A position checks
 * its operand's own kind, not the subtree under it: that a day count is not a date further down is
 * the compiler arms' to build right. Narrowing a position here narrows both sides; widening one
 * also needs the compiler arm that builds the operand, which filters Catalyst expressions before it
 * asks.
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
   * YEAR)} that column times twelve; or a bigint narrowed to the int lane under a guard inside
   * the month range, {@code timestampadd(MONTH, l, ntz)}'s quantity. A derived count is safe here
   * because {@code AddMonths} guards the count's own value at run time
   * ({@code Analysis.Refusal.MONTH_COUNT}).
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

  /** The positions a {@code LiteralSlot} fills: a literal is no level, which is a column. */
  static final Set<VarkaOperandPosition> LITERAL_FILLS = where(p -> switch (p) {
    case DAY_OFFSET, MONTH_COUNT, WEEKDAY -> true;
    case TRUNC_LEVEL -> false;
  });

  /** The positions an {@code IntArith} or an {@code IntNeg} fills: a day or month count. */
  static final Set<VarkaOperandPosition> ARITH_FILLS = where(p -> switch (p) {
    case DAY_OFFSET, MONTH_COUNT -> true;
    case WEEKDAY, TRUNC_LEVEL -> false;
  });

  private static Set<VarkaOperandPosition> where(Predicate<VarkaOperandPosition> fills) {
    Set<VarkaOperandPosition> set = EnumSet.noneOf(VarkaOperandPosition.class);
    for (VarkaOperandPosition p : values()) {
      if (fills.test(p)) {
        set.add(p);
      }
    }
    // Set.copyOf, so that VarkaNodeProps' own copy returns it rather than copying it again
    return Set.copyOf(set);
  }

  /** Whether this position takes {@code node}. */
  public boolean admits(VarkaVectorIR node) {
    return node.props().fills().contains(this);
  }

  /** What this position takes, for a refusal; {@link #admits} in words. */
  public String takes() {
    return switch (this) {
      case DAY_OFFSET -> "a literal slot, a column or int arithmetic";
      case MONTH_COUNT -> "a literal slot, a column, int arithmetic or a bigint narrowed "
          + "under the month range's guard";
      case WEEKDAY -> "a literal slot or a column";
      case TRUNC_LEVEL -> "a column";
    };
  }

  /** The operands of {@code node} that sit in a restricted position; none for most nodes. */
  public static List<Restricted> restrictedOperands(VarkaVectorIR node) {
    return node.props().restricted();
  }
}
