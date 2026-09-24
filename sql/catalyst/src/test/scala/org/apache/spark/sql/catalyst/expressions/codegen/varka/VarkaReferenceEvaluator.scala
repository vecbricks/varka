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
import java.time.temporal.IsoFields

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils

/**
 * The reference evaluator (task 11): an independent Scala implementation of the milestone's 2.6
 * semantics - three-valued conditions, blend, null-skipping greatest/least, floorMod - that the
 * emitted loops are checked against row for row. Every calendar oracle is the definition
 * (`java.time`, `DateTimeUtils`) rather than `VarkaChrono`, the model the lowerings were derived
 * from, so the emitted bytes are held to the definition and not to themselves.
 *
 * Shared by [[VarkaEmitterTestBase]]'s curated matrices and [[VarkaIrFuzzSuite]]'s random
 * trees, which is the reason it is an object rather than the suite's private methods it began
 * as: one oracle, two very different sets of shapes driven through it.
 */
object VarkaReferenceEvaluator {

  def evalValue(
      node: VarkaVectorIR, row: Seq[Option[Int]], lits: Array[Int]): Option[Int] = node match {
    case c: ColumnRef => row(c.ordinal())
    case l: LiteralSlot => Some(lits(l.index()))
    case n: AddDays =>
      for (d <- evalValue(n.days(), row, lits); o <- evalValue(n.offset(), row, lits))
        yield d + o
    case n: SubDays =>
      for (d <- evalValue(n.days(), row, lits); o <- evalValue(n.offset(), row, lits))
        yield d - o
    // Task 93's range check is a status report, not a value change: the reference has no
    // notion of a declined batch, and a lane the guard would report is one the kernel does not
    // answer at all, so the differential never compares against it. The value passes through.
    case n: GuardedDay => evalValue(n.days(), row, lits)
    // A range guard changes no value; a lane outside it declines the batch, which the suites
    // assert on the status rather than on a value this evaluator could spell.
    case n: GuardedRange => evalValue(n.child(), row, lits)
    // A narrowing root is a long-lane shape: its child is computed over 64-bit rows, so it is
    // answered by `evalLong`, and a suite takes the low 32 bits of that as the store does.
    case n: NarrowLane => throw new IllegalArgumentException(
      "a narrowing root is evaluated through evalLong: " + n)
    case n: DateDiff =>
      for (e <- evalValue(n.end(), row, lits); s <- evalValue(n.start(), row, lits)) yield e - s
    case n: DayOfWeek =>
      evalValue(n.days(), row, lits).map(v => (Math.floorMod(v, 7) + 4) % 7 + 1)
    case n: WeekDay =>
      evalValue(n.days(), row, lits).map(v => (Math.floorMod(v, 7) + 3) % 7)
    // The oracle is Spark's own getNextDateForDayOfWeek, quoted directly, not the lowering:
    // Scala's Int arithmetic wraps exactly as the lanes do, so this is exact even at
    // Int.MinValue, and it is byte-for-byte what the row engine evaluates.
    case n: DayOfWeekIso =>
      // The definition: Spark's own weekday plus one, not the emitter's offset arithmetic.
      evalValue(n.days(), row, lits).map(v => DateTimeUtils.getWeekDay(v) + 1)
    case n: NextDay =>
      for (d <- evalValue(n.days(), row, lits); k <- evalValue(n.offset(), row, lits))
        yield d + 1 + Math.floorMod(k - d, 7)
    case n: ThursdayOf =>
      // The Thursday of the day's ISO (Monday-based) week, by java.time's own adjuster - not
      // d + 3 - weekday0, which is what the emitter computes.
      evalValue(n.days(), row, lits).map(v =>
        LocalDate.ofEpochDay(v.toLong).`with`(java.time.DayOfWeek.THURSDAY).toEpochDay.toInt)
    // The calendar oracle is java.time, which is what DateTimeUtils.getYear and its three
    // siblings call - not VarkaChrono, so the emitted bytes are checked against the
    // definition rather than against the model they were derived from.
    case n: Year =>
      evalValue(n.days(), row, lits).map(v => LocalDate.ofEpochDay(v.toLong).getYear)
    case n: Month =>
      evalValue(n.days(), row, lits).map(v => LocalDate.ofEpochDay(v.toLong).getMonthValue)
    case n: DayOfMonth =>
      evalValue(n.days(), row, lits).map(v => LocalDate.ofEpochDay(v.toLong).getDayOfMonth)
    case n: Quarter =>
      // IsoFields.QUARTER_OF_YEAR, which is what DateTimeUtils.getQuarter calls - not
      // (month + 2) / 3, which is what the emitter computes. An oracle that restates the
      // implementation is not an oracle.
      evalValue(n.days(), row, lits)
        .map(v => LocalDate.ofEpochDay(v.toLong).get(IsoFields.QUARTER_OF_YEAR))
    case n: DayOfYear =>
      evalValue(n.days(), row, lits).map(v => LocalDate.ofEpochDay(v.toLong).getDayOfYear)
    case n: WeekOfYear =>
      // The definition (what DateTimeUtils.getWeekOfYear calls), over whatever the child is;
      // it agrees with the emitter's (doy - 1) / 7 + 1 exactly because the child is a
      // Thursday, which the analysis enforces.
      evalValue(n.days(), row, lits)
        .map(v => LocalDate.ofEpochDay(v.toLong).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    case n: LastDay =>
      // The definition, not the linear-form-plus-leap-flag this task's lowering computes.
      evalValue(n.days(), row, lits).map(DateTimeUtils.getLastDayOfMonth)
    // DateTimeUtils.truncDate, which is what Spark's TruncDate evaluates - through LocalDate,
    // not through either of the emitter's two forms.
    case n: TruncDate =>
      val level = n.level() match {
        case TruncLevel.YEAR => DateTimeUtils.TRUNC_TO_YEAR
        case TruncLevel.MONTH => DateTimeUtils.TRUNC_TO_MONTH
        case TruncLevel.QUARTER => DateTimeUtils.TRUNC_TO_QUARTER
      }
      evalValue(n.days(), row, lits).map(DateTimeUtils.truncDate(_, level))
    // The same definition over the level lane (task 61). The leaf hands the kernel no code
    // outside the four date levels - anything else is a null lane - so a live lane with one is
    // not a shape the kernel is ever asked, and truncDate's throw on it is the right answer.
    case n: TruncDateDynamic =>
      for (d <- evalValue(n.days(), row, lits); level <- evalValue(n.level(), row, lits))
        yield DateTimeUtils.truncDate(d, level)
    // The oracle is DateTimeUtils.dateAddMonths - the definition AddMonthsBase's nullSafeEval
    // calls - not VarkaChrono.daysFromCivil, which is the model this node's own arithmetic was
    // derived from and checked against; using it here would test the lowering against itself.
    case n: AddMonths =>
      for (d <- evalValue(n.days(), row, lits); m <- evalValue(n.months(), row, lits))
        yield DateTimeUtils.dateAddMonths(d, m)
    // Task 63. The oracle is Java's own arithmetic, not the emitter's sign test: Math.addExact
    // and friends define overflow, and catching their throw is what says a lane overflowed.
    // WRAP is the wrapping operator, which is what Spark's LEGACY mode and the JVM both do.
    case n: IntArith =>
      for (l <- evalValue(n.left(), row, lits); r <- evalValue(n.right(), row, lits); v <- {
        def exact(f: (Int, Int) => Int): Option[Int] =
          try Some(f(l, r)) catch { case _: ArithmeticException => None }
        val wrapped = n.op() match {
          case IntOp.ADD => l + r
          case IntOp.SUB => l - r
          case IntOp.MUL => l * r
        }
        n.mode() match {
          case Overflow.WRAP => Some(wrapped)
          // FAIL declines the batch rather than returning a value, so a row that overflows
          // has no expected value at all; the suite asserts the status, and reaching this
          // arm with an overflowing row means the kernel did not decline when it had to.
          case Overflow.FAIL | Overflow.NULL => n.op() match {
            case IntOp.ADD => exact(Math.addExact)
            case IntOp.SUB => exact(Math.subtractExact)
            case IntOp.MUL => exact(Math.multiplyExact)
          }
        }
      }) yield v
    case n: IntNeg =>
      for (c <- evalValue(n.child(), row, lits); v <- n.mode() match {
        case Overflow.WRAP => Some(-c)
        case _ => if (c == Int.MinValue) None else Some(-c)
      }) yield v
    // Java's own `/`, which is the definition the node claims to compute: the oracle divides
    // rather than reproducing the emitter's conversion, so the two agree only if the lowering
    // is right.
    case n: ConstDivide =>
      // The divisor is declared at the wider lane and narrowed here, which the node's own
      // constructor makes safe: an int-lane division whose divisor does not fit in an int is
      // refused where it is built.
      evalValue(n.child(), row, lits).map(_ / n.divisor().toInt)
    // The definition, a true division: the kernel's single multiply must agree with it over
    // the bound, and a dividend outside the bound is one the suites never hand the kernel.
    case n: BoundedDivide => evalValue(n.child(), row, lits).map(_ / n.divisor())
    case n: MakeDate =>
      // The definition: LocalDate.of, null (None) where the calendar rejects the triple - never
      // the length rule the emitter computes. The year limit is the kernel's business, not the
      // oracle's: a year outside it is a declined batch, which the status assertion catches.
      for {
        y <- evalValue(n.year(), row, lits); m <- evalValue(n.month(), row, lits)
        d <- evalValue(n.day(), row, lits)
        v <- try Some(LocalDate.of(y, m, d).toEpochDay.toInt)
          catch { case _: java.time.DateTimeException => None }
      } yield v
    case n: Greatest =>
      pick(evalValue(n.left(), row, lits), evalValue(n.right(), row, lits), math.max)
    case n: Least =>
      pick(evalValue(n.left(), row, lits), evalValue(n.right(), row, lits), math.min)
    case n: IfElse =>
      if (evalCond(n.cond(), row, lits).contains(true)) evalValue(n.thenNode(), row, lits)
      else evalValue(n.elseNode(), row, lits)
    case c: Cond => throw new IllegalArgumentException(s"condition $c evaluated as a value")
  }

  private def pick(a: Option[Int], b: Option[Int], op: (Int, Int) => Int): Option[Int] =
    (a, b) match {
      case (Some(x), Some(y)) => Some(op(x, y))
      case (Some(x), None) => Some(x)
      case (None, y) => y
    }

  /**
   * The reference at the long lane, for the subset task 85 ships there: the two leaves, the
   * arithmetic and its three overflow modes, the negate, the hull ops and the conditional.
   * Task 119's first part, landing with the lane it exists to check.
   *
   * It is a second method rather than a widening of `evalValue` because the two answer
   * different questions: a `DateDiff` or a `Year` has no meaning over 64-bit lanes and must
   * not silently produce one, so every calendar node is absent here and reaching one is an
   * error rather than a computation. Scala's `Long` arithmetic wraps exactly as the lanes do,
   * which is what makes this exact at `Long.MinValue` as `evalValue` is at `Int.MinValue`.
   */
  def evalLong(
      node: VarkaVectorIR, row: Seq[Option[Long]], lits: Array[Long]): Option[Long] = node match {
    case c: ColumnRef => row(c.ordinal())
    case l: LiteralSlot => Some(lits(l.index()))
    case n: IntArith =>
      for (l <- evalLong(n.left(), row, lits); r <- evalLong(n.right(), row, lits); v <- {
        def exact(f: (Long, Long) => Long): Option[Long] =
          try Some(f(l, r)) catch { case _: ArithmeticException => None }
        val wrapped = n.op() match {
          case IntOp.ADD => l + r
          case IntOp.SUB => l - r
          case IntOp.MUL => l * r
        }
        n.mode() match {
          case Overflow.WRAP => Some(wrapped)
          // As at the int lane: FAIL declines the batch rather than answering, so an
          // overflowing row has no expected value and the suite asserts the status instead.
          case Overflow.FAIL | Overflow.NULL => n.op() match {
            case IntOp.ADD => exact(Math.addExact)
            case IntOp.SUB => exact(Math.subtractExact)
            // No checked multiply exists at either lane - the int one has no 64-bit product to
            // test with and the long one would need 128 bits, which is task 104's.
            case IntOp.MUL => throw new IllegalArgumentException(
              "a checked multiply has no long-lane overflow test: " + n)
          }
        }
      }) yield v
    case n: IntNeg =>
      for (c <- evalLong(n.child(), row, lits); v <- n.mode() match {
        case Overflow.WRAP => Some(-c)
        // The lane's own most negative value, whose negation is itself.
        case _ => if (c == Long.MinValue) None else Some(-c)
      }) yield v
    case n: ConstDivide =>
      evalLong(n.child(), row, lits).map(_ / n.divisor())
    case n: GuardedRange => evalLong(n.child(), row, lits)
    case n: BoundedDivide =>
      throw new IllegalArgumentException("a bounded division is an int-lane node: " + n)
    // The narrowing is the kernel's store, not a value change: the reference answers the child
    // in full and the suite narrows it the way the store does, so a value that does not fit an
    // int would show as a difference rather than be truncated on both sides.
    case n: NarrowLane => evalLong(n.child(), row, lits)
    case n: Greatest =>
      (evalLong(n.left(), row, lits), evalLong(n.right(), row, lits)) match {
        case (Some(a), Some(b)) => Some(math.max(a, b))
        case (a, b) => a.orElse(b)
      }
    case n: Least =>
      (evalLong(n.left(), row, lits), evalLong(n.right(), row, lits)) match {
        case (Some(a), Some(b)) => Some(math.min(a, b))
        case (a, b) => a.orElse(b)
      }
    case n: IfElse =>
      // Unknown takes the else branch, as at the int lane and as CASE WHEN does.
      if (evalCondLong(n.cond(), row, lits).contains(true)) evalLong(n.thenNode(), row, lits)
      else evalLong(n.elseNode(), row, lits)
    case other => throw new IllegalArgumentException(
      "no long-lane reference for " + other.getClass.getSimpleName + "; task 85 ships the "
        + "lane-generic subset only")
  }

  /** `evalCond`'s counterpart over long lanes, for the conditions the same subset ships. */
  def evalCondLong(
      cond: Cond, row: Seq[Option[Long]], lits: Array[Long]): Option[Boolean] = cond match {
    case n: Compare =>
      for (l <- evalLong(n.left(), row, lits); r <- evalLong(n.right(), row, lits)) yield {
        n.op() match {
          case CompareOp.LT => l < r
          case CompareOp.LE => l <= r
          case CompareOp.GT => l > r
          case CompareOp.GE => l >= r
          case CompareOp.EQ => l == r
        }
      }
    case n: And =>
      (evalCondLong(n.left(), row, lits), evalCondLong(n.right(), row, lits)) match {
        case (Some(false), _) | (_, Some(false)) => Some(false)
        case (Some(true), Some(true)) => Some(true)
        case _ => None
      }
    case n: Or =>
      (evalCondLong(n.left(), row, lits), evalCondLong(n.right(), row, lits)) match {
        case (Some(true), _) | (_, Some(true)) => Some(true)
        case (Some(false), Some(false)) => Some(false)
        case _ => None
      }
    case n: Not => evalCondLong(n.child(), row, lits).map(!_)
    case n: IsNotNull => Some(evalLong(n.child(), row, lits).isDefined)
    case n: InRanges =>
      throw new IllegalArgumentException("a range set is on the int lane only: " + n)
  }

  /** Kleene three-valued logic; `None` is unknown, and only known-true selects THEN. */
  def evalCond(
      cond: Cond, row: Seq[Option[Int]], lits: Array[Int]): Option[Boolean] = cond match {
    case n: Compare =>
      for (l <- evalValue(n.left(), row, lits); r <- evalValue(n.right(), row, lits)) yield {
        n.op() match {
          case CompareOp.LT => l < r
          case CompareOp.LE => l <= r
          case CompareOp.GT => l > r
          case CompareOp.GE => l >= r
          case CompareOp.EQ => l == r
        }
      }
    case n: And =>
      (evalCond(n.left(), row, lits), evalCond(n.right(), row, lits)) match {
        case (Some(false), _) | (_, Some(false)) => Some(false)
        case (Some(true), Some(true)) => Some(true)
        case _ => None
      }
    case n: Or =>
      (evalCond(n.left(), row, lits), evalCond(n.right(), row, lits)) match {
        case (Some(true), _) | (_, Some(true)) => Some(true)
        case (Some(false), Some(false)) => Some(false)
        case _ => None
      }
    case n: Not => evalCond(n.child(), row, lits).map(!_)
    // Written from the node's meaning, not from its emission: some range contains the value.
    case n: InRanges =>
      evalValue(n.child(), row, lits).map { v =>
        val bounds: IndexedSeq[Int] = n.bounds().asScala.map(_.intValue).toIndexedSeq
        bounds.indices.by(2).exists(i => bounds(i) <= v && v <= bounds(i + 1))
      }
    // The first total condition (task 20): IS NOT NULL never returns unknown - a null
    // operand is a definite false, not a missing answer.
    case n: IsNotNull => Some(evalValue(n.child(), row, lits).isDefined)
  }
}
