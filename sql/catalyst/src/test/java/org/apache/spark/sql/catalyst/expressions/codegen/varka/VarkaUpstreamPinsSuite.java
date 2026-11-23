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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;

import com.sun.management.HotSpotDiagnosticMXBean;
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

  /** A product flag of the running JVM, or {@code absent} when this JVM has no such flag. */
  private static String flag(HotSpotDiagnosticMXBean bean, String name, String absent) {
    try {
      return bean.getVMOption(name).getValue();
    } catch (IllegalArgumentException missing) {
      return absent;
    }
  }

  /**
   * Whether this JVM drops a hot exact-arithmetic overflow's message at all: only C2's intrinsic
   * throws the preallocated exception, and only under OmitStackTraceInFastThrow. C1 alone (a
   * quick-only mode, or tiers stopped below 4), the interpreter ({@code -Xint}) and a JVMCI
   * compiler such as Graal keep the message, and there the pin has nothing to pin.
   */
  private static boolean c2DropsHotMessages() {
    HotSpotDiagnosticMXBean bean =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    boolean tiersReachC2 = !Boolean.parseBoolean(flag(bean, "TieredCompilation", "true"))
        || Integer.parseInt(flag(bean, "TieredStopAtLevel", "4")) >= 4;
    return Boolean.parseBoolean(flag(bean, "UseCompiler", "true"))
        && !Boolean.parseBoolean(flag(bean, "UseJVMCICompiler", "false"))
        && !"quick-only".equals(flag(bean, "CompilationMode", "default"))
        && tiersReachC2
        && Boolean.parseBoolean(flag(bean, "OmitStackTraceInFastThrow", "false"));
  }

  @Test
  @DisplayName("a hot Math.addExact overflow loses its message under OmitStackTraceInFastThrow, "
      + "which VarkaMaybeThrows, VarkaExpressionHarness, VarkaAnsiForm and "
      + "VarkaFilterRaiseOrderSuite accept")
  void hotOverflowLosesItsMessage() {
    assumeTrue(c2DropsHotMessages(),
        "the pin holds only under C2 with OmitStackTraceInFastThrow, which this JVM does not run");
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
        + message + "\": C2 no longer drops it");
  }
}
