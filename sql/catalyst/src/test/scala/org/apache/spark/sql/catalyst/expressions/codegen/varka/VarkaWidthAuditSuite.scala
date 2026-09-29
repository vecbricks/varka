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
import scala.jdk.CollectionConverters._

import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaWidthAuditProbe._

/**
 * Which Vector API calls in Varka's kernels C2 refuses to lower, per shape and per vector
 * width, read from C2's own log rather than inferred from a rate (task 153).
 *
 * A refused call is not an error: the Vector API keeps a Java implementation of every
 * operation, C2 compiles that instead, the answer is right, and the suites are green. What is
 * lost is the vector - the fallback loops over the lanes - and the only signs are a rate an
 * order of magnitude below its neighbours and a `** not supported: ...` line that C2 prints
 * under `PrintIntrinsics`. `VarkaTimeBenchmark`'s 128-bit companion found the first one this
 * way (`PLAN_TASK_152.md` 6.5): the 64-bit magic divide at one fiftieth of its sibling,
 * because at two 64-bit lanes this JVM lowers no masked long operation and no compare that
 * produces a mask. Every long-lane guard is built from exactly those.
 *
 * So this suite asks the question for every shape, at every width the host can be asked for.
 * It forks [[VarkaWidthAuditProbe]] once per width under `-XX:MaxVectorSize=<bytes> -Xbatch`
 * and a `PrintIntrinsics` directive scoped to the emitted classes, and reads the refusals
 * between the probe's per-shape markers. Two things are then done with them:
 *
 *  - **an invariant, on every host:** at the host's own preferred width, no coverage row and
 *    no construction other than the opt-in magic form meets a `not supported` refusal, save
 *    the one the design already records - the 64-bit lane's converts below AVX-512, see
 *    `knownBelowAvx512`. A failure names the shape, the operation and the CPU, which is what
 *    a per-lane rate never does. The other two kinds of line C2 prints are recorded but not
 *    asserted on; see `isRefusal`;
 *  - **a census, pinned:** `sql/varka/width_audit.json` records every width's refusals for
 *    the host it was taken on. The census is a property of the CPU and the JDK, so the check
 *    against the committed file runs only on that CPU model and is cancelled, with the reason,
 *    elsewhere; a new host's census is a regeneration on that host, and the file says whose
 *    it is. `VarkaCoverageSuite` renders the 128-bit column of the coverage table from it.
 *
 * {{{
 *   VARKA_AUDIT_REGEN=true build/sbt 'catalyst/testOnly *VarkaWidthAuditSuite'
 * }}}
 */
class VarkaWidthAuditSuite extends SparkFunSuite with VarkaTestWatchdog {

  private val childTimeoutSeconds = 600L

  /** The widths asked for, in bytes as the JVM names them; a host caps what it cannot do. */
  private val widthsBytes = Seq(16, 32, 64)

  private case class Census(preferredBits: Int, useAVX: Int, refusals: Map[String, Seq[String]],
      unattributed: Seq[String])

  private val cpuModel: String = {
    val info = new File("/proc/cpuinfo")
    if (info.exists()) {
      Files.readAllLines(info.toPath).asScala.find(_.startsWith("model name"))
        .map(_.split(":", 2)(1).trim).getOrElse("unknown")
    } else {
      "unknown"
    }
  }

  private def testClasspath: String =
    Option(System.getenv("SPARK_DIST_CLASSPATH")).filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))

  private lazy val coverageJson: File =
    getWorkspaceFilePath("sql", "varka", "coverage.json").toFile

  /** Fork the probe at one width - or at the host's own, for `None` - and read its answers. */
  private def audit(maxVectorSizeBytes: Option[Int]): Census = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val command = new java.util.ArrayList[String]()
    command.add(javaBin.getAbsolutePath)
    command.add("--add-modules")
    command.add("jdk.incubator.vector")
    command.add("--enable-native-access=ALL-UNNAMED")
    command.add("-XX:+UnlockDiagnosticVMOptions")
    command.add("-Xbatch")
    command.add("-XX:CompileCommand=quiet")
    command.add(s"-XX:CompileCommand=PrintIntrinsics,$CLASS_PREFIX*::*")
    maxVectorSizeBytes.foreach(b => command.add(s"-XX:MaxVectorSize=$b"))
    command.add("-cp")
    command.add(testClasspath)
    command.add(VarkaWidthAuditProbe.getClass.getName.stripSuffix("$"))
    command.add(coverageJson.getAbsolutePath)
    val builder = new ProcessBuilder(command)
    builder.redirectErrorStream(true)
    val process = builder.start()
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val refusals = mutable.LinkedHashMap.empty[String, mutable.LinkedHashSet[String]]
    val unattributed = mutable.LinkedHashSet.empty[String]
    var current: Option[String] = None
    var bits = -1
    var useAVX = VarkaEmitOptions.USE_AVX_UNKNOWN
    var done = false
    val tail = mutable.Queue.empty[String]
    try {
      var line = reader.readLine()
      while (line != null) {
        tail.enqueue(line)
        if (tail.size > 40) tail.dequeue()
        // Markers are found anywhere in a line: a compiler thread's record can share the line
        // (`VarkaProbeOutput`).
        val trimmed = line.trim
        VarkaProbeOutput.longAfter(line, PREFERRED_BITS_PREFIX).foreach(b => bits = b.toInt)
        VarkaProbeOutput.longAfter(line, USE_AVX_PREFIX).foreach(u => useAVX = u.toInt)
        VarkaProbeOutput.between(line, SHAPE_BEGIN_PREFIX, SHAPE_NAME_END).foreach { name =>
          current = Some(name)
          refusals.getOrElseUpdate(name, mutable.LinkedHashSet.empty)
        }
        if (VarkaProbeOutput.has(line, SHAPE_END_PREFIX)) {
          current = None
        }
        if (VarkaProbeOutput.has(line, DONE)) {
          done = true
        }
        if (trimmed.startsWith("** ")) {
          val what = trimmed.stripPrefix("** ").trim
          current match {
            case Some(name) => refusals(name) += what
            case None => unattributed += what
          }
        }
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }
    if (!process.waitFor(childTimeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      fail(s"the audit probe did not finish within $childTimeoutSeconds s")
    }
    assert(process.exitValue() == 0 && done,
      s"the audit probe failed (exit ${process.exitValue()}); its last lines:\n" +
        tail.mkString("\n"))
    assert(bits > 0, "the audit probe never reported its preferred vector width")
    Census(bits, useAVX, refusals.map { case (k, v) => k -> v.toSeq }.toMap, unattributed.toSeq)
  }

  private lazy val atPreferred: Census = audit(None)

  /** One census per distinct width the host can actually run; a capped request is dropped. */
  private lazy val byWidth: Seq[(Int, Census)] = {
    val seen = mutable.LinkedHashMap.empty[Int, Census]
    widthsBytes.foreach { bytes =>
      val c = audit(Some(bytes))
      if (!seen.contains(c.preferredBits)) seen(c.preferredBits) = c
    }
    seen.toSeq
  }

  private val magicForm = "magic form (useAVX = 2)"

  /**
   * C2 prints three kinds of line under `PrintIntrinsics` for a vector call it did not inline
   * on an attempt, and only one of them is a verdict. `not supported` is architectural: the
   * matcher has no lowering for that operation at that lane count and element type on this
   * machine, and no later attempt can change it - it is the kind behind the 128-bit collapse
   * `PLAN_TASK_152.md` 6.5 measured. `missing constant` says an argument was not yet a
   * constant when a *late* inline was first attempted; C2 retries late inlines after further
   * optimisation, so the line is not proof of a fallback in the final code - `i + 1` prints two
   * at 128 bits and the committed 128-bit results show it fully vectorised. `unbox failed`
   * says a vector value reached the call as a heap object, which is task 55's boxing; here it
   * appears only once other kernels have been through the shared templates in the same JVM.
   * The invariant below and the committed census both rest on the first alone: the other two
   * depend on when C2 got to a method in the forked JVM, and the first regenerations after
   * task 153 saw them appear and vanish on shapes nothing had touched, so a file that carried
   * them failed on a quiet tree and blessed whichever timing a regeneration had (task 154).
   */
  private def isRefusal(line: String): Boolean = line.startsWith("not supported")

  /** A census's verdict lines per shape: what the file pins and what two runs must agree on. */
  private def verdicts(census: Census): Map[String, Seq[String]] =
    census.refusals.map { case (name, lines) => name -> lines.filter(isRefusal) }

  /**
   * The one refusal a host class is known to have at its own width, and the emitter already
   * knows about: below AVX-512 there is no vector lowering of the 64-bit lane's conversions to
   * and from double (`L2D`, `D2L`), which is why `VarkaEmitOptions.convertsFallBack` exists
   * and the magic form of the division was built (`PLAN_TASK_88.md` 9.2). C2 prints it as a
   * lane cast of four 64-bit lanes: `op=cast#<n>/3 vlen2=4 etype2=double|long ismask=0`.
   * The audit's first CI run confirmed it from the runner pool's EPYC 7763 at 256 bits, in
   * every shape that carries a 64-bit constant division and nowhere else. The invariant
   * expects it there rather than failing every AVX2 runner on a fact the design records;
   * task 121 owns what to do about it.
   */
  private def knownBelowAvx512(census: Census, line: String): Boolean =
    census.useAVX != VarkaEmitOptions.USE_AVX_UNKNOWN &&
      // AVX-512 is level 3, the same reading `VarkaEmitOptions.convertsFallBack` makes.
      census.useAVX < 3 &&
      line.startsWith("not supported") && line.contains("op=cast#") &&
      line.contains("ismask=0") &&
      (line.contains("etype2=double") || line.contains("etype2=long"))

  test("at the host's preferred width, C2 has a lowering for every Vector API call in every " +
      "shape, the 64-bit converts below AVX-512 excepted") {
    val census = atPreferred
    val refused = census.refusals.map { case (name, ops) =>
      name -> ops.filter(o => isRefusal(o) && !knownBelowAvx512(census, o))
    }.filter { case (name, ops) => ops.nonEmpty && !name.contains(magicForm) }
    assert(refused.isEmpty,
      s"on $cpuModel at ${census.preferredBits} bits (UseAVX ${census.useAVX}), C2 refused a " +
        s"lowering in ${refused.size} shape(s):\n" + refused.map { case (n, ops) =>
          s"  $n\n" + ops.map(o => s"    ** $o").mkString("\n")
        }.mkString("\n"))
  }

  private def render(): String = {
    def ordered(pairs: (String, Any)*): java.util.LinkedHashMap[String, Any] = {
      val map = new java.util.LinkedHashMap[String, Any]()
      pairs.foreach { case (k, v) => map.put(k, v) }
      map
    }
    val widths = byWidth.map { case (bits, census) =>
      bits.toString -> ordered(verdicts(census).toSeq.sortBy(_._1).map { case (name, ops) =>
        name -> ops.asJava
      }: _*)
    }
    val doc = ordered(
      "generated_by" -> ("VarkaWidthAuditSuite; regenerate on the host named below with " +
        "VARKA_AUDIT_REGEN=true build/sbt 'catalyst/testOnly *VarkaWidthAuditSuite'"),
      "description" -> ("For every audited shape and every vector width this host can run, " +
        "the 'not supported' lines C2 printed under PrintIntrinsics for the shape's Vector " +
        "API calls, taken in one JVM per width with the shapes run in this order. Each is a " +
        "refusal: that operation runs as a per-lane Java loop at that width. The two other " +
        "kinds of line C2 prints - 'missing constant', a first late-inline attempt it may " +
        "retry, and 'unbox failed', a vector reaching a call as a heap object once the shared " +
        "templates have seen other kernels - are not verdicts and are not recorded: they " +
        "depend on JIT timing in the probe and moved between runs of an unchanged tree. An " +
        "empty list is a shape C2 refused nothing in. The census is a property of the CPU " +
        "and the JDK, so the host is part of the record."),
      "host" -> ordered(
        "cpu" -> cpuModel,
        "arch" -> System.getProperty("os.arch"),
        "jdk" -> System.getProperty("java.runtime.version"),
        "preferred_bits" -> atPreferred.preferredBits,
        "use_avx" -> atPreferred.useAVX),
      "widths" -> ordered(widths: _*))
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n"
  }

  test("two censuses of one width agree on their verdicts, whatever else C2 printed") {
    // The property the committed file rests on: a refusal is architectural and prints on
    // every run, where the other two kinds are timing and may not. A second probe at the
    // preferred width has to give the same verdict lines per shape as the first; the raw
    // line sets are allowed to differ, which is exactly why the file does not carry them.
    val again = audit(None)
    assert(again.preferredBits === atPreferred.preferredBits)
    assert(verdicts(again) === verdicts(atPreferred),
      "the verdict lines of two probes at one width differ, so the census is not a property " +
        "of the machine and the JDK alone")
  }

  test("sql/varka/width_audit.json is this host's census, per width") {
    val path = getWorkspaceFilePath("sql", "varka", "width_audit.json")
    if (sys.env.get("VARKA_AUDIT_REGEN").contains("true")) {
      Files.write(path, render().getBytes(StandardCharsets.UTF_8))
      logInfo(s"regenerated $path")
    } else {
      assert(Files.exists(path), s"$path is missing; generate it with\n" +
        "  VARKA_AUDIT_REGEN=true build/sbt 'catalyst/testOnly *VarkaWidthAuditSuite'")
      val committed = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      val committedHost = new ObjectMapper().readTree(committed).get("host").get("cpu").asText()
      if (committedHost != cpuModel) {
        cancel(s"the committed census is $committedHost's and this is $cpuModel; the census " +
          "is a property of the CPU, so it is compared only where it was taken")
      }
      val rendered = render()
      if (committed != rendered) {
        val before = new ObjectMapper().readTree(committed).get("widths")
        val after = new ObjectMapper().readTree(rendered).get("widths")
        val moved = Seq.newBuilder[String]
        after.fieldNames().asScala.foreach { bits =>
          val b = Option(before.get(bits))
          val a = after.get(bits)
          a.fieldNames().asScala.foreach { shape =>
            val was = b.flatMap(n => Option(n.get(shape))).map(_.toString).getOrElse("(absent)")
            val now = a.get(shape).toString
            if (was != now) moved += s"$bits bits: $shape: $was -> $now"
          }
        }
        val changes = moved.result()
        if (changes.isEmpty) {
          // The verdicts agree and only the provenance block differs - the JDK string after a
          // package update is the case that found this. The census is the verdicts; a new JDK
          // that changed none of them is worth a line, not a red suite, and the next
          // regeneration carries the new string.
          val committedJdk = new ObjectMapper().readTree(committed).get("host").get("jdk").asText()
          info(s"the committed census was taken on JDK $committedJdk and this host runs " +
            s"${System.getProperty("java.runtime.version")}; every verdict agrees, so the file " +
            "stands until the next regeneration updates its provenance")
        } else {
          fail("sql/varka/width_audit.json does not match what this host measures. If the " +
            "emitter or the JDK changed what C2 lowers, regenerate and say so in the plan. " +
            "What moved:\n  " + changes.mkString("\n  "))
        }
      }
    }
  }
}
