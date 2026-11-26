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
import java.util.Random;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * How a kernel should read the zone's bucket table, for row 686's plan: the packed bucket of 16
 * bytes against ZoneForms' three arrays, and five ways to hand the gather its indices. The Vector
 * API's gather takes its indices from an int array, so a kernel stores them and the gather loads
 * them back; the arms differ in where that array is and how far the store is from the load. One
 * arm has no gather: it reads each lane's bucket with scalar loads. Every arm is compared with
 * java.time on every row. Run by run.sh tables; one zone, as ZoneForms.
 */
public class ZoneTables {
  static final VectorSpecies<Long> S = LongVector.SPECIES_PREFERRED;
  static final VectorSpecies<Integer> SI =
      VectorSpecies.of(int.class, VectorShape.forBitSize(S.vectorBitSize() / 2));
  static final int LANES = S.length();
  static final int N = Integer.getInteger("n", 4096);
  static final long MICROS = 1_000_000L;
  static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
  static final ZoneRules RULES = ZONE.getRules();
  /** A bucket is 2^39 microseconds, 6.4 days: at most one change falls in one. */
  static final int BUCKET = 39;
  static final long BASE = LocalDate.of(1900, 1, 1).toEpochDay() * 86400L * MICROS;
  /** An offset is packed in units of 64 microseconds, so that 32 bits hold 18 hours of them. */
  static final int UNIT = 6;

  static final int BUCKETS;
  /** ZoneForms' layout: the offset before, the change, the offset after, a long each. */
  static final long[] TABLE_START;
  static final long[] TABLE_CHANGE;
  static final long[] TABLE_END;
  /** The packed layout: word 0 the change, word 1 the offset before above the offset after. */
  static final long[] PACKED;
  static boolean failed;

  static {
    var list = new ArrayList<ZoneOffsetTransition>();
    Instant at = Instant.ofEpochSecond(BASE / MICROS);
    long end = LocalDate.of(2100, 1, 1).toEpochDay() * 86400L;
    long first = RULES.getOffset(at).getTotalSeconds() * MICROS;
    for (ZoneOffsetTransition tr = RULES.nextTransition(at);
        tr != null && tr.getInstant().getEpochSecond() < end;
        tr = RULES.nextTransition(tr.getInstant())) {
      list.add(tr);
    }
    BUCKETS = (int) ((end * MICROS - BASE) >>> BUCKET) + 2;
    TABLE_START = new long[BUCKETS];
    TABLE_CHANGE = new long[BUCKETS];
    TABLE_END = new long[BUCKETS];
    PACKED = new long[2 * BUCKETS];
    for (int b = 0, i = 0; b < BUCKETS; b++) {
      long from = BASE + ((long) b << BUCKET);
      long before = i == 0 ? first : list.get(i - 1).getOffsetAfter().getTotalSeconds() * MICROS;
      long change = Long.MAX_VALUE;
      long after = before;
      long next = i < list.size()
          ? list.get(i).getInstant().getEpochSecond() * MICROS : Long.MAX_VALUE;
      if (next < from + (1L << BUCKET)) {
        change = next;
        after = list.get(i++).getOffsetAfter().getTotalSeconds() * MICROS;
      }
      if ((before & 63) != 0 || (after & 63) != 0) {
        throw new IllegalStateException("an offset of " + ZONE + " is no multiple of 64 us");
      }
      TABLE_START[b] = before;
      TABLE_CHANGE[b] = change;
      TABLE_END[b] = after;
      PACKED[2 * b] = change;
      PACKED[2 * b + 1] = ((before >> UNIT) << 32) | ((after >> UNIT) & 0xFFFFFFFFL);
    }
  }

  static void javaTime(long[] in, long[] out) {
    for (int i = 0; i < in.length; i++) {
      long seconds = Math.floorDiv(in[i], MICROS);
      out[i] = in[i] + RULES.getOffset(Instant.ofEpochSecond(seconds)).getTotalSeconds() * MICROS;
    }
  }

  /** Twice the clamped bucket of each instant: the index of a bucket's first word. */
  static IntVector indices(LongVector v) {
    return ((IntVector) v.sub(BASE).lanewise(VectorOperators.LSHR, BUCKET)
        .convertShape(VectorOperators.L2I, SI, 0)).min(BUCKETS - 1)
        .lanewise(VectorOperators.LSHL, 1);
  }

  /** The local time from an instant and its bucket's two words. */
  static LongVector local(LongVector v, LongVector change, LongVector word) {
    VectorMask<Long> late = v.compare(VectorOperators.GE, change);
    return word.lanewise(VectorOperators.LSHR, 32).blend(word, late)
        .lanewise(VectorOperators.LSHL, 32).lanewise(VectorOperators.ASHR, 32 - UNIT).add(v);
  }

  static void scalarTail(long[] in, long[] out, int i) {
    for (; i < in.length; i++) {
      int b = Math.min((int) ((in[i] - BASE) >>> BUCKET), BUCKETS - 1);
      long word = PACKED[2 * b + 1];
      out[i] = in[i] + ((in[i] >= PACKED[2 * b] ? (long) (int) word : word >> 32) << UNIT);
    }
  }

  /** ZoneForms' table: three arrays, three gathers, the indices stored beside the gathers. */
  static void threeGathers(long[] in, long[] out, int[] index) {
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += LANES) {
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

  /** The packed table, the indices stored at {@code at} of {@code index} beside the gathers. */
  static void beside(long[] in, long[] out, int[] index, int at) {
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += LANES) {
      LongVector v = LongVector.fromArray(S, in, i);
      indices(v).intoArray(index, at);
      LongVector word = LongVector.fromArray(S, PACKED, 1, index, at);
      LongVector change = LongVector.fromArray(S, PACKED, 0, index, at);
      local(v, change, word).intoArray(out, i);
    }
    scalarTail(in, out, i);
  }

  /** The packed table with a new index array for each call, as a kernel's body would allocate. */
  static void besideAllocated(long[] in, long[] out) {
    beside(in, out, new int[SI.length()], 0);
  }

  /** The whole batch's indices in one pass, then the gathers in a second pass. */
  static void precomputed(long[] in, long[] out, int[] map) {
    int up = S.loopBound(in.length);
    for (int i = 0; i < up; i += LANES) {
      indices(LongVector.fromArray(S, in, i)).intoArray(map, i);
    }
    int i = 0;
    for (; i < up; i += LANES) {
      LongVector v = LongVector.fromArray(S, in, i);
      LongVector word = LongVector.fromArray(S, PACKED, 1, map, i);
      LongVector change = LongVector.fromArray(S, PACKED, 0, map, i);
      local(v, change, word).intoArray(out, i);
    }
    scalarTail(in, out, i);
  }

  /** The indices of {@code groups} lane groups stored first, then their gathers. */
  static void batched(long[] in, long[] out, int[] map, int groups) {
    int up = S.loopBound(in.length);
    int i = 0;
    while (i < up) {
      int stop = Math.min(up, i + groups * LANES);
      for (int j = i, at = 0; j < stop; j += LANES, at += LANES) {
        indices(LongVector.fromArray(S, in, j)).intoArray(map, at);
      }
      for (int at = 0; i < stop; i += LANES, at += LANES) {
        LongVector v = LongVector.fromArray(S, in, i);
        LongVector word = LongVector.fromArray(S, PACKED, 1, map, at);
        LongVector change = LongVector.fromArray(S, PACKED, 0, map, at);
        local(v, change, word).intoArray(out, i);
      }
    }
    scalarTail(in, out, i);
  }

  /** The store of the next group's indices issued after this group's gathers: two arrays. */
  static void pipelined(long[] in, long[] out, int[] first, int[] second) {
    int up = S.loopBound(in.length);
    int[] now = first;
    int[] next = second;
    int i = 0;
    if (up > 0) {
      indices(LongVector.fromArray(S, in, 0)).intoArray(now, 0);
    }
    for (; i < up; i += LANES) {
      LongVector v = LongVector.fromArray(S, in, i);
      LongVector word = LongVector.fromArray(S, PACKED, 1, now, 0);
      LongVector change = LongVector.fromArray(S, PACKED, 0, now, 0);
      if (i + LANES < up) {
        indices(LongVector.fromArray(S, in, i + LANES)).intoArray(next, 0);
      }
      local(v, change, word).intoArray(out, i);
      int[] swap = now;
      now = next;
      next = swap;
    }
    scalarTail(in, out, i);
  }

  /** No gather and no index array: each lane's two words are read by scalar loads. */
  static void scalarLanes(long[] in, long[] out) {
    int i = 0;
    for (int up = S.loopBound(in.length); i < up; i += LANES) {
      LongVector v = LongVector.fromArray(S, in, i);
      IntVector ix = indices(v);
      LongVector change = LongVector.zero(S);
      LongVector word = LongVector.zero(S);
      for (int lane = 0; lane < LANES; lane++) {
        int k = ix.lane(lane);
        change = change.withLane(lane, PACKED[k]);
        word = word.withLane(lane, PACKED[k + 1]);
      }
      local(v, change, word).intoArray(out, i);
    }
    scalarTail(in, out, i);
  }

  static long[] instants(Random random, long from, long until) {
    long[] in = new long[N];
    for (int i = 0; i < N; i++) {
      in[i] = from + (long) (random.nextDouble() * (until - from));
    }
    return in;
  }

  static long micros(int year) {
    return LocalDate.of(year, 1, 1).toEpochDay() * 86400L * MICROS;
  }

  static void time(String form, String data, long[] in, long[] out, Runnable run) {
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
    Arrays.fill(out, Long.MIN_VALUE);
    run.run();
    long[] expected = new long[N];
    javaTime(in, expected);
    int wrong = 0;
    for (int i = 0; i < N; i++) {
      wrong += out[i] != expected[i] ? 1 : 0;
    }
    failed |= wrong != 0;
    System.out.printf("%-40s %-22s best %7.3f  median %7.3f ns a row  %s%n", form, data,
        window[0], window[2], wrong == 0 ? "every row equals java.time" : wrong + " ROWS DIFFER");
  }

  public static void main(String[] args) {
    System.out.printf("# %s, %d lanes of 64 bits, batches of %d rows, %d buckets%n", ZONE, LANES,
        N, BUCKETS);
    var random = new Random(42);
    long[] out = new long[N];
    long[] year = instants(random, micros(2025), micros(2026));
    long[] decade = instants(random, micros(2016), micros(2026));
    long[] all = instants(random, micros(1900), micros(2100));
    int[] scratch = new int[SI.length()];
    int[] offsetScratch = new int[64 + SI.length()];
    int[] second = new int[SI.length()];
    int[] map = new int[N];
    int[] map4 = new int[4 * LANES];
    int[] map16 = new int[16 * LANES];
    String[] names = {"a year, no order", "ten years, no order", "1900 to 2100, no order"};
    long[][] data = {year, decade, all};
    for (int d = 0; d < data.length; d++) {
      long[] in = data[d];
      String name = names[d];
      time("three arrays, three gathers", name, in, out, () -> threeGathers(in, out, scratch));
      time("packed, indices beside the gather", name, in, out, () -> beside(in, out, scratch, 0));
      time("packed, beside, array at an offset", name, in, out,
          () -> beside(in, out, offsetScratch, 64));
      time("packed, beside, array new each call", name, in, out, () -> besideAllocated(in, out));
      time("packed, indices of the whole batch", name, in, out, () -> precomputed(in, out, map));
      time("packed, indices of 4 groups", name, in, out, () -> batched(in, out, map4, 4));
      time("packed, indices of 16 groups", name, in, out, () -> batched(in, out, map16, 16));
      time("packed, the store one group ahead", name, in, out,
          () -> pipelined(in, out, scratch, second));
      time("packed, scalar loads, no gather", name, in, out, () -> scalarLanes(in, out));
    }
    if (failed) {
      throw new IllegalStateException("a form's rows differ from java.time");
    }
  }
}
