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

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.VarkaSessionEmit;

/**
 * The baseline for a filter the Varka rule splits (VARKA-601): a kernel for the conjuncts that
 * read a value, then a copy of each row it keeps into an {@code UnsafeRow} for Spark's own
 * {@code FilterExec} above it, which evaluates the conjunct no kernel computes. It times such
 * splits against Spark's row path over an Arrow-cached table, at a high and a low share of rows
 * the fused conjunct keeps, so that a rule declining the splits that lose rests on a committed
 * file.
 *
 * <p>The residual conjunct of every split is {@code hash(i) % 7 <> 0}: no Varka family computes
 * {@code hash}, so it stays on the row path, and it keeps six rows in seven. The sections, each
 * with no nulls and with one row in ten null in the fused conjunct's column, are those of
 * {@code VARKA-601.md} 3.1:
 *
 * <ul>
 *   <li>{@code u < 900} and {@code u < 100} beside the residual, an int split whose fused
 *       conjunct keeps about 90% and 10% of the rows;</li>
 *   <li>{@code lu < 900} and {@code lu < 100} beside it, the same over a {@code BIGINT};</li>
 *   <li>{@code u < 900} beside it with three columns out, {@code i}, {@code l} and {@code l2},
 *       where the other splits return {@code l} alone, so that the copy carries more
 *       columns;</li>
 *   <li>two controls: {@code u < 900} alone, a kernel with no row filter above it, and the
 *       residual beside {@code u IS NOT NULL} alone, the row path on both arms.</li>
 * </ul>
 *
 * <p>Each section has two arms, named so the labels survive a rule landing:
 * {@code spark row path (varka off)}, a session without the Varka rule, and {@code varka on}, a
 * session with it.
 *
 * <p>Before anything is timed, the run checks each section's plan on the Varka session against
 * the section's {@link Fusion} class
 * ({@link VarkaSqlBenchmarkBase#requireBaseline(SparkSession, String, String, Fusion)}), so a
 * later row that changes the split - a cheaper handoff, a rule that declines it - moves a class
 * and fails the run instead of timing a different plan under the same label. The splits are
 * {@code SPLIT}, the {@code u < 900} control {@code WHOLE} and the residual control {@code NONE}.
 * A session that refuses a filter's condition in a kernel driven at 64 bits
 * ({@code declinesLongConditions}, VARKA-503), the 128-bit companion's host by default, leaves the
 * long conjunct to the row path, and of the filter only the {@code IS NOT NULL} test Spark infers
 * of {@code lu_n} would fuse, which stays whole on the row path (VARKA-598): the long splits are
 * {@code NONE} there and both of their arms time the row path. Each {@code SPLIT} and
 * {@code WHOLE} section is also run once on the Varka session and checked to serve every batch by
 * its kernel ({@link #requireKernel}), so that a batch handed to the per-row fallback, which
 * refilters with the whole condition, fails the run rather than time the row path under the
 * split's label.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaSplitFilterBaselineBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763"
 *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
 * </pre>
 * The run that measures a row which changes a section's class against the committed files lifts
 * the check with {@link VarkaSqlBenchmarkBase#FUSE_CHECK_PROPERTY}, still noting every section
 * that fuses.
 */
public class VarkaSplitFilterBaselineBenchmark extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = 2_000_000;

  /** The table's view name. */
  static final String VIEW = "varka_split_filter";

  /** The residual conjunct every split shares: no kernel computes it. */
  static final String RESIDUAL = "hash(i) % 7 <> 0";

  /**
   * A section: its title, its query, and how much of it the Varka session fuses today, the
   * {@link VarkaSqlBenchmarkBase.Fusion} class the run checks its plan against.
   */
  record Section(String title, String query, Fusion fusion) {}

  /**
   * The table. {@code u} is an {@code INT} uniform on {@code [0, 1000)}, {@code pmod(hash(id, 1),
   * 1000)}, and {@code lu} the same values as a {@code BIGINT}; {@code u_n} and {@code lu_n} are
   * the same with one row in ten null, at {@code id % 10 = 1}, which is independent of the hash,
   * so {@code u_n < 100} still keeps a tenth of the rows that are not null. {@code i}, {@code l}
   * and {@code l2} are payload, hashes of the id under other seeds, and never null, so the
   * residual over {@code i} keeps the same rows at both densities and keeps them independently of
   * {@code u}.
   */
  static String tableSql(int rows) {
    return """
        select u, lu, i, l, l2,
               case when id %% 10 = 1 then null else u end as u_n,
               case when id %% 10 = 1 then null else lu end as lu_n
        from (select id,
                     pmod(hash(id, 1), 1000) as u,
                     cast(pmod(hash(id, 1), 1000) as bigint) as lu,
                     hash(id, 2) as i,
                     cast(hash(id, 3) as bigint) as l,
                     cast(hash(id, 4) as bigint) as l2
              from range(0, %d))""".formatted(rows);
  }

  /**
   * Every section, in the order they run: each null density, then each shape, with the classes a
   * session plans them at when it does or does not refuse a filter's condition in a kernel driven
   * at 64 bits, which the long splits' fused conjuncts are.
   */
  static List<Section> sections(boolean declinesLongConditions) {
    List<Section> out = new ArrayList<>();
    Fusion longSplit = declinesLongConditions ? Fusion.NONE : Fusion.SPLIT;
    for (String nulls : new String[] {"", "_n"}) {
      String u = "u" + nulls;
      String lu = "lu" + nulls;
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " FROM " + VIEW + " WHERE ";
      // The share is of the rows whose fused column is not null: at one row in ten null the
      // splits keep about 81% and 9% of all rows.
      String high = " < 900 AND residual, keeps 90% of non-null rows";
      String low = " < 100 AND residual, keeps 10% of non-null rows";
      out.add(new Section("int split " + u + high + density,
          "SELECT l" + from + u + " < 900 AND " + RESIDUAL, Fusion.SPLIT));
      out.add(new Section("int split " + u + low + density,
          "SELECT l" + from + u + " < 100 AND " + RESIDUAL, Fusion.SPLIT));
      out.add(new Section("long split " + lu + high + density,
          "SELECT l" + from + lu + " < 900 AND " + RESIDUAL, longSplit));
      out.add(new Section("long split " + lu + low + density,
          "SELECT l" + from + lu + " < 100 AND " + RESIDUAL, longSplit));
      out.add(new Section("int split " + u + high + ", three columns out" + density,
          "SELECT i, l, l2" + from + u + " < 900 AND " + RESIDUAL, Fusion.SPLIT));
      out.add(new Section(u + " < 900 alone, control, fuses whole" + density,
          "SELECT l" + from + u + " < 900", Fusion.WHOLE));
      // The null test keeps each density's rows those of its splits; of this filter only the
      // null test would fuse, so it stays whole on the row path (VARKA-598).
      out.add(new Section("residual AND " + u + " IS NOT NULL, control, row path" + density,
          "SELECT l" + from + RESIDUAL + " AND " + u + " IS NOT NULL", Fusion.NONE));
    }
    return out;
  }

  /**
   * Runs {@code s} once on the Varka session and fails unless its Varka nodes ran a kernel batch
   * and handed none to another path: a batch the kernel cannot serve is filtered row by row with
   * the whole condition, and a timing of such batches is the row path's. The check of
   * {@code VarkaDayTimeIntervalBaselineBenchmark.requireKernel}, over this class's sections.
   */
  static void requireKernel(SparkSession varka, Section s) {
    Dataset<Row> df = varka.sql(s.query());
    df.queryExecution().toRdd().count();
    List<SparkPlan> nodes = operators(df.queryExecution().executedPlan()).stream()
        .filter(p -> p.metrics().contains("numVarkaBatches")).toList();
    long batches = 0;
    long fallbacks = 0;
    for (SparkPlan node : nodes) {
      batches += node.metrics().apply("numVarkaBatches").value();
      for (String name : VarkaSizeLadder.FALLBACK_METRICS) {
        if (node.metrics().contains(name)) {
          fallbacks += node.metrics().apply(name).value();
        }
      }
    }
    if (nodes.isEmpty() || batches == 0 || fallbacks != 0) {
      throw new IllegalStateException(s.title() + " is " + s.fusion() + " but its varka on arm "
          + "would time the row path: " + nodes.size() + " Varka nodes ran " + batches
          + " kernel batches and handed " + fallbacks + " to another path: " + s.query());
    }
  }

  /** Whether the run checks that {@code s} serves every batch by its kernel. */
  static boolean timesKernel(Section s) {
    return s.fusion() == Fusion.SPLIT || s.fusion() == Fusion.WHOLE;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-split-filter-baseline-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-split-filter-baseline-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(tableSql(NUM_ROWS)).createOrReplaceTempView(VIEW);
        VarkaArrowSessions.cache(session, VIEW);
      }
      // Each section fuses as its class lists it at the refusal the Varka session resolves, and
      // a section that times a kernel serves every batch by it.
      List<Section> sections = sections(
          VarkaSessionEmit.of(varka.sessionState().conf()).declineTwoLaneConditions());
      for (Section s : sections) {
        requireBaseline(varka, s.title(), s.query(), s.fusion());
        if (timesKernel(s)) {
          requireKernel(varka, s);
        }
      }
      for (Section s : sections) {
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
