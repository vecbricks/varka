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

package org.apache.spark.sql.execution

import java.io.File
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.file.Files

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.logging.log4j.Level
import org.mockito.Mockito.{doAnswer, doThrow, mock, never, verify}

import org.apache.spark.{SparkFunSuite, TaskContext}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaFusedKernel,
  VarkaReproduction}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.execution.varka.{VarkaBatchLedger, VarkaClassDump,
  VarkaFallbackAccounting, VarkaKernelRunner, VarkaReasonMetric}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ColumnarBatch, ColumnVector}

/**
 * The guard paths of the Java components `VarkaEvaluatorBase` is composed of (VARKA-251), which the
 * evaluator suites reach only with healthy batches: the order and the guards of the task-end
 * listener, a close that re-enters the ledger, the accounting's null counters, and the class dump's
 * memo. Each test names the edit it would catch.
 */
class VarkaEvaluatorComponentsSuite extends SparkFunSuite {

  /** Runs `body` in a task context, then completes the task, as the ledger's listener expects. */
  private def inTask[T](body: => T): T = {
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val result = body
      context.markTaskCompleted(None)
      result
    } finally {
      TaskContext.unset()
    }
  }

  private def vector(onClose: => Unit): ColumnVector = {
    val v = mock(classOf[ColumnVector])
    doAnswer { _ => onClose; null }.when(v).close()
    v
  }

  private def batchOf(vectors: ColumnVector*): ColumnarBatch =
    new ColumnarBatch(vectors.toArray, 1)

  test("the task end closes the open batches, runs every cleanup in order, then the allocator") {
    // Catches: folding the loops into one try (a throwing close would skip the cleanups), moving
    // the allocator's close ahead of the cleanups (the scratch release would meet a closed
    // allocator), and dropping a guard (a throwing hook would skip the ones after it).
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val events = mutable.ArrayBuffer.empty[String]
    inTask {
      val ledger = new VarkaBatchLedger
      val buffer = ledger.allocator().buffer(1024)
      val throwing = mock(classOf[ColumnVector])
      doThrow(new IllegalStateException("close")).when(throwing).close()
      val owned = vector(events += "close")
      ledger.trackOwned(batchOf(throwing, owned), Array(throwing, owned))
      ledger.onTaskCompletion(() => { events += "first"; throw new IllegalStateException("hook") })
      ledger.onTaskCompletion(() => { events += "second"; buffer.close() })
    }
    // The batch's second vector closed although the first one threw, then the cleanups ran in the
    // order they were registered although the first threw, and the allocator closed last: it
    // would have raised with the buffer still open had it closed before the second cleanup.
    assert(events.toSeq === Seq("close", "first", "second"))
    assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial)
  }

  test("a close that re-enters the ledger does not skip the rest of the task end") {
    // Catches: iterating the live map rather than a snapshot, which a re-entrant `release` turns
    // into a ConcurrentModificationException that skips the cleanups and the allocator's close.
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    var ran = false
    inTask {
      val ledger = new VarkaBatchLedger
      val buffer = ledger.allocator().buffer(1024)
      // Two batches whose vectors each release the other batch, once, so that whichever the
      // task end closes first removes the other from the map it is walking.
      var batchA: ColumnarBatch = null
      var batchB: ColumnarBatch = null
      var releasedB, releasedA = false
      val a = vector(if (!releasedB) { releasedB = true; ledger.release(batchB) })
      val b = vector(if (!releasedA) { releasedA = true; ledger.release(batchA) })
      batchA = batchOf(a)
      batchB = batchOf(b)
      ledger.trackOwned(batchA, Array(a))
      ledger.trackOwned(batchB, Array(b))
      ledger.onTaskCompletion(() => { ran = true; buffer.close() })
    }
    assert(ran, "the cleanups were skipped")
    assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial)
  }

  test("releasing a forwarded batch closes none of the input's vectors") {
    // Catches: a forwarded batch reaching `release`'s "not one of ours" arm, which closes the
    // batch whole and takes the input's vectors with it.
    inTask {
      val ledger = new VarkaBatchLedger
      val column = mock(classOf[ColumnVector])
      val input = new ColumnarBatch(Array(column), 1)
      val forwarded = ledger.forwardColumns(input, Array(0))
      ledger.release(forwarded)
      verify(column, never()).close()
    }
  }

  test("the fallback accounting counts under its cause and tolerates a node with no counters") {
    // Catches: dropping the null check in `add`, which would be an NPE inside `serveBatch`'s catch
    // arm for a node that registered a partial `VarkaExecMetrics`.
    val none = new VarkaFallbackAccounting(
      new VarkaFallbackAccounting.Counters(null, null, null, null, null), () => "kernel",
      () => "")
    none.kernelFailure(new RuntimeException("boom"), null, null)
    none.rowPathFailure(new RuntimeException("boom"), null)
    none.declinedBatch(1, null)
    none.nonArrowBatch()
    none.allocationSample(1L << 30, 1)

    def metric(): SQLMetric = new SQLMetric("sum")
    val Seq(kernel, rowPath, declined, nonArrow, suspect) = Seq.fill(5)(metric())
    val counted = new VarkaFallbackAccounting(
      new VarkaFallbackAccounting.Counters(kernel, rowPath, declined, nonArrow, suspect),
      () => "kernel", () => "")
    counted.kernelFailure(new RuntimeException("boom"), "further kernel", null)
    counted.rowPathFailure(new RuntimeException("boom"), null)
    counted.declinedBatch(1, "further kernel")
    counted.declinedBatch(1, null)
    counted.nonArrowBatch()
    counted.allocationSample(1L << 30, 1)
    assert(Seq(kernel, rowPath, declined, nonArrow, suspect).map(_.value) === Seq(1, 1, 2, 1, 1))
  }

  test("a kernel that fails on every batch warns once and is counted on every batch") {
    // Catches: a warning per batch, which under one-row batches wrote a stack trace for each of
    // a million rows; and a once-only rule so broad that a second kernel's failure, or the
    // row path's, goes unwarned.
    def metric(): SQLMetric = new SQLMetric("sum")
    val Seq(kernel, rowPath) = Seq.fill(2)(metric())
    val accounting = new VarkaFallbackAccounting(
      new VarkaFallbackAccounting.Counters(kernel, rowPath, null, null, null), () => "kernel",
      () => "")
    // On the root logger, as the suites that read these warnings listen: an appender on the
    // class's own logger gives it a configuration of its own, which outlives the test and keeps
    // its warnings from a later suite's root appender.
    val appender = new LogAppender("varka fallback")
    appender.setThreshold(Level.WARN)
    withLogAppender(appender) {
      (1 to 3).foreach(_ => accounting.kernelFailure(new RuntimeException("boom"), null, null))
      (1 to 2).foreach { _ =>
        accounting.kernelFailure(new RuntimeException("boom"), "further kernel", null)
      }
      (1 to 2).foreach(_ => accounting.rowPathFailure(new RuntimeException("boom"), null))
    }
    val warnings = appender.loggingEvents
      .filter(_.getLoggerName == classOf[VarkaFallbackAccounting].getName)
      .map(_.getMessage.getFormattedMessage).toSeq
    assert(warnings.size === 3, warnings.mkString("\n"))
    assert(warnings.count(_.startsWith("The Varka SIMD kernels kernel failed")) === 1, warnings)
    assert(warnings.count(_.startsWith("The Varka SIMD kernels further kernel failed")) === 1,
      warnings)
    assert(warnings.count(_.startsWith("The per-row machinery")) === 1, warnings)
    assert(warnings.forall(_.contains("Later failures of this kind in this evaluator are " +
      "counted")), warnings)
    assert(Seq(kernel, rowPath).map(_.value) === Seq(5, 2))
  }

  test("a declined batch counts against each reason its status names, and the status bits are " +
      "distinct, the kernels' below the evaluator's") {
    // VARKA-304. Catches: a reason's bit shared with another or with the evaluator's own (a log
    // line and a count could not tell them apart), and a count that misses a bit of a status
    // naming several reasons, which a method of two reasons returns by default.
    val kernelBits = Seq(VarkaFusedKernel.STATUS_DAY_PRODUCER, VarkaFusedKernel.STATUS_MONTH_COUNT,
      VarkaFusedKernel.STATUS_MAKE_DATE, VarkaFusedKernel.STATUS_INT_OVERFLOW,
      VarkaFusedKernel.STATUS_DAY_RANGE, VarkaFusedKernel.STATUS_VALUE_RANGE,
      VarkaFusedKernel.STATUS_DECIMAL_SLOT)
    val evaluatorBits = Seq(VarkaKernelRunner.STATUS_INPUT_BOUND,
      VarkaKernelRunner.STATUS_DERIVED_INPUT, VarkaKernelRunner.STATUS_FORCED)
    val bits = kernelBits ++ evaluatorBits
    assert(bits.forall(b => Integer.bitCount(b) == 1), bits)
    assert(bits.distinct.size === bits.size, bits)
    assert(kernelBits.max < evaluatorBits.min, bits)

    val accounting = new VarkaFallbackAccounting(
      new VarkaFallbackAccounting.Counters(null, null, null, null, null), () => "kernel",
      () => "")
    accounting.declinedBatch(
      VarkaFusedKernel.STATUS_MAKE_DATE | VarkaFusedKernel.STATUS_INT_OVERFLOW, null)
    accounting.declinedBatch(VarkaFusedKernel.STATUS_MAKE_DATE, null)
    accounting.declinedBatch(VarkaKernelRunner.STATUS_INPUT_BOUND, null)
    val counts = accounting.declinedByReason().asScala.map { case (k, v) => k -> v.longValue }
    assert(counts.filter(_._2 > 0) ===
      Map("make-date" -> 2L, "int-overflow" -> 1L, "input-bound" -> 1L))
    assert(counts.size === bits.size)
    assert(VarkaFallbackAccounting.describeStatus(
      VarkaFusedKernel.STATUS_DAY_RANGE | VarkaFusedKernel.STATUS_VALUE_RANGE | (1 << 20)) ===
      "day-range|value-range|0x100000")
  }

  test("a reason's metric counts a decline as exact only where its status names it alone, or " +
      "under exactRefusalStatus") {
    // VARKA-313. Catches: a candidate set's reasons counted as if each had fired (the default
    // options fold a method's reasons into one mask), exactRefusalStatus not trusted to name
    // every bit it sets, and a status of bits no name covers counted under no reason.
    def run(exact: Boolean): Map[String, SQLMetric] = {
      val metrics = VarkaFallbackAccounting.REASONS.asScala.map(_ => new SQLMetric("sum", 0L))
      val accounting = new VarkaFallbackAccounting(
        new VarkaFallbackAccounting.Counters(null, null, null, null, null, metrics.asJava),
        exact, () => "kernel", () => "")
      accounting.declinedBatch(
        VarkaFusedKernel.STATUS_MAKE_DATE | VarkaFusedKernel.STATUS_INT_OVERFLOW, null)
      accounting.declinedBatch(VarkaFusedKernel.STATUS_INT_OVERFLOW, null)
      accounting.declinedBatch(VarkaKernelRunner.STATUS_INPUT_BOUND, null)
      accounting.declinedBatch(1 << 20, null)
      VarkaFallbackAccounting.REASONS.asScala.zip(metrics).toMap
    }
    def decoded(metrics: Map[String, SQLMetric]): Map[String, (Long, Long)] =
      metrics.view.mapValues(m => (VarkaReasonMetric.exact(m.value),
        VarkaReasonMetric.unattributed(m.value))).filter(_._2 != ((0L, 0L))).toMap
    assert(decoded(run(exact = false)) === Map("make-date" -> ((0L, 1L)),
      "int-overflow" -> ((1L, 1L)), "input-bound" -> ((1L, 0L)),
      VarkaFallbackAccounting.UNRECOGNISED -> ((1L, 0L))))
    assert(decoded(run(exact = true)) === Map("make-date" -> ((1L, 0L)),
      "int-overflow" -> ((2L, 0L)), "input-bound" -> ((1L, 0L)),
      VarkaFallbackAccounting.UNRECOGNISED -> ((1L, 0L))))
  }

  test("a session's emit settings are bound from its conf once, and lay nothing over the base " +
      "options at the defaults") {
    // VARKA-308. Catches: a setting the binding forgets to read, a default session that changes
    // the options - which would move every shape key and emitted class - and an explicit false
    // that cannot override a base with the setting on.
    val conf = new SQLConf
    assert(VarkaSessionEmit.of(conf) === VarkaSessionEmit.DEFAULTS)
    val base = VarkaEmitOptions.DEFAULTS
    assert(VarkaSessionEmit.DEFAULTS.apply(base) eq base)
    conf.setConf(SQLConf.VARKA_EMIT_USE_AVX, 2)
    conf.setConfString(SQLConf.VARKA_EMIT_EXACT_REFUSAL_STATUS.key, "true")
    val session = VarkaSessionEmit.of(conf)
    assert(session === new VarkaSessionEmit(2, java.lang.Boolean.TRUE))
    assert(session.apply(base) === base.withUseAVX(2).withExactRefusalStatus(true))
    conf.setConfString(SQLConf.VARKA_EMIT_EXACT_REFUSAL_STATUS.key, "false")
    val exactBase = base.withExactRefusalStatus(true)
    assert(VarkaSessionEmit.of(conf).apply(exactBase) === exactBase.withExactRefusalStatus(false)
      .withUseAVX(2))
    intercept[IllegalArgumentException](new VarkaSessionEmit(-2, null))
  }

  test("the class dump writes a shape once, and a failed write is neither thrown nor remembered") {
    // Catches: a broken memo (a disk write per task), a throw out of a diagnostics write, and a
    // failed write left in the memo (the shape never dumped again).
    withTempDir { dir =>
      val first = Array[Byte](1, 2, 3)
      VarkaClassDump.dump(dir.getAbsolutePath, "VarkaFusedProjection_a.java", first)
      VarkaClassDump.dump(dir.getAbsolutePath, "VarkaFusedProjection_a.java", Array[Byte](9))
      val file = new File(dir, "VarkaFusedProjection_a.class")
      assert(Files.readAllBytes(file.toPath).toSeq === first.toSeq, "the memo dumped it twice")

      // A directory that is a file: creating it fails, nothing throws, and the shape is dumped
      // once the obstacle is gone.
      val blocked = new File(dir, "blocked")
      Files.write(blocked.toPath, Array[Byte](0))
      VarkaClassDump.dump(blocked.getAbsolutePath, "VarkaFusedProjection_b.java", first)
      assert(blocked.isFile)
      assert(blocked.delete())
      VarkaClassDump.dump(blocked.getAbsolutePath, "VarkaFusedProjection_b.java", first)
      assert(Files.readAllBytes(new File(blocked, "VarkaFusedProjection_b.class").toPath).toSeq ===
        first.toSeq)
    }
  }

  test("the both-bodies check compares a boolean output's data bit by bit, where it is valid") {
    // VARKA-27 2.5: a bit output's data is a bitmap, which the width-sliced compare would read
    // as nothing. One output over 100 rows of a null-free batch, its two bodies' bitmaps planted:
    // equal, they pass; one data bit flipped on a valid row fails; a data bit flipped under a
    // null row is undefined data and passes.
    val len = 100
    val words = ((len + 63) / 64) * 8L
    val arena = Arena.ofConfined()
    try {
      def bitmap(set: Int => Boolean): MemorySegment = {
        val seg = arena.allocate(words, 8)
        for (r <- 0 until len if set(r)) {
          seg.set(ValueLayout.JAVA_BYTE, r / 8L,
            (seg.get(ValueLayout.JAVA_BYTE, r / 8L) | (1 << (r % 8))).toByte)
        }
        seg
      }
      val valid = (r: Int) => r % 7 != 3
      val known = (r: Int) => r % 3 == 0
      def check(otherData: MemorySegment): Unit = {
        VarkaKernelRunner.compareBodies("shape", len, true, Array(0L), Array(0),
          Array(VarkaReproduction.KernelCall.BIT_OUTPUT),
          new VarkaKernelRunner.BodyOutputs(
            Array(bitmap(known).address()), Array(bitmap(valid).address())),
          new VarkaKernelRunner.BodyOutputs(
            Array(otherData.address()), Array(bitmap(valid).address())))
      }
      check(bitmap(known))
      // Row 64 is valid and its data bit clear in the served body: the first bit of the second,
      // partial word.
      assert(valid(64) && !known(64))
      val e = intercept[IllegalStateException](check(bitmap(r => known(r) || r == 64)))
      assert(e.getMessage.contains("disagree on output 0 at row 64"), e.getMessage)
      // Row 3 is null: its data bit is not compared.
      assert(!valid(3))
      check(bitmap(r => known(r) && r != 3))
    } finally {
      arena.close()
    }
  }
}
