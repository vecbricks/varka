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
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What this JDK's time-zone data says about the forms milestone 9's plan weighs for reading a
 * time zone in a kernel (`m9/PLAN.md` 2.2): how many changes of offset a zone has and how close
 * they come, whether the step formula for a local time equals `java.time` under each of its three
 * rules, when an offset stops being a whole minute, and how often a batch spans a change. Every
 * zone the JDK lists is read, up to the year 2100. All times here are in seconds.
 */
public class ZoneFacts {
  static final long END = LocalDate.of(2100, 1, 1).toEpochDay() * 86400L;
  static final long Y1970 = 0L;
  static final long DAY = 86400L;

  /** A zone's changes before 2100: the instants, and the offset before and after each. */
  record Changes(String id, ZoneId zone, long[] t, int[] before, int[] after, int fixed) {
    int n() {
      return t.length;
    }

    /** The local time from which the offset after change {@code i} is the only valid one. */
    long lt(int i) {
      return t[i] + Math.max(before[i], after[i]);
    }

    /** The offset in force at {@code instant}. */
    int offsetAt(long instant) {
      int i = Arrays.binarySearch(t, instant);
      i = i >= 0 ? i + 1 : -i - 1;
      return i == 0 ? (n() == 0 ? fixed : before[0]) : after[i - 1];
    }
  }

  static Changes load(String id) {
    ZoneId zone = ZoneId.of(id, ZoneId.SHORT_IDS);
    ZoneRules rules = zone.getRules();
    var list = new ArrayList<ZoneOffsetTransition>();
    Instant at = Instant.MIN;
    while (true) {
      ZoneOffsetTransition tr = rules.nextTransition(at);
      if (tr == null || tr.getInstant().getEpochSecond() >= END) {
        break;
      }
      list.add(tr);
      at = tr.getInstant();
    }
    long[] t = new long[list.size()];
    int[] before = new int[t.length];
    int[] after = new int[t.length];
    for (int i = 0; i < t.length; i++) {
      t[i] = list.get(i).getInstant().getEpochSecond();
      before[i] = list.get(i).getOffsetBefore().getTotalSeconds();
      after[i] = list.get(i).getOffsetAfter().getTotalSeconds();
    }
    return new Changes(id, zone, t, before, after,
        rules.getOffset(Instant.EPOCH).getTotalSeconds());
  }

  static int year(long instant) {
    return LocalDateTime.ofEpochSecond(instant, 0, ZoneOffset.UTC).getYear();
  }

  /** The most changes any window of {@code span} seconds holds, from {@code from} on. */
  static int mostIn(Changes c, long span, long from) {
    int best = 0;
    for (int i = 0, j = 0; i < c.n(); i++) {
      if (c.t[i] < from) {
        continue;
      }
      j = Math.max(j, i);
      while (j < c.n() && c.t[j] - c.t[i] < span) {
        j++;
      }
      best = Math.max(best, j - i);
    }
    return best;
  }

  public static void main(String[] args) {
    var zones = new ArrayList<Changes>();
    for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
      zones.add(load(id));
    }
    System.out.printf("time-zone data %s, %d zone ids, %d with a change before 2100%n",
        ZoneRulesProvider.getVersions("UTC").lastKey(), zones.size(),
        zones.stream().filter(z -> z.n() > 0).count());

    // 1. How many changes, and how close together.
    long total = 0;
    long gaps = 0;
    long overlaps = 0;
    long closest = Long.MAX_VALUE;
    String closestAt = "";
    Changes most = zones.get(0);
    int year1 = 0;
    int year10 = 0;
    int year1Since1970 = 0;
    int year10Since1970 = 0;
    String year1At = "";
    for (Changes c : zones) {
      total += c.n();
      most = c.n() > most.n() ? c : most;
      for (int i = 0; i < c.n(); i++) {
        gaps += c.after[i] > c.before[i] ? 1 : 0;
        overlaps += c.after[i] < c.before[i] ? 1 : 0;
        if (i > 0 && c.t[i] - c.t[i - 1] < closest) {
          closest = c.t[i] - c.t[i - 1];
          closestAt = c.id + " " + year(c.t[i]);
        }
      }
      int y1 = mostIn(c, 365 * DAY, Long.MIN_VALUE);
      if (y1 > year1) {
        year1 = y1;
        year1At = c.id;
      }
      year10 = Math.max(year10, mostIn(c, 3653 * DAY, Long.MIN_VALUE));
      year1Since1970 = Math.max(year1Since1970, mostIn(c, 365 * DAY, Y1970));
      year10Since1970 = Math.max(year10Since1970, mostIn(c, 3653 * DAY, Y1970));
    }
    System.out.printf("changes before 2100: %d (%d gaps, %d overlaps), at most %d in a zone (%s)%n",
        total, gaps, overlaps, most.n(), most.id);
    System.out.printf("closest two changes of one zone: %d s apart (%s)%n", closest, closestAt);
    System.out.printf("most changes in any 365 days: %d (%s); since 1970: %d%n",
        year1, year1At, year1Since1970);
    System.out.printf("most changes in any 10 years: %d; since 1970: %d%n",
        year10, year10Since1970);

    // 2. When an offset stops being a whole minute, quarter hour or hour.
    long lastOddMinute = Long.MIN_VALUE;
    String lastOddMinuteAt = "";
    long lastOddQuarter = Long.MIN_VALUE;
    String lastOddQuarterAt = "";
    int notWholeHourIn2026 = 0;
    int wholeHourSince1970 = 0;
    long at2026 = LocalDate.of(2026, 1, 1).toEpochDay() * DAY;
    for (Changes c : zones) {
      boolean hours = c.offsetAt(Y1970) % 3600 == 0;
      for (int i = -1; i < c.n(); i++) {
        int off = i < 0 ? c.offsetAt(Long.MIN_VALUE) : c.after[i];
        long until = i + 1 < c.n() ? c.t[i + 1] : END;
        if (off % 60 != 0 && until > lastOddMinute) {
          lastOddMinute = until;
          lastOddMinuteAt = c.id;
        }
        if (off % 900 != 0 && until > lastOddQuarter) {
          lastOddQuarter = until;
          lastOddQuarterAt = c.id;
        }
        hours &= until <= Y1970 || off % 3600 == 0;
      }
      notWholeHourIn2026 += c.offsetAt(at2026) % 3600 != 0 ? 1 : 0;
      wholeHourSince1970 += hours ? 1 : 0;
    }
    System.out.printf("last offset that is no whole minute ends in %d (%s)%n",
        year(lastOddMinute), lastOddMinuteAt);
    System.out.printf("last offset that is no whole quarter hour ends in %d (%s)%n",
        year(lastOddQuarter), lastOddQuarterAt);
    System.out.printf("zones whose every offset since 1970 is whole hours: %d of %d; zones not on"
        + " a whole hour in 2026: %d%n", wholeHourSince1970, zones.size(), notWholeHourIn2026);

    // 3. What a zone database laid out for vector code can lean on.
    var rules = new HashSet<String>();
    int quiet = 0;
    long closestLocal = Long.MAX_VALUE;
    String closestLocalAt = "";
    int mostOffsets = 0;
    String mostOffsetsAt = "";
    for (Changes c : zones) {
      rules.add(c.offsetAt(Long.MIN_VALUE) + Arrays.toString(c.t) + Arrays.toString(c.after));
      quiet += c.n() > 0 && c.t[c.n() - 1] < at2026 ? 1 : 0;
      var offsets = new TreeSet<Integer>(List.of(c.offsetAt(Long.MIN_VALUE)));
      for (int i = 0; i < c.n(); i++) {
        offsets.add(c.after[i]);
        if (i > 0 && c.lt(i) - c.lt(i - 1) < closestLocal) {
          closestLocal = c.lt(i) - c.lt(i - 1);
          closestLocalAt = c.id + " " + year(c.t[i]);
        }
      }
      if (offsets.size() > mostOffsets) {
        mostOffsets = offsets.size();
        mostOffsetsAt = c.id;
      }
    }
    long buckets = ((END - LocalDate.of(1900, 1, 1).toEpochDay() * DAY) * 1_000_000L >>> 39) + 2;
    System.out.printf("distinct sets of rules among the %d zone ids: %d%n",
        zones.size(), rules.size());
    System.out.printf("zones with changes, and none after 2025: %d; with no change at all: %d%n",
        quiet, zones.stream().filter(z -> z.n() == 0).count());
    System.out.printf("closest two local thresholds of one zone: %d s apart (%s)%n",
        closestLocal, closestLocalAt);
    System.out.printf("most distinct offsets in one zone: %d (%s)%n", mostOffsets, mostOffsetsAt);
    System.out.printf("a table of 2^39-microsecond buckets from 1900 to 2100: %d buckets,"
        + " %d KB at 8 bytes a bucket, %d KB as ZoneForms' three arrays%n",
        buckets, buckets * 8 / 1024, buckets * 24 / 1024);

    // 4. The step formula for a local time, against java.time's three rules.
    long points = 0;
    long wrong = 0;
    long keptPoints = 0;
    long keptPairs = 0;
    long keptWrong = 0;
    boolean rising = true;
    var midnights = new TreeMap<String, Integer>();
    long midnightWrong = 0;
    for (Changes c : zones) {
      long[] lt = new long[c.n()];
      for (int i = 0; i < c.n(); i++) {
        lt[i] = c.lt(i);
        rising &= i == 0 || lt[i] > lt[i - 1];
      }
      for (int i = 0; i < c.n(); i++) {
        long lo = c.t[i] + Math.min(c.before[i], c.after[i]);
        long hi = lt[i];
        boolean gap = c.after[i] > c.before[i];
        for (long l = lo - 7200; l <= hi + 7200; l += l < lo - 60 || l > hi + 3 ? 60 : 1) {
          // The earlier offset: LocalDateTime.atZone.
          LocalDateTime local = LocalDateTime.ofEpochSecond(l, 0, ZoneOffset.UTC);
          int k = Arrays.binarySearch(lt, l);
          k = k >= 0 ? k + 1 : -k - 1;
          long byFormula = l - (k == 0 ? c.before[0] : c.after[k - 1]);
          points++;
          wrong += byFormula != local.atZone(c.zone).toEpochSecond() ? 1 : 0;
          if (l < lo || l >= hi) {
            continue;
          }
          // The offset kept: ZonedDateTime.ofLocal with the offset the source had, which is
          // the offset before the change, the one after it, or neither.
          keptPoints++;
          for (int kept : new int[] {c.before[i], c.after[i], c.before[i] + 1800}) {
            if (Math.abs(kept) > 64800) {
              continue;
            }
            boolean valid = !gap && (kept == c.before[i] || kept == c.after[i]);
            long expected = ZonedDateTime.ofLocal(local, c.zone, ZoneOffset.ofTotalSeconds(kept))
                .toEpochSecond();
            keptPairs++;
            keptWrong += expected != l - (valid ? kept : c.before[i]) ? 1 : 0;
          }
        }
        // The gap's end: LocalDate.atStartOfDay, where midnight is strictly inside a gap.
        for (long m = Math.floorDiv(lo, DAY) * DAY + DAY; gap && m < hi; m += DAY) {
          String day = LocalDate.ofEpochDay(m / DAY) + " gap of " + (hi - lo) + " s";
          midnights.merge(day, 1, Integer::sum);
          long start = LocalDate.ofEpochDay(m / DAY).atStartOfDay(c.zone).toEpochSecond();
          midnightWrong += start != c.t[i] ? 1 : 0;
        }
      }
    }
    System.out.printf("LT = T + max(offset before, offset after) rises strictly in every zone:"
        + " %b%n", rising);
    System.out.printf("earlier-offset rule: %d local times around every change, %d differ from"
        + " LocalDateTime.atZone%n", points, wrong);
    System.out.printf("kept-offset rule: %d local times in a gap or an overlap, each with up to"
        + " three kept offsets, %d pairs, %d differ from ZonedDateTime.ofLocal%n",
        keptPoints, keptPairs, keptWrong);
    System.out.printf("midnight strictly inside a gap: %d days over %d zone ids, %d where"
        + " atStartOfDay is not the gap's end%n", midnights.size(),
        midnights.values().stream().mapToInt(Integer::intValue).sum(), midnightWrong);
    midnights.forEach((day, n) -> System.out.printf("  %s, %d zone ids%n", day, n));
    boolean failed = !rising || wrong + keptWrong + midnightWrong != 0;

    // 5. How often a batch spans a change, in a zone with daylight saving.
    Changes la = load("America/Los_Angeles");
    var random = new Random(42);
    for (int[] span : new int[][] {{2025, 2026}, {2016, 2026}}) {
      long from = LocalDate.of(span[0], 1, 1).toEpochDay() * DAY;
      long to = LocalDate.of(span[1], 1, 1).toEpochDay() * DAY;
      for (int rows : new int[] {4096, 10000}) {
        int batches = 1000;
        long[] all = new long[batches * rows];
        for (int i = 0; i < all.length; i++) {
          all[i] = from + (long) (random.nextDouble() * (to - from));
        }
        for (boolean ordered : new boolean[] {false, true}) {
          if (ordered) {
            Arrays.sort(all);
          }
          int spanning = 0;
          int mostSpanned = 0;
          for (int b = 0; b < batches; b++) {
            long least = Long.MAX_VALUE;
            long greatest = Long.MIN_VALUE;
            for (int i = b * rows; i < (b + 1) * rows; i++) {
              least = Math.min(least, all[i]);
              greatest = Math.max(greatest, all[i]);
            }
            int spanned = 0;
            for (long t : la.t) {
              spanned += t > least && t <= greatest ? 1 : 0;
            }
            spanning += spanned > 0 ? 1 : 0;
            mostSpanned = Math.max(mostSpanned, spanned);
          }
          System.out.printf("%s %d to %d, %s, batches of %d rows: %d of %d span a change,"
              + " at most %d changes%n", la.id, span[0], span[1] - 1,
              ordered ? "in time order" : "in no order", rows, spanning, batches, mostSpanned);
        }
      }
    }
    if (failed) {
      System.out.println("A CHECK FAILED");
      System.exit(1);
    }
  }
}
