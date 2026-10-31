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

/**
 * The floating arithmetic baseline's sections, run at a small scale on both sessions in every
 * build, so a section that no longer parses, raises or that the two sessions answer differently
 * fails a pull request rather than the runner dispatch that times it (VARKA-342 5). Each section
 * runs under its own SQL settings, its mode where it has one. It checks the answers only: whether
 * a section fuses is the benchmark's own check, made when it is timed, since row 342 is meant to
 * start fusing these queries.
 *
 * The fixture is the benchmark's table over a few hundred chosen ids rather than its first rows,
 * whose `x` and `y` are all negative and thousands from zero: the ids reach every sign pairing of
 * `x` and `y`, the smallest `|x|` and `|y|` on each side of zero, and the extremes the benchmark's
 * javadoc states, which the suite checks against the fixture before it runs the sections. A
 * section over the columns with no null must answer no null, so a value that left its type into a
 * null fails here rather than going into the timed run as a cheap null.
 */
class VarkaFloatingArithmeticBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  /**
   * The fixture's ids: the first 200, for the null pattern and the negative corner, and the ids
   * where `x` is smallest, largest and nearest zero on each side (0, 100002, 49001, 49002), where
   * `y` is the same (0, 71442, 49937, 78514), one where `x < 0 < y` (10000), and where `ym` and
   * `dt` are largest (923, 15247).
   */
  private val ids = "(select id from range(0, 200) union all select id from values " +
    Seq(100002, 49001, 49002, 71442, 49937, 78514, 10000, 923, 15247).map(i => s"(${i}L)")
      .mkString(", ") + " as v(id))"

  /** Runs `body` with the section's SQL settings on both sessions; a section has at most one. */
  private def withSettings(s: VarkaFloatingArithmeticBaselineBenchmark.Section)(
      body: => Unit): Unit = {
    val settings = s.conf.asScala.toSeq
    assert(settings.size <= 1)
    settings.headOption match {
      case Some((key, value)) => withBothConf(key, value)(body)
      case None => body
    }
  }

  test("every section of the floating arithmetic baseline runs, and both sessions answer alike") {
    val view = VarkaFloatingArithmeticBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaFloatingArithmeticBaselineBenchmark.tableSql(ids))
          .createOrReplaceTempView(view)
        session.catalog.cacheTable(view)
      }
      val r = spark.sql(
        s"""select min(x), max(x), min(abs(x)), min(y), max(y), min(abs(y)),
           |       count(distinct signum(x), signum(y)),
           |       max(ym) = interval '1199' month, max(dt) = interval '86399' second
           |from $view""".stripMargin).head()
      assert(r.getDouble(0) === -7000.25)
      assert(r.getDouble(1) === 7285.75)
      assert(math.abs(r.getDouble(2) - 1.0 / 28) < 1e-9)
      assert(r.getDouble(3) === -4500.25)
      assert(math.abs(r.getDouble(4) - 4592.295) < 1e-3)
      assert(math.abs(r.getDouble(5) - 1.0 / 44) < 1e-9)
      assert(r.getLong(6) === 4)
      assert(r.getBoolean(7) && r.getBoolean(8))

      val sections = VarkaFloatingArithmeticBaselineBenchmark.sections().asScala
      assert(sections.size === 20)
      for (s <- sections) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            checkAnswer(varkaSpark.sql(s.query), answer)
            if (s.title.endsWith("no nulls")) {
              assert(answer.where("a IS NULL").count() === 0)
            }
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
