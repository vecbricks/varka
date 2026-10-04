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

import org.scalactic.source.Position
import org.scalatest.Tag

import org.apache.spark.SparkFunSuite
import org.apache.spark.util.Utils

/**
 * A cap on each test of a Varka suite that ends a hung test by name. `SparkFunSuite` already
 * wraps every test in `failAfter(20 minutes)`, but ScalaTest's `failAfter` without a signaler
 * cannot stop a running body: it reports the overrun after the body returns, and a body that
 * never returns is never reported. A compiler loop that does not terminate therefore held the
 * fork's one Build slot for the job's whole limit, printing only ScalaTest's "still running",
 * and named no test (`m6/PLAN.md` row 226). Interrupting the test thread would not
 * stop such a loop either, since a CPU-bound loop checks no interrupt.
 *
 * So each test runs beside a watchdog thread. At the cap it interrupts the test thread, which
 * ends a body that blocks or checks interrupts, and gives it a grace period; a body still
 * running after that is not going to stop, so the watchdog writes every thread's stack under
 * the test's name and halts the JVM. sbt then reports the suite aborted, and the log's last
 * lines say which test and what it was doing, minutes after the hang rather than at the job's
 * limit.
 *
 * The cap is `varka.test.watchdog.minutes`, ten by default: the slowest Varka test on record,
 * the width audit's census, takes about two minutes on the laptop, and the suites' CI jobs
 * finish in eleven to seventeen minutes whole, so a single test past ten is a hang. A suite
 * whose tests are meant to run longer overrides `watchdogMinutes`.
 */
trait VarkaTestWatchdog extends SparkFunSuite {

  /** Minutes a test may run before the watchdog acts. */
  protected def watchdogMinutes: Long =
    sys.props.get("varka.test.watchdog.minutes").map(_.toLong).getOrElse(10L)

  override protected def test(testName: String, testTags: Tag*)(testBody: => Any)
      (implicit pos: Position): Unit = {
    super.test(testName, testTags: _*)(
      VarkaTestWatchdog.guard(suiteName, testName, watchdogMinutes)(testBody))
  }
}

object VarkaTestWatchdog {

  /** The exit status of a JVM the watchdog halted. */
  val HALT_STATUS = 3

  /** Runs `body` under a watchdog of `minutes`, and thirty seconds of grace after the interrupt. */
  def guard[T](suite: String, test: String, minutes: Long)(body: => T): T = {
    // scalastyle:off println
    guard(suite, test, minutes * 60 * 1000L, 30 * 1000L, report = System.err.println,
      halt = status => Runtime.getRuntime.halt(status))(body)
    // scalastyle:on println
  }

  /**
   * The mechanism, with its clock and its effects as parameters so that it can be tested
   * without waiting minutes or losing the JVM: `report` receives the two messages the watchdog
   * writes, and `halt` is what it does to a body that survives the interrupt.
   */
  private[varka] def guard[T](
      suite: String,
      test: String,
      millis: Long,
      graceMillis: Long,
      report: String => Unit,
      halt: Int => Unit)(body: => T): T = {
    val worker = Thread.currentThread()
    val watchdog = new Thread(() => {
      try {
        Thread.sleep(millis)
        report(s"$WATCHDOG '$test' of $suite has run for $millis ms; interrupting it, and " +
          s"halting the JVM in $graceMillis ms if it is still running")
        worker.interrupt()
        Thread.sleep(graceMillis)
        report(s"$WATCHDOG '$test' of $suite is still running; every thread's stack, then " +
          s"the JVM halts with status $HALT_STATUS:\n" + Utils.getThreadDump().mkString("\n"))
        halt(HALT_STATUS)
      } catch {
        // The test ended and its `finally` below interrupted this thread: nothing to do.
        case _: InterruptedException =>
      }
    }, s"varka-watchdog: $test")
    watchdog.setDaemon(true)
    watchdog.start()
    try {
      body
    } finally {
      watchdog.interrupt()
    }
  }

  private val WATCHDOG = "VARKA WATCHDOG:"
}
