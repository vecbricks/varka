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
 * The `INT` arithmetic baseline's sections, run at a small scale on both sessions in every
 * build, so a section that no longer parses, raises, changes its result type or that the two
 * sessions answer differently fails a pull request rather than the runner dispatch that times it
 * (VARKA-95). Each section runs under its own SQL settings, its mode where it has one. It also
 * makes the benchmark's own fuse check, under each setting of the two-lane refusal, and its
 * kernel check on the `WHOLE` control, so a row that changes how a section fuses sets the
 * section's new class in the same pull request.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the
 * id, so these rows already reach the regions the benchmark's javadoc states, which the suite
 * checks before it runs the sections: `i` and `j` of both signs inside `[-40000, 40000]`, every
 * product inside the int, `datediff(d2, d)` of both signs with its product by `i` inside the
 * int, and `i_n` null at one row in ten. A projection over `i` must answer no null, so an
 * overflow into a `try_multiply` null fails here rather than going into the timed run as a cheap
 * null; one over `i_n` must answer exactly its nulls. The filter answers no null, keeping the
 * rows whose product is positive, from the null column the same rows but its nulls.
 */
class VarkaIntArithmeticBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  /** Runs `body` under every one of a section's settings on both sessions, as the benchmark. */
  private def withSettings(s: VarkaIntArithmeticBaselineBenchmark.Section)(
      body: => Unit): Unit = {
    def under(settings: List[(String, String)]): Unit = settings match {
      case (key, value) :: rest => withBothConf(key, value)(under(rest))
      case Nil => body
    }
    under(s.conf.asScala.toList)
  }

  /** Runs `body` with the benchmark's table over the fixture's rows, cached on both sessions. */
  private def withTable(body: => Unit): Unit = {
    val view = VarkaIntArithmeticBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaIntArithmeticBaselineBenchmark.tableSql(rows))
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

  test("every section of the int arithmetic baseline runs, and both sessions answer alike") {
    val view = VarkaIntArithmeticBaselineBenchmark.VIEW
    withTable {
      val int = Int.MaxValue.toLong
      val r = spark.sql(
        s"""select min(i), max(i), min(j), max(j),
           |       max(abs(cast(i as bigint) * j)),
           |       min(datediff(d2, d)), max(datediff(d2, d)),
           |       max(abs(cast(datediff(d2, d) as bigint) * i)),
           |       count_if(i * j > 0), count_if(i_n is null),
           |       count_if(i_n is null and i * j > 0)
           |from $view""".stripMargin).head()
      assert(r.getInt(0) < 0 && r.getInt(1) > 0 && r.getInt(2) < 0 && r.getInt(3) > 0)
      assert(r.getInt(0) >= -40000 && r.getInt(1) <= 40000)
      assert(r.getInt(2) >= -40000 && r.getInt(3) <= 40000)
      assert(r.getLong(4) < int)
      assert(r.getInt(5) < 0 && r.getInt(6) > 0)
      assert(r.getLong(7) < int)
      val kept = r.getLong(8)
      assert(kept > 0 && kept < rows)
      assert(r.getLong(9) === rows / 10)
      val keptNull = r.getLong(10)

      val sections = VarkaIntArithmeticBaselineBenchmark.sections(false).asScala.toSeq
      // Six sections at each of the two null densities, every one left to the row path but the
      // LEGACY control: a row that fuses one of these changes this list.
      assert(sections.size === 2 * 6)
      val fused = sections.filter(_.fusion != VarkaSqlBenchmarkBase.Fusion.NONE)
      assert(fused.map(s => (s.title, s.fusion.toString)) === Seq(
        ("i * j, LEGACY, control, fuses today, no nulls", "WHOLE"),
        ("i * j, LEGACY, control, fuses today, one row in ten null", "WHOLE")))
      for (s <- sections) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query)
            assert(answer.schema.map(_.name) === Seq("a"))
            assert(answer.schema.head.dataType === IntegerType)
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

  test("each section of the int arithmetic baseline fuses as its class lists it",
      PinsDefaults) {
    // The benchmark's own fuse check, made here too, so a change to how a section fuses fails a
    // pull request rather than the runner dispatch that times it. Each section is planned under
    // its own settings and each setting of the two-lane refusal, as a benchmark host may set it,
    // and under the emitter's default options, so the classes pin the defaults; the run that
    // lifts the benchmark's check cancels it.
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    withTable {
      forEachTwoLaneRefusal { declines =>
        for (s <- VarkaIntArithmeticBaselineBenchmark.sections(declines).asScala) {
          withClue(s"${s.title}, refusal $declines") {
            withSettings(s) {
              val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
              assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(s.fusion))
              // The kernel check too: the WHOLE control hands no batch to the row path.
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
