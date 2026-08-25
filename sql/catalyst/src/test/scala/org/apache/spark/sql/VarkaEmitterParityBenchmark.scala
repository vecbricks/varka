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

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitterTestSupport, VarkaFusedKernel, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.varka.vector.DateVectorOps

/**
 * The emitter's gates as a benchmark (see `sql/varka/plans/PLAN_TASK_9.md`,
 * `PLAN_TASK_10.md` and `PLAN_TASK_11.md`).
 *
 * The parity gates: an emitted single-op loop must reach the hand-written kernel within noise
 * (acceptance: at least 0.9x its best-time throughput) - anything worse means C2 did not
 * intrinsify the emitted Vector API calls and the emitter is wrong. Task 9 measured `date_add`;
 * task 10 adds the two-input `datediff`. The chain cases are the fusion gate and the data
 * behind `MAX_CHAIN_DEPTH`: a fused depth-N chain against N sequential kernel passes over the
 * same buffers. Task 10 adds the DAG cases: a subchain shared by two outputs with CSE on, with
 * the memo disabled (pricing CSE itself), and as sequential kernel passes; and the widest shape
 * the emitter accepts (`MAX_FUSED_NODES` ops), which must scale with its op count rather than
 * fall off a cliff at the cap. Task 11 adds the predication cases - a CASE WHEN blend against
 * the same-depth plain arithmetic - and `dayofweek` as the shipped digit-sum mod-7 against the
 * lanewise-DIV reference variant and the per-row `LocalDate` path Spark uses today.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *          "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 *      Results will be written to "benchmarks/VarkaEmitterParityBenchmark-results.txt".
 *   3. the four-lane shape (numbers recorded in the task plans):
 *        build/sbt "project catalyst" 'set Test/javaOptions += "-XX:MaxVectorSize=16"'
 *          "Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 * }}}
 */
object VarkaEmitterParityBenchmark extends BenchmarkBase {

  private val numRows = 1_000_000

  private def chain(depth: Int, slotBase: Int = 0): VarkaVectorIR = {
    var node: VarkaVectorIR = new ColumnRef(0)
    for (level <- 0 until depth) {
      node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
      else new SubDays(node, new LiteralSlot(slotBase + level))
    }
    node
  }

  private def emit(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int,
      loader: VarkaGeneratedClassLoader, n: Int): VarkaFusedKernel = {
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedBench$n"
    val javaRoots = new java.util.ArrayList[VarkaVectorIR]()
    roots.foreach(javaRoots.add)
    loader.defineGeneratedClass(name,
      VarkaLoopEmitter.emit(name, javaRoots, numInputs, numLiterals))
    loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
  }

  private def fill(arena: Arena, isNull: Int => Boolean): (MemorySegment, MemorySegment, Int) = {
    val data = arena.allocate(numRows * 4L, 8)
    val validity = arena.allocate((numRows + 7) / 8L, 8)
    validity.fill(0.toByte)
    var nulls = 0
    for (i <- 0 until numRows) {
      data.set(ValueLayout.JAVA_INT, i * 4L, i % 20000 - 10000)
      if (isNull(i)) {
        nulls += 1
      } else {
        val off = i / 8L
        val old = validity.get(ValueLayout.JAVA_BYTE, off)
        validity.set(ValueLayout.JAVA_BYTE, off, (old | (1 << (i % 8))).toByte)
      }
    }
    (data, validity, nulls)
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val arena = Arena.ofConfined()
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      val (nfData, _, _) = fill(arena, _ => false)
      val (mxData, mxValidity, mxNulls) = fill(arena, i => i % 7 == 0)
      val (nf2Data, _, _) = fill(arena, _ => false)
      val (mx2Data, mx2Validity, mx2Nulls) = fill(arena, i => i % 11 == 0)
      val dst = arena.allocate(numRows * 4L, 8)
      val dstValidity = arena.allocate((numRows + 7) / 8L, 8)
      val dst2 = arena.allocate(numRows * 4L, 8)
      val dst2Validity = arena.allocate((numRows + 7) / 8L, 8)
      // Sequential-pass temporaries, one pair per intermediate of the deepest chain, and the
      // per-output destinations of the widest shape.
      val maxDepth = 16
      val tmpData = Array.fill(maxDepth)(arena.allocate(numRows * 4L, 8))
      val tmpValidity = Array.fill(maxDepth)(arena.allocate((numRows + 7) / 8L, 8))
      val wideDst = Array.fill(4)(arena.allocate(numRows * 4L, 8))
      val wideDstValidity = Array.fill(4)(arena.allocate((numRows + 7) / 8L, 8))

      /** The chain as `depth` hand-written kernel passes, `src` to `dst` through the temps. */
      def sequentialChain(depth: Int, offsets: Array[Int], srcData: Long, srcValidity: Long,
          srcNulls: Int, dstData: Long, dstVal: Long): Unit = {
        var srcD = srcData
        var srcV = srcValidity
        for (level <- 0 until depth) {
          val (outD, outV) = if (level == depth - 1) (dstData, dstVal)
          else (tmpData(level).address(), tmpValidity(level).address())
          if (level % 2 == 0) {
            DateVectorOps.vectorAddDays(srcD, srcV, srcNulls, outD, outV, numRows,
              offsets(level))
          } else {
            DateVectorOps.vectorSubDays(srcD, srcV, srcNulls, outD, outV, numRows,
              offsets(level))
          }
          srcD = outD
          srcV = outV
        }
      }

      runBenchmark("single op: emitted loop vs hand-written kernel") {
        val benchmark = new Benchmark(s"date_add over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val depth1 = emit(Seq(chain(1)), 1, 1, loader, 0)
        benchmark.addCase("hand-written kernel, null-free") { _ =>
          DateVectorOps.vectorAddDays(nfData.address(), 0L, 0,
            dst.address(), dstValidity.address(), numRows, 3)
        }
        benchmark.addCase("emitted loop, null-free") { _ =>
          depth1.run(Array(nfData.address()), Array(0L), Array(0),
            Array(dst.address()), Array(dstValidity.address()), Array(3), numRows)
        }
        benchmark.addCase("hand-written kernel, mixed nulls") { _ =>
          DateVectorOps.vectorAddDays(mxData.address(), mxValidity.address(), mxNulls,
            dst.address(), dstValidity.address(), numRows, 3)
        }
        benchmark.addCase("emitted loop, mixed nulls") { _ =>
          depth1.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
            Array(dst.address()), Array(dstValidity.address()), Array(3), numRows)
        }
        benchmark.run()
      }

      runBenchmark("datediff: emitted loop vs hand-written kernel") {
        val benchmark = new Benchmark(s"datediff over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val diff = emit(
          Seq(new DateDiff(new ColumnRef(0), new ColumnRef(1))), 2, 0, loader, 100)
        benchmark.addCase("hand-written kernel, null-free") { _ =>
          DateVectorOps.vectorDateDiff(nfData.address(), 0L, 0, nf2Data.address(), 0L, 0,
            dst.address(), dstValidity.address(), numRows)
        }
        benchmark.addCase("emitted loop, null-free") { _ =>
          diff.run(Array(nfData.address(), nf2Data.address()), Array(0L, 0L), Array(0, 0),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("hand-written kernel, mixed nulls") { _ =>
          DateVectorOps.vectorDateDiff(mxData.address(), mxValidity.address(), mxNulls,
            mx2Data.address(), mx2Validity.address(), mx2Nulls,
            dst.address(), dstValidity.address(), numRows)
        }
        benchmark.addCase("emitted loop, mixed nulls") { _ =>
          diff.run(Array(mxData.address(), mx2Data.address()),
            Array(mxValidity.address(), mx2Validity.address()), Array(mxNulls, mx2Nulls),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.run()
      }

      runBenchmark("fused chain vs sequential kernel passes") {
        val benchmark = new Benchmark(s"chain over $numRows rows, mixed nulls", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        for (depth <- Seq(1, 2, 4, 8, 16)) {
          val offsets = (0 until depth).map(level => level * 13 + 1).toArray
          val fused = emit(Seq(chain(depth)), 1, depth, loader, depth)
          benchmark.addCase(s"fused, depth $depth") { _ =>
            fused.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
              Array(dst.address()), Array(dstValidity.address()), offsets, numRows)
          }
          benchmark.addCase(s"sequential kernels, depth $depth") { _ =>
            sequentialChain(depth, offsets, mxData.address(), mxValidity.address(), mxNulls,
              dst.address(), dstValidity.address())
          }
        }
        benchmark.run()
      }

      runBenchmark("shared subchain across two outputs (DAG-CSE)") {
        // a = chain8(d); b = datediff(chain8(d), d2): 9 distinct ops. With the memo disabled
        // the same trees emit 17 op calls - the delta prices CSE itself, separately from
        // fusion. The sequential oracle is 9 kernel passes.
        val benchmark = new Benchmark(s"two outputs over $numRows rows, mixed nulls", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val shared = chain(8)
        val roots = Seq[VarkaVectorIR](shared, new DateDiff(shared, new ColumnRef(1)))
        val offsets = (0 until 8).map(level => level * 13 + 1).toArray
        val fused = emit(roots, 2, 8, loader, 200)
        VarkaEmitterTestSupport.setDisableCse(true)
        val fusedNoCse =
          try emit(roots, 2, 8, loader, 201)
          finally VarkaEmitterTestSupport.setDisableCse(false)
        def runFused(kernel: VarkaFusedKernel): Unit = {
          kernel.run(Array(mxData.address(), mx2Data.address()),
            Array(mxValidity.address(), mx2Validity.address()), Array(mxNulls, mx2Nulls),
            Array(dst.address(), dst2.address()),
            Array(dstValidity.address(), dst2Validity.address()), offsets, numRows)
        }
        benchmark.addCase("fused, CSE") { _ => runFused(fused) }
        benchmark.addCase("fused, memo disabled") { _ => runFused(fusedNoCse) }
        benchmark.addCase("sequential kernels (9 passes)") { _ =>
          sequentialChain(8, offsets, mxData.address(), mxValidity.address(), mxNulls,
            dst.address(), dstValidity.address())
          DateVectorOps.vectorDateDiff(dst.address(), dstValidity.address(), mxNulls,
            mx2Data.address(), mx2Validity.address(), mx2Nulls,
            dst2.address(), dst2Validity.address(), numRows)
        }
        benchmark.run()
      }

      runBenchmark("predication: CASE WHEN blend vs plain arithmetic (task 11)") {
        // The same depth-4 arithmetic with and without a comparison + blend wrapped around
        // it - pricing predication itself, in both bodies. The arms are disjoint chains over
        // each input so the predicated case does strictly more work.
        val benchmark = new Benchmark(s"depth-4 arms over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        def chainOver(base: VarkaVectorIR, depth: Int, slotBase: Int): VarkaVectorIR = {
          var node = base
          for (level <- 0 until depth) {
            node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
            else new SubDays(node, new LiteralSlot(slotBase + level))
          }
          node
        }
        val offsets = (0 until 8).map(level => level * 13 + 1).toArray
        val plain = emit(Seq(chainOver(new ColumnRef(0), 4, 0)), 2, 8, loader, 400)
        val blended = emit(Seq(new IfElse(
          new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1)),
          chainOver(new ColumnRef(0), 4, 0),
          chainOver(new ColumnRef(1), 4, 4))), 2, 8, loader, 401)
        def run(kernel: VarkaFusedKernel, d1: Long, v1: Long, n1: Int, d2v: Long, v2: Long,
            n2: Int): Unit = {
          kernel.run(Array(d1, d2v), Array(v1, v2), Array(n1, n2),
            Array(dst.address()), Array(dstValidity.address()), offsets, numRows)
        }
        benchmark.addCase("arithmetic depth 4, null-free") { _ =>
          run(plain, nfData.address(), 0L, 0, nf2Data.address(), 0L, 0)
        }
        benchmark.addCase("CASE WHEN, depth-4 arms, null-free") { _ =>
          run(blended, nfData.address(), 0L, 0, nf2Data.address(), 0L, 0)
        }
        benchmark.addCase("arithmetic depth 4, mixed nulls") { _ =>
          run(plain, mxData.address(), mxValidity.address(), mxNulls,
            mx2Data.address(), mx2Validity.address(), mx2Nulls)
        }
        benchmark.addCase("CASE WHEN, depth-4 arms, mixed nulls") { _ =>
          run(blended, mxData.address(), mxValidity.address(), mxNulls,
            mx2Data.address(), mx2Validity.address(), mx2Nulls)
        }
        benchmark.run()
      }

      runBenchmark("dayofweek: digit sum vs lanewise DIV vs LocalDate (task 11)") {
        val benchmark = new Benchmark(s"dayofweek over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val dow = emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 500)
        VarkaEmitterTestSupport.setDivFloorMod(true)
        val dowDiv =
          try emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 501)
          finally VarkaEmitterTestSupport.setDivFloorMod(false)
        benchmark.addCase("digit sum, null-free") { _ =>
          dow.run(Array(nfData.address()), Array(0L), Array(0),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("digit sum, mixed nulls") { _ =>
          dow.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("lanewise DIV, null-free") { _ =>
          dowDiv.run(Array(nfData.address()), Array(0L), Array(0),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("per-row LocalDate (the path Spark uses today)") { _ =>
          var i = 0
          while (i < numRows) {
            val days = nfData.get(ValueLayout.JAVA_INT, i * 4L)
            dst.set(ValueLayout.JAVA_INT, i * 4L,
              java.time.LocalDate.ofEpochDay(days).getDayOfWeek.plus(1).getValue)
            i += 1
          }
        }
        benchmark.run()
      }

      runBenchmark("widest shape: MAX_FUSED_NODES ops in one kernel") {
        // Four disjoint depth-16 chains: 64 distinct ops, the cap exactly - emitted as four
        // GROUP_BUDGET-sized loop methods since task 11, which is what keeps this case honest
        // in a JVM that has compiled every other kernel in this file first (see
        // PLAN_TASK_11.md section 6). The sequential version is the same 64 kernel passes.
        val benchmark = new Benchmark(s"4 outputs x depth 16 over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val roots = (0 until 4).map(k => chain(16, slotBase = k * 16))
        val offsets = (0 until 64).map(level => level * 7 + 1).toArray
        val fused = emit(roots, 1, 64, loader, 300)
        benchmark.addCase("fused, 64 ops") { _ =>
          fused.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
            wideDst.map(_.address()), wideDstValidity.map(_.address()), offsets, numRows)
        }
        benchmark.addCase("sequential kernels, 64 passes") { _ =>
          for (k <- 0 until 4) {
            sequentialChain(16, offsets.slice(k * 16, k * 16 + 16),
              mxData.address(), mxValidity.address(), mxNulls,
              wideDst(k).address(), wideDstValidity(k).address())
          }
        }
        benchmark.run()
      }
    } finally {
      loader.release()
      arena.close()
    }
  }
}
