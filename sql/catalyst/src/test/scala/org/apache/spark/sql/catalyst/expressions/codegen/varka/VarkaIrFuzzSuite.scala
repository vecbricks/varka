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

import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaIrGrammar.Drawn
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils

/**
 * Random IR trees against the reference evaluator.
 *
 * The emitter suite's matrices are exhaustive over the shapes someone thought to write. This
 * suite writes the others: for each iteration a fresh `Random` seeded from the run seed and the
 * iteration number builds one to three roots out of every supported node type, over one to
 * three int32 columns and up to two literal slots, picks a length, a null pattern per column
 * and a random `VarkaEmitOptions` variant, emits and loads the kernel, runs it once and checks
 * every row and validity bit against [[VarkaReferenceEvaluator]]. A failure names the seed and
 * the iteration, the roots in canonical form, the options, the length and the patterns, and
 * replays with `-Dvarka.fuzz.seed=<seed> -Dvarka.fuzz.only=<iteration>`.
 *
 * What it is for: the class of bug where a slot or a local means two things under two option
 * settings, or a lane-group tail or a validity word is right for every curated shape and wrong
 * for one nobody curated. Random option combinations are the point - every boolean `with*` on
 * the options record is toggled at random (the fault injector excepted), the mod-7 lowering is
 * drawn from all three, and `groupBudget` is sometimes narrowed - so a new option is fuzzed the
 * day it lands without anyone touching this file.
 *
 * The shapes respect the emitter's structural rules, which are the compiler's: a day offset is a
 * literal slot or a column, `next_day`'s weekday and `add_months`' month count are a literal
 * slot or a column, `IsNotNull` is over a column, a selection kernel has one condition root.
 *
 * Ranges: calendar nodes are defined over `VarkaChrono`'s narrowed day range, so every tree
 * carries a bound on the magnitude of its value and a calendar node is only put over a subtree
 * whose bound fits inside that range with slack. Columns hold days within plus or minus 2.5
 * million (about six thousand years either side of 1970) and literals within plus or minus
 * 4000, which keeps sums of two columns and chains of offsets inside the range too.
 *
 * The long lane has a corpus of its own, drawn from `VarkaIrGrammar.LongShapes` over 64-bit
 * columns and literals and run through the kernel's eight-argument entry point against
 * `evalLong`. It is a second sequence with a second seed rather than long shapes mixed into the
 * first, because the first is also the emitted-bytes oracle's committed corpus. Its value roots
 * are narrowed to an int column where their bound fits (`LongShapes.root`, VARKA-235), and a
 * narrowing root's output is read at the store's four bytes a row. The same
 * option draws apply, which is what puts `useAVX` under the constant division and so fuzzes
 * both of its lowerings on one machine.
 *
 * Past the ceilings (VARKA-238): the byte budget is drawn small as well as off and 8000, so the
 * regroup and the declines happen at the widths drawn here, and a third test composes
 * `drawWideShape`'s roots into kernels of at least 250 outputs, past the driver's ceiling of about
 * 180 groups, under option variants that reach every size mechanism - the regroup, the call-site
 * splits and their rollback, the split driver's stages, both grouping switches dropped, and the
 * decline. Every composition is checked row by row, under the defaults where its variant
 * declines. A mechanism the drawn compositions miss is drawn for, up to six more cycles of the
 * variants, and a run that still reaches no instance of it fails (`VarkaEmitTrace`, VARKA-289).
 *
 * Variants (VARKA-278): a draw that passes is emitted again in the variants of
 * `VarkaVariants` - beside a child, permuted, duplicated, copied, beside a stranger, its literals
 * in columns, its columns renumbered, an untaken arm replaced - each compared with the reference
 * evaluator through `VarkaVariantCheck`, so random trees larger than the small-tree series' meet
 * the variants too. The stranger is the draw's next root over copies of its columns, and an
 * untaken arm is replaced by one of the draw's own value subtrees, so every tree a variant adds
 * is one the draw's columns were drawn for. A variant's failure is reported with the variant and
 * the draw's replay, not shrunk.
 *
 * What the emitter reached (VARKA-280): the int-lane, long-lane and wide tests each print which
 * of the emitter's mechanisms (`VarkaEmitMechanism`) their emissions reached, which they never
 * did, and how many distinct plans and mechanism sets they produced (`VarkaEmitReach`); with
 * `VARKA_REACH_DUMP=<dir>` the reports are written there as `VarkaIrFuzzSuite.md`, the file under
 * `sql/varka/coverage/reach/` that row 265 reads. An opt-in test measures steering toward unseen
 * mechanisms against the plain draws (`VarkaSteering`), which the measurement kept out of the
 * main tests.
 *
 * Budget: `-Dvarka.fuzz.iterations` (default 300, a few seconds); `-Dvarka.fuzz.wide` (default
 * 10 compositions, a few seconds); `-Dvarka.fuzz.seed` (default fixed, so the committed run is
 * reproducible and a nightly can vary it). The iterations and the seed apply to both lanes.
 * `-Dvarka.fuzz.variantsEvery=n` takes the variants of every n-th draw (default 1, every draw;
 * 0 none), which is how the nightly's forty thousand draws a lane stay inside the test's cap.
 *
 * Under another JVM (VARKA-272, `dev/varka_platform.sh`): `-Dvarka.fuzz.tier=interpreter|c1|c2|
 * xcomp` names the tier the JVM's flags must show and the compiled test's rules follow,
 * `-Dvarka.fuzz.vectorSupport=true|false` what `EnableVectorSupport` must read, and
 * `-Dvarka.fuzz.answers=<file>` has every compared batch's digest written, for the comparison
 * across JVMs (`VarkaAnswerLog`).
 */
class VarkaIrFuzzSuite extends SparkFunSuite with VarkaMatrixTests with VarkaOwnJvm {

  // The grammar's bounds and seeds, by the names this suite has always used.
  private val columnBound = VarkaIrGrammar.COLUMN_BOUND
  private val intColumnBound = Math.toIntExact(columnBound)
  private val literalBound = VarkaIrGrammar.LITERAL_BOUND
  private val longColumnBound = VarkaIrGrammar.LONG_COLUMN_BOUND
  private val longLiteralBound = VarkaIrGrammar.LONG_LITERAL_BOUND
  private val fuzzSeed = VarkaIrGrammar.FUZZ_SEED
  private val longFuzzSeed = VarkaIrGrammar.LONG_FUZZ_SEED

  private val seed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(fuzzSeed)
  private val longSeed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(longFuzzSeed)
  private val mixedSeed =
    sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(VarkaIrGrammar.MIXED_FUZZ_SEED)
  private val iterations = sys.props.get("varka.fuzz.iterations").map(_.toInt).getOrElse(300)
  private val only = sys.props.get("varka.fuzz.only").map(_.toInt)
  private val variantsEvery = sys.props.get("varka.fuzz.variantsEvery").map(_.toInt).getOrElse(1)
  private val classCounter = new AtomicInteger(0)
  private val skippedPastTheCap = new AtomicInteger(0)
  /** Variants over the op cap of the form without a budget, skipped (VARKA-323). */
  private val skippedPastTheOpCap = new AtomicInteger(0)
  // What the random shapes' emissions did about size, reported after each run (VARKA-238).
  private val randomTrace = new VarkaEmitTrace
  // What each reporting test's emissions reached, for the file row 265 reads (VARKA-280).
  private val reachReports = scala.collection.mutable.ArrayBuffer.empty[VarkaEmitReach]
  private val lengths = Seq(1, 3, 7, 15, 16, 17, 33, 64, 65, 100, 257, 1000)

  /** The options `randomOptions` draws, in the order it draws them. */
  private val fuzzedOptions = VarkaEmitOption.TABLE.asScala.toSeq
    .filter(_.reason != VarkaEmitOption.Reason.FAULT_INJECTOR)
    .sortBy(o => "with" + o.name.head.toUpper + o.name.tail)

  /**
   * A random variant of the options record, drawn from the options table: every option but the
   * fault injectors, in the order of their `with*` setters' names, a boolean as a coin, an enum
   * as one of its constants, and an int one time in five from the values its table entry lists -
   * a lanes override from the powers of two, the AVX level from the levels either side of 3
   * where the 64-bit division changes lowering, the method byte budget from off, the HotSpot
   * limit and the smaller budgets that bring the size machinery down to the widths drawn here
   * (VARKA-238), and the other budgets from small values. The order is fixed so that a seed
   * recorded in a failure keeps drawing the same options.
   */
  private def randomOptions(rnd: Random): VarkaEmitOptions = {
    var opts = VarkaMatrix.base
    for (option <- fuzzedOptions) {
      option match {
        case flag: VarkaEmitOption.Flag => opts = flag.`with`(opts, rnd.nextBoolean())
        case choice: VarkaEmitOption.Choice[_] =>
          opts = choice.withIndex(opts, rnd.nextInt(choice.constants.size))
        case count: VarkaEmitOption.Count =>
          if (rnd.nextInt(5) == 0) {
            opts = count.`with`(opts, count.fuzzDraws.get(rnd.nextInt(count.fuzzDraws.size)))
          }
      }
    }
    opts
  }

  private val patternNames = Seq("null-free", "every-5th", "alternating", "all-null", "random")

  private def pattern(rnd: Random, which: Int, length: Int): Int => Boolean = which match {
    case 0 => _ => false
    case 1 => i => i % 5 == 0
    case 2 => i => i % 2 == 1
    case 3 => _ => true
    case _ =>
      val bits = Array.fill(length)(rnd.nextInt(3) == 0)
      i => bits(i)
  }

  /**
   * The shape's class, or None for a shape no form of the emitter holds. Under the byte budget
   * a shape whose single output is over the budget declines with a reason, by design (VARKA-87);
   * the heaviest trees are the ones most worth checking, so such a shape is run in the form
   * without the budget rather than skipped. When that form declines too, or the budget was off
   * and it declined at once, the reason is the class-file cap on a method's code, the one limit
   * the legacy form has: the JVM holds no method the emitter could make of the shape, the
   * decline is the emitter's answer (VARKA-219), and the shape is counted and skipped. Anything
   * else the emitter throws is a failure - it rejected a shape the grammar builds.
   *
   * `overTheOpCap` says the emitter's form without a budget cannot hold the shape, by
   * `fitsBudgets`: a variant that grew a draw past `MAX_FUSED_NODES` or `MAX_CHAIN_DEPTH`, which
   * the grammar's own shapes never are (VARKA-323). It is asked only when the emitter rejects the
   * shape. Such a shape is counted and skipped, not failed, when the rejection is that cap's and
   * the form that threw is one the cap applies to: the chain depth in either form, the op count
   * only without a budget. A draw whose options have no budget is first retried with the
   * defaults' budget, which has no op cap, so the shape is still checked where that form holds
   * it. Any other rejection fails.
   */
  private def emitOrSkip(context: String, options: VarkaEmitOptions,
      overTheOpCap: => Boolean = false)(
      emitWith: VarkaEmitOptions => Array[Byte]): Option[Array[Byte]] = {
    // Whether `e`, thrown by the form `budget` selects, is the cap the shape is over.
    def capRejection(e: IllegalArgumentException, budget: Int): Boolean = {
      val message = Option(e.getMessage).getOrElse("")
      (message.contains("MAX_CHAIN_DEPTH=") ||
        (budget == 0 && message.contains("MAX_FUSED_NODES="))) && overTheOpCap
    }
    def pastTheOpCap(): Option[Array[Byte]] = {
      skippedPastTheOpCap.incrementAndGet()
      None
    }
    def pastTheCap(d: VarkaEmitDeclined): Option[Array[Byte]] = {
      assert(d.getMessage.contains("over the class-file cap of"),
        s"$context: declined with no budget to decline on: ${d.getMessage}")
      skippedPastTheCap.incrementAndGet()
      None
    }
    // The form without a budget, after the budget form declined on size.
    def withoutBudget(): Option[Array[Byte]] =
      try {
        Some(emitWith(options.withMethodByteBudget(0)))
      } catch {
        case again: VarkaEmitDeclined => pastTheCap(again)
        case e: IllegalArgumentException if capRejection(e, 0) => pastTheOpCap()
        case e: IllegalArgumentException =>
          fail(s"$context: the emitter rejected the shape without the budget: " +
            e.getMessage, e)
        case e: IllegalStateException =>
          fail(s"$context: the emitter failed its own check without the budget: " +
            e.getMessage, e)
      }
    // A shape over the op cap in a draw with no budget, in the defaults' budget form.
    def withBudget(): Option[Array[Byte]] = {
      val budgeted = options.withMethodByteBudget(VarkaEmitOptions.DEFAULTS.methodByteBudget())
      try {
        Some(emitWith(budgeted))
      } catch {
        case _: VarkaEmitDeclined => pastTheOpCap()
        case e: IllegalArgumentException if capRejection(e, budgeted.methodByteBudget()) =>
          pastTheOpCap()
        case e: IllegalArgumentException =>
          fail(s"$context: the emitter rejected the shape under the defaults' budget: " +
            e.getMessage, e)
        case e: IllegalStateException =>
          fail(s"$context: the emitter failed its own check under the defaults' budget: " +
            e.getMessage, e)
      }
    }
    try {
      Some(emitWith(options))
    } catch {
      case d: VarkaEmitDeclined if options.methodByteBudget() > 0 =>
        assert(d.getMessage.contains("bytes"), s"$context: a size decline without a size")
        withoutBudget()
      case d: VarkaEmitDeclined => pastTheCap(d)
      case e: IllegalArgumentException if capRejection(e, options.methodByteBudget()) =>
        if (options.methodByteBudget() == 0 && !e.getMessage.contains("MAX_CHAIN_DEPTH=")) {
          withBudget()
        } else {
          pastTheOpCap()
        }
      case e: IllegalArgumentException =>
        fail(s"$context: the emitter rejected the shape: ${e.getMessage}", e)
      // One of the emitter's own invariants, such as the word check: named with the shape, so
      // a failure found at a high iteration count says which iteration to replay.
      case e: IllegalStateException =>
        fail(s"$context: the emitter failed its own check: ${e.getMessage}", e)
    }
  }

  private def drawInt(iteration: Int, drawSeed: Long = seed): VarkaFuzzCase = {
    val rnd = VarkaIrGrammar.shapeRandom(drawSeed, iteration)
    // The shape itself comes from the shared draw, so this suite and the emitted-bytes oracle
    // run over one corpus; `rnd` is left where the lane values and null patterns below pick up.
    val drawn = VarkaIrGrammar.drawShape(rnd)
    val roots = drawn.roots
    val (numInputs, numLiterals) = (drawn.numInputs, drawn.numLiterals)
    val (smallOrdinal, levelOrdinal) = (drawn.smallOrdinal, drawn.levelOrdinal)
    val lits = Array.fill(numLiterals)(rnd.nextInt(2 * literalBound + 1) - literalBound)
    val length = lengths(rnd.nextInt(lengths.length))
    val patternIds = Seq.fill(numInputs)(rnd.nextInt(patternNames.length))
    val patterns = patternIds.map(pattern(rnd, _, length))
    // Forcing the masked path reports one null over a full bitmap, which the dispatcher reads
    // as "has nulls". Never at length 1: a null count equal to the length is the contract's
    // all-null column, and the fuzzer's first run found exactly that contradiction (44 cases,
    // every one at length 1) before it found anything about the kernel.
    val forceMasked = length > 1 && rnd.nextInt(4) == 0
    val options = randomOptions(rnd)
    val data = Array.tabulate(numInputs, length) { (c, _) =>
      if (c == levelOrdinal) {
        // Exactly the codes TruncLevelLeaf produces; a value outside them is a lane the
        // kernel's contract does not define, so the fuzzer must not invent one.
        DateTimeUtils.TRUNC_TO_WEEK + rnd.nextInt(
          DateTimeUtils.TRUNC_TO_YEAR - DateTimeUtils.TRUNC_TO_WEEK + 1)
      } else {
        // Uniform over the column's bound, the draw `VarkaIrGrammar.within` documents.
        VarkaIrGrammar.intWithin(rnd,
          if (c == smallOrdinal) VarkaChrono.MONTH_ARITH_MAX_MONTHS else intColumnBound)
      }
    }

    val context = s"seed=$drawSeed iteration=$iteration " +
      s"roots=${roots.asScala.map(r => VarkaVectorIR.canonical(r)).mkString("[", ", ", "]")} " +
      s"options=${if (options.isDefault) "(defaults)" else options.canonical()} " +
      s"length=$length patterns=${patternIds.map(patternNames).mkString(",")} " +
      s"literals=${lits.mkString(",")} forceMasked=$forceMasked"

    new VarkaFuzzCase(LaneType.INT, roots, numInputs, lits.map(_.toLong), length,
      Array.tabulate(numInputs, length)((c, i) => patterns(c)(i)), data.map(_.map(_.toLong)),
      forceMasked, options, smallOrdinal, levelOrdinal, context)
  }

  /**
   * `runOne` at the long lane: the same draw of length, null patterns, masking and options
   * over a long-lane shape, 64-bit buffers, the eight-argument `run`, and `evalLong` as the
   * oracle. Null lanes are poisoned with the lane's own extremes, for the reason `runOne`
   * gives: every drawn value is inside the guards and the checked modes by construction, so
   * only a poisoned null lane can reach a condemning comparison, and a kernel that reads one
   * has to be caught reading it.
   */
  private def drawLong(iteration: Int, drawSeed: Long = longSeed): VarkaFuzzCase = {
    val rnd = VarkaIrGrammar.shapeRandom(drawSeed, iteration)
    val drawn = VarkaIrGrammar.drawLongShape(rnd)
    val roots = drawn.roots
    val (numInputs, numLiterals) = (drawn.numInputs, drawn.numLiterals)
    val lits = Array.fill(numLiterals)(VarkaIrGrammar.within(rnd, longLiteralBound))
    val length = lengths(rnd.nextInt(lengths.length))
    val patternIds = Seq.fill(numInputs)(rnd.nextInt(patternNames.length))
    val patterns = patternIds.map(pattern(rnd, _, length))
    val forceMasked = length > 1 && rnd.nextInt(4) == 0
    val options = randomOptions(rnd)
    val data =
      Array.tabulate(numInputs, length)((_, _) => VarkaIrGrammar.within(rnd, longColumnBound))

    val context = s"lane=long seed=$drawSeed iteration=$iteration " +
      s"roots=${roots.asScala.map(r => VarkaVectorIR.canonical(r)).mkString("[", ", ", "]")} " +
      s"options=${if (options.isDefault) "(defaults)" else options.canonical()} " +
      s"length=$length patterns=${patternIds.map(patternNames).mkString(",")} " +
      s"literals=${lits.mkString(",")} forceMasked=$forceMasked"

    new VarkaFuzzCase(LaneType.LONG, roots, numInputs, lits, length,
      Array.tabulate(numInputs, length)((c, i) => patterns(c)(i)), data, forceMasked, options,
      -1, -1, context)
  }

  /**
   * Emits the case's kernel and compares it with the reference evaluator, row by row. Throws
   * what the check throws; returns false for a shape past the class-file cap, which
   * `emitOrSkip` counts and skips, and true once the rows are compared.
   */
  private def run(c: VarkaFuzzCase): Boolean = {
    val long = c.lane == LaneType.LONG
    val className = "org.apache.spark.sql.varka.execution.VarkaFusedFuzz" +
      s"${if (long) "Long" else ""}${classCounter.addAndGet(1)}"
    def emitWith(o: VarkaEmitOptions): Array[Byte] = VarkaLoopEmitter.emitTraced(
      className, c.roots, c.numInputs, c.lits.length, o, randomTrace)
    val bytes = emitOrSkip(c.label, c.options)(emitWith) match {
      case Some(b) => b
      case None => return false
    }
    // The case's own null flags and roots, handed to the Java checks as they are.
    if (long) {
      VarkaKernelChecks.runAndCompareLong(c.label, className, bytes, c.roots, c.numInputs,
        c.lits, new VarkaKernelChecks.LongBatch(c.length, c.nulls, c.data, c.forceMasked),
        false, null)
    } else {
      VarkaKernelChecks.runAndCompare(c.label, className, bytes, c.roots, c.numInputs,
        c.lits.map(_.toInt),
        new VarkaKernelChecks.Batch(c.length, c.nulls, c.data.map(_.map(_.toInt)), c.forceMasked),
        false, null)
    }
    true
  }

  /** What `run(c)` threw as a signature; None when the case passes or is skipped. */
  private def outcomeOf(c: VarkaFuzzCase): Option[VarkaFailureSignature] =
    try {
      run(c)
      None
    } catch {
      case e: VirtualMachineError => throw e
      case e: InterruptedException => throw e
      case t: Throwable => Some(VarkaFailureSignature.of(t, c.label))
    }

  /** Off with `-Dvarka.fuzz.shrink=false`, which leaves a failure as the generator drew it. */
  private val shrinkFailures = !sys.props.get("varka.fuzz.shrink").contains("false")

  /**
   * Runs the case. A failure - any `Throwable`, a mismatch or an error out of the generated
   * class - is shrunk (VARKA-277) and rethrown as a test failure whose message has the original
   * and the smaller case. True when the case was compared and passed.
   */
  private def runChecked(c: VarkaFuzzCase): Boolean = {
    try run(c) catch {
      case e: VirtualMachineError => throw e
      case e: InterruptedException => throw e
      case t: Throwable if shrinkFailures =>
        val signature = VarkaFailureSignature.of(t, c.label)
        if (VarkaKnownFailures.isKnown(VarkaKnownFailures.entries, signature)) {
          logWarning(s"known fuzz failure, not shrunk: $signature")
          return false
        }
        val shrunk = shrink(c, signature)
        val early = (if (shrunk.stoppedEarly) ", budget reached" else "") +
          (if (shrunk.stable) "" else ", UNSTABLE: it did not fail the same way three times")
        fail(Option(t.getMessage).getOrElse(t.getClass.getName) +
          s"\n  shrunk to ${VarkaFuzzCase.describe(shrunk.small)}" +
          s"\n  (${shrunk.runs} runs, ${shrunk.millis} ms$early); signature: $signature", t)
    }
  }

  private def runOne(iteration: Int): Unit = {
    val c = drawInt(iteration)
    if (runChecked(c) && variantsDue(iteration)) runVariants(c, iteration)
  }

  private def runOneLong(iteration: Int): Unit = {
    val c = drawLong(iteration)
    if (runChecked(c) && variantsDue(iteration)) runVariants(c, iteration)
  }

  /**
   * One draw of the third corpus, trees of both lanes (VARKA-28): the same draw of length, null
   * patterns, masking and options, the int columns' values inside the int bound and the long
   * columns' inside the long one, both literal tables, and `VarkaKernelChecks.runAndCompareMixed`
   * as the check. Not shrunk on failure: the shrinker's case holds one literal table, and the
   * failure names its seed and iteration for a replay.
   */
  private def runOneMixed(iteration: Int): Unit = {
    val rnd = VarkaIrGrammar.shapeRandom(mixedSeed, iteration)
    val drawn = VarkaIrGrammar.drawMixedShape(rnd)
    val roots = drawn.roots
    val numInputs = drawn.numInputs
    val intLits = Array.fill(drawn.intLiterals)(VarkaIrGrammar.intWithin(rnd, literalBound))
    val longLits = Array.fill(drawn.longLiterals)(VarkaIrGrammar.within(rnd, longLiteralBound))
    val length = lengths(rnd.nextInt(lengths.length))
    val patternIds = Seq.fill(numInputs)(rnd.nextInt(patternNames.length))
    val patterns = patternIds.map(pattern(rnd, _, length))
    val forceMasked = length > 1 && rnd.nextInt(4) == 0
    val options = randomOptions(rnd)
    val data = Array.tabulate(numInputs, length) { (c, _) =>
      if (c < drawn.intInputs) VarkaIrGrammar.intWithin(rnd, intColumnBound).toLong
      else VarkaIrGrammar.within(rnd, longColumnBound)
    }
    val label = s"lane=mixed seed=$mixedSeed iteration=$iteration " +
      s"roots=${roots.asScala.map(r => VarkaVectorIR.canonical(r)).mkString("[", ", ", "]")} " +
      s"options=${if (options.isDefault) "(defaults)" else options.canonical()} " +
      s"length=$length patterns=${patternIds.map(patternNames).mkString(",")} " +
      s"intLiterals=${intLits.mkString(",")} longLiterals=${longLits.mkString(",")} " +
      s"forceMasked=$forceMasked"
    val className = "org.apache.spark.sql.varka.execution.VarkaFusedFuzzMixed" +
      classCounter.addAndGet(1)
    val numLiterals = drawn.numLiterals
    def emitWith(o: VarkaEmitOptions): Array[Byte] = VarkaLoopEmitter.emitTraced(
      className, roots, numInputs, numLiterals, o, randomTrace)
    emitOrSkip(label, options)(emitWith).foreach { bytes =>
      VarkaKernelChecks.runAndCompareMixed(label, className, bytes, roots, numInputs, intLits,
        longLits, new VarkaKernelChecks.LongBatch(length,
          Array.tabulate(numInputs, length)((c, i) => patterns(c)(i)), data, forceMasked),
        false, null)
    }
  }

  private def variantsDue(iteration: Int): Boolean =
    variantsEvery > 0 && iteration % variantsEvery == 0

  /** What the variants of passing draws came to, by variant and outcome, reported after a run. */
  private val variantCounts = new java.util.concurrent.ConcurrentHashMap[String, AtomicInteger]

  private def countVariant(kind: VarkaVariants.Kind, outcome: String): Unit =
    variantCounts.computeIfAbsent(s"${kind.label} $outcome", _ => new AtomicInteger)
      .incrementAndGet()

  /**
   * The variants of a draw that passed (VARKA-278), each emitted as the draw was - through
   * `emitOrSkip`, its checks on a decline's reason, its count of shapes past the cap and the size
   * trace - and compared through `VarkaVariantCheck`. The stranger is the draw's next root, or
   * its only one, over copies of the columns; an untaken arm's replacement is one of the draw's
   * own value subtrees. A failure, building the variants included, fails the test with the draw
   * to replay.
   */
  /**
   * Whether the emitter's form without a byte budget holds a shape, as it holds every draw: the
   * op cap and the chain depth. `fitsBudgets` reads only the budget from the options, and the
   * budget is what is set to 0 here.
   */
  private def fitsWithoutBudget(roots: java.util.List[VarkaVectorIR], numInputs: Int): Boolean =
    VarkaLoopEmitter.fitsBudgets(roots, numInputs,
      VarkaEmitOptions.DEFAULTS.withMethodByteBudget(0))

  private def runVariants(c: VarkaFuzzCase, iteration: Int): Unit = {
    val emit: VarkaVariantCheck.Emit = (roots, numInputs, numLiterals, options, className) =>
      emitOrSkip(s"${c.label} (a variant)", options,
          !fitsWithoutBudget(roots.asJava, numInputs))(o => VarkaLoopEmitter.emitTraced(
        className, roots.asJava, numInputs, numLiterals, o, randomTrace))
    def failed(what: String, label: String, t: Throwable): Unit = {
      val signature = VarkaFailureSignature.of(t, label)
      if (VarkaKnownFailures.isKnown(VarkaKnownFailures.entries, signature)) {
        logWarning(s"known fuzz failure in a variant, not counted: $signature")
      } else {
        fail(s"$what of a passing draw failed: " +
          Option(t.getMessage).getOrElse(t.getClass.getName) +
          s"\n  draw: ${VarkaFuzzCase.describe(c)}", t)
      }
    }
    val variants = try {
      val pool = VarkaVariants.valueSubtrees(c.roots).asScala.toSeq
      // The draw's roots as strangers, from the next one round: the first whose variant the
      // form without a budget holds, as the grammar's draws are held.
      val strangers =
        (0 until c.roots.size).map(i => c.roots.get((iteration + 1 + i) % c.roots.size))
      VarkaVariantCheck.variants(c, strangers, pool, iteration,
        v => fitsWithoutBudget(v.roots, v.numInputs))
    } catch {
      case e: VirtualMachineError => throw e
      case e: InterruptedException => throw e
      case t: Throwable =>
        failed("building the variants", c.label, t)
        Seq.empty
    }
    for ((kind, variant) <- variants; v <- variant) {
      try {
        VarkaVariantCheck.check(c, v, emit) match {
          case VarkaVariantCheck.Compared => countVariant(kind, "compared")
          case VarkaVariantCheck.Declined(_) => countVariant(kind, "declined")
          case VarkaVariantCheck.Skipped => countVariant(kind, "skipped")
        }
      } catch {
        case e: VirtualMachineError => throw e
        case e: InterruptedException => throw e
        case t: Throwable =>
          failed(s"the ${kind.label} variant", VarkaVariantCheck.caseOf(c, v).label, t)
      }
    }
  }


  /**
   * The variants of draw `iteration` at `drawSeed`, run as the fuzzer runs them: how many were
   * skipped past the op cap, and each kind's outcomes. The draw must pass. The suite's counters
   * are left as they were, so the fuzz tests after these report only their own draws.
   */
  private def variantsOf(drawSeed: Long, iteration: Int,
      adjust: VarkaFuzzCase => VarkaFuzzCase = identity): (Int, Map[String, Int]) = {
    val c = adjust(drawInt(iteration, drawSeed))
    assert(runChecked(c), s"draw $iteration at seed $drawSeed did not pass")
    val (opCap, cap) = (skippedPastTheOpCap.getAndSet(0), skippedPastTheCap.get())
    val counts = new java.util.HashMap[String, AtomicInteger](variantCounts)
    variantCounts.clear()
    try {
      runVariants(c, iteration)
      (skippedPastTheOpCap.get(), variantCounts.asScala.map { case (k, v) => k -> v.get }.toMap)
    } finally {
      skippedPastTheOpCap.set(opCap)
      skippedPastTheCap.set(cap)
      variantCounts.clear()
      variantCounts.putAll(counts)
    }
  }

  /** Whether the variant `runVariants` would have taken first is over the op cap. */
  private def firstChoiceOver(drawSeed: Long, iteration: Int,
      kind: VarkaVariants.Kind): Boolean = {
    val c = drawInt(iteration, drawSeed)
    val first = kind match {
      case VarkaVariants.Kind.BESIDE_A_STRANGER => Some(VarkaVariants.besideAStranger(c.roots,
        c.numInputs, c.roots.get((iteration + 1) % c.roots.size)))
      case _ => VarkaVariants.untakenArm(c.roots, c.numInputs, VarkaVariantCheck.armOracle(c),
        VarkaVariants.valueSubtrees(c.roots), iteration).toScala
    }
    first.exists(v => !fitsWithoutBudget(v.roots, v.numInputs))
  }

  /** A kernel's bytes under a class name, as the compiled histories take it. */
  private type Emit = java.util.function.Function[String, Array[Byte]]

  /** Draws a lane for the compiled histories: `-Dvarka.fuzz.compiled` (VARKA-274). */
  private val compiledDraws = sys.props.get("varka.fuzz.compiled").map(_.toInt).getOrElse(20)

  /**
   * The census's switches (VARKA-326): the first draw of the range (`-Dvarka.fuzz.compiledFrom`,
   * so JVMs split a range), the draws' options (`-Dvarka.fuzz.compiledOptions=defaults` takes the
   * matrix base instead of the drawn ones), and one line per history run on standard output
   * (`-Dvarka.fuzz.census=true`) naming the class the JVM's logs call it by. The same switch
   * prints, once the witness has settled, one `VARKA_COMPILED` line per history run with what its
   * calls came to, which `dev/varka_compiled_fuzz.py` totals over the nightly's JVMs (VARKA-274).
   */
  private val compiledFrom = sys.props.get("varka.fuzz.compiledFrom").map(_.toInt).getOrElse(0)
  private val compiledBaseOptions = sys.props.get("varka.fuzz.compiledOptions").contains("defaults")
  private val censusLines = sys.props.get("varka.fuzz.census").contains("true")

  /** Whether a history not reached fails the compiled test: only on a quiet machine. */
  private val requireTiers = sys.props.get("varka.fuzz.requireTiers").contains("true")

  /**
   * The case as the compiled histories run it, and its kernel under any class name: the options
   * `emitOrSkip` settled on, the budget's own or the form without it, so every history emits what
   * the main test checks. None for a shape past the class-file cap.
   */
  private def compiledForm(c: VarkaFuzzCase): Option[(VarkaFuzzCase, Emit)] = {
    var used = c.options
    def emitWith(o: VarkaEmitOptions)(name: String): Array[Byte] = VarkaLoopEmitter.emitTraced(
      name, c.roots, c.numInputs, c.lits.length, o, randomTrace)
    emitOrSkip(c.label, c.options) { o =>
      val bytes = emitWith(o)("org.apache.spark.sql.varka.execution.VarkaFusedFuzzProbe")
      used = o
      bytes
    }.map(_ => (c.withOptions(used), (name: String) => emitWith(used)(name)))
  }

  /**
   * What one history of `c` throws, as the failure signature of the check's own failure; None
   * when it passes. The shrinker holds a smaller case to the history that failed, not to `cold`,
   * since a failure of compiled code need not show in the interpreter.
   */
  private def historyOutcome(c: VarkaFuzzCase, h: VarkaCompiledCheck.History,
      settings: VarkaCompiledCheck.Settings): Option[VarkaFailureSignature] =
    compiledForm(c).flatMap { case (form, emit) =>
      try {
        VarkaCompiledCheck.run(form, emit, settings.withOnly(h), "")
        None
      } catch {
        case f: VarkaCompiledCheck.HistoryFailure =>
          Some(VarkaFailureSignature.of(f.getCause, c.label))
      }
    }

  /**
   * One case over the histories, a failure handled as `runChecked` handles one: a known failure
   * is logged and the case left; a new one is shrunk against the history that failed and fails
   * the test with the original, the smaller case and its signature. Returns the case's result,
   * None where it was left or past the cap.
   */
  private def runCompiled(c: VarkaFuzzCase, settings: VarkaCompiledCheck.Settings,
      replay: String): Option[VarkaCompiledCheck.Result] = compiledForm(c).flatMap {
    case (form, emit) =>
      try Some(VarkaCompiledCheck.run(form, emit, settings, replay)) catch {
        case f: VarkaCompiledCheck.HistoryFailure =>
          val signature = VarkaFailureSignature.of(f.getCause, c.label)
          if (VarkaKnownFailures.isKnown(VarkaKnownFailures.entries, signature)) {
            logWarning(s"known fuzz failure in history ${f.history.label}, not shrunk: $signature")
            None
          } else if (!shrinkFailures) {
            throw f
          } else {
            val shrunk = VarkaShrinker.shrink(c, signature, (x: VarkaFuzzCase) =>
              historyOutcome(x.withLabel("case"), f.history, settings).toJava)
            val early = (if (shrunk.stoppedEarly) ", budget reached" else "") +
              (if (shrunk.stable) "" else ", UNSTABLE: it did not fail the same way three times")
            fail(f.getMessage + s"\n  shrunk to ${VarkaFuzzCase.describe(shrunk.small)} in " +
              s"history ${f.history.label}\n  (${shrunk.runs} runs, ${shrunk.millis} ms$early); " +
              s"signature: $signature", f)
          }
      }
  }

  /**
   * One `VARKA_COMPILED` line per history run of `results`, each paired with its draw, for the
   * nightly's reader (VARKA-274): the lane, the draw, the history, what its counted calls came to
   * (`Tally.kind`, `-` with the witness off or no counted call), whether its warm-up settled and
   * was confirmed, the settle and wall times in ms, an unsettled warm-up's trap reasons (`-`
   * otherwise), and the case's label on one line. Printed after the witness has settled, since a
   * verdict cannot be read before.
   */
  private def printRunLines(results: Seq[(Int, VarkaCompiledCheck.Result)],
      witnessed: Boolean): Unit = {
    for ((k, r) <- results; run <- r.runs().asScala if run.skipped() == null) {
      val kind = if (witnessed && !run.countedVerdicts().isEmpty) {
        VarkaCompiledCheck.Tally.kind(run)
      } else "-"
      val confirmed = witnessed && run.confirmed()
      val traps = if (run.warmed() && !run.settled() && witnessed) {
        VarkaTierWitness.get().trapReasons(run.className()).toString.replace(" ", "")
      } else "-"
      val label = r.label().replaceAll("\\s+", " ")
      // scalastyle:off println
      println(s"VARKA_COMPILED\t${r.lane()}\t$k\t${run.history().label}\t$kind\t" +
        s"${run.settled()}\t$confirmed\t${run.settleMillis()}\t${run.millis()}\t$traps\t$label")
      // scalastyle:on println
    }
  }

  /**
   * The first `compiledDraws` draws of each lane, each run over the eight histories of
   * `VarkaCompiledCheck` - its answers compared in the interpreter, in C2's code after a warm-up,
   * across a trap and a recompile, and on the way up the tiers with C1 on - and the tier that
   * served each compared call read by `VarkaTierWitness`. A draw whose options set a lanes
   * override runs `cold` only, after every other draw: a second vector species in the JVM makes
   * every later kernel's C2 code box its vectors, which no user's JVM runs. Registered first, so
   * that the other tests' override draws have not run yet.
   *
   * A wrong answer fails, wherever the suite runs. A history whose tier never showed is counted
   * and printed, and fails only under `-Dvarka.fuzz.requireTiers=true`: the suite runs beside other
   * JVMs in the gate, in CI and one JVM per core at night, where C2 compiles late, and the tier
   * facts are held hard by `VarkaTierWitnessSuite` on fixed shapes in the quiet phase. The
   * recording is stopped when the test ends, so the later tests do not pay for it.
   */
  test(s"random IR trees compiled by C2 match the reference evaluator over eight batch " +
      s"histories ($compiledDraws draws a lane)") {
    val settings = VarkaCompiledCheck.Settings.fromProperties()
    // The tier this JVM claims (VARKA-272): its flags are held to the claim first, so that a
    // forked arm whose command line lost a flag fails here, as not the JVM it says it is, and
    // never as a kernel's answer; the facts line is what the arms' report reads.
    val tier = VarkaCompiledCheck.Tier.fromProperty()
    val vectorSupport = sys.props.get("varka.fuzz.vectorSupport").map(_.toBoolean)
    val facts = VarkaPlatformFacts.read()
    info(facts.line())
    val contradictions =
      facts.contradictions(tier, vectorSupport.map(java.lang.Boolean.valueOf).toJava).asScala
    assert(contradictions.isEmpty,
      s"this JVM is not the ${tier.label} tier the run claims: ${contradictions.mkString("; ")}")
    val tally = new VarkaCompiledCheck.Tally
    val drawOf = scala.collection.mutable.ArrayBuffer[(Int, VarkaCompiledCheck.Result)]()
    val overrides = scala.collection.mutable.ArrayBuffer[(Int, VarkaFuzzCase, String)]()
    val draws: Seq[(Int => VarkaFuzzCase, Long)] =
      Seq((k => drawInt(k), seed), (k => drawLong(k), longSeed))
    try {
      val range = compiledFrom until compiledDraws
      for ((draw, drawSeed) <- draws; k <- only.map(Seq(_)).getOrElse(range)) {
        val drawn = draw(k)
        val c = if (compiledBaseOptions) drawn.withOptions(VarkaMatrix.base) else drawn
        val replay = s"-Dvarka.fuzz.seed=$drawSeed -Dvarka.fuzz.only=$k"
        if (c.options.lanesOverride() != 0) {
          overrides += ((k, c, replay))
        } else {
          runCompiled(c, settings, replay).foreach { r =>
            tally.add(r)
            drawOf += ((k, r))
            if (censusLines) {
              r.runs().forEach(run => if (run.skipped() == null) {
                // scalastyle:off println
                println(s"VARKA_CENSUS\t${c.lane()}\t$k\t${run.history().label}\t" +
                  s"${run.className()}\t${run.settled()}\t${run.settleMillis()}\t${c.label}")
                // scalastyle:on println
              })
            }
          }
        }
      }
      if (settings.runs(VarkaCompiledCheck.History.COLD)) {
        for ((k, c, replay) <- overrides) {
          runCompiled(c, settings.withOnly(VarkaCompiledCheck.History.COLD), replay).foreach {
            r => tally.add(r); tally.addColdOnly(); drawOf += ((k, r))
          }
        }
      }
      // The draws are the main tests' first ones; their skips are counted there.
      skippedPastTheCap.set(0)
      skippedPastTheOpCap.set(0)
      if (settings.witnessed) VarkaTierWitness.get().settle()
      tally.report(settings.witnessed).asScala.foreach(info(_))
      if (settings.witnessed) {
        val notReached = VarkaCompiledCheck.notReached(tally, tier).asScala
        info(s"${notReached.size} history(ies) not reached" +
          (if (requireTiers) "" else " (counted; -Dvarka.fuzz.requireTiers=true fails on them)"))
        notReached.foreach(why => if (requireTiers) fail(why) else info(why))
      }
    } finally {
      // In the finally, so a JVM whose test failed still gives the nightly the cases it ran; a
      // failure here is logged, never put in place of the test's own.
      try if (censusLines) {
        try {
          if (settings.witnessed) VarkaTierWitness.get().settle()
          printRunLines(drawOf.toSeq, settings.witnessed)
        } catch {
          case scala.util.control.NonFatal(e) => logWarning(s"no VARKA_COMPILED lines: $e")
        }
      } finally VarkaTierWitness.close()
    }
  }

  // VARKA-323: draws whose variants grow past the op cap and failed, before it, as shapes the
  // emitter rejected. A variant that fits is preferred, and a variant with no form that fits is
  // skipped, counted. Each test first checks that its draw still grows past the cap, so a change
  // to the grammar that moves these draws fails here rather than leaving the tests passing on
  // draws that test nothing. The first draws were of the night of 12 October 2026; VARKA-335's
  // bitwise arms moved them, and these were found again by scanning the same nights' seeds.
  test("a variant grown past the op cap with no smaller form is skipped, not failed") {
    // Every one of the untaken-arm variant's replacements puts it over the cap.
    assert(firstChoiceOver(2026101303002L, 4259, VarkaVariants.Kind.UNTAKEN_ARM))
    val (opCap, counts) = variantsOf(2026101303002L, 4259)
    assert(opCap == 1 && counts.get("untaken arm replaced skipped").contains(1), counts)
  }

  test("with no byte budget, a variant over the op cap is tried under the defaults' budget") {
    // The same draw with the budget off: the form without one rejects the variant at once, the
    // defaults' budget form is tried, declines it on size, and the variant is skipped.
    val (opCap, counts) = variantsOf(2026101303002L, 4259,
      c => c.withOptions(c.options.withMethodByteBudget(0)))
    assert(opCap == 1 && counts.get("untaken arm replaced skipped").contains(1), counts)
  }

  test("a variant grown past the op cap is rebuilt within it where it can be") {
    // An untaken-arm replacement that fits, and a stranger that fits. The stranger's draw is
    // rare: one in the first 200,000 draws of a hundred seeds, searched for when a grammar change
    // redrew the draw named before - VARKA-334's null test over a condition, then VARKA-335's
    // bitwise arms, which redrew both draws here - so it is named rather than searched for.
    assert(firstChoiceOver(2026101307006L, 32279, VarkaVariants.Kind.UNTAKEN_ARM))
    val (armSkips, arm) = variantsOf(2026101307006L, 32279)
    assert(armSkips == 0 && arm.get("untaken arm replaced compared").contains(1), arm)
    assert(firstChoiceOver(2026101300002L, 195413, VarkaVariants.Kind.BESIDE_A_STRANGER))
    val (strangerSkips, stranger) = variantsOf(2026101300002L, 195413)
    assert(strangerSkips == 0 && stranger.get("beside a stranger compared").contains(1), stranger)
  }

  /** `c` with the planted-bug option `name` on. */
  private def planted(c: VarkaFuzzCase, name: String): VarkaFuzzCase =
    c.withOptions(VarkaEmitOption.named(name) match {
      case flag: VarkaEmitOption.Flag => flag.`with`(c.options, true)
      case count: VarkaEmitOption.Count => count.`with`(c.options, 1)
      case other => fail(s"$name is not a planted-bug option: $other")
    })

  /**
   * The first of the first 200 draws that fails with the planted-bug option `name` on, and its
   * signature. Which draws a planted bug reaches depends on the seed, so a test that needs a
   * failing case searches for one rather than naming a draw (VARKA-302).
   */
  private def firstPlantedFailure(name: String): (VarkaFuzzCase, VarkaFailureSignature) =
    (0 until 200).iterator.map(k => planted(drawInt(k), name))
      .map(c => (c, outcomeOf(c))).collectFirst { case (c, Some(sig)) => (c, sig) }
      .getOrElse(fail(s"$name: no failing case in 200 draws (seed $seed)"))

  /** A planted-bug case's failure, shrunk: the first of the first 200 draws that fails. */
  private def shrunkPlanted(name: String): (VarkaFuzzCase, VarkaFailureSignature,
      VarkaShrinker.Shrunk) = {
    val (c, sig) = firstPlantedFailure(name)
    (c, sig, shrink(c, sig))
  }

  /** `c` shrunk, each candidate run under the label "case" so the signature compares alone. */
  private def shrink(c: VarkaFuzzCase, signature: VarkaFailureSignature): VarkaShrinker.Shrunk =
    VarkaShrinker.shrink(c, signature, (x: VarkaFuzzCase) => outcomeOf(x.withLabel("case")).toJava)

  private def nodes(c: VarkaFuzzCase): Int = c.roots.asScala.map(VarkaShrinker.size(_)).sum

  private def contains(node: VarkaVectorIR, cls: Class[_]): Boolean =
    cls.isInstance(node) || VarkaVectorIR.childrenOf(node).exists(contains(_, cls))

  test("a planted liveness bug shrinks to one small root, the option alone and one row") {
    val (c, sig, shrunk) = shrunkPlanted("misdescribeWordLiveness")
    val small = shrunk.small
    assert(small.roots.size == 1 && nodes(small) <= 5, VarkaFuzzCase.describe(small))
    assert(VarkaFuzzCase.optionDelta(small.options).contains("misdescribeWordLiveness=true"))
    assert(VarkaFuzzCase.optionDelta(small.options).size <= 3)
    assert(small.length <= 17)
    assert(sig.kind == "emitter rejection", sig)
    assert(shrunk.runs <= 600 && shrunk.millis < 60000L && !shrunk.stoppedEarly)
    assert(shrunk.stable)
    // A seed's first failing draw can already be within the bound above (three nodes at seed
    // 202610133, two at 202610181), and then there is nothing to remove; a larger one must come
    // out smaller.
    if (nodes(c) > 5) assert(nodes(small) < nodes(c))
  }

  test("a planted bug in the generated class is classified and shrunk, not left to escape") {
    val (_, sig, shrunk) = shrunkPlanted("misdescribeAdd")
    val small = shrunk.small
    assert(sig.kind == "generated class: NoSuchMethodError", sig)
    assert(small.roots.asScala.exists(contains(_, classOf[AddDays])),
      VarkaFuzzCase.describe(small))
    assert(VarkaFuzzCase.optionDelta(small.options).contains("misdescribeAdd=true"))
    assert(shrunk.runs <= 600 && shrunk.millis < 60000L && shrunk.stable)
  }

  test("a failure of the generated class fails its test with the smaller case in the message") {
    val (c, _) = firstPlantedFailure("misdescribeWordLiveness")
    val e = intercept[org.scalatest.exceptions.TestFailedException](runChecked(c))
    assert(e.getMessage.contains("shrunk to lane=int"), e.getMessage)
    assert(e.getMessage.contains("signature: emitter rejection"), e.getMessage)
  }

  test("every known failure still reproduces") {
    val replay = (e: VarkaKnownFailures.Entry) => e.lane match {
      case "int" => outcomeOf(drawInt(e.iteration, e.seed))
      case "long" => outcomeOf(drawLong(e.iteration, e.seed))
      // A composition fuzzer's entry (VARKA-294) is replayed by its own suite.
      case _ => Some(e.signature)
    }
    val stale = VarkaKnownFailures.stale(VarkaKnownFailures.entries,
      (e: VarkaKnownFailures.Entry) => replay(e).toJava).asScala
    assert(stale.isEmpty, "these known fuzz failures no longer reproduce; delete them from " +
      s"${VarkaKnownFailures.PATH}: ${stale.mkString(", ")}")
  }

  /** The record node types of the sealed IR, by simple name. */
  private def recordNodeTypes: Set[Class[_]] = {
    def walk(c: Class[_]): Set[Class[_]] = {
      val subs = Option(c.getPermittedSubclasses).map(_.toSet).getOrElse(Set.empty[Class[_]])
      if (subs.isEmpty) Set(c) else subs.flatMap(walk)
    }
    walk(classOf[VarkaVectorIR]).filter(_.isRecord)
  }

  /** Every node type reachable from `node`, by simple name, added to `seen`. */
  private def collectNodeTypes(node: AnyRef, seen: scala.collection.mutable.Set[String]): Unit = {
    seen += node.getClass.getSimpleName
    node.getClass.getRecordComponents.foreach { rc =>
      val v = rc.getAccessor.invoke(node)
      if (v != null && classOf[VarkaVectorIR].isInstance(v)) {
        collectNodeTypes(v.asInstanceOf[AnyRef], seen)
      }
    }
  }

  /**
   * Whether a node type admits 64-bit lanes, asked of the type itself: its canonical
   * constructor is called once over long leaves, and a type that decomposes epoch days refuses
   * there (`requireInt`), while a lane-generic one constructs. Read off the constructors rather
   * than written as a list, so a node type added to the IR is classified by what it does and
   * the long-lane reach test below sees it without anyone editing this file.
   */
  private def admitsLongLanes(cls: Class[_]): Boolean = {
    val longCol = new ColumnRef(0, LaneType.LONG)
    val longCond = new Compare(CompareOp.LT, longCol, longCol)
    val components = cls.getRecordComponents
    val args: Array[AnyRef] = components.map { rc =>
      val t = rc.getType
      if (t == classOf[Cond]) longCond
      else if (classOf[VarkaVectorIR].isAssignableFrom(t)) longCol
      else if (t == java.lang.Long.TYPE) java.lang.Long.valueOf(1L)
      else if (t == java.lang.Integer.TYPE) Integer.valueOf(1)
      else if (t == java.lang.Boolean.TYPE) java.lang.Boolean.FALSE
      else if (t.isEnum) t.getEnumConstants.head.asInstanceOf[AnyRef]
      else if (t == classOf[java.util.List[_]]) {
        java.util.List.of(Integer.valueOf(1), Integer.valueOf(2))
      }
      else fail(s"${cls.getSimpleName}.${rc.getName} has a component type this probe cannot " +
        s"build: ${t.getName}")
    }
    try {
      cls.getDeclaredConstructor(components.map(_.getType): _*).newInstance(args: _*)
      true
    } catch {
      case e: java.lang.reflect.InvocationTargetException
          if e.getCause.isInstanceOf[IllegalArgumentException] => false
    }
  }

  test("the generator reaches every IR node type") {
    // A green fuzz run says nothing about a node type the generator cannot build: the shapes
    // that would have exercised it are simply never drawn, and the suite reports success for
    // the ones it did draw. That is not hypothetical - `TruncDateDynamic` was outside this
    // generator from VARKA-61 until this test was written, and the gap was found by reading the
    // arms rather than by anything failing.
    //
    // So the reachable set is asserted rather than assumed, against the sealed hierarchy itself
    // so a node type added to the IR fails here until the generator can build it. Generation
    // only: no bytes are emitted and nothing runs, so this is cheap enough to draw far more
    // shapes than the differential test does.
    val permitted = recordNodeTypes.map(_.getSimpleName)
    val seen = scala.collection.mutable.Set.empty[String]
    //
    // The shapes are the ones the fuzzer runs and the emitted-bytes oracle pins: draw k of the
    // shared corpus, every root of it.
    for (k <- 0 until 20000) {
      VarkaIrGrammar.drawShape(VarkaIrGrammar.shapeRandom(seed, k)).roots.asScala
        .foreach(collectNodeTypes(_, seen))
    }
    // Deliberately out of this generator's reach: the conversions, which take or give a long
    // lane, while this grammar builds int trees only. The long-lane generator draws a narrowing
    // at a root, and the mixed-lane generator both conversions anywhere; the tests below assert
    // that.
    //
    // And the null-safe equality (VARKA-336): a draw of it moves every seeded shape after it,
    // the cost corpus the model is fitted on, the warm-up reach and `emitted_bytes.json`, and
    // the model's refit needs the owner's word. The node is covered by its own suite
    // (`VarkaEmitterNullSafeSuite`, both lanes, both bodies, three widths) and by the coverage
    // rows' composition fuzzer until a grammar production is taken with the next refit
    // (`VARKA-336.md` 6 and 11).
    val missing = permitted -- seen - "NarrowLane" - "WidenLane" - "NullSafeEqual"
    assert(missing.isEmpty,
      s"the generator never built: ${missing.toSeq.sorted.mkString(", ")} - add an arm, or " +
        "state here why the node type is deliberately out of the fuzzer's reach")
  }

  test("the long-lane generator reaches every node type that admits 64-bit lanes") {
    // The same assertion for the second corpus, against the set the IR itself defines: a node
    // type is in the long lane's reach exactly when its constructor accepts long leaves. So a
    // lane-generic node added to the IR fails here until `LongShapes` builds it, and a
    // calendar node - which refuses a 64-bit child where it is built - is not asked for.
    val (laneGeneric, intOnly) = recordNodeTypes.partition(admitsLongLanes)
    // The split has to be the one the emitter's javadoc describes, or the probe is not asking
    // the constructors what it thinks it is: every calendar node refuses, and the leaves and
    // the arithmetic accept.
    assert(intOnly.map(_.getSimpleName).contains("Year"))
    assert(laneGeneric.map(_.getSimpleName).contains("ConstDivide"))
    val seen = scala.collection.mutable.Set.empty[String]
    // Over the long corpus the fuzzer runs and the oracle pins, every root of each draw. Its
    // roots come through `LongShapes.root`, so the narrowing an output root may carry is asked
    // for with the rest: it constructs over long leaves, so it counts as lane-generic.
    for (k <- 0 until 20000) {
      VarkaIrGrammar.drawLongShape(VarkaIrGrammar.shapeRandom(longSeed, k)).roots.asScala
        .foreach(collectNodeTypes(_, seen))
    }
    // The null-safe equality is out of this corpus for the reason the int one gives above.
    val missing = laneGeneric.map(_.getSimpleName) -- seen - "NullSafeEqual"
    assert(missing.isEmpty,
      s"the long-lane generator never built: ${missing.toSeq.sorted.mkString(", ")} - add an " +
        "arm to LongShapes, or state here why the node type is deliberately out of its reach")
  }

  test("the int-lane draw spreads its column values over their range") {
    // A draw that piles its values on one point checks little else. A signed remainder is
    // negative half the time: with a clamp to the bound it put half of every int-lane column at
    // exactly -bound, and without one half the values land below -bound. Over the first 2000
    // draws, each column is read by the role its case gives it, and checked against the
    // grammar's constants rather than against a bound chosen the way the draw chooses it, so a
    // wrong bound handed to the draw fails here too. A day column's values lie within
    // COLUMN_BOUND and reach past half of it, fifty times the month bound; the month
    // column's lie within MONTH_ARITH_MAX_MONTHS. In both, no one value holds more than 1% of
    // them and each half of the range holds over 40%. The trunc-level column is left out, since
    // it draws one of five codes on purpose.
    val monthBound = VarkaChrono.MONTH_ARITH_MAX_MONTHS.toLong
    val days = scala.collection.mutable.ArrayBuilder.make[Long]
    val months = scala.collection.mutable.ArrayBuilder.make[Long]
    for (k <- 0 until 2000) {
      val c = drawInt(k)
      for (col <- 0 until c.numInputs() if col != c.levelOrdinal()) {
        (if (col == c.smallOrdinal()) months else days) ++= c.data()(col)
      }
    }
    def spread(name: String, values: Array[Long], bound: Long): Unit = {
      val n = values.length.toDouble
      assert(values.nonEmpty, s"$name: no values drawn")
      assert(values.forall(v => -bound <= v && v <= bound), s"$name: a value outside +-$bound")
      // The most frequent value, by a scan of the sorted values.
      val sorted = values.sorted
      var (top, count, run) = (sorted(0), 1, 1)
      for (i <- 1 until sorted.length) {
        run = if (sorted(i) == sorted(i - 1)) run + 1 else 1
        if (run > count) {
          top = sorted(i)
          count = run
        }
      }
      assert(count / n <= 0.01, s"$name: $count of ${values.length} values are $top")
      val (negative, positive) = (values.count(_ < 0), values.count(_ > 0))
      assert(negative / n > 0.4 && positive / n > 0.4,
        s"$name: $negative negative and $positive positive of ${values.length}")
    }
    val dayValues = days.result()
    spread("day columns", dayValues, VarkaIrGrammar.COLUMN_BOUND)
    spread("month column", months.result(), monthBound)
    val widest = dayValues.iterator.map(math.abs).max
    assert(widest > VarkaIrGrammar.COLUMN_BOUND / 2,
      s"day columns: the widest value is $widest, drawn as if within a narrower bound")
  }

  // ---------------------------------------------------------------------------------------------
  // Wide compositions: past the ceilings the one-to-three-root draw never reaches (VARKA-238).
  // ---------------------------------------------------------------------------------------------

  private val wideIterations = sys.props.get("varka.fuzz.wide").map(_.toInt).getOrElse(10)

  /**
   * The options the wide compositions cycle through, each aimed at mechanisms that only a kernel
   * past the driver's ceiling of about 180 groups reaches: the stages, the grouping switches
   * dropped before a decline, the call-site splits and their rollback, the regroup under a small
   * budget, and the declines themselves.
   */
  private val wideVariants: Seq[(String, VarkaEmitOptions)] = {
    // The variants are the size loop's, with the plan and the prediction off (VARKA-236): under
    // both the first build is the last and no mechanism is reached. The defaults run beside
    // them, planned, so every composition is also checked as production emits it.
    val d = VarkaMatrix.base.withPlanSize(false).withPredictGrouping(false)
    Seq(
      "the defaults, planned" -> VarkaMatrix.base,
      "split driver" -> d.withSplitDriver(true),
      "whole driver" -> d.withSplitDriver(false),
      "whole driver, predicted grouping" -> d.withSplitDriver(false).withPredictGrouping(true),
      "whole driver, call sites split" ->
        d.withSplitDriver(false).withCallSiteBudget(8).withHeavyGroupOutputs(2),
      "split driver, 2000-byte budget" -> d.withSplitDriver(true).withMethodByteBudget(2000))
  }

  private var wideDeclines = 0
  private var wideChecked = 0

  /** `drawWideShape`'s draws, until they hold at least 250 value roots or there are twelve. */
  private def wideDraws(rnd: Random): Seq[Drawn] = {
    val draws = scala.collection.mutable.ArrayBuffer.empty[Drawn]
    while (draws.map(_.roots.size).sum < 250 && draws.size < 12) {
      draws += VarkaIrGrammar.drawWideShape(rnd)
    }
    draws.toSeq
  }

  /**
   * One wide composition: `drawWideShape`'s value roots, drawn until there are at least 250, over
   * the draws' shared column layout. A column keeps the narrowest domain any draw gives it - trunc
   * levels, then month counts, then days - since each is inside the next. The kernel it emits is
   * checked row by row like a drawn shape's; a decline must name a size.
   */
  private def runWide(iteration: Int, trace: VarkaEmitTrace): Unit = {
    val rnd = VarkaIrGrammar.shapeRandom(seed ^ 0x57494445L, iteration)
    val (label, options) = wideVariants(iteration % wideVariants.size)
    val draws = wideDraws(rnd)
    val roots = draws.flatMap(_.roots.asScala).distinct.toSeq
    val numInputs = draws.map(_.numInputs).max
    val numLiterals = draws.map(_.numLiterals).max
    val levels = draws.map(_.levelOrdinal).filter(_ >= 0).toSet
    val small = draws.map(_.smallOrdinal).filter(_ >= 0).toSet
    val lits = Array.fill(numLiterals)(rnd.nextInt(2 * literalBound + 1) - literalBound)
    val length = lengths(rnd.nextInt(lengths.length))
    val patternIds = Seq.fill(numInputs)(rnd.nextInt(patternNames.length))
    val patterns = patternIds.map(pattern(rnd, _, length))
    val forceMasked = length > 1 && rnd.nextInt(4) == 0
    val data = Array.tabulate(numInputs, length) { (c, _) =>
      if (levels(c)) {
        DateTimeUtils.TRUNC_TO_WEEK + rnd.nextInt(
          DateTimeUtils.TRUNC_TO_YEAR - DateTimeUtils.TRUNC_TO_WEEK + 1)
      } else {
        VarkaIrGrammar.intWithin(rnd,
          if (small(c)) VarkaChrono.MONTH_ARITH_MAX_MONTHS else intColumnBound)
      }
    }
    val context = s"wide seed=$seed iteration=$iteration ($label) roots=${roots.size} " +
      s"inputs=$numInputs length=$length patterns=${patternIds.map(patternNames).mkString(",")} " +
      s"literals=${lits.mkString(",")} forceMasked=$forceMasked"
    val className =
      s"org.apache.spark.sql.varka.execution.VarkaFusedFuzzWide${classCounter.addAndGet(1)}"
    def emitWith(o: VarkaEmitOptions, into: VarkaEmitTrace): Option[Array[Byte]] =
      try {
        Some(VarkaLoopEmitter.emitTraced(className, roots.asJava, numInputs, numLiterals, o,
          into))
      } catch {
        case d: VarkaEmitDeclined =>
          assert(d.getMessage.contains("bytes"), s"$context: a decline names no size: " +
            d.getMessage)
          None
      }
    // A variant that declines is the answer it gives, counted; the composition's answers are
    // then checked under the defaults, where the split driver serves it, on a trace of its own
    // so the retry does not count as the variant reaching a mechanism.
    val bytes = emitWith(options, trace).orElse {
      wideDeclines += 1
      emitWith(VarkaMatrix.base, new VarkaEmitTrace)
    }
    bytes.foreach { b =>
      VarkaKernelCheck.runAndCompare(context, className, b, roots, numInputs, lits,
        VarkaKernelCheck.Batch(length, patterns, data, forceMasked))
      wideChecked += 1
    }
  }

  test(s"wide compositions past the driver's ceiling match the reference evaluator, and reach " +
      s"every size mechanism (seed $seed, $wideIterations compositions)") {
    val trace = new VarkaEmitTrace
    val reach = VarkaEmitReach.attach(trace, "wide compositions")
    wideDeclines = 0
    wideChecked = 0
    (0 until wideIterations).foreach(runWide(_, trace))
    // Under the defaults nothing of this width declines: the split driver serves every one.
    assert(wideChecked === wideIterations,
      s"$wideChecked of $wideIterations compositions ran against the reference evaluator")
    // The size loop's reactions the wide variants aim at, by the emissions that reached each
    // (`VarkaEmitTrace.counters` pairs each counter with its mechanism), and the declines.
    import VarkaEmitMechanism._
    def reached: Seq[(String, Int)] = Seq(BYTE_REGROUP, CALL_SITE_SPLIT, CALL_SITE_ROLLBACK,
        STAGE_SPLIT, EXACT_GROUPING_DROPPED, PREDICTION_DROPPED)
      .map(m => m.sentence -> reach.reachedBy(m)) :+ ("a decline" -> wideDeclines)
    def missed: Seq[String] = reached.collect { case (mechanism, 0) => mechanism }
    // Every variant runs at least once only from a full cycle up. Which mechanisms a width
    // reaches is the defaults' structure, so the option matrix checks only the answers. A
    // mechanism the drawn compositions missed is drawn for: further compositions, cycling the
    // variants and each checked row by row, up to six cycles of them (VARKA-289), so the run
    // fails only where a mechanism has become unreachable, not where ten draws were unlucky.
    if (wideIterations >= wideVariants.size && VarkaMatrix.config.isEmpty) {
      val cap = wideIterations + 6 * wideVariants.size
      var k = wideIterations
      while (missed.nonEmpty && k < cap) {
        runWide(k, trace)
        k += 1
      }
      info(s"${k - wideIterations} further compositions drawn for a mechanism the first " +
        s"$wideIterations missed")
      reached.foreach { case (mechanism, n) => info(s"$mechanism: $n") }
      VarkaEmitReach.detach(trace)
      reportReach(reach)
      assert(missed.isEmpty, s"no wide composition reached, in $k: ${missed.mkString(", ")}")
    } else {
      reached.foreach { case (mechanism, n) => info(s"$mechanism: $n") }
      VarkaEmitReach.detach(trace)
      reportReach(reach)
    }
  }

  /**
   * What the size control decided for every emission of a fixed corpus, one line each, for a
   * refactor of the size loop to diff against its base (VARKA-290), like the coverage suite's
   * fusion dump: set `VARKA_SIZE_TRACE_DUMP` to a file and run this test on both sides, under
   * the defaults. After a first line naming the seeds and the options drawn from, a line
   * holds the emission's outcome - the class's SHA-256, or the decline's reason, outputs and
   * planned cut - every `VarkaEmitTrace` counter, and the plan's corrections, so a changed
   * decision shows even where it builds the same bytes. The corpus is the first 1500 drawn shapes
   * of each lane under their drawn options, and 24 wide compositions under the wide variants and
   * planned variants that reach the plan's declines, stages and corrections.
   */
  test("the size control's decisions over a fixed corpus, dumped for a refactor to diff " +
      "(opt-in: VARKA_SIZE_TRACE_DUMP=<file>; VARKA-290)") {
    val target = sys.env.get("VARKA_SIZE_TRACE_DUMP")
    assume(target.isDefined, "opt-in: set VARKA_SIZE_TRACE_DUMP to a file")
    // Under a matrix config the variants named planned may not be: the dump is of the defaults.
    assume(VarkaMatrix.config.isEmpty, "the dump runs under the defaults only")
    val className = "org.apache.spark.sql.varka.execution.VarkaFusedSizeTraceDump"
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    def line(label: String, roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int,
        o: VarkaEmitOptions): String = {
      val trace = new VarkaEmitTrace
      val outcome = try {
        val bytes = VarkaLoopEmitter.emitTraced(className, roots.asJava, numInputs,
          numLiterals, o, trace)
        "class " + sha.digest(bytes).map("%02x".format(_)).mkString
      } catch {
        case d: VarkaEmitDeclined =>
          s"declined ${d.getMessage} outputs=${d.outputs} cut=${d.plannedCut}"
      }
      s"$label | $outcome | builds=${trace.builds} plannedDeclines=${trace.plannedDeclines} " +
        s"plannedStages=${trace.plannedStages} ${trace.reactions.asScala.mkString(" ")} " +
        s"corrections=${trace.corrections.asScala.mkString("; ")}"
    }
    // The seeds and the options the corpus is drawn under, so two dumps of different runs
    // differ on their first line rather than on every line.
    val lines = scala.collection.mutable.ArrayBuffer(
      s"seed=$seed longSeed=$longSeed base=" +
        (if (VarkaMatrix.base.isDefault) "(defaults)" else VarkaMatrix.base.canonical()))
    for (k <- 0 until 1500; c <- Seq(drawInt(k), drawLong(k))) {
      lines += line(s"${c.lane} $k ${c.options.canonical()}", c.roots.asScala.toSeq, c.numInputs,
        c.lits.length, c.options)
    }
    val d = VarkaMatrix.base
    val planned = Seq(
      "planned, whole driver" -> d.withSplitDriver(false),
      "planned, driver misread" -> d.withMisdescribeDriverBytes(4000),
      "planned, 2000-byte budget" -> d.withMethodByteBudget(2000),
      "planned, call sites split" -> d.withCallSiteBudget(8).withHeavyGroupOutputs(2),
      "planned, no exact grouping" -> d.withExactGrouping(false),
      "planned, no prediction" -> d.withPredictGrouping(false),
      "planned, whole driver misread" ->
        d.withSplitDriver(false).withMisdescribeDriverBytes(4000))
    for (k <- 0 until 24; (label, o) <- wideVariants ++ planned) {
      val draws = wideDraws(VarkaIrGrammar.shapeRandom(seed ^ 0x57494445L, k))
      lines += line(s"wide $k ($label)", draws.flatMap(_.roots.asScala).distinct.toSeq,
        draws.map(_.numInputs).max, draws.map(_.numLiterals).max, o)
    }
    java.nio.file.Files.write(java.nio.file.Paths.get(target.get), lines.asJava)
    info(s"${lines.size} emissions to ${target.get}")
  }

  test(s"random IR trees match the reference evaluator (seed $seed, $iterations iterations)") {
    val reach = VarkaEmitReach.attach(randomTrace, "int-lane draws")
    try {
      only match {
        case Some(k) => runOne(k)
        case None => (0 until iterations).foreach(runOne)
      }
    } finally {
      VarkaEmitReach.detach(randomTrace)
    }
    reportReach(reach)
    reportSkipped()
  }

  test(s"random long-lane IR trees match the reference evaluator (seed $longSeed, " +
      s"$iterations iterations)") {
    val reach = VarkaEmitReach.attach(randomTrace, "long-lane draws")
    try {
      only match {
        case Some(k) => runOneLong(k)
        case None => (0 until iterations).foreach(runOneLong)
      }
    } finally {
      VarkaEmitReach.detach(randomTrace)
    }
    reportReach(reach)
    reportSkipped()
  }

  test(s"random IR trees of both lanes match the reference evaluator (seed $mixedSeed, " +
      s"$iterations iterations)") {
    val reach = VarkaEmitReach.attach(randomTrace, "mixed-lane draws")
    try {
      only match {
        case Some(k) => runOneMixed(k)
        case None => (0 until iterations).foreach(runOneMixed)
      }
    } finally {
      VarkaEmitReach.detach(randomTrace)
    }
    reportReach(reach)
    reportSkipped()
  }

  test("the mixed-lane generator reaches both conversions, the calendar under a widening and a " +
      "narrowing below another node") {
    // The node types the third corpus exists for, asserted rather than assumed, as the two
    // single-lane generators' reach is above.
    val seen = scala.collection.mutable.Set.empty[String]
    var narrowBelow = false
    def walk(n: VarkaVectorIR, underWiden: Boolean): Unit = {
      seen += n.getClass.getSimpleName
      if (underWiden && VarkaEmitBudget.isChrono(n)) seen += "calendar under a widening"
      VarkaVectorIR.childrenOf(n).foreach { child =>
        if (child.isInstanceOf[NarrowLane]) narrowBelow = true
        walk(child, underWiden || n.isInstanceOf[WidenLane])
      }
    }
    for (k <- 0 until 20000) {
      VarkaIrGrammar.drawMixedShape(VarkaIrGrammar.shapeRandom(mixedSeed, k)).roots.asScala
        .foreach(walk(_, underWiden = false))
    }
    val wanted = Set("WidenLane", "NarrowLane", "GuardedRange", "calendar under a widening",
      "IfElse", "Compare", "ConstDivide", "IntArith", "Greatest", "Least")
    assert((wanted -- seen).isEmpty, s"never drawn: ${(wanted -- seen).toSeq.sorted}")
    assert(narrowBelow, "no narrowing below another node was drawn")
  }

  // ---------------------------------------------------------------------------------------------
  // What the emitter reached, and steering toward what it has not (VARKA-280).
  // ---------------------------------------------------------------------------------------------

  /** The steering A/B's emissions per arm: `-Dvarka.fuzz.steer`, the main tests' by default. */
  private val steerBudget = sys.props.get("varka.fuzz.steer").map(_.toInt).getOrElse(iterations)
  /** Whether the A/B runs: it is opt-in, since its verdict kept steering out (`VARKA-280.md` 9). */
  private val steerRequested =
    sys.props.contains("varka.fuzz.steer") || sys.props.contains("varka.fuzz.steerSeeds")

  /** The seeds the A/B runs at: `-Dvarka.fuzz.steerSeeds=a,b,...`, the suite's two by default. */
  private val steerSeeds: Seq[(Long, Long)] = sys.props.get("varka.fuzz.steerSeeds")
    .map(_.split(',').toSeq.map(_.trim.toLong).map(s => (s, s))).getOrElse(Seq((seed, longSeed)))

  /**
   * QPG's question asked of the emitter: does steering toward unseen mechanisms reach more of
   * them than the plain draws, for the same number of emissions? Emission only - the mechanisms
   * are compile-time facts - so the arms run in seconds and nothing is compared with the
   * reference evaluator here. The plain arm emits draws 0 to n-1 of each lane; each steered arm
   * spends about half its steps mutating cases it kept (`VarkaSteering`), one keeping a case that
   * reached a new set of mechanisms, the other one that reached a rare mechanism. Each arm prints
   * one `A/B` line for the tally in `VARKA-280.md` 9 and, per mechanism, the emission that first
   * reached it. Reported, not asserted: the verdict is the plan's, and the numbers move with the
   * grammar and the emitter. Opt-in, about 13 seconds at the defaults in a fresh JVM: the A/B kept
   * steering out of the fuzzer (`VARKA-280.md` 9), so no gate pays for it.
   */
  test(s"steering toward unseen mechanisms against the plain draws, emission only " +
      s"(opt-in: -Dvarka.fuzz.steer=<n> or -Dvarka.fuzz.steerSeeds=<a,b,...>; VARKA-280)") {
    assume(steerRequested, "opt-in: set -Dvarka.fuzz.steer or -Dvarka.fuzz.steerSeeds")
    for ((intSeed, laneLongSeed) <- steerSeeds) {
      val lanes: Seq[(String, Long, Int => VarkaFuzzCase)] = Seq(
        ("int", intSeed, k => drawInt(k, intSeed)),
        ("long", laneLongSeed, k => drawLong(k, laneLongSeed)))
      for ((lane, laneSeed, draw) <- lanes) {
        def arm(name: String)(run: (VarkaEmitTrace, VarkaEmitReach) => Unit): VarkaEmitReach = {
          val trace = new VarkaEmitTrace
          val reach = VarkaEmitReach.attach(trace, s"$lane $name")
          run(trace, reach)
          reach
        }
        val arms = Seq(
          arm("plain")((t, _) => VarkaSteering.unsteered(steerBudget, k => draw(k), t)),
          arm("steered by new set")((t, r) => VarkaSteering.steered(steerBudget, laneSeed,
            k => draw(k), t, r, VarkaSteering.Keep.NEW_SET)),
          arm("steered by rare mechanism")((t, r) => VarkaSteering.steered(steerBudget,
            laneSeed, k => draw(k), t, r, VarkaSteering.Keep.RARE_MECHANISM)))
        // The mechanisms every arm reached, whose first emissions compare arm against arm.
        val common = VarkaEmitMechanism.values.toSeq.filter(m => arms.forall(_.firstAt(m) > 0))
        for (r <- arms) {
          val firsts = common.map(r.firstAt).sorted
          val median = if (firsts.isEmpty) -1 else firsts(firsts.size / 2)
          val armName = r.name.stripPrefix(lane + " ").replace(' ', '-')
          info(s"A/B lane=$lane seed=$laneSeed arm=$armName emissions=${r.emissions} " +
            s"mechanisms=${VarkaEmitMechanism.values.length - r.neverReached.size} " +
            s"sets=${r.distinctSets} plans=${r.distinctPlans} medianFirst=$median " +
            s"never=${r.neverReached.asScala.mkString(",")}")
        }
        info(s"  first reached (" + arms.map(_.name).mkString(" / ") + "): " +
          VarkaEmitMechanism.values.toSeq.filter(m => arms.exists(_.firstAt(m) > 0)).map { m =>
            m.name + " " + arms.map(r => if (r.firstAt(m) < 0) "-" else r.firstAt(m).toString)
              .mkString("/")
          }.mkString(", "))
      }
    }
  }

  /** Prints what a test's emissions reached and keeps the report for the dump. */
  private def reportReach(reach: VarkaEmitReach): Unit = {
    reach.lines.asScala.foreach(info(_))
    reachReports += reach
  }

  /**
   * The reports of the tests that ran, written as markdown where `VARKA_REACH_DUMP` names a
   * directory: `VARKA_REACH_DUMP=$PWD/sql/varka/coverage/reach build/sbt 'catalyst/testOnly
   * *VarkaIrFuzzSuite'` regenerates the committed one, which row 265 reads. An absolute path,
   * since the test JVM's working directory is the module's.
   */
  override def afterAll(): Unit = {
    try {
      if (reachReports.nonEmpty) {
        val provenance = s"Generated by `VarkaIrFuzzSuite` with `VARKA_REACH_DUMP` set " +
          s"(VARKA-280): seed $seed (int lane) and $longSeed (long lane), $iterations " +
          s"iterations a lane, $wideIterations wide compositions and any drawn after them for " +
          "a size mechanism they missed, options drawn over " +
          (if (VarkaMatrix.base.isDefault) "the defaults" else VarkaMatrix.base.canonical()) +
          ". A draw's emissions include its variants' and a declined form's retry without the " +
          "budget. Do not edit; regenerate with `VARKA_REACH_DUMP=$PWD/sql/varka/coverage/reach " +
          "build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite'`."
        VarkaEmitReach.dump("VarkaIrFuzzSuite.md", "What the IR fuzzer's emissions reached",
          provenance, reachReports.asJava).ifPresent(p => logInfo(s"reach report written to $p"))
      }
    } finally {
      super.afterAll()
    }
  }

  /**
   * The shapes skipped as past the class-file cap, reported and bounded: the night that found
   * them saw one in three hundred thousand trees per lane, so a run that skips more than a few
   * per thousand has either a heavier grammar or an emitter that declines where it should
   * build, and neither may pass quietly.
   */
  private def reportSkipped(): Unit = {
    info(s"size control over the run: ${randomTrace.builds} builds, " +
      s"${randomTrace.byteRegroups} regroups on bytes, ${randomTrace.siteSplits} call-site " +
      s"splits, ${randomTrace.siteRollbacks} rolled back, ${randomTrace.stageSplits} stage " +
      s"splits, ${randomTrace.fallbacks()} grouping switches dropped")
    if (!variantCounts.isEmpty) {
      info("variants of passing draws (compared/declined/skipped): " +
        VarkaVariants.Kind.values.toSeq.map { k =>
          val n = Seq("compared", "declined", "skipped")
            .map(o => Option(variantCounts.get(s"${k.label} $o")).map(_.get).getOrElse(0))
          s"${k.label} ${n.mkString("/")}"
        }.mkString(", "))
      variantCounts.clear()
    }
    val pastTheOpCap = skippedPastTheOpCap.getAndSet(0)
    if (pastTheOpCap > 0) {
      info(s"$pastTheOpCap variant(s) grown past the cap of the emitter's form without a " +
        "budget, with no smaller form of the variant to take, and declined by that form: " +
        "skipped (VARKA-323)")
    }
    val skipped = skippedPastTheCap.getAndSet(0)
    if (skipped > 0) {
      info(s"$skipped shape(s) past the class-file cap on a method in every form the emitter " +
        "has: declined, and skipped (VARKA-219)")
    }
    assert(skipped <= 2 + iterations / 1000,
      s"$skipped shapes skipped as past the class-file cap in $iterations iterations")
  }
}
