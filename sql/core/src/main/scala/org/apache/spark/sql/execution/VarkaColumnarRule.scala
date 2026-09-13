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

import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.vectorized.ArrowColumnVector

/**
 * Varka plan-level fusion (Task 6). When `spark.sql.codegen.varka.enabled` is set, a
 * Varka-eligible projection sitting above a columnar source runs the SIMD kernels over the Arrow
 * `DateDayVector` buffers (or an `IntVector` for a day-offset column, since task 38) instead of
 * per-row codegen. A dual-mode source that currently feeds
 * rows is switched to its columnar output; projections that are not eligible are left untouched.
 * Since task 12 eligibility is partial: a projection is eligible when at least one entry
 * compiles to the vector IR, with bare columns forwarded zero-copy and the remaining entries
 * evaluated per row alongside the kernels (see `VarkaKernelEvaluator`).
 *
 * Task 21 extends the same two-stage rewrite to filters, the engine's first plan-shape change:
 * an eligible predicate becomes a [[VarkaFilterExec]] (columnar out, compacting the selected
 * rows) or, fused with its to-row transition, a [[VarkaFilterColumnarToRowExec]] (which
 * consumes the selection bitmap at the row boundary, no compaction). Predicate eligibility is
 * per conjunct: the compilable conjuncts of the `AND` spine fuse into the mask kernel and the
 * rest stay in a row `FilterExec` above the Varka node - see [[rewriteFilter]].
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
        case filter @ FilterExec(condition, child)
            if child.supportsColumnar && arrowFriendly(child) =>
          rewriteFilter(condition, child, VarkaFilterExec(_, _)).getOrElse(filter)
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
        case ColumnarToRowExec(varka: VarkaFilterExec) =>
          VarkaFilterColumnarToRowExec(varka.condition, varka.child)
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
        // Task 78: a projection that only narrows a Varka filter's columns, absorbed into the
        // filter node rather than left above it. This runs after the arm above rather than
        // instead of it, so a projection with anything to fuse still becomes a Varka
        // projection node; what reaches here fuses nothing, because forwarding a column is
        // not fusing it. See `isForwardedNarrowing` for why the shape is worth a case at all.
        case ProjectExec(projectList, filter: VarkaFilterColumnarToRowExec)
            if filter.narrowing.isEmpty && isForwardedNarrowing(projectList, filter.output) =>
          filter.copy(narrowing = Some(projectList))
        case filter @ FilterExec(condition, child) =>
          // A filter the pre stage did not see, sitting over a to-row transition it should
          // absorb, never wrap (the columnar-transition wiring lesson). This also revisits
          // the residual filter the pre stage itself left above a Varka filter - harmlessly:
          // its child is by then the row-out Varka node, which is not columnar.
          val columnarChild = child match {
            case ColumnarToRowExec(inner) => inner
            case other => other
          }
          if (columnarChild.supportsColumnar && arrowFriendly(columnarChild)) {
            rewriteFilter(condition, columnarChild, VarkaFilterColumnarToRowExec(_, _))
              .getOrElse(filter)
          } else {
            filter
          }
      }
    } else {
      plan
    }
  }

  // The compiler is the single eligibility oracle: a projection is fused exactly when
  // at least one entry compiles to the vector IR - nested chains, shared subtrees and
  // predication included - with bare columns forwarded and the rest evaluated per row.
  private def isVarkaEligible(
      projectList: Seq[NamedExpression], childOutput: Seq[Attribute]): Boolean = {
    VarkaExpressionCompiler.compilePartial(projectList, childOutput).isDefined
  }

  /**
   * Whether `projectList` only forwards and narrows `childOutput`: every entry is a column of
   * the child, or a rename of one, and nothing is computed.
   *
   * This is task 78's plan-time signal, and the shape it selects is narrower than its name
   * suggests. Spark's own column pruning has already run: for a one-column predicate the
   * pruned child output is that column, the projection above it is redundant, and the
   * optimizer removed it long before this rule saw the plan. What survives to here is a
   * predicate that reads more columns than its consumer wants - `SELECT d FROM t WHERE
   * d < d2` and nothing else - which is why absorbing it moves only the shapes that were
   * losing and leaves every shape that already fused byte for byte as it was.
   *
   * `false` for an entry that computes anything, because such an entry belongs to the
   * eligibility test above: it may fuse, and a fused entry wants a projection node with a
   * kernel, not a wider `UnsafeProjection` in the filter.
   */
  private def isForwardedNarrowing(
      projectList: Seq[NamedExpression], childOutput: Seq[Attribute]): Boolean = {
    val childIds = childOutput.map(_.exprId).toSet
    projectList.nonEmpty && projectList.forall {
      case a: Attribute => childIds.contains(a.exprId)
      case Alias(a: Attribute, _) => childIds.contains(a.exprId)
      case _ => false
    }
  }

  /**
   * Whether the child can actually feed the mask kernel Arrow batches, as far as the plan can
   * say (task-21 review, second pass): `supportsColumnar` alone is satisfied by Parquet/ORC
   * vectorized scans whose OnHeap/OffHeap batches fail `canRun` on every batch - rewriting a
   * filter there pays per-row fallback at WHERE-clause frequency and splits whole-stage
   * codegen for nothing, strictly slower than the FilterExec it replaced. `vectorTypes` is
   * the plan-time signal: a child declaring only Arrow vectors (or a Varka node, whose fused
   * columns are Arrow) qualifies; a child declaring non-Arrow vectors does not; a child
   * declaring nothing keeps the optimistic task-6 default and the per-batch guard decides at
   * run time. The projection rewrites keep the optimistic proxy on purpose: their fallback is
   * the same per-row projection the stock plan runs, where a filter's columnar fallback
   * re-materialises every column.
   */
  private def arrowFriendly(child: SparkPlan): Boolean = child match {
    case _: VarkaProjectExec | _: VarkaFilterExec => true
    case _ => child.vectorTypes.forall(_.forall { t =>
      t == classOf[ArrowColumnVector].getName || t == classOf[VarkaOwnedArrowColumnVector].getName
    })
  }

  /**
   * The filter rewrite under the task-21 conjunct split, or None when no conjunct fuses: the
   * Varka node (built by `mkVarka`) carries exactly the fused conjuncts, and the residual
   * conjuncts - the ones the compiler declined, reasons in the debug log and the Varka node's
   * EXPLAIN - stay in a row [[FilterExec]] above it, which sees only the rows the mask kernel
   * let through. Both folds keep query order.
   */
  private def rewriteFilter(
      condition: Expression,
      child: SparkPlan,
      mkVarka: (Expression, SparkPlan) => SparkPlan): Option[SparkPlan] = {
    VarkaExpressionCompiler.compilePredicate(condition, child.output).map { predicate =>
      val varka = mkVarka(predicate.fusedConjuncts.reduceLeft(And(_, _)), child)
      predicate.residualConjuncts.reduceLeftOption(And(_, _))
        .map(residual => FilterExec(residual, varka))
        .getOrElse(varka)
    }
  }
}
