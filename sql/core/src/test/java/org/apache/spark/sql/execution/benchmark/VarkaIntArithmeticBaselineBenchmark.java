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
import java.util.Map;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;
import org.apache.spark.sql.execution.VarkaSessionEmit;
import org.apache.spark.sql.internal.SQLConf;

/**
 * The baseline for the checked {@code INT} multiply before Varka fuses it (VARKA-95): Spark's row
 * path over an Arrow-cached table of {@code INT} columns and two {@code DATE}s, one section per
 * shape, mode and null density, so that the row reports what it changes against a committed file.
 *
 * <p>The modes are the ones whose answers differ on overflow: LEGACY wraps, ANSI raises
 * {@code ARITHMETIC_OVERFLOW}, and {@code try_multiply} answers null. The shapes are
 * {@code i * j} and {@code i * 3} under ANSI, {@code try_multiply(i, j)},
 * {@code datediff(d2, d) * i} under ANSI, the filter {@code i * j > 0} under ANSI, and the
 * control {@code i * j} under LEGACY, which fuses today as a wrapping multiply, so that a run
 * whose Varka session is off fails the fuse check below. The data never overflows, so every
 * section answers on every row and an ANSI section times arithmetic rather than an error.
 *
 * <p>Each section has two arms, named so the labels survive the row landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. A section listed as {@code NONE} plans no Varka node, so both its arms are the
 * row path and should tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@link Fusion} class, and fails
 * when the plan's class differs
 * ({@link VarkaSqlBenchmarkBase#requireBaseline(SparkSession, String, String, Fusion)}). Every
 * checked multiply declines today, its operands' bounds not ruling out overflow, so every section
 * but the control is {@code NONE}, the filter included: the rule that leaves a filter to Spark
 * when only its inferred {@code IS NOT NULL} tests would fuse (VARKA-598) leaves it whole. The
 * control is {@code WHOLE} and must also run every batch as a kernel
 * ({@link VarkaSizeLadder#varkaFused}). Every section is on the int lane, so the session's
 * refusal of a 64-bit kernel's masks at two lanes (VARKA-503) moves none of them. A row that
 * fuses more of a section sets its new class, so that its fusion cannot turn a baseline section
 * into a fused one unnoticed.
 *
 * <p>The remainder by a literal, {@code i % k}, the row's other half, is timed in row 30's
 * division baseline, which owns {@code %} ({@code VARKA-95.md} 4).
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaIntArithmeticBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses these sections against the committed files lifts the
 * check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section that
 * fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaIntArithmeticBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaIntArithmeticBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaIntArithmeticBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_int_arithmetic";

  /**
   * A section: its title, its query, the SQL settings it runs under on both sessions, empty for
   * {@code try_multiply}, whose answer does not depend on them, and how much of it the Varka
   * session fuses today, the {@link VarkaSqlBenchmarkBase.Fusion} class the run checks its plan
   * against before it times anything.
   */
  record Section(String title, String query, Map<String, String> conf, Fusion fusion) {

    /**
     * Whether the run checks every batch ran as a kernel: a {@code WHOLE} projection's, which
     * {@link VarkaSizeLadder#varkaFused} reads; a filter's node is no projection.
     */
    boolean checksKernels() {
      return fusion == Fusion.WHOLE && !query.contains(" WHERE ");
    }
  }

  /**
   * The table: the {@code INT}s {@code i} and {@code j} with no null, {@code i_n} the values of
   * {@code i} with one row in ten null, and the {@code DATE}s {@code d} and {@code d2}. Each
   * column is a hash of the row's id, so any few thousand rows reach every region below, as the
   * queries suite checks.
   *
   * <ul>
   *   <li>{@code i} and {@code j} are inside {@code [-40000, 40000]}, of both signs, so
   *       {@code |i * j| <= 1.6 * 10^9}, inside the int.</li>
   *   <li>{@code d} and {@code d2} are inside the 20000 days from 2000-01-01, so
   *       {@code datediff(d2, d)} is inside {@code [-19999, 19999]} and
   *       {@code |datediff(d2, d) * i| < 8 * 10^8}.</li>
   *   <li>{@code i_n} is null at one row in ten.</li>
   * </ul>
   * So no product overflows: ANSI raises nowhere and {@code try_multiply} answers null only where
   * an operand is null.
   */
  static String tableSql(int rows) {
    return """
        select i, j, d, d2,
               case when id %% 10 = 1 then null else i end as i_n
        from (select id,
                     pmod(hash(id, 1), 80001) - 40000 as i,
                     pmod(hash(id, 2), 80001) - 40000 as j,
                     date_add(DATE'2000-01-01', pmod(hash(id, 3), 20000)) as d,
                     date_add(DATE'2000-01-01', pmod(hash(id, 4), 20000)) as d2
              from range(0, %d))""".formatted(rows);
  }

  /**
   * Every section, in the order they run: each null density, then each shape. The parameter is
   * the session's two-lane refusal, which moves no section here, all being on the int lane; it is
   * taken so that the run reads the refusal as the other baselines do.
   */
  static List<Section> sections(boolean declinesLongConditions) {
    List<Section> out = new ArrayList<>();
    Map<String, String> legacy = Map.of(SQLConf.ANSI_ENABLED().key(), "false");
    Map<String, String> ansi = Map.of(SQLConf.ANSI_ENABLED().key(), "true");
    for (String nulls : new String[] {"", "_n"}) {
      String i = "i" + nulls;
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " AS a FROM " + VIEW;
      out.add(new Section("i * j, ANSI" + density,
          "SELECT " + i + " * j" + from, ansi, Fusion.NONE));
      out.add(new Section("i * 3, ANSI" + density,
          "SELECT " + i + " * 3" + from, ansi, Fusion.NONE));
      out.add(new Section("try_multiply(i, j)" + density,
          "SELECT try_multiply(" + i + ", j)" + from, Map.of(), Fusion.NONE));
      out.add(new Section("datediff(d2, d) * i, ANSI" + density,
          "SELECT datediff(d2, d) * " + i + from, ansi, Fusion.NONE));
      out.add(new Section("filter i * j > 0, ANSI" + density,
          "SELECT " + i + from + " WHERE " + i + " * j > 0", ansi, Fusion.NONE));
      out.add(new Section("i * j, LEGACY, control, fuses today" + density,
          "SELECT " + i + " * j" + from, legacy, Fusion.WHOLE));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-int-arithmetic-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-int-arithmetic-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView(VIEW);
        VarkaArrowSessions.cache(session, VIEW);
      }
      // The refusal as the Varka session resolves it, as VarkaBooleanOutputBenchmark reads it.
      List<Section> sections = sections(
          VarkaSessionEmit.of(varka.sessionState().conf()).declineTwoLaneConditions());
      // Each section fuses as its class lists it, and a WHOLE projection runs every batch as a
      // kernel.
      for (Section s : sections) {
        withConf(varka, s.conf(), () -> {
          requireBaseline(varka, s.title(), s.query(), s.fusion());
          if (s.checksKernels()) {
            VarkaSizeLadder.varkaFused(varka, s.query());
          }
        });
      }
      for (Section s : sections) {
        section(s.title(), () -> withConf(baseline, s.conf(), () -> withConf(varka, s.conf(),
            () -> {
              VarkaBenchmark benchmark = benchmark(
                  s.title() + " over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS)
                  .timed().build();
              benchmark.addCase("spark row path (varka off)", i -> noop(baseline.sql(s.query())));
              benchmark.addCase("varka on", i -> noop(varka.sql(s.query())));
              runCases(benchmark);
            })));
      }
    } finally {
      baseline.stop();
      varka.stop();
    }
  }
}
