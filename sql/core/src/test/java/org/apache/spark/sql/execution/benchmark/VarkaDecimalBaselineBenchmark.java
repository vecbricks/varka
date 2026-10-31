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
 * The baseline for {@code DECIMAL} before Varka has a lane for it (VARKA-377): Spark's row path
 * over an Arrow-cached table, in four sections per precision and null density, so that the
 * decimal lanes' rows report what they change against a committed file.
 *
 * <p>The three precisions are {@code DECIMAL(9, 2)}, {@code DECIMAL(18, 4)} and
 * {@code DECIMAL(38, 10)}: the widest that fits an int, the widest that fits a long, and one that
 * needs 128 bits, so the 18-to-19 boundary is inside the baseline. Spark's generated code keeps a
 * decimal of precision 18 or less as an unscaled long and a wider one as a {@code BigDecimal},
 * but reading the cached table through {@code ArrowColumnVector} builds a {@code BigDecimal} for
 * every value at every precision, and a {@code Decimal} made from one keeps it rather than the
 * long, so compares and arithmetic take the {@code BigDecimal} path at every precision too. A gap
 * between the precisions here is the cost of larger {@code BigDecimal}s, not of a long against
 * 128 bits; the boundary is in the baseline for the lanes, which do change representation there.
 *
 * <p>The sections are a comparison ({@code x < y} as a filter), an arithmetic chain of three nodes
 * ({@code (x + y) * z - x}, whose result type Spark widens at each node, so that even at
 * {@code DECIMAL(9, 2)} its product is {@code DECIMAL(20, 2)}, past a long), a cast and a choice
 * ({@code CASE WHEN x < y THEN x ELSE y END}), each with no nulls and with one row in ten null.
 * The cast goes to the next precision up: {@code DECIMAL(9, 2)} to {@code DECIMAL(18, 4)} and
 * {@code DECIMAL(18, 4)} to {@code DECIMAL(38, 10)}. {@code DECIMAL(38, 10)} has no wider decimal,
 * so it is cast down to {@code DECIMAL(18, 4)}, which crosses the same boundary the other way
 * and rounds away six digits of scale by {@code ROUND_HALF_UP}, as Spark's cast does. The three
 * casts are the baselines of the rows that build them: the 64-bit cast (row 350), the widening
 * into the 128-bit pair (row 360) and the narrowing back to a long (row 364). No value overflows
 * its target, since an overflow raises under ANSI and is null under LEGACY, and either would stop
 * the timed run or fail the queries suite's check that no section over the null-free columns
 * answers a null.
 *
 * <p>Four more sections time the two {@code TIME} producers whose result is a decimal of
 * precision 18 or less, the first output a kernel would write into Arrow's sixteen-byte slots
 * (VARKA-157): {@code time_to_seconds(t)}, a {@code DECIMAL(14, 6)}, and {@code extract(SECOND
 * FROM t)}, a {@code DECIMAL(8, 6)} at {@code TIME(6)}, each as a projection over a
 * {@code TIME(6)} column with no nulls and with one row in ten null. Spark computes the first
 * through a {@code Decimal} division and the second through a {@code Double} turned into a
 * {@code BigDecimal}, per row. The times spread over the whole day and fill all six digits of the
 * fraction, so the second's fraction is never zero by construction. {@code TIME} is behind a flag
 * whose default is whether the JVM is a test's, which a benchmark's is not, so both sessions turn
 * it on.
 *
 * <p>Each section has two arms, named so the labels survive the lanes landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it. Today both are the row path, so they should tie.
 *
 * <p>A baseline is only one while nothing fuses: before any section is timed, the run checks
 * each section's plan on the Varka session against the section's {@code fuses} flag, the list of
 * the sections a row has fused, and fails when an unlisted section fuses or a listed one does not.
 * No section is listed today. A row that starts to fuse a section lists it, so that its fusion
 * cannot turn a baseline section into a fused one unnoticed.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaDecimalBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 */
public class VarkaDecimalBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** A section: its title, its query, and whether the Varka session fuses it today. */
  record Section(String title, String query, boolean fuses) {}

  /**
   * A precision the class measures: the column type, the suffix of its columns, the largest
   * integer part its values take, and the type its cast section converts to.
   */
  record Precision(int precision, int scale, String suffix, long maxIntegerPart, String castTo) {

    String type() {
      return "DECIMAL(" + precision + ", " + scale + ")";
    }
  }

  /**
   * The integer parts are wide but leave every section in range. At {@code DECIMAL(9, 2)} they
   * reach half the range of the type's seven integer digits. At the two wider precisions they
   * reach five times ten to the twelfth, bound by the cast of {@code DECIMAL(38, 10)} to
   * {@code DECIMAL(18, 4)}, whose 14 integer digits must hold them. At {@code DECIMAL(18, 4)} that
   * is an unscaled value of seventeen digits, one short of the precision, and at
   * {@code DECIMAL(38, 10)} an unscaled value above a long's range, so the high word of its
   * 128-bit slot holds real bits rather than the sign. The chain's product then stays below 10
   * to the 26th, well inside the 30 and 32 integer digits Spark gives it at the two wider
   * precisions.
   */
  static final List<Precision> PRECISIONS = List.of(
      new Precision(9, 2, "9", 5_000_000L, "DECIMAL(18, 4)"),
      new Precision(18, 4, "18", 5_000_000_000_000L, "DECIMAL(38, 10)"),
      new Precision(38, 10, "38", 5_000_000_000_000L, "DECIMAL(18, 4)"));

  /** The precision of the {@code TIME} column the two time sections read. */
  static final int TIME_PRECISION = 6;

  /** The microseconds in a day, the range the {@code TIME} column's values spread over. */
  static final long MICROS_PER_DAY = 86_400_000_000L;

  /** The three operands of every section, in the order a shape uses them. */
  static final List<String> ROLES = List.of("x", "y", "z");

  /**
   * One column's value: an integer part spread evenly over {@code -max} to {@code max}, plus a
   * fraction filling every digit of the scale, both from a hash of the row id, so that each
   * column's values are independent of the others' and the comparisons select about half the rows.
   * The sum is computed exactly, as decimals no wider than 38, and then cast to the column type.
   */
  static String valueSql(Precision p, int seed) {
    String unit = "0." + "0".repeat(p.scale() - 1) + "1";
    long fractions = 1;
    for (int i = 0; i < p.scale(); i++) {
      fractions *= 10;
    }
    return "cast(cast(pmod(xxhash64(id, " + seed + "), " + (2 * p.maxIntegerPart() + 1) + ") - "
        + p.maxIntegerPart() + " as decimal(18, 0)) + cast(pmod(xxhash64(id, " + (seed + 100)
        + "), " + fractions + ") as decimal(18, 0)) * " + unit + " as " + p.type() + ")";
  }

  /**
   * The {@code TIME(6)} column's value: microseconds of the day from a hash of the row id, so the
   * values cover the whole day and every digit of the fraction.
   */
  static String timeSql(int seed) {
    return "time_from_micros(pmod(xxhash64(id, " + seed + "), " + MICROS_PER_DAY + "))";
  }

  /**
   * The table: for each precision, {@code x}, {@code y} and {@code z} with the precision as their
   * suffix ({@code x9}, {@code y18}, ...) and no null, and the same values with one row in ten
   * null ({@code x9n}, ...), at a different row in each column; then {@code t}, a
   * {@code TIME(6)}, and {@code tn}, the same values with one row in ten null.
   */
  static String tableSql(int rows) {
    List<String> values = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    int seed = 0;
    for (Precision p : PRECISIONS) {
      int nullRow = 1;
      for (String role : ROLES) {
        String name = role + p.suffix();
        values.add(valueSql(p, seed++) + " as " + name);
        columns.add(name);
        columns.add("case when id % 10 = " + nullRow + " then null else " + name + " end as "
            + name + "n");
        nullRow += 3;
      }
    }
    values.add(timeSql(seed) + " as t");
    columns.add("t");
    columns.add("case when id % 10 = 2 then null else t end as tn");
    return "select " + String.join(", ", columns) + " from (select id, "
        + String.join(", ", values) + " from range(0, " + rows + "))";
  }

  /**
   * Every section, in the order they run: each precision, then each null density, then each
   * shape; then the two time producers at each null density.
   */
  static List<Section> sections() {
    List<Section> out = new ArrayList<>();
    for (Precision p : PRECISIONS) {
      for (String nulls : new String[] {"", "n"}) {
        String x = "x" + p.suffix() + nulls;
        String y = "y" + p.suffix() + nulls;
        String z = "z" + p.suffix() + nulls;
        String density = nulls.isEmpty() ? "no nulls" : "one row in ten null";
        String type = p.type();
        out.add(new Section(type + " comparison, " + density,
            "SELECT " + x + " FROM varka_decimal WHERE " + x + " < " + y, false));
        out.add(new Section(type + " arithmetic chain, " + density,
            "SELECT (" + x + " + " + y + ") * " + z + " - " + x + " AS a FROM varka_decimal",
            false));
        out.add(new Section(type + " cast to " + p.castTo() + ", " + density,
            "SELECT CAST(" + x + " AS " + p.castTo() + ") AS a FROM varka_decimal", false));
        out.add(new Section(type + " choice, " + density,
            "SELECT CASE WHEN " + x + " < " + y + " THEN " + x + " ELSE " + y
                + " END AS a FROM varka_decimal", false));
      }
    }
    for (String nulls : new String[] {"", "n"}) {
      String t = "t" + nulls;
      String density = nulls.isEmpty() ? "no nulls" : "one row in ten null";
      String time = "TIME(" + TIME_PRECISION + ")";
      out.add(new Section("time_to_seconds over " + time + ", " + density,
          "SELECT time_to_seconds(" + t + ") AS a FROM varka_decimal", false));
      out.add(new Section("extract(SECOND) over " + time + ", " + density,
          "SELECT extract(SECOND FROM " + t + ") AS a FROM varka_decimal", false));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-decimal-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-decimal-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.conf().set("spark.sql.timeType.enabled", "true");
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView("varka_decimal");
        VarkaArrowSessions.cache(session, "varka_decimal");
      }
      for (Section s : sections()) {
        requireBaseline(varka, s.title(), s.query(), s.fuses());
      }
      for (Section s : sections()) {
        section(s.title(), () -> {
          VarkaBenchmark benchmark = benchmark(
              s.title() + " over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS).timed().build();
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
