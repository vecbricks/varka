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

import java.time.{Duration, LocalDate}

import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaIntervalCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DateType, DayTimeIntervalType, StructField, StructType}

/**
 * The day-time interval sum, difference, negation and `abs` against the row engine (VARKA-103
 * 2.1, 3.3). Spark computes the first three with `IntervalMathUtils.addExact`, `subtractExact`
 * and `negateExact` in every mode, raising `INTERVAL_ARITHMETIC_OVERFLOW`, and `abs` with
 * `LongExactNumeric.abs`, raising `ARITHMETIC_OVERFLOW` at `Long.MIN_VALUE`. The kernel checks
 * each on the long lane and declines a batch with an overflowing row, which the row engine then
 * raises on, so each named cell runs both engines and compares what each did: the answer, or the
 * error with its condition and message.
 */
class VarkaDayTimeArithmeticSuite extends QueryTest with VarkaMaybeThrows with VarkaTestWatchdog {

  private val schema = StructType(Seq(
    StructField("dt", DayTimeIntervalType()), StructField("dt2", DayTimeIntervalType())))

  private def micros(n: Long): Duration =
    Duration.ofSeconds(Math.floorDiv(n, 1000000L), Math.floorMod(n, 1000000L) * 1000L)

  private val day = 86400000000L

  /**
   * Pairs whose sums, differences, negations and magnitudes all fit: both signs, a day each way,
   * the long's ends paired so that neither the sum nor the difference leaves it, and a null on
   * either side.
   */
  private val fits: Seq[Row] = {
    val values = (-500 until 500).map(i => i * 7919000003L) ++
      Seq(0L, 1L, -1L, day, -day, day - 1, 1 - day)
    values.zipWithIndex.map { case (v, i) => Row(micros(v), micros(values(i % 17) / 3)) } ++ Seq(
      Row(micros(Long.MaxValue), micros(0)), Row(micros(Long.MaxValue - 1), micros(1)),
      Row(micros(Long.MinValue + 1), micros(-1)), Row(micros(Long.MinValue + 1), micros(0)),
      Row(null, micros(3)), Row(micros(3), null))
  }

  /** A table of `fits` with one row more: `dt` and `dt2` at `a` and `b`. */
  private val tables: Map[String, Seq[Row]] = Map(
    "varka_dta_fits" -> fits,
    // MAX + 1, the first sum past the long; MAX - 1 + 1 = MAX fits, and is in `fits`.
    "varka_dta_sum_overflows" -> (fits :+ Row(micros(Long.MaxValue), micros(1))),
    // MIN - 1, the first difference past the long; MIN + 1 - 1 fits.
    "varka_dta_difference_overflows" -> (fits :+ Row(micros(Long.MinValue), micros(1))),
    // MIN, the one value whose negation and magnitude overflow.
    "varka_dta_min" -> (fits :+ Row(micros(Long.MinValue), micros(0))),
    // The quotient cells of 3.3, every one inside the long, and those inside the guard of a
    // product by 3, the greatest numerator of the decimal literals below.
    "varka_dta_quotients" -> quotientRows(_ => true),
    "varka_dta_quotients_guarded" -> quotientRows(insideTimesThree),
    // A product by 1.5 past the long, which Spark raises on.
    "varka_dta_quotients_max" -> (quotientRows(insideTimesThree) :+
      Row(micros(Long.MaxValue), micros(0))))

  /** Whether `v * 3` is a long: `math.abs` keeps `Long.MinValue` negative, so it is named. */
  private def insideTimesThree(v: Long): Boolean =
    v != Long.MinValue && math.abs(v) <= Long.MaxValue / 3

  /**
   * The cells `dt / k` and the decimal literal forms run on (VARKA-103 3.3): the long's ends and
   * one in, the ties at both signs (`+-5 / 2`, `+-1`, `+-3`), a day and one either side, `2^53`
   * and its neighbours, and the double factor's long edges, `MAX - 511` and `MAX - 512`, with a
   * null; those `keep` admits.
   */
  private def quotientRows(keep: Long => Boolean): Seq[Row] = {
    val magnitudes = Seq(0L, 1L, 3L, 5L, 7L, day - 1, day, day + 1, (1L << 53) - 1, 1L << 53,
      (1L << 53) + 1, Long.MaxValue - 512, Long.MaxValue - 511, Long.MaxValue - 1, Long.MaxValue)
    ((magnitudes ++ magnitudes.map(-_)) :+ Long.MinValue).filter(keep)
      .map(v => Row(micros(v), micros(0))) :+ Row(null, micros(0))
  }

  /**
   * The product cells of `VARKA-103.md` 3.3: for each factor, the operand at the guard's ends
   * and one past each, beside a value well inside, so a table per cell answers or raises on its
   * one edge row alone.
   */
  private val productFactors = Seq(2L, -2L, Long.MinValue)

  private def productCells: Seq[(Long, Long, Boolean)] = productFactors.flatMap { k =>
    val Array(lo, hi) = VarkaIntervalCompiler.dayTimeProductBounds(k)
    Seq((k, lo, false), (k, hi, false)) ++
      (if (lo > Long.MinValue) Seq((k, lo - 1, true)) else Nil) ++
      (if (hi < Long.MaxValue) Seq((k, hi + 1, true)) else Nil)
  }

  private def productTable(k: Long, x: Long): String =
    s"varka_dta_product_${if (k < 0) "m" else ""}${math.abs(k % 1000)}_" +
      s"${if (x < 0) "m" else ""}${math.abs(x % 1000000007L)}"

  private def cache(session: SparkSession): Unit = {
    for ((k, x, _) <- productCells) {
      session.createDataFrame(session.sparkContext.parallelize(
        Seq(Row(micros(x), micros(0)), Row(micros(1), micros(0)), Row(null, micros(0))), 1),
        schema).createOrReplaceTempView(productTable(k, x))
      session.catalog.cacheTable(productTable(k, x))
    }
    for ((name, data) <- tables) {
      session.createDataFrame(session.sparkContext.parallelize(data, 1), schema)
        .createOrReplaceTempView(name)
      session.catalog.cacheTable(name)
    }
    // A date beside a stored INTERVAL DAY, for the day count as a date offset.
    val dates = (-300 until 300).map { i =>
      Row(LocalDate.of(2021, 6, 1).plusDays(i * 7L), Duration.ofDays(i * 13L - 1000))
    } ++ Seq(Row(null, Duration.ofDays(2)), Row(LocalDate.of(2021, 6, 1), null))
    session.createDataFrame(session.sparkContext.parallelize(dates, 1), StructType(Seq(
      StructField("d", DateType), StructField("dd", DayTimeIntervalType(0: Byte, 0: Byte)))))
      .createOrReplaceTempView("varka_dta_dates")
    session.catalog.cacheTable("varka_dta_dates")
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cache(spark)
    cache(varkaSpark)
  }

  /**
   * Both engines on `query` over `table`: they answer the same rows or raise the same error, and
   * the kernel is in the plan either way. When the answer is rows the kernel ran; a raise is the
   * row engine's, after the kernel declined the batch. Returns the row engine's outcome.
   */
  private def checkCell(
      query: String,
      table: String,
      raises: Boolean,
      declines: Boolean = false): VarkaMaybeThrows.Outcome = {
    val sql = query.replace("TABLE", table)
    val (onVarka, plan) = runOutcome(varkaSpark, sql)
    val onSpark = expectedOutcome(sql, plan)
    VarkaMaybeThrows.difference(onSpark, onVarka).foreach(d => fail(s"$sql\n$d"))
    assert(onSpark.isInstanceOf[VarkaMaybeThrows.Raised] === raises, s"$sql: $onSpark")
    assertFused(plan.get)
    if (!raises && !declines) assertKernelsRan(plan.get)
    onSpark
  }

  /**
   * [[checkCell]], and the row engine's outcome again with whole-stage code generation off and
   * the interpreted projections, which must be the same: Spark's two evaluations of the
   * expression agree on the cell, and Varka agrees with both.
   */
  private def checkBothRowPaths(
      query: String,
      table: String,
      raises: Boolean,
      declines: Boolean = false): Unit = {
    val onSpark = checkCell(query, table, raises, declines)
    val sql = query.replace("TABLE", table)
    withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false",
        SQLConf.CODEGEN_FACTORY_MODE.key -> "NO_CODEGEN") {
      VarkaMaybeThrows.difference(onSpark, outcome(spark, sql)).foreach(d =>
        fail(s"$sql, interpreted against generated\n$d"))
    }
  }

  private def condition(o: VarkaMaybeThrows.Outcome): String = o match {
    case r: VarkaMaybeThrows.Raised => r.condition
    case other => fail(s"expected a raise, got $other")
  }

  test("dt + dt2, dt - dt2, -dt and abs(dt) agree with the row engine where nothing overflows") {
    // The long's ends that fit among them: MAX + 0, MAX - 1 + 1, MIN + 1 - 1, -(MIN + 1) and
    // abs(MIN + 1).
    checkCell("SELECT dt + dt2 AS a, dt - dt2 AS b, -dt AS c, abs(dt) AS d, " +
      "abs(dt - dt2) AS e FROM TABLE", "varka_dta_fits", raises = false)
  }

  test("an overflowing sum or difference raises INTERVAL_ARITHMETIC_OVERFLOW from the row " +
      "engine, after the kernel declines its batch") {
    assert(condition(checkCell("SELECT dt + dt2 AS a FROM TABLE", "varka_dta_sum_overflows",
      raises = true)) === "INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION")
    assert(condition(checkCell("SELECT dt - dt2 AS a FROM TABLE",
      "varka_dta_difference_overflows", raises = true)) ===
      "INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION")
  }

  test("-dt at Long.MIN_VALUE raises the interval condition, and abs(dt) ARITHMETIC_OVERFLOW") {
    assert(condition(checkCell("SELECT -dt AS a FROM TABLE", "varka_dta_min", raises = true)) ===
      "INTERVAL_ARITHMETIC_OVERFLOW.WITHOUT_SUGGESTION")
    assert(condition(checkCell("SELECT abs(dt) AS a FROM TABLE", "varka_dta_min",
      raises = true)) === "ARITHMETIC_OVERFLOW")
  }

  test("a stored INTERVAL DAY's day count fuses as an offset in d - dd and not in d + dd") {
    // `d - dd` is DateAdd(d, -extract(DAY FROM dd)): the negated count reaches the date arm's
    // offset arithmetic as `-i` does and fuses under its day guard, while `d + dd` declines.
    // ANSI is pinned, as the cell the split moved to 103a3 is, so the composition runs under
    // one mode whatever the matrix configuration.
    withAnsi(true) {
      for ((q, fuses) <- Seq("d - dd" -> true, "d + dd" -> false)) {
        val sql = s"SELECT $q AS a FROM varka_dta_dates"
        val (onVarka, plan) = runOutcome(varkaSpark, sql)
        VarkaMaybeThrows.difference(expectedOutcome(sql, plan), onVarka).foreach(d =>
          fail(s"$sql\n$d"))
        assert(find(plan.get)(isVarkaNode).isDefined === fuses, sql)
      }
    }
  }

  test("dt * k answers at the guard's ends and raises one past each, as multiplyExact does") {
    for ((k, x, raises) <- productCells) {
      withClue(s"dt * $k at $x") {
        // Long.MIN_VALUE has no literal of its own: its digits past the minus sign overflow the
        // long and parse as a decimal, so it is the folded BIGINT difference.
        val f = if (k == Long.MinValue) "(-9223372036854775807L - 1L)" else k.toString
        checkCell(s"SELECT dt * $f AS a, $f * dt AS b, dt * 0 AS c, dt * 1 AS d FROM TABLE",
          productTable(k, x), raises)
      }
    }
  }

  test("dt / k by a divisor of a day and the decimal literal forms answer as the row engine " +
      "does, with code generation on and off") {
    // No guard: a divisor of a day, and the fractions whose reduced numerator is one.
    checkBothRowPaths("SELECT dt / 24 AS a, dt / -2 AS b, dt / 86400000000 AS c, " +
      "dt / CAST(1000 AS SMALLINT) AS d, dt * 0.5 AS e, dt * -0.5 AS f, dt / 2.0 AS g, " +
      "dt * 0.0 AS h FROM TABLE", "varka_dta_quotients", raises = false)
    // A guard on the product by the numerator, 3 for 1.5 and 2 for 1 / 1.5, which no cell of
    // the table leaves.
    checkBothRowPaths("SELECT dt * 1.5 AS a, dt * -1.5 AS b, dt / 1.5 AS c, dt / -1.5 AS d " +
      "FROM TABLE", "varka_dta_quotients_guarded", raises = false)
    // MAX * 1.5 leaves the long: Spark raises a bare ArithmeticException from longValueExact,
    // and the batch the guard declines raises it from the row engine. MAX / 1.5 fits, and the
    // guard's decline leaves it to the row engine too.
    checkBothRowPaths("SELECT dt * 1.5 AS a FROM TABLE", "varka_dta_quotients_max",
      raises = true)
    checkBothRowPaths("SELECT dt / 1.5 AS a FROM TABLE", "varka_dta_quotients_max",
      raises = false, declines = true)
  }
}
