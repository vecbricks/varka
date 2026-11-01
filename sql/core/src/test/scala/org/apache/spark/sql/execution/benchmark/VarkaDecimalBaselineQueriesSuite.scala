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
import org.apache.spark.sql.catalyst.expressions.{Abs, Alias, AttributeReference, BinaryArithmetic,
  BinaryComparison, Cast, Expression, In, UnaryMinus}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix.PinsDefaults
import org.apache.spark.sql.catalyst.plans.logical.Filter
import org.apache.spark.sql.execution.{ProjectExec, VarkaColumnarRule, VarkaColumnarToRowExec,
  VarkaFilterExec, VarkaProjectExec, VarkaSharedSessions}
import org.apache.spark.sql.types.{BooleanType, DataType, DecimalType, TimeType}

/**
 * The decimal baseline's sections, run at a small scale on both sessions in every build, so a
 * section that no longer parses or that the two sessions answer differently fails a pull request
 * rather than the runner dispatch that times it (VARKA-377 5). It also makes the benchmark's own
 * fuse check, that each section's plan on the Varka session has the section's fusion class, so a
 * row that changes how a section fuses sets the section's new class in the same pull request.
 *
 * Two further checks keep the table what the benchmark claims: every column has the declared
 * precision and scale, and a section over the columns with no null returns no null, nor does
 * any arithmetic operand of a filter's condition there, so a value that overflowed its type into
 * a null (with ANSI mode off) fails here rather than going into the timed run as a cheap null.
 */
class VarkaDecimalBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  /** Runs `body` with the benchmark's table, at `rows` rows, cached on both sessions. */
  private def withTable(rows: Int)(body: => Unit): Unit = {
    for (session <- Seq(spark, varkaSpark)) {
      session.sql(VarkaDecimalBaselineBenchmark.tableSql(rows))
        .createOrReplaceTempView("varka_decimal")
      session.catalog.cacheTable("varka_decimal")
    }
    try body finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_decimal"))
    }
  }

  test("every section of the decimal baseline runs, and both sessions answer it alike") {
    val rows = 2000
    withTable(rows) {
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
      def condition(query: String): Expression = spark.sql(query).queryExecution.analyzed
        .collectFirst { case f: Filter => f.condition }.get
      def comparison(query: String): BinaryComparison = condition(query) match {
        case c: BinaryComparison => c
        case other => fail(s"$other is not a comparison")
      }
      // A side of a comparison of two columns: the column itself when it has the common type,
      // or a cast of it to the common type when it does not.
      def side(e: Expression, common: DataType): String = e match {
        case a: AttributeReference if a.dataType == common => "column"
        case Cast(a: AttributeReference, `common`, _, _) if a.dataType != common => "cast"
        case other => fail(s"$other is neither a column of $common nor a cast of one to it")
      }
      // The 64-bit comparisons stay within a long: coercion takes x < w to a common type of 18
      // digits or less, DECIMAL(11, 4) at precision 9, where both sides are cast to it, and
      // DECIMAL(18, 4) at precision 18, x18's own type, where only w18 is.
      val rescaled = sections.filter(_.title.contains(" x < w filter")).map { s =>
        val c = comparison(s.query)
        val common = c.left.dataType
        (common, side(c.left, common), side(c.right, common))
      }
      assert(rescaled === Seq.fill(2)((DecimalType(11, 4), "cast", "cast")) ++
        Seq.fill(2)((DecimalType(18, 4), "column", "cast")))
      // So do the three arithmetic filters of 349: x9 + y9 < z9 compares at DECIMAL(10, 2),
      // x9 - w9 > 0 at DECIMAL(12, 4) and x8 * y9 < z9 at DECIMAL(18, 4).
      val arithmetic = sections.filter(s => Seq(" x + y < z filter", " x - w > 0 filter",
        " x8 * y < z filter").exists(s.title.contains)).map(s => comparison(s.query).left.dataType)
      assert(arithmetic === Seq.fill(2)(
        Seq(DecimalType(10, 2), DecimalType(12, 4), DecimalType(18, 4))).flatten)
      // No IN casts its column, its literals being of the column's scale: the eight IN filters,
      // the lane pair's at precisions 38 and 20 and the 64-bit ones at 9 and 18, each at both
      // densities.
      val ins = sections.filter(_.title.contains(" IN (c1, c2, c3) filter"))
      assert(ins.size === 8)
      for (s <- ins) {
        withClue(s.title) {
          assert(condition(s.query).asInstanceOf[In].value.isInstanceOf[AttributeReference])
        }
      }
      // A filter over the columns with no null returns its column, which says nothing of the
      // condition, so each arithmetic operand of its condition is run as a value instead and
      // must return no null: the negation and abs of the lane pair's and the 64-bit filters
      // (eight) and the three arithmetic filters' sums, difference and product.
      val operands = for {
        s <- sections if s.title.endsWith("no nulls") && s.query.contains(" WHERE ")
        e <- condition(s.query).collect {
          case e @ (_: BinaryArithmetic | _: UnaryMinus | _: Abs) => e
        }
      } yield s.title -> e.sql
      assert(operands.size === 11)
      // Each operand is projected and its values collected, one per row, rather than filtered
      // on IS NULL: the optimizer folds that filter to false for an operand it takes as never
      // null, and the check would then pass without evaluating the operand at all.
      for ((title, operand) <- operands) {
        withClue(s"$title: $operand") {
          val values = spark.sql(s"SELECT $operand AS a FROM varka_decimal").collect()
          assert(values.length === rows)
          assert(!values.exists(_.isNullAt(0)))
        }
      }
      for (s <- sections) {
        withClue(s.title) {
          val answer = spark.sql(s.query).collect().toSeq
          checkAnswer(varkaSpark.sql(s.query), answer)
          if (s.title.endsWith("no nulls")) {
            assert(!answer.exists(_.isNullAt(0)))
          }
        }
      }
    }
  }

  test("each section of the decimal baseline fuses as its class lists it", PinsDefaults) {
    // The benchmark's own check, made here too, so a change to how a section fuses fails a pull
    // request rather than the runner dispatch that times it. The benchmark plans under the
    // emitter's default options, so the classes pin the defaults, and an option matrix
    // configuration, under which a section may fuse differently, cancels the test; so does the
    // run that lifts the benchmark's check.
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    withTable(100) {
      for (s <- VarkaDecimalBaselineBenchmark.sections().asScala) {
        withClue(s.title) {
          val plan = varkaSpark.sql(s.query).queryExecution.executedPlan
          assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(s.fusion))
        }
      }
    }
  }

  test("the fusion classes tell a row operator and a declined entry from a fused section",
      PinsDefaults) {
    withTable(100) {
      def fusion(query: String): Optional[VarkaSqlBenchmarkBase.Fusion] =
        VarkaSqlBenchmarkBase.fusion(varkaSpark.sql(query).queryExecution.executedPlan)
      val fused = "FROM varka_decimal WHERE x9 <=> y9"
      assert(fusion(s"SELECT x9 $fused") === Optional.of(VarkaSqlBenchmarkBase.Fusion.WHOLE))
      // The rule folds a rename above the Varka filter into the filter's to-row node, so the plan
      // keeps no row projection and the rename is part of the fused node.
      val renamed = varkaSpark.sql(s"SELECT x9 AS r $fused").queryExecution.executedPlan
      assert(!renamed.exists(_.isInstanceOf[ProjectExec]), renamed.treeString)
      assert(VarkaSqlBenchmarkBase.fusion(renamed) ===
        Optional.of(VarkaSqlBenchmarkBase.Fusion.WHOLE))
      // A row projection that only renames a column does reach the classifier where a rule keeps
      // it, so it is built here over the fused plan: it computes nothing, and the plan stays
      // WHOLE, while one that computes over the column is row work beside the fused filter.
      val filtered = varkaSpark.sql(s"SELECT x9 $fused").queryExecution.executedPlan
      val x9 = filtered.output.head
      assert(VarkaSqlBenchmarkBase.fusion(ProjectExec(Seq(Alias(x9, "r")()), filtered)) ===
        Optional.of(VarkaSqlBenchmarkBase.Fusion.WHOLE))
      assert(VarkaSqlBenchmarkBase.fusion(ProjectExec(Seq(x9), filtered)) ===
        Optional.of(VarkaSqlBenchmarkBase.Fusion.WHOLE))
      assert(VarkaSqlBenchmarkBase.fusion(ProjectExec(Seq(Alias(UnaryMinus(x9), "r")()),
        filtered)) === Optional.empty())
      // An aggregate or a sort above it evaluates over rows, so the plan fits no class.
      assert(fusion(s"SELECT count(*) AS c $fused") === Optional.empty())
      assert(fusion(s"SELECT x9 $fused ORDER BY x9") === Optional.empty())
      // The 128-bit comparison is declined, so the Varka projection evaluates it per row beside
      // the 64-bit comparison its kernel computes.
      assert(fusion("SELECT x9 < y9 AS a, x38 < y38 AS b FROM varka_decimal") ===
        Optional.of(VarkaSqlBenchmarkBase.Fusion.PARTIAL))
    }
  }

  test("a row operator decides a plan's fusion class before a Varka projection's entries",
      PinsDefaults) {
    withTable(100) {
      def plan(query: String) = varkaSpark.sql(query).queryExecution.executedPlan
      val partial = "SELECT x9 < y9 AS a, x38 < y38 AS b FROM varka_decimal"
      // With no row operator, a Varka filter of null tests only beside the projection that leaves
      // its 128-bit comparison to rows reads as the projection does.
      val nullTested = plan(s"$partial WHERE x9n IS NOT NULL")
      assert(nullTested.exists(_.isInstanceOf[VarkaFilterExec]), nullTested.treeString)
      assert(VarkaSqlBenchmarkBase.fusion(nullTested) ===
        Optional.of(VarkaSqlBenchmarkBase.Fusion.PARTIAL))
      // A sort above the same projection is a row operator, and with one the plan is NULL_TESTS
      // only when every Varka node is a null-test filter, so it fits no class, not PARTIAL.
      val sorted = plan(s"SELECT a, b FROM ($partial) ORDER BY a")
      assert(sorted.exists(_.isInstanceOf[VarkaColumnarToRowExec]), sorted.treeString)
      assert(VarkaSqlBenchmarkBase.fusion(sorted) === Optional.empty())
    }
  }

  test("a Varka projection that only narrows a Varka filter's output computes nothing",
      PinsDefaults) {
    withTable(100) {
      // The rule's pre stage plans this pair for a projection that forwards a column of a Varka
      // filter's output; a columnar consumer keeps it, and a row consumer has the post stage fold
      // it into the filter's to-row node. The pair is built here as the pre stage builds it.
      val planned = varkaSpark.sql("SELECT x9 FROM varka_decimal WHERE x9 <=> y9")
        .queryExecution.sparkPlan
      val plan = varkaSpark.withActive(VarkaColumnarRule.preColumnarTransitions(planned))
      assert(plan.collectFirst {
        case p @ VarkaProjectExec(_, _: VarkaFilterExec, _) => p
      }.isDefined, plan.treeString)
      assert(VarkaSqlBenchmarkBase.fusion(plan) === Optional.of(VarkaSqlBenchmarkBase.Fusion.WHOLE))
    }
  }
}
