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
 * Chained {@code TIME} expressions: the long lane's answer to the question {@link Chains}
 * answers for the date lane - what Varka is worth against stock Spark when a query does real
 * work per row, and whether a wider vector datapath shows at all.
 *
 * <p><b>Why the {@code TIME} surface cannot answer it.</b> {@link Times} times one expression
 * per row, and every one of its projection entries reads at 0.8 to 1.4 ns per row on the
 * development laptop ({@code benchmarks/TimeSurface-varka-jdk25-results.txt}): an eight-byte
 * column in and an eight-byte column out, with the arithmetic hidden under the memory traffic.
 * A kernel waiting for memory cannot show the width of its datapath, and a single operation is
 * where stock Spark pays its per-row costs only once. These entries compose the same operations
 * until the arithmetic exceeds the memory floor, which is the regime where the datapath and the
 * fusion both count.
 *
 * <p><b>What can be composed on the long lane today, and what cannot.</b> The lane narrows an
 * extract to its int only at an output root, so {@code hour(t)} may end a chain but nothing may
 * sit above it ({@code minute(t + dt) + second(t2)} declines; VARKA-28 lifts this). Arithmetic
 * between two {@code bigint}s or two intervals is not built (VARKA-104 and VARKA-103), so
 * {@code time_diff(..) + time_diff(..)} declines and a {@code bigint} column enters only through
 * {@code greatest}, {@code least}, a comparison or a conditional. And {@code t - dt} declines,
 * because Spark spells it as {@code t + (-dt)} and the interval negation is not lowered. So a
 * chain here composes {@code time_trunc}, {@code t + dt}, {@code greatest}/{@code least} and
 * the conditionals in the lane, and ends in a {@code TIME}, an interval, a {@code time_diff}
 * count or one extract.
 *
 * <p><b>Every entry is safe against midnight by construction, not by luck.</b> A {@code TIME}
 * plus an interval that leaves the day is an error in Spark, on the stock arms as on the fork,
 * and one row would fail the whole run. The table's own guarantee is narrow: {@code dt} runs
 * forward before noon and backward after it, keyed on {@code t}'s hour, so {@code t + dt} and
 * {@code time_trunc(.., t) + dt} stay inside the day, and no other sum with {@code dt} is
 * promised. {@code dt2} is under a second, so it may be added to any time already truncated to
 * the second or coarser - {@code time_trunc('SECOND', t2) + dt2} at most reaches
 * {@code 23:59:59.999} - and never to a raw {@code t2}, which can lie within a second of
 * midnight. Every sum below follows those two rules and nothing else.
 *
 * <p><b>The three types.</b> Every entry reads a {@code TIME} column and an interval column;
 * five also read a {@code bigint}, which is as many as the lane's coverage allows without
 * {@code bigint} arithmetic. The outputs are a {@code TIME}, an interval, a {@code bigint}
 * count and the three extracts, so the list produces each of the lane's types as well as
 * consuming them.
 *
 * <p><b>Op counts</b> are from {@code dev/varka_emit.sh --table "<expr>" --columns
 * t:time,t2:time,dt:dt,dt2:dt,l:bigint,l2:bigint}: the lane-vector invocations in
 * {@code loopDense0}, where the surface's {@code time_trunc('MINUTE', t)} is 7 and
 * {@code t + dt} is 10. Each entry is checked to fuse before it is added - the driver's
 * {@code --expect-fused} fails the run otherwise - and the count is the reason each spelling
 * was chosen over a lighter one of the same shape.
 */
public final class TimeChains {

  /**
   * One chain: the expression, and the emitter op count that is the reason it is here. The
   * count is data rather than a comment so that {@code TimeChainsTest} can hold every entry to
   * {@link #MIN_OPS}; see {@link Chains.Chain} for why depth is not a usable proxy for it.
   *
   * @param expr the projection, in the spelling a reader would write
   * @param emitterOps lane-vector invocations in {@code loopDense0}, from
   *                   {@code dev/varka_emit.sh --table "<expr>"}
   */
  record Chain(String expr, int emitterOps) {}

  /**
   * Below this an entry is still bound by memory and does not earn its place.
   *
   * <p>Derived from the committed {@code TIME} surface rather than inherited from
   * {@link Chains#MIN_OPS}, because every input to the date lane's model changes here: a row
   * is eight bytes rather than four and a lane op covers half the lanes. Reading
   * {@code benchmarks/TimeSurface-varka-jdk25-results.txt} against the op counts of its
   * entries: the one-column entries run from {@code least(l, 5000000000)} at 4 ops and 0.8 ns
   * per row through {@code hour(t)} at 6 and 0.9, {@code time_trunc('MINUTE', t)} at 7 and 1.0,
   * to {@code minute(t)} at 12 and 1.2, which is 0.05 ns per op over a memory floor near
   * 0.6 ns; the two-column entries, {@code greatest(t, t2)} at 4 ops and 1.1 ns and
   * {@code t + dt} at 10 and 1.4, give the same slope over a floor near 0.9 ns. The arithmetic
   * therefore equals the two-column memory floor at 18 ops, and this sits at twice that, the
   * same margin the date list keeps above its own break-even, so that every entry spends at
   * least two thirds of its time computing.
   *
   * <p>What the floor does not buy is the driver's fixed-share rule on a GitHub runner. At
   * 0.05 ns per op the heaviest entry here is near 3.3 ns per row, and a runner's per-iteration
   * constant of about 36 ms needs some 7 ns per row at the 1e8 rows its memory holds of this
   * table to keep the constant under 5%. The laptop clears the rule at 2e8 rows, and the runner
   * dispatch prices its own bound ({@code VARKA-164.md} section 5).
   */
  static final int MIN_OPS = 36;

  private static final List<Chain> CHAINS = List.of(
      // A bigint comparison choosing between two composed time differences: the heaviest
      // entry, and the one that reads all six columns.
      new Chain("CASE WHEN l < l2 THEN time_diff('MINUTE', time_trunc('HOUR', t + dt), "
          + "time_trunc('MINUTE', t2) + dt2) ELSE time_diff('SECOND', time_trunc('MINUTE', t2), "
          + "time_trunc('SECOND', t + dt) + dt2) END", 48),
      new Chain("time_diff('MILLISECOND', greatest(time_trunc('MINUTE', t + dt) + dt2, t2), "
          + "least(time_trunc('SECOND', t2) + dt2, time_trunc('HOUR', t) + dt))", 45),
      // An extract over the deepest composition the lane admits below one: the narrowing
      // happens once, at the root.
      new Chain("hour(least(time_trunc('MINUTE', t + dt) + dt2, time_trunc('SECOND', t2) + dt2, "
          + "time_trunc('HOUR', t) + dt))", 45),
      // An interval output from two independent chains feeding one subtraction: the shape
      // with instruction-level parallelism of its own, and so the control for whether the
      // others are latency-bound.
      new Chain("greatest(time_trunc('MINUTE', t + dt), time_trunc('MINUTE', t2) + dt2) - "
          + "least(time_trunc('SECOND', t), time_trunc('SECOND', t2))", 42),
      // A bigint operand beside two time differences under one greatest.
      new Chain("greatest(time_diff('SECOND', time_trunc('MINUTE', t + dt), "
          + "time_trunc('SECOND', t2) + dt2), least(l, l2), time_diff('MINUTE', t, t2))", 41),
      new Chain("least(time_trunc('MINUTE', t + dt) + dt2, time_trunc('SECOND', t2) + dt2, "
          + "time_trunc('HOUR', t) + dt)", 41),
      new Chain("if(l > l2, time_diff('MINUTE', time_trunc('HOUR', t) + dt, t2), "
          + "time_diff('SECOND', t, time_trunc('MINUTE', t2) + dt2))", 40),
      new Chain("minute(least(time_trunc('MINUTE', t + dt), time_trunc('SECOND', t2) + dt2))", 39),
      new Chain("second(greatest(time_trunc('MINUTE', t + dt) + dt2, time_trunc('SECOND', t2)))",
          39),
      new Chain("time_diff('HOUR', time_trunc('MINUTE', t + dt) + dt2, "
          + "time_trunc('SECOND', time_trunc('MINUTE', t2) + dt2))", 39),
      // A TIME output chosen by a bigint null test.
      new Chain("if(l IS NULL, time_trunc('MINUTE', t + dt) + dt2, "
          + "time_trunc('SECOND', greatest(t2, time_trunc('MINUTE', t) + dt)))", 37),
      // Two composed times under a comparison, choosing between a time difference and a
      // bigint: the lightest entry, at the floor.
      new Chain("CASE WHEN time_trunc('MINUTE', t + dt) < time_trunc('MINUTE', t2) + dt2 "
          + "THEN time_diff('SECOND', t, t2) ELSE least(l, l2) END", 36));

  /**
   * The chains, ordered by cost, descending. A shard's stride over a sorted list hands shard 0
   * the heaviest of every N, which across 36 to 48 ops is a spread of a third and tolerable,
   * as {@link Chains#ENTRIES} says of its own.
   */
  public static final List<Surface.Entry> ENTRIES =
      CHAINS.stream().map(c -> Surface.Entry.projection(c.expr())).toList();

  /** The recorded op count for an entry's label, for the test that checks it earns its place. */
  static int emitterOps(String label) {
    return CHAINS.stream().filter(c -> c.expr().equals(label)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no chain " + label))
        .emitterOps();
  }

  private TimeChains() {}
}
