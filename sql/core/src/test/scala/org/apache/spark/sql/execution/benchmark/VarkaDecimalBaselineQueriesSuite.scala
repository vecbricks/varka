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
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, Expression, In, LessThan}
import org.apache.spark.sql.catalyst.plans.logical.Filter
import org.apache.spark.sql.execution.VarkaSharedSessions
import org.apache.spark.sql.types.{BooleanType, DecimalType, TimeType}

/**
 * The decimal baseline's sections, run at a small scale on both sessions in every build, so a
 * section that no longer parses or that the two sessions answer differently fails a pull request
 * rather than the runner dispatch that times it (VARKA-377 5). It checks the answers only: whether
 * a section fuses is the benchmark's own check, made when it is timed, since a lane row is meant
 * to start fusing these queries.
 *
 * Two further checks keep the table what the benchmark claims: every column has the declared
 * precision and scale, and a section over the columns with no null returns no null, so a value
 * that overflowed its type into a null (with ANSI mode off) fails here rather than going into the
 * timed run as a cheap null.
 */
class VarkaDecimalBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  test("every section of the decimal baseline runs, and both sessions answer it alike") {
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(VarkaDecimalBaselineBenchmark.tableSql(2000))
        .createOrReplaceTempView("varka_decimal")
      session.catalog.cacheTable("varka_decimal")
    }
    try {
      val expected = VarkaDecimalBaselineBenchmark.PRECISIONS.asScala.flatMap { p =>
        Seq("x", "y", "z").flatMap { role =>
          val name = role + p.suffix
          Seq(name -> DecimalType(p.precision, p.scale), name + "n" ->
            DecimalType(p.precision, p.scale))
        }
      } ++ Seq("t", "tn").map(_ -> TimeType(VarkaDecimalBaselineBenchmark.TIME_PRECISION)) ++
        // The lane pair's own columns, written out rather than derived from the helpers that
        // build the table, so that the check does not test the generator against itself.
        Seq("x20", "x20n", "y20", "y20n").map(_ -> DecimalType(20, 2)) ++
        Seq("w9", "w9n").map(_ -> DecimalType(9, 4)) ++
        Seq("w18", "w18n").map(_ -> DecimalType(10, 2)) ++
        Seq("x8", "x8n").map(_ -> DecimalType(8, 2))
      val schema = spark.table("varka_decimal").schema
      assert(schema.map(f => f.name -> f.dataType) === expected)

      val sections = VarkaDecimalBaselineBenchmark.sections().asScala
      assert(sections.size === 88)
      // The result type of each shape, as Spark's decimal rules give it: the chain widens at each
      // node and is capped at 38, and the cast is the one the precision names. The time sections
      // are time_to_seconds, DECIMAL(14, 6), and the seconds with their fraction, DECIMAL(8, 6).
      // The lane pair's five filters return the column, and its projected comparison a boolean;
      // so do the 64-bit comparisons' six shapes. The 64-bit arithmetic's three filters return
      // x9, and its projections are x9 + y9, DECIMAL(10, 2), x9 - w9, DECIMAL(12, 4), and
      // x8 * y9, DECIMAL(18, 4).
      val chain = Map(9 -> DecimalType(21, 4), 18 -> DecimalType(38, 7), 38 -> DecimalType(38, 6))
      val resultTypes = VarkaDecimalBaselineBenchmark.PRECISIONS.asScala.flatMap { p =>
        val own = DecimalType(p.precision, p.scale)
        val castTo = p.castTo.orElseThrow(() => new IllegalStateException(
          s"${p.`type`} has no cast type, so its cast section has nothing to cast to"))
        val cast = spark.sql(s"SELECT CAST(NULL AS $castTo) AS c").schema.head.dataType
        Seq(own, chain(p.precision), cast, own, own, chain(p.precision), cast, own)
      } ++ Seq.fill(2)(Seq(DecimalType(14, 6), DecimalType(8, 6))).flatten ++
        VarkaDecimalBaselineBenchmark.PAIR_PRECISIONS.asScala.flatMap { p =>
          val own = DecimalType(p.precision, p.scale)
          Seq.fill(2)(Seq.fill(5)(own) :+ BooleanType).flatten
        } ++
        Seq(DecimalType(9, 2), DecimalType(18, 4)).flatMap { own =>
          Seq.fill(2)(Seq.fill(5)(own) :+ BooleanType).flatten
        } ++
        Seq.fill(2)(Seq.fill(3)(DecimalType(9, 2)) ++
          Seq(DecimalType(10, 2), DecimalType(12, 4), DecimalType(18, 4))).flatten
      assert(sections.map(s => spark.sql(s.query).schema.head.dataType) === resultTypes)
      // The 64-bit comparisons stay within a long: coercion casts both sides of x < w to a
      // common type of 18 digits or less, DECIMAL(11, 4) and DECIMAL(18, 4), and leaves x of an
      // IN uncast, its literals being of x's scale.
      def condition(query: String): Expression = spark.sql(query).queryExecution.analyzed
        .collectFirst { case f: Filter => f.condition }.get
      val rescaled = sections.filter(_.title.contains(" x < w filter")).map { s =>
        condition(s.query).asInstanceOf[LessThan].left.dataType
      }
      assert(rescaled === Seq.fill(2)(DecimalType(11, 4)) ++ Seq.fill(2)(DecimalType(18, 4)))
      for (s <- sections.filter(_.title.contains(" IN (c1, c2, c3) filter"))) {
        withClue(s.title) {
          assert(condition(s.query).asInstanceOf[In].value.isInstanceOf[AttributeReference])
        }
      }
      for (s <- sections) {
        withClue(s.title) {
          val answer = spark.sql(s.query)
          checkAnswer(varkaSpark.sql(s.query), answer)
          if (s.title.endsWith("no nulls")) {
            assert(answer.where(answer.columns.head + " IS NULL").count() === 0)
          }
        }
      }
    } finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_decimal"))
    }
  }
}
