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

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAllocationSampler.{Schedule, Tracker}

/**
 * The species-pollution sampler's decisions, and its measurement against two loops whose
 * allocation is known: the runtime counterpart of `VarkaAssemblySuite`'s test-time allocation
 * assertion. The positive case here is a loop that allocates on purpose rather than a boxing
 * Vector API loop: making a shared test JVM box would mean running a second species hot, which
 * would degrade every vector suite after this one (`SKILLS.md`, the species-pollution
 * section). The boxing case itself is established by `VarkaAssemblySuite`'s polluted gather
 * pair in a forked JVM, through the same thread-allocation counter this sampler reads.
 */
class VarkaAllocationSamplerSuite extends SparkFunSuite with VarkaTestWatchdog {

  test("the default schedule skips the warm-up, then samples powers of two and every 4096th") {
    val s = Schedule.DEFAULT
    assert((1L until 512L).forall(i => !s.due(i)), "nothing before batch 512")
    assert(s.due(512) && s.due(1024) && s.due(2048) && s.due(4096) && s.due(65536))
    assert(!s.due(513) && !s.due(1000) && !s.due(4095) && !s.due(6000))
    assert(s.due(8192) && s.due(12288) && s.due(20480), "every 4096th after the first")
    // How often a long task pays the two management reads: 2^20 batches is 4G rows at 4096.
    // Twelve powers of two from 2^9 to 2^20, 256 multiples of 4096, nine counted in both.
    val samples = (1L to (1L << 20)).count(s.due)
    assert(samples === 12 + 256 - 9, s"$samples samples in a million batches")
  }

  test("a dense schedule samples every batch from the first") {
    val s = new Schedule(1, 1)
    assert((1L to 100L).forall(s.due))
    intercept[IllegalArgumentException](new Schedule(0, 1))
    intercept[IllegalArgumentException](new Schedule(1, 0))
  }

  test("the verdict is a fixed allowance plus one byte per row") {
    val fixed = VarkaAllocationSampler.FIXED_ALLOWANCE_BYTES
    assert(!VarkaAllocationSampler.suspect(0, 4096))
    assert(!VarkaAllocationSampler.suspect(fixed + 4096, 4096), "exactly the allowance is fine")
    assert(VarkaAllocationSampler.suspect(fixed + 4097, 4096))
    // A boxing loop's rate - a vector object per operator per lane group, hundreds of bytes
    // per row - is two orders of magnitude above the line at any batch length.
    assert(VarkaAllocationSampler.suspect(200L * 4096, 4096))
    assert(VarkaAllocationSampler.suspect(200L * 64, 64))
    // Below about 20 rows the fixed allowance swallows even a boxing rate: a batch that small
    // cannot be judged, and the schedule is built around the default 4096-row batches.
    assert(!VarkaAllocationSampler.suspect(200L * 16, 16))
  }

  test("the tracker warns once, on the second consecutive suspect sample") {
    val t = new Tracker
    assert(!t.record(true), "one suspect sample can be a deopt or a safepoint")
    assert(!t.record(false))
    assert(!t.record(true), "the run restarted")
    assert(t.record(true), "two in a row")
    assert(t.warned())
    assert(!t.record(true), "warned already")
    assert(!t.record(false) && !t.record(true) && !t.record(true), "once per tracker")
  }

  test("the measurement tells an allocating loop from a non-allocating one") {
    assume(VarkaAllocationSampler.supported(), "thread allocation accounting unavailable")
    val rows = 4096
    val input = Array.tabulate(rows)(i => i * 7)
    val output = new Array[Int](rows)
    // Warm both loops so the measured calls are not the interpreter's.
    (0 until 2000).foreach { _ => plainLoop(input, output); allocatingLoop(input, output) }

    val (plainBytes, plain) = measure(plainLoop(input, output), rows)
    assert(!plain, s"a loop with no allocation read $plainBytes bytes")
    val (boxedBytes, boxed) = measure(allocatingLoop(input, output), rows)
    assert(boxed, s"a loop allocating 64 bytes per row read $boxedBytes bytes")
    assert(boxedBytes > 32L * rows, s"$boxedBytes bytes is less than escape analysis can hide")
  }

  private def measure(body: => Int, rows: Int): (Long, Boolean) = {
    val before = VarkaAllocationSampler.allocatedBytes()
    sink += body
    val bytes = VarkaAllocationSampler.allocatedBytes() - before
    (bytes, VarkaAllocationSampler.suspect(bytes, rows))
  }

  @volatile private var sink = 0
  @volatile private var escape: Array[Long] = null

  private def plainLoop(in: Array[Int], out: Array[Int]): Int = {
    var i = 0
    var acc = 0
    while (i < in.length) {
      out(i) = in(i) * 3 + 1
      acc += out(i)
      i += 1
    }
    acc
  }

  /** One 64-byte array per row, published to a field so escape analysis cannot remove it. */
  private def allocatingLoop(in: Array[Int], out: Array[Int]): Int = {
    var i = 0
    var acc = 0
    while (i < in.length) {
      val box = new Array[Long](6)
      box(0) = in(i)
      escape = box
      out(i) = (box(0) * 3 + 1).toInt
      acc += out(i)
      i += 1
    }
    acc
  }
}
