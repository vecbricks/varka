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

/**
 * The TIMESTAMP_NTZ baseline's sections, run at a small scale on both sessions in every build, so a
 * section that no longer parses or that the two sessions answer differently fails a pull request
 * rather than the runner dispatch that times it (VARKA-377 5). It also makes the benchmark's own
 * fuse check, that each section's plan on the Varka session has the section's fusion class, so a
 * row that changes how a section fuses sets the section's new class in the same pull request.
 */
class VarkaTimestampNtzBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  /** Runs `body` with the benchmark's table, at `rows` rows, cached on both sessions. */
  private def withTable(rows: Int)(body: => Unit): Unit = {
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(VarkaTimestampNtzBaselineBenchmark.tableSql(rows))
        .createOrReplaceTempView("varka_ntz")
      session.catalog.cacheTable("varka_ntz")
    }
    try body finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_ntz"))
    }
  }

  test("every section of the TIMESTAMP_NTZ baseline runs, and both sessions answer it alike") {
    withTable(2000) {
      val sections = VarkaTimestampNtzBaselineBenchmark.sections().asScala
      assert(sections.size === 8)
      for (s <- sections) {
        withClue(s.title) {
          checkAnswer(varkaSpark.sql(s.query), spark.sql(s.query))
        }
      }
    }
  }

  test("each section of the TIMESTAMP_NTZ baseline fuses as its class lists it", PinsDefaults) {
    // The benchmark's own fuse check, made here too, so a change to how a section fuses fails a
    // pull request rather than the runner dispatch that times it. The benchmark plans under the
    // emitter's default options, so the classes pin the defaults, and an option matrix
    // configuration, under which a section may fuse differently, cancels the test; so does the
    // run that lifts the benchmark's check.
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    withTable(100) {
      for (s <- VarkaTimestampNtzBaselineBenchmark.sections().asScala) {
        withClue(s.title) {
          val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
          assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(s.fusion))
        }
      }
    }
  }
}
