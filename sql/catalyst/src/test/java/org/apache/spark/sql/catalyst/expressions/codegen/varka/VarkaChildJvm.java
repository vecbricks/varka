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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Failed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Passed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Result;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Skipped;

/**
 * Runs one suite in a JVM of its own and returns each test's result (VARKA-246, {@code m9/SCOPE.md}
 * item 69): the machinery behind a suite marked to run alone, free of any test framework, whose
 * face ({@code VarkaOwnJvm} for ScalaTest) names the main class that runs the suite and the
 * arguments it takes (VARKA-312).
 *
 * <p>The child gets the parent's command-line arguments, classpath and {@code -D} properties (the
 * matrix configuration, the sanitizer, a fuzzer's budget and seed), and
 * {@code -Dvarka.ownJvm=true}, which runs the suite's tests directly. It writes a JUnit report
 * to a temporary directory that the parent reads. The child's output is copied to the parent's,
 * so the matrix's markers are where they were.
 *
 * <p>The child is ended from outside when it outlasts its budget, which is the shortest of the
 * suites' per-test caps ({@code varka.test.watchdog.minutes}, {@code spark.test.timeout}) less a
 * minute, so the parent reports the child's hang before a cap halts the JVM; a JUnit suite passes
 * its own cap ({@code @VarkaSuite(watchdogMinutes)}, none for 0) instead, and
 * {@code -Dvarka.ownJvm.timeoutMinutes} overrides either.
 */
public final class VarkaChildJvm {

  private VarkaChildJvm() {}

  /** Set for the child, and by hand to run such a suite's tests in the JVM at hand. */
  public static final String PROPERTY = VarkaSpeciesGuard.OWN_JVM_PROPERTY;

  /** Whether this JVM is a child, or was told to run such suites directly. */
  public static boolean inChild() {
    return "true".equals(System.getProperty(PROPERTY));
  }

  /** The minutes a child may run: the shortest per-test cap less one, or the override. */
  static long budgetMinutes() {
    String override = System.getProperty("varka.ownJvm.timeoutMinutes");
    if (override != null) {
      return Long.parseLong(override);
    }
    long watchdog = VarkaWatchdog.defaultMinutes();
    long sparkCap = Long.parseLong(System.getProperty("spark.test.timeout", "20"));
    return Math.max(1L, Math.min(watchdog, sparkCap) - 1L);
  }

  /**
   * The minutes a child may run when its suite's per-test cap is {@code capMinutes}, as a JUnit
   * suite under {@code @VarkaSuite} sets it: that cap less one, empty for no limit when the cap
   * is 0 (no watchdog), or the override. No {@code spark.test.timeout} term, since that is
   * {@code SparkFunSuite}'s and caps no JUnit test.
   */
  static OptionalLong budgetMinutes(long capMinutes) {
    String override = System.getProperty("varka.ownJvm.timeoutMinutes");
    if (override != null) {
      return OptionalLong.of(Long.parseLong(override));
    }
    return capMinutes == 0 ? OptionalLong.empty() : OptionalLong.of(Math.max(1L, capMinutes - 1L));
  }

  /**
   * The parent's classpath. {@code java.class.path} is what the parent runs with; the
   * {@code SPARK_DIST_CLASSPATH} environment variable is what the other forking suites prefer,
   * for a launcher whose own classpath is a stub, so it is taken only when the parent's does not
   * hold ScalaTest.
   */
  private static String classpath() {
    String own = System.getProperty("java.class.path");
    if (own.contains("scalatest")) {
      return own;
    }
    String dist = System.getenv("SPARK_DIST_CLASSPATH");
    return dist == null || dist.isEmpty() ? own : dist;
  }

  /**
   * Each test's result from a child: its own where the report holds it, else {@code otherwise},
   * the failure every test asked about takes when the child timed out or left no report.
   */
  public record Results(Map<String, Result> byName, Optional<Failed> otherwise) {
    public Results {
      byName = Map.copyOf(byName);
    }

    /** The result of the test named {@code name}, empty when the child reported none. */
    public Optional<Result> of(String name) {
      Result own = byName.get(name);
      return own != null ? Optional.of(own) : otherwise.map(f -> f);
    }
  }

  /**
   * Runs {@code suite} in a child JVM through {@code mainClass}, given the report directory's
   * path as {@code reportFlag}'s value and then {@code arguments}, and returns each test's result
   * by name, the child limited to {@link #budgetMinutes()}.
   */
  public static Results run(String suite, String mainClass, List<String> arguments,
      String reportFlag) throws Exception {
    return run(suite, mainClass, arguments, reportFlag, OptionalLong.of(budgetMinutes()));
  }

  /**
   * {@link #run(String, String, List, String)} with the child limited to {@code budget} minutes,
   * or not at all when it is empty. Every test the report holds as passed fails instead when the
   * child timed out, when the report says the suite as a whole aborted (a class-level hook threw
   * after the tests ran), or when the child exited non-zero though its report holds no failure;
   * and a child that left no report fails every test asked about, with its last lines.
   */
  public static Results run(String suite, String mainClass, List<String> arguments,
      String reportFlag, OptionalLong budget) throws Exception {
    return run(suite, mainClass, arguments, reportFlag, budget, List.of());
  }

  /**
   * {@link #run(String, String, List, String, OptionalLong)} with {@code jvmFlags} after the
   * parent's arguments, so that HotSpot, which takes the last of a repeated flag, takes these.
   */
  public static Results run(String suite, String mainClass, List<String> arguments,
      String reportFlag, OptionalLong budget, List<String> jvmFlags) throws Exception {
    // The child's code runs where the class-use record does not see it (VarkaTestUse).
    VarkaTestUse.childJvm();
    File report = Files.createTempDirectory("varka-own-jvm").toFile();
    File javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java");
    var command = new ArrayList<String>();
    command.add(javaBin.getAbsolutePath());
    // The parent's own command-line arguments: heap, the incubator module, native access, every
    // -D the run was given. A -javaagent or -agentlib would start twice, and the child needs
    // neither - except JaCoCo's, which dev/varka_gen_coverage.sh puts on every test JVM and which
    // must reach the suites run here too, or their kernels go unmeasured (VARKA-285); it appends
    // to its file under a lock, so a second JVM writing it is what it is built for. Properties
    // the parent set programmatically are not copied.
    for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      boolean agent = a.startsWith("-javaagent") && !a.contains("org.jacoco.agent");
      if (!agent && !a.startsWith("-agentlib")) {
        command.add(a);
      }
    }
    command.addAll(jvmFlags);
    command.add("-D" + PROPERTY + "=true");
    command.add("-cp");
    command.add(classpath());
    command.add(mainClass);
    command.add(reportFlag);
    command.add(report.toString());
    command.addAll(arguments);
    var builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.environment().put("SPARK_TESTING", "1");
    Process process = builder.start();
    // The read below ends only when the child exits, so a hung child is ended from here.
    var timedOut = new AtomicBoolean(false);
    var killer = new Thread(() -> {
      try {
        if (budget.isPresent() && !process.waitFor(budget.getAsLong(), TimeUnit.MINUTES)) {
          timedOut.set(true);
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    killer.setDaemon(true);
    killer.start();
    var tail = new ArrayDeque<String>();
    try (var reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        System.out.println(line);
        tail.addLast(line);
        if (tail.size() > 30) {
          tail.removeFirst();
        }
      }
      process.waitFor();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    Map<String, Result> parsed = VarkaSuiteReport.read(report);
    Optional<String> aborted = VarkaSuiteReport.abortOf(report);
    int exit = process.exitValue();
    if (timedOut.get()) {
      // The budget is one test's cap for the whole suite, and the parent's waiting test has that
      // cap too, so a larger budget alone cannot help a long run: a JVM that runs only this suite
      // runs it directly instead, as the nightly fuzz workflow does (VARKA-392).
      return failAll(parsed, suite + " did not finish in " + budget.getAsLong()
          + " minutes in its own JVM, one test's cap for the whole suite; a JVM that runs only"
          + " this suite can run it directly with -D" + PROPERTY + "=true", false);
    } else if (aborted.isPresent()) {
      return failAll(parsed, "in its own JVM, " + suite + " aborted: " + aborted.get(), true);
    } else if (parsed.isEmpty()) {
      return failAll(parsed, suite + " left no report in its own JVM (exit " + exit + "):\n"
          + String.join("\n", tail), false);
    } else if (exit != 0 && parsed.values().stream().noneMatch(r -> r instanceof Failed)) {
      // A runner exits non-zero only for a failed test or an aborted suite; with neither in the
      // report, something the report could not hold went wrong, and no test may pass over it.
      return failAll(parsed, suite + " exited with exit " + exit + " in its own JVM, though its"
          + " report holds no failure:\n" + String.join("\n", tail), true);
    }
    return new Results(parsed, Optional.empty());
  }

  /**
   * {@code parsed} with every pass turned into a failure with {@code message}, and every cancel
   * too when {@code cancels} is set, so that a whole-suite failure reaches the parent even when
   * no test passed; the failure also answers for any test {@code parsed} does not hold.
   */
  private static Results failAll(Map<String, Result> parsed, String message, boolean cancels) {
    var failure = new Failed(message);
    var failed = new LinkedHashMap<String, Result>();
    parsed.forEach((name, result) -> failed.put(name, switch (result) {
      case Passed p -> failure;
      case Skipped s -> cancels ? failure : s;
      case Failed f -> f;
    }));
    return new Results(failed, Optional.of(failure));
  }
}
