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

import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaClassFileGen
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.internal.SQLConf

/**
 * Varka plan-level fusion (Task 6). When `spark.sql.codegen.varka.enabled` is set, rewrites a
 * fully Varka-eligible projection sitting directly above a columnar source into a
 * [[VarkaColumnarToRowExec]] so the projection runs the SIMD kernels over the Arrow
 * `DateDayVector` buffers instead of per-row codegen. A columnar-to-row transition above the
 * source is absorbed (the node consumes the columnar child directly); a dual-mode source that
 * currently feeds rows is switched to its columnar output. Projections that are not fully
 * eligible are left untouched.
 */
object VarkaColumnarRule extends ColumnarRule {

  override def postColumnarTransitions: Rule[SparkPlan] = { plan =>
    if (SQLConf.get.varkaEnabled) {
      plan.transformUp {
        case project @ ProjectExec(projectList, child) if isFullyVarkaEligible(projectList) =>
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

  private def isFullyVarkaEligible(projectList: Seq[Expression]): Boolean = {
    projectList.nonEmpty && VarkaClassFileGen.eligibleOps(projectList).size == projectList.size
  }
}
