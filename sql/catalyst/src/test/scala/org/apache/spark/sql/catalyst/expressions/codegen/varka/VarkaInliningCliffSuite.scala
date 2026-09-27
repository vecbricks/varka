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
 * The machinery of task 209's admission check, kept working: [[VarkaInliningCliffProbe]] forks,
 * prints its markers under the flags `dev/varka_inlining_cliff.sh` passes, and
 * `dev/varka_inlining_cliff.py` reads one fork into one row of its table. The census itself -
 * twenty forks per case, the sweep over output counts - is the script's, run when the machine is
 * free (`PLAN_TASK_209.md` 2); this suite runs one short fork and asserts nothing about which
 * side of the cliff it landed on.
 */
class VarkaInliningCliffSuite extends SparkFunSuite {

  private def testClasspath: String =
    Option(System.getenv("SPARK_DIST_CLASSPATH")).filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))

  private def fork(outputs: Int, ceiling: Int, seconds: Int): Seq[String] = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val pattern = s"${VarkaInliningCliffProbe.CLASS_PREFIX}*::*"
    val command = new java.util.ArrayList[String]()
    Seq(javaBin.getAbsolutePath, "--add-modules", "jdk.incubator.vector",
      "--enable-native-access=ALL-UNNAMED", "-Xmx1g", "-XX:+UnlockDiagnosticVMOptions",
      "-XX:CompileCommand=quiet", s"-XX:CompileCommand=PrintInlining,$pattern",
      s"-XX:CompileCommand=PrintIntrinsics,$pattern", "-cp", testClasspath,
      VarkaInliningCliffProbe.getClass.getName.stripSuffix("$"),
      outputs.toString, ceiling.toString, seconds.toString).foreach(command.add)
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
      lines.map(_.trim).filter(_.startsWith(prefix)).map(_.stripPrefix(prefix))
    val begin = marked(VarkaInliningCliffProbe.BEGIN_PREFIX)
    assert(begin.size == 1 && begin.head.contains("outputs=16 ceiling=400 c1=on xbatch=off"),
      begin)
    val methods = marked(VarkaInliningCliffProbe.METHODS_PREFIX)
    assert(methods.size == 1, methods)
    // One group of sixteen tails: a dense and a masked loop, each with vector call sites.
    val loops = methods.head.split(",").map(_.split(":")).map(f => (f(0), f(1).toInt, f(2).toInt))
    assert(loops.map(_._1).toSet == Set("loopDense0", "loopMasked0"), loops.toSeq)
    assert(loops.forall { case (_, bytes, sites) => bytes > 0 && sites > 0 }, loops.toSeq)
    assert(marked(VarkaInliningCliffProbe.RATE_PREFIX).size >= 2, lines.takeRight(10))
    assert(marked(VarkaInliningCliffProbe.ALLOC_PREFIX).size == 1)
    assert(marked(VarkaInliningCliffProbe.DONE_PREFIX).exists(_.endsWith("status=0")),
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
}
