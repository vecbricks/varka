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
 * The baseline for the integral division family before Varka fuses it (VARKA-30): Spark's row
 * path over an Arrow-cached table of {@code INT}, {@code BIGINT} and {@code SMALLINT} columns, one
 * section per operator, mode, type and null density, so that the row reports what it changes
 * against a committed file.
 *
 * <p>The operators are {@code div}, {@code %}, {@code try_mod} and {@code pmod} by a column
 * divisor, and {@code %} by the literal 7, over {@code INT} and {@code BIGINT} dividends, each in
 * the modes it has: LEGACY and ANSI, and {@code try_mod}, the one TRY form of the three (Spark
 * builds no TRY {@code div} or {@code pmod}). Spark's {@code div} takes only {@code BIGINT}
 * operands, so an {@code INT} section of it times a {@code BIGINT} division of the widened
 * columns, though its filter is listed with the {@code INT} ones. A {@code SMALLINT} dividend
 * over a {@code SMALLINT} divisor times {@code %} and {@code try_mod}. The same operators run as
 * filters under ANSI ({@code div}, {@code %} and {@code pmod} by the column compared with 0,
 * {@code % 7} compared with 0), so a condition is timed beside each projection. The data never
 * divides by zero and never divides {@code Long.MIN_VALUE} by -1, so an ANSI section raises
 * nowhere and times the division and its zero test rather than an error. A control,
 * {@code i ^ j}, fuses today, so that a run whose Varka session is off fails the fuse check
 * below.
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
 * section but the control plans no Varka node and is {@code NONE}: no operator of the family
 * fuses, and a filter's inferred {@code IS NOT NULL} tests, the only conjuncts that would, are
 * left to Spark beside the division the row path keeps (VARKA-598), at either setting of the
 * session's refusal of 64-bit conditions (VARKA-503). The control is {@code WHOLE} and must also
 * run every batch as a kernel ({@link VarkaSizeLadder#varkaFused}). A row that fuses more of a
 * section sets its new class, so that its fusion cannot turn a baseline section into a fused one
 * unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaIntegralDivisionBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses these sections against the committed files lifts the
 * check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section that
 * fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaIntegralDivisionBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaIntegralDivisionBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaIntegralDivisionBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_integral_division";

  /**
   * A section: its title, its query, the SQL settings it runs under on both sessions, empty for
   * {@code try_mod}, whose answer does not depend on them, and how much of it the Varka session
   * fuses today, the {@link VarkaSqlBenchmarkBase.Fusion} class the run checks its plan against
   * before it times anything. The control {@code i ^ j} cannot raise either, but is pinned to
   * ANSI all the same, so that its plan does not follow the session's default.
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
   * The table: the dividends {@code i} ({@code INT}), {@code l} ({@code BIGINT}) and {@code s}
   * ({@code SMALLINT}), their divisors {@code j}, {@code l2} and {@code s2} of the same types,
   * and {@code i_n}, {@code l_n} and {@code s_n}, the dividends' values with one row in ten
   * null. Each column is a hash of the row's id, so any few thousand rows reach every region
   * below, as the queries suite checks.
   *
   * <ul>
   *   <li>{@code i} is {@code hash(id, 1)}, over the whole int range and both signs, and
   *       {@code s} a hash reduced to the whole {@code SMALLINT} range.</li>
   *   <li>{@code l} is {@code xxhash64(id, 3)} shifted right by {@code 1 + id % 47}, so its
   *       magnitudes spread over the bit lengths from about 16 to 62 and both signs: at most
   *       {@code 2^62} in magnitude, so no section divides {@code Long.MIN_VALUE} by -1.</li>
   *   <li>The divisors {@code j}, {@code l2} and {@code s2} are hashes shifted right by a
   *       per-row count up to their width, so they hold every bit length, both signs, and
   *       {@code 1} and {@code -1} among them; a zero is replaced by 1, so no divisor is
   *       zero.</li>
   * </ul>
   * So no section raises in any mode: ANSI raises nowhere and {@code try_mod} answers null only
   * where its dividend is null.
   */
  static String tableSql(int rows) {
    return """
        select i, j, l, l2, s, s2,
               case when id %% 10 = 1 then null else i end as i_n,
               case when id %% 10 = 1 then null else l end as l_n,
               case when id %% 10 = 1 then null else s end as s_n
        from (select id, i, l, s,
                     case when j0 = 0 then 1 else j0 end as j,
                     case when l20 = 0 then 1 else l20 end as l2,
                     cast(case when s20 = 0 then 1 else s20 end as smallint) as s2
              from (select id,
                           hash(id, 1) as i,
                           shiftright(hash(id, 2), cast(id %% 31 as int)) as j0,
                           shiftright(xxhash64(id, 3), cast(1 + id %% 47 as int)) as l,
                           shiftright(xxhash64(id, 4), cast(id %% 61 as int)) as l20,
                           cast(pmod(hash(id, 5), 65536) - 32768 as smallint) as s,
                           shiftright(pmod(hash(id, 6), 65536) - 32768, cast(id %% 15 as int))
                             as s20
                    from range(0, %d)))""".formatted(rows);
  }

  /**
   * Every section, in the order they run: each null density, then each type, then each
   * operator. {@code declinesLongConditions} is the session's refusal of 64-bit conditions
   * (VARKA-503), the 128-bit companion's host's default; no section's class depends on it today,
   * and a row that fuses a {@code BIGINT} filter reads it here.
   */
  static List<Section> sections(boolean declinesLongConditions) {
    List<Section> out = new ArrayList<>();
    Map<String, String> legacy = Map.of(SQLConf.ANSI_ENABLED().key(), "false");
    Map<String, String> ansi = Map.of(SQLConf.ANSI_ENABLED().key(), "true");
    Fusion intNullTests = Fusion.NONE;
    Fusion longNullTests = Fusion.NONE;
    for (String nulls : new String[] {"", "_n"}) {
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " AS a FROM " + VIEW;
      String[][] types = {{"INT", "i", "j"}, {"BIGINT", "l", "l2"}};
      for (String[] t : types) {
        String type = t[0] + " ";
        String a = t[1] + nulls;
        String b = t[2];
        String an = t[1];
        Fusion nullTests = "INT".equals(t[0]) ? intNullTests : longNullTests;
        out.add(new Section(type + an + " div " + b + ", LEGACY" + density,
            "SELECT " + a + " div " + b + from, legacy, Fusion.NONE));
        out.add(new Section(type + an + " div " + b + ", ANSI" + density,
            "SELECT " + a + " div " + b + from, ansi, Fusion.NONE));
        out.add(new Section(type + an + " % " + b + ", LEGACY" + density,
            "SELECT " + a + " % " + b + from, legacy, Fusion.NONE));
        out.add(new Section(type + an + " % " + b + ", ANSI" + density,
            "SELECT " + a + " % " + b + from, ansi, Fusion.NONE));
        out.add(new Section(type + "try_mod(" + an + ", " + b + ")" + density,
            "SELECT try_mod(" + a + ", " + b + ")" + from, Map.of(), Fusion.NONE));
        out.add(new Section(type + "pmod(" + an + ", " + b + "), LEGACY" + density,
            "SELECT pmod(" + a + ", " + b + ")" + from, legacy, Fusion.NONE));
        out.add(new Section(type + "pmod(" + an + ", " + b + "), ANSI" + density,
            "SELECT pmod(" + a + ", " + b + ")" + from, ansi, Fusion.NONE));
        out.add(new Section(type + an + " % 7, LEGACY" + density,
            "SELECT " + a + " % 7" + from, legacy, Fusion.NONE));
        out.add(new Section(type + an + " % 7, ANSI" + density,
            "SELECT " + a + " % 7" + from, ansi, Fusion.NONE));
        out.add(new Section(type + "filter " + an + " div " + b + " > 0, ANSI" + density,
            "SELECT " + a + from + " WHERE " + a + " div " + b + " > 0", ansi, nullTests));
        out.add(new Section(type + "filter " + an + " % " + b + " > 0, ANSI" + density,
            "SELECT " + a + from + " WHERE " + a + " % " + b + " > 0", ansi, nullTests));
        out.add(new Section(type + "filter pmod(" + an + ", " + b + ") > 0, ANSI" + density,
            "SELECT " + a + from + " WHERE pmod(" + a + ", " + b + ") > 0", ansi, nullTests));
        Fusion bySeven = nullTests;
        out.add(new Section(type + "filter " + an + " % 7 = 0, ANSI" + density,
            "SELECT " + a + from + " WHERE " + a + " % 7 = 0", ansi, bySeven));
      }
      String s = "s" + nulls;
      out.add(new Section("SMALLINT s % s2, LEGACY" + density,
          "SELECT " + s + " % s2" + from, legacy, Fusion.NONE));
      out.add(new Section("SMALLINT s % s2, ANSI" + density,
          "SELECT " + s + " % s2" + from, ansi, Fusion.NONE));
      out.add(new Section("SMALLINT try_mod(s, s2)" + density,
          "SELECT try_mod(" + s + ", s2)" + from, Map.of(), Fusion.NONE));
      out.add(new Section("SMALLINT filter s % s2 > 0, ANSI" + density,
          "SELECT " + s + from + " WHERE " + s + " % s2 > 0", ansi, intNullTests));
      out.add(new Section("INT i ^ j, control, fuses today" + density,
          "SELECT i" + nulls + " ^ j" + from, ansi, Fusion.WHOLE));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-integral-division-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-integral-division-baseline-on", true, false, "local[1]");
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
