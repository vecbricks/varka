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
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.matching.Regex

import org.apache.spark.SparkFunSuite

/**
 * VARKA-31: assert the instructions, not the ratio.
 *
 * Every vectorization claim this project makes elsewhere is an inference from a throughput
 * ratio - the parity gate's "the emitted loop is within 0.9x of the hand-written kernel"
 * standing in for "C2 intrinsified the Vector API calls". VARKA-24 measured how weak that
 * inference is: the same kernels moved 50-190% under an inline {@code CompileCommand} aimed at
 * the incubating Vector API's package (the flag is quoted in full in {@code VARKA-31.md};
 * it cannot be written here, because Scala comments nest and the pattern contains a slash-star)
 * in the engine's JMH harness, and under 1% under that same flag in the catalyst harness. A
 * ratio moves for reasons that have nothing to do with the instructions emitted. This suite
 * reads the instructions instead.
 *
 * Each case forks a JVM running [[VarkaAssemblyProbe]] under
 * `-XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=print,<class>::<method>`, and asserts that
 * the *standard* (non-OSR) C2 nmethod for that method contains a member of the expected
 * instruction family on a vector register of the width the child reports. See
 * `VARKA-31.md`; the four ways this can go quietly wrong are in `SKILLS.md` beside the
 * hsdis notes.
 *
 * The suite skips, rather than fails, where no disassembler is available - that is the expected
 * state of a CI runner, and a gate that goes red for missing tooling is a gate people delete.
 * It says which of the two happened: no library found, or found and refused to load.
 */
class VarkaAssemblySuite extends SparkFunSuite with VarkaTestWatchdog {

  // --- The families -------------------------------------------------------------------------

  /**
   * A named set of mnemonics that mean the same thing. Never a single mnemonic and never a
   * count: the register class is a property of the host, and VARKA-32's bimodality investigation
   * found identical vector-op counts with a 2x difference in total instructions, so a count
   * assertion would go red on a register-allocation roll with nothing actually wrong.
   */
  private case class Family(
      description: String,
      mnemonics: Set[String],
      pattern: Option[Regex] = None,
      operands: Option[Regex] = None) {
    def matches(insn: Insn): Boolean =
      (mnemonics.contains(insn.mnemonic) || pattern.exists(_.matches(insn.mnemonic))) &&
        operands.forall(_.matches(insn.operands.trim))
  }

  private val packedIntAdd = Family("packed integer add", Set("vpaddd", "paddd"))
  private val packedIntSub = Family("packed integer subtract", Set("vpsubd", "psubd"))
  private val packedIntMul = Family("packed integer multiply", Set("vpmulld", "pmulld"))
  private val packedIntShift = Family("packed integer shift",
    Set("vpsrld", "vpsrad", "vpslld", "psrld", "psrad", "pslld"))
  private val packedLoadStore = Family("packed load/store",
    Set("vmovdqu32", "vmovdqu64", "vmovdqu", "vmovdqa32", "vmovdqa", "movdqu", "movdqa"))

  /**
   * Packed integer compare, as a pattern rather than a list.
   *
   * <p>The list this suite was written with - vpcmpd, vpcmpeqd, vpcmpgtd - matched nothing.
   * AVX-512 folds the predicate into the mnemonic, so {@code a > b} on int lanes comes out as
   * {@code vpcmpnled} (not-less-or-equal), and the full set runs to a dozen suffixes that vary
   * with how C2 chose to spell the comparison. Enumerating them would be a list that goes stale
   * on the next lowering change; the shape {@code [v]pcmp<predicate>d} is the actual family.
   * This is VARKA-31.md prediction 3 landing early, and on a different case than expected.
   */
  private val packedCompare = Family("packed integer compare", Set.empty,
    Some("""^v?pcmp[a-z]*d$""".r))

  /** An index-map gather - {@code IntVector.fromArray} with an index array - and the shape task
   *  55's allocation self-test is calibrated on. */
  private val packedGather = Family("packed gather",
    Set("vpgatherdd", "vpgatherqd", "vpgatherdq", "vgatherdps"))

  /**
   * A heap allocation site, by the two instructions C2 emits for nothing else (VARKA-55). These
   * are the *diagnosis* printed with an allocation failure, not the assertion: a static count
   * cannot tell a per-call setup object from a per-iteration box (see {@link #assertNoBoxing}).
   *
   * <p>An inline TLAB allocation bumps the thread's top pointer, prefetches the lines past it
   * ({@code prefetchnta}, three of them under the default {@code AllocatePrefetchLines}) and
   * stores the prototype mark word, {@code movq $1, (%reg)}, into the new object's first word.
   * Neither instruction appears in a Varka loop for any other reason: the kernels carry no
   * software prefetch, and no other store writes the literal 1 to an object's base. Both are
   * matched so that a JVM configured without allocation prefetch, or with compact object headers
   * (whose header store is a different constant), still trips the other half.
   *
   * <p>Why a loop body would allocate at all: a Vector API value gets boxed when the shared
   * {@code IntVector} templates inline bimorphically, which happens once a second species of the
   * same lane type has been through them in the same JVM ({@code SKILLS.md}, "Every operator the
   * plans rely on"). The packed instructions stay in place, so every positive family assertion
   * in this suite still passes; only the allocation assertion sees it.
   */
  private val allocationPrefetch = Family("allocation prefetch",
    Set("prefetchnta", "prefetcht0", "prefetcht1", "prefetcht2", "prefetchw"))
  private val markWordStore = Family("mark-word store", Set("movq"),
    operands = Some("^\\$1,\\s*\\(%r[a-z0-9]+\\)$".r))

  /**
   * What a body looks like when the intrinsic did not fire. Reported alongside a miss, because
   * "0 packed adds, 47 scalar adds" is a diagnosis and "assertion failed" is not.
   */
  private val scalarIntAdd = Family("scalar integer add", Set("addl", "addq", "leal", "leaq"))

  /** x86-64 is the only table filled in; see `VARKA-31.md` 3.1 for why none is invented. */
  private val supportedArches = Set("amd64", "x86_64")

  // --- Locating a disassembler --------------------------------------------------------------

  private sealed trait Hsdis
  /** A directory holding `hsdis-<arch>.so`, to be passed to the child as `LD_LIBRARY_PATH`. */
  private case class OnLibraryPath(dir: File) extends Hsdis
  /** `libhsdis.so` already sits beside `libjvm.so`, where HotSpot finds it unaided. */
  private case object BesideLibjvm extends Hsdis
  private case object NoHsdis extends Hsdis

  private lazy val arch: String = System.getProperty("os.arch")

  /**
   * The machine a failure is read against: the CPU model and the processors the JVM sees. A
   * runner is one draw from a pool of CPU families and neighbours (VARKA-150), so a message that
   * does not say which machine it came from cannot be compared with the next one.
   */
  private lazy val host: String = {
    val model = Try(Files.readAllLines(Paths.get("/proc/cpuinfo")).asScala
      .find(_.startsWith("model name")).map(_.dropWhile(_ != ':').drop(1).trim))
      .toOption.flatten.getOrElse("unknown CPU")
    s"$model, ${Runtime.getRuntime.availableProcessors} processors"
  }

  private lazy val hsdis: Hsdis = {
    val libName = s"hsdis-$arch.so"
    val candidates: Seq[File] =
      Option(System.getProperty("varka.hsdis.dir")).map(new File(_)).toSeq ++
        Option(System.getenv("VARKA_HSDIS_DIR")).map(new File(_)).toSeq ++
        Option(System.getenv("LD_LIBRARY_PATH")).toSeq
          .flatMap(_.split(File.pathSeparatorChar).filter(_.nonEmpty).map(new File(_)))
    val besideLibjvm = new File(new File(System.getProperty("java.home"), "lib"), "server")
    candidates.find(d => new File(d, libName).isFile) match {
      case Some(dir) => OnLibraryPath(dir)
      case None if new File(besideLibjvm, "libhsdis.so").isFile => BesideLibjvm
      case None => NoHsdis
    }
  }

  // --- Running the child --------------------------------------------------------------------

  /** Everything a case needs from one child run. */
  private case class ProbeRun(output: String, exitCode: Int) {

    /** The child's own `IntVector.SPECIES_PREFERRED` width - the flags in force decide it. */
    def preferredBits: Int = {
      longAfter(VarkaAssemblyProbe.PREFERRED_BITS_PREFIX).toInt
    }

    def ranToCompletion: Boolean =
      output.linesIterator.exists(VarkaProbeOutput.has(_, VarkaAssemblyProbe.DONE_PREFIX))

    // Markers are found anywhere in a line, since a compiler thread's record can share it
    // (`VarkaProbeOutput`).
    private def longAfter(prefix: String): Long =
      output.linesIterator.flatMap(VarkaProbeOutput.longAfter(_, prefix)).nextOption()
        .getOrElse(fail(s"the probe printed no '$prefix' line; output:\n${tail(output)}"))

    /** Heap bytes the child allocated per call of the method under test, at steady state. */
    def allocBytesPerCall: Long = longAfter(VarkaAssemblyProbe.ALLOC_BYTES_PER_CALL_PREFIX)

    /** Rows one call processes, so the above can be read per row. */
    def rowsPerCall: Long = longAfter(VarkaAssemblyProbe.ROWS_PER_CALL_PREFIX)
  }

  /** Cap on captured output. `print` over one method is a few thousand lines; a mistaken
   *  pattern that matches every method is not, and buffering that without bound would hang the
   *  suite rather than fail it. */
  private val maxOutputChars = 8 * 1024 * 1024

  private val childTimeoutSeconds = 300L

  private def testClasspath: String =
    Option(System.getenv("SPARK_DIST_CLASSPATH"))
      .filter(_.nonEmpty)
      .getOrElse(System.getProperty("java.class.path"))

  private def runProbe(
      caseName: String, printPattern: String, extraFlags: Seq[String] = Seq.empty): ProbeRun = {
    val javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val command = new java.util.ArrayList[String]()
    command.add(javaBin.getAbsolutePath)
    command.add("--add-modules")
    command.add("jdk.incubator.vector")
    // The kernels and the emitted loops read off-heap memory through MemorySegment.reinterpret,
    // which is a restricted method: without this the child prints a warning per call site.
    command.add("--enable-native-access=ALL-UNNAMED")
    command.add("-XX:+UnlockDiagnosticVMOptions")
    // The probe warms a method by calling it a fixed number of times, and that count buys a
    // compile request, not a compile: with background compilation the calls can run out before
    // the compiler thread gets a core, and the parent then reads a C1 body, or a C2 body compiled
    // early enough to be the Vector API's scalar fallback. That is what the gate's failures on
    // contended runners were (VARKA-150), and pinning sbt to two busy cores reproduces them. With
    // -Xbatch the crossing call returns with the nmethod installed, on any machine.
    command.add("-Xbatch")
    command.add(s"-XX:CompileCommand=print,$printPattern")
    extraFlags.foreach(command.add)
    command.add("-cp")
    command.add(testClasspath)
    command.add(classOf[VarkaAssemblyProbe].getName)
    command.add(caseName)

    val builder = new ProcessBuilder(command)
    builder.redirectErrorStream(true)
    hsdis match {
      case OnLibraryPath(dir) =>
        val existing = Option(System.getenv("LD_LIBRARY_PATH")).getOrElse("")
        val value =
          if (existing.isEmpty) dir.getAbsolutePath
          else s"${dir.getAbsolutePath}${File.pathSeparator}$existing"
        builder.environment().put("LD_LIBRARY_PATH", value)
      case _ =>
    }

    val process = builder.start()
    val captured = new StringBuilder
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    try {
      var line = reader.readLine()
      var overflowed = false
      while (line != null && !overflowed) {
        captured.append(line).append('\n')
        if (captured.length > maxOutputChars) { overflowed = true }
        line = reader.readLine()
      }
      if (overflowed) {
        process.destroyForcibly()
        fail(s"the probe printed more than $maxOutputChars characters for pattern " +
          s"'$printPattern' - the CompileCommand pattern is probably matching every method")
      }
    } finally {
      reader.close()
    }
    if (!process.waitFor(childTimeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      fail(s"the probe did not finish within $childTimeoutSeconds s for case '$caseName'")
    }
    ProbeRun(captured.toString, process.exitValue())
  }

  // --- Reading the disassembly ----------------------------------------------------------------

  private case class Insn(mnemonic: String, operands: String)

  private case class Nmethod(tier: String, osr: Boolean, method: String, insns: Seq[Insn])

  private val nmethodHeader = """^=+\s*(C[12])-compiled nmethod\s*=+$""".r
  private val compiledMethod = """^Compiled method \((c[12])\)\s+(.*)$""".r
  private val methodName = """([\w$./]+::[\w$<>]+)""".r
  /**
   * An instruction line: an address, a colon, a mnemonic beginning with a letter, and operands.
   *
   * <p>This pattern alone is not enough to tell an instruction from a hex dump, which is the
   * point of {@link #disassemblyOpen} below. With no disassembler HotSpot does not stay silent:
   * it prints the nmethod under a `[MachCode]` header as raw hex words - `0x...: ff1f 0045 |
   * 85c9 0f84 | ...` - and those lines have the same address-colon shape. Requiring a letter
   * does not help either, since plenty of hex words begin with one (`ff1f`, `e929`, `c349`).
   * Measured rather than assumed: 68 such lines parsed as instructions before the section
   * marker was used, and the suite reported "the intrinsic did not fire" on a body it had never
   * actually read.
   */
  private val insnLine = """^\s*0x[0-9a-fA-F]+:\s+([a-z][a-zA-Z0-9._]*)\s*(.*)$""".r

  /** hsdis produced real instructions for this nmethod. */
  private val disassemblyMarker = "[Disassembly]"

  /** HotSpot fell back to a raw hex dump, because no disassembler was usable. */
  private val machCodeMarker = "[MachCode]"

  /**
   * Split the child's output into nmethods.
   *
   * HotSpot prints a C1 nmethod for the same method alongside the C2 one, and C1's body is
   * scalar by construction - reading the concatenated text would find scalar instructions in a
   * method that vectorized perfectly. It also prints OSR compilations, which are not what runs
   * in production. Both are separated here rather than filtered at the call site, so no case can
   * forget to.
   */
  private def parseNmethods(output: String): Seq[Nmethod] = {
    val result = ArrayBuffer.empty[Nmethod]
    var tier: Option[String] = None
    var method = ""
    var osr = false
    var disassembled = false
    var insns = ArrayBuffer.empty[Insn]

    def flush(): Unit = {
      tier.foreach(t => result += Nmethod(t, osr, method, insns.toSeq))
      tier = None
      method = ""
      osr = false
      disassembled = false
      insns = ArrayBuffer.empty[Insn]
    }

    output.linesIterator.foreach { line =>
      line match {
        case nmethodHeader(t) =>
          flush()
          tier = Some(t.toLowerCase(java.util.Locale.ROOT))
        case compiledMethod(_, rest) if tier.isDefined =>
          // HotSpot marks an on-stack-replacement compilation with '%' in this line.
          osr = rest.contains("%")
          method = methodName.findFirstIn(rest).getOrElse("")
        case insnLine(mnemonic, operands) if tier.isDefined && disassembled =>
          insns += Insn(mnemonic, operands)
        case _ =>
      }
      // Checked after the match so the marker's own line is never taken for an instruction.
      if (line.contains(disassemblyMarker)) { disassembled = true }
      else if (line.contains(machCodeMarker)) { disassembled = false }
    }
    flush()
    result.toSeq
  }

  /** The standard, non-OSR C2 compilation of `method`, or a failure that says which of the
   *  several distinguishable things went wrong instead. */
  private def standardC2(run: ProbeRun, method: String): Nmethod = {
    val all = parseNmethods(run.output)
    val forMethod = all.filter(_.method.endsWith(method))
    assert(forMethod.nonEmpty,
      s"HotSpot printed no nmethod for $method; it may never have been compiled. " +
        s"nmethods seen: ${all.map(n => s"${n.tier}${if (n.osr) "-osr" else ""} ${n.method}")
          .mkString(", ")}")
    forMethod.find(n => n.tier == "c2" && !n.osr).getOrElse {
      fail(s"no standard (non-OSR) C2 nmethod for $method; saw " +
        forMethod.map(n => s"${n.tier}${if (n.osr) "-osr" else ""}").mkString(", ") +
        s" (host: $host)")
    }
  }

  // --- The assertion -------------------------------------------------------------------------

  /** `zmm`, `ymm` or `xmm`, from the width the child actually ran at. */
  private def registerClass(bits: Int): String = bits match {
    case 512 => "zmm"
    case 256 => "ymm"
    case 128 => "xmm"
    case other => cancel(s"no register class known for a preferred vector width of $other bits")
  }

  private def countIn(nmethod: Nmethod, family: Family, regClass: Option[String]): Int =
    nmethod.insns.count { insn =>
      family.matches(insn) && regClass.forall(cls => insn.operands.contains(s"%$cls"))
    }

  private def assertHasFamily(nmethod: Nmethod, family: Family, regClass: String): Unit = {
    val found = countIn(nmethod, family, Some(regClass))
    if (found == 0) {
      val scalar = countIn(nmethod, scalarIntAdd, None)
      fail(s"${nmethod.method}: expected at least one ${family.description} on a %$regClass " +
        s"register and found none, in ${nmethod.insns.size} instructions " +
        s"(${scalar} ${scalarIntAdd.description}). The intrinsic did not fire, or this body " +
        s"came out scalar (host: $host).")
    }
  }

  private def assertLacksFamily(nmethod: Nmethod, family: Family): Unit = {
    val found = countIn(nmethod, family, None)
    assert(found == 0,
      s"${nmethod.method}: expected no ${family.description} anywhere in this body and found " +
        s"$found - the case is supposed to be unvectorizable, so either it is not or the " +
        s"detector is matching the wrong thing")
  }

  private def allocationSites(nmethod: Nmethod): String = {
    val prefetches = countIn(nmethod, allocationPrefetch, None)
    val markStores = countIn(nmethod, markWordStore, None)
    s"$prefetches allocation prefetches and $markStores mark-word stores in " +
      s"${nmethod.insns.size} instructions"
  }

  /**
   * The line between a per-call setup object and a per-iteration box, in bytes per row.
   *
   * <p>A boxed vector is a fresh object per lane group - at least 80 bytes for the payload alone,
   * so 5 bytes per row at 16 lanes and 20 at 4 - and the probe measured 10 to 16 bytes per row
   * on its polluted case. A {@code MemorySegment} view C2 failed to scalar-replace is one object
   * per call over 1024 rows, under 0.25 bytes per row even four times over. One byte per row
   * sits between them with a margin of at least 5x on either side.
   */
  private val maxBytesPerRow = 1.0

  /**
   * VARKA-55's negative assertion: the compiled body does not allocate per lane group. A kernel
   * loop that boxes a vector keeps every packed instruction and loses 3-13x (the numbers behind
   * that are in {@code SKILLS.md}), so this is the one failure the positive families cannot see.
   *
   * <p>Measured, not read off the disassembly: the probe reports heap bytes per call at steady
   * state. A static count of allocation sites cannot make this distinction - the hand-written
   * kernels carry per-call segment views C2 leaves in place, and an allocation's slow path
   * jumps backwards to its retry point, so "inside a backward branch" is not "inside the loop".
   * The static sites are printed as the diagnosis when the rate fails.
   */
  private def assertNoBoxing(run: ProbeRun, nmethod: Nmethod): Unit = {
    val bytes = run.allocBytesPerCall
    val rows = run.rowsPerCall
    val perRow = bytes.toDouble / rows
    info(f"${nmethod.method}: $bytes bytes allocated per call of $rows rows ($perRow%.2f/row)")
    assert(perRow <= maxBytesPerRow,
      f"${nmethod.method}: allocates $bytes bytes per call of $rows rows, $perRow%.1f bytes " +
        f"per row against a ceiling of $maxBytesPerRow%.1f - a Vector API value is being " +
        s"boxed per lane group (${allocationSites(nmethod)}). The usual cause is a second " +
        "species of the same lane type having been through the shared templates in this JVM; " +
        "run the child with -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining and look for " +
        "'callee changed to' lines naming a second species class (SKILLS.md, 'Every operator " +
        "the plans rely on')." +
        s" (host: $host)")
  }

  /** The self-test's positive half: the measurement sees the box where one is known to be. */
  private def assertBoxes(run: ProbeRun, nmethod: Nmethod): Unit = {
    val bytes = run.allocBytesPerCall
    val rows = run.rowsPerCall
    val perRow = bytes.toDouble / rows
    info(f"${nmethod.method}: $bytes bytes allocated per call of $rows rows ($perRow%.2f/row)")
    assert(perRow > maxBytesPerRow,
      f"${nmethod.method}: expected the polluted body to box and measured $bytes bytes per " +
        f"call of $rows rows ($perRow%.2f per row; ${allocationSites(nmethod)}) - either the " +
        "pollution did not take (C2 inlined the templates monomorphically after all) or the " +
        "measurement is not seeing the body under test")
  }

  // --- Preconditions -------------------------------------------------------------------------

  /**
   * A child that failed to start or died is a bug in this suite, not missing tooling, so it
   * fails rather than cancels - and it is checked before the disassembler is, because a JVM that
   * refused its own flags produces no disassembly either and would otherwise be reported as a
   * missing hsdis. That is not hypothetical: it is how the `::` pattern bug below was first
   * mis-reported.
   */
  private def requireHealthyChild(run: ProbeRun): Unit = {
    assert(run.exitCode == 0,
      s"the probe exited with ${run.exitCode}; output:\n${tail(run.output)}")
    assert(run.ranToCompletion, s"the probe did not finish:\n${tail(run.output)}")
  }

  /**
   * Skip, with a message that distinguishes the two ways a disassembler can be unavailable.
   *
   * <p>The discrimination is made on this suite's own search result, not on HotSpot's
   * "Loading hsdis library failed" line. That line is printed in *both* cases - HotSpot says it
   * when it looked and found nothing just as much as when it found a library it could not load
   * - so keying off it reported "a disassembler was found but HotSpot refused to load it
   * (hsdis: NoHsdis)", which contradicts itself. A wrong skip message is the same class of
   * defect as a detector that matches nothing: the suite still goes green and still tells the
   * reader something false.
   */
  /**
   * Whether a missing disassembler is a failure rather than a skip (VARKA-124).
   *
   * <p>Skipping is right on a developer's machine and on a general CI runner, and the class doc
   * above says why: a gate that goes red for missing tooling is a gate people delete. It is
   * exactly wrong in the one job that exists to run these assertions. That job builds hsdis
   * first - `dev/varka_hsdis_build.sh`, six seconds - so if the disassembler is missing there,
   * the build step failed silently and every case below would cancel while the job went green,
   * asserting nothing and looking identical to a job that checked everything.
   *
   * <p>So the job sets this, and nothing else does.
   */
  private lazy val hsdisRequired: Boolean =
    Option(System.getenv("VARKA_HSDIS_REQUIRED")).exists(_.equalsIgnoreCase("true")) ||
      System.getProperty("varka.hsdis.required", "").equalsIgnoreCase("true")

  /** Cancel, or fail where the caller declared a disassembler must be present. */
  private def cancelOrFail(why: String): Nothing =
    if (hsdisRequired) {
      fail(s"VARKA_HSDIS_REQUIRED is set, so this suite must not skip: $why")
    } else {
      cancel(why)
    }

  private def requireDisassembler(run: ProbeRun): Unit = {
    if (parseNmethods(run.output).exists(_.insns.nonEmpty)) { return }
    hsdis match {
      case NoHsdis =>
        cancelOrFail("no disassembler found: none of -Dvarka.hsdis.dir, VARKA_HSDIS_DIR, " +
          s"LD_LIBRARY_PATH holds hsdis-$arch.so, and <java.home>/lib/server holds no " +
          "libhsdis.so. This is the expected state of a CI runner; see SKILLS.md on " +
          "building hsdis.")
      case found =>
        val refused =
          if (run.output.contains("Loading hsdis library failed")) {
            " and HotSpot printed 'Loading hsdis library failed'"
          } else {
            ""
          }
        cancelOrFail(s"a disassembler was found ($found) but no disassembly came back$refused; " +
          "see SKILLS.md on building hsdis")
    }
  }

  private def requireSupportedArch(): Unit = {
    assume(supportedArches.contains(arch),
      s"no instruction-family table for $arch; see VARKA-31.md 3.1")
  }

  private def tail(s: String): String = s.linesIterator.toSeq.takeRight(40).mkString("\n")

  /**
   * HotSpot's method pattern accepts `package/class.method` or `package.class::method` and
   * rejects the two spellings mixed: a slashed package with `::` fails VM startup outright with
   * "Method pattern uses '/' together with '::'". `VARKA-31.md` 4.3 wrote the slashed form
   * with `::`, and the self-test is what caught it - the child never started, so there was no
   * disassembly to read. The dotted form is used throughout.
   */
  private val probeClass =
    "org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAssemblyProbe"

  // --- The self-test -------------------------------------------------------------------------

  // This pair comes before any Varka shape on purpose. A mnemonic pattern that matches nothing
  // looks exactly like a body with no vector instructions, so without a case that must find
  // something and a case that must find nothing, every other assertion in this suite can pass
  // vacuously. SKILLS.md records the concrete way this nearly happened: this hsdis build
  // separates the mnemonic from its operands with tabs, and a regex written for spaces matches
  // no instruction at all.

  test("self-test: an explicit IntVector loop compiles to packed instructions") {
    requireSupportedArch()
    val run = runProbe("vectorAdd", s"$probeClass::vectorAdd")
    requireHealthyChild(run)
    requireDisassembler(run)
    val nmethod = standardC2(run, "VarkaAssemblyProbe::vectorAdd")
    val cls = registerClass(run.preferredBits)
    assertHasFamily(nmethod, packedIntAdd, cls)
    assertHasFamily(nmethod, packedLoadStore, cls)
  }

  test("self-test: a loop-carried recurrence compiles to no packed instructions") {
    requireSupportedArch()
    val run = runProbe("scalarChain", s"$probeClass::scalarChain")
    requireHealthyChild(run)
    requireDisassembler(run)
    val nmethod = standardC2(run, "VarkaAssemblyProbe::scalarChain")
    assertLacksFamily(nmethod, packedIntAdd)
    // ... and the body is not empty, so "found none" above is a fact about the body rather than
    // about the parser having produced nothing to look at.
    assert(nmethod.insns.size > 20,
      s"only ${nmethod.insns.size} instructions parsed - the parser, not the body, is the " +
        "thing under test here")
  }

  test("the narrow-width run reports a narrower register class") {
    requireSupportedArch()
    val run = runProbe("vectorAdd", s"$probeClass::vectorAdd", Seq("-XX:MaxVectorSize=16"))
    requireHealthyChild(run)
    requireDisassembler(run)
    val bits = run.preferredBits
    assert(bits == 128,
      s"-XX:MaxVectorSize=16 should give a 128-bit preferred species, got $bits")
    val nmethod = standardC2(run, "VarkaAssemblyProbe::vectorAdd")
    assertHasFamily(nmethod, packedIntAdd, registerClass(bits))
  }

  // The allocation assertion's own pair (VARKA-55), for the same reason as the pair above: a
  // measurement that reads zero for everything is indistinguishable from a body that allocates
  // nothing. The clean case must be clean; the polluted case, which differs only in having run
  // the same loop over a second int species in the same JVM, must box.

  private def assertGatherClean(extraFlags: Seq[String]): Unit = {
    requireSupportedArch()
    val run = runProbe("gatherLookup", s"$probeClass::gatherLookup", extraFlags)
    requireHealthyChild(run)
    requireDisassembler(run)
    val nmethod = standardC2(run, "VarkaAssemblyProbe::gatherLookup")
    assertHasFamily(nmethod, packedGather, registerClass(run.preferredBits))
    assertNoBoxing(run, nmethod)
  }

  private def assertGatherBoxesWhenPolluted(extraFlags: Seq[String]): Unit = {
    requireSupportedArch()
    val run = runProbe("gatherLookupPolluted", s"$probeClass::gatherLookup", extraFlags)
    requireHealthyChild(run)
    requireDisassembler(run)
    val nmethod = standardC2(run, "VarkaAssemblyProbe::gatherLookup")
    // The packed instructions are still there - that is the whole point.
    assertHasFamily(nmethod, packedGather, registerClass(run.preferredBits))
    assertBoxes(run, nmethod)
  }

  test("self-test: a gather compiles to a gather and boxes nothing") {
    assertGatherClean(Seq.empty)
  }

  test("self-test: the same gather boxes once a second species has been through the templates") {
    assertGatherBoxesWhenPolluted(Seq.empty)
  }

  /**
   * Whether this JVM compiles the probe's own gather to a packed gather at a forced 128-bit
   * species, read once. Some hosts do not, and a machine whose own probe does not pack at 128
   * bits cannot say whether the kernels do, so the 128-bit assertions below take this as their
   * precondition and cancel, naming the host, where it fails. The default-width assertions stay
   * hard, because the property they rest on is Varka's rather than the JVM's.
   *
   * <p>The precondition holds for one known reason and cancels only for that reason: C2's late
   * inlining of `VectorSupport::loadWithMap` gives up on at least one call site, the fallback
   * is inlined in its place, and the loop comes out scalar with no call to show for it. A
   * scalar body without that signature is something else, and fails rather than cancels so the
   * job cannot go green over it. See `VARKA-165.md` section 6.
   */
  private lazy val narrowSpeciesRefusal: Option[String] = {
    // -XX:+PrintIntrinsics prints one `**` line per refused intrinsic and -XX:+PrintInlining
    // one line per inlining decision. Both are quoted into the message below, but only the
    // inlining decisions discriminate: a packing host and a refusing one print the same three
    // `missing constant` lines and neither prints `not supported`, so the intrinsic is not
    // being refused by the matcher on either.
    val run = runProbe("gatherLookup", s"$probeClass::gatherLookup",
      Seq("-XX:MaxVectorSize=16", "-XX:+PrintIntrinsics", "-XX:+PrintInlining"))
    requireHealthyChild(run)
    requireDisassembler(run)
    val nmethod = standardC2(run, "VarkaAssemblyProbe::gatherLookup")
    if (countIn(nmethod, packedGather, Some(registerClass(run.preferredBits))) > 0) {
      None
    } else {
      val refusals = run.output.linesIterator.map(_.trim).filter(_.startsWith("** "))
        .toSeq.distinct.take(12)
      val why = if (refusals.isEmpty) "C2 printed no intrinsic refusal for it"
        else "C2's own refusals:\n  " + refusals.mkString("\n  ")
      val inlining = run.output.linesIterator.map(_.trim)
        .filter(_.contains("VectorSupport::loadWithMap")).toSeq.distinct.take(6)
      val how = if (inlining.isEmpty) "PrintInlining named no loadWithMap decision"
        else "the gather's inlining decisions:\n  " + inlining.mkString("\n  ")
      val calls = nmethod.insns.count(_.mnemonic == "call")
      val body = s"on this machine ($host) C2 compiles the probe's own gather at a forced " +
        s"128-bit species to a scalar body of ${nmethod.insns.size} instructions with " +
        s"$calls calls. $why. $how."
      // The precondition is keyed on the one cause row 165 established, not on "it came out
      // scalar": a host whose gather goes scalar for some other reason is a finding, and a
      // blanket cancel would hide it behind a green job. The discriminator is the inlining
      // decisions, since the intrinsic refusals are printed by packing and refusing hosts
      // alike; see VARKA-165.md section 6.
      if (inlining.exists(_.contains("failed to inline"))) {
        Some(s"$body C2's late inlining of VectorSupport::loadWithMap gives up on at least " +
          "one call site here, so the fallback is inlined in its place and the loop comes " +
          "out scalar with no call left to show for it. The 128-bit assertions are not " +
          "evidence on such a host; the default-width ones still run. VARKA-165.md " +
          "section 6 has the decisions this was read from.")
      } else {
        cancelOrFail(s"$body Every loadWithMap decision inlined, so row 165's cause does not " +
          "explain this scalar body and something else does. VARKA-165.md section 6 is " +
          "the reading it contradicts.")
      }
    }
  }

  /** Cancel a 128-bit assertion where the probe's own gather does not pack at that species. */
  private def requireNarrowSpecies(): Unit = narrowSpeciesRefusal.foreach(reason => cancel(reason))

  test("self-test: the allocation pair holds at 128-bit lanes") {
    requireNarrowSpecies()
    val narrow = Seq("-XX:MaxVectorSize=16")
    assertGatherClean(narrow)
    assertGatherBoxesWhenPolluted(narrow)
  }

  // --- The kernels ---------------------------------------------------------------------------

  // These come before the emitted loops on purpose. DateVectorOps and ChronoVectorOps are the
  // hand-written references the emitter is measured against, and their shapes are fixed; if one
  // of them stops vectorizing, an emitted loop's regression is not attributable to the emitter.

  /** One case, end to end: fork, require a healthy child and a disassembler, take the standard
   *  C2 nmethod for `method`, and assert every family on the host's register class. */
  private def assertFamilies(
      probeCase: String,
      pattern: String,
      method: String,
      families: Seq[Family],
      extraFlags: Seq[String] = Seq.empty): Unit = {
    requireSupportedArch()
    val run = runProbe(probeCase, pattern, extraFlags)
    requireHealthyChild(run)
    requireDisassembler(run)
    val nmethod = standardC2(run, method)
    val cls = registerClass(run.preferredBits)
    families.foreach(family => assertHasFamily(nmethod, family, cls))
    assertNoBoxing(run, nmethod)
  }

  private val dateVectorOps = "org.apache.spark.sql.varka.vector.DateVectorOps"
  private val chronoVectorOps = "org.apache.spark.sql.varka.vector.ChronoVectorOps"

  /** Production names a generated class `VarkaFusedProjection_<shape hash>`, so the pattern
   *  wildcards exactly where it has to in production. */
  private val emittedLoop = "org.apache.spark.sql.varka.execution.VarkaFusedProjection_*"

  test("DateVectorOps.vectorAddDays is packed") {
    assertFamilies("dateAddDays", s"$dateVectorOps::vectorAddDays",
      "DateVectorOps::vectorAddDays", Seq(packedLoadStore, packedIntAdd))
  }

  test("ChronoVectorOps.vectorFourFields is packed, magic multiplies and all") {
    // The whole civil-from-days decomposition: if the multiplies or the shifts came out scalar,
    // the calendar lowering's entire case would be resting on a throughput coincidence.
    assertFamilies("chronoFourFields", s"$chronoVectorOps::vectorFourFields",
      "ChronoVectorOps::vectorFourFields",
      Seq(packedLoadStore, packedIntAdd, packedIntSub, packedIntMul, packedIntShift))
  }

  // --- The emitted loops ---------------------------------------------------------------------

  test("the emitted year loop is packed") {
    assertFamilies("emittedYear", s"$emittedLoop::loopDense0",
      "VarkaFusedProjection_emittedYear::loopDense0",
      Seq(packedLoadStore, packedIntAdd, packedIntMul, packedIntShift))
  }

  test("the emitted dayofweek loop is packed") {
    // VARKA-14's range-narrowed magic: a multiply and a shift standing in for a division. This is
    // the shape whose whole point is that it avoids a scalar remainder, so a scalar body here
    // would mean the lowering bought nothing.
    assertFamilies("emittedDayOfWeek", s"$emittedLoop::loopDense0",
      "VarkaFusedProjection_emittedDayOfWeek::loopDense0",
      Seq(packedLoadStore, packedIntAdd, packedIntMul, packedIntShift))
  }

  test("the emitted comparison loop produces packed compares, not branches") {
    // The one shape where a scalar fallback would be invisible in a throughput ratio: a
    // per-lane branch on a predictable comparison can run at a speed that looks fine.
    assertFamilies("emittedCompare", s"$emittedLoop::loopDense0",
      "VarkaFusedProjection_emittedCompare::loopDense0",
      Seq(packedLoadStore, packedCompare))
  }

  // --- Both widths ----------------------------------------------------------------------------

  test("every shape is still packed at 128-bit lanes") {
    // The case that catches a hard-coded zmm, and the one VARKA-31.md prediction 3 expects
    // to break first: xmm is also what scalar SSE uses for some operations, so a family table
    // that works by symmetry from the wide run is not good enough.
    requireNarrowSpecies()
    val narrow = Seq("-XX:MaxVectorSize=16")
    assertFamilies("dateAddDays", s"$dateVectorOps::vectorAddDays",
      "DateVectorOps::vectorAddDays", Seq(packedLoadStore, packedIntAdd), narrow)
    assertFamilies("emittedYear", s"$emittedLoop::loopDense0",
      "VarkaFusedProjection_emittedYear::loopDense0",
      Seq(packedLoadStore, packedIntAdd, packedIntMul, packedIntShift), narrow)
    assertFamilies("emittedCompare", s"$emittedLoop::loopDense0",
      "VarkaFusedProjection_emittedCompare::loopDense0",
      Seq(packedLoadStore, packedCompare), narrow)
  }

  // --- Forcing C2 to inline Varka's own packages (VARKA-31.md 7) --------------------------

  /**
   * VARKA-24 measured the JDK half of this question - an inline directive aimed at the incubating
   * Vector API - and found a 50-190% swing in the engine's JMH harness against under 1% in the
   * catalyst one, which turned out to be a fact about JMH rather than about Varka. This is the
   * other half: the same directive aimed at Varka's own packages and at the emitted classes'.
   */
  private val inlineVarka = Seq(
    "-XX:CompileCommand=inline,org.apache.spark.sql.varka.*::*",
    "-XX:CompileCommand=inline,org.apache.spark.sql.catalyst.expressions.codegen.varka.*::*")

  test("forcing C2 to inline Varka's packages leaves the emitted loop body vectorized") {
    // The assertion is deliberately the weak one - the families are still there - because what
    // this pair is really for is the finding recorded in VARKA-31.md 13, and an equality
    // assertion over instruction counts across two configurations is exactly the brittle shape
    // this suite refuses elsewhere: a register-allocation roll would fail it with nothing wrong.
    val families = Seq(packedLoadStore, packedIntAdd, packedIntMul, packedIntShift)
    assertFamilies("emittedYear", s"$emittedLoop::loopDense0",
      "VarkaFusedProjection_emittedYear::loopDense0", families)
    assertFamilies("emittedYear", s"$emittedLoop::loopDense0",
      "VarkaFusedProjection_emittedYear::loopDense0", families, inlineVarka)
  }

  test("forcing C2 to inline Varka's packages leaves the hand-written kernel vectorized") {
    val families = Seq(packedLoadStore, packedIntAdd, packedIntSub, packedIntMul, packedIntShift)
    assertFamilies("chronoFourFields", s"$chronoVectorOps::vectorFourFields",
      "ChronoVectorOps::vectorFourFields", families, inlineVarka)
  }
}
