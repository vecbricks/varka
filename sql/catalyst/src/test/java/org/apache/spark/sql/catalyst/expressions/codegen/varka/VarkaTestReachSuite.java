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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The option matrix's reach by test (VARKA-507, {@link VarkaTestReach}): the recorder names a
 * configuration for a test exactly when the configuration changes something the test compiled or
 * emitted, and a configuration's run skips a test only where the record says it may.
 */
@VarkaSuite
public final class VarkaTestReachSuite {

  private static final String PLANTED = "misdescribeAdd=true";
  private static final String INERT = "cse=false";

  private static VarkaVectorIR days(boolean add) {
    return add ? new AddDays(new ColumnRef(0), new ColumnRef(1))
        : new SubDays(new ColumnRef(0), new ColumnRef(1));
  }

  /** A recorder over two configurations, listening for one test. */
  private static VarkaTestReach.Recorder recording() {
    var r = new VarkaTestReach.Recorder(List.of(PLANTED, INERT));
    r.current = ConcurrentHashMap.newKeySet();
    return r;
  }

  @Test
  @DisplayName("a kernel is named under the configuration that moves its bytes and no other")
  void emittedKernelNamesItsMover() {
    var r = recording();
    r.emitted(List.of(days(true)), 2, 0, VarkaEmitOptions.DEFAULTS);
    assertEquals(Set.of(PLANTED), r.current);
    var s = recording();
    s.emitted(List.of(days(false)), 2, 0, VarkaEmitOptions.DEFAULTS);
    assertEquals(Set.of(), s.current, "date_sub emits alike under both configurations");
  }

  /** A recorder listening for one test, its digests kept. */
  private static VarkaTestReach.Recorder digesting() {
    var r = recording();
    r.currentDigests = new ConcurrentHashMap<>();
    return r;
  }

  @Test
  @DisplayName("a test's digest moves under the configuration that moves its bytes and no other")
  void digestMovesWithTheBytes() {
    var r = digesting();
    r.emitted(List.of(days(true)), 2, 0, VarkaEmitOptions.DEFAULTS);
    var d = VarkaTestReach.digests(r.currentDigests, List.of(PLANTED, INERT));
    assertEquals(Set.of(VarkaTestReach.DEFAULTS, PLANTED, INERT), d.keySet());
    assertNotEquals(d.get(VarkaTestReach.DEFAULTS), d.get(PLANTED));
    assertEquals(d.get(VarkaTestReach.DEFAULTS), d.get(INERT));
  }

  @Test
  @DisplayName("a test's digest is the same in whatever order it compiled its kernels")
  void digestIgnoresOrder() {
    var a = digesting();
    a.emitted(List.of(days(true)), 2, 0, VarkaEmitOptions.DEFAULTS);
    a.emitted(List.of(days(false)), 2, 0, VarkaEmitOptions.DEFAULTS);
    var b = digesting();
    b.emitted(List.of(days(false)), 2, 0, VarkaEmitOptions.DEFAULTS);
    b.emitted(List.of(days(true)), 2, 0, VarkaEmitOptions.DEFAULTS);
    var configs = List.of(PLANTED, INERT);
    assertEquals(VarkaTestReach.digests(a.currentDigests, configs),
        VarkaTestReach.digests(b.currentDigests, configs));
    var one = digesting();
    one.emitted(List.of(days(false)), 2, 0, VarkaEmitOptions.DEFAULTS);
    assertNotEquals(VarkaTestReach.digests(a.currentDigests, configs),
        VarkaTestReach.digests(one.currentDigests, configs), "a kernel more is another digest");
  }

  @Test
  @DisplayName("what differs between two JVMs' renderings of one compile is left out of its digest")
  void stableRenderingDropsIds() {
    assertEquals(VarkaTestReach.stable("(a#12 + b#3) @1b2c3d4e $$Lambda/0x00007f0012345678"),
        VarkaTestReach.stable("(a#7 + b#40) @9f8e7d6c $$Lambda/0x00007f00abcdef00"));
  }

  @Test
  @DisplayName("a compile is named under a configuration that changes its result")
  void compileNamesWhatChangesItsResult() {
    var r = recording();
    // A compile that declines at the defaults and compiles something under the planted arm.
    r.compiled("inputs", VarkaEmitOptions.DEFAULTS, Optional.empty(),
        o -> o.misdescribeAdd() ? Optional.of("compiled") : Optional.empty());
    assertEquals(Set.of(PLANTED), r.current);
  }

  @Test
  @DisplayName("a recompile that throws moves its compile under that configuration")
  void throwingRecompileMoves() {
    var r = recording();
    r.compiled("inputs", VarkaEmitOptions.DEFAULTS, Optional.empty(), o -> {
      if (o.misdescribeAdd()) {
        throw new IllegalStateException("planted");
      }
      return Optional.empty();
    });
    assertEquals(Set.of(PLANTED), r.current);
  }

  @Test
  @DisplayName("a configuration already in the options a test compiled under moves nothing")
  void pinnedOptionMovesNothing() {
    var r = recording();
    VarkaEmitOptions pinned = VarkaMatrixRuns.parse(PLANTED);
    r.emitted(List.of(days(true)), 2, 0, pinned);
    assertEquals(Set.of(), r.current, "the test pins what the configuration sets");
  }

  private static Optional<VarkaTestReach.Record> record(String... lines) throws IOException {
    Path f = Files.createTempFile("varka-reach", ".tsv");
    try {
      Files.write(f, List.of(lines), StandardCharsets.UTF_8);
      return VarkaTestReach.read(f);
    } finally {
      Files.delete(f);
    }
  }

  @Test
  @DisplayName("a run skips a recorded test its configuration does not move, and nothing else")
  void skipsOnlyWhatTheRecordAllows() throws IOException {
    var reach = record("#\tcovers\t" + PLANTED + "," + INERT,
        "S\tmoved\t" + PLANTED, "S\tstill\t-", "S\tcancelled\t*");
    assertTrue(VarkaTestReach.skips(reach, INERT, "S", "moved", false, false));
    assertFalse(VarkaTestReach.skips(reach, PLANTED, "S", "moved", false, false));
    assertTrue(VarkaTestReach.skips(reach, PLANTED, "S", "still", false, false));
    assertFalse(VarkaTestReach.skips(reach, PLANTED, "S", "unrecorded", false, false),
        "a test the record has no line for runs");
    assertFalse(VarkaTestReach.skips(reach, INERT, "S", "cancelled", false, false),
        "a test that cancelled at the defaults runs everywhere");
    assertFalse(VarkaTestReach.skips(reach, PLANTED, "S", "still", true, false),
        "a test the skip list names under the configuration runs");
    assertFalse(VarkaTestReach.skips(reach, "", "S", "still", false, false),
        "the defaults run runs everything");
    assertFalse(VarkaTestReach.skips(Optional.empty(), PLANTED, "S", "still", false, false),
        "a run with no record runs everything");
    assertFalse(VarkaTestReach.skips(reach, "fusedCeiling=200", "S", "still", false, false),
        "a configuration that runs every test runs it");
  }

  @Test
  @DisplayName("a suite's set-up and the JVM's own compiles join every test they cover")
  void suiteAndJvmLinesJoinTheirTests() throws IOException {
    var suite = record("S\tstill\t-", "T\tstill\t-", "S\t*\t" + PLANTED);
    assertFalse(VarkaTestReach.skips(suite, PLANTED, "S", "still", false, false),
        "S's set-up moved under the planted arm");
    assertTrue(VarkaTestReach.skips(suite, PLANTED, "T", "still", false, false));
    var jvm = record("S\tstill\t-", "T\tstill\t-", "*\t*\t" + INERT);
    assertFalse(VarkaTestReach.skips(jvm, INERT, "T", "still", false, false));
  }

  @Test
  @DisplayName("a record its recording marked broken is no record")
  void brokenRecordIsNone() throws IOException {
    assertTrue(record("S\tstill\t-", "#\tbroken\tjava.lang.IllegalStateException: x")
        .isEmpty());
  }

  @Test
  @DisplayName("what is compiled outside a test goes to the suite, and outside a suite to the JVM")
  void outsideATestGoesToTheSuiteOrTheJvm() {
    var r = new VarkaTestReach.Recorder(List.of(PLANTED, INERT));
    r.suite = ConcurrentHashMap.newKeySet();
    r.emitted(List.of(days(true)), 2, 0, VarkaEmitOptions.DEFAULTS);
    assertEquals(Set.of(PLANTED), r.suite);
    r.suite = null;
    r.emitted(List.of(days(true)), 3, 0, VarkaEmitOptions.DEFAULTS);
    assertEquals(Set.of(PLANTED), r.outside);
  }

  @Test
  @DisplayName("a paused recorder, in a suite the recording leaves out, hears nothing")
  void pausedRecorderHearsNothing() {
    var r = recording();
    r.paused = true;
    r.emitted(List.of(days(true)), 2, 0, VarkaEmitOptions.DEFAULTS);
    r.compiled("inputs", VarkaEmitOptions.DEFAULTS, Optional.empty(),
        o -> Optional.of("compiled"));
    assertEquals(Set.of(), r.current);
    assertEquals(Set.of(), r.outside);
  }

  @Test
  @DisplayName("every configuration that runs every test is one the matrix has, with a reason")
  void fullRunConfigurationsAreTheMatrixs() {
    for (Map.Entry<String, String> e : VarkaTestReach.FULL.entrySet()) {
      assertTrue(VarkaMatrixRuns.configurations().stream()
              .anyMatch(c -> c.equals(e.getKey()) || c.startsWith(e.getKey() + "=")),
          e.getKey() + " is listed as running every test but names no configuration");
      assertFalse(e.getValue().isBlank(), e.getKey() + " has no reason");
    }
    assertFalse(VarkaTestReach.runsEveryTest(INERT));
    assertFalse(VarkaTestReach.runsEveryTest("denseValidityOnce=false"),
        "the subject's single option is not the subject");
  }
}
