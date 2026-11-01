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

import java.util.List;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;

/**
 * Whether a Varka filter keeps the columnar path when it narrows its output (VARKA-145).
 *
 * <p>{@code VarkaFilterColumnarToRowExec} carries {@code narrowing}: the projection above it,
 * absorbed, and {@code Some} exactly when the node's output differs from the columns it was
 * given. VARKA-144's crossed experiment found queries differing only in that clause running eight
 * times apart under a {@code noop} sink, and this file was written to price the narrowing. It
 * prices something else, which is why it is committed: with the row read-back forced, narrowing
 * costs nothing - {@code toRdd} puts the narrowed and un-narrowed shapes within 1% of each other -
 * and VARKA-78's {@code VarkaNarrowingBenchmark} had already measured that shape at three
 * selectivities and both widths, finding the narrowed form slightly <i>faster</i> than the
 * two-column control.
 *
 * <p>What the gap actually is: under a columnar sink the un-narrowed shapes stay columnar end to
 * end while the narrowed one does not, so it pays a read-back the others avoid - VARKA-19's
 * floor, arriving through a plan difference rather than through the lane or the column count.
 * The {@code toRdd} arms are the control that says so, and they are the reason this file exists
 * beside VARKA-78's rather than repeating it.
 *
 * <p>The cases vary one thing at a time over one cached table:
 *
 * <pre>
 *   A  one column, filtered and output       - no narrowing
 *   B  two columns, the other one output     - narrowing
 *   C  two columns, both output              - no narrowing, two columns out
 *   D  two columns, both in the predicate    - narrowing
 *   E  one column, no output at all          - an aggregate above the filter, a third path
 *   F  A at about one per cent selectivity
 *   G  B at about one per cent selectivity
 *   and A, B, C again through toRdd, which forces the row path for all three
 * </pre>
 *
 * <p>To run this benchmark:
 * <pre>
 *   dev/varka_bench_regen.sh sql VarkaFilterNarrowingBenchmark
 * </pre>
 */
public class VarkaFilterNarrowingBenchmark extends VarkaSqlBenchmarkBase {

  private static final int NUM_ROWS = 20_000_000;

  /** A case of the table: its name and its query. */
  private record Case(String name, String query) {}

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    // Steady state, checked to have run on the kernel before timing: the warm-up is off, so a
    // new shape's first query does not wait on the row path for its kernel to compile.
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-filter-narrowing", true, false, "local[1]");
    clearSessions();
    try {
      varka.sql("""
          select cast(id %% 100000 as int) as i,
                 cast((id * 7) %% 100000 as int) as i2
          from range(0, %d)""".formatted(NUM_ROWS))
          .createOrReplaceTempView("varka_narrowing");
      varka.catalog().cacheTable("varka_narrowing");
      varka.sql("select count(*) from varka_narrowing").collect();

      List<Case> cases = List.of(
          new Case("A one column, filtered and output (no narrowing)",
              "SELECT i FROM varka_narrowing WHERE i > 50000"),
          new Case("B two columns, the other output (narrowing)",
              "SELECT i2 FROM varka_narrowing WHERE i > 50000"),
          new Case("C two columns, both output (no narrowing)",
              "SELECT i, i2 FROM varka_narrowing WHERE i > 50000"),
          new Case("D two columns, both in the predicate (narrowing)",
              "SELECT i FROM varka_narrowing WHERE i > 50000 AND i2 >= 0"),
          new Case("E one column, no output (count)",
              "SELECT count(*) FROM varka_narrowing WHERE i > 50000"),
          new Case("F A at about 1% selectivity",
              "SELECT i FROM varka_narrowing WHERE i > 99000"),
          new Case("G B at about 1% selectivity",
              "SELECT i2 FROM varka_narrowing WHERE i > 99000"));

      // The control that decides what the gap above is: `toRdd` forces the row read-back for
      // every shape, so a difference that survives it is the narrowing's and one that does not
      // is the columnar path's.
      List<Case> rowCases = List.of(
          new Case("A through toRdd (row path forced)",
              "SELECT i FROM varka_narrowing WHERE i > 50000"),
          new Case("B through toRdd (row path forced)",
              "SELECT i2 FROM varka_narrowing WHERE i > 50000"),
          new Case("C through toRdd (row path forced)",
              "SELECT i, i2 FROM varka_narrowing WHERE i > 50000"));

      for (Case c : cases) {
        if (!holdsVarkaNode(varka.sql(c.query()).queryExecution().executedPlan())) {
          throw new IllegalStateException(c.name() + " did not fuse: " + c.query());
        }
      }

      section("a filter that narrows its output", () -> {
        VarkaBenchmark benchmark = benchmark(
            "over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS).timed().build();
        for (Case c : cases) {
          benchmark.addCase(c.name(), i -> noop(varka.sql(c.query())));
        }
        for (Case c : rowCases) {
          benchmark.addCase(c.name(), i -> varka.sql(c.query()).queryExecution().toRdd().count());
        }
        runCases(benchmark);
      });
    } finally {
      varka.stop();
    }
  }
}
