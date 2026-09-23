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
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection,
  VarkaExpressionCompiler, VarkaGeneratedClassLoader}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitBudget, VarkaEmitOptions,
  VarkaFusedKernel, VarkaLoopEmitter, VarkaSqlResolve, VarkaVectorIR}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.types.DateType

/**
 * What a kernel costs when one of its methods is too large for the JIT
 * (`sql/varka/plans/PLAN_TASK_87.md` section 6).
 *
 * HotSpot never compiles a method whose bytecode exceeds 8000 bytes
 * (`DontCompileHugeMethods`, on by default, with `HugeMethodLimit` fixed at 8000 in a product
 * build). Varka's emitter bounds its loop methods by grouping outputs, and did not bound the
 * epilogue - the one masked pass that handles the rows past the last full lane group - which
 * therefore holds every output of the kernel. A projection of `make_date` outputs pushes it
 * past 8000 bytes at thirteen outputs (masked form) and fourteen (dense form), from which point
 * that epilogue runs interpreted for the life of the JVM, and a batch whose length is not a
 * multiple of the lane count pays for it on every call.
 *
 * The ladder here is the plan's: `make_date(year(d), month(d), k)` for `k` in `1..n`, at 4, 8,
 * 12, 13, 14, 16, 32 and 60 outputs. Two batch lengths, because the cost is a property of the
 * tail: 1024 rows divide by every lane count, so the epilogue returns before any vector work;
 * 1031 leave a tail on every call. Two null arms, because a null-free batch runs the dense
 * driver and its epilogue and only a batch with nulls reaches the masked pair - the one that
 * crosses 8000 bytes first. Every case drives the same total row count through its chunks,
 * reading the same cache-warm prefix, so the columns compare directly and the tail is the only
 * thing that differs between the two lengths of one rung.
 *
 * Two forms, named explicitly so the labels survive the default changing: `single epilogue`,
 * the legacy emission (`methodByteBudget` 0), and `epilogue per group`, the emission under the
 * budget at HugeMethodLimit - a group's methods set up only their group, the epilogue is one
 * method per group, and the class is measured and regrouped (`PLAN_TASK_87.md` 3.1). The file
 * was first committed with the legacy form alone, so the baseline existed before the change
 * measured against it; both forms are in one table per rung so the columns compare directly.
 *
 * The null-free rows from 12 outputs on are a different cliff, and the file says so rather
 * than hiding it (`PLAN_MILESTONE_6.md` section 2.12, task 189). In some JVM forks the second
 * group's dense loop method enters a C2 deoptimization cycle - a new tier-4 compile installed
 * about every 250 milliseconds, the method's own compile time, and made not entrant on first
 * execution at a `profile_predicate` trap on the loop's back-edge - and runs interpreted for
 * the fork's life, a hundred times slower at both batch lengths alike; in other forks the same
 * method compiles once. `-XX:-UseProfiledLoopPredicate` removes the cycle. The band therefore
 * puts those rows in tier 3, unreadable from a diff, and a committed file shows whichever mode
 * its fork landed in. The masked arm never enters the cycle, so the masked rows carry the
 * epilogue comparison this file exists for.
 *
 * Outputs past the 28th add a year offset - `make_date(year(d) + 1, month(d), k)` - because a
 * day literal above 28 is not a valid date in every month and the emitted kernel would decline
 * the batch. The first 28 outputs are exactly the plan's shape; the 32- and 60-output rungs
 * carry one more operation per output beyond it, which is past the cliff either way.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaMethodSizeBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *          "catalyst/Test/runMain org.apache.spark.sql.VarkaMethodSizeBenchmark"
 *      Results will be written to "benchmarks/VarkaMethodSizeBenchmark-results.txt".
 *   3. both widths with provenance, the way every committed file here is made:
 *        dev/varka_bench_regen.sh catalyst VarkaMethodSizeBenchmark
 * }}}
 */
object VarkaMethodSizeBenchmark extends BenchmarkBase {

  private val numRows = 1_000_000

  /**
   * The rungs of the ladder, chosen to straddle the 8000-byte crossing rather than round.
   * `-Dvarka.bench.rungs=12,16` runs a subset, which is how one rung is put under a JVM
   * diagnostic flag without waiting for the others; a committed file always runs them all.
   */
  private val rungs: Seq[Int] = sys.props.get("varka.bench.rungs").filter(_.nonEmpty)
    .map(_.split(",").map(_.trim.toInt).toSeq).getOrElse(Seq(4, 8, 12, 13, 14, 16, 32, 60))

  /**
   * An even length, which every lane count divides, and a ragged one that none does.
   * `-Dvarka.bench.chunks=1024` runs one of them alone, so a kernel can be driven at a single
   * length under a diagnostic flag; a committed file always runs both.
   */
  private val chunks: Seq[(Int, String)] = {
    val all = Seq(1024 -> "even", 1031 -> "ragged")
    sys.props.get("varka.bench.chunks").filter(_.nonEmpty) match {
      case Some(list) =>
        val keep = list.split(",").map(_.trim.toInt).toSet
        all.filter { case (n, _) => keep(n) }
      case None => all
    }
  }

  /** The largest chunk decides the buffers: every call reads and writes the prefix `[0, n)`. */
  private val bufferRows = 4096

  /**
   * The null arms, `null-free` and `mixed nulls`; `-Dvarka.bench.nulls=null-free` runs one
   * alone. The dense and masked bodies are different methods, so one arm under a diagnostic
   * flag says something the pair cannot; a committed file always runs both.
   */
  private val nullArms: Seq[(Boolean, String)] = {
    val all = Seq(false -> "null-free", true -> "mixed nulls")
    sys.props.get("varka.bench.nulls").filter(_.nonEmpty) match {
      case Some(name) => all.filter(_._2 == name)
      case None => all
    }
  }

  private val d = AttributeReference("d", DateType)()
  private val columns: Seq[Attribute] = Seq(d)

  private val usedIds = scala.collection.mutable.Set.empty[String]

  /** The `n`-output projection, compiled the way production compiles it. */
  private def shape(n: Int): CompiledVarkaProjection = {
    val exprs = (1 to n).map { k =>
      val day = (k - 1) % 28 + 1
      val yearOffset = (k - 1) / 28
      val year = if (yearOffset == 0) "year(d)" else s"year(d) + $yearOffset"
      val sql = s"make_date($year, month(d), $day)"
      Alias(VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(sql), columns), s"c$k")()
    }
    VarkaExpressionCompiler.compile(exprs, columns).getOrElse(
      throw new IllegalStateException(s"the $n-output make_date projection did not fuse"))
  }

  /** The two forms, by label; the label is also the class name's suffix. */
  private val forms: Seq[(String, VarkaEmitOptions)] = Seq(
    "single epilogue" -> VarkaEmitOptions.DEFAULTS.withMethodByteBudget(0),
    "epilogue per group" ->
      VarkaEmitOptions.DEFAULTS.withMethodByteBudget(VarkaEmitBudget.HUGE_METHOD_LIMIT))

  private def emit(fused: CompiledVarkaProjection, loader: VarkaGeneratedClassLoader,
      n: Int, form: (String, VarkaEmitOptions)): VarkaFusedKernel = {
    val id = s"${n}_${form._1.replace(' ', '_')}"
    // A duplicate id is a LinkageError from the class loader, so it is refused here by name.
    require(usedIds.add(id), s"case id $id is already in use by another emit in this benchmark")
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedSizeBench$id"
    val javaRoots = new java.util.ArrayList[VarkaVectorIR]()
    fused.outputs.foreach(javaRoots.add)
    loader.defineGeneratedClass(name, VarkaLoopEmitter.emit(name, javaRoots,
      fused.inputOrdinals.size, fused.numLiterals, null, null, form._2))
    loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
  }

  /**
   * A date column of `bufferRows` days in `[-10000, 10000)`, every seventh row null when
   * `nulls` is set. Every year and month it yields makes a valid date with any day up to 28.
   */
  private def fill(arena: Arena, nulls: Boolean): (MemorySegment, MemorySegment) = {
    val data = arena.allocate(bufferRows * 4L, 64)
    val validity = arena.allocate(bufferRows / 8L, 64)
    validity.fill(0.toByte)
    for (i <- 0 until bufferRows) {
      data.set(ValueLayout.JAVA_INT, i * 4L, i % 20000 - 10000)
      if (!(nulls && i % 7 == 0)) {
        val off = i / 8L
        val old = validity.get(ValueLayout.JAVA_BYTE, off)
        validity.set(ValueLayout.JAVA_BYTE, off, (old | (1 << (i % 8))).toByte)
      }
    }
    (data, validity)
  }

  /** Nulls in the prefix `[0, n)` of the mixed arm, where rows 0, 7, 14, ... are null. */
  private def nullsIn(n: Int): Int = (n + 6) / 7

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    val arena = Arena.ofConfined()
    try {
      val (nfData, nfValidity) = fill(arena, nulls = false)
      val (mxData, mxValidity) = fill(arena, nulls = true)

      runBenchmark("the epilogue past HugeMethodLimit: a make_date ladder (task 87)") {
        for (n <- rungs) {
          val fused = shape(n)
          val kernels = forms.map { form => form._1 -> emit(fused, loader, n, form) }
          val literals = fused.literals.toArray
          // Every output is a date, four bytes a row; the validity segment is sized so a
          // word-writing kernel owns the whole last word at every chunk length.
          val dst = Array.fill(n)(arena.allocate(bufferRows * 4L, 64).address())
          val dstValidity = Array.fill(n)(arena.allocate(bufferRows / 8L, 64).address())

          def drive(kernel: VarkaFusedKernel, chunk: Int, mixed: Boolean): Int = {
            var status = 0
            var done = 0
            while (done < numRows) {
              val len = math.min(chunk, numRows - done)
              status |= (if (mixed) {
                kernel.run(Array(mxData.address()), Array(mxValidity.address()),
                  Array(nullsIn(len)), dst, dstValidity, literals, len)
              } else {
                kernel.run(Array(nfData.address()), Array(0L), Array(0),
                  dst, dstValidity, literals, len)
              })
              done += len
            }
            status
          }

          // A guard that trips would send the batch to the row engine in a query; here it
          // would silently time a kernel that did less than the whole batch, so it is refused.
          for ((label, kernel) <- kernels; (chunk, _) <- chunks; (mixed, _) <- nullArms) {
            val status = drive(kernel, chunk, mixed)
            require(status == 0,
              s"$n outputs, $label, at chunk $chunk (mixed=$mixed) declined with status $status")
          }

          val benchmark = new Benchmark(s"$n make_date outputs over $numRows rows in chunks",
            numRows, minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds,
            output = output)
          for ((label, kernel) <- kernels; (chunk, note) <- chunks; (mixed, arm) <- nullArms) {
            benchmark.addCase(s"$label, chunk $chunk ($note), $arm") { _ =>
              drive(kernel, chunk, mixed)
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
