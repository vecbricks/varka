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
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Does one pair of local thresholds a change serve all three of java.time's rules for a local
 * time? For every zone of the running JVM this builds the changes {@code T[i]} (epoch seconds,
 * rising), the offsets {@code O[i]} (the offset before change {@code i}; {@code O[n]} the last),
 * and for each change the two local thresholds {@code lo[i] = T[i] + min(O[i], O[i+1])} and
 * {@code hi[i] = T[i] + max(O[i], O[i+1])}. With {@code k} the count of {@code hi <= L} for a
 * local time {@code L}, it checks against java.time:
 *
 * <ul>
 *   <li>an instant to its offset: {@code O[count of T <= t]};
 *   <li>(a) the earlier offset, {@code LocalDateTime.atZone}: {@code L - O[k]};
 *   <li>(b) the kept offset, {@code ZonedDateTime.ofLocal} with a preferred offset:
 *       {@code L - O[k+1]} when {@code lo[k] <= L}, change {@code k} is an overlap and the
 *       preferred offset is {@code O[k+1]}; else as (a);
 *   <li>(c) the gap's end, {@code LocalDate.atStartOfDay}: {@code T[k]} when {@code lo[k] <= L}
 *       and change {@code k} is a gap; else as (a). Checked at midnights, the only local times
 *       that method takes.
 * </ul>
 *
 * It reads at each change the seconds around both thresholds and the days around it, and 3000
 * random instants a zone from 1800 to 2400. Run by run.sh resolve; it fails on a mismatch.
 */
public class ZoneResolve {
  static final ZoneOffset UTC = ZoneOffset.UTC;
  static final long DAY = 86400L;

  static long[] changes;
  static int[] offsets;
  static long[] lo;
  static long[] hi;

  // Per rule: the checks made and the mismatches found.
  static final long[] CHECKS = new long[4];
  static final long[] MISMATCHES = new long[4];
  static final List<String> EXAMPLES = new ArrayList<>();

  /** How many elements of the rising array {@code a} are at most {@code x}. */
  static int countAtMost(long[] a, long x) {
    int from = 0;
    int to = a.length;
    while (from < to) {
      int mid = (from + to) >>> 1;
      if (a[mid] <= x) {
        from = mid + 1;
      } else {
        to = mid;
      }
    }
    return from;
  }

  static long earlier(long local) {
    return local - offsets[countAtMost(hi, local)];
  }

  static long kept(long local, int preferred) {
    int k = countAtMost(hi, local);
    boolean overlap = k < changes.length && offsets[k + 1] < offsets[k];
    if (overlap && lo[k] <= local && preferred == offsets[k + 1]) {
      return local - offsets[k + 1];
    }
    return local - offsets[k];
  }

  static long gapsEnd(long local) {
    int k = countAtMost(hi, local);
    boolean gap = k < changes.length && offsets[k + 1] > offsets[k];
    if (gap && lo[k] <= local) {
      return changes[k];
    }
    return local - offsets[k];
  }

  static void check(int rule, String what, String id, long input, long expected, long actual) {
    CHECKS[rule]++;
    if (expected != actual) {
      MISMATCHES[rule]++;
      if (EXAMPLES.size() < 12) {
        EXAMPLES.add(what + " " + id + " input " + input + ": java.time " + expected
            + ", the thresholds " + actual);
      }
    }
  }

  static void checkLocal(String id, ZoneId zone, long local, int[] preferred) {
    LocalDateTime time = LocalDateTime.ofEpochSecond(local, 0, UTC);
    check(1, "(a)", id, local, time.atZone(zone).toEpochSecond(), earlier(local));
    for (int p : preferred) {
      ZoneOffset offset = ZoneOffset.ofTotalSeconds(p);
      long expected = ZonedDateTime.ofLocal(time, zone, offset).toEpochSecond();
      check(2, "(b)", id, local, expected, kept(local, p));
    }
    // With no preferred offset the kept-offset rule is the earlier-offset rule.
    check(2, "(b)", id, local, ZonedDateTime.ofLocal(time, zone, null).toEpochSecond(),
        earlier(local));
  }

  static void checkDay(String id, ZoneId zone, long day) {
    long expected = LocalDate.ofEpochDay(day).atStartOfDay(zone).toEpochSecond();
    check(3, "(c)", id, day, expected, gapsEnd(day * DAY));
  }

  /** The zone's changes: the stored ones, then the recurring rules' to the year 2440. */
  static List<ZoneOffsetTransition> changesOf(ZoneRules rules) {
    List<ZoneOffsetTransition> list = new ArrayList<>(rules.getTransitions());
    if (rules.getTransitionRules().isEmpty() || list.isEmpty()) {
      return list;
    }
    Instant at = list.get(list.size() - 1).getInstant();
    while (true) {
      ZoneOffsetTransition next = rules.nextTransition(at);
      if (next == null || next.getInstant().atOffset(UTC).getYear() > 2440) {
        return list;
      }
      list.add(next);
      at = next.getInstant();
    }
  }

  static void build(String id, ZoneRules rules) {
    List<ZoneOffsetTransition> list = changesOf(rules);
    int n = list.size();
    changes = new long[n];
    offsets = new int[n + 1];
    lo = new long[n];
    hi = new long[n];
    offsets[0] = rules.getOffset(Instant.MIN).getTotalSeconds();
    for (int i = 0; i < n; i++) {
      changes[i] = list.get(i).toEpochSecond();
      offsets[i + 1] = list.get(i).getOffsetAfter().getTotalSeconds();
      if (list.get(i).getOffsetBefore().getTotalSeconds() != offsets[i]) {
        throw new IllegalStateException("the offsets of " + id + " do not chain");
      }
      lo[i] = changes[i] + Math.min(offsets[i], offsets[i + 1]);
      hi[i] = changes[i] + Math.max(offsets[i], offsets[i + 1]);
    }
  }

  public static void main(String[] args) {
    List<String> ids = new ArrayList<>(ZoneId.getAvailableZoneIds());
    Collections.sort(ids);
    long until = Instant.parse("2400-01-01T00:00:00Z").getEpochSecond();
    long from = Instant.parse("1800-01-01T00:00:00Z").getEpochSecond();
    List<String> differ = new ArrayList<>();
    for (String id : ids) {
      ZoneId zone = ZoneId.of(id);
      ZoneRules rules = zone.getRules();
      build(id, rules);
      for (int i = 0; i < changes.length && changes[i] < until; i++) {
        for (long t : new long[] {changes[i] - 1, changes[i], changes[i] + 1}) {
          long expected = rules.getOffset(Instant.ofEpochSecond(t)).getTotalSeconds();
          check(0, "offset", id, t, expected, offsets[countAtMost(changes, t)]);
        }
        // Both offsets of the change, and one that is neither.
        int[] preferred = {offsets[i], offsets[i + 1], 1020};
        long mid = lo[i] + (hi[i] - lo[i]) / 2;
        for (long local : new long[] {lo[i] - 1, lo[i], lo[i] + 1, mid, hi[i] - 1, hi[i],
            hi[i] + 1}) {
          checkLocal(id, zone, local, preferred);
        }
        long lastDay = Math.floorDiv(hi[i], DAY) + 1;
        for (long day = Math.floorDiv(lo[i], DAY) - 1; day <= lastDay; day++) {
          checkDay(id, zone, day);
          long midnight = day * DAY;
          if (earlier(midnight) != gapsEnd(midnight)) {
            differ.add(id + " " + LocalDate.ofEpochDay(day) + ": the earlier offset "
                + Instant.ofEpochSecond(earlier(midnight)) + ", the gap's end "
                + Instant.ofEpochSecond(gapsEnd(midnight)));
          }
        }
      }
      SplittableRandom random = new SplittableRandom(id.hashCode());
      for (int j = 0; j < 3000; j++) {
        long t = random.nextLong(from, until);
        int offset = rules.getOffset(Instant.ofEpochSecond(t)).getTotalSeconds();
        check(0, "offset", id, t, offset, offsets[countAtMost(changes, t)]);
        long local = t + offset;
        checkLocal(id, zone, local, new int[] {offset});
        // The kept-offset rule with an instant's own offset gives the instant back.
        check(2, "(b) there and back", id, local, t, kept(local, offset));
        checkDay(id, zone, Math.floorDiv(local, DAY));
      }
    }
    String[] names = {"an instant to its offset", "(a) the earlier offset, atZone",
        "(b) the kept offset, ofLocal", "(c) the gap's end, atStartOfDay"};
    long total = 0;
    long wrong = 0;
    System.out.println("zone ids: " + ids.size());
    for (int rule = 0; rule < names.length; rule++) {
      System.out.println(names[rule] + ": " + CHECKS[rule] + " checks, " + MISMATCHES[rule]
          + " mismatches");
      total += CHECKS[rule];
      wrong += MISMATCHES[rule];
    }
    System.out.println("in all: " + total + " checks, " + wrong + " mismatches");
    for (String example : EXAMPLES) {
      System.out.println("  MISMATCH " + example);
    }
    System.out.println("midnights where (a) and (c) differ: " + differ.size());
    for (String line : differ) {
      System.out.println("  " + line);
    }
    if (wrong != 0) {
      throw new IllegalStateException(wrong + " mismatches against java.time");
    }
  }
}
