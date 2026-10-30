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

import java.time.{Duration, LocalDate, LocalDateTime, ZoneOffset}

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.catalyst.util.DateTimeConstants
import org.apache.spark.sql.execution.VarkaMaybeThrows.{Answered, Raised}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DateType, DayTimeIntervalType, IntegerType, StructField,
  StructType, TimestampNTZType}

/**
 * The casts between `DATE` and `TIMESTAMP_NTZ` (VARKA-338) through a query, against the row
 * engine. Spark converts a date with `multiplyExact(days, MICROS_PER_DAY)` in every mode, so a day
 * past the last one whose midnight is a long raises under LEGACY and ANSI, and `try_cast` catches
 * it and is null; the kernel guards the day and declines that batch alone, and the row engine
 * raises or nulls on the row while the other batches stay on the kernel. The cast back is a floor
 * division that cannot fail, run here at the long range's extremes.
 *
 * And the arithmetic (VARKA-339): Spark computes `t - t2`, `t + dt` and `t - dt` with
 * `subtractExact`, `addExact` and, for the minus, `negateExact` of the interval, in every mode.
 * Each row past the long sits in one batch of three, which the kernel declines, and the row engine
 * raises on it under LEGACY and ANSI alike; the values at the long range's ends that still fit are
 * answered by the kernel with no batch declined.
 */
class VarkaTimestampNtzSuite extends VarkaMaybeThrows with VarkaTestWatchdog {

  private val lastDay = Long.MaxValue / DateTimeConstants.MICROS_PER_DAY

  private def day(n: Long): LocalDate = LocalDate.ofEpochDay(n)

  private def micros(v: Long): LocalDateTime = LocalDateTime.ofEpochSecond(
    Math.floorDiv(v, 1000000L), (Math.floorMod(v, 1000000L) * 1000).toInt, ZoneOffset.UTC)

  private val dateSchema =
    StructType(Seq(StructField("i", IntegerType), StructField("d", DateType)))
  private val ntzSchema =
    StructType(Seq(StructField("i", IntegerType), StructField("t", TimestampNTZType)))

  /** The guard's ends and their neighbours inside it, and nulls. */
  private def insideRows: Seq[Row] =
    Seq(-lastDay, -lastDay + 1, -1L, 0L, 1L, lastDay - 1, lastDay).zipWithIndex.flatMap {
      case (n, i) => Seq(Row(2 * i, day(n)), Row(2 * i + 1, null))
    }

  /** Three batches of two rows; the second holds the day `past`. */
  private def pastRows(past: Long): Seq[Row] =
    Seq(0L, 1L, past, 2L, 3L, 4L).zipWithIndex.map { case (n, i) => Row(i, day(n)) }

  /** The long range's extremes, a day's boundaries, their neighbours, and a null. */
  private def ntzRows: Seq[Row] = {
    val values = Seq(Long.MinValue, Long.MinValue + 1, -1L, 0L, 1L, Long.MaxValue - 1,
      Long.MaxValue, DateTimeConstants.MICROS_PER_DAY, DateTimeConstants.MICROS_PER_DAY - 1,
      -DateTimeConstants.MICROS_PER_DAY, -DateTimeConstants.MICROS_PER_DAY - 1,
      lastDay * DateTimeConstants.MICROS_PER_DAY, -lastDay * DateTimeConstants.MICROS_PER_DAY)
    values.zipWithIndex.map { case (v, i) => Row(i, micros(v)) } :+ Row(values.size, null)
  }

  private def interval(v: Long): Duration =
    Duration.ofSeconds(Math.floorDiv(v, 1000000L), Math.floorMod(v, 1000000L) * 1000)

  private val arithSchema = StructType(Seq(StructField("i", IntegerType),
    StructField("t", TimestampNTZType), StructField("t2", TimestampNTZType),
    StructField("dt", DayTimeIntervalType())))

  private def arithRow(i: Int, t: Long, t2: Long, dt: Long): Row =
    Row(i, micros(t), micros(t2), interval(dt))

  /**
   * Triples at the long range's ends whose difference `t - t2`, sum `t + dt` and difference
   * `t - dt` all fit a long, so no row raises; and nulls in each column.
   */
  private def arithInsideRows: Seq[Row] = Seq(
    arithRow(0, Long.MaxValue, Long.MaxValue, 0L),
    arithRow(1, Long.MinValue, Long.MinValue, 0L),
    arithRow(2, Long.MaxValue - 1, Long.MaxValue, 1L),
    arithRow(3, Long.MinValue + 1, Long.MinValue, 1L),
    arithRow(4, 0L, Long.MinValue + 1, Long.MaxValue),
    arithRow(5, -1L, Long.MaxValue, Long.MinValue + 1),
    Row(6, null, micros(Long.MaxValue), interval(1L)),
    Row(7, micros(Long.MaxValue), null, null))

  /**
   * The forms whose one row past the long is checked, each with the triple it overflows on: the
   * sum past the top and the bottom, the difference of the range's ends both ways round, the
   * difference with an interval past the bottom, and the least interval negated.
   */
  private val arithPast: Seq[(String, String, (Long, Long, Long))] = Seq(
    ("t + dt", "plus_top", (Long.MaxValue, 0L, 1L)),
    ("t + dt", "plus_bottom", (Long.MinValue, 0L, -1L)),
    ("t - t2", "minus_top", (Long.MaxValue, Long.MinValue, 0L)),
    ("t - t2", "minus_bottom", (Long.MinValue, Long.MaxValue, 0L)),
    ("t - dt", "minus_interval", (Long.MinValue, 0L, 1L)),
    ("t - dt", "negated", (0L, 0L, Long.MinValue)))

  /** Three batches of two rows; the second holds `past`, the others small values. */
  private def arithPastRows(past: (Long, Long, Long)): Seq[Row] =
    Seq((1L, 2L, 3L), (4L, 5L, 6L), past, (7L, 8L, 9L), (10L, 11L, 12L), (13L, 14L, 15L))
      .zipWithIndex.map { case ((t, t2, dt), i) => arithRow(i, t, t2, dt) }

  /** Two rows a cached batch, so that one day's batch is one of several. */
  private val batchKey = SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.key

  private def cache(session: SparkSession): Unit = {
    val tables = Seq(("varka_ntz_inside", dateSchema, insideRows),
      ("varka_ntz_past", dateSchema, pastRows(lastDay + 1)),
      ("varka_ntz_past_negative", dateSchema, pastRows(-lastDay - 1)),
      ("varka_ntz_values", ntzSchema, ntzRows),
      ("varka_ntz_arith_inside", arithSchema, arithInsideRows)) ++
      arithPast.map { case (_, name, past) =>
        (s"varka_ntz_arith_$name", arithSchema, arithPastRows(past))
      }
    for ((name, schema, rows) <- tables) {
      session.createDataFrame(session.sparkContext.parallelize(rows, 1), schema)
        .createOrReplaceTempView(name)
      session.catalog.cacheTable(name)
    }
  }

  private var batchesBefore: Seq[Option[String]] = Nil

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    batchesBefore = Seq(spark, varkaSpark).map(_.conf.getOption(batchKey))
    for (session <- Seq(spark, varkaSpark)) {
      session.conf.set(batchKey, "2")
      cache(session)
    }
  }

  override protected def afterAll(): Unit = {
    try {
      Seq(spark, varkaSpark).zip(batchesBefore).foreach {
        case (session, Some(value)) => session.conf.set(batchKey, value)
        case (session, None) => session.conf.unset(batchKey)
      }
    } finally {
      super.afterAll()
    }
  }

  private def varkaNode(plan: SparkPlan): SparkPlan =
    collectFirst(plan) { case v if isVarkaNode(v) => v }.getOrElse(fail(s"no Varka node:\n$plan"))

  /** `query` fused and answered as the row engine answers, with no batch declined. */
  private def checkServed(query: String): Unit = {
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, expectedSql(query, actual))
    assertKernelsRan(plan)
    val declined = varkaNode(plan).metrics("numFallbackBatchesDeclined").value
    assert(declined === 0L, s"$query: $declined batch(es) declined inside the guard")
  }

  test("a date converts to TIMESTAMP_NTZ at the guard's ends, in every mode, on the kernel") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      checkServed("SELECT i, CAST(d AS TIMESTAMP_NTZ) AS t FROM varka_ntz_inside")
      checkServed("SELECT i, try_cast(d AS TIMESTAMP_NTZ) AS t FROM varka_ntz_inside")
    }
  }

  test("a day past the guard raises under LEGACY and ANSI, from the row engine its batch goes to") {
    for (ansi <- Seq(false, true); table <- Seq("varka_ntz_past", "varka_ntz_past_negative")) {
      withAnsi(ansi) {
        val query = s"SELECT i, CAST(d AS TIMESTAMP_NTZ) AS t FROM $table"
        // Fused first, so the error below is the decline handing the batch to the row engine
        // and not a query that never reached the kernel.
        assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
        assert(checkSparkAnswerMaybeThrows(query).isInstanceOf[Raised],
          s"ansi=$ansi: $query: answered where Spark raises")
      }
    }
  }

  test("try_cast nulls the day past the guard, and only its batch leaves the kernel") {
    for (table <- Seq("varka_ntz_past", "varka_ntz_past_negative")) {
      val query = s"SELECT i, try_cast(d AS TIMESTAMP_NTZ) AS t FROM $table"
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      val expected = checkSparkAnswerMaybeThrows(query)
      expected match {
        case Answered(rows) => assert(rows.count(_.isNullAt(1)) === 1, s"$query: $rows")
        case r: Raised => fail(s"$query: raised $r where try_cast nulls")
      }
      // The comparison above ran the query again; this plan's metrics are its own run's.
      actual.collect()
      val node = varkaNode(plan)
      assert(node.metrics("numFallbackBatchesDeclined").value === 1L, query)
      assert(node.metrics("numVarkaBatches").value === 2L, query)
    }
  }

  test("an NTZ value converts to its UTC date at the long range's ends, in every mode") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      checkServed("SELECT i, CAST(t AS DATE) AS d FROM varka_ntz_values")
      checkServed("SELECT i, try_cast(t AS DATE) AS d FROM varka_ntz_values")
    }
    // And the dates are java.time's, not only the row engine's: as epoch days, since a
    // java.sql.Date before 1582 is rebased to the hybrid calendar on its way out.
    val days = varkaSpark.sql("SELECT i, unix_date(CAST(t AS DATE)) AS d FROM varka_ntz_values")
      .collect().filter(!_.isNullAt(1)).map(r => r.getInt(0) -> r.getInt(1).toLong).toMap
    for (Row(i: Int, t: LocalDateTime) <- ntzRows) {
      assert(days(i) === t.toLocalDate.toEpochDay, s"row $i: $t")
    }
  }

  test("TIMESTAMP_NTZ differences and interval sums at the long range's ends, in every mode, " +
    "on the kernel") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      for (form <- Seq("t - t2", "t + dt", "t - dt")) {
        checkServed(s"SELECT i, $form AS v FROM varka_ntz_arith_inside")
      }
    }
  }

  test("a TIMESTAMP_NTZ difference or interval sum past the long raises under LEGACY and ANSI, " +
    "from the row engine its batch goes to") {
    for (ansi <- Seq(false, true); (form, name, _) <- arithPast) withAnsi(ansi) {
      val query = s"SELECT i, $form AS v FROM varka_ntz_arith_$name"
      assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
      assert(checkSparkAnswerMaybeThrows(query).isInstanceOf[Raised],
        s"ansi=$ansi: $query: answered where Spark raises")
    }
  }
}
