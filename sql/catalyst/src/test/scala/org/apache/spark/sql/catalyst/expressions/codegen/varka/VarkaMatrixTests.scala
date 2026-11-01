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
import org.scalatest.{Args, Status, Tag}

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.util.ArrowUtils

/**
 * Runs each test of a Varka suite through [[VarkaMatrix.around]]: under the option matrix, a test
 * the skip list expects a configuration to break is cancelled with the entry's reason, or fails
 * as a stale entry when it passes, and each test's fused batches are recorded for the runner.
 * A test tagged `VarkaMatrix.PinsDefaults` is cancelled under any configuration. Outside the
 * matrix it changes nothing. `VarkaTestWatchdog` extends it; a suite that runs
 * without the watchdog, such as a fuzzer whose tests outlast its cap, mixes in this alone.
 *
 * Under the memory sanitizer (`-Dvarka.sanitizeMemory=true`, VARKA-263) it also holds each suite to
 * leave Arrow's root allocator where it found it: the level when the suite starts is the baseline,
 * and whatever is above it once the suite has cleaned up fails the suite by name, as Trino asserts
 * every memory pool is back to zero after each test class. Off, it changes nothing.
 */
trait VarkaMatrixTests extends SparkFunSuite {

  /**
   * Under the sanitizer, the suite's whole run - `beforeAll`, the tests, `afterAll` - is measured
   * against Arrow's root allocator, and a suite that leaves memory allocated is aborted by name.
   * It wraps `run`, which every suite inherits with the same public signature, and not the
   * `beforeAll` and `afterAll` hooks, which some suites widen to public and the rest keep
   * protected.
   */
  override def run(testName: Option[String], args: Args): Status = {
    // The option matrix's reach recording takes what the suite compiles outside a test - in its
    // set-up, between its tests - as every one of its tests' (VarkaTestReach).
    VarkaTestReach.suiteBegin(suiteName)
    try {
      val status = runSanitized(testName, args)
      status.waitUntilCompleted()
      status
    } finally VarkaTestReach.suiteEnd(suiteName)
  }

  private def runSanitized(testName: Option[String], args: Args): Status = {
    if (!VarkaMemorySanitizer.ENABLED) {
      return super.run(testName, args)
    }
    val before = ArrowUtils.rootAllocator.getAllocatedMemory
    val status = super.run(testName, args)
    status.waitUntilCompleted()
    val leaked = ArrowUtils.rootAllocator.getAllocatedMemory - before
    if (leaked != 0L) {
      if (sys.props.get("arrow.memory.debug.allocator").contains("true")) {
        // The allocator's own account of what is outstanding, with the stack that allocated each
        // buffer: how the first leak the sanitizer found, a test's, was traced.
        // scalastyle:off println
        System.err.println(ArrowUtils.rootAllocator.toVerboseString)
        // scalastyle:on println
      }
      throw new IllegalStateException(s"$suiteName left $leaked bytes of Arrow memory allocated" +
        " (the memory sanitizer's every-byte-back check): a buffer was not closed")
    }
    status
  }

  // The option matrix's reach recording, where the run asks for one, listens from the suite's
  // construction on, before its set-up compiles anything (VarkaTestReach).
  VarkaTestReach.install()

  override protected def test(testName: String, testTags: Tag*)(testBody: => Any)
      (implicit pos: Position): Unit = {
    if (VarkaMatrix.config.nonEmpty && testTags.contains(VarkaMatrix.PinsDefaults)) {
      super.test(testName, testTags: _*)(
        cancel(s"pins the defaults' structure; not run under ${VarkaMatrix.config}"))
    } else if (VarkaMatrix.config.nonEmpty && testTags.contains(VarkaMatrix.C2Code) &&
        !VarkaMatrixRuns.changesC2(VarkaMatrix.config)) {
      super.test(testName, testTags: _*)(cancel(VarkaMatrixRuns.c2CodeMessage(VarkaMatrix.config)))
    } else {
      super.test(testName, testTags: _*)(VarkaMatrix.around(suiteName, testName)(testBody))
    }
  }
}
