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
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import org.apache.logging.log4j.Level

import org.apache.spark.SparkException
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaEmitOptions, VarkaErrorContext, VarkaFailureSignature, VarkaFallbackEvent, VarkaMemorySanitizer, VarkaMemoryViolation, VarkaReplay, VarkaReproduction, VarkaTestWatchdog}
import org.apache.spark.sql.internal.SQLConf

/**
 * A Varka kernel failure writes a reproduction file, and the file replays to the failure
 * (VARKA-315). One test per kind the file is written for, each planted through a fault-injector
 * option so that the failure rides the shape key and the file can reproduce it without the test:
 *
 *  - an exception thrown inside a kernel: `misdescribeDataBytes` below zero maps every segment
 *    short, and the vector loop's last load throws `IndexOutOfBoundsException`;
 *  - a class that fails to link at first execution: `misdescribeAdd`, a `NoSuchMethodError`;
 *  - a memory-sanitizer violation: `misdescribeDataBytes` above zero maps past every buffer
 *    (with `-Dvarka.sanitizeMemory=true` only, as `VarkaMemorySanitizerEndToEndSuite`);
 *  - a class the verifier rejects: `dropStackMaps`, which under test fails the admission's
 *    verification at planning;
 *  - an emitter that throws: `misdescribeWordLiveness`, through the emission catch.
 *
 * Each checks the failure is handled exactly as it was without the file - the fallback answers,
 * or the error reaches the test unchanged - and then replays the file through `VarkaReplay`, the
 * code `dev/varka_emit.sh --replay` runs.
 */
class VarkaReproductionEndToEndSuite
    extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

  private val view = "varka_reproduction_dates"
  private val query = s"SELECT date_add(d, 3) AS a FROM $view"

  /**
   * A thousand dates with a null every second row: long enough for the vector loop to run full
   * lane groups at any width, so a short mapping fails in the loop and not only in the epilogue,
   * and dense enough that any batch the option matrix cuts it into longer than one row holds both
   * nulls and values (the tests read the failing batch's validity; a one-row batch is either
   * without nulls or all null, which has no validity buffer).
   */
  private def cacheView(): Unit = {
    for (session <- Seq(spark, varkaSpark)) {
      session.range(0, 1000)
        .selectExpr(s"IF(id % 2 = 1, NULL, date_add(DATE'2000-01-01', CAST(id AS INT))) AS d")
        .createOrReplaceTempView(view)
      session.catalog.cacheTable(view)
    }
  }

  private def withOptions[T](change: VarkaEmitOptions => VarkaEmitOptions)(body: => T): T = {
    val previous = VarkaColumnarToRowExec.currentEmitOptions
    VarkaColumnarToRowExec.setEmitOptionsForTesting(change(previous))
    try body finally VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
  }

  private def withFailureDeclared[T](body: => T): T = {
    VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting(true)
    try body finally VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting(false)
  }

  /**
   * Runs `body`, then reads the one file it wrote; the directory is gone afterwards.
   *
   * The file can land after `body` returns. A query's tasks run side by side and the first to fail
   * writes the file, but a sibling that fails on the same shape finds it claimed, rethrows at
   * once, and the job's failure reaches the test while the first task is still creating its
   * temporary file or moving it into place. So the test waits for a finished file (a name the
   * writer's final move gives, not the dot-named temporary) and then counts them.
   */
  private def reproduced(body: => Unit): (VarkaReproduction, Path) = {
    var read: Option[(VarkaReproduction, Path)] = None
    withTempDir { dir =>
      val target = new File(dir, "repro")
      VarkaReproduction.resetForTesting()
      varkaSpark.conf.set(SQLConf.VARKA_REPRODUCTION_DIRECTORY.key, target.getPath)
      try body finally varkaSpark.conf.unset(SQLConf.VARKA_REPRODUCTION_DIRECTORY.key)
      def finished: Seq[Path] = if (!target.exists()) Seq.empty else {
        val listing = Files.list(target.toPath)
        try listing.iterator().asScala.toSeq.filterNot(_.getFileName.toString.startsWith("."))
        finally listing.close()
      }
      val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
      while (finished.isEmpty && System.nanoTime() < deadline) Thread.sleep(10)
      val files = finished
      assert(files.size === 1, s"expected one reproduction file, got: ${files.mkString(", ")}")
      read = Some((VarkaReproduction.parse(Files.readString(files.head)), files.head.getFileName))
    }
    read.get
  }

  private def causes(e: Throwable): Seq[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(20).toSeq

  private def assertReplays(file: VarkaReproduction): Unit = {
    val replay = VarkaReplay.of(file)
    assert(replay.reproduced(), replay.report().asScala.mkString("\n"))
  }

  test("an exception inside a kernel writes a file with the batch, which replays to it") {
    cacheView()
    val expected = spark.sql(query).collect().toSeq
    val (file, name) = reproduced {
      withOptions(_.withMisdescribeDataBytes(-4)) {
        withFailureDeclared(checkAnswer(varkaSpark.sql(query), expected))
      }
    }
    assert(file.signature().kind() === "IndexOutOfBoundsException", file.signature())
    assert(file.stage() === VarkaFallbackEvent.KERNEL_FAILURE)
    assert(file.options().contains("misdescribeDataBytes=-4"), file.options())
    assert(file.context().contains("kind: kernel-failure"), file.context())
    assert(name.toString.matches(
      "varka-repro-\\w+-indexoutofboundsexception-pid\\d+-[0-9a-f]+\\.txt"), name)
    val call = file.call()
    assert(call != null && call.omitted() == null, call)
    assert(call.inputs().size() === 1)
    val input = call.inputs().get(0)
    assert(input.data().length === call.rows() * 4)
    assert(input.nullCount() > 0 && input.validity() != null, input)
    assert(file.classBytes() != null)
    assertReplays(file)
  }

  test("a filter kernel's exception writes a file with its selection layout, which replays") {
    cacheView()
    val filter = s"SELECT d FROM $view WHERE date_add(d, 3) > DATE'2001-06-01'"
    val expected = spark.sql(filter).collect().toSeq
    val (file, _) = reproduced {
      withOptions(_.withMisdescribeDataBytes(-4)) {
        withFailureDeclared(checkAnswer(varkaSpark.sql(filter), expected))
      }
    }
    assert(file.signature().kind() === "IndexOutOfBoundsException", file.signature())
    assert(file.call() != null && file.call().selectionBitmaps(), file.call())
    assertReplays(file)
  }

  test("a decimal column's short mapping fails in its kernel and writes a file of its slots, " +
      "which replays to it") {
    // A filter over one decimal column: its kernel writes a selection bitmap and maps no data
    // output, so the decimal column's sixteen-byte slots are the only data segment, and a
    // mapping four bytes short can fail only there (VARKA-347).
    val decimals = "varka_reproduction_decimals"
    for (session <- Seq(spark, varkaSpark)) {
      session.range(0, 1000)
        .selectExpr("IF(id % 2 = 1, NULL, CAST(id AS DECIMAL(9, 2))) AS m")
        .createOrReplaceTempView(decimals)
      session.catalog.cacheTable(decimals)
    }
    val filter = s"SELECT m FROM $decimals WHERE m > 400.50"
    val expected = spark.sql(filter).collect().toSeq
    val (file, _) = reproduced {
      withOptions(_.withMisdescribeDataBytes(-4)) {
        withFailureDeclared(checkAnswer(varkaSpark.sql(filter), expected))
      }
    }
    assert(file.signature().kind() === "IndexOutOfBoundsException", file.signature())
    assert(file.options().contains("misdescribeDataBytes=-4"), file.options())
    val call = file.call()
    assert(call != null && call.omitted() == null && call.selectionBitmaps(), call)
    assert(call.inputs().size() === 1)
    assert(call.inputs().get(0).data().length === call.rows() * 16, call.inputs().get(0))
    assertReplays(file)
  }

  test("a kernel that fails to link writes a file that replays to the NoSuchMethodError") {
    cacheView()
    val expected = spark.sql(query).collect().toSeq
    val (file, _) = reproduced {
      withOptions(_.withMisdescribeAdd(true)) {
        withFailureDeclared(checkAnswer(varkaSpark.sql(query), expected))
      }
    }
    assert(file.signature().kind() === "generated class: NoSuchMethodError", file.signature())
    assertReplays(file)
    // The comparison can fail: the same kernel replayed against another signature is reported as
    // not reproduced, and a file whose options were edited no longer names its kernel.
    val other = copy(file, new VarkaFailureSignature("IndexOutOfBoundsException", "x"),
      file.options())
    assert(!VarkaReplay.of(other).reproduced())
    val refused = intercept[IllegalArgumentException](
      VarkaReplay.of(copy(file, file.signature(), "")))
    assert(refused.getMessage.contains("not to its shape hash"), refused.getMessage)
  }

  private def copy(
      file: VarkaReproduction,
      signature: VarkaFailureSignature,
      options: String): VarkaReproduction =
    new VarkaReproduction(signature, file.stage(), file.context(), file.writtenAt(), file.java(),
      file.sanitizer(), file.shapeHash(), file.numInputs(), file.numLiterals(), file.warmed(),
      options, file.outputs(), file.intLiterals(), file.longLiterals(), file.call(),
      file.classBytes())

  test("a memory-sanitizer violation fails the task unchanged and writes a file that replays " +
      "to it") {
    assume(VarkaMemorySanitizer.ENABLED, "run with -Dvarka.sanitizeMemory=true")
    cacheView()
    var thrown: Throwable = null
    val (file, _) = reproduced {
      withOptions(_.withMisdescribeDataBytes(4096)) {
        thrown = intercept[SparkException](varkaSpark.sql(query).collect())
      }
    }
    assert(causes(thrown).exists(_.isInstanceOf[VarkaMemoryViolation]), thrown)
    assert(file.signature().kind() === "memory violation", file.signature())
    assert(file.stage() === VarkaErrorContext.MEMORY_VIOLATION)
    assert(file.sanitizer())
    assertReplays(file)
  }

  test("a class the verifier rejects fails planning unchanged and writes a file that replays " +
      "to the VerifyError") {
    cacheView()
    var thrown: Throwable = null
    val (file, _) = reproduced {
      withOptions(_.withDropStackMaps(true)) {
        thrown = intercept[Throwable](varkaSpark.sql(query).collect())
      }
    }
    assert(causes(thrown).exists(_.isInstanceOf[VerifyError]), thrown)
    assert(file.signature().kind() === "generated class: VerifyError", file.signature())
    assert(file.stage() === VarkaErrorContext.PLANNING)
    assert(file.call() == null)
    assertReplays(file)
  }

  test("an emitter that throws writes a file from the emission catch, which replays to it") {
    cacheView()
    val expected = spark.sql(query).collect().toSeq
    val (file, _) = reproduced {
      withOptions(_.toBuilder().validityByBitmap(true).misdescribeWordLiveness(true).build()) {
        withFailureDeclared(checkAnswer(varkaSpark.sql(query), expected))
      }
    }
    assert(file.stage() === VarkaFallbackEvent.EMISSION_FAILURE, file)
    assert(file.call() == null && file.classBytes() == null)
    assertReplays(file)
  }

  test("a file that cannot be written leaves the fallback, its answer and its warning as they " +
      "were") {
    cacheView()
    val expected = spark.sql(query).collect().toSeq
    withTempDir { dir =>
      // A regular file where the directory should be: every write fails.
      val blocker = new File(dir, "not-a-directory")
      Files.writeString(blocker.toPath, "x")
      VarkaReproduction.resetForTesting()
      varkaSpark.conf.set(SQLConf.VARKA_REPRODUCTION_DIRECTORY.key, blocker.getPath)
      val appender = new LogAppender("reproduction warnings")
      appender.setThreshold(Level.WARN)
      try {
        withLogAppender(appender) {
          withOptions(_.withMisdescribeAdd(true)) {
            withFailureDeclared(checkAnswer(varkaSpark.sql(query), expected))
          }
        }
      } finally {
        varkaSpark.conf.unset(SQLConf.VARKA_REPRODUCTION_DIRECTORY.key)
      }
      val messages = appender.loggingEvents.map(_.getMessage.getFormattedMessage)
      assert(messages.exists(_.contains("failed on this batch")), messages.mkString("\n"))
      assert(messages.exists(_.contains("Could not write a reproduction")),
        messages.mkString("\n"))
    }
  }

  test("disabled, nothing is written; enabled, one file per shape and kind however many " +
      "batches fail") {
    cacheView()
    val expected = spark.sql(query).collect().toSeq
    withTempDir { dir =>
      val target = new File(dir, "repro")
      VarkaReproduction.resetForTesting()
      varkaSpark.conf.set(SQLConf.VARKA_REPRODUCTION_DIRECTORY.key, target.getPath)
      try {
        withOptions(_.withMisdescribeAdd(true)) {
          withFailureDeclared {
            withSQLConf(SQLConf.VARKA_REPRODUCTION_ENABLED.key -> "false") {
              varkaSpark.conf.set(SQLConf.VARKA_REPRODUCTION_ENABLED.key, "false")
              try checkAnswer(varkaSpark.sql(query), expected)
              finally varkaSpark.conf.unset(SQLConf.VARKA_REPRODUCTION_ENABLED.key)
            }
            assert(!target.exists() || target.list().isEmpty)
            checkAnswer(varkaSpark.sql(query), expected)
            checkAnswer(varkaSpark.sql(query), expected)
          }
        }
      } finally {
        varkaSpark.conf.unset(SQLConf.VARKA_REPRODUCTION_DIRECTORY.key)
      }
      assert(target.list().length === 1, target.list().mkString(", "))
    }
  }

  test("a batch over the byte cap keeps its shape and replays on a synthetic batch") {
    cacheView()
    val expected = spark.sql(query).collect().toSeq
    varkaSpark.conf.set(SQLConf.VARKA_REPRODUCTION_MAX_BATCH_BYTES.key, "0")
    val (file, _) = try {
      reproduced {
        withOptions(_.withMisdescribeDataBytes(-4)) {
          withFailureDeclared(checkAnswer(varkaSpark.sql(query), expected))
        }
      }
    } finally {
      varkaSpark.conf.unset(SQLConf.VARKA_REPRODUCTION_MAX_BATCH_BYTES.key)
    }
    val call = file.call()
    assert(call.omitted() != null && call.omitted().contains("over the limit of 0"), call)
    assert(call.inputs().asScala.forall(i => i.data() == null && i.validity() == null))
    assert(call.inputs().get(0).nullCount() > 0)
    assertReplays(file)
  }
}
