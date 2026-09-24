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

package org.apache.spark.sql.execution

import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{ForwardedOutput, FusedOutput, PartialVarkaProjection, ResidualOutput, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions

/**
 * How a Varka node serves each entry of its projection, in words.
 *
 * Partial eligibility means a fused node can still evaluate entries per row, and until
 * now nothing said which entries those were or why: [[VarkaExpressionCompiler.compilePartial]]
 * classified every entry and dropped the reason on the floor. This renders both - the
 * classification and, for a residual entry, the decline reason the compiler recorded - for the
 * exec nodes' verbose `EXPLAIN` and their debug logs, which is where the question "why didn't my
 * projection fuse?" is actually asked.
 *
 * Rendering is diagnostics only and never on an execution path: the plan-side overload compiles
 * the projection again (the compiler is pure and cheap, and `EXPLAIN` runs once), while the
 * evaluator passes the plan it already compiled.
 */
private[sql] object VarkaFusionReport {

  /** One line per projection entry, against an already compiled classification. */
  def lines(
      partial: PartialVarkaProjection,
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute]): Seq[String] = {
    partial.specs.zipWithIndex.map { case (spec, position) =>
      val name = projectList(position).name
      spec match {
        case FusedOutput(_) =>
          s"$name: fused"
        case ForwardedOutput(ordinal) =>
          s"$name: forwarded from ${childOutput(ordinal).name}"
        case ResidualOutput =>
          val why = partial.declines.get(position).map(_.toString).getOrElse("no reason recorded")
          s"$name: residual ($why)"
      }
    }
  }

  /** The same over a memoized classification - the exec nodes' entry point (task-21 review:
   * one compilation serves EXPLAIN and the driver-side residual count). */
  def lines(
      partial: Option[PartialVarkaProjection],
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute]): Seq[String] = {
    partial match {
      case Some(classified) => lines(classified, projectList, childOutput)
      case None => Seq("no entry is Varka-eligible")
    }
  }

  /** The same, compiling the projection first - the plan-side entry point. */
  def lines(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Seq[String] = {
    lines(VarkaExpressionCompiler.compilePartial(projectList, childOutput, options),
      projectList, childOutput)
  }

  /**
   * The filter counterpart: one line per conjunct of the predicate's `AND` spine -
   * fused into the mask kernel, or residual with the compiler's reason. On a Varka filter
   * node every line reads "fused" by construction (the rule keeps residual conjuncts in a row
   * `FilterExec` above); the mixed rendering exists for logs and for reporting the original,
   * unsplit condition.
   */
  def predicateLines(
      condition: Expression,
      childOutput: Seq[Attribute],
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Seq[String] = {
    VarkaExpressionCompiler.compilePredicate(condition, childOutput, options) match {
      case Some(predicate) =>
        predicate.specs.map { spec =>
          if (spec.fused) {
            s"${render(spec.conjunct)}: fused"
          } else {
            val why = spec.decline.map(_.toString).getOrElse("no reason recorded")
            s"${render(spec.conjunct)}: residual ($why)"
          }
        }
      case None => Seq("no conjunct is Varka-eligible")
    }
  }

  /** A conjunct in the query's own words, capped like `DeclineSink`'s renderings. */
  private def render(conjunct: Expression): String = {
    val text = conjunct.sql
    if (text.length > 80) text.take(77) + "..." else text
  }
}
