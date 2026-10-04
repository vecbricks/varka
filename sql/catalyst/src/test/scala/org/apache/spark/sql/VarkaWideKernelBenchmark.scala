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
 * Kernels wider than one driver method could once hold (VARKA-190 step 2): what a projection of
 * hundreds of outputs costs per row when it runs as one fused kernel, and what each way of
 * getting there past the driver's ceiling costs against the others.
 *
 * The first section prices the table-driven driver (`VarkaEmitOptions.driverOutputTable`)
 * against the unrolled one on the widest projection both can emit, a hundred entries of the
 * size ladder's `greatest(add_months(d, k), date_add(d, k), last_day(d))`, and then runs the
 * table form alone at four hundred, which the unrolled driver cannot emit. The driver's
 * per-output work runs once per batch, so the batches are the 4096 rows a columnar scan hands
 * a kernel, over a million-row date column, null-free and with every seventh row null - the
 * dense body and the masked body with its bitmap pass. See `VARKA-190.md` 9.2 and 10.
 *
 * The second section prices the exact grouping (`VarkaEmitOptions.exactGrouping`) against the
 * greedy walk on the mixed family, where the greedy walk leaves every `date_add` in a loop method
 * of its own and the exact partition does not, at a hundred and two hundred entries, with the
 * four-hundred-entry ladder as the control, whose grouping the switch leaves as it is. The third
 * times one emission of the mixed family under each, for the partition's cost at plan time. See
 * `VARKA-200.md` 4.
 *
 * A section prices the locals a loop or epilogue method stores and never reads
 * (`VarkaEmitOptions.elideUnreadLocals`): the size ladder at a hundred and four hundred entries
 * and the mixed family at a hundred and two hundred, with them built, elided, and built again
 * last as a control for the order of the cases. See `VARKA-239.md` 6.
 *
 * Two sections go past the driver from a table's own ceiling, about 180 groups: eight hundred
 * and twelve hundred ladder entries, two and three hundred groups. One kernel whose driver calls
 * its groups through stages (`VarkaEmitOptions.splitDriver`, A') is priced against several
 * kernels run in turn over each batch (`VarkaEmitOptions.severalKernels`, B), split as the
 * compiler splits them - the largest prefix one kernel serves, then the rest - each reading the
 * input again and recomputing the prefix it shares. First one emission of each, with the bytes
 * of the classes, each form as the plan builds it (`VarkaEmitOptions.planSize`, the default)
 * and as the size loop did; then the two forms at run time, on the ladder and on sixty-four
 * dates listed by field, with A' first and again last. See `VARKA-190.md` 11 and
 * `VARKA-236.md` 6 and 9.3.
 *
 * The last section is item 71's (`m8/SCOPE.md`): the planned grouping, the prediction
 * closing groups with the fit's margins, against the weights' and against the prediction
 * without the margins, on the cheap tails, the mixed family and the ladder.
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

  /** `year(d) + k`, the cheap tail the call-site budget was read on. */
  private def tailEntry(k: Int): VarkaVectorIR =
    new IntArith(IntOp.ADD, Overflow.WRAP, new Year(new ColumnRef(0)), new LiteralSlot(k))

  private var nextId = 0

  private def emit(roots: Seq[VarkaVectorIR], options: VarkaEmitOptions,
      loader: VarkaGeneratedClassLoader, numInputs: Int = 1): VarkaFusedKernel = {
    nextId += 1
    val name = s"org.apache.spark.sql.varka.execution.VarkaWideBench$nextId"
    loader.defineGeneratedClass(name, VarkaLoopEmitter.emit(name,
      java.util.List.of(roots: _*), numInputs, roots.size, null, null, options))
    loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
  }

  /**
   * Sixty-four dates, four fields of each, listed by field: every `year`, then every `month`,
   * `quarter` and `dayofmonth`. The shape of `VARKA-236.md` 2.4, where several kernels' cut
   * in projection order parts the fields of a date, so the second kernel decomposes every date
   * again where the one class computes each prefix once and loads it (VARKA-198).
   */
  private[sql] val datesByField: Seq[VarkaVectorIR] = {
    val cols = (0 until 64).map(new ColumnRef(_))
    cols.map(new Year(_)) ++ cols.map(new Month(_)) ++ cols.map(new Quarter(_)) ++
      cols.map(new DayOfMonth(_))
  }

  /** `n` ladder entries numbered from literal slot 0: one kernel's roots. */
  private[sql] def ladder(n: Int): Seq[VarkaVectorIR] = (0 until n).map(entry)

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

  /**
   * How the compiler splits `roots` under `severalKernels` with `planSize` on: the emitter
   * declines a shape whose driver is over the budget before building it, naming the largest
   * prefix one class serves (`VarkaEmitDeclined.plannedCut`), and the rest is asked again. The
   * kernels' roots: `rest` gives the roots after a cut, since the ladder's entries are numbered
   * from each kernel's own literal slot 0 as `kernelSizes` numbers them, where the dates by field
   * have no literals and are sliced as they are.
   */
  private[sql] def plannedKernels(roots: Seq[VarkaVectorIR], numInputs: Int,
      options: VarkaEmitOptions,
      rest: (Seq[VarkaVectorIR], Int) => Seq[VarkaVectorIR]): Seq[Seq[VarkaVectorIR]] = {
    if (roots.isEmpty) {
      return Nil
    }
    try {
      VarkaLoopEmitter.emit("VarkaWideBenchPlan", java.util.List.of(roots: _*), numInputs,
        roots.size, null, null, options.withPlanSize(true))
      Seq(roots)
    } catch {
      case d: VarkaEmitDeclined if d.plannedCut > 0 =>
        roots.take(d.plannedCut) +: plannedKernels(rest(roots, d.plannedCut), numInputs, options,
          rest)
    }
  }

  /** The ladder's roots after a cut: the rest, numbered from literal slot 0 again. */
  private[sql] def restOfLadder(roots: Seq[VarkaVectorIR], cut: Int): Seq[VarkaVectorIR] =
    ladder(roots.size - cut)

  /** The dates by field after a cut: the rest as it is, since the fields have no literals. */
  private[sql] def restOfFields(roots: Seq[VarkaVectorIR], cut: Int): Seq[VarkaVectorIR] =
    roots.drop(cut)

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

      /**
       * Every 4096-row batch of the column through `kernel`, null-free or with nulls; a kernel
       * over several inputs reads the one column through each of them.
       */
      def scan(kernel: VarkaFusedKernel, outputs: Int, withNulls: Boolean,
          inputs: Int = 1): Unit = {
        val lits = Array.tabulate(outputs)(k => k % 12 + 1)
        val dstData = dsts.take(outputs).map(_.address())
        val dstValidity = dstValidities.take(outputs).map(_.address())
        var done = 0
        var batch = 0
        while (done < numRows) {
          val n = math.min(chunk, numRows - done)
          val status = kernel.run(Array.fill(inputs)(data.address() + done * 4L),
            Array.fill(inputs)(if (withNulls) validity.address() + done / 8L else 0L),
            Array.fill(inputs)(if (withNulls) batchNulls(batch) else 0), dstData, dstValidity,
            lits, n)
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
        // Both forms named: the elision is the default since this section's measurement.
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
      // Both named: the split driver is the default since `VARKA-190.md` 11.5, and each of
      // B's kernels is a kernel whose driver fits, which is what the compiler's split makes.
      // Both plan their size since VARKA-236 made the plan the default; the loop that reacted
      // to each measurement in turn is the reference arm here, named `loop`, and its classes
      // are the planned ones byte for byte (`VARKA-236.md` 9.2), so only the plan-time
      // section still runs it.
      val splitDriver = VarkaEmitOptions.DEFAULTS.withSplitDriver(true)
      val oneKernel = VarkaEmitOptions.DEFAULTS.withSplitDriver(false)
      val loopSplit = splitDriver.withPlanSize(false)
      val loopOne = oneKernel.withPlanSize(false)
      val forms = past.map { n =>
        val sizes = kernelSizes(n, loopOne)
        require(sizes.size > 1, s"$n ladder entries fit one kernel: nothing to compare")
        (n, sizes)
      }

      runBenchmark("past the driver's ceiling: each form's cost at plan time") {
        // One emission per iteration of every class the form needs, built and measured but not
        // defined; the label carries the classes' bytes. The planned forms are the default: the
        // split driver sizes its stages off a driver built alone, one build; several kernels'
        // cut is read off the same, one emission that builds nothing and then one build a
        // kernel. The loop's arms are the reference: the split driver's two builds, several
        // kernels' classes alone with their split given, and the same with the compiler's
        // search for it, which asks the emitter for halving prefixes the shape cache then holds.
        val benchmark = new Benchmark("one emission", 1,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        var sink = 0
        def build(roots: Seq[VarkaVectorIR], options: VarkaEmitOptions): Array[Byte] =
          VarkaLoopEmitter.emit("VarkaWideBenchEmission", java.util.List.of(roots: _*), 1,
            roots.size, null, null, options)
        for ((n, sizes) <- forms) {
          val stagedBytes = build(ladder(n), loopSplit).length
          val severalBytes = sizes.map(k => build(ladder(k), loopOne).length).sum
          benchmark.addCase(s"$n entries, split driver") { _ =>
            sink += build(ladder(n), splitDriver).length
          }
          benchmark.addCase(s"$n entries, several kernels") { _ =>
            plannedKernels(ladder(n), 1, oneKernel, restOfLadder)
              .foreach(k => sink += build(k, oneKernel).length)
          }
          benchmark.addCase(s"$n entries, split driver, the loop's two builds " +
              s"($stagedBytes bytes)") { _ =>
            sink += build(ladder(n), loopSplit).length
          }
          benchmark.addCase(s"$n entries, several kernels, the loop's classes alone " +
              s"($severalBytes bytes in ${sizes.size} classes)") { _ =>
            sizes.foreach(k => sink += build(ladder(k), loopOne).length)
          }
          benchmark.addCase(s"$n entries, several kernels with the loop's compiler search") { _ =>
            sink += kernelSizes(n, loopOne).size
            sizes.foreach(k => sink += build(ladder(k), loopOne).length)
          }
        }
        benchmark.run()
        require(sink != Int.MinValue)
      }

      // VARKA-190's question (VARKA-236.md 3.6): the split driver (A') against several
      // kernels (B) on the ladder, B's best case, and on sixty-four dates by field, its worst,
      // where its cut parts the fields of a date. A' runs first and again last, a control for
      // the case order 11.6 did not control. Both forms plan their size, as they do by default.
      runBenchmark("past the driver's ceiling: a split driver against several kernels, " +
          "the split driver first and again last") {
        val benchmark = new Benchmark(s"$numRows rows in $chunk-row batches", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val shapes = past.map(n => (s"$n ladder entries", ladder(n), 1, restOfLadder _)) :+
          ("sixty-four dates by field, 256 outputs", datesByField, 64, restOfFields _)
        val forms = shapes.map { case (label, roots, inputs, rest) =>
          val planned = plannedKernels(roots, inputs, oneKernel, rest)
          val sizes = planned.map(_.size)
          require(sizes.size > 1, s"$label fits one kernel: nothing to compare")
          val staged = emit(roots, splitDriver, loader, inputs)
          val kernels = planned.map(emit(_, oneKernel, loader, inputs))
          (label, roots.size, inputs, sizes, staged, kernels)
        }
        for (withNulls <- Seq(false, true); (label, n, inputs, sizes, staged, kernels) <- forms) {
          val nullsLabel = if (withNulls) "every seventh row null" else "null-free"
          benchmark.addCase(s"$label, split driver, $nullsLabel") { _ =>
            scan(staged, n, withNulls, inputs)
          }
          benchmark.addCase(s"$label, several kernels (${sizes.mkString(" + ")}), " +
              s"$nullsLabel") { _ =>
            kernels.zip(sizes).foreach { case (kernel, k) => scan(kernel, k, withNulls, inputs) }
          }
          benchmark.addCase(s"$label, split driver, again, $nullsLabel") { _ =>
            scan(staged, n, withNulls, inputs)
          }
        }
        benchmark.run()
      }

      // Item 71 of m8/SCOPE.md, brought into VARKA-236 (VARKA-236.md 3.5): what the
      // predicted grouping costs at run time against the weights', and the planned grouping
      // with the fit's margins, on the families the prediction closes groups on. The weights
      // run first and again last as the control.
      runBenchmark("the predicted grouping against the weights, and planned with the margins") {
        val benchmark = new Benchmark(s"$numRows rows in $chunk-row batches", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        // Planned is the default since VARKA-236; the weights alone and the prediction without
        // the plan's margins are its reference arms.
        val planned = VarkaEmitOptions.DEFAULTS
        val predicted = planned.withPlanSize(false)
        val weights = predicted.withPredictGrouping(false)
        val shapes = Seq(22, 64).map(n => (s"$n cheap tails", (0 until n).map(tailEntry))) ++
          Seq(("200 mixed entries", (0 until 200).map(mixedEntry)),
            (s"$widest ladder entries", ladder(widest)))
        val forms = Seq("weights" -> weights, "predicted" -> predicted, "planned" -> planned,
          "weights, again" -> weights)
        for (withNulls <- Seq(false, true); (shape, roots) <- shapes) {
          val nullsLabel = if (withNulls) "every seventh row null" else "null-free"
          for ((label, options) <- forms) {
            val kernel = emit(roots, options, loader)
            benchmark.addCase(s"$shape, $label (${loops(kernel)} loop methods), $nullsLabel") {
              _ => scan(kernel, roots.size, withNulls)
            }
          }
        }
        benchmark.run()
      }
    } finally {
      arena.close()
    }
  }
}
