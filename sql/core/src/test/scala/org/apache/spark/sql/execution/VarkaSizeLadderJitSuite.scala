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

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.launcher.JavaModuleOptions
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaChildProcess, VarkaProbeOutput, VarkaTestWatchdog}
import org.apache.spark.sql.execution.VarkaSizeLadderJitProbe._

/**
 * The vanilla step of the size ladder (VARKA-171), asserted from the JVM rather than read from a
 * timing. Spark generates a projection's work into one method of the stage's class - the
 * operator's consume function, `project_doConsume_0$`, which `spark.sql.codegen
 * .splitConsumeFuncByOperator` (on by default) splits out of `processNext`, leaving that a small
 * loop that calls it - and past HotSpot's `HugeMethodLimit` - 8000 bytes, which no product build
 * can raise - `DontCompileHugeMethods` refuses to compile that method at any tier: the
 * projection runs in the interpreter inside a compiled loop. Spark notices, logs one INFO line
 * naming the method and its size (`VarkaCodegenCliffLogSuite` pins it), and runs the method
 * anyway; the `hugeMethodLimit` fallback that could act on it defaults to 65535 bytes, which the
 * ladder's range never reaches (`VARKA-171.md` 2.2, `VARKA-188.md` 5).
 *
 * Each test forks a JVM ([[VarkaSizeLadderJitProbe]]) under `-Xbatch -XX:+PrintCompilation`,
 * runs one ladder rung on stock Spark, and reads the tier HotSpot printed for the projection's
 * consume method: compiled below the limit, never compiled above it. The method's size, from
 * Spark's own compile of the stage, is asserted beside the verdict, so a change in Spark's code
 * generation that moved the rung across the limit fails here by name rather than by a flipped
 * verdict. The same shape as `VarkaHugeMethodSuite`, which pins the limit on Varka's side.
 */
class VarkaSizeLadderJitSuite extends SparkFunSuite with VarkaTestWatchdog {

  /** One child per test, so a test's child at its budget ends before the test's cap. */
  private def childBudgetSeconds: Long = VarkaChildProcess.budgetSeconds(watchdogMinutes, 1)

  // The `$` is part of the name Spark generates; a pattern without it matched nothing at all
  // and read, below the limit, exactly like the method it is meant to find missing above it.
  private val method = "GeneratedIteratorForCodegenStage1::project_doConsume_0$"

  private case class Run(bytes: Int, tiers: Seq[Int], tail: String)

  private def runProbe(entries: Int): Run = {
    // The module options Spark's launcher gives every Spark JVM: a session needs them opened.
    val command = VarkaChildProcess.javaCommand(
      (JavaModuleOptions.defaultModuleOptionArray().toSeq ++
        Seq("-Xmx1g", "-Xbatch", "-XX:+PrintCompilation")).asJava,
      VarkaSizeLadderJitProbe.getClass.getName.stripSuffix("$"),
      java.util.List.of(entries.toString))
    val tiers = mutable.ArrayBuffer.empty[Int]
    var bytes = -1
    var done = false
    val what = s"$suiteName's probe of a $entries-entry rung"
    val builder = new ProcessBuilder(command)
    val child = VarkaChildProcess.run(what, builder, childBudgetSeconds, { line =>
      // A marker can share a line with a compile record a compiler thread wrote, on either
      // side, so each is looked for anywhere in the line, and the line is read for a compile
      // record as well (`VarkaProbeOutput`).
      VarkaProbeOutput.longAfter(line, BYTES_PREFIX).foreach(b => bytes = b.toInt)
      if (VarkaProbeOutput.has(line, DONE)) {
        done = true
      }
      val trimmed = line.trim
      if (trimmed.contains(method)) {
        // `PrintCompilation`: timestamp, compile id, attribute flags (`%` for an on-stack
        // replacement), the tier, then `class::method`. The tier is the token before the
        // method, whatever the flags in front of it.
        val tokens = trimmed.split("\\s+")
        val at = tokens.indexWhere(_.contains(method))
        if (at > 0 && tokens(at - 1).forall(_.isDigit)) {
          tiers += tokens(at - 1).toInt
        }
      }
    })
    child.requireSuccess(done && bytes > 0)
    Run(bytes, tiers.toSeq, child.lastLines())
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
