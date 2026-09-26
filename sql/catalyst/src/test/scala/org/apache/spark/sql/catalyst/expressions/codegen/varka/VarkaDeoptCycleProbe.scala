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
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeGenerator, CompiledVarkaProjection,
  VarkaExpressionCompiler, VarkaGeneratedClassLoader}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.types.DateType

/**
 * The child JVM of `dev/varka_deopt_cycle.sh` (task 189): one `make_date` kernel of the method
 * size benchmark's ladder, emitted in one of its two forms and driven over null-free batches for
 * a fixed time, so that the parent can read from the JVM's own compile and deoptimization log
 * whether the kernel's dense loop compiled once or entered the C2 deoptimization cycle
 * `PLAN_MILESTONE_6.md` 2.12 describes. The verdict is the parent's, from those logs; this
 * class only prints what it built and, per second, the rate it saw, so a log can be read
 * against a timeline.
 *
 * Arguments: the output count, the form (`single` for one epilogue over every output, the
 * emission before task 87; `group` for the epilogue per group, the default since), the seconds
 * to run, optionally the batch length (1024, which every lane count divides), and optionally the
 * path to C2: `batches`, the batches from the first call; `warmup`, the path task 212 gives a
 * new kernel - C1 kept off the class by the same compiler directive, and twelve thousand calls
 * of 32 rows before the first batch, so that C2 compiles the loop from the warm-up's profile;
 * and the two halves of that path on their own, `c1off` and `shortcalls`, which tell which half
 * changes what C2 does. `VARKA_DEOPT_DUMP=<dir>` in the environment writes the emitted class
 * there, for `javap`.
 */
object VarkaDeoptCycleProbe {

  val METHODS_PREFIX = "VARKA_DEOPT_METHODS="
  val RATE_PREFIX = "VARKA_DEOPT_RATE="
  val DONE_PREFIX = "VARKA_DEOPT_DONE="

  /** The emitted class's name carries the case, so one log holds many forks apart. */
  def className(outputs: Int, form: String, path: String): String =
    s"org.apache.spark.sql.varka.execution.VarkaDeoptProbe_${outputs}_${form}_$path"

  /** The rows of one warm-up call at a 1024-row batch: `VarkaKernelWarmup`'s slice. */
  private val warmupRows = 32
  private val warmupCalls = 12000

  /** Task 212's directive, for this probe's classes: C1 never compiles them. */
  private def excludeC1(): Unit = {
    val file = Files.createTempFile("varka-deopt-probe-directive", ".json")
    try {
      Files.writeString(file, "[{ match: \"org/apache/spark/sql/varka/execution/" +
        "VarkaDeoptProbe_*.*\", c1: { Exclude: true } }]")
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

  /** The `n`-output projection, exactly `VarkaMethodSizeBenchmark`'s. */
  private def shape(n: Int): CompiledVarkaProjection = {
    val exprs = (1 to n).map { k =>
      val day = (k - 1) % 28 + 1
      val yearOffset = (k - 1) / 28
      val year = if (yearOffset == 0) "year(d)" else s"year(d) + $yearOffset"
      val sql = s"make_date($year, month(d), $day)"
      Alias(VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(sql), columns), s"c$k")()
    }
    VarkaExpressionCompiler.compile(exprs, columns).getOrElse(
      throw new IllegalStateException(s"the $n-output make_date projection did not fuse"))
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 3) {
      // scalastyle:off println
      System.err.println("usage: VarkaDeoptCycleProbe <outputs> <single|group> <seconds> " +
        "[rows] [batches|warmup|c1off|shortcalls]")
      // scalastyle:on println
      System.exit(2)
    }
    val outputs = args(0).toInt
    val form = args(1)
    val seconds = args(2).toInt
    val rows = if (args.length > 3) args(3).toInt else 1024
    val path = if (args.length > 4) args(4) else "batches"
    require(Set("batches", "warmup", "c1off", "shortcalls").contains(path),
      s"path $path is not batches, warmup, c1off or shortcalls")
    val options = form match {
      case "single" => VarkaEmitOptions.DEFAULTS.withMethodByteBudget(0)
      case "group" =>
        VarkaEmitOptions.DEFAULTS.withMethodByteBudget(CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT)
      case other => throw new IllegalArgumentException(s"form $other is not single or group")
    }
    val fused = shape(outputs)
    val name = className(outputs, form, path)
    if (path == "warmup" || path == "c1off") {
      excludeC1()
    }
    val bytes = VarkaLoopEmitter.emit(name, fused.outputs.asJava, fused.inputOrdinals.size,
      fused.numLiterals, null, null, options)
    sys.env.get("VARKA_DEOPT_DUMP").foreach { dir =>
      Files.write(java.nio.file.Paths.get(dir, name + ".class"), bytes)
    }
    // scalastyle:off println
    println(METHODS_PREFIX + VarkaEmitterTestSupport.methodNames(bytes).asScala
      .filter(m => m.startsWith("loop") || m.startsWith("epilogue") || m.startsWith("run"))
      .map(m => s"$m:${VarkaEmitterTestSupport.codeSize(bytes, m)}").mkString(","))
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(name, bytes)
    val kernel =
      loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
    val arena = Arena.ofConfined()
    try {
      // The benchmark's null-free column: days in [-10000, 10000), every validity bit set.
      val bufferRows = 4096
      val data = arena.allocate(bufferRows * 4L, 64)
      (0 until bufferRows).foreach(i => data.set(ValueLayout.JAVA_INT, i * 4L, i % 20000 - 10000))
      val literals = fused.literals.toArray
      val dst = Array.fill(outputs)(arena.allocate(bufferRows * 4L, 64).address())
      val dstValidity = Array.fill(outputs)(arena.allocate(bufferRows / 8L, 64).address())
      val src = Array(data.address())
      val noValidity = Array(0L)
      val noNulls = Array(0)
      var status = 0
      if (path == "warmup" || path == "shortcalls") {
        var call = 0
        while (call < warmupCalls) {
          status |= kernel.run(src, noValidity, noNulls, dst, dstValidity, literals, warmupRows)
          call += 1
        }
        println(s"VARKA_DEOPT_PHASE=batches after $warmupCalls calls of $warmupRows rows")
      }
      val start = System.nanoTime()
      val end = start + seconds * 1000000000L
      var second = 1
      var windowStart = start
      var windowRows = 0L
      while (System.nanoTime() < end) {
        status |= kernel.run(src, noValidity, noNulls, dst, dstValidity, literals, rows)
        windowRows += rows
        val now = System.nanoTime()
        if (now - windowStart >= 1000000000L) {
          val rate = windowRows * 1e9 / (now - windowStart) / 1e6
          println(f"$RATE_PREFIX$second%d ${rate}%.1f")
          second += 1
          windowStart = now
          windowRows = 0
        }
      }
      println(s"$DONE_PREFIX$name status=$status")
    } finally {
      arena.close()
    }
    // scalastyle:on println
  }
}
