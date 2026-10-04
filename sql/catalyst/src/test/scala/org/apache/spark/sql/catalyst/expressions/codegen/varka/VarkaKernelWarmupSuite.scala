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
import java.lang.foreign.{Arena, ValueLayout}
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.management.ObjectName

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmth.State
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmupProbe._

/**
 * The kernel warm-up (`VARKA-212.md` 10): the copy of a batch it runs on, its verdict on a
 * real emitted kernel - which must cover both of the kernel's drivers, whichever kind of batch
 * claimed the warm-up - its release when the shape leaves the cache, and the compiler directive
 * that keeps C1 off the warmed kernel classes so the verdict can arrive at all.
 *
 * The four tests of the verdict run each warm-up in a JVM of its own, through
 * [[VarkaKernelWarmupProbe]]: a verdict is a JIT outcome, and a kernel compiled late in the
 * shared test JVM - after a suite has run a kernel of a second vector species there - boxes every
 * operation and never earns it. The rest of the suite runs in the shared JVM, since nothing it
 * asserts depends on what the JIT made of a kernel.
 */
class VarkaKernelWarmupSuite extends SparkFunSuite with VarkaTestWatchdog {

  /** The lines a probe printed after `prefix`, one per such line. */
  private def marked(lines: Seq[String], prefix: String): Seq[String] =
    lines.flatMap(VarkaProbeOutput.after(_, prefix)).map(_.trim)

  /** `key=value` fields of a marker line, where a field named `outcome` takes the rest. */
  private def fields(line: String): Map[String, String] = {
    val at = line.indexOf("outcome=")
    val (head, rest) =
      if (at < 0) (line, None) else (line.substring(0, at), Some(line.substring(at)))
    (head.trim.split("\\s+").toSeq ++ rest).filter(_.contains("=")).map { kv =>
      val i = kv.indexOf('=')
      kv.substring(0, i) -> kv.substring(i + 1)
    }.toMap
  }

  private def testClasspath: String =
    Option(System.getenv("SPARK_DIST_CLASSPATH")).filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))

  /**
   * What one warm-up claimed by a batch with `claimed` nulls came to, in a JVM of its own: the
   * probe's support line and outcome line as fields, and the allocation of the calls it measured
   * after the verdict, by the batch served.
   */
  private case class Forked(support: Map[String, String], outcome: Map[String, String],
      allocated: Map[String, Long])

  private def warmUpInFork(claimed: Nulls): Forked = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val command = Seq(javaBin.getAbsolutePath, "--add-modules", "jdk.incubator.vector",
      "--enable-native-access=ALL-UNNAMED", "-Xmx1g", "-cp", testClasspath,
      VarkaKernelWarmupProbe.getClass.getName.stripSuffix("$"), claimed.word)
    val process = new ProcessBuilder(command.asJava).redirectErrorStream(true).start()
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
    assert(process.waitFor(240, TimeUnit.SECONDS), "the warm-up probe did not finish")
    val tail = lines.takeRight(40).mkString("\n")
    assert(process.exitValue() == 0, s"the probe failed (exit ${process.exitValue()}):\n$tail")
    assert(marked(lines.toSeq, DONE_PREFIX) == Seq("status=0"), tail)
    val support = marked(lines.toSeq, SUPPORT_PREFIX)
    assert(support.size == 1, tail)
    Forked(fields(support.head), marked(lines.toSeq, OUTCOME_PREFIX).headOption.map(fields)
      .getOrElse(Map.empty), marked(lines.toSeq, ALLOC_PREFIX).map(fields)
      .map(f => f("served") -> f("bytes").toLong).toMap)
  }

  test("the copy repeats a short batch's rows and validity bits, bit offsets included") {
    val arena = Arena.ofConfined()
    try {
      val rows = 5
      val (data, validity, _) = dates(arena, rows)
      val dataCopy = arena.allocate(VarkaKernelWarmup.SNAPSHOT_ROWS * 4L)
      val validityCopy = arena.allocate(VarkaKernelWarmup.SNAPSHOT_ROWS / 8L)
      VarkaKernelWarmup.tileData(data.address(), 4, rows, dataCopy)
      VarkaKernelWarmup.tileValidity(validity.address(), rows, validityCopy)
      (0 until VarkaKernelWarmup.SNAPSHOT_ROWS).foreach { r =>
        val s = r % rows
        assert(dataCopy.getAtIndex(ValueLayout.JAVA_INT, r) ===
          data.getAtIndex(ValueLayout.JAVA_INT, s), s"row $r")
        val bit = (validityCopy.get(ValueLayout.JAVA_BYTE, r / 8) >> (r % 8)) & 1
        assert(bit === (if (s % 7 == 3) 0 else 1), s"validity of row $r")
      }
    } finally {
      arena.close()
    }
  }

  test("a warm-up runs a new kernel until it no longer allocates, and says it is compiled") {
    val run = warmUpInFork(SomeNulls)
    assume(run.support("sampler") == "true", "thread allocation accounting unavailable")
    val o = run.outcome
    assert(o("queued") == "true", "the warm-up was not queued")
    assert(o("idle") == "true", "the warm-up did not finish in two minutes")
    assert(o("matches") == "true", o("outcome"))
    assert(o("state") == State.COMPILED.toString, o("outcome"))
    assert(o("entryState") == State.COMPILED.toString && o("ready") == "true", o)
    // The verdict's evidence: the first probe boxed, the last one did not.
    assert(o("last").toLong * VarkaKernelWarmup.COMPILED_DROP <= o("first").toLong, o("outcome"))
  }

  Seq(NoNulls, SomeNulls, AllNull).foreach { claimed =>
    // The shape has two groups whose loop methods compile one after the other, and the verdict
    // must wait for both: on a starved machine a verdict read from short calls came while the
    // light group's loop was still interpreted, and these calls, 1024 rows each, boxed in it
    // (VARKA-221.md 2).
    test(s"a warm-up claimed by a batch with $claimed compiles both of the kernel's drivers") {
      val run = warmUpInFork(claimed)
      assume(run.support("sampler") == "true", "thread allocation accounting unavailable")
      assume(run.support("canWarm") == "true", "this JVM cannot warm kernels")
      val o = run.outcome
      assert(o("queued") == "true" && o("idle") == "true", o)
      assert(o("matches") == "true", o("outcome"))
      assert(o("state") == State.COMPILED.toString, o("outcome"))
      // A compiled call allocates only its driver's memory segments, a few hundred bytes a
      // column; an interpreted one boxes every vector operation, tens of kilobytes a call.
      val limit = 16L * VarkaKernelWarmup.SEGMENT_BYTES_PER_COLUMN * (1 + numOutputs) +
        VarkaAllocationSampler.FIXED_ALLOWANCE_BYTES
      Seq(NoNulls, SomeNulls).foreach { served =>
        val allocated = run.allocated(served.word)
        assert(allocated <= limit,
          s"batches with $served allocated $allocated bytes after a warm-up claimed by a batch " +
            s"with $claimed (${o("outcome")})")
      }
    }
  }

  test("the copy gives a null row the first valid value, so a dense call reads real dates") {
    val arena = Arena.ofConfined()
    try {
      val rows = VarkaKernelWarmup.SNAPSHOT_ROWS
      val (data, validity, _) = dates(arena, rows)
      VarkaKernelWarmup.fillNullRows(data, 4, validity)
      (0 until rows).foreach { r =>
        val expected = if (r % 7 == 3) 18262 else 18262 + r % 1460
        assert(data.getAtIndex(ValueLayout.JAVA_INT, r) === expected, s"row $r")
      }
    } finally {
      arena.close()
    }
  }

  test("a shape that leaves the cache stops its warm-up and is ready for its tasks") {
    assume(VarkaAllocationSampler.supported(), "thread allocation accounting unavailable")
    val cache = new VarkaShapeCacheImpl(8)
    val (entry, queued) = warm(cache, 1000)
    assert(queued, "the warm-up was not queued")
    cache.invalidateAll()
    assert(VarkaKernelWarmup.awaitIdle(120000), "the warm-up did not finish in two minutes")
    val outcome = VarkaKernelWarmup.recentOutcomes().asScala.last
    assert(outcome.shapeHash() === entry.shapeHash())
    // The eviction released the shape while its warm-up was queued or had barely begun, so the
    // job stopped at once rather than running to a verdict for a class no task uses.
    assert(entry.warmth().state() === State.RELEASED)
    assert(outcome.state() === State.RELEASED, outcome)
    assert(outcome.calls() < VarkaKernelWarmup.SPIN_CALLS, outcome)
  }

  test("a claim goes to exactly one caller, and handing it back lets another take it") {
    val warmth = new VarkaKernelWarmth
    assert(!warmth.ready())
    assert(warmth.tryClaim())
    assert(!warmth.tryClaim(), "a second claim while the first is warming")
    warmth.unclaim()
    assert(warmth.state() === State.COLD)
    assert(warmth.tryClaim())
    warmth.release()
    assert(warmth.ready() && warmth.state() === State.RELEASED)
    warmth.unclaim()
    assert(warmth.state() === State.RELEASED, "a released shape does not go back to cold")
  }

  test("a JVM that warms kernels has the directive that keeps C1 off the warmed classes") {
    // Assumed on the JVM's compilers only: where C1 and C2 are tiered, a directive that failed
    // to install is a failure of this test, not a reason to skip it.
    assume(VarkaKernelCompileDirective.compilers() == VarkaKernelCompileDirective.Compilers.TIERED,
      "C1 and C2 are not tiered in this JVM")
    assume(VarkaAllocationSampler.supported(), "thread allocation accounting unavailable")
    assert(VarkaKernelWarmup.canWarm(), "a tiered JVM that measures allocation cannot warm")
    assert(VarkaKernelCompileDirective.installed())
    val directives = ManagementFactory.getPlatformMBeanServer.invoke(
      new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerDirectivesPrint",
      Array[AnyRef](Array.empty[String]), Array(classOf[Array[String]].getName)).toString
    assert(directives.contains(VarkaKernelCompileDirective.METHOD_PATTERN), directives)
  }

  test("only a warmed kernel's class name is one the directive matches") {
    val warmedName = VarkaShapeCacheImpl.classNameFor(VarkaShapeCacheImpl.shapeHash(shape))
    val plain = new VarkaShapeKey(shape.outputs(), 1, 1)
    val plainName = VarkaShapeCacheImpl.classNameFor(VarkaShapeCacheImpl.shapeHash(plain))
    val prefix = VarkaKernelCompileDirective.METHOD_PATTERN.stripSuffix("*.*").replace('/', '.')
    assert(warmedName.startsWith(prefix), warmedName)
    assert(!plainName.startsWith(prefix), plainName)
    // The same bytes under both names: the mark is the whole difference.
    val prefixLength = VarkaShapeCacheImpl.CLASS_NAME_PREFIX.length
    assert(warmedName.substring(prefixLength) ===
      VarkaShapeCacheImpl.WARMED_MARK + plainName.substring(prefixLength))
  }
}
