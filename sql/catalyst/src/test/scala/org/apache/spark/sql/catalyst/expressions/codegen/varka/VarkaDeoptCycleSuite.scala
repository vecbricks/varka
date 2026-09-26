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
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import org.apache.spark.SparkFunSuite

/**
 * The rule `dev/varka_deopt_cycle.py` judges a fork by, held against recorded JVM logs.
 *
 * The nightly's guard against the C2 deoptimization cycle (`PLAN_TASK_189.md` 3.3) is only as
 * good as that parser: a change to its rule, or to the format HotSpot prints compiles and traps
 * in, could make it read nothing and pass every night. Each fixture under
 * `src/test/resources/varka/deopt-cycle/` is recorded from real forks of `VarkaDeoptCycleProbe`
 * at 128 bits, trimmed to the probe's and the kernel class's lines:
 *  - `cycle.log`, the legacy single-epilogue form at twelve outputs, whose second loop method
 *    is made not entrant and recompiled over and over, trapping at the loop's back edge;
 *  - `once.log`, the default per-group form at the same width, whose loop methods trap at most
 *    four times at their loop heads and then run;
 *  - `head-traps.log`, two consecutive forks of the default form at sixty outputs, where a loop
 *    method sees a third standard tier-4 compile while its callers warm up - the pattern the
 *    parser's first rule misread as the cycle - and the first fork's JVM prints compiles after
 *    its DONE line, while it shuts down.
 */
class VarkaDeoptCycleSuite extends SparkFunSuite {

  private def script: Path = getWorkspaceFilePath("dev", "varka_deopt_cycle.py")

  private def fixture(name: String): Path =
    getWorkspaceFilePath("sql", "catalyst", "src", "test", "resources", "varka", "deopt-cycle",
      name)

  /** The parser's exit status and output on `logs`, run as the nightly runs it. */
  private def parse(logs: Seq[Path], failOnCycle: Boolean = true): (Int, String) = {
    val command = new java.util.ArrayList[String]()
    command.add("python3")
    command.add(script.toString)
    if (failOnCycle) command.add("--fail-on-cycle")
    logs.foreach(l => command.add(l.toString))
    val process = new ProcessBuilder(command).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    assert(process.waitFor(60, TimeUnit.SECONDS), "the parser did not finish")
    (process.exitValue(), output)
  }

  private def verdict(output: String): String =
    output.linesIterator.find(_.startsWith("verdict: ")).getOrElse(fail(output))

  test("a fork in the cycle is judged CYCLE and fails the guard") {
    val (status, output) = parse(Seq(fixture("cycle.log")))
    assert(verdict(output) == "verdict: 1 of 1 forks in the cycle", output)
    assert(status == 1, output)
    // The census reads the same log without failing: the flag is what makes it a guard.
    assert(parse(Seq(fixture("cycle.log")), failOnCycle = false)._1 == 0)
  }

  test("a fork that compiles once passes the guard") {
    val (status, output) = parse(Seq(fixture("once.log")))
    assert(verdict(output) == "verdict: 0 of 1 forks in the cycle", output)
    assert(status == 0, output)
  }

  test("a third compile after head traps alone is not the cycle") {
    val (status, output) = parse(Seq(fixture("head-traps.log")))
    assert(verdict(output) == "verdict: 0 of 2 forks in the cycle", output)
    assert(status == 0, output)
  }

  test("compiles a JVM prints after its DONE line count toward that fork, not the next") {
    // Every fork of a case prints the same class name, so only the fork boundary tells whose
    // compile a line is. The first fork's fourth tier-4 compile of loopDense3 is printed after
    // its DONE line, while its JVM shuts down.
    val (_, output) = parse(Seq(fixture("head-traps.log")))
    val forkLines = output.linesIterator.filter(_.startsWith("VarkaDeoptProbe_60")).toSeq
    assert(forkLines.size == 2, output)
    assert(forkLines(0).contains("loopDense3 4(1)/1/4[227]"), output)
    assert(forkLines(1).contains("loopDense3 3(1)/1/4[227]"), output)
  }

  test("the verdict counts forks across logs") {
    val (status, output) = parse(Seq(fixture("once.log"), fixture("cycle.log")))
    assert(verdict(output) == "verdict: 1 of 2 forks in the cycle", output)
    assert(status == 1, output)
  }

  test("a log with no fork in it fails the guard") {
    withTempDir { dir =>
      val empty = dir.toPath.resolve("width-16.log")
      Files.writeString(empty, "[error] the build failed before any fork ran\n")
      val (status, output) = parse(Seq(empty))
      assert(verdict(output) == "verdict: 0 of 0 forks in the cycle", output)
      assert(status == 1, output)
    }
  }

  test("a fork that did not finish fails the guard") {
    withTempDir { dir =>
      val lines = Files.readAllLines(fixture("once.log"), StandardCharsets.UTF_8)
      val cut = dir.toPath.resolve("width-16.log")
      val kept = new java.util.ArrayList[String]()
      lines.forEach { l => if (!l.contains("VARKA_DEOPT_DONE=")) kept.add(l) }
      Files.write(cut, kept, StandardCharsets.UTF_8)
      val (status, output) = parse(Seq(cut))
      assert(verdict(output) == "verdict: 0 of 1 forks in the cycle, 1 unfinished", output)
      assert(status == 1, output)
    }
  }
}
