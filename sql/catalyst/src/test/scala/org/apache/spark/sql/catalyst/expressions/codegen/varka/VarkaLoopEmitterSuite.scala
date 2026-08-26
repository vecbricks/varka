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
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
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
  private def emit(root: VarkaVectorIR, numLiterals: Int): (String, Array[Byte]) =
    emitMulti(Seq(root), 1, numLiterals)

  /** The multi-output, multi-input version of [[emit]] (task 10). */
  private def emitMulti(
      roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int): (String, Array[Byte]) = {
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedTest${classCounter.addAndGet(1)}"
    (name, VarkaLoopEmitter.emit(name, roots.asJava, numInputs, numLiterals))
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
  // -----------------------------------------------------------------------------------------

  private def evalValue(
      node: VarkaVectorIR, row: Seq[Option[Int]], lits: Array[Int]): Option[Int] = node match {
    case c: ColumnRef => row(c.ordinal())
    case l: LiteralSlot => Some(lits(l.index()))
    case n: AddDays =>
      for (d <- evalValue(n.days(), row, lits); o <- evalValue(n.offset(), row, lits))
        yield d + o
    case n: SubDays =>
      for (d <- evalValue(n.days(), row, lits); o <- evalValue(n.offset(), row, lits))
        yield d - o
    case n: DateDiff =>
      for (e <- evalValue(n.end(), row, lits); s <- evalValue(n.start(), row, lits)) yield e - s
    case n: DayOfWeek =>
      evalValue(n.days(), row, lits).map(v => (Math.floorMod(v, 7) + 4) % 7 + 1)
    case n: WeekDay =>
      evalValue(n.days(), row, lits).map(v => (Math.floorMod(v, 7) + 3) % 7)
    case n: Greatest =>
      pick(evalValue(n.left(), row, lits), evalValue(n.right(), row, lits), math.max)
    case n: Least =>
      pick(evalValue(n.left(), row, lits), evalValue(n.right(), row, lits), math.min)
    case n: IfElse =>
      if (evalCond(n.cond(), row, lits).contains(true)) evalValue(n.thenNode(), row, lits)
      else evalValue(n.elseNode(), row, lits)
    case c: Cond => fail(s"condition $c evaluated as a value")
  }

  private def pick(a: Option[Int], b: Option[Int], op: (Int, Int) => Int): Option[Int] =
    (a, b) match {
      case (Some(x), Some(y)) => Some(op(x, y))
      case (Some(x), None) => Some(x)
      case (None, y) => y
    }

  /** Kleene three-valued logic; `None` is unknown, and only known-true selects THEN. */
  private def evalCond(
      cond: Cond, row: Seq[Option[Int]], lits: Array[Int]): Option[Boolean] = cond match {
    case n: Compare =>
      for (l <- evalValue(n.left(), row, lits); r <- evalValue(n.right(), row, lits)) yield {
        n.op() match {
          case CompareOp.LT => l < r
          case CompareOp.LE => l <= r
          case CompareOp.GT => l > r
          case CompareOp.GE => l >= r
          case CompareOp.EQ => l == r
        }
      }
    case n: And =>
      (evalCond(n.left(), row, lits), evalCond(n.right(), row, lits)) match {
        case (Some(false), _) | (_, Some(false)) => Some(false)
        case (Some(true), Some(true)) => Some(true)
        case _ => None
      }
    case n: Or =>
      (evalCond(n.left(), row, lits), evalCond(n.right(), row, lits)) match {
        case (Some(true), _) | (_, Some(true)) => Some(true)
        case (Some(false), Some(false)) => Some(false)
        case _ => None
      }
    case n: Not => evalCond(n.child(), row, lits).map(!_)
  }

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
      ctx: String = ""): Unit = {
    val (kernel, loader) = load(emitMulti(roots, numInputs, lits.length))
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
          kernel.run(cols.map(_.data.address()).toArray, validityAddrs.toArray,
            nullCounts.toArray, outs.map(_._1.address()).toArray,
            outs.map(_._2.address()).toArray, lits, length)
          for (i <- 0 until length) {
            val row = (0 until numInputs).map { c =>
              if (combo(c)(i)) None else Some(data(c, i))
            }
            for ((root, o) <- roots.zipWithIndex) {
              val expected = evalValue(root, row, lits)
              val bit = (outs(o)._2.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
              val where = s"$ctx len=$length combo=$comboId out=$o row=$i"
              assert(bit === expected.isDefined, s"$where: validity differs (want $expected)")
              expected.foreach { v =>
                assert(outs(o)._1.get(ValueLayout.JAVA_INT, i * 4L) === v, s"$where: value")
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

  test("disabling CSE changes the bytecode but never the results") {
    val shared = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    val roots = Seq[VarkaVectorIR](shared, new DateDiff(shared, new ColumnRef(1)))
    val withCse = emitMulti(roots, 2, 1)
    VarkaLoopEmitter.disableCseForTesting = true
    val withoutCse =
      try emitMulti(roots, 2, 1) finally VarkaLoopEmitter.disableCseForTesting = false
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

  test("the lanewise-DIV floorMod reference variant agrees with the shipped magic multiply") {
    val roots = Seq[VarkaVectorIR](new DayOfWeek(new ColumnRef(0)))
    val extremes = Array(Int.MinValue, Int.MaxValue, -1, 0, -7, 7)
    def days(c: Int, i: Int): Int =
      if (i < extremes.length) extremes(i) else i * 31 - 7000
    VarkaLoopEmitter.divFloorModForTesting = true
    try {
      checkMatrix(roots, 1, Array.empty[Int], Seq(64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = days, ctx = "div-variant")
    } finally {
      VarkaLoopEmitter.divFloorModForTesting = false
    }
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
    VarkaLoopEmitter.digitSumFloorModForTesting = true
    try {
      checkMatrix(roots, 1, Array.empty[Int], Seq(1, 13, 17, 64, 1000),
        nullPatterns.map(p => Seq(p._2)), data = days, ctx = "digit-sum-variant")
    } finally {
      VarkaLoopEmitter.digitSumFloorModForTesting = false
    }
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
    rejects(emit(new AddDays(new ColumnRef(0), new ColumnRef(0)), 1), "literal slots")
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
    // Task 11: conditions are interior only, and never values.
    val cmp = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(0))
    rejects(emitMulti(Seq(cmp), 1, 0), "value position")
    rejects(emitMulti(Seq(new AddDays(cmp, new LiteralSlot(0))), 1, 1), "value position")
    rejects(emitMulti(Seq(new Greatest(new ColumnRef(0), cmp)), 1, 0), "value position")
  }

  test("a wrong descriptor fails naming the call, not as an anonymous VerifyError") {
    VarkaLoopEmitter.misdescribeAddForTesting = true
    try {
      val named = emit(addDays(0), 1)
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
    } finally {
      VarkaLoopEmitter.misdescribeAddForTesting = false
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
    assert(ir.contains("AddDays[days=ColumnRef[ordinal=0], offset=LiteralSlot[index=0]]"))
    assert(ir.contains("numInputs=1"))
    assert(VarkaDebugInfoReader.planFragment(bytes) === "date_add(d#1, 3) AS a#2")
  }

  test("the telemetry-defaulted emit derives the SourceFile and records no plan fragment") {
    val (className, bytes) = emit(addDays(0), 1)
    val simpleName = className.substring(className.lastIndexOf('.') + 1)
    assert(VarkaDebugInfoReader.sourceFile(bytes) === s"$simpleName.java")
    assert(VarkaDebugInfoReader.ir(bytes).contains("AddDays"))
    assert(VarkaDebugInfoReader.planFragment(bytes) === "")
  }
}
