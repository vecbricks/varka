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

import java.util.function.IntUnaryOperator

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.apache.spark.sql.catalyst.expressions.{Add, Alias, And, Attribute, BindReferences,
  BoundReference, Cast, EvalMode, Expression, Greatest, Least, Literal, Multiply, NamedExpression,
  RuntimeReplaceable, Subtract, UnaryMinus}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaDerivedKind, VarkaEmitDeclined,
  VarkaEmitOptions, VarkaLoopEmitter, VarkaRangeAnalysis, VarkaShapeCache, VarkaShapeKey,
  VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{ColumnRef, Cond,
  Greatest => IRGreatest, IntArith, IntNeg, IntOp, LaneType, Least => IRLeast, LiteralSlot,
  Or => IROr, Overflow}
import org.apache.spark.sql.catalyst.expressions.objects.StaticInvoke
import org.apache.spark.sql.types.{BooleanType, DataType, DateType, DayTimeIntervalType,
  IntegerType, LongType, TimeType, YearMonthIntervalType}

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

  /**
   * The lane the kernel's loop runs at, and so the `run` overload the evaluator calls: every
   * root's emission lane, which is the root's own except for a narrowing root, whose 32-bit
   * column is computed in the 64-bit lane (`VarkaVectorIR.emissionLane`).
   */
  def lane: LaneType = VarkaVectorIR.emissionLane(outputs.head)

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
 *
 * It also carries the one compile option the condition arms read, `rangeSets`, for the same
 * reason as the long table: every arm already has the sink in hand.
 */
private final class DeclineSink(childOutput: Seq[Attribute], val rangeSets: Boolean) {
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
 * kernel. Its outputs are condition roots, each a selection bitmap, and each `outputTypes`
 * entry is `BooleanType` as a description only, since a selection bitmap never allocates an
 * output vector. The split mirrors [[PartialVarkaProjection]]'s per-entry eligibility: a mixed
 * `WHERE` fuses what it can, and the rule keeps the residual conjuncts in a row `FilterExec`
 * above the Varka node.
 *
 * `clauses` says how the outputs make the selection: a row is selected when, in every clause,
 * at least one of the clause's outputs selects it. Usually there is one output, the fused
 * conjuncts recombined into one root, and one clause holding it. A predicate that one method
 * cannot hold is split under `splitConditions` (see [[VarkaExpressionCompiler.compilePredicate]])
 * into several conjunction roots, each its own clause, and a disjunction too large alone into
 * several partial roots in one clause. Kleene logic allows both at the mask: a row is known
 * true for `a AND b` exactly when it is known true for both, and for `a OR b` exactly when it
 * is known true for either.
 */
private[sql] case class CompiledVarkaPredicate(
    specs: Seq[VarkaConjunctSpec],
    fused: CompiledVarkaProjection,
    clauses: Seq[Seq[Int]] = Seq(Seq(0))) {

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
   * levels), and its 31 op nodes left half the emitter's `MAX_FUSED_NODES` = 64 budget to
   * the rest of the projection when that cap bounded every kernel; under the byte budget it
   * bounds only the reference form, and the choice of 16 stands on the depth argument. (The
   * emitter's broadcast hoist is NOT part of the basis: its gate counts the kernel's total
   * literal slots, so a capped IN plus any other
   * literal already re-broadcasts inline - the review pass corrected an earlier claim
   * here.) Above the cap the entry declines with a reason instead of silently losing the
   * whole kernel at emission.
   */
  private[codegen] val MaxInLiterals = 16

  /** The all-entries-fused special case of [[compilePartial]], kept for callers that need it. */
  def compile(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Option[CompiledVarkaProjection] = {
    compilePartial(projectList, childOutput, options).collect {
      case partial if partial.specs.forall(_.isInstanceOf[FusedOutput]) => partial.fused
    }
  }

  /**
   * Classifies every projection entry (see [[VarkaOutputSpec]]) and compiles the fused entries
   * into one sub-projection. `Some` exactly when at least one entry fused and the fused trees
   * reference at least one column - the emitted loop reads columns or has nothing to
   * vectorize over.
   *
   * `options` are the emit options the kernel will be emitted with, which every caller has to
   * pass alike: they decide whether the emitter can serve the fused entries in bytes (see
   * [[admitBySize]]), so a caller that passed different ones could classify the same
   * projection differently from the evaluator that runs it.
   */
  def compilePartial(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Option[PartialVarkaProjection] = {
    classify(projectList, childOutput, options)._1
  }

  /**
   * Why each declining entry declined, by position - including when nothing fused, which
   * [[compilePartial]] reports as a bare `None`. For the tools that explain a projection rather
   * than run it.
   */
  private[sql] def declines(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Map[Int, VarkaDecline] = {
    classify(projectList, childOutput, options)._2
  }

  /**
   * The per-entry classification, then the size admission: the entries the weight caps admit
   * are asked of the emitter as the one kernel they make, and an entry the emitter declines in
   * bytes is classified again as residual, with the emitter's reason, until the kernel it leaves
   * is one the emitter serves. Each round demotes at least one entry, so it ends.
   */
  private def classify(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions): (Option[PartialVarkaProjection], Map[Int, VarkaDecline]) = {
    var demoted = Map.empty[Int, String]
    while (true) {
      val classified = classifyOnce(projectList, childOutput, demoted, options)
      val more = classified._1.map { partial =>
        val fusedAt = partial.specs.zipWithIndex.collect { case (FusedOutput(i), at) => i -> at }
        admitBySize(partial.fused, options).map { case (outputs, reason) =>
          outputs.map(fusedAt.toMap).map(_ -> reason).toMap
        }.getOrElse(Map.empty[Int, String])
      }.getOrElse(Map.empty[Int, String])
      if (more.isEmpty) {
        return classified
      }
      demoted ++= more
    }
    throw new IllegalStateException("unreachable")
  }

  private def classifyOnce(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      demoted: Map[Int, String],
      options: VarkaEmitOptions)
      : (Option[PartialVarkaProjection], Map[Int, VarkaDecline]) = {
    // Both tables assign dense indices in first-occurrence order, which makes the compiled
    // shape deterministic in the projection alone.
    val inputs = mutable.LinkedHashMap.empty[Int, Int]
    val literals = mutable.LinkedHashMap.empty[Int, Int]
    val outputs = mutable.ArrayBuffer.empty[VarkaVectorIR]
    val outputTypes = Seq.newBuilder[DataType]
    val sink = new DeclineSink(childOutput, options.rangeSets)
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
        // An entry the size admission demoted in an earlier round: residual with the emitter's
        // reason, and compiled not at all, so it registers nothing in the shared tables.
        case e if demoted.contains(position) =>
          sink.note(demoted(position), e)
          sink.take().foreach(decline => declines += position -> decline)
          ResidualOutput
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
          compileRoot(e, inputs, literals, sink) match {
            // One kernel holds one lane: its loop, its epilogue and its stores are one species.
            // The first fused entry fixes the lane and an entry of the other lane is demoted to
            // residual with a reason that says so - checked here, before the budgets, because
            // `fitsBudgets` would refuse the mix too but only answers yes or no, and a lane
            // mismatch reported as a budget breach sends a reader hunting a chain-depth problem
            // that is not there. Task 28's width conversion is what will let both lanes share a
            // tree; until then the mixed projection fuses one lane and leaves the other.
            case Some(ir) if outputs.nonEmpty &&
                VarkaVectorIR.emissionLane(ir) != VarkaVectorIR.emissionLane(outputs.head) =>
              truncate(inputs, inputsMark)
              truncate(literals, literalsMark)
              sink.truncateLong(longMark)
              sink.truncateBounds(boundsMark)
              sink.take()
              sink.note(laneMismatch(VarkaVectorIR.emissionLane(ir),
                VarkaVectorIR.emissionLane(outputs.head)), e)
              sink.take().foreach(decline => declines += position -> decline)
              ResidualOutput
            // An accepted entry must also fit the emitter's structural budgets together with the
            // entries accepted before it. The emitter enforces the same limits, but at emission
            // time, where a breach can only become a silent per-batch fallback - no decline reason,
            // and EXPLAIN still claims fusion. So the compiler mirrors them and demotes the
            // overflowing entry to residual.
            case Some(ir)
                if VarkaLoopEmitter.fitsBudgets((outputs :+ ir).asJava, inputs.size, options) =>
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
    val reasons = declines.result()
    if (fusedCount > 0 && inputs.nonEmpty) {
      val (ordinals, derived) = VarkaDerivedInput.resolve(inputs)
      (Some(PartialVarkaProjection(specs, CompiledVarkaProjection(
        outputs.toSeq, outputTypes.result(), ordinals, literals.keys.toSeq,
        sink.inputBounds(inputs), derived, sink.longLiteralValues),
        reasons)), reasons)
    } else {
      (None, reasons)
    }
  }

  /**
   * Whether the emitter serves `fused` in bytes, asked of the emitter itself: `None` when it
   * does, and otherwise the fused outputs to demote with the reason. The weight caps admit an
   * entry before anything is built, and weight does not bound size (`PLAN_TASK_87.md`), so a
   * shape the caps admit can still be one the emitter's method budget declines - a single
   * output whose own method is past it, or a driver over it. Asking here moves that decline
   * from every task on the executor to the plan, where EXPLAIN shows it (`PLAN_TASK_169.md`).
   *
   * The question goes through the shape cache with the key the evaluator will build, so a
   * shape is emitted once per JVM whoever asks first: the compiler runs at planning, for
   * EXPLAIN and once per task on the executor, and a direct emission here would put a class
   * build on every one of those. A decline names the outputs whose own group cannot fit; a
   * class-wide one names none, and the last-admitted output is demoted, since the driver it
   * leaves over the budget grows with the number of outputs. Any other failure admits the
   * shape as before: the executor meets it where it always has, behind the ghost fallback.
   */
  private def admitBySize(
      fused: CompiledVarkaProjection,
      options: VarkaEmitOptions): Option[(Seq[Int], String)] = {
    if (options.methodByteBudget() == 0) {
      return None
    }
    val key = new VarkaShapeKey(
      fused.outputs.asJava, fused.inputOrdinals.size, fused.numLiterals, options)
    try {
      VarkaShapeCache.getOrEmit(key, "plan-time admission")
      None
    } catch {
      case d: VarkaEmitDeclined =>
        val named = d.outputs().asScala.map(_.intValue).toSeq
        val reason = s"over the emitter's method budget (${d.getMessage.split("; ").head})"
        Some((if (named.nonEmpty) named else Seq(fused.outputs.size - 1), reason))
      case NonFatal(_) => None
    }
  }

  /** Drops the entries a failed compile appended after `mark` (insertion order). */
  private[codegen] def truncate(table: mutable.LinkedHashMap[Int, Int], mark: Int): Unit = {
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
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Option[CompiledVarkaPredicate] =
    predicateAndSpecs(condition, childOutput, options)._2

  /**
   * Every conjunct of `condition` with what the compiler did with it - fused, or declined with
   * its reason - including when none fuses and [[compilePredicate]] returns `None`, so that a
   * filter left wholly to Spark still says why.
   */
  private[sql] def explainPredicate(
      condition: Expression,
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Seq[VarkaConjunctSpec] =
    predicateAndSpecs(condition, childOutput, options)._1

  private def predicateAndSpecs(
      condition: Expression,
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions): (Seq[VarkaConjunctSpec], Option[CompiledVarkaPredicate]) = {
    // The split hoists fused conjuncts below the residual ones, which reorders evaluation.
    // That is sound only when every conjunct is deterministic - Spark's own predicate
    // pushdown stops at the first nondeterministic conjunct (span(_.deterministic)) for the
    // same reason: a seeded rand() must see every row, not the survivors of a hoisted
    // predicate. One nondeterministic conjunct therefore declines the whole predicate
    // (task-21 review); the rewrite must never change what the query computes.
    if (!condition.deterministic) {
      val why = "the condition is nondeterministic: a fused conjunct would run ahead of a " +
        "nondeterministic one and change the rows it sees"
      val sink = new DeclineSink(childOutput, options.rangeSets)
      return (splitConjuncts(condition).map { conjunct =>
        sink.note(why, BindReferences.bindReference[Expression](conjunct, childOutput))
        VarkaConjunctSpec(conjunct, fused = false, decline = sink.take())
      }, None)
    }
    // The size admission of [[classify]], for conjuncts. The fused conjuncts fold into one
    // condition root, which the emitter cannot split by name. Under `splitConditions` the
    // compiler splits it instead (see [[splitPredicate]]); otherwise, or when no split fits, a
    // decline demotes the last-admitted conjunct and the rest are asked again.
    var demoted = Map.empty[Int, String]
    while (true) {
      val once = predicateOnce(condition, childOutput, demoted, options)
      val more = once.compiled.flatMap { predicate =>
        admitBySize(predicate.fused, options).map { case (_, reason) =>
          predicate.specs.zipWithIndex.filter(_._1.fused).map(_._2).max -> reason
        }
      }
      if (more.isEmpty) {
        return (once.specs, once.compiled)
      }
      if (options.splitConditions) {
        val split = splitPredicate(once, options)
        if (split.isDefined) {
          return (once.specs, split)
        }
      }
      demoted += more.get
    }
    throw new IllegalStateException("unreachable")
  }

  /**
   * One pass of [[predicateOnce]]: the conjunct specs, the predicate with the fused conjuncts
   * folded into one root, and what a split needs to lay them out differently - the fused
   * conditions in query order, and `build`, which makes the predicate of a layout (clauses
   * joined by AND, each clause's roots joined by OR) over the same input and literal tables.
   */
  private case class PredicatePass(
      specs: Seq[VarkaConjunctSpec],
      compiled: Option[CompiledVarkaPredicate],
      conds: Seq[Cond],
      build: Seq[Seq[Cond]] => CompiledVarkaPredicate)

  private def predicateOnce(
      condition: Expression,
      childOutput: Seq[Attribute],
      demoted: Map[Int, String],
      options: VarkaEmitOptions): PredicatePass = {
    val inputs = mutable.LinkedHashMap.empty[Int, Int]
    val literals = mutable.LinkedHashMap.empty[Int, Int]
    val sink = new DeclineSink(childOutput, options.rangeSets)
    val fusedConds = mutable.ArrayBuffer.empty[Cond]
    val specs = splitConjuncts(condition).zipWithIndex.map { case (conjunct, index) =>
      val bound = BindReferences.bindReference[Expression](conjunct, childOutput)
      if (demoted.contains(index)) {
        sink.note(demoted(index), bound)
        VarkaConjunctSpec(conjunct, fused = false, decline = sink.take())
      } else {
        val inputsMark = inputs.size
        val literalsMark = literals.size
        val longMark = sink.longMark
        val boundsMark = sink.boundsMark
        VarkaConditionCompiler.compileCond(bound, inputs, literals, sink) match {
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
              java.util.List.of(VarkaConditionCompiler.andFold(fusedConds.toSeq :+ cond)),
              inputs.size, options) =>
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
    }
    if (fusedConds.nonEmpty && inputs.nonEmpty) {
      val (ordinals, derived) = VarkaDerivedInput.resolve(inputs)
      val bounds = sink.inputBounds(inputs)
      val longs = sink.longLiteralValues
      val build = (layout: Seq[Seq[Cond]]) => {
        val roots = layout.flatten
        val starts = layout.scanLeft(0)(_ + _.size)
        val clauses = layout.indices.map(c => starts(c) until starts(c + 1))
        CompiledVarkaPredicate(specs, CompiledVarkaProjection(roots, roots.map(_ => BooleanType),
          ordinals, literals.keys.toSeq, bounds, derived, longs), clauses)
      }
      val conds = fusedConds.toSeq
      PredicatePass(specs, Some(build(Seq(Seq(VarkaConditionCompiler.andFold(conds))))), conds,
        build)
    } else {
      PredicatePass(specs, None, Seq.empty, _ => throw new IllegalStateException("nothing fused"))
    }
  }

  /**
   * Splits a fused predicate that one method cannot hold across several selection outputs
   * (`PLAN_TASK_172.md` 3.1), or `None` when no split fits and the caller demotes a conjunct as
   * it would without the option.
   *
   * Every extra output costs a pass over its columns and a bitmap, so the split looks for the
   * fewest outputs, in two steps, each a search over a count of equal contiguous pieces in
   * query order. First the fewest conjunction roots, each its own clause, that the emitter
   * accepts, except that it may still name a root holding a single conjunct that is a
   * disjunction. Then, for each root so named, the fewest partial disjunctions of it that the
   * emitter accepts beside everything else, as one clause. A root the second step cannot
   * split - a conjunct that is not a disjunction, or a disjunct too large alone - ends the
   * split with `None`, as does a kernel whose driver the extra outputs take past the budget.
   *
   * The emitter judges the whole kernel at every step, not each piece alone: a method in a kernel
   * of several outputs is not byte for byte what it is by itself, so a piece that fits alone can
   * still be named beside the others. Each question goes through [[admitBySize]] and so through
   * the shape cache, and the split runs only after the single root has been refused, so a
   * predicate that fits is compiled exactly as it is without the option. The search is exact
   * for splits into equal pieces, not over every partition: the exact partition is task 200's
   * dynamic program, which needs a cost cheaper to ask than an emission (task 199).
   */
  private def splitPredicate(
      pass: PredicatePass,
      options: VarkaEmitOptions): Option[CompiledVarkaPredicate] = {
    def disjuncts(c: Cond): Seq[Cond] = c match {
      case or: IROr => disjuncts(or.left()) ++ disjuncts(or.right())
      case other => Seq(other)
    }
    /** `items` in `k` contiguous pieces whose sizes differ by at most one. */
    def pieces(items: Seq[Cond], k: Int): Seq[Seq[Cond]] =
      (0 until k).map(i => items.slice(i * items.size / k, (i + 1) * items.size / k))
    /** The outputs of `layout` the emitter names as over the budget; empty when it fits. */
    def over(layout: Seq[Seq[Cond]]): Set[Int] =
      admitBySize(pass.build(layout).fused, options).map(_._1.toSet).getOrElse(Set.empty)
    /**
     * The smallest count from `lo` up to `cap` that `ok` accepts: doubling until one is
     * accepted, then a binary search below it. It never asks about a count far past the
     * answer, which matters because acceptance is monotone only up to a point - past it, the
     * extra outputs take the kernel's driver over the budget and every count is refused.
     */
    def smallest(lo: Int, cap: Int)(ok: Int => Boolean): Option[Int] = {
      var miss = lo - 1
      var hit = lo
      while (!ok(hit)) {
        if (hit >= cap) {
          return None
        }
        miss = hit
        hit = math.min(hit * 2, cap)
      }
      var (low, high) = (miss + 1, hit)
      while (low < high) {
        val mid = (low + high) / 2
        if (ok(mid)) high = mid else low = mid + 1
      }
      Some(low)
    }
    def splittable(root: Seq[Cond]): Boolean = root.size == 1 && disjuncts(root.head).size > 1

    val conds = pass.conds
    val k = smallest(1, conds.size) { k =>
      val roots = pieces(conds, k)
      over(roots.map(r => Seq(VarkaConditionCompiler.andFold(r)))).forall(o => splittable(roots(o)))
    }
    if (k.isEmpty) {
      return None
    }
    val roots = pieces(conds, k.get)
    var layout: Seq[Seq[Cond]] = roots.map(r => Seq(VarkaConditionCompiler.andFold(r)))
    // Each clause is still one output here, so the named outputs are the clauses to split.
    for (c <- over(layout).toSeq.sorted) {
      val ds = disjuncts(roots(c).head)
      def split(m: Int): Seq[Seq[Cond]] =
        layout.updated(c, pieces(ds, m).map(VarkaConditionCompiler.orFold))
      val m = smallest(2, ds.size) { m =>
        val start = layout.take(c).map(_.size).sum
        val named = over(split(m))
        !(start until start + m).exists(named)
      }
      if (m.isEmpty) {
        return None
      }
      layout = split(m.get)
    }
    if (over(layout).isEmpty) Some(pass.build(layout)) else None
  }
  /**
   * The lane a Spark type's values occupy in a kernel, or `None` for a type no kernel reads.
   * The int side is what the leaf arms below already admit - a date, an int and a year-month
   * interval are all one 32-bit lane - and the long side is milestone 5's: `bigint`, `TIME`
   * (nanoseconds of day) and a day-time interval (microseconds) are one 64-bit lane
   * (`PLAN_TASK_29.md` 3.1). The two timestamp types are that lane physically and are
   * deliberately absent: see `isTimestamp` and the arm that names them.
   */
  private[codegen] def laneOf(dataType: DataType): Option[LaneType] = dataType match {
    case IntegerType | DateType | _: YearMonthIntervalType => Some(LaneType.INT)
    case LongType | _: TimeType | _: DayTimeIntervalType => Some(LaneType.LONG)
    case _ => None
  }

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
  /**
   * An output root: [[compileNode]], plus the one lowering only a root may take. The three
   * `TIME` field extracts compute a 64-bit division and deliver an int, and the emitter narrows
   * a lane at the kernel's store and nowhere else until task 28 gives it a width conversion;
   * so `hour(t)` as an output fuses under a narrowing root, while `hour(t) + 1` and
   * `hour(t) = 12`, which put the narrowed value under another node, reach
   * [[VarkaTimeCompiler.compileTime]] through [[compileNode]] and decline with that reason.
   */
  private def compileRoot(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = expr match {
    case r: RuntimeReplaceable => compileRoot(r.replacement, inputs, literals, sink)
    case si: StaticInvoke
        if VarkaTimeCompiler.timeTargets.contains((si.staticObject, si.functionName)) =>
      VarkaTimeCompiler.compileTime(si, inputs, literals, sink, atRoot = true)
    case other => compileNode(other, inputs, literals, sink)
  }

  /** The reason an entry of one lane records when the kernel is already on the other. */
  private def laneMismatch(entry: LaneType, kernel: LaneType): String =
    s"the $entry lane in a kernel on the $kernel lane: one kernel holds one lane"

  /** The `AND` spine of a condition, in query order - the split [[compilePredicate]] works. */
  private def splitConjuncts(condition: Expression): Seq[Expression] = condition match {
    case And(left, right) => splitConjuncts(left) ++ splitConjuncts(right)
    case other => Seq(other)
  }

  /**
   * The recursive node compiler. `None` anywhere fails the enclosing entry, whose caller rolls the
   * tables back to their pre-entry state. Shapes that cannot be served stay unmatched by
   * construction: a `date_add` over a `datediff` result only type-checks through a `Cast`, which
   * compiles to nothing here. Integer arithmetic over a `datediff` result was in that list until
   * the int32 lowering admitted it - a SIMD lane still cannot throw row-accurately, so an ANSI
   * overflow condemns the batch and the row engine raises it instead.
   */
  private[codegen] def compileNode(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = {
    val arms = leafArms(inputs, literals, sink)
      .orElse(VarkaChronoCompiler.arms(inputs, literals, sink))
      .orElse(VarkaIntervalCompiler.arms(inputs, literals, sink))
      .orElse(VarkaTimeCompiler.arms(inputs, literals, sink))
      .orElse(VarkaConditionCompiler.arms(inputs, literals, sink))
      .orElse(arithmeticArms(inputs, literals, sink))
    arms.applyOrElse(expr, fallback(inputs, literals, sink))
  }

  /**
   * The date leaves: a date column, a date literal and the identity date cast. First in the chain,
   * ahead of every family.
   */
  private def leafArms(
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): PartialFunction[Expression, Option[VarkaVectorIR]] = {
    case br: BoundReference if br.dataType == DateType =>
      Some(columnRef(br, inputs))
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
  }

  /**
   * The int32 arithmetic over int-valued operands and the null-skipping picks, after the families:
   * the `Add(WeekDay, 1)` shape keeps its dedicated calendar node because the calendar arms come
   * first in the chain, and the guard below says so twice.
   */
  private def arithmeticArms(
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): PartialFunction[Expression, Option[VarkaVectorIR]] = {
    // Spark's greatest/least are n-ary; the null-skipping algebra is associative, so a left
    // fold into the binary IR nodes is exact.
    case Greatest(children) =>
      VarkaConditionCompiler.foldPick(children, inputs, literals, sink, new IRGreatest(_, _))
    case Least(children) =>
      VarkaConditionCompiler.foldPick(children, inputs, literals, sink, new IRLeast(_, _))
    // extract(DAYOFWEEK_ISO) / date_part('DOW_ISO'): the analyzer spells them Add(WeekDay(d), 1),
    // and so does a hand-written weekday(d) + 1. One narrow arm, either operand order, and nothing
    // else: integer arithmetic over an output is out of scope for this compiler. Int32 //
    // arithmetic over int-valued operands - a fused field, an IntegerType column, an int literal,
    // or nested arithmetic. Placed after the `Add(WeekDay, 1)` arm of the calendar family so that
    // shape keeps its cheaper dedicated node.
    case a: Add if a.dataType == IntegerType && !VarkaChronoCompiler.isDayOfWeekIso(a) =>
      intArith(IntOp.ADD, a.evalMode, a.left, a.right, a, inputs, literals, sink)
    case a: Subtract if a.dataType == IntegerType =>
      intArith(IntOp.SUB, a.evalMode, a.left, a.right, a, inputs, literals, sink)
    case a: Multiply if a.dataType == IntegerType =>
      intArith(IntOp.MUL, a.evalMode, a.left, a.right, a, inputs, literals, sink)
    case n @ UnaryMinus(c, failOnError) if n.dataType == IntegerType =>
      // Spark has no try_negative, so the mode is only ever WRAP or FAIL here. Negation
      // overflows on exactly one value, `Int.MinValue`, so any bound at all rules it out and
      // the check comes off - the same reasoning the binary arms use, on a narrower fact.
      intOperand(c, inputs, literals, sink).map { x =>
        val checked = failOnError && !magnitude(x, literals).exists(_ <= Int.MaxValue.toLong)
        new IntNeg(if (checked) Overflow.FAIL else Overflow.WRAP, x)
      }
  }

  /**
   * What no arm matched: a column of another type declines by its type, a `RuntimeReplaceable`
   * compiles what would run, and anything else declines as unsupported.
   */
  private def fallback(
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Expression => Option[VarkaVectorIR] = {
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

  /** Spark's evaluation mode as the IR spells it. */
  private[codegen] def overflowOf(mode: EvalMode.Value): Overflow = mode match {
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
  private[codegen] def intOperand(
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
  private[codegen] def literalAt(literals: mutable.LinkedHashMap[Int, Int]): IntUnaryOperator =
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
  private[codegen] def magnitude(
      node: VarkaVectorIR, literals: mutable.LinkedHashMap[Int, Int]): Option[Long] = {
    val m = VarkaRangeAnalysis.magnitude(node, literalAt(literals))
    if (m.isPresent) Some(m.getAsLong) else None
  }

  /**
   * Whether the operation on operands of these bounds cannot leave the int32 range. Read
   * through the analysis's own `IntArith` transfer function rather than re-dispatched here: the
   * candidate node is never emitted, so building one to ask the question is free, and the two
   * answers cannot drift apart the way two copies of "MUL multiplies, else adds" once could. A
   * magnitude is non-negative by construction, so the only thing left to ask is whether it stays
   * at or under `Int.MaxValue` - one past it, `2^31`, is the first magnitude that overflows.
   */
  private[codegen] def cannotOverflow(op: IntOp, l: VarkaVectorIR, r: VarkaVectorIR,
      literals: mutable.LinkedHashMap[Int, Int]): Boolean =
    magnitude(new IntArith(op, Overflow.WRAP, l, r), literals).exists(_ <= Int.MaxValue.toLong)

  /**
   * The shared body of the three binary arithmetic arms. A checked multiply declines unless
   * the operands' bounds prove it cannot overflow: the overflow test for `*` needs the 64-bit
   * product or a lane division, and the emitter has neither in int lanes, so an unprovable
   * `ANSI` or `TRY` multiply stays on the row engine until milestone 5's long lanes arrive
   * (`PLAN_TASK_63.md` 3.4).
   */
  private[codegen] def intArith(
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
  private[codegen] def arithOver(
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
   * Interns `value` into the per-distinct-value literal table and wraps it as a `LiteralSlot` on
   * the int lane. Every folded constant the compiler admits is an int - a day count, a month
   * count, a date's epoch day - so this is the one place a literal's lane is chosen, as
   * `columnRef` is for a column's.
   */
  private[codegen] def intSlot(value: Int, literals: mutable.LinkedHashMap[Int, Int]): LiteralSlot =
    new LiteralSlot(literals.getOrElseUpdate(value, literals.size), LaneType.INT)

  /**
   * Interns `br`'s ordinal into `inputs` and wraps it as a `ColumnRef` on `lane` - shared by
   * `compileNode`'s date, interval and long leaves and `compileOffset`'s `IntegerType` one.
   */
  private[codegen] def columnRef(
      br: BoundReference,
      inputs: mutable.LinkedHashMap[Int, Int],
      lane: LaneType = LaneType.INT): ColumnRef =
    new ColumnRef(inputs.getOrElseUpdate(br.ordinal, inputs.size), lane)

  /**
   * `columnRef`'s twin for an input the evaluator derives from `br`: interned under
   * `VarkaDerivedInput.key` beside the child ordinals, so it takes the next kernel input index
   * and shares the table's rollback.
   */
  private[codegen] def derivedRef(br: BoundReference, kind: VarkaDerivedKind,
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
  private[codegen] def compileIntOperand(
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
}
