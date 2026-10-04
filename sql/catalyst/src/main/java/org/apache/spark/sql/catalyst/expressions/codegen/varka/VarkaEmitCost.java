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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MakeDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDate;

/**
 * A prediction of what a loop-method group will measure once emitted - each of its four
 * methods' bytecode length and Vector API call sites - made from the group's IR alone, without
 * building anything.
 *
 * <p><b>Why it exists.</b> The emitter's limits are read off the built class
 * ({@link VarkaEmittedClass}): a method's bytes against {@link VarkaEmitBudget#HUGE_METHOD_LIMIT}
 * and its call sites against {@link VarkaEmitBudget#CALL_SITE_BUDGET}. So the only way to learn
 * whether a candidate group fits has been to emit it, and a grouping that asks about many
 * candidates - an exact partition asks about every contiguous run of outputs - cannot afford an
 * emission per question. This class answers the same question in the same units for the cost of
 * the walk the grouping already does. The built class stays the last word: a prediction decides
 * where the first grouping closes a group ({@link VarkaEmitOptions#predictGrouping}), and a group
 * that still measures over a limit is split exactly as before.
 *
 * <p><b>The features.</b> A group is described by counts of features, and each is priced per
 * method. The grouping's own walk ({@code VarkaLoopEmitter.GroupOps}) feeds them, so they follow
 * its sharing exactly: every method carries a fixed prologue and loop ({@code fixed}); every
 * output a store ({@code out}, or {@code out/cond} for a selection's condition); every distinct
 * node, its leaves included, its own code, keyed by {@link #kindOf}, since two nodes of one kind
 * but different modes or lanes lower differently; and a calendar node is priced as its tail
 * alone, with the civil-from-days prefix counted where the emitter computes one - once per date
 * the group decomposes when prefixes are shared, once per calendar node when they are not
 * ({@code prefix}) - once more where a tail reads the month step ({@code prefix/month}), or as
 * a load of an earlier group's prefix where the emitter materializes it ({@code prefix/load}).
 *
 * <p><b>The prices.</b> {@link VarkaEmitCostTable} holds one price per feature, fitted by least
 * squares over emitted groups of every width, generated and pinned by {@code VarkaEmitCostSuite}.
 * A second pricing, each feature measured alone beside its own children, was built beside it and
 * lost: it over-prices a wide group by about half, because it charges every node for validity
 * code a wide group shares. {@code VarkaEmitCostAuditSuite} scores both against emitted classes
 * in {@code sql/varka/emit_cost_audit.json}. See {@code VARKA-199.md}.
 *
 * <p><b>What it assumes.</b> The prices are read at the default lowering options and at sixteen
 * int lanes. Other options and widths change a lowering here and there; the prediction is then a
 * little off, which the measurement after the build corrects.
 */
final class VarkaEmitCost {

  private VarkaEmitCost() {}

  /**
   * The eight quantities a prediction has: the bytes, then the call sites, of the dense loop, the
   * masked loop, the dense epilogue and the masked epilogue, in that order. The order is the
   * table's column order.
   */
  static final List<String> METHODS = VarkaMethodNames.GROUP_METHOD_KINDS;

  /** How many quantities a feature is priced in: bytes and call sites for each method. */
  static final int QUANTITIES = 2 * 4;

  /** The feature every method carries once, suffixed with the kernel's lane. */
  static final String FIXED = "fixed/";
  /** The feature every value output carries once, its store and its validity, and the lane. */
  static final String OUTPUT = "out/";
  /** The feature a selection kernel's one condition output carries, and the lane. */
  static final String SELECTION = "out/cond/";
  /** A civil-from-days prefix the group decomposes. */
  static final String PREFIX = "prefix";
  /** The month step of a prefix, emitted when a tail over that date reads it. */
  static final String PREFIX_MONTH = "prefix/month";
  /** A prefix an earlier group materialized, loaded in place of its decomposition. */
  static final String PREFIX_LOAD = "prefix/load";

  /**
   * The feature a node is counted under: its record name, with the parts of the node that choose
   * between lowerings appended - the lane of a leaf or of lane-generic arithmetic, an arithmetic's
   * operator and overflow mode, a comparison's operator, a division's divisor sign, a truncation's
   * level, and whether {@code make_date} fails or nulls. Operands that choose no lowering (a
   * column's ordinal, a literal's slot, a guard's bounds) are left out, so the feature set stays
   * finite over the IR.
   */
  static String kindOf(VarkaVectorIR node) {
    String name = node.getClass().getSimpleName();
    return switch (node) {
      case ColumnRef n -> name + '/' + n.lane();
      case LiteralSlot n -> name + '/' + n.lane();
      case IntArith n -> name + '/' + n.op() + '/' + n.mode() + '/' + n.laneType();
      case IntNeg n -> name + '/' + n.mode() + '/' + n.laneType();
      case ConstDivide n -> name + '/' + n.laneType() + (n.divisor() < 0 ? "/neg" : "/pos");
      case GuardedRange n -> name + '/' + n.laneType();
      case Compare n -> name + '/' + n.op() + '/' + n.laneType();
      case TruncDate n -> name + '/' + n.level();
      case MakeDate n -> name + (n.failOnError() ? "/ansi" : "/null");
      default -> isValueGeneric(node) ? name + '/' + node.laneType() : name;
    };
  }

  /** The lane-generic nodes whose lowering follows their lane, beyond those named above. */
  private static boolean isValueGeneric(VarkaVectorIR node) {
    return node instanceof VarkaVectorIR.Greatest || node instanceof VarkaVectorIR.Least
        || node instanceof VarkaVectorIR.IfElse || node instanceof VarkaVectorIR.IsNotNull
        || node instanceof VarkaVectorIR.And || node instanceof VarkaVectorIR.Or
        || node instanceof VarkaVectorIR.Not;
  }

  /**
   * The features of one group as it is formed, output by output, and what they cost at
   * {@code prices}. {@code GroupOps} calls it for each node the group does not yet hold and for
   * each prefix it does not yet compute, so the tally counts what the emitter emits once, once.
   * The prediction is a running sum, so asking for it costs nothing per output.
   */
  static final class Tally {
    private final Map<String, double[]> prices;
    private final double[] total;
    /** Whether a feature was counted that {@link #prices} has no price for. */
    private boolean unpriced;
    /** Where the month step has been counted: a date, or a node when prefixes are not shared. */
    private final Set<Object> months;
    /** The counts by feature, kept only for the suites that fit and audit the prices. */
    private final Map<String, Integer> counts;

    /**
     * A new group of a kernel on {@code lane}, priced at {@code prices}; {@code keepCounts}
     * keeps the feature counts too.
     */
    Tally(LaneType lane, Map<String, double[]> prices, boolean keepCounts) {
      this(prices, new double[QUANTITIES], false, new HashSet<>(),
          keepCounts ? new HashMap<>() : null);
      count(FIXED + lane);
    }

    private Tally(Map<String, double[]> prices, double[] total, boolean unpriced,
        Set<Object> months, Map<String, Integer> counts) {
      this.prices = prices;
      this.total = total;
      this.unpriced = unpriced;
      this.months = months;
      this.counts = counts;
    }

    Tally copy() {
      return new Tally(prices, total.clone(), unpriced, new HashSet<>(months),
          counts == null ? null : new HashMap<>(counts));
    }

    /** One more output: its store, and the lane its value is stored in. */
    void output(VarkaVectorIR root, LaneType lane) {
      count((root instanceof Cond ? SELECTION : OUTPUT) + lane);
    }

    /** A node the group did not hold before. */
    void node(VarkaVectorIR node) {
      count(kindOf(node));
    }

    /** A prefix the group computes, or loads when {@code loaded}. */
    void prefix(boolean loaded) {
      count(loaded ? PREFIX_LOAD : PREFIX);
    }

    /** A tail that reads the month step of the prefix {@code key} names. */
    void month(Object key) {
      if (months.add(key)) {
        count(PREFIX_MONTH);
      }
    }

    /**
     * The eight predicted quantities, in {@link #METHODS} order, bytes then call sites; null when
     * a feature has no price, which a caller must read as "unknown" rather than as zero.
     */
    double[] predicted() {
      return unpriced ? null : total.clone();
    }

    /** The counts by feature, in feature order; empty unless the tally keeps them. */
    TreeMap<String, Integer> counts() {
      return counts == null ? new TreeMap<>() : new TreeMap<>(counts);
    }

    private void count(String feature) {
      if (counts != null) {
        counts.merge(feature, 1, Integer::sum);
      }
      double[] price = prices.get(feature);
      if (price == null) {
        unpriced = true;
        return;
      }
      for (int q = 0; q < QUANTITIES; q++) {
        total[q] += price[q];
      }
    }
  }

  /**
   * What {@code prices} predict for a group with {@code counts}, as {@link Tally#predicted}; for
   * the audit, which prices one group's counts under more than one table.
   */
  static double[] predict(Map<String, double[]> prices, Map<String, Integer> counts) {
    double[] total = new double[QUANTITIES];
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      double[] price = prices.get(e.getKey());
      if (price == null) {
        return null;
      }
      for (int q = 0; q < QUANTITIES; q++) {
        total[q] += price[q] * e.getValue();
      }
    }
    return total;
  }

  /** The largest predicted byte count among the four methods of {@code prediction}. */
  static double maxBytes(double[] prediction) {
    return Math.max(Math.max(prediction[0], prediction[1]), Math.max(prediction[2], prediction[3]));
  }

  /** The largest predicted call-site count among the four methods of {@code prediction}. */
  static double maxSites(double[] prediction) {
    return Math.max(Math.max(prediction[4], prediction[5]), Math.max(prediction[6], prediction[7]));
  }
}
