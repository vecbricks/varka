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

import java.util.concurrent.atomic.AtomicLong

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The shapes the emit cost model (`VarkaEmitCost`) is built and audited on, and the one way they
 * are measured: each shape emitted with its first grouping left standing, and each group's four
 * methods read off the class beside the features the model counts for that group.
 *
 * Shared by `VarkaEmitCostSuite`, which fits the regression on the even-numbered shapes, and
 * `VarkaEmitCostAuditSuite`, which scores both models on the odd-numbered ones; a copy of the
 * draw in each would let the two silently disagree on which shapes were held out.
 */
object VarkaEmitCostCorpus {

  /** The width every table is read at: the 512-bit int species, the published machine's. */
  val Lanes = 16

  /**
   * `base` with nothing left to regroup a shape after it is built: the byte budget at the
   * class-file cap, so a group's methods are its own whatever their size, and the call-site
   * budget off. Every other option is `base`'s, so the grouping is the one `base` forms first.
   */
  def measuring(base: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): VarkaEmitOptions =
    base.withLanesOverride(Lanes).withMethodByteBudget(VarkaEmitBudget.METHOD_CODE_CAP)
      .withCallSiteBudget(0)

  /** The same options with every output in one group, for the register's probes. */
  val oneGroup: VarkaEmitOptions =
    measuring().withGroupBudget(1 << 20).withFusedCeiling(1 << 20)

  /**
   * One group of one emitted shape: its output indices, the feature counts the model reads, and
   * the eight measured quantities in `VarkaEmitCost.METHODS` order, bytes then call sites, NaN
   * for a method the class does not have (a kernel that nulls a valid input has no dense side).
   */
  case class Group(outputs: Seq[Int], counts: Map[String, Int], measured: Array[Double])

  private val counter = new AtomicLong

  /**
   * Emits `roots` under `options` and returns each group of the grouping the emitter formed, or
   * None when the emitter declines the shape or refuses it. The grouping is read from the same
   * pass the emitter runs, so the groups line up with the method names by index.
   */
  def measure(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int,
      options: VarkaEmitOptions): Option[Seq[Group]] = {
    val name = s"org.apache.spark.sql.varka.execution.VarkaEmitCost${counter.incrementAndGet()}"
    val bytes = try {
      VarkaLoopEmitter.emit(name, roots.asJava, numInputs, numLiterals, null, null, options)
    } catch {
      case _: VarkaEmitDeclined | _: IllegalArgumentException => return None
    }
    val outputs = roots.asJava
    val grouping = VarkaLoopEmitter.groupsForTest(outputs, options)
    val groups = grouping.asScala.map(_.asScala.toSeq.map(_.toInt)).toSeq
    val tallies = VarkaEmitCost.tallies(outputs, grouping, options).asScala
    val emitted = VarkaEmittedClass.measure(bytes)
    val methods = VarkaEmitCost.METHODS.asScala.toSeq
    Some(groups.indices.map { g =>
      def read(m: java.util.Map[String, Integer], method: String): Double =
        Option(m.get(s"$method$g")).map(_.toDouble).getOrElse(Double.NaN)
      val measured = methods.map(read(emitted.codeLength, _)) ++
        methods.map(read(emitted.vectorCallSites, _))
      Group(groups(g), tallies(g).counts().asScala.map { case (k, v) => k -> v.toInt }.toMap,
        measured.toArray)
    }.toSeq)
  }

  /** A shape of the corpus, named so a row of the audit can say where it came from. */
  case class Shape(family: String, index: Int, roots: Seq[VarkaVectorIR], numInputs: Int,
      numLiterals: Int)

  /** How many of each fuzz sequence the corpus takes: the first shapes of the oracle's. */
  val FuzzShapes = 2000
  /** How many wide shapes of each lane the corpus takes. */
  val WideShapes = 200
  /** The wide sequences' seeds, apart from the oracle's so they move nothing committed. */
  val WideSeed = 20260930L
  val WideLongSeed = 20260931L

  /**
   * The size ladder's entry, `greatest(greatest(add_months(d, k), date_add(d, k)), last_day(d))`
   * over one date: four calendar nodes sharing a prefix, the entry the emission benchmark widens.
   */
  def ladderEntry(k: Int): VarkaVectorIR = {
    val col = new ColumnRef(0)
    new Greatest(new Greatest(new AddMonths(col, new LiteralSlot(k)),
      new AddDays(col, new LiteralSlot(k))), new LastDay(col))
  }

  /** `make_date(year(d), month(d), k)`, the ladder that first crossed `HugeMethodLimit`. */
  def makeDateEntry(k: Int): VarkaVectorIR = {
    val col = new ColumnRef(0)
    new MakeDate(new Year(col), new Month(col), new LiteralSlot(k), true)
  }

  /** `year(d) + k`, the cheap tail the call-site budget was read on. */
  def tailEntry(k: Int): VarkaVectorIR =
    new IntArith(IntOp.ADD, Overflow.WRAP, new Year(new ColumnRef(0)), new LiteralSlot(k))

  /** The ladders at the heights the admission check measured, each one shape. */
  def ladders: Seq[Shape] =
    Seq(16, 54, 100, 200, 400).map(n => Shape("size ladder", n, (0 until n).map(ladderEntry), 1,
      n)) ++
    Seq(12, 60).map(n => Shape("make_date ladder", n, (0 until n).map(makeDateEntry), 1, n)) ++
    Seq(22, 64).map(n => Shape("cheap tails", n, (0 until n).map(tailEntry), 1, n))

  /** The fuzz sequences' first shapes, both lanes. */
  def fuzz: Seq[Shape] =
    (0 until FuzzShapes).map { k =>
      val d = VarkaIrGrammar.drawShape(VarkaIrGrammar.shapeRandom(VarkaIrGrammar.fuzzSeed, k))
      Shape("fuzz int", k, d.roots, d.numInputs, d.numLiterals)
    } ++ (0 until FuzzShapes).map { k =>
      val d = VarkaIrGrammar.drawLongShape(
        VarkaIrGrammar.shapeRandom(VarkaIrGrammar.longFuzzSeed, k))
      Shape("fuzz long", k, d.roots, d.numInputs, d.numLiterals)
    }

  /** The wide draws, both lanes. */
  def wide: Seq[Shape] =
    (0 until WideShapes).map { k =>
      val d = VarkaIrGrammar.drawWideShape(VarkaIrGrammar.shapeRandom(WideSeed, k))
      Shape("wide int", k, d.roots, d.numInputs, d.numLiterals)
    } ++ (0 until WideShapes).map { k =>
      val d = VarkaIrGrammar.drawWideLongShape(VarkaIrGrammar.shapeRandom(WideLongSeed, k))
      Shape("wide long", k, d.roots, d.numInputs, d.numLiterals)
    }

  /**
   * The groupings each shape is measured under: the default grouping, and one whose groups are
   * as wide as the fused ceiling allows, which is what brings the fuzz and wide shapes' methods
   * up to the byte budget.
   */
  val arms: Seq[(String, VarkaEmitOptions)] = Seq(
    "default groups" -> measuring(),
    "groups to 400" -> measuring().withGroupBudget(VarkaEmitBudget.FUSED_CEILING))
}
