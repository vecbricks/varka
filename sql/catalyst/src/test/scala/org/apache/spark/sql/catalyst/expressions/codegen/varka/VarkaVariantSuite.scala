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

import java.util.{Collections, IdentityHashMap}

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVariants.{Kind, Variant}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * Every small IR tree in eight variants whose answers follow from its own (VARKA-278). The
 * series of [[VarkaSmallTreeSuite]] meets the emitter one tree at a time, as drawn; but much of
 * what the emitter decides depends on a tree's company - which outputs share a subtree and so a
 * slot, how outputs group into methods, which literal is folded and which column read - so a
 * bug there shows only when the same tree is emitted beside something else. Each tree that
 * passes alone is emitted again as each variant of [[VarkaVariants]] and compared with the
 * reference evaluator through [[VarkaVariantCheck]].
 *
 * The size is `-Dvarka.variants.size`, 3 by default, the pull request's; the nightly runs size 4
 * across JVMs with `-Dvarka.series.slice=k/n`, as the series does. Its own property rather than
 * the series', so a configuration that raises the series (the weekly option matrix) does not
 * raise this one with it. Each tree meets the series' batches by rotation, one each, and all
 * four under `-Dvarka.variants.allBatches=true`: the variants are eight emissions a tree, and the
 * series itself already gives every tree up to size 3 all four. An `IfElse` first appears at
 * size 4, so the untaken-arm variant has a test of its own that enumerates size 4's conditional
 * trees, every one on every batch. A failure on the committed known-failures list
 * (`VarkaKnownFailures`) is logged and not counted, as in the series.
 */
class VarkaVariantSuite extends SparkFunSuite with VarkaMatrixTests with VarkaOwnJvm {

  private val size = sys.props.get("varka.variants.size").map(_.toInt).getOrElse(3)
  private val (slice, slices) = sys.props.get("varka.series.slice").map { s =>
    val Array(k, n) = s.split("/").map(_.toInt)
    (k, n)
  }.getOrElse((0, 1))
  private val allBatches = sys.props.get("varka.variants.allBatches").contains("true")

  // ---------------------------------------------------------------------------------------------
  // The cases
  // ---------------------------------------------------------------------------------------------

  /** A series batch in a case's terms: its rows, nulls, columns and literal, and its name. */
  private final class SeriesBatch(val name: String, val length: Int,
      val nulls: Array[Array[Boolean]], val data: Array[Array[Long]], val lits: Array[Long])

  private lazy val intBatches: Seq[SeriesBatch] = VarkaIrSeries.intBatches.asScala.toSeq.map {
    b => new SeriesBatch(b.name, b.batch.length, b.batch.nulls,
      b.batch.data.map(_.map(_.toLong)), b.lits.map(_.toLong))
  }

  private lazy val longBatches: Seq[SeriesBatch] = VarkaIrSeries.longBatches.asScala.toSeq.map {
    b => new SeriesBatch(b.name, b.batch.length, b.batch.nulls, b.batch.data, b.lits)
  }

  private def caseOf(root: VarkaVectorIR, long: Boolean, b: SeriesBatch, label: String,
      options: VarkaEmitOptions): VarkaFuzzCase =
    new VarkaFuzzCase(if (long) LaneType.LONG else LaneType.INT, java.util.List.of(root),
      if (long) VarkaIrSeries.LONG_NUM_INPUTS else VarkaIrSeries.NUM_INPUTS, b.lits, b.length,
      b.nulls, b.data, false, options,
      if (long) -1 else VarkaIrSeries.SMALL_COLUMN, if (long) -1 else VarkaIrSeries.LEVEL_COLUMN,
      s"$label batch=${b.name}")

  /** A lane's series, its batches, and the trees a stranger and a replacement come from. */
  private final class Lane(val long: Boolean) {
    val series: VarkaIrSeries.Series =
      if (long) new VarkaIrSeries.LongSeries else new VarkaIrSeries.IntSeries
    val batches: Seq[SeriesBatch] = if (long) longBatches else intBatches
    val name: String = if (long) "long" else "int"
    /** The roots to size 2, every one of which the series passes: strangers. */
    lazy val strangers: IndexedSeq[VarkaVectorIR] =
      (series.roots(1).asScala ++ series.roots(2).asScala).toIndexedSeq
    /** The value trees to size 2: replacements for an untaken arm. */
    lazy val replacements: Seq[VarkaVectorIR] =
      (1 to 2).flatMap(series.value(_).asScala).map(_.node)

    /** The `index`-th tree's stranger: a different tree from the pool, by a fixed stride. */
    def stranger(root: VarkaVectorIR, index: Int): VarkaVectorIR = {
      val text = VarkaVectorIR.canonical(root)
      (0 until strangers.size).iterator
        .map(k => strangers((index * 7 + 3 + k) % strangers.size))
        .find(s => VarkaVectorIR.canonical(s) != text).get
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Running and counting
  // ---------------------------------------------------------------------------------------------

  /**
   * What a run compared and declined per variant - a declined variant compares no row, so it is
   * not counted as compared - what it skipped, and its first twenty failures.
   */
  private final class Tally {
    val compared = mutable.LinkedHashMap(Kind.values.toSeq.map(_ -> 0): _*)
    val declined = mutable.LinkedHashMap(Kind.values.toSeq.map(_ -> 0): _*)
    var skipped = 0
    var trees = 0
    val first = mutable.ArrayBuffer.empty[String]
    var failed = 0

    def fail(message: String): Unit = {
      failed += 1
      if (first.size < 20) first += message
    }

    def report(what: String): Unit = {
      logInfo(s"$what: $trees trees, $skipped variants past the class-file cap, $failed failed")
      for (k <- Kind.values) {
        logInfo(f"  ${k.label}%-22s compared ${compared(k)}%8d  declined ${declined(k)}%6d")
      }
    }

    def assertNone(what: String): Unit = {
      report(what)
      assert(failed == 0, s"$failed of the $what failed; the first ${first.size}:\n" +
        first.mkString("\n"))
    }
  }

  /** Runs `body`, recording a failure in `tally` unless it is on the known-failures list. */
  private def recording(tally: Tally, label: String)(body: => Unit): Boolean = {
    try {
      body
      true
    } catch {
      case e: VirtualMachineError => throw e
      case e: InterruptedException => throw e
      case t: Throwable =>
        val signature = VarkaFailureSignature.of(t, label)
        if (VarkaKnownFailures.isKnown(VarkaKnownFailures.entries, signature)) {
          logWarning(s"known failure, not counted: $signature")
        } else {
          tally.fail(Option(t.getMessage).getOrElse(t.getClass.getName))
        }
        false
    }
  }

  /** Checks `c` alone and, where it passes, each variant in `kinds` that applies to it. */
  private def checkCase(lane: Lane, c: VarkaFuzzCase, index: Int, tally: Tally,
      kinds: Set[Kind] = Kind.values.toSet): Unit = {
    var plain: VarkaVariantCheck.Outcome = VarkaVariantCheck.Skipped
    val passed = recording(tally, c.label) {
      plain = VarkaVariantCheck.checkPlain(c, VarkaVariantCheck.retryWithoutBudget)
    }
    if (!passed || plain == VarkaVariantCheck.Skipped) return
    // Building the variants runs the reference evaluator and the rewrites' typing, so it is
    // recorded like a check rather than left to abort the lane.
    var variants = Seq.empty[(Kind, Option[Variant])]
    val built = recording(tally, c.label) {
      variants = VarkaVariantCheck.variants(c, lane.stranger(c.roots.get(0), index),
        lane.replacements, index)
    }
    if (!built) return
    for ((kind, variant) <- variants if kinds(kind); v <- variant) {
      // The check's messages start with the variant's own label, which is what the
      // known-failures signature strips.
      recording(tally, VarkaVariantCheck.caseOf(c, v).label) {
        VarkaVariantCheck.check(c, v, VarkaVariantCheck.retryWithoutBudget) match {
          case VarkaVariantCheck.Compared => tally.compared(kind) += 1
          case VarkaVariantCheck.Declined(_) => tally.declined(kind) += 1
          case VarkaVariantCheck.Skipped => tally.skipped += 1
        }
      }
    }
  }


  /** Every root of `lane` up to `upTo`, this JVM's slice, each with its variants. */
  private def runLane(lane: Lane, upTo: Int, options: VarkaEmitOptions,
      kinds: Set[Kind] = Kind.values.toSet): Tally = {
    val tally = new Tally
    var index = 0
    for (sized <- lane.series.upTo(upTo).asScala) {
      val (treeSize, root) = (sized.size, sized.root)
      if (index % slices == slice) {
        tally.trees += 1
        val batches = if (allBatches) lane.batches
          else Seq(lane.batches(index % lane.batches.size))
        val label = s"lane=${lane.name} size=$treeSize index=$index " +
          s"options=${if (options.isDefault) "(defaults)" else options.canonical()}"
        for (b <- batches) checkCase(lane, caseOf(root, lane.long, b, label, options), index,
          tally, kinds)
      }
      index += 1
    }
    tally
  }

  /**
   * The variants every tree to `size` is expected to give at least once. The untaken arm needs
   * an `IfElse`, which size 3 has not; the conditional test below holds it to its count.
   */
  private def expected(upTo: Int): Seq[Kind] =
    Kind.values.toSeq.filter(k => k != Kind.UNTAKEN_ARM || upTo >= 4)

  // ---------------------------------------------------------------------------------------------
  // The series in variants
  // ---------------------------------------------------------------------------------------------

  for (long <- Seq(false, true)) {
    val laneName = if (long) "long" else "int"
    test(s"every $laneName tree to size $size, in each variant, matches the reference " +
        s"evaluator (slice $slice of $slices)") {
      val tally = runLane(new Lane(long), size, VarkaMatrix.base)
      tally.assertNone(s"$laneName trees to size $size and their variants")
      // Counted, not assumed: a variant that silently stopped applying would pass everything.
      // Over the whole series only; a slice may hold no tree a variant applies to.
      if (slices == 1) {
        for (k <- expected(size)) {
          assert(tally.compared(k) > 0, s"${k.label} compared no $laneName tree to size $size")
        }
      }
    }
  }

  test("every conditional tree of size 4, with an untaken arm replaced, matches the reference " +
      "evaluator on every batch") {
    // Size 4 is the first that holds an IfElse; the batches decide which arm is untaken, the
    // all-null batch taking every condition's else arm. Generation is cheap at size 4, and only
    // the conditional trees are emitted, so the pull request runs this whatever its size.
    for (long <- Seq(false, true)) {
      val lane = new Lane(long)
      val tally = new Tally
      var index = 0
      for (root <- lane.series.roots(4).asScala if root.isInstanceOf[IfElse]) {
        tally.trees += 1
        for (b <- lane.batches) {
          checkCase(lane, caseOf(root, long, b, s"lane=${lane.name} size=4 index=$index",
            VarkaMatrix.base), index, tally, Set(Kind.UNTAKEN_ARM))
        }
        index += 1
      }
      tally.assertNone(s"${lane.name} conditional trees of size 4 with an untaken arm replaced")
      assert(tally.compared(Kind.UNTAKEN_ARM) > 0,
        s"no ${lane.name} conditional tree of size 4 had an untaken arm on any batch")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Each variant is what it says
  // ---------------------------------------------------------------------------------------------

  /** Every node object under `roots`, by identity. */
  private def nodes(roots: Seq[VarkaVectorIR]): java.util.Set[VarkaVectorIR] = {
    val out = Collections.newSetFromMap(new IdentityHashMap[VarkaVectorIR, java.lang.Boolean])
    roots.foreach(r => VarkaVariants.walk(r, n => { out.add(n); () }))
    out
  }

  private def canon(v: Variant): Seq[String] =
    v.roots.asScala.toSeq.map(r => VarkaVectorIR.canonical(r))

  test("each variant builds the shape it names") {
    // A variant that degraded into the plain tree would pass every check vacuously, so each one
    // is held to its shape on a hand-written tree: greatest(add_months(c0, lit0), c1), and a
    // conditional over it.
    val inner = new AddMonths(new ColumnRef(0), new LiteralSlot(0))
    val t = new Greatest(inner, new ColumnRef(1))
    val roots = Seq[VarkaVectorIR](t).asJava
    val text = VarkaVectorIR.canonical(t)

    val child = VarkaVariants.besideAChild(roots, 4).get
    assert(child.roots.get(0) eq t)
    assert(child.roots.get(1) eq inner)
    val permuted = VarkaVariants.permuted(roots, 4).get
    assert((permuted.roots.get(0) eq inner) && (permuted.roots.get(1) eq t))

    val duplicated = VarkaVariants.duplicated(roots, 4)
    assert((duplicated.roots.get(0) eq t) && (duplicated.roots.get(1) eq t))

    val copied = VarkaVariants.copied(roots, 4)
    assert(copied.roots.get(1) == t && !(copied.roots.get(1) eq t))
    val shared = nodes(Seq(t))
    shared.retainAll(nodes(Seq(copied.roots.get(1))))
    assert(shared.isEmpty, s"the copy shares ${shared.size} node objects with the original")

    val stranger = VarkaVariants.besideAStranger(roots, 4, new IntArith(IntOp.ADD, Overflow.WRAP,
      new ColumnRef(0), new LiteralSlot(0)))
    assert(stranger.numInputs == 8)
    assert(canon(stranger)(1) == VarkaVectorIR.canonical(new IntArith(IntOp.ADD, Overflow.WRAP,
      new ColumnRef(4), new LiteralSlot(0))))
    assert(stranger.columns.asScala.drop(4) == stranger.columns.asScala.take(4))

    val literal = VarkaVariants.literalToColumn(roots, 4).get
    assert(literal.numInputs == 5)
    assert(literal.columns.get(4) == new VarkaVariants.FromLiteral(0))
    val reads = nodes(literal.roots.asScala.toSeq).asScala
    assert(!reads.exists(_.isInstanceOf[LiteralSlot]), "a literal slot survived")
    assert(reads.exists { case c: ColumnRef => c.ordinal == 4; case _ => false })
    // The column's sibling keeps its identity: only the path to the literal is rebuilt.
    assert(literal.roots.get(0).asInstanceOf[Greatest].right() eq t.right())
    assert(VarkaVariants.literalToColumn(Seq[VarkaVectorIR](new ColumnRef(0)).asJava, 4).isEmpty)

    val renumbered = VarkaVariants.columnsRenumbered(roots, 4).get
    assert(canon(renumbered).head == VarkaVectorIR.canonical(new Greatest(
      new AddMonths(new ColumnRef(3), new LiteralSlot(0)), new ColumnRef(2))))
    assert(renumbered.columns.asScala.map {
      case f: VarkaVariants.FromColumn => f.ordinal
      case other => fail(s"not a column: $other")
    } == Seq(3, 2, 1, 0))

    val cond = new IsNotNull(new ColumnRef(1))
    val f = new IfElse(cond, t, new ColumnRef(0))
    val pool = Seq[VarkaVectorIR](t, new ColumnRef(1)).asJava
    def armed(taken: VarkaVariants.Taken): java.util.Optional[Variant] =
      VarkaVariants.untakenArm(Seq[VarkaVectorIR](f).asJava, 4, _ => taken, pool, 0)
    val elseOnly = armed(VarkaVariants.Taken.ELSE_ONLY).get.roots.get(0).asInstanceOf[IfElse]
    assert((elseOnly.cond() eq cond) && (elseOnly.elseNode() eq f.elseNode()))
    assert(VarkaVectorIR.canonical(elseOnly.thenNode()) != text, "the untaken arm was kept")
    val thenOnly = armed(VarkaVariants.Taken.THEN_ONLY).get.roots.get(0).asInstanceOf[IfElse]
    assert((thenOnly.thenNode() eq t) && thenOnly.elseNode() != f.elseNode())
    assert(armed(VarkaVariants.Taken.BOTH).isEmpty)
  }

  test("an untaken arm takes the first replacement that fits, else the first") {
    // VARKA-323: a replacement can grow the tree past what the emitter's form without a budget
    // holds. With a predicate, the pool is scanned from each next start until a variant fits.
    val big = new Greatest(new AddMonths(new ColumnRef(0), new LiteralSlot(0)), new ColumnRef(1))
    val small = new ColumnRef(1)
    val f = new IfElse(new IsNotNull(new ColumnRef(1)), new ColumnRef(2), new ColumnRef(0))
    val pool = Seq[VarkaVectorIR](big, small).asJava
    def thenOf(fits: Variant => Boolean): String = VarkaVectorIR.canonical(
      VarkaVariants.untakenArm(Seq[VarkaVectorIR](f).asJava, 4, _ => VarkaVariants.Taken.ELSE_ONLY,
        pool, 0, (v: Variant) => fits(v)).get.roots.get(0).asInstanceOf[IfElse].thenNode())
    def isSmall(v: Variant): Boolean =
      v.roots.get(0).asInstanceOf[IfElse].thenNode().isInstanceOf[ColumnRef]
    assert(thenOf(_ => true) == VarkaVectorIR.canonical(big), "the first replacement fits")
    assert(thenOf(isSmall) == VarkaVectorIR.canonical(small), "the one that fits is taken")
    assert(thenOf(_ => false) == VarkaVectorIR.canonical(big), "none fits: the first")
  }

  test("an untaken arm is never replaced by an output, and no pool holds one") {
    // Catches: a decimal or boolean output placed under an arm, which the emitter refuses below
    // a root. Every node is on the long lane, the outputs' lane, so only the pool filter keeps
    // them out; no fuzzer draw holds a boolean output, so this is where that half is checked.
    def long(ordinal: Int): VarkaVectorIR = new ColumnRef(ordinal, LaneType.LONG)
    val decimalOut = new WidenDecimal(long(3))
    val boolOut = new BoolOutput(new Compare(CompareOp.LT, long(0), long(1)))
    val f = new IfElse(new Compare(CompareOp.GT, long(1), long(0)), long(2), long(0))
    def thenOf(pool: Seq[VarkaVectorIR], rotation: Int): Option[String] =
      VarkaVariants.untakenArm(Seq[VarkaVectorIR](f).asJava, 5, _ => VarkaVariants.Taken.ELSE_ONLY,
        pool.asJava, rotation).toScala
        .map(v => VarkaVectorIR.canonical(v.roots.get(0).asInstanceOf[IfElse].thenNode()))
    assert(thenOf(Seq(decimalOut, boolOut), 0).isEmpty, "an output replaced the arm")
    for (rotation <- 0 until 3) {
      assert(thenOf(Seq(decimalOut, boolOut, long(4)), rotation) ===
        Some(VarkaVectorIR.canonical(long(4))), s"rotation $rotation")
    }
    val pool = VarkaVariants.valueSubtrees(Seq[VarkaVectorIR](decimalOut, boolOut).asJava).asScala
    assert(!pool.exists(n => n.isInstanceOf[WidenDecimal] || n.isInstanceOf[BoolOutput]))
    assert(pool.map(VarkaVectorIR.canonical).toSet ===
      Seq(long(3), long(0), long(1)).map(VarkaVectorIR.canonical).toSet)
  }

  test("a decline by a guard the variant adds is allowed, and by any other guard fails") {
    // add_months over a column count carries the month-count guard, over a literal count none.
    // So moving the literal into a column adds the guard, which is what lets that variant
    // decline, and then only with that guard's bit.
    val t = new AddMonths(new ColumnRef(0), new LiteralSlot(0))
    val moved = VarkaVariants.literalToColumn(Seq[VarkaVectorIR](t).asJava, 4).get
    assert(VarkaVariants.addedRefusalBits(Seq[VarkaVectorIR](t).asJava, 4, moved.roots, 5, 1,
      VarkaMatrix.base) == VarkaFusedKernel.STATUS_MONTH_COUNT)
    // A guard the original already had on the rewritten path is not one the variant adds,
    // though the rewrite rebuilt its node: the range guard over a sum whose literal moved.
    val ranged = Seq[VarkaVectorIR](new GuardedRange(new IntArith(IntOp.ADD, Overflow.WRAP,
      new ColumnRef(0), new LiteralSlot(0)), -10000000L, 10000000L)).asJava
    assert(VarkaVariants.addedRefusalBits(ranged, 4,
      VarkaVariants.literalToColumn(ranged, 4).get.roots, 5, 1, VarkaMatrix.base) == 0,
      "the range guard above a moved literal counted as added")
    // A stranger's own guards are added.
    val guarded = new AddMonths(new ColumnRef(0), new ColumnRef(2))
    assert(VarkaVariants.addedRefusalBits(Seq[VarkaVectorIR](t).asJava, 4,
      VarkaVariants.besideAStranger(Seq[VarkaVectorIR](t).asJava, 4, guarded).roots, 8, 1,
      VarkaMatrix.base) == VarkaFusedKernel.STATUS_MONTH_COUNT)

    // A literal one past the month-count range, moved into a column: the kernel declines with
    // the month-count bit, which the variant brought, so the check accepts the decline.
    val far = VarkaChrono.MONTH_ARITH_MAX_MONTHS.toLong + 1
    val c = caseOf(t, long = false, intBatches(1), "planted", VarkaMatrix.base)
      .withLits(Array(far))
    assert(VarkaVariantCheck.check(c, moved, VarkaVariantCheck.retryWithoutBudget) ==
      VarkaVariantCheck.Declined(VarkaFusedKernel.STATUS_MONTH_COUNT))
    // The same guard firing in a variant that may not decline fails it: the moved case's
    // columns renumbered put the far count in column 0.
    val movedCase = VarkaVariantCheck.caseOf(c, moved)
    val renumbered = VarkaVariants.columnsRenumbered(moved.roots, 5).get
    val e = intercept[Throwable] {
      VarkaVariantCheck.check(movedCase, renumbered, VarkaVariantCheck.retryWithoutBudget)
    }
    assert(e.getMessage.contains("declined the batch"), e.getMessage)
  }

  // ---------------------------------------------------------------------------------------------
  // What the variants catch
  // ---------------------------------------------------------------------------------------------

  test("under the planted liveness bug, a stranger and a literal in a column fail trees the " +
      "plain series passes") {
    // The admission check (VARKA-278.md 2): under `misdescribeWordLiveness` the plain series
    // fails 2,253 of the 3,203 trees to size 3, and these two variants fail 950 of the trees it
    // passes. A planted bug that shows only when an output keeps company is what the variants
    // are for, so each of the two must keep catching one. The walk stops once both have.
    // The bug needs the bitmap validity pass on, whatever the matrix's configuration, and only
    // the liveness invariant's own refusal counts as a catch: `VarkaSmallTreeSuite`'s planted
    // test says why (VARKA-269.md 9).
    val planted = VarkaMatrix.base.withValidityByBitmap(true).withMisdescribeWordLiveness(true)
    val lane = new Lane(false)
    val caught = mutable.LinkedHashMap(Kind.BESIDE_A_STRANGER -> 0, Kind.LITERAL_TO_COLUMN -> 0)
    var index = 0
    val trees = lane.series.upTo(3)
    while (trees.hasNext && caught.values.exists(_ == 0)) {
      val sized = trees.next()
      val (treeSize, root) = (sized.size, sized.root)
      val c = caseOf(root, long = false, lane.batches(index % lane.batches.size),
        s"planted size=$treeSize index=$index", planted)
      val plain = scala.util.Try(VarkaVariantCheck.checkPlain(c,
        VarkaVariantCheck.retryWithoutBudget))
      if (plain.isSuccess) {
        for ((kind, variant) <- VarkaVariantCheck.variants(c, lane.stranger(root, index),
            lane.replacements, index) if caught.contains(kind); v <- variant) {
          val failed = scala.util.Try(
            VarkaVariantCheck.check(c, v, VarkaVariantCheck.retryWithoutBudget)).failed.toOption
          if (failed.exists(t => VarkaKernelChecks.isWordLivenessRefusal(t))) caught(kind) += 1
        }
      }
      index += 1
    }
    for ((kind, n) <- caught) {
      assert(n > 0, s"${kind.label} caught no tree to size 3 that the plain series passes " +
        "under misdescribeWordLiveness")
    }
  }
}
