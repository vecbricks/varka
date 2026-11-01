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

import java.time.Duration

import org.apache.logging.log4j.Level

import org.apache.spark.{SparkException, SparkIllegalArgumentException, SparkIllegalStateException}
import org.apache.spark.executor.Executor
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaAllocationSampler, VarkaEmitOption, VarkaEmitOptions, VarkaErrorContext, VarkaErrors, VarkaFallbackEvent, VarkaFamily, VarkaJfrTestSupport, VarkaJitEvidence, VarkaKernelAllocationEvent, VarkaShapeCache, VarkaTestWatchdog, VarkaValueRange}
import org.apache.spark.sql.execution.varka.VarkaFallbackAccounting
import org.apache.spark.sql.internal.SQLConf

/**
 * Varka's failures carry one context record (VARKA-314): what failed, the shape hash, the emit
 * options that differ from the defaults, the batch, and the cause. A bug at planning time
 * reaches the user as Spark's `INTERNAL_ERROR` with the record instead of a bare
 * `IllegalArgumentException`; a fallback's warning and its JFR event name the options; and the
 * refusal of an undeclared fallback under test raises the internal error with the record.
 *
 * The broken kernel here is the `misdescribeAdd` fault injector, which emits `date_add` against a
 * wrong method descriptor, so the kernel throws `NoSuchMethodError` when it runs - and the
 * option that broke it is the one the record names.
 */
class VarkaErrorContextSuite extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

  private val query = "SELECT date_add(d, 3) AS a FROM varka_dates"

  /** Runs `body` with every kernel emitted under `misdescribeAdd`, then restores the options. */
  private def withBrokenKernel[T](body: => T): T = {
    val previous = VarkaColumnarToRowExec.currentEmitOptions
    val broken = VarkaEmitOption.named("misdescribeAdd") match {
      case flag: VarkaEmitOption.Flag => flag.`with`(previous, true)
      case other => fail(s"misdescribeAdd is not a flag: $other")
    }
    VarkaColumnarToRowExec.setEmitOptionsForTesting(broken)
    try body finally VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
  }

  private def withFailureDeclared[T](body: => T): T = {
    VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting(true)
    try body finally VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting(false)
  }

  private def causes(e: Throwable): Seq[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(20).toSeq

  /**
   * The warnings `logger` writes while `body` runs. Captured on the root logger and filtered by
   * name: an appender on a named logger makes log4j create a config of that logger's own, which
   * outlives the test with the level it had then and so stops a later suite's root appender from
   * seeing that logger's warnings.
   */
  private def warnings(logger: Class[_])(body: => Unit): Seq[String] = {
    val appender = new LogAppender("varka failure warnings")
    appender.setThreshold(Level.WARN)
    withLogAppender(appender)(body)
    appender.loggingEvents.toSeq.filter(_.getLoggerName == logger.getName)
      .map(_.getMessage.getFormattedMessage)
  }

  test("the one user setting out of range raises INVALID_CONF_VALUE.REQUIREMENT") {
    val e = intercept[SparkIllegalArgumentException] {
      varkaSpark.conf.set(SQLConf.VARKA_EMIT_USE_AVX.key, "-3")
    }
    checkError(e,
      condition = "INVALID_CONF_VALUE.REQUIREMENT",
      parameters = Map(
        "confName" -> SQLConf.VARKA_EMIT_USE_AVX.key,
        "confValue" -> "-3",
        "confRequirement" -> ("the level is -2 to resolve it from the host, -1 for the " +
          "emitter's default or a -XX:UseAVX value")))
  }

  test("a compiler bug at planning fails the query on the driver with INTERNAL_ERROR and the " +
      "record, keeping its Java type") {
    cacheDates(varkaSpark)
    VarkaExpressionCompiler.setFailForTesting(true)
    val e = try {
      // VarkaColumnarRule asks the compiler on the driver whether the projection fuses.
      intercept[SparkIllegalArgumentException](varkaSpark.sql(query).collect())
    } finally {
      VarkaExpressionCompiler.setFailForTesting(false)
    }
    assert(e.isInstanceOf[IllegalArgumentException])
    checkError(e,
      condition = "INTERNAL_ERROR",
      parameters = Map("message" ->
        ("(?s)Varka's compiler failed while planning\\.\nVarka context:\n" +
          "  kind: planning\n" +
          "  identity: projection \\[date_add\\(d#\\d+, 3\\) AS a#\\d+\\] over \\[d#\\d+.*\\]\n" +
          "  shape hash: none\n" +
          "  options: .*\n" +
          "  cause: java.lang.IllegalArgumentException: injected Varka compiler failure\n" +
          java.util.regex.Pattern.quote(VarkaErrors.REPORT))),
      matchPVals = true)
    assert(e.getCause.getMessage === "injected Varka compiler failure")
    // The hook reset, the same query plans and fuses again.
    assertFused(varkaSpark.sql(query).queryExecution.executedPlan)
  }

  test("a Varka invariant site that fails at planning reaches the user with the planning " +
      "record around it") {
    // The hook runs a real site with an argument it rejects - the range analysis's empty
    // interval - inside the planning catch, as a compiler bug reaches it (VARKA-314b): the
    // site raises INTERNAL_ERROR with no record, and the catch adds the record around it.
    cacheDates(varkaSpark)
    VarkaExpressionCompiler.setFailForTesting(() => { new VarkaValueRange.Bounded(5, 1); () })
    val e = try {
      intercept[SparkIllegalArgumentException](varkaSpark.sql(query).collect())
    } finally {
      VarkaExpressionCompiler.setFailForTesting(false)
    }
    checkError(e,
      condition = "INTERNAL_ERROR",
      parameters = Map("message" ->
        ("(?s)Varka's compiler failed while planning\\.\nVarka context:\n" +
          "  kind: planning\n" +
          "  identity: projection \\[date_add\\(d#\\d+, 3\\) AS a#\\d+\\] over .*\n" +
          "  shape hash: none\n" +
          "  options: .*\n" +
          "  cause: org.apache.spark.SparkIllegalArgumentException: empty range \\[5, 1\\]\n" +
          java.util.regex.Pattern.quote(VarkaErrors.REPORT))),
      matchPVals = true)
    assert(e.getCause.isInstanceOf[SparkIllegalArgumentException], e.getCause)
    assert(e.getMessage.split(java.util.regex.Pattern.quote(VarkaErrors.REPORT), -1).length == 2,
      "the request to report is said once: " + e.getMessage)
  }

  test("an undeclared kernel failure fails the job with INTERNAL_ERROR, the record naming the " +
      "options and the batch") {
    cacheDates(varkaSpark)
    val e = withBrokenKernel(intercept[SparkException](varkaSpark.sql(query).collect()))
    // A task's internal error reaches the driver inside "Job aborted"; DAGScheduler wraps it.
    val internal = causes(e).collectFirst { case s: SparkIllegalStateException => s }
      .getOrElse(fail("no internal error in the failure's causes", e))
    checkError(internal,
      condition = "INTERNAL_ERROR",
      parameters = Map("message" ->
        ("(?s)A failure of the kernel VarkaFusedProjection_.*\\(VARKA-275\\)\n" +
          "Varka context:\n" +
          "  kind: kernel-failure\n" +
          "  identity: VarkaFusedProjection_\\w+\\.java \\[.*\\] \\(Varka_\\w+_Stage\\d+\\)\n" +
          "  shape hash: \\w+\n" +
          "  options: .*misdescribeAdd=true.*\n" +
          "  batch: \\d+ rows, nulls per input \\[\\d+(, \\d+)*\\]\n" +
          "  cause: java.lang.NoSuchMethodError.*\n" +
          java.util.regex.Pattern.quote(VarkaErrors.REPORT))),
      matchPVals = true)
    assert(internal.getCause.isInstanceOf[NoSuchMethodError], internal.getCause)
  }

  test("a declared kernel failure's warning carries the record, and its event the options") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    val expected = spark.sql(query).collect().toSeq
    val (lines, recorded) = VarkaJfrTestSupport.withJfrRecording(classOf[VarkaFallbackEvent]) {
      withBrokenKernel(withFailureDeclared {
        warnings(classOf[VarkaFallbackAccounting]) {
          checkAnswer(varkaSpark.sql(query), expected)
        }
      })
    }
    val warning = lines.find(_.contains("failed on this batch"))
      .getOrElse(fail(s"no kernel-failure warning among: ${lines.mkString("\n")}"))
    assert(warning.contains("\nVarka context:\n  kind: kernel-failure\n"), warning)
    assert(warning.contains("misdescribeAdd=true"), warning)
    // The kernel's date_add is the calendar family's, and the record names its switch, the
    // setting that keeps that work off Varka while the failure is reported (VARKA-318).
    assert(warning.contains(
      s"\n  families: calendar (${VarkaFamily.CALENDAR.switchKey()})\n"), warning)
    assert(warning.matches("(?s).*\n  batch: \\d+ rows, nulls per input \\[.*"), warning)
    assert(warning.contains("\n  cause: java.lang.NoSuchMethodError"), warning)
    val events = recorded.filter(VarkaJfrTestSupport.isEvent(_, classOf[VarkaFallbackEvent]))
      .filter(_.getString("cause") == VarkaFallbackEvent.KERNEL_FAILURE)
    assert(events.nonEmpty, recorded.mkString("; "))
    events.foreach { event =>
      assert(event.getString("options").contains("misdescribeAdd=true"), event)
      assert(event.getString("kernelIdentity").contains("VarkaFusedProjection_"), event)
    }
  }

  test("an emission failure's warning carries the record, with the shape hash computed at the " +
      "catch") {
    cacheDates(spark)
    cacheDates(varkaSpark)
    val expected = spark.sql(query).collect().toSeq
    VarkaColumnarToRowExec.setFailEmissionForTesting(true)
    val lines = try {
      warnings(classOf[VarkaEvaluatorBase]) {
        checkAnswer(varkaSpark.sql(query), expected)
      }
    } finally {
      VarkaColumnarToRowExec.setFailEmissionForTesting(false)
    }
    val warning = lines.find(_.contains("Failed to emit the Varka fused kernel"))
      .getOrElse(fail(s"no emission-failure warning among: ${lines.mkString("\n")}"))
    assert(warning.contains("\nVarka context:\n  kind: emission-failure\n"), warning)
    assert(warning.matches("(?s).*\n  shape hash: \\w+\n.*"), warning)
    assert(!warning.contains("shape hash: none"), warning)
    assert(warning.contains("\n  cause: java.lang.IllegalStateException: injected Varka " +
      "emission failure"), warning)
  }

  test("an emitter invariant that fails building the kernel falls back, its warning naming the " +
      "site on the record's cause line") {
    // misdescribeWordLiveness plants a liveness verdict the emitter's own walk then contradicts:
    // a real invariant site (VarkaVectorWalk's), raised as INTERNAL_ERROR with no record, which
    // the emission catch puts in the record of its warning.
    cacheDates(spark)
    cacheDates(varkaSpark)
    val nullable = "SELECT date_add(d, 3) AS a, year(d) AS y FROM varka_dates"
    val expected = spark.sql(nullable).collect().toSeq
    val previous = VarkaColumnarToRowExec.currentEmitOptions
    // Validity words exist only with validityByBitmap, so the option matrix's arm that turns it
    // off has no liveness to misdescribe; it is turned back on here, under every other arm.
    def flag(name: String, options: VarkaEmitOptions): VarkaEmitOptions =
      VarkaEmitOption.named(name) match {
        case f: VarkaEmitOption.Flag => f.`with`(options, true)
        case other => fail(s"$name is not a flag: $other")
      }
    val planted = flag("misdescribeWordLiveness", flag("validityByBitmap", previous))
    VarkaShapeCache.invalidateAll()
    VarkaColumnarToRowExec.setEmitOptionsForTesting(planted)
    val lines = try {
      withFailureDeclared {
        warnings(classOf[VarkaEvaluatorBase]) {
          checkAnswer(varkaSpark.sql(nullable), expected)
        }
      }
    } finally {
      VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
      VarkaShapeCache.invalidateAll()
    }
    val warning = lines.find(_.contains("Failed to emit the Varka fused kernel"))
      .getOrElse(fail(s"no emission-failure warning among: ${lines.mkString("\n")}"))
    assert(warning.contains("\nVarka context:\n  kind: emission-failure\n"), warning)
    assert(warning.contains("misdescribeWordLiveness=true"), warning)
    assert(warning.matches(
      "(?s).*\n  cause: org\\.apache\\.spark\\.SparkIllegalStateException: [^\n]+"), warning)
    assert(!warning.contains(VarkaErrors.REPORT), warning)
  }

  test("after failures built records on every batch, the kernel's steady state allocates " +
      "nothing") {
    // The record is built only in a catch. This checks the other half of that: a task whose
    // batches all failed and were answered by the row path leaves nothing behind that the
    // kernel's own path then pays per batch. The allocation sampler measures every kernel batch,
    // and a batch reads clean only once C2 has compiled the loop: before that every vector is a
    // heap object, about 120 KB a 10,000-row batch, and a JVM whose compilers do not reach the
    // loop reads so for as long as the test runs, as the option matrix's run after a merge did
    // (VARKA-314.md 9.2). So the measurement is taken twice under one cap: the control, before
    // any failure, and the steady state after. A control that never reads clean is the JVM's
    // compiler and not the failure path, and cancels the test with the JIT's account of the
    // kernel class; the failure path is blamed only for a steady state dirtier than its control.
    val view = "varka_error_context_steady"
    varkaSpark.range(0, 1 << 20)
      .selectExpr("date_add(DATE'2000-01-01', CAST(id % 20000 AS INT)) AS d")
      .createOrReplaceTempView(view)
    varkaSpark.catalog.cacheTable(view)
    val steady = s"SELECT date_add(d, 3) AS a FROM $view"
    // One round's read: kernel batches, kernel-failure fallbacks, suspect allocation samples.
    def round(): (Long, Long, Long) = {
      val df = varkaSpark.sql(steady)
      assert(df.queryExecution.toRdd.count() === (1L << 20))
      val node = collectFirst(df.queryExecution.executedPlan) { case v if isVarkaNode(v) => v }
        .getOrElse(fail("expected a Varka node"))
      def metric(name: String): Long = node.metrics.get(name).map(_.value).getOrElse(0L)
      (metric("numVarkaBatches"), metric("numFallbackBatchesKernel"),
        metric("numSuspectAllocationSamples"))
    }
    val cap = Duration.ofSeconds(30)
    // Rounds until one reads clean or the cap passes: how many ran, and the last one's read.
    def roundsUntilClean(): (Int, (Long, Long, Long)) = {
      val deadline = System.nanoTime() + cap.toNanos
      var rounds = 0
      var last = (0L, 0L, 0L)
      while (rounds == 0 || (last._3 != 0L && System.nanoTime() < deadline)) {
        last = round()
        rounds += 1
        assert(last._1 > 0L && last._2 === 0L, last)
      }
      (rounds, last)
    }
    // The control's rounds and last read; then the failing round's fallbacks and the steady
    // state's rounds and last read, which there are none of when the control never read clean.
    case class Reads(controlRounds: Int, control: (Long, Long, Long),
        after: Option[(Long, Int, (Long, Long, Long))])
    VarkaEvaluatorBase.allocationSchedule = new VarkaAllocationSampler.Schedule(1, 1)
    // The JIT's own account of the kernel class, for the message of a cancel or a failure.
    val evidence = VarkaJitEvidence.start()
    try {
      varkaSpark.table(view).count()
      val (reads, recorded) =
        VarkaJfrTestSupport.withJfrRecording(classOf[VarkaKernelAllocationEvent]) {
          val (controlRounds, control) = roundsUntilClean()
          if (control._3 != 0L) {
            Reads(controlRounds, control, None)
          } else {
            VarkaColumnarToRowExec.setFailKernelForTesting(true)
            val (_, failed, _) = try round() finally {
              VarkaColumnarToRowExec.setFailKernelForTesting(false)
            }
            val (rounds, last) = roundsUntilClean()
            Reads(controlRounds, control, Some((failed, rounds, last)))
          }
        }
      val samples = recorded
        .filter(VarkaJfrTestSupport.isEvent(_, classOf[VarkaKernelAllocationEvent]))
        .sortBy(_.getStartTime)
      // The kernel's class, from the identity every sample names.
      val shapeHash = samples.headOption.map(_.getString("kernelIdentity"))
        .flatMap("VarkaFusedProjection_(\\w+)\\.java".r.findFirstMatchIn(_)).map(_.group(1))
      // What a reader of a cancel or a failure needs: the last round's samples in bytes a batch
      // (an interpreted loop reads about 120 KB, a compiled one's residue a few KB), and the JIT's
      // account of the class - its compiles by tier, deoptimizations, refused inlining and where
      // the task threads' last allocations came from.
      def account(rounds: Int, last: (Long, Long, Long)): String = {
        val bytes = samples.takeRight(last._1.toInt).map(_.getLong("allocatedBytes"))
        val range = if (bytes.isEmpty) "no samples" else s"${bytes.min} to ${bytes.max} bytes"
        s"the last round read $last after $rounds rounds under a cap of ${cap.toSeconds} s; " +
          s"its samples allocated $range a batch; " +
          shapeHash.map(h => evidence.summary(VarkaShapeCache.classNameFor(h),
            Executor.TASK_THREAD_NAME_PREFIX, Duration.ofSeconds(20)))
            .getOrElse("no sample named the kernel's class")
      }
      reads.after match {
        case None =>
          cancel("the kernel did not read clean before any failure, so this JVM cannot measure " +
            "what the failures leave behind: " + account(reads.controlRounds, reads.control))
        case Some((failed, rounds, last)) =>
          assert(failed > 0L, "the failing round should have taken the kernel-failure catch")
          // Not an assert with the account as its clue: ScalaTest builds a clue before it tests
          // the condition, and the account stops and reads the recording.
          if (last._3 != 0L) {
            fail(s"no clean round after the failures, against a control that read clean after " +
              s"${reads.controlRounds} rounds: " + account(rounds, last))
          }
          logInfo(s"steady state clean after $rounds rounds, $failed failed batches before, " +
            s"the control clean after ${reads.controlRounds} rounds")
      }
    } finally {
      evidence.close()
      VarkaEvaluatorBase.allocationSchedule = VarkaAllocationSampler.Schedule.DEFAULT
      varkaSpark.catalog.uncacheTable(view)
    }
  }

  test("the record renders a field it cannot compute as unavailable, and never throws") {
    val hostile = new RuntimeException("hidden") {
      override def toString: String = throw new IllegalStateException("toString failed")
    }
    val ctx = new VarkaErrorContext("kernel-failure", null,
      null, VarkaErrorContext.safely(() => throw new ArithmeticException("no options")),
      new VarkaErrorContext.Batch(3, java.util.List.of(Int.box(1))), hostile)
    val block = ctx.render()
    assert(block.contains("  identity: unavailable\n"), block)
    assert(block.contains("  shape hash: none\n"), block)
    assert(block.contains("  options: unavailable (java.lang.ArithmeticException)\n"), block)
    assert(block.contains("  batch: 3 rows, nulls per input [1]\n"), block)
    assert(block.contains("  cause: unavailable (java.lang.IllegalStateException)"), block)
    assert(VarkaErrorContext.renderOptions(VarkaEmitOptions.DEFAULTS) === "")
    val error = VarkaErrors.illegalState("an invariant", ctx)
    assert(error.getCondition === "INTERNAL_ERROR")
    assert(error.getCause eq hostile)
    assert(error.getMessageParameters.get("message").startsWith("an invariant\nVarka context:"))
    // A string literal of the user's, rendered into the record, may hold a placeholder; Spark's
    // template filling must neither replace it nor loop on it.
    val literal = VarkaErrors.illegalArgument("concat(s, '${message}')", null)
    assert(literal.getMessage.contains("concat(s, '${message}')"), literal.getMessage)
  }
}
