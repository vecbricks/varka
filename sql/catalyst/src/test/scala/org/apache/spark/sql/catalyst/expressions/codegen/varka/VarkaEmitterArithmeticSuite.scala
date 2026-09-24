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

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.varka.vector.DateVectorOps

/**
 * The vector walk's own lowerings: the day and int arithmetic with its overflow modes, DAG sharing
 * and CSE, the three-valued conditions with their blend, the null-skipping picks, and the
 * selection roots - each against the reference evaluator over every length and null pattern.
 */
class VarkaEmitterArithmeticSuite extends VarkaEmitterTestBase {

  test("a single AddDays matches vectorAddDays across lengths, null patterns and offsets") {
    val (kernel, loader) = load(emit(addDays(0), 1))
    try {
      for {
        length <- lengths
        (patternName, isNull) <- nullPatterns
        offset <- offsets
      } {
        val arena = Arena.ofConfined()
        try {
          val input = makeInput(arena, length, isNull)
          val expected = makeOutput(arena, length)
          val actual = makeOutput(arena, length)
          DateVectorOps.vectorAddDays(
            input.data.address(), input.validityAddress(length), input.nullCount,
            expected._1.address(), expected._2.address(), length, offset)
          kernel.run(
            Array(input.data.address()), Array(input.validityAddress(length)),
            Array(input.nullCount),
            Array(actual._1.address()), Array(actual._2.address()), Array(offset), length)
          assertSameOutput(length, expected, actual,
            s"length=$length pattern=$patternName offset=$offset")
        } finally {
          arena.close()
        }
      }
    } finally {
      loader.release()
    }
  }

  test("a chain of depth N matches N sequential kernel passes") {
    for (depth <- Seq(2, 3, 5, 8, 16)) {
      val chainOffsets = (0 until depth).map(level => level * 13 + 1).toArray
      val (kernel, loader) = load(emit(chain(depth), depth))
      try {
        val arena = Arena.ofConfined()
        try {
          val length = 1000
          val input = makeInput(arena, length, i => i % 7 == 0)
          val actual = makeOutput(arena, length)
          kernel.run(
            Array(input.data.address()), Array(input.validityAddress(length)),
            Array(input.nullCount),
            Array(actual._1.address()), Array(actual._2.address()), chainOffsets, length)

          // Oracle: the same chain as `depth` single-op kernel passes through temp buffers.
          var current = input
          for (level <- 0 until depth) {
            val out = makeOutput(arena, length)
            if (level % 2 == 0) {
              DateVectorOps.vectorAddDays(
                current.data.address(), current.validityAddress(length), current.nullCount,
                out._1.address(), out._2.address(), length, chainOffsets(level))
            } else {
              DateVectorOps.vectorSubDays(
                current.data.address(), current.validityAddress(length), current.nullCount,
                out._1.address(), out._2.address(), length, chainOffsets(level))
            }
            current = Col(out._1, out._2, current.nullCount)
          }
          assertSameOutput(length, (current.data, current.validity), actual, s"depth=$depth")
        } finally {
          arena.close()
        }
      } finally {
        loader.release()
      }
    }
  }

  test("DateDiff matches vectorDateDiff across lengths and per-column null patterns") {
    val root = new DateDiff(new ColumnRef(0), new ColumnRef(1))
    val (kernel, loader) = load(emitMulti(Seq(root), 2, 0))
    try {
      for {
        length <- lengths
        (endName, endNull) <- nullPatterns
        (startName, startNull) <- nullPatterns
      } {
        val arena = Arena.ofConfined()
        try {
          val end = makeInput(arena, length, endNull)
          val start = makeInput(arena, length, startNull)
          val expected = makeOutput(arena, length)
          val actual = makeOutput(arena, length)
          DateVectorOps.vectorDateDiff(
            end.data.address(), end.validityAddress(length), end.nullCount,
            start.data.address(), start.validityAddress(length), start.nullCount,
            expected._1.address(), expected._2.address(), length)
          kernel.run(
            Array(end.data.address(), start.data.address()),
            Array(end.validityAddress(length), start.validityAddress(length)),
            Array(end.nullCount, start.nullCount),
            Array(actual._1.address()), Array(actual._2.address()), Array.empty[Int], length)
          assertSameOutput(length, expected, actual,
            s"length=$length end=$endName start=$startName")
        } finally {
          arena.close()
        }
      }
    } finally {
      loader.release()
    }
  }

  test("AddDays/SubDays with a column offset match the reference evaluator") {
    // The trap this task exists to catch: `s.wordRef` used to alias the result's validity to
    // `days` alone, which was correct only because the offset used to always be a literal
    // (all-valid). A null offset on a non-null date must still make the row null - checkMatrix's
    // full combos(2) drives every (date, offset) null-pattern pair, that one included.
    val add = new AddDays(new ColumnRef(0), new ColumnRef(1))
    val sub = new SubDays(new ColumnRef(0), new ColumnRef(1))
    checkMatrix(Seq(add, sub), 2, Array.emptyIntArray, Seq(1, 17, 64, 65, 1000), combos(2),
      ctx = "column-offset")
  }

  test("two outputs sharing a subchain match sequential kernel passes, types independent") {
    // a = date_add(d, off); b = datediff(date_add(d, off), d2) - the milestone's DAG example:
    // the shared subchain is computed once per lane group and stored into both outputs' math.
    val shared = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    val roots = Seq[VarkaVectorIR](shared, new DateDiff(shared, new ColumnRef(1)))
    val (kernel, loader) = load(emitMulti(roots, 2, 1))
    try {
      for ((patternName, isNull) <- nullPatterns) {
        val arena = Arena.ofConfined()
        try {
          val length = 1000
          val offset = 11
          val d = makeInput(arena, length, isNull)
          val d2 = makeInput(arena, length, i => i % 3 == 0)
          val actualA = makeOutput(arena, length)
          val actualB = makeOutput(arena, length)
          kernel.run(
            Array(d.data.address(), d2.data.address()),
            Array(d.validityAddress(length), d2.validityAddress(length)),
            Array(d.nullCount, d2.nullCount),
            Array(actualA._1.address(), actualB._1.address()),
            Array(actualA._2.address(), actualB._2.address()),
            Array(offset), length)

          // Oracle: the same DAG as two hand-written kernel passes through a temp buffer.
          val expectedA = makeOutput(arena, length)
          val expectedB = makeOutput(arena, length)
          DateVectorOps.vectorAddDays(
            d.data.address(), d.validityAddress(length), d.nullCount,
            expectedA._1.address(), expectedA._2.address(), length, offset)
          DateVectorOps.vectorDateDiff(
            expectedA._1.address(), if (d.nullCount == 0) 0L else expectedA._2.address(),
            d.nullCount,
            d2.data.address(), d2.validityAddress(length), d2.nullCount,
            expectedB._1.address(), expectedB._2.address(), length)
          assertSameOutput(length, expectedA, actualA, s"pattern=$patternName output a")
          assertSameOutput(length, expectedB, actualB, s"pattern=$patternName output b")
        } finally {
          arena.close()
        }
      }
    } finally {
      loader.release()
    }
  }

  test("an all-null input kills only the outputs that read it") {
    // a reads column 0 only; b reads both. With column 1 all-null, a is served and b reads
    // back all-null - through the mask algebra alone, with no dedicated dead-output code.
    val roots = Seq[VarkaVectorIR](
      new AddDays(new ColumnRef(0), new LiteralSlot(0)),
      new DateDiff(new ColumnRef(0), new ColumnRef(1)))
    val (kernel, loader) = load(emitMulti(roots, 2, 1))
    try {
      val arena = Arena.ofConfined()
      try {
        val length = 1000
        val offset = 5
        val d = makeInput(arena, length, i => i % 5 == 0)
        val allNull = makeInput(arena, length, _ => true)
        val actualA = makeOutput(arena, length)
        val actualB = makeOutput(arena, length)
        kernel.run(
          Array(d.data.address(), allNull.data.address()),
          Array(d.validityAddress(length), allNull.validityAddress(length)),
          Array(d.nullCount, allNull.nullCount),
          Array(actualA._1.address(), actualB._1.address()),
          Array(actualA._2.address(), actualB._2.address()),
          Array(offset), length)
        val expectedA = makeOutput(arena, length)
        DateVectorOps.vectorAddDays(
          d.data.address(), d.validityAddress(length), d.nullCount,
          expectedA._1.address(), expectedA._2.address(), length, offset)
        assertSameOutput(length, expectedA, actualA, "the live output")
        for (b <- 0L until (length + 7) / 8L) {
          assert(actualB._2.get(ValueLayout.JAVA_BYTE, b) === 0.toByte,
            s"dead output validity byte $b not zero")
        }

        // Both inputs all-null: the generalized all-null shortcut returns early, and both
        // outputs must still read as all-null (their validity was pre-filled with stale bits).
        val actualC = makeOutput(arena, length)
        val actualD = makeOutput(arena, length)
        kernel.run(
          Array(allNull.data.address(), allNull.data.address()),
          Array(0L, 0L), Array(length, length),
          Array(actualC._1.address(), actualD._1.address()),
          Array(actualC._2.address(), actualD._2.address()),
          Array(offset), length)
        for (b <- 0L until (length + 7) / 8L) {
          assert(actualC._2.get(ValueLayout.JAVA_BYTE, b) === 0.toByte)
          assert(actualD._2.get(ValueLayout.JAVA_BYTE, b) === 0.toByte)
        }
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("a bare ColumnRef output root - a loop that only loads and stores") {
    // unix_date/date_from_unix_date unwrap to their child rather than compiling to a node,
    // so an output whose IR root is a plain column reference is a shape this task makes
    // reachable for the first time - exercise it directly at the emitter level.
    val root = new ColumnRef(0)
    checkMatrix(Seq(root), 1, Array.empty[Int], Seq(0, 1, 5, 17, 64, 65, 1000),
      nullPatterns.map(p => Seq(p._2)), ctx = "bare-columnref")
  }

  test("disabling CSE changes the bytecode but never the results") {
    val shared = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    val roots = Seq[VarkaVectorIR](shared, new DateDiff(shared, new ColumnRef(1)))
    val withCse = emitMulti(roots, 2, 1)
    val withoutCse = emitMulti(roots, 2, 1, VarkaEmitOptions.DEFAULTS.withCse(false))
    assert(!java.util.Arrays.equals(withCse._2, withoutCse._2),
      "disabling the memo left the bytecode unchanged - CSE was not exercised")
    val (kernelCse, loaderCse) = load(withCse)
    val (kernelNoCse, loaderNoCse) = load(withoutCse)
    try {
      val arena = Arena.ofConfined()
      try {
        val length = 1000
        val d = makeInput(arena, length, i => i % 5 == 0)
        val d2 = makeInput(arena, length, i => i % 3 == 0)
        def run(kernel: VarkaFusedKernel): ((MemorySegment, MemorySegment),
            (MemorySegment, MemorySegment)) = {
          val a = makeOutput(arena, length)
          val b = makeOutput(arena, length)
          kernel.run(
            Array(d.data.address(), d2.data.address()),
            Array(d.validityAddress(length), d2.validityAddress(length)),
            Array(d.nullCount, d2.nullCount),
            Array(a._1.address(), b._1.address()),
            Array(a._2.address(), b._2.address()),
            Array(7), length)
          (a, b)
        }
        val (cseA, cseB) = run(kernelCse)
        val (plainA, plainB) = run(kernelNoCse)
        assertSameOutput(length, plainA, cseA, "output a")
        assertSameOutput(length, plainB, cseB, "output b")
      } finally {
        arena.close()
      }
    } finally {
      loaderCse.release()
      loaderNoCse.release()
    }
  }

  test("IfElse over every comparison matches the reference across per-column null patterns") {
    for (op <- CompareOp.values.toSeq) {
      val root = new IfElse(new Compare(op, new ColumnRef(0), new ColumnRef(1)),
        new AddDays(new ColumnRef(0), new LiteralSlot(0)),
        new SubDays(new ColumnRef(1), new LiteralSlot(1)))
      checkMatrix(Seq(root), 2, Array(7, 3), Seq(0, 1, 5, 17, 64, 65, 1000), combos(2),
        ctx = s"op=$op")
    }
  }

  test("three-valued connectives: unknowns propagate by Kleene's rules") {
    // NOT(a < b) OR (a = c AND b <= c): known-false must survive NOT, an unknown falls
    // through to ELSE, and the reference evaluator implements Kleene logic independently.
    val a = new ColumnRef(0)
    val b = new ColumnRef(1)
    val c = new ColumnRef(2)
    val cond = new Or(
      new Not(new Compare(CompareOp.LT, a, b)),
      new And(new Compare(CompareOp.EQ, a, c), new Compare(CompareOp.LE, b, c)))
    val root = new IfElse(cond, new AddDays(a, new LiteralSlot(0)), b)
    checkMatrix(Seq(root), 3, Array(11), Seq(17, 64, 1000), combos(3), ctx = "kleene")
  }

  test("coalesce lowers to IfElse over IsNotNull and matches the reference") {
    val a = new ColumnRef(0)
    val b = new ColumnRef(1)
    val c = new ColumnRef(2)
    // coalesce(a, b) and coalesce(a, b, c) exactly as the compiler lowers them, plus a
    // computed last operand - only the guarded operands are restricted to columns.
    val roots = Seq[VarkaVectorIR](
      new IfElse(new IsNotNull(a), a, b),
      new IfElse(new IsNotNull(a), a, new IfElse(new IsNotNull(b), b, c)),
      new IfElse(new IsNotNull(a), a, new AddDays(b, new LiteralSlot(0))))
    checkMatrix(roots, 3, Array(9), Seq(1, 17, 64, 65, 1000), combos(3), ctx = "coalesce")
  }

  test("a validity predicate among the connectives keeps Kleene's rules") {
    // IsNotNull is the first *total* condition - never unknown - and the pair algebra must
    // absorb it unchanged: AND/OR against an unknown comparison, and IS NULL as NOT over it
    // (a slot swap in the masked body).
    val a = new ColumnRef(0)
    val b = new ColumnRef(1)
    val cond = new Or(
      new And(new IsNotNull(a), new Compare(CompareOp.LT, a, b)),
      new Not(new IsNotNull(b)))
    val root = new IfElse(cond, new Greatest(a, b), new SubDays(b, new LiteralSlot(0)))
    checkMatrix(Seq(root), 2, Array(5), Seq(1, 17, 64, 65, 1000), combos(2), ctx = "validity")
    // Dense/masked agreement on null-free data, where the predicate is constant true.
    val nullFree = Seq(Seq[Int => Boolean](_ => false, _ => false))
    checkMatrix(Seq(root), 2, Array(5), Seq(17, 65), nullFree, forceMasked = true,
      ctx = "validity-forced-masked")
  }

  test("IsNotNull over a computed operand is rejected at analysis") {
    // The compiler already declines this shape; the emitter re-checks because its emission
    // reads the child's per-input validity word, which only a column has before value walks.
    val bad = new IfElse(new IsNotNull(new AddDays(new ColumnRef(0), new LiteralSlot(0))),
      new ColumnRef(0), new ColumnRef(0))
    val e = intercept[IllegalArgumentException](emitMulti(Seq(bad), 1, 1))
    assert(e.getMessage.contains("IsNotNull child must be a ColumnRef"))
  }

  test("greatest and least skip nulls, nested to the n-ary fold shape") {
    val g2 = new Greatest(new ColumnRef(0), new ColumnRef(1))
    val roots = Seq[VarkaVectorIR](
      new Greatest(g2, new ColumnRef(2)),
      new Least(new Least(new ColumnRef(0), new ColumnRef(1)), new ColumnRef(2)),
      // The milestone's irreducible chain: greatest over a nested arithmetic chain.
      new Greatest(new AddDays(new ColumnRef(0), new LiteralSlot(0)), new ColumnRef(2)))
    checkMatrix(roots, 3, Array(7), Seq(1, 17, 64, 65, 1000), combos(3), ctx = "pick")
  }

  test("WRAP and NULL arithmetic match the reference over the extremes, and FAIL " +
      "matches wherever it does not have to decline") {
    val a = new ColumnRef(0)
    val b = new ColumnRef(1)
    val caseLengths = Seq(0, 1, 7, 8, 15, 16, 17, 33, 64, 65, 1000)
    // WRAP and NULL are total over any input: WRAP wraps, NULL nulls the overflowing lane, and
    // both leave the batch computed - so the extremes are fair game and the matrix asserts the
    // status is 0 throughout. The reference computes the same two answers from Math.addExact
    // and Java's wrapping operators, independently of the emitter's sign test.
    for (mode <- Seq(Overflow.WRAP, Overflow.NULL); op <- Seq(IntOp.ADD, IntOp.SUB)) {
      checkMatrix(Seq(new IntArith(op, mode, a, b)), 2, Array.empty[Int], caseLengths,
        combos(2), data = extreme, ctx = s"$op $mode over the extremes")
    }
    // Multiply has no int-lane overflow test at all, so only the wrapping form exists. The
    // compiler declines a checked one; the emitter refuses it, which is what keeps the two
    // from drifting into a lowering that quietly wraps where ANSI says raise.
    checkMatrix(Seq(new IntArith(IntOp.MUL, Overflow.WRAP, a, b)), 2, Array.empty[Int],
      caseLengths, combos(2), data = extreme, ctx = "MUL WRAP over the extremes")
    for (mode <- Seq(Overflow.FAIL, Overflow.NULL); options <- Seq(VarkaEmitOptions.DEFAULTS,
        checkOff)) {
      // Under `checkOff` too. Not because that switch never changes meaning - for a checked
      // add it does, deliberately, which is what makes it a reference arm - but because a
      // multiply has no correct checked emission at all, so there is no cheaper version of
      // the same node for "off" to select. It used to gate this refusal, and a checked
      // multiply emitted there as a plain wrapping one.
      val refused = intercept[IllegalArgumentException] {
        emitMulti(Seq[VarkaVectorIR](new IntArith(IntOp.MUL, mode, a, b)), 2, 0, options)
      }
      assert(refused.getMessage.contains("checked multiply"), s"$mode: ${refused.getMessage}")
    }
    // Negation has only the two modes: Spark has no `try_negative`, and the emitter refuses a
    // NULL one rather than lowering a form nothing can produce - pinned here, because a
    // silently accepted one would multiply by -1 and answer Int.MinValue where the oracle
    // says null.
    checkMatrix(Seq[VarkaVectorIR](new IntNeg(Overflow.WRAP, a)), 1, Array.empty[Int],
      caseLengths, Seq(Seq(nullPatterns(0)._2), Seq(nullPatterns(1)._2), Seq(nullPatterns(3)._2)),
      data = extreme, ctx = "neg WRAP over the extremes")
    val refused = intercept[IllegalArgumentException] {
      emitMulti(Seq[VarkaVectorIR](new IntNeg(Overflow.NULL, a)), 1, 0)
    }
    assert(refused.getMessage.contains("IntNeg has no NULL mode"), refused.getMessage)
    // FAIL condemns the batch instead of answering, so the matrix drives it over operands no
    // op can push out of range - 46340 squared is under Int.MaxValue - and the status
    // assertion inside checkMatrix is then the claim that it did not condemn one anyway.
    for (op <- Seq(IntOp.ADD, IntOp.SUB)) {
      checkMatrix(Seq(new IntArith(op, Overflow.FAIL, a, b)), 2, Array.empty[Int], caseLengths,
        combos(2), data = small, ctx = s"$op FAIL inside the range")
    }
    checkMatrix(Seq[VarkaVectorIR](new IntNeg(Overflow.FAIL, a)), 1, Array.empty[Int],
      caseLengths, Seq(Seq(nullPatterns(0)._2), Seq(nullPatterns(1)._2)),
      data = small, ctx = "neg FAIL inside the range")
    // Nested arithmetic over a fused field, which is where the composite key lives: bounded
    // operands, so this is the shape the compiler emits as WRAP under ANSI.
    checkMatrix(Seq[VarkaVectorIR](new IntArith(IntOp.ADD, Overflow.WRAP,
      new IntArith(IntOp.MUL, Overflow.WRAP, new Year(a), new LiteralSlot(0)), new Month(a))),
      1, Array(100), caseLengths,
      Seq(Seq(nullPatterns(0)._2), Seq(nullPatterns(1)._2), Seq(nullPatterns(2)._2)),
      ctx = "year(d) * 100 + month(d)")
  }

  test("a FAIL lane that overflows condemns the batch - in a loop lane, in an " +
      "epilogue lane, not under a null, and not with the check off") {
    val root = new IntArith(IntOp.ADD, Overflow.FAIL, new ColumnRef(0), new ColumnRef(1))
    val (kernel, loader) = load(emitMulti(Seq[VarkaVectorIR](root), 2, 0))
    val (kernelOff, loaderOff) = load(emitMulti(Seq[VarkaVectorIR](root), 2, 0, checkOff))
    try {
      val arena = Arena.ofConfined()
      try {
        // Lane `at` overflows on the sum; every other lane is a small pair. Nulls are not
        // poisoned here, because poison would replace the very sum under test.
        def left(at: Int)(i: Int): Int = if (i == at) Int.MaxValue else i % 5
        def right(at: Int)(i: Int): Int = if (i == at) 1 else i % 3
        def status(k: VarkaFusedKernel, length: Int, at: Int,
            nullL: Int => Boolean, nullR: Int => Boolean): Int = {
          val l = makeInputData(arena, length, nullL, left(at), poisonNulls = false)
          val r = makeInputData(arena, length, nullR, right(at), poisonNulls = false)
          runKernel2(k, l, r, makeOutput(arena, length), length)
        }
        val none = (_: Int) => false
        assert(status(kernel, 64, -1, none, none) === 0, "nothing overflows")
        // A loop lane, then the same lane in the masked body, then a lane only the epilogue
        // covers whatever the host's lane count is.
        assert(status(kernel, 64, 3, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 64, 3, _ == 40, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 17, 16, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 17, 16, _ == 2, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // The overflowing lane under a null operand: the row is null, its data lanes are
        // undefined, and a batch must not be condemned for arithmetic nobody asked for.
        assert(status(kernel, 64, 3, none, _ == 3) === 0)
        assert(status(kernel, 64, 3, _ == 3, none) === 0)
        assert(status(kernel, 17, 16, none, _ == 16) === 0)
        // With the check off the same batch is computed - wrongly, wrapping where ANSI says
        // raise, which is why the flag is a benchmark arm and not a config.
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

  test("abs is a blend whose negation arm alone condemns, at Int.MinValue") {
    // `abs` is not an op the IR has. The compiler spells it `if (x < 0) -x else x`, so what
    // the emitter sees is task 79's shape - a guarded node under a CASE arm - built from task
    // 63's nodes. There is nothing new to lower here, and that is the claim: the blend
    // answers what Spark's `abs` answers everywhere the negation is defined, and condemns the
    // batch at the one input where Spark raises instead.
    val x = new ColumnRef(0)
    def absOf(mode: Overflow): VarkaVectorIR =
      new IfElse(new Compare(CompareOp.LT, x, new LiteralSlot(0)), new IntNeg(mode, x), x)
    val caseLengths = Seq(0, 1, 7, 8, 15, 16, 17, 33, 64, 65, 1000)
    val patterns = Seq(Seq(nullPatterns(0)._2), Seq(nullPatterns(1)._2), Seq(nullPatterns(3)._2))
    // Inside the range both modes are total and agree with the reference, negatives included -
    // which is the half of `abs` that actually takes the negation arm.
    for (mode <- Seq(Overflow.WRAP, Overflow.FAIL)) {
      checkMatrix(Seq(absOf(mode)), 1, Array(0), caseLengths, patterns, data = small,
        ctx = s"abs $mode inside the range")
    }
    // Over the extremes only the wrapping form is total: it answers Int.MinValue for
    // Int.MinValue, which is what Spark's own LEGACY-mode `abs` does on an int.
    checkMatrix(Seq(absOf(Overflow.WRAP)), 1, Array(0), caseLengths, patterns, data = extreme,
      ctx = "abs WRAP over the extremes")

    // And the checked form at the one value that overflows. Int.MinValue is negative, so it
    // takes the arm that negates and task 79's qualification cannot spare it; the lane after
    // it is the same value under a null, where no arithmetic was asked for.
    val (kernel, loader) = load(emitMulti(Seq(absOf(Overflow.FAIL)), 1, 1))
    try {
      val arena = Arena.ofConfined()
      try {
        def status(length: Int, at: Int, isNull: Int => Boolean): Int = {
          val col = makeInputData(arena, length,
            isNull, i => if (i == at) Int.MinValue else -(i % 5) - 1, poisonNulls = false)
          val out = makeOutput(arena, length)
          kernel.run(Array(col.data.address()), Array(col.validityAddress(length)),
            Array(col.nullCount), Array(out._1.address()), Array(out._2.address()),
            Array(0), length)
        }
        val none = (_: Int) => false
        assert(status(64, -1, none) === 0, "no lane holds Int.MinValue")
        assert(status(64, 3, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE, "a loop lane")
        assert(status(17, 16, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE, "an epilogue lane")
        assert(status(64, 3, _ == 3) === 0, "the overflowing lane is null")
        assert(status(17, 16, _ == 16) === 0, "the overflowing epilogue lane is null")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the check costs bytes only where it is emitted, and none with it off") {
    val a = new ColumnRef(0)
    val b = new ColumnRef(1)
    val bodies = Seq("loopDense0", "loopMasked0", "epilogueDense0", "epilogueMasked0")
    def sizes(root: VarkaVectorIR, options: VarkaEmitOptions): Seq[Int] = {
      val bytes = emitMulti(Seq(root), 2, 0, options)._2
      bodies.map(VarkaEmitterTestSupport.codeSize(bytes, _))
    }
    // The A/B the benchmark prices: with the check off, the FAIL node is the WRAP node's
    // bytes, method for method. This is what makes the two benchmark arms comparable - one
    // measures the sign test and nothing else.
    val wrap = new IntArith(IntOp.ADD, Overflow.WRAP, a, b)
    val fail = new IntArith(IntOp.ADD, Overflow.FAIL, a, b)
    assert(sizes(fail, checkOff) === sizes(wrap, VarkaEmitOptions.DEFAULTS))
    // And with it on it costs bytes in every body that computes the node.
    val checked = sizes(fail, VarkaEmitOptions.DEFAULTS)
    val unchecked = sizes(wrap, VarkaEmitOptions.DEFAULTS)
    assert(checked.zip(unchecked).forall { case (c, u) => c > u },
      s"the check should add bytes to every body: $checked against $unchecked")
    // A WRAP node is untouched by the flag - nothing else in the emitter reads it.
    assert(sizes(wrap, checkOff) === sizes(wrap, VarkaEmitOptions.DEFAULTS))
  }

  test("a TRY node forfeits the dense body, and a checked one does not") {
    val a = new ColumnRef(0)
    val b = new ColumnRef(1)
    // A NULL node can null a lane whose operands are both valid, so the analysis marks the
    // kernel and the dispatcher never sends it a dense batch - there is no dense body to send
    // it to. A FAIL node nulls nothing, so it keeps both.
    val tryAdd = methodNames(emitMulti(Seq[VarkaVectorIR](
      new IntArith(IntOp.ADD, Overflow.NULL, a, b)), 2, 0))
    assert(!tryAdd.exists(_.startsWith("loopDense")), tryAdd.mkString(", "))
    assert(!tryAdd.contains("epilogueDense0"), tryAdd.mkString(", "))
    assert(tryAdd.contains("loopMasked0") && tryAdd.contains("epilogueMasked0"))
    val failAdd = methodNames(emitMulti(Seq[VarkaVectorIR](
      new IntArith(IntOp.ADD, Overflow.FAIL, a, b)), 2, 0))
    assert(failAdd.contains("loopDense0") && failAdd.contains("epilogueDense0"))
  }

  test("the composite key's masked body is its dense twin's bytes") {
    // PLAN_TASK_63.md 6.1 prediction 6. `year(d) * 100 + month(d)` under WRAP has the word of
    // a single input, so task 70's driver pass writes the whole output bitmap once per batch
    // and every word in the loop dies - which leaves the masked method with nothing the dense
    // one does not also do. Under FAIL the guard keeps a word alive and the two must differ,
    // which is the other half of the claim and the reason the check's cost is not free in a
    // masked body.
    val d = new ColumnRef(0)
    def key(mode: Overflow): VarkaVectorIR = new IntArith(IntOp.ADD, mode,
      new IntArith(IntOp.MUL, Overflow.WRAP, new Year(d), new LiteralSlot(0)), new Month(d))
    val wrapped = emitMulti(Seq(key(Overflow.WRAP)), 1, 1)._2
    for ((masked, dense) <- Seq(("loopMasked0", "loopDense0"),
        ("epilogueMasked0", "epilogueDense0"))) {
      assert(VarkaEmitterTestSupport.codeSize(wrapped, masked) ===
        VarkaEmitterTestSupport.codeSize(wrapped, dense), s"WRAP: $masked against $dense")
    }
    val checked = emitMulti(Seq(key(Overflow.FAIL)), 1, 1)._2
    assert(VarkaEmitterTestSupport.codeSize(checked, "loopMasked0") >
      VarkaEmitterTestSupport.codeSize(checked, "loopDense0"),
      "FAIL: the masked loop carries the word the guard reads")
  }

  test("the registered op counts, and the controls that must not move") {
    // PLAN_TASK_63.md 3.3, filled from the emitted bytes. The point of pinning these is that
    // an arm that quietly emits twice the ops it should still passes every value test. The
    // counts are `IntVector` calls in `loopDense0`, so they include the loop's unrolling -
    // which is why they are read as one table rather than reasoned about one at a time.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    def denseOps(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int): Int =
      VarkaEmitterTestSupport.invocationCount(emitMulti(roots, numInputs, numLiterals)._2,
        "loopDense0", "jdk.incubator.vector.IntVector")
    def arith(op: IntOp, mode: Overflow, l: VarkaVectorIR, r: VarkaVectorIR): VarkaVectorIR =
      new IntArith(op, mode, l, r)
    val lit = new LiteralSlot(0)
    val actual = Seq(
      "year(d) + 1, WRAP" ->
        denseOps(Seq(arith(IntOp.ADD, Overflow.WRAP, new Year(d), lit)), 1, 1),
      "year(d) + 1, FAIL" ->
        denseOps(Seq(arith(IntOp.ADD, Overflow.FAIL, new Year(d), lit)), 1, 1),
      "datediff + 1, WRAP" ->
        denseOps(Seq(arith(IntOp.ADD, Overflow.WRAP, new DateDiff(d, d2), lit)), 2, 1),
      "datediff + 1, FAIL" ->
        denseOps(Seq(arith(IntOp.ADD, Overflow.FAIL, new DateDiff(d, d2), lit)), 2, 1),
      "-i, WRAP" -> denseOps(Seq[VarkaVectorIR](new IntNeg(Overflow.WRAP, d)), 1, 0),
      "-i, FAIL" -> denseOps(Seq[VarkaVectorIR](new IntNeg(Overflow.FAIL, d)), 1, 0),
      "year * 100 + month" -> denseOps(Seq(arith(IntOp.ADD, Overflow.WRAP,
        arith(IntOp.MUL, Overflow.WRAP, new Year(d), lit), new Month(d))), 1, 1),
      "year, month (control)" ->
        denseOps(Seq[VarkaVectorIR](new Year(d), new Month(d)), 1, 0),
      "year (control)" -> denseOps(Seq[VarkaVectorIR](new Year(d)), 1, 0),
      "datediff (control)" -> denseOps(Seq[VarkaVectorIR](new DateDiff(d, d2)), 2, 0),
      "date_add(d, off) (control)" -> denseOps(Seq[VarkaVectorIR](new AddDays(d, d2)), 2, 0))
    assert(actual === Seq(
      "year(d) + 1, WRAP" -> 36,
      "year(d) + 1, FAIL" -> 40,
      "datediff + 1, WRAP" -> 6,
      "datediff + 1, FAIL" -> 10,
      "-i, WRAP" -> 3,
      "-i, FAIL" -> 4,
      "year * 100 + month" -> 42,
      "year, month (control)" -> 40,
      "year (control)" -> 34,
      "datediff (control)" -> 4,
      "date_add(d, off) (control)" -> 4))
    // Three claims about those numbers, stated as differences so that a change to the shared
    // year prefix moves both sides rather than the claim.
    val by = actual.toMap
    assert(by("year(d) + 1, FAIL") - by("year(d) + 1, WRAP") === 4 &&
      by("datediff + 1, FAIL") - by("datediff + 1, WRAP") === 4,
      "the add's check is four calls - two XOR, an AND and the compare - wherever it is emitted")
    assert(by("-i, FAIL") - by("-i, WRAP") === 1,
      "negation's check is one compare: it reads the operand, not the result")
    assert(by("year * 100 + month") === by("year, month (control)") + 2,
      "the key is the two fields plus its own two ops, so the year prefix is computed once")
    // Task 68's `make_ym_interval(year(d), month(d))` is this row and not a new one: the
    // compiler lowers it to exactly these nodes with 12 in the literal slot, both in WRAP
    // because the calendar fields' bounds prove the multiply and the add safe. Registering it
    // again would pin the same bytes under a second name.
  }

  test("a comparison root emits the selection bitmap with null-as-false") {
    // The simplest filter kernel: one Compare root, its bitmap checked against the Kleene
    // reference with unknown collapsed to false at the root - across lengths (partial lane
    // groups included) and every pair of null patterns, all-null included (the all-null
    // shortcut must leave a correct all-clear bitmap for a null-intolerant root).
    val root = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1))
    checkMatrix(Seq(root), 2, Array.emptyIntArray, Seq(0, 5, 16, 17, 65, 1000), combos(2),
      ctx = "cmp-root")
  }

  test("BETWEEN- and IN-shaped roots match the reference") {
    // The survey's two dominant filter shapes: BETWEEN as And over paired comparisons
    // against literals, and IN as the balanced OR chain of EQ leaves (task 20's lowering,
    // now at a root). Data cycles a small range so both selects and rejects occur.
    val d = new ColumnRef(0)
    val between = new And(
      new Compare(CompareOp.GE, d, new LiteralSlot(0)),
      new Compare(CompareOp.LE, d, new LiteralSlot(1)))
    val inChain = new Or(
      new Or(new Compare(CompareOp.EQ, d, new LiteralSlot(0)),
        new Compare(CompareOp.EQ, d, new LiteralSlot(1))),
      new Compare(CompareOp.EQ, d, new LiteralSlot(2)))
    checkMatrix(Seq(between), 1, Array(-3, 4), Seq(5, 64, 65, 1000),
      nullPatterns.map(p => Seq(p._2)), ctx = "between-root")
    checkMatrix(Seq(inChain), 1, Array(-4, 0, 5), Seq(5, 64, 65, 1000),
      nullPatterns.map(p => Seq(p._2)), ctx = "in-root")
  }

  test("an Or root over one all-null column still selects on the live column") {
    // The all-null-shortcut counterexample, pinned: Or(unknown, known-true) is known true,
    // so with column 0 all-null and column 1 live the rows where column 1 matches must
    // still select. A shortcut that fired on "some referenced column is all-null" would
    // zero this bitmap - which is why Cond roots are excluded from it.
    val root = new Or(
      new Compare(CompareOp.EQ, new ColumnRef(0), new LiteralSlot(0)),
      new Compare(CompareOp.EQ, new ColumnRef(1), new LiteralSlot(0)))
    val allNullFirst = Seq(Seq[Int => Boolean](_ => true, _ => false))
    checkMatrix(Seq(root), 2, Array(4), Seq(5, 64, 65, 1000), allNullFirst,
      ctx = "or-allnull")
    // And the full matrix for completeness: every pair of patterns.
    checkMatrix(Seq(root), 2, Array(4), Seq(65), combos(2), ctx = "or-matrix")
  }

  test("validity-predicate roots - IS NOT NULL, and IS NULL as its NOT") {
    val isNotNull = new IsNotNull(new ColumnRef(0))
    checkMatrix(Seq(isNotNull), 1, Array.emptyIntArray, Seq(5, 64, 65, 1000),
      nullPatterns.map(p => Seq(p._2)), ctx = "isnotnull-root")
    checkMatrix(Seq[VarkaVectorIR](new Not(isNotNull)), 1, Array.emptyIntArray,
      Seq(5, 64, 65, 1000), nullPatterns.map(p => Seq(p._2)), ctx = "isnull-root")
  }

  test("a mask root beside a value root shares the kernel and its subtrees") {
    // The emitter serves mixed outputs even though milestone 3's filter kernels are
    // single-root: the mask and the value share one CSE'd subtree, and each output keeps
    // its own contract (bitmap with no data store; value with data plus validity).
    val add = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    val roots = Seq[VarkaVectorIR](
      new Compare(CompareOp.GT, add, new ColumnRef(1)),
      add)
    checkMatrix(roots, 2, Array(7), Seq(5, 64, 65, 1000), combos(2), ctx = "mixed-roots")
  }

  test("the masked body agrees with the dense body on a null-free mask root") {
    val root = new And(
      new Compare(CompareOp.GE, new ColumnRef(0), new LiteralSlot(0)),
      new Compare(CompareOp.LE, new ColumnRef(0), new LiteralSlot(1)))
    val nullFree = Seq(Seq[Int => Boolean](_ => false))
    checkMatrix(Seq(root), 1, Array(-3, 4), Seq(64, 65, 1000), nullFree, ctx = "mask-dense")
    checkMatrix(Seq(root), 1, Array(-3, 4), Seq(64, 65, 1000), nullFree,
      forceMasked = true, ctx = "mask-forced")
  }

  test("a shared subchain feeds a condition and both branches across outputs") {
    // CSE across the value/condition boundary: `add = date_add(d, 7)` is compared against,
    // blended over, and emitted as its own output - one computation per lane group.
    val add = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    val cond = new Compare(CompareOp.GT, add, new ColumnRef(1))
    val roots = Seq[VarkaVectorIR](
      add,
      new IfElse(cond, add, new ColumnRef(1)),
      new IfElse(new Not(cond), new DateDiff(add, new ColumnRef(1)), new LiteralSlot(1)))
    checkMatrix(roots, 2, Array(7, 42), Seq(5, 64, 65, 1000), combos(2), ctx = "shared")
  }
}
