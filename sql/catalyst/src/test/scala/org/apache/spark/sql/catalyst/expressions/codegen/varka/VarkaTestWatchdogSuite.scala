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

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.collection.mutable

import org.apache.spark.SparkFunSuite

/**
 * The watchdog's three outcomes, each in well under a second: a body that returns is left
 * alone; a body that blocks ends at the interrupt; a body that ignores the interrupt is
 * reported with the threads' stacks and the JVM is halted, here through a stub that only
 * records the call (`m6/PLAN.md` row 226).
 */
class VarkaTestWatchdogSuite extends SparkFunSuite {

  private def run[T](millis: Long, grace: Long, onHalt: () => Unit = () => ())(body: => T)
      : (Either[Throwable, T], Seq[String], Option[Int]) = {
    val reports = mutable.ArrayBuffer.empty[String]
    val halted = new AtomicInteger(-1)
    val result = try {
      Right(VarkaTestWatchdog.guard("SomeSuite", "some test", millis, grace,
        report = m => reports.synchronized { reports += m },
        halt = s => { halted.set(s); onHalt() })(body))
    } catch {
      case e: Throwable => Left(e)
    }
    // The watchdog's interrupt may still be pending on this thread; the next test starts clean.
    Thread.interrupted()
    (result, reports.synchronized(reports.toSeq), Option(halted.get).filter(_ >= 0))
  }

  test("a test that finishes in time is returned as it is, and nothing is reported") {
    val (result, reports, halted) = run(millis = 60000, grace = 1000)(41 + 1)
    assert(result == Right(42))
    assert(reports.isEmpty && halted.isEmpty)
  }

  test("a test that blocks is ended by the interrupt, and the JVM is left alone") {
    val (result, reports, halted) = run(millis = 100, grace = 5000) {
      Thread.sleep(60000)
    }
    assert(result.left.exists(_.isInstanceOf[InterruptedException]), result)
    assert(reports.length == 1 && reports.head.contains("'some test' of SomeSuite"), reports)
    assert(halted.isEmpty)
  }

  test("a test that ignores the interrupt is reported with every stack, and the JVM halted") {
    val stop = new AtomicBoolean(false)
    var spins = 0L
    // A CPU-bound loop that checks no interrupt, as a compiler loop does; the halt stub
    // releases it, where the real halt would not need to.
    val (result, reports, halted) =
      run(millis = 100, grace = 100, onHalt = () => stop.set(true)) {
        while (!stop.get()) {
          spins += 1
        }
        spins
      }
    assert(halted.contains(VarkaTestWatchdog.HALT_STATUS), (halted, result))
    assert(reports.length == 2, reports.map(_.take(80)))
    assert(reports(1).contains("is still running") && reports(1).contains("varka-watchdog:"),
      reports(1).take(300))
    // The dump names this suite's own frame, so a reader sees what the test was doing.
    assert(reports(1).contains(getClass.getSimpleName), reports(1).take(300))
  }
}
