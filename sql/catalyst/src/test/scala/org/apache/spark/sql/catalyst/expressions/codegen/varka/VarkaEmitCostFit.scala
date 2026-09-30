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

import scala.collection.mutable

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitCostCorpus._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * How `VarkaEmitCostTable`'s two tables are derived from emitted classes, and the Java source
 * that carries them. `VarkaEmitCostSuite` runs both derivations and fails when the committed
 * source differs from what they produce, so a lowering change that moves a price fails there
 * and names the feature, and `VARKA_COST_REGEN=true` rewrites the source.
 *
 * The register (model B) prices one feature per probe: a shape emitted as one group, then the
 * same shape with one root added whose new features are all priced but one, and the difference
 * in each measured quantity, less the priced features' share, is that one's price. The probes
 * are ordered so each finds its partners priced: the leaves, the store and the fixed cost first,
 * from a negation over a column; then every node kind the fuzz grammar draws, each added over
 * its own children at several of its occurrences in the fuzz sequences, with the median reading
 * as its price; then the prefix.
 *
 * The regression (model A) fits every feature's price by least squares over the corpus's
 * even-numbered shapes and the register's probes, the probes included so that a kind the corpus
 * rarely draws still has a price.
 */
object VarkaEmitCostFit {

  private type Price = Array[Double]
  private val Q = VarkaEmitCost.QUANTITIES

  /** One group of `roots` emitted as a single group, or a failure naming the probe. */
  private def single(label: String, roots: Seq[VarkaVectorIR], inputs: Int, lits: Int,
      options: VarkaEmitOptions = oneGroup, group: Int = 0): Group = {
    val groups = measure(roots, inputs, lits, options).getOrElse(
      throw new IllegalStateException(s"probe '$label' did not emit: $roots"))
    groups(group)
  }

  /**
   * The register, and the probe groups it was read from (the regression's extra rows). Fails
   * naming the kinds it could not price, which is how a new node kind announces itself.
   */
  def register(): (Map[String, Price], Seq[Group]) = {
    val prices = mutable.LinkedHashMap[String, Price]()
    val rows = mutable.ArrayBuffer[Group]()

    /**
     * The one feature `after` adds over `before` that has no price yet, and what it measured
     * per quantity - NaN for a method one side does not have, as in a kernel with no dense side
     * - or None when the probe adds no such feature or more than one.
     */
    def read(before: Option[Group], after: Group): Option[(String, Price)] = {
      val keys = after.counts.keySet ++ before.map(_.counts.keySet).getOrElse(Set.empty)
      val diff = keys.toSeq.map { k =>
        k -> (after.counts.getOrElse(k, 0) - before.map(_.counts.getOrElse(k, 0)).getOrElse(0))
      }.filter(_._2 != 0).toMap
      val unknown = diff.keys.filterNot(prices.contains).toSeq
      if (unknown.size != 1) return None
      val u = unknown.head
      Some(u -> Array.tabulate(Q) { q =>
        var v = after.measured(q) - before.map(_.measured(q)).getOrElse(0.0)
        diff.foreach { case (k, n) => if (k != u) v -= n * prices(k)(q) }
        v / diff(u)
      })
    }

    /** Records `readings` of one feature as its price: per quantity, their median. */
    def commit(feature: String, readings: Seq[Price]): Unit =
      prices(feature) = Array.tabulate(Q) { q =>
        val seen = readings.map(_(q)).filterNot(_.isNaN).sorted
        if (seen.isEmpty) 0.0 else seen(seen.size / 2)
      }

    /** One probe's reading, or None when either side does not emit as one group. */
    def one(label: String, before: Seq[VarkaVectorIR], added: Seq[VarkaVectorIR], inputs: Int,
        lits: Int): Option[(String, Price, Seq[Group])] = {
      def emitted(roots: Seq[VarkaVectorIR]): Option[Group] =
        measure(roots, inputs, lits, oneGroup).collect { case Seq(g) => g }
      for {
        b <- if (before.isEmpty) Some(None) else emitted(before).map(Some(_))
        a <- emitted(before ++ added)
        (f, p) <- read(b, a)
      } yield (f, p, a +: b.toSeq)
    }

    def probe(label: String, before: Seq[VarkaVectorIR], added: Seq[VarkaVectorIR], inputs: Int,
        lits: Int): Boolean =
      one(label, before, added, inputs, lits).exists { case (f, p, groups) =>
        commit(f, Seq(p))
        rows ++= groups
        true
      }

    def solve(label: String, before: Option[Group], after: Group): Boolean =
      read(before, after).exists { case (f, p) =>
        commit(f, Seq(p))
        rows += after
        true
      }

    def must(label: String, ok: Boolean): Unit =
      if (!ok) throw new IllegalStateException(s"register probe '$label' found no single " +
        "unpriced feature to read")

    for (lane <- LaneType.values) {
      val c0 = new ColumnRef(0, lane)
      val c1 = new ColumnRef(1, lane)
      val l0 = new LiteralSlot(0, lane)
      val n0 = new IntNeg(Overflow.WRAP, c0)
      must(s"out $lane", probe("out", Seq(n0), Seq(n0), 1, 0))
      must(s"neg $lane", probe("neg", Seq(n0), Seq(new IntNeg(Overflow.WRAP, n0)), 1, 0))
      must(s"column $lane", probe("column", Seq(n0), Seq(new IntNeg(Overflow.WRAP, c1)), 2, 0))
      must(s"literal $lane", probe("literal", Seq(n0), Seq(new IntNeg(Overflow.WRAP, l0)), 1, 1))
      must(s"fixed $lane", probe("fixed", Seq.empty, Seq(n0), 1, 0))
      val cmp = new Compare(CompareOp.LT, c0, c1)
      must(s"if $lane", probe("if", Seq(new IfElse(cmp, c0, c1)), Seq(new IfElse(cmp, c1, c0)),
        2, 0))
    }

    // Every other kind, from its occurrences in the fuzz sequences, each over its own children:
    // a node's code depends a little on what surrounds it, so the median of several readings is
    // the price, rather than whichever occurrence came first.
    val pending = mutable.LinkedHashMap[String, Seq[(VarkaVectorIR, Int, Int)]]()
    occurrences().foreach { case (kind, v) => if (!prices.contains(kind)) pending(kind) = v }
    var progress = true
    while (pending.nonEmpty && progress) {
      progress = false
      for ((kind, found) <- pending.toSeq) {
        val readings = found.flatMap { case (node, inputs, lits) =>
          val (before, added) = kindProbe(node)
          one(kind, before, added, inputs, lits).filter(_._1 == kind)
        }
        if (readings.nonEmpty) {
          commit(kind, readings.map(_._2))
          rows ++= readings.flatMap(_._3)
          pending.remove(kind)
          progress = true
        }
      }
    }
    if (pending.nonEmpty) {
      throw new IllegalStateException(s"no probe prices ${pending.keys.mkString(", ")}")
    }

    // The prefix, now that every tail has a price, and the selection's store last, now that
    // every condition has one.
    val d = new ColumnRef(0)
    val n0 = new IntNeg(Overflow.WRAP, d)
    must("prefix", probe("prefix", Seq(n0), Seq(new Year(d)), 1, 0))
    must("prefix/month", probe("prefix/month", Seq(n0), Seq(new Month(d)), 1, 0))
    val apart = oneGroup.withGroupBudget(1).withFusedCeiling(1)
    must("prefix/load", solve("prefix/load", None,
      single("prefix/load", Seq(new Month(d), new Year(d)), 1, 0, apart, group = 1)))
    for (lane <- LaneType.values) {
      val cmp = new Compare(CompareOp.LT, new ColumnRef(0, lane), new ColumnRef(1, lane))
      must(s"selection $lane", probe("selection", Seq.empty, Seq(cmp), 2, 0))
    }
    (prices.toMap, rows.toSeq)
  }

  /**
   * The shape a kind is priced on: its node's children as roots (a condition wrapped in a
   * conditional, since a condition is not a value root), with a calendar node's date given the
   * month-reading prefix by `month(d)` and `dayofmonth(d)`, and then the node itself, wrapped the
   * same way. Every feature the node brings is then on the first side except its own kind.
   */
  private def kindProbe(node: VarkaVectorIR): (Seq[VarkaVectorIR], Seq[VarkaVectorIR]) = {
    def asRoot(n: VarkaVectorIR): VarkaVectorIR = n match {
      case c: Cond =>
        val x = new ColumnRef(0, c.laneType())
        new IfElse(c, x, x)
      case v => v
    }
    val children = VarkaVectorIR.childrenOf(node).toSeq.map(asRoot)
    val prefix =
      if (VarkaEmitBudget.isChrono(node)) {
        val date = VarkaChronoLowering.chronoChild(node)
        Seq(new Month(date), new DayOfMonth(date)).filterNot(_ == node)
      } else Seq.empty
    ((children ++ prefix).distinct, Seq(asRoot(node)))
  }

  /** How many distinct nodes of each kind the register reads, at most. */
  val Readings = 9

  /**
   * The first `Readings` distinct nodes of each kind in the fuzz sequences, with their shape's
   * inputs and literals: what the register prices each kind on.
   */
  def occurrences(): Map[String, Seq[(VarkaVectorIR, Int, Int)]] = {
    val found = mutable.LinkedHashMap[String, mutable.LinkedHashMap[VarkaVectorIR, (Int, Int)]]()
    def visit(n: VarkaVectorIR, inputs: Int, lits: Int): Unit = {
      val seen = found.getOrElseUpdate(VarkaEmitCost.kindOf(n), mutable.LinkedHashMap())
      if (seen.size < Readings && !seen.contains(n)) seen(n) = (inputs, lits)
      VarkaVectorIR.childrenOf(n).foreach(visit(_, inputs, lits))
    }
    for (k <- 0 until 20000) {
      val d = VarkaIrGrammar.drawShape(VarkaIrGrammar.shapeRandom(VarkaIrGrammar.fuzzSeed, k))
      d.roots.foreach(visit(_, d.numInputs, d.numLiterals))
      val l = VarkaIrGrammar.drawLongShape(
        VarkaIrGrammar.shapeRandom(VarkaIrGrammar.longFuzzSeed, k))
      l.roots.foreach(visit(_, l.numInputs, l.numLiterals))
    }
    found.map { case (k, m) => k -> m.toSeq.map { case (n, (i, l)) => (n, i, l) } }.toMap
  }

  /**
   * The groups the regression is fitted on: the even-numbered fuzz and wide shapes, both arms.
   * The ladders are left out, so the audit's shapes at the budget are all held out.
   */
  def trainingGroups(): Seq[Group] =
    for {
      shape <- fuzz ++ wide
      if shape.index % 2 == 0
      (_, options) <- arms
      group <- measure(shape.roots, shape.numInputs, shape.numLiterals, options).getOrElse(Nil)
    } yield group

  /**
   * Least-squares prices for every feature in `rows`, one fit per quantity over the rows that
   * measured it, with a small ridge so a feature that always appears beside another still gets a
   * definite price.
   */
  def regression(rows: Seq[Group]): Map[String, Price] = {
    val features = rows.flatMap(_.counts.keys).distinct.sorted
    val index = features.zipWithIndex.toMap
    val f = features.size
    val coefficients = Array.ofDim[Double](f, Q)
    for (q <- 0 until Q) {
      val a = Array.ofDim[Double](f, f)
      val b = new Array[Double](f)
      rows.filterNot(_.measured(q).isNaN).foreach { r =>
        val x = r.counts.toSeq.map { case (k, v) => index(k) -> v.toDouble }
        for ((i, xi) <- x) {
          b(i) += xi * r.measured(q)
          for ((j, xj) <- x) a(i)(j) += xi * xj
        }
      }
      for (i <- 0 until f) a(i)(i) += 1e-3
      val solved = solveLinear(a, b)
      for (i <- 0 until f) coefficients(i)(q) = solved(i)
    }
    features.map(k => k -> coefficients(index(k))).toMap
  }

  /** Gaussian elimination with partial pivoting; `a` and `b` are overwritten. */
  private def solveLinear(a: Array[Array[Double]], b: Array[Double]): Array[Double] = {
    val n = b.length
    for (c <- 0 until n) {
      val p = (c until n).maxBy(r => math.abs(a(r)(c)))
      val tr = a(c); a(c) = a(p); a(p) = tr
      val tb = b(c); b(c) = b(p); b(p) = tb
      for (r <- c + 1 until n) {
        val m = a(r)(c) / a(c)(c)
        if (m != 0) {
          for (k <- c until n) a(r)(k) -= m * a(c)(k)
          b(r) -= m * b(c)
        }
      }
    }
    val x = new Array[Double](n)
    for (r <- n - 1 to 0 by -1) {
      var s = b(r)
      for (k <- r + 1 until n) s -= a(r)(k) * x(k)
      x(r) = s / a(r)(r)
    }
    x
  }

  /** A price as the source spells it: an integer bare, anything else to three places. */
  private def number(v: Double): String = {
    val r = math.round(v * 1000) / 1000.0
    if (r == math.rint(r)) math.rint(r).toLong.toString
    else java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString
  }

  private def entries(prices: Map[String, Price]): String =
    prices.toSeq.sortBy(_._1).map { case (k, v) =>
      s"""      Map.entry("$k",\n          new double[] {${v.map(number).mkString(", ")}})"""
    }.mkString(",\n")

  /** The Java source of `VarkaEmitCostTable` for these two tables. */
  def source(register: Map[String, Price], regression: Map[String, Price]): String =
    s"""/*
       | * Licensed to the Apache Software Foundation (ASF) under one or more
       | * contributor license agreements.  See the NOTICE file distributed with
       | * this work for additional information regarding copyright ownership.
       | * The ASF licenses this file to You under the Apache License, Version 2.0
       | * (the "License"); you may not use this file except in compliance with
       | * the License.  You may obtain a copy of the License at
       | *
       | *    http://www.apache.org/licenses/LICENSE-2.0
       | *
       | * Unless required by applicable law or agreed to in writing, software
       | * distributed under the License is distributed on an "AS IS" BASIS,
       | * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
       | * See the License for the specific language governing permissions and
       | * limitations under the License.
       | */
       |
       |package org.apache.spark.sql.catalyst.expressions.codegen.varka;
       |
       |import java.util.Map;
       |
       |/**
       | * The prices {@link VarkaEmitCost} predicts with: for each feature, what it adds to each
       | * of a group's four methods in bytes and then in Vector API call sites, in
       | * {@link VarkaEmitCost#METHODS} order.
       | *
       | * <p>Generated, not written: {@code VarkaEmitCostSuite} derives both tables from
       | * emitted classes at the default options and sixteen int lanes, fails while this file
       | * differs from what it derives, and rewrites it under {@code VARKA_COST_REGEN=true
       | * build/sbt 'catalyst/testOnly *VarkaEmitCostSuite'}. How each table is derived is
       | * {@code VarkaEmitCostFit}'s doc.
       | */
       |final class VarkaEmitCostTable {
       |
       |  private VarkaEmitCostTable() {}
       |
       |  /** The prices of {@code model}, by feature. */
       |  static Map<String, double[]> table(VarkaEmitCost.Model model) {
       |    return model == VarkaEmitCost.Model.REGISTER ? REGISTER : REGRESSION;
       |  }
       |
       |  /** Each feature measured by difference, beside a fixed partner. */
       |  private static final Map<String, double[]> REGISTER = Map.ofEntries(
       |${entries(register)});
       |
       |  /** Each feature fitted by least squares over the corpus's even-numbered shapes. */
       |  private static final Map<String, double[]> REGRESSION = Map.ofEntries(
       |${entries(regression)});
       |}
       |""".stripMargin

  /** Both tables derived afresh, as the source that carries them. */
  def derive(): String = {
    val (reg, probes) = register()
    source(reg, regression(trainingGroups() ++ probes))
  }
}
