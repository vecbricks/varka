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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.pitest.classinfo.ClassByteArraySource;
import org.pitest.help.PitHelpError;
import org.pitest.testapi.AbstractTestUnit;
import org.pitest.testapi.Configuration;
import org.pitest.testapi.Description;
import org.pitest.testapi.ResultCollector;
import org.pitest.testapi.TestGroupConfig;
import org.pitest.testapi.TestPluginFactory;
import org.pitest.testapi.TestSuiteFinder;
import org.pitest.testapi.TestUnit;
import org.pitest.testapi.TestUnitExecutionListener;
import org.pitest.testapi.TestUnitFinder;
import org.scalatest.Args;
import org.scalatest.ConfigMap$;
import org.scalatest.Filter;
import org.scalatest.Filter$;
import org.scalatest.Reporter;
import org.scalatest.Status;
import org.scalatest.Stopper;
import org.scalatest.Stopper$;
import org.scalatest.Suite;
import org.scalatest.Tracker;
import org.scalatest.events.Event;
import org.scalatest.events.Ordinal;
import org.scalatest.events.SuiteAborted;
import org.scalatest.events.TestCanceled;
import org.scalatest.events.TestFailed;
import org.scalatest.events.TestIgnored;

/**
 * PIT's test plugin for ScalaTest suites, test by test (VARKA-265): written here against PIT's
 * own SPI ({@code org.pitest:pitest}), so that mutation testing reaches the Varka suites with no
 * third-party plugin. {@code dev/varka_pit.sh} compiles it and registers it through
 * {@code META-INF/services/org.pitest.testapi.TestPluginFactory}.
 *
 * <p>Each test of a concrete {@link Suite} with a public no-argument constructor is one PIT test
 * unit, so PIT records which tests cover which mutant and runs only those. A unit runs its test
 * alone - {@code run(Some(name), args)} on a fresh instance of its suite, its
 * {@code beforeAll} and {@code afterAll} around it - after emptying the Varka shape cache: a
 * kernel cached by an earlier test was emitted by other code than the mutant in the JVM now, and
 * would hide the mutant, or blame it for another's bytes. A failed test, an aborted suite or a
 * suite status that did not succeed fails the unit; a cancelled or ignored test is skipped.
 */
public final class VarkaPitScalaTest implements TestPluginFactory {

  /**
   * Which tests the run takes: {@code exclude} leaves out the structural ones, {@code only} takes
   * them alone, anything else takes every test. A structural test checks what the emitter emits
   * rather than what a kernel answers: the bytes oracle's, and every test tagged
   * {@code PinsDefaults}, the tag the house gives a test whose subject is the defaults' emitted
   * structure (a grouping, an op count, a build count).
   */
  static final String STRUCTURE = "varka.pit.structure";
  static final String BYTES_ORACLE = "VarkaEmittedBytesSuite";
  static final String PINS_DEFAULTS = "org.apache.spark.sql.varka.PinsDefaults";

  /** The cache a unit empties before its test: emitted kernels outlive the mutant in the JVM. */
  static final String SHAPE_CACHE =
      "org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeCache";

  @Override
  public String name() {
    return "varka-scalatest";
  }

  @Override
  public String description() {
    return "ScalaTest suites, one PIT test unit per test (VARKA-265)";
  }

  @Override
  public Configuration createTestFrameworkConfiguration(TestGroupConfig groups,
      ClassByteArraySource source, Collection<String> excludedRunners,
      Collection<String> includedTestMethods) {
    return new Configuration() {
      @Override
      public int priority() {
        // Ahead of PIT's JUnit 4 plugin (DEFAULT_PRIORITY, 10): the first finder with units wins.
        return 5;
      }

      @Override
      public TestUnitFinder testUnitFinder() {
        return VarkaPitScalaTest::units;
      }

      @Override
      public TestSuiteFinder testSuiteFinder() {
        return c -> List.of();
      }

      @Override
      public Optional<PitHelpError> verifyEnvironment() {
        return Optional.empty();
      }
    };
  }

  /**
   * One unit per test of a runnable suite, as {@link #STRUCTURE} selects; nothing for any other
   * class.
   */
  static List<TestUnit> units(Class<?> c, TestUnitExecutionListener listener) {
    Constructor<?> constructor = constructor(c);
    if (constructor == null) {
      return List.of();
    }
    Suite suite = instantiate(constructor);
    String mode = System.getProperty(STRUCTURE, "");
    boolean oracle = c.getName().endsWith("." + BYTES_ORACLE);
    var units = new ArrayList<TestUnit>();
    scala.collection.Iterator<String> names = suite.testNames().iterator();
    while (names.hasNext()) {
      String name = names.next();
      boolean structural = oracle || suite.tags().get(name)
          .exists(tags -> tags.contains(PINS_DEFAULTS));
      boolean keep = switch (mode) {
        case "exclude" -> !structural;
        case "only" -> structural;
        default -> true;
      };
      if (keep) {
        units.add(new Unit(constructor, name));
      }
    }
    return units;
  }

  /** The public no-argument constructor of a concrete suite, or null. */
  static Constructor<?> constructor(Class<?> c) {
    if (!Suite.class.isAssignableFrom(c) || c.isInterface()
        || Modifier.isAbstract(c.getModifiers()) || !Modifier.isPublic(c.getModifiers())) {
      return null;
    }
    try {
      return c.getConstructor();
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  static Suite instantiate(Constructor<?> constructor) {
    try {
      return (Suite) constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot instantiate " + constructor.getDeclaringClass(), e);
    }
  }

  /** One test of one suite. */
  static final class Unit extends AbstractTestUnit {
    private final Constructor<?> constructor;
    private final String test;

    Unit(Constructor<?> constructor, String test) {
      super(new Description(test, constructor.getDeclaringClass()));
      this.constructor = constructor;
      this.test = test;
    }

    @Override
    public void execute(ResultCollector rc) {
      Description d = getDescription();
      rc.notifyStart(d);
      Outcome outcome = new Outcome();
      try {
        emptyShapeCache();
        Suite suite = instantiate(constructor);
        Args args = new Args(outcome, (Stopper) byDefault(Stopper$.MODULE$),
            (Filter) byDefault(Filter$.MODULE$), ConfigMap$.MODULE$.empty(),
            scala.Option.empty(), new Tracker(new Ordinal(0)),
            scala.collection.immutable.Set$.MODULE$.empty(), false,
            scala.Option.empty(), scala.Option.empty());
        Status status = suite.run(scala.Option.apply(test), args);
        status.waitUntilCompleted();
        // An afterAll that throws reaches the status alone, as its unreported exception, which
        // succeeds() rethrows; a status that failed with no event fails the unit too.
        if (!status.succeeds() && outcome.failure == null && !outcome.skipped) {
          outcome.failure = new AssertionError("the suite's status did not succeed");
        }
      } catch (Throwable t) {
        outcome.failure = t;
      }
      if (outcome.failure != null) {
        // PIT records a kill without the test's message, and the mutant's stack trace stays in
        // the minion; this line reaches the run's log (the MINION lines under --verbose), so a
        // test that fails for reasons of its own can be read there rather than guessed at.
        System.out.println("varka_pit: " + test + " failed: " + outcome.failure);
        rc.notifyEnd(d, outcome.failure);
      } else if (outcome.skipped) {
        rc.notifySkipped(d);
      } else {
        rc.notifyEnd(d);
      }
    }
  }

  /** What the reporter heard about the one test a unit ran. */
  static final class Outcome implements Reporter {
    Throwable failure;
    boolean skipped;

    @Override
    public void apply(Event event) {
      switch (event) {
        case TestFailed f when failure == null ->
            failure = f.throwable().isDefined() ? f.throwable().get()
                : new AssertionError(f.message());
        case SuiteAborted a when failure == null ->
            failure = a.throwable().isDefined() ? a.throwable().get()
                : new AssertionError("suite aborted: " + a.message());
        case TestCanceled c -> skipped = true;
        case TestIgnored i -> skipped = true;
        default -> { }
      }
    }
  }

  /** A ScalaTest companion's {@code default()}, a name Java cannot call as written. */
  static Object byDefault(Object companion) throws ReflectiveOperationException {
    return companion.getClass().getMethod("default").invoke(companion);
  }

  static void emptyShapeCache() throws ReflectiveOperationException {
    Method invalidate = Class.forName(SHAPE_CACHE).getMethod("invalidateAll");
    invalidate.invoke(null);
  }
}
