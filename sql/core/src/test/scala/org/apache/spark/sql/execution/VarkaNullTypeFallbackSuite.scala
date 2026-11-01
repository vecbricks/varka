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

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.execution.vectorized.{OffHeapColumnVector, OnHeapColumnVector,
  WritableColumnVector}
import org.apache.spark.sql.types._

/**
 * A `NullType` column through the row fallbacks (VARKA-299). A relation can carry one
 * (`SELECT NULL AS n`), the Arrow cache holds it, and the kernel passes it through; but when a
 * batch is declined, the evaluator re-answers it a row at a time and writes the survivors with
 * Spark's `RowToColumnConverter`, which has no converter for `NullType` and raised
 * `UNSUPPORTED_DATATYPE` where Spark answers. Found by the random differential
 * (`sql/varka/fuzz/spark/void-column-fallback.sql`).
 */
class VarkaNullTypeFallbackSuite extends QueryTest with VarkaSharedSessions
  with VarkaTestWatchdog {

  /**
   * A table with a `NullType` column `n`, an int `i` and a date `d`. The row with `i` = 100000
   * takes `date_add(d, i)` past the kernel's guard, so its batch is declined to the row engine.
   */
  private val fixture =
    """SELECT d, i, n, CAST(l AS BIGINT) AS l FROM VALUES
      |  (DATE'0001-01-15', 100000, NULL, 1),
      |  (DATE'2024-02-29', 3, NULL, 2),
      |  (DATE'2021-06-01', 5, NULL, 3),
      |  (DATE'2023-12-27', 7, NULL, NULL)
      |AS v(d, i, n, l)""".stripMargin

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(fixture).createOrReplaceTempView("fz")
      session.catalog.cacheTable("fz")
    }
  }

  private def same(query: String): Unit = {
    val actual = varkaSpark.sql(query)
    assertFused(actual.queryExecution.executedPlan)
    checkAnswer(actual, expectedSql(query, actual))
  }

  test("the fixture's column is NullType, so these tests mean what they say") {
    assert(spark.table("fz").schema("n").dataType == NullType)
  }

  test("a filter whose batch is declined keeps a NullType column") {
    same("SELECT d, i, n, date_add(d, i) AS c FROM fz WHERE NOT (i = 5)")
  }

  test("a filter that keeps nothing of a declined batch") {
    same("SELECT n, date_add(d, i) AS c FROM fz WHERE i > 100")
  }

  test("a projection of a NullType column beside an output the kernel declines") {
    same("SELECT n, date_add(d, i) AS c, l + 1 AS m FROM fz")
  }

  test("a NullType literal output beside a fused one") {
    same("SELECT NULL AS x, date_add(d, 3) AS c FROM fz")
  }

  test("the converter writes a NullType cell as a null, in both vector kinds") {
    val schema = new StructType().add("a", IntegerType).add("n", NullType)
      .add("arr", ArrayType(NullType))
    for (offHeap <- Seq(false, true)) {
      val vectors: Array[WritableColumnVector] =
        if (offHeap) OffHeapColumnVector.allocateColumns(2, schema).toArray[WritableColumnVector]
        else OnHeapColumnVector.allocateColumns(2, schema).toArray[WritableColumnVector]
      try {
        val converter = VarkaRowToColumn(schema)
        converter.convert(InternalRow(5, null, null), vectors)
        assert(vectors(0).getInt(0) == 5)
        assert(vectors(1).isNullAt(0))
        assert(vectors(2).isNullAt(0))
      } finally {
        vectors.foreach(_.close())
      }
    }
  }
}
