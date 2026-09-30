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

import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitCostCorpus._

/**
 * How well the emit cost model predicts, pinned in `sql/varka/emit_cost_audit.json`: both
 * models against the classes the emitter builds, method by method, over shapes neither was
 * derived from, and what the grouping switch the model drives does to the number of builds and
 * methods. It is a count of bytes and call sites rather than a timing, so it is the same on any
 * machine with the same JDK, and every claim about the model's accuracy quotes it.
 *
 * Regenerate with `VARKA_COST_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmitCostAuditSuite'`
 * after the tables (`VarkaEmitCostSuite`) have been regenerated and compiled.
 */
class VarkaEmitCostAuditSuite extends VarkaEmitterTestBase {

  private val path = getWorkspaceFilePath("sql", "varka", "emit_cost_audit.json")

  /** The shapes the audit scores: the odd-numbered fuzz and wide shapes, and every ladder. */
  private def heldOut: Seq[Shape] = (fuzz ++ wide).filter(_.index % 2 == 1) ++ ladders

  /** One method of one group: the measured and both predicted quantities. */
  private case class Point(family: String, method: Int, sites: Boolean, measured: Double,
      measuredBytes: Double, predicted: Map[VarkaEmitCost.Model, Double])

  private def points(): Seq[Point] =
    for {
      shape <- heldOut
      (_, options) <- arms
      group <- measure(shape.roots, shape.numInputs, shape.numLiterals, options).getOrElse(Nil)
      predictions = VarkaEmitCost.Model.values.toSeq.map { m =>
        m -> VarkaEmitCost.predict(m, group.counts.map { case (k, v) => k -> Int.box(v) }.asJava)
      }.toMap
      q <- 0 until VarkaEmitCost.QUANTITIES
      if !group.measured(q).isNaN
    } yield Point(shape.family, q % 4, q >= 4, group.measured(q), group.measured(q % 4),
      predictions.map { case (m, p) => m -> p(q) })

  /** The size bands the errors are reported in, by the method's measured bytes. */
  private val bands: Seq[(String, Double => Boolean)] = Seq(
    "under 500 bytes" -> (_ < 500),
    "500 to 1999 bytes" -> (b => b >= 500 && b < 2000),
    "2000 to 7999 bytes" -> (b => b >= 2000 && b < 8000),
    "8000 bytes and over" -> (_ >= 8000),
    "2000 bytes and over" -> (_ >= 2000))

  private def ordered(pairs: (String, Any)*): java.util.LinkedHashMap[String, Any] = {
    val map = new java.util.LinkedHashMap[String, Any]()
    pairs.foreach { case (k, v) => map.put(k, v) }
    map
  }

  private def round(v: Double): Double = math.round(v * 10) / 10.0

  /** The error distribution of `model` over `ps`, relative to what was measured. */
  private def stats(ps: Seq[Point], model: VarkaEmitCost.Model): java.util.Map[String, Any] = {
    val scored = ps.filter(_.measured > 0)
    if (scored.isEmpty) return ordered("methods" -> 0)
    val errors = scored.map(p => 100.0 * math.abs(p.predicted(model) - p.measured) / p.measured)
      .sorted
    def pct(q: Double): Double = round(errors(math.min(errors.size - 1, (q * errors.size).toInt)))
    ordered(
      "methods" -> scored.size,
      "median error %" -> pct(0.5),
      "90th percentile %" -> pct(0.9),
      "99th percentile %" -> pct(0.99),
      "worst %" -> round(errors.last),
      "under-predicted %" -> round(
        100.0 * scored.count(p => p.predicted(model) < p.measured) / scored.size),
      "within 10% share" -> round(100.0 * errors.count(_ <= 10.0) / errors.size))
  }

  private def accuracy(all: Seq[Point]): java.util.Map[String, Any] = {
    val byModel = VarkaEmitCost.Model.values.toSeq.map { model =>
      model.toString -> ordered(Seq(false, true).map { sites =>
        (if (sites) "call sites" else "bytes") -> ordered(bands.map { case (band, in) =>
          band -> stats(all.filter(p => p.sites == sites && in(p.measuredBytes)), model)
        }: _*)
      }: _*)
    }
    def atBudget(sites: Boolean) = VarkaEmitCost.Model.values.toSeq.map { model =>
      model.toString -> ordered(all.map(_.family).distinct.map { family =>
        family -> stats(all.filter(p => p.sites == sites && p.family == family &&
          p.measuredBytes >= 2000), model)
      }: _*)
    }
    ordered(
      "by model, quantity and size band" -> ordered(byModel: _*),
      "bytes of methods of 2000 bytes and over, by shape family" ->
        ordered(atBudget(sites = false): _*),
      "call sites of methods of 2000 bytes and over, by shape family" ->
        ordered(atBudget(sites = true): _*))
  }

  /**
   * One shape at the shipped options, with and without `predictGrouping`: whether it emitted
   * in one build - its first grouping is its last, since a regroup only ever adds groups - and
   * how many loop methods it has. None when it declines.
   */
  private def emitted(shape: Shape, predict: Boolean): Option[(Boolean, Int)] = {
    val options = VarkaEmitOptions.DEFAULTS.withLanesOverride(Lanes).withPredictGrouping(predict)
    try {
      val bytes = VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaEmitCostAudit",
        shape.roots.asJava, shape.numInputs, shape.numLiterals, null, null, options)
      val names = VarkaEmittedClass.measure(bytes).codeLength.keySet.asScala
      val loops = math.max(names.count(_.startsWith("loopDense")),
        names.count(_.startsWith("loopMasked")))
      val first = VarkaLoopEmitter.groupsForTest(shape.roots.asJava, options).size
      Some((loops == first, loops))
    } catch {
      case _: VarkaEmitDeclined => None
    }
  }

  private def grouping(): java.util.Map[String, Any] = {
    val rows = heldOut.map(s => (s, emitted(s, predict = false), emitted(s, predict = true)))
    val families = rows.map(_._1.family).distinct.map { family =>
      val mine = rows.filter(_._1.family == family)
      val both = mine.collect { case (s, Some(off), Some(on)) => (s, off, on) }
      val gained = both.filter { case (_, off, on) => on._2 > off._2 }
      family -> ordered(
        "shapes" -> mine.size,
        "emitted in one build, weights" -> both.count(_._2._1),
        "emitted in one build, predicted" -> both.count(_._3._1),
        "loop methods, weights" -> both.map(_._2._2).sum,
        "loop methods, predicted" -> both.map(_._3._2).sum,
        "shapes with more loop methods, predicted" ->
          gained.map { case (s, off, on) => s"${s.index}: ${off._2} -> ${on._2}" }.asJava,
        "declined, weights" -> mine.count(_._2.isEmpty),
        "declined, predicted" -> mine.count(_._3.isEmpty))
    }
    ordered(families: _*)
  }

  private def render(): String = {
    val doc = ordered(
      "generated_by" -> ("VarkaEmitCostAuditSuite; regenerate with VARKA_COST_REGEN=true " +
        "build/sbt 'catalyst/testOnly *VarkaEmitCostAuditSuite'"),
      "description" -> ("The emit cost model's two tables (VarkaEmitCostTable) scored against " +
        "the classes the emitter builds, at sixteen int lanes, over the odd-numbered fuzz and " +
        "wide shapes and every ladder - none of which either table was derived from - each " +
        "emitted under its default grouping and under groups up to the fused ceiling. An error " +
        "is |predicted - measured| / measured for one method of one group. Then each shape at " +
        "the shipped options with predictGrouping off and on: whether its first grouping was " +
        "its last, and its loop methods. See PLAN_TASK_199.md."),
      "jdk" -> System.getProperty("java.specification.version"),
      "chosen model" -> VarkaEmitCost.CHOSEN.toString,
      "accuracy" -> accuracy(points()),
      "grouping" -> grouping())
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n"
  }

  test("sql/varka/emit_cost_audit.json is what the models predict against what is emitted") {
    val rendered = render()
    if (sys.env.get("VARKA_COST_REGEN").contains("true")) {
      Files.write(path, rendered.getBytes(StandardCharsets.UTF_8))
      logInfo(s"regenerated $path")
    } else {
      assert(Files.exists(path), s"$path is missing; generate it with\n" +
        "  VARKA_COST_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmitCostAuditSuite'")
      val committed = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      assert(committed === rendered, "sql/varka/emit_cost_audit.json differs from what the " +
        "models and the emitter give now; regenerate it and requote PLAN_TASK_199.md from it")
    }
  }
}
