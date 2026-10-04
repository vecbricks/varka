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

package org.apache.spark.sql

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaFusedKernel, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * What a 64-bit lane costs against a 32-bit one, for the same expression over the same rows.
 *
 * The long lane is milestone 5's subject: `bigint`, `TIME`, the timestamps and day-time
 * intervals are all built on it, and VARKA-85 made the emitter produce it before any of those
 * exist. This file is that lane's baseline, committed before VARKA-104 adds the compiler arms
 * that will let SQL reach it - the project's rule that a measurement precedes the change it is
 * meant to judge.
 *
 * **The arithmetic the numbers are read against.** A 512-bit register holds sixteen int lanes
 * and eight long ones, so the same row count is twice the lane groups. It is also twice the
 * bytes. So a compute-bound shape should cost about twice as much per row at the wider lane,
 * and a memory-bound one should too, for a different reason - and a shape that costs *less*
 * than twice would mean neither bound was reached. The interesting number is not the ratio
 * itself but where each shape falls against 2.
 *
 * **Why this is a ladder and not one row count.** Doubling the width doubles the working set,
 * so at some row counts the two arms are not measured in the same place: the int arm still
 * fits a cache level that the long arm has just fallen out of, and the ratio then prices that
 * boundary rather than the lane. The rungs below bracket it - two where both arms share a
 * level, one where the split is expected, and one where both are past the last level - so a
 * ratio near 2 and a ratio far above it can be told apart instead of averaged. A rung's
 * working set is three columns wide (two inputs and an output), which is 12 bytes a row at the
 * int lane and 24 at the long one.
 *
 * **What is deliberately not here.** No SQL, no evaluator, no cache: these are the emitted
 * kernels driven straight over memory segments, as `VarkaArithmeticBenchmark` drives them, so
 * what moves between the two arms is the lane and nothing else. The shapes are the subset task
 * 85 ships at the long lane; the calendar family has no 64-bit form to price.
 *
 * {{{
 *   build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaLongLaneBenchmark"
 *   SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
 *     "catalyst/Test/runMain org.apache.spark.sql.VarkaLongLaneBenchmark"
 * }}}
 */
object VarkaLongLaneBenchmark extends BenchmarkBase {

  /**
   * The rungs, by what each one is for. The sizes are the two-column working sets they imply
   * per arm, int lane then long lane, against this class of machine: a private L2 of about a
   * megabyte a core and a shared L3 of tens of megabytes.
   */
  private val ladder: Seq[(Int, String)] = Seq(
    16384 -> "both arms in L2",            // 192 KB and 384 KB
    262144 -> "both arms in L3",           // 3 MB and 6 MB
    1000000 -> "the long arm leaves L3",   // 12 MB and 24 MB
    8388608 -> "both arms past L3")        // 96 MB and 192 MB

  private val classCounter = new java.util.concurrent.atomic.AtomicInteger(0)

  private def kernel(
      roots: Seq[VarkaVectorIR],
      numInputs: Int,
      numLiterals: Int,
      loader: VarkaGeneratedClassLoader): VarkaFusedKernel = {
    val name = s"org.apache.spark.sql.varka.bench.LongLane${classCounter.incrementAndGet()}"
    val bytes = VarkaLoopEmitter.emit(name, roots.asJava, numInputs, numLiterals, null, null,
      VarkaEmitOptions.DEFAULTS)
    loader.defineGeneratedClass(name, bytes)
    loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
  }

  /** One null-free column of the given width, values chosen to exercise the whole range. */
  private def column(arena: Arena, numRows: Int, bytesPerRow: Int, shift: Int): MemorySegment = {
    val data = arena.allocate(numRows.toLong * bytesPerRow, 8)
    for (i <- 0 until numRows) {
      if (bytesPerRow == 4) {
        data.set(ValueLayout.JAVA_INT, i * 4L, (i * 7 + shift) % 100000 - 50000)
      } else {
        // Past the int range on purpose: a long lane that was quietly an int one would be
        // measured on values it could not hold.
        data.set(ValueLayout.JAVA_LONG, i * 8L, (1L << 40) + (i * 7L + shift) % 100000L - 50000L)
      }
    }
    data
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      runBenchmark("the same expression at 32-bit and 64-bit lanes") {
        for ((numRows, level) <- ladder) {
          runRung(numRows, level, loader)
        }
      }
    } finally {
      loader.release()
    }
  }

  /**
   * One rung: fresh buffers, both lanes, every shape. Each rung owns its arena and closes it,
   * so a later rung never measures a cache the earlier one happened to leave warm.
   */
  private def runRung(numRows: Int, level: String, loader: VarkaGeneratedClassLoader): Unit = {
    val arena = Arena.ofConfined()
    try {
      val int1 = column(arena, numRows, 4, 0)
      val int2 = column(arena, numRows, 4, 137)
      val long1 = column(arena, numRows, 8, 0)
      val long2 = column(arena, numRows, 8, 137)
      val dstInt = arena.allocate(numRows * 4L, 8)
      val dstLong = arena.allocate(numRows * 8L, 8)
      val dstValidity = arena.allocate((numRows + 7) / 8L, 8)
      val intLits = Array(3)
      val longLits = Array(3L << 32)

      def shapes(lane: LaneType): Seq[(String, Seq[VarkaVectorIR])] = {
        val c = new ColumnRef(0, lane)
        val c1 = new ColumnRef(1, lane)
        val l = new LiteralSlot(0, lane)
        Seq(
          "a column, copied" -> Seq(c),
          "column + literal, wrapping" -> Seq(new IntArith(IntOp.ADD, Overflow.WRAP, c, l)),
          "column + column, wrapping" -> Seq(new IntArith(IntOp.ADD, Overflow.WRAP, c, c1)),
          "column + literal, ANSI checked" -> Seq(new IntArith(IntOp.ADD, Overflow.FAIL, c, l)),
          "column * column, wrapping" -> Seq(new IntArith(IntOp.MUL, Overflow.WRAP, c, c1)),
          "greatest(column, column)" -> Seq(new Greatest(c, c1)),
          "a comparison, as a selection" -> Seq(new Compare(CompareOp.LT, c, c1)),
          "CASE WHEN c < c2 THEN c ELSE literal END" ->
            Seq(new IfElse(new Compare(CompareOp.LT, c, c1), c, l)))
      }

      def runInt(k: VarkaFusedKernel, roots: Seq[VarkaVectorIR]): Unit = {
        val dst = if (roots.head.isInstanceOf[Cond]) 0L else dstInt.address()
        val status = k.run(Array(int1.address(), int2.address()), Array(0L, 0L), Array(0, 0),
          Array(dst), Array(dstValidity.address()), intLits, numRows)
        assert(status == 0, s"the kernel declined a batch: $status")
      }

      def runLong(k: VarkaFusedKernel, roots: Seq[VarkaVectorIR]): Unit = {
        val dst = if (roots.head.isInstanceOf[Cond]) 0L else dstLong.address()
        val status = k.run(Array(long1.address(), long2.address()), Array(0L, 0L), Array(0, 0),
          Array(dst), Array(dstValidity.address()), Array.empty[Int], longLits, numRows)
        assert(status == 0, s"the kernel declined a batch: $status")
      }

      val benchmark = new Benchmark(s"$numRows rows - $level", numRows,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      val intShapes = shapes(LaneType.INT)
      val longShapes = shapes(LaneType.LONG)
      for (((name, roots), (_, longRoots)) <- intShapes.zip(longShapes)) {
        val ik = kernel(roots, 2, intLits.length, loader)
        val lk = kernel(longRoots, 2, longLits.length, loader)
        benchmark.addCase(s"$name, int32 lanes") { _ => runInt(ik, roots) }
        benchmark.addCase(s"$name, int64 lanes") { _ => runLong(lk, longRoots) }
      }
      benchmark.run()
    } finally {
      arena.close()
    }
  }
}
