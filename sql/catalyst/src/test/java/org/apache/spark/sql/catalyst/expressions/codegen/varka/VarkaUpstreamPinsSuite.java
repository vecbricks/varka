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
package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The upstream behaviours Varka's tests work around, pinned (VARKA-504): each test fails when the
 * behaviour goes away, so the workaround and its pin are retired together rather than the
 * workaround staying on as dead code. Spark's cache pruning of NaN batches (SPARK-58482) needs a
 * session and is pinned in sql/core.
 */
@VarkaSuite
public final class VarkaUpstreamPinsSuite {

  /** The operand, read per call so that C2 cannot fold the overflow into a constant throw. */
  private static volatile long top = Long.MAX_VALUE;

  /** The message of {@code Math.addExact(a, 1)}'s overflow, or "none" when it does not raise. */
  private static String overflowMessage(long a) {
    try {
      return "none " + Math.addExact(a, 1L);
    } catch (ArithmeticException e) {
      return e.getMessage() == null && e.getStackTrace().length == 0 ? null : e.getMessage();
    }
  }

  @Test
  @DisplayName("a hot Math.addExact overflow loses its message under OmitStackTraceInFastThrow, "
      + "which VarkaMaybeThrows, VarkaExpressionHarness and VarkaAnsiForm accept")
  void hotOverflowLosesItsMessage() {
    // Cold, the overflow carries its message, which the row engine's errors are compared on.
    assertEquals("long overflow", overflowMessage(Long.MAX_VALUE));
    // Hot, C2's intrinsic for the exact arithmetic (inline_math_mathExact) throws the JVM's
    // preallocated ArithmeticException once the site has trapped often enough: no message and
    // no stack trace. Should a JDK keep the message, the workarounds that accept a messageless
    // error are dead, and this test says so.
    long deadline = System.nanoTime() + 60_000_000_000L;
    long calls = 0;
    String message = "long overflow";
    while (message != null && System.nanoTime() < deadline) {
      for (int i = 0; i < 10_000 && message != null; i++) {
        message = overflowMessage(top);
        calls++;
      }
    }
    assertTrue(message == null, "after " + calls + " hot overflows the message is still \""
        + message + "\": the JVM no longer drops it (-XX:-OmitStackTraceInFastThrow?)");
  }
}
