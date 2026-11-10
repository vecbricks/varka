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

import java.time.{Duration, LocalDate, LocalDateTime, LocalTime, Period, ZoneId, ZoneOffset}

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChrono, VarkaRangeAnalysis,
  VarkaShapeCache, VarkaShapeKey, VarkaTestWatchdog}
import org.apache.spark.sql.catalyst.util.{DateTimeConstants, IntervalUtils}
import org.apache.spark.sql.execution.VarkaMaybeThrows.{Answered, Raised}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DateType, DayTimeIntervalType, IntegerType, LongType,
  StringType, StructField, StructType, TimestampNTZType, TimeType, YearMonthIntervalType}

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
 *
 * And the fields (VARKA-340): the calendar fields read the date of the count inside a guard, from
 * 1 March -12799 to 15 August 33134, and a batch holding a live count outside it, the long range's
 * ends among them, goes to the row engine alone; the weekday forms, `hour`, `minute`, `second` and
 * the cast to `TIME` answer every long on the kernel.
 *
 * And `make_timestamp_ntz(d, tm)` with the cast of a `TIME` it serves (VARKA-340): the date's
 * midnight plus the time's microseconds, raising past the long in every mode, so the last day
 * after 04:00:54.775807 raises from the row engine its batch goes to; and the cast's date is one
 * literal a query, the session zone's current date, which a cached kernel reads as a slot.
 * And `timestampadd` and the month forms (VARKA-339): each unit's quantity at its guard's ends
 * runs on the kernel, and one past either end goes to the row engine with its batch alone, which
 * answers or raises `DATETIME_OVERFLOW` as Spark does; the month forms read the date under the
 * fields' guard, and the least month count negated raises.
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

  /** The first microsecond the date guard admits. */
  private val guardLow = VarkaRangeAnalysis.NTZ_MIN_DAYS.toLong * DateTimeConstants.MICROS_PER_DAY
  /** The last microsecond the date guard admits. */
  private val guardHigh =
    (VarkaChrono.NARROW_MAX_DAYS + 1L) * DateTimeConstants.MICROS_PER_DAY - 1

  private def ntzAt(values: Seq[Long]): Seq[Row] =
    values.zipWithIndex.map { case (v, i) => Row(i, micros(v)) }

  /** Three batches of two rows; the second holds `past`. */
  private def pastGuardRows(past: Long): Seq[Row] = ntzAt(Seq(0L, 1L, past, 2L, 3L, 4L))

  private val truncSchema = StructType(ntzSchema.fields :+ StructField("f", StringType))

  /** `values` with a trunc format each, every level the format column reads, and a null. */
  private def truncAt(values: Seq[Long]): Seq[Row] =
    ntzAt(values).zip(Seq("YEAR", "MONTH", "QUARTER", "WEEK", "MM", null)).map {
      case (Row(i, t), f) => Row(i, t, f)
    }

  private def interval(v: Long): Duration = IntervalUtils.microsToDuration(v)

  private val makeSchema = StructType(Seq(StructField("i", IntegerType),
    StructField("d", DateType), StructField("tm", TimeType())))

  /** The last time of day whose count is a long on the last day, 04:00:54.775807. */
  private val lastTime = LocalTime.ofNanoOfDay(14454775807000L)

  /** The last microsecond of a day, the last value of a `TIME(6)`. */
  private val lastMicro = LocalTime.of(23, 59, 59, 999999000)

  /** `(day, time)` pairs, two a batch, and a row of nulls. */
  private def makeAt(rows: Seq[(Long, LocalTime)]): Seq[Row] =
    rows.zipWithIndex.map { case ((n, t), i) => Row(i, day(n), t) } :+ Row(rows.size, null, null)

  /** Three batches of two, the second holding `past`, and a fourth of the null row alone. */
  private def makePastRows(past: (Long, LocalTime)): Seq[Row] = makeAt(Seq((0L, LocalTime.NOON),
    (1L, LocalTime.MIN), past, (2L, lastMicro), (3L, lastTime), (4L, LocalTime.NOON)))

  /**
   * Four batches of two: `t` inside the date guard in the first and the third, past it in the
   * second and the fourth, and `tm` a different time on every row.
   */
  private def pinRows: Seq[Row] = Seq(0L, 1L, guardHigh + 1, guardLow - 1, 2L, 3L, guardHigh + 2,
    guardLow - 2).zipWithIndex.map { case (v, i) =>
      Row(i, micros(v), LocalTime.of(i, i, i, i * 1000))
    }

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

  private val addSchema = StructType(Seq(StructField("i", IntegerType),
    StructField("t", TimestampNTZType), StructField("q", LongType),
    StructField("ym", YearMonthIntervalType())))

  /** The leap day at noon: a month shift from it meets the day-of-month clamp. */
  private val leapNoon = LocalDate.of(2024, 2, 29).toEpochDay * DateTimeConstants.MICROS_PER_DAY +
    DateTimeConstants.MICROS_PER_DAY / 2

  /**
   * Each `timestampadd` unit with the quantities at its guard's ends, which fuse from the epoch
   * (and for the month units from the leap day), and the quantities one past each end, with the
   * count each meets.
   */
  private val addEdges: Seq[(String, Seq[Long], Seq[(Long, Long)])] = Seq(
    ("MICROSECOND", Seq(Long.MaxValue, Long.MinValue),
      Seq((1L, Long.MaxValue), (-1L, Long.MinValue))),
    ("HOUR", Seq(2562047788L, -2562047788L), Seq((0L, 2562047789L), (0L, -2562047789L))),
    ("DAY", Seq(106751991L, -106751991L), Seq((0L, 106751992L), (0L, -106751992L),
      // Past the guard, and still a value: the row engine answers it.
      (-DateTimeConstants.MICROS_PER_DAY, 106751992L))),
    ("WEEK", Seq(15250284L, -15250284L), Seq((0L, 15250285L), (0L, -15250285L))),
    ("MONTH", Seq(24564L, -24576L), Seq((0L, 24565L), (0L, -24577L), (0L, Int.MaxValue + 1L))),
    ("QUARTER", Seq(8188L, -8192L), Seq((0L, 8189L), (0L, -8193L))),
    ("YEAR", Seq(2047L, -2048L), Seq((0L, 2048L), (0L, -2049L), (0L, 178956971L))))

  private val monthUnits = Set("MONTH", "QUARTER", "YEAR")

  /**
   * The inside quantities at the epoch, and at the leap day for a month unit, the leap day moved
   * by one, and a null of each operand.
   */
  private def addInsideRows(unit: String, qs: Seq[Long]): Seq[Row] = {
    val fromLeap = if (monthUnits(unit)) qs.map((leapNoon, _)) else Nil
    (qs.map((0L, _)) ++ fromLeap :+ ((leapNoon, 1L))).zipWithIndex.map {
      case ((t, q), i) => Row(i, micros(t), q, Period.ofMonths(13))
    } ++ Seq(Row(100, null, qs.head, null), Row(101, micros(0L), null, null))
  }

  /** Three batches of two; the second holds `past`, the others small quantities. */
  private def addPastRows(past: (Long, Long)): Seq[Row] =
    Seq((0L, 1L), (0L, 2L), past, (0L, 3L), (0L, 4L), (0L, 5L)).zipWithIndex.map {
      case ((t, q), i) => Row(i, micros(t), q, Period.ofMonths(i))
    }

  /** Bigint quantities cast to INT, two a batch: inside the int range, then two past it, `2^32 + 1`
   * and `-2^32 - 2`, whose low bits are small counts, each beside one inside. */
  private val castRows: Seq[Row] = Seq((0L, 5L), (leapNoon, -7L), (0L, (1L << 32) + 1),
    (leapNoon, 3L), (leapNoon, -(1L << 32) - 2), (0L, 2L))
    .zipWithIndex.map { case ((t, q), i) => Row(i, micros(t), q, null) }

  /** Two rows a cached batch, so that one day's batch is one of several. */
  private val batchKey = SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH.key

  private def cache(session: SparkSession): Unit = {
    val tables = Seq(("varka_ntz_inside", dateSchema, insideRows),
      ("varka_ntz_past", dateSchema, pastRows(lastDay + 1)),
      ("varka_ntz_past_negative", dateSchema, pastRows(-lastDay - 1)),
      ("varka_ntz_values", ntzSchema, ntzRows),
      // The guard's first and last microsecond and their neighbours inside it, with the epoch.
      ("varka_ntz_guard", ntzSchema, ntzAt(Seq(guardLow, guardHigh, 0L, -1L, guardLow + 1,
        guardHigh - 1))),
      ("varka_ntz_trunc", truncSchema, truncAt(Seq(guardLow, guardHigh, 0L, -1L, guardLow + 1,
        guardHigh - 1))),
      ("varka_ntz_trunc_below", truncSchema, truncAt(Seq(0L, 1L, guardLow - 1, 2L, 3L, 4L))),
      ("varka_ntz_below", ntzSchema, pastGuardRows(guardLow - 1)),
      ("varka_ntz_above", ntzSchema, pastGuardRows(guardHigh + 1)),
      ("varka_ntz_arith_inside", arithSchema, arithInsideRows),
      ("varka_ntz_make", makeSchema, makeAt(Seq((lastDay, lastTime), (-lastDay, LocalTime.MIN),
        (-lastDay, lastMicro), (0L, lastMicro), (-1L, LocalTime.NOON)))),
      ("varka_ntz_make_raises", makeSchema, makePastRows((lastDay, lastTime.plusNanos(1000)))),
      ("varka_ntz_make_below", makeSchema, makePastRows((-lastDay - 1, lastMicro))),
      ("varka_ntz_pin", StructType(ntzSchema.fields :+ StructField("tm", TimeType())), pinRows),
      // The month count no negation holds, beside two that fit.
      ("varka_ntz_add_cast", addSchema, castRows),
      ("varka_ntz_add_cast_inside", addSchema, castRows.take(2)),
      // `Int.MaxValue + 1`, whose low bits are `Int.MinValue`: a count every month unit raises on.
      ("varka_ntz_add_cast_least", addSchema, castRows.take(2) :+
        Row(2, micros(0L), Int.MaxValue + 1L, null)),
      ("varka_ntz_ym_least", addSchema, Seq(Int.MinValue, 1, 2).zipWithIndex.map {
        case (m, i) => Row(i, micros(0L), 0L, Period.ofMonths(m))
      })) ++
      addEdges.flatMap { case (unit, inside, past) =>
        (s"varka_ntz_add_${unit}_inside", addSchema, addInsideRows(unit, inside)) +:
          past.zipWithIndex.map { case (p, k) =>
            (s"varka_ntz_add_${unit}_past$k", addSchema, addPastRows(p))
          }
      } ++
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

  /** The calendar fields of `t`, whose date is held to the guard. */
  private val calendarFields = Seq("year(t)", "month(t)", "day(t)", "quarter(t)", "dayofyear(t)",
    "weekofyear(t)", "extract(YEAROFWEEK FROM t)", "last_day(t)", "trunc(t, 'YEAR')",
    "trunc(t, 'MONTH')", "trunc(t, 'QUARTER')")

  /** The fields that answer every long: the weekday forms and the time of day. */
  private val openFields = Seq("dayofweek(t)", "weekday(t)", "extract(DAYOFWEEK_ISO FROM t)",
    "next_day(t, 'MONDAY')", "trunc(t, 'WEEK')", "hour(t)", "minute(t)", "second(t)",
    "CAST(t AS TIME)", "CAST(t AS TIME(3))")

  // varka-session-lint: allow a path: each field of the family through the plan, in both modes,
  // batch declined; the answers themselves are the kernel suite's
  test("the calendar fields of a TIMESTAMP_NTZ at the date guard's ends, in every mode, on the " +
    "kernel") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      for (field <- calendarFields) {
        checkServed(s"SELECT i, $field AS v FROM varka_ntz_guard")
      }
    }
  }

  // varka-session-lint: allow a path: the format column's levels over the guarded date, and
  // the batch the guard declines
  test("trunc of a TIMESTAMP_NTZ by a format column runs on the kernel inside the date guard " +
    "and sends a batch past it to the row engine") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      checkServed("SELECT i, trunc(t, f) AS v FROM varka_ntz_trunc")
      val query = "SELECT i, trunc(t, f) AS v FROM varka_ntz_trunc_below"
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkSparkAnswerMaybeThrows(query)
      actual.collect()
      assert(varkaNode(plan).metrics("numFallbackBatchesDeclined").value === 1L, query)
    }
  }

  // varka-session-lint: allow a path: the declined batch's way to the row engine, as the
  // plan's metrics count it
  test("a calendar field of a count past the date guard goes to the row engine with its batch " +
    "alone") {
    for (table <- Seq("varka_ntz_below", "varka_ntz_above");
         field <- Seq("year(t)", "weekofyear(t)", "last_day(t)", "trunc(t, 'YEAR')")) {
      val query = s"SELECT i, $field AS v FROM $table"
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkSparkAnswerMaybeThrows(query)
      actual.collect()
      val node = varkaNode(plan)
      assert(node.metrics("numFallbackBatchesDeclined").value === 1L, query)
      assert(node.metrics("numVarkaBatches").value === 2L, query)
    }
  }

  // varka-session-lint: allow a path: which batches of one table decline, and which fields
  // stay on the kernel
  test("the long range's ends: a calendar field's batches go to the row engine, the weekday " +
    "forms and the time of day stay on the kernel, in every mode") {
    // varka_ntz_values holds seven batches of two; five hold a count past the date guard.
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      for (field <- Seq("year(t)", "weekofyear(t)", "last_day(t)")) {
        val query = s"SELECT i, $field AS v FROM varka_ntz_values"
        val actual = varkaSpark.sql(query)
        val plan = actual.queryExecution.executedPlan
        assertFused(plan)
        checkSparkAnswerMaybeThrows(query)
        actual.collect()
        val node = varkaNode(plan)
        assert(node.metrics("numFallbackBatchesDeclined").value === 5L, query)
        assert(node.metrics("numVarkaBatches").value === 2L, query)
      }
      for (field <- openFields) {
        checkServed(s"SELECT i, $field AS v FROM varka_ntz_values")
      }
    }
    // And the year is java.time's, past the guard too, where the row engine computes it.
    val years = varkaSpark.sql("SELECT i, year(t) AS y FROM varka_ntz_values").collect()
      .filter(!_.isNullAt(1)).map(r => r.getInt(0) -> r.getInt(1)).toMap
    for (Row(i: Int, t: LocalDateTime) <- ntzRows) {
      assert(years(i) === t.getYear, s"row $i: $t")
    }
  }
  // varka-session-lint: allow a path: each unit's guard through the plan, in both modes, with
  // the batch past it declined
  test("timestampadd at each unit's guard runs on the kernel, and a quantity one past goes to " +
    "the row engine with its batch alone, in every mode") {
    for (ansi <- Seq(false, true); (unit, _, past) <- addEdges) withAnsi(ansi) {
      checkServed(s"SELECT i, timestampadd($unit, q, t) AS v FROM varka_ntz_add_${unit}_inside")
      for (k <- past.indices) {
        val query = s"SELECT i, timestampadd($unit, q, t) AS v FROM varka_ntz_add_${unit}_past$k"
        val actual = varkaSpark.sql(query)
        val plan = actual.queryExecution.executedPlan
        assertFused(plan)
        checkSparkAnswerMaybeThrows(query) match {
          case Answered(_) =>
            actual.collect()
            assert(varkaNode(plan).metrics("numFallbackBatchesDeclined").value === 1L, query)
          case r: Raised =>
            assert(r.condition === "DATETIME_OVERFLOW", s"$query: $r")
        }
      }
    }
  }

  /** The month forms over `t`, the date held to the fields' guard. */
  private val monthForms = Seq("t + INTERVAL '1' MONTH", "t - INTERVAL '13' MONTH",
    "timestampadd(MONTH, -24576, t)", "timestampadd(YEAR, 2047, t)",
    "timestampadd(QUARTER, -8192, t)", "add_months(t, 1)")

  // varka-session-lint: allow a path: the month forms at the date guard's ends, and a batch past
  // it declined
  test("the month forms of a TIMESTAMP_NTZ at the date guard's ends run on the kernel, and a " +
    "timestamp whose date is past it goes to the row engine with its batch alone") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      for (form <- monthForms) {
        checkServed(s"SELECT i, $form AS v FROM varka_ntz_guard")
        for (table <- Seq("varka_ntz_below", "varka_ntz_above")) {
          val query = s"SELECT i, $form AS v FROM $table"
          val actual = varkaSpark.sql(query)
          val plan = actual.queryExecution.executedPlan
          assertFused(plan)
          checkSparkAnswerMaybeThrows(query)
          actual.collect()
          assert(varkaNode(plan).metrics("numFallbackBatchesDeclined").value === 1L, query)
        }
      }
      for (form <- Seq("t + ym", "t - ym")) {
        checkServed(s"SELECT i, $form AS v FROM varka_ntz_add_YEAR_inside")
      }
    }
  }

  // varka-session-lint: allow a path: a cast count in each mode through the plan, and the fused
  // ones on the kernel
  test("timestampadd by a month unit over a bigint cast to INT answers as Spark does under " +
    "LEGACY, ANSI and TRY, past the int range too; MONTH declines and QUARTER and YEAR fuse") {
    for (ansi <- Seq(false, true); cast <- Seq("CAST", "try_cast");
         unit <- Seq("MONTH", "QUARTER", "YEAR")) withAnsi(ansi) {
      val form = s"timestampadd($unit, $cast(q AS INT), t)"
      val fused = unit != "MONTH"
      // ANSI's cast raises on a quantity past the int range and TRY's nulls it. LEGACY's keeps
      // the low bits: a small count answers, and Int.MinValue is past every month unit's range.
      val raises = Map(
        "varka_ntz_add_cast" -> (if (ansi && cast == "CAST") Some("CAST_OVERFLOW") else None),
        "varka_ntz_add_cast_least" -> (if (cast == "try_cast") None
          else if (ansi) Some("CAST_OVERFLOW") else Some("DATETIME_OVERFLOW")))
      for ((table, raised) <- raises) {
        val query = s"SELECT i, $form AS v FROM $table"
        withClue(s"ansi=$ansi: $query") {
          val actual = varkaSpark.sql(query)
          val plan = actual.queryExecution.executedPlan
          if (fused) assertFused(plan) else assertNotFused(plan)
          checkSparkAnswerMaybeThrows(query) match {
            case r: Raised => assert(raised === Some(r.condition), r)
            case Answered(_) =>
              assert(raised === None)
              if (fused) { actual.collect(); assertKernelsRan(plan) }
          }
        }
      }
      if (fused) checkServed(s"SELECT i, $form AS v FROM varka_ntz_add_cast_inside")
    }
  }

  test("the least month count negated raises under LEGACY and ANSI, from the row engine") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      val query = "SELECT i, t - ym AS v FROM varka_ntz_ym_least"
      assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
      assert(checkSparkAnswerMaybeThrows(query).isInstanceOf[Raised], query)
    }
  }

  test("make_timestamp_ntz answers every day whose midnight is a long on the kernel, in every " +
    "mode") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      checkServed("SELECT i, make_timestamp_ntz(d, tm) AS v FROM varka_ntz_make")
    }
  }

  // varka-session-lint: allow a path: the batch the date guard or the checked add sends to the
  // row engine, as the plan's metrics count it
  test("make_timestamp_ntz past the long raises from the row engine, and the day before the " +
    "first is answered there, its batch alone leaving the kernel") {
    for (ansi <- Seq(false, true)) withAnsi(ansi) {
      val raises = "SELECT i, make_timestamp_ntz(d, tm) AS v FROM varka_ntz_make_raises"
      assertFused(varkaSpark.sql(raises).queryExecution.executedPlan)
      assert(checkSparkAnswerMaybeThrows(raises).isInstanceOf[Raised],
        s"ansi=$ansi: $raises: answered where Spark raises")
      val below = "SELECT i, make_timestamp_ntz(d, tm) AS v FROM varka_ntz_make_below"
      val actual = varkaSpark.sql(below)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      assert(checkSparkAnswerMaybeThrows(below).isInstanceOf[Answered], below)
      actual.collect()
      val node = varkaNode(plan)
      assert(node.metrics("numFallbackBatchesDeclined").value === 1L, below)
      assert(node.metrics("numVarkaBatches").value === 3L, below)
    }
  }

  // varka-session-lint: allow a path: the date a rewritten cast carries, on the kernel and on
  // the row engine within one query, and the cached kernel class two zones share
  test("CAST(time AS TIMESTAMP_NTZ) carries one date a query, on the kernel and on the batches " +
    "a date field declines, and two zones share its kernel and answer their own dates") {
    val query = "SELECT i, CAST(tm AS TIMESTAMP_NTZ) AS c, year(t) AS y FROM varka_ntz_pin"
    val expected = pinRows.map { case Row(i: Int, t: LocalDateTime, tm: LocalTime) =>
      (i, tm, t.getYear)
    }
    // The kernel class a plan's fused node resolves to: the hash of the shape cache's key, built
    // from the node's own compilation and emit options. Comparing it across the two zones, rather
    // than counting the JVM-wide cache misses, is blind to any other emission in the JVM meanwhile.
    def shapeHash(plan: SparkPlan): String = {
      val (partial, session) = varkaNode(plan) match {
        case v: VarkaColumnarToRowExec => (v.classification, v.sessionEmit)
        case v: VarkaProjectExec => (v.classification, v.sessionEmit)
        case other => fail(s"not a fused projection: $other")
      }
      val fused = partial.orElseThrow().fused
      VarkaShapeCache.shapeHash(new VarkaShapeKey(fused.outputs, fused.inputOrdinals.size,
        fused.numLiterals, VarkaColumnarToRowExec.emitOptions(session)))
    }
    // The answer under one session zone: the date every row carries, which must be that zone's
    // current date when the query ran, the time each row's own, and the year java.time's; and
    // the shape of the kernel that answered it.
    def run(zone: String): (LocalDate, String) = {
      varkaSpark.conf.set(SQLConf.SESSION_LOCAL_TIMEZONE.key, zone)
      val before = LocalDate.now(ZoneId.of(zone))
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      val rows = actual.collect().map(r => (r.getInt(0), r.getAs[LocalDateTime](1), r.getInt(2)))
        .sortBy(_._1)
      val after = LocalDate.now(ZoneId.of(zone))
      val dates = rows.map(_._2.toLocalDate).distinct
      assert(dates.length === 1, s"$zone: more than one date in a query: ${dates.toSeq}")
      assert(dates.head == before || dates.head == after, s"$zone: ${dates.head}, not today")
      assert(rows.map { case (i, c, y) => (i, c.toLocalTime, y) }.toSeq === expected, zone)
      val node = varkaNode(plan)
      assert(node.metrics("numFallbackBatchesDeclined").value === 2L, s"$zone: $query")
      assert(node.metrics("numVarkaBatches").value === 2L, s"$zone: $query")
      (dates.head, shapeHash(plan))
    }
    val zoneBefore = varkaSpark.conf.getOption(SQLConf.SESSION_LOCAL_TIMEZONE.key)
    try {
      val (east, eastShape) = run("Etc/GMT-14")
      val (west, westShape) = run("Etc/GMT+12")
      assert(westShape === eastShape, "the second zone's kernel is another shape")
      assert(east != west, s"two zones 26 hours apart answered one date, $east")
    } finally {
      zoneBefore match {
        case Some(v) => varkaSpark.conf.set(SQLConf.SESSION_LOCAL_TIMEZONE.key, v)
        case None => varkaSpark.conf.unset(SQLConf.SESSION_LOCAL_TIMEZONE.key)
      }
    }
  }
}
