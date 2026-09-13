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
import org.apache.spark.sql.catalyst.expressions.{Abs, Add, AddMonths, Alias, And, Attribute, BindReferences, BoundReference, CaseWhen, Cast, Coalesce, DateAdd, DateAddYMInterval, DateDiff, DateFromUnixDate, DateSub, DateVarkaSupport, DayOfMonth, DayOfWeek, DayOfYear, EqualTo, EvalMode, Expression, ExtractANSIIntervalDays, GreaterThan, GreaterThanOrEqual, Greatest, If, In, InSet, IsNotNull, IsNull, LastDay, Least, LessThan, LessThanOrEqual, Literal, MakeDate, MakeYMInterval, Month, Multiply, MultiplyYMInterval, NamedExpression, NextDay, Not, Or, Quarter, RuntimeReplaceable, Subtract, TruncDate, UnaryMinus, UnixDate, WeekDay, WeekOfYear, Year, YearOfWeek}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaDerivedKind, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays, AddMonths => IRAddMonths, And => IRAnd, ColumnRef, Compare, CompareOp, Cond, DateDiff => IRDateDiff, DayOfMonth => IRDayOfMonth, DayOfWeek => IRDayOfWeek, DayOfWeekIso, DayOfYear => IRDayOfYear, Greatest => IRGreatest, GuardedDay, IfElse, IntArith, IntNeg, IntOp, IsNotNull => IRIsNotNull, LastDay => IRLastDay, Least => IRLeast, LiteralSlot, MakeDate => IRMakeDate, Month => IRMonth, NextDay => IRNextDay, Not => IRNot, Or => IROr, Overflow, Quarter => IRQuarter, SubDays, ThursdayOf, TruncDate => IRTruncDate, TruncDateDynamic => IRTruncDateDynamic, TruncLevel, WeekDay => IRWeekDay, WeekOfYear => IRWeekOfYear, Year => IRYear}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{BooleanType, DataType, DateType, DayTimeIntervalType, IntegerType, StringType, YearMonthIntervalType}
import org.apache.spark.unsafe.types.UTF8String

/**
 * A whole projection compiled to the Varka vector IR: the trees `VarkaLoopEmitter` turns into one
 * fused loop, plus everything the evaluator needs to drive the emitted class - which child columns
 * it reads (dense kernel input index = position in `inputOrdinals`), the runtime `scalarArgs`
 * values (slot index = position in `literals`), and each output's Spark type, which is what tells a
 * `datediff` day-count column (`IntegerType`) apart from a date column when the output vectors are
 * allocated.
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
 * A kernel input the evaluator derives per batch rather than reads: kernel input
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
 * `inputOrdinals`) must lie in for the kernel's answer to be Spark's. The compiler
 * records one where it rewrote an expression whose row-engine form throws outside the bound -
 * the first is `CAST(i AS INTERVAL DAY)`, which overflows past
 * `VarkaChrono.INTERVAL_DAY_LIMIT_DAYS` days - and the evaluator checks it per batch before the
 * kernel runs, declining the batch to the row engine, which then raises the error, when a live
 * lane is outside. A bound is a property of the compiled plan, not of the emitted bytes: two
 * projections with the same IR and different bounds share a kernel class.
 */
private[sql] case class VarkaInputBound(inputIndex: Int, lo: Int, hi: Int)

/**
 * How one projection entry is served under partial eligibility: computed by the fused
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
 * Why one entry could not be fused: the answer to "why didn't my projection fuse?",
 * which the compiler's per-entry `None` used to swallow. `reason` is the vocabulary term - the
 * same string the exec nodes' verbose `EXPLAIN` and debug logs print - and `expr` names the
 * offending expression, the innermost one that actually failed rather than the whole entry.
 */
private[sql] case class VarkaDecline(reason: String, expr: String) {
  override def toString: String = s"$reason: $expr"
}

/**
 * Collects what one entry's compilation leaves behind besides its IR: its decline, and the input
 * bounds it asks the evaluator to check (see `PLAN_TASK_56.md`; keyed by child ordinal until the
 * entry is accepted, and dropped with a declining entry the way its columns and literals are). The
 * recursion reports a decline at the point of failure and the first note wins, so the recorded
 * reason is the innermost cause rather than the outermost expression that inherited it; [[take]]
 * hands it over and resets for the next entry.
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
 * A projection classified entry by entry: `specs` has one entry per projectList
 * position, in order, and `fused` is the sub-projection of just the [[FusedOutput]] entries -
 * their kernel-input and literal tables cover only what the fused trees reference, so a
 * residual entry constrains neither the emitted loop nor `canRun`'s Arrow check.
 *
 * `declines` maps the position of each [[ResidualOutput]] entry to why it declined,
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
 * A filter predicate compiled conjunct by conjunct: `specs` classifies every
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
 * Compiles a bound projection list to the Varka vector IR, recursing where the MVP's flat matcher
 * demanded bare attributes - `datediff(date_add(d, 7), d2)` compiles where milestone 1 saw nothing,
 * and so do `CASE WHEN`/`IF` (via interior comparisons and the three-valued connectives),
 * `greatest`/`least`, `dayofweek`/`weekday` and date literals. Task 20 widened the conditions with
 * `IN` over date literals (capped, see [[MaxInLiterals]]) and the validity predicates `IS [NOT]
 * NULL` over bare columns, and the values with `coalesce`/`nvl`/`nvl2` (lowered onto the validity
 * condition) and the identity date cast. Used by both `VarkaColumnarRule` (is the projection
 * eligible?) and `VarkaKernelEvaluator` (what does the emitted loop compute?), so eligibility
 * cannot drift from execution: there is one compiler and the rule's question is
 * `compilePartial(...).isDefined`.
 *
 * Eligibility is per entry, not all or nothing: [[compilePartial]] classifies every entry as fused,
 * forwarded (a bare column of any type, zero-copy) or residual (per-row), and the projection is
 * eligible when at least one entry fuses - a projection of forwards and residuals alone gains
 * nothing from Varka and stays on Janino untouched. [[compile]] remains as the all-entries-fused
 * special case for callers that need exactly that.
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
 * This is the only eligibility rule there is.
 */
private[sql] object VarkaExpressionCompiler {

  /**
   * The most literals an `IN` list may hold and still fuse, counted after dedup.
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
   * Compiles a filter predicate conjunct by conjunct. The condition splits on its
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
   * The recursive node compiler. `None` anywhere fails the enclosing entry, whose caller rolls the
   * tables back to their pre-entry state. Shapes that cannot be served stay unmatched by
   * construction: a `date_add` over a `datediff` result only type-checks through a `Cast`, which
   * compiles to nothing here. Integer arithmetic over a `datediff` result was in that list until
   * the int32 lowering admitted it - a SIMD lane still cannot throw row-accurately, so an ANSI
   * overflow condemns the batch and the row engine raises it instead.
   */
  private def compileNode(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = expr match {
    case br: BoundReference if br.dataType == DateType =>
      Some(columnRef(br, inputs))
    // Task 67: a year-month interval column, on the same lane. Its value is a count of months in
    // every unit, so nothing about the lowering changes; what makes widening the leaf safe rather
    // than "do not open it wider" is that Spark's own typing decides where the value may appear. An
    // interval only type-checks into DateAddYMInterval, the ordered comparisons and IN, the
    // same-typed Least/Greatest/Coalesce/If/CaseWhen, and Cast - never into date_add's offset,
    // datediff, a calendar extraction or AddMonths' date operand, all of which are typed DateType
    // or IntegerType. So an interval in a date position is a type error the analyzer rejected
    // before the compiler ran, and the leaf cannot put one there.
    case br: BoundReference if br.dataType.isInstanceOf[YearMonthIntervalType] =>
      Some(columnRef(br, inputs))
    // The interval literal, beside the date literal and for the same reason: the value is
    // already the int the lane holds, so `ym > INTERVAL '6' MONTH` and
    // `coalesce(ym, INTERVAL '0' MONTH)` become a slot rather than a decline.
    case Literal(months: Int, _: YearMonthIntervalType) =>
      Some(new LiteralSlot(literals.getOrElseUpdate(months, literals.size)))
    // A date literal's value is already an epoch-day int, so it takes a slot in the shared
    // per-distinct-value table like a folded day offset does - what makes
    // `d < DATE'...'` and `greatest(d, DATE'...')` reachable at all. `days: Int` does not
    // match a null-valued Literal, which falls through to the catch-all below; that is a
    // safe blind spot, not a bug, since ConstantFolding removes a null date literal from any
    // real query before it can reach here (unix_date/date_from_unix_date add two more
    // recursive paths into this same match, both equally covered by that guarantee).
    case Literal(days: Int, DateType) =>
      Some(new LiteralSlot(literals.getOrElseUpdate(days, literals.size)))
    // The identity cast: the corpus wraps date expressions in `CAST(... AS DATE)`
    // 85 times, and after optimization the wrapper is a no-op over an already-date child -
    // unwrap it. A `cast(<string literal> AS DATE)` never reaches here (constant-folded to a
    // date literal by the optimizer); a string *column* cast is a per-row parse with no
    // string lane and stays declined below.
    case c: Cast if c.dataType == DateType && c.child.dataType == DateType =>
      compileNode(c.child, inputs, literals, sink)
    // unix_date/date_from_unix_date are Spark's own `input.asInstanceOf[Int]` in full - a date IS a
    // day count, so both are a pure type relabel with nothing to compute. Unwrapping to the child
    // rather than adding an IR node means `SELECT unix_date(d)` and `SELECT d` compile to the same
    // IR and share a shape hash - correct, since kernel identity is about lane math and theirs is
    // identical; the entry's output type still comes from the Catalyst expression, not the IR.
    // `date_from_unix_date`'s child is an integer column, and no value leaf reads a bare int
    // column, so this arm declines through the ordinary non-date-column path below.
    case UnixDate(child) =>
      compileNode(child, inputs, literals, sink)
    case DateFromUnixDate(child) =>
      compileNode(child, inputs, literals, sink)
    // Task 67's relabels, on `unix_date`'s pattern above: a cast that returns its operand unchanged
    // is the child alone, with no node emitted. `intToYearMonthInterval` returns `v` for a MONTH
    // end field and `yearMonthIntervalToInt` returns `v` for a MONTH-ended interval, so both
    // directions of the MONTH unit are the identity on the lane; only the Spark type on the outside
    // differs, and that rides on `outputTypes`. The YEAR unit is neither direction's identity - it
    // multiplies or divides by twelve. Its outbound half is the arm below; its inbound half,
    // `CAST(ym AS INT)` over a YEAR-ended interval, is a division by twelve, which is not supported
    // yet - it belongs with the year-month extracts.
    case Cast(child, YearMonthIntervalType(_, YearMonthIntervalType.MONTH), _, _)
        if child.dataType == IntegerType =>
      compileIntOperand(child, "the month count", inputs, literals, sink)
    // Task 68: the unit relabel between two year-month intervals, which is not a cast a user
    // writes but the one type coercion inserts whenever two units meet - `ymm + ymy` widens
    // both operands to YEAR TO MONTH before the add. `Cast.castToYearMonthInterval` computes
    // `periodToMonths(monthsToPeriod(v), endField)`, which splits the count into whole years
    // and a remainder and puts it back together: exactly `v` again for a MONTH end field, at
    // every int including `Int.MinValue`, since the reassembly's `multiplyExact` is over
    // `v / 12`. So this direction emits nothing and only `outputTypes` moves. The YEAR-ended
    // direction drops the remainder, which is a division by twelve, and declines below.
    case Cast(child, YearMonthIntervalType(_, YearMonthIntervalType.MONTH), _, _)
        if child.dataType.isInstanceOf[YearMonthIntervalType] =>
      compileNode(child, inputs, literals, sink)
    // Task 68: `CAST(i AS INTERVAL YEAR)` in a value position, which is `12 * i` with an interval
    // output. `IntervalUtils.intToYearMonthInterval` computes it with `Math.multiplyExact` whatever
    // the session's ANSI mode, so the multiply is checked and the bound is the only thing that
    // removes it. This is the same expression `compileMonths` admits in `add_months`' month-count
    // position; the difference is that there the emitter has a shape check to satisfy and here it
    // has none, which is why `PLAN_TASK_67.md` 2.1 - written about `compileMonths` - reads as if
    // the whole cast were blocked when only that position was.
    case c @ Cast(child, YearMonthIntervalType(YearMonthIntervalType.YEAR,
        YearMonthIntervalType.YEAR), _, _) if child.dataType == IntegerType =>
      val mark = literals.size
      val built = for {
        x <- intOperand(child, inputs, literals, sink)
        r <- arithOver(IntOp.MUL, Overflow.FAIL, x, twelve(literals), c, literals, mark, sink)
      } yield r
      if (built.isEmpty) truncate(literals, mark)
      built
    // The truncating half of the pair above, named rather than left to the generic decline:
    // narrowing a year-month interval to a YEAR-ended unit keeps only the whole years, which is `v
    // - v % 12` and so a division. Type coercion never produces this - it widens the end field - so
    // it reaches here only from a cast the user wrote, and it is not supported yet, belonging with
    // `extract(YEAR FROM ym)` and `ym / k`.
    case c @ Cast(child, YearMonthIntervalType(_, YearMonthIntervalType.YEAR), _, _)
        if child.dataType.isInstanceOf[YearMonthIntervalType] =>
      sink.note("year-month interval narrowed to a YEAR-ended unit, which divides by twelve", c)
      None
    case Cast(child, IntegerType, _, _)
        if child.dataType == YearMonthIntervalType(YearMonthIntervalType.MONTH,
          YearMonthIntervalType.MONTH) =>
      compileNode(child, inputs, literals, sink)
    case DateAdd(child, days) =>
      // The date child compiles before the offset, matching CaseWhen's rule a few cases below:
      // ordinals and literal slots register in reading order. A foldable literal offset registers
      // no ordinal, so this ordering is new in an observable way now that an offset can be a
      // column: when BOTH operands are unfusable, DeclineSink's "first note wins" rule reports the
      // child's reason, not the offset's (pinned by VarkaExpressionCompilerSuite's "with two
      // independently unfusable operands, the child's reason is reported" test).
      days match {
        // `date - INTERVAL n DAY`: the analyzer spells it as an add of the negated
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
    // all-valid invariant (`PLAN_TASK_11.md` 2.1): decline.
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
    // Coalesce right-folds onto the validity condition: `coalesce(a, b)` is
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
    // extract(DAYOFWEEK_ISO) / date_part('DOW_ISO'): the analyzer spells them Add(WeekDay(d), 1),
    // and so does a hand-written weekday(d) + 1. One narrow arm, either operand order, and nothing
    // else: integer arithmetic over an output is out of scope for this compiler. Task 63: int32
    // arithmetic over int-valued operands - a fused field, an IntegerType column, an int literal,
    // or nested arithmetic. Placed after the `Add(WeekDay, 1)` arm below so that shape keeps its
    // cheaper dedicated node.
    case a: Add if a.dataType == IntegerType && !isDayOfWeekIso(a) =>
      intArith(IntOp.ADD, a.evalMode, a.left, a.right, a, inputs, literals, sink)
    case a: Subtract if a.dataType == IntegerType =>
      intArith(IntOp.SUB, a.evalMode, a.left, a.right, a, inputs, literals, sink)
    case a: Multiply if a.dataType == IntegerType =>
      intArith(IntOp.MUL, a.evalMode, a.left, a.right, a, inputs, literals, sink)
    // Group A: the year-month interval algebra, on the int32 arithmetic nodes with an
    // interval-typed output. The int arms above keep their `IntegerType` gate and these are
    // siblings rather than a widening of it, because int arithmetic is an int-typed concept and a
    // widened gate is how an interval reaches a position that reads it as a day count. Every one of
    // them is checked in every evaluation mode - Spark computes them with `addExact`,
    // `subtractExact`, `negateExact` and `multiplyExact`, and there is no `LEGACY` or `try_`
    // spelling for an interval - so the mode is `FAIL` and `intBound` is the only thing that takes
    // the check off.
    case a: Add if a.dataType.isInstanceOf[YearMonthIntervalType] =>
      intervalArith(IntOp.ADD, a.left, a.right, a, inputs, literals, sink)
    case a: Subtract if a.dataType.isInstanceOf[YearMonthIntervalType] =>
      intervalArith(IntOp.SUB, a.left, a.right, a, inputs, literals, sink)
    case n @ UnaryMinus(c, _) if n.dataType.isInstanceOf[YearMonthIntervalType] =>
      // `IntervalMathUtils.negateExact`, which throws on `Int.MinValue` alone, so any bound at
      // all rules it out - `IntNeg`'s reasoning over an interval operand.
      intervalOperand(c, "the negated interval", inputs, literals, sink).map { x =>
        val checked = !intBound(x, literals).exists(_ <= Int.MaxValue.toLong)
        new IntNeg(if (checked) Overflow.FAIL else Overflow.WRAP, x)
      }
    case n @ Abs(c, _) if n.dataType.isInstanceOf[YearMonthIntervalType] =>
      // There is no abs op in the IR, and none is needed: `abs(x)` is the blend `if (x < 0) -x else
      // x`, which is the int negate node under `IfElse`. The only input that overflows a negation
      // is `Int.MinValue`, and it is negative, so it takes the `IntNeg` arm - the check fires
      // exactly where `IntegerExactNumeric` throws. That puts a checked node under a `CASE` arm,
      // and that is deliberate: the guard is qualified by the arm, so only the lanes that actually
      // negate can condemn the batch.
      intervalOperand(c, "the absolute interval", inputs, literals, sink).map { x =>
        val zero = new LiteralSlot(literals.getOrElseUpdate(0, literals.size))
        val checked = !intBound(x, literals).exists(_ <= Int.MaxValue.toLong)
        new IfElse(new Compare(CompareOp.LT, x, zero),
          new IntNeg(if (checked) Overflow.FAIL else Overflow.WRAP, x), x)
      }
    case m @ MakeYMInterval(y, mo) =>
      // `toIntExact(addExact(months, multiplyExact(years, 12)))` - two of the int32 arithmetic
      // nodes composed, both checked. Over bounded operands the bound removes both checks, which is
      // what makes `make_ym_interval(year(d), month(d))` fuse with none; over an unbounded int
      // column the multiply keeps its check and declines, as every checked multiply does.
      val mark = literals.size
      val built = for {
        years <- intOperand(y, inputs, literals, sink)
        months <- intOperand(mo, inputs, literals, sink)
        scaled <- arithOver(IntOp.MUL, Overflow.FAIL, years, twelve(literals), m,
          literals, mark, sink)
        total <- arithOver(IntOp.ADD, Overflow.FAIL, months, scaled, m, literals, mark, sink)
      } yield total
      if (built.isEmpty) truncate(literals, mark)
      built
    case m @ MultiplyYMInterval(iv, num) =>
      // `Math.multiplyExact(months, num)` for the int-family arms. A literal multiplier is
      // bounded and the check comes off; an int column is an unbounded checked multiply and
      // declines like any other. The `Long`, `Decimal` and `Double` arms are not int32 lanes
      // and decline by type rather than reaching `intOperand`, which would report them as
      // "not an int column or literal" and hide which of the two is wrong.
      num.dataType match {
        case IntegerType =>
          val mark = literals.size
          val built = for {
            x <- intervalOperand(iv, "the multiplied interval", inputs, literals, sink)
            k <- intOperand(num, inputs, literals, sink)
            r <- arithOver(IntOp.MUL, Overflow.FAIL, x, k, m, literals, mark, sink)
          } yield r
          if (built.isEmpty) truncate(literals, mark)
          built
        case other =>
          sink.note(s"interval multiplier of type ${other.simpleString} is not an int32 lane", m)
          None
      }
    case n @ UnaryMinus(c, failOnError) if n.dataType == IntegerType =>
      // Spark has no try_negative, so the mode is only ever WRAP or FAIL here. Negation
      // overflows on exactly one value, `Int.MinValue`, so any bound at all rules it out and
      // the check comes off - the same reasoning the binary arms use, on a narrower fact.
      intOperand(c, inputs, literals, sink).map { x =>
        val checked = failOnError && !intBound(x, literals).exists(_ <= Int.MaxValue.toLong)
        new IntNeg(if (checked) Overflow.FAIL else Overflow.WRAP, x)
      }
    case Add(WeekDay(child), Literal(1, IntegerType), _) =>
      compileNode(child, inputs, literals, sink).map(new DayOfWeekIso(_))
    case Add(Literal(1, IntegerType), WeekDay(child), _) =>
      compileNode(child, inputs, literals, sink).map(new DayOfWeekIso(_))
    // next_day: a foldable weekday is resolved at compile time and travels as a
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
    // A weekday column: the kernel reads an int32 column the evaluator derives
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
    // The calendar extractions. One civil-from-days decomposition per node, so two
    // fields of the same date are computed twice - see VarkaVectorIR.Year for why. The child
    // goes through `calendarInput`: the decomposition is exact only over
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
    // make_date(y, m, d): three int operands, each a column or a literal, and the
    // evaluation mode captured on the expression - two modes are two shapes.
    case MakeDate(y, m, d, failOnError) =>
      for {
        yy <- compileIntOperand(y, "make_date's year", inputs, literals, sink)
        mm <- compileIntOperand(m, "make_date's month", inputs, literals, sink)
        dd <- compileIntOperand(d, "make_date's day", inputs, literals, sink)
      } yield new IRMakeDate(yy, mm, dd, failOnError)
    case LastDay(child) =>
      calendarInput(child, expr, inputs, literals, sink).map(new IRLastDay(_))
    // weekofyear, extract(WEEK) and date_part: the ISO week by the Thursday rule - the
    // week tail over the Thursday of the day's week, two nodes so the prefix runs over the
    // shifted day and so extract(YEAROFWEEK) is Year over the same ThursdayOf. The
    // calendar node's child is the shift, so the range analysis admits the shift, not the day.
    case WeekOfYear(child) =>
      compileNode(child, inputs, literals, sink)
        .flatMap(c => admitCalendar(new ThursdayOf(c), expr, literals, sink))
        .map(new IRWeekOfYear(_))
    // extract(YEAROFWEEK) / date_part('YEAROFWEEK'): the ISO week-based year is the
    // calendar year of the same Thursday, so Year over the same shift - one prefix for both
    // fields under CSE, and nothing in the emitter.
    case YearOfWeek(child) =>
      compileNode(child, inputs, literals, sink)
        .flatMap(c => admitCalendar(new ThursdayOf(c), expr, literals, sink))
        .map(new IRYear(_))
    // trunc(date, fmt): the format resolves at compile time, like next_day's weekday, because the
    // level chooses which code is emitted. YEAR, MONTH and QUARTER are one node with the level as a
    // shape-bearing field; WEEK is Spark's own definition, next_day(d - 7, 'MONDAY'), rewritten
    // onto the nodes the compiler already has - the unix_date pattern of retiring an expression
    // onto existing IR. A stored string column is the dynamic node below. Everything else declines,
    // each for its own reason: the row engine answers those with a NULL column, which no IR node
    // can produce.
    case TruncDate(date, format) if format.foldable =>
      foldTruncLevel(format, sink).flatMap {
        case ToLevel(level) =>
          calendarInput(date, expr, inputs, literals, sink).map(new IRTruncDate(_, level))
        case ToWeek =>
          compileNode(date, inputs, literals, sink).map { d =>
            val week = new LiteralSlot(literals.getOrElseUpdate(7, literals.size))
            // next_day's slot holds dayOfWeek - 1; Monday through the same parser
            // foldWeekday uses, so the constant is the definition's, not a retyped 3.
            val monday = new LiteralSlot(literals.getOrElseUpdate(
              DateTimeUtils.getDayOfWeekFromString(UTF8String.fromString("MONDAY")) - 1,
              literals.size))
            new IRNextDay(new SubDays(d, week), monday)
          }
      }
    // A format column: the level is read per batch by the evaluator's derived leaf
    // (TruncLevelLeaf) into an int32 column of parseTruncLevel's codes, on next_day's pattern
    //, and the kernel computes every period and selects on it. No ANSI twin in the
    // kind: TruncDate has no error path, so a null, unrecognised or sub-day format is a NULL
    // row in either mode - the leaf's null lane, through the node's word. Any collation is
    // admitted because the parser ignores it; an expression over the column stays the row
    // engine's, since the leaf reads a stored column.
    case TruncDate(date, br: BoundReference) if br.dataType.isInstanceOf[StringType] =>
      calendarInput(date, expr, inputs, literals, sink)
        .map(new IRTruncDateDynamic(_, derivedRef(br, VarkaDerivedKind.TRUNC_LEVEL, inputs)))
    case t: TruncDate =>
      sink.note("trunc with a non-foldable format", t)
      None
    // Month arithmetic: add_months(d, n) and d +- INTERVAL n MONTH/YEAR are the same
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

  /** Task 57's `extract(DAYOFWEEK_ISO)` shape, which keeps its own node rather than becoming
   *  int arithmetic over a `weekday` output. Either operand order, exactly as that arm reads. */
  private def isDayOfWeekIso(a: Add): Boolean = (a.left, a.right) match {
    case (WeekDay(_), Literal(1, IntegerType)) => true
    case (Literal(1, IntegerType), WeekDay(_)) => true
    case _ => false
  }

  /** Spark's evaluation mode as the IR spells it. */
  private def overflowOf(mode: EvalMode.Value): Overflow = mode match {
    case EvalMode.LEGACY => Overflow.WRAP
    case EvalMode.ANSI => Overflow.FAIL
    case EvalMode.TRY => Overflow.NULL
  }

  /**
   * An operand of int arithmetic: an `IntegerType` column becomes the int column leaf, an int
   * literal a slot, and everything else goes through `compileNode` - which yields the fused int
   * fields (`datediff`, the extractions, the ISO weekday) and nested arithmetic. A `DateType`
   * operand is refused here rather than silently treated as a day count: `date + 1` is `DateAdd`
   * and has its own arm.
   */
  private def intOperand(
      e: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = e match {
    case br: BoundReference if br.dataType == IntegerType => Some(columnRef(br, inputs))
    case Literal(v: Int, IntegerType) =>
      Some(new LiteralSlot(literals.getOrElseUpdate(v, literals.size)))
    case _ if e.dataType != IntegerType =>
      sink.note(s"int arithmetic operand of type ${e.dataType.simpleString}", e)
      None
    case _ => compileNode(e, inputs, literals, sink)
  }

  /**
   * How large an int-valued node's result can be in absolute value, or `None` where nothing
   * bounds it. The calendar fields are bounded by their own definitions, a literal by its
   * value, and `datediff` by its two operands' own day ranges - not by the date contract
   * alone, which is true of a date column but not of every operand a `datediff` accepts. An
   * `IntegerType` column is not bounded at all, and neither is anything built on one.
   *
   * This exists so a checked operation that provably cannot overflow needs no check - which is
   * what makes `year(d) * 100 + month(d)` fuse under ANSI, the shape `PLAN_TASK_63.md` 6
   * measures. The compiler can do this and the emitter cannot: a `LiteralSlot` carries a slot
   * index, and the value behind it only arrives in `scalarArgs` at run time.
   *
   * Deliberately conservative. A bound is returned only where it is certain, so a `None` costs
   * a check or a decline and never a wrong answer.
   */
  private def intBound(node: VarkaVectorIR, literals: mutable.LinkedHashMap[Int, Int]):
      Option[Long] = {
    // Exact, because a bound that wraps is worse than no bound at all: two nested bounds whose
    // product passes 2^63 would come back a small non-negative number and "prove" a checked
    // operation safe. `None` is the conservative answer and costs only a check.
    def both(l: VarkaVectorIR, r: VarkaVectorIR)(f: (Long, Long) => Long): Option[Long] =
      for (a <- intBound(l, literals); b <- intBound(r, literals); v <- exactly(f(a, b))) yield v
    node match {
      case slot: LiteralSlot =>
        Some(math.abs(literals.keysIterator.drop(slot.index()).next().toLong))
      // The widest year a lowered date can carry: the narrowed range runs to year 33134, and
      // a day producer's guard keeps every decomposed date inside it.
      case _: IRYear => Some(40000L)
      case _: IRMonth => Some(12L)
      case _: IRDayOfMonth => Some(31L)
      case _: IRQuarter => Some(4L)
      case _: IRDayOfYear => Some(366L)
      case _: IRWeekOfYear => Some(53L)
      case _: IRDayOfWeek => Some(7L)
      case _: IRWeekDay => Some(6L)
      case _: DayOfWeekIso => Some(7L)
      // A difference of two days, bounded only where both of those days are. The contract
      // width is the answer for two date columns, but not for every `datediff`: `date_add(d,
      // 2147483647)` is a legal operand whose int32 lane wraps, and a contract-width bound
      // over it would be a fiction that removes the very check that would have caught it. So
      // both operands are asked for their own day interval with no guard assumed at the top,
      // because a `datediff` is not a calendar node and so arms none itself; a calendar node
      // *inside* an operand still does, which `shifted`'s `guardsBelow` restores. An interval
      // that leaves the int range is refused, because a lane that produced it wrapped on the
      // way in.
      case n: IRDateDiff =>
        (dayRange(n.end(), literals, guarded = false),
            dayRange(n.start(), literals, guarded = false)) match {
          case (Bounded(elo, ehi), Bounded(slo, shi)) if withinInt(elo) && withinInt(ehi) &&
              withinInt(slo) && withinInt(shi) =>
            exactly(math.max(math.abs(ehi - slo), math.abs(elo - shi)))
          case _ => None
        }
      case n: IntArith => n.op() match {
        case IntOp.MUL => both(n.left(), n.right())(Math.multiplyExact)
        case _ => both(n.left(), n.right())(Math.addExact)
      }
      case n: IntNeg => intBound(n.child(), literals)
      // A column, a date-valued node used as an int, anything else: unbounded.
      case _ => None
    }
  }

  /** `Some(v)` unless computing it overflowed `Long`, which makes the bound meaningless. */
  private def exactly(v: => Long): Option[Long] =
    try Some(v) catch { case _: ArithmeticException => None }

  /** Whether a day count fits an int32 lane, so producing it cannot have wrapped. */
  private def withinInt(v: Long): Boolean = v >= Int.MinValue.toLong && v <= Int.MaxValue.toLong

  /**
   * Whether the operation on operands of these bounds cannot leave the int32 range. Read
   * through `intBound`'s own `IntArith` arm rather than re-dispatched here: the candidate node
   * is never emitted, so building one to ask the question is free, and the two answers cannot
   * drift apart the way two copies of "MUL multiplies, else adds" once could. The bound is
   * always non-negative by construction (every base case and every combinator in `intBound`
   * preserves that), so the only thing left to ask is whether it stays at or under
   * `Int.MaxValue` - one past it, `2^31`, is the first magnitude that overflows.
   */
  private def cannotOverflow(op: IntOp, l: VarkaVectorIR, r: VarkaVectorIR,
      literals: mutable.LinkedHashMap[Int, Int]): Boolean =
    intBound(new IntArith(op, Overflow.WRAP, l, r), literals).exists(_ <= Int.MaxValue.toLong)

  /**
   * The shared body of the three binary arithmetic arms. A checked multiply declines unless
   * the operands' bounds prove it cannot overflow: the overflow test for `*` needs the 64-bit
   * product or a lane division, and the emitter has neither in int lanes, so an unprovable
   * `ANSI` or `TRY` multiply stays on the row engine until milestone 5's long lanes arrive
   * (`PLAN_TASK_63.md` 3.4).
   */
  private def intArith(
      op: IntOp,
      mode: EvalMode.Value,
      l: Expression,
      r: Expression,
      whole: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    val mark = literals.size
    val operands = for {
      x <- intOperand(l, inputs, literals, sink)
      y <- intOperand(r, inputs, literals, sink)
    } yield (x, y)
    operands match {
      case None => None
      case Some((x, y)) =>
        arithOver(op, overflowOf(mode), x, y, whole, literals, mark, sink)
    }
  }

  /**
   * The overflow decision, shared by the int arithmetic arms and the interval ones so that the
   * bound rule and the checked-multiply refusal are stated once rather than in two places that can
   * drift. A checked operation whose operands' bounds rule out overflow needs no check at all and
   * emits as `WRAP` - fewer ops, and the only way a checked multiply fuses; an int lane has no
   * cheap overflow test for `*`, so one that keeps its check declines and `literals` is rolled back
   * to `mark` so a declining entry leaves no slot behind.
   */
  private def arithOver(
      op: IntOp,
      declared: Overflow,
      x: VarkaVectorIR,
      y: VarkaVectorIR,
      whole: Expression,
      literals: mutable.LinkedHashMap[Int, Int],
      mark: Int,
      sink: DeclineSink): Option[VarkaVectorIR] = {
    val overflow =
      if (declared != Overflow.WRAP && cannotOverflow(op, x, y, literals)) Overflow.WRAP
      else declared
    if (op == IntOp.MUL && overflow != Overflow.WRAP) {
      truncate(literals, mark)
      sink.note("checked int multiply whose operands do not rule out overflow", whole)
      None
    } else {
      Some(new IntArith(op, overflow, x, y))
    }
  }

  /**
   * An operand of the year-month interval algebra: a value whose lane is a count of months. The
   * interval column and the interval literal are `compileNode`'s own leaves, and nested interval
   * arithmetic is the arms below, so this is a type gate over the same walk - the interval
   * counterpart of `intOperand`, and separate for the same reason the arms are separate rather than
   * the int arms' type gate being widened.
   */
  private def intervalOperand(
      e: Expression,
      position: String,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = e match {
    case _ if !e.dataType.isInstanceOf[YearMonthIntervalType] =>
      sink.note(s"$position of type ${e.dataType.simpleString} is not a year-month interval", e)
      None
    case _ => compileNode(e, inputs, literals, sink)
  }

  /**
   * The shared body of the binary interval arms. Spark computes `ym + ym` and `ym - ym` with
   * `IntervalMathUtils.addExact`/`subtractExact`, which throw in every evaluation mode - there is
   * no `LEGACY` wrapping form for an interval and no `try_` spelling - so the declared mode is
   * `FAIL` unconditionally rather than read off `evalMode`, and `arithOver`'s bound is the only
   * thing that takes the check off.
   */
  private def intervalArith(
      op: IntOp,
      l: Expression,
      r: Expression,
      whole: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    val mark = literals.size
    val operands = for {
      x <- intervalOperand(l, "the left interval operand", inputs, literals, sink)
      y <- intervalOperand(r, "the right interval operand", inputs, literals, sink)
    } yield (x, y)
    operands.flatMap { case (x, y) =>
      arithOver(op, Overflow.FAIL, x, y, whole, literals, mark, sink)
    }
  }

  /** The slot holding `12`, the months in a year - `make_ym_interval` and the YEAR casts. */
  private def twelve(literals: mutable.LinkedHashMap[Int, Int]): LiteralSlot =
    new LiteralSlot(literals.getOrElseUpdate(12, literals.size))

  /**
   * Interns `br`'s ordinal into `inputs` and wraps it as a `ColumnRef` - shared by
   * `compileNode`'s `DateType` leaf and `compileOffset`'s `IntegerType` one, the two column
   * kinds the compiler admits.
   */
  private def columnRef(br: BoundReference, inputs: mutable.LinkedHashMap[Int, Int]): ColumnRef =
    new ColumnRef(inputs.getOrElseUpdate(br.ordinal, inputs.size))

  /**
   * `columnRef`'s twin for an input the evaluator derives from `br`: interned under
   * `VarkaDerivedInput.key` beside the child ordinals, so it takes the next kernel input index
   * and shares the table's rollback.
   */
  private def derivedRef(br: BoundReference, kind: VarkaDerivedKind,
      inputs: mutable.LinkedHashMap[Int, Int]): ColumnRef =
    new ColumnRef(inputs.getOrElseUpdate(VarkaDerivedInput.key(br.ordinal, kind), inputs.size))

  /**
   * An int operand of a node that is not a day: a foldable int literal as a slot, a bare
   * `IntegerType` column as a column ref, and any other `IntegerType` expression through
   * `compileNode`, which is where the fused int fields and the arithmetic arms live. So
   * `make_date(y + 1, m, d)` fuses, and an operand of the wrong type still declines here with
   * `position` in the reason rather than reaching an arm that would read it as an int.
   * `compileNode`'s column leaf stays `DateType`-only, which is why the two leaves above cannot be
   * left to it.
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
    case other if other.dataType != IntegerType =>
      sink.note(s"$position is not an int column or literal", other)
      None
    case other => compileNode(other, inputs, literals, sink)
  }

  /**
   * The day offset of a `date_add`/`date_sub`: a folded literal keeps today's `LiteralSlot` shape
   * (existing plans and their cached kernels are untouched), a non-foldable offset is a bare
   * `IntegerType` column, and it may also be int arithmetic over those - `date_add(d, i * 7)`. It
   * is still deliberately not a general `compileNode` recursion. `compileNode`'s `BoundReference`
   * leaf stays `DateType`-only: widening it instead of this dedicated path would let an int column
   * reach every other position that calls `compileNode` too (`Compare`, `DateDiff`, `Coalesce`,
   * `Greatest`...), fusing plain integer-vs-integer predicates that were never part of this task's
   * scope ("do not open it wider", `PLAN_TASK_38.md` 6).
   */
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
          // A day interval built from an int column: `CAST(i AS INTERVAL DAY)`. The
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
            // A stored INTERVAL DAY column is int64 microseconds, which no int32 lane can read;
            // this is scoped to the int-cast form.
            sink.note("day interval is not an int column cast to days", e)
            None
          // Task 63: arithmetic over an int column as the offset, `date_add(d, i * 7)`. What makes
          // this safe above rather than only here is `dayRange`, which reads any non-literal offset
          // as a column shift: a calendar node over such a producer still gets the runtime range
          // guard, exactly as it does for a bare column offset. Only the four arithmetic shapes,
          // not every `IntegerType` expression - the emitter's own check on this operand admits the
          // same three node kinds and nothing else, so the two stay a matched pair rather than one
          // silently outgrowing the other.
          case arith @ (_: Add | _: Subtract | _: Multiply | _: UnaryMinus)
              if arith.dataType == IntegerType =>
            // The compiled root has to be a shape the offset position takes, not merely something
            // built from an arithmetic expression: `weekday(d) + 1` is an `Add` that lowers to
            // `DayOfWeekIso`, which the offset position does not accept. Admitting it here would
            // put an entry through `compilePartial` as fused and let the emitter's refusal fire at
            // emit time, where the evaluator turns it into a silent per-batch fallback while
            // EXPLAIN still claims fusion - the ghost fallback `sql/varka/AGENTS.md` forbids. The
            // test is the emitter's own predicate rather than a copy of its list, so the two cannot
            // drift apart again.
            compileNode(arith, inputs, literals, sink).flatMap { n =>
              if (VarkaLoopEmitter.isDayOffsetShape(n)) {
                Some(n)
              } else {
                sink.note("day offset arithmetic that lowers to a node the offset " +
                  "position does not take", arith)
                None
              }
            }
          case other =>
            sink.note(
              "day offset is not a foldable literal, an integer column or int arithmetic",
              other)
            None
        }
    }
  }

  /**
   * "An int column, as a day interval", the one spelling of it that stays a date-lane
   * expression: `ExtractANSIIntervalDays` over `CAST(i AS INTERVAL DAY)`, which is
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
   * "An int column, as a year-month interval", `DayIntervalOffset`'s twin for months: `CAST(i
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
   * The month count of `add_months`/`date +- INTERVAL n MONTH/YEAR` : a foldable count folds to a
   * bounded `LiteralSlot`, the same two reasons as before - not foldable, or foldable but outside
   * `VarkaChrono`'s `MONTH_ARITH_MIN/MAX_MONTHS`, the range the emitter's `/ 12` magic multiply
   * covers (`PLAN_TASK_40.md` section 2.2). A non-foldable count is a `ColumnRef` when it is a bare
   * `IntegerType` column (`add_months(d, m)`) or the `MONTH`-end interval cast above (`d + CAST(m
   * AS INTERVAL MONTH)`) - the emitter bounds it lanewise at run time instead (the runtime guard on
   * `AddMonths` itself, since the exactness domain is the count's alone, `PLAN_TASK_60.md` section
   * 2). A `YearMonthIntervalType` column with no such cast declines by name: the Arrow cache holds
   * it as an `IntervalYearVector`, which `isArrowBacked` does not read, so admitting it would fuse
   * at plan time and then refuse every batch. `d - INTERVAL m MONTH` arrives as `UnaryMinus` over
   * the cast and is not matched here; it declines until the int negate node composes with it.
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
          // `Cast.intToYearMonthInterval` returns `12 * v` for a YEAR end field, so the value
          // under this cast is a count of years and the node needs months - unlike the MONTH-end
          // cast MonthIntervalOffset matches, which returns `v` unchanged.
          //
          // The emitter's month-count check accepts int arithmetic once `requireMonthCountShape`
          // has split it from `next_day`'s weekday, which shares no guard with it. So this is that
          // multiply, always checked because `IntervalUtils.intToYearMonthInterval` uses
          // `Math.multiplyExact` whatever the session's ANSI mode, and `arithOver`'s bound is what
          // removes the check - `CAST(year(d) AS INTERVAL YEAR)` fuses on its 40000 bound while a
          // bare column declines, as every unbounded checked multiply does. A foldable year count
          // never reaches this arm at all: `foldDaysOffset` above folds `CAST(5 AS INTERVAL YEAR)`
          // to 60 before the match.
          case c @ Cast(operand, YearMonthIntervalType(YearMonthIntervalType.YEAR,
              YearMonthIntervalType.YEAR), _, _) if operand.dataType == IntegerType =>
            val mark = literals.size
            val built = for {
              x <- intOperand(operand, inputs, literals, sink)
              r <- arithOver(IntOp.MUL, Overflow.FAIL, x, twelve(literals), c,
                literals, mark, sink)
            } yield r
            if (built.isEmpty) truncate(literals, mark)
            built
          // Task 68: `d - ym_col`, which the analyzer spells `DateAddYMInterval(d,
          // UnaryMinus(ym))`, so the count is a negation of an interval column. Its check comes off
          // wherever a negation's does, which is any bound at all - and a bare interval column has
          // none, so this keeps its check and is guarded on the count's value at run time exactly
          // as a plain column count is.
          case u @ UnaryMinus(operand, _)
              if operand.dataType.isInstanceOf[YearMonthIntervalType] =>
            intervalOperand(operand, "the negated month count", inputs, literals, sink).map { x =>
              val checked = !intBound(x, literals).exists(_ <= Int.MaxValue.toLong)
              new IntNeg(if (checked) Overflow.FAIL else Overflow.WRAP, x)
            }
          // Task 67: `d + ym_col`. The stored value is the month count in every unit, so this is
          // the column-count `AddMonths` exactly, with the same runtime guard on the count's lanes
          // - the guard reads the value and not the column's Spark type. It declined until now only
          // because the evaluator would not read the vector, and the evaluator would not read it
          // because no arm asked.
          case br: BoundReference if br.dataType.isInstanceOf[YearMonthIntervalType] =>
            Some(columnRef(br, inputs))
          // AddMonths.inputTypes is Seq(DateType, IntegerType) exactly - unlike DateAdd, which
          // accepts a TypeCollection - so the analyzer widens a Short/Byte count with a cast and
          // a bare non-integer column never reaches here. The cast is what arrives, and it gets
          // a reason naming the column's own type: the int32 lanes read an IntegerType column,
          // and a SmallIntVector is not one, so this declines rather than fusing at plan time
          // and refusing every batch. Fusing it needs a task-59-style derived leaf to widen the
          // column ahead of the kernel, which is its own task.
          case Cast(br: BoundReference, IntegerType, _, _) if br.dataType != IntegerType =>
            sink.note(s"month count column of type ${br.dataType.simpleString} reaches the " +
              "compiler behind a widening cast; the int32 lanes read only an integer column", br)
            None
          case other =>
            sink.note("month count is neither a foldable literal nor an integer column", other)
            None
        }
    }
  }


  /**
   * How far the IR under a calendar node can move a day. `Bounded` is an interval of epoch days the
   * value is proven to lie in - which both column-driven producers still yield, because each
   * carries a runtime guard that establishes an interval: a column month count is guarded to
   * `MONTH_ARITH_MIN/MAX_MONTHS`, so the day it can reach is bounded by the same 31-day-month
   * over-approximation the literal arm uses; and a column *day* offset (`date_add`/`date_sub`) is
   * guarded on its own result to the narrowed range, so its output is `[NARROW_MIN_DAYS,
   * NARROW_MAX_DAYS]` by construction. Stating that interval rather than a distinct "shifted"
   * verdict is what lets the two compose: whatever sits above a guarded producer shifts a known
   * interval, and `admitCalendar` tests the result. `Unknown` means a node this analysis does not
   * know, which `calendarInput` declines rather than trusts.
   */
  private sealed trait DayRange
  private case class Bounded(lo: Long, hi: Long) extends DayRange
  private case object Unknown extends DayRange

  /**
   * The compile-time half of the calendar range guard (see `PLAN_TASK_52.md` 3.1 and 10.3). The
   * civil-from-days decomposition is exact only over `VarkaChrono.NARROW_MIN_DAYS ..
   * NARROW_MAX_DAYS`, and there is no per-lane check on each calendar node, on the argument that
   * the range is decidable once, here, for everything except a column offset. This is that
   * decision, over the IR already built for the calendar node's child:
   *
   *  - a column holds `[CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]` by the project's contract, and
   *    a date literal is itself (the parser cannot write one outside the contract, but a
   *    hand-built `Literal` can, so it is read back rather than assumed);
   *  - a literal day offset shifts by exactly its value, `next_day` by 1 to 7, `add_months(n)`
   *    by 28n to 31n in whichever order, `last_day` by 0 to 30 - each an over-approximation in
   *    the safe direction, and the `LastDay`/`AddMonths` outputs matter because a date they
   *    produce can be read by a further calendar node after its own input passed this check;
   *  - `add_months` with a column count shifts by the same 31-day-month
   *    over-approximation, at the emitter's own guard bound (`MONTH_ARITH_MIN/MAX_MONTHS`)
   *    rather than one literal value - tighter than the whole contract range, and it composes;
   *  - `greatest`/`least`/`if`/`coalesce` (the last compiles to `IfElse`) take the hull of
   *    their date operands.
   *
   * `guarded` says whether the caller is a calendar consumer, which is what arms the runtime guard
   * on a column-offset producer: with it on, such a producer answers the narrowed range on the
   * strength of that guard, and with it off it answers `Unknown`, because nothing keeps it in
   * range. It has no default on purpose - the safe value is not the one a caller gets by forgetting
   * - and it turns back on below a calendar node in the subtree, which `shifted`'s `guardsBelow`
   * does.
   *
   * Values are `Long` so two literals of two billion cannot wrap the sum. A field-typed output
   * (`year`, `dayofweek`, `datediff`...) never reaches here as a calendar node's child - the
   * Spark type gate forbids it - and anything else is `Unknown`. The literal table is keyed by
   * value in slot order and untyped, so a slot's value is read by its IR position only.
   */
  private def dayRange(node: VarkaVectorIR, literals: mutable.LinkedHashMap[Int, Int],
      guarded: Boolean): DayRange = {
    def literalValue(slot: LiteralSlot): Long = literals.keysIterator.drop(slot.index).next().toLong
    // `guardsBelow` marks a node that is itself a calendar consumer - which is
    // `VarkaLoopEmitter.isChrono`'s set, the `Chrono` interface *plus* `AddMonths`, not the
    // interface alone. Task 52's guard is armed on every column-offset producer under one, so
    // the subtree below it is guarded even when the caller above is not a calendar node.
    // Without this, `datediff(last_day(date_add(d, i)), d2)` would report its operand
    // unbounded although the `last_day` over it does arm the guard - a bound lost, and a check
    // emitted, for a shape that is in fact provably in range.
    def shifted(child: VarkaVectorIR, lo: Long, hi: Long,
        guardsBelow: Boolean = false): DayRange =
      dayRange(child, literals, guarded || guardsBelow) match {
        case Bounded(clo, chi) => Bounded(clo + lo, chi + hi)
        case other => other
      }
    def hull(a: VarkaVectorIR, b: VarkaVectorIR): DayRange =
      (dayRange(a, literals, guarded), dayRange(b, literals, guarded)) match {
        case (Unknown, _) | (_, Unknown) => Unknown
        case (Bounded(alo, ahi), Bounded(blo, bhi)) =>
          Bounded(math.min(alo, blo), math.max(ahi, bhi))
      }
    // A column day offset: the emitter guards this producer's own result per batch
    // and declines the batch when a lane leaves the narrowed range, so what reaches whatever
    // sits above is exactly that range - not an unknowable shift. Saying so here is what makes
    // the guarantee compose: a further shift widens this interval and `admitCalendar` tests the
    // widened one, where treating the subtree as unbounded-but-guarded would let a shift above
    // the producer carry the day back out of the range with nothing left to catch it. The child
    // still has to be a shape the analysis knows, or the offset is added to an unknown day.
    def columnShifted(child: VarkaVectorIR): DayRange =
      if (!guarded) {
        // `guarded = false` asks what this node produces with no runtime guard behind it. The
        // narrowed range below is true only because a calendar consumer arms the guard on the
        // producer; a caller that is not one - `datediff`, which `intBound` bounds - gets no such
        // promise, so the honest answer there is that nothing bounds it.
        Unknown
      } else {
        dayRange(child, literals, guarded) match {
          case Unknown => Unknown
          case _ => Bounded(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS)
        }
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
          shifted(n.days(), math.min(28 * m, 31 * m), math.max(28 * m, 31 * m),
            guardsBelow = true)
        // A column count is bounded by the emitter's own runtime guard to
        // [MONTH_ARITH_MIN_MONTHS, MONTH_ARITH_MAX_MONTHS], so the day it can produce is
        // bounded too - by the same 31-day-month over-approximation the literal arm uses, at
        // the guard's own extremes rather than one literal value. This is the correction to
        // `PLAN_MILESTONE_4.md` 2.27, which expected a column count to be unbounded here: a
        // runtime-bounded count still yields a `Bounded` day range, which composes with the
        // interval a guarded day offset contributes and needs no second guard of its own.
        case _ => shifted(n.days(),
          31L * VarkaChrono.MONTH_ARITH_MIN_MONTHS, 31L * VarkaChrono.MONTH_ARITH_MAX_MONTHS,
          guardsBelow = true)
      }
      case n: IRLastDay => shifted(n.days(), 0, 30, guardsBelow = true)
      // A truncated date is its input or an earlier day of the same period: at most
      // 365 back, the 31st of December of a leap year truncated to its year.
      case n: IRTruncDate => shifted(n.days(), -365, 0, guardsBelow = true)
      // The same bound for the level-column form: its week result is at most six
      // days back, its year result the same 365.
      case n: IRTruncDateDynamic => shifted(n.days(), -365, 0, guardsBelow = true)
      // make_date publishes only whole years of the narrow range: every date it
      // answers lies inside it, and a year outside declines the batch before any consumer.
      case n: IRMakeDate => Bounded(
        LocalDate.of(VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1).toEpochDay,
        LocalDate.of(VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31).toEpochDay)
      // The Thursday of a day's week is within three days of it either way.
      // The whole point of the node: whatever its child's interval was, what leaves
      // it is inside the range the check enforces, because a lane outside it is reported and
      // the batch recomputed on the row engine. That reset is what lets a second guarded shift
      // compose above a first, which is the composition `PLAN_TASK_93.md` 2 is about.
      case n: GuardedDay => Bounded(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS)
      case n: ThursdayOf => shifted(n.days(), -3, 3)
      case n: IRGreatest => hull(n.left(), n.right())
      case n: IRLeast => hull(n.left(), n.right())
      case n: IfElse => hull(n.thenNode(), n.elseNode())
      case _ => Unknown
    }
  }

  /**
   * Compiles a calendar node's child and admits it only if `dayRange` says the decomposition will
   * see a day inside the narrowed range. A bounded interval that leaves it declines the entry -
   * free at run time, and the row engine computes it correctly. A column-driven producer
   * contributes the interval its own runtime guard establishes (their runtime halves) rather than a
   * special verdict, so a shift above such a producer is tested here like any other. An unknown
   * producer declines, so a node this analysis has not been taught fails safe as a residual entry
   * rather than as a wrong answer.
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
   * The result of re-arming a subtree: the rewritten node, the interval it now produces, and
   * whether a runtime-valued shift has contributed to that interval since the last
   * [[GuardedDay]] - which is what decides whether a further overflow can be guarded or has to
   * decline.
   */
  private case class Rearmed(node: VarkaVectorIR, range: DayRange, runtime: Boolean)

  /**
   * Insert [[GuardedDay]] wherever the running interval would leave the range the calendar
   * lowering decomposes exactly, resetting the interval there.
   *
   * <p>Task 52 guards one producer, and its guard promises the whole narrowed range - so a
   * second guarded shift above it has no budget left and [[admitCalendar]] must decline the
   * expression, although both shifts are individually fine. Re-arming spends the range again:
   * at a node whose interval overflows, the emitted check makes everything above it start from
   * `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]` once more.
   *
   * <p>It is a rewrite rather than a set of positions because the emitter cannot be told where
   * to check: it never sees literal values, so it cannot run this arithmetic, and
   * `VarkaShapeKey` keys a cached kernel on the IR without them - so a placement carried beside
   * the IR would let one shape be served another's guards. In the IR, the shape key separates
   * them (`PLAN_TASK_93.md` 3.3.1).
   *
   * <p><b>What must still decline, and the rule is narrower than it first looks.</b> A node is
   * re-armed only when *its own* shift is runtime-valued - a column offset, a column month
   * count - and not merely when something below it was. A literal shift that leaves the range
   * leaves it for every row, so a check above it would emit a kernel that reports every batch:
   * a slower way to decline than declining once, here, for free. The first version of this
   * rule tested "did any runtime value contribute", and admitted
   * `year(date_add(date_add(d, i), 20000000))` on the strength of the inner column offset,
   * which is exactly that mistake.
   *
   * <p>Descends only the day-typed children the analysis bounds, which is exactly [[dayRange]]'s
   * own set; anything else is returned untouched and its interval speaks for itself.
   */
  private def rearm(
      node: VarkaVectorIR,
      literals: mutable.LinkedHashMap[Int, Int]): Rearmed = {
    def shiftIsRuntime(offset: VarkaVectorIR): Boolean = !offset.isInstanceOf[LiteralSlot]
    // The node rebuilt over re-armed children, and whether its own shift is runtime-valued.
    val (rebuilt, ownRuntime, childRuntime) = node match {
      case n: AddDays =>
        val d = rearm(n.days(), literals)
        (new AddDays(d.node, n.offset()), shiftIsRuntime(n.offset()), d.runtime)
      case n: SubDays =>
        val d = rearm(n.days(), literals)
        (new SubDays(d.node, n.offset()), shiftIsRuntime(n.offset()), d.runtime)
      case n: IRAddMonths =>
        val d = rearm(n.days(), literals)
        (new IRAddMonths(d.node, n.months()), shiftIsRuntime(n.months()), d.runtime)
      case n: IRLastDay =>
        val d = rearm(n.days(), literals)
        (new IRLastDay(d.node), false, d.runtime)
      case n: IRTruncDate =>
        val d = rearm(n.days(), literals)
        (new IRTruncDate(d.node, n.level()), false, d.runtime)
      case n: IRNextDay =>
        val d = rearm(n.days(), literals)
        (new IRNextDay(d.node, n.offset()), false, d.runtime)
      case n: ThursdayOf =>
        val d = rearm(n.days(), literals)
        (new ThursdayOf(d.node), false, d.runtime)
      // The hull nodes: both operands are day-typed, so both are re-armed and either's runtime
      // contribution counts for the pair.
      case n: IRGreatest =>
        val a = rearm(n.left(), literals)
        val b = rearm(n.right(), literals)
        (new IRGreatest(a.node, b.node), false, a.runtime || b.runtime)
      case n: IRLeast =>
        val a = rearm(n.left(), literals)
        val b = rearm(n.right(), literals)
        (new IRLeast(a.node, b.node), false, a.runtime || b.runtime)
      case n: IfElse =>
        val a = rearm(n.thenNode(), literals)
        val b = rearm(n.elseNode(), literals)
        (new IfElse(n.cond(), a.node, b.node), false, a.runtime || b.runtime)
      // A leaf of the analysis, or a node it does not bound: nothing to descend into, and its
      // own interval is whatever `dayRange` already says.
      case other => (other, false, false)
    }
    dayRange(rebuilt, literals, guarded = true) match {
      case Bounded(lo, hi)
          if ownRuntime
            && (lo < VarkaChrono.NARROW_MIN_DAYS || hi > VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS) =>
        Rearmed(new GuardedDay(rebuilt),
          Bounded(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS), runtime = false)
      case other => Rearmed(rebuilt, other, ownRuntime || childRuntime)
    }
  }

  /**
   * The admission half of [[calendarInput]] over an already-built IR node, for a calendar node
   * whose child is not the compiled expression itself - the week tail runs over the Thursday shift
   * the compiler wraps around the date, so the shift is what the analysis bounds.
   */
  private def admitCalendar(
      node: VarkaVectorIR,
      calendar: Expression,
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    dayRange(node, literals, guarded = true) match {
      // Asymmetric on purpose. Downward, `NARROW_MIN_DAYS` binds: below it the narrowing is
      // undefined and no correction rescues it. Upward, the binding limit is not `NARROW_MAX_DAYS`
      // - that is the era step's *shift* domain and the range the runtime guards enforce on a
      // producer's own result - but how far the decomposition stays exact on a value already in
      // hand, which `eraOf`'s one-era correction carries about 9,266 years further. So an upward
      // shift over a guarded day producer, which used to decline conservatively, is admitted where
      // it is genuinely exact.
      case Bounded(lo, hi)
          if lo >= VarkaChrono.NARROW_MIN_DAYS
            && hi <= VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS =>
        Some(node)
      case Bounded(lo, hi) =>
        // Task 93: the interval ran out, but if a runtime-valued shift is what carried it out
        // then a check can spend it again. `rearm` rewrites the subtree with the checks in it
        // and answers the interval that results; only a literal overflow, which no check can
        // rescue, still declines.
        val fixed = rearm(node, literals)
        fixed.range match {
          case Bounded(flo, fhi)
              if flo >= VarkaChrono.NARROW_MIN_DAYS
                && fhi <= VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS =>
            Some(fixed.node)
          case _ =>
            sink.note(s"day range [$lo, $hi] leaves the calendar lowering's range", calendar)
            None
        }
      case Unknown =>
        sink.note("day producer the calendar range analysis does not bound", calendar)
        None
    }
  }

  /**
   * Resolves `next_day`'s weekday operand to the runtime literal
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
   * Resolves `trunc`'s format operand through `DateTimeUtils.parseTruncLevel` - the
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
   * The condition compiler: interior comparisons and the connectives, three-valued
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
      } yield new IRAnd(left, right)
    case Or(l, r) =>
      for {
        left <- compileCond(l, inputs, literals, sink)
        right <- compileCond(r, inputs, literals, sink)
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
    // comparison of two int lanes like any other; the literal takes a slot the way a date literal
    // does. Only here: compileNode's value leaves stay DateType, since a bare int literal has no
    // meaning as a date operand, and int arithmetic over an output is out of scope here, not a
    // comparison's.
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
