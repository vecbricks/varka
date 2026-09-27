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

import org.apache.spark.sql.catalyst.expressions.{Attribute, MutableProjection, NamedExpression,
  UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.vectorized.{MutableColumnarRow, WritableColumnVector}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * The row path of a Varka node whose output is columnar: each row of an input batch projected
 * into the writable column vectors of an output batch.
 *
 * When every output column has a primitive Java type - the fixed-width types, and the dates,
 * timestamps, times and intervals stored as them - a mutable projection writes each entry straight
 * into its vector, through a [[MutableColumnarRow]] over the output vectors whose `rowId` is the
 * row being written: the pattern Spark's vectorized hash aggregation uses. Any other output takes
 * the conversion instead: each row is projected into an `UnsafeRow`, and [[RowToColumnConverter]]
 * copies it into the vectors. The generated code writes a string or a nested value through the
 * row's `update`, which `MutableColumnarRow` rejects, and a null decimal or calendar interval
 * through its typed setter with a null value rather than through `setNullAt`, so only the
 * primitive types are safe to write directly. The direct write saves the `UnsafeRow` and the
 * converter's pass over it, about a tenth of the row path's time; see `PLAN_TASK_230.md` 2.
 *
 * Either way the projection reads its input through [[VarkaInputRows]]. Both projections are
 * compiled on first use, so a task that never takes the row path compiles neither.
 */
private[execution] class VarkaVectorProjection(
    projectList: Seq[NamedExpression],
    childOutput: Seq[Attribute]) {

  /** The schema of the output batch. */
  val outputSchema: StructType = DataTypeUtils.fromAttributes(projectList.map(_.toAttribute))

  /** Whether the entries are written straight into the vectors. */
  val writesDirectly: Boolean =
    outputSchema.fields.forall(field => CodeGenerator.isPrimitiveType(field.dataType))

  private lazy val inputRows = new VarkaInputRows(projectList, childOutput)
  private lazy val direct = MutableProjection.create(projectList, inputRows.attributes)
  private lazy val unsafe = UnsafeProjection.create(projectList, inputRows.attributes)
  private lazy val converter = new RowToColumnConverter(outputSchema)

  /**
   * Projects every row of `input` into `output`, from position 0, and returns the number of rows
   * written. `output` holds one freshly allocated vector per output column, with room for every
   * row of `input`.
   */
  def project(input: ColumnarBatch, output: Array[WritableColumnVector]): Int = {
    val rows = input.rowIterator()
    var count = 0
    if (writesDirectly) {
      val target = new MutableColumnarRow(output)
      direct.target(target)
      while (rows.hasNext) {
        target.rowId = count
        direct(inputRows(rows.next()))
        count += 1
      }
    } else {
      while (rows.hasNext) {
        converter.convert(unsafe(inputRows(rows.next())), output)
        count += 1
      }
    }
    count
  }
}
