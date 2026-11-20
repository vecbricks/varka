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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.UniqueId;
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
 * <p>A generated test is held the same way under its key, the name the skip list, the reports
 * and the child's answers know it by: a {@code @TestFactory}'s dynamic test under the name its
 * factory gives it, and an invocation of a {@code @ParameterizedTest} or a {@code @RepeatedTest}
 * under its method's display name, a space and the invocation's rendered name
 * ({@link #invocationKey}).
 *
 * <p>A cancel is opentest4j's {@link TestAbortedException}, which JUnit reports as aborted and the
 * listener prints {@code !!! CANCELED !!!}. It is thrown from the interceptor rather than decided
 * by an {@code ExecutionCondition}, because a condition would report the test disabled, which is
 * ScalaTest's "ignored", not its "canceled".
 *
 * <p>Around the class: before it, the extension fails a class whose shape it cannot hold
 * ({@link #checkShape}: two tests with one name, a template whose invocations make no stable key,
 * a {@code @Nested} class, the annotation only inherited); and under
 * the memory sanitizer it holds the class to leave Arrow's root allocator where it found it, as
 * {@code VarkaMatrixTests.run} does.
 */
public final class VarkaSuiteExtension
    implements BeforeAllCallback, AfterAllCallback, InvocationInterceptor, ExecutionCondition {

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
    GENERATED_KEYS.remove(context.getRequiredTestClass());
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
   *   <li>a test template whose invocations make no stable key ({@link #templateRefusal}): one
   *       other than {@code @ParameterizedTest} and {@code @RepeatedTest}, or one whose name
   *       numbers its invocations by a count that moves when a case is added;</li>
   *   <li>a class template ({@code @ParameterizedClass}), whose invocations rerun each test
   *       under one name;</li>
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
    for (Method m : AnnotationSupport.findAnnotatedMethods(testClass, TestTemplate.class,
        HierarchyTraversalMode.TOP_DOWN)) {
      Optional<String> refusal = templateRefusal(m);
      if (refusal.isPresent()) {
        throw new IllegalStateException(name + "." + m.getName() + " " + refusal.get());
      }
    }
    if (AnnotationSupport.isAnnotated(testClass, ClassTemplate.class)) {
      throw new IllegalStateException(name + " is a class template (@ParameterizedClass, ...),"
          + " which runs every test once an invocation under one name; a @VarkaSuite class's"
          + " tests are generated by method templates, which the harness keys apart");
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

  // A placeholder of a @ParameterizedTest name that renders an argument: {0}, {1,number,#}, ...
  private static final Pattern ARGUMENT_PLACEHOLDER = Pattern.compile(
      "\\{(\\d+(,[^}]*)?|arguments|argumentsWithNames|argumentSetName"
          + "|argumentSetNameOrArgumentsWithNames)}");

  // The placeholder of @ParameterizedTest's default name, which JUnit renders as "[{index}] ...".
  private static final String DEFAULT_NAME_PLACEHOLDER = "{default_display_name}";

  /**
   * Why the harness cannot hold the test template {@code m}, or empty when it can. An
   * invocation's key is its method's display name and its rendered name ({@link #invocationKey}),
   * and a key is a skip-list line's and the ledger's name for the test, so it must not move when
   * a case is added: a {@code @ParameterizedTest}'s name renders an argument and holds neither
   * {@code {index}} (nor JUnit's default, which renders it) nor {@code {displayName}} (which the
   * key holds already), and a
   * {@code @RepeatedTest}'s holds {@code {currentRepetition}} and not the total. Any other kind
   * of template is refused, since nothing says what its invocations' names are.
   */
  static Optional<String> templateRefusal(Method m) {
    Optional<ParameterizedTest> parameterized =
        AnnotationSupport.findAnnotation(m, ParameterizedTest.class);
    if (parameterized.isPresent()) {
      String pattern = parameterized.get().name();
      if (!ARGUMENT_PLACEHOLDER.matcher(pattern).find()
          || pattern.contains(ParameterizedTest.INDEX_PLACEHOLDER)
          || pattern.contains(DEFAULT_NAME_PLACEHOLDER)
          || pattern.contains(ParameterizedTest.DISPLAY_NAME_PLACEHOLDER)) {
        return Optional.of("names its invocations '" + pattern + "'; in a Varka suite a"
            + " @ParameterizedTest's name renders an argument and holds no {index},"
            + " {default_display_name} or {displayName}, since the method's display name and"
            + " that name are each invocation's skip-list key");
      }
      return Optional.empty();
    }
    Optional<RepeatedTest> repeated = AnnotationSupport.findAnnotation(m, RepeatedTest.class);
    if (repeated.isPresent()) {
      String pattern = repeated.get().name();
      if (!pattern.contains(RepeatedTest.CURRENT_REPETITION_PLACEHOLDER)
          || pattern.contains(RepeatedTest.TOTAL_REPETITIONS_PLACEHOLDER)
          || pattern.contains(RepeatedTest.DISPLAY_NAME_PLACEHOLDER)) {
        return Optional.of("names its repetitions '" + pattern + "'; in a Varka suite a"
            + " @RepeatedTest is named \"repetition {currentRepetition}\", or another name with"
            + " the repetition and neither the total nor {displayName}, since the method's"
            + " display name and that name are each repetition's skip-list key");
      }
      return Optional.empty();
    }
    return Optional.of("is a test template other than @ParameterizedTest and @RepeatedTest,"
        + " whose invocations the harness cannot name");
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
    hold(invocation, context, context.getRequiredTestClass(), context.getDisplayName(), false);
  }

  /**
   * A {@code @TestFactory}'s dynamic test, held as a plain {@code @Test} is
   * ({@link #interceptTestMethod}) under the name the factory gave it, which is its key.
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
      invocation.skip();
      throw new IllegalStateException("a dynamic test outside any test class: "
          + context.getUniqueId());
    }
    hold(invocation, context, testClass, context.getDisplayName(), true);
  }

  /**
   * An invocation of a {@code @ParameterizedTest} or a {@code @RepeatedTest}, held as a plain
   * {@code @Test} is under its key, {@link #invocationKey} of its template's display name and
   * its own.
   */
  @Override
  public void interceptTestTemplateMethod(Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext, ExtensionContext context)
      throws Throwable {
    hold(invocation, context, context.getRequiredTestClass(), keyOf(context), true);
  }

  /**
   * Runs one test of {@code testClass}, or takes its result from the child JVM, under
   * {@link #runTest}, with {@code test} as its key. A {@code generated} test's key is known only
   * as it runs, so a key the class's generated tests have already used fails the test here.
   */
  private static void hold(Invocation<Void> invocation, ExtensionContext context,
      Class<?> testClass, String test, boolean generated) throws Throwable {
    VarkaSuite suite = AnnotationSupport.findAnnotation(testClass, VarkaSuite.class)
        .orElse(null);
    if (suite == null) {
      invocation.skip();
      throw new IllegalStateException(
          testClass.getName() + " registers VarkaSuiteExtension without @VarkaSuite");
    }
    // The set starts with the plain tests' names, so a generated key may not take one either.
    if (generated && !GENERATED_KEYS.computeIfAbsent(testClass, c -> {
      Set<String> keys = ConcurrentHashMap.newKeySet();
      keys.addAll(displayNames(c));
      return keys;
    }).add(test)) {
      invocation.skip();
      throw new IllegalStateException(testClass.getSimpleName() + " names more than one test "
          + "'" + test + "'; a Varka suite's test names are its skip-list keys and must be unique");
    }
    long minutes = capMinutes(suite);
    Guard guard = minutes == 0 ? Guard.NONE
        : (s, t, body) -> VarkaWatchdog.guard(s, t, minutes, body);
    boolean answeredByChild = suite.ownJvm() && !VarkaChildJvm.inChild();
    // Jupiter requires an intercepted invocation to be proceeded or skipped exactly once; a test
    // cancelled before its body, or answered from the child JVM, skips it.
    var invoked = new boolean[1];
    Body<Object> body;
    if (answeredByChild) {
      String className = testClass.getName();
      body = () -> {
        invoked[0] = true;
        invocation.skip();
        return answerFromChild(
            childResults(className, minutes, List.of(suite.jvmFlags())), test);
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
   * The plain tests' names and the generated test keys each class has run, for {@link #hold}'s
   * check.
   */
  private static final Map<Class<?>, Set<String>> GENERATED_KEYS = new ConcurrentHashMap<>();

  /**
   * The type of the last unique-id segment of a test template's invocation, which Jupiter's
   * engine gives it and the launcher's identifiers carry.
   */
  static final String INVOCATION_SEGMENT = "test-template-invocation";

  /**
   * The key of a test template's invocation: the template's display name, a space, and the
   * invocation's rendered name, {@code SMALLINT sweep [LEGACY, +, widened]}. The listener names
   * the invocation so, and the child JVM's answers are read back by it.
   */
  static String invocationKey(String template, String invocation) {
    return template + " " + invocation;
  }

  /** Whether the unique id {@code uniqueId} is a test template's invocation's. */
  static boolean isInvocation(String uniqueId) {
    return UniqueId.parse(uniqueId).getLastSegment().getType().equals(INVOCATION_SEGMENT);
  }

  /** The key of the test template invocation {@code context} runs. */
  private static String keyOf(ExtensionContext context) {
    String template = context.getParent().map(ExtensionContext::getDisplayName)
        .orElseThrow(() -> new IllegalStateException(
            "a template invocation with no template: " + context.getUniqueId()));
    return invocationKey(template, context.getDisplayName());
  }

  /**
   * Leaves out a template's invocation that {@link VarkaSuiteRunner}'s {@code -z} or {@code -t}
   * filters do not select. The runner filters the other tests at discovery, where a template's
   * invocations do not exist yet, so it hands its filters here as a configuration parameter. The
   * invocation is disabled with {@link VarkaSuiteRunner#NOT_SELECTED}, which the listener
   * neither prints nor counts, as ScalaTest's runner shows nothing of a test it filtered out.
   */
  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (context.getTestMethod().isEmpty() || !isInvocation(context.getUniqueId())) {
      return ConditionEvaluationResult.enabled("not a template invocation");
    }
    Optional<String> filters = context.getConfigurationParameter(
        VarkaSuiteRunner.FILTERS_PARAMETER + context.getRequiredTestClass().getName());
    if (filters.isEmpty() || VarkaSuiteRunner.selects(filters.get(), keyOf(context))) {
      return ConditionEvaluationResult.enabled("selected");
    }
    return ConditionEvaluationResult.disabled(VarkaSuiteRunner.NOT_SELECTED);
  }

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
