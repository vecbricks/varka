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

import jdk.incubator.vector.IntVector

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmth.State
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmupProbe._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmupProbe.Nulls
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix.C2Code

/**
 * The kernel warm-up (`VARKA-212.md` 10): the copy of a batch it runs on, its verdict on a
 * real emitted kernel - which must cover both of the kernel's drivers, whichever kind of batch
 * claimed the warm-up - its release when the shape leaves the cache, and the compiler directive
 * that keeps C1 off the warmed kernel classes so the verdict can arrive at all.
 *
 * The five tests of the verdict run each warm-up in a JVM of its own, through
 * [[VarkaKernelWarmupProbe]]: a verdict is a JIT outcome, and a kernel compiled late in the
 * shared test JVM - after a suite has run a kernel of a second vector species there - boxes every
 * operation and never earns it. The rest of the suite runs in the shared JVM, since nothing it
 * asserts depends on what the JIT made of a kernel.
 *
 * A probe's JVM gets this JVM's matrix configuration, so the option matrix warms each
 * configuration's kernel, and this JVM's `-XX:MaxVectorSize`, `-XX:UseAVX` and `-XX:UseSVE`, so
 * a run at a narrower vector reaches it and the species this JVM checks is the probe's. It does
 * not get the memory sanitizer: the probe measures what C2 made of a kernel, and a sanitized call
 * deoptimises warm code compiled outside the sanitizer's window (`VARKA-400.md` 2). Nor does it
 * get `-XX:ActiveProcessorCount`, so its compiler threads follow the machine's processors.
 *
 * The forked tests' subject is what the JIT makes of a kernel, so they are tagged
 * `VarkaMatrix.C2Code`: under the option matrix they run only where a configuration changes what
 * C2 is given beyond the bytes (a lane count, the half species, `useAVX`), and the defaults' run
 * checks the warm-up on the kernels users run. The rest of the suite runs under every
 * configuration.
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
   * probe's support line and outcome line as fields, the allocation of the calls it measured
   * after the verdict, by the batch served, the same calls through the kernel's own `run`, and
   * its JIT state line after those calls.
   */
  private case class Forked(support: Map[String, String], outcome: Map[String, String],
      allocated: Map[String, Long], ownRun: Map[String, Long], jit: String,
      later: Map[String, (Long, Long)] = Map.empty, laterJit: String = "no later state printed")

  /** The probe's budget: its warm-up deadline (120 s), its wait for the verdict, and slack. */
  private val probeBudgetSeconds = 240L

  private def warmUpInFork(claimed: Nulls, wide: Boolean = false,
      others: Boolean = false): Forked = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val vectorFlags = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toSeq
      .filter(a => Seq("-XX:MaxVectorSize=", "-XX:UseAVX=", "-XX:UseSVE=").exists(a.startsWith))
    val config = VarkaMatrix.config
    val matrixConfig = if (config.isEmpty) Nil else Seq(s"-D${VarkaMatrix.PROPERTY}=$config")
    val command = Seq(javaBin.getAbsolutePath, "--add-modules", "jdk.incubator.vector",
      "--enable-native-access=ALL-UNNAMED", "-Xmx1g", "-Dvarka.warmup.deadlineSeconds=120") ++
      vectorFlags ++ matrixConfig ++ Seq("-cp", testClasspath,
      classOf[VarkaKernelWarmupProbe].getName, claimed.word) ++ (if (wide) Seq("wide") else Nil) ++
      (if (others) Seq("others") else Nil)
    // The child JVM's code runs where the class-use record cannot see it (VarkaTestUse).
    VarkaTestUse.childJvm()
    val process = new ProcessBuilder(command.asJava).redirectErrorStream(true).start()
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val lines = mutable.ArrayBuffer.empty[String]
    // The read below ends only when the child exits, so a hung probe is ended from here: a daemon
    // thread destroys it after the budget (the child's own deadline is 120 s).
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
    assert(process.waitFor(probeBudgetSeconds, TimeUnit.SECONDS),
      s"the warm-up probe did not finish in $probeBudgetSeconds seconds")
    val tail = lines.takeRight(40).mkString("\n")
    assert(process.exitValue() == 0, s"the probe failed (exit ${process.exitValue()}):\n$tail")
    assert(marked(lines.toSeq, DONE_PREFIX) == Seq("status=0"), tail)
    val support = marked(lines.toSeq, SUPPORT_PREFIX)
    assert(support.size == 1, tail)
    Forked(fields(support.head), marked(lines.toSeq, OUTCOME_PREFIX).headOption.map(fields)
      .getOrElse(Map.empty), marked(lines.toSeq, ALLOC_PREFIX).map(fields)
      .map(f => f("served") -> f("bytes").toLong).toMap,
      marked(lines.toSeq, OWN_RUN_PREFIX).map(fields).map(f => f("served") -> f("bytes").toLong)
        .toMap,
      marked(lines.toSeq, JIT_PREFIX).headOption.getOrElse("no JIT state printed"),
      marked(lines.toSeq, LATER_PREFIX).map(fields)
        .map(f => f("served") -> (f("bytes").toLong, f("own").toLong)).toMap,
      marked(lines.toSeq, LATER_JIT_PREFIX).headOption.getOrElse("no later state printed"))
  }

  /**
   * Cancels a forked compile test under a lane count that is not this JVM's preferred int
   * species, which the probe's JVM shares: the template warm-up compiles the Vector API's shared
   * templates for the preferred species, so a kernel of a second one boxes its every operation and
   * never earns the verdict (`vector-api-and-width.md`, "two species in one JVM is a box per
   * iteration").
   */
  private def assumeOneSpecies(): Unit = {
    val lanes = VarkaMatrix.base.lanesOverride()
    val preferred = IntVector.SPECIES_PREFERRED.length()
    assume(lanes == 0 || lanes == preferred,
      s"a kernel at $lanes int lanes is a second species beside this JVM's preferred $preferred")
  }

  test("the copy repeats a short batch's rows and validity bits, bit offsets included") {
    val arena = Arena.ofConfined()
    try {
      val rows = 5
      val batch = dates(arena, rows, Nulls.SOME_NULLS)
      val (data, validity) = (batch.data, batch.validity)
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

  test("a warm-up runs a new kernel until it no longer allocates, and says it is compiled",
      C2Code) {
    assumeOneSpecies()
    val run = warmUpInFork(Nulls.SOME_NULLS)
    assume(run.support("sampler") == "true", "thread allocation accounting unavailable")
    val o = run.outcome
    assert(o("queued") == "true", "the warm-up was not queued")
    assert(o("idle") == "true", "the warm-up did not finish within the probe's wait")
    assert(o("matches") == "true", o("outcome"))
    assert(o("state") == State.COMPILED.toString, o("outcome"))
    assert(o("entryState") == State.COMPILED.toString && o("ready") == "true", o)
    // The verdict's evidence: the first probe boxed, the last one did not.
    assert(o("last").toLong * VarkaKernelWarmup.COMPILED_DROP <= o("first").toLong, o("outcome"))
  }

  test("a wide shape warmed after an unwarmed kernel took the templates to C2 still compiles",
      C2Code) {
    // The order of VarkaColdStartBenchmark, and of any JVM whose first vector code is not a
    // warm-up: a kernel runs unwarmed first, its interpreted calls take the shared Vector API
    // templates to their standalone C2 compiles, and only then does a shape warm. Before
    // VARKA-389 the template warm-up ran at that point, deoptimised those compiles, and a wide
    // kernel, which inlines each template hundreds of times, summed the decompile count past
    // C2's cutoff and was released at the deadline on every machine (`VARKA-389.md` 8.4a). The
    // probe's wide shape is the benchmark's at sixteen entries, four groups of four outputs; the
    // JVM is the probe's own, so no earlier suite can have warmed the templates first.
    assumeOneSpecies()
    val run = warmUpInFork(Nulls.SOME_NULLS, wide = true)
    assume(run.support("sampler") == "true", "thread allocation accounting unavailable")
    assume(run.support("canWarm") == "true", "this JVM cannot warm kernels")
    val o = run.outcome
    assert(o("queued") == "true" && o("idle") == "true", o)
    assert(o("matches") == "true", o("outcome"))
    assert(o("state") == State.COMPILED.toString, o("outcome"))
  }

  Seq(Nulls.NO_NULLS, Nulls.SOME_NULLS, Nulls.ALL_NULL).foreach { claimed =>
    // The shape has two groups whose loop methods compile one after the other, and the verdict
    // must wait for both: on a starved machine a verdict read from short calls came while the
    // light group's loop was still interpreted, and these calls, 1024 rows each, boxed in it
    // (VARKA-221.md 2). The calls asserted on take the entry the evaluator and the warm-up take,
    // whose code the verdict read; the same calls through the kernel's own `run`, a chain the
    // warm-up does not wait for, are reported beside them and not asserted (VARKA-398.md 3,
    // row 405).
    test(s"after a warm-up claimed by a batch with $claimed, both drivers run without boxing " +
        "through the entry the evaluator calls", C2Code) {
      assumeOneSpecies()
      val run = warmUpInFork(claimed)
      assume(run.support("sampler") == "true", "thread allocation accounting unavailable")
      assume(run.support("canWarm") == "true", "this JVM cannot warm kernels")
      val o = run.outcome
      assert(o("queued") == "true" && o("idle") == "true", o)
      assert(o("matches") == "true", o("outcome"))
      assert(o("state") == State.COMPILED.toString, o("outcome"))
      // A compiled call allocates only its driver's memory segments, a few hundred bytes a
      // column; an interpreted one boxes every vector operation, tens of kilobytes a call.
      val limit = 16L * VarkaKernelWarmup.SEGMENT_BYTES_PER_COLUMN * (1 + NUM_OUTPUTS) +
        VarkaAllocationSampler.FIXED_ALLOWANCE_BYTES
      val ownRun = Seq(Nulls.NO_NULLS, Nulls.SOME_NULLS)
        .map(served => s"$served ${run.ownRun.getOrElse(served.word, -1L)} bytes").mkString(", ")
      info(s"through the kernel's own run, not asserted: $ownRun; ${run.jit}")
      Seq(Nulls.NO_NULLS, Nulls.SOME_NULLS).foreach { served =>
        val allocated = run.allocated(served.word)
        assert(allocated <= limit,
          s"batches with $served allocated $allocated bytes after a warm-up claimed by a batch " +
            s"with $claimed (${o("outcome")}); through the kernel's own run, not asserted: " +
            s"$ownRun; ${run.jit}")
      }
    }
  }

  test("a kernel stays free of boxing through the evaluator's entry after other kernel " +
      "classes have been warmed through the same default method", C2Code) {
    // The executor's situation: one JVM warms shape after shape through
    // `VarkaFusedKernel.run`'s scratch default method. While only the first kernel's class has
    // reached it, C2 compiles that method with the whole kernel inlined and the verdict reads that
    // code; a second and third class fail the compile's class check and a task's calls of the
    // first kernel can fall back on its own `run` chain. That chain has C2 code by then
    // (VARKA-405.md 2), and this holds it there: the limits are the ones of the test above, read
    // after three more shapes have warmed.
    assumeOneSpecies()
    val run = warmUpInFork(Nulls.SOME_NULLS, others = true)
    assume(run.support("sampler") == "true", "thread allocation accounting unavailable")
    assume(run.support("canWarm") == "true", "this JVM cannot warm kernels")
    val o = run.outcome
    assert(o("queued") == "true" && o("idle") == "true", o)
    assert(o("state") == State.COMPILED.toString, o("outcome"))
    val limit = 16L * VarkaKernelWarmup.SEGMENT_BYTES_PER_COLUMN * (1 + NUM_OUTPUTS) +
      VarkaAllocationSampler.FIXED_ALLOWANCE_BYTES
    assert(run.later.keySet == Set("none", "some"), run.laterJit)
    info(s"after three more shapes: ${run.later}; ${run.laterJit}")
    Seq(Nulls.NO_NULLS, Nulls.SOME_NULLS).foreach { served =>
      val (entry, own) = run.later(served.word)
      assert(entry <= limit && own <= limit,
        s"batches with $served allocated $entry bytes through the evaluator's entry and $own " +
          s"through the kernel's own run, against $limit, after three more shapes warmed " +
          s"(${o("outcome")}); ${run.laterJit}")
    }
  }

  test("the copy gives a null row the first valid value, so a dense call reads real dates") {
    val arena = Arena.ofConfined()
    try {
      val rows = VarkaKernelWarmup.SNAPSHOT_ROWS
      val batch = dates(arena, rows, Nulls.SOME_NULLS)
      val (data, validity) = (batch.data, batch.validity)
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
    val warmed = warm(cache, 1000, Nulls.SOME_NULLS)
    val (entry, queued) = (warmed.entry, warmed.queued)
    assert(queued, "the warm-up was not queued")
    cache.invalidateAll()
    assert(VarkaKernelWarmup.awaitIdle(150000),
      "the warm-up did not finish within the probe's wait")
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
    val plain =
      new VarkaShapeKey(shape.outputs(), shape.numInputs(), shape.numLiterals(), shape.options(),
        false)
    val plainName = VarkaShapeCacheImpl.classNameFor(VarkaShapeCacheImpl.shapeHash(plain))
    val prefix = VarkaKernelCompileDirective.METHOD_PATTERN.stripSuffix("*.*").replace('/', '.')
    assert(warmedName.startsWith(prefix), warmedName)
    assert(!plainName.startsWith(prefix), plainName)
    // The same bytes under both names: the mark is the whole difference.
    val prefixLength = VarkaShapeCacheImpl.CLASS_NAME_PREFIX.length
    assert(warmedName.substring(prefixLength) ===
      VarkaShapeCacheImpl.WARMED_MARK + plainName.substring(prefixLength))
  }

  test("the JIT state a released warm-up reports names the compilers, the cache and the queue") {
    // What VARKA-295 needs from the next failure on CI: whether C2 was off, the code cache full,
    // or the queue backed up behind other methods. Read here on a JVM that has compiled a little.
    val state = VarkaKernelWarmup.jitState()
    assert(state.startsWith("JIT: "), state)
    assert(state.contains("compile time"), state)
    assert(state.contains("Code"), state)
    assert(state.contains("compile queue of"), state)
    // Given a class, it also lists that class's compiled methods and the ones with no code:
    // String's are compiled in any JVM that has run a test, and its rarely used ones are not.
    val classState = VarkaKernelWarmup.jitState(classOf[String])
    assert(classState.matches(".*kernel code \\(\\d+\\): .*\\d+ L\\d s\\d \\S+, .*"), classState)
    assert(classState.contains("no code in use: ["), classState)
  }

  test("a block is clean only within the allowance and a quarter of the first") {
    val allowance = 9216L
    val first = 5858064L
    // The uncompiled rate: boxing on every operation.
    assert(!VarkaKernelWarmup.blockClean(first, allowance, first))
    assert(!VarkaKernelWarmup.blockClean(first / 2, allowance, first))
    // Within the allowance and a quarter of the first or less: a compiled kernel.
    assert(VarkaKernelWarmup.blockClean(800L, allowance, first))
    assert(VarkaKernelWarmup.blockClean(allowance, allowance, first))
    // Over the allowance is not clean however far below the first: an interpreted light loop
    // boxes tens of kilobytes (38784 bytes after 6.1 MB was VARKA-295's counterexample).
    assert(!VarkaKernelWarmup.blockClean(38784L, allowance, 6104984L))
    assert(!VarkaKernelWarmup.blockClean(20720L, allowance, first))
    // Within the allowance but not a quarter below the first: a narrow kernel's boxing.
    assert(!VarkaKernelWarmup.blockClean(2000L, allowance, 4000L))
    assert(!VarkaKernelWarmup.blockClean(Long.MaxValue, Long.MaxValue, first))
  }
}
