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

package org.apache.spark.sql.catalyst.expressions.codegen.varka

import java.io.{BufferedReader, File, InputStreamReader}
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import jdk.incubator.vector.IntVector

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSanitizedWarmupProbe._

/**
 * A kernel warmed by [[VarkaKernelWarmup]] in a sanitized run is not thrown away by its first
 * sanitized batch (`VARKA-401.md`). [[VarkaMemorySanitizer]]'s check reads its thread's window
 * and tests it against null; a warm-up whose thread had no window made C2 compile every inlined
 * check with the window always null, and the first call in a window deoptimized the warmed code
 * (a `null_assert_or_unreached0` trap at the check, which `-Xlog:deoptimization=debug` prints).
 *
 * The test runs the warm-up and a window's calls in a JVM of its own through
 * [[VarkaSanitizedWarmupProbe]], with the sanitizer on whatever this JVM has: the verdict is a JIT
 * outcome that the shared test JVM cannot promise, and the sanitizer is the probe's subject.
 */
class VarkaSanitizedWarmupSuite extends SparkFunSuite with VarkaTestWatchdog {

  private val probeBudgetSeconds = 420L

  /** The probe's output lines. */
  private def runProbe(): Seq[String] = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val vectorFlags = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toSeq
      .filter(a => Seq("-XX:MaxVectorSize=", "-XX:UseAVX=", "-XX:UseSVE=").exists(a.startsWith))
    val config = VarkaMatrix.config
    val matrixConfig = if (config.isEmpty) Nil else Seq(s"-D${VarkaMatrix.PROPERTY}=$config")
    val classpath = Option(System.getenv("SPARK_DIST_CLASSPATH")).filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))
    val command = Seq(javaBin.getAbsolutePath, "--add-modules", "jdk.incubator.vector",
      "--enable-native-access=ALL-UNNAMED", "-Xmx1g", "-Dvarka.warmup.deadlineSeconds=120",
      s"-D${VarkaMemorySanitizer.PROPERTY}=true", "-Xlog:deoptimization=debug") ++ vectorFlags ++
      matrixConfig ++ Seq("-cp", classpath, classOf[VarkaSanitizedWarmupProbe].getName)
    val process = new ProcessBuilder(command.asJava).redirectErrorStream(true).start()
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val lines = mutable.ArrayBuffer.empty[String]
    val killer = new Thread(() => {
      if (!process.waitFor(probeBudgetSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
    })
    killer.setDaemon(true)
    killer.start()
    try {
      var line = reader.readLine()
      while (line != null) {
        lines += line
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }
    assert(process.waitFor(probeBudgetSeconds, TimeUnit.SECONDS), "the probe did not finish")
    assert(process.exitValue() == 0, s"the probe failed:\n${lines.takeRight(40).mkString("\n")}")
    lines.toSeq
  }

  private def marked(lines: Seq[String], prefix: String): Option[String] =
    lines.find(_.startsWith(prefix)).map(_.stripPrefix(prefix))

  test("a kernel warmed in a sanitized run keeps its code through its first sanitized calls") {
    val lanes = VarkaMatrix.base.lanesOverride()
    assume(lanes == 0 || lanes == IntVector.SPECIES_PREFERRED.length(),
      s"a kernel at $lanes int lanes is a second species beside this JVM's preferred one")
    val lines = runProbe()
    // The warm-up's own mappings are checked now; an over-mapping would end its warm-up with a
    // logged failure and no verdict, which the assumption below would turn into a cancel.
    val failed = lines.filter(l => l.contains("VarkaMemoryViolation") ||
      l.contains("failed during its warm-up"))
    assert(failed.isEmpty, s"the warm-up failed:\n${failed.take(3).mkString("\n")}")
    val state = marked(lines, STATE_PREFIX).getOrElse(fail("the probe printed no state"))
    // A warm-up that did not reach its verdict (a loaded machine, a JVM that cannot warm) left no
    // warmed code to keep, so there is nothing to compare.
    assume(state.contains("state=COMPILED"), s"the warm-up did not compile the kernel: $state")
    val from = lines.indexOf(BEFORE)
    val to = lines.indexOf(AFTER)
    assert(from >= 0 && to > from, lines.takeRight(40).mkString("\n"))
    val checked = marked(lines, CHECKED_PREFIX).getOrElse(fail("the probe counted no checks"))
    assert(checked.toLong > 0, "the sanitizer checked nothing: it did not reach the kernel")
    val traps = lines.slice(from, to).filter { l =>
      l.contains("VarkaMemorySanitizer.check") && l.contains("make_not_entrant")
    }
    assert(traps.isEmpty,
      s"${traps.size} compiled methods were made not entrant at the sanitizer's check:\n" +
        traps.take(3).mkString("\n"))
  }
}
