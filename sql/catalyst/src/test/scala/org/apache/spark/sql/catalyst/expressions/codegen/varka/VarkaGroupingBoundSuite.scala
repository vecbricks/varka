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
import scala.util.Random

import org.apache.spark.sql.catalyst.expressions.Alias
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler

/**
 * How far the greedy output grouping is from the best partition of the outputs in their order
 * (`VarkaGroupingBound`), held to the bound `VARKA-200.md` 2 measured, over the cost model's
 * corpus, shapes whose prefix-sharers are not adjacent, and projections composed of the coverage
 * table's rows. In ops the two are within a fraction of a percent on every family, and that is
 * the bound held here; in loop methods the greedy grouping strands cheap outputs that share
 * nothing, which is what the emitter's exact grouping (`VarkaEmitOptions.exactGrouping`) is for,
 * and the suite holds that grouping to the best partition shape by shape.
 */
class VarkaGroupingBoundSuite extends VarkaEmitterTestBase {

  // The greedy walk, the baseline every arm below is held against, without the plan: its margins
  // close groups earlier than the rule the bound admits runs by (VARKA-236), and the bound is
  // about the rule.
  private val options = VarkaEmitOptions.DEFAULTS.withLanesOverride(VarkaEmitCostCorpus.LANES)
    .withExactGrouping(false).withPlanSize(false)

  private lazy val table =
    VarkaCoverageRows.read(getWorkspaceFilePath("sql", "varka", "coverage.json"))

  /**
   * Projections of twenty to two hundred entries drawn from the coverage table's projection
   * rows, through the compiler: entries of every family the compiler admits, over the table's
   * columns, with the sharing a real projection has rather than a ladder's or a random tree's.
   * The compiler fuses one lane and declines the rest; the kernel it makes is the shape.
   */
  private def compositions: Seq[VarkaEmitCostCorpus.Shape] = {
    val rnd = new Random(20261001L)
    (0 until 60).flatMap { i =>
      val picked = Seq.fill(20 + rnd.nextInt(181))(
        table.projections(rnd.nextInt(table.projections.size)))
      val list = picked.zipWithIndex.map { case (row, k) =>
        Alias(VarkaCoverageRows.resolve(row.executable, table.columns), s"c$k")()
      }
      VarkaExpressionCompiler.compilePartial(list, table.columns, options).map { partial =>
        val fused = partial.fused
        new VarkaEmitCostCorpus.Shape("coverage compositions", i, fused.outputs.asJava,
          fused.inputOrdinals.size, math.max(fused.literals.size, fused.longLiterals.size))
      }
    }
  }

  /** Every shape the check reads: the cost model's corpus and the two families it lacked. */
  private lazy val shapes: Seq[VarkaEmitCostCorpus.Shape] =
    VarkaEmitCostCorpus.fuzz().asScala.toSeq ++ VarkaEmitCostCorpus.wide().asScala ++
      VarkaEmitCostCorpus.ladders().asScala ++ VarkaGroupingBound.interleaved().asScala ++
      compositions

  /**
   * The loop methods of the shape as it ships, after the measurement regroups it, and how many
   * times the emitter dropped a grouping switch to make it; None where it declines.
   */
  private def shipped(shape: VarkaEmitCostCorpus.Shape,
      emitOptions: VarkaEmitOptions): Option[(Int, Int)] = {
    try {
      val builds = new Array[Int](2)
      val bytes = VarkaLoopEmitter.emitCountingBuilds(
        "org.apache.spark.sql.varka.execution.VarkaGroupingBoundTest", shape.roots,
        shape.numInputs, shape.numLiterals, emitOptions, builds)
      val names = VarkaEmittedClass.measure(bytes).codeLength.keySet.asScala
      Some((math.max(names.count(_.startsWith("loopDense")),
        names.count(_.startsWith("loopMasked"))), builds(1)))
    } catch {
      case _: VarkaEmitDeclined => None
    }
  }

  /** The loop methods of the shape as it ships, after the measurement regroups it. */
  private def shippedLoops(shape: VarkaEmitCostCorpus.Shape): Option[Int] =
    shipped(shape, options).map(_._1)

  test("the greedy grouping is within half a percent of the best partition's ops on every " +
      "family, and never below it once the prediction closes groups") {
    val accounts = shapes.map { s =>
      VarkaGroupingBound.account(s.family, s.index, s.roots, options)
    }
    // By construction: the best partition is searched over the runs the predicting greedy
    // rule admits, so it is never worse than that grouping. A shape where it is means the
    // test's admission and the grouping's have parted.
    accounts.foreach { a =>
      assert(a.best.ops <= a.predicted.ops && (a.best.ops < a.predicted.ops ||
        a.best.groups <= a.predicted.groups),
        s"${a.family} ${a.index}: the best partition ${a.best} is worse than the predicting " +
          s"greedy grouping ${a.predicted}")
    }
    val summaries = VarkaGroupingBound.summarize(accounts.asJava).asScala
    val shipped = shapes.groupBy(_.family).map { case (family, mine) =>
      val loops = mine.map(shippedLoops(_))
      family -> (loops.flatten.sum, loops.count(_.isEmpty))
    }
    VarkaGroupingBound.table(summaries.asJava).linesIterator.foreach(info(_))
    shipped.toSeq.sortBy(_._1).foreach { case (family, (loops, declined)) =>
      info(s"$family: $loops loop methods as shipped, after the regroup, $declined declined")
    }
    // In ops - the per-row work, which is what the weights price - the greedy grouping is at
    // the optimum on every family to within a fraction of a percent. In groups it is not: on
    // outputs that mix prefix-sharers with cheap outputs sharing nothing it strands the cheap
    // ones in groups of their own, up to twice the loop methods at the same ops
    // (VARKA-200.md 2). The groups are reported above and are the exact grouping's to
    // close; the ops are the bound held here.
    summaries.values.foreach { s =>
      assert(s.savedPercent <= 0.5, s"${s.family}: the best partition saves " +
        f"${s.savedPercent}%.2f%% of the ops, past the half percent VARKA-200.md 2 measured")
    }
  }

  test("the exact grouping is the best partition of every shape, with the prediction and " +
      "without, forms only groups the rule admits, and keeps the greedy partition wherever " +
      "that is already the best") {
    // The best partition here is this suite's own dynamic program over `runsForTest`; the exact
    // grouping is the emitter's, under the same rule. On every shape they must agree in ops and
    // groups, every group the emitter forms must be a run the rule admits - one the greedy walk
    // could have formed from its first output - and where the greedy walk is already at the
    // best the emitter must keep its partition as it is, so the switch moves only the shapes it
    // improves (VARKA-200.md 3.1 and 4).
    for (predict <- Seq(false, true); shape <- shapes) {
      val opts = options.withPredictGrouping(predict)
      val runs = VarkaGroupingBound.runs(shape.roots, opts)
      val best = VarkaGroupingBound.optimal(runs)
      val greedyGrouping = VarkaLoopEmitter.groupsForTest(shape.roots, opts)
      val exactGrouping =
        VarkaLoopEmitter.groupsForTest(shape.roots, opts.withExactGrouping(true))
      val greedy = VarkaGroupingBound.of(runs, greedyGrouping)
      val exact = VarkaGroupingBound.of(runs, exactGrouping)
      val where = s"${shape.family} ${shape.index}, prediction $predict"
      Option(VarkaGroupingBound.unformable(runs, exactGrouping))
        .foreach(why => fail(s"$where: $why"))
      assert(exact.ops == best.ops && exact.groups == best.groups,
        s"$where: the exact grouping has ${exact.ops} ops in ${exact.groups} groups, the best " +
          s"partition ${best.ops} in ${best.groups}")
      if (greedy.ops == best.ops && greedy.groups == best.groups) {
        assert(exactGrouping == greedyGrouping, s"$where: the greedy partition is already the " +
          s"best and the exact grouping moved it: $greedyGrouping to $exactGrouping")
      }
    }
  }

  test("under the exact grouping no shape declines, and none falls back to the greedy grouping") {
    // A class the exact grouping would make decline is built again greedily, so a decline alone
    // cannot show the switch costing a kernel: the fallback hides it. What the test holds is
    // that the fallback never runs for the exact grouping - a shape drops no more switches under
    // it than under the greedy grouping - and it reports the loop methods each emits as shipped,
    // after the measurement's regroup.
    val exactOptions = options.withExactGrouping(true)
    val totals = scala.collection.mutable.LinkedHashMap[String, (Int, Int)]()
    for (shape <- shapes) {
      val greedy = shipped(shape, options)
      val exact = shipped(shape, exactOptions)
      val name = s"${shape.family} ${shape.index}"
      assert(greedy.isEmpty || exact.nonEmpty,
        s"$name emits under the greedy grouping and declines under the exact one")
      assert(exact.map(_._2) === greedy.map(_._2),
        s"$name falls back from the exact grouping to make a class that fits")
      val (g, e) = totals.getOrElse(shape.family, (0, 0))
      totals(shape.family) = (g + greedy.map(_._1).getOrElse(0), e + exact.map(_._1).getOrElse(0))
    }
    totals.foreach { case (family, (g, e)) =>
      info(s"$family: $g loop methods as shipped greedily, $e under the exact grouping")
    }
  }
}
