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
 * width, read from C2's own log rather than inferred from a rate (VARKA-153).
 *
 * A refused call is not an error: the Vector API keeps a Java implementation of every
 * operation, C2 compiles that instead, the answer is right, and the suites are green. What is
 * lost is the vector - the fallback loops over the lanes - and the only signs are a rate an
 * order of magnitude below its neighbours and a `** not supported: ...` line that C2 prints
 * under `PrintIntrinsics`. `VarkaTimeBenchmark`'s 128-bit companion found the first one this
 * way (`VARKA-152.md` 6.5): the 64-bit magic divide at one fiftieth of its sibling,
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
 *    the one the design already records - the 64-bit lane's converts without AVX512DQ, see
 *    `knownConversionRefusal`. A failure names the shape, the operation and the CPU, which is
 *    what a per-lane rate never does. The other two kinds of line C2 prints are recorded but not
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

  /**
   * One width's answers: what C2 refused per shape, and what the compiler declined before C2
   * saw it, keyed by the same shape name. The two are different kinds of answer - a decline is
   * no lowering at all, a refusal is a per-lane fallback - and both are pinned (VARKA-329).
   */
  private case class Census(preferredBits: Int, useAVX: Int, conversionsFallBack: Boolean,
      featuresFailure: Option[String], refusals: Map[String, Seq[String]],
      declined: Map[String, String], unattributed: Seq[String])

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
    // VarkaHostIsa's feature query, which keys the converts the invariant expects (VARKA-538).
    command.add("--add-exports=java.base/jdk.internal.vm.vector=ALL-UNNAMED")
    command.add("-XX:+UnlockDiagnosticVMOptions")
    command.add("-Xbatch")
    command.add("-XX:CompileCommand=quiet")
    command.add(s"-XX:CompileCommand=PrintIntrinsics,$CLASS_PREFIX*::*")
    maxVectorSizeBytes.foreach(b => command.add(s"-XX:MaxVectorSize=$b"))
    command.add("-cp")
    command.add(testClasspath)
    command.add(VarkaWidthAuditProbe.getClass.getName.stripSuffix("$"))
    command.add(coverageJson.getAbsolutePath)
    // The child JVM's code runs where the class-use record cannot see it (VarkaTestUse).
    VarkaTestUse.childJvm()
    val builder = new ProcessBuilder(command)
    builder.redirectErrorStream(true)
    val process = builder.start()
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val refusals = mutable.LinkedHashMap.empty[String, mutable.LinkedHashSet[String]]
    val unattributed = mutable.LinkedHashSet.empty[String]
    val declined = mutable.LinkedHashMap.empty[String, String]
    var current: Option[String] = None
    var bits = -1
    var useAVX = VarkaEmitOptions.USE_AVX_UNKNOWN
    var fallsBack: Option[Boolean] = None
    var featuresFailure: Option[Option[String]] = None
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
        VarkaProbeOutput.after(line, CONVERSIONS_FALL_BACK_PREFIX).foreach { v =>
          fallsBack = Some(v.trim.startsWith("true"))
        }
        // The probe folds the failure onto one line and closes it, so a compile record glued
        // after it is left out; a line without the end mark reads as an unknown failure.
        featuresFailureIn(line).foreach(f => featuresFailure = Some(f))
        VarkaProbeOutput.after(line, FEATURES_PREFIX).foreach { f =>
          logInfo(s"the audit probe's JVM reports the features: ${f.trim}")
        }
        VarkaProbeOutput.between(line, SHAPE_BEGIN_PREFIX, SHAPE_NAME_END).foreach { name =>
          current = Some(name)
          refusals.getOrElseUpdate(name, mutable.LinkedHashSet.empty)
        }
        // The compiler's decline belongs to the shape whose markers it sits between, and it is
        // printed instead of a C2 refusal, so no `** ` line follows it.
        VarkaProbeOutput.after(line, DECLINED_PREFIX).foreach { reason =>
          current.foreach(name => declined(name) = reason.trim)
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
    assert(fallsBack.isDefined, "the audit probe never reported whether its converts fall back")
    assert(featuresFailure.isDefined, "the audit probe never reported its feature read")
    Census(bits, useAVX, fallsBack.get, featuresFailure.get,
      refusals.map { case (k, v) => k -> v.toSeq }.toMap, declined.toMap, unattributed.toSeq)
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
   * `VARKA-152.md` 6.5 measured. `missing constant` says an argument was not yet a
   * constant when a *late* inline was first attempted; C2 retries late inlines after further
   * optimisation, so the line is not proof of a fallback in the final code - `i + 1` prints two
   * at 128 bits and the committed 128-bit results show it fully vectorised. `unbox failed`
   * says a vector value reached the call as a heap object, which is VARKA-55's boxing; here it
   * appears only once other kernels have been through the shared templates in the same JVM.
   * The invariant below and the committed census both rest on the first alone: the other two
   * depend on when C2 got to a method in the forked JVM, and the first regenerations after
   * VARKA-153 saw them appear and vanish on shapes nothing had touched, so a file that carried
   * them failed on a quiet tree and blessed whichever timing a regeneration had (VARKA-154).
   */
  private def isRefusal(line: String): Boolean = line.startsWith("not supported")

  /** How a decline is written into the census beside the `not supported` lines. */
  private def declinedLine(reason: String): String = "declined: " + reason

  /**
   * A census's verdict lines per shape: what the file pins and what two runs must agree on. A
   * shape the emitter declined has one line of its own, its reason, and no refusal lines; the
   * two kinds share the map because the table renders a row's width from its shape's entry.
   */
  private def verdicts(census: Census): Map[String, Seq[String]] = {
    val names = census.refusals.keySet ++ census.declined.keySet
    names.map { name =>
      name -> (census.refusals.getOrElse(name, Seq.empty).filter(isRefusal) ++
        census.declined.get(name).map(declinedLine))
    }.toMap
  }

  /**
   * The one refusal a host class is known to have at its own width, and the emitter already
   * knows about: without AVX512DQ there is no vector lowering of the 64-bit lane's conversions
   * to and from double (`L2D`, `D2L`), which is why `VarkaEmitOptions.convertsFallBack` exists
   * and the magic form of the division was built (`VARKA-88.md` 9.2). C2 prints it as a
   * lane cast of four 64-bit lanes: `op=cast#<n>/3 vlen2=4 etype2=double|long ismask=0`.
   * The audit's first CI run confirmed it from the runner pool's EPYC 7763 at 256 bits, in
   * every shape that carries a 64-bit constant division and nowhere else. The invariant
   * expects it where the probe's JVM says its converts fall back - HotSpot's own feature list,
   * read by `VarkaHostIsa` in that JVM (VARKA-538) - rather than failing every AVX2 runner on
   * a fact the design records.
   */
  private def knownConversionRefusal(conversionsFallBack: Boolean, line: String): Boolean =
    conversionsFallBack &&
      line.startsWith("not supported") && line.contains("op=cast#") &&
      line.contains("ismask=0") &&
      (line.contains("etype2=double") || line.contains("etype2=long"))

  /**
   * The declines the committed census records for one width, shape to the reason the file keeps
   * behind its `declined:` prefix, the same shape a live census's `declined` map has. Empty when
   * the file or the width is absent, and empty at 128 bits since the part-form widening load:
   * a TINYINT column there reads a 64-bit byte species and converts it in two parts, so the
   * width declines no shape any more (VARKA-329). The emitter's decline is a function of the
   * shape and the width alone, not of the CPU, so a column another host recorded is the
   * expectation a host of that width is held to.
   */
  private def recordedDeclines(bits: Int): Map[String, String] = {
    val path = getWorkspaceFilePath("sql", "varka", "width_audit.json")
    if (!Files.exists(path)) {
      return Map.empty
    }
    val widths = new ObjectMapper()
      .readTree(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).get("widths")
    Option(widths).flatMap(w => Option(w.get(bits.toString))).map { at =>
      at.fieldNames().asScala.flatMap { shape =>
        at.get(shape).asScala.map(_.asText()).collectFirst {
          case line if line.startsWith("declined: ") => shape -> line.stripPrefix("declined: ")
        }
      }.toMap
    }.getOrElse(Map.empty)
  }

  /**
   * Every shape the committed census names for one width, emitted or declined. Both maps the
   * forced-128-bit test compares are empty since the part-form widening load, so a probe that
   * named no shape at all would agree with the file for the wrong reason; the test holds the
   * live shape set to these names.
   */
  private def recordedShapes(bits: Int): Set[String] = {
    val path = getWorkspaceFilePath("sql", "varka", "width_audit.json")
    if (!Files.exists(path)) {
      return Set.empty
    }
    val widths = new ObjectMapper()
      .readTree(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).get("widths")
    Option(widths).flatMap(w => Option(w.get(bits.toString)))
      .map(at => at.fieldNames().asScala.toSet).getOrElse(Set.empty)
  }

  /**
   * The declines at one width that the committed census does not record there, shape and reason
   * both. The subtraction is one-directional on purpose: a shape the emitter stopped declining
   * is a stale file for the next regeneration, not a failure of a host that cannot compare the
   * whole census, while an unrecorded decline - another shape, another reason, or any decline
   * at a width the file records none for - is exactly the regression the preferred-width test
   * exists to catch. "A decline is admitted only where the census records it" proves both
   * directions; the 128-bit test below applies it to a real four-int-lane census.
   */
  private def unexplainedDeclines(
      declined: Map[String, String], recorded: Map[String, String]): Map[String, String] =
    declined.filter { case (shape, line) => recorded.get(shape) != Some(line) }

  test("at the host's preferred width, every shape is emitted and C2 has a lowering for every " +
      "Vector API call in it, the 64-bit converts below AVX-512 excepted") {
    val census = atPreferred
    // A decline at the preferred width would mean a coverage row the default emission cannot
    // serve on the host it runs on, which no width choice explains - unless the committed
    // census records that decline for this width. No width records one since the part-form
    // widening load (VARKA-329), so every decline fails.
    val recorded = recordedDeclines(census.preferredBits)
    val unexplained = unexplainedDeclines(census.declined, recorded)
    assert(unexplained.isEmpty,
      s"on $cpuModel at ${census.preferredBits} bits (UseAVX ${census.useAVX}), the emitter " +
        s"declined ${census.declined.size} shape(s), and the committed census records " +
        s"${recorded.size} for this width; unexplained:\n" + unexplained.map {
          case (name, reason) => s"  $name: $reason"
        }.mkString("\n"))
    // An x86 probe that could not read its feature list answers "converts" whatever its CPU,
    // so the converts' refusal it may meet is not admitted; that is the unread list, and it is
    // reported as such rather than as a refusal nobody expected.
    census.featuresFailure.filter(_ => VarkaHostIsa.isX86(System.getProperty("os.arch", "")))
      .foreach { failure =>
        // The opt-in magic-form shapes are outside the invariant below, so not here either.
        val converts = census.refusals.filter { case (name, _) => !name.contains(magicForm) }
          .values.flatten
          .filter(o => isRefusal(o) && knownConversionRefusal(conversionsFallBack = true, o))
        assert(converts.isEmpty,
          s"on $cpuModel at ${census.preferredBits} bits (UseAVX ${census.useAVX}): feature " +
            s"list unreadable in the audit probe's JVM ($failure), so the converts' refusal " +
            "below cannot be keyed on its answer; an IllegalAccessException there means " +
            "the fork's --add-exports of java.base/jdk.internal.vm.vector is missing:\n" +
            converts.toSeq.distinct.map(o => s"    ** $o").mkString("\n"))
      }
    val refused = census.refusals.map { case (name, ops) =>
      name -> ops.filter(o =>
        isRefusal(o) && !knownConversionRefusal(census.conversionsFallBack, o))
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
        "empty list is a shape C2 refused nothing in. A shape the compiler declines at a width " +
        "has no call for C2 to judge and is recorded as 'declined: <the compiler's reason>' " +
        "instead. No shape declines for want of a narrow lowering since VARKA-329's part-form " +
        "byte widening load. A predicate row any of whose conjuncts the compiler leaves to the " +
        "row filter, for any reason, reads declined, its reason naming that conjunct where " +
        "the row's other conjuncts fused; on an x86 host whose long species has two lanes the " +
        "compiler refuses every filter condition in a kernel driven at the long lane " +
        "(VARKA-503), so every predicate row with a conjunct over a long column reads " +
        "declined at that width. The census is a " +
        "property of the CPU and the JDK, so the host is part of the record."),
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

  test("a decline is admitted only where the census records it, with its reason") {
    // The preferred-width test's allowance, proved in both directions without needing a
    // 128-bit host: a recorded decline passes, and a new shape, a different reason or an empty
    // record each fail. The reason is the one the file carried for `y1 < y2` at 128 bits before
    // the part-form widening load made that width emit it (VARKA-329).
    val reason = "a BYTE column has no widening load at 4 int lanes"
    val recorded = Map("y1 < y2" -> reason, "y1 = 5Y" -> reason)
    assert(unexplainedDeclines(recorded, recorded).isEmpty)
    assert(unexplainedDeclines(recorded + ("y1 <= y2" -> reason), recorded) ===
      Map("y1 <= y2" -> reason))
    assert(unexplainedDeclines(
      Map("y1 < y2" -> "a narrow column needs a baked int lane count to be widened"),
      recorded).nonEmpty)
    assert(unexplainedDeclines(recorded, Map.empty) === recorded)
  }

  test("a forced 128-bit census declines exactly what the census records for 128 bits") {
    // What the preferred-width test does on a host whose own width is 128 bits, done here on
    // any host that can be asked for it: the real four-int-lane probe against the file's
    // 128-bit column, declines included. A decline is the emitter's verdict, not C2's, so a
    // host with a wider datapath measures what the recording host measured. The comparison is
    // both ways, so a decline the file does not record fails, as does a recorded decline the
    // probe no longer produces - the stale-file direction the one-way subtraction above leaves
    // to the next regeneration. Neither side has a line today: a TINYINT column reads a 64-bit
    // byte species and converts it in two parts at four int lanes, so every audited shape
    // emits there (VARKA-329). Both maps being empty, the shape names carry the test: the probe
    // must have named every shape the column records, or an empty census would agree with it.
    val census = byWidth.toMap.getOrElse(128, cancel(
      "this host cannot be asked for 128-bit vectors, so its 128-bit declines are not measured"))
    val recordedNames = recordedShapes(128)
    val liveNames = census.refusals.keySet ++ census.declined.keySet
    assert(recordedNames.nonEmpty && recordedNames.subsetOf(liveNames),
      "the forced 128-bit probe must name every shape the census records for that width, or " +
        "two empty decline maps agree for the wrong reason; not named by the probe:\n  " +
        (recordedNames -- liveNames).toSeq.sorted.mkString("\n  "))
    val recorded = recordedDeclines(128)
    val unexplained = unexplainedDeclines(census.declined, recorded)
    val stale = unexplainedDeclines(recorded, census.declined)
    assert(unexplained.isEmpty && stale.isEmpty,
      "at 128 bits the emitter and the census disagree on what declines:\n" +
        (unexplained.map { case (name, reason) => s"  declined, not recorded: $name: $reason" } ++
          stale.map { case (name, reason) => s"  recorded, not declined: $name: $reason" })
          .mkString("\n"))
  }

  /**
   * Fails on a feature read that failed for a cause this tree controls, and names that cause.
   * `VarkaHostIsa` keeps the exception's `toString`, so the text starts with its class: an
   * inaccessible method is the missing --add-exports (`fix` says where it belongs) and fails. A
   * garbled probe line, whose failure text is unknown, fails too, before any class is matched in
   * it, since the class there may come from a glued record. Any other cause - a JDK without the
   * method or its class, a native that is not registered - is the JDK's, not the tree's, so the
   * test is cancelled with a log line.
   */
  private def holdFeatureRead(jvm: String, failure: Option[String], fix: String): Unit =
    failure.foreach { text =>
      if (text.startsWith(FEATURES_FAILURE_UNKNOWN)) {
        fail(s"$jvm's feature read cannot be judged: $text")
      } else if (Seq("IllegalAccessException", "InaccessibleObjectException", "does not export")
          .exists(text.contains)) {
        fail(s"$jvm could not read its feature list: the query is not exported to it ($text); " +
          fix)
      } else {
        logInfo(s"$jvm's JDK has no usable HotSpot feature query ($text), so the host's answer " +
          "cannot be checked here")
        cancel(s"$jvm's JDK has no usable HotSpot feature query: $text")
      }
    }

  test("the converts' refusal is admitted only where the host says its converts fall back") {
    // Test 3.5 of VARKA-538: the key is the host's answer, so a fallback host reported as
    // converting must fail the preferred-width invariant on the L2D/D2L line, and a host that
    // falls back must admit it, while any other refusal fails either way.
    val l2d = "not supported: arity=1 op=cast#2/3 vlen2=4 etype2=double ismask=0"
    val other = "not supported: arity=2 op=mul vlen=4 etype=long ismask=0"
    assert(knownConversionRefusal(conversionsFallBack = true, l2d))
    assert(!knownConversionRefusal(conversionsFallBack = false, l2d))
    assert(!knownConversionRefusal(conversionsFallBack = true, other))
    // The probe's own answer keys its census, and on x86 an unread list answers "converts"
    // whatever the CPU, so both JVMs must read it there: the fork through audit()'s
    // --add-exports, this JVM through the build's. A dropped export fails here rather than
    // quietly turning every DQ-less host into one whose converts the census expects to lower.
    val suite = VarkaHostIsa.host()
    if (suite.x86()) {
      holdFeatureRead("the audit probe's JVM", atPreferred.featuresFailure,
        "audit() must pass --add-exports of java.base/jdk.internal.vm.vector")
      holdFeatureRead("this JVM", Option(suite.failure()),
        "the build's test options must export java.base/jdk.internal.vm.vector")
    }
    // The two answers are held to each other only like for like. The fork inherits none of this
    // JVM's -XX options, so a suite run under -XX:UseAVX=2 on an AVX-512 host answers
    // differently from a fork at the host's level; they are compared only at one level.
    if (atPreferred.useAVX == VarkaEmitOptions.HOST_USE_AVX && suite.readable() &&
        atPreferred.featuresFailure.isEmpty) {
      assert(atPreferred.conversionsFallBack === suite.conversionsFallBack(),
        s"at UseAVX ${atPreferred.useAVX} the probe's JVM and the suite's disagree on the " +
          s"converts; the suite read [${suite.features()}]")
    } else {
      logInfo(s"the probe's answer is not compared with this JVM's: the probe ran at UseAVX " +
        s"${atPreferred.useAVX} (read failure: ${atPreferred.featuresFailure}), this JVM at " +
        s"${VarkaEmitOptions.HOST_USE_AVX} (read failure: ${Option(suite.failure())})")
    }
  }
}
