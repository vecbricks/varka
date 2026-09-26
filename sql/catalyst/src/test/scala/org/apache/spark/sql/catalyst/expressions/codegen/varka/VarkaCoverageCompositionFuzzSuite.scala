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

import scala.util.Random

import org.json4s.{DefaultFormats, JString}
import org.json4s.jackson.JsonMethods.parse

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, AttributeReference,
  Expression, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{FusedOutput, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser

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
 * alternate between the default width and four lanes, the two the emitted-bytes oracle pins.
 *
 * Budget: `-Dvarka.fuzz.compositions` (default 40, under a minute); `-Dvarka.fuzz.seed` (default
 * fixed, shared with the IR fuzzer so a nightly varies both with one property). A failure names
 * the seed, the iteration and the rows, and `-Dvarka.fuzz.only=<iteration>` replays one.
 */
class VarkaCoverageCompositionFuzzSuite extends SparkFunSuite {

  private val seed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(20260925L)
  private val iterations = sys.props.get("varka.fuzz.compositions").map(_.toInt).getOrElse(40)
  private val only = sys.props.get("varka.fuzz.only").map(_.toInt)

  private case class Row(executable: String, predicate: Boolean)

  /** The coverage table's columns and rows, from the committed file the coverage suite keeps. */
  private lazy val (columns: Seq[Attribute], projections: Seq[Row], predicates: Seq[Row]) = {
    implicit val formats: DefaultFormats.type = DefaultFormats
    val json = parse(new String(java.nio.file.Files.readAllBytes(
      getWorkspaceFilePath("sql", "varka", "coverage.json")), "UTF-8"))
    val columns = (json \ "columns").extract[Map[String, String]].toSeq.map { case (name, t) =>
      AttributeReference(name, CatalystSqlParser.parseDataType(t))()
    }
    val rows = (json \ "expressions").children.map { row =>
      val executable = (row \ "executable") match {
        case JString(s) if s.nonEmpty => s
        case _ => (row \ "sql").extract[String]
      }
      Row(executable, (row \ "form").extract[String] == "predicate")
    }
    val (predicates, projections) = rows.partition(_.predicate)
    assert(projections.size > 40 && predicates.size > 10, s"${rows.size} rows read")
    (columns, projections, predicates)
  }

  private def resolve(sql: String): Expression =
    VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(sql), columns)

  /** One to `max`, log-uniform, so most compositions are small and some are very wide. */
  private def width(rnd: Random, max: Int): Int =
    math.max(1, math.exp(rnd.nextDouble() * math.log(max)).toInt)

  private def options(rnd: Random): VarkaEmitOptions =
    if (rnd.nextBoolean()) VarkaEmitOptions.DEFAULTS
    else VarkaEmitOptions.DEFAULTS.withLanesOverride(4)

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
    val opts = options(rnd)
    val where = s"seed $seed iteration $iteration, ${picked.size} entries, options " +
      s"${opts.canonical}:\n  ${picked.map(_.executable).mkString("\n  ")}"
    val (fused, declined) = try {
      val fused = VarkaExpressionCompiler.compilePartial(list, columns, opts)
        .map(_.specs.zipWithIndex.collect { case (_: FusedOutput, i) => i }.toSet)
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
    val opts = options(rnd)
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
