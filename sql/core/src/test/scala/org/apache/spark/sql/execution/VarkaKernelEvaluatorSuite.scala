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

import org.apache.arrow.memory.{BufferAllocator, OutOfMemoryException}
import org.apache.arrow.vector.{BaseFixedWidthVector, DateDayVector, IntervalYearVector, IntVector, VarCharVector}

import org.apache.spark.TaskContext
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, AttributeReference, CaseWhen, Coalesce, DateAdd, DateAddYMInterval, If, In, LessThan, Literal, NamedExpression, NextDay, Remainder, TruncDate, Year}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaDebugInfoReader, VarkaEmitOptions, VarkaShapeCache}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DateType, IntegerType, StringType, YearMonthIntervalType}
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
class VarkaKernelEvaluatorSuite extends QueryTest with SharedSparkSession with VarkaTestWatchdog {

  private val attrD = AttributeReference("d", DateType)()
  private val intAttr = AttributeReference("i", IntegerType)()
  private val childOutput = Seq(attrD, intAttr)

  // One fused entry, one forwarded, one residual - the task's canonical mixed projection.
  // The residual one was `i + 1` until VARKA-63 lowered int arithmetic and it started fusing;
  // `i % 7` is the same shape built from an operator that is still nobody's arm.
  private val mixedList: Seq[NamedExpression] = Seq(
    Alias(DateAdd(attrD, Literal(3)), "a")(),
    intAttr,
    Alias(Remainder(intAttr, Literal(7)), "inc")())

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
          if (i == null) null else Int.box(i % 7))
      }
      assert(actual === expected)
      kernels.release(out)
      completeTask()
    }
  }

  test("under severalKernels a projection past one kernel assembles every kernel's columns in " +
      "order, and release closes each of them") {
    // Two hundred entries are past the unrolled driver's ceiling, so the compiler serves them with
    // two kernels (VARKA-190.md 11); the forwarded and residual entries sit between them.
    val adds = (0 until 200).map(k => Alias(DateAdd(attrD, Literal(k)), s"a$k")())
    val projectList = (adds.take(100) :+ intAttr) ++ adds.drop(100) :+
      Alias(Remainder(intAttr, Literal(7)), "inc")()
    VarkaColumnarToRowExec.setEmitOptionsForTesting(
      VarkaEmitOptions.DEFAULTS.withDriverOutputTable(false).withSeveralKernels(true))
    try {
      withTask { (input, completeTask) =>
        val kernels = evaluator(projectList)
        assert(kernels.partialPlan.get.kernels.size === 2)
        assert(kernels.canRun(input))
        val out = kernels.project(input)
        assert(out.numCols() === 202)
        assert(out.column(100) eq input.column(1), "the forwarded column was copied")
        for (r <- dates.indices; c <- 0 until 202) {
          val expected: java.lang.Integer = c match {
            case 100 => ints(r)
            case 201 => if (ints(r) == null) null else Int.box(ints(r) % 7)
            case _ =>
              val k = if (c < 100) c else c - 1
              if (dates(r) == null) null else Int.box(dates(r) + k)
          }
          val actual = if (out.column(c).isNullAt(r)) null else Int.box(out.column(c).getInt(r))
          assert(actual === expected, s"row $r column $c")
        }
        kernels.release(out)
        completeTask()
      }
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS)
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

  test("the emit.useAVX level reaches the shape key, so a session at a level emits its own " +
      "class") {
    // VARKA-121's session switch: `spark.sql.codegen.varka.emit.useAVX` is read on the driver,
    // carried to the evaluator and applied onto the emit options that form the shape key. The
    // level is part of the shape hash, so the class name moves with it - which is what keeps a
    // level-2 kernel from being served to a default-level session out of the shared cache.
    withTask { (input, _) =>
      val byDefault = evaluator()
      byDefault.release(byDefault.project(input))
      val atTwo = new VarkaKernelEvaluator(mixedList, childOutput,
        offHeapColumnVectorEnabled = false, operatorName = "Test", None, VarkaExecMetrics(),
        emitUseAVX = 2)
      atTwo.release(atTwo.project(input))
      val defaultBytes = byDefault.emittedClassBytes.get
      val level2Bytes = atTwo.emittedClassBytes.get
      // Same IR, different shape: only the options moved.
      assert(VarkaDebugInfoReader.ir(defaultBytes) === VarkaDebugInfoReader.ir(level2Bytes))
      assert(VarkaDebugInfoReader.sourceFile(defaultBytes) !==
        VarkaDebugInfoReader.sourceFile(level2Bytes),
        "a level in the options must change the shape hash and so the class")
    }
  }

  test("the emitted class carries shape-level telemetry, joined back to this execution") {
    withTask { (input, completeTask) =>
      val kernels = evaluator()
      kernels.release(kernels.project(input))
      val bytes = kernels.emittedClassBytes.get
      // The custom attribute through the diagnostics reader: the bytes describe the shape
      // (VARKA-18) - the fused IR, the shape-hash SourceFile, `shape <hash>` as the plan
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

  test("the emitted class is dumped under its SourceFile name, byte for byte") {
    withTempDir { dumpDir =>
      withTask { (input, completeTask) =>
        val kernels = evaluator(classDumpDirectory = Some(dumpDir.getAbsolutePath))
        kernels.release(kernels.project(input))
        val bytes = kernels.emittedClassBytes.get
        // Shape-named since VARKA-18; the dump happens on hit and miss alike, so a session
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

  test("an interval column serves the kernel, and an interval output is allocated") {
    // Both sides of the admission in one run: `IntervalYearVector` accepted as an input by
    // `isArrowBacked`, and `YearMonthIntervalType` allocated as an output by
    // `allocateVector`. Nothing between them changes - the value is a month count in an int32
    // buffer either way - so what this test really pins is that the two allowlists agree.
    val ymAttr = AttributeReference("ym",
      YearMonthIntervalType(YearMonthIntervalType.MONTH, YearMonthIntervalType.MONTH))()
    val output = Seq(attrD, ymAttr)
    val months: Seq[java.lang.Integer] = Seq(1, 12, null, -3)
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test-ym", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val input = VarkaColumnarToRowExecSuite.buildBatch(
        BatchSpec("arrow", Seq(dates, months)), output, allocator)
      // A date output over the interval count, and the interval itself forwarded through a
      // combinator so the output vector is interval-typed rather than merely forwarded.
      val projectList: Seq[NamedExpression] = Seq(
        Alias(DateAddYMInterval(attrD, ymAttr), "a")(),
        Alias(Coalesce(Seq(ymAttr, Literal(0, ymAttr.dataType))), "b")())
      val kernels = new VarkaKernelEvaluator(projectList, output,
        offHeapColumnVectorEnabled = false, operatorName = "Test", None)
      assert(kernels.canRun(input), "an IntervalYearVector input should be servable")
      val out = kernels.project(input)
      assert(out.numCols() === 2)
      assert(out.numRows() === dates.length)
      // The second output really is an interval vector, which is `allocateVector`'s arm.
      val second = out.column(1).asInstanceOf[ArrowColumnVector].getValueVector()
      assert(second.isInstanceOf[IntervalYearVector],
        s"expected an IntervalYearVector output, got ${second.getClass.getSimpleName}")
      // coalesce over a null interval gives the literal; the null date row stays null.
      assert(out.column(1).getInt(2) === 0)
      assert(out.column(0).isNullAt(1))
      context.markTaskCompleted(None)
      input.close()
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the interval output leaked Arrow memory")
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("an interval output takes the int output's write path, byte for byte") {
    // What `VARKA-68.md` 6.1 called the Arrow write path, and what is actually there.
    // The kernel never touches an Arrow vector object: `project` reads
    // `getDataBuffer().memoryAddress()` off each output and the emitted loop writes four-byte
    // lanes into that address, so the only thing `allocateVector`'s arm decides is which class
    // holds the buffer. Both classes are `BaseFixedWidthVector`s of width four, so
    // `allocateNew` reserves the same bytes and the same bytes land in them.
    //
    // This is pinned rather than measured, because a benchmark can only fail to find a
    // difference that is not there, while this fails the day Arrow changes a width or someone
    // adds a per-type write. The widths are asserted first: they are the premise, and they
    // belong to a library this repository does not own.
    assert(IntVector.TYPE_WIDTH === 4)
    assert(DateDayVector.TYPE_WIDTH === IntVector.TYPE_WIDTH)
    assert(IntervalYearVector.TYPE_WIDTH === IntVector.TYPE_WIDTH,
      "an interval month count is an int32 lane; the whole type admission rests on it")

    val ymAttr = AttributeReference("ym",
      YearMonthIntervalType(YearMonthIntervalType.MONTH, YearMonthIntervalType.MONTH))()
    val output = Seq(intAttr, ymAttr)
    // One set of counts, held twice under the two Spark types, so the two projections below
    // differ in nothing but the type - which is the whole experiment.
    val counts: Seq[java.lang.Integer] = Seq(7, -3, null, 0, 2147483, -2147483)
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test-write", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      // The same arithmetic twice: `i + 3` over the int column, `ym + INTERVAL 3 MONTH` over
      // the interval one. Both lower to one `IntArith` over a column and a literal slot.
      val asInt: Seq[NamedExpression] = Seq(Alias(Add(intAttr, Literal(3)), "a")())
      val asInterval: Seq[NamedExpression] =
        Seq(Alias(Add(ymAttr, Literal(3, ymAttr.dataType)), "a")())
      val buffers = Seq(asInt, asInterval).map { projectList =>
        val input = VarkaColumnarToRowExecSuite.buildBatch(
          BatchSpec("arrow", Seq(counts, counts)), output, allocator)
        val kernels = new VarkaKernelEvaluator(projectList, output,
          offHeapColumnVectorEnabled = false, operatorName = "Test", None)
        assert(kernels.canRun(input), "both spellings should be servable")
        val out = kernels.project(input)
        val vector = out.column(0).asInstanceOf[ArrowColumnVector].getValueVector()
          .asInstanceOf[BaseFixedWidthVector]
        val data = vector.getDataBuffer()
        val bytes = Array.tabulate(4 * counts.length)(i => data.getByte(i.toLong))
        val cls = vector.getClass.getSimpleName
        val capacity = data.capacity()
        input.close()
        (cls, capacity, bytes.toSeq)
      }
      val (intClass, intCapacity, intBytes) = buffers.head
      val (ymClass, ymCapacity, ymBytes) = buffers(1)
      // The classes really do differ, or the comparison below proves nothing.
      assert(intClass === "IntVector" && ymClass === "IntervalYearVector",
        s"expected the two arms to allocate different classes, got $intClass and $ymClass")
      assert(ymCapacity === intCapacity, "same width, so allocateNew reserves the same buffer")
      assert(ymBytes === intBytes,
        "the interval output's data buffer must hold the int output's bytes exactly")
      context.markTaskCompleted(None)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "one of the two arms leaked Arrow memory")
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("the fusion report names each entry's fate and the residual entry's reason") {
    val lines = VarkaFusionReport.lines(mixedList, childOutput)
    assert(lines.length === 3)
    assert(lines(0) === "a: fused")
    assert(lines(1) === "i: forwarded from i")
    // The reason names the innermost expression that failed, in the query's own column names.
    assert(lines(2).startsWith("inc: residual (unsupported expression:"), lines(2))
    assert(lines(2).contains("i"), lines(2))
  }

  test("a declined offset and a missing ELSE report their own reasons") {
    // A bare int column offset fuses since VARKA-38 and int arithmetic over one since VARKA-63,
    // so the declining shape here is `i % 7`: an offset expression built from an operator
    // neither task lowered, which is what still reaches this reason.
    val nonLiteralOffset = Seq[NamedExpression](
      Alias(DateAdd(attrD, Remainder(intAttr, Literal(7))), "shifted")(),
      Alias(DateAdd(attrD, Literal(1)), "fused")())
    val offsetLines = VarkaFusionReport.lines(nonLiteralOffset, childOutput)
    assert(offsetLines(0).contains("day offset is not a foldable literal"), offsetLines(0))
    val noElse = Seq[NamedExpression](
      Alias(CaseWhen(Seq((LessThan(attrD, Literal(0, DateType)), attrD)), None), "picked")(),
      Alias(DateAdd(attrD, Literal(1)), "fused")())
    val elseLines = VarkaFusionReport.lines(noElse, childOutput)
    assert(elseLines(0).contains("CASE WHEN without an ELSE branch"), elseLines(0))
  }

  test("a day shift past the calendar range reports its interval as the reason") {
    val farYear = Seq[NamedExpression](
      Alias(Year(DateAdd(attrD, Literal(20000000))), "far")(),
      Alias(Year(attrD), "near")())
    val lines = VarkaFusionReport.lines(farYear, childOutput)
    assert(lines(0).startsWith("far: residual (day range ["), lines(0))
    assert(lines(0).contains("leaves the calendar lowering's range"), lines(0))
    assert(lines(1) === "near: fused", lines(1))
  }

  test("the IN cap and the validity-operand declines report their reasons") {
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
  // VARKA-59: the derived weekday input.
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

  test("the derived weekday input serves the kernel from a string column, grows " +
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

  test("a scratch grow that runs out of memory leaves the previous buffer " +
      "usable, and the task's cleanup closes it exactly once") {
    // `grown` used to close the old buffer before allocating the replacement. `buffer` throws
    // Arrow's OutOfMemoryException, a plain RuntimeException that `serveBatch` catches as a
    // per-batch failure - so the task kept running with `derivedData(i)` holding a *closed*
    // buffer. Arrow's close only releases the reference: `capacity()` and `memoryAddress()`
    // stay plain field reads, so the next smaller batch found the stale capacity big enough,
    // skipped the regrow, and had the leaf write through an address the allocator had freed;
    // the cleanup then closed it again and took the reference count negative.
    //
    // The allocator here is capped so the wide batch's scratch cannot be satisfied while the
    // narrow one's can. What this asserts is the invariant, not the corruption: after the
    // failure the evaluator still answers the narrow batch correctly - which it can only do
    // from a live buffer - and task completion accounts to zero, which a double close would
    // not. Allocating before closing is what makes both true.
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val capped = ArrowUtils.rootAllocator.newChildAllocator("varka-capped", 0, 4096)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val kernels = new VarkaKernelEvaluator(
        Seq(Alias(NextDay(attrD, attrS, failOnError = false), "a")()),
        Seq(attrD, attrS), offHeapColumnVectorEnabled = false, operatorName = "Test", None) {
        override protected def taskAllocator(): BufferAllocator = {
          // Still register the completion listener the real one registers; only the allocator
          // it hands back differs.
          ensureCleanup()
          capped
        }
      }
      val narrowDates: Seq[java.lang.Integer] = Seq(19797, null, 19723)
      val narrowNames = Seq("MON", "TUE", null)
      def narrow(): ColumnarBatch = weekdayBatch(allocator, narrowDates, narrowNames)
      val expected = narrowDates.zip(narrowNames).map { case (d, n) => nextDay(d, n) }

      val first = narrow()
      val out = kernels.project(first)
      assert(column(out) === expected)
      kernels.release(out)
      first.close()
      val heldAfterFirst = capped.getAllocatedMemory
      assert(heldAfterFirst > 0, "the narrow batch should have left its scratch allocated")

      // Wide enough that the data scratch alone is past the cap: the grow throws where the
      // evaluator cannot help it, which is exactly the path that used to free the live buffer.
      val wideDates: Seq[java.lang.Integer] = (0 until 2000).map(i => Int.box(19000 + i))
      val wideNames = (0 until 2000).map(i => Seq("MON", "TUE", "WED")(i % 3))
      val wide = weekdayBatch(allocator, wideDates, wideNames)
      assert(kernels.canRun(wide))
      intercept[OutOfMemoryException](kernels.project(wide))
      wide.close()
      // The discriminating assertion. Closing before allocating frees the live buffer on the
      // way to the throw, so the allocator's accounting drops; allocating first cannot, so it
      // is unchanged. The reads below cannot be trusted to catch this on their own - freed
      // memory usually still holds its old contents - and the double close at task completion
      // is now caught and logged rather than thrown, so neither of those would fail reliably.
      assert(capped.getAllocatedMemory === heldAfterFirst,
        "the failed grow freed a buffer the evaluator still references")

      // The buffer the failed grow would have freed is still the one this batch reuses.
      val again = narrow()
      val outAgain = kernels.project(again)
      assert(column(outAgain) === expected, "the scratch did not survive the failed grow")
      kernels.release(outAgain)
      again.close()

      context.markTaskCompleted(None)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the derived input's scratch leaked, or was closed twice, past task completion")
    } finally {
      TaskContext.unset()
      capped.close()
      allocator.close()
    }
  }

  test("a weekday source that is not an Arrow VarCharVector refuses the batch, and " +
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

  // ---------------------------------------------------------------------------------------------
  // VARKA-61: the derived trunc-level input.
  // ---------------------------------------------------------------------------------------------

  private def truncEvaluator(): VarkaKernelEvaluator =
    new VarkaKernelEvaluator(Seq(Alias(TruncDate(attrD, attrS), "a")()), Seq(attrD, attrS),
      offHeapColumnVectorEnabled = false, operatorName = "Test", None)

  /** The row engine's answer: null for a null date, a null format or a non-date level. */
  private def truncOf(d: java.lang.Integer, fmt: String): java.lang.Integer = {
    if (d == null || fmt == null) return null
    val level = DateTimeUtils.parseTruncLevel(UTF8String.fromString(fmt))
    if (level < DateTimeUtils.MIN_LEVEL_OF_DATE_TRUNC) null
    else Int.box(DateTimeUtils.truncDate(d, level))
  }

  test("the derived trunc-level input serves the kernel from a string column, an " +
      "all-invalid batch is all NULL with nothing declined, an on-heap source is refused, and " +
      "no Arrow memory is left behind") {
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val kernels = truncEvaluator()
      val dates: Seq[java.lang.Integer] = Seq(19797, null, 19723, 0, -1, 19783, 19797)
      val formats = Seq("YEAR", "mm", null, "week", "HOUR", "Quarter", "xyz")
      val batch = weekdayBatch(allocator, dates, formats)
      assert(kernels.canRun(batch))
      val out = kernels.project(batch)
      assert(column(out) === dates.zip(formats).map { case (d, f) => truncOf(d, f) })
      kernels.release(out)
      batch.close()
      // Every format invalid: the leaf reports every lane null, the kernel runs over an
      // all-null input and the output is all NULL. Nothing declines - unlike the weekday
      // leaf under ANSI, there is no error the row engine could raise instead.
      val bad = weekdayBatch(allocator, Seq[java.lang.Integer](19797, 19798, 19799),
        Seq("DAY", "xyz", ""))
      val outBad = kernels.project(bad)
      assert(column(outBad) === Seq(null, null, null))
      kernels.release(outBad)
      bad.close()
      // A longer batch grows the scratch (the maskBuf discipline), then a shorter one reuses it.
      val bigDates: Seq[java.lang.Integer] = (0 until 70).map(i => Int.box(i * 13 - 100))
      val bigFormats = (0 until 70).map(i => Seq("yy", "MON", "quarter", "WEEK", "dd")(i % 5))
      val big = weekdayBatch(allocator, bigDates, bigFormats)
      val outBig = kernels.project(big)
      assert(column(outBig) === bigDates.zip(bigFormats).map { case (d, f) => truncOf(d, f) })
      kernels.release(outBig)
      big.close()
      val again = weekdayBatch(allocator, dates, formats)
      val outAgain = kernels.project(again)
      assert(column(outAgain) === dates.zip(formats).map { case (d, f) => truncOf(d, f) })
      kernels.release(outAgain)
      again.close()
      val onHeap = weekdayBatch(allocator, Seq[java.lang.Integer](0, 1), Seq("YEAR", "MONTH"),
        arrowNames = false)
      assert(!kernels.canRun(onHeap))
      onHeap.close()
      context.markTaskCompleted(None)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the derived input's scratch leaked past task completion")
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("a destination validity buffer carries whole 64-bit words, at every length") {
    // VARKA-47's admission check, and the reason it is a committed test rather than a comment:
    // the answer is a property of the Arrow version this repository depends on, and the whole
    // task rests on it. `VarkaLoopEmitter` writes a destination bitmap one lane group at a
    // time today - a byte, a short or an int, never a word - because `validityBitsAt`'s
    // javadoc records that addressing a whole word "would read past the end of the bitmap near
    // it". VARKA-47 wants exactly that word-wide store on the destination side, which needs the
    // buffer behind it to own the whole last word.
    //
    // The bound is `((len + 63) / 64) * 8`: the last lane group's rows are all below `len`, so
    // its word index is at most `(len - 1) >>> 6`, and the store touches the eight bytes of
    // that word. `(len + 7) / 8` - what the kernel's segment is sized to today, and what Arrow
    // is asked for - is smaller than that for every length not a multiple of 64.
    //
    // The lengths are the awkward ones on purpose: below one word, either side of a word
    // boundary, and either side of the default COLUMN_BATCH_SIZE.
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-task-47", 0, Long.MaxValue)
    try {
      for (len <- Seq(1, 7, 8, 9, 63, 64, 65, 127, 128, 1000, 4095, 4096)) {
        val wordBytes = ((len.toLong + 63L) / 64L) * 8L
        for (vector <- Seq[BaseFixedWidthVector](
            new DateDayVector("d", allocator),
            new IntVector("i", allocator),
            new IntervalYearVector("ym", allocator))) {
          try {
            vector.allocateNew(len)
            val capacity = vector.getValidityBuffer().capacity()
            assert(capacity >= wordBytes,
              s"${vector.getClass.getSimpleName} at $len rows: validity capacity $capacity " +
                s"is short of the $wordBytes bytes a word-wide store needs")
            // The nominal size the emitter uses today, for contrast: this is what the store
            // would run off, and it is what section 2.1 of the plan proposes to round up.
            assert((len + 7) / 8 <= capacity)
          } finally {
            vector.close()
          }
        }
      }
    } finally {
      allocator.close()
    }
  }
}
