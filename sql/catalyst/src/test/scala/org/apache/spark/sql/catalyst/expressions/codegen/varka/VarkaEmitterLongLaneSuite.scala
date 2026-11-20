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

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.{Alias, AttributeReference, EvalMode, Expression,
  Literal, Multiply, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{VarkaExpressionCompiler, VarkaNodeCompiler}

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.{isEpilogue, isLoop}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.types.LongType

/**
 * The 64-bit lane: the long matrix against the reference evaluator at both widths, the narrowing
 * root that stores four bytes a row, and the range guard that makes an out-of-range lane a decline
 * rather than a wrap.
 */
class VarkaEmitterLongLaneSuite extends VarkaEmitterTestBase with VarkaOwnJvm {

  test("the long lane computes what the reference says, at both its widths") {
    // VARKA-85 step 4's proof: the lane-generic subset of the IR emitted against LongVector and
    // run over 64-bit buffers, at the long lane's own 2 and 8 counts - 128 and 512 bits, the
    // same two widths the int matrix uses at 4 and 16. The values straddle the int range on
    // purpose: `1L << 40` and its neighbours are numbers a 32-bit lane cannot hold, so a
    // kernel that had silently kept int descriptors would differ on the first row rather than
    // agreeing by accident.
    val col = new ColumnRef(0, LaneType.LONG)
    val col1 = new ColumnRef(1, LaneType.LONG)
    val lit = new LiteralSlot(0, LaneType.LONG)
    val lits = Array(3L << 32)
    def values(c: Int, i: Int): Long = (1L << 40) + i.toLong * (c + 1) * (1L << 20) - (i % 5)
    val cases: Seq[(String, Seq[VarkaVectorIR])] = Seq(
      "a column, copied" -> Seq(col),
      "a literal, broadcast" -> Seq(lit),
      "wrapping add, subtract and multiply" -> Seq(
        new IntArith(IntOp.ADD, Overflow.WRAP, col, col1),
        new IntArith(IntOp.SUB, Overflow.WRAP, col, lit),
        new IntArith(IntOp.MUL, Overflow.WRAP, col, col1)),
      "checked add and subtract" -> Seq(
        new IntArith(IntOp.ADD, Overflow.FAIL, col, lit),
        new IntArith(IntOp.SUB, Overflow.FAIL, col1, lit)),
      "try_add and try_subtract" -> Seq(
        new IntArith(IntOp.ADD, Overflow.NULL, col, col1),
        new IntArith(IntOp.SUB, Overflow.NULL, col, col1)),
      "negate, wrapping and checked" -> Seq(
        new IntNeg(Overflow.WRAP, col), new IntNeg(Overflow.FAIL, col1)),
      "the hull ops" -> Seq(new Greatest(col, col1), new Least(col, lit)),
      "a conditional over a comparison" -> Seq(
        new IfElse(new Compare(CompareOp.LT, col, col1), col, lit)),
      "every comparison as a selection root" -> Seq(
        new Compare(CompareOp.LT, col, col1), new Compare(CompareOp.GE, col, lit)),
      "three-valued logic over the comparisons" -> Seq(
        new And(new Compare(CompareOp.GT, col, lit), new IsNotNull(col1)),
        new Or(new Not(new Compare(CompareOp.EQ, col, col1)), new IsNotNull(col))))
    for (lanes <- Seq(2, 8); (name, roots) <- cases) {
      checkLongMatrix(roots, 2, lits, Seq(1, 7, 64, 129), combos(2), values, name, Some(lanes))
    }
  }

  /**
   * Values that put the 64-bit sign test under load: both extremes, their neighbours, zero,
   * and magnitudes an int lane cannot hold - so a lowering that had kept 32-bit descriptors
   * disagrees on value as well as on overflow.
   */
  private def extremeLong(col: Int, i: Int): Long = {
    val vs = Array(Long.MaxValue, Long.MinValue, Long.MaxValue - 1, Long.MinValue + 1, 0L, 1L,
      -1L, 1L << 40, -(1L << 40), Int.MaxValue.toLong + 1L, Long.MaxValue / 2,
      Long.MinValue / 2)
    vs((i + col * 5) % vs.length)
  }

  /** The same spread, kept small enough that no add or subtract over two of them overflows. */
  private def smallLong(col: Int, i: Int): Long = {
    val vs = Array(0L, 1L, -1L, 7L, -7L, 1L << 20, -(1L << 20), 1L << 40, -(1L << 40))
    vs((i + col * 3) % vs.length)
  }

  test("long WRAP and NULL arithmetic match the reference over the extremes, and long FAIL " +
      "matches wherever it does not have to decline") {
    // The int lane's overflow matrix, re-run at 64 bits. Until this existed the long matrix
    // drove the checked and nulling modes over values far from either extreme, so the sign
    // test they exist for never fired once: every row took the non-overflowing path, and a
    // lowering that tested the wrong half of a 64-bit operand would have passed.
    val a = new ColumnRef(0, LaneType.LONG)
    val b = new ColumnRef(1, LaneType.LONG)
    val lengths = Seq(0, 1, 7, 17, 64, 129)
    val noLits = Array.empty[Long]
    for (lanes <- Seq(2, 8)) {
      // WRAP and NULL are total over any input - one wraps, the other nulls the overflowing
      // lane - so both leave the batch computed and the extremes are fair game.
      for (mode <- Seq(Overflow.WRAP, Overflow.NULL); op <- Seq(IntOp.ADD, IntOp.SUB)) {
        checkLongMatrix(Seq(new IntArith(op, mode, a, b)), 2, noLits, lengths, combos(2),
          extremeLong, s"$op $mode over the long extremes", Some(lanes))
      }
      checkLongMatrix(Seq(new IntArith(IntOp.MUL, Overflow.WRAP, a, b)), 2, noLits, lengths,
        combos(2), extremeLong, "MUL WRAP over the long extremes", Some(lanes))
      // Negation's own extreme: `-Long.MinValue` is `Long.MinValue`, which WRAP publishes and
      // the checked form refuses.
      checkLongMatrix(Seq[VarkaVectorIR](new IntNeg(Overflow.WRAP, a)), 1, noLits, lengths,
        combos(1), extremeLong, "neg WRAP over the long extremes", Some(lanes))
      // A bigint's `abs` (VARKA-104): `abs(Long.MinValue)` is itself in the legacy mode.
      checkLongMatrix(Seq[VarkaVectorIR](new IntNeg(Overflow.WRAP, a, true)), 1, noLits,
        lengths, combos(1), extremeLong, "abs WRAP over the long extremes", Some(lanes))
      // FAIL condemns the batch instead of answering it, so its matrix arm runs over operands
      // no add or subtract can push out of range; the status assertion inside the matrix is
      // then the claim that it did not condemn one anyway.
      for (op <- Seq(IntOp.ADD, IntOp.SUB)) {
        checkLongMatrix(Seq(new IntArith(op, Overflow.FAIL, a, b)), 2, noLits, lengths,
          combos(2), smallLong, s"$op FAIL inside the long range", Some(lanes))
      }
      checkLongMatrix(Seq[VarkaVectorIR](new IntNeg(Overflow.FAIL, a)), 1, noLits, lengths,
        combos(1), smallLong, "neg FAIL inside the long range", Some(lanes))
      checkLongMatrix(Seq[VarkaVectorIR](new IntNeg(Overflow.FAIL, a, true)), 1, noLits,
        lengths, combos(1), smallLong, "abs FAIL inside the long range", Some(lanes))
    }
    // The two refusals the int lane pins, re-pinned at this one: a checked multiply has no
    // correct emission at either lane - the 128-bit product VARKA-104 needs is missing at both
    // - and `try_negative` is not a Spark function, so a NULL negate is a shape nothing can
    // produce and is refused rather than lowered into a plain negation.
    for (mode <- Seq(Overflow.FAIL, Overflow.NULL)) {
      val refused = intercept[IllegalArgumentException] {
        emitMulti(Seq[VarkaVectorIR](new IntArith(IntOp.MUL, mode, a, b)), 2, 0)
      }
      assert(refused.getMessage.contains("checked multiply"), s"$mode: ${refused.getMessage}")
    }
    val refusedNeg = intercept[IllegalArgumentException] {
      emitMulti(Seq[VarkaVectorIR](new IntNeg(Overflow.NULL, a)), 1, 0)
    }
    assert(refusedNeg.getMessage.contains("IntNeg has no NULL mode"), refusedNeg.getMessage)
  }

  test("a long FAIL lane that overflows condemns the batch - in a loop lane, in an " +
      "epilogue lane, not under a null, and not with the check off") {
    val root = new IntArith(IntOp.ADD, Overflow.FAIL,
      new ColumnRef(0, LaneType.LONG), new ColumnRef(1, LaneType.LONG))
    val (kernel, loader) = load(emitMulti(Seq[VarkaVectorIR](root), 2, 0))
    val (kernelOff, loaderOff) = load(emitMulti(Seq[VarkaVectorIR](root), 2, 0, checkOff))
    try {
      val arena = Arena.ofConfined()
      try {
        // Lane `at` overflows on the sum and every other lane is a small pair. The overflow is
        // one an int lane would not see: `Long.MaxValue + 1` is in range for the narrower type
        // and out of range here, so a kernel left testing 32-bit signs reports nothing.
        def left(at: Int)(i: Int): Long = if (i == at) Long.MaxValue else i % 5
        def right(at: Int)(i: Int): Long = if (i == at) 1L else i % 3
        def status(k: VarkaFusedKernel, length: Int, at: Int,
            nullL: Int => Boolean, nullR: Int => Boolean): Int = {
          val l = makeLongInput(arena, length, nullL, left(at), poisonNulls = false)
          val r = makeLongInput(arena, length, nullR, right(at), poisonNulls = false)
          val out = makeLongOutput(arena, length)
          k.run(Array(l.data.address(), r.data.address()),
            Array(l.validityAddress(length), r.validityAddress(length)),
            Array(l.nullCount, r.nullCount),
            Array(out._1.address()), Array(out._2.address()),
            Array.empty[Int], Array.empty[Long], length)
        }
        val none = (_: Int) => false
        assert(status(kernel, 64, -1, none, none) === 0, "nothing overflows")
        // A loop lane, the same lane in the masked body, then a lane only the epilogue covers
        // whatever this host's long lane count turns out to be.
        assert(status(kernel, 64, 3, none, none) === VarkaFusedKernel.STATUS_INT_OVERFLOW)
        assert(status(kernel, 64, 3, _ == 40, none) === VarkaFusedKernel.STATUS_INT_OVERFLOW)
        assert(status(kernel, 17, 16, none, none) === VarkaFusedKernel.STATUS_INT_OVERFLOW)
        assert(status(kernel, 17, 16, _ == 2, none) === VarkaFusedKernel.STATUS_INT_OVERFLOW)
        // The overflowing lane under a null operand: the row is null, its data lane is
        // undefined, and a batch must not be condemned for arithmetic nobody asked for.
        assert(status(kernel, 64, 3, none, _ == 3) === 0)
        assert(status(kernel, 64, 3, _ == 3, none) === 0)
        assert(status(kernel, 17, 16, none, _ == 16) === 0)
        // With the check off the same batch is computed, wrapping where ANSI says raise -
        // which is why that flag is a benchmark arm and not a config.
        assert(status(kernelOff, 64, 3, none, none) === 0)
        assert(status(kernelOff, 17, 16, none, none) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
      loaderOff.release()
    }
  }

  test("a checked long negation and abs condemn the batch on Long.MinValue alone, not under a " +
      "null (VARKA-104)") {
    val col = new ColumnRef(0, LaneType.LONG)
    for (abs <- Seq(false, true)) {
      val (kernel, loader) = load(emitMulti(Seq[VarkaVectorIR](
        new IntNeg(Overflow.FAIL, col, abs)), 1, 0))
      try {
        val arena = Arena.ofConfined()
        try {
          // Long.MinValue + 1 negates to Long.MaxValue, the last value that does not overflow.
          def status(length: Int, at: Int, value: Long, nulled: Int => Boolean): Int = {
            val in = makeLongInput(arena, length, nulled,
              i => if (i == at) value else i % 7 - 3, poisonNulls = false)
            val out = makeLongOutput(arena, length)
            kernel.run(Array(in.data.address()), Array(in.validityAddress(length)),
              Array(in.nullCount), Array(out._1.address()), Array(out._2.address()),
              Array.empty[Int], Array.empty[Long], length)
          }
          val none = (_: Int) => false
          withClue(s"abs=$abs") {
            assert(status(64, 3, Long.MinValue + 1, none) === 0)
            assert(status(64, 3, Long.MinValue, none) === VarkaFusedKernel.STATUS_INT_OVERFLOW)
            assert(status(17, 16, Long.MinValue, none) === VarkaFusedKernel.STATUS_INT_OVERFLOW)
            assert(status(64, 3, Long.MinValue, _ == 3) === 0)
          }
        } finally {
          arena.close()
        }
      } finally {
        loader.release()
      }
    }
  }

  test("a narrowing root stores four bytes a row of what the long lane computed, at both widths") {
    // Route A of `VARKA-102.md` 8.3: the extracts of a TIME are 64-bit divisions whose
    // results are ints, and a `NarrowLane` root stores the low half of each lane at `i * 4`
    // where a wide root stores the whole lane at `i * 8`. A narrowed root shares its kernel
    // with a wide one here on purpose, since the offset is per root; the rows reach both ends
    // of the day and the sub-second edges; and the values are the long reference's narrowed
    // the way the store narrows them. Validity is the child's, which the store leaves alone,
    // so the nulls and the tail are asserted as for any long root.
    val t = new ColumnRef(0, LaneType.LONG)
    val sixty = new LiteralSlot(0, LaneType.LONG)
    // Every dividend here is a time of day or a quotient of one, which is the bound each
    // division states; a quotient's is derived from its own node so that two equal subtrees
    // carry equal bounds and stay one common subexpression.
    val nanosPerDay = 86400000000000L
    def remainderOfSixty(x: ConstDivide): VarkaVectorIR =
      new IntArith(IntOp.SUB, Overflow.WRAP, x,
        new IntArith(IntOp.MUL, Overflow.WRAP,
          new ConstDivide(x, 60L, x.dividendBound() / math.abs(x.divisor())), sixty))
    val hour = new NarrowLane(new ConstDivide(t, 3600000000000L, nanosPerDay))
    val minute = new NarrowLane(remainderOfSixty(new ConstDivide(t, 60000000000L, nanosPerDay)))
    val second = new NarrowLane(remainderOfSixty(new ConstDivide(t, 1000000000L, nanosPerDay)))
    val roots = Seq[VarkaVectorIR](hour, minute, t, second)
    val edges = Seq(0L, 999999999L, 1000000000L, 3599999999999L, 3600000000000L,
      43200000000000L, 86399999999999L)
    def value(i: Int): Long = if (i < edges.length) edges(i) else {
      java.lang.Long.remainderUnsigned(i.toLong * 0x9E3779B97F4A7C15L, 86400000000000L)
    }
    // Both store forms (`VarkaEmitOptions.narrowHalfSpecies`): the masked store into the
    // lane's own width, and the whole store into the half-width species, which at a baked
    // count the option selects; the values and the validity must not depend on the choice.
    for (lanes <- Seq(2, 8); half <- Seq(false, true)) {
      val (name, bytes) = emitMulti(roots, 1, 1,
        VarkaMatrix.base.withLanesOverride(lanes).withNarrowHalfSpecies(half))
      // The store, counted from the bytes: one int store per narrowed root across the dense
      // bodies, the same across the masked epilogues (one per group, like the loop methods),
      // and nothing else on the int species - no int arithmetic, so the narrowing is the store
      // and only the store.
      def intVectorCalls(method: String): Int =
        VarkaEmitterTestSupport.invocationCount(bytes, method, "jdk.incubator.vector.IntVector")
      val methods = VarkaEmitterTestSupport.methodNames(bytes).asScala
      assert(methods.filter(isLoop(_, true)).map(intVectorCalls).sum === 3,
        s"at $lanes lanes, half=$half: three narrowed stores")
      assert(methods.filter(isEpilogue(_, false)).map(intVectorCalls).sum === 3,
        s"at $lanes lanes, half=$half: three in the epilogues")
      val (kernel, loader) = load((name, bytes))
      try {
        for (length <- Seq(1, 7, 17, 64, 129); (patternName, isNull) <- nullPatterns) {
          val arena = Arena.ofConfined()
          try {
            val in = makeLongInput(arena, length, isNull, value)
            val outs = roots.map {
              case _: NarrowLane => makeOutput(arena, length)
              case _ => makeLongOutput(arena, length)
            }
            val status = kernel.run(Array(in.data.address()), Array(in.validityAddress(length)),
              Array(in.nullCount), outs.map(_._1.address()).toArray,
              outs.map(_._2.address()).toArray, Array.empty[Int], Array(60L), length)
            assert(status === 0,
              s"lanes=$lanes half=$half len=$length $patternName: status $status")
            for (i <- 0 until length; (root, o) <- roots.zipWithIndex) {
              val row = Seq(if (isNull(i)) None else Some(value(i)))
              val expected = evalLong(root, row, Array(60L))
              val bit = (outs(o)._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
              val where = s"lanes=$lanes half=$half len=$length $patternName out=$o row=$i"
              assert(bit === expected.isDefined, s"$where: validity (want $expected)")
              expected.foreach { v =>
                root match {
                  case _: NarrowLane =>
                    assert(outs(o)._1.get(ValueLayout.JAVA_INT, i * 4L) === v.toInt,
                      s"$where: the narrowed value")
                  case _ =>
                    assert(outs(o)._1.get(ValueLayout.JAVA_LONG, i * 8L) === v,
                      s"$where: the wide value beside it")
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

  test("a long-lane range guard passes the value through and declines the batch on a lane " +
      "outside its bounds, in the loop and in the epilogue") {
    // GuardedDay's twin at 64 bits, with bounds the node carries. Three things are checked
    // and fail differently: the value is unchanged where the guard holds; a single lane out of
    // range reports STATUS_VALUE_RANGE, in a full lane group and in the masked tail; and a
    // null lane outside the bounds does not fire, since a null has no value to be out of range.
    val root = Seq[VarkaVectorIR](new GuardedRange(new ColumnRef(0, LaneType.LONG), 0L,
      86399999999999L))
    for (lanes <- Seq(2, 8)) {
      val (kernel, loader) = load(emitMulti(root, 1, 0,
        VarkaMatrix.base.withLanesOverride(lanes)))
      try {
        def status(length: Int, value: Int => Long, isNull: Int => Boolean): Int = {
          val arena = Arena.ofConfined()
          try {
            val in = makeLongInput(arena, length, isNull, value)
            val (data, validity) = makeLongOutput(arena, length)
            val st = kernel.run(Array(in.data.address()), Array(in.validityAddress(length)),
              Array(in.nullCount), Array(data.address()), Array(validity.address()),
              Array.empty[Int], Array.empty[Long], length)
            if (st == 0) {
              for (i <- 0 until length if !isNull(i)) {
                assert(data.get(ValueLayout.JAVA_LONG, i * 8L) === value(i),
                  s"lanes=$lanes row $i passed through unchanged")
              }
            }
            st
          } finally {
            arena.close()
          }
        }
        val none = (_: Int) => false
        assert(status(64, i => i.toLong * 1000000007L % 86400000000000L, none) === 0)
        // One lane at the top of the day plus one nanosecond: the loop body's guard.
        assert(status(64, i => if (i == 5) 86400000000000L else 1L, none) ===
          VarkaFusedKernel.STATUS_VALUE_RANGE, "a loop lane")
        // The same lane in the masked tail of a 17-row batch.
        assert(status(17, i => if (i == 16) -1L else 1L, none) ===
          VarkaFusedKernel.STATUS_VALUE_RANGE, "an epilogue lane")
        // A null lane holding an out-of-range payload does not fire the guard.
        assert(status(64, i => if (i == 5) -1L else 1L, i => i == 5) === 0, "a null lane")
      } finally {
        loader.release()
      }
    }
  }

  test("a guarded long-lane division declines the batch on a dividend past the bound its " +
      "caller stated, under both lowerings") {
    // VARKA-147's acceptance line. A caller with no structural bound discharges `ConstDivide`'s
    // dividend obligation by guarding, and the guard is what makes the claim true at run time:
    // a lane past it condemns the batch and the row engine answers it. Both lowerings are run,
    // because they fail differently above the bound - the conversion form by one, the magic
    // form by reading the dividend modulo 2^52 - and neither may be reached.
    val bound = 1L << 40
    val root = Seq[VarkaVectorIR](
      new ConstDivide(new GuardedRange(new ColumnRef(0, LaneType.LONG), -(bound - 1), bound - 1),
        1000L, bound))
    val lowerings = Seq(
      ("conversion", VarkaMatrix.base),
      ("magic", VarkaMatrix.base.withUseAVX(2)))
    for ((name, options) <- lowerings; lanes <- Seq(2, 8)) {
      val (kernel, loader) = load(emitMulti(root, 1, 0, options.withLanesOverride(lanes)))
      try {
        def status(length: Int, value: Int => Long): Int = {
          val arena = Arena.ofConfined()
          try {
            val in = makeLongInput(arena, length, _ => false, value)
            val (data, validity) = makeLongOutput(arena, length)
            val st = kernel.run(Array(in.data.address()), Array(in.validityAddress(length)),
              Array(in.nullCount), Array(data.address()), Array(validity.address()),
              Array.empty[Int], Array.empty[Long], length)
            if (st == 0) {
              for (i <- 0 until length) {
                assert(data.get(ValueLayout.JAVA_LONG, i * 8L) === value(i) / 1000L,
                  s"$name lanes=$lanes row $i")
              }
            }
            st
          } finally {
            arena.close()
          }
        }
        // Inside the bound the quotient is Java's, at both signs.
        assert(status(64, i => (if (i % 2 == 0) 1L else -1L) * (i.toLong * 7919L)) === 0,
          s"$name lanes=$lanes in range")
        // One lane past it, in a full lane group and in the masked tail.
        assert(status(64, i => if (i == 5) bound else 1L) ===
          VarkaFusedKernel.STATUS_VALUE_RANGE, s"$name lanes=$lanes a loop lane")
        assert(status(17, i => if (i == 16) -bound else 1L) ===
          VarkaFusedKernel.STATUS_VALUE_RANGE, s"$name lanes=$lanes an epilogue lane")
      } finally {
        loader.release()
      }
    }
  }

  test("a NULL long-lane range guard nulls the lanes outside its bounds and answers the batch, " +
      "at both widths") {
    // The decline above, as a null: the same compares, the lanes they flag cleared from the
    // guard's own word. The values put both ends, one past each and both extremes on lanes of
    // every group and tail; a sum over the guard is null where the guard nulled its lane.
    val col = new ColumnRef(0, LaneType.LONG)
    val col1 = new ColumnRef(1, LaneType.LONG)
    val bound = 1L << 40
    val guard = new GuardedRange(col, -bound, bound, Overflow.NULL)
    val vs = Array(-bound - 1, -bound, 0L, bound, bound + 1, Long.MinValue, Long.MaxValue, 7L, -3L)
    def values(c: Int, i: Int): Long = if (c == 0) vs((i * 5 + 1) % vs.length) else i.toLong
    val roots = Seq[VarkaVectorIR](guard, new IntArith(IntOp.ADD, Overflow.WRAP, guard, col1))
    for (lanes <- Seq(2, 8)) {
      checkLongMatrix(roots, 2, Array.empty[Long], Seq(1, 7, 9, 17, 64, 129), combos(2), values,
        "a NULL long guard", Some(lanes))
    }
  }

  test("a range guard at the int lane refuses bounds an int cannot hold, and an empty range " +
      "and a wrapping guard are refused where they are built") {
    val col = new ColumnRef(0, LaneType.INT)
    val wide = intercept[IllegalArgumentException](
      emitMulti(Seq(new GuardedRange(col, 0L, 1L << 40)), 1, 0))
    assert(wide.getMessage.contains("needs int bounds"), wide.getMessage)
    // The lower bound as well: one under Integer.MIN_VALUE is refused as one over MAX_VALUE is.
    val low = intercept[IllegalArgumentException](
      emitMulti(Seq(new GuardedRange(col, -(1L << 40), 0L)), 1, 0))
    assert(low.getMessage.contains("needs int bounds"), low.getMessage)
    val empty = intercept[IllegalArgumentException](new GuardedRange(col, 5L, 4L))
    assert(empty.getMessage.contains("empty range"), empty.getMessage)
    // A guard fails or nulls: one that wrapped would let every value through.
    val wrap = intercept[IllegalArgumentException](new GuardedRange(col, 0L, 4L, Overflow.WRAP))
    assert(wrap.getMessage.contains("WRAP guards nothing"), wrap.getMessage)
  }

  // ---------------------------------------------------------------------------------------
  // The guarded product (VARKA-611): the checked BIGINT multiply's magnitude pre-check
  // ---------------------------------------------------------------------------------------

  /** The batch status a guarded product owes: declined where any live row trips. */
  private def owedStatus(rows: Seq[(Long, Long)]): Int =
    if (rows.exists { case (a, b) => GuardedProduct.trips(a, b) }) {
      VarkaFusedKernel.STATUS_INT_OVERFLOW
    } else {
      0
    }

  /**
   * Runs a two-input long kernel over `rows`, row `i` null where `nulled(i)`, and returns its
   * status; where it answers, every live row's value is held to Java's `*`, and where the
   * status is 0 no live row may overflow, which is the node's soundness from the data side.
   */
  private def runPairs(kernel: VarkaFusedKernel, rows: IndexedSeq[(Long, Long)],
      nulled: Int => Boolean, lits: Array[Long], ctx: String): Int = {
    val arena = Arena.ofConfined()
    try {
      val n = rows.length
      val a = makeLongInput(arena, n, nulled, i => rows(i)._1, poisonNulls = false)
      val b = makeLongInput(arena, n, _ => false, i => rows(i)._2, poisonNulls = false)
      val (data, validity) = makeLongOutput(arena, n)
      val st = kernel.run(Array(a.data.address(), b.data.address()),
        Array(a.validityAddress(n), b.validityAddress(n)), Array(a.nullCount, b.nullCount),
        Array(data.address()), Array(validity.address()), Array.empty[Int], lits, n)
      if (st == 0) {
        for (i <- 0 until n if !nulled(i)) {
          val (x, y) = rows(i)
          val exact = BigInt(x) * BigInt(y)
          assert(exact.isValidLong, s"$ctx row $i: $x * $y overflows and was answered")
          assert(data.get(ValueLayout.JAVA_LONG, i * 8L) === x * y, s"$ctx row $i: value")
        }
      }
      st
    } finally {
      arena.close()
    }
  }

  test("a guarded product declines exactly where its double test trips and answers Java's " +
      "product elsewhere, at 2, 4 and 8 lanes and levels 2 and 3 (VARKA-611 5.1)") {
    val root = new GuardedProduct(new ColumnRef(0, LaneType.LONG),
      new ColumnRef(1, LaneType.LONG))
    // The edges of the soundness argument (`VARKA-611.md` 2.4): a product exact in doubles just
    // under 2^62, exactly 2^62, a magnitude of 2^63 that does not overflow, two overflows, a
    // product between 2^62 and 2^63, one 637 under 2^62 whose double product rounds up to it,
    // and one 12 over 2^62 whose first operand, past 2^53, rounds down so that it passes.
    val edges = Seq(
      (1L << 31, (1L << 31) - 1),
      (1L << 31, 1L << 31),
      (Long.MinValue, 1L),
      (-1L, Long.MinValue),
      (Long.MaxValue, 2L),
      (3037000499L, 3037000499L),
      (1537228672809129089L, 3L),
      (44773650664343572L, 103L))
    val signed = edges.flatMap { case (a, b) => Seq((a, b), (-a, b), (a, -b), (-a, -b)) }
    // The two that decide the band's sides, pinned so a change to BOUND shows here first.
    assert(GuardedProduct.trips(1537228672809129089L, 3L), "a product under 2^62 rounds up")
    assert(!GuardedProduct.trips(44773650664343572L, 103L), "a product over 2^62 rounds down")
    for (level <- Seq(2, 3); lanes <- Seq(2, 4, 8)) {
      val options = VarkaMatrix.base.withUseAVX(level).withLanesOverride(lanes)
      val (kernel, loader) = load(emitMulti(Seq[VarkaVectorIR](root), 2, 0, options))
      try {
        for ((pair, at) <- signed.zipWithIndex; length <- Seq(64, 17)) {
          val lane = if (length == 17) 16 else at % 64
          val rows = (0 until length).map(i => if (i == lane) pair else (i.toLong, 3L - i))
          val ctx = s"level $level, $lanes lanes, $pair at row $lane of $length"
          val none = (_: Int) => false
          assert(runPairs(kernel, rows, none, Array.empty, ctx) === owedStatus(Seq(pair)), ctx)
          // A null operand's row is never read, however its stored values multiply.
          assert(runPairs(kernel, rows, _ == lane, Array.empty, ctx) === 0, s"$ctx, nulled")
        }
      } finally {
        loader.release()
      }
    }
  }

  test("a guarded product declines every batch holding a product past 2^62 (1 + 2^-50) and " +
      "none whose products stay under 2^62 (1 - 2^-50) (VARKA-611 5.2)") {
    // `VARKA-104.md` 7 risk 3's decline rate, on the sound sides of the rounding band only, so
    // the assertion is exact: 4,096-row fixtures whose magnitudes are drawn from [2^61, the
    // lower side), and the same with one row from (the upper side, 2^63) at a drawn position.
    val root = new GuardedProduct(new ColumnRef(0, LaneType.LONG),
      new ColumnRef(1, LaneType.LONG))
    val lower = BigDecimal(2).pow(62) * (1 - BigDecimal(2).pow(-50))
    val upper = BigDecimal(2).pow(62) * (1 + BigDecimal(2).pow(-50))
    val rnd = new java.util.Random(611L)
    def pairUnder(top: BigDecimal, bottom: BigDecimal): (Long, Long) = {
      val a = (1L << 20) + (rnd.nextLong() & ((1L << 40) - 1))
      val m = bottom + (top - bottom) * BigDecimal(rnd.nextDouble())
      val b = (m / BigDecimal(a)).toLong
      val sa = if (rnd.nextBoolean()) a else -a
      val sb = if (rnd.nextBoolean()) b else -b
      (sa, sb)
    }
    val rows = 4096
    val batches = 8
    for (level <- Seq(2, 3)) {
      val options = VarkaMatrix.base.withUseAVX(level)
      val (kernel, loader) = load(emitMulti(Seq[VarkaVectorIR](root), 2, 0, options))
      try {
        var declinedSafe = 0
        var declinedTripping = 0
        for (batch <- 0 until batches) {
          val safe = (0 until rows).map(_ => pairUnder(lower, BigDecimal(2).pow(61)))
          assert(safe.forall { case (a, b) => (BigDecimal(a) * BigDecimal(b)).abs < lower })
          if (runPairs(kernel, safe, _ => false, Array.empty, s"level $level safe $batch") != 0) {
            declinedSafe += 1
          }
          val at = rnd.nextInt(rows)
          var big = pairUnder(BigDecimal(2).pow(63) * BigDecimal("0.99"), upper)
          while ((BigDecimal(big._1) * BigDecimal(big._2)).abs <= upper) {
            big = pairUnder(BigDecimal(2).pow(63) * BigDecimal("0.99"), upper)
          }
          val tripping = safe.updated(at, big)
          if (runPairs(kernel, tripping, _ => false, Array.empty,
              s"level $level tripping $batch") == VarkaFusedKernel.STATUS_INT_OVERFLOW) {
            declinedTripping += 1
          }
        }
        assert(declinedSafe === 0, s"level $level: batches under the lower side declined")
        assert(declinedTripping === batches, s"level $level: tripping batches answered")
      } finally {
        loader.release()
      }
    }
  }

  test("a guarded product with no named level is refused by the kernel plan, as a BIGINT " +
      "conversion is (VARKA-611 7 risk 3)") {
    val root = new GuardedProduct(new ColumnRef(0, LaneType.LONG),
      new ColumnRef(1, LaneType.LONG))
    val refusal = VarkaKernelPlan.admit(java.util.List.of(root), VarkaEmitOptions.DEFAULTS,
      false)
    assert(refusal.isPresent)
    assert(refusal.get.kind() === VarkaKernelPlan.Kind.CONVERSION)
    assert(refusal.get.reason() === VarkaKernelPlan.GUARDED_PRODUCT_UNKNOWN_LEVEL)
    for (level <- Seq(2, 3)) {
      assert(!VarkaKernelPlan.admit(java.util.List.of(root),
        VarkaEmitOptions.DEFAULTS.withUseAVX(level), false).isPresent, s"level $level")
    }
  }

  test("a projection's checked BIGINT multiply builds a guarded product under MAGNITUDE at a " +
      "named level and keeps A's decline elsewhere, and its batches of 1, 3 and 4,096 rows " +
      "decline where a row trips (VARKA-611 5.4, 5.5)") {
    val l = AttributeReference("l", LongType)()
    val m = AttributeReference("m", LongType)()
    val columns = Seq(l, m)
    def out(e: Expression): NamedExpression = Alias(e, "o")()
    val col0 = new ColumnRef(0, LaneType.LONG)
    val col1 = new ColumnRef(1, LaneType.LONG)
    val three = new LiteralSlot(0, LaneType.LONG)
    val arms: Seq[(String, Expression, VarkaVectorIR, Long => Long)] = Seq(
      ("l * m, ANSI", Multiply(l, m, EvalMode.ANSI), new GuardedProduct(col0, col1),
        r => (1L << 31) - 4096L + r),
      ("l * 3, ANSI", Multiply(l, Literal(3L), EvalMode.ANSI),
        new GuardedProduct(col0, three), _ => 3L),
      ("try_multiply(l, m)", Multiply(l, m, EvalMode.TRY), new GuardedProduct(col0, col1),
        r => (1L << 31) - 4096L + r))
    def magnitude(level: Int): VarkaEmitOptions = VarkaMatrix.base.withUseAVX(level)
      .withCheckedLongMultiply(VarkaEmitOptions.CheckedLongMultiply.MAGNITUDE)
    def reasons(e: Expression, options: VarkaEmitOptions,
        admission: VarkaAdmission = VarkaAdmission.NO_HOST_REFUSAL): Seq[String] =
      VarkaExpressionCompiler.declines(Seq(out(e)), columns, options, admission)
        .asScala.values.map(_.reason).toSeq
    val x86 = VarkaHostIsa.isX86(System.getProperty("os.arch", ""))
    for ((name, e, ir, right) <- arms) {
      withClue(name) {
        for (level <- Seq(2, 3)) {
          val compiled = VarkaExpressionCompiler.compile(Seq(out(e)), columns, magnitude(level))
          assert(compiled.get.outputs.asScala.toSeq === Seq(ir), s"level $level")
          // BY_HOST is B on x86 at both levels, and A on any other host.
          val byHost = VarkaMatrix.base.withUseAVX(level)
            .withCheckedLongMultiply(VarkaEmitOptions.CheckedLongMultiply.BY_HOST)
          assert(reasons(e, byHost).isEmpty === x86, s"BY_HOST at level $level")
        }
        // A's decline: at the unknown level, whose conversions would run as Java, and under
        // BOUND_ONLY; and on a two-lane x86 host the pre-check's mask declines as every
        // checked BIGINT operation's does.
        val unknown = VarkaMatrix.base.withUseAVX(VarkaEmitOptions.USE_AVX_UNKNOWN)
          .withCheckedLongMultiply(VarkaEmitOptions.CheckedLongMultiply.MAGNITUDE)
        for (options <- Seq(unknown, VarkaMatrix.base.withUseAVX(2)
            .withCheckedLongMultiply(VarkaEmitOptions.CheckedLongMultiply.BOUND_ONLY))) {
          val why = reasons(e, options)
          assert(why.size === 1 && why.head.startsWith("checked bigint multiply"), why)
        }
        assert(reasons(e, magnitude(2), new VarkaAdmission(java.util.Set.of(), true)) ===
          Seq(VarkaNodeCompiler.TWO_LANE_CHECK_REASON))
        // The batches the option matrix's `arrowBatchSize` cuts, over a fixture whose products
        // cross 2^62 near its row 2048: B's own condition, held per batch.
        val left: Long => Long =
          if (right(0) == 3L) r => 1537228672809129301L - 2048L + r else r => (1L << 31) + r
        val fixture = (0 until 4096).map(r => (left(r.toLong), right(r.toLong)))
        val lits = if (right(0) == 3L) Array(3L) else Array.empty[Long]
        for (level <- Seq(2, 3)) {
          val (kernel, loader) = load(emitMulti(Seq(ir), 2, lits.length, magnitude(level)))
          try {
            for (size <- Seq(1, 3, 4096); start <- (2030 until 2070 by size).take(
                if (size == 4096) 1 else 40)) {
              val from = if (size == 4096) 0 else start
              val batch = fixture.slice(from, from + size)
              val ctx = s"level $level, $size rows from $from"
              assert(runPairs(kernel, batch, _ => false, lits, ctx) === owedStatus(batch), ctx)
            }
          } finally {
            loader.release()
          }
        }
      }
    }
  }
}
