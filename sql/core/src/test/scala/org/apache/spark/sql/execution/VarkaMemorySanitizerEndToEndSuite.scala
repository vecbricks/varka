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

import org.apache.arrow.memory.ArrowBuf
import org.scalatest.{Args, Reporter}
import org.scalatest.events.Event

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaMatrixTests,
  VarkaMemorySanitizer, VarkaMemoryViolation, VarkaSegments, VarkaTestWatchdog}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.varka.vector.VarkaVectorSupport

/**
 * The memory sanitizer (VARKA-263) on the real evaluator path. These tests need the sanitizer on
 * (`-Dvarka.sanitizeMemory=true`) and are cancelled without it; the one that does not need it,
 * "the suites run with the sanitizer on", is what fails when a suite JVM was meant to have it and
 * does not, so that "the suites and the fuzzers run with it" is checked and not claimed.
 */
class VarkaMemorySanitizerEndToEndSuite
    extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

  private def withSanitizer(body: => Unit): Unit = {
    assume(VarkaMemorySanitizer.ENABLED, "run with -Dvarka.sanitizeMemory=true")
    body
  }

  /**
   * Caches a `varka_bools` view in both sessions: two nullable boolean columns `b` and `b2` beside
   * an int `i`, over 100 rows. 100 is not a multiple of eight or of 64, so a bitmap read or
   * written over it ends in a partial byte, a partial word and a partial lane group.
   */
  private def cacheBools(): Unit = {
    val rows = (0 until 100).map { i =>
      (if (i % 7 == 3) null: java.lang.Boolean else Boolean.box(i % 2 == 0),
        if (i % 5 == 4) null: java.lang.Boolean else Boolean.box(i % 3 == 0), i)
    }
    for (session <- Seq(spark, varkaSpark)) {
      session.createDataFrame(rows).toDF("b", "b2", "i").createOrReplaceTempView("varka_bools")
      session.catalog.cacheTable("varka_bools")
    }
  }

  test("a Varka projection's kernel maps its buffers through the sanitizer") {
    withSanitizer {
      cacheDates(spark)
      val query = "SELECT date_add(d, 3) AS a, date_sub(d, 5) AS b FROM varka_dates ORDER BY a"
      val expected = spark.sql(query)
      cacheDates(varkaSpark)
      val before = VarkaMemorySanitizer.checked()
      val actual = varkaSpark.sql(query)
      assertFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, expected)
      assertKernelsRan(actual.queryExecution.executedPlan)
      assert(VarkaMemorySanitizer.checked() > before,
        "the projection's kernel mapped nothing in a window: the sanitizer did not reach it")
    }
  }

  test("a boolean column's kernel maps its one-bit-a-row data inside the buffer") {
    withSanitizer {
      // A bit vector's data buffer holds (rows + 7) / 8 bytes, a thirty-second of what a lane's
      // width a row would map; 100 rows leave a partial last byte and a partial last group.
      cacheBools()
      val query = "SELECT i FROM varka_bools WHERE b AND (NOT b2 OR i > 50) ORDER BY i"
      val expected = spark.sql(query)
      val before = VarkaMemorySanitizer.checked()
      val actual = varkaSpark.sql(query)
      assertFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, expected)
      assertKernelsRan(actual.queryExecution.executedPlan)
      assert(VarkaMemorySanitizer.checked() > before, "the kernel mapped nothing in a window")
    }
  }

  test("a boolean output's kernel writes its data bitmap inside the buffer, a word at a time") {
    withSanitizer {
      // VARKA-27: a BOOLEAN output's data is a bitmap of whole words, which the guard admits and
      // nothing past; 100 rows end in a partial word. Under -Dvarka.checkBothBodies (the option
      // matrix sets both flags) the other body writes buffers of its own of the same size.
      cacheBools()
      val query = "SELECT i, b AND b2 AS c, (i > 50) OR b AS e, b <=> b2 AS n FROM varka_bools " +
        "ORDER BY i"
      val expected = spark.sql(query)
      val before = VarkaMemorySanitizer.checked()
      val actual = varkaSpark.sql(query)
      assertFused(actual.queryExecution.executedPlan)
      checkAnswer(actual, expected)
      assertKernelsRan(actual.queryExecution.executedPlan)
      assert(VarkaMemorySanitizer.checked() > before, "the kernel mapped nothing in a window")
    }
  }

  test("a mapping one byte past a registered buffer fails through the engine's ofAddress") {
    withSanitizer {
      val buf = ArrowUtils.rootAllocator.buffer(64L)
      try {
        VarkaMemorySanitizer.begin()
        try {
          VarkaMemorySanitizer.register("input data", 7, buf)
          VarkaVectorSupport.ofAddress(buf.memoryAddress(), buf.capacity())
          val e = intercept[VarkaMemoryViolation] {
            VarkaVectorSupport.ofAddress(buf.memoryAddress(), buf.capacity() + 1)
          }
          assert(e.getMessage.contains("input data 7"), e.getMessage)
        } finally {
          VarkaMemorySanitizer.end()
        }
      } finally {
        buf.close()
      }
    }
  }

  test("a mapping through catalyst's VarkaSegments is checked the same way") {
    withSanitizer {
      val buf = ArrowUtils.rootAllocator.buffer(64L)
      try {
        VarkaMemorySanitizer.begin()
        try {
          VarkaMemorySanitizer.register("input validity", 3, buf)
          VarkaSegments.map(buf.memoryAddress(), buf.capacity())
          val e = intercept[VarkaMemoryViolation] {
            VarkaSegments.map(buf.memoryAddress() + 8L, buf.capacity())
          }
          assert(e.getMessage.contains("input validity 3"), e.getMessage)
        } finally {
          VarkaMemorySanitizer.end()
        }
      } finally {
        buf.close()
      }
    }
  }

  test("an overwritten canary fails the check made when the kernel returns") {
    withSanitizer {
      val bytes = 64L
      val buf = ArrowUtils.rootAllocator.buffer(
        bytes + VarkaMemorySanitizer.CANARY_BYTES)
      try {
        VarkaMemorySanitizer.begin()
        try {
          VarkaMemorySanitizer.guard("output data", 5, buf, bytes)
          VarkaMemorySanitizer.verifyCanaries()
          buf.setByte(bytes, 0)
          val e = intercept[VarkaMemoryViolation] { VarkaMemorySanitizer.verifyCanaries() }
          assert(e.getMessage.contains("output data 5"), e.getMessage)
        } finally {
          VarkaMemorySanitizer.end()
        }
      } finally {
        buf.close()
      }
    }
  }

  test("a suite that leaves Arrow memory allocated is aborted by name") {
    // Catches: the every-byte-back check being a no-op, which a suite that never leaks cannot
    // tell from one that works.
    withSanitizer {
      val leaked = new java.util.concurrent.atomic.AtomicReference[ArrowBuf]()
      class Leaker extends VarkaMatrixTests {
        test("allocates and forgets") {
          leaked.set(ArrowUtils.rootAllocator.buffer(128L))
        }
      }
      val silent = new Reporter {
        override def apply(event: Event): Unit = {}
      }
      try {
        val e = intercept[IllegalStateException] {
          new Leaker().run(None, Args(silent))
        }
        assert(e.getMessage.contains("Leaker"), e.getMessage)
        assert(e.getMessage.contains("128 bytes"), e.getMessage)
      } finally {
        leaked.get().close()
      }
    }
  }

  test("a suite that cleans up after itself passes the check") {
    withSanitizer {
      class Tidy extends VarkaMatrixTests {
        test("allocates and closes") {
          ArrowUtils.rootAllocator.buffer(128L).close()
        }
      }
      val silent = new Reporter {
        override def apply(event: Event): Unit = {}
      }
      new Tidy().run(None, Args(silent))
    }
  }

  test("a violation is not a catchable kernel failure") {
    // Catches: the ghost fallback turning a memory violation into a row-engine answer.
    val violation = new VarkaMemoryViolation("test")
    assert(!org.apache.spark.sql.execution.varka.VarkaKernelRunner.isCatchable(violation))
    assert(org.apache.spark.sql.execution.varka.VarkaKernelRunner.isCatchable(
      new IllegalStateException("an ordinary kernel failure")))
  }
}
