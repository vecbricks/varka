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

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Case;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Failed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Passed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Result;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Skipped;

/**
 * Prints the tests of {@link VarkaSuite} classes as ScalaTest prints its tests (VARKA-312), so a
 * JUnit suite's run reads like a ScalaTest one's in sbt's output, the gate's logs and the
 * matrix's report, each test named by its display name, or a test template's invocation by its
 * key ({@link VarkaSuiteExtension#invocationKey}):
 * <pre>
 * VarkaMethodNamesSuite:
 * - the builders give the names the emitted classes have
 * - a test that fails *** FAILED ***
 *   its message, on indented lines
 * - a test that was cancelled !!! CANCELED !!!
 *   its reason
 * - a disabled test !!! IGNORED !!!
 * </pre>
 * A class whose class-level hooks fail - two tests with one name, Arrow memory left allocated -
 * is printed {@code *** ABORTED ***}, as ScalaTest prints an aborted suite.
 *
 * <p>JUnit Platform loads it from {@code META-INF/services} in catalyst's test resources, for
 * every JUnit run on a classpath that holds them - sbt's {@code jupiter-interface}, Maven's
 * surefire - so it is silent for any class not under {@code @VarkaSuite}, and Spark's own JUnit
 * suites print what they always printed. {@code jupiter-interface}'s own lines, which name the
 * method, stay beside these under sbt.
 *
 * <p>{@link VarkaSuiteRunner} attaches an instance of its own with a report directory: that one
 * also writes each class's {@code TEST-<class>.xml} there and keeps the counts the runner's
 * summary lines add to ScalaTest's.
 */
public final class VarkaSuiteListener implements TestExecutionListener {

  /** Tests and suites by outcome, as ScalaTest's summary lines count them. */
  public record Counts(int succeeded, int failed, int canceled, int ignored, int pending,
      int suitesCompleted, int suitesAborted) {

    public static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0);

    public Counts plus(Counts o) {
      return new Counts(succeeded + o.succeeded, failed + o.failed, canceled + o.canceled,
          ignored + o.ignored, pending + o.pending, suitesCompleted + o.suitesCompleted,
          suitesAborted + o.suitesAborted);
    }

    /** ScalaTest's {@code Tests:} line, which {@code dev/varka_matrix_report.py} reads. */
    public String testsLine() {
      return "Tests: succeeded " + succeeded + ", failed " + failed + ", canceled " + canceled
          + ", ignored " + ignored + ", pending " + pending;
    }

    /** ScalaTest's {@code Suites:} line, which {@code dev/varka_matrix_report.py} reads. */
    public String suitesLine() {
      return "Suites: completed " + suitesCompleted + ", aborted " + suitesAborted;
    }
  }

  /** One class while it runs: its name, when it started, and its tests so far. */
  private record ClassRun(String className, long startNanos, List<Case> cases) {}

  private final Optional<File> reportDir;
  private final PrintStream out;
  private final Map<String, Boolean> isVarkaSuite = new HashMap<>();
  // The running Varka classes by their unique id.
  private final Map<String, ClassRun> running = new HashMap<>();
  private final Map<String, Long> testStarts = new HashMap<>();
  private TestPlan plan;
  private Counts counts = Counts.ZERO;

  /** The instance JUnit Platform loads: prints to standard output, writes no report. */
  public VarkaSuiteListener() {
    this(Optional.empty(), System.out);
  }

  /** An instance that also writes {@code TEST-<class>.xml} into {@code reportDir}, if given. */
  public VarkaSuiteListener(Optional<File> reportDir, PrintStream out) {
    this.reportDir = reportDir;
    this.out = out;
  }

  /** The tests and classes this listener has seen end so far. */
  public synchronized Counts counts() {
    return counts;
  }

  @Override
  public synchronized void testPlanExecutionStarted(TestPlan testPlan) {
    plan = testPlan;
  }

  @Override
  public synchronized void executionStarted(TestIdentifier id) {
    Optional<String> varkaClass = varkaClassOf(id);
    if (varkaClass.isPresent()) {
      running.put(id.getUniqueId(), new ClassRun(varkaClass.get(), System.nanoTime(),
          new ArrayList<>()));
      out.println(simpleName(varkaClass.get()) + ":");
    } else if (id.isTest() && classRunOf(id) != null) {
      testStarts.put(id.getUniqueId(), System.nanoTime());
    }
  }

  @Override
  public synchronized void executionSkipped(TestIdentifier id, String reason) {
    ClassRun run = classRunOf(id);
    // An invocation the runner's filters leave out is not shown, as a filtered test is not. A
    // disabled template or factory, whose tests are never generated, is shown as one test.
    if (run != null && !VarkaSuiteRunner.NOT_SELECTED.equals(reason)) {
      String name = nameOf(id);
      out.println("- " + name + " !!! IGNORED !!!");
      run.cases().add(new Case(name, new Skipped(reason), 0.0));
      counts = counts.plus(new Counts(0, 0, 0, 1, 0, 0, 0));
    }
  }

  @Override
  public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
    ClassRun own = running.remove(id.getUniqueId());
    if (own != null) {
      finishClass(own, result);
      return;
    }
    ClassRun run = classRunOf(id);
    // A template or a factory that fails as a whole - its argument source threw or gave none -
    // generated no test to report, so it is reported as a test of its own, under its name.
    if (run == null
        || (!id.isTest() && result.getStatus() == TestExecutionResult.Status.SUCCESSFUL)) {
      return;
    }
    Long start = testStarts.remove(id.getUniqueId());
    double seconds = start == null ? 0.0 : (System.nanoTime() - start) / 1e9;
    String name = nameOf(id);
    String message = result.getThrowable().map(VarkaSuiteListener::messageOf).orElse("");
    Result outcome = switch (result.getStatus()) {
      case SUCCESSFUL -> {
        out.println("- " + name);
        counts = counts.plus(new Counts(1, 0, 0, 0, 0, 0, 0));
        yield new Passed();
      }
      case ABORTED -> {
        out.println("- " + name + " !!! CANCELED !!!");
        printIndented(message);
        counts = counts.plus(new Counts(0, 0, 1, 0, 0, 0, 0));
        yield new Skipped(message);
      }
      case FAILED -> {
        out.println("- " + name + " *** FAILED ***");
        printIndented(message);
        counts = counts.plus(new Counts(0, 1, 0, 0, 0, 0, 0));
        yield new Failed(message);
      }
    };
    run.cases().add(new Case(name, outcome, seconds));
  }

  /**
   * A class has ended: a completed suite when its container succeeded, an aborted one when a
   * class-level hook failed (FAILED) or a class-level assumption failed (ABORTED, which JUnit
   * gives a container whose tests it then never starts). ScalaTest reports both kinds as an
   * aborted suite, and an aborted class must not pass as a completed one with no tests.
   */
  private void finishClass(ClassRun run, TestExecutionResult result) {
    Optional<String> aborted = switch (result.getStatus()) {
      case SUCCESSFUL -> Optional.empty();
      case FAILED, ABORTED ->
          Optional.of(result.getThrowable().map(VarkaSuiteListener::messageOf).orElse(""));
    };
    if (aborted.isPresent()) {
      out.println("Exception encountered when attempting to run a suite with class name: "
          + run.className() + " *** ABORTED ***");
      printIndented(aborted.get());
      counts = counts.plus(new Counts(0, 0, 0, 0, 0, 0, 1));
    } else {
      counts = counts.plus(new Counts(0, 0, 0, 0, 0, 1, 0));
    }
    if (reportDir.isPresent()) {
      try {
        VarkaSuiteReport.write(reportDir.get(), run.className(),
            (System.nanoTime() - run.startNanos()) / 1e9, run.cases(), aborted);
      } catch (Exception e) {
        throw new IllegalStateException("could not write the report of " + run.className(), e);
      }
    }
  }

  /** The class {@code id} is the container of, when that class is under {@code @VarkaSuite}. */
  private Optional<String> varkaClassOf(TestIdentifier id) {
    if (id.getSource().orElse(null) instanceof ClassSource source
        && isVarkaSuite.computeIfAbsent(source.getClassName(), VarkaSuiteListener::annotated)) {
      return Optional.of(source.getClassName());
    }
    return Optional.empty();
  }

  /**
   * The name of the test {@code id}: its display name, or for a test template's invocation its
   * key, the template's display name and its own, as the extension keys it.
   */
  private String nameOf(TestIdentifier id) {
    if (VarkaSuiteExtension.isInvocation(id.getUniqueId())) {
      Optional<TestIdentifier> template = plan.getParent(id);
      if (template.isPresent()) {
        return VarkaSuiteExtension.invocationKey(template.get().getDisplayName(),
            id.getDisplayName());
      }
    }
    return id.getDisplayName();
  }

  /** The running Varka class {@code id} belongs to, at any depth, or null. */
  private ClassRun classRunOf(TestIdentifier id) {
    if (plan == null) {
      return null;
    }
    Optional<TestIdentifier> at = plan.getParent(id);
    while (at.isPresent()) {
      ClassRun run = running.get(at.get().getUniqueId());
      if (run != null) {
        return run;
      }
      at = plan.getParent(at.get());
    }
    return null;
  }

  private static boolean annotated(String className) {
    try {
      Class<?> c = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      return AnnotationSupport.isAnnotated(c, VarkaSuite.class);
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  private static String simpleName(String className) {
    String name = className.substring(className.lastIndexOf('.') + 1);
    return name.substring(name.lastIndexOf('$') + 1);
  }

  private static String messageOf(Throwable t) {
    return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
  }

  /** {@code text} on lines indented by two, as ScalaTest prints a failure's message. */
  private void printIndented(String text) {
    for (String line : text.split("\n", -1)) {
      out.println("  " + line);
    }
  }
}
