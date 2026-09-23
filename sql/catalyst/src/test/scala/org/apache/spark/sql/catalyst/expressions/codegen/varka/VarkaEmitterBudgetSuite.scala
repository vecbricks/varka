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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * Output grouping and the method budgets: `fitsBudgets` against the analysis caps, which shapes a
 * budget change regroups, the calendar weights against what the emitter emits, and the emitted
 * methods against `HugeMethodLimit`.
 */
class VarkaEmitterBudgetSuite extends VarkaEmitterTestBase {

  test("fitsBudgets mirrors the analysis caps, distinct ops across outputs") {
    def chain(base: Int, depth: Int): VarkaVectorIR =
      (0 until depth).foldLeft[VarkaVectorIR](new ColumnRef(base)) { (n, _) =>
        new AddDays(n, new LiteralSlot(0))
      }
    assert(VarkaLoopEmitter.fitsBudgets(java.util.List.of[VarkaVectorIR](chain(0, 16)), 1))
    assert(!VarkaLoopEmitter.fitsBudgets(java.util.List.of[VarkaVectorIR](chain(0, 17)), 1))
    // Five disjoint depth-13 chains are 65 distinct ops - the same shape the emitter's own
    // rejection test uses against MAX_FUSED_NODES.
    val five: Seq[VarkaVectorIR] = (0 until 5).map(k => chain(k, 13))
    assert(!VarkaLoopEmitter.fitsBudgets(java.util.List.of[VarkaVectorIR](five: _*), 5))
    // A shared subtree is one node, exactly as Analysis counts it.
    val shared = chain(0, 13)
    val sharedFive: Seq[VarkaVectorIR] = Seq.fill(5)(shared)
    assert(VarkaLoopEmitter.fitsBudgets(java.util.List.of[VarkaVectorIR](sharedFive: _*), 1))
    // The input-column cap is mirrored too (the review found it missing): the emitter's
    // emit() rejects numInputs > 64, so the compiler must never accept such a projection.
    val one = java.util.List.of[VarkaVectorIR](chain(0, 1))
    assert(VarkaLoopEmitter.fitsBudgets(one, 64))
    assert(!VarkaLoopEmitter.fitsBudgets(one, 65))
  }

  test("calendar siblings over one date share a loop method; plain chains, other " +
      "dates and the ceiling keep them apart") {
    // PLAN_TASK_32.md 10.2's table, pinned by loop-method count. Before B2 this test asserted
    // the opposite for the four fields - one method each, "whatever GROUP_BUDGET would say" -
    // because a method of ~180 ops was believed to be a compile cliff. 7.5 measured that away
    // and clause 2 of groupOutputs now admits an output that reuses a prefix the group already
    // computes, up to FUSED_CEILING. Everything clause 2 does not admit keeps today's grouping,
    // and that half is the guard: whether a merely-shared subchain pays to merge is
    // GROUP_BUDGET's own question (task 17 measured a loss, the file since task 46 shows a win;
    // task 43 owns it), and B2 deliberately does not answer it.
    val col = new ColumnRef(0)
    val fields = Seq[VarkaVectorIR](
      new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    def loops(roots: Seq[VarkaVectorIR], inputs: Int, lits: Int,
        options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Int =
      methodNames(emitMulti(roots, inputs, lits, options)).count(_.startsWith("loopDense"))
    // Four fields over one date: one prefix, four tails, 52 ops in one method.
    assert(loops(fields, 1, 0) === 1)
    // With sharing off there is no prefix to reuse, clause 2 never fires, and each field
    // outweighs GROUP_BUDGET on its own: the four methods of before B2, kept as the reference
    // variant the parity benchmark's "separate" rows are emitted with.
    assert(loops(fields, 1, 0, unshared) === 4)
    // Two dates: the second year reuses nothing (saved = 0) and 38 + 38 > 16.
    assert(loops(Seq(new Year(col), new Year(new ColumnRef(1))), 2, 0) === 2)
    // A plain chain is untouched: add_days and sub_days fit the budget together as they did.
    assert(loops(Seq(new AddDays(col, new LiteralSlot(0)), new SubDays(col, new LiteralSlot(0))),
      1, 1) === 1)
    // A plain output ahead of the siblings: year reuses nothing against [x + 1] and 1 + 38 > 16,
    // so it opens a group of its own, which month then joins.
    assert(loops(Seq(new AddDays(col, new LiteralSlot(0)), new Year(col), new Month(col)),
      1, 1) === 2)
    // The ceiling bounds clause 2: at prefix + two tails the third sibling opens a new group,
    // which the fourth joins - two methods of two.
    val tight = VarkaEmitOptions.DEFAULTS.withFusedCeiling(
      VarkaEmitBudget.CHRONO_PREFIX_WEIGHT + 2 * VarkaEmitBudget.CHRONO_FIELD_TAIL_WEIGHT)
    assert(loops(fields, 1, 0, tight) === 2)
    // Greedy in output order, pinned as the limitation 10.2 names rather than fixed: month(d)
    // is offered to the group holding year(d2), whose prefix it cannot reuse, so it forms a
    // third group instead of rejoining year(d). Adjacent, the same three outputs take two.
    assert(loops(Seq(new Year(col), new Year(new ColumnRef(1)), new Month(col)), 2, 0) === 3)
    assert(loops(Seq(new Year(col), new Month(col), new Year(new ColumnRef(1))), 2, 0) === 2)
    // Task 58's debt closes on the way: weekofyear and yearofweek decompose the same shifted
    // day, so they share a method now rather than only the epilogue.
    val shift = new ThursdayOf(col)
    assert(loops(Seq(new WeekOfYear(shift), new Year(shift)), 1, 0) === 1)
  }

  test("whole-node reuse groups what a wider budget would, and nothing else") {
    // Clause 2 lets an output join a group past the budget when joining lets it skip work the
    // group already does. B2 wrote that for a civil-from-days prefix; `shareWholeNodes`
    // generalises it to any node the group holds, which is the same argument - a reused prefix
    // is reused nodes. The point is that it needs no wider budget: `groupBudget` bounds the
    // method, and it is what keeps compile time in hand (C1 refuses a loop method past about
    // 1900 bytes), so buying the CSE by raising it would loosen the wrong bound.
    //
    // The claim asserted here is method-for-method identity, in both directions: at the
    // shipped budget the rule emits exactly what a budget of 24 emits for the shapes that
    // share nodes, and exactly what the shipped budget emits for every shape that does not.
    // Method-level rather than whole-class, because `emitMulti` gives each class a fresh name
    // and the name is in the bytes.
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    def over(base: VarkaVectorIR, depth: Int, slotBase: Int): VarkaVectorIR = {
      var node = base
      for (level <- 0 until depth) {
        node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
        else new SubDays(node, new LiteralSlot(slotBase + level))
      }
      node
    }
    val shared8 = chain(8)
    val da1 = new AddDays(c0, new LiteralSlot(0))
    val rule = VarkaEmitOptions.DEFAULTS.withShareWholeNodes(true)
    val wider = VarkaEmitOptions.DEFAULTS.withGroupBudget(24)
    val shipped = VarkaEmitOptions.DEFAULTS.withShareWholeNodes(false)

    // Shapes that share nodes but no prefix, and whose merged weight straddles the budget:
    // the rule must emit what the wider budget emits.
    val sharing = Seq[(String, Seq[VarkaVectorIR], Int, Int)](
      ("task 17's two outputs over a shared chain",
        Seq(over(shared8, 6, 8), over(shared8, 6, 14)), 1, 20),
      ("three outputs over a shared chain",
        Seq(over(shared8, 4, 8), over(shared8, 4, 12), over(shared8, 4, 16)), 1, 20))
    // The loop methods of one emitted class, as (name -> code size) - what a grouping change
    // moves and a class name does not.
    def layout(bytes: (String, Array[Byte])): Seq[(String, Int)] =
      methodNames(bytes).filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked"))
        .sorted.map(m => m -> VarkaEmitterTestSupport.codeSize(bytes._2, m))

    for ((name, roots, inputs, lits) <- sharing) {
      assert(layout(emitMulti(roots, inputs, lits, rule))
        === layout(emitMulti(roots, inputs, lits, wider)),
        s"$name: the rule at the shipped budget should emit what a budget of 24 emits")
      assert(layout(emitMulti(roots, inputs, lits, rule))
        !== layout(emitMulti(roots, inputs, lits, shipped)),
        s"$name: the rule should change this shape, or the corpus has stopped exercising it")
    }

    // And everything else is untouched: nothing shared, sharing already served by clause 2,
    // sharing that fits the budget anyway, and outputs too heavy to join at any bound.
    val untouched = Seq[(String, Seq[VarkaVectorIR], Int, Int)](
      ("two plain chains over different columns", Seq(chain(6), over(c1, 6, 6)), 2, 12),
      ("year and month over one date, clause 2's own case",
        Seq[VarkaVectorIR](new Year(c0), new Month(c0)), 1, 0),
      ("year over two dates, nothing to share",
        Seq[VarkaVectorIR](new Year(c0), new Year(c1)), 2, 0),
      ("add_months over two dates, heavier than any bound",
        Seq[VarkaVectorIR](new AddMonths(c0, new LiteralSlot(0)),
          new AddMonths(c1, new LiteralSlot(0))), 2, 1),
      ("date_add and datediff over it, already one group", Seq(da1, new DateDiff(da1, c1)), 2, 1))
    for ((name, roots, inputs, lits) <- untouched) {
      assert(layout(emitMulti(roots, inputs, lits, rule))
        === layout(emitMulti(roots, inputs, lits, shipped)),
        s"$name: the rule reached a shape it has no reuse to act on")
    }
  }

  test("a budget change reaches only the shapes whose grouping it decides") {
    // The guard section 2.35 believed already existed and did not. B2's byte-identity test
    // above compares `shareChronoPrefix` off against on at ONE budget; nothing asserted that
    // moving the budget itself touches only what it should. Task 71 measured the cost of a
    // default change by moving it and running the suites - one assertion failed, at 64, and it
    // was the one that spells out "1 + 38 > 16" - which is the right cost and the wrong way to
    // learn it. This is the assertion.
    //
    // The corpus is shapes whose grouping no budget in the ladder can change: one output is
    // always one group whatever the budget, and outputs whose combined weight exceeds every
    // rung stay apart at all of them. A shape that legitimately regroups - two small disjoint
    // outputs, which a wider budget should merge - is deliberately not here, because it is
    // what the budget is for.
    val col = new ColumnRef(0)
    val corpus = Seq[(String, Seq[VarkaVectorIR], Int, Int)](
      ("one depth-8 chain, one output", Seq(chain(8)), 1, 8),
      // 38 ops against the shipped 16: a single output wider than the budget gets its own
      // group untouched, and stays one method when the budget grows past it.
      ("one calendar output, wider than the shipped budget", Seq[VarkaVectorIR](new Year(col)),
        1, 0),
      // add_months weighs 112 - wider than every rung, so it is its own group at all of them.
      ("one add_months, wider than every rung",
        Seq[VarkaVectorIR](new AddMonths(col, new LiteralSlot(0))), 1, 1),
      // Two calendar outputs over different dates: 38 + 38 against a top rung of 64, and no
      // prefix to reuse, so clause 1 splits them and clause 2 never opens.
      ("two calendar outputs over different dates",
        Seq[VarkaVectorIR](new Year(col), new Year(new ColumnRef(1))), 2, 0))
    val rungs = Seq(16, 24, 32, 48, 64)
    for ((name, roots, inputs, lits) <- corpus) {
      val emitted = rungs.map { budget =>
        budget -> emitMulti(roots, inputs, lits, VarkaEmitOptions.DEFAULTS.withGroupBudget(budget))
      }
      val (baseBudget, base) = emitted.head
      val loops = methodNames(base)
        .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked")).sorted
      for ((budget, bytes) <- emitted.tail) {
        assert(methodNames(bytes)
          .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked")).sorted === loops,
          s"$name: budget $budget grouped differently from $baseBudget, and this shape's " +
            "grouping is not the budget's to decide")
        for (method <- loops) {
          assert(VarkaEmitterTestSupport.codeSize(bytes._2, method)
            === VarkaEmitterTestSupport.codeSize(base._2, method),
            s"$name: $method changed size between budgets $baseBudget and $budget")
        }
      }
    }
  }

  test("with no prefix to reuse, sharing changes no loop method - the guard that " +
      "clause 2 admits fragment reuse and nothing else") {
    // Before B2 this test asserted every calendar loop method byte for byte unchanged under
    // sharing, which was the proof that no committed number could move; its own comment said
    // B2 would fail it and that the parity file then needs regenerating, which is what
    // happened. What B2 promises instead is that clause 2 reaches nothing but fragment reuse:
    // for shapes with no calendar prefix - the task-17 pair, a deep chain, the CASE WHEN and
    // greatest cases the parity file names, the mod-7 family - every loop method is byte for
    // byte identical with sharing on and off, method names and sizes alike. Asserted by
    // construction, so it holds whichever way task 17's split-versus-merged rows read.
    val col = new ColumnRef(0)
    def chainOver(base: VarkaVectorIR, depth: Int, slotBase: Int): VarkaVectorIR = {
      var node = base
      for (level <- 0 until depth) {
        node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
        else new SubDays(node, new LiteralSlot(slotBase + level))
      }
      node
    }
    val shared8 = chain(8)
    val corpus = Seq[(String, Seq[VarkaVectorIR], Int, Int)](
      ("task 17's two outputs over a shared chain",
        Seq(chainOver(shared8, 6, 8), chainOver(shared8, 6, 14)), 1, 20),
      ("depth-8 chain", Seq(chain(8)), 1, 8),
      ("CASE WHEN", Seq(new IfElse(new Compare(CompareOp.LT, col, new LiteralSlot(0)),
        new AddDays(col, new LiteralSlot(1)), new SubDays(col, new LiteralSlot(1)))), 1, 2),
      ("greatest and least", Seq(new Greatest(col, new ColumnRef(1)),
        new Least(col, new ColumnRef(1))), 2, 0),
      ("dayofweek and weekday", Seq(new DayOfWeek(col), new WeekDay(col)), 1, 0),
      ("next_day", Seq(new NextDay(col, new LiteralSlot(0))), 1, 1),
      ("datediff", Seq(new DateDiff(col, new ColumnRef(1))), 2, 0))
    for ((name, roots, inputs, lits) <- corpus) {
      val plain = emitMulti(roots, inputs, lits, unshared)
      val withSharing = emitMulti(roots, inputs, lits, sharing)
      val loops = methodNames(plain)
        .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked")).sorted
      assert(methodNames(withSharing)
        .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked")).sorted === loops,
        s"$name: sharing changed the loop-method layout of a shape with no prefix to share")
      for (method <- loops) {
        assert(VarkaEmitterTestSupport.codeSize(withSharing._2, method)
          === VarkaEmitterTestSupport.codeSize(plain._2, method),
          s"$name: $method changed size under sharing, so clause 2 reached a shape with no " +
            "prefix to reuse")
      }
    }
  }

  test("every calendar weight is the prefix plus the tail the emitter emits") {
    // The register PLAN_TASK_32.md 10.3 asked for, asserted off the class file the way the
    // task 53 and 54 registers are. Each calendar node alone emits its prefix plus its tail,
    // and beside month(d) in one loop method it adds exactly its tail - which is the
    // arithmetic clause 2 of groupOutputs sums against FUSED_CEILING. A lowering change that
    // moves a count fails here and names the constant to recount, rather than leaving a
    // weight to drift the way CHRONO_WEIGHT drifted from 50 to 40 without anything noticing.
    val col = new ColumnRef(0)
    def ops(roots: Seq[VarkaVectorIR], inputs: Int = 1, lits: Int = 0,
        options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Int =
      laneOps(emitMulti(roots, inputs, lits, options)._2, "loopDense0")
    val prefix = VarkaEmitBudget.CHRONO_PREFIX_WEIGHT
    // A prefix no tail in the group reads the month out of elides the month step (task 48).
    val prefixNoMonth = prefix - monthStepOps(VarkaEmitOptions.DEFAULTS)
    val month = new Month(col)
    val monthAlone = ops(Seq(month))
    assert(monthAlone === prefix + 4, "month(d), the partner every tail is measured beside")
    // Wide enough that clause 1 alone puts month(d) and the node in one method, so the pair
    // measures the fragment's arithmetic whether or not clause 2 exists yet.
    val wide = VarkaEmitOptions.DEFAULTS.withGroupBudget(400)
    // (name, node, its tail, whether its own prefix keeps the month step, literals, inputs)
    val register = Seq(
      ("year", new Year(col), 5, false, 0, 1),
      ("dayofmonth", new DayOfMonth(col), 5, true, 0, 1),
      ("quarter", new Quarter(col), 7, true, 0, 1),
      ("dayofyear", new DayOfYear(col), VarkaEmitBudget.DAY_OF_YEAR_TAIL_WEIGHT, false, 0, 1),
      ("last_day", new LastDay(col), VarkaEmitBudget.LAST_DAY_TAIL_WEIGHT, true, 0, 1),
      ("add_months", new AddMonths(col, new LiteralSlot(0)),
        VarkaEmitBudget.ADD_MONTHS_TAIL_WEIGHT, true, 1, 1),
      ("trunc YEAR", new TruncDate(col, TruncLevel.YEAR),
        VarkaEmitBudget.TRUNC_YEAR_TAIL_WEIGHT, false, 0, 1),
      ("trunc MONTH", new TruncDate(col, TruncLevel.MONTH),
        VarkaEmitBudget.TRUNC_MONTH_TAIL_WEIGHT, true, 0, 1),
      ("trunc QUARTER", new TruncDate(col, TruncLevel.QUARTER),
        VarkaEmitBudget.TRUNC_QUARTER_TAIL_WEIGHT, true, 0, 1),
      ("trunc dynamic", new TruncDateDynamic(col, new ColumnRef(1)),
        VarkaEmitBudget.TRUNC_DYNAMIC_TAIL_WEIGHT, true, 0, 2))
    for ((name, node, tail, readsMonth, lits, inputs) <- register) {
      val alone = ops(Seq(node), inputs, lits)
      val own = if (readsMonth) prefix else prefixNoMonth
      assert(alone === own + tail,
        s"$name alone emits $alone lane ops, not prefix $own + tail $tail - recount the constant")
      val paired = ops(Seq(month, node), inputs, lits, wide)
      assert(paired === monthAlone + tail,
        s"month(d) beside $name emits $paired lane ops, not month's $monthAlone + tail $tail")
    }
    // weekofyear decomposes the Thursday-shifted day, so its prefix is keyed on ThursdayOf(d)
    // and shares nothing with month(d)'s; the shift's own ops are the ThursdayOf node's weight.
    val shift = new ThursdayOf(col)
    assert(ops(Seq(new WeekOfYear(shift))) ===
      ops(Seq(shift)) + prefixNoMonth + VarkaEmitBudget.WEEK_OF_YEAR_TAIL_WEIGHT)
    // The four fields share one tail constant, at the widest of the four.
    assert(VarkaEmitBudget.CHRONO_FIELD_TAIL_WEIGHT === 7)
  }

  test("sharing the prefix moves the epilogue's HugeMethodLimit crossing, and the bitmap " +
    "pass moves " +
      "it again: unshared 21 to 22, shared 44 to 49") {
    // This is what step B1 is for, and the only thing it is for under today's grouping. The
    // epilogue is one method over *every* output by task 24's deliberate decision, so its size
    // grows with the whole projection rather than with a group. Four fields over one date
    // repeat the decomposition four times; sharing it is most of the method.
    //
    // The outputs must be distinct nodes to count: the IR's records compare by value, so
    // year(d) twice is one node and the emitter already emits it once. Four fields per date
    // over as many dates as the width needs is the shape task 44 measured.
    //
    // The unshared boundary has now moved three times, each for a different reason, which is
    // why it is re-measured here rather than reasoned about: task 44 recorded 16 fits/17
    // crosses; task 51 removed the per-extraction range guard, shrinking every emitted calendar
    // prefix, shared or not, to 18 fits/19 crosses (see PLAN_TASK_51.md section 4.1 for the
    // numbers that replaced); task 48 lets a Year node's own prefix skip the March-month step,
    // and unshared every Year node has its own prefix, so the epilogue's four-fields-per-date
    // shape loses one month step per date - 19 fits/20 crosses; task 54's Julian map takes a
    // division stage out of every prefix, shared or not, so unshared 20 fits (7675 bytes) and
    // 21 crosses (8336). Shared is still at 44 - 40 outputs fit in 7087 bytes and 44 cross at
    // 8063, down from 8630 - because the epilogue holds every output, so each date's fragment
    // has a Month consumer and keeps the month step, and the prefix it shares got shorter by
    // the same amount for every date. The ladder is in PLAN_TASK_54.md section 9. The limit
    // itself is HotSpot's HugeMethodLimit, past which it gives up on compiling the method at
    // all (interpreted, boxed vectors, on every batch whose length is not a lane multiple).
    def fields(dates: Int): Seq[VarkaVectorIR] = (0 until dates).flatMap { c =>
      val col = new ColumnRef(c)
      Seq[VarkaVectorIR](new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    }
    val limit = 8000
    // Task 70 (PLAN_TASK_70.md 9): with the bitmap pass on by default, every word in these
    // methods is dead, so epilogueMasked is epilogueDense's bytes and the crossing is the
    // dense epilogue's - unshared 21 fits (7563) and 22 crosses (8033); shared reaches
    // 49. The per-group arm keeps the old boundaries, asserted beside.
    assert(epilogueSize(fields(6).take(21), 12, unshared) < limit)
    assert(epilogueSize(fields(6).take(22), 12, unshared) > limit)
    assert(epilogueSize(fields(12), 12, sharing) < limit,
      "forty-eight shared outputs fit under the pass; the boundary is further out")
    assert(epilogueSize(fields((49 + 3) / 4).take(49 - 1), 13,
      sharing) < limit)
    val past = epilogueSize(fields((49 + 3) / 4).take(49), 13,
      sharing)
    assert(past > limit,
      s"49 shared calendar outputs now fit in $past bytes - the pass reaches " +
        "further than this test records, so PLAN_TASK_70.md 9's ladder is stale")
    // The reference variant: the boundaries task 54 left, 20/21 unshared and 44 shared.
    val perGroupUnshared = unshared.withValidityByBitmap(false)
    val perGroupShared = sharing.withValidityByBitmap(false)
    assert(epilogueSize(fields(5), 12, perGroupUnshared) < limit)
    assert(epilogueSize(fields(6).take(21), 12, perGroupUnshared) > limit)
    assert(epilogueSize(fields(10), 12, perGroupShared) < limit)
    assert(epilogueSize(fields(11), 12, perGroupShared) > limit)
  }

  test("the driver stays under HugeMethodLimit on the output ladder, with the pass " +
      "on and off, and the pass costs the 48-output driver what prediction 6 said") {
    // Nothing measured the driver before this task; it is one method for every output and the
    // one method every batch runs. Measured before the work: 2409 bytes at 44 outputs against
    // the epilogue's 8058, 2624 at 48 (PLAN_TASK_70.md 6.1). The pass adds about ten bytes
    // per served single-input output - one call with its operand pushes, less the zero it
    // replaces - so the 48-output driver was predicted under 500 bytes larger.
    def fields(dates: Int): Seq[VarkaVectorIR] = (0 until dates).flatMap { c =>
      val col = new ColumnRef(c)
      Seq[VarkaVectorIR](new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    }
    for (dates <- Seq(5, 11, 12); (base, layout) <- Seq((sharing, "shared"),
        (unshared, "unshared")); on <- Seq(true, false)) {
      val bytes = emitMulti(fields(dates), dates, 0, base.withValidityByBitmap(on))._2
      val driver = VarkaEmitterTestSupport.codeSize(bytes, "runMasked")
      assert(driver < 8000, s"$layout, $dates dates, pass=$on: runMasked is $driver bytes")
    }
    val off = VarkaEmitterTestSupport.codeSize(
      emitMulti(fields(12), 12, 0, sharing.withValidityByBitmap(false))._2, "runMasked")
    val on = VarkaEmitterTestSupport.codeSize(
      emitMulti(fields(12), 12, 0, sharing.withValidityByBitmap(true))._2, "runMasked")
    assert(on > off && on - off < 600,
      s"48 outputs: the driver went from $off to $on bytes; prediction 6 said under 500 more")
  }

  test("under the byte budget a loop method sets up only its group's outputs and literals, " +
      "and answers the same (task 87, step 3a)") {
    // PLAN_TASK_87.md 2.6.2: every loop method used to materialize the destination segments of
    // every output in the kernel and load every literal, so a group's bytes grew with the whole
    // kernel - the term a regroup could never shrink - and past 255 locals every load took a
    // wide prefix. Under the switch the group sets up what it writes and reads. The op count
    // is untouched by construction, the epilogue and the driver are untouched until their own
    // steps, and a kernel of one group has nothing to drop, so its bytes are identical.
    def ladder(n: Int): Seq[VarkaVectorIR] = (0 until n).map { k =>
      val col = new ColumnRef(0)
      new MakeDate(new Year(col), new Month(col), new LiteralSlot(k), true)
    }
    // 8000 is HotSpot's HugeMethodLimit; step 3 names it VarkaEmitBudget.HUGE_METHOD_LIMIT.
    val on = VarkaEmitOptions.DEFAULTS.withMethodByteBudget(8000)
    def sizes(n: Int, options: VarkaEmitOptions): Map[String, Int] = {
      val bytes = emitMulti(ladder(n), 1, n, options)._2
      VarkaEmitterTestSupport.methodNames(bytes).asScala.filter(_ != "<init>")
        .map(m => m -> VarkaEmitterTestSupport.codeSize(bytes, m)).toMap
    }
    def ops(n: Int, options: VarkaEmitOptions, method: String): Int =
      VarkaEmitterTestSupport.invocationCount(
        emitMulti(ladder(n), 1, n, options)._2, method, "jdk.incubator.vector.IntVector")

    val (off60, on60) = (sizes(60, VarkaEmitOptions.DEFAULTS), sizes(60, on))
    for (m <- off60.keys if m.startsWith("loop")) {
      assert(on60(m) < off60(m), s"$m: ${off60(m)} -> ${on60(m)} bytes, expected smaller")
      assert(ops(60, on, m) === ops(60, VarkaEmitOptions.DEFAULTS, m), s"$m: the op count moved")
    }
    for (m <- Seq("epilogueDense", "epilogueMasked", "runDense", "runMasked")) {
      assert(on60(m) === off60(m), s"$m is not a loop method and must not move in this step")
    }
    assert(sizes(4, on) === sizes(4, VarkaEmitOptions.DEFAULTS), "one group: nothing to drop")

    // The same answers as the reference evaluator, on both bodies, at ragged and even lengths.
    // `forceMasked` reports one null over a full-set bitmap to reach the masked body, and at
    // length 1 that one null is the whole batch: the kernel marks the input dead and nulls every
    // output, which is right for what it was told. So the forced arm starts at 7; the null
    // pattern covers the masked body at length 1 honestly, with a null the reference also sees.
    val lits = (1 to 16).toArray
    val patterns = Seq(Seq((_: Int) => false), Seq((i: Int) => i % 3 == 0))
    val lengths = Seq(1, 7, 16, 17, 100)
    checkMatrix(ladder(16), 1, lits, lengths, patterns, options = on,
      ctx = "per-group prologue")
    checkMatrix(ladder(16), 1, lits, lengths.filter(_ > 1), patterns, options = on,
      forceMasked = true, ctx = "per-group prologue, masked")
  }
}
