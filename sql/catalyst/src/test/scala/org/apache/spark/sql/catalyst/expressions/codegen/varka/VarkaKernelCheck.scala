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

import org.scalatest.Assertions._

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The fuzzers' row-by-row check of one emitted int-lane kernel against
 * [[VarkaReferenceEvaluator]], shared by the IR fuzzer's drawn and wide shapes and the
 * composition fuzzer's compiled kernels (task 238). Scala because the oracle it drives is a Scala
 * object.
 */
object VarkaKernelCheck {

  /**
   * One batch's columns: `length` rows, each column's null pattern and drawn values, and whether
   * the masked body is forced by reporting a null over a full bitmap.
   */
  case class Batch(length: Int, patterns: Seq[Int => Boolean], data: Array[Array[Int]],
      forceMasked: Boolean)

  private def alloc(arena: Arena, bytes: Long): MemorySegment =
    arena.allocate(math.max(bytes, 1L), 8)

  /**
   * Runs one emitted int-lane kernel over the drawn columns and checks every row and validity bit
   * against [[VarkaReferenceEvaluator]]. Null lanes are poisoned; `forceMasked` reports a null over
   * a full bitmap so the masked body runs. Returns whether the rows were compared: false only
   * where `declineAllowed` and the kernel declined the batch, which a kernel over columns drawn
   * without its domains may do.
   */
  def runAndCompare(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Int], batch: Batch,
      declineAllowed: Boolean = false): Boolean = {
    val Batch(length, patterns, data, forceMasked) = batch
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(className, bytes)
    val kernel = loader.loadClass(className).getConstructor().newInstance()
      .asInstanceOf[VarkaFusedKernel]
    val arena = Arena.ofConfined()
    try {
      val srcData = new Array[Long](numInputs)
      val srcValidity = new Array[Long](numInputs)
      val nullCounts = new Array[Int](numInputs)
      for (c <- 0 until numInputs) {
        val d = alloc(arena, length * 4L)
        val v = alloc(arena, (length + 7) / 8L)
        v.fill(0.toByte)
        var nulls = 0
        for (i <- 0 until length) {
          if (patterns(c)(i)) {
            // Poisoned, not left at the drawn value (task 70's harness rule, the same one
            // VarkaEmitterTestBase.poison states). `data` is drawn inside `columnBound` and
            // `MONTH_ARITH_MAX_MONTHS`, so a null lane holding its drawn value is in range by
            // construction and can never reach a guard's condemning comparison - which is the
            // one thing the fuzzer is here to reach. Alternating on the null ordinal puts each
            // extreme on both sides of every bound whatever the null pattern is.
            d.set(ValueLayout.JAVA_INT, i * 4L,
              if ((nulls & 1) == 0) Int.MinValue else Int.MaxValue)
            nulls += 1
          } else {
            d.set(ValueLayout.JAVA_INT, i * 4L, data(c)(i))
            val off = i / 8L
            v.set(ValueLayout.JAVA_BYTE, off,
              (v.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))).toByte)
          }
        }
        srcData(c) = d.address()
        nullCounts(c) = if (forceMasked && nulls == 0) 1 else nulls
        srcValidity(c) =
          if (nullCounts(c) == 0 || nulls == length) 0L else v.address()
      }
      val outs = roots.map { r =>
        val d = alloc(arena, length * 4L)
        for (i <- 0 until length) d.set(ValueLayout.JAVA_INT, i * 4L, 0xDEADBEEF)
        val v = alloc(arena, (length + 7) / 8L)
        v.fill(0xFF.toByte)
        (if (r.isInstanceOf[Cond]) 0L else d.address(), d, v)
      }
      val status = kernel.run(srcData, srcValidity, nullCounts, outs.map(_._1).toArray,
        outs.map(_._3.address()).toArray, lits, length,
        VarkaEmitterTestSupport.scratch(kernel, length))
      if (status != 0 && declineAllowed) {
        return false
      }
      assert(status === 0, s"$context: the kernel declined the batch (status $status)")
      for (i <- 0 until length) {
        val row = (0 until numInputs).map(c => if (patterns(c)(i)) None else Some(data(c)(i)))
        for ((root, o) <- roots.zipWithIndex) {
          val bit = (outs(o)._3.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
          root match {
            case c: Cond =>
              val want = VarkaReferenceEvaluator.evalCond(c, row, lits).contains(true)
              assert(bit === want, s"$context: selection row $i differs (want $want)")
            case _ =>
              val want = VarkaReferenceEvaluator.evalValue(root, row, lits)
              assert(bit === want.isDefined,
                s"$context: validity of output $o row $i differs (want $want)")
              want.foreach { v =>
                assert(outs(o)._2.get(ValueLayout.JAVA_INT, i * 4L) === v,
                  s"$context: output $o row $i differs (want $v)")
              }
          }
        }
      }
      true
    } finally {
      arena.close()
      loader.release()
    }
  }
}
