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

import java.io.File

import scala.jdk.CollectionConverters._

import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Expression, In, InSet, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.types.{DateType, DayTimeIntervalType, IntegerType, LongType, TimeType, YearMonthIntervalType}

/**
 * The shapes the width audit (VARKA-153) asks C2 about: every row of the coverage table as the
 * compiler lowers it, and a set of hand-built constructions that no row reaches but a kernel
 * can - the checked long arithmetic, the range guard on its own, the two lowerings of the
 * 64-bit constant division. Shared by [[VarkaWidthAuditProbe]], which emits and runs them in a
 * forked JVM, and [[VarkaWidthAuditSuite]], which reads what C2 said about each and pins it, so
 * that the two cannot disagree about what was audited.
 *
 * Every shape carries the name the committed census keys it by. A coverage row is keyed by its
 * SQL, so the coverage table can look its own rows up; a construction is keyed by a sentence.
 */
object VarkaWidthAuditShapes {

  /** One kernel to audit: its roots, their input and literal counts, and its emit options. */
  case class Shape(
      name: String,
      roots: Seq[VarkaVectorIR],
      numInputs: Int,
      numLiterals: Int,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS) {
    /** The lane the kernel runs at: a narrowing root is an int computed in the long lane. */
    def lane: LaneType = VarkaVectorIR.emissionLane(roots.head)
  }

  // The coverage table's columns, as `VarkaCoverageSuite` declares them.
  private val d = AttributeReference("d", DateType)()
  private val d2 = AttributeReference("d2", DateType)()
  private val i = AttributeReference("i", IntegerType)()
  private val ymm = AttributeReference("ymm",
    YearMonthIntervalType(YearMonthIntervalType.MONTH, YearMonthIntervalType.MONTH))()
  private val ymy = AttributeReference("ymy",
    YearMonthIntervalType(YearMonthIntervalType.YEAR, YearMonthIntervalType.YEAR))()
  private val ym = AttributeReference("ym",
    YearMonthIntervalType(YearMonthIntervalType.YEAR, YearMonthIntervalType.MONTH))()
  private val l = AttributeReference("l", LongType)()
  private val l2 = AttributeReference("l2", LongType)()
  private val t = AttributeReference("t", TimeType(6))()
  private val t2 = AttributeReference("t2", TimeType(6))()
  private val dt = AttributeReference("dt", DayTimeIntervalType())()
  private val dt2 = AttributeReference("dt2", DayTimeIntervalType())()
  private val columns: Seq[Attribute] = Seq(d, d2, i, ymm, ymy, ym, l, l2, t, t2, dt, dt2)

  /** The row as the optimizer would hand it to the compiler; `VarkaEmittedBytesSuite`'s rule. */
  private def asCatalystClaims(expr: Expression, catalyst: Set[String]): Expression = expr match {
    case In(value, list) if catalyst.contains("InSet") && list.forall(_.isInstanceOf[Literal]) =>
      InSet(value, list.map(_.asInstanceOf[Literal].value).toSet)
    case other => other
  }

  /**
   * Every row of `coverage.json` as a kernel, through the compiler, keyed by the row's SQL. A
   * row that does not fuse is an error here rather than a skip: `VarkaCoverageSuite` asserts
   * that every row fuses, so a row missing from the audit would be a row the table claims and
   * this file silently stopped asking about.
   */
  def coverageRows(coverageJson: File): Seq[Shape] = {
    val doc = new ObjectMapper().readTree(coverageJson)
    doc.get("expressions").elements().asScala.toSeq.map { e =>
      val sql = e.get("sql").asText()
      val executable = Option(e.get("executable")).map(_.asText()).getOrElse(sql)
      val form = e.get("form").asText()
      val catalyst = Option(e.get("catalyst"))
        .map(_.elements().asScala.map(_.asText()).toSet).getOrElse(Set.empty[String])
      val resolved = asCatalystClaims(
        VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(executable), columns), catalyst)
      val compiled =
        if (form == "predicate") {
          VarkaExpressionCompiler.compilePredicate(resolved, columns).map(_.fused)
        } else {
          VarkaExpressionCompiler.compile(Seq(Alias(resolved, "c")()), columns)
        }
      val c = compiled.getOrElse(
        throw new IllegalStateException(
          s"coverage row does not fuse, so it cannot be audited: $sql"))
      Shape(sql, c.outputs.asScala.toSeq, c.inputOrdinals.size, c.numLiterals)
    }
  }

  /**
   * The constructions the rows do not reach. Each is one thing the emitter builds from a
   * compare and a mask, or one lowering of a division, so a refusal names a construction and
   * not a query. The int-lane twins are the control: the same construction at the lane whose
   * 128-bit species has four lanes rather than two.
   */
  def constructions: Seq[Shape] = {
    val lc = new ColumnRef(0, LaneType.LONG)
    val lc2 = new ColumnRef(1, LaneType.LONG)
    val ic = new ColumnRef(0, LaneType.INT)
    val ic2 = new ColumnRef(1, LaneType.INT)
    def arith(op: IntOp, mode: Overflow, a: VarkaVectorIR, b: VarkaVectorIR) =
      new IntArith(op, mode, a, b)
    val nanosPerHour = 3600000000000L
    Seq(
      Shape("construction: l + l2, wrapping", Seq(arith(IntOp.ADD, Overflow.WRAP, lc, lc2)), 2, 0),
      Shape("construction: l + l2, ANSI checked", Seq(arith(IntOp.ADD, Overflow.FAIL, lc, lc2)),
        2, 0),
      Shape("construction: try_add(l, l2)", Seq(arith(IntOp.ADD, Overflow.NULL, lc, lc2)), 2, 0),
      Shape("construction: -l, ANSI checked", Seq(new IntNeg(Overflow.FAIL, lc)), 1, 0),
      Shape("construction: a range guard over l",
        Seq(new GuardedRange(lc, -(1L << 46), 1L << 46)), 1, 0),
      Shape("construction: l / 3600000000000, conversion form",
        Seq(new ConstDivide(lc, nanosPerHour, ConstDivide.EXACT_DIVIDEND_BOUND)), 1, 0),
      Shape("construction: l / 3600000000000, magic form (useAVX = 2)",
        Seq(new ConstDivide(lc, nanosPerHour, ConstDivide.EXACT_DIVIDEND_BOUND)), 1, 0,
        VarkaEmitOptions.DEFAULTS.withUseAVX(2)),
      Shape("construction: l < l2, as a selection", Seq(new Compare(CompareOp.LT, lc, lc2)), 2, 0),
      Shape("construction: CASE WHEN l < l2 THEN l ELSE l2 END",
        Seq(new IfElse(new Compare(CompareOp.LT, lc, lc2), lc, lc2)), 2, 0),
      Shape("construction: NOT (l < l2) OR l2 IS NOT NULL, as a selection",
        Seq(new Or(new Not(new Compare(CompareOp.LT, lc, lc2)), new IsNotNull(lc2))), 2, 0),
      Shape("construction: greatest(l, l2)", Seq(new Greatest(lc, lc2)), 2, 0),
      Shape("construction: i + i2, ANSI checked (int lane)",
        Seq(arith(IntOp.ADD, Overflow.FAIL, ic, ic2)), 2, 0),
      Shape("construction: i < i2, as a selection (int lane)",
        Seq(new Compare(CompareOp.LT, ic, ic2)), 2, 0),
      Shape("construction: CASE WHEN i < i2 THEN i ELSE i2 END (int lane)",
        Seq(new IfElse(new Compare(CompareOp.LT, ic, ic2), ic, ic2)), 2, 0),
      Shape("construction: i / 3600, conversion form (int lane)",
        Seq(new ConstDivide(ic, 3600)), 1, 0))
  }

  def all(coverageJson: File): Seq[Shape] = coverageRows(coverageJson) ++ constructions
}
