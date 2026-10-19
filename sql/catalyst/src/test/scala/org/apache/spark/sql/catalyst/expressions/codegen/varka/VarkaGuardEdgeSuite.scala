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

import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, AttributeReference, DateAdd => SqlDateAdd, DateDiff => SqlDateDiff, DayOfWeek => SqlDayOfWeek, EvalMode, Expression, Literal, Month => SqlMonth, WeekDay => SqlWeekDay, Year => SqlYear}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaGuardSpec.{Shift, Verdict}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.types.DateType

/**
 * Every guard on both sides of its bound (VARKA-279 3.2). For each of the emitter's six guards, a
 * small kernel that carries it, and inputs at the bound and one past it on both sides - `lo - 1`,
 * `lo`, `hi`, `hi + 1` - in a full vector lane (row 0) and in the epilogue lane (row 16 of 17,
 * past every whole vector at 128, 256 and 512 bits for both lanes), the edge row valid and, in a
 * second batch, null in the column that carries it; [[VarkaKernelCheck]] runs each batch through
 * both of the kernel's bodies, the other body clearing the nulls, so a null edge becomes a valid
 * one there. The kernel's status, read under `exactRefusalStatus`, is held to
 * [[VarkaGuardSpec]]'s verdict for each body, and where the verdict is an answer every row is
 * compared with the reference evaluator. The seventh guard, the per-batch input bound, has no IR
 * node: it is `IntRangeOps.allWithin` in the evaluator, and is driven there. A date column's
 * bound is not that one since VARKA-379 but an int range guard over the column, which the
 * compiler places: its edges are here beside the other range guard's, and a section of its own
 * drives it through the compiler.
 *
 * The hand-written edge tests stay as the readable examples; this suite is what makes every guard
 * have them. A planted wrong bound - each end of each guard moved by one, on the verdict's side -
 * must make the suite fail, so it cannot pass vacuously: the edge inputs land exactly on each
 * bound and one past it, so an emitter whose bound were off by one would disagree with the
 * verdict on the same rows. The emitter's side cannot be moved from a test: its bounds are
 * `static final` fields, the narrow range and the month limits constants javac inlines into the
 * emitter, the `make_date` years computed when `VarkaChrono` loads.
 */
class VarkaGuardEdgeSuite extends SparkFunSuite with VarkaMatrixTests {

  private val length = 17
  private val positions = Seq(0 -> "a full vector lane", 16 -> "the epilogue lane")
  private val classCounter = new AtomicInteger(0)
  /**
   * The matrix's options with every guard this suite tests on, so that a configuration that turns
   * one off (the producer guard, the overflow check) still leaves the guard under test, and with
   * each reason's own status bit.
   */
  private def options: VarkaEmitOptions = VarkaMatrix.base.withExactRefusalStatus(true)
    .withCheckIntOverflow(true).withGuardDayProducers(true)

  // ---------------------------------------------------------------------------------------------
  // The guards and their edges
  // ---------------------------------------------------------------------------------------------

  /**
   * One guard's kernel and edges: `filler` is a row every guard admits, each edge a row with its
   * label, and `column` the input the edge's null twin nulls.
   */
  private case class Guarded(name: String, reason: Analysis.Refusal, roots: Seq[VarkaVectorIR],
      long: Boolean, filler: Seq[Long], edges: Seq[(String, Seq[Long])], column: Int,
      lits: Array[Long] = Array.empty) {
    def numInputs: Int = filler.size
    /** The kernel's guards, read once. */
    lazy val spec = new VarkaGuardSpec.Guards(roots, numInputs, lits.length, options, long)
  }

  private val narrowLo = VarkaChrono.NARROW_MIN_DAYS.toLong
  private val narrowHi = VarkaChrono.NARROW_MAX_DAYS.toLong

  /** `lo - 1`, `lo`, `hi`, `hi + 1`, each with its label. */
  private def around(lo: Long, hi: Long): Seq[(String, Long)] =
    Seq("lo - 1" -> (lo - 1), "lo" -> lo, "hi" -> hi, "hi + 1" -> (hi + 1))

  private lazy val guarded: Seq[Guarded] = {
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    val c2 = new ColumnRef(2)
    val l0 = new ColumnRef(0, LaneType.LONG)
    val l1 = new ColumnRef(1, LaneType.LONG)
    val months = around(VarkaChrono.MONTH_ARITH_MIN_MONTHS, VarkaChrono.MONTH_ARITH_MAX_MONTHS)
    val years = around(VarkaChrono.MAKE_DATE_MIN_YEAR, VarkaChrono.MAKE_DATE_MAX_YEAR)
    // An operand pair whose exact sum (or difference) is lo - 1, lo, hi and hi + 1 of the lane.
    def sums(min: Long, max: Long, sub: Boolean): Seq[(String, Seq[Long])] = {
      val s = if (sub) -1L else 1L
      Seq("lo - 1" -> Seq(min, -s), "lo" -> Seq(min, 0L), "hi" -> Seq(max, 0L),
        "hi + 1" -> Seq(max, s))
    }
    Seq(
      // A column month count, on a day well inside the range.
      Guarded("add_months over a column count", Analysis.Refusal.MONTH_COUNT,
        Seq(new AddMonths(c0, c1)), long = false, Seq(0L, 7L),
        months.map { case (l, m) => l -> Seq(0L, m) }, column = 1),
      // make_date's year in both modes, a valid date at the limits.
      Guarded("make_date's year (NULL form)", Analysis.Refusal.MAKE_DATE,
        Seq(new MakeDate(c0, c1, c2, false)), long = false, Seq(2000L, 6L, 15L),
        years.map { case (l, y) => l -> Seq(y, 1L, 1L) }, column = 0),
      Guarded("make_date's year (ANSI form)", Analysis.Refusal.MAKE_DATE,
        Seq(new MakeDate(c0, c1, c2, true)), long = false, Seq(2000L, 6L, 15L),
        years.map { case (l, y) => l -> Seq(y, 1L, 1L) }, column = 0),
      // make_date's date under ANSI, either side of February's last day in a leap year and not:
      // the NULL form answers null where the ANSI form must decline.
      Guarded("make_date's day (ANSI form)", Analysis.Refusal.MAKE_DATE,
        Seq(new MakeDate(c0, c1, c2, true)), long = false, Seq(2000L, 6L, 15L),
        Seq("29 February 2024" -> Seq(2024L, 2L, 29L), "30 February 2024" -> Seq(2024L, 2L, 30L),
          "28 February 2023" -> Seq(2023L, 2L, 28L), "29 February 2023" -> Seq(2023L, 2L, 29L),
          "month 12" -> Seq(2024L, 12L, 1L), "month 13" -> Seq(2024L, 13L, 1L)), column = 2),
      Guarded("make_date's day (NULL form)", Analysis.Refusal.MAKE_DATE,
        Seq(new MakeDate(c0, c1, c2, false)), long = false, Seq(2000L, 6L, 15L),
        Seq("30 February 2024" -> Seq(2024L, 2L, 30L), "month 13" -> Seq(2024L, 13L, 1L)),
        column = 2),
      // Checked arithmetic at both ends of each lane.
      Guarded("a checked int add", Analysis.Refusal.INT_OVERFLOW,
        Seq(new IntArith(IntOp.ADD, Overflow.FAIL, c0, c1)), long = false, Seq(3L, 4L),
        sums(Int.MinValue, Int.MaxValue, sub = false), column = 0),
      Guarded("a checked int subtract", Analysis.Refusal.INT_OVERFLOW,
        Seq(new IntArith(IntOp.SUB, Overflow.FAIL, c0, c1)), long = false, Seq(3L, 4L),
        sums(Int.MinValue, Int.MaxValue, sub = true), column = 0),
      Guarded("a checked int negation", Analysis.Refusal.INT_OVERFLOW,
        Seq(new IntNeg(Overflow.FAIL, c0)), long = false, Seq(3L),
        Seq("operand lo" -> Seq(Int.MinValue.toLong), "operand lo + 1" ->
          Seq(Int.MinValue + 1L), "operand hi" -> Seq(Int.MaxValue.toLong)), column = 0),
      Guarded("a checked long add", Analysis.Refusal.INT_OVERFLOW,
        Seq(new IntArith(IntOp.ADD, Overflow.FAIL, l0, l1)), long = true, Seq(3L, 4L),
        sums(Long.MinValue, Long.MaxValue, sub = false), column = 0),
      Guarded("a checked long subtract", Analysis.Refusal.INT_OVERFLOW,
        Seq(new IntArith(IntOp.SUB, Overflow.FAIL, l0, l1)), long = true, Seq(3L, 4L),
        sums(Long.MinValue, Long.MaxValue, sub = true), column = 0),
      Guarded("a checked long negation", Analysis.Refusal.INT_OVERFLOW,
        Seq(new IntNeg(Overflow.FAIL, l0)), long = true, Seq(3L),
        Seq("operand lo" -> Seq(Long.MinValue), "operand lo + 1" -> Seq(Long.MinValue + 1),
          "operand hi" -> Seq(Long.MaxValue)), column = 0),
      // The re-armed day check, over a column day.
      Guarded("the day range on a column day", Analysis.Refusal.DAY_RANGE,
        Seq(new Year(new GuardedDay(c0))), long = false, Seq(0L),
        around(narrowLo, narrowHi).map { case (l, d) => l -> Seq(d) }, column = 0),
      // A range guard with the bounds it carries, at both lanes.
      Guarded("an int range guard", Analysis.Refusal.VALUE_RANGE,
        Seq(new GuardedRange(c0, -1000L, 1000L)), long = false, Seq(5L),
        around(-1000L, 1000L).map { case (l, v) => l -> Seq(v) }, column = 0),
      // The column bound the compiler guards a date column with (VARKA-379), under the
      // calendar field it lets fuse.
      Guarded("the column bound on a date column", Analysis.Refusal.VALUE_RANGE,
        Seq(new Year(new GuardedRange(c0, VarkaChrono.CONTRACT_MIN_DAYS,
          VarkaChrono.CONTRACT_MAX_DAYS))), long = false, Seq(0L),
        around(VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS).map {
          case (l, d) => l -> Seq(d) }, column = 0),
      Guarded("a long range guard", Analysis.Refusal.VALUE_RANGE,
        Seq(new GuardedRange(l0, 0L, 86399999999999L)), long = true, Seq(5L),
        around(0L, 86399999999999L).map { case (l, v) => l -> Seq(v) }, column = 0),
      // A column-offset day producer a calendar node reads, its result at the range's ends.
      Guarded("a column-offset add a calendar node reads", Analysis.Refusal.DAY_PRODUCER,
        Seq(new Year(new AddDays(c0, c1))), long = false, Seq(0L, 3L),
        around(narrowLo, narrowHi).map { case (l, d) => l -> Seq(0L, d) }, column = 1),
      Guarded("a column-offset subtract a calendar node reads", Analysis.Refusal.DAY_PRODUCER,
        Seq(new Month(new SubDays(c0, c1))), long = false, Seq(0L, 3L),
        around(narrowLo, narrowHi).map { case (l, d) => l -> Seq(0L, -d) }, column = 1))
  }

  // ---------------------------------------------------------------------------------------------
  // Running one edge
  // ---------------------------------------------------------------------------------------------

  /**
   * One batch of one guard's edge: each body's status, in the order the check runs them (the
   * batch's own body, then the other), and each body's rows.
   */
  private case class Ran(g: Guarded, label: String, statuses: Seq[Int],
      bodies: Seq[Seq[Seq[Option[Long]]]]) {
    def declined: Boolean = statuses.exists(_ != 0)
    def answered: Boolean = statuses.contains(0)
  }

  private def rowsOf(data: Array[Array[Long]],
      patterns: Seq[Int => Boolean]): Seq[Seq[Option[Long]]] =
    (0 until length).map { i =>
      data.indices.map(c => if (patterns(c)(i)) None else Some(data(c)(i)))
    }

  /** Whether every body's status is one its own verdict admits. */
  private def agrees(statuses: Seq[Int], verdicts: Seq[Verdict]): Boolean =
    statuses.size == verdicts.size && statuses.zip(verdicts).forall { case (s, v) => v.admits(s) }

  private def verdicts(r: Ran, shifts: Seq[Shift] = Nil): Seq[Verdict] =
    r.bodies.map(rows => VarkaGuardSpec.verdict(r.g.spec, r.g.lits, rows, shifts))

  /**
   * Every edge of `g`, at every position, three ways: valid in the dense body, valid with the
   * masked body forced (a null count over a full bitmap), and null in the column that carries it.
   * Each runs through both bodies, the second whatever the first returned, so the masked body meets
   * a valid out-of-range edge and the dense one a null edge made valid.
   */
  private def run(g: Guarded): Seq[Ran] = {
    val className =
      s"org.apache.spark.sql.varka.execution.VarkaGuardEdge${classCounter.incrementAndGet()}"
    val bytes = VarkaLoopEmitter.emit(className, g.roots.asJava, g.numInputs, g.lits.length,
      null, null, options)
    val ways =
      Seq((false, false, ""), (false, true, ", masked body forced"), (true, false, ", null"))
    for ((edge, values) <- g.edges; (at, where) <- positions; (nulled, forced, how) <- ways)
        yield {
      val data = Array.tabulate(g.numInputs, length) { (c, i) =>
        if (i == at) values(c) else g.filler(c)
      }
      val patterns: Seq[Int => Boolean] = (0 until g.numInputs).map { c =>
        (i: Int) => nulled && c == g.column && i == at
      }
      val label = s"${g.name} at $edge in $where$how"
      val statuses = if (g.long) {
        VarkaKernelCheck.runEachBodyLongStatus(label, className, bytes, g.roots, g.numInputs,
          g.lits, VarkaKernelCheck.LongBatch(length, patterns, data, forceMasked = forced))
      } else {
        VarkaKernelCheck.runEachBodyStatus(label, className, bytes, g.roots, g.numInputs,
          g.lits.map(_.toInt), VarkaKernelCheck.Batch(length, patterns,
            data.map(_.map(_.toInt)), forceMasked = forced))
      }
      val other = VarkaKernelCheck.otherBody(g.numInputs, length, patterns, forced).map(_._1)
      Ran(g, label, statuses, (patterns +: other.toSeq).map(p => rowsOf(data, p)))
    }
  }

  private lazy val ran: Seq[Ran] = guarded.flatMap(run)

  // ---------------------------------------------------------------------------------------------
  // The edges
  // ---------------------------------------------------------------------------------------------

  test("every guard declines one past its bound and answers at it, in a full vector lane and " +
      "the epilogue lane, valid and null, through both bodies") {
    val wrong = ran.filterNot(r => agrees(r.statuses, verdicts(r)))
    assert(wrong.isEmpty, s"${wrong.size} of ${ran.size} edge batches disagree with the " +
      "verdict:\n" + wrong.take(20).map(r =>
        s"${r.label}: statuses ${r.statuses.mkString(", ")}, verdicts " +
          verdicts(r).mkString(", ")).mkString("\n"))
    // Counted, not assumed: every guard both declined and answered somewhere. By guard, not by
    // kernel: make_date's NULL form answers an invalid day with a null and never declines it.
    for ((reason, rs) <- ran.groupBy(_.g.reason)) {
      assert(rs.exists(_.declined), s"$reason: no edge declined")
      assert(rs.exists(_.answered), s"$reason: no edge was answered")
    }
    logInfo(s"${ran.size} edge batches over ${guarded.size} guarded kernels, " +
      s"${ran.count(_.declined)} declined in some body")
  }

  test("a bound moved by one on the verdict's side fails the edges, at each end of each guard") {
    // The suite's own non-vacuity: each end of each guard moved by one in either direction must
    // turn some edge batch against the verdict. A shift outward admits a row the kernel
    // declines; inward, condemns one it answers.
    val reasons = guarded.map(_.reason).distinct
    var shifts = 0
    for (reason <- reasons; (lo, hi) <- Seq((1L, 0L), (-1L, 0L), (0L, 1L), (0L, -1L))) {
      val shifted = Seq(Shift(reason, lo, hi))
      val caught = ran.filter(_.g.reason == reason)
        .exists(r => !agrees(r.statuses, verdicts(r, shifted)))
      assert(caught, s"$reason with its bound moved by ($lo, $hi) still agrees with every edge")
      shifts += 1
    }
    assert(shifts == reasons.size * 4 && reasons.size == 6, s"$shifts shifts over $reasons")
  }

  // ---------------------------------------------------------------------------------------------
  // Under an arm
  // ---------------------------------------------------------------------------------------------

  test("a guard under an arm no row takes is masked when qualified, and may decline when not") {
    // if (c0 > c2) guardedRange(c1, -1000, 1000) else c2, on a batch with no nulls, so both
    // bodies see the same values: row 3 takes the else arm with c1 out of range, every other row
    // the then arm in range. Qualified by its arm, the guard must not fire on row 3; unqualified
    // (guardUnderArm off) it fires there, which the verdict allows and does not require - and the
    // kernel is held to what it does, so neither case passes vacuously. Taking the then arm out
    // of range must decline, qualified or not.
    val g = new GuardedRange(new ColumnRef(1), -1000L, 1000L)
    val root = new IfElse(new Compare(CompareOp.GT, new ColumnRef(0), new ColumnRef(2)), g,
      new ColumnRef(2))
    def value(c: Int)(i: Int): Long = c match {
      case 0 => if (i == 3) 1L else 10L
      case 1 => if (i == 3) 5000L else i.toLong
      case _ => 2L
    }
    val untaken = Array.tabulate(3, length)((c, i) => value(c)(i))
    val taken = Array.tabulate(3, length)((c, i) => if (c == 1 && i == 5) 5000L else value(c)(i))
    val none: Seq[Int => Boolean] = Seq.fill(3)((_: Int) => false)
    val bit = VarkaFusedKernel.STATUS_VALUE_RANGE
    def statuses(o: VarkaEmitOptions, data: Array[Array[Long]], label: String): Seq[Int] = {
      val name =
        s"org.apache.spark.sql.varka.execution.VarkaGuardArm${classCounter.incrementAndGet()}"
      val bytes = VarkaLoopEmitter.emit(name, Seq[VarkaVectorIR](root).asJava, 3, 0, null, null, o)
      VarkaKernelCheck.runEachBodyStatus(label, name, bytes, Seq(root), 3, Array.empty,
        VarkaKernelCheck.Batch(length, none, data.map(_.map(_.toInt)), forceMasked = false))
    }
    for ((under, expected, kernelBits) <- Seq((true, VarkaGuardSpec.Answer, 0),
        (false, Verdict(0, bit), bit))) {
      val o = options.withGuardUnderArm(under)
      val label = s"guardUnderArm=$under, the arm untaken"
      val verdict = VarkaGuardSpec.verdict(Seq(root), 3, Array.empty, o, long = false,
        rowsOf(untaken, none))
      assert(verdict == expected, s"$label: verdict $verdict")
      val st = statuses(o, untaken, label)
      assert(st.forall(verdict.admits), s"$label: statuses $st, verdict $verdict")
      assert(st.forall(_ == kernelBits),
        s"$label: statuses $st, the kernel should give $kernelBits")
    }
    for (under <- Seq(true, false)) {
      val o = options.withGuardUnderArm(under)
      val label = s"guardUnderArm=$under, the arm taken"
      // Row 5 takes the then arm out of range, so the bit is a must; with the guard unqualified,
      // row 3's untaken value adds it as a may as well.
      val verdict = VarkaGuardSpec.verdict(Seq(root), 3, Array.empty, o, long = false,
        rowsOf(taken, none))
      assert(verdict.must == bit, s"$label: verdict $verdict")
      val st = statuses(o, taken, label)
      assert(st.forall(_ == bit), s"$label: statuses $st")
    }
  }

  test("a guard above a FAIL lane that overflowed may fire on the wrapped value, and the kernel " +
      "names both reasons") {
    // `d - ym` at the one count whose negation overflows: `AddMonths(c0, IntNeg(FAIL, c1))`. The
    // negation condemns the batch, its lane keeps the wrapped Int.MinValue and stays valid, and
    // the month count reads it and fires too; the reference evaluator has no value for an
    // overflowed FAIL lane, so the verdict allows the count's bit rather than requiring it. Found
    // by the coverage rows' partitions (VARKA-279 9.2), where one batch per row had hidden it
    // behind the OR of the batch's rows. One in from the overflow, the negation is the int's top
    // and the count alone fires; an ordinary count is answered.
    val root = new AddMonths(new ColumnRef(0), new IntNeg(Overflow.FAIL, new ColumnRef(1)))
    val o = VarkaFusedKernel.STATUS_INT_OVERFLOW
    val m = VarkaFusedKernel.STATUS_MONTH_COUNT
    val none: Seq[Int => Boolean] = Seq.fill(2)((_: Int) => false)
    val cases = Seq(
      ("the count whose negation overflows", Int.MinValue.toLong, Verdict(o, m), o | m),
      ("the count whose negation is the int's top", Int.MinValue + 1L, Verdict(m, 0), m),
      ("an ordinary count", -5L, VarkaGuardSpec.Answer, 0))
    for ((label, count, expected, status) <- cases) {
      val data = Array.tabulate(2, length)((c, i) => if (c == 0) 0L else if (i == 3) count else 7L)
      val verdict = VarkaGuardSpec.verdict(Seq(root), 2, Array.empty, options, long = false,
        rowsOf(data, none))
      assert(verdict == expected, s"$label: verdict $verdict")
      val name =
        s"org.apache.spark.sql.varka.execution.VarkaGuardWrapped${classCounter.incrementAndGet()}"
      val bytes = VarkaLoopEmitter.emit(name, Seq[VarkaVectorIR](root).asJava, 2, 0, null, null,
        options)
      val st = VarkaKernelCheck.runEachBodyStatus(label, name, bytes, Seq(root), 2, Array.empty,
        VarkaKernelCheck.Batch(length, none, data.map(_.map(_.toInt)), forceMasked = false))
      assert(st.forall(_ == status), s"$label: statuses $st, the kernel should give $status")
      assert(st.forall(verdict.admits), s"$label: statuses $st, verdict $verdict")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The input bound
  // ---------------------------------------------------------------------------------------------

  test("the per-batch input bound admits its limits and refuses one past, in a full vector " +
      "lane and the epilogue lane, and never on a null") {
    // CAST(i AS INTERVAL DAY)'s bound, as VarkaChronoCompiler records it: the evaluator declines
    // a batch with a live lane outside it before the kernel runs (IntRangeOps.allWithin).
    val limit = VarkaChrono.INTERVAL_DAY_LIMIT_DAYS
    def within(at: Int, value: Long, nulled: Boolean): Boolean =
      IntRangeOpsSuite.allWithin(Array.tabulate(length)(i => if (i == at) value.toInt else 7),
        i => nulled && i == at, -limit, limit)
    // The bound as written, then each end moved by one each way on the expectation's side: every
    // move must turn some edge against it, so neither end is checked vacuously.
    for ((dLo, dHi) <- Seq((0L, 0L), (1L, 0L), (-1L, 0L), (0L, 1L), (0L, -1L))) {
      val cases = for ((label, v) <- around(-limit.toLong, limit.toLong);
          (at, where) <- positions; nulled <- Seq(false, true)) yield {
        val expected = nulled || (v >= -limit + dLo && v <= limit + dHi)
        (s"$label in $where${if (nulled) ", null" else ""}", within(at, v, nulled) == expected)
      }
      if (dLo == 0 && dHi == 0) {
        val wrong = cases.filterNot(_._2)
        assert(wrong.isEmpty, s"the input bound disagrees at: ${wrong.map(_._1).mkString("; ")}")
      } else {
        assert(cases.exists(!_._2),
          s"the input bound moved by ($dLo, $dHi) agrees with every edge")
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The column bound, through the compiler (VARKA-379)
  // ---------------------------------------------------------------------------------------------

  test("VARKA-379: the column bound is checked in the kernel's own loop, in both bodies, in a " +
      "full lane group and the tail, and in a split method, by a min and a max in the loop") {
    // The compiler guards a date column whose bound an admission rested on; this drives the
    // kernels it builds. A day one past the bound declines the batch in a full lane group (row
    // 0) and in the tail (row 16 of 17), in the dense body and in the masked one, the loop's
    // guard a min and a max compared once after the loop. The guard reads the column's stored
    // days and not its validity (VARKA-379 11); the harness stores the bound's own ends under a
    // null of such a column (VarkaKernelChecks.storedUnderNull), so a batch whose only far days
    // are under null rows is answered by its own, masked body, and declined by the dense one once
    // its nulls are cleared and those days read as valid. The masked body forced over a batch
    // with no nulls answers inside the bound and declines one far day. The split
    // shape puts the guarded output in a method of its own, last, so its guard is in a group
    // method other than the first: the budget of 700 bytes holds the guarded month's loop under
    // every validity form (506 bytes at the defaults, 611 masked with validityByBitmap off) and
    // not the two outputs before it with it (803). At the JVM's own width, one species per test JVM
    // (VARKA-246): the gate runs the suite at the host's width and under -XX:MaxVectorSize=16,
    // and row 16 is past every whole lane group at 128, 256 and 512 bits.
    val d = AttributeReference("d", DateType)()
    val d2 = AttributeReference("d2", DateType)()
    val bit = VarkaFusedKernel.STATUS_VALUE_RANGE
    val shapes: Seq[(String, Seq[Expression], Int)] = Seq(
      ("year(d)", Seq(SqlYear(d)), -1),
      ("datediff(d, d2) + 1 under ANSI", Seq(Add(SqlDateDiff(d, d2), Literal(1), EvalMode.ANSI)),
        -1),
      ("a kernel split by output, the guarded one last",
        Seq(SqlDayOfWeek(d2), SqlWeekDay(d2), SqlMonth(d)), 700))
    var batches = 0
    for ((name, entries, budget) <- shapes) {
      val base = VarkaMatrix.base.withExactRefusalStatus(true).withSeveralKernels(false)
      val o = if (budget > 0) base.withMethodByteBudget(budget) else base
      val named = entries.map(e => Alias(e, "c")())
      val compiled = VarkaExpressionCompiler.compile(named, Seq(d, d2), o).toScala.getOrElse(
        fail(s"$name does not fuse: " + VarkaExpressionCompiler.declines(named, Seq(d, d2), o)))
      val roots = compiled.outputs.asScala.toSeq
      val numInputs = compiled.inputOrdinals.size
      // The kernel inputs the compiler guarded: the date columns the bound is checked on.
      def guardedIn(n: VarkaVectorIR): Set[Int] = n match {
        case g: GuardedRange => g.child() match {
          case c: ColumnRef => Set(c.ordinal())
          case other => guardedIn(other)
        }
        case other => VarkaVectorIR.childrenOf(other).toSeq.flatMap(guardedIn).toSet
      }
      val guardedInputs = roots.flatMap(guardedIn).toSet
      assert(guardedInputs.nonEmpty, s"$name: no column is guarded in ${roots.mkString("; ")}")
      assert(compiled.inputBounds.isEmpty, s"$name: the bound reached the evaluator's pre-pass")
      val className =
        s"org.apache.spark.sql.varka.execution.VarkaColumnBound${classCounter.incrementAndGet()}"
      val bytes = VarkaLoopEmitter.emit(className, compiled.outputs, numInputs,
        compiled.numLiterals, null, null, o)
      if (budget > 0) {
        // The guard's bound is a constant in the method that holds it: a group's loop other than
        // the first, the outputs before the guarded one having filled that.
        val bodies = VarkaEmitterTestSupport.methodBodies(bytes).asScala
        val loops = bodies.keys.filter(_.startsWith("loopDense")).toSeq
        val guarding =
          loops.filter(m => bodies(m).contains(VarkaChrono.CONTRACT_MAX_DAYS.toString))
        assert(loops.size >= 2 && guarding.nonEmpty &&
          !guarding.exists(_.startsWith("loopDense0(")),
          s"$name: loop methods ${loops.mkString(", ")}, the guard in ${guarding.mkString(", ")}")
      }
      val lits = compiled.literals.asScala.map(_.intValue).toArray
      for (k <- guardedInputs) {
        // (label, the rows holding a far day, the rows null in column k, forced masked, each
        //  body's status: its own, then the other's, which clears the nulls and keeps the data)
        val cases: Seq[(String, Set[Int], Set[Int], Boolean, Seq[Int])] = Seq(
          ("every day inside", Set.empty, Set.empty, false, Seq(0, 0)),
          ("a far day in a full lane group, dense", Set(0), Set.empty, false, Seq(bit, bit)),
          ("a far day in the tail, dense", Set(16), Set.empty, false, Seq(bit, bit)),
          ("a far day in a full lane group, masked", Set(0), Set(5), false, Seq(bit, bit)),
          ("a far day in the tail, masked", Set(16), Set(5), false, Seq(bit, bit)),
          ("every day inside, masked body forced", Set.empty, Set.empty, true, Seq(0, 0)),
          ("a far day in a full lane group, masked body forced", Set(0), Set.empty, true,
            Seq(bit, bit)),
          ("a far day in the tail, masked body forced", Set(16), Set.empty, true, Seq(bit, bit)),
          // The masked body runs null rows over what the harness stores under them, the
          // bound's ends; the other body clears the nulls and keeps the data.
          ("poison under nulls in a lane group and the tail", Set.empty, Set(3, 16), false,
            Seq(0, 0)),
          ("far days under nulls in a lane group and the tail", Set(3, 16), Set(3, 16), false,
            Seq(0, bit)))
        for ((label, far, nullRows, forced, want) <- cases) {
          val data = Array.tabulate(numInputs, length) { (c, i) =>
            if (c == k && far.contains(i)) VarkaChrono.CONTRACT_MAX_DAYS + 1 else 19000 + i * 400
          }
          val patterns: Seq[Int => Boolean] =
            (0 until numInputs).map(c => (i: Int) => c == k && nullRows.contains(i))
          val context = s"$name, input $k: $label"
          val st = VarkaKernelCheck.runEachBodyStatus(context, className, bytes, roots,
            numInputs, lits, VarkaKernelCheck.Batch(length, patterns, data, forceMasked = forced))
          assert(st == want, s"$context: statuses $st, want $want")
          batches += 1
        }
      }
    }
    // Three shapes, four guarded inputs among them, ten batches each.
    assert(batches == 4 * 10, s"$batches batches")
  }

  test("VARKA-379: in a projection split across kernels, the kernel holding the guard declines " +
      "a far day while the one before it serves") {
    // Under the op cap (the byte budget off) with several kernels, thirty-two entries over d2,
    // 64 ops, fill the first kernel and the calendar fields over d are the second's, the only one
    // with the guard. A far day in d is declined by the second kernel after the first has
    // answered; the evaluator then sends the whole batch to the row engine, which
    // `VarkaFarDaySuite` checks end to end. Inside the bound both kernels answer as the
    // reference evaluator does.
    val d = AttributeReference("d", DateType)()
    val d2 = AttributeReference("d2", DateType)()
    val entries = (1 to 32).map(k => SqlDayOfWeek(SqlDateAdd(d2, Literal(k)))) ++
      (1 to 12).map(k => SqlYear(SqlDateAdd(d, Literal(k))))
    val o = VarkaMatrix.base.withMethodByteBudget(0).withSeveralKernels(true)
      .withExactRefusalStatus(true)
    val partial = VarkaExpressionCompiler.compilePartial(entries.map(e => Alias(e, "c")()),
      Seq(d, d2), o).toScala.getOrElse(fail("the projection does not fuse"))
    val kernels = partial.fused +: partial.more.asScala.toSeq
    assert(kernels.size == 2 && partial.declines.isEmpty, s"${kernels.size} kernels, " +
      s"declines ${partial.declines}")
    def guarded(n: VarkaVectorIR): Boolean =
      n.isInstanceOf[GuardedRange] || VarkaVectorIR.childrenOf(n).exists(guarded)
    assert(kernels.map(_.outputs.asScala.exists(guarded)) == Seq(false, true),
      "the guard belongs to the second kernel alone")
    val bit = VarkaFusedKernel.STATUS_VALUE_RANGE
    for ((far, want) <- Seq(false -> Seq(0, 0), true -> Seq(0, bit))) {
      val statuses = kernels.map { k =>
        val roots = k.outputs.asScala.toSeq
        val numInputs = k.inputOrdinals.size
        val className =
          s"org.apache.spark.sql.varka.execution.VarkaColumnBound${classCounter.incrementAndGet()}"
        val bytes = VarkaLoopEmitter.emit(className, k.outputs, numInputs, k.numLiterals, null,
          null, o)
        // Kernel input i reads child column inputOrdinals(i): 0 is d, 1 is d2.
        val data = Array.tabulate(numInputs, length) { (c, i) =>
          if (far && k.inputOrdinals.get(c) == 0 && i == 16) VarkaChrono.CONTRACT_MAX_DAYS + 1
          else 19000 + i * 400
        }
        VarkaKernelCheck.runAndCompareStatus(s"kernel over ${k.inputOrdinals}, far=$far",
          className, bytes, roots, numInputs, k.literals.asScala.map(_.intValue).toArray,
          VarkaKernelCheck.Batch(length, Seq.fill(numInputs)((_: Int) => false), data,
            forceMasked = false), declineAllowed = true)
      }
      assert(statuses == want, s"far=$far: statuses $statuses, want $want")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The verdict against the hand-written tests
  // ---------------------------------------------------------------------------------------------

  test("the verdict agrees with the cases the hand-written edge tests pin") {
    // Literal values from the tests that pinned each guard before this suite, so the verdict is
    // held to expectations written apart from it. One row each.
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    val c2 = new ColumnRef(2)
    val m = VarkaFusedKernel.STATUS_MONTH_COUNT
    val y = VarkaFusedKernel.STATUS_MAKE_DATE
    val o = VarkaFusedKernel.STATUS_INT_OVERFLOW
    val r = VarkaFusedKernel.STATUS_VALUE_RANGE
    val p = VarkaFusedKernel.STATUS_DAY_PRODUCER
    val d = VarkaFusedKernel.STATUS_DAY_RANGE
    val composed = new Year(new GuardedDay(new AddMonths(new AddDays(c0, c1), c2)))
    val cases: Seq[(String, VarkaVectorIR, Boolean, Seq[Long], Int)] = Seq(
      // VarkaEmitterChronoSuite, "a column month count declines the batch" (~1404)
      ("month count 24564", new AddMonths(c0, c1), false, Seq(0L, 24564L), 0),
      ("month count 24565", new AddMonths(c0, c1), false, Seq(0L, 24565L), m),
      // VarkaEmitterArithmeticSuite, "a FAIL lane that overflows condemns the batch" (~504)
      ("Int.MaxValue + 1", new IntArith(IntOp.ADD, Overflow.FAIL, c0, c1), false,
        Seq(Int.MaxValue.toLong, 1L), o),
      ("Int.MaxValue + 0", new IntArith(IntOp.ADD, Overflow.FAIL, c0, c1), false,
        Seq(Int.MaxValue.toLong, 0L), 0),
      // VarkaEmitterLongLaneSuite, "a long-lane range guard" (~278): 0 to 86399999999999
      ("range -1", new GuardedRange(new ColumnRef(0, LaneType.LONG), 0L, 86399999999999L), true,
        Seq(-1L), r),
      ("range 86399999999999", new GuardedRange(new ColumnRef(0, LaneType.LONG), 0L,
        86399999999999L), true, Seq(86399999999999L), 0),
      // VarkaChronoSuite, "the scalar makeDate is LocalDate.of at the corners" (~289), under ANSI
      ("make_date(2024, 2, 29) ANSI", new MakeDate(c0, c1, c2, true), false,
        Seq(2024L, 2L, 29L), 0),
      ("make_date(2023, 2, 29) ANSI", new MakeDate(c0, c1, c2, true), false,
        Seq(2023L, 2L, 29L), y),
      ("make_date(2024, 13, 1) ANSI", new MakeDate(c0, c1, c2, true), false,
        Seq(2024L, 13L, 1L), y),
      ("make_date(2024, 13, 1) NULL", new MakeDate(c0, c1, c2, false), false,
        Seq(2024L, 13L, 1L), 0),
      // VarkaChronoSuite, "the make_date year limits are the whole years of the narrow range"
      // (~281): years -12799 and 33133 are the limits.
      ("make_date(-12799, 1, 1)", new MakeDate(c0, c1, c2, false), false,
        Seq(-12799L, 1L, 1L), 0),
      ("make_date(-12800, 1, 1)", new MakeDate(c0, c1, c2, false), false,
        Seq(-12800L, 1L, 1L), y),
      // VarkaEmitterChronoSuite, "a column-offset producer under a calendar node" (~1284)
      ("year(d + (NARROW_MAX + 1))", new Year(new AddDays(c0, c1)), false,
        Seq(0L, narrowHi + 1), p),
      ("month of a difference one day under the range", new Month(new SubDays(c0, c1)), false,
        Seq(0L, -(narrowLo - 1)), p),
      // VarkaEmitterChronoSuite, "the re-armed check fires on the composed day" (~102): the
      // producer in range, the month add carrying the day past it.
      ("composed day past the range", composed, false, Seq(narrowHi - 10, 5L, 1L), d),
      ("composed day inside", composed, false, Seq(1000L, 5L, 1L), 0))
    for ((label, root, long, row, bits) <- cases) {
      val v = VarkaGuardSpec.verdict(Seq(root), row.size, Array.empty, options, long,
        Seq(row.map(Some(_))))
      assert(v == Verdict(bits, 0), s"$label: verdict $v, the hand-written test pins $bits")
    }
  }
}
