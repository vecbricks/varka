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

import scala.jdk.CollectionConverters._

import org.apache.spark.util.Utils

/**
 * One kernel warm-up in a JVM of its own, for [[VarkaKernelWarmupSuite]]'s compile tests, and the
 * shape and batches that suite warms.
 *
 * A warm-up's verdict is a JIT outcome: the kernel's methods compiled by C2 with every vector
 * operation intrinsified, so that a call no longer allocates. The shared test JVM cannot promise
 * that outcome to a kernel it compiles late. Once an int kernel of a second species has run there
 * - the suites that check their answers at a lanes override do - the Vector API's shared templates
 * carry two receiver classes, C2 inlines both, and every vector the warmed kernel computes is
 * boxed where the two paths merge. The kernel then never stops allocating, and the warm-up rightly
 * never calls it compiled (`vector-api-and-width.md`, "two species in one JVM is a box per
 * iteration"; see `VARKA-209.md` 13.2). A fresh JVM per case is the clean JVM the verdict
 * assumes.
 *
 * `main(<none|some|all>)` warms [[shape]] on a batch of 10000 dates whose nulls the argument
 * names, waits for the warm-up to finish, then measures sixteen calls of 1024 rows on a batch
 * with no nulls and on one with some, and prints:
 *  - `VARKA_WARMUP_SUPPORT=sampler=<bool> canWarm=<bool>`;
 *  - where the sampler works, `VARKA_WARMUP_OUTCOME=queued=<bool> idle=<bool> matches=<bool>
 *    state=<state> entryState=<state> ready=<bool> first=<bytes> last=<bytes>
 *    outcome=<the outcome record>`, `matches` saying the outcome is this shape's;
 *  - where the warm-up was queued, `VARKA_WARMUP_ALLOC=served=<none|some> bytes=<bytes>` for each
 *    of the two batches;
 *  - `VARKA_WARMUP_DONE=status=0`.
 */
object VarkaKernelWarmupProbe {

  val SUPPORT_PREFIX = "VARKA_WARMUP_SUPPORT="
  val OUTCOME_PREFIX = "VARKA_WARMUP_OUTCOME="
  val ALLOC_PREFIX = "VARKA_WARMUP_ALLOC="
  val DONE_PREFIX = "VARKA_WARMUP_DONE="

  /**
   * A shape with enough calendar work that its uncompiled calls allocate unmistakably, emitted
   * to be warmed, so that the C1-exclusion directive reaches its class. Its outputs fall in two
   * groups whose loop methods compile one after the other, so a verdict has to wait for both.
   */
  def shape: VarkaShapeKey = {
    val d = new VarkaVectorIR.ColumnRef(0)
    val k = new VarkaVectorIR.LiteralSlot(0)
    val roots = java.util.List.of[VarkaVectorIR](
      new VarkaVectorIR.AddMonths(d, k), new VarkaVectorIR.LastDay(d),
      new VarkaVectorIR.AddDays(d, k))
    new VarkaShapeKey(roots, 1, 1, VarkaEmitOptions.DEFAULTS, true)
  }

  val numOutputs = 3

  /** Where a batch of [[dates]] has its nulls; `word` is how [[main]] is told. */
  sealed abstract class Nulls(val label: String, val word: String) {
    override def toString: String = label
  }
  case object NoNulls extends Nulls("no nulls", "none")
  case object SomeNulls extends Nulls("some nulls", "some")
  case object AllNull extends Nulls("only nulls", "all")

  /**
   * `rows` dates around 2020 in memory the arena owns, with nulls as `nulls` says - for some
   * nulls, every seventh row - and the batch's null count.
   */
  def dates(
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
   * Queues a warm-up of the cache's kernel for [[shape]] on a batch of `rows` dates with nulls
   * as `nulls` says, its one input declared nullable; the entry, and whether the warm-up was
   * queued. The claim is the caller's first, so it cannot fail on a fresh cache.
   */
  def warm(
      cache: VarkaShapeCacheImpl,
      rows: Int,
      nulls: Nulls = SomeNulls): (VarkaShapeEntry, Boolean) = {
    val entry = cache.getOrEmit(Utils.getContextOrSparkClassLoader, shape, "warmup-suite").entry
    require(entry.warmth().tryClaim(), "the shape's warm-up was already claimed")
    val arena = Arena.ofConfined()
    try {
      val (data, validity, nullCount) = dates(arena, rows, nulls)
      val validityAddress = if (nullCount == 0) 0L else validity.address()
      val queued = VarkaKernelWarmup.start(entry.warmth(), entry.shapeHash(), entry.newKernel(),
        false, Array(data.address()), Array(validityAddress), Array(nullCount), Array(4),
        true, rows, numOutputs, Array(2), Array.emptyLongArray)
      (entry, queued)
    } finally {
      // The warm-up copies the batch before start returns, so the batch can go at once.
      arena.close()
    }
  }

  /**
   * What the kernel allocates over sixteen calls of 1024 rows with nulls as `nulls` says, after
   * fifty calls to settle: the evidence of which of its drivers runs compiled code.
   */
  def allocationOfCalls(kernel: VarkaFusedKernel, nulls: Nulls): Long = {
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

  def main(args: Array[String]): Unit = {
    // scalastyle:off println
    require(args.length == 1, "usage: VarkaKernelWarmupProbe <none|some|all>")
    val claimed = Seq(NoNulls, SomeNulls, AllNull).find(_.word == args(0)).getOrElse(
      throw new IllegalArgumentException(s"the claiming batch has none, some or all nulls, " +
        s"not ${args(0)}"))
    val sampler = VarkaAllocationSampler.supported()
    val canWarm = VarkaKernelWarmup.canWarm()
    println(s"${SUPPORT_PREFIX}sampler=$sampler canWarm=$canWarm")
    if (sampler) {
      val (entry, queued) = warm(new VarkaShapeCacheImpl(8), 10000, claimed)
      val idle = queued && VarkaKernelWarmup.awaitIdle(120000)
      val outcome = VarkaKernelWarmup.recentOutcomes().asScala.lastOption
      val matches = outcome.exists(_.shapeHash() == entry.shapeHash())
      println(s"${OUTCOME_PREFIX}queued=$queued idle=$idle matches=$matches " +
        s"state=${outcome.map(_.state().toString).getOrElse("none")} " +
        s"entryState=${entry.warmth().state()} ready=${entry.warmth().ready()} " +
        s"first=${outcome.map(_.firstProbeBytes()).getOrElse(-1L)} " +
        s"last=${outcome.map(_.lastProbeBytes()).getOrElse(-1L)} " +
        s"outcome=${outcome.map(_.toString).getOrElse("none")}")
      if (queued) {
        val kernel = entry.newKernel()
        Seq(NoNulls, SomeNulls).foreach { served =>
          println(s"${ALLOC_PREFIX}served=${served.word} " +
            s"bytes=${allocationOfCalls(kernel, served)}")
        }
      }
    }
    println(s"${DONE_PREFIX}status=0")
    // scalastyle:on println
  }
}
