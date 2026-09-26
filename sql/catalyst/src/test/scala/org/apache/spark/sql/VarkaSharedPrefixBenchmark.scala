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

import java.lang.foreign.{Arena, ValueLayout}

import scala.concurrent.duration._

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection,
  VarkaExpressionCompiler, VarkaGeneratedClassLoader}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions,
  VarkaEmitterTestSupport, VarkaFusedKernel, VarkaLoopEmitter, VarkaSqlResolve, VarkaVectorIR}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.types.DateType

/**
 * What recomputing the civil-from-days prefix costs, per group that recomputes it (task 198's
 * admission check, `sql/varka/plans/PLAN_TASK_198.md`).
 *
 * A calendar output - `year(d)`, `month(d)`, `make_date(year(d), month(d), k)` - decomposes its
 * date through a prefix of about forty vector operations, and the outputs of one loop method
 * that decompose the same date share it. A kernel whose outputs are split across several loop
 * methods recomputes it in each. Task 198 would compute it once per batch into a scratch buffer
 * the later methods read; before that is built, this measures what it could save, with no new
 * code: the same outputs emitted with the fused ceiling lowered, so the emitter splits them into
 * more groups and every extra group recomputes the prefix once more. The difference between two
 * arms of one table is the price of the extra recomputations, plus one method call per group per
 * batch, which is the whole ceiling of what a computed-once prefix can win on that shape.
 *
 * Two shapes. Cheap tails over one date, `year(d) + k`, where the prefix is most of each
 * group's work and the ceiling is widest; and the make_date ladder's top rung, 60
 * `make_date(year(d), month(d), k)` outputs, the shape `PLAN_TASK_87.md` 3.3 counted eleven
 * repeated prefixes in. Each arm's table row names its group count, read from the emitted class,
 * so the x-axis is in the file. Null-free batches of 1024 rows, which every lane count divides:
 * the loop methods are what is measured, and the epilogue returns at once.
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh catalyst VarkaSharedPrefixBenchmark
 * }}}
 */
object VarkaSharedPrefixBenchmark extends BenchmarkBase {

  private val numRows = 1_000_000
  private val chunk = 1024

  private val d = AttributeReference("d", DateType)()
  private val columns: Seq[Attribute] = Seq(d)
  private val usedIds = scala.collection.mutable.Set.empty[String]

  private def compile(sqls: Seq[String]): CompiledVarkaProjection = {
    val exprs = sqls.zipWithIndex.map { case (sql, k) =>
      Alias(VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(sql), columns), s"c$k")()
    }
    VarkaExpressionCompiler.compile(exprs, columns).getOrElse(
      throw new IllegalStateException(s"the projection did not fuse: ${sqls.head}, ..."))
  }

  /** The two shapes, by title. */
  private val shapes: Seq[(String, Seq[String])] = Seq(
    "64 cheap tails over one date: year(d) + k" -> (1 to 64).map(k => s"year(d) + $k"),
    "60 make_date(year(d), month(d), k) outputs" -> (1 to 60).map { k =>
      val day = (k - 1) % 28 + 1
      val yearOffset = (k - 1) / 28
      val year = if (yearOffset == 0) "year(d)" else s"year(d) + $yearOffset"
      s"make_date($year, month(d), $day)"
    })

  /** Fused ceilings from the default down, each splitting the outputs into more groups. */
  private val ceilings = Seq(400, 200, 100, 50)

  private def emit(fused: CompiledVarkaProjection, loader: VarkaGeneratedClassLoader,
      id: String, options: VarkaEmitOptions): (VarkaFusedKernel, Int) = {
    require(usedIds.add(id), s"case id $id is already in use by another emit in this benchmark")
    val name = s"org.apache.spark.sql.varka.execution.VarkaSharedPrefixBench$id"
    val roots = new java.util.ArrayList[VarkaVectorIR]()
    fused.outputs.foreach(roots.add)
    val bytes = VarkaLoopEmitter.emit(name, roots, fused.inputOrdinals.size, fused.numLiterals,
      null, null, options)
    val groups = VarkaEmitterTestSupport.methodNames(bytes).toArray
      .count(_.toString.startsWith("loopDense"))
    loader.defineGeneratedClass(name, bytes)
    (loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel], groups)
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    val arena = Arena.ofConfined()
    try {
      val data = arena.allocate(chunk * 4L, 64)
      for (i <- 0 until chunk) data.set(ValueLayout.JAVA_INT, i * 4L, i * 7 % 20000 - 10000)
      runBenchmark("the civil-from-days prefix, recomputed per group") {
        for (((title, sqls), s) <- shapes.zipWithIndex) {
          val fused = compile(sqls)
          val n = sqls.size
          val literals = fused.literals.toArray
          val dst: Array[Long] = Array.fill(n)(arena.allocate(chunk * 4L, 64).address())
          val dstValidity: Array[Long] =
            Array.fill(n)(arena.allocate(chunk / 8L, 64).address())
          val arms = ceilings.map { c =>
            val (kernel, groups) = emit(fused, loader, s"${s}_$c",
              VarkaEmitOptions.DEFAULTS.withFusedCeiling(c))
            (c, groups, kernel)
          }
          def drive(kernel: VarkaFusedKernel): Int = {
            var status = 0
            var done = 0
            while (done < numRows) {
              val len = math.min(chunk, numRows - done)
              status |= kernel.run(Array(data.address()), Array(0L), Array(0), dst, dstValidity,
                literals, len)
              done += len
            }
            status
          }
          for ((c, _, kernel) <- arms) {
            require(drive(kernel) == 0, s"$title at ceiling $c declined a batch")
          }
          val benchmark = new Benchmark(s"$title over $numRows rows", numRows,
            minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
          for ((c, groups, kernel) <- arms) {
            benchmark.addCase(s"fused ceiling $c: $groups group${if (groups == 1) "" else "s"}") {
              _ => drive(kernel)
            }
          }
          benchmark.run()
        }
      }
    } finally {
      arena.close()
    }
  }
}
