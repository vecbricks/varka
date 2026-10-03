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
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The emit cost model (`VarkaEmitCost`): its price tables against the classes the emitter
 * builds, its reach over the IR, how the grouping counts a group's features, and the grouping
 * it drives under `VarkaEmitOptions.predictGrouping`. How well it predicts is
 * `VarkaEmitCostAuditSuite`'s. The corpus, the fit and the audit are Java
 * (`VarkaEmitCostCorpus`, `VarkaEmitCostFit`, `VarkaEmitCostAudit`); this suite drives them.
 */
class VarkaEmitCostSuite extends VarkaEmitterTestBase {

  private def varkaDir(tree: String): Path = getWorkspaceFilePath("sql", "catalyst", "src", tree,
    "java", "org", "apache", "spark", "sql", "catalyst", "expressions", "codegen", "varka")

  private val sources = Seq(varkaDir("main").resolve("VarkaEmitCostTable.java"),
    varkaDir("test").resolve("VarkaEmitCostRegister.java"))

  test("the committed price tables are the ones the emitted classes give") {
    // The register is read off emitted classes feature by feature, and the fitted prices are
    // fitted to emitted classes, so a lowering change that moves any method's bytes or call
    // sites moves a price. Failing here names the lines that moved, which is what keeps the
    // tables from drifting the way the grouping weights once did.
    val derived = VarkaEmitCostFit.derive().asScala
    for ((path, text) <- sources.zip(derived)) {
      if (sys.env.get("VARKA_COST_REGEN").contains("true")) {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8))
        logInfo(s"regenerated $path")
      } else {
        val committed = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        if (committed != text) {
          val was = committed.linesIterator.toSet
          val moved = text.linesIterator.filterNot(was.contains).take(20).toSeq
          fail(s"${path.getFileName} differs from what the emitter's classes give; regenerate " +
            "with VARKA_COST_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmitCostSuite' and " +
            "say in the plan what moved. First lines that differ:\n  " + moved.mkString("\n  "))
        }
      }
    }
  }

  /** Every record type of the IR's sealed hierarchy, by simple name. */
  private def irTypes(c: Class[_] = classOf[VarkaVectorIR]): Set[String] =
    Option(c.getPermittedSubclasses).map(_.toSet.flatMap((k: Class[_]) => irTypes(k)))
      .getOrElse(Set(c.getSimpleName))

  test("every feature the IR can produce has a price in both tables") {
    // A feature missing from a table makes every group holding it unpredictable, and the switch
    // then falls back to the weights for that group without a word. The expected set is
    // enumerated from the IR's own enums, not from what the fuzz grammar draws - the grammar
    // did not draw NarrowLane, the root of every TIME kernel, until task 235 - and every record
    // type of the IR must be named in it, so a node type added later fails here until priced.
    val expected = VarkaEmitCostFit.expectedFeatures().asScala.toSet
    val unnamed = irTypes().filterNot(t => expected.exists(f => f == t || f.startsWith(t + "/")))
    assert(unnamed.isEmpty, s"IR types with no expected feature: ${unnamed.toSeq.sorted}")
    for ((name, prices) <- Seq("VarkaEmitCostTable" -> VarkaEmitCostTable.PRICES,
        "VarkaEmitCostRegister" -> VarkaEmitCostRegister.PRICES)) {
      val missing = expected.filterNot(prices.containsKey)
      assert(missing.isEmpty, s"$name has no price for ${missing.toSeq.sorted.mkString(", ")}")
    }
  }

  /** The feature counts of each group of the first grouping of `roots` under `options`. */
  private def counts(options: VarkaEmitOptions, roots: VarkaVectorIR*): Seq[Map[String, Int]] =
    VarkaLoopEmitter.talliesForTest(roots.asJava, options, VarkaEmitCostTable.PRICES).asScala
      .map(_.counts().asScala.map { case (k, v) => k -> v.toInt }.toMap).toSeq

  test("the grouping counts what the emitter emits once, once") {
    // The features are fed by the grouping's own walk, so they follow its sharing: a repeated
    // root is one more store and nothing else, a node two outputs hold is counted once, a
    // shared prefix is counted once per date and its month step once, an unshared one once per
    // calendar node, and a later group counts a materialized prefix as a load.
    val d = new ColumnRef(0)
    val year = new Year(d)
    val month = new Month(d)
    val defaults = VarkaEmitOptions.DEFAULTS
    val one = Map("fixed/INT" -> 1, "out/INT" -> 1, "Year" -> 1, "ColumnRef/INT" -> 1,
      "prefix" -> 1)
    assert(counts(defaults, year) === Seq(one))
    assert(counts(defaults, year, year) === Seq(one + ("out/INT" -> 2)))
    assert(counts(defaults, year, month) ===
      Seq(one + ("out/INT" -> 2) + ("Month" -> 1) + ("prefix/month" -> 1)))
    assert(counts(defaults.withShareChronoPrefix(false).withGroupBudget(400), year, month) ===
      Seq(one + ("out/INT" -> 2) + ("Month" -> 1) + ("prefix" -> 2) + ("prefix/month" -> 1)))
    val apart = defaults.withGroupBudget(1).withFusedCeiling(1)
    assert(counts(apart, month, year)(1) ===
      Map("fixed/INT" -> 1, "out/INT" -> 1, "Year" -> 1, "ColumnRef/INT" -> 1,
        "prefix/load" -> 1))
    assert(counts(defaults, new Compare(CompareOp.LT, d, new ColumnRef(1))).head(
      "out/cond/INT") === 1)
    val long = new IntNeg(Overflow.WRAP, new ColumnRef(0, LaneType.LONG))
    assert(counts(defaults, new NarrowLane(long)).head ===
      Map("fixed/LONG" -> 1, "out/LONG" -> 1, "NarrowLane" -> 1, "IntNeg/WRAP/LONG" -> 1,
        "ColumnRef/LONG" -> 1))
  }

  /**
   * `roots` at the audit's width, with the prediction on or off and the plan off: the bytes and
   * the builds taken. The plan's margins close groups earlier than the prediction alone, and
   * what this suite holds is the prediction; the plan is `VarkaKernelPlanSuite`'s.
   */
  private def emitted(roots: Seq[VarkaVectorIR], inputs: Int, lits: Int, predict: Boolean,
      base: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS.withPlanSize(false))
      : (Option[Array[Byte]], Int) = {
    val builds = new Array[Int](1)
    val bytes = try {
      Some(VarkaLoopEmitter.emitCountingBuilds(
        "org.apache.spark.sql.varka.execution.VarkaEmitCostTest", roots.asJava, inputs, lits,
        base.withLanesOverride(VarkaEmitCostCorpus.LANES).withPredictGrouping(predict), builds))
    } catch {
      case _: VarkaEmitDeclined => None
    }
    (bytes, builds(0))
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
    for (shape <- VarkaEmitCostCorpus.fuzz(400).asScala) {
      val roots = shape.roots.asScala.toSeq
      val off = emitted(roots, shape.numInputs, shape.numLiterals, predict = false)._1
      val on = emitted(roots, shape.numInputs, shape.numLiterals, predict = true)._1
      assert(off.map(_.toSeq) === on.map(_.toSeq), s"${shape.family} ${shape.index}: $roots")
    }
  }

  test("the cheap tails build once under predictGrouping, in fewer loop methods") {
    // The one family the call-site budget rebuilds at the shipped options: the weights put every
    // tail over one date in one group, the built class measures its methods over the budget, and
    // the halving builds it two or three times. The prediction closes each group where the
    // measurement would, so the first grouping is the last.
    for (n <- Seq(22, 64)) {
      val roots = (0 until n).map(VarkaEmitCostCorpus.tailEntry)
      val (on, onBuilds) = emitted(roots, 1, n, predict = true)
      val (off, offBuilds) = emitted(roots, 1, n, predict = false)
      assert(onBuilds === 1 && offBuilds > 1, s"$n tails: $onBuilds and $offBuilds builds")
      assert(loops(on.get) <= loops(off.get), s"$n tails: ${loops(on.get)} loop methods " +
        s"predicted, ${loops(off.get)} under the weights")
      VarkaEmittedClass.measure(on.get).vectorCallSites.asScala.foreach { case (m, sites) =>
        if (VarkaEmitBudget.groupOf(m) >= 0) {
          assert(sites <= VarkaEmitBudget.CALL_SITE_BUDGET, s"$n tails: $m carries $sites")
        }
      }
    }
  }

  test("under predictGrouping every wide shape gets the weights' verdict, and those that gain " +
      "a loop method are the ones the plans record") {
    // The predicted grouping closes groups the weights keep, and each group is a call more in
    // the driver, which on the widest shapes pushed the driver past the byte budget; such a class
    // is built again with the weights alone, so the switch never costs a kernel, and a shape the
    // weights decline declines under it too; what those declines cost in builds is pinned in
    // `emit_cost_audit.json`. A shape can still
    // gain a loop method where the measurement halves a group and the greedy close fills one
    // and leaves the rest in two (PLAN_TASK_199.md 9): that list is pinned, so a new entry is
    // seen rather than averaged away. The greedy walk's, as the plan records it: under the exact
    // grouping, the default since `PLAN_TASK_200.md` 8.2, `VarkaGroupingBoundSuite` holds the
    // same wide shapes to no decline and no fallback.
    val greedy = VarkaEmitOptions.DEFAULTS.withExactGrouping(false).withPlanSize(false)
    val gained = Seq.newBuilder[String]
    for (shape <- VarkaEmitCostCorpus.wide().asScala) {
      val where = s"${shape.family} ${shape.index}"
      val roots = shape.roots.asScala.toSeq
      val off = emitted(roots, shape.numInputs, shape.numLiterals, predict = false, greedy)._1
      val on = emitted(roots, shape.numInputs, shape.numLiterals, predict = true, greedy)._1
      off match {
        case Some(o) =>
          val p = on.getOrElse(fail(s"$where emits under the weights and declines predicted"))
          if (loops(p) > loops(o)) gained += s"$where: ${loops(o)} -> ${loops(p)}"
        case None =>
          assert(on.isEmpty, s"$where declines under the weights and emits predicted")
      }
    }
    // The int-lane entry is that greedy close: the weights' class is built twice. The two
    // long-lane entries are a second way to gain one, found when task 235 moved the long-lane
    // corpus (PLAN_TASK_235.md 9): the weights build once, so the measurement never splits a
    // group, and the prediction still closes one a method early - a prediction near the budget
    // erring high, where the greedy close is one erring low.
    assert(gained.result() === Seq("wide int 120: 31 -> 32", "wide long 59: 55 -> 56",
      "wide long 78: 14 -> 15"))
  }

  test("a decline no grouping can avoid is not built again under the weights") {
    // One make_date output alone over a byte budget of a few hundred bytes: the predicted
    // grouping and the weights both leave it a group of its own, the class declines naming it,
    // and the fallback that rebuilds a predicted grouping's decline is skipped.
    val col = new ColumnRef(0)
    val roots = Seq(new MakeDate(new Year(col), new Month(col), new LiteralSlot(0), true),
      new Year(col))
    val tight = VarkaEmitOptions.DEFAULTS.withMethodByteBudget(300)
    val (off, offBuilds) = emitted(roots, 1, 1, predict = false, tight)
    val (on, onBuilds) = emitted(roots, 1, 1, predict = true, tight)
    assert(off.isEmpty && on.isEmpty)
    assert(onBuilds <= offBuilds, s"$onBuilds builds predicted, $offBuilds under the weights")
  }

  test("under predictGrouping the shapes it regroups answer as the reference evaluator does") {
    // The fuzz draw toggles the switch like every option, but its shapes are too narrow for a
    // prediction to close a group, so the switch is checked here on the shapes where it does:
    // the cheap tails at the int lane, and at the long lane forty outputs over one shared
    // division, which the call-site budget splits. Each emits under the switch in a grouping the
    // weights never form, and is run at every null pattern over lengths that leave an epilogue.
    // The prediction alone, without the plan's margins (task 236), against the weights alone.
    val weights = VarkaEmitOptions.DEFAULTS.withPlanSize(false).withPredictGrouping(false)
    val predicted = weights.withPredictGrouping(true)
    val tails = (0 until 64).map(VarkaEmitCostCorpus.tailEntry)
    assert(VarkaLoopEmitter.groupsForTest(tails.asJava, predicted) !=
      VarkaLoopEmitter.groupsForTest(tails.asJava, weights))
    val lits = (0 until 64).map(k => k * 3 - 90).toArray
    checkMatrix(tails, 1, lits, Seq(1, 7, 17, 64, 129), combos(1), options = predicted,
      ctx = "cheap tails, predicted")
    checkMatrix(tails, 1, lits, Seq(17, 129), combos(1), forceMasked = true,
      options = predicted, ctx = "cheap tails, predicted, masked")

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
