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

import scala.jdk.CollectionConverters._

import org.scalatest.BeforeAndAfterEach

import org.apache.spark.SparkFunSuite

/**
 * The Vector API template warm-up (VARKA-295): it runs the same calls every time and computes a
 * result from them, runs once per JVM however many threads ask, makes a thread that arrives during
 * the run wait for it, and never fails the thread that calls it. Whether it removes the templates'
 * traps is measured, not asserted here: `VARKA-295.md` 9.7 has the fuzz arm and the reproducer.
 */
class VarkaVectorTemplateWarmupSuite extends SparkFunSuite with BeforeAndAfterEach
    with VarkaTestWatchdog {

  import VarkaVectorTemplateWarmup._

  // The warm-up is JVM-wide state; leave it as a JVM that has run it, whatever a test did.
  override def afterEach(): Unit = {
    try {
      resetForTest(null)
      ensure()
    } finally {
      super.afterEach()
    }
  }

  test("the calls are deterministic and their results are used") {
    val first = warmUp()
    assert(first !== 0L, "a zero checksum would mean the calls computed nothing")
    assert(warmUp() === first)
  }

  test("ensure runs the warm-up once, then does nothing") {
    resetForTest(null)
    assert(ensure() > 0L, "the first call must run the calls")
    assert(completedRuns() === 1)
    assert(ensure() === 0L, "the second call has nothing to do")
    assert(completedRuns() === 1)
  }

  test("two threads calling ensure run the body once") {
    resetForTest(null)
    val results = new java.util.concurrent.ConcurrentLinkedQueue[java.lang.Long]()
    val threads = (1 to 2).map { _ =>
      val t = new Thread(() => results.add(ensure()))
      t.start()
      t
    }
    threads.foreach(_.join())
    assert(completedRuns() === 1)
    assert(results.asScala.count(_ > 0L) === 1, s"results $results")
  }

  /**
   * Starts a run held at its gate (and failing with `failure` once released, if not null), calls
   * `body` on a second thread once the run is claimed, waits until that thread is parked on the
   * run's latch, then opens the gate. Returns what `body`
   * returned and whether the run had ended when the second thread returned.
   */
  private def duringARun[T](body: () => T, failure: Throwable = null): (T, Boolean) = {
    resetForTest(failure)
    val gate = new java.util.concurrent.CountDownLatch(1)
    holdRunsForTest(gate)
    val runner = new Thread(() => ensure())
    runner.start()
    while (!runClaimed()) {
      Thread.onSpinWait()
    }
    @volatile var result: Option[(T, Boolean)] = None
    @volatile var thrown: Throwable = null
    val waiter = new Thread(() => {
      try {
        val r = body()
        result = Some((r, runner.getState == Thread.State.TERMINATED || !gateClosed(gate)))
      } catch {
        case t: Throwable => thrown = t
      }
    })
    waiter.start()
    // Parked means still WAITING 50 ms after first seen so: a thread can pass through a short wait
    // of the JVM's own on its way to the latch.
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
    var parked = false
    while (!parked && waiter.isAlive && System.nanoTime() < deadline) {
      if (waiter.getState == Thread.State.WAITING) {
        Thread.sleep(50)
        parked = waiter.getState == Thread.State.WAITING
      } else {
        Thread.onSpinWait()
      }
    }
    gate.countDown()
    waiter.join()
    runner.join()
    if (thrown != null) {
      throw thrown
    }
    assert(parked, "the second thread must wait while the run is held")
    result.get
  }

  private def gateClosed(gate: java.util.concurrent.CountDownLatch): Boolean = gate.getCount > 0

  test("a thread that arrives during the run waits for it to end") {
    val (returned, ended) = duringARun(() => ensure())
    assert(returned === 0L, "the waiter did not run the warm-up")
    assert(ended, "the waiter must not return before the run has been released")
    assert(completedRuns() === 1, "a second run must never start")
  }

  test("an interrupted waiter still waits for the run, and keeps its interrupt") {
    val (interrupted, ended) = duringARun { () =>
      Thread.currentThread().interrupt()
      ensure()
      Thread.currentThread().isInterrupted
    }
    assert(ended, "an interrupt must not let a thread run vector code before the warm-up ends")
    assert(interrupted, "the interrupt is put back on the thread")
    assert(completedRuns() === 1)
  }

  test("a waiter wakes when the run fails") {
    val (returned, ended) = duringARun(() => ensure(), new InternalError("injected"))
    assert(returned === 0L)
    assert(ended)
    assert(completedRuns() === 0, "the run failed")
    assert(nanos() === 0L)
  }

  test("an interrupt does not cut the pacing's wait short, and is kept") {
    Thread.currentThread().interrupt()
    val started = System.nanoTime()
    val waited = awaitC2Queue(200, () => 1000)
    val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    // Thread.interrupted() reads and clears the flag, so the thread leaves the test clean.
    assert(Thread.interrupted(), "the interrupt is put back on the thread")
    assert(waited >= 200L, s"an interrupted wait must still last its budget, waited $waited ms")
    assert(elapsed < 5000, s"and end at it, took $elapsed ms")
    assert(awaitC2Queue(5000, () => -1) < 1000L, "no reading of the queue means no wait")
  }

  test("C2's threads are counted as HotSpot splits CICompilerCount") {
    // CompilationPolicy::initialize: C1 gets max(count / 3, 1), C2 the rest, at least one.
    assert(c2Threads(1) === 1)
    assert(c2Threads(2) === 1, "the count of one to three processors leaves C2 one thread")
    assert(c2Threads(3) === 2, "four processors")
    assert(c2Threads(4) === 3)
    assert(c2Threads(12) === 8, "24 processors")
    // The count HotSpot's ergonomics choose at two and three processors, from a JVM of each.
    val javaBin = s"${System.getProperty("java.home")}/bin/java"
    for (processors <- Seq(2, 3)) {
      val builder = new ProcessBuilder(javaBin, s"-XX:ActiveProcessorCount=$processors",
        "-XX:+PrintFlagsFinal", "-version").redirectErrorStream(true)
      // Options from the environment could set the count or the compilers themselves.
      Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
        .foreach(builder.environment().remove(_))
      val process = builder.start()
      val flags = new String(process.getInputStream.readAllBytes(), "UTF-8")
      assert(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "the JVM must exit")
      assert(process.exitValue() === 0)
      val count = flags.linesIterator.map(_.trim.split("\\s+"))
        .collectFirst { case f if f.length > 3 && f(1) == "CICompilerCount" => f(3).toInt }
      assert(count === Some(2), s"CICompilerCount at $processors processors")
      assert(c2Threads(count.get) === 1, s"one C2 thread at $processors processors")
    }
  }

  test("a failure, an Error included, is absorbed and the thread goes on") {
    resetForTest(new InternalError("injected"))
    assert(ensure() === 0L, "a failed run reports no time")
    assert(completedRuns() === 0)
    // The worker's guard: the call must not throw out of the thread that makes it.
    resetForTest(new InternalError("injected"))
    @volatile var escaped: Throwable = null
    val t = new Thread(() => {
      VarkaKernelWarmup.templateWarmup()
      // A statement after the call that only a surviving thread reaches.
      completedAfter = true
    })
    t.setUncaughtExceptionHandler((_, e) => escaped = e)
    t.start()
    t.join()
    assert(escaped === null)
    assert(completedAfter, "the thread must reach the code after the warm-up")
    // And the warm-up still works once the cause is gone.
    resetForTest(null)
    assert(ensure() > 0L)
  }

  test("beforeVectorCode runs the warm-up once, on the caller's thread, then is settled") {
    resetForTest(null)
    beforeVectorCode()
    assert(completedRuns() === 1, "the first entry point to arrive runs the warm-up")
    assert(nanos() > 0L, "the run's time is kept for the worker's log line and the tests")
    assert(VarkaKernelWarmup.templateWarmupNanos() === nanos())
    beforeVectorCode()
    assert(completedRuns() === 1, "settled: one volatile read, no second run")
    // Switched off, the hook settles without running, and a JVM that decided off stays off.
    resetForTest(null)
    System.setProperty(PROPERTY, "false")
    try {
      beforeVectorCode()
      assert(completedRuns() === 0)
      assert(nanos() === 0L)
    } finally {
      System.clearProperty(PROPERTY)
    }
    beforeVectorCode()
    assert(completedRuns() === 0, "a JVM that decided off stays off")
  }

  test("the pacing reads C2's queue as the JVM prints it, within its budget") {
    val drained = "Current compiles:\n\nC1 compile queue:\nEmpty\n\nC2 compile queue:\nEmpty\n"
    assert(c2QueueLength(drained) === 0)
    val busyC1Only = "Current compiles:\n\nC1 compile queue:\n" +
      "   10 3  java.lang.String::hashCode (49 bytes)\n\nC2 compile queue:\nEmpty\n"
    assert(c2QueueLength(busyC1Only) === 0, "C1's queue is not what the pacing waits for")
    val busyC2 = "Current compiles:\n\nC1 compile queue:\nEmpty\n\nC2 compile queue:\n" +
      "   11 4  java.lang.String::hashCode (49 bytes)\n" +
      "   12 4  java.lang.String::equals (11 bytes)\n"
    assert(c2QueueLength(busyC2) === 2)
    assert(c2QueueLength("") === 0, "a format the parser does not know must not hold the warm-up")
    val busyJvmci = "Current compiles:\n\nC1 compile queue:\nEmpty\n\nJVMCI compile queue:\n" +
      "   11 4  java.lang.String::hashCode (49 bytes)\n"
    assert(c2QueueLength(busyJvmci) === 1, "a JVMCI compiler's queue stands in C2's place")
    assert(PROFILING_TIER_QUEUE_LIMIT >= 1)
    assert(PACE_QUEUE_TARGET >= 1 && PACE_QUEUE_TARGET <= PROFILING_TIER_QUEUE_LIMIT,
      "the pacing waits for the shorter queue at which the policy resumes profiling")
    assert(THIN_CALLS_PER_ROUND * ROUNDS >= 2000,
      "every one-line wrapper must pass the policy's old mark of 2,000 calls inside the rounds")
    val started = System.nanoTime()
    val waited = awaitC2Queue(300)
    val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assert(waited >= 0L && waited <= elapsed + 1)
    assert(elapsed < 5000, s"the wait must end within its budget, took $elapsed ms")
  }

  test("the property switches the warm-up off") {
    resetForTest(null)
    System.setProperty(PROPERTY, "false")
    try {
      assert(ensure() === 0L)
      assert(completedRuns() === 0)
    } finally {
      System.clearProperty(PROPERTY)
    }
  }

  @volatile private var completedAfter = false
}
