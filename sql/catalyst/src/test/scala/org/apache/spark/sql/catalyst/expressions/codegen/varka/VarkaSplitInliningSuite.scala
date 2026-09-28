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

import org.apache.spark.SparkFunSuite

/**
 * What splitting generated code into methods costs vanilla Spark once the methods are many,
 * asserted from HotSpot's own inlining log rather than read from a timing. Spark keeps every
 * generated method under the 8000 bytes HotSpot compiles by splitting large expressions into
 * methods and calling them in sequence. Each of those methods compiles, but C2 inlines callees
 * into their caller only until the caller's inlined code reaches `DesiredMethodLimit` - 8000
 * bytes of bytecode, a separate budget from the 8000-byte `HugeMethodLimit` - and refuses the
 * rest. So past a few thousand bytes of split code the calls stay calls: the code is compiled,
 * but each row pays a real call per method, and C2 cannot optimise across them.
 *
 * Each test forks a JVM ([[VarkaSplitInliningProbe]]) that runs one `CASE WHEN` projection
 * outside a whole-stage codegen stage under `-Xbatch -XX:+PrintCompilation` and `PrintInlining`
 * for the generated class, and reads which of the split `caseWhen_*` methods C2 inlined into
 * their caller. The post that quotes this is `PLAN_TASK_181.md` 3.4.
 */
class VarkaSplitInliningSuite extends SparkFunSuite {

  private val childTimeoutSeconds = 300L

  /** A C2 compile of a method of the generated projection, and its split callees' decisions. */
  private case class Compile(method: String, callees: Seq[(String, String)])

  private case class Run(compiles: Seq[Compile], tail: String) {
    /** The C2 compile that decided about the split methods: the one with split callees. */
    def caller: Compile = {
      val callers = compiles.filter(_.callees.nonEmpty)
      assert(callers.size == 1, s"expected one C2 caller of the split methods, got " +
        s"${callers.map(_.method)}; the probe's last lines:\n$tail")
      callers.head
    }
    def compiledAlone: Set[String] = compiles.map(_.method).toSet
  }

  private def testClasspath: String =
    Option(System.getenv("SPARK_DIST_CLASSPATH")).filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))

  // `PrintCompilation`: timestamp, compile id, attribute flags, the tier, `class::method` and
  // the size. A line saying a compile was discarded ("made not entrant") has the same shape.
  private val header = """\d+\s+\d+\s+[%sbn!\s]*?([0-4])\s+(\S+)::(\S+) \(\d+ bytes\)""".r
  // `PrintInlining`: one line per call site of the compiled method, `@ bci class::method`, the
  // callee's size and the decision.
  private val callee = """@ \d+\s+(\S+)::(caseWhen_\d+_\d+\$) \(\d+ bytes\)\s+(.*)$""".r

  private def runProbe(branches: Int): Run = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val pattern = VarkaSplitInliningProbe.PROJECTION_CLASS + "::*"
    val command = new java.util.ArrayList[String]()
    Seq(javaBin.getAbsolutePath, "--add-modules", "jdk.incubator.vector",
      "--enable-native-access=ALL-UNNAMED", "-Xmx1g", "-Xbatch", "-XX:+PrintCompilation",
      "-XX:+UnlockDiagnosticVMOptions", "-XX:CompileCommand=quiet",
      s"-XX:CompileCommand=PrintInlining,$pattern", "-cp", testClasspath,
      VarkaSplitInliningProbe.getClass.getName.stripSuffix("$"), branches.toString)
      .foreach(command.add)
    val process = new ProcessBuilder(command).redirectErrorStream(true).start()
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val compiles = mutable.ArrayBuffer.empty[Compile]
    var current: Option[(String, mutable.ArrayBuffer[(String, String)])] = None
    def close(): Unit = {
      current.foreach { case (m, cs) => compiles += Compile(m, cs.toSeq) }
      current = None
    }
    val tail = mutable.Queue.empty[String]
    var done = false
    try {
      var line = reader.readLine()
      while (line != null) {
        tail.enqueue(line)
        if (tail.size > 40) tail.dequeue()
        // The marker may share a line with a compile record (`VarkaProbeOutput`).
        if (VarkaProbeOutput.has(line, VarkaSplitInliningProbe.DONE)) {
          done = true
        }
        if (!line.contains("made not entrant") && !line.contains("made zombie")) {
          header.findFirstMatchIn(line) match {
            case Some(h) =>
              close()
              // Only C2's compiles of the generated class, and not its on-stack replacements.
              if (h.group(1) == "4" && h.group(2) == VarkaSplitInliningProbe.PROJECTION_CLASS &&
                  !line.contains("%")) {
                current = Some((h.group(3), mutable.ArrayBuffer.empty))
              }
            case None =>
              for ((_, cs) <- current; c <- callee.findFirstMatchIn(line)
                  if c.group(1) == VarkaSplitInliningProbe.PROJECTION_CLASS) {
                cs += ((c.group(2), c.group(3).trim))
              }
          }
        }
        line = reader.readLine()
      }
      close()
    } finally {
      reader.close()
    }
    if (!process.waitFor(childTimeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      fail(s"the probe did not finish within $childTimeoutSeconds s")
    }
    assert(process.exitValue() == 0 && done,
      s"the probe failed (exit ${process.exitValue()}); its last lines:\n" + tail.mkString("\n"))
    Run(compiles.toSeq, tail.mkString("\n"))
  }

  private val overBudget = "size > DesiredMethodLimit"

  test("a few split methods are inlined into their caller") {
    // Sixteen branches split into six methods, which `apply` calls directly. C2 inlines the hot
    // ones; none is refused for the caller's size.
    val caller = runProbe(16).caller
    val inlined = caller.callees.filter(_._2.startsWith("inline"))
    assert(inlined.size >= 4, caller)
    assert(!caller.callees.exists(_._2.contains(overBudget)), caller)
  }

  test("past C2's inlining budget the split methods compile but are called, not inlined") {
    // Three hundred branches split into about a hundred methods, which one grouping method
    // calls. C2 inlines the first few into it and refuses the rest because the caller's inlined
    // code has reached `DesiredMethodLimit`; each refused method is compiled by C2 on its own,
    // so the code is compiled and every row still makes the calls.
    val run = runProbe(300)
    val caller = run.caller
    val inlined = caller.callees.filter(_._2.startsWith("inline")).map(_._1)
    val refused = caller.callees.filter(_._2.contains(overBudget)).map(_._1)
    assert(inlined.nonEmpty, caller)
    assert(refused.size > 4 * inlined.size, s"inlined ${inlined.size}, refused for the " +
      s"budget ${refused.size}: $caller")
    val notCompiled = refused.toSet -- run.compiledAlone
    assert(notCompiled.isEmpty, s"refused methods never compiled by C2: $notCompiled")
  }
}
