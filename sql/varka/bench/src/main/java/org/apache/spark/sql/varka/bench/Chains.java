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

package org.apache.spark.sql.varka.bench;

import java.util.List;

/**
 * Chained date expressions: what Varka is worth against stock Spark when a query does real
 * work per row, which is the question {@link Surface} understates and the datapath question
 * it cannot answer at all.
 *
 * <p><b>Why a second list, and why it is the one that makes the case.</b> The surface is one
 * entry per expression, in the spelling a reader would write, and that is what makes it a
 * coverage document. It also understates the engine twice over.
 *
 * <p>Its lightest rows are bound by memory bandwidth rather than by arithmetic: measured on the
 * development laptop at 1e9 rows, {@code date_add(d, 3)} runs at 0.5 ns/row - that one *is*
 * committed, in {@code benchmarks/DateSurface-varka-jdk25-results.txt} - while reading four
 * bytes and writing four, about 15 GB/s and so single-core DRAM bandwidth on that machine. The
 * kernel is waiting for memory, not for the vector unit, so no width of datapath moves it and
 * roughly a third of the surface is in that regime.
 *
 * <p>And a single operation is where stock Spark is least disadvantaged. Its generated code
 * pays its per-row costs once there; over a chain it pays them at every link, while the kernel
 * here fuses the whole chain into one vectorised loop and shares the civil-from-days prefix
 * across the calendar nodes that read one date. So the ratio against stock should
 * *grow* with depth, and the surface's 18x to 25x is the floor of what this engine is worth
 * rather than the headline. These entries are where that is visible.
 *
 * <p><b>What these are instead.</b> The same operations composed until the arithmetic per byte
 * read rises by an order of magnitude and the kernel is bound by what it computes rather than
 * by what it loads. Op counts from {@code dev/varka_emit.sh --table}, against 34 for
 * {@code year(d)} and 64 for {@code weekofyear(d)}.
 *
 * <p><b>And they mix the types on purpose.</b> Varka covers three - DATE, INT and the
 * year-month interval, all int32 in one lane - and a benchmark of dates alone understates that
 * to a reader and exercises less of the compiler. <b>Every</b> entry carries a date column, an
 * int column and an interval column in one expression; one produces an interval rather than
 * consuming one. (This read "seven of the twelve" and the plan said eight; both were written
 * against the first list and neither was recounted when it was replaced.)
 * That is closer to real SQL than a chain of one type, and it is the claim the milestone
 * actually wants to make.
 *
 * <p><b>Two other things follow from the arithmetic, and they are why this list exists at the
 * size it does.</b> The job-size rule of {@code DateSurfaceBenchmark} fails a row whose fixed
 * share is over 5%, so an entry has to be slow enough per row that the job's constant cost
 * disappears behind it. These entries are chosen to clear that at **1e8 rows**, where the
 * cached table is 2.4 GB and resident, rather than at the 5e8 the surface needs and that no
 * runner in the pool can hold. More work per row and more rows are two ways to buy executor
 * time, and only one of them collides with memory.
 *
 * <p><b>The sizing is a prediction, and is registered as one.</b> {@code VARKA-62.md}
 * 11.13 records it with its arithmetic and will score it against the first committed chain
 * file. The numbers behind it - a per-iteration fixed cost near 18 ms, roughly 0.02 ns per
 * emitter op over a memory floor near 0.8 ns, and so 6.7 to 10.5 ns/row for the entries here -
 * come from a GitHub dispatch on 11 September 2026 whose results were never committed,
 * because that run failed the fixed-share rule and predates the change that made a failing
 * run upload its evidence. They are therefore scratch figures in the sense
 * {@code dev/varka_quote_allowlist.txt} means it, and they are written here as the reasoning
 * that chose the list rather than as measurements anyone should quote.
 *
 * <p><b>Most of these were impossible until the guard learned to re-arm.</b> A chain that shifts a
 * date by a
 * column of days and then by a column month count declined at compile time, because the producer
 * guard promised the whole narrowed range and the shift above it had none left; the entry at
 * 332 ops below is the shape that opened that task. Re-arming the guard admits them, which is
 * why this list is 293 to 483 emitter ops where its first version was 163 to 304.
 *
 * <p>One family still cannot sit above a re-armed guard: {@code weekofyear} and
 * {@code YEAROFWEEK} shift by the Thursday rule's literal three days, and a literal shift is
 * not re-armed - so {@code extract(YEAROFWEEK FROM add_months(last_day(date_add(d, i)), i) +
 * ymy)} declines at {@code [-6156431, 12144130]}, three days past the floor. Whether a small
 * literal shift should re-arm - guarding it would decline almost no batch, unlike a shift of
 * twenty million days - is a follow-up rather than a defect.
 *
 * <p>The week entry here avoids that by spelling its day shift as an interval rather than as
 * {@code date_add(d, i)}, and the difference is the saturation and not the guard: a column day
 * offset makes {@code dayRange} answer the whole of
 * {@code [NARROW_MIN_DAYS, NARROW_MAX_DAYS]} on the strength of the producer's runtime guard, which
 * leaves the shifts above it nothing, while a month shift over a plain column keeps the
 * interval additive from the contract range and it stays inside. So that entry lowers to
 * {@code (weekOfYear (thursdayOf (addMonths (addMonths (lastDay (addMonths col:0 col:1)) col:2)
 * col:3)))} - no {@code GuardedDay} anywhere in it - and is admitted on {@code admitCalendar}'s
 * first case. It is the one entry of the twelve with no re-armed guard in it.
 *
 * <p>The other eleven do, and one of them twice:
 * {@code quarter(next_day(add_months(last_day(date_add(d, i)), i) + ymy, 'MONDAY'))} lowers to
 * {@code (quarter (nextDay (guardedDay (addMonths (guardedDay (addMonths (lastDay (addDays
 * col:0 col:2)) col:2)) col:3)) lit:0))}. A reader reworking the literal-shift follow-up above
 * should take the eleven as the regression set for guard placement, and not this one.
 *
 * <p>Every entry is checked to fuse before it is added - the driver's {@code --expect-fused}
 * fails the run otherwise - and the op count is the reason each was chosen over a lighter
 * spelling of the same shape. A chain that folds is worth knowing about but not worth timing:
 * {@code datediff(date_from_unix_date(unix_date(d) + i), d)} collapses to 9 ops, because
 * {@code date_from_unix_date(unix_date(d))} is the identity and the compiler knows it.
 *
 * <p><b>Shapes that decline, found while choosing these and worth recording.</b>
 * {@code datediff(d2, d) * i} and {@code i % 20} both decline - an int multiply by a column
 * and an int remainder - although multiplying by a literal is fine, as
 * {@code CAST(month(d) AS INTERVAL YEAR) * 3} in {@link Surface} shows. So does
 * {@code make_ym_interval(i, i)}, where the surface's
 * {@code make_ym_interval(year(d), month(d))} fuses. These are coverage gaps in the int32
 * arm rather than anything wrong here, and they are why several otherwise natural mixed-type
 * spellings are absent.
 */
public final class Chains {

  /**
   * One chain: the expression, and the emitter op count that is the reason it is here.
   *
   * <p>The count is data rather than a comment because it is the entry's whole justification,
   * and a justification a test cannot read is one that goes stale silently. Nesting depth is
   * not a usable proxy for it - {@code weekofyear(add_months(d, i))} is two calls and 176 ops,
   * while {@code month(next_day(date_add(d, i), 'MONDAY'))} is three calls and 55 - because
   * what costs is the arithmetic inside each operation, not how many there are.
   *
   * @param expr the projection, in the spelling a reader would write
   * @param emitterOps {@code IntVector} invocations in {@code loopDense0}, from
   *                   {@code dev/varka_emit.sh --table "<expr>"}, which is the source of truth
   *                   and regenerates every number here. Not "IntVector and VectorMask", which
   *                   is what this said and what the tool's non-table mode prints - the table
   *                   mode counts one of the two, and the numbers below are the table's
   */
  record Chain(String expr, int emitterOps) {}

  /**
   * Below this an entry does not earn its place. The break-even is lower than the constant:
   * the class comment's model - roughly 0.02 ns per op over a 0.8 ns memory floor against a
   * 3.6 ns/row threshold - puts it near 140 ops. This sits well above that on purpose, because
   * the list is not trying to perch at the edge of the fixed-share rule but to be firmly
   * compute-bound, and every entry the re-arm admits clears it comfortably. A later editor who
   * recomputes the model, reads 280 as a typo and lowers it to 150 would admit entries that
   * pass this test and then fail --max-fixed-share after a gated runner dispatch, which is a
   * much more expensive place to find out.
   */
  static final int MIN_OPS = 280;

  private static final List<Chain> CHAINS = List.of(
      // An interval *output*, built from two decomposed dates and an int: the third type as a
      // result and not only as an input, and the heaviest entry here.
      new Chain("make_ym_interval(year(add_months(last_day(date_add(d, i)), i) + ymy), "
          + "month(d + ymm) + i)", 483),
      // Two independent chains feeding one subtract - the only shape with instruction-level
      // parallelism of its own, and so the control for whether the others are latency-bound.
      new Chain("datediff(add_months(last_day(date_add(d, i)), i) + ymy, last_day(d + ym))", 466),
      new Chain("weekofyear(add_months(last_day(d + ymm), i) + ymy)", 461),
      new Chain("quarter(add_months(last_day(date_add(d, i) + ym), i) + ymm)", 444),
      new Chain("quarter(next_day(add_months(last_day(date_add(d, i)), i) + ymy, 'MONDAY'))", 346),
      new Chain("dayofyear(add_months(last_day(date_add(d, i)), i) + ymy)", 335),
      // The shape that motivated the re-arm: it declined until the guard learned to reset.
      new Chain("dayofyear(add_months(last_day(date_add(d, i)), 1) + ymy)", 332),
      new Chain("month(add_months(last_day(date_add(d, i)), i) + ymy)", 327),
      new Chain("year(add_months(last_day(date_add(d, i)), i) + ymy)", 326),
      new Chain("year(add_months(last_day(date_add(d, i) + ym), i))", 326),
      new Chain("dayofweek(add_months(last_day(date_add(d, i)), i) + ymy)", 308),
      new Chain("datediff(add_months(last_day(date_add(d, i)), i) + ymy, d)", 293));

  /**
   * The chains. Ordered by cost, descending - which the first version of this comment denied,
   * claiming they were interleaved by family so a shard's stride would not sort them; the list
   * was already sorted. It is tolerable here and would not be for {@link Surface#ENTRIES}: a
   * stride over a sorted list hands shard 0 the heaviest of every N, so the shards diverge by
   * the list's spread, and across 293 to 483 ops that is under 1.7x where across the surface's
   * 9 to 218 it would be an order of magnitude.
   */
  public static final List<Surface.Entry> ENTRIES =
      CHAINS.stream().map(c -> Surface.Entry.projection(c.expr())).toList();

  /** The recorded op count for an entry's label, for the test that checks it earns its place. */
  static int emitterOps(String label) {
    return CHAINS.stream().filter(c -> c.expr().equals(label)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no chain " + label))
        .emitterOps();
  }

  private Chains() {}
}
