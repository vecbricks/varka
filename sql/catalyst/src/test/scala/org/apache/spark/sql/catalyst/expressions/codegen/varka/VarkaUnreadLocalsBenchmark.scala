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
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader

/**
 * Task 239's admission measurement (`PLAN_TASK_239.md` 2.2 and 2.3): what the reference locals a
 * loop or epilogue method stores and never reads cost a kernel at run time, measured before the
 * emitter changes by stripping them from the built class (`VarkaUnreadLocalsTrim`). The size
 * ladder and the mixed family as `VarkaWideKernelBenchmark` runs them, a million rows in 4096-row
 * batches, null-free and with every seventh row null; each kernel as emitted, stripped of every
 * kind, of the segments alone and of the prefix reloads alone at the widest size, and as emitted
 * again last, a control for the order of the cases. Then what one batch allocates, as emitted and
 * stripped, from the JVM's per-thread counter after the timed runs have warmed the kernels.
 *
 * {{{
 *   SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "catalyst/Test/runMain \
 *     org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaUnreadLocalsBenchmark"
 * }}}
 */
object VarkaUnreadLocalsBenchmark extends BenchmarkBase {

  private val numRows = 1_000_000
  private val chunk = 4096
  private var nextId = 0

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val arena = Arena.ofConfined()
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      val data = arena.allocate(numRows * 4L, 8)
      val validity = arena.allocate((numRows + 7) / 8L, 8)
      for (i <- 0 until numRows) {
        data.setAtIndex(ValueLayout.JAVA_INT, i, (i * 37) % 22000)
        if (i % 7 != 0) {
          val off = i / 8L
          validity.set(ValueLayout.JAVA_BYTE, off,
            (validity.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))).toByte)
        }
      }
      val batchNulls = (0 until numRows by chunk).map { start =>
        (start until math.min(start + chunk, numRows)).count(_ % 7 == 0)
      }.toArray
      val dsts = Array.fill(400)(arena.allocate(chunk * 4L, 8))
      val dstValidities = Array.fill(400)(arena.allocate(chunk / 8L, 8))

      def kernel(roots: Seq[VarkaVectorIR], kinds: Seq[String]): VarkaFusedKernel = {
        nextId += 1
        val name = s"org.apache.spark.sql.varka.execution.VarkaUnreadLocalsBench$nextId"
        val emitted = VarkaLoopEmitter.emit(name, roots.asJava, 1, roots.size, null, null,
          VarkaEmitOptions.DEFAULTS)
        val bytes =
          if (kinds.isEmpty) emitted else VarkaUnreadLocalsTrim.trim(emitted, kinds.asJava)
        loader.defineGeneratedClass(name, bytes)
        loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
      }

      // Every argument array a batch passes, made once per output count, so that what a batch
      // allocates is the kernel's alone.
      val argsByOutputs = scala.collection.mutable.Map.empty[Int, (Array[Int], Array[Long],
        Array[Long])]
      def args(outputs: Int): (Array[Int], Array[Long], Array[Long]) =
        argsByOutputs.getOrElseUpdate(outputs, (Array.tabulate(outputs)(i => i % 12 + 1),
          dsts.take(outputs).map(_.address()), dstValidities.take(outputs).map(_.address())))
      val srcData = Array.tabulate(numRows / chunk + 1)(b => Array(data.address() + b * chunk * 4L))
      val srcValidity = Array.tabulate(numRows / chunk + 1)(b =>
        Array(validity.address() + b * chunk / 8L))
      val noValidity = Array(0L)
      val nullCounts = batchNulls.map(n => Array(n))
      val noNulls = Array(0)

      /** Batch `batch` of the column through `k`. */
      def runBatch(k: VarkaFusedKernel, a: (Array[Int], Array[Long], Array[Long]),
          withNulls: Boolean, batch: Int): Unit = {
        val (lits, dstData, dstValidity) = a
        val n = math.min(chunk, numRows - batch * chunk)
        val status = k.run(srcData(batch), if (withNulls) srcValidity(batch) else noValidity,
          if (withNulls) nullCounts(batch) else noNulls, dstData, dstValidity, lits, n)
        require(status == 0, s"the kernel declined a batch: status $status")
      }

      def scan(k: VarkaFusedKernel, outputs: Int, withNulls: Boolean): Unit = {
        val a = args(outputs)
        var batch = 0
        while (batch * chunk < numRows) {
          runBatch(k, a, withNulls, batch)
          batch += 1
        }
      }

      val all = Seq("segment", "vector", "mask", "shared slot", "other")
      val families = Seq(
        ("the size ladder", Seq(100, 400),
          (n: Int) => (0 until n).map(VarkaEmitCostCorpus.ladderEntry)),
        ("the mixed family", Seq(100, 200),
          (n: Int) => VarkaGroupingBound.mixed(n).asScala.toSeq))
      val mx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
      val tid = Thread.currentThread().getId
      for ((family, sizes, roots) <- families) {
        val kernels = scala.collection.mutable.ArrayBuffer.empty[(String, VarkaFusedKernel, Int)]
        runBenchmark(s"$family, as emitted and stripped of the locals nothing reads") {
          val benchmark = new Benchmark(s"$numRows rows in $chunk-row batches", numRows,
            minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
          for (withNulls <- Seq(false, true); n <- sizes) {
            val label = if (withNulls) "every seventh row null" else "null-free"
            val arms = Seq("as emitted" -> Seq.empty[String], "stripped" -> all) ++
              (if (n == sizes.max) {
                Seq("segments stripped" -> Seq("segment"), "reloads stripped" -> Seq("vector"))
              } else Nil) ++
              Seq("as emitted, again" -> Seq.empty[String])
            for ((arm, kinds) <- arms) {
              val k = kernel(roots(n), kinds)
              if (n == sizes.max && (arm == "as emitted" || arm == "stripped")) {
                kernels += ((s"$n entries, $arm, $label", k, n))
              }
              benchmark.addCase(s"$n entries, $arm, $label") { _ => scan(k, n, withNulls) }
            }
          }
          benchmark.run()
          // What one batch allocates, after the timed runs have compiled the kernels.
          val lines = kernels.map { case (label, k, n) =>
            val withNulls = label.endsWith("every seventh row null")
            val a = args(n)
            (0 until 1000).foreach(_ => runBatch(k, a, withNulls, 0))
            val before = mx.getThreadAllocatedBytes(tid)
            (0 until 1000).foreach(_ => runBatch(k, a, withNulls, 0))
            val after = mx.getThreadAllocatedBytes(tid)
            f"$label%-60s ${(after - before) / 1000.0}%10.1f bytes allocated a batch"
          }
          val text = ("" +: "Allocation, one 4096-row batch:" +: lines :+ "").mkString("\n")
          output.foreach(_.write((text + "\n").getBytes(StandardCharsets.UTF_8)))
          // scalastyle:off println
          println(text)
          // scalastyle:on println
        }
      }
    } finally {
      arena.close()
    }
  }
}
