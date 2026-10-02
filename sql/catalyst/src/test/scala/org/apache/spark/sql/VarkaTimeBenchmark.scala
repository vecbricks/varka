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
import java.nio.ByteOrder

import scala.concurrent.duration._

import jdk.incubator.vector.{IntVector, VectorOperators}

import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaFusedKernel, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeConstants._

/**
 * What the `TIME` extracts cost in the representation Spark stores - nanoseconds of day in a
 * 64-bit lane - against the split representation `SCOPE_MILESTONE_8.md` item 11 proposes:
 * seconds of day in a 32-bit lane, with the nanoseconds within the second beside it.
 *
 * `hour`, `minute` and `second` over a `TIME` are constant divisions, and a `TIME` has no
 * calendar, so the division is the whole cost. In the stored form it is a 64-bit division:
 * three operations through the double lane on a host with the AVX-512 converts, fourteen in
 * the magic-number form the emitter takes without them (`PLAN_TASK_88.md` 9.2), and the
 * results come out in 64-bit lanes, or narrowed to 32-bit ones at the store (`PLAN_TASK_102.md`
 * 8.3). In the split form the same extracts are divisions of a number under 86400 by 3600 and
 * 60, in 32-bit lanes, twice as many to a register and half the bytes to read.
 *
 * Eight arms per shape, adjacent, on the same rows:
 *
 *  - **nanoseconds of day, int64 lanes, conversion form** - the shipped lowering on this
 *    machine, `ConstDivide` through `L2D`, `vdivpd`, `D2L`, stored wide;
 *  - **nanoseconds of day, int64 lanes, conversion form, narrowed store** - the same tree
 *    under a `NarrowLane` root, which is what `hour(t)` compiles to: the quotient narrowed with
 *    `L2I` and stored at four bytes a row under an int mask;
 *  - **nanoseconds of day, int64 lanes, conversion form, narrowed store, half species** - the
 *    same root stored through the int species of half the width, whole, which is the form
 *    `PLAN_TASK_156.md` weighs against the masked one;
 *  - **nanoseconds of day, int64 lanes, magic form** - the same tree emitted with
 *    `useAVX = 2`, the lowering every AVX2-only host in the runner census takes;
 *  - **seconds of day, int32 lanes, emitted** - the split form's extracts as the emitter
 *    lowers an int-lane `ConstDivide`: the multiply-high through 64-bit lanes since task 149,
 *    with the double route it replaced beside it as the reference arm;
 *  - **seconds of day, int32 lanes, emitted bounded multiply** - the same extracts as the
 *    emitter's `BoundedDivide` lowers them, one multiply and one shift each, the emitted twin
 *    of the hand-written arm below (`PLAN_TASK_102.md` 8.4);
 *  - **seconds of day, int32 lanes, hand-written magic multiply** - the split form as item 11
 *    imagines it: one multiply and one logical shift per division, exact over the bounded
 *    dividend, which is the lowering the calendar prefix uses and `ConstDivide` does not have.
 *    The constants are found by search and proven exact by exhaustion at start-up, not
 *    written down.
 *
 * Beside them, the price of the split itself - one 64-bit division by 10^9 and a
 * multiply-subtract, producing both halves as 64-bit outputs since the narrowing store does
 * not exist, so it is an upper bound on the conversion - and two copies as the memory floor
 * of each lane. A shape that extracts one field pays the split for one division; the
 * three-field shape is where a split done once is amortised.
 *
 * A ladder rather than one row count, because the two forms read different bytes per row
 * (8 against 4) and write different ones (8 against 4): at a million rows the int arm may
 * fit a cache level the long arm has left, and the ratio would price that boundary
 * (`sql/varka/skills/benchmarking.md`, "A benchmark that changes bytes per row must be a
 * ladder"). The rungs are `VarkaLongLaneBenchmark`'s.
 *
 * To run this benchmark:
 * {{{
 *   1. build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaTimeBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *          "catalyst/Test/runMain org.apache.spark.sql.VarkaTimeBenchmark"
 *      Results will be written to
 *      "sql/catalyst/benchmarks/VarkaTimeBenchmark-jdk<NN>-results.txt".
 *   3. both widths, with provenance:
 *        dev/varka_bench_regen.sh catalyst VarkaTimeBenchmark
 * }}}
 */
object VarkaTimeBenchmark extends BenchmarkBase {

  /** The rungs, as `VarkaLongLaneBenchmark` chose them against this class of machine. */
  private val ladder: Seq[(Int, String)] = Seq(
    16384 -> "both forms in L2",
    262144 -> "both forms in L3",
    1000000 -> "the long form leaves L3",
    8388608 -> "both forms past L3")

  /** Every case id handed to [[emit]], so a reused one is named here rather than deep in a run. */
  private val usedIds = scala.collection.mutable.Set.empty[Int]

  /**
   * Structural dry run: with {@code -Dvarka.bench.dryRun=true} every kernel is emitted and
   * registered but nothing is timed, so `dev/varka_bench_ids.sh` can check this file's ids in
   * the time a JVM takes to start. A dry run measures nothing.
   */
  private val dryRun = sys.props.get("varka.bench.dryRun").exists(_.toBoolean)

  /**
   * A case-name substring from `-Dvarka.bench.only`, for investigating one arm without paying
   * for the whole ladder. Diagnostic only: a filtered run is never written to a results file.
   */
  private val only = sys.props.get("varka.bench.only").filter(_.nonEmpty)

  private def runCases(b: Benchmark): Unit = if (!dryRun) {
    only.foreach { pattern =>
      val keep = b.benchmarks.filter(_.name.contains(pattern)).toSeq
      b.benchmarks.clear()
      b.benchmarks ++= keep
    }
    if (b.benchmarks.nonEmpty) b.run()
  }

  private def reportIds(): Unit = if (dryRun) {
    val ids = usedIds.toSeq.sorted
    // scalastyle:off println
    println(s"varka-bench-ids: ${ids.size} cases, ids ${ids.mkString(",")}")
    println(s"varka-bench-next-free-id: ${if (ids.isEmpty) 0 else ids.max + 1}")
    // scalastyle:on println
  }

  private def emit(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int,
      loader: VarkaGeneratedClassLoader, n: Int,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS): VarkaFusedKernel = {
    require(usedIds.add(n), s"case id $n is already in use by another emit in this benchmark")
    val name = s"org.apache.spark.sql.varka.execution.VarkaTimeBench$n"
    val javaRoots = new java.util.ArrayList[VarkaVectorIR]()
    roots.foreach(javaRoots.add)
    loader.defineGeneratedClass(name,
      VarkaLoopEmitter.emit(name, javaRoots, numInputs, numLiterals, null, null, options))
    loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
  }

  // ---------------------------------------------------------------------------------------
  // The shapes, in both representations
  // ---------------------------------------------------------------------------------------

  /** The magic form of the 64-bit division, which an AVX2-only host takes without asking. */
  private val magicForm = VarkaEmitOptions.DEFAULTS.withUseAVX(2)

  /** The int lane's conversion through double lanes, the reference arm since task 149. */
  private val doubleRoute = VarkaEmitOptions.DEFAULTS.withMulHiDivide(false)

  /**
   * The narrowed store through the half-width int species (`PLAN_TASK_156.md`): honoured only
   * at a baked lane count, so the count is the host's own, which at every width this file is
   * regenerated at is what the shipped form emits for anyway.
   */
  private val halfSpecies = VarkaEmitOptions.DEFAULTS
    .withLanesOverride(jdk.incubator.vector.LongVector.SPECIES_PREFERRED.length())
    .withNarrowHalfSpecies(true)

  /** The literal slots of the long form: nanoseconds per hour, per minute, per second. */
  private val NANOS_PER_HOUR = SECONDS_PER_HOUR * NANOS_PER_SECOND
  private val NANOS_PER_MINUTE = SECONDS_PER_MINUTE * NANOS_PER_SECOND
  private val longLits = Array(NANOS_PER_HOUR, NANOS_PER_MINUTE, NANOS_PER_SECOND)

  /** The literal slots of the split form: seconds per hour, seconds per minute. */
  private val intLits = Array(SECONDS_PER_HOUR.toInt, SECONDS_PER_MINUTE.toInt)

  /**
   * The three extracts over nanoseconds of day, built the way a compiler with common
   * subexpressions would build them: each field's remainder feeds the next, so the three-field
   * shape divides three times and `minute` alone twice. `SUB(t, MUL(hour, H))` is the
   * remainder; a `%` node does not exist and would lower to the same thing.
   */
  private def longFields: Map[String, VarkaVectorIR] = {
    val t = new ColumnRef(0, LaneType.LONG)
    def lit(i: Int) = new LiteralSlot(i, LaneType.LONG)
    def sub(a: VarkaVectorIR, b: VarkaVectorIR) = new IntArith(IntOp.SUB, Overflow.WRAP, a, b)
    def mul(a: VarkaVectorIR, b: VarkaVectorIR) = new IntArith(IntOp.MUL, Overflow.WRAP, a, b)
    val hour = new ConstDivide(t, NANOS_PER_HOUR)
    val afterHours = sub(t, mul(hour, lit(0)))
    val minute = new ConstDivide(afterHours, NANOS_PER_MINUTE)
    val afterMinutes = sub(afterHours, mul(minute, lit(1)))
    val second = new ConstDivide(afterMinutes, NANOS_PER_SECOND)
    Map("hour" -> hour, "minute" -> minute, "second" -> second)
  }

  /**
   * The same three over seconds of day. `second` needs no division of its own here - it is
   * the remainder after the minutes - so the three-field shape divides twice.
   */
  private def intFields: Map[String, VarkaVectorIR] = {
    val s = new ColumnRef(0, LaneType.INT)
    def lit(i: Int) = new LiteralSlot(i, LaneType.INT)
    def sub(a: VarkaVectorIR, b: VarkaVectorIR) = new IntArith(IntOp.SUB, Overflow.WRAP, a, b)
    def mul(a: VarkaVectorIR, b: VarkaVectorIR) = new IntArith(IntOp.MUL, Overflow.WRAP, a, b)
    val hour = new ConstDivide(s, SECONDS_PER_HOUR)
    val afterHours = sub(s, mul(hour, lit(0)))
    val minute = new ConstDivide(afterHours, SECONDS_PER_MINUTE)
    val second = sub(afterHours, mul(minute, lit(1)))
    Map("hour" -> hour, "minute" -> minute, "second" -> second)
  }

  /**
   * The split form's extracts as the emitter now lowers a division it can bound: `hour` from
   * the seconds under 86400, `minute` from the seconds after the hours under 3600, each one
   * multiply and one shift, with the same constants the hand-written arm searches for. The
   * emitted twin of that arm (`PLAN_TASK_102.md` 8.4).
   */
  private def boundedIntFields: Map[String, VarkaVectorIR] = {
    val s = new ColumnRef(0, LaneType.INT)
    def lit(i: Int) = new LiteralSlot(i, LaneType.INT)
    def sub(a: VarkaVectorIR, b: VarkaVectorIR) = new IntArith(IntOp.SUB, Overflow.WRAP, a, b)
    def mul(a: VarkaVectorIR, b: VarkaVectorIR) = new IntArith(IntOp.MUL, Overflow.WRAP, a, b)
    val hour = BoundedDivide.of(s, SECONDS_PER_HOUR.toInt, SECONDS_PER_DAY.toInt)
    val afterHours = sub(s, mul(hour, lit(0)))
    val minute = BoundedDivide.of(afterHours, SECONDS_PER_MINUTE.toInt, SECONDS_PER_HOUR.toInt)
    val second = sub(afterHours, mul(minute, lit(1)))
    Map("hour" -> hour, "minute" -> minute, "second" -> second)
  }

  /** The shapes priced: each field alone, and the three together, where sharing pays. */
  private val shapes: Seq[(String, Seq[String])] = Seq(
    "hour" -> Seq("hour"),
    "minute" -> Seq("minute"),
    "second" -> Seq("second"),
    "hour, minute, second" -> Seq("hour", "minute", "second"))

  // ---------------------------------------------------------------------------------------
  // The hand-written magic multiply, item 11's arm
  // ---------------------------------------------------------------------------------------

  /**
   * `(M, K)` such that `floor(s / d) == (s * M) >>> K` for every `s` in `[0, range)`, with the
   * product read as an unsigned 32-bit number - which is what `IntVector.mul` followed by a
   * logical shift computes. Found by search from the widest shift down and proven by
   * exhaustion over the range, so a constant that is only nearly exact cannot reach the
   * kernel; the dividend bound is what makes a single multiply enough, exactly as in the
   * calendar prefix, and a divisor with no such pair over its range is a failure here rather
   * than a wrong row.
   */
  private def magic(d: Int, range: Int): (Int, Int) = {
    (31 to 1 by -1).iterator
      .map(k => (((1L << k) + d - 1) / d, k))
      .find { case (m, k) =>
        m <= Int.MaxValue && (range - 1).toLong * m < (1L << 32) &&
          (0 until range).forall(s => ((s.toLong * m) >>> k) == s / d)
      }
      .map { case (m, k) => (m.toInt, k) }
      .getOrElse(throw new IllegalStateException(
        s"no exact single-multiply form for / $d over [0, $range)"))
  }

  private val (hourM, hourK) = magic(SECONDS_PER_HOUR.toInt, SECONDS_PER_DAY.toInt)
  private val (minuteM, minuteK) = magic(SECONDS_PER_MINUTE.toInt, SECONDS_PER_HOUR.toInt)

  private val species = IntVector.SPECIES_PREFERRED
  private val order = ByteOrder.nativeOrder()

  /**
   * The split form's extracts as one multiply and one shift each: `hour` from the seconds,
   * `minute` from the seconds left after the hours (under 3600, which is what lets its
   * constant fit), `second` as the remainder after the minutes. Which of the three are
   * stored is the shape's choice; the arithmetic for the fields before a stored one is done
   * either way, as the emitted forms do it too.
   */
  private def handWritten(src: MemorySegment, dsts: Seq[MemorySegment], fields: Seq[String],
      numRows: Int): Unit = {
    val wantHour = fields.contains("hour")
    val wantMinute = fields.contains("minute")
    val wantSecond = fields.contains("second")
    val hourDst = if (wantHour) dsts(fields.indexOf("hour")) else null
    val minuteDst = if (wantMinute) dsts(fields.indexOf("minute")) else null
    val secondDst = if (wantSecond) dsts(fields.indexOf("second")) else null
    val bound = species.loopBound(numRows)
    var i = 0
    while (i < bound) {
      val s = IntVector.fromMemorySegment(species, src, i * 4L, order)
      val hour = s.mul(hourM).lanewise(VectorOperators.LSHR, hourK)
      if (wantHour) hour.intoMemorySegment(hourDst, i * 4L, order)
      if (wantMinute || wantSecond) {
        val afterHours = s.sub(hour.mul(SECONDS_PER_HOUR.toInt))
        val minute = afterHours.mul(minuteM).lanewise(VectorOperators.LSHR, minuteK)
        if (wantMinute) minute.intoMemorySegment(minuteDst, i * 4L, order)
        if (wantSecond) {
          afterHours.sub(minute.mul(SECONDS_PER_MINUTE.toInt))
            .intoMemorySegment(secondDst, i * 4L, order)
        }
      }
      i += species.length()
    }
    while (i < numRows) {
      val s = src.get(ValueLayout.JAVA_INT, i * 4L)
      if (wantHour) hourDst.set(ValueLayout.JAVA_INT, i * 4L, s / 3600)
      if (wantMinute) minuteDst.set(ValueLayout.JAVA_INT, i * 4L, s / 60 % 60)
      if (wantSecond) secondDst.set(ValueLayout.JAVA_INT, i * 4L, s % 60)
      i += 1
    }
  }

  // ---------------------------------------------------------------------------------------
  // The run
  // ---------------------------------------------------------------------------------------

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      // Every kernel once, before the ladder: a kernel does not depend on the row count, and
      // one id per kernel keeps the emitted class names stable across rungs.
      var nextId = 0
      def kernelId(): Int = { val n = nextId; nextId += 1; n }
      val longK = longFields
      val intK = intFields
      val boundedK = boundedIntFields
      val emitted = shapes.map { case (name, fields) =>
        val longRoots = fields.map(longK)
        val intRoots = fields.map(intK)
        val boundedRoots = fields.map(boundedK)
        name -> Seq(
          ("nanoseconds of day, int64 lanes, conversion form (shipped)",
            emit(longRoots, 1, longLits.length, loader, kernelId()), LaneType.LONG, false),
          ("nanoseconds of day, int64 lanes, conversion form, narrowed store (shipped)",
            emit(longRoots.map(new NarrowLane(_)), 1, longLits.length, loader, kernelId()),
            LaneType.LONG, true),
          ("nanoseconds of day, int64 lanes, conversion form, narrowed store, half species",
            emit(longRoots.map(new NarrowLane(_)), 1, longLits.length, loader, kernelId(),
              halfSpecies),
            LaneType.LONG, true),
          ("nanoseconds of day, int64 lanes, magic form (the AVX2 lowering)",
            emit(longRoots, 1, longLits.length, loader, kernelId(), magicForm),
            LaneType.LONG, false),
          ("seconds of day, int32 lanes, emitted (shipped: multiply-high, task 149)",
            emit(intRoots, 1, intLits.length, loader, kernelId()), LaneType.INT, false),
          ("seconds of day, int32 lanes, emitted (the double route it replaced)",
            emit(intRoots, 1, intLits.length, loader, kernelId(), doubleRoute), LaneType.INT,
            false),
          ("seconds of day, int32 lanes, emitted bounded multiply",
            emit(boundedRoots, 1, intLits.length, loader, kernelId()), LaneType.INT, false))
      }
      val t = new ColumnRef(0, LaneType.LONG)
      val seconds = new ConstDivide(t, NANOS_PER_SECOND)
      val split = emit(Seq[VarkaVectorIR](seconds,
        new IntArith(IntOp.SUB, Overflow.WRAP, t,
          new IntArith(IntOp.MUL, Overflow.WRAP, seconds, new LiteralSlot(2, LaneType.LONG)))),
        1, longLits.length, loader, kernelId())
      val copyLong = emit(Seq[VarkaVectorIR](t), 1, 0, loader, kernelId())
      val copyInt = emit(Seq[VarkaVectorIR](new ColumnRef(0, LaneType.INT)), 1, 0, loader,
        kernelId())
      reportIds()
      for ((numRows, level) <- ladder) {
        runRung(numRows, level, emitted, split, copyLong, copyInt)
      }
    } finally {
      loader.release()
    }
  }

  private def runRung(numRows: Int, level: String,
      emitted: Seq[(String, Seq[(String, VarkaFusedKernel, LaneType, Boolean)])],
      split: VarkaFusedKernel, copyLong: VarkaFusedKernel, copyInt: VarkaFusedKernel): Unit = {
    val arena = Arena.ofConfined()
    try {
      // The same instants in both representations: nanoseconds of day spread over the whole
      // day, and the seconds of day each one falls in.
      val nanos = arena.allocate(numRows * 8L, 8)
      val secs = arena.allocate(numRows * 4L, 8)
      for (i <- 0 until numRows) {
        val t = java.lang.Long.remainderUnsigned(i.toLong * 0x9E3779B97F4A7C15L, NANOS_PER_DAY)
        nanos.set(ValueLayout.JAVA_LONG, i * 8L, t)
        secs.set(ValueLayout.JAVA_INT, i * 4L, (t / NANOS_PER_SECOND).toInt)
      }
      val longDsts = Array.fill(3)(arena.allocate(numRows * 8L, 8))
      val intDsts = Array.fill(3)(arena.allocate(numRows * 4L, 8))
      val validities = Array.fill(3)(arena.allocate((numRows + 7) / 8L, 8))

      // The hand-written arm checked against the definition once per rung, on this rung's
      // rows, before it is timed: a wrong constant would otherwise be a fast row.
      handWritten(secs, intDsts.toSeq, Seq("hour", "minute", "second"), numRows)
      for (i <- 0 until numRows) {
        val s = secs.get(ValueLayout.JAVA_INT, i * 4L)
        require(intDsts(0).get(ValueLayout.JAVA_INT, i * 4L) == s / 3600 &&
          intDsts(1).get(ValueLayout.JAVA_INT, i * 4L) == s / 60 % 60 &&
          intDsts(2).get(ValueLayout.JAVA_INT, i * 4L) == s % 60,
          s"the hand-written magic multiply is wrong at row $i (seconds $s)")
      }

      // `narrowed`: a long-lane kernel whose roots narrow at the store writes int columns.
      def run(k: VarkaFusedKernel, lane: LaneType, outputs: Int,
          narrowed: Boolean = false): Unit = {
        val status = if (lane == LaneType.LONG) {
          val dsts = if (narrowed) intDsts else longDsts
          k.run(Array(nanos.address()), Array(0L), Array(0),
            dsts.take(outputs).map(_.address()), validities.take(outputs).map(_.address()),
            Array.empty[Int], longLits, numRows)
        } else {
          k.run(Array(secs.address()), Array(0L), Array(0),
            intDsts.take(outputs).map(_.address()), validities.take(outputs).map(_.address()),
            intLits, numRows)
        }
        require(status == 0, s"the kernel declined a batch: status $status")
      }

      val benchmark = new Benchmark(s"TIME extracts over $numRows rows - $level", numRows,
        minNumIters = 5, warmupTime = 2.seconds, minTime = 2.seconds, output = output)
      for (((name, fields), (_, arms)) <- shapes.zip(emitted)) {
        for ((arm, kernel, lane, narrowed) <- arms) {
          benchmark.addCase(s"$name: $arm") { _ => run(kernel, lane, fields.size, narrowed) }
        }
        benchmark.addCase(s"$name: seconds of day, int32 lanes, hand-written magic multiply") {
          _ => handWritten(secs, intDsts.toSeq, fields, numRows)
        }
      }
      benchmark.addCase("the split itself: nanoseconds to (seconds, nanoseconds within), " +
        "int64 lanes, both halves stored wide") { _ => run(split, LaneType.LONG, 2) }
      benchmark.addCase("floor: nanoseconds of day copied, int64 lanes") { _ =>
        run(copyLong, LaneType.LONG, 1)
      }
      benchmark.addCase("floor: seconds of day copied, int32 lanes") { _ =>
        run(copyInt, LaneType.INT, 1)
      }
      runCases(benchmark)
    } finally {
      arena.close()
    }
  }
}
