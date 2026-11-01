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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a JUnit 5 class as a Varka suite (VARKA-312): its tests run under the option matrix's
 * skip list and {@code PinsDefaults} and {@code C2Code} cancellation, under the per-test
 * watchdog, and under the memory sanitizer's per-suite allocator check, as a ScalaTest suite that
 * mixes in {@code VarkaTestWatchdog} does. {@link VarkaSuiteExtension} does the work; this
 * annotation only registers it and carries its two parameters.
 *
 * <p>Each test carries its sentence as a {@code @DisplayName}, the way a ScalaTest test is named:
 * {@code @DisplayName("a reduction that fails differently is not smaller")}. That sentence is the
 * test's name everywhere a Varka tool shows or matches one - the lines {@link VarkaSuiteListener}
 * prints, the JUnit XML, the matrix's skip list ({@code sql/varka/matrix/skips.tsv}) and its
 * fused-batch report - and the suite is its class's simple name. Two tests of one class may not
 * share a name, as ScalaTest forbids too; the extension fails such a class.
 *
 * <p>A class named {@code Varka...Suite} under this annotation is found by
 * {@code dev/varka_matrix.sh} beside the Scala suites, so the gate, the matrix and CI's scoped
 * jobs run it with no change of their own. The script reads the annotation in the class's own
 * source file, so the annotation goes on the class itself: it is {@code @Inherited} only so that a
 * subclass relying on its base class's fails loudly under sbt instead of running as a plain JUnit
 * class. The extension also fails a class whose tests are not plain {@code @Test} methods
 * (parameterized, repeated or dynamic tests) or that holds {@code @Nested} classes, which the
 * harness cannot wrap or name.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(VarkaSuiteExtension.class)
public @interface VarkaSuite {

  /**
   * The tag of a test whose subject is the defaults' emitted structure, which the matrix cancels
   * under any configuration: {@code @Tag(VarkaSuite.PINS_DEFAULTS)}, the JUnit spelling of
   * ScalaTest's {@code VarkaMatrix.PinsDefaults}.
   */
  String PINS_DEFAULTS = VarkaMatrixRuns.PINS_DEFAULTS_TAG;

  /**
   * The tag of a test whose subject is the C2 code a kernel compiles to, which the matrix runs
   * only under a configuration that changes what C2 is given beyond the bytes and cancels under
   * the others: {@code @Tag(VarkaSuite.C2_CODE)}, the JUnit spelling of ScalaTest's
   * {@code VarkaMatrix.C2Code} ({@link VarkaMatrixRuns#C2_CODE_TAG}).
   */
  String C2_CODE = VarkaMatrixRuns.C2_CODE_TAG;

  /** The value of {@link #watchdogMinutes} that means the JVM's cap, the property's. */
  long JVM_CAP = -1;

  /**
   * Minutes a test may run before the watchdog acts: by default the JVM's cap
   * ({@code varka.test.watchdog.minutes}, ten unless set), {@code 0} for no watchdog - a suite
   * whose tests are meant to outlast any cap, as a fuzzer that mixes in {@code VarkaMatrixTests}
   * alone does in Scala. An {@link #ownJvm} class's child JVM is limited to this less a minute,
   * and not at all for {@code 0}.
   */
  long watchdogMinutes() default JVM_CAP;

  /**
   * Whether the class runs in a JVM of its own when it is run in a shared one, as a Scala suite
   * mixing in {@code VarkaOwnJvm} does: the first test to run starts a child JVM that runs the
   * whole class, and every test takes its result from the child's report: passed, failed with
   * the child's message, or cancelled with the child's reason. The skip list and the watchdog act
   * in the child only, so the parent never reinterprets the child's verdict.
   */
  boolean ownJvm() default false;

  /**
   * Flags an {@link #ownJvm} class's child JVM starts with after the parent's own, so the last
   * of a flag wins: {@code -XX:UseAVX=2} runs the class under AVX2 on an AVX-512 host. The
   * option matrix, which runs such a class in a JVM of its own without a child, gives that JVM
   * these flags instead ({@code dev/varka_matrix.sh}), so write them on one line.
   */
  String[] jvmFlags() default {};
}
