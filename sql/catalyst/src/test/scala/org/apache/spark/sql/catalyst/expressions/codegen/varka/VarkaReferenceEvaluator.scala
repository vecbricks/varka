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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils

/**
 * The reference evaluator (task 11): an independent Scala implementation of the milestone's 2.6
 * semantics - three-valued conditions, blend, null-skipping greatest/least, floorMod - that the
 * emitted loops are checked against row for row. Every calendar oracle is the definition
 * (`java.time`, `DateTimeUtils`) rather than `VarkaChrono`, the model the lowerings were derived
 * from, so the emitted bytes are held to the definition and not to themselves.
 *
 * Shared by [[VarkaLoopEmitterSuite]]'s curated matrices and [[VarkaIrFuzzSuite]]'s random
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
    // The first total condition (task 20): IS NOT NULL never returns unknown - a null
    // operand is a definite false, not a missing answer.
    case n: IsNotNull => Some(evalValue(n.child(), row, lits).isDefined)
  }
}
