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

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;

/**
 * The baseline for the math functions before Varka fuses them (VARKA-345): Spark's row path over
 * an Arrow-cached table of {@code DOUBLE} columns, one section per function and null density, so
 * that the rows that fuse the functions report what they change against a committed file.
 *
 * <p>The functions are {@code sin(x)}, {@code tan(x)}, {@code cbrt(x)}, {@code exp(x)},
 * {@code log(p)}, {@code log10(p)}, {@code pow(p, y)}, {@code atan2(y, x)}, {@code sinh(x)},
 * {@code hypot(x, y)}, {@code sec(x)} and the composition {@code exp(x) * 2 + 1}, each with no
 * nulls and with one row in ten null. They span the two kinds of function Spark calls
 * (VARKA-345.md 0, item 3): {@code exp}, {@code log}, {@code log10}, {@code pow}, {@code atan2},
 * {@code sinh} and {@code hypot} are fdlibm on every host, and {@code sin}, {@code tan},
 * {@code cbrt} and {@code sec} (which is {@code 1 / Math.cos}) are the host's own stub on x86.
 * The composition times the arithmetic around a call, where a kernel that calls the function per
 * lane can still gain. {@code log(p)} with one argument is Spark's {@code Logarithm} over the
 * base e, which calls {@code StrictMath.log}. None of these functions compiles differently under
 * ANSI, so every section runs in the session's default mode.
 *
 * <p>The plan names {@code log(x)}, {@code log10(x)} and {@code pow(x, y)}; they read {@code p}
 * here, a positive column, since both logarithms answer null at {@code x <= 0} and {@code pow} of
 * a negative base and a fractional exponent is NaN, and neither would time the function's
 * ordinary path.
 *
 * <p>Each section has two arms, named so the labels survive the row landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Today no section plans a Varka node, so both arms are the row path and should
 * tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@link Fusion} class, the
 * record of how much of it a row has fused, and fails when the plan's class differs
 * ({@link VarkaSqlBenchmarkBase#requireBaseline(SparkSession, String, String, Fusion)}); the
 * results file notes the class of each section that fuses. Every section is {@code NONE}, since
 * every math function declines today (VARKA-345.md 0, item 12). A row that fuses a section sets
 * its new class, so that its fusion cannot turn a baseline section into a fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaMathBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which changes a section's class against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaMathBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaMathBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaMathBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_math";

  /**
   * A section: its title, its query, and how much of it the Varka session fuses today, the
   * {@link VarkaSqlBenchmarkBase.Fusion} class the run checks its plan against before timing. The
   * rest is what the queries suite checks its answer against: whether it reads the columns with
   * one row in ten null, and how many such columns it reads, since each is null at its own rows.
   */
  record Section(String title, String query, Fusion fusion, boolean nullInputs, int columns) {}

  /**
   * The table: {@code x}, {@code y} and {@code p} as doubles with no null, and {@code x_n},
   * {@code y_n} and {@code p_n} the same values with one row in ten null, at a different row in
   * each column. Each column is a hash of the row's id, so any few thousand rows reach both ends of
   * its range, as the queries suite checks, and no value is NaN, infinite or huge: item 0.9 of
   * VARKA-345.md measured a lane at {@code 1e300} or NaN as a slow path of a library call, which
   * a baseline of ordinary values must not time.
   *
   * <ul>
   *   <li>{@code x} is {@code hash(id, 1) / 2^31 * 10}, in {@code [-10, 10)}: both signs, so
   *       {@code sin}, {@code tan}, {@code sec} and {@code cbrt} see both sides of zero, and
   *       mostly past {@code pi / 4}, so the trigonometric functions reduce their argument;
   *       {@code exp(x)} stays inside {@code [4.5e-5, 2.3e4]} and {@code sinh(x)} inside
   *       {@code +-1.2e4}, far from overflow.</li>
   *   <li>{@code y} is {@code hash(id, 2) / 2^31 * 10}, in {@code [-10, 10)} and independent of
   *       {@code x}, so {@code atan2(y, x)} sees all four quadrants and {@code hypot(x, y)} both
   *       signs of each operand.</li>
   *   <li>{@code p} is {@code 10^(hash(id, 3) / 2^31 * 6)}, in {@code [1e-6, 1e6)}, positive and
   *       spread evenly over twelve decades, so {@code log} and {@code log10} answer both signs
   *       and {@code pow(p, y)} stays inside {@code [1e-60, 1e60]}, finite and normal.</li>
   * </ul>
   */
  static String tableSql(int rows) {
    return tableSql("range(0, " + rows + ")");
  }

  /**
   * {@link #tableSql(int)} over the ids of {@code ids}, a relation with a {@code BIGINT} column
   * {@code id}.
   */
  static String tableSql(String ids) {
    return """
        select x, y, p,
               case when id %% 10 = 1 then null else x end as x_n,
               case when id %% 10 = 4 then null else y end as y_n,
               case when id %% 10 = 7 then null else p end as p_n
        from (select id,
                     hash(id, 1) / 2147483648.0d * 10.0d as x,
                     hash(id, 2) / 2147483648.0d * 10.0d as y,
                     power(10.0d, hash(id, 3) / 2147483648.0d * 6.0d) as p
              from %s)""".formatted(ids);
  }

  /**
   * A function: its title and its expression, with {@code X}, {@code Y} and {@code P} standing
   * for the columns of one null density, and how many of the columns it reads.
   */
  private record Form(String title, String expr, int columns) {}

  /** Every function, in the order the plan lists them (VARKA-345.md 4). */
  private static final List<Form> FORMS = List.of(
      new Form("DOUBLE sin(x)", "sin(X)", 1),
      new Form("DOUBLE tan(x)", "tan(X)", 1),
      new Form("DOUBLE cbrt(x)", "cbrt(X)", 1),
      new Form("DOUBLE exp(x)", "exp(X)", 1),
      new Form("DOUBLE log(p)", "log(P)", 1),
      new Form("DOUBLE log10(p)", "log10(P)", 1),
      new Form("DOUBLE pow(p, y)", "pow(P, Y)", 2),
      new Form("DOUBLE atan2(y, x)", "atan2(Y, X)", 2),
      new Form("DOUBLE sinh(x)", "sinh(X)", 1),
      new Form("DOUBLE hypot(x, y)", "hypot(X, Y)", 2),
      new Form("DOUBLE sec(x)", "sec(X)", 1),
      new Form("DOUBLE exp(x) * 2 + 1", "exp(X) * 2 + 1", 1));

  /** Every section, in the order they run: each null density, then each function. */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    for (boolean nullInputs : new boolean[] {false, true}) {
      String nulls = nullInputs ? "_n" : "";
      String density = ", " + (nullInputs ? "one row in ten null" : "no nulls");
      for (Form f : FORMS) {
        String expr = f.expr()
            .replace("X", "x" + nulls)
            .replace("Y", "y" + nulls)
            .replace("P", "p" + nulls);
        out.add(new Section(f.title() + density, "SELECT " + expr + " AS a FROM " + VIEW,
            Fusion.NONE, nullInputs, f.columns()));
      }
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-math-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-math-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView(VIEW);
        VarkaArrowSessions.cache(session, VIEW);
      }
      for (Section s : sections()) {
        requireBaseline(varka, s.title(), s.query(), s.fusion());
      }
      for (Section s : sections()) {
        section(s.title(), () -> {
          VarkaBenchmark benchmark = benchmark(
              s.title() + " over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS)
              .timed().build();
          benchmark.addCase("spark row path (varka off)", i -> noop(baseline.sql(s.query())));
          benchmark.addCase("varka on", i -> noop(varka.sql(s.query())));
          runCases(benchmark);
        });
      }
    } finally {
      baseline.stop();
      varka.stop();
    }
  }
}
