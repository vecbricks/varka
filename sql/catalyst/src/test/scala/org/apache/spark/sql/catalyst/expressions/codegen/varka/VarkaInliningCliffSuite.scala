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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.collection.mutable

import org.apache.spark.SparkFunSuite

/**
 * The machinery of VARKA-209's admission check, kept working: [[VarkaInliningCliffProbe]] forks,
 * prints its markers under the flags `dev/varka_inlining_cliff.sh` passes, and
 * `dev/varka_inlining_cliff.py` reads one fork into one row of its table. The census itself -
 * twenty forks per case, the sweep over output counts - is the script's, run when the machine is
 * free (`VARKA-209.md` 2); this suite runs one short fork and asserts nothing about which
 * side of the cliff it landed on.
 */
class VarkaInliningCliffSuite extends SparkFunSuite with VarkaTestWatchdog {

  private def testClasspath: String =
    Option(System.getenv("SPARK_DIST_CLASSPATH")).filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))

  /** One fork of the probe; `budget` is the emitter's call-site budget, the default when
   *  absent, which reaches the probe as its last positional argument behind the others. */
  private def fork(outputs: Int, ceiling: Int, seconds: Int,
      budget: Option[Int] = None): Seq[String] = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val pattern = s"${VarkaInliningCliffProbe.CLASS_PREFIX}*::*"
    val command = new java.util.ArrayList[String]()
    (Seq(javaBin.getAbsolutePath, "--add-modules", "jdk.incubator.vector",
      "--enable-native-access=ALL-UNNAMED", "-Xmx1g", "-XX:+UnlockDiagnosticVMOptions",
      "-XX:CompileCommand=quiet", s"-XX:CompileCommand=PrintInlining,$pattern",
      s"-XX:CompileCommand=PrintIntrinsics,$pattern", "-cp", testClasspath,
      VarkaInliningCliffProbe.getClass.getName.stripSuffix("$"),
      outputs.toString, ceiling.toString, seconds.toString) ++
      budget.toSeq.flatMap(b => Seq("1024", "c1on", "cheap", "none", b.toString)))
      .foreach(command.add)
    val process = new ProcessBuilder(command).redirectErrorStream(true).start()
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val lines = mutable.ArrayBuffer.empty[String]
    try {
      var line = reader.readLine()
      while (line != null) {
        lines += line
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }
    assert(process.waitFor(120, TimeUnit.SECONDS), "the probe did not finish")
    assert(process.exitValue() == 0, s"the probe failed (exit ${process.exitValue()}):\n" +
      lines.takeRight(40).mkString("\n"))
    lines.toSeq
  }

  test("the probe prints its markers, and the reader makes one row of them") {
    val lines = fork(outputs = 16, ceiling = 400, seconds = 3)
    def marked(prefix: String): Seq[String] =
      lines.flatMap(VarkaProbeOutput.after(_, prefix)).map(_.trim)
    val begin = marked(VarkaInliningCliffProbe.BEGIN_PREFIX)
    assert(begin.size == 1 && begin.head.contains("outputs=16 ceiling=400 c1=on xbatch=off") &&
      begin.head.endsWith(s" budget=${VarkaEmitBudget.CALL_SITE_BUDGET} " +
        s"heavy=${VarkaEmitBudget.HEAVY_GROUP_OUTPUTS}"), begin)
    val methods = marked(VarkaInliningCliffProbe.METHODS_PREFIX)
    assert(methods.size == 1, methods)
    // One group of sixteen tails: a dense and a masked loop, each with vector call sites.
    // The list has no spaces, so a record glued after it ends at the first one.
    val loops = methods.head.takeWhile(!_.isWhitespace).split(",").map(_.split(":"))
      .map(f => (f(0), f(1).toInt, f(2).toInt))
    assert(loops.map(_._1).toSet == Set("loopDense0", "loopMasked0"), loops.toSeq)
    assert(loops.forall { case (_, bytes, sites) => bytes > 0 && sites > 0 }, loops.toSeq)
    assert(marked(VarkaInliningCliffProbe.RATE_PREFIX).size >= 2, lines.takeRight(10))
    assert(marked(VarkaInliningCliffProbe.ALLOC_PREFIX).size == 1)
    assert(marked(VarkaInliningCliffProbe.DONE_PREFIX).exists(_.split("\\s+").contains("status=0")),
      marked(VarkaInliningCliffProbe.DONE_PREFIX))

    val log = Files.createTempFile("varka-cliff", ".log")
    try {
      Files.write(log, lines.mkString("\n").getBytes(StandardCharsets.UTF_8))
      val reader = getWorkspaceFilePath("dev", "varka_inlining_cliff.py")
      val process = new ProcessBuilder("python3", reader.toString, log.toString)
        .redirectErrorStream(true).start()
      val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      assert(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, output)
      assert(output.contains("16 outputs, ceiling 400, c1 on, xbatch off"), output)
      assert(output.contains("loopDense0=") && !output.contains("unfinished  "), output)
    } finally {
      Files.deleteIfExists(log)
    }
  }

  test("under the call-site budget the probe forks twenty-two tails as two loop methods, and " +
      "with the budget off as the one method past C1 the census measured (VARKA-209)") {
    // The census of VARKA-209.md 10.1 put C1's limit between 93 and 99 vector call sites,
    // twenty and twenty-two cheap tails in one loop method. The probe's default arm is now the
    // production emitter, whose call-site budget splits the second shape; the `0` arm is the
    // census's own, kept so the cliff stays reproducible with the budget in place.
    def loops(lines: Seq[String]): Map[String, Int] =
      lines.flatMap(VarkaProbeOutput.after(_, VarkaInliningCliffProbe.METHODS_PREFIX))
        .head.trim.takeWhile(!_.isWhitespace).split(",").map(_.split(":"))
        .map(f => f(0) -> f(2).toInt).toMap
    val split = loops(fork(outputs = 22, ceiling = 400, seconds = 2))
    assert(split.keySet == Set("loopDense0", "loopDense1", "loopMasked0", "loopMasked1"), split)
    assert(split.values.forall(_ <= VarkaEmitBudget.CALL_SITE_BUDGET), split)
    val whole = fork(outputs = 22, ceiling = 400, seconds = 2, budget = Some(0))
    assert(whole.exists(_.contains(" budget=0")), whole.take(3))
    assert(loops(whole) == Map("loopDense0" -> 99, "loopMasked0" -> 99), loops(whole))
  }
}
