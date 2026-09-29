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
 * What recomputing the civil-from-days prefix costs, per group that recomputes it, against
 * computing it once per batch (task 198, `sql/varka/plans/PLAN_TASK_198.md`).
 *
 * A calendar output - `year(d)`, `month(d)`, `make_date(year(d), month(d), k)` - decomposes its
 * date through a prefix of about forty vector operations, and the outputs of one loop method
 * that decompose the same date share it. A kernel whose outputs are split across several loop
 * methods recomputes it in each - or, under `materializeChronoPrefix`, the first method stores
 * the prefix's vectors into a scratch region the caller passes and the later methods load them.
 * Each table takes the same outputs at the fused ceiling lowered step by step, so the emitter
 * splits them into more groups, and at each ceiling emits them both ways: the recomputed arm
 * and the materialized arm at the same group count, so their difference is what the
 * materialization wins at that many groups, and the recomputed arms' differences price the
 * recomputations the way the admission check did before the mechanism was built.
 *
 * Two shapes. Cheap tails over one date, `year(d) + k`, where the prefix is most of each
 * group's work and the ceiling is widest; and the make_date ladder's top rung, 60
 * `make_date(year(d), month(d), k)` outputs, the shape `PLAN_TASK_87.md` 3.3 counted eleven
 * repeated prefixes in. Each arm's table row names its group count, read from the emitted class,
 * so the x-axis is in the file. Null-free batches of 1024 rows, which every lane count divides:
 * the loop methods are what is measured, and the epilogue returns at once.
 *
 * Two more arms at the default ceiling price the call-site budget (task 209), which splits a
 * wide group whose loop method C1 refuses and leaves a narrow one: the budget off, so the cheap
 * tails are the one loop method past C1 the census measured; and the budget with its heavy-group
 * exemption off, so the make_date outputs split to one a method as the cheap tails do. The
 * default arms carry the budget as production does.
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
      runBenchmark("the civil-from-days prefix, recomputed per group or computed once") {
        for (((title, sqls), s) <- shapes.zipWithIndex) {
          val fused = compile(sqls)
          val n = sqls.size
          val literals = fused.literals.toArray
          val dst: Array[Long] = Array.fill(n)(arena.allocate(chunk * 4L, 64).address())
          val dstValidity: Array[Long] =
            Array.fill(n)(arena.allocate(chunk / 8L, 64).address())
          // Per ceiling, the recomputed arm and the materialized one; the latter takes the
          // scratch its kernel asks for, sized by the batch. Then the two call-site budget arms
          // at the default ceiling, materialized: the budget off, and every group split.
          val budgetArms = Seq(
            "call-site budget off" -> VarkaEmitOptions.DEFAULTS.withCallSiteBudget(0),
            "every group split" -> VarkaEmitOptions.DEFAULTS.withHeavyGroupOutputs(0))
          val arms = ceilings.flatMap { c =>
            Seq(false, true).map { materialized =>
              val (kernel, groups) = emit(fused, loader, s"${s}_${c}_$materialized",
                VarkaEmitOptions.DEFAULTS.withFusedCeiling(c)
                  .withMaterializeChronoPrefix(materialized))
              val scratch = VarkaEmitterTestSupport.scratch(kernel, chunk)
              (s"fused ceiling $c", materialized, groups, kernel, scratch)
            }
          } ++ budgetArms.map { case (label, options) =>
            val (kernel, groups) = emit(fused, loader, s"${s}_$label", options)
            val scratch = VarkaEmitterTestSupport.scratch(kernel, chunk)
            (s"fused ceiling ${ceilings.head}, $label", true, groups, kernel, scratch)
          }
          def drive(kernel: VarkaFusedKernel, scratch: Long): Int = {
            var status = 0
            var done = 0
            while (done < numRows) {
              val len = math.min(chunk, numRows - done)
              status |= kernel.run(Array(data.address()), Array(0L), Array(0), dst, dstValidity,
                literals, len, scratch)
              done += len
            }
            status
          }
          for ((arm, materialized, _, kernel, scratch) <- arms) {
            require(drive(kernel, scratch) == 0,
              s"$title at $arm, materialized $materialized, declined a batch")
          }
          val benchmark = new Benchmark(s"$title over $numRows rows", numRows,
            minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
          for ((arm, materialized, groups, kernel, scratch) <- arms) {
            val name = s"$arm: $groups group${if (groups == 1) "" else "s"}" +
              (if (materialized) ", prefix computed once" else ", prefix per group")
            benchmark.addCase(name) { _ => drive(kernel, scratch) }
          }
          benchmark.run()
        }
      }
    } finally {
      arena.close()
    }
  }
}
