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
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

/**
 * The floating cast baseline's sections, run at a small scale on both sessions in every build, so
 * a section that no longer parses, raises, changes its result type or that the two sessions
 * answer differently fails a pull request rather than the runner dispatch that times it
 * (VARKA-343 5). Each section runs under its own SQL settings, its mode where it has one. It
 * checks the answers only: whether a section fuses is the benchmark's own check, made when it is
 * timed, since row 343 is meant to start fusing these queries.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the
 * id, so these rows already reach the regions the benchmark's javadoc states, which the suite
 * checks before it runs the sections: `l` past `2^53` on both sides, between `2^51` and `2^53`
 * on both sides and below `2^24`, `i` over both halves of the int range, `x` inside the int range
 * with its `0.0` and `-0.0` zeros told apart by their sign, `w` with a fraction, past the int
 * range, past `2^53` inside the long range and past the long range, its NaN and infinities, and
 * `s` inside the day. A section over the columns with no null must answer no null, except
 * `try_cast`, which must answer null exactly on the rows of `w` past the long range, NaN or
 * infinite, so a value that left its type into a null fails here rather than going into the timed
 * run as a cheap null. A section over the columns with one row in ten null must answer exactly
 * those nulls, and the `try_cast` the same rows of `w_n` past the long range besides; the filter
 * answers no null in either, keeping the rows whose `x` is neither zero nor null.
 */
class VarkaFloatingCastBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  /** The result type of each cast, in the order `sections()` lists them for one density. */
  private val resultTypes = Seq(FloatType, DoubleType, DoubleType, IntegerType, IntegerType,
    LongType, FloatType, DoubleType, TimeType(TimeType.MICROS_PRECISION))

  /** Runs `body` with the section's SQL settings on both sessions; a section has at most one. */
  private def withSettings(s: VarkaFloatingCastBaselineBenchmark.Section)(
      body: => Unit): Unit = {
    val settings = s.conf.asScala.toSeq
    assert(settings.size <= 1)
    settings.headOption match {
      case Some((key, value)) => withBothConf(key, value)(body)
      case None => body
    }
  }

  test("every section of the floating cast baseline runs, and both sessions answer alike") {
    val view = VarkaFloatingCastBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaFloatingCastBaselineBenchmark.tableSql(rows))
          .createOrReplaceTempView(view)
        session.catalog.cacheTable(view)
      }
      val longEdge = "9.223372036854775807E18"
      val p51 = "power(2, 51)"
      val p53 = "power(2, 53)"
      val inf = "double('Infinity')"
      val r = spark.sql(
        s"""select min(l) < -$p53, max(l) > $p53,
           |       count_if(l > -power(2, 24) and l < power(2, 24)),
           |       count_if(l > $p51 and l <= $p53), count_if(l < -$p51 and l >= -$p53),
           |       min(i) < -power(2, 30), max(i) > power(2, 30),
           |       max(abs(x)) <= power(2, 31) / 7,
           |       count_if(cast(x as string) = '0.0'), count_if(cast(x as string) = '-0.0'),
           |       count_if(not isnan(w) and abs(w) <> $inf and abs(w) > $longEdge),
           |       count_if(isnan(w)), count_if(w = $inf), count_if(w = -$inf),
           |       count_if(w % 1 <> 0),
           |       count_if(abs(w) > power(2, 31) and abs(w) < $p53),
           |       count_if(abs(w) > $p53 and abs(w) <= $longEdge),
           |       min(s) >= 0, max(s) < 86400, max(s) > 86000,
           |       count_if(w_n is null),
           |       count_if(w_n is null or isnan(w_n) or abs(w_n) > $longEdge)
           |from $view""".stripMargin).head()
      assert(r.getBoolean(0) && r.getBoolean(1))
      assert(r.getLong(2) > 0 && r.getLong(3) > 0 && r.getLong(4) > 0)
      assert(r.getBoolean(5) && r.getBoolean(6))
      assert(r.getBoolean(7))
      assert(r.getLong(8) === rows / 8 && r.getLong(9) === rows / 8)
      val overflows = r.getLong(10)
      assert(overflows > rows / 125 && overflows <= rows / 100)
      assert(r.getLong(11) === rows / 1000)
      assert(r.getLong(12) === rows / 1000 && r.getLong(13) === rows / 1000)
      assert(r.getLong(14) > 0 && r.getLong(15) > 0 && r.getLong(16) > 0)
      assert(r.getBoolean(17) && r.getBoolean(18) && r.getBoolean(19))
      // The rows of w the try_cast answers null for, and those of w_n with its own nulls besides.
      val tryNulls = overflows + 3 * rows / 1000
      assert(r.getLong(20) === rows / 10)
      assert(r.getLong(21) === rows / 10 + tryNulls)
      // LEGACY's cast saturates past the int range and answers 0 for NaN, rather than null.
      withBothConf(SQLConf.ANSI_ENABLED.key, "false") {
        val legacy = spark.sql(
          s"""select count_if(isnan(w) and cast(w as int) = 0),
             |       count_if(w = $inf and cast(w as int) = ${Int.MaxValue}),
             |       count_if(w = -$inf and cast(w as int) = ${Int.MinValue}),
             |       count_if(w >= power(2, 31) and w < $inf),
             |       count_if(w >= power(2, 31) and w < $inf and cast(w as int) = ${Int.MaxValue}),
             |       count_if(w < -power(2, 31) and w > -$inf),
             |       count_if(w < -power(2, 31) and w > -$inf and cast(w as int) = ${Int.MinValue})
             |from $view""".stripMargin).head()
        assert(legacy.getLong(0) === rows / 1000 && legacy.getLong(1) === rows / 1000)
        assert(legacy.getLong(2) === rows / 1000)
        // Every finite value past the int range saturates, and about three in seven of the rows
        // are past it on each side, so the infinities alone cannot meet the check.
        assert(legacy.getLong(3) > rows / 4 && legacy.getLong(4) === legacy.getLong(3))
        assert(legacy.getLong(5) > rows / 4 && legacy.getLong(6) === legacy.getLong(5))
      }

      val sections = VarkaFloatingCastBaselineBenchmark.sections().asScala.toSeq
      assert(sections.size === 2 * resultTypes.size)
      for ((s, index) <- sections.zipWithIndex) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            assert(answer.schema.map(_.name) === Seq("a"))
            assert(answer.schema.head.dataType === resultTypes(index % resultTypes.size))
            checkAnswer(varkaSpark.sql(s.query), answer)
            val noNulls = s.title.endsWith("no nulls")
            val nulls = if (s.query.contains("WHERE")) {
              // The filter drops the null x_n with the zeros: coalesce answers false for both.
              val kept = rows * 3 / 4 - (if (noNulls) 0 else rows / 10)
              assert(answer.count() === kept)
              0L
            } else {
              val overflowed = if (s.query.contains("try_cast")) tryNulls else 0L
              (if (noNulls) 0L else rows / 10) + overflowed
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
