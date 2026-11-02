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
import org.apache.spark.sql.internal.SQLConf;

/**
 * The baseline for the rounding functions before Varka fuses them (VARKA-344): Spark's row path
 * over an Arrow-cached table of {@code DOUBLE}, {@code INT} and {@code BIGINT} columns, one section
 * per function and null density, so that the row reports what it changes against a committed
 * file.
 *
 * <p>The functions are {@code rint(x)}; {@code floor(x)} and {@code ceil(x)} over a double, which
 * answer a {@code BIGINT}, under LEGACY and under ANSI; {@code floor(i)} over an {@code INT}, which
 * Spark plans as {@code FLOOR(CAST(i AS DOUBLE))}; {@code round(i, -2)} and {@code bround(i, -2)};
 * {@code truncate(l, -3)}; {@code round(l, -3)} under ANSI; {@code factorial(k)} over values in
 * {@code [-2, 22]}, null outside {@code [0, 20]}; and {@code width_bucket(x, -2.0E8, 2.0E8, 10)}
 * with literal bounds; {@code floor(xw)} and {@code ceil(xw)} under LEGACY over a wide double
 * column; and {@code floor(xwf)} and {@code ceil(xwf)} under ANSI over a finite wide one; each
 * with no nulls and with one row in ten null.
 *
 * <p>A section whose generated code depends on {@code spark.sql.ansi.enabled} pins the mode and
 * names it in its title, since Spark compiles a different expression per mode even where both
 * answer alike: {@code floor} and {@code ceil} over a double convert through {@code
 * MathUtils.doubleToLong} under ANSI and a plain cast under LEGACY, and {@code round}, {@code
 * bround} and {@code truncate} over an integral type end in {@code intValueExact} or {@code
 * longValueExact} under {@code MathUtils.withOverflowCode} under ANSI and in {@code intValue} or
 * {@code longValue} under LEGACY. {@code floor(i)}, {@code round(i, -2)}, {@code bround(i, -2)}
 * and {@code truncate(l, -3)} are timed in both modes, since row 344 builds a kernel for each
 * (VARKA-344.md 2.4 and 2.5), and {@code round(l, -3)} under ANSI, as the plan's section 4 names
 * it. Over this table no ANSI section raises: no {@code INT} overflows when rounded to a multiple
 * of 100, a truncation never increases a magnitude, every {@code INT} converts to a long exactly,
 * and {@code l} stays far from the long's edge. {@code rint}, {@code factorial} and
 * {@code width_bucket} compile the same code in both modes and run in the session's default one.
 *
 * <p>The sections over {@code xw} and {@code xwf} time {@code floor} and {@code ceil} on wide
 * data: values past {@code 2^51} at every batch, where row 344b's bounded AVX2 arm declines, so
 * that the arm's fallback is measured in both modes before 344b picks its default (added by the
 * owner's word, 1 November 2026). {@code rint} has no bounded arm, so it is timed over {@code x}
 * only. The sections over {@code xw} run under LEGACY only, since ANSI's {@code floor} and
 * {@code ceil} raise on an infinity or a value past the long range and would end the run. Under
 * LEGACY Spark compiles them as {@code (long) java.lang.Math.floor(c)}, the Java narrowing
 * conversion, so NaN answers 0, {@code +Infinity} and every value from {@code 2^63} up answer
 * {@code Long.MAX_VALUE}, and {@code -Infinity} and every value below {@code -2^63} answer
 * {@code Long.MIN_VALUE}. The sections over {@code xwf} run under ANSI, Spark 4's default and the
 * mode of row 344's prediction 3, over values that are all inside the long range or NaN, so none
 * raises: {@code MathUtils.doubleToLong} raises only on a value that is not NaN and lies below
 * {@code -2^63} or from {@code 2^63} up, and lets NaN through to {@code (long) NaN}, which is 0.
 *
 * <p>Each section has two arms, named so the labels survive the row landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Today no section plans a Varka node, so both arms are the row path and should
 * tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@code fuses} flag, the list of
 * the sections a row has fused, and fails when an unlisted section fuses or a listed one does not;
 * the results file notes each section that fuses. No section is listed. A row that fuses a
 * section lists it, so that its fusion cannot turn a baseline section into a fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaRoundingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which fuses unlisted sections against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses:
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaRoundingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-Dvarka.baseline.fuseCheck=false
 *   gh workflow run benchmark.yml -f class=VarkaRoundingBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"
 * </pre>
 */
public class VarkaRoundingBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_rounding";

  /** The literal bounds of the {@code width_bucket} section, inside {@code x}'s range. */
  static final String BUCKET_BOUNDS = "-2.0E8D, 2.0E8D, 10";

  /** Which rows of a section's answer are null, beside the rows where its input is null. */
  enum NullRule {
    /** Only the rows where the input is null. */
    INPUT,
    /** Also the NaN rows of {@code x}, as {@code width_bucket} answers. */
    INPUT_OR_NAN,
    /** Also the rows of {@code k} outside {@code [0, 20]}, as {@code factorial} answers. */
    INPUT_OR_OUTSIDE_FACTORIAL
  }

  /**
   * A section: its title, its query, the SQL settings it runs under on both sessions, empty for a
   * function whose generated code does not depend on them, and whether the Varka session fuses
   * it, which the run checks before it times anything. The rest is what the queries suite checks
   * its answer against: the result type's SQL name, whether it reads the columns with one row in
   * ten null and which other rows answer null. The type is a name rather than a {@code DataType},
   * since {@code dev/varka_new_lane.py} reads a benchmark naming a lane's {@code DataTypes} member
   * as one that benchmarks the lane, which this row-path baseline does not.
   */
  record Section(String title, String query, Map<String, String> conf, boolean fuses,
      String resultType, boolean nullInputs, NullRule nulls) {}

  /**
   * The table: {@code x} a {@code DOUBLE}, {@code i} and {@code k} {@code INT}s, {@code l} a
   * {@code BIGINT}, and {@code xw} and {@code xwf} {@code DOUBLE}s with wide values, with no null,
   * and {@code x_n} to {@code xwf_n} the same values with one row in ten null, at a different row
   * in each column. Each column is a hash of the row's id, so any
   * few thousand rows reach every region below, as the queries suite checks.
   *
   * <ul>
   *   <li>{@code x} is {@code hash(id, 1) / 7.0}, within {@code +-2^31 / 7}, so its fractions
   *       keep many bits and its values lie far inside the long range, where ANSI's
   *       {@code floor} and {@code ceil} never raise, and inside {@code 2^51}, past which the
   *       bounded AVX2 form of row 344 declines a batch: a share past it, however small, would
   *       decline every batch of that arm and leave nothing to measure. One row in eight is
   *       instead a tie, {@code hash(id, 1) div 8} plus {@code 0.5}, inside {@code 2^28 + 0.5}
   *       and so inside the same bound, where {@code rint} picks the even
   *       neighbour; one row in eight is {@code 0.0} and another {@code -0.0}, whose sign
   *       {@code rint} keeps; and one row in a thousand is NaN, which {@code floor} and
   *       {@code ceil} answer 0 for in both modes and {@code width_bucket} null, none of them
   *       among {@code x_n}'s nulls. About three rows in four lie inside the bucket bounds, and
   *       the rest below or above them. There is no infinity, on which ANSI's {@code floor}
   *       would raise and end the run: the wide values are {@code xw}'s.</li>
   *   <li>{@code i} is {@code hash(id)}, over the whole int range.</li>
   *   <li>{@code l} is {@code xxhash64(id)} shifted right by {@code 9 + id % 55}, so its
   *       magnitudes spread evenly over the bit lengths up to 54: about one row in forty past
   *       {@code 2^52}, where the long lane's division by a constant stops being exact, and none
   *       past {@code 2^54}, so none near the long's edge, where ANSI's {@code round(l, -3)}
   *       would raise, and none past the {@code 2^55} guard row 344 plans for {@code k = 3}.</li>
   *   <li>{@code k} is {@code pmod(hash(id, 4), 25) - 2}, in {@code [-2, 22]}, so about four
   *       rows in twenty-five are outside {@code [0, 20]}, where {@code factorial} answers
   *       null.</li>
   *   <li>{@code xw} mixes ordinary and wide doubles. One row in eight is {@code 0.0} and
   *       another {@code -0.0}; one row in two is ordinary, {@code hash(id, 5) / 7.0}, inside
   *       {@code 2^31 / 7} with a fraction; and one row in four is wide, {@code hash(id, 5) / 7.0}
   *       scaled by a power of two: at one row in eight by {@code 2^(24 + id % 11)}, within
   *       {@code +-2^62.2} and so inside the long range, mostly past {@code 2^51} and many past
   *       {@code 2^52}, where the double is whole and the conversion to {@code BIGINT} is exact;
   *       at the other by {@code 2^(36 + pmod(hash(id, 6), 960))}, from about {@code 2^64} up to
   *       {@code 2^1023.2}, near {@code Double.MAX_VALUE} and never past it, where LEGACY's
   *       {@code floor} and {@code ceil} saturate at {@code Long.MAX_VALUE} or
   *       {@code Long.MIN_VALUE}. One row in a thousand each is NaN, {@code +Infinity} and
   *       {@code -Infinity}, taking the place of the value at those rows, none among
   *       {@code xw_n}'s nulls. A wide row comes at every fourth id, so every run of four rows
   *       holds one and a 4096-row batch about a thousand: every batch declines under 344b's
   *       bounded arm, against none over {@code x}, so the two columns time its two ends. A
   *       smaller share would not change that, since one wide row declines a batch, until it
   *       were so sparse as to leave most batches whole and the section a measure of the share
   *       chosen.</li>
   *   <li>{@code xwf} is {@code xw} without the values ANSI raises on: one row in eight is
   *       {@code 0.0} and another {@code -0.0}; one row in two is ordinary,
   *       {@code hash(id, 5) / 7.0}; one row in four is wide, {@code hash(id, 5) / 7.0} scaled by
   *       {@code 2^(24 + id % 11)} as {@code xw}'s first wide rows are, within {@code +-2^62.2}
   *       (a hash is below {@code 2^31} in magnitude, over 7, times at most {@code 2^34}), which
   *       leaves a factor of about 1.7 to {@code 2^63}, mostly past {@code 2^51} and many past
   *       {@code 2^52}; and one row in a thousand is NaN, none among {@code xwf_n}'s nulls. It
   *       holds no infinity and nothing past the long range. Its wide share is {@code xw}'s, at
   *       the same ids, so every 4096-row batch holds about a thousand wide rows and declines under
   *       344b's bounded arm, for the reason {@code xw}'s does, without the saturating values.</li>
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
        select x, i, l, k, xw, xwf,
               case when id %% 10 = 5 then null else x end as x_n,
               case when id %% 10 = 3 then null else i end as i_n,
               case when id %% 10 = 1 then null else l end as l_n,
               case when id %% 10 = 7 then null else k end as k_n,
               case when id %% 10 = 9 then null else xw end as xw_n,
               case when id %% 10 = 6 then null else xwf end as xwf_n
        from (select id,
                     case when id %% 1000 = 11 then double('NaN')
                          when id %% 8 = 0 then 0.0d
                          when id %% 8 = 4 then -0.0d
                          when id %% 8 = 2 then cast(hash(id, 1) div 8 as double) + 0.5d
                          else hash(id, 1) / 7.0d end as x,
                     hash(id) as i,
                     shiftright(xxhash64(id), cast(9 + id %% 55 as int)) as l,
                     pmod(hash(id, 4), 25) - 2 as k,
                     case when id %% 1000 = 11 then double('NaN')
                          when id %% 1000 = 333 then double('Infinity')
                          when id %% 1000 = 555 then double('-Infinity')
                          when id %% 8 = 0 then 0.0d
                          when id %% 8 = 4 then -0.0d
                          when id %% 8 = 3 then hash(id, 5) / 7.0d * power(2.0d, 24 + id %% 11)
                          when id %% 8 = 7 then hash(id, 5) / 7.0d
                               * power(2.0d, 36 + pmod(hash(id, 6), 960))
                          else hash(id, 5) / 7.0d end as xw,
                     case when id %% 1000 = 11 then double('NaN')
                          when id %% 8 = 0 then 0.0d
                          when id %% 8 = 4 then -0.0d
                          when id %% 8 in (3, 7) then hash(id, 5) / 7.0d
                               * power(2.0d, 24 + id %% 11)
                          else hash(id, 5) / 7.0d end as xwf
              from %s)""".formatted(ids);
  }

  /**
   * A function over one column: its title, its expression, its result type. The list it is in
   * sets the modes it is timed in.
   */
  private record Form(String title, String expr, String resultType) {}

  /**
   * Every section, in the order they run: each null density, then each function, a function
   * timed in both modes LEGACY first, then the wide columns, {@code xw} under LEGACY and
   * {@code xwf} under ANSI.
   */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    Map<String, String> legacy = Map.of(SQLConf.ANSI_ENABLED().key(), "false");
    Map<String, String> ansi = Map.of(SQLConf.ANSI_ENABLED().key(), "true");
    for (boolean nullInputs : new boolean[] {false, true}) {
      String nulls = nullInputs ? "_n" : "";
      String x = "x" + nulls;
      String i = "i" + nulls;
      String l = "l" + nulls;
      String k = "k" + nulls;
      String xw = "xw" + nulls;
      String xwf = "xwf" + nulls;
      String density = ", " + (nullInputs ? "one row in ten null" : "no nulls");
      String from = " AS a FROM " + VIEW;
      out.add(new Section("DOUBLE rint(x)" + density, "SELECT rint(" + x + ")" + from,
          Map.of(), false, "DOUBLE", nullInputs, NullRule.INPUT));
      for (Form f : List.of(
          new Form("DOUBLE floor(x)", "floor(" + x + ")", "BIGINT"),
          new Form("DOUBLE ceil(x)", "ceil(" + x + ")", "BIGINT"),
          new Form("INT floor(i)", "floor(" + i + ")", "BIGINT"),
          new Form("INT round(i, -2)", "round(" + i + ", -2)", "INT"),
          new Form("INT bround(i, -2)", "bround(" + i + ", -2)", "INT"),
          new Form("BIGINT truncate(l, -3)", "truncate(" + l + ", -3)", "BIGINT"))) {
        for (String mode : new String[] {"LEGACY", "ANSI"}) {
          out.add(new Section(f.title() + ", " + mode + density, "SELECT " + f.expr() + from,
              mode.equals("ANSI") ? ansi : legacy, false, f.resultType(), nullInputs,
              NullRule.INPUT));
        }
      }
      out.add(new Section("BIGINT round(l, -3), ANSI" + density,
          "SELECT round(" + l + ", -3)" + from,
          ansi, false, "BIGINT", nullInputs, NullRule.INPUT));
      out.add(new Section("INT factorial(k)" + density, "SELECT factorial(" + k + ")" + from,
          Map.of(), false, "BIGINT", nullInputs, NullRule.INPUT_OR_OUTSIDE_FACTORIAL));
      out.add(new Section("DOUBLE width_bucket(x, lo, hi, 10)" + density,
          "SELECT width_bucket(" + x + ", " + BUCKET_BOUNDS + ")" + from,
          Map.of(), false, "BIGINT", nullInputs, NullRule.INPUT_OR_NAN));
      for (Form f : List.of(
          new Form("DOUBLE floor(xw)", "floor(" + xw + ")", "BIGINT"),
          new Form("DOUBLE ceil(xw)", "ceil(" + xw + ")", "BIGINT"))) {
        out.add(new Section(f.title() + ", LEGACY" + density, "SELECT " + f.expr() + from,
            legacy, false, f.resultType(), nullInputs, NullRule.INPUT));
      }
      for (Form f : List.of(
          new Form("DOUBLE floor(xwf)", "floor(" + xwf + ")", "BIGINT"),
          new Form("DOUBLE ceil(xwf)", "ceil(" + xwf + ")", "BIGINT"))) {
        out.add(new Section(f.title() + ", ANSI" + density, "SELECT " + f.expr() + from,
            ansi, false, f.resultType(), nullInputs, NullRule.INPUT));
      }
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-rounding-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-rounding-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView(VIEW);
        VarkaArrowSessions.cache(session, VIEW);
      }
      for (Section s : sections()) {
        withConf(varka, s.conf(), () -> requireBaseline(varka, s.title(), s.query(), s.fuses()));
      }
      for (Section s : sections()) {
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
