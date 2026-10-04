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

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.isDriver
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The plan of a kernel's size before its first build (`VarkaEmitOptions.planSize`,
 * `VARKA-236.md` 3): the driver read off a driver built alone, the stages sized from it in
 * the first build, the decline before any build that names the compiler's cut, and the size
 * loop left as the last resort.
 */
class VarkaKernelPlanSuite extends VarkaEmitterTestBase {

  // Both arms under the weights alone: with the prediction on, the plan's margins close groups
  // the prediction without them keeps, so the loop and the plan would not be building the same
  // grouping on the shapes `emit_cost_audit.json` names; the margins are the audit's to pin.
  private val unplanned = VarkaEmitOptions.DEFAULTS.withPlanSize(false).withPredictGrouping(false)
  private val planned = unplanned.withPlanSize(true)

  /** One group per output: a group budget of one closes a group after every `date_add`. */
  private def oneEach(options: VarkaEmitOptions): VarkaEmitOptions =
    options.withGroupBudget(1).withFusedCeiling(1)

  private def dateAdds(n: Int): Seq[VarkaVectorIR] =
    (0 until n).map(k => new AddDays(new ColumnRef(0), new LiteralSlot(k)))

  private def ladderEntry(k: Int): VarkaVectorIR = {
    val col = new ColumnRef(0)
    new Greatest(new Greatest(new AddMonths(col, new LiteralSlot(k)),
      new AddDays(col, new LiteralSlot(k))), new LastDay(col))
  }

  /** The class, how many times it was built, and its trace. */
  private def emitted(roots: Seq[VarkaVectorIR], numInputs: Int, lits: Int,
      options: VarkaEmitOptions): (Array[Byte], VarkaEmitTrace) = {
    val trace = new VarkaEmitTrace
    val bytes = VarkaLoopEmitter.emitTraced(
      "org.apache.spark.sql.varka.execution.VarkaKernelPlanTest", roots.asJava, numInputs, lits,
      options, trace)
    (bytes, trace)
  }

  private def drivers(m: VarkaEmittedClass): Map[String, Int] = m.codeLength.asScala.collect {
    case (k, v) if isDriver(k) => k -> v.intValue
  }.toMap

  test("the plan's driver is the built driver, byte for byte, from one group to four hundred") {
    // The driver from a table is its calls alone, so built by itself over the first grouping it
    // measures what the full class's driver measures; a change to the driver's code fails here
    // instead of costing a rebuild. The whole driver is read, so the split driver is off.
    val whole = unplanned.withSplitDriver(false)
    val shapes = Seq(1, 2, 50, 100, 180).map(n => (s"$n one-output groups", dateAdds(n), n,
      oneEach(whole))) :+ ("four hundred ladder entries", (0 until 400).map(ladderEntry), 400,
      whole)
    for ((name, roots, lits, options) <- shapes) {
      val (bytes, _) = emitted(roots, 1, lits, options)
      val built = drivers(VarkaEmittedClass.measure(bytes))
      val plan = drivers(VarkaLoopEmitter.plannedDriversForTest(roots.asJava, 1, lits, options))
      assert(plan === built, name)
    }
  }

  test("the planned split driver is the two-build class, byte for byte, in one build") {
    // Past the driver's ceiling the loop builds the class whole, reads the stage size off its
    // driver and builds it again; the plan reads the same size off the driver built alone, so
    // its one build is that second build (`VARKA-236.md` 2.2). The compositions of wide draws
    // the audit counts are the shapes near and past the ceiling the fuzzer draws.
    val shapes = Seq(("three hundred one-output groups", dateAdds(300), 1, 300, oneEach(_)),
      ("fifteen hundred one-output groups", dateAdds(1500), 1, 1500, oneEach(_))) ++
      Seq(800, 1200).map(n => (s"$n ladder entries", (0 until n).map(ladderEntry), 1, n,
        identity[VarkaEmitOptions] _)) ++
      VarkaEmitCostCorpus.pastCeiling().asScala.collect {
        case s if s.family.startsWith("wide compositions") =>
          (s"${s.family} ${s.index}", s.roots.asScala.toSeq, s.numInputs, s.numLiterals,
            (o: VarkaEmitOptions) => o.withLanesOverride(VarkaEmitCostCorpus.LANES))
      }
    var staged = 0
    for ((name, roots, numInputs, lits, arm) <- shapes) {
      val (loop, loopTrace) = emitted(roots, numInputs, lits, arm(unplanned))
      val (plan, planTrace) = emitted(roots, numInputs, lits, arm(planned))
      assert(java.util.Arrays.equals(loop, plan), s"$name: the planned class differs")
      // Each sizing of the stages the plan reads off a driver built alone is a build the loop
      // spent finding that driver over, and nothing else moves: a composition the loop also
      // split on call sites is split so under the plan too, as a correction of its one build.
      assert(planTrace.builds === loopTrace.builds - planTrace.plannedStages,
        s"$name: ${planTrace.builds} builds under the plan, ${loopTrace.builds} in the loop")
      if (loopTrace.stageSplits > 0) {
        staged += 1
        assert(planTrace.plannedStages >= 1, s"$name: the stages were not planned")
      }
    }
    assert(staged >= 4, s"$staged shapes staged: the shapes do not reach the ceiling")
  }

  test("a driver over the budget with the split driver off declines before any build, naming " +
      "the prefix one class serves") {
    val whole = planned.withSplitDriver(false)
    val roots = (0 until 800).map(ladderEntry)
    val trace = new VarkaEmitTrace
    val declined = intercept[VarkaEmitDeclined] {
      VarkaLoopEmitter.emitTraced("org.apache.spark.sql.varka.execution.VarkaKernelPlanTest",
        roots.asJava, 1, 800, whole, trace)
    }
    assert(trace.builds === 0 && trace.plannedDeclines === 1)
    assert(declined.outputs().isEmpty && declined.getMessage.contains("planned before the build"))
    val cut = declined.plannedCut()
    assert(cut > 600 && cut < 800, s"cut at $cut")
    // The prefix it names is one class, built once, and one entry more is not: the driver's
    // bytes are exact, so the cut is the largest prefix one class serves (VARKA-236.md 2.3).
    val (_, prefixTrace) = emitted(roots.take(cut), 1, cut, whole)
    assert(prefixTrace.builds === 1 && prefixTrace.plannedDeclines === 0)
    intercept[VarkaEmitDeclined](emitted(roots.take(cut + 4), 1, cut + 4, whole))
    // The loop's answer, found by bisection without the plan, is the same prefix.
    val loop = unplanned.withSplitDriver(false)
    def fits(k: Int): Boolean =
      try { emitted(roots.take(k), 1, k, loop); true } catch { case _: VarkaEmitDeclined => false }
    var lo = cut
    var hi = 800
    while (hi - lo > 1) {
      val mid = (lo + hi) / 2
      if (fits(mid)) lo = mid else hi = mid
    }
    assert(lo === cut, s"the loop fits $lo entries where the plan cuts at $cut")
  }

  test("a planned build the measurement finds over a limit is corrected once, and the " +
      "correction is named") {
    // `misdescribeDriverBytes` takes bytes off the plan's reading of the driver, so the plan
    // sizes no stages for three hundred one-output groups and the build finds the driver over;
    // the loop's own reaction then sizes the stages, which the trace counts as the plan's
    // correction.
    val (bytes, trace) = emitted(dateAdds(300), 1, 300,
      oneEach(planned).withMisdescribeDriverBytes(100000))
    assert(trace.builds === 2 && trace.plannedStages === 0 && trace.stageSplits === 1)
    assert(trace.corrections.size === 1 && trace.corrections.get(0).startsWith("the stages:"),
      trace.corrections)
    assert(VarkaEmitBudget.overLimits(VarkaEmittedClass.measure(bytes)).isEmpty)
  }

  test("under the plan no shape of the audit's corpus builds a third time") {
    // The audit file names the shapes the plan corrects; here, over the same shapes, the loop
    // never has to run past the correction (`VARKA-236.md` 3.2).
    val shapes = VarkaEmitCostAudit.heldOut().asScala ++ VarkaEmitCostCorpus.pastCeiling().asScala
    var corrected = Seq.empty[String]
    for (s <- shapes) {
      val trace = new VarkaEmitTrace
      try {
        VarkaLoopEmitter.emitTraced("org.apache.spark.sql.varka.execution.VarkaKernelPlanTest",
          s.roots, s.numInputs, s.numLiterals, VarkaEmitCostAudit.planned(), trace)
      } catch {
        case _: VarkaEmitDeclined =>
      }
      assert(trace.builds <= 2, s"${s.family} ${s.index}: ${trace.builds} builds, " +
        s"corrections ${trace.corrections.asScala}")
      if (trace.builds == 2) {
        corrected :+= s"${s.family} ${s.index}"
      }
    }
    assert(corrected.size <= 9, s"${corrected.size} shapes corrected: $corrected")
  }

  test("a planned kernel answers as the reference evaluator does, staged and cut") {
    val staged = dateAdds(300)
    for (masked <- Seq(false, true)) {
      checkMatrix(staged, 1, (0 until 300).map(k => k * 7 - 1000).toArray,
        if (masked) Seq(17, 129) else Seq(1, 17, 129), combos(1), forceMasked = masked,
        ctx = s"300 one-output groups, planned, forceMasked $masked", options = oneEach(planned))
    }
    val roots = (0 until 800).map(ladderEntry)
    val cut = intercept[VarkaEmitDeclined](
      emitted(roots, 1, 800, planned.withSplitDriver(false))).plannedCut()
    checkMatrix(roots.take(cut), 1, (0 until cut).map(k => k % 40 - 20).toArray, Seq(1, 129),
      combos(1), ctx = s"the first $cut ladder entries, planned",
      options = planned.withSplitDriver(false))
  }
}
