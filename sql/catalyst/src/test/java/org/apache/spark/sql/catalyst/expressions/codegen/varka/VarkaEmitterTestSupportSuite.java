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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VarkaEmitterTestSupport}'s reads of the JVM's code list (VARKA-442): the levels it
 * reports for a kernel's loops, and its policy for a JVM that does not serve the diagnostic
 * command, which the benchmarks meet as an arm that is timed as it is rather than a run that
 * ends.
 */
@VarkaSuite
public final class VarkaEmitterTestSupportSuite {

  /** A class with a dense loop method, so a readable code list gives the method a level. */
  private static final class Probe {
    static void loopDense0() { }
  }

  /** A line of the code list's shape for {@code loopDense0}: compile id, level, state, method. */
  private static String codeList() {
    return "1 4 0 " + Probe.class.getName() + ".loopDense0()V [0x0000, 0x0000 - 0x0001]";
  }

  @Test
  @DisplayName("a readable code list gives each loop of the side the level it holds")
  void readableCodeList() {
    Map<String, Integer> dense = VarkaEmitterTestSupport.loopLevels(Probe.class, true,
        VarkaEmitterTestSupportSuite::codeList);
    assertEquals(Set.of("loopDense0"), dense.keySet());
    assertEquals(4, dense.get("loopDense0").intValue());
    assertTrue(VarkaEmitterTestSupport.allLoopsC2(dense));
    assertTrue(VarkaEmitterTestSupport.loopLevels(Probe.class, false,
        VarkaEmitterTestSupportSuite::codeList).isEmpty(), "Probe has no masked loop");
  }

  @Test
  @DisplayName("an unreadable code list keeps each loop at no level, so no arm settles on it")
  void unreadableCodeList() {
    Supplier<String> unavailable = () -> {
      throw new IllegalStateException("could not read the code list");
    };
    Map<String, Integer> levels = VarkaEmitterTestSupport.loopLevels(Probe.class, true,
        unavailable);
    assertEquals(Set.of("loopDense0"), levels.keySet(),
        "the declared loop names survive an unreadable list");
    assertEquals(0, levels.get("loopDense0").intValue(), "no loop reads as compiled");
    assertFalse(VarkaEmitterTestSupport.allLoopsC2(levels),
        "an unreadable list must not read as C2 code");
  }
}
