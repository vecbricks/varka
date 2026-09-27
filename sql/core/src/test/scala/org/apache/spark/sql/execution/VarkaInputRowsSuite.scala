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

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, AttributeReference, Literal,
  NamedExpression, UnsafeProjection}
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * What [[VarkaInputRows]] gives a row-by-row evaluation over a columnar batch: a copy of the
 * referenced columns when some column is read more than once, the batch's own rows otherwise,
 * and either way the results the evaluation would compute over the batch's rows.
 */
class VarkaInputRowsSuite extends SparkFunSuite {

  private val a = AttributeReference("a", IntegerType)()
  private val b = AttributeReference("b", StringType)()
  private val c = AttributeReference("c", IntegerType)()
  private val childOutput = Seq(a, b, c)

  /** `c` read twice, `a` once, `b` not at all. */
  private val rereading: Seq[NamedExpression] =
    Seq(Alias(Add(c, c), "c2")(), Alias(Add(a, Literal(1)), "a1")())

  /** Every column read once, the string among them. */
  private val readingOnce: Seq[NamedExpression] =
    Seq(Alias(Add(a, Literal(1)), "a1")(), b, c)

  /** Three rows over (a, b, c), with a null in every column. */
  private def batch(): ColumnarBatch = {
    val schema = StructType(childOutput.map(x => StructField(x.name, x.dataType)))
    val vectors = OnHeapColumnVector.allocateColumns(3, schema)
    vectors(0).putInt(0, 1); vectors(0).putNull(1); vectors(0).putInt(2, 3)
    Seq("x", "yy").zipWithIndex.foreach { case (s, i) =>
      val bytes = s.getBytes("UTF-8")
      vectors(1).putByteArray(i, bytes, 0, bytes.length)
    }
    vectors(1).putNull(2)
    vectors(2).putInt(0, 10); vectors(2).putInt(1, 20); vectors(2).putNull(2)
    val columns = new ColumnarBatch(vectors.toArray)
    columns.setNumRows(3)
    columns
  }

  test("a column read more than once: only the referenced columns, copied, in the child's order") {
    val rows = new VarkaInputRows(rereading, childOutput)
    assert(rows.copies)
    assert(rows.attributes == Seq(a, c))
    val input = batch()
    try {
      val copied = rows(input.getRow(0))
      assert(copied.numFields == 2 && copied.getInt(0) == 1 && copied.getInt(1) == 10)
      val withNull = rows(input.getRow(2))
      assert(withNull.getInt(0) == 3 && withNull.isNullAt(1))
    } finally {
      input.close()
    }
  }

  test("every column read once: the batch's own rows, and no copy of the string") {
    val rows = new VarkaInputRows(readingOnce, childOutput)
    assert(!rows.copies)
    assert(rows.attributes == childOutput)
    val input = batch()
    try {
      val row = input.getRow(1)
      assert(rows(row) eq row)
    } finally {
      input.close()
    }
  }

  test("a projection bound to the attributes computes what it computes over the batch's rows") {
    for (projectList <- Seq(rereading, readingOnce)) {
      val rows = new VarkaInputRows(projectList, childOutput)
      val direct = UnsafeProjection.create(projectList, childOutput)
      val throughRows = UnsafeProjection.create(projectList, rows.attributes)
      val input = batch()
      try {
        for (i <- 0 until input.numRows()) {
          val expected = direct(input.getRow(i)).copy()
          assert(throughRows(rows(input.getRow(i))) == expected,
            s"row $i of ${projectList.map(_.sql).mkString(", ")}")
        }
      } finally {
        input.close()
      }
    }
  }
}
