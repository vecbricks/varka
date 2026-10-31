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

import java.io.File
import java.lang.foreign.{Arena, ValueLayout}

import scala.jdk.CollectionConverters._

import jdk.incubator.vector.IntVector

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaWidthAuditShapes.{
  Declined, Entry, Shape}

/**
 * The child process behind [[VarkaWidthAuditSuite]] (VARKA-153). Launched in a forked JVM under
 * `-Xbatch` and a `PrintIntrinsics` directive scoped to the emitted classes, it emits every
 * audited shape, runs each one hot enough for C2 to compile both its bodies, and prints a
 * marker before and after. What HotSpot prints in between - the `** not supported: ...` lines
 * C2 writes when it refuses to lower a Vector API call and leaves the Java fallback in place -
 * is the answer the parent reads.
 *
 * `-Xbatch` is what makes the markers mean something: compilation then happens on the thread
 * that triggered it, inside the calls between the two markers, rather than on a background
 * compiler thread whose output would land under whichever shape happened to be running. The
 * shape's class name carries its index, so the directive matches nothing else and a refusal
 * inside a shared Vector API method compiled on its own is not mistaken for one of ours.
 *
 * Both bodies are compiled on purpose: the dense body is what a null-free batch runs, the
 * masked body is what a batch with nulls runs, and they load and store differently.
 */
object VarkaWidthAuditProbe {

  val PREFERRED_BITS_PREFIX = "VARKA_AUDIT_PREFERRED_BITS="
  /** The host's `UseAVX` as `VarkaEmitOptions.HOST_USE_AVX` reads it, -1 where there is no flag. */
  val USE_AVX_PREFIX = "VARKA_AUDIT_USE_AVX="
  val SHAPE_BEGIN_PREFIX = "VARKA_AUDIT_SHAPE_BEGIN "
  val SHAPE_END_PREFIX = "VARKA_AUDIT_SHAPE_END "
  val SHAPE_NAME_END = " VARKA_AUDIT_NAME_END"
  /** A shape this width has no lowering for, with the compiler's reason (VARKA-329). */
  val DECLINED_PREFIX = "VARKA_AUDIT_DECLINED "
  val DONE = "VARKA_AUDIT_DONE"

  /** The prefix every emitted class is named with; the `PrintIntrinsics` directive matches it. */
  val CLASS_PREFIX = "org.apache.spark.sql.varka.execution.VarkaAudit"

  private val rows = 256
  private val calls = 20000

  /**
   * `<coverage.json> [substring]`: the second argument keeps only the shapes whose name
   * contains it, for running one shape by hand under extra flags (`-XX:+PrintInlining` says
   * which inlined method a refusal came from; the suite's scoped directive does not).
   */
  def main(args: Array[String]): Unit = {
    require(args.length == 1 || args.length == 2,
      "usage: VarkaWidthAuditProbe <path to coverage.json> [name substring]")
    // scalastyle:off println
    println(PREFERRED_BITS_PREFIX + IntVector.SPECIES_PREFERRED.vectorBitSize())
    println(USE_AVX_PREFIX + VarkaEmitOptions.HOST_USE_AVX)
    val entries: Seq[Entry] = VarkaWidthAuditShapes.all(new File(args(0)))
      .filter(s => args.length == 1 || s.name.contains(args(1)))
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      entries.zipWithIndex.foreach { case (entry, k) =>
        // The name holds spaces, so it is closed with a mark the reader stops at: a compiler
        // thread's record can run into the line (`VarkaProbeOutput`).
        println(SHAPE_BEGIN_PREFIX + entry.name + SHAPE_NAME_END)
        System.out.flush()
        entry match {
          case shape: Shape => runHot(shape, k, loader)
          // The compiler refused the row at this width, so there is no kernel to emit or run;
          // the reason is the width's answer and the parent pins it beside the refusals.
          case Declined(_, reason) =>
            println(DECLINED_PREFIX + reason)
            System.out.flush()
        }
        println(SHAPE_END_PREFIX + entry.name)
        System.out.flush()
      }
    } finally {
      loader.release()
    }
    println(DONE)
    // scalastyle:on println
  }

  /** The inputs `roots` read as a decimal column, whose buffer is sixteen bytes a row. */
  private def decimalInputs(roots: Seq[VarkaVectorIR]): Set[Int] = {
    val seen = new java.util.IdentityHashMap[VarkaVectorIR, Unit]()
    def walk(n: VarkaVectorIR): Set[Int] = if (seen.containsKey(n)) Set.empty else {
      seen.put(n, ())
      val own = n match {
        case c: VarkaVectorIR.DecimalColumn => Set(c.ordinal)
        case _ => Set.empty[Int]
      }
      own ++ VarkaVectorIR.childrenOf(n).flatMap(walk)
    }
    roots.flatMap(walk).toSet
  }

  private def runHot(shape: Shape, k: Int, loader: VarkaGeneratedClassLoader): Unit = {
    val className = CLASS_PREFIX + k
    // A shape a width has no lowering for is a `Declined` entry, decided by the compiler before
    // this probe runs; one that reaches the emitter and still declines is a budget the forced
    // width was not meant to test, and fails the audit rather than being pinned as its answer.
    val bytes = VarkaLoopEmitter.emit(className, shape.roots.asJava, shape.numInputs,
      shape.numLiterals, null, null, shape.options)
    loader.defineGeneratedClass(className, bytes)
    val kernel = loader.loadClass(className).getConstructor().newInstance()
      .asInstanceOf[VarkaFusedKernel]
    val arena = Arena.ofConfined()
    try {
      // Eight-byte rows and the long table at every 64-bit lane, DOUBLE's included.
      val wide = shape.lane.bits() == java.lang.Long.SIZE
      val stride = if (wide) 8L else 4L
      // Small non-negative values: inside every guard, far from every overflow, so the
      // checked shapes compute rather than decline and both bodies run to the end.
      // A decimal column (VARKA-347) is Arrow's sixteen-byte slot a row, the low word and its
      // sign; its small values are inside every precision, so its slot check passes.
      val decimals = decimalInputs(shape.roots)
      val inputs = (0 until shape.numInputs).map { c =>
        if (decimals.contains(c)) {
          val seg = arena.allocate(rows * 16L, 64)
          for (r <- 0 until rows) {
            seg.set(ValueLayout.JAVA_LONG, r * 16L, r * 3L + c + 1)
            seg.set(ValueLayout.JAVA_LONG, r * 16L + 8L, 0L)
          }
          seg
        } else {
          val seg = arena.allocate(rows * stride, 64)
          for (r <- 0 until rows) {
            if (wide) seg.set(ValueLayout.JAVA_LONG, r * 8L, (r * 3L + c + 1) * 1000000L)
            else seg.set(ValueLayout.JAVA_INT, r * 4L, r * 3 + c + 1)
          }
          seg
        }
      }
      val validity = arena.allocate((rows + 7) / 8L, 8)
      validity.fill(0xFF.toByte)
      var nulls = 0
      for (r <- 0 until rows if r % 7 == 3) {
        val off = r / 8L
        validity.set(ValueLayout.JAVA_BYTE, off,
          (validity.get(ValueLayout.JAVA_BYTE, off) & ~(1 << (r % 8))).toByte)
        nulls += 1
      }
      val outs = shape.roots.map(_ => arena.allocate(rows * stride, 64))
      val outValidity = shape.roots.map(_ => arena.allocate((rows + 7) / 8L, 8))
      val dstData = shape.roots.zip(outs).map { case (root, o) =>
        if (root.isInstanceOf[Cond]) 0L else o.address()
      }.toArray
      val dstValidity = outValidity.map(_.address()).toArray
      val srcData = inputs.map(_.address()).toArray
      // Both tables at the long lane: a kernel that converts between lanes (VARKA-28) reads its
      // int literals from the int table beside the long one.
      val intLits = Array.fill(shape.numLiterals)(7)
      val longLits = Array.fill(if (wide) shape.numLiterals else 0)(7L)
      def call(masked: Boolean): Int = {
        val srcValidity = Array.fill(shape.numInputs)(if (masked) validity.address() else 0L)
        val nullCounts = Array.fill(shape.numInputs)(if (masked) nulls else 0)
        if (wide) {
          kernel.run(srcData, srcValidity, nullCounts, dstData, dstValidity, intLits, longLits,
            rows)
        } else {
          kernel.run(srcData, srcValidity, nullCounts, dstData, dstValidity, intLits, rows)
        }
      }
      var sink = 0
      var n = 0
      while (n < calls) {
        sink += call(masked = false)
        sink += call(masked = true)
        n += 1
      }
      // Keeps the calls' results live; a status of 42 does not exist.
      if (sink == 42) throw new IllegalStateException("unreachable: status 42")
    } finally {
      arena.close()
    }
  }
}
