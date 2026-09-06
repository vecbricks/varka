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

package org.apache.spark.sql.catalyst.expressions.codegen.varka

import java.lang.foreign.{Arena, ValueLayout}
import java.nio.charset.StandardCharsets

import org.apache.arrow.vector.VarCharVector

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.ArrowColumnVector
import org.apache.spark.unsafe.types.UTF8String

class TruncLevelLeafSuite extends SparkFunSuite {

  /** Every spelling `parseTruncLevel` maps to a date level, with the level it maps to. */
  private val accepted = Seq(
    "WEEK" -> TruncLevelLeaf.WEEK,
    "MON" -> TruncLevelLeaf.MONTH, "MONTH" -> TruncLevelLeaf.MONTH, "MM" -> TruncLevelLeaf.MONTH,
    "QUARTER" -> TruncLevelLeaf.QUARTER,
    "YEAR" -> TruncLevelLeaf.YEAR, "YYYY" -> TruncLevelLeaf.YEAR, "YY" -> TruncLevelLeaf.YEAR)

  /** Spellings the parser answers with a sub-day level or TRUNC_INVALID: null lanes, all. */
  private val rejected = Seq(
    "DAY", "DD", "HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND", "QTR", "", " YEAR",
    "YEAR ", "YEARS", "Y", "M", "MONTHS", "WEEKS", "WEEK\u0000", "Q", "YYY")

  /** The definition: the row engine's own code, whatever it is. */
  private def oracle(s: String): Int = DateTimeUtils.parseTruncLevel(UTF8String.fromString(s))

  private def escape(s: String): String =
    s.map(c => if (c >= ' ' && c < 127) c.toString else f"\\u$c%04x").mkString("\"", "", "\"")

  /** Every upper/lower pattern of `s`'s letters. */
  private def casePatterns(s: String): Seq[String] =
    (0 until (1 << s.length)).map { bits =>
      s.zipWithIndex.map { case (c, i) =>
        if ((bits & (1 << i)) != 0) c.toLower else c.toUpper
      }.mkString
    }

  test("the leaf's codes are DateTimeUtils' own, and the date levels are exactly WEEK..YEAR") {
    // The Java constants restate private[sql] Scala values; this is where a renumbering shows.
    assert(TruncLevelLeaf.WEEK === DateTimeUtils.TRUNC_TO_WEEK)
    assert(TruncLevelLeaf.MONTH === DateTimeUtils.TRUNC_TO_MONTH)
    assert(TruncLevelLeaf.QUARTER === DateTimeUtils.TRUNC_TO_QUARTER)
    assert(TruncLevelLeaf.YEAR === DateTimeUtils.TRUNC_TO_YEAR)
    assert(TruncLevelLeaf.WEEK === DateTimeUtils.MIN_LEVEL_OF_DATE_TRUNC)
    // Every code the parser can answer, classified the way TruncInstant.evalHelper does: a
    // level below the week is a NULL result, and nothing above the year exists.
    val codes = (accepted.map(_._1) ++ rejected).map(oracle).distinct
    assert(codes.contains(DateTimeUtils.TRUNC_INVALID))
    codes.foreach { code =>
      assert(TruncLevelLeaf.isDateLevel(code) === (code >= DateTimeUtils.MIN_LEVEL_OF_DATE_TRUNC),
        s"code $code")
    }
    assert(TruncLevelLeaf.isDateLevel(TruncLevelLeaf.YEAR + 1) === false)
  }

  test("every case pattern of every accepted spelling is its level; the rejected set is null") {
    var count = 0
    for ((name, level) <- accepted; variant <- casePatterns(name)) {
      assert(oracle(variant) === level, s"the oracle moved on ${escape(variant)}")
      assert(TruncLevelLeaf.parse(UTF8String.fromString(variant)) === level, escape(variant))
      count += 1
    }
    assert(count === accepted.map(s => 1 << s._1.length).sum)
    for (s <- rejected) {
      val code = TruncLevelLeaf.parse(UTF8String.fromString(s))
      assert(code === oracle(s), escape(s))
      assert(!TruncLevelLeaf.isDateLevel(code), s"${escape(s)} would truncate")
    }
  }

  test("a non-ASCII format is a null lane under the parser's own case fold") {
    // Locale.ROOT's upper case maps U+017F (long s) to S and U+0131 (dotless i) to I; no
    // accepted spelling contains either letter, so no non-ASCII string can reach a level. The
    // Kelvin sign (U+212A) upper-cases to itself, not to K. (Code points spelled out rather
    // than escaped: scalastyle reads a \u escape as the character itself.)
    def cp(codePoint: Int): String = new String(Character.toChars(codePoint))
    val rows = Seq(s"WEE${cp(0x212A)}", s"W${cp(0x0415)}EK", s"MONTH${cp(0x00A0)}",
      s"${cp(0x017F)}", s"YEAR${cp(0x0131)}", cp(0x00DF))
    rows.foreach { s =>
      val code = TruncLevelLeaf.parse(UTF8String.fromString(s))
      assert(code === oracle(s), escape(s))
      assert(!TruncLevelLeaf.isDateLevel(code), s"${escape(s)} would truncate")
    }
  }

  test("fill writes the level, the validity and the null count, and never declines") {
    val formats = Seq("Month", null, "HOUR", "week", "YY", "", "QUARTER", "dd", "mon", null)
    withVector(formats) { column =>
      val arena = Arena.ofConfined()
      try {
        val data = arena.allocate(formats.length * 4L, 8)
        val validity = arena.allocate((formats.length + 7) / 8L, 8)
        validity.fill(0xFF.toByte) // rewritten whole: stale bits must not survive
        val nulls = TruncLevelLeaf.fill(column, formats.length, data.address(),
          validity.address())
        assert(nulls === 5, "two null formats, two sub-day levels, one empty")
        for ((format, i) <- formats.zipWithIndex) {
          val valid = (validity.get(ValueLayout.JAVA_BYTE, i / 8L) >> (i % 8) & 1) == 1
          val expected = if (format == null) DateTimeUtils.TRUNC_INVALID else oracle(format)
          assert(valid === TruncLevelLeaf.isDateLevel(expected), s"row $i validity")
          if (valid) {
            assert(data.get(ValueLayout.JAVA_INT, i * 4L) === expected, s"row $i")
          } else {
            assert(data.get(ValueLayout.JAVA_INT, i * 4L) === 0, s"row $i: a null lane is zeroed")
          }
        }
        // A shorter length reads a prefix of the same column; an empty batch writes nothing.
        assert(TruncLevelLeaf.fill(column, 3, data.address(), validity.address()) === 2)
        assert(TruncLevelLeaf.fill(column, 0, data.address(), validity.address()) === 0)
      } finally {
        arena.close()
      }
    }
  }

  test("fill over an all-invalid column is all nulls, and over all-valid is none") {
    withVector(Seq("day", "x", null)) { column =>
      val arena = Arena.ofConfined()
      try {
        val data = arena.allocate(12L, 8)
        val validity = arena.allocate(1L, 8)
        assert(TruncLevelLeaf.fill(column, 3, data.address(), validity.address()) === 3)
        assert(validity.get(ValueLayout.JAVA_BYTE, 0L) === 0.toByte)
      } finally {
        arena.close()
      }
    }
    withVector(Seq("yyyy", "mm", "quarter", "WEEK", "yy", "MON", "Year", "Month", "week")) {
      column =>
        val arena = Arena.ofConfined()
        try {
          val data = arena.allocate(36L, 8)
          val validity = arena.allocate(2L, 8)
          assert(TruncLevelLeaf.fill(column, 9, data.address(), validity.address()) === 0)
          assert(validity.get(ValueLayout.JAVA_BYTE, 0L) === 0xFF.toByte)
          assert(validity.get(ValueLayout.JAVA_BYTE, 1L) === 1.toByte)
        } finally {
          arena.close()
        }
    }
  }

  /** A `VarCharVector` over `formats` (null for a null row), wrapped as Spark's column vector. */
  private def withVector(formats: Seq[String])(body: ArrowColumnVector => Unit): Unit = {
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("trunc-level-leaf", 0,
      Long.MaxValue)
    val vector = new VarCharVector("s", allocator)
    try {
      vector.allocateNew()
      formats.zipWithIndex.foreach {
        case (null, i) => vector.setNull(i)
        case (s, i) => vector.setSafe(i, s.getBytes(StandardCharsets.UTF_8))
      }
      vector.setValueCount(formats.length)
      body(new ArrowColumnVector(vector))
    } finally {
      vector.close()
      allocator.close()
    }
  }
}
