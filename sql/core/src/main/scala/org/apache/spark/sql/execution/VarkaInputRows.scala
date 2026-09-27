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
  UnsafeProjection, UnsafeRow}

/**
 * Reads the input columns a row-by-row evaluation references, once per row, into an
 * `UnsafeRow`, so that the evaluation reads them from there rather than from the batch.
 *
 * A row of a columnar batch is a view: every read goes through the column vector and, for an
 * Arrow batch, the vector's accessor. Subexpression elimination leaves plain column references
 * alone, so a projection that mentions a column thirty times reads it thirty times through that
 * chain. Copying the referenced columns first costs one read each, and the evaluation's reads
 * become `UnsafeRow` reads - what vanilla Spark's row-at-a-time path over a cached table does,
 * whose cache reader writes each row into an `UnsafeRow` before any operator sees it. Only the
 * referenced columns are copied, in the child's order.
 *
 * Bind the evaluation to [[attributes]], not to the child's output, and apply it to what
 * [[apply]] returns. The returned row is reused: it is rewritten by the next call.
 */
private[execution] class VarkaInputRows(
    expressions: Seq[Expression],
    childOutput: Seq[Attribute]) {

  /** The referenced columns, in the child's order: the schema of the rows [[apply]] returns. */
  val attributes: Seq[Attribute] = {
    val referenced = AttributeSet(expressions.flatMap(_.references))
    childOutput.filter(referenced.contains)
  }

  private val copy = UnsafeProjection.create(attributes, childOutput)

  def apply(row: InternalRow): UnsafeRow = copy(row)
}
