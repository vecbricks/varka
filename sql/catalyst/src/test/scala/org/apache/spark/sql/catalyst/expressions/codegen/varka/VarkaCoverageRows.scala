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

import java.nio.file.{Files, Path}

import org.json4s.{DefaultFormats, JString}
import org.json4s.jackson.JsonMethods.parse

import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, Expression}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser

/**
 * The coverage table (`sql/varka/coverage.json`) as the suites that compose its rows read it:
 * its columns as attributes, and each row's executable SQL with whether it is a predicate. One
 * reader, so the composition fuzzer and the grouping bound draw from the same table.
 */
object VarkaCoverageRows {

  case class Row(executable: String, predicate: Boolean)

  private val booleanColumn = "\\bb2?\\b".r

  /**
   * Whether an executable row reads one of the table's BOOLEAN columns, `b` or `b2`. The fuzzers
   * that draw int columns leave these rows out: a boolean input is a bitmap their batches and
   * oracles do not build.
   */
  def readsBoolean(executable: String): Boolean = booleanColumn.findFirstIn(executable).isDefined

  private val narrowColumn = "\\b(y1|y2|s1|s2)\\b".r

  /**
   * Whether an executable row reads one of the table's narrow columns, `y1` and `y2` (TINYINT) or
   * `s1` and `s2` (SMALLINT, VARKA-329). The fuzzers that draw int and long columns leave these
   * rows out for {@link #readsBoolean}'s reason and one more: a narrow column is one or two bytes
   * a row, which their batches and oracles do not build. Drawing the narrow types is VARKA-352's.
   */
  def readsNarrow(executable: String): Boolean = narrowColumn.findFirstIn(executable).isDefined

  private val decimalColumn = "\\b(d9|d9b|d18|d18b|d9s4|d16s9|d8|d19|d38|d38b|d3810)\\b".r

  /**
   * Whether an executable row reads one of the table's DECIMAL columns, `d9`, `d9b`, `d18`,
   * `d18b`, `d9s4`, `d16s9` (VARKA-347, VARKA-348), `d8` (VARKA-349) or the 128-bit pair's
   * `d19`, `d38`, `d38b` and `d3810` (VARKA-359). The fuzzers that draw
   * int and long columns leave these rows out for {@link #readsNarrow}'s reason: a decimal
   * column is Arrow's sixteen-byte slot a row, which their batches and oracles do not build.
   * Drawing the decimal type is VARKA-352's.
   */
  def readsDecimal(executable: String): Boolean = decimalColumn.findFirstIn(executable).isDefined

  /**
   * A filter the composition fuzzer applies to the coverage rows: `name` says what a row it leaves
   * out reads, `owedBy` the plan row whose work draws such rows (`None` when no row owes it, which
   * the fuzzer's accounting test fails on), and `reads` whether an executable row is left out.
   */
  case class Filter(name: String, owedBy: Option[String], reads: String => Boolean)

  /** The composition fuzzer's filters, in the order a row left out is counted against them. */
  val compositionFilters: Seq[Filter] = Seq(
    Filter("BOOLEAN", Some("VARKA-352"), readsBoolean),
    Filter("TINYINT or SMALLINT", Some("VARKA-352"), readsNarrow),
    Filter("DECIMAL", Some("VARKA-352"), readsDecimal))

  case class Table(columns: Seq[Attribute], projections: Seq[Row], predicates: Seq[Row])

  /** The table at `path`, the committed file the coverage suite keeps. */
  def read(path: Path): Table = {
    implicit val formats: DefaultFormats.type = DefaultFormats
    val json = parse(new String(Files.readAllBytes(path), "UTF-8"))
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
    require(projections.size > 40 && predicates.size > 10, s"${rows.size} rows read from $path")
    Table(columns, projections, predicates)
  }

  /** `sql` bound against the table's columns and analysed, as the planner would hand it on. */
  def resolve(sql: String, columns: Seq[Attribute]): Expression =
    VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(sql), columns)
}
