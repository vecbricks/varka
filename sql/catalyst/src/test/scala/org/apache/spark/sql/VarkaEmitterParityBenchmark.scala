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
import java.nio.charset.StandardCharsets
import java.util.Locale

import scala.concurrent.duration._
import scala.util.control.NonFatal

import org.apache.arrow.vector.VarCharVector

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.DateTimeExpressionUtils
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{TruncLevelLeaf, VarkaEmitOptions, VarkaFusedKernel, VarkaLoopEmitter, VarkaVectorIR, WeekdayLeaf}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.varka.vector.{ChronoScalarOps, ChronoVectorOps, DateVectorOps}
import org.apache.spark.sql.vectorized.ArrowColumnVector

/**
 * The emitter's gates as a benchmark (see `sql/varka/plans/PLAN_TASK_9.md`,
 * `PLAN_TASK_10.md` and `PLAN_TASK_11.md`).
 *
 * The parity gates: an emitted single-op loop must reach the hand-written kernel within noise
 * (acceptance: at least 0.9x its best-time throughput) - anything worse means C2 did not
 * intrinsify the emitted Vector API calls and the emitter is wrong. Task 9 measured `date_add`;
 * task 10 adds the two-input `datediff`. The chain cases are the fusion gate and the data
 * behind `MAX_CHAIN_DEPTH`: a fused depth-N chain against N sequential kernel passes over the
 * same buffers. Task 10 adds the DAG cases: a subchain shared by two outputs with CSE on, with
 * the memo disabled (pricing CSE itself), and as sequential kernel passes; and the widest shape
 * the emitter accepts (`MAX_FUSED_NODES` ops), which must scale with its op count rather than
 * fall off a cliff at the cap. Task 11 adds the predication cases - a CASE WHEN blend against
 * the same-depth plain arithmetic - and the `dayofweek` mod-7 comparison, whose shipped
 * lowering is the two-fold magic multiply since the task 14 follow-up, priced against the
 * task 11 digit sum, the lanewise-DIV reference and the per-row `LocalDate` path.
 *
 * Task 24 adds the batch-length alignment ladder, which is the only case here that exercises
 * the loop's remainder handling at all: every other case runs one call over the whole
 * lane-aligned buffer, so `loopBound == length` and the tail has never processed a row under
 * measurement. It drives the same total row count through aligned and unaligned chunks, at two
 * chunk sizes, so the tail's marginal cost and the per-call prologue can be read separately.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *          "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 *      Results will be written to "benchmarks/VarkaEmitterParityBenchmark-results.txt".
 *   3. the four-lane shape (numbers recorded in the task plans):
 *        build/sbt "project catalyst" 'set Test/javaOptions += "-XX:MaxVectorSize=16"'
 *          "Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
 * }}}
 */
object VarkaEmitterParityBenchmark extends BenchmarkBase {

  private val numRows = 1_000_000

  private def chain(depth: Int, slotBase: Int = 0): VarkaVectorIR = {
    var node: VarkaVectorIR = new ColumnRef(0)
    for (level <- 0 until depth) {
      node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
      else new SubDays(node, new LiteralSlot(slotBase + level))
    }
    node
  }

  /** Every case id handed to [[emit]], so a reused one is named here rather than deep in a run. */
  private val usedIds = scala.collection.mutable.Set.empty[Int]

  private def emit(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int,
      loader: VarkaGeneratedClassLoader, n: Int,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): VarkaFusedKernel = {
    // A duplicate id is otherwise a LinkageError from the class loader, twenty minutes into a
    // regeneration and pointing at the loader rather than at the two cases that chose the same
    // number. Not every id in this file is a literal - the trunc block computes `id` and
    // `id + 1` from a tuple list - so a grep is not a reliable way to pick a free one.
    require(usedIds.add(n), s"case id $n is already in use by another emit in this benchmark")
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedBench$n"
    val javaRoots = new java.util.ArrayList[VarkaVectorIR]()
    roots.foreach(javaRoots.add)
    loader.defineGeneratedClass(name,
      VarkaLoopEmitter.emit(name, javaRoots, numInputs, numLiterals, null, null, options))
    loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
  }

  /**
   * Closes every non-null vector in `vs`, guarding each so one failure cannot strand the rest.
   * `failing`, when non-null, is an exception already on its way out: a close failure is
   * attached to it rather than replacing it, since that one is the reason the run is ending.
   */
  private def closeNames(vs: Array[VarCharVector], failing: Throwable): Unit = {
    if (vs != null) {
      var i = 0
      while (i < vs.length) {
        val v = vs(i)
        vs(i) = null
        if (v != null) {
          try {
            v.close()
          } catch {
            case NonFatal(e) =>
              if (failing != null) {
                failing.addSuppressed(e)
              } else {
                // scalastyle:off println
                println(s"closing a benchmark name vector failed: $e")
                // scalastyle:on println
              }
          }
        }
        i += 1
      }
    }
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
      val (nf2Data, _, _) = fill(arena, _ => false)
      val (mx2Data, mx2Validity, mx2Nulls) = fill(arena, i => i % 11 == 0)
      val dst = arena.allocate(numRows * 4L, 8)
      val dstValidity = arena.allocate((numRows + 7) / 8L, 8)
      val dst2 = arena.allocate(numRows * 4L, 8)
      val dst2Validity = arena.allocate((numRows + 7) / 8L, 8)
      // Sequential-pass temporaries, one pair per intermediate of the deepest chain, and the
      // per-output destinations of the widest shape.
      val maxDepth = 16
      val tmpData = Array.fill(maxDepth)(arena.allocate(numRows * 4L, 8))
      val tmpValidity = Array.fill(maxDepth)(arena.allocate((numRows + 7) / 8L, 8))
      val wideDst = Array.fill(4)(arena.allocate(numRows * 4L, 8))
      val wideDstValidity = Array.fill(4)(arena.allocate((numRows + 7) / 8L, 8))

      /** The chain as `depth` hand-written kernel passes, `src` to `dst` through the temps. */
      def sequentialChain(depth: Int, offsets: Array[Int], srcData: Long, srcValidity: Long,
          srcNulls: Int, dstData: Long, dstVal: Long): Unit = {
        var srcD = srcData
        var srcV = srcValidity
        for (level <- 0 until depth) {
          val (outD, outV) = if (level == depth - 1) (dstData, dstVal)
          else (tmpData(level).address(), tmpValidity(level).address())
          if (level % 2 == 0) {
            DateVectorOps.vectorAddDays(srcD, srcV, srcNulls, outD, outV, numRows,
              offsets(level))
          } else {
            DateVectorOps.vectorSubDays(srcD, srcV, srcNulls, outD, outV, numRows,
              offsets(level))
          }
          srcD = outD
          srcV = outV
        }
      }

      runBenchmark("single op: emitted loop vs hand-written kernel") {
        val benchmark = new Benchmark(s"date_add over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val depth1 = emit(Seq(chain(1)), 1, 1, loader, 0)
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

      runBenchmark("datediff: emitted loop vs hand-written kernel") {
        val benchmark = new Benchmark(s"datediff over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val diff = emit(
          Seq(new DateDiff(new ColumnRef(0), new ColumnRef(1))), 2, 0, loader, 100)
        benchmark.addCase("hand-written kernel, null-free") { _ =>
          DateVectorOps.vectorDateDiff(nfData.address(), 0L, 0, nf2Data.address(), 0L, 0,
            dst.address(), dstValidity.address(), numRows)
        }
        benchmark.addCase("emitted loop, null-free") { _ =>
          diff.run(Array(nfData.address(), nf2Data.address()), Array(0L, 0L), Array(0, 0),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("hand-written kernel, mixed nulls") { _ =>
          DateVectorOps.vectorDateDiff(mxData.address(), mxValidity.address(), mxNulls,
            mx2Data.address(), mx2Validity.address(), mx2Nulls,
            dst.address(), dstValidity.address(), numRows)
        }
        benchmark.addCase("emitted loop, mixed nulls") { _ =>
          diff.run(Array(mxData.address(), mx2Data.address()),
            Array(mxValidity.address(), mx2Validity.address()), Array(mxNulls, mx2Nulls),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.run()
      }

      runBenchmark("fused chain vs sequential kernel passes") {
        val benchmark = new Benchmark(s"chain over $numRows rows, mixed nulls", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        for (depth <- Seq(1, 2, 4, 8, 16)) {
          val offsets = (0 until depth).map(level => level * 13 + 1).toArray
          val fused = emit(Seq(chain(depth)), 1, depth, loader, depth)
          benchmark.addCase(s"fused, depth $depth") { _ =>
            fused.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
              Array(dst.address()), Array(dstValidity.address()), offsets, numRows)
          }
          benchmark.addCase(s"sequential kernels, depth $depth") { _ =>
            sequentialChain(depth, offsets, mxData.address(), mxValidity.address(), mxNulls,
              dst.address(), dstValidity.address())
          }
        }
        benchmark.run()
      }

      runBenchmark("shared subchain across two outputs (DAG-CSE)") {
        // a = chain8(d); b = datediff(chain8(d), d2): 9 distinct ops. With the memo disabled
        // the same trees emit 17 op calls - the delta prices CSE itself, separately from
        // fusion. The sequential oracle is 9 kernel passes.
        val benchmark = new Benchmark(s"two outputs over $numRows rows, mixed nulls", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val shared = chain(8)
        val roots = Seq[VarkaVectorIR](shared, new DateDiff(shared, new ColumnRef(1)))
        val offsets = (0 until 8).map(level => level * 13 + 1).toArray
        val fused = emit(roots, 2, 8, loader, 200)
        val fusedNoCse =
          emit(roots, 2, 8, loader, 201, VarkaEmitOptions.DEFAULTS.withCse(false))
        def runFused(kernel: VarkaFusedKernel): Unit = {
          kernel.run(Array(mxData.address(), mx2Data.address()),
            Array(mxValidity.address(), mx2Validity.address()), Array(mxNulls, mx2Nulls),
            Array(dst.address(), dst2.address()),
            Array(dstValidity.address(), dst2Validity.address()), offsets, numRows)
        }
        benchmark.addCase("fused, CSE") { _ => runFused(fused) }
        benchmark.addCase("fused, memo disabled") { _ => runFused(fusedNoCse) }
        benchmark.addCase("sequential kernels (9 passes)") { _ =>
          sequentialChain(8, offsets, mxData.address(), mxValidity.address(), mxNulls,
            dst.address(), dstValidity.address())
          DateVectorOps.vectorDateDiff(dst.address(), dstValidity.address(), mxNulls,
            mx2Data.address(), mx2Validity.address(), mx2Nulls,
            dst2.address(), dst2Validity.address(), numRows)
        }
        benchmark.run()
      }

      runBenchmark("predication: CASE WHEN blend vs plain arithmetic (task 11)") {
        // The same depth-4 arithmetic with and without a comparison + blend wrapped around
        // it - pricing predication itself, in both bodies. The arms are disjoint chains over
        // each input so the predicated case does strictly more work.
        val benchmark = new Benchmark(s"depth-4 arms over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        def chainOver(base: VarkaVectorIR, depth: Int, slotBase: Int): VarkaVectorIR = {
          var node = base
          for (level <- 0 until depth) {
            node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
            else new SubDays(node, new LiteralSlot(slotBase + level))
          }
          node
        }
        val offsets = (0 until 8).map(level => level * 13 + 1).toArray
        val plain = emit(Seq(chainOver(new ColumnRef(0), 4, 0)), 2, 8, loader, 400)
        val blended = emit(Seq(new IfElse(
          new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1)),
          chainOver(new ColumnRef(0), 4, 0),
          chainOver(new ColumnRef(1), 4, 4))), 2, 8, loader, 401)
        def run(kernel: VarkaFusedKernel, d1: Long, v1: Long, n1: Int, d2v: Long, v2: Long,
            n2: Int): Unit = {
          kernel.run(Array(d1, d2v), Array(v1, v2), Array(n1, n2),
            Array(dst.address()), Array(dstValidity.address()), offsets, numRows)
        }
        benchmark.addCase("arithmetic depth 4, null-free") { _ =>
          run(plain, nfData.address(), 0L, 0, nf2Data.address(), 0L, 0)
        }
        benchmark.addCase("CASE WHEN, depth-4 arms, null-free") { _ =>
          run(blended, nfData.address(), 0L, 0, nf2Data.address(), 0L, 0)
        }
        benchmark.addCase("arithmetic depth 4, mixed nulls") { _ =>
          run(plain, mxData.address(), mxValidity.address(), mxNulls,
            mx2Data.address(), mx2Validity.address(), mx2Nulls)
        }
        benchmark.addCase("CASE WHEN, depth-4 arms, mixed nulls") { _ =>
          run(blended, mxData.address(), mxValidity.address(), mxNulls,
            mx2Data.address(), mx2Validity.address(), mx2Nulls)
        }
        benchmark.run()
      }

      runBenchmark("dayofweek: magic multiply vs digit sum vs DIV vs LocalDate (tasks 11, 14)") {
        val benchmark = new Benchmark(s"dayofweek over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val dow = emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 500)
        val dowDiv = emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 501,
          VarkaEmitOptions.DEFAULTS.withFloorMod7(VarkaEmitOptions.FloorMod7.DIV))
        val dowDigitSum = emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 502,
          VarkaEmitOptions.DEFAULTS.withFloorMod7(VarkaEmitOptions.FloorMod7.DIGIT_SUM))
        // Task 57: extract(DAYOFWEEK_ISO), dayofweek's tail with the other offset and the same
        // add; priced beside the shipped dayofweek row, which it should match within noise.
        val dowIso = emit(Seq(new DayOfWeekIso(new ColumnRef(0))), 1, 0, loader, 507)
        benchmark.addCase("dayofweek_iso (task 57), null-free") { _ =>
          dowIso.run(Array(nfData.address()), Array(0L), Array(0),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("magic multiply (shipped), null-free") { _ =>
          dow.run(Array(nfData.address()), Array(0L), Array(0),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("magic multiply (shipped), mixed nulls") { _ =>
          dow.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("digit sum (task 11 reference), null-free") { _ =>
          dowDigitSum.run(Array(nfData.address()), Array(0L), Array(0),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("digit sum (task 11 reference), mixed nulls") { _ =>
          dowDigitSum.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("lanewise DIV, null-free") { _ =>
          dowDiv.run(Array(nfData.address()), Array(0L), Array(0),
            Array(dst.address()), Array(dstValidity.address()), Array.empty[Int], numRows)
        }
        benchmark.addCase("per-row LocalDate (the path Spark uses today)") { _ =>
          var i = 0
          while (i < numRows) {
            val days = nfData.get(ValueLayout.JAVA_INT, i * 4L)
            dst.set(ValueLayout.JAVA_INT, i * 4L,
              java.time.LocalDate.ofEpochDay(days).getDayOfWeek.plus(1).getValue)
            i += 1
          }
        }
        benchmark.run()
      }

      runBenchmark("year: the calendar extractions against LocalDate (task 26)") {
        // The emitted civil-from-days decomposition against the scalar path Spark runs today.
        // A second lowering, which split the dividend to cover the whole int day range without
        // a guard, was measured here before being dropped for costing 14-24%; see
        // PLAN_TASK_26.md section 11.2 for that comparison.
        //
        // Driven in 4096-row chunks - Spark's COLUMN_BATCH_SIZE - rather than one
        // million-row call, so the per-call prologue is paid at the rate production pays it,
        // and walking the buffer rather than a warm prefix so the kernel and the scalar
        // anchor below are measured in the same memory regime. The four-field case is here
        // because task 26 gave
        // calendar nodes a GROUP_BUDGET weight so they cannot share a loop method; this is
        // where that is measured rather than assumed.
        val repeats = 20
        val chunk = 4096
        val benchmark = new Benchmark(s"${numRows.toLong * repeats} rows in 4096-row chunks",
          numRows.toLong * repeats,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        def nullsIn(n: Int): Int = (n + 6) / 7
        // Four outputs need four destinations; one shared buffer would have the kernels
        // overwrite each other and alias four stores onto one cache line. wideDst/wideDstValidity
        // are the same four buffers the "widest shape" section below uses - task 32's own cases
        // reuse them rather than allocating a second set, since nothing in either section holds
        // a result across sections.
        val dstData4 = wideDst.map(_.address())
        val dstValidity4 = wideDstValidity.map(_.address())
        // Each chunk advances the source and destination addresses, so a pass walks the whole
        // buffer rather than re-reading a cache-warm prefix 245 times. That matters here and
        // not in the task-24 ladder below: this section's scalar anchor walks all one million
        // rows, so a cache-resident kernel measured against a streaming scalar loop would put
        // the two sides of the headline ratio in different regimes. Measured: the same
        // dayofweek kernel reads 7746.1 M rows/s over the whole buffer against 8058.8 over a
        // warm prefix, so the regime is worth about 4% here - small, but it is the difference
        // between a measured ratio and a nearly-measured one.
        // The chunk walk itself, shared by every case in this section: `repeats` passes over the
        // whole buffer in 4096-row chunks, handing the body each chunk's source data offset,
        // validity offset and row count. Written once because two cases measured against each
        // other must not differ in their addressing - task 32's first pass hand-copied this loop
        // for its own case, which is exactly the drift this prevents.
        def eachChunk(body: (Long, Long, Int) => Unit): Unit = {
          var pass = 0
          while (pass < repeats) {
            var done = 0
            while (done < numRows) {
              val n = math.min(chunk, numRows - done)
              body(done * 4L, done / 8L, n)
              done += n
            }
            pass += 1
          }
        }
        def chunked(kernel: VarkaFusedKernel, mixed: Boolean, outputs: Int = 1): Unit =
          eachChunk { (dataOff, validityOff, n) =>
            val dstData = if (outputs == 1) Array(dst.address() + dataOff)
              else dstData4.take(outputs).map(_ + dataOff)
            val dstValid = if (outputs == 1) Array(dstValidity.address() + validityOff)
              else dstValidity4.take(outputs).map(_ + validityOff)
            // A declined batch does the same vector work and reports the same time, so a
            // discarded status would let this file commit a rate production never sees -
            // it pays the kernel and then the whole row path. Same reason checkMatrix
            // asserts it.
            val status = if (mixed) {
              kernel.run(Array(mxData.address() + dataOff),
                Array(mxValidity.address() + validityOff),
                Array(nullsIn(n)), dstData, dstValid, Array.empty[Int], n)
            } else {
              kernel.run(Array(nfData.address() + dataOff), Array(0L), Array(0),
                dstData, dstValid, Array.empty[Int], n)
            }
            require(status == 0, s"the kernel declined a batch: status $status")
          }
        val year = emit(Seq(new Year(new ColumnRef(0))), 1, 0, loader, 801)
        val fourFields = Seq[VarkaVectorIR](new Year(new ColumnRef(0)),
          new Month(new ColumnRef(0)), new DayOfMonth(new ColumnRef(0)),
          new Quarter(new ColumnRef(0)))
        val four = emit(fourFields, 1, 0, loader, 803)
        val dow = emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 804)
        // Task 45's A/B, on shapes that already exist rather than new ones: the point is what
        // the dense validity fill does to kernels that ship today. Each pair is adjacent, so
        // both sides run back to back under one JIT and thermal state, and the mixed-null rows
        // are the control - the masked bodies are asserted byte for byte identical under the
        // option, so movement there is run noise rather than an effect.
        val perGroup = VarkaEmitOptions.DEFAULTS.withDenseValidityOnce(false)
        val yearPerGroup = emit(Seq(new Year(new ColumnRef(0))), 1, 0, loader, 830, perGroup)
        val dowPerGroup = emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 832, perGroup)
        // Task 48's A/B. The shipped year kernel skips the prefix's March-month step - four
        // lane ops a year tail never reads, since it takes the January turn off the day of
        // year instead (PLAN_TASK_48.md section 2) - and this is the same kernel with the
        // step put back. The two are adjacent, and the pairs interleaved by null pattern,
        // because that is the interleaving: one regeneration runs both sides back to back
        // under the same JIT and thermal state, and the file is compared by minimums across
        // regenerations rather than row against row. Four ops on a 43-op body is under half a
        // millisecond at this section's 11 ms best time, so "inside noise" is a legitimate
        // outcome here and the default does not rest on this number - it rests on the step
        // being provably dead work where it is elided.
        val yearMonthKept = emit(Seq(new Year(new ColumnRef(0))), 1, 0, loader, 802,
          VarkaEmitOptions.DEFAULTS.withElideChronoMonth(false))
        // add_months is the widest calendar node the emitter has - it decomposes and
        // recomposes in one loop method - and until now it had no committed number at all,
        // which is why the leap flag's cost was argued rather than measured. One scalar arg,
        // the month count, so this cannot share `chunked`.
        // Task 53's A/B, built the same way and for the same reason: adjacent cases, both
        // null patterns, so the two axes are measured back to back under one JIT and thermal
        // state. `dayofmonth` is the one with the largest op-count win (-4 of 43, the tail
        // stops running emitMonthStart forwards entirely) and `month` the smallest (-2 of 40),
        // so the pair brackets what the numerator can be worth; the four-field shape is here
        // because it pays the block once and the tails three times, which is where a win
        // should compound if it is real. `year` is deliberately absent: it reads neither axis,
        // and PLAN_TASK_53.md 6.1 prediction 3 is that it does not move - a case that cannot
        // move is a case that only adds runtime to this section.
        val monthNeri = emit(Seq(new Month(new ColumnRef(0))), 1, 0, loader, 820)
        val monthOld = emit(Seq(new Month(new ColumnRef(0))), 1, 0, loader, 821,
          VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(false))
        val domNeri = emit(Seq(new DayOfMonth(new ColumnRef(0))), 1, 0, loader, 822)
        val domOld = emit(Seq(new DayOfMonth(new ColumnRef(0))), 1, 0, loader, 823,
          VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(false))
        val fourOld = emit(fourFields, 1, 0, loader, 824,
          VarkaEmitOptions.DEFAULTS.withNeriSchneiderMonth(false))
        val addMonths = emit(Seq(new AddMonths(new ColumnRef(0), new LiteralSlot(0))),
          1, 1, loader, 811)
        def chunkedAddMonths(kernel: VarkaFusedKernel, mixed: Boolean): Unit =
          eachChunk { (dataOff, validityOff, n) =>
            val status = if (mixed) {
              kernel.run(Array(mxData.address() + dataOff),
                Array(mxValidity.address() + validityOff), Array(nullsIn(n)),
                Array(dst.address() + dataOff),
                Array(dstValidity.address() + validityOff), Array(13), n)
            } else {
              kernel.run(Array(nfData.address() + dataOff), Array(0L), Array(0),
                Array(dst.address() + dataOff),
                Array(dstValidity.address() + validityOff), Array(13), n)
            }
            require(status == 0, s"the kernel declined a batch: status $status")
          }
        // Task 54's A/B, both prefix forms named explicitly rather than one of them as "the
        // default", so the labels survive the default changing. `year` is the shape that pays
        // the prefix and nothing else, so it brackets the top of what the map is worth (five
        // ops off a body of about forty, one carry stage off the dependent chain); the
        // four-field shared shape pays the prefix once and the tails thrice, so it brackets the
        // bottom; `add_months` is the widest node and the one whose year assembly runs inside a
        // recomposition. Adjacent cases, both null patterns, the same interleaving discipline
        // as tasks 45, 48 and 53.
        val julian = VarkaEmitOptions.DEFAULTS.withJulianMap(true)
        val centuryYear = VarkaEmitOptions.DEFAULTS.withJulianMap(false)
        val yearJulian = emit(Seq(new Year(new ColumnRef(0))), 1, 0, loader, 840, julian)
        val yearCenturyYear =
          emit(Seq(new Year(new ColumnRef(0))), 1, 0, loader, 841, centuryYear)
        val fourJulian = emit(fourFields, 1, 0, loader, 842, julian)
        val fourCenturyYear = emit(fourFields, 1, 0, loader, 843, centuryYear)
        val addMonthsJulian = emit(Seq(new AddMonths(new ColumnRef(0), new LiteralSlot(0))),
          1, 1, loader, 844, julian)
        val addMonthsCenturyYear = emit(
          Seq(new AddMonths(new ColumnRef(0), new LiteralSlot(0))), 1, 1, loader, 845,
          centuryYear)
        // Task 46's A/B: the same kernels emitted against the general validity helpers - the
        // pair that takes the lane count as an argument and carries a four-arm switch on it -
        // rather than the sibling named for the emitted width. Measured through task 45's
        // A/B, one refused `orValidityBitsAt` costs 1.87 to 3.24 ns per lane group at either
        // width, so the shapes here are the ones that still make the call: a masked
        // projection, the shared four-field masked shape with four writes and one read per
        // group, and the selection kernel below, which makes it in both bodies. The
        // null-free-with-per-group-OR pair isolates the write with no masked machinery around
        // it, and is directly comparable to the task 45 row beside it.
        val generalHelpers = VarkaEmitOptions.DEFAULTS.withValidityByWidth(false)
        val yearGeneral = emit(Seq(new Year(new ColumnRef(0))), 1, 0, loader, 880, generalHelpers)
        val yearPerGroupGeneral = emit(Seq(new Year(new ColumnRef(0))), 1, 0, loader, 881,
          generalHelpers.withDenseValidityOnce(false))
        val dowGeneral = emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 882,
          generalHelpers)
        val fourSharedGeneral = emit(fourFields, 1, 0, loader, 883,
          generalHelpers.withGroupBudget(200))
        // The selection kernel: a Cond root's slot holds a selection bitmap, which is computed
        // rather than known, so task 45's driver fill cannot serve it and the per-group OR
        // stays in the dense body too. It is the shape the columnar filter runs on every batch
        // and the one shape with no committed parity case before task 46.
        // The second half of task 46: the same kernels with the validity OR emitted after the
        // store, which is where it was until the compiled loop showed it as a real call in every
        // arm. Both sides carry the width-named helpers, so this pair prices the order alone.
        val orAfter = VarkaEmitOptions.DEFAULTS.withValidityOrFirst(false)
        val yearOrAfter = emit(Seq(new Year(new ColumnRef(0))), 1, 0, loader, 887, orAfter)
        val fourSharedOrAfter = emit(fourFields, 1, 0, loader, 888, orAfter.withGroupBudget(200))
        val selectionRoot = new Compare(CompareOp.LT, new ColumnRef(0), new LiteralSlot(0))
        val filterKernel = emit(Seq(selectionRoot), 1, 1, loader, 884)
        val filterGeneral = emit(Seq(selectionRoot), 1, 1, loader, 885, generalHelpers)
        // A Cond root writes no data at all, so its data address is 0L by the interface
        // contract - the dst slot must not be materialized - and the day it compares against
        // rides the scalar-args array. Zero selects about half of `fill`'s values, which run
        // from -10000 to 9999, so the bitmap carries mixed bits rather than a constant.
        def chunkedFilter(kernel: VarkaFusedKernel, mixed: Boolean): Unit =
          eachChunk { (dataOff, validityOff, n) =>
            val status = if (mixed) {
              kernel.run(Array(mxData.address() + dataOff),
                Array(mxValidity.address() + validityOff), Array(nullsIn(n)),
                Array(0L), Array(dstValidity.address() + validityOff), Array(0), n)
            } else {
              kernel.run(Array(nfData.address() + dataOff), Array(0L), Array(0),
                Array(0L), Array(dstValidity.address() + validityOff), Array(0), n)
            }
            require(status == 0, s"the kernel declined a batch: status $status")
          }
        benchmark.addCase("year, null-free") { _ => chunked(year, false) }
        benchmark.addCase("year, validity OR-ed per group (task 45 A/B), null-free") { _ =>
          chunked(yearPerGroup, false)
        }
        benchmark.addCase(
          "year, validity OR-ed per group, general helpers (task 46 A/B), null-free") { _ =>
          chunked(yearPerGroupGeneral, false)
        }
        benchmark.addCase("year, validity OR-ed per group (task 45 A/B), mixed nulls") { _ =>
          chunked(yearPerGroup, true)
        }
        benchmark.addCase("year, month step kept (task 48 A/B), null-free") { _ =>
          chunked(yearMonthKept, false)
        }
        benchmark.addCase("year, mixed nulls") { _ => chunked(year, true) }
        benchmark.addCase("year, general validity helpers (task 46 A/B), mixed nulls") { _ =>
          chunked(yearGeneral, true)
        }
        benchmark.addCase("year, validity OR after the store (task 46 A/B), mixed nulls") { _ =>
          chunked(yearOrAfter, true)
        }
        benchmark.addCase("dayofweek, mixed nulls") { _ => chunked(dow, true) }
        benchmark.addCase("dayofweek, general validity helpers (task 46 A/B), mixed nulls") { _ =>
          chunked(dowGeneral, true)
        }
        benchmark.addCase("filter d < literal, null-free") { _ =>
          chunkedFilter(filterKernel, false)
        }
        benchmark.addCase(
          "filter d < literal, general validity helpers (task 46 A/B), null-free") { _ =>
          chunkedFilter(filterGeneral, false)
        }
        benchmark.addCase("filter d < literal, mixed nulls") { _ =>
          chunkedFilter(filterKernel, true)
        }
        benchmark.addCase(
          "filter d < literal, general validity helpers (task 46 A/B), mixed nulls") { _ =>
          chunkedFilter(filterGeneral, true)
        }
        benchmark.addCase("year, month step kept (task 48 A/B), mixed nulls") { _ =>
          chunked(yearMonthKept, true)
        }
        benchmark.addCase("add_months(d, 13), null-free") { _ =>
          chunkedAddMonths(addMonths, false)
        }
        benchmark.addCase("add_months(d, 13), mixed nulls") { _ =>
          chunkedAddMonths(addMonths, true)
        }
        benchmark.addCase("year, Julian map (task 54 A/B), null-free") { _ =>
          chunked(yearJulian, false)
        }
        benchmark.addCase("year, century-then-year (task 54 A/B), null-free") { _ =>
          chunked(yearCenturyYear, false)
        }
        benchmark.addCase("year, Julian map (task 54 A/B), mixed nulls") { _ =>
          chunked(yearJulian, true)
        }
        benchmark.addCase("year, century-then-year (task 54 A/B), mixed nulls") { _ =>
          chunked(yearCenturyYear, true)
        }
        benchmark.addCase("year+month+day+quarter, Julian map (task 54 A/B), null-free") { _ =>
          chunked(fourJulian, false, outputs = 4)
        }
        benchmark.addCase("year+month+day+quarter, century-then-year (task 54 A/B), null-free") {
          _ => chunked(fourCenturyYear, false, outputs = 4)
        }
        benchmark.addCase("add_months(d, 13), Julian map (task 54 A/B), null-free") { _ =>
          chunkedAddMonths(addMonthsJulian, false)
        }
        benchmark.addCase("add_months(d, 13), century-then-year (task 54 A/B), null-free") { _ =>
          chunkedAddMonths(addMonthsCenturyYear, false)
        }
        benchmark.addCase("month, Neri-Schneider (task 53 A/B), null-free") { _ =>
          chunked(monthNeri, false)
        }
        benchmark.addCase("month, 0-based axis (task 53 A/B), null-free") { _ =>
          chunked(monthOld, false)
        }
        benchmark.addCase("month, Neri-Schneider (task 53 A/B), mixed nulls") { _ =>
          chunked(monthNeri, true)
        }
        benchmark.addCase("month, 0-based axis (task 53 A/B), mixed nulls") { _ =>
          chunked(monthOld, true)
        }
        benchmark.addCase("dayofmonth, Neri-Schneider (task 53 A/B), null-free") { _ =>
          chunked(domNeri, false)
        }
        benchmark.addCase("dayofmonth, 0-based axis (task 53 A/B), null-free") { _ =>
          chunked(domOld, false)
        }
        benchmark.addCase("dayofmonth, Neri-Schneider (task 53 A/B), mixed nulls") { _ =>
          chunked(domNeri, true)
        }
        benchmark.addCase("dayofmonth, 0-based axis (task 53 A/B), mixed nulls") { _ =>
          chunked(domOld, true)
        }
        benchmark.addCase("year+month+day+quarter, 0-based axis (task 53 A/B), null-free") { _ =>
          chunked(fourOld, false, outputs = 4)
        }
        benchmark.addCase("year+month+day+quarter, null-free") { _ =>
          chunked(four, false, outputs = 4)
        }
        // Task 32 step B2's gate (PLAN_TASK_32.md section 7.2): does the emitted fragment,
        // fused into one loop method by a widened groupBudget, actually reach the throughput
        // the hand-written ceiling below promises - and at how few fields does it start to pay.
        // "Separate" is today's shape: each field its own loop method at the shipped
        // GROUP_BUDGET (16), which is far under a calendar node's CHRONO_WEIGHT (50) so no
        // budget short of a deliberate widening ever fuses them. "Shared" widens the budget to
        // 200 - comfortably past four fields' 200 ops (50 each) - so groupOutputs puts every
        // field in one method, where shareChronoPrefix (on by default since step B1) then runs
        // the decomposition once. Neither the option nor the grouping affects results, only
        // which bytes compute them; VarkaLoopEmitterSuite pins that both ways.
        val wideBudget = VarkaEmitOptions.DEFAULTS.withGroupBudget(200)
        val col0 = new ColumnRef(0)
        val yearMonth = Seq[VarkaVectorIR](new Year(col0), new Month(col0))
        val yearMonthDay = yearMonth :+ new DayOfMonth(col0)
        val yearMonthSeparate = emit(yearMonth, 1, 0, loader, 805)
        val yearMonthShared = emit(yearMonth, 1, 0, loader, 806, wideBudget)
        val yearMonthDaySeparate = emit(yearMonthDay, 1, 0, loader, 807)
        val yearMonthDayShared = emit(yearMonthDay, 1, 0, loader, 808, wideBudget)
        val fourShared = emit(fourFields, 1, 0, loader, 809, wideBudget)
        val fourSharedPerGroup = emit(fourFields, 1, 0, loader, 831,
          wideBudget.withDenseValidityOnce(false))
        benchmark.addCase("year+month, separate (2 loop methods), null-free") { _ =>
          chunked(yearMonthSeparate, false, outputs = 2)
        }
        benchmark.addCase("year+month, shared (1 loop method), null-free") { _ =>
          chunked(yearMonthShared, false, outputs = 2)
        }
        benchmark.addCase("year+month+day, separate (3 loop methods), null-free") { _ =>
          chunked(yearMonthDaySeparate, false, outputs = 3)
        }
        benchmark.addCase("year+month+day, shared (1 loop method), null-free") { _ =>
          chunked(yearMonthDayShared, false, outputs = 3)
        }
        benchmark.addCase("dayofweek, validity OR-ed per group (task 45 A/B), null-free") {
          _ => chunked(dowPerGroup, false)
        }
        benchmark.addCase(
          "year+month+day+quarter, shared, validity OR-ed per group (task 45 A/B), null-free") {
          _ => chunked(fourSharedPerGroup, false, outputs = 4)
        }
        benchmark.addCase(
          "year+month+day+quarter, shared, validity OR-ed per group (task 45 A/B), mixed nulls") {
          _ => chunked(fourSharedPerGroup, true, outputs = 4)
        }
        benchmark.addCase("year+month+day+quarter, shared (1 loop method), null-free") { _ =>
          chunked(fourShared, false, outputs = 4)
        }
        benchmark.addCase("year+month+day+quarter, shared (1 loop method), mixed nulls") { _ =>
          chunked(fourShared, true, outputs = 4)
        }
        benchmark.addCase(
          "year+month+day+quarter, shared, general helpers (task 46 A/B), mixed nulls") { _ =>
          chunked(fourSharedGeneral, true, outputs = 4)
        }
        benchmark.addCase(
          "year+month+day+quarter, shared, OR after the store (task 46 A/B), mixed nulls") { _ =>
          chunked(fourSharedOrAfter, true, outputs = 4)
        }
        // The regression guard section 5.2 asks for: two chrono nodes over different dates
        // must not be pushed into one method by the widened budget clause, since there is
        // nothing between them to share. VarkaLoopEmitterSuite's correctness test is the one
        // that would catch a wrong merge; this prices what a right non-merge costs against the
        // single-date case above.
        val twoDates = emit(Seq(new Year(col0), new Year(new ColumnRef(1))), 2, 0, loader, 810,
          wideBudget)
        benchmark.addCase("year(d1), year(d2), two dates, shared option, null-free") { _ =>
          eachChunk { (dataOff, validityOff, n) =>
            val status = twoDates.run(
              Array(nfData.address() + dataOff, nf2Data.address() + dataOff),
              Array(0L, 0L), Array(0, 0),
              Array(dst.address() + dataOff, dst2.address() + dataOff),
              Array(dstValidity.address() + validityOff, dst2Validity.address() + validityOff),
              Array.empty[Int], n)
            require(status == 0, s"the kernel declined a batch: status $status")
          }
        }
        // Task 52's A/B: the range guard, moved from every calendar extraction (task 26,
        // removed by task 51) to the one producer the compiler cannot bound - a date_add whose
        // offset is a column - and only under a calendar node. `year(date_add(d, off))` is the
        // one shape that pays it, so that is what is priced, guard on against guard off,
        // adjacent and on both null patterns like the task 48 and 53 pairs; `date_add(d, off)`
        // alone is the control, byte-identical under both settings (VarkaLoopEmitterSuite
        // asserts it) and measured here so a difference in that row is run noise, not the
        // guard. The second date buffer stands in for the offset column: its values are days in
        // [-10000, 10000), so every sum stays in range and the status must read zero.
        val guardOff = VarkaEmitOptions.DEFAULTS.withGuardDayProducers(false)
        val offsetAdd = new AddDays(col0, new ColumnRef(1))
        val yearOfAddGuarded = emit(Seq(new Year(offsetAdd)), 2, 0, loader, 850)
        val yearOfAddUnguarded = emit(Seq(new Year(offsetAdd)), 2, 0, loader, 851, guardOff)
        val addAloneGuardOn = emit(Seq(offsetAdd), 2, 0, loader, 852)
        val addAloneGuardOff = emit(Seq(offsetAdd), 2, 0, loader, 853, guardOff)
        def nulls2In(n: Int): Int = (n + 10) / 11
        // `lits` is empty for every two-column shape here; task 60's literal-count control is
        // the one case that needs a slot, and it runs on this same runner so that the control
        // and the shape it controls for differ in the kernel alone.
        def chunkedTwo(kernel: VarkaFusedKernel, mixed: Boolean,
            lits: Array[Int] = Array.empty[Int]): Unit =
          eachChunk { (dataOff, validityOff, n) =>
            val status = if (mixed) {
              kernel.run(
                Array(mxData.address() + dataOff, mx2Data.address() + dataOff),
                Array(mxValidity.address() + validityOff, mx2Validity.address() + validityOff),
                Array(nullsIn(n), nulls2In(n)),
                Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                lits, n)
            } else {
              kernel.run(
                Array(nfData.address() + dataOff, nf2Data.address() + dataOff),
                Array(0L, 0L), Array(0, 0),
                Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                lits, n)
            }
            require(status == 0, s"the kernel declined a batch: status $status")
          }
        benchmark.addCase("year(date_add(d, off)), producer guard on (task 52 A/B), null-free") {
          _ => chunkedTwo(yearOfAddGuarded, false)
        }
        benchmark.addCase("year(date_add(d, off)), producer guard off (task 52 A/B), null-free") {
          _ => chunkedTwo(yearOfAddUnguarded, false)
        }
        benchmark.addCase("year(date_add(d, off)), producer guard on (task 52 A/B), mixed nulls") {
          _ => chunkedTwo(yearOfAddGuarded, true)
        }
        benchmark.addCase("year(date_add(d, off)), producer guard off (task 52 A/B), mixed nulls") {
          _ => chunkedTwo(yearOfAddUnguarded, true)
        }
        benchmark.addCase("date_add(d, off) alone, guard option on (task 52 control), null-free") {
          _ => chunkedTwo(addAloneGuardOn, false)
        }
        benchmark.addCase("date_add(d, off) alone, guard option off (task 52 control), null-free") {
          _ => chunkedTwo(addAloneGuardOff, false)
        }
        // Task 60's pair, the same guard block on a heavier producer: add_months' own month
        // count, widened from a literal to a column, with a runtime guard against
        // MONTH_ARITH_MIN/MAX_MONTHS in place of task 40's compile-time bound. nf2Data/mx2Data
        // double as the count column - their values are days in [-10000, 10000), comfortably
        // inside the guard's range - so the status must read zero and this prices the shape
        // alone, not a decline.
        //
        // The control is the literal count on this same runner, emitted adjacent and with the
        // same numInputs, rather than id 811 far above: that one runs chunkedAddMonths and sits
        // dozens of cases away, so its delta carried a different runner and a different JIT and
        // thermal state as well as the change being measured. What the delta here contains is
        // the guard plus one column load in place of a broadcast - the control is passed both
        // stream addresses but its IR reads only col0, so the second stream's traffic is still
        // on the column side of the comparison and the difference is not the guard alone.
        //
        // There is deliberately no guard-off variant. The count check is self-guarding and
        // unconditional (VarkaEmitOptions.guardDayProducers does not reach it), because the
        // compiler's dayRange bounds a column count on the strength of it firing and cannot see
        // the option; an A/B against it would emit identical bytes. Isolating the guard's own
        // cost would take a measurement-only option, which is not worth reintroducing the
        // coupling this task's review removed - see PLAN_TASK_60.md 9.
        val addMonthsCol = new AddMonths(col0, new ColumnRef(1))
        val addMonthsLit = new AddMonths(col0, new LiteralSlot(0))
        val addMonthsColGuarded = emit(Seq(addMonthsCol), 2, 0, loader, 854)
        val addMonthsLitControl = emit(Seq(addMonthsLit), 2, 1, loader, 855)
        benchmark.addCase("add_months(d, m), column count (task 60), null-free") { _ =>
          chunkedTwo(addMonthsColGuarded, false)
        }
        benchmark.addCase("add_months(d, 13), literal count (task 60 control), null-free") { _ =>
          chunkedTwo(addMonthsLitControl, false, Array(13))
        }
        benchmark.addCase("add_months(d, m), column count (task 60), mixed nulls") { _ =>
          chunkedTwo(addMonthsColGuarded, true)
        }
        benchmark.addCase("add_months(d, 13), literal count (task 60 control), mixed nulls") {
          _ => chunkedTwo(addMonthsLitControl, true, Array(13))
        }
        // Task 35's A/B: trunc(date, ...) under its two lowerings, SUBTRACT (the day of year
        // or day of month taken off the date) against RECOMPOSE (the period's first day rebuilt
        // through emitDaysFromCivil), adjacent per level like the task 48, 53 and 54 pairs so
        // both sides run under one JIT and thermal state. MONTH follows the switch too, though
        // only its subtract form is expected to ship: it is the day-of-month tail with one op
        // changed, and it prices the recomposition on the shape where it has the least to hide
        // behind. The per-row DateTimeUtils.truncDate loop is what Spark runs today.
        val recompose = VarkaEmitOptions.DEFAULTS
          .withTruncDate(VarkaEmitOptions.TruncDateForm.RECOMPOSE)
        val truncCases = Seq(
          ("YEAR", TruncLevel.YEAR, 860), ("MONTH", TruncLevel.MONTH, 862),
          ("QUARTER", TruncLevel.QUARTER, 864)).map { case (name, level, id) =>
          (name,
            emit(Seq(new TruncDate(col0, level)), 1, 0, loader, id),
            emit(Seq(new TruncDate(col0, level)), 1, 0, loader, id + 1, recompose))
        }
        for ((name, subtract, recomposed) <- truncCases) {
          benchmark.addCase(s"trunc $name, subtract (task 35 A/B), null-free") { _ =>
            chunked(subtract, false)
          }
          benchmark.addCase(s"trunc $name, recompose (task 35 A/B), null-free") { _ =>
            chunked(recomposed, false)
          }
        }
        for ((name, subtract, recomposed) <- truncCases if name != "MONTH") {
          benchmark.addCase(s"trunc $name, subtract (task 35 A/B), mixed nulls") { _ =>
            chunked(subtract, true)
          }
          benchmark.addCase(s"trunc $name, recompose (task 35 A/B), mixed nulls") { _ =>
            chunked(recomposed, true)
          }
        }
        // Task 42: make_date over three int columns - the year, month and day of nfData's own
        // dates, so every triple is valid and in range - the NULL and ANSI forms as an adjacent
        // A/B (they differ by a word store and the mask the guard receives), a mixed-null run
        // with mxData's validity on the year, and the per-row path Spark uses today.
        val yData = arena.allocate(numRows * 4L, 8)
        val mData = arena.allocate(numRows * 4L, 8)
        val dData = arena.allocate(numRows * 4L, 8)
        locally {
          var i = 0
          while (i < numRows) {
            val date = java.time.LocalDate.ofEpochDay(nfData.get(ValueLayout.JAVA_INT, i * 4L))
            yData.set(ValueLayout.JAVA_INT, i * 4L, date.getYear)
            mData.set(ValueLayout.JAVA_INT, i * 4L, date.getMonthValue)
            dData.set(ValueLayout.JAVA_INT, i * 4L, date.getDayOfMonth)
            i += 1
          }
        }
        val col1 = new ColumnRef(1)
        val col2 = new ColumnRef(2)
        val makeDateNull = emit(Seq(new MakeDate(col0, col1, col2, false)), 3, 0, loader, 880)
        val makeDateAnsi = emit(Seq(new MakeDate(col0, col1, col2, true)), 3, 0, loader, 881)
        def chunkedThree(kernel: VarkaFusedKernel, mixed: Boolean): Unit =
          eachChunk { (dataOff, validityOff, n) =>
            val status = if (mixed) {
              kernel.run(
                Array(yData.address() + dataOff, mData.address() + dataOff,
                  dData.address() + dataOff),
                Array(mxValidity.address() + validityOff, 0L, 0L),
                Array(nullsIn(n), 0, 0),
                Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                Array.empty[Int], n)
            } else {
              kernel.run(
                Array(yData.address() + dataOff, mData.address() + dataOff,
                  dData.address() + dataOff),
                Array(0L, 0L, 0L), Array(0, 0, 0),
                Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                Array.empty[Int], n)
            }
            require(status == 0, s"the kernel declined a batch: status $status")
          }
        benchmark.addCase("make_date, NULL form (task 42 A/B), null-free") { _ =>
          chunkedThree(makeDateNull, false)
        }
        benchmark.addCase("make_date, ANSI form (task 42 A/B), null-free") { _ =>
          chunkedThree(makeDateAnsi, false)
        }
        benchmark.addCase("make_date, NULL form (task 42 A/B), mixed nulls") { _ =>
          chunkedThree(makeDateNull, true)
        }
        benchmark.addCase("make_date, ANSI form (task 42 A/B), mixed nulls") { _ =>
          chunkedThree(makeDateAnsi, true)
        }
        benchmark.addCase("per-row LocalDate.of make_date (the path Spark uses today)") { _ =>
          var pass = 0
          while (pass < repeats) {
            var i = 0
            while (i < numRows) {
              val date = java.time.LocalDate.of(yData.get(ValueLayout.JAVA_INT, i * 4L),
                mData.get(ValueLayout.JAVA_INT, i * 4L), dData.get(ValueLayout.JAVA_INT, i * 4L))
              dst.set(ValueLayout.JAVA_INT, i * 4L, DateTimeUtils.localDateToDays(date))
              i += 1
            }
            pass += 1
          }
        }
        // Task 37: weekofyear as the compiler builds it, the week tail over the Thursday shift,
        // beside the dayofyear rows above as the sibling control (the tail is dayofyear's plus
        // four ops, the shift another seventeen), the shift alone, and the per-row path.
        val weekOfYear = emit(Seq(new WeekOfYear(new ThursdayOf(col0))), 1, 0, loader, 870)
        val thursdayOf = emit(Seq(new ThursdayOf(col0)), 1, 0, loader, 872)
        // Task 58: yearofweek is Year over the same shift; the pair is the sharing row, one
        // ThursdayOf and one prefix for both fields under CSE.
        val yearOfWeek = emit(Seq(new Year(new ThursdayOf(col0))), 1, 0, loader, 873)
        val isoPair = emit(Seq(new WeekOfYear(new ThursdayOf(col0)),
          new Year(new ThursdayOf(col0))), 1, 0, loader, 874)
        benchmark.addCase("weekofyear (task 37), null-free") { _ => chunked(weekOfYear, false) }
        benchmark.addCase("yearofweek (task 58), null-free") { _ => chunked(yearOfWeek, false) }
        benchmark.addCase("weekofyear + yearofweek, one shift (task 58), null-free") { _ =>
          chunked(isoPair, false, 2)
        }
        benchmark.addCase("weekofyear (task 37), null-free") { _ => chunked(weekOfYear, false) }
        benchmark.addCase("weekofyear (task 37), mixed nulls") { _ => chunked(weekOfYear, true) }
        benchmark.addCase("ThursdayOf alone (task 37), null-free") { _ =>
          chunked(thursdayOf, false)
        }
        benchmark.addCase("per-row DateTimeUtils.getWeekOfYear (the path Spark uses today)") {
          _ =>
            var pass = 0
            while (pass < repeats) {
              var i = 0
              while (i < numRows) {
                val days = nfData.get(ValueLayout.JAVA_INT, i * 4L)
                dst.set(ValueLayout.JAVA_INT, i * 4L, DateTimeUtils.getWeekOfYear(days))
                i += 1
              }
              pass += 1
            }
        }
        benchmark.addCase("per-row DateTimeUtils.truncDate YEAR (the path Spark uses today)") {
          _ =>
            var pass = 0
            while (pass < repeats) {
              var i = 0
              while (i < numRows) {
                val days = nfData.get(ValueLayout.JAVA_INT, i * 4L)
                dst.set(ValueLayout.JAVA_INT, i * 4L,
                  DateTimeUtils.truncDate(days, DateTimeUtils.TRUNC_TO_YEAR))
                i += 1
              }
              pass += 1
            }
        }
        // Task 32's ceiling (PLAN_TASK_32.md): the same four fields from one shared
        // decomposition, computed by hand outside the emitter (ChronoVectorOps), against the four
        // independently emitted nodes above. It runs through the same eachChunk walk, over the
        // same buffers, writing the same four data and four validity destinations, and pays the
        // same narrow-range guard - once, where the four-node case pays it four times, which is
        // the saving being measured rather than an omission. The first version of this case
        // omitted the guard, wrote one shared validity buffer and hand-copied the chunk loop;
        // the number it produced is why task 32 was first declined.
        benchmark.addCase("year+month+day+quarter, shared decomposition (hand-written ceiling)") {
          _ =>
            eachChunk { (dataOff, validityOff, n) =>
              val status = ChronoVectorOps.vectorFourFields(
                nfData.address() + dataOff, 0L, 0,
                dstData4.map(_ + dataOff), dstValidity4.map(_ + validityOff), n)
              require(status == 0, s"the ceiling kernel declined a batch: status $status")
            }
        }
        // The same arithmetic and the same op count, scheduled to keep fewer values live: the
        // year assembly hoisted so era/century/yoc die early, and each output stored as soon as
        // it exists rather than all four at the end (which is also what emitLaneGroup does). The
        // pair prices the schedule alone, which is what decides whether the 128-bit width can
        // reach the win the native width gets - see PLAN_TASK_32.md section 7.
        benchmark.addCase("year+month+day+quarter, shared decomposition (short live ranges)") {
          _ =>
            eachChunk { (dataOff, validityOff, n) =>
              val status = ChronoVectorOps.vectorFourFieldsShortLive(
                nfData.address() + dataOff, 0L, 0,
                dstData4.map(_ + dataOff), dstValidity4.map(_ + validityOff), n)
              require(status == 0, s"the short-live kernel declined a batch: status $status")
            }
        }
        // Section 2.17's bound: the same kernel, the same guard, the same op count, with every
        // destination validity buffer and every orValidityBitsAt/orPartialValidityBitsAt call
        // removed. The gap between this and the ceiling above is what the validity write costs,
        // separated from the arithmetic without touching a single line the two kernels share -
        // ChronoVectorOpsTest checks that byte for byte.
        benchmark.addCase("year+month+day+quarter, shared decomposition (no validity write)") {
          _ =>
            eachChunk { (dataOff, validityOff, n) =>
              val status = ChronoVectorOps.vectorFourFieldsNoValidity(
                nfData.address() + dataOff, dstData4.map(_ + dataOff), n)
              require(status == 0, s"the no-validity kernel declined a batch: status $status")
            }
        }
        // The scalar baseline this file never had. The LocalDate anchor below is not one: it
        // allocates a LocalDate per row, so it prices allocation rather than arithmetic.
        // ChronoScalarOps is the same civil-from-days as an ordinary Java loop over the same
        // buffer, in the same chunks, writing the same outputs - so the gap between these and
        // the emitted "year, null-free" case above is what the Vector API is actually worth
        // here. The two spellings differ only in how each division is written, and that is the
        // second question: `/` becomes a high-half multiply, which has no vector node and so
        // can never auto-vectorize, while `(x * M) >>> k` is a plain 64-bit multiply and shift,
        // which SuperWord may vectorize on its own. Running the magic case again under
        // -XX:-UseSuperWord says whether it did.
        def scalarChunked(kernel: (Long, Long, Long, Int) => Unit): Unit = {
          var pass = 0
          while (pass < repeats) {
            var done = 0
            while (done < numRows) {
              val n = math.min(chunk, numRows - done)
              kernel(nfData.address() + done * 4L, dst.address() + done * 4L,
                dstValidity.address() + done / 8L, n)
              done += n
            }
            pass += 1
          }
        }
        benchmark.addCase("scalar year, division (cannot auto-vectorize), null-free") { _ =>
          scalarChunked(ChronoScalarOps.yearByDivision)
        }
        benchmark.addCase("scalar year, magic multiply (may auto-vectorize), null-free") { _ =>
          scalarChunked(ChronoScalarOps.yearByMagic)
        }
        // The 2x2 that says *why* SuperWord declined the case above: array against
        // MemorySegment, trivial body against the full decomposition. Re-run the section under
        // -XX:-UseSuperWord; a case SuperWord vectorized slows down, one it never touched does
        // not move. See ChronoScalarOps's probe block. These are diagnostics, not kernels, and
        // they are here rather than in a scratch program so the answer stays reproducible.
        val probeSrc = new Array[Int](chunk)
        val probeDst = new Array[Int](chunk)
        var pi = 0
        while (pi < chunk) {
          probeSrc(pi) = nfData.get(ValueLayout.JAVA_INT, pi * 4L)
          pi += 1
        }
        val probePasses = repeats * (numRows / chunk)
        benchmark.addCase("probe: trivial body, int[] (control)") { _ =>
          var p = 0
          while (p < probePasses) {
            ChronoScalarOps.probeArrayTrivial(probeSrc, probeDst, chunk)
            p += 1
          }
        }
        benchmark.addCase("probe: trivial body, MemorySegment") { _ =>
          var p = 0
          while (p < probePasses) {
            ChronoScalarOps.probeSegmentTrivial(nfData.address(), dst.address(), chunk)
            p += 1
          }
        }
        benchmark.addCase("probe: full year body, int[]") { _ =>
          var p = 0
          while (p < probePasses) {
            ChronoScalarOps.probeArrayYear(probeSrc, probeDst, chunk)
            p += 1
          }
        }
        // The in-harness anchors: dayofweek is the cheapest emitted date node (a 20-op vector
        // body against year's ~50), and the per-row LocalDate loop is what Spark runs today.
        benchmark.addCase("dayofweek, for scale, null-free") { _ => chunked(dow, false) }
        benchmark.addCase("per-row LocalDate year (the path Spark uses today)") { _ =>
          var pass = 0
          while (pass < repeats) {
            var i = 0
            while (i < numRows) {
              val days = nfData.get(ValueLayout.JAVA_INT, i * 4L)
              dst.set(ValueLayout.JAVA_INT, i * 4L, java.time.LocalDate.ofEpochDay(days).getYear)
              i += 1
            }
            pass += 1
          }
        }
        benchmark.run()
      }

      runBenchmark("GROUP_BUDGET: two outputs over one shared chain, split vs kept together") {
        // The register's open retuning candidate (task 17). A shared depth-8 chain with six
        // more ops on each of two outputs is 20 distinct ops, straddling the shipped budget of
        // 16: at 16 the outputs land in two loop methods and the second recomputes the eight
        // shared ops per lane group, at 24 they share one method and keep their cross-output
        // CSE. The question is whether the bigger method's C2 compile stays cheap enough for
        // that to pay (PLAN_TASK_14.md 7.5 measured compile time at ~1 ms per vector op).
        val benchmark = new Benchmark(
          s"two outputs over a shared chain, $numRows rows, mixed nulls", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        def chainOver(base: VarkaVectorIR, depth: Int, slotBase: Int): VarkaVectorIR = {
          var node = base
          for (level <- 0 until depth) {
            node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
            else new SubDays(node, new LiteralSlot(slotBase + level))
          }
          node
        }
        val shared = chain(8)
        val roots = Seq[VarkaVectorIR](
          chainOver(shared, 6, 8), chainOver(shared, 6, 14))
        val offsets = (0 until 20).map(level => level * 13 + 1).toArray
        val split = emit(roots, 2, 20, loader, 600,
          VarkaEmitOptions.DEFAULTS.withGroupBudget(16))
        val together = emit(roots, 2, 20, loader, 601,
          VarkaEmitOptions.DEFAULTS.withGroupBudget(24))
        def run(kernel: VarkaFusedKernel): Unit = {
          kernel.run(Array(mxData.address(), mx2Data.address()),
            Array(mxValidity.address(), mx2Validity.address()), Array(mxNulls, mx2Nulls),
            Array(dst.address(), dst2.address()),
            Array(dstValidity.address(), dst2Validity.address()), offsets, numRows)
        }
        benchmark.addCase("budget 16 (shipped): two loop methods, shared chain recomputed") {
          _ => run(split)
        }
        benchmark.addCase("budget 24: one loop method, cross-output CSE kept") { _ =>
          run(together)
        }
        benchmark.run()
      }

      runBenchmark("widest shape: MAX_FUSED_NODES ops in one kernel") {
        // Four disjoint depth-16 chains: 64 distinct ops, the cap exactly - emitted as four
        // GROUP_BUDGET-sized loop methods since task 11, which is what keeps this case honest
        // in a JVM that has compiled every other kernel in this file first (see
        // PLAN_TASK_11.md section 6). The sequential version is the same 64 kernel passes.
        val benchmark = new Benchmark(s"4 outputs x depth 16 over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val roots = (0 until 4).map(k => chain(16, slotBase = k * 16))
        val offsets = (0 until 64).map(level => level * 7 + 1).toArray
        val fused = emit(roots, 1, 64, loader, 300)
        benchmark.addCase("fused, 64 ops") { _ =>
          fused.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
            wideDst.map(_.address()), wideDstValidity.map(_.address()), offsets, numRows)
        }
        benchmark.addCase("sequential kernels, 64 passes") { _ =>
          for (k <- 0 until 4) {
            sequentialChain(16, offsets.slice(k * 16, k * 16 + 16),
              mxData.address(), mxValidity.address(), mxNulls,
              wideDst(k).address(), wideDstValidity(k).address())
          }
        }
        benchmark.run()
      }

      runBenchmark("next_day: the literal kernel, the column kernel, the derived leaf and " +
          "the row engine's own path (task 59)") {
        // Task 59's measurement (PLAN_TASK_59.md 6). next_day with a weekday column runs as
        // the two-input kernel over an int32 column the evaluator derives per batch from the
        // string column through the row engine's own parser; the fused form is the column
        // kernel plus the leaf, and the anchor it is held to is getNextDateExact per row, the
        // path the row engine runs: the same parse and the arithmetic, per row. The literal
        // kernel is the control that must not move, and the column kernel is priced beside it
        // (one load in place of a broadcast). The leaf is priced alone under both parsers,
        // over valid names and over names of which a tenth are unrecognised, where the
        // row-engine parser constructs an exception per bad row. Driven in 4096-row chunks
        // over the whole buffer like the year section, for the same reason: the anchor walks
        // every row, so the kernel must too.
        val repeats = 20
        val chunk = 4096
        val benchmark = new Benchmark(s"${numRows.toLong * repeats} rows in 4096-row chunks",
          numRows.toLong * repeats,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        def eachChunk(body: (Long, Long, Int) => Unit): Unit = {
          var pass = 0
          while (pass < repeats) {
            var done = 0
            while (done < numRows) {
              val n = math.min(chunk, numRows - done)
              body(done * 4L, done / 8L, n)
              done += n
            }
            pass += 1
          }
        }
        // The weekday column: the 21 spellings in three case styles, cycling; and the same
        // with every tenth row unrecognised. One Arrow vector per 4096-row chunk, which is
        // what the evaluator hands the leaf - a cached batch is its own vector - read through
        // the same ArrowColumnVector accessor it reads a cached string column by.
        val spellings = Seq("SU", "SUN", "SUNDAY", "MO", "MON", "MONDAY", "TU", "TUE",
          "TUESDAY", "WE", "WED", "WEDNESDAY", "TH", "THU", "THURSDAY", "FR", "FRI", "FRIDAY",
          "SA", "SAT", "SATURDAY")
        def styled(i: Int): String = {
          val s = spellings(i % spellings.length)
          (i / spellings.length) % 3 match {
            case 0 => s
            case 1 => s.toLowerCase(Locale.ROOT)
            case _ => s.head.toString + s.tail.toLowerCase(Locale.ROOT)
          }
        }
        val allocator = ArrowUtils.rootAllocator.newChildAllocator("next-day-bench", 0,
          Long.MaxValue)
        val chunks = (numRows + chunk - 1) / chunk
        // Builds every chunk's vector, closing the ones already built if a later allocation
        // fails. `Array.tabulate` would drop the partial array on the way out, leaking each
        // vector it had filled - and `allocateNew` is exactly the call that can fail here.
        def names(bad: Int => Boolean): Array[VarCharVector] = {
          val built = new Array[VarCharVector](chunks)
          try {
            var c = 0
            while (c < chunks) {
              val n = math.min(chunk, numRows - c * chunk)
              val v = new VarCharVector("s", allocator)
              built(c) = v
              v.allocateNew(n * 10L, n)
              for (i <- 0 until n) {
                val row = c * chunk + i
                val s = if (bad(row)) "xyz" else styled(row)
                v.setSafe(i, s.getBytes(StandardCharsets.UTF_8))
              }
              v.setValueCount(n)
              c += 1
            }
            built
          } catch {
            case e: Throwable =>
              closeNames(built, e)
              throw e
          }
        }
        // The two arrays are built *inside* the try, and assigned to vars declared outside it,
        // so that the finally owns them from the first vector onward. Built before it - as they
        // were - a failure in the second `names` call left the first array and the allocator
        // unreachable for the rest of the benchmark JVM.
        var validNames: Array[VarCharVector] = null
        var tenthBad: Array[VarCharVector] = null
        try {
          validNames = names(_ => false)
          tenthBad = names(_ % 10 == 9)
          val valid = validNames.map(new ArrowColumnVector(_))
          val mixedNames = tenthBad.map(new ArrowColumnVector(_))
          // k as the kernel reads it after the leaf: -1 .. 5, cycling; the leaf's own output
          // buffers, overwritten per chunk.
          val kData = arena.allocate(numRows * 4L, 8)
          for (i <- 0 until numRows) kData.set(ValueLayout.JAVA_INT, i * 4L, i % 7 - 1)
          val kValidity = arena.allocate((numRows + 7) / 8L, 8)
          val literal = emit(Seq(new NextDay(new ColumnRef(0), new LiteralSlot(0))), 1, 1,
            loader, 890)
          val column = emit(Seq(new NextDay(new ColumnRef(0), new ColumnRef(1))), 2, 0,
            loader, 891)
          def chunkedLiteral(): Unit = eachChunk { (dataOff, validityOff, n) =>
            val status = literal.run(Array(nfData.address() + dataOff), Array(0L), Array(0),
              Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
              Array(3), n)
            require(status == 0, s"the kernel declined a batch: status $status")
          }
          // The column kernel over the k column as the leaf leaves it: null-free, and with the
          // date's mixed-null pattern on the date side (the leaf's own nulls are the parity of
          // the mixed-names case above, not of this kernel).
          def chunkedColumn(mixed: Boolean): Unit = eachChunk { (dataOff, validityOff, n) =>
            val status = if (mixed) {
              column.run(Array(mxData.address() + dataOff, kData.address() + dataOff),
                Array(mxValidity.address() + validityOff, 0L), Array((n + 6) / 7, 0),
                Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                Array.empty[Int], n)
            } else {
              column.run(Array(nfData.address() + dataOff, kData.address() + dataOff),
                Array(0L, 0L), Array(0, 0),
                Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                Array.empty[Int], n)
            }
            require(status == 0, s"the kernel declined a batch: status $status")
          }
          def chunkedLeaf(columns: Array[ArrowColumnVector], parser: WeekdayLeaf.Parser): Unit =
            eachChunk { (dataOff, validityOff, n) =>
              val nulls = WeekdayLeaf.fill(columns((dataOff / 4L).toInt / chunk), n, false,
                parser, kData.address() + dataOff, kValidity.address() + validityOff)
              require(nulls >= 0)
            }
          benchmark.addCase("next_day(d, 'MON'), literal kernel (control), null-free") { _ =>
            chunkedLiteral()
          }
          benchmark.addCase("next_day(d, k), column kernel, null-free") { _ =>
            chunkedColumn(false)
          }
          benchmark.addCase("next_day(d, k), column kernel, mixed nulls on the date") { _ =>
            chunkedColumn(true)
          }
          benchmark.addCase("weekday leaf, row-engine parser, valid names") { _ =>
            chunkedLeaf(valid, WeekdayLeaf.Parser.ROW_ENGINE)
          }
          benchmark.addCase("weekday leaf, ascii parser, valid names") { _ =>
            chunkedLeaf(valid, WeekdayLeaf.Parser.ASCII)
          }
          benchmark.addCase("weekday leaf, row-engine parser, a tenth unrecognised") { _ =>
            chunkedLeaf(mixedNames, WeekdayLeaf.Parser.ROW_ENGINE)
          }
          benchmark.addCase("weekday leaf, ascii parser, a tenth unrecognised") { _ =>
            chunkedLeaf(mixedNames, WeekdayLeaf.Parser.ASCII)
          }
          benchmark.addCase("next_day(d, s) per row, getNextDateExact (the row engine)") { _ =>
            eachChunk { (dataOff, _, n) =>
              val column = valid((dataOff / 4L).toInt / chunk)
              var i = 0
              while (i < n) {
                val days = nfData.get(ValueLayout.JAVA_INT, dataOff + i * 4L)
                dst.set(ValueLayout.JAVA_INT, dataOff + i * 4L,
                  DateTimeExpressionUtils.getNextDateExact(days, column.getUTF8String(i)))
                i += 1
              }
            }
          }
          benchmark.run()
        } finally {
          // Each vector close is guarded and the allocator close sits in its own finally, so
          // one failure cannot skip the rest: the allocator is a child of the process-lifetime
          // root, and skipping its close charges these bytes against the root for the whole run.
          try {
            closeNames(validNames, null)
            closeNames(tenthBad, null)
          } finally {
            allocator.close()
          }
        }
      }

      runBenchmark("trunc with a format column: the dynamic kernel, the literal controls, the " +
          "level leaf and the row engine's own path (task 61)") {
        // Task 61's measurement (PLAN_TASK_61.md 6). trunc(d, fmt) with a format column runs
        // as the two-input kernel over an int32 level column the evaluator derives per batch
        // through the row engine's own parseTruncLevel; the kernel computes all four periods
        // and blends on the level, so it is priced against the widest and the narrowest
        // literal tails (QUARTER, MONTH), which must not move. The leaf is priced alone over
        // valid spellings and over spellings of which a tenth are unrecognised (no error path:
        // an unrecognised format is a null lane), and the anchor is parseTruncLevel then
        // truncDate per row, which is what the row engine runs. 4096-row chunks like the year
        // and next_day sections.
        val repeats = 20
        val chunk = 4096
        val benchmark = new Benchmark(s"${numRows.toLong * repeats} rows in 4096-row chunks",
          numRows.toLong * repeats,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        def eachChunk(body: (Long, Long, Int) => Unit): Unit = {
          var pass = 0
          while (pass < repeats) {
            var done = 0
            while (done < numRows) {
              val n = math.min(chunk, numRows - done)
              body(done * 4L, done / 8L, n)
              done += n
            }
            pass += 1
          }
        }
        // The format column: the eight accepted spellings in three case styles, cycling; and
        // the same with every tenth row a sub-day or unrecognised format.
        val spellings = Seq("YEAR", "YYYY", "YY", "MON", "MONTH", "MM", "QUARTER", "WEEK")
        def styled(i: Int): String = {
          val f = spellings(i % spellings.length)
          (i / spellings.length) % 3 match {
            case 0 => f
            case 1 => f.toLowerCase(Locale.ROOT)
            case _ => f.head.toString + f.tail.toLowerCase(Locale.ROOT)
          }
        }
        val allocator = ArrowUtils.rootAllocator.newChildAllocator("trunc-bench", 0,
          Long.MaxValue)
        val chunks = (numRows + chunk - 1) / chunk
        def formats(bad: Int => Boolean): Array[VarCharVector] = Array.tabulate(chunks) { c =>
          val n = math.min(chunk, numRows - c * chunk)
          val v = new VarCharVector("fmt", allocator)
          v.allocateNew(n * 8L, n)
          for (i <- 0 until n) {
            val row = c * chunk + i
            val f = if (bad(row)) (if (row % 20 == 9) "DAY" else "QTR") else styled(row)
            v.setSafe(i, f.getBytes(StandardCharsets.UTF_8))
          }
          v.setValueCount(n)
          v
        }
        val validFormats = formats(_ => false)
        val tenthBad = formats(_ % 10 == 9)
        try {
          val valid = validFormats.map(new ArrowColumnVector(_))
          val mixedFormats = tenthBad.map(new ArrowColumnVector(_))
          // The level column as the kernel reads it after the leaf: the four codes cycling;
          // also the leaf's own output buffers, overwritten per chunk.
          val levelData = arena.allocate(numRows * 4L, 8)
          for (i <- 0 until numRows) {
            levelData.set(ValueLayout.JAVA_INT, i * 4L, TruncLevelLeaf.WEEK + i % 4)
          }
          val levelValidity = arena.allocate((numRows + 7) / 8L, 8)
          val dynamic = emit(Seq(new TruncDateDynamic(new ColumnRef(0), new ColumnRef(1))),
            2, 0, loader, 902)
          val quarter = emit(Seq(new TruncDate(new ColumnRef(0), TruncLevel.QUARTER)), 1, 0,
            loader, 903)
          val month = emit(Seq(new TruncDate(new ColumnRef(0), TruncLevel.MONTH)), 1, 0,
            loader, 904)
          def chunkedLiteral(kernel: VarkaFusedKernel, mixed: Boolean): Unit =
            eachChunk { (dataOff, validityOff, n) =>
              val status = if (mixed) {
                kernel.run(Array(mxData.address() + dataOff),
                  Array(mxValidity.address() + validityOff), Array((n + 6) / 7),
                  Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                  Array.empty[Int], n)
              } else {
                kernel.run(Array(nfData.address() + dataOff), Array(0L), Array(0),
                  Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                  Array.empty[Int], n)
              }
              require(status == 0, s"the kernel declined a batch: status $status")
            }
          // The dynamic kernel over the level column as the leaf leaves it: null-free, and
          // with the date's mixed-null pattern on the date side.
          def chunkedDynamic(mixed: Boolean): Unit = eachChunk { (dataOff, validityOff, n) =>
            val status = if (mixed) {
              dynamic.run(Array(mxData.address() + dataOff, levelData.address() + dataOff),
                Array(mxValidity.address() + validityOff, 0L), Array((n + 6) / 7, 0),
                Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                Array.empty[Int], n)
            } else {
              dynamic.run(Array(nfData.address() + dataOff, levelData.address() + dataOff),
                Array(0L, 0L), Array(0, 0),
                Array(dst.address() + dataOff), Array(dstValidity.address() + validityOff),
                Array.empty[Int], n)
            }
            require(status == 0, s"the kernel declined a batch: status $status")
          }
          def chunkedLeaf(columns: Array[ArrowColumnVector]): Unit =
            eachChunk { (dataOff, validityOff, n) =>
              val nulls = TruncLevelLeaf.fill(columns((dataOff / 4L).toInt / chunk), n,
                levelData.address() + dataOff, levelValidity.address() + validityOff)
              require(nulls >= 0)
            }
          benchmark.addCase("trunc(d, 'QUARTER'), literal kernel (control), null-free") { _ =>
            chunkedLiteral(quarter, false)
          }
          benchmark.addCase("trunc(d, 'MONTH'), literal kernel (control), null-free") { _ =>
            chunkedLiteral(month, false)
          }
          benchmark.addCase("trunc(d, level), dynamic kernel, null-free") { _ =>
            chunkedDynamic(false)
          }
          benchmark.addCase("trunc(d, 'QUARTER'), literal kernel (control), mixed nulls") { _ =>
            chunkedLiteral(quarter, true)
          }
          benchmark.addCase("trunc(d, level), dynamic kernel, mixed nulls on the date") { _ =>
            chunkedDynamic(true)
          }
          benchmark.addCase("trunc-level leaf, valid formats") { _ =>
            chunkedLeaf(valid)
          }
          benchmark.addCase("trunc-level leaf, a tenth sub-day or unrecognised") { _ =>
            chunkedLeaf(mixedFormats)
          }
          benchmark.addCase("trunc(d, fmt) per row, parseTruncLevel then truncDate (the row " +
              "engine)") { _ =>
            eachChunk { (dataOff, _, n) =>
              val column = valid((dataOff / 4L).toInt / chunk)
              var i = 0
              while (i < n) {
                val days = nfData.get(ValueLayout.JAVA_INT, dataOff + i * 4L)
                val level = DateTimeUtils.parseTruncLevel(column.getUTF8String(i))
                dst.set(ValueLayout.JAVA_INT, dataOff + i * 4L,
                  DateTimeUtils.truncDate(days, level))
                i += 1
              }
            }
          }
          benchmark.run()
        } finally {
          validFormats.foreach(_.close())
          tenthBad.foreach(_.close())
          allocator.close()
        }
      }

      runBenchmark("batch-length alignment: what the scalar tail actually costs (task 24)") {
        // Milestone 4 open question 3, answered before the masked epilogue replaces the tail.
        // Every committed harness in this project happens to be lane-aligned - this file runs
        // one call over 1,000,000 rows, DateVectorOpsBenchmark's sizes are 32 / 10000 /
        // 1000000, and Spark's default COLUMN_BATCH_SIZE is 4096, every one a multiple of 4, 8
        // and 16 - so loopBound == length throughout and the scalar tail has never executed a
        // single row under measurement. Two chunk ladders, because the production shape and
        // the marginal cost are different questions. 4096 against 4095 is the shape a query
        // actually runs: the unaligned arm leaves lanes-1 rows to the tail, which is 0.4% of a
        // batch at 16 lanes and may well sit inside this harness's noise - that being itself
        // the finding. 64 against 63 leaves the same lanes-1 rows in a batch a sixty-fourth
        // the size, magnifying them to a quarter of the work so the marginal cost of a scalar
        // row is measured rather than inferred.
        //
        // Two comparisons, read differently. Within a pair the call counts match to within
        // one, so the difference is the tail and nothing else. Between the pairs the call
        // count rises 64-fold, so the difference prices the per-call prologue - which matters
        // here because emitBody emits that prologue in every mode, so today's tail method
        // re-wraps every segment, re-reads the species and recomputes loopBound before
        // discovering it has no rows to process.
        //
        // Every case processes the same row count, so the columns compare directly across
        // chunk sizes. Each call re-reads the same cache-warm prefix rather than walking the
        // buffer, which holds memory traffic constant and leaves the loop shape as the only
        // variable - the same cache-resident regime the rest of this file runs in, so the
        // rates here are comparable to the chain section above.
        //
        // Read the result as an absolute cost, not a percentage: divide a pair's per-call
        // difference by lanes-1 to get the price of one scalar tail row. The percentage
        // depends entirely on the denominator, and this file's denominator is a bare kernel
        // call. An end-to-end query's is ten to twenty times larger - VarkaFilterBenchmark
        // and VarkaThroughputBenchmark measure 5-25 ns/row once Arrow access and the row
        // boundary are in it - so the same absolute cost is a far smaller share there.
        val repeats = 20
        val benchmark = new Benchmark(s"${numRows.toLong * repeats} rows in chunks",
          numRows.toLong * repeats,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        // fill() nulls every seventh row, so a chunk of n rows read from offset 0 holds
        // exactly this many. Passing the whole buffer's count instead would hand the kernel a
        // null count for a batch it is not running, and its dead-column test reads that.
        def nullsIn(n: Int): Int = (n + 6) / 7
        def chunked(kernel: VarkaFusedKernel, chunk: Int, offsets: Array[Int],
            mixed: Boolean): Unit = {
          var pass = 0
          while (pass < repeats) {
            var done = 0
            while (done < numRows) {
              val n = math.min(chunk, numRows - done)
              if (mixed) {
                kernel.run(Array(mxData.address()), Array(mxValidity.address()),
                  Array(nullsIn(n)), Array(dst.address()), Array(dstValidity.address()),
                  offsets, n)
              } else {
                kernel.run(Array(nfData.address()), Array(0L), Array(0),
                  Array(dst.address()), Array(dstValidity.address()), offsets, n)
              }
              done += n
            }
            pass += 1
          }
        }
        val chainOffsets = (0 until 4).map(level => level * 13 + 1).toArray
        val chain4 = emit(Seq(chain(4)), 1, 4, loader, 700)
        val dow = emit(Seq(new DayOfWeek(new ColumnRef(0))), 1, 0, loader, 701)
        val ladder = Seq(4096 -> "aligned", 4095 -> "lanes-1 tail rows",
          64 -> "aligned", 63 -> "lanes-1 tail rows")
        for ((chunk, note) <- ladder) {
          benchmark.addCase(s"depth-4 chain, chunk $chunk ($note), null-free") { _ =>
            chunked(chain4, chunk, chainOffsets, mixed = false)
          }
          benchmark.addCase(s"depth-4 chain, chunk $chunk ($note), mixed nulls") { _ =>
            chunked(chain4, chunk, chainOffsets, mixed = true)
          }
        }
        // dayofweek as the second shape: its vector body is 20 ops but its scalar tail is one
        // Math.floorMod and two fixups per row, so the tail's share of the work is larger here
        // than on the chain - the shape most likely to make the tail visible at all.
        for ((chunk, note) <- ladder.drop(2)) {
          benchmark.addCase(s"dayofweek, chunk $chunk ($note), null-free") { _ =>
            chunked(dow, chunk, Array.empty[Int], mixed = false)
          }
          benchmark.addCase(s"dayofweek, chunk $chunk ($note), mixed nulls") { _ =>
            chunked(dow, chunk, Array.empty[Int], mixed = true)
          }
        }
        // Task 32 step B1's own case for this ladder (PLAN_TASK_32.md section 7.1): four
        // calendar outputs share nothing in the *loop* under today's grouping - GROUP_BUDGET
        // keeps each field in its own method whether or not shareChronoPrefix is set, which
        // VarkaLoopEmitterSuite pins byte for byte - so the two settings can only differ in the
        // epilogue, the one method every output shares (task 24). That difference is invisible
        // at chunk 4096, which every case elsewhere in this file uses and which divides evenly
        // at every supported lane count, so the epilogue always returns at its length check and
        // is never timed. This ladder's unaligned arms are the only place in the file that runs
        // it at all.
        val fourFieldsCol = Seq[VarkaVectorIR](new Year(new ColumnRef(0)),
          new Month(new ColumnRef(0)), new DayOfMonth(new ColumnRef(0)),
          new Quarter(new ColumnRef(0)))
        val fourFieldsUnshared = emit(fourFieldsCol, 1, 0, loader, 710,
          VarkaEmitOptions.DEFAULTS.withShareChronoPrefix(false))
        val fourFieldsShared = emit(fourFieldsCol, 1, 0, loader, 711)
        val calDstData = wideDst.map(_.address())
        val calDstValidity = wideDstValidity.map(_.address())
        def chunkedCalendar(kernel: VarkaFusedKernel, chunk: Int, mixed: Boolean): Unit = {
          var pass = 0
          while (pass < repeats) {
            var done = 0
            while (done < numRows) {
              val n = math.min(chunk, numRows - done)
              val status = if (mixed) {
                kernel.run(Array(mxData.address()), Array(mxValidity.address()),
                  Array(nullsIn(n)), calDstData, calDstValidity, Array.empty[Int], n)
              } else {
                kernel.run(Array(nfData.address()), Array(0L), Array(0),
                  calDstData, calDstValidity, Array.empty[Int], n)
              }
              require(status == 0, s"the kernel declined a batch: status $status")
              done += n
            }
            pass += 1
          }
        }
        for ((chunk, note) <- ladder) {
          benchmark.addCase(
            s"year+month+day+quarter, unshared, chunk $chunk ($note), null-free") { _ =>
            chunkedCalendar(fourFieldsUnshared, chunk, mixed = false)
          }
          benchmark.addCase(
            s"year+month+day+quarter, shared, chunk $chunk ($note), null-free") { _ =>
            chunkedCalendar(fourFieldsShared, chunk, mixed = false)
          }
        }
        benchmark.run()
      }

      runBenchmark("task 44: the epilogue's HugeMethodLimit crossing (PLAN_TASK_32.md 7.1)") {
        // The four-field ladder above never shows the crossing this task is actually about:
        // sixteen calendar outputs (four date columns) sit at 7531 bytes unshared - already
        // under the 8000-byte HugeMethodLimit, so sharing there has nothing to cross, and the
        // near-identical numbers above are the honest result of that. Five date columns of
        // four fields is twenty outputs, which PLAN_TASK_32.md's ladder measures at 9436 bytes
        // unshared - past the limit, so HotSpot compiles epilogueMasked at no tier at all and it
        // runs interpreted with boxed vectors on every batch whose length is not a lane
        // multiple - and 4048 bytes shared, comfortably under it. This section is where that
        // crossing is priced rather than inferred from bytecode size alone.
        val repeats = 20
        val cols = 5
        val benchmark = new Benchmark(
          s"${numRows.toLong * repeats} rows, 20 calendar outputs over 5 dates",
          numRows.toLong * repeats,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val srcCols = Array.fill(cols)(fill(arena, _ => false)._1)
        val dstCols = Array.fill(4 * cols)(arena.allocate(numRows * 4L, 8))
        val dstValCols = Array.fill(4 * cols)(arena.allocate((numRows + 7) / 8L, 8))
        val roots20 = (0 until cols).flatMap { c =>
          val col = new ColumnRef(c)
          Seq[VarkaVectorIR](new Year(col), new Month(col), new DayOfMonth(col),
            new Quarter(col))
        }
        val unshared20 = emit(roots20, cols, 0, loader, 900,
          VarkaEmitOptions.DEFAULTS.withShareChronoPrefix(false))
        val shared20 = emit(roots20, cols, 0, loader, 901)
        val srcValidityZero = Array.fill(cols)(0L)
        val srcNullsZero = Array.fill(cols)(0)
        def chunked20(kernel: VarkaFusedKernel, chunk: Int): Unit = {
          var pass = 0
          while (pass < repeats) {
            var done = 0
            while (done < numRows) {
              val n = math.min(chunk, numRows - done)
              val dataOff = done * 4L
              val validityOff = done / 8L
              val status = kernel.run(
                srcCols.map(_.address() + dataOff), srcValidityZero, srcNullsZero,
                dstCols.map(_.address() + dataOff), dstValCols.map(_.address() + validityOff),
                Array.empty[Int], n)
              require(status == 0, s"the kernel declined a batch: status $status")
              done += n
            }
            pass += 1
          }
        }
        // 4096/64 never reach the epilogue at all (every supported lane count divides them),
        // so they are the control: whatever the two settings cost there is the loop, not the
        // crossing. 4095/63 are where the crossing has to show up if it is real.
        for ((chunk, note) <- Seq(4096 -> "aligned", 4095 -> "lanes-1 tail rows",
            64 -> "aligned", 63 -> "lanes-1 tail rows")) {
          benchmark.addCase(s"unshared (9436B epilogue, past HugeMethodLimit), chunk $chunk " +
            s"($note)") { _ => chunked20(unshared20, chunk) }
          benchmark.addCase(s"shared (4048B epilogue, under HugeMethodLimit), chunk $chunk " +
            s"($note)") { _ => chunked20(shared20, chunk) }
        }
        benchmark.run()
      }

      runBenchmark("task 43: one output, widening - where a single loop method stops scaling") {
        // GROUP_BUDGET bounds ops *between* outputs and never inside one, so a single root can
        // emit an arbitrarily wide loop method. The budget's javadoc calls single-output loops
        // healthy "at every width tried" and the width tried was 59 ops; this is the ladder that
        // finds out where that stops being true (PLAN_TASK_43.md).
        //
        // The shape has to vary op count and nothing else, and the three obvious constructions
        // all fail: an AddDays chain varies dependency depth (task 25's axis), repeated calendar
        // nodes get their prefixes shared by task 32 step B1 - which is why section 2.16's own
        // example is 61 ops today rather than the ~190 it records - and repeated identical
        // subtrees are CSE'd away by emitValue. A greatest/least tree over independent
        // dayofweek(d + k) subtrees with distinct literal slots avoids all three, and measures
        // exactly linear at 19 ops per subtree.
        val benchmark = new Benchmark(s"one output over $numRows rows, null-free", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        def dowAt(k: Int): VarkaVectorIR = new DayOfWeek(new AddDays(new ColumnRef(0),
          new LiteralSlot(k)))
        def wideTree(subtrees: Int): VarkaVectorIR = {
          var node: VarkaVectorIR = dowAt(0)
          for (i <- 1 until subtrees) {
            node = if (i % 2 == 0) new Greatest(node, dowAt(i)) else new Least(node, dowAt(i))
          }
          node
        }
        for ((subtrees, expectedOps) <- Seq(1 -> 20, 3 -> 58, 5 -> 96, 8 -> 153, 10 -> 191,
            13 -> 248)) {
          val root = wideTree(subtrees)
          val name = s"org.apache.spark.sql.varka.execution.VarkaFusedLadder$subtrees"
          val javaRoots = new java.util.ArrayList[VarkaVectorIR]()
          javaRoots.add(root)
          val bytes = VarkaLoopEmitter.emit(name, javaRoots, 1, subtrees, null, null,
            VarkaEmitOptions.DEFAULTS)
          // Asserted rather than assumed: if a lowering change moves these, the x-axis of this
          // ladder has silently changed and every number below is about a different shape.
          val ops = VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0",
            "jdk.incubator.vector.IntVector")
          require(ops == expectedOps,
            s"ladder point $subtrees should emit $expectedOps IntVector ops, got $ops")
          loader.defineGeneratedClass(name, bytes)
          val kernel = loader.loadClass(name).getConstructor().newInstance()
            .asInstanceOf[VarkaFusedKernel]
          val offsets = (0 until subtrees).map(k => k * 13 + 1).toArray
          val loopBytes = VarkaEmitterTestSupport.codeSize(bytes, "loopDense0")
          benchmark.addCase(s"$ops ops in one loop method ($loopBytes bytecode bytes)") { _ =>
            val status = kernel.run(Array(nfData.address()), Array(0L), Array(0),
              Array(dst.address()), Array(dstValidity.address()), offsets, numRows)
            require(status == 0, s"the kernel declined a batch: status $status")
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
