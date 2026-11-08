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
 * The math baseline's sections, run at a small scale on both sessions in every build, so a
 * section that no longer parses, raises, or that the two sessions answer differently fails a pull
 * request rather than the runner dispatch that times it (VARKA-345 4). It also makes the
 * benchmark's own fuse check, that each section's plan on the Varka session has the section's
 * fusion class, so a row that changes how a section fuses sets the section's new class in the same
 * pull request.
 *
 * The fixture is the benchmark's table over its first 2000 ids. Its columns are hashes of the id,
 * so these rows already reach the ends of the ranges the benchmark's javadoc states, which the
 * suite checks before it runs the sections (bounds the table's construction guarantees for every
 * id, which these rows only spot-check). A section over the columns with no null must answer no
 * null and only finite values, so a value outside a function's ordinary domain fails here rather
 * than going into the timed run as a cheap null or NaN. A section over the columns with one row in
 * ten null must answer exactly the rows where one of its inputs is null, each column null at its
 * own rows.
 */
class VarkaMathBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 2000

  private val view = VarkaMathBaselineBenchmark.VIEW

  /** The benchmark's table over the first `rows` ids, built and cached once on both sessions. */
  override protected def beforeAll(): Unit = {
    super.beforeAll()
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(VarkaMathBaselineBenchmark.tableSql(rows)).createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
    }
  }

  override protected def afterAll(): Unit = {
    try {
      for (session <- Seq(spark, varkaSpark)
          if session != null && session.catalog.tableExists(view)) {
        session.catalog.uncacheTable(view)
        session.catalog.dropTempView(view)
      }
    } finally {
      super.afterAll()
    }
  }

  test("every section of the math baseline runs, and both sessions answer alike") {
    val r = spark.sql(
      s"""select min(x), max(x), min(y), max(y), min(p), max(p),
         |       count(distinct signum(x), signum(y)),
         |       count_if(abs(x) > pi() / 4),
         |       count_if(x_n is null), count_if(y_n is null), count_if(p_n is null),
         |       count_if(x_n is null and y_n is null), count_if(p_n is null and y_n is null)
         |from $view""".stripMargin).head()
    assert(r.getDouble(0) >= -10 && r.getDouble(0) < -9.9)
    assert(r.getDouble(1) < 10 && r.getDouble(1) > 9.9)
    assert(r.getDouble(2) >= -10 && r.getDouble(2) < -9.9)
    assert(r.getDouble(3) < 10 && r.getDouble(3) > 9.9)
    assert(r.getDouble(4) >= 1e-6 && r.getDouble(4) < 2e-6)
    assert(r.getDouble(5) < 1e6 && r.getDouble(5) > 5e5)
    // All four quadrants of atan2, and no zero.
    assert(r.getLong(6) === 4)
    assert(r.getLong(7) > rows * 9 / 10)
    assert(r.getLong(8) === rows / 10 && r.getLong(9) === rows / 10)
    assert(r.getLong(10) === rows / 10)
    // Each column is null at its own rows, so a two-column section's nulls add up: atan2 and
    // hypot read x_n and y_n, pow reads p_n and y_n.
    assert(r.getLong(11) === 0 && r.getLong(12) === 0)

    val sections = VarkaMathBaselineBenchmark.sections().asScala.toSeq
    assert(sections.size === 24, sections.map(_.title).mkString("sections:\n", "\n", ""))
    assert(sections.map(_.title).distinct.size === sections.size)
    for (s <- sections) {
      withClue(s.title) {
        // One job a session: the row engine's answer is collected once, Varka's is checked
        // against it, and the nulls and non-finite values are counted from it in memory.
        val df = spark.sql(s.query)
        assert(df.schema.map(_.name) === Seq("a"))
        assert(df.schema.head.dataType.sql === "DOUBLE")
        val answer = df.collect().toSeq
        checkAnswer(varkaSpark.sql(s.query), answer)
        val nulls = if (s.nullInputs) s.columns * rows / 10 else 0
        assert(answer.count(_.isNullAt(0)) === nulls)
        assert(answer.filterNot(_.isNullAt(0)).count(row => !row.getDouble(0).isFinite) === 0)
      }
    }
  }

  test("each section of the math baseline fuses as its class lists it", PinsDefaults) {
    // The benchmark's own fuse check, made here too, so a change to how a section fuses fails a
    // pull request rather than the runner dispatch that times it. The benchmark plans under the
    // emitter's default options, so the classes pin the defaults, and an option matrix
    // configuration, under which a section may fuse differently, cancels the test; so does the
    // run that lifts the benchmark's check.
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    // Every section is NONE, so a check that could not see a Varka node anywhere would pass
    // each of them; a comparison of the two DOUBLE columns fuses, and fails such a check here.
    assert(VarkaSqlBenchmarkBase.fusedPlan(varkaSpark, s"SELECT x FROM $view WHERE x < y")
      .isPresent)
    for (s <- VarkaMathBaselineBenchmark.sections().asScala) {
      withClue(s.title) {
        val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
        assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(s.fusion))
      }
    }
  }
}
