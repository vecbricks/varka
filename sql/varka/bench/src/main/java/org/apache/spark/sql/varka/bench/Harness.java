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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Timing and formatting, kept apart from Spark so the tests can drive it with a fake clock.
 *
 * <p>The iteration policy is Spark's {@code Benchmark} (task 14's methodology): warm up until
 * {@code warmupNanos} have passed, then measure until both {@code minIters} iterations and
 * {@code minNanos} have passed. Each iteration records wall time from the clock and executor
 * time from a counter the caller reads after draining the listener bus, so an iteration never
 * reads the previous one's tasks.
 *
 * <p>The output is Spark's harness format, column for column - {@code Best Time(ms)},
 * {@code Avg Time(ms)}, {@code Stdev(ms)}, {@code Rate(M/s)}, {@code Per Row(ns)},
 * {@code Relative} - because {@code dev/varka_bench_diff.py} keys its rows on that header and
 * {@code dev/varka_quote_check.py} reads the numbers from it. {@link HarnessFormatTest} holds
 * the layout to those scripts' regexes.
 */
public final class Harness {

  /** A clock, so the timing loop is testable without waiting. */
  public interface Clock {
    long nanoTime();
  }

  /** The per-iteration samples of one measurement, in milliseconds. */
  public record Samples(List<Double> wallMs, List<Double> executorMs) {}

  /** Best, average and sample standard deviation, in milliseconds. */
  public record Stats(double bestMs, double avgMs, double stdevMs) {}

  /** One row of a table. */
  public record Case(String name, Stats stats) {}

  private Harness() {}

  /**
   * Runs {@code body} under the iteration policy and returns its samples. {@code executorMillis}
   * is read before and after each measured iteration, with {@code drain} run in between so
   * asynchronous task-end events have landed.
   */
  public static Samples measure(
      Runnable body,
      Clock clock,
      LongSupplier executorMillis,
      Runnable drain,
      int minIters,
      long warmupNanos,
      long minNanos) {
    long warmStart = clock.nanoTime();
    do {
      body.run();
    } while (clock.nanoTime() - warmStart < warmupNanos);
    drain.run();
    List<Double> wall = new ArrayList<>();
    List<Double> exec = new ArrayList<>();
    long total = 0L;
    while (wall.size() < minIters || total < minNanos) {
      long before = executorMillis.getAsLong();
      long t0 = clock.nanoTime();
      body.run();
      long dt = clock.nanoTime() - t0;
      drain.run();
      long after = executorMillis.getAsLong();
      wall.add(dt / 1e6);
      exec.add((double) (after - before));
      total += dt;
    }
    return new Samples(wall, exec);
  }

  /** Spark's {@code Benchmark} statistics: min, mean, and the sample standard deviation. */
  public static Stats stats(List<Double> ms) {
    if (ms.isEmpty()) {
      throw new IllegalArgumentException("no samples");
    }
    double best = Double.MAX_VALUE;
    double sum = 0.0;
    for (double v : ms) {
      best = Math.min(best, v);
      sum += v;
    }
    double avg = sum / ms.size();
    double sq = 0.0;
    for (double v : ms) {
      sq += (v - avg) * (v - avg);
    }
    double stdev = ms.size() > 1 ? Math.sqrt(sq / (ms.size() - 1)) : 0.0;
    return new Stats(best, avg, stdev);
  }

  /**
   * One table in the harness's layout: the name padded to at least 40 columns, the header,
   * a rule, one row per case with the rate and per-row time from the best iteration and the
   * relative column against the first case, as Spark prints it.
   */
  public static String table(String name, long rows, List<Case> cases) {
    int nameLen = 40;
    nameLen = Math.max(nameLen, name.length());
    for (Case c : cases) {
      nameLen = Math.max(nameLen, c.name().length());
    }
    String fmt = "%-" + nameLen + "s %14s %14s %11s %12s %13s %10s%n";
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, fmt, name + ":", "Best Time(ms)", "Avg Time(ms)",
        "Stdev(ms)", "Rate(M/s)", "Per Row(ns)", "Relative"));
    sb.append("-".repeat(nameLen + 80)).append(System.lineSeparator());
    double firstBest = cases.isEmpty() ? 1.0 : cases.get(0).stats().bestMs();
    for (Case c : cases) {
      Stats s = c.stats();
      double rate = rows / s.bestMs() / 1000.0;
      sb.append(String.format(Locale.ROOT, fmt, c.name(),
          String.format(Locale.ROOT, "%5.0f", s.bestMs()),
          String.format(Locale.ROOT, "%4.0f", s.avgMs()),
          String.format(Locale.ROOT, "%5.0f", s.stdevMs()),
          String.format(Locale.ROOT, "%10.1f", rate),
          String.format(Locale.ROOT, "%6.1f", 1000.0 / rate),
          String.format(Locale.ROOT, "%3.1fX", firstBest / s.bestMs())));
    }
    return sb.toString();
  }
}
