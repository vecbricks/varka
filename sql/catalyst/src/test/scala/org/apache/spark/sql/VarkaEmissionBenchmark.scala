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

import scala.concurrent.duration._

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * What it costs to *build* a fused kernel, as opposed to running one: the time
 * [[VarkaLoopEmitter]] spends turning an IR tree into class bytes, and the time the JVM spends
 * defining the class those bytes describe.
 *
 * This is the cost a query pays once per distinct shape, before the first batch and behind the
 * shape cache, so it never appears in a throughput figure. It is worth its own file for two
 * reasons. It is the number a reader asks for when told that a query compiles itself - "how long
 * does that take" has had no committed answer, only an estimate in a plan. And it is the number
 * the lane refactor moves: parameterising the emitter on a lane descriptor builds a
 * `MethodTypeDesc` per lane member and consults the descriptor at every one of the forty
 * per-lane sites, which is work done while emitting. A baseline measured before that change is
 * what makes "the refactor costs nothing at emit time" a claim that can be checked rather than
 * asserted.
 *
 * Two sections.
 *
 * **Emission alone** calls `VarkaLoopEmitter.emit` and throws the bytes away. It isolates the
 * emitter: the IR walk, the analysis pass, the loop and epilogue bodies, the constant pool. The
 * shapes rise from one leaf to a four-output projection over calendar nodes, so a reader can see
 * how the cost scales with what is being compiled rather than only its value for one shape.
 *
 * **Emission and definition** adds `VarkaGeneratedClassLoader.defineKernelClass`, which is what
 * a cold query actually pays: the JVM verifies the bytes and links the class. The pair prices
 * the emitter against the platform, and the split matters because only the first half is
 * Varka's to improve.
 *
 * Every case emits a fresh class each iteration - the shape cache is deliberately not involved,
 * since its hit path is task 18's subject and costs a map lookup.
 *
 * {{{
 *   build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaEmissionBenchmark"
 *   SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
 *     "catalyst/Test/runMain org.apache.spark.sql.VarkaEmissionBenchmark"
 * }}}
 */
object VarkaEmissionBenchmark extends BenchmarkBase {

  /** Emissions per measured iteration: enough that one iteration is milliseconds, not micros. */
  private val emissionsPerIteration = 200

  private val col = new ColumnRef(0)
  private val col1 = new ColumnRef(1)
  private val lit = new LiteralSlot(0)

  /** The shapes, smallest first, with the number of inputs and literals each needs. */
  private val shapes: Seq[(String, Seq[VarkaVectorIR], Int, Int)] = Seq(
    ("a column, copied", Seq(col), 1, 0),
    ("d + 3", Seq(new AddDays(col, lit)), 1, 1),
    ("i + 1, ANSI checked", Seq(new IntArith(IntOp.ADD, Overflow.FAIL, col, lit)), 1, 1),
    ("year(d)", Seq(new Year(col)), 1, 0),
    ("a predicate: d < d2 AND d IS NOT NULL",
      Seq(new And(new Compare(CompareOp.LT, col, col1), new IsNotNull(col))), 2, 0),
    ("make_date(year(d), month(d), 1)",
      Seq(new MakeDate(new Year(col), new Month(col), lit, true)), 1, 1),
    ("four calendar outputs over one date",
      Seq(new Year(col), new Month(col), new DayOfMonth(col),
        new WeekOfYear(new ThursdayOf(col))), 1, 0))

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val options = VarkaEmitOptions.DEFAULTS
    var sink = 0

    runBenchmark("emitting a fused kernel: bytes only") {
      val benchmark = new Benchmark("one emission", emissionsPerIteration,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      shapes.foreach { case (name, roots, inputs, literals) =>
        benchmark.addCase(name) { _ =>
          var i = 0
          while (i < emissionsPerIteration) {
            val bytes = VarkaLoopEmitter.emit("VarkaEmissionBenchmarkKernel",
              java.util.List.of(roots: _*), inputs, literals, null, null, options)
            // Read one byte so the emission cannot be optimized away as dead.
            sink += bytes.length
            i += 1
          }
        }
      }
      benchmark.run()
    }

    runBenchmark("emitting a fused kernel: bytes and a defined class") {
      val benchmark = new Benchmark("one emission and definition", emissionsPerIteration,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      shapes.foreach { case (name, roots, inputs, literals) =>
        benchmark.addCase(name) { _ =>
          var i = 0
          while (i < emissionsPerIteration) {
            // A loader per class, as the shape cache gives each entry, so a defined class can be
            // collected rather than accumulating for the length of the run.
            val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
            val className = s"VarkaEmissionBenchmarkKernel$i"
            val bytes = VarkaLoopEmitter.emit(className, java.util.List.of(roots: _*), inputs,
              literals, null, null, options)
            sink += loader.defineGeneratedClass(className, bytes).getSimpleName.length
            loader.release()
            i += 1
          }
        }
      }
      benchmark.run()
    }

    // Task 190: kernels as wide as a real projection gets once the op cap no longer bounds them.
    // One emission per iteration - a four-hundred-output emission is not microseconds - and the
    // byte budget set out of reach at 200 and 400 outputs, where the default would decline the
    // driver: the rungs price emission itself, which the designs past the driver's ceiling
    // (PLAN_TASK_190.md 3.2) both pay, and prediction 2 there reads them.
    runBenchmark("emitting a wide kernel: four-op outputs (task 190)") {
      val benchmark = new Benchmark("one emission", 1,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      def entry(k: Int): VarkaVectorIR = new Greatest(new Greatest(
        new AddMonths(col, new LiteralSlot(k)), new AddDays(col, new LiteralSlot(k))),
        new LastDay(col))
      for (n <- Seq(25, 50, 100, 200, 400)) {
        val roots = java.util.List.of((0 until n).map(entry): _*)
        val wide = if (n <= 100) options else options.withMethodByteBudget(1 << 20)
        val label = if (n <= 100) s"$n outputs" else s"$n outputs, budget out of reach"
        benchmark.addCase(label) { _ =>
          sink += VarkaLoopEmitter.emit("VarkaEmissionBenchmarkWide", roots, 1, n, null, null,
            wide).length
        }
      }
      benchmark.run()
    }

    // Keeps the emitted bytes and the defined classes live to the end of the run.
    if (sink == Int.MinValue) {
      // scalastyle:off println
      println(sink)
      // scalastyle:on println
    }
  }
}
