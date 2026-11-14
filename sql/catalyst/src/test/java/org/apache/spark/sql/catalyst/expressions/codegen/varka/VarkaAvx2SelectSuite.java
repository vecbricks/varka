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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaCompiledCheck.History;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * Row 396's shrunk cases as regression tests, under AVX2 (VARKA-491). JDK 25's C2 miscompiles
 * {@code VectorMask.fromLong(s, m.toLong())} on x86 without AVX-512 mask registers. A masked body
 * whose condition's columns have no nulls in a lane group makes that round trip under
 * {@code fromLongMasks}: C2-compiled, its select takes the ELSE branch on every true lane. At the
 * defaults the emitter ANDs the word with an all-ones C2 cannot fold first, on the hosts where
 * the miscompile can happen ({@code VarkaMaskGuard.OPEN}). This suite runs each case through the
 * compiled histories of {@link VarkaCompiledCheck} in a JVM of its own started with
 * {@code -XX:UseAVX=2}, so an AVX-512 host checks it too; a JVM that is not x86 ignores the flag.
 *
 * <p>The cases are the shrinks the fuzzer printed, trees, options and batch as written there:
 * three from the runner night of 19 October 2026 and one from 24 October, with the 24 October
 * shape again at the int lane, since the rule matches every lane type. The failure is a matter of
 * the JIT's timing, so a pass proves less than the byte check of {@code VarkaMaskOfWordSuite},
 * which holds the emitter to the form whatever the JIT does.
 */
@VarkaSuite(ownJvm = true, jvmFlags = {"-XX:+IgnoreUnrecognizedVMOptions", "-XX:UseAVX=2"})
public final class VarkaAvx2SelectSuite {

  /**
   * One shrunk case: its trees in the canonical form, its options as the matrix writes them
   * (empty for the defaults), its literals and columns ({@code null} a null row), whether the
   * masked body was forced, and the history it failed in, which the case must reach to count.
   */
  private record Case(String label, LaneType lane, List<String> roots, String options,
      long[] lits, Long[][] columns, boolean forceMasked, History history) {}

  /** The tries a case gets to reach the history it failed in. */
  private static final int TRIES = 3;

  /**
   * Runs {@code c} through every history, failing on a wrong answer. A run that did not reach
   * the history the case failed in checked nothing the bug needs, so the case runs again, and is
   * cancelled, not passed, when no try reached it.
   */
  private static void check(Case c) {
    List<VarkaVectorIR> roots = c.roots().stream().map(VarkaCanonicalIr::parse).toList();
    VarkaEmitOptions options = c.options().isEmpty() ? VarkaEmitOptions.DEFAULTS
        : VarkaMatrixRuns.parse(c.options());
    int rows = c.columns()[0].length;
    boolean[][] nulls = new boolean[c.columns().length][rows];
    long[][] data = new long[c.columns().length][rows];
    for (int col = 0; col < c.columns().length; col++) {
      for (int i = 0; i < rows; i++) {
        Long v = c.columns()[col][i];
        nulls[col][i] = v == null;
        data[col][i] = v == null ? 0 : v;
      }
    }
    var fuzzCase = new VarkaFuzzCase(c.lane(), roots, c.columns().length, c.lits(), rows, nulls,
        data, c.forceMasked(), options, -1, -1, c.label());
    var settings = VarkaCompiledCheck.Settings.fromProperties();
    String prefix = "history not reached: " + c.history().label + ",";
    List<String> missed = List.of();
    for (int i = 0; i < TRIES; i++) {
      var tally = new VarkaCompiledCheck.Tally();
      tally.add(VarkaCompiledCheck.run(fuzzCase,
          name -> VarkaLoopEmitter.emit(name, roots, c.columns().length, c.lits().length, null,
              null, options),
          settings, c.label()));
      if (!settings.witnessed()) {
        return;
      }
      // The verdicts are read once the witness has seen every compile and trap of the run.
      VarkaTierWitness.get().settle();
      missed = VarkaCompiledCheck.notReached(tally).stream()
          .filter(m -> m.startsWith(prefix))
          .toList();
      if (missed.isEmpty()) {
        return;
      }
    }
    assumeTrue(false, TRIES + " tries did not reach " + c.history().label + ": " + missed);
  }

  /** The options the 24 October shrink kept off; the rest are the defaults. */
  private static final String OCT24_OPTIONS = "rangeSets=false,splitConditions=false,"
      + "groupLocalSlots=false,materializeChronoPrefix=false,predictGrouping=false,"
      + "driverOutputTable=false,splitDriver=false,severalKernels=false,"
      + "elideUnreadLocals=false,planSize=false,groupBudgetUnderPrediction=true";

  /** The 24 October batch: seven rows, a lane group of four and a three-row masked epilogue. */
  private static final Long[][] OCT24_COLUMNS = {
    {4460076517051L, null, 18898746763276L, null, -50266100486726L, null, -49607829850524L},
    {4421922265370L, 35258963307884L, -59385115539699L, null, 38382192361867L, 5079289584248L,
        null},
    {-36599559927214L, null, -25562645782204L, null, -41690266522730L, null, 41053864906336L}};

  /** The 24 October batch at the int lane: each value's low half, as random as the whole. */
  private static Long[][] lowHalves(Long[][] columns) {
    var out = new Long[columns.length][];
    for (int c = 0; c < columns.length; c++) {
      out[c] = new Long[columns[c].length];
      for (int i = 0; i < columns[c].length; i++) {
        out[c][i] = columns[c][i] == null ? null : (long) columns[c][i].intValue();
      }
    }
    return out;
  }

  @Test
  @DisplayName("the child runs under AVX2 where it is x86")
  void runsUnderAvx2() {
    String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    if (VarkaHostIsa.isX86(arch)) {
      var hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      // The flag lowers UseAVX to at most 2; a host without AVX2 starts lower still, and is
      // checked all the same.
      int useAvx = Integer.parseInt(hotspot.getVMOption("UseAVX").getValue());
      assertTrue(useAvx <= 2, "UseAVX=" + useAvx + " in the AVX2 child");
      assertTrue(VarkaMaskGuard.OPEN == 0L, "the mask guard is open in the AVX2 child");
    }
  }

  @Test
  @DisplayName("seed 202610241's long-lane select answers right in every compiled history")
  void oct24Long() {
    check(new Case("202610241/10", LaneType.LONG,
        List.of("(if (cmp:GT col:0:long col:1:long) col:0:long (int:ADD:NULL col:1:long "
            + "col:2:long))"),
        OCT24_OPTIONS, new long[0], OCT24_COLUMNS, true, History.WARM_SAME));
  }

  @Test
  @DisplayName("the same select at the int lane answers right in every compiled history")
  void oct24Int() {
    check(new Case("202610241/10 at the int lane", LaneType.INT,
        List.of("(if (cmp:GT col:0 col:1) col:0 (int:ADD:NULL col:1 col:2))"),
        OCT24_OPTIONS, new long[0], lowHalves(OCT24_COLUMNS), true, History.WARM_SAME));
  }

  @Test
  @DisplayName("seed 202610190's one-row select answers right in every compiled history")
  void oct19RemainderLong() {
    check(new Case("202610190/17", LaneType.LONG,
        List.of("(if (cmp:GE col:0:long col:0:long) col:0:long lit:0:long)",
            "(int:SUB:NULL col:0:long col:0:long)"),
        "", new long[] {1033515L, -380240L}, new Long[][] {{0L}}, false,
        History.QUIET_REMAINDER));
  }

  @Test
  @DisplayName("seed 202610191's int-lane select over dates answers right in every compiled "
      + "history")
  void oct19DatesInt() {
    check(new Case("202610191/19", LaneType.INT,
        List.of("(if (cmp:GE (nextDay lit:0 col:1) (weekOfYear (thursdayOf lit:0))) "
            + "(if (cmp:GT col:2 col:1) lit:0 col:1) (int:ADD:WRAP col:1 lit:0))"),
        "shareChronoPrefix=false,elideChronoMonth=false,neriSchneiderMonth=false,"
            + "guardDayProducers=false,validityByWidth=false,validityByBitmap=false,"
            + "checkIntOverflow=false,truncDate=RECOMPOSE,floorMod7=DIV,division=DOUBLE_RECIP,"
            + "guardUnderArm=false,shareWholeNodes=false,validityByWord=true,mulHiDivide=false,"
            + "splitConditions=false,heavyGroupOutputs=24,predictGrouping=false,planSize=false,"
            + "groupBudgetUnderPrediction=true",
        new long[] {1870L}, new Long[][] {{2088399L}, {8L}, {null}}, false, History.RELEASED));
  }

  /**
   * Seed 202610193's shrink printed its fifteen rows' first eight; the last seven follow the
   * same patterns, the first two columns alternating with nulls and the third random. With the
   * batch so completed the case is a test of the shape: under {@code fromLongMasks} it was not
   * shown to fail, its {@code warm-same} history not reached in three tries.
   */
  @Test
  @DisplayName("seed 202610193's greatest of a select answers right in every compiled history")
  void oct19GreatestLong() {
    Long n = null;
    check(new Case("202610193/10", LaneType.LONG,
        List.of("(greatest (int:SUB:NULL lit:1:long lit:1:long) (if (cmp:GT col:0:long "
            + "col:2:long) col:0:long col:2:long))"),
        "", new long[] {139196L, -42871L}, new Long[][] {
          {20308794457513L, n, -53641677463336L, n, -10370579422076L, n, -66086446597541L, n,
              31415926535897L, n, -27182818284590L, n, 16180339887498L, n, -14142135623730L},
          {28936040454758L, n, 5771471121234L, n, -37433794358376L, n, 56993516262945L, n,
              -17320508075688L, n, 22360679774997L, n, -26457513110645L, n, 30000000000001L},
          {29207714741784L, 9140448796737L, 61721271750381L, n, n, n, n, n,
              -44721359549995L, n, 12345678901234L, -98765432109876L, n, 55555555555555L, n}},
        false, History.WARM_SAME));
  }
}
