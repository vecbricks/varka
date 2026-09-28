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

package org.apache.spark.sql.execution

import java.io.{File, PrintWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLongArray

import scala.collection.mutable

import com.codahale.metrics.{Histogram, Reservoir, Snapshot}
import org.scalatest.{Args, Reporter, Suite}
import org.scalatest.events.{Event, SuiteAborted, TestCanceled, TestFailed,
  TestSucceeded}

import org.apache.spark.SparkFunSuite
import org.apache.spark.metrics.source.CodegenMetrics
import org.apache.spark.sql.{SQLQueryTestSuite, SSBQuerySuite, TPCDSQuerySuite, TPCHQuerySuite}

/**
 * How large the methods vanilla Spark generates are, over a real workload: every query of the
 * SQL golden-file suite and of the TPC-DS, TPC-H and SSB query suites, with Varka off (its
 * default). Spark records the bytecode size of every method of every class it compiles in the
 * `generatedMethodSize` histogram of [[CodegenMetrics]], but that histogram keeps a sample of
 * about a thousand values that decays with time, so after an hour of queries it describes the
 * last few minutes. This census replaces the histogram's sample with an exact count of every
 * size, kept per suite family, runs the suites one after another in one JVM, and writes the
 * counts against the two limits a method meets: the 8000 bytes HotSpot compiles and the 64 KB
 * the JVM loads.
 *
 * What is counted: each method of each class Spark compiled, the class's constructor and
 * initialisers included. Spark caches compiled classes by their source, so a query whose code
 * was already compiled adds nothing: the census counts distinct generated code, not executions.
 * The golden-file suite executes its queries; the TPC suites plan theirs and compile every
 * whole-stage codegen stage without data.
 *
 * It is not a test: without the environment variable `VARKA_CENSUS=1` it registers no test, so
 * CI discovers it and runs nothing. Run it by name:
 * {{{
 *   VARKA_CENSUS=1 build/sbt "sql/testOnly org.apache.spark.sql.execution.VarkaMethodSizeCensus"
 * }}}
 * and it writes `target/VarkaCodegenMethodSizes-results.txt` under `sql/core`, or the path in
 * the environment variable `VARKA_CENSUS_OUT`; `VARKA_CENSUS_FAMILIES`, a comma-separated
 * subset of `golden,tpcds,tpch,ssb`, runs only those. Environment variables, because sbt runs
 * the suites in a forked JVM that its own system properties do not reach. The committed file is
 * `sql/core/benchmarks/VarkaCodegenMethodSizes-jdk25-results.txt`; see `PLAN_TASK_181.md`.
 */
class VarkaMethodSizeCensus extends SparkFunSuite {

  import VarkaMethodSizeCensus._

  // Registered only when asked for, so that CI discovers the class and runs nothing. The golden
  // files take longer than the 20 minutes `SparkFunSuite` allows a test, and it reads its limit
  // when the test is registered, so the limit is raised around the registration.
  if (sys.env.get("VARKA_CENSUS").contains("1")) {
    val timeout = sys.props.get("spark.test.timeout")
    System.setProperty("spark.test.timeout", "600")
    try registerCensus() finally {
      timeout.fold(System.clearProperty("spark.test.timeout"))(
        System.setProperty("spark.test.timeout", _))
    }
  }

  private def registerCensus(): Unit = {
    test("the bytecode sizes of vanilla Spark's generated methods over its query suites") {
      val selected = sys.env.get("VARKA_CENSUS_FAMILIES").map(_.split(",").map(_.trim).toSet)
      val suites = Seq[() => Suite](
        () => new SQLQueryTestSuite, () => new TPCDSQuerySuite, () => new TPCHQuerySuite,
        () => new SSBQuerySuite).map(_()).filter(s => selected.forall(_.contains(key(s))))
      val histogram = CodegenMetrics.METRIC_GENERATED_METHOD_BYTECODE_SIZE
      val countBefore = histogram.getCount
      val original = swapReservoir(new CountingReservoir(counts))
      val outcomes = mutable.LinkedHashMap.empty[String, Outcomes]
      try {
        // One suite at a time, in this thread, so that each method is counted under the family
        // of the suite that compiled it. The suites run here rather than as nested suites,
        // which sbt would run as separate tasks after this one.
        suites.foreach { suite =>
          currentFamily = family(suite)
          val reporter = new OutcomeReporter
          suite.run(None, Args(reporter)).waitUntilCompleted()
          outcomes(currentFamily) = reporter.outcomes
        }
      } finally {
        swapReservoir(original)
      }
      val out = new File(sys.env.getOrElse("VARKA_CENSUS_OUT",
        "target/VarkaCodegenMethodSizes-results.txt"))
      val writer = new PrintWriter(out, StandardCharsets.UTF_8)
      try writer.write(report(counts, outcomes)) finally writer.close()
      logInfo(s"VarkaMethodSizeCensus wrote ${out.getAbsolutePath}")
      val counted = counts.values.map(c => (0 to maxSize).map(c.get).sum).sum
      assert(counted == histogram.getCount - countBefore, s"counted $counted methods, the " +
        s"histogram ${histogram.getCount - countBefore}; outcomes: $outcomes")
      assert(counted > 0, s"no generated method was counted; outcomes: $outcomes")
    }
  }
}

object VarkaMethodSizeCensus {

  /** The largest size a method can have: the JVM refuses a method of 64 KB or more. */
  private val maxSize = 65535

  @volatile private var currentFamily = "none"

  /** The exact count of every method size, per suite family, in the order families ran. */
  private val counts = mutable.LinkedHashMap.empty[String, AtomicLongArray]

  private def key(suite: Suite): String = suite match {
    case _: SQLQueryTestSuite => "golden"
    case _: TPCDSQuerySuite => "tpcds"
    case _: TPCHQuerySuite => "tpch"
    case _: SSBQuerySuite => "ssb"
    case other => other.suiteName
  }

  private def family(suite: Suite): String = suite match {
    case _: SQLQueryTestSuite => "SQL golden files (SQLQueryTestSuite)"
    case _: TPCDSQuerySuite => "TPC-DS (TPCDSQuerySuite)"
    case _: TPCHQuerySuite => "TPC-H (TPCHQuerySuite)"
    case _: SSBQuerySuite => "SSB (SSBQuerySuite)"
    case other => other.suiteName
  }

  /** How the tests of one suite ended, so the report can say whether its queries all ran. */
  private[execution] case class Outcomes(
      succeeded: Int, failed: Seq[String], canceled: Int, aborted: Seq[String])

  private class OutcomeReporter extends Reporter {
    private var succeeded = 0
    private var canceled = 0
    private val failed = mutable.ArrayBuffer.empty[String]
    private val aborted = mutable.ArrayBuffer.empty[String]
    override def apply(event: Event): Unit = synchronized {
      event match {
        case _: TestSucceeded => succeeded += 1
        case e: TestFailed => failed += e.testName
        case _: TestCanceled => canceled += 1
        case e: SuiteAborted => aborted += s"${e.suiteName}: ${e.message}"
        case _ =>
      }
    }
    def outcomes: Outcomes = synchronized {
      Outcomes(succeeded, failed.toSeq, canceled, aborted.toSeq)
    }
  }

  /** Counts every value into the current family, and keeps Spark's own sample alongside. */
  private class CountingReservoir(counts: mutable.LinkedHashMap[String, AtomicLongArray])
    extends Reservoir {
    private val sample = new com.codahale.metrics.ExponentiallyDecayingReservoir()
    override def size(): Int = sample.size()
    override def update(value: Long): Unit = {
      sample.update(value)
      val slots = counts.synchronized {
        counts.getOrElseUpdate(currentFamily, new AtomicLongArray(maxSize + 1))
      }
      slots.incrementAndGet(math.min(math.max(value, 0L), maxSize.toLong).toInt)
    }
    override def getSnapshot: Snapshot = sample.getSnapshot
  }

  /**
   * Puts `reservoir` into Spark's `generatedMethodSize` histogram and returns the one it
   * replaced. The histogram's reservoir is a private final field with no setter, so this is
   * reflection; the histogram's own count is unaffected.
   */
  private def swapReservoir(reservoir: Reservoir): Reservoir = {
    val field = classOf[Histogram].getDeclaredField("reservoir")
    field.setAccessible(true)
    val histogram = CodegenMetrics.METRIC_GENERATED_METHOD_BYTECODE_SIZE
    val previous = field.get(histogram).asInstanceOf[Reservoir]
    field.set(histogram, reservoir)
    previous
  }

  /**
   * Size bands, in bytes of bytecode. HotSpot compiles a method of up to 8000 bytes
   * (`HugeMethodLimit`), so 8000 closes a band and 8001 opens the next.
   */
  private val bands: Seq[(Int, Int)] = Seq(
    (0, 99), (100, 249), (250, 499), (500, 999), (1000, 1999), (2000, 3999), (4000, 8000),
    (8001, 16000), (16001, 32000), (32001, maxSize))

  private[execution] def report(
      counts: collection.Map[String, AtomicLongArray],
      outcomes: collection.Map[String, Outcomes]): String = {
    val sb = new StringBuilder
    def line(s: String): Unit = sb.append(s).append('\n')
    val jvm = s"${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}"
    line("=" * 96)
    line("Bytecode sizes of the methods vanilla Spark generates, over its own query suites")
    line("=" * 96)
    line("")
    line(s"JVM: $jvm")
    line("Every method of every class Spark compiled, once per distinct generated class.")
    line("HotSpot does not compile a method past 8000 bytes; the JVM refuses one of 64 KB.")
    val families = counts.toSeq :+ ("all" -> {
      val all = new AtomicLongArray(maxSize + 1)
      counts.values.foreach(c => (0 to maxSize).foreach(i => all.addAndGet(i, c.get(i))))
      all
    })
    families.foreach { case (name, c) =>
      val total = (0 to maxSize).map(c.get).sum
      line("")
      line(s"$name: $total methods")
      outcomes.get(name).foreach { o =>
        line(s"tests: ${o.succeeded} succeeded, ${o.failed.size} failed, ${o.canceled} canceled" +
          (if (o.aborted.isEmpty) "" else s", aborted: ${o.aborted.mkString("; ")}") +
          (if (o.failed.isEmpty) "" else s"; failed: ${o.failed.mkString(", ")}"))
      }
      if (total > 0) {
        line(f"${"bytes"}%-14s${"methods"}%12s${"share"}%10s${"cumulative"}%12s")
        var cumulative = 0L
        bands.foreach { case (lo, hi) =>
          val n = (lo to hi).map(c.get).sum
          cumulative += n
          line(f"${s"$lo-$hi"}%-14s$n%12d${100.0 * n / total}%9.3f%%" +
            f"${100.0 * cumulative / total}%11.3f%%")
        }
        def percentile(p: Double): Int = {
          val target = math.ceil(p * total).toLong
          var seen = 0L
          (0 to maxSize).find { i => seen += c.get(i); seen >= target }.getOrElse(maxSize)
        }
        val largest = (maxSize to 0 by -1).find(c.get(_) > 0).get
        val past8000 = (8001 to maxSize).map(c.get).sum
        line(s"median ${percentile(0.5)}, p90 ${percentile(0.9)}, p99 ${percentile(0.99)}, " +
          s"p99.9 ${percentile(0.999)}, largest $largest; past 8000 bytes: $past8000")
      }
    }
    line("")
    line("Every size with its count, over all families (bytes methods):")
    val all = families.last._2
    (0 to maxSize).filter(all.get(_) > 0).foreach(i => line(s"$i ${all.get(i)}"))
    sb.toString
  }
}
