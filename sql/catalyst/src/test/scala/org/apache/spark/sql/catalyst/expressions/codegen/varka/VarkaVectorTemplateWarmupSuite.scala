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
 * The Vector API template warm-up (VARKA-295): it runs the same calls every time and computes

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
