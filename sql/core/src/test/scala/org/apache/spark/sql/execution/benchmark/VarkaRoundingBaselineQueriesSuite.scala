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
import org.apache.spark.sql.execution.VarkaSharedSessions
import org.apache.spark.sql.execution.benchmark.VarkaRoundingBaselineBenchmark.NullRule
import org.apache.spark.sql.internal.SQLConf

/**
 * The rounding baseline's sections, run at a small scale on both sessions in every build, so a
 * section that no longer parses, raises, changes its result type or that the two sessions answer
 * differently fails a pull request rather than the runner dispatch that times it (VARKA-344 5).
 * Each section runs under its own SQL settings, its mode where it has one. It checks the answers
 * only: whether a section fuses is the benchmark's own check, made when it is timed, since row
 * 344 is meant to start fusing these queries.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the id,
 * so these rows already reach the regions the benchmark's javadoc states, which the suite checks
 * before it runs the sections: `x` with a fraction, at a tie, at `0.0` and `-0.0` told apart by
 * their sign, NaN, below, inside and above the bucket bounds, and inside `2^31 / 7` with the ties
 * inside `2^28 + 0.5` (bounds the table's construction guarantees for every id, which these rows
 * only spot-check); `i` over both halves of the int range; `l` past `2^52` and not past `2^54`; `k`
 * at both ends of `[-2, 22]`; `xw` with a fraction, at both zeros, NaN and both infinities, past
 * `2^51`, past `2^52` inside the long range and past `2^63`, with no finite value overflowed to an
 * infinity; `xwf` with a fraction, at both zeros and NaN, past `2^51` and past `2^52`, with no
 * infinity and every value inside `2^62.2`. Under LEGACY it also checks `floor` and `ceil` over
 * `xw`'s NaN, infinities and values past the long range on both sessions: Spark compiles them as
 * `(long) Math.floor(c)` and `(long) Math.ceil(c)`, so NaN answers 0 and the rest saturate at
 * `Long.MaxValue` or `Long.MinValue`, rather than null. Under ANSI it checks that `floor` and
 * `ceil` over every row of `xwf` and `xwf_n` raise nothing on either session and answer what
 * LEGACY answers, NaN giving 0. A section over the columns with no null must answer no null,
 * except `factorial`, which must answer null exactly on the rows of `k` outside `[0, 20]`, and
 * `width_bucket`, null exactly on the NaN rows of `x`, so a value that left its type into a null
 * fails here rather than going into the timed run as a cheap null. A section over the columns with
 * one row in ten null must answer exactly those nulls, besides the same rows of `k_n` and `x_n`.
 * Each section carries its own result type and null rule, so adding or reordering sections cannot
 * pair one with another's expectations.
 */
class VarkaRoundingBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  /** Runs `body` with the section's SQL settings on both sessions; a section has at most one. */
  private def withSettings(s: VarkaRoundingBaselineBenchmark.Section)(body: => Unit): Unit = {
    val settings = s.conf.asScala.toSeq
    assert(settings.size <= 1)
    settings.headOption match {
      case Some((key, value)) => withBothConf(key, value)(body)
      case None => body
    }
  }

  test("every section of the rounding baseline runs, and both sessions answer alike") {
    val view = VarkaRoundingBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaRoundingBaselineBenchmark.tableSql(rows)).createOrReplaceTempView(view)
        session.catalog.cacheTable(view)
      }
      val r = spark.sql(
        s"""select count_if(abs(x) % 1 = 0.5),
           |       count_if(cast(x as string) = '0.0'), count_if(cast(x as string) = '-0.0'),
           |       count_if(isnan(x)),
           |       count_if(not isnan(x) and abs(x) % 1 <> 0 and abs(x) % 1 <> 0.5),
           |       max(if(isnan(x), 0, abs(x))) <= power(2, 31) / 7
           |         and max(if(abs(x) % 1 = 0.5, abs(x), 0)) <= power(2, 28) + 0.5,
           |       count_if(x < -2.0E8), count_if(x >= -2.0E8 and x < 2.0E8), count_if(x >= 2.0E8),
           |       min(i) < -power(2, 30), max(i) > power(2, 30),
           |       count_if(abs(l) > power(2, 52)), max(abs(l)) <= power(2, 54),
           |       min(k) = -2, max(k) = 22,
           |       count_if(k < 0 or k > 20), count_if(k_n is null or k_n < 0 or k_n > 20),
           |       count_if(x_n is null), count_if(isnan(x_n))
           |from $view""".stripMargin).head()
      assert(r.getLong(0) === rows / 8)
      assert(r.getLong(1) === rows / 8 && r.getLong(2) === rows / 8)
      assert(r.getLong(3) === rows / 1000)
      assert(r.getLong(4) > rows / 3)
      assert(r.getBoolean(5))
      assert(r.getLong(6) > 0 && r.getLong(7) > rows / 2 && r.getLong(8) > 0)
      assert(r.getBoolean(9) && r.getBoolean(10))
      assert(r.getLong(11) > 0 && r.getBoolean(12))
      assert(r.getBoolean(13) && r.getBoolean(14))
      val factorialNulls = r.getLong(15)
      assert(factorialNulls > 0)
      val factorialNullsN = r.getLong(16)
      assert(r.getLong(17) === rows / 10)
      // The NaN rows of x keep their value in x_n, so width_bucket answers null on them too.
      assert(r.getLong(18) === rows / 1000)
      val nan = rows / 1000L

      val longEdge = "9.223372036854775807E18"
      val inf = "double('Infinity')"
      val w = spark.sql(
        s"""select count_if(isnan(xw)), count_if(xw = $inf), count_if(xw = -$inf),
           |       count_if(abs(xw) = $inf),
           |       count_if(cast(xw as string) = '0.0'), count_if(cast(xw as string) = '-0.0'),
           |       count_if(not isnan(xw) and xw % 1 <> 0),
           |       count_if(abs(xw) > power(2, 51) and abs(xw) <= power(2, 52)),
           |       count_if(abs(xw) > power(2, 52) and abs(xw) <= $longEdge),
           |       count_if(not isnan(xw) and abs(xw) <> $inf and abs(xw) > $longEdge),
           |       count_if(xw_n is null), count_if(isnan(xw_n)), count_if(abs(xw_n) = $inf)
           |from $view""".stripMargin).head()
      assert(w.getLong(0) === rows / 1000)
      assert(w.getLong(1) === rows / 1000 && w.getLong(2) === rows / 1000)
      // Only the two infinite rows: no finite value's scaling overflowed to an infinity.
      assert(w.getLong(3) === 2 * rows / 1000)
      assert(w.getLong(4) === rows / 8 && w.getLong(5) === rows / 8)
      assert(w.getLong(6) > rows / 3)
      assert(w.getLong(7) > 0 && w.getLong(8) > 0)
      // About one row in eight is scaled past the long range.
      assert(w.getLong(9) > rows / 10)
      assert(w.getLong(10) === rows / 10)
      // The NaN and infinite rows of xw keep their value in xw_n.
      assert(w.getLong(11) === rows / 1000 && w.getLong(12) === 2 * rows / 1000)
      // LEGACY's floor and ceil answer 0 for NaN and saturate on the infinities and past the long
      // range, rather than null, alike on both sessions.
      withBothConf(SQLConf.ANSI_ENABLED.key, "false") {
        val edges = Seq("floor", "ceil").map { f =>
          s"""count_if(isnan(xw) and $f(xw) = 0),
             |count_if(xw = $inf and $f(xw) = ${Long.MaxValue}),
             |count_if(xw = -$inf and $f(xw) = ${Long.MinValue}),
             |count_if(xw >= power(2, 63) and xw < $inf),
             |count_if(xw >= power(2, 63) and xw < $inf and $f(xw) = ${Long.MaxValue}),
             |count_if(xw < -power(2, 63) and xw > -$inf),
             |count_if(xw < -power(2, 63) and xw > -$inf and $f(xw) = ${Long.MinValue})
             |""".stripMargin
        }.mkString(", ")
        val query = s"select $edges from $view"
        val legacy = spark.sql(query).head()
        assert(varkaSpark.sql(query).head() === legacy)
        for (base <- Seq(0, 7)) {
          assert(legacy.getLong(base) === rows / 1000)
          assert(legacy.getLong(base + 1) === rows / 1000)
          assert(legacy.getLong(base + 2) === rows / 1000)
          // Every finite value past the long range saturates, about one row in sixteen on each
          // side, so the infinities alone cannot meet the check.
          assert(legacy.getLong(base + 3) > rows / 25)
          assert(legacy.getLong(base + 4) === legacy.getLong(base + 3))
          assert(legacy.getLong(base + 5) > rows / 25)
          assert(legacy.getLong(base + 6) === legacy.getLong(base + 5))
        }
      }

      val f = spark.sql(
        s"""select count_if(isnan(xwf)), count_if(abs(xwf) = $inf),
           |       count_if(cast(xwf as string) = '0.0'), count_if(cast(xwf as string) = '-0.0'),
           |       count_if(not isnan(xwf) and xwf % 1 <> 0),
           |       count_if(abs(xwf) > power(2, 51) and abs(xwf) <= power(2, 52)),
           |       count_if(abs(xwf) > power(2, 52)),
           |       max(if(isnan(xwf), 0, abs(xwf))) <= power(2, 62.2),
           |       count_if(xwf_n is null), count_if(isnan(xwf_n))
           |from $view""".stripMargin).head()
      assert(f.getLong(0) === rows / 1000 && f.getLong(1) === 0)
      assert(f.getLong(2) === rows / 8 && f.getLong(3) === rows / 8)
      assert(f.getLong(4) > rows / 3)
      assert(f.getLong(5) > 0 && f.getLong(6) > 0)
      assert(f.getBoolean(7))
      // The NaN rows of xwf keep their value in xwf_n.
      assert(f.getLong(8) === rows / 10 && f.getLong(9) === rows / 1000)
      // ANSI's floor and ceil over xwf raise nothing, answer 0 on NaN and agree with LEGACY,
      // where no value of xwf saturates, on both sessions. Each answer is paired with its input,
      // so the comparison is row by row.
      val finite =
        s"select xwf, floor(xwf), ceil(xwf), xwf_n, floor(xwf_n), ceil(xwf_n) from $view"
      val legacyFinite = withBothConf(SQLConf.ANSI_ENABLED.key, "false") {
        spark.sql(finite).collect().toSeq
      }
      withBothConf(SQLConf.ANSI_ENABLED.key, "true") {
        val ansiFinite = spark.sql(finite)
        checkAnswer(ansiFinite, legacyFinite)
        checkAnswer(varkaSpark.sql(finite), legacyFinite)
        assert(ansiFinite.toDF("v", "fl", "ce", "v_n", "fl_n", "ce_n")
          .where("isnan(v) and fl = 0 and ce = 0").count() === rows / 1000)
      }

      val sections = VarkaRoundingBaselineBenchmark.sections().asScala.toSeq
      assert(sections.size === 40, sections.map(_.title).mkString("sections:\n", "\n", ""))
      assert(sections.map(_.title).distinct.size === sections.size)
      for (s <- sections) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            assert(answer.schema.map(_.name) === Seq("a"))
            assert(answer.schema.head.dataType.sql === s.resultType)
            checkAnswer(varkaSpark.sql(s.query), answer)
            val inputNulls = if (s.nullInputs) rows / 10L else 0L
            val nulls = s.nulls match {
              case NullRule.INPUT => inputNulls
              case NullRule.INPUT_OR_NAN => inputNulls + nan
              case NullRule.INPUT_OR_OUTSIDE_FACTORIAL =>
                if (s.nullInputs) factorialNullsN else factorialNulls
            }
            assert(answer.where("a IS NULL").count() === nulls)
          }
        }
      }
    } finally {
      for (session <- sessions if session.catalog.tableExists(view)) {
        session.catalog.uncacheTable(view)
        session.catalog.dropTempView(view)
      }
    }
  }
}
