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

import scala.util.Try

import org.apache.spark.SparkThrowable
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.types.{ByteType, IntegerType, LongType, ShortType, StructField,
  StructType}

/**
 * `round`, `bround` and `truncate` over the int lane (VARKA-344) against the row engine, end to
 * end, at the cells of `VARKA-344.md` 3.1: `TINYINT`, `SMALLINT` and `INT` at the ties, the ends
 * of ANSI's windows, the extremes of each type and the scales past the type, and a `BIGINT` at
 * a scale of 0. Under LEGACY every form fuses and answers as the row engine does; under ANSI
 * every cell of a form that can raise is run on its own, and Varka's answer or error is the row
 * engine's, a cell Spark raises on reaching it through the declined batch. A `BIGINT` at a
 * negative scale stays on the row engine until the long lane's pre-shift (part 344a3).
 */
// varka-session-lint: allow the cells of the plan's 3.1 through the cached Arrow path, each
// raising cell its own query, so the error is the row engine's after the kernel declines its batch
class VarkaRoundingSuite extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

  private val table = "varka_round_cells"

  private val ints: Seq[java.lang.Integer] = Seq[Int](0, 1, -1, 4, 5, -5, 15, -15, 25, -25,
    35, 149, 150, -150, 250, -250, 1234567895, -1234567895, 2147483640, 2147483644, -2147483644,
    2147483645, -2147483645, 2147483646, Int.MaxValue, Int.MinValue).map(Int.box)

  private val longs: Seq[java.lang.Long] = Seq[Long](0, 5, -5, 1500, -2500,
    5000000000000000000L, Long.MaxValue, Long.MinValue).map(Long.box)

  private val bytes: Seq[java.lang.Byte] =
    Seq[Int](0, 5, -5, 15, -15, 25, 124, -124, 125, -125, 127, -128).map(v => Byte.box(v.toByte))

  private val shorts: Seq[java.lang.Short] = Seq[Int](0, 50, -50, 150, -150, 250, 32749, -32749,
    32750, -32750, 32764, -32765, 32767, -32768).map(v => Short.box(v.toShort))

  /**
   * Each column cycling through its own cells: the first `n` rows hold every cell of every column
   * on a valid lane, and the next `n` the cells again with a null at every third row, a different
   * row per column, so the null lanes run beside valid ones without hiding a cell.
   */
  private def rows: Seq[Row] = {
    val n = Seq(ints, longs, bytes, shorts).map(_.size).max
    (0 until 2 * n).map { k =>
      def at[T <: AnyRef](cells: Seq[T], shift: Int): T =
        if (k >= n && (k + shift) % 3 == 0) null.asInstanceOf[T] else cells(k % cells.size)
      Row(k, at(ints, 0), at(longs, 1), at(bytes, 2), at(shorts, 1))
    }
  }

  private val schema = StructType(Seq(StructField("id", IntegerType),
    StructField("i", IntegerType), StructField("l", LongType), StructField("y", ByteType),
    StructField("s", ShortType)))

  private def cacheTable(session: SparkSession): Unit = {
    session.createDataFrame(spark.sparkContext.parallelize(rows, 1), schema)
      .createOrReplaceTempView(table)
    session.catalog.cacheTable(table)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cacheTable(spark)
    cacheTable(varkaSpark)
  }

  private val forms = Seq(
    "round(i, 0)", "round(i, -1)", "bround(i, -1)", "truncate(i, -1)", "round(i, -2)",
    "bround(i, -2)", "truncate(i, -2)", "round(i, -9)", "bround(i, -9)", "round(i, -10)",
    "round(y, -1)", "bround(y, -1)", "truncate(y, -1)", "round(y, -2)", "round(y, -3)",
    "round(s, -1)", "round(s, -2)", "bround(s, -2)", "truncate(s, -3)", "round(s, -4)",
    "round(s, -5)", "round(l, 0)", "bround(l, 3)", "truncate(y, 2)")

  /** The forms ANSI raises on for some cell, each run cell by cell. */
  private val raising = Set("round(i, -1)", "bround(i, -1)", "round(y, -1)", "bround(y, -1)",
    "round(s, -1)", "round(s, -2)", "bround(s, -2)")

  private def fusesAndAnswers(forms: Seq[String]): Unit = forms.foreach { form =>
    val q = s"SELECT id, $form AS v FROM $table"
    val actual = varkaSpark.sql(q)
    withClue(s"$q: ") {
      assertFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, expectedSql(q, actual))
    }
  }

  test("LEGACY: every form fuses and answers as the row engine over every cell") {
    withAnsi(false)(fusesAndAnswers(forms))
  }

  test("ANSI: a form no cell raises on fuses and answers as the row engine over every cell") {
    withAnsi(true)(fusesAndAnswers(forms.filterNot(raising.contains)))
  }

  test("ANSI: a form that raises answers or raises as the row engine on each cell") {
    withAnsi(true) {
      forms.filter(raising.contains).foreach { form =>
        assertFused(varkaSpark.sql(s"SELECT $form AS v FROM $table").queryExecution.executedPlan)
        rows.indices.foreach { id =>
          val one = s"SELECT $form AS v FROM $table WHERE id = $id"
          def outcome(session: SparkSession): Either[String, Seq[Row]] =
            Try(session.sql(one).collect().toSeq).toEither.left.map {
              case e: SparkThrowable => e.getCondition
              case e => throw e
            }
          withClue(s"$one: ") {
            assert(outcome(varkaSpark) === outcome(spark))
          }
        }
      }
    }
  }

  test("the cells of VARKA-344.md 3.1 answer as the plan states") {
    def one(sql: String): Any = varkaSpark.sql(sql).collect().head.get(0)
    withAnsi(false) {
      assert(one(s"SELECT round(i, -1) FROM $table WHERE i = 2147483645") === -2147483646)
      assert(one(s"SELECT bround(i, -1) FROM $table WHERE i = 2147483645") === 2147483640)
      assert(one(s"SELECT round(y, -1) FROM $table WHERE y = 125") === (-126).toByte)
    }
  }

  test("a bigint at a negative scale stays on the row engine") {
    withAnsi(false) {
      val q = s"SELECT id, round(l, -3) AS v FROM $table"
      val actual = varkaSpark.sql(q)
      assertNotFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, expectedSql(q, actual))
    }
  }
}
