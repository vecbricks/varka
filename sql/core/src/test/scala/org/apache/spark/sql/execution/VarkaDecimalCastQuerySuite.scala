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

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.types.{DataType, DecimalType, StructField, StructType}

/**
 * The 64-bit decimal casts (VARKA-350) to BIGINT, BOOLEAN and between decimals over an
 * Arrow-cached table, Varka against vanilla Spark in LEGACY, ANSI and TRY: the rows both answer,
 * or the error both raise; the narrow integral targets decline, and are only checked to run on
 * the row engine here. The columns hold the named cases of `VARKA-350.md` 3 item 2 for every
 * target: the values a range check through a double gets wrong for INT
 * (`0.999999999999999999` as `DECIMAL(18, 18)`, `2147483647.99999999` as `DECIMAL(18, 8)` and the
 * three first differences of item 0.14, in `f`, `k` and `m`), each target's bounds and one past
 * them, `-0.5`, `-1.5` and `1.5`, Trino's `184467440737095517` at `(18, 0)` into `(18, 2)`, and
 * `9.99` and `9.95` into `(2, 1)` (the rounding carry that overflows) - with a null beside them.
 * Here they feed the BIGINT, BOOLEAN and decimal casts; holding the narrow targets to them is
 * 350a1n's. The division guard's boundaries are the coverage rows' domains.
 *
 * A cast into a decimal is read back through a cast to `BIGINT` at scale 0 and through
 * comparisons at its scale, and as a projection's own output through the decimal store (348b).
 * Every query is also checked to run in a Varka kernel: a projection's entry fuses, a filter is a
 * Varka node.
 */
// varka-session-lint: allow the casts are held to vanilla Spark's answers over the Arrow cache,
// whose read path decides each decimal's backing (VARKA-365), and to its errors
class VarkaDecimalCastQuerySuite extends VarkaMaybeThrows with VarkaTestWatchdog {

  private val view = "varka_decimal_casts"

  /** A column: its name, its type, and the values it holds, a null among them. */
  private case class Column(name: String, dataType: DataType, values: Seq[Any])

  private def dec(s: String): java.math.BigDecimal = new java.math.BigDecimal(s)

  private def decimals(scale: Int, values: String*): Seq[Any] =
    values.map(v => dec(v).setScale(scale)) :+ null

  private val columns = Seq(
    Column("a", DecimalType(18, 18), decimals(18, "0.999999999999999999",
      "-0.999999999999999999", "0.5", "-0.5", "0", "0.000000000000000001")),
    Column("b", DecimalType(18, 8), decimals(8, "2147483647.99999999",
      "-2147483648.99999999", "2147483648", "-2147483649", "2147483646.5", "32767.99999999",
      "-32768.5", "32768", "127.9", "-128.99999999", "128", "1.5", "-1.5",
      "9999999999.99999999", "-9999999999.99999999")),
    Column("c", DecimalType(9, 2), decimals(2, "9999999.99", "-9999999.99", "1.50", "-1.50",
      "0.50", "-0.50", "0", "32767.99", "-32768.99", "32768", "127.99", "-129", "9.99", "9.95",
      "9.94", "-9.95")),
    // 2^52 and 2^53 and their neighbours, read at scale 0, where no division runs.
    Column("d", DecimalType(18, 0), decimals(0, "999999999999999999",
      "-999999999999999999", "184467440737095517", "2147483647", "-2147483648", "2147483648",
      "-2147483649", "4503599627370496", "4503599627370495", "9007199254740991",
      "9007199254740992", "-9007199254740992", "0")),
    Column("e", DecimalType(16, 9), decimals(9, "9999999.999999999", "-9999999.999999999",
      "2147483.5", "0.000000001", "-0.000000001")),
    Column("f", DecimalType(17, 7), decimals(7, "2064370910.9999999", "-2064370910.9999999",
      "2147483647.9999999", "-2147483648.9999999")),
    Column("k", DecimalType(17, 12), decimals(12, "16887.999999999999", "32767.999999999999",
      "-32768.999999999999", "-32769")),
    Column("m", DecimalType(17, 14), decimals(14, "-128.99999999999999",
      "127.99999999999999", "-129", "128")),
    Column("g", DecimalType(10, 0), decimals(0, "2147483647", "-2147483648", "2147483648",
      "-2147483649", "32767", "-32768", "32768", "127", "-128", "128", "-129")),
    Column("h", DecimalType(3, 2), decimals(2, "9.99", "-9.99", "9.95", "9.94", "0.05",
      "-0.05", "1")))

  private val decimalColumns = columns.filter(_.dataType.isInstanceOf[DecimalType]).map(_.name)

  private def cacheView(): Unit = {
    val schema = StructType(columns.map(c => StructField(c.name, c.dataType)))
    val rows = columns.map(_.values.length).max
    val data = (0 until rows).map(r => Row.fromSeq(columns.map(c => c.values.lift(r).orNull)))
    Seq(spark, varkaSpark).foreach { session =>
      session.createDataFrame(session.sparkContext.parallelize(data, 1), schema)
        .createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cacheView()
  }

  override protected def afterAll(): Unit = {
    try {
      Seq(spark, varkaSpark).foreach { session =>
        session.catalog.uncacheTable(view)
      }
    } finally {
      super.afterAll()
    }
  }

  private val modes = Seq("LEGACY" -> "false", "ANSI" -> "true")

  /**
   * Checks `cast AS a` over the view in every mode: both engines alike, and the entry fused.
   * `tryToo` runs it once more as `try_cast` in place of `CAST`.
   */
  private def checkProjection(cast: String, tryToo: Boolean = true): Unit = {
    val forms = modes.map { case (mode, ansi) => (mode, ansi, cast) } ++
      (if (tryToo) Seq(("TRY", "true", cast.replace("CAST(", "try_cast("))) else Nil)
    for ((mode, ansi, sql) <- forms) {
      withAnsi(ansi.toBoolean) {
        val query = s"SELECT $sql AS a FROM $view"
        withClue(s"$mode: $query") {
          checkSparkAnswerMaybeThrows(query)
          val explained = varkaSpark.sql(query).queryExecution
            .explainString(org.apache.spark.sql.execution.ExplainMode.fromString("formatted"))
          assert(explained.contains("a: fused"), explained)
        }
      }
    }
  }

  /** Checks the filter `WHERE condition` in every mode: both engines alike, and a Varka node. */
  private def checkFilter(condition: String): Unit = {
    for ((mode, ansi) <- modes) {
      withAnsi(ansi.toBoolean) {
        val query = s"SELECT * FROM $view WHERE $condition"
        withClue(s"$mode: $query") {
          checkSparkAnswerMaybeThrows(query)
          assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
        }
      }
    }
  }

  test("a decimal cast to BIGINT truncates toward zero in every mode") {
    for (c <- decimalColumns) {
      checkProjection(s"CAST($c AS BIGINT)")
    }
  }

  test("a decimal cast to TINYINT, SMALLINT or INT runs on the row engine, as row 350's next " +
      "part's") {
    // VarkaDecimalCastCompiler.NARROW_REASON, which is package-private to the compiler.
    val reason = "a cast of a decimal to TINYINT, SMALLINT or INT narrows the decimal slots' " +
      "long lane, and is row 350's next part (350a1n)"
    for (target <- Seq("TINYINT", "SMALLINT", "INT"); (mode, ansi) <- modes) {
      withAnsi(ansi.toBoolean) {
        // The BIGINT cast beside it fuses, so the plan has a Varka node whose report names why.
        val query = s"SELECT CAST(c AS $target) AS a, CAST(c AS BIGINT) AS b FROM $view"
        withClue(s"$mode: $query") {
          checkSparkAnswerMaybeThrows(query)
          val explained = varkaSpark.sql(query).queryExecution
            .explainString(org.apache.spark.sql.execution.ExplainMode.fromString("formatted"))
          assert(explained.contains(s"a: residual ($reason"), explained)
          assert(explained.contains("b: fused"), explained)
        }
      }
    }
  }

  test("a decimal cast to BOOLEAN is the unscaled value's test against zero, as a filter") {
    for (c <- decimalColumns) {
      checkFilter(s"CAST($c AS BOOLEAN)")
    }
  }

  test("a decimal cast to a lower scale rounds half up, and overflows into null under LEGACY " +
      "and TRY and an error under ANSI") {
    val casts = Seq(
      "c" -> "DECIMAL(9, 0)", "c" -> "DECIMAL(7, 0)", "c" -> "DECIMAL(2, 0)",
      "h" -> "DECIMAL(2, 1)", "h" -> "DECIMAL(1, 0)", "a" -> "DECIMAL(1, 0)",
      "b" -> "DECIMAL(10, 0)", "b" -> "DECIMAL(18, 0)", "d" -> "DECIMAL(17, 0)",
      "e" -> "DECIMAL(8, 0)", "f" -> "DECIMAL(11, 0)", "k" -> "DECIMAL(5, 0)",
      "m" -> "DECIMAL(3, 0)")
    for ((c, to) <- casts) {
      checkProjection(s"CAST(CAST($c AS $to) AS BIGINT)")
    }
    // At a scale above 0 the BIGINT read hides the fraction, so the comparisons pin it.
    checkFilter("CAST(h AS DECIMAL(2, 1)) = 9.9")
    checkFilter("CAST(h AS DECIMAL(3, 1)) = 10.0")
    checkFilter("CAST(h AS DECIMAL(3, 1)) < -0.1")
    checkFilter("CAST(b AS DECIMAL(17, 2)) > 2147483647.99")
  }

  test("a decimal cast to a higher scale is guarded before its multiply, so no check reads a " +
      "wrapped value: Trino's 184467440737095517 at (18, 0) into (18, 2) is null or raises") {
    val casts = Seq("d" -> "DECIMAL(18, 2)", "d" -> "DECIMAL(18, 1)", "g" -> "DECIMAL(12, 4)",
      "c" -> "DECIMAL(9, 4)", "h" -> "DECIMAL(3, 3)", "h" -> "DECIMAL(4, 3)")
    for ((c, to) <- casts) {
      checkProjection(s"CAST(CAST($c AS $to) AS BIGINT)")
    }
    checkFilter("CAST(d AS DECIMAL(18, 2)) < 0")
    checkFilter("CAST(c AS DECIMAL(9, 4)) > 0")
    checkFilter("CAST(h AS DECIMAL(3, 3)) < 0.5")
  }

  test("a cast into a decimal is a decimal output of its own: rounded, rescaled up or checked at " +
      "one scale, null or an error past the precision") {
    val casts = Seq(
      "c" -> "DECIMAL(9, 0)", "c" -> "DECIMAL(2, 0)", "h" -> "DECIMAL(2, 1)",
      "h" -> "DECIMAL(3, 1)", "b" -> "DECIMAL(17, 2)", "d" -> "DECIMAL(17, 0)",
      "d" -> "DECIMAL(18, 2)", "g" -> "DECIMAL(12, 4)", "c" -> "DECIMAL(9, 4)",
      "h" -> "DECIMAL(3, 3)", "g" -> "DECIMAL(5, 0)", "d" -> "DECIMAL(10, 0)")
    for ((c, to) <- casts) {
      checkProjection(s"CAST($c AS $to)")
    }
  }

  test("a precision change at one scale is the precision check alone") {
    checkProjection("CAST(CAST(g AS DECIMAL(5, 0)) AS BIGINT)")
    checkProjection("CAST(CAST(d AS DECIMAL(10, 0)) AS BIGINT)")
    checkFilter("CAST(b AS DECIMAL(12, 8)) > 0")
  }

  test("with the decimal cast family off, a cast that rounds runs on the row engine while " +
      "coercion's widening rescale still fuses") {
    withBothConf(org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily
        .DECIMAL_CAST.switchKey(), "false") {
      val query = s"SELECT CAST(c AS BIGINT) AS a, c < e AS b FROM $view"
      checkSparkAnswerMaybeThrows(query)
      val explained = varkaSpark.sql(query).queryExecution
        .explainString(org.apache.spark.sql.execution.ExplainMode.fromString("formatted"))
      val off = org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily.DECIMAL_CAST
        .offReason()
      assert(explained.contains(s"a: residual ($off: "), explained)
      assert(explained.contains("b: fused"), explained)
    }
  }
}
