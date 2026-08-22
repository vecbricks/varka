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

import scala.concurrent.duration._

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, DateAdd, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.{
  ClassFileGenOp, GenerateUnsafeProjection, VarkaClassFileGen, VarkaGeneratedClassLoader}
import org.apache.spark.sql.types.DateType

/**
 * Class-generation time benchmark (Task 7): per-op Gen-time for the Janino string path
 * (`GenerateUnsafeProjection.generate`, i.e. string generation + `CodeGenerator.compile`) vs the
 * Varka Class-File path (`VarkaClassFileGen.assembleKernelClass` + a fresh
 * [[VarkaGeneratedClassLoader]].defineGeneratedClass per "task"). A fresh literal each Janino
 * iteration defeats the global compiler cache so every iteration is a cold compile, matching the
 * first-query cost.
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
  private val kernelOp = ClassFileGenOp(
    "org.apache.spark.sql.varka.vector.DateVectorOps", "vectorAddDays", "(JJIJJII)V")

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

      benchmark.addCase("varka: assembleKernelClass + defineGeneratedClass", numIters = 5) { i =>
        val className = s"org.apache.spark.sql.varka.execution.VarkaKernelRunner$i"
        val bytes = VarkaClassFileGen.assembleKernelClass(className, kernelOp)
        val loader = new VarkaGeneratedClassLoader(Thread.currentThread().getContextClassLoader)
        sink = loader.defineGeneratedClass(className, bytes)
        loader.release()
      }

      benchmark.run()
    }
  }
}
