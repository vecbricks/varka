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

import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.IntStream

import scala.jdk.CollectionConverters._

import com.google.common.math.IntMath

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Cast, DivideYMInterval, Expression, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.types.{ByteType, DataType, IntegerType, ShortType, YearMonthIntervalType}

/**
 * `ym / num` for a literal integral `num`, and the two YEAR-unit casts, against Spark's own
 * evaluation (`VarkaSparkOracle`), and the HALF_UP step's rule swept over every int32 dividend
 * against `IntMath.divide(.., HALF_UP)`, which is what `DivideYMInterval` calls.
 *
 * The rule the compiler lowers, for a divisor `d` with `|d| >= 2`: `q = m / d` truncated,
 * `r = m - q * d` in wrapping int arithmetic, and the quotient moves one step away from zero iff
 * `|r| >= ceil(|d| / 2)`, in the direction `sign(m) * sign(d)`. `model` below is that rule in
 * plain ints, step for step what the emitted tree computes.
 */
class VarkaEmitterYmDivideSuite extends SparkFunSuite with VarkaOwnJvm {

  private val ymType = YearMonthIntervalType()
  private val ymColumn = AttributeReference("ym", ymType)()
  private val yearType = YearMonthIntervalType(YearMonthIntervalType.YEAR,
    YearMonthIntervalType.YEAR)
  private val yearColumn = AttributeReference("ymy", yearType)()
  private val attrs: Seq[Attribute] = Seq(ymColumn, yearColumn)

  private var classCounter = 0

  /** The rule, in the ints the lane holds: wrapping multiply and subtract, no widening. */
  private def model(m: Int, d: Int): Int = {
    val q = m / d
    val r = m - q * d
    val half = ((math.abs(d.toLong) + 1) / 2).toInt
    val away = if (d > 0) 1 else -1
    val magnitude = if (m < 0) -r else r
    q + (if (magnitude >= half) (if (m < 0) -away else away) else 0)
  }

  /** The divisor classes: small, powers of two and their neighbours, the extremes. */
  private val divisors: Seq[Int] = {
    val powers = (1 to 30).flatMap(k => Seq((1 << k) - 1, 1 << k, (1 << k) + 1))
    val small = (2 to 20) ++ Seq(60, 100, 360, 1000, 3600, 12345, 32767, 65537, 12345679)
    val all = (small ++ powers ++ Seq(Int.MaxValue, Int.MaxValue - 1, 1 << 30))
      .filter(_ >= 2).distinct
    (all ++ all.map(-_) ++ Seq(Int.MinValue, Int.MinValue + 1)).distinct
  }

  /** Every divisor a Byte literal holds, which `ym / CAST(d AS TINYINT)` takes. */
  private val byteDivisors: Seq[Int] = (-128 to 127).filter(d => d != 0 && d != 1 && d != -1)

  /** Dividends around each divisor's half points and multiples, and the edges of the range. */
  private def dividendsFor(d: Int): Array[Int] = {
    val half = (math.abs(d.toLong) + 1) / 2
    val points = for {
      k <- Seq(-3L, -2L, -1L, 0L, 1L, 2L, 3L, Int.MaxValue.toLong / math.abs(d.toLong),
        Int.MinValue.toLong / math.abs(d.toLong))
      t <- Seq(-half - 1, -half, -half + 1, -1L, 0L, 1L, half - 1, half, half + 1)
      v = k * d + t
      if v >= Int.MinValue && v <= Int.MaxValue
    } yield v.toInt
    val edges = Seq(Int.MinValue, Int.MinValue + 1, Int.MaxValue, Int.MaxValue - 1, -1, 0, 1)
    val spread = (0 until 400).map(i => (i * 10737418L - 2147483648L).toInt)
    (points ++ edges ++ spread).distinct.toArray
  }

  /**
   * Compiles `expr` over the two interval columns, emits it at `lanes` and runs it over `values`
   * in the first column (and the second, the same), against the Spark oracle, with every null
   * pattern. Unless `mustCompare` is false, fails when every batch was declined instead of
   * compared.
   */
  private def check(expr: Expression, values: Array[Int], lanes: Int,
      mustCompare: Boolean = true): Unit = {
    val plan = VarkaExpressionCompiler.compile(Seq(Alias(expr, "c")()), attrs)
      .orElseThrow(() => new AssertionError(s"${expr.sql} did not compile"))
    val options = VarkaMatrix.base.withLanesOverride(lanes)
    classCounter += 1
    val className = s"org.apache.spark.sql.varka.execution.VarkaYmDivide$classCounter"
    val roots = plan.outputs.asScala.toSeq
    val numInputs = plan.inputOrdinals.size
    val bytes = VarkaLoopEmitter.emit(className, plan.outputs, numInputs, plan.numLiterals,
      null, null, options)
    val oracle = new VarkaSparkOracle(Seq(expr),
      plan.inputOrdinals.asScala.toSeq.map(i => attrs(i)))
    val lits = plan.literals.asScala.map(_.intValue).toArray
    val patterns = Seq[Int => Boolean](_ => false, _ % 5 == 0, _ % 2 == 1)
    var compared = false
    for (pattern <- patterns) {
      val data = Array.fill(numInputs)(values)
      val batch = VarkaKernelCheck.Batch(values.length, Seq.fill(numInputs)(pattern), data,
        forceMasked = false)
      compared |= VarkaKernelCheck.runAndCompare(s"${expr.sql} lanes=$lanes", className, bytes,
        roots, numInputs, lits, batch, declineAllowed = true, spark = Some(oracle))
    }
    if (mustCompare) assert(compared, s"${expr.sql} at $lanes lanes declined every batch")
  }

  private def divide(d: Int, dataType: DataType): Expression = {
    val literal = dataType match {
      case ByteType => Literal(d.toByte)
      case ShortType => Literal(d.toShort)
      case _ => Literal(d)
    }
    DivideYMInterval(ymColumn, literal)
  }

  private val lanesUnderTest = Seq(0, 4)

  test("ym / d matches Spark for every divisor class, at the default width and at 128 bits") {
    for (lanes <- lanesUnderTest; d <- divisors) {
      check(divide(d, IntegerType), dividendsFor(d), lanes)
    }
  }

  test("ym / d matches Spark for every Byte divisor, and Short ones at their edges") {
    for (lanes <- lanesUnderTest; d <- byteDivisors) {
      check(divide(d, ByteType), dividendsFor(d), lanes)
    }
    for (lanes <- lanesUnderTest; d <- Seq(2, -2, 255, -256, 32767, -32768, 12345)) {
      check(divide(d, ShortType), dividendsFor(d), lanes)
    }
  }

  test("ym / 1 is the operand") {
    val edge = Array(Int.MinValue, Int.MinValue + 1, -1, 0, 1, Int.MaxValue, 12, -12)
    for (lanes <- lanesUnderTest) check(divide(1, IntegerType), edge, lanes)
  }

  test("CAST(ymy AS INT) and the YEAR narrowing match Spark over the whole edge set") {
    val values = (dividendsFor(12) ++ dividendsFor(-12) ++ dividendsFor(7)).distinct
    for (lanes <- lanesUnderTest) {
      check(Cast(yearColumn, IntegerType), values, lanes)
      check(Cast(ymColumn, yearType), values, lanes)
      check(Cast(yearColumn, yearType), values, lanes)
    }
  }

  // -------------------------------------------------------------------------------------------
  // The rule over every int32 dividend (VARKA-89).
  // -------------------------------------------------------------------------------------------

  /**
   * Counts the dividends in `[from, to]` (stepping by `step`, `to` clamped to `Int.MaxValue`)
   * where `model` differs from Guava.
   */
  private def sweepRange(d: Int, from: Long, to: Long, step: Long, bad: AtomicLong,
      first: AtomicLong): Unit = {
    val last = math.min(to, Int.MaxValue.toLong)
    var m = from
    while (m <= last) {
      val v = m.toInt
      if (model(v, d) != IntMath.divide(v, d, RoundingMode.HALF_UP)) {
        if (bad.getAndIncrement() == 0) first.set(m)
      }
      m += step
    }
  }

  private def sweep(ds: Seq[Int], step: Long): Unit = {
    val chunks = 256
    val span = (1L << 32) / chunks
    for (d <- ds) {
      val bad = new AtomicLong
      val first = new AtomicLong
      IntStream.range(0, chunks).parallel().forEach { c =>
        val from = Int.MinValue.toLong + c * span
        sweepRange(d, from, from + span - 1, step, bad, first)
      }
      if (bad.get != 0) {
        fail(s"d=$d: the rule differs from IntMath.divide at ${bad.get} dividends, " +
          s"first ${first.get}")
      }
    }
  }

  test("the rule equals IntMath.divide HALF_UP on a slice of int32: a stride, the edges, the " +
      "half points") {
    val all = (divisors ++ byteDivisors).distinct
    sweep(all, 4099)
    for (d <- all; m <- dividendsFor(d)) {
      assert(model(m, d) == IntMath.divide(m, d, RoundingMode.HALF_UP), s"m=$m d=$d")
    }
    // Every dividend near zero and at both ends, for every divisor.
    for (d <- all; w <- Seq(-40000L, Int.MinValue.toLong, Int.MaxValue - 80000L)) {
      val bad = new AtomicLong
      sweepRange(d, w, w + 80000, 1, bad, new AtomicLong)
      assert(bad.get == 0, s"d=$d window at $w")
    }
  }

  test("the rule equals IntMath.divide HALF_UP at every int32 dividend, for every divisor " +
      "class (long-sweep: -Dvarka.sweep.long=true, about twelve minutes; VARKA-89)") {
    assume(System.getProperty("varka.sweep.long") == "true",
      "set -Dvarka.sweep.long=true to sweep all 2^32 dividends per divisor")
    val started = System.nanoTime()
    sweep((divisors ++ byteDivisors).distinct, 1)
    logInfo(s"ym / d sweep: ${(System.nanoTime() - started) / 1000000000L} s")
  }

  test("the sweeps' divisors are the divisors ym_divide.smt2 proves") {
    val proven = VarkaProofFiles.YM_DIVISORS.asScala.map(_.toInt).toSet
    assert((divisors ++ byteDivisors).toSet == proven)
  }
}
