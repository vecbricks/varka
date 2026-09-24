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

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.time.LocalDate

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils

/**
 * The calendar family (`VarkaChronoLowering`): the day-of-week arithmetic, the extractions over
 * the shared civil-from-days prefix, `trunc` in both forms, `make_date`, `weekofyear`, `last_day`,
 * `add_months`, prefix sharing between siblings, and the range guards on the day producers those
 * nodes read - against `LocalDate`, `DateTimeUtils` and the reference evaluator.
 */
class VarkaEmitterChronoSuite extends VarkaEmitterTestBase {

  /** Runs one kernel over one input column, returning the batch status it reports. */
  private def runKernel(
      kernel: VarkaFusedKernel,
      input: Col,
      out: (MemorySegment, MemorySegment),
      length: Int): Int =
    kernel.run(
      Array(input.data.address()), Array(input.validity.address()), Array(input.nullCount),
      Array(out._1.address()), Array(out._2.address()), Array.empty[Int], length)

  test("neither next_day's weekday nor add_months' month count trips " +
      "analysis anymore, now that both widened from a literal-only offset to a column") {
    // The check that used to reject both nodes together (and whose message the IR fuzzer's
    // first failure quoted for the wrong one, #110) required a literal for either operand.
    // Task 59 widened next_day's weekday to a column (the evaluator's derived leaf) and task 60
    // widened add_months' month count the same way (task 38's AddDays/SubDays offset shape);
    // with both landed, requireLiteralOffset has no caller left and is gone, so neither shape
    // is rejected at analysis - each is exercised in full (values, nulls, cost) by its own
    // task's tests below.
    val (_, months) = emitMulti(Seq(new AddMonths(new ColumnRef(0), new ColumnRef(1))), 2, 0)
    assert(months.nonEmpty)
    val (_, weekday) = emitMulti(Seq(new NextDay(new ColumnRef(0), new ColumnRef(1))), 2, 0)
    assert(weekday.nonEmpty)
    // What replaced it still fires, and still names the operand that failed. Widening the two
    // nodes removed the literal requirement, not the shape requirement: an arbitrary subtree in
    // either position is a compiler bug the emitter refuses rather than emits. The message is
    // asserted per operand because one message shared across four operands is what sent #110
    // looking for a next_day the shape did not contain - the whole reason the name is a
    // parameter. Without an assertion here, dropping any of the four calls keeps the suite green.
    val badCount = intercept[IllegalArgumentException](
      emitMulti(Seq(new AddMonths(new ColumnRef(0), new Year(new ColumnRef(0)))), 1, 0))
    assert(badCount.getMessage.contains("add_months' month count"), badCount.getMessage)
    val badWeekday = intercept[IllegalArgumentException](
      emitMulti(Seq(new NextDay(new ColumnRef(0), new Year(new ColumnRef(0)))), 1, 0))
    assert(badWeekday.getMessage.contains("next_day's weekday"), badWeekday.getMessage)
    val badOffset = intercept[IllegalArgumentException](
      emitMulti(Seq(new AddDays(new ColumnRef(0), new Year(new ColumnRef(0)))), 1, 0))
    assert(badOffset.getMessage.contains("date_add's day offset"), badOffset.getMessage)
    val badSubOffset = intercept[IllegalArgumentException](
      emitMulti(Seq(new SubDays(new ColumnRef(0), new Year(new ColumnRef(0)))), 1, 0))
    assert(badSubOffset.getMessage.contains("date_sub's day offset"), badSubOffset.getMessage)
  }

  test("the month count takes int arithmetic, next_day's weekday still does not") {
    // Task 68 split `requireOffsetShape` in two. The month count and the weekday shared it
    // under one sentence - that each "carries a runtime bound a derived value cannot declare" -
    // which is true of the weekday and false of the count: a column-count `AddMonths` is in
    // `selfGuarding` and is checked at run time against MONTH_ARITH_MIN/MAX_MONTHS by a
    // lanewise test on the count's own value, which cares nothing about what produced it. The
    // weekday has no such guard, so a derived value there reaches `emitFloorMod7` unchecked.
    //
    // Both directions are asserted, because a split made on one side only is a ghost fallback
    // on the other - the compiler admitting what the emitter refuses, or the emitter admitting
    // what nothing guards.
    for (count <- Seq(new IntNeg(Overflow.FAIL, new ColumnRef(1)),
        new IntArith(IntOp.MUL, Overflow.WRAP, new ColumnRef(1), new LiteralSlot(0)))) {
      val (_, bytes) = emitMulti(Seq(new AddMonths(new ColumnRef(0), count)), 2, 1)
      assert(bytes.nonEmpty, s"the month count should hold $count")
    }
    val badWeekday = intercept[IllegalArgumentException](emitMulti(
      Seq(new NextDay(new ColumnRef(0), new IntNeg(Overflow.FAIL, new ColumnRef(1)))), 2, 0))
    assert(badWeekday.getMessage.contains("next_day's weekday"), badWeekday.getMessage)
  }

  test("the re-armed check fires on the composed day, in every body") {
    // The runtime half of task 93. The compiler admits year(add_months(date_add(d, i), i))
    // because it inserts a check between the month add and the decomposition; this is that
    // check doing its job, built here as IR rather than through the compiler so the emitter is
    // tested on its own.
    //
    // The value the check sees is d + offset + 31-ish * count, and only lanes outside the
    // narrowed range condemn the batch. A lane the validity word says is null must not,
    // because a null lane holds whatever the column held and the batch is still answerable.
    val guarded = new GuardedDay(
      new AddMonths(new AddDays(new ColumnRef(0), new ColumnRef(1)), new ColumnRef(2)))
    val root = new Year(guarded)
    val (kernel, loader) = load(emitMulti(Seq(root), 3, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        def status(length: Int, day: Int => Int, off: Int => Int, count: Int => Int,
            nullAt: Int => Boolean): Int = {
          val d = makeInputData(arena, length, nullAt, day, poisonNulls = false)
          val o = makeInputData(arena, length, _ => false, off, poisonNulls = false)
          val c = makeInputData(arena, length, _ => false, count, poisonNulls = false)
          runKernel3(kernel, d, o, c, makeOutput(arena, length), length)
        }
        val none = (_: Int) => false
        // Everything small: in range, computed.
        assert(status(64, _ => 0, _ => 1, _ => 1, none) === 0)
        // Lane 5 asks for the largest month count the count guard allows over a day already at
        // the top of the guarded range: the composed day leaves the range and the batch is
        // condemned. In a loop lane...
        val far = (i: Int) => if (i == 5) VarkaChrono.MONTH_ARITH_MAX_MONTHS else 0
        assert(status(64, _ => VarkaChrono.NARROW_MAX_DAYS, _ => 0, far, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE, "a loop lane")
        // ... and in an epilogue lane, where the bounds mask has to let it through.
        assert(status(17, _ => VarkaChrono.NARROW_MAX_DAYS, _ => 0,
          i => if (i == 16) VarkaChrono.MONTH_ARITH_MAX_MONTHS else 0, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE, "an epilogue lane")
        // The same lane under a null date does not condemn: the word masks it out.
        assert(status(64, _ => VarkaChrono.NARROW_MAX_DAYS, _ => 0, far, _ == 5) === 0,
          "a null lane must not condemn the batch")
        // Downward too, which is the direction the lowering is not exact in at all.
        assert(status(64, _ => VarkaChrono.NARROW_MIN_DAYS, _ => 0,
          i => if (i == 7) VarkaChrono.MONTH_ARITH_MIN_MONTHS else 0, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE, "below the floor")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the month-count guard covers a derived count, which is why the split is safe") {
    // The claim the split rests on, tested rather than asserted: the guard reads the count's
    // lanes after the arithmetic, so a count that only leaves the range *because* of the
    // negation still condemns the batch. Without this the split would be a way to smuggle an
    // unguarded count past task 60.
    val root = new AddMonths(new ColumnRef(0), new IntNeg(Overflow.WRAP, new ColumnRef(1)))
    val (kernel, loader) = load(emitMulti(Seq(root), 2, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        def status(count: Int => Int, length: Int): Int = {
          val dates = makeInputData(arena, length, _ => false, _ => 0, poisonNulls = false)
          val counts = makeInputData(arena, length, _ => false, count, poisonNulls = false)
          runKernel2(kernel, dates, counts, makeOutput(arena, length), length)
        }
        // In range after negation: computed.
        assert(status(i => -(i % 100), 64) === 0)
        // Lane 3 negates to one month past MONTH_ARITH_MAX_MONTHS, so the guard must fire -
        // and the input itself, -24565, is inside the range, so only the derived value is out.
        assert(status(i => if (i == 3) -(VarkaChrono.MONTH_ARITH_MAX_MONTHS + 1) else 0, 64) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("next_day with a column weekday matches the reference evaluator over every " +
      "null pattern of both columns, in and out of the leaf's range") {
    // The trap is task 38's again: the node's word used to alias the date's alone, which was
    // right only while the weekday was always a literal. combos(2) drives every (date,
    // weekday) null-pattern pair, the null-weekday-on-a-live-date one included. The weekday
    // column cycles through -2 .. 6, so the leaf's whole range -1 .. 5 and a value either side
    // of it are covered: the lowering is exact for every int k, and the reference is Spark's
    // own formula, so out-of-range values are as checkable as in-range ones.
    val root = new NextDay(new ColumnRef(0), new ColumnRef(1))
    def data(c: Int, i: Int): Int = if (c == 0) i * 997 - 300000 else i % 9 - 2
    checkMatrix(Seq(root), 2, Array.emptyIntArray, Seq(1, 13, 17, 64, 65, 1000), combos(2),
      data = data, ctx = "next_day column weekday")
  }

  test("the column and literal next_day forms cost what PLAN_TASK_59.md 3.3 " +
      "registered, and the literal form's bytes did not move") {
    val literal = emitMulti(Seq(new NextDay(new ColumnRef(0), new LiteralSlot(0))), 1, 1)._2
    val column = emitMulti(Seq(new NextDay(new ColumnRef(0), new ColumnRef(1))), 2, 0)._2
    assert(laneOps(literal, "loopDense0") === 18, "the literal form")
    assert(laneOps(column, "loopDense0") === 18, "the column form")
  }

  test("dayofweek and weekday match floorMod and LocalDate across extreme and negative days") {
    val roots = Seq[VarkaVectorIR](
      new DayOfWeek(new ColumnRef(0)), new WeekDay(new ColumnRef(0)))
    // The 15-bit fold boundaries are edges of the shipped magic-multiply lowering.
    val extremes = Array(Int.MinValue, Int.MaxValue, Int.MinValue + 1, Int.MaxValue - 1,
      -1, 0, 1, -7, 7, -8, 8, Int.MaxValue - 3, Int.MinValue + 3,
      32767, 32768, -32768, -32769)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 997 - 300000
    checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "dow")
    // The independent oracle behind the reference: Spark's DateTimeUtils formula through
    // LocalDate, valid for every int epoch day.
    for (v <- extremes) {
      val viaLocalDate = java.time.LocalDate.ofEpochDay(v).getDayOfWeek.plus(1).getValue
      assert((Math.floorMod(v, 7) + 4) % 7 + 1 === viaLocalDate, s"oracle self-check v=$v")
    }
  }

  test("next_day matches Spark's own wrapping formula for every weekday, at the extremes") {
    // One root per weekday offset (k = dayOfWeek - 1). DateTimeUtils.getDayOfWeekFromString
    // returns [0, 6] with THURSDAY = 0 .. WEDNESDAY = 6, so k itself ranges over [-1, 5], not
    // [0, 6] - THURSDAY's k = -1 is the one value a naive 0-to-6 sweep would miss (caught by
    // this task's code review). All seven share one emitted class and one literal-slot array
    // - the point of "k is a runtime literal" (section 2).
    val roots = (0 to 6).map(slot => new NextDay(new ColumnRef(0), new LiteralSlot(slot)))
    val lits = Array(-1, 0, 1, 2, 3, 4, 5)
    // The 15-bit fold boundaries are edges of the shared floorMod7 lowering; the rest probe
    // the deliberate k - d overflow (section 2) near both ends of the int range.
    val extremes = Array(Int.MinValue, Int.MaxValue, Int.MinValue + 1, Int.MaxValue - 1,
      -1, 0, 1, -7, 7, -8, 8, Int.MaxValue - 3, Int.MinValue + 3,
      32767, 32768, -32768, -32769)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 997 - 300000
    checkMatrix(roots, 1, lits, Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "next_day")
    // The independent oracle behind the reference: Spark's own getNextDateForDayOfWeek,
    // which wraps in plain int arithmetic - checked against the reduce-first form the recipe
    // warns is wrong, to confirm the two really do disagree at the boundary it names.
    def spark(startDay: Int, dayOfWeek: Int): Int =
      startDay + 1 + ((dayOfWeek - 1 - startDay) % 7 + 7) % 7
    def reduceFirst(startDay: Int, k: Int): Int =
      startDay + 1 + Math.floorMod(k - Math.floorMod(startDay, 7), 7)
    assert(spark(Int.MinValue, 3) === -2147483647, "oracle self-check")
    assert(reduceFirst(Int.MinValue, 2) === -2147483643, "reduce-first disagrees as documented")
    assert(spark(Int.MinValue, 3) !== reduceFirst(Int.MinValue, 2))
  }

  test("the calendar extractions match LocalDate over the range they cover") {
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)))
    checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay, ctx = "narrowed")
  }

  test("both prefix forms match LocalDate over the calendar boundaries, last_day too") {
    // The Julian map and the century-then-year split, each held to LocalDate over the same
    // boundary set on every calendar tail plus last_day, whose month-length arithmetic reads
    // the prefix's year. Agreeing with LocalDate here is also them agreeing with each other,
    // which is what keeps whichever is not the default a live reference variant rather than
    // dead code - the same discipline as FloorMod7 and task 53's month axis.
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)), new LastDay(new ColumnRef(0)),
      new TruncDate(new ColumnRef(0), TruncLevel.YEAR),
      new TruncDate(new ColumnRef(0), TruncLevel.MONTH),
      new TruncDate(new ColumnRef(0), TruncLevel.QUARTER))
    for (julian <- Seq(true, false)) {
      checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"julianMap=$julian", options = VarkaEmitOptions.DEFAULTS.withJulianMap(julian))
    }
  }

  private val truncForms = Seq(VarkaEmitOptions.TruncDateForm.SUBTRACT,
    VarkaEmitOptions.TruncDateForm.RECOMPOSE)

  test("trunc matches DateTimeUtils.truncDate over the calendar boundaries, under " +
      "both lowerings and both prefix forms, and its date output feeds further arithmetic") {
    // The boundary set is the calendar family's: year and era edges, February in leap, common
    // and century years, every month-length boundary, and the covered range's own ends. The
    // reference is DateTimeUtils.truncDate, the definition. The two lowerings agreeing with it
    // is them agreeing with each other, which keeps whichever is not the default a live
    // reference variant (FloorMod7's precedent). The date_add over the MONTH form is the
    // DateType output surviving a second operation in the same chain, which the milestone
    // row asks for and which a single-column test cannot show.
    val chained = new AddDays(new TruncDate(new ColumnRef(0), TruncLevel.MONTH),
      new LiteralSlot(0))
    for (form <- truncForms; julian <- Seq(true, false); neri <- Seq(true, false)) {
      val options = VarkaEmitOptions.DEFAULTS.withTruncDate(form).withJulianMap(julian)
        .withNeriSchneiderMonth(neri)
      checkMatrix(truncRoots :+ chained, 1, Array(5), Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"trunc $form julianMap=$julian neri=$neri", options = options)
    }
  }

  test("every trunc level and every month of two years, quarter starts included") {
    // Day-by-day over 2023 (common) and 2024 (leap), so every quarter start and every month
    // start is crossed in both year kinds rather than sampled - the four-way quarter select
    // and the leap-adjusted starts are what this sweep is for.
    val start = LocalDate.of(2023, 1, 1).toEpochDay.toInt
    val days = (0 until 731).map(start + _)
    def day(c: Int, i: Int): Int = if (i < days.length) days(i) else i * 9973 - 400000
    for (form <- truncForms) {
      checkMatrix(truncRoots, 1, Array.empty[Int], Seq(731),
        nullPatterns.map(p => Seq(p._2)), data = day, ctx = s"trunc two years $form",
        options = VarkaEmitOptions.DEFAULTS.withTruncDate(form))
    }
  }

  test("trunc shares the calendar prefix with a sibling extraction over the same date") {
    // trunc(d, 'MONTH') beside year(d) in one loop method runs the civil-from-days prefix once,
    // asserted the way task 32's own tests do: the shared kernel's dense loop carries fewer
    // IntVector calls than the unshared one, by at least the prefix's own op count.
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)),
      new TruncDate(new ColumnRef(0), TruncLevel.MONTH))
    val wide = VarkaEmitOptions.DEFAULTS.withGroupBudget(200)
    val shared = laneOps(emitMulti(roots, 1, 0, wide)._2, "loopDense0")
    val unshared = laneOps(emitMulti(roots, 1, 0, wide.withShareChronoPrefix(false))._2,
      "loopDense0")
    assert(unshared - shared >= 20,
      s"expected the prefix to be shared: $shared IntVector ops shared vs $unshared unshared")
  }

  private val makeDateAnsi =
    new MakeDate(new ColumnRef(0), new ColumnRef(1), new ColumnRef(2), true)

  /** Runs a three-input kernel with one output, returning the batch status. */
  private def runKernel3(kernel: VarkaFusedKernel, a: Col, b: Col, c: Col,
      out: (MemorySegment, MemorySegment), length: Int): Int =
    kernel.run(
      Array(a.data.address(), b.data.address(), c.data.address()),
      Array(a.validityAddress(length), b.validityAddress(length), c.validityAddress(length)),
      Array(a.nullCount, b.nullCount, c.nullCount),
      Array(out._1.address()), Array(out._2.address()), Array.empty[Int], length)

  test("make_date matches LocalDate.of over the validity corners - nulls for invalid " +
      "dates under the NULL form, the valid triples under both forms - at every length and " +
      "null pattern of its three inputs") {
    // The NULL form runs every triple: an invalid date is a null output and the status stays 0.
    checkMatrix(Seq(makeDateNull), 3, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      combos(3), data = tripleData(makeDateAll), ctx = "NULL form")
    // The ANSI form over the valid triples alone; its invalid rows are the status test below.
    for (root <- Seq(makeDateNull, makeDateAnsi)) {
      checkMatrix(Seq(root), 3, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        combos(3), data = tripleData(makeDateValid),
        ctx = s"valid triples, ansi=${root.failOnError()}")
    }
  }

  test("an invalid date under the ANSI form declines the batch, in a loop lane and " +
      "an epilogue lane, and not under a null input; a year past the limit declines under " +
      "both forms and is not confused with an invalid date") {
    val (ansi, loaderA) = load(emitMulti(Seq(makeDateAnsi), 3, 0))
    val (nul, loaderN) = load(emitMulti(Seq(makeDateNull), 3, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        val none = (_: Int) => false
        // Valid everywhere except lane `at`, which gets `bad`.
        def run(k: VarkaFusedKernel, length: Int, at: Int, bad: (Int, Int, Int),
            nullY: Int => Boolean, nullM: Int => Boolean, nullD: Int => Boolean): Int = {
          def pick(c: Int)(i: Int): Int = if (i == at) {
            if (c == 0) bad._1 else if (c == 1) bad._2 else bad._3
          } else tripleData(makeDateValid)(c, i)
          // `pick` places `bad` at lane `at`, and these cases null that same lane on purpose,
          // so the boundary value must survive rather than be replaced by a poison extreme.
          val y = makeInputData(arena, length, nullY, pick(0), poisonNulls = false)
          val m = makeInputData(arena, length, nullM, pick(1), poisonNulls = false)
          val d = makeInputData(arena, length, nullD, pick(2), poisonNulls = false)
          runKernel3(k, y, m, d, makeOutput(arena, length), length)
        }
        val feb30 = (2024, 2, 30)
        val farYear = (VarkaChrono.MAKE_DATE_MAX_YEAR + 1, 6, 15)
        val earlyYear = (VarkaChrono.MAKE_DATE_MIN_YEAR - 1, 6, 15)
        val farAndInvalid = (VarkaChrono.MAKE_DATE_MAX_YEAR + 1, 13, 1)
        // In range and valid: both forms run.
        assert(run(ansi, 64, -1, feb30, none, none, none) === 0)
        assert(run(nul, 64, -1, feb30, none, none, none) === 0)
        // An invalid date: the ANSI form declines (dense body, then masked, then epilogue).
        val declined = VarkaFusedKernel.STATUS_CHRONO_RANGE
        assert(run(ansi, 64, 3, feb30, none, none, none) === declined)
        assert(run(ansi, 64, 3, feb30, _ == 40, none, none) === declined)
        assert(run(ansi, 17, 16, feb30, none, none, none) === declined)
        // ... and does not decline it under a null in any of the three inputs.
        assert(run(ansi, 64, 3, feb30, _ == 3, none, none) === 0)
        assert(run(ansi, 64, 3, feb30, none, _ == 3, none) === 0)
        assert(run(ansi, 64, 3, feb30, none, none, _ == 3) === 0)
        // The NULL form never declines an invalid date.
        assert(run(nul, 64, 3, feb30, none, none, none) === 0)
        assert(run(nul, 17, 16, feb30, none, none, none) === 0)
        // A year past either limit declines under both forms, and a null there does not.
        for ((k, name) <- Seq((ansi, "ansi"), (nul, "null")); bad <- Seq(farYear, earlyYear)) {
          assert(run(k, 64, 5, bad, none, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE,
            s"$name $bad in a loop lane")
          assert(run(k, 17, 16, bad, none, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE,
            s"$name $bad in an epilogue lane")
          assert(run(k, 64, 5, bad, _ == 5, none, none) === 0, s"$name $bad under a null year")
        }
        // Out of range with an invalid month is a decline, not a null, under the NULL form too.
        assert(run(nul, 64, 5, farAndInvalid, none, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
      } finally {
        arena.close()
      }
    } finally {
      loaderA.release()
      loaderN.release()
    }
  }

  test("a null-free batch with an invalid date under the NULL form yields a null " +
      "lane - the dense fast path is not taken by a kernel that nulls a valid input") {
    val (nul, loader) = load(emitMulti(Seq(makeDateNull), 3, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        val length = 64
        val none = (_: Int) => false
        def pick(c: Int)(i: Int): Int =
          if (i == 9) { if (c == 0) 2024 else if (c == 1) 2 else 30 }
          else tripleData(makeDateValid)(c, i)
        val y = makeInputData(arena, length, none, pick(0))
        val m = makeInputData(arena, length, none, pick(1))
        val d = makeInputData(arena, length, none, pick(2))
        val out = makeOutput(arena, length)
        assert(runKernel3(nul, y, m, d, out, length) === 0)
        val bits = out._2
        def valid(i: Int): Boolean = (bits.get(ValueLayout.JAVA_BYTE, i / 8) >> (i % 8) & 1) == 1
        assert(!valid(9), "the invalid date must be a null lane")
        assert((0 until length).filter(_ != 9).forall(valid), "every other lane is valid")
        val expected: (Int, Int) => Int = tripleData(makeDateValid)
        assert(out._1.get(ValueLayout.JAVA_INT, 10 * 4L) ===
          VarkaChrono.makeDate(expected(0, 10), expected(1, 10), expected(2, 10)))
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the NULL form's kernel has no dense methods and the ANSI form's has both") {
    val nulNames = methodNames(emitMulti(Seq(makeDateNull), 3, 0))
    assert(!nulNames.contains("runDense") && !nulNames.contains("loopDense0"), nulNames)
    assert(nulNames.contains("runMasked") && nulNames.contains("loopMasked0"), nulNames)
    val ansiNames = methodNames(emitMulti(Seq(makeDateAnsi), 3, 0))
    assert(ansiNames.contains("runDense") && ansiNames.contains("runMasked"), ansiNames)
  }

  test("make_date costs what PLAN_TASK_42.md 3.6 registered under both forms, and " +
      "no sibling moved") {
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, inputs: Int, literals: Int = 0,
        method: String = "loopDense0"): Int =
      laneOps(emitMulti(Seq(root), inputs, literals, VarkaEmitOptions.DEFAULTS)._2, method)
    val counts = Seq(
      ("make_date ANSI, dense loop", ops(makeDateAnsi, 3), 57),
      ("make_date ANSI, masked loop", ops(makeDateAnsi, 3, method = "loopMasked0"), 57),
      ("make_date NULL, masked loop", ops(makeDateNull, 3, method = "loopMasked0"), 57),
      ("add_months", ops(new AddMonths(col, new LiteralSlot(0)), 1, literals = 1), 112),
      ("dayofyear", ops(new DayOfYear(col), 1), 43))
    val table = counts.map { case (n, got, want) => s"$n=$got (registered $want)" }
    assert(counts.forall { case (_, got, want) => got == want },
      s"the register moved; re-pin from these IntVector counts:\n  " + table.mkString("\n  "))
  }

  test("weekofyear matches IsoFields over the ISO corners, Velox's fixtures and the " +
      "calendar boundaries, under both prefix forms and every mod-7 lowering") {
    // The shift alone and Year over it (task 58's shape) ride along: the oracle for the
    // shift is java.time's own adjuster, and Year over the Thursday is the ISO week-based
    // year, both from the definition rather than from the lowering.
    val thursday = new ThursdayOf(new ColumnRef(0))
    val roots = Seq[VarkaVectorIR](new WeekOfYear(thursday), thursday, new Year(thursday))
    for (julian <- Seq(true, false); mod <- VarkaEmitOptions.FloorMod7.values()) {
      val options = VarkaEmitOptions.DEFAULTS.withJulianMap(julian).withFloorMod7(mod)
      checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = isoWeekDay,
        ctx = s"julian=$julian mod=$mod", options = options)
    }
  }

  test("weekofyear matches IsoFields on every day from 1990-12-20 to 2030-01-10") {
    // Forty year boundaries in both directions. The Thursday rule claims the boundaries are
    // automatic; this is the check, at a length that puts every one of them in a loop lane
    // and at one that leaves some in a tail lane.
    val start = LocalDate.of(1990, 12, 20).toEpochDay.toInt
    val end = LocalDate.of(2030, 1, 10).toEpochDay.toInt
    val roots = Seq[VarkaVectorIR](new WeekOfYear(new ThursdayOf(new ColumnRef(0))))
    checkMatrix(roots, 1, Array.empty[Int], Seq(end - start + 1, 4093),
      nullPatterns.map(p => Seq(p._2)), data = (_, i) => start + i, ctx = "dense")
  }

  test("WeekOfYear over anything but a ThursdayOf is refused at analysis") {
    // The lowering is the ISO week of a Thursday only; the compiler builds the pair, and the
    // emitter refuses any other tree rather than emitting a plausible wrong week.
    val col = new ColumnRef(0)
    val lit = new LiteralSlot(0)
    for (child <- Seq[VarkaVectorIR](col, new AddDays(col, lit), new NextDay(col, lit))) {
      val e = intercept[IllegalArgumentException](emitMulti(Seq(new WeekOfYear(child)), 1, 1))
      assert(e.getMessage.contains("WeekOfYear's child must be a ThursdayOf"), e.getMessage)
    }
  }

  test("the Thursday shift and the week tail cost what PLAN_TASK_37.md 3.3 " +
      "registered, and adding the nodes moved no sibling's bytes") {
    // Off the class file, like the task 35 register, at the shipped options.
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, literals: Int = 0): Int =
      laneOps(emitMulti(Seq(root), 1, literals, VarkaEmitOptions.DEFAULTS)._2, "loopDense0")
    val counts = Seq(
      ("ThursdayOf", ops(new ThursdayOf(col)), 19),
      ("weekofyear", ops(new WeekOfYear(new ThursdayOf(col))), 64),
      ("yearofweek", ops(new Year(new ThursdayOf(col))), 51),
      ("next_day", ops(new NextDay(col, new LiteralSlot(0)), literals = 1), 18),
      ("weekday", ops(new WeekDay(col)), 17),
      ("dayofyear", ops(new DayOfYear(col)), 43),
      ("month", ops(new Month(col)), 35),
      ("dayofmonth", ops(new DayOfMonth(col)), 36))
    val table = counts.map { case (n, got, want) => s"$n=$got (registered $want)" }
    assert(counts.forall { case (_, got, want) => got == want },
      s"the register moved; re-pin from these dense-loop IntVector counts:\n  " +
        table.mkString("\n  "))
  }

  test("dayofweek_iso matches getWeekDay + 1 over two whole weeks and the calendar " +
      "boundaries, under every mod-7 lowering") {
    // A full week around 1970-01-01 and one around 2024-01-01, so the Sunday wrap (7, never 0)
    // is in a loop lane and a tail lane, plus the boundary set at both ends of the range.
    val week1970 = (-4 to 3).toArray
    val week2024 = (0 to 7).map(i => LocalDate.of(2024, 1, 1).toEpochDay.toInt + i).toArray
    val days = week1970 ++ week2024 ++ calendarBoundaryDays
    def data(c: Int, i: Int): Int = if (i < days.length) days(i) else i * 9973 - 400000
    val roots = Seq[VarkaVectorIR](new DayOfWeekIso(new ColumnRef(0)),
      new WeekDay(new ColumnRef(0)), new DayOfWeek(new ColumnRef(0)))
    for (mod <- VarkaEmitOptions.FloorMod7.values()) {
      checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = data, ctx = s"mod=$mod",
        options = VarkaEmitOptions.DEFAULTS.withFloorMod7(mod))
    }
  }

  test("dayofweek_iso costs weekday plus one, and neither sibling moved") {
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR): Int =
      laneOps(emitMulti(Seq(root), 1, 0, VarkaEmitOptions.DEFAULTS)._2, "loopDense0")
    val counts = Seq(
      ("dayofweek_iso", ops(new DayOfWeekIso(col)), 18),
      ("weekday", ops(new WeekDay(col)), 17),
      ("dayofweek", ops(new DayOfWeek(col)), 18))
    val table = counts.map { case (n, got, want) => s"$n=$got (registered $want)" }
    assert(counts.forall { case (_, got, want) => got == want },
      s"the register moved; re-pin from these dense-loop IntVector counts:\n  " +
        table.mkString("\n  "))
  }

  test("the week tail and Year over one ThursdayOf share a prefix, and neither " +
      "shares with year over the bare date") {
    // weekofyear(d) and yearofweek(d) (task 58) in one loop method decompose the Thursday
    // once, asserted the way the task 32 and 35 sharing tests are; year(d) beside them runs
    // its own prefix over the date, which is the cost row 37 says a mixed projection pays.
    val col = new ColumnRef(0)
    val thursday = new ThursdayOf(col)
    val pair = Seq[VarkaVectorIR](new WeekOfYear(thursday), new Year(thursday))
    val wide = VarkaEmitOptions.DEFAULTS.withGroupBudget(200)
    val shared = laneOps(emitMulti(pair, 1, 0, wide)._2, "loopDense0")
    val unshared = laneOps(emitMulti(pair, 1, 0, wide.withShareChronoPrefix(false))._2,
      "loopDense0")
    assert(unshared - shared >= 20,
      s"expected the prefix to be shared: $shared IntVector ops shared vs $unshared unshared")
    val weekAlone = laneOps(emitMulti(Seq(pair.head), 1, 0, wide)._2, "loopDense0")
    assert(shared - weekAlone < 10,
      s"Year over the shared shift should cost under ten ops more: $shared vs $weekAlone")
    val withYear = laneOps(emitMulti(pair :+ new Year(col), 1, 0, wide)._2, "loopDense0")
    assert(withYear - shared >= 20,
      s"year(d) beside the pair should run its own prefix: $withYear vs $shared")
  }

  test("the trunc tails cost what PLAN_TASK_35.md section 8 registered, per level " +
      "and form, and adding the node moved no other node's bytes") {
    // Off the class file, like the task 53 and 54 registers, at the shipped prefix options.
    // The DayOfYear arm was refactored onto emitJanuaryDayOfYear for this task, so its count
    // is pinned too: the extraction's bytes must not have moved.
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, form: VarkaEmitOptions.TruncDateForm): Int =
      laneOps(emitMulti(Seq(root), 1, 0, VarkaEmitOptions.DEFAULTS.withTruncDate(form))._2,
        "loopDense0")
    val dayOfYear = ops(new DayOfYear(col), VarkaEmitOptions.TruncDateForm.SUBTRACT)
    val month = ops(new Month(col), VarkaEmitOptions.TruncDateForm.SUBTRACT)
    val dayOfMonth = ops(new DayOfMonth(col), VarkaEmitOptions.TruncDateForm.SUBTRACT)
    val registered = Seq(
      ("dayofyear", new DayOfYear(col), VarkaEmitOptions.TruncDateForm.SUBTRACT, 43),
      ("trunc YEAR, subtract", new TruncDate(col, TruncLevel.YEAR),
        VarkaEmitOptions.TruncDateForm.SUBTRACT, 45),
      ("trunc MONTH, subtract", new TruncDate(col, TruncLevel.MONTH),
        VarkaEmitOptions.TruncDateForm.SUBTRACT, 36),
      ("trunc QUARTER, subtract", new TruncDate(col, TruncLevel.QUARTER),
        VarkaEmitOptions.TruncDateForm.SUBTRACT, 62),
      ("trunc YEAR, recompose", new TruncDate(col, TruncLevel.YEAR),
        VarkaEmitOptions.TruncDateForm.RECOMPOSE, 70),
      ("trunc MONTH, recompose", new TruncDate(col, TruncLevel.MONTH),
        VarkaEmitOptions.TruncDateForm.RECOMPOSE, 74),
      ("trunc QUARTER, recompose", new TruncDate(col, TruncLevel.QUARTER),
        VarkaEmitOptions.TruncDateForm.RECOMPOSE, 79))
    // The subtract MONTH form is dayofmonth's tail with the increment replaced by the
    // subtraction (36 against 36), and YEAR is dayofyear's plus two (45 against 43).
    assert(month === 35 && dayOfMonth === 36 && dayOfYear === 43,
      s"the siblings moved: month=$month dayofmonth=$dayOfMonth dayofyear=$dayOfYear")
    val counted = registered.map { case (name, root, form, _) => (name, ops(root, form)) }
    assert(counted.map(_._2) === registered.map(_._4),
      s"the register: ${counted.map { case (n, c) => s"$n=$c" }.mkString(", ")}; " +
        s"month=$month dayofmonth=$dayOfMonth dayofyear=$dayOfYear")
  }

  test("the emitted trunc kernels match DateTimeUtils.truncDate over the whole covered range " +
      "(opt-in: -Dvarka.sweep=true; task 35)") {
    // The gate that found the leap-flag constant tasks 34 and 36 each shipped wrong, and
    // which no boundary list caught: every day the narrowed prefix covers, through the real
    // emitted kernel, per level, under both lowerings and both prefix forms.
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernels")
    for (form <- truncForms; julian <- Seq(true, false); neri <- Seq(true, false)) {
      sweepTrunc(VarkaEmitOptions.DEFAULTS.withTruncDate(form).withJulianMap(julian)
        .withNeriSchneiderMonth(neri))
    }
  }

  // -------------------------------------------------------------------------------------------
  // Task 61: trunc with a level column (TruncDateDynamic).
  // -------------------------------------------------------------------------------------------

  private val dynamicTrunc = new TruncDateDynamic(new ColumnRef(0), new ColumnRef(1))

  /** The leaf's four codes, cycled by row; nothing else ever reaches a live level lane. */
  private def levelByRow(i: Int): Int = TruncLevelLeaf.WEEK + i % 4

  test("trunc with a level column matches DateTimeUtils.truncDate over the calendar " +
      "boundaries and every null pattern of both columns, under every prefix and mod-7 form") {
    // The level cycles the four codes the leaf can hand the kernel, so every boundary date
    // meets every level somewhere in the matrix; combos(2) drives the null-level-on-a-live-date
    // pattern that a word aliasing the date's alone would get wrong (task 38's trap, task 59's
    // again). The mod-7 lowering is the week result's, so all three ship variants run.
    def data(c: Int, i: Int): Int = if (c == 0) calendarBoundaryDay(0, i) else levelByRow(i)
    for (mod7 <- VarkaEmitOptions.FloorMod7.values(); julian <- Seq(true, false);
        neri <- Seq(true, false)) {
      val options = VarkaEmitOptions.DEFAULTS.withFloorMod7(mod7).withJulianMap(julian)
        .withNeriSchneiderMonth(neri)
      checkMatrix(Seq(dynamicTrunc), 2, Array.emptyIntArray, Seq(1, 13, 17, 64, 65, 1000),
        combos(2), data = data, ctx = s"trunc dynamic $mod7 julianMap=$julian neri=$neri",
        options = options)
    }
  }

  test("every level over every day of two years, beside the literal node sharing " +
      "its prefix") {
    // Day by day over 2023 and 2024 at one level per pass, so every week, month, quarter and
    // year start is crossed in both year kinds at the level that reads it - the week rows are
    // the ones no literal test covers, since the literal WEEK is a next_day rewrite. The
    // literal MONTH node beside it shares the prefix fragment with the dynamic one.
    val start = LocalDate.of(2023, 1, 1).toEpochDay.toInt
    val days = (0 until 731).map(start + _)
    val roots = Seq[VarkaVectorIR](dynamicTrunc, new TruncDate(new ColumnRef(0), TruncLevel.MONTH))
    for (level <- TruncLevelLeaf.WEEK to TruncLevelLeaf.YEAR) {
      def data(c: Int, i: Int): Int =
        if (c == 0) { if (i < days.length) days(i) else i * 9973 - 400000 } else level
      checkMatrix(roots, 2, Array.emptyIntArray, Seq(731), combos(2).take(4), data = data,
        ctx = s"trunc dynamic two years level=$level")
    }
  }

  test("a literal level is rejected at analysis - that shape is the literal node") {
    val e = intercept[IllegalArgumentException](
      emitMulti(Seq(new TruncDateDynamic(new ColumnRef(0), new LiteralSlot(0))), 1, 1))
    assert(e.getMessage.contains("trunc's level must be a column"), e.getMessage)
  }

  test("the dynamic tail costs what PLAN_TASK_61.md 3.3 registered") {
    // The literal nodes' own counts are the task 35 register above; their exact bytes were
    // hashed before and after the factoring (PLAN_TASK_61.md 9). This pins the dynamic form.
    assert(laneOps(emitMulti(Seq(dynamicTrunc), 2, 0)._2, "loopDense0") === 91)
  }

  test("the emitted dynamic trunc kernel matches DateTimeUtils.truncDate over the whole " +
      "covered range at every level (opt-in: -Dvarka.sweep=true; task 61)") {
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernel")
    for (mod7 <- VarkaEmitOptions.FloorMod7.values(); julian <- Seq(true, false);
        neri <- Seq(true, false)) {
      sweepTruncDynamic(VarkaEmitOptions.DEFAULTS.withFloorMod7(mod7).withJulianMap(julian)
        .withNeriSchneiderMonth(neri))
    }
  }

  private def sweepTruncDynamic(options: VarkaEmitOptions): Unit = {
    val (kernel, loader) = load(emitMulti(Seq(dynamicTrunc), 2, 0, options))
    try {
      val arena = Arena.ofConfined()
      try {
        val chunk = 1 << 16
        val data = alloc(arena, chunk * 4L)
        val levels = alloc(arena, chunk * 4L)
        val validity = alloc(arena, (chunk + 7) / 8L)
        validity.fill(0xFF.toByte)
        val out = makeOutput(arena, chunk)
        for (level <- TruncLevelLeaf.WEEK to TruncLevelLeaf.YEAR) {
          var i = 0
          while (i < chunk) {
            levels.set(ValueLayout.JAVA_INT, i * 4L, level)
            i += 1
          }
          var day = VarkaChrono.NARROW_MIN_DAYS
          var mismatches = 0
          while (day <= VarkaChrono.NARROW_MAX_DAYS) {
            val n = math.min(chunk, VarkaChrono.NARROW_MAX_DAYS - day + 1)
            i = 0
            while (i < n) {
              data.set(ValueLayout.JAVA_INT, i * 4L, day + i)
              i += 1
            }
            val status = kernel.run(Array(data.address(), levels.address()),
              Array(validity.address(), validity.address()), Array(0, 0),
              Array(out._1.address()), Array(out._2.address()), Array.empty[Int], n)
            assert(status === 0, s"the kernel declined an in-range batch at day $day")
            i = 0
            while (i < n) {
              val d = day + i
              val got = out._1.get(ValueLayout.JAVA_INT, i * 4L)
              val want = DateTimeUtils.truncDate(d, level)
              if (got != want) {
                mismatches += 1
                if (mismatches < 4) {
                  fail(s"day $d level $level under ${options.canonical()}: " +
                    s"emitted $got, DateTimeUtils.truncDate $want")
                }
              }
              i += 1
            }
            day += n
          }
          assert(mismatches === 0,
            s"level $level: the emitted kernel disagreed on $mismatches days")
        }
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the emitted kernel agrees with VarkaChrono's scalar twin, not only with LocalDate") {
    // Every other test in this file checks the emitted kernel against LocalDate and
    // VarkaChronoSuite checks VarkaChrono against LocalDate separately - each a genuine
    // definition-level oracle, deliberately not each other (see evalValue's comment above).
    // That leaves a gap this test closes: nothing committed (the direct comparison only runs
    // opt-in, in the exhaustive sweep below) ever compares the emitted bytecode against
    // VarkaChrono directly, so a future edit that moved both the same wrong way could agree
    // with LocalDate on every curated/pseudo-random day above and still have silently
    // diverged from VarkaChrono - contradicting VarkaChrono's own class-doc promise that "any
    // disagreement with the emitted kernel is an emission bug". A committed, non-exhaustive
    // sample is enough to catch that: it does not need to be exhaustive, since the exhaustive
    // sweep already exists for the LocalDate side and opting into it is what full coverage
    // means here.
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)))
    val days = Array(
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS, VarkaChrono.NARROW_MAX_DAYS - 1, -1, 0, 1,
      LocalDate.of(1600, 2, 29).toEpochDay.toInt, LocalDate.of(1900, 3, 1).toEpochDay.toInt,
      LocalDate.of(2000, 2, 29).toEpochDay.toInt
    ) ++ Array.tabulate(2000)(i => i * 9973 - 400000)
      .filter(VarkaChrono.inNarrowRange)
    val (kernel, loader) = load(emitMulti(roots, 1, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        val data = alloc(arena, days.length * 4L)
        val validity = alloc(arena, (days.length + 7) / 8L)
        validity.fill(0xFF.toByte)
        days.zipWithIndex.foreach { case (d, i) => data.set(ValueLayout.JAVA_INT, i * 4L, d) }
        val outs = roots.map(_ => makeOutput(arena, days.length))
        val status = kernel.run(Array(data.address()), Array(validity.address()), Array(0),
          outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
          Array.empty[Int], days.length)
        assert(status === 0, "the kernel declined an in-range batch")
        days.indices.foreach { i =>
          val fields = VarkaChrono.narrowed(days(i))
          val want = Seq(fields.year, fields.month, fields.dayOfMonth, fields.quarter,
            fields.dayOfYear)
          val got = outs.map(_._1.get(ValueLayout.JAVA_INT, i * 4L))
          assert(got === want, s"day ${days(i)}: emitted $got, VarkaChrono $want")
        }
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("last_day matches DateTimeUtils.getLastDayOfMonth over the range it covers " +
      "(task 36)") {
    val root = new LastDay(new ColumnRef(0))
    val inRange = Array(
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS, VarkaChrono.NARROW_MAX_DAYS - 1,
      -1, 0, 1, -719468, -719162,
      LocalDate.of(1900, 2, 15).toEpochDay.toInt, LocalDate.of(1900, 2, 28).toEpochDay.toInt,
      LocalDate.of(1900, 3, 1).toEpochDay.toInt,
      LocalDate.of(2000, 2, 15).toEpochDay.toInt, LocalDate.of(2000, 2, 29).toEpochDay.toInt,
      LocalDate.of(2000, 3, 1).toEpochDay.toInt,
      LocalDate.of(2023, 2, 28).toEpochDay.toInt, LocalDate.of(2023, 3, 1).toEpochDay.toInt,
      LocalDate.of(2024, 2, 29).toEpochDay.toInt, LocalDate.of(2024, 3, 1).toEpochDay.toInt,
      LocalDate.of(1, 1, 1).toEpochDay.toInt, LocalDate.of(9999, 12, 31).toEpochDay.toInt)
    // Every month of a leap year (2024) and of a common year (2023), so all twelve linear-form
    // lengths are exercised twice and February is exercised under both leap rules.
    val everyMonth = (2023 to 2024).flatMap { y =>
      (1 to 12).map(m => LocalDate.of(y, m, 15).toEpochDay.toInt)
    }.toArray
    val boundary = inRange ++ everyMonth
    def days(c: Int, i: Int): Int =
      if (i < boundary.length) boundary(i) else i * 9973 - 400000
    checkMatrix(Seq(root), 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "last_day narrowed")
  }

  test("add_months matches DateTimeUtils across clamp boundaries and month offsets") {
    val root = new AddMonths(new ColumnRef(0), new LiteralSlot(0))
    // Every one of these has a different day-of-month than the month it lands in, at both
    // ends of the year and across a common/leap February - the clamp is where a wrong
    // implementation fails, per PLAN_TASK_40.md section 4.
    val clampDays = Array(
      LocalDate.of(2023, 1, 31).toEpochDay.toInt, LocalDate.of(2023, 3, 31).toEpochDay.toInt,
      LocalDate.of(2020, 2, 29).toEpochDay.toInt, LocalDate.of(2024, 2, 28).toEpochDay.toInt,
      LocalDate.of(1900, 1, 31).toEpochDay.toInt, LocalDate.of(2000, 1, 31).toEpochDay.toInt,
      LocalDate.of(2023, 12, 31).toEpochDay.toInt, 0, -1, 1,
      // A four-digit year plus a multi-century month offset overflows the 32-bit lane
      // multiply behind the /400 and /100 magic (VarkaChrono.YEAR_CENTURY_M's javadoc) -
      // the exact shape that found the bug during development. Near-epoch dates alone do
      // not reach it.
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS, VarkaChrono.NARROW_MAX_DAYS - 1, 3818579, 3811279)
    def days(c: Int, i: Int): Int =
      if (i < clampDays.length) clampDays(i) else i * 9973 - 400000
    // Offsets of 0, +-1, +-11, +-12, +-13, +-1200 cross a multiple of 12 both ways, which is
    // where the month-arithmetic dividend's own bias could be off by one.
    // Both prefix forms (task 54): add_months decomposes through the prefix and recomposes, so
    // a year of era that was off by one would surface here before anywhere else.
    for {
      julian <- Seq(true, false)
      offset <- Seq(0, 1, -1, 11, -11, 12, -12, 13, -13, 1200, -1200,
        VarkaChrono.MONTH_ARITH_MAX_MONTHS, VarkaChrono.MONTH_ARITH_MIN_MONTHS)
    } {
      checkMatrix(Seq(root), 1, Array(offset), Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = days,
        ctx = s"add_months offset=$offset julianMap=$julian",
        options = VarkaEmitOptions.DEFAULTS.withJulianMap(julian))
    }
  }

  test("a chained calendar computation matches across every lane-group tail length") {
    // Historically an epilogue-mask/guard interaction bug: a masked load fills the lanes past
    // `length` with 0, and the now-removed guard ran on the node's *input*, which here is a
    // computed value (0 - 5400000, well outside the guard's range) - so an unmasked check
    // declined every batch whose length was not a lane multiple, even though every real row,
    // near 2022, was in range. Task 51 removed the guard entirely; this case is kept as a
    // general correctness check on a chained node across non-lane-multiple lengths.
    val root = new Year(new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    def days(c: Int, i: Int): Int = 19000 + i
    checkMatrix(Seq(root), 1, Array(5400000), Seq(16, 17, 31, 64, 1000, 4095, 4096),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "epilogue-guard")
  }

  test("the emitted calendar kernel matches LocalDate over its whole range " +
      "(opt-in: -Dvarka.sweep=true)") {
    // VarkaChrono's own suite sweeps the scalar model over all 16,777,216 days, and the
    // emitter loads the same constants - but it re-expresses the algorithm as bytecode, with
    // its own op order, carry steps and mask polarity. Only this sweep holds the *emitted*
    // form to the same standard; without it the class doc's "cannot drift" covers the
    // constants and not the code, and a transposed slot would survive every other test.
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernel")
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)))
    // Both lowerings, because task 32 step B's shared prefix re-orders nothing but does make
    // four of these five outputs read locals a fifth wrote. A transposed slot there would
    // survive every bounded test in this suite and fail here.
    //
    // Both switch positions, and a year-only kernel beside the four-field one, because task
    // 48's elision is a claim about a local that is never written: the four-field shape keeps
    // the month step under sharing (three of its tails read it) and only the year-only shape
    // sweeps the elided prefix in both sharing modes. This is the gate that matters for that
    // claim - the bounded tests locate a failure it would only report.
    //
    // `dayofyear` alone for the same reason: task 34's tail is the second one that reads the
    // January turn off the day of year rather than off the month, so its prefix elides the
    // step too - and a tail reading an unwritten local returns a plausible wrong day, not a
    // crash. This is the only test that would notice.
    //
    // Both month axes (task 53), which is what makes the older lowering a live reference
    // variant rather than dead code: the two compute the same fields through different
    // constants on differently-based month indices, so agreeing with LocalDate over the whole
    // range is also them agreeing with each other over it. This is the gate that matters for
    // the axis change - `add_months` and `last_day` recompose through these constants, and a
    // month-axis mistake in either is exactly what a boundary set misses and a sweep cannot.
    val yearOnly = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    val dayOfYearOnly = Seq[VarkaVectorIR](new DayOfYear(new ColumnRef(0)))
    // Both prefix forms (task 54), for the same reason as the month axes: the two reach the
    // year of era and the day of year through different divisions, and only the sweep holds
    // the emitted Julian map to LocalDate over every covered day rather than over an era.
    for {
      options <- Seq(unshared, sharing)
      elide <- Seq(true, false)
      neri <- Seq(true, false)
      julian <- Seq(true, false)
    } {
      val axis = options.withElideChronoMonth(elide).withNeriSchneiderMonth(neri)
        .withJulianMap(julian)
      sweepCalendar(roots, axis)
      sweepCalendar(yearOnly, axis, date => Seq(date.getYear))
      sweepCalendar(dayOfYearOnly, axis, date => Seq(date.getDayOfYear))
    }
  }

  test("the emitted last_day kernel matches DateTimeUtils over its whole range " +
      "(opt-in: -Dvarka.sweep=true; task 36)") {
    // The same discipline the four-field sweep above holds the emitter to, for last_day's
    // own tail: emitLeapFlag's magic constants were first written as an exact one-shot magic
    // that overflows a 32-bit lane's signed product past y ~ 25600 (roughly year 12400), which
    // no test narrower than this sweep caught - every boundary list in this file's other tests
    // happened to land under that threshold. Guard against that class of bug reappearing.
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernel")
    // Both month axes (task 53). This node is the one whose month-length arithmetic reads the
    // prefix slot directly rather than through a tail, so it is the only place the axis had to
    // be handled inside a recomposing node - which makes it the one most worth sweeping twice.
    for {
      neri <- Seq(true, false)
      julian <- Seq(true, false)
    } {
      sweepLastDay(VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(neri).withJulianMap(julian))
    }
  }

  test("a day outside the covered range is no longer declined") {
    // Tasks 26 through 40 guarded every calendar extraction against a day outside
    // VarkaChrono.NARROW_MIN_DAYS..NARROW_MAX_DAYS, declining the whole batch to the row
    // engine. Task 51 removed that guard: the arithmetic is still only proven exact inside
    // the narrowed range (VarkaChronoSuite's exhaustive sweep is over exactly that range), but
    // nothing checks it at run time anymore, so a day outside it is now computed silently
    // rather than declined. PLAN_TASK_51.md records why the owner accepted that trade, and
    // Task 52 moved the check to the nodes that can actually manufacture such a day: the
    // compiler bounds every literal shift and the emitter guards a column-offset producer
    // (the "task 52" tests below). A bare column past the range is the column contract's
    // breach, not a guard's business, so this batch is still computed, not declined.
    val root = new Year(new ColumnRef(0))
    val (kernel, loader) = load(emitMulti(Seq(root), 1, 0, VarkaEmitOptions.DEFAULTS))
    try {
      val arena = Arena.ofConfined()
      try {
        val length = 64
        // One day past the range, in a lane the vector loop covers.
        val bad = makeInputData(arena, length, _ => false,
          i => if (i == 3) VarkaChrono.NARROW_MAX_DAYS + 1 else i * 97)
        val out = makeOutput(arena, length)
        assert(runKernel(kernel, bad, out, length) === 0)
        // And in a lane only the epilogue covers, whatever the host's lane count.
        val tail = makeInputData(arena, 17, _ => false,
          i => if (i == 16) VarkaChrono.NARROW_MIN_DAYS - 1 else i * 97)
        val tailOut = makeOutput(arena, 17)
        assert(runKernel(kernel, tail, tailOut, 17) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("sharing the calendar prefix changes the bytecode but never the results") {
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)))
    assert(VarkaEmitOptions.DEFAULTS.shareChronoPrefix(),
      "the shared prefix is no longer the default - the epilogue-size case for it is in " +
        "PLAN_TASK_32.md section 7.1, so say why here if it was deliberately turned off")
    assert(epilogueSize(roots, 1, sharing) < epilogueSize(roots, 1, unshared),
      "the shared epilogue is no smaller, so the prefix is still being emitted four times")
    // Both settings over the same matrix and the same java.time oracle. Running the unshared
    // one here too is what makes this a differential rather than a second correctness test:
    // a harness case that the shared lowering fails and the unshared one also fails is a
    // problem with the case, and this says so in the same run.
    for ((options, ctx) <- Seq((unshared, "unshared"), (sharing, "shared"))) {
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = s"$ctx prefix",
        options = options)
      // forceMasked reports one null, so a length of 1 would report the column all-null and
      // take the kernel's all-null shortcut instead of the masked body this is here to drive.
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths.filter(_ > 1),
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, forceMasked = true,
        ctx = s"$ctx prefix, masked", options = options)
    }
  }

  test("a shared prefix serves add_months and a plain extraction over the same date") {
    // add_months writes the prefix's carry mask as its own scratch after the prefix is done
    // (emitChronoPrefix's javadoc says why that is sound). Ordering it *before* the three
    // extractions is what would catch it if it were not: they read the shared slots after it
    // has finished with them.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](
      new AddMonths(col, new LiteralSlot(0)), new Year(col), new Month(col), new DayOfMonth(col))
    for (offset <- Seq(0, 1, -13, VarkaChrono.MONTH_ARITH_MAX_MONTHS)) {
      checkMatrix(roots, 1, Array(offset), remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays,
        ctx = s"shared with add_months offset=$offset", options = sharing)
    }
  }

  test("the guard's removal reaches the shared prefix too") {
    // This PR predates task 51 and originally asserted the opposite: that the guard, sharing
    // the prefix across the three outputs below, still fired and declined the batch. Task 51
    // removed the guard from emitEra, which emitChronoPrefixOnce - the fragment-sharing entry
    // point this PR added - calls exactly like the unshared path does. That is why removal
    // needed no change here: there was never a second, sharing-specific copy of the guard to
    // find and delete. This test now exists to keep it that way - if a future change gives
    // the shared path its own inlined guard logic instead of routing through emitEra, this is
    // where that would first show up as a mistaken STATUS_CHRONO_RANGE. Task 52's guard lives
    // at a column-offset producer, never in the prefix, so this stays true after it too.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](new Year(col), new Month(col), new Quarter(col))
    val (kernel, loader) = load(emitMulti(roots, 1, 0, sharing))
    try {
      val arena = Arena.ofConfined()
      try {
        def status(length: Int, isNull: Int => Boolean, day: Int => Int): Int = {
          // `day` pins an out-of-range value at one lane which a caller may also null.
          val in = makeInputData(arena, length, isNull, day, poisonNulls = false)
          val outs = roots.map(_ => makeOutput(arena, length))
          kernel.run(
            Array(in.data.address()), Array(in.validity.address()), Array(in.nullCount),
            outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
            Array.empty[Int], length)
        }
        assert(status(64, _ => false, i => i * 97) === 0, "an in-range batch was declined")
        assert(status(64, _ => false, i => if (i == 3) VarkaChrono.NARROW_MAX_DAYS + 1
          else i * 97) === 0, "a day past the range was declined through the shared prefix")
        assert(status(17, _ => false, i => if (i == 16) VarkaChrono.NARROW_MIN_DAYS - 1
          else i * 97) === 0,
          "a day past the range was declined in the epilogue, where sharing happens today")
        assert(status(64, i => i == 3, i => if (i == 3) VarkaChrono.NARROW_MAX_DAYS + 1
          else i * 97) === 0, "an out-of-range value under a null row condemned the batch")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  // Task 52's runtime half: the range guard, moved from every calendar extraction to the one
  // producer the compiler cannot bound - a date_add/date_sub whose offset is a column.
  private val guardOff = VarkaEmitOptions.DEFAULTS.withGuardDayProducers(false)

  // Task 79's A/B arm: the arm context off, which is what every shape emitted before it.
  private val armOff = VarkaEmitOptions.DEFAULTS.withGuardUnderArm(false)

  test("a guarded producer under a CASE arm no longer condemns from the untaken arm") {
    // `CASE WHEN c < 1 THEN year(date_add(d, off)) ELSE year(d) END`, on the day-producer guard
    // task 52 built. Whether a lane is out of range and which arm it takes are set by two
    // different columns - `off` and `c` - so a lane index chooses one without deciding the
    // other. The first version of this test derived the arm from the lane's parity, which made
    // the index carry both, and the two epilogue cases inherited the wrong arm; the expected
    // status is also computed from `takesThen` now rather than written beside each index, so
    // the fixture and the assertion cannot disagree about a fact the fixture determines.
    val producer = new Year(new AddDays(new ColumnRef(0), new ColumnRef(1)))
    val root = new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(2), new LiteralSlot(0)),
      producer,
      new Year(new ColumnRef(0)))
    val (kernel, loader) = load(emitMulti(Seq(root), 3, 1))
    val (kernelOff, loaderOff) = load(emitMulti(Seq(root), 3, 1, armOff))
    try {
      val arena = Arena.ofConfined()
      try {
        def day(i: Int): Int = 100
        // Lane `at` is pushed one day past the range; every other lane stays a small shift.
        def off(at: Int)(i: Int): Int =
          if (i == at) VarkaChrono.NARROW_MAX_DAYS + 1 - day(i) else i % 5
        // The arm column, and the one function both the data and the expectation read.
        def takesThen(i: Int): Boolean = i % 3 == 0
        def cond(i: Int): Int = if (takesThen(i)) 0 else 7
        def status(k: VarkaFusedKernel, length: Int, at: Int): Int = {
          val d = makeInputData(arena, length, _ => false, day, poisonNulls = false)
          val o = makeInputData(arena, length, _ => false, off(at), poisonNulls = false)
          val c = makeInputData(arena, length, _ => false, cond, poisonNulls = false)
          val out = makeOutput(arena, length)
          k.run(
            Array(d.data.address(), o.data.address(), c.data.address()),
            Array(d.validityAddress(length), o.validityAddress(length),
              c.validityAddress(length)),
            Array(d.nullCount, o.nullCount, c.nullCount),
            Array(out._1.address()), Array(out._2.address()), Array(1), length)
        }
        /** What the guard must do for an out-of-range lane at `at`: fire iff that lane's own
         *  condition takes the arm the producer is in. Derived, not restated. */
        def expected(at: Int): Int =
          if (at >= 0 && takesThen(at)) VarkaFusedKernel.STATUS_CHRONO_RANGE else 0
        // A loop lane in each arm, and a lane only the epilogue covers in each arm - four
        // cases whose arm and whose body are now chosen independently.
        for ((length, at) <- Seq((64, 3), (64, 4), (17, 15), (17, 16))) {
          assert(status(kernel, length, at) === expected(at),
            s"length $length lane $at: takesThen=${takesThen(at)}")
          // Off, the arm is ignored and every out-of-range lane condemns: the cliff, and the
          // reference arm the A/B prices against.
          assert(status(kernelOff, length, at) === VarkaFusedKernel.STATUS_CHRONO_RANGE,
            s"length $length lane $at: with the context off this must decline either way")
        }
        // Nothing out of range: 0 under both settings.
        assert(status(kernel, 64, -1) === expected(-1))
        assert(status(kernelOff, 64, -1) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
      loaderOff.release()
    }
  }

  test("an unknown condition sends its lane to ELSE, and the guard there still fires") {
    // The polarity test. SQL's CASE routes an *unknown* condition to ELSE, so the else arm's
    // context is NOT known-true - known-false plus unknown - and never the known-false word.
    // Here the condition's own column is null on the out-of-range lane, so the condition is
    // unknown there; the guarded producer is in the ELSE arm and must condemn the batch. Had
    // the emitter used kF, that lane would fall outside the else context and the batch would
    // wrongly survive.
    val root = new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(2), new LiteralSlot(0)),
      new Year(new ColumnRef(0)),
      new Year(new AddDays(new ColumnRef(0), new ColumnRef(1))))
    val (kernel, loader) = load(emitMulti(Seq(root), 3, 1))
    try {
      val arena = Arena.ofConfined()
      try {
        val at = 3
        def day(i: Int): Int = 100
        def off(i: Int): Int = if (i == at) VarkaChrono.NARROW_MAX_DAYS + 1 - day(i) else i % 5
        val length = 64
        val d = makeInputData(arena, length, _ => false, day, poisonNulls = false)
        val o = makeInputData(arena, length, _ => false, off, poisonNulls = false)
        // The condition column is null exactly on the out-of-range lane: unknown -> ELSE.
        val c = makeInputData(arena, length, _ == at, _ => 0, poisonNulls = false)
        val out = makeOutput(arena, length)
        val status = kernel.run(
          Array(d.data.address(), o.data.address(), c.data.address()),
          Array(d.validityAddress(length), o.validityAddress(length), c.validityAddress(length)),
          Array(d.nullCount, o.nullCount, c.nullCount),
          Array(out._1.address()), Array(out._2.address()), Array(1), length)
        assert(status === VarkaFusedKernel.STATUS_CHRONO_RANGE,
          "an unknown condition takes ELSE, so the guard in the ELSE arm must condemn")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("a guard whose node's uses do not agree on one arm stays unqualified") {
    // The three shapes 3.3 refuses to narrow, each asserted to keep declining on a lane the
    // arm would have excused. These are the silent-wrong-answer cases: narrowing any of them
    // would stop a batch declining that must decline.
    val producer = new Year(new AddDays(new ColumnRef(0), new ColumnRef(1)))
    val cond = new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0))
    val shapes = Seq(
      // (a) used inside an arm and bare in a second output: CSE emits one node, and the bare
      // use needs the guard on every lane.
      "in an arm and bare" ->
        Seq(new IfElse(cond, producer, new Year(new ColumnRef(0))), producer),
      // (b) used under two different conditions: the second condition's word does not exist
      // where the node is first emitted, which is why the rule refuses the disjunction.
      "under two conditions" -> Seq(
        new IfElse(cond, producer, new Year(new ColumnRef(0))),
        new IfElse(new Compare(CompareOp.GT, new ColumnRef(0), new LiteralSlot(0)),
          producer, new Year(new ColumnRef(0)))),
      // (c) in condition position: computed on every lane the IfElse is, so unconditional.
      "in a condition" -> Seq(new IfElse(
        new Compare(CompareOp.LT, producer, new LiteralSlot(0)),
        new Year(new ColumnRef(0)),
        new Month(new ColumnRef(0)))))
    for ((name, roots) <- shapes) {
      val (kernel, loader) = load(emitMulti(roots, 2, 1))
      try {
        val arena = Arena.ofConfined()
        try {
          val at = 1
          val cut = 5000
          def day(i: Int): Int = if (i % 2 == 0) 100 else cut + 100
          def off(i: Int): Int =
            if (i == at) VarkaChrono.NARROW_MAX_DAYS + 1 - day(i) else i % 5
          val length = 64
          val d = makeInputData(arena, length, _ => false, day, poisonNulls = false)
          val o = makeInputData(arena, length, _ => false, off, poisonNulls = false)
          val outs = roots.indices.map(_ => makeOutput(arena, length))
          val status = kernel.run(
            Array(d.data.address(), o.data.address()),
            Array(d.validityAddress(length), o.validityAddress(length)),
            Array(d.nullCount, o.nullCount),
            outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
            Array(cut), length)
          assert(status === VarkaFusedKernel.STATUS_CHRONO_RANGE,
            s"$name: the guard must stay unqualified, and lane $at must decline the batch")
        } finally {
          arena.close()
        }
      } finally {
        loader.release()
      }
    }
  }

  test("a shape with no guarded node under an arm is byte-identical either way") {
    // The assertion that the context reached only the guards: every shape without a
    // batch-condemning node under an arm emits exactly what it did before task 79.
    val shapes = Seq(
      "plain year" -> (Seq(new Year(new ColumnRef(0))), 1, 0),
      "guarded producer, no arm" ->
        (Seq(new Year(new AddDays(new ColumnRef(0), new ColumnRef(1)))), 2, 0),
      "CASE over unguarded arms" -> (Seq(new IfElse(
        new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0)),
        new Year(new ColumnRef(0)), new Month(new ColumnRef(0)))), 1, 1))
    for ((name, (roots, inputs, literals)) <- shapes) {
      val on = emitMulti(roots, inputs, literals)._2
      val off = emitMulti(roots, inputs, literals, armOff)._2
      for (body <- Seq("loopDense0", "loopMasked0", "epilogueDense0", "epilogueMasked0")) {
        assert(VarkaEmitterTestSupport.codeSize(on, body) ===
          VarkaEmitterTestSupport.codeSize(off, body), s"$name: $body moved")
      }
    }
  }

  test("make_date over a shifted year - the documented compile-time shape - " +
      "actually runs and matches the reference") {
    // `compileIntOperand`'s own doc says `make_date(y + 1, m, d)` fuses, and
    // `VarkaExpressionCompilerSuite` pins the IR that widening produces - but nothing had ever
    // emitted it, run it, or checked its value: the fuzzer's make_date arm only ever reads a
    // date's own fields back, never arithmetic over one of them. This is that gap closed.
    //
    // The shift is +1, over triples chosen to stay valid after it: MAKE_DATE_MAX_YEAR is
    // excluded, since a +1 there is the one shift this file's plain triples would push out of
    // range, and every Feb 29 is excluded too, since a leap day is invalid the moment +1 lands
    // it on a non-leap year (2024-02-29 -> 2025-02-29, which does not exist) - both are real
    // declines, correctly, and belong to task 42's own decline test rather than this one, which
    // is about the value on the path that does compute.
    val safeTriples: Array[(Int, Int, Int)] = makeDateValid.filterNot { case (y, m, d) =>
      y == VarkaChrono.MAKE_DATE_MAX_YEAR || (m == 2 && d == 29)
    }
    val y = new ColumnRef(0)
    val m = new ColumnRef(1)
    val d = new ColumnRef(2)
    val shiftedYear = new IntArith(IntOp.ADD, Overflow.WRAP, y, new LiteralSlot(0))
    for (ansi <- Seq(false, true)) {
      checkMatrix(Seq(new MakeDate(shiftedYear, m, d, ansi)), 3, Array(1),
        Seq(1, 13, 17, 64, 1000), combos(3), data = tripleData(safeTriples),
        ctx = s"make_date(y + 1, m, d), ansi=$ansi")
    }
  }

  test("a column-offset producer under a calendar node declines the batch whose " +
      "result leaves the range - in a loop lane, in an epilogue lane, and not under a null") {
    val add = new Year(new AddDays(new ColumnRef(0), new ColumnRef(1)))
    val sub = new Month(new SubDays(new ColumnRef(0), new ColumnRef(1)))
    for ((root, past, mirrored) <- Seq(
        (add, VarkaChrono.NARROW_MAX_DAYS + 1, false),
        (sub, VarkaChrono.NARROW_MIN_DAYS - 1, true))) {
      val (kernel, loader) = load(emitMulti(Seq(root), 2, 0))
      val (kernelOff, loaderOff) = load(emitMulti(Seq(root), 2, 0, guardOff))
      try {
        val arena = Arena.ofConfined()
        try {
          // The offset that lands lane `at` exactly one day past the range; every other lane
          // stays a small shift. `sub` subtracts, so its offset is the negated distance.
          def day(i: Int): Int = i * 97
          def off(at: Int)(i: Int): Int =
            if (i == at) { if (mirrored) day(i) - past else past - day(i) } else i % 5
          def status(k: VarkaFusedKernel, length: Int, at: Int,
              nullDate: Int => Boolean, nullOff: Int => Boolean): Int = {
            // `off(at)` is chosen so `d + off` lands just past the range at lane `at`, and
            // the cases below null that lane; poison would replace the sum being tested.
            val d = makeInputData(arena, length, nullDate, day, poisonNulls = false)
            val o = makeInputData(arena, length, nullOff, off(at), poisonNulls = false)
            runKernel2(k, d, o, makeOutput(arena, length), length)
          }
          val none = (_: Int) => false
          // In range: computed, under both settings.
          assert(status(kernel, 64, -1, none, none) === 0)
          assert(status(kernelOff, 64, -1, none, none) === 0)
          // A loop lane past the range (dense body: no nulls anywhere).
          assert(status(kernel, 64, 3, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
          // The same lane in the masked body, with an unrelated null elsewhere.
          assert(status(kernel, 64, 3, _ == 40, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
          // A lane only the epilogue covers, whatever the host's lane count.
          assert(status(kernel, 17, 16, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
          assert(status(kernel, 17, 16, _ == 2, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
          // The out-of-range lane under a null offset, then under a null date: the row is
          // null, its data lanes are undefined, and the batch must not be condemned.
          assert(status(kernel, 64, 3, none, _ == 3) === 0)
          assert(status(kernel, 64, 3, _ == 3, none) === 0)
          assert(status(kernel, 17, 16, none, _ == 16) === 0)
          // The reference variant computes every one of them - wrongly past the range, which
          // is exactly what the metric-only differential asserts against.
          assert(status(kernelOff, 64, 3, none, none) === 0)
          assert(status(kernelOff, 17, 16, none, none) === 0)
        } finally {
          arena.close()
        }
      } finally {
        loader.release()
        loaderOff.release()
      }
    }
  }

  test("the guard is emitted only where a calendar node reads a column-offset " +
      "producer, and adds bytes nowhere else") {
    val producer = new AddDays(new ColumnRef(0), new ColumnRef(1))
    val bodies = Seq("loopDense0", "loopMasked0", "epilogueDense0", "epilogueMasked0")
    def sizes(root: VarkaVectorIR, numInputs: Int, options: VarkaEmitOptions): Seq[Int] = {
      val bytes = emitMulti(Seq(root), numInputs, 0, options)._2
      bodies.map(VarkaEmitterTestSupport.codeSize(bytes, _))
    }
    // A producer with no calendar consumer: byte-identical under both settings, so
    // `date_add(d, off)` on its own pays nothing for a guard it does not need.
    assert(sizes(producer, 2, VarkaEmitOptions.DEFAULTS) === sizes(producer, 2, guardOff))
    assert(sizes(new DateDiff(producer, new ColumnRef(0)), 2, VarkaEmitOptions.DEFAULTS) ===
      sizes(new DateDiff(producer, new ColumnRef(0)), 2, guardOff))
    // A calendar node over a bare column, and over a literal-offset producer: the compiler
    // bounds both, and the emitter plans nothing.
    assert(sizes(new Year(new ColumnRef(0)), 1, VarkaEmitOptions.DEFAULTS) ===
      sizes(new Year(new ColumnRef(0)), 1, guardOff))
    val literal = new Year(new AddDays(new ColumnRef(0), new LiteralSlot(0)))
    assert(emitMulti(Seq(literal), 1, 1)._2.length ===
      emitMulti(Seq(literal), 1, 1, guardOff)._2.length)
    // The guarded shape: every body grows by the guard, and only the guarded shape does.
    val guarded = sizes(new Year(producer), 2, VarkaEmitOptions.DEFAULTS)
    val unguarded = sizes(new Year(producer), 2, guardOff)
    for ((body, (on, off)) <- bodies.zip(guarded.zip(unguarded))) {
      assert(on > off, s"$body: expected the guard's bytes, got $on vs $off")
    }
  }

  test("in-range column offsets under calendar nodes match the reference evaluator " +
      "under both settings, and CSE off repeats the guard without breaking it") {
    val producer = new AddDays(new ColumnRef(0), new ColumnRef(1))
    val roots = Seq[VarkaVectorIR](new Year(producer), new Month(producer),
      new DayOfMonth(new SubDays(new ColumnRef(0), new ColumnRef(1))))
    // Days stay near the epoch and offsets small, so no lane leaves the range and the status
    // must read zero in every case the matrix drives - the guard's silence is asserted too.
    val data = (c: Int, i: Int) => if (c == 0) (i * 97) % 40000 - 20000 else i % 23 - 11
    for (options <- Seq(VarkaEmitOptions.DEFAULTS, guardOff,
        VarkaEmitOptions.DEFAULTS.withCse(false))) {
      checkMatrix(roots, 2, Array.emptyIntArray, Seq(1, 17, 64, 65, 1000), combos(2),
        data = data, ctx = s"task 52 ${options.canonical()}", options = options)
    }
    // With CSE off the producer is re-emitted per reader, guard included; an out-of-range
    // lane is still caught.
    val (kernel, loader) = load(emitMulti(roots, 2, 0, VarkaEmitOptions.DEFAULTS.withCse(false)))
    try {
      val arena = Arena.ofConfined()
      try {
        val d = makeInputData(arena, 64, _ => false, i => i * 97)
        val o = makeInputData(arena, 64, _ => false,
          i => if (i == 5) VarkaChrono.NARROW_MAX_DAYS + 1 - 5 * 97 else 1)
        val outs = roots.map(_ => makeOutput(arena, 64))
        val status = kernel.run(Array(d.data.address(), o.data.address()), Array(0L, 0L),
          Array(0, 0), outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
          Array.empty[Int], 64)
        assert(status === VarkaFusedKernel.STATUS_CHRONO_RANGE)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  // Task 60's runtime half: the same range-guard block (now `emitRangeGuard`, generalized from
  // task 52's `emitProducerGuard`) on AddMonths' own month count, wherever it sits - the guard
  // protects the node's own magic-multiply arithmetic, not a further calendar consumer's.

  test("a column month count declines the batch whose count leaves the range - in a " +
      "loop lane, in an epilogue lane, and not under a null; the bounds themselves compute") {
    val root = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    val (kernel, loader) = load(emitMulti(Seq(root), 2, 0))
    val (kernelOff, loaderOff) = load(emitMulti(Seq(root), 2, 0, guardOff))
    try {
      val arena = Arena.ofConfined()
      try {
        // Days stay well inside the narrowed range regardless of the count under test, so a
        // failure here is the count guard's, not the unrelated day decomposition's.
        def day(i: Int): Int = (i * 97) % 40000 - 20000
        def count(at: Int, value: Int)(i: Int): Int = if (i == at) value else i % 11 - 5
        def status(k: VarkaFusedKernel, length: Int, at: Int, value: Int,
            nullDate: Int => Boolean, nullCount: Int => Boolean): Int = {
          // Same as task 52's: the violating month count is pinned at a lane these cases null.
          val d = makeInputData(arena, length, nullDate, day, poisonNulls = false)
          val m = makeInputData(arena, length, nullCount, count(at, value), poisonNulls = false)
          runKernel2(k, d, m, makeOutput(arena, length), length)
        }
        val none = (_: Int) => false
        val hi = VarkaChrono.MONTH_ARITH_MAX_MONTHS
        val lo = VarkaChrono.MONTH_ARITH_MIN_MONTHS
        // In range: computed, under both settings.
        assert(status(kernel, 64, -1, 0, none, none) === 0)
        assert(status(kernelOff, 64, -1, 0, none, none) === 0)
        // Both bounds themselves compute - the guard is `< lo || > hi`, not `<= lo || >= hi`.
        assert(status(kernel, 64, 3, hi, none, none) === 0)
        assert(status(kernel, 64, 3, lo, none, none) === 0)
        // One past each bound, in a loop lane (dense body: no nulls anywhere).
        assert(status(kernel, 64, 3, hi + 1, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 64, 3, lo - 1, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // The same lane in the masked body, with an unrelated null elsewhere.
        assert(status(kernel, 64, 3, hi + 1, _ == 40, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // A lane only the epilogue covers, whatever the host's lane count.
        assert(status(kernel, 17, 16, hi + 1, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 17, 16, lo - 1, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // A live violation in the masked epilogue - the one body where the guard mask is ANDed
        // with both the node's word and the epilogue mask, and the body whose ordering produced
        // this task's VerifyError. The other epilogue cases above are null-free, so the dense
        // driver runs them and only epilogueDense is exercised; the null here is on a lane other
        // than the violating one, so the violation stays live and the guard must still see it.
        assert(status(kernel, 17, 16, hi + 1, _ == 2, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // The out-of-range lane under a null count, then under a null date: the row is null,
        // its data lanes are undefined, and the batch must not be condemned.
        assert(status(kernel, 64, 3, hi + 1, none, _ == 3) === 0)
        assert(status(kernel, 64, 3, hi + 1, _ == 3, none) === 0)
        assert(status(kernel, 17, 16, hi + 1, none, _ == 16) === 0)
        // guardDayProducers does not reach this guard: the count check is the node's own
        // correctness (its magic multiply is exact only over the guarded range) and the
        // compiler's dayRange bounds a column count on the strength of it, so the option-off
        // variant declines exactly as the default does. Only task 52's day-producer guard,
        // which insures a consumer rather than the producer itself, is a reference variant.
        assert(status(kernelOff, 64, 3, hi + 1, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernelOff, 17, 16, lo - 1, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernelOff, 64, -1, 0, none, none) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
      loaderOff.release()
    }
  }

  test("a literal date with a column count guards the same, on the branch that has " +
      "no word of its own") {
    // Every other test builds AddMonths(ColumnRef, ColumnRef), which owns its validity word.
    // A literal date gives the node no word of its own: planWordRef aliases the count input's,
    // the new emitAndWord is skipped, and emitRangeGuard reads an aliased input slot instead.
    // That is a different path through the same guard, and nothing else covers it.
    val root = new AddMonths(new LiteralSlot(0), new ColumnRef(0))
    val (kernel, loader) = load(emitMulti(Seq(root), 1, 1, VarkaEmitOptions.DEFAULTS))
    try {
      val arena = Arena.ofConfined()
      try {
        val hi = VarkaChrono.MONTH_ARITH_MAX_MONTHS
        // The date rides the literal table rather than an input column, so the kernel is run
        // directly: runKernel passes no literals.
        val dateLiteral = 19000
        def status(length: Int, at: Int, value: Int, nullCount: Int => Boolean): Int = {
          val m = makeInputData(arena, length, nullCount,
            i => if (i == at) value else i % 7 - 3, poisonNulls = false)
          val out = makeOutput(arena, length)
          kernel.run(
            Array(m.data.address()), Array(m.validity.address()), Array(m.nullCount),
            Array(out._1.address()), Array(out._2.address()), Array(dateLiteral), length)
        }
        val none = (_: Int) => false
        assert(status(64, -1, 0, none) === 0)
        assert(status(64, 3, hi + 1, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(17, 16, hi + 1, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // Null count on the violating lane: undefined data, and the batch stands.
        assert(status(64, 3, hi + 1, _ == 3) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("in-range column month counts match the reference evaluator under both " +
      "option values, with and without a further calendar reader") {
    val root = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    val roots = Seq[VarkaVectorIR](root, new Year(root))
    // The count cycles across the whole guarded bound, both ends included; the day stays near
    // the epoch so add_months' own recompose never leaves the narrowed range even at the
    // bound's most extreme shift.
    val counts = Seq(VarkaChrono.MONTH_ARITH_MIN_MONTHS, VarkaChrono.MONTH_ARITH_MAX_MONTHS,
      0, 1, -1, 12, -12, 100, -100)
    val data = (c: Int, i: Int) =>
      if (c == 0) (i * 9973) % 40000 - 20000 else counts(i % counts.length)
    for (options <- Seq(VarkaEmitOptions.DEFAULTS, guardOff,
        VarkaEmitOptions.DEFAULTS.withCse(false))) {
      checkMatrix(roots, 2, Array.emptyIntArray, Seq(1, 17, 64, 65, 1000), combos(2),
        data = data, ctx = s"task 60 ${options.canonical()}", options = options)
    }
  }

  test("the guard is emitted only for a column-driven month count, and the literal " +
      "form's bytes do not move") {
    val literal = new AddMonths(new ColumnRef(0), new LiteralSlot(0))
    val column = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    val bodies = Seq("loopDense0", "loopMasked0", "epilogueDense0", "epilogueMasked0")
    def sizes(root: VarkaVectorIR, numInputs: Int, lits: Int, options: VarkaEmitOptions)
        : Seq[Int] = {
      val bytes = emitMulti(Seq(root), numInputs, lits, options)._2
      bodies.map(VarkaEmitterTestSupport.codeSize(bytes, _))
    }
    // The literal form: byte-identical under both settings, and identical to its shape before
    // this task (asserted below by the register itself).
    assert(sizes(literal, 1, 1, VarkaEmitOptions.DEFAULTS) === sizes(literal, 1, 1, guardOff))
    // The control for the count guard's bytes is the literal form, not the option: the count
    // guard is self-guarding and unconditional, so the option-off variant carries it too and
    // the two column runs are byte-identical. Only the day-producer guard answers to the flag.
    val guarded = sizes(column, 2, 0, VarkaEmitOptions.DEFAULTS)
    assert(guarded === sizes(column, 2, 0, guardOff),
      "guardDayProducers must not reach the self-guarding count check")
    // Every body of the column form carries the guard the literal form does not need.
    for ((body, (col, lit)) <- bodies.zip(guarded.zip(sizes(literal, 1, 1,
        VarkaEmitOptions.DEFAULTS)))) {
      assert(col > lit, s"$body: expected the guard's bytes, got $col vs $lit")
    }
  }

  test("the register PLAN_TASK_60.md 3.3 predicted - the guard costs two IntVector " +
      "compares on top of a column's load replacing a literal's broadcast") {
    val literal = new AddMonths(new ColumnRef(0), new LiteralSlot(0))
    val column = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    val literalOps = laneOps(emitMulti(Seq(literal), 1, 1)._2, "loopDense0")
    val guardedOps = laneOps(emitMulti(Seq(column), 2, 0)._2, "loopDense0")
    // The count guard is unconditional (it is the node's own correctness, and the compiler's
    // compile-time bound rests on it), so the option-off run is the same 114 rather than the
    // 112 an option-gated guard would give. The register's prediction is unaffected: it is
    // about the two compares the guard adds to the literal form's 112, which still holds.
    val optionOffOps = laneOps(emitMulti(Seq(column), 2, 0, guardOff)._2, "loopDense0")
    assert((literalOps, optionOffOps, guardedOps) === ((112, 114, 114)),
      s"the register: literal=$literalOps optionOff=$optionOffOps guarded=$guardedOps")
  }

  test("the shared prefix survives two calendar outputs in one loop method") {
    // Until B2 this ran under a widened GROUP_BUDGET, because the shipped grouping put every
    // calendar output in its own loop method and only the epilogue ever held two; it was the
    // shape B2 would ship, measured for correctness before it was measured for throughput.
    // B2 shipped it, so the defaults are that shape and the test runs under them.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](
      new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    assert(methodNames(emitMulti(roots, 1, 0, sharing)).count(_.startsWith("loopDense")) === 1,
      "the defaults did not put the four outputs in one loop method")
    checkMatrix(roots, 1, Array.empty[Int], remainderLengths ++ Seq(64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = "one loop method",
      options = sharing)
  }

  test("two calendar outputs over different dates share nothing") {
    // The fragment is keyed on the child, so year(d1) and year(d2) must each emit their own
    // prefix. A key that collapsed to the node type would silently answer d2 from d1's
    // decomposition - right-looking numbers, wrong rows, and no status to say so.
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)), new Year(new ColumnRef(1)))
    // Not the whole class: emitMulti gives every emission a fresh name, so the constant pool
    // differs whatever the body does. The epilogue is where two outputs meet, so its size is
    // the thing that would have moved had the two prefixes collapsed into one.
    assert(epilogueSize(roots, 2, sharing) === epilogueSize(roots, 2, unshared),
      "the epilogue moved for two outputs that have nothing to share")
    // And clause 2 does not put them in one loop method: the second reuses no prefix.
    assert(methodNames(emitMulti(roots, 2, 0, sharing)).count(_.startsWith("loopDense")) === 2,
      "two dates with nothing to share landed in one loop method")
    checkMatrix(roots, 2, Array.empty[Int], remainderLengths,
      // The second date is the first walked from a different index rather than shifted by a
      // constant: adding to a day that is already at the range's edge would push it out and
      // make the kernel decline, which is a guard result, not a sharing one.
      nullPatterns.map(p => Seq(p._2, p._2)), data = (c, i) => calendarDays(c, i + c * 3),
      ctx = "two dates", options = sharing)
  }

  test("the numerator costs what PLAN_TASK_53.md 3.4 registered, per tail") {
    // Registered before the work and asserted after, off the class file rather than reasoned
    // from the helpers. A miss here is a bug in the lowering, not a surprise about it: the
    // deltas are arithmetic on ops that either are or are not emitted.
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, lits: Int, neri: Boolean): Int =
      laneOps(emitMulti(Seq(root), 1, lits,
        VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(neri))._2, "loopDense0")
    for ((name, root, lits, delta) <- Seq(
        ("year", new Year(col), 0, 0),
        ("month", new Month(col), 0, -2),
        ("dayofmonth", new DayOfMonth(col), 0, -4),
        ("quarter", new Quarter(col), 0, -2),
        ("last_day", new LastDay(col), 0, -3),
        ("add_months", new AddMonths(col, new LiteralSlot(0)), 1, -4))) {
      assert(ops(root, lits, neri = true) - ops(root, lits, neri = false) === delta,
        s"$name moved by ${ops(root, lits, neri = true) - ops(root, lits, neri = false)} ops, " +
          s"not $delta - PLAN_TASK_53.md 3.4 needs updating with the reason")
    }
    // `year` is the control: it reads neither axis, so if it ever moves the numerator has
    // leaked into a tail that has no business seeing it.
    assert(ops(new Year(col), 0, neri = true) === ops(new Year(col), 0, neri = false),
      "the year tail must not change when only the month axis does")
  }

  test("the Julian map costs what PLAN_TASK_54.md 3.3 registered, per node") {
    // Off the class file, like task 53's: the prefix loses the century fold and the year-step
    // underflow correction and gains the map and a second carry, and the year assembly loses
    // the `100 * century` multiply-add. Registered before the run; a miss is a bug in the
    // lowering or an error in the registered accounting, and either way the plan says which.
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, lits: Int, julian: Boolean): Int =
      laneOps(emitMulti(Seq(root), 1, lits,
        VarkaEmitOptions.DEFAULTS.withJulianMap(julian))._2, "loopDense0")
    for ((name, root, lits, delta) <- Seq(
        ("year", new Year(col), 0, -5),
        ("month", new Month(col), 0, -3),
        ("dayofmonth", new DayOfMonth(col), 0, -3),
        ("quarter", new Quarter(col), 0, -3),
        ("dayofyear", new DayOfYear(col), 0, -5),
        ("last_day", new LastDay(col), 0, -5),
        ("add_months", new AddMonths(col, new LiteralSlot(0)), 1, -5))) {
      val moved = ops(root, lits, julian = true) - ops(root, lits, julian = false)
      assert(moved === delta,
        s"$name moved by $moved ops, not $delta - PLAN_TASK_54.md 3.3 needs updating with " +
          "the reason")
    }
  }

  test("a year-only body computes no month, and the switch says so") {
    assert(VarkaEmitOptions.DEFAULTS.elideChronoMonth(),
      "the elision is no longer the default - the case for it is in PLAN_TASK_48.md section " +
        "3.3, so say why here if it was deliberately turned off")
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    // Both axes, because task 53 changed what the step costs without changing whether it is
    // elided: four ops on the 0-based month, two on the numerator. The elision has to hold on
    // each, and asserting it on only the shipped one would let the reference variant rot.
    for (axis <- Seq(VarkaEmitOptions.DEFAULTS,
        VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(false))) {
      val elided = emitMulti(roots, 1, 0, axis)._2
      val kept = emitMulti(roots, 1, 0, axis.withElideChronoMonth(false))._2
      // Every body role, because every one of them runs the prefix: the two loop methods and
      // the two epilogues each hold this single year and nothing that reads a month.
      for (body <- Seq("loopDense0", "loopMasked0", "epilogueDense0", "epilogueMasked0")) {
        assert(laneOps(elided, body) === laneOps(kept, body) - monthStepOps(axis),
          s"$body did not lose exactly the month step at neri=${axis.neriSchneiderMonth()}: " +
            s"${laneOps(kept, body)} lane ops with the step kept, ${laneOps(elided, body)} " +
            "with it elided")
      }
    }
    // The four ops are dead work, so removing them is not allowed to move an answer.
    for ((options, ctx) <- Seq(
        (VarkaEmitOptions.DEFAULTS, "elided"),
        (VarkaEmitOptions.DEFAULTS.withElideChronoMonth(false), "kept"))) {
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = s"month step $ctx",
        options = options)
    }
  }

  test("the month step follows the group's consumers, not the emission order") {
    val col = new ColumnRef(0)
    for ((roots, ctx) <- Seq(
        (Seq[VarkaVectorIR](new Year(col), new Month(col)), "year first"),
        (Seq[VarkaVectorIR](new Month(col), new Year(col)), "month first"))) {
      val elided = emitMulti(roots, 1, 0, sharing)._2
      val kept = emitMulti(roots, 1, 0, sharing.withElideChronoMonth(false))._2
      // Under B2 the pair shares a loop method as well as the epilogue, so in both bodies the
      // one shared prefix is read by the month tail and must keep the step - whichever of the
      // two siblings happens to emit it. This is the whole reason the decision is read from
      // the group's consumer set rather than from the node being emitted.
      assert(methodNames(emitMulti(roots, 1, 0, sharing)).count(_.startsWith("loopMasked"))
        === 1, s"the pair no longer shares a loop method ($ctx)")
      for (body <- Seq("loopMasked0", "epilogueMasked0")) {
        assert(laneOps(elided, body) === laneOps(kept, body),
          s"$body elided the month step with a month tail reading it ($ctx)")
      }
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = s"shared, $ctx",
        options = sharing)
    }
    // Over different dates the two keep separate loop methods, and exactly one of them - the
    // year's, whose prefix no month tail reads - elides. This is the half the pre-B2 version of
    // the test asserted on year(d), month(d), when those were separate methods too.
    val split = Seq[VarkaVectorIR](new Year(col), new Month(new ColumnRef(1)))
    val elided = emitMulti(split, 2, 0, sharing)._2
    val kept = emitMulti(split, 2, 0, sharing.withElideChronoMonth(false))._2
    val saved = Seq("loopMasked0", "loopMasked1")
      .map(body => laneOps(kept, body) - laneOps(elided, body))
    assert(saved.sorted === Seq(0, monthStepOps(sharing)),
      s"expected exactly one loop method to elide the month step, saved $saved")
  }

  test("with sharing off the decision is per node, not per fragment") {
    // Unshared, year(d) and month(d) name different locals even though their fragment keys are
    // equal, so the year's own prefix elides and the month's does not - keying the decision on
    // the fragment there would make the year pay for a month it shares nothing with.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](new Year(col), new Month(col))
    val elided = emitMulti(roots, 1, 0, unshared)._2
    val kept = emitMulti(roots, 1, 0, unshared.withElideChronoMonth(false))._2
    assert(laneOps(elided, "epilogueMasked0") ===
      laneOps(kept, "epilogueMasked0") - monthStepOps(unshared),
      "the unshared epilogue holds two prefixes and exactly one of them - the year's - is " +
        "supposed to lose its month step")
    checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
      nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = "unshared, per node",
      options = unshared)
  }

  test("dayofyear elides the month step too, and month(d) beside it does not") {
    // The bounded counterpart of the sweep: dayofyear's tail reads the January turn off the
    // day of year (like Year's, task 48), so its prefix has no reason to run the month step.
    // A regression here is silent - the tail would read a local nothing wrote - so the count
    // is pinned rather than left to the sweep, which is opt-in.
    val col = new ColumnRef(0)
    val alone = Seq[VarkaVectorIR](new DayOfYear(col))
    // Both axes (task 53). What this node elides is whatever the prefix's month step costs on
    // the axis in force - four ops on the 0-based one, two on the numerator - so the assertion
    // is about the elision holding, not about a particular number.
    for (axis <- Seq(VarkaEmitOptions.DEFAULTS,
        VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(false))) {
      val elided = emitMulti(alone, 1, 0, axis)._2
      val kept = emitMulti(alone, 1, 0, axis.withElideChronoMonth(false))._2
      for (body <- Seq("loopDense0", "loopMasked0", "epilogueDense0", "epilogueMasked0")) {
        assert(laneOps(elided, body) === laneOps(kept, body) - monthStepOps(axis),
          s"$body did not lose exactly the month step at neri=${axis.neriSchneiderMonth()}: " +
            s"${laneOps(kept, body)} lane ops with the step kept, ${laneOps(elided, body)} " +
            "with it elided")
      }
    }
    // Shared with a month tail, the fragment keeps the step for both - the decision is the
    // group's consumer set, not the node's, and dayofyear must not elide out from under it.
    val withMonth = Seq[VarkaVectorIR](new DayOfYear(col), new Month(col))
    val sharedElided = emitMulti(withMonth, 1, 0, sharing)._2
    val sharedKept = emitMulti(withMonth, 1, 0, sharing.withElideChronoMonth(false))._2
    assert(laneOps(sharedElided, "epilogueMasked0") === laneOps(sharedKept, "epilogueMasked0"),
      "the shared epilogue elided the month step with a month tail reading it")
    for ((options, ctx) <- Seq((VarkaEmitOptions.DEFAULTS, "alone"), (sharing, "shared"))) {
      checkMatrix(if (ctx == "alone") alone else withMonth, 1, Array.empty[Int],
        remainderLengths, nullPatterns.map(p => Seq(p._2)), data = calendarDays,
        ctx = s"dayofyear month step, $ctx", options = options)
    }
  }

  test("the lanewise-DIV floorMod reference variant agrees with the shipped magic multiply") {
    val roots = Seq[VarkaVectorIR](new DayOfWeek(new ColumnRef(0)))
    val extremes = Array(Int.MinValue, Int.MaxValue, -1, 0, -7, 7)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 31 - 7000
    checkMatrix(roots, 1, Array.empty[Int], Seq(64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "div-variant",
      options = VarkaEmitOptions.DEFAULTS.withFloorMod7(VarkaEmitOptions.FloorMod7.DIV))
  }

  test("the digit-sum floorMod reference variant agrees with the shipped magic multiply") {
    // The task 11 lowering, kept as a reference: same matrix as the shipped path's own test,
    // with the 15-bit fold boundaries among the extremes.
    val roots = Seq[VarkaVectorIR](
      new DayOfWeek(new ColumnRef(0)), new WeekDay(new ColumnRef(0)))
    val extremes = Array(Int.MinValue, Int.MaxValue, Int.MinValue + 1, Int.MaxValue - 1,
      -1, 0, 1, -7, 7, -8, 8, 32767, 32768, -32768, -32769)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 997 - 300000
    checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "digit-sum-variant",
      options = VarkaEmitOptions.DEFAULTS.withFloorMod7(VarkaEmitOptions.FloorMod7.DIGIT_SUM))
  }
}
