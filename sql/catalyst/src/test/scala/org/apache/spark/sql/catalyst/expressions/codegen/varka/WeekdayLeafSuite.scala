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
import java.util.Locale

import scala.util.Try

import org.apache.arrow.vector.VarCharVector

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.ArrowColumnVector
import org.apache.spark.unsafe.types.UTF8String

/**
 * [[WeekdayLeaf]] against its definition, `DateTimeUtils.getDayOfWeekFromString`, under both
 * parsers (task 59). The domain is the one an ASCII fast path can get wrong: every case
 * pattern of every accepted spelling, every short ASCII string, every one-byte mutation of
 * every spelling, the untrimmed and empty strings, and the non-ASCII rows whose
 * `Locale.ROOT` upper case is a weekday name. The fill contract - data, validity, null
 * count, and the ANSI sentinel - is checked over a hand-built Arrow string vector.
 */
class WeekdayLeafSuite extends SparkFunSuite {

  private val spellings = Seq(
    "SU", "SUN", "SUNDAY", "MO", "MON", "MONDAY", "TU", "TUE", "TUESDAY", "WE", "WED",
    "WEDNESDAY", "TH", "THU", "THURSDAY", "FR", "FRI", "FRIDAY", "SA", "SAT", "SATURDAY")

  /** The definition: `k`, or `UNRECOGNISED` where the row engine throws. */
  private def oracle(s: String): Int =
    Try(DateTimeUtils.getDayOfWeekFromString(UTF8String.fromString(s)) - 1)
      .getOrElse(WeekdayLeaf.UNRECOGNISED)

  private def checkBoth(s: String): Unit = {
    val expected = oracle(s)
    val u = UTF8String.fromString(s)
    assert(WeekdayLeaf.parse(u, WeekdayLeaf.Parser.ROW_ENGINE) === expected,
      s"row-engine parser on ${escape(s)}")
    assert(WeekdayLeaf.parse(u, WeekdayLeaf.Parser.ASCII) === expected,
      s"ascii parser on ${escape(s)}")
  }

  private def escape(s: String): String =
    s.map(c => if (c >= ' ' && c < 127) c.toString else f"\\u$c%04x").mkString("\"", "", "\"")

  /** Every upper/lower pattern of `s`'s letters. */
  private def casePatterns(s: String): Seq[String] =
    (0 until (1 << s.length)).map { bits =>
      s.zipWithIndex.map { case (c, i) =>
        if ((bits & (1 << i)) != 0) c.toLower else c.toUpper
      }.mkString
    }

  test("every case pattern of every accepted spelling is its day, under both parsers") {
    var count = 0
    for (name <- spellings; variant <- casePatterns(name)) {
      assert(oracle(variant) !== WeekdayLeaf.UNRECOGNISED, s"the oracle rejects $variant")
      checkBoth(variant)
      count += 1
    }
    assert(count === spellings.map(s => 1 << s.length).sum)
    // The whole range, and THURSDAY's negative k, which a 0..6 assumption would miss.
    assert(WeekdayLeaf.parse(UTF8String.fromString("thursday"), WeekdayLeaf.Parser.ASCII) === -1)
    assert(WeekdayLeaf.parse(UTF8String.fromString("Wed"), WeekdayLeaf.Parser.ASCII) === 5)
  }

  test("every one- and two-byte ASCII string, and the untrimmed and empty strings") {
    val bytes = (0 until 128).map(_.toChar)
    for (a <- bytes) checkBoth(a.toString)
    for (a <- bytes; b <- bytes) checkBoth(s"$a$b")
    Seq("", " MON", "MON ", "MON.", "MONDAYS", "MONDA", "SUNDAY\u0000", "\tSU").foreach {
      s =>
        assert(oracle(s) === WeekdayLeaf.UNRECOGNISED, s"the oracle accepts ${escape(s)}")
        checkBoth(s)
    }
  }

  test("every one-byte printable mutation of every spelling agrees with the definition") {
    // A near miss the fold or the length test lets through would show here and nowhere else:
    // one byte of a valid name replaced by every printable ASCII byte, at every position.
    for (name <- spellings; pos <- name.indices; b <- 32 until 127) {
      val mutated = name.updated(pos, b.toChar)
      checkBoth(mutated)
      checkBoth(mutated.toLowerCase(Locale.ROOT))
    }
  }

  test("a non-ASCII byte hands the row to the definition, whichever way it folds") {
    // Locale.ROOT's upper case maps U+017F (long s) to S and U+0131 (dotless i) to I, so
    // these two are weekdays; the Cyrillic look-alike and the non-breaking space are not.
    // (Code points spelled out rather than escaped: scalastyle reads a \u escape as the
    // character itself.)
    def cp(codePoint: Int): String = new String(Character.toChars(codePoint))
    val longS = cp(0x017F)
    val dotlessI = cp(0x0131)
    val rows = Seq(s"${longS}unday", s"fr${dotlessI}day", s"m${cp(0x043E)}nday",
      s"monday${cp(0x00A0)}", s"${cp(0x00DF)}unday", s"SUNDAY$longS", longS, s"${longS}u",
      s"${longS}un")
    assert(oracle(s"${longS}unday") === 2 && oracle(s"fr${dotlessI}day") === 0,
      "the JDK fact moved")
    rows.foreach(checkBoth)
  }

  test("fill writes k, the validity and the null count; declines only under ANSI") {
    val names = Seq("Mon", null, "xyz", "sunday", "TH", "", "SAT", "friday", "WEDNESDAY", null)
    withVector(names) { column =>
      for (parser <- WeekdayLeaf.Parser.values()) {
        val arena = Arena.ofConfined()
        try {
          val data = arena.allocate(names.length * 4L, 8)
          val validity = arena.allocate((names.length + 7) / 8L, 8)
          validity.fill(0xFF.toByte) // rewritten whole: stale bits must not survive
          val nulls = WeekdayLeaf.fill(column, names.length, false, parser, data.address(),
            validity.address())
          assert(nulls === 4, s"$parser: two null names, one unrecognised, one empty")
          for ((name, i) <- names.zipWithIndex) {
            val valid = (validity.get(ValueLayout.JAVA_BYTE, i / 8L) >> (i % 8) & 1) == 1
            val expected = if (name == null) WeekdayLeaf.UNRECOGNISED else oracle(name)
            assert(valid === (expected != WeekdayLeaf.UNRECOGNISED), s"$parser: row $i validity")
            if (valid) {
              assert(data.get(ValueLayout.JAVA_INT, i * 4L) === expected, s"$parser: row $i")
            }
          }
          // ANSI: the unrecognised name declines; without it the same rows fill.
          assert(WeekdayLeaf.fill(column, names.length, true, parser, data.address(),
            validity.address()) === WeekdayLeaf.DECLINED)
          assert(WeekdayLeaf.fill(column, 2, true, parser, data.address(),
            validity.address()) === 1, s"$parser: the first two rows hold one null")
          assert(WeekdayLeaf.fill(column, 0, true, parser, data.address(),
            validity.address()) === 0)
        } finally {
          arena.close()
        }
      }
    }
  }

  /** A `VarCharVector` over `names` (null for a null row), wrapped as Spark's column vector. */
  private def withVector(names: Seq[String])(body: ArrowColumnVector => Unit): Unit = {
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("weekday-leaf", 0, Long.MaxValue)
    val vector = new VarCharVector("s", allocator)
    try {
      vector.allocateNew()
      names.zipWithIndex.foreach {
        case (null, i) => vector.setNull(i)
        case (s, i) => vector.setSafe(i, s.getBytes(StandardCharsets.UTF_8))
      }
      vector.setValueCount(names.length)
      body(new ArrowColumnVector(vector))
    } finally {
      // The allocator close must run even if the vector's does not: it is a child of the
      // process-lifetime root allocator, so skipping it charges this suite's bytes against the
      // root for the rest of the test JVM and makes some later suite's leak check fail instead.
      try {
        vector.close()
      } finally {
        allocator.close()
      }
    }
  }
}
