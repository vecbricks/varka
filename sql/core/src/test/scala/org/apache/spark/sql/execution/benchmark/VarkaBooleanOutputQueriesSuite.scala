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
import org.apache.spark.sql.types.{BooleanType, ByteType, DataType, DateType, LongType}

/**
 * The boolean-output benchmark's sections, run at a small scale on both sessions in every build,
 * so a section that no longer parses or that the two sessions answer differently fails a pull
 * request rather than the runner dispatch that times it (VARKA-27 5). Whether an output section
 * fuses is the benchmark's own check, made when it is timed, since row 27 is meant to start fusing
 * the output sections; the filter controls' plans are checked here too, on both sides of the
 * two-lane refusal, so the expectation the benchmark holds them to is right on every host.
 *
 * Two further checks keep the table and the sections what the benchmark claims: every column has
 * its declared type, every output section returns a boolean and every filter control the type of
 * the column it selects, and a section over the columns with no null returns no null, so a
 * condition that turned three-valued by mistake fails here rather than timing a different shape.
 */
class VarkaBooleanOutputQueriesSuite extends QueryTest with VarkaSharedSessions {

  test("every section of the boolean-output benchmark runs, and both sessions answer it alike") {
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(VarkaBooleanOutputBenchmark.tableSql(2000))
        .createOrReplaceTempView("varka_boolean")
      session.catalog.cacheTable("varka_boolean")
    }
    try {
      val types: Map[String, DataType] =
        Map("DATE" -> DateType, "BIGINT" -> LongType, "TINYINT" -> ByteType,
          "BOOLEAN" -> BooleanType)
      val plain = VarkaBooleanOutputBenchmark.PAIRS.asScala.toSeq.flatMap { p =>
        Seq(p.first -> types(p.`type`), p.second -> types(p.`type`))
      }
      val expected = plain ++ plain.map { case (name, t) => (name + "n") -> t }
      val schema = spark.table("varka_boolean").schema
      assert(schema.map(f => f.name -> f.dataType) === expected)
      // Every column with nulls is null on one row in ten, and the plain ones never.
      for ((name, _) <- plain) {
        val nulls = spark.sql(s"SELECT count_if(${name}n IS NULL), count_if($name IS NULL) " +
          "FROM varka_boolean").head()
        assert((nulls.getLong(0), nulls.getLong(1)) === (200L, 0L), name)
      }

      // The answers do not depend on the plan a control is held to, so either expectation does.
      val sections = VarkaBooleanOutputBenchmark.sections(false).asScala.toSeq
      assert(sections.size === 24)
      assert(sections.count(_.control) === 12)
      // An output section answers the condition, a boolean; a control answers the column it
      // selects, whose type the table's schema gives.
      val resultTypes = VarkaBooleanOutputBenchmark.CONDITIONS.asScala.toSeq.flatMap { c =>
        val selected = types(VarkaBooleanOutputBenchmark.PAIRS.asScala
          .find(p => p.first == c.selected).get.`type`)
        Seq(BooleanType, selected, BooleanType, selected)
      }
      assert(sections.map(s => spark.sql(s.query).schema.head.dataType) === resultTypes)
      for (s <- sections) {
        withClue(s.title) {
          val answer = spark.sql(s.query)
          checkAnswer(varkaSpark.sql(s.query), answer)
          if (s.title.endsWith("no nulls")) {
            assert(answer.where(answer.columns.head + " IS NULL").count() === 0)
          }
          // An output is neither constant nor nearly so: it is true on between a tenth and five
          // sixths of the rows (a comparison about half, an AND about a quarter, less with nulls),
          // and null on at most a quarter.
          if (!s.control) {
            val c = answer.columns.head
            val counts = answer.selectExpr(s"count_if($c)", s"count_if($c IS NULL)").head()
            assert(counts.getLong(0) > 2000 / 10 && counts.getLong(0) < 2000 * 5 / 6, counts)
            assert(counts.getLong(1) <= 2000 / 4, counts)
          }
        }
      }
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_boolean"))
    }
  }

  test("each filter control has the plan the benchmark expects, with and without the two-lane " +
      "refusal") {
    // The benchmark runs with the refusal at the host's default, so on the 128-bit companion's
    // host its long controls decline (VARKA-503); this checks both expectations on any host by
    // setting the refusal, which the shared sessions leave off, each way.
    val key = SQLConf.VARKA_DECLINE_TWO_LANE_CONDITIONS.key
    varkaSpark.sql(VarkaBooleanOutputBenchmark.tableSql(2000))
      .createOrReplaceTempView("varka_boolean")
    varkaSpark.catalog.cacheTable("varka_boolean")
    val before = varkaSpark.conf.getOption(key)
    try {
      for (declines <- Seq(true, false)) {
        varkaSpark.conf.set(key, declines.toString)
        for (s <- VarkaBooleanOutputBenchmark.sections(declines).asScala if s.control) {
          val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
          assert(VarkaBooleanOutputBenchmark.matches(plan, s.expect),
            s"${s.title}, refusal $declines: expected ${s.expect}\n${plan.treeString}")
        }
      }
    } finally {
      before match {
        case Some(v) => varkaSpark.conf.set(key, v)
        case None => varkaSpark.conf.unset(key)
      }
      varkaSpark.catalog.uncacheTable("varka_boolean")
    }
  }
}
