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

import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, BindReferences, BoundReference, CaseWhen, DateAdd, DateDiff, DateSub, DateVarkaSupport, DayOfWeek, EqualTo, Expression, GreaterThan, GreaterThanOrEqual, Greatest, If, Least, LessThan, LessThanOrEqual, Literal, NamedExpression, Not, Or, WeekDay}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays, ColumnRef, CompareOp, DateDiff => IRDateDiff, LiteralSlot, SubDays}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{And => IRAnd, Compare, Cond, DayOfWeek => IRDayOfWeek, Greatest => IRGreatest, IfElse, Least => IRLeast, Not => IRNot, Or => IROr, WeekDay => IRWeekDay}
import org.apache.spark.sql.types.{DataType, DateType}

/**
 * A whole projection compiled to the Varka vector IR (milestone 2, task 10): the trees
 * `VarkaLoopEmitter` turns into one fused loop, plus everything the evaluator needs to drive the
 * emitted class - which child columns it reads (dense kernel input index = position in
 * `inputOrdinals`), the runtime `scalarArgs` values (slot index = position in `literals`), and
 * each output's Spark type, which is what tells a `datediff` day-count column (`IntegerType`)
 * apart from a date column when the output vectors are allocated.
 */
private[sql] case class CompiledVarkaProjection(
    outputs: Seq[VarkaVectorIR],
    outputTypes: Seq[DataType],
    inputOrdinals: Seq[Int],
    literals: Seq[Int])

/**
 * How one projection entry is served under partial eligibility (task 12): computed by the fused
 * kernel, forwarded as the input's own vector, or evaluated per row by the residual projection.
 */
private[sql] sealed trait VarkaOutputSpec

/** A kernel column: output `fusedIndex` of the fused sub-projection. */
private[sql] case class FusedOutput(fusedIndex: Int) extends VarkaOutputSpec

/**
 * A bare column reference, forwarded zero-copy from child output ordinal `childOrdinal`. Any
 * type, not just dates: forwarding never reads the values, so it does not care about lanes.
 */
private[sql] case class ForwardedOutput(childOrdinal: Int) extends VarkaOutputSpec

/** Everything else: evaluated per row, one pass for all residual entries together. */
private[sql] case object ResidualOutput extends VarkaOutputSpec

/**
 * A projection classified entry by entry (task 12): `specs` has one entry per projectList
 * position, in order, and `fused` is the sub-projection of just the [[FusedOutput]] entries -
 * their kernel-input and literal tables cover only what the fused trees reference, so a
 * residual entry constrains neither the emitted loop nor `canRun`'s Arrow check.
 */
private[sql] case class PartialVarkaProjection(
    specs: Seq[VarkaOutputSpec],
    fused: CompiledVarkaProjection)

/**
 * Compiles a bound projection list to the Varka vector IR, recursing where the MVP's
 * flat matcher demanded bare attributes - `datediff(date_add(d, 7), d2)` compiles where
 * milestone 1 saw nothing, and since task 11 so do `CASE WHEN`/`IF` (via interior comparisons
 * and the three-valued connectives), `greatest`/`least`, `dayofweek`/`weekday` and date
 * literals. Used by both `VarkaColumnarRule` (is the projection eligible?) and
 * `VarkaKernelEvaluator` (what does the emitted loop compute?), so eligibility cannot drift from
 * execution: there is one compiler and the rule's question is `compilePartial(...).isDefined`.
 *
 * Since task 12 eligibility is per entry, not all or nothing: [[compilePartial]] classifies
 * every entry as fused, forwarded (a bare column of any type, zero-copy) or residual (per-row),
 * and the projection is eligible when at least one entry fuses - a projection of forwards and
 * residuals alone gains nothing from Varka and stays on Janino untouched. [[compile]] remains
 * as the all-entries-fused special case for callers that need exactly that.
 *
 * Literal day offsets fold through [[DateVarkaSupport.foldDaysOffset]] - the same rule the MVP
 * matched on - into slots of the runtime argument table, assigned per distinct '''value''': two
 * occurrences of `date_add(d, 1)` must compile to equal IR records, or the emitter's CSE could
 * not see they are one computation. Slots are numbered in first-occurrence order, so a chain's
 * shape does not depend on what its constants are - the identity milestone 3's cache will key
 * on.
 *
 * The expressions' own `isClassFileGenEligible` and its genCode-time registration are
 * deliberately not consulted and not widened: they feed the Janino compile-cache key and stay as
 * milestone 1 left them (`PLAN_MILESTONE_2.md` section 4).
 */
private[sql] object VarkaExpressionCompiler {

  /** The all-entries-fused special case of [[compilePartial]], kept for callers that need it. */
  def compile(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute]): Option[CompiledVarkaProjection] = {
    compilePartial(projectList, childOutput).collect {
      case partial if partial.specs.forall(_.isInstanceOf[FusedOutput]) => partial.fused
    }
  }

  /**
   * Classifies every projection entry (see [[VarkaOutputSpec]]) and compiles the fused entries
   * into one sub-projection. `Some` exactly when at least one entry fused and the fused trees
   * reference at least one column - the emitted loop reads columns or has nothing to
   * vectorize over.
   */
  def compilePartial(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute]): Option[PartialVarkaProjection] = {
    // Both tables assign dense indices in first-occurrence order, which makes the compiled
    // shape deterministic in the projection alone.
    val inputs = mutable.LinkedHashMap.empty[Int, Int]
    val literals = mutable.LinkedHashMap.empty[Int, Int]
    val outputs = Seq.newBuilder[VarkaVectorIR]
    val outputTypes = Seq.newBuilder[DataType]
    var fusedCount = 0
    val specs = projectList.map { named =>
      // Bound at Expression, not NamedExpression: a bare column entry binds to a
      // BoundReference, which is not a NamedExpression, and the cast inside bindReference
      // would throw instead of letting the match below classify it.
      val bound = BindReferences.bindReference[Expression](named, childOutput)
      val inner = bound match {
        case Alias(child, _) => child
        case e => e
      }
      inner match {
        // A bare column is compilable as a node but never emitted as an output: emitting it
        // would be a copy loop, while forwarding the input's vector is zero-copy.
        case br: BoundReference => ForwardedOutput(br.ordinal)
        case e =>
          // The tables are shared across entries (CSE across outputs depends on it), so a
          // declining entry must not leave the columns and literals its failing subtrees
          // registered: they would widen the kernel's input set - and `canRun`'s Arrow check -
          // for no output. Entries are appended in table order, so truncating to the
          // pre-entry size restores the exact prior state.
          val inputsMark = inputs.size
          val literalsMark = literals.size
          compileNode(e, inputs, literals) match {
            case Some(ir) =>
              outputs += ir
              outputTypes += e.dataType
              fusedCount += 1
              FusedOutput(fusedCount - 1)
            case None =>
              truncate(inputs, inputsMark)
              truncate(literals, literalsMark)
              ResidualOutput
          }
      }
    }
    if (fusedCount > 0 && inputs.nonEmpty) {
      Some(PartialVarkaProjection(specs, CompiledVarkaProjection(
        outputs.result(), outputTypes.result(), inputs.keys.toSeq, literals.keys.toSeq)))
    } else {
      None
    }
  }

  /** Drops the entries a failed compile appended after `mark` (insertion order). */
  private def truncate(table: mutable.LinkedHashMap[Int, Int], mark: Int): Unit = {
    if (table.size > mark) {
      table.keys.drop(mark).toSeq.foreach(table.remove)
    }
  }

  /**
   * The recursive node compiler. `None` anywhere fails the enclosing entry, whose caller rolls
   * the tables back to their pre-entry state. Shapes that
   * cannot be served stay unmatched by construction: an integer `Add` over a `datediff` result
   * is not a date expression (and ANSI overflow cannot throw row-accurately from a lane), and a
   * `date_add` over a `datediff` result only type-checks through a `Cast`, which compiles to
   * nothing here.
   */
  private def compileNode(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int]): Option[VarkaVectorIR] = expr match {
    case br: BoundReference if br.dataType == DateType =>
      Some(new ColumnRef(inputs.getOrElseUpdate(br.ordinal, inputs.size)))
    // A date literal's value is already an epoch-day int, so it takes a slot in the shared
    // per-distinct-value table like a folded day offset does (task 11) - what makes
    // `d < DATE'...'` and `greatest(d, DATE'...')` reachable at all.
    case Literal(days: Int, DateType) =>
      Some(new LiteralSlot(literals.getOrElseUpdate(days, literals.size)))
    case DateAdd(child, days) =>
      for {
        offset <- DateVarkaSupport.foldDaysOffset(days)
        node <- compileNode(child, inputs, literals)
      } yield new AddDays(node, new LiteralSlot(literals.getOrElseUpdate(offset, literals.size)))
    case DateSub(child, days) =>
      for {
        offset <- DateVarkaSupport.foldDaysOffset(days)
        node <- compileNode(child, inputs, literals)
      } yield new SubDays(node, new LiteralSlot(literals.getOrElseUpdate(offset, literals.size)))
    case DateDiff(end, start) =>
      for {
        endNode <- compileNode(end, inputs, literals)
        startNode <- compileNode(start, inputs, literals)
      } yield new IRDateDiff(endNode, startNode)
    case If(pred, thenValue, elseValue) =>
      for {
        cond <- compileCond(pred, inputs, literals)
        thenNode <- compileNode(thenValue, inputs, literals)
        elseNode <- compileNode(elseValue, inputs, literals)
      } yield new IfElse(cond, thenNode, elseNode)
    // CASE WHEN with an ELSE right-folds into nested IfElse - SQL's first-match semantics is
    // exactly nested if-else. Compilation runs in query order (branches left to right, then
    // the ELSE) so input ordinals and literal slots register deterministically in reading
    // order; only the fold is right-associative. With no ELSE the missing branch is a null
    // literal, which would break the dense body's all-valid invariant (task 11 plan, 2.1):
    // decline.
    case CaseWhen(branches, elseValue) =>
      elseValue.flatMap { elseExpr =>
        val compiledBranches = branches.map { case (pred, value) =>
          (compileCond(pred, inputs, literals), compileNode(value, inputs, literals))
        }
        val compiledElse = compileNode(elseExpr, inputs, literals)
        if (compiledBranches.forall(b => b._1.isDefined && b._2.isDefined)
            && compiledElse.isDefined) {
          Some(compiledBranches.foldRight(compiledElse.get) { case ((cond, value), rest) =>
            new IfElse(cond.get, value.get, rest)
          })
        } else {
          None
        }
      }
    // Spark's greatest/least are n-ary; the null-skipping algebra is associative, so a left
    // fold into the binary IR nodes is exact.
    case Greatest(children) =>
      foldPick(children, inputs, literals, new IRGreatest(_, _))
    case Least(children) =>
      foldPick(children, inputs, literals, new IRLeast(_, _))
    case DayOfWeek(child) =>
      compileNode(child, inputs, literals).map(new IRDayOfWeek(_))
    case WeekDay(child) =>
      compileNode(child, inputs, literals).map(new IRWeekDay(_))
    case _ => None
  }

  private def foldPick(
      children: Seq[Expression],
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      combine: (VarkaVectorIR, VarkaVectorIR) => VarkaVectorIR): Option[VarkaVectorIR] = {
    val compiled = children.map(compileNode(_, inputs, literals))
    if (compiled.nonEmpty && compiled.forall(_.isDefined)) {
      Some(compiled.flatten.reduceLeft(combine))
    } else {
      None
    }
  }

  /**
   * The condition compiler (task 11): interior comparisons and the connectives, three-valued
   * at run time via the emitter's known-true/known-false pairs. `EqualNullSafe` deliberately
   * declines - its both-null-is-true case breaks the null-intolerant comparison rule and earns
   * its own algebra entry or nothing (plan section 4).
   */
  private def compileCond(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int]): Option[Cond] = expr match {
    case LessThan(l, r) => compare(CompareOp.LT, l, r, inputs, literals)
    case LessThanOrEqual(l, r) => compare(CompareOp.LE, l, r, inputs, literals)
    case GreaterThan(l, r) => compare(CompareOp.GT, l, r, inputs, literals)
    case GreaterThanOrEqual(l, r) => compare(CompareOp.GE, l, r, inputs, literals)
    case EqualTo(l, r) => compare(CompareOp.EQ, l, r, inputs, literals)
    case And(l, r) =>
      for {
        left <- compileCond(l, inputs, literals)
        right <- compileCond(r, inputs, literals)
      } yield new IRAnd(left, right)
    case Or(l, r) =>
      for {
        left <- compileCond(l, inputs, literals)
        right <- compileCond(r, inputs, literals)
      } yield new IROr(left, right)
    case Not(child) => compileCond(child, inputs, literals).map(new IRNot(_))
    case _ => None
  }

  private def compare(
      op: CompareOp,
      l: Expression,
      r: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int]): Option[Cond] = {
    for {
      left <- compileNode(l, inputs, literals)
      right <- compileNode(r, inputs, literals)
    } yield new Compare(op, left, right)
  }
}
