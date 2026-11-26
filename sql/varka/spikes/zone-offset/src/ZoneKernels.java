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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * What row 657's first kernels cost a row, beyond the offset that ZoneForms timed: a whole field
 * over a zoned instant (the hour of the day), and the way back, a local time to an instant, under
 * the earlier-offset rule and under the kept-offset rule. Every form is compared with java.time
 * on every row. Run by run.sh kernels; one zone, as ZoneForms.
 */
public class ZoneKernels {
  static final VectorSpecies<Long> S = LongVector.SPECIES_PREFERRED;
  static final int N = Integer.getInteger("n", 4096);
  static final long MICROS = 1_000_000L;
  static final long DAY = 86400L * MICROS;
  static final long HOUR = 3600L * MICROS;
  static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
  static final ZoneRules RULES = ZONE.getRules();
  /** The bits of 2^52 as a double: OR-ed over a long below 2^52, they make that long a double. */
  static final long TWO_52_BITS = 0x4330000000000000L;
  static final double TWO_52 = 0x1p52;
  static long sink;
  static boolean failed;

  /**
   * The changes of the zone between two instants: each change's instant, the offsets on its two
   * sides, and, on the local time line, the local time from which the later offset holds under
   * the earlier-offset rule ({@code local}) and the first local time of an overlap
   * ({@code overlap}, which is {@code local} itself for a gap, so that no local time is inside).
   */
  record Steps(long base, long[] at, long[] delta, long[] local, long[] overlap, long[] after) {
    static Steps of(long least, long greatest) {
      var list = new ArrayList<ZoneOffsetTransition>();
      Instant from = Instant.ofEpochSecond(Math.floorDiv(least, MICROS));
      for (ZoneOffsetTransition tr = RULES.nextTransition(from);
          tr != null && tr.getInstant().getEpochSecond() * MICROS <= greatest;
          tr = RULES.nextTransition(tr.getInstant())) {
        list.add(tr);
      }
      int n = list.size();
      long[] at = new long[n];
      long[] delta = new long[n];
      long[] local = new long[n];
      long[] overlap = new long[n];
      long[] after = new long[n];
      for (int i = 0; i < n; i++) {
        long before = list.get(i).getOffsetBefore().getTotalSeconds() * MICROS;
        after[i] = list.get(i).getOffsetAfter().getTotalSeconds() * MICROS;
        at[i] = list.get(i).getInstant().getEpochSecond() * MICROS;
        delta[i] = after[i] - before;
        local[i] = at[i] + Math.max(before, after[i]);
        overlap[i] = after[i] < before ? at[i] + after[i] : local[i];
      }
      return new Steps(RULES.getOffset(from).getTotalSeconds() * MICROS, at, delta, local,
          overlap, after);
    }

    /** These steps with {@code k} steps: the missing ones are changes that never come. */
    Steps padded(int k) {
      long[] never = new long[k];
      Arrays.fill(never, Long.MAX_VALUE);
      long[] zero = new long[k];
      return new Steps(base, pad(at, never), pad(delta, zero), pad(local, never),
          pad(overlap, never), pad(after, zero));
    }

    private static long[] pad(long[] values, long[] filler) {
      long[] out = filler.clone();
      System.arraycopy(values, 0, out, 0, values.length);
      return out;
    }
  }

  // ---- the hour of the day ----

  /** The hour as Spark's row engine reads it ({@code DateTimeUtils.getHours}). */
  static void javaTimeHour(long[] in, long[] out) {
    for (int i = 0; i < in.length; i++) {
      Instant instant = Instant.ofEpochSecond(
          Math.floorDiv(in[i], MICROS), Math.floorMod(in[i], MICROS) * 1000);
      out[i] = instant.atZone(ZONE).toLocalDateTime().getHour();
    }
  }

  /** A long from 0 to 2^52 as a double, with no conversion instruction. */
  static DoubleVector toDouble(LongVector v) {
    return v.or(TWO_52_BITS).reinterpretAsDoubles().sub(TWO_52);
  }

  /** A whole double from 0 to 2^52 as a long, with no conversion instruction. */
  static LongVector toLong(DoubleVector d) {
    return d.add(TWO_52).reinterpretAsLongs().and((1L << 52) - 1);
  }

  /**
   * The whole quotient of a positive long by {@code b}, in doubles: the quotient less a half
   * rounds to the whole number below it when the double takes 2^52, since a quotient is a whole
   * number or at least {@code 1 / b} from the next one. The half is short by {@code 0.5 / b}, so
   * that a whole quotient rounds to itself and the largest fraction still rounds down.
   *
   * <p>This holds on the probe's rows, the local times of 2025, and not on every long: a quotient
   * of zero comes out wrong, and so does a value late in this century, where {@code 0.5 / b} is
   * under the quotient's last bit. It prices the shape of a kernel's division; a kernel takes the
   * engine's proved lowering.
   */
  static LongVector floorDiv(LongVector v, double b) {
    return toLong(toDouble(v).div(b).sub(0.5 - 0.5 / b));
  }

  /**
   * The hour of a local time of 2025: its whole hours since the epoch, less their whole days
   * times 24. The Vector API has no floor and, with AVX2, no long division or conversion, so each
   * quotient is {@link #floorDiv}, inside the range that method names; the probe's check against
   * java.time on every row is what holds it.
   */
  static LongVector hourOf(LongVector local) {
    LongVector hours = floorDiv(local, (double) HOUR);
    LongVector days = floorDiv(hours, 24.0);
    LongVector days24 = days.lanewise(VectorOperators.LSHL, 4)
        .add(days.lanewise(VectorOperators.LSHL, 3));
    return hours.sub(days24);
  }

  static void hourOneOffset(long[] in, long[] out, long offset) {
    int i = 0;
    for (; i < S.loopBound(in.length); i += S.length()) {
      hourOf(LongVector.fromArray(S, in, i).add(offset)).intoArray(out, i);
    }
    for (; i < in.length; i++) {
      out[i] = Math.floorMod(in[i] + offset, DAY) / HOUR;
    }
  }

  static void hourStepsOf2(long[] in, long[] out, Steps s) {
    LongVector base = LongVector.broadcast(S, s.base);
    LongVector at0 = LongVector.broadcast(S, s.at[0]);
    LongVector at1 = LongVector.broadcast(S, s.at[1]);
    LongVector delta0 = LongVector.broadcast(S, s.delta[0]);
    LongVector delta1 = LongVector.broadcast(S, s.delta[1]);
    int i = 0;
    for (; i < S.loopBound(in.length); i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      LongVector offset = base
          .add(delta0, v.compare(VectorOperators.GE, at0))
          .add(delta1, v.compare(VectorOperators.GE, at1));
      hourOf(v.add(offset)).intoArray(out, i);
    }
    for (; i < in.length; i++) {
      out[i] = Math.floorMod(in[i] + toLocalOffset(in[i], s), DAY) / HOUR;
    }
  }

  /** The same with no mask: a step is a subtract, a shift and an AND. */
  static void hourStepsOf2NoMask(long[] in, long[] out, Steps s) {
    LongVector base = LongVector.broadcast(S, s.base);
    LongVector at0 = LongVector.broadcast(S, s.at[0] - 1);
    LongVector at1 = LongVector.broadcast(S, s.at[1] - 1);
    LongVector delta0 = LongVector.broadcast(S, s.delta[0]);
    LongVector delta1 = LongVector.broadcast(S, s.delta[1]);
    int i = 0;
    for (; i < S.loopBound(in.length); i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      LongVector offset = base
          .add(at0.sub(v).lanewise(VectorOperators.ASHR, 63).and(delta0))
          .add(at1.sub(v).lanewise(VectorOperators.ASHR, 63).and(delta1));
      hourOf(v.add(offset)).intoArray(out, i);
    }
    for (; i < in.length; i++) {
      out[i] = Math.floorMod(in[i] + toLocalOffset(in[i], s), DAY) / HOUR;
    }
  }

  static long toLocalOffset(long instant, Steps s) {
    long offset = s.base;
    for (int k = 0; k < s.at.length; k++) {
      offset += instant >= s.at[k] ? s.delta[k] : 0;
    }
    return offset;
  }

  // ---- a local time to an instant ----

  static LocalDateTime localOf(long micros) {
    return LocalDateTime.ofEpochSecond(Math.floorDiv(micros, MICROS),
        (int) Math.floorMod(micros, MICROS) * 1000, ZoneOffset.UTC);
  }

  static long microsOf(Instant instant) {
    return instant.getEpochSecond() * MICROS + instant.getNano() / 1000;
  }

  /** {@code LocalDateTime.atZone}: the earlier offset in an overlap, a gap skipped forward. */
  static void javaTimeEarlier(long[] local, long[] out) {
    for (int i = 0; i < local.length; i++) {
      out[i] = microsOf(localOf(local[i]).atZone(ZONE).toInstant());
    }
  }

  /** {@code ZonedDateTime.ofLocal}: in an overlap the offset the value had, if it is valid. */
  static void javaTimeKept(long[] local, long[] kept, long[] out) {
    for (int i = 0; i < local.length; i++) {
      ZoneOffset preferred = ZoneOffset.ofTotalSeconds((int) (kept[i] / MICROS));
      out[i] = microsOf(ZonedDateTime.ofLocal(localOf(local[i]), ZONE, preferred).toInstant());
    }
  }

  /** The earlier-offset rule in steps of 2: the same steps as to a local time, at {@code local}. */
  static void earlierStepsOf2(long[] in, long[] out, Steps s) {
    LongVector base = LongVector.broadcast(S, s.base);
    LongVector at0 = LongVector.broadcast(S, s.local[0]);
    LongVector at1 = LongVector.broadcast(S, s.local[1]);
    LongVector delta0 = LongVector.broadcast(S, s.delta[0]);
    LongVector delta1 = LongVector.broadcast(S, s.delta[1]);
    int i = 0;
    for (; i < S.loopBound(in.length); i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      LongVector offset = base
          .add(delta0, v.compare(VectorOperators.GE, at0))
          .add(delta1, v.compare(VectorOperators.GE, at1));
      v.sub(offset).intoArray(out, i);
    }
    for (; i < in.length; i++) {
      out[i] = in[i] - toInstantOffset(in[i], Long.MIN_VALUE, s);
    }
  }

  /**
   * The kept-offset rule in steps of 2. A step's later offset holds from {@code local}, or, for
   * a row whose kept offset is that later offset, already from the overlap's first local time:
   * so a step is two compares, an AND and an OR of masks, and one masked add.
   */
  static void keptStepsOf2(long[] in, long[] kept, long[] out, Steps s) {
    LongVector base = LongVector.broadcast(S, s.base);
    int i = 0;
    for (; i < S.loopBound(in.length); i += S.length()) {
      LongVector v = LongVector.fromArray(S, in, i);
      LongVector k = LongVector.fromArray(S, kept, i);
      LongVector offset = base;
      for (int step = 0; step < 2; step++) {
        var late = v.compare(VectorOperators.GE, s.local[step]);
        var inOverlap = v.compare(VectorOperators.GE, s.overlap[step])
            .and(k.compare(VectorOperators.EQ, s.after[step]));
        offset = offset.add(s.delta[step], late.or(inOverlap));
      }
      v.sub(offset).intoArray(out, i);
    }
    for (; i < in.length; i++) {
      out[i] = in[i] - toInstantOffset(in[i], kept[i], s);
    }
  }

  static long toInstantOffset(long local, long kept, Steps s) {
    long offset = s.base;
    for (int k = 0; k < s.local.length; k++) {
      boolean late = local >= s.local[k] || (local >= s.overlap[k] && kept == s.after[k]);
      offset += late ? s.delta[k] : 0;
    }
    return offset;
  }

  // ---- the data and the clock ----

  static long micros(int year, int month, int day) {
    return LocalDate.of(year, month, day).toEpochDay() * DAY;
  }

  static long[] between(Random random, long from, long until) {
    long[] in = new long[N];
    for (int i = 0; i < N; i++) {
      in[i] = from + (long) (random.nextDouble() * (until - from));
    }
    return in;
  }

  /** Half the rows of {@code in} moved to within three hours of one of the changes. */
  static long[] nearChanges(Random random, long[] in, long[] changes) {
    long[] out = in.clone();
    for (int i = 0; i < N; i += 2) {
      long change = changes[random.nextInt(changes.length)];
      out[i] = change - 3 * HOUR + (long) (random.nextDouble() * 6 * HOUR);
    }
    return out;
  }

  static void time(String form, String data, long[] out, long[] expected, Runnable run) {
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
    if (expected != null) {
      Arrays.fill(out, Long.MIN_VALUE);
      run.run();
      int wrong = 0;
      for (int i = 0; i < N; i++) {
        wrong += out[i] != expected[i] ? 1 : 0;
      }
      check = wrong == 0 ? "every row equals java.time" : wrong + " ROWS DIFFER";
      failed |= wrong != 0;
    }
    System.out.printf("%-40s %-30s best %7.3f  median %7.3f ns a row  %s%n",
        form, data, window[0], window[2], check);
  }

  public static void main(String[] args) {
    System.out.printf("# %s, %d lanes of 64 bits, batches of %d rows%n", ZONE, S.length(), N);
    var random = new Random(42);
    long[] out = new long[N];
    long from = micros(2025, 1, 1);
    long until = micros(2026, 1, 1);
    Steps found = Steps.of(from, until - 1);
    if (found.at.length != 2) {
      throw new IllegalStateException("the year has not two changes in " + ZONE);
    }
    Steps steps = found.padded(2);

    // The hour of the day: a year of instants in no order, half of them near a change.
    long[] year = nearChanges(random, between(random, from, until), steps.at);
    long[] hours = new long[N];
    javaTimeHour(year, hours);
    time("java.time, the hour of the day", "a year, half near a change", out, null,
        () -> javaTimeHour(year, out));
    time("hour, steps of 2", "a year, half near a change", out, hours,
        () -> hourStepsOf2(year, out, steps));
    time("hour, steps of 2, no mask", "a year, half near a change", out, hours,
        () -> hourStepsOf2NoMask(year, out, steps));
    long[] week = between(random, micros(2025, 7, 7), micros(2025, 7, 14));
    long[] weekHours = new long[N];
    javaTimeHour(week, weekHours);
    long summer = RULES.getOffset(Instant.ofEpochSecond(week[0] / MICROS)).getTotalSeconds()
        * MICROS;
    time("hour, one offset", "a week of July", out, weekHours,
        () -> hourOneOffset(week, out, summer));

    // A local time to an instant: a year of local times in no order, half of them within three
    // hours of a gap or an overlap, so that both are met in every batch.
    long[] local = nearChanges(random, between(random, from, until), steps.local);
    long[] earlier = new long[N];
    javaTimeEarlier(local, earlier);
    time("java.time, LocalDateTime.atZone", "a year, half near a change", out, null,
        () -> javaTimeEarlier(local, out));
    time("to an instant, earlier offset, steps of 2", "a year, half near a change", out, earlier,
        () -> earlierStepsOf2(local, out, steps));

    // The kept offset: each row keeps one of the zone's two offsets of the year, at random, as
    // a value moved by months or days keeps the offset it had.
    long[] kept = new long[N];
    for (int i = 0; i < N; i++) {
      kept[i] = random.nextBoolean() ? steps.base : steps.base + steps.delta[0];
    }
    long[] keptExpected = new long[N];
    javaTimeKept(local, kept, keptExpected);
    time("java.time, ZonedDateTime.ofLocal", "a year, half near a change", out, null,
        () -> javaTimeKept(local, kept, out));
    time("to an instant, kept offset, steps of 2", "a year, half near a change", out,
        keptExpected, () -> keptStepsOf2(local, kept, out, steps));
    int differ = 0;
    for (int i = 0; i < N; i++) {
      differ += earlier[i] != keptExpected[i] ? 1 : 0;
    }
    System.out.printf("# the kept offset changes the instant of %d of %d rows%n", differ, N);
    sink ^= out[0];
    if (failed) {
      System.exit(1);
    }
  }
}
