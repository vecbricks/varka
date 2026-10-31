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

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChrono
import org.apache.spark.sql.execution.VarkaSharedSessions
import org.apache.spark.sql.types.{DayTimeIntervalType, IntegerType, TimestampNTZType}

/**
 * The TIMESTAMP_NTZ arithmetic baseline's sections, run at a small scale on both sessions in every
 * build, so a section that no longer parses, that raises, that the two sessions answer
 * differently, or that fuses unlisted fails a pull request rather than the runner dispatch that
 * times it
 * (VARKA-339 5, step 1).
 *
 * Two further checks keep the table what the benchmark claims: every column has the declared
 * type, and a section over the columns with no null returns no null. Every section is collected
 * on both sessions, so one whose values left the range Spark computes in would raise here
 * (DATETIME_OVERFLOW or ARITHMETIC_OVERFLOW) rather than in the timed run.
 *
 * The fuse check is the benchmark's own, made here too: a section fuses exactly when its class
 * lists it as fusing, and none is listed while the class is a baseline. A step of VARKA-339 that
 * fuses a section lists it, and this check follows the list; with the benchmark's fuse check
 * property set to false it is skipped, as the benchmark's plan check is.
 */
class VarkaTimestampNtzArithmeticBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  /** Runs `body` with the benchmark's table, at `rows` rows, cached on both sessions. */
  private def withTable(rows: Int)(body: => Unit): Unit = {
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(VarkaTimestampNtzArithmeticBaselineBenchmark.tableSql(rows))
        .createOrReplaceTempView("varka_ntz_arith")
      session.catalog.cacheTable("varka_ntz_arith")
    }
    try body finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_ntz_arith"))
    }
  }

  test("every section of the TIMESTAMP_NTZ arithmetic baseline runs, and both sessions answer " +
    "it alike") {
    withTable(2000) {
      val dt = DayTimeIntervalType(DayTimeIntervalType.DAY, DayTimeIntervalType.SECOND)
      val columns = Seq("ntz" -> TimestampNTZType, "ntz2" -> TimestampNTZType,
        "ntz3" -> TimestampNTZType, "dt" -> dt, "q" -> IntegerType)
      val expected = columns ++ columns.map { case (name, t) => (name + "n") -> t }
      val schema = spark.table("varka_ntz_arith").schema
      assert(schema.map(f => f.name -> f.dataType) === expected)

      val sections = VarkaTimestampNtzArithmeticBaselineBenchmark.sections().asScala
      assert(sections.size === 12)
      // Every shape keeps the timestamp's type: the interval sums, timestampadd and time_bucket
      // all answer a TIMESTAMP_NTZ for a TIMESTAMP_NTZ operand.
      assert(sections.map(s => spark.sql(s.query).schema.head.dataType) ===
        Seq.fill(sections.size)(TimestampNTZType))
      for (s <- sections) {
        withClue(s.title) {
          val answer = spark.sql(s.query).collect().toSeq
          checkAnswer(varkaSpark.sql(s.query), answer)
          if (s.title.endsWith("no nulls")) {
            assert(!answer.exists(_.isNullAt(0)))
          }
        }
      }
    }
  }

  test("a section of the TIMESTAMP_NTZ arithmetic baseline fuses only when it is listed") {
    import VarkaTimestampNtzArithmeticBaselineBenchmark._
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    withTable(100) {
      // A filter comparing two NTZ columns fuses, so a check that found no Varka node anywhere,
      // over a plan it could not see into, fails here rather than passing every section.
      assert(VarkaSqlBenchmarkBase.fusedPlan(varkaSpark,
        "SELECT ntz FROM varka_ntz_arith WHERE ntz < ntz2").isPresent)
      for (s <- sections().asScala) {
        withClue(s.title) {
          assert(VarkaSqlBenchmarkBase.fusedPlan(varkaSpark, s.query).isPresent === s.fuses)
        }
      }
    }
  }

  test("no section of the TIMESTAMP_NTZ arithmetic baseline raises at the ends of its values") {
    import VarkaTimestampNtzArithmeticBaselineBenchmark._
    // The month form's day guard admits these days, so the after-run's month section fuses whole.
    assert(-MAX_TIMESTAMP_DAYS > VarkaChrono.NARROW_MIN_DAYS)
    assert(MAX_TIMESTAMP_DAYS < VarkaChrono.NARROW_MAX_DAYS)
    // The hash draws a whole number of days within the bound and a time of day in [0, 1 day), so
    // the rows of either sign hold each column's least and greatest values, paired so the chain's
    // ntz + (ntz2 - ntz3) reaches its own least and greatest sums.
    def dayTime(days: Long, high: Boolean): String =
      s"make_dt_interval($days, 0, 0, ${if (high) "86399.999999" else "0"})"
    def row(id: Int, sign: Long): String = {
      val high = sign > 0
      val epoch = "timestamp_ntz'1970-01-01 00:00:00' + "
      val ntz = epoch + dayTime(sign * MAX_TIMESTAMP_DAYS, high)
      val far = epoch + dayTime(-sign * MAX_TIMESTAMP_DAYS, !high)
      val dt = dayTime(sign * MAX_INTERVAL_DAYS, high)
      val q = s"cast(${sign * MAX_HOURS} as int)"
      Seq(ntz -> "ntz", ntz -> "ntz2", far -> "ntz3", dt -> "dt", q -> "q")
        .map { case (v, n) => s"$v as $n" }
        .mkString(s"select $id as id, ", ", ", "")
    }
    // Rows 0 and 1 hold the extremes in every column. Rows 2 and 3 repeat them, and there each
    // nullable column is null, as in the timed table: so the null sections read the extremes
    // and a null beside them in one batch, and answer null on rows 2 and 3 only.
    val columns = Seq("ntz", "ntz2", "ntz3", "dt", "q")
    val rows = Seq(row(0, 1), row(1, -1), row(2, 1), row(3, -1)).mkString(" union all ")
    val table = (columns ++ columns.map(c => s"case when id >= 2 then null else $c end as ${c}n"))
      .mkString("select ", ", ", s" from ($rows)")
    // Cached, as the timed table is: an uncached view of literal rows is folded at planning, so
    // the extreme values would never reach the cached scan a kernel reads.
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(table).createOrReplaceTempView("varka_ntz_arith")
      session.catalog.cacheTable("varka_ntz_arith")
    }
    try {
      assert(columns.forall(c => spark.table("varka_ntz_arith").schema(c + "n").nullable))
      for (s <- sections().asScala) {
        withClue(s.title) {
          val answer = spark.sql(s.query).collect().toSeq
          checkAnswer(varkaSpark.sql(s.query), answer)
          val nulls = answer.count(_.isNullAt(0))
          assert(nulls === (if (s.title.endsWith("no nulls")) 0 else 2))
        }
      }
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_ntz_arith"))
    }
  }
}
