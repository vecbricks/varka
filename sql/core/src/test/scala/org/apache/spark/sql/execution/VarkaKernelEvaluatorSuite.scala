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
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{DateDayVector, VarCharVector}

import org.apache.spark.TaskContext
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, AttributeReference, CaseWhen, Coalesce, DateAdd, If, In, LessThan, Literal, NamedExpression, NextDay, Year}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaDebugInfoReader, VarkaShapeCache}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DateType, IntegerType, StringType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}
import org.apache.spark.unsafe.types.UTF8String

/**
 * Unit tests for [[VarkaKernelEvaluator]]'s task-12 surface: the column-by-column batch
 * assembly (fused, forwarded, residual) and the ownership discipline it owes the vectors it
 * did not allocate. The oracle for the lifetime tests is the Arrow allocator's memory
 * accounting, as in the exec-node suites: a leak shows as memory above the expected level, a
 * double-close of a forwarded vector as memory below it (or as an Arrow reference-count
 * underflow on the input's own close), so the tests assert exact levels, not merely no crash.
 *
 * The exec-node suites cover the same paths end to end; this suite drives the evaluator
 * directly because `eq` on a forwarded vector and the precise close accounting are not
 * observable through a collected result. It also owns the task-13 telemetry round trip off
 * [[VarkaKernelEvaluator.emittedClassBytes]], the one place the emitted bytes are reachable.
 */
class VarkaKernelEvaluatorSuite extends QueryTest with SharedSparkSession {

  private val attrD = AttributeReference("d", DateType)()
  private val intAttr = AttributeReference("i", IntegerType)()
  private val childOutput = Seq(attrD, intAttr)

  // One fused entry, one forwarded, one residual - the task's canonical mixed projection.
  private val mixedList: Seq[NamedExpression] = Seq(
    Alias(DateAdd(attrD, Literal(3)), "a")(),
    intAttr,
    Alias(Add(intAttr, Literal(1)), "inc")())

  private val dates: Seq[java.lang.Integer] = Seq(0, null, -5, 20000)
  private val ints: Seq[java.lang.Integer] = Seq(10, 11, null, 13)

  private def evaluator(
      projectList: Seq[NamedExpression] = mixedList,
      classDumpDirectory: Option[String] = None): VarkaKernelEvaluator =
    new VarkaKernelEvaluator(projectList, childOutput, offHeapColumnVectorEnabled = false,
      operatorName = "Test", classDumpDirectory)

  /** Runs `body` inside an empty task context with a private Arrow child allocator. */
  private def withTask(body: (ColumnarBatch, () => Unit) => Unit): Unit = {
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val input = VarkaColumnarToRowExecSuite.buildBatch(
        BatchSpec("arrow", Seq(dates, ints)), childOutput, allocator)
      body(input, () => context.markTaskCompleted(None))
      input.close()
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the test left Arrow memory allocated")
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("a mixed projection assembles fused, forwarded and residual columns in order") {
    withTask { (input, completeTask) =>
      val kernels = evaluator()
      assert(kernels.canRun(input))
      val out = kernels.project(input)
      assert(out.numCols() === 3)
      assert(out.numRows() === dates.length)
      // The forwarded column is the input's own vector - zero copy means the same object.
      assert(out.column(1) eq input.column(1), "the forwarded column was copied")
      val actual = (0 until out.numRows()).map { r =>
        (0 until out.numCols()).map { c =>
          if (out.column(c).isNullAt(r)) null else Int.box(out.column(c).getInt(r))
        }
      }
      val expected = dates.zip(ints).map { case (d, i) =>
        Seq(
          if (d == null) null else Int.box(d + 3),
          i,
          if (i == null) null else Int.box(i + 1))
      }
      assert(actual === expected)
      kernels.release(out)
      completeTask()
    }
  }

  test("release closes exactly the owned vectors and leaves forwarded ones to the input") {
    withTask { (input, completeTask) =>
      val afterInput = ArrowUtils.rootAllocator.getAllocatedMemory
      val kernels = evaluator()
      val out = kernels.project(input)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory > afterInput,
        "the kernel output should have allocated Arrow memory")
      kernels.release(out)
      // Exactly back to the input's level: above would be a leaked kernel vector, below a
      // closed forwarded one.
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === afterInput,
        "release must close the owned vectors and only those")
      // The forwarded vector is still alive and readable through the input.
      assert(input.column(1).getInt(0) === ints.head)
      completeTask()
    }
  }

  test("an abandoned batch is drained by the task-completion listener, forwarded ones spared") {
    withTask { (input, completeTask) =>
      val afterInput = ArrowUtils.rootAllocator.getAllocatedMemory
      val kernels = evaluator()
      kernels.project(input) // never released - a LIMIT-style early stop
      completeTask()
      // The listener closed the owned vectors and then the child allocator; a leaked vector
      // would have made the allocator's close throw, a double-closed forwarded vector would
      // show below the input's level.
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === afterInput,
        "the task-completion listener must drain exactly the owned vectors")
      assert(input.column(1).getInt(0) === ints.head)
    }
  }

  test("a failed projection leaves nothing allocated, on the kernel and the residual path") {
    withTask { (input, completeTask) =>
      val afterInput = ArrowUtils.rootAllocator.getAllocatedMemory
      // Kernel failure: the fused output vectors were allocated before the kernel ran and must
      // be closed on the way out.
      val kernels = evaluator()
      VarkaColumnarToRowExec.setFailKernelForTesting(true)
      try {
        intercept[Throwable](kernels.project(input))
      } finally {
        VarkaColumnarToRowExec.setFailKernelForTesting(false)
      }
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === afterInput,
        "a kernel failure must close the fused output vectors")
      // Residual failure: the residual pass runs after the kernel, so the fused vectors are
      // live when it throws and must be closed too.
      val exploding = evaluator(Seq(
        Alias(DateAdd(attrD, Literal(3)), "a")(),
        Alias(ExplodingCodegenExpression(), "boom")()))
      intercept[Throwable](exploding.project(input))
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === afterInput,
        "a residual failure must close the fused output vectors")
      completeTask()
    }
  }

  test("the emitted class carries shape-level telemetry, joined back to this execution") {
    withTask { (input, completeTask) =>
      val kernels = evaluator()
      kernels.release(kernels.project(input))
      val bytes = kernels.emittedClassBytes.get
      // The custom attribute through the diagnostics reader: the bytes describe the shape
      // (task 18) - the fused IR, the shape-hash SourceFile, `shape <hash>` as the plan
      // fragment - because the class is shared and must not replay one execution's identity
      // for another.
      assert(VarkaDebugInfoReader.ir(bytes).contains("(addDays "))
      val sourceFile = VarkaDebugInfoReader.sourceFile(bytes)
      assert(sourceFile.matches("VarkaFusedProjection_[0-9a-f]{16}\\.java"), sourceFile)
      val hash = sourceFile.stripPrefix("VarkaFusedProjection_").stripSuffix(".java")
      assert(VarkaDebugInfoReader.planFragment(bytes) === s"shape $hash")
      // The per-execution identity - operator, stage, and the whole projection with the
      // residual entry included - lives in the cache's side table, joined by the hash.
      val executions = VarkaShapeCache.executionsFor(hash)
      assert(executions.exists { e =>
        e.startsWith(s"Varka_Test_Stage${TaskContext.get().stageId()}") &&
          e.contains("date_add") && e.contains("inc")
      }, s"side table misses this execution: ${executions.mkString("; ")}")
      completeTask()
    }
  }

  test("task 16: the emitted class is dumped under its SourceFile name, byte for byte") {
    withTempDir { dumpDir =>
      withTask { (input, completeTask) =>
        val kernels = evaluator(classDumpDirectory = Some(dumpDir.getAbsolutePath))
        kernels.release(kernels.project(input))
        val bytes = kernels.emittedClassBytes.get
        // Shape-named since task 18; the dump happens on hit and miss alike, so a session
        // that configured the directory after the shape was cached still gets its file.
        val sourceFile = VarkaDebugInfoReader.sourceFile(bytes)
        val dumped = new File(dumpDir, sourceFile.stripSuffix(".java") + ".class")
        assert(dumped.exists(), s"no class dumped into $dumpDir")
        // Byte-identical to what ran, and still a class the reader can parse - which is what
        // makes `javap` on it worth anything.
        assert(java.util.Arrays.equals(Files.readAllBytes(dumped.toPath), bytes))
        assert(VarkaDebugInfoReader.ir(Files.readAllBytes(dumped.toPath)).contains("(addDays "))
        completeTask()
      }
    }
  }

  test("task 16: the fusion report names each entry's fate and the residual entry's reason") {
    val lines = VarkaFusionReport.lines(mixedList, childOutput)
    assert(lines.length === 3)
    assert(lines(0) === "a: fused")
    assert(lines(1) === "i: forwarded from i")
    // The reason names the innermost expression that failed, in the query's own column names.
    assert(lines(2).startsWith("inc: residual (unsupported expression:"), lines(2))
    assert(lines(2).contains("i"), lines(2))
  }

  test("task 16: a declined offset and a missing ELSE report their own reasons") {
    // A bare int column offset fuses since task 38; `i + 1` is still a non-foldable,
    // non-column offset expression, which stays declined.
    val nonLiteralOffset = Seq[NamedExpression](
      Alias(DateAdd(attrD, Add(intAttr, Literal(1))), "shifted")(),
      Alias(DateAdd(attrD, Literal(1)), "fused")())
    val offsetLines = VarkaFusionReport.lines(nonLiteralOffset, childOutput)
    assert(offsetLines(0).contains("day offset is not a foldable literal"), offsetLines(0))
    val noElse = Seq[NamedExpression](
      Alias(CaseWhen(Seq((LessThan(attrD, Literal(0, DateType)), attrD)), None), "picked")(),
      Alias(DateAdd(attrD, Literal(1)), "fused")())
    val elseLines = VarkaFusionReport.lines(noElse, childOutput)
    assert(elseLines(0).contains("CASE WHEN without an ELSE branch"), elseLines(0))
  }

  test("task 52: a day shift past the calendar range reports its interval as the reason") {
    val farYear = Seq[NamedExpression](
      Alias(Year(DateAdd(attrD, Literal(20000000))), "far")(),
      Alias(Year(attrD), "near")())
    val lines = VarkaFusionReport.lines(farYear, childOutput)
    assert(lines(0).startsWith("far: residual (day range ["), lines(0))
    assert(lines(0).contains("leaves the calendar lowering's range"), lines(0))
    assert(lines(1) === "near: fused", lines(1))
  }

  test("task 20: the IN cap and the validity-operand declines report their reasons") {
    val overCap = Seq[NamedExpression](
      Alias(If(In(attrD, (1 to 17).map(k => Literal(k, DateType))), attrD,
        DateAdd(attrD, Literal(1))), "picked")(),
      Alias(DateAdd(attrD, Literal(1)), "fused")())
    val capLines = VarkaFusionReport.lines(overCap, childOutput)
    assert(capLines(0).contains("IN list longer than the fused cap of 16"), capLines(0))
    val computed = Seq[NamedExpression](
      Alias(Coalesce(Seq(DateAdd(attrD, Literal(1)), attrD)), "co")(),
      Alias(DateAdd(attrD, Literal(1)), "fused")())
    val coalesceLines = VarkaFusionReport.lines(computed, childOutput)
    assert(coalesceLines(0).contains(
      "coalesce operand before the last is not a bare date column"), coalesceLines(0))
  }

  // ---------------------------------------------------------------------------------------------
  // Task 59: the derived weekday input.
  // ---------------------------------------------------------------------------------------------

  private val attrS = AttributeReference("s", StringType)()

  private def weekdayEvaluator(failOnError: Boolean): VarkaKernelEvaluator =
    new VarkaKernelEvaluator(Seq(Alias(NextDay(attrD, attrS, failOnError), "a")()),
      Seq(attrD, attrS), offHeapColumnVectorEnabled = false, operatorName = "Test", None)

  /** A date column beside a string column, the batch a cached table hands the evaluator. */
  private def weekdayBatch(
      allocator: BufferAllocator,
      dates: Seq[java.lang.Integer],
      names: Seq[String],
      arrowNames: Boolean = true): ColumnarBatch = {
    val d = new DateDayVector("d", allocator)
    d.allocateNew(dates.length)
    dates.zipWithIndex.foreach { case (v, i) => if (v == null) d.setNull(i) else d.setSafe(i, v) }
    d.setValueCount(dates.length)
    val s: ColumnVector = if (arrowNames) {
      val v = new VarCharVector("s", allocator)
      v.allocateNew()
      names.zipWithIndex.foreach { case (n, i) =>
        if (n == null) v.setNull(i) else v.setSafe(i, n.getBytes(StandardCharsets.UTF_8))
      }
      v.setValueCount(names.length)
      new ArrowColumnVector(v)
    } else {
      val v = new OnHeapColumnVector(names.length, StringType)
      names.zipWithIndex.foreach { case (n, i) =>
        if (n == null) v.putNull(i) else v.putByteArray(i, n.getBytes(StandardCharsets.UTF_8))
      }
      v
    }
    val batch = new ColumnarBatch(Array(new ArrowColumnVector(d), s))
    batch.setNumRows(dates.length)
    batch
  }

  /** The row engine's non-ANSI answer: null for a null or unrecognised name or a null date. */
  private def nextDay(d: java.lang.Integer, name: String): java.lang.Integer = {
    if (d == null || name == null) return null
    try {
      Int.box(DateTimeUtils.getNextDateForDayOfWeek(d,
        DateTimeUtils.getDayOfWeekFromString(UTF8String.fromString(name))))
    } catch {
      case _: org.apache.spark.SparkIllegalArgumentException => null
    }
  }

  private def column(out: ColumnarBatch): Seq[java.lang.Integer] =
    (0 until out.numRows()).map(r =>
      if (out.column(0).isNullAt(r)) null else Int.box(out.column(0).getInt(r)))

  test("task 59: the derived weekday input serves the kernel from a string column, grows " +
      "its scratch across batch sizes, and leaves no Arrow memory behind") {
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val kernels = weekdayEvaluator(failOnError = false)
      val smallDates: Seq[java.lang.Integer] = Seq(0, null, 5, 19723, -3)
      val smallNames = Seq("MON", "tue", null, "xyz", "Th")
      val small = weekdayBatch(allocator, smallDates, smallNames)
      assert(kernels.canRun(small))
      val outSmall = kernels.project(small)
      assert(column(outSmall) === smallDates.zip(smallNames).map { case (d, n) => nextDay(d, n) })
      kernels.release(outSmall)
      small.close()
      // A longer batch grows both scratch buffers (the maskBuf discipline), then a shorter one
      // reuses them; the null-count and validity the leaf reports drive the masked body.
      val bigDates: Seq[java.lang.Integer] = (0 until 70).map(i => Int.box(i * 13 - 100))
      val bigNames = (0 until 70).map(i => if (i % 9 == 8) "" else if (i % 2 == 0) "WE" else "sat")
      val big = weekdayBatch(allocator, bigDates, bigNames)
      val outBig = kernels.project(big)
      assert(column(outBig) === bigDates.zip(bigNames).map { case (d, n) => nextDay(d, n) })
      kernels.release(outBig)
      big.close()
      val again = weekdayBatch(allocator, smallDates, smallNames)
      val outAgain = kernels.project(again)
      assert(column(outAgain) === smallDates.zip(smallNames).map { case (d, n) => nextDay(d, n) })
      kernels.release(outAgain)
      again.close()
      context.markTaskCompleted(None)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the derived input's scratch leaked past task completion")
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("task 59: a weekday source that is not an Arrow VarCharVector refuses the batch, and " +
      "under ANSI an unrecognised name declines it") {
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val dates: Seq[java.lang.Integer] = Seq(0, 1, 2)
      val onHeap = weekdayBatch(allocator, dates, Seq("MON", "TUE", "WED"), arrowNames = false)
      assert(!weekdayEvaluator(failOnError = false).canRun(onHeap))
      onHeap.close()
      // ANSI: the leaf never throws; the evaluator declines with its own status, so the exec
      // node routes the batch to the row engine, which raises for the live-date row.
      val ansi = weekdayEvaluator(failOnError = true)
      val bad = weekdayBatch(allocator, dates, Seq("MON", "nope", "WED"))
      assert(ansi.canRun(bad))
      val declined = intercept[VarkaBatchDeclined](ansi.project(bad))
      assert(declined.status === VarkaKernelEvaluator.STATUS_DERIVED_INPUT)
      bad.close()
      val goodNames = Seq("MON", null, "WED")
      val good = weekdayBatch(allocator, dates, goodNames)
      val out = ansi.project(good)
      assert(column(out) === dates.zip(goodNames).map { case (d, n) => nextDay(d, n) })
      ansi.release(out)
      good.close()
      context.markTaskCompleted(None)
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }
}
