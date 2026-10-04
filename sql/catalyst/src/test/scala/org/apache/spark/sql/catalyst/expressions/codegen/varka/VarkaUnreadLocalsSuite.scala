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
import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The cost corpus's emitted classes held to locals that are read (`VarkaUnreadLocals`). A shared
 * slot nothing reads means the body's use count (`Slots.bodyUses`) and the walk that emits the
 * body disagree about how often a node is visited: a count too high costs a `dup` and a store, as
 * it did for dates two calendar nodes reach through one shared prefix before the count learned
 * the walk's rule (`VARKA-223.md` 9.4). Under `elideUnreadLocals` no reference local of any
 * kind goes unread (VARKA-239).
 */
class VarkaUnreadLocalsSuite extends VarkaEmitterTestBase {

  // The corpus of VARKA-223.md 9.1: random shapes of both lanes, wide shapes whose groups
  // load materialized prefixes, and the ladders.
  private lazy val corpus = VarkaEmitCostCorpus.fuzz(1000).asScala.toSeq ++
    VarkaEmitCostCorpus.wide().asScala ++ VarkaEmitCostCorpus.ladders().asScala

  // The shipped options elide the unread locals; the reference form builds them all.
  private val elided = VarkaEmitOptions.DEFAULTS.withLanesOverride(VarkaEmitCostCorpus.LANES)
    .withElideUnreadLocals(true)
  private val built = elided.withElideUnreadLocals(false)

  // Each options value's classes, emitted once and shared by the tests that read them.
  private val emitted = mutable.Map.empty[VarkaEmitOptions, Seq[(String, Array[Byte])]]

  /** Every corpus shape's class under `options`, labelled with the shape's family and index. */
  private def classes(options: VarkaEmitOptions): Seq[(String, Array[Byte])] =
    emitted.getOrElseUpdate(options, corpus.map { s =>
      s"${s.family} ${s.index}" -> VarkaLoopEmitter.emit(
        "org.apache.spark.sql.varka.execution.VarkaUnreadLocals", s.roots, s.numInputs,
        s.numLiterals, null, null, options)
    })

  /** The census of every unread reference local over the corpus under `options`, by kind. */
  private def census(options: VarkaEmitOptions): Map[String, Int] =
    classes(options).flatMap { case (_, bytes) =>
      VarkaUnreadLocalsTrim.census(bytes).asScala.map(_.kind)
    }.groupBy(identity).view.mapValues(_.size).toMap

  test("every shared slot of every loop and epilogue method in the cost corpus is read") {
    // Both bodies of each shape, with and without the switch: the switch moves the slots the
    // segments and the mask took, and must leave the shared ones alone.
    for (options <- Seq(built, elided)) {
      val unread = classes(options).flatMap { case (label, bytes) =>
        VarkaUnreadLocals.unreadSharedSlots(bytes).asScala.map(f => s"$label: $f")
      }
      assert(unread.isEmpty, s"${unread.size} shared slots nothing reads under " +
        s"${options.canonical()}, the first: " + unread.take(5).mkString("; "))
    }
  }

  test("under elideUnreadLocals no loop or epilogue method in the cost corpus stores a " +
      "reference local it never reads") {
    val on = census(elided)
    assert(on.isEmpty, s"unread reference locals under the switch: $on")
    // Without CSE the body counts its uses for the value columns alone (Slots.plan).
    val noCse = census(elided.withCse(false))
    assert(noCse.isEmpty, s"unread reference locals under the switch without CSE: $noCse")
    // The form that builds everything keeps the four kinds VARKA-239 found (VARKA-239.md 2.1)
    // and no other, so a leftover of a new kind added to the reference form is seen too.
    val off = census(built)
    assert(off.keySet.subsetOf(Set("segment: output validity", "vector: prefix reload",
      "segment: input data", "mask: indexInRange")) && off.nonEmpty,
      s"the reference form's unread reference locals: $off")
  }

  test("under elideUnreadLocals a group that loads a materialized prefix loads what each " +
      "calendar tail reads, under every option its reads depend on") {
    // The arms of VarkaChronoLowering.prefixReads that depend on the options - the century
    // without the Julian map, the day of year without the Neri-Schneider month, trunc's
    // recomposing form - matter only where a later group loads a prefix, which the corpus may
    // not reach under those options. Here a year alone in the first group stores the prefix and
    // each tail, alone in the second, loads it. A vector the tail reads and its arm does not
    // name fails verification or answers wrongly; one named and never read is a load the census
    // finds.
    val col = new ColumnRef(0)
    val tails: Seq[(VarkaVectorIR, Int, Array[Int])] = Seq(
      (new Year(col), 1, Array.empty[Int]),
      (new DayOfYear(col), 1, Array.empty[Int]),
      (new WeekOfYear(new ThursdayOf(col)), 1, Array.empty[Int]),
      (new AddMonths(col, new LiteralSlot(0)), 1, Array(7)),
      (new Month(col), 1, Array.empty[Int]),
      (new Quarter(col), 1, Array.empty[Int]),
      (new DayOfMonth(col), 1, Array.empty[Int]),
      (new LastDay(col), 1, Array.empty[Int]),
      (new TruncDate(col, TruncLevel.YEAR), 1, Array.empty[Int]),
      (new TruncDate(col, TruncLevel.QUARTER), 1, Array.empty[Int]),
      (new TruncDate(col, TruncLevel.MONTH), 1, Array.empty[Int]),
      (new TruncDateDynamic(col, new ColumnRef(1)), 2, Array.empty[Int]))
    // The reference form, which builds every prefix vector, so that the second group loads them.
    val split = VarkaEmitOptions.DEFAULTS.withElideUnreadLocals(false)
      .withMaterializeChronoPrefix(true).withGroupBudget(1).withFusedCeiling(1)
    val arms = Seq("the defaults" -> split, "no Julian map" -> split.withJulianMap(false),
      "no Neri-Schneider month" -> split.withNeriSchneiderMonth(false),
      "recomposed trunc" -> split.withTruncDate(VarkaEmitOptions.TruncDateForm.RECOMPOSE))
    // The calendar's boundaries, then days across four centuries; the level column of the
    // dynamic trunc walks the levels a date truncates to (DateTimeUtils' week, month, quarter
    // and year).
    val levels = Array(6, 7, 8, 9)
    def data(c: Int, i: Int): Int =
      if (c == 1) levels(i % levels.length)
      else if (i < calendarBoundaryDays.length) calendarBoundaryDays(i)
      else i * 293 - 150000
    def loads(bytes: Array[Byte]): Int = {
      val vector = "jdk.incubator.vector.IntVector"
      VarkaEmitterTestSupport.invocationCount(bytes, "loopDense1", vector) -
        VarkaEmitterTestSupport.invocationCount(bytes, "loopDense1", vector,
          java.util.List.of("fromMemorySegment"))
    }
    for ((arm, options) <- arms; (tail, numInputs, lits) <- tails) {
      // The producer decomposes the tail's own date - a week of year's is the Thursday of its
      // week - and, as equal roots are one output, the year's own tail takes a month.
      val date = tail match {
        case w: WeekOfYear => w.days()
        case _ => col
      }
      val producer = if (tail.isInstanceOf[Year]) new Month(date) else new Year(date)
      val roots = Seq[VarkaVectorIR](producer, tail)
      val ctx = s"$tail under $arm"
      // The second group loads the prefix: all five vectors when everything is built.
      val all = emitMulti(roots, numInputs, lits.length, options)._2
      assert(loads(all) >= 5, s"$ctx: the second group loads no prefix")
      val bytes = emitMulti(roots, numInputs, lits.length, options.withElideUnreadLocals(true))._2
      val unread = VarkaUnreadLocalsTrim.census(bytes).asScala.map(d => s"${d.method} ${d.kind}")
      assert(unread.isEmpty, s"$ctx: unread reference locals ${unread.mkString(", ")}")
      checkMatrix(roots, numInputs, lits, Seq(1, 1031), combos(numInputs), data = data,
        ctx = ctx, options = options.withElideUnreadLocals(true))
    }
  }
}
