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
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaFusedKernel, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * What int32 arithmetic costs in the fused loop, and what Spark's ANSI overflow check costs on
 * top of it (`sql/varka/plans/m4/VARKA-63.md`).
 *
 * A file of its own rather than another section in `VarkaEmitterParityBenchmark`, whose subject
 * is a lowering priced against its hand-written kernel or against a second lowering of the same
 * shape. The question here is a different one - what a *semantic* obligation costs, when the
 * evaluation mode is the only thing that changed - and its rows can then be regenerated,
 * reviewed and quoted on their own.
 *
 * Three things are measured.
 *
 * **The check's price, as an A/B on one node.** Every checked case appears twice, as the same
 * IR emitted with `checkIntOverflow` on and off. Off is not a setting any query may run under -
 * it drops an obligation ANSI mode imposes - it is the reference arm, the same device VARKA-52
 * uses for `guardDayProducers`. The difference between the two rows is the sign test: four
 * lanewise ops and a compare for `+` and `-`, one compare for unary minus, which reads the
 * operand rather than the result. The wrapping form of the same operation is beside them as a
 * control, so the arithmetic's own cost can be read before any check is added.
 *
 * **Where the mask goes.** A `FAIL` node ORs the overflowing lanes into the batch's condemning
 * accumulator; a `NULL` node - `try_add` - clears them from its own validity word instead. The
 * masks are identical and their disposal is not, so `try_add` is priced beside the checked add
 * rather than assumed to match it. Neither is measured with lanes that actually overflow: the
 * columns stay far from the extremes, so both rows price the check doing its work rather than
 * short-circuiting a batch.
 *
 * **What the compile-time bound removes.** `year(d) * 100 + month(d)` is the shape
 * `VARKA-63.md` 6 is about. Its operands are bounded by the calendar, the compiler proves
 * the result cannot leave the int range, and every node in it is emitted `WRAP` - so under ANSI
 * this shape carries no check at all. The section prices that against the same expression with
 * its outer add checked, which is what the emitter would produce without the analysis. There is
 * no third arm with the multiply checked, because there is no such kernel: an int-lane multiply
 * has no cheap overflow test, the compiler declines a checked one outright, and without the
 * bound the whole expression would be residual rather than slower.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaArithmeticBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *          "catalyst/Test/runMain org.apache.spark.sql.VarkaArithmeticBenchmark"
 *      Results will be written to
 *      "sql/catalyst/benchmarks/VarkaArithmeticBenchmark-jdk<NN>-results.txt".
 *   3. both widths, with provenance:
 *        dev/varka_bench_regen.sh catalyst VarkaArithmeticBenchmark
 * }}}
 */
object VarkaArithmeticBenchmark extends BenchmarkBase {

  private val numRows = 1_000_000

  /** Every case id handed to [[emit]], so a reused one is named here rather than deep in a run. */
  private val usedIds = scala.collection.mutable.Set.empty[Int]

  /**
   * Structural dry run: with {@code -Dvarka.bench.dryRun=true} every case is emitted and
   * registered but none is timed, so the whole file's case ids are checked in about as long as
   * it takes to start a JVM instead of the twenty minutes a full run takes to reach the case
   * that collides.
   *
   * It exists because picking a free id by reading the file is unreliable and the emitter's own
   * `require` is the only authority: not every id here is a literal - the trunc block computes
   * `id` and `id + 1` from a tuple list - so a grep answers a question this method answers
   * exactly. `dev/varka_bench_ids.sh` is the front end, and it prints the next free id.
   *
   * A dry run measures nothing and proves nothing about the numbers. It is a check on the
   * file's structure, and it is never a substitute for a regeneration.
   */
  private val dryRun = sys.props.get("varka.bench.dryRun").exists(_.toBoolean)

  /**
   * A case-name substring from `-Dvarka.bench.only`, for investigating one block without
   * paying for the whole file. Diagnostic only: a filtered run must never be written to a
   * results file, since the committed file is the whole surface.
   */
  private val only = sys.props.get("varka.bench.only").filter(_.nonEmpty)

  /** Times `b`, unless this is a dry run - see [[dryRun]] - and only the cases [[only]] keeps. */
  private def runCases(b: Benchmark): Unit = if (!dryRun) {
    only.foreach { pattern =>
      val keep = b.benchmarks.filter(_.name.contains(pattern)).toSeq
      b.benchmarks.clear()
      b.benchmarks ++= keep
    }
    if (b.benchmarks.nonEmpty) b.run()
  }

  /** What a dry run prints instead of timings: every id in use, and the first one that is not. */
  private def reportIds(): Unit = if (dryRun) {
    val ids = usedIds.toSeq.sorted
    // scalastyle:off println
    println(s"varka-bench-ids: ${ids.size} cases, ids ${ids.mkString(",")}")
    println(s"varka-bench-next-free-id: ${if (ids.isEmpty) 0 else ids.max + 1}")
    // scalastyle:on println
  }

  /** The A/B's reference arm: the same IR with VARKA-63's overflow check switched off. */
  private val uncheckedArm = VarkaEmitOptions.DEFAULTS.withCheckIntOverflow(false)

  private def emit(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int,
      loader: VarkaGeneratedClassLoader, n: Int,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): VarkaFusedKernel = {
    // A duplicate id is otherwise a LinkageError from the class loader, well into a
    // regeneration and pointing at the loader rather than at the two cases that chose the
    // same number.
    require(usedIds.add(n), s"case id $n is already in use by another emit in this benchmark")
    val name = s"org.apache.spark.sql.varka.execution.VarkaArithBench$n"
    val javaRoots = new java.util.ArrayList[VarkaVectorIR]()
    roots.foreach(javaRoots.add)
    loader.defineGeneratedClass(name,
      VarkaLoopEmitter.emit(name, javaRoots, numInputs, numLiterals, null, null, options))
    loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
  }

  /**
   * One int32 column, the same shape the parity benchmark fills: values within +-10000 of
   * `shift`, so they are dates when a calendar node reads them and ordinary integers when the
   * arithmetic does, and nowhere near the extremes either way. No case here can therefore
   * overflow, which is what makes the checked and unchecked arms comparable - both run every
   * lane to the end. `shift` is what makes a second column genuinely a second column: two
   * calls with `shift = 0` produce bit-identical data, which made every two-input case here a
   * disguised `datediff(x, x)` until this was added - a benchmark whose own column was a copy
   * of the first still measures real bytes and real throughput, but it could never have been
   * extended into a value check, since nothing it computed could ever be wrong.
   */
  private def fill(arena: Arena, isNull: Int => Boolean, shift: Int = 0):
      (MemorySegment, MemorySegment, Int) = {
    val data = arena.allocate(numRows * 4L, 8)
    val validity = arena.allocate((numRows + 7) / 8L, 8)
    validity.fill(0.toByte)
    var nulls = 0
    for (i <- 0 until numRows) {
      data.set(ValueLayout.JAVA_INT, i * 4L, i % 20000 - 10000 + shift)
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
      // Shifted, not repeated: the two-input cases below are `datediff`, and a second column
      // bit-identical to the first would price the emitted bytes correctly but make every
      // difference zero, which is not the shape a differential over this file would want.
      val (nf2Data, _, _) = fill(arena, _ => false, shift = 137)
      val (mx2Data, mx2Validity, mx2Nulls) = fill(arena, i => i % 11 == 0, shift = 137)
      val dst = arena.allocate(numRows * 4L, 8)
      val dstValidity = arena.allocate((numRows + 7) / 8L, 8)
      val dst2 = arena.allocate(numRows * 4L, 8)
      val dst2Validity = arena.allocate((numRows + 7) / 8L, 8)
      val noLiterals = Array.empty[Int]

      /** One input, null-free or mixed, with the literal values a case's slots expect. */
      def run1(kernel: VarkaFusedKernel, mixed: Boolean, literals: Array[Int]): Unit = {
        val status = if (mixed) {
          kernel.run(Array(mxData.address()), Array(mxValidity.address()), Array(mxNulls),
            Array(dst.address()), Array(dstValidity.address()), literals, numRows)
        } else {
          kernel.run(Array(nfData.address()), Array(0L), Array(0), Array(dst.address()),
            Array(dstValidity.address()), literals, numRows)
        }
        // A declined batch would mean a lane overflowed, which no case here is built to do -
        // and the checked arm would then be measured short-circuiting rather than checking.
        require(status == 0, s"the kernel declined a batch: status $status")
      }

      /** One input, two outputs: the control that computes both calendar fields separately. */
      def runTwoOutputs(kernel: VarkaFusedKernel): Unit = {
        val status = kernel.run(Array(nfData.address()), Array(0L), Array(0),
          Array(dst.address(), dst2.address()),
          Array(dstValidity.address(), dst2Validity.address()), noLiterals, numRows)
        require(status == 0, s"the kernel declined a batch: status $status")
      }

      /** Two inputs, for the `datediff` shape whose operands must be different columns. */
      def run2(kernel: VarkaFusedKernel, mixed: Boolean, literals: Array[Int]): Unit = {
        val status = if (mixed) {
          kernel.run(Array(mxData.address(), mx2Data.address()),
            Array(mxValidity.address(), mx2Validity.address()), Array(mxNulls, mx2Nulls),
            Array(dst.address()), Array(dstValidity.address()), literals, numRows)
        } else {
          kernel.run(Array(nfData.address(), nf2Data.address()), Array(0L, 0L), Array(0, 0),
            Array(dst.address()), Array(dstValidity.address()), literals, numRows)
        }
        require(status == 0, s"the kernel declined a batch: status $status")
      }

      val col = new ColumnRef(0)
      val col1 = new ColumnRef(1)
      val lit = new LiteralSlot(0)
      val one = Array(1)

      runBenchmark("int arithmetic over a column, and what the ANSI check adds") {
        val benchmark = new Benchmark(s"one output over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)

        def binary(op: IntOp, mode: Overflow): Seq[VarkaVectorIR] =
          Seq(new IntArith(op, mode, col, lit))

        val addChecked = emit(binary(IntOp.ADD, Overflow.FAIL), 1, 1, loader, 960)
        val addUnchecked =
          emit(binary(IntOp.ADD, Overflow.FAIL), 1, 1, loader, 961, uncheckedArm)
        val addWrap = emit(binary(IntOp.ADD, Overflow.WRAP), 1, 1, loader, 962)
        val subChecked = emit(binary(IntOp.SUB, Overflow.FAIL), 1, 1, loader, 963)
        val subUnchecked =
          emit(binary(IntOp.SUB, Overflow.FAIL), 1, 1, loader, 964, uncheckedArm)
        val negChecked = emit(Seq(new IntNeg(Overflow.FAIL, col)), 1, 0, loader, 965)
        val negUnchecked =
          emit(Seq(new IntNeg(Overflow.FAIL, col)), 1, 0, loader, 966, uncheckedArm)

        benchmark.addCase("i + 1, ANSI, checked") { _ => run1(addChecked, false, one) }
        benchmark.addCase("i + 1, ANSI, check off") { _ => run1(addUnchecked, false, one) }
        benchmark.addCase("i + 1, LEGACY wrapping") { _ => run1(addWrap, false, one) }
        benchmark.addCase("i + 1, ANSI, checked, mixed nulls") { _ =>
          run1(addChecked, true, one)
        }
        benchmark.addCase("i + 1, ANSI, check off, mixed nulls") { _ =>
          run1(addUnchecked, true, one)
        }
        benchmark.addCase("i - 1, ANSI, checked") { _ => run1(subChecked, false, one) }
        benchmark.addCase("i - 1, ANSI, check off") { _ => run1(subUnchecked, false, one) }
        benchmark.addCase("-i, ANSI, checked") { _ => run1(negChecked, false, noLiterals) }
        benchmark.addCase("-i, ANSI, check off") { _ => run1(negUnchecked, false, noLiterals) }
        runCases(benchmark)
      }

      runBenchmark("where the overflow mask goes: condemn the batch, or null the lane") {
        val benchmark = new Benchmark(s"one output over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)

        val failAdd = emit(Seq(new IntArith(IntOp.ADD, Overflow.FAIL, col, lit)), 1, 1,
          loader, 970)
        val tryAdd = emit(Seq(new IntArith(IntOp.ADD, Overflow.NULL, col, lit)), 1, 1,
          loader, 971)
        val wrapAdd = emit(Seq(new IntArith(IntOp.ADD, Overflow.WRAP, col, lit)), 1, 1,
          loader, 972)

        // Mixed nulls only, for both checked arms. A NULL node nulls lanes whose inputs were
        // valid, so the dispatcher never hands it a dense batch, and its dense body would drop
        // the mask - a null-free row here would price a kernel production never runs.
        benchmark.addCase("i + 1, ANSI, checked (mask condemns the batch)") { _ =>
          run1(failAdd, true, one)
        }
        benchmark.addCase("try_add(i, 1) (mask clears the lane's validity)") { _ =>
          run1(tryAdd, true, one)
        }
        benchmark.addCase("i + 1, LEGACY wrapping (no mask)") { _ => run1(wrapAdd, true, one) }
        runCases(benchmark)
      }

      runBenchmark("arithmetic over calendar fields, where the bound removes the check") {
        val benchmark = new Benchmark(s"one output over $numRows rows", numRows,
          minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
        val hundred = Array(100)

        // year(d) * 100 + month(d) as the compiler emits it under ANSI: the calendar bounds
        // both operands, the product and the sum are proved to stay in range, and every node
        // is WRAP. The paired arm checks the outer add - the one check the analysis removes,
        // and the only one that could be emitted at all, since a checked multiply has no
        // int-lane test and is declined outright.
        def compositeKey(outer: Overflow): Seq[VarkaVectorIR] =
          Seq(new IntArith(IntOp.ADD, outer,
            new IntArith(IntOp.MUL, Overflow.WRAP, new Year(col), lit), new Month(col)))

        val keyBounded = emit(compositeKey(Overflow.WRAP), 1, 1, loader, 980)
        val keyCheckedAdd = emit(compositeKey(Overflow.FAIL), 1, 1, loader, 981)
        // The fields alone, so the rows above can be read against what they are built on.
        val yearOnly = emit(Seq[VarkaVectorIR](new Year(col)), 1, 0, loader, 982)
        val bothFields =
          emit(Seq[VarkaVectorIR](new Year(col), new Month(col)), 1, 0, loader, 983)
        // The other bounded shape: datediff's own range bounds what the arithmetic extends.
        val diffWrap = emit(Seq[VarkaVectorIR](new IntArith(IntOp.ADD, Overflow.WRAP,
          new DateDiff(col, col1), lit)), 2, 1, loader, 984)
        val diffChecked = emit(Seq[VarkaVectorIR](new IntArith(IntOp.ADD, Overflow.FAIL,
          new DateDiff(col, col1), lit)), 2, 1, loader, 985)

        benchmark.addCase("year(d) * 100 + month(d), bound proved, no check") { _ =>
          run1(keyBounded, false, hundred)
        }
        benchmark.addCase("year(d) * 100 + month(d), add checked") { _ =>
          run1(keyCheckedAdd, false, hundred)
        }
        benchmark.addCase("year(d) * 100 + month(d), no check, mixed nulls") { _ =>
          run1(keyBounded, true, hundred)
        }
        benchmark.addCase("year(d) alone") { _ => run1(yearOnly, false, noLiterals) }
        benchmark.addCase("year(d), month(d), no arithmetic") { _ =>
          runTwoOutputs(bothFields)
        }
        benchmark.addCase("datediff(d, d2) + 1, bound proved, no check") { _ =>
          run2(diffWrap, false, one)
        }
        benchmark.addCase("datediff(d, d2) + 1, checked") { _ => run2(diffChecked, false, one) }
        runCases(benchmark)
      }
    } finally {
      loader.release()
      arena.close()
    }
    reportIds()
  }
}
