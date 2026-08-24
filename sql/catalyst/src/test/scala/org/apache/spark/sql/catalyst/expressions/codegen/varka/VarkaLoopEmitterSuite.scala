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
 * Unit tests for [[VarkaLoopEmitter]] (milestone 2, task 9): the emitted fused loop must match
 * the hand-written `DateVectorOps` kernels - the reference semantics - row for row and bit for
 * bit, across lengths that straddle every lane and byte boundary of the 4-, 8- and 16-lane
 * species, every null pattern, and offsets including int wrap-around.
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
  private def chain(depth: Int): VarkaVectorIR = {
    var node: VarkaVectorIR = new ColumnRef(0)
    for (level <- 0 until depth) {
      node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(level))
      else new SubDays(node, new LiteralSlot(level))
    }
    node
  }

  /** Emits the chain into a uniquely named class; returns the name with the bytes. */
  private def emit(root: VarkaVectorIR, numLiterals: Int): (String, Array[Byte]) = {
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedTest${classCounter.addAndGet(1)}"
    (name, VarkaLoopEmitter.emit(name, java.util.List.of(root), 1, numLiterals))
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

  private def makeInput(arena: Arena, length: Int, isNull: Int => Boolean): Col = {
    val data = alloc(arena, length * 4L)
    val validity = alloc(arena, (length + 7) / 8L)
    validity.fill(0.toByte)
    var nulls = 0
    for (i <- 0 until length) {
      data.set(ValueLayout.JAVA_INT, i * 4L, i * 31 - 7000)
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

  test("IR outside the task-9 shape is rejected with a reason, not emitted wrong") {
    def rejects(body: => Unit, fragment: String): Unit = {
      val e = intercept[IllegalArgumentException](body)
      assert(e.getMessage.contains(fragment), s"message was: ${e.getMessage}")
    }
    rejects(emit(chain(VarkaLoopEmitter.MAX_CHAIN_DEPTH + 1),
      VarkaLoopEmitter.MAX_CHAIN_DEPTH + 1), "MAX_CHAIN_DEPTH")
    rejects(emit(new AddDays(new ColumnRef(1), new LiteralSlot(0)), 1), "column 0")
    rejects(emit(new AddDays(new ColumnRef(0), new LiteralSlot(1)), 1), "literal slot")
    rejects(emit(new AddDays(new ColumnRef(0), new ColumnRef(0)), 1), "literal slots")
    rejects(VarkaLoopEmitter.emit("t", java.util.List.of(addDays(0)), 2, 1), "single input")
    rejects(VarkaLoopEmitter.emit("t",
      java.util.List.of(addDays(0), addDays(0)), 1, 1), "single output")
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
}
