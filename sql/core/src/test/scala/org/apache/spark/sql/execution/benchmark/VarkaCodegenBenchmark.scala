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

package org.apache.spark.sql.execution.benchmark

import java.util.{List => JList}

import scala.concurrent.duration._

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, DateAdd, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.{
  GenerateUnsafeProjection, VarkaGeneratedClassLoader}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.types.DateType

/**
 * Class-generation time benchmark (Task 7): Gen-time for the Janino string path
 * (`GenerateUnsafeProjection.generate`, i.e. string generation + `CodeGenerator.compile`) vs the
 * Varka Class-File path - `VarkaLoopEmitter.emit` of a representative fused kernel (two outputs
 * sharing a subchain, the shape the throughput benchmark's DAG-CSE case runs) plus define, load
 * and instantiate through a fresh [[VarkaGeneratedClassLoader]], which is the exact per-task
 * work `VarkaKernelEvaluator`'s runner does. A fresh literal each Janino iteration defeats the
 * global compiler cache so every iteration is a cold compile, matching the first-query cost.
 *
 * Task 14 added the fused case beside milestone 1's per-op dispatcher case; task 17 retired the
 * dispatchers with the rest of that layer, so what remains measures only machinery that runs.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaCodegenBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain ..."
 *      Results will be written to "benchmarks/VarkaCodegenBenchmark-jdk<NN>-results.txt".
 * }}}
 */
object VarkaCodegenBenchmark extends BenchmarkBase {

  private val startAttr = AttributeReference("d", DateType)()
  private val fields = Seq(startAttr)
  // The representative fused-kernel IR: `date_add(d, 1) AS a, datediff(date_add(d, 1), d2) AS b`
  // - two outputs, a shared subchain, two inputs, one literal slot. The same shape as the
  // throughput benchmark's DAG-CSE case, so the two committed files describe one kernel.
  private val sharedChain = new VarkaVectorIR.AddDays(
    new VarkaVectorIR.ColumnRef(0), new VarkaVectorIR.LiteralSlot(0))
  private val fusedOutputs: JList[VarkaVectorIR] = JList.of(
    sharedChain, new VarkaVectorIR.DateDiff(sharedChain, new VarkaVectorIR.ColumnRef(1)))

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    runBenchmark("Varka per-op class generation (Gen-time)") {
      val benchmark = new Benchmark("class generation time", 1,
        minNumIters = 2, warmupTime = 1.second, minTime = 1.second, output = output)

      // Keep the compiled result alive so the compile work cannot be elided.
      var sink: AnyRef = null

      benchmark.addCase("janino: GenerateUnsafeProjection.generate", numIters = 5) { i =>
        // A fresh literal per iteration defeats the CodeGenerator cache so each iteration is a
        // cold string-generate + Janino compile.
        sink = GenerateUnsafeProjection.generate(
          Seq(DateAdd(startAttr, Literal(i + 1))), fields)
      }

      benchmark.addCase("varka: fused emit + define + load + instantiate", numIters = 5) { i =>
        // The per-task sequence VarkaKernelEvaluator's runner performs for the fused loop.
        val className = s"org.apache.spark.sql.varka.execution.VarkaFusedProjectionBench$i"
        val bytes = VarkaLoopEmitter.emit(className, fusedOutputs, 2, 1)
        val loader = new VarkaGeneratedClassLoader(Thread.currentThread().getContextClassLoader)
        loader.defineGeneratedClass(className, bytes)
        sink = loader.loadClass(className).getConstructor().newInstance().asInstanceOf[AnyRef]
        loader.release()
      }

      benchmark.run()
    }
  }
}
