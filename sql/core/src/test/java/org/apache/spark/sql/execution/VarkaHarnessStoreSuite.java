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

package org.apache.spark.sql.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import scala.collection.immutable.Seq;

import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.internal.SQLConf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The harness's store ({@link VarkaHarnessStore}) as the defaults' run and a configuration's run
 * use it, both played in this JVM: a writing store stands for the defaults' run, a store reading
 * its entries under a named configuration for the configuration's, over a second making of the
 * fixture, whose columns have other expression ids, as another JVM's would.
 */
@VarkaSuite
public final class VarkaHarnessStoreSuite {

  private static final String FIXTURE = "SELECT rid, i, d FROM VALUES "
      + "(0, 1, DATE'2021-03-04'), (1, 2, DATE'2022-05-06'), "
      + "(2, CAST(NULL AS INT), CAST(NULL AS DATE)) AS v(rid, i, d)";

  @AfterEach
  void matrixOptions() {
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE);
  }

  @Test
  @DisplayName("a configuration's run plans only what the defaults' run did not store")
  void plansOnlyWhatTheStoreLacks() {
    VarkaHarnessPlans.Fixture fixture = VarkaHarnessPlans.fixture(FIXTURE);
    VarkaHarnessStore defaults = VarkaHarnessStore.writing();
    VarkaHarnessStore.with(defaults, () -> {
      VarkaHarnessPlans.projection("i + 1", fixture.columns());
      VarkaHarnessPlans.projection("year(d)", fixture.columns());
      VarkaHarnessPlans.condition("i > 1", fixture.columns());
      return null;
    });
    assertEquals(3, defaults.planMisses.get());
    assertEquals(0, defaults.planUnstorable.get());

    VarkaHarnessPlans.Fixture again = VarkaHarnessPlans.fixture(FIXTURE);
    VarkaHarnessStore config = defaults.readingAs("cse=false");
    config.remove(key -> key.contains("year(d)"));
    Seq<NamedExpression> taken = VarkaHarnessStore.with(config, () -> {
      VarkaHarnessPlans.projection("year(d)", again.columns());
      VarkaHarnessPlans.condition("i > 1", again.columns());
      return VarkaHarnessPlans.projection("i + 1", again.columns());
    });
    assertEquals(2, config.planHits.get(), config.line());
    assertEquals(1, config.planMisses.get(), config.line());
    // What the configuration took reads the second fixture's columns, as its own plan does.
    Seq<NamedExpression> planned = VarkaHarnessPlans.projection("i + 1", again.columns());
    assertEquals(VarkaHarnessStore.describe(planned, true),
        VarkaHarnessStore.describe(taken, true));
  }

  /** The coverage harness's conf for {@code ansi}, as a check reads it. */
  private static SQLConf conf(boolean ansi) {
    return VarkaCoverageHarnessSuite.conf(ansi);
  }

  /** {@code expression}'s projection check on {@code fixture} under {@code conf}. */
  private static VarkaExpressionHarness.Outcome check(String expression,
      VarkaHarnessPlans.Fixture fixture, SQLConf conf) {
    return VarkaCoverageHarnessSuite.withConf(conf, () -> VarkaExpressionHarness.projection(
        expression, VarkaHarnessPlans.projection(expression, fixture.columns()), fixture, conf));
  }

  /** The keys of {@code store}'s entries that begin with {@code kind}. */
  private static List<String> keys(VarkaHarnessStore store, String kind) {
    return store.entries().keySet().stream().filter(k -> k.startsWith(kind + "\t")).toList();
  }

  @Test
  @DisplayName("a configuration's run holds the kernel to Spark's stored answer and evaluates "
      + "Spark for none it holds")
  void configurationTakesStoredAnswers() {
    SQLConf conf = conf(true);
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS);
    VarkaHarnessStore defaults = VarkaHarnessStore.writing();
    VarkaHarnessPlans.Fixture fixture = VarkaHarnessPlans.fixture(FIXTURE);
    assertTrue(VarkaHarnessStore.with(defaults, () -> check("i + 1", fixture, conf)).ok());
    assertEquals(1, keys(defaults, "answer").size(), defaults.line());

    // Under a configuration whose options this JVM's kernels carry, over a second fixture.
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.parse("cse=false"));
    VarkaHarnessStore config = defaults.readingAs("cse=false");
    VarkaHarnessPlans.Fixture again = VarkaHarnessPlans.fixture(FIXTURE);
    VarkaExpressionHarness.Outcome outcome =
        VarkaHarnessStore.with(config, () -> check("i + 1", again, conf));
    assertTrue(outcome.ok(), String.join("\n", outcome.findings));
    assertTrue(outcome.answered > 0, "the kernel answered no batch");
    assertEquals(1, config.answerHits.get(), config.line());
    assertEquals(0, config.answerMisses.get(), config.line());
    // An expression the store lacks is evaluated, once.
    VarkaHarnessStore.with(config, () -> check("i + 2", again, conf));
    assertEquals(1, config.answerMisses.get(), config.line());
  }

  @Test
  @DisplayName("a wrong stored answer fails the check it is taken by")
  void plantedAnswerFails() {
    // The fault is planted in the store: the answers to i + 2 written under the key of i + 1's,
    // which a kernel that answers i + 1 rightly must then disagree with on every row.
    SQLConf conf = conf(true);
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaEmitOptions.DEFAULTS);
    VarkaHarnessPlans.Fixture fixture = VarkaHarnessPlans.fixture(FIXTURE);
    VarkaHarnessStore one = VarkaHarnessStore.writing();
    VarkaHarnessStore.with(one, () -> check("i + 1", fixture, conf));
    VarkaHarnessStore two = VarkaHarnessStore.writing();
    VarkaHarnessStore.with(two, () -> check("i + 2", fixture, conf));
    String key = keys(one, "answer").get(0);
    byte[] wrong = two.entries().get(keys(two, "answer").get(0));

    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.parse("cse=false"));
    VarkaHarnessStore config = one.readingAs("cse=false");
    config.plant(key, wrong);
    VarkaExpressionHarness.Outcome planted =
        VarkaHarnessStore.with(config, () -> check("i + 1", fixture, conf));
    assertEquals(1, config.answerHits.get(), config.line());
    assertTrue(planted.findings.stream().anyMatch(f -> f.contains("output 0: Spark 3, Varka 2")),
        "the harness missed the planted answer: " + planted.findings);
  }

  @Test
  @DisplayName("a Scala object keys by its class, not by a toString that names its identity")
  void scalaObjectKeysByClass() {
    // AnyTimeType's toString is Object's, its identity hash, which another JVM does not share.
    Object o = org.apache.spark.sql.types.AnyTimeType$.MODULE$;
    assertEquals(o.getClass().getName(), VarkaHarnessStore.describe(o));
  }

  @Test
  @DisplayName("the store's entries come back from its files as they were written")
  void filesRoundTrip() throws IOException {
    Path dir = Files.createTempDirectory("varka-harness-store");
    try {
      VarkaHarnessPlans.Fixture fixture = VarkaHarnessPlans.fixture(FIXTURE);
      VarkaHarnessStore defaults = VarkaHarnessStore.writing();
      VarkaHarnessStore.with(defaults, () ->
          VarkaHarnessPlans.projection("i + 1", fixture.columns()));
      VarkaHarnessStore.write(dir, defaults.entries());
      VarkaHarnessStore.write(dir, Map.of("only in the second file", new byte[] {1, 2}));
      Map<String, byte[]> read = VarkaHarnessStore.read(dir);
      assertEquals(defaults.entries().size() + 1, read.size());
      for (Map.Entry<String, byte[]> e : defaults.entries().entrySet()) {
        assertTrue(java.util.Arrays.equals(e.getValue(), read.get(e.getKey())), e.getKey());
      }
    } finally {
      try (var files = Files.list(dir)) {
        for (Path f : files.toList()) {
          Files.delete(f);
        }
      }
      Files.delete(dir);
    }
  }

  @Test
  @DisplayName("a file written on another commit or options table is ignored, and the rest read")
  void staleFilesIgnored() throws IOException {
    Path dir = Files.createTempDirectory("varka-harness-store");
    try {
      VarkaHarnessStore.Stamp mine = VarkaHarnessStore.stamp();
      VarkaHarnessStore.write(dir, Map.of("this commit", new byte[] {1}), mine);
      VarkaHarnessStore.write(dir, Map.of("another commit", new byte[] {2}),
          new VarkaHarnessStore.Stamp(mine.commit() + "-other", mine.options()));
      VarkaHarnessStore.write(dir, Map.of("another table", new byte[] {3}),
          new VarkaHarnessStore.Stamp(mine.commit(), mine.options() + "-other"));
      // A file cut short, as a download that stopped would leave it: none of it is read.
      Path whole = dir.resolve("whole");
      VarkaHarnessStore.write(whole, Map.of("cut short", new byte[64]), mine);
      try (var files = Files.list(whole)) {
        Path file = files.toList().get(0);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(dir.resolve("harness-cut.bin"),
            java.util.Arrays.copyOf(bytes, bytes.length / 2));
        Files.delete(file);
      }
      Files.delete(whole);

      VarkaHarnessStore.Read read = VarkaHarnessStore.read(dir, mine);
      assertEquals(Map.of("this commit", 1), Map.copyOf(
          read.entries().entrySet().stream().collect(java.util.stream.Collectors.toMap(
              Map.Entry::getKey, e -> (int) e.getValue()[0]))));
      assertEquals(3, read.ignored().size(), String.join("\n", read.ignored()));
      assertTrue(read.ignored().stream().anyMatch(l -> l.contains("commit " + mine.commit()
          + "-other")), String.join("\n", read.ignored()));
      assertTrue(read.ignored().stream().anyMatch(l -> l.contains("options table")),
          String.join("\n", read.ignored()));
      assertTrue(read.ignored().stream().anyMatch(l -> l.contains("harness-cut.bin")),
          String.join("\n", read.ignored()));
      // A directory the defaults' run never wrote reads as an empty store.
      assertTrue(VarkaHarnessStore.read(dir.resolve("missing"), mine).entries().isEmpty());
    } finally {
      try (var files = Files.list(dir)) {
        for (Path f : files.toList()) {
          Files.delete(f);
        }
      }
      Files.delete(dir);
    }
  }
}
