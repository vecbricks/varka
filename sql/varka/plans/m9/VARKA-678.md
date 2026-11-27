# VARKA-678: The time-zone database for vector code

*Planned on 27 November 2026. This plan stands alone: a builder needs this file and the
repository's `AGENTS.md` files, nothing else. Not started: milestone 9 waits for the owner's
word (`m9/PLAN.md` decision 18). The code facts below were checked on master `a0e3a62cf2b`, and
the `java.time` facts in the source of OpenJDK 25 (`jdk-25-ga`). No JVM was run for this plan.*

## 1. What this row is

Row 678 of `sql/varka/plans/m9/PLAN.md`: "The time-zone database for vector code". Its line
asks for "a zone's class, its changes on both time lines with each gap's and overlap's window,
the instants from which its offsets are whole minutes and whole hours, the rule years written
out as they are needed, and the bucket table, which answers `VarkaZoneData.toLocalTable`
(`VARKA-686.md` 4.5); built once a zone in each JVM, from that JVM's `ZoneRules`; ids with the
same rules share an entry." It is done when "every zone id the session accepts has an entry;
the steps and the table read from it equal `java.time` over the sweep's instants on both time
lines; the time to build an entry and its size are measured and recorded; and no entry crosses
from the driver to an executor".

The row adds three classes to `sql/catalyst` and their tests. It adds no IR record, no emit
option, no lowering and no census cell, and it moves no emitted byte. That is why the milestone
plan's wave 0 allows it while milestone 8 runs (`m9/PLAN.md` section 4: "the library's start
(row 679) and row 678, which are new classes and their tests").

**What it gives the rows after it.**

| row | what it reads of this row | name, file |
| :--- | :--- | :--- |
| 657 | the production zone data and its provider: the compiler asks whether a zone is served, the evaluator hands the provider to the runner | `VarkaChronoZone.PROVIDER`, a `VarkaZoneData.Provider`, in `V/VarkaChronoZone.java` (part d) |
| 682 (its runner), 685, 687, 688 | `changes(least, greatest)` and `toLocalArgs(...)` behind `VarkaZoneData`, with no allocation a call | `VarkaChronoZone`, delegating to `VarkaChronoZoneChanges` (parts a and d) |
| 684 | the production implementer its sweep finds by `git grep -l 'implements VarkaZoneData' -- sql/catalyst/src/main`, and the provider it calls `PROD` | `V/VarkaChronoZone.java`, `VarkaChronoZone.PROVIDER` |
| 686 | the changes of 1900 to 2100 as the two arrays `VarkaZoneTable.pack` takes, and a slot that keeps the packed table for the entry's life | `VarkaChronoZoneChanges.changesAt`, `changeOffsets`, `memo` (part b) |
| 690, 693 | the local time line: the count of windows a range touches and the arguments of each of the three rules | `VarkaChronoZoneChanges.localChanges`, `toInstantArgs(Rule, ...)` (part b); row 690 adds the arms of `VarkaChronoZone.toInstantArgs` that call them |
| 685 (its part e), 691 | the instants from which every offset is a whole minute and a whole hour | `VarkaChronoZoneChanges.wholeMinutesFrom()`, `wholeHoursFrom()` (part b) |
| 679 | the first content of the library's zone database: two merged files that import only the JDK, which row 679 ports | `V/VarkaChronoZoneChanges.java`, `V/VarkaChronoZones.java` (section 2.4) |

**What must be merged before it starts.** Nothing for parts a, b and c, which are built in
that order. Part d needs part c, and it implements `VarkaZoneData`, so it also needs row
682's part c (`[VARKA-682] Add VarkaZoneData and its java.time
reference for tests`). Row 657 needs part d. Row 686's part a needs parts b and d.

**The goals it serves** (`m9/PLAN.md` 1.1; section 3.1 there lists row 678 under G1, G2 and
G7), each with the item of 1.2 that it moves. The row fuses nothing itself, so it serves each
goal only through a later row.

- G1: "**G1. `TIMESTAMP_LTZ` at microseconds (phase 1).** It is a census type. The entries that
  read no time zone fuse over it: choice, null tests, comparisons, `IN`, `greatest`, `least` and
  the hashes, as they do over `TIMESTAMP_NTZ`, and the conversions between a timestamp and a
  count since the epoch (`timestamp_seconds` and its kin, and the `unix_*` functions). Under the
  session time zone, the calendar and clock fields, `date_trunc`, `months_between`,
  `from_utc_timestamp`, `to_utc_timestamp`, `convert_timezone`, `make_timestamp` and its forms
  and the timestamp arithmetic fuse." Item 1: "**G1:** at least three quarters of
  `TIMESTAMP_LTZ`'s (entry, type) pairs fused (about 66 entries are expected to admit it)."
  Only through row 657 and the rows after it: every entry under the session zone reads this
  row's data. The check: `dev/varka_census.py --summary` after row 657; this row moves no cell.
- G2: "**G2. Every time zone, proven.** A kernel's answer equals Spark's in every zone the
  session can name - every region in the time-zone data of the JDK that CI runs, Spark's short
  names and fixed offsets - at the gaps and overlaps of each change of a zone's offset too,
  checked by a sweep that runs in CI. A kernel answers there itself: instants in no order must
  not send every batch back to the row engine (2.2)." Item 2: "**G2:** the every-zone sweep
  passes in CI under each rule by which Spark resolves a local time in a gap or an overlap
  (2.1); every kernel that reads a time zone has a test whose rows straddle both of a year's
  changes of offset; and over ten years of instants in no order in a daylight-saving zone the
  kernels of the fields, of the casts and of the sum with an interval decline no batch." This
  row moves the first clause directly at the level of the data: its three suites sweep every
  zone id of CI's JDK, under the three rules, in every Build (section 6). The kernels' sweep is
  row 684's and row 690's. The check: the suites' lines in the Build's log.
- G7: "**G7. Speed where a time zone is read.** A fused `TIMESTAMP_LTZ` expression runs
  significantly faster than vanilla Spark: than the row path of the same build, section by
  section, and than the newest stock Spark release on a timestamp surface. No fused shape is
  slower than the row path on a measured host; a shape that would be declines there." Item 10:
  "**G7:** on the AMD EPYC 7763 at 256 bits, in the baseline classes of row 655 under a
  daylight-saving zone with a year of instants in no order, every `TIMESTAMP_LTZ` section whose
  kernel reads the zone runs at least five times the row path's rate and every other section at
  least twice; no section is slower than the row path at 256 or at 128 bits; and the timestamp
  surface against the newest stock Spark release is committed for the post. The nanosecond
  `TIMESTAMP_LTZ` sections meet the same bars in phase 2. The bars are the owner's (decision
  9)." Only through rows 657 and 691. This row's share is the cost of filling one batch's
  arguments, which its benchmark records (section 7); the check is that file.
- G5 ("**G5. Delivery by cheaper models.**"): not served. The row is foundation and is built at
  full strength (section 8).

## 2. What an agent must know

### 2.1 The idea

A `TIMESTAMP` is a long of microseconds since the epoch in UTC, an *instant*. Its local time in
a zone is the instant plus the zone's offset at that instant. Spark's row engine asks
`java.time` for that offset one value at a time: `ZoneRules.getOffset(Instant)` is a binary
search over the zone's stored changes, and past the last stored change it works a yearly rule
out again on every call. A Varka kernel does not look a zone up for each row. Before a kernel
runs, the runner finds the least and the greatest value of the batch, asks the zone's data for
the changes inside that range, and writes them into the kernel's arguments. The kernel then
adds one offset to every row and, for each change, a difference to the rows at or after it.

So the kernel's side needs the zone as plain sorted arrays that answer, with no allocation and
in well under a microsecond: how many changes fall in a range, and which. This row lays the
JVM's own `ZoneRules` out that way, once for each distinct set of rules in each JVM. It is not
a second time-zone database: it copies nothing from outside the running JDK, so a kernel and
the row engine on one executor always read the same data.

### 2.2 Terms

- *Instant*: microseconds since 1970-01-01T00:00:00Z, a long.
- *Local time*: the instant plus the offset in force, a long of microseconds on the *local time
  line*. It is what a `TIMESTAMP_NTZ` holds.
- *Offset*: local time minus UTC. `java.time` holds it in whole seconds, within 18 hours
  (`ZoneOffset`). Here it is an `int` of seconds in the data and microseconds in an argument.
- *Change*: an instant `T` at which a zone's offset changes from `before` to `after`. It falls
  on a whole second. `java.time` calls it a `ZoneOffsetTransition`.
- *Stored change*: a change that `ZoneRules.getTransitions()` lists. *Rule change*: a change
  that a recurring rule (`ZoneOffsetTransitionRule`) creates for a year after the last stored
  change. A `ZoneRules` holds at most 16 rules.
- *Rule era*: the instants after the last stored change of a zone that has rules.
- *Cycle*: 400 Gregorian years, 146097 days, a whole number of weeks. A rule names a month, a
  day and a day of the week, so the rule changes of year `y + 400` are those of year `y` moved
  by one cycle.
- *Window*: on the local time line a change is the half-open interval from `lo = T +
  min(before, after)` to `hi = T + max(before, after)`. In a *gap* (`after > before`) the
  local times of the window do not exist. In an *overlap* (`after < before`) each occurs twice.
- *The three rules* by which `java.time` turns a local time `L` into an instant, each used by
  Spark (`m9/PLAN.md` 2.1): the *earlier offset* (`LocalDateTime.atZone`); the *kept offset*
  (`ZonedDateTime.ofLocal` with a preferred offset); the *gap's end* (what
  `LocalDate.atStartOfDay` does at a midnight inside a gap).
- *Step*: one change as a kernel reads it, a threshold and a difference. *Padding*: a step that
  never fires, a threshold of `Long.MAX_VALUE` and a difference of 0.
- *Fixed zone*: a zone whose `ZoneRules.isFixedOffset()` is true. The compiler folds it and no
  kernel reads its data.
- *Served*: a zone whose entry passed its build-time check. A zone that is not served has no
  kernel: the compile declines by name, and the row engine answers.
- *Entry*: the data of one distinct set of rules, an immutable `VarkaChronoZoneChanges`.

### 2.3 The names

"Reads" exists on master or in a merged plan. "Adds" is this row's.

| name | reads or adds | meaning |
| :--- | :--- | :--- |
| `java.time.zone.ZoneRules`: `isFixedOffset()`, `getTransitions()`, `getTransitionRules()`, `getOffset(Instant)`, `getValidOffsets(LocalDateTime)`, `getTransition(LocalDateTime)` | reads | the JVM's own data, and the oracle |
| `ZoneOffsetTransitionRule.createTransition(int year)` | reads | the change a rule gives a year |
| `DateTimeUtils.getZoneId(String)` (catalyst; from `SparkDateTimeUtils`) | reads | Spark's own resolution of a zone id: `ZoneId.of(id, ZoneId.SHORT_IDS)` after two fixes of spelling |
| `VarkaZoneData`, `VarkaZoneData.Provider` (row 682, part c) | reads | the interface a kernel's runner and the compiler read a zone through; `Provider.of(zoneId)` returns null for a zone that is not served |
| `VarkaJavaTimeZoneData` (row 682, part c; catalyst tests) | reads | the reference `VarkaZoneData` over `java.time` |
| `VarkaZoneTable.pack(long[] at, int[] offsetSeconds, long[] args, int first)` (row 686, part a) | named only | packs the changes of 1900 to 2100 into the bucket table; this row gives its two inputs |
| `VarkaChronoZoneChanges` | adds, parts a and b | one set of rules laid out for vector code: the entry |
| `VarkaChronoZoneChanges.Rule` | adds, part b | `EARLIER`, `GAP_END`, `KEPT`: the three rules |
| `VarkaChronoZoneChanges.Check` | adds, part a | what the build-time check counted, and why it failed |
| `VarkaChronoZones` | adds, part c | the cache: one entry for each distinct `ZoneRules` in this JVM |
| `VarkaChronoZone` | adds, part d | the production `VarkaZoneData`: a zone id and its entry |
| `VarkaChronoZone.PROVIDER` | adds, part d | the production `VarkaZoneData.Provider` |
| `VarkaChronoZoneChangesSuite`, `VarkaChronoZoneLocalSuite`, `VarkaChronoZonesSuite`, `VarkaChronoZoneSuite`, `VarkaChronoZoneFixtures` | adds | the suites and their shared helpers (catalyst tests, Java) |
| `VarkaChronoZoneBenchmark` | adds, part c | the time to build an entry, its size, and the cost of one batch's arguments |

`VarkaZoneData`, as row 682 declares it (`VARKA-682.md` 4.7), with the method row 686 adds:

    public interface VarkaZoneData {
      String id();
      boolean fixed();
      int changes(long least, long greatest);        // changes T with least < T <= greatest
      int localChanges(long least, long greatest);
      void toLocalArgs(long least, long greatest, int steps, long[] args, int first);
      void toInstantArgs(VarkaVectorIR.Zoned node, long least, long greatest, int steps,
          long[] args, int first);
      boolean toLocalTable(long[] args, int first);  // row 686, part a
      interface Provider {
        Provider NONE = id -> null;
        @Nullable VarkaZoneData of(String zoneId);
      }
    }

`changes` is capped at `MANY = 4096`. `toLocalArgs` writes `args[first]`, the offset at `least`
in microseconds, then one pair `at`, `delta` for each change in order, then padding up to
`steps`; it throws `IllegalArgumentException` when the changes exceed `steps`.

### 2.4 The library beside this row

`m9/PLAN.md` 2.10 and the owner's decisions 13 and 16: a library of its own, `vechrono` at
`vecbricks/vechrono`, holds the batch forms of `java.time`, the zone database among them. "A
form is built there first", and "No row of this plan waits on a release of the library: a suite
reads `java.time` alone until the release holds what it calls." The repository does not exist
on 27 November 2026, and row 679 creates it only on the owner's go-ahead.

So this plan is written to be built inside `sql/catalyst` with no library:

- `VarkaChronoZoneChanges` imports only `java.time`, `java.util` and `java.util.function`. It
  names no Spark class and no Varka class. It is the file that the library takes.
- `VarkaChronoZones` (the cache) imports only the JDK and `VarkaChronoZoneChanges`.
- `VarkaChronoZone` is Varka's own: it implements `VarkaZoneData` and calls Spark's
  `DateTimeUtils.getZoneId`. It stays in Varka whatever the library holds.
- The oracle of every suite is `java.time`. No suite of this row reads the library.

Section 11 states the seam with row 679's plan, `VARKA-679.md`.

## 3. The admission check

Paths: `V` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`,
`T` is its `src/test/java` twin, `B` is `sql/catalyst/src/test/java/org/apache/spark/sql`. Each
repository fact was read on master `a0e3a62cf2b`.

**A builder of this row does not open OpenJDK's `java.time` sources.** The planner read them
(`jdk-25-ga`) to learn where `java.time`'s answers can step. Facts 3 to 8 and 10 below state
what that reading found, in this plan's words, as behaviour of the public API: `ZoneRules
.getOffset`, `getValidOffsets`, `getTransition`, `getTransitions`, `getTransitionRules`,
`isFixedOffset`, and `ZoneOffsetTransitionRule.createTransition`. The build rests on no fact
for its truth: each is held by a test of this row through that API, named beside it, and by
the build-time check, which compares with the same methods. If a test of a fact fails, the
builder stops and reports; the builder does not go to the JDK's source to find why.

1. **No name of this row exists, and no Varka code reads `ZoneRules`.**
   `ls $V/VarkaChronoZoneChanges.java $V/VarkaChronoZones.java $V/VarkaChronoZone.java
   2>/dev/null | wc -l` prints 0. `git grep -l 'ZoneRules' -- sql/catalyst/src/main
   sql/core/src/main | wc -l` prints 0.
2. **`VarkaZoneData` is not on master yet.** `ls $V/VarkaZoneData.java
   $T/VarkaJavaTimeZoneData.java 2>/dev/null | wc -l` prints 0. Its text in 2.3 is from
   `VARKA-682.md` 4.7 and `VARKA-686.md` 4.5. So part d starts with a grep of it.
3. **`java.time` answers one lookup at a time, in three ways.** For an instant,
   `ZoneRules.getOffset(Instant)` behaves so. A zone with no stored change answers its one base
   offset, whatever its rules. An instant at or before the last stored change gets the offset
   after the last stored change at or before it. A later instant, in a zone with rules, gets
   an offset worked out from the rule changes of one year. *Held by:* part a's test 10
   (`rulesOnly()`: rules and no stored change), tests 2 and 5 (stored changes), tests 4 and 6
   (the rule era).
4. **Which year: the one read with the last stored offset.** For a rule-era instant,
   `java.time` takes the year of that instant's local date under the offset after the last
   stored change, not under the offset in force, and reads that year's rule changes in the
   rules' own order. So near a year's start its answer can differ from a plain sorted list of
   changes, when a rule's change falls outside the year so read, or the rules are not in time
   order. This row must equal `java.time`, since the row engine reads it. *Held by:* check C4
   of 4.4, and part a's test 9 (`outOfYear()`, whose premise is asserted first, and
   `outOfOrder()`).
5. **A rule year from 2100 on costs more to look up** than an earlier one: `java.time` keeps
   the rule changes of earlier years and works later ones out on each call. This is a cost
   and not a behaviour; no code rests on it. It is why prediction 4 of 7.1 is a bound.
6. **A rule's change is a function of the year alone, and repeats every 400 years.**
   `ZoneOffsetTransitionRule.createTransition(year)` reads the rule's month, day of month and
   day of the week, and the Gregorian calendar repeats after 146097 days, which is 7 times
   20871 and so a whole number of weeks. *Held by:* part a's test 11 (the arithmetic), test 4
   (rule changes up to the last cycle of a long) and part b's test 5 (the same on the local
   line).
7. **A local time is looked up by its own local year,** and resolved as follows.
   `ZoneRules.getValidOffsets(LocalDateTime)` gives one offset outside every window; none
   inside a gap; two inside an overlap, the offset before first. `LocalDateTime.atZone` takes
   the first of two, and inside a gap moves the time on by the gap's length and takes the
   offset after. `ZonedDateTime.ofLocal` does the same, but takes a preferred offset where it
   is one of the two. `LocalDate.atStartOfDay` gives the change's own instant for a midnight
   inside a gap. *Held by:* check C7 of 4.4, and part b's tests 1 (the lists), 2 (`atZone`),
   3 (`ofLocal`) and 4 (`atStartOfDay`).
8. **Equal rules are equal by value.** Two `ZoneRules` with the same changes and rules are
   `equals` and have one `hashCode`, so a `ZoneRules` is a map key and two ids with the same
   rules meet in one entry. `isFixedOffset()` is true only for a zone with no stored change,
   no standard-time change and no rule. *Held by:* part c's test 1 (ids of one set share an
   entry) and part a's test 10 (`standardOnly()` and `rulesOnly()` are not fixed).
9. **Spark resolves a zone id in one place.** `SparkDateTimeUtils.getZoneId` pads a
   one-digit hour (`+1:00`) and a one-digit minute (`+01:5`), then calls
   `ZoneId.of(formattedZoneId, ZoneId.SHORT_IDS)`; a bad id throws a `SparkDateTimeException`,
   a `java.time.DateTimeException`. `grep -c 'ZoneId.of(formattedZoneId, ZoneId.SHORT_IDS)'
   sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/SparkDateTimeUtils.scala` prints
   1. Java calls it as `DateTimeUtils.getZoneId(id)`: `JavaBeanDeserializationSuite` does.
10. **Spark's row path reads the offset that `ZoneRules.getOffset(Instant)` gives.**
    `SparkDateTimeUtils.getLocalDateTime` is `microsToInstant(micros).atZone(zoneId)
    .toLocalDateTime` (read in the repository), and `Instant.atZone` of a region takes that
    region's `getRules().getOffset` of the instant. *Held by:* part a's test 2, which for the
    JDK's own sets also compares with `Instant.atZone(zone).getOffset()`.
11. **The probes' evidence for the layout** (`sql/varka/spikes/zone-offset/results/`, JDK 25,
    time-zone data 2026f). `facts.txt`: 604 zone ids, 349 distinct sets of rules, 65794 changes
    before 2100, at most 400 in a zone, the closest two changes and the closest two local
    thresholds of one zone 601200 s apart, at most 5 changes in any 365 days. `resolve.txt`:
    18399068 checks of the three rules to the year 2400 and no mismatch. The probes walk
    `ZoneRules.nextTransition`; this row writes rule years out by `createTransition` and checks
    them against `getOffset`, so their evidence carries over only through that check.
12. **The suites' harness and the benchmark's base exist.** `grep -c 'boolean ownJvm() default
    false;' $T/VarkaSuite.java` prints 1; `grep -c 'protected static void runCases(VarkaBenchmark
    benchmark)' $B/VarkaBenchmarkBase.java` prints 1; `grep -c 'protected final void note(String
    line)' $B/VarkaBenchmarkBase.java` prints 1; `git grep -n 'static long allocatedBytes' --
    $V/VarkaAllocationSampler.java` prints one line.
13. **A file named `Varka...` is Varka-scoped in CI.** `varka_scoped_regexes` of
    `dev/sparktestsupport/modules.py` holds `(^|/)[^/]*[Vv]arka[^/]*$`, so the lint's
    `ci-module` rule passes for each new file here.

**What I could not check.** No JVM run was allowed for a fact of the JDK's data, so these are
read from the planner's reading and from the probes' files, and each is asserted or printed by
a test:
whether every set of rules of time-zone data 2026f passes the check of 4.4 (the probes found
no mismatch to 2400 by another walk); how many stored changes a zone holds and so each entry's
size; whether any stored change has equal offsets before and after (the public factory rejects
one, the binary file's reader does not check); whether the made-up rules of 5.1 behave as fact
4 predicts (test 9 of part a asserts its premise first); every time and size of section 7. Rows 682,
686, 657 and 690 are not built: their names are from their plans, and part d's B0 greps them.

## 4. The design

### 4.1 The data layout: `VarkaChronoZoneChanges`

One final class, immutable after `build`. Its fields:

| field | type | what it holds |
| :--- | :--- | :--- |
| `fixed` | `boolean` | `rules.isFixedOffset()` |
| `at` | `long[n]` | the instants of the zone's changes in microseconds, strictly ascending: the stored changes, then the head, then the block (below) |
| `offsetSeconds` | `int[n + 1]` | `offsetSeconds[i]` is the offset in force after `i` changes, in whole seconds; `offsetSeconds[0]` holds before the first change |
| `block` | `int` | the count of changes in one cycle of the rule era; 0 for a zone with no rules |
| `blockStart`, `blockEnd` | `long` | the cycle's first instant and `blockStart + CYCLE_MICROS`; unused when `block` is 0 |
| `wholeMinutesFrom`, `wholeHoursFrom` | `long` | 4.7 |
| `check` | `Check` | 4.4 |
| `memo` | `volatile long[]` | 4.8; the one field written after `build` |

Constants, each `public static final`: `long MICROS = 1_000_000L`; `long CYCLE_MICROS =
146_097L * 86_400L * MICROS`; `long MAX_OFFSET_MICROS = 64_800L * MICROS` (18 hours,
`ZoneOffset`'s bound); `int MANY = 4096`.

**Visibility.** Every field is `private final` (`memo` is `private volatile`), and the arrays
never leave the class. The class, `build`, `Check`, `Rule`, the five basic methods
`fixed()`, `served()`, `check()`, `size()` and `bytes()`, and the methods that 4.3 to 4.8
write as `public` are public: the benchmark, in another package, and the library's port read
them. The view of 4.2 (`rank`, `atOf`, `secondsOf`, `loOf`, `hiOf`) and `int block()` are
package-private, with no modifier: the suites are in the same package and read a change by
its number through them. `localRank` and `localStarted` are private. Where a test table says
"the change `r`", it means `atOf(r)`, `secondsOf(r)` and `secondsOf(r + 1)`, for `r` from 0
to `size() - 1`; "a block change" is one with `r >= size() - block()`.

**The unit.** `at` is in microseconds, because a batch's least and greatest value and a
kernel's thresholds are microseconds: a fill compares and copies, and multiplies nothing. A
change is on a whole second, so the product by 1,000,000 is exact. Offsets stay whole seconds
in an `int`, which is what `VarkaZoneTable.pack` takes; a fill multiplies the few it writes.
The local thresholds `lo` and `hi` are not stored: `hi(r) = at(r) + MICROS *
max(offsetSeconds[r], offsetSeconds[r + 1])` is two loads and an add, and a fill reads a
handful.

**The three runs of `at`.**

1. *Stored*: `rules.getTransitions()`, in its order, each `toEpochSecond() * MICROS`, with
   `getOffsetBefore()` of the first as `offsetSeconds[0]` and each `getOffsetAfter()` after it.
   A stored change whose two offsets are equal is kept as it is, a step of difference 0, so
   that the counts equal a walk of `ZoneRules.nextTransition`. With no stored change, `n` is 0
   and `offsetSeconds[0]` is `rules.getOffset(Instant.EPOCH)`; the rules are then not read, as
   `getOffset` does not read them (fact 3).
2. *Head*, only if `rules.getTransitionRules()` is not empty and a stored change exists. Let
   `last` be the last stored change in seconds, `w` the offset after it, and `Y0` the year of
   `LocalDateTime.ofEpochSecond(last + 1, 0, w)`: the year `java.time` reads for the first
   instant of the rule era (fact 4). The head is each `rule.createTransition(Y0)`, in the
   rules' order, whose epoch second is greater than `last`.
3. *Block*: each `rule.createTransition(y)` for `y` from `Y0 + 1` to `Y0 + 400`, in order of
   year and then of rule: 400 times the count of rules. `blockStart` is `LocalDateTime.of(Y0 +
   1, 1, 1, 0, 0).toEpochSecond(w) * MICROS`, the instant from which `java.time` reads the
   year `Y0 + 1`.

The array is not sorted by the builder. `java.time` scans a year's rule changes in the rules'
order (fact 4), so a set of rules out of order has an answer that a sorted list does not give;
the check of 4.4 refuses it.

**To which year.** The rule era is written out for 400 years after its first, and never past
that. Every later instant is answered by the cycle (4.2), to `Long.MAX_VALUE`, which is in
the year 294247. The row's line says "the rule years written out as they are needed"; this
plan writes the whole cycle when the entry is built, because an entry that never changes needs
no lock on the path a batch takes (open question 2).

**Memory.** An entry's arrays hold `12 * n + 4` bytes, which `bytes()` returns: 8 for each
instant and 4 for each offset. For a zone with two rule changes a year the block alone is 800
changes, 9600 bytes. The bucket table of row 686, when a kernel first asks for it, adds
`VarkaZoneTable.LONGS` longs, 183,712 bytes (`m9/PLAN.md` 2.2). A fixed zone holds one `int`.

### 4.2 The periodic view

Five package-private methods give the changes as one endless ascending sequence, numbered
from 0. With
`p = n - block`, the index of the block's first change:

- `long rank(long x)`: the count of changes with instant `<= x`. For `block == 0` or `x <
  blockEnd`: a binary search of `at`. Else `k = 1 + Long.divideUnsigned(x - blockEnd,
  CYCLE_MICROS)`, and the answer is `k * block` plus the binary search for `x - k *
  CYCLE_MICROS`. The true difference `x - blockEnd` is under 2^64, so the unsigned quotient is
  exact whatever the signs, and the reduced value is from `blockStart` to `blockEnd - 1`.
- `long atOf(long r)`: for `r < n`, `at[r]`; else, with `j = r - p`, `at[p + j % block] + (j /
  block) * CYCLE_MICROS`. Callers ask only for a change at or before a long they hold, so the
  sum is a long.
- `int secondsOf(long r)`: the offset in force after `r` changes: for `r <= n`,
  `offsetSeconds[r]`; else `offsetSeconds[p + (r - p) % block]`.
- `long loOf(long r)` and `long hiOf(long r)`: `atOf(r)` plus `MICROS` times the lesser, or
  the greater, of `secondsOf(r)` and `secondsOf(r + 1)`.

A change `r` *exists* when `r < n` or `block > 0`. `atOf`, `loOf` and `hiOf` are called only
for a change that exists; every loop below is bounded by a `rank`, which counts only such.
`int block()` returns the field.

`rank` is a `long`: a zone with rules has two or more changes in each of the 292,000 years
between today and `Long.MAX_VALUE`.

### 4.3 The instant line

- `public int offsetSecondsAt(long instant)`: `secondsOf(rank(instant))`.
- `public int changes(long least, long greatest)`: 0 when `greatest <= least`; else
  `(int) Math.min(rank(greatest) - rank(least), MANY)`. A change at `least` is not counted and
  one at `greatest` is.
- `public void toLocalArgs(long least, long greatest, int steps, long[] args, int first)`: with
  `r0 = rank(least)` and `c = changes(least, greatest)`: throws `IllegalArgumentException("a
  range of " + c + " changes does not fit " + steps + " steps")` when `c > steps`. Else
  `args[first] = secondsOf(r0) * MICROS`; for `i` from 0 to `c - 1`, `args[first + 1 + 2 * i] =
  atOf(r0 + i)` and `args[first + 2 + 2 * i] = (secondsOf(r0 + i + 1) - secondsOf(r0 + i)) *
  MICROS`; for `i` from `c` to `steps - 1`, `Long.MAX_VALUE` and 0.

Each is exact for every pair of longs, the two ends of a long included, and none allocates.
The scalar meaning of the arguments, which the tests and row 682's lowering share: `local = x +
args[first] + sum over i of (x >= at[i] ? delta[i] : 0)`.

### 4.4 The build-time check

`public static VarkaChronoZoneChanges build(ZoneRules rules)` never throws for a non-null
argument. It builds the arrays, runs the check, and returns an entry whose `served()` is
`check.failure() == null`. An `ArithmeticException` (a product or a sum computed with
`Math.multiplyExact` and `Math.addExact`) or a `DateTimeException` inside it becomes a
failure whose text is C1's for an `ArithmeticException` and `java.time threw <the
exception's simple class name>` for a `DateTimeException`. A fixed zone has nothing to check
and is served: its `check()` is `new Check(0, 0, 0, 0, Long.MAX_VALUE, 0, null)`, never null.
An entry that is not served is
asked nothing but `served()`, `check()` and `fixed()`: `VarkaChronoZone.of` returns null for
it, and its other methods carry no guard.

    public record Check(int stored, int ruleChanges, int gaps, int overlaps,
        long closestWindowsSeconds, int compared, String failure) { }   // failure: null if passed

The build stops at the first failure, with these texts, in this order (an inexact product
or sum is found as the arrays are written). Part a builds C1 to C5; part b adds C6 and C7.

| | what must hold | failure text |
| :--- | :--- | :--- |
| C1 | every product and sum of the build is exact | `a change outside a long of microseconds` |
| C2 | `at` rises strictly | `changes out of order at <instant>` |
| C3 | for each head and block change `i`, the rule's `getOffsetBefore()` is `offsetSeconds[i]` | `a rule's offset before is not the offset in force at <instant>` |
| C4 | on the instant line, at each point `s` (seconds) of the set below, and at `s - 1`: `rules.getOffset(Instant.ofEpochSecond(s)).getTotalSeconds()` equals `offsetSecondsAt(s * MICROS)` | `java.time differs at instant <s>` |
| C5 | each head change is before `blockStart`; each block change is at or after `blockStart` and before `blockEnd`; `offsetSeconds[n] == offsetSeconds[p]` | `a rule change outside its cycle at <instant>` |
| C6 | `hiOf(r) <= loOf(r + 1)` for each change `r` below `n` whose next change exists; with rules, the first change of the next cycle is change `n` | `local windows touch at <instant>` |
| C7 | on the local line, at each point `L` (seconds) of the set below, and at `L - 1`: the first and the last element of `rules.getValidOffsets(ldt)` equal the earlier and the later offset the data gives, or both the list and the data say that `L` is in a gap and `rules.getTransition(ldt).toEpochSecond()` is that change's instant | `java.time differs at local time <ldt>` |

- C4's points: every change `r` from 0 to `n - 1`, and with rules also change `n`, the first of
  the next cycle; and the start of each rule year, `LocalDateTime.of(y, 1, 1, 0,
  0).toEpochSecond(w)` for `y` from `Y0 + 1` to `Y0 + 401`.
- C7's points: `loOf(r)` and `hiOf(r)` of the same changes, and the local midnight that starts
  each year from `Y0` to `Y0 + 401`. `ldt` is `LocalDateTime.ofEpochSecond(L, 0,
  ZoneOffset.UTC)`. What the data gives at `L`: with `k = localRank(L * MICROS)` (4.5), the
  count of changes whose `hi` is at or before it. If change `k` does not exist (`k == n` in a
  zone with no rules), the one offset `secondsOf(k)`. Else change `k` is the next one: if
  `loOf(k) <= L * MICROS` the time is inside its window, a gap when `secondsOf(k + 1) >
  secondsOf(k)`, else an overlap of the two offsets `secondsOf(k)` (earlier) and `secondsOf(k
  + 1)`; if not inside, the one offset `secondsOf(k)`.

**Why the points are enough.** On each line both functions, `java.time`'s and the data's, are
step functions of whole seconds. `java.time`'s can step only at a change of the year it reads
or where the year it reads changes (facts 3, 4 and 7); the data's only at a change. So between
two neighbours of the union of those points both are constant, and equality at each point and
one second before it is equality everywhere up to the end of the first cycle. Past it, both
repeat by the cycle: the data by 4.2, `java.time` by fact 6. The far-future tests hold that
second half, which the check does not run.

C4 runs before C5 on purpose: `offsetSecondsAt` is a binary search and a division that throw
for no data, so it is safe on a block that C5 would refuse, and a set of rules that
`java.time` reads in another year then fails with the text that says so. C5 is what the
reduction by cycles of 4.2 rests on. No made-up rules known to this plan pass C4 and fail C5;
its failure arm is reached by no test, only by part a's planted fault "C4 removed", and the
pull request says so beside the JaCoCo line.

`stored` is the count of stored changes and `ruleChanges` the head plus the block.
`compared` counts the lookups of C4 and C7. `closestWindowsSeconds` is the least `loOf(r + 1) -
hiOf(r)` in seconds, `Long.MAX_VALUE` for fewer than two changes. `gaps` and `overlaps` count
the changes held with `after > before` and `after < before`.

### 4.5 The local line

`M` is `MAX_OFFSET_MICROS`. A local range is *inside the clamp* when `least >= Long.MIN_VALUE
+ 2 * M` and `greatest <= Long.MAX_VALUE - 2 * M`: a threshold is within `M` of its change, and
a change that matters is within `M` of the range, so inside the clamp no sum leaves a long.

- `private long localRank(long L)`: the count of changes with `hi <= L`. Start at `r =
  rank(L - M)`, since each of those changes has `hi <= L`; then, while `r < rank(L + M)` and
  `hiOf(r) <= L`, add one. C6 makes `hi` rise, and the loop runs once for each change within
  36 hours: once or not at all on the JDK's data (fact 11).
- `private long localStarted(long L)`: the count with `lo <= L`, the same way with `loOf`.
- `public int localChanges(long least, long greatest)`: `MANY` when the range is not inside
  the clamp (so no form covers it and the batch takes the row path); 0 when `greatest <
  least`; else `(int) Math.min(localStarted(greatest) - localRank(least), MANY)`: the windows
  with `hi > least` and `lo <= greatest`, each change that can touch a local time of the range.
- `public void toInstantArgs(Rule rule, long least, long greatest, int steps, long[] args, int
  first)`: throws `IllegalArgumentException` when the range is not inside the clamp, or when
  `c = localChanges(least, greatest)` exceeds `steps` (the text of 4.3). With `r0 =
  localRank(least)`: `args[first] = secondsOf(r0) * MICROS`, the *base*. Then, for each change
  `r = r0 + i`, with `delta = (secondsOf(r + 1) - secondsOf(r)) * MICROS`, `after =
  secondsOf(r + 1) * MICROS` and `gap = delta > 0`:

| rule | the arguments of step `i`, in order | count | padding |
| :--- | :--- | ---: | :--- |
| `EARLIER` | `hiOf(r)`, `delta` | `1 + 2 * steps` | `Long.MAX_VALUE`, 0 |
| `GAP_END` | `hiOf(r)`, `gap ? loOf(r) : hiOf(r)`, `delta` | `1 + 3 * steps` | `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0 |
| `KEPT` | `hiOf(r)`, `gap ? hiOf(r) : loOf(r)`, `after`, `delta` | `1 + 4 * steps` | `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0, 0 |

The scalar meaning, from `VARKA-657.md` 2.4 and 3.7, which the tests of part b evaluate and
row 690's lowering must equal. `offset(L)` is the base plus the `delta` of each step that
fires:

- `EARLIER`: step `i` fires when `L >= hi[i]`. The instant is `L - offset(L)`. It is
  `LocalDateTime.atZone`.
- `KEPT`, with `kept` the offset the value had, in microseconds: step `i` fires when `L >=
  hi[i] || (L >= lo[i] && kept == after[i])`. It is `ZonedDateTime.ofLocal(ldt, zone,
  ZoneOffset.ofTotalSeconds(kept / MICROS))`. For a gap `lo[i]` is `hi[i]`, so no local time
  is inside.
- `GAP_END`: `L'` is `hi[i]` where `lo[i] <= L < hi[i]` for some `i`, else `L`; then
  `EARLIER` on `L'`. For an overlap `lo[i]` is `hi[i]`. Inside a gap the instant is the
  change's own instant. It is `LocalDate.atStartOfDay` at a midnight.

A window is in at most one step's reach, since windows do not touch (C6).

### 4.6 Fixed zones, and a zone with no change

`build` of a fixed `ZoneRules` gives `n = 0`, `offsetSeconds = {the offset}`, `block = 0`,
`fixed = true` and no check. Every count is 0, `toLocalArgs` writes the offset and padding.
The cache does not keep it (4.9): a fixed offset has 129,601 possible values and costs nothing
to build. A zone with a standard-time change and no change of the wall offset is not fixed for
`java.time` and has `n = 0`: it is served, with one offset.

### 4.7 Whole minutes and whole hours

`public long wholeMinutesFrom()` is the least instant `m` such that every offset in force at
or after `m` is a multiple of 60 seconds. It is total, by these rules in order, with `g =
60`:

1. if `block > 0` and any of `offsetSeconds[p]` to `offsetSeconds[n]` is no multiple of `g`:
   `Long.MAX_VALUE`. The rule era then holds an odd offset in every year, for ever;
2. if `offsetSeconds[n]` is no multiple of `g`: `Long.MAX_VALUE`;
3. else let `i` be the least index such that `offsetSeconds[i]` to `offsetSeconds[n]` are
   all multiples of `g`. If `i == 0`: `Long.MIN_VALUE`, every offset is whole;
4. else `at[i - 1]`, the change that ended the last odd offset.

`public long wholeHoursFrom()` is the same with `g = 3600`. Both are computed in `build`. No
merged plan reads them yet: `VARKA-685.md` 4.10 reduces the filled offsets instead. The row's
line asks for them.

### 4.8 What row 686's table reads

- `public long[] changesAt(long from, long until)`: a new array of the changes with `from <=
  instant < until`, ascending. `public int[] changeOffsets(long from, long until)`: a new array
  one longer, the offset in seconds before the first of them and after each. Both start from
  two ranks: `first = from == Long.MIN_VALUE ? 0 : rank(from - 1)` and `end = until ==
  Long.MIN_VALUE ? 0 : rank(until - 1)`, so nothing overflows at the least long, and `count =
  Math.toIntExact(Math.max(0, end - first))`. `changesAt` returns `atOf(first + i)` for `i`
  from 0 to `count - 1`; `changeOffsets` returns `secondsOf(first + i)` for `i` from 0 to
  `count`. With no change in the range, or `until <= from`, `changesAt` is an empty array and
  `changeOffsets` is one element, the offset in force just before `from`. With `from =
  VarkaZoneTable.ORIGIN` and `until = VarkaZoneTable.LIMIT` they are `pack`'s first two
  arguments. They allocate, and run once for a zone.
- `public long[] memo(Function<VarkaChronoZoneChanges, long[]> build)`: returns the array the
  entry keeps, calling `build` under `synchronized (this)` the first time, after a read of the
  volatile field that finds it null. `build` must not return null; an empty array means "this
  zone has none". Row 686's part a keeps the packed table there:

      // in VarkaChronoZone, added by row 686's part a
      public boolean toLocalTable(long[] args, int first) {
        long[] table = data.memo(VarkaChronoZone::packed);   // empty: pack answered false
        if (table.length == 0) return false;
        System.arraycopy(table, 0, args, first, table.length);
        return true;
      }

### 4.9 The cache: `VarkaChronoZones`

    public final class VarkaChronoZones {
      private static final ConcurrentHashMap<ZoneRules, VarkaChronoZoneChanges> ENTRIES = ...;
      public static VarkaChronoZoneChanges of(ZoneRules rules);
      public static int cached();
      static void clearForTesting();
    }

- `of`: a fixed `rules` is built and returned, not kept. Else
  `ENTRIES.computeIfAbsent(rules, VarkaChronoZoneChanges::build)`.
- **The key is the rules, by value** (fact 8). Ids with the same rules share an entry, as the
  row's line asks, and nothing is keyed by an id. A lookup hashes the rules' arrays, a few
  hundred elements; it runs at a compile and once for a task's runner, never for a batch.
- **Thread safety.** `computeIfAbsent` builds an entry at most once: a second thread that asks
  for the same rules waits for the first, and threads that ask for other rules do not. `build`
  calls only `java.time` and never the cache, so the mapping function cannot recurse. An entry
  is safely published: its fields are final and its arrays are never written after the
  constructor. The one later write is `memo`, a volatile field written once under the entry's
  monitor.
- **An entry that failed its check is kept too,** with `served()` false, so a zone that is not
  served is checked once and not on every compile.
- **No eviction.** The map is bounded by the JDK's data: one entry for each distinct set of
  rules that a query of this JVM named, at most 349 on time-zone data 2026f (fact 11).
- **It follows the JVM.** The key is the `ZoneRules` object that `ZoneId.getRules()` returns
  when the provider is asked. If a `ZoneRulesProvider` gives an id new rules, the next lookup
  finds or builds the entry of the new rules, the ones the row engine reads from then on.

### 4.10 The production zone data: `VarkaChronoZone`

    public record VarkaChronoZone(String id, VarkaChronoZoneChanges data)
        implements VarkaZoneData {
      public static final VarkaZoneData.Provider PROVIDER = VarkaChronoZone::of;
      public static @Nullable VarkaChronoZone of(String zoneId);
    }

- `of(zoneId)`: `ZoneId zone = DateTimeUtils.getZoneId(zoneId)`, then `VarkaChronoZones
  .of(zone.getRules())`, both inside one `try`; a `java.time.DateTimeException` gives null.
  Null also when the entry is not served. Else `new VarkaChronoZone(zone.getId(), data)`. So
  every spelling that Spark accepts resolves as Spark resolves it: `PST` gives the id
  `America/Los_Angeles` and that zone's entry, and `+1:00` gives `+01:00`.
- `fixed()`, `changes`, `localChanges` and `toLocalArgs` delegate to `data`.
- `toInstantArgs(VarkaVectorIR.Zoned node, ...)` is a `switch` over the sealed `Zoned` with no
  `default`. Its one arm, `case VarkaVectorIR.ToLocal t`, throws `IllegalArgumentException("a
  ToLocal reads toLocalArgs")`, as the reference does. Row 690 adds the arms of its records,
  each one call of `data.toInstantArgs(Rule, ...)`; the compiler makes it, since the switch
  stops compiling when `Zoned` permits a record more.
- `of` allocates one small record, at a compile and for a task's runner. No method that a
  batch calls allocates.

### 4.11 Where the data comes from, and where it is resolved

- **From the running JDK.** The only source is the `ZoneRules` of the JVM that runs the
  kernel. The row ships no table and reads no file. An update of the JDK's time-zone data
  moves the kernels and the row engine of that JVM together.
- **On the executor.** A compiled plan holds zone ids, strings (`CompiledVarkaProjection
  .zones()`, row 682); the IR holds an index. `VarkaZoneData.Provider.of` is called where the
  kernel is compiled and where a task's runner is built, both on the executor
  (`VARKA-657.md` 3.1; `VARKA-682.md` 4.8). `VarkaChronoZones` is a static of the JVM that
  loads it.
- **No entry crosses.** Neither `VarkaChronoZoneChanges` nor `VarkaChronoZone` implements
  `Serializable`, and no class of this row holds one in a field that a plan serializes. A
  later change that captured an entry in a task's closure would fail the task's serialization
  in every test that runs a query, not answer with the driver's rules.

### 4.12 Edge cases

| case | what happens | what holds it |
| :--- | :--- | :--- |
| `Long.MIN_VALUE`, `Long.MAX_VALUE` as an instant | exact: `rank` and `atOf` cannot overflow (4.2) | part a, tests 3 and 7 |
| a local range within 36 hours of an end of a long | `localChanges` is `MANY`; `toInstantArgs` throws | part b, test 5 |
| `greatest < least` | 0 changes; `toLocalArgs` writes the base at `least` and padding | part a, tests 7 (the count) and 8 (the inverted pair) |
| a change exactly at `least`, or at `greatest` | not counted; counted | part a, tests 7 and 8 |
| the year 9999's end, the years before 1582 | instants like any other: the data has no calendar, and before the first stored change the offset is `offsetSeconds[0]` | part a, test 3; part b, test 5 |
| a zone with rules, an instant thousands of years on | reduced by whole cycles | part a, test 4 |
| a zone whose stored changes reach past today and that has no rule (a change in the future) | stored changes like any other | part a, test 6 |
| a zone with no change that is not fixed | served, `n = 0` | part a, test 10 |
| a zone whose rules `java.time` reads in another year, or out of order, or whose windows touch | not served: the compile declines by name | part a, test 9; part b, test 11 |
| an id in another spelling (`PST`, `+1:00`, `UTC+1`) | resolved by Spark's own function | part d, tests 1 and 2 |
| an unknown id | `of` returns null and throws nothing | part d, test 3 |
| two threads, one zone | one entry | part c, test 3 |
| a batch with no live row, a null row, two lanes, AVX2 against AVX-512 | not this row's: the data is scalar Java, read before the kernel runs; the runner fills nothing for a batch with no live row (`VARKA-682.md` 4.8) | - |

**What declines by name.** This row writes no decline text. A zone that is not served makes
`PROVIDER.of` return null, and row 657's `VarkaZoneCompiler` declines with its own reason. A
range of more changes than a kernel's form holds is the runner's `STATUS_BATCH_ARGS` (row
682): it asks `changes` first.

### 4.13 What is out of this row

| what | the row that holds it |
| :--- | :--- |
| the IR records, their lowering, the forms and the option `zoneForm`; the runner's fill and its decline of a batch within 18 hours of an end of a long | 682 |
| the compiler's use of the provider, the fold of a fixed zone, the family `ZONE` and every decline text | 657 |
| the range of a batch: the pass, the cache's statistics, a computed operand | 683, 687, 688 |
| the choice of a form for a batch | 685 |
| the bucket's layout and packing (`VarkaZoneTable`), `toLocalTable` itself, the table's kernel | 686 |
| a bucket table of the local time line | 693 |
| the lowering of the three rules, and the arms of `VarkaChronoZone.toInstantArgs` | 690 |
| the kernels' every-zone sweep, the planted faults of the kernels, the fuzzers' zone | 684, 690 |
| `second` and `minute` with no offset | 685, its part e |
| the steps with no mask at two lanes | 689 |
| the forms measured in kernels, and G7's reading | 691 |
| the library's repository, its CI, its release and its benchmarks at each vector width | 679 |
| the register's entry for the JDK's time-zone data | 681 |
| the nanosecond timestamps | 662, 673 to 675 |
| a zone read from a column | out of the milestone (`m9/PLAN.md` 1.3) |

No part of this row moves a census cell, an emitted byte, a generated file or an existing
benchmark's results.

## 5. The parts

Four pull requests, each under 1000 counted lines (`python3 dev/varka_push_check.py` prints
the count; `dev/varka_msg_check.py pr` fails at the cap).

| part | title | needs | gives | lines |
| :--- | :--- | :--- | :--- | ---: |
| a | `[VARKA-678] Lay a zone's changes out as sorted arrays, checked against java.time` | nothing | `VarkaChronoZoneChanges` with the instant line and checks C1 to C5; the fixtures | 880 |
| b | `[VARKA-678] Add the local time line and the three rules to a zone's changes` | a | the local line, C6 and C7, the whole-minute instants, the table's inputs and `memo` | 800 |
| c | `[VARKA-678] Keep one entry for each set of zone rules in a JVM, and measure it` | b | `VarkaChronoZones`, the benchmark and its results | 480 |
| d | `[VARKA-678] Serve Varka's zone data from the JVM's own rules` | b, c, and row 682's part c | `VarkaChronoZone`, `PROVIDER`; the row's Outcome | 400 |

The parts are built in the order a, b, c, d, each after the one before has merged; no two are
built side by side. Parts a and b both edit `VarkaChronoZoneChanges.java`, part c's benchmark
reads part b's methods, and part d calls part c's cache. Part d also waits for row 682's part
c. Part d is the part that row 657 and every row after it reads.

Every shell starts with:

    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1
    V=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    T=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    B=sql/catalyst/src/test/java/org/apache/spark/sql

`$VARKA_HEAVY_LOCK` is the machine's lock for a heavy JVM; every `build/sbt` runs under `flock`
of it. A JUnit run prints sbt's line `Passed: Total N, Failed 0, Errors 0, Passed N`.

**Stop rules, every part.** Stop and report to the lead when: a B0 line prints something
else; a test of the sheet fails for a reason the sheet does not name, above all test 1 of
part a (a zone of the JDK that the check refuses is a finding for the owner, not a thing to
relax); the count passes 950 (for part a, after its cut line was taken); anything is left to
decide.

**Every new file** starts with the Apache licence header of a neighbouring file. Every class,
constant and method has a comment that a reader new to the code can follow, saying what the
code is, not what it used to be.

**Words the test tables share.** Every suite is in the package of `$V`, so it reads the
package-private view of 4.2.

- *Each set*: the list the test names, walked by `eachSet`; its random source is `new
  SplittableRandom(seed() + set.index())`, made inside the body, so a parallel walk draws the
  same numbers as a serial one. *Uniform from `a` to
  `b`* is `random.nextLong(a, b)`.
- *The domain of a set* is from `D0` to `D1 = 16725225600000000` (2500-01-01T00:00:00Z),
  where `D0` is `-5364662400000000` (1800-01-01T00:00:00Z) for a set whose `size()` is 0, and
  else the lesser of that and `atOf(0) - 315360000000000` (ten years of 365 days before the
  first change).
- *A random change* is `r = random.nextInt(size())`. An input that names a change skips a set
  whose `size()` is 0.
- *The range of `k` changes at `r`*, a pair `(least, greatest)` that holds exactly `k`
  changes: for `k == 0`, `(atOf(r), atOf(r) + 1)`; for `k >= 1`, `(atOf(r) - 1, atOf(r + k -
  1))`. It is skipped when `block() == 0` and `r + k > size()`.
- *A walk of `nextTransition` from `x` to `y`* (microseconds): `t =
  rules.nextTransition(Instant.ofEpochSecond(Math.floorDiv(x, 1_000_000L)))`, then `t =
  rules.nextTransition(t.getInstant())`, while `t != null` and `t.toEpochSecond() <=
  Math.floorDiv(y, 1_000_000L)`. It yields the changes `T` with `x < T <= y`.

### 5.1 Part a: the entry and the instant line

Branch `varka-678a-zone-changes`.

**B0, the premises.** Each prints what its comment says.

    ls $V/VarkaChronoZoneChanges.java $T/VarkaChronoZoneFixtures.java $T/VarkaChronoZoneChangesSuite.java 2>/dev/null | wc -l   # 0
    git grep -l 'ZoneRules' -- sql/catalyst/src/main sql/core/src/main | wc -l                 # 0
    grep -c 'boolean ownJvm() default false;' $T/VarkaSuite.java                               # 1
    git branch --list 'varka-678a-zone-changes' | wc -l                                        # 0
    "$JAVA_HOME/bin/java" -version 2>&1 | grep -c 'version "25'                                # 1

**Steps.**

1. `$V/VarkaChronoZoneChanges.java`, new: the constants, the fields and the three runs of 4.1;
   `build`, `served()`, `check()`, `fixed()`, `size()` (`n`), `bytes()`; the package-private view of
   4.2 (no modifier), without `loOf` and `hiOf`; the three methods of 4.3; the `Check` record and C1
   to C5 of 4.4, in that order, with `closestWindowsSeconds` set to `Long.MAX_VALUE` and `gaps` and
   `overlaps` counted from the offsets. The imports are of `java.time`, `java.time.zone` and
   `java.util`, each class by name; no Spark class, no Varka class, no stream and no lambda outside
   `build`.
2. `$T/VarkaChronoZoneFixtures.java`, new, a final class of static helpers:
   - `record RuleSet(int index, String id, ZoneRules rules, List<String> ids)`, and `static
     List<RuleSet> sets()`: `ZoneId.getAvailableZoneIds()`, sorted, grouped by
     `ZoneId.of(id).getRules()` in a `LinkedHashMap`; `id` is the group's first id and `index`
     the set's place in the list, from 0. Computed once.
   - `static List<RuleSet> withMadeUp()`: `sets()`, then `threeRules()` and `standardOnly()`
     under the ids `made-up/three-rules` and `made-up/standard-only`, with the next two
     indexes and an empty list of ids.
   - The made-up rules, exactly:

         static ZoneRules threeRules() {      // served: three rules a year, by day of week
           ZoneOffset std = ZoneOffset.ofHoursMinutes(5, 45);
           ZoneOffset dst = ZoneOffset.ofHoursMinutes(6, 45);
           ZoneOffset dbl = ZoneOffset.ofHoursMinutes(7, 45);
           var stored = ZoneOffsetTransition.of(LocalDateTime.of(1990, 10, 28, 3, 0), dst, std);
           return ZoneRules.of(std, dst, List.of(), List.of(stored), List.of(
               rule(Month.MARCH, -1, DayOfWeek.SUNDAY, 2, std, std, dst),
               rule(Month.JUNE, 1, DayOfWeek.SUNDAY, 2, std, dst, dbl),
               rule(Month.OCTOBER, -1, DayOfWeek.SUNDAY, 3, std, dbl, std)));
         }
         static ZoneRules outOfYear() {       // not served: fact 4 of section 3
           ZoneOffset std = ZoneOffset.ofHours(-1);
           ZoneOffset dst = ZoneOffset.UTC;
           var stored = ZoneOffsetTransition.of(LocalDateTime.of(2000, 7, 1, 0, 0), dst, std);
           return ZoneRules.of(std, dst, List.of(), List.of(stored), List.of(
               ZoneOffsetTransitionRule.of(Month.JANUARY, 1, null, LocalTime.MIDNIGHT, false,
                   TimeDefinition.UTC, std, std, dst),
               ZoneOffsetTransitionRule.of(Month.JULY, 1, null, LocalTime.MIDNIGHT, false,
                   TimeDefinition.UTC, std, dst, std)));
         }
         static ZoneRules standardOnly() {    // served, not fixed, no change of the offset
           ZoneOffset two = ZoneOffset.ofHours(2);
           var standard = ZoneOffsetTransition.of(
               LocalDateTime.of(2000, 1, 1, 0, 0), two, ZoneOffset.ofHours(3));
           return ZoneRules.of(two, two, List.of(standard), List.of(), List.of());
         }

     `rulesOnly()`, served: `ZoneRules.of(std, std, List.of(), List.of(), <the three rules
     of threeRules()>)`, rules and no stored change, which `java.time` answers with the base
     offset alone (fact 3);
     and three more that are not served, each by one edit of `threeRules()`: `outOfOrder()`,
     its three rules in the order October, June, March; `brokenChain()`, its March rule with
     `dbl` as the offset before; and `tooFar()`, no rule and the one stored change
     `ZoneOffsetTransition.of(LocalDateTime.of(900000, 1, 1, 0, 0), std, dst)` with `std` as
     both base offsets.
     `rule(month, day, dayOfWeek, hour, standard, before, after)` is
     `ZoneOffsetTransitionRule.of(month, day, dayOfWeek, LocalTime.of(hour, 0), false,
     TimeDefinition.WALL, standard, before, after)`.
   - `static int javaOffset(ZoneRules rules, long micros)`:
     `rules.getOffset(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L)))
     .getTotalSeconds()`. `static String tzdata()`:
     `ZoneRulesProvider.getVersions("UTC").lastKey()`. `static long seed()`:
     `Long.getLong("varka.zone.seed", 678L)`.
   - `static void eachSet(List<RuleSet> sets, BiConsumer<RuleSet, List<String>> body)`: runs
     `body` over `sets.parallelStream()`, each with its own list of failure lines, and fails
     with the first ten lines and the seed if any list is not empty. A failure line names the
     set's id, the instant and both answers.
3. `$T/VarkaChronoZoneChangesSuite.java`, new: the eleven tests below.
4. `sql/varka/plans/m9/PLAN.md`, row 678's status, as 5.5 has it for part a. If the row
   still reads `**Filed**`, the plan's own pull request has not merged: stop.

**Tests.** `VarkaChronoZoneChangesSuite`, JUnit 5, Java, catalyst, `@VarkaSuite`, each `@Test`
with its sentence as a `@DisplayName`. The oracle of each is `java.time`. "Each set" is
`withMadeUp()` through `eachSet`, with the entry from `VarkaChronoZoneChanges.build`.

| # | test (its display name in short) | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | every set of rules of this JVM builds and passes its check | `sets()` | `served()` for each, else the message holds the id and `check().failure()`; `check()` is never null; the count of ids is `ZoneId.getAvailableZoneIds().size()`; prints `checked: <sets> sets of rules for <ids> zone ids, tzdata <tzdata()>, <changes> changes held, <equal> stored changes with equal offsets, <compared> lookups`, where `<changes>` sums `size()`, `<compared>` sums `check().compared()` and `<equal>` counts the elements of each `rules.getTransitions()` whose two offsets are equal | a zone of a new time-zone data that the layout cannot hold, found at the JDK's update and not in a query |
| 2 | the offset around each change the entry holds is java.time's | each set; each change `r` from 0 to `size() - 1`: `atOf(r) - 1000000`, `atOf(r) - 1`, `atOf(r)`, `atOf(r) + 1`, `atOf(r) + 1000000` | `offsetSecondsAt` equals `javaOffset`; for a set of `sets()` (not a made-up one) it also equals `Instant.ofEpochSecond(Math.floorDiv(x, 1000000L)).atZone(ZoneId.of(set.id())).getOffset().getTotalSeconds()`, the call Spark's row path makes. A stored change with equal offsets, if test 1 counted one, is among these changes: this test is its case | a rank that counts `<` for `<=`: every row at a change in the old offset |
| 3 | the offset in the far past and at both ends of a long is java.time's | each set; `Long.MIN_VALUE`, `Long.MIN_VALUE + 1`, -62135596800000000 (0001-01-01), -12219292800000000 (1582-10-15), `atOf(0) - 1` where `size() > 0`, 253402300799999999 and 253402300800000000 (the year 9999's end), `Long.MAX_VALUE - MAX_OFFSET_MICROS`, `Long.MAX_VALUE` | the same | an overflow in the reduction by cycles; an offset before the first change taken from the first change's other side |
| 4 | a rule change 1, 2, 10, 100 and 700 cycles on, and in the last cycle of a long, is java.time's | each set with `block() > 0`; each block change `r`; `x = atOf(r) + k * CYCLE_MICROS` for `k` of 1, 2, 10, 100, 700 and `(Long.MAX_VALUE - atOf(r)) / CYCLE_MICROS`: the instants `x - 1` and `x` | the same; and at least one set of `sets()` has `block() > 0` | a cycle that is a day short; an image indexed into the wrong year of the block |
| 5 | 25000 random instants a set, by seed, are java.time's | each set; 20000 uniform over the set's domain, then 5000 from `random.nextLong()`, every long | the same | a stretch between changes that the edges do not reach |
| 6 | every second for two hours either side of each change from 2026 to 2040 | each set; each change `r` below `size()` with `1767225600000000 <= atOf(r) < 2240611200000000` (2026-01-01 to 2041-01-01); `atOf(r) + s * 1000000` for `s` from -7200 to 7200 | the same; and among `sets()` at least one set with such a change has `block() > 0` and at least one has `block() == 0` (a stored change in the future) | a zone whose change in the future comes from a rule in one JDK and from the stored list in the next |
| 7 | `changes` counts a change at `greatest` and not one at `least` | each set; 200 pairs `(least, greatest)`, each end uniform over the set's domain, in the order drawn; for each of 20 random changes, with `t = atOf(r)`, the pairs `(t, t)`, `(t - 1, t)`, `(t, t + 1)`, `(t + 1, t)`; and `(Long.MIN_VALUE, Long.MAX_VALUE)` | 0 when `greatest <= least`; else the length of a walk of `nextTransition` from `least` to `greatest`, stopped at 4096 | an end of the range off by one, which is a step the kernel lacks or one too many |
| 8 | `toLocalArgs` writes the base, the steps in order and the padding, and a scalar step loop over them is java.time's local time | each set; for each of 20 random changes and each `k` of 0, 1, 2 and 8, the range of `k` changes at `r`, into a `new long[23]` filled with -1, with `first = 3` and `steps = 8` (the program is indexes 3 to 19); and, for each of those 20 changes, the inverted pair `(atOf(r) + 5, atOf(r) - 5)` with the same array, `first` and `steps` | `args[3]` is `javaOffset(least) * 1000000`; for `x` of `least`, `greatest` and, for each change of the range, `at - 1`, `at`, `at + 1`: `x + args[3] + sum over i of (x >= args[4 + 2 * i] ? args[5 + 2 * i] : 0)` equals `x + javaOffset(x) * 1000000`; steps `k` to 7 are `Long.MAX_VALUE`, 0; indexes 0 to 2 and 20 to 22 are still -1; for `k >= 1` the same call with `steps = k - 1` throws `IllegalArgumentException`; and for the inverted pair nothing throws, `args[3]` is `javaOffset(least) * 1000000`, all 8 steps are `Long.MAX_VALUE`, 0, and indexes 0 to 2 and 20 to 22 are still -1 | a difference written as the new offset; a step left from the batch before; a write outside the program |
| 9 | made-up rules that the layout cannot hold are not served, each with its reason | `outOfYear()`, `outOfOrder()`, `brokenChain()`, `tooFar()` | for `outOfYear()` first the premise: `rules.getOffset(Instant.parse("2001-01-01T00:00:00Z"))` is -01:00, though the rule's change at that instant goes to +00:00; then, for each, `build` throws nothing, `served()` is false and the failure starts with, in order: `java.time differs at instant`, `changes out of order`, `a rule's offset before is not the offset in force`, `a change outside a long of microseconds` | a check that passes everything; a build that throws inside a compile |
| 10 | a zone with no change is not fixed unless java.time says so | `standardOnly()`; `rulesOnly()`; `ZoneOffset.ofHoursMinutes(5, 30).getRules()`. The five instants: `Long.MIN_VALUE`, 0, 1751328000000000 (2025-07-01), 1893456000000000 (2030-01-01), `Long.MAX_VALUE` | `standardOnly()`: `fixed()` false, `size()` 0, served, `offsetSecondsAt` 7200 at the five instants. `rulesOnly()`: first the premise, `rules.getOffset(Instant.parse("2030-07-01T00:00:00Z")).getTotalSeconds()` is 20700, the base offset, though a rule would have changed it; then `fixed()` false, `size()` 0, `block()` 0, served, 20700 at the five instants. The offset: `fixed()` true, `size()` 0, `check().failure()` null, `changes(Long.MIN_VALUE, Long.MAX_VALUE)` 0, and `toLocalArgs(0, 1, 2, new long[5], 0)` writes 19800000000, `Long.MAX_VALUE`, 0, `Long.MAX_VALUE`, 0 | a zone with one offset that declines; rules read where `java.time` does not read them |
| 11 | 400 years are 146097 days, a whole number of weeks, and the made-up zone holds one cycle | - | `CYCLE_MICROS == 146097L * 86400 * 1000000`; `146097 % 7 == 0`; `LocalDate.of(2400, 1, 1).toEpochDay() - LocalDate.of(2000, 1, 1).toEpochDay() == 146097`; the entry of `threeRules()` has `size()` 1201 and `bytes()` 14416 | a block of 399 or 401 years |

**Commands**, in order, with what each prints.

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaChronoZoneChangesSuite'
        # Passed: Total 11, Failed 0, Errors 0, Passed 11; and one line that starts "checked: "
    git diff --exit-code -- sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json sql/varka/operator_census.json sql/varka/types.tsv
        # exits 0: no generated file moves
    dev/varka_precommit.sh                                                                     # exits 0
    python3 dev/varka_push_check.py                                                            # exits 0, a size near 880

Then JaCoCo over the class, with the commands of 6.3: every method of
`VarkaChronoZoneChanges.java` entered, and each branch missed named in the pull request with
its reason (test 9 reaches the failures of C1 to C4; C5's is reached only by a planted fault,
4.4; the `DateTimeException` arm by nothing, since no made-up rules known here make
`java.time` throw).

**Planted faults**, each planted after the commit, run, and reverted; the pull request lists
each with the test that failed.

| fault | fails |
| :--- | :--- |
| `146_096L` in `CYCLE_MICROS` | tests 11 and 4 |
| `rank`'s search counts `at < x` | test 2, at `at` itself |
| C4 removed | test 9: `outOfYear()` fails with C5's text, not C4's |
| `toLocalArgs` writes no padding | test 8 |
| `toLocalArgs` writes the offset after as `delta` | test 8 |

**The cut line.** If, with everything above written, `python3 dev/varka_push_check.py`
counts more than 900 lines, part a becomes two pull requests, and nothing else changes:

- *Part a* keeps the class, the fixtures `sets()`, `withMadeUp()`, `threeRules()`,
  `standardOnly()` and the helpers, and tests 1 to 8. Its command prints `Passed: Total 8`.
  Of its planted faults it keeps the second, the fourth and the fifth, and `146_096L`, which
  then fails test 4 alone.
- *Part a2*, `[VARKA-678] Hold with made-up rules what the zone layout refuses`, branch
  `varka-678a2-made-up-rules`, before part b: the fixtures `outOfYear()`, `outOfOrder()`,
  `brokenChain()`, `tooFar()` and `rulesOnly()`, and tests 9, 10 and 11, added to the same
  suite, which then prints `Passed: Total 11`; the planted fault "C4 removed"; about 200
  lines. Its B0 is part b's first line and `grep -c 'static ZoneRules outOfYear()'
  $T/VarkaChronoZoneFixtures.java` printing 0. Row 678's status then names it as 5.5 says.

Part b's B0 expects `outOfYear()` to exist, so part a2, where it exists, merges before part b.

**Done when** the commands print the above, the faults fail as named, and the pull request's
description holds test 1's line, the JaCoCo line, the size and the review's findings.

**Not here:** no reading of OpenJDK's `java.time` sources (section 3); no local line, no cache, no
`VarkaZoneData`, no file under `sql/core`, no emit option, no generated file, no benchmark.

### 5.2 Part b: the local line

Branch `varka-678b-local-line`.

**B0.**

    grep -c 'public static VarkaChronoZoneChanges build(ZoneRules rules)' $V/VarkaChronoZoneChanges.java   # 1
    grep -c 'int localChanges(\|void toInstantArgs(\|enum Rule {' $V/VarkaChronoZoneChanges.java   # 0: no such declaration yet (a comment may use the words)
    grep -c 'static ZoneRules outOfYear()' $T/VarkaChronoZoneFixtures.java                     # 1
    ls $T/VarkaChronoZoneLocalSuite.java 2>/dev/null | wc -l                                   # 0
    git branch --list 'varka-678b-local-line' | wc -l                                          # 0

**Steps.**

1. `$V/VarkaChronoZoneChanges.java`: `loOf`, `hiOf`; `enum Rule { EARLIER, GAP_END, KEPT }`;
   `localRank`, `localStarted`, `localChanges` and `toInstantArgs` of 4.5; C6 and C7 of 4.4,
   with `closestWindowsSeconds` now computed; `wholeMinutesFrom()` and `wholeHoursFrom()` of
   4.7; `changesAt`, `changeOffsets` and `memo` of 4.8 (the one import added is
   `java.util.function.Function`).
2. `$T/VarkaChronoZoneFixtures.java`: the helpers of the local oracle.
   - `static LocalDateTime local(long micros)`: `LocalDateTime.ofEpochSecond(Math.floorDiv
     (micros, 1_000_000L), (int) Math.floorMod(micros, 1_000_000L) * 1000, ZoneOffset.UTC)`;
     `static long micros(Instant i)`: seconds times 1,000,000 plus `getNano() / 1000`, exact.
   - `static long earlier(long[] a, int f, int steps, long L)`, `kept(a, f, steps, L, long
     keptMicros)` and `gapEnd(a, f, steps, L)`: the three scalar meanings of 4.5, over
     arguments at `f`. Every argument and result is in microseconds, the kept offset too; the
     oracle's `ZoneOffset` is `ZoneOffset.ofTotalSeconds((int) (keptMicros / 1_000_000L))`.
   - `static ZoneRules touching()`: two stored changes whose windows overlap, not served:

         ZoneOffset one = ZoneOffset.ofHours(1);
         ZoneOffset two = ZoneOffset.ofHours(2);
         var gap = ZoneOffsetTransition.of(LocalDateTime.of(2000, 3, 26, 2, 0), one, two);
         var back = ZoneOffsetTransition.of(LocalDateTime.of(2000, 3, 26, 3, 30), two, one);
         return ZoneRules.of(one, one, List.of(), List.of(gap, back), List.of());

3. `$T/VarkaChronoZoneLocalSuite.java`, new: the eleven tests below.
4. `$T/VarkaChronoZoneChangesSuite.java`: test 1's line gains, at its end, `, closest windows
   <s> s (<id>)`, the least `check().closestWindowsSeconds()` over the sets and its set's id.
   Nothing else is edited there. If C6 or C7 refuses a zone of the JDK, test 1 fails: that
   is a stop.
5. Row 678's status in `m9/PLAN.md`, as 5.5 has it for part b.

**Tests.** `VarkaChronoZoneLocalSuite`, JUnit 5, Java, catalyst, `@VarkaSuite`. "Each set" is
`sets()`, the JDK's own, with `zone = ZoneId.of(set.id())`, since the oracles are
`java.time`'s methods over a `ZoneId`. Unless a line says otherwise the arguments are filled
by `toInstantArgs(rule, L - 86400000000, L + 86400000000, 8, args, 0)` into a `new
long[33]`, a day either side of the local time `L`; a local time whose range of a day either
side touches more than 8 windows is skipped and counted, and the count is asserted to be 0
for `sets()`. "The edges of a window", for a change `r` with `lo = loOf(r)` and `hi =
hiOf(r)`, are `lo - 1000000`, `lo - 1`, `lo`, `lo + 1`, `lo + (hi - lo) / 2`, `hi - 1`, `hi`,
`hi + 1`, `hi + 1000000`; "every window" is each change `r` from 0 to `size() - 1`. The
three oracles, with `ldt = local(L)`: *atZone* is `micros(ldt.atZone(zone).toInstant())`;
*ofLocal* is `micros(ZonedDateTime.ofLocal(ldt, zone, ZoneOffset.ofTotalSeconds((int)
(keptMicros / 1000000))).toInstant())`; *gap's end* is, with `t = rules.getTransition(ldt)`,
`t.toEpochSecond() * 1000000` when `t != null && t.isGap()`, else atZone.

| # | test | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | each window is a gap or an overlap as java.time has it | each set, each change `r` whose `secondsOf(r)` and `secondsOf(r + 1)` differ: the local times `local(lo)`, `local(hi - 1)` (inside) and `local(lo - 1)`, `local(hi)` (outside) | inside: `rules.getValidOffsets` has 0 elements where `secondsOf(r + 1) > secondsOf(r)` and 2 where it is less, and `rules.getTransition(...).toEpochSecond() * 1000000` is `atOf(r)`; outside: 1 element | a window on the wrong side of its change |
| 2 | the earlier-offset rule at the edges of every window is `LocalDateTime.atZone` | each set, the edges of every window, `Rule.EARLIER` | `earlier(args, 0, 8, L)` equals atZone | `min` for `max` in a threshold |
| 3 | the kept-offset rule is `ZonedDateTime.ofLocal` for the offset before, the offset after and a third | each set, the edges of every window, `Rule.KEPT`, with `keptMicros` each of `secondsOf(r) * 1000000`, `secondsOf(r + 1) * 1000000` and `(secondsOf(r) + 3600) * 1000000`; the third is skipped where `secondsOf(r) + 3600 > 64800` | `kept(args, 0, 8, L, keptMicros)` equals ofLocal | a kept offset honoured inside a gap; a kept offset ignored inside an overlap |
| 4 | the gap's-end rule is the change's instant inside a gap, `atZone` elsewhere, and `LocalDate.atStartOfDay` at a midnight | each set, `Rule.GAP_END`: the edges of every window, and the two midnights `Math.floorDiv(lo, 86400000000) * 86400000000` and `Math.floorDiv(hi, 86400000000) * 86400000000` | `gapEnd(args, 0, 8, L)` equals gap's end; at each of the two midnights it also equals `micros(local(L).toLocalDate().atStartOfDay(zone).toInstant())`; prints `midnights strictly inside a gap: <count>`, the midnights `L` with `lo < L < hi` in a gap | an overlap treated as a gap; the one rule Spark's cast from `DATE` reads |
| 5 | the three rules in the far past, the far future and at the clamp | (i) each set with `block() > 0`: the edges of every block change's window, each plus `k * CYCLE_MICROS` for `k` of 1, 10 and 700. (ii) each set: the local times -62135596800000000, -12219292800000000 and 253402300799999999. (iii) each set, with `E = Long.MAX_VALUE - 2 * MAX_OFFSET_MICROS` and `S = Long.MIN_VALUE + 2 * MAX_OFFSET_MICROS`: the four ranges `(0, E + 1)`, `(S - 1, 0)`, `(E, E)` and `(S, S)` | (i) and (ii): the three rules against their oracles as in tests 2 to 4, with `keptMicros = secondsOf(r) * 1000000` in (i) and 0 in (ii). (iii), first two ranges: `localChanges(least, greatest)` is 4096, and `toInstantArgs(Rule.EARLIER, least, greatest, 8, new long[17], 0)` throws `IllegalArgumentException`. (iii), last two, with `L` the one value: `localChanges(L, L)` is 0 or 1, `toInstantArgs(Rule.EARLIER, L, L, 8, new long[17], 0)` throws nothing, and `earlier(args, 0, 8, L)` equals atZone | a threshold that wraps near an end of a long and fires for every row |
| 6 | 20000 random local times a set, by seed, under the three rules | each set; 10000 times `L` uniform from `loOf(r) - 10800000000` to `hiOf(r) + 10800000000` of a random change `r`, with `keptMicros` one of `secondsOf(r) * 1000000` and `secondsOf(r + 1) * 1000000` by `random.nextBoolean()`; then 10000 times `L` uniform over the set's domain with `keptMicros = offsetSecondsAt(L) * 1000000`; a set of `size()` 0 takes 20000 of the second kind | the three rules against their three oracles | a local time between windows that the edges do not reach |
| 7 | `localChanges` counts the windows a range touches | each set; 200 pairs with each end uniform over the set's domain, in the order drawn; and, for each of 20 random changes with `lo = loOf(r)`, `hi = hiOf(r)`: `(lo, lo)`, `(lo - 1, lo - 1)`, `(hi, hi)`, `(hi - 1, hi - 1)`, `(hi, lo)` | 0 when `greatest < least`; else the count, stopped at 4096, of the transitions `t` of a walk of `nextTransition` from `least - 2 * MAX_OFFSET_MICROS - 1000000` to `greatest + 2 * MAX_OFFSET_MICROS` for which, with `T = t.toEpochSecond() * 1000000` and `b`, `a` its two offsets in microseconds, `T + Math.max(b, a) > least` and `T + Math.min(b, a) <= greatest` | a window that starts inside the range and ends outside it left out, so that a kept offset there is missed |
| 8 | `toInstantArgs` keeps its order, pads, and throws past its steps | the entry of `ZoneId.of("America/Los_Angeles").getRules()`; `least = 1735689600000000` (2025-01-01T00:00 as a local long), `greatest = 1767139200000000` (2025-12-31T00:00); each rule into a `new long[24]` filled with -1, `first = 2`, `steps = 4` | `localChanges` is 2. `args[2]` is -28800000000 for each rule. `EARLIER` (indexes 2 to 10): step 0 is 1741489200000000 (03:00 on 9 March), 3600000000; step 1 is 1762048800000000 (02:00 on 2 November), -3600000000; steps 2 and 3 are `Long.MAX_VALUE`, 0; indexes 11 to 23 are -1. `GAP_END` (2 to 14): step 0 is 1741489200000000, 1741485600000000 (02:00), 3600000000; step 1 is 1762048800000000, 1762048800000000, -3600000000; padding `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0. `KEPT` (2 to 18): step 0 is 1741489200000000, 1741489200000000, -25200000000, 3600000000; step 1 is 1762048800000000, 1762045200000000 (01:00), -28800000000, -3600000000; padding `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0, 0. Indexes 0, 1 and those past each program are -1. With `steps = 1` each rule throws `IllegalArgumentException` | two arguments of a step swapped, which every rule's sweep could miss only by luck |
| 9 | `wholeMinutesFrom` and `wholeHoursFrom` are the change that ends the last odd offset | each set, with `g` of 60 and of 3600 | from `java.time` alone: `Long.MAX_VALUE` if any rule of `rules.getTransitionRules()` has a `getOffsetBefore()` or `getOffsetAfter()` that is no multiple of `g` and `rules.getTransitions()` is not empty; else, walking `rules.getTransitions()` from its last element to its first: `Long.MAX_VALUE` if the last element's `getOffsetAfter()` is no multiple; the instant in microseconds of the first element met whose `getOffsetBefore()` is no multiple; `Long.MIN_VALUE` if none is met. With no stored change: `Long.MIN_VALUE` if `rules.getOffset(Instant.EPOCH)` is a multiple, else `Long.MAX_VALUE`. Prints `whole minutes from: <the greatest value under Long.MAX_VALUE over sets()> (<id>)` | an instant from which `second` would be read with no offset, one change early |
| 10 | `changesAt` and `changeOffsets` give the changes of 1900 to 2100 as a walk of `nextTransition` does, and `memo` builds once | (i) each set, `from = -2208988800000000`, `until = 4102444800000000`. (ii) the entry of `standardOnly()`, the same range; and each set's `changeOffsets(Long.MIN_VALUE, Long.MIN_VALUE + 1)`. (iii) a new entry `build(ZoneId.of("Europe/Podgorica").getRules())`: 8 threads, started by one latch, each call `memo` with one builder that adds 1 to an `AtomicInteger` and returns `new long[] {7}`. (iv) another new entry of the same rules: `memo` twice, with a builder that counts and returns `new long[0]` | (i): `changesAt` holds, element for element, `T * 1000000` of a walk of `nextTransition` from `from - 1` to `until - 1`; `changeOffsets` is one longer and holds `javaOffset(from - 1)`, then each walked transition's `getOffsetAfter().getTotalSeconds()`. (ii): `changesAt` is empty and `changeOffsets` is `{7200}`; `changeOffsets(Long.MIN_VALUE, Long.MIN_VALUE + 1)` has one element, `javaOffset(Long.MIN_VALUE)`, and throws nothing. (iii): the 8 results are one array object and the counter reads 1. (iv): both results are one empty array object and the counter reads 1 | a table packed from changes that the steps do not hold; an overflow at the least long |
| 11 | made-up rules whose two windows touch are not served | `touching()` | `served()` false, and the failure starts with `local windows touch` | a zone for which one local time is in two windows |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaChronoZoneLocalSuite *VarkaChronoZoneChangesSuite'
        # Passed: Total 22, Failed 0, Errors 0, Passed 22
    git diff --exit-code -- sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json sql/varka/operator_census.json sql/varka/types.tsv
    dev/varka_precommit.sh                                                                     # exits 0
    python3 dev/varka_push_check.py                                                            # exits 0, a size near 800

Then JaCoCo over the class as in part a.

**Planted faults.**

| fault | fails |
| :--- | :--- |
| `Math.min` for `Math.max` in `hiOf` | test 2 |
| `KEPT` writes `loOf(r)` for a gap | test 3, inside a gap with the offset after kept |
| `GAP_END` writes `loOf(r)` for an overlap | test 4, inside an overlap |
| C6 removed | test 11: the failure's text is another, or the zone is served |
| the clamp removed from `localChanges` | test 5 |
| `changeOffsets` starts with the offset after the first change | test 10 |

**Done when** the commands print the above and the faults fail as named.

**Not here:** no reading of OpenJDK's `java.time` sources (section 3); no cache, no `VarkaZoneData`,
no call of `VarkaZoneTable` (row 686's), no IR.

### 5.3 Part c: the cache, and the measurement

Branch `varka-678c-zone-cache`.

**B0.**

    grep -c 'public long\[\] memo(' $V/VarkaChronoZoneChanges.java                             # 1
    ls $V/VarkaChronoZones.java $T/VarkaChronoZonesSuite.java $B/VarkaChronoZoneBenchmark.java 2>/dev/null | wc -l   # 0
    grep -c 'protected static void runCases(VarkaBenchmark benchmark)' $B/VarkaBenchmarkBase.java   # 1
    grep -c 'protected final void note(String line)' $B/VarkaBenchmarkBase.java                # 1
    git grep -c 'public static long allocatedBytes()' -- $V/VarkaAllocationSampler.java       # one line, ending :1
    git branch --list 'varka-678c-zone-cache' | wc -l                                          # 0

**Steps.**

1. `$V/VarkaChronoZones.java`, new, as 4.9 has it. Its imports: `java.time.zone.ZoneRules` and
   `java.util.concurrent.ConcurrentHashMap`.
2. `$T/VarkaChronoZonesSuite.java`, new: the seven tests below.
3. `$B/VarkaChronoZoneBenchmark.java`, new: `extends VarkaBenchmarkBase`, as section 7 has
   it, each case through `runCases`, so that a dry run checks every case and times none.
4. The lead dispatches the benchmark on the pull request (section 7) and commits the results
   file and its provenance file to it. The builder does not time it on the laptop.
5. Row 678's status in `m9/PLAN.md`, as 5.5 has it for part c. Section 12 of this plan: the
   benchmark's numbers and each prediction of 7.1 scored.

**Tests.** `VarkaChronoZonesSuite`, JUnit 5, Java, catalyst, `@VarkaSuite(ownJvm = true)`: it
clears a static cache, so it runs in a JVM of its own. JUnit fixes no order of tests, so
tests 1, 2, 3 and 4 each start with `VarkaChronoZones.clearForTesting()`, and no test reads
what another left. `F` is the count of sets of `sets()` whose `rules.isFixedOffset()` is
false.

| # | test | inputs | expected | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | ids with the same rules share one entry | after the clear: `sets()`, every id of each, `e(id) = VarkaChronoZones.of(ZoneId.of(id).getRules())` | for a set that is not fixed, `e(id)` is the same object for every id of the set, and no two such sets share an object; for a fixed set, every `e(id)` is `fixed()` (each call may give a new object); then `cached()` is `F` | an entry for each id: 604 builds for 349 sets of rules |
| 2 | a fixed offset is built and not kept | after the clear: `ZoneOffset.ofTotalSeconds(s).getRules()` for `s` from 0 to 999 | each entry is `fixed()` with `offsetSecondsAt(0) == s`; `cached()` is 0 after them | a cache that a query with many offsets grows without bound |
| 3 | sixteen threads asking for every zone get one entry a set of rules | after the clear: 16 threads, started by one `CountDownLatch`, each asking `of(ZoneId.of(id).getRules())` for every id of `ZoneId.getAvailableZoneIds()` in an order shuffled by `new Random(seed() + thread)`, each keeping its results in a map by id | for every id whose rules are not fixed, the 16 threads hold the same object; `cached()` is `F`; no thread threw | two entries of one zone, each with a table of its own |
| 4 | an entry that failed its check is kept and not built again | after the clear: `rules = outOfYear()`, one object, passed to `of` twice | the two results are the same object, `served()` is false, `cached()` is 1 | a zone checked again on every compile |
| 5 | an entry does not serialize | `VarkaChronoZoneChanges.class`, `VarkaChronoZones.class` | neither is assignable to `java.io.Serializable` | an entry that a task's closure carries from the driver |
| 6 | the size of every entry, printed | `sets()`, each built by `VarkaChronoZoneChanges.build` (the cache is not read) | `bytes() == 12L * size() + 4` for each; prints `sizes: <sets> entries, <changes> changes, <bytes> bytes; the largest <id>, <n> changes, <bytes> bytes; America/Los_Angeles <n> changes, <bytes> bytes` | - (it is the record of the row's size) |
| 7 | the methods a batch calls allocate nothing | the entry of `America/Los_Angeles`, two `long[33]`; one round is `changes(1735689600000000, 1767139200000000)`, `toLocalArgs` of that range with 8 steps, `localChanges` of that range, and `toInstantArgs(Rule.KEPT, ...)` of it with 8 steps; and the same four over the year 2825 (both ends plus `2 * CYCLE_MICROS`). The test is skipped by `assumeTrue(VarkaAllocationSampler.supported())`. 20000 rounds to warm; then `before = VarkaAllocationSampler.allocatedBytes()`, 1000 rounds, `after` | `after - before <= 4096`, the sampler's allowance for one call (`VarkaAllocationSampler.suspect`), over all 1000 rounds | a fill that boxes or allocates for each batch, which is right and slow |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaChronoZonesSuite'
        # Passed: Total 7, Failed 0, Errors 0, Passed 7; and one line that starts "sizes: "
    flock "$VARKA_HEAVY_LOCK" build/sbt 'project catalyst' 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' 'Test/runMain org.apache.spark.sql.VarkaChronoZoneBenchmark'
        # one line "checked: every case equals java.time", then the three kinds of note lines of section 7, then [success]
    dev/varka_precommit.sh                                                                     # exits 0
    python3 dev/varka_push_check.py                                                            # exits 0, a size near 480

**Planted faults.**

| fault | fails |
| :--- | :--- |
| `of` returns `VarkaChronoZoneChanges.build(rules)` and reads no map | tests 1, 3 and 4 |
| `of` keeps a fixed zone | test 2 |
| `VarkaChronoZoneChanges implements java.io.Serializable` | test 5 |
| `toLocalArgs` starts with `leak = new long[1];`, `leak` a new `static volatile long[]` field of the class, so that the array escapes and the JIT cannot remove it | test 7 |

**Done when** the commands print the above, the faults fail as named, and the results file of
the EPYC 7763 is committed with section 12's scores.

**Not here:** no reading of OpenJDK's `java.time` sources (section 3); no `VarkaZoneData`; no timing
on the laptop; no change to parts a and b's code beyond what a failing test of this part forces,
which is a stop.

### 5.4 Part d: the production zone data

Branch `varka-678d-zone-data`. It starts when row 682's part c has merged.

**B0.**

    grep -c 'void toLocalArgs(long least, long greatest, int steps, long\[\] args, int first);' $V/VarkaZoneData.java   # 1
    grep -n 'interface Provider' -A3 $V/VarkaZoneData.java        # NONE = id -> null, and "@Nullable VarkaZoneData of(String zoneId);": note the import of Nullable
    grep -c 'boolean toLocalTable' $V/VarkaZoneData.java          # 0: row 686's part a needs this part, so it cannot have merged
    grep -c 'PROVIDER = VarkaJavaTimeZoneData::new' $T/VarkaJavaTimeZoneData.java                # 1
    git grep -l 'implements VarkaZoneData' -- sql/catalyst/src/main sql/core/src/main | wc -l    # 0
    grep -c 'public static VarkaChronoZoneChanges of(ZoneRules rules)' $V/VarkaChronoZones.java  # 1 (part c merged)
    grep -c 'sealed interface Zoned' $V/VarkaVectorIR.java        # 1; note the records it permits
    grep -c 'record ColumnRef(int ordinal, LaneType lane) implements VarkaVectorIR' $V/VarkaVectorIR.java   # 1
    grep -c 'record ToLocal(VarkaVectorIR instant, int zone, int hash)' $V/VarkaVectorIR.java   # 1; its public constructor is (instant, zone)
    git branch --list 'varka-678d-zone-data' | wc -l                                             # 0

If `Zoned` permits more than `ToLocal` (row 690 merged first), `toInstantArgs` gets an arm for
each record as 4.10 says, and test 6 keeps its `ToLocal` case. If `toLocalTable` is there,
stop: the order of the rows is not the one this plan was written for.

**Steps.**

1. `$V/VarkaChronoZone.java`, new, as 4.10 has it, with the same `Nullable` import that
   `VarkaZoneData` has. It has no `toLocalTable`: row 686's part a adds the method of 4.8 with
   the interface's.
2. `$T/VarkaChronoZoneSuite.java`, new: the eight tests below.
3. Row 678's status in `m9/PLAN.md`, as 5.5 has it for part d. Section 12 of this plan: the
   outcome. `sql/varka/skills/calendar-algorithms.md`: a new `##` section, "A zone
   as sorted arrays: the cycle, and the year java.time reads", with facts 4 to 7 of section 3
   and what test 1 printed; then `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
4. The register of upstream behaviours (row 681). In row 681's line of `m9/PLAN.md`, the
   words "the JDK's time-zone data with row 657" become "the JDK's time-zone data with row
   657, pinned by `VarkaChronoZoneChangesSuite`'s first test (row 678)". If row 681 has
   merged its register (its row reads Done and names the file), add the entry there too: the
   behaviour "the layout of `VarkaChronoZoneChanges` holds every zone of the JDK's time-zone
   data", the version test 1 printed, no upstream issue, the code `VarkaChronoZoneChanges
   .build`, the test `VarkaChronoZoneChangesSuite`, "every set of rules of this JVM builds
   and passes its check". If it has not merged, the edit of the row's line is the whole step.

**Tests.** `VarkaChronoZoneSuite`, JUnit 5, Java, catalyst, `@VarkaSuite`.

| # | test | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | every zone id of this JVM, each short id and each of Spark's spellings is served | `ZoneId.getAvailableZoneIds()`; the keys of `ZoneId.SHORT_IDS`; `UTC`, `Z`, `GMT`, `UT`, `UTC+1`, `GMT-08:00`, `UT+05:30`, `+1:00`, `-9:30`, `+01:5`, `+05:30`, `-18:00`, `+18:00`, `+00:00:01` | `PROVIDER.of(id)` is not null; its `id()` is `DateTimeUtils.getZoneId(id).getId()`; its `fixed()` is `DateTimeUtils.getZoneId(id).getRules().isFixedOffset()` | a session zone that Spark accepts and Varka declines, so that its entries read as fused in the census and decline in that session |
| 2 | two spellings of one zone share an entry | `PST` and `America/Los_Angeles`; `+1:00` and `+01:00` | `VarkaChronoZone.of("PST")` has the id `America/Los_Angeles` and the same `data()` object as `VarkaChronoZone.of("America/Los_Angeles")`; `+1:00` has the id `+01:00` and one offset, 3600 | an id kept as it was typed, which would split one zone over two zone indexes |
| 3 | an unknown id is not served and throws nothing | `Mars/Phobos`, the empty string, `+25:00` | null for each | a compile that fails where it should decline |
| 4 | `changes` and `toLocalArgs` are the java.time reference's, long for long | every id of `ZoneId.getAvailableZoneIds()`, sorted, with `p = VarkaChronoZone.of(id)` (the record, so that `p.data()` is at hand; test 1 holds that `PROVIDER.of` is the same call), `ref = VarkaJavaTimeZoneData.PROVIDER.of(id)` and `new SplittableRandom(seed() + index of the id)`; 50 pairs with each end uniform from -5364662400000000 to 16725225600000000; and, where `p.data().size() > 0`, for each of 5 random changes and each `k` of 0, 1, 2 and 8 the range of `k` changes at `r` of `p.data()` (section 5's words) | `p.changes(least, greatest) == ref.changes(least, greatest)`; where that is at most 8 and `least <= greatest`, `p.toLocalArgs(least, greatest, 8, a, 0)` and `ref.toLocalArgs(least, greatest, 8, b, 0)` leave two `new long[17]` equal | production data that differs from what rows 682 to 690 tested their kernels with |
| 5 | `localChanges` is at most the reference's | the same ids and the same 50 pairs, kept where `least <= greatest`, read as local ranges | `p.localChanges(least, greatest) <= ref.localChanges(least, greatest)`. The exact count is part b's test 7 | a count over the reference's, which would mean the reference's kernels were tested with too few steps, or the production's with a wrong window |
| 6 | `toInstantArgs` refuses a `ToLocal` node | `node = new VarkaVectorIR.ToLocal(new VarkaVectorIR.ColumnRef(0, VarkaVectorIR.LaneType.LONG), 0)`; `VarkaChronoZone.of("Europe/Podgorica").toInstantArgs(node, 0, 1, 2, new long[9], 0)` | `IllegalArgumentException` | a `ToLocal` program filled with local thresholds |
| 7 | the cap of `changes` is the reference's | `America/Los_Angeles`, `(Long.MIN_VALUE, Long.MAX_VALUE)` | `PROVIDER.of(id).changes(...)` and `VarkaJavaTimeZoneData.PROVIDER.of(id).changes(...)` are both 4096, which is `VarkaChronoZoneChanges.MANY` | two caps that drift apart, so that one side declines a batch the other fills |
| 8 | a zone's data does not serialize | `PROVIDER.of("Europe/Podgorica")` to an `ObjectOutputStream` | `java.io.NotSerializableException` | zone data built on the driver and read on an executor |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaChronoZoneSuite *VarkaEmitterZoneSuite'
        # VarkaChronoZoneSuite reports 8 tests passed; VarkaEmitterZoneSuite as before this part
    git grep -l 'implements VarkaZoneData' -- sql/catalyst/src/main                              # $V/VarkaChronoZone.java
    git grep -n 'VarkaZoneData.Provider PROVIDER' -- sql/catalyst/src/main                       # one line, in VarkaChronoZone.java
    git diff --exit-code -- sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json sql/varka/operator_census.json sql/varka/types.tsv
    dev/varka_precommit.sh                                                                     # exits 0
    python3 dev/varka_push_check.py                                                            # exits 0, a size near 400

**Planted faults.**

| fault | fails |
| :--- | :--- |
| `ZoneId.of(zoneId)` in place of `DateTimeUtils.getZoneId(zoneId)` | test 1, at `PST` and at `+1:00` |
| the record keeps `zoneId` as its id | test 2 |
| `of` lets the exception out | test 3 |
| `changes` passes `greatest, least` | test 4 |

**Done when** the commands print the above, the faults fail as named, and row 678 reads Done.

**Not here:** no reading of OpenJDK's `java.time` sources (section 3); no edit of
`VarkaZoneCompiler`, of `VarkaEvaluatorBase.zoneData()` or of any file under `sql/core`: row 657
wires `VarkaChronoZone.PROVIDER` in (`VARKA-657-PARTS.md` 4.2 and 5.2). No logging. No arm for a
record that `Zoned` does not permit yet.

### 5.5 The row's status in `m9/PLAN.md`, part by part

The plan's own pull request leaves row 678 opening with ``**Planned** (`VARKA-678.md`, 27
November 2026: four parts, `` (11.1, edit 1). Each part replaces the text from `**Planned**`
(or, later, `**Started**`) up to and including the words `27 November 2026: four parts, ` by
its own text below, and leaves the rest of the line as it is. `#<a>` to `#<d>` are the
parts' pull request numbers.

| part | the text it writes |
| :--- | :--- |
| a | ``**Started** (part a is #<a>; parts b, c and d are left; `VARKA-678.md`, 27 November 2026: four parts, `` |
| a2, only if the cut line was taken | ``**Started** (part a is #<a> and #<a2>, split at its cut line; parts b, c and d are left; `VARKA-678.md`, 27 November 2026: four parts, `` |
| b | ``**Started** (parts a and b are #<a> and #<b>; parts c and d are left; `VARKA-678.md`, 27 November 2026: four parts, `` |
| c | ``**Started** (parts a, b and c are #<a>, #<b> and #<c>; part d is left; `VARKA-678.md`, 27 November 2026: four parts, `` |
| d | ``**Done** (#<a>, #<b>, #<c> and #<d>; the bucket table's own test is test 7 of row 686's part a; `VARKA-678.md`, 27 November 2026: four parts, `` |

If the cut line was taken, rows b, c and d write `#<a> and #<a2>` where the table has `#<a>`.

## 6. Tests, proofs, fuzzing and coverage

### 6.1 The suites

All four are JUnit 5 classes in Java under `@VarkaSuite`, in catalyst's test tree, beside the
code. Their oracle is `java.time` in the JVM that runs them, so on CI they prove the layout
for CI's JDK and its time-zone data, and test 1 of part a prints which.

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaChronoZoneChangesSuite` (part a, 11 tests) | every set of rules of the JVM builds and passes its check; the offset at every change held, in the far past, at both ends of a long, cycles into the future, at random instants and around the changes of 2026 to 2040; `changes` and `toLocalArgs`; made-up rules that must not be served | a zone whose data differs from `java.time` on the instant line |
| `VarkaChronoZoneLocalSuite` (part b, 11 tests) | each window against `java.time`; the three rules at the edges of every window, far away, at random and at the clamp; `localChanges`, `toInstantArgs`; the whole-minute instants; the table's inputs; `memo` | a local time resolved by another rule than Spark's row path takes |
| `VarkaChronoZonesSuite` (part c, 7 tests) | one entry for each set of rules, under 16 threads too; fixed zones not kept; a failed entry kept; nothing serializes; the sizes; no allocation on the methods a batch calls | two entries of one zone; a cache that grows with the offsets a query names |
| `VarkaChronoZoneSuite` (part d, 8 tests) | every id, short id and spelling served as Spark resolves it; the production data against the reference, long for long | a zone Spark accepts and Varka declines; production data that differs from what the kernels were tested with |

**What the row leaves to later suites.** The kernels over this data are swept by
`VarkaZoneSweepSuite` (row 684 for `ToLocal`, row 690 for the three rules), and
`VarkaZoneTableSuite`'s test 7 (row 686, part a) compares the production table with the
reference's "for every zone id, long for long". Row 684's planted fault "the driver's rules
read" holds that a program resolves its zone on the executor.

### 6.2 Proofs, fuzzing, mutation

- **Proof files:** none. The house rule is "**A new bounded lowering comes with its proof
  file**"; this row has no lowering and no bound that a solver can state. `VARKA-657.md`
  section 5 says it of the whole family: "The equality with `java.time` is not an SMT claim:
  it is a property of the JVM's data, held by the sweep in every Build". The one arithmetic
  claim, that 400 years are a whole number of weeks, is test 11 of part a.
- **Fuzzing:** the row adds no production to a fuzzer's grammar, since it adds no IR record
  and no expression. Its own random tests draw by seed (`-Dvarka.zone.seed=<n>`, default
  678), and a failure's message names the seed, the zone and the instant. Row 684 makes the
  session zone a drawn setting of the Spark fuzzer and draws zone nodes in the IR fuzzer;
  both then run through this row's data.
- **Registry suites:** none applies. The row adds no IR record, so the registries of
  `m8/VARKA-489.md`'s recipe do not meet it.
- **Mutation:** the family's mutation run (`VARKA-657.md` section 5) is over the programs'
  `fill`. Here the planted faults of each part stand in for it: 5 in part a, 6 in part b, 4 in
  part c, 4 in part d, each with the test it fails.

### 6.3 Coverage

JaCoCo over the three classes, in the pull request that adds each, with every method entered
and each missed branch named. `S` is a new, empty directory in the builder's scratch area;
`<suites>` is the part's suites, `<Class>` its class.

    J=$HOME/.m2/repository/org/jacoco; M=$HOME/.m2/repository/org/ow2/asm
    A=$J/org.jacoco.agent/0.8.15/org.jacoco.agent-0.8.15-runtime.jar
    flock "$VARKA_HEAVY_LOCK" build/sbt "set LocalProject(\"catalyst\") / Test / javaOptions += \"-javaagent:$A=destfile=$S/jacoco.exec,append=true,includes=org.apache.spark.sql.catalyst.expressions.codegen.varka.*\"" 'catalyst/testOnly <suites>'
    cp=$J/org.jacoco.core/0.8.15/org.jacoco.core-0.8.15.jar:sql/catalyst/target/scala-2.13/classes:common/utils/target/scala-2.13/classes
    cp=$cp:$M/asm/9.10.1/asm-9.10.1.jar:$M/asm-commons/9.10.1/asm-commons-9.10.1.jar:$M/asm-tree/9.10.1/asm-tree-9.10.1.jar
    java -cp "$cp" dev/varka_gen_coverage/VarkaEmitterCoverage.java "$S/jacoco.exec" sql/catalyst/target/scala-2.13/classes sql/catalyst/src/main/java sql/varka/coverage/reach "$S/emitter.md"
    grep '^| <Class>.java ' "$S/emitter.md"

`$S/emitter.md` is scratch; the committed `sql/varka/coverage/emitter.md` stays as it is. The
coverage table `sql/varka/coverage.json` gets no row: the row fuses no expression.

### 6.4 What runs where

- **Locally, before a push:** the part's suites, the dry run of the benchmark (part c), the
  planted faults, JaCoCo, `dev/varka_precommit.sh`, `python3 dev/varka_push_check.py`, and the
  author's own `/code-review high` of the diff.
- **Only in CI:** the Build's `Varka suites: catalyst` jobs, which run the suites on the
  runner's JDK and data; the Maven job, which compiles the new Java without sbt's classpath;
  the option matrix, where it is dispatched. No test of this row reads an emit option or
  emits a kernel, so no configuration of the matrix moves one, and no test here takes
  `PinsDefaults` or a line in `skips.tsv`. The width audit and the memory sanitizer do not
  meet the row: it maps no buffer and emits no vector code.
- **The test time is said in each pull request,** against `sql/varka/ci/step-budget.tsv`, as
  the house rule asks (7.1 has the predictions).

## 7. Measurement

The row has no kernel, so it has no baseline class and no A/B arm of an emit option. What it
measures is what its done-when names, "the time to build an entry and its size", and the cost
that a zoned kernel pays once for each batch, the fill.

**The class.** `VarkaChronoZoneBenchmark extends VarkaBenchmarkBase`, in `$B`, Java. It is in
another package than the code, so it calls the public methods only. Its neighbour to copy is
`$B/VarkaCompileBenchmark.java`: `section(title, body)`, `benchmark(name,
valuesPerIteration).minIterations(5).warmup(Duration.ofSeconds(2))
.minTime(Duration.ofSeconds(2)).build()`, `addCase(name, iteration -> ...)`, and here
`runCases(benchmark)` in place of that file's `benchmark.run()`. As there, the class has an
instance field `private long sink` and a `private volatile long blackhole`: each case's body
adds its result (a count, or an argument it wrote) to `sink`, and the last statement of
`runBenchmarkSuite` is `blackhole = sink`, so that the JIT cannot drop a case's work.

| section | benchmark (the rate's unit) | cases, the first the control |
| :--- | :--- | :--- |
| building a zone's entry | one entry (entries) | `java.time: getTransitions and getTransitionRules, read and dropped`; `America/Los_Angeles`; `Europe/Belgrade`; `Africa/Casablanca`; `Asia/Kathmandu` |
| | every set of rules of this JVM (entries; one iteration builds them all) | `build, with its check` |
| a batch's arguments | one batch (calls; 1000 calls an iteration) | `java.time: a walk of nextTransition over a year, as the reference does`; `changes over a year`; `changes and toLocalArgs, 2 steps, a year`; `changes and toLocalArgs, 8 steps, four years`; `changes and toLocalArgs, 2 steps, the year 2825`; `localChanges and toInstantArgs KEPT, 2 steps, a year`; `the cache: of(rules)` |

The zone of the second section is `America/Los_Angeles`. A year is the instants from
1735689600000000 to 1767225599999999 (2025); four years end at 1861919999999999 (the end of
2028); the year 2825 is 2025's two ends plus `2 * CYCLE_MICROS`, past every entry's block;
the local year is
1735689600000000 to 1767139200000000. The sets of rules are grouped as the fixtures group
them, by the benchmark's own few lines (it cannot read the test package's helper).

**The order of `runBenchmarkSuite`,** the same in a dry run and in a timed run, except for
the last step:

1. *The checks*, one for each case, each a `require(...)` of the base class:
   - each `build` case: the entry is `served()`, and `offsetSecondsAt(x)` equals
     `rules.getOffset(Instant.ofEpochSecond(Math.floorDiv(x, 1_000_000L))).getTotalSeconds()`
     at 1000 instants uniform over 2025 (`new SplittableRandom(678)`); for `every set of
     rules`, every entry is served;
   - the two controls: none, they are `java.time`;
   - `changes over a year`: the count equals the length of the control's walk;
   - each `toLocalArgs` case: with the filled arguments, `x + base + sum (x >= at[i] ?
     delta[i] : 0)` equals `x + 1000000 *` the offset `java.time` gives, at the range's two
     ends;
   - the `KEPT` case: at the range's two ends, with `kept` the base, the scalar meaning of
     4.5 equals `ZonedDateTime.ofLocal` of that local time and offset, in microseconds;
   - `the cache`: two calls return the same object.
   Then it prints `checked: every case equals java.time`.
2. *The notes*, by `note(...)`, so that a timed run writes them into the results file:
   `tzdata <version>, <sets> sets of rules for <ids> zone ids`; `no vector code: one file, no
   128-bit companion`; `sizes: ...` in the words of `VarkaChronoZonesSuite`'s test 6; and,
   for each case of the second section but the controls, `bytes a call: <case> <n>`. `<n>` is
   measured so: 20000 calls of the case to warm; then eight times `before =
   VarkaAllocationSampler.allocatedBytes()`, 1000 calls, `after`; `<n>` is the least `(after -
   before) / 1000` of the eight. Where `VarkaAllocationSampler.supported()` is false the note
   reads `bytes a call: not supported`.
3. *The tables*: each benchmark built, its cases added, and `runCases(benchmark)`, which
   times nothing in a dry run.

**Where it runs.** The lead dispatches it on part c's pull request, on vecbricks/varka:

    gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<N> -f class=VarkaChronoZoneBenchmark -f jdk=25 -f scala=2.13 -f num-splits=1 -f create-commit=false -f expected-cpu="AMD EPYC 7763"

The artifact is committed to the pull request as
`sql/catalyst/benchmarks/VarkaChronoZoneBenchmark-jdk25-runner-epyc7763-results.txt` with its
`-provenance.txt`, in the form of `VarkaDecimalStoreBenchmark`'s. A run on another CPU is
dispatched again. There is one file and no 128-bit companion: the code under test has no
vector operation, so the vector width cannot move it; a note of the file says so.
The laptop adds nothing here, and the builder times nothing on it.

### 7.1 Predictions, registered before the run

Each is scored in section 12 from the file named. None is a gate.

1. Every set of rules of the runner's JDK passes the check: 0 zones not served
   (`VarkaChronoZoneChangesSuite` test 1's line in the Build's log). The probes found no
   mismatch by another walk (fact 11).
2. No zone of the runner's JDK has more than 2 rule changes a year, so no block is over 800
   changes (the same line's largest entry, and the benchmark's `sizes` note).
3. `America/Los_Angeles`'s entry is under 16,000 bytes, the largest entry under 32,000, and
   all entries together under 4,000,000 (the `sizes` note). The reasoning: 9600 bytes for a
   block of 800, and 12 for each stored change; `facts.txt` has at most 400 changes in a zone
   before 2100.
4. Building one entry with its check takes under 20 ms on the runner, and all sets of rules
   under 5 s (the first section). The reasoning: a few thousand lookups of C4 and C7 an
   entry, most of them past 2100, where `java.time` works a rule out on each call (fact 5);
   no committed number prices that lookup, so this is a bound and not an estimate.
5. `changes and toLocalArgs, 2 steps, a year` costs under 200 ns a call, which is under a
   twentieth of a nanosecond a row for a batch of 4096; and less than the `java.time` walk of
   the control (the second section).
6. The case in the year 2825 costs at most twice the case in 2025: the reduction by cycles is
   a division and a multiply.
7. Every `bytes a call` note of the second section reads 0, or within the sampler's allowance
   that `VarkaLongRangeAllocationSuite` (row 683) uses, if that row has merged.
8. Test time. The calibration table (`python3 dev/varka_calibration.py`) has two near rows:
   VARKA-281, a calendar sweep under a parallel stream, at 0.08 of its estimate; and
   VARKA-328, a JUnit class with no session, at about 0.03. Both estimates priced one core and
   then doubled. So these are stated for a runner's four cores, with no doubling:
   `VarkaChronoZoneChangesSuite` adds under 30 s to a Build's catalyst bin, and
   `VarkaChronoZoneLocalSuite` under 60 s; `VarkaChronoZonesSuite` under 20 s with its own
   JVM's start; `VarkaChronoZoneSuite` under 20 s. The largest term is test 6 of part a,
   14,401 lookups for each change of 2026 to 2040. Scored from the suites' times in the
   Build's log. If a suite passes its bound, the one-second sweep of test 6 moves to the
   nightly (`dev/varka_nightly.sh`) and the Build keeps the edges.
9. The four parts count about 880, 800, 480 and 400 lines, none over 950; part a may need
   its cut line (5.1), which it takes above 900.

## 8. Who builds it

Every part is built at full strength, not by a cheaper model from a generated plan. The
milestone plan gives the cheaper models the fan-out rows, each "one task plan from a
template" (`m9/PLAN.md` 1.1, G5; 2.7); this row is foundation, in wave 0, and a wrong
threshold in it is a wrong answer in every zoned kernel that no fallback catches. Parts a and
b hold the layout and the check, and want one author. Parts c and d are small and exact; they
are still not template tasks: they have no entry, no type and no one-command oracle of row
658. So the row does not serve G5, and its pull requests do not enter the ledger's share.

The lead (the coordinating agent) dispatches the benchmark, commits its results and scores
section 7.1; the owner's word starts the row (decision 18) and decides the open questions of
section 11.

## 9. Risks

1. **A zone of some JDK fails the check.** Then that zone is not served on that JVM: its
   kernels decline by name and the row path answers, which is right and slow. Test 1 of part
   a fails in CI when the runner's JDK brings such data, so it is seen at the JDK's update.
   Row 681's register takes the entry (part d, step 4).
2. **The reading of `java.time`'s private code is wrong somewhere.** The design does not rest
   on it: C4 and C7 compare with the public methods at every point where either side can
   step, and the suites compare again at other instants. The reading only says why the points
   are enough.
3. **The cycle.** A rule that did not repeat after 400 years would break the far-future
   answers. Fact 6 says it cannot, test 4 of part a and test 5 of part b check images up to
   the last cycle of a long, and a planted cycle one day short fails two tests.
4. **Row 682's interface moves before part d.** Part d's B0 greps each name; the core class
   does not depend on it, so only the thin record changes.
5. **`localChanges` is tighter than the reference's.** The reference of `VARKA-682.md` counts
   the changes within 18 hours of the range; this row counts the windows the range touches.
   Part d's test 5 asserts "at most"; row 690 must not assert equality (11.1, edit 2).
6. **A failed zone is silent.** A zone that is not served declines every zoned entry under
   it, and nothing logs why. The compile's decline reason is row 657's; test 1 of part a is
   what tells in CI. Open question 4.
7. **The suites' time** on a runner (prediction 8): the dense test moves to the nightly if it
   fails its bound.
8. **The library diverges.** Once `vechrono` holds its own zone database, two copies exist.
   vfdlibm's rule is the control (`m9/PLAN.md` section 5): a change to one is paired with the
   pull request to the other, and nothing here waits on a release.
9. **The plan ages.** Its code facts are master's of 27 November 2026. Each sheet starts with
   greps of its premises, and a premise that fails stops the part.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and the root `AGENTS.md`, the ones a builder of this row is most
likely to break:

- "**No allocation, streams, lambdas or boxing on a path that runs per batch or per row**".
  `changes`, `toLocalArgs`, `localChanges` and `toInstantArgs` run for each batch: plain loops
  over the two arrays. `build`, `changesAt`, `changeOffsets` and the cache run once.
- "**The JDK, not a library**, for anything the JDK covers: no new Guava or Commons use in
  Varka's Java". The cache is a `ConcurrentHashMap`.
- "**A record for every data carrier.**" `Check` and `VarkaChronoZone` are records.
  `VarkaChronoZoneChanges` is a class: it holds arrays that must not leak and one field that
  is written late.
- "**Closed sets are sealed, and matched exhaustively.**" The switch over `Zoned` in
  `VarkaChronoZone.toInstantArgs` has no `default`.
- "**A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`**
  (VARKA-312), each `@Test` carrying its sentence as a `@DisplayName`".
- "**The Varka code carries no `TODO` or `FIXME` comments, and should not start.**" and "**A
  comment describes the code as it is.**"
- "Any performance claim in code comments, plans or docs traces to a committed benchmark
  result file." No comment of this row says how fast a fill is; the results file does.
- "**Review your own diff before opening the pull request**" and "**A pull request stays
  under 1000 changed lines**".
- "**Never kill by name pattern**", "**Never `git stash`.**", "**Write only inside your own
  worktree and scratch area.**"
- This row's own rule, by the lead's decision pending the owner (open question 5): a builder
  does not open OpenJDK's `java.time` sources. What the build needs of `java.time` is in
  section 3, in this plan's words, and each fact there has its test through the public API.
- The root file: source lines within 100 characters, ASCII only in code and comments, the
  Apache licence header on every new file, and the pre-flight checks before the first edit.
  A commit's only attribution is one `Generated-by: <tool> (<model>)` line
  (`dev/varka_msg_check.py`).

**What not to do.** Do not ship or read a time-zone file: the JVM's `ZoneRules` is the only
source. Do not sort the changes a `ZoneRules` gives (4.1). Do not relax a check so that a
zone of the JDK passes: stop and report. Do not key anything by a zone id. Do not make an
entry `Serializable`, and do not hold one in a field of a plan node, an expression or a
compiled projection. Do not call `VarkaChronoZones` or `PROVIDER` from the driver's planning
code. Do not add an emit option, an IR record, a conf, a decline text or a log line. Do not
edit `sql/core`. Do not time anything on the shared laptop. Do not edit a generated file.

## 11. Seams with the plans written beside this one, open questions, and edits

**Row 679, `vechrono`.** Its plan is `VARKA-679.md` of `sql/varka/plans/m9/`; its section 4.2
describes the library's `ZoneData`, the same zone data as this row's. The order between the
two rows has no condition, by the lead's decision of 27 November 2026. Row 678 is built first,
in `sql/catalyst`: the library's repository does not exist. A builder of this row never
looks for the library, imports nothing of it, adds no dependency and links no pull request
of it. After part c has merged, row 679 ports this row's two merged files into the library,
`VarkaChronoZoneChanges` first and then `VarkaChronoZones`, and row 679 adds the test that
compares the library's arguments with this row's. `VarkaChronoZone`, its provider and
Spark's resolution of an id stay in Varka. Where `VARKA-679.md` words the algorithm, the
check or a method otherwise, this plan binds the Varka classes, and the port follows the
merged files.

**Rows 654 and 656** (the census with `TIMESTAMP_LTZ`, and the type on the long lane; their
plans are written beside this one). This row meets neither: it reads no type, no census file
and no Arrow vector. It assumes only that neither adds a class named `VarkaChronoZone*`.

**What this row hands to rows 657 and 682 to 690,** by name: `VarkaChronoZone.PROVIDER`
(`public static final VarkaZoneData.Provider`, in `$V/VarkaChronoZone.java`), which
`VarkaZoneCompiler`'s provider field and `VarkaEvaluatorBase.zoneData()` take (row 657);
`VarkaChronoZoneChanges.changesAt`, `changeOffsets` and `memo` for `toLocalTable` (row 686,
part a, 4.8 above); `VarkaChronoZoneChanges.Rule` and `toInstantArgs(Rule, long, long, int,
long[], int)` for the arms of `VarkaChronoZone.toInstantArgs` (row 690).

**Open questions for the owner**, each with the recommendation first; the plan is written on
the recommendation.

1. *Build in `sql/catalyst` now, before `vechrono` exists?* Recommended: yes. Row 657 waits
   for this row, the milestone plan says no row waits on the library, and the lead has
   decided the order so (above). The other choice, the library first, puts a new repository
   and its CI on the zone path.
2. *The whole cycle written when an entry is built, where the row's line says "as they are
   needed"?* Recommended: the whole cycle. It costs about 9600 bytes a zone with two rule
   changes a year, makes an entry immutable, and leaves no lock and no growth on the path a
   batch takes. The other choice writes a year when a batch first reaches it and needs a
   guarded, growing array.
3. *`localChanges` as the count of windows a range touches, tighter than the reference's
   18-hour rule?* Recommended: yes. A wider count sends a batch near a change to a larger
   form, or to the row path, for a step it never needs.
4. *Should a zone that fails its check log once, by Spark's structured logging?*
   Recommended: not in this row. The core must stay free of Spark, and row 657 owns the
   decline's reason; if the owner wants a log, row 657's `VarkaZoneCompiler` writes it with
   `VarkaChronoZones.of(rules).check().failure()`.

5. *May a builder read OpenJDK's `java.time` sources?* Recommended, and the plan is written
   so: no. Every behaviour the build sheets rest on is stated in section 3 and held by a
   named test through the public API, so the build needs no reading, and code written from
   the JDK's private methods would rest on what a JDK update may change without notice. The
   other choice lets a builder read them to debug a failing fact, at the cost of a build
   that may copy private structure.

### 11.1 Edits to other plans, made by this plan's own pull request

The pull request that adds this file to `sql/varka/plans/m9/` also makes these edits. Each is an
exact old text and its new text; the lead applies them. A block below is indented by seven spaces;
what follows those seven is the file's own text, its own indentation included. No part of the row
repeats them. (Row 681's line is edited by part d, step 4, since it names a test that must exist.)

1. `m9/PLAN.md`, row 678's line, four places: its status, then three of its text. Old:

       | 678 | The time-zone database for vector code. **Filed** (25 November 2026, on the owner's word of that day; the admission check:

   New:

       | 678 | The time-zone database for vector code. **Planned** (`VARKA-678.md`, 27 November 2026: four parts, the entry and the instant line, the local line, the cache with its measurement, the production zone data; filed 25 November 2026, on the owner's word of that day; the admission check:

   Old:

       the rule years written out as they are needed, and the bucket table, which answers `VarkaZoneData.toLocalTable` (`VARKA-686.md` 4.5);

   New:

       one cycle of 400 rule years written out when the entry is built, and the changes of 1900 to 2100 that row 686 packs into the bucket table, which answers `VarkaZoneData.toLocalTable` (`VARKA-686.md` 4.5);

   Old:

       the steps and the table read from it equal `java.time` over the sweep's instants on both time lines;

   New:

       the steps read from it equal `java.time` over the sweep's instants on both time lines, and the table packed from it equals the reference's (row 686's part a holds that test);

   Old:

       | the owner's word of 25 November 2026; `m10/SCOPE.md` item 89; row 679's first | medium |

   New:

       | the owner's word of 25 November 2026; `m10/SCOPE.md` item 89; row 682's part c before its last part; row 679 ports its merged files into the library | medium |

2. `VARKA-682.md`, part c, step 2. Old:

       `rules.isFixedOffset()`. `localChanges` counts the changes whose local instant, before or
          after the change, falls in the range widened by `MAX_OFFSET_MICROS` on both sides.

   New:

       `rules.isFixedOffset()`. `localChanges` counts the changes whose local instant, before or
          after the change, falls in the range widened by `MAX_OFFSET_MICROS` on both sides. That
          is an upper bound: the production data counts the windows the range touches, `hi >
          least` and `lo <= greatest` (`VARKA-678.md` 4.5), which is never more. A test compares
          the two counts with `<=`, never with equality.

3. `VARKA-686.md` 4.5. Old:

       - **Row 678's production data** must answer the same method: it gives `pack` its changes
         inside `[ORIGIN, LIMIT)` as sorted instants with their offsets, which row 678's line names
         ("its changes sorted on both time lines"). It may build the packed table once a zone in

   New:

       - **Row 678's production data** must answer the same method: it gives `pack` its changes
         inside `[ORIGIN, LIMIT)` as sorted instants with their offsets, which row 678's line names
         ("its changes sorted on both time lines"): `VarkaChronoZoneChanges.changesAt(ORIGIN,
         LIMIT)` and `changeOffsets(ORIGIN, LIMIT)`, with the packed table kept by
         `VarkaChronoZoneChanges.memo` (`VARKA-678.md` 4.8). It may build the packed table once a zone in

4. `VARKA-657-PARTS.md`, part A's B0. Old:

       | `grep -rn "VarkaZoneData.Provider" sql/catalyst/src/main/java` | row 678's provider: a public constant or method. `PROVIDER` below stands for it |

   New:

       | `grep -rn "VarkaZoneData.Provider" sql/catalyst/src/main/java` | row 678's provider, `VarkaChronoZone.PROVIDER` (`VARKA-678.md` 4.10). `PROVIDER` below stands for it |

5. `VARKA-684.md`, "What could not be checked". Old:

       - the production `VarkaZoneData.Provider`, called `PROD` below;

   New:

       - the production `VarkaZoneData.Provider`, `VarkaChronoZone.PROVIDER` (`VARKA-678.md` 4.10),
         called `PROD` below;

## 12. Outcome

*To be written when the row closes: the four pull requests, test 1's line on CI's JDK, the
sizes, the benchmark's numbers, and each prediction of 7.1 scored.*
