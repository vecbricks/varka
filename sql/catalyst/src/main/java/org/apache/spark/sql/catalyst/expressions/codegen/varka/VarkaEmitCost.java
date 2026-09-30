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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChronoLowering.chronoChild;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChronoLowering.tailReadsMarchMonth;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitBudget.isChrono;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.childrenOf;

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
 * a walk over the group's nodes. The built class stays the last word: a prediction decides where
 * the first grouping closes a group ({@link VarkaEmitOptions#predictGrouping}), and a group that
 * still measures over a limit is split exactly as before.
 *
 * <p><b>The features.</b> A group is described by counts of features, and each model prices a
 * feature per method. The features follow what the emitter emits once per group, per output and
 * per distinct node, which is why the counting mirrors {@code VarkaLoopEmitter}'s grouping
 * accounting: every method carries a fixed prologue and loop ({@code fixed}); every output a
 * store ({@code out}, or {@code out/cond} for a selection's condition); every distinct node,
 * its leaves included, its own code, keyed by {@link #kindOf}, since two nodes of one kind but
 * different modes or lanes lower differently; and a calendar node is priced as its tail alone,
 * with the civil-from-days prefix counted once per date the group decomposes
 * ({@code prefix}), once more per such date whose tails read the month step
 * ({@code prefix/month}), or as a load of an earlier group's prefix where the emitter
 * materializes it ({@code prefix/load}).
 *
 * <p><b>The two models.</b> {@link Model#REGISTER} prices each feature with what it measures when
 * emitted, by difference, beside a fixed partner; {@link Model#REGRESSION} with coefficients
 * fitted by least squares on a corpus of emitted groups. Both tables are
 * {@link VarkaEmitCostTable}, generated and pinned by {@code VarkaEmitCostSuite}, and
 * {@code VarkaEmitCostAudit} measures both against emitted classes. See {@code PLAN_TASK_199.md}.
 *
 * <p><b>What it assumes.</b> The tables are read at the default lowering options and at sixteen
 * int lanes. Other options and widths change a lowering here and there; the prediction is then a
 * little off, which the measurement after the build corrects.
 */
final class VarkaEmitCost {

  private VarkaEmitCost() {}

  /** The two ways a feature is priced; see the class doc. */
  enum Model { REGISTER, REGRESSION }

  /** The model {@link VarkaEmitOptions#predictGrouping} asks; chosen by the audit. */
  static final Model CHOSEN = Model.REGRESSION;

  /**
   * The eight quantities a prediction has: the bytes, then the call sites, of the dense loop, the
   * masked loop, the dense epilogue and the masked epilogue, in that order. The order is the
   * table's column order.
   */
  static final List<String> METHODS =
      List.of("loopDense", "loopMasked", "epilogueDense", "epilogueMasked");

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
   * The feature counts of one group as it is formed, output by output: the distinct nodes it
   * holds, the dates whose prefix it decomposes or loads, and the counts. Adding an output that
   * repeats nodes the group already holds counts only what is new, as the emitter's common
   * subexpression elimination emits only what is new.
   */
  static final class Tally {
    /** The kernel's lane, which every fixed and per-output feature is keyed by. */
    private final VarkaVectorIR.LaneType lane;
    /** Whether a prefix an earlier group computes is loaded here rather than recomputed. */
    private final boolean materialize;
    /** The prefixes the earlier groups compute; shared with them, read here. */
    private final Set<VarkaVectorIR> earlier;
    private final Set<VarkaVectorIR> nodes;
    private final Set<VarkaVectorIR> dates;
    private final Set<VarkaVectorIR> monthDates;
    private final Map<String, Integer> counts;

    Tally(VarkaVectorIR.LaneType lane, boolean materialize, Set<VarkaVectorIR> earlier) {
      this(lane, materialize, earlier, new HashSet<>(), new HashSet<>(), new HashSet<>(),
          new HashMap<>());
      counts.put(FIXED + lane, 1);
    }

    private Tally(VarkaVectorIR.LaneType lane, boolean materialize, Set<VarkaVectorIR> earlier,
        Set<VarkaVectorIR> nodes, Set<VarkaVectorIR> dates, Set<VarkaVectorIR> monthDates,
        Map<String, Integer> counts) {
      this.lane = lane;
      this.materialize = materialize;
      this.earlier = earlier;
      this.nodes = nodes;
      this.dates = dates;
      this.monthDates = monthDates;
      this.counts = counts;
    }

    Tally copy() {
      return new Tally(lane, materialize, earlier, new HashSet<>(nodes), new HashSet<>(dates),
          new HashSet<>(monthDates), new HashMap<>(counts));
    }

    /** Counts {@code root} as one more output of the group. */
    void add(VarkaVectorIR root) {
      bump((root instanceof Cond ? SELECTION : OUTPUT) + lane);
      walk(root);
    }

    /** What {@code model} predicts for the group so far; see {@link VarkaEmitCost#predict}. */
    double[] predict(Model model) {
      return VarkaEmitCost.predict(model, counts);
    }

    /** The dates this group decomposes itself: the prefixes a later group may load. */
    Set<VarkaVectorIR> decomposedDates() {
      Set<VarkaVectorIR> own = new HashSet<>(dates);
      own.removeIf(d -> materialize && earlier.contains(d));
      return own;
    }

    /** The counts so far, by feature, in feature order. */
    TreeMap<String, Integer> counts() {
      return new TreeMap<>(counts);
    }

    private void bump(String feature) {
      counts.merge(feature, 1, Integer::sum);
    }

    private void walk(VarkaVectorIR node) {
      if (!nodes.add(node)) {
        return;
      }
      bump(kindOf(node));
      if (isChrono(node)) {
        VarkaVectorIR date = chronoChild(node);
        boolean loaded = materialize && earlier.contains(date);
        if (dates.add(date)) {
          bump(loaded ? PREFIX_LOAD : PREFIX);
        }
        if (!loaded && tailReadsMarchMonth(node) && monthDates.add(date)) {
          bump(PREFIX_MONTH);
        }
      }
      for (VarkaVectorIR child : childrenOf(node)) {
        walk(child);
      }
    }
  }

  /**
   * The eight quantities {@code model} predicts for a group with {@code counts}, in
   * {@link #METHODS} order, bytes then call sites; null when a feature has no price in the
   * model's table, which a caller must read as "unknown" rather than as zero.
   */
  static double[] predict(Model model, Map<String, Integer> counts) {
    Map<String, double[]> table = VarkaEmitCostTable.table(model);
    double[] total = new double[QUANTITIES];
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      double[] price = table.get(e.getKey());
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

  /**
   * The tally of each group of {@code groups} over {@code outputs}, formed as
   * {@code VarkaLoopEmitter} forms them: in order, each group seeing the prefixes the groups
   * before it decompose. What the audit and the suite compare against the emitted class.
   */
  static List<Tally> tallies(List<VarkaVectorIR> outputs, List<List<Integer>> groups,
      VarkaEmitOptions options) {
    boolean materialize = options.materializeChronoPrefix() && options.methodByteBudget() > 0;
    VarkaVectorIR.LaneType lane = VarkaVectorIR.emissionLane(outputs.get(0));
    Set<VarkaVectorIR> earlier = new HashSet<>();
    List<Tally> result = new java.util.ArrayList<>();
    for (List<Integer> group : groups) {
      Tally t = new Tally(lane, materialize, earlier);
      for (int o : group) {
        t.add(outputs.get(o));
      }
      result.add(t);
      earlier.addAll(t.decomposedDates());
    }
    return result;
  }
}
