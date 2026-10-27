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
package org.apache.spark.sql.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Locale;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan;
import org.apache.spark.sql.internal.SQLConf;

/**
 * What the two-lane refusal's host suites check, in the JVM their flags start
 * ({@link VarkaTwoLaneHost128Suite}, {@link VarkaTwoLaneHost256Suite}): that both the compiler's
 * host fact, {@link VarkaKernelPlan#HOST_TWO_LANE_MASKS_FALL_BACK}, and the default a session that
 * does not set {@code spark.sql.codegen.varka.declineTwoLaneConditions} resolves give the answer
 * the host the flags make must give.
 *
 * <p>The host fact is computed from what this JVM reports, so a test that compares it with the
 * same computation proves nothing; these suites fix the host from outside instead, with the JVM's
 * flags, and state the answer that host must give. The lane count is not read here: sql/core's
 * Java tests compile without the incubator Vector API module under Maven.
 */
final class VarkaTwoLaneHostCheck {

  private VarkaTwoLaneHostCheck() {}

  /** Whether this JVM runs on x86, where {@code -XX:UseAVX} and the two-lane refusal apply. */
  static boolean isX86() {
    String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    return arch.equals("amd64") || arch.equals("x86_64");
  }

  /**
   * Checks the refusal on this JVM: {@code onX86} where it is x86 at {@code UseAVX} of at least
   * {@code minUseAvx}, and false on any other architecture, whose back end lowers two-lane masks.
   * An x86 JVM below that level (a host without AVX2 asked for 256 bits) does not have the
   * vector width the case is about, so the case is cancelled.
   */
  static void check(int minUseAvx, boolean onX86) {
    int useAvx = VarkaEmitOptions.HOST_USE_AVX;
    boolean expected = false;
    if (isX86()) {
      assumeTrue(useAvx >= minUseAvx, "UseAVX=" + useAvx + " on this x86 host, below "
          + minUseAvx);
      expected = onX86;
    }
    String host = System.getProperty("os.arch") + " at UseAVX=" + useAvx;
    assertEquals(expected, VarkaKernelPlan.HOST_TWO_LANE_MASKS_FALL_BACK,
        "the compiler's host fact on " + host);
    assertEquals(expected, VarkaSessionEmit.of(new SQLConf()).declineTwoLaneConditions(),
        "the session's default on " + host);
  }
}
