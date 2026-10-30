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

import java.lang.{Double => JDouble, Float => JFloat}
import java.lang.foreign.{Arena, ValueLayout}
import java.util.SplittableRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.LongStream

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Abs, Attribute, BoundReference, Expression, GenericInternalRow, RuntimeReplaceable, UnaryMinus}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection, VarkaExpressionCompiler, VarkaGeneratedClassLoader}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DoubleType, FloatType}

/**
 * Floating arithmetic (VARKA-342): `+`, `-`, `*`, negation and `abs` at the `DOUBLE` and `FLOAT`
 * lanes, and `/`, `sqrt`, `signum`, `degrees` and `radians` at the `DOUBLE` one, against the
 * reference evaluator and against Spark's own expressions.
 *
 * Every form is the IEEE operation Java computes, so the kernel's answer is held bit for bit,
 * with one exception, the NaN rule: a NaN an operation computes from two NaN operands of
 * different payloads is compared by being NaN, since which payload the row engine returns
 * depends on how C2 ordered the operands (`VARKA-342.md` 0.6). Negation, `abs` and `signum`
 * move their operand's bits and are compared bit for bit, NaN payloads included.
 *
 * The division's mode is its zero divisor's disposal: null under LEGACY and TRY, a declined
 * batch under ANSI, which the row engine raises `DIVIDE_BY_ZERO` on. Both zeros are a zero
 * divisor and NaN is not, as in Spark's `DivModLike`.
 *
 * The negation and `abs` of every `FLOAT` pattern run on every build. The binary sweeps, `+`,
 * `-` and `*` of every `FLOAT` pattern against each of sixteen operands, run under
 * `-Dvarka.sweep=true` (the nightly's sweep step).
 */
class VarkaEmitterFloatingArithSuite extends VarkaEmitterTestBase with VarkaOwnJvm {

  import VarkaEmitterFloatingArithSuite._

  private val x = new ColumnRef(0, LaneType.DOUBLE)
  private val y = new ColumnRef(1, LaneType.DOUBLE)
  private val f = new ColumnRef(0, LaneType.FLOAT)
  private val g = new ColumnRef(1, LaneType.FLOAT)

  // -------------------------------------------------------------------------------------------
  // The IR against the reference evaluator
  // -------------------------------------------------------------------------------------------

  /**
   * Row `i`'s operand pair from `specials`: every ordered pair in turn. Two NaNs of different
   * payloads are not paired, since the NaN rule compares their sum by NaN-ness and the matrix
   * compares bits; the second takes the first's bits instead.
   */
  private def pairOf[T](specials: Array[T], isNaN: T => Boolean)(col: Int, i: Int): T = {
    val n = specials.length
    val a = specials(i % n)
    val b = specials((i / n + i) % n)
    if (col == 0) a else if (isNaN(a) && isNaN(b)) a else b
  }

  private def doubleData(col: Int, i: Int): Long =
    pairOf(DOUBLE_SPECIALS, (v: Long) => JDouble.isNaN(JDouble.longBitsToDouble(v)))(col, i)

  private def floatData(col: Int, i: Int): Int =
    pairOf(FLOAT_SPECIALS, (v: Int) => JFloat.isNaN(JFloat.intBitsToFloat(v)))(col, i)

  test("the floating operations compute what the reference says at the DOUBLE lane, at both " +
      "widths") {
    val k = new LiteralSlot(0, LaneType.DOUBLE)
    val roots: Seq[VarkaVectorIR] = IntOp.values().toSeq.map(op =>
      new IntArith(op, Overflow.WRAP, x, y)) ++ Seq(
      new IntArith(IntOp.DIV, Overflow.NULL, x, y),
      new IntArith(IntOp.DIV, Overflow.WRAP, y, x),
      new IntNeg(Overflow.WRAP, x), new IntNeg(Overflow.WRAP, y, true),
      new MathUnary(MathFn.SQRT, x), new IntArith(IntOp.MUL, Overflow.WRAP, x, k))
    val lits = Array(JDouble.doubleToRawLongBits(Math.toDegrees(1.0)))
    for (lanes <- Seq(2, 8)) {
      checkLongMatrix(roots, 2, lits, Seq(1, 7, 64, 129, 400), combos(2), doubleData,
        "double", Some(lanes))
    }
  }

  test("the floating operations compute what the reference says at the FLOAT lane, at both " +
      "widths") {
    val roots: Seq[VarkaVectorIR] = Seq(IntOp.ADD, IntOp.SUB, IntOp.MUL).map(op =>
      new IntArith(op, Overflow.WRAP, f, g)) ++ Seq(
      new IntArith(IntOp.SUB, Overflow.WRAP, g, f), new IntNeg(Overflow.WRAP, f),
      new IntNeg(Overflow.WRAP, g, true))
    for (lanes <- Seq(4, 16)) {
      checkMatrix(roots, 2, Array.empty[Int], Seq(1, 7, 64, 129, 400), combos(2),
        data = floatData, ctx = s"float lanes=$lanes",
        options = VarkaMatrix.base.withLanesOverride(lanes))
    }
  }

  test("a floating add, subtract or multiply has no checked form, and an integral lane no " +
      "divide") {
    for (op <- Seq(IntOp.ADD, IntOp.SUB, IntOp.MUL); mode <- Seq(Overflow.FAIL, Overflow.NULL)) {
      intercept[IllegalArgumentException](new IntArith(op, mode, x, y))
      intercept[IllegalArgumentException](new IntArith(op, mode, f, g))
    }
    intercept[IllegalArgumentException](new IntNeg(Overflow.FAIL, x))
    for (mode <- Overflow.values()) {
      intercept[IllegalArgumentException](
        new IntArith(IntOp.DIV, mode, new ColumnRef(0), new ColumnRef(1)))
      intercept[IllegalArgumentException](new IntArith(IntOp.DIV, mode,
        new ColumnRef(0, LaneType.LONG), new ColumnRef(1, LaneType.LONG)))
    }
    intercept[IllegalArgumentException](new MathUnary(MathFn.SQRT, new ColumnRef(0)))
  }

  // -------------------------------------------------------------------------------------------
  // The division's zero divisor
  // -------------------------------------------------------------------------------------------

  /** One double-lane kernel over two columns, `None` a null: its status and its first output. */
  private def runDouble(root: VarkaVectorIR, xs: Seq[Option[Double]], ys: Seq[Option[Double]],
      lanes: Int = 0): (Int, Seq[Option[Long]]) = {
    val (kernel, loader) = load(emitMulti(Seq(root), 2, 0,
      VarkaMatrix.base.withLanesOverride(lanes)))
    val n = xs.length
    val arena = Arena.ofConfined()
    try {
      def col(vs: Seq[Option[Double]]) = makeLongInput(arena, n, i => vs(i).isEmpty,
        i => JDouble.doubleToRawLongBits(vs(i).getOrElse(0.0)))
      val cols = Seq(col(xs), col(ys))
      val out = makeLongOutput(arena, n)
      val status = kernel.run(cols.map(_.data.address()).toArray,
        cols.map(_.validityAddress(n)).toArray, cols.map(_.nullCount).toArray,
        Array(out._1.address()), Array(out._2.address()), Array.empty[Int], Array.empty[Long], n,
        VarkaEmitterTestSupport.scratch(kernel, n))
      val values = (0 until n).map { i =>
        val bit = (out._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
        if (bit) Some(out._1.get(ValueLayout.JAVA_LONG, i * 8L)) else None
      }
      (status, values)
    } finally {
      arena.close()
      loader.release()
    }
  }

  private def bits(d: Double): Option[Long] = Some(JDouble.doubleToRawLongBits(d))

  test("a zero divisor of either sign nulls a division under NULL, and NaN does not") {
    val div = new IntArith(IntOp.DIV, Overflow.NULL, x, y)
    val xs = Seq(Some(1.0), Some(0.0), Some(Double.NaN), Some(1.0), Some(-1.0), None, Some(6.0),
      Some(1.0))
    val ys = Seq(Some(-0.0), Some(0.0), Some(0.0), Some(Double.NaN), Some(Double.PositiveInfinity),
      Some(0.0), Some(3.0), None)
    for (lanes <- Seq(2, 4, 8)) {
      val (status, out) = runDouble(div, xs, ys, lanes)
      assert(status === 0)
      assert(out === Seq(None, None, None, bits(Double.NaN), bits(-0.0), None, bits(2.0), None),
        s"lanes=$lanes")
    }
  }

  test("a live zero divisor of either sign declines a FAIL division, and a null dividend over " +
      "it does not") {
    val div = new IntArith(IntOp.DIV, Overflow.FAIL, x, y)
    for (lanes <- Seq(2, 4, 8); zero <- Seq(0.0, -0.0)) {
      // A zero divisor on a live row, at the end of a batch longer than a vector.
      val xs = Seq.fill(9)(Some(1.0)) :+ Some(5.0)
      val ys = Seq.fill(9)(Some(2.0)) :+ Some(zero)
      assert(runDouble(div, xs, ys, lanes)._1 === VarkaFusedKernel.STATUS_DIVIDE_BY_ZERO,
        s"lanes=$lanes zero=$zero")
      // The same zero under a null dividend: null, as Spark's ANSI division answers.
      val (status, out) = runDouble(div, xs.updated(9, None), ys, lanes)
      assert(status === 0, s"lanes=$lanes zero=$zero")
      assert(out === Seq.fill(9)(bits(0.5)) :+ None)
      // A NaN divisor is no zero, under ANSI as anywhere.
      assert(runDouble(div, xs, ys.updated(9, Some(Double.NaN)), lanes)._2(9) === bits(Double.NaN))
    }
  }

  test("a WRAP division answers a zero divisor's infinity or NaN, the IEEE quotient") {
    // The form a literal non-zero divisor takes, and a math composition dividing by a computed
    // value (row 356's `1 / sin(x)`): no test at all.
    val div = new IntArith(IntOp.DIV, Overflow.WRAP, x, y)
    val (status, out) = runDouble(div, Seq(Some(1.0), Some(-1.0), Some(0.0), Some(1.0)),
      Seq(Some(0.0), Some(0.0), Some(-0.0), Some(-0.0)))
    assert(status === 0)
    assert(out === Seq(bits(Double.PositiveInfinity), bits(Double.NegativeInfinity),
      Some(JDouble.doubleToRawLongBits(0.0 / -0.0)), bits(Double.NegativeInfinity)))
  }

  // -------------------------------------------------------------------------------------------
  // The compiled forms against Spark, in every mode
  // -------------------------------------------------------------------------------------------

  private val xa = VarkaSqlResolve.column("x", DoubleType)
  private val ya = VarkaSqlResolve.column("y", DoubleType)
  private val za = VarkaSqlResolve.column("z", DoubleType)
  private val fa = VarkaSqlResolve.column("f", FloatType)
  private val ga = VarkaSqlResolve.column("g", FloatType)
  private val doubleCols: Seq[Attribute] = Seq(xa, ya, za)
  private val floatCols: Seq[Attribute] = Seq(fa, ga)

  private def resolve(sql: String, cols: Seq[Attribute], mode: String): Expression = {
    val conf = SQLConf.get.clone()
    conf.setConfString(SQLConf.ANSI_ENABLED.key, (mode == "ANSI").toString)
    SQLConf.withExistingConf(conf) {
      val text = if (mode == "TRY") sql.replace("x / y", "try_divide(x, y)") else sql
      VarkaSqlResolve.parse(text, cols)
    }
  }

  private def compile(e: Expression, cols: Seq[Attribute]): CompiledVarkaProjection = {
    val plan = VarkaExpressionCompiler.compile(Seq(VarkaSqlResolve.named(e, "c")), cols,
      VarkaMatrix.base)
    assert(plan.isPresent, s"${e.sql} declines: " + VarkaExpressionCompiler.declines(
      Seq(VarkaSqlResolve.named(e, "c")), cols, VarkaMatrix.base))
    plan.get
  }

  /** The bits of a floating answer of Spark's, or of the kernel's; None for a null. */
  private def sparkBits(v: Any): Option[Long] = v match {
    case null => None
    case d: Double => Some(JDouble.doubleToRawLongBits(d))
    case f: Float => Some(JFloat.floatToRawIntBits(f).toLong & 0xffffffffL)
  }

  private def nanBits(b: Long, float: Boolean): Boolean =
    if (float) JFloat.isNaN(JFloat.intBitsToFloat(b.toInt))
    else JDouble.isNaN(JDouble.longBitsToDouble(b))

  /**
   * Runs `sql` compiled over `rows` and holds every row to Spark's `eval` of the same resolved
   * expression: the same bits, or under the NaN rule both NaN where `movesBits` is false. A row
   * Spark raises on must decline the batch with `status`; the rows are then run again without
   * it. Returns how many rows were compared.
   */
  private def checkAgainstSpark(sql: String, cols: Seq[Attribute], rows: Seq[Seq[Any]],
      mode: String, movesBits: Boolean, status: Int = 0): Int = {
    val e = resolve(sql, cols, mode)
    val plan = compile(e, cols)
    val float = e.dataType == FloatType
    // Spark evaluates a RuntimeReplaceable (try_add, try_divide) as its replacement, which the
    // optimizer puts in its place.
    val bound = VarkaSqlResolve.bind(e.transformUp { case r: RuntimeReplaceable => r.replacement },
      cols)
    val spark = rows.map { r =>
      try Right(sparkBits(bound.eval(new GenericInternalRow(r.toArray[Any]))))
      catch { case t: Throwable => Left(t) }
    }
    val inputs = plan.inputOrdinals().asScala.map(_.intValue()).toSeq
    val emitted = emitMulti(plan.outputs().asScala.toSeq, inputs.size, plan.numLiterals())
    val (kernel, loader) = load(emitted)
    val ints = plan.literals().asScala.map(_.intValue()).toArray
    val longs = plan.longLiterals().asScala.map(_.longValue()).toArray
    def run(rs: Seq[Seq[Any]]): (Int, Seq[Option[Long]]) = {
      val n = rs.length
      val arena = Arena.ofConfined()
      try {
        val colsIn = inputs.map { c =>
          if (float) {
            makeInputData(arena, n, i => rs(i)(c) == null,
              i => JFloat.floatToRawIntBits(rs(i)(c).asInstanceOf[Float]))
          } else {
            makeLongInput(arena, n, i => rs(i)(c) == null,
              i => JDouble.doubleToRawLongBits(rs(i)(c).asInstanceOf[Double]))
          }
        }
        val out = if (float) makeOutput(arena, n) else makeLongOutput(arena, n)
        val args = (colsIn.map(_.data.address()).toArray, colsIn.map(_.validityAddress(n)).toArray,
          colsIn.map(_.nullCount).toArray, Array(out._1.address()), Array(out._2.address()))
        val st = if (float) {
          kernel.run(args._1, args._2, args._3, args._4, args._5, ints, n,
            VarkaEmitterTestSupport.scratch(kernel, n))
        } else {
          kernel.run(args._1, args._2, args._3, args._4, args._5, ints, longs, n,
            VarkaEmitterTestSupport.scratch(kernel, n))
        }
        (st, (0 until n).map { i =>
          val bit = (out._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
          if (!bit) None
          else if (float) Some(out._1.get(ValueLayout.JAVA_INT, i * 4L).toLong & 0xffffffffL)
          else Some(out._1.get(ValueLayout.JAVA_LONG, i * 8L))
        })
      } finally {
        arena.close()
      }
    }
    try {
      val raising = spark.zipWithIndex.collect { case (Left(_), i) => i }.toSet
      if (raising.nonEmpty) {
        assert(status !== 0, s"$sql ($mode): Spark raises, and no status is expected")
        spark.collectFirst { case Left(t) => t }.foreach { t =>
          assert(t.isInstanceOf[SparkException] || t.isInstanceOf[ArithmeticException],
            s"$sql ($mode): Spark raises ${t.getClass}")
        }
        assert(run(rows)._1 === status, s"$sql ($mode): the batch Spark raises on is declined")
      }
      val kept = rows.indices.filterNot(raising)
      val (st, got) = run(kept.map(rows))
      assert(st === 0, s"$sql ($mode): declined a batch Spark answers")
      kept.zip(got).foreach { case (i, k) =>
        val want = spark(i).toOption.get
        val agree = (want, k) match {
          case (Some(a), Some(b)) => a == b || !movesBits && nanBits(a, float) && nanBits(b, float)
          case (None, None) => true
          case _ => false
        }
        assert(agree, s"$sql ($mode) over ${rows(i).mkString(", ")}: Spark " +
          want.map(java.lang.Long.toHexString) + ", the kernel " +
          k.map(java.lang.Long.toHexString))
      }
      kept.size
    } finally {
      loader.release()
    }
  }

  private val modes = Seq("LEGACY", "ANSI", "TRY")

  /** 2.7's cells as operand pairs, and the special values against each other. */
  private val doubleCells: Seq[Seq[Any]] = {
    val cells = Seq[(Double, Double)](
      (0.0, -1.0), (-0.0, 0.0), (-0.0, -0.0), (Double.PositiveInfinity, Double.PositiveInfinity),
      (0.0, Double.PositiveInfinity), (1.0, -0.0), (0.0, 0.0), (Double.NaN, 0.0),
      (1.0, Double.NaN), (-1.0, Double.PositiveInfinity), (Double.MaxValue, 2.0),
      (JDouble.MIN_NORMAL, 2.0), (Double.MinPositiveValue, 2.0), (-0.0, 1.0),
      (Double.MaxValue, Double.MaxValue), (-Double.MaxValue, 0.5), (3.0, 7.0))
    val specials = DOUBLE_SPECIALS.map(JDouble.longBitsToDouble).toSeq
    (cells ++ (for (a <- specials; b <- specials) yield (a, b))).map { case (a, b) =>
      Seq[Any](a, b, 1.5)
    } ++ Seq(Seq[Any](null, 0.0, 1.5), Seq[Any](1.0, null, 1.5), Seq[Any](null, null, 1.5))
  }

  private val floatCells: Seq[Seq[Any]] = {
    val specials = FLOAT_SPECIALS.map(JFloat.intBitsToFloat).toSeq
    ((Seq[(Float, Float)]((0.0f, -1.0f), (-0.0f, 0.0f), (-0.0f, -0.0f),
      (Float.PositiveInfinity, Float.PositiveInfinity), (0.0f, Float.PositiveInfinity),
      (Float.MaxValue, 2.0f), (JFloat.MIN_NORMAL, 0.5f), (Float.MinPositiveValue, 0.5f)) ++
      (for (a <- specials; b <- specials) yield (a, b))).map { case (a, b) => Seq[Any](a, b) }) ++
      Seq(Seq[Any](null, 0.0f), Seq[Any](1.0f, null))
  }

  // Forms that compute a value: a NaN from two NaNs is compared by NaN-ness.
  for (sql <- Seq("x + y", "x - y", "x * y", "y - x", "x * 2.5D", "-1.0D * x", "sqrt(x)",
      "degrees(x)", "radians(x)", "x / 3.0D", "x / -0.5D", "x / CAST('NaN' AS DOUBLE)",
      "try_add(x, y)", "try_multiply(x, y)", "(x + y) * z - x")) {
    test(s"DOUBLE $sql fuses and matches Spark in every mode") {
      modes.foreach(m => checkAgainstSpark(sql, doubleCols, doubleCells, m, movesBits = false))
    }
  }

  // Forms that move their operand's bits, compared bit for bit, NaN payloads included.
  for (sql <- Seq("-x", "abs(x)", "signum(x)", "-(-x)", "abs(-x)")) {
    test(s"DOUBLE $sql fuses and matches Spark bit for bit in every mode") {
      modes.foreach(m => checkAgainstSpark(sql, doubleCols, doubleCells, m, movesBits = true))
    }
  }

  test("DOUBLE x / y is null on a zero divisor under LEGACY and TRY, and declines under ANSI") {
    for (m <- Seq("LEGACY", "TRY")) {
      checkAgainstSpark("x / y", doubleCols, doubleCells, m, movesBits = false)
    }
    val compared = checkAgainstSpark("x / y", doubleCols, doubleCells, "ANSI", movesBits = false,
      status = VarkaFusedKernel.STATUS_DIVIDE_BY_ZERO)
    assert(compared < doubleCells.size, "the cells hold zero divisors Spark raises on")
  }

  test("a choice that guards the divisor fuses under ANSI over a batch holding zeros") {
    for (sql <- Seq("IF(y = 0.0D, 0.0D, x / y)", "CASE WHEN y <> 0.0D THEN x / y ELSE 0.0D END")) {
      val compared = checkAgainstSpark(sql, doubleCols, doubleCells, "ANSI", movesBits = false)
      assert(compared === doubleCells.size, sql)
    }
  }

  test("a division by a literal zero of either sign declines by name") {
    for (sql <- Seq("x / 0.0D", "x / -0.0D"); m <- modes) {
      val e = resolve(sql, doubleCols, m)
      val entry = Seq(VarkaSqlResolve.named(e, "c"))
      assert(VarkaExpressionCompiler.compile(entry, doubleCols, VarkaMatrix.base).isEmpty, sql)
      val reasons = VarkaExpressionCompiler.declines(entry, doubleCols, VarkaMatrix.base)
        .values().asScala.map(_.reason())
      assert(reasons.exists(_.contains("a division by a literal zero")), s"$sql: $reasons")
    }
  }

  test("a division by a non-zero literal carries no zero test") {
    for (m <- modes) {
      val plan = compile(resolve("x / 3.0D", doubleCols, m), doubleCols)
      plan.outputs().get(0) match {
        case a: IntArith => assert(a.op() === IntOp.DIV && a.mode() === Overflow.WRAP)
        case other => fail(s"x / 3.0D compiles to $other")
      }
    }
  }

  for (sql <- Seq("f + g", "f - g", "f * g", "g - f", "f * 0.5F")) {
    test(s"FLOAT $sql fuses and matches Spark in every mode") {
      modes.foreach(m => checkAgainstSpark(sql, floatCols, floatCells, m, movesBits = false))
    }
  }

  for (sql <- Seq("-f", "abs(f)")) {
    test(s"FLOAT $sql fuses and matches Spark bit for bit in every mode") {
      modes.foreach(m => checkAgainstSpark(sql, floatCols, floatCells, m, movesBits = true))
    }
  }

  test("signum keeps a zero's sign and a NaN's payload, and answers null for a null") {
    val rows = Seq[Seq[Any]](Seq(0.0, 0.0, 0.0), Seq(-0.0, 0.0, 0.0),
      Seq(Double.PositiveInfinity, 0.0, 0.0), Seq(Double.NegativeInfinity, 0.0, 0.0),
      Seq(Double.NaN, 0.0, 0.0), Seq(JDouble.longBitsToDouble(0xfff8000000000000L), 0.0, 0.0),
      Seq(JDouble.longBitsToDouble(0x7ff8000000000001L), 0.0, 0.0), Seq(null, 0.0, 0.0),
      Seq(Double.MinPositiveValue, 0.0, 0.0), Seq(-Double.MinPositiveValue, 0.0, 0.0))
    for (m <- modes) {
      assert(checkAgainstSpark("signum(x)", doubleCols, rows, m, movesBits = true) === rows.size)
    }
  }

  test("no multiply and add is contracted: (x * y) + z is 0.0 where an FMA gives -2^-54") {
    val a = 1.0 + Math.scalb(1.0, -27)
    val b = 1.0 - Math.scalb(1.0, -27)
    assert(Math.fma(a, b, -1.0) === -Math.scalb(1.0, -54), "the operands are the named case")
    // a * b rounds to 1.0, a tie between 1 - 2^-53 and 1.0 broken to even, so the sum is 0.0.
    assert(a * b + -1.0 === 0.0)
    val rows = Seq.fill(17)(Seq[Any](a, b, -1.0))
    for (m <- modes) {
      assert(checkAgainstSpark("(x * y) + z", doubleCols, rows, m, movesBits = true) === 17)
    }
  }

  // -------------------------------------------------------------------------------------------
  // The JDK's constants, and the sweeps
  // -------------------------------------------------------------------------------------------

  test("Math.toDegrees and Math.toRadians are one multiply by their value at 1.0") {
    val toDeg = Math.toDegrees(1.0)
    val toRad = Math.toRadians(1.0)
    def check(v: Double): Unit = {
      assert(JDouble.doubleToRawLongBits(Math.toDegrees(v)) ===
        JDouble.doubleToRawLongBits(v * toDeg), s"toDegrees($v)")
      assert(JDouble.doubleToRawLongBits(Math.toRadians(v)) ===
        JDouble.doubleToRawLongBits(v * toRad), s"toRadians($v)")
    }
    DOUBLE_SPECIALS.foreach(b => check(JDouble.longBitsToDouble(b)))
    // Every exponent band, a seeded draw of patterns in each; and widened FLOAT patterns.
    val random = new SplittableRandom(342L)
    for (exp <- 0 until 2048; _ <- 0 until 64) {
      val bitsOf = (exp.toLong << 52) | (random.nextLong() & 0xfffffffffffffL)
      check(JDouble.longBitsToDouble(bitsOf))
      check(-JDouble.longBitsToDouble(bitsOf))
    }
    var p = 0L
    while (p < (1L << 32)) {
      check(JFloat.intBitsToFloat(p.toInt).toDouble)
      p += 4099
    }
  }

  private val chunk = 1 << 16

  /**
   * Every FLOAT pattern through `root`, a kernel over one FLOAT column with `lits` its int
   * literal table, each row against `want` of the pattern: the same bits, or both NaN where
   * `nanByNess`. A strided slice is also held to Spark's `spark` expression over the column.
   */
  private def sweepFloat(ctx: String, root: VarkaVectorIR, lits: Array[Int],
      want: Int => Int, nanByNess: Boolean, spark: Option[Expression]): Unit = {
    val emitted = emitMulti(Seq(root), 1, lits.length, VarkaMatrix.base)
    val loaders = new ConcurrentLinkedQueue[VarkaGeneratedClassLoader]()
    val kernels = ThreadLocal.withInitial[VarkaFusedKernel] { () =>
      val (kernel, loader) = load(emitted)
      loaders.add(loader)
      kernel
    }
    val failure = new AtomicReference[String]()
    val chunks = (1L << 32) / chunk
    try {
      LongStream.range(0, chunks).parallel().forEach { k =>
        if (failure.get() == null) {
          val first = k * chunk
          val kernel = kernels.get()
          val arena = Arena.ofConfined()
          try {
            val in = alloc(arena, chunk * 4L)
            var i = 0
            while (i < chunk) {
              in.set(ValueLayout.JAVA_INT, i * 4L, (first + i).toInt)
              i += 1
            }
            val out = makeOutput(arena, chunk)
            val status = kernel.run(Array(in.address()), Array(0L), Array(0),
              Array(out._1.address()), Array(out._2.address()), lits, chunk,
              VarkaEmitterTestSupport.scratch(kernel, chunk))
            if (status != 0) {
              failure.compareAndSet(null, s"$ctx: status $status at pattern $first")
            }
            i = 0
            while (i < chunk && failure.get() == null) {
              val pattern = (first + i).toInt
              val got = out._1.get(ValueLayout.JAVA_INT, i * 4L)
              val expected = want(pattern)
              val same = got == expected || nanByNess &&
                JFloat.isNaN(JFloat.intBitsToFloat(got)) &&
                JFloat.isNaN(JFloat.intBitsToFloat(expected))
              if (!same) {
                failure.compareAndSet(null, f"$ctx: over $pattern%08x the kernel gave $got%08x " +
                  f"and the definition $expected%08x")
              }
              if ((pattern & 0xfff) == 0x5a5) {
                spark.foreach { e =>
                  val v = e.eval(InternalRow(JFloat.intBitsToFloat(pattern)))
                  val sb = JFloat.floatToRawIntBits(v.asInstanceOf[Float])
                  if (sb != got && !(nanByNess && JFloat.isNaN(v.asInstanceOf[Float]))) {
                    failure.compareAndSet(null, f"$ctx: over $pattern%08x the kernel gave " +
                      f"$got%08x and Spark $sb%08x")
                  }
                }
              }
              i += 1
            }
          } finally {
            arena.close()
          }
        }
      }
    } finally {
      loaders.forEach(_.release())
    }
    Option(failure.get()).foreach(message => fail(message))
  }

  private val floatCol = BoundReference(0, FloatType, nullable = true)

  test("the negation and abs of every FLOAT pattern are Spark's bits") {
    sweepFloat("-f", new IntNeg(Overflow.WRAP, f), Array.empty,
      p => JFloat.floatToRawIntBits(-JFloat.intBitsToFloat(p)), nanByNess = false,
      Some(UnaryMinus(floatCol)))
    sweepFloat("abs(f)", new IntNeg(Overflow.WRAP, f, true), Array.empty,
      p => JFloat.floatToRawIntBits(Math.abs(JFloat.intBitsToFloat(p))), nanByNess = false,
      Some(Abs(floatCol)))
  }

  private def sweepsEnabled: Boolean = System.getProperty("varka.sweep") == "true"

  test("+, - and * of every FLOAT pattern against sixteen operands are Java's float operators " +
      "(opt-in: -Dvarka.sweep=true; VARKA-342)") {
    assume(sweepsEnabled, "set -Dvarka.sweep=true to sweep the emitted kernels")
    val k = new LiteralSlot(0, LaneType.FLOAT)
    for (operand <- SWEEP_OPERANDS; op <- Seq(IntOp.ADD, IntOp.SUB, IntOp.MUL)) {
      val b = JFloat.intBitsToFloat(operand)
      val want: Int => Int = p => {
        val a = JFloat.intBitsToFloat(p)
        JFloat.floatToRawIntBits(op match {
          case IntOp.ADD => a + b
          case IntOp.SUB => a - b
          case IntOp.MUL => a * b
          case IntOp.DIV => a / b
        })
      }
      sweepFloat(f"$op by $operand%08x", new IntArith(op, Overflow.WRAP, f, k), Array(operand),
        want, nanByNess = true, None)
    }
  }
}

object VarkaEmitterFloatingArithSuite {

  /**
   * The DOUBLE special values as bits: three NaNs (quiet, negative, a payload), both zeros, both
   * infinities, the extremes, the smallest subnormal and the smallest normal in both signs, and
   * three normals.
   */
  val DOUBLE_SPECIALS: Array[Long] = Array(
    0x7ff8000000000000L, 0xfff8000000000000L, 0x7ff8000000000001L,
    JDouble.doubleToRawLongBits(0.0), JDouble.doubleToRawLongBits(-0.0),
    JDouble.doubleToRawLongBits(Double.PositiveInfinity),
    JDouble.doubleToRawLongBits(Double.NegativeInfinity),
    JDouble.doubleToRawLongBits(Double.MaxValue), JDouble.doubleToRawLongBits(-Double.MaxValue),
    JDouble.doubleToRawLongBits(Double.MinPositiveValue),
    JDouble.doubleToRawLongBits(-Double.MinPositiveValue),
    JDouble.doubleToRawLongBits(JDouble.MIN_NORMAL),
    JDouble.doubleToRawLongBits(-JDouble.MIN_NORMAL),
    JDouble.doubleToRawLongBits(1.5), JDouble.doubleToRawLongBits(-2.5),
    JDouble.doubleToRawLongBits(Math.sqrt(Double.MaxValue)))

  /** The same at FLOAT, and the sixteen operands of the binary sweeps. */
  val FLOAT_SPECIALS: Array[Int] = Array(
    0x7fc00000, 0xffc00000, 0x7fc00001,
    JFloat.floatToRawIntBits(0.0f), JFloat.floatToRawIntBits(-0.0f),
    JFloat.floatToRawIntBits(Float.PositiveInfinity),
    JFloat.floatToRawIntBits(Float.NegativeInfinity),
    JFloat.floatToRawIntBits(Float.MaxValue), JFloat.floatToRawIntBits(-Float.MaxValue),
    JFloat.floatToRawIntBits(Float.MinPositiveValue),
    JFloat.floatToRawIntBits(-Float.MinPositiveValue),
    JFloat.floatToRawIntBits(JFloat.MIN_NORMAL), JFloat.floatToRawIntBits(-JFloat.MIN_NORMAL),
    JFloat.floatToRawIntBits(1.5f), JFloat.floatToRawIntBits(-2.5f),
    JFloat.floatToRawIntBits(3.0f))

  val SWEEP_OPERANDS: Array[Int] = FLOAT_SPECIALS
}
