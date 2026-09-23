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

import java.lang.foreign.{Arena, ValueLayout}

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The child process behind [[VarkaHugeMethodSuite]] (task 87). Launched in a forked JVM under
 * `-Xbatch -XX:+PrintCompilation`, it emits one `make_date` ladder - `n` outputs over one date
 * column, one literal day apiece - under the emit options the arguments select, runs it hot on
 * ragged batches so that every loop and epilogue method is invoked past C2's threshold, and
 * prints a marker when it is done. What HotSpot prints in between is the answer the parent
 * reads: one line per compilation with the tier and the method, and no line at all for a method
 * `DontCompileHugeMethods` refused.
 *
 * `-Xbatch` makes the marker mean something: a compile is then finished on the calling thread
 * before the crossing call returns, so by the time the marker prints every compile the calls
 * earned has been printed, on any machine.
 */
object VarkaHugeMethodProbe {

  val DONE = "VARKA_HUGE_DONE"

  /** The emitted class's name; the parent matches `PrintCompilation` lines against it. */
  val CLASS_NAME = "org.apache.spark.sql.varka.execution.VarkaHugeMethodProbeKernel"

  /** A ragged batch: 1031 rows leave a tail of seven past sixteen lanes, so the epilogue works. */
  val ROWS = 1031

  /** Calls per body, past `Tier4InvocationThreshold` (5000) with room for a recompile. */
  val CALLS = 20000

  /** `n` `make_date(year(d), month(d), <literal k>)` outputs over one date column. */
  def ladder(n: Int): Seq[VarkaVectorIR] = (0 until n).map { k =>
    val col = new ColumnRef(0)
    new MakeDate(new Year(col), new Month(col), new LiteralSlot(k), true)
  }

  /** `<outputs> <methodByteBudget>`: the ladder's height and the switch, 0 for the legacy form. */
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "usage: VarkaHugeMethodProbe <outputs> <methodByteBudget>")
    val n = args(0).toInt
    val options = VarkaEmitOptions.DEFAULTS.withMethodByteBudget(args(1).toInt)
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      val bytes = VarkaLoopEmitter.emit(CLASS_NAME, ladder(n).asJava, 1, n, null, null, options)
      loader.defineGeneratedClass(CLASS_NAME, bytes)
      val kernel = loader.loadClass(CLASS_NAME).getConstructor().newInstance()
        .asInstanceOf[VarkaFusedKernel]
      runHot(kernel, n)
    } finally {
      loader.release()
    }
    // scalastyle:off println
    println(DONE)
    // scalastyle:on println
  }

  private def runHot(kernel: VarkaFusedKernel, n: Int): Unit = {
    val arena = Arena.ofConfined()
    try {
      // Days since the epoch, a few weeks apart, and days of month 1 to 16: every make_date is
      // valid, so the ANSI guard passes and both bodies run to their end.
      val input = arena.allocate(ROWS * 4L, 64)
      for (r <- 0 until ROWS) input.set(ValueLayout.JAVA_INT, r * 4L, r * 23 + 1)
      val validity = arena.allocate((ROWS + 7) / 8L, 8)
      validity.fill(0xFF.toByte)
      var nulls = 0
      for (r <- 0 until ROWS if r % 7 == 3) {
        val off = r / 8L
        validity.set(ValueLayout.JAVA_BYTE, off,
          (validity.get(ValueLayout.JAVA_BYTE, off) & ~(1 << (r % 8))).toByte)
        nulls += 1
      }
      val dstData = Array.fill(n)(arena.allocate(ROWS * 4L, 64).address())
      val dstValidity = Array.fill(n)(arena.allocate((ROWS + 7) / 8L, 8).address())
      val srcData = Array(input.address())
      val lits = (1 to n).toArray
      def call(masked: Boolean): Int = {
        val srcValidity = Array(if (masked) validity.address() else 0L)
        val nullCounts = Array(if (masked) nulls else 0)
        kernel.run(srcData, srcValidity, nullCounts, dstData, dstValidity, lits, ROWS)
      }
      var sink = 0
      var i = 0
      while (i < CALLS) {
        sink += call(masked = false)
        sink += call(masked = true)
        i += 1
      }
      // Keeps the calls' results live; a status of 42 does not exist.
      if (sink == 42) throw new IllegalStateException("unreachable: status 42")
    } finally {
      arena.close()
    }
  }
}
