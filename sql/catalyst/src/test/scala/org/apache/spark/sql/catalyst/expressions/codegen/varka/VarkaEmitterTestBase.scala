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
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils

/**
 * The fixtures every emitter suite shares: the emitted class built through [[emitMulti]] and
 * loaded through [[load]], the input and output columns over Arrow-shaped memory, the null
 * patterns and the lengths that straddle every lane and byte boundary of the 4-, 8- and 16-lane
 * species, and the two matrices - [[checkMatrix]] at the int lane, [[checkLongMatrix]] at the long
 * lane - that run a shape over every (length, null pattern) case against
 * [[VarkaReferenceEvaluator]], the independent Scala implementation of the semantics.
 *
 * The suites are split by the family of the emitter they test: `VarkaEmitterArithmeticSuite`,
 * `VarkaEmitterChronoSuite`, `VarkaEmitterDivisionSuite`, `VarkaEmitterLongLaneSuite`,
 * `VarkaEmitterValiditySuite`, `VarkaEmitterBudgetSuite` and `VarkaEmitterContractSuite`. Each
 * must also run green under `-XX:MaxVectorSize=16`, the four-lane shape where width bugs hide:
 *
 * {{{
 *   build/sbt "project catalyst" 'set Test/javaOptions += "-XX:MaxVectorSize=16"' \
 *     "testOnly *VarkaEmitter*Suite"
 * }}}
 */
trait VarkaEmitterTestBase extends SparkFunSuite {

  protected val classCounter = new AtomicInteger(0)

  // Boundary-straddling lengths for every species this can run at (4, 8 or 16 lanes), plus the
  // byte boundaries of the bit-packed validity, plus batch-sized ones.
  protected val lengths = Seq(0, 1, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65,
    1000, 4096, 4097)

  protected val offsets = Seq(0, 1, -1, 3, Int.MaxValue - 1)

  /** Null pattern: name -> which rows are null. */
  protected val nullPatterns: Seq[(String, Int => Boolean)] = Seq(
    ("null-free", _ => false),
    ("mixed", i => i % 5 == 0),
    ("alternating", i => i % 2 == 1),
    ("all-null", _ => true))

  protected def addDays(offsetSlot: Int): VarkaVectorIR =
    new AddDays(new ColumnRef(0), new LiteralSlot(offsetSlot))

  /** An `AddDays`/`SubDays` chain of the given depth, alternating so C2 cannot reassociate it. */
  protected def chain(depth: Int, slotBase: Int = 0): VarkaVectorIR = {
    var node: VarkaVectorIR = new ColumnRef(0)
    for (level <- 0 until depth) {
      node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
      else new SubDays(node, new LiteralSlot(slotBase + level))
    }
    node
  }

  /** Emits the chain into a uniquely named class; returns the name with the bytes. */
  protected def emit(
      root: VarkaVectorIR,
      numLiterals: Int,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): (String, Array[Byte]) =
    emitMulti(Seq(root), 1, numLiterals, options)

  /**
   * The multi-output, multi-input version of [[emit]] (task 10). Since task 23 the emitter's
   * non-shape inputs travel as a [[VarkaEmitOptions]] value on the call rather than as static
   * hooks a test had to set and reset, so a variant is just a different argument here.
   */
  protected def emitMulti(
      roots: Seq[VarkaVectorIR],
      numInputs: Int,
      numLiterals: Int,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): (String, Array[Byte]) = {
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedTest${classCounter.addAndGet(1)}"
    (name, VarkaLoopEmitter.emit(name, roots.asJava, numInputs, numLiterals, null, null, options))
  }

  /** Loads an emitted class through the per-task loader and instantiates it. */
  protected def load(
      named: (String, Array[Byte])): (VarkaFusedKernel, VarkaGeneratedClassLoader) = {
    val (className, bytes) = named
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(className, bytes)
    val kernel = loader.loadClass(className).getConstructor()
      .newInstance().asInstanceOf[VarkaFusedKernel]
    (kernel, loader)
  }

  /** The declared method names of an emitted class - how the method layout is asserted. */
  protected def methodNames(named: (String, Array[Byte])): Seq[String] = {
    val (className, bytes) = named
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(className, bytes)
    loader.loadClass(className).getDeclaredMethods.map(_.getName).toSeq
  }

  /** One column's worth of buffers: data, validity bitmap and its null count. */
  protected case class Col(data: MemorySegment, validity: MemorySegment, nullCount: Int) {
    // Per the kernel contract a null-free or all-null column may pass 0L for its validity.
    def validityAddress(length: Int): Long =
      if (nullCount == 0 || nullCount == length) 0L else validity.address()
  }

  protected def alloc(arena: Arena, bytes: Long): MemorySegment =
    arena.allocate(math.max(bytes, 1L), 8)

  protected def makeInput(arena: Arena, length: Int, isNull: Int => Boolean): Col =
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
  protected def poison(nullOrdinal: Int): Int =
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
  protected def makeInputData(
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

  protected def makeOutput(
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
  protected def outputValidityBytes(options: VarkaEmitOptions, length: Int): Long =
    if (options.validityByWord()) ((length + 63L) / 64L) * 8L else (length + 7L) / 8L

  /** Asserts two (data, validity) outputs agree bit for bit and, where valid, value for value. */
  protected def assertSameOutput(
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

  protected def evalValue(
      node: VarkaVectorIR, row: Seq[Option[Int]], lits: Array[Int]): Option[Int] =
    VarkaReferenceEvaluator.evalValue(node, row, lits)

  protected def evalCond(
      cond: Cond, row: Seq[Option[Int]], lits: Array[Int]): Option[Boolean] =
    VarkaReferenceEvaluator.evalCond(cond, row, lits)

  protected def evalLong(
      node: VarkaVectorIR, row: Seq[Option[Long]], lits: Array[Long]): Option[Long] =
    VarkaReferenceEvaluator.evalLong(node, row, lits)

  protected def evalCondLong(
      cond: Cond, row: Seq[Option[Long]], lits: Array[Long]): Option[Boolean] =
    VarkaReferenceEvaluator.evalCondLong(cond, row, lits)

  protected def defaultData(col: Int, i: Int): Int = (i * (col + 3)) % 23 - 11

  /**
   * Emits the outputs once, then runs every (length, per-column null pattern) case against the
   * reference evaluator. With `forceMasked` a null-free column reports one null over a
   * full-set bitmap, which sends the batch down `runMasked` - the dispatcher tests only
   * `nullCount != 0` - so the masked body is exercised on the same data the dense body serves.
   */
  protected def checkMatrix(
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
  protected def makeLongInput(
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
  protected def makeLongOutput(arena: Arena, length: Int): (MemorySegment, MemorySegment) = {
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
  protected def checkLongMatrix(
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

  /** Every pair (or triple) of the four null patterns, as per-column combinations. */
  protected def combos(numInputs: Int): Seq[Seq[Int => Boolean]] = {
    val ps = nullPatterns.map(_._2)
    if (numInputs == 1) ps.map(Seq(_))
    else if (numInputs == 2) for (a <- ps; b <- ps) yield Seq(a, b)
    else for (a <- ps; b <- ps; c <- ps) yield Seq(a, b, c)
  }

  /** The calendar boundary set every bounded calendar test walks; see the first use below. */
  protected val calendarBoundaryDays: Array[Int] = Array(
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

  protected def calendarBoundaryDay(c: Int, i: Int): Int =
    if (i < calendarBoundaryDays.length) calendarBoundaryDays(i) else i * 9973 - 400000

  // Task 35: trunc(date, YEAR | MONTH | QUARTER), two lowerings behind VarkaEmitOptions.truncDate.
  protected val truncRoots = Seq[VarkaVectorIR](
    new TruncDate(new ColumnRef(0), TruncLevel.YEAR),
    new TruncDate(new ColumnRef(0), TruncLevel.MONTH),
    new TruncDate(new ColumnRef(0), TruncLevel.QUARTER))

  // Task 42: make_date over three int columns. The triples cover the validity rule's corners:
  // valid dates at both ends of the contract, 29 February in leap, common, century and
  // quatercentennial years, 30 February, 31 April, 32 December, month 0, 13 and -1, day 0 and
  // -1. `makeDateTriples(c, i)` cycles them per column `c` (0 year, 1 month, 2 day).
  protected val makeDateAll: Array[(Int, Int, Int)] = Array(
    (2024, 1, 1), (2024, 2, 29), (2023, 2, 29), (1900, 2, 29), (2000, 2, 29), (2024, 2, 30),
    (2024, 4, 31), (2024, 4, 30), (2024, 12, 31), (2024, 12, 32), (2024, 13, 1), (2024, 0, 1),
    (2024, -1, 15), (2024, 1, 0), (2024, 6, -1), (1, 1, 1), (9999, 12, 31), (1970, 1, 1),
    (1969, 12, 31), (VarkaChrono.MAKE_DATE_MIN_YEAR, 1, 1),
    (VarkaChrono.MAKE_DATE_MAX_YEAR, 12, 31))

  protected val makeDateValid: Array[(Int, Int, Int)] = makeDateAll.filter { case (y, m, d) =>
    VarkaChrono.makeDate(y, m, d) >= VarkaChrono.MAKE_DATE_OUT_OF_RANGE + 1 }

  protected def tripleData(triples: Array[(Int, Int, Int)])(c: Int, i: Int): Int = {
    val t = triples(i % triples.length)
    if (c == 0) t._1 else if (c == 1) t._2 else t._3
  }

  protected val makeDateNull =
    new MakeDate(new ColumnRef(0), new ColumnRef(1), new ColumnRef(2), false)

  // Task 37's days: the ISO corners its plan names - the week-53 years, the January days that
  // belong to the old year and the December days that belong to the new one - and Velox's
  // Spark-compatibility fixtures (velox/functions/sparksql/tests/DateTimeFunctionsTest.cpp),
  // written against Spark by people who had to match it exactly, over the calendar boundary set.
  protected val isoWeekDays: Array[Int] = Array(
      LocalDate.of(2015, 12, 28), LocalDate.of(2016, 1, 1), LocalDate.of(2019, 12, 30),
      LocalDate.of(2020, 12, 31), LocalDate.of(2021, 1, 1),
      LocalDate.of(2004, 12, 31), LocalDate.of(2009, 12, 31), LocalDate.of(2015, 12, 31),
      LocalDate.of(2026, 12, 31),
      LocalDate.of(1919, 12, 31), LocalDate.of(1969, 12, 31), LocalDate.of(1960, 1, 1),
      LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31),
      // leap years ending on a Thursday, a Friday and a Saturday
      LocalDate.of(2020, 12, 31), LocalDate.of(2004, 12, 31), LocalDate.of(2016, 12, 31)
    ).map(_.toEpochDay.toInt) ++ calendarBoundaryDays

  protected def isoWeekDay(c: Int, i: Int): Int =
    if (i < isoWeekDays.length) isoWeekDays(i) else i * 9973 - 400000

  protected def sweepTrunc(options: VarkaEmitOptions): Unit = {
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

  /** What `LocalDate` says the five extractions are, in the order they are emitted. */
  protected val allCalendarFields: LocalDate => Seq[Int] = date =>
    Seq(date.getYear, date.getMonthValue, date.getDayOfMonth,
      date.get(IsoFields.QUARTER_OF_YEAR), date.getDayOfYear)

  protected def sweepCalendar(
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

  protected def sweepLastDay(options: VarkaEmitOptions): Unit = {
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

  // -------------------------------------------------------------------------------------------
  // Task 32 step B: sharing the civil-from-days prefix between calendar nodes over one date.
  // -------------------------------------------------------------------------------------------

  /** The days the calendar differentials drive: the range's edges, then a strided walk. */
  protected def calendarDays(c: Int, i: Int): Int = {
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
  protected val remainderLengths = Seq(1, 13, 17, 63, 1001)

  protected val sharing = VarkaEmitOptions.DEFAULTS.withShareChronoPrefix(true)

  protected val unshared = VarkaEmitOptions.DEFAULTS.withShareChronoPrefix(false)

  /** The lane ops one emitted body method runs: its `IntVector` invocations, counted off the
   * class file. Task 48's deliverable is a count, not a duration, so it is asserted as one. */
  protected def laneOps(bytes: Array[Byte], method: String): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, method, "jdk.incubator.vector.IntVector")

  /** The `IntVector` ops the prefix's March-month step costs: two multiplies (the `* 5` and
   * the magic), the `+ 2`, and the magic's shift. The store into t[5] is not one. */
  /**
   * What the prefix's month step costs, which depends on the axis: four ops on the 0-based one
   * (the `* 5`, the `+ 2`, the magic multiply and its shift) and two on task 53's 3-based one
   * (the `* 2141` and the `+ 197913`). Task 48's elision saves whichever of the two the shape
   * was going to pay, which is the sense in which that task's win shrank rather than went away.
   */
  protected def monthStepOps(options: VarkaEmitOptions): Int =
    if (options.neriSchneiderMonth()) 2 else 4

  /**
   * The single masked epilogue's bytecode size in the form before task 87 - the one method
   * every output shared (task 24) - whatever `options` says about the byte budget. The tests
   * that pin where that method crossed HugeMethodLimit measure this form on purpose: the
   * crossing is the fact the per-group epilogue answers, and it stays measurable at budget 0.
   */
  protected def singleEpilogueSize(
      roots: Seq[VarkaVectorIR], numInputs: Int, options: VarkaEmitOptions): Int =
    VarkaEmitterTestSupport.codeSize(
      emitMulti(roots, numInputs, 0, options.withMethodByteBudget(0))._2, "epilogueMasked")

  /** Runs a two-input kernel with one output, returning the batch status. */
  protected def runKernel2(kernel: VarkaFusedKernel, a: Col, b: Col,
      out: (MemorySegment, MemorySegment), length: Int): Int =
    kernel.run(
      Array(a.data.address(), b.data.address()),
      Array(a.validityAddress(length), b.validityAddress(length)),
      Array(a.nullCount, b.nullCount),
      Array(out._1.address()), Array(out._2.address()), Array.empty[Int], length)

  // Task 63's int arithmetic. `checkOff` is the A/B arm the benchmark prices and the flag the
  // emitter reads to drop the sign test; it is never a correct setting for an ANSI query.
  protected val checkOff = VarkaEmitOptions.DEFAULTS.withCheckIntOverflow(false)

  /** Values that put the sign test under load: both extremes, their neighbours, and zero. */
  protected def extreme(col: Int, i: Int): Int = {
    val vs = Array(Int.MaxValue, Int.MinValue, Int.MaxValue - 1, Int.MinValue + 1, 0, 1, -1,
      100, -100, 7, Int.MaxValue / 2, Int.MinValue / 2)
    vs((i + col * 5) % vs.length)
  }

  /** The same spread, kept small enough that no op over two of them can overflow. */
  protected def small(col: Int, i: Int): Int = {
    val vs = Array(0, 1, -1, 7, -7, 100, -100, 30000, -30000, 46340, -46340)
    vs((i + col * 3) % vs.length)
  }
}
