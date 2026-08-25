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
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.internal.SQLConf

/**
 * Varka plan-level fusion (Task 6). When `spark.sql.codegen.varka.enabled` is set, a
 * Varka-eligible projection sitting above a columnar source runs the SIMD kernels over the Arrow
 * `DateDayVector` buffers instead of per-row codegen. A dual-mode source that currently feeds
 * rows is switched to its columnar output; projections that are not eligible are left untouched.
 * Since task 12 eligibility is partial: a projection is eligible when at least one entry
 * compiles to the vector IR, with bare columns forwarded zero-copy and the remaining entries
 * evaluated per row alongside the kernels (see `VarkaKernelEvaluator`).
 *
 * The rewrite happens in two stages, on either side of the transition insertion that
 * [[ApplyColumnarRulesAndInsertTransitions]] does between them, because which of the two Varka
 * nodes belongs in the plan depends on what the consumer above the projection wants:
 *
 *  - before transitions, the projection becomes a [[VarkaProjectExec]], which is columnar in and
 *    columnar out. Spark then treats it like any other columnar node: a consumer that takes
 *    batches - a DSv2 write whose connector declares `supportsColumnarWrite` - gets the kernels'
 *    output batches directly, with no transition at all;
 *  - after transitions, a to-row transition that did get inserted above such a node is fused with
 *    it into a [[VarkaColumnarToRowExec]], which runs the same kernels and converts their output
 *    to rows in one node. That is the plan a row consumer got before this two-stage split existed,
 *    unchanged.
 *
 * The post stage also still matches a plain projection over a to-row transition, for a projection
 * the pre stage did not see - another columnar rule may have introduced it, and post rules run in
 * reverse rule order, so this rule sees the plan before rules listed after it in that stage.
 */
object VarkaColumnarRule extends ColumnarRule {

  override def preColumnarTransitions: Rule[SparkPlan] = { plan =>
    if (SQLConf.get.varkaEnabled) {
      plan.transformUp {
        case project @ ProjectExec(projectList, child)
            if isVarkaEligible(projectList, child.output) =>
          if (child.supportsColumnar) {
            VarkaProjectExec(projectList, child)
          } else {
            project
          }
      }
    } else {
      plan
    }
  }

  override def postColumnarTransitions: Rule[SparkPlan] = { plan =>
    if (SQLConf.get.varkaEnabled) {
      plan.transformUp {
        case ColumnarToRowExec(varka: VarkaProjectExec) =>
          VarkaColumnarToRowExec(varka.projectList, varka.child)
        case project @ ProjectExec(projectList, child)
            if isVarkaEligible(projectList, child.output) =>
          val columnarChild = child match {
            case ColumnarToRowExec(inner) => inner
            case other => other
          }
          if (columnarChild.supportsColumnar) {
            VarkaColumnarToRowExec(projectList, columnarChild)
          } else {
            project
          }
      }
    } else {
      plan
    }
  }

  // The compiler is the single eligibility oracle (task 10): a projection is fused exactly when
  // at least one entry compiles to the vector IR (task 12) - nested chains, shared subtrees and
  // predication included - with bare columns forwarded and the rest evaluated per row.
  private def isVarkaEligible(
      projectList: Seq[NamedExpression], childOutput: Seq[Attribute]): Boolean = {
    VarkaExpressionCompiler.compilePartial(projectList, childOutput).isDefined
  }
}
