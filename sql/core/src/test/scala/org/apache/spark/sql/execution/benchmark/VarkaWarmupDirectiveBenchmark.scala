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

package org.apache.spark.sql.execution.benchmark

import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.management.ObjectName

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaKernelCompileDirective,
  VarkaKernelWarmup, VarkaShapeCache}

/**
 * Why Varka keeps C1 off its kernel classes (`VarkaKernelCompileDirective`, `PLAN_TASK_212.md`
 * 10): the warm-up's verdict on a 54-entry kernel with the directive and without it, each in
 * fresh JVMs.
 *
 * Every child JVM runs the sequence that exposed the trap: a Varka session with the warm-up on,
 * the ladder's hundred thousand cached rows, then twelve queries of a 16-entry shape and twelve
 * of a 54-entry one, back to back, each rung waiting for its warm-up's verdict. The queries' own
 * compiles keep C2's queue long while the new kernel crosses its first threshold, which is when
 * HotSpot compiles a method at tier 2 instead of tier 3. Without the directive a kernel method
 * compiled that way is stranded on boxing C1 code and the verdict never comes; with it, every
 * kernel method goes from the interpreter to C2. "Without" is the directive the production code
 * installs, overridden by one pushed on top of it that enables C1 again for the same classes, so
 * the production path needs no switch for this.
 *
 * Fresh JVMs because the trap depends on what else the JIT is doing, which one JVM's history
 * would carry from one run into the next. Each child prints one line per rung.
 *
 * To run this benchmark:
 * {{{
 *   dev/varka_bench_regen.sh sql VarkaWarmupDirectiveBenchmark
 * }}}
 */
object VarkaWarmupDirectiveBenchmark extends SqlBasedBenchmark {

  private val smoke = sys.env.get("VARKA_COLDSTART_SMOKE").contains("true")

  /** Fresh JVMs per mode. */
  private val runs = if (smoke) 1 else 3

  private val childTimeoutSeconds = 600L

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    spark.stop()
    runBenchmark("the warm-up's verdict with and without C1 kept off the kernel classes") {
      for (mode <- Seq("without", "with"); run <- 1 to runs) {
        child(mode).foreach(line => report(s"$mode the directive, JVM $run: $line"))
      }
    }
  }

  private def report(line: String): Unit = {
    // scalastyle:off println
    println(line)
    // scalastyle:on println
    output.foreach(_.write((line + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  /**
   * Runs one child JVM in `mode` and returns its result lines. Its output goes to a file rather
   * than a pipe, so that a child which stalls cannot hold the parent past the timeout.
   */
  private def child(mode: String): Seq[String] = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val command = new java.util.ArrayList[String]()
    command.add(javaBin.getAbsolutePath)
    // The benchmark JVM's own options - the module opens Spark needs, the heap, the vector
    // module - so the child is the same JVM but for its history.
    ManagementFactory.getRuntimeMXBean.getInputArguments.asScala
      .filterNot(_.startsWith("-agentlib")).foreach(command.add)
    command.add("-cp")
    command.add(childClassPath())
    command.add(VarkaWarmupDirectiveChild.getClass.getName.stripSuffix("$"))
    command.add(mode)
    val log = Files.createTempFile("varka-directive-child", ".log")
    try {
      val process = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(log.toFile).start()
      val finished = process.waitFor(childTimeoutSeconds, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        process.waitFor(30, TimeUnit.SECONDS)
      }
      val lines = Files.readAllLines(log, StandardCharsets.UTF_8).asScala.toSeq
      def tail: String = lines.takeRight(40).mkString("\n")
      if (!finished) {
        throw new IllegalStateException(
          s"the child did not finish in $childTimeoutSeconds s; its last lines:\n$tail")
      }
      val results = lines.filter(_.startsWith(VarkaWarmupDirectiveChild.RESULT))
        .map(_.stripPrefix(VarkaWarmupDirectiveChild.RESULT))
      require(process.exitValue() == 0 && results.nonEmpty,
        s"the child failed (exit ${process.exitValue()}); its last lines:\n$tail")
      results
    } finally {
      Files.deleteIfExists(log)
    }
  }

  /**
   * The child's class path: this JVM's own, plus every local jar and directory its context
   * class loaders were given. Under `spark-submit`, which is how the benchmark workflow runs
   * benchmarks, the test jars that hold the child come in through `--jars` and are only there.
   */
  private def childClassPath(): String = {
    val entries = mutable.LinkedHashSet.empty[String]
    entries ++= System.getProperty("java.class.path").split(File.pathSeparator).filter(_.nonEmpty)
    var loader = Thread.currentThread().getContextClassLoader
    while (loader != null) {
      loader match {
        case urls: URLClassLoader =>
          urls.getURLs.filter(_.getProtocol == "file").foreach { url =>
            entries += new File(url.toURI).getPath
          }
        case _ =>
      }
      loader = loader.getParent
    }
    entries.mkString(File.pathSeparator)
  }
}

/** One child JVM of [[VarkaWarmupDirectiveBenchmark]]: the sequence, in the mode it is given. */
object VarkaWarmupDirectiveChild {

  val RESULT = "VARKA-DIRECTIVE-RESULT "

  def main(args: Array[String]): Unit = {
    val mode = args(0)
    require(VarkaKernelCompileDirective.readyForWarmup() && VarkaKernelCompileDirective.installed(),
      "this JVM did not add the directive")
    if (mode == "without") {
      // A directive pushed later is matched first, so this one wins for the kernel classes.
      val file = Files.createTempFile("varka-c1-enabled", ".json")
      try {
        Files.writeString(file, "[{ match: \"" + VarkaKernelCompileDirective.METHOD_PATTERN +
          "\", c1: { Exclude: false } }]")
        ManagementFactory.getPlatformMBeanServer.invoke(
          new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerDirectivesAdd",
          Array[AnyRef](Array(file.toString)), Array(classOf[Array[String]].getName))
      } finally {
        Files.deleteIfExists(file)
      }
    }
    val session = VarkaArrowSessions.createSession("VarkaWarmupDirective", varkaEnabled = true,
      warmupEnabled = true)
    try {
      VarkaSizeLadder.cacheDates(session, 100000)
      for (n <- Seq(16, 54)) {
        VarkaShapeCache.invalidateAll()
        val q = s"SELECT ${(1 to n).map(k => VarkaSizeLadder.entry(100 + k)).mkString(", ")} " +
          "FROM ladder_dates"
        val previous = VarkaKernelWarmup.recentOutcomes().asScala.lastOption
        (1 to 12).foreach(_ => session.sql(q).write.format("noop").mode("overwrite").save())
        require(VarkaKernelWarmup.awaitIdle(300000), s"the $n-entry warm-up did not finish")
        val o = VarkaKernelWarmup.recentOutcomes().asScala.last
        require(!previous.exists(_ eq o), s"the $n-entry shape had no warm-up of its own")
        // scalastyle:off println
        println(s"$RESULT$n entries: ${o.state()} after ${o.runNanos() / 1000000} ms and " +
          s"${o.calls()} calls; the first probe allocated ${o.firstProbeBytes()} bytes, the " +
          s"last ${o.lastProbeBytes()}")
        // scalastyle:on println
      }
    } finally {
      session.stop()
    }
  }
}
