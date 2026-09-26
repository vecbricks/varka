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
import java.lang.management.ManagementFactory
import javax.management.ObjectName

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmth.State
import org.apache.spark.util.Utils

/**
 * The kernel warm-up (`PLAN_TASK_212.md` 10): the copy of a batch it runs on, its verdict on a
 * real emitted kernel - which must cover both of the kernel's drivers, whichever kind of batch
 * claimed the warm-up - its release when the shape leaves the cache, and the compiler directive
 * that keeps C1 off the warmed kernel classes so the verdict can arrive at all.
 */
class VarkaKernelWarmupSuite extends SparkFunSuite {

  /**
   * A shape with enough calendar work that its uncompiled calls allocate unmistakably, emitted
   * to be warmed, so that the C1-exclusion directive reaches its class.
   */
  private def shape: VarkaShapeKey = {
    val d = new VarkaVectorIR.ColumnRef(0)
    val k = new VarkaVectorIR.LiteralSlot(0)
    val roots = java.util.List.of[VarkaVectorIR](
      new VarkaVectorIR.AddMonths(d, k), new VarkaVectorIR.LastDay(d),
      new VarkaVectorIR.AddDays(d, k))
    new VarkaShapeKey(roots, 1, 1, VarkaEmitOptions.DEFAULTS, true)
  }

  private val numOutputs = 3

  /** Where a batch of [[dates]] has its nulls. */
  private sealed abstract class Nulls(val label: String) {
    override def toString: String = label
  }
  private case object NoNulls extends Nulls("no nulls")
  private case object SomeNulls extends Nulls("some nulls")
  private case object AllNull extends Nulls("only nulls")

  /**
   * `rows` dates around 2020 in memory the arena owns, with nulls as `nulls` says - for some
   * nulls, every seventh row - and the batch's null count.
   */
  private def dates(
      arena: Arena,
      rows: Int,
      nulls: Nulls = SomeNulls): (MemorySegment, MemorySegment, Int) = {
    val data = arena.allocate(rows * 4L, 64)
    val validity = arena.allocate(((rows + 63) / 64) * 8L, 64)
    var nullCount = 0
    (0 until rows).foreach { r =>
      data.setAtIndex(ValueLayout.JAVA_INT, r, 18262 + r % 1460)
      val isNull = nulls match {
        case NoNulls => false
        case SomeNulls => r % 7 == 3
        case AllNull => true
      }
      if (isNull) {
        nullCount += 1
      } else {
        val b = r / 8
        validity.set(ValueLayout.JAVA_BYTE, b,
          (validity.get(ValueLayout.JAVA_BYTE, b) | (1 << (r % 8))).toByte)
      }
    }
    (data, validity, nullCount)
  }

  /**
   * Queues a warm-up of the cache's kernel for `shape` on a batch of `rows` dates with nulls as
   * `nulls` says, its one input declared nullable.
   */
  private def warm(
      cache: VarkaShapeCacheImpl,
      rows: Int,
      nulls: Nulls = SomeNulls): VarkaShapeEntry = {
    val entry = cache.getOrEmit(Utils.getContextOrSparkClassLoader, shape, "warmup-suite").entry
    assert(entry.warmth().tryClaim())
    val arena = Arena.ofConfined()
    try {
      val (data, validity, nullCount) = dates(arena, rows, nulls)
      val validityAddress = if (nullCount == 0) 0L else validity.address()
      val queued = VarkaKernelWarmup.start(entry.warmth(), entry.shapeHash(), entry.newKernel(),
        false, Array(data.address()), Array(validityAddress), Array(nullCount), Array(4),
        true, rows, numOutputs, Array(2), Array.emptyLongArray)
      assert(queued, "the warm-up was not queued")
    } finally {
      // The warm-up copies the batch before start returns, so the batch can go at once.
      arena.close()
    }
    entry
  }

  /**
   * What the kernel allocates over sixteen calls of 1024 rows with nulls as `nulls` says, after
   * fifty calls to settle: the evidence of which of its drivers runs compiled code.
   */
  private def allocationOfCalls(kernel: VarkaFusedKernel, nulls: Nulls): Long = {
    val rows = 1024
    val arena = Arena.ofConfined()
    try {
      val (data, validity, nullCount) = dates(arena, rows, nulls)
      val validityAddress = if (nullCount == 0) 0L else validity.address()
      val dstData = Array.fill(numOutputs)(arena.allocate(rows * 4L, 64).address())
      val dstValidity = Array.fill(numOutputs)(arena.allocate(rows / 8L + 8, 64).address())
      def call(): Unit = kernel.run(Array(data.address()), Array(validityAddress),
        Array(nullCount), dstData, dstValidity, Array(2), rows)
      (1 to 50).foreach(_ => call())
      // A plain loop: a lambda first created inside the window would count its own bootstrap,
      // tens of kilobytes, against the kernel.
      var k = 0
      val before = VarkaAllocationSampler.allocatedBytes()
      while (k < 16) {
        call()
        k += 1
      }
      VarkaAllocationSampler.allocatedBytes() - before
    } finally {
      arena.close()
    }
  }

  test("the copy repeats a short batch's rows and validity bits, bit offsets included") {
    val arena = Arena.ofConfined()
    try {
      val rows = 5
      val (data, validity, _) = dates(arena, rows)
      val dataCopy = arena.allocate(VarkaKernelWarmup.SNAPSHOT_ROWS * 4L)
      val validityCopy = arena.allocate(VarkaKernelWarmup.SNAPSHOT_ROWS / 8L)
      VarkaKernelWarmup.tileData(data.address(), 4, rows, dataCopy)
      VarkaKernelWarmup.tileValidity(validity.address(), rows, validityCopy)
      (0 until VarkaKernelWarmup.SNAPSHOT_ROWS).foreach { r =>
        val s = r % rows
        assert(dataCopy.getAtIndex(ValueLayout.JAVA_INT, r) ===
          data.getAtIndex(ValueLayout.JAVA_INT, s), s"row $r")
        val bit = (validityCopy.get(ValueLayout.JAVA_BYTE, r / 8) >> (r % 8)) & 1
        assert(bit === (if (s % 7 == 3) 0 else 1), s"validity of row $r")
      }
    } finally {
      arena.close()
    }
  }

  test("a warm-up runs a new kernel until it no longer allocates, and says it is compiled") {
    assume(VarkaAllocationSampler.supported(), "thread allocation accounting unavailable")
    val cache = new VarkaShapeCacheImpl(8)
    val entry = warm(cache, 10000)
    assert(VarkaKernelWarmup.awaitIdle(120000), "the warm-up did not finish in two minutes")
    val outcome = VarkaKernelWarmup.recentOutcomes().asScala.last
    assert(outcome.shapeHash() === entry.shapeHash())
    assert(outcome.state() === State.COMPILED, outcome)
    assert(entry.warmth().state() === State.COMPILED)
    assert(entry.warmth().ready())
    // The verdict's evidence: the first probe boxed, the last one did not.
    assert(outcome.lastProbeBytes() * VarkaKernelWarmup.COMPILED_DROP <= outcome.firstProbeBytes(),
      outcome)
  }

  Seq(NoNulls, SomeNulls, AllNull).foreach { claimed =>
    test(s"a warm-up claimed by a batch with $claimed compiles both of the kernel's drivers") {
      assume(VarkaAllocationSampler.supported(), "thread allocation accounting unavailable")
      assume(VarkaKernelWarmup.canWarm(), "this JVM cannot warm kernels")
      val entry = warm(new VarkaShapeCacheImpl(8), 10000, claimed)
      assert(VarkaKernelWarmup.awaitIdle(120000), "the warm-up did not finish in two minutes")
      val outcome = VarkaKernelWarmup.recentOutcomes().asScala.last
      assert(outcome.shapeHash() === entry.shapeHash())
      assert(outcome.state() === State.COMPILED, outcome)
      // A compiled call allocates only its driver's memory segments, a few hundred bytes a
      // column; an interpreted one boxes every vector operation, tens of kilobytes a call.
      val limit = 16L * VarkaKernelWarmup.SEGMENT_BYTES_PER_COLUMN * (1 + numOutputs) +
        VarkaAllocationSampler.FIXED_ALLOWANCE_BYTES
      val kernel = entry.newKernel()
      Seq(NoNulls, SomeNulls).foreach { served =>
        val allocated = allocationOfCalls(kernel, served)
        assert(allocated <= limit,
          s"batches with $served allocated $allocated bytes after a warm-up claimed by a batch " +
            s"with $claimed ($outcome)")
      }
    }
  }

  test("the copy gives a null row the first valid value, so a dense call reads real dates") {
    val arena = Arena.ofConfined()
    try {
      val rows = VarkaKernelWarmup.SNAPSHOT_ROWS
      val (data, validity, _) = dates(arena, rows)
      VarkaKernelWarmup.fillNullRows(data, 4, validity)
      (0 until rows).foreach { r =>
        val expected = if (r % 7 == 3) 18262 else 18262 + r % 1460
        assert(data.getAtIndex(ValueLayout.JAVA_INT, r) === expected, s"row $r")
      }
    } finally {
      arena.close()
    }
  }

  test("a shape that leaves the cache stops its warm-up and is ready for its tasks") {
    assume(VarkaAllocationSampler.supported(), "thread allocation accounting unavailable")
    val cache = new VarkaShapeCacheImpl(8)
    val entry = warm(cache, 1000)
    cache.invalidateAll()
    assert(VarkaKernelWarmup.awaitIdle(120000), "the warm-up did not finish in two minutes")
    val outcome = VarkaKernelWarmup.recentOutcomes().asScala.last
    assert(outcome.shapeHash() === entry.shapeHash())
    // The eviction released the shape while its warm-up was queued or had barely begun, so the
    // job stopped at once rather than running to a verdict for a class no task uses.
    assert(entry.warmth().state() === State.RELEASED)
    assert(outcome.state() === State.RELEASED, outcome)
    assert(outcome.calls() < VarkaKernelWarmup.SPIN_CALLS, outcome)
  }

  test("a claim goes to exactly one caller, and handing it back lets another take it") {
    val warmth = new VarkaKernelWarmth
    assert(!warmth.ready())
    assert(warmth.tryClaim())
    assert(!warmth.tryClaim(), "a second claim while the first is warming")
    warmth.unclaim()
    assert(warmth.state() === State.COLD)
    assert(warmth.tryClaim())
    warmth.release()
    assert(warmth.ready() && warmth.state() === State.RELEASED)
    warmth.unclaim()
    assert(warmth.state() === State.RELEASED, "a released shape does not go back to cold")
  }

  test("a JVM that warms kernels has the directive that keeps C1 off the warmed classes") {
    // Assumed on the JVM's compilers only: where C1 and C2 are tiered, a directive that failed
    // to install is a failure of this test, not a reason to skip it.
    assume(VarkaKernelCompileDirective.compilers() == VarkaKernelCompileDirective.Compilers.TIERED,
      "C1 and C2 are not tiered in this JVM")
    assume(VarkaAllocationSampler.supported(), "thread allocation accounting unavailable")
    assert(VarkaKernelWarmup.canWarm(), "a tiered JVM that measures allocation cannot warm")
    assert(VarkaKernelCompileDirective.installed())
    val directives = ManagementFactory.getPlatformMBeanServer.invoke(
      new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerDirectivesPrint",
      Array[AnyRef](Array.empty[String]), Array(classOf[Array[String]].getName)).toString
    assert(directives.contains(VarkaKernelCompileDirective.METHOD_PATTERN), directives)
  }

  test("only a warmed kernel's class name is one the directive matches") {
    val warmedName = VarkaShapeCacheImpl.classNameFor(VarkaShapeCacheImpl.shapeHash(shape))
    val plain = new VarkaShapeKey(shape.outputs(), 1, 1)
    val plainName = VarkaShapeCacheImpl.classNameFor(VarkaShapeCacheImpl.shapeHash(plain))
    val prefix = VarkaKernelCompileDirective.METHOD_PATTERN.stripSuffix("*.*").replace('/', '.')
    assert(warmedName.startsWith(prefix), warmedName)
    assert(!plainName.startsWith(prefix), plainName)
    // The same bytes under both names: the mark is the whole difference.
    val prefixLength = VarkaShapeCacheImpl.CLASS_NAME_PREFIX.length
    assert(warmedName.substring(prefixLength) ===
      VarkaShapeCacheImpl.WARMED_MARK + plainName.substring(prefixLength))
  }
}
