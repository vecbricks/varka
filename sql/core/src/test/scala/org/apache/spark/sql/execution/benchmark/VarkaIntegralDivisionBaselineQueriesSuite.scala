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
 * The integral division baseline's sections, run at a small scale on both sessions in every
 * build, so a section that no longer parses, raises, changes its result type or that the two
 * sessions answer differently fails a pull request rather than the runner dispatch that times it
 * (VARKA-30 4). Each section runs under its own SQL settings, its mode where it has one. It also
 * makes the benchmark's own fuse check, that each section's plan on the Varka session has the
 * section's fusion class under the section's settings, with the two-lane condition refusal off
 * and on, and its kernel check on the `WHOLE` control, so a row that changes how a section fuses
 * sets the section's new class in the same pull request.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the
 * id, so these rows already reach the regions the benchmark's javadoc states, which the suite
 * checks before it runs the sections: no divisor zero, `j` holding `1` and `-1` and `l2` holding
 * `-1`, small divisors of every type, `l2` past the int range, dividends of both signs, `l` past
 * the int range and at most `2^62` in magnitude, and the `_n` dividends null at exactly one row
 * in ten. A projection over the columns with no null must answer no null, so a zero divisor's
 * `try_mod` null fails here rather than going into the timed run as a cheap null; one over a
 * column with one row in ten null must answer exactly those nulls. A filter answers no null and
 * keeps some rows, not all.
 */
class VarkaIntegralDivisionBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  /** Runs `body` under every one of a section's settings on both sessions, as the benchmark. */
  private def withSettings(s: VarkaIntegralDivisionBaselineBenchmark.Section)(
      body: => Unit): Unit = {
    def under(settings: List[(String, String)]): Unit = settings match {
      case (key, value) :: rest => withBothConf(key, value)(under(rest))
      case Nil => body
    }
    under(s.conf.asScala.toList)
  }

  /** Runs `body` with the benchmark's table over the fixture's rows, cached on both sessions. */
  private def withTable(body: => Unit): Unit = {
    val view = VarkaIntegralDivisionBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaIntegralDivisionBaselineBenchmark.tableSql(rows))
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

  /** The type a section's one column has: `div`'s `BIGINT`, else the dividend's type. */
  private def columnType(s: VarkaIntegralDivisionBaselineBenchmark.Section): DataType = {
    if (!s.query.contains("WHERE") && s.query.contains(" div ")) {
      LongType
    } else if (s.title.startsWith("INT ")) {
      IntegerType
    } else if (s.title.startsWith("BIGINT ")) {
      LongType
    } else {
      assert(s.title.startsWith("SMALLINT "), s.title)
      ShortType
    }
  }

  test("every section of the integral division baseline runs, and both sessions answer alike") {
    val view = VarkaIntegralDivisionBaselineBenchmark.VIEW
    withTable {
      val int = Int.MaxValue.toLong
      val r = spark.sql(
        s"""select count_if(j = 0), count_if(l2 = 0), count_if(s2 = 0),
           |       count_if(j = -1), count_if(j = 1), count_if(l2 = -1),
           |       count_if(abs(j) < 256), count_if(abs(l2) < 256), count_if(abs(s2) < 16),
           |       min(i), max(i), min(l), max(l), min(s), max(s),
           |       count_if(abs(l) > $int), count_if(abs(l2) > $int),
           |       max(abs(l)), count_if(i % 7 = 0),
           |       count_if(i_n is null), count_if(l_n is null), count_if(s_n is null)
           |from $view""".stripMargin).head()
      for (column <- 0 until 3) {
        assert(r.getLong(column) === 0L, s"zero divisors, column $column")
      }
      for (column <- 3 until 9) {
        assert(r.getLong(column) > 0, s"column $column")
      }
      assert(r.getInt(9) < 0 && r.getInt(10) > 0)
      assert(r.getLong(11) < 0 && r.getLong(12) > 0)
      assert(r.getShort(13) < 0 && r.getShort(14) > 0)
      assert(r.getLong(15) > 0 && r.getLong(16) > 0)
      assert(r.getLong(17) <= (1L << 62))
      assert(r.getLong(18) > 0)
      for (column <- 19 until 22) {
        assert(r.getLong(column) === rows / 10, s"nulls, column $column")
      }

      val sections = VarkaIntegralDivisionBaselineBenchmark.sections(false).asScala.toSeq
      // 31 sections at each of the two null densities, every one unfused but the control.
      assert(sections.size === 2 * 31)
      for (declines <- Seq(false, true)) {
        val classes = VarkaIntegralDivisionBaselineBenchmark.sections(declines).asScala
          .groupBy(_.fusion.toString).map { case (k, v) => k -> v.size }
        assert(classes === Map("WHOLE" -> 2, "NONE" -> 60), s"refusal $declines")
      }
      for (s <- sections) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            assert(answer.schema.map(_.name) === Seq("a"))
            assert(answer.schema.head.dataType === columnType(s))
            // One job per session: the counts below come from the rows collected here.
            val collected = answer.collect().toSeq
            checkAnswer(varkaSpark.sql(s.query), collected)
            val noNulls = s.title.endsWith("no nulls")
            val nulls = if (s.query.contains("WHERE")) {
              assert(collected.nonEmpty && collected.size < rows)
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

  test("each section of the integral division baseline fuses as its class lists it",
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
        for (s <- VarkaIntegralDivisionBaselineBenchmark.sections(declines).asScala) {
          withClue(s"${s.title}, refusal $declines") {
            withSettings(s) {
              val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
              assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(s.fusion))
              // The benchmark's kernel check too: the WHOLE control hands no batch to the row
              // path.
              if (s.checksKernels) {
                VarkaSizeLadder.varkaFused(varkaSpark, s.query)
              }
            }
          }
        }
      }
    }
  }
}
