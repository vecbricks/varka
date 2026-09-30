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
