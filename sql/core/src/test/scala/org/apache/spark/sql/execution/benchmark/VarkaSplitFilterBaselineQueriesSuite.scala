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
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{IntegerType, LongType}

/**
 * The split-filter baseline's sections, run at a small scale on both sessions in every build, so
 * a section that no longer parses or that the two sessions answer differently fails a pull
 * request rather than the runner dispatch that times it (VARKA-601 5). It also makes the
 * benchmark's own fuse check, that each section's plan on the Varka session has the section's
 * fusion class with the two-lane condition refusal off and on, and its kernel check, that a
 * `SPLIT` or `WHOLE` section hands no batch to the row path, so a row that changes the split
 * sets the sections' new classes in the same pull request.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the
 * id, so these rows already have the shares the sections' titles state, which the suite checks
 * first, with windows wide enough for 2000 draws: `u < 900` keeps about 90% of them, `u < 100`
 * about 10%, the residual about six in seven, `lu` equals `u`, and the `_n` columns are null at
 * exactly one row in ten.
 */
class VarkaSplitFilterBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  /** Runs `body` with the benchmark's table over the fixture's rows, cached on both sessions. */
  private def withTable(body: => Unit): Unit = {
    val view = VarkaSplitFilterBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaSplitFilterBaselineBenchmark.tableSql(rows))
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

  test("every section of the split-filter baseline runs, and both sessions answer alike") {
    val view = VarkaSplitFilterBaselineBenchmark.VIEW
    val residual = VarkaSplitFilterBaselineBenchmark.RESIDUAL
    withTable {
      val r = spark.sql(
        s"""select count_if(u < 900), count_if(u < 100), count_if($residual),
           |       count_if(lu <> u), count_if(u_n is null), count_if(lu_n is null),
           |       count_if(u_n is null and u < 100), min(u), max(u)
           |from $view""".stripMargin).head()
      assert(r.getLong(0) > 1700 && r.getLong(0) < 1900, s"u < 900 keeps ${r.getLong(0)}")
      assert(r.getLong(1) > 140 && r.getLong(1) < 260, s"u < 100 keeps ${r.getLong(1)}")
      assert(r.getLong(2) > 1600 && r.getLong(2) < 1830, s"the residual keeps ${r.getLong(2)}")
      assert(r.getLong(3) === 0)
      assert(r.getLong(4) === rows / 10 && r.getLong(5) === rows / 10)
      // The nulls do not fall on u's low values only, so u_n < 100 still keeps about a tenth.
      assert(r.getLong(6) > 0 && r.getLong(6) < r.getLong(1))
      assert(r.getInt(7) === 0 && r.getInt(8) === 999)

      // The answers do not depend on the plan a section is held to, so either list does; this
      // is the list with the two-lane refusal off, the shared sessions' setting.
      val sections = VarkaSplitFilterBaselineBenchmark.sections(false).asScala.toSeq
      assert(sections.size === 14)
      val refused = VarkaSplitFilterBaselineBenchmark.sections(true).asScala.toSeq
      assert(refused.map(s => (s.title, s.query)) === sections.map(s => (s.title, s.query)))
      for (s <- sections) {
        withClue(s.title) {
          val answer = spark.sql(s.query)
          val types = if (s.query.startsWith("SELECT i, l, l2")) {
            Seq(IntegerType, LongType, LongType)
          } else {
            Seq(LongType)
          }
          assert(answer.schema.map(_.dataType) === types)
          val collected = answer.collect().toSeq
          checkAnswer(varkaSpark.sql(s.query), collected)
          // The payload is never null, and every section keeps rows and drops some.
          assert(collected.forall(row => !row.anyNull))
          assert(collected.nonEmpty && collected.size < rows)
        }
      }
    }
  }

  test("each section of the split-filter baseline fuses as its class lists it", PinsDefaults) {
    // The benchmark's own fuse check, made here too, under the emitter's default options, so
    // the classes pin the defaults; the run that lifts the benchmark's check cancels it. The
    // benchmark lists its classes at the two-lane refusal the host resolves (VARKA-503), which
    // the shared sessions leave off, so both lists are checked here by setting the refusal each
    // way. The benchmark's session also resolves a named emit level from the host where the
    // test sessions pin the unnamed one, so the classes are checked at the unnamed level and at
    // the named levels the runners and the laptop resolve (VARKA-594).
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    withTable {
      for (level <- Seq(-1, 2, 3)) forEachTwoLaneRefusal { declines =>
        withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, level.toString) {
          val sections = VarkaSplitFilterBaselineBenchmark.sections(declines).asScala.toSeq
          // Under the refusal only the long splits move, to the row path.
          val longSplits = sections.filter(_.title.startsWith("long split"))
          assert(longSplits.size === 4)
          assert(longSplits.forall(_.fusion == (if (declines) {
            VarkaSqlBenchmarkBase.Fusion.NONE
          } else {
            VarkaSqlBenchmarkBase.Fusion.SPLIT
          })))
          for (s <- sections) {
            withClue(s"${s.title}, useAVX $level, refusal $declines") {
              val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
              assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(s.fusion), plan.treeString)
              if (VarkaSplitFilterBaselineBenchmark.timesKernel(s)) {
                VarkaSplitFilterBaselineBenchmark.requireKernel(varkaSpark, s)
              }
            }
          }
        }
      }
    }
  }
}
