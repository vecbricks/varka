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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LastDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MakeDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Month;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * The shapes the emit cost model ({@code VarkaEmitCost}) is priced and audited on, and the one
 * way they are measured: each shape emitted with its first grouping standing, and each group's
 * four methods read off the class beside the features the grouping counted for that group.
 *
 * <p>Shared by {@link VarkaEmitCostFit}, which fits the prices on the even-numbered shapes, and
 * {@link VarkaEmitCostAudit}, which scores them on the odd-numbered ones and the ladders; one
 * draw for both is what keeps the held-out shapes held out.
 */
final class VarkaEmitCostCorpus {

  private VarkaEmitCostCorpus() {}

  /** The width every price is read at: the 512-bit int species, the published machine's. */
  static final int LANES = 16;

  /**
   * {@code base} with nothing left to regroup a shape after it is built: the byte budget at the
   * class-file cap, so a group's methods are its own whatever their size, and the call-site
   * budget off. Every other option is {@code base}'s, so the grouping is the one it forms first.
   */
  static VarkaEmitOptions measuring(VarkaEmitOptions base) {
    return base.withLanesOverride(LANES).withMethodByteBudget(VarkaEmitBudget.METHOD_CODE_CAP)
        .withCallSiteBudget(0);
  }

  /**
   * The measuring options at the shipped ones, but for the greedy walk: the price tables were
   * fitted on its groups (`PLAN_TASK_199.md`), and a model of a method's bytes from its features
   * does not change with which groups the grouping picks, so the exact grouping's default
   * (`PLAN_TASK_200.md` 8.2) leaves the fit's sample as it was.
   */
  static VarkaEmitOptions measuring() {
    return measuring(VarkaEmitOptions.DEFAULTS.withExactGrouping(false));
  }

  /** The measuring options with every output in one group, for the register's probes. */
  static VarkaEmitOptions oneGroup() {
    return measuring().withGroupBudget(1 << 20).withFusedCeiling(1 << 20);
  }

  /**
   * One group of one emitted shape: its output indices, the feature counts the grouping made
   * for it, and the eight measured quantities in {@code VarkaEmitCost.METHODS} order, bytes then
   * call sites, NaN for a method the class does not have (a kernel that nulls a valid input has
   * no dense side).
   */
  record Group(List<Integer> outputs, Map<String, Integer> counts, double[] measured) {}

  private static final AtomicLong COUNTER = new AtomicLong();

  /**
   * Emits {@code roots} under {@code options} and returns each group of the grouping the
   * emitter formed, or empty when the emitter declines the shape or built it more than once - a
   * method over the class-file cap is still split under the measuring options, and a regrouped
   * class's methods no longer match the first grouping's tallies. Any other refusal is an
   * emitter bug, and is thrown.
   */
  static Optional<List<Group>> measure(List<VarkaVectorIR> roots, int numInputs,
      int numLiterals, VarkaEmitOptions options) {
    String name = "org.apache.spark.sql.varka.execution.VarkaEmitCost"
        + COUNTER.incrementAndGet();
    int[] builds = new int[1];
    byte[] bytes;
    try {
      bytes = VarkaLoopEmitter.emitCountingBuilds(name, roots, numInputs, numLiterals, options,
          builds);
    } catch (VarkaEmitDeclined e) {
      return Optional.empty();
    }
    if (builds[0] != 1) {
      return Optional.empty();
    }
    List<List<Integer>> groups = VarkaLoopEmitter.groupsForTest(roots, options);
    List<VarkaEmitCost.Tally> tallies =
        VarkaLoopEmitter.talliesForTest(roots, options, VarkaEmitCostTable.PRICES);
    VarkaEmittedClass emitted = VarkaEmittedClass.measure(bytes);
    List<Group> result = new ArrayList<>();
    for (int g = 0; g < groups.size(); g++) {
      double[] measured = new double[VarkaEmitCost.QUANTITIES];
      for (int m = 0; m < 4; m++) {
        String method = VarkaEmitCost.METHODS.get(m) + g;
        measured[m] = orNaN(emitted.codeLength().get(method));
        measured[m + 4] = orNaN(emitted.vectorCallSites().get(method));
      }
      result.add(new Group(groups.get(g), tallies.get(g).counts(), measured));
    }
    return Optional.of(result);
  }

  private static double orNaN(Integer v) {
    return v == null ? Double.NaN : v;
  }

  /** A shape of the corpus, named so a row of the audit can say where it came from. */
  record Shape(String family, int index, List<VarkaVectorIR> roots, int numInputs,
      int numLiterals) {}

  /** How many of each fuzz sequence the corpus takes: the first shapes of the oracle's. */
  static final int FUZZ_SHAPES = 2000;
  /** How many wide shapes of each lane the corpus takes. */
  static final int WIDE_SHAPES = 200;
  /** The wide sequences' seeds, apart from the oracle's so they move nothing committed. */
  static final long WIDE_SEED = 20260930L;
  static final long WIDE_LONG_SEED = 20260931L;

  /**
   * The size ladder's entry, {@code greatest(greatest(add_months(d, k), date_add(d, k)),
   * last_day(d))} over one date: four calendar nodes sharing a prefix, the entry the emission
   * benchmark widens.
   */
  static VarkaVectorIR ladderEntry(int k) {
    ColumnRef col = new ColumnRef(0);
    return new Greatest(new Greatest(new AddMonths(col, new LiteralSlot(k)),
        new AddDays(col, new LiteralSlot(k))), new LastDay(col));
  }

  /** {@code make_date(year(d), month(d), k)}, the ladder that first crossed HugeMethodLimit. */
  static VarkaVectorIR makeDateEntry(int k) {
    ColumnRef col = new ColumnRef(0);
    return new MakeDate(new Year(col), new Month(col), new LiteralSlot(k), true);
  }

  /** {@code year(d) + k}, the cheap tail the call-site budget was read on. */
  static VarkaVectorIR tailEntry(int k) {
    return new IntArith(IntOp.ADD, Overflow.WRAP, new Year(new ColumnRef(0)), new LiteralSlot(k));
  }

  private static List<VarkaVectorIR> entries(int n, java.util.function.IntFunction<VarkaVectorIR>
      entry) {
    return IntStream.range(0, n).mapToObj(entry).toList();
  }

  /** The ladders at the heights the admission check measured, each one shape. */
  static List<Shape> ladders() {
    List<Shape> shapes = new ArrayList<>();
    for (int n : new int[] {16, 54, 100, 200, 400}) {
      shapes.add(new Shape("size ladder", n, entries(n, VarkaEmitCostCorpus::ladderEntry), 1, n));
    }
    for (int n : new int[] {12, 60}) {
      shapes.add(new Shape("make_date ladder", n,
          entries(n, VarkaEmitCostCorpus::makeDateEntry), 1, n));
    }
    for (int n : new int[] {22, 64}) {
      shapes.add(new Shape("cheap tails", n, entries(n, VarkaEmitCostCorpus::tailEntry), 1, n));
    }
    return shapes;
  }

  private static List<VarkaVectorIR> roots(scala.collection.Seq<VarkaVectorIR> roots) {
    return List.copyOf(CollectionConverters.asJava(roots));
  }

  /** The fuzz sequences' first shapes, both lanes. */
  static List<Shape> fuzz() {
    return fuzz(FUZZ_SHAPES);
  }

  /** The first {@code n} shapes of each fuzz sequence. */
  static List<Shape> fuzz(int n) {
    List<Shape> shapes = new ArrayList<>();
    for (int k = 0; k < n; k++) {
      VarkaIrGrammar.Drawn d = VarkaIrGrammar.drawShape(
          VarkaIrGrammar.shapeRandom(VarkaIrGrammar.fuzzSeed(), k));
      shapes.add(new Shape("fuzz int", k, roots(d.roots()), d.numInputs(), d.numLiterals()));
    }
    for (int k = 0; k < n; k++) {
      VarkaIrGrammar.DrawnLong d = VarkaIrGrammar.drawLongShape(
          VarkaIrGrammar.shapeRandom(VarkaIrGrammar.longFuzzSeed(), k));
      shapes.add(new Shape("fuzz long", k, roots(d.roots()), d.numInputs(), d.numLiterals()));
    }
    return shapes;
  }

  /** The wide draws, both lanes. */
  static List<Shape> wide() {
    List<Shape> shapes = new ArrayList<>();
    for (int k = 0; k < WIDE_SHAPES; k++) {
      VarkaIrGrammar.Drawn d = VarkaIrGrammar.drawWideShape(
          VarkaIrGrammar.shapeRandom(WIDE_SEED, k));
      shapes.add(new Shape("wide int", k, roots(d.roots()), d.numInputs(), d.numLiterals()));
    }
    for (int k = 0; k < WIDE_SHAPES; k++) {
      VarkaIrGrammar.DrawnLong d = VarkaIrGrammar.drawWideLongShape(
          VarkaIrGrammar.shapeRandom(WIDE_LONG_SEED, k));
      shapes.add(new Shape("wide long", k, roots(d.roots()), d.numInputs(), d.numLiterals()));
    }
    return shapes;
  }

  /**
   * The groupings each shape is measured under: the default grouping, and one whose groups are
   * as wide as the fused ceiling allows, which is what brings the fuzz and wide shapes' methods
   * up to the byte budget.
   */
  static List<Map.Entry<String, VarkaEmitOptions>> arms() {
    return List.of(Map.entry("default groups", measuring()),
        Map.entry("groups to 400", measuring().withGroupBudget(VarkaEmitBudget.FUSED_CEILING)));
  }
}
