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
import scala.util.Random

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, AttributeReference, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection, FusedOutput, KernelOutput, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType

/**
 * Random compositions of the coverage table, through the compiler to the emitter.
 *
 * `VarkaIrFuzzSuite` draws IR directly and so never sees the compiler admit anything; the
 * coverage suite compiles every documented expression alone and requires it to fuse. Between
 * the two is the path a wide query takes: many admitted entries in one projection, or many
 * admitted conjuncts in one filter, which the compiler groups, budgets in bytes, regroups and
 * partly declines. That path produced the epilogue past 64KB (`PLAN_TASK_87.md`) and the
 * `CASE WHEN` that failed to emit (`PLAN_TASK_169.md`), and nothing drew it at random.
 *
 * Each iteration composes a projection of one to three hundred entries drawn from the table's
 * projection rows, or a filter of one to sixty-four of its predicate rows, resolves them against
 * the table's own columns, and asks the compiler what the planner asks. The property is the
 * milestone's (`PLAN_MILESTONE_6.md` 1.3): every entry is fused or declined with a reason, the
 * compiler throws nothing, and a decline of an entry the table says fuses alone is one of the
 * two the record knows, a size decline naming the budget or the one-lane rule. Emit options
 * alternate between the default width and four lanes, the two the emitted-bytes oracle pins,
 * and the exact grouping (`PLAN_TASK_200.md`) is on or off at random, since the wide projections
 * drawn here are where it changes the partition.
 *
 * Past the columns one kernel reads (task 238): a third test spreads each of 150 to 300 rows over
 * eighty renamed copies of the table's columns, so the compiler serves the projection with
 * several kernels (`VarkaEmitOptions.severalKernels`), holds the same property, and runs every
 * int-lane kernel without derived inputs or bounds against the reference evaluator
 * (`VarkaKernelCheck`); a batch a guard declines, over columns drawn without their domains, is
 * counted rather than compared. `-Dvarka.fuzz.wideCompositions` sets its count (default 20).
 *
 * Budget: `-Dvarka.fuzz.compositions` (default 40, under a minute); `-Dvarka.fuzz.seed` (default
 * fixed, shared with the IR fuzzer so a nightly varies both with one property). A failure names
 * the seed, the iteration and the rows, and `-Dvarka.fuzz.only=<iteration>` replays one.
 */
class VarkaCoverageCompositionFuzzSuite extends SparkFunSuite {

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
  private def options(rnd: Random, iteration: Int): VarkaEmitOptions = {
    val lanes = if (rnd.nextBoolean()) VarkaEmitOptions.DEFAULTS
      else VarkaEmitOptions.DEFAULTS.withLanesOverride(4)
    lanes.withExactGrouping(new Random(~(seed * 1000003L + iteration)).nextBoolean())
  }

  /**
   * The two reasons a composition may decline an entry that fuses alone: a size decline, whose
   * reason names a budget, and the one-lane rule - a kernel holds one lane, so a projection
   * that mixes the int and the long lane fuses the first lane it meets and leaves the other,
   * which `PLAN_TASK_29.md` pins and task 28's width conversion is to lift. Any other reason on
   * an admitted row is a finding.
   */
  private def isCompositionDecline(reason: String): Boolean =
    reason.contains("budget") || reason.contains("one kernel holds one lane")

  private def runProjection(iteration: Int): Unit = {
    val rnd = new Random(seed * 1000003L + iteration)
    val picked = Seq.fill(width(rnd, 300))(projections(rnd.nextInt(projections.size)))
    val list: Seq[NamedExpression] = picked.zipWithIndex.map { case (row, i) =>
      Alias(resolve(row.executable), s"c$i")()
    }
    val opts = options(rnd, iteration)
    val where = s"seed $seed iteration $iteration, ${picked.size} entries, options " +
      s"${opts.canonical}:\n  ${picked.map(_.executable).mkString("\n  ")}"
    val (fused, declined) = try {
      // A further kernel's entry is fused too (task 190's `severalKernels`, on by default).
      val fused = VarkaExpressionCompiler.compilePartial(list, columns, opts)
        .map(_.specs.zipWithIndex.collect {
          case (_: FusedOutput, i) => i
          case (_: KernelOutput, i) => i
        }.toSet)
        .getOrElse(Set.empty[Int])
      (fused, VarkaExpressionCompiler.declines(list, columns, opts))
    } catch {
      case e: Exception => fail(s"the compiler threw on $where", e)
    }
    assert(fused.size + declined.size == list.size && (fused & declined.keySet).isEmpty,
      s"entries neither fused nor declined, or both, on $where")
    declined.foreach { case (i, d) =>
      assert(d.reason.nonEmpty, s"entry $i declined without a reason on $where")
      assert(isCompositionDecline(d.reason),
        s"entry $i, which fuses alone, declined for '${d.reason}' on $where")
    }
  }

  private def runPredicate(iteration: Int): Unit = {
    val rnd = new Random(seed * 1000003L + 500000L + iteration)
    val picked = Seq.fill(width(rnd, 64))(predicates(rnd.nextInt(predicates.size)))
    val condition = picked.map(row => resolve(row.executable)).reduceLeft(And)
    val opts = options(rnd, iteration)
    val where = s"seed $seed iteration $iteration, ${picked.size} conjuncts, options " +
      s"${opts.canonical}:\n  ${picked.map(_.executable).mkString("\n  ")}"
    val specs = try {
      VarkaExpressionCompiler.explainPredicate(condition, columns, opts)
    } catch {
      case e: Exception => fail(s"the compiler threw on $where", e)
    }
    // A row of the table may itself be a conjunction, which the compiler splits, so the specs
    // are at least as many as the rows picked.
    assert(specs.size >= picked.size, s"${specs.size} conjunct specs for $where")
    specs.zipWithIndex.foreach { case (spec, i) =>
      assert(spec.fused || spec.decline.exists(_.reason.nonEmpty),
        s"conjunct $i neither fused nor declined with a reason on $where")
      spec.decline.filterNot(_ => spec.fused).foreach { d =>
        assert(isCompositionDecline(d.reason),
          s"conjunct $i, which fuses alone, declined for '${d.reason}' on $where")
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Past the columns one kernel reads (task 238).
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
   * Runs one compiled kernel against the reference evaluator where the check can: an int-lane
   * kernel with no derived input and no input bound, over columns drawn without their domains, so
   * a batch a guard declines is counted rather than compared. Returns whether rows were compared.
   */
  private def checkKernel(plan: CompiledVarkaProjection, opts: VarkaEmitOptions, rnd: Random,
      where: String): Boolean = {
    if (plan.lane != LaneType.INT || plan.derivedInputs.nonEmpty || plan.inputBounds.nonEmpty) {
      return false
    }
    val numInputs = plan.inputOrdinals.size
    kernelCounter += 1
    val className = s"org.apache.spark.sql.varka.execution.VarkaCompositionWide$kernelCounter"
    val bytes = VarkaLoopEmitter.emit(className, plan.outputs.asJava, numInputs,
      plan.numLiterals, null, null, opts)
    val length = Seq(1, 7, 64, 100, 257, 1000)(rnd.nextInt(6))
    val patterns: Seq[Int => Boolean] = Seq.fill(numInputs) {
      rnd.nextInt(3) match {
        case 0 => (_: Int) => false
        case 1 => (i: Int) => i % 5 == 0
        case _ =>
          val bits = Array.fill(length)(rnd.nextInt(3) == 0)
          (i: Int) => bits(i)
      }
    }
    val data = Array.fill(numInputs, length)(rnd.nextInt(60001) - 30000)
    VarkaKernelCheck.runAndCompare(s"$where, kernel of ${plan.outputs.size}", className, bytes,
      plan.outputs, numInputs, plan.literals.toArray,
      VarkaKernelCheck.Batch(length, patterns, data, forceMasked = length > 1 && rnd.nextBoolean()),
      declineAllowed = true)
  }

  test("random projections over more columns than a kernel reads are fused or declined, and " +
      "their kernels answer as the reference evaluator does") {
    var severalKernels = 0
    var compared = 0
    var widest = 0
    for (iteration <- 0 until wideIterations) {
      val rnd = new Random(seed * 1000003L + 900000L + iteration)
      val picked = Seq.fill(150 + rnd.nextInt(151))(projections(rnd.nextInt(projections.size)))
      val list: Seq[NamedExpression] = picked.zipWithIndex.map { case (row, i) =>
        Alias(onCopy(resolve(row.executable), rnd.nextInt(copies.size)), s"c$i")()
      }
      val wide = copies.flatten
      val opts = options(rnd, iteration)
      val where = s"seed $seed wide iteration $iteration, ${picked.size} entries, options " +
        s"${opts.canonical}"
      val partial = try {
        VarkaExpressionCompiler.compilePartial(list, wide, opts)
      } catch {
        case e: Exception => fail(s"the compiler threw on $where", e)
      }
      val declined = VarkaExpressionCompiler.declines(list, wide, opts)
      val fused = partial.toSeq.flatMap(_.specs.zipWithIndex.collect {
        case (_: FusedOutput, i) => i
        case (_: KernelOutput, i) => i
      }).toSet
      assert(fused.size + declined.size == list.size && (fused & declined.keySet).isEmpty,
        s"entries neither fused nor declined, or both, on $where")
      declined.foreach { case (i, d) =>
        assert(isCompositionDecline(d.reason),
          s"entry $i, which fuses alone, declined for '${d.reason}' on $where")
      }
      partial.foreach { p =>
        widest = math.max(widest, p.fused.inputOrdinals.size)
        if (p.kernels.size > 1) {
          severalKernels += 1
        }
        p.kernels.foreach(k => if (checkKernel(k, opts, rnd, where)) compared += 1)
      }
    }
    info(s"$severalKernels of $wideIterations projections served by several kernels, " +
      s"$compared kernels compared row by row, the widest first kernel reading $widest columns")
    assert(severalKernels > 0 && compared > 0, s"$severalKernels projections reached several " +
      s"kernels and $compared kernels were compared; the widest first kernel read $widest columns")
  }

  test("random projections of coverage rows are fused or declined in bytes, never thrown") {
    only match {
      case Some(i) => runProjection(i)
      case None => (0 until iterations).foreach(runProjection)
    }
  }

  test("random conjunctions of coverage predicates are fused or declined in bytes, never thrown") {
    only match {
      case Some(i) => runPredicate(i)
      case None => (0 until iterations).foreach(runPredicate)
    }
  }
}
