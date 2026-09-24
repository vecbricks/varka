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

import java.time.LocalDate
import java.util.function.IntUnaryOperator

import scala.collection.mutable
import scala.util.Random

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaIrGrammar._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaRangeAnalysis.{GuardPolicy, Kind}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaValueRange.{Bounded, Range, UNKNOWN}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils

/**
 * The value-range lattice and the analysis over it (task 84).
 *
 * Three groups of tests. The lattice laws: every operation saturates to unknown instead of
 * wrapping, hull is a join, a product contains every corner, unknown absorbs. The transfer
 * functions: one test per row of `PLAN_TASK_84.md` table 3.4, each against the interval the table
 * gives, so "reproduced, not tightened" is asserted rather than reviewed. And the property test
 * milestone 5's section 2.15 says the task is not done without: over random IR from the shared
 * grammar, random literal tables and random lane rows with nulls, at every node and for the
 * kind its slot gives it, the interval the analysis reports contains the value the reference
 * evaluator computes, on every row the runtime guards would let through.
 *
 * Before the compiler was switched over, this property test was also run against the two
 * functions the analysis replaced, and an equivalence test compared the two answer for answer
 * over the same trees (`PLAN_TASK_84.md` section 9 has the numbers); both went with the old
 * functions. Budget: `-Dvarka.range.trees` (default 10000) and `-Dvarka.range.seed` (default
 * fixed), on the fuzz suite's precedent.
 */
class VarkaRangeAnalysisSuite extends SparkFunSuite {

  private val seed = sys.props.get("varka.range.seed").map(_.toLong).getOrElse(20260916L)
  private val trees = sys.props.get("varka.range.trees").map(_.toInt).getOrElse(10000)

  private def lits(values: Int*): IntUnaryOperator = i => values(i)
  private val noLits: IntUnaryOperator = _ => throw new IllegalStateException("no literal slots")

  private def analysis(node: VarkaVectorIR, kind: Kind, policy: GuardPolicy,
      literals: IntUnaryOperator): Range = VarkaRangeAnalysis.range(node, kind, policy, literals)

  private def day(node: VarkaVectorIR, policy: GuardPolicy, literals: IntUnaryOperator = noLits) =
    analysis(node, Kind.DAY, policy, literals)
  private def int(node: VarkaVectorIR, literals: IntUnaryOperator = noLits) =
    analysis(node, Kind.INT, GuardPolicy.NONE, literals)

  private val contract = VarkaRangeAnalysis.CONTRACT
  private val narrow = VarkaRangeAnalysis.NARROW
  private def sym(m: Long): Range = VarkaValueRange.symmetric(m)
  private def r(lo: Long, hi: Long): Range = VarkaValueRange.of(lo, hi)

  // ---------------------------------------------------------------------------------------
  // The lattice
  // ---------------------------------------------------------------------------------------

  test("every operation saturates to unknown at the Long extremes instead of wrapping") {
    val top = r(Long.MaxValue - 1, Long.MaxValue)
    val bottom = r(Long.MinValue, Long.MinValue + 1)
    assert(top.shift(0, 1) === UNKNOWN)
    assert(bottom.shift(-1, 0) === UNKNOWN)
    assert(top.add(r(1, 1)) === UNKNOWN)
    assert(bottom.sub(r(1, 1)) === UNKNOWN)
    assert(sym(Long.MaxValue).mul(sym(2)) === UNKNOWN)
    assert(r(Long.MinValue, 0).neg() === UNKNOWN)
    assert(r(Long.MinValue, 0).abs() === UNKNOWN)
    assert(r(Long.MinValue, 0).magnitude().isEmpty)
    // The task 63 case stated of the type: two nested bounds whose product passes 2^63 used to
    // come back small and positive; now they come back unknown, and unknown proves nothing.
    val big = sym(1L << 40)
    assert(big.mul(big) === UNKNOWN)
    assert(!UNKNOWN.fitsInt())
    assert(!UNKNOWN.within(Long.MinValue, Long.MaxValue))
  }

  test("unknown absorbs every operation") {
    val b = r(-3, 5)
    assert(UNKNOWN.shift(1, 2) === UNKNOWN)
    assert(UNKNOWN.hull(b) === UNKNOWN && b.hull(UNKNOWN) === UNKNOWN)
    assert(UNKNOWN.add(b) === UNKNOWN && b.add(UNKNOWN) === UNKNOWN)
    assert(UNKNOWN.sub(b) === UNKNOWN && b.sub(UNKNOWN) === UNKNOWN)
    assert(UNKNOWN.mul(b) === UNKNOWN && b.mul(UNKNOWN) === UNKNOWN)
    assert(UNKNOWN.neg() === UNKNOWN && UNKNOWN.abs() === UNKNOWN)
    assert(UNKNOWN.magnitude().isEmpty)
  }

  test("hull is commutative, associative and idempotent; shift composes; products hold corners") {
    val rnd = new Random(seed)
    def draw(): Range = {
      val a = rnd.nextLong() >> rnd.nextInt(64)
      val b = rnd.nextLong() >> rnd.nextInt(64)
      r(math.min(a, b), math.max(a, b))
    }
    for (_ <- 0 until 5000) {
      val (a, b, c) = (draw(), draw(), draw())
      assert(a.hull(b) === b.hull(a))
      assert(a.hull(b).hull(c) === a.hull(b.hull(c)))
      assert(a.hull(a) === a)
      // A shift is an interval too, so its low end is at or below its high end - which is how
      // every transfer function calls it; an inverted pair is a caller's bug and throws.
      val (d1, d2) = (rnd.nextInt(1000) - 500, rnd.nextInt(1000) - 500)
      val (s1, s2) = (math.min(d1, d2), math.max(d1, d2))
      val twice = a.shift(s1, s2).shift(s1, s2)
      val once = a.shift(2L * s1, 2L * s2)
      assert(twice === once || twice === UNKNOWN || once === UNKNOWN)
      (a.mul(b), a, b) match {
        case (p: Bounded, x: Bounded, y: Bounded) =>
          // A bounded product means every corner product was exact, so plain `*` is exact here.
          for (u <- Seq(x.lo, x.hi); v <- Seq(y.lo, y.hi)) {
            assert(p.lo <= u * v && u * v <= p.hi, s"$p misses corner $u * $v")
          }
        case _ =>
      }
    }
  }

  test("the queries: magnitude, fitsInt, within, abs") {
    assert(r(-3, 5).magnitude().getAsLong === 5)
    assert(r(-7, 5).magnitude().getAsLong === 7)
    assert(sym(9).magnitude().getAsLong === 9)
    assert(r(Int.MinValue, Int.MaxValue).fitsInt())
    assert(!r(Int.MinValue.toLong - 1, 0).fitsInt() && !r(0, Int.MaxValue.toLong + 1).fitsInt())
    assert(r(2, 3).within(1, 4) && !r(2, 5).within(1, 4))
    assert(r(2, 3).abs() === r(2, 3))
    assert(r(-3, -2).abs() === r(2, 3))
    assert(r(-3, 5).abs() === r(0, 5))
    assert(r(-3, 5).neg() === r(-5, 3))
    assert(r(1, 2).sub(r(10, 20)) === r(-19, -8))
    intercept[IllegalArgumentException](VarkaValueRange.of(1, 0))
    intercept[IllegalArgumentException](VarkaValueRange.symmetric(-1))
  }

  // ---------------------------------------------------------------------------------------
  // The transfer functions, one test per row of table 3.4
  // ---------------------------------------------------------------------------------------

  private val c0 = new ColumnRef(0)
  private val c1 = new ColumnRef(1)
  private val slot0 = new LiteralSlot(0)
  private val slot1 = new LiteralSlot(1)

  test("table 3.4: a column is the contract under DAY and unknown under INT") {
    assert(day(c0, GuardPolicy.NONE) === contract)
    assert(day(c0, GuardPolicy.ARMED) === contract)
    assert(int(c0) === UNKNOWN)
  }

  test("table 3.4: a literal is itself under DAY and symmetric under INT") {
    val l = lits(-7, 12)
    assert(day(slot0, GuardPolicy.NONE, l) === r(-7, -7))
    assert(day(slot1, GuardPolicy.ARMED, l) === r(12, 12))
    assert(int(slot0, l) === sym(7))
    assert(int(slot1, l) === sym(12))
    assert(int(new LiteralSlot(0), lits(Int.MinValue)) === sym(1L << 31))
  }

  test("table 3.4: a literal day shift moves the interval by exactly its value, same policy") {
    val l = lits(10)
    assert(day(new AddDays(c0, slot0), GuardPolicy.NONE, l) === contract.shift(10, 10))
    assert(day(new SubDays(c0, slot0), GuardPolicy.NONE, l) === contract.shift(-10, -10))
    // Same policy below: a column offset under a literal shift is still unguarded under NONE.
    assert(day(new AddDays(new AddDays(c0, c1), slot0), GuardPolicy.NONE, l) === UNKNOWN)
    assert(day(new AddDays(new AddDays(c0, c1), slot0), GuardPolicy.ARMED, l) ===
      narrow.shift(10, 10))
    assert(int(new AddDays(c0, slot0), l) === UNKNOWN)
  }

  test("table 3.4: a column day offset is unknown under NONE and the narrow range under ARMED") {
    assert(day(new AddDays(c0, c1), GuardPolicy.NONE) === UNKNOWN)
    assert(day(new SubDays(c0, c1), GuardPolicy.NONE) === UNKNOWN)
    assert(day(new AddDays(c0, c1), GuardPolicy.ARMED) === narrow)
    assert(day(new SubDays(c0, c1), GuardPolicy.ARMED) === narrow)
    // Only over a child the analysis knows: an unknown child stays unknown even when armed.
    assert(day(new AddDays(new IntArith(IntOp.ADD, Overflow.WRAP, c0, c1), c1),
      GuardPolicy.ARMED) === UNKNOWN)
  }

  test("table 3.4: next_day shifts by 1 to 7 and the Thursday by -3 to 3, same policy") {
    assert(day(new NextDay(c0, c1), GuardPolicy.NONE) === contract.shift(1, 7))
    assert(day(new NextDay(new AddDays(c0, c1), c1), GuardPolicy.NONE) === UNKNOWN)
    assert(day(new NextDay(new AddDays(c0, c1), c1), GuardPolicy.ARMED) === narrow.shift(1, 7))
    assert(day(new ThursdayOf(c0), GuardPolicy.NONE) === contract.shift(-3, 3))
    assert(day(new ThursdayOf(new AddDays(c0, c1)), GuardPolicy.NONE) === UNKNOWN)
    assert(int(new NextDay(c0, c1)) === UNKNOWN && int(new ThursdayOf(c0)) === UNKNOWN)
  }

  test("table 3.4: add_months shifts by 28m..31m for a literal, the guard's extremes for a " +
      "column, and re-arms its child") {
    val l = lits(3, -2)
    assert(day(new AddMonths(c0, slot0), GuardPolicy.NONE, l) === contract.shift(84, 93))
    assert(day(new AddMonths(c0, slot1), GuardPolicy.NONE, l) === contract.shift(-62, -56))
    val guard = (31L * VarkaChrono.MONTH_ARITH_MIN_MONTHS, 31L * VarkaChrono.MONTH_ARITH_MAX_MONTHS)
    assert(day(new AddMonths(c0, c1), GuardPolicy.NONE) === contract.shift(guard._1, guard._2))
    // The child is asked under ARMED whatever the caller's policy: the producer is bounded here.
    assert(day(new AddMonths(new AddDays(c0, c1), slot0), GuardPolicy.NONE, l) ===
      narrow.shift(84, 93))
    assert(int(new AddMonths(c0, slot0), l) === UNKNOWN)
  }

  test("table 3.4: last_day and trunc shift by 0..30 and -365..0 and re-arm their child") {
    assert(day(new LastDay(c0), GuardPolicy.NONE) === contract.shift(0, 30))
    assert(day(new LastDay(new AddDays(c0, c1)), GuardPolicy.NONE) === narrow.shift(0, 30))
    assert(day(new TruncDate(c0, TruncLevel.YEAR), GuardPolicy.NONE) === contract.shift(-365, 0))
    assert(day(new TruncDate(new AddDays(c0, c1), TruncLevel.MONTH), GuardPolicy.NONE) ===
      narrow.shift(-365, 0))
    assert(day(new TruncDateDynamic(c0, c1), GuardPolicy.NONE) === contract.shift(-365, 0))
    assert(day(new TruncDateDynamic(new AddDays(c0, c1), c1), GuardPolicy.NONE) ===
      narrow.shift(-365, 0))
    assert(int(new LastDay(c0)) === UNKNOWN && int(new TruncDate(c0, TruncLevel.YEAR)) === UNKNOWN)
  }

  test("table 3.4: make_date is the whole years of the narrow range, children unqueried") {
    val md = new MakeDate(c0, c1, new IntArith(IntOp.MUL, Overflow.WRAP, c0, c1), false)
    val expected = r(
      LocalDate.of(VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1).toEpochDay,
      LocalDate.of(VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31).toEpochDay)
    assert(day(md, GuardPolicy.NONE) === expected)
    assert(day(md, GuardPolicy.ARMED) === expected)
    assert(expected.within(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS))
    assert(int(md) === UNKNOWN)
  }

  test("table 3.4: a GuardedDay is the narrow range whatever its child") {
    assert(day(new GuardedDay(new IntArith(IntOp.MUL, Overflow.WRAP, c0, c1)),
      GuardPolicy.NONE) === narrow)
    assert(day(new GuardedDay(c0), GuardPolicy.ARMED) === narrow)
    assert(int(new GuardedDay(c0)) === UNKNOWN)
  }

  test("table 3.4: greatest, least and if are hulls under DAY and unknown under INT") {
    val l = lits(5)
    val shifted = new AddDays(c0, slot0)
    val cond = new Compare(CompareOp.LT, c0, c1)
    for (node <- Seq(new Greatest(c0, shifted), new Least(shifted, c0), new IfElse(cond, c0,
        shifted))) {
      assert(day(node, GuardPolicy.NONE, l) === contract.hull(contract.shift(5, 5)))
      assert(int(node, l) === UNKNOWN)
    }
    // Unknown if either branch is.
    assert(day(new Greatest(c0, new AddDays(c0, c1)), GuardPolicy.NONE) === UNKNOWN)
    assert(day(new Greatest(c0, new AddDays(c0, c1)), GuardPolicy.ARMED) === contract.hull(narrow))
  }

  test("table 3.4: datediff is the widest difference of its operands' unguarded days") {
    val l = lits(10)
    val (lo, hi) = (VarkaChrono.CONTRACT_MIN_DAYS.toLong, VarkaChrono.CONTRACT_MAX_DAYS.toLong)
    assert(int(new DateDiff(c0, c1)) === sym(hi - lo))
    assert(int(new DateDiff(new AddDays(c0, slot0), c1), l) === sym(hi + 10 - lo))
    // Unguarded: a column offset under datediff is not bounded, and neither is the difference.
    assert(int(new DateDiff(new AddDays(c0, c1), c1)) === UNKNOWN)
    // A calendar node inside an operand re-arms, so the same producer under last_day is bounded.
    assert(int(new DateDiff(new LastDay(new AddDays(c0, c1)), c1)) ===
      sym(VarkaChrono.NARROW_MAX_DAYS.toLong + 30 - lo))
    // An operand interval that leaves int32 is refused: the lane that produced it wrapped.
    assert(int(new DateDiff(new AddDays(c0, slot0), c1), lits(Int.MaxValue)) === UNKNOWN)
    assert(day(new DateDiff(c0, c1), GuardPolicy.ARMED) === UNKNOWN)
  }

  test("table 3.4: the calendar fields are their constants, children unqueried") {
    val expected = Seq[(VarkaVectorIR, Long)](
      new Year(c0) -> VarkaChrono.YEAR_FIELD_MAGNITUDE, new Month(c0) -> 12,
      new DayOfMonth(c0) -> 31, new Quarter(c0) -> 4, new DayOfYear(c0) -> 366,
      new WeekOfYear(c0) -> 53, new DayOfWeek(c0) -> 7, new DayOfWeekIso(c0) -> 7,
      new WeekDay(c0) -> 6)
    for ((node, m) <- expected) {
      assert(int(node) === sym(m), node)
      assert(day(node, GuardPolicy.ARMED) === UNKNOWN, node)
    }
    // Over an unknown child the constants still hold: the compiler admitted the child first.
    assert(int(new Year(new IntArith(IntOp.MUL, Overflow.WRAP, c0, c1))) ===
      sym(VarkaChrono.YEAR_FIELD_MAGNITUDE))
  }

  test("the year constant covers the whole admitted day range") {
    // The admitted range reaches NARROW_DECOMPOSE_MAX_DAYS upward and NARROW_MIN_DAYS downward;
    // the year bound has to cover both ends, and the top end is the wider one.
    val top = LocalDate.ofEpochDay(VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS).getYear
    val bottom = LocalDate.ofEpochDay(VarkaChrono.NARROW_MIN_DAYS).getYear
    assert(VarkaChrono.YEAR_FIELD_MAGNITUDE === math.max(math.abs(bottom), top))
    assert(top > 40000, s"the pre-task-84 constant of 40000 would have been enough only if the " +
      s"admitted range stopped short of year $top")
  }

  test("table 3.4: int arithmetic is the sum or product of magnitudes, symmetric, saturating") {
    val l = lits(-3, 7)
    val year = new Year(c0)
    val ym = VarkaChrono.YEAR_FIELD_MAGNITUDE.toLong
    assert(int(new IntArith(IntOp.ADD, Overflow.WRAP, slot0, slot1), l) === sym(10))
    assert(int(new IntArith(IntOp.SUB, Overflow.WRAP, slot0, slot1), l) === sym(10))
    assert(int(new IntArith(IntOp.MUL, Overflow.WRAP, slot0, slot1), l) === sym(21))
    assert(int(new IntArith(IntOp.MUL, Overflow.FAIL, year, slot1), l) === sym(7 * ym))
    assert(int(new IntArith(IntOp.ADD, Overflow.WRAP, year, c0)) === UNKNOWN)
    assert(int(new IntNeg(Overflow.FAIL, slot0), l) === sym(3))
    assert(int(new IntNeg(Overflow.FAIL, c0)) === UNKNOWN)
    // Nested products past 2^63 come back unknown, not small and positive.
    val huge = lits(Int.MaxValue)
    val big = new LiteralSlot(0)
    var product: VarkaVectorIR = big
    for (_ <- 0 until 3) product = new IntArith(IntOp.MUL, Overflow.WRAP, product, big)
    assert(int(product, huge) === UNKNOWN)
    assert(day(new IntArith(IntOp.ADD, Overflow.WRAP, slot0, slot1), GuardPolicy.ARMED, l) ===
      UNKNOWN)
  }

  test("table 3.4: a condition has no value") {
    val cond = new Compare(CompareOp.LT, c0, c1)
    for (node <- Seq[VarkaVectorIR](cond, new And(cond, cond), new Or(cond, cond), new Not(cond),
        new IsNotNull(c0))) {
      assert(day(node, GuardPolicy.ARMED) === UNKNOWN)
      assert(int(node) === UNKNOWN)
    }
  }

  // ---------------------------------------------------------------------------------------
  // The property test
  // ---------------------------------------------------------------------------------------

  /** How a column's lanes are drawn, from the slots it sits in; the first listed wins. */
  private object Role extends Enumeration {
    val Level, Months, Day, Offset, Int = Value
  }

  /** Every (node, kind) pair the analysis is asked about, and the role of every column. */
  private def walk(root: VarkaVectorIR): (Seq[(VarkaVectorIR, Kind)], Map[Int, Role.Value]) = {
    val asked = mutable.ArrayBuffer.empty[(VarkaVectorIR, Kind)]
    val roles = mutable.Map.empty[Int, Role.Value]
    def role(ordinal: Int, r: Role.Value): Unit =
      roles.update(ordinal, roles.get(ordinal).map(old => if (r < old) r else old).getOrElse(r))
    def leafRole(n: VarkaVectorIR, r: Role.Value): Unit = n match {
      case c: ColumnRef => role(c.ordinal(), r)
      case _ =>
    }
    def value(n: VarkaVectorIR, kind: Kind): Unit = {
      asked += ((n, kind))
      n match {
        case c: ColumnRef => role(c.ordinal(), if (kind == Kind.DAY) Role.Day else Role.Int)
        case _: LiteralSlot =>
        case x: AddDays => value(x.days(), Kind.DAY); leafRole(x.offset(), Role.Offset)
        case x: SubDays => value(x.days(), Kind.DAY); leafRole(x.offset(), Role.Offset)
        case x: NextDay => value(x.days(), Kind.DAY); leafRole(x.offset(), Role.Offset)
        case x: ThursdayOf => value(x.days(), Kind.DAY)
        case x: AddMonths => value(x.days(), Kind.DAY); leafRole(x.months(), Role.Months)
        case x: LastDay => value(x.days(), Kind.DAY)
        case x: TruncDate => value(x.days(), Kind.DAY)
        case x: TruncDateDynamic => value(x.days(), Kind.DAY); leafRole(x.level(), Role.Level)
        case x: MakeDate =>
          value(x.year(), Kind.INT); value(x.month(), Kind.INT); value(x.day(), Kind.INT)
        case x: GuardedDay => value(x.days(), Kind.DAY)
        case x: GuardedRange => value(x.child(), kind)
        case x: NarrowLane => value(x.child(), kind)
        case x: Greatest => value(x.left(), kind); value(x.right(), kind)
        case x: Least => value(x.left(), kind); value(x.right(), kind)
        case x: IfElse => cond(x.cond()); value(x.thenNode(), kind); value(x.elseNode(), kind)
        case x: DateDiff => value(x.end(), Kind.DAY); value(x.start(), Kind.DAY)
        case x: Year => value(x.days(), Kind.DAY)
        case x: Month => value(x.days(), Kind.DAY)
        case x: DayOfMonth => value(x.days(), Kind.DAY)
        case x: Quarter => value(x.days(), Kind.DAY)
        case x: DayOfYear => value(x.days(), Kind.DAY)
        case x: WeekOfYear => value(x.days(), Kind.DAY)
        case x: DayOfWeek => value(x.days(), Kind.DAY)
        case x: DayOfWeekIso => value(x.days(), Kind.DAY)
        case x: WeekDay => value(x.days(), Kind.DAY)
        case x: IntArith => value(x.left(), Kind.INT); value(x.right(), Kind.INT)
        case x: IntNeg => value(x.child(), Kind.INT)
        case x: ConstDivide => value(x.child(), Kind.INT)
        case x: BoundedDivide => value(x.child(), Kind.INT)
        case c: Cond => cond(c)
      }
    }
    def cond(c: Cond): Unit = c match {
      case x: Compare => value(x.left(), Kind.DAY); value(x.right(), Kind.DAY)
      case x: And => cond(x.left()); cond(x.right())
      case x: Or => cond(x.left()); cond(x.right())
      case x: Not => cond(x.child())
      case x: IsNotNull => value(x.child(), Kind.DAY)
      case x: InRanges => value(x.child(), Kind.DAY)
    }
    val rootKind = root match {
      case _: DateDiff | _: Year | _: Month | _: DayOfMonth | _: Quarter | _: DayOfYear |
          _: WeekOfYear | _: DayOfWeek | _: DayOfWeekIso | _: WeekDay | _: IntArith |
          _: IntNeg | _: ConstDivide | _: BoundedDivide => Kind.INT
      case _ => Kind.DAY
    }
    value(root, rootKind)
    (asked.toSeq, roles.toMap)
  }

  private def isCalendarConsumer(n: VarkaVectorIR): Boolean =
    n.isInstanceOf[Chrono] || n.isInstanceOf[AddMonths]

  /**
   * Whether the row is one the kernel would answer for this subtree, which is the condition
   * under which the analysis's intervals are promised: every calendar consumer sees a day the
   * lowering is admitted for, every guarded producer under an armed consumer (or under the
   * caller's own ARMED policy) is inside the narrow range, every GuardedDay's child is, a column
   * month count is inside the emitter's guard, and make_date's year is inside its own. A row
   * that fails is one the kernel declines, and the reference has no spelling for a decline.
   */
  private def admissible(node: VarkaVectorIR, armed: Boolean, row: Seq[Option[Int]],
      literals: Array[Int]): Boolean = {
    def v(n: VarkaVectorIR): Option[Int] = VarkaReferenceEvaluator.evalValue(n, row, literals)
    def within(x: Option[Int], lo: Long, hi: Long): Boolean = x.forall(i => i >= lo && i <= hi)
    def go(n: VarkaVectorIR, armed: Boolean): Boolean = n match {
      case x: AddDays =>
        (!armed || x.offset().isInstanceOf[LiteralSlot] ||
          within(v(x), VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS)) &&
          go(x.days(), armed)
      case x: SubDays =>
        (!armed || x.offset().isInstanceOf[LiteralSlot] ||
          within(v(x), VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS)) &&
          go(x.days(), armed)
      case x: GuardedDay =>
        within(v(x.days()), VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS) &&
          go(x.days(), armed)
      case x: GuardedRange =>
        within(v(x.child()), x.lo(), x.hi()) && go(x.child(), armed)
      // The narrowing changes no value and admits whatever its child admits.
      case x: NarrowLane => go(x.child(), armed)
      case x: AddMonths =>
        within(v(x.days()), VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS) &&
          (x.months().isInstanceOf[LiteralSlot] || within(v(x.months()),
            VarkaChrono.MONTH_ARITH_MIN_MONTHS, VarkaChrono.MONTH_ARITH_MAX_MONTHS)) &&
          go(x.days(), armed = true)
      case x: Chrono =>
        val child = x match {
          case y: Year => y.days()
          case y: Month => y.days()
          case y: DayOfMonth => y.days()
          case y: Quarter => y.days()
          case y: DayOfYear => y.days()
          case y: WeekOfYear => y.days()
          case y: LastDay => y.days()
          case y: TruncDate => y.days()
          case y: TruncDateDynamic => y.days()
        }
        within(v(child), VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS) &&
          go(child, armed = true)
      case x: MakeDate =>
        within(v(x.year()), VarkaChrono.MAKE_DATE_MIN_YEAR, VarkaChrono.MAKE_DATE_MAX_YEAR) &&
          go(x.year(), armed) && go(x.month(), armed) && go(x.day(), armed)
      case x: DateDiff => go(x.end(), armed = false) && go(x.start(), armed = false)
      case x: NextDay => go(x.days(), armed)
      case x: ThursdayOf => go(x.days(), armed)
      case x: Greatest => go(x.left(), armed) && go(x.right(), armed)
      case x: Least => go(x.left(), armed) && go(x.right(), armed)
      case x: IfElse =>
        goCond(x.cond(), armed) && go(x.thenNode(), armed) && go(x.elseNode(), armed)
      case x: DayOfWeek => go(x.days(), armed)
      case x: DayOfWeekIso => go(x.days(), armed)
      case x: WeekDay => go(x.days(), armed)
      case x: IntArith => go(x.left(), armed) && go(x.right(), armed)
      case x: IntNeg => go(x.child(), armed)
      case x: ConstDivide => go(x.child(), armed)
      case x: BoundedDivide => go(x.child(), armed)
      case _: ColumnRef | _: LiteralSlot => true
      case c: Cond => goCond(c, armed)
    }
    def goCond(c: Cond, armed: Boolean): Boolean = c match {
      case x: Compare => go(x.left(), armed) && go(x.right(), armed)
      case x: And => goCond(x.left(), armed) && goCond(x.right(), armed)
      case x: Or => goCond(x.left(), armed) && goCond(x.right(), armed)
      case x: Not => goCond(x.child(), armed)
      case x: IsNotNull => go(x.child(), armed)
      case x: InRanges => go(x.child(), armed)
    }
    go(node, armed)
  }

  test(s"the interval a node reports contains what the reference computes " +
      s"(seed $seed, $trees trees)") {
    val rnd = new Random(seed)
    var checked = 0L
    var vacuous = 0L
    for (t <- 0 until trees) {
      val numInputs = 1 + rnd.nextInt(3)
      val numLiterals = rnd.nextInt(3)
      val smallOrdinal = if (numInputs > 1) numInputs - 1 else -1
      val levelOrdinal = if (numInputs > 2) numInputs - 2 else -1
      val shapes = new Shapes(rnd, numInputs, numLiterals, smallOrdinal, levelOrdinal)
      val root = shapes.value(1 + rnd.nextInt(4)).node
      // Distinct literal values: the compiler's table is keyed by value, and the legacy oracle
      // reads it the way the compiler does.
      val literals = {
        val drawn = mutable.LinkedHashSet.empty[Int]
        while (drawn.size < numLiterals) drawn += rnd.nextInt(2 * literalBound + 1) - literalBound
        drawn.toArray
      }
      val (asked, roles) = walk(root)
      def draw(ordinal: Int): Int = roles.getOrElse(ordinal, Role.Int) match {
        case Role.Level => DateTimeUtils.TRUNC_TO_WEEK +
          rnd.nextInt(DateTimeUtils.TRUNC_TO_YEAR - DateTimeUtils.TRUNC_TO_WEEK + 1)
        case Role.Months => VarkaChrono.MONTH_ARITH_MIN_MONTHS + rnd.nextInt(
          VarkaChrono.MONTH_ARITH_MAX_MONTHS - VarkaChrono.MONTH_ARITH_MIN_MONTHS + 1)
        case Role.Day => VarkaChrono.CONTRACT_MIN_DAYS + rnd.nextInt(
          VarkaChrono.CONTRACT_MAX_DAYS - VarkaChrono.CONTRACT_MIN_DAYS + 1)
        case Role.Offset => (rnd.nextLong() % (2 * columnBound + 1) - columnBound).toInt
        case Role.Int => rnd.nextInt()
      }
      val rows = Seq.fill(8)((0 until numInputs).map(c =>
        if (rnd.nextInt(8) == 0) None else Some(draw(c))))
      for ((node, kind) <- asked; policy <- Seq(GuardPolicy.NONE, GuardPolicy.ARMED)) {
        val range = analysis(node, kind, policy, i => literals(i))
        if (range == UNKNOWN) {
          vacuous += 1
        } else {
          for (row <- rows if admissible(node, policy == GuardPolicy.ARMED, row, literals)) {
            VarkaReferenceEvaluator.evalValue(node, row, literals).foreach { value =>
              checked += 1
              val b = range.asInstanceOf[Bounded]
              assert(b.lo <= value && value <= b.hi,
                s"seed=$seed tree=$t root=${VarkaVectorIR.canonical(root)} " +
                  s"node=${VarkaVectorIR.canonical(node)} kind=$kind policy=$policy " +
                  s"row=$row literals=${literals.mkString(",")}: range $range excludes $value")
            }
          }
        }
      }
    }
    logInfo(s"range property: $checked values checked against a bounded interval, " +
      s"$vacuous queries answered unknown")
    assert(checked > 0)
  }
}
