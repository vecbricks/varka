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

import scala.collection.mutable

import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, BindReferences, BoundReference, DateAdd, DateDiff, DateSub, DateVarkaSupport, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{AddDays, ColumnRef, DateDiff => IRDateDiff, LiteralSlot, SubDays}
import org.apache.spark.sql.types.{DataType, DateType}

/**
 * A whole projection compiled to the Varka vector IR (milestone 2, task 10): the trees
 * `VarkaLoopEmitter` turns into one fused loop, plus everything the evaluator needs to drive the
 * emitted class - which child columns it reads (dense kernel input index = position in
 * `inputOrdinals`), the runtime `scalarArgs` values (slot index = position in `literals`), and
 * each output's Spark type, which is what tells a `datediff` day-count column (`IntegerType`)
 * apart from a date column when the output vectors are allocated.
 */
private[sql] case class CompiledVarkaProjection(
    outputs: Seq[VarkaVectorIR],
    outputTypes: Seq[DataType],
    inputOrdinals: Seq[Int],
    literals: Seq[Int])

/**
 * Compiles a bound projection list to [[CompiledVarkaProjection]], recursing where the MVP's
 * flat matcher demanded bare attributes - `datediff(date_add(d, 7), d2)` compiles where
 * milestone 1 saw nothing. Used by both `VarkaColumnarRule` (is the projection eligible?) and
 * `VarkaKernelEvaluator` (what does the emitted loop compute?), so eligibility cannot drift from
 * execution: there is one compiler and the rule's question is `compile(...).isDefined`.
 *
 * All or nothing, still: one uncompilable entry returns `None` for the whole projection, and the
 * plan stays untouched (partial eligibility is task 12). A bare column as an output entry is
 * deliberately uncompilable too - emitting it would be a copy loop, while task 12 forwards it
 * zero-copy.
 *
 * Literal day offsets fold through [[DateVarkaSupport.foldDaysOffset]] - the same rule the MVP
 * matched on - into slots of the runtime argument table, assigned per distinct '''value''': two
 * occurrences of `date_add(d, 1)` must compile to equal IR records, or the emitter's CSE could
 * not see they are one computation. Slots are numbered in first-occurrence order, so a chain's
 * shape does not depend on what its constants are - the identity milestone 3's cache will key
 * on.
 *
 * The expressions' own `isClassFileGenEligible` and its genCode-time registration are
 * deliberately not consulted and not widened: they feed the Janino compile-cache key and stay as
 * milestone 1 left them (`PLAN_MILESTONE_2.md` section 4).
 */
private[sql] object VarkaExpressionCompiler {

  def compile(
      projectList: Seq[NamedExpression],
      childOutput: Seq[Attribute]): Option[CompiledVarkaProjection] = {
    // Both tables assign dense indices in first-occurrence order, which makes the compiled
    // shape deterministic in the projection alone.
    val inputs = mutable.LinkedHashMap.empty[Int, Int]
    val literals = mutable.LinkedHashMap.empty[Int, Int]
    val outputs = Seq.newBuilder[VarkaVectorIR]
    val outputTypes = Seq.newBuilder[DataType]
    val allCompiled = projectList.forall { named =>
      // Bound at Expression, not NamedExpression: a bare column entry binds to a
      // BoundReference, which is not a NamedExpression, and the cast inside bindReference
      // would throw instead of letting the match below decline it.
      val bound = BindReferences.bindReference[Expression](named, childOutput)
      val inner = bound match {
        case Alias(child, _) => child
        case e => e
      }
      // A bare column is compilable as a node but not as an output: see the class doc.
      if (inner.isInstanceOf[BoundReference]) {
        false
      } else {
        compileNode(inner, inputs, literals) match {
          case Some(ir) =>
            outputs += ir
            outputTypes += inner.dataType
            true
          case None => false
        }
      }
    }
    if (allCompiled && projectList.nonEmpty && inputs.nonEmpty) {
      Some(CompiledVarkaProjection(
        outputs.result(), outputTypes.result(), inputs.keys.toSeq, literals.keys.toSeq))
    } else {
      None
    }
  }

  /**
   * The recursive node compiler. `None` anywhere fails the whole projection, so entries the
   * tables gained on a failing path are simply discarded with everything else. Shapes that
   * cannot be served stay unmatched by construction: an integer `Add` over a `datediff` result
   * is not a date expression (and ANSI overflow cannot throw row-accurately from a lane), and a
   * `date_add` over a `datediff` result only type-checks through a `Cast`, which compiles to
   * nothing here.
   */
  private def compileNode(
      expr: Expression,
      inputs: mutable.LinkedHashMap[Int, Int],
      literals: mutable.LinkedHashMap[Int, Int]): Option[VarkaVectorIR] = expr match {
    case br: BoundReference if br.dataType == DateType =>
      Some(new ColumnRef(inputs.getOrElseUpdate(br.ordinal, inputs.size)))
    case DateAdd(child, days) =>
      for {
        offset <- DateVarkaSupport.foldDaysOffset(days)
        node <- compileNode(child, inputs, literals)
      } yield new AddDays(node, new LiteralSlot(literals.getOrElseUpdate(offset, literals.size)))
    case DateSub(child, days) =>
      for {
        offset <- DateVarkaSupport.foldDaysOffset(days)
        node <- compileNode(child, inputs, literals)
      } yield new SubDays(node, new LiteralSlot(literals.getOrElseUpdate(offset, literals.size)))
    case DateDiff(end, start) =>
      for {
        endNode <- compileNode(end, inputs, literals)
        startNode <- compileNode(start, inputs, literals)
      } yield new IRDateDiff(endNode, startNode)
    case _ => None
  }
}
