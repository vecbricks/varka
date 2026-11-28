# Varka Milestone 9 Plan: every timestamp, in every time zone

*Opened 25 November 2026, while milestone 8 runs. The owner set the theme the same day:
`TIMESTAMP_LTZ` at precision 6 to 9, `TIMESTAMP_NTZ` with nanoseconds, and the remaining features
of the ANSI intervals, `TIME`, and `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ` with `DATE`. The decisions
in section 1 are the owner's and fixed; this plan works inside them.*

This is a task plan, not a scope catalogue. Under the house rule (`sql/varka/AGENTS.md`), the
catalogue that sat in this folder (`SCOPE.md`, with its reading note and the plans of the rows it
holds) moved forward to `m10/` by `dev/varka_catalogue_move.sh 9 10` on 25 November 2026, with
its item numbers unchanged and every inbound reference rewritten: `m10/SCOPE.md` below is that
catalogue. By the owner's choice the move was a pull request of its own, since its mechanical
rewrite alone is over the size cap. The same pull request moved the plans of the six rows this
milestone takes from milestone 8 (section 3) into this folder, as the house rule asks of a plan
whose row changes milestone.

The plan holds the goals and what "done" means (section 1), the design each foundation row starts
from (section 2), the task table (section 3), the order (section 4), the risks (section 5) and
the predictions registered before the work (section 6). A row's own plan is written in the pull
request that opens the row.

## 1. The question, and what "done" means

Can Varka answer Spark's datetime surface - every timestamp kind Spark has, in every session time
zone, and the interval, `TIME` and `DATE` expressions milestone 8 leaves - bit for bit with
Spark, and much faster than Spark where a time zone is read? And can most of that work be done by
cheaper models from written task plans?

**The owner's decisions (25 November 2026).**

1. The theme above. Precision 6 is today's microsecond `TIMESTAMP` and `TIMESTAMP_NTZ`; precision
   7 to 9 is Spark's `TimestampLTZNanosType(p)` and `TimestampNTZNanosType(p)` (SPARK-56876),
   which store a value as a long of microseconds and a short of nanoseconds, so "6 to 9" is
   Spark's whole range.
2. Two phases: microseconds first (`TIMESTAMP_LTZ`, and the remaining features), nanoseconds
   second.
3. The work is built mostly by cheaper models: DeepSeek Flash, Kimi K2.7 Code and GigaChat Pro.
   They are not Claude models, so a task plan they take is self-contained: the files to edit in
   order, the shell commands to run, a one-command oracle with the output it must print, and the
   house rules it must keep, with the repository's `AGENTS.md` files as their only other input.
   The same review holds their pull requests to the same bar as any other.
4. Delivery by cheaper models is a goal of the milestone (G5), not only its method.
5. The milestone ends in a post (G6).
6. The rows milestone 8 leaves - 30, 95, 103, 351, 364 and 366 - are this milestone's (section
   3). Rows 30, 95, 364 and 366 are not datetime rows; they come as milestone 8's carry-over.
7. How precisions 7, 8 and 9 enter the census (one type per kind with a precision band, as
   `DECIMAL` has, or one type per precision) is designed, not assumed: row 660's design answers
   it.
   *27 November 2026:* the owner chose two types with a band, choice (b) of `VARKA-660.md`, and
   that the seven date functions that take a `DATE` count over the two types.
8. Varka's timestamps with a time zone outperform vanilla Spark significantly (G7).
9. "Significantly" is five times the row path's rate where a kernel reads the zone and twice
   elsewhere, with nothing slower (1.2, item 10).
10. The cheaper models implement on the laptop (2.7).
11. The rows that bring a new type into the census, 654 and 656, wait for milestone 8's close
    (section 4).
12. The pilot's first task goes to one of the three models, not to all three (2.7).
13. The batch forms of `java.time` get a library of their own, as vfdlibm is for the math: the
    first implementation of a form, and what Varka's tests check against (2.10).
14. The milestone takes five more items of the scope catalogue, each for a goal (2.11): the gate
    that no fused shape is slower than the row path (row 680), the register of upstream
    behaviours (row 681), a pull request's Build merged with master (row 590), a stock arm over
    the Arrow cache (in row 655) and `make_ym_interval` over plain int columns (in row 672). The
    rest of the catalogue moves to milestone 10.
15. At two 64-bit lanes on x86 a section that would run slower than the row path declines
    there, as G7 has it. The catalogue's lowering without masks for every long-lane kernel (its
    item 101) is not this milestone's; row 689 builds that form for the zone steps alone:
    `ToLocal`'s, and those of the three rules of a local time to an instant.
16. The library of decision 13 is `vechrono`, and its repository is `vecbricks/vechrono`
    (decided on 25 November 2026, after this plan merged, as decisions 17 and 18 were).
17. The zone conversions are IR nodes, as 2.2 has them, and what several fields share they share
    through the node. The work of row 657 is regular rows, 657 and 682 to 692, not lettered
    parts of one row (`VARKA-657.md` 1).
18. Milestone 9 has not started. While milestone 8 runs, only planning goes on: no row of this
    plan is built before the owner's word (section 4).

### 1.1 Goals

- **G1. `TIMESTAMP_LTZ` at microseconds (phase 1).** It is a census type. The entries that read
  no time zone fuse over it: choice, null tests, comparisons, `IN`, `greatest`, `least` and the
  hashes, as they do over `TIMESTAMP_NTZ`, and the conversions between a timestamp and a count
  since the epoch (`timestamp_seconds` and its kin, and the `unix_*` functions). Under the
  session time zone, the calendar and clock fields, `date_trunc`, `months_between`,
  `from_utc_timestamp`, `to_utc_timestamp`, `convert_timezone`, `make_timestamp` and its forms
  and the timestamp arithmetic fuse.
- **G2. Every time zone, proven.** A kernel's answer equals Spark's in every zone the session
  can name - every region in the time-zone data of the JDK that CI runs, Spark's short names
  and fixed offsets - at the gaps and overlaps of each change of a zone's offset too, checked by
  a sweep that runs in CI. A kernel answers there itself: instants in no order must not send
  every batch back to the row engine (2.2).
- **G3. The remaining datetime features (phase 1).** The temporal pairs milestone 8 leaves
  unfused - interval `div`, `extract` over a day-time interval, `time_bucket` (its year-month
  width is `m10/SCOPE.md` item 117), `width_bucket`, the interval and `TIME` casts, the `try_`
  functions over intervals (item 125), and a `DATE` mixed with a timestamp; the one feature with
  no census row, `d + dt` over a stored interval column (item 126); and `TIME` plus or minus an
  interval without its range guard (item 50), a fused form that loses its guard once SPARK-57853
  merges upstream. Items 117, 125 and 126 are tasks (rows 664 to 666); item 50 is taken only if
  SPARK-57853 merges before the close, and stays in the catalogue otherwise. The six rows
  carried over from milestone 8 (decision 6) are part of G3.
- **G4. Nanosecond `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ`, precision 7 to 9 (phase 2).** Admitted
  while Spark's `spark.sql.timestampNanosTypes.enabled` is on, as Spark itself admits them; the
  functions Spark accepts over them fuse, and the casts among `DATE`, `TIME`, the microsecond and
  the nanosecond timestamps.
- **G5. Delivery by cheaper models.** After the foundation, each fan-out task is one task plan
  from a template, with a one-command oracle; most fan-out pull requests are built by the cheaper
  models, each through the same review, and a ledger (row 658) records each one's builder and
  what its review found.
- **G6. The post.** What of Spark's datetime surface now runs in kernels, how that is known, how
  much faster it is, and how much of it the cheaper models built.
- **G7. Speed where a time zone is read.** A fused `TIMESTAMP_LTZ` expression runs significantly
  faster than vanilla Spark: than the row path of the same build, section by section, and than
  the newest stock Spark release on a timestamp surface. No fused shape is slower than the row
  path on a measured host; a shape that would be declines there.

### 1.2 Done when

The census items are read from `dev/varka_census.py --summary`, which prints the pairs fused and
admitted for each type; row 654 gives it this milestone's files. The ratios below are fixed; the
counts beside them are 25 November 2026's and move with the census, as the upstream sync adds
entries and rows 654 and 661 add the new types.

1. **G1:** at least three quarters of `TIMESTAMP_LTZ`'s (entry, type) pairs fused (about 66
   entries are expected to admit it).
2. **G2:** the every-zone sweep passes in CI under each rule by which Spark resolves a local
   time in a gap or an overlap (2.1); every kernel that reads a time zone has a test whose rows
   straddle both of a year's changes of offset; and over ten years of instants in no order in a
   daylight-saving zone the kernels of the fields, of the casts and of the sum with an interval
   decline no batch.
3. **G3:** at least nine tenths of the (entry, type) pairs over `DATE`, `TIMESTAMP_NTZ`, `TIME`
   and the two interval types fused (203 of 278 today, so 251), and at least two thirds of the
   microsecond temporal casts fused or declined with their reason: the 42 legal pairs of
   `m8/casts.tsv` with a temporal side, plus `TIMESTAMP_LTZ`'s pairs row 654 adds (23).
   *27 November 2026:* milestone 9's census counts the seven date functions that take a `DATE`
   over `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ` too (`VARKA-660.md` 16): 7 pairs more in this
   item's count, and 7 more in item 1's, which then reads 77 entries.
4. **G4:** at least three quarters of each nanosecond type's pairs fused under the flag, and at
   least two thirds of the nanosecond types' cast pairs row 661 adds (20, `VARKA-660.md` 4.2)
   fused or declined with their reason. On the counts of `VARKA-660.md` 4.2 that is 52 of
   `ntz_ns`'s 69 pairs, 51 of `ltz_ns`'s 68, and 14 of the 20 cast pairs.
5. The six carried-over rows (30, 95, 103, 351, 364, 366) meet their own done-when.
6. Rows 664 to 666 meet the done-when their catalogue items state, and item 50 too if it is
   taken.
7. Every pair left unfused carries its reason in the census: a declined band, an owner's
   decision, a function Spark itself rejects over the type, or a named row of the next milestone.
8. **G5:** the share of fan-out pull requests the cheaper models built, and their review finding
   counts, reported from row 658's ledger; no threshold.
9. **G6:** the post is published.
10. **G7:** on the AMD EPYC 7763 at 256 bits, in the baseline classes of row 655 under a
    daylight-saving zone with a year of instants in no order, every `TIMESTAMP_LTZ` section whose
    kernel reads the zone runs at least five times the row path's rate and every other section at
    least twice; no section is slower than the row path at 256 or at 128 bits; and the timestamp
    surface against the newest stock Spark release is committed for the post. The nanosecond
    `TIMESTAMP_LTZ` sections meet the same bars in phase 2. The bars are the owner's (decision
    9).
11. Rows 590, 680 and 681, which decision 14 added, meet their own done-when.

### 1.3 Out of scope

- The datetime functions that read or write a string (`date_format`, `to_char`,
  `from_unixtime`, `to_date`, `to_timestamp` and its forms, `dayname`, `monthname`,
  `unix_timestamp` of a string): the `STRING` decision stands.
- A time zone read from a column: `from_utc_timestamp`, `to_utc_timestamp`, `convert_timezone`
  and `make_timestamp` take the zone as a `STRING`, so a literal zone is in and a zone column is
  out under the same decision.
- Casts to and from `STRING`; aggregates and window functions; the coverage spine (TPC-H q6 and
  q1), which moved with the catalogue to milestone 10.
- A function Spark rejects over the nanosecond types (today `ts - ts`, `months_between` and
  `timestampdiff`): Varka follows Spark as Spark's support moves.

### 1.4 Order

Section 4 has the waves. In short:

0. **Before the fan-out:** milestone 8 closes; the upstream sync (row 663: the fork is 345
   commits behind apache/spark, and the sync brings SPARK-61538's nanosecond fix among others);
   the shared-file fixes (row 642; milestone 8's row 646, one file per plan row; and row 653,
   the files every type row appends to or regenerates); the baselines (row 655); the census and
   the cast matrix with `TIMESTAMP_LTZ` (row 654); the task template, its oracle and its ledger
   (row 658); the pilot (row 659); the register of upstream behaviours and the Build merged with
   master (rows 681 and 590); the never-slower gate from the close (row 680). Milestone 8's open
   rows that wait on a carried row (340's
   `SECOND` as a decimal waits on 351; 349's narrowing casts wait on 364) close in milestone 8
   with that part named as milestone 9's.
1. **Phase 1:** the six carried-over rows; the `TIMESTAMP_LTZ` lane and the time-zone design
   (rows 656, 657 and 682 to 692), built at full strength; then the fan-out over G1 and G3
   (rows 667 to 669 and 672), with the full-strength rows 664 to 666, 670 and 671 beside it.
2. **Phase 2:** how precisions 7 to 9 enter the census (row 660), then the census with the
   nanosecond types (row 661) and their layout (row 662), built both ways and measured; then the
   fan-out over G4 (rows 673 and 674), with row 675 at full strength.

### 1.5 Defaults, until a row's design says otherwise

- **Time zones:** the offset is a step function of the instant, evaluated over the changes of
  offset a batch spans. One offset a batch is the first form built; it serves UTC, fixed offsets
  and instants in time order, and declines nearly every batch of instants in no order (2.2).
  Steps written out for the changes a batch spans serve those, and a table serves a batch that
  spans many years; each is built and measured, and the default at each vector width is the
  fastest that passes G2's sweep.
- **The nanosecond layout:** the cache's pair of a microsecond long and a nanosecond short, and
  one long at the column's precision, both built and measured.
- **The oracle:** Spark's row engine; `java.time` a second reference at the edges.
- **Speed:** a baseline on the unchanged engine before a kernel, in its own pull request; every
  number from the AMD EPYC 7763 at 256 and 128 bits; a ratio under 1.3 run again and compared by
  minimums. The bar of decision 9 has this behind it: on the same runner the fused `BIGINT`
  arithmetic sections average 7.0X over their 50 sections
  (`VarkaBigintArithmeticBaselineBenchmark`'s committed results), and row 340 registered five
  times for `year(ntz)`.

### 1.6 Still open for the owner

- **Two readings of the owner's answers of 25 November 2026,** which 2.7 takes until the owner
  says otherwise: "impl on the laptop" as the whole task there (the compile, the suites the task
  names and the regeneration), with CI verifying the pull request as it does every pull
  request; and "one of" as the pilot's first task going to one model, the other two models
  taking theirs once it has merged.

## 2. Design

What each foundation row starts from. Spark was read on 25 November 2026 at master
`ceef882ae45`; names are given in place of line numbers, which move.

### 2.1 What Spark does with a time zone

- **The value.** A `TIMESTAMP` is a long of microseconds since the epoch in UTC, the same eight
  bytes as a `TIMESTAMP_NTZ`. In Arrow it is a `TimeStampMicroTZVector` whose field names the
  session zone; the zone there is a label, and the values are instants.
- **Where the zone enters.** A `TimeZoneAwareExpression` takes the session zone at analysis, one
  zone for the query. `zoneIdForType` gives the `TIMESTAMP_NTZ` family UTC, which is why
  milestone 8's kernels read no zone. The session accepts any name `DateTimeUtils.getZoneId`
  resolves: a region, a fixed offset, or one of `ZoneId.SHORT_IDS`.
- **What reads no zone.** Comparisons, null tests, choice, `IN`, `greatest`, `least` and the
  hashes read the long. So do `timestamp_seconds`, `timestamp_millis`, `timestamp_micros`,
  `unix_seconds`, `unix_millis` and `unix_micros`, which are not `TimeZoneAwareExpression`s,
  and the casts between `TIMESTAMP` and the numeric types and `BOOLEAN`. `timestamp_nanos` and
  `unix_nanos` are the nanosecond types' alone: one returns `TimestampLTZNanosType(9)` and the
  other takes no microsecond timestamp.
- **Instant to local** is a function of the instant: the fields, `CAST(ts AS DATE)`,
  `CAST(ts AS TIMESTAMP_NTZ)` and `from_utc_timestamp` add the zone's offset at that instant.
  An offset has second granularity, and before a zone's first standard time it is a local mean
  time that is no whole minute, so even `minute` and `second` read the zone.
- **Local to instant** meets a gap where the clocks go forward and an overlap where they go
  back. `java.time` resolves them by three rules, and Spark uses each:
  - *the earlier offset* in an overlap, and a time in a gap moved later by the gap's length
    (`LocalDateTime.atZone`): a cast from `TIMESTAMP_NTZ` and `to_utc_timestamp` (`convertTz`),
    and `make_timestamp` (`DateTimeExpressionUtils`);
  - *the offset kept* in an overlap while it is valid there (`ZonedDateTime.plusDays`,
    `plusMonths`): `ts + INTERVAL DAY`, whose whole days are calendar days
    (`timestampAddDayTime`), and `ts + INTERVAL MONTH` (`timestampAddMonths`);
  - *the gap's end* when midnight falls in a gap (`LocalDate.atStartOfDay`): a cast from `DATE`
    (`daysToMicros`).
- **A difference** of two timestamps is the microseconds between their two local date-times
  (`subtractTimestamps`), so it reads the offset at both ends.
- **Spark's own fast path.** `truncTimestamp` reads the offset at the input, truncates in local
  time, reads the offset again at the result, and takes `truncTimestampSlow` when the two
  differ. That is a guard and a fallback in scalar form, and it is why vanilla `date_trunc` is
  cheaper than the fields, which call `java.time` for every row.
- **A zone argument** (`from_utc_timestamp`, `to_utc_timestamp`, `convert_timezone`, the seventh
  argument of `make_timestamp`) is a `STRING`: a literal is one zone for the query, and a column
  is a zone a row (1.3).

### 2.2 The session time zone in a kernel (rows 678 and 657)

Two probes measured this section on 25 November 2026 (`sql/varka/spikes/zone-offset`, pull
request #1713): `results/facts.txt` over every zone id of JDK 25's time-zone data 2026f, 604 of
them, to the year 2100; and `results/forms.txt`, timings on the laptop, an AMD Ryzen AI 9 HX PRO
370. The runners' numbers are row 691's.

**The offset is a step function.** A zone is a sorted list of instants `T[i]` and the offset
`O[i]` that holds from `T[i]` on. For instant to local, `local = instant + O[#{T <= instant}]`.
For local to instant under the earlier-offset rule, `instant = local - O[#{LT <= local}]` with
`LT[i] = T[i] + max(O[i-1], O[i])`. The catalogue's item 89, bullet 7, had that formula from a
survey, "to be repeated before a design leans on it". The probe repeated it: `LT` rises strictly
in every zone, and the formula equals `LocalDateTime.atZone` at each of 257,568,575 local times
around the 65,794 changes. The kept-offset rule is the same formula with one exception, inside
an overlap, where the source's own offset wins if it is one of the two; that equals
`ZonedDateTime.ofLocal` at each of 237,632,993 local times inside a gap or an overlap, with
each offset a source could keep. The gap's-end rule differs only when midnight is strictly
inside a gap: one day, 31 March 1919, in six zone ids.

**What the data allows.** The closest two changes of one zone are 601,200 s apart, so a bucket
of 2^39 microseconds holds at most one, on either time line. No zone has more than 5 changes in
any 365 days or 42 in any ten years, and none has more than 8 distinct offsets. The last offset
that is no whole minute ended in 1972 and the last that is no whole quarter hour in 1979; 562
ids have had whole-hour offsets since 1970. The 604 ids are 349 distinct sets of rules, and 397
ids have no change after 2025.

**One offset a batch is not enough.** With one offset a batch, a batch whose instants lie on
both sides of a change must decline. In `America/Los_Angeles`, 1000 of 1000 batches of a year of
instants in no order span a change, at 4096 rows and at 10000; in time order 2 of 1000 do. So
the entries would count as fused in the census and gain nothing on such data (G2, G7). One
offset is right for UTC, for a fixed offset and for a zone that has stopped changing, and it is
the first form built.

**The zone database (row 678).** `java.time` keeps a zone for one lookup at a time: a binary search,
then a rule worked out for a year past its table. Row 678 lays the same data out for vector code,
once a zone in each JVM, from that JVM's own `ZoneRules`: the zone's class (fixed; no change after a
given instant; yearly changes), its changes sorted on both time lines with the window of each gap
and overlap, the instants from which its offsets are whole minutes and whole hours, the rule years
written out as they are needed and repeated by the 400-year cycle, and the bucket table (11,482
buckets a zone from 1900 to 2100: 89 KB at eight bytes a bucket, and three times that as the probe's
three arrays). Ids with the same rules share an entry. It is special data structures over the JVM's
data, not a second time-zone database: a shipped copy would let a kernel and the row engine disagree
after an update of either. Row 678 is Varka's own, which the evaluator reads, and is built first;
the library (2.10) ports its merged files. (26 November 2026: the packed table of `VARKA-686.md` is
183,712 bytes a zone, 16 a bucket.)

**The forms, and what each cost on the laptop:** nanoseconds a row to write `instant + offset`
for a batch of 4096 instants in no order, each form equal to `java.time` on every row.

| form | 8 lanes | 4 lanes, AVX2 | 2 lanes, AVX2 |
| :--- | ---: | ---: | ---: |
| `java.time`: the offset | 10 | 10 | 11 |
| `java.time`: the hour of the day, as the row engine reads it | 27 | 26 | 26 |
| one offset | 0.078 | 0.077 | 0.088 |
| steps written out, 1 change | 0.087 | 0.077 | 7.007 |
| steps written out, 2 changes | 0.086 | 0.148 | 13 |
| steps written out, 4 changes | 0.145 | 0.382 | 29 |
| steps written out, 8 changes | 0.289 | 0.755 | 63 |
| steps read in a loop, 8 changes | 1.199 | 1.440 | 51 |
| 2 steps over a year, their range not checked | 0.084 | 0.145 | 12 |
| 2 steps over a year, their range checked by an OR | 0.096 | 0.232 | 14 |
| 2 steps over a year, their range checked by least and greatest | 0.104 | 0.250 | 14 |
| 2 steps with no mask, checked by an OR | 0.139 | 0.346 | 0.685 |
| a pass for the least and greatest instant, alone | 0.058 | 0.232 | 0.393 |
| the table, three gathers | 0.78 | 0.88 to 0.95 | 39 |

- **Steps written out** (a compare and a masked add for each change the batch spans, the
  change points and deltas in the kernel's slots) cost little more than one offset and under a
  fiftieth of `java.time`'s lookup. Read in a loop they cost twice as much at four lanes and
  four times at eight, so a kernel is emitted for its number of steps: a few sizes, the unused
  steps padded with a change that never comes.
- **The table** costs the same at any number of changes. At four lanes it wins past about nine
  changes a batch, at eight lanes past about twenty. It is the form for a batch that spans many
  years. The probe's two table rows touch a few kilobytes of the table, so the cost of a batch
  spread over all of it is still to be measured.
- **Knowing the batch's range is the dear part.** A pass before the kernel costs more than two
  steps at four lanes, and milestone 7's date bound cost 10% to 17% on the cheapest date kernels
  until its check was folded into the loop. The Arrow cache already records each batch's least
  and greatest value of a `TIMESTAMP` column (`ArrowCachedBatchSerializer`'s statistics): where
  the input is a cached column the range is free, and that comes first. Where it is not, steps
  carried from the batch before are checked in the loop by one OR a vector: every instant is
  inside a power-of-two range the steps cover exactly when the OR of every `instant - from` has
  no high bit, and a batch that leaves the range runs again with steps of its own. That check
  is not free either: over two steps it costs 0.09 ns a row at four lanes and 0.01 at eight, by
  the minimums of two runs. A zone that has stopped changing needs one bound.
- **At two 64-bit lanes this JVM compiles no mask** (`sql/varka/skills/the-jit.md`). The masked
  steps and the table's gathers ran as Java loops there: two steps cost more than `java.time`'s
  own lookup, and the table more than three times as much. The step written as a subtract, a
  shift and an AND has no mask. A kernel takes that form at two lanes or declines there, as row
  503 does for a long-lane condition.

**What needs no offset.** A field finer than the offset's own grain reads the instant alone:
`second` wherever every offset in the batch's range is whole minutes, and `minute` wherever
they are whole hours. ClickHouse keeps the two as flags of a zone; here the database holds the
instant from which each holds, and the kernel's range check covers it. Spark itself reads no
zone to truncate to a second or finer.

**How the values reach a kernel.** A kernel's constants are literal slots
(`VarkaVectorIR.LiteralSlot`): scalars it receives in an array on every call and broadcasts
once, with their values outside the shape key. Today the compiler fills them from the plan's
literals, once a query. The offset, the change points and the range are the first slots the
evaluator fills from the batch, so row 657 designs that kind of slot: who computes it, in which
table (one a task, since two tasks run one compiled projection), and how a batch that leaves
the range runs again or declines. The only per-batch mechanism today, `VarkaInputBound`, checks
an int interval fixed at compile time and can only decline.

**What stays out of the kernel's bytes.** The zone is a property of the compiled plan, as an
input bound is, so two sessions in two zones share a kernel class. The offsets come from the
executor's `ZoneRules`, the rules Spark's row engine reads there, never from the driver
(catalogue item 127's rule, applied to zone data). A literal zone argument is a second zone with
its own slots. A zone with a constant rule is a fixed offset from the plan on
(`ZoneId.normalized`).

**Two nodes carry the family.** `ToLocal` (instant to local) and `ToInstant` (local to instant,
with its rule) are IR nodes; a zone-aware expression over a `TIMESTAMP` lowers to the
`TIMESTAMP_NTZ` kernel between them. So the fields need no kernel of their own, and a fan-out
task over a field is its admission, its coverage rows and its tests.

**The proof.** A unit suite sweeps every region of CI's JDK, Spark's short names and a sample of
fixed offsets: each change of offset, two hours either side at a one-second step, and the far
past and future, under each of the three rules, against `java.time`. The probe ran that sweep
for the formulas in under a minute, so it runs in every Build. The Spark-level suites take a
fixture whose rows straddle both changes of a year, because on any other rows a wrong kernel
agrees with Spark (catalogue item 31). The fuzzers draw the session zone from a list with
daylight saving, a half-hour change (`Australia/Lord_Howe`), a 45-minute offset
(`Asia/Kathmandu`) and a zone that crossed the date line (`Pacific/Apia`).

**Names.** New zone classes take the `Chrono` name, as the house rule has it.

*25 November 2026, from row 657's plan (`VARKA-657.md` 1 and 2.9), which read the code and ran
two more probes:* the two nodes stay (decision 17), with a second record for the kept offset; the
values a batch brings are arguments of their own after the literals, not literal slots; a form
is an emit option; the cache's statistics stop at the scan today, so the first source of a
batch's range is a pass over the column; and a form is chosen from that range, so no batch runs
twice. The work is rows 657 and 682 to 692.

### 2.3 `TIMESTAMP_LTZ` on the long lane (row 656)

The registry (`VarkaTypes`) declines `TIMESTAMP` today with the reason "its arithmetic is in the
session time zone, which is not lane arithmetic". The row turns the entry to the long lane, with
the census name `ltz` (*27 November 2026:* row 654's part b gives the entry that name first,
while it still declines, `VARKA-654.md` 4.5), and takes the path of
`sql/varka/ADDING_A_TYPE.md` for a type that joins a
lane: the registry and `types.tsv` (step 4), the fuzzers' entry and fixture column (step 9),
`TimeStampMicroTZVector` in `VarkaLaneArrow` in and out (step 10), the coverage rows (step 11),
the benchmark (step 14), the matrix and the linters (steps 15 and 16). The one new piece of
plumbing is the output: Arrow needs the session zone in the field of a `TIMESTAMP` vector, so
the evaluator carries the zone's name to `allocateVector`.

The row fuses what reads no zone and needs no new node: the load, the comparisons and null-safe
equality, the null tests, `IN`, `greatest`, `least`, `CASE`, `IF`, `coalesce` and the output.
Milestone 8's row 338 is the model: one plan and one code pull request (#1028, #1031), 40
files, most of its lines generated.

### 2.4 The census and the cast matrix (rows 654 and 661)

- `dev/varka_census.py` writes `m8/census.tsv` and reads `m8/census_curated.txt` by fixed
  paths. Row 654 gives it the milestone as an argument and a census and a curated file in this
  folder; its `--summary` already prints the pairs fused and admitted for each type, which is
  what 1.2 reads. Milestone 8's census and cast files are named by fixed paths in other readers
  too: `dev/varka_new_lane.py`, `dev/varka_regen_generated.sh`, `dev/varka_nightly.sh`, the fuzz
  workflow, the merge driver's list in `sql/varka/.gitattributes` and `VarkaCastMatrixSuite`.
  *27 November 2026:* the readers are thirteen, not these six; `VARKA-654.md` 4.3 lists each
  with its edit.
  Row 654 brings each to the milestone's files, since row 656's `dev/varka_new_lane.py --check`
  looks for its lane in the census.
- 20 entries are out of scope today with the reason "`TIMESTAMP` with local time zone".
  `current_timestamp` and `now` fold to literals before Varka sees them. The other 18 come into
  scope: 6 conversions with the epoch that read no zone; `timestamp_nanos` and `unix_nanos`,
  which are phase 2's (row 675); and `from_utc_timestamp`, `to_utc_timestamp`,
  `convert_timezone`, `date_trunc`, `months_between`, `make_timestamp`, `try_make_timestamp`,
  `make_timestamp_ltz`, `try_make_timestamp_ltz` and the cast alias `timestamp`.
  *27 November 2026:* Spark's `convert_timezone` takes and returns `TIMESTAMP_NTZ`, so the
  census holds it as an `ntz` entry (`VARKA-654.md` 4.6).
- The cast table gains `TIMESTAMP_LTZ`'s 23 legal pairs, from `Cast.canCast` and
  `Cast.canAnsiCast`: the seven numeric types in both directions, `BOOLEAN` in both directions
  under LEGACY only, `DATE`, `TIMESTAMP_NTZ` and `TIME` in both directions, and the identity.
  The 16 with a number or a boolean read no zone. `VarkaCastMatrixSuite` holds the table to
  Spark's three legality functions, as it does today.
- **After milestone 8's close** (decision 11). Row 654 writes this milestone's census beside
  milestone 8's, which stays as its close left it, so no new type moves a count the close
  read.
- **The nanosecond types (row 661).** Spark's support moves weekly, so admission is not a hand
  list: a suite asks Spark's own type check, with the preview flag on, whether an entry accepts
  the type, and writes the answer as a table the census reads, as `VarkaTypesSuite` writes
  `types.tsv`. An entry Spark rejects carries that as its reason, and a later sync then moves
  cells by itself. The flag is on by default under tests and off in production, so the
  census sets it and does not rely on the default. The nanosecond types add 20 cast pairs
  (`VARKA-660.md` 4.6): with `DATE`, `TIME`, the two microsecond timestamps and each other. Row
  681's register of upstream behaviours takes SPARK-56822, the preview, as a moving dependency.

### 2.5 The nanosecond timestamps (rows 660 and 662)

- **Spark's value** is a `TimestampNanosVal`: `epochMicros`, a long, and `nanosWithinMicro`, a
  short from 0 to 999. Its range is the microsecond types' whole range, not a long of
  nanoseconds. The flag `spark.sql.timestampNanosTypes.enabled` is internal and off in
  production; SPARK-60335 made every function without nanosecond support reject the type in
  place of narrowing it.
- **What a kernel sees.** The Arrow cache stores the value as a struct of two children, a long
  and a short. It is the first Spark column that is two Arrow vectors, and `isArrowBacked`
  admits only lane vectors today. One value is two words, as a 128-bit decimal is in the
  registry; the output is a struct of two vectors.
- **Precision** is in the type, not in the value's layout: a cast to a lower precision floors
  the nanoseconds to a power of ten. Row 660 decides how that enters the census and the cast
  table. The plan's lean is one type per kind, `ntz_ns` and `ltz_ns`, with the precision a band
  only where an answer depends on it, as `dec` carries its two bands; six types would multiply
  rows whose kernels are the same.
  *27 November 2026:* the owner chose two types with a band (`VARKA-660.md`): 137 (entry, type)
  pairs and 20 cast pairs, where six types give 411 and 84. The answer depends on the
  precision in `timestampadd` with the `NANOSECOND` unit and in six cast pairs. The seven date
  functions that take a `DATE` count over the two types, by his word of the same day.
- **Two layouts (row 662), both built.** (a) Two words, the cache's own form: exact over the whole
  range, and every field reads the microsecond word alone, so the fields are the microsecond
  kernels. Only `second` with its fraction and the arithmetic touch the second word. (b) One long at
  the column's precision, as ClickHouse's `DateTime64`, QuestDB's nanosecond type and DuckDB's and
  Arrow's unit types hold a timestamp: one word and plain arithmetic, a multiply and an add on load
  and a division on store. Its range is the years 1677 to 2262 at nine digits, ten times as wide at
  eight and a hundred times at seven, and a batch outside it declines. The measurement is on the
  baselines of row 655. The library (row 679) holds both layouts first, outside Spark, and its
  benchmark of the two shapes on the same values (`VARKA-679.md` 5.2, L14 and L17) is their first
  measurement.
  *28 November 2026:* the owner asked that the representation be measured for vectors before it
  is built, as SPARK-56822 analysed it for a scalar engine. Row 662 is now that measurement
  alone: four representations in hand loops (Spark's two parts, one long, seconds with
  nanoseconds, days with nanoseconds), their cost table, and their conversions as proved
  equalities. Row 699 builds the cache's struct in and out and the kernels: over all three
  representations that hold the whole range, by the owner's answer of the same day, and over one
  long where it is the fastest, with the measurement naming each class's default. Where this
  section or a row's line says that row 662 builds the struct, a layout or a kernel, read row
  699. The two layouts above are no longer assumed. A representation is not fixed for the type:
  the table and the equalities are kept so that a choice by query and by source (`m10/SCOPE.md`
  item 11) stays open.
- **Floor, never round.** A cast to a lower precision floors in Spark. Trino rounds half up,
  DuckDB rounds half away from zero and Arrow truncates toward zero, so none of the three is a
  reference here.
- **Edges.** SPARK-57838, in the fork, says where Spark raises at the range's ends; the tests
  pin those values against `TimestampNanosVal`.
- **Zones.** A nanosecond `TIMESTAMP_LTZ` takes row 657's offset on its microsecond word; an
  offset is whole seconds, so the nanosecond word is untouched.

### 2.6 Shared files (rows 642 and 653, and milestone 8's row 646)

Parallel rows conflict in three kinds of file, and the fan-out multiplies each.

- **The task table.** Two rows that edit neighbouring lines of `PLAN.md` conflict. Row 646 of
  milestone 8 moves each row to a file of its own and renders the table; this plan's table
  moves with it. Until then the fan-out's rows are all in section 3 already, so a fan-out pull
  request adds no row.
- **The option files.** Row 642: an emit option is declared in one place.
- **What every type row appends to or regenerates.** A cast task of milestone 8 (#1434) touched
  23 files. Its own were five: a compiler family, two suites, the row's plan and a benchmark.
  The rest were shared: `VarkaCoverageSuite.scala`'s rows, `VarkaExpressionCompilerSuite.scala`,
  `census_curated.txt`, one line of `casts.tsv`, `skips.tsv`, and the generated `coverage.json`,
  `emitted_bytes.json`, `width_audit.json`, `census.tsv`, `casts_fused.tsv` and the docs table.
  Row 546's merge driver (#1293) merges the generated ones in a local clone, but GitHub's
  mergeability check and its squash merge run no driver, so each merge to master leaves the
  other open type rows in conflict, and each then costs a merge, a regeneration on the laptop
  and a new Build. Regenerating in CI after a merge is excluded: the fork has no token.

Row 653's rule: a task adds files of its own and edits only its family's. The lists a task
appends to become one file an entry (a coverage row, a compiler expectation, a curated claim, a
cast pair, a skip line), read by the suites and the tools that hold the lists today; a generated
file and each of its digests are split the same way, so a regeneration writes only the task's
own files. Sibling casts sit on neighbouring lines of `casts.tsv`, so a split by family alone
is not enough.

*27 November 2026:* `VARKA-653.md` is the plan. The three generated JSON files stay where a reader
opens them, as views that git ignores, and what is committed is one record a row; the coverage table
and the skip list keep what they hold and take a new entry as a file; the docs' row tables are
rendered in a commit of their own. By the owner's word the census's files are row 696's.

### 2.7 The cheaper models' work (rows 658 and 659)

**The task plan** is generated, not written by hand, so that it holds nothing to decide. For one
entry and one type it names:

1. Spark's class and the methods that define the answer.
2. The sibling to copy: the merged kernel of the nearest type, with its tests.
3. The files to add and the files to edit, in order, and what goes in each.
4. The commands, in order, each with the output it must print: `dev/varka_emit.sh` on the
   expression must read `FusedOutput`; the family's suites; the differential suite; the
   regeneration; `python3 dev/varka_census.py --check`; `dev/varka_msg_check.py pr`.
5. The declines to keep, each by its reason and its test.
6. The house rules that bind the change, quoted from the `AGENTS.md` files, and the attribution
   line with the model's own tool name.
7. What not to do: no new IR node the plan does not name, no edit outside the listed files, no
   change to a default.
8. How to open the pull request: the branch, the title, the body's sections, the ledger line.

`sql/varka/ADDING_AN_EXPRESSION.md` is the page such a plan points at, and it describes
milestone 4's engine: int lanes only, `m4/PLAN.md` as the task table, `dev/varka_gate.sh` as the
gate. Row 658 brings it to today's tree.

**The oracle** is one command for an entry and a type: the compile, the suites the task names,
the regeneration, and the checks. It fails on an unfused entry, on a stale generated file, on a
pull request over the size cap, and on a benchmark section slower than the row path.

**Where a model works** (decision 10): on the laptop, where it runs the oracle. The laptop runs
one heavy JVM at a time, so a model's run takes the lock the coordinator's runs take, and the
tasks go through it in turn. That makes the oracle's time the fan-out's rate: the pilot
measures it, and row 653 keeps a task's regeneration to its own files. CI verifies each pull
request, as it does every pull request.

**The loop.** The coordinator generates the plan. The owner gives it to a model. The model
builds, runs the oracle and opens the pull request. The review (`/code-review high`) posts its
findings on the pull request, and the model fixes them: one review and one fix round. Then the
pull request merges, or the coordinator takes it over, or the task returns to the pool. The
ledger records which, with the builder, the rounds and the finding counts; a pull request the
coordinator had to rewrite does not count as built by the model.

*27 November 2026, the owner's answer to `VARKA-658.md`'s question 2:* the model does not
open the pull request. It builds, runs the oracle, runs `dev/varka_fanout.py ready` and
stops at `READY` with a report. The lead then gates and reviews the branch and opens the
pull request through `open-pr.sh`, as for every pull request; it gives the review's
findings to a builder as a file, and that fix round ends at `READY` again.

**The pilot (row 659).** The four casts from `TIME` to the integral types are legal, unfused
and claimed by no row. Each is a floor division by the nanoseconds in a second, the narrower
targets checking their range; `dt -> bigint` is the fused sibling, and they need no new type and
no zone.

- *First* (decision 12), one model builds `time -> int` from its generated plan, and the pull
  request goes through the loop above. What it teaches goes into the template before the next.
- *Then* the other two models take `time -> smallint` and `time -> tinyint`, and the first
  takes `time -> bigint`, in flight together. Three siblings of one family are row 653's
  hardest case.
- *Admission:* a model joins the fan-out when its pilot pull request merges within two fix
  rounds. One that does not gets a second task, one of the five always-null casts from `DATE`;
  after that its share goes to the others or to the coordinator, and G5 reports the share as it
  is.

*27 November 2026:* `VARKA-658.md` is the plan. One tool, `dev/varka_fanout.py`, renders a
task's plan from a card, holds a change to the files that plan names, runs the oracle and
prints the ledger. Four things differ from the list above. The oracle asks
`VarkaCoverageSuite` whether the task's coverage rows fuse, not `dev/varka_emit.sh`, whose
default columns are not the coverage table's. The model writes no ledger line: its pull
request's body carries the oracle's line, and the lead writes the ledger. A benchmark
section whose numbers the branch does not bring is reported as owed and does not fail; it
fails once the runner's numbers are committed on the branch. And the model opens no pull
request (item 8, and "The loop" below): it stops when `dev/varka_fanout.py ready` prints
`READY`, and the lead opens the pull request after its own gate and review. The library's
pull requests (2.10) are ledger lines of a kind of their own, counted beside the fan-out's
share.

### 2.8 The upstream sync (row 663)

The fork's base is apache/spark of 10 November 2026, 345 commits back. The sync brings
SPARK-61538 (special datetime values in casts to the nanosecond types), SPARK-62149 (`try_abs`
and `try_negative`, which take the two interval types and so add census pairs to G3),
SPARK-61352 (the query context of interval errors, which ends the reason of three
interval-division declines) and SPARK-56076 (the types framework's row access, to be read for
what it changes under Varka's readers). The census fails on the three registry names the sync
adds until each is curated in or out. The sync waits for #1708 and #1675, and for the owner's
answers on those names.

### 2.9 What other engines do

Read on 25 November 2026 in the local checkouts of ClickHouse, QuestDB, Trino, Druid, Velox,
Gluten, Comet, DuckDB, StarRocks, Arrow, DataFusion and abseil's cctz. None of them converts a
named zone in vector code: each reads the zone a value at a time. ClickHouse was read directly
and the others by a survey; a row checks a claim again before it leans on it. The reading note
behind this table comes after the catalogue's move, since `dev/varka_catalogue_move.sh` took every
reading note of this folder with the catalogue.

| engine | how it reads a zone | what this plan takes |
| :--- | :--- | :--- |
| ClickHouse | a table for each zone with one entry a local day from 1900 to 2299: the day's first instant, its calendar fields and the day's change of offset; a lookup is a division and two compares, with no search (`DateLUTImpl`); two flags say that a zone's offsets are whole hours or whole minutes | a table whose bucket holds at most one change, read with no search; the two flags, which free `minute` and `second` of the zone |
| cctz | sorted changes, each with its local time on both sides, so one compare says whether a local time is unique, skipped or repeated; a hint remembers the last change found; the yearly rule written out for 400 years, later instants moved back by whole cycles | both local times in the database; the carried steps, which are its hint for a batch; the 400-year cycle |
| QuestDB | the JDK's own change arrays, copied, with a cache of each year's rule; it remembers the interval it is in and when that ends, and starts doing so only after two near misses; one long of nanoseconds for its nanosecond type | the database read from the JVM's own rules; the guard against thrashing on instants in no order |
| Trino | Joda for every value; a `timestamp(p)` past six digits is a pair, microseconds and the picoseconds of the microsecond, and a field reads the first word alone | the two-word layout and its rule for fields |
| Druid | Joda for every value; truncates in UTC when every offset of the zone is a multiple of the unit | the same test, over the offsets in the batch's own range |
| Velox, Gluten | a binary search for every value and no cache; fixed offsets in whole minutes, and `second` reads no zone | its gaps as tests: an offset with seconds, and Spark's own daylight-saving test for `timestampadd`, which Gluten excludes |
| Comet | UTC is arithmetic and any other zone a library call a value; `date_trunc` outside UTC, `from_utc_timestamp`, `make_timestamp`, `months_between` and `timestampadd` run in Spark's own code; a zone with a constant rule becomes a fixed offset when the plan is built | the fixed-offset test at plan time (`ZoneId.normalized`) |
| DuckDB, Arrow, DataFusion, StarRocks | a calendar or library call a value; one long at the type's precision for nanoseconds; in an overlap DuckDB takes the later instant | the long at the column's precision as the second nanosecond layout; each difference from Spark as a test |

### 2.10 A library for the batch forms of `java.time` (row 679)

The owner's decisions 13 and 16: the library is `vechrono`, at `vecbricks/vechrono`, a
repository that row 679 creates when it starts. vfdlibm is the model: its own repository, a
release on Maven Central,
a test-scope dependency of `sql/catalyst`, and never a runtime dependency of a kernel.

- **What it holds.** For each `java.time` method that Spark's datetime expressions call, a form over
  arrays with the same answer in every lane: the zone database of 2.2 and the offset forms, local to
  instant under the three rules, the calendar and clock fields, the sums with days and months, the
  difference of two local date-times, and truncation. Every form has nanosecond precision from the
  library's first release, in two shapes (the owner, 27 November 2026): the two words of 2.5, a long
  of microseconds and the nanoseconds within it, which is the contract over the whole range; and one
  long of nanoseconds, a measured arm over the years 1677 to 2262. It holds no parsing, no
  formatting and no `java.time` objects.
- **Its contract** is `java.time`'s answer to the nanosecond, bit for bit, in every zone of the
  running JVM. The every-zone sweep of 2.2 is its test, and the forms' benchmarks at each vector
  width are its record. The probes of 2.2 are its first content.
- **The first implementation.** A form is built there first: a build takes seconds and needs no
  Spark, and a function with `java.time` as its oracle is the smallest task a cheaper model can
  take. Varka then lowers the form in its own IR, by its own measurement, since fused code calls
  no library; the port is paired with the library's pull request.
- **The test reference.** Varka's suites check a kernel against the library's form and against
  `java.time`, as the math ports' suites check against vfdlibm and `StrictMath`.
- **What does not wait.** No row of this plan waits on a release of the library: a suite reads
  `java.time` alone until the release holds what it calls.
- *27 November 2026, from the plan of row 679:* a Varka suite can name only a class of the
  library's pinned release, so the cross-check against the library joins with the Varka pull
  request that raises the pinned version. And the cheaper models' pull requests in the library
  count for G5 only if row 658's ledger takes them: that row's plan decides how.

### 2.11 What the scope catalogue gives this plan

The scope catalogue was read item by item on 25 November 2026 against the goals. An item is this
milestone's when it serves one: the datetime surface (G1 to G4), the speed of the long-lane
kernels the timestamps run on and the comparison with stock Spark (G7), or what the fan-out's
rate and safety rest on (G5).

- **Taken** (decision 14). Item 120 as row 680; item 123 as row 681; row 590 of item 138's
  table, which tests a pull request merged with master, item 129's subject; item 132 as a part of
  row 655; and, from item 39's table, `make_ym_interval` over plain int columns as a part of row
  672. Items 31, 117, 121, 125 and 126, bullet 7 of item 89 and row 642 were taken when this
  plan opened, and item 50 on its condition (G3).
- **Read by a row's plan, with no row of their own.** Row 657 reads three: where a form is
  chosen (item 127 wants each CPU-dependent form chosen on the executor that runs it, and
  today's three are chosen on the driver); how the session zone reaches the compiler (item
  106's one value for the per-compile settings); and the range from the cache's statistics (the
  statistics-directed guards of item 15's table and the batch bounds of item 39's). Row 678
  reads item 10, where one table entry holds a day's fields beside its offset. Row 662 reads
  item 11, which makes a physical representation the compiler's decision. Row 658 reads the
  checks of item 112.
- **Related, and left in the catalogue.** The mechanisms for a choice by host (items 73, 101,
  118, 119, 131 and 134), by decision 15. The levers that a missed bar of G7 would call for
  (items 13, 14, 37 and 99). The tuning of calendar kernels that already fuse (item 87, and the
  calendar rows of item 15's table). Fork-only date functions (item 12) and a date column's
  bound past the year 9999 (item 102), which are not Spark's surface and not Spark's data. CI
  throughput (item 122 and the matrix and harness rows of item 138's table): the length of
  section 4 needs about four merges a working day, and the last 200 merges took 17 days.

## 3. Tasks

Sizes are the house's small, medium and large. A row opens **Filed** and has no plan file until
its first pull request. Whoever takes a row says so on its issue before starting, the owner
included, so that no row is built twice. Rows 667 to 669 and 672 to 674 are fan-out rows: one
row a family, one part a pull request, two to four sibling entries a part, each part from a
generated plan.

| task | what it is | where it came from | size |
| ---: | :--- | :--- | :--- |
| 30 | **Started** (carried over from milestone 8 on 25 November 2026; its state and record are `m8/PLAN.md` row 30 and its plan). The integral division family | milestone 8, `VARKA-30.md` | large |
| 95 | **Started** (carried over from milestone 8 on 25 November 2026; state and record: `m8/PLAN.md` row 95 and its plan). The checked int multiply over a column, and `i % k` | milestone 8, `VARKA-95.md` | medium |
| 103 | **Started** (carried over from milestone 8 on 25 November 2026; 103a1 merged as #1534; state and record: `m8/PLAN.md` row 103 and its plan). Day-time interval arithmetic, products, quotients, extracts and `make_dt_interval` | milestone 8, `VARKA-103.md` | medium |
| 351 | **Planned** (carried over from milestone 8 on 25 November 2026; state and record: `m8/PLAN.md` row 351 and its plan). Decimals in the existing types' functions, and the rounding functions | milestone 8, `VARKA-351.md` | large |
| 364 | **Planned** (carried over from milestone 8 on 25 November 2026; state and record: `m8/PLAN.md` row 364 and its plan). The 128-bit decimal casts | milestone 8, `VARKA-364.md` | medium |
| 366 | **Planned** (carried over from milestone 8 on 25 November 2026; state and record: `m8/PLAN.md` row 366 and its plan). The exact decimal-to-floating arm | milestone 8, `VARKA-366.md` | medium |
| 577 | **Started** (carried over from milestone 8 on 27 November 2026; its baseline, spike and readings merged, 577a waits on row 30; state and record: `m8/PLAN.md` row 577 and its plan). The decimal `mod`, `pmod` and `try_mod` by a variable divisor | milestone 8, `m8/VARKA-577.md` | medium to large |
| 579 | **Planned** (carried over from milestone 8 on 27 November 2026; state and record: `m8/PLAN.md` row 579 and its plan). A calendar node over a guarded narrowed long in the IR fuzzer's mixed shapes | milestone 8, `m8/VARKA-579.md` | small |
| 604 | **Started** (carried over from milestone 8 on 27 November 2026; its helper merged as #1623 and #1651, part 2 waits on row 577's 577b; state and record: `m8/PLAN.md` row 604 and its plan). The decimal remainder at two long lanes on x86 behind an option | milestone 8, `m8/VARKA-604.md` | medium |
| 609 | **Planned** (carried over from milestone 8 on 27 November 2026; state and record: `m8/PLAN.md` row 609 and its plan). A guard in an `IfElse` condition is qualified by the arms around the `IfElse` | milestone 8, `m8/VARKA-609.md` | small |
| 590 | **Planned** (taken from the scope catalogue on 25 November 2026, decision 14; its state and record are `m8/PLAN.md` row 590 and its plan). A pull request's Build tests it merged with Varka's master | the catalogue's item 138, `VARKA-590.md` | medium |
| 642 | **Planned** (`m10/SCOPE.md` item 138). Declare an emit option in one place, so parallel option rows stop conflicting | milestone 8 by item 138, `VARKA-642.md` | small to medium |
| 646 | **Filed** (carried over from milestone 8 on 27 November 2026; state and record: `m8/PLAN.md` row 646). Each row of the task table in its own file, and the table generated from them | milestone 8, the row | small to medium |
| 653 | The files every type row appends to or regenerates, one file an entry. **Planned** (`VARKA-653.md`, 27 November 2026: seven parts, the demonstration first; the three generated JSON files become one record a row with views that git ignores, each option arm one digest a row, three blocks of the docs page rendered in a commit of their own, and a new coverage row and a new skip each a file; the parts that move a list wait for rows 654 and 656; the plan measures 41 of 50 consecutive row-adding commits in conflict; by the owner's word of 27 November 2026 the census's files left the row for row 696, and the done-when below is the one the row meets, which the owner signed the same day; filed 25 November 2026; the admission check: #1434 touched 23 files, 18 of them shared, and on 25 November five open pull requests conflicted with master in generated files that the merge driver resolves only in a local clone) Section 2.6. **Done when** three branches that each add a sibling coverage row of one family, with its generated records and its matrix skip, and two that add rows of different families, merge with master in either order under `git merge-tree` with no merge driver, with no conflict in those files and with no record to regenerate; the three generated JSON files are no longer committed, and a missing record fails CI's docs job and a record that is not what the compiler gives fails the Build's suite, each with its command (the width audit's content on the laptop alone, as before); the docs page's row tables are written by no such branch, are refreshed by a render commit, and a page that is behind warns and never fails; and every tool and suite that reads the lists gives the same output before and after, but for the option arms' digests and the two-lane census's header, which change once and on purpose. What a branch writes for the census (its entry's line of the curated file, the census's line, the cast tables) is row 696's | `m10/SCOPE.md` item 121, its part of 11 November 2026; row 546 | large |
| 654 | The census and the cast matrix by milestone, with `TIMESTAMP_LTZ`. **Planned** (`VARKA-654.md`, 27 November 2026: two parts, the census by milestone and then `ltz` in the census and the cast matrix; it starts after the tag `varka-m8`; the plan counts thirteen readers, not six, and 77 entries that admit the type; filed 25 November 2026; the admission check: the tool and six other readers name milestone 8's census and cast files by fixed paths, and 20 entries carry the zoned reason) Section 2.4. **Done when** the summary for milestone 9 prints the pairs admitted and fused for each type, and every reader of the census and cast files reads the milestone's; `ltz` is a census type and each of the 18 entries that come into scope has a row or a reason; the cast table holds the 23 pairs and `VarkaCastMatrixSuite` passes; and milestone 8's census files are as its close left them | owner's decisions 1, 2 and 11; milestone 8's close and row 663 first | medium to large |
| 655 | Baselines for `TIMESTAMP_LTZ` and the nanosecond types before their kernels, and the timestamp surface. **Planned** (`VARKA-655.md`, 27 November 2026: seven parts, the zone-free class that row 656 needs first, then the fields and the casts, the arithmetic and the zone functions, the second run of the `TIMESTAMP_NTZ` files, the surface with two arms over the Arrow cache, and two parts of nanosecond classes in wave 4; nine classes on one base, a lead form in six cells and every other form in G7's cell, each with a `TIMESTAMP_NTZ` twin where one fuses; filed 25 November 2026; the admission check: the `TIMESTAMP_NTZ` baseline classes exist and no class reads a `TIMESTAMP`; the three `TIMESTAMP_NTZ` results files still hold their runs from before the kernels) One class a family, as row 377 built them: the zone-free sections, the fields, the casts, the arithmetic and the zone functions, each under UTC, a fixed offset and a daylight-saving zone, over instants in time order and in no order across one and ten years; and timestamp cases in the surface that runs against the newest stock Spark release, with a stock arm over the Arrow cache that release ships (the catalogue's item 132), so that the kernels' share is read apart from the cache format's, or the row records why the release's serializer cannot be compared with the fork's. **Done when** each class and both widths' results from the EPYC 7763 are committed before the type's first kernel, each later row's measurement diffs against them, and the `TIMESTAMP_NTZ` files are run again so that milestone 8's kernels have their numbers | owner's decision 8; the house rule for a baseline; row 377 | medium |
| 656 | `TIMESTAMP_LTZ` on the long lane. **Planned** (`VARKA-656.md`, 27 November 2026: five parts, the type named in every decline with nothing fused, the twin suite that keeps the two timestamp types apart, the lane, the lane through sessions under three zones, and the results; four code pull requests where prediction 2 of section 6 said two; it fuses `in_set` too, by the owner's word of that day; filed 25 November 2026; the admission check: the registry declines the type, and `types.tsv` reads `TIMESTAMP declined`) Section 2.3. **Done when** the registry has the type on the long lane; a `TimeStampMicroTZVector` is read and written, with the session zone in the output's field; the load, the comparisons, the null tests, `IN`, `greatest`, `least`, the choices and the output fuse with coverage rows; the conformance suite passes; `dev/varka_new_lane.py --check` passes; and no existing shape's bytes move | `m10/SCOPE.md` item 31, its zoned type; owner's decision 11; rows 654 and 655 first | medium |
| 657 | The session time zone in a kernel. **Planned** (`VARKA-657.md`, 25 November 2026, the design of this row and of rows 682 to 692: the conversions are IR nodes whose values for a batch are arguments after the literals, and a form is an emit option; the admission check: a literal slot is filled once a query, an input bound can only decline, and in the probe one offset a batch declines 1000 of 1000 batches of a year of instants in no order) Section 2.2: its plan and its probes first; then the first kernels end to end over row 682's node: the zone's index and the fold of a fixed zone, `hour`, `minute`, `second` and `CAST(ts AS DATE)`, the zone programs over row 678's data, the suite whose rows straddle both changes of a year, the coverage rows and the benchmark sections. **Done when** the four entries fuse under a zone with changes and equal Spark on rows that straddle both changes of a year; and a batch that spans more changes than its kernel's form declines by name | `m10/SCOPE.md` items 31 and 89; rows 682, 683, 656, 678 and 655 first, each form from row 679's | large |
| 658 | The fan-out task: its template, generator, oracle and ledger. **Planned** (`VARKA-658.md`, 27 November 2026: five parts, the cards and the scope check first, then the template and the generator, the oracle, the ledger, the guide; one tool, `dev/varka_fanout.py`; the pilots are #1584 and #1434, whose 13 and 23 files the generated plans name, through a table of the paths that have moved since; filed 25 November 2026; the admission check: `sql/varka/ADDING_AN_EXPRESSION.md` describes milestone 4's int-lane engine, and no command says of one entry and one type whether the work is done) Section 2.7. **Done when** a plan generated for a merged entry names every file that entry's pull request touched; the oracle passes on that entry and fails with its kernel reverted; the oracle's time on the laptop is measured; the ledger has its columns and one line; and the guide describes today's tree | owner's decisions 3, 4 and 10; rows 642 and 646 and row 653's layout first | medium |
| 659 | The pilot: each cheaper model builds a templated task. **Filed** (25 November 2026; the admission check: the four casts from `TIME` to the integral types are legal, unfused and unclaimed in `m8/casts.tsv`) Section 2.7: one model first, then the other two, and the admission rule. **Done when** each model's pull request is in the ledger with its review; the admission rule's verdict for each model is recorded; the four casts are fused; and what the pilot taught is in the template and in `skills/working-in-this-repo.md` | owner's decisions 3, 4 and 12; row 658 first | medium |
| 660 | How precisions 7 to 9 enter the census. **Done** (`VARKA-660.md`, 27 November 2026: a design with three choices and their counts; the owner chose (b) the same day, two census types, `ntz_ns` and `ltz_ns`, with the precision a band on `timestampadd` and on six cast pairs, and chose that the seven date functions that take a `DATE` count over them: 137 (entry, type) pairs and 20 cast pairs; row 661 builds it from the plan's section 6; filed 25 November 2026; the admission check: the precision is in the type, the value's layout is one for all three, and a cast to a lower precision floors) Section 2.5: a design, no code. **Done when** the plan states the census types and the cast pairs each choice gives, with their counts, and the owner has chosen | owner's decision 7 | small |
| 661 | The census and the cast matrix with the nanosecond types. **Planned** (`VARKA-661.md`, 27 November 2026: four parts, the two types in the registry and the cast matrix, then Spark's answers as a table that a suite writes from the analyzer and its check with the flag set on, then the census admitting the types from that table, then the band of the precision; filed 25 November 2026; the admission check: the registry holds neither type, and Spark's set of functions over them moved in six commits since May) Section 2.4. **Done when** the two types are census types as row 660 decides; each entry's admission is read from Spark's own type check with the flag on; an entry Spark rejects carries that reason; and the cast table holds the nanosecond pairs | owner's decision 1; rows 654, 660 and 663 first | medium |
| 662 | The nanosecond timestamps' layout. **Planned** (`VARKA-662.md`, 28 November 2026: a pilot and a study of four representations in hand loops, their cost table, their conversions and guards proved, and a rule, in eleven parts; the cache's struct and the kernels are row 699; filed 25 November 2026; the admission check: the cache stores the value as a struct of two vectors, and `isArrowBacked` admits lane vectors only) Section 2.5. **Done when**, by the owner's word of 28 November 2026 that the representation be measured for vectors before it is built: the candidate representations (Spark's two parts, one long of nanoseconds, seconds with nanoseconds, days with nanoseconds) are measured in hand loops, each held to Spark's methods; the numbers are a cost table, each conversion an equality with its range, proved; and a rule names each class's default representation by host and width, among those row 699 builds | owner's decision 1; no row first | large |
| 663 | Sync with apache/spark's master. **Done** (#1773, 27 November 2026: apache/spark `d1286ba2c16`, 368 commits, merged in one commit; the census names `try_abs`, `try_negative` and `farm_fingerprint` curated with the owner's word of 27 November, the first two unfused until row 672, the third excluded; row 672 already names the three interval-division declines; the merge also needed `next_day` and `trunc` to rest on the date column bound, as SPARK-60929 makes them raise, and a second pull request, #1774, gives the fork's Spark shards more time) **Filed** earlier (25 November 2026; the admission check: 345 commits behind, SPARK-61538 not an ancestor of master, and a trial merge fails the census on three names) Section 2.8. **Done when** master holds apache/spark's master of the sync's day; the census passes with each new name curated in or out with its reason; and the three interval-division declines SPARK-61352 ends are named in row 672 | the house's sync; #1708 and #1675 first | medium |
| 664 | A year-month `time_bucket`. **Filed** (25 November 2026, from the catalogue) The item's text and its done-when, over `TIMESTAMP_NTZ`, and over `TIMESTAMP_LTZ` once row 657 lands | `m10/SCOPE.md` item 117 | small |
| 665 | `TryEval` over a fused subtree. **Filed** (25 November 2026, from the catalogue) The item's rule and its done-when: each `try_` entry over an interval fuses, and a subtree with an inexact guard declines | `m10/SCOPE.md` item 125 | medium |
| 666 | `d + dt` over a stored `INTERVAL DAY` column. **Filed** (25 November 2026, from the catalogue) The item's text and its done-when | `m10/SCOPE.md` item 126 | small |
| 667 | Fan-out: `TIMESTAMP_LTZ`'s zone-free entries. **Filed** (25 November 2026) The 6 conversions with the epoch over microseconds, `hash` and `xxhash64` (after row 337), `known_not_null` and `try_eval` (*27 November 2026:* `in_set` is row 656's, which fuses it by the arm that fuses `IN`). **Done when** each entry is fused with a coverage row and a benchmark section, or carries its reason | owner's decision 3; rows 656 and 659 first | medium |
| 668 | Fan-out: `TIMESTAMP_LTZ`'s fields. **Filed** (25 November 2026) `year`, `quarter`, `month`, `day`, `dayofmonth`, `dayofyear`, `dayofweek`, `weekday`, `weekofyear`, `second`, `last_day`, `next_day`, `trunc`, `extract`, `date_part`, `datepart` and `time_bucket`, each the `TIMESTAMP_NTZ` kernel behind row 682's node. **Done when** each is fused with a coverage row, a test whose rows straddle both changes of a year and a benchmark section at G7's bar, or carries its reason | owner's decision 3; rows 657 and 659 first | medium |
| 669 | Fan-out: `TIMESTAMP_LTZ`'s casts. **Filed** (25 November 2026) The 16 pairs with a number or a boolean, which read no zone and wait for row 656 alone; then `DATE`, `TIMESTAMP_NTZ` and `TIME` in both directions behind the nodes of rows 682 and 690. **Done when** each of the 23 pairs is fused under each mode it admits, with its proof kind and its line in the cast table, or carries its reason | owner's decision 3; rows 656, 657, 690 and 659 first | medium |
| 670 | `TIMESTAMP_LTZ`'s zone functions. **Filed** (25 November 2026) `from_utc_timestamp`, `to_utc_timestamp` and `convert_timezone` over a literal zone, `date_trunc`, `months_between`, and `make_timestamp`, `make_timestamp_ltz` and their `try_` forms, at full strength. **Done when** each is fused with its tests across both changes and a benchmark section at G7's bar, or carries its reason; a zone column declines by name | owner's decisions 1 and 8; rows 657 and 690 first | large |
| 671 | `TIMESTAMP_LTZ`'s arithmetic. **Filed** (25 November 2026) `ts - ts`, `ts` plus and minus a day-time and a year-month interval, `timestampadd` and `datetime_sub`, at full strength: both directions of the conversion and the kept-offset rule. **Done when** each is fused with tests across both changes, in both signs, and a benchmark section at G7's bar | owner's decisions 1 and 8; rows 657 and 690 first | large |
| 672 | Fan-out: the existing temporal types' tail. **Filed** (25 November 2026; the admission check: 75 of the 278 temporal pairs are unfused, and 32 of the 42 temporal casts) The five always-null casts from `DATE`; the casts between the intervals and the integral and decimal types, after row 103's; `try_abs` and `try_negative` over the intervals, after row 663; `make_ym_interval` over int columns that no date gives (from the catalogue's item 39), a part built at full strength and not from a generated plan, since its admission check is an investigation: the form fuses over the ANSI overflow edge or declines with its reason; the three interval-division declines SPARK-61352 ends; and each pair milestone 8's close leaves with this row's name. **Done when** 1.2's item 3 holds | owner's decisions 1 and 6; row 659 first | large |
| 673 | Fan-out: the nanosecond fields. **Filed** (25 November 2026) The fields Spark accepts over the two types, on the microsecond word; a nanosecond `TIMESTAMP_LTZ` behind row 682's node. **Done when** each is fused with a coverage row and a benchmark section, or carries the reason that Spark rejects it | owner's decision 3; rows 662 and 699 first | medium |
| 674 | Fan-out: the nanosecond casts. **Filed** (25 November 2026) The pairs with `DATE`, `TIME`, the microsecond timestamps and each other, and the changes of precision. **Done when** each pair row 661 lists is fused or carries its reason | owner's decision 3; rows 662 and 699 first | medium |
| 675 | The nanosecond functions. **Filed** (25 November 2026) What Spark accepts over the two types at the last sync: `date_trunc`, `timestampadd`, `from_utc_timestamp`, `to_utc_timestamp`, `convert_timezone`, the `unix_*` functions and `timestamp_nanos`; the units below a microsecond and the sums that carry into the microsecond word at full strength. **Done when** each is fused or carries its reason | owner's decision 1; rows 662 and 699 first | medium |
| 676 | The post. **Filed** (25 November 2026) What of Spark's datetime surface runs in kernels, how it is known in every zone, how much faster it is than vanilla Spark, and what the cheaper models built, with its figures from committed results; it runs row 655's timestamp surface against the stock release once more, on the milestone's last kernels, after row 691's first run with the zone kernels (`VARKA-655.md` 4.10). **Done when** the surface's last run is committed and the post is published | owner's decision 5 | medium |
| 677 | The close. **Filed** (25 November 2026) Section 1.2 read item by item against the tree, G5's report from the ledger, and the house's close: documents only, its numbers quoted from CI's committed runs. **Done when** every item of 1.2 is answered and the milestone's tag is set | the house's close | small |
| 678 | The time-zone database for vector code. **Planned** (`VARKA-678.md`, 27 November 2026: four parts, the entry and the instant line, the local line, the cache with its measurement, the production zone data; filed 25 November 2026, on the owner's word of that day; the admission check: `java.time` answers one lookup at a time by a binary search, and the probe's sorted changes gave every row `java.time`'s answer under each of the three rules) Section 2.2: a zone's class, its changes on both time lines with each gap's and overlap's window, the instants from which its offsets are whole minutes and whole hours, one cycle of 400 rule years written out when the entry is built, and the changes of 1900 to 2100 that row 686 packs into the bucket table, which answers `VarkaZoneData.toLocalTable` (`VARKA-686.md` 4.5); built once a zone in each JVM, from that JVM's `ZoneRules`; ids with the same rules share an entry. **Done when** every zone id the session accepts has an entry; the steps read from it equal `java.time` over the sweep's instants on both time lines, and the table packed from it equals the reference's (row 686's part a holds that test); the time to build an entry and its size are measured and recorded; and no entry crosses from the driver to an executor | the owner's word of 25 November 2026; `m10/SCOPE.md` item 89; row 682's part c before its last part; row 679 ports its merged files into the library | medium |
| 679 | `vechrono`, a library of its own for the batch forms of `java.time`. **Planned** (`VARKA-679.md`, 27 November 2026: a founding commit, eighteen parts of the library and one of Varka; nanoseconds are its unit from the first release; it starts after milestone 8 closes, on the owner's word; filed 25 November 2026, the owner's decisions 13 and 16; the admission check: none of the eleven engines read converts a named zone in vector code, and vfdlibm is on Maven Central and read by `sql/catalyst` in test scope) Section 2.10: its repository; the zone database, the offset forms and the three rules first, from the probes of 2.2; then `plusNanos`, the first form over both words of a nanosecond value; then the fields and the sums as their rows come, each in both shapes. **Done when** the repository builds and tests in its own CI; the every-zone sweep and the forms' benchmarks at each vector width are in it; a release is on Maven Central; and a Varka suite checks a kernel against it in test scope | owner's decision 13; vfdlibm's precedent | medium |
| 680 | Never slower than Spark: a gate at each width. **Filed** (25 November 2026, decision 14, from the catalogue's item 120; the admission check: no benchmark class reads `coverage.json` and no nightly times a coverage row against the row path, and the item's case, `(d < d2) AND (l < l2)` at 0.8X at 128 bits, was found because a filter control happened to time it) One benchmark class driven by `coverage.json` times each coverage row's kernel against the row path, nightly, at 128 and 256 bits on the EPYC 7763, and fails on a row under 1.0X by more than the band its own reruns give, unless a committed list holds the row with the task row that owns it and the ratio it had; a listed row fails again only when it falls further. The laptop's widths stay with the catalogue's item. **Done when** the nightly runs and its results are committed for each width; each row of the temporal families under 1.0X is made faster or declined by a recorded refusal (G7); every other row under 1.0X is on the list with its owner; and a planted slow row fails the gate | owner's decisions 8 and 14 | medium |
| 681 | A register of the upstream behaviours Varka depends on or works around. **Filed** (25 November 2026, decision 14, from the catalogue's item 123; the admission check: no such file exists, and 2.4 leans on it) One file, each entry with the version that has the behaviour, the upstream issue, the Varka code that depends on it and the test that pins it. The row builds it with the item's four entries, whose tests exist. This milestone's entries come with the rows that build their tests: SPARK-61352 and SPARK-61538 with row 663, the JDK's time-zone data with row 657, the nanosecond preview (SPARK-56822) and its rejections (SPARK-60335) with rows 661 and 662, and SPARK-57853 with item 50 if it is taken. **Done when** the register exists, each of the four entries names its pinning test, and the steps of the upstream sync and of a JDK upgrade read it; the close (row 677) checks that each later entry is there | owner's decision 14 | small |
| 682 | `ToLocal` and its batch arguments. **Planned** (`VARKA-682.md`, 26 November 2026: six parts, the record with its registries and the one-offset lowering first, the runner's fill fifth; filed 25 November 2026 from `VARKA-657.md`; the admission check: a kernel's long arguments are a task's own and are passed on every call, and the shape key holds the literals' count and the options, not a value) `VARKA-657.md` 3.1, 3.2 and 3.5: the record under a sealed `Zoned` interface with its registries; the layout of batch arguments from the final IR and the options, read by the emitter and by the runner; the runner's fill, its status, and the warm-up and the reproduction following a batch's values; the lowering of one offset and of the steps, the form an emit option. **Done when** a kernel over a test zone answers two batches with two sets of values from one class, two tasks do not see each other's values, each form's bytes are pinned, and no literal slot moves | `VARKA-657.md`; the catalogue's item 11; row 683 first, for the runner's fill | medium |
| 683 | The range of a long column. **Planned** (`VARKA-683.md`, 26 November 2026: one part, the pass with a loop for hosts that compile a mask and one with no mask, two suites and a benchmark class; filed 25 November 2026 from `VARKA-657.md`; the admission check: `IntRangeOps.allWithin` checks an int interval, and nothing returns a least or a greatest value) `VARKA-657.md` 3.3: `LongRangeOps.leastAndGreatest`, null rows skipped by the validity words, with a benchmark section. **Done when** it equals a scalar loop over random columns, null patterns, lengths around a vector and the long extremes at both widths, and its cost a row is recorded | `VARKA-657.md` | small |
| 684 | The proof of the zone kernels. **Planned** (`VARKA-684.md`, 26 November 2026: three parts, the sweep, then the proof file and the planted faults, then the fuzzers; filed 25 November 2026 from `VARKA-657.md`) `VARKA-657.md` 5, for `ToLocal`: `VarkaZoneSweepSuite` over every distinct set of rules, with the metamorphic checks; `zone_step.smt2` and the planted faults of `ToLocal` and of the batch arguments; the session zone as a drawn setting of the Spark fuzzer, zone nodes in the IR fuzzer's grammar, and the property suite. **Done when** the sweep passes in every Build for `ToLocal` inside its time budget, each of its planted faults fails a named test, and both fuzzers draw a zone | `VARKA-657.md`; row 657 first | medium |
| 685 | A kernel for each batch. **Planned** (`VARKA-685.md`, 26 November 2026: the list of kernels, the range pass once a batch, the ladder, the measurement, and a fifth part that row 691's numbers gate; filed 25 November 2026 from `VARKA-657.md`) `VARKA-657.md` 3.5: the evaluator owns a list of kernels by form, a refactoring that moves no emitted byte; then the ladder, the least form that covers a batch's range; last, `second` and `minute` with no offset, if row 691's numbers show a gain. **Done when** a year of instants in no order under `America/Los_Angeles` declines no batch, each batch runs the least form that covers it, and no batch runs twice | `VARKA-657.md`; row 657 first | medium |
| 686 | The table form of a zone conversion. **Planned** (`VARKA-686.md`, 26 November 2026: four parts, the bucket with its data and its proof, then the kernel, then the ladder's last form and the sweep, then the measurement; filed 25 November 2026 from `VARKA-657.md`) `VARKA-657.md` 2.8 and 3.5: the lowering with a packed bucket of 16 bytes and a clamped index, `zone_bucket.smt2`, and the form as the ladder's last. **Done when** ten years of instants in no order decline no batch, a null row's storage outside the table reads no memory outside it, and the packed table is measured against three gathers | `VARKA-657.md`; rows 682, 683 and 657 with row 678 first; row 684 before parts b and c; row 685 before part c | medium |
| 687 | The cache's statistics reach the evaluator. **Planned** (`VARKA-687.md`, 26 November 2026: two parts, the range carried on the scan's vector and read in place of the pass, then the measurement; filed 25 November 2026 from `VARKA-657.md`; the admission check: `ArrowCachedBatchToColumnarBatchIterator.next` drops them) `VARKA-657.md` 2.6 and 3.3: a cached batch's least and greatest value handed to the evaluator, in place of the pass. **Done when** a zoned kernel over a cached column runs no pass, and the difference is measured | `VARKA-657.md`; the catalogue's items 15 and 39; row 657 first | small |
| 688 | The range of a computed operand for each batch. **Planned** (`VARKA-688.md`, 26 November 2026: six parts, a range program evaluated once a batch with no allocation, the fill, the first kernel over a computed timestamp, the range of an int column, the table form over a computed operand, the results; filed 25 November 2026 from `VARKA-657.md`) `VARKA-657.md` 3.3: interval arithmetic over the ranges of the batch's columns, with the transfer functions of `VarkaRangeAnalysis` and one for each zone node. **Done when** `hour(ts + INTERVAL '1' HOUR)` fuses under a zone with changes and declines no batch that its form covers | `VARKA-657.md`; row 657 first, and row 685's first three parts; its table part needs row 686 | medium |
| 689 | Zone steps at two lanes. **Planned** (`VARKA-689.md`, 27 November 2026: four parts, the option `zoneStepMask` with `ToLocal`'s steps and the span rule, the three local rules' steps, the record of what a two-lane host refuses with its tool, the reading and the verdicts; filed 25 November 2026, from `VARKA-657.md`; the admission check: at two 64-bit lanes this JVM compiles no mask, and in the probe the masked steps run as Java loops there) `VARKA-657.md` 3.8: each step as a subtract, a shift and an AND, for `ToLocal` and for the three rules of a local time to an instant, whose records and masked steps are row 690's (`VARKA-690.md`, appendix A, holds the design notes), an option arm whose default follows the host; a recorded refusal where a form is slower than the row path; and a decline of a batch whose span does not fit a long, where the step with no mask is not exact. **Done when** at 128 bits each zoned kernel is faster than the row path or declines by a recorded refusal, and a batch of a wide span declines | `VARKA-657.md`; decision 15; row 657 first, row 684's proof file, row 690's part b for the span rule's view, its part c for the local rules and its parts d to f for the record; row 655's classes for the reading | small to medium |
| 690 | A local time to an instant: `ToInstant` and `ToInstantKept`. **Planned** (`VARKA-690.md`, 27 November 2026: nine parts, the two records with their one-offset kernels and the fill on the local line, the masked steps of the three rules, the three first kernels, the sweep under three rules, the fuzzers' draws, the results; the steps with no mask are row 689's; filed 25 November 2026, from `VARKA-657.md`; the admission check: one pair of local thresholds a change equals `java.time` under its three rules in the probe, over the 604 zone ids with no mismatch) `VARKA-657.md` 2.4 and 3.7: the records and their registries; `CAST(ntz AS TIMESTAMP)` under the earlier offset; `CAST(d AS TIMESTAMP)` under the gap's end; `ts + INTERVAL DAY` under the kept offset; each rule in the sweep with its kernel, its planted faults and the there-and-back check. **Done when** the first kernel of each rule fuses with tests whose rows straddle both changes of a year, the sweep passes in every Build under the three rules, and each planted fault of a rule fails a named test | `VARKA-657.md`; row 657 first; row 688's parts a to d for the last two kernels; row 684 for the sweep's parts | large |
| 691 | The zone kernels measured. **Filed** (25 November 2026, from `VARKA-657.md`) `VARKA-657.md` 6: each form in a kernel on row 655's classes at 256 and 128 bits, the A/B arms, the default of each option at each width, G7's first reading, the first run of row 655's timestamp surface with the zone kernels on against the stock release (`VARKA-655.md` 4.10: the entries that fuse turned in `Timestamps`, the six arms at 256 and 128 bits), and the family's outcome. **Done when** each form of `VARKA-657.md` 3.5 is measured in a kernel, the default at each width is the fastest, G7's bar holds for `hour` and for the cast to `DATE`, or the miss is recorded with its lever, the surface's twelve files with the kernels on are committed beside row 655's unfused ones, and the outcome in `VARKA-657.md` is written | `VARKA-657.md`; rows 685 to 690 first | small |
| 692 | A zone conversion shared across method groups. **Filed** (25 November 2026, from `VARKA-657.md`; the admission check: a shared node is computed again in each group, and only the calendar prefix has a store and a load, VARKA-198) `VARKA-657.md` 3.9: three fields of one timestamp in one group and in two, measured; the store and load for a zone node only if it pays. **Done when** the measurement is recorded and the choice it gives is built, or declined by name | `VARKA-657.md`; row 691 first | small |
| 693 | The table form of a local time. **Filed** (26 November 2026, on the owner's word of that day, from `VARKA-686.md` 4.9; the admission check: row 686's table is of the instant line alone, and `ToInstant` under `TABLE` has no arguments) The bucket table of row 686 on the local time line, for `ToInstant` and `ToInstantKept`: its data, its lowering and the form in the ladder for a kernel that holds either record. **Done when** the casts to `TIMESTAMP` and `ts + INTERVAL DAY` decline no batch over ten years of values in no order, and the sweep passes with the table under the three rules | `VARKA-686.md` 4.9; G2's item 2; rows 686 and 690 first | medium |
| 694 | The Varka agent index split behind pointers. **Moved to milestone 8** (28 November 2026, by the owner's word of that day: agents must be able to start a task from the repository before this milestone builds, so the row is built in `m8/PLAN.md`, which holds it with the sections the cold-start audit asked for). **Filed** (27 November 2026, from the upstream sync #1773; the admission check: upstream's agent-docs lint fails with `codex-cap` because the chain that ends at `sql/varka/AGENTS.md` is 40,096 bytes and Codex reads 32,768, so 7,328 bytes are never seen; `dev/agent_docs_lint_exceptions.txt` exempts the file until this row lands) Move the branch-only detail of `sql/varka/AGENTS.md` (33,018 bytes against a target of 4,096) into files under `sql/varka/.agents/`, each behind a pointer that says when to read it, as upstream's index files do, and delete the exemption. **Done when** `dev/agent_docs_lint.py --strict` passes with no exemption and every rule the file holds today is reachable from a pointer | upstream sync #1773; `dev/agent_docs_lint.py` | medium |
| 695 | The width audit's cost, cut to what the census needs. **Filed** (27 November 2026, from #1777's two failed runs of `Varka suites: catalyst 1 of 3` and #1773's first Build; the admission check: on the laptop pinned to four cores, one probe at a time, the five probes took 191, 191, 292, 210 and 191 s, against 92 to 115 s for the first four-core reading of 9 November, so the audit costs about twice what it did before the second phase of `VARKA-550` audited each shape's epilogue; on a GitHub runner with four processors (the Build of #1774, run 45988077035) they took 247, 240, 350, 257 and 237 s, and the runner's preferred width is 256 bits, so the preferred, the preferred-again, the 256-bit and the 512-bit probe, which the host caps to 256, are one run done four times, 734 of the 1,331 s; the failed runs of #1777 passed the 600 s budget with a probe at 128 bits, about 1.7 times the 350 s of this run; #1774 raises the budget to 1200 s and prints each probe's time, and this row ends the need to keep raising it) In two steps. First, run each distinct width once: skip a forced probe whose width the host caps to one that ran, and run the preferred-again probe, which exists for the test that two censuses of one width agree, only when the census is regenerated and not on every CI run; this takes the audit on a 256-bit runner from five probes to two and keeps every width's refusals in `sql/varka/width_audit.json`. Second, if the 128-bit probe, which the first step leaves at about 350 s, is still above 300 s, run the epilogue phase of the audit at the preferred width only, or split the probes over CI jobs, whichever the probe times in the Build's log show saves more, and say in the census which width its epilogue column no longer covers. **Done when** the slowest probe on a four-core runner takes under 300 s, half the budget, with the pinned census unchanged or its change named, and no probe of the width audit has passed 600 s for a week | `VARKA-550.md` second phase; #1774 for the probe times; `m8/results/VARKA-581-probe-times.txt` | medium |
| 696 | A census claim, a cast pair's probe lines and the census's rendered files, one file an entry. **Filed** (27 November 2026, split from row 653 by the owner's word; the admission check, from `VARKA-653.md` 3 and 4.9: after row 653 two branches still conflict in `census_curated.txt` and `census.tsv` where they claim one entry or neighbouring entries (1 of 14 and 1 of 16 consecutive row-adding commits that both changed them; 2 and 5 of 194 replayed merges that conflict), three sibling casts would on neighbouring lines of the cast tables (0 of 3 measured), and round 2 of `dev/varka_rows_demo.py` shows three siblings in conflict in the two census files) `VARKA-653.md` appendix A holds design notes that bind no one. The row edits `dev/varka_rows_demo.py`: a cast among round 2's five branches, and round 2 deciding the exit status with round 1. **Done when** round 2 of that script prints no file in conflict or stale, with a cast among its branches; a newly fused type for an entry is a file of its own and no edit of the curated file; no row-adding branch changes a line of `census.tsv`, `casts.tsv`, `casts_fused.tsv` or `casts_suites.tsv` that another row-adding branch changes; and every reader of those five files gives the same output before and after | the owner's word, 27 November 2026; `VARKA-653.md` 4.9 and appendix A; rows 654 (its parts a and b built), 656 (its part B) and 653 first | medium |
| 698 | A two-word load and store with no mask at two long lanes, as a spike. **Filed** (28 November 2026, by the owner's word of that day, from the wide sum's spike of `m8/PLAN.md` row 360; the admission check: `x86.ad` of JDK 25 returns false for `VectorLoadMask`, `VectorMaskCast` and `VectorStoreMask` where `vlen == 2`, so at two 64-bit lanes on x86 C2 lowers no mask and the Vector API runs its Java fallback, and `sql/varka/skills/vector-api-and-width.md` lists the two-vector `rearrange`, `selectFrom` and `compress` among the calls that fall back there while a lane read, a lane insert at a constant index, the shifts, AND, OR and an OR reduction are intrinsic; every vector loop of `VarkaDecimalPairSpikeBenchmark` loads a pair's words by a two-vector `rearrange`, the carry form with no compare included, and in that spike's laptop reading (pull request #1799, not merged on this day) both vector forms lose to the scalar loop at 128 bits; whether the loss is the load alone was read from the source and not from the assembly) Spike loops for a pair's load and store at two long lanes that call nothing of that list, by a lane read and a lane insert and by the forms the spike's builder finds, each held to the scalar reference, with the wide sum and one comparison built on the load that wins; read on the laptop and the EPYC 7763 under `-XX:MaxVectorSize=16` and on the Arm runner, whose own back end refuses none of these calls. It answers whether a two-word value can win over the row path at 128 bits on x86 at all, for the decimal pair and for row 662's two-word timestamps, before a kernel form is built there; a form that loses stays declined by the two-lane refusal, which row 597 of `m8/PLAN.md`, moved to `m10/SCOPE.md` on 27 November 2026, would extend to value kernels. **Done when** the 128-bit assembly of the spike's loops shows no call of the fallback, the results file holds each load form against the scalar loop and the two-vector `rearrange` at 128 bits on the three hosts, and the row records, by the house's band of 1.3, whether a two-lane pair kernel is worth a row | the owner's word, 28 November 2026; `m8/PLAN.md` row 360 and its spike; `m7/VARKA-374.md`; rows 662 and 689 | small |
| 699 | The nanosecond timestamps' storage and kernels over the study's forms. **Filed** (28 November 2026, from `VARKA-662.md` 4.11 and 4.12; the admission check: row 662 measures the representations in hand loops and builds nothing a kernel runs; *28 November 2026, the owner's answers to `VARKA-662.md` section 15:* all three whole-range representations are built whatever the numbers, one long of nanoseconds where it is the fastest, a scalar body where no vector form wins, and a column that arrives as epoch nanoseconds is admitted) The cache's struct in and out, the null tests and `coalesce`, the two conformance suites and the first census claims; the conversions among Spark's two parts, seconds with nanoseconds and days with nanoseconds as IR nodes with their range conditions and guards; the comparisons, `IN`, `between`, the choices, the hashes, the cast to microseconds, `hour` and `second` with its fraction in all three, each class defaulting by host and width to the form row 662's rule names, the AVX-512 readings first where one default is named for the project; the kernels in one long of nanoseconds for the classes where the rule names it the default, each with its batch guard, a batch that holds a value outside 1677 to 2262 going to the row path; a kernel whose body is a scalar loop where the rule's default is a scalar body, read after row 698's verdict, since a mask-free two-lane form that wins there replaces it; a column that arrives as epoch nanoseconds admitted as it is, with the form's range; the option that names the form; the forms measured on row 655's nanosecond classes. Planned after row 662's part T2b and after row 661 is built. **Done when** a nanosecond column is read from the cache's struct and written back; the zone-free entries fuse or decline by name; a field over the microsecond word, a cast to microseconds and `second` with its fraction fuse; each of the three representations is measured on row 655's classes at 256 and 128 bits, and the one long where it is built, each built form that is not a class's default kept as an option arm; a class whose default is a scalar body runs that body inside a kernel and answers as the vector kernels do; a batch outside the one long's range answers through the row path; and the values at the range's ends answer as `TimestampNanosVal` does | rows 662, 661 and 653 first; row 655's parts F and G; row 698's verdict for 128 bits; `VARKA-662.md` 4.10 to 4.12 and 19 | large |

### 3.1 Goals to rows

| goal | rows |
| :--- | :--- |
| G1 `TIMESTAMP_LTZ` | 654, 656, 678, 657, 682, 683, 688, 690, 667 to 671 |
| G2 every zone | 679, 678, 657, 684, 686, 693, and the tests of 668 to 671 |
| G3 remaining features | 30, 95, 103, 351, 364, 366, 664 to 666, 672, and 681 for the upstream they wait on |
| G4 nanoseconds | 660 to 662, 673 to 675, 681 and 699 |
| G5 cheaper models | 653, 696, 658, 659, 590, the fan-out rows (667 to 669 and 672 to 674), the library's functions (679), and 642 and 646 under them |
| G6 the post | 676 |
| G7 speed | 655, 678, 657, 685 to 687, 689, 691, 692, 680, and a benchmark section in every `TIMESTAMP_LTZ` row |

### 3.2 What the carried rows hold

Rows 30, 95, 103, 351, 364 and 366 are mostly built: 31 local branches from 20 to 23 November
2026 hold their parts, each named for its row and part (`varka-30a1-r` to `varka-366a3-fuzz-r`).
The statuses of rows 30, 95 and 103 name their parts; those of rows 351, 364 and 366 still read
Planned, and each takes its parts with its first pull request. Landing a part is a merge of
master, a review, a push and CI, in the merge queue's order; the parts over the 128-bit decimal
(351's last, 364 and 366) wait for milestone 8's rows 360 to 363.

*27 November 2026:* five more rows came from milestone 8 with its close path (`m8/PLAN.md` 12.5),
each with its number, and its state and record still in `m8/PLAN.md`: 577 and 604, the decimal
remainder and its two-lane option (577a waits on row 30; 577b, and 604's part 2 with it, on
milestone 8's rows 359 to 363); 579, the fuzzer's calendar node over a narrowed long; 609, the
guard in an `IfElse` condition that row 103's build found; and 646, one file a plan row, which 1.4
already lists before the fan-out. Their plans stay in `m8/`. Four are built in part on local
branches, kept unmerged: `varka-577b2-remainder-arm-r` (8 commits, 22 November 2026),
`varka-577-pair-remainder` (2 commits, 20 November 2026), `varka-604c-remainder-option` (19
commits, 20 November 2026), `varka-579-fuzz-calendar-narrow` and `varka-609-ifelse-guard-arms` (5
commits each, 23 November 2026). Row 646 has no branch.

## 4. Order

*25 November 2026, the owner, after this plan merged (decision 18): only milestone 8's tasks run
now. Of wave 0 the plans and the catalogue's move go on; a row of this plan starts on his word.*

**Wave 0, now, while milestone 8 runs.** Nothing here adds a type to the census. This plan; the
catalogue's move; rows 646 and 642, which are milestone 8's throughput work too; the plans of rows
653, 657 and 660; row 678, which is new classes and their tests (the library, row 679, starts after
milestone 8 closes, on the owner's word of 27 November 2026, and ports row 678's merged files); row
655's `TIMESTAMP_LTZ` classes, the second run of the `TIMESTAMP_NTZ` ones and its timestamp
surface (`VARKA-655.md`, parts A to E); the carried rows' built parts; rows 590 and 681, which
move no census cell; row 663 once #1708 and #1675 have merged.
The carried parts and the sync move cells of milestone 8's census while it is open, as its own rows
do; its note of 25 November 2026 says how its close reads them.

**Wave 1, from milestone 8's close** (decision 11): the census opens to the new type. Rows 654
and 656; row 653's code, its parts that move a list after row 656's part B
(`VARKA-653.md` 12); row 658; and row 680, whose findings before the close would fall on
rows milestone 8 is still building.

**Wave 2.** Rows 682 and 683, then row 657, and behind it rows 684 to 690; the pilot (row
659); then the fan-out that reads no zone: row 672, row 667 and the first 16 pairs of row 669.
Row 696 (the census's files, split from row 653), behind rows 654, 656 and 653, and before
the pilot's second round where it is ready by then.

**Wave 3.** Behind the zone: the fan-out rows 668 and 669, and the full-strength rows 670, 671
and 664 to 666; rows 669 to 671 wait for row 690. Rows 691 and 692, the zone's measurement, and row 693 after row 690.

**Wave 4, phase 2's foundation, beside wave 3.** Rows 660, 661, 655's nanosecond classes, 662.

**Wave 5.** Rows 673 to 675.

**Wave 6.** Rows 676 and 677.

**The paths that set the length.** Zones: milestone 8's close, then 663, 654, 656, with 679
and 678 beside them, then 682, 657 and 690, then 668 to 671. The cheaper models' machinery: 646,
642 and 653, then 658, 659, then every fan-out row. Nanoseconds: 660, 661, 662, then 673 to 675. The
carried decimals: milestone 8's 128-bit rows, then 351, 364 and 366.

**The length.** About 130 pull requests and about seven working weeks from the sync's merge
(section 6), at the rate of 9 to 25 November 2026, when the last 200 merges took 17 days and 37
of them were plans, and with milestone 8's tail sharing the slots. Decision 14's five additions
are about eight pull requests more; prediction 1 of section 6 stays as registered and is scored
so.

## 5. Risks

- **Milestone 8's close slips.** It has 102 open rows. Wave 0 does not wait for it; every later
  wave does (decision 11), so a late close moves the whole length by as much.
- **The per-batch slot is a new kind of kernel argument,** and a pass before the kernel has cost
  10% to 17% before. The row's plan comes first, and "steps, carried" has no pass.
- **Two 64-bit lanes.** This JVM compiles no mask there, and the probe's masked steps ran slower
  than `java.time`. A zone kernel takes the form with no mask at that width or declines, and
  G7's 128-bit bar is read with that.
- **A kernel that declines.** A form that sends most batches to the row engine meets the census
  and fails G2 and G7; both name the fixture that shows it.
- **One laptop.** The cheaper models, the coordinator's regenerations and the owner's own rows
  share one heavy JVM at a time (decision 10). The oracle's time a task is the fan-out's rate;
  the pilot measures it before the fan-out is sized.
- **The cheaper models fail the pilot.** The generated plans and the oracle still pay: the
  coordinator builds the same tasks at less cost, and G5 has no threshold.
- **Row 653 is late.** Then the fan-out runs one pull request at a time, each merge costing the
  others a regeneration.
- **Spark's nanosecond surface moves.** Row 661 reads Spark at the last sync; one sync is fixed
  before wave 5.
- **The first column of two Arrow vectors.** Row 662 leans on row 359's two words; if that
  path does not carry a struct, the row grows.
- **The review's load.** About 50 pull requests from the cheaper models, each with one review;
  the findings go on the pull request so that a model can act on them.
- **Time-zone data differs** between CI's JDK and an executor's. Offsets are read on the
  executor, and the sweep names its JDK.
- **A library of its own** (decision 13) is a second repository to keep. vfdlibm's rule is the
  control: a port here is paired with the pull request there, and nothing here waits on a
  release.
- **The gate finds losers outside the datetime families.** Row 680 puts each on its committed
  list with the row that owns it and fixes none of them here; the gate then fails on a new
  loser or a worse one only, so a `TIMESTAMP_LTZ` row that loses is not hidden among them.
- **A benchmark's runner.** Every G7 number needs an EPYC 7763; a run on another CPU is
  dispatched again, as the house does.

## 6. Predictions, registered 25 November 2026

Scored at the close, each against the record.

1. The milestone takes about 130 pull requests, between 100 and 160: the carried rows about 30,
   the foundation and the shared files about 35, the fan-out rows about 35, the full-strength
   datetime rows about 25, the close 3.
2. Row 656 lands in one plan and at most two code pull requests, as row 338 did.
3. One offset a batch declines every batch of a year of instants in no order in a
   daylight-saving zone, and none under UTC. This is arithmetic, and the measurement is its
   check. *Scored the same day by the probe of 2.2, which ran after this was written: 1000 of
   1000 batches span a change, at 4096 rows and at 10000.*
4. Over such instants the fields run at least ten times the row path at 256 bits on the EPYC
   7763, twice G7's bar: the row path pays `java.time`'s zone lookup for every row on top of
   what a `TIMESTAMP_NTZ` field costs it, and the kernel adds a few compares.
5. The fastest form at two changes a batch is "steps, carried"; the table wins only past eight.
   *Scored the same day on the laptop: right at four lanes, where the table wins past about nine
   changes; at eight lanes it wins only past about twenty. The runner's score is row 691's.*
6. Both nanosecond layouts run the fields at the same rate, and the single long wins only on
   the arithmetic.
7. The pilot's first pull request merges within two fix rounds, and not with no finding.
8. The cheaper models build at least half of the fan-out's pull requests.
9. The close is seven working weeks after the sync's merge, between five and ten.
