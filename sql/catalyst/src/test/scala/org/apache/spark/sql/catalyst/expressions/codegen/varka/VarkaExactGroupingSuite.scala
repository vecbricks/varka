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

/**
 * The exact grouping (`VarkaEmitOptions.exactGrouping`) as the emitter builds it: the loop
 * methods it saves where the greedy walk strands cheap outputs, the classes it leaves alone where
 * the greedy walk is already at the best, its answers, and how it meets a forced start and the
 * measurement's regroup. How it compares with the best partition over the whole corpus is
 * `VarkaGroupingBoundSuite`'s. See `PLAN_TASK_200.md`.
 */
class VarkaExactGroupingSuite extends VarkaEmitterTestBase {

  private val greedy = VarkaEmitOptions.DEFAULTS
  private val exact = VarkaEmitOptions.DEFAULTS.withExactGrouping(true)

  private def mixed(n: Int): Seq[VarkaVectorIR] = VarkaGroupingBound.mixed(n).asScala.toSeq

  /** The loop methods of an emitted class: its groups. */
  private def loops(bytes: Array[Byte]): Int = {
    val names = VarkaEmittedClass.measure(bytes).codeLength.keySet.asScala
    math.max(names.count(_.startsWith("loopDense")), names.count(_.startsWith("loopMasked")))
  }

  /** A literal for each entry of the mixed family: a day `make_date` accepts in any month. */
  private def mixedLits(n: Int): Array[Int] = Array.tabulate(n)(k => k % 28 + 1)

  test("on the mixed family the exact grouping emits the best partition's loop methods, about " +
      "half the greedy walk's, at the same ops") {
    // The greedy walk closes a group on the three prefix-sharers before each date_add, which
    // then fits nowhere and takes a loop method of its own; the best partition starts each group
    // at the cheap outputs instead. At forty entries that is 20 groups against 11.
    for (n <- Seq(40, 200)) {
      val roots = mixed(n)
      val runs = VarkaGroupingBound.runs(roots.asJava, greedy)
      val best = VarkaGroupingBound.optimal(runs)
      val greedyPartition = VarkaGroupingBound.of(runs,
        VarkaLoopEmitter.groupsForTest(roots.asJava, greedy))
      val exactPartition = VarkaGroupingBound.of(runs,
        VarkaLoopEmitter.groupsForTest(roots.asJava, exact))
      assert(exactPartition.ops === greedyPartition.ops && exactPartition.ops === best.ops)
      assert(exactPartition.groups === best.groups && best.groups < greedyPartition.groups)
      assert(loops(emitMulti(roots, 1, n, exact)._2) === best.groups)
      assert(loops(emitMulti(roots, 1, n, greedy)._2) === greedyPartition.groups)
      if (n == 40) {
        assert((greedyPartition.groups, best.groups) === (20, 11))
      }
    }
  }

  test("where the greedy partition is already the best the exact grouping emits the same class, " +
      "byte for byte") {
    // The ladders are each one entry repeated, and the greedy walk fills every group as far as
    // the rule allows, which is the best partition too; the exact grouping breaks its ties toward
    // the longest first group, as the greedy walk does, so it changes nothing there.
    def bodies(b: Array[Byte]): Map[String, String] = VarkaEmitterTestSupport.methodBodies(b)
      .asScala.toMap.map { case (m, body) => m -> body.replaceAll("VarkaFusedTest\\d+", "K") }
    val ladders = Seq(
      ("size ladder at 100", (0 until 100).map(VarkaEmitCostCorpus.ladderEntry), 100),
      ("make_date ladder at 60", (0 until 60).map(VarkaEmitCostCorpus.makeDateEntry), 60),
      ("cheap tails at 64", (0 until 64).map(VarkaEmitCostCorpus.tailEntry), 64))
    for ((name, roots, lits) <- ladders) {
      assert(bodies(emitMulti(roots, 1, lits, exact)._2) ===
        bodies(emitMulti(roots, 1, lits, greedy)._2), name)
    }
  }

  test("the mixed family answers as the reference evaluator does under the exact grouping") {
    for (n <- Seq(40, 200); masked <- Seq(false, true)) {
      checkMatrix(mixed(n), 1, mixedLits(n),
        if (masked) Seq(7, 17, 129) else Seq(1, 17, 129), combos(1), forceMasked = masked,
        ctx = s"$n mixed entries, exact grouping, forceMasked $masked", options = exact)
    }
  }

  test("a forced start begins a group of the exact grouping, the groups around it are ones the " +
      "rule admits, and the ops are no more than the greedy walk's with the same start") {
    // The measurement's regroup forces a start at the middle of a group over a budget, and the
    // exact grouping must honour it as the greedy walk does: the best partition is then the best
    // of those in which the start begins a group.
    val roots = mixed(40).asJava
    val forced = Set(5, 17, 30).map(Integer.valueOf).asJava
    val runs = VarkaGroupingBound.runs(roots, greedy)
    val exactGrouping = VarkaLoopEmitter.groupsForTest(roots, exact, forced)
    val starts = exactGrouping.asScala.map(_.get(0).intValue).toSet
    assert(Set(5, 17, 30).subsetOf(starts), exactGrouping)
    assert(Option(VarkaGroupingBound.unformable(runs, exactGrouping)).isEmpty, exactGrouping)
    val greedyGrouping = VarkaLoopEmitter.groupsForTest(roots, greedy, forced)
    assert(VarkaGroupingBound.of(runs, exactGrouping).ops <=
      VarkaGroupingBound.of(runs, greedyGrouping).ops)
  }

  test("under a byte budget the measurement still halves the groups of the exact grouping, every " +
      "method fits, and the kernel answers") {
    // Sixty-four cheap tails over one date, at a budget a single tail's methods fit under and a
    // group of them does not: the build measures, forces starts, and the exact grouping rebuilds
    // around them until every group fits.
    val roots = (0 until 64).map(VarkaEmitCostCorpus.tailEntry)
    val tight = exact.withMethodByteBudget(1000)
    val measured = VarkaEmittedClass.measure(emitMulti(roots, 1, 64, tight)._2)
    measured.codeLength.asScala.foreach { case (m, bytes) =>
      if (VarkaEmitBudget.groupOf(m) >= 0) {
        assert(bytes <= 1000, s"$m is $bytes bytes")
      }
    }
    assert(loops(emitMulti(roots, 1, 64, tight)._2) > 1)
    checkMatrix(roots, 1, (0 until 64).map(k => k * 3 - 90).toArray, Seq(1, 17, 129), combos(1),
      options = tight, ctx = "cheap tails under a 1000-byte budget, exact grouping")
  }
}
