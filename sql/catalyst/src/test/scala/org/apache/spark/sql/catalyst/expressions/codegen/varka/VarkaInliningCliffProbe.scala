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

import java.lang.foreign.{Arena, ValueLayout}
import java.lang.management.ManagementFactory
import java.nio.file.Files
import javax.management.ObjectName

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection,
  VarkaExpressionCompiler, VarkaGeneratedClassLoader}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.isLoop
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.types.DateType

/**
 * The child process behind `dev/varka_inlining_cliff.sh` and [[VarkaInliningCliffSuite]]
 * (VARKA-209): does a kernel's loop method keep its vector intrinsics, or run as the Vector API's
 * scalar fallback, and does the same class land on either side from one JVM to the next?
 *
 * It emits VARKA-198's cheap-tail shape - `year(d) + k` for `k` up to the output count, over one
 * date column - at the given fused ceiling, which decides how many loop methods the outputs are
 * grouped into (400 puts sixty-four in one group of 225 `IntVector` call sites, 100 in two, 50 in
 * six), drives the kernel over null-free batches for a number of seconds, and prints its rate
 * every second. The verdict is the rate of the last seconds: the two sides VARKA-198 measured are
 * about 4 and 250 nanoseconds a row apart, and `dev/varka_inlining_cliff.py` cuts between them.
 * The evidence on why is the JVM's, from the flags the launcher passes: `PrintInlining` and
 * `PrintIntrinsics` scoped to the emitted class print into this process's output, between the
 * markers, and `-XX:+LogCompilation` writes the compile log `dev/varka_c2_report.py` reads.
 *
 * Two shapes: `cheap`, the tails above, and `makedate`, the deopt guard's
 * `make_date(year(d), month(d), k)` outputs, whose every call site carries more of C1's virtual
 * registers. And an optional compiler directive on the emitted class, `inline`, which forces
 * `VarkaVectorSupport`'s helpers inline whatever C2's budgets say and raises the class's node
 * limit: the arm that asks whether steering the JIT lifts the cliff on a stock JDK.
 *
 * The last two arguments are the emitter's call-site budget
 * (`VarkaEmitOptions.callSiteBudget`) and its heavy-group exemption
 * (`VarkaEmitOptions.heavyGroupOutputs`), by default the production values: under them the
 * emitter splits a wide group whose loop method is past C1's limit, so the cheap shape forks as
 * several loop methods and the make_date shape as it always did. A budget of `0` turns it off,
 * which is the arm the census ran before the budget existed and the control it is measured
 * against; an exemption of `0` splits the make_date shape too, one output a method. Either
 * argument may be `default`, which is how the launcher asks for the production value of one
 * while it sets the other.
 *
 * Lines the reader looks for:
 *  - `VARKA_CLIFF_BEGIN=<class> pid=<pid> outputs=<n> ceiling=<c> c1=<on|off> xbatch=<on|off>
 *    shape=<cheap|makedate> directive=<none|inline> budget=<sites> heavy=<outputs>`;
 *  - `VARKA_CLIFF_METHODS=<method>:<bytes>:<vector call sites>,...` for the loop methods, the
 *    sites in the budget's unit (`VarkaEmittedClass.vectorCallSites`);
 *  - `VARKA_CLIFF_RATE=<second> <nanoseconds a row>` once a second;
 *  - `VARKA_CLIFF_ALLOC=<bytes a call>` over the last second, since a scalar fallback boxes;
 *  - `VARKA_CLIFF_DONE=<class> status=<status>`.
 *
 * `c1off` keeps C1 off the emitted class through a compiler directive, as the kernel warm-up
 * keeps it in production; the default is C1 on, the condition VARKA-198 measured under.
 */
object VarkaInliningCliffProbe {

  val BEGIN_PREFIX = "VARKA_CLIFF_BEGIN="
  val METHODS_PREFIX = "VARKA_CLIFF_METHODS="
  val RATE_PREFIX = "VARKA_CLIFF_RATE="
  val ALLOC_PREFIX = "VARKA_CLIFF_ALLOC="
  val DONE_PREFIX = "VARKA_CLIFF_DONE="

  /** The emitted classes' prefix, which the launcher's `CompileCommand` patterns name. */
  val CLASS_PREFIX = "org.apache.spark.sql.varka.execution.VarkaCliffProbe_"

  def className(outputs: Int, ceiling: Int, c1: String, shape: String, directive: String,
      budget: Int, heavy: Int): String =
    s"$CLASS_PREFIX${outputs}_${ceiling}_${c1}_${shape}_${directive}_${budget}_$heavy"

  /** The C2 options the `inline` directive sets on the emitted class. */
  private val inlineDirective = "c2: { inline: [\"+org/apache/spark/sql/varka/vector/" +
    "VarkaVectorSupport.*\"], MaxNodeLimit: 240000 }"

  /** Installs a compiler directive on the emitted classes: C1 excluded, the C2 options, or both. */
  private def addDirective(excludeC1: Boolean, inline: Boolean): Unit = {
    val options = Seq(if (excludeC1) "c1: { Exclude: true }" else "",
      if (inline) inlineDirective else "").filter(_.nonEmpty).mkString(", ")
    val file = Files.createTempFile("varka-cliff-probe-directive", ".json")
    try {
      Files.writeString(file, "[{ match: \"org/apache/spark/sql/varka/execution/" +
        "VarkaCliffProbe_*.*\", " + options + " }]")
      val reply = ManagementFactory.getPlatformMBeanServer.invoke(
        new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerDirectivesAdd",
        Array[AnyRef](Array(file.toString)), Array(classOf[Array[String]].getName))
      require(String.valueOf(reply).contains("added"), s"the directive was refused: $reply")
    } finally {
      Files.deleteIfExists(file)
    }
  }

  private val d = AttributeReference("d", DateType)()
  private val columns: Seq[Attribute] = Seq(d)

  /**
   * The projection: VARKA-198's cheap tails, `year(d) + k`, the prefix most of each output's
   * work; or the deopt guard's `make_date(year(d), month(d), k)` outputs.
   */
  private def shape(kind: String, n: Int): CompiledVarkaProjection = {
    val exprs = (1 to n).map { k =>
      val sql = kind match {
        case "cheap" => s"year(d) + $k"
        case "makedate" =>
          val day = (k - 1) % 28 + 1
          val yearOffset = (k - 1) / 28
          val year = if (yearOffset == 0) "year(d)" else s"year(d) + $yearOffset"
          s"make_date($year, month(d), $day)"
        case other => throw new IllegalArgumentException(s"shape $other is not cheap or makedate")
      }
      Alias(VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(sql), columns), s"c$k")()
    }
    VarkaExpressionCompiler.compile(exprs, columns).getOrElse(
      throw new IllegalStateException(s"the $n-output $kind projection did not fuse"))
  }

  def main(args: Array[String]): Unit = {
    // scalastyle:off println
    if (args.length < 3) {
      System.err.println("usage: VarkaInliningCliffProbe <outputs> <fusedCeiling> <seconds> " +
        "[rows] [c1on|c1off] [cheap|makedate] [none|inline] [callSiteBudget|default] " +
        "[heavyGroupOutputs|default]")
      System.exit(2)
    }
    val outputs = args(0).toInt
    val ceiling = args(1).toInt
    val seconds = args(2).toInt
    val rows = if (args.length > 3) args(3).toInt else 1024
    val c1 = if (args.length > 4) args(4).stripPrefix("c1") else "on"
    require(c1 == "on" || c1 == "off", s"C1 is on or off, not $c1")
    val kind = if (args.length > 5) args(5) else "cheap"
    val directive = if (args.length > 6) args(6) else "none"
    require(directive == "none" || directive == "inline",
      s"the directive is none or inline, not $directive")
    def countOrDefault(i: Int, default: Int): Int =
      if (args.length > i && args(i) != "default") args(i).toInt else default
    val budget = countOrDefault(7, VarkaEmitOptions.DEFAULTS.callSiteBudget())
    val heavy = countOrDefault(8, VarkaEmitOptions.DEFAULTS.heavyGroupOutputs())
    val xbatch = if (ManagementFactory.getRuntimeMXBean.getInputArguments.contains("-Xbatch")) {
      "on"
    } else {
      "off"
    }
    val name = className(outputs, ceiling, c1, kind, directive, budget, heavy)
    println(s"$BEGIN_PREFIX$name pid=${ProcessHandle.current().pid()} outputs=$outputs " +
      s"ceiling=$ceiling c1=$c1 xbatch=$xbatch shape=$kind directive=$directive " +
      s"budget=$budget heavy=$heavy")
    if (c1 == "off" || directive == "inline") {
      addDirective(excludeC1 = c1 == "off", inline = directive == "inline")
    }
    val fused = shape(kind, outputs)
    val bytes = VarkaLoopEmitter.emit(name, fused.outputs,
        fused.inputOrdinals.size,
      fused.numLiterals, null, null,
      // The budget is read against the measured split, not the predicted one (VARKA-236).
      VarkaEmitOptions.DEFAULTS.withFusedCeiling(ceiling).withCallSiteBudget(budget)
        .withHeavyGroupOutputs(heavy).withPredictGrouping(false).withPlanSize(false))
    val measured = VarkaEmittedClass.measure(bytes)
    println(METHODS_PREFIX + VarkaEmitterTestSupport.methodNames(bytes).asScala
      .filter(isLoop(_))
      .map(m => s"$m:${measured.codeLength.get(m)}:${measured.vectorCallSites.get(m)}")
      .mkString(","))
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(name, bytes)
    val kernel =
      loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
    val threads = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    val arena = Arena.ofConfined()
    try {
      val data = arena.allocate(rows * 4L, 64)
      (0 until rows).foreach(i => data.set(ValueLayout.JAVA_INT, i * 4L, i * 7 % 20000 - 10000))
      val literals = fused.literals.asScala.map(_.intValue).toArray
      val dst = Array.fill(outputs)(arena.allocate(rows * 4L, 64).address())
      val dstValidity = Array.fill(outputs)(arena.allocate(rows / 8L + 8, 64).address())
      val src = Array(data.address())
      val noValidity = Array(0L)
      val noNulls = Array(0)
      var status = 0
      val start = System.nanoTime()
      val end = start + seconds * 1000000000L
      var second = 1
      var windowStart = start
      var windowRows = 0L
      var windowCalls = 0L
      var windowBytes = threads.getCurrentThreadAllocatedBytes
      var bytesPerCall = 0.0
      while (System.nanoTime() < end) {
        status |= kernel.run(src, noValidity, noNulls, dst, dstValidity, literals, rows)
        windowRows += rows
        windowCalls += 1
        val now = System.nanoTime()
        if (now - windowStart >= 1000000000L) {
          val allocated = threads.getCurrentThreadAllocatedBytes
          bytesPerCall = (allocated - windowBytes).toDouble / windowCalls
          println(f"$RATE_PREFIX$second%d ${(now - windowStart).toDouble / windowRows}%.2f")
          second += 1
          windowStart = now
          windowRows = 0
          windowCalls = 0
          windowBytes = allocated
        }
      }
      println(f"$ALLOC_PREFIX$bytesPerCall%.0f")
      println(s"$DONE_PREFIX$name status=$status")
    } finally {
      arena.close()
    }
    // scalastyle:on println
  }
}
