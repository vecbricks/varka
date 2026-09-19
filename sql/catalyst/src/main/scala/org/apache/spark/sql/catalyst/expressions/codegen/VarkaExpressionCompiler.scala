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

import java.util.Locale
import java.util.function.IntUnaryOperator

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.apache.spark.SparkIllegalArgumentException
import org.apache.spark.sql.catalyst.expressions.{Abs, Add, AddMonths, Alias, And, Attribute, BindReferences, BoundReference, CaseWhen, Cast, Coalesce, DateAdd, DateAddYMInterval, DateDiff, DateFromUnixDate, DateSub, DateVarkaSupport, DayOfMonth, DayOfWeek, DayOfYear, EqualTo, EvalMode, Expression, ExtractANSIIntervalDays, ExtractANSIIntervalMonths, ExtractANSIIntervalYears, GreaterThan, GreaterThanOrEqual, Greatest, HoursOfTime, If, In, InSet, IsNotNull, IsNull, LastDay, Least, LessThan, LessThanOrEqual, Literal, MakeDate, MakeTime, MakeYMInterval, MinutesOfTime, Month, Multiply, MultiplyYMInterval, NamedExpression, NextDay, Not, Or, Quarter, RuntimeReplaceable, SecondsOfTime, SecondsOfTimeWithFraction, Subtract, SubtractTimes, TimeAddInterval, TimeDiff, TimeTrunc, TruncDate, UnaryMinus, UnixDate, WeekDay, WeekOfYear, Year, YearOfWeek}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaDerivedKind, VarkaLoopEmitter, VarkaRangeAnalysis, VarkaValueRange, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaRangeAnalysis.{GuardPolicy, Kind}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays, AddMonths => IRAddMonths, And => IRAnd, ColumnRef, Compare, CompareOp, Cond, ConstDivide, DateDiff => IRDateDiff, DayOfMonth => IRDayOfMonth, DayOfWeek => IRDayOfWeek, DayOfWeekIso, DayOfYear => IRDayOfYear, Greatest => IRGreatest, GuardedDay, GuardedRange, IfElse, IntArith, IntNeg, IntOp, IsNotNull => IRIsNotNull, LaneType, LastDay => IRLastDay, Least => IRLeast, LiteralSlot, MakeDate => IRMakeDate, Month => IRMonth, NextDay => IRNextDay, Not => IRNot, Or => IROr, Overflow, Quarter => IRQuarter, SubDays, ThursdayOf, TruncDate => IRTruncDate, TruncDateDynamic => IRTruncDateDynamic, TruncLevel, WeekDay => IRWeekDay, WeekOfYear => IRWeekOfYear, Year => IRYear}
import org.apache.spark.sql.catalyst.expressions.objects.StaticInvoke
import org.apache.spark.sql.catalyst.util.{DateTimeConstants, DateTimeUtils}
import org.apache.spark.sql.types.{BooleanType, ByteType, DataType, DateType, DayTimeIntervalType, Decimal, DecimalType, IntegerType, LongType, StringType, TimestampNTZType, TimestampType, TimeType, YearMonthIntervalType}
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
    derivedInputs: Seq[VarkaDerivedInput] = Nil,
    longLiterals: Seq[Long] = Nil) {

  // A kernel is single-lane - every output root agrees, which the emitter enforces - so it reads
  // exactly one of the two literal tables. Both non-empty would mean an entry of the other lane
  // left its literals behind when it was demoted, which the per-entry rollback rules out.
  require(literals.isEmpty || longLiterals.isEmpty,
    "a kernel is single-lane, so at most one of its literal tables is populated")

  /** The lane every output root is on, and so the `run` overload the evaluator calls. */
  def lane: LaneType = outputs.head.laneType()

  /** The slot count of the one literal table this kernel reads - what the emitter is told. */
  def numLiterals: Int = literals.size + longLiterals.size

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
 * Collects what one entry's compilation leaves behind besides its IR: its decline, the input
 * bounds it asks the evaluator to check (see `PLAN_TASK_56.md`; keyed by child ordinal until the
 * entry is accepted, and dropped with a declining entry the way its columns and literals are), and
 * the long lane's literal table. The
 * recursion reports a decline at the point of failure and the first note wins, so the recorded
 * reason is the innermost cause rather than the outermost expression that inherited it; [[take]]
 * hands it over and resets for the next entry.
 *
 * The long literal table lives here rather than beside the int one in every signature because
 * every compile arm already carries the sink, and because it is the second half of one table
 * rather than a second table: a kernel is single-lane, so it reads the int slots or the long
 * slots and never both. It follows the bounds' rollback discipline - a mark before an entry, a
 * truncate when the entry declines.
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

  private val longLiterals = mutable.LinkedHashMap.empty[Long, Int]

  /** Interns `value` in the long lane's per-distinct-value table and wraps it as its slot. */
  def longSlot(value: Long): LiteralSlot =
    new LiteralSlot(longLiterals.getOrElseUpdate(value, longLiterals.size), LaneType.LONG)

  def longMark: Int = longLiterals.size

  /** Drops the long literals interned since `mark` - a declining entry's. */
  def truncateLong(mark: Int): Unit = {
    if (longLiterals.size > mark) {
      longLiterals.keys.drop(mark).toSeq.foreach(longLiterals.remove)
    }
  }

  /** The long literal table in slot order, for the compiled plan. */
  def longLiteralValues: Seq[Long] = longLiterals.keys.toSeq

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
 * `greatest`/`least`, `dayofweek`/`weekday` and date literals. The conditions also take `IN` over
 * date literals (capped, see [[MaxInLiterals]]) and the validity predicates `IS [NOT] NULL` over
 * bare columns, and the values with `coalesce`/`nvl`/`nvl2` (lowered onto the validity condition)
 * and the identity date cast. Used by both `VarkaColumnarRule` (is the projection eligible?) and
 * `VarkaKernelEvaluator` (what does the emitted loop compute?), so eligibility cannot drift from
 * execution: there is one compiler and the rule's question is `compilePartial(...).isDefined`.
 *
 * Eligibility is per entry, not all or nothing: [[compilePartial]] classifies every entry as fused,
 * forwarded (a bare column of any type, zero-copy) or residual (per-row), and the projection is
 * eligible when at least one entry fuses - a projection of forwards and residuals alone gains
 * nothing from Varka and stays on Janino untouched. [[compile]] remains as the all-entries-fused
 * special case for callers that need exactly that.
 *
 * The third entry point, [[compilePredicate]], is a filter condition compiled to a single condition
 * root - the selection mask the emitter writes as a bitmap - with the same per-part eligibility,
 * split on the predicate's `AND` spine instead of projection entries.
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
          val longMark = sink.longMark
          val boundsMark = sink.boundsMark
          compileNode(e, inputs, literals, sink) match {
            // One kernel holds one lane: its loop, its epilogue and its stores are one species.
            // The first fused entry fixes the lane and an entry of the other lane is demoted to
            // residual with a reason that says so - checked here, before the budgets, because
            // `fitsBudgets` would refuse the mix too but only answers yes or no, and a lane
            // mismatch reported as a budget breach sends a reader hunting a chain-depth problem
            // that is not there. Task 28's width conversion is what will let both lanes share a
            // tree; until then the mixed projection fuses one lane and leaves the other.
            case Some(ir) if outputs.nonEmpty && ir.laneType() != outputs.head.laneType() =>
              truncate(inputs, inputsMark)
              truncate(literals, literalsMark)
              sink.truncateLong(longMark)
              sink.truncateBounds(boundsMark)
              sink.take()
              sink.note(laneMismatch(ir.laneType(), outputs.head.laneType()), e)
              sink.take().foreach(decline => declines += position -> decline)
              ResidualOutput
            // An accepted entry must also fit the emitter's structural budgets together with the
            // entries accepted before it. The emitter enforces the same limits, but at emission
            // time, where a breach can only become a silent per-batch fallback - no decline reason,
            // and EXPLAIN still claims fusion. So the compiler mirrors them and demotes the
            // overflowing entry to residual.
            case Some(ir) if VarkaLoopEmitter.fitsBudgets((outputs :+ ir).asJava, inputs.size) =>
              sink.take()
              outputs += ir
              outputTypes += e.dataType
              fusedCount += 1
              FusedOutput(fusedCount - 1)
            case compiled =>
              truncate(inputs, inputsMark)
              truncate(literals, literalsMark)
              sink.truncateLong(longMark)
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
        sink.inputBounds(inputs), derived, sink.longLiteralValues),
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
      val longMark = sink.longMark
      val boundsMark = sink.boundsMark
      compileCond(bound, inputs, literals, sink) match {
        // The lane rule of `compilePartial`, for conjuncts: the AND fold below would refuse a
        // mix by construction, so it is asked here first and answered with the lane's reason.
        case Some(cond) if fusedConds.nonEmpty && cond.laneType() != fusedConds.head.laneType() =>
          truncate(inputs, inputsMark)
          truncate(literals, literalsMark)
          sink.truncateLong(longMark)
          sink.truncateBounds(boundsMark)
          sink.take()
          sink.note(laneMismatch(cond.laneType(), fusedConds.head.laneType()), bound)
          VarkaConjunctSpec(conjunct, fused = false, decline = sink.take())
        case Some(cond) if VarkaLoopEmitter.fitsBudgets(
            java.util.List.of(andFold(fusedConds.toSeq :+ cond)), inputs.size) =>
          sink.take()
          fusedConds += cond
          VarkaConjunctSpec(conjunct, fused = true, decline = None)
        case compiled =>
          truncate(inputs, inputsMark)
          truncate(literals, literalsMark)
          sink.truncateLong(longMark)
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
          ordinals, literals.keys.toSeq, sink.inputBounds(inputs), derived,
          sink.longLiteralValues)))
    } else {
      None
    }
  }

  /**
   * The lane a Spark type's values occupy in a kernel, or `None` for a type no kernel reads.
   * The int side is what the leaf arms below already admit - a date, an int and a year-month
   * interval are all one 32-bit lane - and the long side is milestone 5's: `bigint`, `TIME`
   * (nanoseconds of day) and a day-time interval (microseconds) are one 64-bit lane
   * (`PLAN_TASK_29.md` 3.1). The two timestamp types are that lane physically and are
   * deliberately absent: see `isTimestamp` and the arm that names them.
   */
  private def laneOf(dataType: DataType): Option[LaneType] = dataType match {
    case IntegerType | DateType | _: YearMonthIntervalType => Some(LaneType.INT)
    case LongType | _: TimeType | _: DayTimeIntervalType => Some(LaneType.LONG)
    case _ => None
  }

  /**
   * Whether the type is one of the two timestamps, which milestone 5 leaves out by decision
   * (`SCOPE_MILESTONE_6.md` item 31): a zoned `TIMESTAMP`'s differences and interval additions
   * are computed on local date-times in the session zone and are not lane arithmetic, and the
   * NTZ family, whose arithmetic would be plain, waits with it. The decline names the milestone
   * so EXPLAIN shows a decision rather than a gap.
   */
  private def isTimestamp(dataType: DataType): Boolean = dataType match {
    case TimestampType | TimestampNTZType => true
    case _ => false
  }

  private val timestampOutOfMilestone = "a timestamp column is outside milestone 5"

  /**
   * Every `TIME` expression Spark has, keyed by the `StaticInvoke` it actually arrives as.
   *
   * <p>None of them reaches this compiler under its own class name. All nine are
   * `RuntimeReplaceable` and rewrite themselves into a `StaticInvoke` on `DateTimeUtils` before
   * physical planning, so an arm matching `case HoursOfTime(child)` would never fire in a real
   * query - the optimizer's `ReplaceExpressions` has long since run.
   *
   * <p>The key is not written down. Each expression is constructed once here and asked for its
   * own `replacement`, and the `(staticObject, functionName)` pair is read off that. Both sides
   * therefore move together: if upstream renames `getHoursOfTime`, this table renames with it,
   * where a hardcoded string would have stopped matching silently and left nothing behind but a
   * benchmark that got slower. `PLAN_TASK_102.md` 2.1 is the argument; `VarkaTimeTargetsSuite`
   * is the check that the table still describes what Spark produces for real SQL.
   *
   * <p>A replacement that stops being a `StaticInvoke` fails here, at class initialisation,
   * rather than disappearing from the table unnoticed.
   */
  private[codegen] val timeTargets: Map[(Class[_], String), String] = {
    val t = Literal.create(0L, TimeType(TimeType.MICROS_PRECISION))
    val i = Literal.create(0, IntegerType)
    val d = Literal.create(Decimal(0), DecimalType(16, 6))
    val dt = Literal.create(0L, DayTimeIntervalType())
    val u = Literal.create(UTF8String.fromString("HOUR"), StringType)
    Seq[(Expression, String)](
      HoursOfTime(t) -> "hour(t)",
      MinutesOfTime(t) -> "minute(t)",
      SecondsOfTime(t) -> "second(t)",
      SecondsOfTimeWithFraction(t) -> "second(t) with its fraction",
      MakeTime(i, i, d) -> "make_time",
      TimeTrunc(u, t) -> "time_trunc",
      SubtractTimes(t, t) -> "t1 - t2",
      TimeDiff(u, t, t) -> "timediff",
      TimeAddInterval(t, dt) -> "t + interval").map {
      case (e, label) =>
        e.asInstanceOf[RuntimeReplaceable].replacement match {
          case si: StaticInvoke => (si.staticObject, si.functionName) -> label
          case other =>
            throw new IllegalStateException(
              s"$label no longer replaces into a StaticInvoke but into " +
                s"${other.getClass.getSimpleName}; VarkaExpressionCompiler.timeTargets must be " +
                "rewritten rather than quietly stop matching")
        }
    }.toMap
  }

  /**
   * The reason a `TIME` expression declines, naming the expression rather than reporting it as
   * unsupported.
   *
   * <p>Task 102 lowers these one group at a time, and the difference between "not lowered yet"
   * and "unsupported" is what tells a reader which. The same distinction task 89 drew for
   * `extract(MONTH FROM ym)`, where a bare decline would have suggested the division was still
   * missing when the output type was the blocker.
   */
  private def timeNotLoweredYet(label: String): String =
    s"$label is a TIME expression Varka does not lower yet (task 102)"

  /**
   * The lowerings of the `TIME` expressions, keyed on the `DateTimeUtils` method each one's
   * replacement invokes - which is the one name that survives the optimizer (see
   * `timeTargets`). The arithmetic is read off `DateTimeUtils` itself, not off the expression:
   *
   * {{{
   *   subtractTimes(end, start) = (end - start) / NANOS_PER_MICROS
   *   timeDiff(unit, start, end) = (end - start) / nanosPerUnit(unit)
   *   timeTrunc(level, nanos)    = nanos truncatedTo level, i.e. (nanos / u) * u
   * }}}
   *
   * Every one is a same-lane subtraction or a constant division, and every dividend is
   * bounded by construction: a `TIME` is nanoseconds of day, below 2^47, and the difference of
   * two stays below 2^47 in magnitude. That is far inside `ConstDivide.EXACT_DIVIDEND_BOUND`,
   * so the division is exact and no per-batch bound is needed - the one place in the lane's
   * arithmetic where the bound is a property of the type rather than of the data. The
   * subtraction cannot overflow for the same reason, so it wraps; the truncating multiply's
   * product is at most its dividend.
   *
   * A non-literal unit or level declines: the divisor is part of the kernel's shape, and a
   * column of unit names would need a kernel per distinct value. `trunc(d, fmt)` made the same
   * choice for the date lane.
   */
  private def compileTime(
      si: StaticInvoke,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    val label = timeTargets((si.staticObject, si.functionName))
    def long(e: Expression): Option[VarkaVectorIR] =
      compileNode(e, inputs, literals, sink).filter { ir =>
        // The arguments are TIME and interval columns, literals and their widening casts, all
        // of which the leaf arms above put on the long lane; anything else declined already.
        ir.laneType() == LaneType.LONG
      }
    def literalUnit(e: Expression, table: Map[String, Long], what: String): Option[Long] =
      e match {
        case Literal(u: UTF8String, _) =>
          val found = table.get(u.toString.toUpperCase(Locale.ROOT))
          if (found.isEmpty) sink.note(s"$label: unknown $what '$u'", si)
          found
        case other =>
          sink.note(s"$label: the $what is not a literal, and the divisor is part of the " +
            "kernel's shape", other)
          None
      }
    (si.functionName, si.arguments) match {
      case ("subtractTimes", Seq(end, start)) =>
        for (e <- long(end); st <- long(start)) yield
          new ConstDivide(new IntArith(IntOp.SUB, Overflow.WRAP, e, st),
            DateTimeConstants.NANOS_PER_MICROS)
      case ("timeDiff", Seq(unit, start, end)) =>
        for {
          nanos <- literalUnit(unit, nanosPerTimeUnit, "unit")
          e <- long(end)
          st <- long(start)
        } yield new ConstDivide(new IntArith(IntOp.SUB, Overflow.WRAP, e, st), nanos)
      case ("timeTrunc", Seq(level, time)) =>
        for {
          unit <- literalUnit(level, nanosPerTimeUnit, "level")
          t <- long(time)
        } yield new IntArith(IntOp.MUL, Overflow.WRAP, new ConstDivide(t, unit),
          sink.longSlot(unit))
      // timeAddInterval(t, p, dt, endField, target): addExact(t, multiplyExact(dt, 1000)),
      // thrown out of if the sum leaves [0, NANOS_PER_DAY), then truncated to `target` digits.
      // Two guards make the lane's wrapping arithmetic exact and the throw a decline. The
      // interval is held to one day either way first: any |dt| beyond that puts every t's sum
      // outside the day, so Spark throws on every such row and the row engine may as well
      // raise it; inside it, dt * 1000 and the sum both stay under 2^48 and cannot overflow.
      // The sum is then held to the day, which is the throw itself. The precision truncation
      // is the identity and is not emitted - see `timeAddIntervalTruncates`, which proves it.
      case ("timeAddInterval",
          Seq(time, Literal(_, IntegerType), interval, Literal(_, ByteType),
            Literal(target: Int, IntegerType))) =>
        if (timeAddIntervalTruncates(time.dataType, interval.dataType, target)) {
          // Not reachable for any type Spark admits today; a decline rather than a wrong
          // answer if that ever changes.
          sink.note(s"$label: the precision truncation is not the identity for these types",
            si)
          return None
        }
        // A literal interval's guard is decided here rather than per lane: one outside a day
        // crosses midnight for every time, which is the row engine's error to raise for every
        // row, and one inside it is already nanoseconds the kernel can add. A column takes the
        // guard and the multiply.
        // A def, not a val: the time is compiled first, so the inputs keep the expression's
        // argument order, as every other lowering's do.
        def nanos: Option[VarkaVectorIR] = interval match {
          case Literal(micros: Long, _: DayTimeIntervalType) =>
            if (math.abs(micros) > DateTimeConstants.MICROS_PER_DAY) {
              sink.note(s"$label: the interval is longer than a day, so every time crosses " +
                "midnight and the row engine raises the error", si)
              None
            } else {
              Some(sink.longSlot(micros * DateTimeConstants.NANOS_PER_MICROS))
            }
          case _ =>
            long(interval).map { dt =>
              val micros = new GuardedRange(dt, -DateTimeConstants.MICROS_PER_DAY,
                DateTimeConstants.MICROS_PER_DAY)
              new IntArith(IntOp.MUL, Overflow.WRAP, micros,
                sink.longSlot(DateTimeConstants.NANOS_PER_MICROS))
            }
        }
        for (t <- long(time); n <- nanos) yield
          new GuardedRange(new IntArith(IntOp.ADD, Overflow.WRAP, t, n), 0L,
            DateTimeConstants.NANOS_PER_DAY - 1)
      case _ =>
        sink.note(timeNotLoweredYet(label), si)
        None
    }
  }

  /**
   * Whether `truncateTimeToPrecision(sum, target)` inside `timeAddInterval` can change the sum,
   * which decides whether the lowering must emit it. It cannot, for every input type Spark
   * admits, and this is the argument the kernel rests on rather than a re-derivation per call:
   * the time is a multiple of 10^(9 - p) by its type, and the interval in nanoseconds is a
   * multiple of 10^3 - or of a whole minute when its end field is coarser than SECOND. The
   * target is max(p, 6) in the first case and p in the second, and in both the sum is a
   * multiple of 10^(9 - target), which is exactly what the truncation removes nothing from.
   * Kept as a function so the claim is checked against the types at compile time and a
   * future TimeType or interval that breaks the argument refuses to lower rather than lowering
   * wrongly.
   */
  private[codegen] def timeAddIntervalTruncates(
      time: DataType, interval: DataType, target: Int): Boolean = {
    val p = time.asInstanceOf[TimeType].precision
    val endField = interval.asInstanceOf[DayTimeIntervalType].endField
    val sumGranularity = if (endField < DayTimeIntervalType.SECOND) {
      // Whole minutes at least: 6e10 nanoseconds, which every 10^(9 - p) divides.
      math.min(9 - p, 10)
    } else {
      // Microseconds: 10^3 nanoseconds.
      math.min(9 - p, 3)
    }
    // The truncation is the identity iff the sum's granularity is at least the target's.
    sumGranularity < 9 - target
  }

  /**
   * The nanoseconds in each unit `time_diff` and `time_trunc` accept, spelled as
   * `DateTimeUtils.getNanosPerTimeUnit` and `parseTimeTruncLevel` spell them - the same five
   * names, and nothing coarser than an hour, because a `TIME` has no day.
   */
  private val nanosPerTimeUnit: Map[String, Long] = Map(
    "MICROSECOND" -> DateTimeConstants.NANOS_PER_MICROS,
    "MILLISECOND" -> DateTimeConstants.NANOS_PER_MILLIS,
    "SECOND" -> DateTimeConstants.NANOS_PER_SECOND,
    "MINUTE" -> DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE,
    "HOUR" -> DateTimeConstants.NANOS_PER_SECOND * DateTimeConstants.SECONDS_PER_MINUTE
      * DateTimeConstants.MINUTES_PER_HOUR)

  /** The reason an entry of one lane records when the kernel is already on the other. */
  private def laneMismatch(entry: LaneType, kernel: LaneType): String =
    s"the $entry lane in a kernel on the $kernel lane: one kernel holds one lane"

  /**
   * Whether `nodes` share a lane, noting the mismatch against `whole` when they do not. Every
   * IR node whose operands may disagree - the connectives, the blend - refuses a mix in its
   * constructor, so this is asked first wherever Spark's typing does not already force the
   * agreement: `CASE WHEN l > 0 THEN d ELSE d2` type-checks, and its condition is on the long
   * lane while its branches are on the int one.
   */
  private def sameLane(whole: Expression, sink: DeclineSink, nodes: VarkaVectorIR*): Boolean = {
    val lanes = nodes.map(_.laneType()).distinct
    if (lanes.size <= 1) {
      true
    } else {
      sink.note(s"one kernel holds one lane, and this mixes the ${lanes(0)} and ${lanes(1)} lanes",
        whole)
      false
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
    // A year-month interval column, on the same lane. Its value is a count of months in every unit,
    // so nothing about the lowering changes; what makes widening the leaf safe rather than "do not
    // open it wider" is that Spark's own typing decides where the value may appear. An interval
    // only type-checks into DateAddYMInterval, the ordered comparisons and IN, the same-typed
    // Least/Greatest/Coalesce/If/CaseWhen, and Cast - never into date_add's offset, datediff, a
    // calendar extraction or AddMonths' date operand, all of which are typed DateType or
    // IntegerType. So an interval in a date position is a type error the analyzer rejected before
    // the compiler ran, and the leaf cannot put one there.
    case br: BoundReference if br.dataType.isInstanceOf[YearMonthIntervalType] =>
      Some(columnRef(br, inputs))
    // The long lane's column leaf: a `bigint`, a `TIME(p)` and a day-time interval are one
    // eight-byte lane, holding the value, nanoseconds of day and microseconds respectively (task
    // 29). As with the interval leaf above, Spark's own typing decides where such a value may
    // appear - never in a date or an int position - so the leaf cannot put one there, and the
    // IR's constructors refuse a tree that mixes it with the int lane anyway.
    case br: BoundReference if laneOf(br.dataType).contains(LaneType.LONG) =>
      Some(columnRef(br, inputs, LaneType.LONG))
    // Ahead of the generic "non-date column" decline below, so the reason is the decision.
    case br: BoundReference if isTimestamp(br.dataType) =>
      sink.note(timestampOutOfMilestone, br)
      None
    // The long lane's literals, beside the int ones: the value is already the long the lane
    // holds, so `l > 5000000000`, `t < TIME'12:00'` and `dt > INTERVAL '1' DAY` take a slot.
    case Literal(v: Long, t) if laneOf(t).contains(LaneType.LONG) =>
      Some(sink.longSlot(v))
    // TIME's precision cast. A `TIME(p)` value is stored truncated to `p` digits and
    // `Cast.castToTime` truncates again to the target precision, so a cast to an equal or wider
    // precision - the one type coercion inserts when two precisions meet in a comparison -
    // returns its operand unchanged and compiles to the child, as the year-month MONTH relabel
    // does above. Narrowing drops digits, which is a floor division the lane has no exact form
    // of yet (task 88); it declines with its reason rather than falling through as unsupported.
    case Cast(child, TimeType(to), _, _)
        if child.dataType.isInstanceOf[TimeType]
          && to >= child.dataType.asInstanceOf[TimeType].precision =>
      compileNode(child, inputs, literals, sink)
    case c @ Cast(child, _: TimeType, _, _) if child.dataType.isInstanceOf[TimeType] =>
      sink.note("TIME narrowed to a lower precision, which truncates", c)
      None
    // The day-time interval's unit relabel, the twin of the year-month MONTH arm above: type
    // coercion casts `INTERVAL '0' SECOND` to the column's DAY TO SECOND before comparing, and
    // `castToDayTimeInterval` keeps the microseconds whole for a SECOND end field
    // (`SparkIntervalUtils.durationToMicros`), so the cast is the child. A coarser end field
    // truncates to that unit - `micros - micros % unit`, a division - and declines with its
    // reason rather than falling through as unsupported.
    case Cast(child, DayTimeIntervalType(_, DayTimeIntervalType.SECOND), _, _)
        if child.dataType.isInstanceOf[DayTimeIntervalType] =>
      compileNode(child, inputs, literals, sink)
    case c @ Cast(child, _: DayTimeIntervalType, _, _)
        if child.dataType.isInstanceOf[DayTimeIntervalType] =>
      sink.note("day-time interval narrowed to a coarser end field, which truncates", c)
      None
    // The interval literal, beside the date literal and for the same reason: the value is
    // already the int the lane holds, so `ym > INTERVAL '6' MONTH` and
    // `coalesce(ym, INTERVAL '0' MONTH)` become a slot rather than a decline.
    case Literal(months: Int, _: YearMonthIntervalType) =>
      Some(intSlot(months, literals))
    // A date literal's value is already an epoch-day int, so it takes a slot in the shared
    // per-distinct-value table like a folded day offset does - what makes
    // `d < DATE'...'` and `greatest(d, DATE'...')` reachable at all. `days: Int` does not
    // match a null-valued Literal, which falls through to the catch-all below; that is a
    // safe blind spot, not a bug, since ConstantFolding removes a null date literal from any
    // real query before it can reach here (unix_date/date_from_unix_date add two more
    // recursive paths into this same match, both equally covered by that guarantee).
    case Literal(days: Int, DateType) =>
      Some(intSlot(days, literals))
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
    // The interval relabels, on `unix_date`'s pattern above: a cast that returns its operand
    // unchanged is the child alone, with no node emitted. `intToYearMonthInterval` returns `v` for
    // a MONTH end field and `yearMonthIntervalToInt` returns `v` for a MONTH-ended interval, so
    // both directions of the MONTH unit are the identity on the lane; only the Spark type on the
    // outside differs, and that rides on `outputTypes`. The YEAR unit is neither direction's
    // identity - it multiplies or divides by twelve. Its outbound half is the arm below; its
    // inbound half, `CAST(ym AS INT)` over a YEAR-ended interval, is a division by twelve, which is
    // not supported yet - it belongs with the year-month extracts.
    case Cast(child, YearMonthIntervalType(_, YearMonthIntervalType.MONTH), _, _)
        if child.dataType == IntegerType =>
      compileIntOperand(child, "the month count", inputs, literals, sink)
    // The unit relabel between two year-month intervals, which is not a cast a user writes but the
    // one type coercion inserts whenever two units meet - `ymm + ymy` widens both operands to YEAR
    // TO MONTH before the add. `Cast.castToYearMonthInterval` computes
    // `periodToMonths(monthsToPeriod(v), endField)`, which splits the count into whole years and a
    // remainder and puts it back together: exactly `v` again for a MONTH end field, at every int
    // including `Int.MinValue`, since the reassembly's `multiplyExact` is over `v / 12`. So this
    // direction emits nothing and only `outputTypes` moves. The YEAR-ended direction drops the
    // remainder, which is a division by twelve, and declines below.
    case Cast(child, YearMonthIntervalType(_, YearMonthIntervalType.MONTH), _, _)
        if child.dataType.isInstanceOf[YearMonthIntervalType] =>
      compileNode(child, inputs, literals, sink)
    // `CAST(i AS INTERVAL YEAR)` in a value position, which is `12 * i` with an interval output.
    // `IntervalUtils.intToYearMonthInterval` computes it with `Math.multiplyExact` whatever the
    // session's ANSI mode, so the multiply is checked and the bound is the only thing that removes
    // it. This is the same expression `compileMonths` admits in `add_months`' month-count position;
    // the difference is that there the emitter has a shape check to satisfy and here it has none,
    // which is why `PLAN_TASK_67.md` 2.1 - written about `compileMonths` - reads as if the whole
    // cast were blocked when only that position was.
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
    case CaseWhen(branches, elseValue) =>
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
    // else: integer arithmetic over an output is out of scope for this compiler. Int32 //
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
    // spelling for an interval - so the mode is `FAIL` and the bound is the only thing that takes
    // the check off.
    case a: Add if a.dataType.isInstanceOf[YearMonthIntervalType] =>
      intervalArith(IntOp.ADD, a.left, a.right, a, inputs, literals, sink)
    case a: Subtract if a.dataType.isInstanceOf[YearMonthIntervalType] =>
      intervalArith(IntOp.SUB, a.left, a.right, a, inputs, literals, sink)
    case n @ UnaryMinus(c, _) if n.dataType.isInstanceOf[YearMonthIntervalType] =>
      // `IntervalMathUtils.negateExact`, which throws on `Int.MinValue` alone, so any bound at
      // all rules it out - `IntNeg`'s reasoning over an interval operand.
      intervalOperand(c, "the negated interval", inputs, literals, sink).map { x =>
        val checked = !magnitude(x, literals).exists(_ <= Int.MaxValue.toLong)
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
        val zero = intSlot(0, literals)
        val checked = !magnitude(x, literals).exists(_ <= Int.MaxValue.toLong)
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
    case e @ ExtractANSIIntervalYears(iv) =>
      // `IntervalUtils.getYears(months)` is `months / 12` - Java's `/`, truncating toward zero -
      // over a stored month count that nothing bounds. The int-lane magic multiply the calendar
      // uses is exact over 0..49,151, about one forty-thousandth of the type, so this is the
      // first division Varka emits through the double lane instead: exact for every int32, and
      // truncating already, so it needs neither a range guard nor a correction step.
      // `sql/varka/plans/verify_ym_division.py` checks both claims over all 2^32 month counts.
      intervalOperand(iv, "the interval", inputs, literals, sink).map(new ConstDivide(_, 12))
    case e @ ExtractANSIIntervalMonths(iv) =>
      // `(months % 12).toByte`. The remainder is one multiply and one subtract away from the
      // quotient above, so the division is not what blocks this: the result is a `ByteType`, and
      // Varka has neither a byte lane nor an Arrow vector to store one into. It declines until
      // a narrowing store exists, which is its own question (PLAN_MILESTONE_5.md 2.20).
      sink.note("extract(MONTH FROM ym) returns a byte, which has no lane", e)
      None
    case m @ MultiplyYMInterval(iv, num) =>
      // `Math.multiplyExact(months, num)` for the int-family arms. Both operands have to be
      // bounded before the check comes off, and a stored interval column never is, so a
      // literal multiplier alone does not buy it: `ym * 2` declines, while
      // `make_ym_interval(year(d), month(d)) * 2` fuses over a bounded interval. The `Long`,
      // `Decimal` and `Double` arms are not int32 lanes and decline by type rather than
      // reaching `intOperand`, which would report them as
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
        val checked = failOnError && !magnitude(x, literals).exists(_ <= Int.MaxValue.toLong)
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
      } yield new IRNextDay(d, intSlot(k, literals))
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
            val week = intSlot(7, literals)
            // next_day's slot holds dayOfWeek - 1; Monday through the same parser
            // foldWeekday uses, so the constant is the definition's, not a retyped 3.
            val monday = intSlot(
              DateTimeUtils.getDayOfWeekFromString(UTF8String.fromString("MONDAY")) - 1, literals)
            new IRNextDay(new SubDays(d, week), monday)
          }
      }
    // A format column: the level is read per batch by the evaluator's derived leaf
    // (TruncLevelLeaf) into an int32 column of parseTruncLevel's codes, on next_day's pattern,
    // and the kernel computes every period and selects on it. No ANSI twin in the
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
    // A TIME expression, which arrives as the StaticInvoke its RuntimeReplaceable rewrote itself
    // into (see `timeTargets`). The lowered ones are matched by the method they invoke; the
    // rest decline by name through the same table.
    case si: StaticInvoke if timeTargets.contains((si.staticObject, si.functionName)) =>
      compileTime(si, inputs, literals, sink)
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

  /** The `extract(DAYOFWEEK_ISO)` shape, which keeps its own node rather than becoming
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
      Some(intSlot(v, literals))
    case _ if e.dataType != IntegerType =>
      sink.note(s"int arithmetic operand of type ${e.dataType.simpleString}", e)
      None
    case _ => compileNode(e, inputs, literals, sink)
  }

  /**
   * The literal table as [[VarkaRangeAnalysis]] reads it: slot index to value. The table is keyed
   * by value in slot order, so a slot's value is its key's position; and it is read at call time,
   * never snapshotted, because the table grows as compilation proceeds and is truncated on every
   * decline.
   */
  private def literalAt(literals: mutable.LinkedHashMap[Int, Int]): IntUnaryOperator =
    slot => literals.keysIterator.drop(slot).next()

  /**
   * How large an int-valued node's result can be in absolute value, or `None` where nothing
   * bounds it: [[VarkaRangeAnalysis]]'s `INT` query. This exists so a checked operation that
   * provably cannot overflow needs no check - which is what makes `year(d) * 100 + month(d)`
   * fuse under ANSI, the shape `PLAN_TASK_63.md` 6 measures. The compiler can do this and the
   * emitter cannot: a `LiteralSlot` carries a slot index, and the value behind it only arrives in
   * `scalarArgs` at run time. Conservative by construction: a `None` costs a check or a decline
   * and never a wrong answer.
   */
  private def magnitude(
      node: VarkaVectorIR, literals: mutable.LinkedHashMap[Int, Int]): Option[Long] = {
    val m = VarkaRangeAnalysis.magnitude(node, literalAt(literals))
    if (m.isPresent) Some(m.getAsLong) else None
  }

  /**
   * The epoch days a day-valued node can produce: [[VarkaRangeAnalysis]]'s `DAY` query, under
   * `ARMED` for a calendar consumer - which arms the runtime guard on every column-offset
   * producer below it - and `NONE` for anything else.
   */
  private def dayRange(node: VarkaVectorIR, literals: mutable.LinkedHashMap[Int, Int],
      policy: GuardPolicy): VarkaValueRange.Range =
    VarkaRangeAnalysis.range(node, Kind.DAY, policy, literalAt(literals))

  /**
   * Whether every day in the interval decomposes exactly. Asymmetric on purpose. Downward,
   * `NARROW_MIN_DAYS` binds: below it the narrowing is undefined and no correction rescues it.
   * Upward, the binding limit is not `NARROW_MAX_DAYS` - that is the era step's *shift* domain and
   * the range the runtime guards enforce on a producer's own result - but how far the
   * decomposition stays exact on a value already in hand, which `eraOf`'s one-era correction
   * carries about 9,266 years further. So an upward shift over a guarded day producer, which used
   * to decline conservatively, is admitted where it is genuinely exact.
   */
  private def decomposesExactly(b: VarkaValueRange.Bounded): Boolean =
    b.within(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_DECOMPOSE_MAX_DAYS)

  /**
   * Whether the operation on operands of these bounds cannot leave the int32 range. Read
   * through the analysis's own `IntArith` transfer function rather than re-dispatched here: the
   * candidate node is never emitted, so building one to ask the question is free, and the two
   * answers cannot drift apart the way two copies of "MUL multiplies, else adds" once could. A
   * magnitude is non-negative by construction, so the only thing left to ask is whether it stays
   * at or under `Int.MaxValue` - one past it, `2^31`, is the first magnitude that overflows.
   */
  private def cannotOverflow(op: IntOp, l: VarkaVectorIR, r: VarkaVectorIR,
      literals: mutable.LinkedHashMap[Int, Int]): Boolean =
    magnitude(new IntArith(op, Overflow.WRAP, l, r), literals).exists(_ <= Int.MaxValue.toLong)

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
    intSlot(12, literals)

  /**
   * Interns `value` into the per-distinct-value literal table and wraps it as a `LiteralSlot` on
   * the int lane. Every folded constant the compiler admits is an int - a day count, a month
   * count, a date's epoch day - so this is the one place a literal's lane is chosen, as
   * `columnRef` is for a column's.
   */
  private def intSlot(value: Int, literals: mutable.LinkedHashMap[Int, Int]): LiteralSlot =
    new LiteralSlot(literals.getOrElseUpdate(value, literals.size), LaneType.INT)

  /**
   * Interns `br`'s ordinal into `inputs` and wraps it as a `ColumnRef` on `lane` - shared by
   * `compileNode`'s date, interval and long leaves and `compileOffset`'s `IntegerType` one.
   */
  private def columnRef(
      br: BoundReference,
      inputs: mutable.LinkedHashMap[Int, Int],
      lane: LaneType = LaneType.INT): ColumnRef =
    new ColumnRef(inputs.getOrElseUpdate(br.ordinal, inputs.size), lane)

  /**
   * `columnRef`'s twin for an input the evaluator derives from `br`: interned under
   * `VarkaDerivedInput.key` beside the child ordinals, so it takes the next kernel input index
   * and shares the table's rollback.
   */
  private def derivedRef(br: BoundReference, kind: VarkaDerivedKind,
      inputs: mutable.LinkedHashMap[Int, Int]): ColumnRef =
    new ColumnRef(
      inputs.getOrElseUpdate(VarkaDerivedInput.key(br.ordinal, kind), inputs.size), LaneType.INT)

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
      Some(intSlot(v, literals))
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
        Some(intSlot(offset, literals))
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
          // Arithmetic over an int column as the offset, `date_add(d, i * 7)`. What makes this safe
          // above rather than only here is `dayRange`, which reads any non-literal offset as a
          // column shift: a calendar node over such a producer still gets the runtime range guard,
          // exactly as it does for a bare column offset. Only the four arithmetic shapes, not every
          // `IntegerType` expression - the emitter's own check on this operand admits the same
          // three node kinds and nothing else, so the two stay a matched pair rather than one
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
   * The month count of `add_months`/`date +- INTERVAL n MONTH/YEAR`: a foldable count folds to a
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
        Some(intSlot(m, literals))
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
          // `d - ym_col`, which the analyzer spells `DateAddYMInterval(d, UnaryMinus(ym))`, so the
          // count is a negation of an interval column. Its check comes off wherever a negation's
          // does, which is any bound at all - and a bare interval column has none, so this keeps
          // its check and is guarded on the count's value at run time exactly as a plain column
          // count is.
          case u @ UnaryMinus(operand, _)
              if operand.dataType.isInstanceOf[YearMonthIntervalType] =>
            intervalOperand(operand, "the negated month count", inputs, literals, sink).map { x =>
              val checked = !magnitude(x, literals).exists(_ <= Int.MaxValue.toLong)
              new IntNeg(if (checked) Overflow.FAIL else Overflow.WRAP, x)
            }
          // `d + ym_col`. The stored value is the month count in every unit, so this is the
          // column-count `AddMonths` exactly, with the same runtime guard on the count's lanes -
          // the guard reads the value and not the column's Spark type. It declined until now only
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
  private case class Rearmed(node: VarkaVectorIR, range: VarkaValueRange.Range, runtime: Boolean)

  /**
   * Insert [[GuardedDay]] wherever the running interval would leave the range the calendar
   * lowering decomposes exactly, resetting the interval there.
   *
   * <p>The producer guard covers one producer, and promises the whole narrowed range - so a second
   * guarded shift above it has no budget left and [[admitCalendar]] must decline the expression,
   * although both shifts are individually fine. Re-arming spends the range again: at a node whose
   * interval overflows, the emitted check makes everything above it start from `[NARROW_MIN_DAYS,
   * NARROW_MAX_DAYS]` once more.
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
    dayRange(rebuilt, literals, GuardPolicy.ARMED) match {
      case b: VarkaValueRange.Bounded if ownRuntime && !decomposesExactly(b) =>
        Rearmed(new GuardedDay(rebuilt), VarkaRangeAnalysis.NARROW, runtime = false)
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
    dayRange(node, literals, GuardPolicy.ARMED) match {
      case b: VarkaValueRange.Bounded if decomposesExactly(b) => Some(node)
      case b: VarkaValueRange.Bounded =>
        // The interval ran out, but if a runtime-valued shift is what carried it out then a check
        // can spend it again. `rearm` rewrites the subtree with the checks in it and answers the
        // interval that results; only a literal overflow, which no check can rescue, still
        // declines.
        val fixed = rearm(node, literals)
        fixed.range match {
          case f: VarkaValueRange.Bounded if decomposesExactly(f) => Some(fixed.node)
          case _ =>
            sink.note(s"day range [${b.lo}, ${b.hi}] leaves the calendar lowering's range",
              calendar)
            None
        }
      case _: VarkaValueRange.Unknown =>
        sink.note("day producer the calendar range analysis does not bound", calendar)
        None
      // The range type is sealed in Java, which Scala cannot see; a third member would land
      // here, and answering for it would be a wrong answer rather than a decline.
      case other => throw new IllegalStateException(s"unexpected range $other")
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
        if sameLane(expr, sink, left, right)
      } yield new IRAnd(left, right)
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
