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
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Coalesce, Greatest}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DateType, DoubleType, FloatType, StructField, StructType}

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
 * Spark's order and checked over every pair of the special values. What still declines - the
 * arithmetic, the casts, a floating value beside an integral one in a kernel - is pinned at
 * the end, with the query still answering as the row engine does.
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
    // own for each of true, false and unknown. (A `BOOLEAN` output is no lane's yet, and a CASE
    // with no ELSE waits for a typed null leaf, row 333.)
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
  // What still declines, by name: arithmetic, the casts, and a floating value beside an
  // integral one in a kernel.
  // -----------------------------------------------------------------------------------------

  private val x1 = AttributeReference("x1", DoubleType)()
  private val x2 = AttributeReference("x2", DoubleType)()
  private val f1 = AttributeReference("f1", FloatType)()
  private val d = AttributeReference("d", DateType)()
  private val output: Seq[Attribute] = Seq(d, x1, x2, f1)

  test("EXPLAIN names why a floating cast or sum declines, and fuses the picks") {
    val lines = VarkaFusionReport.lines(Seq(
      Alias(Coalesce(Seq(x1, x2)), "a")(),
      Alias(Greatest(Seq(x1, x2)), "b")(),
      Alias(org.apache.spark.sql.catalyst.expressions.Cast(f1, DoubleType), "c")(),
      Alias(org.apache.spark.sql.catalyst.expressions.Add(x1, x2), "e")()), output)
    assert(lines.size == 4, lines)
    assert(lines.head.endsWith(": fused"), lines)
    assert(lines(1).endsWith(": fused"), lines)
    assert(lines(2).contains("a cast to or from a floating type (row 343)"), lines)
    assert(lines(3).contains("floating arithmetic (row 342)"), lines)
  }

  Seq("CAST(f1 AS DOUBLE)", "x1 + x2", "-x1", "abs(f1)").foreach { expr =>
    test(s"SELECT $expr declines and answers as the row engine does") {
      checkDeclined(s"SELECT $expr AS v FROM $$t")
    }
  }

  test("a choice between floating values under a date condition declines: the lanes differ") {
    checkDeclined("SELECT CASE WHEN d > d2 THEN x1 ELSE x2 END AS v FROM $t")
  }

  test("beside a date conjunct, a floating one stays with the row filter") {
    // The date conjunct keeps the kernel's integral lane, and the floating one is the residual
    // until a mask crosses between the kinds.
    fixtures.foreach { case (fixture, _) =>
      for (q <- Seq(s"SELECT d FROM $fixture WHERE x1 IS NOT NULL AND d > d2",
          s"SELECT d FROM $fixture WHERE d < d2 AND x1 < 1.5D",
          s"SELECT d FROM $fixture WHERE f1 = f2 AND d <= d2",
          // A FLOAT and a DOUBLE conjunct meet on no lane: one keeps the kernel.
          s"SELECT d FROM $fixture WHERE x1 > 0.0D AND f1 < 1.5F")) {
        val actual = varkaSpark.sql(q)
        withClue(q) {
          assertFused(actual.queryExecution.executedPlan)
          checkAnswer(actual, expectedSql(q, actual))
        }
      }
    }
  }
}
