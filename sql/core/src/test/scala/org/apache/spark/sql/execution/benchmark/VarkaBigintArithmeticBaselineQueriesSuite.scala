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
import org.apache.spark.sql.execution.{VarkaSessionEmit, VarkaSharedSessions}
import org.apache.spark.sql.types._

/**
 * The `BIGINT` arithmetic baseline's sections, run at a small scale on both sessions in every
 * build, so a section that no longer parses, raises, changes its result type or that the two
 * sessions answer differently fails a pull request rather than the runner dispatch that times it
 * (VARKA-104). Each section runs under its own SQL settings, its mode where it has one. It also
 * makes the benchmark's own fuse check, that each section's plan on the Varka session has the
 * section's fusion class under the section's settings, and its kernel check on the control, so a
 * row that changes how a section fuses sets the section's new class in the same pull request.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the
 * id, so these rows already reach the regions the benchmark's javadoc states, which the suite
 * checks before it runs the sections: `l` and `l2` of both signs, past the int range and below
 * it, and below `2^49` in magnitude; `m` of both signs inside `[-3000, 3000]`. A section over the
 * columns with no null must answer no null, so an overflow into a `try_` null fails here rather
 * than going into the timed run as a cheap null; a section over the column with one row in ten
 * null must answer exactly those nulls. The filter answers no null, keeping the rows whose sum is
 * positive, from the null column the same rows but its nulls.
 */
class VarkaBigintArithmeticBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  /** Runs `body` under every one of a section's settings on both sessions, as the benchmark. */
  private def withSettings(s: VarkaBigintArithmeticBaselineBenchmark.Section)(
      body: => Unit): Unit = {
    def under(settings: List[(String, String)]): Unit = settings match {
      case (key, value) :: rest => withBothConf(key, value)(under(rest))
      case Nil => body
    }
    under(s.conf.asScala.toList)
  }

  /** Runs `body` with the benchmark's table over the fixture's rows, cached on both sessions. */
  private def withTable(body: => Unit): Unit = {
    val view = VarkaBigintArithmeticBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaBigintArithmeticBaselineBenchmark.tableSql(rows))
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

  test("every section of the bigint arithmetic baseline runs, and both sessions answer alike") {
    val view = VarkaBigintArithmeticBaselineBenchmark.VIEW
    withTable {
      val int = Int.MaxValue.toLong
      val bound = 1L << 49
      val r = spark.sql(
        s"""select count_if(l < -$int), count_if(l > $int),
           |       count_if(l < 0 and l >= -$int), count_if(l > 0 and l <= $int),
           |       count_if(l2 < -$int), count_if(l2 > $int),
           |       count_if(l2 < 0 and l2 >= -$int), count_if(l2 > 0 and l2 <= $int),
           |       count_if(m < 0), count_if(m > 0),
           |       max(abs(l)), max(abs(l2)), min(m), max(m),
           |       count_if(l + l2 > 0), count_if(l_n is null),
           |       count_if(l_n is null and l + l2 > 0)
           |from $view""".stripMargin).head()
      for (column <- 0 until 10) {
        assert(r.getLong(column) > 0, s"column $column")
      }
      assert(r.getLong(10) < bound && r.getLong(11) < bound)
      assert(r.getLong(12) >= -3000 && r.getLong(13) <= 3000)
      val kept = r.getLong(14)
      assert(kept > 0 && kept < rows)
      assert(r.getLong(15) === rows / 10)
      val keptNull = r.getLong(16)

      val sections = VarkaBigintArithmeticBaselineBenchmark.sections(false).asScala.toSeq
      for (s <- sections) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            assert(answer.schema.map(_.name) === Seq("a"))
            assert(answer.schema.head.dataType === LongType)
            // One job per session: the counts below come from the rows collected here.
            val collected = answer.collect().toSeq
            checkAnswer(varkaSpark.sql(s.query), collected)
            val noNulls = s.title.endsWith("no nulls")
            val nulls = if (s.query.contains("WHERE")) {
              assert(collected.size === kept - (if (noNulls) 0 else keptNull))
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

  test("each section of the bigint arithmetic baseline fuses as its class lists it",
      PinsDefaults) {
    // The benchmark's own fuse check, made here too, so a change to how a section fuses fails a
    // pull request rather than the runner dispatch that times it. Each section is planned under
    // its own settings, as the benchmark plans it, and under the emitter's default options, so
    // the classes pin the defaults; the run that lifts the benchmark's check cancels it.
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    withTable {
      val declines =
        VarkaSessionEmit.of(varkaSpark.sessionState.conf).declineTwoLaneConditions()
      for (s <- VarkaBigintArithmeticBaselineBenchmark.sections(declines).asScala) {
        withClue(s.title) {
          withSettings(s) {
            val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
            assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(s.fusion))
            // The benchmark's kernel check too: a WHOLE projection hands no batch to the row path.
            if (s.checksKernels) {
              VarkaSizeLadder.varkaFused(varkaSpark, s.query)
            }
          }
        }
      }
    }
  }
}
