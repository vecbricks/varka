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

package org.apache.spark.sql.catalyst.expressions.codegen

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.{And, BoundReference, CaseWhen, Coalesce, EqualTo,
  Expression, GreaterThan, GreaterThanOrEqual, If, In, InSet, IsNotNull, IsNull, LessThan,
  LessThanOrEqual, Literal, Not, Or, RuntimeReplaceable}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{And => IRAnd,
  ColumnRef, Compare, CompareOp, Cond, IfElse, InRanges, IsNotNull => IRIsNotNull, Not => IRNot,
  Or => IROr}
import org.apache.spark.sql.types.{DateType, IntegerType, YearMonthIntervalType}

/**
 * The predicate family of the compiler: the three-valued conditions - comparisons, `IN` over date
 * literals, the validity predicates and the connectives - compiled by `compileCond` for a filter's
 * mask root and for the value-side conditionals `IF`, `CASE WHEN` and `coalesce`, which fold onto
 * `IfElse` over those conditions.
 *
 * The arms are a partial function `VarkaExpressionCompiler.compileNode` chains for the
 * conditionals; `compilePredicate` reaches `compileCond` and the balanced `andFold` directly.
 */
private[codegen] object VarkaConditionCompiler {

  /**
   * The conditional arms of `compileNode`: `IF`, `CASE WHEN` and `coalesce`.
   */
  private[codegen] def arms(
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): PartialFunction[Expression, Option[VarkaVectorIR]] = {
    case expr @ If(pred, thenValue, elseValue) =>
      for {
        cond <- compileCond(pred, inputs, literals, sink)
        thenNode <- compileNode(thenValue, inputs, literals, sink)
        elseNode <- compileNode(elseValue, inputs, literals, sink)
        if sameLane(expr, sink, cond, thenNode, elseNode)
      } yield new IfElse(cond, thenNode, elseNode)
    // With no ELSE the missing branch is a null literal, which would break the dense body's
    // all-valid invariant (`PLAN_TASK_11.md` 2.1): decline.
    case c @ CaseWhen(_, None) =>
      sink.note("CASE WHEN without an ELSE branch", c)
      None
    // CASE WHEN with an ELSE right-folds into nested IfElse - SQL's first-match semantics is
    // exactly nested if-else. Compilation runs in query order (branches left to right, then
    // the ELSE) so input ordinals and literal slots register deterministically in reading
    // order; only the fold is right-associative.
    case expr @ CaseWhen(branches, elseValue) =>
      elseValue.flatMap { elseExpr =>
        val compiledBranches = branches.map { case (pred, value) =>
          (compileCond(pred, inputs, literals, sink),
            compileNode(value, inputs, literals, sink))
        }
        val compiledElse = compileNode(elseExpr, inputs, literals, sink)
        if (compiledBranches.forall(b => b._1.isDefined && b._2.isDefined)
            && compiledElse.isDefined
            && sameLane(expr, sink,
              (compiledBranches.flatMap(b => Seq(b._1.get, b._2.get)) :+ compiledElse.get): _*)) {
          Some(compiledBranches.foldRight(compiledElse.get) { case ((cond, value), rest) =>
            new IfElse(cond.get, value.get, rest)
          })
        } else {
          None
        }
      }
    // Coalesce right-folds onto the validity condition: `coalesce(a, b)` is
    // `IfElse(IsNotNull(a), a, b)`, whose masked validity - (kT & valid(a)) | (~kT & valid(b))
    // with kT = valid(a) - reduces to valid(a) | valid(b), exactly SQL's coalesce. Every
    // operand before the last must be a bare date column (the IsNotNull child restriction);
    // `nvl`/`ifnull` arrive here already rewritten to Coalesce by the optimizer, and `nvl2`
    // arrives as `If(IsNotNull(...), ...)` and rides the same condition node.
    case Coalesce(children) if children.nonEmpty =>
      compileCoalesce(children, inputs, literals, sink)
  }

  /**
   * Whether `nodes` share a lane, noting the mismatch against `whole` when they do not. Every
   * IR node whose operands may disagree - the connectives, the blend - refuses a mix in its
   * constructor, so this is asked first wherever Spark's typing does not already force the
   * agreement: `CASE WHEN l > 0 THEN d ELSE d2` type-checks, and its condition is on the long
   * lane while its branches are on the int one.
   */
  private[codegen] def sameLane(
      whole: Expression, sink: DeclineSink, nodes: VarkaVectorIR*): Boolean = {
    val lanes = nodes.map(_.laneType()).distinct
    if (lanes.size <= 1) {
      true
    } else {
      sink.note(s"one kernel holds one lane, and this mixes the ${lanes(0)} and ${lanes(1)} lanes",
        whole)
      false
    }
  }

  /**
   * Folds the fused conjuncts back into one root, '''balanced''' like [[balancedOr]] and for
   * the same reason: Kleene AND is associative, so the shape is a canonicalization, and a
   * left fold would grow the chain depth by one per conjunct - a WHERE of 16 fusible
   * conjuncts would trip `MAX_CHAIN_DEPTH` for no semantic reason, where the balanced fold
   * stays logarithmic.
   */
  private[codegen] def andFold(conds: Seq[Cond]): Cond = balancedFold(conds, new IRAnd(_, _))

  /** The disjunction of `conds` as a balanced tree, as [[andFold]] is of a conjunction; the
   * partial roots of a split predicate are folded with it. */
  private[codegen] def orFold(conds: Seq[Cond]): Cond = balancedOr(conds)

  /**
   * The Coalesce right-fold. Every operand except the last compiles and must be a bare date column:
   * `IsNotNull` reads the per-input validity word, which only a column has before value emission
   * (the recorded milestone-3 restriction) - a computed operand declines with its own reason. The
   * `ColumnRef` match below is a proxy for "this operand is a bare column" that is exact only
   * because every `compileNode` arm producing a `ColumnRef` today is either an actual column read
   * or a null-intolerant identity relabel (`unix_date`/`date_from_unix_date` and the identity date
   * `Cast`) - a future relabel that changes nullability or value would silently break this guard.
   */
  private def compileCoalesce(
      children: Seq[Expression],
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = children match {
    case Seq(last) => compileNode(last, inputs, literals, sink)
    case head +: rest =>
      compileNode(head, inputs, literals, sink) match {
        case Some(ref: ColumnRef) =>
          compileCoalesce(rest, inputs, literals, sink)
            .map(restNode => new IfElse(new IRIsNotNull(ref), ref, restNode))
        case Some(_) =>
          sink.note("coalesce operand before the last is not a bare date column", head)
          None
        case None => None
      }
  }

  private[codegen] def foldPick(
      children: Seq[Expression],
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink,
      combine: (VarkaVectorIR, VarkaVectorIR) => VarkaVectorIR): Option[VarkaVectorIR] = {
    val compiled = children.map(compileNode(_, inputs, literals, sink))
    if (compiled.nonEmpty && compiled.forall(_.isDefined)) {
      Some(compiled.flatten.reduceLeft(combine))
    } else {
      None
    }
  }

  /**
   * The condition compiler: interior comparisons and the connectives, three-valued
   * at run time via the emitter's known-true/known-false pairs. `EqualNullSafe` deliberately
   * declines - its both-null-is-true case breaks the null-intolerant comparison rule and earns
   * its own algebra entry or nothing (plan section 4).
   */
  private[codegen] def compileCond(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[Cond] = expr match {
    case LessThan(l, r) => compare(CompareOp.LT, l, r, inputs, literals, sink)
    case LessThanOrEqual(l, r) => compare(CompareOp.LE, l, r, inputs, literals, sink)
    case GreaterThan(l, r) => compare(CompareOp.GT, l, r, inputs, literals, sink)
    case GreaterThanOrEqual(l, r) => compare(CompareOp.GE, l, r, inputs, literals, sink)
    case EqualTo(l, r) => compare(CompareOp.EQ, l, r, inputs, literals, sink)
    // IN over date literals: an EQ chain joined by OR, which the mask algebra
    // makes exactly SQL's IN inside a condition - a null value leaves every comparison
    // unknown, the OR of unknowns is unknown, and an unknown condition falls to ELSE.
    case in @ In(value, list)
        if value.dataType == DateType || value.dataType.isInstanceOf[YearMonthIntervalType] =>
      compileInList(value, list.map(literalDays), in, inputs, literals, sink)
    case inSet: InSet
        if inSet.child.dataType == DateType
          || inSet.child.dataType.isInstanceOf[YearMonthIntervalType] =>
      // InSet's set is unordered; compileInList sorts, which is what keeps the literal
      // slots and the shape hash deterministic across runs.
      compileInList(inSet.child,
        inSet.hset.toSeq.map { case days: Int => Some(days); case _ => None },
        inSet, inputs, literals, sink)
    case And(l, r) =>
      for {
        left <- compileCond(l, inputs, literals, sink)
        right <- compileCond(r, inputs, literals, sink)
        if sameLane(expr, sink, left, right)
      } yield new IRAnd(left, right)
    // A disjunction of ranges over one int or date column - the partition-key filter a BI tool
    // writes for a set of date ranges - is one range set, whose code does not grow with the
    // ranges, instead of a tree of comparisons whose code does.
    case or: Or if sink.rangeSets && rangeSet(or).isDefined =>
      val (column, bounds) = rangeSet(or).get
      Some(new InRanges(columnRef(column, inputs), bounds.map(Int.box).asJava))
    case Or(l, r) =>
      for {
        left <- compileCond(l, inputs, literals, sink)
        right <- compileCond(r, inputs, literals, sink)
        if sameLane(expr, sink, left, right)
      } yield new IROr(left, right)
    case Not(child) => compileCond(child, inputs, literals, sink).map(new IRNot(_))
    // The validity predicates: IS NOT NULL is the IR's first total condition
    // (never unknown), and IS NULL is its NOT - a slot swap in the emitter, no code.
    case IsNotNull(child) =>
      compileValidity(child, expr, inputs, literals, sink)
    case IsNull(child) =>
      compileValidity(child, expr, inputs, literals, sink).map(new IRNot(_))
    // Defensive, mirroring compileNode: hand-built Nvl/Nvl2 in tests and the fusion report
    // arrive unreplaced; real queries never do.
    case r: RuntimeReplaceable =>
      compileCond(r.replacement, inputs, literals, sink)
    case other =>
      sink.note("unsupported predicate", other)
      None
  }

  /**
   * The int a date or year-month interval literal holds - epoch days for one, a month count
   * for the other - or `None` for anything else (null included). Both are int32 lanes and an
   * `IN` list is type-homogeneous, so one function serves both; the type gate is on the `In`
   * arms, which is where the value's own type decides.
   */
  private def literalDays(e: Expression): Option[Int] = e match {
    case Literal(days: Int, DateType) => Some(days)
    case Literal(months: Int, _: YearMonthIntervalType) => Some(months)
    case _ => None
  }

  /**
   * Compiles an IN list: dedup and sort the literal days - Kleene OR is commutative
   * and EQ is pure, so the order is free, and a canonical order keeps the literal slots and
   * the shape hash deterministic (`InSet` hands the values over as an unordered set) - then a
   * '''balanced''' pairwise fold of OR over the EQ leaves. The fold shape is part of the cap
   * arithmetic: balanced, [[VarkaExpressionCompiler.MaxInLiterals]] literals are
   * `ceil(log2 n) + 1` levels and `2n - 1` op nodes; a right-nested fold would hit the emitter's
   * depth cap at 15. Above the cap, or with any non-literal or null element, the entry declines
   * with its reason.
   */
  private def compileInList(
      value: Expression,
      elements: Seq[Option[Int]],
      whole: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[Cond] = {
    if (elements.isEmpty || elements.exists(_.isEmpty)) {
      sink.note("IN list has a null or non-literal element", whole)
      None
    } else {
      val days = elements.flatten.distinct.sorted
      if (days.size > MaxInLiterals) {
        sink.note(s"IN list longer than the fused cap of $MaxInLiterals", whole)
        None
      } else {
        compileNode(value, inputs, literals, sink).map { compiledValue =>
          val leaves: Seq[Cond] = days.map { d =>
            new Compare(CompareOp.EQ, compiledValue,
              intSlot(d, literals))
          }
          balancedOr(leaves)
        }
      }
    }
  }

  /** Pairwise-reduces conditions into a balanced OR tree; the base of the cap arithmetic. */
  private def balancedOr(level: Seq[Cond]): Cond = balancedFold(level, new IROr(_, _))

  /** Pairwise-reduces conditions into a balanced tree of `combine` - the shared shape behind
   * [[balancedOr]] and the predicate's [[andFold]]. */
  @scala.annotation.tailrec
  private def balancedFold(level: Seq[Cond], combine: (Cond, Cond) => Cond): Cond = {
    require(level.nonEmpty, "balancedFold needs at least one condition")
    if (level.size == 1) {
      level.head
    } else {
      balancedFold(level.grouped(2).map {
        case Seq(a, b) => combine(a, b)
        case Seq(a) => a
      }.toSeq, combine)
    }
  }

  /**
   * Compiles the operand of a validity predicate, which must land on a bare column: the emitter
   * reads the column's per-lane-group validity word, and only a column's word is live before
   * value emission (the recorded milestone-3 restriction). As in `compileCoalesce` above, the
   * `ColumnRef` match is a proxy for "bare column" that depends on every relabel expression
   * compiling to `ColumnRef` staying a null-intolerant identity.
   *
   * <p>The column may be a date or an `IntegerType` one. Both are the same int32 lane and the
   * same validity word, and the int case is not optional: Spark's optimizer infers
   * `isnotnull(i)` beside any null-intolerant predicate on `i`, so refusing it would leave a
   * residual row filter above every fused int comparison (task 122) - the kernel would do the
   * comparison and the row engine would still visit every row to check the null.
   */
  private def compileValidity(
      child: Expression,
      whole: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[Cond] = {
    val compiled = child match {
      case br: BoundReference if br.dataType == IntegerType => Some(columnRef(br, inputs))
      case _ => compileNode(child, inputs, literals, sink)
    }
    compiled match {
      case Some(ref: ColumnRef) => Some(new IRIsNotNull(ref))
      case Some(_) =>
        sink.note("validity predicate over a non-column operand", whole)
        None
      case None => None
    }
  }

  /**
   * `or` as a range set, if it is one: two or more disjuncts, each a range over the same int or
   * date column with literal bounds - `c >= a and c <= b` in either order and either operand
   * order, `c = a`, or a strict bound, which moves by one - as the column and the ranges'
   * bounds, sorted by lower bound and merged where they overlap or touch. `None` for anything
   * else, which then compiles as the comparisons it is written as. A disjunct whose range is
   * empty (`c > a and c < a + 1`) selects nothing and is dropped; if all are, it is not a set.
   */
  private[codegen] def rangeSet(or: Or): Option[(BoundReference, Seq[Int])] = {
    def disjuncts(e: Expression): Seq[Expression] = e match {
      case Or(l, r) => disjuncts(l) ++ disjuncts(r)
      case other => Seq(other)
    }
    def column(e: Expression): Option[BoundReference] = e match {
      case br: BoundReference if br.dataType == IntegerType || br.dataType == DateType =>
        Some(br)
      case _ => None
    }
    def bound(e: Expression, of: BoundReference): Option[Long] = e match {
      case Literal(v: Int, t) if t == of.dataType => Some(v.toLong)
      case _ => None
    }
    // One side of a range: the column, and a lower or upper bound as an inclusive long. Which
    // it is depends on the operator and on which operand is the column: `c >= a` is a lower
    // bound, `a >= c` an upper one.
    def side(e: Expression): Option[(BoundReference, Option[Long], Option[Long])] = {
      // `columnIsLower`: whether the operator bounds the column from below when the column is
      // its left operand.
      def oriented(l: Expression, r: Expression, columnIsLower: Boolean, strict: Boolean) = {
        val step = if (strict) 1L else 0L
        def as(col: BoundReference, v: Long, lower: Boolean) =
          if (lower) (col, Some(v + step), None) else (col, None, Some(v - step))
        column(l).flatMap(col => bound(r, col).map(v => as(col, v, columnIsLower)))
          .orElse(column(r).flatMap(col => bound(l, col).map(v => as(col, v, !columnIsLower))))
      }
      e match {
        case GreaterThanOrEqual(l, r) => oriented(l, r, columnIsLower = true, strict = false)
        case GreaterThan(l, r) => oriented(l, r, columnIsLower = true, strict = true)
        case LessThanOrEqual(l, r) => oriented(l, r, columnIsLower = false, strict = false)
        case LessThan(l, r) => oriented(l, r, columnIsLower = false, strict = true)
        case _ => None
      }
    }
    def range(e: Expression): Option[(BoundReference, Long, Long)] = e match {
      case EqualTo(c, b) =>
        column(c).flatMap(col => bound(b, col).map(v => (col, v, v)))
          .orElse(column(b).flatMap(col => bound(c, col).map(v => (col, v, v))))
      case And(l, r) =>
        for {
          (lc, llo, lhi) <- side(l)
          (rc, rlo, rhi) <- side(r)
          if lc.ordinal == rc.ordinal && lc.dataType == rc.dataType
          lo <- llo.orElse(rlo)
          hi <- lhi.orElse(rhi)
          if llo.isDefined != rlo.isDefined
        } yield (lc, lo, hi)
      case _ => None
    }
    val parts = disjuncts(or)
    val ranges = parts.flatMap(range)
    if (parts.size < 2 || ranges.size != parts.size
        || ranges.map(r => (r._1.ordinal, r._1.dataType)).distinct.size != 1) {
      return None
    }
    val merged = mutable.ArrayBuffer.empty[(Long, Long)]
    for ((lo, hi) <- ranges.map(r => (r._2, r._3)).filter(r => r._1 <= r._2).sortBy(_._1)) {
      if (merged.nonEmpty && lo <= merged.last._2 + 1) {
        merged(merged.size - 1) = (merged.last._1, math.max(merged.last._2, hi))
      } else {
        merged += ((lo, hi))
      }
    }
    if (merged.isEmpty) {
      None
    } else {
      // The strict bounds moved by one in longs; clamp back into the int range, which a moved
      // bound can only have left by stepping past an int extreme that no int value is beyond.
      def clamp(v: Long): Int = math.max(Int.MinValue, math.min(Int.MaxValue, v)).toInt
      Some((ranges.head._1, merged.toSeq.flatMap { case (lo, hi) => Seq(clamp(lo), clamp(hi)) }))
    }
  }

  private def compare(
      op: CompareOp,
      l: Expression,
      r: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[Cond] = {
    // What a comparison's operands may be, beyond what `compileNode` yields. An int literal
    // against a fused int field - `weekofyear(d) = 53`, `month(d) = 6` - is a comparison of two
    // int lanes like any other, and the literal takes a slot the way a date literal does. An
    // `IntegerType` column is the same lane read from a different place, which `intOperand`
    // already admits for arithmetic (task 63), so `i > 0` and `i < i2` compare in the kernel
    // rather than leaving a residual row filter above it. Both cases are stated here rather
    // than in `compileNode`, whose value leaves stay `DateType`: a bare int has no meaning as a
    // *date* operand, and widening that would admit `date_add(d, i)`'s offset as a date.
    //
    // There is no guard question. A comparison produces a mask, not a value, so no result can
    // leave the int range - which is why this takes one rule where task 63's arithmetic needed
    // an overflow mode.
    def operand(e: Expression): Option[VarkaVectorIR] = e match {
      case Literal(v: Int, IntegerType) =>
        Some(intSlot(v, literals))
      case br: BoundReference if br.dataType == IntegerType => Some(columnRef(br, inputs))
      case _ => compileNode(e, inputs, literals, sink)
    }
    for {
      left <- operand(l)
      right <- operand(r)
    } yield new Compare(op, left, right)
  }
}
