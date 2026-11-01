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
import org.apache.spark.sql.types._

/**
 * The rounding baseline's sections, run at a small scale on both sessions in every build, so a
 * section that no longer parses, raises, changes its result type or that the two sessions answer
 * differently fails a pull request rather than the runner dispatch that times it (VARKA-344 5).
 * Each section runs under its own SQL settings, its mode where it has one. It checks the answers
 * only: whether a section fuses is the benchmark's own check, made when it is timed, since row
 * 344 is meant to start fusing these queries.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the
 * id, so these rows already reach the regions the benchmark's javadoc states, which the suite
 * checks before it runs the sections: `x` with a fraction, at a tie, at `0.0` and `-0.0` told
 * apart by their sign, NaN, below, inside and above the bucket bounds, and inside `2^31 / 7`;
 * `i` over both halves of the int range; `l` past `2^52` and not past `2^54`; `k` at both ends of
 * `[-2, 22]`. A section over the columns with no null must answer no null, except `factorial`,
 * which must answer null exactly on the rows of `k` outside `[0, 20]`, and `width_bucket`, null
 * exactly on the NaN rows of `x`, so a value that left its type into a null fails here rather
 * than going into the timed run as a cheap null. A section over the columns with one row in ten
 * null must answer exactly those nulls, besides the same rows of `k_n` and `x_n`.
 */
class VarkaRoundingBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  /** The result type of each function, in the order `sections()` lists them for one density. */
  private val resultTypes = Seq(DoubleType, LongType, LongType, LongType, LongType, LongType,
    IntegerType, IntegerType, LongType, LongType, LongType, LongType)

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
           |       max(if(isnan(x), 0, abs(x))) <= power(2, 31) / 7,
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

      val sections = VarkaRoundingBaselineBenchmark.sections().asScala.toSeq
      assert(sections.size === 2 * resultTypes.size)
      for ((s, index) <- sections.zipWithIndex) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            assert(answer.schema.map(_.name) === Seq("a"))
            assert(answer.schema.head.dataType === resultTypes(index % resultTypes.size))
            checkAnswer(varkaSpark.sql(s.query), answer)
            val noNulls = s.title.endsWith("no nulls")
            val inputNulls = if (noNulls) 0L else rows / 10L
            val nulls = if (s.query.contains("factorial")) {
              if (noNulls) factorialNulls else factorialNullsN
            } else if (s.query.contains("width_bucket")) {
              inputNulls + nan
            } else {
              inputNulls
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
