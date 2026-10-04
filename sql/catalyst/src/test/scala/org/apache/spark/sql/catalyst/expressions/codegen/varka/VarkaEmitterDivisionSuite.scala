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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The constant divisions (`VarkaDivisionLowering`): the calendar prefix's divisions under the
 * three lowerings, and `ConstDivide`'s multiply-high, conversion and magic-number forms at both
 * lanes, each against Java's `/` over the range it is exact on, with the op counts that tell the
 * forms apart.
 */
class VarkaEmitterDivisionSuite extends VarkaEmitterTestBase {

  // -------------------------------------------------------------------------------------------
  // VARKA-88: the calendar prefix's constant divisions through the double lane.
  // -------------------------------------------------------------------------------------------

  private val divisionForms = VarkaEmitOptions.Division.values().toSeq

  /**
   * Roots reaching every division site the prefix and its tails contain: the era step and the
   * century/year split (or the Julian map in their place), the month and day-of-month steps, the
   * quarter, and the ISO week. `add_months` and `make_date` carry the two that are not in the
   * prefix - the month arithmetic's `/12` and the recomposition's `/400` and `/100` - and have
   * their own tests below, because their inputs are not a date column.
   */
  private val divisionRoots = Seq[VarkaVectorIR](
    new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
    new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
    new DayOfYear(new ColumnRef(0)), new LastDay(new ColumnRef(0)),
    new TruncDate(new ColumnRef(0), TruncLevel.YEAR),
    new TruncDate(new ColumnRef(0), TruncLevel.MONTH),
    new TruncDate(new ColumnRef(0), TruncLevel.QUARTER))

  test("the calendar extractions agree with LocalDate under all three division lowerings, " +
      "on both prefix forms") {
    // The double forms convert each int vector into two double vectors, divide there and convert
    // back. They are exact over the range each site's dividend stays in - which is what
    // sql/varka/plans/verify_double_division.py establishes - so agreeing with LocalDate here is
    // the check that the conversion parts, the join and the deny-list are all right at once. A
    // part index off by one would put a quotient in the wrong lane and show up as a wrong date,
    // not as a crash.
    for (form <- divisionForms; julian <- Seq(true, false)) {
      checkMatrix(divisionRoots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"division=$form julianMap=$julian",
        options = VarkaEmitOptions.DEFAULTS.withDivision(form).withJulianMap(julian))
    }
  }

  test("the double-lane divisions compute the same dates at every vector width") {
    // The double half of a 512-bit register holds four lanes and of a 128-bit one holds two, so
    // the lane count the conversion splits into is not the lane count the loop runs at. Each
    // width is emitted and run in turn, on the same boundary dates, so a width whose halves do
    // not tile the vector would answer differently rather than silently.
    for (form <- divisionForms; lanes <- Seq(2, 4, 8, 16)) {
      checkMatrix(divisionRoots, 1, Array.empty[Int], Seq(17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"division=$form lanes=$lanes",
        options = VarkaEmitOptions.DEFAULTS.withDivision(form).withLanesOverride(lanes))
    }
  }

  test("weekofyear agrees with IsoFields under all three division lowerings") {
    // The ISO week is the one site whose divisor is 7, and it sits behind the Thursday shift
    // rather than in the prefix proper.
    val thursday = new ThursdayOf(new ColumnRef(0))
    for (form <- divisionForms) {
      checkMatrix(Seq[VarkaVectorIR](new WeekOfYear(thursday)), 1, Array.empty[Int],
        Seq(1, 13, 17, 64, 1000), nullPatterns.map(p => Seq(p._2)), data = isoWeekDay,
        ctx = s"weekofyear division=$form",
        options = VarkaEmitOptions.DEFAULTS.withDivision(form))
    }
  }

  test("add_months agrees under all three division lowerings") {
    // The month arithmetic's own `/12`, which no extraction reaches, over a count range the
    // magic multiply's bound admits.
    val root = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    def data(c: Int, i: Int): Int = if (c == 0) calendarBoundaryDay(0, i) else i % 61 - 30
    for (form <- divisionForms) {
      checkMatrix(Seq[VarkaVectorIR](root), 2, Array.emptyIntArray, Seq(1, 13, 17, 64, 1000),
        combos(2), data = data, ctx = s"add_months division=$form",
        options = VarkaEmitOptions.DEFAULTS.withDivision(form))
    }
  }

  test("make_date and the recomposing trunc agree under all three division lowerings") {
    // Both reach `emitDaysFromCivil`, whose `/400` and `/100` share one multiplier and differ
    // only in the shift - the pair the division table exists to keep apart.
    for (form <- divisionForms) {
      val options = VarkaEmitOptions.DEFAULTS.withDivision(form)
      checkMatrix(Seq(makeDateNull), 3, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        combos(3), data = tripleData(makeDateValid), ctx = s"make_date division=$form",
        options = options)
      checkMatrix(truncRoots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"trunc recompose division=$form",
        options = options.withTruncDate(VarkaEmitOptions.TruncDateForm.RECOMPOSE))
    }
  }

  /** The ops one emitted body runs against a given vector class, counted off the class file. */
  private def opsOn(bytes: Array[Byte], owner: String): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0", s"jdk.incubator.vector.$owner")

  /**
   * How many `convertShape` calls a body makes - the lane-width conversions, and only those.
   * The reinterprets the AVX2 division uses are declared on `Vector` too and are not
   * conversions: they reread the same bits at another type, which is the whole point of that
   * form, so a count that included them would report the form it exists to distinguish.
   */
  private def convertShapes(bytes: Array[Byte]): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0", "jdk.incubator.vector.Vector",
      java.util.List.of("reinterpretAsDoubles", "reinterpretAsLongs"))

  /**
   * How many of a body's divisions took a double form: each emits exactly one `mul` or `div` per
   * half, so the `DoubleVector` count is twice the number of divisions that were lowered.
   */
  private def doubleDivisions(bytes: Array[Byte]): Int = {
    val halves = opsOn(bytes, "DoubleVector")
    assert(halves % 2 === 0, s"a double division emits two halves, saw $halves")
    // Each double division also converts twice in and twice out, all four on `Vector` itself.
    // The multiply-high form converts the same four times through long lanes and divides
    // nowhere, so the cross-check holds only where a double half exists.
    assert(halves == 0 || convertShapes(bytes) === halves * 2,
      s"expected ${halves * 2} conversions for $halves halves")
    halves / 2
  }

  test("a double-lane division costs seven ops where the magic costs two, and kills the carry") {
    // `year` over the shipped prefix divides three times - the era step, the Julian century and
    // the Julian year - and each of the three rounds down and is corrected by a carry. The magic
    // form spends two `IntVector` ops on the division and three more on the carry; the double
    // form spends four conversions, two divides and one `or` to rejoin the halves, and no carry
    // at all, because its quotient is exact.
    //
    // So the trade is not "seven against two" per division in isolation: against the magic form
    // plus its carry it is seven against five, and the seven move off the 32-bit multiplier.
    // Whether that wins is the A/B this option exists to run; that it is what gets emitted is
    // what these counts pin.
    val year = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    def bytes(form: VarkaEmitOptions.Division): Array[Byte] =
      emitMulti(year, 1, 0, VarkaEmitOptions.DEFAULTS.withDivision(form))._2

    val magic = bytes(VarkaEmitOptions.Division.MAGIC)
    assert(opsOn(magic, "IntVector") === 34)
    assert(doubleDivisions(magic) === 0)

    val div = bytes(VarkaEmitOptions.Division.DOUBLE_DIV)
    assert(doubleDivisions(div) === 3)
    // Each division loses its magic multiply and shift and gains one `or` (-1 each), and each of
    // the three carries the exact quotient makes dead goes away (-3 each).
    assert(opsOn(div, "IntVector") === 34 - 3 * 1 - 3 * 3)
  }

  test("the era step's /146097 falls back to the magic form under the reciprocal, and the " +
      "Julian century's does not") {
    // Both divide by 146097 and they answer differently, which is the whole reason the deny-list
    // is keyed on the site rather than on the divisor: the era step's dividend is any biased
    // day, and 146097 itself is one of them, where multiplying by fl(1/146097) rounds below the
    // integer; the Julian century's dividends are the values congruent to 3 mod 4, and that
    // multiple is not among them. `sql/varka/plans/verify_double_division.py` is what decides
    // this, and this test is the emitter obeying it.
    //
    // The narrowed prefix divides three times and the Julian one also three times - the era step
    // in both, then either the century and year of century, or the Julian century and year. If
    // the reciprocal were refused by divisor, the Julian shape would lose two divisions rather
    // than one.
    val year = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    for (julian <- Seq(false, true)) {
      def bytes(form: VarkaEmitOptions.Division): Array[Byte] =
        emitMulti(year, 1, 0,
          VarkaEmitOptions.DEFAULTS.withDivision(form).withJulianMap(julian))._2
      assert(doubleDivisions(bytes(VarkaEmitOptions.Division.DOUBLE_DIV)) === 3,
        s"julianMap=$julian")
      assert(doubleDivisions(bytes(VarkaEmitOptions.Division.DOUBLE_RECIP)) === 2,
        s"julianMap=$julian: exactly the era step should fall back")
    }
  }

  test("the shipped default emits no double-lane ops at all") {
    // The option is off by default, so no production kernel converts anything: the emitted bytes
    // for every calendar shape are what they were before this existed, which is also what keeps
    // VarkaEmittedBytesSuite's registered hashes valid without regenerating them.
    val bytes = emitMulti(divisionRoots, 1, 0)._2
    assert(doubleDivisions(bytes) === 0)
    assert(VarkaEmitOptions.DEFAULTS.division() === VarkaEmitOptions.Division.MAGIC)
  }

  test("the emitted calendar kernels agree over the whole covered range under both double " +
      "division forms (opt-in: -Dvarka.sweep=true; VARKA-88)") {
    // The bounded tests above run the double forms over a boundary list; this runs them over
    // every day the prefix covers, which is the only check at the resolution the deny-list was
    // decided at. `verify_double_division.py` proves the arithmetic exact over each site's
    // range; this proves the emitter divides the range it was proved over - a dividend that
    // escaped its bound, or a reciprocal admitted where the script refused it, is wrong on a
    // handful of days out of sixteen million and invisible to anything narrower.
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernels")
    val fields = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)))
    val doubleForms =
      Seq(VarkaEmitOptions.Division.DOUBLE_RECIP, VarkaEmitOptions.Division.DOUBLE_DIV)
    // Both prefix forms, because they divide by 146097 at different sites and the reciprocal is
    // admitted at one and refused at the other - the single most load-bearing row of the table.
    for (form <- doubleForms; julian <- Seq(true, false)) {
      val options = VarkaEmitOptions.DEFAULTS.withDivision(form).withJulianMap(julian)
      sweepCalendar(fields, options)
      sweepTrunc(options)
      sweepLastDay(options)
    }
  }

  // -------------------------------------------------------------------------------------------
  // VARKA-89: a constant division with no magic form, over the whole int32 range.
  // -------------------------------------------------------------------------------------------

  test("a constant division matches Java's `/` over the whole int32 range, at every width") {
    // `extract(YEAR FROM ym)` is `months / 12` over a month count nothing bounds, so the
    // calendar's range-narrowed magic cannot serve it and this node converts through double
    // lanes instead. Two things are being checked at once and they fail differently: that the
    // quotient is exact - which `sql/varka/plans/verify_ym_division.py` proves over all 2^32
    // counts, and which would break here at the extremes first - and that it *truncates toward
    // zero* rather than flooring, which is what a magic would have done and what would show up
    // only on negative dividends with a remainder.
    // Both forms, every divisor the emitter or the fuzz grammar divides by at this lane, and
    // the dividends around each divisor's multiples where truncation and floor part (task
    // 149). The multiply-high is the shipped form; the conversion form is the reference arm.
    for (d <- intDivisors; mulHi <- Seq(true, false)) {
      val root = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), d))
      val m = math.abs(d)
      val extremes = Array(Int.MinValue, Int.MinValue + 1, Int.MaxValue, Int.MaxValue - 1,
        -1, 0, 1, -m + 1, m - 1, -m, m, -m - 1, m + 1, -49151, 49151, -49152, 49152,
        (Int.MaxValue / m) * m, (Int.MinValue / m) * m, (Int.MaxValue / m) * m + 1,
        (Int.MinValue / m) * m - 1)
      def dividends(c: Int, i: Int): Int =
        if (i < extremes.length) extremes(i) else i * 7919 - 1000000
      for (lanes <- Seq(0, 2, 4, 8, 16)) {
        checkMatrix(root, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
          nullPatterns.map(p => Seq(p._2)), data = dividends,
          ctx = s"divc/$d mulHi=$mulHi lanes=$lanes",
          options = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes).withMulHiDivide(mulHi))
      }
    }
  }

  /**
   * The int-lane constant divisors in use: `extract(YEAR FROM ym)`'s twelve, the fuzz
   * grammar's list (`VarkaIrGrammar.ConstDivideDivisors`), and the `TIME` split form's two.
   * The sweep below proves the multiply-high form over all 2^32 dividends for each of them.
   */
  private val intDivisors = Seq(12, 2, 3, 7, 100, -3, -12, 60, 3600)

  test("the multiply-high form's constants are Hacker's Delight's") {
    // The derivation is the book's; the constants it must produce for the divisors the book
    // works are known, and a derivation that drifted would produce a form that is merely
    // nearly exact - which the sweep would catch, at a price this catches for free.
    def magic(d: Int): (Long, Long) = {
      val m = VarkaDivisionLowering.signedMagicForTest(d)
      (m(0), m(1))
    }
    assert(magic(12) === (0x2AAAAAABL, 32 + 1))
    assert(magic(7) === (0x92492493L, 32 + 2))
    assert(magic(3) === (0x55555556L, 32 + 0))
    assert(magic(2) === (0x80000001L, 32 + 0))
    assert(magic(100) === (0x51EB851FL, 32 + 5))
    intercept[IllegalArgumentException](VarkaDivisionLowering.signedMagicForTest(1))
  }

  // The proof the emitted arithmetic rests on, run as the arithmetic: the unsigned multiplier,
  // the one shift and the sign bit, against Java's `/`, for all 2^32 dividends and every divisor
  // in `intDivisors`. Scalar, not the kernel - the kernel's parity over the extremes and the
  // fuzzer's random dividends are above; this is the exhaustive half. One test per divisor, so a
  // failure names its divisor and each stays well inside the test watchdog's cap; the comparison
  // is a plain branch, since an assert with an interpolated clue builds a string on every one of
  // the 2^32 iterations, which kept the single test of nine divisors running for hours
  // (VARKA-283.md).
  for (d <- intDivisors) {
    test(s"the multiply-high form is exact over every int32 dividend for divisor $d " +
        "(opt-in: -Dvarka.sweep=true; VARKA-149)") {
      assume(System.getProperty("varka.sweep") == "true",
        "set -Dvarka.sweep=true to sweep the multiply-high form")
      val magic = VarkaDivisionLowering.signedMagicForTest(math.abs(d))
      val mu = magic(0)
      val shift = magic(1).toInt
      val sign = if (d < 0) -1 else 1
      var n = Int.MinValue
      var done = false
      while (!done) {
        val q = (((n.toLong * mu) >> shift) + (n >>> 31)).toInt * sign
        if (q != n / d) {
          fail(s"d=$d n=$n: the multiply-high form gives $q where Java's / gives ${n / d}")
        }
        if (n == Int.MaxValue) done = true else n += 1
      }
    }
  }

  test("a constant division takes the multiply-high form by default and the double lane as " +
      "the reference arm, whatever the division option says") {
    // The `division` option chooses among the lowerings the *calendar* has and does not reach
    // this node. What does is `mulHiDivide`: on, the body has no double-lane op at all and
    // carries the multiply-high's four long-lane ops - two multiplies, two shifts - and four
    // conversions; off, the conversion form's two double divides. Neither option can leave the
    // node with no lowering.
    val root = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 12))
    for (form <- VarkaEmitOptions.Division.values()) {
      val mulHi = emitMulti(root, 1, 0, VarkaEmitOptions.DEFAULTS.withDivision(form))._2
      assert(doubleDivisions(mulHi) === 0, s"division=$form")
      assert(opsOn(mulHi, "LongVector") === 4, s"division=$form")
      assert(convertShapes(mulHi) === 4, s"division=$form")
      val converting = emitMulti(root, 1, 0,
        VarkaEmitOptions.DEFAULTS.withDivision(form).withMulHiDivide(false))._2
      assert(doubleDivisions(converting) === 1, s"division=$form")
      assert(opsOn(converting, "LongVector") === 0, s"division=$form")
    }
  }

  test("a constant division by zero or by -1 is refused rather than emitted") {
    // Zero has no quotient at all, and -1 overflows at Integer.MinValue - the one input where
    // Java's `/` throws rather than answering. Both are the row engine's to raise, so the
    // shapes are refused where they are built rather than emitted as something plausible.
    val col = new ColumnRef(0, LaneType.INT)
    val zero = intercept[IllegalArgumentException](new ConstDivide(col, 0))
    assert(zero.getMessage.contains("division by zero"), zero.getMessage)
    val minusOne =
      intercept[IllegalArgumentException](emitMulti(Seq(new ConstDivide(col, -1)), 1, 0))
    assert(minusOne.getMessage.contains("overflows at Integer.MIN_VALUE"), minusOne.getMessage)
  }

  test("a constant division by one emits no conversion at all") {
    // The identity. Nothing requires the compiler to have folded it, and emitting a conversion
    // round trip for it would be a silent cost on a shape that does nothing.
    val root = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 1))
    assert(doubleDivisions(emitMulti(root, 1, 0)._2) === 0)
    checkMatrix(root, 1, Array.empty[Int], Seq(17, 1000), nullPatterns.map(p => Seq(p._2)),
      ctx = "divc/1")
  }

  // -------------------------------------------------------------------------------------------
  // VARKA-88 step 3: the same division at 64-bit lanes, where the conversion is same-width.
  // -------------------------------------------------------------------------------------------

  /**
   * `doubleDivisions`' twin at the long lane. A 64-bit lane and a double lane are the same
   * width, so one division is one divide and one conversion each way - there is no second half
   * to count and nothing to rejoin.
   */
  private def longDoubleDivisions(bytes: Array[Byte]): Int = {
    val divides = opsOn(bytes, "DoubleVector")
    assert(convertShapes(bytes) === divides * 2,
      s"expected ${divides * 2} conversions for $divides divisions")
    divides
  }

  /**
   * The dividends worth driving a division by `d` over: zero, both signs of the divisor's own
   * neighbourhood - where truncation and floor differ and where a reciprocal would fail at an
   * exact multiple - and both ends of the range the lowering is exact over. Derived from the
   * divisor rather than written out, so a divisor added to the list below cannot end up
   * exercised only far from its own multiples.
   */
  private def dividendsAround(d: Long): Array[Long] = {
    val bound = ConstDivide.EXACT_DIVIDEND_BOUND - 1
    Array(0L, 1L, -1L, d, d - 1, d + 1, -d, -(d - 1), -(d + 1), 2 * d, 2 * d - 1, -(2 * d) + 1,
      bound, -bound, bound - 1, -(bound - 1)).map(v => math.max(-bound, math.min(bound, v)))
  }

  test("a long-lane constant division matches Java's `/` over the range it is exact on") {
    // The divisors VARKA-102 and VARKA-103 need, each over its own neighbourhood and both ends of
    // the exact range. Two things fail differently here: precision, which breaks at the ends of the
    // range first, and truncation toward zero, which a floor-producing lowering gets wrong only
    // on negative dividends with a remainder - hence both signs of every value.
    val col = new ConstDivide(new ColumnRef(0, LaneType.LONG), 1, ConstDivide.EXACT_DIVIDEND_BOUND)
    // The level is pinned rather than inherited. `DEFAULTS.useAVX` is the machine's, so
    // without this the test would check the conversion form on an AVX-512 host and the magic
    // form on every other - covering one lowering twice and the other never, on a machine
    // nobody chose. The magic form has its own test below, at its own pinned level.
    val converting = VarkaEmitOptions.DEFAULTS.withUseAVX(3)
    val divisors = Seq(
      3_600_000_000_000L,   // hour(t), nanos per hour
      60_000_000_000L,      // minute(t) step 1
      1_000_000_000L,       // second(t) step 1
      1_000_000L,           // time_trunc to milliseconds
      1_000L,               // t1 - t2, nanos per micro
      86_400_000_000L,      // extract(DAY FROM dt), micros per day
      60L,                  // minute(t) and second(t) step 2
      -60L)                 // a negative divisor, whose quotient truncates the other way
    for (d <- divisors; lanes <- Seq(2, 8)) {
      val root = Seq[VarkaVectorIR](
        new ConstDivide(new ColumnRef(0, LaneType.LONG), d, ConstDivide.EXACT_DIVIDEND_BOUND))
      val vs = dividendsAround(d)
      checkLongMatrix(root, 1, Array.empty[Long], Seq(1, 7, 17, 64, 129), combos(1),
        (_, i) => vs(i % vs.length), s"long divc/$d", lanes, converting)
    }
    assert(col.divisor() === 1)
  }

  test("the AVX2 form computes the same quotients as the conversions, at both signs") {
    // A host whose L2D and D2L do not intrinsify takes a magic-number form instead: no
    // conversion instruction at all, a floor built by hand out of a compare and a masked
    // subtract, and the sign applied afterwards because the identity needs a non-negative
    // operand and produces a floor where Java truncates. Every one of those is a place the two
    // forms could disagree, so they are driven over the same dividends and required to agree
    // with the same reference. The option is set explicitly rather than inherited: the
    // arithmetic is correct on any machine, and it is the lowering that is under test.
    val avx2 = VarkaEmitOptions.DEFAULTS.withUseAVX(2)
    val divisors = Seq(3_600_000_000_000L, 1_000_000_000L, 1_000L, 86_400_000_000L, 60L, -60L)
    for (d <- divisors; lanes <- Seq(2, 8)) {
      val root = Seq[VarkaVectorIR](
        new ConstDivide(new ColumnRef(0, LaneType.LONG), d, ConstDivide.EXACT_DIVIDEND_BOUND))
      val vs = dividendsAround(d)
      checkLongMatrix(root, 1, Array.empty[Long], Seq(1, 7, 17, 64, 129), combos(1),
        (_, i) => vs(i % vs.length), s"avx2 divc/$d", lanes, avx2)
    }
  }

  test("the AVX2 form is chosen by the level, and only at the long lane") {
    // What selects it, stated as bytes rather than as intent. `convertShape` is the conversion
    // form's signature call and the magic form has none; the int lane has no magic form at all,
    // since its dividend is exactly representable and the conversion is what it is built on.
    val long64 = Seq[VarkaVectorIR](
      new ConstDivide(new ColumnRef(0, LaneType.LONG), 1000, ConstDivide.EXACT_DIVIDEND_BOUND))
    val int32 = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 12))
    def converts(roots: Seq[VarkaVectorIR], options: VarkaEmitOptions): Int =
      convertShapes(emitMulti(roots, 1, 0, options)._2)
    val defaults = VarkaEmitOptions.DEFAULTS
    assert(converts(long64, defaults.withUseAVX(2)) === 0, "AVX2 emits no conversion")
    assert(converts(long64, defaults.withUseAVX(3)) === 2, "AVX-512 converts in and out")
    // A machine that reports no level has told us nothing against its converts, so it keeps
    // them rather than paying for a form it may not need.
    assert(converts(long64, defaults.withUseAVX(VarkaEmitOptions.USE_AVX_UNKNOWN)) === 2)
    assert(converts(int32, defaults.withUseAVX(2)) === 4, "the int lane is not affected")

    // And what the form costs, counted from the bytes rather than from the plan that sketched
    // it: seven double ops (the two halves of the identity, the divide, the round and its
    // correction), two reinterprets, and five long ops - fourteen against the conversion form's
    // three. `VARKA-88.md` 3.3 registered nine, before the signed case was decided;
    // section 9.2 records the correction.
    //
    // The long count excludes the loads and stores, which belong to the body and not to the
    // division: what it pins is the sign handling, which is the part the correction note is
    // about and the part a reader might think is removable.
    val bytes = emitMulti(long64, 1, 0, defaults.withUseAVX(2))._2
    assert(opsOn(bytes, "DoubleVector") === 7, "the double half of the magic form")
    assert(opsOn(bytes, "Vector") === 2, "two reinterprets and no conversion")
    val longOps = VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0",
      "jdk.incubator.vector.LongVector",
      java.util.List.of("fromMemorySegment", "intoMemorySegment", "broadcast"))
    assert(longOps === 5, "the magnitude, the identity's OR and mask, and the sign tail")
  }

  test("a long-lane constant division converts once each way, where the int lane converts twice") {
    // The op counts of `VARKA-88.md` 3.3: three operations at the long lane against seven at
    // the int one. The saving is structural rather than incidental - an int vector has twice the
    // lanes of the double vector it converts into, so it needs two halves and a join, while a
    // 64-bit lane pairs one to one.
    // Both levels are named. `DEFAULTS.useAVX` is whatever the machine reports, so emitting
    // with it would assert the conversion form's shape against whichever form the host picked.
    val converting = VarkaEmitOptions.DEFAULTS.withUseAVX(3)
    val long64 = Seq[VarkaVectorIR](
      new ConstDivide(new ColumnRef(0, LaneType.LONG), 1000, ConstDivide.EXACT_DIVIDEND_BOUND))
    val int32 = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 12))
    assert(longDoubleDivisions(emitMulti(long64, 1, 0, converting)._2) === 1)
    assert(doubleDivisions(emitMulti(int32, 1, 0, converting.withMulHiDivide(false))._2) === 1)
    // The counter above is what makes the difference explicit: the int lane spends two divides
    // and four conversions on one division, the long lane one and two.
    assert(opsOn(emitMulti(long64, 1, 0, converting)._2, "DoubleVector") === 1)
    // The int lane's conversion form is the reference arm now; the shipped form multiplies.
    assert(opsOn(emitMulti(int32, 1, 0, converting.withMulHiDivide(false))._2,
      "DoubleVector") === 2)
  }

  test("both 64-bit division forms run through the JVM at the dividend bound: exact below " +
      "it, and above it each fails in the shape its lowering predicts") {
    // Row 166. `ConstDivide.EXACT_DIVIDEND_BOUND` is 2^52, and until now the claim that the
    // conversion form is exact under 2^53 and the magic form exact under 2^52 and wrong above
    // rested on `verify_double_division.py`, a model of the lowerings, while the long-lane
    // fuzzer's columns stop at 2^46. This drives the emitted kernels themselves over the bits
    // no test had visited, against Java's `/`, and asserts not only where each form is right
    // but the *shape* of how it is wrong past its bound - a failure of another shape would
    // mean the model is wrong, which is the thing worth finding.
    //
    // The shapes, from `VarkaDivisionLowering`'s own account. The conversion form divides in
    // doubles: exact while the dividend is under 2^53, where a correctly rounded quotient
    // cannot cross an integer; above that the dividend itself rounds by up to one, so the
    // quotient is within one of Java's. The magic form reads the dividend's magnitude through
    // the `0x4330000000000000` identity, whose mantissa is 52 bits: from 2^52 to 2^54 the OR
    // is idempotent on the exponent bits the magnitude sets, so the value read back is the
    // magnitude modulo 2^52, and the quotient is that of the low 52 bits with the sign applied
    // afterwards.
    val mask52 = (1L << 52) - 1
    val divisors = Seq(3_600_000_000_000L, 60_000_000_000L, 1_000_000_000L, 1_000L,
      86_400_000_000L, 60L, -60L)
    val forms = Seq(
      ("conversion", VarkaEmitOptions.DEFAULTS),
      ("magic", VarkaEmitOptions.DEFAULTS.withUseAVX(2)))
    // A region is [base, 2 * base): the ends, the first multiple boundary, one deep inside.
    def region(base: Long, d: Long): Seq[Long] = {
      val m = math.abs(d)
      Seq(base, base + 1, base + m - 1, base + m, base + m + 1, base + 12_345_678_901L,
        2 * base - 1, 2 * base - m, 2 * base - m + 1).flatMap(v => Seq(v, -v))
    }
    def magicShape(v: Long, d: Long): Long = {
      val q = (math.abs(v) & mask52) / math.abs(d)
      if ((v < 0) != (d < 0)) -q else q
    }
    for ((name, options) <- forms; d <- divisors; lanes <- Seq(2, 8)) {
      val root = Seq[VarkaVectorIR](
        new ConstDivide(new ColumnRef(0, LaneType.LONG), d, ConstDivide.EXACT_DIVIDEND_BOUND))
      val (kernel, loader) = load(emitMulti(root, 1, 0, options.withLanesOverride(lanes)))
      try {
        def run(values: Seq[Long], length: Int): Seq[Long] = {
          val arena = Arena.ofConfined()
          try {
            val in = makeLongInput(arena, length, _ => false, i => values(i % values.length))
            val (data, validity) = makeLongOutput(arena, length)
            val st = kernel.run(Array(in.data.address()), Array(in.validityAddress(length)),
              Array(in.nullCount), Array(data.address()), Array(validity.address()),
              Array.empty[Int], Array.empty[Long], length)
            assert(st === 0, s"$name / $d lanes=$lanes: an unguarded division reports nothing")
            (0 until length).map(i => data.get(ValueLayout.JAVA_LONG, i * 8L))
          } finally {
            arena.close()
          }
        }
        for ((base, tier) <- Seq((1L << 51, "under"), (1L << 52, "past 2^52"),
            (1L << 53, "past 2^53")); length <- Seq(64, 17)) {
          val values = region(base, d)
          val out = run(values, length)
          for (i <- 0 until length) {
            val v = values(i % values.length); val q = v / d
            val clue = s"$name / $d lanes=$lanes $tier v=$v: got ${out(i)}, Java says $q"
            (name, tier) match {
              case (_, "under") => assert(out(i) === q, clue)
              case ("conversion", "past 2^52") => assert(out(i) === q, clue)
              case ("conversion", _) => assert(math.abs(out(i) - q) <= 1, clue)
              case ("magic", _) => assert(out(i) === magicShape(v, d), clue)
            }
          }
        }
      } finally {
        loader.release()
      }
    }
  }

  test("a long-lane constant division refuses the divisors that have no quotient") {
    // The int lane's refusals, restated at the width they now apply to. The -1 message names
    // the lane's own most negative value, because that is the input it is about.
    val col = new ColumnRef(0, LaneType.LONG)
    val bound = ConstDivide.EXACT_DIVIDEND_BOUND
    val zero = intercept[IllegalArgumentException](new ConstDivide(col, 0, bound))
    assert(zero.getMessage.contains("division by zero"), zero.getMessage)
    val minusOne =
      intercept[IllegalArgumentException](emitMulti(Seq(new ConstDivide(col, -1, bound)), 1, 0))
    assert(minusOne.getMessage.contains("overflows at Long.MIN_VALUE"), minusOne.getMessage)
    // And the bound itself: the two-argument form is the int lane's, so a long-lane division
    // that leaves the bound out is refused where it is built rather than emitted unguarded,
    // and a bound no lowering can honour is refused beside it (VARKA-147).
    val unstated = intercept[IllegalArgumentException](new ConstDivide(col, 60))
    assert(unstated.getMessage.contains("must state the dividend bound"), unstated.getMessage)
    val overClaimed =
      intercept[IllegalArgumentException](new ConstDivide(col, 60, bound + 1))
    assert(overClaimed.getMessage.contains("dividend bound must lie in"), overClaimed.getMessage)
    // A guard discharges the obligation only if it delivers what the division then claims:
    // the guard admits its own endpoints, so a claim equal to them is one short.
    val short = intercept[IllegalArgumentException](
      emitMulti(Seq(new ConstDivide(new GuardedRange(col, -1000L, 1000L), 60, 1000L)), 1, 0))
    assert(short.getMessage.contains("the guard below it admits"), short.getMessage)
    emitMulti(Seq(new ConstDivide(new GuardedRange(col, -1000L, 1000L), 60, 1001L)), 1, 0)
    // And a divisor the int lane cannot hold is a mistake in the tree rather than a division
    // whose every quotient is zero.
    val tooWide = intercept[IllegalArgumentException](
      new ConstDivide(new ColumnRef(0, LaneType.INT), 1L << 40))
    assert(tooWide.getMessage.contains("needs an int divisor"), tooWide.getMessage)
  }

  // -------------------------------------------------------------------------------------------
  // VARKA-102: a range guard at the long lane, which is what makes `TIME + INTERVAL` a decline
  // rather than a wrap where Spark throws.
  // -------------------------------------------------------------------------------------------

  test("a bounded division is exact over its whole bound at both widths, and refuses a pair " +
      "that does not exist") {
    // VARKA-102.md 8.4: one multiply and one logical shift, exact for every dividend under
    // the bound by the constructor's search. The batch is the bound long, so every dividend
    // the node is defined over is compared once against the true division, in a full lane
    // group and in the tail, at 128 and 512 bits.
    for ((d, bound) <- Seq((3600, 86400), (60, 3600), (7, 4096), (24, 1440)); lanes <- Seq(4, 16)) {
      val root = Seq[VarkaVectorIR](BoundedDivide.of(new ColumnRef(0), d, bound))
      checkMatrix(root, 1, Array.empty[Int], Seq(1, 17, bound), combos(1),
        data = (_, i) => i % bound, ctx = s"divb $d over $bound",
        options = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes))
    }
    // No single multiply is exact for / 60 over the whole day: the product would pass 2^32.
    val e = intercept[IllegalArgumentException](BoundedDivide.of(new ColumnRef(0), 60, 86400))
    assert(e.getMessage.contains("no exact single-multiply form"), e.getMessage)
    // And a pair handed in by hand is checked against the search rather than trusted.
    val wrong = intercept[IllegalArgumentException](
      new BoundedDivide(new ColumnRef(0), 3600, 86400, 37283, 26))
    assert(wrong.getMessage.contains("is not the exact single-multiply form"), wrong.getMessage)
  }
}
