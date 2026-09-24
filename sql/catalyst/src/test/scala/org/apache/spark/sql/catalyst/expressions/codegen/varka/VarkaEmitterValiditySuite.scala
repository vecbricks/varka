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

import java.lang.foreign.{Arena, ValueLayout}

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The validity side of the emitted class: the masked body against the dense one, the word algebra
 * against the slot planner, the bitmap pass in the driver and the word liveness it rests on, and
 * the width-specialised validity helpers - byte-identical bitmaps under every setting, at every
 * length and width.
 */
class VarkaEmitterValiditySuite extends VarkaEmitterTestBase {

  test("the masked body agrees with the dense body on null-free data") {
    // forceMasked reports one null over a full-set bitmap, which the dispatcher sends down
    // runMasked; the reference expectations are identical to the dense run's.
    val root = new IfElse(new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1)),
      new Greatest(new DayOfWeek(new ColumnRef(0)), new ColumnRef(1)),
      new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    val nullFree = Seq(Seq[Int => Boolean](_ => false, _ => false))
    checkMatrix(Seq(root), 2, Array(3), Seq(17, 64, 65, 1000), nullFree, ctx = "dense")
    checkMatrix(Seq(root), 2, Array(3), Seq(17, 64, 65, 1000), nullFree,
      forceMasked = true, ctx = "forced-masked")
  }

  test("the validity-word algebra agrees with planWordRef on the shapes the plan " +
      "reasons about, and every word a body stores is loaded") {
    // Two emit-time assertions arm this task before it changes a byte. planSlots asserts, on
    // every masked body it plans, that the symbolic word algebra (Analysis.pureWord and
    // wordOwner - what the bitmap pass will read) and the slot references planWordRef assigns
    // describe the same word; and every loop or epilogue body asserts at its end that each
    // word it stored was loaded at least once and each word it loaded was stored, through the
    // one call every consumer reads a word by. Both run under every test in this suite and
    // every fuzz iteration. This test exists so a failure names itself here first, on the
    // shapes PLAN_TASK_70.md 3.3 registers op counts for, rather than inside whichever other
    // test happens to build the shape - and so that the two corners the agreement check
    // deliberately allows (greatest over two literals, greatest over one input twice: a slot
    // written with `-1 | -1` where the algebra says the constant or the input) are exercised on
    // purpose. Emission is the assertion.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val d3 = new ColumnRef(2)
    val d4 = new ColumnRef(3)
    val lit = new LiteralSlot(0)
    def ymdq(c: VarkaVectorIR): Seq[VarkaVectorIR] = Seq(
      new Year(c), new Month(c), new DayOfMonth(c), new Quarter(c))
    val shapes: Seq[(String, Seq[VarkaVectorIR], Int)] = Seq(
      ("year(d)", Seq(new Year(d)), 1),
      ("year, month, dayofmonth, quarter over d", ymdq(d), 1),
      ("next_day(d, k), column kernel", Seq(new NextDay(d, d2)), 2),
      ("greatest(d, d2)", Seq(new Greatest(d, d2)), 2),
      ("year(date_add(d, off)), guarded", Seq(new Year(new AddDays(d, d2))), 2),
      ("year(d) beside d < lit", Seq(new Year(d), new Compare(CompareOp.LT, d, lit)), 1),
      ("if(d < d2, d, d2)", Seq(new IfElse(new Compare(CompareOp.LT, d, d2), d, d2)), 2),
      ("datediff(greatest(d, d2), greatest(d3, d4)), the mixed tree",
        Seq(new DateDiff(new Greatest(d, d2), new Greatest(d3, d4))), 4),
      ("greatest(lit, lit) beside year(d)", Seq(new Greatest(lit, lit), new Year(d)), 1),
      ("greatest(d, d)", Seq(new Greatest(d, d)), 1),
      ("datediff(d, d)", Seq(new DateDiff(d, d)), 1),
      ("make_date, both forms", Seq[VarkaVectorIR](
        new MakeDate(new Year(d), new Month(d), new DayOfMonth(d), false),
        new MakeDate(new Year(d), new Month(d), new DayOfMonth(d), true)), 1),
      ("trunc(d, level column)", Seq(new TruncDateDynamic(d, d2)), 2),
      ("add_months(d, m), column count, under year", Seq(new Year(new AddMonths(d, d2))), 2))
    for ((name, roots, numInputs) <- shapes) {
      val (_, bytes) = emitMulti(roots, numInputs, 1)
      assert(bytes.nonEmpty, name)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Task 70: validity as bitmap algebra in the driver.
  // ---------------------------------------------------------------------------------------------

  private val bitmapOn = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(true)

  private val bitmapOff = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(false)

  private val supportClass = "org.apache.spark.sql.varka.vector.VarkaVectorSupport"

  /** The validity work in a method: every VarkaVectorSupport call but the segment mapping,
   *  which every body mode emits per segment and which would keep this off zero for ever
   *  (PLAN_TASK_70.md 3.3). */
  private def validityOps(bytes: Array[Byte], method: String): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, method, supportClass, Seq("ofAddress").asJava)

  private def supportNames(bytes: Array[Byte]): Set[String] =
    VarkaEmitterTestSupport.invokedNames(bytes, supportClass).asScala.toSet

  test("byte-identical validity with the bitmap pass on and off, every null pattern " +
      "and length, over the shapes the plan names") {
    // The existing oracle is the assertion: checkMatrix compares every output's validity byte
    // for byte against the reference evaluator and asserts status 0, and makeInputData poisons
    // every null lane, so this is also the guard-under-nulls test (PLAN_TASK_70.md 5) - a
    // lowering that dropped a guard's word AND would decline a batch here. Each shape names
    // the corner it is for.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val d3 = new ColumnRef(2)
    val lit = new LiteralSlot(0)
    val one = nullPatterns.map(p => Seq(p._2))
    val shapes: Seq[(String, Seq[VarkaVectorIR], Int, Seq[Seq[Int => Boolean]])] = Seq(
      ("year(d): a copy", Seq(new Year(d)), 1, one),
      ("four fields over d, one method",
        Seq(new Year(d), new Month(d), new DayOfMonth(d), new Quarter(d)), 1, one),
      ("next_day(d, k), column kernel: an AND", Seq(new NextDay(d, d2)), 2, combos(2)),
      // The OR root: with d all-null the output is d2's bitmap and 0L is never dereferenced;
      // with d null-free every row is valid, the case 2.3's first rule got wrong.
      ("greatest(d, d2): an OR", Seq(new Greatest(d, d2)), 2, combos(2)),
      ("least(d, d2)", Seq(new Least(d, d2)), 2, combos(2)),
      ("datediff(d, d2)", Seq(new DateDiff(d, d2)), 2, combos(2)),
      // The guards keep their words (2.2): the producer's word stays live for the AND with the
      // condemning mask, and a poisoned null lane must not decline the batch.
      ("year(date_add(d, off)), guarded producer", Seq(new Year(new AddDays(d, d2))), 2,
        combos(2)),
      ("year(add_months(d, m)), guarded count", Seq(new Year(new AddMonths(d, d2))), 2,
        combos(2)),
      ("year(d) beside d < lit: a Cond root keeps the read",
        Seq(new Year(d), new Compare(CompareOp.LT, d, lit)), 1, one),
      ("if(d < d2, d, d2): a blend, not served",
        Seq(new IfElse(new Compare(CompareOp.LT, d, d2), d, d2)), 2, combos(2)),
      // Two outputs where an all-null d does not fire the driver's shortcut, since year(d3)
      // reads no all-null column: the AND root's bitmap has to be written by the pass, before
      // step (5), or it is never written at all (3.1).
      ("datediff(d, d2) beside year(d3): the AND root past the shortcut",
        Seq(new DateDiff(d, d2), new Year(d3)), 3, combos(3)),
      // A null-free input inside a masked kernel: year(d)'s bitmap is setValid, not a copy.
      ("year(d) beside datediff(d2, d3): a null-free input under a masked driver",
        Seq(new Year(d), new DateDiff(d2, d3)), 3, combos(3)),
      // Three columns: the first two through the pair entry point, the third through Into.
      ("datediff(datediff(d, d2), d3): a three-column AND chain",
        Seq(new DateDiff(new DateDiff(d, d2), d3)), 3, combos(3)),
      ("greatest(greatest(d, d2), d3): a three-column OR chain",
        Seq(new Greatest(new Greatest(d, d2), d3)), 3, combos(3)),
      // The mixed tree is declined and keeps today's per-group path; it must still be right.
      ("datediff(greatest(d, d2), greatest(d, d3)): the mixed tree, declined",
        Seq(new DateDiff(new Greatest(d, d2), new Greatest(d, d3))), 3, combos(3)))
    for ((name, roots, n, patterns) <- shapes; on <- Seq(true, false)) {
      checkMatrix(roots, n, Array(3), remainderLengths ++ Seq(64, 1000), patterns,
        ctx = s"$name, validityByBitmap=$on", options = if (on) bitmapOn else bitmapOff)
    }
  }

  test("the validity work per masked loop method, as PLAN_TASK_70.md 3.3 registered " +
      "it, and no IntVector op moves") {
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val lit = new LiteralSlot(0)
    // The Cond pair needs one method to be the mixed method the table describes: a Year weighs
    // 38 against GROUP_BUDGET's 16, so at the default budget the two outputs split.
    val oneMethod = VarkaEmitOptions.DEFAULTS.withGroupBudget(200)
    val rows: Seq[(String, Seq[VarkaVectorIR], Int, VarkaEmitOptions, Int, Int)] = Seq(
      ("year(d)", Seq(new Year(d)), 1, VarkaEmitOptions.DEFAULTS, 2, 0),
      ("year, month, dayofmonth, quarter over d",
        Seq(new Year(d), new Month(d), new DayOfMonth(d), new Quarter(d)), 1,
        VarkaEmitOptions.DEFAULTS, 5, 0),
      ("next_day(d, k), column kernel", Seq(new NextDay(d, d2)), 2, VarkaEmitOptions.DEFAULTS,
        3, 0),
      // The pick's null substitution reads both operand words for the value, whether or not
      // its own word is wanted - the consumer PLAN_TASK_70.md 2.2 did not list - so its two
      // reads stay and only the write goes. The plan's 3.3 registered 0 here off 2.2's
      // inventory; this assertion is what corrected it.
      ("greatest(d, d2)", Seq(new Greatest(d, d2)), 2, VarkaEmitOptions.DEFAULTS, 3, 2),
      ("year(date_add(d, off)), guarded", Seq(new Year(new AddDays(d, d2))), 2,
        VarkaEmitOptions.DEFAULTS, 3, 2),
      ("year(d) beside d < lit, one method",
        Seq(new Year(d), new Compare(CompareOp.LT, d, lit)), 1, oneMethod, 3, 2),
      ("if(d < d2, d, d2)", Seq(new IfElse(new Compare(CompareOp.LT, d, d2), d, d2)), 2,
        VarkaEmitOptions.DEFAULTS, 3, 3))
    for ((name, roots, n, base, today, after) <- rows) {
      val off = emitMulti(roots, n, 1, base.withValidityByBitmap(false))._2
      val on = emitMulti(roots, n, 1, base.withValidityByBitmap(true))._2
      assert(validityOps(off, "loopMasked0") === today, s"$name, pass off: reads + writes today")
      assert(validityOps(on, "loopMasked0") === after, s"$name, pass on: what is left")
      assert(laneOps(on, "loopMasked0") === laneOps(off, "loopMasked0"),
        s"$name: the pass moved an IntVector op, and it touches no lane op")
    }
  }

  test("a single-operator word tree of any depth is served through the chain entry " +
      "points; a mixed AND/OR tree is declined and keeps its per-group write") {
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val d3 = new ColumnRef(2)
    val d4 = new ColumnRef(3)
    val andChain = emitMulti(Seq(new DateDiff(new DateDiff(d, d2), d3)), 3, 0, bitmapOn)._2
    assert(validityOps(andChain, "loopMasked0") === 0)
    assert(supportNames(andChain).contains("andColumnValidity"), supportNames(andChain))
    assert(supportNames(andChain).contains("andColumnValidityInto"), supportNames(andChain))
    val orChain = emitMulti(Seq(new Greatest(new Greatest(d, d2), d3)), 3, 0, bitmapOn)._2
    // The picks' value substitution still reads every operand word (2.2's missed consumer),
    // so the reads stay; only the root's write goes.
    assert(validityOps(orChain, "loopMasked0") === 3)
    assert(supportNames(orChain).contains("orColumnValidity"), supportNames(orChain))
    assert(supportNames(orChain).contains("orColumnValidityInto"), supportNames(orChain))
    val single = emitMulti(Seq(new Year(d)), 1, 0, bitmapOn)._2
    assert(supportNames(single).contains("copyColumnValidity"), supportNames(single))
    // And(Or(0, 1), Or(2, 3)): two live intermediates, which two-operand calls over one
    // destination cannot evaluate. Declined: no column entry point, today's reads and write.
    val mixed = emitMulti(
      Seq(new DateDiff(new Greatest(d, d2), new Greatest(d3, d4))), 4, 0, bitmapOn)._2
    assert(!supportNames(mixed).exists(_.contains("ColumnValidity")), supportNames(mixed))
    assert(validityOps(mixed, "loopMasked0") === 5, "four reads for the picks, one write")
  }

  test("the served and declined root counts, per shape") {
    // The safety net PLAN_TASK_70.md 3.1 promised. Without it a regression that stopped
    // serving every root would revert the whole lowering to the per-group path and pass the
    // suite: the byte-identity test compares the two settings, which agree when nothing is
    // served; the differential compares against a reference evaluator, and the per-group path
    // is correct; and every size assertion is an upper bound. So the counts are pinned per
    // shape here, in both directions - what is served, and what is declined and why.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val d3 = new ColumnRef(2)
    val d4 = new ColumnRef(3)
    def counts(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int = 0,
        options: VarkaEmitOptions = bitmapOn): (Int, Int) = {
      val c = VarkaLoopEmitter.bitmapPassCounts(roots.asJava, numInputs, numLiterals, options)
      (c(0), c(1))
    }
    // Served, and nothing declined: a leaf word, an AND chain, an OR chain, four fields over
    // one date, and the shape the whole task is named for.
    assert(counts(Seq(new Year(d)), 1) === (1, 0))
    assert(counts(Seq(new DateDiff(new DateDiff(d, d2), d3)), 3) === (1, 0))
    assert(counts(Seq(new Greatest(new Greatest(d, d2), d3)), 3) === (1, 0))
    assert(counts(Seq(new Year(d), new Month(d), new DayOfMonth(d), new Quarter(d)), 1)
      === (4, 0))
    // Declined for a mixed tree - the one kind the counter is for.
    assert(counts(Seq(new DateDiff(new Greatest(d, d2), new Greatest(d3, d4))), 4) === (0, 1))
    // Unserved but not declined: a word the emission computes rather than folds. `IfElse`
    // blends by the known-true mask and `make_date` tests its own validity, so neither has a
    // pure expression at all and neither is a mixed tree.
    val blend = new IfElse(new Compare(CompareOp.LT, d, d2), d, d2)
    assert(counts(Seq(blend), 2) === (0, 0))
    assert(counts(Seq(new MakeDate(new Year(d), new Month(d), new DayOfMonth(d), false)), 1)
      === (0, 0))
    // A `Cond` root is a selection bitmap, not a value: never served, never counted.
    assert(counts(Seq(new Compare(CompareOp.LT, d, d2)), 2) === (0, 0))
    // With the option off nothing is served and nothing is declined - the pass does not run,
    // so a shape that would have been declined is not counted as one.
    val off = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(false)
    assert(counts(Seq(new Year(d)), 1, 0, off) === (0, 0))
    assert(counts(Seq(new DateDiff(new Greatest(d, d2), new Greatest(d3, d4))), 4, 0, off)
      === (0, 0))
  }

  test("the word-liveness invariant is armed, in both directions") {
    // misdescribeWordLiveness inverts the verdict on every word. year(d): its only word is
    // dead - the root is served and nothing else reads it - so the fault makes it live: stored
    // at the top of the lane group, loaded by nobody, refused at the end of the body.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val fault = bitmapOn.withMisdescribeWordLiveness(true)
    val stored = intercept[IllegalStateException] {
      emitMulti(Seq(new Year(d)), 1, 0, fault)
    }
    assert(stored.getMessage.contains("stored but never loaded"), stored.getMessage)
    // year(date_add(d, off)): the guard reads the producer's word, so it and both inputs' are
    // live; the fault makes them dead, and the guard's load is refused at the load.
    val loaded = intercept[IllegalStateException] {
      emitMulti(Seq(new Year(new AddDays(d, d2))), 2, 0, fault)
    }
    assert(loaded.getMessage.contains("declared dead is loaded"), loaded.getMessage)
    // make_date: its own word is demanded unconditionally, because its guard reads it, so the
    // fault kills it and the guard's load is refused - the same direction as above, on the one
    // node that reaches its word through neither the AND family nor a root write. Pinned
    // because that arm read its slot directly until this task's review: a raw load reaches no
    // refusal at all, and the emission died in the class-file writer with an invalid local
    // index instead, which is not what this injector is documented to raise.
    val makeDate = new MakeDate(new Year(d), new Month(d), new DayOfMonth(d), false)
    val guardLoad = intercept[IllegalStateException] {
      emitMulti(Seq[VarkaVectorIR](makeDate), 1, 0, fault)
    }
    assert(guardLoad.getMessage.contains("declared dead is loaded"), guardLoad.getMessage)
    // With the pass off every word is live already, so the inversion has nothing to invert.
    assert(emitMulti(Seq(new Year(d)), 1, 0, bitmapOff.withMisdescribeWordLiveness(true))
      ._2.nonEmpty)
    assert(emitMulti(Seq[VarkaVectorIR](makeDate), 1, 0,
      bitmapOff.withMisdescribeWordLiveness(true))._2.nonEmpty)
  }

  test("a masked method whose every word is dead is its dense twin's bytes - one " +
      "body, not two") {
    // No per-group read, no per-group write, no null-state prologue, no own-word slot: what is
    // left is the dense method. Asserted on size rather than on the byte string because the
    // two methods differ in name inside the constant pool, not in code; a size match on both
    // the loop and the epilogue is the claim 2.34 asked to have verified rather than assumed.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    for ((name, roots, n) <- Seq(
        ("year(d)", Seq[VarkaVectorIR](new Year(d)), 1),
        ("four fields over d",
          Seq[VarkaVectorIR](new Year(d), new Month(d), new DayOfMonth(d), new Quarter(d)), 1),
        ("next_day(d, k), column kernel", Seq[VarkaVectorIR](new NextDay(d, d2)), 2),
        ("datediff(d, d2)", Seq[VarkaVectorIR](new DateDiff(d, d2)), 2))) {
      val bytes = emitMulti(roots, n, 0, bitmapOn)._2
      for ((masked, dense) <- Seq(("loopMasked0", "loopDense0"),
          ("epilogueMasked0", "epilogueDense0"))) {
        assert(VarkaEmitterTestSupport.codeSize(bytes, masked) ===
          VarkaEmitterTestSupport.codeSize(bytes, dense), s"$name: $masked against $dense")
      }
    }
  }

  test("the driver's fill writes the bits the loop used to OR, exactly") {
    // The narrow claim: the dense path writes the same bits from a different place. So the
    // check is byte-for-byte identity against today's path, at every length where the last
    // byte is partial - which is the byte that fails if setValid fills whole bytes rather than
    // exactly `length` bits. assertSameOutput inside checkMatrix already compares validity byte
    // for byte, so driving both option values through it is the assertion.
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)), new DayOfWeek(new ColumnRef(0)))
    val nullFree = Seq(Seq[Int => Boolean](_ => false))
    val lengths = Seq(1, 7, 8, 9, 15, 16, 17, 63, 64, 65, 1000, 4095)
    // Days that stay inside the narrowed range at every index these lengths reach.
    // `calendarDays` walks out of it past about index 1180 (i * 9973 - 400000), and since task
    // 51 removed the per-extraction guard an out-of-range day no longer declines - it returns a
    // plausible wrong year. That is a real hazard, but it is task 52's, and a validity test
    // that trips over it is testing the wrong thing.
    def inRangeDays(c: Int, i: Int): Int = 19000 + (i % 9973)
    for (once <- Seq(true, false)) {
      checkMatrix(roots, 1, Array.empty[Int], lengths, nullFree, data = inRangeDays,
        ctx = s"denseValidityOnce=$once",
        options = VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(once))
    }
  }

  test("a Cond root keeps its per-group OR under both option values") {
    // The selection bitmap's bits mean "known true", not "valid", so the driver must not fill
    // it - a filled selection bitmap selects every row. This is the test that fails if the
    // fill is applied to a Cond root, and it is why fillsValidityOnce excludes them rather
    // than the driver and the loop each deciding separately.
    val root = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1))
    for (once <- Seq(true, false)) {
      checkMatrix(Seq(root), 2, Array.empty[Int], Seq(17, 64, 65, 1000),
        nullPatterns.map(p => Seq(p._2, p._2)), ctx = s"cond, denseValidityOnce=$once",
        options = VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(once))
    }
  }

  test("the masked path's bytes do not move, and the dense path's shrink") {
    // The guard that keeps this task off the masked path, asserted the way task 32 asserted
    // its own: the masked bodies are byte for byte as they were, so no masked case can have
    // changed, and only the dense loop is allowed to have lost anything.
    val col = new ColumnRef(0)
    for ((roots, name) <- Seq(
        (Seq[VarkaVectorIR](new Year(col)), "year"),
        (Seq[VarkaVectorIR](new Year(col), new Month(col)), "year+month"),
        (Seq[VarkaVectorIR](chain(4)), "chain4"))) {
      val off = emitMulti(roots, 1, 4, VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(false))._2
      val on = emitMulti(roots, 1, 4, VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(true))._2
      for (body <- Seq("loopMasked0", "epilogueMasked0")) {
        assert(VarkaEmitterTestSupport.codeSize(off, body) ===
          VarkaEmitterTestSupport.codeSize(on, body),
          s"$name: $body moved, so this task reached the masked path")
      }
      for (body <- Seq("loopDense0", "epilogueDense0")) {
        assert(VarkaEmitterTestSupport.codeSize(on, body) <
          VarkaEmitterTestSupport.codeSize(off, body),
          s"$name: $body did not shrink, so the per-group OR is still being emitted")
      }
    }
  }

  /** The support class the emitted bodies call their validity helpers on. */
  private val support = "org.apache.spark.sql.varka.vector.VarkaVectorSupport"

  private val intVector = "jdk.incubator.vector.IntVector"

  test("a whole lane group calls the helper named for the emitted width") {
    // The emitter knows the lane count when it writes the bytes, so the callee can carry it and
    // the four-arm switch on the width disappears from the call. Asserted on the names in the
    // class rather than on a timing, and by exact match: "orValidityBitsAt" is a prefix of
    // "orValidityBitsAt16", so a substring test would pass on the form this task removes.
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    for ((lanes, bits) <- Seq(2 -> 64, 4 -> 128, 8 -> 256, 16 -> 512)) {
      // Since task 70 the shipped year(d) makes no per-group validity call at all - its
      // bitmap is copied once by the driver - so the helpers this test names are reached
      // through the per-group reference variant, which is what the naming is pinned on.
      val bytes = emitMulti(roots, 1, 0,
        VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes).withValidityByBitmap(false))._2
      val called = VarkaEmitterTestSupport.invokedNames(bytes, support).asScala
      assert(called.contains(s"validityBitsAt$lanes"), s"$lanes lanes: $called")
      assert(called.contains(s"orValidityBitsAt$lanes"), s"$lanes lanes: $called")
      assert(!called.contains("validityBitsAt"), s"$lanes lanes: the general reader survived")
      assert(!called.contains("orValidityBitsAt"), s"$lanes lanes: the general writer survived")
      // The epilogue's partial group is not a lane width and keeps the general pair.
      assert(called.contains("orPartialValidityBitsAt"), s"$lanes lanes: $called")
      // The species is baked to match, so the class cannot compute at one width and write
      // validity at another - the invariant that would otherwise be implicit in "the emitter
      // runs in the JVM that runs the kernel".
      val fields = VarkaEmitterTestSupport.staticFieldsRead(bytes, intVector).asScala
      assert(fields.contains(s"SPECIES_$bits"), s"$lanes lanes: $fields")
      assert(!fields.contains("SPECIES_PREFERRED"), s"$lanes lanes: $fields")
    }
  }

  test("a width with no specialised helper falls back to the general pair") {
    // 32 int lanes is a 1024-bit shape: SVE reaches it, the Vector API has no named species
    // constant for it, and VarkaVectorSupport has no pair. The fallback is what keeps such a
    // machine correct, so it is emitted and asserted rather than reasoned about.
    // The per-group reference arm since task 70: the shipped year(d) makes no per-group
    // validity call, and it is the general pair's naming this test pins.
    val bytes = emitMulti(Seq[VarkaVectorIR](new Year(new ColumnRef(0))), 1, 0,
      VarkaEmitOptions.DEFAULTS.withLanesOverride(32).withValidityByBitmap(false))._2
    val called = VarkaEmitterTestSupport.invokedNames(bytes, support).asScala
    assert(called.contains("validityBitsAt") && called.contains("orValidityBitsAt"), s"$called")
    assert(!called.exists(_.matches("(or)?ValidityBitsAt\\d+")), s"$called")
    assert(VarkaEmitterTestSupport.staticFieldsRead(bytes, intVector).asScala
      .contains("SPECIES_PREFERRED"))
  }

  test("with the option off the emission is the pre-task form") {
    // The A/B's other arm, and the reference variant: no width anywhere - not in a callee name
    // and not in the species - so what the benchmark compares against is what shipped before.
    // Both of task 46's arms are reached through task 70's per-group reference arm now.
    val bytes = emitMulti(Seq[VarkaVectorIR](new Year(new ColumnRef(0))), 1, 0,
      VarkaEmitOptions.DEFAULTS.withValidityByWidth(false).withValidityByBitmap(false))._2
    val called = VarkaEmitterTestSupport.invokedNames(bytes, support).asScala
    assert(called.contains("validityBitsAt") && called.contains("orValidityBitsAt"), s"$called")
    assert(!called.exists(_.matches("(or)?ValidityBitsAt\\d+")), s"$called")
    val fields = VarkaEmitterTestSupport.staticFieldsRead(bytes, intVector).asScala
    assert(fields.contains("SPECIES_PREFERRED"), s"$fields")
    assert(!fields.exists(_.matches("SPECIES_\\d+")), s"$fields")
    // And the lane count is asked for at run time here and nowhere in a baked emission, which
    // is the other half of "the width is a property of the class": a constant, not a call.
    val species = "jdk.incubator.vector.VectorSpecies"
    assert(VarkaEmitterTestSupport.invokedNames(bytes, species).asScala.contains("length"))
    val baked = emitMulti(Seq[VarkaVectorIR](new Year(new ColumnRef(0))), 1, 0)._2
    assert(!VarkaEmitterTestSupport.invokedNames(baked, species).asScala.contains("length"),
      "the baked emission still calls VectorSpecies.length()")
  }

  test("the word writer's bitmap is the per-group writer's, at every length, width " +
      "and null state") {
    // The failure mode this task has and its predecessors did not: a store eight bytes wide
    // where the group is one or two, into a bitmap whose nominal size is (length + 7) / 8. Both
    // halves of that are length-dependent and silent - a word that runs off the end faults only
    // when the segment happens to be tight, and a partial word left behind is a wrong bit, not
    // a crash - so the ladder is the test, and it runs the awkward lengths on purpose: below a
    // word, either side of a word boundary, either side of the default batch size.
    //
    // The oracle is the per-group writer itself. Both arms run on the same input and the
    // bitmaps are compared byte for byte over the rows that exist, which is the only comparison
    // that can catch a bit set in the wrong word.
    val blend: VarkaVectorIR = new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0)),
      new AddDays(new ColumnRef(0), new LiteralSlot(0)),
      new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    // A Cond root is the filter kernel, and it is in this task's population on *every* batch:
    // its slot holds a selection bitmap rather than validity, so task 45's driver fill cannot
    // serve it and task 70's pass does not run in a dense body at all. Leaving it out would
    // leave the project's most common shape untested at both arms.
    val filter: VarkaVectorIR = new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0))
    val lits = Array(3)
    for (lanes <- Seq(4, 8, 16)) {
      val perGroup = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes)
      val byWord = perGroup.withValidityByWord(true)
      for ((roots, shape) <- Seq(
          Seq(blend) -> "one blend",
          Seq(filter) -> "a filter",
          Seq(blend, filter) -> "a blend beside a filter",
          Seq(blend, blend, filter) -> "two blends beside a filter")) {
        val (refKernel, refLoader) = load(emitMulti(roots, 1, lits.length, perGroup))
        val (wordKernel, wordLoader) = load(emitMulti(roots, 1, lits.length, byWord))
        try {
          for (length <- Seq(1, 7, 8, 15, 16, 63, 64, 65, 127, 128, 129, 1000, 4096);
               (isNull, nullState) <- Seq[(Int => Boolean, String)](
                 (_ => false, "null-free"),
                 (i => i % 3 == 0, "mixed nulls"),
                 (_ => true, "all null"))) {
            val arena = Arena.ofConfined()
            try {
              val col = makeInputData(arena, length, isNull, i => i - 500)
              // The reference arm keeps the tight segment, so it stays the guard it has always
              // been; only the word arm is given the whole words a real destination carries.
              val refOut = roots.map(_ => makeOutput(arena, length))
              val wordOut = roots.map(_ =>
                makeOutput(arena, length, ((length + 63L) / 64L) * 8L))
              val ctx = s"$shape at $lanes lanes, length $length, $nullState"
              for ((kernel, outs) <- Seq(refKernel -> refOut, wordKernel -> wordOut)) {
                val dstData = roots.zip(outs).map { case (root, out) =>
                  if (root.isInstanceOf[Cond]) 0L else out._1.address()
                }
                val status = kernel.run(Array(col.data.address()),
                  Array(col.validityAddress(length)), Array(col.nullCount),
                  dstData.toArray, outs.map(_._2.address()).toArray, lits, length)
                assert(status === 0, s"$ctx: the kernel declined a batch it should compute")
              }
              for ((root, i) <- roots.zipWithIndex) {
                for (b <- 0L until (length + 7) / 8L) {
                  assert(
                    wordOut(i)._2.get(ValueLayout.JAVA_BYTE, b) ===
                      refOut(i)._2.get(ValueLayout.JAVA_BYTE, b),
                    s"$ctx: output $i validity byte $b differs")
                }
                if (!root.isInstanceOf[Cond]) {
                  for (r <- 0 until length) {
                    val valid =
                      (refOut(i)._2.get(ValueLayout.JAVA_BYTE, r / 8L) & (1 << (r % 8))) != 0
                    if (valid) {
                      assert(
                        wordOut(i)._1.get(ValueLayout.JAVA_INT, r * 4L) ===
                          refOut(i)._1.get(ValueLayout.JAVA_INT, r * 4L),
                        s"$ctx: output $i row $r differs")
                    }
                  }
                }
              }
            } finally {
              arena.close()
            }
          }
        } finally {
          refLoader.release()
          wordLoader.release()
        }
      }
    }
  }

  /** The four body methods' sizes, which is how a change's blast radius is asserted here. */
  private def bodySizes(named: (String, Array[Byte])): Seq[Int] =
    Seq("loopDense0", "loopMasked0", "epilogueDense0", "epilogueMasked0")
      .map(VarkaEmitterTestSupport.codeSize(named._2, _))

  test("the word writer reaches the outputs that keep a per-group write, and only " +
      "those") {
    // The blast radius, asserted rather than described. An output task 45 fills once, and one
    // task 70's pass writes whole, must emit the same bytes under both arms - the word writer
    // has nothing to do for them - while an output that still writes per lane group must not.
    // This is also what stops the two arms collapsing into one kernel, which is exactly how
    // task 46's A/B silently began timing itself (see the task 76 test below).
    val lanes = 16
    val perGroup = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes)
    val byWord = perGroup.withValidityByWord(true)
    // `year(d)` on a dense batch is task 45's fill; on a masked batch with the bitmap pass on
    // it is task 70's whole-bitmap write. Neither keeps a per-group write, so both arms agree.
    val year: VarkaVectorIR = new Year(new ColumnRef(0))
    assert(bodySizes(emitMulti(Seq(year), 1, 0, perGroup)) ===
      bodySizes(emitMulti(Seq(year), 1, 0, byWord)),
      "an output the driver writes must not change under the word writer")
    // A blend's word is computed per lane group, so it is unserved by construction and keeps
    // the write - both bodies must differ.
    val blend: VarkaVectorIR = new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0)),
      new AddDays(new ColumnRef(0), new LiteralSlot(0)),
      new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    assert(bodySizes(emitMulti(Seq(blend), 1, 1, perGroup)) !==
      bodySizes(emitMulti(Seq(blend), 1, 1, byWord)),
      "a per-group write must change under the word writer, or the A/B times one kernel twice")
    // And a filter, whose per-group OR survives task 45 in the dense body too.
    val filter: VarkaVectorIR = new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0))
    assert(bodySizes(emitMulti(Seq(filter), 1, 1, perGroup)) !==
      bodySizes(emitMulti(Seq(filter), 1, 1, byWord)),
      "a selection root must change under the word writer")
    // A width the accumulator's arithmetic cannot serve falls back to the per-group form
    // rather than emitting something subtly wrong. At 64 lanes a group is a whole word and the
    // mask `(1L << lanes) - 1` is zero, because Java shifts modulo 64 - the arm would write
    // nothing but zeros. No int lane count this JVM offers reaches it, which is exactly why it
    // is worth an assertion: the guard is unreachable today and has to survive a wider one.
    val wide = VarkaEmitOptions.DEFAULTS.withLanesOverride(64)
    assert(bodySizes(emitMulti(Seq(blend), 1, 1, wide)) ===
      bodySizes(emitMulti(Seq(blend), 1, 1, wide.withValidityByWord(true))),
      "a 64-lane group must not word-write, since its lane mask would be zero")
  }

  test("the write-count ladder really is one shape family, so its steps are runtime") {
    // Read the ladder's own emissions before reading its numbers. PLAN_TASK_76.md 3.2 built
    // these four rungs to "hold the shape family constant and vary only the count", and task 47
    // measured a step at k=3 that neither task's model predicts: both arms that write per lane
    // group fall away sharply there while the word writer does not. The first thing to rule out
    // is a layout change - a rung crossing GROUP_BUDGET into two loop methods would pay every
    // per-method cost twice, and no rule could be fitted across that.
    //
    // It does not happen. All four rungs emit one masked loop method and the body grows by a
    // steady ~130 bytes per write. So the k=3 step is a property of how the JVM runs these
    // bytes, not of which bytes are emitted - which is what points at task 46's mechanism, the
    // caller's node count crossing C2's inlining cutoff so that one more OR call stops being
    // inlined. The word writer has no call at that site to refuse, and its curve is smooth.
    // Asserted here so the next reader of either ladder meets the fact before the number.
    // Built exactly as the benchmark builds them - one literal slot per blend. Repeating one
    // slot instead would make the k roots the same tree, which CSE collapses to a single
    // output: the rungs would all be one write and the ladder would measure nothing.
    def rung(k: Int): Seq[VarkaVectorIR] = (0 until k).map { j =>
      new IfElse(
        new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(j)),
        new AddDays(new ColumnRef(0), new LiteralSlot(j)),
        new SubDays(new ColumnRef(0), new LiteralSlot(j)))
    }
    def loopMethods(k: Int): Int =
      methodNames(emitMulti(rung(k), 1, 4, VarkaEmitOptions.DEFAULTS))
        .count(_.startsWith("loopMasked"))
    assert((1 to 4).map(loopMethods) === Seq(1, 1, 1, 1),
      "a rung emitting two loop methods would pay every per-method cost twice")
    val bytes = (1 to 4).map(k =>
      VarkaEmitterTestSupport.codeSize(
        emitMulti(rung(k), 1, 4, VarkaEmitOptions.DEFAULTS)._2, "loopMasked0"))
    val steps = bytes.sliding(2).map(p => p(1) - p(0)).toSeq
    assert(steps.forall(step => step > 100 && step < 160),
      s"the rungs should grow by one write's worth of bytes each: $bytes (steps $steps)")
  }

  test("every arm of the width-specialisation A/B still emits two different kernels") {
    // The failure this task is downstream of, made loud. Task 70's pass removed the per-group
    // validity call for a served root, which left both of task 46's arms emitting the same
    // bytes - each pair timed one kernel against itself, and the committed numbers said so for
    // a regeneration before anyone noticed. The arms were rebuilt on task 70's per-group
    // reference variant; this is the assertion that they stay rebuilt.
    //
    // Asserted on the loop methods rather than the whole class, since `emitMulti` gives each
    // class a fresh name and the name is in the bytes.
    val col = new ColumnRef(0)
    val perGroup = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(false)
    val general = perGroup.withValidityByWidth(false)
    def layout(bytes: (String, Array[Byte])): Seq[(String, Int)] =
      methodNames(bytes).filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked"))
        .sorted.map(m => m -> VarkaEmitterTestSupport.codeSize(bytes._2, m))

    // Every shape the parity file pairs for this A/B, with the options each arm is built from.
    val pairs = Seq[(String, Seq[VarkaVectorIR], Int, Int, VarkaEmitOptions, VarkaEmitOptions)](
      ("year", Seq[VarkaVectorIR](new Year(col)), 1, 0, perGroup, general),
      ("year, dense arm", Seq[VarkaVectorIR](new Year(col)), 1, 0,
        perGroup.withDenseValidityOnce(false), general.withDenseValidityOnce(false)),
      ("dayofweek", Seq[VarkaVectorIR](new DayOfWeek(col)), 1, 0, perGroup, general),
      ("year+month+day+quarter, shared",
        Seq[VarkaVectorIR](new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col)),
        1, 0, perGroup, general),
      ("filter d < literal",
        Seq[VarkaVectorIR](new Compare(CompareOp.LT, col, new LiteralSlot(0))), 1, 1,
        VarkaEmitOptions.DEFAULTS, general))
    for ((name, roots, inputs, lits, specialised, other) <- pairs) {
      assert(layout(emitMulti(roots, inputs, lits, specialised))
        !== layout(emitMulti(roots, inputs, lits, other)),
        s"$name: the two arms emit the same loop methods, so their benchmark pair times one " +
          "kernel against itself - which is exactly what task 70 did to this A/B once")
    }

    // The filter pair is the one whose two arms differ in two flags nominally, `DEFAULTS`
    // against `perGroupWrite.withValidityByWidth(false)`. `validityByBitmap` should be inert
    // for a `Cond` root, since the bitmap pass never serves one - asserted here rather than
    // assumed, because if it is not inert that pair measures two changes at once.
    val cond = Seq[VarkaVectorIR](new Compare(CompareOp.LT, col, new LiteralSlot(0)))
    assert(layout(emitMulti(cond, 1, 1, VarkaEmitOptions.DEFAULTS))
      === layout(emitMulti(cond, 1, 1, perGroup)),
      "validityByBitmap is not inert for a Cond root, so the filter A/B varies two things")
  }

  test("the specialised helpers answer what the general pair answered") {
    // The correctness statement, and the only one that matters: results identical under both
    // settings, at every null pattern and every length where the last byte is partial. The
    // helpers' own equivalence is pinned in the engine's VarkaVectorSupportWidthTest; this is
    // the emitted loop calling them with the rows and words it really produces.
    // On the per-group reference arm, for the reason the naming tests above give: both of
    // these roots are served by task 70's bitmap pass, so under the shipped default neither
    // arm makes a per-group validity call and the two would be the same kernel - a
    // self-comparison that could not fail. The default path's own coverage of these helpers
    // is the declined-root test below, where the per-group write survives.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](new Year(col), new DayOfWeek(col))
    val lengths = Seq(1, 7, 8, 9, 15, 16, 17, 63, 64, 65, 1000, 4095)
    def inRangeDays(c: Int, i: Int): Int = 19000 + (i % 9973)
    for (byWidth <- Seq(true, false)) {
      checkMatrix(roots, 1, Array.empty[Int], lengths, nullPatterns.map(p => Seq(p._2)),
        data = inRangeDays, ctx = s"validityByWidth=$byWidth",
        options = VarkaEmitOptions.DEFAULTS.withValidityByWidth(byWidth)
          .withValidityByBitmap(false))
    }
  }

  test("the specialised helpers are still reached under the bitmap pass default") {
    // What the two A/B tests above cannot check once they run on the reference arm: that the
    // width-specialised writer is still emitted, and still right, on the shipped default. A
    // root the bitmap pass declines is what keeps a per-group write there - here a tree that
    // mixes the two operators, which no chain of one operator can fold - so the helpers are
    // named and the results compared with `validityByBitmap` left on.
    val mixed = new DateDiff(new Greatest(new ColumnRef(0), new ColumnRef(1)),
      new Greatest(new ColumnRef(2), new ColumnRef(3)))
    assert(VarkaLoopEmitter.bitmapPassCounts(Seq[VarkaVectorIR](mixed).asJava, 4, 0,
      VarkaEmitOptions.DEFAULTS) === Array(0, 1), "the fixture is meant to be declined")
    for ((lanes, _) <- Seq(2 -> 64, 4 -> 128, 8 -> 256, 16 -> 512)) {
      val bytes = emitMulti(Seq[VarkaVectorIR](mixed), 4, 0,
        VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes))._2
      val called = VarkaEmitterTestSupport.invokedNames(bytes, support).asScala
      assert(called.contains(s"orValidityBitsAt$lanes"),
        s"$lanes lanes: the default path lost the specialised writer: $called")
    }
    for (byWidth <- Seq(true, false)) {
      checkMatrix(Seq(mixed), 4, Array.empty[Int], Seq(17, 64, 65, 1000, 4095),
        nullPatterns.map(p => Seq(p._2, p._2, p._2, p._2)),
        ctx = s"declined root, validityByWidth=$byWidth",
        options = VarkaEmitOptions.DEFAULTS.withValidityByWidth(byWidth))
    }
  }

  test("a Cond root's selection bitmap is identical under both settings") {
    // The shape this task helps that task 45 could not: a filter kernel ORs its selection
    // bitmap per lane group in both bodies, because those bits are computed rather than known.
    // Identical bitmaps under both settings is what says the specialised writer's lane mask is
    // right where the word carries bits above the group.
    val root = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1))
    for (byWidth <- Seq(true, false)) {
      checkMatrix(Seq(root), 2, Array.empty[Int], Seq(17, 64, 65, 1000, 4095),
        nullPatterns.map(p => Seq(p._2, p._2)), ctx = s"cond, validityByWidth=$byWidth",
        options = VarkaEmitOptions.DEFAULTS.withValidityByWidth(byWidth))
    }
  }

  test("the validity OR before the compute answers what the OR after it answered") {
    // The order moved so C2 meets the OR helper before the body's intrinsics have spent its
    // node budget; the bytes are the same either way and the results must be. The second root
    // is the shape that caught the first version of this: a Year over an IfElse, whose word
    // aliases the blend's *computed* slot and so is not known before the compute - reading it
    // early was a frame with no such local, and the verifier said so. Both settings, every
    // null pattern, lengths with a partial last byte, the masked path forced. Not length 1:
    // forcing the masked path sets the null count to 1, and a null count equal to the length is
    // the all-null column by the harness's own contract (validity address 0L), which the oracle
    // does not model - so that one length fails under either setting, for a reason that is not
    // this test's.
    val col = new ColumnRef(0)
    val lit = new LiteralSlot(0)
    val blend = new IfElse(new Compare(CompareOp.LT, col, lit), new AddDays(col, lit), col)
    val roots = Seq[VarkaVectorIR](new Year(col), new Year(blend), new Greatest(col, blend))
    def inRangeDays(c: Int, i: Int): Int = 19000 + (i % 9973)
    // On the per-group reference arm: `validityOrFirst` moves the per-group OR, and under
    // task 70's default the first root makes no such OR at all while the other two hold
    // computed words that were never known before the compute - so all three arms would be
    // one kernel and the comparison would be with itself.
    for (orFirst <- Seq(true, false)) {
      checkMatrix(roots, 1, Array(3), Seq(7, 8, 9, 15, 16, 17, 63, 64, 65, 1000, 4095),
        nullPatterns.map(p => Seq(p._2)), data = inRangeDays, forceMasked = true,
        ctx = s"validityOrFirst=$orFirst",
        options = VarkaEmitOptions.DEFAULTS.withValidityOrFirst(orFirst)
          .withValidityByBitmap(false))
    }
  }

  test("an emission for a foreign width still computes that width's answers") {
    // lanesOverride exists so one JVM can exercise every arm, which is only honest if the
    // emitted class is self-consistent: it carries the species its helper names were chosen
    // for, so it computes correctly (slowly, if the hardware is narrower) rather than writing
    // validity for a width its vectors do not have.
    val col = new ColumnRef(0)
    for (lanes <- Seq(2, 4, 8, 16)) {
      checkMatrix(Seq[VarkaVectorIR](new AddDays(col, new LiteralSlot(0))), 1, Array(3),
        Seq(17, 64, 65, 1000), nullPatterns.map(p => Seq(p._2)), ctx = s"lanesOverride=$lanes",
        options = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes))
    }
  }
}
