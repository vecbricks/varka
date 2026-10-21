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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.LongStream

import org.apache.spark.sql.catalyst.expressions.{BitmapExpressionUtils, BitwiseAnd, BitwiseCount, BitwiseNot, BitwiseOr, BitwiseXor, BoundReference, Expression, ShiftLeft, ShiftRight, ShiftRightUnsigned, SpecificInternalRow}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.types.IntegerType

/**
 * The bitwise family (VARKA-335): `&`, `|`, `^`, `~`, the three shifts and `bit_count` at both
 * lanes, and the bitmap functions as the compiler builds them, against the reference evaluator
 * and against Spark's own expressions.
 *
 * The operations are Java's, and the one place the emitted code could part from them is the
 * shift count: the Vector API masks it to the lane's width, which is what Java's operators do,
 * so the counts here run past the width and below zero. `bit_count` is the other place, since
 * the int lane counts the 32 bits a negative value gains in the widening without widening it.
 *
 * The exhaustive sweeps, every int through `~` and `bit_count` and every int through each shift,
 * run under `-Dvarka.sweep=true` (the nightly's sweep step); a strided slice of the same sweeps
 * runs on every build.
 */
class VarkaEmitterBitwiseSuite extends VarkaEmitterTestBase with VarkaOwnJvm {

  private val c0 = new ColumnRef(0)
  private val c1 = new ColumnRef(1)
  private val lc0 = new ColumnRef(0, LaneType.LONG)
  private val lc1 = new ColumnRef(1, LaneType.LONG)

  /** The shift counts the lanes must mask as Java does: below zero, inside and past the width. */
  private val counts: Array[Int] = (-64 to 64).toArray ++ Array(Int.MinValue, Int.MaxValue, 1000)

  private def shiftCount(col: Int, i: Int): Int =
    if (col == 0) extreme(0, i) * (i % 7 + 1) + i else counts(i % counts.length)

  test("the bitwise operations and bit_count compute what the reference says at the int lane, " +
      "every shift count included, at both widths") {
    val roots = BitOpKind.values().toSeq.map(op => new BitOp(op, c0, c1): VarkaVectorIR) ++
      Seq(new BitOp(BitOpKind.XOR, c0, new LiteralSlot(0)), new BitCount(c0),
        new BitOp(BitOpKind.SHL, c0, new LiteralSlot(1)))
    // The literals: -1, which is how `~x` compiles, and a count of 33, which masks to 1.
    for (lanes <- Seq(4, 16)) {
      checkMatrix(roots, 2, Array(-1, 33), Seq(1, 13, 17, 64, 1000), combos(2),
        data = shiftCount, ctx = s"bitwise lanes=$lanes",
        options = VarkaMatrix.base.withLanesOverride(lanes))
    }
  }

  test("the bitwise operations and bit_count compute what the reference says at the long lane, " +
      "at both widths") {
    def values(col: Int, i: Int): Long = {
      val edges = Array(Long.MinValue, Long.MaxValue, -1L, 0L, 1L, Long.MinValue + 1,
        1L << 40, -(1L << 40), Int.MinValue.toLong, Int.MaxValue.toLong + 1)
      if (col == 1 && i % 3 == 0) counts(i % counts.length).toLong
      else if (i < edges.length) edges((i + col) % edges.length)
      else i.toLong * 0x9E3779B97F4A7C15L + col
    }
    val roots = BitOpKind.values().toSeq.map(op => new BitOp(op, lc0, lc1): VarkaVectorIR) ++
      Seq(new BitOp(BitOpKind.XOR, lc0, new LiteralSlot(0, LaneType.LONG)), new BitCount(lc0),
        new BitOp(BitOpKind.SHR, lc0, new LiteralSlot(1, LaneType.LONG)))
    // -1, and a count of 65, which masks to 1 at this lane.
    for (lanes <- Seq(2, 8)) {
      checkLongMatrix(roots, 2, Array(-1L, 65L), Seq(1, 7, 64, 129), combos(2), values,
        "bitwise", lanes)
    }
  }

  test("bit_count of a bigint as a narrowing root stores the count as an int, at both widths") {
    val root = new NarrowLane(new BitCount(lc0))
    def value(i: Int): Long = Array(Long.MinValue, -1L, 0L, 1L, Long.MaxValue)
      .lift(i).getOrElse(i.toLong * 0x9E3779B97F4A7C15L)
    for (lanes <- Seq(2, 8)) {
      val (kernel, loader) = load(emitMulti(Seq(root), 1, 0,
        VarkaMatrix.base.withLanesOverride(lanes)))
      try {
        for (length <- Seq(1, 7, 64, 129); (patternName, isNull) <- nullPatterns) {
          val arena = Arena.ofConfined()
          try {
            val in = makeLongInput(arena, length, isNull, value)
            val out = makeOutput(arena, length)
            val status = kernel.run(Array(in.data.address()), Array(in.validityAddress(length)),
              Array(in.nullCount), Array(out._1.address()), Array(out._2.address()),
              Array.empty[Int], Array.empty[Long], length)
            assert(status === 0, s"lanes=$lanes len=$length $patternName: status $status")
            for (i <- 0 until length) {
              val bit = (out._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
              assert(bit === !isNull(i), s"lanes=$lanes len=$length $patternName row $i")
              if (!isNull(i)) {
                assert(out._1.get(ValueLayout.JAVA_INT, i * 4L) ===
                  java.lang.Long.bitCount(value(i)), s"lanes=$lanes $patternName row $i")
              }
            }
          } finally {
            arena.close()
          }
        }
      } finally {
        loader.release()
      }
    }
  }

  test("a bigint shifted by an int column, and bit_count of a bigint under another node, " +
      "compute Spark's formula in a kernel of both lanes, at both widths") {
    // Spark's ShiftLeft, ShiftRight and ShiftRightUnsigned are Java's `long << int`, `>>` and
    // `>>>`, which read the low six bits of the int count; BitwiseCount is Long.bitCount.
    val count = new WidenLane(c1)
    val roots: Seq[VarkaVectorIR] = Seq(
      new BitOp(BitOpKind.SHL, lc0, count), new BitOp(BitOpKind.SHR, lc0, count),
      new BitOp(BitOpKind.USHR, lc0, count),
      // The count is itself a narrowed popcount of the value: both conversions in one kernel.
      new BitOp(BitOpKind.SHL, lc0, new WidenLane(new NarrowLane(new BitCount(lc0)))),
      // A long-lane value computed from an int-lane one that came from the long lane.
      new BitOp(BitOpKind.XOR, lc0, new WidenLane(
        new BitOp(BitOpKind.AND, new NarrowLane(new BitCount(lc0)), c1))),
      new NarrowLane(new BitCount(lc0)))
    val longRoots = 5
    val intCounts = Array(-1, 0, 1, 31, 32, 33, 63, 64, 65, 127, 128, Int.MinValue,
      Int.MaxValue)
    val edges = Array(0L, 1L, -1L, Long.MaxValue, Long.MinValue, 1L << 32, -(1L << 32) - 1,
      0x5555555555555555L, 0xAAAAAAAAAAAAAAAAL, Long.MinValue + 1)
    def value(i: Int): Long = edges(i % edges.length) + (i / edges.length)
    // Every value meets every count over the batch: the value cycles faster than the count.
    def intCount(i: Int): Int = intCounts((i / edges.length) % intCounts.length)
    val expected: Seq[(Long, Int) => Long] = Seq(
      (v, c) => v << c, (v, c) => v >> c, (v, c) => v >>> c,
      (v, _) => v << java.lang.Long.bitCount(v),
      (v, c) => v ^ (java.lang.Long.bitCount(v) & c).toLong,
      (v, _) => java.lang.Long.bitCount(v).toLong)
    for (lanes <- Seq(2, 8)) {
      val (kernel, loader) = load(emitMulti(roots, 2, 0,
        VarkaMatrix.base.withLanesOverride(lanes)))
      try {
        val length = edges.length * intCounts.length * 2 + 3
        for ((leftName, leftNull) <- nullPatterns; (rightName, rightNull) <- nullPatterns) {
          val arena = Arena.ofConfined()
          try {
            val longIn = makeLongInput(arena, length, leftNull, value)
            val intIn = makeInputData(arena, length, rightNull, intCount)
            val longOuts = (0 until longRoots).map(_ => makeLongOutput(arena, length))
            val intOut = makeOutput(arena, length)
            val outs = longOuts.map(o => (o._1, o._2)) :+ intOut
            val status = kernel.run(
              Array(longIn.data.address(), intIn.data.address()),
              Array(longIn.validityAddress(length), intIn.validityAddress(length)),
              Array(longIn.nullCount, intIn.nullCount),
              outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
              Array.empty[Int], Array.empty[Long], length)
            val where = s"lanes=$lanes left=$leftName right=$rightName"
            assert(status === 0, s"$where: status $status")
            for (i <- 0 until length; (want, o) <- expected.zipWithIndex) {
              // Roots 3 and 5 read the long column alone; the others read the int column too.
              val validWanted = !leftNull(i) && (o == 3 || o == 5 || !rightNull(i))
              val bit = (outs(o)._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
              assert(bit === validWanted, s"$where root $o row $i: validity")
              if (validWanted) {
                val w = want(value(i), intCount(i))
                if (o < longRoots) {
                  assert(outs(o)._1.get(ValueLayout.JAVA_LONG, i * 8L) === w,
                    s"$where root $o row $i: v=${value(i)} c=${intCount(i)}")
                } else {
                  assert(outs(o)._1.get(ValueLayout.JAVA_INT, i * 4L) === w.toInt,
                    s"$where root $o row $i: v=${value(i)}")
                }
              }
            }
          } finally {
            arena.close()
          }
        }
      } finally {
        loader.release()
      }
    }
  }

  /**
   * The bitmap functions as `VarkaNodeCompiler.bitmap` builds them, with its literal table: 0, 1,
   * then the mask or the shift.
   */
  private def bitmapIr(position: Boolean): VarkaVectorIR = {
    val zero = new LiteralSlot(0, LaneType.LONG)
    val one = new LiteralSlot(1, LaneType.LONG)
    val positive = new Compare(CompareOp.GT, lc0, zero)
    val w = new IfElse(positive, new IntArith(IntOp.SUB, Overflow.WRAP, lc0, one),
      new IntNeg(Overflow.WRAP, lc0))
    val third = new LiteralSlot(2, LaneType.LONG)
    if (position) {
      new BitOp(BitOpKind.AND, w, third)
    } else {
      val q = new BitOp(BitOpKind.USHR, w, third)
      new IfElse(positive, new IntArith(IntOp.ADD, Overflow.WRAP, q, one),
        new IntNeg(Overflow.WRAP, q))
    }
  }

  test("the bitmap functions as the compiler builds them agree with BitmapExpressionUtils") {
    // The named cases of `VARKA-335.md` 2 item 5 and the edges either side of each bucket's
    // ends, then a spread over the whole long; `bitmap.smt2` proves the identities over every
    // long, and this checks the emitted kernel computes the tree the proof is about.
    val n = BitmapExpressionUtils.NUM_BITS.toLong
    val named = Array(Long.MinValue, Long.MinValue + 1, -1L, 0L, 1L, n, n + 1, n - 1, -n, -n - 1,
      -n + 1, 2 * n, 2 * n + 1, Long.MaxValue, Long.MaxValue - 1)
    def value(i: Int): Long =
      if (i < named.length) named(i) else i.toLong * 0x9E3779B97F4A7C15L
    // The compiler's shift, derived from Spark's constant as the compiler derives it.
    val shift = java.lang.Long.numberOfTrailingZeros(n).toLong
    for ((position, third) <- Seq((true, (1L << shift) - 1), (false, shift)); lanes <- Seq(2, 8)) {
      val (kernel, loader) = load(emitMulti(Seq(bitmapIr(position)), 1, 3,
        VarkaMatrix.base.withLanesOverride(lanes)))
      try {
        // Every null pattern, so the masked body's blends and their shared comparison meet a
        // null lane as well as the dense body's.
        for ((patternName, isNull) <- nullPatterns) {
          val arena = Arena.ofConfined()
          try {
            val length = 4099
            val in = makeLongInput(arena, length, isNull, value)
            val out = makeLongOutput(arena, length)
            val status = kernel.run(Array(in.data.address()), Array(in.validityAddress(length)),
              Array(in.nullCount), Array(out._1.address()), Array(out._2.address()),
              Array.empty[Int], Array(0L, 1L, third), length)
            assert(status === 0)
            for (i <- 0 until length) {
              val where = s"position=$position lanes=$lanes $patternName row $i"
              val bit = (out._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
              assert(bit === !isNull(i), s"$where: validity")
              if (!isNull(i)) {
                val v = value(i)
                val want = if (position) BitmapExpressionUtils.bitmapBitPosition(v)
                  else BitmapExpressionUtils.bitmapBucketNumber(v)
                assert(out._1.get(ValueLayout.JAVA_LONG, i * 8L) === want, s"$where: v=$v")
              }
            }
          } finally {
            arena.close()
          }
        }
      } finally {
        loader.release()
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // The sweeps: the emitted kernel against Spark's own expression, every row
  // -------------------------------------------------------------------------------------------

  /** The rows of one sweep chunk. */
  private val chunk = 1 << 16

  /**
   * Runs `root`, an int-lane kernel over `inputs` int columns with the literals `lits`, over
   * `rows` rows, whose column `c` at row `r` is `input(r, c)`, and compares every row with what
   * `expr`, Spark's own expression over the same columns as bound references, evaluates to.
   * Chunks run in parallel, each worker on a kernel of its own; the first difference fails.
   */
  private def sweep(ctx: String, root: VarkaVectorIR, inputs: Int, lits: Array[Int], rows: Long,
      input: (Long, Int) => Int, expr: Expression): Unit = {
    val emitted = emitMulti(Seq(root), inputs, lits.length, VarkaMatrix.base)
    val loaders = new ConcurrentLinkedQueue[VarkaGeneratedClassLoader]()
    val kernels = ThreadLocal.withInitial[VarkaFusedKernel] { () =>
      val (kernel, loader) = load(emitted)
      loaders.add(loader)
      kernel
    }
    val failure = new AtomicReference[String]()
    val chunks = (rows + chunk - 1) / chunk
    try {
      LongStream.range(0, chunks).parallel().forEach { k =>
        if (failure.get() == null) {
          val first = k * chunk
          val n = math.min(chunk.toLong, rows - first).toInt
          val kernel = kernels.get()
          val row = new SpecificInternalRow(Seq.fill(inputs)(IntegerType))
          val arena = Arena.ofConfined()
          try {
            val cols = (0 until inputs).map(_ => alloc(arena, n * 4L))
            for (c <- 0 until inputs; i <- 0 until n) {
              cols(c).set(ValueLayout.JAVA_INT, i * 4L, input(first + i, c))
            }
            val out = makeOutput(arena, n)
            val status = kernel.run(cols.map(_.address()).toArray, Array.fill(inputs)(0L),
              Array.fill(inputs)(0), Array(out._1.address()), Array(out._2.address()), lits, n,
              VarkaEmitterTestSupport.scratch(kernel, n))
            if (status != 0) {
              failure.compareAndSet(null, s"$ctx: status $status at row $first")
            }
            var i = 0
            while (i < n && failure.get() == null) {
              for (c <- 0 until inputs) {
                row.setInt(c, input(first + i, c))
              }
              val want = expr.eval(row).asInstanceOf[Int]
              val got = out._1.get(ValueLayout.JAVA_INT, i * 4L)
              if (got != want) {
                val operands = (0 until inputs).map(c => input(first + i, c)).mkString(", ")
                failure.compareAndSet(null, s"$ctx: over ($operands) the kernel gave $got and " +
                  s"Spark $want")
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

  private def int(ordinal: Int): BoundReference = BoundReference(ordinal, IntegerType, false)

  /** Every int, in row order. */
  private val everyInt: (Long, Int) => Int = (r, _) => r.toInt

  /** A strided slice of the ints: 2^24 rows 257 apart, so every byte of the value varies. */
  private val sliceRows = 1L << 24
  private val slice: (Long, Int) => Int = (r, _) => (r * 257 + 0x80000000L).toInt

  private def sweepsEnabled: Boolean = System.getProperty("varka.sweep") == "true"

  /** The unary sweeps: `~` as the compiler builds it, `x ^ -1`, and `bit_count`. */
  private def unarySweeps(rows: Long, input: (Long, Int) => Int, what: String): Unit = {
    sweep(s"~ over $what", new BitOp(BitOpKind.XOR, c0, new LiteralSlot(0)), 1, Array(-1), rows,
      input, BitwiseNot(int(0)))
    sweep(s"bit_count over $what", new BitCount(c0), 1, Array.empty, rows, input,
      BitwiseCount(int(0)))
  }

  /**
   * The shifts, each value with one count: the count of row `r` cycles through `[-64, 64]`, so
   * every value is shifted and every count meets every residue of the values it is paired with.
   */
  private def shiftSweeps(rows: Long, value: (Long, Int) => Int, what: String): Unit = {
    val input: (Long, Int) => Int = (r, c) => if (c == 0) value(r, 0) else (r % 129).toInt - 64
    for ((op, expr) <- Seq(
        BitOpKind.SHL -> ShiftLeft(int(0), int(1)),
        BitOpKind.SHR -> ShiftRight(int(0), int(1)),
        BitOpKind.USHR -> ShiftRightUnsigned(int(0), int(1)))) {
      sweep(s"$op over $what", new BitOp(op, c0, c1), 2, Array.empty, rows, input, expr)
    }
  }

  test("~ and bit_count match Spark over a strided slice of the ints") {
    unarySweeps(sliceRows, slice, "the slice")
  }

  test("the shifts match Spark over a strided slice of the ints, every count in [-64, 64]") {
    shiftSweeps(sliceRows, slice, "the slice")
  }

  test("&, | and ^ match Spark over a slice of int pairs") {
    // Two operands of 32 bits each are 64 bits, past exhaustion (m8/PLAN.md 5): the pairs are
    // the slice against a second stride, so every bit of both operands varies.
    val input: (Long, Int) => Int = (r, c) =>
      if (c == 0) slice(r, 0) else (r * 0x9E3779B1L).toInt
    for ((op, expr) <- Seq(
        BitOpKind.AND -> BitwiseAnd(int(0), int(1)),
        BitOpKind.OR -> BitwiseOr(int(0), int(1)),
        BitOpKind.XOR -> BitwiseXor(int(0), int(1)))) {
      sweep(s"$op over the pairs", new BitOp(op, c0, c1), 2, Array.empty, sliceRows, input, expr)
    }
  }

  test("~ and bit_count match Spark over every int (opt-in: -Dvarka.sweep=true; VARKA-335)") {
    assume(sweepsEnabled, "set -Dvarka.sweep=true to sweep the emitted kernels")
    unarySweeps(1L << 32, everyInt, "every int")
  }

  test("the shifts match Spark over every int, with every count in [-64, 64] " +
      "(opt-in: -Dvarka.sweep=true; VARKA-335)") {
    assume(sweepsEnabled, "set -Dvarka.sweep=true to sweep the emitted kernels")
    shiftSweeps(1L << 32, everyInt, "every int")
  }
}
