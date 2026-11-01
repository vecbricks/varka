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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns.Kind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns.Skip;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Case;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Failed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Passed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Skipped;

/**
 * The JUnit face of the Varka harness (VARKA-312), tested as {@code VarkaMatrixSuite} tests the
 * ScalaTest face: through the extension's seam, {@code VarkaSuiteExtension.runTest}, with the
 * skip list's entries, the configuration, the report file and the watchdog's clock passed in, so
 * that a planted entry or a configuration needs no change to {@code sql/varka/matrix/skips.tsv}
 * and no matrix JVM. The listener, the class-level checks and a class in its own JVM are tested
 * end to end, by running the small fixture classes below through the JUnit Launcher in this JVM.
 *
 * <p>The fixtures are JUnit classes, so sbt may find and run them on their own too. Each passes
 * there: their failures are planted only while a test here runs them ({@link #planted}).
 */
@VarkaSuite
public final class VarkaSuiteHarnessSuite {

  private static final String SUITE = "VarkaSuiteHarnessSuite";

  /** Set while a test here runs a fixture whose failures it plants; off when sbt runs one. */
  private static volatile boolean planted;

  private static Object run(String config, List<Skip> entries, Set<String> tags,
      VarkaMatrixRuns.Body<Object> body) throws Throwable {
    return VarkaSuiteExtension.runTest(config, entries, Optional.empty(),
        VarkaSuiteExtension.Guard.NONE, SUITE, "t", tags, false, body);
  }

  // ---------------------------------------------------------------------------------------------
  // The matrix's skip list, PinsDefaults and C2Code, through the seam
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a fails entry cancels its test with the entry's reason")
  void failsEntryCancels() {
    var entries = List.of(new Skip("cse=false", SUITE, "t", Kind.FAILS, "a planted reason"));
    var cancel = assertThrows(TestAbortedException.class, () -> run("cse=false", entries,
        Set.of(), () -> {
          throw new IllegalStateException("the configuration breaks it");
        }));
    assertTrue(cancel.getMessage().contains("a planted reason")
        && cancel.getMessage().contains("IllegalStateException"), cancel.getMessage());
    // Under another configuration the same entry does not apply, and the failure is a failure.
    assertThrows(IllegalStateException.class, () -> run("division=DOUBLE_DIV", entries,
        Set.of(), () -> {
          throw new IllegalStateException("the configuration breaks it");
        }));
  }

  @Test
  @DisplayName("a stale entry fails its test, and the body's own cancel passes through")
  void staleEntryFails() throws Throwable {
    var entries = List.of(new Skip("cse=false", SUITE, "t", Kind.FAILS, "a planted reason"));
    var stale = assertThrows(AssertionFailedError.class,
        () -> run("cse=false", entries, Set.of(), () -> 42));
    assertTrue(stale.getMessage().contains("stale entry"), stale.getMessage());
    var own = assertThrows(TestAbortedException.class, () -> run("cse=false", entries, Set.of(),
        () -> {
          throw new TestAbortedException("the body's own");
        }));
    assertEquals("the body's own", own.getMessage());
    assertEquals(42, run("", entries, Set.of(), () -> 42));
  }

  @Test
  @DisplayName("a PinsDefaults test is cancelled under a configuration and runs at the defaults")
  void pinsDefaults() throws Throwable {
    var ran = new AtomicInteger();
    var pins = Set.of(VarkaSuite.PINS_DEFAULTS);
    var cancel = assertThrows(TestAbortedException.class,
        () -> run("cse=false", List.of(), pins, ran::incrementAndGet));
    assertEquals(VarkaMatrixRuns.pinsDefaultsMessage("cse=false"), cancel.getMessage());
    assertEquals(0, ran.get());
    assertEquals(1, run("", List.of(), pins, ran::incrementAndGet));
    assertEquals(2, run("cse=false", List.of(), Set.of("another.tag"), ran::incrementAndGet));
  }

  @Test
  @DisplayName("a C2Code test is cancelled under a configuration that changes only the bytes, "
      + "and runs where C2 is given something else and at the defaults")
  void c2Code() throws Throwable {
    var ran = new AtomicInteger();
    var c2 = Set.of(VarkaSuite.C2_CODE);
    var cancel = assertThrows(TestAbortedException.class,
        () -> run("cse=false", List.of(), c2, ran::incrementAndGet));
    assertEquals(VarkaMatrixRuns.c2CodeMessage("cse=false"), cancel.getMessage());
    assertEquals(0, ran.get());
    assertEquals(1, run("lanesOverride=4", List.of(), c2, ran::incrementAndGet));
    assertEquals(2, run("", List.of(), c2, ran::incrementAndGet));
  }

  @Test
  @DisplayName("the fused-batch report gets the suite's simple name and the test's display name")
  void fusedReport(TestInfo info, @TempDir Path dir) throws Throwable {
    Path report = dir.resolve("fused.tsv");
    VarkaSuiteExtension.runTest("", List.of(), Optional.of(report.toString()),
        VarkaSuiteExtension.Guard.NONE, SUITE, info.getDisplayName(), Set.of(), false,
        () -> null);
    // Nothing counts batches in catalyst's tests, so the count is -1, "not counted".
    long fused = VarkaMatrixRuns.fusedBatches().getAsLong() < 0 ? -1 : 0;
    assertEquals(SUITE + "\t" + info.getDisplayName() + "\t" + fused + "\n",
        Files.readString(report));
  }

  // ---------------------------------------------------------------------------------------------
  // The watchdog, inside the matrix, on this test's own thread
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the watchdog's interrupt reaches a JUnit test's thread, inside the matrix")
  void watchdogInterrupts() {
    var reports = new ArrayList<String>();
    var halted = new AtomicInteger(-1);
    VarkaSuiteExtension.Guard guard = (s, t, body) -> VarkaWatchdog.guard(s, t, 100, 5000,
        m -> {
          synchronized (reports) {
            reports.add(m);
          }
        }, halted::set, body);
    try {
      assertThrows(InterruptedException.class, () -> VarkaSuiteExtension.runTest("", List.of(),
          Optional.empty(), guard, SUITE, "blocks", Set.of(), false, () -> {
            Thread.sleep(60000);
            return null;
          }));
    } finally {
      Thread.interrupted();
    }
    synchronized (reports) {
      assertEquals(1, reports.size(), reports.toString());
      assertTrue(reports.get(0).contains("'blocks' of " + SUITE), reports.get(0));
    }
    assertEquals(-1, halted.get());
  }

  @Test
  @DisplayName("the watchdog halts a JUnit test that ignores the interrupt")
  void watchdogHalts() throws Throwable {
    var reports = new ArrayList<String>();
    var halted = new AtomicInteger(-1);
    var stop = new AtomicBoolean(false);
    // A CPU-bound loop that checks no interrupt; the halt stub releases it, as the real halt
    // would not need to.
    VarkaSuiteExtension.Guard guard = (s, t, body) -> VarkaWatchdog.guard(s, t, 100, 100,
        m -> {
          synchronized (reports) {
            reports.add(m);
          }
        }, status -> {
          halted.set(status);
          stop.set(true);
        }, body);
    try {
      VarkaSuiteExtension.runTest("", List.of(), Optional.empty(), guard, SUITE, "spins",
          Set.of(), false, () -> {
            long spins = 0;
            while (!stop.get()) {
              spins++;
            }
            return spins;
          });
    } finally {
      Thread.interrupted();
    }
    assertEquals(VarkaWatchdog.HALT_STATUS, halted.get());
    synchronized (reports) {
      assertEquals(2, reports.size());
      // The stack dump names this suite's frame, so a reader sees what the test was doing.
      assertTrue(reports.get(1).contains("is still running")
          && reports.get(1).contains(getClass().getSimpleName()), reports.get(1));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // A class in its own JVM
  // ---------------------------------------------------------------------------------------------

  /** Passes only where its body runs in a child JVM; in the shared one it takes the child's. */
  @VarkaSuite(ownJvm = true)
  static final class OwnJvmFixture {
    @Test
    @DisplayName("runs in the child JVM")
    void runsInChild() {
      assertTrue(VarkaChildJvm.inChild(), "the body ran in the shared JVM");
    }
  }

  @Test
  @DisplayName("an ownJvm class's test runs in the child and reports there")
  void ownJvmRunsInChild() {
    var results = launch(OwnJvmFixture.class, Optional.empty());
    assertEquals(Set.of("runs in the child JVM"), results.keySet());
    TestExecutionResult result = results.get("runs in the child JVM");
    assertEquals(TestExecutionResult.Status.SUCCESSFUL, result.getStatus(),
        () -> result.getThrowable().map(Throwable::toString).orElse(""));
  }

  @Test
  @DisplayName("a failure in the child fails its test in the parent with the child's message")
  void childFailureFailsParent(@TempDir File dir) throws Exception {
    VarkaSuiteReport.write(dir, "p.Fixture", 1.5, List.of(
        new Case("passes", new Passed(), 0.5),
        new Case("fails <&> \"quoted\"", new Failed("output 0 row 7 differs"), 0.5),
        new Case("cancelled", new Skipped("not run here"), 0.0)), Optional.empty());
    var results = new VarkaChildJvm.Results(VarkaSuiteReport.read(dir), Optional.empty());
    assertEquals(null, VarkaSuiteExtension.answerFromChild(results, "passes"));
    var failure = assertThrows(AssertionFailedError.class,
        () -> VarkaSuiteExtension.answerFromChild(results, "fails <&> \"quoted\""));
    assertEquals("in its own JVM: output 0 row 7 differs", failure.getMessage());
    var cancel = assertThrows(TestAbortedException.class,
        () -> VarkaSuiteExtension.answerFromChild(results, "cancelled"));
    // The child's own reason, which names the configuration and the skip-list entry.
    assertEquals("not run here", cancel.getMessage());
    var missing = assertThrows(AssertionFailedError.class,
        () -> VarkaSuiteExtension.answerFromChild(results, "absent"));
    assertTrue(missing.getMessage().contains("no result for 'absent'"), missing.getMessage());
  }

  @Test
  @DisplayName("the parent takes the child's verdict as it is, without the matrix again")
  void parentKeepsChildVerdict(@TempDir Path dir) throws Throwable {
    // The child applied the skip list: a stale entry failed there, and a listed failure was
    // cancelled there. The parent must neither turn the stale failure into a cancel nor write a
    // fused-batch line of its own over the child's.
    var entries = List.of(new Skip("cse=false", SUITE, "t", Kind.FAILS, "a planted reason"));
    Path report = dir.resolve("fused.tsv");
    var stale = assertThrows(AssertionFailedError.class, () -> VarkaSuiteExtension.runTest(
        "cse=false", entries, Optional.of(report.toString()), VarkaSuiteExtension.Guard.NONE,
        SUITE, "t", Set.of(), true, () -> Assertions.fail("in its own JVM: stale entry ...")));
    assertEquals("in its own JVM: stale entry ...", stale.getMessage());
    var cancel = assertThrows(TestAbortedException.class, () -> VarkaSuiteExtension.runTest(
        "cse=false", entries, Optional.of(report.toString()), VarkaSuiteExtension.Guard.NONE,
        SUITE, "t", Set.of(), true, () -> {
          throw new TestAbortedException("the child's reason");
        }));
    assertEquals("the child's reason", cancel.getMessage());
    assertTrue(Files.notExists(report), () -> "the parent wrote " + report);
  }

  @Test
  @DisplayName("the child's budget follows the class's watchdog minutes, and 0 means none")
  void childBudget() {
    assertEquals(OptionalLong.empty(), VarkaChildJvm.budgetMinutes(0));
    assertEquals(OptionalLong.of(1), VarkaChildJvm.budgetMinutes(2));
    assertEquals(OptionalLong.of(29), VarkaChildJvm.budgetMinutes(30));
  }

  /**
   * A stand-in for a child's runner: writes one passing test to the report directory it is given
   * ({@code -u <dir>}), with a class-level abort when told {@code abort}, and exits 1.
   */
  public static final class ExitingChild {
    public static void main(String[] args) throws Exception {
      VarkaSuiteReport.write(new File(args[1]), "p.Fixture", 0.1,
          List.of(new Case("passes", new Passed(), 0.1)),
          args[2].equals("abort") ? Optional.of("a planted abort") : Optional.empty());
      System.exit(1);
    }
  }

  @Test
  @DisplayName("a child whose class aborted, or that exited non-zero, fails its passed tests")
  void childAbortFailsParent() throws Exception {
    var aborted = VarkaChildJvm.run("p.Fixture", ExitingChild.class.getName(),
        List.of("abort"), "-u");
    assertTrue(aborted.of("passes").orElseThrow() instanceof Failed f
        && f.message().contains("a planted abort"), aborted.toString());
    var exited = VarkaChildJvm.run("p.Fixture", ExitingChild.class.getName(),
        List.of("exit"), "-u");
    assertTrue(exited.of("passes").orElseThrow() instanceof Failed f
        && f.message().contains("exit 1"), exited.toString());
  }

  // ---------------------------------------------------------------------------------------------
  // The class-level checks and the listener, end to end
  // ---------------------------------------------------------------------------------------------

  /** Two tests with one name: never run (JUnit runs no abstract class), only read. */
  abstract static class RepeatedNames {
    @Test
    @DisplayName("one name")
    void first() {}

    @Test
    @DisplayName("one name")
    void second() {}

    @Test
    void unnamed() {}
  }

  @Test
  @DisplayName("a class whose tests share a name is refused, and a method's default name counts")
  void repeatedNamesRefused() {
    List<String> names = VarkaSuiteExtension.displayNames(RepeatedNames.class);
    assertEquals(Set.of("one name", "unnamed()"), Set.copyOf(names));
    assertEquals(3, names.size());
    assertEquals(List.of("one name"), VarkaSuiteExtension.repeatedNames(names));
    assertEquals(List.of(),
        VarkaSuiteExtension.repeatedNames(VarkaSuiteExtension.displayNames(getClass())));
  }

  /** A repeated test beside a dynamic one: never run, only read. */
  @VarkaSuite
  abstract static class TemplateTests {
    @RepeatedTest(2)
    @DisplayName("repeated")
    void repeated() {}

    @TestFactory
    Stream<DynamicTest> dynamic() {
      return Stream.of(DynamicTest.dynamicTest("one", () -> { }));
    }
  }

  /** Dynamic tests alone, which the harness holds: never run, only read. */
  @VarkaSuite
  abstract static class DynamicTests {
    @TestFactory
    Stream<DynamicTest> dynamic() {
      return Stream.of(DynamicTest.dynamicTest("one", () -> { }));
    }
  }

  /** Dynamic tests in a class whose child JVM answers by method: never run, only read. */
  @VarkaSuite(ownJvm = true)
  abstract static class OwnJvmDynamicTests {
    @TestFactory
    Stream<DynamicTest> dynamic() {
      return Stream.of(DynamicTest.dynamicTest("one", () -> { }));
    }
  }

  /** A class grouping tests in a nested class: never run, only read. */
  abstract static class NestedTests {
    @Nested
    class Inner {
      @Test
      @DisplayName("inside")
      void inside() {}
    }
  }

  /** The annotation on a base class only, which dev/varka_matrix.sh cannot see. */
  @VarkaSuite
  abstract static class AnnotatedBase {}

  abstract static class InheritsOnly extends AnnotatedBase {
    @Test
    @DisplayName("inherited")
    void inherited() {}
  }

  @Test
  @DisplayName("a template test, an ownJvm class's dynamic test, a nested class and an inherited "
      + "annotation are refused, and a dynamic test is held")
  void shapesRefused() {
    var template = assertThrows(IllegalStateException.class,
        () -> VarkaSuiteExtension.checkShape(TemplateTests.class));
    assertTrue(template.getMessage().contains("repeated")
        && !template.getMessage().contains("dynamic"), template.getMessage());
    VarkaSuiteExtension.checkShape(DynamicTests.class);
    var ownJvm = assertThrows(IllegalStateException.class,
        () -> VarkaSuiteExtension.checkShape(OwnJvmDynamicTests.class));
    assertTrue(ownJvm.getMessage().contains("ownJvm"), ownJvm.getMessage());
    var nested = assertThrows(IllegalStateException.class,
        () -> VarkaSuiteExtension.checkShape(NestedTests.class));
    assertTrue(nested.getMessage().contains("@Nested"), nested.getMessage());
    var inherited = assertThrows(IllegalStateException.class,
        () -> VarkaSuiteExtension.checkShape(InheritsOnly.class));
    assertTrue(inherited.getMessage().contains("on the class itself"), inherited.getMessage());
    VarkaSuiteExtension.checkShape(getClass());
  }

  @TestFactory
  @DisplayName("dynamic tests run under the harness")
  Stream<DynamicTest> dynamicTestsRun() {
    return Stream.of(DynamicTest.dynamicTest("a dynamic test of the harness's own suite",
        () -> assertTrue(true)));
  }

  /** One test of each outcome while planted; four passing tests when sbt runs it alone. */
  @VarkaSuite
  static final class ListenerFixture {
    @Test
    @DisplayName("a test that passes")
    void passes() {}

    @Test
    @DisplayName("a test that fails, when planted")
    void fails() {
      if (planted) {
        Assertions.fail("a planted failure\nits second line");
      }
    }

    @Test
    @DisplayName("a test that is cancelled, when planted")
    void cancelled() {
      Assumptions.assumeFalse(planted, "not run here");
    }

    @Test
    @DisabledIf("isPlanted")
    @DisplayName("a test that is disabled, when planted")
    void disabled() {}

    static boolean isPlanted() {
      return planted;
    }
  }

  /** A class whose class-level hook fails, while planted. */
  @VarkaSuite
  static final class AbortingFixture {
    @BeforeAll
    static void setUp() {
      if (planted) {
        throw new IllegalStateException("a planted abort");
      }
    }

    @Test
    @DisplayName("never runs when planted")
    void never() {}
  }

  @Test
  @DisplayName("the listener prints ScalaTest's lines, counts each outcome and writes the XML")
  void listenerLines(@TempDir File dir) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var listener = new VarkaSuiteListener(Optional.of(dir),
        new PrintStream(bytes, true, StandardCharsets.UTF_8));
    planted = true;
    try {
      launch(ListenerFixture.class, Optional.of(listener));
      launch(AbortingFixture.class, Optional.of(listener));
    } finally {
      planted = false;
    }
    List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
    // JUnit orders the methods of a class its own way, so the test lines are compared as a set.
    assertEquals("ListenerFixture:", lines.get(0));
    assertEquals(Set.of(
        "- a test that passes",
        "- a test that fails, when planted *** FAILED ***",
        "  a planted failure",
        "  its second line",
        "- a test that is cancelled, when planted !!! CANCELED !!!",
        "  Assumption failed: not run here",
        "- a test that is disabled, when planted !!! IGNORED !!!"),
        Set.copyOf(lines.subList(1, 8)));
    int failed = lines.indexOf("- a test that fails, when planted *** FAILED ***");
    assertEquals("  a planted failure", lines.get(failed + 1));
    assertEquals(List.of("AbortingFixture:",
        "Exception encountered when attempting to run a suite with class name: "
            + AbortingFixture.class.getName() + " *** ABORTED ***",
        "  a planted abort"), lines.subList(8, lines.size()));
    assertEquals(new VarkaSuiteListener.Counts(1, 1, 1, 1, 0, 1, 1), listener.counts());

    Map<String, VarkaSuiteReport.Result> report = VarkaSuiteReport.read(dir);
    assertEquals(new Passed(), report.get("a test that passes"));
    assertEquals(new Failed("in its own JVM: a planted failure\nits second line"),
        report.get("a test that fails, when planted"));
    assertTrue(report.get("a test that is cancelled, when planted") instanceof Skipped);
    assertTrue(report.get("a test that is disabled, when planted") instanceof Skipped);
    String xml = Files.readString(
        dir.toPath().resolve("TEST-" + ListenerFixture.class.getName() + ".xml"));
    assertTrue(xml.contains("name=\"" + ListenerFixture.class.getName() + "\"")
        && xml.contains("tests=\"4\"") && xml.contains("failures=\"1\"")
        && xml.contains("skipped=\"2\"") && xml.matches("(?s).*time=\"[0-9]+\\.[0-9]{3}\".*"),
        xml);
    String aborted = Files.readString(
        dir.toPath().resolve("TEST-" + AbortingFixture.class.getName() + ".xml"));
    assertTrue(aborted.contains("errors=\"1\"") && aborted.contains("a planted abort"), aborted);
  }

  /** A class whose class-level assumption fails, while planted: JUnit aborts the class. */
  @VarkaSuite
  static final class AssumingFixture {
    @BeforeAll
    static void setUp() {
      Assumptions.assumeFalse(planted, "not here");
    }

    @Test
    @DisplayName("never runs when planted")
    void never() {}
  }

  @Test
  @DisplayName("a class aborted by an assumption is an aborted suite, not an empty completed one")
  void assumptionAbortsClass(@TempDir File dir) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var listener = new VarkaSuiteListener(Optional.of(dir),
        new PrintStream(bytes, true, StandardCharsets.UTF_8));
    planted = true;
    try {
      launch(AssumingFixture.class, Optional.of(listener));
    } finally {
      planted = false;
    }
    assertEquals(List.of("AssumingFixture:",
        "Exception encountered when attempting to run a suite with class name: "
            + AssumingFixture.class.getName() + " *** ABORTED ***",
        "  Assumption failed: not here"),
        bytes.toString(StandardCharsets.UTF_8).lines().toList());
    assertEquals(new VarkaSuiteListener.Counts(0, 0, 0, 0, 0, 0, 1), listener.counts());
    String xml = Files.readString(
        dir.toPath().resolve("TEST-" + AssumingFixture.class.getName() + ".xml"));
    assertTrue(xml.contains("errors=\"1\""), xml);
  }

  @Test
  @DisplayName("the runner keeps -z and -t with their -s, for a ScalaTest suite and a JUnit class")
  void runnerFilters(@TempDir File dir) throws Exception {
    String watchdog = VarkaSuiteHarnessSuite.class.getPackageName() + ".VarkaTestWatchdogSuite";
    // The runner prints its summary lines to standard output; they are kept out of this JVM's
    // log, where they would read as a second run's.
    PrintStream out = System.out;
    System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try {
      VarkaSuiteRunner.run(new String[] {"-u", dir.toString(),
          "-s", watchdog, "-z", "finishes in time",
          "-s", ListenerFixture.class.getName(), "-z", "passes",
          "-t", "a test that is disabled, when planted"});
    } finally {
      System.setOut(out);
    }
    String scala = Files.readString(dir.toPath().resolve("TEST-" + watchdog + ".xml"));
    assertTrue(scala.contains("tests=\"1\"") && scala.contains("finishes in time"), scala);
    String junit = Files.readString(
        dir.toPath().resolve("TEST-" + ListenerFixture.class.getName() + ".xml"));
    assertTrue(junit.contains("tests=\"2\"") && junit.contains("a test that passes")
        && junit.contains("a test that is disabled, when planted"), junit);
  }

  @Test
  @DisplayName("the listener is silent for a class not under VarkaSuite")
  void listenerSilent() {
    var bytes = new ByteArrayOutputStream();
    var listener = new VarkaSuiteListener(Optional.empty(),
        new PrintStream(bytes, true, StandardCharsets.UTF_8));
    var results = launch(PlainFixture.class, Optional.of(listener));
    assertEquals(Set.of("plain()"), results.keySet());
    assertEquals("", bytes.toString(StandardCharsets.UTF_8));
    assertEquals(VarkaSuiteListener.Counts.ZERO, listener.counts());
  }

  /** A JUnit class like Spark's own, not under {@code @VarkaSuite}. */
  static final class PlainFixture {
    @Test
    void plain() {}
  }

  /**
   * Runs {@code fixture} through a Launcher of its own in this JVM, with the listeners that
   * {@code META-INF/services} registers left off - so the run's lines and counts do not reach
   * this JVM's own - and {@code listener}, when given. Returns each test's result by name.
   */
  private static Map<String, TestExecutionResult> launch(Class<?> fixture,
      Optional<VarkaSuiteListener> listener) {
    var results = new LinkedHashMap<String, TestExecutionResult>();
    TestExecutionListener collect = new TestExecutionListener() {
      @Override
      public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest()) {
          results.put(id.getDisplayName(), result);
        }
      }
    };
    var config = LauncherConfig.builder()
        .enableTestExecutionListenerAutoRegistration(false)
        .addTestExecutionListeners(collect);
    listener.ifPresent(config::addTestExecutionListeners);
    LauncherFactory.create(config.build()).execute(LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectClass(fixture)).build());
    return results;
  }
}
