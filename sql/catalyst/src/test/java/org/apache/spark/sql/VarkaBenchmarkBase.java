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

package org.apache.spark.sql;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import scala.Option;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;
import scala.runtime.BoxedUnit;

import org.apache.spark.benchmark.BenchmarkBase;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;

/**
 * The base of a Varka benchmark written in Java: Spark's {@link BenchmarkBase}, with sections and
 * benchmarks a Java body can make without touching a Scala type ({@link VarkaBenchmark}).
 *
 * <p>A subclass implements {@code runBenchmarkSuite(String[])} and declares no {@code main}.
 * {@code BenchmarkBase.main} is an instance method, which Java will not let a static one hide; JDK
 * 25's launcher calls an inherited instance {@code main} on a class with none of its own (JEP
 * 512), and {@code build/sbt "<module>/Test/runMain <class>"} forks through that launcher. So
 * Spark's own {@code main} runs, and names and writes the results file as it does for a Scala
 * benchmark: {@code dev/varka_bench_regen.sh catalyst <class>} works unchanged. An unforked sbt
 * {@code run} would not find the method; the test configurations all fork. Spark's {@code
 * Benchmarks} runner, which {@code .github/workflows/benchmark.yml} drives through {@code
 * spark-submit}, makes an instance with the no-arg constructor when the {@code main} it finds is
 * not static. {@code spark-submit --class <a Java benchmark>} on its own does not: it requires a
 * static {@code main}, so a Java benchmark goes through {@code Benchmarks} there.
 *
 * <p>A benchmark that names its kernels by case id gets the dry run and the case filter from here
 * ({@link #runCases}, {@link #claimCaseId}, {@link #reportCaseIds}).
 */
public abstract class VarkaBenchmarkBase extends BenchmarkBase {

  /** Refuses a dry run or a filtered run that would write a results file, then runs Spark's. */
  @Override
  public void main(String[] args) {
    // Spark's main opens the results file before it calls runBenchmarkSuite, and opening it
    // empties it, so the check has to come first: a later one would find the committed file
    // already gone.
    boolean writesResults = "1".equals(System.getenv("SPARK_GENERATE_BENCHMARK_FILES"));
    if (writesResults && (dryRun() || only().isPresent())) {
      String partial = dryRun()
          ? "-D" + DRY_RUN_PROPERTY + "=true"
          : "-D" + ONLY_PROPERTY + "=" + only().orElseThrow();
      throw new IllegalArgumentException(partial + " runs part of a benchmark, which must not "
          + "be written to its results file: unset SPARK_GENERATE_BENCHMARK_FILES, or drop the "
          + "property");
    }
    super.main(args);
  }

  // ---------------------------------------------------------------------------------------
  // Sections and benchmarks
  // ---------------------------------------------------------------------------------------

  /** A titled section of the results file, holding what {@code body} runs. */
  protected final void section(String title, Runnable body) {
    runBenchmark(title, () -> {
      body.run();
      return BoxedUnit.UNIT;
    });
  }

  /** A benchmark writing to this run's results file; {@code valuesPerIteration} sets the rate. */
  protected final VarkaBenchmark.Builder benchmark(String name, long valuesPerIteration) {
    return new VarkaBenchmark.Builder(name, valuesPerIteration, output());
  }

  /**
   * Prints {@code line} and writes it to the results file, if the run writes one: a fact a
   * section's tables depend on, kept beside them so the file says what was measured. The sql/core
   * benchmarks, {@code VarkaSqlBenchmarkBase}'s among them, use this one.
   */
  protected final void note(String line) {
    System.out.println(line);
    Option<OutputStream> out = output();
    if (out.isDefined()) {
      try {
        out.get().write((line + "\n").getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // Dry runs and case filters
  // ---------------------------------------------------------------------------------------

  private static final String DRY_RUN_PROPERTY = "varka.bench.dryRun";
  private static final String ONLY_PROPERTY = "varka.bench.only";

  /** Every case id passed to {@link #claimCaseId}, which a dry run prints. */
  private final TreeSet<Integer> caseIds = new TreeSet<>();

  /**
   * Whether this is a structural dry run, {@code -Dvarka.bench.dryRun=true}: every kernel is
   * emitted and its id claimed, but no case is timed, so {@code dev/varka_bench_ids.sh} checks a
   * file's ids in about the time a JVM takes to start. A dry run measures nothing; it checks the
   * file's structure and is never a substitute for a regeneration.
   *
   * <p>Only {@code true} and {@code false} are accepted, in any case, as Scala's {@code toBoolean}
   * accepts them: a value such as {@code 1} read as false would start the full timed run the
   * caller meant to skip.
   */
  protected static boolean dryRun() {
    String value = System.getProperty(DRY_RUN_PROPERTY, "false");
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException(
          "-D" + DRY_RUN_PROPERTY + " must be true or false, not \"" + value + "\"");
    };
  }

  /**
   * The case-name substring of {@code -Dvarka.bench.only}, for investigating one block without
   * paying for the whole file. Diagnostic only: {@link #main} refuses it when the run would write
   * a results file, since the committed file is the whole surface.
   */
  protected static Optional<String> only() {
    return Optional.ofNullable(System.getProperty(ONLY_PROPERTY)).filter(s -> !s.isEmpty());
  }

  /**
   * Times {@code benchmark}'s cases, only those whose name contains {@link #only()}, and none in
   * a dry run. Spark's {@code run} refuses a benchmark with no cases, so a filter that empties
   * one skips it.
   */
  protected static void runCases(VarkaBenchmark benchmark) {
    if (dryRun()) {
      return;
    }
    only().ifPresent(benchmark::retainCases);
    if (benchmark.hasCases()) {
      benchmark.run();
    }
  }

  /**
   * Records the case id a kernel is named by, refusing one already used. A reused id would
   * otherwise surface as a {@code LinkageError} from the class loader, well into a regeneration
   * and pointing at the loader rather than at the two cases that chose the same number.
   */
  protected final void claimCaseId(int id) {
    require(caseIds.add(id), "case id " + id + " is already in use by another emit in this "
        + "benchmark");
  }

  /**
   * In a dry run, prints every claimed id and the first one free, the two lines {@code
   * dev/varka_bench_ids.sh} reads; otherwise nothing.
   */
  protected final void reportCaseIds() {
    if (dryRun()) {
      String ids = caseIds.stream().map(String::valueOf).collect(Collectors.joining(","));
      int next = caseIds.isEmpty() ? 0 : caseIds.last() + 1;
      System.out.println("varka-bench-ids: " + caseIds.size() + " cases, ids " + ids);
      System.out.println("varka-bench-next-free-id: " + next);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Helpers for setting up a benchmark
  // ---------------------------------------------------------------------------------------

  /**
   * Scala's {@code require}, for the checks a benchmark makes while it sets up. The message is
   * built before the call, so a check inside a timed loop tests its condition and throws {@link
   * #requirementFailed} itself, keeping the loop free of string building. Public, so that a class
   * of static helpers beside the benchmarks ({@code VarkaSizeLadder}, {@code VarkaColdPath}) and a
   * probe's {@code main} fail with the same exception and prefix as the benchmarks do.
   */
  public static void require(boolean requirement, String message) {
    if (!requirement) {
      throw requirementFailed(message);
    }
  }

  /** The exception Scala's {@code require} throws, with its message prefix. */
  public static IllegalArgumentException requirementFailed(String message) {
    return new IllegalArgumentException("requirement failed: " + message);
  }

  /** Defines an emitted kernel's class in {@code loader} and makes an instance of it. */
  protected static VarkaFusedKernel defineKernel(
      VarkaGeneratedClassLoader loader, String className, byte[] bytes) {
    // The class defineGeneratedClass returns is the one just defined, so no lookup by name.
    Class<?> clazz = loader.defineGeneratedClass(className, bytes);
    try {
      return (VarkaFusedKernel) clazz.getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot instantiate " + className, e);
    }
  }

  /** {@code entry(0)} to {@code entry(n - 1)}: the roots of a kernel of {@code n} outputs. */
  protected static List<VarkaVectorIR> roots(int n, IntFunction<VarkaVectorIR> entry) {
    var out = new ArrayList<VarkaVectorIR>(n);
    for (int k = 0; k < n; k++) {
      out.add(entry.apply(k));
    }
    return out;
  }

  /** A Java list as the Scala {@code Seq} the compiler's and Spark's entry points take. */
  protected static <T> Seq<T> seq(List<T> list) {
    return CollectionConverters.asScala(list).toSeq();
  }
}
