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

import java.time.{Duration, LocalTime}

import org.apache.spark.{SparkArithmeticException, SparkDateTimeException}
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaHostIsa,
  VarkaTestWatchdog}
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DayTimeIntervalType, LongType, StructField, StructType, TimeType}

/**
 * VARKA-102's TIME kernels through a session: what [[VarkaTimeHarnessSuite]], which holds the
 * kernels' answers over every second of a day with no session, cannot check.
 *
 * `t + dt` is a wrapping add under two range guards, because Spark's
 * `DateTimeUtils.timeAddInterval` throws where the sum leaves the day and a lane cannot throw,
 * so the batch declines to the row engine instead; the `time_from_*` conversions decline a
 * count outside the day the same way. The two error tests here hold the errors a user then sees
 * to Spark's own, and the third holds the session switch that selects the 64-bit division's
 * magic form, through both consumers, the row node and the Arrow cache's columnar one.
 */
class VarkaTimeArithmeticSuite extends QueryTest with VarkaMaybeThrows with VarkaTestWatchdog {

  private val secondsPerDay = 86400
  private val day = "varka_time_day"
  private val crossing = "varka_time_crossing"
  private val outside = "varka_time_outside"

  /**
   * Every second of the day, plus a sub-second edge on every thirteenth row: one microsecond,
   * half a second, the last microsecond before the next second. Every 31st `t` and every 47th
   * `dt` is null.
   */
  private def dayRows: Seq[Row] = (0 until secondsPerDay).map { i =>
    val fraction = if (i % 13 == 0) Seq(1000L, 500000000L, 999999000L)(i / 13 % 3) else 0L
    val t = LocalTime.ofSecondOfDay(i).plusNanos(fraction)
    // Shifted by twelve hours and thirty-seven minutes, modulo the day.
    val t2 = t.plusSeconds(12 * 3600 + 37 * 60)
    // Truncated to milliseconds, so the TIME(3) column exercises the widening cast.
    val t3 = LocalTime.ofNanoOfDay(t.toNanoOfDay / 1000000L * 1000000L)
    // An hour forward before noon and back after it stays inside the day for every row; the
    // half-second one exercises the sub-second path and the microsecond precision step.
    val forward = t.isBefore(LocalTime.NOON)
    val dt = if (forward) Duration.ofHours(1) else Duration.ofHours(-1)
    val dt2 = if (forward) Duration.ofMillis(500) else Duration.ofMillis(-500)
    // `s` is the second of day as a bigint, the count the `time_from_*` conversions take.
    Row(if (i % 31 == 30) null else t, t2, t3, if (i % 47 == 46) null else dt, dt2,
      if (i % 31 == 30) null else t.toSecondOfDay.toLong)
  }

  /** Three rows whose sum leaves the day, one that stays, and a null. */
  private def crossingRows: Seq[Row] = Seq(
    Row(LocalTime.of(23, 30), Duration.ofHours(1)),
    Row(LocalTime.of(0, 15), Duration.ofHours(-1)),
    Row(LocalTime.of(23, 59, 59, 999999000), Duration.ofNanos(1000)),
    Row(LocalTime.of(12, 0), Duration.ofHours(1)),
    Row(null, Duration.ofHours(1)))

  private val daySchema = StructType(Seq(
    StructField("t", TimeType(6)), StructField("t2", TimeType(6)),
    StructField("t3", TimeType(3)), StructField("dt", DayTimeIntervalType()),
    StructField("dt2", DayTimeIntervalType()), StructField("s", LongType)))

  /** Counts outside the day on either side, and one inside: the conversions' range check. */
  private def outsideRows: Seq[Row] = Seq(Row(86400L), Row(-1L), Row(3600L), Row(null))

  private val outsideSchema = StructType(Seq(StructField("s", LongType)))

  private val crossingSchema = StructType(Seq(
    StructField("t", TimeType(6)), StructField("dt", DayTimeIntervalType())))

  private def cache(session: SparkSession): Unit = {
    session.createDataFrame(session.sparkContext.parallelize(dayRows, 2), daySchema)
      .createOrReplaceTempView(day)
    session.catalog.cacheTable(day)
    session.createDataFrame(session.sparkContext.parallelize(crossingRows, 1), crossingSchema)
      .createOrReplaceTempView(crossing)
    session.catalog.cacheTable(crossing)
    session.createDataFrame(session.sparkContext.parallelize(outsideRows, 1), outsideSchema)
      .createOrReplaceTempView(outside)
    session.catalog.cacheTable(outside)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cache(spark)
    cache(varkaSpark)
  }

  private def isColumnarVarkaNode(plan: SparkPlan): Boolean = plan match {
    case _: VarkaProjectExec | _: VarkaFilterExec => true
    case _ => false
  }

  /**
   * Both consumers: the direct query fused and served by the kernels with the row engine's
   * answer, and the same query cached, which the Arrow cache builder feeds from the columnar
   * node.
   */
  private def checkBoth(select: String, table: String = day): Unit = {
    val query = s"SELECT $select AS v FROM $table"
    withClue(s"row consumer: $select") {
      val actual = varkaSpark.sql(query)
      val plan = actual.queryExecution.executedPlan
      assertFused(plan)
      checkAnswer(actual, expectedSql(query, actual))
      assertKernelsRan(plan)
    }
    val view = "varka_time_out"
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(query).createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
    }
    try {
      withClue(s"columnar consumer: $select") {
        val cached = varkaSpark.table(view).queryExecution.executedPlan.collectFirst {
          case scan: InMemoryTableScanExec => scan.relation.cachedPlan
        }
        assert(cached.isDefined, "expected the view to be served from the cache")
        assert(cached.get.find(isColumnarVarkaNode).isDefined,
          s"expected a columnar Varka node under the cache:\n${cached.get.treeString}")
        val served = varkaSpark.table(view)
        checkAnswer(served, expectedOf(spark.table(view), served, view))
      }
    } finally {
      for (session <- Seq(spark, varkaSpark)) session.catalog.uncacheTable(view)
    }
  }

  test("t + dt that crosses midnight raises Spark's own error under Varka, not a wrapped time") {
    // The test the guard exists for. A kernel that wrapped would answer a time here; Spark
    // answers DATETIME_OVERFLOW, and so must a query served by Varka, from the same rows.
    val query = s"SELECT t + dt AS v FROM $crossing"
    // Fused first, so the error below is one the kernel's decline handed to the row engine and
    // not one a query that never reached the kernel would have raised anyway.
    assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
    val raised = checkSparkRaisesTheSame[SparkArithmeticException](query)
    assert(raised.condition === "DATETIME_OVERFLOW")
  }

  test("the emit.useAVX session switch selects the magic form, and the answers do not move") {
    // VARKA-121: `spark.sql.codegen.varka.emit.useAVX=2` makes a session emit the magic-number
    // form of the 64-bit division - the lowering a host without intrinsified converts takes -
    // through the same plan and the same cache path, with the row engine as the oracle for it
    // as for the conversion form. The switch is a session property, so it is set on the fused
    // session alone and set back to the session's pinned -1 in a finally (an unset would leave
    // the conf's default, which may resolve from the host).
    varkaSpark.conf.set(SQLConf.VARKA_EMIT_USE_AVX.key, "2")
    try {
      for (field <- Seq("hour", "minute", "second")) checkBoth(s"$field(t)")
      checkBoth("time_trunc('MINUTE', t)")
      checkBoth("time_diff('HOUR', t, t2)")
    } finally {
      varkaSpark.conf.set(SQLConf.VARKA_EMIT_USE_AVX.key, "-1")
    }
  }

  /** The session emit settings every Varka node of `plan` was planned with. */
  private def plannedEmit(plan: SparkPlan): Seq[VarkaSessionEmit] = collect(plan) {
    case p: VarkaProjectExec => p.sessionEmit
    case f: VarkaFilterExec => f.sessionEmit
    case f: VarkaFilterColumnarToRowExec => f.sessionEmit
    case c: VarkaColumnarToRowExec => c.sessionEmit
  }

  /** Runs `body` with the fused session asking for its level from the host, then re-pins it. */
  private def resolvingFromHost(body: => Unit): Unit = {
    varkaSpark.conf.set(SQLConf.VARKA_EMIT_USE_AVX.key, VarkaSessionEmit.USE_AVX_FROM_HOST.toString)
    try body finally varkaSpark.conf.set(SQLConf.VARKA_EMIT_USE_AVX.key, "-1")
  }

  test("a session resolving its level on a host whose converts fall back plans the magic form") {
    // VARKA-538 test 3.2: a fallback host, planted, makes the driver resolve level 2; the plan
    // carries that record to the executors, whose compile reads it, and the answers stay the
    // row engine's. A converting host, planted, leaves the emitter's default.
    resolvingFromHost {
      VarkaSessionEmit.setHostForTesting(
        new VarkaHostIsa.Reading("amd64", "avx, avx2, fma", null))
      try {
        val plan = varkaSpark.sql(s"SELECT hour(t) AS v FROM $day").queryExecution.executedPlan
        assert(plannedEmit(plan).nonEmpty, plan.treeString)
        assert(plannedEmit(plan).forall(_.useAVX === 2), plan.treeString)
        assert(plannedEmit(plan).forall(e =>
          VarkaColumnarToRowExec.emitOptions(e).convertsFallBack()))
        checkBoth("hour(t)")
        checkBoth("time_diff('HOUR', t, t2)")
      } finally {
        VarkaSessionEmit.setHostForTesting(null)
      }
      VarkaSessionEmit.setHostForTesting(
        new VarkaHostIsa.Reading("amd64", "avx, avx2, avx512f, avx512dq, avx512vl", null))
      try {
        val plan = varkaSpark.sql(s"SELECT hour(t) AS v FROM $day").queryExecution.executedPlan
        assert(plannedEmit(plan).forall(_.useAVX === VarkaEmitOptions.USE_AVX_UNKNOWN),
          plan.treeString)
      } finally {
        VarkaSessionEmit.setHostForTesting(null)
      }
    }
  }

  test("a session resolving its level from this JVM plans what the JVM's features say") {
    // VARKA-538 test 3.6, nothing planted: the real export, the reflective read and of(conf)
    // on whatever CPU runs the suite - the magic form on an AVX2 runner, the conversion form
    // on an AVX-512 one - and the answers are the row engine's either way.
    resolvingFromHost {
      val plan = varkaSpark.sql(s"SELECT hour(t) AS v FROM $day").queryExecution.executedPlan
      val emits = plannedEmit(plan)
      assert(emits.nonEmpty, plan.treeString)
      val host = VarkaHostIsa.host()
      // The level, not the options' predicate: a matrix configuration may set a base level the
      // session's unknown leaves in place.
      val expected = if (host.conversionsFallBack()) 2 else VarkaEmitOptions.USE_AVX_UNKNOWN
      assert(emits.forall(_.useAVX === expected), s"$emits on a host reading $host")
      if (host.x86()) {
        assert(host.readable(), s"the feature query failed on x86: ${host.failure()}")
      }
      for (field <- Seq("hour", "minute", "second")) checkBoth(s"$field(t)")
    }
  }

  test("a count outside the day raises Spark's own error under Varka, from the same rows") {
    // The guard the conversions ride: the kernel declines the batch that holds 86400 and -1,
    // and the row engine raises what it raises without Varka, on the same condition.
    val query = s"SELECT time_from_seconds(s) AS v FROM $outside"
    assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
    // A SparkDateTimeException, not the arithmetic one `t + dt` raises: the conversion's range
    // check is its own error, and the kernel's decline has to hand the row engine that one.
    checkSparkRaisesTheSame[SparkDateTimeException](query)
  }
}
