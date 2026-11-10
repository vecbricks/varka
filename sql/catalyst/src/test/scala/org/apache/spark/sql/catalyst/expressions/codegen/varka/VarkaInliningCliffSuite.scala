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

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix.PinsDefaults

/**
 * The machinery of VARKA-209's admission check, kept working: [[VarkaInliningCliffProbe]] forks,
 * prints its markers under the flags `dev/varka_inlining_cliff.sh` passes, and
 * `dev/varka_inlining_cliff.py` reads one fork into one row of its table. The census itself -
 * twenty forks per case, the sweep over output counts - is the script's, run when the machine is
 * free (`VARKA-209.md` 2); this suite runs one short fork and asserts nothing about which
 * side of the cliff it landed on.
 *
 * The probe measures C2's inlining of loops it emits at `VarkaEmitOptions.DEFAULTS`, and its fork
 * gets neither the option matrix's configuration nor this JVM's vector flags, so no configuration
 * gives it anything new: both tests are tagged `VarkaMatrix.PinsDefaults`.
 */
class VarkaInliningCliffSuite extends SparkFunSuite with VarkaTestWatchdog {

  /**
   * Two children per test - a probe and the reader of its log, or two probes - so a test's
   * children at their budgets, 270 s each at the watchdog's ten minutes, end before its cap.
   */
  private def childBudgetSeconds: Long = VarkaChildProcess.budgetSeconds(watchdogMinutes, 2)

  /** One fork of the probe; `budget` is the emitter's call-site budget, the default when
   *  absent, which reaches the probe as its last positional argument behind the others. */
  private def fork(outputs: Int, ceiling: Int, seconds: Int,
      budget: Option[Int] = None): Seq[String] = {
    val pattern = s"${VarkaInliningCliffProbe.CLASS_PREFIX}*::*"
    val command = VarkaChildProcess.javaCommand(
      java.util.List.of("-Xmx1g", "-XX:+UnlockDiagnosticVMOptions", "-XX:CompileCommand=quiet",
        s"-XX:CompileCommand=PrintInlining,$pattern",
        s"-XX:CompileCommand=PrintIntrinsics,$pattern"),
      classOf[VarkaInliningCliffProbe].getName,
      (Seq(outputs.toString, ceiling.toString, seconds.toString) ++
        budget.toSeq.flatMap(b => Seq("1024", "c1on", "cheap", "none", b.toString))).asJava)
    val what = s"$suiteName's probe of $outputs outputs at ceiling $ceiling" +
      budget.map(b => s" and call-site budget $b").getOrElse("")
    val lines = mutable.ArrayBuffer.empty[String]
    VarkaChildProcess.run(what, new ProcessBuilder(command), childBudgetSeconds, lines += _)
      .requireSuccess(true)
    lines.toSeq
  }

  test("the probe prints its markers, and the reader makes one row of them", PinsDefaults) {
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
      val read = mutable.ArrayBuffer.empty[String]
      VarkaChildProcess.run(s"$suiteName's varka_inlining_cliff.py over the probe's log",
        new ProcessBuilder("python3", reader.toString, log.toString), childBudgetSeconds,
        read += _).requireSuccess(true)
      val output = read.mkString("\n")
      assert(output.contains("16 outputs, ceiling 400, c1 on, xbatch off"), output)
      assert(output.contains("loopDense0=") && !output.contains("unfinished  "), output)
    } finally {
      Files.deleteIfExists(log)
    }
  }

  test("under the call-site budget the probe forks twenty-two tails as two loop methods, and " +
      "with the budget off as the one method past C1 the census measured (VARKA-209)",
      PinsDefaults) {
    // The census of VARKA-209.md 10.1 put C1's limit between 93 and 99 vector call sites,
    // twenty and twenty-two cheap tails in one loop method. The probe's default arm is the
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
    // The census counted 99; since VARKA-379 the tails' date column carries the column bound's
    // guard, still past the limit the census found: its min and max in the loop, the two
    // broadcasts that start them and the two compares after the loop make it 105.
    assert(loops(whole) == Map("loopDense0" -> 105, "loopMasked0" -> 105), loops(whole))
  }
}
