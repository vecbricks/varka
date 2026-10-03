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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitCostCorpus.Group;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitCostCorpus.Shape;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfMonth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Least;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Month;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Or;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncLevel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * How the emit cost model's two price tables are derived from emitted classes, and the Java
 * sources that carry them: {@code VarkaEmitCostTable}, the fitted prices the grouping predicts
 * with, and {@link VarkaEmitCostRegister}, the measured prices the audit compares them against.
 * {@code VarkaEmitCostSuite} runs the derivations and fails while either committed source
 * differs, so a lowering change that moves a price fails there and names the feature;
 * {@code VARKA_COST_REGEN=true} rewrites both.
 *
 * <p><b>The register</b> prices one feature per probe: a shape emitted as one group, then the
 * same shape with one root added whose new features all have prices but one, and the difference
 * in each measured quantity, less the priced features' share, is that one's price. The probes
 * are ordered so each finds its partners priced: the leaves, the store and the fixed cost first,
 * from a negation over a column; then every kind the IR has, each added over its own children -
 * at several of its occurrences in the fuzz sequences, with the median reading as its price, or
 * for a kind the grammar never draws on a node built for the purpose; then the prefix.
 *
 * <p><b>The regression</b> fits every feature's price by least squares over the even-numbered
 * fuzz and wide shapes and the register's probes, the probes included so that a kind the corpus
 * rarely or never draws still has a price.
 */
final class VarkaEmitCostFit {

  private VarkaEmitCostFit() {}

  private static final int Q = VarkaEmitCost.QUANTITIES;

  /** How many distinct nodes of each kind the register reads, at most. */
  static final int READINGS = 9;

  /** The register's prices and the probe groups they were read from. */
  record Register(Map<String, double[]> prices, List<Group> probes) {}

  // -------------------------------------------------------------------------------------------
  // Every feature the model must price
  // -------------------------------------------------------------------------------------------

  /**
   * Every feature {@code VarkaEmitCost.kindOf} and the tally can produce, enumerated from the
   * IR's own enums rather than from what a grammar happens to draw: both tables must price all
   * of them, or a group holding the missing one is silently unpredictable. A lowering variant
   * the emitter refuses - an int-lane checked multiply has no overflow test - is left out, since
   * no group can hold it.
   */
  static Set<String> expectedFeatures() {
    Set<String> f = new TreeSet<>();
    for (String feature : enumeratedFeatures()) {
      VarkaVectorIR node = synthetic(feature);
      if (node == null || emittable(node)) {
        f.add(feature);
      }
    }
    return f;
  }

  /** Whether the emitter accepts {@code node} as the one root of a kernel. */
  private static boolean emittable(VarkaVectorIR node) {
    try {
      VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaEmitCostProbe",
          List.of(asRoot(node)), 2, 0, null, null, VarkaEmitCostCorpus.oneGroup());
      return true;
    } catch (VarkaEmitDeclined e) {
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static Set<String> enumeratedFeatures() {
    Set<String> f = new TreeSet<>();
    for (LaneType lane : LaneType.values()) {
      f.add(VarkaEmitCost.FIXED + lane);
      f.add(VarkaEmitCost.OUTPUT + lane);
      f.add(VarkaEmitCost.SELECTION + lane);
      for (String k : List.of("ColumnRef", "LiteralSlot", "GuardedRange", "Greatest", "Least",
          "IfElse", "IsNotNull", "And", "Or", "Not")) {
        f.add(k + "/" + lane);
      }
      for (IntOp op : IntOp.values()) {
        for (Overflow mode : Overflow.values()) {
          f.add("IntArith/" + op + "/" + mode + "/" + lane);
        }
      }
      for (Overflow mode : Overflow.values()) {
        f.add("IntNeg/" + mode + "/" + lane);
      }
      for (CompareOp op : CompareOp.values()) {
        f.add("Compare/" + op + "/" + lane);
      }
      f.add("ConstDivide/" + lane + "/pos");
      f.add("ConstDivide/" + lane + "/neg");
    }
    for (TruncLevel level : TruncLevel.values()) {
      f.add("TruncDate/" + level);
    }
    f.addAll(List.of("MakeDate/ansi", "MakeDate/null", "NarrowLane", "AddDays", "SubDays",
        "DateDiff", "DayOfWeek", "WeekDay", "DayOfWeekIso", "NextDay", "ThursdayOf", "AddMonths",
        "Year", "Month", "DayOfMonth", "Quarter", "DayOfYear", "LastDay", "TruncDateDynamic",
        "WeekOfYear", "GuardedDay", "BoundedDivide", "InRanges", VarkaEmitCost.PREFIX,
        VarkaEmitCost.PREFIX_MONTH, VarkaEmitCost.PREFIX_LOAD));
    return f;
  }

  // -------------------------------------------------------------------------------------------
  // The register
  // -------------------------------------------------------------------------------------------

  /**
   * The register, and the probe groups it was read from (the regression's extra rows). Fails
   * naming the features it could not price, which is how a new node kind announces itself.
   */
  static Register register() {
    Map<String, double[]> prices = new LinkedHashMap<>();
    List<Group> rows = new ArrayList<>();
    Probe probe = new Probe(prices, rows);

    for (LaneType lane : LaneType.values()) {
      ColumnRef c0 = new ColumnRef(0, lane);
      ColumnRef c1 = new ColumnRef(1, lane);
      IntNeg n0 = new IntNeg(Overflow.WRAP, c0);
      probe.must("out " + lane, List.of(n0), List.of(n0), 1, 0);
      probe.must("neg " + lane, List.of(n0), List.of(new IntNeg(Overflow.WRAP, n0)), 1, 0);
      probe.must("column " + lane, List.of(n0), List.of(new IntNeg(Overflow.WRAP, c1)), 2, 0);
      probe.must("literal " + lane, List.of(n0),
          List.of(new IntNeg(Overflow.WRAP, new LiteralSlot(0, lane))), 1, 1);
      probe.must("fixed " + lane, List.of(), List.of(n0), 1, 0);
      Compare cmp = new Compare(CompareOp.LT, c0, c1);
      probe.must("if " + lane, List.of(new IfElse(cmp, c0, c1)),
          List.of(new IfElse(cmp, c1, c0)), 2, 0);
    }
    // NarrowLane is admitted at an output root only, over a long child, so the grammar never
    // builds it; it is priced here, on its own root form.
    IntNeg longNeg = new IntNeg(Overflow.WRAP, new ColumnRef(0, LaneType.LONG));
    probe.must("narrow", List.of(longNeg), List.of(new NarrowLane(longNeg)), 1, 0);

    // Every other kind, from its occurrences in the fuzz sequences, each over its own children:
    // a node's code depends a little on what surrounds it, so the median of several readings is
    // the price, rather than whichever occurrence came first.
    Map<String, List<Occurrence>> pending = new LinkedHashMap<>(occurrences());
    pending.keySet().removeAll(prices.keySet());
    boolean progress = true;
    while (!pending.isEmpty() && progress) {
      progress = false;
      for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
        var e = it.next();
        List<Reading> readings = new ArrayList<>();
        for (Occurrence o : e.getValue()) {
          Probe.Parts p = kindProbe(o.node());
          probe.read(p.before(), p.added(), o.inputs(), o.literals())
              .filter(r -> r.feature().equals(e.getKey()))
              .ifPresent(readings::add);
        }
        if (!readings.isEmpty()) {
          probe.commit(e.getKey(), readings);
          it.remove();
          progress = true;
        }
      }
    }
    if (!pending.isEmpty()) {
      throw new IllegalStateException("no probe prices " + pending.keySet());
    }
    // A lowering variant the grammar never draws - a mode or operator at a lane its arms do not
    // reach - is priced on a node built for it over columns.
    for (String feature : expectedFeatures()) {
      if (!prices.containsKey(feature) && synthetic(feature) != null) {
        Probe.Parts p = kindProbe(synthetic(feature));
        probe.must(feature, p.before(), p.added(), 2, 0);
      }
    }

    // The prefix, now that every tail has a price, and the selection's store last, now that
    // every condition has one.
    ColumnRef d = new ColumnRef(0);
    IntNeg n0 = new IntNeg(Overflow.WRAP, d);
    probe.must("prefix", List.of(n0), List.of(new Year(d)), 1, 0);
    probe.must("prefix/month", List.of(n0), List.of(new Month(d)), 1, 0);
    VarkaEmitOptions apart = VarkaEmitCostCorpus.oneGroup().withGroupBudget(1).withFusedCeiling(1);
    Group loads = VarkaEmitCostCorpus.measure(List.of(new Month(d), new Year(d)), 1, 0, apart)
        .orElseThrow().get(1);
    probe.commit("prefix/load", List.of(probe.solve(Optional.empty(), loads).orElseThrow(
        () -> new IllegalStateException("the prefix/load probe read no single new feature"))));
    for (LaneType lane : LaneType.values()) {
      probe.must("selection " + lane, List.of(),
          List.of(new Compare(CompareOp.LT, new ColumnRef(0, lane), new ColumnRef(1, lane))),
          2, 0);
    }
    Set<String> missing = new TreeSet<>(expectedFeatures());
    missing.removeAll(prices.keySet());
    if (!missing.isEmpty()) {
      throw new IllegalStateException("the register has no price for " + missing);
    }
    return new Register(prices, rows);
  }

  /**
   * A node of the kind {@code feature} names, over columns, for the lowering variants the
   * grammar does not reach; null for a feature that is not such a variant.
   */
  private static VarkaVectorIR synthetic(String feature) {
    String[] p = feature.split("/");
    if (!Set.of("IntArith", "IntNeg", "Compare", "Greatest", "Least", "IsNotNull", "And", "Or",
        "Not", "ConstDivide").contains(p[0])) {
      return null;
    }
    LaneType lane = LaneType.valueOf(p[0].equals("ConstDivide") ? p[1] : p[p.length - 1]);
    ColumnRef c0 = new ColumnRef(0, lane);
    ColumnRef c1 = new ColumnRef(1, lane);
    Compare cmp = new Compare(CompareOp.LT, c0, c1);
    return switch (p[0]) {
      case "IntArith" -> new IntArith(IntOp.valueOf(p[1]), Overflow.valueOf(p[2]), c0, c1);
      case "IntNeg" -> new IntNeg(Overflow.valueOf(p[1]), c0);
      case "Compare" -> new Compare(CompareOp.valueOf(p[1]), c0, c1);
      case "Greatest" -> new Greatest(c0, c1);
      case "Least" -> new Least(c0, c1);
      case "IsNotNull" -> new IsNotNull(c0);
      case "And" -> new And(cmp, new Compare(CompareOp.GT, c0, c1));
      case "Or" -> new Or(cmp, new Compare(CompareOp.GT, c0, c1));
      case "Not" -> new Not(cmp);
      case "ConstDivide" -> new ConstDivide(c0, p[2].equals("neg") ? -7 : 7,
          lane == LaneType.LONG ? ConstDivide.EXACT_DIVIDEND_BOUND : Integer.MAX_VALUE);
      default -> null;
    };
  }

  /** One node a kind is read at, with its shape's inputs and literals. */
  record Occurrence(VarkaVectorIR node, int inputs, int literals) {}

  /** One probe's reading: the one feature it added without a price, and what it measured. */
  record Reading(String feature, double[] price, List<Group> groups) {}

  /** The register's probing, over the prices found so far. */
  private static final class Probe {
    private final Map<String, double[]> prices;
    private final List<Group> rows;

    Probe(Map<String, double[]> prices, List<Group> rows) {
      this.prices = prices;
      this.rows = rows;
    }

    /** A probe's two sides: the shape, and the roots added to it. */
    record Parts(List<VarkaVectorIR> before, List<VarkaVectorIR> added) {}

    private Optional<Group> one(List<VarkaVectorIR> roots, int inputs, int lits) {
      return VarkaEmitCostCorpus.measure(roots, inputs, lits, VarkaEmitCostCorpus.oneGroup())
          .filter(g -> g.size() == 1).map(g -> g.get(0));
    }

    /**
     * The one feature {@code after} adds over {@code before} that has no price yet, and what it
     * measured per quantity - NaN for a method one side does not have - or empty when the probe
     * adds no such feature or more than one.
     */
    Optional<Reading> solve(Optional<Group> before, Group after) {
      Map<String, Integer> diff = new HashMap<>(after.counts());
      before.ifPresent(b -> b.counts().forEach((k, v) -> diff.merge(k, -v, Integer::sum)));
      diff.values().removeIf(v -> v == 0);
      List<String> unknown = diff.keySet().stream().filter(k -> !prices.containsKey(k)).toList();
      if (unknown.size() != 1) {
        return Optional.empty();
      }
      String u = unknown.get(0);
      double[] price = new double[Q];
      for (int q = 0; q < Q; q++) {
        double v = after.measured()[q] - (before.isPresent() ? before.get().measured()[q] : 0.0);
        for (Map.Entry<String, Integer> e : diff.entrySet()) {
          if (!e.getKey().equals(u)) {
            v -= e.getValue() * prices.get(e.getKey())[q];
          }
        }
        price[q] = v / diff.get(u);
      }
      List<Group> groups = new ArrayList<>();
      groups.add(after);
      before.ifPresent(groups::add);
      return Optional.of(new Reading(u, price, groups));
    }

    /** One probe's reading, or empty when either side does not emit as one group. */
    Optional<Reading> read(List<VarkaVectorIR> before, List<VarkaVectorIR> added, int inputs,
        int lits) {
      Optional<Group> b = before.isEmpty() ? Optional.empty() : one(before, inputs, lits);
      if (!before.isEmpty() && b.isEmpty()) {
        return Optional.empty();
      }
      List<VarkaVectorIR> all = new ArrayList<>(before);
      all.addAll(added);
      return one(all, inputs, lits).flatMap(a -> solve(b, a));
    }

    /** Records {@code readings} of one feature as its price: per quantity, their median. */
    void commit(String feature, List<Reading> readings) {
      double[] price = new double[Q];
      for (int q = 0; q < Q; q++) {
        final int at = q;
        double[] seen = readings.stream().mapToDouble(r -> r.price()[at])
            .filter(v -> !Double.isNaN(v)).sorted().toArray();
        price[q] = seen.length == 0 ? 0.0 : seen[seen.length / 2];
      }
      prices.put(feature, price);
      readings.forEach(r -> rows.addAll(r.groups()));
    }

    void must(String label, List<VarkaVectorIR> before, List<VarkaVectorIR> added, int inputs,
        int lits) {
      Optional<Reading> r = read(before, added, inputs, lits);
      if (r.isEmpty()) {
        throw new IllegalStateException(
            "register probe '" + label + "' found no single unpriced feature to read");
      }
      commit(r.get().feature(), List.of(r.get()));
    }
  }

  /**
   * The shape a kind is priced on: its node's children as roots (a condition wrapped in a
   * conditional, since a condition is not a value root), with a calendar node's date given the
   * month-reading prefix by {@code month(d)} and {@code dayofmonth(d)}, and then the node itself,
   * wrapped the same way. Every feature the node brings is then on the first side except its own.
   */
  private static Probe.Parts kindProbe(VarkaVectorIR node) {
    Set<VarkaVectorIR> before = new LinkedHashSet<>();
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      before.add(asRoot(child));
    }
    if (VarkaEmitBudget.isChrono(node)) {
      VarkaVectorIR date = VarkaChronoLowering.chronoChild(node);
      for (VarkaVectorIR partner : List.of(new Month(date), new DayOfMonth(date))) {
        if (!partner.equals(node)) {
          before.add(partner);
        }
      }
    }
    return new Probe.Parts(List.copyOf(before), List.of(asRoot(node)));
  }

  private static VarkaVectorIR asRoot(VarkaVectorIR n) {
    if (n instanceof Cond c) {
      ColumnRef x = new ColumnRef(0, c.laneType());
      return new IfElse(c, x, x);
    }
    return n;
  }

  /**
   * The first {@link #READINGS} distinct nodes of each kind in the fuzz sequences, with their
   * shape's inputs and literals: what the register prices each kind on.
   */
  static Map<String, List<Occurrence>> occurrences() {
    Map<String, Map<VarkaVectorIR, Occurrence>> found = new LinkedHashMap<>();
    for (Shape shape : VarkaEmitCostCorpus.fuzz(OCCURRENCE_SHAPES)) {
      for (VarkaVectorIR root : shape.roots()) {
        visit(root, shape, found);
      }
    }
    Map<String, List<Occurrence>> result = new LinkedHashMap<>();
    found.forEach((k, v) -> result.put(k, List.copyOf(v.values())));
    return result;
  }

  /** How far into each fuzz sequence {@link #occurrences} looks for a kind's readings. */
  private static final int OCCURRENCE_SHAPES = 20000;

  private static void visit(VarkaVectorIR n, Shape shape,
      Map<String, Map<VarkaVectorIR, Occurrence>> found) {
    Map<VarkaVectorIR, Occurrence> seen =
        found.computeIfAbsent(VarkaEmitCost.kindOf(n), k -> new LinkedHashMap<>());
    if (seen.size() < READINGS) {
      seen.putIfAbsent(n, new Occurrence(n, shape.numInputs(), shape.numLiterals()));
    }
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(n)) {
      visit(child, shape, found);
    }
  }

  // -------------------------------------------------------------------------------------------
  // The regression
  // -------------------------------------------------------------------------------------------

  /**
   * The groups the regression is fitted on: the even-numbered fuzz and wide shapes, both arms.
   * The ladders are left out, so the audit's shapes at the budget are all held out.
   */
  static List<Group> trainingGroups() {
    List<Group> rows = new ArrayList<>();
    List<Shape> shapes = new ArrayList<>(VarkaEmitCostCorpus.fuzz());
    shapes.addAll(VarkaEmitCostCorpus.wide());
    for (Shape shape : shapes) {
      if (shape.index() % 2 != 0) {
        continue;
      }
      for (Map.Entry<String, VarkaEmitOptions> arm : VarkaEmitCostCorpus.arms()) {
        VarkaEmitCostCorpus.measure(shape.roots(), shape.numInputs(), shape.numLiterals(),
            arm.getValue()).ifPresent(rows::addAll);
      }
    }
    return rows;
  }

  /**
   * Least-squares prices for every feature in {@code rows}, one fit per quantity over the rows
   * that measured it, with a small ridge so a feature that always appears beside another still
   * gets a definite price.
   */
  static Map<String, double[]> regression(List<Group> rows) {
    List<String> features = new ArrayList<>(new TreeSet<>(
        rows.stream().flatMap(r -> r.counts().keySet().stream()).toList()));
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < features.size(); i++) {
      index.put(features.get(i), i);
    }
    int f = features.size();
    double[][] coefficients = new double[f][Q];
    for (int q = 0; q < Q; q++) {
      double[][] a = new double[f][f];
      double[] b = new double[f];
      for (Group r : rows) {
        double y = r.measured()[q];
        if (Double.isNaN(y)) {
          continue;
        }
        int[] is = r.counts().keySet().stream().mapToInt(index::get).toArray();
        double[] xs = r.counts().values().stream().mapToDouble(Integer::doubleValue).toArray();
        for (int i = 0; i < is.length; i++) {
          b[is[i]] += xs[i] * y;
          for (int j = 0; j < is.length; j++) {
            a[is[i]][is[j]] += xs[i] * xs[j];
          }
        }
      }
      for (int i = 0; i < f; i++) {
        a[i][i] += 1e-3;
      }
      double[] solved = solveLinear(a, b);
      for (int i = 0; i < f; i++) {
        coefficients[i][q] = solved[i];
      }
    }
    Map<String, double[]> result = new TreeMap<>();
    for (int i = 0; i < f; i++) {
      result.put(features.get(i), coefficients[i]);
    }
    return result;
  }

  /** Gaussian elimination with partial pivoting; {@code a} and {@code b} are overwritten. */
  private static double[] solveLinear(double[][] a, double[] b) {
    int n = b.length;
    for (int c = 0; c < n; c++) {
      int p = c;
      for (int r = c + 1; r < n; r++) {
        if (Math.abs(a[r][c]) > Math.abs(a[p][c])) {
          p = r;
        }
      }
      double[] tr = a[c];
      a[c] = a[p];
      a[p] = tr;
      double tb = b[c];
      b[c] = b[p];
      b[p] = tb;
      for (int r = c + 1; r < n; r++) {
        double m = a[r][c] / a[c][c];
        if (m != 0) {
          for (int k = c; k < n; k++) {
            a[r][k] -= m * a[c][k];
          }
          b[r] -= m * b[c];
        }
      }
    }
    double[] x = new double[n];
    for (int r = n - 1; r >= 0; r--) {
      double s = b[r];
      for (int k = r + 1; k < n; k++) {
        s -= a[r][k] * x[k];
      }
      x[r] = s / a[r][r];
    }
    return x;
  }

  // -------------------------------------------------------------------------------------------
  // The generated sources
  // -------------------------------------------------------------------------------------------

  /** A price as the source spells it: an integer bare, anything else to three places. */
  private static String number(double v) {
    double r = Math.round(v * 1000) / 1000.0;
    if (r == Math.rint(r)) {
      return Long.toString((long) Math.rint(r));
    }
    return BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
  }

  private static String entries(Map<String, double[]> prices) {
    StringBuilder sb = new StringBuilder();
    new TreeMap<>(prices).forEach((k, v) -> {
      if (!sb.isEmpty()) {
        sb.append(",\n");
      }
      sb.append("      Map.entry(\"").append(k).append("\",\n          new double[] {")
          .append(String.join(", ", Arrays.stream(v).mapToObj(VarkaEmitCostFit::number)
              .toList()))
          .append("})");
    });
    return sb.toString();
  }

  private static final String LICENSE = """
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

      import java.util.Map;

      """;

  /**
   * The band of a quantity where its budget binds: a method of this many bytes and over is near
   * enough the byte budget for an under-prediction to cost a rebuild, and the same for call sites
   * against the call-site budget. The audit's "2000 bytes and over" band, and two thirds of
   * {@code CALL_SITE_BUDGET}.
   */
  static final int BYTES_BAND = 2000;
  static final int SITES_BAND = VarkaEmitBudget.CALL_SITE_BUDGET * 2 / 3;

  /**
   * The margins the planned grouping closes a group under its budgets by
   * ({@code VarkaEmitOptions.planSize}, {@code PLAN_TASK_236.md} 3.3): the largest
   * under-prediction {@code prices} make on {@code rows}, the methods they were fitted on, in the
   * band where each budget binds, as a share of the measurement; {@code [bytes, sites]}, each at
   * least zero and rounded up to a thousandth. Derived with the prices, so a refit moves them.
   */
  static double[] margins(Map<String, double[]> prices, List<Group> rows) {
    double bytes = 0;
    double sites = 0;
    for (Group r : rows) {
      double[] predicted = VarkaEmitCost.predict(prices, r.counts());
      if (predicted == null) {
        continue;
      }
      for (int q = 0; q < Q; q++) {
        double measured = r.measured()[q];
        if (Double.isNaN(measured)) {
          continue;
        }
        double under = (measured - predicted[q]) / measured;
        if (q < 4 && measured >= BYTES_BAND) {
          bytes = Math.max(bytes, under);
        } else if (q >= 4 && measured >= SITES_BAND) {
          sites = Math.max(sites, under);
        }
      }
    }
    return new double[] {Math.ceil(bytes * 1000) / 1000, Math.ceil(sites * 1000) / 1000};
  }

  /** The Java source of {@code VarkaEmitCostTable}, the fitted prices and their margins. */
  static String tableSource(Map<String, double[]> regression, double[] margins) {
    return LICENSE + """
        /**
         * The prices {@link VarkaEmitCost} predicts with: for each feature, what it adds to each
         * of a group's four methods in bytes and then in Vector API call sites, in
         * {@link VarkaEmitCost#METHODS} order, fitted by least squares over emitted groups; and
         * the margins the planned grouping keeps under the budgets, the largest under-prediction
         * the fit makes on its own groups in the band where each budget binds
         * ({@code PLAN_TASK_236.md} 3.3).
         *
         * <p>Generated, not written: {@code VarkaEmitCostSuite} derives it from emitted classes at
         * the default options and sixteen int lanes, fails while this file differs from what it
         * derives, and rewrites it under {@code VARKA_COST_REGEN=true build/sbt
         * 'catalyst/testOnly *VarkaEmitCostSuite'}. How it is derived is
         * {@code VarkaEmitCostFit}'s doc.
         */
        final class VarkaEmitCostTable {

          private VarkaEmitCostTable() {}

          /**
           * The share of the byte budget a planned group is closed under, so that a method the
           * prices under-predict still measures within it.
           */
        """ + "  static final double BYTES_MARGIN = " + number(margins[0]) + ";\n\n"
        + "  /** The same for the call-site budget. */\n"
        + "  static final double SITES_MARGIN = " + number(margins[1]) + ";\n\n"
        + """
          /** Each feature's price, by feature. */
          static final Map<String, double[]> PRICES = Map.ofEntries(
        """ + entries(regression) + ");\n}\n";
  }

  /** The Java source of {@link VarkaEmitCostRegister}, the measured prices. */
  static String registerSource(Map<String, double[]> register) {
    return LICENSE + """
        /**
         * The emit cost model's second pricing, kept for the audit: each feature measured on
         * emitted classes beside its own children rather than fitted, in the order and units of
         * {@code VarkaEmitCostTable}. It lost to the fitted prices ({@code PLAN_TASK_199.md} 9.2),
         * and {@code VarkaEmitCostAudit} keeps scoring it so the comparison stays checkable.
         *
         * <p>Generated with {@code VarkaEmitCostTable}, by the same suite and the same switch.
         */
        final class VarkaEmitCostRegister {

          private VarkaEmitCostRegister() {}

          /** Each feature's measured price, by feature. */
          static final Map<String, double[]> PRICES = Map.ofEntries(
        """ + entries(register) + ");\n}\n";
  }

  /** Both sources derived afresh: the table's, then the register's. */
  static List<String> derive() {
    Register reg = register();
    List<Group> rows = new ArrayList<>(trainingGroups());
    rows.addAll(reg.probes());
    Map<String, double[]> fitted = regression(rows);
    return List.of(tableSource(fitted, margins(fitted, rows)), registerSource(reg.prices()));
  }
}
