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
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaHugeMethodProbe._

/**
 * The property task 87 exists for, asserted from the JVM rather than inferred from a byte count:
 * under the byte budget every loop and epilogue method of a kernel past the legacy form's
 * crossing is compiled by C2, and in the legacy form the single epilogue is not compiled at all.
 *
 * HotSpot's `DontCompileHugeMethods` refuses any method whose bytecode exceeds `HugeMethodLimit`
 * (8000 bytes, a develop flag no product build can raise) at every tier, silently: the method
 * runs in the interpreter for the life of the JVM and nothing prints. The legacy emitter's
 * single epilogue crosses that limit at thirteen `make_date` outputs (`PLAN_TASK_87.md` 2.2),
 * so a sixteen-output ladder is the shape on which the two forms differ. Each test forks a JVM
 * ([[VarkaHugeMethodProbe]]) under `-Xbatch -XX:+PrintCompilation`, runs the ladder hot, and
 * reads the tiers HotSpot printed for the emitted class's methods.
 */
class VarkaHugeMethodSuite extends SparkFunSuite {

  private val outputs = 16

  private val childTimeoutSeconds = 300L

  /** Tier by method: the highest tier HotSpot printed a compilation of that method at. */
  private case class Compiled(tier: Map[String, Int], names: Seq[String])

  private def testClasspath: String =
    Option(System.getenv("SPARK_DIST_CLASSPATH"))
      .filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))

  private def runProbe(methodByteBudget: Int): Compiled = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val command = new java.util.ArrayList[String]()
    command.add(javaBin.getAbsolutePath)
    command.add("--add-modules")
    command.add("jdk.incubator.vector")
    command.add("--enable-native-access=ALL-UNNAMED")
    command.add("-Xbatch")
    command.add("-XX:+PrintCompilation")
    command.add("-cp")
    command.add(testClasspath)
    command.add(VarkaHugeMethodProbe.getClass.getName.stripSuffix("$"))
    command.add(outputs.toString)
    command.add(methodByteBudget.toString)
    val builder = new ProcessBuilder(command)
    builder.redirectErrorStream(true)
    val process = builder.start()
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val tiers = mutable.LinkedHashMap.empty[String, Int]
    var done = false
    val tail = mutable.Queue.empty[String]
    val marker = CLASS_NAME + "::"
    try {
      var line = reader.readLine()
      while (line != null) {
        tail.enqueue(line)
        if (tail.size > 40) tail.dequeue()
        if (line.trim == DONE) {
          done = true
        } else if (line.contains(marker)) {
          // `PrintCompilation`: timestamp, compile id, attribute flags, the tier, then
          // `class::method (N bytes)`; a later `made not entrant` line repeats the tier. The
          // tier is the token before the method, whatever the flags in front of it were.
          val tokens = line.trim.split("\\s+")
          val at = tokens.indexWhere(_.startsWith(marker))
          if (at > 0 && tokens(at - 1).forall(_.isDigit)) {
            val method = tokens(at).stripPrefix(marker)
            val tier = tokens(at - 1).toInt
            tiers(method) = math.max(tiers.getOrElse(method, 0), tier)
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
    assert(process.exitValue() == 0 && done,
      s"the probe failed (exit ${process.exitValue()}); its last lines:\n" + tail.mkString("\n"))
    // The methods the class declares, emitted here under the same options, so the assertion
    // below is over the whole layout and not only over the methods HotSpot happened to name.
    val bytes = VarkaLoopEmitter.emit(CLASS_NAME, ladder(outputs).asJava, 1, outputs,
      null, null, VarkaEmitOptions.DEFAULTS.withMethodByteBudget(methodByteBudget))
    val names = VarkaEmitterTestSupport.methodNames(bytes).asScala.toSeq
      .filter(m => m.startsWith("loop") || m.startsWith("epilogue"))
    Compiled(tiers.toMap, names)
  }

  private def render(c: Compiled): String =
    c.names.map(m => s"$m: " + c.tier.get(m).map(t => s"tier $t").getOrElse("never compiled"))
      .mkString("\n")

  test("under the byte budget every loop and epilogue method of a sixteen-output ladder " +
      "reaches tier 4") {
    val c = runProbe(8000)
    val epilogues = c.names.filter(_.startsWith("epilogue"))
    assert(epilogues.size > 2 && epilogues.forall(_.last.isDigit),
      "the epilogue is one method per group under the switch: " + epilogues.mkString(", "))
    val short = c.names.filterNot(m => c.tier.get(m).contains(4))
    assert(short.isEmpty, "not at tier 4:\n" + render(c))
  }

  test("without it the single epilogue of the same ladder is never compiled at any tier") {
    val c = runProbe(0)
    assert(c.names.filter(_.startsWith("epilogue")) === Seq("epilogueDense", "epilogueMasked"),
      c.names.mkString(", "))
    for (m <- Seq("epilogueDense", "epilogueMasked")) {
      assert(!c.tier.contains(m), s"$m was compiled, so this ladder no longer crosses " +
        "HugeMethodLimit and the suite needs a taller one:\n" + render(c))
    }
    val loops = c.names.filter(_.startsWith("loop"))
    assert(loops.forall(m => c.tier.get(m).contains(4)), "a loop method short of tier 4:\n" +
      render(c))
  }
}
