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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaFusedKernel, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.varka.vector.DateVectorOps

/**
 * Task 9's two gates as a benchmark (see `sql/varka/plans/PLAN_TASK_9.md`).
 *
 * The parity gate: the emitted depth-1 loop must reach the hand-written `vectorAddDays` within
 * noise (acceptance: at least 0.9x its best-time throughput) - anything worse means C2 did not
 * intrinsify the emitted Vector API calls and the emitter is wrong. The chain cases are the
 * fusion preview and the data behind `MAX_CHAIN_DEPTH`: a fused depth-N chain against N
 * sequential kernel passes over the same buffers.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *          "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 *      Results will be written to "benchmarks/VarkaEmitterParityBenchmark-results.txt".
 *   3. the four-lane shape (numbers recorded in PLAN_TASK_9.md):
 *        build/sbt "project catalyst" 'set Test/javaOptions += "-XX:MaxVectorSize=16"'
 *          "Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 * }}}
 */
object VarkaEmitterParityBenchmark extends BenchmarkBase {

  private val numRows = 1_000_000

  private def chain(depth: Int): VarkaVectorIR = {
    var node: VarkaVectorIR = new ColumnRef(0)
    for (level <- 0 until depth) {
      node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(level))
      else new SubDays(node, new LiteralSlot(level))
    }
    node
  }

  private def emit(root: VarkaVectorIR, numLiterals: Int,
      loader: VarkaGeneratedClassLoader, n: Int): VarkaFusedKernel = {
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedBench$n"
    loader.defineGeneratedClass(name,
      VarkaLoopEmitter.emit(name, java.util.List.of(root), 1, numLiterals))
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
      val dst = arena.allocate(numRows * 4L, 8)
      val dstValidity = arena.allocate((numRows + 7) / 8L, 8)
      // Sequential-pass temporaries, one pair per intermediate of the deepest chain.
      val maxDepth = 16
      val tmpData = Array.fill(maxDepth)(arena.allocate(numRows * 4L, 8))
      val tmpValidity = Array.fill(maxDepth)(arena.allocate((numRows + 7) / 8L, 8))

      runBenchmark("single op: emitted loop vs hand-written kernel") {
        val benchmark = new Benchmark(s"date_add over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val depth1 = emit(chain(1), 1, loader, 0)
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

      runBenchmark("fused chain vs sequential kernel passes") {
        val benchmark = new Benchmark(s"chain over $numRows rows, mixed nulls", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        for (depth <- Seq(1, 2, 4, 8, 16)) {
          val offsets = (0 until depth).map(level => level * 13 + 1).toArray
          val fused = emit(chain(depth), depth, loader, depth)
          benchmark.addCase(s"fused, depth $depth") { _ =>
            fused.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
              Array(dst.address()), Array(dstValidity.address()), offsets, numRows)
          }
          benchmark.addCase(s"sequential kernels, depth $depth") { _ =>
            var srcD = mxData.address()
            var srcV = mxValidity.address()
            for (level <- 0 until depth) {
              val (outD, outV) = if (level == depth - 1) (dst, dstValidity)
              else (tmpData(level), tmpValidity(level))
              if (level % 2 == 0) {
                DateVectorOps.vectorAddDays(srcD, srcV, mxNulls,
                  outD.address(), outV.address(), numRows, offsets(level))
              } else {
                DateVectorOps.vectorSubDays(srcD, srcV, mxNulls,
                  outD.address(), outV.address(), numRows, offsets(level))
              }
              srcD = outD.address()
              srcV = outV.address()
            }
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
