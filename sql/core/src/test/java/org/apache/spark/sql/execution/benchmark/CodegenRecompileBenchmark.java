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

package org.apache.spark.sql.execution.benchmark;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.spark.metrics.source.CodegenMetrics;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmarkBase;
import org.apache.spark.sql.catalyst.expressions.codegen.ByteCodeStats;
import org.apache.spark.sql.catalyst.expressions.codegen.CodeAndComment;
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator;
import org.apache.spark.sql.internal.SQLConf;

/**
 * What compiling a query again costs on stock Spark (VARKA-258.md 3, measurements 3, 4 and 6).
 *
 * <p>Spark caches its generated classes by their Java source, and writes integer, date and other
 * fixed-width literals into that source, so a query that differs from an earlier one only in such
 * a value is compiled again by Janino, and its new class starts again in the bytecode interpreter.
 * Three sections:
 *
 * <ol>
 *   <li>a short query over a small cached table with the same value each time, against a new
 *       value each time, for an integer, a date, a string (which Spark passes as a reference, the
 *       control) and an integer parameter; then, for each, the classes compiled per query and how
 *       much of the query's time Janino took;
 *   <li>Janino's compile time against a stage's size, over projections of {@code id + k} and
 *       aggregates of {@code sum}s of growing size, each compile of a source new to the cache
 *       (not {@code CASE WHEN}, whose stage this build already compiles while it generates the
 *       code, to decide whether to split it, SPARK-33301);
 *   <li>the same short query with the integer passed as a reference ({@link IntSlot}, a test-only
 *       expression that reads the value from a field of the generated class): the classes
 *       compiled and the time with a new value each time, and the steady-state cost of reading
 *       the value from a field instead of a constant on a large cached table.
 * </ol>
 *
 * <p>A new value comes from a counter, not from the benchmark's iteration number, which Spark's
 * harness holds at one value through each case's warm-up. Varka is off. Every timed query runs in
 * one JVM in local mode, where a stage is compiled by the driver and again by its first task (the
 * cache is keyed by the thread's class loader, SPARK-44795); on a cluster each executor compiles it
 * once more, which the counts of {@code sql/varka/demo/compiles/} show.
 *
 * <p>To run this benchmark, on a GitHub-hosted runner through {@code .github/workflows/
 * benchmark.yml} with {@code class=CodegenRecompileBenchmark}, or locally:
 * <pre>
 *   build/sbt "sql/Test/runMain org.apache.spark.sql.execution.benchmark.CodegenRecompileBenchmark"
 * </pre>
 * The results file is "benchmarks/CodegenRecompileBenchmark-jdk&lt;NN&gt;-results.txt".
 */
public class CodegenRecompileBenchmark extends VarkaBenchmarkBase {

  /** Rows of the small table, and the bound of the counter's values, which are its row ids. */
  private static final int SMALL_ROWS = 100_000;
  private static final long LARGE_ROWS = 20_000_000L;
  /** The value the "same value each time" cases use; the counter starts above it. */
  private static final int SAME = 5;
  /** Queries per arm in the tables of classes compiled and Janino's share. */
  private static final int SHARE_QUERIES = 40;
  /**
   * The modulus of the short query of the third section: past every row id, so that {@code i %
   * WIDE = v} matches the one row {@code v} for every value the counter gives, as {@code i = v}
   * does, while it keeps the cached table's batches from being pruned by the value, which the
   * reference could not do.
   */
  private static final int WIDE = 1_000_000;
  /** Compiles per rung of the compile-time table. */
  private static final int COMPILES_PER_RUNG = 9;

  private SparkSession spark;
  private int next = 1000;

  /** A value no earlier query of this run has used: a row id of the small table. */
  private int fresh() {
    require(next < SMALL_ROWS - 1, "the counter ran past the small table's row ids");
    return next++;
  }

  /** One way to run the short query: its label, the query for a value, and whether it is new. */
  private record Arm(String label, IntConsumer query, boolean freshEach) {
    void run(IntSupplier fresh) {
      query.accept(freshEach ? fresh.getAsInt() : SAME);
    }
  }

  private void collect(String sql) {
    spark.sql(sql).collect();
  }

  private void collect(String sql, Map<String, Object> args) {
    spark.sql(sql, args).collect();
  }

  private static String date(int v) {
    return LocalDate.of(2000, 1, 1).plusDays(v).toString();
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    spark = SparkSession.builder()
        .master("local[1]")
        .appName(getClass().getCanonicalName())
        .config(SQLConf.SHUFFLE_PARTITIONS().key(), 1)
        .config("spark.ui.enabled", false)
        .config(SQLConf.VARKA_ENABLED().key(), false)
        // Janino, whatever SPARK_CODEGEN_COMPILER says: the times below are Janino's.
        .config(SQLConf.CODEGEN_COMPILER().key(), "janino")
        .getOrCreate();
    spark.sql("CACHE TABLE t AS SELECT CAST(id AS INT) AS i, "
        + "DATE_ADD(DATE'2000-01-01', CAST(id AS INT)) AS d, CAST(id AS STRING) AS s "
        + "FROM RANGE(0, " + SMALL_ROWS + ", 1, 1)");
    spark.sql("CACHE TABLE large AS SELECT CAST(id AS INT) AS i "
        + "FROM RANGE(0, " + LARGE_ROWS + ", 1, 1)");

    List<Arm> literals = List.of(
        new Arm("int, the same value each time",
            v -> collect("SELECT count(*) FROM t WHERE i = " + v), false),
        new Arm("int, a new value each time",
            v -> collect("SELECT count(*) FROM t WHERE i = " + v), true),
        new Arm("date, a new value each time",
            v -> collect("SELECT count(*) FROM t WHERE d = DATE'" + date(v) + "'"), true),
        new Arm("string, a new value each time",
            v -> collect("SELECT count(*) FROM t WHERE s = '" + v + "'"), true),
        new Arm("int parameter, a new value each time",
            v -> collect("SELECT count(*) FROM t WHERE i = :p", Map.of("p", v)), true));
    // Spark's own classes compile on a session's first queries; each shape runs once first.
    literals.forEach(arm -> arm.query().accept(1));

    section("A short query over a small cached table: the same value each time, or a new one",
        () -> {
          shortQueries("SELECT count(*) FROM t WHERE <column> = <value>, " + SMALL_ROWS
              + " rows in one partition", literals);
          classesAndJaninoShare(literals);
        });

    section("Janino's compile time against a stage's size", this::compileTimes);

    List<Arm> slots = List.of(
        new Arm("literal, the same value each time", v -> modFilter("t", WIDE, lit(v)), false),
        new Arm("literal, a new value each time", v -> modFilter("t", WIDE, lit(v)), true),
        new Arm("reference, a new value each time",
            v -> modFilter("t", WIDE, CodegenRecompileSupport.intSlot(v)), true));
    slots.forEach(arm -> arm.query().accept(1));
    section("An integer passed as a reference instead of written into the source", () -> {
      // The reference must filter as the literal does, or the times below describe a broken
      // predicate; the line in the results file is the check's record.
      if (!dryRun()) {
        // The ends of the counter's range, from below and above, so the checks leave every value
        // it gives new to the cache.
        checkReference("t", WIDE, SAME, 1);
        checkReference("t", WIDE, 999, 1);
        checkReference("t", WIDE, SMALL_ROWS - 1, 1);
        checkReference("large", 1000, 7, LARGE_ROWS / 1000);
      }
      shortQueries("SELECT count(*) FROM t WHERE i % " + WIDE + " = <value>, " + SMALL_ROWS
          + " rows in one partition", slots);
      classesAndJaninoShare(slots);
      var steady = benchmark("steady state: SELECT count(*) FROM large WHERE i % 1000 = 7, "
          + LARGE_ROWS + " rows", LARGE_ROWS).timed().build();
      steady.addCase("literal", i -> modFilter("large", 1000, lit(7)));
      steady.addCase("reference",
          i -> modFilter("large", 1000, CodegenRecompileSupport.intSlot(7)));
      runCases(steady);
    });
  }

  /**
   * Refuses to go on unless the query counts {@code expected} rows for {@code v} both with the
   * literal and with the reference, and records the check in the results file: the reference's
   * times mean nothing if it filters differently.
   */
  private void checkReference(String table, int modulus, int v, long expected) {
    long byLiteral = modFilter(table, modulus, lit(v));
    long byReference = modFilter(table, modulus, CodegenRecompileSupport.intSlot(v));
    require(byLiteral == expected && byReference == expected, "for " + v + " the literal counted "
        + byLiteral + " rows and the reference " + byReference + ", not " + expected);
    note("check: SELECT count(*) FROM " + table + " WHERE i % " + modulus + " = " + v
        + " counts " + expected + " rows, with the literal and with the reference");
  }

  /** {@code SELECT count(*) FROM <table> WHERE i % <modulus> = <value>}, the DataFrame API's. */
  private long modFilter(String table, int modulus, Column value) {
    return spark.table(table).where(col("i").mod(modulus).equalTo(value)).count();
  }

  /** Times each arm's query: milliseconds per query, each iteration one query. */
  private void shortQueries(String title, List<Arm> arms) {
    var benchmark = benchmark(title, 1).timed().build();
    for (Arm arm : arms) {
      benchmark.addCase(arm.label(), i -> arm.run(this::fresh));
    }
    runCases(benchmark);
  }

  /**
   * For each arm, over {@link #SHARE_QUERIES} queries: the classes compiled per query, from
   * Spark's counter, and the milliseconds per query, all of it and Janino's part, from the
   * compiler's own clock. What is neither Janino nor the repeated query's time is mostly the new
   * class running in the interpreter before the JIT compiles it.
   */
  private void classesAndJaninoShare(List<Arm> arms) {
    if (dryRun()) {
      return;
    }
    note(String.format("%-40s %16s %16s %18s", "mean of " + SHARE_QUERIES + " queries",
        "classes / query", "ms / query", "Janino ms / query"));
    for (Arm arm : arms) {
      // One query first, unrecorded: the timed table before evicted the classes of the arms
      // that repeat a value.
      arm.run(this::fresh);
      long classes = CodegenMetrics.METRIC_COMPILATION_TIME().getCount();
      long janino = CodeGenerator.compileTime();
      long start = System.nanoTime();
      for (int q = 0; q < SHARE_QUERIES; q++) {
        arm.run(this::fresh);
      }
      double ms = (System.nanoTime() - start) / 1e6 / SHARE_QUERIES;
      double perQuery =
          (double) (CodegenMetrics.METRIC_COMPILATION_TIME().getCount() - classes) / SHARE_QUERIES;
      double janinoMs = (CodeGenerator.compileTime() - janino) / 1e6 / SHARE_QUERIES;
      note(String.format("%-40s %16.1f %16.2f %18.2f", arm.label(), perQuery, ms, janinoMs));
    }
    note("");
  }

  /** A shape of growing size whose literals come from an offset, so each offset is a new source. */
  private record Shape(String label, IntFunction<String> sql) {}

  /**
   * Compiles each rung's largest stage {@link #COMPILES_PER_RUNG} times, each time from a source
   * new to the cache, and prints the source's size, the largest method's and the compile's time:
   * the best and the median. The sources are generated before the clock starts; only Janino is
   * timed.
   */
  private void compileTimes() {
    if (dryRun()) {
      return;
    }
    List<Shape> shapes = new ArrayList<>();
    for (int n : new int[] {1, 10, 25, 50, 99}) {
      shapes.add(new Shape("projection of " + n + " entries id + k", o -> "SELECT "
          + IntStream.rangeClosed(1, n).mapToObj(k -> "id + " + (k + o) + " AS c" + k)
              .collect(Collectors.joining(", ")) + " FROM RANGE(10)"));
    }
    for (int n : new int[] {10, 20, 40, 60}) {
      shapes.add(new Shape("aggregate of " + n + " sums by a key", o -> "SELECT id % 10 AS g, "
          + IntStream.rangeClosed(1, n).mapToObj(k -> "sum(id + " + (k + o) + ") AS s" + k)
              .collect(Collectors.joining(", ")) + " FROM RANGE(10) GROUP BY g"));
    }
    // Janino's own code is cold in a new JVM; a dashboard's server has compiled many classes.
    for (int w = 0; w < 50; w++) {
      CodeGenerator.compile(source(shapes.get(1), 1_000_000 + w * 1000));
    }
    note(String.format("%-36s %12s %16s %12s %12s", "shape, each compile a new source",
        "source chars", "largest method", "best ms", "median ms"));
    int offset = 2_000_000;
    for (Shape shape : shapes) {
      List<CodeAndComment> sources = new ArrayList<>();
      for (int c = 0; c < COMPILES_PER_RUNG; c++) {
        offset += 10_000;
        sources.add(source(shape, offset));
      }
      double[] ms = new double[COMPILES_PER_RUNG];
      ByteCodeStats stats = null;
      for (int c = 0; c < COMPILES_PER_RUNG; c++) {
        long start = System.nanoTime();
        stats = CodeGenerator.compile(sources.get(c))._2();
        ms[c] = (System.nanoTime() - start) / 1e6;
      }
      Arrays.sort(ms);
      note(String.format("%-36s %12d %10d bytes %12.2f %12.2f", shape.label(),
          sources.get(0).body().length(), stats.maxMethodCodeSize(), ms[0],
          ms[COMPILES_PER_RUNG / 2]));
    }
    note("");
  }

  private CodeAndComment source(Shape shape, int offset) {
    return CodegenRecompileSupport.largestStageSource(spark.sql(shape.sql().apply(offset)));
  }

  @Override
  public void afterAll() {
    if (spark != null) {
      spark.stop();
    }
  }
}
