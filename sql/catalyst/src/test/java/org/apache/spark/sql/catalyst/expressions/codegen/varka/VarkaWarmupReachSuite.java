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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The template warm-up calls every Vector API method an emitted kernel calls at least
 * {@link VarkaWarmupReach#OLD_MARK} times (VARKA-397): the methods read off the kernels of the
 * coverage table, the fuzz grammar and the matrix's options, the warm-up's calls counted by a
 * counting copy of its rounds. A lane row that adds a call the warm-up does not make fails here,
 * naming the method, the call and the shape; the fix is a call in the warm-up's rounds, in a block
 * of {@code THIN_CALLS_PER_ROUND}.
 */
@VarkaSuite
public final class VarkaWarmupReachSuite {

  private static Map<String, String> corpus;

  /** The corpus's counted methods, emitted once per JVM for the tests that read them. */
  private static synchronized Map<String, String> corpus() throws IOException {
    if (corpus == null) {
      String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
      assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
      corpus = VarkaWarmupReach.corpusTargets(Path.of(home, "sql", "varka", "coverage.json"));
    }
    return corpus;
  }

  @Test
  @DisplayName("every Vector API method an emitted kernel calls gets 2,000 calls from the warm-up")
  void everyMethodWarmed() throws IOException {
    Map<String, String> emitted = corpus();
    // A scan that read nothing would pass; the coverage rows alone reach 60 methods.
    assertTrue(emitted.size() >= 60, "only " + emitted.size() + " methods read: " + emitted);
    List<String> under = VarkaWarmupReach.underMark(emitted,
        VarkaWarmupReach.warmupCounts(Set.of()), VarkaWarmupReach.OLD_MARK);
    assertTrue(under.isEmpty(), under.size() + " of the " + emitted.size() + " methods the "
        + "kernels call get fewer than " + VarkaWarmupReach.OLD_MARK + " calls from the "
        + "warm-up's rounds; call each in a block of THIN_CALLS_PER_ROUND in "
        + "VarkaVectorTemplateWarmup.Rounds:\n  " + String.join("\n  ", under));
  }

  @Test
  @DisplayName("a call is counted exactly, and one the rounds stop making is named with no calls")
  void plantedOmission() throws IOException {
    // A call of the doubles' block, counted as if it were not there.
    var planted = new VarkaWarmupReach.Call("DoubleVector", "add",
        "(D)Ljdk/incubator/vector/DoubleVector;");
    String target = VarkaWarmupReach.dispatch(planted, null);
    assertTrue(corpus().containsKey(target), target + " is not in the corpus: " + corpus());
    // Counted, it has exactly the calls its block makes: a counter that credited a call twice, or
    // credited one call site's calls to another, would show here.
    long block = VarkaVectorTemplateWarmup.ROUNDS * VarkaVectorTemplateWarmup.THIN_CALLS_PER_ROUND;
    assertEquals(block, VarkaWarmupReach.warmupCounts(Set.of()).get(target));
    List<String> under = VarkaWarmupReach.underMark(corpus(),
        VarkaWarmupReach.warmupCounts(Set.of(planted)), VarkaWarmupReach.OLD_MARK);
    assertTrue(under.stream().anyMatch(l -> l.startsWith(target + ": 0 calls")),
        "the planted omission of " + target + " is not named: " + under);
  }

  @Test
  @DisplayName("the mark is the JVM's: Tier3InvocationThreshold scaled by TieredOldPercentage")
  void markIsTheJvms() {
    HotSpotDiagnosticMXBean bean =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    long threshold = Long.parseLong(bean.getVMOption("Tier3InvocationThreshold").getValue());
    // TieredOldPercentage is a diagnostic flag, visible only under -XX:+UnlockDiagnosticVMOptions;
    // otherwise it holds its default, 1000 in JDK 25's compiler_globals.hpp.
    long percentage = 1000;
    try {
      percentage = Long.parseLong(bean.getVMOption("TieredOldPercentage").getValue());
    } catch (IllegalArgumentException hidden) {
      // the default stands
    }
    assertEquals(VarkaWarmupReach.OLD_MARK, threshold * percentage / 100,
        "CompilationPolicy::is_old's mark moved: Tier3InvocationThreshold " + threshold
            + ", TieredOldPercentage " + percentage);
  }
}
