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

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The emit cost model (`VarkaEmitCost`): its tables against the classes the emitter builds, its
 * reach over the IR, how it counts a group's features, and the grouping it drives under
 * `VarkaEmitOptions.predictGrouping`. How well it predicts is `VarkaEmitCostAuditSuite`'s.
 */
class VarkaEmitCostSuite extends VarkaEmitterTestBase {

  private val tablePath = getWorkspaceFilePath("sql", "catalyst", "src", "main", "java", "org",
    "apache", "spark", "sql", "catalyst", "expressions", "codegen", "varka",
    "VarkaEmitCostTable.java")

  test("the committed price tables are the ones the emitted classes give") {
    // The register is read off emitted classes feature by feature, and the regression is fitted
    // to emitted classes, so a lowering change that moves any method's bytes or call sites moves
    // a price. Failing here names the features that moved, which is what keeps the tables from
    // drifting the way the grouping weights once did.
    val derived = VarkaEmitCostFit.derive()
    if (sys.env.get("VARKA_COST_REGEN").contains("true")) {
      Files.write(tablePath, derived.getBytes(StandardCharsets.UTF_8))
      logInfo(s"regenerated $tablePath")
    } else {
      val committed = new String(Files.readAllBytes(tablePath), StandardCharsets.UTF_8)
      if (committed != derived) {
        val was = committed.linesIterator.toSet
        val moved = derived.linesIterator.filterNot(was.contains).take(20).toSeq
        fail("VarkaEmitCostTable.java differs from what the emitter's classes give; regenerate " +
          "with VARKA_COST_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmitCostSuite' and say " +
          "in the plan what moved. First lines that differ:\n  " + moved.mkString("\n  "))
      }
    }
  }

  test("every node kind the fuzz grammar draws has a price in both tables") {
    // A kind absent from a table would make every group holding it unpredictable, and the switch
    // would quietly fall back to the weights for it; the grammar reaches every IR node type, so
    // its kinds are the set to hold the tables to.
    val kinds = VarkaEmitCostFit.occurrences().keySet ++
      LaneType.values.toSeq.flatMap(l => Seq(VarkaEmitCost.FIXED + l, VarkaEmitCost.OUTPUT + l,
        VarkaEmitCost.SELECTION + l)) ++
      Seq(VarkaEmitCost.PREFIX, VarkaEmitCost.PREFIX_MONTH, VarkaEmitCost.PREFIX_LOAD)
    for (model <- VarkaEmitCost.Model.values) {
      val missing = kinds.filterNot(VarkaEmitCostTable.table(model).containsKey)
      assert(missing.isEmpty, s"$model has no price for ${missing.toSeq.sorted.mkString(", ")}")
    }
  }

  test("a tally counts what the emitter emits once, once") {
    // The features mirror the emitter's sharing: a repeated root is one more store and nothing
    // else, a node two outputs hold is counted once, a prefix is counted once per date and its
    // month step once, and a later group counts a materialized prefix as a load.
    val d = new ColumnRef(0)
    val materialize = true
    def counts(roots: VarkaVectorIR*): Map[String, Int] = {
      val t = new VarkaEmitCost.Tally(LaneType.INT, materialize, new java.util.HashSet())
      roots.foreach(t.add)
      t.counts().asScala.map { case (k, v) => k -> v.toInt }.toMap
    }
    val year = new Year(d)
    assert(counts(year) === Map("fixed/INT" -> 1, "out/INT" -> 1, "Year" -> 1,
      "ColumnRef/INT" -> 1, "prefix" -> 1))
    assert(counts(year, year) === counts(year) + ("out/INT" -> 2))
    assert(counts(year, new Month(d)) === counts(year) + ("out/INT" -> 2) + ("Month" -> 1) +
      ("prefix/month" -> 1))
    assert(counts(new Month(d), new DayOfMonth(d))("prefix/month") === 1)
    val groups = VarkaEmitCost.tallies(Seq[VarkaVectorIR](new Month(d), year).asJava,
      Seq(Seq(0), Seq(1)).map(_.map(Integer.valueOf).asJava).asJava,
      VarkaEmitOptions.DEFAULTS).asScala
    assert(groups(1).counts().asScala.toMap.map { case (k, v) => k -> v.toInt } ===
      Map("fixed/INT" -> 1, "out/INT" -> 1, "Year" -> 1, "ColumnRef/INT" -> 1,
        "prefix/load" -> 1))
    assert(counts(new Compare(CompareOp.LT, d, new ColumnRef(1)))("out/cond/INT") === 1)
  }

  /** `roots` at the shipped options and sixteen lanes, with the prediction on or off. */
  private def emitted(roots: Seq[VarkaVectorIR], inputs: Int, lits: Int,
      predict: Boolean): Option[Array[Byte]] = {
    try {
      Some(VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaEmitCostTest",
        roots.asJava, inputs, lits, null, null, VarkaEmitOptions.DEFAULTS
          .withLanesOverride(VarkaEmitCostCorpus.Lanes).withPredictGrouping(predict)))
    } catch {
      case _: VarkaEmitDeclined => None
    }
  }

  /** The loop methods of an emitted class: its groups. */
  private def loops(bytes: Array[Byte]): Int = {
    val names = VarkaEmittedClass.measure(bytes).codeLength.keySet.asScala
    math.max(names.count(_.startsWith("loopDense")), names.count(_.startsWith("loopMasked")))
  }

  test("under predictGrouping the fuzz shapes emit byte for byte as under the weights") {
    // No fuzz shape comes near a budget - the grammar draws one to three roots, and a group of
    // at most HEAVY_GROUP_OUTPUTS outputs is exempt from the call-site budget - so a prediction
    // that closed a group on one of them would be a prediction wrong by a factor, and the bytes
    // are the sharpest way to see it.
    for (k <- 0 until 400) {
      val d = VarkaIrGrammar.drawShape(VarkaIrGrammar.shapeRandom(VarkaIrGrammar.fuzzSeed, k))
      val l = VarkaIrGrammar.drawLongShape(
        VarkaIrGrammar.shapeRandom(VarkaIrGrammar.longFuzzSeed, k))
      for ((lane, roots, inputs, lits) <- Seq(("int", d.roots, d.numInputs, d.numLiterals),
          ("long", l.roots, l.numInputs, l.numLiterals))) {
        val off = emitted(roots, inputs, lits, predict = false)
        val on = emitted(roots, inputs, lits, predict = true)
        assert(off.map(_.toSeq) === on.map(_.toSeq), s"$lane shape $k: $roots")
      }
    }
  }

  test("the cheap tails build once under predictGrouping, in fewer loop methods") {
    // The one family the call-site budget rebuilds at the shipped options: the weights put every
    // tail over one date in one group, the built class measures its methods over the budget, and
    // the halving builds it two or three times. The prediction closes each group where the
    // measurement would, so the first grouping is the last.
    for (n <- Seq(22, 64)) {
      val roots = (0 until n).map(VarkaEmitCostCorpus.tailEntry)
      val options = VarkaEmitOptions.DEFAULTS.withLanesOverride(VarkaEmitCostCorpus.Lanes)
        .withPredictGrouping(true)
      val on = emitted(roots, 1, n, predict = true).get
      val off = emitted(roots, 1, n, predict = false).get
      assert(loops(on) === VarkaLoopEmitter.groupsForTest(roots.asJava, options).size,
        s"$n tails: the predicted grouping was regrouped after the build")
      assert(loops(on) <= loops(off), s"$n tails: ${loops(on)} loop methods predicted, " +
        s"${loops(off)} under the weights")
      VarkaEmittedClass.measure(on).vectorCallSites.asScala.foreach { case (m, sites) =>
        if (VarkaEmitBudget.groupOf(m) >= 0) {
          assert(sites <= VarkaEmitBudget.CALL_SITE_BUDGET, s"$n tails: $m carries $sites")
        }
      }
    }
  }

  test("under predictGrouping no wide shape declines that the weights emit, and the two that " +
      "gain a loop method are the greedy close the plan records") {
    // The predicted grouping closes groups the weights keep, and each group is a call more in
    // the driver, which on the widest shapes pushed the driver past the byte budget; such a class
    // is built again with the weights alone, so the switch never costs a kernel. A shape can
    // still gain a loop method where the measurement halves a group and the greedy close fills
    // one and leaves the rest in two (PLAN_TASK_199.md 9): that list is pinned, so a new entry
    // is seen rather than averaged away.
    val gained = Seq.newBuilder[String]
    for (shape <- VarkaEmitCostCorpus.wide) {
      val where = s"${shape.family} ${shape.index}"
      emitted(shape.roots, shape.numInputs, shape.numLiterals, predict = false).foreach { off =>
        val on = emitted(shape.roots, shape.numInputs, shape.numLiterals, predict = true)
          .getOrElse(fail(s"$where emits under the weights and declines predicted"))
        if (loops(on) > loops(off)) gained += s"$where: ${loops(off)} -> ${loops(on)}"
      }
    }
    assert(gained.result() === Seq("wide int 120: 31 -> 32", "wide long 95: 40 -> 41"))
  }

  test("under predictGrouping the shapes it regroups answer as the reference evaluator does") {
    // The fuzz draw toggles the switch like every option, but its shapes are too narrow for a
    // prediction to close a group, so the switch is checked here on the shapes where it does:
    // the cheap tails at the int lane, and at the long lane forty outputs over one shared
    // division, which the call-site budget splits. Each emits under the switch in a grouping the
    // weights never form, and is run at every null pattern over lengths that leave an epilogue.
    val predicted = VarkaEmitOptions.DEFAULTS.withPredictGrouping(true)
    val tails = (0 until 64).map(VarkaEmitCostCorpus.tailEntry)
    assert(VarkaLoopEmitter.groupsForTest(tails.asJava, predicted) !=
      VarkaLoopEmitter.groupsForTest(tails.asJava, VarkaEmitOptions.DEFAULTS))
    checkMatrix(tails, 1, (0 until 64).map(k => k * 3 - 90).toArray, Seq(1, 7, 17, 64, 129),
      combos(1), options = predicted, ctx = "cheap tails, predicted")
    checkMatrix(tails, 1, (0 until 64).map(k => k * 3 - 90).toArray, Seq(17, 129),
      combos(1), forceMasked = true, options = predicted, ctx = "cheap tails, predicted, masked")

    val col = new ColumnRef(0, LaneType.LONG)
    val hours = new ConstDivide(col, 3_600_000_000_000L, ConstDivide.EXACT_DIVIDEND_BOUND)
    val divisions: Seq[VarkaVectorIR] = (0 until 40).map { k =>
      new IntArith(IntOp.ADD, Overflow.WRAP, hours, new LiteralSlot(k, LaneType.LONG))
    }
    assert(VarkaLoopEmitter.groupsForTest(divisions.asJava, predicted).size > 1)
    val bound = ConstDivide.EXACT_DIVIDEND_BOUND - 1
    for (lanes <- Seq(2, 8)) {
      checkLongMatrix(divisions, 1, (0 until 40).map(k => k * 1_000_003L - 17L).toArray,
        Seq(1, 7, 17, 64, 129), combos(1),
        (_: Int, i: Int) => ((i * 7_919_000_003L) % bound) * (if (i % 2 == 0) 1 else -1),
        "long divisions, predicted", lanes, predicted)
    }
  }
}
