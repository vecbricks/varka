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

import java.time.LocalDate;

/**
 * The scalar denominator of every Varka calendar speedup, on whichever JDK is running it.
 *
 * <p>Why this file exists outside the build. Every Varka number is a ratio, and the thing it is
 * a ratio <i>to</i> is this loop: {@code LocalDate.ofEpochDay(d).getYear()}, which is exactly
 * what {@code DateTimeUtils.getYear} does and therefore what Spark's row path costs per row.
 * The fork's own build targets Java 25 - the emitter needs {@code java.lang.classfile}, which
 * is JDK 24 and later - so nothing in it can run on the Java 17 that Apache Spark still builds
 * with by default, and the parity benchmark cannot report a Java 17 column. This loop can:
 * it is plain {@code java.time} and plain arrays, so both JDKs compile and run it, and it is
 * the only part of the comparison that exists on both.
 *
 * <p>What it does <b>not</b> measure: the Varka kernels (unavailable before JDK 24), Arrow
 * access, the row boundary, or anything else in a query. Read it as the per-row cost of the
 * calendar arithmetic alone, and read a Varka ratio quoted against it the same way.
 *
 * <p>Methodology follows {@code VARKA-14.md} 2.1, as the rest of the project's numbers do:
 * five measured iterations over two-second windows after a two-second warm-up, on an otherwise
 * idle machine, reported by the minimum. The data matches the parity benchmark's: one million
 * rows of {@code i % 20000 - 10000} days, walked in 4096-row chunks.
 *
 * <pre>{@code
 *   /usr/lib/jvm/java-25-openjdk-amd64/bin/java sql/varka/baselines/ChronoScalarBaseline.java
 *   /usr/lib/jvm/java-17-openjdk-amd64/bin/java sql/varka/baselines/ChronoScalarBaseline.java
 * }</pre>
 */
public final class ChronoScalarBaseline {

  private static final int ROWS = 1_000_000;
  private static final int CHUNK = 4096;
  private static final int REPEATS = 20;
  private static final long WARMUP_NANOS = 2_000_000_000L;
  private static final long WINDOW_NANOS = 2_000_000_000L;
  private static final int ITERATIONS = 5;

  private ChronoScalarBaseline() {}

  public static void main(String[] args) {
    int[] days = new int[ROWS];
    for (int i = 0; i < ROWS; i++) {
      days[i] = i % 20000 - 10000;
    }
    int[] out = new int[ROWS];

    System.out.println("java.version = " + System.getProperty("java.version")
        + ", vm = " + System.getProperty("java.vm.name"));
    report("year (LocalDate.ofEpochDay().getYear())", days, out, Field.YEAR);
    report("year+month+day+quarter", days, out, Field.ALL);
  }

  private enum Field { YEAR, ALL }

  private static void report(String label, int[] days, int[] out, Field field) {
    long deadline = System.nanoTime() + WARMUP_NANOS;
    while (System.nanoTime() < deadline) {
      pass(days, out, field);
    }
    long best = Long.MAX_VALUE;
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      long windowEnd = System.nanoTime() + WINDOW_NANOS;
      long passes = 0;
      long start = System.nanoTime();
      while (System.nanoTime() < windowEnd) {
        pass(days, out, field);
        passes++;
      }
      long elapsed = System.nanoTime() - start;
      long perPass = elapsed / Math.max(passes, 1);
      best = Math.min(best, perPass);
    }
    long rowsPerPass = (long) ROWS * REPEATS;
    double rate = rowsPerPass / (best / 1e9) / 1e6;
    double perRow = (double) best / rowsPerPass;
    System.out.printf("%-42s %8.1f M rows/s   %5.2f ns/row%n", label, rate, perRow);
    // Consume the output so nothing above can be folded away.
    if (out[ROWS - 1] == Integer.MIN_VALUE) {
      throw new IllegalStateException("unreachable");
    }
  }

  /** One pass over the whole buffer, in chunks, as a batched engine would walk it. */
  private static void pass(int[] days, int[] out, Field field) {
    for (int repeat = 0; repeat < REPEATS; repeat++) {
      int done = 0;
      while (done < ROWS) {
        int n = Math.min(CHUNK, ROWS - done);
        if (field == Field.YEAR) {
          for (int i = done; i < done + n; i++) {
            out[i] = LocalDate.ofEpochDay(days[i]).getYear();
          }
        } else {
          for (int i = done; i < done + n; i++) {
            LocalDate date = LocalDate.ofEpochDay(days[i]);
            out[i] = date.getYear() + date.getMonthValue() + date.getDayOfMonth()
                + (date.getMonthValue() + 2) / 3;
          }
        }
        done += n;
      }
    }
  }
}
