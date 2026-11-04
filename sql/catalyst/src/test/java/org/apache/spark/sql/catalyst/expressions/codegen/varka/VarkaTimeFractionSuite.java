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

package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.util.DateTimeConstants;
import org.apache.spark.sql.catalyst.util.DateTimeUtils;
import org.apache.spark.sql.types.Decimal;

/**
 * The fraction of {@code extract(SECOND FROM t)} against Spark's double round trip
 * (`VARKA-157.md` 3 item 4). Spark's {@code getSecondsOfTimeWithFraction(n, p)} computes the
 * second of the minute {@code s} and the truncated fraction {@code g = (n % 10^9) * 10^p / 10^9},
 * then {@code Decimal(s + g.toDouble / 10^p, 2 + p, p)}: a {@code Double} rounded back to
 * {@code p} digits. The kernel's value is the unscaled {@code s * 10^p + g}
 * ({@code VarkaTimeCompiler.decimalProducer}), built of divisions {@code long_divide.smt2}
 * proves. Spark's value depends on {@code n} only through {@code (s, g)}, so checking every one
 * of the {@code 60 * 10^p} pairs of a precision checks every nanosecond of a minute; this sweep
 * is that check, and the argument that the double lands back on {@code s * 10^p + g} is what it
 * confirms.
 *
 * <p>Two tiers, by cost: {@code p <= 7} in every build (667 million pairs, 7.6 s on the laptop's
 * 24 threads, 6.8 s of it {@code TIME(7)}), and {@code p = 8} and {@code p = 9} a long sweep
 * ({@code -Dvarka.sweep.long=true}, 66 billion, the nightly's, about ten times and a hundred
 * times {@code TIME(7)}'s), each in parallel over the cores.
 */
@VarkaSuite
public final class VarkaTimeFractionSuite {

  private static final long SECOND = DateTimeConstants.NANOS_PER_SECOND;

  /** At most this many pairs are evaluated by one task of the parallel stream. */
  private static final long BLOCK = 1L << 20;

  /**
   * Every {@code (s, g)} pair of {@code TIME(p)} against Spark, or the first mismatches found:
   * the time {@code n = s * 10^9 + g * 10^(9 - p)} is the first nanosecond of the pair, and every
   * later nanosecond of it truncates to the same {@code g}.
   */
  private static List<String> mismatches(int p) {
    long scale = Decimal.POW_10()[p];
    long step = Decimal.POW_10()[9 - p];
    long pairs = 60L * scale;
    var found = Collections.synchronizedList(new ArrayList<String>());
    var count = new AtomicLong();
    LongStream.range(0, (pairs + BLOCK - 1) / BLOCK).parallel().forEach(b -> {
      long end = Math.min(pairs, (b + 1) * BLOCK);
      for (long k = b * BLOCK; k < end; k++) {
        long s = k / scale;
        long g = k % scale;
        long n = s * SECOND + g * step;
        Decimal d = DateTimeUtils.getSecondsOfTimeWithFraction(n, p);
        if (d.scale() != p || d.toUnscaledLong() != k) {
          if (count.incrementAndGet() <= 10) {
            found.add("TIME(" + p + ") n=" + n + ": Spark " + d + ", kernel " + k);
          }
        }
      }
    });
    if (count.get() > 10) {
      found.add((count.get() - 10) + " more at TIME(" + p + ")");
    }
    return found;
  }

  private static void sweep(int from, int to) {
    var found = new ArrayList<String>();
    for (int p = from; p <= to; p++) {
      long start = System.nanoTime();
      found.addAll(mismatches(p));
      System.out.println(String.format("VarkaTimeFractionSuite: TIME(%d), %d pairs, %.1f s", p,
          60L * Decimal.POW_10()[p], (System.nanoTime() - start) / 1e9));
    }
    assertTrue(found.isEmpty(), String.join("\n", found));
  }

  @Test
  @DisplayName("the fraction agrees with Spark's double round trip on every (s, g) of TIME(0) "
      + "to TIME(7)")
  void everyPairToSeven() {
    sweep(0, 7);
  }

  @Test
  @DisplayName("the fraction agrees with Spark's double round trip on every (s, g) of TIME(8) "
      + "and TIME(9), every nanosecond of a minute (long-sweep: -Dvarka.sweep.long=true; "
      + "VARKA-157)")
  void everyNanosecondOfAMinute() {
    assumeTrue("true".equals(System.getProperty("varka.sweep.long")),
        "set -Dvarka.sweep.long=true to sweep TIME(8) and TIME(9)");
    sweep(8, 9);
  }

  /**
   * {@code time_to_seconds}' {@code (n + 500) / 1000} against Spark's exact quotient rounded half
   * up, over the first and the last two million nanoseconds of the day, every one of them, and
   * each second's neighbourhood of the rounding edge {@code x.0000005}: a sum that only the
   * edges can tell from truncation or from rounding half even.
   */
  @Test
  @DisplayName("time_to_seconds rounds half up as Spark does at the ends of the day and every "
      + "second's rounding edge")
  void timeToSecondsRounding() {
    long day = DateTimeConstants.NANOS_PER_DAY;
    var found = Collections.synchronizedList(new ArrayList<String>());
    LongStream ends = LongStream.concat(LongStream.range(0, 2_000_000L),
        LongStream.range(day - 2_000_000L, day));
    LongStream edges = LongStream.range(0, 86400L).flatMap(sec ->
        LongStream.rangeClosed(-2, 2).map(d -> sec * SECOND + 500 + d));
    LongStream.concat(ends, edges).parallel().forEach(n -> {
      Decimal d = DateTimeUtils.timeToSeconds(n);
      if (d.scale() != 6 || d.toUnscaledLong() != (n + 500) / 1000) {
        found.add("n=" + n + ": Spark " + d + ", kernel " + (n + 500) / 1000);
      }
    });
    assertTrue(found.isEmpty(), String.join("\n", found.subList(0, Math.min(10, found.size()))));
  }
}
