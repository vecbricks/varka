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
 * (`VarkaGroupingBound`), held to the bound `PLAN_TASK_200.md` 2 measured, over the cost model's
 * corpus, shapes whose prefix-sharers are not adjacent, and projections composed of the coverage
 * table's rows. In ops the two are within a fraction of a percent on every family, and that is
 * the bound held here; in loop methods the greedy grouping strands cheap outputs that share
 * nothing, which is what task 200's exact partition is for, and this suite reports the methods
 * until it holds the shipped grouping to them too.
 */
class VarkaGroupingBoundSuite extends VarkaEmitterTestBase {

  private val options = VarkaEmitOptions.DEFAULTS.withLanesOverride(VarkaEmitCostCorpus.LANES)

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

  /** The loop methods of the shape as it ships, after the measurement regroups it. */
  private def shippedLoops(shape: VarkaEmitCostCorpus.Shape): Option[Int] = {
    try {
      val bytes = VarkaLoopEmitter.emitCountingBuilds(
        "org.apache.spark.sql.varka.execution.VarkaGroupingBoundTest", shape.roots,
        shape.numInputs, shape.numLiterals, options, new Array[Int](1))
      val names = VarkaEmittedClass.measure(bytes).codeLength.keySet.asScala
      Some(math.max(names.count(_.startsWith("loopDense")),
        names.count(_.startsWith("loopMasked"))))
    } catch {
      case _: VarkaEmitDeclined => None
    }
  }

  test("the greedy grouping is within half a percent of the best partition's ops on every " +
      "family, and never below it once the prediction closes groups") {
    val shapes = VarkaEmitCostCorpus.fuzz().asScala ++ VarkaEmitCostCorpus.wide().asScala ++
      VarkaEmitCostCorpus.ladders().asScala ++ VarkaGroupingBound.interleaved().asScala ++
      compositions
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
      val loops = mine.map(shippedLoops)
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
    // (PLAN_TASK_200.md 2). The groups are reported above and are the exact grouping's to
    // close; the ops are the bound held here.
    summaries.values.foreach { s =>
      assert(s.savedPercent <= 0.5, s"${s.family}: the best partition saves " +
        f"${s.savedPercent}%.2f%% of the ops, past the half percent PLAN_TASK_200.md 2 measured")
    }
  }
}
