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

package org.apache.spark.sql.execution

import org.apache.spark.sql.{DataFrame, QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, Attribute, AttributeReference,
  BindReferences, Cast, Coalesce, Expression, GreaterThan, Greatest, IsNotNull, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaHostIsa,
  VarkaSameAnswer, VarkaTestWatchdog}
import org.apache.spark.sql.catalyst.plans.logical.Project
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DataType, DateType, DoubleType, FloatType, IntegerType,
  LongType, ShortType, StructField, StructType}

/**
 * The floating lanes' first nodes, end to end: a `DOUBLE` or `FLOAT` column read from Varka's
 * Arrow cache, chosen by a null test or picked by `coalesce`, and written back - checked against
 * the row engine over three null patterns.
 *
 * These nodes move a value's bits and never read its meaning, so the check is on the bits:
 * `checkAnswer` compares doubles with `equals`, which takes every NaN as one value, and so it
 * cannot see a store that loses a NaN's payload. Each answer is therefore also compared by
 * `doubleToRawLongBits` and `floatToRawIntBits`, row by row. The fixtures carry both zeros, both
 * infinities, NaN, the smallest subnormal and the extremes, so a lowering that converted a value
 * instead of moving it would change at least one of them, and one fixture carries NaNs of
 * several payloads, so a pick that returned the wrong one of two NaNs would show.
 *
 * The comparisons, `<=>`, `greatest`, `least`, `IN`, `isnan` and `nanvl` are lowered in
 * Spark's order and checked over every pair of the special values. The arithmetic (VARKA-342)
 * is checked over the same pairs, in every mode, a NaN whose payload is open by its provenance
 * compared by being NaN (`VarkaSameAnswer`'s row level). What still declines - the casts, the
 * remainder, a floating value beside an integral one in a kernel - is pinned at the end, with the
 * query still answering as the row engine does.
 */
class VarkaFloatingLaneSuite extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

  /**
   * The three fixtures - nulls mixed in, none, all - each a cached view of `d`, `d2` (dates, for
   * the shapes that mix lanes), `x1`, `x2` (`DOUBLE`) and `f1`, `f2` (`FLOAT`).
   */
  private val fixtures: Seq[(String, String)] = {
    def dbl(v: String) = s"CAST('$v' AS DOUBLE)"
    def flt(v: String) = s"CAST('$v' AS FLOAT)"
    val pairs = Seq(
      ("0.0", "-0.0"), ("-0.0", "0.0"), ("NaN", "1.5"), ("Infinity", "-Infinity"),
      ("-Infinity", "NaN"), ("4.9E-324", "-2.5"), ("1.7976931348623157E308", "-0.0"),
      ("-1.7976931348623157E308", "3.0"))
    val floatPairs = Seq(
      ("-0.0", "0.0"), ("NaN", "NaN"), ("1.5", "-1.5"), ("1.4E-45", "Infinity"),
      ("-Infinity", "-0.0"), ("3.4028235E38", "NaN"), ("0.0", "2.5"), ("-3.4028235E38", "1.0"))
    val dates = Seq(("2024-01-31", "2024-02-29"), ("2024-02-29", "2024-02-29"),
      ("2023-12-27", "2024-01-02"), ("2021-01-01", "2020-12-31"), ("2021-06-01", "2021-06-01"),
      ("2021-03-15", "2021-03-14"), ("1969-12-31", "1970-01-01"), ("2000-02-29", "1999-12-31"))
    val dense = dates.zip(pairs).zip(floatPairs).map { case (((d, d2), (x1, x2)), (f1, f2)) =>
      Seq(s"DATE'$d'", s"DATE'$d2'", dbl(x1), dbl(x2), flt(f1), flt(f2))
    }
    val mixed = dense.zipWithIndex.map { case (cells, k) =>
      // A different column is null on each row, so every column meets a null somewhere.
      cells.updated(k % cells.size, "NULL")
    }
    val allNull = Seq.fill(8)(Seq("CAST(NULL AS DATE)", "CAST(NULL AS DATE)",
      "CAST(NULL AS DOUBLE)", "CAST(NULL AS DOUBLE)", "CAST(NULL AS FLOAT)",
      "CAST(NULL AS FLOAT)"))
    def view(rows: Seq[Seq[String]], where: String = ""): String =
      s"""SELECT * FROM VALUES ${rows.map(_.mkString("(", ", ", ")")).mkString(",\n  ")}
         |AS v(d, d2, x1, x2, f1, f2)$where""".stripMargin
    // As in the long lane's fixtures: the dense view carries an all-null row and filters it out,
    // so its columns stay nullable and the optimizer leaves the null tests to Varka.
    // Every ordered pair of the special values, so each operator meets NaN against NaN and
    // against every other value, the two zeros both ways round, the infinities, the smallest
    // subnormal and the extremes; and a null on each side.
    val specials = Seq("NaN", "-0.0", "0.0", "Infinity", "-Infinity", "1.5", "-2.5")
    val doubleSpecials = specials ++ Seq("4.9E-324", "1.7976931348623157E308")
    val floatSpecials = specials ++ Seq("1.4E-45", "3.4028235E38")
    val table = doubleSpecials.indices.flatMap { i =>
      doubleSpecials.indices.map { j =>
        Seq("DATE'2024-01-01'", "DATE'2024-01-02'", dbl(doubleSpecials(i)),
          dbl(doubleSpecials(j)), flt(floatSpecials(i)), flt(floatSpecials(j)))
      }
    } ++ Seq(
      Seq("DATE'2024-01-01'", "DATE'2024-01-02'", "NULL", dbl("NaN"), "NULL", flt("NaN")),
      Seq("DATE'2024-01-01'", "DATE'2024-01-02'", dbl("0.0"), "NULL", flt("0.0"), "NULL"))
    Seq("varka_float_pairs" -> view(table),
      "varka_float_mixed" -> view(mixed),
      "varka_float_dense" -> view(dense :+ allNull.head,
        "\nWHERE x1 IS NOT NULL AND x2 IS NOT NULL AND f1 IS NOT NULL AND f2 IS NOT NULL"),
      "varka_float_nulls" -> view(allNull))
  }

  /**
   * NaNs of three payloads and both signs beside each other, a zero, a value and a null, in both
   * kinds: `CAST('NaN' ...)` gives only the canonical NaN, so this is the fixture on which a pick
   * that returned the wrong one of two NaNs, or a store that quieted a payload, would show. The
   * rows are built from their bits, which the cache keeps as they are.
   */
  private val payloads = "varka_float_payloads"

  private def payloadRows: Seq[Row] = {
    def dbl(bits: Long): java.lang.Double = java.lang.Double.longBitsToDouble(bits)
    def flt(bits: Int): java.lang.Float = java.lang.Float.intBitsToFloat(bits)
    val (na, nb, nn) = (dbl(0x7ff8000000000001L), dbl(0x7ff8000000000002L),
      dbl(0xfff8000000000000L))
    val (fa, fb, fn) = (flt(0x7fc00001), flt(0x7fc00002), flt(0xffc00000))
    val day = java.sql.Date.valueOf("2024-01-01")
    Seq[(java.lang.Double, java.lang.Double, java.lang.Float, java.lang.Float)](
      (na, nb, fa, fb), (nb, na, fb, fa), (nn, na, fn, fa), (na, nn, fa, fn), (na, null, fa, null),
      (null, nb, null, fb), (na, -0.0, fa, -0.0f), (0.0, nb, 0.0f, fb), (1.5, na, 1.5f, fa)
    ).map { case (x1, x2, f1, f2) => Row(day, day, x1, x2, f1, f2) }
  }

  private val payloadSchema = StructType(Seq(StructField("d", DateType),
    StructField("d2", DateType), StructField("x1", DoubleType), StructField("x2", DoubleType),
    StructField("f1", FloatType), StructField("f2", FloatType)))

  /** Every fixture's name, the payload one included. */
  private val names: Seq[String] = fixtures.map(_._1) :+ payloads

  private def cacheFixtures(session: SparkSession): Unit = {
    fixtures.foreach { case (name, sql) =>
      session.sql(sql).createOrReplaceTempView(name)
      session.catalog.cacheTable(name)
    }
    session.createDataFrame(spark.sparkContext.parallelize(payloadRows, 1), payloadSchema)
      .createOrReplaceTempView(payloads)
    session.catalog.cacheTable(payloads)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cacheFixtures(spark)
    cacheFixtures(varkaSpark)
  }

  /** The answer's cells, floating ones as their raw bits, so a NaN's payload and a sign count. */
  private def bits(df: DataFrame): Seq[Seq[Any]] = df.collect().toSeq.map(_.toSeq.map {
    case d: Double => ("double", java.lang.Double.doubleToRawLongBits(d))
    case f: Float => ("float", java.lang.Float.floatToRawIntBits(f))
    case other => other
  })

  /**
   * One query on every fixture: fused, the same answer bit for bit, and a kernel ran. A filter
   * is run with the cache's batch pruning off: Spark's batch statistics leave NaN out, so a
   * filter that matches NaN rows could prune their batches on both sides (SPARK-58482), and the
   * kernel would never see the rows it is tested on.
   */
  private def checkFused(query: String): Unit = names.foreach { fixture =>
    val q = query.replace("$t", fixture)
    withBothConf(SQLConf.IN_MEMORY_PARTITION_PRUNING.key, "false") {
      val actual = varkaSpark.sql(q)
      val expected = expectedSql(q, actual)
      val plan = actual.queryExecution.executedPlan
      withClue(s"$fixture: $q") {
        assertFused(plan)
        checkAnswer(actual, expected)
        assert(bits(actual).sortBy(_.toString) === bits(expected).sortBy(_.toString))
        // After the answer: the metric counts the batches the action above ran. With pruning
        // off the scan hands every batch on, so a kernel runs even where nothing is selected.
        assertKernelsRan(plan)
      }
    }
  }

  /**
   * Varka's rows against the row engine's at `VarkaSameAnswer`'s row level: bit for bit, but for
   * two NaNs where the row engine's is open by its provenance on that row - an add or a multiply
   * over two NaNs of different bits, whose payload Java does not fix (it depends on the tier and
   * on how C2 ordered the operands, `VARKA-342.md` 0.6 and 5.1), or a node that passes one on.
   * The provenance is read off the row engine's optimized projection, bound to the rows its
   * child reads; both sides read one cached fixture through one projection, in one order.
   */
  private def checkRowLevel(actual: DataFrame, expected: DataFrame): Unit = {
    val (projection, child) = expected.queryExecution.optimizedPlan match {
      case Project(list, c) => (list, c)
      case other => fail(s"not a projection:\n$other")
    }
    val bound = projection.map(e => BindReferences.bindReference(e: Expression, child.output))
    val inputs = org.apache.spark.sql.classic.Dataset.ofRows(spark, child)
      .queryExecution.toRdd.map(_.copy()).collect()
    val toRow = CatalystTypeConverters.createToCatalystConverter(actual.schema)
    val types = actual.schema.map(_.dataType).toArray
    val (want, got) = (expected.collect(), actual.collect())
    assert(want.length === got.length && inputs.length === want.length)
    for (i <- want.indices) {
      val difference = VarkaSameAnswer.sameRow(toRow(want(i)).asInstanceOf[InternalRow],
        toRow(got(i)).asInstanceOf[InternalRow], types,
        o => VarkaSameAnswer.openNaN(bound(o), inputs(i)))
      assert(difference === null, s"row $i of ${inputs(i)}: $difference")
    }
  }

  /** [[checkFused]] for an arithmetic form, under `ansi`, at the row level ([[checkRowLevel]]). */
  private def checkFusedArith(query: String, ansi: Boolean): Unit =
      names.foreach { fixture =>
    val q = query.replace("$t", fixture)
    withAnsi(ansi) {
      val actual = varkaSpark.sql(q)
      val expected = expectedSql(q, actual)
      val plan = actual.queryExecution.executedPlan
      withClue(s"$fixture (ansi=$ansi): $q") {
        assertFused(plan)
        checkAnswer(actual, expected)
        checkRowLevel(actual, expected)
        assertKernelsRan(plan)
      }
    }
  }

  /** One query on every fixture: no Varka node, and the row engine's answer. */
  private def checkDeclined(query: String): Unit = names.foreach { fixture =>
    val q = query.replace("$t", fixture)
    val actual = varkaSpark.sql(q)
    withClue(s"$fixture: $q") {
      assertNotFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, expectedSql(q, actual))
    }
  }

  // -----------------------------------------------------------------------------------------
  // The nodes that move bits. A literal is its bits in the table of its width, reinterpreted
  // where the kernel broadcasts it, so `-0.0D` keeps its sign and the NaN literal stays NaN.
  // -----------------------------------------------------------------------------------------

  Seq(
    "coalesce(x1, x2)",
    "coalesce(x1, -0.0D)",
    "coalesce(x2, CAST('NaN' AS DOUBLE))",
    "if(x1 IS NULL, x2, x1)",
    "CASE WHEN x1 IS NULL THEN 1.5D WHEN x2 IS NULL THEN x1 ELSE x2 END",
    "nvl(x2, CAST('-Infinity' AS DOUBLE))"
  ).foreach { expr =>
    test(s"DOUBLE: SELECT $expr fuses and matches the row engine bit for bit") {
      checkFused(s"SELECT $expr AS v FROM $$t")
    }
  }

  Seq(
    "coalesce(f1, f2)",
    "coalesce(f1, -0.0F)",
    "if(f2 IS NOT NULL, f2, 2.5F)",
    "CASE WHEN f1 IS NULL THEN f2 ELSE f1 END"
  ).foreach { expr =>
    test(s"FLOAT: SELECT $expr fuses and matches the row engine bit for bit") {
      checkFused(s"SELECT $expr AS v FROM $$t")
    }
  }

  test("a date filter forwards unread DOUBLE and FLOAT columns through the compaction") {
    // The filter reads only the dates; the floating columns survive it by the per-row copy at
    // eight bytes and the four-byte path, now that a lane reads their Arrow vectors.
    checkFused("SELECT x1, x2, f1, f2 FROM $t WHERE d > d2")
  }

  test("a DOUBLE and a FLOAT root beside a date root each take a kernel of their own") {
    checkFused("SELECT coalesce(x1, x2) AS a, greatest(d, d2) AS b, coalesce(f1, f2) AS c " +
      "FROM $t")
  }

  // -----------------------------------------------------------------------------------------
  // The nodes that order values, in Spark's order: NaN equals NaN and is greater than every
  // other value, and -0.0 equals 0.0. The pairs fixture is the table of every operator over the
  // special values; the bits of each answer are compared, so `greatest(-0.0, 0.0)` must be
  // -0.0 and two NaNs must give the first one's bits.
  // -----------------------------------------------------------------------------------------

  for ((kind, a, b, nan, zero, one) <- Seq(
      ("DOUBLE", "x1", "x2", "CAST('NaN' AS DOUBLE)", "0.0D", "1.5D"),
      ("FLOAT", "f1", "f2", "CAST('NaN' AS FLOAT)", "0.0F", "1.5F"))) {
    val minusZero = s"CAST('-0.0' AS $kind)"
    // Every condition as a filter and as a choice: the filter fuses whole, the null tests the
    // optimizer infers beside a comparison included, and the choice shows the condition's own
    // three-valued answer, which a filter folds unknown into false and hides - a value of its
    // own for each of true, false and unknown. (A `BOOLEAN` output is no lane's yet; a CASE
    // with no ELSE is the floating lane's null, whose rows are the coverage suite's, VARKA-578.)
    Seq(s"$a < $b", s"$a <= $b", s"$a > $b", s"$a >= $b", s"$a = $b", s"$a <> $b",
      s"$a <=> $b", s"NOT ($a < $b)", s"isnan($a)", s"NOT isnan($a)", s"$a = $zero",
      s"$a < $nan", s"$a >= $nan", s"$a = $nan", s"$a <=> $nan", s"$a IS NULL OR $a > $b",
      s"$one < $a", s"$nan = $a", s"$nan <= $a", s"$minusZero = $a",
      s"$a IN ($zero, $nan, $one)", s"$a IN ($minusZero, $one)",
      // Past the optimizer's threshold of ten the list arrives as an InSet; this one holds a
      // zero, which both zeros of the column must match.
      s"$a IN (${(0 to 10).map(k => s"CAST($k AS $kind)").mkString(", ")}, $nan)"
    ).foreach { cond =>
      test(s"$kind: WHERE $cond fuses and matches the row engine") {
        checkFused(s"SELECT $a, $b FROM $$t WHERE $cond")
      }
      test(s"$kind: a choice on $cond fuses and matches the row engine bit for bit") {
        checkFused(s"SELECT CASE WHEN $cond THEN $one WHEN NOT ($cond) THEN $zero " +
          s"ELSE CAST(-2.5 AS $kind) END AS c FROM $$t")
      }
    }
    Seq(s"greatest($a, $b)", s"least($a, $b)", s"greatest($a, $b, $zero)",
      s"least($b, $nan, $a)", s"nanvl($a, $b)", s"nanvl($a, $one)",
      s"CASE WHEN $a < $b THEN $a ELSE $b END", s"if($a <=> $b, $one, $zero)",
      s"if(isnan($a), $zero, $a)", s"CASE WHEN $a IN ($zero, $nan) THEN $b ELSE $a END",
      s"greatest($b, $a)", s"least($b, $a)", s"nanvl($b, $a)"
    ).foreach { expr =>
      test(s"$kind: SELECT $expr fuses and matches the row engine bit for bit") {
        checkFused(s"SELECT $expr AS v FROM $$t")
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // The arithmetic (VARKA-342): one answer in every mode, the division's zero divisor apart.
  // -----------------------------------------------------------------------------------------

  // One projection of every form, so that the session runs the path once a mode: the forms'
  // answers, cell by cell and bit by bit, are the harness's and VarkaEmitterFloatingArithSuite's.
  // The forms over two columns come first; the rest move a NaN's bits or take them from one NaN
  // operand. Every form is held at the row level, a subtraction's and a division's NaN of two
  // NaNs to its left operand's bits.
  private val twoColumnForms = Seq("x1 + x2", "x1 - x2", "x1 * x2", "try_divide(x1, x2)",
    "try_add(x1, x2)", "(x1 + x2) * x2 - x1", "f1 + f2", "f1 * f2", "f2 - f1")
  private val arithmetic = (twoColumnForms ++ Seq("x2 / 3.0D", "sqrt(x1)", "degrees(x2)",
    "radians(x1)", "-x1", "abs(x2)", "signum(x1)", "-f1", "abs(f2)"))
    .zipWithIndex.map { case (e, k) => s"$e AS v$k" }.mkString(", ")

  test("the floating arithmetic fuses and matches the row engine in both modes, x1 / x2 null " +
      "on a zero divisor in the legacy one") {
    for (ansi <- Seq(false, true)) {
      checkFusedArith(s"SELECT $arithmetic FROM $$t", ansi)
    }
    checkFusedArith("SELECT x1 / x2 AS v FROM $t", ansi = false)
  }

  test("a division whose divisor a choice guards fuses under ANSI over batches holding zeros") {
    for (q <- Seq("SELECT IF(x2 = 0.0D, 0.0D, x1 / x2) AS v FROM $t",
        "SELECT CASE WHEN x2 <> 0.0D THEN x1 / x2 ELSE x1 END AS v FROM $t")) {
      checkFusedArith(q, ansi = true)
    }
  }

  test("one division shared by two outputs under arms of different conditions fuses under ANSI " +
      "over batches holding zeros") {
    // Both outputs read the one `x1 / x2`, each under its own arm. Its guard is qualified by the
    // OR of the two arms, so a zero divisor that every arm reading it sends elsewhere condemns no
    // batch; with the arms collapsed to none, every batch holding a zero divisor declined.
    val q = "SELECT IF(x2 = 0.0D, 0.0D, x1 / x2) AS a, " +
      "CASE WHEN x2 <> 0.0D THEN x1 / x2 ELSE x1 END AS b FROM $t"
    checkFusedArith(q, ansi = true)
    // The pairs fixture divides by both zeros, in every batch: none declines.
    withAnsi(true) {
      val actual = varkaSpark.sql(q.replace("$t", "varka_float_pairs"))
      actual.collect()
      val node = collectFirst(actual.queryExecution.executedPlan) { case v if isVarkaNode(v) => v }
        .getOrElse(fail("no Varka node"))
      assert(node.metrics("numFallbackBatchesDeclined").value === 0L)
    }
  }

  test("x1 / x2 under ANSI raises DIVIDE_BY_ZERO through the row engine on a zero divisor") {
    // The pairs fixture divides by both zeros; the batch declines and the row engine raises.
    withAnsi(true) {
      val q = "SELECT x1 / x2 AS v FROM varka_float_pairs"
      val actual = varkaSpark.sql(q)
      assertFused(actual.queryExecution.executedPlan)
      val e = intercept[org.apache.spark.SparkArithmeticException](actual.collect())
      assert(e.getCondition === "DIVIDE_BY_ZERO", e.getMessage)
      // A fixture with no live zero divisor answers, fused.
      checkFusedArith("SELECT x1 / x2 AS v FROM $t WHERE x2 IS NULL OR x2 <> 0.0D", ansi = true)
    }
  }

  // -----------------------------------------------------------------------------------------
  // What still declines, by name: the casts, the remainder, and a floating value beside an
  // integral one in a kernel.
  // -----------------------------------------------------------------------------------------

  private val x1 = AttributeReference("x1", DoubleType)()
  private val x2 = AttributeReference("x2", DoubleType)()
  private val f1 = AttributeReference("f1", FloatType)()
  private val d = AttributeReference("d", DateType)()
  private val output: Seq[Attribute] = Seq(d, x1, x2, f1)

  test("EXPLAIN names why a floating cast or remainder declines, and fuses the picks and sum") {
    val lines = VarkaFusionReport.lines(Seq(
      Alias(Coalesce(Seq(x1, x2)), "a")(),
      Alias(Greatest(Seq(x1, x2)), "b")(),
      Alias(org.apache.spark.sql.catalyst.expressions.Cast(f1, IntegerType), "c")(),
      Alias(org.apache.spark.sql.catalyst.expressions.Remainder(x1, x2), "e")(),
      Alias(org.apache.spark.sql.catalyst.expressions.Add(x1, x2), "g")()), output)
    assert(lines.size == 5, lines)
    assert(lines.head.endsWith(": fused"), lines)
    assert(lines(1).endsWith(": fused"), lines)
    assert(lines(2).contains("a cast from a floating type (row 343's second part)"), lines)
    assert(lines(3).contains("a floating remainder (row 346)"), lines)
    assert(lines(4).endsWith(": fused"), lines)
  }

  test("the casts between FLOAT and DOUBLE fuse and keep a NaN's payload, both zeros and the "
      + "rounding to an infinity") {
    // The sum of two NaNs has no fixed payload (`VARKA-342.md` 0.6), so that first column is
    // compared at the row level; the conversions' columns bit for bit, in both modes.
    for (ansi <- Seq(false, true)) {
      checkFusedArith("SELECT x2 + CAST(f2 AS DOUBLE) AS b, CAST(f1 AS DOUBLE) AS a, "
        + "CAST(x1 AS FLOAT) AS c, CAST(f1 AS DOUBLE) / 2.0D AS e FROM $t", ansi)
    }
  }

  private val l = AttributeReference("l", LongType)()
  private val y = AttributeReference("y", ShortType)()

  /** Why `CAST(child AS to)` alone, over `l` and `y`, declines, or None where it fuses. */
  private def castDecline(
      child: Expression, to: DataType, options: VarkaEmitOptions): Option[String] = {
    val declines = VarkaExpressionCompiler.declines(Seq(Alias(Cast(child, to), "c")()),
      Seq(l, y), options)
    Option(declines.get(0)).map(_.toString)
  }

  test("on a planted host without AVX512DQ a bigint cast to DOUBLE fuses by its AVX2 form, and " +
      "a bigint condition as a DOUBLE or FLOAT fuses through the int lane") {
    // An AVX-512 host without DQ, which no AMD host makes the JVM report, beside one with it.
    val knights = new VarkaHostIsa.Reading("amd64",
      "avx, avx2, avx512f, avx512cd, avx512er, avx512pf", null, 512)
    val withDq = new VarkaHostIsa.Reading("amd64", "avx, avx2, avx512f, avx512dq", null, 512)
    val conf = new SQLConf
    conf.setConf(SQLConf.VARKA_EMIT_USE_AVX, VarkaSessionEmit.USE_AVX_FROM_HOST)
    def options(host: VarkaHostIsa.Reading): VarkaEmitOptions =
      VarkaColumnarToRowExec.emitOptions(VarkaSessionEmit.of(conf, host))
    assert(options(knights).convertsFallBack())
    val positive = GreaterThan(l, Literal(0L))
    for (to <- Seq(DoubleType, FloatType)) {
      assert(castDecline(l, to, options(knights)).isEmpty, to)
      assert(castDecline(positive, to, options(knights)).isEmpty, to)
      assert(castDecline(l, to, options(withDq)).isEmpty, to)
      // The default level names no host, so it declines the cast: an AVX2 host there would run
      // L2D and L2F as the Vector API's Java loop, and the AVX2 form would slow every other.
      val unknown = castDecline(l, to, VarkaEmitOptions.DEFAULTS)
      assert(unknown.exists(_.contains("unknown emit level")), unknown)
    }
    // Beside a double literal, which goes to the long table: the projection checks its kernel
    // drives at 64 bits under the defaults' plan, which declines the conversion, and must not
    // take that refusal for an int drive.
    val withLiteral = VarkaExpressionCompiler.compile(Seq(Alias(
      Add(Cast(l, DoubleType), Literal(1.5d)), "c")()), Seq(l, y), options(withDq))
    assert(withLiteral.isPresent, "CAST(l AS DOUBLE) + 1.5D did not compile at level 3")
    // VarkaDifferentialHarnessSuite runs it at level 3 against the row engine, beside the casts.
  }

  test("an aarch64 host fuses a bigint cast to DOUBLE and to FLOAT, and no other host's level " +
      "moves") {
    // VARKA-584. aarch64's matcher lowers L2D and L2F with no feature predicate, so a session
    // asking for the host's level names level 3 there, NEON or SVE, and both casts fuse as one
    // convertShape each. Catches: an aarch64 host left at the unknown level, where both decline;
    // an aarch64 answer that asks for a feature; an unread list, which may be a JVM that is not
    // HotSpot, or another architecture moved off the unknown level; an x86 level moved; and an
    // explicit level the aarch64 host overrides.
    val conf = new SQLConf
    // The two-lane refusal is resolved from the same reading; set off, it reads no JVM lanes.
    conf.setConfString(SQLConf.VARKA_DECLINE_TWO_LANE_CONDITIONS.key, "false")
    conf.setConf(SQLConf.VARKA_EMIT_USE_AVX, VarkaSessionEmit.USE_AVX_FROM_HOST)
    def reading(arch: String, features: String, failure: String, bits: Int): VarkaHostIsa.Reading =
      new VarkaHostIsa.Reading(arch, features, failure, bits)
    val three = VarkaEmitOptions.AVX512_LEVEL
    val unknown = VarkaEmitOptions.USE_AVX_UNKNOWN
    val neon = reading("aarch64", "fp, asimd", null, 128)
    val hosts = Seq(
      neon -> three,
      reading("aarch64", "fp, asimd, sve, sve2", null, 256) -> three,
      reading("aarch64", "", "no such method", 128) -> unknown,
      reading("riscv64", "rv64, v", null, 128) -> unknown,
      reading("ppc64le", "", null, 128) -> unknown,
      reading("amd64", "", "IllegalAccessException: no export", 256) -> unknown,
      reading("amd64", "avx, avx2, fma", null, 128) -> unknown,
      reading("amd64", "avx, avx2, fma", null, 256) -> 2,
      reading("amd64", "avx2, avx512f, avx512dq", null, 512) -> three)
    // A session at the unknown level keeps the base options' level, and the option matrix's
    // useAVX configurations put a level there, so the cast is checked twice: over the defaults,
    // where the session's level alone decides, and over the base, at the level it composes to.
    def checkCasts(session: VarkaSessionEmit, clue: String): Unit = {
      val base = VarkaColumnarToRowExec.currentEmitOptions
      val options = VarkaColumnarToRowExec.emitOptions(session)
      val expected = if (session.useAVX == unknown) base.useAVX else session.useAVX
      assert(options.useAVX === expected, clue)
      val overDefaults = session.apply(VarkaEmitOptions.DEFAULTS)
      for (to <- Seq(DoubleType, FloatType); opts <- Seq(overDefaults, options)) {
        val decline = castDecline(l, to, opts)
        if (opts.useAVX == unknown) {
          assert(decline.exists(_.contains("unknown emit level")), s"$clue to $to: $decline")
        } else {
          assert(decline.isEmpty, s"$clue to $to at level ${opts.useAVX}")
        }
      }
    }
    // An unread x86 reading would write the once-per-JVM warning, which another test watches.
    val loggedBefore = VarkaSessionEmit.setUnreadableLoggedForTesting(true)
    try {
      for ((host, level) <- hosts) {
        val session = VarkaSessionEmit.of(conf, host)
        assert(session.useAVX === level, host)
        checkCasts(session, host.toString)
      }
    } finally {
      VarkaSessionEmit.setUnreadableLoggedForTesting(loggedBefore)
    }
    for (level <- Seq(unknown, 2, three)) {
      conf.setConf(SQLConf.VARKA_EMIT_USE_AVX, level)
      val session = VarkaSessionEmit.of(conf, neon)
      assert(session.useAVX === level, s"level $level")
      checkCasts(session, s"aarch64 at level $level")
    }
  }

  // varka-session-lint: allow a path: the planner resolving a planted host's level, which no
  // harness has, to a kernel; the level-3 answers are VarkaDifferentialHarnessSuite's.
  test("on a planted aarch64 host a session at the host's level fuses CAST(l AS DOUBLE) and " +
      "CAST(l AS FLOAT), and this JVM's kernel matches the row engine bit for bit") {
    // VARKA-584 end to end: the planner resolves the planted host, as the driver of an aarch64
    // cluster would, and the kernel converts on this JVM - an x86 one in the Build, so this holds
    // the plan and level 3's bytes to Spark, while aarch64's own scvtf lowering is checked by
    // VarkaEmitterFloatingCastSuite on the Arm runner (varka-sweeps). The cells are the casts'
    // traps: the extremes, 2^53 + 2^29 + 1, which rounds to 2^53 through DOUBLE and to
    // 2^53 + 2^30 in one rounding to FLOAT, 2^53 + 1 and 2^24 + 1, the first values each type
    // cannot hold, and 4275361147, which a split-and-add form misrounds, on ids 1 to 10; then
    // 64-bit hashes, one row in 17 null.
    val view = "varka_bigint_casts"
    val cells = Seq("-9223372036854775808", "9223372036854775807", "9007199791611905",
      "-9007199791611905", "9007199254740993", "16777217", "4275361147", "4503599627370497",
      "-1", "0").map(v => s"CAST('$v' AS BIGINT)").mkString(", ")
    val table =
      s"""SELECT CASE WHEN id % 17 = 0 THEN NULL
         |            WHEN id <= 10 THEN element_at(array($cells), CAST(id AS INT))
         |            ELSE hash(id) * 4294967296L + (hash(id, 1) & 4294967295L) END AS l
         |FROM range(0, 2000, 1, 2)""".stripMargin
    val sessions = Seq(spark, varkaSpark)
    VarkaSessionEmit.setHostForTesting(new VarkaHostIsa.Reading("aarch64", "fp, asimd", null,
      128))
    try {
      for (session <- sessions) {
        session.sql(table).createOrReplaceTempView(view)
        session.catalog.cacheTable(view)
      }
      withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, VarkaSessionEmit.USE_AVX_FROM_HOST.toString) {
        val q = s"SELECT CAST(l AS DOUBLE) AS a, CAST(l AS FLOAT) AS b FROM $view"
        val actual = varkaSpark.sql(q)
        val expected = expectedSql(q, actual)
        val plan = actual.queryExecution.executedPlan
        assertFused(plan)
        checkAnswer(actual, expected)
        assert(bits(actual).sortBy(_.toString) === bits(expected).sortBy(_.toString))
        assertKernelsRan(plan)
      }
    } finally {
      VarkaSessionEmit.setHostForTesting(null)
      for (session <- sessions if session.catalog.tableExists(view)) {
        session.catalog.uncacheTable(view)
        session.catalog.dropTempView(view)
      }
    }
  }

  test("a smallint read for its validity alone fuses as a DOUBLE and as a FLOAT") {
    // The double kernel has no narrow value but a column, and a null test reads only the
    // column's validity word, a bit per row at every lane (VARKA-343.md 10, 343a5).
    val base = VarkaEmitOptions.DEFAULTS
    assert(castDecline(IsNotNull(y), DoubleType, base).isEmpty)
    assert(castDecline(IsNotNull(y), FloatType, base).isEmpty)
  }

  // The divisors are literals: the fixtures' zero divisors raise under ANSI on both sides. The
  // casts from a floating type are TRY's, since the fixtures' NaNs raise under ANSI.
  private val declined = Seq("try_cast(f1 AS INT)", "x1 % 2.5D", "pmod(f1, 2.5F)",
    "try_cast(x1 AS BIGINT)")

  declined.foreach { expr =>
    test(s"SELECT $expr declines and answers as the row engine does") {
      checkDeclined(s"SELECT $expr AS v FROM $$t")
    }
  }

  test("a choice between floating values under a date condition fuses, its condition crossed") {
    checkFused("SELECT CASE WHEN d > d2 THEN x1 ELSE x2 END AS v FROM $t")
  }

  test("beside a date conjunct, a floating one shares the kernel") {
    // The conjuncts meet across the kinds, each off the meeting lane crossed (VARKA-343 2.2.1).
    fixtures.foreach { case (fixture, _) =>
      for (q <- Seq(s"SELECT d FROM $fixture WHERE x1 IS NOT NULL AND d > d2",
          s"SELECT d FROM $fixture WHERE d < d2 AND x1 < 1.5D",
          s"SELECT d FROM $fixture WHERE f1 = f2 AND d <= d2",
          // A FLOAT and a DOUBLE conjunct meet at the double lane.
          s"SELECT d FROM $fixture WHERE x1 > 0.0D AND f1 < 1.5F")) {
        val actual = varkaSpark.sql(q)
        withClue(q) {
          // No row filter above the Varka node: the kernel takes both conjuncts.
          val plan = actual.queryExecution.executedPlan
          assertFused(plan)
          assert(collect(plan) { case f: FilterExec => f }.isEmpty, plan.treeString)
          checkAnswer(actual, expectedSql(q, actual))
        }
      }
    }
  }
}
