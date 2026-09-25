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

import org.json4s.{DefaultFormats, JString}
import org.json4s.jackson.JsonMethods.parse

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, BindReferences,
  Expression, RuntimeReplaceable}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaSqlResolve, VarkaVectorIR}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser

/**
 * The compiler's family chain is disjoint (task 173, scope item 41).
 *
 * `VarkaExpressionCompiler.compileNode` dispatches a node through a chain of groups - the date
 * leaves, the calendar, interval, time and condition families, then the int arithmetic - and
 * the first group whose arms are defined at the node compiles it. The chain is order-safe only
 * if no node is claimed by two groups; that was an argument in `PLAN_TASK_159.md`, and a fifth
 * family or a widened guard would break it silently, the first group winning. This suite holds
 * it as a fact: every node of every expression of the coverage table, and of a few shapes the
 * compiler suite watches, is asked of each group's `isDefinedAt`, and at most one answers. A
 * node no group claims is the fallback's, which is allowed; two claims is the defect.
 *
 * The chain is read from `VarkaExpressionCompiler.familyChain`, the list `compileNode` itself
 * dispatches through, so a group added there is checked here without this file changing. The
 * last test duplicates a group on purpose and asserts the check catches it, which is what makes
 * a green run mean something.
 */
class VarkaFamilyChainSuite extends SparkFunSuite {

  /** The coverage table's columns and every row's executable SQL, from the committed file. */
  private lazy val (columns: Seq[Attribute], rows: Seq[String]) = {
    implicit val formats: DefaultFormats.type = DefaultFormats
    val json = parse(new String(java.nio.file.Files.readAllBytes(
      getWorkspaceFilePath("sql", "varka", "coverage.json")), "UTF-8"))
    val columns = (json \ "columns").extract[Map[String, String]].toSeq.map { case (name, t) =>
      AttributeReference(name, CatalystSqlParser.parseDataType(t))()
    }
    val rows = (json \ "expressions").children.map { row =>
      (row \ "executable") match {
        case JString(s) if s.nonEmpty => s
        case _ => (row \ "sql").extract[String]
      }
    }
    assert(rows.size > 80, s"${rows.size} coverage rows read")
    (columns, rows)
  }

  /**
   * Shapes beside the table's that sit on a seam between two groups: the one arm the calendar
   * family and the int arithmetic both look at, in both operand orders; the identity date cast
   * the leaves unwrap; and arithmetic over a calendar field.
   */
  private val seams = Seq(
    "weekday(d) + 1", "1 + weekday(d)", "weekday(d) + 2", "cast(d as date)",
    "year(d) + i", "-(month(d))", "greatest(d, d2)", "d + INTERVAL 3 MONTH")

  private def bound(sql: String): Expression = BindReferences.bindReference[Expression](
    VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(sql), columns), columns)

  /** Every node of `e`, and of what a `RuntimeReplaceable` compiles as, since the compiler
   * compiles the replacement. */
  private def nodes(e: Expression): Seq[Expression] = e match {
    case r: RuntimeReplaceable => e +: nodes(r.replacement)
    case _ => e +: e.children.flatMap(nodes)
  }

  private type Chain = Seq[(String, PartialFunction[Expression, Option[VarkaVectorIR]])]

  private def freshChain(): Chain = {
    val inputs = mutable.LinkedHashMap.empty[Int, Int]
    val literals = mutable.LinkedHashMap.empty[Int, Int]
    VarkaExpressionCompiler.familyChain(inputs, literals, new DeclineSink(columns, true))
  }

  /**
   * The nodes of `sqls` claimed by more than one group of `chain`, each with its claimants, and
   * how many nodes each group claimed. Asking `isDefinedAt` runs only the arms' guards, which
   * test the expression class and its data type and build nothing.
   */
  private def claims(chain: Chain, sqls: Seq[String])
      : (Seq[(String, Expression, Seq[String])], Map[String, Int]) = {
    val counts = mutable.LinkedHashMap(chain.map(_._1 -> 0): _*)
    val doubles = Seq.newBuilder[(String, Expression, Seq[String])]
    for (sql <- sqls; node <- nodes(bound(sql))) {
      val claimants = chain.collect { case (name, arms) if arms.isDefinedAt(node) => name }
      claimants.foreach(name => counts(name) += 1)
      if (claimants.size > 1) doubles += ((sql, node, claimants))
    }
    (doubles.result(), counts.toMap)
  }

  test("no node of the coverage table or the seams is claimed by two groups") {
    val (doubles, counts) = claims(freshChain(), rows ++ seams)
    assert(doubles.isEmpty, doubles.map { case (sql, node, who) =>
      s"$node in `$sql` is claimed by ${who.mkString(" and ")}"
    }.mkString("\n"))
    // Every group has to claim something, or the corpus is not exercising the seam it guards.
    val idle = counts.collect { case (name, 0) => name }
    assert(idle.isEmpty, s"no node in the corpus reached: ${idle.mkString(", ")}")
    logInfo(s"family chain claims over ${rows.size + seams.size} expressions: $counts")
  }

  test("the check fails when a group's arms are duplicated on purpose") {
    // A copy of the calendar family appended to the chain claims every calendar node twice.
    // If this test ever passes with the duplicate in place, the check above proves nothing.
    val chain = freshChain()
    val (doubles, _) = claims(chain :+ ("calendar again" -> chain(1)._2), rows)
    assert(doubles.nonEmpty)
    assert(doubles.forall(_._3 == Seq("calendar", "calendar again")), doubles.take(3))
  }
}
