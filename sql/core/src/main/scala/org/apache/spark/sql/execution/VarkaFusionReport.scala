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

import org.apache.spark.sql.catalyst.expressions.{Attribute, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{ForwardedOutput, FusedOutput, PartialVarkaProjection, ResidualOutput, VarkaExpressionCompiler}

/**
 * How a Varka node serves each entry of its projection, in words (milestone 2, task 16).
 *
 * Partial eligibility (task 12) means a fused node can still evaluate entries per row, and until
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

  /** The same, compiling the projection first - the plan-side entry point. */
  def lines(projectList: Seq[NamedExpression], childOutput: Seq[Attribute]): Seq[String] = {
    VarkaExpressionCompiler.compilePartial(projectList, childOutput) match {
      case Some(partial) => lines(partial, projectList, childOutput)
      case None => Seq("no entry is Varka-eligible")
    }
  }
}
