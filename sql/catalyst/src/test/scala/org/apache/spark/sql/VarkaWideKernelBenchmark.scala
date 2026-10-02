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
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitDeclined, VarkaEmitOptions, VarkaFusedKernel, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * Kernels wider than one driver method could once hold (task 190 step 2): what a projection of
 * hundreds of outputs costs per row when it runs as one fused kernel, and what each way of
 * getting there past the driver's ceiling costs against the others.
 *
 * The first section prices the table-driven driver (`VarkaEmitOptions.driverOutputTable`)
 * against the unrolled one on the widest projection both can emit, a hundred entries of the
 * size ladder's `greatest(add_months(d, k), date_add(d, k), last_day(d))`, and then runs the
 * table form alone at four hundred, which the unrolled driver cannot emit. The driver's
 * per-output work runs once per batch, so the batches are the 4096 rows a columnar scan hands
 * a kernel, over a million-row date column, null-free and with every seventh row null - the
 * dense body and the masked body with its bitmap pass. See `PLAN_TASK_190.md` 9.2 and 10.
 *
 * The second section prices the exact grouping (`VarkaEmitOptions.exactGrouping`) against the
 * greedy walk on the mixed family, where the greedy walk leaves every `date_add` in a loop method
 * of its own and the exact partition does not, at a hundred and two hundred entries, with the
 * four-hundred-entry ladder as the control, whose grouping the switch leaves as it is. The third
 * times one emission of the mixed family under each, for the partition's cost at plan time. See
 * `PLAN_TASK_200.md` 4.
 *
 * A section prices the locals a loop or epilogue method stores and never reads
 * (`VarkaEmitOptions.elideUnreadLocals`): the size ladder at a hundred and four hundred entries
 * and the mixed family at a hundred and two hundred, with them built, elided, and built again
 * last as a control for the order of the cases. See `PLAN_TASK_239.md` 6.
 *
 * The last two sections go past the driver from a table's own ceiling, about 180 groups: eight
 * hundred and twelve hundred ladder entries, two and three hundred groups. One kernel whose driver
 * calls its groups through stages (`VarkaEmitOptions.splitDriver`, A') is priced against several
 * kernels run in turn over each batch (`VarkaEmitOptions.severalKernels`, B), split as the
 * compiler splits them - the largest prefix one kernel serves, then the rest - each reading the
 * input again and recomputing the prefix it shares. Then one emission of each, with the bytes
 * of the classes. See `PLAN_TASK_190.md` 11.
 *
 * {{{
 *   build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaWideKernelBenchmark"
 *   SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
 *     "catalyst/Test/runMain org.apache.spark.sql.VarkaWideKernelBenchmark"
 * }}}
 */
object VarkaWideKernelBenchmark extends BenchmarkBase {

  private val numRows = 1_000_000
  private val chunk = 4096

  private def entry(k: Int): VarkaVectorIR = {
    val col = new ColumnRef(0)
    new Greatest(new Greatest(new AddMonths(col, new LiteralSlot(k)),
      new AddDays(col, new LiteralSlot(k))), new LastDay(col))
  }

  /** The two groupings the exact grouping's sections compare, each named for its form. */
  private val groupings = Seq(
    "greedy grouping" -> VarkaEmitOptions.DEFAULTS.withExactGrouping(false),
    "exact grouping" -> VarkaEmitOptions.DEFAULTS.withExactGrouping(true))

  /**
   * The mixed family's entry `k`: the size ladder's entry, `make_date(year(d), month(d), k)`, the
   * cheap tail `year(d) + k` and `date_add(d, k)`, in rotation over one date. The same shapes as
   * the catalyst suites' `VarkaGroupingBound.mixed`, which is not visible from this package.
   */
  private def mixedEntry(k: Int): VarkaVectorIR = {
    val col = new ColumnRef(0)
    k % 4 match {
      case 0 => entry(k)
      case 1 => new MakeDate(new Year(col), new Month(col), new LiteralSlot(k), true)
      case 2 => new IntArith(IntOp.ADD, Overflow.WRAP, new Year(col), new LiteralSlot(k))
      case _ => new AddDays(col, new LiteralSlot(k))
    }
  }

  private var nextId = 0

  private def emit(roots: Seq[VarkaVectorIR], options: VarkaEmitOptions,
      loader: VarkaGeneratedClassLoader): VarkaFusedKernel = {
    nextId += 1
    val name = s"org.apache.spark.sql.varka.execution.VarkaWideBench$nextId"
    loader.defineGeneratedClass(name, VarkaLoopEmitter.emit(name,
      java.util.List.of(roots: _*), 1, roots.size, null, null, options))
    loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
  }

  /** `n` ladder entries numbered from literal slot 0: one kernel's roots. */
  private def ladder(n: Int): Seq[VarkaVectorIR] = (0 until n).map(entry)

  /**
   * How the compiler splits `n` ladder entries under `severalKernels`: the largest prefix one
   * kernel serves, found by halving as `VarkaExpressionCompiler.classify` does, then the same for
   * the rest. Each kernel's entries are numbered from its own literal slot 0.
   */
  private def kernelSizes(n: Int, options: VarkaEmitOptions): Seq[Int] = {
    def fits(k: Int): Boolean =
      try {
        VarkaLoopEmitter.emit("VarkaWideBenchFit", java.util.List.of(ladder(k): _*), 1, k,
          null, null, options)
        true
      } catch {
        case _: VarkaEmitDeclined => false
      }
    if (n == 0) {
      Nil
    } else if (fits(n)) {
      Seq(n)
    } else {
      var lo = 1
      var hi = n
      while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (fits(mid)) lo = mid else hi = mid
      }
      lo +: kernelSizes(n - lo, options)
    }
  }

  /** The loop methods of an emitted kernel: its groups. */
  private def loops(kernel: VarkaFusedKernel): Int =
    kernel.getClass.getDeclaredMethods.count(_.getName.startsWith("loopDense"))

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val arena = Arena.ofConfined()
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      // Days from 1970 to about 2030, and a bitmap with every seventh row null.
      val data = arena.allocate(numRows * 4L, 8)
      val validity = arena.allocate((numRows + 7) / 8L, 8)
      var nulls = 0
      for (i <- 0 until numRows) {
        data.setAtIndex(ValueLayout.JAVA_INT, i, (i * 37) % 22000)
        if (i % 7 == 0) {
          nulls += 1
        } else {
          val off = i / 8L
          validity.set(ValueLayout.JAVA_BYTE, off,
            (validity.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))).toByte)
        }
      }
      val batchNulls = (0 until numRows by chunk).map { start =>
        (start until math.min(start + chunk, numRows)).count(_ % 7 == 0)
      }.toArray
      val widest = 400
      val past = Seq(800, 1200)
      val dsts = Array.fill(past.max)(arena.allocate(chunk * 4L, 8))
      val dstValidities = Array.fill(past.max)(arena.allocate(chunk / 8L, 8))

      /** Every 4096-row batch of the column through `kernel`, null-free or with nulls. */
      def scan(kernel: VarkaFusedKernel, outputs: Int, withNulls: Boolean): Unit = {
        val lits = Array.tabulate(outputs)(k => k % 12 + 1)
        val dstData = dsts.take(outputs).map(_.address())
        val dstValidity = dstValidities.take(outputs).map(_.address())
        var done = 0
        var batch = 0
        while (done < numRows) {
          val n = math.min(chunk, numRows - done)
          val status = kernel.run(Array(data.address() + done * 4L),
            Array(if (withNulls) validity.address() + done / 8L else 0L),
            Array(if (withNulls) batchNulls(batch) else 0), dstData, dstValidity, lits, n)
          require(status == 0, s"the kernel declined a batch: status $status")
          done += n
          batch += 1
        }
      }

      runBenchmark("the driver from a table against the driver unrolled") {
        val benchmark = new Benchmark(s"$numRows rows in $chunk-row batches", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        // Both forms named: the table is the default since this section's measurement.
        val unrolled = VarkaEmitOptions.DEFAULTS.withDriverOutputTable(false)
        val table = VarkaEmitOptions.DEFAULTS.withDriverOutputTable(true)
        val hundred = (0 until 100).map(entry)
        val forms = Seq("unrolled driver" -> emit(hundred, unrolled, loader),
          "driver from a table" -> emit(hundred, table, loader))
        for (withNulls <- Seq(false, true); (label, kernel) <- forms) {
          val nullsLabel = if (withNulls) "every seventh row null" else "null-free"
          benchmark.addCase(s"100 entries, $label, $nullsLabel") { _ =>
            scan(kernel, 100, withNulls)
          }
        }
        val wide = emit((0 until widest).map(entry), table, loader)
        for (withNulls <- Seq(false, true)) {
          val nullsLabel = if (withNulls) "every seventh row null" else "null-free"
          benchmark.addCase(s"$widest entries, driver from a table, $nullsLabel") { _ =>
            scan(wide, widest, withNulls)
          }
        }
        benchmark.run()
      }

      runBenchmark("the exact grouping against the greedy walk") {
        val benchmark = new Benchmark(s"$numRows rows in $chunk-row batches", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val shapes = Seq(100, 200).map(n => (s"$n mixed entries", (0 until n).map(mixedEntry))) :+
          (s"$widest ladder entries", (0 until widest).map(entry))
        // Each kernel is emitted once and scanned with nulls and without.
        val kernels = for ((shape, roots) <- shapes; (label, options) <- groupings)
          yield (shape, roots, label, emit(roots, options, loader))
        for (withNulls <- Seq(false, true); (shape, roots, label, kernel) <- kernels) {
          val nullsLabel = if (withNulls) "every seventh row null" else "null-free"
          benchmark.addCase(s"$shape, $label (${loops(kernel)} loop methods), $nullsLabel") { _ =>
            scan(kernel, roots.size, withNulls)
          }
        }
        benchmark.run()
      }

      runBenchmark("the locals nothing reads, built and elided") {
        val benchmark = new Benchmark(s"$numRows rows in $chunk-row batches", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        // Both forms named: the elision is off by default until this section's measurement.
        val built = VarkaEmitOptions.DEFAULTS.withElideUnreadLocals(false)
        val elided = VarkaEmitOptions.DEFAULTS.withElideUnreadLocals(true)
        val shapes = Seq(100, widest).map(n => (s"$n ladder entries", (0 until n).map(entry))) ++
          Seq(100, 200).map(n => (s"$n mixed entries", (0 until n).map(mixedEntry)))
        // The built form runs again last, a control for the order of the cases.
        val forms = Seq("locals built" -> built, "locals elided" -> elided,
          "locals built, again" -> built)
        for (withNulls <- Seq(false, true); (shape, roots) <- shapes) {
          val nullsLabel = if (withNulls) "every seventh row null" else "null-free"
          for ((label, options) <- forms) {
            val kernel = emit(roots, options, loader)
            benchmark.addCase(s"$shape, $label, $nullsLabel") { _ =>
              scan(kernel, roots.size, withNulls)
            }
          }
        }
        benchmark.run()
      }

      runBenchmark("emitting the mixed family: the exact grouping's cost at plan time") {
        // One emission per iteration, the class built and measured but not defined.
        val benchmark = new Benchmark("one emission", 1,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val roots = java.util.List.of((0 until 200).map(mixedEntry): _*)
        var sink = 0
        for ((label, options) <- groupings) {
          benchmark.addCase(s"200 mixed entries, $label") { _ =>
            sink += VarkaLoopEmitter.emit("VarkaWideBenchEmission", roots, 1, 200, null, null,
              options).length
          }
        }
        benchmark.run()
        require(sink != Int.MinValue)
      }

      // The two forms past the driver from a table's ceiling. Stages only where the driver is
      // over the budget, and several kernels only where one kernel cannot serve them all.
      // Both named: the split driver is the default since `PLAN_TASK_190.md` 11.5, and each of
      // B's kernels is a kernel whose driver fits, which is what the compiler's split makes.
      val splitDriver = VarkaEmitOptions.DEFAULTS.withSplitDriver(true)
      val oneKernel = VarkaEmitOptions.DEFAULTS.withSplitDriver(false)
      val forms = past.map { n =>
        val sizes = kernelSizes(n, oneKernel)
        require(sizes.size > 1, s"$n ladder entries fit one kernel: nothing to compare")
        val staged = emit(ladder(n), splitDriver, loader)
        val stages = staged.getClass.getDeclaredMethods.count(_.getName.startsWith("stageDense"))
        (n, sizes, staged, stages, sizes.map(k => emit(ladder(k), oneKernel, loader)))
      }

      runBenchmark("past the driver's ceiling: a split driver against several kernels") {
        val benchmark = new Benchmark(s"$numRows rows in $chunk-row batches", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        for (withNulls <- Seq(false, true); (n, sizes, staged, stages, kernels) <- forms) {
          val nullsLabel = if (withNulls) "every seventh row null" else "null-free"
          benchmark.addCase(s"$n entries, split driver (one kernel, $stages stages), " +
              s"$nullsLabel") { _ =>
            scan(staged, n, withNulls)
          }
          benchmark.addCase(s"$n entries, several kernels (${sizes.mkString(" + ")}), " +
              s"$nullsLabel") { _ =>
            kernels.zip(sizes).foreach { case (kernel, k) => scan(kernel, k, withNulls) }
          }
        }
        benchmark.run()
      }

      runBenchmark("past the driver's ceiling: each form's cost at plan time") {
        // One emission per iteration of every class the form needs, built and measured but not
        // defined; the label carries the classes' bytes.
        val benchmark = new Benchmark("one emission", 1,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        var sink = 0
        def build(roots: Seq[VarkaVectorIR], options: VarkaEmitOptions): Array[Byte] =
          VarkaLoopEmitter.emit("VarkaWideBenchEmission", java.util.List.of(roots: _*), 1,
            roots.size, null, null, options)
        for ((n, sizes, _, _, _) <- forms) {
          val stagedBytes = build(ladder(n), splitDriver).length
          val severalBytes = sizes.map(k => build(ladder(k), oneKernel).length).sum
          benchmark.addCase(s"$n entries, split driver ($stagedBytes bytes)") { _ =>
            sink += build(ladder(n), splitDriver).length
          }
          benchmark.addCase(s"$n entries, several kernels ($severalBytes bytes in " +
              s"${sizes.size} classes)") { _ =>
            sizes.foreach(k => sink += build(ladder(k), oneKernel).length)
          }
          // The compiler finds the split by asking the emitter for halving prefixes, which the
          // shape cache then holds; this is what a projection's first plan pays for it.
          benchmark.addCase(s"$n entries, several kernels with the compiler's search") { _ =>
            sink += kernelSizes(n, oneKernel).size
            sizes.foreach(k => sink += build(ladder(k), oneKernel).length)
          }
        }
        benchmark.run()
        require(sink != Int.MinValue)
      }
    } finally {
      arena.close()
    }
  }
}
