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

import java.time.LocalDate

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.apache.spark.SparkIllegalArgumentException
import org.apache.spark.sql.catalyst.expressions.{Add, AddMonths, Alias, And, Attribute, BindReferences, BoundReference, CaseWhen, Cast, Coalesce, DateAdd, DateAddYMInterval, DateDiff, DateFromUnixDate, DateSub, DateVarkaSupport, DayOfMonth, DayOfWeek, DayOfYear, EqualTo, Expression, ExtractANSIIntervalDays, GreaterThan, GreaterThanOrEqual, Greatest, If, In, InSet, IsNotNull, IsNull, LastDay, Least, LessThan, LessThanOrEqual, Literal, MakeDate, Month, NamedExpression, NextDay, Not, Or, Quarter, RuntimeReplaceable, TruncDate, UnaryMinus, UnixDate, WeekDay, WeekOfYear, Year, YearOfWeek}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaDerivedKind, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays, AddMonths => IRAddMonths, And => IRAnd, ColumnRef, Compare, CompareOp, Cond, DateDiff => IRDateDiff, DayOfMonth => IRDayOfMonth, DayOfWeek => IRDayOfWeek, DayOfWeekIso, DayOfYear => IRDayOfYear, Greatest => IRGreatest, IfElse, IsNotNull => IRIsNotNull, LastDay => IRLastDay, Least => IRLeast, LiteralSlot, MakeDate => IRMakeDate, Month => IRMonth, NextDay => IRNextDay, Not => IRNot, Or => IROr, Quarter => IRQuarter, SubDays, ThursdayOf, TruncDate => IRTruncDate, TruncLevel, WeekDay => IRWeekDay, WeekOfYear => IRWeekOfYear, Year => IRYear}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{BooleanType, DataType, DateType, DayTimeIntervalType, IntegerType, StringType, YearMonthIntervalType}
import org.apache.spark.unsafe.types.UTF8String

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
    literals: Seq[Int],
    inputBounds: Seq[VarkaInputBound] = Nil,
    derivedInputs: Seq[VarkaDerivedInput] = Nil) {

  private lazy val derivedByInput: Map[Int, VarkaDerivedInput] =
    derivedInputs.map(d => d.inputIndex -> d).toMap

  /** The derived-input note for kernel input `inputIndex`, if the evaluator derives it. */
  def derivedAt(inputIndex: Int): Option[VarkaDerivedInput] = derivedByInput.get(inputIndex)
}

/**
 * A kernel input the evaluator derives per batch (task 59) rather than reads: kernel input
 * `inputIndex` (a position in `inputOrdinals`, whose entry there is `sourceOrdinal`) is the
 * int32 column `kind` computes from child column `sourceOrdinal` - the first kind maps
 * `next_day`'s weekday names to `dayOfWeek - 1` - before the kernel runs. Like a bound, a
 * property of the compiled plan and not of the emitted bytes: the kernel sees an int input.
 */
private[sql] case class VarkaDerivedInput(inputIndex: Int, sourceOrdinal: Int,
    kind: VarkaDerivedKind)

private[sql] object VarkaDerivedInput {
  private val kinds = VarkaDerivedKind.values().length

  /**
   * The key a derived input is interned under in the compiler's input table beside the child
   * ordinals: negative, so it can collide with no ordinal, and one per (column, kind), so two
   * `next_day` over the same weekday column share one leaf. The table's mark-and-truncate
   * discipline rolls it back with the plain columns when its entry declines.
   */
  def key(sourceOrdinal: Int, kind: VarkaDerivedKind): Int =
    -1 - (sourceOrdinal * kinds + kind.ordinal())

  /** Whether an input-table key names a derived input rather than a child ordinal. */
  def isKey(key: Int): Boolean = key < 0

  /** The child ordinal a derived key was made from. */
  def sourceOrdinal(key: Int): Int = (-1 - key) / kinds

  def kind(key: Int): VarkaDerivedKind = VarkaDerivedKind.values()((-1 - key) % kinds)

  /** `inputOrdinals` and `derivedInputs` from the accepted entries' input table. */
  def resolve(inputs: mutable.LinkedHashMap[Int, Int]): (Seq[Int], Seq[VarkaDerivedInput]) = {
    val keys = inputs.keys.toSeq
    val ordinals = keys.map(k => if (isKey(k)) sourceOrdinal(k) else k)
    val derived = keys.zipWithIndex.collect {
      case (k, i) if isKey(k) => VarkaDerivedInput(i, sourceOrdinal(k), kind(k))
    }
    (ordinals, derived)
  }
}

/**
 * A closed interval every live value of kernel input `inputIndex` (a position in
 * `inputOrdinals`) must lie in for the kernel's answer to be Spark's (task 56). The compiler
 * records one where it rewrote an expression whose row-engine form throws outside the bound -
 * the first is `CAST(i AS INTERVAL DAY)`, which overflows past
 * `VarkaChrono.INTERVAL_DAY_LIMIT_DAYS` days - and the evaluator checks it per batch before the
 * kernel runs, declining the batch to the row engine, which then raises the error, when a live
 * lane is outside. A bound is a property of the compiled plan, not of the emitted bytes: two
 * projections with the same IR and different bounds share a kernel class.
 */
private[sql] case class VarkaInputBound(inputIndex: Int, lo: Int, hi: Int)

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
 * Why one entry could not be fused (task 16): the answer to "why didn't my projection fuse?",
 * which the compiler's per-entry `None` used to swallow. `reason` is the vocabulary term - the
 * same string the exec nodes' verbose `EXPLAIN` and debug logs print - and `expr` names the
 * offending expression, the innermost one that actually failed rather than the whole entry.
 */
private[sql] case class VarkaDecline(reason: String, expr: String) {
  override def toString: String = s"$reason: $expr"
}

/**
 * Collects what one entry's compilation leaves behind besides its IR: its decline, and the
 * input bounds it asks the evaluator to check (task 56; keyed by child ordinal until the
 * entry is accepted, and dropped with a declining entry the way its columns and literals
 * are). The recursion reports a decline at the point of failure and the
 * first note wins, so the recorded reason is the innermost cause rather than the outermost
 * expression that inherited it; [[take]] hands it over and resets for the next entry.
 *
 * The recursion works on bound expressions, whose `BoundReference`s render as
 * `input[1, int, true]`; the child's attributes go back in before the text is kept, so a
 * reason reads in the query's own column names.
 */
private final class DeclineSink(childOutput: Seq[Attribute]) {
  private var first: Option[VarkaDecline] = None
  private val bounds = mutable.ArrayBuffer.empty[(Int, Int, Int)]

  /** Notes that child ordinal `ordinal` must lie in `[lo, hi]` for the entry being compiled. */
  def bound(ordinal: Int, lo: Int, hi: Int): Unit = bounds += ((ordinal, lo, hi))

  def boundsMark: Int = bounds.size

  /** Drops the bounds noted since `mark` - a declining entry's. */
  def truncateBounds(mark: Int): Unit = bounds.remove(mark, bounds.size - mark)

  /** The noted bounds in kernel-input terms, given the accepted entries' input table. */
  def inputBounds(inputs: mutable.LinkedHashMap[Int, Int]): Seq[VarkaInputBound] =
    bounds.toSeq.collect {
      case (ordinal, lo, hi) if inputs.contains(ordinal) =>
        VarkaInputBound(inputs(ordinal), lo, hi)
    }.distinct

  def note(reason: String, expr: Expression): Unit = {
    if (first.isEmpty) {
      val named = expr.transformUp {
        case br: BoundReference if br.ordinal >= 0 && br.ordinal < childOutput.length =>
          childOutput(br.ordinal)
      }
      val text = named.sql
      val shown = if (text.length > 80) text.take(77) + "..." else text
      first = Some(VarkaDecline(reason, shown))
    }
  }

  def take(): Option[VarkaDecline] = {
    val taken = first
    first = None
    taken
  }
}

/**
 * A projection classified entry by entry (task 12): `specs` has one entry per projectList
 * position, in order, and `fused` is the sub-projection of just the [[FusedOutput]] entries -
 * their kernel-input and literal tables cover only what the fused trees reference, so a
 * residual entry constrains neither the emitted loop nor `canRun`'s Arrow check.
 *
 * `declines` (task 16) maps the position of each [[ResidualOutput]] entry to why it declined,
 * for the exec nodes' verbose `EXPLAIN`; it is diagnostics only and no execution path reads it.
 */
private[sql] case class PartialVarkaProjection(
    specs: Seq[VarkaOutputSpec],
    fused: CompiledVarkaProjection,
    declines: Map[Int, VarkaDecline] = Map.empty)

/**
 * One conjunct of a filter predicate under the task-21 split: the original (unbound)
 * expression, whether it joined the mask kernel, and - for a residual conjunct - why not.
 * The predicate counterpart of [[VarkaOutputSpec]] plus its decline entry.
 */
private[sql] case class VarkaConjunctSpec(
    conjunct: Expression,
    fused: Boolean,
    decline: Option[VarkaDecline])

/**
 * A filter predicate compiled conjunct by conjunct (task 21): `specs` classifies every
 * conjunct of the condition's `AND` spine in query order, and `fused` describes the mask
 * kernel - its single output is the fused conjuncts recombined into one condition root, and
 * its `outputTypes` entry is `BooleanType` as a description only, since a selection bitmap
 * never allocates an output vector. The split mirrors [[PartialVarkaProjection]]'s per-entry
 * eligibility: a mixed `WHERE` fuses what it can, and the rule keeps the residual conjuncts
 * in a row `FilterExec` above the Varka node.
 */
private[sql] case class CompiledVarkaPredicate(
    specs: Seq[VarkaConjunctSpec],
    fused: CompiledVarkaProjection) {

  /** The conjuncts the mask kernel serves, in query order, unbound. */
  def fusedConjuncts: Seq[Expression] = specs.filter(_.fused).map(_.conjunct)

  /** The conjuncts left to a row filter above, in query order, unbound. */
  def residualConjuncts: Seq[Expression] = specs.filterNot(_.fused).map(_.conjunct)
}

/**
 * Compiles a bound projection list to the Varka vector IR, recursing where the MVP's
 * flat matcher demanded bare attributes - `datediff(date_add(d, 7), d2)` compiles where
 * milestone 1 saw nothing, and since task 11 so do `CASE WHEN`/`IF` (via interior comparisons
 * and the three-valued connectives), `greatest`/`least`, `dayofweek`/`weekday` and date
 * literals. Task 20 widened the conditions with `IN` over date literals (capped, see
 * [[MaxInLiterals]]) and the validity predicates `IS [NOT] NULL` over bare columns, and the
 * values with `coalesce`/`nvl`/`nvl2` (lowered onto the validity condition) and the identity
 * date cast. Used by both `VarkaColumnarRule` (is the projection eligible?) and
 * `VarkaKernelEvaluator` (what does the emitted loop compute?), so eligibility cannot drift from
 * execution: there is one compiler and the rule's question is `compilePartial(...).isDefined`.
 *
 * Since task 12 eligibility is per entry, not all or nothing: [[compilePartial]] classifies
 * every entry as fused, forwarded (a bare column of any type, zero-copy) or residual (per-row),
 * and the projection is eligible when at least one entry fuses - a projection of forwards and
 * residuals alone gains nothing from Varka and stays on Janino untouched. [[compile]] remains
 * as the all-entries-fused special case for callers that need exactly that.
 *
 * Task 21 adds the third entry point, [[compilePredicate]]: a filter condition compiled to a
 * single condition root - the selection mask the emitter writes as a bitmap - with the same
 * per-part eligibility, split on the predicate's `AND` spine instead of projection entries.
 *
 * Literal day offsets fold through [[DateVarkaSupport.foldDaysOffset]] - the same rule the MVP
 * matched on - into slots of the runtime argument table, assigned per distinct '''value''': two
 * occurrences of `date_add(d, 1)` must compile to equal IR records, or the emitter's CSE could
 * not see they are one computation. Slots are numbered in first-occurrence order, so a chain's
 * shape does not depend on what its constants are - the identity milestone 3's cache will key
 * on.
 *
 * This is the only eligibility rule there is. Milestone 1's parallel one - the expressions'
 * `isClassFileGenEligible` and its genCode-time registration, deliberately left alone while two
 * generations of codegen coexisted - retired with the dispatcher layer in task 17.
 */
private[sql] object VarkaExpressionCompiler {

  /**
   * The most literals an `IN` list may hold and still fuse (task 20), counted after dedup.
   * The basis, recorded in `PLAN_TASK_20.md`: 16 is depth-safe under any fold shape
   * (`MAX_CHAIN_DEPTH` = 16 while the balanced chain here is `ceil(log2 16) + 1` = 5
   * levels), and its 31 op nodes leave half the emitter's `MAX_FUSED_NODES` = 64 budget to
   * the rest of the projection. (The emitter's broadcast hoist is NOT part of the basis:
   * its gate counts the kernel's total literal slots, so a capped IN plus any other
   * literal already re-broadcasts inline - the review pass corrected an earlier claim
   * here.) Above the cap the entry declines with a reason instead of silently losing the
   * whole kernel at emission.
   */
  private[codegen] val MaxInLiterals = 16

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
    val outputs = mutable.ArrayBuffer.empty[VarkaVectorIR]
    val outputTypes = Seq.newBuilder[DataType]
    val sink = new DeclineSink(childOutput)
    val declines = Map.newBuilder[Int, VarkaDecline]
    var fusedCount = 0
    val specs = projectList.zipWithIndex.map { case (named, position) =>
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
          val boundsMark = sink.boundsMark
          compileNode(e, inputs, literals, sink) match {
            // Task 20: an accepted entry must also fit the emitter's structural budgets
            // together with the entries accepted before it. The emitter enforces the same
            // limits, but at emission time, where a breach can only become a silent
            // per-batch fallback - no decline reason, and EXPLAIN still claims fusion. So
            // the compiler mirrors them and demotes the overflowing entry to residual.
            case Some(ir) if VarkaLoopEmitter.fitsBudgets((outputs :+ ir).asJava, inputs.size) =>
              sink.take()
              outputs += ir
              outputTypes += e.dataType
              fusedCount += 1
              FusedOutput(fusedCount - 1)
            case compiled =>
              truncate(inputs, inputsMark)
              truncate(literals, literalsMark)
              sink.truncateBounds(boundsMark)
              if (compiled.isDefined) {
                sink.take() // an over-budget entry compiled clean; its reason is the budget
                sink.note("exceeds the emitter's fused budget", e)
              }
              // A declining entry always leaves a reason: every `None` below notes one.
              sink.take().foreach(decline => declines += position -> decline)
              ResidualOutput
          }
      }
    }
    if (fusedCount > 0 && inputs.nonEmpty) {
      val (ordinals, derived) = VarkaDerivedInput.resolve(inputs)
      Some(PartialVarkaProjection(specs, CompiledVarkaProjection(
        outputs.toSeq, outputTypes.result(), ordinals, literals.keys.toSeq,
        sink.inputBounds(inputs), derived),
        declines.result()))
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
   * Compiles a filter predicate conjunct by conjunct (task 21). The condition splits on its
   * `AND` spine - Kleene AND is associative, so the split changes nothing - and each conjunct
   * either joins the fused mask kernel or stays behind as a residual, mirroring
   * [[compilePartial]]'s per-entry eligibility including the table rollback: a declining
   * conjunct must not widen the kernel's input set or `canRun`'s Arrow check. An accepted
   * conjunct must also keep the '''recombined''' root within the emitter's budgets - the
   * AND fold adds a node per accepted conjunct, so the budgets are mirrored against the fold,
   * not the conjunct alone. `Some` exactly when at least one conjunct fused and the fused
   * tree reads at least one column; the caller keeps `residualConjuncts` in a row filter
   * above.
   *
   * The null rule needs no glue here: at the mask root unknown is false (see the IR's `Cond`
   * doc), and AND-splitting preserves it - a row where any conjunct is null or false has the
   * whole conjunction null or false, and both read as unselected.
   */
  def compilePredicate(
      condition: Expression,
      childOutput: Seq[Attribute]): Option[CompiledVarkaPredicate] = {
    // The split hoists fused conjuncts below the residual ones, which reorders evaluation.
    // That is sound only when every conjunct is deterministic - Spark's own predicate
    // pushdown stops at the first nondeterministic conjunct (span(_.deterministic)) for the
    // same reason: a seeded rand() must see every row, not the survivors of a hoisted
    // predicate. One nondeterministic conjunct therefore declines the whole predicate
    // (task-21 review); the rewrite must never change what the query computes.
    if (!condition.deterministic) return None
    val inputs = mutable.LinkedHashMap.empty[Int, Int]
    val literals = mutable.LinkedHashMap.empty[Int, Int]
    val sink = new DeclineSink(childOutput)
    val fusedConds = mutable.ArrayBuffer.empty[Cond]
    val specs = splitConjuncts(condition).map { conjunct =>
      val bound = BindReferences.bindReference[Expression](conjunct, childOutput)
      val inputsMark = inputs.size
      val literalsMark = literals.size
      val boundsMark = sink.boundsMark
      compileCond(bound, inputs, literals, sink) match {
        case Some(cond) if VarkaLoopEmitter.fitsBudgets(
            java.util.List.of(andFold(fusedConds.toSeq :+ cond)), inputs.size) =>
          sink.take()
          fusedConds += cond
          VarkaConjunctSpec(conjunct, fused = true, decline = None)
        case compiled =>
          truncate(inputs, inputsMark)
          truncate(literals, literalsMark)
          sink.truncateBounds(boundsMark)
          if (compiled.isDefined) {
            sink.take()
            sink.note("exceeds the emitter's fused budget", bound)
          }
          // A declining conjunct always leaves a reason: every `None` in compileCond notes one.
          VarkaConjunctSpec(conjunct, fused = false, decline = sink.take())
      }
    }
    if (fusedConds.nonEmpty && inputs.nonEmpty) {
      val (ordinals, derived) = VarkaDerivedInput.resolve(inputs)
      Some(CompiledVarkaPredicate(specs,
        CompiledVarkaProjection(Seq(andFold(fusedConds.toSeq)), Seq(BooleanType),
          ordinals, literals.keys.toSeq, sink.inputBounds(inputs), derived)))
    } else {
      None
    }
  }

  /** The `AND` spine of a condition, in query order - the split [[compilePredicate]] works. */
  private def splitConjuncts(condition: Expression): Seq[Expression] = condition match {
    case And(left, right) => splitConjuncts(left) ++ splitConjuncts(right)
    case other => Seq(other)
  }

  /**
   * Folds the fused conjuncts back into one root, '''balanced''' like [[balancedOr]] and for
   * the same reason: Kleene AND is associative, so the shape is a canonicalization, and a
   * left fold would grow the chain depth by one per conjunct - a WHERE of 16 fusible
   * conjuncts would trip `MAX_CHAIN_DEPTH` for no semantic reason, where the balanced fold
   * stays logarithmic.
   */
  private def andFold(conds: Seq[Cond]): Cond = balancedFold(conds, new IRAnd(_, _))

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
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = expr match {
    case br: BoundReference if br.dataType == DateType =>
      Some(columnRef(br, inputs))
    // A date literal's value is already an epoch-day int, so it takes a slot in the shared
    // per-distinct-value table like a folded day offset does (task 11) - what makes
    // `d < DATE'...'` and `greatest(d, DATE'...')` reachable at all. `days: Int` does not
    // match a null-valued Literal, which falls through to the catch-all below; that is a
    // safe blind spot, not a bug, since ConstantFolding removes a null date literal from any
    // real query before it can reach here (unix_date/date_from_unix_date add two more
    // recursive paths into this same match, both equally covered by that guarantee).
    case Literal(days: Int, DateType) =>
      Some(new LiteralSlot(literals.getOrElseUpdate(days, literals.size)))
    // The identity cast (task 20): the corpus wraps date expressions in `CAST(... AS DATE)`
    // 85 times, and after optimization the wrapper is a no-op over an already-date child -
    // unwrap it. A `cast(<string literal> AS DATE)` never reaches here (constant-folded to a
    // date literal by the optimizer); a string *column* cast is a per-row parse with no
    // string lane and stays declined below.
    case c: Cast if c.dataType == DateType && c.child.dataType == DateType =>
      compileNode(c.child, inputs, literals, sink)
    // unix_date/date_from_unix_date (task 41) are Spark's own `input.asInstanceOf[Int]` in
    // full - a date IS a day count, so both are a pure type relabel with nothing to compute.
    // Unwrapping to the child rather than adding an IR node means `SELECT unix_date(d)` and
    // `SELECT d` compile to the same IR and share a shape hash - correct, since kernel
    // identity is about lane math and theirs is identical; the entry's output type still
    // comes from the Catalyst expression, not the IR. `date_from_unix_date`'s child is an
    // integer column, unreadable until task 38 opens that leaf - until then this arm simply
    // declines through the existing non-date-column path below.
    case UnixDate(child) =>
      compileNode(child, inputs, literals, sink)
    case DateFromUnixDate(child) =>
      compileNode(child, inputs, literals, sink)
    case DateAdd(child, days) =>
      // The date child compiles before the offset, matching CaseWhen's rule a few cases below:
      // ordinals and literal slots register in reading order. Before task 38 the offset was
      // always a foldable literal (no ordinal to register), so this ordering is new in an
      // observable way now that an offset can be a column: when BOTH operands are unfusable,
      // DeclineSink's "first note wins" rule reports the child's reason, not the offset's
      // (pinned by VarkaExpressionCompilerSuite's "with two independently unfusable operands,
      // the child's reason is reported" test).
      days match {
        // `date - INTERVAL n DAY` (task 56): the analyzer spells it as an add of the negated
        // day count, `DateAdd(d, UnaryMinus(ExtractANSIIntervalDays(r)))`. Inside the cast's
        // own bound the negation cannot overflow, so it is absorbed into SubDays - no
        // UnaryMinus node exists and none is needed.
        case UnaryMinus(DayIntervalOffset(br), _) =>
          for {
            node <- compileNode(child, inputs, literals, sink)
            offsetNode <- compileOffset(DayIntervalOffset.wrap(br), inputs, literals, sink)
          } yield new SubDays(node, offsetNode)
        case _ =>
          for {
            node <- compileNode(child, inputs, literals, sink)
            offsetNode <- compileOffset(days, inputs, literals, sink)
          } yield new AddDays(node, offsetNode)
      }
    case DateSub(child, days) =>
      for {
        node <- compileNode(child, inputs, literals, sink)
        offsetNode <- compileOffset(days, inputs, literals, sink)
      } yield new SubDays(node, offsetNode)
    case DateDiff(end, start) =>
      for {
        endNode <- compileNode(end, inputs, literals, sink)
        startNode <- compileNode(start, inputs, literals, sink)
      } yield new IRDateDiff(endNode, startNode)
    case If(pred, thenValue, elseValue) =>
      for {
        cond <- compileCond(pred, inputs, literals, sink)
        thenNode <- compileNode(thenValue, inputs, literals, sink)
        elseNode <- compileNode(elseValue, inputs, literals, sink)
      } yield new IfElse(cond, thenNode, elseNode)
    // With no ELSE the missing branch is a null literal, which would break the dense body's
    // all-valid invariant (task 11 plan, 2.1): decline.
    case c @ CaseWhen(_, None) =>
      sink.note("CASE WHEN without an ELSE branch", c)
      None
    // CASE WHEN with an ELSE right-folds into nested IfElse - SQL's first-match semantics is
    // exactly nested if-else. Compilation runs in query order (branches left to right, then
    // the ELSE) so input ordinals and literal slots register deterministically in reading
    // order; only the fold is right-associative.
    case CaseWhen(branches, elseValue) =>
      elseValue.flatMap { elseExpr =>
        val compiledBranches = branches.map { case (pred, value) =>
          (compileCond(pred, inputs, literals, sink),
            compileNode(value, inputs, literals, sink))
        }
        val compiledElse = compileNode(elseExpr, inputs, literals, sink)
        if (compiledBranches.forall(b => b._1.isDefined && b._2.isDefined)
            && compiledElse.isDefined) {
          Some(compiledBranches.foldRight(compiledElse.get) { case ((cond, value), rest) =>
            new IfElse(cond.get, value.get, rest)
          })
        } else {
          None
        }
      }
    // Coalesce (task 20) right-folds onto the validity condition: `coalesce(a, b)` is
    // `IfElse(IsNotNull(a), a, b)`, whose masked validity - (kT & valid(a)) | (~kT & valid(b))
    // with kT = valid(a) - reduces to valid(a) | valid(b), exactly SQL's coalesce. Every
    // operand before the last must be a bare date column (the IsNotNull child restriction);
    // `nvl`/`ifnull` arrive here already rewritten to Coalesce by the optimizer, and `nvl2`
    // arrives as `If(IsNotNull(...), ...)` and rides the same condition node.
    case Coalesce(children) if children.nonEmpty =>
      compileCoalesce(children, inputs, literals, sink)
    // Spark's greatest/least are n-ary; the null-skipping algebra is associative, so a left
    // fold into the binary IR nodes is exact.
    case Greatest(children) =>
      foldPick(children, inputs, literals, sink, new IRGreatest(_, _))
    case Least(children) =>
      foldPick(children, inputs, literals, sink, new IRLeast(_, _))
    case DayOfWeek(child) =>
      compileNode(child, inputs, literals, sink).map(new IRDayOfWeek(_))
    case WeekDay(child) =>
      compileNode(child, inputs, literals, sink).map(new IRWeekDay(_))
    // extract(DAYOFWEEK_ISO) / date_part('DOW_ISO') (task 57): the analyzer spells them
    // Add(WeekDay(d), 1), and so does a hand-written weekday(d) + 1. One narrow arm, either
    // operand order, and nothing else: integer arithmetic over an output is task 30's.
    case Add(WeekDay(child), Literal(1, IntegerType), _) =>
      compileNode(child, inputs, literals, sink).map(new DayOfWeekIso(_))
    case Add(Literal(1, IntegerType), WeekDay(child), _) =>
      compileNode(child, inputs, literals, sink).map(new DayOfWeekIso(_))
    // next_day (task 33): a foldable weekday is resolved at compile time and travels as a
    // runtime literal. An unrecognized or null one declines rather than throws - it is the
    // row engine's business, and it has two different behaviours for it depending on ANSI
    // mode which Varka must not try to reproduce. Evaluating a foldable-but-computed weekday
    // expression (not only a bare Literal) can itself throw for reasons unrelated to the
    // weekday name - that must decline too, per the ghost-fallback contract, rather than
    // crash planning.
    case NextDay(start, dow, _) if dow.foldable =>
      for {
        k <- foldWeekday(dow, sink)
        d <- compileNode(start, inputs, literals, sink)
      } yield new IRNextDay(d, new LiteralSlot(literals.getOrElseUpdate(k, literals.size)))
    // A weekday column (task 59): the kernel reads an int32 column the evaluator derives
    // from the names, per batch, by the row engine's own parser (WeekdayLeaf), so the node
    // is the same and only the offset's origin differs. ANSI mode is part of the derived
    // input's kind, since NextDay fixes failOnError at construction. Any collation is
    // admitted because the parser ignores it. An expression over the column stays the row
    // engine's: the leaf reads a stored column.
    case NextDay(start, br: BoundReference, failOnError) if br.dataType.isInstanceOf[StringType] =>
      val kind = if (failOnError) VarkaDerivedKind.WEEKDAY_ANSI else VarkaDerivedKind.WEEKDAY
      compileNode(start, inputs, literals, sink).map(new IRNextDay(_, derivedRef(br, kind, inputs)))
    case n: NextDay =>
      sink.note("next_day with a weekday that is neither a literal nor a column", n)
      None
    // The calendar extractions (task 26). One civil-from-days decomposition per node, so two
    // fields of the same date are computed twice - see VarkaVectorIR.Year for why. The child
    // goes through `calendarInput` (task 52): the decomposition is exact only over
    // VarkaChrono's narrowed range, and the compiler is where a shift that can leave it is
    // known before anything runs.
    case Year(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRYear(_))
    case Month(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRMonth(_))
    case DayOfMonth(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRDayOfMonth(_))
    case Quarter(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRQuarter(_))
    case DayOfYear(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRDayOfYear(_))
    // make_date(y, m, d) (task 42): three int operands, each a column or a literal, and the
    // evaluation mode captured on the expression - two modes are two shapes.
    case MakeDate(y, m, d, failOnError) =>
      for {
        yy <- compileIntOperand(y, "make_date's year", inputs, literals, sink)
        mm <- compileIntOperand(m, "make_date's month", inputs, literals, sink)
        dd <- compileIntOperand(d, "make_date's day", inputs, literals, sink)
      } yield new IRMakeDate(yy, mm, dd, failOnError)
    case LastDay(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRLastDay(_))
    // weekofyear, extract(WEEK) and date_part (task 37): the ISO week by the Thursday rule - the
    // week tail over the Thursday of the day's week, two nodes so the prefix runs over the
    // shifted day and so extract(YEAROFWEEK) (task 58) is Year over the same ThursdayOf. The
    // calendar node's child is the shift, so the range analysis admits the shift, not the day.
    case WeekOfYear(child) =>
      compileNode(child, inputs, literals, sink)
        .flatMap(c => admitCalendar(new ThursdayOf(c), expr, literals, sink))
        .map(new IRWeekOfYear(_))
    // extract(YEAROFWEEK) / date_part('YEAROFWEEK') (task 58): the ISO week-based year is the
    // calendar year of the same Thursday, so Year over the same shift - one prefix for both
    // fields under CSE, and nothing in the emitter.
    case YearOfWeek(child) =>
      compileNode(child, inputs, literals, sink)
        .flatMap(c => admitCalendar(new ThursdayOf(c), expr, literals, sink))
        .map(new IRYear(_))
    // trunc(date, fmt) (task 35): the format resolves at compile time, like next_day's weekday,
    // because the level chooses which code is emitted. YEAR, MONTH and QUARTER are one node
    // with the level as a shape-bearing field; WEEK is Spark's own definition,
    // next_day(d - 7, 'MONDAY'), rewritten onto the nodes task 33 already has - the unix_date
    // pattern of retiring an expression onto existing IR. Everything else declines, each for
    // its own reason: the row engine answers those with a NULL column, which no IR node can
    // produce.
    case TruncDate(date, format) if format.foldable =>
      foldTruncLevel(format, sink).flatMap {
        case ToLevel(level) =>
          calendarInput(date, expr, inputs, literals, sink).map(new IRTruncDate(_, level))
        case ToWeek =>
          compileNode(date, inputs, literals, sink).map { d =>
            val week = new LiteralSlot(literals.getOrElseUpdate(7, literals.size))
            // next_day's slot holds dayOfWeek - 1 (task 33); Monday through the same parser
            // foldWeekday uses, so the constant is the definition's, not a retyped 3.
            val monday = new LiteralSlot(literals.getOrElseUpdate(
              DateTimeUtils.getDayOfWeekFromString(UTF8String.fromString("MONDAY")) - 1,
              literals.size))
            new IRNextDay(new SubDays(d, week), monday)
          }
      }
    case t: TruncDate =>
      sink.note("trunc with a non-foldable format", t)
      None
    // Month arithmetic (task 40): add_months(d, n) and d +- INTERVAL n MONTH/YEAR are the same
    // node - AddMonthsBase's two subclasses differ only in where the month count comes from,
    // both physically an Int. `d - INTERVAL n MONTH` arrives as DatetimeSub, already replaced
    // by its DateAddYMInterval(l, UnaryMinus(r)) by the time a real query reaches here.
    // The date child compiles before the month count, matching DateAdd's rule above: ordinals
    // register in reading order, so add_months(d, m) puts d at ordinal 0 and m at ordinal 1 -
    // and when both decline, DeclineSink's "first note wins" rule reports the date's reason.
    case AddMonths(startDate, numMonths) =>
      for {
        node <- calendarInput(startDate, expr, inputs, literals, sink)
        months <- compileMonths(numMonths, inputs, literals, sink)
      } yield new IRAddMonths(node, months)
    case DateAddYMInterval(date, interval) =>
      for {
        node <- calendarInput(date, expr, inputs, literals, sink)
        months <- compileMonths(interval, inputs, literals, sink)
      } yield new IRAddMonths(node, months)
    // A column of any other type: eligible to be forwarded as a whole entry, never to be read
    // by the int32 lanes of a kernel.
    case br: BoundReference =>
      sink.note(s"non-date column of type ${br.dataType.simpleString}", br)
      None
    // Defensive: a real query never carries an unreplaced RuntimeReplaceable this far (the
    // optimizer's ReplaceExpressions runs long before physical planning), but hand-built
    // expressions in tests and the plan-side fusion report can - compile what would run.
    case r: RuntimeReplaceable =>
      compileNode(r.replacement, inputs, literals, sink)
    case other =>
      sink.note("unsupported expression", other)
      None
  }

  /**
   * The Coalesce right-fold (task 20). Every operand except the last compiles and must be a
   * bare date column: `IsNotNull` reads the per-input validity word, which only a column has
   * before value emission (the recorded milestone-3 restriction) - a computed operand
   * declines with its own reason. The `ColumnRef` match below is a proxy for "this operand
   * is a bare column" that is exact only because every `compileNode` arm producing a
   * `ColumnRef` today is either an actual column read or a null-intolerant identity relabel
   * (`unix_date`/`date_from_unix_date`, task 41, and the identity date `Cast`) - a future
   * relabel that changes nullability or value would silently break this guard.
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

  /**
   * Interns `br`'s ordinal into `inputs` and wraps it as a `ColumnRef` - shared by
   * `compileNode`'s `DateType` leaf and `compileOffset`'s `IntegerType` one, the two column
   * kinds the compiler admits.
   */
  private def columnRef(br: BoundReference, inputs: mutable.LinkedHashMap[Int, Int]): ColumnRef =
    new ColumnRef(inputs.getOrElseUpdate(br.ordinal, inputs.size))

  /**
   * `columnRef`'s twin for an input the evaluator derives from `br` (task 59): interned under
   * `VarkaDerivedInput.key` beside the child ordinals, so it takes the next kernel input index
   * and shares the table's rollback.
   */
  private def derivedRef(br: BoundReference, kind: VarkaDerivedKind,
      inputs: mutable.LinkedHashMap[Int, Int]): ColumnRef =
    new ColumnRef(inputs.getOrElseUpdate(VarkaDerivedInput.key(br.ordinal, kind), inputs.size))

  /**
   * The day offset of a `date_add`/`date_sub`: a folded literal keeps today's `LiteralSlot`
   * shape (existing plans and their cached kernels are untouched), and a non-foldable offset
   * (task 38) is a bare `IntegerType` column - deliberately not a general `compileNode`
   * recursion. `compileNode`'s `BoundReference` leaf stays `DateType`-only: widening it instead
   * of this dedicated path would let an int column reach every other position that calls
   * `compileNode` too (`Compare`, `DateDiff`, `Coalesce`, `Greatest`...), fusing plain
   * integer-vs-integer predicates that were never part of this task's scope (task 38 section 6:
   * "do not open it wider").
   */
  /**
   * An int operand of a node that is not a day: a foldable int literal as a slot, a bare
   * `IntegerType` column as a column ref, anything else declined with `position` in the
   * reason. `compileOffset`'s shape without its interval cases, and the helper task 63 widens
   * when integer arithmetic joins; `compileNode`'s column leaf stays `DateType`-only.
   */
  private def compileIntOperand(
      e: Expression,
      position: String,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = e match {
    case Literal(v: Int, IntegerType) =>
      Some(new LiteralSlot(literals.getOrElseUpdate(v, literals.size)))
    case br: BoundReference if br.dataType == IntegerType => Some(columnRef(br, inputs))
    case other =>
      sink.note(s"$position is not an int column or literal", other)
      None
  }

  private def compileOffset(
      days: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    DateVarkaSupport.foldDaysOffset(days) match {
      case Some(offset) =>
        Some(new LiteralSlot(literals.getOrElseUpdate(offset, literals.size)))
      case None =>
        days match {
          case br: BoundReference if br.dataType == IntegerType =>
            Some(columnRef(br, inputs))
          case br: BoundReference =>
            sink.note(s"non-integer day offset column of type ${br.dataType.simpleString}", br)
            None
          // A day interval built from an int column (task 56): `CAST(i AS INTERVAL DAY)`. The
          // cast multiplies by a day's micros and the extractor divides them back out, so the
          // day count is `i` itself - wherever the cast does not throw. Past
          // INTERVAL_DAY_LIMIT_DAYS it throws in every mode, where a kernel would wrap, so the
          // column carries a bound the evaluator checks per batch and declines the batch to
          // the row engine when a live lane is outside.
          case DayIntervalOffset(br) =>
            sink.bound(br.ordinal, -VarkaChrono.INTERVAL_DAY_LIMIT_DAYS,
              VarkaChrono.INTERVAL_DAY_LIMIT_DAYS)
            Some(columnRef(br, inputs))
          case e: ExtractANSIIntervalDays =>
            // A stored INTERVAL DAY column is int64 microseconds, which no int32 lane can
            // read; the owner scoped task 56 to the int-cast form.
            sink.note("day interval is not an int column cast to days", e)
            None
          case other =>
            sink.note("day offset is not a foldable literal or an integer column", other)
            None
        }
    }
  }

  /**
   * "An int column, as a day interval", the one spelling of it that stays a date-lane
   * expression (task 56): `ExtractANSIIntervalDays` over `CAST(i AS INTERVAL DAY)`, which is
   * exactly `i` inside the cast's bound. `i * INTERVAL '1' DAY` is not a second spelling: a
   * multiplied interval widens to DAY TO SECOND, so the analyzer casts the date to a timestamp
   * and the expression leaves the date lane (`TimestampAddInterval`, milestone 5). `wrap`
   * rebuilds the cast form so the `DateAdd` arm can hand the negated case back to
   * `compileOffset` and share its bound and reason.
   */
  private object DayIntervalOffset {
    def unapply(e: Expression): Option[BoundReference] = e match {
      case ExtractANSIIntervalDays(
          Cast(br: BoundReference, DayTimeIntervalType(DayTimeIntervalType.DAY,
            DayTimeIntervalType.DAY), _, _)) if br.dataType == IntegerType => Some(br)
      case _ => None
    }
    def wrap(br: BoundReference): Expression =
      ExtractANSIIntervalDays(Cast(br, DayTimeIntervalType(DayTimeIntervalType.DAY)))
  }

  /**
   * "An int column, as a year-month interval", `MonthIntervalOffset`'s twin for months: `CAST(i
   * AS INTERVAL MONTH)` reaches the compiler as the cast itself, with no extraction wrapper -
   * unlike `DayIntervalOffset`, whose micros-typed cast needs `ExtractANSIIntervalDays` to read
   * back out - because `Cast.intToYearMonthInterval` returns `v` unchanged for an end field of
   * `MONTH` (checked in `PLAN_TASK_60.md` section 2), so the cast node's own evaluated value
   * already is the month count. `i * INTERVAL '1' MONTH` is not a second spelling, for the same
   * reason `DayIntervalOffset`'s doc gives for days: a multiplied interval leaves the date lane.
   */
  private object MonthIntervalOffset {
    def unapply(e: Expression): Option[BoundReference] = e match {
      case Cast(br: BoundReference, YearMonthIntervalType(YearMonthIntervalType.MONTH,
          YearMonthIntervalType.MONTH), _, _) if br.dataType == IntegerType => Some(br)
      case _ => None
    }
  }

  /**
   * The month count of `add_months`/`date +- INTERVAL n MONTH/YEAR` (task 40, widened by task
   * 60): a foldable count folds to a bounded `LiteralSlot`, the same two reasons as before -
   * not foldable, or foldable but outside `VarkaChrono`'s `MONTH_ARITH_MIN/MAX_MONTHS`, the
   * range the emitter's `/ 12` magic multiply covers (`PLAN_TASK_40.md` section 2.2). A
   * non-foldable count is a `ColumnRef` when it is a bare `IntegerType` column (`add_months(d,
   * m)`) or the `MONTH`-end interval cast above (`d + CAST(m AS INTERVAL MONTH)`) - the emitter
   * bounds it lanewise at run time instead (task 60's guard on `AddMonths` itself, since the
   * exactness domain is the count's alone, `PLAN_TASK_60.md` section 2). A `YearMonthIntervalType`
   * column with no such cast declines by name: the Arrow cache holds it as an
   * `IntervalYearVector`, which `isArrowBacked` does not read, so admitting it would fuse at
   * plan time and then refuse every batch. `d - INTERVAL m MONTH` arrives as `UnaryMinus` over
   * the cast and is not matched here; it declines until task 63's negate composes with it.
   */
  private def compileMonths(
      months: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    DateVarkaSupport.foldDaysOffset(months) match {
      case Some(m) if m < VarkaChrono.MONTH_ARITH_MIN_MONTHS
          || m > VarkaChrono.MONTH_ARITH_MAX_MONTHS =>
        sink.note("month count outside the range the emitter's magic multiply covers", months)
        None
      case Some(m) =>
        Some(new LiteralSlot(literals.getOrElseUpdate(m, literals.size)))
      case None =>
        months match {
          case br: BoundReference if br.dataType == IntegerType =>
            Some(columnRef(br, inputs))
          case MonthIntervalOffset(br) =>
            Some(columnRef(br, inputs))
          // The YEAR-end cast multiplies by 12 and can throw (Cast.intToYearMonthInterval),
          // unlike the MONTH-end cast MonthIntervalOffset matches, which is exact - so it is
          // not admitted as the column itself and gets its own reason rather than falling into
          // the generic "neither literal nor column" catch-all below.
          case Cast(br: BoundReference, YearMonthIntervalType(YearMonthIntervalType.YEAR,
              YearMonthIntervalType.YEAR), _, _) if br.dataType == IntegerType =>
            sink.note("non-integer month count column of type interval year", months)
            None
          case br: BoundReference if br.dataType.isInstanceOf[YearMonthIntervalType] =>
            sink.note("year-month interval column is not readable by the int32 lanes", br)
            None
          case br: BoundReference =>
            sink.note(s"non-integer month count column of type ${br.dataType.simpleString}", br)
            None
          case other =>
            sink.note("month count is neither a foldable literal nor an integer column", other)
            None
        }
    }
  }


  /**
   * How far the IR under a calendar node can move a day (task 52). `Bounded` is an interval of
   * epoch days the value is proven to lie in - which a column month count (task 60) still
   * yields, since the emitter's own runtime guard on that count bounds how far it can shift the
   * day; `ColumnShifted` means a `date_add`/`date_sub` with a column *day* offset sits in the
   * subtree instead, so the interval is unknowable here and the emitter guards that producer at
   * run time; `Unknown` means a node this analysis does not know, which `calendarInput`
   * declines rather than trusts.
   */
  private sealed trait DayRange
  private case class Bounded(lo: Long, hi: Long) extends DayRange
  private case object ColumnShifted extends DayRange
  private case object Unknown extends DayRange

  /**
   * The compile-time half of the calendar range guard (task 52, `PLAN_TASK_52.md` 3.1 and
   * 10.3). Task 26's decomposition is exact only over `VarkaChrono.NARROW_MIN_DAYS ..
   * NARROW_MAX_DAYS`, and task 51 removed the per-lane check every calendar node used to run,
   * on the argument that the range is decidable once, here, for everything except a column
   * offset. This is that decision, over the IR already built for the calendar node's child:
   *
   *  - a column holds `[CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]` by the project's contract, and
   *    a date literal is itself (the parser cannot write one outside the contract, but a
   *    hand-built `Literal` can, so it is read back rather than assumed);
   *  - a literal day offset shifts by exactly its value, `next_day` by 1 to 7, `add_months(n)`
   *    by 28n to 31n in whichever order, `last_day` by 0 to 30 - each an over-approximation in
   *    the safe direction, and the `LastDay`/`AddMonths` outputs matter because a date they
   *    produce can be read by a further calendar node after its own input passed this check;
   *  - `add_months` with a column count (task 60) shifts by the same 31-day-month
   *    over-approximation, at the emitter's own guard bound (`MONTH_ARITH_MIN/MAX_MONTHS`)
   *    rather than one literal value - tighter than the whole contract range, and it composes;
   *  - `greatest`/`least`/`if`/`coalesce` (the last compiles to `IfElse`) take the hull of
   *    their date operands.
   *
   * Values are `Long` so two literals of two billion cannot wrap the sum. A field-typed output
   * (`year`, `dayofweek`, `datediff`...) never reaches here as a calendar node's child - the
   * Spark type gate forbids it - and anything else is `Unknown`. The literal table is keyed by
   * value in slot order and untyped, so a slot's value is read by its IR position only.
   */
  private def dayRange(node: VarkaVectorIR, literals: mutable.LinkedHashMap[Int, Int]): DayRange = {
    def literalValue(slot: LiteralSlot): Long = literals.keysIterator.drop(slot.index).next().toLong
    def shifted(child: VarkaVectorIR, lo: Long, hi: Long): DayRange =
      dayRange(child, literals) match {
        case Bounded(clo, chi) => Bounded(clo + lo, chi + hi)
        case other => other
      }
    def hull(a: VarkaVectorIR, b: VarkaVectorIR): DayRange =
      (dayRange(a, literals), dayRange(b, literals)) match {
        case (Unknown, _) | (_, Unknown) => Unknown
        case (ColumnShifted, _) | (_, ColumnShifted) => ColumnShifted
        case (Bounded(alo, ahi), Bounded(blo, bhi)) =>
          Bounded(math.min(alo, blo), math.max(ahi, bhi))
      }
    def columnShifted(child: VarkaVectorIR): DayRange = dayRange(child, literals) match {
      case Unknown => Unknown
      case _ => ColumnShifted
    }
    node match {
      case _: ColumnRef => Bounded(VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS)
      case slot: LiteralSlot =>
        val v = literalValue(slot)
        Bounded(v, v)
      case n: AddDays => n.offset() match {
        case slot: LiteralSlot => shifted(n.days(), literalValue(slot), literalValue(slot))
        case _ => columnShifted(n.days())
      }
      case n: SubDays => n.offset() match {
        case slot: LiteralSlot => shifted(n.days(), -literalValue(slot), -literalValue(slot))
        case _ => columnShifted(n.days())
      }
      case n: IRNextDay => shifted(n.days(), 1, 7)
      case n: IRAddMonths => n.months() match {
        case slot: LiteralSlot =>
          val m = literalValue(slot)
          shifted(n.days(), math.min(28 * m, 31 * m), math.max(28 * m, 31 * m))
        // A column count is bounded by the emitter's own runtime guard (task 60) to
        // [MONTH_ARITH_MIN_MONTHS, MONTH_ARITH_MAX_MONTHS], so the day it can produce is
        // bounded too - by the same 31-day-month over-approximation the literal arm uses, at
        // the guard's own extremes rather than one literal value. This is the correction to
        // `PLAN_MILESTONE_4.md` 2.27, which named this shift `ColumnShifted`: a runtime-bounded
        // count still yields a `Bounded` day range, which composes and needs no second guard.
        case _ => shifted(n.days(),
          31L * VarkaChrono.MONTH_ARITH_MIN_MONTHS, 31L * VarkaChrono.MONTH_ARITH_MAX_MONTHS)
      }
      case n: IRLastDay => shifted(n.days(), 0, 30)
      // A truncated date (task 35) is its input or an earlier day of the same period: at most
      // 365 back, the 31st of December of a leap year truncated to its year.
      case n: IRTruncDate => shifted(n.days(), -365, 0)
      // make_date publishes only whole years of the narrow range (task 42): every date it
      // answers lies inside it, and a year outside declines the batch before any consumer.
      case n: IRMakeDate => Bounded(
        LocalDate.of(VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1).toEpochDay,
        LocalDate.of(VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31).toEpochDay)
      // The Thursday of a day's week is within three days of it either way (task 37).
      case n: ThursdayOf => shifted(n.days(), -3, 3)
      case n: IRGreatest => hull(n.left(), n.right())
      case n: IRLeast => hull(n.left(), n.right())
      case n: IfElse => hull(n.thenNode(), n.elseNode())
      case _ => Unknown
    }
  }

  /**
   * Compiles a calendar node's child and admits it only if `dayRange` says the decomposition
   * will see a day inside the narrowed range. A bounded interval that leaves it declines the
   * entry - free at run time, and the row engine computes it correctly; a column-shifted
   * subtree is admitted, because the emitter guards that producer per batch (task 52's
   * runtime half); an unknown producer declines, so a node this analysis has not been taught
   * fails safe as a residual entry rather than as a wrong answer.
   */
  private def calendarInput(
      child: Expression,
      calendar: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    compileNode(child, inputs, literals, sink).flatMap(admitCalendar(_, calendar, literals, sink))
  }

  /**
   * The admission half of [[calendarInput]] over an already-built IR node, for a calendar node
   * whose child is not the compiled expression itself - task 37's week tail runs over the
   * Thursday shift the compiler wraps around the date, so the shift is what the analysis bounds.
   */
  private def admitCalendar(
      node: VarkaVectorIR,
      calendar: Expression,
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    dayRange(node, literals) match {
      case Bounded(lo, hi)
          if lo >= VarkaChrono.NARROW_MIN_DAYS && hi <= VarkaChrono.NARROW_MAX_DAYS =>
        Some(node)
      case Bounded(lo, hi) =>
        sink.note(s"day range [$lo, $hi] leaves the calendar lowering's range", calendar)
        None
      case ColumnShifted => Some(node)
      case Unknown =>
        sink.note("day producer the calendar range analysis does not bound", calendar)
        None
    }
  }

  /**
   * Resolves `next_day`'s weekday operand (task 33) to the runtime literal
   * `k = dayOfWeek - 1` the emitted lowering needs. `dayOfWeek` comes from
   * `DateTimeUtils.getDayOfWeekFromString`, whose range is `[0, 6]`
   * (`THURSDAY = 0 .. WEDNESDAY = 6`), so `k` ranges over `{-1, 0, ..., 5}`. Unlike
   * `foldOffset`, the operand need not be a bare `Literal` - `next_day`'s weekday is any
   * foldable expression - so it is evaluated eagerly, and every way that can fail declines
   * rather than throws: a null result, an unrecognized weekday name
   * (`SparkIllegalArgumentException`), or any other exception `dow.eval()` itself raises
   * while evaluating a computed (not just literal) expression.
   */
  private def foldWeekday(dow: Expression, sink: DeclineSink): Option[Int] = {
    try {
      val name = dow.eval()
      if (name == null) {
        sink.note("next_day with a null weekday", dow)
        None
      } else {
        Some(DateTimeUtils.getDayOfWeekFromString(name.asInstanceOf[UTF8String]) - 1)
      }
    } catch {
      case _: SparkIllegalArgumentException =>
        sink.note("next_day with an unrecognized weekday", dow)
        None
      case NonFatal(e) =>
        sink.note(s"next_day weekday failed to evaluate: ${e.getMessage}", dow)
        None
    }
  }

  /** Where a `trunc(date, fmt)` compiles to: a `TruncDate` level, or the `WEEK` rewrite. */
  private sealed trait TruncTarget
  private case class ToLevel(level: TruncLevel) extends TruncTarget
  private case object ToWeek extends TruncTarget

  /**
   * Resolves `trunc`'s format operand (task 35) through `DateTimeUtils.parseTruncLevel` - the
   * definition, never a re-implementation of its spellings and case folding - to one of the
   * three date levels or the `WEEK` rewrite, or `None` with the reason noted. Like
   * `foldWeekday`, the operand is any foldable expression, so it is evaluated eagerly and every
   * way that can fail declines rather than throws: a null format, an unrecognized string, a
   * level below a day (`'DAY'`, `'HOUR'`... - `truncDate` is undefined there and the row engine
   * returns NULL), or an exception from evaluating a computed format.
   */
  private def foldTruncLevel(format: Expression, sink: DeclineSink): Option[TruncTarget] = {
    try {
      val fmt = format.eval()
      if (fmt == null) {
        sink.note("trunc with a null format", format)
        None
      } else {
        DateTimeUtils.parseTruncLevel(fmt.asInstanceOf[UTF8String]) match {
          case DateTimeUtils.TRUNC_TO_YEAR => Some(ToLevel(TruncLevel.YEAR))
          case DateTimeUtils.TRUNC_TO_MONTH => Some(ToLevel(TruncLevel.MONTH))
          case DateTimeUtils.TRUNC_TO_QUARTER => Some(ToLevel(TruncLevel.QUARTER))
          case DateTimeUtils.TRUNC_TO_WEEK => Some(ToWeek)
          case DateTimeUtils.TRUNC_INVALID =>
            sink.note("trunc with an unrecognized format", format)
            None
          case _ =>
            sink.note("trunc to a level below a day, which is null for a date", format)
            None
        }
      }
    } catch {
      case NonFatal(e) =>
        sink.note(s"trunc format failed to evaluate: ${e.getMessage}", format)
        None
    }
  }

  private def foldPick(
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
   * The condition compiler (task 11): interior comparisons and the connectives, three-valued
   * at run time via the emitter's known-true/known-false pairs. `EqualNullSafe` deliberately
   * declines - its both-null-is-true case breaks the null-intolerant comparison rule and earns
   * its own algebra entry or nothing (plan section 4).
   */
  private def compileCond(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[Cond] = expr match {
    case LessThan(l, r) => compare(CompareOp.LT, l, r, inputs, literals, sink)
    case LessThanOrEqual(l, r) => compare(CompareOp.LE, l, r, inputs, literals, sink)
    case GreaterThan(l, r) => compare(CompareOp.GT, l, r, inputs, literals, sink)
    case GreaterThanOrEqual(l, r) => compare(CompareOp.GE, l, r, inputs, literals, sink)
    case EqualTo(l, r) => compare(CompareOp.EQ, l, r, inputs, literals, sink)
    // IN over date literals (task 20): an EQ chain joined by OR, which the mask algebra
    // makes exactly SQL's IN inside a condition - a null value leaves every comparison
    // unknown, the OR of unknowns is unknown, and an unknown condition falls to ELSE.
    case in @ In(value, list) if value.dataType == DateType =>
      compileInList(value, list.map(literalDays), in, inputs, literals, sink)
    case inSet: InSet if inSet.child.dataType == DateType =>
      // InSet's set is unordered; compileInList sorts, which is what keeps the literal
      // slots and the shape hash deterministic across runs.
      compileInList(inSet.child,
        inSet.hset.toSeq.map { case days: Int => Some(days); case _ => None },
        inSet, inputs, literals, sink)
    case And(l, r) =>
      for {
        left <- compileCond(l, inputs, literals, sink)
        right <- compileCond(r, inputs, literals, sink)
      } yield new IRAnd(left, right)
    case Or(l, r) =>
      for {
        left <- compileCond(l, inputs, literals, sink)
        right <- compileCond(r, inputs, literals, sink)
      } yield new IROr(left, right)
    case Not(child) => compileCond(child, inputs, literals, sink).map(new IRNot(_))
    // The validity predicates (task 20): IS NOT NULL is the IR's first total condition
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

  /** The epoch-day value of a date literal, or `None` for anything else (null included). */
  private def literalDays(e: Expression): Option[Int] = e match {
    case Literal(days: Int, DateType) => Some(days)
    case _ => None
  }

  /**
   * Compiles an IN list (task 20): dedup and sort the literal days - Kleene OR is commutative
   * and EQ is pure, so the order is free, and a canonical order keeps the literal slots and
   * the shape hash deterministic (`InSet` hands the values over as an unordered set) - then a
   * '''balanced''' pairwise fold of OR over the EQ leaves. The fold shape is part of the cap
   * arithmetic: balanced, [[MaxInLiterals]] literals are `ceil(log2 n) + 1` levels and
   * `2n - 1` op nodes; a right-nested fold would hit the emitter's depth cap at 15. Above the
   * cap, or with any non-literal or null element, the entry declines with its reason.
   */
  private def compileInList(
      value: Expression,
      elements: Seq[Option[Int]],
      whole: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[Cond] = {
    if (elements.isEmpty || elements.exists(_.isEmpty)) {
      sink.note("IN list has a null or non-literal date element", whole)
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
              new LiteralSlot(literals.getOrElseUpdate(d, literals.size)))
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
   * Compiles the operand of a validity predicate, which must land on a bare date column: the
   * emitter reads the column's per-lane-group validity word, and only a column's word is live
   * before value emission (the recorded milestone-3 restriction). As in `compileCoalesce`
   * above, the `ColumnRef` match is a proxy for "bare column" that depends on every relabel
   * expression compiling to `ColumnRef` staying a null-intolerant identity.
   */
  private def compileValidity(
      child: Expression,
      whole: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[Cond] = {
    compileNode(child, inputs, literals, sink) match {
      case Some(ref: ColumnRef) => Some(new IRIsNotNull(ref))
      case Some(_) =>
        sink.note("validity predicate over a non-column operand", whole)
        None
      case None => None
    }
  }

  private def compare(
      op: CompareOp,
      l: Expression,
      r: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[Cond] = {
    // An int literal against a fused int field - `weekofyear(d) = 53`, `month(d) = 6` - is a
    // comparison of two int lanes like any other (task 37); the literal takes a slot the way a
    // date literal does. Only here: compileNode's value leaves stay DateType, since a bare
    // int literal has no meaning as a date operand, and int arithmetic over an output is
    // task 30's, not a comparison's.
    def operand(e: Expression): Option[VarkaVectorIR] = e match {
      case Literal(v: Int, IntegerType) =>
        Some(new LiteralSlot(literals.getOrElseUpdate(v, literals.size)))
      case _ => compileNode(e, inputs, literals, sink)
    }
    for {
      left <- operand(l)
      right <- operand(r)
    } yield new Compare(op, left, right)
  }
}
