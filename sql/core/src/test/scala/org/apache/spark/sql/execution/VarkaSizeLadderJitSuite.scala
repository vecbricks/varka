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

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import scala.collection.mutable

import org.apache.spark.SparkFunSuite
import org.apache.spark.launcher.JavaModuleOptions
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator
import org.apache.spark.sql.execution.VarkaSizeLadderJitProbe._

/**
 * The vanilla step of the size ladder (task 171), asserted from the JVM rather than read from a
 * timing. Spark generates a projection's work into one method of the stage's class - the
 * operator's consume function, `project_doConsume_0$`, which `spark.sql.codegen
 * .splitConsumeFuncByOperator` (on by default) splits out of `processNext`, leaving that a small
 * loop that calls it - and past HotSpot's `HugeMethodLimit` - 8000 bytes, which no product build
 * can raise - `DontCompileHugeMethods` refuses to compile that method at any tier: the
 * projection runs in the interpreter inside a compiled loop, and neither Spark nor the JVM says
 * so. Spark logs a generated method only past 65535 bytes, when it disables
 * whole-stage codegen, which the ladder's range never reaches (`PLAN_TASK_171.md` 2.2).
 *
 * Each test forks a JVM ([[VarkaSizeLadderJitProbe]]) under `-Xbatch -XX:+PrintCompilation`,
 * runs one ladder rung on stock Spark, and reads the tier HotSpot printed for the projection's
 * consume method: compiled below the limit, never compiled above it. The method's size, from
 * Spark's own compile of the stage, is asserted beside the verdict, so a change in Spark's code
 * generation that moved the rung across the limit fails here by name rather than by a flipped
 * verdict. The same shape as `VarkaHugeMethodSuite`, which pins the limit on Varka's side.
 */
class VarkaSizeLadderJitSuite extends SparkFunSuite {

  private val childTimeoutSeconds = 600L

  // The `$` is part of the name Spark generates; a pattern without it matched nothing at all
  // and read, below the limit, exactly like the method it is meant to find missing above it.
  private val method = "GeneratedIteratorForCodegenStage1::project_doConsume_0$"

  private case class Run(bytes: Int, tiers: Seq[Int], tail: String)

  private def testClasspath: String =
    Option(System.getenv("SPARK_DIST_CLASSPATH"))
      .filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))

  private def runProbe(entries: Int): Run = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val command = new java.util.ArrayList[String]()
    command.add(javaBin.getAbsolutePath)
    // The module options Spark's launcher gives every Spark JVM: a session needs them opened.
    JavaModuleOptions.defaultModuleOptionArray().foreach(command.add)
    Seq("--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED",
      "-Xmx1g", "-Xbatch", "-XX:+PrintCompilation", "-cp", testClasspath,
      VarkaSizeLadderJitProbe.getClass.getName.stripSuffix("$"), entries.toString)
      .foreach(command.add)
    val builder = new ProcessBuilder(command)
    builder.redirectErrorStream(true)
    val process = builder.start()
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val tiers = mutable.ArrayBuffer.empty[Int]
    var bytes = -1
    var done = false
    val tail = mutable.Queue.empty[String]
    try {
      var line = reader.readLine()
      while (line != null) {
        tail.enqueue(line)
        if (tail.size > 40) tail.dequeue()
        val trimmed = line.trim
        if (trimmed.startsWith(BYTES_PREFIX)) {
          bytes = trimmed.stripPrefix(BYTES_PREFIX).toInt
        } else if (trimmed == DONE) {
          done = true
        } else if (trimmed.contains(method)) {
          // `PrintCompilation`: timestamp, compile id, attribute flags (`%` for an on-stack
          // replacement), the tier, then `class::method`. The tier is the token before the
          // method, whatever the flags in front of it.
          val tokens = trimmed.split("\\s+")
          val at = tokens.indexWhere(_.contains(method))
          if (at > 0 && tokens(at - 1).forall(_.isDigit)) {
            tiers += tokens(at - 1).toInt
          }
        }
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }
    if (!process.waitFor(childTimeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      fail(s"the probe did not finish within $childTimeoutSeconds s")
    }
    assert(process.exitValue() == 0 && done && bytes > 0,
      s"the probe failed (exit ${process.exitValue()}); its last lines:\n" + tail.mkString("\n"))
    Run(bytes, tiers.toSeq, tail.mkString("\n"))
  }

  private val limit = CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT

  test("below HugeMethodLimit the vanilla projection's consume method is compiled by C2") {
    val run = runProbe(32)
    assert(run.bytes < limit, s"32 entries generate ${run.bytes} bytes; the rung moved")
    assert(run.tiers.contains(4), s"the consume method (${run.bytes} bytes) reached tiers " +
      s"${run.tiers.distinct.sorted.mkString(",")}, not 4")
  }

  test("past HugeMethodLimit the vanilla projection's consume method is never compiled at " +
      "any tier") {
    val run = runProbe(80)
    assert(run.bytes > limit, s"80 entries generate ${run.bytes} bytes; the rung moved")
    assert(run.tiers.isEmpty, s"the consume method (${run.bytes} bytes) was compiled at tiers " +
      s"${run.tiers.distinct.sorted.mkString(",")}")
  }
}
