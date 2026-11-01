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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns.Body;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns.Outcomes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns.Skip;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Failed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Passed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Result;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.Skipped;
import org.apache.spark.sql.util.ArrowUtils;

/**
 * The JUnit face of the Varka test harness (VARKA-312), registered by {@link VarkaSuite}: what
 * the ScalaTest traits {@code VarkaMatrixTests}, {@code VarkaTestWatchdog} and
 * {@code VarkaOwnJvm} do for a ScalaTest suite, over the same Java core.
 *
 * <p>Each test method runs as the traits compose, the matrix outermost and the watchdog inside:
 * <ol>
 *   <li>under a matrix configuration, a test tagged {@link VarkaSuite#PINS_DEFAULTS} is cancelled
 *       without running, with the ScalaTest form's message;</li>
 *   <li>under a matrix configuration that sets none of {@link VarkaMatrixRuns#C2_OPTIONS}, a test
 *       tagged {@link VarkaSuite#C2_CODE} is cancelled the same way;</li>
 *   <li>otherwise {@link VarkaMatrixRuns#around} runs it against the skip list - a test the list
 *       expects to fail is cancelled when it fails and fails as a stale entry when it passes -
 *       and records its fused batches;</li>
 *   <li>inside that, {@link VarkaWatchdog#guard} caps it;</li>
 *   <li>inside that, the test method itself.</li>
 * </ol>
 * An {@code ownJvm} class in the shared JVM skips all four wrappers: the child JVM ran the class
 * under them, and each test only takes the child's result, as it is.
 *
 * <p>A cancel is opentest4j's {@link TestAbortedException}, which JUnit reports as aborted and the
 * listener prints {@code !!! CANCELED !!!}. It is thrown from the interceptor rather than decided
 * by an {@code ExecutionCondition}, because a condition would report the test disabled, which is
 * ScalaTest's "ignored", not its "canceled".
 *
 * <p>Around the class: before it, the extension fails a class whose shape it cannot hold
 * ({@link #checkShape}: two tests with one name, a test that is not a plain {@code @Test}, a
 * {@code @Nested} class, the annotation only inherited); and under
 * the memory sanitizer it holds the class to leave Arrow's root allocator where it found it, as
 * {@code VarkaMatrixTests.run} does.
 */
public final class VarkaSuiteExtension
    implements BeforeAllCallback, AfterAllCallback, InvocationInterceptor {

  /** How a JUnit test is cancelled and failed, and the exception a cancel is. */
  static final Outcomes OUTCOMES = new Outcomes(
      TestAbortedException::new, AssertionFailedError::new, TestAbortedException.class);

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(VarkaSuiteExtension.class);

  private static final String ARROW_BEFORE = "arrowAllocatedBefore";

  // One child JVM per ownJvm class per parent JVM; the first test to ask starts it.
  private static final Map<String, VarkaChildJvm.Results> CHILDREN = new ConcurrentHashMap<>();

  // ---------------------------------------------------------------------------------------------
  // Around the class
  // ---------------------------------------------------------------------------------------------

  @Override
  public void beforeAll(ExtensionContext context) {
    checkShape(context.getRequiredTestClass());
    // The option matrix's reach recording, where the run asks for one, and the suite's window,
    // which takes what it compiles outside a test as every one of its tests' (VarkaTestReach).
    VarkaTestReach.install();
    VarkaTestReach.suiteBegin(context.getRequiredTestClass().getSimpleName());
    if (VarkaMemorySanitizer.ENABLED) {
      context.getStore(NAMESPACE).put(ARROW_BEFORE,
          ArrowUtils.rootAllocator().getAllocatedMemory());
    }
  }

  @Override
  public void afterAll(ExtensionContext context) {
    DYNAMIC_NAMES.remove(context.getRequiredTestClass());
    VarkaTestReach.suiteEnd(context.getRequiredTestClass().getSimpleName());
    Long before = context.getStore(NAMESPACE).get(ARROW_BEFORE, Long.class);
    if (before == null) {
      return;
    }
    long leaked = ArrowUtils.rootAllocator().getAllocatedMemory() - before;
    if (leaked != 0L) {
      if ("true".equals(System.getProperty("arrow.memory.debug.allocator"))) {
        // The allocator's own account of what is outstanding, with each buffer's stack.
        System.err.println(ArrowUtils.rootAllocator().toVerboseString());
      }
      throw new IllegalStateException(context.getRequiredTestClass().getSimpleName() + " left "
          + leaked + " bytes of Arrow memory allocated (the memory sanitizer's every-byte-back"
          + " check): a buffer was not closed");
    }
  }

  /**
   * Fails a class whose shape the harness cannot hold, before any of its tests runs, so the class
   * is reported aborted rather than its tests run outside the harness:
   * <ul>
   *   <li>two tests with one display name, since the skip list and the fused-batch report key a
   *       test by its suite and name;</li>
   *   <li>a {@code @ParameterizedTest} or {@code @RepeatedTest} test: JUnit runs those through
   *       another interceptor method than {@link #interceptTestMethod}, so they would bypass the
   *       skip list, {@code PinsDefaults}, {@code C2Code} and the watchdog, and their
   *       invocations' names ({@code [1] ...}) make poor skip-list keys. A {@code @TestFactory}'s
   *       dynamic tests are held ({@link #interceptDynamicTest}), each under the name the factory
   *       gives it, except in an {@code ownJvm} class, whose child answers by test method;</li>
   *   <li>a {@code @Nested} class, which inherits this extension but not the annotation, and
   *       whose tests the listener and the reports would put under the outer class's name;</li>
   *   <li>{@code @VarkaSuite} inherited from a superclass only: {@code dev/varka_matrix.sh} finds
   *       a Java suite by the annotation in its own source file, so the matrix, the gate and CI's
   *       scoped jobs would never run such a class.</li>
   * </ul>
   */
  static void checkShape(Class<?> testClass) {
    String name = testClass.getSimpleName();
    List<String> repeated = repeatedNames(displayNames(testClass));
    if (!repeated.isEmpty()) {
      throw new IllegalStateException(name + " names more than one test " + repeated
          + "; a Varka suite's test names are its skip-list keys and must be unique");
    }
    var templates = new ArrayList<String>();
    for (Method m : AnnotationSupport.findAnnotatedMethods(testClass, TestTemplate.class,
        HierarchyTraversalMode.TOP_DOWN)) {
      templates.add(m.getName());
    }
    if (!templates.isEmpty()) {
      throw new IllegalStateException(name + " has parameterized or repeated tests " + templates
          + "; a @VarkaSuite test is a plain @Test or a @TestFactory's, which the harness wraps");
    }
    VarkaSuite annotation = testClass.getDeclaredAnnotation(VarkaSuite.class);
    if (annotation != null && annotation.ownJvm() && !AnnotationSupport.findAnnotatedMethods(
        testClass, TestFactory.class, HierarchyTraversalMode.TOP_DOWN).isEmpty()) {
      throw new IllegalStateException(name + " is ownJvm and has dynamic tests; the child JVM "
          + "answers a test by its method, so an ownJvm class keeps to plain @Test methods");
    }
    List<Class<?>> nested = ReflectionSupport.findNestedClasses(testClass,
        c -> c.isAnnotationPresent(Nested.class));
    if (!nested.isEmpty()) {
      throw new IllegalStateException(name + " holds @Nested classes "
          + nested.stream().map(Class::getSimpleName).toList()
          + "; a @VarkaSuite class keeps its tests in the class itself");
    }
    if (testClass.getDeclaredAnnotation(VarkaSuite.class) == null) {
      throw new IllegalStateException(name + " takes @VarkaSuite from a superclass; declare it"
          + " on the class itself, where dev/varka_matrix.sh finds a Java suite");
    }
  }

  /**
   * The display name of every test method of {@code testClass}, inherited ones included: its
   * {@code @DisplayName}, or JUnit's default, the method's name and parameter types.
   */
  static List<String> displayNames(Class<?> testClass) {
    var names = new ArrayList<String>();
    for (Method m : AnnotationSupport.findAnnotatedMethods(testClass, Test.class,
        HierarchyTraversalMode.TOP_DOWN)) {
      names.add(AnnotationSupport.findAnnotation(m, DisplayName.class)
          .map(DisplayName::value)
          .orElseGet(() -> m.getName() + Arrays.stream(m.getParameterTypes())
              .map(Class::getSimpleName).collect(Collectors.joining(", ", "(", ")"))));
    }
    return names;
  }

  /** The names that occur more than once in {@code names}, each once, in first-repeat order. */
  static List<String> repeatedNames(List<String> names) {
    var seen = new HashSet<String>();
    var repeated = new ArrayList<String>();
    for (String name : names) {
      if (!seen.add(name) && !repeated.contains(name)) {
        repeated.add(name);
      }
    }
    return repeated;
  }

  // ---------------------------------------------------------------------------------------------
  // Around each test
  // ---------------------------------------------------------------------------------------------

  @Override
  public void interceptTestMethod(Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext, ExtensionContext context)
      throws Throwable {
    Class<?> testClass = context.getRequiredTestClass();
    VarkaSuite suite = AnnotationSupport.findAnnotation(testClass, VarkaSuite.class)
        .orElseThrow(() -> new IllegalStateException(
            testClass.getName() + " registers VarkaSuiteExtension without @VarkaSuite"));
    long minutes = capMinutes(suite);
    Guard guard = minutes == 0 ? Guard.NONE
        : (s, t, body) -> VarkaWatchdog.guard(s, t, minutes, body);
    String test = context.getDisplayName();
    boolean answeredByChild = suite.ownJvm() && !VarkaChildJvm.inChild();
    // Jupiter requires an intercepted invocation to be proceeded or skipped exactly once; a test
    // cancelled before its body, or answered from the child JVM, skips it.
    var invoked = new boolean[1];
    Body<Object> body;
    if (answeredByChild) {
      body = () -> {
        invoked[0] = true;
        invocation.skip();
        return answerFromChild(
            childResults(testClass.getName(), minutes, List.of(suite.jvmFlags())), test);
      };
    } else {
      body = () -> {
        invoked[0] = true;
        invocation.proceed();
        return null;
      };
    }
    try {
      runTest(VarkaMatrixRuns.CONFIG, VarkaMatrixRuns.skips(),
          Optional.ofNullable(System.getProperty(VarkaMatrixRuns.REPORT_PROPERTY)), guard,
          testClass.getSimpleName(), test, context.getTags(), answeredByChild, body);
    } finally {
      if (!invoked[0]) {
        invocation.skip();
      }
    }
  }

  /**
   * A {@code @TestFactory}'s dynamic test, held as a plain {@code @Test} is
   * ({@link #interceptTestMethod}) under the name the factory gave it, which is its skip-list key:
   * a name a class's tests have already used fails the test, since the names are only known as
   * the factory hands them out. An {@code ownJvm} class has none ({@link #checkShape}).
   */
  @Override
  public void interceptDynamicTest(Invocation<Void> invocation,
      DynamicTestInvocationContext invocationContext, ExtensionContext context)
      throws Throwable {
    // A dynamic test's context holds no class of its own; its factory's, a parent, does.
    Class<?> testClass = null;
    for (Optional<ExtensionContext> c = Optional.of(context); c.isPresent() && testClass == null;
        c = c.get().getParent()) {
      testClass = c.get().getTestClass().orElse(null);
    }
    if (testClass == null) {
      throw new IllegalStateException("a dynamic test outside any test class: "
          + context.getUniqueId());
    }
    Class<?> owner = testClass;
    VarkaSuite suite = AnnotationSupport.findAnnotation(owner, VarkaSuite.class)
        .orElseThrow(() -> new IllegalStateException(
            owner.getName() + " registers VarkaSuiteExtension without @VarkaSuite"));
    long minutes = capMinutes(suite);
    Guard guard = minutes == 0 ? Guard.NONE
        : (s, t, body) -> VarkaWatchdog.guard(s, t, minutes, body);
    String test = context.getDisplayName();
    if (!DYNAMIC_NAMES.computeIfAbsent(owner, c -> ConcurrentHashMap.newKeySet()).add(test)) {
      invocation.skip();
      throw new IllegalStateException(owner.getSimpleName() + " names more than one test "
          + "'" + test + "'; a Varka suite's test names are its skip-list keys and must be unique");
    }
    var invoked = new boolean[1];
    Body<Object> body = () -> {
      invoked[0] = true;
      invocation.proceed();
      return null;
    };
    try {
      runTest(VarkaMatrixRuns.CONFIG, VarkaMatrixRuns.skips(),
          Optional.ofNullable(System.getProperty(VarkaMatrixRuns.REPORT_PROPERTY)), guard,
          owner.getSimpleName(), test, context.getTags(), false, body);
    } finally {
      if (!invoked[0]) {
        invocation.skip();
      }
    }
  }

  /** The dynamic test names each class has run, for {@link #interceptDynamicTest}'s check. */
  private static final Map<Class<?>, Set<String>> DYNAMIC_NAMES = new ConcurrentHashMap<>();

  /** The minutes the watchdog gives each of {@code suite}'s tests: 0 for no watchdog. */
  static long capMinutes(VarkaSuite suite) {
    return suite.watchdogMinutes() == VarkaSuite.JVM_CAP
        ? VarkaWatchdog.defaultMinutes() : suite.watchdogMinutes();
  }

  /** What caps one test's body: the watchdog, or nothing. */
  @FunctionalInterface
  interface Guard {
    Guard NONE = (suite, test, body) -> body.run();

    Object run(String suite, String test, Body<Object> body) throws Throwable;
  }

  /**
   * One test under the harness, with this JVM's configuration, skip list, report file and cap
   * as parameters: the seam {@code VarkaSuiteHarnessSuite} tests the composition through, as
   * {@code VarkaMatrixSuite} tests the Scala face through {@code VarkaMatrix.around}'s.
   *
   * <p>{@code answeredByChild} is set for an {@code ownJvm} class in the shared JVM, whose
   * {@code body} only reads the child's result. The child already ran the test under the same
   * configuration, skip list and watchdog, so the body runs bare: the matrix applied a second
   * time would read the child's stale-entry failure as the listed failure and cancel it, and
   * would append its own fused-batch line over the child's. The child's budget caps the wait.
   */
  static Object runTest(String config, List<Skip> entries, Optional<String> report, Guard guard,
      String suite, String test, Set<String> tags, boolean answeredByChild, Body<Object> body)
      throws Throwable {
    if (answeredByChild) {
      return body.run();
    }
    if (!config.isEmpty() && tags.contains(VarkaSuite.PINS_DEFAULTS)) {
      throw new TestAbortedException(VarkaMatrixRuns.pinsDefaultsMessage(config));
    }
    if (!config.isEmpty() && tags.contains(VarkaSuite.C2_CODE)
        && !VarkaMatrixRuns.changesC2(config)) {
      throw new TestAbortedException(VarkaMatrixRuns.c2CodeMessage(config));
    }
    return VarkaMatrixRuns.around(entries, config, report, suite, test, OUTCOMES,
        () -> guard.run(suite, test, body));
  }

  // ---------------------------------------------------------------------------------------------
  // A class in a JVM of its own
  // ---------------------------------------------------------------------------------------------

  /**
   * The child JVM's results for {@code className}, running the child on the first call. The
   * child may run for the class's per-test cap less a minute ({@code capMinutes}, 0 for no cap),
   * the same cap its tests have in the child, and starts with {@code jvmFlags} after the parent's.
   */
  private static VarkaChildJvm.Results childResults(String className, long capMinutes,
      List<String> jvmFlags) {
    return CHILDREN.computeIfAbsent(className, c -> {
      try {
        return VarkaChildJvm.run(c, VarkaSuiteRunner.class.getName(), List.of("-oW", "-s", c),
            "-u", VarkaChildJvm.budgetMinutes(capMinutes), jvmFlags);
      } catch (Exception e) {
        throw new IllegalStateException("could not run " + c + " in a JVM of its own", e);
      }
    });
  }

  /**
   * The test {@code test}'s outcome in the shared JVM, from the child's results: it returns when
   * the child passed it, is cancelled with the child's reason, and fails with the child's message.
   */
  static Object answerFromChild(VarkaChildJvm.Results results, String test) {
    Optional<Result> result = results.of(test);
    if (result.isEmpty()) {
      return Assertions.fail("the child JVM reported no result for '" + test + "'");
    }
    return switch (result.get()) {
      case Passed p -> null;
      case Skipped s -> throw new TestAbortedException(s.reason());
      case Failed f -> Assertions.fail(f.message());
    };
  }
}
