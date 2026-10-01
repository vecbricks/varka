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

import scala.collection.mutable

import org.scalatest.{Args, Reporter, Suite}
import org.scalatest.events.{Event, SuiteAborted, TestCanceled, TestFailed, TestSucceeded}

import org.apache.spark.SparkFunSuite
import org.apache.spark.scheduler.{SparkListener, SparkListenerEvent}
import org.apache.spark.sql.{SQLQueryTestSuite, SSBQuerySuite, TPCDSQuerySuite, TPCHQuerySuite}
import org.apache.spark.sql.catalyst.expressions.LeafExpression
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.execution.adaptive.{AdaptiveSparkPlanExec, AQEShuffleReadExec,
  QueryStageExec}
import org.apache.spark.sql.execution.command.{DataWritingCommandExec, ExecutedCommandExec}
import org.apache.spark.sql.execution.datasources.WriteFilesExec
import org.apache.spark.sql.execution.datasources.v2.V2CommandExec
import org.apache.spark.sql.execution.exchange.{Exchange, ReusedExchangeExec}
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd
import org.apache.spark.sql.internal.SQLConf

/**
 * How often, over a real workload, an operator runs outside whole-stage codegen, and why: every
 * final physical plan of the SQL golden-file suite and of the TPC-DS, TPC-H and SSB query suites,
 * with Varka off (its default), each operator counted as inside a stage or outside one, and an
 * operator outside given the reason `CollapseCodegenStages` had for leaving it out. The census
 * of task 188 lists the places Spark's code generation gives up; this counts how often the
 * plan-time ones happen (`PLAN_TASK_233.md` 2).
 *
 * The reasons re-derive `CollapseCodegenStages.supportCodegen` (`WholeStageCodegenExec.scala`) on
 * the operator as it stands in the final plan: the operator is not `CodegenSupport`; it is, but
 * its own `supportCodegen` says no (the aggregate, join, generator and union conditions); one of
 * its expressions is a non-leaf `CodegenFallback`, which is named; its output or an input has
 * more nested fields than `spark.sql.codegen.maxFields`; it is a columnar scan, whose batches the
 * stage reads through a conversion, which is Spark's design and not a give-up; or none of these,
 * which is the structural rest - a stage root that is never wrapped, a join's child kept in a
 * stage of its own. First of all, an operator planned under `spark.sql.codegen.wholeStage` false
 * is outside a stage because a setting says so; the golden-file suite runs every query file under
 * several codegen settings, so those runs are named apart rather than counted as give-ups.
 * Exchanges and their reuses are counted apart, since they bound stages by design, and the
 * plumbing - `WholeStageCodegenExec`, `InputAdapter`, the columnar transitions, query stages,
 * subquery wrappers - is walked through and not counted.
 *
 * Two hooks, one per kind of suite. The golden-file suite executes its queries, each inside
 * `SQLExecution.withNewExecutionId`, so a `SparkListener` named in `spark.extraListeners` sees
 * every `SparkListenerSQLExecutionEnd` with its `QueryExecution`, whose executed plan is final by
 * then, adaptive execution included. The TPC suites plan their queries without data and hand
 * each plan to `checkGeneratedCode`, which the subclasses below override to record it. Both feed
 * one classifier, and a plan is counted once.
 *
 * Not a test: without the environment variable `VARKA_CENSUS=1` it registers nothing, so CI
 * discovers the class and runs nothing. Run it by name, as `VarkaMethodSizeCensus` runs:
 * {{{
 *   VARKA_CENSUS=1 build/sbt "sql/testOnly org.apache.spark.sql.execution.VarkaCodegenGiveUpCensus"
 * }}}
 * It writes `target/VarkaCodegenGiveUps-results.txt` under `sql/core`, or the path in
 * `VARKA_CENSUS_OUT`; `VARKA_CENSUS_FAMILIES`, a comma-separated subset of `golden,tpcds,tpch,ssb`,
 * runs only those. The committed file is
 * `sql/core/benchmarks/VarkaCodegenGiveUps-jdk25-results.txt`.
 */
class VarkaCodegenGiveUpCensus extends SparkFunSuite {

  import VarkaCodegenGiveUpCensus._

  // Registered only when asked for; the golden files take longer than the 20 minutes
  // `SparkFunSuite` allows a test, and it reads its limit at registration.
  if (sys.env.get("VARKA_CENSUS").contains("1")) {
    val timeout = sys.props.get("spark.test.timeout")
    System.setProperty("spark.test.timeout", "600")
    try registerCensus() finally {
      timeout.fold(System.clearProperty("spark.test.timeout"))(
        System.setProperty("spark.test.timeout", _))
    }
  }

  private def registerCensus(): Unit = {
    test("operators outside whole-stage codegen, by reason, over Spark's query suites") {
      val selected = sys.env.get("VARKA_CENSUS_FAMILIES").map(_.split(",").map(_.trim).toSet)
      // The listener reaches every session the suites build: `SparkConf` reads the system
      // property when the test session is created.
      val listeners = sys.props.get("spark.extraListeners")
      System.setProperty("spark.extraListeners",
        (listeners.toSeq :+ classOf[PlanListener].getName).mkString(","))
      val outcomes = mutable.LinkedHashMap.empty[String, Outcomes]
      try {
        val suites = Seq[() => Suite](
          () => new SQLQueryTestSuite, () => new CensusTPCDSQuerySuite,
          () => new CensusTPCHQuerySuite, () => new CensusSSBQuerySuite)
          .map(_()).filter(s => selected.forall(_.contains(key(s))))
        suites.foreach { suite =>
          currentFamily = family(suite)
          val reporter = new OutcomeReporter
          suite.run(None, Args(reporter)).waitUntilCompleted()
          outcomes(currentFamily) = reporter.outcomes
        }
      } finally {
        listeners.fold(System.clearProperty("spark.extraListeners"))(
          System.setProperty("spark.extraListeners", _))
      }
      val out = new File(sys.env.getOrElse("VARKA_CENSUS_OUT",
        "target/VarkaCodegenGiveUps-results.txt"))
      val writer = new PrintWriter(out, StandardCharsets.UTF_8)
      try writer.write(report(tallies, outcomes)) finally writer.close()
      logInfo(s"VarkaCodegenGiveUpCensus wrote ${out.getAbsolutePath}")
      assert(tallies.values.map(_.plans).sum > 0, s"no plan was counted; outcomes: $outcomes")
    }
  }
}

object VarkaCodegenGiveUpCensus {

  @volatile private var currentFamily = "none"

  /** What one family's plans came to, in the order the reasons were first met. */
  private[execution] class Tally {
    var plans = 0L
    var operators = 0L
    var inStage = 0L
    var exchanges = 0L
    /** reason -> (operators, plans touched, class or expression -> operators) */
    val outside =
      mutable.LinkedHashMap.empty[String, (Long, Long, mutable.LinkedHashMap[String, Long])]
    /** Every `CodegenFallback` expression class met, with how many operators it took out. */
    val fallbacks = mutable.LinkedHashMap.empty[String, Long]
  }

  private val tallies = mutable.LinkedHashMap.empty[String, Tally]

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

  /** The TPC suites plan without data; their plans arrive through this override. */
  class CensusTPCDSQuerySuite extends TPCDSQuerySuite {
    override protected def checkGeneratedCode(plan: SparkPlan, check: Boolean): Unit = {
      record(plan)
      super.checkGeneratedCode(plan, check)
    }
  }

  class CensusTPCHQuerySuite extends TPCHQuerySuite {
    override protected def checkGeneratedCode(plan: SparkPlan, check: Boolean): Unit = {
      record(plan)
      super.checkGeneratedCode(plan, check)
    }
  }

  class CensusSSBQuerySuite extends SSBQuerySuite {
    override protected def checkGeneratedCode(plan: SparkPlan, check: Boolean): Unit = {
      record(plan)
      super.checkGeneratedCode(plan, check)
    }
  }

  /** The golden files execute; each execution's end carries the plan that ran. */
  class PlanListener extends SparkListener {
    override def onOtherEvent(event: SparkListenerEvent): Unit = event match {
      case e: SparkListenerSQLExecutionEnd if e.qe != null && e.executionFailure.isEmpty =>
        record(e.qe.executedPlan)
      case _ =>
    }
  }

  /** One operator's place in the final plan. */
  private sealed trait Place
  private case object InStage extends Place
  private case object IsExchange extends Place
  private case class Outside(reason: String, who: String) extends Place

  private def isCommand(plan: SparkPlan): Boolean = plan match {
    case _: CommandResultExec | _: ExecutedCommandExec | _: V2CommandExec => true
    case _ => false
  }

  /** Why `CollapseCodegenStages` left this operator out of a stage, in its own order of tests. */
  private def reason(plan: SparkPlan, conf: SQLConf): (String, String) = plan match {
    case p if !p.conf.wholeStageEnabled =>
      ("whole-stage codegen switched off by a setting", p.getClass.getSimpleName)
    case p: CodegenSupport if p.supportCodegen =>
      val fallback = p.expressions.flatMap(_.collectFirst {
        case e: CodegenFallback if !e.isInstanceOf[LeafExpression] => e
      }).headOption
      if (fallback.isDefined) {
        ("a CodegenFallback expression", fallback.get.getClass.getSimpleName)
      } else if (WholeStageCodegenExec.isTooManyFields(conf, p.schema)) {
        ("more output fields than maxFields", p.getClass.getSimpleName)
      } else if (p.children.exists(c => WholeStageCodegenExec.isTooManyFields(conf, c.schema))) {
        ("more input fields than maxFields", p.getClass.getSimpleName)
      } else if (p.supportsColumnar) {
        ("a columnar scan, read inside the stage", p.getClass.getSimpleName)
      } else {
        ("structural: supports codegen, left out", p.getClass.getSimpleName)
      }
    case p: CodegenSupport => ("supportCodegen is false", p.getClass.getSimpleName)
    case p if p.supportsColumnar =>
      ("a columnar scan, read inside the stage", p.getClass.getSimpleName)
    case p => ("not a CodegenSupport operator", p.getClass.getSimpleName)
  }

  /** Every operator of `plan` with its place: the plumbing is walked through, not counted. */
  private def places(plan: SparkPlan, inStage: Boolean, conf: SQLConf,
      out: mutable.ArrayBuffer[Place]): Unit = {
    plan match {
      case a: AdaptiveSparkPlanExec => places(a.executedPlan, false, conf, out)
      case s: QueryStageExec => places(s.plan, false, conf, out)
      case w: WholeStageCodegenExec => places(w.child, true, conf, out)
      case i: InputAdapter => places(i.child, false, conf, out)
      case c: ColumnarToRowExec => places(c.child, false, conf, out)
      case r: RowToColumnarExec => places(r.child, false, conf, out)
      case e: Exchange =>
        out += IsExchange
        e.children.foreach(places(_, false, conf, out))
      case _: ReusedExchangeExec | _: SubqueryBroadcastExec => out += IsExchange
      case r: AQEShuffleReadExec => places(r.child, false, conf, out)
      // A write's own nodes wrap the plan that produced the rows; the plan is what is counted.
      case w: DataWritingCommandExec => places(w.child, false, conf, out)
      case w: WriteFilesExec => places(w.child, false, conf, out)
      // Subquery wrappers hold a plan of their own; the plan is counted, the wrapper is not.
      case s: SubqueryExec => places(s.child, false, conf, out)
      case r: ReusedSubqueryExec => places(r.child, false, conf, out)
      case p =>
        if (inStage) out += InStage
        else {
          val (why, who) = reason(p, conf)
          out += Outside(why, who)
        }
        p.children.foreach(places(_, inStage, conf, out))
    }
    plan.subqueries.foreach(places(_, false, conf, out))
  }

  private[execution] def record(plan: SparkPlan): Unit = {
    if (isCommand(plan)) return
    val found = mutable.ArrayBuffer.empty[Place]
    places(plan, inStage = false, plan.conf, found)
    if (found.isEmpty) return
    tallies.synchronized {
      val t = tallies.getOrElseUpdate(currentFamily, new Tally)
      t.plans += 1
      val touched = mutable.LinkedHashSet.empty[String]
      found.foreach {
        case InStage => t.operators += 1; t.inStage += 1
        case IsExchange => t.exchanges += 1
        case Outside(why, who) =>
          t.operators += 1
          val (n, plans, whos) = t.outside.getOrElse(why,
            (0L, 0L, mutable.LinkedHashMap.empty[String, Long]))
          whos(who) = whos.getOrElse(who, 0L) + 1
          t.outside(why) = (n + 1, if (touched.add(why)) plans + 1 else plans, whos)
          if (why == "a CodegenFallback expression") {
            t.fallbacks(who) = t.fallbacks.getOrElse(who, 0L) + 1
          }
      }
    }
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

  private[execution] def report(
      tallies: collection.Map[String, Tally],
      outcomes: collection.Map[String, Outcomes]): String = {
    val sb = new StringBuilder
    def line(s: String): Unit = sb.append(s).append('\n')
    val jvm = s"${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}"
    line("=" * 96)
    line("Operators outside whole-stage codegen, by reason, over Spark's own query suites")
    line("=" * 96)
    line("")
    line(s"JVM: $jvm")
    line("Every operator of every final plan, counted once per plan; exchanges apart, since they")
    line("bound stages by design; the plumbing (stages, input adapters, columnar transitions,")
    line("query stages) walked through. Commands are not plans and are skipped.")
    val all = new Tally
    tallies.values.foreach { t =>
      all.plans += t.plans; all.operators += t.operators; all.inStage += t.inStage
      all.exchanges += t.exchanges
      t.outside.foreach { case (why, (n, plans, whos)) =>
        val (an, aplans, awhos) = all.outside.getOrElse(why,
          (0L, 0L, mutable.LinkedHashMap.empty[String, Long]))
        whos.foreach { case (w, c) => awhos(w) = awhos.getOrElse(w, 0L) + c }
        all.outside(why) = (an + n, aplans + plans, awhos)
      }
      t.fallbacks.foreach { case (w, c) => all.fallbacks(w) = all.fallbacks.getOrElse(w, 0L) + c }
    }
    (tallies.toSeq :+ ("all" -> all)).foreach { case (name, t) =>
      line("")
      line(s"$name: ${t.plans} plans, ${t.operators} operators, ${t.exchanges} exchanges")
      outcomes.get(name).foreach { o =>
        line(s"tests: ${o.succeeded} succeeded, ${o.failed.size} failed, ${o.canceled} canceled" +
          (if (o.aborted.isEmpty) "" else s", aborted: ${o.aborted.mkString("; ")}") +
          (if (o.failed.isEmpty) "" else s"; failed: ${o.failed.mkString(", ")}"))
      }
      if (t.operators > 0) {
        val outside = t.operators - t.inStage
        line(f"in a stage: ${t.inStage} (${100.0 * t.inStage / t.operators}%.1f%%); " +
          f"outside: $outside (${100.0 * outside / t.operators}%.1f%%)")
        line(f"${"reason"}%-42s${"operators"}%10s${"share"}%8s${"plans"}%7s  most often")
        t.outside.toSeq.sortBy(-_._2._1).foreach { case (why, (n, plans, whos)) =>
          val top = whos.toSeq.sortBy(-_._2).take(5).map { case (w, c) => s"$w $c" }.mkString(", ")
          line(f"$why%-42s$n%10d${100.0 * n / t.operators}%7.1f%%$plans%7d  $top")
        }
        if (t.fallbacks.nonEmpty) {
          line("CodegenFallback expressions met (operators taken out of a stage): " +
            t.fallbacks.toSeq.sortBy(-_._2).map { case (w, c) => s"$w $c" }.mkString(", "))
        }
      }
    }
    sb.toString
  }
}
