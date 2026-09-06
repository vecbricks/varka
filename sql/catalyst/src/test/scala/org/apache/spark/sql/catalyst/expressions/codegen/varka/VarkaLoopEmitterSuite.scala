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

  private def makeInputData(
      arena: Arena, length: Int, isNull: Int => Boolean, value: Int => Int): Col = {
    val data = alloc(arena, length * 4L)
    val validity = alloc(arena, (length + 7) / 8L)
    validity.fill(0.toByte)
    var nulls = 0
    for (i <- 0 until length) {
      data.set(ValueLayout.JAVA_INT, i * 4L, value(i))
      if (isNull(i)) {
        nulls += 1
      } else {
        val off = i / 8L
        val old = validity.get(ValueLayout.JAVA_BYTE, off)
        validity.set(ValueLayout.JAVA_BYTE, off, (old | (1 << (i % 8))).toByte)
      }
    }
    Col(data, validity, nulls)
  }

  private def makeOutput(arena: Arena, length: Int): (MemorySegment, MemorySegment) = {
    val data = alloc(arena, length * 4L)
    // A sentinel no chain produces from the inputs above, so an unwritten valid row shows.
    for (i <- 0 until length) data.set(ValueLayout.JAVA_INT, i * 4L, 0xDEADBEEF)
    val validity = alloc(arena, (length + 7) / 8L)
    validity.fill(0xFF.toByte) // the loop must zero it; stale bits must not leak through
    (data, validity)
  }

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
          val outs = roots.map(_ => makeOutput(arena, length))
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

  /** Every pair (or triple) of the four null patterns, as per-column combinations. */
  private def combos(numInputs: Int): Seq[Seq[Int => Boolean]] = {
    val ps = nullPatterns.map(_._2)
    if (numInputs == 2) for (a <- ps; b <- ps) yield Seq(a, b)
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

  test("AddDays/SubDays with a column offset (task 38) match the reference evaluator") {
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

  test("task 41: a bare ColumnRef output root - a loop that only loads and stores") {
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

  test("task 20: coalesce lowers to IfElse over IsNotNull and matches the reference") {
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

  test("task 20: a validity predicate among the connectives keeps Kleene's rules") {
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

  test("task 20: IsNotNull over a computed operand is rejected at analysis") {
    // The compiler already declines this shape; the emitter re-checks because its emission
    // reads the child's per-input validity word, which only a column has before value walks.
    val bad = new IfElse(new IsNotNull(new AddDays(new ColumnRef(0), new LiteralSlot(0))),
      new ColumnRef(0), new ColumnRef(0))
    val e = intercept[IllegalArgumentException](emitMulti(Seq(bad), 1, 1))
    assert(e.getMessage.contains("IsNotNull child must be a ColumnRef"))
  }

  test("task 59 + task 60: neither next_day's weekday nor add_months' month count trips " +
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

  test("task 59: next_day with a column weekday matches the reference evaluator over every " +
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

  test("task 59: the column and literal next_day forms cost what PLAN_TASK_59.md 3.3 " +
      "registered, and the literal form's bytes did not move") {
    val literal = emitMulti(Seq(new NextDay(new ColumnRef(0), new LiteralSlot(0))), 1, 1)._2
    val column = emitMulti(Seq(new NextDay(new ColumnRef(0), new ColumnRef(1))), 2, 0)._2
    assert(laneOps(literal, "loopDense0") === 18, "the literal form")
    assert(laneOps(column, "loopDense0") === 18, "the column form")
  }

  test("task 20: fitsBudgets mirrors the analysis caps, distinct ops across outputs") {
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

  test("task 54: both prefix forms match LocalDate over the calendar boundaries, last_day too") {
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

  test("task 35: trunc matches DateTimeUtils.truncDate over the calendar boundaries, under " +
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

  test("task 35: every trunc level and every month of two years, quarter starts included") {
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

  test("task 35: trunc shares the calendar prefix with a sibling extraction over the same date") {
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

  test("task 42: make_date matches LocalDate.of over the validity corners - nulls for invalid " +
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

  test("task 42: an invalid date under the ANSI form declines the batch, in a loop lane and " +
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
          val y = makeInputData(arena, length, nullY, pick(0))
          val m = makeInputData(arena, length, nullM, pick(1))
          val d = makeInputData(arena, length, nullD, pick(2))
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

  test("task 42: a null-free batch with an invalid date under the NULL form yields a null " +
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

  test("task 42: the NULL form's kernel has no dense methods and the ANSI form's has both") {
    val nulNames = methodNames(emitMulti(Seq(makeDateNull), 3, 0))
    assert(!nulNames.contains("runDense") && !nulNames.contains("loopDense0"), nulNames)
    assert(nulNames.contains("runMasked") && nulNames.contains("loopMasked0"), nulNames)
    val ansiNames = methodNames(emitMulti(Seq(makeDateAnsi), 3, 0))
    assert(ansiNames.contains("runDense") && ansiNames.contains("runMasked"), ansiNames)
  }

  test("task 42: make_date costs what PLAN_TASK_42.md 3.6 registered under both forms, and " +
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

  test("task 37: weekofyear matches IsoFields over the ISO corners, Velox's fixtures and the " +
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

  test("task 37: weekofyear matches IsoFields on every day from 1990-12-20 to 2030-01-10") {
    // Forty year boundaries in both directions. The Thursday rule claims the boundaries are
    // automatic; this is the check, at a length that puts every one of them in a loop lane
    // and at one that leaves some in a tail lane.
    val start = LocalDate.of(1990, 12, 20).toEpochDay.toInt
    val end = LocalDate.of(2030, 1, 10).toEpochDay.toInt
    val roots = Seq[VarkaVectorIR](new WeekOfYear(new ThursdayOf(new ColumnRef(0))))
    checkMatrix(roots, 1, Array.empty[Int], Seq(end - start + 1, 4093),
      nullPatterns.map(p => Seq(p._2)), data = (_, i) => start + i, ctx = "dense")
  }

  test("task 37: WeekOfYear over anything but a ThursdayOf is refused at analysis") {
    // The lowering is the ISO week of a Thursday only; the compiler builds the pair, and the
    // emitter refuses any other tree rather than emitting a plausible wrong week.
    val col = new ColumnRef(0)
    val lit = new LiteralSlot(0)
    for (child <- Seq[VarkaVectorIR](col, new AddDays(col, lit), new NextDay(col, lit))) {
      val e = intercept[IllegalArgumentException](emitMulti(Seq(new WeekOfYear(child)), 1, 1))
      assert(e.getMessage.contains("WeekOfYear's child must be a ThursdayOf"), e.getMessage)
    }
  }

  test("task 37: the Thursday shift and the week tail cost what PLAN_TASK_37.md 3.3 " +
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
  test("task 57: dayofweek_iso matches getWeekDay + 1 over two whole weeks and the calendar " +
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

  test("task 57: dayofweek_iso costs weekday plus one, and neither sibling moved") {
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

  test("task 37: the week tail and Year over one ThursdayOf share a prefix, and neither " +
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

  test("task 35: the trunc tails cost what PLAN_TASK_35.md section 8 registered, per level " +
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

  test("task 61: trunc with a level column matches DateTimeUtils.truncDate over the calendar " +
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

  test("task 61: every level over every day of two years, beside the literal node sharing " +
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

  test("task 61: a literal level is rejected at analysis - that shape is the literal node") {
    val e = intercept[IllegalArgumentException](
      emitMulti(Seq(new TruncDateDynamic(new ColumnRef(0), new LiteralSlot(0))), 1, 1))
    assert(e.getMessage.contains("trunc's level must be a column"), e.getMessage)
  }

  test("task 61: the dynamic tail costs what PLAN_TASK_61.md 3.3 registered") {
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

  test("task 40: add_months matches DateTimeUtils across clamp boundaries and month offsets") {
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

  test("a day outside the covered range is no longer declined (task 51)") {
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

  test("each calendar output gets its own loop method, whatever GROUP_BUDGET would say") {
    // Four calendar outputs weigh far more than GROUP_BUDGET, so they must not share a loop
    // method: one method of ~180 vector ops is the C2 compile cliff the budget exists for.
    val roots = Seq[VarkaVectorIR](
      new Year(new ColumnRef(0)), new Month(new ColumnRef(0)),
      new DayOfMonth(new ColumnRef(0)), new Quarter(new ColumnRef(0)))
    val names = methodNames(emitMulti(roots, 1, 0, VarkaEmitOptions.DEFAULTS))
    assert(names.count(_.startsWith("loopDense")) === 4,
      s"expected one dense loop method per calendar output, got ${names.mkString(", ")}")
    // A plain chain is unaffected: the weight applies to calendar nodes only.
    val plain = Seq[VarkaVectorIR](
      new AddDays(new ColumnRef(0), new LiteralSlot(0)),
      new SubDays(new ColumnRef(0), new LiteralSlot(0)))
    assert(methodNames(emitMulti(plain, 1, 1, VarkaEmitOptions.DEFAULTS))
      .count(_.startsWith("loopDense")) === 1)
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

  test("the guard's removal reaches the shared prefix too (task 51)") {
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
          val in = makeInputData(arena, length, isNull, day)
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

  /** Runs a two-input kernel with one output, returning the batch status. */
  private def runKernel2(kernel: VarkaFusedKernel, a: Col, b: Col,
      out: (MemorySegment, MemorySegment), length: Int): Int =
    kernel.run(
      Array(a.data.address(), b.data.address()),
      Array(a.validityAddress(length), b.validityAddress(length)),
      Array(a.nullCount, b.nullCount),
      Array(out._1.address()), Array(out._2.address()), Array.empty[Int], length)

  test("task 52: a column-offset producer under a calendar node declines the batch whose " +
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
            val d = makeInputData(arena, length, nullDate, day)
            val o = makeInputData(arena, length, nullOff, off(at))
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

  test("task 52: the guard is emitted only where a calendar node reads a column-offset " +
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

  test("task 52: in-range column offsets under calendar nodes match the reference evaluator " +
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

  test("task 60: a column month count declines the batch whose count leaves the range - in a " +
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
          val d = makeInputData(arena, length, nullDate, day)
          val m = makeInputData(arena, length, nullCount, count(at, value))
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

  test("task 60: a literal date with a column count guards the same, on the branch that has " +
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
          val m = makeInputData(arena, length, nullCount, i => if (i == at) value else i % 7 - 3)
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

  test("task 60: in-range column month counts match the reference evaluator under both " +
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

  test("task 60: the guard is emitted only for a column-driven month count, and the literal " +
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

  test("task 60: the register PLAN_TASK_60.md 3.3 predicted - the guard costs two IntVector " +
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
    // Today's GROUP_BUDGET puts every calendar output in its own loop method, so only the
    // epilogue ever holds two - which means nothing in the default configuration exercises
    // sharing inside a loop body. A budget wide enough to hold all four does, and that is
    // the shape step B2 would ship, measured here for correctness before it is measured for
    // throughput.
    val col = new ColumnRef(0)
    val roots = Seq[VarkaVectorIR](
      new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col))
    val wide = sharing.withGroupBudget(200)
    assert(methodNames(emitMulti(roots, 1, 0, wide)).count(_.startsWith("loopDense")) === 1,
      "the wide budget did not put the four outputs in one loop method")
    checkMatrix(roots, 1, Array.empty[Int], remainderLengths ++ Seq(64, 1000),
      nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = "one wide loop method",
      options = wide)
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
    checkMatrix(roots, 2, Array.empty[Int], remainderLengths,
      // The second date is the first walked from a different index rather than shifted by a
      // constant: adding to a day that is already at the range's edge would push it out and
      // make the kernel decline, which is a guard result, not a sharing one.
      nullPatterns.map(p => Seq(p._2, p._2)), data = (c, i) => calendarDays(c, i + c * 3),
      ctx = "two dates", options = sharing)
  }

  test("sharing the prefix leaves every loop method byte for byte as it was") {
    // Why no benchmark number moves, established by construction rather than by re-running a
    // noisy measurement. Today's GROUP_BUDGET gives every calendar output its own loop method,
    // so no loop body holds two chrono nodes and there is nothing in one for the fragment to
    // share; the epilogue is the only body that holds them all. The parity benchmark drives
    // 4096-row chunks, which every lane count divides, so its epilogue returns at the length
    // check and is never timed - and with the loop methods identical, no committed figure in
    // VarkaEmitterParityBenchmark-jdk25-results.txt can be affected by this change.
    //
    // If a future task relaxes the budget so a loop method does hold two (step B2), this test
    // fails and that is the signal that the parity file has to be regenerated.
    val col = new ColumnRef(0)
    for (roots <- Seq(
        Seq[VarkaVectorIR](new Year(col)),
        Seq[VarkaVectorIR](new Year(col), new Month(col)),
        Seq[VarkaVectorIR](
          new Year(col), new Month(col), new DayOfMonth(col), new Quarter(col)))) {
      val plainBytes = emitMulti(roots, 1, 0, unshared)._2
      val sharedBytes = emitMulti(roots, 1, 0, sharing)._2
      val loops = methodNames(emitMulti(roots, 1, 0, unshared))
        .filter(n => n.startsWith("loopDense") || n.startsWith("loopMasked"))
      assert(loops.size === roots.size * 2,
        s"expected one dense and one masked loop method per output, got $loops")
      for (name <- loops) {
        assert(VarkaEmitterTestSupport.codeSize(sharedBytes, name)
          === VarkaEmitterTestSupport.codeSize(plainBytes, name),
          s"$name changed size under sharing, so a loop body now holds two calendar nodes " +
            "and the parity results file needs regenerating")
      }
    }
  }

  test("task 53: the numerator costs what PLAN_TASK_53.md 3.4 registered, per tail") {
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

  test("task 54: the Julian map costs what PLAN_TASK_54.md 3.3 registered, per node") {
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

  test("task 48: a year-only body computes no month, and the switch says so") {
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

  test("task 48: the month step follows the group's consumers, not the emission order") {
    val col = new ColumnRef(0)
    for ((roots, ctx) <- Seq(
        (Seq[VarkaVectorIR](new Year(col), new Month(col)), "year first"),
        (Seq[VarkaVectorIR](new Month(col), new Year(col)), "month first"))) {
      val elided = emitMulti(roots, 1, 0, sharing)._2
      val kept = emitMulti(roots, 1, 0, sharing.withElideChronoMonth(false))._2
      // The epilogue holds both outputs, so its one shared prefix is read by the month tail
      // and must keep the step - whichever of the two siblings happens to emit the prefix.
      // This is the whole reason the decision is read from the group's consumer set rather
      // than from the node being emitted.
      assert(laneOps(elided, "epilogueMasked") === laneOps(kept, "epilogueMasked"),
        s"the shared epilogue elided the month step with a month tail reading it ($ctx)")
      // The loop methods hold one output each, so exactly one of them - the year's - elides.
      val saved = Seq("loopMasked0", "loopMasked1")
        .map(body => laneOps(kept, body) - laneOps(elided, body))
      assert(saved.sorted === Seq(0, monthStepOps(sharing)),
        s"expected exactly one loop method to elide the month step, saved $saved ($ctx)")
      checkMatrix(roots, 1, Array.empty[Int], remainderLengths,
        nullPatterns.map(p => Seq(p._2)), data = calendarDays, ctx = s"shared, $ctx",
        options = sharing)
    }
  }

  test("task 48: with sharing off the decision is per node, not per fragment") {
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

  test("task 34: dayofyear elides the month step too, and month(d) beside it does not") {
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

  test("sharing the prefix moves the epilogue's HugeMethodLimit crossing from 21 outputs to 44") {
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
    // Unshared, 20 outputs fit and 21 do not.
    assert(epilogueSize(fields(5), 12, unshared) < limit)
    assert(epilogueSize(fields(6).take(21), 12, unshared) > limit)
    // Shared, the same 20 fit with room to spare, and the boundary moves out to 44 outputs
    // over eleven dates.
    assert(epilogueSize(fields(5), 12, sharing) < limit)
    assert(epilogueSize(fields(10), 12, sharing) < limit)
    val past = epilogueSize(fields(11), 12, sharing)
    assert(past > limit,
      s"forty-four shared calendar outputs now fit in $past bytes - sharing reaches further " +
        "than this test records, so the ladder in PLAN_TASK_32.md section 7.1 is stale again")
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

  test("task 45: the driver's fill writes the bits the loop used to OR, exactly") {
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

  test("task 45: a Cond root keeps its per-group OR under both option values") {
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

  test("task 45: the masked path's bytes do not move, and the dense path's shrink") {
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

  test("task 21: a comparison root emits the selection bitmap with null-as-false") {
    // The simplest filter kernel: one Compare root, its bitmap checked against the Kleene
    // reference with unknown collapsed to false at the root - across lengths (partial lane
    // groups included) and every pair of null patterns, all-null included (the all-null
    // shortcut must leave a correct all-clear bitmap for a null-intolerant root).
    val root = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1))
    checkMatrix(Seq(root), 2, Array.emptyIntArray, Seq(0, 5, 16, 17, 65, 1000), combos(2),
      ctx = "cmp-root")
  }

  test("task 21: BETWEEN- and IN-shaped roots match the reference") {
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

  test("task 21: an Or root over one all-null column still selects on the live column") {
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

  test("task 21: validity-predicate roots - IS NOT NULL, and IS NULL as its NOT") {
    val isNotNull = new IsNotNull(new ColumnRef(0))
    checkMatrix(Seq(isNotNull), 1, Array.emptyIntArray, Seq(5, 64, 65, 1000),
      nullPatterns.map(p => Seq(p._2)), ctx = "isnotnull-root")
    checkMatrix(Seq[VarkaVectorIR](new Not(isNotNull)), 1, Array.emptyIntArray,
      Seq(5, 64, 65, 1000), nullPatterns.map(p => Seq(p._2)), ctx = "isnull-root")
  }

  test("task 21: a mask root beside a value root shares the kernel and its subtrees") {
    // The emitter serves mixed outputs even though milestone 3's filter kernels are
    // single-root: the mask and the value share one CSE'd subtree, and each output keeps
    // its own contract (bitmap with no data store; value with data plus validity).
    val add = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    val roots = Seq[VarkaVectorIR](
      new Compare(CompareOp.GT, add, new ColumnRef(1)),
      add)
    checkMatrix(roots, 2, Array(7), Seq(5, 64, 65, 1000), combos(2), ctx = "mixed-roots")
  }

  test("task 21: the masked body agrees with the dense body on a null-free mask root") {
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

  test("task 23: the shallow rendering of every node type is pinned, like the shape hash") {
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
