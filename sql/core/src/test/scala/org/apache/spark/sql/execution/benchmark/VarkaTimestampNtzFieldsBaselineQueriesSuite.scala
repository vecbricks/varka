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

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.{QueryTest, Row}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChrono
import org.apache.spark.sql.execution.VarkaSharedSessions
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DateType, TimestampNTZType, TimeType}

/**
 * The TIMESTAMP_NTZ fields baseline's sections, run at a small scale on both sessions in every
 * build, so a section that no longer parses, that raises, that changes its result type, that the
 * two sessions answer differently, or that fuses unlisted fails a pull request rather than the
 * runner dispatch that times it (VARKA-340 5, step 1).
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the id,
 * so these rows already reach the regions the benchmark's javadoc states, which the suite checks
 * before it runs the sections: timestamps on both sides of the epoch, those before it with a time
 * of day other than midnight, days before the Gregorian reform, days at a year's ends where
 * `weekofyear`'s Thursday shift crosses into the next or the last year, every hour off the pinned
 * rows, both ends of the day in `ntz` and in `tm` at their pinned rows, dates on both sides of the
 * epoch, and every day inside the date fields' guard. A section answers the type its `Section`
 * names and exactly the nulls of the null columns it reads, each null at its own row of ten.
 *
 * `CAST(tm AS TIMESTAMP_NTZ)` takes the query's current date, so a midnight between the two
 * sessions' runs changes its answer; such a section's pair is run again once when the current
 * date read before and after it differ.
 *
 * The fuse check is the benchmark's own, made here too: a section fuses exactly when its class
 * lists it as fusing, and none is listed while the class is a baseline. A step of VARKA-340 that
 * fuses a section lists it, and this check follows the list; with the benchmark's fuse check
 * property set to false it is skipped, as the benchmark's plan check is.
 */
class VarkaTimestampNtzFieldsBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  import VarkaTimestampNtzFieldsBaselineBenchmark._

  private val rows = 2000

  /** Runs `body` with `table` cached on both sessions as the benchmark's view. */
  private def withView(table: String)(body: => Unit): Unit = {
    withBothConf(SQLConf.TIME_TYPE_ENABLED.key, "true") {
      val sessions = Seq(spark, varkaSpark)
      try {
        for (session <- sessions) {
          session.sql(table).createOrReplaceTempView(VIEW)
          session.catalog.cacheTable(VIEW)
        }
        body
      } finally {
        for (session <- sessions if session.catalog.tableExists(VIEW)) {
          session.catalog.uncacheTable(VIEW)
          session.catalog.dropTempView(VIEW)
        }
      }
    }
  }

  private def currentDate(): Row = spark.sql("SELECT current_date()").head()

  /**
   * Runs `s` on `spark`, checks that `varkaSpark` answers it alike, and returns the answer. A
   * section that reads the current date is run again once if a midnight fell during its pair.
   */
  private def answerOnBoth(s: Section): Seq[Row] = {
    def pair(): Seq[Row] = {
      val answer = spark.sql(s.query).collect().toSeq
      checkAnswer(varkaSpark.sql(s.query), answer)
      answer
    }
    if (!s.readsCurrentDate) {
      pair()
    } else {
      val before = currentDate()
      try pair() catch {
        case _: Exception if currentDate() != before => pair()
      }
    }
  }

  test("every section of the TIMESTAMP_NTZ fields baseline runs, and both sessions answer it " +
    "alike") {
    withView(tableSql(rows)) {
      val schema = spark.table(VIEW).schema
      val types = Seq("ntz" -> TimestampNTZType, "tm" -> TimeType(TimeType.MICROS_PRECISION),
        "d" -> DateType)
      assert(schema.map(f => f.name -> f.dataType) ===
        types ++ types.map { case (n, t) => (n + "n") -> t })

      val epoch = "timestamp_ntz'1970-01-01 00:00:00'"
      val pinned = "cast(ntz as time) in (time'00:00:00', time'23:59:59.999999')"
      val r = spark.sql(
        s"""select count_if(ntz < $epoch) as before_epoch, count_if(ntz >= $epoch) as after_epoch,
           |       count_if(ntz < $epoch and cast(ntz as time) <> time'00:00:00')
           |         as before_epoch_timed,
           |       count_if(ntz < timestamp_ntz'1582-10-15 00:00:00') as julian,
           |       count_if((month(ntz) = 12 and weekofyear(ntz) = 1)
           |         or (month(ntz) = 1 and weekofyear(ntz) >= 52)) as week_crosses_year,
           |       count(distinct case when not $pinned then hour(ntz) end) as free_hours,
           |       count_if(cast(ntz as time) = time'00:00:00') as ntz_midnight,
           |       count_if(cast(ntz as time) = time'23:59:59.999999') as ntz_day_end,
           |       count_if(tm = time'00:00:00') as tm_midnight,
           |       count_if(tm = time'23:59:59.999999') as tm_day_end,
           |       min(unix_date(cast(ntz as date))) as ntz_min_day,
           |       max(unix_date(cast(ntz as date))) as ntz_max_day,
           |       min(unix_date(d)) as d_min_day, max(unix_date(d)) as d_max_day,
           |       count_if(ntzn is null) as ntz_nulls, count_if(tmn is null) as tm_nulls,
           |       count_if(dn is null) as d_nulls,
           |       count_if(tmn is null and dn is null) as tm_and_d_nulls
           |from $VIEW""".stripMargin).head()
      def long(name: String): Long = r.getAs[Long](name)
      def int(name: String): Int = r.getAs[Int](name)
      assert(long("before_epoch") > rows / 3 && long("after_epoch") > rows / 3)
      assert(long("before_epoch_timed") > rows / 3)
      // Day -141427, 15 October 1582, lies just below the middle of the spread.
      assert(long("julian") > rows / 3)
      // Some of the last days of December in week 1 of the next year, or some of the first days
      // of January in the last week of the year before.
      assert(long("week_crosses_year") > 0)
      // Every hour of the day, off the rows pinned to its ends.
      assert(long("free_hours") === 24)
      // Each end of the day at its pinned rows, two in two thousand.
      assert(long("ntz_midnight") === rows / 1000 && long("ntz_day_end") === rows / 1000)
      assert(long("tm_midnight") === rows / 1000 && long("tm_day_end") === rows / 1000)
      // Every day inside the date fields' guard, a year above the narrow lane's floor.
      assert(int("ntz_min_day") >= -MAX_DAYS && int("ntz_max_day") <= MAX_DAYS)
      assert(int("ntz_min_day") < -MAX_DAYS / 2 && int("ntz_max_day") > MAX_DAYS / 2)
      assert(int("d_min_day") < -MAX_DAYS / 2 && int("d_max_day") > MAX_DAYS / 2)
      assert(int("d_min_day") >= -MAX_DAYS && int("d_max_day") <= MAX_DAYS)
      assert(long("ntz_nulls") === rows / 10 && long("tm_nulls") === rows / 10)
      assert(long("d_nulls") === rows / 10 && long("tm_and_d_nulls") === 0)

      for (s <- sections().asScala) {
        withClue(s.title) {
          val schema = spark.sql(s.query).schema
          assert(schema.map(f => f.name -> f.dataType) === Seq("v" -> s.resultType))
          val answer = answerOnBoth(s)
          assert(answer.count(_.isNullAt(0)) === s.nullColumns * rows / 10)
        }
      }
    }
  }

  test("a section of the TIMESTAMP_NTZ fields baseline fuses only when it is listed") {
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    withView(tableSql(100)) {
      // The cast to DATE fuses, so a check that found no Varka node anywhere, over a plan it
      // could not see into, fails here rather than passing every section.
      assert(VarkaSqlBenchmarkBase.fusedPlan(varkaSpark,
        s"SELECT CAST(ntz AS DATE) AS v FROM $VIEW").isPresent)
      for (s <- sections().asScala) {
        withClue(s.title) {
          assert(VarkaSqlBenchmarkBase.fusedPlan(varkaSpark, s.query).isPresent === s.fuses)
        }
      }
    }
  }

  test("no section of the TIMESTAMP_NTZ fields baseline raises at the ends of its values") {
    // The date fields' guard admits these days, so the after-run's date sections fuse whole.
    assert(-MAX_DAYS > VarkaChrono.NARROW_MIN_DAYS + 365)
    assert(MAX_DAYS < VarkaChrono.NARROW_MAX_DAYS)
    def row(id: Int, sign: Long): String = {
      val high = sign > 0
      val ntz = s"timestamp_ntz'1970-01-01 00:00:00' + make_dt_interval(${sign * MAX_DAYS}, " +
        s"0, 0, ${if (high) "86399.999999" else "0"})"
      val tm = s"time_from_micros(${if (high) MICROS_PER_DAY - 1 else 0}L)"
      val d = s"date_from_unix_date(${sign * MAX_DAYS})"
      s"select $id as id, $ntz as ntz, $tm as tm, $d as d"
    }
    // Rows 0 and 1 hold each column's least and greatest values. Rows 2 and 3 repeat them, and
    // there each nullable column is null, as in the timed table: so the null sections read the
    // extremes and a null beside them in one batch, and answer null on rows 2 and 3 only.
    val columns = Seq("ntz", "tm", "d")
    val rowsSql = Seq(row(0, 1), row(1, -1), row(2, 1), row(3, -1)).mkString(" union all ")
    // Cached, as the timed table is: an uncached view of literal rows is folded at planning, so
    // the extreme values would never reach the cached scan a kernel reads.
    val table = (columns ++ columns.map(c => s"case when id >= 2 then null else $c end as ${c}n"))
      .mkString("select ", ", ", s" from ($rowsSql)")
    withView(table) {
      for (s <- sections().asScala) {
        withClue(s.title) {
          val nulls = answerOnBoth(s).count(_.isNullAt(0))
          assert(nulls === (if (s.nullColumns > 0) 2 else 0))
        }
      }
    }
  }
}
