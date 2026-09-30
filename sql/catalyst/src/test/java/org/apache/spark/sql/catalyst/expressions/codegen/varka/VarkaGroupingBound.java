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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitCostCorpus.Shape;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter.RunForTest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfMonth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LastDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Month;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Quarter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * The best partition of a kernel's outputs into loop-method groups, in the outputs' order, and
 * the greedy grouping held against it.
 *
 * <p>{@code VarkaLoopEmitter.groupOutputs} walks the outputs once and closes a group when the
 * next output would pass a budget. The best partition of a sequence is a dynamic program over
 * every contiguous run of outputs, quadratic in their number: each prefix's cheapest split is
 * the cheapest split of a shorter prefix plus one more group. This class prices the runs through
 * the emitter's own {@link VarkaLoopEmitter#runsForTest}, which decides what may be one group by
 * the grouping's own rule, so the best partition's search space contains the greedy partition,
 * and the gap between them is what greedy grouping costs. A run's cost is its op total in the
 * grouping's weights, which is what a group recomputes or loads of what it shares.
 * {@code VarkaGroupingBoundSuite} holds the gap to a bound over the cost model's corpus and the
 * shapes below, and holds the emitter's own exact grouping
 * ({@code VarkaEmitOptions.exactGrouping}) to this partition; the measurement that set the
 * bound is {@code PLAN_TASK_200.md} 2.
 */
final class VarkaGroupingBound {

  private VarkaGroupingBound() {}

  /** A partition of a kernel's outputs: its loop-method groups, their op total, and the groups. */
  record Partition(int groups, long ops, List<List<Integer>> grouping) {}

  /**
   * One shape's account: the greedy grouping under {@code options}, the same with the cost
   * model's prediction closing groups too, the best partition of the runs the prediction admits,
   * and the emitter's exact grouping under the prediction, which should be that partition.
   */
  record Account(String family, int index, int outputs, Partition greedy, Partition predicted,
      Partition best, Partition exact) {}

  /** {@code runs.get(i).get(k)}: the run of outputs {@code [i, i + k]}. */
  static List<List<RunForTest>> runs(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    List<List<RunForTest>> all = new ArrayList<>();
    for (int i = 0; i < roots.size(); i++) {
      all.add(VarkaLoopEmitter.runsForTest(roots, i, options));
    }
    return all;
  }

  /**
   * The partition with the fewest ops, and of those the fewest groups, over the runs the rule
   * admits; a single output is always a group of its own.
   */
  static Partition optimal(List<List<RunForTest>> runs) {
    int n = runs.size();
    long[] cost = new long[n + 1];
    int[] groups = new int[n + 1];
    int[] previous = new int[n + 1];
    Arrays.fill(cost, Long.MAX_VALUE);
    cost[0] = 0;
    for (int i = 0; i < n; i++) {
      if (cost[i] == Long.MAX_VALUE) {
        continue;
      }
      List<RunForTest> from = runs.get(i);
      for (int k = 0; k < from.size(); k++) {
        RunForTest run = from.get(k);
        // Admission is cumulative along a run: once an output was not admitted, no longer run
        // from the same start is a group.
        if (k > 0 && !run.admitted()) {
          break;
        }
        int j = i + k + 1;
        long c = cost[i] + run.ops();
        if (c < cost[j] || (c == cost[j] && groups[i] + 1 < groups[j])) {
          cost[j] = c;
          groups[j] = groups[i] + 1;
          previous[j] = i;
        }
      }
    }
    List<List<Integer>> grouping = new ArrayList<>();
    for (int j = n; j > 0; j = previous[j]) {
      List<Integer> group = new ArrayList<>();
      for (int o = previous[j]; o < j; o++) {
        group.add(o);
      }
      grouping.add(0, group);
    }
    return new Partition(groups[n], cost[n], grouping);
  }

  /** What {@code grouping} costs, read off the same runs. */
  static Partition of(List<List<RunForTest>> runs, List<List<Integer>> grouping) {
    long ops = 0;
    for (List<Integer> group : grouping) {
      List<RunForTest> from = runs.get(group.get(0));
      if (group.size() > from.size()) {
        throw new IllegalStateException("a group of " + group.size() + " outputs from output "
            + group.get(0) + " is longer than any run recorded there");
      }
      ops += from.get(group.size() - 1).ops();
    }
    return new Partition(grouping.size(), ops, grouping);
  }

  /**
   * The account of one shape under {@code options}, whose own {@code exactGrouping} is ignored:
   * the greedy and predicted columns are the greedy walk's, and the exact column the switch's.
   */
  static Account account(String family, int index, List<VarkaVectorIR> roots,
      VarkaEmitOptions options) {
    options = options.withExactGrouping(false);
    VarkaEmitOptions predicting = options.withPredictGrouping(true);
    List<List<RunForTest>> runs = runs(roots, predicting);
    Partition greedy = of(runs,
        VarkaLoopEmitter.groupsForTest(roots, options.withPredictGrouping(false)));
    Partition predicted = of(runs, VarkaLoopEmitter.groupsForTest(roots, predicting));
    Partition exact =
        of(runs, VarkaLoopEmitter.groupsForTest(roots, predicting.withExactGrouping(true)));
    return new Account(family, index, roots.size(), greedy, predicted, optimal(runs), exact);
  }

  /**
   * Whether every group of {@code grouping} is a run the rule admits - one the greedy walk could
   * have formed had it started a group at the group's first output - or else the first group
   * that is not.
   */
  static String unformable(List<List<RunForTest>> runs, List<List<Integer>> grouping) {
    for (List<Integer> group : grouping) {
      List<RunForTest> from = runs.get(group.get(0));
      if (group.size() > from.size() || !from.get(group.size() - 1).admitted()) {
        return "the group " + group + " is not a run the rule admits";
      }
    }
    return null;
  }

  /** One family's accounts summed, with the shapes where the best partition differs. */
  record Summary(String family, int shapes, long greedyGroups, long predictedGroups,
      long bestGroups, long exactGroups, long predictedOps, long bestOps, int bestFewerGroups,
      int bestMoreGroups, int bestMoreOps, double largestSavingPercent, String largestSavingAt) {

    /** The ops the best partition saves against the predicted greedy one, in percent. */
    double savedPercent() {
      return predictedOps == 0 ? 0 : 100.0 * (predictedOps - bestOps) / predictedOps;
    }
  }

  static Map<String, Summary> summarize(List<Account> accounts) {
    Map<String, List<Account>> byFamily = new LinkedHashMap<>();
    for (Account a : accounts) {
      byFamily.computeIfAbsent(a.family(), k -> new ArrayList<>()).add(a);
    }
    Map<String, Summary> out = new LinkedHashMap<>();
    byFamily.forEach((family, list) -> {
      long greedyGroups = 0;
      long predictedGroups = 0;
      long bestGroups = 0;
      long exactGroups = 0;
      long predictedOps = 0;
      long bestOps = 0;
      int fewer = 0;
      int more = 0;
      int moreOps = 0;
      double largest = 0;
      String at = "none";
      for (Account a : list) {
        greedyGroups += a.greedy().groups();
        predictedGroups += a.predicted().groups();
        bestGroups += a.best().groups();
        exactGroups += a.exact().groups();
        predictedOps += a.predicted().ops();
        bestOps += a.best().ops();
        if (a.best().groups() < a.predicted().groups()) {
          fewer++;
        }
        if (a.best().groups() > a.predicted().groups()) {
          more++;
        }
        if (a.best().ops() > a.predicted().ops()) {
          moreOps++;
        }
        double saving = a.predicted().ops() == 0 ? 0
            : 100.0 * (a.predicted().ops() - a.best().ops()) / a.predicted().ops();
        if (saving > largest) {
          largest = saving;
          at = a.index() + " (" + a.predicted().groups() + " to " + a.best().groups()
              + " groups, " + a.predicted().ops() + " to " + a.best().ops() + " ops)";
        }
      }
      out.put(family, new Summary(family, list.size(), greedyGroups, predictedGroups, bestGroups,
          exactGroups, predictedOps, bestOps, fewer, more, moreOps, largest, at));
    });
    return out;
  }

  /** The summaries as the markdown table the plan quotes, one row per family. */
  static String table(Map<String, Summary> summaries) {
    StringBuilder sb = new StringBuilder();
    sb.append("| family | shapes | groups: greedy, predicted, best, exact "
        + "| ops saved by the best | shapes where the best has fewer groups "
        + "| largest saving on one shape |\n");
    sb.append("|---|---:|---:|---:|---:|---:|\n");
    summaries.values().forEach(s -> sb.append(String.format(
        "| %s | %d | %d, %d, %d, %d | %.2f%% | %d | %.2f%% at %s |%n", s.family(), s.shapes(),
        s.greedyGroups(), s.predictedGroups(), s.bestGroups(), s.exactGroups(),
        s.savedPercent(), s.bestFewerGroups(), s.largestSavingPercent(), s.largestSavingAt())));
    return sb.toString();
  }

  // -------------------------------------------------------------------------------------------
  // Shapes with systematic sharing between outputs that are not adjacent
  // -------------------------------------------------------------------------------------------

  /**
   * The shapes the cost model's corpus lacks: outputs of several families over one date, and
   * fields of several dates interleaved so that the outputs sharing a prefix are not adjacent.
   * A greedy close can strand a later sharer there, where a homogeneous ladder or a draw of
   * independent random trees cannot show it.
   */
  static List<Shape> interleaved() {
    List<Shape> shapes = new ArrayList<>();
    for (int n : new int[] {40, 100, 200}) {
      shapes.add(new Shape("mixed families, one date", n, mixed(n), 1, n));
    }
    for (int n : new int[] {24, 60, 120}) {
      List<VarkaVectorIR> roots = new ArrayList<>();
      for (int k = 0; k < n; k++) {
        ColumnRef c = new ColumnRef(k % 2);
        roots.add(switch ((k / 2) % 6) {
          case 0 -> new Year(c);
          case 1 -> new Month(c);
          case 2 -> new Quarter(c);
          case 3 -> new DayOfMonth(c);
          case 4 -> new AddMonths(c, new LiteralSlot(k));
          default -> new LastDay(c);
        });
      }
      shapes.add(new Shape("two dates, fields interleaved", n, roots, 2, n));
    }
    List<VarkaVectorIR> byDate = new ArrayList<>();
    List<VarkaVectorIR> byField = new ArrayList<>();
    for (int c = 0; c < 12; c++) {
      byDate.addAll(fields(new ColumnRef(c)));
    }
    for (int f = 0; f < 4; f++) {
      for (int c = 0; c < 12; c++) {
        byField.add(fields(new ColumnRef(c)).get(f));
      }
    }
    shapes.add(new Shape("twelve dates, by date", 48, byDate, 12, 0));
    shapes.add(new Shape("twelve dates, by field", 48, byField, 12, 0));
    return shapes;
  }

  /**
   * The mixed family at {@code n} entries: a size-ladder entry, a {@code make_date}, a cheap tail
   * {@code year(d) + k} and a {@code date_add(d, k)} over one date, in rotation, each over the
   * literal slot of its own index. The greedy walk leaves every {@code date_add} in a loop method
   * of its own here, which the best partition does not ({@code PLAN_TASK_200.md} 2).
   */
  static List<VarkaVectorIR> mixed(int n) {
    ColumnRef d = new ColumnRef(0);
    List<VarkaVectorIR> roots = new ArrayList<>();
    for (int k = 0; k < n; k++) {
      roots.add(switch (k % 4) {
        case 0 -> VarkaEmitCostCorpus.ladderEntry(k);
        case 1 -> VarkaEmitCostCorpus.makeDateEntry(k);
        case 2 -> VarkaEmitCostCorpus.tailEntry(k);
        default -> new AddDays(d, new LiteralSlot(k));
      });
    }
    return roots;
  }

  private static List<VarkaVectorIR> fields(ColumnRef c) {
    return List.of(new Year(c), new Month(c), new DayOfMonth(c), new Quarter(c));
  }
}
