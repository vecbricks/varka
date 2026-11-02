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

package org.apache.spark.sql.execution.benchmark

import java.util.Optional

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix.PinsDefaults
import org.apache.spark.sql.execution.VarkaSharedSessions
import org.apache.spark.sql.types._

/**
 * The day-time interval baseline's sections, run at a small scale on both sessions in every
 * build, so a section that no longer parses, raises, changes its result type or that the two
 * sessions answer differently fails a pull request rather than the runner dispatch that times it
 * (VARKA-103 4). It also makes the benchmark's own fuse check, that each section's plan on the
 * Varka session has the section's fusion class under all of the section's settings, with the
 * two-lane condition refusal off and on, and its kernel check, that a `WHOLE` section hands no
 * batch to the row path, so a row that changes how a section fuses sets the section's new class
 * in the same pull request.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the
 * id, so these rows already reach the regions the benchmark's javadoc states, which the suite
 * checks before it runs the sections: `dt` and `dt2` of both signs and past a thousand days,
 * with negative values whose hour, minute and second truncate toward zero, and `dt`'s planted
 * minus one microsecond and minus one day; `i`, `i2`, `i3` and `s` of both signs, `i2` past a
 * day's 23 hours. A section over the columns with no null must answer no null, so a value that
 * left its type into a null fails here rather than going into the timed run as a cheap null; a
 * section over the columns with one row in ten null must answer exactly those nulls. The filters
 * answer no null, the extract keeping the rows whose hour is at least twelve, and the control
 * the rows where `dt` is the larger. The blend control answers no null either: where `dt_n` is
 * null its condition is too, and the blend takes `dt2`, which has none.
 */
class VarkaDayTimeIntervalBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  private val dayTime = DayTimeIntervalType(DayTimeIntervalType.DAY, DayTimeIntervalType.SECOND)

  /**
   * The result columns' types of each section, in the order `sections(boolean)` lists one
   * density, the same order for both values.
   */
  private val resultTypes = Seq(
    Seq(dayTime), Seq(dayTime), Seq(dayTime), Seq(dayTime), Seq(dayTime),
    Seq(IntegerType), Seq(ByteType),
    Seq(IntegerType, ByteType, ByteType, DecimalType(8, 6)),
    Seq(dayTime), Seq(dayTime),
    Seq(DayTimeIntervalType(DayTimeIntervalType.HOUR)),
    Seq(dayTime), Seq(dayTime), Seq(dayTime))

  /** Runs `body` under every one of a section's settings on both sessions, as the benchmark. */
  private def withSettings(s: VarkaDayTimeIntervalBaselineBenchmark.Section)(
      body: => Unit): Unit = {
    def under(settings: List[(String, String)]): Unit = settings match {
      case (key, value) :: rest => withBothConf(key, value)(under(rest))
      case Nil => body
    }
    under(s.conf.asScala.toList)
  }

  /** Runs `body` with the benchmark's table over the fixture's rows, cached on both sessions. */
  private def withTable(body: => Unit): Unit = {
    val view = VarkaDayTimeIntervalBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaDayTimeIntervalBaselineBenchmark.tableSql(rows))
          .createOrReplaceTempView(view)
        session.catalog.cacheTable(view)
      }
      body
    } finally {
      for (session <- sessions if session.catalog.tableExists(view)) {
        session.catalog.uncacheTable(view)
        session.catalog.dropTempView(view)
      }
    }
  }

  test("every section of the day-time interval baseline runs, and both sessions answer alike") {
    val view = VarkaDayTimeIntervalBaselineBenchmark.VIEW
    withTable {
      val day = "INTERVAL '1000' DAY"
      val r = spark.sql(
        s"""select count_if(dt < -$day), count_if(dt > $day),
           |       count_if(dt2 < -$day), count_if(dt2 > $day),
           |       count_if(dt < INTERVAL '0' SECOND and extract(HOUR FROM dt) < 0),
           |       count_if(dt < INTERVAL '0' SECOND and extract(MINUTE FROM dt) < 0),
           |       count_if(dt < INTERVAL '0' SECOND and extract(SECOND FROM dt) < 0),
           |       count_if(dt = INTERVAL '-0.000001' SECOND and extract(DAY FROM dt) = 0
           |           and extract(HOUR FROM dt) = 0 and extract(MINUTE FROM dt) = 0
           |           and extract(SECOND FROM dt) = -0.000001),
           |       count_if(dt = INTERVAL '-1' DAY and extract(DAY FROM dt) = -1
           |           and extract(HOUR FROM dt) = 0 and extract(MINUTE FROM dt) = 0
           |           and extract(SECOND FROM dt) = 0),
           |       min(i), max(i), min(i2), max(i2), min(i3), max(i3),
           |       count_if(s < 0), count_if(s > 0),
           |       count_if(extract(HOUR FROM dt) >= 12), count_if(dt > dt2),
           |       count_if(dt_n is null), count_if(i_n is null), count_if(s_n is null)
           |from $view""".stripMargin).head()
      for (column <- 0 until 7) {
        assert(r.getLong(column) > 0, s"column $column")
      }
      assert(r.getLong(7) === rows / 1000 && r.getLong(8) === rows / 1000)
      assert(r.getInt(9) < -90000 && r.getInt(10) > 90000)
      assert(r.getInt(11) < -23 && r.getInt(12) > 23)
      assert(r.getInt(13) < -90000 && r.getInt(14) > 90000)
      assert(r.getLong(15) > 0 && r.getLong(16) > 0)
      val hourKept = r.getLong(17)
      val controlKept = r.getLong(18)
      assert(hourKept > 0 && controlKept > 0)
      for (column <- 19 until 22) {
        assert(r.getLong(column) === rows / 10)
      }
      // The rows the filters keep from the null column: those of the full column but the nulls.
      val k = spark.sql(
        s"""select count_if(dt_n is null and extract(HOUR FROM dt) >= 12),
           |       count_if(dt_n is null and dt > dt2)
           |from $view""".stripMargin).head()

      // The answers do not depend on the plan a section is held to, so either list does; this
      // is the list with the two-lane refusal off, the shared sessions' setting.
      val sections = VarkaDayTimeIntervalBaselineBenchmark.sections(false).asScala.toSeq
      assert(sections.size === 2 * resultTypes.size)
      def fused(list: Seq[VarkaDayTimeIntervalBaselineBenchmark.Section]): Seq[(String, String)] =
        list.filter(_.fusion != VarkaSqlBenchmarkBase.Fusion.NONE)
          .map(s => (s.title, s.fusion.toString))
      // The projections row 103 fuses, WHOLE at both widths: the refusal below is a filter's.
      val projections = Seq("dt + dt2 - dt", "abs(dt)", "dt * 3")
      def density(d: String): Seq[(String, String)] = projections.map(p => (s"$p, $d", "WHOLE"))
      val blend = "CASE WHEN dt > dt2 THEN dt ELSE dt2 END, control, fuses at both widths"
      assert(fused(sections) === density("no nulls") ++ Seq(
        ("filter extract(HOUR FROM dt) >= 12, no nulls", "NULL_TESTS"),
        ("filter dt > dt2, control, fuses today, no nulls", "WHOLE"),
        (s"$blend, no nulls", "WHOLE")) ++ density("one row in ten null") ++ Seq(
        ("filter extract(HOUR FROM dt) >= 12, one row in ten null", "NULL_TESTS"),
        ("filter dt > dt2, control, fuses today, one row in ten null", "WHOLE"),
        (s"$blend, one row in ten null", "WHOLE")))
      // Under the refusal only the projections and the blend control fuse, so the 128-bit run
      // still has a WHOLE control; the list's titles and queries are the same either way.
      val refused = VarkaDayTimeIntervalBaselineBenchmark.sections(true).asScala.toSeq
      assert(refused.map(s => (s.title, s.query)) === sections.map(s => (s.title, s.query)))
      assert(fused(refused) === density("no nulls") ++ Seq((s"$blend, no nulls", "WHOLE")) ++
        density("one row in ten null") ++ Seq((s"$blend, one row in ten null", "WHOLE")))
      for ((s, index) <- sections.zipWithIndex) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            val types = resultTypes(index % resultTypes.size)
            assert(answer.schema.map(_.name) === Seq("a", "b", "c", "d").take(types.size))
            assert(answer.schema.map(_.dataType) === types)
            // One job per session: the counts below come from the rows collected here.
            val collected = answer.collect().toSeq
            checkAnswer(varkaSpark.sql(s.query), collected)
            val noNulls = s.title.endsWith("no nulls")
            val nulls = if (s.query.contains("WHERE")) {
              val kept = if (s.query.contains("> dt2")) {
                controlKept - (if (noNulls) 0 else k.getLong(1))
              } else {
                hourKept - (if (noNulls) 0 else k.getLong(0))
              }
              assert(collected.size === kept)
              0L
            } else if (s.query.contains("CASE WHEN")) {
              0L
            } else {
              if (noNulls) 0L else rows / 10L
            }
            assert(collected.count(_.isNullAt(0)) === nulls)
          }
        }
      }
    }
  }

  test("each section of the day-time interval baseline fuses as its class lists it",
      PinsDefaults) {
    // The benchmark's own fuse check, made here too, so a change to how a section fuses fails a
    // pull request rather than the runner dispatch that times it. Each section is planned under
    // its own settings, as the benchmark plans it, and under the emitter's default options, so
    // the classes pin the defaults; the run that lifts the benchmark's check cancels it. The
    // benchmark lists its classes at the two-lane refusal the host resolves (VARKA-503), which
    // the shared sessions leave off, so both lists are checked here by setting the refusal each
    // way.
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    withTable {
      forEachTwoLaneRefusal { declines =>
        for (s <- VarkaDayTimeIntervalBaselineBenchmark.sections(declines).asScala) {
          withClue(s"${s.title}, refusal $declines") {
            withSettings(s) {
              val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
              assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(s.fusion))
              // The benchmark's kernel check too: a WHOLE section hands no batch to the row path.
              if (s.fusion == VarkaSqlBenchmarkBase.Fusion.WHOLE) {
                VarkaDayTimeIntervalBaselineBenchmark.requireKernel(varkaSpark, s)
              }
            }
          }
        }
      }
    }
  }
}
