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

import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.control.NonFatal

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, BindReferences,
  BoundReference, Expression, NamedExpression, RuntimeReplaceable}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.{ForwardedOutput,
  FusedOutput, KernelOutput}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitDeclined,
  VarkaEmitOptions, VarkaKernelWarmup, VarkaLoopEmitter, VarkaShapeCache, VarkaShapeKey,
  VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{Cond, LaneType,
  Or => IROr}
import org.apache.spark.sql.catalyst.expressions.objects.StaticInvoke
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{BooleanType, DataType}

/**
 * Compiles a bound projection list to the Varka vector IR, recursing where the MVP's flat matcher
 * demanded bare attributes - `datediff(date_add(d, 7), d2)` compiles where milestone 1 saw nothing,
 * and so do `CASE WHEN`/`IF` (via interior comparisons and the three-valued connectives),
 * `greatest`/`least`, `dayofweek`/`weekday` and date literals. The conditions also take `IN` over
 * date literals (capped, see `VarkaNodeCompiler.MAX_IN_LITERALS`) and the validity predicates
 * `IS [NOT] NULL` over bare columns, and the values with `coalesce`/`nvl`/`nvl2` (lowered onto
 * the validity condition)
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
private[sql] object VarkaExpressionCompiler extends Logging {

  /** The all-entries-fused special case of [[compilePartial]], kept for callers that need it. */
  def compile(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Option[CompiledVarkaProjection] = {
    compilePartial(projectList, childOutput, options).collect {
      case partial if partial.specs.asScala.forall(_.isInstanceOf[FusedOutput]) => partial.fused
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
    classifyKernels(projectList, childOutput, options)._1
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
    classifyKernels(projectList, childOutput, options)._2
  }

  /**
   * [[classify]], and under `VarkaEmitOptions.severalKernels` the entries it set aside only for
   * the kernel's sake - the suffix a class-wide decline demoted, an entry that fits alone but not
   * beside the rest - classified again as a kernel of their own, round after round, until a round
   * fuses nothing or nothing is left aside. Each round is [[classify]] over the whole projection
   * with every other entry demoted, so it sees the same entries in the same order and the kernels
   * are deterministic in the projection alone. See `VARKA-190.md` 11.
   */
  private def classifyKernels(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions): (Option[PartialVarkaProjection], Map[Int, VarkaDecline]) = {
    val (first, firstDeclines, firstAside) = classify(projectList, childOutput, options)
    if (!options.severalKernels || first.isEmpty) {
      return (first, firstDeclines)
    }
    val specs = first.get.specs.asScala.toArray
    var declines = firstDeclines
    val more = mutable.ArrayBuffer.empty[CompiledVarkaProjection]
    var aside = firstAside
    var progress = true
    while (aside.nonEmpty && progress) {
      val others = projectList.indices.filterNot(aside).map(_ -> OtherKernel).toMap
      val (next, nextDeclines, nextAside) = classify(projectList, childOutput, options, others)
      val fused = next.toSeq.flatMap(_.specs.asScala.zipWithIndex.collect {
        case (f: FusedOutput, at) => at -> f.fusedIndex
      })
      progress = fused.nonEmpty
      if (progress) {
        more += next.get.fused
        fused.foreach { case (at, i) =>
          specs(at) = new KernelOutput(more.size, i)
          declines -= at
        }
      }
      // Every entry this round classified and did not fuse carries this round's reason, the
      // latest thing that kept it out, including when the round fused nothing and ends the loop.
      val fusedNow = fused.map(_._1).toSet
      aside.filterNot(fusedNow).foreach { at =>
        nextDeclines.get(at).foreach(d => declines += at -> d)
      }
      aside = nextAside
    }
    (Some(new PartialVarkaProjection(specs.toSeq.asJava, first.get.fused, javaDeclines(declines),
      more.asJava)), declines)
  }

  /** The reason a round of [[classifyKernels]] demotes an entry another kernel serves. */
  private val OtherKernel = "served by another kernel of this projection"

  /**
   * The per-entry classification, then the size admission: the entries the weight caps admit
   * are asked of the emitter as the one kernel they make, and an entry the emitter declines in
   * bytes is classified again as residual, with the emitter's reason, until the kernel it leaves
   * is one the emitter serves. Each round demotes at least one entry, so it ends.
   *
   * A decline that names outputs demotes those. A class-wide one - a driver over a limit, a
   * class over the class-file caps - names none, and what shrinks such a class is fewer
   * outputs; the driver grows with their number. Demoting one output per round would ask for
   * a class of nearly the same size once per output, which on a projection of thousands of
   * entries is thousands of builds of a class of thousands of methods. So a class-wide decline
   * bisects instead: the fused entries are a prefix in projection order, the largest prefix
   * the emitter admits is found by halving, and the rest are demoted with the class-wide
   * reason - a logarithmic number of asks, each one emission through the shape cache.
   */
  private def classify(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions,
      initial: Map[Int, String] = Map.empty)
      : (Option[PartialVarkaProjection], Map[Int, VarkaDecline], Set[Int]) = {
    // A nondeterministic entry declines the whole projection, as a nondeterministic conjunct
    // declines a predicate. A Varka node evaluates its residual entries row by row, on its row
    // path and beside its kernel, through projections of its own; vanilla's `Project` draws a
    // seeded `rand()` from one generator per partition, initialized with the partition's index,
    // and two paths would each need one. Declined, the projection runs as vanilla's.
    if (!projectList.forall(_.deterministic)) {
      val why = "the projection has a nondeterministic entry, whose values would depend on the " +
        "path each batch took"
      val sink = new DeclineSink(childOutput, options.rangeSets)
      return (None, projectList.zipWithIndex.flatMap { case (named, position) =>
        sink.note(why, BindReferences.bindReference[Expression](named, childOutput))
        sink.take().map(position -> _)
      }.toMap, Set.empty)
    }
    // The projection positions of a partial's fused entries, in the order the kernel numbers
    // them; a decline's named outputs index this.
    def positions(partial: PartialVarkaProjection): Seq[Int] =
      partial.specs.asScala.toSeq.zipWithIndex.collect {
        case (f: FusedOutput, at) => f.fusedIndex -> at
      }
        .sortBy(_._1).map(_._2)
    def ask(demoted: Map[Int, String])
        : Option[(PartialVarkaProjection, Seq[Int], String, Int)] = {
      classifyOnce(projectList, childOutput, demoted, options)._1.flatMap { partial =>
        admitBySize(partial.fused, options).map { case (named, reason, cut) =>
          (partial, named, reason, cut)
        }
      }
    }
    var demoted = initial
    // The entries a class-wide decline demoted: they fit, only not in this kernel.
    var bisected = Set.empty[Int]
    // The cuts the plan has made for this kernel, each one emission that builds nothing; after
    // two the bisection has the last word (VARKA-236.md 3.4).
    var plannedCuts = 0
    while (true) {
      ask(demoted) match {
        case None =>
          val (partial, declines, alone) = classifyOnce(projectList, childOutput, demoted, options)
          return (partial, declines, bisected ++ alone)
        case Some((partial, named, reason, _)) if named.nonEmpty =>
          val at = positions(partial)
          demoted ++= named.map(at).map(_ -> reason)
        case Some((partial, _, reason, cut))
            if cut > 0 && cut < positions(partial).size && plannedCuts < 2 =>
          // Under `planSize` the decline says how many of the fused entries one class serves,
          // read off the driver over the grouping the emitter formed: the rest are set aside in
          // one step. The next ask plans the prefix on its own grouping, so a prefix whose own
          // driver is still over cuts once more, and after that the bisection decides.
          val at = positions(partial)
          plannedCuts += 1
          demoted ++= at.drop(cut).map(_ -> reason)
          bisected ++= at.drop(cut)
        case Some((partial, _, reason, _)) =>
          val at = positions(partial)
          // `hi` entries are known not to fit as a class; `lo` entries are known to fit, or to
          // decline naming outputs, which the next round handles. Nothing fused fits trivially.
          var lo = 0
          var hi = at.size
          var reasonAtHi = reason
          while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            ask(demoted ++ at.drop(mid).map(_ -> reasonAtHi)) match {
              case Some((_, named, r, _)) if named.isEmpty =>
                hi = mid
                reasonAtHi = r
              case _ =>
                lo = mid
            }
          }
          demoted ++= at.drop(lo).map(_ -> reasonAtHi)
          bisected ++= at.drop(lo)
      }
    }
    throw new IllegalStateException("unreachable")
  }

  private def classifyOnce(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      demoted: Map[Int, String],
      options: VarkaEmitOptions)
      : (Option[PartialVarkaProjection], Map[Int, VarkaDecline], Set[Int]) = {
    // Both tables assign dense indices in first-occurrence order, which makes the compiled
    // shape deterministic in the projection alone.
    val inputs = mutable.LinkedHashMap.empty[Int, Int]
    val literals = mutable.LinkedHashMap.empty[Int, Int]
    val outputs = mutable.ArrayBuffer.empty[VarkaVectorIR]
    val outputTypes = Seq.newBuilder[DataType]
    val sink = new DeclineSink(childOutput, options.rangeSets)
    val declines = Map.newBuilder[Int, VarkaDecline]
    // Entries over the budgets beside the others that fit them alone: another kernel's.
    val alone = Set.newBuilder[Int]
    var fusedCount = 0
    // Whether an entry over the budgets beside the others fits them by itself, counted with its
    // own columns: compiled alone into tables of its own, which the shared ones never see.
    def fitsAlone(e: Expression): Boolean = {
      val own = mutable.LinkedHashMap.empty[Int, Int]
      compileRoot(e, own, mutable.LinkedHashMap.empty[Int, Int],
        new DeclineSink(childOutput, options.rangeSets))
        .exists(ir => VarkaLoopEmitter.fitsBudgets(java.util.List.of(ir), own.size, options))
    }
    def classifyEntry(named: NamedExpression, position: Int): VarkaOutputSpec = {
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
        case br: BoundReference => new ForwardedOutput(br.ordinal)
        // An entry the size admission demoted in an earlier round: residual with the emitter's
        // reason, and compiled not at all, so it registers nothing in the shared tables.
        case e if demoted.contains(position) =>
          sink.note(demoted(position), e)
          sink.take().foreach(decline => declines += position -> decline)
          VarkaOutputSpec.RESIDUAL
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
          // A test's forced decline (`forceResidualAt`, VARKA-296): the entry compiles as any
          // other and then takes the over-budget branch below, so the rollback it runs is the
          // one a real decline runs.
          val forced = options.forceResidualAt == position + 1
          compileRoot(e, inputs, literals, sink) match {
            // One kernel holds one lane: its loop, its epilogue and its stores are one species.
            // The first fused entry fixes the lane and an entry of the other lane is demoted to
            // residual with a reason that says so - checked here, before the budgets, because
            // `fitsBudgets` would refuse the mix too but only answers yes or no, and a lane
            // mismatch reported as a budget breach sends a reader hunting a chain-depth problem
            // that is not there. VARKA-28's width conversion is what will let both lanes share a
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
              VarkaOutputSpec.RESIDUAL
            // An accepted entry must also fit the emitter's structural budgets together with the
            // entries accepted before it. The emitter enforces the same limits, but at emission
            // time, where a breach can only become a silent per-batch fallback - no decline reason,
            // and EXPLAIN still claims fusion. So the compiler mirrors them and demotes the
            // overflowing entry to residual.
            case Some(ir) if !forced &&
                VarkaLoopEmitter.fitsBudgets((outputs :+ ir).asJava, inputs.size, options) =>
              sink.take()
              outputs += ir
              outputTypes += e.dataType
              fusedCount += 1
              new FusedOutput(fusedCount - 1)
            case compiled =>
              // `misdescribeRollback` is a fault injector for the forced-decline check: zero in
              // production, where this restores the four tables exactly.
              options.misdescribeRollback match {
                case 1 =>
                case 3 => truncate(inputs, math.max(0, inputsMark - 1))
                case _ => truncate(inputs, inputsMark)
              }
              truncate(literals, literalsMark)
              sink.truncateLong(longMark)
              if (options.misdescribeRollback != 2) {
                sink.truncateBounds(boundsMark)
              }
              if (compiled.isDefined) {
                sink.take() // an entry that compiled clean; its reason is the budget, or the force
                if (forced) {
                  sink.note(s"forced residual (forceResidualAt=${options.forceResidualAt})", e)
                } else {
                  sink.note("exceeds the emitter's fused budget", e)
                  if (fitsAlone(e)) {
                    alone += position
                  }
                }
              }
              // A declining entry always leaves a reason: every `None` below notes one.
              sink.take().foreach(decline => declines += position -> decline)
              VarkaOutputSpec.RESIDUAL
          }
      }
    }
    val specs = projectList.zipWithIndex.map { case (named, position) =>
      // Another kernel's entry in a round of `classifyKernels`: neither bound nor noted, so a
      // round costs the entries it classifies rather than the whole projection.
      if (demoted.get(position).contains(OtherKernel)) VarkaOutputSpec.RESIDUAL
      else classifyEntry(named, position)
    }
    val reasons = declines.result()
    if (fusedCount > 0 && inputs.nonEmpty) {
      val fused = projection(outputs.toSeq, outputTypes.result(), inputs, literals, sink)
      (Some(new PartialVarkaProjection(specs.asJava, fused, javaDeclines(reasons),
        java.util.List.of())), reasons, alone.result())
    } else {
      (None, reasons, alone.result())
    }
  }

  /**
   * Whether the emitter serves `fused` in bytes, asked of the emitter itself: `None` when it
   * does, and otherwise the fused outputs to demote with the reason. The weight caps admit an
   * entry before anything is built, and weight does not bound size (`VARKA-87.md`), so a
   * shape the caps admit can still be one the emitter's method budget declines - a single
   * output whose own method is past it, or a driver over it. Asking here moves that decline
   * from every task on the executor to the plan, where EXPLAIN shows it (`VARKA-169.md`).
   *
   * The question goes through the shape cache with the key the evaluator will build, so a
   * shape is built once per JVM whoever asks first: the compiler runs at planning, for
   * EXPLAIN and once per task on the executor, and a direct emission here would put a class
   * build on every one of those. It is answered without defining a class (VARKA-237): the cache
   * builds and measures the shape and holds its bytes, and the evaluator's first lookup defines
   * the class from them, so planning on the Spark driver loads nothing, and a bisection's probes
   * leave no class behind but the one that runs. A decline names the outputs whose own group
   * cannot fit; a class-wide one names none, and the caller demotes outputs from the end,
   * since the driver it leaves over the budget grows with their number ([[classify]] bisects,
   * or cuts in one step where the decline carries the plan's cut, VARKA-236). Any other failure
   * of the build admits the shape as before, and is logged once per JVM: the
   * executor meets it where it always has, behind the ghost fallback. A class that builds but
   * fails to define or link is no longer met here at all, since nothing is defined: the
   * executors meet it on its first batch, the same fallback; the tests verify every admitted
   * shape's bytes instead ([[VarkaShapeCache.admit]]).
   */
  private def admitBySize(
      fused: CompiledVarkaProjection,
      options: VarkaEmitOptions): Option[(Seq[Int], String, Int)] = {
    // Asked with the budget off as well: the legacy form is built once and never measured, so
    // the only decline it can give is the class-file cap's, and that one is worth a residual
    // at plan time rather than a per-task fallback on the executor (VARKA-219.md 10).
    val key = new VarkaShapeKey(
      fused.outputs, fused.inputOrdinals.size, fused.numLiterals, options,
      VarkaKernelWarmup.warms(SQLConf.get.varkaWarmupEnabled))
    try {
      VarkaShapeCache.admit(key)
      None
    } catch {
      case d: VarkaEmitDeclined =>
        val named = d.outputs().asScala.map(_.intValue).toSeq
        val reason = s"over the emitter's method budget (${d.getMessage.split("; ").head})"
        Some((named, reason, d.plannedCut()))
      case NonFatal(e) =>
        // Not a decline, so not a shape the emitter refuses by design: an emitter bug, or a
        // failure of the JVM's, which the executor meets behind the ghost fallback as before.
        // Said once per JVM here as well, so that a plan admitting a shape the emitter cannot
        // build shows on the driver and not only in a task's log (VARKA-219.md 3.1).
        val where = e.getStackTrace.headOption.map(_.toString).getOrElse("")
        if (loggedEmitterFailures.add(s"${e.getClass.getName}@$where")) {
          logWarning("The Varka emitter failed at plan time on a shape the compiler admitted, " +
            s"which the executor will meet behind the fallback: ${e.getMessage}", e)
        }
        None
    }
  }

  /**
   * The plan-time emitter failures already logged in this JVM, by exception class and the
   * frame that threw: bounded by the emitter's code, where a key by message would grow with
   * every distinct shape a long-lived driver plans.
   */
  private val loggedEmitterFailures = ConcurrentHashMap.newKeySet[String]()

  /** Drops the entries a failed compile appended after `mark` (insertion order). */
  private[codegen] def truncate(table: mutable.LinkedHashMap[Int, Int], mark: Int): Unit =
    VarkaNodeCompiler.truncate(table, mark)

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
        new VarkaConjunctSpec(conjunct, false, sink.take().toJava)
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
        admitBySize(predicate.fused, options).map { case (_, reason, _) =>
          predicate.specs.asScala.zipWithIndex.filter(_._1.fused).map(_._2).max -> reason
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
        new VarkaConjunctSpec(conjunct, false, sink.take().toJava)
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
            new VarkaConjunctSpec(conjunct, false, sink.take().toJava)
          case Some(cond) if VarkaLoopEmitter.fitsBudgets(
              java.util.List.of(VarkaConditionCompiler.andFold(fusedConds.toSeq :+ cond)),
              inputs.size, options) =>
            sink.take()
            fusedConds += cond
            new VarkaConjunctSpec(conjunct, true, java.util.Optional.empty[VarkaDecline]())
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
            new VarkaConjunctSpec(conjunct, false, sink.take().toJava)
        }
      }
    }
    if (fusedConds.nonEmpty && inputs.nonEmpty) {
      val tables = projection(Seq.empty, Seq.empty, inputs, literals, sink)
      val build = (layout: Seq[Seq[Cond]]) => {
        val roots: Seq[VarkaVectorIR] = layout.flatten
        val starts = layout.scanLeft(0)(_ + _.size)
        val clauses = layout.indices.map(c => (starts(c) until starts(c + 1)).map(Int.box).asJava)
        new CompiledVarkaPredicate(specs.asJava, new CompiledVarkaProjection(roots.asJava,
          roots.map(_ => BooleanType: DataType).asJava, tables.inputOrdinals, tables.literals,
          tables.inputBounds, tables.derivedInputs, tables.longLiterals), clauses.asJava)
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
   * (`VARKA-172.md` 3.1), or `None` when no split fits and the caller demotes a conjunct as
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
   * for splits into equal pieces, not over every partition: the exact partition is VARKA-200's
   * dynamic program, which needs a cost cheaper to ask than an emission (VARKA-199).
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
   * The lowerings of the `TIME` expressions, keyed on the `DateTimeUtils` method each one's
   * replacement invokes - which is the one name that survives the optimizer (see
   * `VarkaTimeCompiler.TIME_TARGETS`). The arithmetic is read off `DateTimeUtils` itself, not off
   * the expression:
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
   * An output root: `VarkaNodeCompiler.compileNode`, plus the one lowering only a root may take.
   * The three `TIME` field extracts compute a 64-bit division and deliver an int, and the
   * emitter narrows a lane at the kernel's store and nowhere else until VARKA-28 gives it a width
   * conversion;
   * so `hour(t)` as an output fuses under a narrowing root, while `hour(t) + 1` and
   * `hour(t) = 12`, which put the narrowed value under another node, reach
   * [[VarkaTimeCompiler.compileTime]] through `VarkaNodeCompiler.compileNode` and decline with
   * that reason.
   */
  private def compileRoot(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): Option[VarkaVectorIR] = expr match {
    case r: RuntimeReplaceable => compileRoot(r.replacement, inputs, literals, sink)
    case si: StaticInvoke if VarkaTimeCompiler.isTimeTarget(si) =>
      VarkaTimeCompiler.compileTime(si, inputs, literals, sink, true)
    case other => VarkaNodeCompiler.compileNode(other, inputs, literals, sink)
  }

  /**
   * The fused projection over the accepted entries' tables: `inputOrdinals` and `derivedInputs`
   * from the input table (a derived input is interned under a negative key beside the child
   * ordinals, see `VarkaDerivedInput.key`), the literal slots, and the sink's bounds and 64-bit
   * literals.
   */
  private def projection(
      outputs: Seq[VarkaVectorIR],
      outputTypes: Seq[DataType],
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int],
      sink: DeclineSink): CompiledVarkaProjection = {
    val keys = inputs.keys.toSeq
    val ordinals = keys.map { k =>
      Int.box(if (VarkaDerivedInput.isKey(k)) VarkaDerivedInput.sourceOrdinal(k) else k)
    }
    val derived = keys.zipWithIndex.collect {
      case (k, i) if VarkaDerivedInput.isKey(k) =>
        new VarkaDerivedInput(i, VarkaDerivedInput.sourceOrdinal(k), VarkaDerivedInput.kind(k))
    }
    new CompiledVarkaProjection(outputs.asJava, outputTypes.asJava, ordinals.asJava,
      literals.keys.toSeq.map(Int.box).asJava, sink.inputBounds(inputs), derived.asJava,
      sink.longLiteralValues)
  }

  private def javaDeclines(declines: Map[Int, VarkaDecline]): java.util.Map[Integer, VarkaDecline] =
    declines.map { case (at, d) => Int.box(at) -> d }.asJava

  /** The reason an entry of one lane records when the kernel is already on the other. */
  private def laneMismatch(entry: LaneType, kernel: LaneType): String =
    s"the $entry lane in a kernel on the $kernel lane: one kernel holds one lane"

  /** The `AND` spine of a condition, in query order - the split [[compilePredicate]] works. */
  private def splitConjuncts(condition: Expression): Seq[Expression] = condition match {
    case And(left, right) => splitConjuncts(left) ++ splitConjuncts(right)
    case other => Seq(other)
  }
}
