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

import scala.runtime.BoxedUnit;

import org.apache.spark.internal.config.UI;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.VarkaColumnarRule$;
import org.apache.spark.sql.execution.VarkaColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFilterColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFilterExec;
import org.apache.spark.sql.execution.VarkaProjectExec;
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.StaticSQLConf;

/**
 * What the long lane costs end to end, against the int lane and against the row engine
 * (milestone 5, the baseline VARKA-29's {@code VARKA-29.md} 6.1.2 registered and could not
 * score).
 *
 * <p>VARKA-142 priced the lane at the kernel: a 64-bit lane costs 1.5x to 2.0x an int one in
 * cache and a flat 2.11x to 2.20x out of it, measured over memory segments with no Spark above
 * them. This is the same question one layer up, where the Arrow cache, the batch machinery and the
 * per-batch fixed costs are included and only some of them double with the lane. Every pair of
 * cases below is one shape at two widths - {@code i > i2} beside {@code l > l2}, and nothing else
 * moved - so the ratio of the two Varka rows is the lane's end-to-end price, and the ratio of each
 * Varka row to its baseline is what the engine is worth on that shape.
 *
 * <p>Only comparisons, {@code greatest}/{@code least} and {@code CASE WHEN} appear, because that
 * is what VARKA-29 admits on the long lane: arithmetic over {@code bigint} is VARKA-104, the
 * {@code TIME} fields are 102 and the interval arithmetic is 103. A {@code TIME} and a day-time
 * interval case run beside the {@code bigint} ones because all three share the lane and none of
 * them shares a Catalyst expression, so a lowering that was accidentally type-specific would show
 * as one row out of line.
 *
 * <p>Each Varka case asserts that its query actually fused before it is timed: a benchmark whose
 * subject silently fell back to the row engine would report the row engine twice and call the
 * ratio 1.0. The exception is a filter whose condition is on the long lane, on an x86 host whose
 * long species has two lanes (the 128-bit companion's): the compiler refuses that condition
 * there (VARKA-503), so the case is checked to have declined, and its Varka arm is the row path.
 *
 * <p>To run this benchmark:
 * <pre>
 *   dev/varka_bench_regen.sh sql VarkaLongLaneThroughputBenchmark
 * </pre>
 */
public class VarkaLongLaneThroughputBenchmark extends VarkaSqlBenchmarkBase {

  private static final int NUM_ROWS = 2_000_000;

  /**
   * The second scale, for the one question a single row count cannot answer. VARKA-142 found the
   * lane's kernel cost depends on where the working set sits: 1.5x to 2.0x while both arms are
   * in cache and a flat 2.11x to 2.20x once they are not. End to end the per-batch fixed costs
   * do not double with the lane, so the ratio should sit <i>below</i> the kernel's at a small
   * scale and climb toward it as the columns leave cache. This table carries only the four
   * columns the filter pairs read, so twenty million rows is about half a gigabyte rather than
   * four.
   */
  private static final int BIG_ROWS = 20_000_000;

  private static SparkSession createSession(String appName, boolean varkaEnabled) {
    SparkSession.Builder builder = SparkSession.builder()
        .master("local[1]")
        .appName(appName)
        .config(UI.UI_ENABLED().key(), false)
        .config(SQLConf.SHUFFLE_PARTITIONS().key(), 1L)
        .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED().key(), "false")
        .config(StaticSQLConf.SPARK_CACHE_SERIALIZER().key(),
            ArrowCachedBatchSerializer.class.getName())
        .config(SQLConf.CACHE_VECTORIZED_READER_ENABLED().key(), "true")
        // `TIME` is gated by a flag whose default is "is this a test JVM"; a benchmark is not
        // one, so both sessions turn it on explicitly and measure the same feature set.
        .config("spark.sql.timeType.enabled", "true");
    if (varkaEnabled) {
      builder
          .config(SQLConf.VARKA_ENABLED().key(), "true")
          // Steady state, checked to have run on the kernel before timing: a new shape's
          // first query must not wait on the row path for its kernel to compile.
          .config(SQLConf.VARKA_WARMUP_ENABLED().key(), "false")
          .withExtensions(extensions -> {
            extensions.injectColumnar(session -> VarkaColumnarRule$.MODULE$);
            return BoxedUnit.UNIT;
          });
    }
    return builder.getOrCreate();
  }

  /**
   * One cached table carrying the same values at both widths: {@code i}, {@code i2} as ints and
   * {@code l}, {@code l2} as the same numbers in {@code bigint}, so the two arms of a pair
   * compare identical data and differ only in the lane. {@code d}, {@code d2} are dates, which
   * are the int lane's <i>value</i> leaves: a bare {@code int} column is admitted as a comparison
   * or arithmetic operand (VARKA-122) and not as a value, so {@code greatest(i, i2)} declines
   * where {@code greatest(l, l2)} fuses, and the int-lane twin of a long projection has to be the
   * date columns. Same lane, same lane count, one fewer byte of nothing - the pairing is about
   * the width, not the Spark type. {@code t}, {@code t2} are {@code TIME(6)} spread over the day
   * and {@code dt}, {@code dt2} are day-time intervals of both signs. Every 31st row is null in
   * each column, the null pattern the date benchmarks use, so the validity word is live rather
   * than trivially absent.
   */
  private static void cacheTable(SparkSession session) {
    session.sql("""
        select
          case when id %% 31 = 0 then null
               else date_add(date'2020-01-01', cast(id %% 100000 as int)) end as d,
          case when id %% 31 = 7 then null
               else date_add(date'2021-01-01', cast((id * 7) %% 100000 as int)) end as d2,
          case when id %% 31 = 0 then null else cast(id %% 100000 as int) end as i,
          case when id %% 31 = 7 then null else cast((id * 7) %% 100000 as int) end as i2,
          case when id %% 31 = 0 then null
               else cast(id %% 100000 as bigint) + 5000000000L end as l,
          case when id %% 31 = 7 then null
               else cast((id * 7) %% 100000 as bigint) + 5000000000L end as l2,
          case when id %% 31 = 0 then null
               else make_time(cast(id %% 24 as int), cast((id * 7) %% 60 as int),
                              cast((id * 13) %% 60 as decimal(16, 6))) end as t,
          case when id %% 31 = 7 then null
               else make_time(cast((id * 5) %% 24 as int), cast((id * 11) %% 60 as int),
                              cast((id * 17) %% 60 as decimal(16, 6))) end as t2,
          case when id %% 31 = 0 then null
               else make_dt_interval(0, 0, 0,
                                     cast(id %% 100000 as decimal(16, 6))) end as dt,
          case when id %% 31 = 7 then null
               else make_dt_interval(0, 0, 0,
                                     -cast((id * 7) %% 100000 as decimal(16, 6))) end as dt2
        from range(0, %d)""".formatted(NUM_ROWS))
        .createOrReplaceTempView("varka_long_pairs");
    session.catalog().cacheTable("varka_long_pairs");
    // cacheTable is lazy, and the first case measured would otherwise pay for the whole cache.
    session.sql("select count(*) from varka_long_pairs").collect();
  }

  /** {@code cacheTable}'s four-column twin at {@link #BIG_ROWS}, for the scale question. */
  private static void cacheBigTable(SparkSession session) {
    session.sql("""
        select
          case when id %% 31 = 0 then null else cast(id %% 100000 as int) end as i,
          case when id %% 31 = 7 then null else cast((id * 7) %% 100000 as int) end as i2,
          case when id %% 31 = 0 then null
               else cast(id %% 100000 as bigint) + 5000000000 end as l,
          case when id %% 31 = 7 then null
               else cast((id * 7) %% 100000 as bigint) + 5000000000 end as l2
        from range(0, %d)""".formatted(BIG_ROWS))
        .createOrReplaceTempView("varka_long_pairs_big");
    session.catalog().cacheTable("varka_long_pairs_big");
    session.sql("select count(*) from varka_long_pairs_big").collect();
  }

  /**
   * Whether the plan holds one of the four Varka node classes: a filter that feeds a row consumer
   * is a {@code VarkaFilterColumnarToRowExec}, and a check that looked only for {@code
   * VarkaFilterExec} would call a fused plan unfused.
   */
  private static boolean isVarkaNode(SparkSession session, String query) {
    return session.sql(query).queryExecution().executedPlan().find(
        p -> p instanceof VarkaColumnarToRowExec || p instanceof VarkaProjectExec
            || p instanceof VarkaFilterExec || p instanceof VarkaFilterColumnarToRowExec)
        .isDefined();
  }

  private static void requireFused(SparkSession session, String name, String query) {
    if (!isVarkaNode(session, query)) {
      SparkPlan plan = session.sql(query).queryExecution().executedPlan();
      throw new IllegalStateException(name + " did not fuse, so this case would time the row "
          + "engine twice: " + query + "\n" + plan.treeString());
    }
  }

  /**
   * {@link #requireFused}, except for a filter whose condition is on the long lane on a host where
   * the compiler refuses one (x86 at two long lanes, VARKA-503): there the case must have
   * declined, and its Varka arm times the row path, which is what such a host runs.
   */
  private static void requireRuns(SparkSession session, String name, String query,
      boolean longCondition) {
    if (!longCondition || !VarkaKernelPlan.HOST_TWO_LANE_MASKS_FALL_BACK) {
      requireFused(session, name, query);
    } else if (isVarkaNode(session, query)) {
      throw new IllegalStateException(name + " fused on a two-lane x86 host, where the compiler "
          + "refuses a long-lane condition: " + query);
    }
  }

  /** {@link #runPair} over the {@link #NUM_ROWS} table. */
  private void runPair(SparkSession baseline, SparkSession varka, String name, String intQuery,
      String longQuery) {
    runPair(baseline, varka, name, intQuery, longQuery, NUM_ROWS);
  }

  /**
   * One shape at both widths: four rows, the two baselines and the two Varka arms, in one table
   * so the reader can take any ratio without crossing a JVM. {@code intQuery} and {@code
   * longQuery} must be the same shape over the same values.
   */
  private void runPair(SparkSession baseline, SparkSession varka, String name, String intQuery,
      String longQuery, int rows) {
    requireFused(varka, name + " (int lane)", intQuery);
    requireRuns(varka, name + " (long lane)", longQuery, name.startsWith("filter"));
    section(name, () -> {
      VarkaBenchmark benchmark = benchmark(
          name + " over " + rows + " Arrow-cached rows", rows).timed().build();
      benchmark.addCase("int32 lane, baseline (Janino)", i -> noop(baseline.sql(intQuery)));
      benchmark.addCase("int32 lane, varka (SIMD)", i -> noop(varka.sql(intQuery)));
      benchmark.addCase("int64 lane, baseline (Janino)", i -> noop(baseline.sql(longQuery)));
      benchmark.addCase("int64 lane, varka (SIMD)", i -> noop(varka.sql(longQuery)));
      runCases(benchmark);
    });
  }

  /** A long-lane shape with no int twin - the {@code TIME} and interval columns. */
  private void runSingle(SparkSession baseline, SparkSession varka, String name, String query) {
    requireRuns(varka, name, query, name.startsWith("filter"));
    section(name, () -> {
      VarkaBenchmark benchmark = benchmark(
          name + " over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS).timed().build();
      benchmark.addCase("baseline (Janino)", i -> noop(baseline.sql(query)));
      benchmark.addCase("varka (SIMD)", i -> noop(varka.sql(query)));
      runCases(benchmark);
    });
  }

  /** A case of the crossed table: its name and its query. */
  private record Case(String name, String query) {}

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = createSession("varka-long-baseline", false);
    clearSessions();
    SparkSession varka = createSession("varka-long-fused", true);
    clearSessions();
    try {
      cacheTable(baseline);
      cacheTable(varka);

      // The pair VARKA-29.md 6.1.2 predicts: a comparison filter, one column against
      // another, at both widths. The predicted band is 0.45x to 0.60x of the int arm.
      runPair(baseline, varka, "filter, column against column",
          "SELECT i FROM varka_long_pairs WHERE i > i2",
          "SELECT l FROM varka_long_pairs WHERE l > l2");
      runPair(baseline, varka, "filter, column against literal",
          "SELECT i FROM varka_long_pairs WHERE i > 50000",
          "SELECT l FROM varka_long_pairs WHERE l > 5000050000");
      runPair(baseline, varka, "filter, two conjuncts and a null check",
          "SELECT i FROM varka_long_pairs WHERE i > i2 AND i2 IS NOT NULL",
          "SELECT l FROM varka_long_pairs WHERE l > l2 AND l2 IS NOT NULL");
      // The int-lane arm of a projection pair is the date columns, for the reason `cacheTable`
      // gives: a bare int column is not a value leaf at the int lane.
      runPair(baseline, varka, "projection, greatest",
          "SELECT greatest(d, d2) AS a FROM varka_long_pairs",
          "SELECT greatest(l, l2) AS a FROM varka_long_pairs");
      runPair(baseline, varka, "projection, CASE WHEN over a comparison",
          "SELECT CASE WHEN d < d2 THEN d ELSE d2 END AS a FROM varka_long_pairs",
          "SELECT CASE WHEN l < l2 THEN l ELSE l2 END AS a FROM varka_long_pairs");

      // The other two types on the same lane, which share no Catalyst expression with bigint.
      runSingle(baseline, varka, "filter, TIME comparison",
          "SELECT t FROM varka_long_pairs WHERE t < t2");
      runSingle(baseline, varka, "projection, greatest over TIME",
          "SELECT greatest(t, t2) AS a FROM varka_long_pairs");
      runSingle(baseline, varka, "filter, day-time interval comparison",
          "SELECT dt FROM varka_long_pairs WHERE dt > dt2");
      runSingle(baseline, varka, "projection, least over day-time intervals",
          "SELECT least(dt, dt2) AS a FROM varka_long_pairs");

      // The same two filters ten times larger, which is the only way to tell a lane cost
      // from a per-batch fixed cost: the kernel's share of the work grows with the rows
      // and the fixed costs do not.
      cacheBigTable(baseline);
      cacheBigTable(varka);
      runPair(baseline, varka, "filter, column against column, " + BIG_ROWS + " rows",
          "SELECT i FROM varka_long_pairs_big WHERE i > i2",
          "SELECT l FROM varka_long_pairs_big WHERE l > l2", BIG_ROWS);
      runPair(baseline, varka, "filter, column against literal, " + BIG_ROWS + " rows",
          "SELECT i FROM varka_long_pairs_big WHERE i > 50000",
          "SELECT l FROM varka_long_pairs_big WHERE l > 5000050000", BIG_ROWS);

      // Which half of a filter costs the lane: the comparison, or the surviving column's
      // compaction? The four cases cross them. A Varka filter compacts every forwarded column
      // to the selected rows, and the vectorised `compress` path serves four-byte vectors only
      // (`VarkaKernelEvaluator`, the finding `VARKA-29.md` 2 recorded as VARKA-128's), so an
      // eight-byte column takes a per-row copy. If the cost follows the *output* column rather
      // than the compared one, that is the compaction and not the lane.
      section("filter: where the lane's cost is, " + BIG_ROWS + " rows", () -> {
        VarkaBenchmark benchmark = benchmark(
            "compare and output crossed over " + BIG_ROWS + " Arrow-cached rows", BIG_ROWS)
            .timed().build();
        List<Case> cases = List.of(
            new Case("compare int32, output int32",
                "SELECT i FROM varka_long_pairs_big WHERE i > 50000"),
            new Case("compare int64, output int64",
                "SELECT l FROM varka_long_pairs_big WHERE l > 5000050000"),
            new Case("compare int64, output int32",
                "SELECT i FROM varka_long_pairs_big WHERE l > 5000050000"),
            new Case("compare int32, output int64",
                "SELECT l FROM varka_long_pairs_big WHERE i > 50000"),
            new Case("compare int64, output none (count)",
                "SELECT count(*) FROM varka_long_pairs_big WHERE l > 5000050000"),
            new Case("compare int32, output none (count)",
                "SELECT count(*) FROM varka_long_pairs_big WHERE i > 50000"));
        for (Case c : cases) {
          requireRuns(varka, c.name(), c.query(), c.name().startsWith("compare int64"));
          benchmark.addCase(c.name() + ", varka (SIMD)", i -> noop(varka.sql(c.query())));
        }
        runCases(benchmark);
      });
    } finally {
      baseline.stop();
      varka.stop();
    }
  }
}
