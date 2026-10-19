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

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Random

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, AttributeReference, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection, PartialVarkaProjection, VarkaConjunctOrder, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.{FusedOutput, KernelOutput}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaCompositionCase.Entry
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType
import org.apache.spark.sql.catalyst.util.{DateTimeConstants, DateTimeUtils}
import org.apache.spark.sql.types.{DataType, DayTimeIntervalType, TimeType}

/**
 * Random compositions of the coverage table, through the compiler to the emitter.
 *
 * `VarkaIrFuzzSuite` draws IR directly and so never sees the compiler admit anything; the
 * coverage suite compiles every documented expression alone and requires it to fuse. Between
 * the two is the path a wide query takes: many admitted entries in one projection, or many
 * admitted conjuncts in one filter, which the compiler groups, budgets in bytes, regroups and
 * partly declines. That path produced the epilogue past 64KB (`VARKA-87.md`) and the
 * `CASE WHEN` that failed to emit (`VARKA-169.md`), and nothing drew it at random.
 *
 * Each iteration composes a projection of one to three hundred entries drawn from the table's
 * projection rows, or a filter of one to sixty-four of its predicate rows, resolves them against
 * the table's own columns, and asks the compiler what the planner asks. The property is the
 * milestone's (`m6/PLAN.md` 1.3): every entry is fused or declined with a reason, the
 * compiler throws nothing, and a decline of an entry the table says fuses alone is one of the
 * two the record knows, a size decline naming the budget or the one-lane rule. Emit options
 * alternate between the default width and four lanes, the two the emitted-bytes oracle pins,
 * and the exact grouping (`VARKA-200.md`) is on or off at random, since the wide projections
 * drawn here are where it changes the partition.
 *
 * Past the columns one kernel reads (VARKA-238): a third test spreads each of 150 to 300 rows over
 * eighty renamed copies of the table's columns, so the compiler serves the projection with
 * several kernels (`VarkaEmitOptions.severalKernels`), holds the same property, and runs every
 * kernel, at either lane, against the reference evaluator (`VarkaKernelCheck`). A batch a guard
 * declines, over columns drawn without all of their domains, is drawn again nearer zero, so every
 * kernel is compared and each lane that has kernels has comparisons (VARKA-289).
 * `-Dvarka.fuzz.wideCompositions` sets its count (default 20).
 *
 * Budget: `-Dvarka.fuzz.compositions` (default 40, under a minute); `-Dvarka.fuzz.seed` (default
 * fixed, shared with the IR fuzzer so a nightly varies both with one property). A failure names
 * the seed, the iteration and the rows, and `-Dvarka.fuzz.only=<iteration>` replays one.
 */
class VarkaCoverageCompositionFuzzSuite
    extends SparkFunSuite with VarkaMatrixTests with VarkaOwnJvm {

  private val seed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(20260925L)
  private val iterations = sys.props.get("varka.fuzz.compositions").map(_.toInt).getOrElse(40)
  private val only = sys.props.get("varka.fuzz.only").map(_.toInt)

  /** The coverage table's columns and rows, from the committed file the coverage suite keeps. */
  private lazy val table =
    VarkaCoverageRows.read(getWorkspaceFilePath("sql", "varka", "coverage.json"))
  private def columns = table.columns
  private def projections = table.projections
  private def predicates = table.predicates

  private def resolve(sql: String): Expression = VarkaCoverageRows.resolve(sql, columns)

  /** One to `max`, log-uniform, so most compositions are small and some are very wide. */
  private def width(rnd: Random, max: Int): Int =
    math.max(1, math.exp(rnd.nextDouble() * math.log(max)).toInt)

  /**
   * The emit options of an iteration. The exact grouping is drawn from a stream of its own, so
   * adding it left every composition the main stream draws, and the seeds that found past bugs,
   * as they were.
   */
  private def options(rnd: Random, seed: Long, iteration: Int): VarkaEmitOptions = {
    val lanes = if (rnd.nextBoolean()) VarkaMatrix.base
      else VarkaMatrix.base.withLanesOverride(4)
    lanes.withExactGrouping(new Random(~(seed * 1000003L + iteration)).nextBoolean())
  }

  /**
   * The reasons a composition may decline an entry that fuses alone: a size decline, whose
   * reason names a budget; the one-lane rule - a kernel holds one lane, so a projection
   * that mixes the int and the long lane fuses the first lane it meets and leaves the other,
   * which `VARKA-29.md` pins and VARKA-28's width conversion is to lift; and, for a conjunct,
   * the order rule of VARKA-380, which demotes one Spark evaluates after a residual conjunct
   * that can raise. Any other reason on an admitted row is a finding.
   */
  private def isCompositionDecline(reason: String): Boolean =
    reason.contains("budget") || reason.contains("one kernel holds one lane") ||
      reason.contains(VarkaConjunctOrder.OUT_OF_ORDER)

  /**
   * The finding of a failed check on a line of its own, and the case after it: the signature of a
   * failure (VARKA-294) is that first line with its numbers taken out, so the case, whose size and
   * options change as it shrinks, must not be on it.
   */
  private def finding(what: String, c: VarkaCompositionCase): String =
    s"$what\n  on ${c.label}"

  private def whereOf(seed: Long, iteration: Int, c: VarkaCompositionCase, noun: String,
      picked: Seq[String]): String =
    s"seed $seed iteration $iteration, ${picked.size} $noun, options " +
      s"${c.options.canonical}:\n  ${picked.mkString("\n  ")}"

  /** The case drawn for `iteration`, with its label naming the seed, the iteration and the rows. */
  private def drawProjection(seed: Long, iteration: Int): VarkaCompositionCase = {
    val rnd = new Random(seed * 1000003L + iteration)
    val picked = Seq.fill(width(rnd, 300))(projections(rnd.nextInt(projections.size)))
    val entries = picked.map(row => Entry(row.executable, resolve(row.executable))).toVector
    val opts = options(rnd, seed, iteration)
    val c = VarkaCompositionCase("projection", entries, opts, 0L, "")
    c.copy(label = whereOf(seed, iteration, c, "entries", picked.map(_.executable)))
  }

  private def checkProjection(c: VarkaCompositionCase): Unit = {
    val list: Seq[NamedExpression] = c.entries.zipWithIndex.map { case (e, i) =>
      Alias(e.expr, s"c$i")()
    }
    val (fused, declined) = try {
      // A further kernel's entry is fused too (VARKA-190's `severalKernels`, on by default).
      val fused = VarkaExpressionCompiler.compilePartial(list, columns, c.options).toScala
        .map(_.specs.asScala.toSeq.zipWithIndex.collect {
          case (_: FusedOutput, i) => i
          case (_: KernelOutput, i) => i
        }.toSet)
        .getOrElse(Set.empty[Int])
      (fused, VarkaExpressionCompiler.declines(list, columns, c.options).asScala
        .map { case (k, v) => k.intValue -> v }.toMap)
    } catch {
      case e: Exception => fail(finding(s"the compiler threw ${threw(e)}", c), e)
    }
    assert(fused.size + declined.size == list.size && (fused & declined.keySet).isEmpty,
      finding("entries neither fused nor declined, or both", c))
    declined.foreach { case (i, d) =>
      assert(d.reason.nonEmpty, finding(s"entry $i declined without a reason", c))
      assert(isCompositionDecline(d.reason),
        finding(s"entry $i, which fuses alone, declined for '${d.reason}'", c))
    }
  }

  /** The exception's class and first line, for the first line of a finding. */
  private def threw(e: Throwable): String =
    s"${e.getClass.getSimpleName}: ${Option(e.getMessage).flatMap(_.linesIterator.nextOption())
      .getOrElse("")}"

  private def drawPredicate(seed: Long, iteration: Int): VarkaCompositionCase = {
    val rnd = new Random(seed * 1000003L + 500000L + iteration)
    val picked = Seq.fill(width(rnd, 64))(predicates(rnd.nextInt(predicates.size)))
    val entries = picked.map(row => Entry(row.executable, resolve(row.executable))).toVector
    val opts = options(rnd, seed, iteration)
    val c = VarkaCompositionCase("predicate", entries, opts, 0L, "")
    c.copy(label = whereOf(seed, iteration, c, "conjuncts", picked.map(_.executable)))
  }

  private def checkPredicate(c: VarkaCompositionCase): Unit = {
    val condition = c.entries.map(_.expr).reduceLeft(And)
    val specs = try {
      VarkaExpressionCompiler.explainPredicate(condition, columns, c.options).asScala.toSeq
    } catch {
      case e: Exception => fail(finding(s"the compiler threw ${threw(e)}", c), e)
    }
    // A row of the table may itself be a conjunction, which the compiler splits, so the specs
    // are at least as many as the rows picked.
    assert(specs.size >= c.entries.size, finding(s"${specs.size} conjunct specs", c))
    specs.zipWithIndex.foreach { case (spec, i) =>
      assert(spec.fused || spec.decline.toScala.exists(_.reason.nonEmpty),
        finding(s"conjunct $i neither fused nor declined with a reason", c))
      spec.decline.toScala.filterNot(_ => spec.fused).foreach { d =>
        assert(isCompositionDecline(d.reason),
          finding(s"conjunct $i, which fuses alone, declined for '${d.reason}'", c))
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Past the columns one kernel reads (VARKA-238).
  // ---------------------------------------------------------------------------------------------

  /**
   * The table's columns eighty times over, renamed. Most rows read the one date column, so a
   * projection reaches past the 64 columns a kernel reads only when that column alone has more
   * copies than a kernel holds.
   */
  private lazy val copies: Seq[Seq[Attribute]] = (0 until 80).map { k =>
    columns.map(a => AttributeReference(s"${a.name}_$k", a.dataType, a.nullable)())
  }

  /** `e`, resolved against the table's columns, moved onto copy `k`. */
  private def onCopy(e: Expression, k: Int): Expression = {
    val byId = columns.map(_.exprId).zip(copies(k)).toMap
    e.transform { case a: AttributeReference if byId.contains(a.exprId) => byId(a.exprId) }
  }

  private var kernelCounter = 0

  private val wideIterations =
    sys.props.get("varka.fuzz.wideCompositions").map(_.toInt).getOrElse(20)

  /**
   * The value of a long-lane input of `dataType` at `scale`, the attempt's step towards zero: a
   * `TIME` inside the day in nanoseconds, the domain every long-lane guard assumes, and in whole
   * units of its precision, the only values a `TIME(p)` column holds (VARKA-276: drawn to the
   * nanosecond, a `TIME(6)` value was one Spark truncates on its first arithmetic); a day-time
   * interval and a `BIGINT` within 2^45 and 2^40 of zero, then nearer at each step.
   */
  private def drawLong(rnd: Random, dataType: DataType, scale: Int): Long = {
    def timeUnit(t: TimeType): Long = math.pow(10, 9 - t.precision).toLong
    (dataType, scale) match {
      case (t: TimeType, 2) => (1 + rnd.nextInt(3)) * timeUnit(t)
      case (_, 2) => 1 + rnd.nextInt(3)
      case (t: TimeType, 0) =>
        Math.floorMod(rnd.nextLong(), DateTimeConstants.NANOS_PER_DAY / timeUnit(t)) * timeUnit(t)
      case (t: TimeType, _) => Math.floorMod(rnd.nextLong(), 1000000L / timeUnit(t)) * timeUnit(t)
      case (_: DayTimeIntervalType, 0) => VarkaIrGrammar.within(rnd.self, 1L << 45)
      case (_, 0) => VarkaIrGrammar.within(rnd.self, 1L << 40)
      case _ => VarkaIrGrammar.within(rnd.self, 30000)
    }
  }

  /** Kernels compared row by row and batches drawn again after a decline, per lane. */
  private val comparedByLane = scala.collection.mutable.Map.empty[LaneType, Int]
  private val kernelsByLane = scala.collection.mutable.Map.empty[LaneType, Int]
  private var redrawn = 0

  /**
   * Runs one compiled kernel, at its lane, against the reference evaluator over `inputs`, the
   * attributes its input ordinals index. Each input is drawn from its own domain: a derived input
   * from its kind's codes, a bounded input across its bound, an int-lane input within thirty
   * thousand either side of zero, a long-lane one by its type (`drawLong`). A batch a guard
   * declines is drawn again twice, nearer zero each time and a bounded input clamped into its
   * bound, the last within one to three, which no guard declines; each attempt keeps the length
   * and null patterns. The test then asserts that
   * every kernel was compared. Returns whether the kernel was.
   *
   * With `poisoned`, the batch's shape is not drawn but fixed (VARKA-138), `poisonedPatterns`,
   * and it runs through the masked body alone, at both fills: its other body would be the same
   * values with no nulls, which meet no poisoned lane and which the drawn batches cover.
   * `kernelBytes` replaces the emitted kernel, for a test that plants a wrong one.
   */
  private def checkKernel(plan: CompiledVarkaProjection, inputs: Seq[Attribute],
      opts: VarkaEmitOptions, rnd: Random, spark: Option[VarkaSparkOracle] = None,
      context: String = "kernel", poisoned: Boolean = false,
      kernelBytes: Option[Array[Byte]] = None,
      patternsOf: Int => Seq[Int => Boolean] = poisonedPatterns): Boolean = {
    val numInputs = plan.inputOrdinals.size
    kernelCounter += 1
    kernelsByLane(plan.lane) = kernelsByLane.getOrElse(plan.lane, 0) + 1
    val className = s"org.apache.spark.sql.varka.execution.VarkaCompositionWide$kernelCounter"
    val bytes = kernelBytes.getOrElse(VarkaLoopEmitter.emit(className, plan.outputs, numInputs,
      plan.numLiterals, null, null, opts))
    val length = if (poisoned) PoisonedLength else Seq(1, 7, 64, 100, 257, 1000)(rnd.nextInt(6))
    val patterns: Seq[Int => Boolean] = if (poisoned) {
      patternsOf(numInputs)
    } else {
      Seq.fill(numInputs) {
        rnd.nextInt(3) match {
          case 0 => (_: Int) => false
          case 1 => (i: Int) => i % 5 == 0
          case _ =>
            val bits = Array.fill(length)(rnd.nextInt(3) == 0)
            (i: Int) => bits(i)
        }
      }
    }
    val forceMasked = !poisoned && length > 1 && rnd.nextBoolean()
    def intValue(i: Int, scale: Int): Int = intInput(plan, i, scale, rnd)
    val roots = plan.outputs.asScala.toSeq
    def attempt(scale: Int): Boolean = if (plan.lane == LaneType.LONG) {
      val data = Array.tabulate(numInputs) { i =>
        val dataType = inputs(plan.inputOrdinals.get(i)).dataType
        Array.fill(length)(drawLong(rnd, dataType, scale))
      }
      val lits = plan.longLiterals.asScala.map(_.longValue).toArray
      // A kernel of both lanes (VARKA-28) reads the int table beside the long one.
      val intLits = plan.literals.asScala.map(_.intValue).toArray
      val batch = VarkaKernelCheck.LongBatch(length, patterns, data, forceMasked)
      if (poisoned) {
        VarkaKernelCheck.runOwnBodyLong(context, className, bytes, roots, numInputs, lits, batch,
          declineAllowed = true, spark = spark, intLits = intLits)
      } else {
        VarkaKernelCheck.runAndCompareLong(context, className, bytes, roots, numInputs, lits,
          batch, declineAllowed = true, spark = spark, intLits = intLits)
      }
    } else {
      val data = Array.tabulate(numInputs)(i => Array.fill(length)(intValue(i, scale)))
      val lits = plan.literals.asScala.map(_.intValue).toArray
      val batch = VarkaKernelCheck.Batch(length, patterns, data, forceMasked)
      if (poisoned) {
        VarkaKernelCheck.runOwnBody(context, className, bytes, roots, numInputs, lits, batch,
          declineAllowed = true, spark = spark)
      } else {
        VarkaKernelCheck.runAndCompare(context, className, bytes, roots, numInputs, lits, batch,
          declineAllowed = true, spark = spark)
      }
    }
    val compared = (0 until 3).exists { scale =>
      if (scale > 0) redrawn += 1
      attempt(scale)
    }
    if (compared) comparedByLane(plan.lane) = comparedByLane.getOrElse(plan.lane, 0) + 1
    compared
  }

  /**
   * The value of int-lane input `i` of `plan` at `scale`, the attempt's step towards zero: a
   * derived input from its kind's codes, a bounded input across its bound, any other within thirty
   * thousand either side of zero, then nearer at each step.
   */
  private def intInput(plan: CompiledVarkaProjection, i: Int, scale: Int, rnd: Random): Int =
    plan.derivedAt(i).toScala.map(_.kind) match {
      case Some(VarkaDerivedKind.TRUNC_LEVEL) =>
        DateTimeUtils.TRUNC_TO_WEEK +
          rnd.nextInt(DateTimeUtils.TRUNC_TO_YEAR - DateTimeUtils.TRUNC_TO_WEEK + 1)
      case Some(_) => rnd.nextInt(7)
      case None =>
        def unbounded: Int = scale match {
          case 0 => rnd.nextInt(60001) - 30000
          case 1 => rnd.nextInt(201) - 100
          case _ => 1 + rnd.nextInt(3)
        }
        plan.inputBounds.asScala.find(_.inputIndex == i) match {
          case Some(b) if scale == 0 =>
            (b.lo + (rnd.nextLong() & Long.MaxValue) % (b.hi.toLong - b.lo + 1)).toInt
          // Nearer zero as the unbounded draw, inside the bound: a value across the whole of
          // it, added to a date, passes the date guard at every attempt.
          case Some(b) => unbounded.max(b.lo).min(b.hi)
          case None => unbounded
        }
    }

  /** The fixed poisoned batch's rows (VARKA-138): see `poisonedPatterns`. */
  private val PoisonedLength = 257

  /**
   * The fixed poisoned batch's null patterns (VARKA-138): 257 rows, input `c` null where
   * `i % 3 == (c + 1) % 3`, and every input null where `i % 9 == 0`. Each input has its own
   * phase, so a poisoned lane sits beside a valid one in another input - the case a null-skipping
   * `greatest` or `coalesce` must not take the poison from - and inputs null together carry
   * different poisons; the rows divisible by nine are null in every input. 257 rows are whole
   * lane groups at every width plus one row for the epilogue: input 0 is null on row 1, in the
   * first lane group, and on row 256, the epilogue's one row.
   */
  private def poisonedPatterns(numInputs: Int): Seq[Int => Boolean] =
    (0 until numInputs).map(c => (i: Int) => i % 3 == (c + 1) % 3 || i % 9 == 0)

  /** The shape the poisoned batch had first: every input null together, on rows 1, 4, ... */
  private def samePhasePatterns(numInputs: Int): Seq[Int => Boolean] =
    Seq.fill(numInputs)((i: Int) => i % 3 == 1)

  /** Rows of the poisoned batch where a null input sits beside a valid one. */
  private def mixedRows(numInputs: Int): Int = {
    val patterns = poisonedPatterns(numInputs)
    (0 until PoisonedLength).count { i =>
      val nulls = patterns.count(_(i))
      nulls > 0 && nulls < numInputs
    }
  }

  /**
   * The expression behind each output of kernel `k` of `p`: the entry whose spec names that
   * kernel and output. Kernel 0's outputs are [[FusedOutput]]s, every later kernel's
   * [[KernelOutput]]s.
   */
  private def outputsOf(p: PartialVarkaProjection, k: Int,
      c: VarkaCompositionCase): Seq[Expression] = {
    val exprs = new Array[Expression](p.kernels.get(k).outputs.size)
    p.specs.asScala.toSeq.zipWithIndex.foreach {
      case (f: FusedOutput, i) if k == 0 => exprs(f.fusedIndex) = c.entries(i).expr
      case (ko: KernelOutput, i) if ko.kernel == k => exprs(ko.fusedIndex) = c.entries(i).expr
      case _ =>
    }
    assert(!exprs.contains(null), finding(s"kernel $k has an output no entry names", c))
    exprs.toSeq
  }

  /**
   * Spark's answers for a kernel's outputs (VARKA-276), over the attributes its inputs read; none
   * for a kernel that derives an input, whose source column Spark would read in place of the code
   * the kernel is handed. The coverage table has no column a derivation reads.
   */
  private def sparkOracle(plan: CompiledVarkaProjection, columns: Seq[Attribute],
      outputs: Seq[Expression]): Option[VarkaSparkOracle] =
    if (plan.derivedInputs.asScala.nonEmpty) None
    else Some(new VarkaSparkOracle(outputs, plan.inputOrdinals.asScala.toSeq.map(columns(_))))

  private def drawWide(seed: Long, iteration: Int): VarkaCompositionCase = {
    val rnd = new Random(seed * 1000003L + 900000L + iteration)
    val picked = Seq.fill(150 + rnd.nextInt(151))(projections(rnd.nextInt(projections.size)))
    val entries = picked.map { row =>
      Entry(row.executable, onCopy(resolve(row.executable), rnd.nextInt(copies.size)))
    }.toVector
    val opts = options(rnd, seed, iteration)
    // The batches the kernels are checked on come from a stream of their own, so a shrunk case
    // is checked on the batches the original was.
    val c = VarkaCompositionCase("wide", entries, opts, rnd.nextLong(), "")
    c.copy(label = whereOf(seed, iteration, c, "entries", picked.map(_.executable))
      .replace(", options", " (wide), options"))
  }

  /** Whether the projection reached several kernels, and how many columns its first read. */
  private def checkWide(c: VarkaCompositionCase): (Boolean, Int) = {
    val list: Seq[NamedExpression] = c.entries.zipWithIndex.map { case (e, i) =>
      Alias(e.expr, s"c$i")()
    }
    val wide = copies.flatten
    val rnd = new Random(c.checkSeed)
    val partial = try {
      VarkaExpressionCompiler.compilePartial(list, wide, c.options).toScala
    } catch {
      case e: Exception => fail(finding(s"the compiler threw ${threw(e)}", c), e)
    }
    val declined = VarkaExpressionCompiler.declines(list, wide, c.options).asScala
      .map { case (k, v) => k.intValue -> v }.toMap
    val fused = partial.toSeq.flatMap(_.specs.asScala.toSeq.zipWithIndex.collect {
      case (_: FusedOutput, i) => i
      case (_: KernelOutput, i) => i
    }).toSet
    assert(fused.size + declined.size == list.size && (fused & declined.keySet).isEmpty,
      finding("entries neither fused nor declined, or both", c))
    declined.foreach { case (i, d) =>
      assert(isCompositionDecline(d.reason),
        finding(s"entry $i, which fuses alone, declined for '${d.reason}'", c))
    }
    partial.foreach { p =>
      p.kernels.asScala.toSeq.zipWithIndex.foreach { case (kernel, k) =>
        checkKernel(kernel, wide, c.options, rnd, sparkOracle(kernel, wide, outputsOf(p, k, c)))
      }
    }
    (partial.exists(_.kernels.size > 1), partial.map(_.fused.inputOrdinals.size).getOrElse(0))
  }

  // ---------------------------------------------------------------------------------------------
  // A forced decline is the entry deleted (VARKA-296).
  // ---------------------------------------------------------------------------------------------

  private val forcedIterations =
    sys.props.get("varka.fuzz.forcedCompositions").map(_.toInt).getOrElse(300)

  private def aliased(entries: Seq[Entry]): Seq[NamedExpression] =
    entries.zipWithIndex.map { case (e, i) => Alias(e.expr, s"c$i")() }

  private def fusedPositions(p: PartialVarkaProjection): Seq[Int] =
    p.specs.asScala.toSeq.zipWithIndex.collect {
      case (_: FusedOutput, i) => i
      case (_: KernelOutput, i) => i
    }

  /**
   * A projection of up to forty of the table's rows with one entry the compiler fuses forced to
   * decline (`forceResidualAt`), or None when nothing fuses. The forced position rides the case's
   * options, so a shrunk case keeps its force.
   */
  private def drawForced(seed: Long, iteration: Int): Option[VarkaCompositionCase] = {
    val rnd = new Random(seed * 1000003L + 1300000L + iteration)
    val picked = Seq.fill(width(rnd, 40))(projections(rnd.nextInt(projections.size)))
    val entries = picked.map(row => Entry(row.executable, resolve(row.executable))).toVector
    val opts = options(rnd, seed, iteration)
    VarkaExpressionCompiler.compilePartial(aliased(entries), columns, opts).toScala
      .map(fusedPositions).filter(_.nonEmpty).map { fused =>
        val k = fused(rnd.nextInt(fused.size))
        val c = VarkaCompositionCase("forced", entries, opts.withForceResidualAt(k + 1), 0L, "")
        c.copy(label = whereOf(seed, iteration, c, "entries", picked.map(_.executable)) +
          s"\n  forced: entry $k")
      }
  }

  /**
   * What differs between the projection with entry `k` forced to decline and the projection
   * without it, compiled with the same options otherwise: the fused sub-projection, the further
   * kernels, the specs past `k` and the declines shifted past it. A decline that rolls the shared
   * tables back exactly is the entry never having been seen.
   */
  private def forcedDifference(
      forced: Option[PartialVarkaProjection],
      deleted: Option[PartialVarkaProjection],
      k: Int): Option[String] = (forced, deleted) match {
    case (None, None) => None
    case (Some(_), None) => Some("the forced projection fused, the one without the entry did not")
    case (None, Some(_)) => Some("the projection without the entry fused, the forced one did not")
    case (Some(f), Some(d)) =>
      val a = f.fused
      val b = d.fused
      val parts = Seq(
        "outputs" -> (a.outputs.asScala.toSeq != b.outputs.asScala.toSeq),
        "outputTypes" -> (a.outputTypes.asScala.toSeq != b.outputTypes.asScala.toSeq),
        "inputOrdinals" -> (a.inputOrdinals.asScala.toSeq != b.inputOrdinals.asScala.toSeq),
        "literals" -> (a.literals.asScala.toSeq != b.literals.asScala.toSeq),
        "inputBounds" -> (a.inputBounds.asScala.toSeq != b.inputBounds.asScala.toSeq),
        "derivedInputs" -> (a.derivedInputs.asScala.toSeq != b.derivedInputs.asScala.toSeq),
        "longLiterals" -> (a.longLiterals.asScala.toSeq != b.longLiterals.asScala.toSeq),
        "further kernels" -> (f.more.asScala.toSeq != d.more.asScala.toSeq),
        "specs" -> (f.specs.asScala.toSeq.patch(k, Nil, 1) != d.specs.asScala.toSeq),
        "declines" -> (f.declines.asScala.collect {
          case (p, v) if p != k => (if (p > k) p - 1 else p) -> v
        } != d.declines.asScala)).collect { case (name, true) => name }
      if (parts.isEmpty) None else Some(parts.mkString(", "))
  }

  private def checkForced(c: VarkaCompositionCase): Unit = {
    val k = c.options.forceResidualAt - 1
    if (k >= 0 && k < c.entries.size) {
      val (forced, deleted) = try {
        (VarkaExpressionCompiler.compilePartial(aliased(c.entries), columns, c.options).toScala,
          VarkaExpressionCompiler.compilePartial(aliased(c.entries.patch(k, Nil, 1)), columns,
            c.options.withForceResidualAt(0)).toScala)
      } catch {
        case e: Exception => fail(finding(s"the compiler threw ${threw(e)}", c), e)
      }
      forcedDifference(forced, deleted, k).foreach { what =>
        fail(finding(s"a forced decline differs from the entry deleted on: $what", c))
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The coverage rows' kernels as C2 code (VARKA-274).
  // ---------------------------------------------------------------------------------------------

  /**
   * Coverage row `sql`'s kernel `plan` as a case for the compiled histories (VARKA-274): one batch
   * of 64 to 1,000 rows, a third of each column's rows null, its values drawn as `checkKernel`
   * draws them. The compiled histories compare with no decline allowed, so the batch is the first
   * of three attempts, each nearer zero, whose every row the guards answer, with no nulls, with the
   * batch's own null flags and with column 0 all-null. Only the row's derived truncation level is
   * named to the check (`levelOrdinal`); its other derived codes, its bounds and a `TIME`'s day
   * are not, so only the histories that run this batch's values (`compiledHistories`) may run it.
   */
  private def compiledCase(sql: String, plan: CompiledVarkaProjection, opts: VarkaEmitOptions,
      rnd: Random): VarkaFuzzCase = {
    val n = plan.inputOrdinals.size
    val isLong = plan.lane == LaneType.LONG
    val length = Seq(64, 100, 257, 1000)(rnd.nextInt(4))
    val nulls = Array.fill(n)(Array.tabulate(length)(i => i % 5 != 4 && rnd.nextInt(3) == 0))
    val lits = if (isLong) plan.longLiterals.asScala.map(_.longValue).toArray
      else plan.literals.asScala.map(_.longValue).toArray
    def value(i: Int, scale: Int): Long =
      if (isLong) drawLong(rnd, columns(plan.inputOrdinals.get(i)).dataType, scale)
      else intInput(plan, i, scale, rnd)
    // The null flags the histories run: none (the other body's run, which reads every value),
    // the batch's own, and `quiet-null`'s column 0 all-null.
    val masks = Seq(nulls.map(_.map(_ => false)), nulls, nulls.zipWithIndex.map { case (z, i) =>
      if (i == 0) z.map(_ => true) else z })
    val answered = (0 until 3).iterator
      .map(scale => Array.tabulate(n)(i => Array.fill(length)(value(i, scale))))
      .find { d =>
        val rows = Array.tabulate(length)(r => Array.tabulate(n)(col => d(col)(r)))
        masks.forall { z =>
          VarkaGuardSpecs.verdict(plan.outputs, n, lits,
            if (isLong) plan.literals.asScala.map(_.intValue).toArray else Array.empty[Int],
            opts, isLong, rows,
            Array.tabulate(length)(r => Array.tabulate(n)(col => z(col)(r))),
            java.util.List.of()).answers()
        }
      }
    val data = answered.getOrElse(fail(s"$sql: a guard declined each of three attempts"))
    val level = (0 until n).find(i =>
      plan.derivedAt(i).toScala.exists(_.kind == VarkaDerivedKind.TRUNC_LEVEL)).getOrElse(-1)
    new VarkaFuzzCase(plan.lane, plan.outputs, n, lits, length, nulls, data, false, opts, -1,
      level, s"coverage row $sql, ${plan.lane} lane, $length rows")
  }

  /**
   * The coverage rows the compiled test runs (VARKA-274): `-Dvarka.fuzz.coverageCompiled`, rows a
   * lane (default 4) or `all`, and `-Dvarka.fuzz.coverageRow=<sql>` runs that row alone.
   */
  private val coverageCompiled = sys.props.get("varka.fuzz.coverageCompiled")
  private val coverageRow = sys.props.get("varka.fuzz.coverageRow")

  /**
   * The histories the compiled test runs: `warm-same`, `quiet-null` and `released`, or those of
   * them, `cold` and `recompiled` that `-Dvarka.fuzz.history` names. The others draw values of
   * their own (`warm-other`) or walk a column to a guard's edge (`quiet-guard`), within the IR
   * grammar's bounds, which a coverage row's derived codes, bounds and `TIME` day do not share.
   */
  private def compiledHistories: java.util.Set[VarkaCompiledCheck.History] = {
    import VarkaCompiledCheck.History._
    val named = VarkaCompiledCheck.Settings.fromProperties().only.asScala.toSet
    val runs = if (named.isEmpty) Set(WARM_SAME, QUIET_NULL, RELEASED)
      else named.intersect(Set(COLD, WARM_SAME, QUIET_NULL, RECOMPILED, RELEASED))
    assert(runs.nonEmpty, s"no history of ${named.map(_.label)} runs a coverage row's batch")
    runs.asJava
  }

  // ---------------------------------------------------------------------------------------------
  // Failures, shrunk (VARKA-294).
  // ---------------------------------------------------------------------------------------------

  /** Off with `-Dvarka.fuzz.shrink=false`, which leaves a failure as the generator drew it. */
  private val shrinkFailures = !sys.props.get("varka.fuzz.shrink").contains("false")

  /** What `check(c)` threw as a signature; None when the case passes. */
  private def outcomeOf(check: VarkaCompositionCase => Any)(
      c: VarkaCompositionCase): Option[VarkaFailureSignature] =
    try {
      check(c)
      None
    } catch {
      case e: VirtualMachineError => throw e
      case e: InterruptedException => throw e
      case t: Throwable => Some(VarkaFailureSignature.of(t, "kernel"))
    }

  /**
   * Runs the case. A failure - any `Throwable`, a finding or a mismatch out of a kernel - is
   * shrunk and rethrown as a test failure whose message has the original and the smaller case,
   * unless its signature is on the known list, which is reported and returns None.
   */
  private def runChecked[T](c: VarkaCompositionCase,
      known: java.util.List[VarkaKnownFailures.Entry] = VarkaKnownFailures.entries)(
      check: VarkaCompositionCase => T): Option[T] = {
    try Some(check(c)) catch {
      case e: VirtualMachineError => throw e
      case e: InterruptedException => throw e
      case t: Throwable if shrinkFailures =>
        val signature = VarkaFailureSignature.of(t, "kernel")
        if (VarkaKnownFailures.isKnown(known, signature)) {
          logWarning(s"known fuzz failure, not shrunk: $signature")
          return None
        }
        val shrunk = VarkaCompositionShrinker.shrink(c, signature, outcomeOf(check))
        val early = (if (shrunk.stoppedEarly) ", budget reached" else "") +
          (if (shrunk.stable) "" else ", UNSTABLE: it did not fail the same way three times")
        fail(Option(t.getMessage).getOrElse(t.getClass.getName) +
          s"\n  shrunk to ${VarkaCompositionCase.describe(shrunk.small)}" +
          s"\n  (${shrunk.runs} runs, ${shrunk.millis} ms$early); signature: $signature", t)
    }
  }

  /** `c` with the planted-bug option `name` on. */
  private def planted(c: VarkaCompositionCase, name: String): VarkaCompositionCase =
    c.copy(options = VarkaEmitOption.named(name) match {
      case flag: VarkaEmitOption.Flag => flag.`with`(c.options, true)
      case count: VarkaEmitOption.Count => count.`with`(c.options, 1)
      case other => fail(s"$name is not a planted-bug option: $other")
    })

  /**
   * Coverage rows' kernels alone over three of `VarkaCompiledCheck`'s histories: warmed to C2 and
   * given its batch (`warm-same`), warmed on partial nulls and given an all-null column
   * (`quiet-null`), and with C1 on and no warm-up over batches of 10,000 rows (`released`), every
   * batch compared with the reference evaluator whatever tier served it, and the tier read by
   * `VarkaTierWitness`. So the lowerings the table lists are checked as the C2 code users run, not
   * only in the interpreter, which is all the other tests' single calls reach.
   * `-Dvarka.fuzz.history` narrows them (`compiledHistories`).
   *
   * A row costs four to six seconds on a quiet laptop and many times that under load, so a run
   * takes a sample of rows a lane, drawn by the seed, and the nightly, whose seed is the day's,
   * runs every row (`dev/varka_compiled_fuzz.sh`). Each row's batch is drawn from a stream of its
   * own, so a row's case is the same in a sample as in the whole table.
   *
   * At the matrix base without a lanes override, and registered first: a lanes-override kernel
   * puts a second vector species in the JVM, and every later kernel's C2 code then boxes its
   * vectors, which no user's JVM runs; the other tests emit such kernels. A wrong answer fails; a
   * history whose tier never showed is counted and printed, and fails only under
   * `-Dvarka.fuzz.requireTiers=true`, as in `VarkaIrFuzzSuite`. The recording is stopped when the
   * test ends.
   */
  test("coverage rows' kernels agree with the reference evaluator as C2 code, across a trap " +
      "and on the way up the tiers (VARKA-274)") {
    val opts = VarkaMatrix.base.withLanesOverride(0)
    val props = VarkaCompiledCheck.Settings.fromProperties()
    val settings = new VarkaCompiledCheck.Settings(props.settleMillis, props.releasedBatches,
      props.releasedRows, props.witnessed, compiledHistories)
    val tally = new VarkaCompiledCheck.Tally
    try {
      val rowKernels = projections.map { row =>
        row.executable -> VarkaExpressionCompiler.compile(Seq(Alias(resolve(row.executable),
          "c")()), columns, opts).toScala.getOrElse(fail(s"${row.executable} did not fuse alone"))
      } ++ predicates.map { row =>
        row.executable -> VarkaExpressionCompiler.compilePredicate(resolve(row.executable),
          columns, opts).toScala.getOrElse(fail(s"${row.executable} did not fuse alone")).fused
      }
      // A kernel that reads both literal tables (VARKA-28) has no form in the compiled check's
      // case, which holds one; `VarkaEmitterMixedLaneSuite` and the IR fuzzer's mixed corpus run
      // such kernels instead.
      val oneTable =
        rowKernels.filter { case (_, p) => p.literals.isEmpty || p.longLiterals.isEmpty }
      val kernels = coverageRow match {
        case Some(sql) =>
          oneTable.filter(_._1 == sql).ensuring(_.nonEmpty, s"no coverage row is $sql")
        case None if coverageCompiled.contains("all") => oneTable
        case None =>
          val perLane = coverageCompiled.map(_.toInt).getOrElse(4)
          LaneType.values.toSeq.flatMap { lane =>
            new Random(seed ^ 274L ^ lane.ordinal).shuffle(oneTable.filter(_._2.lane == lane))
              .take(perLane)
          }
      }
      for ((sql, plan) <- kernels) {
        val c = compiledCase(sql, plan, opts, new Random(seed ^ 274L ^ sql.hashCode))
        val emit: java.util.function.Function[String, Array[Byte]] = name =>
          VarkaLoopEmitter.emit(name, plan.outputs, plan.inputOrdinals.size, plan.numLiterals,
            null, null, opts)
        val replay = s"-Dvarka.fuzz.seed=$seed -Dvarka.fuzz.coverageRow=" + "\"" + sql + "\""
        try tally.add(VarkaCompiledCheck.run(c, emit, settings, replay))
        catch {
          case f: VarkaCompiledCheck.HistoryFailure => fail(f.getMessage, f)
        }
      }
      info(s"${kernels.size} of ${rowKernels.size} coverage rows' kernels")
      if (settings.witnessed) VarkaTierWitness.get().settle()
      tally.report(settings.witnessed).asScala.foreach(info(_))
      if (settings.witnessed) {
        val notReached = VarkaCompiledCheck.notReached(tally).asScala
        val require = sys.props.get("varka.fuzz.requireTiers").contains("true")
        info(s"${notReached.size} history(ies) not reached" +
          (if (require) "" else " (counted; -Dvarka.fuzz.requireTiers=true fails on them)"))
        notReached.foreach(why => if (require) fail(why) else info(why))
      }
      assert(tally.results().size == kernels.size)
    } finally {
      VarkaTierWitness.close()
    }
  }

  test("random projections over more columns than a kernel reads are fused or declined, and " +
      "their kernels answer as the reference evaluator does") {
    val heldBefore = VarkaKernelCheck.sparkComparisons.get()
    var severalKernels = 0
    var widest = 0
    comparedByLane.clear()
    kernelsByLane.clear()
    redrawn = 0
    for (iteration <- 0 until wideIterations) {
      runChecked(drawWide(seed, iteration))(checkWide).foreach { case (several, first) =>
        widest = math.max(widest, first)
        if (several) severalKernels += 1
      }
    }
    def perLane(m: scala.collection.Map[LaneType, Int]): String =
      LaneType.values.toSeq.map(l => s"${m.getOrElse(l, 0)} $l").mkString(", ")
    info(s"$severalKernels of $wideIterations projections served by several kernels, " +
      s"kernels compared row by row: ${perLane(comparedByLane)} of ${perLane(kernelsByLane)}, " +
      s"$redrawn batches drawn again after a decline, the widest first kernel reading $widest " +
      s"columns, ${VarkaKernelCheck.sparkComparisons.get() - heldBefore} answers held to Spark")
    // Every lane that has kernels has comparisons, since a declined batch is drawn again until
    // no guard declines it; the draw decides only how many kernels each lane gets.
    assert(severalKernels > 0, s"no projection reached several kernels; the widest first " +
      s"kernel read $widest columns")
    kernelsByLane.keys.foreach { lane =>
      assert(comparedByLane.getOrElse(lane, 0) == kernelsByLane(lane),
        s"${comparedByLane.getOrElse(lane, 0)} of ${kernelsByLane(lane)} $lane kernels were " +
          "compared row by row")
    }
  }

  test("every coverage row's kernel agrees with the reference evaluator, and the reference " +
      "with Spark's eval (VARKA-276)") {
    // Each row alone, as the coverage suite compiles it, at both widths the emitted-bytes oracle
    // pins, on batches drawn as the wide test draws them: the kernel, the reference evaluator and
    // Catalyst's interpreted eval of the row's own expression, three ways on every row. Then one
    // fixed batch per row and width with a poisoned null lane in the loop and in the epilogue
    // (VARKA-138), whose values come from a stream of their own, so that the drawn batches, and
    // the seeds that replay them, are what they were before it.
    val rnd = new Random(seed ^ 276L)
    val poisonedRnd = new Random(seed ^ 138L)
    val before = VarkaKernelCheck.sparkComparisons.get()
    var checked = 0
    var poisoned = 0
    var mixed = 0
    for (opts <- Seq(VarkaMatrix.base, VarkaMatrix.base.withLanesOverride(4))) {
      for (row <- projections) {
        val expr = resolve(row.executable)
        val plan = VarkaExpressionCompiler.compile(Seq(Alias(expr, "c")()), columns, opts).toScala
          .getOrElse(fail(s"${row.executable} did not fuse alone"))
        val spark = sparkOracle(plan, columns, Seq(expr))
        assert(spark.isDefined, s"${row.executable} derives an input")
        // The row's expression is the check's context, which a failure's message starts with:
        // the checks fail with an IllegalStateException, which `withClue` would pass through.
        (0 until 4).foreach(_ => checkKernel(plan, columns, opts, rnd, spark,
          context = s"kernel of ${row.executable}"))
        if (checkKernel(plan, columns, opts, poisonedRnd, spark,
            context = s"kernel of ${row.executable} (poisoned batch)", poisoned = true)) {
          poisoned += 1
          if (plan.inputOrdinals.size > 1) mixed += 1
        }
        checked += 1
      }
      for (row <- predicates) {
        val predicate = VarkaExpressionCompiler.compilePredicate(resolve(row.executable), columns,
          opts).toScala.getOrElse(fail(s"${row.executable} did not fuse alone"))
        val mask = predicate.fusedConjuncts.asScala.toSeq.reduce(And)
        val spark = sparkOracle(predicate.fused, columns, Seq(mask))
        assert(spark.isDefined, s"${row.executable} derives an input")
        (0 until 4).foreach(_ => checkKernel(predicate.fused, columns, opts, rnd, spark,
          context = s"kernel of ${row.executable}"))
        if (checkKernel(predicate.fused, columns, opts, poisonedRnd, spark,
            context = s"kernel of ${row.executable} (poisoned batch)", poisoned = true)) {
          poisoned += 1
          if (predicate.fused.inputOrdinals.size > 1) mixed += 1
        }
        checked += 1
      }
    }
    assert(checked == 2 * (projections.size + predicates.size))
    // One poisoned batch per row and width, and each compared row by row: a batch every attempt
    // of which a guard declined would have put no poisoned lane in front of the kernel.
    assert(poisoned == checked, s"$poisoned of $checked kernels compared on a poisoned batch")
    // Every kernel of two or more inputs met a null beside a valid input on rows of its own.
    (2 to 4).foreach(n => assert(mixedRows(n) > 100, s"${mixedRows(n)} mixed rows at $n inputs"))
    val compared = VarkaKernelCheck.sparkComparisons.get() - before
    assert(compared > 100000, s"only $compared answers were held to Spark")
    info(s"$checked kernels, $poisoned of them on a poisoned batch ($mixed with a null beside a " +
      s"valid input on ${mixedRows(2)} of its rows at two inputs), $compared answers held to Spark")
  }

  test("a kernel that writes no validity fails at one fill and passes at the other (VARKA-138)") {
    // The planted fault: a coverage row's kernel with every write to its output validity taken
    // out of its bytes, so each bitmap keeps what the harness filled it with. On a batch with no
    // nulls every row is valid: the ones fill reads right and only the zero fill - which is what
    // production's Arrow buffers hold - shows the bits the kernel never set. On a batch of nulls
    // it is the other way round: only the ones fill shows the bits the kernel never cleared. So
    // each fill catches what the other cannot, and the check reaches the kernel at both. Each
    // batch runs through its own body only: the all-null batch's other body is a dense one.
    import VarkaKernelChecks.ValidityFill.{ONES, ZERO}
    val rows = Seq("year(d)", "greatest(l, l2)")
    val widths = Seq(VarkaMatrix.base -> "the default width",
      VarkaMatrix.base.withLanesOverride(4) -> "four lanes")
    for ((opts, width) <- widths; sql <- rows) {
      val plan = VarkaExpressionCompiler.compile(Seq(Alias(resolve(sql), "c")()), columns, opts)
        .toScala.getOrElse(fail(s"$sql did not fuse alone"))
      val numInputs = plan.inputOrdinals.size
      val roots = plan.outputs.asScala.toSeq
      kernelCounter += 1
      val className = s"org.apache.spark.sql.varka.execution.VarkaValidityFault$kernelCounter"
      val bytes = VarkaLoopEmitter.emit(className, plan.outputs, numInputs, plan.numLiterals,
        null, null, opts)
      val faulty = VarkaValidityFault.withoutValidityWrites(bytes)
      val rnd = new Random(seed ^ 1380L)
      // A whole number of bytes, so the check of the bits past the last row (which this fault
      // fails at the ones fill, see the end) does not decide the row-bit assertions.
      var length = 256
      val intLits = plan.literals.asScala.map(_.intValue).toArray
      val longLits = plan.longLiterals.asScala.map(_.longValue).toArray
      def intBatch(allNull: Boolean): VarkaKernelCheck.Batch = VarkaKernelCheck.Batch(length,
        Seq.fill(numInputs)((_: Int) => allNull),
        Array.fill(numInputs, length)(rnd.nextInt(60001) - 30000), forceMasked = false)
      def longBatch(allNull: Boolean): VarkaKernelCheck.LongBatch =
        VarkaKernelCheck.LongBatch(length, Seq.fill(numInputs)((_: Int) => allNull),
        Array.fill(numInputs, length)(VarkaIrGrammar.within(rnd.self, 1L << 40)),
        forceMasked = false)
      // `fill` None is the check every fuzzer runs: both bodies, each at both fills.
      def run(code: Array[Byte], allNull: Boolean, fill: Option[VarkaKernelChecks.ValidityFill])
          : Unit = {
        val context = s"kernel of $sql at $width, ${if (allNull) "every row null" else "no nulls"}"
        if (plan.lane == LaneType.LONG) {
          val batch = longBatch(allNull)
          fill match {
            case Some(f) => VarkaKernelCheck.runOwnBodyLong(context, className, code, roots,
              numInputs, longLits, batch, fills = Seq(f), intLits = intLits)
            case None => VarkaKernelCheck.runAndCompareLong(context, className, code, roots,
              numInputs, longLits, batch, intLits = intLits)
          }
        } else {
          val batch = intBatch(allNull)
          fill match {
            case Some(f) => VarkaKernelCheck.runOwnBody(context, className, code, roots,
              numInputs, intLits, batch, fills = Seq(f))
            case None => VarkaKernelCheck.runAndCompare(context, className, code, roots,
              numInputs, intLits, batch)
          }
        }
      }
      // The validity bytes a kernel changed from the fill, read off its buffers rather than off
      // the rewrite's own list of writers, so a writer the rewrite misses shows here.
      def written(code: Array[Byte], allNull: Boolean, fill: VarkaKernelChecks.ValidityFill)
          : Int =
        if (plan.lane == LaneType.LONG) {
          VarkaKernelCheck.validityBytesWrittenLong(className, code, roots, numInputs, longLits,
            longBatch(allNull), fill, intLits)
        } else {
          VarkaKernelCheck.validityBytesWritten(className, code, roots, numInputs, intLits,
            intBatch(allNull), fill)
        }
      for (allNull <- Seq(false, true); fill <- Seq(ZERO, ONES)) {
        assert(written(faulty, allNull, fill) == 0,
          s"$sql at $width: the faulty kernel still writes validity (all null: $allNull, $fill)")
      }
      // The emitted kernel writes the bits each batch's answer needs, at whichever fill hid them.
      assert(written(bytes, allNull = false, ZERO) > 0 && written(bytes, allNull = true, ONES) > 0,
        s"$sql at $width writes no validity")
      def fails(allNull: Boolean, fill: Option[VarkaKernelChecks.ValidityFill]): String =
        intercept[IllegalStateException](run(faulty, allNull, fill)).getMessage
      // The kernel as emitted passes every way it is run.
      for (allNull <- Seq(false, true); fill <- Seq(None, Some(ZERO), Some(ONES))) {
        run(bytes, allNull, fill)
      }
      // The faulty one: the zero fill alone catches the bits it never sets...
      run(faulty, allNull = false, Some(ONES))
      val unset = fails(allNull = false, Some(ZERO))
      assert(unset.contains("(output validity pre-filled 0x00): validity of output 0 row 0 " +
        "differs (want Some("), unset)
      // ...the ones fill alone the bits it never clears...
      run(faulty, allNull = true, Some(ZERO))
      val uncleared = fails(allNull = true, Some(ONES))
      assert(uncleared.contains("(output validity pre-filled 0xFF): validity of output 0 row 0 " +
        "differs (want None, got Some("), uncleared)
      // ...and the check the fuzzers run, at both fills, catches both.
      Seq(false, true).foreach(allNull => fails(allNull, None))
      // At 257 rows the faulty kernel leaves the ones fill in the last byte's seven bits past
      // the last row, which every writer clears (`setValid`'s contract), and fails there too.
      length = PoisonedLength
      run(bytes, allNull = false, Some(ONES))
      val tail = fails(allNull = false, Some(ONES))
      assert(tail.contains("has bits set past row 256 in its last byte (0xff)"), tail)
      info(s"${VarkaValidityFault.validityWrites(bytes)} validity writes removed; " +
        s"${unset.linesIterator.next()}; ${uncleared.linesIterator.next()}")
    }
  }

  test("the poisoned batch puts a null beside a valid input, which a wrong kernel fails on " +
      "(VARKA-138)") {
    // A planted wrong kernel for greatest(l, l2): `CASE WHEN l > l2 THEN l ELSE l2 END`, which
    // agrees with greatest wherever both inputs are valid or both null, and answers null where
    // only l2 is - where greatest skips the null and answers l. The poisoned batch as first built
    // made every input null together, so this kernel passed it; with each input on its own null
    // phase it fails, on the first row where l2 alone is null.
    val sql = "greatest(l, l2)"
    for (opts <- Seq(VarkaMatrix.base, VarkaMatrix.base.withLanesOverride(4))) {
      val expr = resolve(sql)
      val plan = VarkaExpressionCompiler.compile(Seq(Alias(expr, "c")()), columns, opts).toScala
        .getOrElse(fail(s"$sql did not fuse alone"))
      val wrong = plan.outputs.get(0) match {
        case g: VarkaVectorIR.Greatest =>
          new VarkaVectorIR.IfElse(
            new VarkaVectorIR.Compare(VarkaVectorIR.CompareOp.GT, g.left, g.right),
            g.left, g.right)
        case other => fail(s"$sql compiled to $other, not a Greatest")
      }
      val spark = sparkOracle(plan, columns, Seq(expr))
      def check(patternsOf: Int => Seq[Int => Boolean]): Boolean = {
        // Emitted under the class name checkKernel is about to give its kernel.
        val className =
          s"org.apache.spark.sql.varka.execution.VarkaCompositionWide${kernelCounter + 1}"
        val bytes = VarkaLoopEmitter.emit(className, java.util.List.of(wrong),
          plan.inputOrdinals.size, plan.numLiterals, null, null, opts)
        checkKernel(plan, columns, opts, new Random(seed ^ 138L), spark,
          context = s"wrong kernel of $sql", poisoned = true, kernelBytes = Some(bytes),
          patternsOf = patternsOf)
      }
      assert(check(samePhasePatterns), "the wrong kernel should pass the same-phase batch")
      val e = intercept[IllegalStateException](check(poisonedPatterns))
      assert(e.getMessage.contains("wrong kernel of greatest(l, l2)") &&
        e.getMessage.contains("differs (want Some(") && e.getMessage.contains("got None)"),
        e.getMessage)
      info(e.getMessage.linesIterator.next())
    }
  }

  test("random projections of coverage rows are fused or declined in bytes, never thrown") {
    only match {
      case Some(i) => runChecked(drawProjection(seed, i))(checkProjection)
      case None => (0 until iterations).foreach(i =>
        runChecked(drawProjection(seed, i))(checkProjection))
    }
  }

  test("random conjunctions of coverage predicates are fused or declined in bytes, never thrown") {
    only match {
      case Some(i) => runChecked(drawPredicate(seed, i))(checkPredicate)
      case None => (0 until iterations).foreach(i =>
        runChecked(drawPredicate(seed, i))(checkPredicate))
    }
  }

  test("a forced decline leaves the projection the entry was never in") {
    var checked = 0
    val range = only.map(i => i to i).getOrElse(0 until forcedIterations)
    for (iteration <- range) {
      drawForced(seed, iteration).foreach { c =>
        runChecked(c)(checkForced)
        checked += 1
      }
    }
    info(s"$checked of ${range.size} projections had an entry to force")
    // A draw with nothing to force checks nothing; most draws fuse something.
    assert(checked * 2 > range.size, s"only $checked of ${range.size} draws had an entry to force")
  }

  test("the forced-decline check sees a wrong rollback") {
    // One case a fault: the forced entry registers a column only it reads (1), a bound on a column
    // another entry keeps in the table (2), and a column after one an earlier entry reads (3).
    val cases = Seq(
      1 -> (Seq("datediff(d2, d)", "d + CAST(i AS INTERVAL DAY)", "year(d)"), 2),
      2 -> (Seq("d + CAST(i AS INTERVAL DAY)", "i + 1"), 1),
      3 -> (Seq("datediff(d2, d)", "d + CAST(i AS INTERVAL DAY)", "i - 1"), 2))
    for ((fault, (sqls, force)) <- cases) {
      val c = VarkaCompositionCase("forced", sqls.map(sql => Entry(sql, resolve(sql))).toVector,
        VarkaMatrix.base.withForceResidualAt(force), 0L, s"planted fault $fault")
      checkForced(c)
      val e = intercept[org.scalatest.exceptions.TestFailedException](
        checkForced(c.copy(options = c.options.withMisdescribeRollback(fault))))
      assert(e.getMessage.contains("a forced decline differs from the entry deleted"),
        s"fault $fault: ${e.getMessage}")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The shrinker on this fuzzer's cases (VARKA-294).
  // ---------------------------------------------------------------------------------------------

  /** A planted-bug wide case's failure and its shrink: the first of ten draws that fails. */
  private def shrunkPlanted(name: String): (VarkaCompositionCase, VarkaFailureSignature,
      VarkaCompositionShrinker.Shrunk) = {
    val found = (0 until 10).iterator.map(k => planted(drawWide(seed, k), name))
      .map(c => (c, outcomeOf(checkWide)(c))).collectFirst { case (c, Some(sig)) => (c, sig) }
    val (c, sig) = found.getOrElse(fail(s"$name: no failing wide case in 10 draws"))
    (c, sig, VarkaCompositionShrinker.shrink(c, sig, outcomeOf(checkWide)))
  }

  test("a planted bug in a wide projection shrinks from hundreds of entries to a few") {
    val (c, sig, shrunk) = shrunkPlanted("misdescribeAdd")
    val small = shrunk.small
    info(s"${c.entries.size} entries shrunk to ${small.entries.size} in ${shrunk.runs} runs, " +
      s"${shrunk.millis} ms: ${VarkaCompositionCase.describe(small)}")
    assert(c.entries.size >= 150)
    assert(small.entries.size <= 3, VarkaCompositionCase.describe(small))
    assert(small.entries.map(e => VarkaCompositionShrinker.size(e.expr)).sum <=
      small.entries.map(e => VarkaCompositionShrinker.size(e.expr)).size * 8,
      VarkaCompositionCase.describe(small))
    assert(VarkaFuzzCase.optionDelta(small.options).contains("misdescribeAdd=true"))
    assert(VarkaFuzzCase.optionDelta(small.options).size <= 3)
    assert(sig.kind == "generated class: NoSuchMethodError", sig)
    assert(shrunk.runs <= 300 && shrunk.millis < 30000L && !shrunk.stoppedEarly, shrunk.toString)
    assert(shrunk.stable)
  }

  test("ddmin over the entries finds the pair a failure needs") {
    val c = drawProjection(seed, 3)
    val sixty = (0 until 60).map(i => c.entries(i % c.entries.size).copy(sql = s"row$i")).toVector
    val base = c.copy(entries = sixty)
    val sig = new VarkaFailureSignature("test", "needs a pair")
    def outcome(x: VarkaCompositionCase): Option[VarkaFailureSignature] =
      if (Seq("row7", "row41").forall(r => x.entries.exists(_.sql == r))) Some(sig) else None
    val shrunk = VarkaCompositionShrinker.shrink(base, sig, outcome)
    assert(shrunk.small.entries.map(_.sql) == Vector("row7", "row41"), shrunk.small.entries)
    assert(shrunk.stable && !shrunk.stoppedEarly)
  }

  test("an entry's expression shrinks to the node a failure needs") {
    val nested = Entry("date_add(last_day(d), 3)", resolve("date_add(last_day(d), 3)"))
    val base = VarkaCompositionCase("projection", Vector(nested), VarkaMatrix.base, 0L, "")
    val sig = new VarkaFailureSignature("test", "needs last_day")
    def outcome(x: VarkaCompositionCase): Option[VarkaFailureSignature] =
      if (x.entries.exists(_.expr.sql.contains("last_day"))) Some(sig) else None
    val shrunk = VarkaCompositionShrinker.shrink(base, sig, outcome)
    assert(VarkaCompositionShrinker.size(nested.expr) > 3)
    assert(shrunk.small.entries.head.expr.sql.startsWith("last_day("), shrunk.small.entries)
    assert(VarkaCompositionShrinker.size(shrunk.small.entries.head.expr) == 2)
  }

  test("a failure fails its test with the smaller case in the message") {
    val (c, _, _) = shrunkPlanted("misdescribeAdd")
    val e = intercept[org.scalatest.exceptions.TestFailedException](runChecked(c)(checkWide))
    assert(e.getMessage.contains("shrunk to kind=wide"), e.getMessage)
    assert(e.getMessage.contains("signature: generated class: NoSuchMethodError"), e.getMessage)
  }

  test("a failure with a listed signature is reported as known, not failed") {
    val (c, sig, _) = shrunkPlanted("misdescribeAdd")
    val known = java.util.List.of(new VarkaKnownFailures.Entry("wide", seed, 0, sig, "test"))
    assert(runChecked(c, known)(checkWide).isEmpty)
  }

  test("every known composition failure still reproduces") {
    val replay = (e: VarkaKnownFailures.Entry) => e.lane match {
      case "projection" => outcomeOf(checkProjection)(drawProjection(e.seed, e.iteration))
      case "predicate" => outcomeOf(checkPredicate)(drawPredicate(e.seed, e.iteration))
      case "wide" => outcomeOf(checkWide)(drawWide(e.seed, e.iteration))
      case "forced" => drawForced(e.seed, e.iteration).flatMap(outcomeOf(checkForced))
      // The IR fuzzer's entries are replayed by its own suite.
      case _ => Some(e.signature)
    }
    val stale = VarkaKnownFailures.stale(VarkaKnownFailures.entries,
      (e: VarkaKnownFailures.Entry) => replay(e).toJava).asScala
    assert(stale.isEmpty, "these known fuzz failures no longer reproduce; delete them from " +
      s"${VarkaKnownFailures.PATH}: ${stale.mkString(", ")}")
  }
}
