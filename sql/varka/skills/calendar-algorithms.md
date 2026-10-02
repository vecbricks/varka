# Calendar algorithms

The date arithmetic itself: the papers behind it, the identities it rests on, and what neighbouring codebases do.

One of Varka's lesson files; the index over all of them is
[`SKILLS.md`](../../../SKILLS.md) at the repository root, which is generated from
these files by `dev/varka_toc.py`.

## Reading a paper into the repo

- **An extractor that drops things beats one that invents them.** Varka keeps
  machine transcriptions of load-bearing third-party papers in
  `sql/varka/papers/`, and the conversion is deliberately mechanical: the text is
  rebuilt from `pdftotext -bbox-layout` word geometry, where a glyph smaller than
  its line's body and off its baseline is a superscript or a subscript. A plain
  `pdftotext` is useless for a maths paper - it flattens every script, so `2^16`
  becomes `216` and `N_C` becomes `NC` - while the geometric pass recovered 1408
  scripts across 35 pages with seven misses, all of them tokens the PDF had
  already merged.
- **`marker-pdf` was measured against that and rejected, on five pages.** It is
  genuinely better at what the geometric pass loses: eight displayed formulas
  came out as correct LaTeX with their tall delimiters intact, plus 95 table rows
  including assembly listings the geometric pass drops entirely. But on the same
  five pages it silently closed three half-open intervals (`[0, U[` to `[0, U]`,
  twice more elsewhere) and dropped a digit from an eleven-digit validity bound
  (`10441974239` to `1044197429`), and mangled two glyphs in prose. Every one of
  those reads as plausible. A model-based pipeline never leaves a gap where it
  could not read something - it produces something reasonable instead - so its
  output cannot be trusted for the constants and ranges that are the whole reason
  to have the paper. Note it also needs a `llama-server` binary since surya 0.22
  dropped the torch backend, and its `pillow<11` pin does not build on Python
  3.14.
- The general shape: **for anything a plan will quote as a number, prefer the
  extractor whose failure mode is a visible hole.** Then say in the file what the
  hole is, and point at the PDF for the parts that did not survive.

## A reciprocal's top bits are a remainder, and other things a neighbouring codebase had

A review of `datealgo-rs` (Nuutti Kotivuori's Rust port of Neri-Schneider, with Cassio Neri as a
contributor), done after task 53 had shipped the same month block. The papers in `sql/varka/papers`
give the algorithms; a production port by people who have already fought the constants is a second
source worth an hour, because it shows which corners the paper leaves to the reader. Four things
came out of it, each checked exhaustively over Varka's day range rather than taken on trust.

**Weekday from the top bits of a truncated reciprocal.** `((m + k) * floor(2^32 / 7)) >>> 29` is
`(m + k - 1) mod 7 + 1` for every `m` up to about 1.34e8 (checked to that bound at a 2^16 step, and
over all 16,777,216 days of the narrow range with zero mismatches). The multiply wraps modulo 2^32
on purpose: the top three bits of the wrapped product are the fractional part of `m / 7` to three
bits, which is the remainder. Three ops. Today's `dayofweek(col)` body is 19 IntVector ops, of
which the two-fold mod-7 is about 16; `weekday` and `next_day` carry the same fold. This is the
largest single saving found since the calendar family started, and it is **range-bounded**: the
fold is exact for every int32 day, the reciprocal only inside the narrow range, so it belongs
inside the range task 52's compile-time analysis guarantees a calendar input (it would make
`dayofweek` a range-checked node like the extractions), as a fourth `FloorMod7` variant with the
fold kept as the total-range reference. It is a task of its own after the emitter settles, not a
rider on another PR.

**The Thursday rule for the ISO week.** `weekofyear` planned as "provisional week, then two
year-boundary corrections and a weeks-in-year helper". `datealgo-rs` does it as: move to the
Thursday of the same week, `t = d + 3 - weekday0(d)`; the ISO week-year is that Thursday's year
and the week is `(ordinal(t) - 1) / 7 + 1`. Varka already has the January ordinal from task 34, so
the whole rule is the weekday, a shift, the day-of-year over `t`, and one exact division. Same op
count as the planned design, but both boundary corrections and the helper vanish by construction,
and the test burden with them. Row 37 in `PLAN_MILESTONE_4.md` now says so.

**Days-from-civil without the era split.** `emitDaysFromCivil` (task 40, used by `add_months`)
does `era = y / 400` with a carry, then `century = yoe / 100` with a carry. `datealgo-rs` writes the
year part as `1461 * y / 4 - c + c / 4` with `c = y / 100`: equal to the era/yoe/century form over
all 102,500 biased years, `1461 * y` fits int32, and the `/400` division and its carry step are
gone. Worth about six ops off `add_months`'s 117. The `/100` still needs its correction step: no
exact magic exists on that domain with the product under 2^31 (re-checked, k = 16..31).

**A closed-form month length.** `30 | (m ^ (m >> 3))` is the length of every month except
February, for the January-based `m` in 1..12. Three ops against `last_day`'s two `emitMonthStart`
calls and a subtract, but the January month costs two ops from the March axis and February still
needs its blend, so the net is a few ops. Recorded; not worth a task on its own.

**Not taken.** `is_leap_year` there is the branchy `y % 25` form; Varka's Hueffner hash is four
branchless ops and stays. The century and year steps of `rd_to_date` use a 64-bit multiply-high,
which int lanes cannot express; they become borrowable verbatim when task 49 brings int64 lanes.

The general lesson is the admission check: every one of these was a claim about a constant over a
range until the range was swept. The month-length identity took twelve cases; the weekday trick
took the full narrow range plus a search for where it stops holding, because a trick that is exact
to 1.34e8 and needed to 1.68e7 has eight times the headroom, and that number is the thing to write
down, not "it works".

## Validate a fixed-format string with a saturating subtraction

From Daniel Lemire's `sse_date.c` (2023, "Parsing time stamps faster with SIMD instructions"), read
for a `cast(string AS DATE)` fast path; the design it produced is under milestone 4's item 8. The
general lesson is independent of dates.

A fixed-format string is a row that is either exactly in shape or not the kernel's business, and
the cheapest way to decide that for a whole row at once is not a compare per field but **one
saturating unsigned subtraction against a per-position limit vector**. XOR the bytes with `0x30`
so digits become 0..9 and every other byte becomes something large; subtract, saturating at zero,
the largest value each position may hold (`9` for a free digit, `1` for the leading digit of a
month, `3` for the leading digit of a day, the XORed separator for a separator). A byte in range
leaves zero; anything else leaves a residue. Where a per-byte limit is too loose - months 13..19
pass a leading-digit test - pair the bytes into two-digit values and subtract again against the
field's limit. Subtract the other way against a minimum vector to reject zero, and put the
separator's own value in that vector too, since an upper bound alone lets a digit sit where a dash
belongs. OR the residues and
test for all-zero: one mask for the row, no branch per field, and the failing rows go to the row
engine. It is the same discipline as task 26's range guard applied to bytes - the kernel checks
that the row is the shape it compiled for and declines the rest, rather than parsing.

Two facts that make it expressible here. JDK 25's `VectorOperators` has the saturating operators
(`SUSUB`, `SUADD`, `SSUB`, `SADD`, `UMIN`, `UMAX`; checked with `javap` on this machine), so the
trick needs no compare-and-blend emulation. And the digit *combine* that follows does not need
x86's byte multiply-add: with the digits packed into a long lane, the SWAR ladder from Lemire's
2018 `eightchartoi.c` - multiply by `1 + (10 << 8)`, shift, mask; multiply by `1 + (100 << 16)`,
shift, mask - leaves the two-digit fields in 16-bit slots after two steps, which for a date is
where to stop.

What the same repository does *not* have, so nobody looks twice: any SIMD date or time
*formatter*. Its integer-to-string work (the 2026 IFMA paper, eight digits in two `vpmadd52`
instructions) rests on a 52-bit multiply-add the Vector API does not expose; what survives for a
future `date_format` is only the idea of producing all eight fixed-width digits with lane
multiplies and inserting the separators with a shuffle, paired with ClickHouse's template-and-patch
(milestone 6, section 6).

## Velox is a semantics reference for the calendar family, not a performance one

This is the calendar read. Velox's expression evaluator - encoding peeling, per-row error
bitmaps, the conjunct metric - was surveyed separately on 16 September 2026 and is
`SCOPE_MILESTONE_8.md` item 18.

Read in September 2026 for the same question as ClickHouse, `datealgo-rs` and Lemire's repository:
is there anything to borrow. There is not, and the reason is worth one paragraph so nobody reads it
again for speed. Every Spark-compatible date function in `velox/functions/sparksql` converts the
day to a `struct tm` through a full civil decomposition and reads one field, one decomposition per
row per function with nothing shared. Since May 2026 (PR #17371, `velox/type/FastDate.h`) that
decomposition is Neri-Schneider's reference code with era shift 82 - the same month block task 53
shipped, the same `1461 * y / 4 - c + c / 4` and `(979 * m - 2919) / 32` inverse the `datealgo-rs`
review recorded, and the 64-bit year multiply task 49 is waiting for. Their measured gain from the
swap was 1.6-1.9x end to end on `month` and `day`, none on `year(date)`. ISO week goes through
Howard Hinnant's `iso_week.h`; `yearofweek` keeps the two boundary corrections task 37 dropped for
the Thursday rule; `next_day` is `start + 1 + floorMod(dow - 1 - start, 7)` off the day number, as
task 33 does. No datetime file contains SIMD. The only SIMD near expressions,
`SIMDComparisonUtil.h`, computes 64 comparison bytes and packs them into a bitmask, which the
Vector API gives Varka as `VectorMask.toLong()`.

What Velox *is* good for: its Spark-compatibility tests were written by people who had to match
Spark exactly, and they are a second, independent list of the edge cases worth pinning.
`sparksql/tests/DateTimeFunctionsTest.cpp` has 56 cases; task 37's row now names the `weekOfYear`
set as fixtures to import, and the `addMonths` and `makeDate` sets are a cross-check for tasks 40
and 42. Its string-to-date cast is a character loop over exactly the Spark grammar milestone 4's
item 8 sends to the fallback - optional sign, at least four year digits, optional `-[m]m` and
`-[d]d`, then end, space or `T` - which confirms that design's shape mask covers the right subset.
Velox also ships `DateExtractBenchmark` and `FormatDateTimeBenchmark` over 1024-row vectors fuzzed
within 67 years of the epoch; an external scalar-engine reference number is available from them at
the cost of a Velox build, and that ~67-year range is a fair data-shape argument in milestone 6's
item 10 cache question.

## The Julian map: one division stage fewer in civil-from-days

From `benjoffe/fast-date-benchmarks` (Ben Joffe's fork of Neri and Schneider's harness, with his
own algorithms from four posts, 2025-2026), read in September 2026. The fifth codebase read for the
calendar family and the first that changes Varka's arithmetic.

Neri-Schneider, and Varka's prefix after it, take the day of era to a century, then a year of
century, then a day of year, and pay for the leap day at the year step with an underflow
correction. Joffe removes the middle stage. Scale the day by four first, `qds = 4 * doe + 3`; the
century is `qds / 146097` (146097 / 4 is 36524.25, the mean century). Then add four back per
century, `jul = qds + 4 * cen` within an era (the general form subtracts `cen & ~3` as well, which
is zero inside one era): that maps the Gregorian count onto a calendar in which every fourth year
is leap without exception, and in that calendar `jul / 1461` is the year and `(jul mod 1461) >>> 2`
the day of year, Feb 29 included, with no leap test at all. The `+ 3` also puts the era's last day
in century 3, so the `cen == 4` fold goes too.

Checked here in Varka's terms - 32-bit low products, round-down magic, one carry per division -
over all 146097 days of an era against Python's calendar: zero mismatches with
`cen = (qds * 1837) >>> 28` and `yrs = (jul * 2870) >>> 22`, largest product 1677225130, one carry
sufficient for each (46 and 8627 of the 146097 days take it). Against `emitChronoPrefix` today
that is century 10 ops to 7, year 13 to 10, year assembly 5 to 3, and one correction stage fewer
on the dependent chain. It is task 54, run as task 53 was: a variant, an A/B, both widths.

**Measured, task 54 (`PLAN_TASK_54.md` 9).** Shipped as the default. The A/B in one run, Julian map
against century-then-year: `year` null-free 3444.2 against 2746.4 M rows/s at AVX-512 (+25%) and
1333.0 against 1054.5 at 128-bit (+26%, from the committed 128-bit companion file); four fields
unshared +19% and +20%; `add_months` +3% and +4%; the mixed-null rows +14% and +8%. The op-count prediction was 8-12% and the reason it was under
by half is the lesson: the five ops that went were a serial stage - a compare, a leap-flag mask,
three masked fixes, each waiting on the last - on a body that is latency-bound on its chain, so
they were worth their depth, not their count. Count ops to predict a throughput-bound body; count
dependent stages to predict this one. The same run moved the epilogue's `HugeMethodLimit` ladder a
fourth time (21 unshared, 44 shared), which is the fixture every prefix change moves and every
prefix plan should list.

Three neighbours of the idea, for the record:

- **Blend the constant, not the result.** Joffe picks the numerator's offset before the multiply
  rather than fixing the quotient after the shift. A January offset of `197913 - 12 * 65536` on
  task 53's month numerator makes `num >>> 16` the final month, and the low half is untouched so
  the day formula still holds (checked over all 366 days); `979 * 12 - 2919 = 8829` does the same
  for the month-start formula. The op count does not move; the blend leaves the critical path.
  That is a change for the instruction harness of task 31 to see, not for a ratio.
- **The rest of `fast64` needs a multiply-high.** The year multiply's low bits feed the month step
  directly, and `(yrs % 4) * constant` absorbs the leap day; four multiplies for the whole date
  where Neri-Schneider takes seven, about 40% faster in his scalar measurements. The Vector API has
  no multiply-high on any lane, so these wait for task 49's long lanes and its exact low products,
  where the admission check should now try the two-division form beside the three-division one.
- **The bucket technique** is the guard-free int-lane total if task 49 fails its gate: choose an
  approximate era by a shift, reduce the day into a window, fix the year up by `bucket * 2800`.
  About 14 ops against task 26's `TOTAL` at 16, without the deliberate wrap; the eight-entry offset
  table in his `article_2_l1` is one lane permute on a 256-bit int species.

Confirmed and left alone: `emitLeapFlag`'s Hueffner hash is the fastest leap test in Joffe's own
leap benchmark (0.79x of the Drepper-Neri-Schneider form on x64), and his signed variant is six
lane ops to its four; both are exact over Varka's biased year range.

## A GPU port of Spark's date code is scalar code run per thread

`NVIDIA/spark-rapids-jni` was read in September 2026 on the guess that a lane-per-thread engine
with Spark's exact semantics would have solved Varka's branch-free problem for string parsing and
time zones. It has not, and the reason is worth keeping so the guess is not made again: CUDA
tolerates divergence, so its kernels are ordinary scalar C++ - `while (pos < end)` digit loops,
early returns, a per-row `switch` on the format string - run once per thread. The arithmetic under
them is Hinnant's `civil_from_days` with plain divisions and a weekday by `(days - c) mod 7`. None
of that shape transfers to Vector API lanes, where a divergent row costs the whole vector.

What it contributes is the residue of having matched Spark to the row: the trim definition
(`c <= 32 || c == 127`), the year-digit and year-range limits, the trailing `T`/space rule and a
fixture list for the string-to-date fallback (milestone 4, item 8); the ANSI protocol of parsing to
a nullable column and failing the batch if nulls appeared, which is Varka's status bit; and a
production instance of the transition-table timezone design milestone 4's item 2 already names -
two sorted instant arrays per zone, DST rules taking over past the table's end, and the
floor-versus-truncate decision at a gap that is wrong by an hour if made the other way. What it
does not contain is any extraction: `year` and its family live in cuDF on `cuda::std::chrono`.
