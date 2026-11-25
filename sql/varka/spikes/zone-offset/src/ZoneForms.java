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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * What each form of `m9/PLAN.md` 2.2 costs to turn a batch of instants into local times: hand
 * loops over one batch of microseconds, each timed in five two-second windows after a two-second
 * warm-up and checked against `java.time` on every row. A form writes `instant + offset`; the
 * `java.time` rows are what the row engine pays for the same answer, and for a field.
 */
public class ZoneForms {
  static final VectorSpecies<Long> S = LongVector.SPECIES_PREFERRED;
  static final VectorSpecies<Integer> SI =
      VectorSpecies.of(int.class, VectorShape.forBitSize(S.vectorBitSize() / 2));
  static final int N = Integer.getInteger("n", 4096);
  static final long MICROS = 1_000_000L;
  static final ZoneId ZONE = ZoneId.of(System.getProperty("zone", "America/Los_Angeles"));
  static final ZoneRules RULES = ZONE.getRules();
  /** A bucket of the table is 2^39 microseconds, 6.4 days: at most one change falls in one. */
  static final int BUCKET = 39;
  static final long BASE = LocalDate.of(1900, 1, 1).toEpochDay() * 86400L * MICROS;

  /** The zone's changes from 1900 to 2100, in microseconds: instants and the offset after. */
  static final long[] CHANGE;
  static final long[] AFTER;
  static final long FIRST;
  static final long[] TABLE_START;
  static final long[] TABLE_CHANGE;
  static final long[] TABLE_END;

  static {
    List<ZoneOffsetTransition> list = new ArrayList<>();
    Instant at = Instant.ofEpochSecond(BASE / MICROS);
    long end = LocalDate.of(2100, 1, 1).toEpochDay() * 86400L;
    FIRST = RULES.getOffset(at).getTotalSeconds() * MICROS;
    for (ZoneOffsetTransition tr = RULES.nextTransition(at);
        tr != null && tr.getInstant().getEpochSecond() < end;
        tr = RULES.nextTransition(tr.getInstant())) {
      list.add(tr);
    }
    CHANGE = new long[list.size()];
    AFTER = new long[list.size()];
    for (int i = 0; i < CHANGE.length; i++) {
      CHANGE[i] = list.get(i).getInstant().getEpochSecond() * MICROS;
      AFTER[i] = list.get(i).getOffsetAfter().getTotalSeconds() * MICROS;
    }
    int buckets = (int) ((end * MICROS - BASE) >>> BUCKET) + 2;
    TABLE_START = new long[buckets];
    TABLE_CHANGE = new long[buckets];
    TABLE_END = new long[buckets];
    for (int b = 0, i = 0; b < buckets; b++) {
      long from = BASE + ((long) b << BUCKET);
      TABLE_START[b] = i == 0 ? FIRST : AFTER[i - 1];
      TABLE_CHANGE[b] = Long.MAX_VALUE;
      TABLE_END[b] = TABLE_START[b];
      if (i < CHANGE.length && CHANGE[i] < from + (1L << BUCKET)) {
        TABLE_CHANGE[b] = CHANGE[i];
        TABLE_END[b] = AFTER[i++];
        if (i < CHANGE.length && CHANGE[i] < from + (1L << BUCKET)) {
          throw new IllegalStateException("two changes in one bucket of " + ZONE);
        }
      }
    }
  }

  /** The steps a batch between {@code least} and {@code greatest} needs. */
  record Steps(long base, long[] at, long[] delta) {
    static Steps of(long least, long greatest) {
      int first = 0;
      while (first < CHANGE.length && CHANGE[first] <= least) {
        first++;
      }
      int last = first;
      while (last < CHANGE.length && CHANGE[last] <= greatest) {
        last++;
      }
      long[] at = Arrays.copyOfRange(CHANGE, first, last);
      long[] delta = new long[at.length];
      for (int j = 0; j < at.length; j++) {
        delta[j] = AFTER[first + j] - (first + j == 0 ? FIRST : AFTER[first + j - 1]);
      }
      return new Steps(first == 0 ? FIRST : AFTER[first - 1], at, delta);
    }
  }

  static void javaTime(long[] in, long[] out) {
    for (int i = 0; i < in.length; i++) {
      Instant instant = Instant.ofEpochSecond(Math.floorDiv(in[i], MICROS));
      out[i] = in[i] + RULES.getOffset(instant).getTotalSeconds() * MICROS;
    }
  }

  /** The hour of the day as Spark's row engine reads it (`DateTimeUtils.getHours`). */
  static void javaTimeHour(long[] in, long[] out) {
    for (int i = 0; i < in.length; i++) {
      Instant instant = Instant.ofEpochSecond(
          Math.floorDiv(in[i], MICROS), Math.floorMod(in[i], MICROS) * 1000);
      out[i] = instant.atZone(ZONE).toLocalDateTime().getHour();
    }
  }

  static void oneOffset(long[] in, long[] out, long offset) {
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += S.length()) {
      LongVector.fromArray(S, in, i).add(offset).intoArray(out, i);
    }
    for (; i < in.length; i++) {
      out[i] = in[i] + offset;
    }
  }

  /**
   * A compare and a masked add for each change the batch spans, the changes read in a loop: what
   * a kernel that is not written for its number of changes pays.
   */
  static void stepsInLoop(long[] in, long[] out, Steps s) {
    int k = s.at.length;
    LongVector[] at = new LongVector[k];
    LongVector[] delta = new LongVector[k];
    for (int j = 0; j < k; j++) {
      at[j] = LongVector.broadcast(S, s.at[j]);
      delta[j] = LongVector.broadcast(S, s.delta[j]);
    }
    LongVector base = LongVector.broadcast(S, s.base);
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      LongVector offset = base;
      for (int j = 0; j < k; j++) {
        offset = offset.add(delta[j], v.compare(VectorOperators.GE, at[j]));
      }
      v.add(offset).intoArray(out, i);
    }
    for (; i < in.length; i++) {
      out[i] = in[i] + scalarOffset(in[i], s);
    }
  }

  static long scalarOffset(long instant, Steps s) {
    long offset = s.base;
    for (int j = 0; j < s.at.length; j++) {
      offset += instant >= s.at[j] ? s.delta[j] : 0;
    }
    return offset;
  }

  /** The broadcast change points and deltas of {@code s}, and its base offset last. */
  static LongVector[][] broadcast(Steps s) {
    int k = s.at.length;
    LongVector[] at = new LongVector[k];
    LongVector[] delta = new LongVector[k];
    for (int j = 0; j < k; j++) {
      at[j] = LongVector.broadcast(S, s.at[j]);
      delta[j] = LongVector.broadcast(S, s.delta[j]);
    }
    return new LongVector[][] {at, delta, {LongVector.broadcast(S, s.base)}};
  }

  static LongVector one(LongVector offset, LongVector v, LongVector[][] b, int j) {
    return offset.add(b[1][j], v.compare(VectorOperators.GE, b[0][j]));
  }

  static LongVector two(LongVector offset, LongVector v, LongVector[][] b, int j) {
    return one(one(offset, v, b, j), v, b, j + 1);
  }

  static LongVector four(LongVector offset, LongVector v, LongVector[][] b, int j) {
    return two(two(offset, v, b, j), v, b, j + 2);
  }

  static LongVector eight(LongVector offset, LongVector v, LongVector[][] b, int j) {
    return four(four(offset, v, b, j), v, b, j + 4);
  }

  /**
   * The steps written out for 1, 2, 4, 8 or 16 changes, as an emitted kernel would have them:
   * each loop below is one chain of compares and masked adds with no inner loop.
   */
  static void stepsWrittenOut(long[] in, long[] out, Steps s) {
    LongVector[][] b = broadcast(s);
    LongVector base = b[2][0];
    int up = S.loopBound(in.length);
    int i = 0;
    switch (s.at.length) {
      case 1 -> {
        for (; i < up; i += S.length()) {
          LongVector v = LongVector.fromArray(S, in, i);
          v.add(one(base, v, b, 0)).intoArray(out, i);
        }
      }
      case 2 -> {
        for (; i < up; i += S.length()) {
          LongVector v = LongVector.fromArray(S, in, i);
          v.add(two(base, v, b, 0)).intoArray(out, i);
        }
      }
      case 4 -> {
        for (; i < up; i += S.length()) {
          LongVector v = LongVector.fromArray(S, in, i);
          v.add(four(base, v, b, 0)).intoArray(out, i);
        }
      }
      case 8 -> {
        for (; i < up; i += S.length()) {
          LongVector v = LongVector.fromArray(S, in, i);
          v.add(eight(base, v, b, 0)).intoArray(out, i);
        }
      }
      case 16 -> {
        for (; i < up; i += S.length()) {
          LongVector v = LongVector.fromArray(S, in, i);
          v.add(eight(eight(base, v, b, 0), v, b, 8)).intoArray(out, i);
        }
      }
      default -> throw new IllegalArgumentException(s.at.length + " changes are not written out");
    }
    for (; i < in.length; i++) {
      out[i] = in[i] + scalarOffset(in[i], s);
    }
  }

  /**
   * Two steps, carried from the batch before, with the check that every instant is in the range
   * they cover in the same loop, as the batch's least and greatest instant; false means the
   * batch must run again with steps of its own.
   */
  static boolean stepsCarriedByRange(long[] in, long[] out, Steps s, long from, long until) {
    LongVector[][] b = broadcast(s);
    LongVector base = b[2][0];
    LongVector least = LongVector.broadcast(S, Long.MAX_VALUE);
    LongVector greatest = LongVector.broadcast(S, Long.MIN_VALUE);
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      least = least.min(v);
      greatest = greatest.max(v);
      v.add(two(base, v, b, 0)).intoArray(out, i);
    }
    long lo = least.reduceLanes(VectorOperators.MIN);
    long hi = greatest.reduceLanes(VectorOperators.MAX);
    for (; i < in.length; i++) {
      lo = Math.min(lo, in[i]);
      hi = Math.max(hi, in[i]);
      out[i] = in[i] + scalarOffset(in[i], s);
    }
    return lo >= from && hi < until;
  }

  /**
   * {@link #stepsCarriedByRange} with its check as one OR a vector: every instant is in
   * {@code [from, from + 2^bits)} exactly when the OR of every {@code instant - from} has no bit
   * at or above {@code bits}.
   */
  static boolean stepsCarriedByOr(long[] in, long[] out, Steps s, long from, int bits) {
    LongVector[][] b = broadcast(s);
    LongVector base = b[2][0];
    LongVector lo = LongVector.broadcast(S, from);
    LongVector seen = LongVector.zero(S);
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      seen = seen.or(v.sub(lo));
      v.add(two(base, v, b, 0)).intoArray(out, i);
    }
    long or = seen.reduceLanes(VectorOperators.OR);
    for (; i < in.length; i++) {
      or |= in[i] - from;
      out[i] = in[i] + scalarOffset(in[i], s);
    }
    return or >>> bits == 0;
  }

  /**
   * Two steps with no mask, for the species of two 64-bit lanes, at which this JVM compiles no
   * mask (`skills/the-jit.md`): `at - 1 - instant` is negative exactly when the instant is at or
   * past the change, and its sign, shifted across the lane, selects the delta. The instants must
   * be near enough to the changes for the difference not to overflow, which the OR check shows.
   */
  static boolean stepsNoMask(long[] in, long[] out, Steps s, long from, int bits) {
    LongVector base = LongVector.broadcast(S, s.base);
    LongVector at0 = LongVector.broadcast(S, s.at[0] - 1);
    LongVector at1 = LongVector.broadcast(S, s.at[1] - 1);
    LongVector delta0 = LongVector.broadcast(S, s.delta[0]);
    LongVector delta1 = LongVector.broadcast(S, s.delta[1]);
    LongVector lo = LongVector.broadcast(S, from);
    LongVector seen = LongVector.zero(S);
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      seen = seen.or(v.sub(lo));
      LongVector offset = base
          .add(at0.sub(v).lanewise(VectorOperators.ASHR, 63).and(delta0))
          .add(at1.sub(v).lanewise(VectorOperators.ASHR, 63).and(delta1));
      v.add(offset).intoArray(out, i);
    }
    long or = seen.reduceLanes(VectorOperators.OR);
    for (; i < in.length; i++) {
      or |= in[i] - from;
      out[i] = in[i] + scalarOffset(in[i], s);
    }
    return or >>> bits == 0;
  }

  /** The pass a form pays when it must know the batch's range before the kernel runs. */
  static long leastAndGreatest(long[] in) {
    LongVector least = LongVector.broadcast(S, Long.MAX_VALUE);
    LongVector greatest = LongVector.broadcast(S, Long.MIN_VALUE);
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      least = least.min(v);
      greatest = greatest.max(v);
    }
    long lo = least.reduceLanes(VectorOperators.MIN);
    long hi = greatest.reduceLanes(VectorOperators.MAX);
    for (; i < in.length; i++) {
      lo = Math.min(lo, in[i]);
      hi = Math.max(hi, in[i]);
    }
    return lo ^ hi;
  }

  /** Three gathers from the zone's table, indexed by the instant's bucket. */
  static void table(long[] in, long[] out, int[] index) {
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      ((IntVector) v.sub(BASE).lanewise(VectorOperators.LSHR, BUCKET)
          .convertShape(VectorOperators.L2I, SI, 0)).intoArray(index, 0);
      LongVector start = LongVector.fromArray(S, TABLE_START, 0, index, 0);
      LongVector change = LongVector.fromArray(S, TABLE_CHANGE, 0, index, 0);
      LongVector end = LongVector.fromArray(S, TABLE_END, 0, index, 0);
      v.add(start.blend(end, v.compare(VectorOperators.GE, change))).intoArray(out, i);
    }
    for (; i < in.length; i++) {
      int b = (int) ((in[i] - BASE) >>> BUCKET);
      out[i] = in[i] + (in[i] >= TABLE_CHANGE[b] ? TABLE_END[b] : TABLE_START[b]);
    }
  }

  static long[] instants(Random random, long from, long until) {
    long[] in = new long[N];
    for (int i = 0; i < N; i++) {
      in[i] = from + (long) (random.nextDouble() * (until - from));
    }
    return in;
  }

  static long micros(int year, int month, int day) {
    return LocalDate.of(year, month, day).toEpochDay() * 86400L * MICROS;
  }

  static void time(String form, String data, int changes, long[] in, long[] out, Runnable run,
      boolean local) {
    for (long until = System.nanoTime() + 2_000_000_000L; System.nanoTime() < until; ) {
      run.run();
    }
    double[] window = new double[5];
    for (int w = 0; w < window.length; w++) {
      long calls = 0;
      long from = System.nanoTime();
      long now;
      do {
        run.run();
        calls++;
        now = System.nanoTime();
      } while (now - from < 2_000_000_000L);
      window[w] = (now - from) / (double) (calls * N);
    }
    Arrays.sort(window);
    String check = "";
    if (local) {
      long[] expected = new long[N];
      javaTime(in, expected);
      int wrong = 0;
      for (int i = 0; i < N; i++) {
        wrong += out[i] != expected[i] ? 1 : 0;
      }
      check = wrong == 0 ? "every row equals java.time" : wrong + " ROWS DIFFER";
    }
    System.out.printf("%-34s %-28s changes %2d  best %7.3f  median %7.3f ns a row  %s%n",
        form, data, changes, window[0], window[2], check);
  }

  public static void main(String[] args) {
    System.out.printf("# %s, %d lanes of 64 bits, batches of %d rows%n", ZONE, S.length(), N);
    Random random = new Random(42);
    long[] out = new long[N];
    int[] index = new int[SI.length()];

    long[] week = instants(random, micros(2025, 7, 7), micros(2025, 7, 14));
    long[] year = instants(random, micros(2025, 1, 1), micros(2026, 1, 1));
    long[] decade = instants(random, micros(2016, 1, 1), micros(2026, 1, 1));
    Steps weekSteps = Steps.of(micros(2025, 7, 7), micros(2025, 7, 14));
    Steps yearSteps = Steps.of(micros(2025, 1, 1), micros(2026, 1, 1));

    time("java.time, the offset", "a year, no order", 2, year, out,
        () -> javaTime(year, out), true);
    time("java.time, the hour of the day", "a year, no order", 2, year, out,
        () -> javaTimeHour(year, out), false);
    time("one offset", "a week, no order", 0, week, out,
        () -> oneOffset(week, out, weekSteps.base), true);
    time("the pass for least and greatest", "a year, no order", 2, year, out,
        () -> leastAndGreatest(year), false);
    time("steps carried, checked by range", "a year, no order", 2, year, out,
        () -> stepsCarriedByRange(year, out, yearSteps, micros(2025, 1, 1), micros(2026, 1, 1)),
        true);
    // 2^45 microseconds are 407 days: the year of 2025 from its first instant.
    time("steps carried, checked by an OR", "a year, no order", 2, year, out,
        () -> stepsCarriedByOr(year, out, yearSteps, micros(2025, 1, 1), 45), true);
    time("steps carried, no mask, by an OR", "a year, no order", 2, year, out,
        () -> stepsNoMask(year, out, yearSteps, micros(2025, 1, 1), 45), true);

    // Batches that span exactly k changes: from a month before the first of them to a month
    // after the last, counted from the first change of 2016.
    int first = 0;
    while (CHANGE[first] < micros(2016, 1, 1)) {
      first++;
    }
    long month = 30 * 86400L * MICROS;
    for (int k : new int[] {1, 2, 4, 8, 16}) {
      long from = CHANGE[first] - month;
      long until = CHANGE[first + k - 1] + month;
      long[] in = instants(random, from, until);
      Steps s = Steps.of(from, until);
      time("steps, written out", (k + 1) / 2 + " years, no order", k, in, out,
          () -> stepsWrittenOut(in, out, s), true);
      if (k >= 8) {
        time("steps, read in a loop", (k + 1) / 2 + " years, no order", k, in, out,
            () -> stepsInLoop(in, out, s), true);
      }
    }

    time("table, three gathers", "a year, no order", 2, year, out,
        () -> table(year, out, index), true);
    time("table, three gathers", "ten years, no order", 20, decade, out,
        () -> table(decade, out, index), true);
  }
}
