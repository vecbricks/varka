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
import java.lang.ref.{ReferenceQueue, WeakReference}
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.varka.vector.DateVectorOps

/**
 * Unit tests for [[VarkaLoopEmitter]] (milestone 2, tasks 9-11): the emitted fused loop must
 * match the hand-written `DateVectorOps` kernels - the reference semantics for the arithmetic
 * ops - row for row and bit for bit, across lengths that straddle every lane and byte boundary
 * of the 4-, 8- and 16-lane species, every null pattern (applied independently per column for
 * the multi-input shapes), and offsets including int wrap-around. The predication ops (task 11)
 * run against an in-suite reference evaluator implementing the milestone's 2.6 semantics
 * independently - Kleene three-valued conditions, blend, null-skipping greatest/least,
 * full-range floorMod - across the same matrices.
 *
 * The suite must also run green under `-XX:MaxVectorSize=16` (the four-lane shape; milestone 1's
 * finding 1 is why that width is where bugs hide):
 * {{{
 *   build/sbt "project catalyst" 'set Test/javaOptions += "-XX:MaxVectorSize=16"' \
 *     "testOnly *VarkaLoopEmitterSuite"
 * }}}
 */
class VarkaLoopEmitterSuite extends SparkFunSuite {

  private val classCounter = new AtomicInteger(0)

  // Boundary-straddling lengths for every species this can run at (4, 8 or 16 lanes), plus the
  // byte boundaries of the bit-packed validity, plus batch-sized ones.
  private val lengths = Seq(0, 1, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65,
    1000, 4096, 4097)

  private val offsets = Seq(0, 1, -1, 3, Int.MaxValue - 1)

  /** Null pattern: name -> which rows are null. */
  private val nullPatterns: Seq[(String, Int => Boolean)] = Seq(
    ("null-free", _ => false),
    ("mixed", i => i % 5 == 0),
    ("alternating", i => i % 2 == 1),
    ("all-null", _ => true))

  private def addDays(offsetSlot: Int): VarkaVectorIR =
    new AddDays(new ColumnRef(0), new LiteralSlot(offsetSlot))

  /** An `AddDays`/`SubDays` chain of the given depth, alternating so C2 cannot reassociate it. */
  private def chain(depth: Int, slotBase: Int = 0): VarkaVectorIR = {
    var node: VarkaVectorIR = new ColumnRef(0)
    for (level <- 0 until depth) {
      node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
      else new SubDays(node, new LiteralSlot(slotBase + level))
    }
    node
  }

  /** Emits the chain into a uniquely named class; returns the name with the bytes. */
  private def emit(
      root: VarkaVectorIR,
      numLiterals: Int,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): (String, Array[Byte]) =
    emitMulti(Seq(root), 1, numLiterals, options)

  /**
   * The multi-output, multi-input version of [[emit]] (task 10). Since task 23 the emitter's
   * non-shape inputs travel as a [[VarkaEmitOptions]] value on the call rather than as static
   * hooks a test had to set and reset, so a variant is just a different argument here.
   */
  private def emitMulti(
      roots: Seq[VarkaVectorIR],
      numInputs: Int,
      numLiterals: Int,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): (String, Array[Byte]) = {
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedTest${classCounter.addAndGet(1)}"
    (name, VarkaLoopEmitter.emit(name, roots.asJava, numInputs, numLiterals, null, null, options))
  }

  /** Loads an emitted class through the per-task loader and instantiates it. */
  private def load(named: (String, Array[Byte])): (VarkaFusedKernel, VarkaGeneratedClassLoader) = {
    val (className, bytes) = named
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(className, bytes)
    val kernel = loader.loadClass(className).getConstructor()
      .newInstance().asInstanceOf[VarkaFusedKernel]
    (kernel, loader)
  }

  /** Runs one kernel over one input column, returning the batch status it reports. */
  private def runKernel(
      kernel: VarkaFusedKernel,
      input: Col,
      out: (MemorySegment, MemorySegment),
      length: Int): Int =
    kernel.run(
      Array(input.data.address()), Array(input.validity.address()), Array(input.nullCount),
      Array(out._1.address()), Array(out._2.address()), Array.empty[Int], length)

  /** The declared method names of an emitted class - how the method layout is asserted. */
  private def methodNames(named: (String, Array[Byte])): Seq[String] = {
    val (className, bytes) = named
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(className, bytes)
    loader.loadClass(className).getDeclaredMethods.map(_.getName).toSeq
  }

  /** One column's worth of buffers: data, validity bitmap and its null count. */
  private case class Col(data: MemorySegment, validity: MemorySegment, nullCount: Int) {
    // Per the kernel contract a null-free or all-null column may pass 0L for its validity.
    def validityAddress(length: Int): Long =
      if (nullCount == 0 || nullCount == length) 0L else validity.address()
  }

  private def alloc(arena: Arena, bytes: Long): MemorySegment =
    arena.allocate(math.max(bytes, 1L), 8)

  private def makeInput(arena: Arena, length: Int, isNull: Int => Boolean): Col =
    makeInputData(arena, length, isNull, i => i * 31 - 7000)

  /**
   * A null slot's data is poisoned, not zeroed (task 70's harness change, made before that
   * task's emitter work so the whole existing matrix runs against it first). Arrow leaves the
   * data under a null slot undefined, the loop body loads every column unmasked, and the only
   * things standing between a null lane's garbage and a wrong answer are the validity word -
   * which the range guards AND with their condemning mask (tasks 42, 52, 60) - and the rule
   * that no lowering traps on any int. Until this landed the harness wrote the caller's own
   * value into a null slot - the same in-range day or count the valid rows carry - which
   * cannot tell a kernel that honours the word from one that never had to: an in-range lane
   * reaches no guard's condemning comparison whether it is masked or not. Alternating the two
   * extremes can, and puts each on both sides of every guard's bound.
   *
   * <p>The alternation counts null slots, not row indices. Keying it on {@code i} would collide
   * with the null patterns, which are themselves index predicates: under {@code alternating}
   * ({@code i % 2 == 1}) every null row is odd, so an {@code i & 1} poison would write
   * {@code Int.MaxValue} in every one of them and a quarter of the matrix would only ever see
   * the upper side of a bound. The bounds are asymmetric - {@code MAKE_DATE_MIN_YEAR} against
   * {@code MAKE_DATE_MAX_YEAR}, and task 69 widens {@code dayRange} in one direction only - so
   * a mask applied to the {@code > MAX} comparison but not the {@code < MIN} one would stay
   * green. Counting null slots alternates whatever the pattern is.
   */
  private def poison(nullOrdinal: Int): Int =
    if ((nullOrdinal & 1) == 0) Int.MinValue else Int.MaxValue

  /**
   * @param poisonNulls false where the caller has deliberately placed a value at a row it also
   *   marks null - the guard tests that pin a boundary value in a null lane and assert the batch
   *   is not condemned. Poisoning those would substitute an extreme for the boundary the test
   *   names, and one of them ({@code MAKE_DATE_MIN_YEAR - 1} under a null year) would silently
   *   become a duplicate of the case above it. Every other caller leaves it on; the pattern
   *   matrix, which is where a lowering that forgets to AND the guard with the word gets caught,
   *   is entirely on the poisoned path.
   */
  private def makeInputData(
      arena: Arena,
      length: Int,
      isNull: Int => Boolean,
      value: Int => Int,
      poisonNulls: Boolean = true): Col = {
    val data = alloc(arena, length * 4L)
    val validity = alloc(arena, (length + 7) / 8L)
    validity.fill(0.toByte)
    var nulls = 0
    for (i <- 0 until length) {
      if (isNull(i)) {
        data.set(ValueLayout.JAVA_INT, i * 4L, if (poisonNulls) poison(nulls) else value(i))
        nulls += 1
      } else {
        data.set(ValueLayout.JAVA_INT, i * 4L, value(i))
        val off = i / 8L
        val old = validity.get(ValueLayout.JAVA_BYTE, off)
        validity.set(ValueLayout.JAVA_BYTE, off, (old | (1 << (i % 8))).toByte)
      }
    }
    Col(data, validity, nulls)
  }

  private def makeOutput(
      arena: Arena,
      length: Int,
      validityBytes: Long = -1L): (MemorySegment, MemorySegment) = {
    val data = alloc(arena, length * 4L)
    // A sentinel no chain produces from the inputs above, so an unwritten valid row shows.
    for (i <- 0 until length) data.set(ValueLayout.JAVA_INT, i * 4L, 0xDEADBEEF)
    // The nominal size by default, and a bounded segment is a real assertion: a per-group
    // write addressing more than the bytes its group occupies faults here rather than
    // corrupting a neighbour. Task 47's word writer needs whole words instead, which is what
    // an Arrow destination buffer actually carries (VarkaKernelEvaluatorSuite), so its arm
    // asks for that size explicitly rather than widening every other test's guard.
    val validity = alloc(arena, if (validityBytes >= 0) validityBytes else (length + 7) / 8L)
    validity.fill(0xFF.toByte) // the loop must zero it; stale bits must not leak through
    (data, validity)
  }

  /** The destination validity bytes an emission under `options` needs; see [[makeOutput]]. */
  private def outputValidityBytes(options: VarkaEmitOptions, length: Int): Long =
    if (options.validityByWord()) ((length + 63L) / 64L) * 8L else (length + 7L) / 8L

  /** Asserts two (data, validity) outputs agree bit for bit and, where valid, value for value. */
  private def assertSameOutput(
      length: Int,
      expected: (MemorySegment, MemorySegment),
      actual: (MemorySegment, MemorySegment),
      context: String): Unit = {
    for (b <- 0L until (length + 7) / 8L) {
      assert(actual._2.get(ValueLayout.JAVA_BYTE, b) === expected._2.get(ValueLayout.JAVA_BYTE, b),
        s"$context: validity byte $b differs")
    }
    for (i <- 0 until length) {
      val valid = (expected._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
      if (valid) {
        assert(actual._1.get(ValueLayout.JAVA_INT, i * 4L) ===
          expected._1.get(ValueLayout.JAVA_INT, i * 4L), s"$context: row $i differs")
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // Task 11: the reference evaluator - an independent Scala implementation of the milestone's
  // 2.6 semantics (three-valued conditions, blend, null-skipping greatest/least, floorMod)
  // that every predication test runs the emitted loop against, row for row and bit for bit.
  // It lives in VarkaReferenceEvaluator now, shared with the IR fuzzer; these two are the
  // suite's names for it.
  // -----------------------------------------------------------------------------------------

  private def evalValue(
      node: VarkaVectorIR, row: Seq[Option[Int]], lits: Array[Int]): Option[Int] =
    VarkaReferenceEvaluator.evalValue(node, row, lits)

  private def evalCond(
      cond: Cond, row: Seq[Option[Int]], lits: Array[Int]): Option[Boolean] =
    VarkaReferenceEvaluator.evalCond(cond, row, lits)

  private def evalLong(
      node: VarkaVectorIR, row: Seq[Option[Long]], lits: Array[Long]): Option[Long] =
    VarkaReferenceEvaluator.evalLong(node, row, lits)

  private def evalCondLong(
      cond: Cond, row: Seq[Option[Long]], lits: Array[Long]): Option[Boolean] =
    VarkaReferenceEvaluator.evalCondLong(cond, row, lits)

  private def defaultData(col: Int, i: Int): Int = (i * (col + 3)) % 23 - 11

  /**
   * Emits the outputs once, then runs every (length, per-column null pattern) case against the
   * reference evaluator. With `forceMasked` a null-free column reports one null over a
   * full-set bitmap, which sends the batch down `runMasked` - the dispatcher tests only
   * `nullCount != 0` - so the masked body is exercised on the same data the dense body serves.
   */
  private def checkMatrix(
      roots: Seq[VarkaVectorIR],
      numInputs: Int,
      lits: Array[Int],
      caseLengths: Seq[Int],
      patternCombos: Seq[Seq[Int => Boolean]],
      data: (Int, Int) => Int = defaultData,
      forceMasked: Boolean = false,
      ctx: String = "",
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Unit = {
    val (kernel, loader) = load(emitMulti(roots, numInputs, lits.length, options))
    try {
      for (length <- caseLengths; (combo, comboId) <- patternCombos.zipWithIndex) {
        val arena = Arena.ofConfined()
        try {
          val cols = (0 until numInputs).map { c =>
            makeInputData(arena, length, combo(c), i => data(c, i))
          }
          val outs = roots.map(_ =>
            makeOutput(arena, length, outputValidityBytes(options, length)))
          val nullCounts = cols.map { col =>
            if (forceMasked && col.nullCount == 0) 1 else col.nullCount
          }
          val validityAddrs = cols.zip(nullCounts).map { case (col, nc) =>
            if (nc == 0 || nc == length) col.validityAddress(length) else col.validity.address()
          }
          // A Cond root is a selection output (task 21): its data address is 0L per the
          // kernel contract - exactly what the filter evaluator passes - so a regression
          // that touches it faults instead of writing somewhere silently.
          val dstData = roots.zip(outs).map { case (root, out) =>
            if (root.isInstanceOf[Cond]) 0L else out._1.address()
          }
          // The status is asserted, not discarded: a guard that declines every batch
          // leaves the destination values correct - the arithmetic does not depend on it -
          // so without this the matrix stays green while the kernel computes nothing in
          // production. Every shape this harness drives is one the kernel must answer.
          val status = kernel.run(cols.map(_.data.address()).toArray, validityAddrs.toArray,
            nullCounts.toArray, dstData.toArray,
            outs.map(_._2.address()).toArray, lits, length)
          assert(status === 0,
            s"$ctx: the kernel declined a batch it should have computed " +
              s"(length $length, combo $comboId, status $status)")
          for (i <- 0 until length) {
            val row = (0 until numInputs).map { c =>
              if (combo(c)(i)) None else Some(data(c, i))
            }
            for ((root, o) <- roots.zipWithIndex) {
              val bit = (outs(o)._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
              val where = s"$ctx len=$length combo=$comboId out=$o row=$i"
              root match {
                case c: Cond =>
                  // The selection rule: a bit is set exactly where the condition is known
                  // true - unknown reads as false (the mask-root null rule).
                  val expected = evalCond(c, row, lits).contains(true)
                  assert(bit === expected, s"$where: selection differs (want $expected)")
                case _ =>
                  val expected = evalValue(root, row, lits)
                  assert(bit === expected.isDefined,
                    s"$where: validity differs (want $expected)")
                  expected.foreach { v =>
                    assert(outs(o)._1.get(ValueLayout.JAVA_INT, i * 4L) === v, s"$where: value")
                  }
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

  // -------------------------------------------------------------------------------------------
  // The long lane (task 85, step 4)
  // -------------------------------------------------------------------------------------------

  /** One 64-bit column: eight bytes a lane, and the same validity bitmap the int lane uses. */
  private def makeLongInput(
      arena: Arena,
      length: Int,
      isNull: Int => Boolean,
      value: Int => Long,
      poisonNulls: Boolean = true): Col = {
    val data = alloc(arena, length * 8L)
    val validity = alloc(arena, (length + 7) / 8L)
    validity.fill(0.toByte)
    var nulls = 0
    for (i <- 0 until length) {
      if (isNull(i)) {
        // A poison value under a null lane, as the int harness does: a kernel that reads a
        // null lane's value gets a number no case expects rather than a plausible one. A test
        // whose subject is the value under a null lane passes `poisonNulls = false`, because
        // poison would replace what it is measuring.
        data.set(ValueLayout.JAVA_LONG, i * 8L,
          if (poisonNulls) Long.MinValue + 7L + nulls else value(i))
        nulls += 1
      } else {
        data.set(ValueLayout.JAVA_LONG, i * 8L, value(i))
        validity.set(ValueLayout.JAVA_BYTE, i / 8L,
          (validity.get(ValueLayout.JAVA_BYTE, i / 8L) | (1 << (i % 8))).toByte)
      }
    }
    Col(data, validity, nulls)
  }

  /**
   * `makeOutput`'s twin at 64-bit lanes, and it carries the same two assertions: the data is
   * poisoned with a sentinel no case computes, so an unwritten valid row shows up as a wrong
   * value rather than as a plausible zero, and the validity bytes start all-ones, so a loop
   * that forgot to zero them publishes stale bits instead of passing.
   */
  private def makeLongOutput(arena: Arena, length: Int): (MemorySegment, MemorySegment) = {
    val data = alloc(arena, length * 8L)
    for (i <- 0 until length) data.set(ValueLayout.JAVA_LONG, i * 8L, 0xDEADBEEFCAFEBABEL)
    val validity = alloc(arena, (length + 7) / 8L)
    validity.fill(0xFF.toByte)
    (data, validity)
  }

  /**
   * The int matrix's twin at 64-bit lanes, over the subset task 85 ships there: the leaves, the
   * arithmetic and its modes, the negate, the comparisons, the hull ops and the conditional.
   * It drives the kernel through the eight-argument `run` - the long lane's own entry point,
   * whose second scalar array is what a 64-bit literal needs - and compares against
   * `evalLong`, which is task 119's first part.
   */
  private def checkLongMatrix(
      roots: Seq[VarkaVectorIR],
      numInputs: Int,
      lits: Array[Long],
      caseLengths: Seq[Int],
      patternCombos: Seq[Seq[Int => Boolean]],
      data: (Int, Int) => Long,
      ctx: String,
      lanes: Int,
      base: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Unit = {
    val options = base.withLanesOverride(lanes)
    val (kernel, loader) = load(emitMulti(roots, numInputs, lits.length, options))
    try {
      for (length <- caseLengths; (combo, comboId) <- patternCombos.zipWithIndex) {
        val arena = Arena.ofConfined()
        try {
          val cols = (0 until numInputs).map { c =>
            makeLongInput(arena, length, combo(c), i => data(c, i))
          }
          val outs = roots.map(_ => makeLongOutput(arena, length))
          val dstData = roots.zip(outs).map { case (root, out) =>
            if (root.isInstanceOf[Cond]) 0L else out._1.address()
          }
          val status = kernel.run(cols.map(_.data.address()).toArray,
            cols.map(_.validityAddress(length)).toArray, cols.map(_.nullCount).toArray,
            dstData.toArray, outs.map(_._2.address()).toArray,
            Array.empty[Int], lits, length)
          assert(status === 0,
            s"$ctx at $lanes lanes: the kernel declined a batch it should have computed " +
              s"(length $length, combo $comboId, status $status)")
          for (i <- 0 until length) {
            val row = (0 until numInputs).map { c =>
              if (combo(c)(i)) None else Some(data(c, i))
            }
            for ((root, o) <- roots.zipWithIndex) {
              val bit = (outs(o)._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
              val where = s"$ctx at $lanes lanes, len=$length combo=$comboId out=$o row=$i"
              root match {
                case c: Cond =>
                  val expected = evalCondLong(c, row, lits).contains(true)
                  assert(bit === expected, s"$where: selection differs (want $expected)")
                case _ =>
                  val expected = evalLong(root, row, lits)
                  assert(bit === expected.isDefined,
                    s"$where: validity differs (want $expected)")
                  expected.foreach { v =>
                    assert(outs(o)._1.get(ValueLayout.JAVA_LONG, i * 8L) === v, s"$where: value")
                  }
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

  test("the long lane computes what the reference says, at both its widths") {
    // Task 85 step 4's proof: the lane-generic subset of the IR emitted against LongVector and
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
      checkLongMatrix(roots, 2, lits, Seq(1, 7, 64, 129), combos(2), values, name, lanes)
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
          extremeLong, s"$op $mode over the long extremes", lanes)
      }
      checkLongMatrix(Seq(new IntArith(IntOp.MUL, Overflow.WRAP, a, b)), 2, noLits, lengths,
        combos(2), extremeLong, "MUL WRAP over the long extremes", lanes)
      // Negation's own extreme: `-Long.MinValue` is `Long.MinValue`, which WRAP publishes and
      // the checked form refuses.
      checkLongMatrix(Seq[VarkaVectorIR](new IntNeg(Overflow.WRAP, a)), 1, noLits, lengths,
        combos(1), extremeLong, "neg WRAP over the long extremes", lanes)
      // FAIL condemns the batch instead of answering it, so its matrix arm runs over operands
      // no add or subtract can push out of range; the status assertion inside the matrix is
      // then the claim that it did not condemn one anyway.
      for (op <- Seq(IntOp.ADD, IntOp.SUB)) {
        checkLongMatrix(Seq(new IntArith(op, Overflow.FAIL, a, b)), 2, noLits, lengths,
          combos(2), smallLong, s"$op FAIL inside the long range", lanes)
      }
      checkLongMatrix(Seq[VarkaVectorIR](new IntNeg(Overflow.FAIL, a)), 1, noLits, lengths,
        combos(1), smallLong, "neg FAIL inside the long range", lanes)
    }
    // The two refusals the int lane pins, re-pinned at this one: a checked multiply has no
    // correct emission at either lane - the 128-bit product task 104 needs is missing at both
    // - and `try_negative` is not a Spark function, so a NULL negate is a shape nothing can
    // produce and is refused rather than lowered into a multiply by -1.
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
        assert(status(kernel, 64, 3, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 64, 3, _ == 40, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 17, 16, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 17, 16, _ == 2, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
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

  /** Every pair (or triple) of the four null patterns, as per-column combinations. */
  private def combos(numInputs: Int): Seq[Seq[Int => Boolean]] = {
    val ps = nullPatterns.map(_._2)
    if (numInputs == 1) ps.map(Seq(_))
    else if (numInputs == 2) for (a <- ps; b <- ps) yield Seq(a, b)
    else for (a <- ps; b <- ps; c <- ps) yield Seq(a, b, c)
  }

  test("the emitted class passes class-file verification before it is ever loaded") {
    val errors = VarkaEmitterTestSupport.verify(emit(addDays(0), 1)._2).asScala
    assert(errors.isEmpty, s"verifier errors: ${errors.mkString("; ")}")
  }

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

  test("neither next_day's weekday nor add_months' month count trips " +
      "analysis anymore, now that both widened from a literal-only offset to a column") {
    // The check that used to reject both nodes together (and whose message the IR fuzzer's
    // first failure quoted for the wrong one, #110) required a literal for either operand.
    // Task 59 widened next_day's weekday to a column (the evaluator's derived leaf) and task 60
    // widened add_months' month count the same way (task 38's AddDays/SubDays offset shape);
    // with both landed, requireLiteralOffset has no caller left and is gone, so neither shape
    // is rejected at analysis - each is exercised in full (values, nulls, cost) by its own
    // task's tests below.
    val (_, months) = emitMulti(Seq(new AddMonths(new ColumnRef(0), new ColumnRef(1))), 2, 0)
    assert(months.nonEmpty)
    val (_, weekday) = emitMulti(Seq(new NextDay(new ColumnRef(0), new ColumnRef(1))), 2, 0)
    assert(weekday.nonEmpty)
    // What replaced it still fires, and still names the operand that failed. Widening the two
    // nodes removed the literal requirement, not the shape requirement: an arbitrary subtree in
    // either position is a compiler bug the emitter refuses rather than emits. The message is
    // asserted per operand because one message shared across four operands is what sent #110
    // looking for a next_day the shape did not contain - the whole reason the name is a
    // parameter. Without an assertion here, dropping any of the four calls keeps the suite green.
    val badCount = intercept[IllegalArgumentException](
      emitMulti(Seq(new AddMonths(new ColumnRef(0), new Year(new ColumnRef(0)))), 1, 0))
    assert(badCount.getMessage.contains("add_months' month count"), badCount.getMessage)
    val badWeekday = intercept[IllegalArgumentException](
      emitMulti(Seq(new NextDay(new ColumnRef(0), new Year(new ColumnRef(0)))), 1, 0))
    assert(badWeekday.getMessage.contains("next_day's weekday"), badWeekday.getMessage)
    val badOffset = intercept[IllegalArgumentException](
      emitMulti(Seq(new AddDays(new ColumnRef(0), new Year(new ColumnRef(0)))), 1, 0))
    assert(badOffset.getMessage.contains("date_add's day offset"), badOffset.getMessage)
    val badSubOffset = intercept[IllegalArgumentException](
      emitMulti(Seq(new SubDays(new ColumnRef(0), new Year(new ColumnRef(0)))), 1, 0))
    assert(badSubOffset.getMessage.contains("date_sub's day offset"), badSubOffset.getMessage)
  }

  test("the month count takes int arithmetic, next_day's weekday still does not") {
    // Task 68 split `requireOffsetShape` in two. The month count and the weekday shared it
    // under one sentence - that each "carries a runtime bound a derived value cannot declare" -
    // which is true of the weekday and false of the count: a column-count `AddMonths` is in
    // `selfGuarding` and is checked at run time against MONTH_ARITH_MIN/MAX_MONTHS by a
    // lanewise test on the count's own value, which cares nothing about what produced it. The
    // weekday has no such guard, so a derived value there reaches `emitFloorMod7` unchecked.
    //
    // Both directions are asserted, because a split made on one side only is a ghost fallback
    // on the other - the compiler admitting what the emitter refuses, or the emitter admitting
    // what nothing guards.
    for (count <- Seq(new IntNeg(Overflow.FAIL, new ColumnRef(1)),
        new IntArith(IntOp.MUL, Overflow.WRAP, new ColumnRef(1), new LiteralSlot(0)))) {
      val (_, bytes) = emitMulti(Seq(new AddMonths(new ColumnRef(0), count)), 2, 1)
      assert(bytes.nonEmpty, s"the month count should hold $count")
    }
    val badWeekday = intercept[IllegalArgumentException](emitMulti(
      Seq(new NextDay(new ColumnRef(0), new IntNeg(Overflow.FAIL, new ColumnRef(1)))), 2, 0))
    assert(badWeekday.getMessage.contains("next_day's weekday"), badWeekday.getMessage)
  }

  test("the re-armed check fires on the composed day, in every body") {
    // The runtime half of task 93. The compiler admits year(add_months(date_add(d, i), i))
    // because it inserts a check between the month add and the decomposition; this is that
    // check doing its job, built here as IR rather than through the compiler so the emitter is
    // tested on its own.
    //
    // The value the check sees is d + offset + 31-ish * count, and only lanes outside the
    // narrowed range condemn the batch. A lane the validity word says is null must not,
    // because a null lane holds whatever the column held and the batch is still answerable.
    val guarded = new GuardedDay(
      new AddMonths(new AddDays(new ColumnRef(0), new ColumnRef(1)), new ColumnRef(2)))
    val root = new Year(guarded)
    val (kernel, loader) = load(emitMulti(Seq(root), 3, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        def status(length: Int, day: Int => Int, off: Int => Int, count: Int => Int,
            nullAt: Int => Boolean): Int = {
          val d = makeInputData(arena, length, nullAt, day, poisonNulls = false)
          val o = makeInputData(arena, length, _ => false, off, poisonNulls = false)
          val c = makeInputData(arena, length, _ => false, count, poisonNulls = false)
          runKernel3(kernel, d, o, c, makeOutput(arena, length), length)
        }
        val none = (_: Int) => false
        // Everything small: in range, computed.
        assert(status(64, _ => 0, _ => 1, _ => 1, none) === 0)
        // Lane 5 asks for the largest month count the count guard allows over a day already at
        // the top of the guarded range: the composed day leaves the range and the batch is
        // condemned. In a loop lane...
        val far = (i: Int) => if (i == 5) VarkaChrono.MONTH_ARITH_MAX_MONTHS else 0
        assert(status(64, _ => VarkaChrono.NARROW_MAX_DAYS, _ => 0, far, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE, "a loop lane")
        // ... and in an epilogue lane, where the bounds mask has to let it through.
        assert(status(17, _ => VarkaChrono.NARROW_MAX_DAYS, _ => 0,
          i => if (i == 16) VarkaChrono.MONTH_ARITH_MAX_MONTHS else 0, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE, "an epilogue lane")
        // The same lane under a null date does not condemn: the word masks it out.
        assert(status(64, _ => VarkaChrono.NARROW_MAX_DAYS, _ => 0, far, _ == 5) === 0,
          "a null lane must not condemn the batch")
        // Downward too, which is the direction the lowering is not exact in at all.
        assert(status(64, _ => VarkaChrono.NARROW_MIN_DAYS, _ => 0,
          i => if (i == 7) VarkaChrono.MONTH_ARITH_MIN_MONTHS else 0, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE, "below the floor")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the month-count guard covers a derived count, which is why the split is safe") {
    // The claim the split rests on, tested rather than asserted: the guard reads the count's
    // lanes after the arithmetic, so a count that only leaves the range *because* of the
    // negation still condemns the batch. Without this the split would be a way to smuggle an
    // unguarded count past task 60.
    val root = new AddMonths(new ColumnRef(0), new IntNeg(Overflow.WRAP, new ColumnRef(1)))
    val (kernel, loader) = load(emitMulti(Seq(root), 2, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        def status(count: Int => Int, length: Int): Int = {
          val dates = makeInputData(arena, length, _ => false, _ => 0, poisonNulls = false)
          val counts = makeInputData(arena, length, _ => false, count, poisonNulls = false)
          runKernel2(kernel, dates, counts, makeOutput(arena, length), length)
        }
        // In range after negation: computed.
        assert(status(i => -(i % 100), 64) === 0)
        // Lane 3 negates to one month past MONTH_ARITH_MAX_MONTHS, so the guard must fire -
        // and the input itself, -24565, is inside the range, so only the derived value is out.
        assert(status(i => if (i == 3) -(VarkaChrono.MONTH_ARITH_MAX_MONTHS + 1) else 0, 64) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("next_day with a column weekday matches the reference evaluator over every " +
      "null pattern of both columns, in and out of the leaf's range") {
    // The trap is task 38's again: the node's word used to alias the date's alone, which was
    // right only while the weekday was always a literal. combos(2) drives every (date,
    // weekday) null-pattern pair, the null-weekday-on-a-live-date one included. The weekday
    // column cycles through -2 .. 6, so the leaf's whole range -1 .. 5 and a value either side
    // of it are covered: the lowering is exact for every int k, and the reference is Spark's
    // own formula, so out-of-range values are as checkable as in-range ones.
    val root = new NextDay(new ColumnRef(0), new ColumnRef(1))
    def data(c: Int, i: Int): Int = if (c == 0) i * 997 - 300000 else i % 9 - 2
    checkMatrix(Seq(root), 2, Array.emptyIntArray, Seq(1, 13, 17, 64, 65, 1000), combos(2),
      data = data, ctx = "next_day column weekday")
  }

  test("the column and literal next_day forms cost what PLAN_TASK_59.md 3.3 " +
      "registered, and the literal form's bytes did not move") {
    val literal = emitMulti(Seq(new NextDay(new ColumnRef(0), new LiteralSlot(0))), 1, 1)._2
    val column = emitMulti(Seq(new NextDay(new ColumnRef(0), new ColumnRef(1))), 2, 0)._2
    assert(laneOps(literal, "loopDense0") === 18, "the literal form")
    assert(laneOps(column, "loopDense0") === 18, "the column form")
  }

  test("fitsBudgets mirrors the analysis caps, distinct ops across outputs") {
    def chain(base: Int, depth: Int): VarkaVectorIR =
      (0 until depth).foldLeft[VarkaVectorIR](new ColumnRef(base)) { (n, _) =>
        new AddDays(n, new LiteralSlot(0))
      }
    assert(VarkaLoopEmitter.fitsBudgets(java.util.List.of[VarkaVectorIR](chain(0, 16)), 1))
    assert(!VarkaLoopEmitter.fitsBudgets(java.util.List.of[VarkaVectorIR](chain(0, 17)), 1))
    // Five disjoint depth-13 chains are 65 distinct ops - the same shape the emitter's own
    // rejection test uses against MAX_FUSED_NODES.
    val five: Seq[VarkaVectorIR] = (0 until 5).map(k => chain(k, 13))
    assert(!VarkaLoopEmitter.fitsBudgets(java.util.List.of[VarkaVectorIR](five: _*), 5))
    // A shared subtree is one node, exactly as Analysis counts it.
    val shared = chain(0, 13)
    val sharedFive: Seq[VarkaVectorIR] = Seq.fill(5)(shared)
    assert(VarkaLoopEmitter.fitsBudgets(java.util.List.of[VarkaVectorIR](sharedFive: _*), 1))
    // The input-column cap is mirrored too (the review found it missing): the emitter's
    // emit() rejects numInputs > 64, so the compiler must never accept such a projection.
    val one = java.util.List.of[VarkaVectorIR](chain(0, 1))
    assert(VarkaLoopEmitter.fitsBudgets(one, 64))
    assert(!VarkaLoopEmitter.fitsBudgets(one, 65))
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

  test("dayofweek and weekday match floorMod and LocalDate across extreme and negative days") {
    val roots = Seq[VarkaVectorIR](
      new DayOfWeek(new ColumnRef(0)), new WeekDay(new ColumnRef(0)))
    // The 15-bit fold boundaries are edges of the shipped magic-multiply lowering.
    val extremes = Array(Int.MinValue, Int.MaxValue, Int.MinValue + 1, Int.MaxValue - 1,
      -1, 0, 1, -7, 7, -8, 8, Int.MaxValue - 3, Int.MinValue + 3,
      32767, 32768, -32768, -32769)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 997 - 300000
    checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "dow")
    // The independent oracle behind the reference: Spark's DateTimeUtils formula through
    // LocalDate, valid for every int epoch day.
    for (v <- extremes) {
      val viaLocalDate = java.time.LocalDate.ofEpochDay(v).getDayOfWeek.plus(1).getValue
      assert((Math.floorMod(v, 7) + 4) % 7 + 1 === viaLocalDate, s"oracle self-check v=$v")
    }
  }

  test("next_day matches Spark's own wrapping formula for every weekday, at the extremes") {
    // One root per weekday offset (k = dayOfWeek - 1). DateTimeUtils.getDayOfWeekFromString
    // returns [0, 6] with THURSDAY = 0 .. WEDNESDAY = 6, so k itself ranges over [-1, 5], not
    // [0, 6] - THURSDAY's k = -1 is the one value a naive 0-to-6 sweep would miss (caught by
    // this task's code review). All seven share one emitted class and one literal-slot array
    // - the point of "k is a runtime literal" (section 2).
    val roots = (0 to 6).map(slot => new NextDay(new ColumnRef(0), new LiteralSlot(slot)))
    val lits = Array(-1, 0, 1, 2, 3, 4, 5)
    // The 15-bit fold boundaries are edges of the shared floorMod7 lowering; the rest probe
    // the deliberate k - d overflow (section 2) near both ends of the int range.
    val extremes = Array(Int.MinValue, Int.MaxValue, Int.MinValue + 1, Int.MaxValue - 1,
      -1, 0, 1, -7, 7, -8, 8, Int.MaxValue - 3, Int.MinValue + 3,
      32767, 32768, -32768, -32769)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 997 - 300000
    checkMatrix(roots, 1, lits, Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "next_day")
    // The independent oracle behind the reference: Spark's own getNextDateForDayOfWeek,
    // which wraps in plain int arithmetic - checked against the reduce-first form the recipe
    // warns is wrong, to confirm the two really do disagree at the boundary it names.
    def spark(startDay: Int, dayOfWeek: Int): Int =
      startDay + 1 + ((dayOfWeek - 1 - startDay) % 7 + 7) % 7
    def reduceFirst(startDay: Int, k: Int): Int =
      startDay + 1 + Math.floorMod(k - Math.floorMod(startDay, 7), 7)
    assert(spark(Int.MinValue, 3) === -2147483647, "oracle self-check")
    assert(reduceFirst(Int.MinValue, 2) === -2147483643, "reduce-first disagrees as documented")
    assert(spark(Int.MinValue, 3) !== reduceFirst(Int.MinValue, 2))
  }

  /** The calendar boundary set every bounded calendar test walks; see the first use below. */
  private val calendarBoundaryDays: Array[Int] = Array(
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS, VarkaChrono.NARROW_MAX_DAYS - 1,
      -1, 0, 1, -719468, -719162,
      LocalDate.of(1600, 2, 29).toEpochDay.toInt, LocalDate.of(1900, 3, 1).toEpochDay.toInt,
      LocalDate.of(2000, 2, 29).toEpochDay.toInt, LocalDate.of(1, 1, 1).toEpochDay.toInt,
      LocalDate.of(9999, 12, 31).toEpochDay.toInt
    ) ++ Array(
      // dayofyear's own boundary set (task 34): every year-end/year-start pair a leap flag
      // could get wrong, plus February's own boundary in a leap and a century-non-leap year.
      LocalDate.of(2000, 1, 1), LocalDate.of(2000, 12, 31), // leap
      LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), // leap
      LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31), // common
      LocalDate.of(1900, 1, 1), LocalDate.of(1900, 12, 31), // century, not leap
      LocalDate.of(2000, 2, 28), LocalDate.of(1900, 2, 28)
    ).map(_.toEpochDay.toInt)

  private def calendarBoundaryDay(c: Int, i: Int): Int =
    if (i < calendarBoundaryDays.length) calendarBoundaryDays(i) else i * 9973 - 400000

  test("the calendar extractions match LocalDate over the range they cover") {
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)))
    checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay, ctx = "narrowed")
  }

  test("both prefix forms match LocalDate over the calendar boundaries, last_day too") {
    // The Julian map and the century-then-year split, each held to LocalDate over the same
    // boundary set on every calendar tail plus last_day, whose month-length arithmetic reads
    // the prefix's year. Agreeing with LocalDate here is also them agreeing with each other,
    // which is what keeps whichever is not the default a live reference variant rather than
    // dead code - the same discipline as FloorMod7 and task 53's month axis.
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)), new LastDay(new ColumnRef(0)),
      new TruncDate(new ColumnRef(0), TruncLevel.YEAR),
      new TruncDate(new ColumnRef(0), TruncLevel.MONTH),
      new TruncDate(new ColumnRef(0), TruncLevel.QUARTER))
    for (julian <- Seq(true, false)) {
      checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"julianMap=$julian", options = VarkaEmitOptions.DEFAULTS.withJulianMap(julian))
    }
  }

  // Task 35: trunc(date, YEAR | MONTH | QUARTER), two lowerings behind VarkaEmitOptions.truncDate.
  private val truncRoots = Seq[VarkaVectorIR](
    new TruncDate(new ColumnRef(0), TruncLevel.YEAR),
    new TruncDate(new ColumnRef(0), TruncLevel.MONTH),
    new TruncDate(new ColumnRef(0), TruncLevel.QUARTER))

  private val truncForms = Seq(VarkaEmitOptions.TruncDateForm.SUBTRACT,
    VarkaEmitOptions.TruncDateForm.RECOMPOSE)

  test("trunc matches DateTimeUtils.truncDate over the calendar boundaries, under " +
      "both lowerings and both prefix forms, and its date output feeds further arithmetic") {
    // The boundary set is the calendar family's: year and era edges, February in leap, common
    // and century years, every month-length boundary, and the covered range's own ends. The
    // reference is DateTimeUtils.truncDate, the definition. The two lowerings agreeing with it
    // is them agreeing with each other, which keeps whichever is not the default a live
    // reference variant (FloorMod7's precedent). The date_add over the MONTH form is the
    // DateType output surviving a second operation in the same chain, which the milestone
    // row asks for and which a single-column test cannot show.
    val chained = new AddDays(new TruncDate(new ColumnRef(0), TruncLevel.MONTH),
      new LiteralSlot(0))
    for (form <- truncForms; julian <- Seq(true, false); neri <- Seq(true, false)) {
      val options = VarkaEmitOptions.DEFAULTS.withTruncDate(form).withJulianMap(julian)
        .withNeriSchneiderMonth(neri)
      checkMatrix(truncRoots :+ chained, 1, Array(5), Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"trunc $form julianMap=$julian neri=$neri", options = options)
    }
  }

  test("every trunc level and every month of two years, quarter starts included") {
    // Day-by-day over 2023 (common) and 2024 (leap), so every quarter start and every month
    // start is crossed in both year kinds rather than sampled - the four-way quarter select
    // and the leap-adjusted starts are what this sweep is for.
    val start = LocalDate.of(2023, 1, 1).toEpochDay.toInt
    val days = (0 until 731).map(start + _)
    def day(c: Int, i: Int): Int = if (i < days.length) days(i) else i * 9973 - 400000
    for (form <- truncForms) {
      checkMatrix(truncRoots, 1, Array.empty[Int], Seq(731),
        nullPatterns.map(p => Seq(p._2)), data = day, ctx = s"trunc two years $form",
        options = VarkaEmitOptions.DEFAULTS.withTruncDate(form))
    }
  }

  test("trunc shares the calendar prefix with a sibling extraction over the same date") {
    // trunc(d, 'MONTH') beside year(d) in one loop method runs the civil-from-days prefix once,
    // asserted the way task 32's own tests do: the shared kernel's dense loop carries fewer
    // IntVector calls than the unshared one, by at least the prefix's own op count.
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)),
      new TruncDate(new ColumnRef(0), TruncLevel.MONTH))
    val wide = VarkaEmitOptions.DEFAULTS.withGroupBudget(200)
    val shared = laneOps(emitMulti(roots, 1, 0, wide)._2, "loopDense0")
    val unshared = laneOps(emitMulti(roots, 1, 0, wide.withShareChronoPrefix(false))._2,
      "loopDense0")
    assert(unshared - shared >= 20,
      s"expected the prefix to be shared: $shared IntVector ops shared vs $unshared unshared")
  }

  // Task 42: make_date over three int columns. The triples cover the validity rule's corners:
  // valid dates at both ends of the contract, 29 February in leap, common, century and
  // quatercentennial years, 30 February, 31 April, 32 December, month 0, 13 and -1, day 0 and
  // -1. `makeDateTriples(c, i)` cycles them per column `c` (0 year, 1 month, 2 day).
  private val makeDateAll: Array[(Int, Int, Int)] = Array(
    (2024, 1, 1), (2024, 2, 29), (2023, 2, 29), (1900, 2, 29), (2000, 2, 29), (2024, 2, 30),
    (2024, 4, 31), (2024, 4, 30), (2024, 12, 31), (2024, 12, 32), (2024, 13, 1), (2024, 0, 1),
    (2024, -1, 15), (2024, 1, 0), (2024, 6, -1), (1, 1, 1), (9999, 12, 31), (1970, 1, 1),
    (1969, 12, 31), (VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1),
    (VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31))
  private val makeDateValid: Array[(Int, Int, Int)] = makeDateAll.filter { case (y, m, d) =>
    VarkaChrono.makeDate(y, m, d) >= VarkaChrono.MAKE_DATE_OUT_OF_RANGE + 1 }
  private def tripleData(triples: Array[(Int, Int, Int)])(c: Int, i: Int): Int = {
    val t = triples(i % triples.length)
    if (c == 0) t._1 else if (c == 1) t._2 else t._3
  }
  private val makeDateNull =
    new MakeDate(new ColumnRef(0), new ColumnRef(1), new ColumnRef(2), false)
  private val makeDateAnsi =
    new MakeDate(new ColumnRef(0), new ColumnRef(1), new ColumnRef(2), true)

  /** Runs a three-input kernel with one output, returning the batch status. */
  private def runKernel3(kernel: VarkaFusedKernel, a: Col, b: Col, c: Col,
      out: (MemorySegment, MemorySegment), length: Int): Int =
    kernel.run(
      Array(a.data.address(), b.data.address(), c.data.address()),
      Array(a.validityAddress(length), b.validityAddress(length), c.validityAddress(length)),
      Array(a.nullCount, b.nullCount, c.nullCount),
      Array(out._1.address()), Array(out._2.address()), Array.empty[Int], length)

  test("make_date matches LocalDate.of over the validity corners - nulls for invalid " +
      "dates under the NULL form, the valid triples under both forms - at every length and " +
      "null pattern of its three inputs") {
    // The NULL form runs every triple: an invalid date is a null output and the status stays 0.
    checkMatrix(Seq(makeDateNull), 3, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      combos(3), data = tripleData(makeDateAll), ctx = "NULL form")
    // The ANSI form over the valid triples alone; its invalid rows are the status test below.
    for (root <- Seq(makeDateNull, makeDateAnsi)) {
      checkMatrix(Seq(root), 3, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        combos(3), data = tripleData(makeDateValid),
        ctx = s"valid triples, ansi=${root.failOnError()}")
    }
  }

  test("an invalid date under the ANSI form declines the batch, in a loop lane and " +
      "an epilogue lane, and not under a null input; a year past the limit declines under " +
      "both forms and is not confused with an invalid date") {
    val (ansi, loaderA) = load(emitMulti(Seq(makeDateAnsi), 3, 0))
    val (nul, loaderN) = load(emitMulti(Seq(makeDateNull), 3, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        val none = (_: Int) => false
        // Valid everywhere except lane `at`, which gets `bad`.
        def run(k: VarkaFusedKernel, length: Int, at: Int, bad: (Int, Int, Int),
            nullY: Int => Boolean, nullM: Int => Boolean, nullD: Int => Boolean): Int = {
          def pick(c: Int)(i: Int): Int = if (i == at) {
            if (c == 0) bad._1 else if (c == 1) bad._2 else bad._3
          } else tripleData(makeDateValid)(c, i)
          // `pick` places `bad` at lane `at`, and these cases null that same lane on purpose,
          // so the boundary value must survive rather than be replaced by a poison extreme.
          val y = makeInputData(arena, length, nullY, pick(0), poisonNulls = false)
          val m = makeInputData(arena, length, nullM, pick(1), poisonNulls = false)
          val d = makeInputData(arena, length, nullD, pick(2), poisonNulls = false)
          runKernel3(k, y, m, d, makeOutput(arena, length), length)
        }
        val feb30 = (2024, 2, 30)
        val farYear = (VarkaChrono.MAKE_DATE_MAX_YEAR + 1, 6, 15)
        val earlyYear = (VarkaChrono.MAKE_DATE_MIN_YEAR - 1, 6, 15)
        val farAndInvalid = (VarkaChrono.MAKE_DATE_MAX_YEAR + 1, 13, 1)
        // In range and valid: both forms run.
        assert(run(ansi, 64, -1, feb30, none, none, none) === 0)
        assert(run(nul, 64, -1, feb30, none, none, none) === 0)
        // An invalid date: the ANSI form declines (dense body, then masked, then epilogue).
        val declined = VarkaFusedKernel.STATUS_CHRONO_RANGE
        assert(run(ansi, 64, 3, feb30, none, none, none) === declined)
        assert(run(ansi, 64, 3, feb30, _ == 40, none, none) === declined)
        assert(run(ansi, 17, 16, feb30, none, none, none) === declined)
        // ... and does not decline it under a null in any of the three inputs.
        assert(run(ansi, 64, 3, feb30, _ == 3, none, none) === 0)
        assert(run(ansi, 64, 3, feb30, none, _ == 3, none) === 0)
        assert(run(ansi, 64, 3, feb30, none, none, _ == 3) === 0)
        // The NULL form never declines an invalid date.
        assert(run(nul, 64, 3, feb30, none, none, none) === 0)
        assert(run(nul, 17, 16, feb30, none, none, none) === 0)
        // A year past either limit declines under both forms, and a null there does not.
        for ((k, name) <- Seq((ansi, "ansi"), (nul, "null")); bad <- Seq(farYear, earlyYear)) {
          assert(run(k, 64, 5, bad, none, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE,
            s"$name $bad in a loop lane")
          assert(run(k, 17, 16, bad, none, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE,
            s"$name $bad in an epilogue lane")
          assert(run(k, 64, 5, bad, _ == 5, none, none) === 0, s"$name $bad under a null year")
        }
        // Out of range with an invalid month is a decline, not a null, under the NULL form too.
        assert(run(nul, 64, 5, farAndInvalid, none, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
      } finally {
        arena.close()
      }
    } finally {
      loaderA.release()
      loaderN.release()
    }
  }

  test("a null-free batch with an invalid date under the NULL form yields a null " +
      "lane - the dense fast path is not taken by a kernel that nulls a valid input") {
    val (nul, loader) = load(emitMulti(Seq(makeDateNull), 3, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        val length = 64
        val none = (_: Int) => false
        def pick(c: Int)(i: Int): Int =
          if (i == 9) { if (c == 0) 2024 else if (c == 1) 2 else 30 }
          else tripleData(makeDateValid)(c, i)
        val y = makeInputData(arena, length, none, pick(0))
        val m = makeInputData(arena, length, none, pick(1))
        val d = makeInputData(arena, length, none, pick(2))
        val out = makeOutput(arena, length)
        assert(runKernel3(nul, y, m, d, out, length) === 0)
        val bits = out._2
        def valid(i: Int): Boolean = (bits.get(ValueLayout.JAVA_BYTE, i / 8) >> (i % 8) & 1) == 1
        assert(!valid(9), "the invalid date must be a null lane")
        assert((0 until length).filter(_ != 9).forall(valid), "every other lane is valid")
        val expected: (Int, Int) => Int = tripleData(makeDateValid)
        assert(out._1.get(ValueLayout.JAVA_INT, 10 * 4L) ===
          VarkaChrono.makeDate(expected(0, 10), expected(1, 10), expected(2, 10)))
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the NULL form's kernel has no dense methods and the ANSI form's has both") {
    val nulNames = methodNames(emitMulti(Seq(makeDateNull), 3, 0))
    assert(!nulNames.contains("runDense") && !nulNames.contains("loopDense0"), nulNames)
    assert(nulNames.contains("runMasked") && nulNames.contains("loopMasked0"), nulNames)
    val ansiNames = methodNames(emitMulti(Seq(makeDateAnsi), 3, 0))
    assert(ansiNames.contains("runDense") && ansiNames.contains("runMasked"), ansiNames)
  }

  test("make_date costs what PLAN_TASK_42.md 3.6 registered under both forms, and " +
      "no sibling moved") {
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, inputs: Int, literals: Int = 0,
        method: String = "loopDense0"): Int =
      laneOps(emitMulti(Seq(root), inputs, literals, VarkaEmitOptions.DEFAULTS)._2, method)
    val counts = Seq(
      ("make_date ANSI, dense loop", ops(makeDateAnsi, 3), 57),
      ("make_date ANSI, masked loop", ops(makeDateAnsi, 3, method = "loopMasked0"), 57),
      ("make_date NULL, masked loop", ops(makeDateNull, 3, method = "loopMasked0"), 57),
      ("add_months", ops(new AddMonths(col, new LiteralSlot(0)), 1, literals = 1), 112),
      ("dayofyear", ops(new DayOfYear(col), 1), 43))
    val table = counts.map { case (n, got, want) => s"$n=$got (registered $want)" }
    assert(counts.forall { case (_, got, want) => got == want },
      s"the register moved; re-pin from these IntVector counts:\n  " + table.mkString("\n  "))
  }

  // Task 37's days: the ISO corners its plan names - the week-53 years, the January days that
  // belong to the old year and the December days that belong to the new one - and Velox's
  // Spark-compatibility fixtures (velox/functions/sparksql/tests/DateTimeFunctionsTest.cpp),
  // written against Spark by people who had to match it exactly, over the calendar boundary set.
  private val isoWeekDays: Array[Int] = Array(
      LocalDate.of(2015, 12, 28), LocalDate.of(2016, 1, 1), LocalDate.of(2019, 12, 30),
      LocalDate.of(2020, 12, 31), LocalDate.of(2021, 1, 1),
      LocalDate.of(2004, 12, 31), LocalDate.of(2009, 12, 31), LocalDate.of(2015, 12, 31),
      LocalDate.of(2026, 12, 31),
      LocalDate.of(1919, 12, 31), LocalDate.of(1969, 12, 31), LocalDate.of(1960, 1, 1),
      LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31),
      // leap years ending on a Thursday, a Friday and a Saturday
      LocalDate.of(2020, 12, 31), LocalDate.of(2004, 12, 31), LocalDate.of(2016, 12, 31)
    ).map(_.toEpochDay.toInt) ++ calendarBoundaryDays

  private def isoWeekDay(c: Int, i: Int): Int =
    if (i < isoWeekDays.length) isoWeekDays(i) else i * 9973 - 400000

  test("weekofyear matches IsoFields over the ISO corners, Velox's fixtures and the " +
      "calendar boundaries, under both prefix forms and every mod-7 lowering") {
    // The shift alone and Year over it (task 58's shape) ride along: the oracle for the
    // shift is java.time's own adjuster, and Year over the Thursday is the ISO week-based
    // year, both from the definition rather than from the lowering.
    val thursday = new ThursdayOf(new ColumnRef(0))
    val roots = Seq[VarkaVectorIR](new WeekOfYear(thursday), thursday, new Year(thursday))
    for (julian <- Seq(true, false); mod <- VarkaEmitOptions.FloorMod7.values()) {
      val options = VarkaEmitOptions.DEFAULTS.withJulianMap(julian).withFloorMod7(mod)
      checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = isoWeekDay,
        ctx = s"julian=$julian mod=$mod", options = options)
    }
  }

  test("weekofyear matches IsoFields on every day from 1990-12-20 to 2030-01-10") {
    // Forty year boundaries in both directions. The Thursday rule claims the boundaries are
    // automatic; this is the check, at a length that puts every one of them in a loop lane
    // and at one that leaves some in a tail lane.
    val start = LocalDate.of(1990, 12, 20).toEpochDay.toInt
    val end = LocalDate.of(2030, 1, 10).toEpochDay.toInt
    val roots = Seq[VarkaVectorIR](new WeekOfYear(new ThursdayOf(new ColumnRef(0))))
    checkMatrix(roots, 1, Array.empty[Int], Seq(end - start + 1, 4093),
      nullPatterns.map(p => Seq(p._2)), data = (_, i) => start + i, ctx = "dense")
  }

  test("WeekOfYear over anything but a ThursdayOf is refused at analysis") {
    // The lowering is the ISO week of a Thursday only; the compiler builds the pair, and the
    // emitter refuses any other tree rather than emitting a plausible wrong week.
    val col = new ColumnRef(0)
    val lit = new LiteralSlot(0)
    for (child <- Seq[VarkaVectorIR](col, new AddDays(col, lit), new NextDay(col, lit))) {
      val e = intercept[IllegalArgumentException](emitMulti(Seq(new WeekOfYear(child)), 1, 1))
      assert(e.getMessage.contains("WeekOfYear's child must be a ThursdayOf"), e.getMessage)
    }
  }

  test("the Thursday shift and the week tail cost what PLAN_TASK_37.md 3.3 " +
      "registered, and adding the nodes moved no sibling's bytes") {
    // Off the class file, like the task 35 register, at the shipped options.
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, literals: Int = 0): Int =
      laneOps(emitMulti(Seq(root), 1, literals, VarkaEmitOptions.DEFAULTS)._2, "loopDense0")
    val counts = Seq(
      ("ThursdayOf", ops(new ThursdayOf(col)), 19),
      ("weekofyear", ops(new WeekOfYear(new ThursdayOf(col))), 64),
      ("yearofweek", ops(new Year(new ThursdayOf(col))), 51),
      ("next_day", ops(new NextDay(col, new LiteralSlot(0)), literals = 1), 18),
      ("weekday", ops(new WeekDay(col)), 17),
      ("dayofyear", ops(new DayOfYear(col)), 43),
      ("month", ops(new Month(col)), 35),
      ("dayofmonth", ops(new DayOfMonth(col)), 36))
    val table = counts.map { case (n, got, want) => s"$n=$got (registered $want)" }
    assert(counts.forall { case (_, got, want) => got == want },
      s"the register moved; re-pin from these dense-loop IntVector counts:\n  " +
        table.mkString("\n  "))
  }
  test("dayofweek_iso matches getWeekDay + 1 over two whole weeks and the calendar " +
      "boundaries, under every mod-7 lowering") {
    // A full week around 1970-01-01 and one around 2024-01-01, so the Sunday wrap (7, never 0)
    // is in a loop lane and a tail lane, plus the boundary set at both ends of the range.
    val week1970 = (-4 to 3).toArray
    val week2024 = (0 to 7).map(i => LocalDate.of(2024, 1, 1).toEpochDay.toInt + i).toArray
    val days = week1970 ++ week2024 ++ calendarBoundaryDays
    def data(c: Int, i: Int): Int = if (i < days.length) days(i) else i * 9973 - 400000
    val roots = Seq[VarkaVectorIR](new DayOfWeekIso(new ColumnRef(0)),
      new WeekDay(new ColumnRef(0)), new DayOfWeek(new ColumnRef(0)))
    for (mod <- VarkaEmitOptions.FloorMod7.values()) {
      checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = data, ctx = s"mod=$mod",
        options = VarkaEmitOptions.DEFAULTS.withFloorMod7(mod))
    }
  }

  test("dayofweek_iso costs weekday plus one, and neither sibling moved") {
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR): Int =
      laneOps(emitMulti(Seq(root), 1, 0, VarkaEmitOptions.DEFAULTS)._2, "loopDense0")
    val counts = Seq(
      ("dayofweek_iso", ops(new DayOfWeekIso(col)), 18),
      ("weekday", ops(new WeekDay(col)), 17),
      ("dayofweek", ops(new DayOfWeek(col)), 18))
    val table = counts.map { case (n, got, want) => s"$n=$got (registered $want)" }
    assert(counts.forall { case (_, got, want) => got == want },
      s"the register moved; re-pin from these dense-loop IntVector counts:\n  " +
        table.mkString("\n  "))
  }

  test("the week tail and Year over one ThursdayOf share a prefix, and neither " +
      "shares with year over the bare date") {
    // weekofyear(d) and yearofweek(d) (task 58) in one loop method decompose the Thursday
    // once, asserted the way the task 32 and 35 sharing tests are; year(d) beside them runs
    // its own prefix over the date, which is the cost row 37 says a mixed projection pays.
    val col = new ColumnRef(0)
    val thursday = new ThursdayOf(col)
    val pair = Seq[VarkaVectorIR](new WeekOfYear(thursday), new Year(thursday))
    val wide = VarkaEmitOptions.DEFAULTS.withGroupBudget(200)
    val shared = laneOps(emitMulti(pair, 1, 0, wide)._2, "loopDense0")
    val unshared = laneOps(emitMulti(pair, 1, 0, wide.withShareChronoPrefix(false))._2,
      "loopDense0")
    assert(unshared - shared >= 20,
      s"expected the prefix to be shared: $shared IntVector ops shared vs $unshared unshared")
    val weekAlone = laneOps(emitMulti(Seq(pair.head), 1, 0, wide)._2, "loopDense0")
    assert(shared - weekAlone < 10,
      s"Year over the shared shift should cost under ten ops more: $shared vs $weekAlone")
    val withYear = laneOps(emitMulti(pair :+ new Year(col), 1, 0, wide)._2, "loopDense0")
    assert(withYear - shared >= 20,
      s"year(d) beside the pair should run its own prefix: $withYear vs $shared")
  }

  test("the trunc tails cost what PLAN_TASK_35.md section 8 registered, per level " +
      "and form, and adding the node moved no other node's bytes") {
    // Off the class file, like the task 53 and 54 registers, at the shipped prefix options.
    // The DayOfYear arm was refactored onto emitJanuaryDayOfYear for this task, so its count
    // is pinned too: the extraction's bytes must not have moved.
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, form: VarkaEmitOptions.TruncDateForm): Int =
      laneOps(emitMulti(Seq(root), 1, 0, VarkaEmitOptions.DEFAULTS.withTruncDate(form))._2,
        "loopDense0")
    val dayOfYear = ops(new DayOfYear(col), VarkaEmitOptions.TruncDateForm.SUBTRACT)
    val month = ops(new Month(col), VarkaEmitOptions.TruncDateForm.SUBTRACT)
    val dayOfMonth = ops(new DayOfMonth(col), VarkaEmitOptions.TruncDateForm.SUBTRACT)
    val registered = Seq(
      ("dayofyear", new DayOfYear(col), VarkaEmitOptions.TruncDateForm.SUBTRACT, 43),
      ("trunc YEAR, subtract", new TruncDate(col, TruncLevel.YEAR),
        VarkaEmitOptions.TruncDateForm.SUBTRACT, 45),
      ("trunc MONTH, subtract", new TruncDate(col, TruncLevel.MONTH),
        VarkaEmitOptions.TruncDateForm.SUBTRACT, 36),
      ("trunc QUARTER, subtract", new TruncDate(col, TruncLevel.QUARTER),
        VarkaEmitOptions.TruncDateForm.SUBTRACT, 62),
      ("trunc YEAR, recompose", new TruncDate(col, TruncLevel.YEAR),
        VarkaEmitOptions.TruncDateForm.RECOMPOSE, 70),
      ("trunc MONTH, recompose", new TruncDate(col, TruncLevel.MONTH),
        VarkaEmitOptions.TruncDateForm.RECOMPOSE, 74),
      ("trunc QUARTER, recompose", new TruncDate(col, TruncLevel.QUARTER),
        VarkaEmitOptions.TruncDateForm.RECOMPOSE, 79))
    // The subtract MONTH form is dayofmonth's tail with the increment replaced by the
    // subtraction (36 against 36), and YEAR is dayofyear's plus two (45 against 43).
    assert(month === 35 && dayOfMonth === 36 && dayOfYear === 43,
      s"the siblings moved: month=$month dayofmonth=$dayOfMonth dayofyear=$dayOfYear")
    val counted = registered.map { case (name, root, form, _) => (name, ops(root, form)) }
    assert(counted.map(_._2) === registered.map(_._4),
      s"the register: ${counted.map { case (n, c) => s"$n=$c" }.mkString(", ")}; " +
        s"month=$month dayofmonth=$dayOfMonth dayofyear=$dayOfYear")
  }

  test("the emitted trunc kernels match DateTimeUtils.truncDate over the whole covered range " +
      "(opt-in: -Dvarka.sweep=true; task 35)") {
    // The gate that found the leap-flag constant tasks 34 and 36 each shipped wrong, and
    // which no boundary list caught: every day the narrowed prefix covers, through the real
    // emitted kernel, per level, under both lowerings and both prefix forms.
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernels")
    for (form <- truncForms; julian <- Seq(true, false); neri <- Seq(true, false)) {
      sweepTrunc(VarkaEmitOptions.DEFAULTS.withTruncDate(form).withJulianMap(julian)
        .withNeriSchneiderMonth(neri))
    }
  }

  // -------------------------------------------------------------------------------------------
  // Task 61: trunc with a level column (TruncDateDynamic).
  // -------------------------------------------------------------------------------------------

  private val dynamicTrunc = new TruncDateDynamic(new ColumnRef(0), new ColumnRef(1))

  /** The leaf's four codes, cycled by row; nothing else ever reaches a live level lane. */
  private def levelByRow(i: Int): Int = TruncLevelLeaf.WEEK + i % 4

  test("trunc with a level column matches DateTimeUtils.truncDate over the calendar " +
      "boundaries and every null pattern of both columns, under every prefix and mod-7 form") {
    // The level cycles the four codes the leaf can hand the kernel, so every boundary date
    // meets every level somewhere in the matrix; combos(2) drives the null-level-on-a-live-date
    // pattern that a word aliasing the date's alone would get wrong (task 38's trap, task 59's
    // again). The mod-7 lowering is the week result's, so all three ship variants run.
    def data(c: Int, i: Int): Int = if (c == 0) calendarBoundaryDay(0, i) else levelByRow(i)
    for (mod7 <- VarkaEmitOptions.FloorMod7.values(); julian <- Seq(true, false);
        neri <- Seq(true, false)) {
      val options = VarkaEmitOptions.DEFAULTS.withFloorMod7(mod7).withJulianMap(julian)
        .withNeriSchneiderMonth(neri)
      checkMatrix(Seq(dynamicTrunc), 2, Array.emptyIntArray, Seq(1, 13, 17, 64, 65, 1000),
        combos(2), data = data, ctx = s"trunc dynamic $mod7 julianMap=$julian neri=$neri",
        options = options)
    }
  }

  test("every level over every day of two years, beside the literal node sharing " +
      "its prefix") {
    // Day by day over 2023 and 2024 at one level per pass, so every week, month, quarter and
    // year start is crossed in both year kinds at the level that reads it - the week rows are
    // the ones no literal test covers, since the literal WEEK is a next_day rewrite. The
    // literal MONTH node beside it shares the prefix fragment with the dynamic one.
    val start = LocalDate.of(2023, 1, 1).toEpochDay.toInt
    val days = (0 until 731).map(start + _)
    val roots = Seq[VarkaVectorIR](dynamicTrunc, new TruncDate(new ColumnRef(0), TruncLevel.MONTH))
    for (level <- TruncLevelLeaf.WEEK to TruncLevelLeaf.YEAR) {
      def data(c: Int, i: Int): Int =
        if (c == 0) { if (i < days.length) days(i) else i * 9973 - 400000 } else level
      checkMatrix(roots, 2, Array.emptyIntArray, Seq(731), combos(2).take(4), data = data,
        ctx = s"trunc dynamic two years level=$level")
    }
  }

  test("a literal level is rejected at analysis - that shape is the literal node") {
    val e = intercept[IllegalArgumentException](
      emitMulti(Seq(new TruncDateDynamic(new ColumnRef(0), new LiteralSlot(0))), 1, 1))
    assert(e.getMessage.contains("trunc's level must be a column"), e.getMessage)
  }

  test("the dynamic tail costs what PLAN_TASK_61.md 3.3 registered") {
    // The literal nodes' own counts are the task 35 register above; their exact bytes were
    // hashed before and after the factoring (PLAN_TASK_61.md 9). This pins the dynamic form.
    assert(laneOps(emitMulti(Seq(dynamicTrunc), 2, 0)._2, "loopDense0") === 91)
  }

  test("the emitted dynamic trunc kernel matches DateTimeUtils.truncDate over the whole " +
      "covered range at every level (opt-in: -Dvarka.sweep=true; task 61)") {
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernel")
    for (mod7 <- VarkaEmitOptions.FloorMod7.values(); julian <- Seq(true, false);
        neri <- Seq(true, false)) {
      sweepTruncDynamic(VarkaEmitOptions.DEFAULTS.withFloorMod7(mod7).withJulianMap(julian)
        .withNeriSchneiderMonth(neri))
    }
  }

  private def sweepTruncDynamic(options: VarkaEmitOptions): Unit = {
    val (kernel, loader) = load(emitMulti(Seq(dynamicTrunc), 2, 0, options))
    try {
      val arena = Arena.ofConfined()
      try {
        val chunk = 1 << 16
        val data = alloc(arena, chunk * 4L)
        val levels = alloc(arena, chunk * 4L)
        val validity = alloc(arena, (chunk + 7) / 8L)
        validity.fill(0xFF.toByte)
        val out = makeOutput(arena, chunk)
        for (level <- TruncLevelLeaf.WEEK to TruncLevelLeaf.YEAR) {
          var i = 0
          while (i < chunk) {
            levels.set(ValueLayout.JAVA_INT, i * 4L, level)
            i += 1
          }
          var day = VarkaChrono.NARROW_MIN_DAYS
          var mismatches = 0
          while (day <= VarkaChrono.NARROW_MAX_DAYS) {
            val n = math.min(chunk, VarkaChrono.NARROW_MAX_DAYS - day + 1)
            i = 0
            while (i < n) {
              data.set(ValueLayout.JAVA_INT, i * 4L, day + i)
              i += 1
            }
            val status = kernel.run(Array(data.address(), levels.address()),
              Array(validity.address(), validity.address()), Array(0, 0),
              Array(out._1.address()), Array(out._2.address()), Array.empty[Int], n)
            assert(status === 0, s"the kernel declined an in-range batch at day $day")
            i = 0
            while (i < n) {
              val d = day + i
              val got = out._1.get(ValueLayout.JAVA_INT, i * 4L)
              val want = DateTimeUtils.truncDate(d, level)
              if (got != want) {
                mismatches += 1
                if (mismatches < 4) {
                  fail(s"day $d level $level under ${options.canonical()}: " +
                    s"emitted $got, DateTimeUtils.truncDate $want")
                }
              }
              i += 1
            }
            day += n
          }
          assert(mismatches === 0,
            s"level $level: the emitted kernel disagreed on $mismatches days")
        }
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  private def sweepTrunc(options: VarkaEmitOptions): Unit = {
    val (kernel, loader) = load(emitMulti(truncRoots, 1, 0, options))
    val levels = Seq(DateTimeUtils.TRUNC_TO_YEAR, DateTimeUtils.TRUNC_TO_MONTH,
      DateTimeUtils.TRUNC_TO_QUARTER)
    try {
      val arena = Arena.ofConfined()
      try {
        val chunk = 1 << 16
        val data = alloc(arena, chunk * 4L)
        val validity = alloc(arena, (chunk + 7) / 8L)
        validity.fill(0xFF.toByte)
        val outs = truncRoots.map(_ => makeOutput(arena, chunk))
        var day = VarkaChrono.NARROW_MIN_DAYS
        var mismatches = 0
        while (day <= VarkaChrono.NARROW_MAX_DAYS) {
          val n = math.min(chunk, VarkaChrono.NARROW_MAX_DAYS - day + 1)
          var i = 0
          while (i < n) {
            data.set(ValueLayout.JAVA_INT, i * 4L, day + i)
            i += 1
          }
          val status = kernel.run(Array(data.address()), Array(validity.address()), Array(0),
            outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
            Array.empty[Int], n)
          assert(status === 0, s"the kernel declined an in-range batch at day $day")
          i = 0
          while (i < n) {
            val d = day + i
            var o = 0
            while (o < levels.length) {
              val got = outs(o)._1.get(ValueLayout.JAVA_INT, i * 4L)
              val want = DateTimeUtils.truncDate(d, levels(o))
              if (got != want) {
                mismatches += 1
                if (mismatches < 4) {
                  fail(s"day $d level ${levels(o)} under ${options.canonical()}: " +
                    s"emitted $got, DateTimeUtils.truncDate $want")
                }
              }
              o += 1
            }
            i += 1
          }
          day += n
        }
        assert(mismatches === 0, s"the emitted kernels disagreed on $mismatches days")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the emitted kernel agrees with VarkaChrono's scalar twin, not only with LocalDate") {
    // Every other test in this file checks the emitted kernel against LocalDate and
    // VarkaChronoSuite checks VarkaChrono against LocalDate separately - each a genuine
    // definition-level oracle, deliberately not each other (see evalValue's comment above).
    // That leaves a gap this test closes: nothing committed (the direct comparison only runs
    // opt-in, in the exhaustive sweep below) ever compares the emitted bytecode against
    // VarkaChrono directly, so a future edit that moved both the same wrong way could agree
    // with LocalDate on every curated/pseudo-random day above and still have silently
    // diverged from VarkaChrono - contradicting VarkaChrono's own class-doc promise that "any
    // disagreement with the emitted kernel is an emission bug". A committed, non-exhaustive
    // sample is enough to catch that: it does not need to be exhaustive, since the exhaustive
    // sweep already exists for the LocalDate side and opting into it is what full coverage
    // means here.
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)))
    val days = Array(
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS, VarkaChrono.NARROW_MAX_DAYS - 1, -1, 0, 1,
      LocalDate.of(1600, 2, 29).toEpochDay.toInt, LocalDate.of(1900, 3, 1).toEpochDay.toInt,
      LocalDate.of(2000, 2, 29).toEpochDay.toInt
    ) ++ Array.tabulate(2000)(i => i * 9973 - 400000)
      .filter(VarkaChrono.inNarrowRange)
    val (kernel, loader) = load(emitMulti(roots, 1, 0))
    try {
      val arena = Arena.ofConfined()
      try {
        val data = alloc(arena, days.length * 4L)
        val validity = alloc(arena, (days.length + 7) / 8L)
        validity.fill(0xFF.toByte)
        days.zipWithIndex.foreach { case (d, i) => data.set(ValueLayout.JAVA_INT, i * 4L, d) }
        val outs = roots.map(_ => makeOutput(arena, days.length))
        val status = kernel.run(Array(data.address()), Array(validity.address()), Array(0),
          outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
          Array.empty[Int], days.length)
        assert(status === 0, "the kernel declined an in-range batch")
        days.indices.foreach { i =>
          val fields = VarkaChrono.narrowed(days(i))
          val want = Seq(fields.year, fields.month, fields.dayOfMonth, fields.quarter,
            fields.dayOfYear)
          val got = outs.map(_._1.get(ValueLayout.JAVA_INT, i * 4L))
          assert(got === want, s"day ${days(i)}: emitted $got, VarkaChrono $want")
        }
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("last_day matches DateTimeUtils.getLastDayOfMonth over the range it covers " +
      "(task 36)") {
    val root = new LastDay(new ColumnRef(0))
    val inRange = Array(
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS, VarkaChrono.NARROW_MAX_DAYS - 1,
      -1, 0, 1, -719468, -719162,
      LocalDate.of(1900, 2, 15).toEpochDay.toInt, LocalDate.of(1900, 2, 28).toEpochDay.toInt,
      LocalDate.of(1900, 3, 1).toEpochDay.toInt,
      LocalDate.of(2000, 2, 15).toEpochDay.toInt, LocalDate.of(2000, 2, 29).toEpochDay.toInt,
      LocalDate.of(2000, 3, 1).toEpochDay.toInt,
      LocalDate.of(2023, 2, 28).toEpochDay.toInt, LocalDate.of(2023, 3, 1).toEpochDay.toInt,
      LocalDate.of(2024, 2, 29).toEpochDay.toInt, LocalDate.of(2024, 3, 1).toEpochDay.toInt,
      LocalDate.of(1, 1, 1).toEpochDay.toInt, LocalDate.of(9999, 12, 31).toEpochDay.toInt)
    // Every month of a leap year (2024) and of a common year (2023), so all twelve linear-form
    // lengths are exercised twice and February is exercised under both leap rules.
    val everyMonth = (2023 to 2024).flatMap { y =>
      (1 to 12).map(m => LocalDate.of(y, m, 15).toEpochDay.toInt)
    }.toArray
    val boundary = inRange ++ everyMonth
    def days(c: Int, i: Int): Int =
      if (i < boundary.length) boundary(i) else i * 9973 - 400000
    checkMatrix(Seq(root), 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "last_day narrowed")
  }

  test("add_months matches DateTimeUtils across clamp boundaries and month offsets") {
    val root = new AddMonths(new ColumnRef(0), new LiteralSlot(0))
    // Every one of these has a different day-of-month than the month it lands in, at both
    // ends of the year and across a common/leap February - the clamp is where a wrong
    // implementation fails, per PLAN_TASK_40.md section 4.
    val clampDays = Array(
      LocalDate.of(2023, 1, 31).toEpochDay.toInt, LocalDate.of(2023, 3, 31).toEpochDay.toInt,
      LocalDate.of(2020, 2, 29).toEpochDay.toInt, LocalDate.of(2024, 2, 28).toEpochDay.toInt,
      LocalDate.of(1900, 1, 31).toEpochDay.toInt, LocalDate.of(2000, 1, 31).toEpochDay.toInt,
      LocalDate.of(2023, 12, 31).toEpochDay.toInt, 0, -1, 1,
      // A four-digit year plus a multi-century month offset overflows the 32-bit lane
      // multiply behind the /400 and /100 magic (VarkaChrono.YEAR_CENTURY_M's javadoc) -
      // the exact shape that found the bug during development. Near-epoch dates alone do
      // not reach it.
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS, VarkaChrono.NARROW_MAX_DAYS - 1, 3818579, 3811279)
    def days(c: Int, i: Int): Int =
      if (i < clampDays.length) clampDays(i) else i * 9973 - 400000
    // Offsets of 0, +-1, +-11, +-12, +-13, +-1200 cross a multiple of 12 both ways, which is
    // where the month-arithmetic dividend's own bias could be off by one.
    // Both prefix forms (task 54): add_months decomposes through the prefix and recomposes, so
    // a year of era that was off by one would surface here before anywhere else.
    for {
      julian <- Seq(true, false)
      offset <- Seq(0, 1, -1, 11, -11, 12, -12, 13, -13, 1200, -1200,
        VarkaChrono.MONTH_ARITH_MAX_MONTHS, VarkaChrono.MONTH_ARITH_MIN_MONTHS)
    } {
      checkMatrix(Seq(root), 1, Array(offset), Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = days,
        ctx = s"add_months offset=$offset julianMap=$julian",
        options = VarkaEmitOptions.DEFAULTS.withJulianMap(julian))
    }
  }

  test("a chained calendar computation matches across every lane-group tail length") {
    // Historically an epilogue-mask/guard interaction bug: a masked load fills the lanes past
    // `length` with 0, and the now-removed guard ran on the node's *input*, which here is a
    // computed value (0 - 5400000, well outside the guard's range) - so an unmasked check
    // declined every batch whose length was not a lane multiple, even though every real row,
    // near 2022, was in range. Task 51 removed the guard entirely; this case is kept as a
    // general correctness check on a chained node across non-lane-multiple lengths.
    val root = new Year(new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    def days(c: Int, i: Int): Int = 19000 + i
    checkMatrix(Seq(root), 1, Array(5400000), Seq(16, 17, 31, 64, 1000, 4095, 4096),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "epilogue-guard")
  }

  test("the emitted calendar kernel matches LocalDate over its whole range " +
      "(opt-in: -Dvarka.sweep=true)") {
    // VarkaChrono's own suite sweeps the scalar model over all 16,777,216 days, and the
    // emitter loads the same constants - but it re-expresses the algorithm as bytecode, with
    // its own op order, carry steps and mask polarity. Only this sweep holds the *emitted*
    // form to the same standard; without it the class doc's "cannot drift" covers the
    // constants and not the code, and a transposed slot would survive every other test.
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernel")
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)))
    // Both lowerings, because task 32 step B's shared prefix re-orders nothing but does make
    // four of these five outputs read locals a fifth wrote. A transposed slot there would
    // survive every bounded test in this suite and fail here.
    //
    // Both switch positions, and a year-only kernel beside the four-field one, because task
    // 48's elision is a claim about a local that is never written: the four-field shape keeps
    // the month step under sharing (three of its tails read it) and only the year-only shape
    // sweeps the elided prefix in both sharing modes. This is the gate that matters for that
    // claim - the bounded tests locate a failure it would only report.
    //
    // `dayofyear` alone for the same reason: task 34's tail is the second one that reads the
    // January turn off the day of year rather than off the month, so its prefix elides the
    // step too - and a tail reading an unwritten local returns a plausible wrong day, not a
    // crash. This is the only test that would notice.
    //
    // Both month axes (task 53), which is what makes the older lowering a live reference
    // variant rather than dead code: the two compute the same fields through different
    // constants on differently-based month indices, so agreeing with LocalDate over the whole
    // range is also them agreeing with each other over it. This is the gate that matters for
    // the axis change - `add_months` and `last_day` recompose through these constants, and a
    // month-axis mistake in either is exactly what a boundary set misses and a sweep cannot.
    val yearOnly = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    val dayOfYearOnly = Seq[VarkaVectorIR](new DayOfYear(new ColumnRef(0)))
    // Both prefix forms (task 54), for the same reason as the month axes: the two reach the
    // year of era and the day of year through different divisions, and only the sweep holds
    // the emitted Julian map to LocalDate over every covered day rather than over an era.
    for {
      options <- Seq(unshared, sharing)
      elide <- Seq(true, false)
      neri <- Seq(true, false)
      julian <- Seq(true, false)
    } {
      val axis = options.withElideChronoMonth(elide).withNeriSchneiderMonth(neri)
        .withJulianMap(julian)
      sweepCalendar(roots, axis)
      sweepCalendar(yearOnly, axis, date => Seq(date.getYear))
      sweepCalendar(dayOfYearOnly, axis, date => Seq(date.getDayOfYear))
    }
  }

  /** What `LocalDate` says the five extractions are, in the order they are emitted. */
  private val allCalendarFields: LocalDate => Seq[Int] = date =>
    Seq(date.getYear, date.getMonthValue, date.getDayOfMonth,
      date.get(IsoFields.QUARTER_OF_YEAR), date.getDayOfYear)

  private def sweepCalendar(
      roots: Seq[VarkaVectorIR],
      options: VarkaEmitOptions,
      expected: LocalDate => Seq[Int] = allCalendarFields): Unit = {
    val (kernel, loader) = load(emitMulti(roots, 1, 0, options))
    try {
      val arena = Arena.ofConfined()
      try {
        val chunk = 1 << 16
        val data = alloc(arena, chunk * 4L)
        val validity = alloc(arena, (chunk + 7) / 8L)
        validity.fill(0xFF.toByte)
        val outs = roots.map(_ => makeOutput(arena, chunk))
        var day = VarkaChrono.NARROW_MIN_DAYS
        var mismatches = 0
        while (day <= VarkaChrono.NARROW_MAX_DAYS) {
          val n = math.min(chunk, VarkaChrono.NARROW_MAX_DAYS - day + 1)
          var i = 0
          while (i < n) {
            data.set(ValueLayout.JAVA_INT, i * 4L, day + i)
            i += 1
          }
          val status = kernel.run(Array(data.address()), Array(validity.address()), Array(0),
            outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
            Array.empty[Int], n)
          assert(status === 0, s"the kernel declined an in-range batch at day $day")
          i = 0
          while (i < n) {
            val date = LocalDate.ofEpochDay((day + i).toLong)
            val got = outs.map(_._1.get(ValueLayout.JAVA_INT, i * 4L))
            val want = expected(date)
            if (got != want) {
              mismatches += 1
              if (mismatches < 4) {
                fail(s"day ${day + i} ($date), shared=${options.shareChronoPrefix()}, " +
                  s"elided=${options.elideChronoMonth()}: emitted $got, LocalDate $want")
              }
            }
            i += 1
          }
          day += n
        }
        assert(mismatches === 0, s"the emitted kernel disagreed on $mismatches days, " +
          s"shared=${options.shareChronoPrefix()}, elided=${options.elideChronoMonth()}")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the emitted last_day kernel matches DateTimeUtils over its whole range " +
      "(opt-in: -Dvarka.sweep=true; task 36)") {
    // The same discipline the four-field sweep above holds the emitter to, for last_day's
    // own tail: emitLeapFlag's magic constants were first written as an exact one-shot magic
    // that overflows a 32-bit lane's signed product past y ~ 25600 (roughly year 12400), which
    // no test narrower than this sweep caught - every boundary list in this file's other tests
    // happened to land under that threshold. Guard against that class of bug reappearing.
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernel")
    // Both month axes (task 53). This node is the one whose month-length arithmetic reads the
    // prefix slot directly rather than through a tail, so it is the only place the axis had to
    // be handled inside a recomposing node - which makes it the one most worth sweeping twice.
    for {
      neri <- Seq(true, false)
      julian <- Seq(true, false)
    } {
      sweepLastDay(VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(neri).withJulianMap(julian))
    }
  }

  private def sweepLastDay(options: VarkaEmitOptions): Unit = {
    val (kernel, loader) = load(emitMulti(Seq(new LastDay(new ColumnRef(0))), 1, 0, options))
    try {
      val arena = Arena.ofConfined()
      try {
        val chunk = 1 << 16
        val data = alloc(arena, chunk * 4L)
        val validity = alloc(arena, (chunk + 7) / 8L)
        validity.fill(0xFF.toByte)
        val out = makeOutput(arena, chunk)
        var day = VarkaChrono.NARROW_MIN_DAYS
        var mismatches = 0
        while (day <= VarkaChrono.NARROW_MAX_DAYS) {
          val n = math.min(chunk, VarkaChrono.NARROW_MAX_DAYS - day + 1)
          var i = 0
          while (i < n) {
            data.set(ValueLayout.JAVA_INT, i * 4L, day + i)
            i += 1
          }
          val status = kernel.run(Array(data.address()), Array(validity.address()), Array(0),
            Array(out._1.address()), Array(out._2.address()), Array.empty[Int], n)
          assert(status === 0, s"the kernel declined an in-range batch at day $day")
          i = 0
          while (i < n) {
            val d = day + i
            val got = out._1.get(ValueLayout.JAVA_INT, i * 4L)
            val want = DateTimeUtils.getLastDayOfMonth(d)
            if (got != want) {
              mismatches += 1
              if (mismatches < 4) {
                fail(s"day $d: emitted $got, DateTimeUtils.getLastDayOfMonth $want")
              }
            }
            i += 1
          }
          day += n
        }
        assert(mismatches === 0, s"the emitted kernel disagreed on $mismatches days")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("a day outside the covered range is no longer declined") {
    // Tasks 26 through 40 guarded every calendar extraction against a day outside
    // VarkaChrono.NARROW_MIN_DAYS..NARROW_MAX_DAYS, declining the whole batch to the row
    // engine. Task 51 removed that guard: the arithmetic is still only proven exact inside
    // the narrowed range (VarkaChronoSuite's exhaustive sweep is over exactly that range), but
    // nothing checks it at run time anymore, so a day outside it is now computed silently
    // rather than declined. PLAN_TASK_51.md records why the owner accepted that trade, and
    // Task 52 moved the check to the nodes that can actually manufacture such a day: the
    // compiler bounds every literal shift and the emitter guards a column-offset producer
    // (the "task 52" tests below). A bare column past the range is the column contract's
    // breach, not a guard's business, so this batch is still computed, not declined.
    val root = new Year(new ColumnRef(0))
    val (kernel, loader) = load(emitMulti(Seq(root), 1, 0, VarkaEmitOptions.DEFAULTS))
    try {
      val arena = Arena.ofConfined()
      try {
        val length = 64
        // One day past the range, in a lane the vector loop covers.
        val bad = makeInputData(arena, length, _ => false,
          i => if (i == 3) VarkaChrono.NARROW_MAX_DAYS + 1 else i * 97)
        val out = makeOutput(arena, length)
        assert(runKernel(kernel, bad, out, length) === 0)
        // And in a lane only the epilogue covers, whatever the host's lane count.
        val tail = makeInputData(arena, 17, _ => false,
          i => if (i == 16) VarkaChrono.NARROW_MIN_DAYS - 1 else i * 97)
        val tailOut = makeOutput(arena, 17)
        assert(runKernel(kernel, tail, tailOut, 17) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("calendar siblings over one date share a loop method; plain chains, other " +
      "dates and the ceiling keep them apart") {
    // PLAN_TASK_32.md 10.2's table, pinned by loop-method count. Before B2 this test asserted
    // the opposite for the four fields - one method each, "whatever GROUP_BUDGET would say" -
    // because a method of ~180 ops was believed to be a compile cliff. 7.5 measured that away
    // and clause 2 of groupOutputs now admits an output that reuses a prefix the group already
    // computes, up to FUSED_CEILING. Everything clause 2 does not admit keeps today's grouping,
    // and that half is the guard: whether a merely-shared subchain pays to merge is
    // GROUP_BUDGET's own question (task 17 measured a loss, the file since task 46 shows a win;
    // task 43 owns it), and B2 deliberately does not answer it.
    val col = new ColumnRef(0)
    val fields = Seq[VarkaVectorIR](
      new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    def loops(roots: Seq[VarkaVectorIR], inputs: Int, lits: Int,
        options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Int =
      methodNames(emitMulti(roots, inputs, lits, options)).count(_.startsWith("loopDense"))
    // Four fields over one date: one prefix, four tails, 52 ops in one method.
    assert(loops(fields, 1, 0) === 1)
    // With sharing off there is no prefix to reuse, clause 2 never fires, and each field
    // outweighs GROUP_BUDGET on its own: the four methods of before B2, kept as the reference
    // variant the parity benchmark's "separate" rows are emitted with.
    assert(loops(fields, 1, 0, unshared) === 4)
    // Two dates: the second year reuses nothing (saved = 0) and 38 + 38 > 16.
    assert(loops(Seq(new Year(col), new Year(new ColumnRef(1))), 2, 0) === 2)
    // A plain chain is untouched: add_days and sub_days fit the budget together as they did.
    assert(loops(Seq(new AddDays(col, new LiteralSlot(0)), new SubDays(col, new LiteralSlot(0))),
      1, 1) === 1)
    // A plain output ahead of the siblings: year reuses nothing against [x + 1] and 1 + 38 > 16,
    // so it opens a group of its own, which month then joins.
    assert(loops(Seq(new AddDays(col, new LiteralSlot(0)), new Year(col), new Month(col)),
      1, 1) === 2)
    // The ceiling bounds clause 2: at prefix + two tails the third sibling opens a new group,
    // which the fourth joins - two methods of two.
    val tight = VarkaEmitOptions.DEFAULTS.withFusedCeiling(
      VarkaLoopEmitter.CHRONO_PREFIX_WEIGHT + 2 * VarkaLoopEmitter.CHRONO_FIELD_TAIL_WEIGHT)
    assert(loops(fields, 1, 0, tight) === 2)
    // Greedy in output order, pinned as the limitation 10.2 names rather than fixed: month(d)
    // is offered to the group holding year(d2), whose prefix it cannot reuse, so it forms a
    // third group instead of rejoining year(d). Adjacent, the same three outputs take two.
    assert(loops(Seq(new Year(col), new Year(new ColumnRef(1)), new Month(col)), 2, 0) === 3)
    assert(loops(Seq(new Year(col), new Month(col), new Year(new ColumnRef(1))), 2, 0) === 2)
    // Task 58's debt closes on the way: weekofyear and yearofweek decompose the same shifted
    // day, so they share a method now rather than only the epilogue.
    val shift = new ThursdayOf(col)
    assert(loops(Seq(new WeekOfYear(shift), new Year(shift)), 1, 0) === 1)
  }

  // -------------------------------------------------------------------------------------------
  // Task 32 step B: sharing the civil-from-days prefix between calendar nodes over one date.
  // -------------------------------------------------------------------------------------------

  /** The days the calendar differentials drive: the range's edges, then a strided walk. */
  private def calendarDays(c: Int, i: Int): Int = {
    val edges = Array(
      VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MIN_DAYS + 1,
      VarkaChrono.NARROW_MAX_DAYS, VarkaChrono.NARROW_MAX_DAYS - 1,
      -1, 0, 1, -719468,
      LocalDate.of(1600, 2, 29).toEpochDay.toInt, LocalDate.of(1900, 3, 1).toEpochDay.toInt,
      LocalDate.of(2000, 2, 29).toEpochDay.toInt, LocalDate.of(2023, 12, 31).toEpochDay.toInt)
    if (i < edges.length) edges(i) else i * 9973 - 400000
  }

  // Lengths deliberately chosen odd or prime: a lane count divides 64 and 1000 but none of
  // these, so every case leaves a remainder and so exercises the epilogue - which under
  // today's grouping is the only body that holds two calendar outputs at once, and therefore
  // the only body where sharing does anything at all.
  private val remainderLengths = Seq(1, 13, 17, 63, 1001)

  private val sharing = VarkaEmitOptions.DEFAULTS.withShareChronoPrefix(true)
  private val unshared = VarkaEmitOptions.DEFAULTS.withShareChronoPrefix(false)

  /** The lane ops one emitted body method runs: its `IntVector` invocations, counted off the
   * class file. Task 48's deliverable is a count, not a duration, so it is asserted as one. */
  private def laneOps(bytes: Array[Byte], method: String): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, method, "jdk.incubator.vector.IntVector")

  /** The `IntVector` ops the prefix's March-month step costs: two multiplies (the `* 5` and
   * the magic), the `+ 2`, and the magic's shift. The store into t[5] is not one. */
  /**
   * What the prefix's month step costs, which depends on the axis: four ops on the 0-based one
   * (the `* 5`, the `+ 2`, the magic multiply and its shift) and two on task 53's 3-based one
   * (the `* 2141` and the `+ 197913`). Task 48's elision saves whichever of the two the shape
   * was going to pay, which is the sense in which that task's win shrank rather than went away.
   */
  private def monthStepOps(options: VarkaEmitOptions): Int =
    if (options.neriSchneiderMonth()) 2 else 4

  /** The masked epilogue's bytecode size - the one method every output shares (task 24). */
  private def epilogueSize(
      roots: Seq[VarkaVectorIR], numInputs: Int, options: VarkaEmitOptions): Int =
    VarkaEmitterTestSupport.codeSize(
      emitMulti(roots, numInputs, 0, options)._2, "epilogueMasked")

  test("sharing the calendar prefix changes the bytecode but never the results") {
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)))
    assert(VarkaEmitOptions.DEFAULTS.shareChronoPrefix(),
      "the shared prefix is no longer the default - the epilogue-size case for it is in " +
        "PLAN_TASK_32.md section 7.1, so say why here if it was deliberately turned off")
    assert(epilogueSize(roots, 1, sharing) < epilogueSize(roots, 1, unshared),
      "the shared epilogue is no smaller, so the prefix is still being emitted four times")
    // Both settings over the same matrix and the same java.time oracle. Running the unshared
    // one here too is what makes this a differential rather than a second correctness test:
    // a harness case that the shared lowering fails and the unshared one also fails is a
    // problem with the case, and this says so in the same run.
    for ((options, ctx) <- Seq((unshared, "unshared"), (sharing, "shared"))) {
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = s"$ctx prefix",
        options = options)
      // forceMasked reports one null, so a length of 1 would report the column all-null and
      // take the kernel's all-null shortcut instead of the masked body this is here to drive.
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths.filter(_ > 1),
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, forceMasked = true,
        ctx = s"$ctx prefix, masked", options = options)
    }
  }

  test("a shared prefix serves add_months and a plain extraction over the same date") {
    // add_months writes the prefix's carry mask as its own scratch after the prefix is done
    // (emitChronoPrefix's javadoc says why that is sound). Ordering it *before* the three
    // extractions is what would catch it if it were not: they read the shared slots after it
    // has finished with them.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](
      new AddMonths(col, new LiteralSlot(0)), new Year(col), new Month(col), new DayOfMonth(col))
    for (offset <- Seq(0, 1, -13, VarkaChrono.MONTH_ARITH_MAX_MONTHS)) {
      checkMatrix(roots, 1, Array(offset), remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays,
        ctx = s"shared with add_months offset=$offset", options = sharing)
    }
  }

  test("the guard's removal reaches the shared prefix too") {
    // This PR predates task 51 and originally asserted the opposite: that the guard, sharing
    // the prefix across the three outputs below, still fired and declined the batch. Task 51
    // removed the guard from emitEra, which emitChronoPrefixOnce - the fragment-sharing entry
    // point this PR added - calls exactly like the unshared path does. That is why removal
    // needed no change here: there was never a second, sharing-specific copy of the guard to
    // find and delete. This test now exists to keep it that way - if a future change gives
    // the shared path its own inlined guard logic instead of routing through emitEra, this is
    // where that would first show up as a mistaken STATUS_CHRONO_RANGE. Task 52's guard lives
    // at a column-offset producer, never in the prefix, so this stays true after it too.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](new Year(col), new Month(col), new Quarter(col))
    val (kernel, loader) = load(emitMulti(roots, 1, 0, sharing))
    try {
      val arena = Arena.ofConfined()
      try {
        def status(length: Int, isNull: Int => Boolean, day: Int => Int): Int = {
          // `day` pins an out-of-range value at one lane which a caller may also null.
          val in = makeInputData(arena, length, isNull, day, poisonNulls = false)
          val outs = roots.map(_ => makeOutput(arena, length))
          kernel.run(
            Array(in.data.address()), Array(in.validity.address()), Array(in.nullCount),
            outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
            Array.empty[Int], length)
        }
        assert(status(64, _ => false, i => i * 97) === 0, "an in-range batch was declined")
        assert(status(64, _ => false, i => if (i == 3) VarkaChrono.NARROW_MAX_DAYS + 1
          else i * 97) === 0, "a day past the range was declined through the shared prefix")
        assert(status(17, _ => false, i => if (i == 16) VarkaChrono.NARROW_MIN_DAYS - 1
          else i * 97) === 0,
          "a day past the range was declined in the epilogue, where sharing happens today")
        assert(status(64, i => i == 3, i => if (i == 3) VarkaChrono.NARROW_MAX_DAYS + 1
          else i * 97) === 0, "an out-of-range value under a null row condemned the batch")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  // Task 52's runtime half: the range guard, moved from every calendar extraction to the one
  // producer the compiler cannot bound - a date_add/date_sub whose offset is a column.
  private val guardOff = VarkaEmitOptions.DEFAULTS.withGuardDayProducers(false)

  // Task 79's A/B arm: the arm context off, which is what every shape emitted before it.
  private val armOff = VarkaEmitOptions.DEFAULTS.withGuardUnderArm(false)

  test("a guarded producer under a CASE arm no longer condemns from the untaken arm") {
    // `CASE WHEN c < 1 THEN year(date_add(d, off)) ELSE year(d) END`, on the day-producer guard
    // task 52 built. Whether a lane is out of range and which arm it takes are set by two
    // different columns - `off` and `c` - so a lane index chooses one without deciding the
    // other. The first version of this test derived the arm from the lane's parity, which made
    // the index carry both, and the two epilogue cases inherited the wrong arm; the expected
    // status is also computed from `takesThen` now rather than written beside each index, so
    // the fixture and the assertion cannot disagree about a fact the fixture determines.
    val producer = new Year(new AddDays(new ColumnRef(0), new ColumnRef(1)))
    val root = new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(2), new LiteralSlot(0)),
      producer,
      new Year(new ColumnRef(0)))
    val (kernel, loader) = load(emitMulti(Seq(root), 3, 1))
    val (kernelOff, loaderOff) = load(emitMulti(Seq(root), 3, 1, armOff))
    try {
      val arena = Arena.ofConfined()
      try {
        def day(i: Int): Int = 100
        // Lane `at` is pushed one day past the range; every other lane stays a small shift.
        def off(at: Int)(i: Int): Int =
          if (i == at) VarkaChrono.NARROW_MAX_DAYS + 1 - day(i) else i % 5
        // The arm column, and the one function both the data and the expectation read.
        def takesThen(i: Int): Boolean = i % 3 == 0
        def cond(i: Int): Int = if (takesThen(i)) 0 else 7
        def status(k: VarkaFusedKernel, length: Int, at: Int): Int = {
          val d = makeInputData(arena, length, _ => false, day, poisonNulls = false)
          val o = makeInputData(arena, length, _ => false, off(at), poisonNulls = false)
          val c = makeInputData(arena, length, _ => false, cond, poisonNulls = false)
          val out = makeOutput(arena, length)
          k.run(
            Array(d.data.address(), o.data.address(), c.data.address()),
            Array(d.validityAddress(length), o.validityAddress(length),
              c.validityAddress(length)),
            Array(d.nullCount, o.nullCount, c.nullCount),
            Array(out._1.address()), Array(out._2.address()), Array(1), length)
        }
        /** What the guard must do for an out-of-range lane at `at`: fire iff that lane's own
         *  condition takes the arm the producer is in. Derived, not restated. */
        def expected(at: Int): Int =
          if (at >= 0 && takesThen(at)) VarkaFusedKernel.STATUS_CHRONO_RANGE else 0
        // A loop lane in each arm, and a lane only the epilogue covers in each arm - four
        // cases whose arm and whose body are now chosen independently.
        for ((length, at) <- Seq((64, 3), (64, 4), (17, 15), (17, 16))) {
          assert(status(kernel, length, at) === expected(at),
            s"length $length lane $at: takesThen=${takesThen(at)}")
          // Off, the arm is ignored and every out-of-range lane condemns: the cliff, and the
          // reference arm the A/B prices against.
          assert(status(kernelOff, length, at) === VarkaFusedKernel.STATUS_CHRONO_RANGE,
            s"length $length lane $at: with the context off this must decline either way")
        }
        // Nothing out of range: 0 under both settings.
        assert(status(kernel, 64, -1) === expected(-1))
        assert(status(kernelOff, 64, -1) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
      loaderOff.release()
    }
  }

  test("an unknown condition sends its lane to ELSE, and the guard there still fires") {
    // The polarity test. SQL's CASE routes an *unknown* condition to ELSE, so the else arm's
    // context is NOT known-true - known-false plus unknown - and never the known-false word.
    // Here the condition's own column is null on the out-of-range lane, so the condition is
    // unknown there; the guarded producer is in the ELSE arm and must condemn the batch. Had
    // the emitter used kF, that lane would fall outside the else context and the batch would
    // wrongly survive.
    val root = new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(2), new LiteralSlot(0)),
      new Year(new ColumnRef(0)),
      new Year(new AddDays(new ColumnRef(0), new ColumnRef(1))))
    val (kernel, loader) = load(emitMulti(Seq(root), 3, 1))
    try {
      val arena = Arena.ofConfined()
      try {
        val at = 3
        def day(i: Int): Int = 100
        def off(i: Int): Int = if (i == at) VarkaChrono.NARROW_MAX_DAYS + 1 - day(i) else i % 5
        val length = 64
        val d = makeInputData(arena, length, _ => false, day, poisonNulls = false)
        val o = makeInputData(arena, length, _ => false, off, poisonNulls = false)
        // The condition column is null exactly on the out-of-range lane: unknown -> ELSE.
        val c = makeInputData(arena, length, _ == at, _ => 0, poisonNulls = false)
        val out = makeOutput(arena, length)
        val status = kernel.run(
          Array(d.data.address(), o.data.address(), c.data.address()),
          Array(d.validityAddress(length), o.validityAddress(length), c.validityAddress(length)),
          Array(d.nullCount, o.nullCount, c.nullCount),
          Array(out._1.address()), Array(out._2.address()), Array(1), length)
        assert(status === VarkaFusedKernel.STATUS_CHRONO_RANGE,
          "an unknown condition takes ELSE, so the guard in the ELSE arm must condemn")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("a guard whose node's uses do not agree on one arm stays unqualified") {
    // The three shapes 3.3 refuses to narrow, each asserted to keep declining on a lane the
    // arm would have excused. These are the silent-wrong-answer cases: narrowing any of them
    // would stop a batch declining that must decline.
    val producer = new Year(new AddDays(new ColumnRef(0), new ColumnRef(1)))
    val cond = new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0))
    val shapes = Seq(
      // (a) used inside an arm and bare in a second output: CSE emits one node, and the bare
      // use needs the guard on every lane.
      "in an arm and bare" ->
        Seq(new IfElse(cond, producer, new Year(new ColumnRef(0))), producer),
      // (b) used under two different conditions: the second condition's word does not exist
      // where the node is first emitted, which is why the rule refuses the disjunction.
      "under two conditions" -> Seq(
        new IfElse(cond, producer, new Year(new ColumnRef(0))),
        new IfElse(new Compare(CompareOp.GT, new ColumnRef(0), new LiteralSlot(0)),
          producer, new Year(new ColumnRef(0)))),
      // (c) in condition position: computed on every lane the IfElse is, so unconditional.
      "in a condition" -> Seq(new IfElse(
        new Compare(CompareOp.LT, producer, new LiteralSlot(0)),
        new Year(new ColumnRef(0)),
        new Month(new ColumnRef(0)))))
    for ((name, roots) <- shapes) {
      val (kernel, loader) = load(emitMulti(roots, 2, 1))
      try {
        val arena = Arena.ofConfined()
        try {
          val at = 1
          val cut = 5000
          def day(i: Int): Int = if (i % 2 == 0) 100 else cut + 100
          def off(i: Int): Int =
            if (i == at) VarkaChrono.NARROW_MAX_DAYS + 1 - day(i) else i % 5
          val length = 64
          val d = makeInputData(arena, length, _ => false, day, poisonNulls = false)
          val o = makeInputData(arena, length, _ => false, off, poisonNulls = false)
          val outs = roots.indices.map(_ => makeOutput(arena, length))
          val status = kernel.run(
            Array(d.data.address(), o.data.address()),
            Array(d.validityAddress(length), o.validityAddress(length)),
            Array(d.nullCount, o.nullCount),
            outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
            Array(cut), length)
          assert(status === VarkaFusedKernel.STATUS_CHRONO_RANGE,
            s"$name: the guard must stay unqualified, and lane $at must decline the batch")
        } finally {
          arena.close()
        }
      } finally {
        loader.release()
      }
    }
  }

  test("a shape with no guarded node under an arm is byte-identical either way") {
    // The assertion that the context reached only the guards: every shape without a
    // batch-condemning node under an arm emits exactly what it did before task 79.
    val shapes = Seq(
      "plain year" -> (Seq(new Year(new ColumnRef(0))), 1, 0),
      "guarded producer, no arm" ->
        (Seq(new Year(new AddDays(new ColumnRef(0), new ColumnRef(1)))), 2, 0),
      "CASE over unguarded arms" -> (Seq(new IfElse(
        new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0)),
        new Year(new ColumnRef(0)), new Month(new ColumnRef(0)))), 1, 1))
    for ((name, (roots, inputs, literals)) <- shapes) {
      val on = emitMulti(roots, inputs, literals)._2
      val off = emitMulti(roots, inputs, literals, armOff)._2
      for (body <- Seq("loopDense0", "loopMasked0", "epilogueDense", "epilogueMasked")) {
        assert(VarkaEmitterTestSupport.codeSize(on, body) ===
          VarkaEmitterTestSupport.codeSize(off, body), s"$name: $body moved")
      }
    }
  }


  /** Runs a two-input kernel with one output, returning the batch status. */
  private def runKernel2(kernel: VarkaFusedKernel, a: Col, b: Col,
      out: (MemorySegment, MemorySegment), length: Int): Int =
    kernel.run(
      Array(a.data.address(), b.data.address()),
      Array(a.validityAddress(length), b.validityAddress(length)),
      Array(a.nullCount, b.nullCount),
      Array(out._1.address()), Array(out._2.address()), Array.empty[Int], length)

  // Task 63's int arithmetic. `checkOff` is the A/B arm the benchmark prices and the flag the
  // emitter reads to drop the sign test; it is never a correct setting for an ANSI query.
  private val checkOff = VarkaEmitOptions.DEFAULTS.withCheckIntOverflow(false)

  /** Values that put the sign test under load: both extremes, their neighbours, and zero. */
  private def extreme(col: Int, i: Int): Int = {
    val vs = Array(Int.MaxValue, Int.MinValue, Int.MaxValue - 1, Int.MinValue + 1, 0, 1, -1,
      100, -100, 7, Int.MaxValue / 2, Int.MinValue / 2)
    vs((i + col * 5) % vs.length)
  }

  /** The same spread, kept small enough that no op over two of them can overflow. */
  private def small(col: Int, i: Int): Int = {
    val vs = Array(0, 1, -1, 7, -7, 100, -100, 30000, -30000, 46340, -46340)
    vs((i + col * 3) % vs.length)
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
    val bodies = Seq("loopDense0", "loopMasked0", "epilogueDense", "epilogueMasked")
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
    assert(!tryAdd.contains("epilogueDense"), tryAdd.mkString(", "))
    assert(tryAdd.contains("loopMasked0") && tryAdd.contains("epilogueMasked"))
    val failAdd = methodNames(emitMulti(Seq[VarkaVectorIR](
      new IntArith(IntOp.ADD, Overflow.FAIL, a, b)), 2, 0))
    assert(failAdd.contains("loopDense0") && failAdd.contains("epilogueDense"))
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
        ("epilogueMasked", "epilogueDense"))) {
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

  test("make_date over a shifted year - the documented compile-time shape - " +
      "actually runs and matches the reference") {
    // `compileIntOperand`'s own doc says `make_date(y + 1, m, d)` fuses, and
    // `VarkaExpressionCompilerSuite` pins the IR that widening produces - but nothing had ever
    // emitted it, run it, or checked its value: the fuzzer's make_date arm only ever reads a
    // date's own fields back, never arithmetic over one of them. This is that gap closed.
    //
    // The shift is +1, over triples chosen to stay valid after it: MAKE_DATE_MAX_YEAR is
    // excluded, since a +1 there is the one shift this file's plain triples would push out of
    // range, and every Feb 29 is excluded too, since a leap day is invalid the moment +1 lands
    // it on a non-leap year (2024-02-29 -> 2025-02-29, which does not exist) - both are real
    // declines, correctly, and belong to task 42's own decline test rather than this one, which
    // is about the value on the path that does compute.
    val safeTriples: Array[(Int, Int, Int)] = makeDateValid.filterNot { case (y, m, d) =>
      y == VarkaChrono.MAKE_DATE_MAX_YEAR || (m == 2 && d == 29)
    }
    val y = new ColumnRef(0)
    val m = new ColumnRef(1)
    val d = new ColumnRef(2)
    val shiftedYear = new IntArith(IntOp.ADD, Overflow.WRAP, y, new LiteralSlot(0))
    for (ansi <- Seq(false, true)) {
      checkMatrix(Seq(new MakeDate(shiftedYear, m, d, ansi)), 3, Array(1),
        Seq(1, 13, 17, 64, 1000), combos(3), data = tripleData(safeTriples),
        ctx = s"make_date(y + 1, m, d), ansi=$ansi")
    }
  }

  test("a column-offset producer under a calendar node declines the batch whose " +
      "result leaves the range - in a loop lane, in an epilogue lane, and not under a null") {
    val add = new Year(new AddDays(new ColumnRef(0), new ColumnRef(1)))
    val sub = new Month(new SubDays(new ColumnRef(0), new ColumnRef(1)))
    for ((root, past, mirrored) <- Seq(
        (add, VarkaChrono.NARROW_MAX_DAYS + 1, false),
        (sub, VarkaChrono.NARROW_MIN_DAYS - 1, true))) {
      val (kernel, loader) = load(emitMulti(Seq(root), 2, 0))
      val (kernelOff, loaderOff) = load(emitMulti(Seq(root), 2, 0, guardOff))
      try {
        val arena = Arena.ofConfined()
        try {
          // The offset that lands lane `at` exactly one day past the range; every other lane
          // stays a small shift. `sub` subtracts, so its offset is the negated distance.
          def day(i: Int): Int = i * 97
          def off(at: Int)(i: Int): Int =
            if (i == at) { if (mirrored) day(i) - past else past - day(i) } else i % 5
          def status(k: VarkaFusedKernel, length: Int, at: Int,
              nullDate: Int => Boolean, nullOff: Int => Boolean): Int = {
            // `off(at)` is chosen so `d + off` lands just past the range at lane `at`, and
            // the cases below null that lane; poison would replace the sum being tested.
            val d = makeInputData(arena, length, nullDate, day, poisonNulls = false)
            val o = makeInputData(arena, length, nullOff, off(at), poisonNulls = false)
            runKernel2(k, d, o, makeOutput(arena, length), length)
          }
          val none = (_: Int) => false
          // In range: computed, under both settings.
          assert(status(kernel, 64, -1, none, none) === 0)
          assert(status(kernelOff, 64, -1, none, none) === 0)
          // A loop lane past the range (dense body: no nulls anywhere).
          assert(status(kernel, 64, 3, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
          // The same lane in the masked body, with an unrelated null elsewhere.
          assert(status(kernel, 64, 3, _ == 40, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
          // A lane only the epilogue covers, whatever the host's lane count.
          assert(status(kernel, 17, 16, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
          assert(status(kernel, 17, 16, _ == 2, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
          // The out-of-range lane under a null offset, then under a null date: the row is
          // null, its data lanes are undefined, and the batch must not be condemned.
          assert(status(kernel, 64, 3, none, _ == 3) === 0)
          assert(status(kernel, 64, 3, _ == 3, none) === 0)
          assert(status(kernel, 17, 16, none, _ == 16) === 0)
          // The reference variant computes every one of them - wrongly past the range, which
          // is exactly what the metric-only differential asserts against.
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
  }

  test("the guard is emitted only where a calendar node reads a column-offset " +
      "producer, and adds bytes nowhere else") {
    val producer = new AddDays(new ColumnRef(0), new ColumnRef(1))
    val bodies = Seq("loopDense0", "loopMasked0", "epilogueDense", "epilogueMasked")
    def sizes(root: VarkaVectorIR, numInputs: Int, options: VarkaEmitOptions): Seq[Int] = {
      val bytes = emitMulti(Seq(root), numInputs, 0, options)._2
      bodies.map(VarkaEmitterTestSupport.codeSize(bytes, _))
    }
    // A producer with no calendar consumer: byte-identical under both settings, so
    // `date_add(d, off)` on its own pays nothing for a guard it does not need.
    assert(sizes(producer, 2, VarkaEmitOptions.DEFAULTS) === sizes(producer, 2, guardOff))
    assert(sizes(new DateDiff(producer, new ColumnRef(0)), 2, VarkaEmitOptions.DEFAULTS) ===
      sizes(new DateDiff(producer, new ColumnRef(0)), 2, guardOff))
    // A calendar node over a bare column, and over a literal-offset producer: the compiler
    // bounds both, and the emitter plans nothing.
    assert(sizes(new Year(new ColumnRef(0)), 1, VarkaEmitOptions.DEFAULTS) ===
      sizes(new Year(new ColumnRef(0)), 1, guardOff))
    val literal = new Year(new AddDays(new ColumnRef(0), new LiteralSlot(0)))
    assert(emitMulti(Seq(literal), 1, 1)._2.length ===
      emitMulti(Seq(literal), 1, 1, guardOff)._2.length)
    // The guarded shape: every body grows by the guard, and only the guarded shape does.
    val guarded = sizes(new Year(producer), 2, VarkaEmitOptions.DEFAULTS)
    val unguarded = sizes(new Year(producer), 2, guardOff)
    for ((body, (on, off)) <- bodies.zip(guarded.zip(unguarded))) {
      assert(on > off, s"$body: expected the guard's bytes, got $on vs $off")
    }
  }

  test("in-range column offsets under calendar nodes match the reference evaluator " +
      "under both settings, and CSE off repeats the guard without breaking it") {
    val producer = new AddDays(new ColumnRef(0), new ColumnRef(1))
    val roots = Seq[VarkaVectorIR](new Year(producer), new Month(producer),
      new DayOfMonth(new SubDays(new ColumnRef(0), new ColumnRef(1))))
    // Days stay near the epoch and offsets small, so no lane leaves the range and the status
    // must read zero in every case the matrix drives - the guard's silence is asserted too.
    val data = (c: Int, i: Int) => if (c == 0) (i * 97) % 40000 - 20000 else i % 23 - 11
    for (options <- Seq(VarkaEmitOptions.DEFAULTS, guardOff,
        VarkaEmitOptions.DEFAULTS.withCse(false))) {
      checkMatrix(roots, 2, Array.emptyIntArray, Seq(1, 17, 64, 65, 1000), combos(2),
        data = data, ctx = s"task 52 ${options.canonical()}", options = options)
    }
    // With CSE off the producer is re-emitted per reader, guard included; an out-of-range
    // lane is still caught.
    val (kernel, loader) = load(emitMulti(roots, 2, 0, VarkaEmitOptions.DEFAULTS.withCse(false)))
    try {
      val arena = Arena.ofConfined()
      try {
        val d = makeInputData(arena, 64, _ => false, i => i * 97)
        val o = makeInputData(arena, 64, _ => false,
          i => if (i == 5) VarkaChrono.NARROW_MAX_DAYS + 1 - 5 * 97 else 1)
        val outs = roots.map(_ => makeOutput(arena, 64))
        val status = kernel.run(Array(d.data.address(), o.data.address()), Array(0L, 0L),
          Array(0, 0), outs.map(_._1.address()).toArray, outs.map(_._2.address()).toArray,
          Array.empty[Int], 64)
        assert(status === VarkaFusedKernel.STATUS_CHRONO_RANGE)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  // Task 60's runtime half: the same range-guard block (now `emitRangeGuard`, generalized from
  // task 52's `emitProducerGuard`) on AddMonths' own month count, wherever it sits - the guard
  // protects the node's own magic-multiply arithmetic, not a further calendar consumer's.

  test("a column month count declines the batch whose count leaves the range - in a " +
      "loop lane, in an epilogue lane, and not under a null; the bounds themselves compute") {
    val root = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    val (kernel, loader) = load(emitMulti(Seq(root), 2, 0))
    val (kernelOff, loaderOff) = load(emitMulti(Seq(root), 2, 0, guardOff))
    try {
      val arena = Arena.ofConfined()
      try {
        // Days stay well inside the narrowed range regardless of the count under test, so a
        // failure here is the count guard's, not the unrelated day decomposition's.
        def day(i: Int): Int = (i * 97) % 40000 - 20000
        def count(at: Int, value: Int)(i: Int): Int = if (i == at) value else i % 11 - 5
        def status(k: VarkaFusedKernel, length: Int, at: Int, value: Int,
            nullDate: Int => Boolean, nullCount: Int => Boolean): Int = {
          // Same as task 52's: the violating month count is pinned at a lane these cases null.
          val d = makeInputData(arena, length, nullDate, day, poisonNulls = false)
          val m = makeInputData(arena, length, nullCount, count(at, value), poisonNulls = false)
          runKernel2(k, d, m, makeOutput(arena, length), length)
        }
        val none = (_: Int) => false
        val hi = VarkaChrono.MONTH_ARITH_MAX_MONTHS
        val lo = VarkaChrono.MONTH_ARITH_MIN_MONTHS
        // In range: computed, under both settings.
        assert(status(kernel, 64, -1, 0, none, none) === 0)
        assert(status(kernelOff, 64, -1, 0, none, none) === 0)
        // Both bounds themselves compute - the guard is `< lo || > hi`, not `<= lo || >= hi`.
        assert(status(kernel, 64, 3, hi, none, none) === 0)
        assert(status(kernel, 64, 3, lo, none, none) === 0)
        // One past each bound, in a loop lane (dense body: no nulls anywhere).
        assert(status(kernel, 64, 3, hi + 1, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 64, 3, lo - 1, none, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // The same lane in the masked body, with an unrelated null elsewhere.
        assert(status(kernel, 64, 3, hi + 1, _ == 40, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // A lane only the epilogue covers, whatever the host's lane count.
        assert(status(kernel, 17, 16, hi + 1, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernel, 17, 16, lo - 1, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // A live violation in the masked epilogue - the one body where the guard mask is ANDed
        // with both the node's word and the epilogue mask, and the body whose ordering produced
        // this task's VerifyError. The other epilogue cases above are null-free, so the dense
        // driver runs them and only epilogueDense is exercised; the null here is on a lane other
        // than the violating one, so the violation stays live and the guard must still see it.
        assert(status(kernel, 17, 16, hi + 1, _ == 2, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // The out-of-range lane under a null count, then under a null date: the row is null,
        // its data lanes are undefined, and the batch must not be condemned.
        assert(status(kernel, 64, 3, hi + 1, none, _ == 3) === 0)
        assert(status(kernel, 64, 3, hi + 1, _ == 3, none) === 0)
        assert(status(kernel, 17, 16, hi + 1, none, _ == 16) === 0)
        // guardDayProducers does not reach this guard: the count check is the node's own
        // correctness (its magic multiply is exact only over the guarded range) and the
        // compiler's dayRange bounds a column count on the strength of it, so the option-off
        // variant declines exactly as the default does. Only task 52's day-producer guard,
        // which insures a consumer rather than the producer itself, is a reference variant.
        assert(status(kernelOff, 64, 3, hi + 1, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernelOff, 17, 16, lo - 1, none, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(kernelOff, 64, -1, 0, none, none) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
      loaderOff.release()
    }
  }

  test("a literal date with a column count guards the same, on the branch that has " +
      "no word of its own") {
    // Every other test builds AddMonths(ColumnRef, ColumnRef), which owns its validity word.
    // A literal date gives the node no word of its own: planWordRef aliases the count input's,
    // the new emitAndWord is skipped, and emitRangeGuard reads an aliased input slot instead.
    // That is a different path through the same guard, and nothing else covers it.
    val root = new AddMonths(new LiteralSlot(0), new ColumnRef(0))
    val (kernel, loader) = load(emitMulti(Seq(root), 1, 1, VarkaEmitOptions.DEFAULTS))
    try {
      val arena = Arena.ofConfined()
      try {
        val hi = VarkaChrono.MONTH_ARITH_MAX_MONTHS
        // The date rides the literal table rather than an input column, so the kernel is run
        // directly: runKernel passes no literals.
        val dateLiteral = 19000
        def status(length: Int, at: Int, value: Int, nullCount: Int => Boolean): Int = {
          val m = makeInputData(arena, length, nullCount,
            i => if (i == at) value else i % 7 - 3, poisonNulls = false)
          val out = makeOutput(arena, length)
          kernel.run(
            Array(m.data.address()), Array(m.validity.address()), Array(m.nullCount),
            Array(out._1.address()), Array(out._2.address()), Array(dateLiteral), length)
        }
        val none = (_: Int) => false
        assert(status(64, -1, 0, none) === 0)
        assert(status(64, 3, hi + 1, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        assert(status(17, 16, hi + 1, none) === VarkaFusedKernel.STATUS_CHRONO_RANGE)
        // Null count on the violating lane: undefined data, and the batch stands.
        assert(status(64, 3, hi + 1, _ == 3) === 0)
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("in-range column month counts match the reference evaluator under both " +
      "option values, with and without a further calendar reader") {
    val root = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    val roots = Seq[VarkaVectorIR](root, new Year(root))
    // The count cycles across the whole guarded bound, both ends included; the day stays near
    // the epoch so add_months' own recompose never leaves the narrowed range even at the
    // bound's most extreme shift.
    val counts = Seq(VarkaChrono.MONTH_ARITH_MIN_MONTHS, VarkaChrono.MONTH_ARITH_MAX_MONTHS,
      0, 1, -1, 12, -12, 100, -100)
    val data = (c: Int, i: Int) =>
      if (c == 0) (i * 9973) % 40000 - 20000 else counts(i % counts.length)
    for (options <- Seq(VarkaEmitOptions.DEFAULTS, guardOff,
        VarkaEmitOptions.DEFAULTS.withCse(false))) {
      checkMatrix(roots, 2, Array.emptyIntArray, Seq(1, 17, 64, 65, 1000), combos(2),
        data = data, ctx = s"task 60 ${options.canonical()}", options = options)
    }
  }

  test("the guard is emitted only for a column-driven month count, and the literal " +
      "form's bytes do not move") {
    val literal = new AddMonths(new ColumnRef(0), new LiteralSlot(0))
    val column = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    val bodies = Seq("loopDense0", "loopMasked0", "epilogueDense", "epilogueMasked")
    def sizes(root: VarkaVectorIR, numInputs: Int, lits: Int, options: VarkaEmitOptions)
        : Seq[Int] = {
      val bytes = emitMulti(Seq(root), numInputs, lits, options)._2
      bodies.map(VarkaEmitterTestSupport.codeSize(bytes, _))
    }
    // The literal form: byte-identical under both settings, and identical to its shape before
    // this task (asserted below by the register itself).
    assert(sizes(literal, 1, 1, VarkaEmitOptions.DEFAULTS) === sizes(literal, 1, 1, guardOff))
    // The control for the count guard's bytes is the literal form, not the option: the count
    // guard is self-guarding and unconditional, so the option-off variant carries it too and
    // the two column runs are byte-identical. Only the day-producer guard answers to the flag.
    val guarded = sizes(column, 2, 0, VarkaEmitOptions.DEFAULTS)
    assert(guarded === sizes(column, 2, 0, guardOff),
      "guardDayProducers must not reach the self-guarding count check")
    // Every body of the column form carries the guard the literal form does not need.
    for ((body, (col, lit)) <- bodies.zip(guarded.zip(sizes(literal, 1, 1,
        VarkaEmitOptions.DEFAULTS)))) {
      assert(col > lit, s"$body: expected the guard's bytes, got $col vs $lit")
    }
  }

  test("the register PLAN_TASK_60.md 3.3 predicted - the guard costs two IntVector " +
      "compares on top of a column's load replacing a literal's broadcast") {
    val literal = new AddMonths(new ColumnRef(0), new LiteralSlot(0))
    val column = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    val literalOps = laneOps(emitMulti(Seq(literal), 1, 1)._2, "loopDense0")
    val guardedOps = laneOps(emitMulti(Seq(column), 2, 0)._2, "loopDense0")
    // The count guard is unconditional (it is the node's own correctness, and the compiler's
    // compile-time bound rests on it), so the option-off run is the same 114 rather than the
    // 112 an option-gated guard would give. The register's prediction is unaffected: it is
    // about the two compares the guard adds to the literal form's 112, which still holds.
    val optionOffOps = laneOps(emitMulti(Seq(column), 2, 0, guardOff)._2, "loopDense0")
    assert((literalOps, optionOffOps, guardedOps) === ((112, 114, 114)),
      s"the register: literal=$literalOps optionOff=$optionOffOps guarded=$guardedOps")
  }

  test("the shared prefix survives two calendar outputs in one loop method") {
    // Until B2 this ran under a widened GROUP_BUDGET, because the shipped grouping put every
    // calendar output in its own loop method and only the epilogue ever held two; it was the
    // shape B2 would ship, measured for correctness before it was measured for throughput.
    // B2 shipped it, so the defaults are that shape and the test runs under them.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](
      new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    assert(methodNames(emitMulti(roots, 1, 0, sharing)).count(_.startsWith("loopDense")) === 1,
      "the defaults did not put the four outputs in one loop method")
    checkMatrix(roots, 1, Array.empty[Int], remainderLengths ++ Seq(64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = "one loop method",
      options = sharing)
  }

  test("two calendar outputs over different dates share nothing") {
    // The fragment is keyed on the child, so year(d1) and year(d2) must each emit their own
    // prefix. A key that collapsed to the node type would silently answer d2 from d1's
    // decomposition - right-looking numbers, wrong rows, and no status to say so.
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)), new Year(new ColumnRef(1)))
    // Not the whole class: emitMulti gives every emission a fresh name, so the constant pool
    // differs whatever the body does. The epilogue is where two outputs meet, so its size is
    // the thing that would have moved had the two prefixes collapsed into one.
    assert(epilogueSize(roots, 2, sharing) === epilogueSize(roots, 2, unshared),
      "the epilogue moved for two outputs that have nothing to share")
    // And clause 2 does not put them in one loop method: the second reuses no prefix.
    assert(methodNames(emitMulti(roots, 2, 0, sharing)).count(_.startsWith("loopDense")) === 2,
      "two dates with nothing to share landed in one loop method")
    checkMatrix(roots, 2, Array.empty[Int], remainderLengths,
      // The second date is the first walked from a different index rather than shifted by a
      // constant: adding to a day that is already at the range's edge would push it out and
      // make the kernel decline, which is a guard result, not a sharing one.
      nullPatterns.map(p => Seq(p._2, p._2)), data = (c, i) => calendarDays(c, i + c * 3),
      ctx = "two dates", options = sharing)
  }

  test("whole-node reuse groups what a wider budget would, and nothing else") {
    // Clause 2 lets an output join a group past the budget when joining lets it skip work the
    // group already does. B2 wrote that for a civil-from-days prefix; `shareWholeNodes`
    // generalises it to any node the group holds, which is the same argument - a reused prefix
    // is reused nodes. The point is that it needs no wider budget: `groupBudget` bounds the
    // method, and it is what keeps compile time in hand (C1 refuses a loop method past about
    // 1900 bytes), so buying the CSE by raising it would loosen the wrong bound.
    //
    // The claim asserted here is method-for-method identity, in both directions: at the
    // shipped budget the rule emits exactly what a budget of 24 emits for the shapes that
    // share nodes, and exactly what the shipped budget emits for every shape that does not.
    // Method-level rather than whole-class, because `emitMulti` gives each class a fresh name
    // and the name is in the bytes.
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    def over(base: VarkaVectorIR, depth: Int, slotBase: Int): VarkaVectorIR = {
      var node = base
      for (level <- 0 until depth) {
        node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
        else new SubDays(node, new LiteralSlot(slotBase + level))
      }
      node
    }
    val shared8 = chain(8)
    val da1 = new AddDays(c0, new LiteralSlot(0))
    val rule = VarkaEmitOptions.DEFAULTS.withShareWholeNodes(true)
    val wider = VarkaEmitOptions.DEFAULTS.withGroupBudget(24)
    val shipped = VarkaEmitOptions.DEFAULTS.withShareWholeNodes(false)

    // Shapes that share nodes but no prefix, and whose merged weight straddles the budget:
    // the rule must emit what the wider budget emits.
    val sharing = Seq[(String, Seq[VarkaVectorIR], Int, Int)](
      ("task 17's two outputs over a shared chain",
        Seq(over(shared8, 6, 8), over(shared8, 6, 14)), 1, 20),
      ("three outputs over a shared chain",
        Seq(over(shared8, 4, 8), over(shared8, 4, 12), over(shared8, 4, 16)), 1, 20))
    // The loop methods of one emitted class, as (name -> code size) - what a grouping change
    // moves and a class name does not.
    def layout(bytes: (String, Array[Byte])): Seq[(String, Int)] =
      methodNames(bytes).filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked"))
        .sorted.map(m => m -> VarkaEmitterTestSupport.codeSize(bytes._2, m))

    for ((name, roots, inputs, lits) <- sharing) {
      assert(layout(emitMulti(roots, inputs, lits, rule))
        === layout(emitMulti(roots, inputs, lits, wider)),
        s"$name: the rule at the shipped budget should emit what a budget of 24 emits")
      assert(layout(emitMulti(roots, inputs, lits, rule))
        !== layout(emitMulti(roots, inputs, lits, shipped)),
        s"$name: the rule should change this shape, or the corpus has stopped exercising it")
    }

    // And everything else is untouched: nothing shared, sharing already served by clause 2,
    // sharing that fits the budget anyway, and outputs too heavy to join at any bound.
    val untouched = Seq[(String, Seq[VarkaVectorIR], Int, Int)](
      ("two plain chains over different columns", Seq(chain(6), over(c1, 6, 6)), 2, 12),
      ("year and month over one date, clause 2's own case",
        Seq[VarkaVectorIR](new Year(c0), new Month(c0)), 1, 0),
      ("year over two dates, nothing to share",
        Seq[VarkaVectorIR](new Year(c0), new Year(c1)), 2, 0),
      ("add_months over two dates, heavier than any bound",
        Seq[VarkaVectorIR](new AddMonths(c0, new LiteralSlot(0)),
          new AddMonths(c1, new LiteralSlot(0))), 2, 1),
      ("date_add and datediff over it, already one group", Seq(da1, new DateDiff(da1, c1)), 2, 1))
    for ((name, roots, inputs, lits) <- untouched) {
      assert(layout(emitMulti(roots, inputs, lits, rule))
        === layout(emitMulti(roots, inputs, lits, shipped)),
        s"$name: the rule reached a shape it has no reuse to act on")
    }
  }

  test("a budget change reaches only the shapes whose grouping it decides") {
    // The guard section 2.35 believed already existed and did not. B2's byte-identity test
    // above compares `shareChronoPrefix` off against on at ONE budget; nothing asserted that
    // moving the budget itself touches only what it should. Task 71 measured the cost of a
    // default change by moving it and running the suites - one assertion failed, at 64, and it
    // was the one that spells out "1 + 38 > 16" - which is the right cost and the wrong way to
    // learn it. This is the assertion.
    //
    // The corpus is shapes whose grouping no budget in the ladder can change: one output is
    // always one group whatever the budget, and outputs whose combined weight exceeds every
    // rung stay apart at all of them. A shape that legitimately regroups - two small disjoint
    // outputs, which a wider budget should merge - is deliberately not here, because it is
    // what the budget is for.
    val col = new ColumnRef(0)
    val corpus = Seq[(String, Seq[VarkaVectorIR], Int, Int)](
      ("one depth-8 chain, one output", Seq(chain(8)), 1, 8),
      // 38 ops against the shipped 16: a single output wider than the budget gets its own
      // group untouched, and stays one method when the budget grows past it.
      ("one calendar output, wider than the shipped budget", Seq[VarkaVectorIR](new Year(col)),
        1, 0),
      // add_months weighs 112 - wider than every rung, so it is its own group at all of them.
      ("one add_months, wider than every rung",
        Seq[VarkaVectorIR](new AddMonths(col, new LiteralSlot(0))), 1, 1),
      // Two calendar outputs over different dates: 38 + 38 against a top rung of 64, and no
      // prefix to reuse, so clause 1 splits them and clause 2 never opens.
      ("two calendar outputs over different dates",
        Seq[VarkaVectorIR](new Year(col), new Year(new ColumnRef(1))), 2, 0))
    val rungs = Seq(16, 24, 32, 48, 64)
    for ((name, roots, inputs, lits) <- corpus) {
      val emitted = rungs.map { budget =>
        budget -> emitMulti(roots, inputs, lits, VarkaEmitOptions.DEFAULTS.withGroupBudget(budget))
      }
      val (baseBudget, base) = emitted.head
      val loops = methodNames(base)
        .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked")).sorted
      for ((budget, bytes) <- emitted.tail) {
        assert(methodNames(bytes)
          .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked")).sorted === loops,
          s"$name: budget $budget grouped differently from $baseBudget, and this shape's " +
            "grouping is not the budget's to decide")
        for (method <- loops) {
          assert(VarkaEmitterTestSupport.codeSize(bytes._2, method)
            === VarkaEmitterTestSupport.codeSize(base._2, method),
            s"$name: $method changed size between budgets $baseBudget and $budget")
        }
      }
    }
  }

  test("with no prefix to reuse, sharing changes no loop method - the guard that " +
      "clause 2 admits fragment reuse and nothing else") {
    // Before B2 this test asserted every calendar loop method byte for byte unchanged under
    // sharing, which was the proof that no committed number could move; its own comment said
    // B2 would fail it and that the parity file then needs regenerating, which is what
    // happened. What B2 promises instead is that clause 2 reaches nothing but fragment reuse:
    // for shapes with no calendar prefix - the task-17 pair, a deep chain, the CASE WHEN and
    // greatest cases the parity file names, the mod-7 family - every loop method is byte for
    // byte identical with sharing on and off, method names and sizes alike. Asserted by
    // construction, so it holds whichever way task 17's split-versus-merged rows read.
    val col = new ColumnRef(0)
    def chainOver(base: VarkaVectorIR, depth: Int, slotBase: Int): VarkaVectorIR = {
      var node = base
      for (level <- 0 until depth) {
        node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
        else new SubDays(node, new LiteralSlot(slotBase + level))
      }
      node
    }
    val shared8 = chain(8)
    val corpus = Seq[(String, Seq[VarkaVectorIR], Int, Int)](
      ("task 17's two outputs over a shared chain",
        Seq(chainOver(shared8, 6, 8), chainOver(shared8, 6, 14)), 1, 20),
      ("depth-8 chain", Seq(chain(8)), 1, 8),
      ("CASE WHEN", Seq(new IfElse(new Compare(CompareOp.LT, col, new LiteralSlot(0)),
        new AddDays(col, new LiteralSlot(1)), new SubDays(col, new LiteralSlot(1)))), 1, 2),
      ("greatest and least", Seq(new Greatest(col, new ColumnRef(1)),
        new Least(col, new ColumnRef(1))), 2, 0),
      ("dayofweek and weekday", Seq(new DayOfWeek(col), new WeekDay(col)), 1, 0),
      ("next_day", Seq(new NextDay(col, new LiteralSlot(0))), 1, 1),
      ("datediff", Seq(new DateDiff(col, new ColumnRef(1))), 2, 0))
    for ((name, roots, inputs, lits) <- corpus) {
      val plain = emitMulti(roots, inputs, lits, unshared)
      val withSharing = emitMulti(roots, inputs, lits, sharing)
      val loops = methodNames(plain)
        .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked")).sorted
      assert(methodNames(withSharing)
        .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked")).sorted === loops,
        s"$name: sharing changed the loop-method layout of a shape with no prefix to share")
      for (method <- loops) {
        assert(VarkaEmitterTestSupport.codeSize(withSharing._2, method)
          === VarkaEmitterTestSupport.codeSize(plain._2, method),
          s"$name: $method changed size under sharing, so clause 2 reached a shape with no " +
            "prefix to reuse")
      }
    }
  }

  test("the numerator costs what PLAN_TASK_53.md 3.4 registered, per tail") {
    // Registered before the work and asserted after, off the class file rather than reasoned
    // from the helpers. A miss here is a bug in the lowering, not a surprise about it: the
    // deltas are arithmetic on ops that either are or are not emitted.
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, lits: Int, neri: Boolean): Int =
      laneOps(emitMulti(Seq(root), 1, lits,
        VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(neri))._2, "loopDense0")
    for ((name, root, lits, delta) <- Seq(
        ("year", new Year(col), 0, 0),
        ("month", new Month(col), 0, -2),
        ("dayofmonth", new DayOfMonth(col), 0, -4),
        ("quarter", new Quarter(col), 0, -2),
        ("last_day", new LastDay(col), 0, -3),
        ("add_months", new AddMonths(col, new LiteralSlot(0)), 1, -4))) {
      assert(ops(root, lits, neri = true) - ops(root, lits, neri = false) === delta,
        s"$name moved by ${ops(root, lits, neri = true) - ops(root, lits, neri = false)} ops, " +
          s"not $delta - PLAN_TASK_53.md 3.4 needs updating with the reason")
    }
    // `year` is the control: it reads neither axis, so if it ever moves the numerator has
    // leaked into a tail that has no business seeing it.
    assert(ops(new Year(col), 0, neri = true) === ops(new Year(col), 0, neri = false),
      "the year tail must not change when only the month axis does")
  }

  test("the Julian map costs what PLAN_TASK_54.md 3.3 registered, per node") {
    // Off the class file, like task 53's: the prefix loses the century fold and the year-step
    // underflow correction and gains the map and a second carry, and the year assembly loses
    // the `100 * century` multiply-add. Registered before the run; a miss is a bug in the
    // lowering or an error in the registered accounting, and either way the plan says which.
    val col = new ColumnRef(0)
    def ops(root: VarkaVectorIR, lits: Int, julian: Boolean): Int =
      laneOps(emitMulti(Seq(root), 1, lits,
        VarkaEmitOptions.DEFAULTS.withJulianMap(julian))._2, "loopDense0")
    for ((name, root, lits, delta) <- Seq(
        ("year", new Year(col), 0, -5),
        ("month", new Month(col), 0, -3),
        ("dayofmonth", new DayOfMonth(col), 0, -3),
        ("quarter", new Quarter(col), 0, -3),
        ("dayofyear", new DayOfYear(col), 0, -5),
        ("last_day", new LastDay(col), 0, -5),
        ("add_months", new AddMonths(col, new LiteralSlot(0)), 1, -5))) {
      val moved = ops(root, lits, julian = true) - ops(root, lits, julian = false)
      assert(moved === delta,
        s"$name moved by $moved ops, not $delta - PLAN_TASK_54.md 3.3 needs updating with " +
          "the reason")
    }
  }

  test("every calendar weight is the prefix plus the tail the emitter emits") {
    // The register PLAN_TASK_32.md 10.3 asked for, asserted off the class file the way the
    // task 53 and 54 registers are. Each calendar node alone emits its prefix plus its tail,
    // and beside month(d) in one loop method it adds exactly its tail - which is the
    // arithmetic clause 2 of groupOutputs sums against FUSED_CEILING. A lowering change that
    // moves a count fails here and names the constant to recount, rather than leaving a
    // weight to drift the way CHRONO_WEIGHT drifted from 50 to 40 without anything noticing.
    val col = new ColumnRef(0)
    def ops(roots: Seq[VarkaVectorIR], inputs: Int = 1, lits: Int = 0,
        options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): Int =
      laneOps(emitMulti(roots, inputs, lits, options)._2, "loopDense0")
    val prefix = VarkaLoopEmitter.CHRONO_PREFIX_WEIGHT
    // A prefix no tail in the group reads the month out of elides the month step (task 48).
    val prefixNoMonth = prefix - monthStepOps(VarkaEmitOptions.DEFAULTS)
    val month = new Month(col)
    val monthAlone = ops(Seq(month))
    assert(monthAlone === prefix + 4, "month(d), the partner every tail is measured beside")
    // Wide enough that clause 1 alone puts month(d) and the node in one method, so the pair
    // measures the fragment's arithmetic whether or not clause 2 exists yet.
    val wide = VarkaEmitOptions.DEFAULTS.withGroupBudget(400)
    // (name, node, its tail, whether its own prefix keeps the month step, literals, inputs)
    val register = Seq(
      ("year", new Year(col), 5, false, 0, 1),
      ("dayofmonth", new DayOfMonth(col), 5, true, 0, 1),
      ("quarter", new Quarter(col), 7, true, 0, 1),
      ("dayofyear", new DayOfYear(col), VarkaLoopEmitter.DAY_OF_YEAR_TAIL_WEIGHT, false, 0, 1),
      ("last_day", new LastDay(col), VarkaLoopEmitter.LAST_DAY_TAIL_WEIGHT, true, 0, 1),
      ("add_months", new AddMonths(col, new LiteralSlot(0)),
        VarkaLoopEmitter.ADD_MONTHS_TAIL_WEIGHT, true, 1, 1),
      ("trunc YEAR", new TruncDate(col, TruncLevel.YEAR),
        VarkaLoopEmitter.TRUNC_YEAR_TAIL_WEIGHT, false, 0, 1),
      ("trunc MONTH", new TruncDate(col, TruncLevel.MONTH),
        VarkaLoopEmitter.TRUNC_MONTH_TAIL_WEIGHT, true, 0, 1),
      ("trunc QUARTER", new TruncDate(col, TruncLevel.QUARTER),
        VarkaLoopEmitter.TRUNC_QUARTER_TAIL_WEIGHT, true, 0, 1),
      ("trunc dynamic", new TruncDateDynamic(col, new ColumnRef(1)),
        VarkaLoopEmitter.TRUNC_DYNAMIC_TAIL_WEIGHT, true, 0, 2))
    for ((name, node, tail, readsMonth, lits, inputs) <- register) {
      val alone = ops(Seq(node), inputs, lits)
      val own = if (readsMonth) prefix else prefixNoMonth
      assert(alone === own + tail,
        s"$name alone emits $alone lane ops, not prefix $own + tail $tail - recount the constant")
      val paired = ops(Seq(month, node), inputs, lits, wide)
      assert(paired === monthAlone + tail,
        s"month(d) beside $name emits $paired lane ops, not month's $monthAlone + tail $tail")
    }
    // weekofyear decomposes the Thursday-shifted day, so its prefix is keyed on ThursdayOf(d)
    // and shares nothing with month(d)'s; the shift's own ops are the ThursdayOf node's weight.
    val shift = new ThursdayOf(col)
    assert(ops(Seq(new WeekOfYear(shift))) ===
      ops(Seq(shift)) + prefixNoMonth + VarkaLoopEmitter.WEEK_OF_YEAR_TAIL_WEIGHT)
    // The four fields share one tail constant, at the widest of the four.
    assert(VarkaLoopEmitter.CHRONO_FIELD_TAIL_WEIGHT === 7)
  }

  test("a year-only body computes no month, and the switch says so") {
    assert(VarkaEmitOptions.DEFAULTS.elideChronoMonth(),
      "the elision is no longer the default - the case for it is in PLAN_TASK_48.md section " +
        "3.3, so say why here if it was deliberately turned off")
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    // Both axes, because task 53 changed what the step costs without changing whether it is
    // elided: four ops on the 0-based month, two on the numerator. The elision has to hold on
    // each, and asserting it on only the shipped one would let the reference variant rot.
    for (axis <- Seq(VarkaEmitOptions.DEFAULTS,
        VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(false))) {
      val elided = emitMulti(roots, 1, 0, axis)._2
      val kept = emitMulti(roots, 1, 0, axis.withElideChronoMonth(false))._2
      // Every body role, because every one of them runs the prefix: the two loop methods and
      // the two epilogues each hold this single year and nothing that reads a month.
      for (body <- Seq("loopDense0", "loopMasked0", "epilogueDense", "epilogueMasked")) {
        assert(laneOps(elided, body) === laneOps(kept, body) - monthStepOps(axis),
          s"$body did not lose exactly the month step at neri=${axis.neriSchneiderMonth()}: " +
            s"${laneOps(kept, body)} lane ops with the step kept, ${laneOps(elided, body)} " +
            "with it elided")
      }
    }
    // The four ops are dead work, so removing them is not allowed to move an answer.
    for ((options, ctx) <- Seq(
        (VarkaEmitOptions.DEFAULTS, "elided"),
        (VarkaEmitOptions.DEFAULTS.withElideChronoMonth(false), "kept"))) {
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = s"month step $ctx",
        options = options)
    }
  }

  test("the month step follows the group's consumers, not the emission order") {
    val col = new ColumnRef(0)
    for ((roots, ctx) <- Seq(
        (Seq[VarkaVectorIR](new Year(col), new Month(col)), "year first"),
        (Seq[VarkaVectorIR](new Month(col), new Year(col)), "month first"))) {
      val elided = emitMulti(roots, 1, 0, sharing)._2
      val kept = emitMulti(roots, 1, 0, sharing.withElideChronoMonth(false))._2
      // Under B2 the pair shares a loop method as well as the epilogue, so in both bodies the
      // one shared prefix is read by the month tail and must keep the step - whichever of the
      // two siblings happens to emit it. This is the whole reason the decision is read from
      // the group's consumer set rather than from the node being emitted.
      assert(methodNames(emitMulti(roots, 1, 0, sharing)).count(_.startsWith("loopMasked"))
        === 1, s"the pair no longer shares a loop method ($ctx)")
      for (body <- Seq("loopMasked0", "epilogueMasked")) {
        assert(laneOps(elided, body) === laneOps(kept, body),
          s"$body elided the month step with a month tail reading it ($ctx)")
      }
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = s"shared, $ctx",
        options = sharing)
    }
    // Over different dates the two keep separate loop methods, and exactly one of them - the
    // year's, whose prefix no month tail reads - elides. This is the half the pre-B2 version of
    // the test asserted on year(d), month(d), when those were separate methods too.
    val split = Seq[VarkaVectorIR](new Year(col), new Month(new ColumnRef(1)))
    val elided = emitMulti(split, 2, 0, sharing)._2
    val kept = emitMulti(split, 2, 0, sharing.withElideChronoMonth(false))._2
    val saved = Seq("loopMasked0", "loopMasked1")
      .map(body => laneOps(kept, body) - laneOps(elided, body))
    assert(saved.sorted === Seq(0, monthStepOps(sharing)),
      s"expected exactly one loop method to elide the month step, saved $saved")
  }

  test("with sharing off the decision is per node, not per fragment") {
    // Unshared, year(d) and month(d) name different locals even though their fragment keys are
    // equal, so the year's own prefix elides and the month's does not - keying the decision on
    // the fragment there would make the year pay for a month it shares nothing with.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](new Year(col), new Month(col))
    val elided = emitMulti(roots, 1, 0, unshared)._2
    val kept = emitMulti(roots, 1, 0, unshared.withElideChronoMonth(false))._2
    assert(laneOps(elided, "epilogueMasked") ===
      laneOps(kept, "epilogueMasked") - monthStepOps(unshared),
      "the unshared epilogue holds two prefixes and exactly one of them - the year's - is " +
        "supposed to lose its month step")
    checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
      nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = "unshared, per node",
      options = unshared)
  }

  test("dayofyear elides the month step too, and month(d) beside it does not") {
    // The bounded counterpart of the sweep: dayofyear's tail reads the January turn off the
    // day of year (like Year's, task 48), so its prefix has no reason to run the month step.
    // A regression here is silent - the tail would read a local nothing wrote - so the count
    // is pinned rather than left to the sweep, which is opt-in.
    val col = new ColumnRef(0)
    val alone = Seq[VarkaVectorIR](new DayOfYear(col))
    // Both axes (task 53). What this node elides is whatever the prefix's month step costs on
    // the axis in force - four ops on the 0-based one, two on the numerator - so the assertion
    // is about the elision holding, not about a particular number.
    for (axis <- Seq(VarkaEmitOptions.DEFAULTS,
        VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(false))) {
      val elided = emitMulti(alone, 1, 0, axis)._2
      val kept = emitMulti(alone, 1, 0, axis.withElideChronoMonth(false))._2
      for (body <- Seq("loopDense0", "loopMasked0", "epilogueDense", "epilogueMasked")) {
        assert(laneOps(elided, body) === laneOps(kept, body) - monthStepOps(axis),
          s"$body did not lose exactly the month step at neri=${axis.neriSchneiderMonth()}: " +
            s"${laneOps(kept, body)} lane ops with the step kept, ${laneOps(elided, body)} " +
            "with it elided")
      }
    }
    // Shared with a month tail, the fragment keeps the step for both - the decision is the
    // group's consumer set, not the node's, and dayofyear must not elide out from under it.
    val withMonth = Seq[VarkaVectorIR](new DayOfYear(col), new Month(col))
    val sharedElided = emitMulti(withMonth, 1, 0, sharing)._2
    val sharedKept = emitMulti(withMonth, 1, 0, sharing.withElideChronoMonth(false))._2
    assert(laneOps(sharedElided, "epilogueMasked") === laneOps(sharedKept, "epilogueMasked"),
      "the shared epilogue elided the month step with a month tail reading it")
    for ((options, ctx) <- Seq((VarkaEmitOptions.DEFAULTS, "alone"), (sharing, "shared"))) {
      checkMatrix(if (ctx == "alone") alone else withMonth, 1, Array.empty[Int],
        remainderLengths, nullPatterns.map(p => Seq(p._2)), data = calendarDays,
        ctx = s"dayofyear month step, $ctx", options = options)
    }
  }

  test("sharing the prefix moves the epilogue's HugeMethodLimit crossing, and the bitmap " +
    "pass moves " +
      "it again: unshared 21 to 22, shared 44 to 49") {
    // This is what step B1 is for, and the only thing it is for under today's grouping. The
    // epilogue is one method over *every* output by task 24's deliberate decision, so its size
    // grows with the whole projection rather than with a group. Four fields over one date
    // repeat the decomposition four times; sharing it is most of the method.
    //
    // The outputs must be distinct nodes to count: the IR's records compare by value, so
    // year(d) twice is one node and the emitter already emits it once. Four fields per date
    // over as many dates as the width needs is the shape task 44 measured.
    //
    // The unshared boundary has now moved three times, each for a different reason, which is
    // why it is re-measured here rather than reasoned about: task 44 recorded 16 fits/17
    // crosses; task 51 removed the per-extraction range guard, shrinking every emitted calendar
    // prefix, shared or not, to 18 fits/19 crosses (see PLAN_TASK_51.md section 4.1 for the
    // numbers that replaced); task 48 lets a Year node's own prefix skip the March-month step,
    // and unshared every Year node has its own prefix, so the epilogue's four-fields-per-date
    // shape loses one month step per date - 19 fits/20 crosses; task 54's Julian map takes a
    // division stage out of every prefix, shared or not, so unshared 20 fits (7675 bytes) and
    // 21 crosses (8336). Shared is still at 44 - 40 outputs fit in 7087 bytes and 44 cross at
    // 8063, down from 8630 - because the epilogue holds every output, so each date's fragment
    // has a Month consumer and keeps the month step, and the prefix it shares got shorter by
    // the same amount for every date. The ladder is in PLAN_TASK_54.md section 9. The limit
    // itself is HotSpot's HugeMethodLimit, past which it gives up on compiling the method at
    // all (interpreted, boxed vectors, on every batch whose length is not a lane multiple).
    def fields(dates: Int): Seq[VarkaVectorIR] = (0 until dates).flatMap { c =>
      val col = new ColumnRef(c)
      Seq[VarkaVectorIR](new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    }
    val limit = 8000
    // Task 70 (PLAN_TASK_70.md 9): with the bitmap pass on by default, every word in these
    // methods is dead, so epilogueMasked is epilogueDense's bytes and the crossing is the
    // dense epilogue's - unshared 21 fits (7563) and 22 crosses (8033); shared reaches
    // 49. The per-group arm keeps the old boundaries, asserted beside.
    assert(epilogueSize(fields(6).take(21), 12, unshared) < limit)
    assert(epilogueSize(fields(6).take(22), 12, unshared) > limit)
    assert(epilogueSize(fields(12), 12, sharing) < limit,
      "forty-eight shared outputs fit under the pass; the boundary is further out")
    assert(epilogueSize(fields((49 + 3) / 4).take(49 - 1), 13,
      sharing) < limit)
    val past = epilogueSize(fields((49 + 3) / 4).take(49), 13,
      sharing)
    assert(past > limit,
      s"49 shared calendar outputs now fit in $past bytes - the pass reaches " +
        "further than this test records, so PLAN_TASK_70.md 9's ladder is stale")
    // The reference variant: the boundaries task 54 left, 20/21 unshared and 44 shared.
    val perGroupUnshared = unshared.withValidityByBitmap(false)
    val perGroupShared = sharing.withValidityByBitmap(false)
    assert(epilogueSize(fields(5), 12, perGroupUnshared) < limit)
    assert(epilogueSize(fields(6).take(21), 12, perGroupUnshared) > limit)
    assert(epilogueSize(fields(10), 12, perGroupShared) < limit)
    assert(epilogueSize(fields(11), 12, perGroupShared) > limit)
  }

  test("the masked body agrees with the dense body on null-free data") {
    // forceMasked reports one null over a full-set bitmap, which the dispatcher sends down
    // runMasked; the reference expectations are identical to the dense run's.
    val root = new IfElse(new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1)),
      new Greatest(new DayOfWeek(new ColumnRef(0)), new ColumnRef(1)),
      new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    val nullFree = Seq(Seq[Int => Boolean](_ => false, _ => false))
    checkMatrix(Seq(root), 2, Array(3), Seq(17, 64, 65, 1000), nullFree, ctx = "dense")
    checkMatrix(Seq(root), 2, Array(3), Seq(17, 64, 65, 1000), nullFree,
      forceMasked = true, ctx = "forced-masked")
  }

  test("the validity-word algebra agrees with planWordRef on the shapes the plan " +
      "reasons about, and every word a body stores is loaded") {
    // Two emit-time assertions arm this task before it changes a byte. planSlots asserts, on
    // every masked body it plans, that the symbolic word algebra (Analysis.pureWord and
    // wordOwner - what the bitmap pass will read) and the slot references planWordRef assigns
    // describe the same word; and every loop or epilogue body asserts at its end that each
    // word it stored was loaded at least once and each word it loaded was stored, through the
    // one call every consumer reads a word by. Both run under every test in this suite and
    // every fuzz iteration. This test exists so a failure names itself here first, on the
    // shapes PLAN_TASK_70.md 3.3 registers op counts for, rather than inside whichever other
    // test happens to build the shape - and so that the two corners the agreement check
    // deliberately allows (greatest over two literals, greatest over one input twice: a slot
    // written with `-1 | -1` where the algebra says the constant or the input) are exercised on
    // purpose. Emission is the assertion.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val d3 = new ColumnRef(2)
    val d4 = new ColumnRef(3)
    val lit = new LiteralSlot(0)
    def ymdq(c: VarkaVectorIR): Seq[VarkaVectorIR] = Seq(
      new Year(c), new Month(c), new DayOfMonth(c), new Quarter(c))
    val shapes: Seq[(String, Seq[VarkaVectorIR], Int)] = Seq(
      ("year(d)", Seq(new Year(d)), 1),
      ("year, month, dayofmonth, quarter over d", ymdq(d), 1),
      ("next_day(d, k), column kernel", Seq(new NextDay(d, d2)), 2),
      ("greatest(d, d2)", Seq(new Greatest(d, d2)), 2),
      ("year(date_add(d, off)), guarded", Seq(new Year(new AddDays(d, d2))), 2),
      ("year(d) beside d < lit", Seq(new Year(d), new Compare(CompareOp.LT, d, lit)), 1),
      ("if(d < d2, d, d2)", Seq(new IfElse(new Compare(CompareOp.LT, d, d2), d, d2)), 2),
      ("datediff(greatest(d, d2), greatest(d3, d4)), the mixed tree",
        Seq(new DateDiff(new Greatest(d, d2), new Greatest(d3, d4))), 4),
      ("greatest(lit, lit) beside year(d)", Seq(new Greatest(lit, lit), new Year(d)), 1),
      ("greatest(d, d)", Seq(new Greatest(d, d)), 1),
      ("datediff(d, d)", Seq(new DateDiff(d, d)), 1),
      ("make_date, both forms", Seq[VarkaVectorIR](
        new MakeDate(new Year(d), new Month(d), new DayOfMonth(d), false),
        new MakeDate(new Year(d), new Month(d), new DayOfMonth(d), true)), 1),
      ("trunc(d, level column)", Seq(new TruncDateDynamic(d, d2)), 2),
      ("add_months(d, m), column count, under year", Seq(new Year(new AddMonths(d, d2))), 2))
    for ((name, roots, numInputs) <- shapes) {
      val (_, bytes) = emitMulti(roots, numInputs, 1)
      assert(bytes.nonEmpty, name)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Task 70: validity as bitmap algebra in the driver.
  // ---------------------------------------------------------------------------------------------

  private val bitmapOn = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(true)
  private val bitmapOff = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(false)
  private val supportClass = "org.apache.spark.sql.varka.vector.VarkaVectorSupport"

  /** The validity work in a method: every VarkaVectorSupport call but the segment mapping,
   *  which every body mode emits per segment and which would keep this off zero for ever
   *  (PLAN_TASK_70.md 3.3). */
  private def validityOps(bytes: Array[Byte], method: String): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, method, supportClass, Seq("ofAddress").asJava)

  private def supportNames(bytes: Array[Byte]): Set[String] =
    VarkaEmitterTestSupport.invokedNames(bytes, supportClass).asScala.toSet

  test("byte-identical validity with the bitmap pass on and off, every null pattern " +
      "and length, over the shapes the plan names") {
    // The existing oracle is the assertion: checkMatrix compares every output's validity byte
    // for byte against the reference evaluator and asserts status 0, and makeInputData poisons
    // every null lane, so this is also the guard-under-nulls test (PLAN_TASK_70.md 5) - a
    // lowering that dropped a guard's word AND would decline a batch here. Each shape names
    // the corner it is for.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val d3 = new ColumnRef(2)
    val lit = new LiteralSlot(0)
    val one = nullPatterns.map(p => Seq(p._2))
    val shapes: Seq[(String, Seq[VarkaVectorIR], Int, Seq[Seq[Int => Boolean]])] = Seq(
      ("year(d): a copy", Seq(new Year(d)), 1, one),
      ("four fields over d, one method",
        Seq(new Year(d), new Month(d), new DayOfMonth(d), new Quarter(d)), 1, one),
      ("next_day(d, k), column kernel: an AND", Seq(new NextDay(d, d2)), 2, combos(2)),
      // The OR root: with d all-null the output is d2's bitmap and 0L is never dereferenced;
      // with d null-free every row is valid, the case 2.3's first rule got wrong.
      ("greatest(d, d2): an OR", Seq(new Greatest(d, d2)), 2, combos(2)),
      ("least(d, d2)", Seq(new Least(d, d2)), 2, combos(2)),
      ("datediff(d, d2)", Seq(new DateDiff(d, d2)), 2, combos(2)),
      // The guards keep their words (2.2): the producer's word stays live for the AND with the
      // condemning mask, and a poisoned null lane must not decline the batch.
      ("year(date_add(d, off)), guarded producer", Seq(new Year(new AddDays(d, d2))), 2,
        combos(2)),
      ("year(add_months(d, m)), guarded count", Seq(new Year(new AddMonths(d, d2))), 2,
        combos(2)),
      ("year(d) beside d < lit: a Cond root keeps the read",
        Seq(new Year(d), new Compare(CompareOp.LT, d, lit)), 1, one),
      ("if(d < d2, d, d2): a blend, not served",
        Seq(new IfElse(new Compare(CompareOp.LT, d, d2), d, d2)), 2, combos(2)),
      // Two outputs where an all-null d does not fire the driver's shortcut, since year(d3)
      // reads no all-null column: the AND root's bitmap has to be written by the pass, before
      // step (5), or it is never written at all (3.1).
      ("datediff(d, d2) beside year(d3): the AND root past the shortcut",
        Seq(new DateDiff(d, d2), new Year(d3)), 3, combos(3)),
      // A null-free input inside a masked kernel: year(d)'s bitmap is setValid, not a copy.
      ("year(d) beside datediff(d2, d3): a null-free input under a masked driver",
        Seq(new Year(d), new DateDiff(d2, d3)), 3, combos(3)),
      // Three columns: the first two through the pair entry point, the third through Into.
      ("datediff(datediff(d, d2), d3): a three-column AND chain",
        Seq(new DateDiff(new DateDiff(d, d2), d3)), 3, combos(3)),
      ("greatest(greatest(d, d2), d3): a three-column OR chain",
        Seq(new Greatest(new Greatest(d, d2), d3)), 3, combos(3)),
      // The mixed tree is declined and keeps today's per-group path; it must still be right.
      ("datediff(greatest(d, d2), greatest(d, d3)): the mixed tree, declined",
        Seq(new DateDiff(new Greatest(d, d2), new Greatest(d, d3))), 3, combos(3)))
    for ((name, roots, n, patterns) <- shapes; on <- Seq(true, false)) {
      checkMatrix(roots, n, Array(3), remainderLengths ++ Seq(64, 1000), patterns,
        ctx = s"$name, validityByBitmap=$on", options = if (on) bitmapOn else bitmapOff)
    }
  }

  test("the validity work per masked loop method, as PLAN_TASK_70.md 3.3 registered " +
      "it, and no IntVector op moves") {
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val lit = new LiteralSlot(0)
    // The Cond pair needs one method to be the mixed method the table describes: a Year weighs
    // 38 against GROUP_BUDGET's 16, so at the default budget the two outputs split.
    val oneMethod = VarkaEmitOptions.DEFAULTS.withGroupBudget(200)
    val rows: Seq[(String, Seq[VarkaVectorIR], Int, VarkaEmitOptions, Int, Int)] = Seq(
      ("year(d)", Seq(new Year(d)), 1, VarkaEmitOptions.DEFAULTS, 2, 0),
      ("year, month, dayofmonth, quarter over d",
        Seq(new Year(d), new Month(d), new DayOfMonth(d), new Quarter(d)), 1,
        VarkaEmitOptions.DEFAULTS, 5, 0),
      ("next_day(d, k), column kernel", Seq(new NextDay(d, d2)), 2, VarkaEmitOptions.DEFAULTS,
        3, 0),
      // The pick's null substitution reads both operand words for the value, whether or not
      // its own word is wanted - the consumer PLAN_TASK_70.md 2.2 did not list - so its two
      // reads stay and only the write goes. The plan's 3.3 registered 0 here off 2.2's
      // inventory; this assertion is what corrected it.
      ("greatest(d, d2)", Seq(new Greatest(d, d2)), 2, VarkaEmitOptions.DEFAULTS, 3, 2),
      ("year(date_add(d, off)), guarded", Seq(new Year(new AddDays(d, d2))), 2,
        VarkaEmitOptions.DEFAULTS, 3, 2),
      ("year(d) beside d < lit, one method",
        Seq(new Year(d), new Compare(CompareOp.LT, d, lit)), 1, oneMethod, 3, 2),
      ("if(d < d2, d, d2)", Seq(new IfElse(new Compare(CompareOp.LT, d, d2), d, d2)), 2,
        VarkaEmitOptions.DEFAULTS, 3, 3))
    for ((name, roots, n, base, today, after) <- rows) {
      val off = emitMulti(roots, n, 1, base.withValidityByBitmap(false))._2
      val on = emitMulti(roots, n, 1, base.withValidityByBitmap(true))._2
      assert(validityOps(off, "loopMasked0") === today, s"$name, pass off: reads + writes today")
      assert(validityOps(on, "loopMasked0") === after, s"$name, pass on: what is left")
      assert(laneOps(on, "loopMasked0") === laneOps(off, "loopMasked0"),
        s"$name: the pass moved an IntVector op, and it touches no lane op")
    }
  }

  test("a single-operator word tree of any depth is served through the chain entry " +
      "points; a mixed AND/OR tree is declined and keeps its per-group write") {
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val d3 = new ColumnRef(2)
    val d4 = new ColumnRef(3)
    val andChain = emitMulti(Seq(new DateDiff(new DateDiff(d, d2), d3)), 3, 0, bitmapOn)._2
    assert(validityOps(andChain, "loopMasked0") === 0)
    assert(supportNames(andChain).contains("andColumnValidity"), supportNames(andChain))
    assert(supportNames(andChain).contains("andColumnValidityInto"), supportNames(andChain))
    val orChain = emitMulti(Seq(new Greatest(new Greatest(d, d2), d3)), 3, 0, bitmapOn)._2
    // The picks' value substitution still reads every operand word (2.2's missed consumer),
    // so the reads stay; only the root's write goes.
    assert(validityOps(orChain, "loopMasked0") === 3)
    assert(supportNames(orChain).contains("orColumnValidity"), supportNames(orChain))
    assert(supportNames(orChain).contains("orColumnValidityInto"), supportNames(orChain))
    val single = emitMulti(Seq(new Year(d)), 1, 0, bitmapOn)._2
    assert(supportNames(single).contains("copyColumnValidity"), supportNames(single))
    // And(Or(0, 1), Or(2, 3)): two live intermediates, which two-operand calls over one
    // destination cannot evaluate. Declined: no column entry point, today's reads and write.
    val mixed = emitMulti(
      Seq(new DateDiff(new Greatest(d, d2), new Greatest(d3, d4))), 4, 0, bitmapOn)._2
    assert(!supportNames(mixed).exists(_.contains("ColumnValidity")), supportNames(mixed))
    assert(validityOps(mixed, "loopMasked0") === 5, "four reads for the picks, one write")
  }

  test("the served and declined root counts, per shape") {
    // The safety net PLAN_TASK_70.md 3.1 promised. Without it a regression that stopped
    // serving every root would revert the whole lowering to the per-group path and pass the
    // suite: the byte-identity test compares the two settings, which agree when nothing is
    // served; the differential compares against a reference evaluator, and the per-group path
    // is correct; and every size assertion is an upper bound. So the counts are pinned per
    // shape here, in both directions - what is served, and what is declined and why.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val d3 = new ColumnRef(2)
    val d4 = new ColumnRef(3)
    def counts(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int = 0,
        options: VarkaEmitOptions = bitmapOn): (Int, Int) = {
      val c = VarkaLoopEmitter.bitmapPassCounts(roots.asJava, numInputs, numLiterals, options)
      (c(0), c(1))
    }
    // Served, and nothing declined: a leaf word, an AND chain, an OR chain, four fields over
    // one date, and the shape the whole task is named for.
    assert(counts(Seq(new Year(d)), 1) === (1, 0))
    assert(counts(Seq(new DateDiff(new DateDiff(d, d2), d3)), 3) === (1, 0))
    assert(counts(Seq(new Greatest(new Greatest(d, d2), d3)), 3) === (1, 0))
    assert(counts(Seq(new Year(d), new Month(d), new DayOfMonth(d), new Quarter(d)), 1)
      === (4, 0))
    // Declined for a mixed tree - the one kind the counter is for.
    assert(counts(Seq(new DateDiff(new Greatest(d, d2), new Greatest(d3, d4))), 4) === (0, 1))
    // Unserved but not declined: a word the emission computes rather than folds. `IfElse`
    // blends by the known-true mask and `make_date` tests its own validity, so neither has a
    // pure expression at all and neither is a mixed tree.
    val blend = new IfElse(new Compare(CompareOp.LT, d, d2), d, d2)
    assert(counts(Seq(blend), 2) === (0, 0))
    assert(counts(Seq(new MakeDate(new Year(d), new Month(d), new DayOfMonth(d), false)), 1)
      === (0, 0))
    // A `Cond` root is a selection bitmap, not a value: never served, never counted.
    assert(counts(Seq(new Compare(CompareOp.LT, d, d2)), 2) === (0, 0))
    // With the option off nothing is served and nothing is declined - the pass does not run,
    // so a shape that would have been declined is not counted as one.
    val off = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(false)
    assert(counts(Seq(new Year(d)), 1, 0, off) === (0, 0))
    assert(counts(Seq(new DateDiff(new Greatest(d, d2), new Greatest(d3, d4))), 4, 0, off)
      === (0, 0))
  }

  test("the word-liveness invariant is armed, in both directions") {
    // misdescribeWordLiveness inverts the verdict on every word. year(d): its only word is
    // dead - the root is served and nothing else reads it - so the fault makes it live: stored
    // at the top of the lane group, loaded by nobody, refused at the end of the body.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    val fault = bitmapOn.withMisdescribeWordLiveness(true)
    val stored = intercept[IllegalStateException] {
      emitMulti(Seq(new Year(d)), 1, 0, fault)
    }
    assert(stored.getMessage.contains("stored but never loaded"), stored.getMessage)
    // year(date_add(d, off)): the guard reads the producer's word, so it and both inputs' are
    // live; the fault makes them dead, and the guard's load is refused at the load.
    val loaded = intercept[IllegalStateException] {
      emitMulti(Seq(new Year(new AddDays(d, d2))), 2, 0, fault)
    }
    assert(loaded.getMessage.contains("declared dead is loaded"), loaded.getMessage)
    // make_date: its own word is demanded unconditionally, because its guard reads it, so the
    // fault kills it and the guard's load is refused - the same direction as above, on the one
    // node that reaches its word through neither the AND family nor a root write. Pinned
    // because that arm read its slot directly until this task's review: a raw load reaches no
    // refusal at all, and the emission died in the class-file writer with an invalid local
    // index instead, which is not what this injector is documented to raise.
    val makeDate = new MakeDate(new Year(d), new Month(d), new DayOfMonth(d), false)
    val guardLoad = intercept[IllegalStateException] {
      emitMulti(Seq[VarkaVectorIR](makeDate), 1, 0, fault)
    }
    assert(guardLoad.getMessage.contains("declared dead is loaded"), guardLoad.getMessage)
    // With the pass off every word is live already, so the inversion has nothing to invert.
    assert(emitMulti(Seq(new Year(d)), 1, 0, bitmapOff.withMisdescribeWordLiveness(true))
      ._2.nonEmpty)
    assert(emitMulti(Seq[VarkaVectorIR](makeDate), 1, 0,
      bitmapOff.withMisdescribeWordLiveness(true))._2.nonEmpty)
  }

  test("a masked method whose every word is dead is its dense twin's bytes - one " +
      "body, not two") {
    // No per-group read, no per-group write, no null-state prologue, no own-word slot: what is
    // left is the dense method. Asserted on size rather than on the byte string because the
    // two methods differ in name inside the constant pool, not in code; a size match on both
    // the loop and the epilogue is the claim 2.34 asked to have verified rather than assumed.
    val d = new ColumnRef(0)
    val d2 = new ColumnRef(1)
    for ((name, roots, n) <- Seq(
        ("year(d)", Seq[VarkaVectorIR](new Year(d)), 1),
        ("four fields over d",
          Seq[VarkaVectorIR](new Year(d), new Month(d), new DayOfMonth(d), new Quarter(d)), 1),
        ("next_day(d, k), column kernel", Seq[VarkaVectorIR](new NextDay(d, d2)), 2),
        ("datediff(d, d2)", Seq[VarkaVectorIR](new DateDiff(d, d2)), 2))) {
      val bytes = emitMulti(roots, n, 0, bitmapOn)._2
      for ((masked, dense) <- Seq(("loopMasked0", "loopDense0"),
          ("epilogueMasked", "epilogueDense"))) {
        assert(VarkaEmitterTestSupport.codeSize(bytes, masked) ===
          VarkaEmitterTestSupport.codeSize(bytes, dense), s"$name: $masked against $dense")
      }
    }
  }

  test("the driver stays under HugeMethodLimit on the output ladder, with the pass " +
      "on and off, and the pass costs the 48-output driver what prediction 6 said") {
    // Nothing measured the driver before this task; it is one method for every output and the
    // one method every batch runs. Measured before the work: 2409 bytes at 44 outputs against
    // the epilogue's 8058, 2624 at 48 (PLAN_TASK_70.md 6.1). The pass adds about ten bytes
    // per served single-input output - one call with its operand pushes, less the zero it
    // replaces - so the 48-output driver was predicted under 500 bytes larger.
    def fields(dates: Int): Seq[VarkaVectorIR] = (0 until dates).flatMap { c =>
      val col = new ColumnRef(c)
      Seq[VarkaVectorIR](new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    }
    for (dates <- Seq(5, 11, 12); (base, layout) <- Seq((sharing, "shared"),
        (unshared, "unshared")); on <- Seq(true, false)) {
      val bytes = emitMulti(fields(dates), dates, 0, base.withValidityByBitmap(on))._2
      val driver = VarkaEmitterTestSupport.codeSize(bytes, "runMasked")
      assert(driver < 8000, s"$layout, $dates dates, pass=$on: runMasked is $driver bytes")
    }
    val off = VarkaEmitterTestSupport.codeSize(
      emitMulti(fields(12), 12, 0, sharing.withValidityByBitmap(false))._2, "runMasked")
    val on = VarkaEmitterTestSupport.codeSize(
      emitMulti(fields(12), 12, 0, sharing.withValidityByBitmap(true))._2, "runMasked")
    assert(on > off && on - off < 600,
      s"48 outputs: the driver went from $off to $on bytes; prediction 6 said under 500 more")
  }

  test("the driver's fill writes the bits the loop used to OR, exactly") {
    // The narrow claim: the dense path writes the same bits from a different place. So the
    // check is byte-for-byte identity against today's path, at every length where the last
    // byte is partial - which is the byte that fails if setValid fills whole bytes rather than
    // exactly `length` bits. assertSameOutput inside checkMatrix already compares validity byte
    // for byte, so driving both option values through it is the assertion.
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)), new DayOfWeek(new ColumnRef(0)))
    val nullFree = Seq(Seq[Int => Boolean](_ => false))
    val lengths = Seq(1, 7, 8, 9, 15, 16, 17, 63, 64, 65, 1000, 4095)
    // Days that stay inside the narrowed range at every index these lengths reach.
    // `calendarDays` walks out of it past about index 1180 (i * 9973 - 400000), and since task
    // 51 removed the per-extraction guard an out-of-range day no longer declines - it returns a
    // plausible wrong year. That is a real hazard, but it is task 52's, and a validity test
    // that trips over it is testing the wrong thing.
    def inRangeDays(c: Int, i: Int): Int = 19000 + (i % 9973)
    for (once <- Seq(true, false)) {
      checkMatrix(roots, 1, Array.empty[Int], lengths, nullFree, data = inRangeDays,
        ctx = s"denseValidityOnce=$once",
        options = VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(once))
    }
  }

  test("a Cond root keeps its per-group OR under both option values") {
    // The selection bitmap's bits mean "known true", not "valid", so the driver must not fill
    // it - a filled selection bitmap selects every row. This is the test that fails if the
    // fill is applied to a Cond root, and it is why fillsValidityOnce excludes them rather
    // than the driver and the loop each deciding separately.
    val root = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1))
    for (once <- Seq(true, false)) {
      checkMatrix(Seq(root), 2, Array.empty[Int], Seq(17, 64, 65, 1000),
        nullPatterns.map(p => Seq(p._2, p._2)), ctx = s"cond, denseValidityOnce=$once",
        options = VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(once))
    }
  }

  test("the masked path's bytes do not move, and the dense path's shrink") {
    // The guard that keeps this task off the masked path, asserted the way task 32 asserted
    // its own: the masked bodies are byte for byte as they were, so no masked case can have
    // changed, and only the dense loop is allowed to have lost anything.
    val col = new ColumnRef(0)
    for ((roots, name) <- Seq(
        (Seq[VarkaVectorIR](new Year(col)), "year"),
        (Seq[VarkaVectorIR](new Year(col), new Month(col)), "year+month"),
        (Seq[VarkaVectorIR](chain(4)), "chain4"))) {
      val off = emitMulti(roots, 1, 4, VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(false))._2
      val on = emitMulti(roots, 1, 4, VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(true))._2
      for (body <- Seq("loopMasked0", "epilogueMasked")) {
        assert(VarkaEmitterTestSupport.codeSize(off, body) ===
          VarkaEmitterTestSupport.codeSize(on, body),
          s"$name: $body moved, so this task reached the masked path")
      }
      for (body <- Seq("loopDense0", "epilogueDense")) {
        assert(VarkaEmitterTestSupport.codeSize(on, body) <
          VarkaEmitterTestSupport.codeSize(off, body),
          s"$name: $body did not shrink, so the per-group OR is still being emitted")
      }
    }
  }

  /** The support class the emitted bodies call their validity helpers on. */
  private val support = "org.apache.spark.sql.varka.vector.VarkaVectorSupport"
  private val intVector = "jdk.incubator.vector.IntVector"

  test("a whole lane group calls the helper named for the emitted width") {
    // The emitter knows the lane count when it writes the bytes, so the callee can carry it and
    // the four-arm switch on the width disappears from the call. Asserted on the names in the
    // class rather than on a timing, and by exact match: "orValidityBitsAt" is a prefix of
    // "orValidityBitsAt16", so a substring test would pass on the form this task removes.
    val roots = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    for ((lanes, bits) <- Seq(2 -> 64, 4 -> 128, 8 -> 256, 16 -> 512)) {
      // Since task 70 the shipped year(d) makes no per-group validity call at all - its
      // bitmap is copied once by the driver - so the helpers this test names are reached
      // through the per-group reference variant, which is what the naming is pinned on.
      val bytes = emitMulti(roots, 1, 0,
        VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes).withValidityByBitmap(false))._2
      val called = VarkaEmitterTestSupport.invokedNames(bytes, support).asScala
      assert(called.contains(s"validityBitsAt$lanes"), s"$lanes lanes: $called")
      assert(called.contains(s"orValidityBitsAt$lanes"), s"$lanes lanes: $called")
      assert(!called.contains("validityBitsAt"), s"$lanes lanes: the general reader survived")
      assert(!called.contains("orValidityBitsAt"), s"$lanes lanes: the general writer survived")
      // The epilogue's partial group is not a lane width and keeps the general pair.
      assert(called.contains("orPartialValidityBitsAt"), s"$lanes lanes: $called")
      // The species is baked to match, so the class cannot compute at one width and write
      // validity at another - the invariant that would otherwise be implicit in "the emitter
      // runs in the JVM that runs the kernel".
      val fields = VarkaEmitterTestSupport.staticFieldsRead(bytes, intVector).asScala
      assert(fields.contains(s"SPECIES_$bits"), s"$lanes lanes: $fields")
      assert(!fields.contains("SPECIES_PREFERRED"), s"$lanes lanes: $fields")
    }
  }

  test("a width with no specialised helper falls back to the general pair") {
    // 32 int lanes is a 1024-bit shape: SVE reaches it, the Vector API has no named species
    // constant for it, and VarkaVectorSupport has no pair. The fallback is what keeps such a
    // machine correct, so it is emitted and asserted rather than reasoned about.
    // The per-group reference arm since task 70: the shipped year(d) makes no per-group
    // validity call, and it is the general pair's naming this test pins.
    val bytes = emitMulti(Seq[VarkaVectorIR](new Year(new ColumnRef(0))), 1, 0,
      VarkaEmitOptions.DEFAULTS.withLanesOverride(32).withValidityByBitmap(false))._2
    val called = VarkaEmitterTestSupport.invokedNames(bytes, support).asScala
    assert(called.contains("validityBitsAt") && called.contains("orValidityBitsAt"), s"$called")
    assert(!called.exists(_.matches("(or)?ValidityBitsAt\\d+")), s"$called")
    assert(VarkaEmitterTestSupport.staticFieldsRead(bytes, intVector).asScala
      .contains("SPECIES_PREFERRED"))
  }

  test("with the option off the emission is the pre-task form") {
    // The A/B's other arm, and the reference variant: no width anywhere - not in a callee name
    // and not in the species - so what the benchmark compares against is what shipped before.
    // Both of task 46's arms are reached through task 70's per-group reference arm now.
    val bytes = emitMulti(Seq[VarkaVectorIR](new Year(new ColumnRef(0))), 1, 0,
      VarkaEmitOptions.DEFAULTS.withValidityByWidth(false).withValidityByBitmap(false))._2
    val called = VarkaEmitterTestSupport.invokedNames(bytes, support).asScala
    assert(called.contains("validityBitsAt") && called.contains("orValidityBitsAt"), s"$called")
    assert(!called.exists(_.matches("(or)?ValidityBitsAt\\d+")), s"$called")
    val fields = VarkaEmitterTestSupport.staticFieldsRead(bytes, intVector).asScala
    assert(fields.contains("SPECIES_PREFERRED"), s"$fields")
    assert(!fields.exists(_.matches("SPECIES_\\d+")), s"$fields")
    // And the lane count is asked for at run time here and nowhere in a baked emission, which
    // is the other half of "the width is a property of the class": a constant, not a call.
    val species = "jdk.incubator.vector.VectorSpecies"
    assert(VarkaEmitterTestSupport.invokedNames(bytes, species).asScala.contains("length"))
    val baked = emitMulti(Seq[VarkaVectorIR](new Year(new ColumnRef(0))), 1, 0)._2
    assert(!VarkaEmitterTestSupport.invokedNames(baked, species).asScala.contains("length"),
      "the baked emission still calls VectorSpecies.length()")
  }

  test("the word writer's bitmap is the per-group writer's, at every length, width " +
      "and null state") {
    // The failure mode this task has and its predecessors did not: a store eight bytes wide
    // where the group is one or two, into a bitmap whose nominal size is (length + 7) / 8. Both
    // halves of that are length-dependent and silent - a word that runs off the end faults only
    // when the segment happens to be tight, and a partial word left behind is a wrong bit, not
    // a crash - so the ladder is the test, and it runs the awkward lengths on purpose: below a
    // word, either side of a word boundary, either side of the default batch size.
    //
    // The oracle is the per-group writer itself. Both arms run on the same input and the
    // bitmaps are compared byte for byte over the rows that exist, which is the only comparison
    // that can catch a bit set in the wrong word.
    val blend: VarkaVectorIR = new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0)),
      new AddDays(new ColumnRef(0), new LiteralSlot(0)),
      new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    // A Cond root is the filter kernel, and it is in this task's population on *every* batch:
    // its slot holds a selection bitmap rather than validity, so task 45's driver fill cannot
    // serve it and task 70's pass does not run in a dense body at all. Leaving it out would
    // leave the project's most common shape untested at both arms.
    val filter: VarkaVectorIR = new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0))
    val lits = Array(3)
    for (lanes <- Seq(4, 8, 16)) {
      val perGroup = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes)
      val byWord = perGroup.withValidityByWord(true)
      for ((roots, shape) <- Seq(
          Seq(blend) -> "one blend",
          Seq(filter) -> "a filter",
          Seq(blend, filter) -> "a blend beside a filter",
          Seq(blend, blend, filter) -> "two blends beside a filter")) {
        val (refKernel, refLoader) = load(emitMulti(roots, 1, lits.length, perGroup))
        val (wordKernel, wordLoader) = load(emitMulti(roots, 1, lits.length, byWord))
        try {
          for (length <- Seq(1, 7, 8, 15, 16, 63, 64, 65, 127, 128, 129, 1000, 4096);
               (isNull, nullState) <- Seq[(Int => Boolean, String)](
                 (_ => false, "null-free"),
                 (i => i % 3 == 0, "mixed nulls"),
                 (_ => true, "all null"))) {
            val arena = Arena.ofConfined()
            try {
              val col = makeInputData(arena, length, isNull, i => i - 500)
              // The reference arm keeps the tight segment, so it stays the guard it has always
              // been; only the word arm is given the whole words a real destination carries.
              val refOut = roots.map(_ => makeOutput(arena, length))
              val wordOut = roots.map(_ =>
                makeOutput(arena, length, ((length + 63L) / 64L) * 8L))
              val ctx = s"$shape at $lanes lanes, length $length, $nullState"
              for ((kernel, outs) <- Seq(refKernel -> refOut, wordKernel -> wordOut)) {
                val dstData = roots.zip(outs).map { case (root, out) =>
                  if (root.isInstanceOf[Cond]) 0L else out._1.address()
                }
                val status = kernel.run(Array(col.data.address()),
                  Array(col.validityAddress(length)), Array(col.nullCount),
                  dstData.toArray, outs.map(_._2.address()).toArray, lits, length)
                assert(status === 0, s"$ctx: the kernel declined a batch it should compute")
              }
              for ((root, i) <- roots.zipWithIndex) {
                for (b <- 0L until (length + 7) / 8L) {
                  assert(
                    wordOut(i)._2.get(ValueLayout.JAVA_BYTE, b) ===
                      refOut(i)._2.get(ValueLayout.JAVA_BYTE, b),
                    s"$ctx: output $i validity byte $b differs")
                }
                if (!root.isInstanceOf[Cond]) {
                  for (r <- 0 until length) {
                    val valid =
                      (refOut(i)._2.get(ValueLayout.JAVA_BYTE, r / 8L) & (1 << (r % 8))) != 0
                    if (valid) {
                      assert(
                        wordOut(i)._1.get(ValueLayout.JAVA_INT, r * 4L) ===
                          refOut(i)._1.get(ValueLayout.JAVA_INT, r * 4L),
                        s"$ctx: output $i row $r differs")
                    }
                  }
                }
              }
            } finally {
              arena.close()
            }
          }
        } finally {
          refLoader.release()
          wordLoader.release()
        }
      }
    }
  }

  /** The four body methods' sizes, which is how a change's blast radius is asserted here. */
  private def bodySizes(named: (String, Array[Byte])): Seq[Int] =
    Seq("loopDense0", "loopMasked0", "epilogueDense", "epilogueMasked")
      .map(VarkaEmitterTestSupport.codeSize(named._2, _))

  test("the word writer reaches the outputs that keep a per-group write, and only " +
      "those") {
    // The blast radius, asserted rather than described. An output task 45 fills once, and one
    // task 70's pass writes whole, must emit the same bytes under both arms - the word writer
    // has nothing to do for them - while an output that still writes per lane group must not.
    // This is also what stops the two arms collapsing into one kernel, which is exactly how
    // task 46's A/B silently began timing itself (see the task 76 test below).
    val lanes = 16
    val perGroup = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes)
    val byWord = perGroup.withValidityByWord(true)
    // `year(d)` on a dense batch is task 45's fill; on a masked batch with the bitmap pass on
    // it is task 70's whole-bitmap write. Neither keeps a per-group write, so both arms agree.
    val year: VarkaVectorIR = new Year(new ColumnRef(0))
    assert(bodySizes(emitMulti(Seq(year), 1, 0, perGroup)) ===
      bodySizes(emitMulti(Seq(year), 1, 0, byWord)),
      "an output the driver writes must not change under the word writer")
    // A blend's word is computed per lane group, so it is unserved by construction and keeps
    // the write - both bodies must differ.
    val blend: VarkaVectorIR = new IfElse(
      new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0)),
      new AddDays(new ColumnRef(0), new LiteralSlot(0)),
      new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    assert(bodySizes(emitMulti(Seq(blend), 1, 1, perGroup)) !==
      bodySizes(emitMulti(Seq(blend), 1, 1, byWord)),
      "a per-group write must change under the word writer, or the A/B times one kernel twice")
    // And a filter, whose per-group OR survives task 45 in the dense body too.
    val filter: VarkaVectorIR = new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0))
    assert(bodySizes(emitMulti(Seq(filter), 1, 1, perGroup)) !==
      bodySizes(emitMulti(Seq(filter), 1, 1, byWord)),
      "a selection root must change under the word writer")
    // A width the accumulator's arithmetic cannot serve falls back to the per-group form
    // rather than emitting something subtly wrong. At 64 lanes a group is a whole word and the
    // mask `(1L << lanes) - 1` is zero, because Java shifts modulo 64 - the arm would write
    // nothing but zeros. No int lane count this JVM offers reaches it, which is exactly why it
    // is worth an assertion: the guard is unreachable today and has to survive a wider one.
    val wide = VarkaEmitOptions.DEFAULTS.withLanesOverride(64)
    assert(bodySizes(emitMulti(Seq(blend), 1, 1, wide)) ===
      bodySizes(emitMulti(Seq(blend), 1, 1, wide.withValidityByWord(true))),
      "a 64-lane group must not word-write, since its lane mask would be zero")
  }

  test("the write-count ladder really is one shape family, so its steps are runtime") {
    // Read the ladder's own emissions before reading its numbers. PLAN_TASK_76.md 3.2 built
    // these four rungs to "hold the shape family constant and vary only the count", and task 47
    // measured a step at k=3 that neither task's model predicts: both arms that write per lane
    // group fall away sharply there while the word writer does not. The first thing to rule out
    // is a layout change - a rung crossing GROUP_BUDGET into two loop methods would pay every
    // per-method cost twice, and no rule could be fitted across that.
    //
    // It does not happen. All four rungs emit one masked loop method and the body grows by a
    // steady ~130 bytes per write. So the k=3 step is a property of how the JVM runs these
    // bytes, not of which bytes are emitted - which is what points at task 46's mechanism, the
    // caller's node count crossing C2's inlining cutoff so that one more OR call stops being
    // inlined. The word writer has no call at that site to refuse, and its curve is smooth.
    // Asserted here so the next reader of either ladder meets the fact before the number.
    // Built exactly as the benchmark builds them - one literal slot per blend. Repeating one
    // slot instead would make the k roots the same tree, which CSE collapses to a single
    // output: the rungs would all be one write and the ladder would measure nothing.
    def rung(k: Int): Seq[VarkaVectorIR] = (0 until k).map { j =>
      new IfElse(
        new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(j)),
        new AddDays(new ColumnRef(0), new LiteralSlot(j)),
        new SubDays(new ColumnRef(0), new LiteralSlot(j)))
    }
    def loopMethods(k: Int): Int =
      methodNames(emitMulti(rung(k), 1, 4, VarkaEmitOptions.DEFAULTS))
        .count(_.startsWith("loopMasked"))
    assert((1 to 4).map(loopMethods) === Seq(1, 1, 1, 1),
      "a rung emitting two loop methods would pay every per-method cost twice")
    val bytes = (1 to 4).map(k =>
      VarkaEmitterTestSupport.codeSize(
        emitMulti(rung(k), 1, 4, VarkaEmitOptions.DEFAULTS)._2, "loopMasked0"))
    val steps = bytes.sliding(2).map(p => p(1) - p(0)).toSeq
    assert(steps.forall(step => step > 100 && step < 160),
      s"the rungs should grow by one write's worth of bytes each: $bytes (steps $steps)")
  }

  test("every arm of the width-specialisation A/B still emits two different kernels") {
    // The failure this task is downstream of, made loud. Task 70's pass removed the per-group
    // validity call for a served root, which left both of task 46's arms emitting the same
    // bytes - each pair timed one kernel against itself, and the committed numbers said so for
    // a regeneration before anyone noticed. The arms were rebuilt on task 70's per-group
    // reference variant; this is the assertion that they stay rebuilt.
    //
    // Asserted on the loop methods rather than the whole class, since `emitMulti` gives each
    // class a fresh name and the name is in the bytes.
    val col = new ColumnRef(0)
    val perGroup = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(false)
    val general = perGroup.withValidityByWidth(false)
    def layout(bytes: (String, Array[Byte])): Seq[(String, Int)] =
      methodNames(bytes).filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked"))
        .sorted.map(m => m -> VarkaEmitterTestSupport.codeSize(bytes._2, m))

    // Every shape the parity file pairs for this A/B, with the options each arm is built from.
    val pairs = Seq[(String, Seq[VarkaVectorIR], Int, Int, VarkaEmitOptions, VarkaEmitOptions)](
      ("year", Seq[VarkaVectorIR](new Year(col)), 1, 0, perGroup, general),
      ("year, dense arm", Seq[VarkaVectorIR](new Year(col)), 1, 0,
        perGroup.withDenseValidityOnce(false), general.withDenseValidityOnce(false)),
      ("dayofweek", Seq[VarkaVectorIR](new DayOfWeek(col)), 1, 0, perGroup, general),
      ("year+month+day+quarter, shared",
        Seq[VarkaVectorIR](new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col)),
        1, 0, perGroup, general),
      ("filter d < literal",
        Seq[VarkaVectorIR](new Compare(CompareOp.LT, col, new LiteralSlot(0))), 1, 1,
        VarkaEmitOptions.DEFAULTS, general))
    for ((name, roots, inputs, lits, specialised, other) <- pairs) {
      assert(layout(emitMulti(roots, inputs, lits, specialised))
        !== layout(emitMulti(roots, inputs, lits, other)),
        s"$name: the two arms emit the same loop methods, so their benchmark pair times one " +
          "kernel against itself - which is exactly what task 70 did to this A/B once")
    }

    // The filter pair is the one whose two arms differ in two flags nominally, `DEFAULTS`
    // against `perGroupWrite.withValidityByWidth(false)`. `validityByBitmap` should be inert
    // for a `Cond` root, since the bitmap pass never serves one - asserted here rather than
    // assumed, because if it is not inert that pair measures two changes at once.
    val cond = Seq[VarkaVectorIR](new Compare(CompareOp.LT, col, new LiteralSlot(0)))
    assert(layout(emitMulti(cond, 1, 1, VarkaEmitOptions.DEFAULTS))
      === layout(emitMulti(cond, 1, 1, perGroup)),
      "validityByBitmap is not inert for a Cond root, so the filter A/B varies two things")
  }

  test("the specialised helpers answer what the general pair answered") {
    // The correctness statement, and the only one that matters: results identical under both
    // settings, at every null pattern and every length where the last byte is partial. The
    // helpers' own equivalence is pinned in the engine's VarkaVectorSupportWidthTest; this is
    // the emitted loop calling them with the rows and words it really produces.
    // On the per-group reference arm, for the reason the naming tests above give: both of
    // these roots are served by task 70's bitmap pass, so under the shipped default neither
    // arm makes a per-group validity call and the two would be the same kernel - a
    // self-comparison that could not fail. The default path's own coverage of these helpers
    // is the declined-root test below, where the per-group write survives.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](new Year(col), new DayOfWeek(col))
    val lengths = Seq(1, 7, 8, 9, 15, 16, 17, 63, 64, 65, 1000, 4095)
    def inRangeDays(c: Int, i: Int): Int = 19000 + (i % 9973)
    for (byWidth <- Seq(true, false)) {
      checkMatrix(roots, 1, Array.empty[Int], lengths, nullPatterns.map(p => Seq(p._2)),
        data = inRangeDays, ctx = s"validityByWidth=$byWidth",
        options = VarkaEmitOptions.DEFAULTS.withValidityByWidth(byWidth)
          .withValidityByBitmap(false))
    }
  }

  test("the specialised helpers are still reached under the bitmap pass default") {
    // What the two A/B tests above cannot check once they run on the reference arm: that the
    // width-specialised writer is still emitted, and still right, on the shipped default. A
    // root the bitmap pass declines is what keeps a per-group write there - here a tree that
    // mixes the two operators, which no chain of one operator can fold - so the helpers are
    // named and the results compared with `validityByBitmap` left on.
    val mixed = new DateDiff(new Greatest(new ColumnRef(0), new ColumnRef(1)),
      new Greatest(new ColumnRef(2), new ColumnRef(3)))
    assert(VarkaLoopEmitter.bitmapPassCounts(Seq[VarkaVectorIR](mixed).asJava, 4, 0,
      VarkaEmitOptions.DEFAULTS) === Array(0, 1), "the fixture is meant to be declined")
    for ((lanes, _) <- Seq(2 -> 64, 4 -> 128, 8 -> 256, 16 -> 512)) {
      val bytes = emitMulti(Seq[VarkaVectorIR](mixed), 4, 0,
        VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes))._2
      val called = VarkaEmitterTestSupport.invokedNames(bytes, support).asScala
      assert(called.contains(s"orValidityBitsAt$lanes"),
        s"$lanes lanes: the default path lost the specialised writer: $called")
    }
    for (byWidth <- Seq(true, false)) {
      checkMatrix(Seq(mixed), 4, Array.empty[Int], Seq(17, 64, 65, 1000, 4095),
        nullPatterns.map(p => Seq(p._2, p._2, p._2, p._2)),
        ctx = s"declined root, validityByWidth=$byWidth",
        options = VarkaEmitOptions.DEFAULTS.withValidityByWidth(byWidth))
    }
  }

  test("a Cond root's selection bitmap is identical under both settings") {
    // The shape this task helps that task 45 could not: a filter kernel ORs its selection
    // bitmap per lane group in both bodies, because those bits are computed rather than known.
    // Identical bitmaps under both settings is what says the specialised writer's lane mask is
    // right where the word carries bits above the group.
    val root = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1))
    for (byWidth <- Seq(true, false)) {
      checkMatrix(Seq(root), 2, Array.empty[Int], Seq(17, 64, 65, 1000, 4095),
        nullPatterns.map(p => Seq(p._2, p._2)), ctx = s"cond, validityByWidth=$byWidth",
        options = VarkaEmitOptions.DEFAULTS.withValidityByWidth(byWidth))
    }
  }

  test("the validity OR before the compute answers what the OR after it answered") {
    // The order moved so C2 meets the OR helper before the body's intrinsics have spent its
    // node budget; the bytes are the same either way and the results must be. The second root
    // is the shape that caught the first version of this: a Year over an IfElse, whose word
    // aliases the blend's *computed* slot and so is not known before the compute - reading it
    // early was a frame with no such local, and the verifier said so. Both settings, every
    // null pattern, lengths with a partial last byte, the masked path forced. Not length 1:
    // forcing the masked path sets the null count to 1, and a null count equal to the length is
    // the all-null column by the harness's own contract (validity address 0L), which the oracle
    // does not model - so that one length fails under either setting, for a reason that is not
    // this test's.
    val col = new ColumnRef(0)
    val lit = new LiteralSlot(0)
    val blend = new IfElse(new Compare(CompareOp.LT, col, lit), new AddDays(col, lit), col)
    val roots = Seq[VarkaVectorIR](new Year(col), new Year(blend), new Greatest(col, blend))
    def inRangeDays(c: Int, i: Int): Int = 19000 + (i % 9973)
    // On the per-group reference arm: `validityOrFirst` moves the per-group OR, and under
    // task 70's default the first root makes no such OR at all while the other two hold
    // computed words that were never known before the compute - so all three arms would be
    // one kernel and the comparison would be with itself.
    for (orFirst <- Seq(true, false)) {
      checkMatrix(roots, 1, Array(3), Seq(7, 8, 9, 15, 16, 17, 63, 64, 65, 1000, 4095),
        nullPatterns.map(p => Seq(p._2)), data = inRangeDays, forceMasked = true,
        ctx = s"validityOrFirst=$orFirst",
        options = VarkaEmitOptions.DEFAULTS.withValidityOrFirst(orFirst)
          .withValidityByBitmap(false))
    }
  }

  test("an emission for a foreign width still computes that width's answers") {
    // lanesOverride exists so one JVM can exercise every arm, which is only honest if the
    // emitted class is self-consistent: it carries the species its helper names were chosen
    // for, so it computes correctly (slowly, if the hardware is narrower) rather than writing
    // validity for a width its vectors do not have.
    val col = new ColumnRef(0)
    for (lanes <- Seq(2, 4, 8, 16)) {
      checkMatrix(Seq[VarkaVectorIR](new AddDays(col, new LiteralSlot(0))), 1, Array(3),
        Seq(17, 64, 65, 1000), nullPatterns.map(p => Seq(p._2)), ctx = s"lanesOverride=$lanes",
        options = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes))
    }
  }

  test("the lanewise-DIV floorMod reference variant agrees with the shipped magic multiply") {
    val roots = Seq[VarkaVectorIR](new DayOfWeek(new ColumnRef(0)))
    val extremes = Array(Int.MinValue, Int.MaxValue, -1, 0, -7, 7)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 31 - 7000
    checkMatrix(roots, 1, Array.empty[Int], Seq(64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "div-variant",
      options = VarkaEmitOptions.DEFAULTS.withFloorMod7(VarkaEmitOptions.FloorMod7.DIV))
  }

  test("the digit-sum floorMod reference variant agrees with the shipped magic multiply") {
    // The task 11 lowering, kept as a reference: same matrix as the shipped path's own test,
    // with the 15-bit fold boundaries among the extremes.
    val roots = Seq[VarkaVectorIR](
      new DayOfWeek(new ColumnRef(0)), new WeekDay(new ColumnRef(0)))
    val extremes = Array(Int.MinValue, Int.MaxValue, Int.MinValue + 1, Int.MaxValue - 1,
      -1, 0, 1, -7, 7, -8, 8, 32767, 32768, -32768, -32769)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 997 - 300000
    checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = days, ctx = "digit-sum-variant",
      options = VarkaEmitOptions.DEFAULTS.withFloorMod7(VarkaEmitOptions.FloorMod7.DIGIT_SUM))
  }

  // -------------------------------------------------------------------------------------------
  // Task 88: the calendar prefix's constant divisions through the double lane.
  // -------------------------------------------------------------------------------------------

  private val divisionForms = VarkaEmitOptions.Division.values().toSeq

  /**
   * Roots reaching every division site the prefix and its tails contain: the era step and the
   * century/year split (or the Julian map in their place), the month and day-of-month steps, the
   * quarter, and the ISO week. `add_months` and `make_date` carry the two that are not in the
   * prefix - the month arithmetic's `/12` and the recomposition's `/400` and `/100` - and have
   * their own tests below, because their inputs are not a date column.
   */
  private val divisionRoots = Seq[VarkaVectorIR](
    new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
    new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
    new DayOfYear(new ColumnRef(0)), new LastDay(new ColumnRef(0)),
    new TruncDate(new ColumnRef(0), TruncLevel.YEAR),
    new TruncDate(new ColumnRef(0), TruncLevel.MONTH),
    new TruncDate(new ColumnRef(0), TruncLevel.QUARTER))

  test("the calendar extractions agree with LocalDate under all three division lowerings, " +
      "on both prefix forms") {
    // The double forms convert each int vector into two double vectors, divide there and convert
    // back. They are exact over the range each site's dividend stays in - which is what
    // sql/varka/plans/verify_double_division.py establishes - so agreeing with LocalDate here is
    // the check that the conversion parts, the join and the deny-list are all right at once. A
    // part index off by one would put a quotient in the wrong lane and show up as a wrong date,
    // not as a crash.
    for (form <- divisionForms; julian <- Seq(true, false)) {
      checkMatrix(divisionRoots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"division=$form julianMap=$julian",
        options = VarkaEmitOptions.DEFAULTS.withDivision(form).withJulianMap(julian))
    }
  }

  test("the double-lane divisions compute the same dates at every vector width") {
    // The double half of a 512-bit register holds four lanes and of a 128-bit one holds two, so
    // the lane count the conversion splits into is not the lane count the loop runs at. Each
    // width is emitted and run in turn, on the same boundary dates, so a width whose halves do
    // not tile the vector would answer differently rather than silently.
    for (form <- divisionForms; lanes <- Seq(2, 4, 8, 16)) {
      checkMatrix(divisionRoots, 1, Array.empty[Int], Seq(17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"division=$form lanes=$lanes",
        options = VarkaEmitOptions.DEFAULTS.withDivision(form).withLanesOverride(lanes))
    }
  }

  test("weekofyear agrees with IsoFields under all three division lowerings") {
    // The ISO week is the one site whose divisor is 7, and it sits behind the Thursday shift
    // rather than in the prefix proper.
    val thursday = new ThursdayOf(new ColumnRef(0))
    for (form <- divisionForms) {
      checkMatrix(Seq[VarkaVectorIR](new WeekOfYear(thursday)), 1, Array.empty[Int],
        Seq(1, 13, 17, 64, 1000), nullPatterns.map(p => Seq(p._2)), data = isoWeekDay,
        ctx = s"weekofyear division=$form",
        options = VarkaEmitOptions.DEFAULTS.withDivision(form))
    }
  }

  test("add_months agrees under all three division lowerings") {
    // The month arithmetic's own `/12`, which no extraction reaches, over a count range the
    // magic multiply's bound admits.
    val root = new AddMonths(new ColumnRef(0), new ColumnRef(1))
    def data(c: Int, i: Int): Int = if (c == 0) calendarBoundaryDay(0, i) else i % 61 - 30
    for (form <- divisionForms) {
      checkMatrix(Seq[VarkaVectorIR](root), 2, Array.emptyIntArray, Seq(1, 13, 17, 64, 1000),
        combos(2), data = data, ctx = s"add_months division=$form",
        options = VarkaEmitOptions.DEFAULTS.withDivision(form))
    }
  }

  test("make_date and the recomposing trunc agree under all three division lowerings") {
    // Both reach `emitDaysFromCivil`, whose `/400` and `/100` share one multiplier and differ
    // only in the shift - the pair the division table exists to keep apart.
    for (form <- divisionForms) {
      val options = VarkaEmitOptions.DEFAULTS.withDivision(form)
      checkMatrix(Seq(makeDateNull), 3, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        combos(3), data = tripleData(makeDateValid), ctx = s"make_date division=$form",
        options = options)
      checkMatrix(truncRoots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = calendarBoundaryDay,
        ctx = s"trunc recompose division=$form",
        options = options.withTruncDate(VarkaEmitOptions.TruncDateForm.RECOMPOSE))
    }
  }

  /** The ops one emitted body runs against a given vector class, counted off the class file. */
  private def opsOn(bytes: Array[Byte], owner: String): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0", s"jdk.incubator.vector.$owner")

  /**
   * How many `convertShape` calls a body makes - the lane-width conversions, and only those.
   * The reinterprets the AVX2 division uses are declared on `Vector` too and are not
   * conversions: they reread the same bits at another type, which is the whole point of that
   * form, so a count that included them would report the form it exists to distinguish.
   */
  private def convertShapes(bytes: Array[Byte]): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0", "jdk.incubator.vector.Vector",
      java.util.List.of("reinterpretAsDoubles", "reinterpretAsLongs"))

  /**
   * How many of a body's divisions took a double form: each emits exactly one `mul` or `div` per
   * half, so the `DoubleVector` count is twice the number of divisions that were lowered.
   */
  private def doubleDivisions(bytes: Array[Byte]): Int = {
    val halves = opsOn(bytes, "DoubleVector")
    assert(halves % 2 === 0, s"a double division emits two halves, saw $halves")
    // Each division also converts twice in and twice out, all four on `Vector` itself.
    assert(convertShapes(bytes) === halves * 2,
      s"expected ${halves * 2} conversions for $halves halves")
    halves / 2
  }

  test("a double-lane division costs seven ops where the magic costs two, and kills the carry") {
    // `year` over the shipped prefix divides three times - the era step, the Julian century and
    // the Julian year - and each of the three rounds down and is corrected by a carry. The magic
    // form spends two `IntVector` ops on the division and three more on the carry; the double
    // form spends four conversions, two divides and one `or` to rejoin the halves, and no carry
    // at all, because its quotient is exact.
    //
    // So the trade is not "seven against two" per division in isolation: against the magic form
    // plus its carry it is seven against five, and the seven move off the 32-bit multiplier.
    // Whether that wins is the A/B this option exists to run; that it is what gets emitted is
    // what these counts pin.
    val year = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    def bytes(form: VarkaEmitOptions.Division): Array[Byte] =
      emitMulti(year, 1, 0, VarkaEmitOptions.DEFAULTS.withDivision(form))._2

    val magic = bytes(VarkaEmitOptions.Division.MAGIC)
    assert(opsOn(magic, "IntVector") === 34)
    assert(doubleDivisions(magic) === 0)

    val div = bytes(VarkaEmitOptions.Division.DOUBLE_DIV)
    assert(doubleDivisions(div) === 3)
    // Each division loses its magic multiply and shift and gains one `or` (-1 each), and each of
    // the three carries the exact quotient makes dead goes away (-3 each).
    assert(opsOn(div, "IntVector") === 34 - 3 * 1 - 3 * 3)
  }

  test("the era step's /146097 falls back to the magic form under the reciprocal, and the " +
      "Julian century's does not") {
    // Both divide by 146097 and they answer differently, which is the whole reason the deny-list
    // is keyed on the site rather than on the divisor: the era step's dividend is any biased
    // day, and 146097 itself is one of them, where multiplying by fl(1/146097) rounds below the
    // integer; the Julian century's dividends are the values congruent to 3 mod 4, and that
    // multiple is not among them. `sql/varka/plans/verify_double_division.py` is what decides
    // this, and this test is the emitter obeying it.
    //
    // The narrowed prefix divides three times and the Julian one also three times - the era step
    // in both, then either the century and year of century, or the Julian century and year. If
    // the reciprocal were refused by divisor, the Julian shape would lose two divisions rather
    // than one.
    val year = Seq[VarkaVectorIR](new Year(new ColumnRef(0)))
    for (julian <- Seq(false, true)) {
      def bytes(form: VarkaEmitOptions.Division): Array[Byte] =
        emitMulti(year, 1, 0,
          VarkaEmitOptions.DEFAULTS.withDivision(form).withJulianMap(julian))._2
      assert(doubleDivisions(bytes(VarkaEmitOptions.Division.DOUBLE_DIV)) === 3,
        s"julianMap=$julian")
      assert(doubleDivisions(bytes(VarkaEmitOptions.Division.DOUBLE_RECIP)) === 2,
        s"julianMap=$julian: exactly the era step should fall back")
    }
  }

  test("the shipped default emits no double-lane ops at all") {
    // The option is off by default, so no production kernel converts anything: the emitted bytes
    // for every calendar shape are what they were before this existed, which is also what keeps
    // VarkaEmittedBytesSuite's registered hashes valid without regenerating them.
    val bytes = emitMulti(divisionRoots, 1, 0)._2
    assert(doubleDivisions(bytes) === 0)
    assert(VarkaEmitOptions.DEFAULTS.division() === VarkaEmitOptions.Division.MAGIC)
  }

  test("the emitted calendar kernels agree over the whole covered range under both double " +
      "division forms (opt-in: -Dvarka.sweep=true; task 88)") {
    // The bounded tests above run the double forms over a boundary list; this runs them over
    // every day the prefix covers, which is the only check at the resolution the deny-list was
    // decided at. `verify_double_division.py` proves the arithmetic exact over each site's
    // range; this proves the emitter divides the range it was proved over - a dividend that
    // escaped its bound, or a reciprocal admitted where the script refused it, is wrong on a
    // handful of days out of sixteen million and invisible to anything narrower.
    assume(System.getProperty("varka.sweep") == "true",
      "set -Dvarka.sweep=true to sweep the emitted kernels")
    val fields = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)),
      new DayOfYear(new ColumnRef(0)))
    val doubleForms =
      Seq(VarkaEmitOptions.Division.DOUBLE_RECIP, VarkaEmitOptions.Division.DOUBLE_DIV)
    // Both prefix forms, because they divide by 146097 at different sites and the reciprocal is
    // admitted at one and refused at the other - the single most load-bearing row of the table.
    for (form <- doubleForms; julian <- Seq(true, false)) {
      val options = VarkaEmitOptions.DEFAULTS.withDivision(form).withJulianMap(julian)
      sweepCalendar(fields, options)
      sweepTrunc(options)
      sweepLastDay(options)
    }
  }

  // -------------------------------------------------------------------------------------------
  // Task 89: a constant division with no magic form, over the whole int32 range.
  // -------------------------------------------------------------------------------------------

  test("a constant division matches Java's `/` over the whole int32 range, at every width") {
    // `extract(YEAR FROM ym)` is `months / 12` over a month count nothing bounds, so the
    // calendar's range-narrowed magic cannot serve it and this node converts through double
    // lanes instead. Two things are being checked at once and they fail differently: that the
    // quotient is exact - which `sql/varka/plans/verify_ym_division.py` proves over all 2^32
    // counts, and which would break here at the extremes first - and that it *truncates toward
    // zero* rather than flooring, which is what a magic would have done and what would show up
    // only on negative dividends with a remainder.
    val root = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 12))
    val extremes = Array(Int.MinValue, Int.MinValue + 1, Int.MaxValue, Int.MaxValue - 1,
      -1, 0, 1, -11, 11, -12, 12, -13, 13, -49151, 49151, -49152, 49152)
    def months(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 7919 - 1000000
    for (lanes <- Seq(0, 2, 4, 8, 16)) {
      checkMatrix(root, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = months, ctx = s"divc/12 lanes=$lanes",
        options = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes))
    }
  }

  test("a constant division is emitted through the double lane whatever the division option " +
      "says, because it has no other lowering") {
    // The `division` option chooses among the lowerings the *calendar* has. This node has one,
    // so the option cannot turn it off - and if it ever did, the kernel would compute nothing
    // rather than compute something slower.
    val root = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 12))
    for (form <- VarkaEmitOptions.Division.values()) {
      val bytes = emitMulti(root, 1, 0, VarkaEmitOptions.DEFAULTS.withDivision(form))._2
      assert(doubleDivisions(bytes) === 1, s"division=$form")
    }
  }

  test("a constant division by zero or by -1 is refused rather than emitted") {
    // Zero has no quotient at all, and -1 overflows at Integer.MinValue - the one input where
    // Java's `/` throws rather than answering. Both are the row engine's to raise, so the
    // shapes are refused where they are built rather than emitted as something plausible.
    val col = new ColumnRef(0, LaneType.INT)
    val zero = intercept[IllegalArgumentException](new ConstDivide(col, 0))
    assert(zero.getMessage.contains("division by zero"), zero.getMessage)
    val minusOne =
      intercept[IllegalArgumentException](emitMulti(Seq(new ConstDivide(col, -1)), 1, 0))
    assert(minusOne.getMessage.contains("overflows at Integer.MIN_VALUE"), minusOne.getMessage)
  }

  test("a constant division by one emits no conversion at all") {
    // The identity. Nothing requires the compiler to have folded it, and emitting a conversion
    // round trip for it would be a silent cost on a shape that does nothing.
    val root = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 1))
    assert(doubleDivisions(emitMulti(root, 1, 0)._2) === 0)
    checkMatrix(root, 1, Array.empty[Int], Seq(17, 1000), nullPatterns.map(p => Seq(p._2)),
      ctx = "divc/1")
  }

  // -------------------------------------------------------------------------------------------
  // Task 88 step 3: the same division at 64-bit lanes, where the conversion is same-width.
  // -------------------------------------------------------------------------------------------

  /**
   * `doubleDivisions`' twin at the long lane. A 64-bit lane and a double lane are the same
   * width, so one division is one divide and one conversion each way - there is no second half
   * to count and nothing to rejoin.
   */
  private def longDoubleDivisions(bytes: Array[Byte]): Int = {
    val divides = opsOn(bytes, "DoubleVector")
    assert(convertShapes(bytes) === divides * 2,
      s"expected ${divides * 2} conversions for $divides divisions")
    divides
  }

  /**
   * The dividends worth driving a division by `d` over: zero, both signs of the divisor's own
   * neighbourhood - where truncation and floor differ and where a reciprocal would fail at an
   * exact multiple - and both ends of the range the lowering is exact over. Derived from the
   * divisor rather than written out, so a divisor added to the list below cannot end up
   * exercised only far from its own multiples.
   */
  private def dividendsAround(d: Long): Array[Long] = {
    val bound = ConstDivide.EXACT_DIVIDEND_BOUND - 1
    Array(0L, 1L, -1L, d, d - 1, d + 1, -d, -(d - 1), -(d + 1), 2 * d, 2 * d - 1, -(2 * d) + 1,
      bound, -bound, bound - 1, -(bound - 1)).map(v => math.max(-bound, math.min(bound, v)))
  }

  test("a long-lane constant division matches Java's `/` over the range it is exact on") {
    // The divisors task 102 and 103 need, each over its own neighbourhood and both ends of the
    // exact range. Two things fail differently here: precision, which breaks at the ends of the
    // range first, and truncation toward zero, which a floor-producing lowering gets wrong only
    // on negative dividends with a remainder - hence both signs of every value.
    val col = new ConstDivide(new ColumnRef(0, LaneType.LONG), 1)
    // The level is pinned rather than inherited. `DEFAULTS.useAVX` is the machine's, so
    // without this the test would check the conversion form on an AVX-512 host and the magic
    // form on every other - covering one lowering twice and the other never, on a machine
    // nobody chose. The magic form has its own test below, at its own pinned level.
    val converting = VarkaEmitOptions.DEFAULTS.withUseAVX(3)
    val divisors = Seq(
      3_600_000_000_000L,   // hour(t), nanos per hour
      60_000_000_000L,      // minute(t) step 1
      1_000_000_000L,       // second(t) step 1
      1_000_000L,           // time_trunc to milliseconds
      1_000L,               // t1 - t2, nanos per micro
      86_400_000_000L,      // extract(DAY FROM dt), micros per day
      60L,                  // minute(t) and second(t) step 2
      -60L)                 // a negative divisor, whose quotient truncates the other way
    for (d <- divisors; lanes <- Seq(2, 8)) {
      val root = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.LONG), d))
      val vs = dividendsAround(d)
      checkLongMatrix(root, 1, Array.empty[Long], Seq(1, 7, 17, 64, 129), combos(1),
        (_, i) => vs(i % vs.length), s"long divc/$d", lanes, converting)
    }
    assert(col.divisor() === 1)
  }

  test("the AVX2 form computes the same quotients as the conversions, at both signs") {
    // A host whose L2D and D2L do not intrinsify takes a magic-number form instead: no
    // conversion instruction at all, a floor built by hand out of a compare and a masked
    // subtract, and the sign applied afterwards because the identity needs a non-negative
    // operand and produces a floor where Java truncates. Every one of those is a place the two
    // forms could disagree, so they are driven over the same dividends and required to agree
    // with the same reference. The option is set explicitly rather than inherited: the
    // arithmetic is correct on any machine, and it is the lowering that is under test.
    val avx2 = VarkaEmitOptions.DEFAULTS.withUseAVX(2)
    val divisors = Seq(3_600_000_000_000L, 1_000_000_000L, 1_000L, 86_400_000_000L, 60L, -60L)
    for (d <- divisors; lanes <- Seq(2, 8)) {
      val root = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.LONG), d))
      val vs = dividendsAround(d)
      checkLongMatrix(root, 1, Array.empty[Long], Seq(1, 7, 17, 64, 129), combos(1),
        (_, i) => vs(i % vs.length), s"avx2 divc/$d", lanes, avx2)
    }
  }

  test("the AVX2 form is chosen by the level, and only at the long lane") {
    // What selects it, stated as bytes rather than as intent. `convertShape` is the conversion
    // form's signature call and the magic form has none; the int lane has no magic form at all,
    // since its dividend is exactly representable and the conversion is what it is built on.
    val long64 = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.LONG), 1000))
    val int32 = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 12))
    def converts(roots: Seq[VarkaVectorIR], options: VarkaEmitOptions): Int =
      convertShapes(emitMulti(roots, 1, 0, options)._2)
    val defaults = VarkaEmitOptions.DEFAULTS
    assert(converts(long64, defaults.withUseAVX(2)) === 0, "AVX2 emits no conversion")
    assert(converts(long64, defaults.withUseAVX(3)) === 2, "AVX-512 converts in and out")
    // A machine that reports no level has told us nothing against its converts, so it keeps
    // them rather than paying for a form it may not need.
    assert(converts(long64, defaults.withUseAVX(VarkaEmitOptions.USE_AVX_UNKNOWN)) === 2)
    assert(converts(int32, defaults.withUseAVX(2)) === 4, "the int lane is not affected")

    // And what the form costs, counted from the bytes rather than from the plan that sketched
    // it: seven double ops (the two halves of the identity, the divide, the round and its
    // correction), two reinterprets, and five long ops - fourteen against the conversion form's
    // three. `PLAN_TASK_88.md` 3.3 registered nine, before the signed case was decided;
    // section 9.2 records the correction.
    //
    // The long count excludes the loads and stores, which belong to the body and not to the
    // division: what it pins is the sign handling, which is the part the correction note is
    // about and the part a reader might think is removable.
    val bytes = emitMulti(long64, 1, 0, defaults.withUseAVX(2))._2
    assert(opsOn(bytes, "DoubleVector") === 7, "the double half of the magic form")
    assert(opsOn(bytes, "Vector") === 2, "two reinterprets and no conversion")
    val longOps = VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0",
      "jdk.incubator.vector.LongVector",
      java.util.List.of("fromMemorySegment", "intoMemorySegment", "broadcast"))
    assert(longOps === 5, "the magnitude, the identity's OR and mask, and the sign tail")
  }

  test("a long-lane constant division converts once each way, where the int lane converts twice") {
    // The op counts of `PLAN_TASK_88.md` 3.3: three operations at the long lane against seven at
    // the int one. The saving is structural rather than incidental - an int vector has twice the
    // lanes of the double vector it converts into, so it needs two halves and a join, while a
    // 64-bit lane pairs one to one.
    // Both levels are named. `DEFAULTS.useAVX` is whatever the machine reports, so emitting
    // with it would assert the conversion form's shape against whichever form the host picked.
    val converting = VarkaEmitOptions.DEFAULTS.withUseAVX(3)
    val long64 = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.LONG), 1000))
    val int32 = Seq[VarkaVectorIR](new ConstDivide(new ColumnRef(0, LaneType.INT), 12))
    assert(longDoubleDivisions(emitMulti(long64, 1, 0, converting)._2) === 1)
    assert(doubleDivisions(emitMulti(int32, 1, 0, converting)._2) === 1)
    // The counter above is what makes the difference explicit: the int lane spends two divides
    // and four conversions on one division, the long lane one and two.
    assert(opsOn(emitMulti(long64, 1, 0, converting)._2, "DoubleVector") === 1)
    assert(opsOn(emitMulti(int32, 1, 0, converting)._2, "DoubleVector") === 2)
  }

  test("a long-lane constant division refuses the divisors that have no quotient") {
    // The int lane's refusals, restated at the width they now apply to. The -1 message names
    // the lane's own most negative value, because that is the input it is about.
    val col = new ColumnRef(0, LaneType.LONG)
    val zero = intercept[IllegalArgumentException](new ConstDivide(col, 0))
    assert(zero.getMessage.contains("division by zero"), zero.getMessage)
    val minusOne =
      intercept[IllegalArgumentException](emitMulti(Seq(new ConstDivide(col, -1)), 1, 0))
    assert(minusOne.getMessage.contains("overflows at Long.MIN_VALUE"), minusOne.getMessage)
    // And a divisor the int lane cannot hold is a mistake in the tree rather than a division
    // whose every quotient is zero.
    val tooWide = intercept[IllegalArgumentException](
      new ConstDivide(new ColumnRef(0, LaneType.INT), 1L << 40))
    assert(tooWide.getMessage.contains("needs an int divisor"), tooWide.getMessage)
  }

  // -------------------------------------------------------------------------------------------
  // Task 102: a range guard at the long lane, which is what makes `TIME + INTERVAL` a decline
  // rather than a wrap where Spark throws.
  // -------------------------------------------------------------------------------------------

  test("a long-lane range guard passes the value through and declines the batch on a lane " +
      "outside its bounds, in the loop and in the epilogue") {
    // GuardedDay's twin at 64 bits, with bounds the node carries. Three things are checked
    // and fail differently: the value is unchanged where the guard holds; a single lane out of
    // range reports STATUS_CHRONO_RANGE, in a full lane group and in the masked tail; and a
    // null lane outside the bounds does not fire, since a null has no value to be out of range.
    val root = Seq[VarkaVectorIR](new GuardedRange(new ColumnRef(0, LaneType.LONG), 0L,
      86399999999999L))
    for (lanes <- Seq(2, 8)) {
      val (kernel, loader) = load(emitMulti(root, 1, 0,
        VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes)))
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
          VarkaFusedKernel.STATUS_CHRONO_RANGE, "a loop lane")
        // The same lane in the masked tail of a 17-row batch.
        assert(status(17, i => if (i == 16) -1L else 1L, none) ===
          VarkaFusedKernel.STATUS_CHRONO_RANGE, "an epilogue lane")
        // A null lane holding an out-of-range payload does not fire the guard.
        assert(status(64, i => if (i == 5) -1L else 1L, i => i == 5) === 0, "a null lane")
      } finally {
        loader.release()
      }
    }
  }

  test("a range guard at the int lane refuses bounds an int cannot hold, and an empty range " +
      "is refused where it is built") {
    val col = new ColumnRef(0, LaneType.INT)
    val wide = intercept[IllegalArgumentException](
      emitMulti(Seq(new GuardedRange(col, 0L, 1L << 40)), 1, 0))
    assert(wide.getMessage.contains("needs int bounds"), wide.getMessage)
    val empty = intercept[IllegalArgumentException](new GuardedRange(col, 5L, 4L))
    assert(empty.getMessage.contains("empty range"), empty.getMessage)
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

  test("IR outside the emitter's shape is rejected with a reason, not emitted wrong") {
    def rejects(body: => Unit, fragment: String): Unit = {
      val e = intercept[IllegalArgumentException](body)
      assert(e.getMessage.contains(fragment), s"message was: ${e.getMessage}")
    }
    rejects(emit(chain(VarkaLoopEmitter.MAX_CHAIN_DEPTH + 1),
      VarkaLoopEmitter.MAX_CHAIN_DEPTH + 1), "MAX_CHAIN_DEPTH")
    rejects(emit(new AddDays(new ColumnRef(1), new LiteralSlot(0)), 1), "column ordinal")
    rejects(emit(new AddDays(new ColumnRef(0), new LiteralSlot(1)), 1), "literal slot")
    // A column offset (task 38) is legal IR now - AddDays(ColumnRef, ColumnRef) no longer
    // throws; see "AddDays/SubDays with a column offset (task 38) match the reference
    // evaluator" above for its coverage.
    rejects(VarkaLoopEmitter.emit("t", java.util.List.of[VarkaVectorIR](), 1, 0),
      "no output chains")
    rejects(VarkaLoopEmitter.emit("t", java.util.List.of(addDays(0)), 0, 1), "numInputs")
    rejects(VarkaLoopEmitter.emit("t", java.util.List.of(addDays(0)),
      VarkaLoopEmitter.MAX_INPUTS + 1, 1), "numInputs")
    // 5 disjoint depth-13 chains hold 65 distinct ops, one past the total-size cap. The cap
    // counts nodes after CSE: the same 4 chains repeated as 8 outputs stay within it.
    val disjointChains = (0 until 5).map(k => chain(13, slotBase = k * 13))
    rejects(emitMulti(disjointChains, 1, 65), "MAX_FUSED_NODES")
    val (_, sharedOk) = emitMulti(
      disjointChains.take(4) ++ disjointChains.take(4), 1, 52)
    assert(sharedOk.nonEmpty)
    // Task 11: conditions are never values. (A condition as an output ROOT became legal in
    // task 21 - it emits a selection bitmap - so only the value positions reject now.)
    val cmp = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(0))
    rejects(emitMulti(Seq(new AddDays(cmp, new LiteralSlot(0))), 1, 1), "value position")
    rejects(emitMulti(Seq(new Greatest(new ColumnRef(0), cmp)), 1, 0), "value position")
  }

  test("a wrong descriptor fails naming the call, not as an anonymous VerifyError") {
    val named = emit(addDays(0), 1, VarkaEmitOptions.DEFAULTS.withMisdescribeAdd(true))
    // Member resolution is link-time work, so the class still verifies...
    assert(VarkaEmitterTestSupport.verify(named._2).isEmpty)
    val (kernel, loader) = load(named)
    try {
      val arena = Arena.ofConfined()
      try {
        // Long enough that the vector loop (where the wrong call sits) runs at any width.
        val length = 64
        val input = makeInput(arena, length, _ => false)
        val out = makeOutput(arena, length)
        val e = intercept[LinkageError] {
          kernel.run(
            Array(input.data.address()), Array(0L), Array(0),
            Array(out._1.address()), Array(out._2.address()), Array(1), length)
        }
        // ...and the first execution names the exact call the descriptor table got wrong.
        assert(e.isInstanceOf[NoSuchMethodError], s"got ${e.getClass}: ${e.getMessage}")
        assert(e.getMessage.contains("IntVector.add"), s"message was: ${e.getMessage}")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the emitted class unloads once the loader is released") {
    val queue = new ReferenceQueue[ClassLoader]()
    val (className, bytes) = emit(addDays(0), 1)
    var loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    val ref = new WeakReference[ClassLoader](loader, queue)
    loader.defineGeneratedClass(className, bytes)
    loader.loadClass(className)
    loader.release()
    // Drop the only strong reference; the frame slot must not pin the loader (the reason the
    // existing loader suite uses a var too).
    loader = null
    var collected = false
    var attempts = 0
    while (!collected && attempts < 50) {
      System.gc()
      collected = queue.remove(100) != null
      attempts += 1
    }
    assert(collected, "the loader (and with it the emitted class) was not collected")
    assert(ref.get() == null)
  }

  /** The committed line map of the every-node-type key; see the test that pins it. */
  private val pinnedLineMap = Seq(
    "1=col:0",
    "2=lit:0",
    "3=(cmp:LT 1 2)",
    "4=(cmp:EQ 1 2)",
    "5=(not 4)",
    "6=(or 3 5)",
    "7=(cmp:GE 1 2)",
    "8=(isNotNull 1)",
    "9=(and 7 8)",
    "10=(and 6 9)",
    "11=(addDays 1 2)",
    "12=(subDays 1 2)",
    "13=(greatest 11 12)",
    "14=(year 1)",
    "15=(month 1)",
    "16=(greatest 14 15)",
    "17=(dayOfMonth 1)",
    "18=(quarter 1)",
    "19=(lastDay 1)",
    "20=(truncDate:YEAR 1)",
    "21=(least 19 20)",
    "22=(least 18 21)",
    "23=(greatest 17 22)",
    "24=(least 16 23)",
    "25=(dayOfYear 1)",
    "26=(thursdayOf 1)",
    "27=(weekOfYear 26)",
    "28=(greatest 25 27)",
    "29=(greatest 24 28)",
    "30=(dayOfWeek 1)",
    "31=(dateDiff 29 30)",
    "32=(weekDay 1)",
    "33=(dayOfWeekIso 1)",
    "34=(least 32 33)",
    "35=(nextDay 1 2)",
    "36=(addMonths 1 2)",
    "37=(truncDateDynamic 1 1)",
    "38=(makeDate:NULL 1 2 2)",
    "39=(makeDate:ANSI 1 2 2)",
    "40=(least 38 39)",
    "41=(least 37 40)",
    "42=(least 36 41)",
    "43=(least 35 42)",
    "44=(least 34 43)",
    "45=(least 31 44)",
    "46=(if 10 13 45)").mkString("\n")

  /** The class's own LineNumberTable key, parsed back into line -> rendered IR node. */
  private def lineKey(bytes: Array[Byte]): Map[Int, String] = {
    val recorded = VarkaDebugInfoReader.lineMap(bytes)
    assert(recorded != null && recorded.nonEmpty, "the class recorded no line map")
    recorded.linesIterator.map { entry =>
      val parts = entry.split("=", 2)
      parts(0).toInt -> parts(1)
    }.toMap
  }

  test("emit rejects null options the way it rejects its other arguments") {
    // The other two argument checks throw IllegalArgumentException with a message; options
    // would otherwise have failed as a bare NPE partway through the analysis walk.
    val e = intercept[IllegalArgumentException] {
      VarkaLoopEmitter.emit("X", Seq[VarkaVectorIR](addDays(0)).asJava, 1, 1, null, null, null)
    }
    assert(e.getMessage.contains("options"), e.getMessage)
  }

  test("the shallow rendering of every node type is pinned, like the shape hash") {
    // The line map travels inside the class bytes and is read back by tooling with no live
    // session, so its rendering is a contract, not an implementation detail - and it used to
    // ride Record.toString, whose format no JDK promises. One key using all 24 node types (and
    // three CompareOps), so a change to any rendering, to the operand order, or to the
    // topological schedule fails here. If it does: make sure the change is intended, then
    // update the literal and say so in the task plan - the same rule as the pinned shape
    // hashes in VarkaShapeCacheSuite. Task 26 added the four calendar extractions and
    // re-pinned it (PLAN_TASK_26.md); task 33 added NextDay, task 40 added AddMonths, task 36
    // added LastDay, task 34 added DayOfYear and task 61 added TruncDateDynamic, each
    // re-pinning it again (PLAN_TASK_33.md, PLAN_TASK_40.md, PLAN_TASK_36.md, PLAN_TASK_34.md,
    // PLAN_TASK_61.md). Re-pinned from the failing
    // assertion's own output, never carried over from one side of a merge: a line map that is
    // right for one node set is wrong for the union of two.
    val col = new ColumnRef(0)
    val lit = new LiteralSlot(0)
    val cond = new And(
      new Or(
        new Compare(CompareOp.LT, col, lit),
        new Not(new Compare(CompareOp.EQ, col, lit))),
      new And(new Compare(CompareOp.GE, col, lit), new IsNotNull(col)))
    val chrono = new Greatest(
      new Least(
        new Greatest(new Year(col), new Month(col)),
        new Greatest(new DayOfMonth(col),
          new Least(new Quarter(col),
            new Least(new LastDay(col), new TruncDate(col, TruncLevel.YEAR))))),
      new Greatest(new DayOfYear(col), new WeekOfYear(new ThursdayOf(col))))
    val everyNode = new IfElse(
      cond,
      new Greatest(new AddDays(col, lit), new SubDays(col, lit)),
      new Least(new DateDiff(chrono, new DayOfWeek(col)),
        new Least(new Least(new WeekDay(col), new DayOfWeekIso(col)),
          new Least(new NextDay(col, lit),
            new Least(new AddMonths(col, lit),
              new Least(new TruncDateDynamic(col, col),
                new Least(new MakeDate(col, lit, lit, false),
                  new MakeDate(col, lit, lit, true))))))))
    val (_, bytes) = emitMulti(Seq(everyNode), 1, 1)
    val lineMap = VarkaDebugInfoReader.lineMap(bytes)
    assert(lineMap === pinnedLineMap, s"re-pin pinnedLineMap from this output:\n$lineMap")
    // The DAG, not a tree: col:0 is written once as line 1 and pointed at sixteen times. The
    // Record.toString rendering this replaced inlined every subtree, so line 25 alone carried
    // the whole IR and the key grew quadratically in exactly the sharing the emitter exploits.
    assert(pinnedLineMap.linesIterator.count(_.contains("col:0")) === 1)
  }

  test("telemetry: the emitted lines index the IR nodes the debug attribute records") {
    // datediff(date_add(d, 1), d2): five distinct nodes, so the loop and the epilogue
    // attribute their instructions to lines 1..5 and the key decodes every one of them.
    val add = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    val root = new DateDiff(add, new ColumnRef(1))
    val (_, bytes) = emitMulti(Seq(root), 2, 1)
    val key = lineKey(bytes)
    assert(key.keys.toSeq.sorted === (1 to key.size).toSeq,
      "the key must number the nodes 1..N with no gaps")
    // Children strictly before parents, which is what makes a line number a schedule position.
    assert(key(key.size).startsWith("(dateDiff"), s"the root should be last: ${key(key.size)}")
    assert(key.values.exists(_.startsWith("col:")))
    assert(key.values.count(_.startsWith("(addDays")) === 1)
    for (method <- Seq("loopMasked0", "epilogueMasked", "loopDense0", "epilogueDense")) {
      val lines = VarkaEmitterTestSupport.lineNumbers(bytes, method)
      assert(lines.asScala.nonEmpty, s"$method carries no LineNumberTable")
      assert(lines.asScala.forall(line => key.contains(line)),
        s"$method has lines outside the key: ${lines.asScala.mkString(", ")}")
    }
  }

  test("a kernel failure's stack frame resolves to the IR node that threw") {
    // The misdescribe option fails the AddDays call site at link time, inside the loop - the
    // shape a real kernel failure takes. The frame through the generated class must name the
    // SourceFile and a line, and the class's own key must decode that line to the node.
    val named = emit(addDays(0), 1, VarkaEmitOptions.DEFAULTS.withMisdescribeAdd(true))
    val (className, bytes) = named
    val (kernel, loader) = load(named)
    try {
      val arena = Arena.ofConfined()
      try {
        val length = 64
        val input = makeInput(arena, length, _ => false)
        val out = makeOutput(arena, length)
        val e = intercept[LinkageError] {
          kernel.run(
            Array(input.data.address()), Array(0L), Array(0),
            Array(out._1.address()), Array(out._2.address()), Array(1), length)
        }
        val frame = e.getStackTrace.find(_.getClassName == className).getOrElse(
          fail(s"no frame in the generated class:\n${e.getStackTrace.mkString("\n")}"))
        val simpleName = className.substring(className.lastIndexOf('.') + 1)
        assert(frame.getFileName === s"$simpleName.java")
        assert(frame.getLineNumber > 0, "the frame carries no line number")
        val node = lineKey(bytes).getOrElse(frame.getLineNumber,
          fail(s"line ${frame.getLineNumber} is not in the recorded key"))
        assert(node.startsWith("(addDays"), s"the failing line decoded to $node")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("telemetry: the SourceFile and VarkaDebugInfo attributes round-trip off the bytes") {
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedTest${classCounter.addAndGet(1)}"
    val bytes = VarkaLoopEmitter.emit(name, Seq(addDays(0)).asJava, 1, 1,
      "Varka_Project_Stage3.java", "date_add(d#1, 3) AS a#2")
    // The attributes are metadata: the class must verify exactly as it did without them.
    assert(VarkaEmitterTestSupport.verify(bytes).isEmpty)
    // A reader without the mapper sees an opaque attribute under the right name - the shape
    // any third-party class-file tool gets - while the diagnostics reader registers the
    // mapper and recovers the payload: the rendered IR and the caller's plan fragment.
    assert(VarkaEmitterTestSupport.hasAttributeNamed(bytes, "VarkaDebugInfo"))
    assert(VarkaDebugInfoReader.sourceFile(bytes) === "Varka_Project_Stage3.java")
    val ir = VarkaDebugInfoReader.ir(bytes)
    assert(ir.contains("outputs=[(addDays col:0 lit:0)]"))
    assert(ir.contains("numInputs=1"))
    assert(VarkaDebugInfoReader.planFragment(bytes) === "date_add(d#1, 3) AS a#2")
    // Task 16: the same attribute carries the LineNumberTable's decoding key.
    assert(VarkaDebugInfoReader.lineMap(bytes).startsWith("1="))
  }

  test("the telemetry-defaulted emit derives the SourceFile and records no plan fragment") {
    val (className, bytes) = emit(addDays(0), 1)
    val simpleName = className.substring(className.lastIndexOf('.') + 1)
    assert(VarkaDebugInfoReader.sourceFile(bytes) === s"$simpleName.java")
    assert(VarkaDebugInfoReader.ir(bytes).contains("(addDays col:0 lit:0)"))
    assert(VarkaDebugInfoReader.planFragment(bytes) === "")
  }
}
