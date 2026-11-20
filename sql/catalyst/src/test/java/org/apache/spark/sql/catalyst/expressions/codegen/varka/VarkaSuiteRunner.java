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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.scalatest.Reporter;
import org.scalatest.events.Event;
import org.scalatest.events.RunAborted;
import org.scalatest.events.RunCompleted;
import org.scalatest.events.Summary;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteListener.Counts;

/**
 * Runs ScalaTest suites and {@link VarkaSuite} JUnit classes in one JVM, as the matrix's and the
 * gate's JVMs do (VARKA-312): {@code dev/varka_matrix.sh} starts this main where it started
 * ScalaTest's {@code org.scalatest.tools.Runner}, with the same arguments - {@code -u <dir>},
 * {@code -s <class>} per suite, each optionally followed by {@code -z <text>} and
 * {@code -t <name>} test filters, and ScalaTest's output flags such as {@code -oW}.
 *
 * <p>It sorts the named classes by reflection, without initialising them: an
 * {@code org.scalatest.Suite} goes to ScalaTest's Runner with every flag as given and where it
 * was given, a {@code @VarkaSuite} class to the JUnit Platform Launcher, with its filters
 * matched against each test's display name (a template invocation's key), and with a
 * {@link VarkaSuiteListener},
 * which prints its tests as ScalaTest would and writes its {@code TEST-<class>.xml} into the same
 * {@code -u} directory. ScalaTest's suites run first, and its Runner prints its own summary; then
 * the JUnit classes; then one combined {@code Suites:} line and one {@code Tests:} line over both,
 * last, because {@code dev/varka_matrix_report.py} reads the last of each in a JVM's log. A shard
 * with no JUnit class prints ScalaTest's counts again. ScalaTest's counts come from a reporter
 * this class attaches ({@code -C}) that keeps the run's summary, so nothing parses the output.
 *
 * <p>Exit status 0 when nothing failed or aborted on either side, 1 otherwise, as ScalaTest's
 * Runner exits.
 */
public final class VarkaSuiteRunner {

  private VarkaSuiteRunner() {}

  public static void main(String[] args) throws Exception {
    // Exit explicitly, as ScalaTest's main does: a suite may leave a non-daemon thread behind.
    System.exit(run(args) ? 0 : 1);
  }

  /**
   * A ScalaTest test-name filter, which applies to the {@code -s} it follows: {@code -z} keeps
   * the tests whose name contains the text, {@code -t} the test with exactly that name.
   */
  sealed interface NameFilter permits Containing, Named {
    boolean matches(String testName);
  }

  /** {@code -z <text>}. */
  record Containing(String text) implements NameFilter {
    @Override
    public boolean matches(String testName) {
      return testName.contains(text);
    }
  }

  /** {@code -t <name>}. */
  record Named(String name) implements NameFilter {
    @Override
    public boolean matches(String testName) {
      return testName.equals(name);
    }
  }

  /**
   * The Launcher configuration parameter, after a JUnit class's name, that holds the class's
   * filters ({@link #encode}) for {@code VarkaSuiteExtension}, which applies them to a test
   * template's invocations as they are generated.
   */
  static final String FILTERS_PARAMETER = "varka.runner.filters.";

  /** The reason a template invocation the filters leave out is disabled with. */
  static final String NOT_SELECTED = "not selected by -z or -t";

  // Separates the filters in FILTERS_PARAMETER's value; no test name holds it.
  private static final String SEPARATOR = "\0";

  /** {@code filters} as {@link #FILTERS_PARAMETER}'s value: each its flag's letter and text. */
  static String encode(List<NameFilter> filters) {
    var parts = new ArrayList<String>();
    for (NameFilter f : filters) {
      parts.add(switch (f) {
        case Containing c -> "z" + c.text();
        case Named n -> "t" + n.name();
      });
    }
    return String.join(SEPARATOR, parts);
  }

  /** Whether one of the filters {@link #encode} gave {@code encoded} matches {@code testName}. */
  static boolean selects(String encoded, String testName) {
    for (String part : encoded.split(SEPARATOR, -1)) {
      NameFilter f = part.startsWith("z") ? new Containing(part.substring(1))
          : new Named(part.substring(1));
      if (f.matches(testName)) {
        return true;
      }
    }
    return false;
  }

  /** The named suites run as described above; true when nothing failed or aborted. */
  public static boolean run(String[] args) throws Exception {
    // ScalaTest's arguments are argv as given, less the JUnit classes' -s and the filters that
    // follow them: its -z and -t apply to the -s they follow, so every position is kept.
    var scalaArgs = new ArrayList<String>();
    var scalaSuites = new ArrayList<String>();
    // Each JUnit class with its -z and -t filters, none meaning every test.
    var junitClasses = new LinkedHashMap<Class<?>, List<NameFilter>>();
    Optional<File> reportDir = Optional.empty();
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    // The JUnit class whose -s came last, which a -z or -t right after it belongs to.
    Class<?> junitAt = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      boolean hasValue = i + 1 < args.length;
      if (arg.equals("-s") && hasValue) {
        String name = args[++i];
        Class<?> c = Class.forName(name, false, loader);
        if (org.scalatest.Suite.class.isAssignableFrom(c)) {
          scalaSuites.add(name);
          scalaArgs.add(arg);
          scalaArgs.add(name);
          junitAt = null;
        } else if (AnnotationSupport.isAnnotated(c, VarkaSuite.class)) {
          junitClasses.putIfAbsent(c, new ArrayList<>());
          junitAt = c;
        } else {
          throw new IllegalArgumentException(
              name + " is neither a ScalaTest suite nor a @VarkaSuite JUnit class");
        }
      } else if ((arg.equals("-z") || arg.equals("-t")) && hasValue && junitAt != null) {
        String text = args[++i];
        junitClasses.get(junitAt).add(arg.equals("-z") ? new Containing(text) : new Named(text));
      } else {
        if (arg.equals("-u") && hasValue) {
          reportDir = Optional.of(new File(args[i + 1]));
        }
        scalaArgs.add(arg);
        junitAt = null;
      }
    }

    Counts scala = Counts.ZERO;
    boolean scalaPassed = true;
    if (!scalaSuites.isEmpty()) {
      scalaArgs.addAll(0, List.of("-C", SummaryReporter.class.getName()));
      SummaryReporter.LAST.set(null);
      scalaPassed = org.scalatest.tools.Runner.run(scalaArgs.toArray(new String[0]));
      Counts reported = SummaryReporter.LAST.get();
      // A run ScalaTest aborted leaves no summary; it counts as one aborted suite, as the
      // matrix's report counts a JVM that printed no Suites: line.
      scala = reported != null ? reported : new Counts(0, 0, 0, 0, 0, 0, 1);
    }

    Counts junit = Counts.ZERO;
    if (!junitClasses.isEmpty()) {
      var listener = new VarkaSuiteListener(reportDir, System.out);
      var config = LauncherConfig.builder()
          .enableTestExecutionListenerAutoRegistration(false)
          .addTestExecutionListeners(listener)
          .build();
      List<DiscoverySelector> selectors = junitClasses.keySet().stream()
          .<DiscoverySelector>map(DiscoverySelectors::selectClass).toList();
      var filters = new HashMap<String, List<NameFilter>>();
      junitClasses.forEach((c, f) -> filters.put(c.getName(), f));
      // A test of a class with filters runs when one of them matches its display name, the
      // name ScalaTest's filters match; the Launcher drops the containers left with no test. A
      // test template's invocations are generated as it runs, so the extension filters them by
      // their keys, given the filters as configuration parameters.
      PostDiscoveryFilter byName = descriptor -> {
        if (descriptor.isTest() && descriptor.getSource().orElse(null) instanceof MethodSource m) {
          List<NameFilter> own = filters.getOrDefault(m.getClassName(), List.of());
          String name = descriptor.getDisplayName();
          if (!own.isEmpty() && own.stream().noneMatch(f -> f.matches(name))) {
            return FilterResult.excluded("not selected by -z or -t");
          }
        }
        return FilterResult.included("selected");
      };
      var builder = LauncherDiscoveryRequestBuilder.request().selectors(selectors).filters(byName);
      filters.forEach((c, f) -> {
        if (!f.isEmpty()) {
          builder.configurationParameter(FILTERS_PARAMETER + c, encode(f));
        }
      });
      LauncherDiscoveryRequest request = builder.build();
      LauncherFactory.create(config).execute(request);
      junit = listener.counts();
    }

    Counts total = scala.plus(junit);
    System.out.println("Run completed: " + scalaSuites.size() + " ScalaTest suite(s) and "
        + junitClasses.size() + " JUnit class(es) in this JVM.");
    System.out.println("Total number of tests run: "
        + (total.succeeded() + total.failed() + total.canceled()));
    System.out.println(total.suitesLine());
    System.out.println(total.testsLine());
    boolean passed = scalaPassed && total.failed() == 0 && total.suitesAborted() == 0;
    System.out.println(passed ? "All tests passed." : "*** " + total.failed()
        + " TESTS FAILED, " + total.suitesAborted() + " SUITES ABORTED ***");
    return passed;
  }

  /**
   * The ScalaTest reporter {@link #run} attaches: keeps the counts of the run's summary, which
   * ScalaTest's own summary lines print. ScalaTest builds it by its class name, so it hands the
   * counts back through a static.
   */
  public static final class SummaryReporter implements Reporter {

    static final AtomicReference<Counts> LAST = new AtomicReference<>();

    @Override
    public void apply(Event event) {
      if (event instanceof RunCompleted completed && completed.summary().isDefined()) {
        Summary s = completed.summary().get();
        LAST.set(new Counts(s.testsSucceededCount(), s.testsFailedCount(),
            s.testsCanceledCount(), s.testsIgnoredCount(), s.testsPendingCount(),
            s.suitesCompletedCount(), s.suitesAbortedCount()));
      } else if (event instanceof RunAborted aborted && aborted.summary().isDefined()) {
        Summary s = aborted.summary().get();
        LAST.set(new Counts(s.testsSucceededCount(), s.testsFailedCount(),
            s.testsCanceledCount(), s.testsIgnoredCount(), s.testsPendingCount(),
            s.suitesCompletedCount(), s.suitesAbortedCount() + 1));
      }
    }
  }
}
