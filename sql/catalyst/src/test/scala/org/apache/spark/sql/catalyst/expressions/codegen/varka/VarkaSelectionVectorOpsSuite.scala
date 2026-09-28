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

import org.apache.spark.SparkFunSuite

/**
 * [[SelectionVectorOps.compactInts]] against a per-row scalar reference (task-24 review: the
 * kernel previously had only end-to-end coverage through the differential suite, and the
 * filter suite's release test had silently stopped exercising compaction when the
 * all-selected fast path started forwarding). Lengths straddle every lane and byte boundary
 * the way `VarkaEmitterTestBase`'s do, so every remainder runs at 4, 8 and 16 lanes alike.
 *
 * Named with the `Varka` prefix, unlike the class it tests, so the documented
 * `testOnly *Varka*` gate picks it up.
 */
class VarkaSelectionVectorOpsSuite extends SparkFunSuite with VarkaTestWatchdog {

  private val lanes = SelectionVectorOps.intLanes()

  /** An Arrow-layout bitmap (LSB of byte 0 is row 0) with `bit(i)` set for i < len. */
  private def bitmap(arena: Arena, len: Int, bit: Int => Boolean): MemorySegment = {
    val seg = arena.allocate(math.max((len + 7) / 8L, 1), 8)
    seg.fill(0.toByte)
    for (i <- 0 until len if bit(i)) {
      val off = i / 8L
      seg.set(ValueLayout.JAVA_BYTE, off,
        (seg.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))).toByte)
    }
    seg
  }

  private def isSet(seg: MemorySegment, i: Int): Boolean =
    (seg.get(ValueLayout.JAVA_BYTE, i / 8L) >>> (i % 8) & 1) != 0

  /**
   * Runs the kernel over one configuration and checks every output row against the scalar
   * reference: selected rows land at the front in order, each carrying its source validity;
   * validity bits at and above `count` stay clear (the kernel zeroes before ORing - the
   * destination is pre-filled with 0xFF here to prove it); rows written into the slack past
   * `count` are anyone's, so they are not asserted on.
   */
  private def check(len: Int, selected: Int => Boolean, nulls: Option[Int => Boolean]): Unit = {
    val arena = Arena.ofConfined()
    try {
      val src = arena.allocate(math.max(len, 1) * 4L, 8)
      for (i <- 0 until len) {
        src.set(ValueLayout.JAVA_INT, i * 4L, i * 7 + 3)
      }
      val srcValid = nulls.map(n => bitmap(arena, len, i => !n(i)))
      val selection = bitmap(arena, len, selected)
      val count = (0 until len).count(selected)
      val dstBytes = (count + lanes) * 4L
      val dst = arena.allocate(dstBytes, 8)
      dst.fill(0x5A.toByte)
      val dstValidBytes = (count + 7) / 8L + 8
      val dstValid = arena.allocate(dstValidBytes, 8)
      dstValid.fill(0xFF.toByte)

      SelectionVectorOps.compactInts(
        src.address(), srcValid.map(_.address()).getOrElse(0L), nulls.isDefined,
        selection, len, count,
        dst.address(), dstBytes, dstValid.address(), dstValidBytes)

      var pos = 0
      for (i <- 0 until len if selected(i)) {
        val valid = nulls.forall(n => !n(i))
        assert(isSet(dstValid, pos) === valid,
          s"validity at output row $pos (input row $i), len=$len")
        if (valid) {
          assert(dst.get(ValueLayout.JAVA_INT, pos * 4L) === i * 7 + 3,
            s"value at output row $pos (input row $i), len=$len")
        }
        pos += 1
      }
      assert(pos === count)
      for (j <- count until math.min(dstValidBytes * 8, count + 64L).toInt) {
        assert(!isSet(dstValid, j), s"validity bit $j at/above count=$count must be clear")
      }
    } finally {
      arena.close()
    }
  }

  // Boundary-straddling lengths for every species this can run at, plus a byte-unaligned
  // spread and one long enough to cross many validity words.
  private val lengths = Seq(0, 1, 3, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 129, 1000)

  test("compactInts matches a scalar reference across lengths, selections and null patterns") {
    for (len <- lengths) {
      val selections: Seq[(String, Int => Boolean)] = Seq(
        ("none", _ => false),
        ("all", _ => true),
        ("alternating", i => i % 2 == 0),
        ("every third", i => i % 3 == 0),
        ("first only", i => i == 0),
        ("last only", i => i == len - 1))
      val nullPatterns: Seq[(String, Option[Int => Boolean])] = Seq(
        ("null-free", None),
        ("every third null", Some(i => i % 3 == 1)),
        ("all null", Some(_ => true)))
      for ((sName, sel) <- selections; (nName, nulls) <- nullPatterns) {
        withClue(s"len=$len selection=$sName nulls=$nName: ") {
          check(len, sel, nulls)
        }
      }
    }
  }

  test("the slack precondition and the count consistency check both throw") {
    val arena = Arena.ofConfined()
    try {
      val len = 64
      val src = arena.allocate(len * 4L, 8)
      val selection = bitmap(arena, len, _ => true)
      val dstValid = arena.allocate(16, 8)
      // Exactly count rows and no slack: the unmasked stores would run past the buffer, so
      // the entry check must refuse before any store happens.
      val tight = arena.allocate(len * 4L, 8)
      val e1 = intercept[IllegalArgumentException] {
        SelectionVectorOps.compactInts(src.address(), 0L, false, selection, len, len,
          tight.address(), len * 4L, dstValid.address(), 16L)
      }
      assert(e1.getMessage.contains("slack"), e1.getMessage)
      // A count that did not come from this bitmap: the kernel finishes and then refuses,
      // because the caller's count is what sized the destination.
      val roomy = arena.allocate((len + lanes) * 4L, 8)
      val e2 = intercept[IllegalStateException] {
        SelectionVectorOps.compactInts(src.address(), 0L, false, selection, len, len - 1,
          roomy.address(), (len + lanes) * 4L, dstValid.address(), 16L)
      }
      assert(e2.getMessage.contains("count"), e2.getMessage)
    } finally {
      arena.close()
    }
  }
}
