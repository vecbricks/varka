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
import org.apache.spark.sql.execution.{CoalesceExec, CollectLimitExec, GlobalLimitExec,
  LocalLimitExec, ProjectExec, RangeExec, SparkPlan, UnionExec, VarkaColumnarRule,
  VarkaColumnarToRowExec, VarkaFilterColumnarToRowExec, VarkaFilterExec, VarkaProjectExec,
  VarkaSharedSessions}
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec
import org.apache.spark.sql.execution.exchange.{ReusedExchangeExec, ShuffleExchangeExec}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{BooleanType, DataType, DecimalType, DoubleType, FloatType,
  IntegerType, LongType, ShortType, TimeType}

/**
 * The decimal baseline's sections, run at a small scale on both sessions in every build, so a
 * section that no longer parses or that the two sessions answer differently fails a pull request
 * rather than the runner dispatch that times it (VARKA-377 5). It also makes the benchmark's own
 * fuse check, that each section's plan on the Varka session has the section's fusion class and
 * that a `WHOLE` section, a filter's included, runs every batch as a kernel, so a row that changes
 * how a section fuses sets the section's new class in the same pull request; and it shows the
 * kernel check failing a section whose batches all fall back.
 *
 * Two further checks keep the table what the benchmark claims: every column has the declared
 * precision and scale, and a section over the columns with no null returns no null, nor does
 * any arithmetic operand of a filter's condition there, so a value that overflowed its type into
 * a null (with ANSI mode off) fails here rather than going into the timed run as a cheap null.
 */
class VarkaDecimalBaselineQueriesSuite extends QueryTest with VarkaSharedSessions {

  /** Runs `body` with the section's SQL settings on both sessions. */
  private def withSettings(s: VarkaDecimalBaselineBenchmark.Section)(body: => Unit): Unit = {
    def run(settings: List[(String, String)]): Unit = settings match {
      case (key, value) :: rest => withBothConf(key, value)(run(rest))
      case Nil => body
    }
    run(s.conf.asScala.toList)
  }

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
    // The benchmark times its sections at the sessions' defaults, so this test reads them at
    // the defaults too. The capped result types below hold only where an operation may cut
    // its scale, so a session that starts with the setting off fails here and not in a type.
    assert(conf.decimalOperationsAllowPrecisionLoss)
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
        Seq("x8", "x8n").map(_ -> DecimalType(8, 2)) ++
        // The widening sections' columns of rows 360 and 361, then the BIGINT.
        Seq("x15", "x15n", "y15", "y15n", "z15", "z15n").map(_ -> DecimalType(15, 2)) ++
        Seq("z20", "z20n").map(_ -> DecimalType(20, 2)) ++
        Seq("y20s4", "y20s4n").map(_ -> DecimalType(20, 4)) ++
        Seq("x38s2", "x38s2n", "y38s2", "y38s2n").map(_ -> DecimalType(38, 2)) ++
        Seq("l", "ln").map(_ -> LongType) ++
        // The cast sections' columns of row 350, after the BIGINT.
        Seq("x15s4", "x15s4n").map(_ -> DecimalType(15, 4)) ++
        Seq("x18s14", "x18s14n").map(_ -> DecimalType(18, 14)) ++
        Seq("l16", "l16n").map(_ -> LongType) ++
        // Row 362's products' columns, and row 363's cut product's last.
        Seq("y17s2", "y17s2n").map(_ -> DecimalType(17, 2)) ++
        Seq("w20", "w20n").map(_ -> DecimalType(20, 2)) ++
        Seq("w38s2", "w38s2n").map(_ -> DecimalType(38, 2)) ++
        Seq("x19s8", "x19s8n", "y19s8", "y19s8n").map(_ -> DecimalType(19, 8))
      val schema = spark.table("varka_decimal").schema
      assert(schema.map(f => f.name -> f.dataType) === expected)

      val sections = VarkaDecimalBaselineBenchmark.sections(false).asScala
      assert(sections.size === 180)
      // The result type of each shape, as Spark's decimal rules give it: the chain widens at each
      // node and is capped at 38, and the cast is the one the precision names. The time sections
      // are time_to_seconds, DECIMAL(14, 6), and the seconds with their fraction, DECIMAL(8, 6).
      // The lane pair's five filters return the column, and its projected comparison a boolean;
      // so do the 64-bit comparisons' six shapes. The 64-bit arithmetic's three filters return
      // x9, and its projections are x9 + y9, DECIMAL(10, 2), x9 - w9, DECIMAL(12, 4), and
      // x8 * y9, DECIMAL(18, 4). A remainder of two operands of one type has that type. The
      // widening sums are x20 + y20, DECIMAL(21, 2), x20 - y20s4, DECIMAL(23, 4), x38s2 + y38s2
      // capped at DECIMAL(38, 2), and l + x9 and l + x18 over l cast to DECIMAL(20, 0),
      // DECIMAL(23, 2) and DECIMAL(25, 4); the products are x15 * y15, DECIMAL(31, 4),
      // x15 * (1 - y15), DECIMAL(32, 4), l * x9, DECIMAL(30, 2), and l * x18, whose precision 39
      // Spark caps at 38 keeping the scale (VARKA-361.md 0 item 5), DECIMAL(38, 4). Their two
      // filters return x20 and x15. The casts of row 350 have their targets' types, the filter
      // returning x9. Row 362's products keep their scale: x20 * y17s2 is DECIMAL(38, 4)
      // uncapped, w20 * l over l cast to DECIMAL(20, 0) is capped to DECIMAL(38, 2),
      // x38s2 * w38s2 to DECIMAL(38, 4), and their filter returns x20. Row 363's cut their
      // scale: x19s8 * y19s8, DECIMAL(39, 16), to DECIMAL(38, 15), x38 * y38, DECIMAL(77, 20),
      // to DECIMAL(38, 6), and x38 + y38, DECIMAL(39, 10), to DECIMAL(38, 9).
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
          Seq(DecimalType(10, 2), DecimalType(12, 4), DecimalType(18, 4))).flatten ++
        Seq(DecimalType(9, 2), DecimalType(18, 4), DecimalType(38, 10), DecimalType(20, 2))
          .flatMap(t => Seq.fill(4)(t)) ++
        Seq.fill(2)(Seq(DecimalType(21, 2), DecimalType(23, 4), DecimalType(38, 2),
          DecimalType(23, 2), DecimalType(25, 4), DecimalType(20, 2), DecimalType(31, 4),
          DecimalType(32, 4), DecimalType(30, 2), DecimalType(38, 4), DecimalType(15, 2)))
          .flatten ++
        Seq.fill(2)(Seq(LongType, IntegerType, IntegerType, ShortType, DoubleType, FloatType,
          DecimalType(9, 0), DecimalType(9, 2), LongType, IntegerType, DoubleType, FloatType,
          DecimalType(18, 2), FloatType, IntegerType, IntegerType, DoubleType, FloatType,
          DecimalType(18, 2), DecimalType(18, 2))).flatten ++
        Seq.fill(2)(Seq(DecimalType(38, 4), DecimalType(38, 2), DecimalType(38, 4),
          DecimalType(20, 2))).flatten ++
        Seq.fill(2)(Seq(DecimalType(38, 15), DecimalType(38, 6), DecimalType(38, 9))).flatten
      // The one section null on most rows by design; named once, so a reworded title fails here.
      val mostlyNullTitle = "try_cast(x AS SMALLINT), past the range on most rows"
      assert(sections.count(_.title.contains(mostlyNullTitle)) === 2)
      // The cast sections of row 350: the casts to BIGINT, the casts between decimals
      // (a decimal output, written by the decimal store, 348b) and the BOOLEAN filter fuse whole
      // where the session takes a 64-bit kernel's masks, the rest stay on the row path.
      for (declines <- Seq(false, true)) {
        val casts = VarkaDecimalBaselineBenchmark.sections(declines).asScala.slice(126, 166)
        // The BIGINT column's CAST(l AS DECIMAL(18, 2)) runs on the row path (no BIGINT into a
        // decimal fuses yet), so the titles name the decimal column x.
        val fused = Seq(" AS BIGINT)", "filter CAST(x AS BOOLEAN)",
          "CAST(x AS DECIMAL(9, 0))", "CAST(x AS DECIMAL(18, 2))")
        for (s <- casts) {
          val expected =
            if (!declines && fused.exists(t => s.title.contains(t))) {
              VarkaSqlBenchmarkBase.Fusion.WHOLE
            } else {
              VarkaSqlBenchmarkBase.Fusion.NONE
            }
          assert(s.fusion === expected, s.title)
        }
      }
      // The one fused section whose guard declines every batch of its values, at each density,
      // and none where the session refuses a 64-bit kernel's masks, which leaves it NONE.
      for (declines <- Seq(false, true)) {
        val fallbacks = VarkaDecimalBaselineBenchmark.sections(declines).asScala
          .filter(_.timesFallback).map(_.title).toSeq
        val expected = if (declines) Seq.empty else Seq("no nulls", "one row in ten null")
          .map(d => s"DECIMAL(18, 4) CAST(x AS DECIMAL(18, 2)), $d")
        assert(fallbacks === expected, s"refusal $declines")
      }
      var types = Seq.empty[DataType]
      for (s <- sections) {
        withSettings(s)(types :+= spark.sql(s.query).schema.head.dataType)
      }
      assert(types === resultTypes)
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
      // x9 - w9 > 0 at DECIMAL(12, 4) and x8 * y9 < z9 at DECIMAL(18, 4). The widening filters
      // of 360 and 361 do not: x20 + y20 < z20 compares at DECIMAL(21, 2) and x15 * y15 < z15
      // at DECIMAL(31, 4), and row 362's x20 * y17s2 < z20 at DECIMAL(38, 4).
      val arithmetic = sections.filter(s => Seq(" x + y < z filter", " x - w > 0 filter",
        " x8 * y < z filter", " x * y < z filter").exists(s.title.contains))
        .map(s => comparison(s.query).left.dataType)
      assert(arithmetic === Seq.fill(2)(
        Seq(DecimalType(10, 2), DecimalType(12, 4), DecimalType(18, 4))).flatten ++
        Seq.fill(2)(Seq(DecimalType(21, 2), DecimalType(31, 4))).flatten ++
        Seq.fill(2)(DecimalType(38, 4)))
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
      // (eight), the three arithmetic filters' sums, difference and product, and the widening
      // filters' sum and product, and row 362's product.
      val operands = for {
        s <- sections if s.title.endsWith("no nulls") && s.query.contains(" WHERE ")
        e <- condition(s.query).collect {
          case e @ (_: BinaryArithmetic | _: UnaryMinus | _: Abs) => e
        }
      } yield s.title -> e.sql
      assert(operands.size === 14)
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
      // A section over the columns with no null answers no null, but for the TRY cast whose
      // operand is past the target's range on most rows by design, which answers null there.
      for (s <- sections) {
        withClue(s.title) {
          withSettings(s) {
            val answer = spark.sql(s.query).collect().toSeq
            checkAnswer(varkaSpark.sql(s.query), answer)
            val mostlyNull = s.title.contains(mostlyNullTitle)
            if (s.title.endsWith("no nulls") && !mostlyNull) {
              assert(!answer.exists(_.isNullAt(0)))
            }
            if (mostlyNull) {
              assert(answer.count(_.isNullAt(0)) > rows / 2)
            }
          }
        }
      }
    }
  }

  test("no divisor of a remainder section is zero at the benchmark's row count") {
    // A zero divisor raises under ANSI, which would stop the timed run, so the full table is
    // generated here, uncached, and its divisor columns are counted.
    val divisors = VarkaDecimalBaselineBenchmark.REMAINDER_PRECISIONS.asScala.map("y" + _.suffix)
    val zeros = spark.sql(VarkaDecimalBaselineBenchmark.tableSql(
      VarkaDecimalBaselineBenchmark.NUM_ROWS)).where(divisors.map(_ + " = 0").mkString(" OR "))
    assert(zeros.count() === 0)
  }

  test("each section of the decimal baseline fuses as its class lists it", PinsDefaults) {
    // The benchmark's own check, made here too, so a change to how a section fuses fails a pull
    // request rather than the runner dispatch that times it. The benchmark plans under the
    // emitter's default options, so the classes pin the defaults, and an option matrix
    // configuration, under which a section may fuse differently, cancels the test; so does the
    // run that lifts the benchmark's check. The benchmark lists its classes at the two-lane
    // refusal the host resolves (VARKA-503), which the shared sessions leave off, so both lists
    // are checked here by setting the refusal each way.
    assume(VarkaSqlBenchmarkBase.fuseCheck(),
      s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
    val key = SQLConf.VARKA_DECLINE_TWO_LANE_CONDITIONS.key
    val before = varkaSpark.conf.getOption(key)
    withTable(100) {
      try {
        for (declines <- Seq(false, true)) {
          varkaSpark.conf.set(key, declines.toString)
          for (s <- VarkaDecimalBaselineBenchmark.sections(declines).asScala) {
            withClue(s"${s.title}, refusal $declines") {
              withSettings(s) {
                VarkaSqlBenchmarkBase.requireSection(
                  varkaSpark, s.title, s.query, s.fusion, s.batchCheck)
              }
            }
          }
        }
      } finally {
        before match {
          case Some(v) => varkaSpark.conf.set(key, v)
          case None => varkaSpark.conf.unset(key)
        }
      }
    }
  }

  test("a WHOLE section whose batches fall back fails the kernel check", PinsDefaults) {
    // The kernel check's own test. With every kernel invocation declining its batch, a WHOLE
    // filter section and a WHOLE projection section of this baseline plan as before but run every
    // batch on the row path, and the check fails each, counting the batches handed back; without
    // the hook the same sections pass. The filter is the case the check exists for: its Varka
    // node is no projection, which the size ladders' run-time check reads alone. The sections
    // are listed at the two-lane refusal the shared sessions leave off.
    assume(VarkaSqlBenchmarkBase.fuseCheck(), "the fuse check is lifted")
    withTable(100) {
      val whole = VarkaDecimalBaselineBenchmark.sections(false).asScala
        .filter(s => s.fusion == VarkaSqlBenchmarkBase.Fusion.WHOLE && !s.timesFallback)
      val planted = Seq(
        whole.find(_.query.contains(" WHERE ")).getOrElse(fail("no WHOLE filter section")),
        whole.find(!_.query.contains(" WHERE ")).getOrElse(fail("no WHOLE projection section")))
      val handed = """handed (\d+) to another path""".r
      for (s <- planted) {
        withClue(s.title)(withSettings(s) {
          VarkaSqlBenchmarkBase.requireSection(varkaSpark, s.title, s.query, s.fusion)
          VarkaColumnarToRowExec.setDeclineKernelForTesting(true)
          val e = try {
            intercept[IllegalStateException] {
              VarkaSqlBenchmarkBase.requireSection(varkaSpark, s.title, s.query, s.fusion)
            }
          } finally {
            VarkaColumnarToRowExec.setDeclineKernelForTesting(false)
          }
          assert(e.getMessage.contains("is WHOLE but its varka on arm would time the row path"))
          assert(handed.findAllMatchIn(e.getMessage).map(_.group(1).toLong).sum > 0,
            e.getMessage)
          VarkaSqlBenchmarkBase.requireSection(varkaSpark, s.title, s.query, s.fusion)
        })
      }
    }
  }

  test("a filter of which only the inferred IS NOT NULL tests would fuse stays whole on the " +
      "row path at every emit level", PinsDefaults) {
    // VARKA-598. No lowering computes the DECIMAL(15, 2) product, so of `x * y < z` only the
    // IS NOT NULL tests Spark infers would fuse, under a row filter holding the comparison; the
    // filter declines whole, at the unnamed emit level and the named ones the runners and the
    // laptop resolve. A null test with nothing left over still fuses, and so does an IS NULL
    // beside a conjunct that does not, and both sessions answer every query alike.
    withTable(200) {
      for (level <- Seq(-1, 2, 3); n <- Seq("", "n")) {
        withClue(s"useAVX $level, columns '$n'") {
          withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, level.toString) {
            def plan(query: String): SparkPlan =
              varkaSpark.sql(query).queryExecution.executedPlan
            def varkaFilters(p: SparkPlan): Int = p.collect {
              case f: VarkaFilterExec => f
              case f: VarkaFilterColumnarToRowExec => f
            }.size
            val from = "FROM varka_decimal WHERE "
            val product = s"SELECT x15$n $from x15$n * y15$n < z15$n"
            val tested = s"SELECT x15$n $from x15$n IS NOT NULL"
            val isNull = s"SELECT x15$n $from y15$n IS NULL AND (x15$n * y15$n) IS NULL"
            assert(varkaFilters(plan(product)) === 0, plan(product).treeString)
            assert(VarkaSqlBenchmarkBase.fusion(plan(product)) ===
              Optional.of(VarkaSqlBenchmarkBase.Fusion.NONE))
            assert(VarkaSqlBenchmarkBase.fusion(plan(tested)) ===
              Optional.of(VarkaSqlBenchmarkBase.Fusion.WHOLE))
            assert(varkaFilters(plan(isNull)) === 1, plan(isNull).treeString)
            for (query <- Seq(product, tested, isNull)) {
              checkAnswer(varkaSpark.sql(query), spark.sql(query).collect().toSeq)
            }
          }
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
      // The 128-bit sum is declined, so the Varka projection evaluates it per row beside
      // the 64-bit comparison its kernel computes.
      assert(fusion("SELECT x9 < y9 AS a, x38 + y38 < y38 AS b FROM varka_decimal") ===
        Optional.of(VarkaSqlBenchmarkBase.Fusion.PARTIAL))
    }
  }

  test("a split filter whose fused part reads a value is SPLIT, and only on its own",
      PinsDefaults) {
    // VARKA-601. The DECIMAL(15, 2) product has no lowering, so beside the 64-bit comparison it
    // is the residual of a split: a row FilterExec above the to-row Varka filter. Under a sort
    // the plan has a second row operator and fits no class, and a filter of null tests beside
    // the same residual is still NULL_TESTS, not SPLIT.
    withTable(100) {
      def fusion(query: String): Optional[VarkaSqlBenchmarkBase.Fusion] =
        VarkaSqlBenchmarkBase.fusion(varkaSpark.sql(query).queryExecution.executedPlan)
      val split = "FROM varka_decimal WHERE x9 < y9 AND x15 * y15 < z15"
      val plan = varkaSpark.sql(s"SELECT x9 $split").queryExecution.executedPlan
      assert(plan.exists {
        case f: VarkaFilterColumnarToRowExec => f.wholeCondition.isDefined
        case _ => false
      }, plan.treeString)
      assert(VarkaSqlBenchmarkBase.fusion(plan) ===
        Optional.of(VarkaSqlBenchmarkBase.Fusion.SPLIT))
      assert(fusion(s"SELECT x9 $split ORDER BY x9") === Optional.empty())
      assert(fusion(s"SELECT count(*) AS c $split") === Optional.empty())
      assert(fusion("SELECT x15n FROM varka_decimal WHERE y15n IS NULL AND " +
        "(x15n * y15n) IS NULL") === Optional.of(VarkaSqlBenchmarkBase.Fusion.NULL_TESTS))
      checkAnswer(varkaSpark.sql(s"SELECT x9 $split"), spark.sql(s"SELECT x9 $split"))
    }
  }

  test("the fusion classes read a plan under adaptive execution through its stages",
      PinsDefaults) {
    withTable(100) {
      withBothConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true") {
        def plan(query: String): SparkPlan = varkaSpark.sql(query).queryExecution.executedPlan
        // An exchange makes adaptive execution wrap the plan, and the Varka filter under it is
        // planned only as its stage is created, so a search that stopped at the wrapper would
        // find no Varka node and read either plan as NONE. The filter keeps about half the
        // rows: a stage that returns none is replaced by an empty relation, with no Varka node.
        val fused = "FROM varka_decimal WHERE x9 < y9"
        val distributed = plan(s"SELECT x9 $fused DISTRIBUTE BY x9")
        assert(distributed.isInstanceOf[AdaptiveSparkPlanExec], distributed.treeString)
        assert(VarkaSqlBenchmarkBase.fusion(distributed) ===
          Optional.of(VarkaSqlBenchmarkBase.Fusion.WHOLE))
        val sorted = plan(s"SELECT x9 $fused ORDER BY x9")
        assert(sorted.isInstanceOf[AdaptiveSparkPlanExec], sorted.treeString)
        assert(VarkaSqlBenchmarkBase.fusion(sorted) === Optional.empty())
      }
    }
  }

  test("a row operator decides a plan's fusion class before a Varka projection's entries",
      PinsDefaults) {
    withTable(100) {
      def plan(query: String): SparkPlan = varkaSpark.sql(query).queryExecution.executedPlan
      val partial = "SELECT x9 < y9 AS a, x38 + y38 < y38 AS b FROM varka_decimal"
      // With no row operator, a Varka filter of null tests only beside the projection that leaves
      // its 128-bit sum comparison to rows reads as the projection does.
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

  test("a Varka projection that computes with no classification gives no class",
      PinsDefaults) {
    withTable(100) {
      // The rule plans a Varka projection only when one of its entries compiles, so this node is
      // built: the PARTIAL plan's projection keeping only the 128-bit sum comparison its kernel
      // declines. The check cannot tell what such a node fuses, so the plan fits no class, not
      // PARTIAL, which a section could list.
      val partial = varkaSpark.sql("SELECT x9 < y9 AS a, x38 + y38 < y38 AS b FROM varka_decimal")
        .queryExecution.executedPlan
      val declined = partial.transform {
        case p: VarkaColumnarToRowExec => p.copy(projectList = p.projectList.filter(_.name == "b"))
        case p: VarkaProjectExec => p.copy(projectList = p.projectList.filter(_.name == "b"))
      }
      assert(declined.collectFirst {
        case p: VarkaColumnarToRowExec => p.classification
        case p: VarkaProjectExec => p.classification
      } === Some(Optional.empty()), declined.treeString)
      assert(VarkaSqlBenchmarkBase.fusion(declined) === Optional.empty())
    }
  }

  test("the fusion classes read a subquery's plan and a reused exchange's", PinsDefaults) {
    withTable(100) {
      def plan(query: String): SparkPlan = varkaSpark.sql(query).queryExecution.executedPlan
      def isVarka(p: SparkPlan): Boolean = p match {
        case _: VarkaColumnarToRowExec | _: VarkaProjectExec | _: VarkaFilterExec |
            _: VarkaFilterColumnarToRowExec => true
        case _ => false
      }
      // The outer filter's 128-bit comparison is the row path's; the scalar subquery holds a
      // Varka filter and an aggregate over its rows. Read without the subquery, the plan would
      // read as at most its null tests fused.
      val nested = plan("SELECT x9 FROM varka_decimal WHERE x38 < " +
        "(SELECT max(y38) FROM varka_decimal WHERE x9 <=> y9)")
      assert(nested.subqueriesAll.exists(_.exists(isVarka)), nested.treeString)
      assert(VarkaSqlBenchmarkBase.fusion(nested) === Optional.empty())
      // A reused exchange is a leaf; it is read through the exchange it reuses, here one over a
      // partial aggregate, which is row work.
      val grouped = plan("SELECT x9, count(*) AS c FROM varka_decimal WHERE x9 <=> y9 GROUP BY x9")
      val exchange = grouped.collectFirst { case e: ShuffleExchangeExec => e }
      assert(exchange.isDefined, grouped.treeString)
      assert(VarkaSqlBenchmarkBase.fusion(ReusedExchangeExec(exchange.get.output, exchange.get))
        === Optional.empty())
    }
  }

  test("a limit, a union and a coalesce only move a fused section's rows", PinsDefaults) {
    withTable(100) {
      val whole = Optional.of(VarkaSqlBenchmarkBase.Fusion.WHOLE)
      val filtered = varkaSpark.sql("SELECT x9 FROM varka_decimal WHERE x9 <=> y9")
        .queryExecution.executedPlan
      assert(VarkaSqlBenchmarkBase.fusion(filtered) === whole)
      // Built over the fused plan, as the rules would place them, since a baseline's query has
      // none of them: none evaluates an expression, so the plan stays WHOLE.
      assert(VarkaSqlBenchmarkBase.fusion(LocalLimitExec(10, filtered)) === whole)
      assert(VarkaSqlBenchmarkBase.fusion(GlobalLimitExec(10, LocalLimitExec(10, filtered))) ===
        whole)
      assert(VarkaSqlBenchmarkBase.fusion(CollectLimitExec(10, filtered)) === whole)
      assert(VarkaSqlBenchmarkBase.fusion(UnionExec(Seq(filtered, filtered))) === whole)
      assert(VarkaSqlBenchmarkBase.fusion(CoalesceExec(1, filtered)) === whole)
      // A range is a leaf, exempt like the cached scan: a source both arms read alike.
      val range = varkaSpark.range(10).queryExecution.executedPlan.collectFirst {
        case r: RangeExec => r
      }
      assert(range.isDefined)
      assert(VarkaSqlBenchmarkBase.fusion(UnionExec(Seq(filtered, range.get))) === whole)
      // A limit that sorts first is row work.
      assert(VarkaSqlBenchmarkBase.fusion(varkaSpark.sql(
        "SELECT x9 FROM varka_decimal WHERE x9 <=> y9 ORDER BY x9 LIMIT 10")
        .queryExecution.executedPlan) === Optional.empty())
    }
  }
}
