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

import scala.util.Random

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The random IR grammar shared by [[VarkaIrFuzzSuite]], which emits and runs the trees it
 * draws, and [[VarkaRangeAnalysisSuite]], which asks the range analysis about them. One
 * generator rather than two, on the `VarkaSqlResolve` precedent: two grammars would drift, and
 * the property the range suite checks is only worth what the shapes it is checked over are.
 *
 * `Shapes` draws value and condition trees over `numInputs` int32 columns and `numLiterals`
 * literal slots (`LongShapes`, at the end of the file, is its twin at the long lane), obeying
 * the emitter's structural rules (a day offset is a literal slot or a column, `IsNotNull` is
 * over a column, a calendar node sits only over a subtree whose magnitude bound fits the
 * narrowed day range with slack). `boundsOf` recomputes two magnitude bounds from a built tree
 * - the node's own value and the largest value any guarded day producer under it reaches -
 * which is what decides whether a calendar node may be placed over it. The bounds saturate
 * rather than wrap in every arm that combines two.
 *
 * Columns hold days within plus or minus `columnBound` (about six thousand years either side of
 * 1970) and literals within plus or minus `literalBound`; `chronoBound` is the magnitude a
 * calendar node's subtree must stay under. The suites draw the actual lane values themselves,
 * inside these bounds, because how a row is drawn is the suite's question (the fuzzer poisons
 * null lanes, the range suite widens int-only columns to the whole of int32).
 */
object VarkaIrGrammar {

  val columnBound = 2500000L
  val literalBound = 4000

  /**
   * The seed both shape corpora are drawn from: `VarkaIrFuzzSuite` runs shape `k` against the
   * reference evaluator, and `VarkaEmittedBytesSuite` pins the bytes shape `k` emits. One seed
   * here rather than one in each, so that the oracle's shapes really are the fuzzer's - it pins
   * many more of them than the fuzzer checks by default, but every shape the fuzzer checks is
   * one the oracle pins, which is what makes "the emitter produces what it produced" a statement
   * about code that was also checked for correctness. The fuzz suite's own system property
   * overrides its seed for a one-off hunt; the oracle's corpus is the committed file's, so it
   * does not follow the override.
   */
  val fuzzSeed = 20260903L

  // The generator's magnitude bounds saturate rather than wrap, in *every* arm that combines
  // two of them - `boundsOf` and the generator alike. Four nested multiplies of a column's own
  // bound pass 2^63, and a bound that came back negative would let `fitsUnderChrono` admit an
  // out-of-range subtree under a calendar node, at which point the kernel declines the batch,
  // correctly, and the suite fails asserting a zero status. Saturating only the arms that can
  // reach 2^63 on their own is not enough: `Long.MaxValue` wraps negative the moment a parent
  // adds anything to it, so a single plain `+` one level up undoes the discipline. A saturated
  // bound is always an over-approximation, which is the safe direction here.
  private def satAdd(a: Long, b: Long): Long =
    try Math.addExact(a, b) catch { case _: ArithmeticException => Long.MaxValue }
  private def satMul(a: Long, b: Long): Long =
    try Math.multiplyExact(a, math.max(1L, b)) catch {
      case _: ArithmeticException => Long.MaxValue
    }
  /** A calendar node may sit over a subtree whose value cannot leave this magnitude. */
  val chronoBound = 5000000L

  /** A generated node with a bound on the magnitude of the value it can take. */
  case class Gen(node: VarkaVectorIR, bound: Long)

  /**
   * Two bounds for a built subtree: the magnitude the node's own value can reach, and the
   * magnitude the *guarded day producers* inside it can reach. The second is not the first,
   * and treating them as one is what let this suite generate shapes that decline correctly
   * and then assert that they do not.
   *
   * `VarkaLoopEmitter`'s `collectGuardedProducers` puts task 52's range guard on every
   * `AddDays`/`SubDays` with a column offset *anywhere* below a calendar node - the walk
   * descends the whole subtree and does not stop at a node that re-bases the day. So
   * `month(dayOfWeek(addDays(addDays(c, c), c)))` guards a producer whose value reaches
   * three times `columnBound`, even though the value `month` actually decomposes is the
   * `dayOfWeek` result and is always 1 to 7. With `columnBound` at 2.5 million that producer
   * reaches 7.5 million, past `NARROW_MIN_DAYS`, and the batch is declined - correctly.
   * A `Gen.bound` of 7 on the `dayOfWeek` hid it from the `chronoBound` check.
   *
   * Six million fuzz iterations on 7 September 2026 found this in twenty jobs out of twenty,
   * and the shortest reproducer is one iteration: seed 20260907005, iteration 61379, whose
   * pattern is `null-free`, so it has nothing to do with poisoned null lanes.
   *
   * Recomputed from the IR rather than threaded through `Shapes`, because threading is what
   * a new arm forgets: every arm would have to carry it, including through `cond`, and one
   * of the twenty failures had its producer inside an `IfElse` condition. The match is
   * exhaustive over what the generator builds and fails loudly on anything else, so adding a
   * node type to the generator without a rule here stops the suite rather than silently
   * shrinking its coverage.
   */
  def boundsOf(node: VarkaVectorIR): (Long, Long) = {
    def v(n: VarkaVectorIR): Long = boundsOf(n)._1
    def g(ns: VarkaVectorIR*): Long = ns.foldLeft(0L)((m, n) => math.max(m, boundsOf(n)._2))
    def shift(days: VarkaVectorIR, offset: VarkaVectorIR): (Long, Long) = {
      val own = satAdd(v(days), v(offset))
      // A literal offset is folded at compile time and never guarded (requireOffsetShape);
      // a column offset is exactly what collectGuardedProducers collects.
      val guarded = if (offset.isInstanceOf[LiteralSlot]) 0L else own
      (own, math.max(g(days, offset), guarded))
    }
    node match {
      case _: ColumnRef => (columnBound, 0L)
      case _: LiteralSlot => (literalBound, 0L)
      case n: AddDays => shift(n.days(), n.offset())
      case n: SubDays => shift(n.days(), n.offset())
      case n: DateDiff => (satAdd(v(n.end()), v(n.start())), g(n.end(), n.start()))
      case n: Greatest => (math.max(v(n.left()), v(n.right())), g(n.left(), n.right()))
      case n: Least => (math.max(v(n.left()), v(n.right())), g(n.left(), n.right()))
      case n: IfElse =>
        (math.max(v(n.thenNode()), v(n.elseNode())), g(n.cond(), n.thenNode(), n.elseNode()))
      // Conditions carry no day value of their own, but their operands hold producers.
      case n: Compare => (0L, g(n.left(), n.right()))
      case n: And => (0L, g(n.left(), n.right()))
      case n: Or => (0L, g(n.left(), n.right()))
      case n: Not => (0L, g(n.child()))
      case n: IsNotNull => (0L, g(n.child()))
      case n: InRanges => (0L, g(n.child()))
      // The mod-7 family: exact for every int32 day, so the value is small whatever it is
      // over - which is precisely why the producers underneath stay visible in the guard bound.
      case n: DayOfWeek => (7L, g(n.days()))
      case n: WeekDay => (6L, g(n.days()))
      case n: DayOfWeekIso => (7L, g(n.days()))
      case n: NextDay => (satAdd(v(n.days()), 8), g(n.days(), n.offset()))
      case n: ThursdayOf => (satAdd(v(n.days()), 3), g(n.days()))
      // Task 93: a range check beside the value, which the value itself does not feel. The
      // bound is the child's, so a subtree that would leave the range still shows as one -
      // the guard reports such a batch rather than making it representable.
      case n: GuardedDay => (v(n.days()), g(n.days()))
      case n: GuardedRange => (v(n.child()), g(n.child()))
      case n: WeekOfYear => (53L, g(n.days()))
      case n: Year => (40000L, g(n.days()))
      case n: Month => (12L, g(n.days()))
      case n: DayOfMonth => (31L, g(n.days()))
      case n: Quarter => (4L, g(n.days()))
      case n: DayOfYear => (366L, g(n.days()))
      case n: AddMonths => (satAdd(v(n.days()), satMul(v(n.months()), 31)), g(n.days(), n.months()))
      // trunc moves a date down by up to a year, so the truncated value's magnitude can exceed
      // the child's by that much: the bound is the child's plus 366, not the child's. The
      // difference is invisible to the calendar placement, which checks with slack, and
      // decisive for the range guard's arm, which takes the bound as a guard the value must
      // not leave.
      case n: TruncDate => (satAdd(v(n.days()), 366), g(n.days()))
      // Task 63: wrapping arithmetic can leave the day range entirely, which is what the
      // bound is for - a calendar node over such a subtree is refused by fitsUnderChrono.
      case n: IntArith => n.op() match {
        case IntOp.MUL => (satMul(v(n.left()), v(n.right())), g(n.left(), n.right()))
        case _ => (satAdd(v(n.left()), v(n.right())), g(n.left(), n.right()))
      }
      case n: IntNeg => (v(n.child()), g(n.child()))
      case n: ConstDivide => (v(n.child()) / math.abs(n.divisor().toLong), g(n.child()))
      case n: BoundedDivide => (((n.bound() - 1) / n.divisor()).toLong, g(n.child()))
      // The dynamic form moves the date down like the literal one, whatever the level; the
      // level column contributes no day magnitude of its own, only whatever guarded producer
      // might sit under it, which `g` picks up.
      case n: TruncDateDynamic => (satAdd(v(n.days()), 366), g(n.days(), n.level()))
      case n: LastDay => (satAdd(v(n.days()), 31), g(n.days()))
      // make_date guards its own year, so its output is a date inside the column contract.
      case n: MakeDate =>
        (VarkaChrono.CONTRACT_MAX_DAYS.toLong, g(n.year(), n.month(), n.day()))
      case other =>
        throw new IllegalStateException(
          s"boundsOf has no rule for ${other.getClass.getSimpleName}: add one, or the " +
            "calendar arms silently stop being placed over subtrees containing it")
    }
  }

  /**
   * One drawn shape: the kernel's roots, the input and literal counts they were drawn over, and
   * the two special columns, which a caller drawing lane values has to honour - the values a
   * column holds are bounded by the arms that may read it.
   */
  case class Drawn(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int,
      smallOrdinal: Int, levelOrdinal: Int)

  /**
   * Draw one shape from `rnd`: the column and literal counts, the special columns, the depth and
   * the roots, in that order, leaving `rnd` positioned where a caller that needs lane values and
   * null patterns picks up.
   *
   * It lives here rather than in either caller because two of them must draw the *same* corpus
   * from the same seed: [[VarkaIrFuzzSuite]] runs each shape against the reference evaluator, and
   * `VarkaEmittedBytesSuite` pins the bytes the same shape emits. A copy of the preamble in each
   * would let one extra `rnd.next*` call in one of them silently split the corpus in two, and the
   * oracle would go on pinning shapes nothing checks for correctness. `fuzzSeed` and
   * `shapeRandom` are here for the same reason.
   */
  /** The generator for shape `k` of the sequence, the same in both suites. */
  def shapeRandom(seed: Long, k: Int): Random = new Random(seed * 1000003L + k)

  def drawShape(rnd: Random): Drawn = {
    val numInputs = 1 + rnd.nextInt(3)
    val numLiterals = rnd.nextInt(3)
    // The last input, when there is more than one, holds month-count-magnitude values so a
    // *column* month count can be fuzzed; see Shapes' doc. With a single input there is no
    // ordinal to spare - that one has to stay a day column for every other arm.
    val smallOrdinal = if (numInputs > 1) numInputs - 1 else -1
    // The second special column, and only when there are three: with two, taking one for
    // trunc levels would leave a single day column and starve every other arm.
    val levelOrdinal = if (numInputs > 2) numInputs - 2 else -1
    val shapes = new Shapes(rnd, numInputs, numLiterals, smallOrdinal, levelOrdinal)
    val depth = 1 + rnd.nextInt(4)
    // Either a projection of value roots or one selection root: the two kinds of kernel
    // production emits, never mixed in one class.
    val roots: Seq[VarkaVectorIR] =
      if (rnd.nextInt(5) == 0) Seq(shapes.cond(depth))
      else Seq.fill(1 + rnd.nextInt(3))(shapes.value(depth).node).distinct
    Drawn(roots, numInputs, numLiterals, smallOrdinal, levelOrdinal)
  }

  /** One iteration's shape generator; keeps a node budget so trees stay well inside the
   *  emitter's `MAX_FUSED_NODES` and `MAX_CHAIN_DEPTH`.
   *
   *  `smallOrdinal` is the input column whose values `runOne` keeps inside
   *  `MONTH_ARITH_MIN/MAX_MONTHS`, or -1 when this iteration has only one input. Every other
   *  column holds day-magnitude values, which as a month count would trip the runtime guard on
   *  every batch and decline it - leaving the status-zero assertions nothing to check. Giving
   *  one ordinal a small range is what lets a *column* month count be fuzzed at all, and it is
   *  the operand shape task 63 will want too. Its `Gen` bound stays `columnBound` wherever the
   *  generic leaf draws it, which over-approximates its real range in the safe direction.
   *
   *  `levelOrdinal` is the same idea for task 61's `trunc` with a format column, or -1 when
   *  this iteration has fewer than three inputs. `TruncLevelLeaf` hands the kernel
   *  `DateTimeUtils.parseTruncLevel`'s codes - 6 (`WEEK`) to 9 (`YEAR`) - or a null lane, and
   *  nothing else, so a level column drawn at day magnitude would be a lane the leaf can never
   *  produce and the node would be fuzzed outside its contract. `runOne` draws this one from
   *  the four codes instead, which is what lets `TruncDateDynamic` be generated at all - it
   *  was the one IR node type this suite could not reach. */
  class Shapes(rnd: Random, numInputs: Int, numLiterals: Int, smallOrdinal: Int,
      levelOrdinal: Int) {
    private var budget = 20

    /**
     * Whether a calendar node may sit over this subtree. Both bounds have to fit inside
     * `chronoBound`: the value the node itself decomposes, and - see `boundsOf` - the value
     * every guarded day producer underneath reaches, because task 52's guard is placed on
     * those on their own values however far below the calendar node they sit.
     */
    private def fitsUnderChrono(a: Gen): Boolean =
      a.bound <= chronoBound && boundsOf(a.node)._2 <= chronoBound

    private def leaf(): Gen =
      if (numLiterals > 0 && rnd.nextInt(4) == 0) {
        Gen(new LiteralSlot(rnd.nextInt(numLiterals)), literalBound)
      } else {
        Gen(new ColumnRef(rnd.nextInt(numInputs)), columnBound)
      }

    private def literal(): Gen =
      if (numLiterals > 0) Gen(new LiteralSlot(rnd.nextInt(numLiterals)), literalBound)
      else Gen(new ColumnRef(rnd.nextInt(numInputs)), columnBound)

    /** See the `case 21` arm: non-zero, not -1, both signs, a power of two among them. */
    private val ConstDivideDivisors = Array(2, 3, 7, 12, 100, -3, -12)

    def value(depth: Int): Gen = {
      if (depth == 0 || budget <= 1) return leaf()
      budget -= 1
      rnd.nextInt(24) match {
        case 0 =>
          val a = value(depth - 1); val b = literal()
          Gen(new AddDays(a.node, b.node), satAdd(a.bound, b.bound))
        case 1 =>
          val a = value(depth - 1); val b = literal()
          Gen(new SubDays(a.node, b.node), satAdd(a.bound, b.bound))
        case 2 =>
          val a = value(depth - 1); val b = value(depth - 1)
          Gen(new DateDiff(a.node, b.node), satAdd(a.bound, b.bound))
        case 3 =>
          val a = value(depth - 1); val b = value(depth - 1)
          Gen(new Greatest(a.node, b.node), math.max(a.bound, b.bound))
        case 4 =>
          val a = value(depth - 1); val b = value(depth - 1)
          Gen(new Least(a.node, b.node), math.max(a.bound, b.bound))
        case 5 =>
          val c = cond(depth - 1); val a = value(depth - 1); val b = value(depth - 1)
          Gen(new IfElse(c, a.node, b.node), math.max(a.bound, b.bound))
        case 6 =>
          val a = value(depth - 1)
          Gen(new DayOfWeek(a.node), 7)
        case 7 =>
          val a = value(depth - 1)
          Gen(new WeekDay(a.node), 6)
        case 8 =>
          // next_day's weekday as a literal slot (task 33) or, on the other draw, a column
          // (task 59's derived weekday leaf), whose values the reference reads like any int:
          // the lowering is exact for every k, so a date column serves as the weekday column.
          val a = value(depth - 1)
          val k = if (rnd.nextBoolean()) literal()
            else Gen(new ColumnRef(rnd.nextInt(numInputs)), columnBound)
          Gen(new NextDay(a.node, k.node), satAdd(a.bound, 8))
        case 14 =>
          // make_date (task 42) over a date's own fields: always a valid triple in range, so
          // both modes run to status 0 and the answer is the date itself; every third one
          // takes a literal day instead under the NULL form, where an invalid day is a null
          // output and the batch still runs.
          val a = value(depth - 1)
          if (!fitsUnderChrono(a)) return a
          if (numLiterals > 0 && rnd.nextInt(3) == 0) {
            val k = literal()
            Gen(new MakeDate(new Year(a.node), new Month(a.node), k.node, false),
              satAdd(a.bound, 31))
          } else {
            Gen(new MakeDate(new Year(a.node), new Month(a.node), new DayOfMonth(a.node),
              rnd.nextBoolean()), a.bound)
          }
        case 15 =>
          // The Thursday of the day's week (task 37): a day-typed producer within three days.
          val a = value(depth - 1)
          Gen(new ThursdayOf(a.node), satAdd(a.bound, 3))
        case 16 =>
          // weekofyear as the compiler builds it, the pair as a unit: the week tail is defined
          // over ThursdayOf only (the emitter refuses any other child), and the subtree has to
          // stay inside the narrowed range like every calendar node's.
          val a = value(depth - 1)
          if (!fitsUnderChrono(a)) return a
          Gen(new WeekOfYear(new ThursdayOf(a.node)), 53)
        case 17 =>
          val a = value(depth - 1)
          Gen(new DayOfWeekIso(a.node), 7)
        case 18 =>
          // Task 63's int arithmetic. A checked mode is drawn only where the operands' own
          // bounds rule overflow out, which is what keeps the value comparison meaningful: the
          // kernel has to answer, not decline, and the suite asserts a zero status. Drawing
          // FAIL over unbounded operands would decline most batches and check nothing - but
          // drawing WRAP only, as this arm did until task 63's review, leaves every checked
          // emission path outside the differential oracle: the guard accumulator, the FAIL
          // word's liveness, the TRY narrowing, and the slot numbering that the scratch
          // temporaries shift.
          val a = value(depth - 1)
          val b = literal()
          val sumBound = satAdd(a.bound, b.bound)
          // `bound` is a magnitude, so the sum is safe exactly when the bounds' sum is.
          def mode(safe: Boolean): Overflow =
            if (!safe) Overflow.WRAP
            else rnd.nextInt(3) match {
              case 0 => Overflow.WRAP
              case 1 => Overflow.FAIL
              case _ => Overflow.NULL
            }
          rnd.nextInt(3) match {
            case 0 => Gen(new IntArith(IntOp.ADD, mode(sumBound <= Int.MaxValue.toLong),
              a.node, b.node), sumBound)
            case 1 => Gen(new IntArith(IntOp.SUB, mode(sumBound <= Int.MaxValue.toLong),
              a.node, b.node), sumBound)
            // A checked multiply has no int-lane overflow test at all, so the compiler
            // declines one and the emitter refuses one: WRAP is the only mode that exists.
            case _ => Gen(new IntArith(IntOp.MUL, Overflow.WRAP, a.node, b.node),
              satMul(a.bound, b.bound))
          }
        case 19 =>
          val a = value(depth - 1)
          // Negation overflows on exactly one value, so any bound inside the int range rules
          // it out and FAIL is safe to draw. NULL has no spelling in Spark and the emitter
          // refuses it, so it is not drawn here.
          val checked = a.bound <= Int.MaxValue.toLong && rnd.nextBoolean()
          Gen(new IntNeg(if (checked) Overflow.FAIL else Overflow.WRAP, a.node), a.bound)
        case 21 =>
          // Task 89's constant division. The divisors are drawn from a fixed set rather than at
          // random: zero has no quotient and -1 overflows at Integer.MinValue, both of which the
          // node and the emitter refuse, so drawing one would make the fuzzer assert its own
          // refusal instead of the arithmetic. Both signs appear, because the lowering truncates
          // toward zero and a floor would differ only on a negative dividend with a remainder.
          val a = value(depth - 1)
          val d = ConstDivideDivisors(rnd.nextInt(ConstDivideDivisors.length))
          Gen(new ConstDivide(a.node, d), a.bound / math.abs(d.toLong))
        case 23 =>
          // Group C's bounded division (PLAN_TASK_102.md 8.4): exact only for a non-negative
          // dividend under its bound. The grammar tracks a subtree's magnitude and not its
          // sign, so the dividend is a calendar field, non-negative and bounded by what it is:
          // the day of the year under 367, the month under 13, the day of the month under 32.
          // A guard that fired would be a decline the reference cannot spell, as the arms
          // above say, so nothing is guarded here and nothing can fire.
          val a = value(depth - 1)
          if (!fitsUnderChrono(a)) return a
          rnd.nextInt(3) match {
            case 0 => Gen(BoundedDivide.of(new DayOfYear(a.node), 7, 367), 53)
            case 1 => Gen(BoundedDivide.of(new Month(a.node), 4, 13), 4)
            case _ => Gen(BoundedDivide.of(new DayOfMonth(a.node), 7, 32), 5)
          }
        case 20 =>
          // Task 93's range check, over a subtree that stays inside the narrowed range - the
          // same condition the calendar family below draws under, and for the same reason. A
          // guard over a subtree that leaves the range does exactly what it is for: it reports
          // the batch, the kernel declines, and the reference evaluator has no spelling for
          // that, so this test would read a correct decline as a mismatch. The guard's firing
          // is asserted where a declined batch is the expected answer, in the emitter suite.
          val a = value(depth - 1)
          if (!fitsUnderChrono(a)) return a
          Gen(new GuardedDay(a.node), a.bound)
        case 22 =>
          // Task 102's range guard, with bounds that contain the child's own bound, for the
          // reason the day guard's arm gives: a guard that fires declines the batch, and the
          // reference evaluator has no spelling for that. Its firing is asserted in the
          // emitter suite. The int lane's analysis refuses bounds an int cannot hold, so a
          // bound the grammar's saturating arithmetic has pushed past int is replaced by the
          // whole int range, which contains every value the lane can produce.
          val a = value(depth - 1)
          val bound = if (a.bound > Int.MaxValue.toLong) Int.MaxValue.toLong else a.bound
          val lo = if (a.bound > Int.MaxValue.toLong) Int.MinValue.toLong else -bound
          Gen(new GuardedRange(a.node, lo, bound), a.bound)
        case n =>
          // The calendar family, over a subtree that stays inside the narrowed range.
          val a = value(depth - 1)
          if (!fitsUnderChrono(a)) return a
          n match {
            case 9 => Gen(new Year(a.node), 40000)
            case 10 => Gen(new Month(a.node), 12)
            case 11 => Gen(new DayOfMonth(a.node), 31)
            case 12 => Gen(new Quarter(a.node), 4)
            case _ => rnd.nextInt(4) match {
              case 0 => Gen(new DayOfYear(a.node), 366)
              case 2 if numLiterals > 0 || smallOrdinal >= 0 =>
                // The count is a literal slot (task 40) or, when this iteration has a
                // small-magnitude column, that column (task 60). It cannot be any other column:
                // the rest hold day-magnitude values, vastly past MONTH_ARITH_MIN/MAX_MONTHS, so
                // the runtime guard would decline every batch and the status-zero assertions
                // below would have nothing left to check.
                val m = if (smallOrdinal >= 0 && (numLiterals == 0 || rnd.nextBoolean())) {
                  Gen(new ColumnRef(smallOrdinal), VarkaChrono.MONTH_ARITH_MAX_MONTHS.toLong)
                } else {
                  literal()
                }
                Gen(new AddMonths(a.node, m.node), satAdd(a.bound, satMul(m.bound, 31)))
              case 3 =>
                // trunc (task 35) moves a date down by at most a year, which the bound has
                // to carry (see `boundsOf`): a range guard drawn over this node takes the
                // bound as the range the value must stay in, and a year-start below the
                // child's own bound is a live lane the guard would condemn. The level is drawn
                // at random so all three tails are fuzzed; the column-level form (task 61,
                // TruncDateDynamic) is drawn only when this iteration has a level column,
                // whose lanes hold the leaf's codes 6..9.
                if (levelOrdinal >= 0 && rnd.nextBoolean()) {
                  Gen(new TruncDateDynamic(a.node, new ColumnRef(levelOrdinal)),
                    satAdd(a.bound, 366))
                } else {
                  val levels = TruncLevel.values()
                  Gen(new TruncDate(a.node, levels(rnd.nextInt(levels.length))),
                    satAdd(a.bound, 366))
                }
              case _ => Gen(new LastDay(a.node), satAdd(a.bound, 31))
            }
          }
      }
    }

    /**
     * A range set over a drawn value: one to six sorted, disjoint, non-adjacent ranges placed
     * across the value's own bound, so that lanes land inside ranges, between them and outside
     * all of them.
     */
    def rangeSet(depth: Int): InRanges = {
      val v = value(depth)
      val span = math.max(1L, math.min(v.bound, Int.MaxValue / 4L))
      val bounds = scala.collection.mutable.ArrayBuffer.empty[Int]
      def draw(limit: Long): Long = (rnd.nextLong() & Long.MaxValue) % (limit + 1)
      var at = -span + draw(span)
      for (_ <- 0 until 1 + rnd.nextInt(6) if at <= span) {
        val lo = at
        val hi = math.min(span, lo + draw(span / 4))
        bounds += lo.toInt
        bounds += hi.toInt
        at = hi + 2 + draw(span / 4)
      }
      new InRanges(v.node, scala.jdk.CollectionConverters.SeqHasAsJava(
        bounds.map(Int.box).toSeq).asJava)
    }

    def cond(depth: Int): Cond = {
      budget -= 1
      if (depth == 0 || budget <= 1 || rnd.nextInt(3) == 0) {
        val ops = CompareOp.values()
        new Compare(ops(rnd.nextInt(ops.length)), value(depth).node, value(depth).node)
      } else {
        rnd.nextInt(5) match {
          case 0 => new And(cond(depth - 1), cond(depth - 1))
          case 1 => new Or(cond(depth - 1), cond(depth - 1))
          case 2 => new Not(cond(depth - 1))
          case 3 => rangeSet(depth)
          // IsNotNull reads a column's validity word directly, so its child must be a column.
          case _ => new IsNotNull(new ColumnRef(rnd.nextInt(numInputs)))
        }
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // The long lane
  // -----------------------------------------------------------------------------------------

  /**
   * The seed of the long-lane corpus. It is a second sequence rather than long shapes drawn
   * into `fuzzSeed`'s, because that sequence is also the emitted-bytes oracle's committed
   * corpus: a long shape drawn into it would move every block digest in `emitted_bytes.json`,
   * and the oracle would stop being able to say whether the int32 emitter changed.
   */
  val longFuzzSeed = 20260919L

  /**
   * Columns at the long lane hold values within plus or minus `longColumnBound`, the magnitude
   * of a nanosecond of day (2^46 is a little over 8.6e13), and literals within plus or minus
   * `longLiteralBound`. The bounds exist for one node: `ConstDivide` is exact only while its
   * dividend stays under `ConstDivide.EXACT_DIVIDEND_BOUND`, a precondition the caller owns
   * and nothing enforces, so the generator places a division only over a subtree whose bound
   * fits under it - the long lane's `fitsUnderChrono`. A sum of two columns fits; a product of
   * two does not, and stays undivided.
   */
  val longColumnBound: Long = {
    // A campaign can raise it to the divisions' own bound, `-Dvarka.fuzz.longColumnBound` at
    // 2^52 - 1, so random trees divide in the six bits between a day of nanoseconds and where the
    // lowerings stop being exact (milestone 5 row 166). The default keeps the committed fuzz
    // blocks what they are: the bound reaches the trees the grammar draws, so changing it
    // changes `emitted_bytes.json`'s `fuzz_long` blocks, and a regeneration must say so.
    sys.props.get("varka.fuzz.longColumnBound").map(_.toLong).getOrElse(1L << 46)
  }
  val longLiteralBound = 1L << 20

  /** One drawn long-lane shape: the roots and the column and literal counts they read. */
  case class DrawnLong(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int)

  /** `drawShape`'s twin at the long lane, shared by the fuzzer and the emitted-bytes oracle. */
  def drawLongShape(rnd: Random): DrawnLong = {
    val numInputs = 1 + rnd.nextInt(3)
    val numLiterals = rnd.nextInt(3)
    val shapes = new LongShapes(rnd, numInputs, numLiterals)
    val depth = 1 + rnd.nextInt(4)
    val roots: Seq[VarkaVectorIR] =
      if (rnd.nextInt(5) == 0) Seq(shapes.cond(depth))
      else Seq.fill(1 + rnd.nextInt(3))(shapes.value(depth).node).distinct
    DrawnLong(roots, numInputs, numLiterals)
  }

  /**
   * The generator at the long lane, over the lane-generic subset of the IR: the leaves, the
   * arithmetic in its three modes, the negate, the constant division in both its lowerings
   * (which one emits is `useAVX`'s choice, which the suite draws), the range guard, the hull
   * ops, the conditional and the conditions. No calendar node, since those refuse a 64-bit
   * child where they are built. The `TIME` and interval expressions the compiler lowers are
   * trees of exactly these nodes - a subtraction under a division, a guarded add over a
   * guarded multiply - so a grammar over the node set covers them without knowing their names,
   * and the fuzzer's reach test holds it to the whole set.
   *
   * Every subtree carries a magnitude bound, saturating as `Shapes`' do. It decides three
   * things: whether a division may be placed (the dividend bound), whether a checked mode may
   * be drawn (an overflow the bounds rule out is one the kernel need not decline on, so the
   * suite can assert a zero status), and what range a guard is given (one that contains the
   * child's bound, because a guard that fires declines the batch and the reference evaluator
   * has no spelling for that; the guards' firing is asserted in the emitter suite).
   */
  class LongShapes(rnd: Random, numInputs: Int, numLiterals: Int) {
    private var budget = 20

    private def saturated(bound: Long): Boolean = bound == Long.MaxValue

    private def column(): Gen =
      Gen(new ColumnRef(rnd.nextInt(numInputs), LaneType.LONG), longColumnBound)

    private def leaf(): Gen =
      if (numLiterals > 0 && rnd.nextInt(4) == 0) {
        Gen(new LiteralSlot(rnd.nextInt(numLiterals), LaneType.LONG), longLiteralBound)
      } else {
        column()
      }

    /**
     * The divisors the `TIME` and interval lowerings divide by - nanoseconds per unit, micros
     * per day - and small ones on both signs, where truncation and floor part on every second
     * or third dividend rather than once in a billion.
     */
    private val LongDivisors = Array(2L, 3L, 7L, 60L, -60L, 1000L, 1000000L, 1000000000L,
      60000000000L, 3600000000000L, 86400000000L, -12L)

    def value(depth: Int): Gen = {
      if (depth == 0 || budget <= 1) return leaf()
      budget -= 1
      rnd.nextInt(9) match {
        case 0 | 1 =>
          // Add and subtract, over two subtrees or a subtree and a leaf. A checked mode only
          // where the bounds rule overflow out, as at the int lane: the kernel has to answer,
          // not decline, for the value comparison to check anything.
          val a = value(depth - 1)
          val b = if (rnd.nextBoolean()) leaf() else value(depth - 1)
          val sumBound = satAdd(a.bound, b.bound)
          val mode =
            if (saturated(sumBound)) Overflow.WRAP
            else rnd.nextInt(3) match {
              case 0 => Overflow.WRAP
              case 1 => Overflow.FAIL
              case _ => Overflow.NULL
            }
          val op = if (rnd.nextBoolean()) IntOp.ADD else IntOp.SUB
          Gen(new IntArith(op, mode, a.node, b.node), sumBound)
        case 2 =>
          // A checked multiply has no 64-bit overflow test, so WRAP is the only mode.
          val a = value(depth - 1)
          val b = value(depth - 1)
          Gen(new IntArith(IntOp.MUL, Overflow.WRAP, a.node, b.node), satMul(a.bound, b.bound))
        case 3 =>
          // Negation overflows on Long.MinValue alone, which any unsaturated bound rules out.
          val a = value(depth - 1)
          val checked = !saturated(a.bound) && rnd.nextBoolean()
          Gen(new IntNeg(if (checked) Overflow.FAIL else Overflow.WRAP, a.node), a.bound)
        case 4 =>
          // The constant division, over a dividend the lowering is exact for. Above the bound
          // the conversion form is off by one and the magic form reads the dividend modulo
          // 2^52, and neither is a bug this suite is asking about.
          val a = value(depth - 1)
          if (a.bound >= ConstDivide.EXACT_DIVIDEND_BOUND) return a
          val d = LongDivisors(rnd.nextInt(LongDivisors.length))
          // The grammar has tracked this subtree's bound all along; the node now carries it,
          // which is the whole of task 147 seen from the generator's side. Plus one, because
          // the grammar's bound is the widest magnitude a value takes and the node's is one the
          // value stays under - a guard from -bound to +bound admits bound itself.
          Gen(new ConstDivide(a.node, d, a.bound + 1), a.bound / math.abs(d))
        case 5 =>
          val a = value(depth - 1)
          if (saturated(a.bound)) {
            Gen(new GuardedRange(a.node, Long.MinValue, Long.MaxValue), a.bound)
          } else {
            Gen(new GuardedRange(a.node, -a.bound, a.bound), a.bound)
          }
        case 6 =>
          val a = value(depth - 1); val b = value(depth - 1)
          Gen(new Greatest(a.node, b.node), math.max(a.bound, b.bound))
        case 7 =>
          val a = value(depth - 1); val b = value(depth - 1)
          Gen(new Least(a.node, b.node), math.max(a.bound, b.bound))
        case _ =>
          val c = cond(depth - 1); val a = value(depth - 1); val b = value(depth - 1)
          Gen(new IfElse(c, a.node, b.node), math.max(a.bound, b.bound))
      }
    }

    def cond(depth: Int): Cond = {
      budget -= 1
      if (depth == 0 || budget <= 1 || rnd.nextInt(3) == 0) {
        val ops = CompareOp.values()
        new Compare(ops(rnd.nextInt(ops.length)), value(depth).node, value(depth).node)
      } else {
        rnd.nextInt(4) match {
          case 0 => new And(cond(depth - 1), cond(depth - 1))
          case 1 => new Or(cond(depth - 1), cond(depth - 1))
          case 2 => new Not(cond(depth - 1))
          case _ => new IsNotNull(new ColumnRef(rnd.nextInt(numInputs), LaneType.LONG))
        }
      }
    }
  }
}
