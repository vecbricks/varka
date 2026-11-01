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
 * The integral cast baseline's sections, run at a small scale on both sessions in every build, so
 * a section that no longer parses, raises, changes its result type or that the two sessions
 * answer differently fails a pull request rather than the runner dispatch that times it
 * (VARKA-332 5). Each section runs under its own SQL settings: its mode where it has one, and
 * the filter's constraint propagation, off. It
 * checks the answers only: whether a section fuses is the benchmark's own check, made when it is
 * timed, for VARKA-332 is meant to start fusing these queries.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the
 * id, so these rows already reach the regions the benchmark's javadoc states, which the suite
 * checks before it runs the sections: `i` over both halves of the int range with its zeros, `j`
 * and `y` reaching both ends of the byte range, `k` past the byte range on both sides at one row
 * in two hundred each, `l` inside the short range, past it on both sides and past the int range,
 * `b` both true and false, `m` at both ends of the range `CAST(m AS INTERVAL YEAR)` accepts, and
 * `dt` of both signs under `2^31` seconds, with negative values that truncate toward zero and
 * its minus half second. A section over the columns with no null must answer no null, except
 * `try_cast`, which must answer null exactly on the rows of `k` past the byte range, so a value
 * that left its type into a null fails here rather than going into the timed run as a cheap
 * null. A section over the columns with one row in ten null must answer exactly those nulls, and
 * the `try_cast` the same rows of `k_n` past the byte range besides; the filter answers no null
 * in either, keeping the rows whose `i` is neither zero nor null.
 */
class VarkaIntegralCastBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  /** The result type of each cast, in the order `sections()` lists them for one density. */
  private val resultTypes = Seq(ByteType, ByteType, ByteType, ByteType, ShortType, LongType,
    IntegerType, IntegerType, YearMonthIntervalType(YearMonthIntervalType.YEAR), LongType,
    LongType)

  /** Runs `body` with the section's SQL settings on both sessions; a section has at most one. */
  private def withSettings(s: VarkaIntegralCastBaselineBenchmark.Section)(
      body: => Unit): Unit = {
    val settings = s.conf.asScala.toSeq
    assert(settings.size <= 1)
    settings.headOption match {
      case Some((key, value)) => withBothConf(key, value)(body)
      case None => body
    }
  }

  test("every section of the integral cast baseline runs, and both sessions answer alike") {
    val view = VarkaIntegralCastBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaIntegralCastBaselineBenchmark.tableSql(rows))
          .createOrReplaceTempView(view)
        session.catalog.cacheTable(view)
      }
      val zero = "INTERVAL '0' SECOND"
      val r = spark.sql(
        s"""select min(i) < -power(2, 30), max(i) > power(2, 30), count_if(i = 0),
           |       min(j), max(j), count_if(k > 127), count_if(k < -128),
           |       count_if(l >= -32768 and l <= 32767), count_if(l > 32767),
           |       count_if(l < -32768), count_if(abs(l) > power(2, 31)),
           |       min(y), max(y), count_if(b), count_if(not b),
           |       min(m), max(m),
           |       count_if(dt < INTERVAL '-1' SECOND), count_if(dt > INTERVAL '1' SECOND),
           |       count_if(dt < $zero and
           |           make_dt_interval(0, 0, 0, cast(cast(dt as bigint) as decimal(18, 6))) > dt),
           |       count_if(dt = INTERVAL '-0.5' SECOND and cast(dt as bigint) = 0),
           |       count_if(abs(cast(dt as bigint)) >= power(2, 31)),
           |       count_if(i_n is null), count_if(j_n is null), count_if(k_n is null),
           |       count_if(l_n is null), count_if(y_n is null), count_if(b_n is null),
           |       count_if(m_n is null), count_if(dt_n is null)
           |from $view""".stripMargin).head()
      assert(r.getBoolean(0) && r.getBoolean(1))
      assert(r.getLong(2) === rows / 8)
      assert(r.getInt(3) === -128 && r.getInt(4) === 127)
      // The rows of k the try_cast answers null for.
      val tryNulls = rows / 100
      assert(r.getLong(5) === rows / 200 && r.getLong(6) === rows / 200)
      assert(r.getLong(7) > 0 && r.getLong(8) > 0 && r.getLong(9) > 0 && r.getLong(10) > 0)
      assert(r.getByte(11) === Byte.MinValue && r.getByte(12) === Byte.MaxValue)
      assert(r.getLong(13) > 0 && r.getLong(14) > 0)
      assert(r.getInt(15) === -178956970 && r.getInt(16) === 178956970)
      assert(r.getLong(17) > 0 && r.getLong(18) > 0 && r.getLong(19) > 0)
      assert(r.getLong(20) === rows / 1000)
      assert(r.getLong(21) === 0)
      for (column <- 22 until 30) {
        assert(r.getLong(column) === rows / 10)
      }
      // LEGACY's casts keep the low bits of the values past the narrow type, rather than null:
      // the low bits read as a signed number are ((v + 2^(w-1)) & (2^w - 1)) - 2^(w-1), the sum
      // wrapping under LEGACY without changing them.
      withBothConf(SQLConf.ANSI_ENABLED.key, "false") {
        val legacy = spark.sql(
          s"""select count_if(cast(i as tinyint) <> i),
             |       count_if(cast(i as tinyint) <> ((i + 128) & 255) - 128),
             |       count_if(cast(l as smallint) <> l),
             |       count_if(cast(l as smallint) <> ((l + 32768) & 65535) - 32768),
             |       count_if(cast(k as tinyint) <> k),
             |       count_if(cast(k as tinyint) <> ((k + 128) & 255) - 128),
             |       count_if((cast(k as tinyint) <> k) <> (try_cast(k as tinyint) is null))
             |from $view""".stripMargin).head()
        assert(legacy.getLong(0) > rows / 2 && legacy.getLong(1) === 0)
        assert(legacy.getLong(2) > rows / 2 && legacy.getLong(3) === 0)
        // LEGACY's twin of the try_cast wraps exactly the rows the try_cast answers null for, row
        // for row: no row wraps where the try_cast answers a value, or keeps k where it is null.
        assert(legacy.getLong(4) === rows / 100 && legacy.getLong(5) === 0)
        assert(legacy.getLong(6) === 0)
      }

      val sections = VarkaIntegralCastBaselineBenchmark.sections().asScala.toSeq
      assert(sections.size === 2 * resultTypes.size)
      assert(sections.filter(_.fusion != VarkaSqlBenchmarkBase.Fusion.NONE).map(_.title) === Seq(
        "INT CAST(i AS BIGINT), control, fuses today, no nulls",
        "INT CAST(i AS BIGINT), control, fuses today, one row in ten null"))
      for ((s, index) <- sections.zipWithIndex) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            assert(answer.schema.map(_.name) === Seq("a"))
            assert(answer.schema.head.dataType === resultTypes(index % resultTypes.size))
            checkAnswer(varkaSpark.sql(s.query), answer)
            val noNulls = s.title.endsWith("no nulls")
            val nulls = if (s.query.contains("WHERE")) {
              // The filter drops the null i_n with the zeros: neither cast is true.
              val kept = rows * 7 / 8 - (if (noNulls) 0 else rows / 10)
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
