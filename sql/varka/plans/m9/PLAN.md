# Varka Milestone 9 Plan: every timestamp, in every time zone

*Opened 25 November 2026, while milestone 8 runs. The owner set the theme the same day:
`TIMESTAMP_LTZ` at precision 6 to 9, `TIMESTAMP_NTZ` with nanoseconds, and the remaining features
of the ANSI intervals, `TIME`, and `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ` with `DATE`. The decisions
in section 1 are his and fixed; this plan works inside them.*

This is a task plan, not a scope catalogue. Under the house rule (`sql/varka/AGENTS.md`), the
catalogue that sits in this folder (`SCOPE.md`, with its reading note and the plans of the rows it
holds) moves forward to `m10/` by `dev/varka_catalogue_move.sh 9 10`, with its item numbers
unchanged and every inbound reference rewritten. By the owner's choice of 25 November 2026 that
move is the next pull request, on its own, since its mechanical rewrite alone is over the size
cap; until it merges, `m9/SCOPE.md` below is the catalogue in this folder. The same pull request
moves the plans of the six rows this milestone takes from milestone 8 (section 2) into this
folder, as the house rule asks of a plan whose row changes milestone. This pull request
holds the goals; the foundation's rows, their designs and the fan-out's task template follow in
their own pull requests, as section 2 says.

## 1. The question, and what "done" means

Can Varka answer Spark's datetime surface - every timestamp kind Spark has, in every session time
zone, and the interval, `TIME` and `DATE` expressions milestone 8 leaves - bit for bit with
Spark? And can most of that work be done by cheaper models from written task plans?

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
   2). Rows 30, 95, 364 and 366 are not datetime rows; they come as milestone 8's carry-over.
7. How precisions 7, 8 and 9 enter the census (one type per kind with a precision band, as
   `DECIMAL` has, or one type per precision) is designed, not assumed: row F2's design answers it.

### 1.1 Goals

- **G1. `TIMESTAMP_LTZ` at microseconds (phase 1).** It is a census type. The entries that read
  no time zone - choice, null tests, comparisons, `IN`, `greatest`, `least` and the hashes - fuse
  over it as they do over `TIMESTAMP_NTZ`. Under the session time zone, the calendar and clock
  fields, `date_trunc`, `months_between`, `from_utc_timestamp`, `to_utc_timestamp`,
  `convert_timezone`, `make_timestamp` and its forms, `timestamp_seconds` and its kin, the
  `unix_*` functions and the timestamp arithmetic fuse.
- **G2. Every time zone, proven.** A kernel's answer equals Spark's in every zone the JDK knows,
  at daylight-saving gaps and overlaps too, checked by a sweep that runs in CI.
- **G3. The remaining datetime features (phase 1).** The temporal pairs milestone 8 leaves
  unfused - interval `div`, `extract` over a day-time interval, `time_bucket` (its year-month
  width is `m9/SCOPE.md` item 117), `width_bucket`, the interval and `TIME` casts, the `try_`
  functions over intervals (item 125), and a `DATE` mixed with a timestamp; the one feature with
  no census row, `d + dt` over a stored interval column (item 126); and `TIME` plus or minus an
  interval without its range guard (item 50), a fused form that loses its guard once SPARK-57853
  merges upstream. The six rows carried over from milestone 8 (decision 6) are part of G3.
- **G4. Nanosecond `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ`, precision 7 to 9 (phase 2).** Admitted
  while Spark's `spark.sql.timestampNanosTypes.enabled` is on, as Spark itself admits them; the
  functions Spark accepts over them fuse, and the casts among `DATE`, `TIME`, the microsecond and
  the nanosecond timestamps.
- **G5. Delivery by cheaper models.** After the foundation, each fan-out task is one task plan
  from a template, with a one-command oracle; most fan-out pull requests are built by the cheaper
  models, each through the same review.
- **G6. The post.** What of Spark's datetime surface now runs in kernels, how that is known, and
  how much of it the cheaper models built.

### 1.2 Done when

Each item is read from `dev/varka_census.py --summary`, which row F1 extends to print the counts
per type; the ratios below are fixed, and the counts behind them are fixed when F1's census
holds the new types.

1. **G1:** at least three quarters of `TIMESTAMP_LTZ`'s (entry, type) pairs fused (about 68
   entries are expected to admit it).
2. **G2:** the every-zone sweep passes in CI, and every kernel that reads a time zone has a test
   whose rows straddle a daylight-saving change.
3. **G3:** at least 250 of the 278 (entry, type) pairs over `DATE`, `TIMESTAMP_NTZ`, `TIME` and
   the two interval types fused (203 on 25 November 2026), and at least two thirds of the
   temporal casts fused or declined with their reason: the 42 legal pairs of `m8/casts.tsv` with
   a temporal side, plus the new types' pairs F1 adds (about 43 more).
4. **G4:** at least three quarters of each nanosecond type's pairs fused under the flag.
5. The six carried-over rows (30, 95, 103, 351, 364, 366) meet their own done-when.
6. Every pair left unfused carries its reason in the census: a declined band, or a named row of
   the next milestone.
7. **G5:** the share of fan-out pull requests the cheaper models built, and their review finding
   counts, reported; no threshold.
8. **G6:** the post is published.

### 1.3 Out of scope

- The datetime functions that read or write a string (`date_format`, `to_char`,
  `from_unixtime`, `to_date`, `to_timestamp` and its forms, `dayname`, `monthname`,
  `unix_timestamp` of a string): the `STRING` decision stands.
- Casts to and from `STRING`; aggregates and window functions; the coverage spine (TPC-H q6 and
  q1), which moves with the catalogue to milestone 10.
- A function Spark rejects over the nanosecond types (today `ts - ts`, `months_between` and
  `timestampdiff`): Varka follows Spark as Spark's support moves.

### 1.4 Order

0. **Before the fan-out:** milestone 8 closes; the upstream sync (the fork is 345 commits behind
   apache/spark, and the sync brings SPARK-61538's nanosecond fix among others); the shared-file
   fixes (row 642, and F0: one file per plan row); the census and the cast matrix with
   `TIMESTAMP_LTZ` (F1); the task template and its oracle (F3); the pilot (F4). Milestone 8's
   open rows that wait on a carried row (340's `SECOND` as a decimal waits on 351; 349's
   narrowing casts wait on 364) close in milestone 8 with that part named as milestone 9's.
1. **Phase 1:** the six carried-over rows; the `TIMESTAMP_LTZ` lane and the time-zone design
   (F5, F6), built at full strength; then the fan-out over G1 and G3.
2. **Phase 2:** how precisions 7 to 9 enter the census (F2), then the census with the
   nanosecond types (F1b) and their layout (F7), built both ways and measured; then the fan-out
   over G4.

### 1.5 Defaults, until a row's design says otherwise

- **Time zones:** one offset a batch, the batch declining when it spans a daylight-saving change;
  a per-row table of the zone's transitions as a measured option arm.
- **The nanosecond layout:** the cache's pair of a microsecond long and a nanosecond short, and a
  long of nanoseconds, both built and measured.
- **The oracle:** Spark's row engine; `java.time` a second reference at the edges.

## 2. Tasks

The foundation rows below are named here and numbered, planned and designed in the pull request
that opens each; the fan-out rows come from F3's template once F1's census lists their entries.

| row | what it is | depends on |
| :--- | :--- | :--- |
| F0 | One file per plan row, so parallel rows stop conflicting in `PLAN.md` | - |
| F1 | The census and the cast matrix with `TIMESTAMP_LTZ`, and per-type counts in `--summary` | the upstream sync |
| F2 | How precisions 7 to 9 enter the census (owner's decision 7) | - |
| F1b | The census and the cast matrix with the nanosecond types | F1, F2 |
| F3 | The fan-out task template, self-contained, and its one-command oracle | F0, 642 |
| F4 | The pilot: one templated task built by each cheaper model, then reviewed | F3 |
| F5 | The `TIMESTAMP_LTZ` lane: Arrow, the type registry, load, compare, choice, output | F1 |
| F6 | The time-zone design, its every-zone sweep and its daylight-saving tests | F5 |
| F7 | The nanosecond layout, both arms, measured | F1b |

The rows this milestone takes, with their numbers and their plans where they are:

| task | what it is | where it came from | size |
| ---: | :--- | :--- | :--- |
| 30 | **Started** (carried over from milestone 8 on 25 November 2026; its state and record are `m8/PLAN.md` row 30 and its plan). The integral division family | milestone 8, `VARKA-30.md` | large |
| 95 | **Started** (carried over from milestone 8 on 25 November 2026; state and record: `m8/PLAN.md` row 95 and its plan). The checked int multiply over a column, and `i % k` | milestone 8, `VARKA-95.md` | medium |
| 103 | **Started** (carried over from milestone 8 on 25 November 2026; 103a1 merged as #1534; state and record: `m8/PLAN.md` row 103 and its plan). Day-time interval arithmetic, products, quotients, extracts and `make_dt_interval` | milestone 8, `VARKA-103.md` | medium |
| 351 | **Planned** (carried over from milestone 8 on 25 November 2026; state and record: `m8/PLAN.md` row 351 and its plan). Decimals in the existing types' functions, and the rounding functions | milestone 8, `VARKA-351.md` | large |
| 364 | **Planned** (carried over from milestone 8 on 25 November 2026; state and record: `m8/PLAN.md` row 364 and its plan). The 128-bit decimal casts | milestone 8, `VARKA-364.md` | medium |
| 366 | **Planned** (carried over from milestone 8 on 25 November 2026; state and record: `m8/PLAN.md` row 366 and its plan). The exact decimal-to-floating arm | milestone 8, `VARKA-366.md` | medium |
| 642 | **Planned** (`m9/SCOPE.md` item 138). Declare an emit option in one place, so parallel option rows stop conflicting | milestone 8 by item 138, `VARKA-642.md` | small to medium |
