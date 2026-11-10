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
 * The baseline for {@code BIGINT} arithmetic before Varka fuses it (VARKA-104): Spark's row path
 * over an Arrow-cached table of {@code BIGINT} columns and one {@code INT}, one section per
 * operation, mode and null density, so that the row reports what it changes against a committed
 * file.
 *
 * <p>The modes are the ones whose answers differ from each other's on overflow: LEGACY wraps,
 * ANSI raises, and {@code try_add}, {@code try_subtract} and {@code try_multiply} answer null.
 * The operations over a {@code BIGINT} column are {@code l + 1}, {@code l + l2},
 * {@code l - l2}, {@code l * 3}, {@code l * m}, {@code -l} and {@code abs(l)}, each under LEGACY
 * and ANSI, and the three between two columns also as their {@code try_} form; the negation and
 * {@code abs} have none. Over a bounded long leaf, the widened int, {@code CAST(i AS BIGINT) + 1}
 * and {@code CAST(i AS BIGINT) * 1000} run in all three modes: the only shape whose check the
 * lattice's long half takes off (VARKA-104.md 8 step 2), timed beside its LEGACY twin, the
 * unchecked reference. The data never overflows, so every section answers on every row and an
 * ANSI section times arithmetic rather than an error; what the modes cost is the check each one
 * makes on the way. One filter, {@code l + l2 > 0}, times the arithmetic as a condition; and a
 * control, {@code l ^ l2}, fuses today, so that a run whose Varka session is off fails the fuse
 * check below.
 *
 * <p>Each section has two arms, named so the labels survive the row landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. A section listed as {@code NONE} plans no Varka node, so both its arms are the
 * row path and should tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@link Fusion} class, and fails
 * when the plan's class differs
 * ({@link VarkaSqlBenchmarkBase#requireBaseline(SparkSession, String, String, Fusion)}). With
 * the arithmetic fused (VARKA-104), every section is {@code WHOLE} but a checked product over a
 * column ({@code l * 3} and {@code l * m} under ANSI, {@code try_multiply(l, m)}), which declines
 * and is {@code NONE}: the filter, every LEGACY form, the checked sums, differences, negation and
 * {@code abs}, and the widened int's six, whose checks the range lattice takes off. Where the
 * session refuses a 64-bit kernel's masks at two lanes (VARKA-503), as the 128-bit companion's
 * host does by default, the filter and every form that keeps its overflow check are {@code NONE};
 * the wrapping forms and the widened int's, which have no check left, stay {@code WHOLE}. A
 * {@code WHOLE} projection must also run every batch as a kernel
 * ({@link VarkaSizeLadder#varkaFused}); the filter's node is no projection. A row that fuses
 * more of a section sets its new class, so that its fusion cannot turn a baseline section into a
 * fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaBigintArithmeticBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses these sections against the committed files lifts the
 * check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section that
 * fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaBigintArithmeticBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaBigintArithmeticBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaBigintArithmeticBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_bigint_arithmetic";

  /**
   * A section: its title, its query, the SQL settings it runs under on both sessions, empty for
   * a {@code try_} form, whose answer does not depend on them, and how much of it the Varka
   * session fuses today, the {@link VarkaSqlBenchmarkBase.Fusion} class the run checks its plan
   * against before it times anything. The control {@code l ^ l2} cannot overflow either, but is
   * pinned to ANSI all the same, so that its plan does not follow the session's default.
   */
  record Section(String title, String query, Map<String, String> conf, Fusion fusion) {

    /**
     * Whether the run checks every batch ran as a kernel: a {@code WHOLE} projection's, which
     * {@link VarkaSizeLadder#varkaFused} reads; the filter's node is no projection.
     */
    boolean checksKernels() {
      return fusion == Fusion.WHOLE && !query.contains(" WHERE ");
    }
  }

  /**
   * The table: {@code l}, {@code l2} and {@code m} {@code BIGINT}s with no null, {@code l_n}
   * the values of {@code l} with one row in ten null, and the {@code INT}s {@code i} and
   * {@code i_n}. Each column is a hash of the row's id, so
   * any few thousand rows reach every region below, as the queries suite checks.
   *
   * <ul>
   *   <li>{@code l} is {@code xxhash64(id)} shifted right by {@code 14 + id % 50}, so its
   *       magnitudes spread over every bit length up to 49 and both signs: below
   *       {@code 2^49} in magnitude, and past the int range at about a third of the rows.</li>
   *   <li>{@code l2} is the same form over {@code xxhash64(id, 1)} and {@code id % 47}, so the
   *       two columns' bit lengths are not in step. A sum or a difference of the two stays below
   *       {@code 2^50}, and {@code l + 1}, {@code -l} and {@code abs(l)} are far from the
   *       long's edges.</li>
   *   <li>{@code m} is inside {@code [-3000, 3000]}, a factor whose product with {@code l} is
   *       below {@code 2^61} in magnitude, as is {@code l * 3}.</li>
   *   <li>{@code i} is an {@code INT}, {@code hash(id, 3)}, over the whole int range, and
   *       {@code i_n} its values with the same rows null as {@code l_n}. Widened to a
   *       {@code BIGINT} it is a bounded long leaf: {@code CAST(i AS BIGINT) + 1} and
   *       {@code CAST(i AS BIGINT) * 1000}, below {@code 2^41}, cannot overflow whatever the
   *       data.</li>
   * </ul>
   * So no section overflows in any mode: ANSI raises nowhere and the {@code try_} forms answer
   * null only where an operand is null.
   */
  static String tableSql(int rows) {
    return """
        select l, l2, m,
               case when id %% 10 = 1 then null else l end as l_n,
               i,
               case when id %% 10 = 1 then null else i end as i_n
        from (select id,
                     shiftright(xxhash64(id), cast(14 + id %% 50 as int)) as l,
                     shiftright(xxhash64(id, 1), cast(14 + id %% 47 as int)) as l2,
                     cast(pmod(hash(id, 2), 6001) - 3000 as bigint) as m,
                     hash(id, 3) as i
              from range(0, %d))""".formatted(rows);
  }

  /**
   * Every section, in the order they run: each null density, then each operation. The filter is
   * a condition in a kernel driven at 64 bits, which a session that refuses those
   * ({@code declinesLongConditions}, VARKA-503) leaves to the row path whole: the 128-bit
   * companion's host by default. The same session leaves there every form that keeps its
   * overflow check.
   */
  static List<Section> sections(boolean declinesLongConditions) {
    List<Section> out = new ArrayList<>();
    Map<String, String> legacy = Map.of(SQLConf.ANSI_ENABLED().key(), "false");
    Map<String, String> ansi = Map.of(SQLConf.ANSI_ENABLED().key(), "true");
    // A form that keeps its overflow check - one that raises or nulls on overflow - declines
    // where the filter does, for the same long-lane mask (VARKA-104); a wrapping one, and one
    // whose check the range lattice took off, fuses on every host.
    Fusion checked = declinesLongConditions ? Fusion.NONE : Fusion.WHOLE;
    for (String nulls : new String[] {"", "_n"}) {
      String l = "l" + nulls;
      String wide = "CAST(i" + nulls + " AS BIGINT)";
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " AS a FROM " + VIEW;
      out.add(new Section("l + 1, LEGACY" + density,
          "SELECT " + l + " + 1" + from, legacy, Fusion.WHOLE));
      out.add(new Section("l + 1, ANSI" + density,
          "SELECT " + l + " + 1" + from, ansi, checked));
      out.add(new Section("l + l2, LEGACY" + density,
          "SELECT " + l + " + l2" + from, legacy, Fusion.WHOLE));
      out.add(new Section("l + l2, ANSI" + density,
          "SELECT " + l + " + l2" + from, ansi, checked));
      out.add(new Section("try_add(l, l2)" + density,
          "SELECT try_add(" + l + ", l2)" + from, Map.of(), checked));
      out.add(new Section("l - l2, LEGACY" + density,
          "SELECT " + l + " - l2" + from, legacy, Fusion.WHOLE));
      out.add(new Section("l - l2, ANSI" + density,
          "SELECT " + l + " - l2" + from, ansi, checked));
      out.add(new Section("try_subtract(l, l2)" + density,
          "SELECT try_subtract(" + l + ", l2)" + from, Map.of(), checked));
      out.add(new Section("l * 3, LEGACY" + density,
          "SELECT " + l + " * 3" + from, legacy, Fusion.WHOLE));
      out.add(new Section("l * 3, ANSI" + density,
          "SELECT " + l + " * 3" + from, ansi, Fusion.NONE));
      out.add(new Section("l * m, LEGACY" + density,
          "SELECT " + l + " * m" + from, legacy, Fusion.WHOLE));
      out.add(new Section("l * m, ANSI" + density,
          "SELECT " + l + " * m" + from, ansi, Fusion.NONE));
      out.add(new Section("try_multiply(l, m)" + density,
          "SELECT try_multiply(" + l + ", m)" + from, Map.of(), Fusion.NONE));
      out.add(new Section("-l, LEGACY" + density,
          "SELECT -" + l + from, legacy, Fusion.WHOLE));
      out.add(new Section("-l, ANSI" + density,
          "SELECT -" + l + from, ansi, checked));
      out.add(new Section("abs(l), LEGACY" + density,
          "SELECT abs(" + l + ")" + from, legacy, Fusion.WHOLE));
      out.add(new Section("abs(l), ANSI" + density,
          "SELECT abs(" + l + ")" + from, ansi, checked));
      // A bounded long leaf, the widened int: the one shape whose check the lattice's long half
      // (VARKA-104.md 8 step 2) removes, with its LEGACY twin as the unchecked reference.
      out.add(new Section("CAST(i AS BIGINT) + 1, LEGACY" + density,
          "SELECT " + wide + " + 1" + from, legacy, Fusion.WHOLE));
      out.add(new Section("CAST(i AS BIGINT) + 1, ANSI" + density,
          "SELECT " + wide + " + 1" + from, ansi, Fusion.WHOLE));
      out.add(new Section("try_add(CAST(i AS BIGINT), 1)" + density,
          "SELECT try_add(" + wide + ", 1)" + from, Map.of(), Fusion.WHOLE));
      out.add(new Section("CAST(i AS BIGINT) * 1000, LEGACY" + density,
          "SELECT " + wide + " * 1000" + from, legacy, Fusion.WHOLE));
      out.add(new Section("CAST(i AS BIGINT) * 1000, ANSI" + density,
          "SELECT " + wide + " * 1000" + from, ansi, Fusion.WHOLE));
      out.add(new Section("try_multiply(CAST(i AS BIGINT), 1000)" + density,
          "SELECT try_multiply(" + wide + ", 1000)" + from, Map.of(), Fusion.WHOLE));
      out.add(new Section("filter l + l2 > 0, ANSI" + density,
          "SELECT " + l + from + " WHERE " + l + " + l2 > 0", ansi,
          declinesLongConditions ? Fusion.NONE : Fusion.WHOLE));
      out.add(new Section("l ^ l2, control, fuses today" + density,
          "SELECT " + l + " ^ l2" + from, ansi, Fusion.WHOLE));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-bigint-arithmetic-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-bigint-arithmetic-baseline-on", true, false, "local[1]");
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
