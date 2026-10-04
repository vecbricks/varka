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

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeSet, Expression,
  UnsafeProjection}

/**
 * The input rows of a row-by-row evaluation over a columnar batch: the batch's own rows, or,
 * when the evaluation reads some column more than once, a copy of the columns it references in
 * an `UnsafeRow`.
 *
 * A row of a columnar batch is a view: every read goes through the column vector and, for an
 * Arrow batch, the vector's accessor. Subexpression elimination leaves plain column references
 * alone, so a projection that mentions a column thirty times inlines thirty such reads into its
 * generated methods. The reads are cheap, but they change what C2 makes of those methods:
 * escape analysis then leaves allocations in place - one closure per `add_months`, from the
 * overflow check of Spark's `toIntExact` - that it removes when the same projection reads an
 * `UnsafeRow`, the row vanilla Spark's cache reader hands its operators. Copying the referenced
 * columns first, one read each, gives C2 vanilla's shape; see `VARKA-228.md` 7.
 *
 * A column read once is read once either way, and copying it - a string's bytes, say - would be
 * pure cost, so the copy is made only when some column is referenced more than once.
 *
 * Bind the evaluation to [[attributes]], not to the child's output, and apply it to what
 * [[apply]] returns. A copied row is reused: it is rewritten by the next call.
 */
private[execution] class VarkaInputRows(
    expressions: Seq[Expression],
    childOutput: Seq[Attribute]) {

  /** Whether the rows are copied: some input column is referenced more than once. */
  val copies: Boolean = {
    val references = expressions.flatMap(_.collect { case a: Attribute => a.exprId })
    references.size > references.distinct.size
  }

  /**
   * The schema of the rows [[apply]] returns: the referenced columns in the child's order when
   * the rows are copied, the child's output when they are not.
   */
  val attributes: Seq[Attribute] =
    if (copies) {
      val referenced = AttributeSet(expressions.flatMap(_.references))
      childOutput.filter(referenced.contains)
    } else {
      childOutput
    }

  private val copy: Option[UnsafeProjection] =
    if (copies) Some(UnsafeProjection.create(attributes, childOutput)) else None

  def apply(row: InternalRow): InternalRow = copy match {
    case Some(project) => project(row)
    case None => row
  }
}
