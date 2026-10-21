# Varka Milestone 8 Plan: every numeric and temporal type

*Opened 14 October 2026, while milestone 7 runs. The owner set the theme the same day: new data
types, and more casts and expressions on the existing ones. The decisions in section 1 are his
and fixed; this plan works inside them.*

*17 October 2026: milestone 7 closed (`m7/VARKA-261.md`), and this plan is the one in flight.*

This is a task plan, not a scope catalogue. Under the house rule (`sql/varka/AGENTS.md`), the
catalogue moved forward in the same pull request: the catalogue that sat in this folder is now
`m9/SCOPE.md`, with its item numbers unchanged, its reading note (`READING_LAYOUTS.md`) and the
plans of the two rows it holds (`VARKA-64.md`, `VARKA-73.md`) beside it, and every inbound
reference rewritten by `dev/varka_catalogue_move.sh 8 9`, which a later merge reruns. The items
this milestone takes are named below with the number they have there: 1, 2, 31, 32, 33, 34, 35,
36, 54, 55, 89 (bullet 1, and bullet 6 without its runtime bound, which the 128-bit lane pair of
2.8 makes unnecessary), 93 and 94. So are the rows it
takes from the catalogue's moved-row tables (items 15 and 39), which keep their task numbers and
their designs where they are: 27, 28, 30, 39, 89, 95, 103, 104 and 157. Four of those already
have plan files in this folder (`VARKA-28.md`, `VARKA-39.md`, `VARKA-89.md`, `VARKA-104.md`).

*Owner's decision, 14 October 2026, on the math family: item 36's first option, a ULP contract
behind a SQL configuration (2.7.1, row 345). The functions it covers are fused within a stated
bound, the one deliberate departure from bit-identical answers, recorded as such in
`SCOPE_STANDARD_MODE.md`.*

*Owner's decision, 14 October 2026, on decimals: milestone 8 supports `DECIMAL` up to precision
38, an unscaled long up to precision 18 and a 128-bit lane pair above (2.8, rows 359 to 364). It
replaces a rule of the same day that bound every decimal node to precision 18. Decimal division
stays out.*

*Owner's answers, 14 October 2026, to the plan's open questions: the math configuration defaults
to on (2.7.1); `TIME` to `TIMESTAMP_NTZ` is in, its date a per-query constant (2.6); decimal
division is out of this milestone (2.8); the post's headline is decided later (section 7).*

*Owner's decisions, 14 October 2026, on the review of this plan against the engines and the
papers (six engine explorations and five paper readings; what they found is carried into the rows
below, and section 9 lists what they read and rejected): the fork's Arrow readers build a
long-backed decimal up to precision 18, so the kernel, the fallback and the Varka-off path all
follow Spark's exact cast rule, which is what Parquet gives (A1, row 365); rows 362 and 363 keep
the 256-bit product, the `10^38` check and the rounding division, built and measured against the
row engine and declined only if they lose (A2); `pow` is fused behind a guard on fdlibm's weak
branch, its exact exponents as exact nodes outside the contract (A3, row 358); and the exact
decimal-to-floating arm, the extended math probe, Spark's own cast and hash suites under the Varka
evaluator and the fuzzer's count arm are rows of their own (A4, rows 366 to 369).*

*Owner's answer, 16 October 2026, to question 3 of section 7: option (iii), no reader change. It
supersedes A1. The house rule is that row answers follow vanilla Spark always, and A1 would make
every Arrow consumer of the fork answer the ANSI and TRY casts of a decimal to `INT`, `SMALLINT`
and `TINYINT` differently from vanilla Spark with Varka off as well. So the kernel reproduces
vanilla Spark's column-path rule for those three casts, the double rule over a
`BigDecimal`-backed value, which is what Varka's fallback reads through `ArrowColumnVector`. A1's
text is kept where it stands, with notes; the places it shaped (1.1 item 3, 2.2, 2.8, 2.10, 5,
rows 350, 364 and 365, the wave table, risk 10, 10.1 item 3) now carry (iii)'s consequences,
each marked with this date.*

*Owner's answer, 16 October 2026, to question 4 of section 7: "we should start from the tasks that
establish the foundation of Varka." Neither order the question offered, but a third one derived
from the rows' dependencies: every foundation row - a row other rows build on, such as a lane, a
load or store, a shared IR node or a harness - lands before any function row that nothing waits
on, for every type. The order is 4.1; the old table stays in section 4 as the record. The places
the cheapest-first order shaped (2.3, 2.9, section 4, 10.4) carry dated notes.*

*Owner's decision, 14 October 2026: no milestone 7 row moves.* The rows still open in
`m7/PLAN.md` stay there and close there; nothing below depends on one except where a row says so.

*17 October 2026, at milestone 7's close: two rows moved here after all, 180 (promotion, a
cadence no milestone closes) and 386 (a filter whose answer waits on Spark's own decision
upstream); `m7/PLAN.md`'s rows 180 and 386 record the move and the rows below carry it.*

## 1. The question, and what "done" means

Varka fuses six Spark types on two lanes. `VarkaNodeCompiler.laneOf` admits `INT`, `DATE` and
year-month intervals on a 32-bit lane and `BIGINT`, `TIME` and day-time intervals on a 64-bit one;
`VarkaEvaluatorBase.isArrowBacked` reads their six Arrow vectors and nothing else;
`VarkaKernelEvaluator.allocateVector` writes the same six. Everything else a query computes over a
number - a `DOUBLE` price, a `DECIMAL` amount, a `BOOLEAN` flag, a `SMALLINT` code, a
`TIMESTAMP_NTZ` event time - goes back to Spark's row engine at the first node that touches it.
And on the six types it has, Varka fuses 62 of the 183 expressions the census below finds in
scope, 15 of them for every type they take.

The owner's theme is the answer: **new data types, and more casts and expressions on the existing
ones.**

* **The type set.** The existing six, checked against the code above, plus seven new ones:
  `BOOLEAN`, `TINYINT` (`BYTE`), `SMALLINT` (`SHORT`), `FLOAT`, `DOUBLE`, `TIMESTAMP_NTZ` and
  `DECIMAL` up to precision 38, Spark's maximum, in two representations (an unscaled long up to
  precision 18, a 128-bit lane pair above).
* **Out of scope, owner's decision, 14 October 2026:** `STRING` itself and every cast to or from
  it; `TIMESTAMP` with the local time zone. Decimal division is out too (2.8).
* **The expressions:** "Explore all expressions, operators and functions which support existing
  and new types only. We should support most of them." Section 2.1 is that exploration, committed
  as a file a script regenerates, and 1.1 item 2 turns "most" into a number.
* **The milestone ends with a post**, named in 1.1 item 7.

### 1.1 Done when

1. **The seven new types are read and written by kernels, in eight representations** (`DECIMAL` as
   an unscaled long up to precision 18 and as a 128-bit lane pair above). Each has a coverage row
   for a load, a comparison, a choice and an output, its Arrow vector admitted by `isArrowBacked`
   and allocated by `allocateVector`, the IR fuzzer and the Spark-level fuzzer drawing it (352), and
   the bytes oracle unchanged for every existing shape at each lane's landing. The three
   representation questions with machine-dependent answers are built both ways and measured, the
   losing arm kept (329 for `BYTE` and `SHORT`, 347 for the decimal load path below precision 19,
   359 for the lane pair's layout).
2. **Most of the census is fused.** Of the 183 in-scope entries of `m8/census.tsv`, at least 122
   (two thirds) are fused for every type they admit, and of its 881 entry-and-type pairs at least
   661 (three quarters); every pair of the 881 left unfused carries its reason in the census,
   either a construction's declined band (346, recorded with its bounds) or a named row of
   `m9/SCOPE.md`; the pairs an owner's decision removed (decimal division) are outside the 881 and
   carry that decision in the census's reason column. Today the counts are 15 entries and 96
   pairs. A type counts as fused only where a
   coverage row reads a column of it, which `dev/varka_census.py` checks against `coverage.json` and
   row 328 makes exact.
3. **The cast matrix is fused and proven.** Every one of the 106 legal pairs of `m8/casts.tsv`
   is fused under LEGACY, ANSI and TRY where the mode admits it, or declined with its reason; each
   proven as section 5 assigns, by exhaustion where the source has at most 32 bits (62 pairs), by
   the SMT proofs of `dev/varka_prove.sh` where the source is a 64-bit integer and the rule an
   integer one (29), by sampled regions and the fuzzer where the source is a double or the pair
   rounds a 64-bit value to a floating type (12), and by both for a decimal to `INT`, `SMALLINT`
   or `TINYINT`, whose LEGACY low bits are SMT's and whose ANSI and TRY range check, by Spark's
   double rule at every precision, is the sampled arm's (3; until the answer to question 3 of
   section 7 on 16 October 2026 this read "whose exact rule below precision 19 is SMT's and whose
   double rule above it is the sampled arm's"); Spark's own cast suites run through the Varka
   evaluator agree with the matrix pair by pair (368).
4. **Spark's semantics hold at the edges and are tested there**: NaN and both zeros in every
   floating comparison, choice and `IN` (item 89 bullet 1's table against
   `SQLOrderingUtil.compareDoubles`); a zero divisor null under LEGACY and an error under ANSI for
   every numeric type; integral overflow wrapping at its own width under LEGACY and raising under
   ANSI, `TINYINT` and `SMALLINT` included; decimal rescaling `ROUND_HALF_UP`, Spark's
   precision-loss rule above precision 38 (363), and decimal overflow null under LEGACY and an error
   under ANSI, at every precision up to 38; NaN payloads and signs compared by the register's rule
   (`SCOPE_STANDARD_MODE.md` 3.5), bit for bit where a value passes through and by NaN-ness where
   it is computed; every floating-to-decimal answer held to JDK 19's `Double.toString` (3.6); the
   ANSI form comparing error classes, as `VARKA-138.md` built it, on every new type.
5. **The math family is fused under the ULP contract** (345, 367, 356 to 358): each of its 27
   entries states its bound - in representable doubles from the row engine's double, keyed by JDK
   build, host library (SVML at AVX-512, SVML at AVX2, SLEEF on NEON) and CPU - in the coverage
   table and the user support table, its exact values are tested bit for bit before the bound, its
   test oracle compares within the bound, it is gated by
   `spark.sql.codegen.varka.math.ulpContract.enabled`, on by default, and the departure is entry
   3.4 of `SCOPE_STANDARD_MODE.md` and is stated where users look (353). The eleven entries item
   36's probe never measured are fused only after 367 measures them, and `pow` behind its guard
   (2.7.1). Every other expression stays bit-exact.
6. **The existing types are filled in**: `BIGINT` arithmetic (104), the integral division shapes
   (30, 95, item 33), the day-time interval expressions (103), `date - date` (39), the year-month
   divisions (89), the bitwise family (item 32), null-safe equality (item 34), the choice shapes of
   items 55 and 94, and `IS NULL` over a predicate (item 93).
7. **The post is published**: *One engine, every number: Varka across Spark's numeric and temporal
   types* (355). The claim it has to earn is the census's: what share of Spark's scalar expressions
   over these thirteen types a kernel now answers, bit for bit with the row engine (or within the
   stated contract for the math family), and how that is known - the exhaustive sweeps, the SMT
   proofs, the fuzzers - with every declined case named. A share without the "how it is known"
   half is not the post.
8. Beside the spine, the support table users read (353, item 54) states the census per type, and
   the milestone closes on 354, which reads this list row by row.

## 2. Design

Each row's design is in the item or plan it comes from, where it has one; this section says what
the milestone adds and how the rows meet. Every count below comes from `dev/varka_census.py` at
commit `90b8871b705` (14 October 2026), whose output is the two committed files.

### 2.1 The census

`dev/varka_census.py` reads `FunctionRegistry.scala` and writes `m8/census.tsv`, one line per
registered name and per implicit operator. The mechanical half - every name, the class or builder
it registers, the registry section - comes from the source; the judgement half - which types each
admits within the set, its result, what Varka fuses today and what is left - is
`m8/census_curated.txt`, read against each class's `inputTypes` and `dataType`. The script fails
rather than guess: a registered name with neither a curated line nor an exclusion, a curated name
the registry no longer has, a class the sources do not define, or a type claimed fused for a class
that no row of `coverage.json` both names and reads a column of, and any registry entry line it
did not read. The last-but-one is a check and not a proof - a coverage row naming several classes
credits each with every column it reads - and row 328 adds the single-expression coverage rows
that make it exact. A type fused only over a derived operand (`year(d) * 2` for an int multiply)
is marked, and its curated note must quote the coverage row. `--check` fails if either committed
file is stale, and row 328 runs it in the linters' job.

**The registry has 599 names, not 511.** `SCOPE_FUNCTIONS.md` counted 511 on 19 September 2026. The
same parse over the registry at `3fe328d55c0` (20 September) finds 595, with the sections above its
table differing too (math 69 against 61, datetime 85 against 64), so the old figure does not
reproduce by this method and is not used here; the four names since are upstream additions. 599
names register 534 classes. Beside them, 28 operators reach a plan without a registry entry of their
own (`try_cast`, `InSet`, `SubtractDates`, `SubtractTimestamps`, `TimestampAdd`, the interval
multiplications, divisions and extracts, `CheckOverflow`, `MakeDecimal`, `UnscaledValue`, `TryEval`,
the optimizer's relabels and its `NormalizeNaNAndZero`). `PromotePrecision` no longer exists in this
tree; decimal arithmetic computes its result type in `BinaryArithmetic` itself.

**What is out, and why.** 444 of the 599 names are out:

| reason | names |
| :--- | ---: |
| `STRING` input or output, or a parse of one (owner's decision) | 151 |
| aggregate, window or generator (Varka fuses scalar projections and filters) | 105 |
| nested types: array, map, struct, the vector functions | 65 |
| sketch state, `BINARY`, `VARIANT`, geospatial | 83 |
| `TIMESTAMP` with the local time zone (owner's decision) | 20 |
| a constant per query, folded before Varka sees it (`current_date`, `pi`) | 7 |
| nondeterministic, or a per-task fact (`rand`, `uuid`, `spark_partition_id`) | 9 |
| a `NULL`-typed or `CALENDAR INTERVAL` result | 4 |

**What is in.** 183 entries - 155 registered names and the 28 implicit operators - over 157
classes. By family, with the entry-and-type pairs they admit and the pairs fused today:

| family | entries | pairs | fused pairs | fully fused entries |
| :--- | ---: | ---: | ---: | ---: |
| conditional | 11 | 120 | 8 | 0 |
| predicate | 16 | 149 | 25 | 0 |
| math and arithmetic | 59 | 223 | 12 | 0 |
| bitwise | 13 | 41 | 0 | 0 |
| datetime | 41 | 102 | 40 | 11 |
| hash | 2 | 26 | 0 | 0 |
| conversion (`cast` and its aliases) | 11 | 104 | 0 | 0 |
| misc (bitmap positions) | 2 | 2 | 0 | 0 |
| implicit operators | 28 | 114 | 11 | 4 |
| **total** | **183** | **881** | **96** | **15** |

By type, pairs fused today over pairs admitted: `INT` 15 of 90, `BIGINT` 12 of 86, `DATE` 41 of 63,
`TIME` 13 of 49, year-month interval 10 of 55, day-time interval 5 of 57; and none yet of the new
types' `BOOLEAN` 41, `TINYINT` 75, `SMALLINT` 75, `FLOAT` 66, `DOUBLE` 102, `DECIMAL` 68,
`TIMESTAMP_NTZ` 54.

By the hardest work left once its lanes exist: 15 done, 74 plumbing (a compiler arm and IR on the
lanes), 67 semantics (overflow modes, NaN, rounding, decimal scale, and the constructions of 2.7
for `round`, `bround`, `truncate`, `%`, `mod`, `pmod` and `try_mod` over a floating operand, which
the first draft of this plan called hard), 27 the math family under its ULP contract (2.7.1), and
none hard: the reading of the papers (section 9) gave every former hard entry a construction, with
a declined band where one has it (346).

Three properties of the census to read it by:

* **"Fused today" under-counts on purpose.** A type counts only where a coverage row reads a
  column of it: `docs/sql-varka.md` says year-month intervals fuse in the comparisons and `IN`, but
  no coverage row shows it, so the census does not count it. Row 328 adds the coverage rows that
  close such gaps, which moves the count without moving the compiler.
* **The cast entries count by source type**, as the census's other entries count by type: an
  alias admits exactly the sources `Cast` allows it (the script computes them from the matrix's
  legality, not by hand), and a source counts fused when every legal pair from it in
  `m8/casts.tsv` is fused in every mode it admits. So the conversion family's 104 pairs move with
  section 2.2's matrix.
* **A pair declined by the owner's decision leaves the denominator.** Decimal division (`/`,
  `try_divide` and `div` over decimals) is out of this milestone; the three pairs are marked in the
  curated file and recorded in the census's reason column, not counted among the 881. A
  `DECIMAL(p, s)` with a negative scale, which Spark allows only under
  `spark.sql.legacy.allowNegativeScaleOfDecimal`, is not a type of the census: the compiler
  declines it for every entry, and the curated file's head says so.
* **The done-when share is over pairs and over entries.** Pairs weigh an expression by how many
  types it serves, so `=` over thirteen types is thirteen; entries weigh `factorial` the same as
  `+`. Either alone would flatter one kind of work.

### 2.2 The cast matrix

`m8/casts.tsv`, from the same script, lists every ordered pair of the thirteen types that
`Cast.canCast` (LEGACY, `Cast.scala` lines 249 to 340) or `Cast.canAnsiCast` (ANSI, lines 92 to
205) allows; `try_cast` follows `canTryCast`, which for these atomic types is `canAnsiCast`
(lines 211 to 239). Of 169 ordered pairs, 106 are legal under LEGACY and 98 under ANSI and TRY;
the eight LEGACY-only pairs are `DATE` to each numeric type and to `BOOLEAN`, all of which return
null for every row. Row 328 holds the transcription to Spark by a suite that asks `Cast` itself
about every pair, so the matrix cannot drift from the rules it copies.

**By what a pair does to a value:**

| kind | pairs | what it is, and the mode split |
| :--- | ---: | :--- |
| identity | 9 | same type |
| relabel, unit narrowing or truncation | 3 | year-month MONTH end and day-time SECOND end keep the value, `TIME` widening keeps it (all three fused today); a coarser end field or a narrower `TIME` precision divides |
| widening | 12 | integral to a wider integral, to a floating type that holds it exactly, `FLOAT` to `DOUBLE`; exact in every mode |
| narrowing | 6 | integral to a narrower integral: LEGACY wraps, ANSI raises `CAST_OVERFLOW`, TRY nulls |
| rounding | 6 | `INT` or `BIGINT` to `FLOAT`, `BIGINT` to `DOUBLE`, `DOUBLE` to `FLOAT`, decimal to floating; round to nearest even, no error in any mode |
| truncation | 12 | floating to integral (LEGACY saturates to `INT` or `BIGINT`, NaN to zero, and for `TINYINT` and `SMALLINT` saturates to `INT` and then wraps; ANSI and TRY range-check on the double - against `2^31 - 1` exactly for `INT`, and for `BIGINT` against `Long.MaxValue.toDouble`, which is `2^63`, so `2^63` passes and saturates - and raise or null on NaN), decimal to integral (toward zero, the low bits kept under LEGACY; `CAST_OVERFLOW` under ANSI, by the exact rule for a long-backed value and the double rule for a `BigDecimal`-backed one, 2.8; for Varka the double rule at every precision for `INT`, `SMALLINT` and `TINYINT`, since its fallback reads `BigDecimal`-backed values: the answer to question 3, 16 October 2026) |
| scale up | 13 | integral to decimal (overflow past `p - s` digits: null or `NUMERIC_VALUE_OUT_OF_RANGE`), `DATE` to `TIMESTAMP_NTZ` and integral to an interval (`multiplyExact`: raises under LEGACY and ANSI and is null under TRY, since `try_cast` wraps every in-scope cast in a catch - `Cast.canUseLegacyCastForTryCast` exempts only string sources; a `TINYINT` or `SMALLINT` source never overflows, an `INT` only to a DAY end, past 106,751,991, or a YEAR end, past 178,956,970) |
| rescale | 4 | decimal to decimal (`ROUND_HALF_UP` on a smaller scale, overflow null or error), `TIME` to decimal, decimal to an interval |
| floor division, floor modulo, unit division | 16 | `TIMESTAMP_NTZ` to `DATE` and to `TIME`, `TIME` and the intervals to integral and decimal targets |
| test, select | 14 | numeric to `BOOLEAN` (`x != 0`, NaN true), `BOOLEAN` to numeric (one or zero) |
| always null | 8 | `DATE` to numeric and `BOOLEAN`, LEGACY only |
| session date | 1 | `TIME` to `TIMESTAMP_NTZ` takes its date from `CURRENT_DATE`, which `ComputeCurrentTime` makes a literal per query; in, as a per-query constant (2.6) |
| shortest digits | 2 | `FLOAT` and `DOUBLE` to decimal: `Decimal(double)` is the double's shortest decimal, which 346 builds as a lanewise guard from the rounding interval, an integer arm, and the exact shortest-digits path for the rest |

Decimal to floating is in "rounding" with two guards, one per target, and an exact arm behind
them. `Decimal.toDouble` is `BigDecimal.doubleValue`, correctly rounded; an unscaled long `u`
divided by `10^s` in double arithmetic is also correctly rounded while both operands are exact and
the division rounds once, which is the JDK's own guard: `s == 0` at any magnitude, or `(long)
(double) u == u` with `0 < s <= 22`, `Long.MAX_VALUE` excluded from the equality since it rounds to
`2^63` and back. `Decimal.toFloat` is `BigDecimal.floatValue`, rounded once from the exact value,
so the `FLOAT` target has its own, narrower guard - `(long) (float) u == u` and `s <= 10`, the
quotient taken in float - and **never the double quotient narrowed**, which rounds twice: the
float-conversions reading found Velox, ClickHouse and StarRocks all narrowing, and a value inside
the double guard, `DECIMAL(16, 14)` at 64.00001907348633, that narrows wrongly. Where the `FLOAT`
guard fails, round-to-odd is sound: `q = RN(u / 10^s)` in double, `r = fma(-q, 10^s, u)` the exact
remainder, `q` moved to its odd neighbour toward the exact value when `r != 0` and `q`'s last bit
is even, then `D2F` (Boldo and Melquiond's Theorem 3, with `k = 29` extra bits). Where either guard
fails, 366's exact arm takes the batch: decimal to double is the number-parsing problem `u * 10^-s`,
and with the scale a per-column constant Eisel and Lemire's algorithm needs a table of two longs
per kernel; their Theorem 1, Corollary 2 and Lemma 3 give an exact result for every unscaled long
with no fallback while `s <= 27`, at about eight limb multiplies and forty lane operations, taken
per batch when the guard mask is not all true, so the 64-bit form fuses every value for both
targets; the abort case exists only for `s >= 28`, which must decline. Above precision 18 (364),
the exact arm runs twice on the top 64 bits of `|u|`, as `w'` and `w' + 1`, accepts when both give
one double and declines the lane otherwise, about one in a thousand by the reading's estimate.

**How each pair is proven** is by its source, since the source's width is what makes exhaustion
possible: every value of a `BOOLEAN`, `TINYINT`, `SMALLINT`, `INT`, `DATE` or year-month interval,
and every bit pattern of a `FLOAT` (2^32 of them), is tried against Spark's own `Cast` - 62 pairs. A
64-bit integer source (`BIGINT`, day-time interval, `TIME`, `TIMESTAMP_NTZ`, the unscaled decimal,
which above precision 18 is 128 bits) cannot be exhausted, but where the rule is an integer one - a
wrap, a range check, a multiply by a power of ten, a division by a unit, 29 pairs - it is what
milestone 7's SMT proofs state (`VARKA-240.md`, `VARKA-241.md`), so each is proven there over the
whole domain, beside sampled regions and the fuzzer. 12 pairs - a `DOUBLE` source, and a 64-bit
source rounding to `FLOAT` or `DOUBLE` - get sampled regions (both zeros, NaN, the infinities, the
subnormals, each integral boundary and its neighbouring values, and `+-2^63`, `+-2^31` and their
neighbours for the integral targets) and the fuzzer: `VARKA-241.md` found floating-point theory
does not decide in useful time. The remaining 3, a decimal to `INT`, `SMALLINT` or `TINYINT`, are
mixed (2.8): below precision 19 the rule is the exact integer one and SMT's, above it Spark's own
rule goes through a double and is the sampled arm's. *Changed 16 October 2026 by the answer to
question 3 of section 7:* the kernel follows the double rule at every precision for the ANSI and
TRY range check of those three, so that check is the sampled arm's throughout and only their
LEGACY low bits are SMT's; they stay a mixed 3. Seven pairs are fused today, each in part (the
note column says which part). Beside the matrix, 368 runs Spark's own cast suites through the
Varka evaluator and records fused or declined per pair and mode, as Gluten's tracker does, diffed
against the matrix nightly.

### 2.3 What the lane model has, and what each new type asks of it

Checked in the code on 14 October 2026:

* **`VarkaVectorIR.LaneType` is `{ INT, LONG }`**, carried by the leaves and derived everywhere
  else by an exhaustive switch over the sealed IR. The main sources name `LaneType.INT` or
  `LaneType.LONG` 55 times and the emitter's `Lane.INT` or `Lane.LONG` 26 times, across 16 files
  (`git grep -E "LaneType\.(INT|LONG)\b|\bLane\.(INT|LONG)\b" -- 'sql/*/src/main/*'`). A new
  lane type is a compile error at every exhaustive switch, which is what the Java rules want.
* **`Lane` derives each lane's descriptors from an integral scalar** (`CD_int`, `CD_long`): the
  broadcast, the lanewise and compare shapes, the shifts and the bitwise operators. A `FLOAT` or
  `DOUBLE` lane needs that enum split into its integral and floating halves before any floating
  arm exists - the operators differ (no shifts, no `AND`; `DIV` is a true divide), and so does
  what a comparison means (section 2.7). That is the structural reason the cheapest-first order of
  the owner's brief is right: `BYTE` and `SHORT` exercise a third and fourth width on integral
  descriptors, before the floating split. *16 October 2026:* the order is now foundations first
  (4.1, the answer to question 4 of section 7), not cheapest first; this structural point stands,
  since the narrow lanes (wave F1) still land before the floating split (wave F2).
* **A kernel reads a column as a `MemorySegment` over the Arrow data buffer with a byte stride**
  (`Lane.byteStride`). `TinyIntVector`, `SmallIntVector`, `Float4Vector`, `Float8Vector` and
  `TimeStampMicroVector` are the same shape at strides one, two, four, eight and eight;
  `BitVector` is not (one bit a row, section 2.5) and `DecimalVector` is sixteen bytes a row
  whatever the precision (`ArrowUtils.toArrowType` maps every decimal to `Decimal(p, s, 128)`,
  section 2.8).
* **Mixed widths wait on `VARKA-28.md`.** Today an emission carries one lane and the IR refuses a
  tree that mixes them, except for `NarrowLane`: a long value delivered as an int column, allowed
  only as an output root and only over values proven to fit (the `TIME` field extracts), which
  row 28 generalizes rather than replaces. Most of the cast matrix,
  the `TIMESTAMP_NTZ` calendar fields (a long dividing into the int32 calendar prefix) and every
  implicit promotion Catalyst inserts (`i + l` arrives as `CAST(i AS BIGINT) + l`) are mixed-width
  trees, so row 28 is wave 0 whatever the type order. *19 October 2026:* row 28 is done; a kernel of both lanes is driven at
  the long lane, its int values in the low half of the int species of the same width
  (`VARKA-28.md` 3.2 and 9).

### 2.4 `TINYINT` and `SMALLINT` (329, 330, 332)

Spark computes `TINYINT + TINYINT` as a `TINYINT`: LEGACY wraps at eight bits and ANSI raises
when the int sum leaves `[-128, 127]` (`ByteExactNumeric.plus`); the same at sixteen bits for
`SMALLINT`. Comparisons, choice and `IN` are width-independent. There are two ways to give them
lanes, and which is faster depends on the machine - the case where the owner keeps both arms:

* **Widen onto the int lane.** Load eight or sixteen bits, sign-extend to int32 (`ByteVector` to
  `IntVector` by `convertShape` or a widening load), compute on the int lane the engine already
  has, and narrow at the store. Arithmetic then needs a re-narrow per node: LEGACY's wrap is a
  shift left and an arithmetic shift right by 24 (or 16), ANSI's check a range compare. No new
  descriptors; a quarter (or half) of the lanes per vector of a native lane.
* **Native `ByteVector` and `ShortVector` lanes.** Four (or two) times the elements per vector,
  wrapping arithmetic for free; the ANSI and TRY overflow test is `add != sadd` (and `sub != ssub`,
  the saturating operators the Vector API has), one instruction on a byte or short lane; but two
  new `Lane` entries, and every mixed-width node with an int or long converts. `*` widens in both
  arms: HotSpot lowers a byte add and compare natively and a byte multiply or shift by emulation
  through 16-bit lanes (the integer reading), so the verdict is per operator, not per arm.

329 builds both behind an emit option, as `VARKA-248.md` 3.2's matrix expects of an option, and
measures them on comparisons, a three-node arithmetic chain, a mixed `TINYINT` and `INT`
projection, and a fourth shape, the packed boolean output of a byte compare (a 32-lane byte mask
packs in one `toLong`), at both vector widths, recording the verdict per operator. Two facts of the
plan shape the shapes: `b + 1` over a `TINYINT` column is an int-lane shape after type coercion,
and a byte compared with a literal arrives from `UnwrapCastInBinaryComparison`, which rewrites the
widened compare back onto the byte column. The default is the faster on the CI runners' widths;
the other arm stays, fuzzed and in the option matrix. 330 then gives both arms the arithmetic in
the three modes, proven by exhaustion over every pair of `TINYINT` operands (2^16) and every pair
of `SMALLINT` ones (2^32) for each operator, with `-(-128Y)`, `abs(-128Y)` and the short analogues
as named cases.

### 2.5 `BOOLEAN` (331, 27)

A `BOOLEAN` column is not a lane: Arrow's `BitVector` holds one bit a row, with no byte stride.
What the engine already has is the right shape for it. A condition in a kernel carries a
known-true and a known-false mask (`VARKA-138.md`, item 93), and the validity bitmap is read and
written a word at a time. So a `BOOLEAN` column enters as a condition leaf - `VectorMask.fromLong`
over its data word, with known-true the valid set bits and known-false the valid clear ones - and
`AND`, `OR` and `NOT` over such leaves are Spark's three-valued logic with no new rule. It leaves
as row 27's boolean output: the mask packed into a `BitVector`'s data buffer, which is the
validity write the driver already does over a second buffer. Row 27's own measurement
(`m5/PLAN.md` 2.1) found packing the mask straight into the bitmap 1.18x faster at AVX-512 and
slower at 128 bits than materializing an int column, so it keeps both forms, as its text says.

Three things the reading adds. The tests put arbitrary data bits under null slots, since Spark's
writer clears only the validity bit. A boolean null literal becomes a condition leaf: `SELECT b =
1000 AS f` over a `TINYINT` column folds to one, and `VarkaConditionCompiler` matches no boolean
literal today (its literal arms take `Integer` values only). And the selected-row count is taken
per word: `VarkaSelectionBitmap.countSet` reads the mask a byte at a time, 512 reads for a
4096-row batch where 64 would do; a word-wise count, with no Harley-Seal at 512-byte bitmaps (the
proofs reading's verdict), goes in with 27.

With 27, every predicate the engine fuses can also be a projection output (`SELECT d < d2 AS
flag`), which is most of the predicate family's pairs at a stroke.

### 2.6 `TIMESTAMP_NTZ` (338, 339, 340)

Item 31's design holds: `TimestampNTZType` is microseconds since the epoch on the long lane,
evaluated in UTC (`zoneIdForType`), so its arithmetic is plain long arithmetic and its exact
helpers raise on overflow whatever the ANSI setting - the checked lowering is the only correct one,
and a batch with an overflowing lane declines to the row engine, which raises the same error.

* **338, the lane.** `TimeStampMicroVector` in `isArrowBacked` and `allocateVector`, one arm in
  `laneOf`; comparisons, choice, `IN`, the null tests; the casts with `DATE`. `DATE` to
  `TIMESTAMP_NTZ` is `multiplyExact(days, micros per day)`, which overflows the long past
  106,751,991 days either side of the epoch: it raises under LEGACY and ANSI and is null under
  TRY, so the kernel guards the day and the test runs `try_cast` at days `+-106,751,991` and
  `+-106,751,992`. The other direction is a floor division.
* **339, the arithmetic.** `ntz - ntz` (a day-time interval), `ntz +/- dt`, `timestampadd` for
  the units up to a week, `time_bucket` over a day-time width: plain checked arithmetic. `ntz +/-
  ym` and `timestampadd` by months, quarters or years are calendar arithmetic on the date part, the
  `add_months` machinery over the day and the time of day carried beside it. The overflow edge is
  not a day boundary: the representable timestamps end mid-day - the last valid time on day
  106,751,991 is 04:00:54.775807 and the first on day -106,751,992 is 19:59:05.224192 - so a
  kernel guards the day at `+-106,751,991` and then does a checked add, and day -106,751,992
  declines whole. `ntz - dt` is `ts + negateExact(dt)`, so `dt != Long.MIN_VALUE` is guarded too
  (and `months != Integer.MIN_VALUE` for the year-month forms); `time_bucket` with a width of a
  day or more raises when the next bucket's start overflows; `timestampadd`'s month units
  multiply in int.
* **340, the decomposition.** `year`, `month` and the other date fields go through the date:
  `floorDiv(micros, 86400000000)` into the int32 calendar prefix, the first kernel where a long
  feeds the calendar machinery. That division has an exact full-range form, from the division
  reading: 86,400,000,000 is `2^13 * 10,546,875`, so the floor division is an arithmetic shift by
  13 followed by a floor division by 10,546,875 of a value whose magnitude is at most `2^50`,
  with no guard needed at `Long.MIN_VALUE`; the Granlund-Montgomery form does the second step in
  three lane operations with no remainder, with shift 71, multiplier 223,875,151,780,487 and first
  wrong dividend 1,933,372,399,218,749 (checked here: the multiplier is `ceil(2^71 / d)`, and the
  first dividend where it fails is the first `n = d - 1 (mod d)` at or past `2^71 / e`, well past
  `2^50`). The proof goes into the long-lane proof file's divisor list, or 350's second file - not
  `ChronoDivide`'s table, which is int-lane. The int32 prefix is exact for years -12800 to about
  42400 (`VarkaChrono.NARROW_MIN_DAYS` to `NARROW_DECOMPOSE_MAX_DAYS`) and `TIMESTAMP_NTZ`
  reaches about 290,000 years either side, so the date fields decline outside the prefix's range,
  and the test pins `Long.MIN_VALUE` and `Long.MAX_VALUE` through the fallback, with `java.time`
  as the oracle at the edges (an engine built on a chrono-style library ends at 262,143 years and
  cannot serve as one). `hour`, `minute` and `second` are a floor modulo by the day and divisions
  by constants; `extract(SECOND FROM ntz)` is a `DECIMAL(8, 6)` and depends on the decimal rows.

**`TIME` to `TIMESTAMP_NTZ`, owner's decision, 14 October 2026: in.** Spark takes the date from
`CURRENT_DATE` in the session time zone, and in a real plan `ComputeCurrentTime` replaces the cast
with `MakeTimestampNTZ(dateLiteral, time).replacement` (`finishAnalysis.scala`), a date literal
fixed once per query - the same `StaticInvoke` of `makeTimestampNTZ` that `make_timestamp_ntz(date,
time)` becomes, so one compiler arm serves both. The kernel receives the date as a per-query
constant - a literal slot, whose value is an argument and not part of the shape key, so a kernel
cached from yesterday's query takes today's date - and computes `days * micros per day + nanos /
1000`. The ghost fallback sees the same date because it evaluates the same rewritten expression,
with the same literal, on the row engine. 340's test pins it: a query whose batches are split
between the kernel and the fallback (a forced decline on every other batch) returns one date on
every row, equal to the literal in the plan; a second query run under a different clock date
reuses the cached kernel and returns its own date.

Row 103 takes the same reading: `extract(DAY FROM dt)` is exact over the whole long by the same
shift split over the magnitude with the sign restored afterwards - `IntervalUtils.getDays` truncates
toward zero where the split floors, the sign trick `ConstDivide` already applies to a truncating
quotient - so `m5/PLAN.md` 2.38's "exact only under `2^52`" is corrected there by a dated note;
the hours and minutes extracts need the byte output (329, 330) and the seconds extract the decimal
lane (351); `make_dt_interval` checks each partial sum in Spark's order; and its `HALF_UP` on a
double factor is tested at ties for both signs, at `2^53` and at the long edges, with Spark's own
IEEE operations and never `Math.round` or a fused multiply. Rows 39 and 89 need no change.

Out with the zoned type: `date_trunc`, `months_between` and `timestampdiff`, whose operands Spark
coerces to `TIMESTAMP`.

### 2.7 `DOUBLE` and `FLOAT` (341 to 346)

Items 35 and 36 scope the lane and the math family; three facts of Spark's semantics decide the
first rows:

* **Comparisons are not IEEE's.** `SQLOrderingUtil.compareDoubles` makes NaN equal to NaN and
  greater than every other value, and `-0.0` equal to `0.0`. Item 89 bullet 1 has the formulas
  (from Polars's total-order kernels and Spark's `genEqual`) and the constant-specialised forms; the
  same ordering decides `greatest`, `least` and `IN`. 341 ships the first floating comparison with
  item 89 bullet 1's table test of every operator, extended to NaN payload bits and the tie cells:
  `greatest(-0.0, 0.0)` is `-0.0` and `greatest(0.0, -0.0)` is `0.0` (the first of two equal
  operands stays), two NaN payloads return the first, and `-0.0 IN (0.0, ...)` with 2 and with 11
  literals, the two `IN` lowerings. The costed alternative, a six-operation sortable key per
  operand, is recorded as not taken (section 9). One canonicalisation node - `x + 0.0`, then a blend
  of the NaN lanes to `0x7ff8000000000000` - serves `NormalizeNaNAndZero` and 337's hash input.
* **A zero divisor is null or an error, not an infinity.** `Divide`, `Remainder` and `Pmod` are
  `DivModLike`: null under LEGACY and raised under ANSI when the divisor is zero (`-0.0` included,
  since `x == 0`). And `Divide` takes only `DOUBLE` and `DECIMAL`, so `i / j` over two ints arrives
  as a division of two casts to double - which makes 342 the row that fuses the commonest division
  users write. Spark is the oracle for the ANSI branch (Velox has none), and a division by a
  constant is never turned into a multiply by its reciprocal.
* **Which operations are bit-exact.** `+`, `-`, `*`, `/` and `sqrt` are correctly rounded by IEEE
  754 in a lane exactly as in the scalar code, and `abs`, negation, `signum`, `degrees` and
  `radians` (`Math.toDegrees` is one multiply) are exact by construction; 342 ships them all, with
  the signed-zero and special cells as tests: `0.0 * -1.0` is `-0.0`, `-0.0 + 0.0` is `+0.0`, `-0.0
  - 0.0` is `-0.0`, `sqrt(-0.0)` is `-0.0`, and `inf - inf`, `0 * inf` and `inf / inf` are NaN. The
  transcendental functions are not: item 36's probe found every bound operator differing from the
  library Spark calls on up to thirteen percent of inputs on every host, so they are fused under
  the contract 2.7.1 describes.

**NaN payloads and signs: the oracle's rule** (`SCOPE_STANDARD_MODE.md` 3.5). The standard leaves
a NaN's sign and payload unspecified; a row carries raw bits, `abs` is a bit operation, negation
flips the sign, and Spark's own `rint(-NaN)` sign depends on the JIT tier (derived from the source
by the IEEE reading; 344 runs the probe). So the bytes oracle compares NaN lanes bit for bit in
pass-through shapes and by NaN-ness in computed ones, the fuzzers draw payload, negative and
signalling NaNs, and the emitter never commutes a floating `+` or `*`, which are not commutative at
the bit level with two NaN operands.

**The conversions (343)** take the IEEE reading's corrections. The D2L premise this plan opened
with was reversed: HotSpot's fix-up already turns the x86 "integer indefinite" into Java's `(long)
d` (NaN to zero, saturation), so LEGACY needs no detection and ANSI needs two range compares on
the double. The row carries a table mapping each Spark rule to its IEEE operation, with the split
boundaries as cells: the ANSI cast passes `2^63` and saturates (`DoubleExactNumeric.toLong`'s
`floor(x) <= Long.MaxValue.toDouble`) and raises on NaN; ANSI `floor` and `ceil` raise at `2^63`
and pass NaN to zero (`MathUtils.doubleToLong`); TRY nulls NaN and out-of-range; `D2B` and `D2S`
match LEGACY's `(byte) (int)` wrap in one operator. The host condition is named in the row: `D2L`,
`F2L`, `L2D` and `L2F` need AVX512DQ on x86; on AVX2 the magic-number form, `rawbits(x + 0x1.8p52)
- 0x4338000000000000`, serves below `2^51`. `BIGINT` to `FLOAT` must round once: through `DOUBLE`
it rounds twice, and `2^53 + 2^29 + 1` is the counterexample (checked here: `2^53` through the
double, `2^53 + 2^30` directly). On AVX2 and NEON the row builds it as round-to-odd - the
magnitude, `t = (m >>> 12) | sticky`, the `2^52` identity, times `2^12`, `D2F`, the sign - and the
plain identity plus `D2F` below `2^52`; the proof states the round-to-odd truncation as an SMT
bit-vector claim, cites Theorem 3 with its Coq proof, and exhausts the construction at a reduced
width through `binary16`.

**The rounding functions (344)** are built, not called, since `VectorOperators` has no rounding
operator. The `2^52` construction is written with its conditions: the keep-`x` arm at `|x| >=
2^52`, which also handles NaN and the infinities; Sterbenz's lemma for the exact subtraction;
parity for ties; and the sign restored by OR, without which `rint(-0.3)` is `+0.0` against
`Math.rint`'s `-0.0`. `floor` and `ceil` to `BIGINT` on AVX2 are `rint`, the correction and the
magic conversion, declining above `2^51`; `bround` over an integral by the Lemire, Bartlett and
Kaser section 7 form (add `d / 2`, floor, subtract one when divisible and odd).

**The former hard entries (346)** each have a construction from the reading, with a declined band
where one has it:

* `round`, `bround` and `truncate` over a `DOUBLE` at scales 0 to 22: Spark acts on the shortest
  decimal, so the kernel tests a tie as `|d| == (2k + 1) / (2 * 10^s)` under a magnitude guard, with
  the final step the JDK's own `v / 10^scale`; step 2 is Clinger's fast path with its bounds (`w <=
  2^53`, `2 * 10^s` exact for `s <= 22`), and Schubfach's R5 is the citation for "the shortest
  decimal is `T` when `ulp(d) < 10^-(s + 1)`"; a value with `|d| >= 10^(16 - s)` returns `d`; only
  the band below that and above the guard declines, and a negative scale stays declined.
* A floating `%` and `pmod`: the one-FMA remainder - `q = trunc(x / y)`, `r = fma(-q, y, x)`,
  recomputed after a one-step correction of `q`, `x`'s sign OR'd into `r` - exact for `|x| >= |y|`
  and `|x / y| < 2^53` by the quantum argument, with SLEEF's `xfmod` as a second oracle; `pmod(-0.0,
  n)` and `pmod(-n, n)` are `-0.0` in Spark and `+0.0` in Velox, a cell.
* `DOUBLE` and `FLOAT` to decimal: a lanewise guard from the rounding interval (with `y = RN(|d| *
  10^s)`, accept when the distance to the nearest half-integer exceeds `|y| * 2^-50` and `|y| <
  2^49`) plus the integer arm (an integral `|d| < 2^53` is its own decimal), and the exact
  shortest-digits path for the rest, as Comet does; `FLOAT` widened to double first; NaN and the
  infinities null even under ANSI; never Velox's fifteen-digit rule.
* Ryu's printf form is not adopted: its digits are the exact binary value's (C's `%f`) while
  Spark's three modes act on the shortest decimal - 2.675, 1.005 and 0.125 at scale 2 separate
  them - and per lane it would need a 50 to 100 kB gather (section 9). The band 346 declines has
  its item in `m9/SCOPE.md` (97): Schubfach's selection half in lanes.

#### 2.7.1 The math family's ULP contract (345, 367, 356 to 358)

The owner chose item 36's first option on 14 October 2026. What it means, piece by piece, with
the math reading's corrections:

* **A bound per function, per host library, stated where users read.** Each fused math function
  states in the coverage table and in the user support table (353) how far its lanes may be from
  the row engine's answer. **The bound is a count of representable doubles** - "within k steps",
  the ordered-long distance the probe already measures - and never `k * Math.ulp(reference)`, which
  admits twice the doubles below a power of two, passes anything against an infinite reference and
  passes `-0.0` against `+0.0`. Item 36's measured numbers do not move under the definition. The
  bound is keyed by JDK build, library and CPU, since the JDK binds a different library on each
  host - SVML at AVX-512, SVML at AVX2, SLEEF on NEON - and the binding is verified once per JDK
  build: `-Djdk.incubator.vector.DEBUG=true` must report a symbol address for every emitted math
  kernel, because a non-constant operator means a silent `Math.*` call per lane. The contracted
  bound is kept apart from the measured one: the Vector API promises only `Math`'s specification
  (up to 2.5 ulp for `sinh`, `cosh` and `tanh`), SLEEF states its tiers, SVML states none, and JEP
  537 changes SLEEF; the measured step distance is the guarantee, and 367's probe says of it "a
  lower bound of the true maximum".
* **Three statements of the first draft, corrected.** An operator the JDK leaves unbound on a host
  is exact only where Spark calls `Math`: `POW` at AVX2 falls back to `Math.pow` and not to Spark's
  `StrictMath.pow`, and the interpreter and C1 run `Math.*` too. `tanh` on x86 is one step against
  `Math.tanh`, which Spark calls; the two was against `StrictMath` (`dev/varka_canary/mathlane-x86-
  avx512-zen5.txt` and the AVX2 file: 1535 and 455 lanes differ from `Math` by at most 1, 5707 and
  5045 from `StrictMath` by at most 2). And `log10`'s two is `StrictMath.log10`'s own error - Sun's
  formula in `FdLibm.java` - not the library's, so no kernel shrinks it. A measured two means one
  side is wrong: two faithful results are at most one step apart except just above a power of two.
  `SCOPE_FUNCTIONS.md` 3 and item 36 say only six functions have host-independent row-engine bits;
  `atan`, `atan2`, `hypot`, `sinh`, `cosh`, `asin` and `acos` are fdlibm everywhere too, by the
  file's own list of the x86 intrinsics, and both carry a dated correction.
* **Two facts from the source readings (`READING_JDK.md` 2.2 and 2.3).** Spark calls `StrictMath`
  for `exp`, `expm1`, `log`, `log2`, `log10`, `log1p` and `pow` and `Math` for the rest, while the
  Vector API's fallback for an unbound operator is `Math.*`. On x86 `Math.exp`, `Math.log` and
  `Math.pow` are Intel stubs, so a declined lane for any of them, not only `pow`, is not bit-exact
  against the row engine. And `Math.cbrt` is an x86 stub but fdlibm on aarch64, and Spark calls
  `Math.cbrt`, so `cbrt` is host dependent like `sin`, not one of the fdlibm-everywhere functions.
  The binding is keyed by the species width as well as the CPU: SVML's AVX-512 symbols are chosen
  only for a 512-bit species, and `POW` is unbound below 512 bits.
* **A third, from the review of section 10 (10.1 item 1).** SLEEF 3.6.1, which the JDK bundles,
  sends `exp` and `pow` to infinity in a band below the true overflow point, where Spark's answer is
  finite, so on a SLEEF host a lane that comes back infinite from finite inputs declines the batch
  (358).
* **The eleven entries the probe never measured have no bound and are not fused until 367
  measures them**, at the level Spark computes them: `asin`, `acos`, `sinh` and `cosh` as
  operators; `sec`, `csc`, `cot`, `log2`, `asinh`, `acosh`, `atanh` and the two-argument `log` as
  the compositions they are, since a composite's bound is never copied from its primitive's, and
  `asinh` and `acosh` are fdlibm branch blends of three math calls per lane, not formulas.
* **`pow`, owner's decision A3.** fdlibm's `pow`, which `StrictMath.pow` ports, has a weak branch
  the probe never reached: for `|y| > 0x1.00000_ffff_ffffp31` and `0x1.fffff_0000_0000p-1 <= |x| <=
  0x1.00000_ffff_ffffp0` it computes `log(x)` by a four-term series, and a 636-ulp case is quoted
  for it. The branch is in `FdLibm.java`, but the number has no source in the JDK tree
  (`READING_JDK.md` 2.1), so 367 finds the worst case by search. The kernel fuses `pow` behind a
  guard: a batch with any lane where `|y| > 2^31` and `||x| - 1| <= 2^-20` - the guard tests `|x|`,
  as the branch does - declines to the row path, and the general-exponent kernel is enabled on a
  host only after 367 has measured the guarded domain there. fdlibm's exact special exponents,
  confirmed in `FdLibm.java`'s `Pow`, are exact nodes outside the contract: `y == 2` is `x * x`;
  `y == 0.5` is `Math.sqrt(x + 0.0)` for every `x >= -Double.MAX_VALUE`, the `+ 0.0` making
  `pow(-0.0, 0.5)` `+0.0`, with `-infinity` falling through to the general case (not "for
  `x >= 0`": a negative `x` gives NaN either way); `y == 1` is `x` and `y == -1` is `1.0 / x`.
* **An exact-value table per row, tested for bit equality before the bound**: integer-power
  exactness, `pow(x, 1) = x`, `pow(x, 0) = 1`, `log10(10^n) = n`, `tanh`'s saturation, `acos(1) =
  +0`, `exp(0) = 1`, the odd functions' signed zeros, the value-dependent nulls of the log family,
  `atan2`'s `+ 0.0`, and the two cases where Java departs from IEEE and C99: `pow(1, NaN)` and
  `pow(+-1, +-inf)` are NaN in Java but 1 in C99, which SLEEF follows. The within-bound comparison
  must not let a NaN pass against a number.
* **The probe (367), split out of 345:** bit-pattern inputs over the whole domain, the libraries'
  switching regions, the handbook's and Gladman's worst cases as fixed vectors, a bisection search
  for the largest step distance, results keyed by JDK build, symbol and CPU. Spark has no float math
  functions, so no float-exhaustive case exists.
* **The oracle compares within the bound, and only for these functions.** The coverage oracle, the
  reference evaluator and the fuzzers compare a math function's lanes with the row engine's within
  its stated bound for the host they run on, and a composition containing one compares its outputs
  that way; every other expression, including `sqrt`, `degrees`, `radians` and the arithmetic, stays
  bit for bit.
* **A SQL configuration gates it**, because it is a departure:
  `spark.sql.codegen.varka.math.ulpContract.enabled`, on the two-surface model of
  `SCOPE_STANDARD_MODE.md` 4, with the emit option in the shape key. Off, the math family declines
  with a reason naming the configuration. The configuration audit suite runs after it is added.
* **One query can mix the two answers.** The ghost fallback serves a declined batch on the row
  engine, so with the configuration on, rows of one output column can come from a kernel in one
  batch and from `Math` or `StrictMath` in the next, and may differ in the last bit by batch. That
  is the mix a cluster already produces: HotSpot's `Math.sin` itself differs between x86 and
  aarch64 executors, so a column of `sin` over a mixed cluster is already not one bit pattern. The
  coverage table says so beside the bound.
* **The register records it.** `SCOPE_STANDARD_MODE.md` 3.4 is the first deliberate departure from
  "row answers follow Spark", with the reason, the bound and the configuration.
* **A scope item for milestone 9** (`m9/SCOPE.md` 98): an emitted SLEEF-shaped `exp` and `log`,
  about 20 to 35 lane operations for `exp` and 40 for the u35 `log` (the u10 `log` the JDK binds is
  about 70; hand counts, `READING_SLEEF.md` 2), as the route to one bit pattern on every host and,
  later, to correct rounding - not an alternative to the contract, since fdlibm itself is not
  correctly rounded. Item 36's own dated correction records that its emitted-fdlibm option, which
  stays item 36's, must be costed without FMA, because `StrictMath` is pure Java and FMA changes the
  bits.

**The default is on: owner's decision, 14 October 2026.** The plan had recommended off; the owner
chose the speed. The consequences, plainly:

* **With Varka enabled, the math functions' answers may differ from vanilla Spark's by up to their
  stated bound**, unless a user sets `spark.sql.codegen.varka.math.ulpContract.enabled` to false.
  Enabling Varka is no longer answer-neutral for these 27 functions; it stays so for every other
  expression.
* **The differential suites** compare the math functions within the bound by default, since the
  default configuration is what they test; a second arm with the configuration off asserts that the
  family declines and the answers are Spark's bit for bit. The option matrix carries both.
* **The fallback** mixes the two answers in one column by batch, as above, by default rather than
  only for a user who opted in.
* **The documentation says so where users look**: the user support table (353) gives each
  function's bound per host library, and says what Java's own "within 1 ulp" specification is
  worth - a specification the libraries are believed to meet, while Gladman's tables show
  fdlibm-derived `log10` at 2.08 and `sinh` and `cosh` at 2.67 ulp (the math reading) - against the
  measured step distance, which is the guarantee; and `docs/sql-varka.md` states the default and
  how to turn it off at the head of its math section and in its configuration list, not only in
  the coverage table. 353 is not done until both do.

### 2.8 `DECIMAL` up to precision 38 (347 to 351, 157, 359 to 366)

Items 1 and 2 are the design input. *Owner's decision, 14 October 2026: milestone 8 supports
`DECIMAL` up to precision 38, Spark's maximum, with a 128-bit physical representation above
precision 18.* It replaces the same day's rule that bound every node to precision 18; decimal
division stays out.

**Two representations.** Spark's maximum precision is 38 (`DecimalType.MAX_PRECISION`), and
`10^38 - 1` is below `2^127`, so every Spark decimal's unscaled value fits a 128-bit two's
complement integer. Its scale rides on the Spark type, as a year-month interval's unit does. A
negative scale, allowed only under `spark.sql.legacy.allowNegativeScaleOfDecimal`, is declined by
the compiler everywhere and recorded as such in the census.

* **Precision up to 18: an unscaled long**, on the long lane, read by 347's three arms below.
* **Precision 19 to 38: a lane pair** - two long lanes, the low words and the high words, a value
  occupying one lane of each. Widening from the 64-bit form is sign extension of the low word into
  the high one. A 512-bit register then holds four values' worth of a pair (eight low words in one
  register, eight high words in another), half the long lane's density.

**What Arrow holds.** `ArrowUtils.toArrowType` maps every decimal to `Decimal(p, s, 128)`: sixteen
bytes a value, little-endian two's complement, the low word first. For precision above 18 those
are the value as it is, so no high-word guard is needed - but the words are interleaved in memory,
so the lane pair is still built by a de-interleave (two loads and two even and odd lane selections,
the shuffle 347's first arm uses for the low word alone) and stored by the inverse interleave; the
alternative of computing on adjacent lanes moves every carry across a lane instead, and 359 picks
by measurement. Admission requires `bitWidth == 128` on the Arrow field: Arrow Java maps the
32-bit and 64-bit decimal widths to the same sixteen-byte vector class, and DuckDB emits
`Decimal32` and `Decimal64`.

**The load path below 19 is a measurement, built three ways (347).** Item 1's de-interleave - two
loads and an even-lane shuffle per output vector, or a compress where it intrinsifies - with the
high word checked against the sign of the low one, a mismatch declining the batch, and the
shuffle-free form of that check as a measured variant; against a cache layout in which
`ArrowCachedBatchSerializer` writes a decimal of precision up to 18 as its unscaled long (or int
up to 9), which the kernel loads like a `BIGINT` and the row path reads through a column vector
that rebuilds the `Decimal`; and the int lane for precision up to 9. The measurement separates the
load's cost from the compute's, so the int arm is judged on its density and not on its load. The
cache arm changes a file format, so it carries a version tag the serializer checks; the
de-interleave serves every other Arrow source (Python, a columnar reader) either way. The JDK's
limits are recorded with the verdict as the layout reading states them: no two-lane `rearrange`
intrinsic on x86, no `compress` on NEON. The tests put garbage high words under null slots, a slot
holding `10^p`, and a wrong high word. Row 157 is the output's mirror: the store writes each value
word and its sign extension into the sixteen-byte slots, which is an interleave - two two-vector
selects, then two stores - and not a second store.

**The fork's two row paths, as the code has them (row 365, owner's decision A1).** The fallback
and every other Arrow consumer in the fork (the cached-batch iterator, the Python, R and external
UDF runners) read a decimal through `ArrowColumnVector`, whose `SmallDecimalAccessor` for
precision up to 18 reads the low word, checks the high word against its sign, and then
**deliberately builds a `BigDecimal`-backed `Decimal`** (`Decimal.apply(BigDecimal.valueOf(unscaled,
scale), precision, scale)`, its comment: "Keep BigDecimal-backed Decimal semantics, including
checked integral conversions"); above 18, `DecimalAccessor` builds one from `getObject`. The cache's
row reader, `ArrowColumnReader`, does the opposite for precision up to 18: no high-word check, and
the raw low word written into the `UnsafeRow`, which `getDecimal` reads back as a long-backed
`Decimal`. Spark has two rules for the ANSI and TRY casts of a decimal to `INT`, `SMALLINT` or
`TINYINT` (`Decimal.roundToNumeric`): a long-backed value truncates exactly, a `BigDecimal`-backed
one converts to a double first - `0.999999999999999999` as `DECIMAL(18, 18)` cast to `INT` is 1 on
the double path and 0 on the exact one, and `2147483647.99999999` raises on the double path. Spark's
Parquet reader builds long-backed values, so vanilla Spark on Parquet follows the exact rule; and
today, with Varka on, an ANSI `CAST(dec AS INT)` over a cached table can answer differently by
batch, the kernel-served batches aside, depending on which of the two fork paths serves it. 365
makes both paths build `Decimal(unscaled, p, s)` - long-backed - when the high word is the sign
extension and `|unscaled| < 10^p`, keeping the `BigDecimal` path for a value outside its declared
precision; adds the check to the cache reader; and files the upstream JIRA that records that
Spark's ANSI and TRY decimal-to-integral cast depends on the data source. Rows 350 and 364 then
build the exact rule for precision up to 18 (integer arithmetic, SMT at full width) and, above 18,
the double rule through 364's exact decimal-to-double (the two-pass arm), declining the lane where
the arm declines. `BIGINT` is exact on both paths (`roundToLong` uses `longValueExact`), so only
the three pairs are affected, and they leave the SMT count (2.2).

*Superseded on 16 October 2026 by the owner's answer to question 3 of section 7, option (iii); the
paragraph above is A1's plan, kept as the record.* 365 changes no reader: the fallback and every
other Arrow consumer keep `ArrowColumnVector`'s `BigDecimal`-backed value, and the cache's row
reader keeps its long-backed one, as upstream has them. Since Varka reads columns and its fallback
reads through `ArrowColumnVector`, the kernel follows the double rule at every precision for the
ANSI and TRY casts to `INT`, `SMALLINT` and `TINYINT`: 350 builds it below precision 19 as 364 does
above it. The rule range-checks a correctly rounded `u / 10^s`, so those three pairs take 366's
exact arm wherever 350's guard fails, and their proof is the sampled arm's, not SMT's (2.2, 5).
The by-batch disagreement of risk 10 stays, now between Varka-on reads (kernel and fallback both
on the double rule) and Varka-off reads of a cached table on the row path (the exact rule); 365
makes it visible, by a coverage note and a line in `docs/sql-varka.md` when the row is built, and
cannot remove it. The row reader's missing high-word check (`VARKA-365.md` 2 item 2) is reported
as its own finding in the upstream JIRA, whose text `VARKA-365.md` 9 records and which is filed
only with the owner's yes.

**What the row path costs, and why the speed-up is expected large.** Every Arrow consumer above
builds a `java.math.BigDecimal` per value at every precision, and arithmetic on such a `Decimal`
is `BigDecimal` arithmetic, allocating per value. A kernel over unscaled lanes allocates nothing
per value, so the largest speed-ups of the milestone are expected here, especially above precision
18 where the result is also a `BigDecimal`. **Unmeasured**: no number is claimed until 347 and 359
commit one.

**What 128 bits change.**

* **TPC-H q6's product fuses**: `l_extendedprice * l_discount` over the specification's
  `DECIMAL(15, 2)` is `DECIMAL(31, 4)`, a 64 by 64 to 128-bit multiply (361). Its `sum()` is an
  aggregate and stays Spark's in this milestone, so q6 as a query is still not Varka's end to end.
* **A `BIGINT` meets a decimal at `DECIMAL(20, 0)`** under type coercion (`DecimalType.LongDecimal`;
  `TINYINT`, `SMALLINT` and `INT` meet it at precision 3, 5 and 10), so `BIGINT`-with-decimal
  arithmetic and comparison now fuse through the widening casts of 360.
* **Precision loss.** With `spark.sql.decimalOperations.allowPrecisionLoss` true, the default, a
  result type above 38 is capped by `DecimalType.adjustPrecisionScale`: precision 38, scale `max(38
  - intDigits, min(scale, 6))`, the value rounded `ROUND_HALF_UP` to that scale. A product of two
  wide decimals is exact only in 256 bits before that rounding, so 362 and 363 carry the cases a
  128-bit lane pair cannot hold whole: a full 128 by 128 product, and a rounding division of a
  128-bit or 256-bit value by `10^k`. **Owner's decision A2: both are built and measured against
  the row engine, and declined with a reason only if they lose.**

**The arithmetic, from the decimal and division readings.**

* **360, add and subtract: the capped shapes apart from the uncapped.** When the uncapped result
  precision exceeds 38, the exact sum can pass `2^127` while Spark's rounded result fits:
  `DECIMAL(38, 10) + DECIMAL(38, 10)` with both operands at `10^38 - 1` is `2 * 10^37` at scale 9,
  and a plain 128-bit add wraps. The capped shapes need the 129th bit (Trino's `addWithOverflow`
  returns it) feeding 363's unsigned division, or Velox's whole-and-fraction split, so they land
  with 363; the uncapped shapes, where the result precision Spark picks holds every exact sum, land
  first, with the lemma that a wrapped sum of two values below `10^38` fails the `10^38` check,
  which removes the overflow flag from them. The rescale-up of the coarser operand carries a batch
  guard, and the worked case above is a test.
* **361, the widening multiply.** The Vector API has no multiply-high and `MUL` on a `LongVector`
  keeps the low 64 bits, so the 64 by 64 to 128-bit product is built from 32-bit limbs. The
  decimal reading read HotSpot's `vectornode.cpp` (lines 2189 to 2237 in its copy): JDK 25 emits
  one `vpmuludq` for a 64-bit lane multiply only when both inputs are visibly masked to 32 bits or
  shifted right by 32 or more, so a broadcast constant limb must be written as
  `broadcast(c).and(0xFFFFFFFF)` or it falls to the seven-instruction emulation; the
  `dev/varka_emit.sh --asm` check covers the constant-limb case and must survive GVN. The product
  is built on Arrow's carry-free limb order with the JDK's `unsignedMultiplyHigh` sign correction:
  no carry compares, no `abs`, no 128-bit negate.
* **362, the full multiply.** If `p1 + p2 + 1 <= 38` the product fits 128 bits with no check, a
  static shortcut. Otherwise the 256-bit product is built by column summation of sixteen
  `vpmuludq`, no Karatsuba and no compares, then rounded by 363.
* **363, the rounding division: a magic multiply, not a reciprocal.** For a compile-time divisor
  the Granlund-Montgomery magic with Lemire, Bartlett and Kaser's exact bound beats Moller and
  Granlund's reciprocal-and-adjust: sixteen `vpmuludq` in one parallel block for a 128-bit dividend
  against seven to fourteen over one or two serial steps with blends and a normalisation shift -
  near a tie in count, shallower in dependency depth, smaller in proof. The dividend is pre-shifted
  by `k - 1` (or `k`) and divided by `5^k`, which keeps the divisor one word up to `k = 27`, and
  `HALF_UP` needs no remainder: `(floor((n >> (k - 1)) / 5^k) + 1) >> 1`. The 256-bit dividend of a
  capped product costs 49 to 64 limb products. No engine does this - all use long division - and
  the row borrows Trino's last-stage rounding and DuckDB's overflow-free `HALF_UP`. The lemma that
  makes the design right: Spark multiplies under `MathContext(39, DOWN)` (`Decimal.scala` 524 and
  597) and rounds `HALF_UP` afterwards, and that equals one `HALF_UP` of the exact product. The
  rule is read from the node's resolved result type - what the analyzer computed under the
  session's `allowPrecisionLoss` - and never from the session configuration at kernel-compile time
  (Comet's bug #4124). Spark's SPARK-45786 random test, which Gluten excludes, is ported; the `k =
  39` multiply is declined or constant-folded. The fork's `Decimal.scala` (line 593 on) already
  has the SPARK-45786 fix, so the lemma holds against it; spark-rapids-jni deliberately keeps the
  earlier double rounding to match older Spark releases (`READING_RAPIDS.md` 2.1), which is why
  the port pins the fork's behaviour. The product is compared with `10^38` at full 256-bit width
  before any narrowing: arrow-rs's unchecked multiply keeps the low 128 bits, and a product near
  `10^61` wraps into range and passes (`READING_ARROW_RS.md`).
* **Where the bounds live.** `VarkaRangeAnalysis` is int-only, so every "no intermediate passes
  `2^64`" claim of 361 to 363 is an SMT lemma in this milestone; extending the analysis to long
  lanes is `VARKA-104.md` 3.2's question and not taken here.

**The proofs (359 to 364).** Every 128-bit claim is stated over integers, not bit-vectors, as
`sql/varka/proofs/README.md` prescribes ("Why integers, not bit-vectors"), in two layers: the ring
identity alone, and the carry network with every limb product a free variable in `[0, (2^32 -
1)^2]`. Both layers are linear and expected to decide at full width for 361, 362 and 363 - 363's
constants are linear because the multiplier is a constant - so no claim rests on a reduced width;
a reduced width stays only as a cross-check for 361 and 362. The sign correction is stated as
`ite(x < 0, y, 0)`, never as a bitwise AND on a negative. The proofs reading's survey of Fiat's
bugs (carries in 8 of 26) argues against sampled regions for a carry claim, which is why the
carries are proven and not sampled.

**Casts and rounding below 19 (350, 351).** A rescale down uses DuckDB's signed-half add before the
constant division, with the `|u| + 10^k / 2 < 2^63` headroom stated in the SMT proof, and `fma(-q,
10^s, u) == 0` is a one-operation exactness test for a tie decision. The constant division has a
gap to close: `ConstDivide.EXACT_DIVIDEND_BOUND` is `2^52` (`VarkaVectorIR.java` 496), the AVX2
magic form's identity under the double route, and an unscaled `DECIMAL(18, s)` reaches `2^59.8`.
The route is a pre-shift by `k` and a `ConstDivide` by `5^k`, which needs no guard for `k >= 8` on
a `DECIMAL(18, s)`; for `k = 1..7` a `GuardedRange` of `2^(52 + k)`, or a 64-bit limb magic (four
`vpmuludq`, exact everywhere, unmeasured) as an option arm measured against the double divide.
`5^k` for `k = 1..18` joins the long-lane proof in a second proof file run under cvc5, since the
existing file already costs Z3 28 s on the laptop and 59 s on the runner. `round`, `bround` and
`truncate` over a `DECIMAL(18, s)` give precision 19 for any target scale at or above `s`, so 351
depends on 360 and the 128-bit store, and a negative-scale `round` raises on overflow in every
mode. Two tests other engines' gaps suggest: a decimal equal to `Int.MinValue` or `Long.MinValue`
cast under ANSI (Comet raises, Spark returns), and the JDK dependence of `Double.toString` stated in
the oracle's header (`SCOPE_STANDARD_MODE.md` 3.6).

**What the Vector API offers for it**, checked in JDK 25.0.4's `VectorOperators`: unsigned
comparisons (`ULT`, `ULE`, `UGT`, `UGE`), so a sum's carry is `ULT(lo, a.lo)`; unsigned `UMIN`,
`UMAX` and saturating `SUADD`; `ZERO_EXTEND_I2L`; shifts and masks. There is **no multiply-high on
any lane type**; the limb construction above is the consequence.

**Semantics, ANSI and LEGACY.** A result that overflows its type (`CheckOverflow`: more digits than
`p - s` in the integer part) is null under LEGACY and TRY and `NUMERIC_VALUE_OUT_OF_RANGE` under
ANSI; for the 64-bit form with a result within precision 18 that cannot happen, and 349 proves it,
while the lane pair checks against `10^p` per lane group (359). A cast to a smaller scale rounds
`ROUND_HALF_UP`: on an unscaled value, the signed half added and a truncating division by `10^k`,
the constant division above below 19 (350) and 363's magic-number multiply above. Decimal to
integral truncates toward zero and keeps the low bits under LEGACY (`Decimal.toLong` is
`BigDecimal.longValue`), raising `CAST_OVERFLOW` under ANSI - `NUMERIC_VALUE_OUT_OF_RANGE` is the
class for a decimal target - by the two rules 365 describes.

**Decimal division is out of milestone 8: owner's decision, 14 October 2026.** `/`, `try_divide`
and `div` over decimals decline with the reason "owner's decision, 14 October 2026"; the census
records the three pairs in its reason column and leaves them out of the done-when denominator. No
cast is affected: a cast to a smaller decimal scale divides by a power of ten, which is the rounding
rescale 350 and 363 build, not the division operator. Decimal `%`, `mod` and `pmod` are
remainders, not quotients, and stay in.

**The census and the casts.** Both count the decimal as one type, so the move from 18 to 38 changes
no count: the census has 881 pairs, 68 of them decimal, and the cast matrix 106 legal pairs. What
changes is that a decimal pair counts fused only when it fuses at every precision up to 38, both
representations, which is what rows 359 to 364 are for; the decimal entries' rows say so.

### 2.9 The existing types

The rows that fill in the six types run beside the new lanes, not after them, and most are the
cheapest work in the milestone: 104 (`BIGINT` arithmetic, its plan written and its overflow test
already lane-generic), 30, 95 and item 33 (`div`, `%` and `pmod` over the integral lanes, and a
column multiplier), 103 (day-time interval arithmetic, extracts and `make_dt_interval`), 39 (`date
- date`), 89 (the year-month divisions), 335 (item 32's bitwise family, the emitter already
emitting `AND`, `XOR` and the shifts), 336 (item 34's null-safe equality), 333 (item 55's int
branches, item 94's CASE without ELSE, and the rewritten conditionals `nullif`, `nvl2`,
`zeroifnull`, `nullifzero`, `between`), and 334 (item 93's `IS NULL` over a predicate, whose local
branch exists with seven failures to understand). *16 October 2026, since the answer to question 4
of section 7:* these rows no longer run beside the new lanes. 104 and 334 are foundation rows of
wave 0, and 333 and 335's first part stay there because they need nothing new; 30, 95, 103, 39,
89, 336 and 335's second part are function rows, which land after every lane (4.1). The integer
reading settles four of them:

* **30.** `div` over `INT` is the existing long-lane divide over two exact widening casts, since
  `IntegralDivide.inputType` is `LongType` (with the decimal and interval types) and type coercion
  widens an `INT` first - not the "int in, long out" node item 33 describes, which carries a dated
  correction. `pmod(-1, Int.MinValue)` returns `Int.MaxValue` in this fork (`MathUtils.pmod`'s
  comment, tracked by SPARK-59934); Varka reproduces it and the SMT goal states the addition as
  wrapping. A null dividend with a zero divisor is null even under ANSI, a coverage row.
  `emitConstDivide` is reused for `%` and `pmod` by a constant; `TINYINT` and `SMALLINT` dividends
  fit the int-lane magic bound of 46341; `pmod` by a power of two is an AND. An option for a
  literal divisor only: a full-range long-lane magic from four `vpmuludq`, a `MulHiDivide` sibling
  of VARKA-149, measured against the current lowering. The text above is silent on a divisor that
  is a column, and no node of the emitter divides by one (`VarkaVectorIR` has `ConstDivide` and
  `BoundedDivide` only; the census lists `div`, `%`, `mod` and `pmod` as not fused today). The
  row states it, after reading Monniaux and Pain's Coq-proved division through doubles
  (`READING_M8_PAPERS.md` 1): a `BIGINT` column divisor takes their 64-bit algorithm over the
  whole range - a correctly rounded `float` reciprocal of `b`, one refinement by two `fma` to
  about 46 bits, a first quotient and its exact integer remainder, a second quotient from that
  remainder, one correction where the last remainder is negative, `b = 1` and `b >= 2^63` (after
  the signs are taken off, the case of `Long.MIN_VALUE`) chosen by a blend, the sign put back
  after - so no batch declines for size, and `div`, `%` and `pmod` over one divisor share the
  reciprocal. Its proof assumes round-to-nearest conversions from double to integer; Java's
  truncate, so the conversion is emulated (the 2^52 add-and-subtract below `2^52`, the identity
  above it) and stated as its own lemma, which keeps the paper's sequence of operations and with
  it the proof; the kernel is written from the paper, not from CompCert's code, and cites the
  proof's files (`FPDivision64.v`). The double route `a - trunc(a / b) * b` (exact for
  `|a| < 2^52`, `skills/vector-api-and-width.md`) stays for `INT`, where it is exact for every
  operand, and Sneller's second iteration (`READING_SNELLER.md`) is superseded. On AVX2 the
  conversions and the 64-bit multiply are emulated by the JDK, so the algorithm is measured
  against the scalar loop there and declines with a reason if it loses. A `TINYINT` or
  `SMALLINT` dividend is below `2^24`, so `trunc(a / b)` in a `float` lane is exact for it by the
  same dividend bound; the 8-bit and 16-bit notes of `READING_0X80.md` measured 6.7 to 15 times
  the scalar divide with that form.
* **335.** `bit_count` is a 64-bit popcount of the sign-extended value (`BitwiseCount` calls
  `Long.bitCount` on the widened byte, short or int), so `pop32(x) + ((x >> 31) & 32)` serves byte,
  short and int on the int lane, the native byte arm pays a widening, and `bit_count(bigint)` under
  another node waits on row 28; on AVX2 the JDK already emits the nibble-table algorithm (about 1.75
  instructions per int row by the reading's count) and one instruction only with VPOPCNTDQ, so the
  row claims no speed-up for the count alone; Velox and Gluten ship the wrong rule. A `TINYINT` or
  `SMALLINT` shift is an int shift over a cast, a long shift by a column is mixed width, and the
  vector shifts mask the count as Java does. The bitmap functions are two blended ANDs and a signed
  shift; `Long.MIN_VALUE` is a test.
* **336, item 34 answered.** `<=>` is a condition node over the validity words, `kT = (vA & vB & E)
  | ~(vA | vB)`, the same kind as 334's `IS NOT NULL` over a predicate, so 336 follows 334 and no
  null-tolerant value node is added. `p IS TRUE` parses to `EqualNullSafe(p, true)`, so the node
  takes a predicate child; and the `IS NULL` partition of `a <=> b` must be empty, which is the
  row's partition test.
* **333.** Identity substitution for `greatest` and `least`, with real `MIN_VALUE` and `MAX_VALUE`
  operands beside nulls as named cases (DuckDB's sentinel bug), and a check of what plan `nullif`
  reaches after the `With` rewrite before it counts as one shape.

### 2.10 Tests and proofs as the lanes land

Every lane row extends the oracles in the same pull request: the coverage table (the row engine as
the oracle for every shape), the reference evaluator's semantics for the type, the IR fuzzer's
grammar and the Spark-level fuzzer's columns. 352 closes what the lane rows cannot do alone, and
its rewritten list is the reading's: (a) the partition check is the TLP `WHERE` oracle, in place
since VARKA-262, over the milestone's shapes only - NaN and `-0.0` pairs, bare `BOOLEAN` predicates
with NULL leaves, narrow-int literals whose three partitions are three
`UnwrapCastInBinaryComparison` rewrites, decimals across the 18 to 19 boundary, `TIMESTAMP_NTZ`
against `DATE`; (b) its ANSI gap, that it returns nothing on any error, so a disagreement among the
three parts on raising is a finding; (c) the generators keep `-0.0`, NaN with payloads and the
infinities on (Comet turns `-0.0` off), sweep the decimal shapes `(9, 2)`, `(17, 7)`, `(18, 0)`,
`(18, 9)`, `(18, 18)`, `(19, 0)` and `(38, 10)` at null densities 0, 0.1, 0.5 and 1, derive
decimal operand types from Spark's result rules, and draw casts from `m8/casts.tsv`; (d) two
cross-mode properties - "TRY is null exactly where ANSI raises, and equal elsewhere" for all 98
pairs and the `try_` arithmetic, and "ANSI equals LEGACY where ANSI does not raise", with its one
named exception, the double rule of 2.8 above precision 18 (at every precision since the answer to
question 3 of section 7, 16 October 2026); (e) the sampled regions of 2.2, 2.7 and
the matrix, and the tie windows for `s <= 4` and `s <= 17` with an assertion that no tie occurs
outside them; all under the option matrix. A lane's first pull request also keeps
`emitted_bytes.json` unchanged for every existing shape: adding a lane must not move a byte of an
int or long kernel.

Two rows beside it (owner's decision A4). **368** runs Spark's own `CastWithAnsiOffSuite`,
`CastWithAnsiOnSuite`, `TryCastSuite` and `HashExpressionsSuite` with the Varka evaluator forced on
and constant folding excluded, recording fused or declined per `(from, to, mode)` as Gluten's
tracker does, and diffs the record against the census and the matrix nightly. **369** is the
fuzzer's count arm: over a large cached fixture, `COUNT(*) WHERE p` against `COUNT_IF(p)` and the
three-part sum reaches the batches and lane positions twelve rows cannot, and localises a
disagreement to the filter plumbing against the projection plumbing; kernel against kernel once 27
lands; the Spark differential stays the ground truth.

The exhaustive sweeps cost machine time, stated against the calibration table in
`skills/testing-and-debugging.md`: the int32 lowerings' exhaustion takes about thirteen and a half
minutes of each nightly (`m7/PLAN.md` 1), so each 2^32 sweep this plan adds - a `SMALLINT` operator
pair, a `FLOAT` source cast - is predicted at that order per sweep and, by the table's observed
ratios, up to twice that once the harness's reads are counted. They run in the nightly and in idle
windows; PR CI runs a fixed sampled slice of each. The proof files have a budget of their own (risk
5).

### 2.11 The post

*One engine, every number* is for JVM and database engineers, as milestone 7's own post was, and it
is built from the census rather than from a benchmark: the share it states is 1.1 item 2's, per type
and per family, and its substance is how a kernel can be shown to agree with Spark across thirteen
types - exhaustion where the width allows, SMT for 64- and 128-bit integer rules, the fuzzers for
the rest, and the cases declined with their reasons. For readers who want the floating-point
ground under it, the post points to Goldberg's D.3.2, D.3.5, D.4.1.4, D.4.3, D.5.2 and D.7 and to
the Handbook of Floating-Point Arithmetic's 2.6.1 and 4.2, as the IEEE reading suggests. Whether a
per-type benchmark joins it is decided later by the owner (section 7).

## 3. Task breakdown

Task numbers continue the single sequence from 328, the next free number at commit `90b8871b705`
on 14 October 2026 (the highest in any plan, table or branch was 327); rows taken from the
catalogue keep theirs. Sizes are the house's small, medium and large.

| task | what it is | where it came from | size |
| ---: | :--- | :--- | :--- |
| 328 | **Done** (`VARKA-328.md` 9, 19 October 2026): the census and the cast matrix are checked in the docs job and the pre-commit hook, `Cast` holds the matrix's legality over all 169 pairs, the census credits a class only with the types its own node takes in a coverage row and holds the fused cast pairs to the rows that cast them, and 27 coverage rows prove what the documentation claims; fused pairs 96 to 122, fused cast pairs 7 to 6 (the identity unmarked, `int` to `ym` narrowed to the MONTH unit, its YEAR casts to row 332), and `dev/varka_catalogue_move.sh` fixed to leave milestone 8's own reading notes in place). The census and the cast matrix kept true: `dev/varka_census.py --check` in the linters' job; a suite holding `m8/casts.tsv`'s legality to `Cast.canCast`, `canAnsiCast` and `canTryCast` over every pair, under Comet's four-way rule - a legal pair has a test, an illegal and an identity pair have none, and a declined pair is exactly a pair without a coverage row, per mode; the coverage rows that prove what the documentation already says fuses (year-month comparisons and `IN`, null tests on every existing type), and a single-expression coverage row behind every fused claim, so the census's check reads one class per row; `dev/varka_catalogue_move.sh --check 8 9` beside them. **Done when** the three checks run in CI and the census's fused count moves only with a coverage row | this plan, 2.1 and 2.2 | small |
| 375 | **Planned** (`VARKA-375.md`, 16 October 2026; *refreshed 21 October 2026, `VARKA-375.md` 0, at `a429af749f4`: the census confirms `fromLong` and `toLong` intrinsic with no allocation on the laptop and the EPYC 7763 runner for bytes and ints at every width and for longs at 512 and 256 bits, and on the Neoverse N2 runner at its 128 bits, and refutes "every host" for one cell, long at 128 bits on x86 (116 and 68 bytes a row, `m7` row 374's two-lane mask), which the plan's construction would not rescue since it ends in a long compare that makes the same mask; 335a built the `lanewise` shift by a vector the construction would reuse and no mask-from-word helper; the `UseSVE=1` run was never made, the canary workflow has no JVM-flags input, and the build half stays unadmitted until it is*; the admission check: the Arm runner has BitPerm and `x86.ad` admits the node under AVX with BMI2, so `fromLong` is intrinsic on every host the milestone measures; a host without BitPerm is the same runner under `-XX:UseSVE=1`, which clears the feature in `vm_version_aarch64.cpp`; that canary run and its allocation reading are the first step, and admit the build half only if the fallback allocates or is slower; *owner's decision, 16 October 2026*: the simulation is accepted for the allocation half, and the timing half on a real NEON-only or SVE1 host stays unbuilt until such a host exists). `VectorMask.fromLong` on Arm (section 10.1 item 2): JDK 25 intrinsifies `VectorLongToMask` on aarch64 only with SVE2 BitPerm (`aarch64_vector.ad`), every kernel builds its validity masks with it, and 331's boolean leaf will; on a NEON-only or SVE1 core it is the Java fallback. In this milestone because 1.1 item 5 keys bounds by the Arm library, 367 runs there, and 331 rests on the call. Find whether the Arm runner has BitPerm and what a validity-reading kernel allocates there; if the call falls back, build the mask from the word by a construction every host intrinsifies (a broadcast, a shift by the lane index, a compare) behind an emit option, measured on x86 and Arm. **Done when** the Arm runner's feature line and a validity-reading kernel's allocation rate there are recorded, and either `fromLong` is shown intrinsic on the hosts the milestone measures or the alternative ships with its measurement, the bytes oracle unchanged under the x86 defaults Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): `fromLong` is intrinsic on bytes, ints and longs at 512 and 256 bits and on bytes and ints at 128; on longs at 128 bits it is the Java fallback (`m7` row 374). | this plan, 10.1 | small to medium |
| 376 | **Done** (`VARKA-376.md` 9, 20 October 2026, on the laptop: 85 cells at 512, 256 and 128 bits, a verdict in each, 85 intrinsic with no allocation at 512 bits and 84 of 85 at 256; the 128-bit column has 14 fallbacks, `POW` has no vector stub below 512 bits, and no intrinsic loop allocates (the fallbacks allocate 40 to 220 bytes a row); `VarkaOperatorCensusSuite` forks one JVM per cell and width in 25 s, and `varka-width-audit.yml` runs it on both runner classes; the EPYC 7763 and Neoverse N2 columns came from that workflow's dispatch, run 39400866095 of 20 October 2026, and are committed under `results/`: the AVX2 runner refuses `L2D`, `D2L`, `L2F` and `POW` at 256 bits, the Neoverse N2 only `TANH`; `VARKA-376.md` 9.2). The intrinsic and allocation census of this milestone's operators (section 10.3 item 3): one probe per family the rows use - byte and short widening and narrowing, the floating compares, `L2D`, `D2L`, `F2D` and `D2F`, lanewise `FMA`, the math operators, unsigned long compares, the decimal de-interleave's rearranges, `VectorMask.fromLong` and `toLong` per species - at 512 bits (the laptop), 256 (the EPYC 7763 runner) and 128 (the forced width and the Arm runner), each asserting its allocation rate as `VARKA-55.md` does, with the two-species case 329's arms create in one JVM. In this milestone because two of its designs already rest on operators the match rules refuse on a CI host (30's conversions on AVX2, 375's mask on Arm). **Done when** the table is committed beside the record's operator table in `skills/vector-api-and-width.md`, and every row whose design uses an operator the table finds non-intrinsic on a host names its form for that host | this plan, 10.3 | small |
| 377 | **Planned** (`VARKA-377.md`, 16 October 2026; the admission check: no benchmark covers a new type; the classes are Java in sql/core on `VarkaBenchmarkBase`, both arms the row path today; `benchmark.yml` is dispatch-only and x86-only, pinned with `expected-cpu`, the 128-bit arm by `extra-java-options`, results committed from the artifact as runner files). Baseline benchmarks for the new types before their kernels (section 10.3 item 6): one class per family - `TINYINT` and `SMALLINT`, `BOOLEAN`, `TIMESTAMP_NTZ`, the floating types, decimal at precisions 9, 18 and 38 - over a comparison, an arithmetic chain, a cast and a choice, each measured on the unchanged code in its own pull request, the house rule for a baseline, so that every lane row reports its speed-up against Spark's whole-stage-generated row path as a diff. In this milestone because no row measures the new types against Spark, and 2.8's expected decimal speed-up is unmeasured. **Done when** each family's class and both widths' results are committed before the family's first lane row, and that row's measurement diffs against them | this plan, 10.3 | small |
| 383 | **Planned, shrunk; half (a) admitted by the laptop reading (21 October 2026)**: step 2 of `VARKA-383.md` 0 read on the idle laptop (1-minute load 2.82 to 0.36, sanitizer off, `performance` profile, 12 forks, `--after 64 --forks 6 --widths 64,32 --prewarm on`): the long lane past position 15 released **42 of 144 at 512 bits and 38 of 144 at 256 bits**, 41 and 38 with every loop method at tier 4, position 0 at 0 of 6, against 257's 43 of 144 on a loaded machine, so there is no sign of a load effect (rows 389 to 397 sit between the readings and are confounded with it) (`VARKA-383.md` 9.1, `results/VARKA-383-long-lane-history.txt`); prewarm-off arm not run; the int lane at 256 bits released 43 of 144. Next per 3.2: R3 (raised trap limits) and wave 2's replay can run on the laptop with `--jvm`; R1 and R2 on runners need the `benchmark.yml` history mode and the owner's go-ahead; arm A is built only after R3 names the mechanism; step 1 (the CI count) and the done-when are open, so the row is not Done. **R3 (21 October, `VARKA-383.md` 9.2): the raised trap limits left the long lane at 45 of 144 (42 before), so 9.6's trap sums are not the mechanism and arm A is not built; a replay of the released draws shows 35 to 56 repeated C2 `profile_predicate` deoptimisations of one `loopMasked0` bytecode per released kernel, unchanged by the limits, and the next reading dumps that class. Dumped (`VARKA-383.md` 9.3, 21 October): the bytecode is the backedge `goto` of the emitted main vector loop, and `-XX:-UseProfiledLoopPredicate` takes both released draws to COMPILED with no `profile_predicate` trap, at 512 bits on one fork; the next step is that flag at the 144-draw reading and a source-level loop shape that does not need it.** *Earlier:* (`VARKA-383.md` 0, 21 October 2026, against `a429af749f4`: half (b), a prewarm that came after the JVM's first kernels, is answered, since rows 389, 391, 395 and 397 run it before any vector code of the engine (`VarkaVectorTemplateWarmup.beforeVectorCode`, whatever the session's warm-up setting), compile the wrappers at tier 3 in its rounds and fail a test when a called method gets under 2,000 calls; what stands is the long lane after a history, unread since 16 October, read first on the laptop at both widths (`--widths 64,32`) and on runners only if that admits or is ambiguous, `convertShape` with `L2D` and `D2L` that the prewarm still does not call, and a count of the end-to-end suite's CI results since `568582a9f77`; row 405's non-reproduction and 407's C1 timing change no step, 401 (PR #953) is pending; the 16 October text follows). *Planned (as filed, 16 October 2026):* ( the admission check, by reading the code and the failed CI job's log: the end-to-end suite's released shape is an int-lane kernel (`DATE`, `Int256Vector`) at a 256-bit runner's width, every loop at tier 4 and boxing at `IntVector.lanewiseTemplate`, in a JVM whose sessions kept the warm-up off for half an hour of Varka suites before the first warm-up ran the template prewarm; so the row has two halves, 257's long lane after a history and a prewarm that comes after the JVM's first kernels, each reproduced on runners through a history mode of `benchmark.yml` before its fix is admitted). Long-lane kernels released after a kernel history (added 16 October 2026, wave 0; its plan file is a later step). `m7/VARKA-257.md` 9.2 (on PR #873, not yet merged) found that at the host's width with the template prewarm on, the warm-up released long-lane kernels at the 30 s deadline still allocating in 43 of 144 cases at history positions 16 to 63 and 7 of 72 at positions 4 to 15, while the prewarm cut the int lane's releases past position 16 to 15 of 144; every long-lane release had C2 code in use for every loop method, compiled and still allocating. Those readings were taken on the laptop at a machine load of 14 to 30, so the first step is a reproduction on an idle GitHub-hosted runner (through `benchmark.yml` or a workflow dispatch, after the owner's go-ahead), then a fix of the long-lane template prewarm, or whatever the reproduction shows. It folds in `m9/SCOPE.md` item 77 (owner's decision, 16 October 2026): `VarkaWarmupEndToEndSuite` failed on two pull requests that day with `RELEASED did not equal COMPILED` on one shape (`calls=108464` and `calls=109608`), which is 257's cause: the failed job's log (fork run 38912533464) reads `runNanos=120151168042` and `lastProbeBytes=197328`, a release at the suite's 120 s deadline with the last probe still allocating (`m9/SCOPE.md` item 77's notes of the same day; an earlier reading of one second came from a cut-off failure annotation). In this milestone because `DECIMAL` up to precision 18, `DOUBLE` and `TIMESTAMP_NTZ` are long-lane kernels, so every lane row's measurement and the warm-up's promise for them depend on long-lane kernels compiling clean after a history. **Done when** the runner's reproduction is recorded, the cause it shows is fixed or recorded with its reason, the long lane's release rate after a history is read again on the runner, and `VarkaWarmupEndToEndSuite` passes in a full CI run of the sql module | `m7/VARKA-257.md` 9.2 and 9.5; `m9/SCOPE.md` item 77 | medium |
| 28 | **Done** (`VARKA-28.md` 9, 19 October 2026): a kernel holds both lanes, driven at the long lane with its int values in the low half of the int species of the same width, not the pair representation the plan registered (`VARKA-28.md` 3.2); `WidenLane` added and `NarrowLane` admitted below a root; `CAST(int AS bigint)` fuses in every mode and `CAST(bigint AS int)` in LEGACY and ANSI, ANSI declining past the int range; the promotions under the comparisons, the picks and the conditionals fuse, conditions of the two lanes meet on the long one, `i + l` waits on 104's long `Add`, and `hour(t) + 1` fuses; the existing shapes' bytes unchanged; the A/B failed prediction 1 as first emitted - half the long lane's rate at 512 bits, a sixth at 128 - from a conversion C2 did not intrinsify and masked int loads, and after the fix a mixed kernel matches or beats the long lane at every rung but 128 bits in L2, 0.91 of it, `VARKA-28.md` 9.3). Lane-width conversion: one kernel over two widths, the int-to-long cast and the narrowing back. **Done when** `VARKA-28.md`'s own list holds, with the mixed-width trees of this milestone's casts admitted | catalogue item 39 table; `VARKA-28.md` | medium |
| 104 | **Planned** (`VARKA-104.md`, 18 September 2026, brought up to date 16 October 2026: the arms and `arithOver` are in `VarkaNodeCompiler.java`, `arithOver` shared with the interval arms; the lattice's long refusal unchanged; division is row 30's; option B not intrinsic on AVX2, option C the limb product 361 reuses). `BIGINT` arithmetic in the three modes, closing VARKA-30's int64 half. **Done when** `VARKA-104.md`'s list holds | catalogue item 39 table; `VARKA-104.md` | medium |
| 30 | The integral division family: `div`, `%` and `pmod` over `INT` and `BIGINT`, a zero divisor null or raised, by 2.9's reading - `div` over `INT` as the long-lane divide over two widening casts, `pmod(-1, Int.MinValue)` as `Int.MaxValue` with the SMT goal wrapping, a null dividend with a zero divisor null under ANSI, `emitConstDivide` reused for a constant divisor, `pmod` by a power of two as an AND, and the four-`vpmuludq` long-lane magic as a measured option for a literal divisor, and a column divisor: `BIGINT` by Monniaux and Pain's 64-bit algorithm over the whole range, its round-to-nearest conversions emulated as a lemma of their own (`READING_M8_PAPERS.md` 1), `INT` by the double route, `TINYINT` and `SMALLINT` in the `float` lane, exact below `2^24` (`READING_0X80.md`). **Done when** each fuses in the three modes with the row engine's error classes, proven by exhaustion at 32 bits and SMT at 64, the `BIGINT` column divisor by the paper's proof plus the conversion lemma and tested at the paper's corner cases (`b = 1`, `b >= 2^63` after the signs, `b` either side of `2^42`, `Long.MIN_VALUE` against `-1` and `1`) and over a large random draw, and its cost against the scalar loop measured at both widths and on an AVX2 host, where its four conversions are not intrinsic (`x86.ad` needs AVX512DQ for a long to double and a double to long vector cast), so the form is chosen per host statically: the algorithm where the conversions are intrinsic (376 says where), and on AVX2 either a full-range conversion built from 32-bit halves with its own rounding lemma, measured, or the bounded double route with its decline (section 10.1 item 4) | catalogue item 39 table; `m5/PLAN.md` 2.4; item 33 | medium |
| 95 | An int multiply by a column, and `i % k`. **Done when** both fuse with the checked-multiply rule, or the record says why not | catalogue item 39 table; `m5/PLAN.md` 2.30 | small |
| 103 | Day-time interval arithmetic, multiplications and divisions, the extracts and `make_dt_interval`: `extract(DAY FROM dt)` exact over the whole long by the shift split of 2.6 over the magnitude with the sign restored (a truncating quotient, not 340's floor, so the magnitude and sign steps on top of the split's three lane operations), hours and minutes over the byte output, seconds over the decimal lane, the partial sums of `make_dt_interval` checked in Spark's order, `HALF_UP` on a double factor tested at ties for both signs, at `2^53` and at the long edges with Spark's own IEEE operations. **Done when** `m5/PLAN.md` 2.38's list fuses (its "exact only under `2^52`" corrected by a dated note), each division exact under its stated bound or guarded, `java.time` the oracle at the edges | catalogue item 39 table; `m5/PLAN.md` 2.38 | medium |
| 39 | **Done** (`VARKA-39.md` 7, 20 October 2026): `date - date` is fused, as one compiler arm composing `WidenLane`, a long `SUB`, a `GuardedRange` of `+-106751991` (`Long.MAX_VALUE / MICROS_PER_DAY`, the exact edge of `multiplyExact`) and a `WRAP` multiply by `MICROS_PER_DAY` on the long lane; no new IR node, emitter arm or evaluator change. A day count past the bound, which includes every pair whose int difference overflows, declines the batch and the row engine raises Spark's own error; the legacy `CalendarInterval` variant declines with its reason; `datediff` is unchanged. The long-lane `WRAP` multiply was not this row's first: the TIME conversions already emit it. Proved at both widths by the emitter suite (null patterns against a `Long` oracle, both overflow paths and the boundary in both directions, a null beside a value at each edge), the compiler suite and a differential suite that runs both engines on the raised errors | catalogue item 39 table; `VARKA-39.md` | small |
| 89 | **Done in part** (`VARKA-89.md` 9, 21 October 2026): `ym / num` for a literal `Integer`, `Short` or `Byte` `num` with `|num| >= 2` is fused by the int-lane HALF_UP form (`q = ConstDivide`, `r = m - q*d` wrapping, one step away from zero iff `|r| >= ceil(|d|/2)`; no widening, no guard), `ym / 1` is the operand, and `CAST(ymy AS INT)` and `CAST(ym AS INTERVAL YEAR)` are `ConstDivide(v, 12)` and that times twelve; no new IR node, emitter arm or evaluator change. The correction held: swept against `IntMath.divide` HALF_UP at all 2^32 dividends for 409 divisors (every byte divisor, powers of two and their neighbours in both signs, the int extremes), 11 min 46 s (a nightly-only long sweep, outside the gate's sweep step), proved per divisor in `sql/varka/proofs/ym_divide.smt2` (Z3 about 4 s; the divisor symbolic as well is not solved), plus five rows in `verify_ym_division.py`. Remaining: `ym / -1` declines (a negation was built and reverted: the row engine's error for the declined batch lacked Spark's SQL context), a zero and a long literal divisor decline, `extract(MONTH)` waits for 329, column, fractional and decimal divisors are 30's, 342's and 349's. The year-month divisions beyond `extract(YEAR FROM ym)`. **Done when** `VARKA-89.md` 0.5's list holds | catalogue item 39 table; `VARKA-89.md` | small |
| 333 | **Planned** (`VARKA-333.md`, 16 October 2026; the admission check: `INT` has no value leaf by design, so the any-type positions get typed arms; `CASE` without ELSE, `nullif` and `nullifzero` need a typed null leaf, which `Analysis.nullsFromValidInputs` already dispatches to the masked body; `IN` is gated to `DATE` and year-month; which shapes compile today is one `dev/varka_emit.sh` run and one optimized-plan check, the first step). The choice and null shapes on every existing type: item 55's int branches, item 94's CASE without ELSE, `coalesce`, `greatest`, `least` and `IN` on each existing type, and the rewritten conditionals `nullif`, `nvl2`, `zeroifnull`, `nullifzero`, `between`; identity substitution for `greatest` and `least` with real `MIN_VALUE` and `MAX_VALUE` operands beside nulls as named cases, and the plan `nullif` reaches after the `With` rewrite checked before it counts as one shape. **Done when** each fuses on all six existing types with a coverage row, `date_add(d, i)`'s offset rule unchanged | items 55, 94 | small |
| 334 | **Done** (`VARKA-334.md` 9, 19 October 2026): the branch's seven failures were one analysis check older than the branch, which refused a condition under `IsNotNull`, not a wrong answer; the design is admitted and fuses `IS NULL` and `IS NOT NULL` over every predicate row whose predicate can be null, 41 of 61, as filters and as `CASE` conditions, with fourteen coverage rows, the partition oracle querying `(p) IS NULL` itself, a `CASE` sweep over every predicate row, the grammar's production and a cost feature of its own. Found on the way: a guard under such a test was dropped, now kept, with the kernel masked-only so both bodies share its verdict, as is a kernel with a materialized calendar prefix under such a test, whose dense body never stored it (a wrong answer in IR the compiler does not build); the dense skip now reaches the slot plan; and a latent month step stored for nothing in a materialized prefix, which the grammar's redraw surfaced, is fixed with its shrunk shape pinned). `IS NULL` and `IS NOT NULL` over a predicate. **Done when** item 93's list holds, the seven failures of its branch understood | item 93 | small |
| 335 | **335a done, 335b open, two of its items done** (`VARKA-335.md` 9, 19 October 2026): two IR nodes, `BitOp` (`&`, `\|`, `^` and the three shifts, `~x` as `x ^ -1`) and `BitCount`, fuse the operators over `INT` and `BIGINT`, the shifts over `INT` by a literal or a column and over `BIGINT` by a literal, `bit_count` over `INT` and as a `BIGINT` output, and both bitmap functions, a blend of `v - 1` and `-v` under a mask or a logical shift, proven for every long in `proofs/bitmap.smt2`; every int through `~`, `bit_count` and each shift against Spark's own expressions in `VarkaEmitterBitwiseSuite` (opt-in, nightly), a 2^24 slice on every build; 18 coverage rows, census pairs 122 to 146 and fully fused entries 15 to 23; the emit cost model refit on the grammar with the bitwise arms, on the owner's word of 19 October 2026 (margins 0.205 to 0.155 bytes, 0.176 to 0.206 sites; the 64-tail ladder 6 to 8 loop methods recomputed, 4 to 5 materialized, benchmarked in `VARKA-335.md` 9.2a; *21 October 2026, row 409: re-paired on the fixed benchmark, the default arm is 9.6% (512-bit), 11.9% (256) and 12.3% (128) faster than the base and the recomputed arm 19.0%, 16.1% and 16.6% slower, the first table's "256-bit" rows being the 512-bit runs*); stacked on rows 28 and 334. 335b, after 28, 329 and 331: the narrow operands, `bit_get` and `getbit`, a `BIGINT` shifted by an `INT` column, `bit_count(bigint)` under another node and `bit_count(bool)`. **335b, 20 October 2026, two items done** (`VARKA-335.md` 9.4): a `BIGINT` shifted by an `INT` column or expression (`BitOp` over a `WidenLane` count) and `bit_count(bigint)` under another node (`NarrowLane` over the popcount) fuse, at both widths, with the shift count read as Java reads it for every int; still open, behind 329 and 331: the narrow operands, `bit_get` and `getbit`, `bit_count(bool)`. *Planned 16 October 2026; the admission check:* no bitwise IR node exists; wave 0 can fuse the operators over `INT` and `BIGINT`, the shifts by a literal or an int column, `bit_count` over `INT` and as a `BIGINT` root, and the bitmap functions (by SMT), 24 census pairs; the narrow operands, `bit_get`, a long shift by a column and `bit_count(bool)` wait for 28, 329 and 331. The bitwise and shift operators, `bit_count` (the 64-bit popcount of the sign-extended value, 2.9), `bit_get` (its `TINYINT` result admitted with 329) and the bitmap position functions (two blended ANDs and a signed shift, `Long.MIN_VALUE` a test); the narrow shifts as int shifts over a cast, the long shift by a column as mixed width after 28, the count masked as Java does; no speed-up claimed for the count alone on AVX2. **Done when** each fuses on every integral type it takes, by exhaustion at 32 bits and below | item 32 | small |
| 336 | **Done** (`VARKA-336.md` 12, 21 October 2026): `a <=> b` and `equal_null(a, b)` fuse over the six types of the coverage table's `EqualTo` rows (`DATE`, `INT`, `BIGINT`, `TIME` and the year-month and day-time intervals), computed and widened operands included, and `p IS [NOT] TRUE|FALSE` fuses over any predicate, as filters, `CASE` conditions, under `NOT` and under `AND`/`OR`. One new IR node, `NullSafeEqual`, for value operands (total: `kT = (E & vA & vB) | ~(vA | vB)`, `kF = ~kT`), and the predicate forms composed as `And(p, IsNotNull(p))`, as planned; 16 coverage rows; the node is unpriced and in no grammar, which six suites that demand every node be drawn or priced had to be told in words (the plan had not listed them); the partition oracle excludes `NullPropagation` for the null-safe rows so the kernel, not Spark's folding, answers the `IS NULL` partition. The golden corpus holds no `<=>` statement, so its verdicts did not move. Found on the way: the composition fuzzer's sample of rows for the compiled check admitted a long-lane row that also reads an int literal (`shiftright(l, i ^ 5)`) and failed once the table grew, a harness gap now closed. *Planned:* Null-safe equality, `<=>` and `equal_null`, as a condition node over the validity words (item 34 answered, 2.9), taking a predicate child since `p IS TRUE` parses to `EqualNullSafe(p, true)`. **Planned 21 October 2026** (`VARKA-336.md`, admission check at `a429af749f4`): one new IR node, `NullSafeEqual`, for value operands (`kT = (E & vA & vB) \| ~(vA \| vB)`, `kF = ~kT`, never null; `IsNotNull` cannot take a computed value, so a composition would serve bare columns only), and `p IS [NOT] TRUE\|FALSE` composed from existing nodes as `And(p, IsNotNull(p))`; **boolean-boolean `<=>` (`b1 <=> b2`, `p <=> q`) is built by 331** as `BoolNullSafeEq` over two `Cond` children, since 331 owns the boolean leaves, so 336 follows only 334 and has no hand-off waiting on 331 (review of 21 October 2026, decided in the plan); no grammar production and no cost-model entry in this row (the grammar feeds the cost corpus, so a draw reprices the model), the `IS NULL` partition proved with `NullPropagation` excluded since `<=>` is never nullable and Spark folds the test by default; floating NaN and `-0.0` ride row 341's equality mask. **Done when** both fuse on every type, the `IS NULL` partition of `a <=> b` empty in the row's partition test | item 34 | medium |
| 329 | `TINYINT` and `SMALLINT` columns, built both ways: widened onto the int lane and native byte and short lanes, behind an emit option, measured on comparisons, a three-node chain, a mixed projection and the packed boolean output of a byte compare at both widths, the verdict recorded per operator (2.4). Both arms in one JVM load bytes at a narrow shape and compare them at the full one, which is the two-species case the record measured at 6.4x to 12.8x slower (`skills/vector-api-and-width.md`), so 376's census settles it before the arms are built (section 10.3 item 3). **Done when** both arms load, compare, choose and store with coverage rows, the measurement is committed with its verdict per width and operator, and the losing arm stays fuzzed in the option matrix Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): the two-species case did not box in any of five arrangements at 512 and 256 bits (narrow and full byte species in either order of first use, the widening load beside a full compare in either order, and both in one loop); the byte and short operators are intrinsic and allocation-free at every width, so the arms are built without a species workaround on this host, and the runner hosts' columns are owed.  *Planned 21 October 2026, `VARKA-329.md` (one file for 329 and 330):* one IR typed `BYTE` and `SHORT`, the option `narrowLanes` (`WIDENED`: stored narrow, computed on the int lane, `Analysis.inputLane` against `laneOf`; `NATIVE`: narrow throughout, an advantage only for kernels with no int value); the census's six cells at 128 bits are skipped, not intrinsic (a 4-lane int species has no 32-bit byte species), and it has no `NEG`, `ABS`, bitwise, `short` load or `B2S` cell, so a census extension and a hand-loop spike come first; benchmark `VarkaNarrowLaneBenchmark` with registered bounds (NATIVE 1.5 to 4.0 times in cache, 0.85 to 1.3 past it). | this plan, 2.4; `VARKA-329.md` | large |
| 330 | `TINYINT` and `SMALLINT` arithmetic in the three modes: wrap at the type's width, ANSI's range check (`add != sadd` on the native arm), TRY's null, `*` widening in both arms. **Done when** `+`, `-`, `*`, negation, `abs` and the `try_` forms fuse on both arms, proven by exhaustion over every operand pair, with `-(-128Y)`, `abs(-128Y)` and the short analogues as named cases, and the first 2^32 sweep timed against the Spark oracle and the milestone's sweep budget totalled before the next sweep lands, the 16-bit integer rules moved to SMT where that is complete (section 10.3 item 5)  *Planned 21 October 2026, `VARKA-329.md` 4:* a checked narrow multiply is provable with no 64-bit product (a short product is at most 2^30), so the widened arm fuses `*` in every mode where `INT * INT` still declines; `abs` composes from `IfElse`, `Compare` and `IntNeg` (no node); LEGACY wrap is deferred to the first non-ring consumer (SMT-proved); 18 sweeps of 2^32 for `SMALLINT`, the first timed before the others. | this plan, 2.4; `VARKA-329.md` | medium |
| 331 | **Planned** (`VARKA-331.md`, 21 October 2026; the admission check: no boolean column compiles today; the read is `validityBitsAt` over the `BitVector`'s data buffer, the same bitmap layout, so the masked body needs no mask conversion (`kt = data & valid`, `kf = ~data & valid`) and only the dense body calls `fromLong`; new nodes `BoolColumn` (lane-carrying leaf), `CondConst`, `BoolCompare`, `CondIf` and a condition-to-value bridge `CondValue` that 335b, 332 and 27 share; a bare column's `IS NULL` is its validity word, not 334's `IsNotNull`-over-`Cond` route; **331 also builds the boolean `<=>`** (`BoolNullSafeEq`, kT/kF of its 2.8; 336 follows only 334 and does not wait on 331, review of 21 October 2026); the new nodes are unpriced until 352's refit; the oracle exhausts four leaf states per column, null with either data bit, at every offset and both widths). `BOOLEAN` columns as conditions: `BitVector` read as known-true and known-false masks, three-valued `AND`, `OR` and `NOT` over columns, comparisons and choice over booleans, a boolean null literal as a condition leaf. **Done when** a boolean column filters, chooses and compares with coverage rows and the null-pattern tests of `VARKA-138.md`, arbitrary data bits under null slots included, and the mask built from the data word by a construction intrinsic on every host the milestone measures (375) Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): the mask conversions are intrinsic and allocation-free at every width except the long lane at 128 bits, where x86 C2 lowers no two-lane mask (`m7` row 374), so at the forced 128-bit species the operator is the Vector API's Java fallback loop, and the 128-bit companion records it as such. | this plan, 2.5 | medium |
| 27 | Boolean outputs: a predicate or a boolean column as a projection result, in both forms row 27's measurement keeps, and the selected-row count taken per word. **Done when** `m5/PLAN.md` 2.1's list holds over every predicate row of the coverage table Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): `toLong` and `fromLong` are intrinsic with no allocation on bytes, ints and longs at 512 and 256 bits and on bytes and ints at 128; on longs at 128 bits x86 C2 lowers no two-lane mask (`m7` row 374), so at the forced 128-bit species the operator is the Vector API's Java fallback loop, and the 128-bit companion records it as such. | catalogue item 15 table; `m5/PLAN.md` 2.1 | medium |
| 332 | The integral and boolean casts in the three modes (wrap, `CAST_OVERFLOW`, null), and the interval casts to and from integral types with their mode split (raise under LEGACY and ANSI, null under TRY; never for a `TINYINT` or `SMALLINT` source). *Found by 328 (19 October 2026), and to fuse here on the owner's word:* of the year-month casts with `INT`, only the MONTH unit fuses today (`CAST(i AS INTERVAL MONTH)`, `CAST(ymm AS INT)`, both relabels); `CAST(i AS INTERVAL YEAR)`, a checked multiply by twelve, and `CAST(ymy AS INT)`, a division by twelve, decline. **Done when** every such pair of `m8/casts.tsv` fuses, proven by exhaustion where the source has at most 32 bits and by SMT where it has 64 | this plan, 2.2 | medium |
| 337 | `hash` and `xxhash64` over the numeric and temporal columns: NaN and `-0.0` canonicalised first by 2.7's shared node, the decimal path picked by the type's precision, precision above 18 declined at first (its hash is a variable-length big-endian byte array with Spark's legacy Murmur3 tail) or built, the row-28 dependency noted; sampled regions: payload NaNs, the four NaN range endpoints, byte lengths 1, 2, 8, 9, 15 and 16; `xxhash64` measured separately on AVX2 (64-bit multiplies emulated), AVX-512 and NEON (scalar). **Done when** both fuse over every type in the set with Spark's per-type byte layouts, proven against `Murmur3HashFunction` and `XxHash64Function` by exhaustion at 32 bits | this plan, 2.1 | medium |
| 338 | The `TIMESTAMP_NTZ` lane: load and store, comparisons, choice, `IN`, the null tests, the casts with `DATE`. **Done when** each fuses with coverage rows, `DATE` to `TIMESTAMP_NTZ` guarded at `+-106,751,991` days, raising under LEGACY and ANSI and null under TRY, with `try_cast` tested at days `+-106,751,991` and `+-106,751,992` | item 31 | small |
| 339 | `TIMESTAMP_NTZ` arithmetic: `ntz - ntz`, `ntz +/- dt`, `ntz +/- ym`, `timestampadd`, `time_bucket`, checked, with 2.6's edges: the day guarded at `+-106,751,991` then a checked add, day -106,751,992 declined whole, `dt != Long.MIN_VALUE` and `months != Integer.MIN_VALUE` guarded, `time_bucket`'s next-bucket overflow, `timestampadd`'s month units in int. **Done when** each fuses, the overflowing lane declining to the row engine's error, the calendar forms proven against `java.time` over the sweep the date family uses | item 31 | medium |
| 340 | `TIMESTAMP_NTZ` decomposition: the date fields through the exact full-range floor division of 2.6 (`>> 13`, then by 10,546,875) into the int32 prefix, declining outside the prefix's years; `hour`, `minute`, `second`, `extract` over it (`SECOND` a `DECIMAL(8, 6)`, with 351); the casts with `TIME` - to `TIMESTAMP_NTZ` by the one arm that also serves `make_timestamp_ntz(date, time)`, the query's date a per-query constant. **Done when** each fuses, the date-pinning test of 2.6 passes with half the batches on the fallback, `Long.MIN_VALUE` and `Long.MAX_VALUE` pinned through the fallback, and the division proven by SMT over the whole long range in the long-lane proof file's divisor list (not `ChronoDivide`'s int-lane table), its magic constants recorded | item 31; `m5/PLAN.md` 2.6 for the long-lane division | medium |
| 341 | The floating lanes: `Lane` split into integral and floating halves, `DOUBLE` and `FLOAT` lane types, `Float8Vector` and `Float4Vector` read and written, comparisons, choice and `IN` by Spark's NaN and `-0.0` rules, `isnan`, `nanvl`, the canonicalisation node shared with `NormalizeNaNAndZero` and 337, and the NaN rule of `SCOPE_STANDARD_MODE.md` 3.5 in the oracles and the fuzzers. **Done when** item 89 bullet 1's table test, extended to NaN payload bits and the tie cells of 2.7, passes for every operator and its constant-specialised forms, the bytes oracle unchanged for every integral shape Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): the float compares and `IS_NAN` are intrinsic and allocation-free at every width; the double ones are intrinsic at 512 and 256 bits and at 128 bits, two lanes, x86 C2 lowers no two-lane mask (`m7` row 374), so at the forced 128-bit species the operator is the Vector API's Java fallback loop, and the 128-bit companion records it as such. | items 35, 89 | large |
| 342 | Floating arithmetic and the exact functions: `+`, `-`, `*`, `/` (a zero divisor null or raised, Spark the ANSI oracle; an int division through its casts; never a reciprocal), negation, `abs`, `signum`, `sqrt`, `degrees`, `radians`, `try_divide`, the interval products and quotients by a floating factor, with 2.7's signed-zero and special cells. **Done when** each fuses, bit-identical to the row engine over sampled regions and the fuzzer | item 35 | medium |
| 343 | The floating casts: integral to floating (rounding as the JVM does; `BIGINT` to `FLOAT` rounded once, by round-to-odd on AVX2 and NEON), `FLOAT` and `DOUBLE` to each other, floating to integral (LEGACY's saturation with no detection, ANSI's two range compares and NaN, TRY's nulls, the mapping table of 2.7 with its boundary cells; `D2L` and its kin under AVX512DQ, the magic-number form below `2^51` on AVX2, the host named), to and from `BOOLEAN`, `time_from_seconds` over a double. **Done when** every such pair of `m8/casts.tsv` fuses in the three modes, a `FLOAT` source proven over all 2^32 bit patterns, the round-to-odd truncation stated as an SMT bit-vector claim and exhausted through `binary16`, and a split-and-add conversion kept as a named failing case (it misrounds 16.5% of random unsigned 32-bit inputs above `2^31`; 4275361147 gives 4275361280 against 4275361024, `READING_0X80.md`) | this plan, 2.2 | medium |
| 344 | The rounding functions: `floor`, `ceil`, `rint` over a double by the `2^52` construction with its conditions written out (2.7), `floor` and `ceil` to `BIGINT` (on AVX2 through `rint`, the correction and the magic conversion, declining above `2^51`), `round`, `bround` (the Lemire, Bartlett and Kaser section 7 form) and `truncate` over integral types, `factorial`, `width_bucket`, the `rint(-NaN)` probe. **Done when** each fuses, the double forms checked over every boundary region, `rint(-0.3)` giving `-0.0`, and the integral ones by exhaustion at 32 bits | items 35, 36 | medium |
| 345 | The math family's ULP contract and its configuration (2.7.1): the bound defined as a count of representable doubles from the row engine's double, keyed by JDK build, library and CPU, contracted and measured bounds kept apart, the binding verified once per JDK build under `-Djdk.incubator.vector.DEBUG=true` and by the C2 compile itself (`dev/varka_emit.sh --asm` showing the call to the library's symbol, since the DEBUG line proves the symbol and not the call, `READING_JDK.md` 2.6); SLEEF's stated tier beside each measured bound (`READING_SLEEF.md` 6.5); null and masked-off lanes blended to a harmless argument before the library call, since a masked math operator computes every lane and blends after (`READING_JDK.md` 2.7); the coverage and support tables' bound column; the oracle and fuzzers comparing within the bound, a NaN never passing against a number; the exact-value table per family row tested bit for bit; `spark.sql.codegen.varka.math.ulpContract.enabled`, on by default, with its emit option in the shape key, the differential suites' config-off arm, and `SCOPE_STANDARD_MODE.md` 3.4. **Done when** the configuration declines the family when off, a test shows a within-bound comparison catches a lane two steps further out, and the dated corrections of `SCOPE_FUNCTIONS.md` 3 and item 36 are in Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): every `Double` math operator is intrinsic and allocation-free at 512 bits; at 256 and 128 bits `POW` has no vector stub, so the loop never reaches the library call and runs the Java fallback (the canary's finding), which is the form of `pow` at those species until a host shows otherwise. | item 36; owner's decision, 14 October 2026 | medium |
| 356 | The trigonometric functions under the contract: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, and `sec`, `csc`, `cot` as the compositions they are, each only after 367 has measured it. **Done when** each fuses within its stated bound with a coverage row per host library, its exact values bit for bit | items 35, 36 | medium |
| 357 | The hyperbolic functions under the contract: `sinh`, `cosh`, `tanh`, and `asinh`, `acosh`, `atanh` as fdlibm's branch blends of three math calls per lane, each only after 367 has measured it; SLEEF binds no `TANH`, so on Arm `tanh` is a per-lane `Math.tanh` loop (bit-exact there, `Math.tanh` being fdlibm on aarch64) and is declined on SLEEF hosts unless measured faster (`READING_JDK.md` 2.5). **Done when** each fuses within its stated bound, the blends reproduced branch by branch, `tanh`'s saturation bit for bit | items 35, 36 | medium |
| 358 | The exponential, logarithmic, power and root functions under the contract: `exp`, `expm1`, `log` (one and two arguments, the second a composition), `ln`, `log10`, `log1p`, `log2`, `cbrt`, `hypot`, and `pow` by owner's decision A3: behind the guard on fdlibm's weak branch (`|y| > 2^31` and `||x| - 1| <= 2^-20` declines the batch; the branch's own thresholds are `0x1.00000_ffff_ffffp31`, `0x1.fffff_0000_0000p-1` and `0x1.00000_ffff_ffffp0`), its general-exponent kernel enabled on a host only after 367 has measured the guarded domain there, and fdlibm's exact exponents as exact nodes outside the contract (`y == 2` as `x * x`; `y == 0.5` as `Math.sqrt(x + 0.0)` for `x >= -Double.MAX_VALUE`; `y == +-1` as `x` and `1.0 / x`), with Java's `pow(1, NaN)` and `pow(+-1, +-inf)` as NaN against C99's 1 in the exact table; on SLEEF hosts an output guard, a lane that comes back infinite from finite inputs declining the batch, because SLEEF 3.6.1, which the JDK bundles, sends `exp` and `pow` to infinity above 709.78271114955742909, below the true overflow point (section 10.1 item 1). **Done when** each fuses within its stated bound, `log10` at its two steps, the `pow` guard's decline, the exact nodes and the SLEEF overflow band's decline tested bit for bit Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): `exp`, `log`, `log10`, `expm1`, `log1p`, `cbrt` and `sqrt` are intrinsic at every width; `pow` has no vector stub at 256 and 128 bits and runs the Java fallback there. | items 35, 36; owner's decision A3 | medium |
| 346 | The constructions behind the former hard entries (2.7): `round`, `bround` and `truncate` over a `DOUBLE` at scales 0 to 22 by the tie test over the shortest decimal, with the declined band recorded by its bounds; a floating `%`, `pmod` and `try_mod` by the one-FMA remainder with SLEEF's `xfmod` as a second oracle; `DOUBLE` and `FLOAT` to decimal by the rounding-interval guard, the integer arm and the exact shortest-digits path. **Done when** each fuses with its proof or declines its recorded band, the census carrying the band | this plan, 2.7 | medium |
| 347 | The decimal load path for precision up to 18, built three ways: the de-interleave (shuffle, and compress where it intrinsifies) with the high word checked, and the shuffle-free check as a measured variant; an unscaled-long cache layout for precision up to 18; and the int lane for precision up to 9; `bitWidth == 128` required on the Arrow field; load cost separated from compute cost; the JDK's limits recorded with the verdict. **Done when** all three read a decimal column with coverage rows, the tests cover garbage high words under nulls, a slot holding `10^p` and a wrong high word, the measurement is committed per width with its verdict, and the arms that lose stay behind the option Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): the two-vector `rearrange`, `selectFrom` and `compress` on longs are intrinsic and allocation-free at 512 and 256 bits; at 128 bits they are the Java fallback (two lanes, `m7` row 374), so the de-interleave at 128 bits is the fallback loop and is measured as that. | items 1, 89 bullet 6 without its runtime bound | large |
| 157 | The widening store: a decimal output's value word and sign extension interleaved into the sixteen-byte slots by two two-vector selects and two stores. **Done when** `m5/PLAN.md` 2.93's list holds | catalogue item 39 table; `m5/PLAN.md` 2.93 | small |
| 348 | Decimal comparisons, choice, `IN`, null tests, negation and `abs`, at equal and at unequal scales (a constant rescale proven to fit the long), for operands whose common type stays within precision 18; wider ones are 359's; a negative scale declined. **Done when** each fuses, the load's precision check declining an out-of-precision batch | items 1, 2 | medium |
| 349 | Decimal `+`, `-` and `*` in the 64-bit form, with `CheckOverflow`, `MakeDecimal` and `UnscaledValue`. **Done when** each fuses for every operand pair whose result stays within precision 18 (wider results are 360 to 362's), the no-overflow claim and the rescales proven by SMT over every precision pair, LEGACY and ANSI giving the same bytes | item 2 | medium |
| 350 | The decimal casts in the 64-bit form (both sides within precision 18): to and from integral types (the exact rule under ANSI and TRY, after 365; *since the answer to question 3 of section 7, 16 October 2026*: Spark's double rule under ANSI and TRY for `INT`, `SMALLINT` and `TINYINT`, as 364 builds it above precision 18, which needs a correctly rounded `u / 10^s` and so takes 366's exact arm outside the guard, those three pairs' ANSI and TRY range check proven by the sampled arm and not SMT; `BIGINT` and LEGACY stay integer rules), decimal to decimal (`ROUND_HALF_UP` by the signed-half add and the constant division, its `2^52` gap closed by the pre-shift and `5^k` route of 2.8 with the `k = 1..7` guard or the limb-magic arm, overflow null or raised), to and from `BOOLEAN`, to `DOUBLE` and to `FLOAT` under their two guards of 2.2, round-to-odd where the `FLOAT` guard fails. **Done when** every decimal pair of `m8/casts.tsv` fuses or hands the batch to 366's exact arm, the integer rules proven by SMT with `5^k` for `k = 1..18` in a second proof file under cvc5, and the three double-rule range checks by the sampled arm | this plan, 2.2 and 2.8; the answer to question 3 | medium |
| 351 | Decimals in the existing types' functions, all of whose decimal types are within precision 18, and the rounding functions over a decimal of either form: `time_to_seconds`, `second` with its fraction, `extract(SECOND FROM dt)` and `extract(SECOND FROM ntz)`, `make_time`, `make_dt_interval` and `make_timestamp_ntz` over decimal seconds, `time_from_seconds` over a decimal, `floor`, `ceil`, `round`, `bround` and `truncate` over a decimal - the last three giving precision 19 over a `DECIMAL(18, s)`, so after 360 and 157, a negative-scale `round` raising on overflow in every mode. **Done when** each fuses with a coverage row | `m5/PLAN.md` 2.93; items 1, 2 | medium |
| 359 | The 128-bit lane pair: a decimal of precision 19 to 38 as low-word and high-word long lanes in the IR and the emitter, `Decimal128` loaded by a de-interleave and stored by the inverse (or computed on adjacent lanes, by measurement), comparisons (the high words signed, the low words unsigned), `IN`, choice, null tests, negation and `abs` with their borrow, and the `10^p` overflow check per lane group; the signed high-word compare is the pair's own, Highway's 128-bit compares being unsigned only (`READING_HIGHWAY.md` 3.5). Admission check, before any of it is built (section 10.3 item 2): a scalar per-row loop inside the kernel on `Math.multiplyHigh` and `unsignedMultiplyHigh`, with no de-interleave, measured against the lane pair for the 128 by 128 product, the `10^38` check and the `HALF_UP` rescale at 512, 256 and 128 bits; the lane pair is built where it wins and the scalar form kept as the reference arm. **Done when** each fuses at every precision from 19 to 38 with coverage rows, the comparisons and the `10^p` check proven by SMT over integers in the two layers of 2.8, and the bytes oracle unchanged for every 64-bit shape Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): the unsigned long compares and the long de-interleave are intrinsic and allocation-free at 512 and 256 bits; at 128 bits both are the Java fallback (two lanes, `m7` row 374). | this plan, 2.8 | large |
| 360 | 128-bit add and subtract with the carry from an unsigned compare, the uncapped shapes first (their wrapped sum fails the `10^p` check, the lemma proven) and the capped shapes with 363 (the 129th bit feeding its unsigned division, or the whole-and-fraction split), the rescale-up of the coarser operand under a batch guard, and the widening casts: a 64-bit decimal to the lane pair by sign extension, and an integral to `DECIMAL(3, 0)`, `(5, 0)`, `(10, 0)` or `(20, 0)` as type coercion inserts them, so a `BIGINT` with a decimal fuses. **Done when** each fuses in the three modes with `CheckOverflow`'s `10^p` check, `DECIMAL(38, 10) + DECIMAL(38, 10)` at `10^38 - 1` a test, the carry and the sign extension proven by SMT over integers at full width Census reading (`VARKA-376.md` 9, 20 October 2026, laptop): the unsigned long compares are intrinsic and allocation-free at 512 and 256 bits; at 128 bits they are the Java fallback (two lanes, `m7` row 374). | this plan, 2.8 | medium |
| 361 | The widening multiply: two operands within precision 18 whose product exceeds it (TPC-H q6's `DECIMAL(15, 2) * DECIMAL(15, 2)`, `DECIMAL(31, 4)`), 64 by 64 to 128 bits from 32-bit limbs on Arrow's carry-free limb order with the `unsignedMultiplyHigh` sign correction, every constant limb written as `broadcast(c).and(0xFFFFFFFF)` so C2 selects `vpmuludq`; a `BIGINT` operand arrives as `CAST(l AS DECIMAL(20, 0))` but stays a 64-bit value, so its product is this row's 64 by 64 and not 362's 128 by 128, as StarRocks strips the cast (`READING_DECIMAL_ENGINES.md` 2.5). **Done when** it fuses with its emitted instruction mix read by `dev/varka_emit.sh --asm`, the constant-limb case included and surviving GVN; the ring identity and the carry network proven by SMT over integers at full width, a reduced width as a cross-check only | this plan, 2.8 | medium |
| 362 | The full multiply: 128 by 128 bits into a 256-bit product by column summation of sixteen `vpmuludq`, no Karatsuba and no compares, with the static shortcut when `p1 + p2 + 1 <= 38`, an SMT line stating it against Velox's weaker `< 38`, and Velox's leading-zero test as an option arm that keeps the common batch on 128 bits (`READING_DECIMAL_ENGINES.md` 2.1, 2.2); built and measured against the row engine, declined with its reason only if it loses (owner's decision A2). **Done when** it fuses for every precision pair whose result Spark keeps exactly, or carries its measured reason to decline; the ring identity and the carry network proven by SMT over integers at full width, a reduced width as a cross-check only; arrow-rs's carry corners and a random `BigInteger` cross-check of every arm as tests (`READING_ARROW_RS.md` 3.4, `READING_DECIMAL_ENGINES.md` 2.6) | this plan, 2.8; owner's decision A2 | large |
| 363 | Spark's precision-loss rule and the rescale down: with `allowPrecisionLoss` (default true) a result type above 38 is capped at 38 and its scale cut to `max(38 - intDigits, min(scale, 6))`, the value rounded `ROUND_HALF_UP` by the magic-multiply division of 2.8 (pre-shift by `k - 1`, divide by `5^k`, `(q + 1) >> 1`; sixteen `vpmuludq` for a 128-bit dividend, 49 to 64 limb products for a 256-bit one), Trino's last-stage rounding and DuckDB's overflow-free `HALF_UP` borrowed, the `MathContext(39, DOWN)`-then-`HALF_UP` lemma stated and proven, the rule read from the node's resolved type and never the session configuration, SPARK-45786's random test ported, the `k = 39` multiply declined or folded; with the setting false, the exact type and its overflow. The rounding functions over a 128-bit decimal use the same division. Built and measured, declined with its reason only if it loses (owner's decision A2). **Done when** both settings fuse or carry their measured reason, the magic constants proven by the proof files' rule (`sql/varka/proofs/`) over integers at full width - linear, since the multiplier is a constant; the rounding fixtures and the `10^k +- 1` and `5^k +- 1` divisor table of `READING_ARROW_RS.md` 3.2 and 3.3 and rapids' compatibility triples and 38-nines edge (`READING_RAPIDS.md` 3.1) as named tests | this plan, 2.8; owner's decision A2 | large |
| 364 | The 128-bit decimal casts: to and from integral types (truncation toward zero, the low bits under LEGACY, `CAST_OVERFLOW` under ANSI; to `INT`, `SMALLINT` and `TINYINT` by Spark's double rule above precision 18, through the two-pass exact arm, the lane declined where the arm declines; 350 builds the same rule below 19 since the answer to question 3, 16 October 2026), to `DOUBLE` and `FLOAT` by the exact arm run on the top 64 bits of `|u|` as `w'` and `w' + 1`, accepted when equal and declined otherwise, `Long.MAX_VALUE` excluded from any `(long) (double) u == u` guard, to and from other decimals of either form (`ROUND_HALF_UP` through 363's division), to and from `BOOLEAN`. **Done when** every decimal pair of `m8/casts.tsv` fuses at every precision up to 38 or carries its recorded reason, the integer rules proven by SMT over integers at full width | this plan, 2.2 and 2.8 | medium |
| 365 | **Planned** (`VARKA-365.md`, 16 October 2026; **design decided the same day: the owner answered question 3 of section 7 with option (iii), no reader change, which supersedes A1**. The admission check by reading: the column path builds a `BigDecimal`-backed value, the cache's row reader a long-backed one with no high-word check, and which a cached table takes depends on its other columns' types; a three-path test observing the disagreement is the first step). Until that answer the row was A1's reader change: `ArrowColumnVector.SmallDecimalAccessor` and `ArrowColumnReader` building a long-backed decimal up to precision 18, so that every path followed Spark's exact rule for the ANSI and TRY casts to `INT`, `SMALLINT` and `TINYINT` (2.8 keeps that plan as the record). It changed because row answers follow vanilla Spark always, and the reader change would have made every Arrow consumer of the fork (the cached-batch iterator, the Python, R and external UDF runners) answer the three casts differently from vanilla Spark with Varka off as well; SPARK-59443's record (upstream kept the `BigDecimal`-backed accessor on purpose on 21 September 2026, fork commit `f2dcec5bdd6`, without ruling on it, while its cached-batch row path already gives a long-backed value) was the answer's input. Under (iii) the readers stay as upstream has them, and the row builds: `VarkaDecimalReadPathSuite` first, the regression test that pins the two rules on the three paths; a coverage note and a line in `docs/sql-varka.md` stating that a Varka-off read of a cached table on the row path answers the three casts by the exact rule where Varka's kernel and fallback answer by the double rule; and the upstream JIRA's text, recorded in `VARKA-365.md` 9, that Spark's ANSI and TRY decimal-to-integral cast depends on the data source and the read path, with the row reader's missing high-word check reported as its own finding. 350 and 364 build the double rule in the kernel. **Done when** `VarkaDecimalReadPathSuite` observes `0.999999999999999999` as `DECIMAL(18, 18)` and `2147483647.99999999` as `DECIMAL(18, 8)` cast to `INT` under ANSI and TRY answering 0 and 2147483647 on the row path and 1 and `CAST_OVERFLOW` (null under TRY) on the row path's columnar fallback and on Varka's columnar path, so that 350's kernel has the answer it must match; the coverage note and the documentation line are in; and the JIRA text is recorded in `VARKA-365.md` 9, filed only with the owner's yes (*until 16 October 2026 the done-when asked for the three paths to agree on the exact rule, a wrong high word declining or raising as before, and Spark's cast suites passing on every consumer*) | this plan, 2.8; owner's decision A1, superseded by the answer to question 3 (16 October 2026); the layout and casts readings | small (medium while it was A1's reader change) |
| 366 | The exact decimal-to-floating arm (owner's decision A4, 2.2): Eisel and Lemire's algorithm with the scale a per-column constant (a two-long table per kernel), exact for every unscaled long with no fallback while `s <= 27`, for both targets, taken per batch when the guard mask of 350 is not all true; `s >= 28` declines; above precision 18 the two-pass form of 364; for `DOUBLE`, the working-precision round-to-odd of section 9 as an option arm. **Done when** the 64-bit form fuses every value for `DOUBLE` and `FLOAT`, bit-identical to `BigDecimal.doubleValue` and `floatValue` over sampled regions, the tie windows and the fuzzer, with the paper's Theorem 1, Corollary 2 and Lemma 3 cited for the exactness claim and its cost measured against the guarded division | this plan, 2.2; the float-conversions reading | medium |
| 367 | The extended math probe (owner's decision A4, split out of 345): `dev/varka_canary/MathLaneProbe.java` over every function the family fuses, at the level Spark computes it (the four unprobed operators and the eight compositions), against both references, with bit-pattern inputs over the whole domain, the libraries' switching regions, the handbook's and Gladman's worst cases as fixed vectors, a bisection search for the largest step distance, and `pow`'s guarded domain; the SLEEF overflow band of section 10.1 and glibc's and Intel's switching regions as fixed vectors (`READING_LIBM.md` 6.3, `READING_JDK.md` 2.8); a true-value column from MPFR through the foreign-function API (`libmpfr.so.6` is installed on the laptop), which says which side is wrong where the kernel and the row engine disagree and how often each side misrounds; results keyed by JDK build, symbol and CPU and committed per host, each stated as a lower bound of the true maximum. **Done when** a run on each of the three host libraries is committed and every bound of 356 to 358 cites its line | this plan, 2.7.1; the math reading | medium |
| 368 | Spark's own `CastWithAnsiOffSuite`, `CastWithAnsiOnSuite`, `TryCastSuite` and `HashExpressionsSuite` run with the Varka evaluator forced on and constant folding excluded (owner's decision A4), recording fused or declined per `(from, to, mode)` as Gluten's tracker does, diffed against `m8/casts.tsv` and the census nightly. **Done when** the four suites pass under the evaluator, the record is committed, and a pair the matrix calls fused that the suites find declined fails the nightly | this plan, 2.10; the casts reading | medium |
| 369 | The fuzzer's count arm (owner's decision A4): over a large cached fixture, `COUNT(*) WHERE p` against `COUNT_IF(p)` and the three-part sum, reaching the batches and lane positions twelve rows cannot and localising a disagreement to the filter plumbing against the projection plumbing; kernel against kernel once 27 lands; the Spark differential the ground truth. **Done when** the arm runs nightly over the fixture with the milestone's shapes and its first disagreement, if any, is a fixed finding or a named row | this plan, 2.10; the proofs reading | medium |
| 352 | The fuzzers and the reference evaluator across types, by 2.10's list: the IR fuzzer's grammar and the Spark-level fuzzer's columns over all thirteen types, the TLP `WHERE` oracle over the milestone's shapes with its ANSI gap closed, the generators' NaN, `-0.0`, infinity and decimal-shape sweeps, casts drawn from `m8/casts.tsv`, the two cross-mode properties, the sampled regions and tie windows, all under the option matrix. **Done when** both fuzzers draw every type at every nightly, the cross-mode properties hold over the 98 pairs and the `try_` arithmetic, and a cross-type disagreement found is fixed or a named row | this plan, 2.10 | medium |
| 353 | The support table for users: `docs/sql-varka.md` states, per type and per family, what fuses, from the census and `coverage.json`, every column generated, notes included, the decimal per representation and the casts per mode by each mode's own legality (Comet's hand-written notes and its LEGACY legality in the ANSI table are the cautionary cases); its "three types" paragraph says what the code admits; each math function's bound per host library beside what Java's "within 1 ulp" specification is worth; and the math configuration's default (on) and how to turn it off at the head of the math section and in the configuration list (2.7.1). **Done when** the table is generated and `VarkaCoverageSuite` fails when it drifts | item 54 | small |
| 354 | The closing task: 1.1 read row by row, the census and the cast matrix regenerated on the milestone's build, the README's account updated. **Done when** each item of 1.1 is met or its gap is named | this plan | small |
| 355 | The milestone's post, *One engine, every number*, for JVM and database engineers, pointing its readers to Goldberg's and the handbook's sections named in 2.11. **Done when** published with the census share per type and the proofs behind it, as 2.11 says | this plan, 2.11 | medium |
| 180 | Promotion, continuously. **From milestone 7** (17 October 2026, `m7/PLAN.md` row 180), as it came into milestone 7 from 6: a cadence, not a task the milestone closes. The milestone's own post is row 355 | item 81 | a cadence |
| 386 | **From milestone 7** (17 October 2026, `m7/PLAN.md` row 386). A filter whose first conjunct is NULL on a row and whose second raises: Spark's default session, with whole-stage codegen, returns the row, because its generated filter stops at the NULL conjunct; the interpreted filter and Varka raise. The reproducer is `sql/varka/fuzz/spark/varka-386-20261017-22897.sql` (`status: known 386`). Upstream, SPARK-60315 files the disagreement, and apache/spark#59573 makes codegen raise as the interpreted path does, behind a legacy flag (`spark.sql.legacy.filterShortCircuitOnNullConjunct`) that restores the old answer. The standard reads the same way: the second conjunct decides between Unknown and False, so it is not an inessential part (ISO/IEC 9075-2, 7.11 and 6.35). Varka's change for that semantics, the flag included, is VARKA-388's work: built on branch `varka-388` in milestone 7 and not opened as a pull request, since it would differ from released Spark until the upstream change merges. In milestone 8 because milestone 7 closed before the upstream decision; the row does not belong to a wave and is taken when the decision comes. **Done when** the upstream pull request is decided, Varka gives the decided answer under both settings of the flag if it merged (or codegen's answer if it did not), the reproducer is a regression that agrees, and `-Dvarka.sparkfuzz.nested=false` is gone from the nightly's `sparkfuzz` step | `m7/PLAN.md` rows 380, 386; VARKA-381 9.2 | small to medium |
| 392 | **Done** (`VARKA-392.md` 9, 18 October 2026): the nightly fuzz workflow runs again. From 13 October every night's IR fuzzer failed all 16 tests at "did not finish in 9 minutes in its own JVM": `VarkaOwnJvm` forked a child whose budget is one test's cap less a minute for the whole suite, which the suite outgrew at 40,000 iterations a lane; the composition and size-4 variants steps after it were skipped all six nights. The workflow's JVMs, each already running one suite alone, now run it directly (`-Dvarka.ownJvm=true`) with 45-minute caps, the laptop nightly's fuzz step likewise, and each fuzzer step runs whether or not the one before failed; the child's timeout message names the cause. A local replay of the 20 seeds of 13 to 17 October found no wrong answer, only a shrinker test that assumed every seed's first failing draw could be made smaller, now fixed | `m7/PLAN.md` 12, the close's review, which found the workflow red six nights unread; 1.1 point 1's IR fuzzer drawing each new type (352) needs the nightly running | small |
| 393 | The weekly full matrix (`varka-weekly-matrix.yml`) green on master. It has failed every week since its first run: on 27 September and 4 and 11 October in the Java linter alone, on upstream javadoc positions (`InvalidJavadocPosition` in `CollationSupport.java`, `CLIService.java` and four more) that a sync later cleared, and on 18 October in Varka's sql suites, where "a projection's first query takes the row path and its next one the compiled kernel" and "a columnar consumer's first write takes the row path and its next one the kernel" failed under the defaults on master `9435783beb2`, before #921 moved the template warm-up first, beside one upstream streaming test ("tumbling window max"). Nobody read the four failures. A dispatch on master `25d8b251098` on 18 October, after #921 and #924, failed one test of 9,628 in sql's extended tests, upstream's "SPARK-33084: Add jar support Ivy URI in SQL", which resolves from Maven Central, and the two warm-up tests passed. The row reruns the matrix on master after #921 and #924, fixes what is still Varka's, records what is upstream's with the sync that clears it, and makes a failed scheduled run reach the owner the same day rather than wait for a close to read it. **Done when** a weekly run on master is green, or red only in jobs whose cause is upstream and named in the row, and a failed scheduled Varka workflow opens or updates an issue. *20 October 2026: the second half is built: the last job of `varka-weekly-matrix.yml`, `varka-option-matrix.yml` and `varka-fuzz.yml` runs, on a scheduled run with a failed job, the composite action `report-scheduled-failure` (`dev/varka_scheduled_failure.py`), which comments on the workflow's open `scheduled-run-failed` issue or opens one, and closes nothing. The first half, a green weekly run on master, is read from a dispatch started on 20 October 2026, run 39494354027, and is not part of that change; the row is not Done.* | `m7/PLAN.md` 12, the close's review, which found the weekly run never green; 1.1 point 1's bytes oracle and suites hold for the existing shapes only if the full matrix runs them | small to medium |
| 394 | **Done** (`VARKA-394.md` 8, 19 October 2026): the option matrix's 51 failures on the dispatch of `25d8b251098` read and each put down. 32 were `VarkaIrFuzzSuite` running out of `VarkaOwnJvm`'s nine-minute child under `useAVX=0` and `groupLocalSlots=false`, row 392's trap; `dev/varka_matrix.sh` now gives each suite that runs alone a runner JVM of its own that runs it directly (`-Dvarka.ownJvm=true`), each test under its own cap, for the matrix, the gate and CI alike, scope item 111's harness redesign left as it was. Of the other 19, none was a wrong answer: nine `fails` lines in `skips.tsv` (the VARKA-290 trace test under the three driver and budget options, beside its sibling's lines; `checkIntOverflow=false`'s wrapped values in three raise-order tests, the residual conjunct and the VARKA-386 reproducer; `arrowBatchSize=1`'s statistics pruning the overflow row in the residual conjunct test) and the stale `year(d) + i` line removed; five tests fixed (the far-day split test counts the batch size's batches, the VARKA-379 split shape's budget fits every validity form, the split-predicate test pins the byte budget its shape needs, the VARKA-311 test emits from `VarkaMatrix.base`, the allocation sampler assumes a kernel no wider than the JVM's preferred vector, which the dispatch's 256-bit runner was not); and `heavyGroupOutputs=0`'s warm-up failure is not that option's, its probe fork running the defaults, a finding for the warm-up rows. VARKA-253's failure had gone with #918. Every touched suite passes on the laptop under every configuration it failed in; the full run on master is the first after the merge | `m7/PLAN.md` 12, the close's review; 1.1 point 1's bytes oracle unchanged for every existing shape is checked across the options only here | medium |
| 395 | **Done** (`VARKA-395.md` 9, 19 October 2026): the template warm-up holds no lock while it waits and states what it costs. The first thread to reach a hook claims the run in a short `synchronized` block and runs it outside; any other thread waits on a latch until the run ends, so no vector code runs before the templates are warm, and neither the pacing nor a waiter is cut short by an interrupt (each puts it back). The pacing's budget drops from 20 s to 2 s, twice the longest wait measured at two and four cores (537 ms), and the class doc states the cost as the rounds plus that budget. Each queue reading stays a global safepoint, as HotSpot runs `Compiler.queue`; the redundant last reading goes and the sleep between readings is 10 ms, and eighteen JVMs of the end-to-end suite measured 306 to 338 readings a warm-up, medians of 8 to 22 us, summed 6 to 9 ms on four idle cores and 10 to 36 ms on two or on a loaded machine, the largest 6.4 ms. `c2Threads` follows HotSpot's split, one C2 thread at 1 to 3 processors, tested at 2 and 3 against `-XX:ActiveProcessorCount`; master logged a limit of 10 and a target of 4 there. The lower findings: the interrupt fixed, JVMCI's queue name read (its native-library split recorded only), the doubles' scalar forms and the reinterpretations at 2,400 calls, the Vector API moved to a holder so the hook's class cannot fail on it, the class doc corrected. The four-core suite passed five of five in every run, nine on the fix; the review's further findings are fixed or recorded in `VARKA-395.md` 9.2, which proposes a check that the warm-up calls every Vector API method the emitter can call | post-merge reviews of #921 and #924; 1.1 point 1 lands every new type's kernels through this warm-up | medium |
| 396 | Three wrong answers from C2-compiled kernels on a GitHub-hosted runner. The first runner night after #926 (run 39200941445, 19 October 2026, master `3ea5b269520`, Zulu 25.0.4+7) ran `VarkaIrFuzzSuite`'s compiled-history test (row 274a) to the end for the first time on a runner, and three of its four JVMs failed it with an output mismatch: seed 202610190, long lane, iteration 17, history `quiet-remainder`, output 0 row 0 (want 2322424077287743304, got 4083781993637624380), shrunk under the default options to `(if (cmp:GE col:0:long col:0:long) col:0:long lit:0:long)` and `(int:SUB:NULL col:0:long col:0:long)` over one row of 0; seed 202610191, int lane, iteration 19, history `released`, output 1 row 0 (want -1862, got 8), shrunk to one `if` over `nextDay`, `weekOfYear` and `thursdayOf` under eleven non-default options; seed 202610193, long lane, iteration 10, history `warm-same`, output 0 row 1 (want 0, got -33583357646223), shrunk under the default options to `(greatest (int:SUB:NULL lit:1:long lit:1:long) (if (cmp:GT col:0:long col:2:long) col:0:long col:2:long))` over 15 rows. Each shrunk case failed the same way three times in its JVM. The histories run in order and a case's first failure ends it, so `cold`, run first and in the interpreter, passed in all three; only compiled histories differed. Nothing else has reproduced them. On the laptop (Zen 5, AVX-512 with `UseAVX=3` and `MaxVectorSize=64` on 256-bit units, OpenJDK 25.0.4.1) all three pass replayed alone at the same seed, iteration and history, and the runner's whole step passes on four pinned cores at the four seeds, every earlier draw run before each case as on the runner (the shared templates' profiles, the mechanism of row 389, included). A second runner dispatch at the same seeds the same morning (run 39205113943) passed the compiled test too. The failing runner's CPU and vector width are not in its logs, and the pool holds both AVX2 and AVX-512 machines. The test had never completed on a runner before: from 13 October every night timed out (row 392), so `m8/VARKA-392.md` 9's "the six dark nights hid no wrong answer" holds for the laptop's runs only, and the defect's age is unknown. The row makes the fuzz workflow log the CPU model, its vector flags and the JVM's `MaxVectorSize`, samples runners at these seeds until one fails again, reproduces each case on a host of that CPU class, names the cause, Varka's emitted code or runtime or C2 itself, and fixes it, or for a C2 defect reduces it, reports it to OpenJDK unless already reported, and keeps Varka from emitting the shape that reaches it. **Done when** the three cases are reproduced and their cause named, each is fixed or kept from the emitter with the JDK bug recorded, each shrunk case is a regression test under the history that failed, and a runner fuzz night at seeds 202610190 to 202610193 passes the compiled test | the runner fuzz night of 19 October 2026; 1.1 point 1 lands every new type's kernels through the same compiled code | unknown until reproduced; medium at least |
| 397 | **Done** (`VARKA-397.md` 9, 19 October 2026): `VarkaWarmupReachSuite` fails when a Vector API method an emitted kernel calls gets fewer than 2,000 calls from the template warm-up's rounds. The kernels' methods are read off the bytes of every coverage row's kernels, 200 fuzz shapes of each lane under the defaults and the matrix's base, and the rows and 20 shapes a lane under each other option configuration but the lane counts; the warm-up's calls are counted by running a copy of its rounds, rewritten to record each call's receiver and defined as a hidden nestmate of the warm-up; both are keyed by the method HotSpot keeps the count for, the implementation the call dispatches to (`Int512Vector.convertShape`, `AbstractVector.reinterpretAsLongs`, the mask class of the call's lane). Of the 75 methods the corpus reaches, 38 had fewer than 2,000 calls on master (the memory loads and stores, the masks' and species' calls, the conversions, eight scalar forms never called); the rounds now call each in a block of `THIN_CALLS_PER_ROUND`, 2,400 times or more, and a planted omission of `DoubleVector.add(double)` is named with no calls. The four-core end-to-end suite passed five of five in three runs on each arm, the warm-up a median of 336 ms against master's 375; the suite takes 15 to 28 s. Found by row 395 (19 October 2026, `VARKA-395.md` 9.2): the emitter calls `DoubleVector.add`, `sub`, `mul` and `div` with a scalar, which the template warm-up never called, and nothing caught the next wrapper the emitter adds. **Done when** a test fails when any Vector API method an emitted kernel invokes gets fewer than 2,000 calls from the warm-up's rounds, every new lane row's kernels included, with one planted omission caught | `VARKA-395.md` 9.2; 1.1 point 1 lands every new type's kernels through the warm-up | small to medium |
| 398 | **Done** (`VARKA-398.md` 8, 19 October 2026): the failures are consistent with a right verdict and a test that measured another entry; no runner log rules out a deoptimisation instead. The runners' byte counts match, per call, sixteen interpreted calls of the light group's masked loop (`date_add`, three boxed vectors a lane group: 397,696 bytes at 256 bits, 301,312 at 512, matched on the laptop to within 80 and 200 bytes a call by excluding that loop from compilation; the remainder of 280 and 400 bytes a call is unexplained). The warm-up and the evaluator call the kernel through `VarkaFusedKernel.run`'s scratch form, whose default method C2 compiles with the whole kernel inlined in a one-class JVM (`PrintInlining`), and the verdict read that code; the test called the seven-argument `run` directly, whose own standalone compiles race the verdict, and a claim of only nulls, which sends half the masked calls to the all-null shortcut, loses the race. Not reproduced on the laptop in about twenty starved runs, so the done-when's runner-log clause is met by inference until the next scheduled matrix runs the new probe line; the probe's new jitState line showed the kernel's own `run`, `runDense` and `runMasked` without code after the verdict in six of six. The test, renamed for what it asserts, now measures through the scratch form, reports the same calls through the kernel's own `run` beside them without asserting on them, the probe prints the kernel class's JIT state into the failure message, and the fork carries the matrix configuration and the JVM's `MaxVectorSize`, `UseAVX` and `UseSVE` (not the sanitizer or `ActiveProcessorCount`); under it the class-name test builds its plain key from the shape's options, the forked compile tests cancel under a lane count other than the JVM's preferred, and `methodByteBudget=0`'s 64-op cap gets one `skips.tsv` line. All 51 configurations pass the suite on the laptop on the pre-review commit, and the defaults and `lanesOverride=8` at 256 bits; the review's changes were run under the defaults at 512 and 128 bits and five configurations. Found beside it (`VARKA-398.md` 7): the verdict rests on inlining through a default method only one kernel class has reached, filed as row 405. *As filed:* The kernel warm-up probe's verdict on the runners. Found by row 394 (19 October 2026, `VARKA-394.md` 7): on option-matrix run 39168155152, `VarkaKernelWarmupSuite`'s "a warm-up claimed by a batch with only nulls compiles both of the kernel's drivers" reported `COMPILED` and then allocated 301,312 bytes over the batches with some nulls, against a limit of 20,480, under the defaults, on a runner beside a second test JVM: a verdict the later calls did not bear out. Its forked probe JVM receives no `varka.matrix.config`, so in the matrix it tests the defaults whatever the shard's option. **Done when** the cause is read from the probe's compilation log on a runner (a verdict taken before C2's code was installed, or a deoptimisation after it), the verdict or the test is fixed so it holds beside a second JVM, and the probe's fork carries the matrix configuration | `VARKA-394.md` 7; the warm-up's verdict is what every lane's first compiled batch rests on | small |
| 399 | **Done** (`VARKA-399.md`, 19 October 2026): #929's commands move into one composite action, `.github/actions/record-runner-platform`, which writes the CPU model, its AVX, SVE and ASIMD flags and the JVM's `MaxVectorSize`, `UseAVX` and `UseSVE` to the step summary and to a `platform.txt`; the fuzz workflow's two inline copies call it, the option matrix's shards call it before their suites and upload the file, and the weekly matrix, which is `build_and_test.yml` called with every job on, gets it in the `build`, `varka-engine` and `varka-scoped` jobs (the `build` shards upload it; the other two upload nothing, so their summary is the record). Jobs that run no kernel, or run SQL with the engine off, are left out and listed in the plan, which narrows the row's "every job" to these. The runner evidence is owed: the pull request's own full-matrix run for the `build_and_test.yml` jobs, and the option matrix's first run on master after the merge. | `VARKA-394.md` 7; the runner pool mixes 256-bit and 512-bit machines | small |
| 400 | **Done** (`VARKA-400.md`, 19 October 2026): the trap was a deoptimization, not a memory violation. `VarkaCompiledCheck`'s warm-up called the kernel outside the memory sanitizer's window, so C2 compiled the inlined `VarkaMemorySanitizer.check` speculating that the window stays null (`speculate_null_assert`), and the first compared call, inside a window, threw the warmed code of both drivers away; the kernel has no out-of-bounds access. The warm-up's calls now run in the window with their buffers registered, as the compared calls do, so the warm-up's own memory accesses are checked too. `VarkaTierWitnessSuite` passes 7 of 7 with the sanitizer on and off, and the defaults gate passes whole (`--split 3 -j 6`: 1,528 passed, none failed, 48 opt-in cancels). Milestone 7's debt entry for the failure is closed, and the lesson is in `skills/the-jit.md`. | row 28's gate run; 1.1 point 1's suites hold only if the gate is green | small |
| 401 | **Done** (`VARKA-401.md`, 20 October 2026): the premise held for the engine's warm-up and not for the fuzz suites. In a sanitized JVM a kernel warmed through `VarkaKernelWarmup` and then called 200 times in a window lost ten level-4 compiles to `make_not_entrant` at `VarkaMemorySanitizer.check` (read from `-Xlog:deoptimization=debug`; HotSpot names the reason `null_assert_or_unreached0`); with the warm-up's calls in the window the count is zero. `VarkaKernelWarmup.Job.run` now opens the window once for the whole warm-up and registers the job's memory, at no per-call cost and, with the sanitizer off, two returns on a `static final` per warm-up. The fuzz suites warm through `VarkaCompiledCheck`'s own warm-up, which row 400 had fixed, so this change never touched them; the sanitized defaults gate (`--split 3 -j 4`) reports 0 histories not reached for both, with 13 int-lane and 17 long-lane compiled cases in `VarkaIrFuzzSuite` and 4 and 4 in `VarkaCoverageCompositionFuzzSuite`, and passes whole: 1,736 passed, none failed, 51 opt-in cancels, 608 s. `VarkaSanitizedWarmupSuite` fails before the fix and passes after; the four warm-up suites and `VarkaWarmupEndToEndSuite` pass with the sanitizer on and off. The lesson is added to `skills/the-jit.md`. | `VARKA-400.md` | small |
| 402 | **Fix landed, proof pending** (20 October 2026; no plan file, the row is one helper; the cause is inferred, not confirmed, and the row closes when the next option matrix passes the test in every configuration): read from the run: the failing line is `VarkaFallbackReasonSuite.scala:60`, the `checkVarkaAnswerAndFallbackReason` helper that the test's `exactRefusalStatus=true` half calls (its `exactRefusalStatus=false` half passed in the same run), and the map it rendered held `null` for all ten reasons: the execution the helper chose reported none of the node's metrics. `VarkaFallbackReasons.rendered` took the session's highest-numbered execution, so an execution numbered after the checked query's must have run in that window; which one cannot be read from the log (the matrix keeps only the summary and the failure text), and it was not reproduced. The helper now reads the highest execution whose metrics hold any of the node's reason metrics (matched by the node's own accumulator ids, so no other query's execution qualifies), whatever runs after; it already waited for the listener bus, so a store that lags the bus stays a possible cause if the nulls return. The suite passes locally under the defaults; the option-matrix proof is pending until the matrix runs after merge. Found by the option matrix's first run after row 394 (run 39272577973, 19 October 2026): `VarkaFallbackReasonSuite`'s "a folded pair of reasons reads \"not reported\"" failed under `julianMap=false` and `materializeChronoPrefix=false` with every reason the SQL UI renders `null` (`null did not equal "2"`), though neither option touches fallback accounting and the two runs before passed it. Suspected, not confirmed: `VarkaFallbackReasons.rendered` reads the metrics of the session's highest-numbered execution, which need not be the checked query's. Done when the cause is read from the run (or reproduced locally), the helper reads the checked query's own execution, and the next option matrix passes the test in every configuration | `VARKA-394.md` | small |
| 403 | **Done in part** (20 October 2026; the fix landed, the row's measured items did not apply, as the next sentences say): `Slots.epilogueReadsMask` counts a column or a root as a reader of the long bounds mask only when it is in the driving lane, since an int column is loaded and an int root stored under a low-half mask of its own; the epilogue of a kernel of both lanes with no long column, long root, guard or prefix therefore builds no mask, and `VarkaEmitterMixedLaneSuite` pins it (it fails on the old predicate: one mask built in each of the two epilogue bodies, where none is read). The row's premise held in part: its example `cast(l AS int) + i` loads the long column `l` under the bounds mask, so its epilogue reads the mask and its bytes do not change, and `VarkaLaneConversionBenchmark`'s narrowing arm is that kernel, so its 128-bit rerun was not taken (it would measure the same class twice). Every kernel the change does reach is an int-valued root over widened ints and long literals only; an ANSI narrowing or an int add in such a kernel carries a guard, which reads the mask, so no coverage row is both reached and guardless, and the docs column and `width_audit.json` are unchanged; `emitted_bytes.json` moved in its fuzz blocks only. | `VARKA-28.md` 9.3 | small |
| 404 | The int-lane reference arm's rate at 16384 rows follows the tiered JIT. Found by row 28 (19 October 2026, `VARKA-28.md` 9.3, `results/int-arm-tiering.txt`): `i2 + i` read 24787 M rows/s in one run and 1161 in the next with unchanged kernel bytes, 20202 under `-XX:-TieredCompilation`, and the 262144-row rung is unaffected; which compile the harness's call site gets depends on what ran before it. A benchmark whose reference arm swings twentyfold cannot score a prediction against it. **Done when** the cause is read from the JIT's own output (`-XX:+PrintCompilation` or a compilation log), and the harness either isolates the arm or states its rung as unreliable in the result file | `VARKA-28.md` | small |
| 405 | **Done** (`VARKA-405.md`, 20 October 2026): the first half did not reproduce, so the warm-up is unchanged, and the second half is documented. With a second to fourth kernel class through the default method (three more shapes warmed after the first kernel's verdict, in a forked JVM, 12 forked runs, 10 of which read the state afterwards, on one to twenty-four cores beside up to six busy loops), the first kernel's own `run` and its four loop methods had C2 code and its calls allocated 0 to 9,600 bytes over sixteen calls, through the scratch form and through its own `run`, against an interpreted loop's 397,696; at the verdict itself (both runs that read it) the loops had C2 code and the three driver methods had none. A new test guards both entries after the three shapes (`VarkaKernelWarmupSuite`, 1.5 s, passes on master). What row 398's runners showed stays a transient of a queued or dropped loop compile that no run caught. The lanes cliff is measured (`lanesOverride=4` at a preferred 8: released at the deadline, 253,952 bytes a call) and stated at `VarkaEmitOptions.lanesOverride` and `VarkaKernelWarmup`, not refused: the override is reached only by the test hook and the option matrix, which reruns every suite at five lane counts (2 to 32). *As filed:* The kernel warm-up's verdict under a second kernel class. Found by row 398 (19 October 2026, `VARKA-398.md` 7): the warm-up's verdict reads a kernel through `VarkaFusedKernel.run`'s scratch default method, which in a JVM where only one kernel class has reached it C2 compiles with the whole kernel inlined, and it holds only while that stays so. In an executor a second kernel class through the same method fails that compile's class check and throws it away, and calls fall back to the first kernel's own `run` chain, which may have no C2 code - the runners of option-matrix run 39272577973 showed one of its loops interpreted after a `COMPILED` verdict - though the shape was reported `COMPILED`. Beside it, a product behaviour the warm-up suite's species cancel hides: under a `lanesOverride` other than the JVM's preferred int species every warm-up is `RELEASED` at its deadline while every call boxes, since the template warm-up compiles the shared templates for the preferred species only. **Done when** it is measured - a test in which two kernel classes reach the default method, reading the first kernel's compiled state and its allocation after its verdict - and the warm-up either waits for the kernel's own chain or enters it through a call site that cannot inline it, and the override's cliff is either refused where the override is set or stated where it is documented | `VARKA-398.md` 7; 1.1 point 1's lanes reach their first compiled batch through this verdict | small to medium |
| 406 | The 64-tail ladder's slow mode with the prefix computed once. Found by row 335a (20 October 2026, `VARKA-335.md` 9.2a, `results/VARKA-335-slow-mode-bisect.txt`): `VarkaSharedPrefixBenchmark`'s default (prefix-once) arm of the 64-tail ladder falls at times into a mode of 65 to 349 ns/row against about 2.7, bimodally, at the laptop's 256-bit width. Five alternated rounds per commit: no slow cell at `a93cb83e706` (12 October), `25a1bae6893` or `3f6f450bd71` (14 October); 10 of 25 cells slow at `05242b26d2e` (19 October, before row 28), 10 at row 28, 20 at row 334, 3 at row 335a's head. So it entered among the 19 product and harness commits from `3f6f450bd71` to `05242b26d2e`, which include the four template warm-up changes (`aa457c3797b`, `568582a9f77`, `089f7659b1b`, `e6aa78e3cd5`); suspected, at medium confidence and with no JIT log yet, a warm-up that leaves some kernels' Vector API calls virtual and boxing (`VARKA-295`'s failure family). The fast mode also moved, 2.3 to 2.7 ns/row. **Done when** the commit that introduced it is named by measurement, the cause is read from the JIT (`PrintCompilation`, deoptimisation and allocation logs of a slow round against a fast one of that commit), and the ladder's prefix-once arm shows no slow cell in five alternated rounds, with the result committed. **Investigated 20 October 2026, not done** (`results/VARKA-406-slow-mode.txt`): the commit is `568582a9f77` (the warm-up before any kernel; 7 of 10 runs' cells slow, against 1 of 15 at its parent and 0 of 15 with the warm-up switched off at `05242b26d2e`); the JIT log shows a slow arm running in tier-3 (C1) code for its whole timed run, its tier-4 request coming 37 s late and its C2 compile starting 4 s after that, not a C2 kernel with virtual boxing calls. Open: why the request is late. **Fixed in the benchmark by row 407** (the warm-up change proposed here was tested alone and dropped); the row's done-when on the slow cells is met, and why the request is late stays open as a finding | `VARKA-335.md` | medium |
| 407 | **Done** (20 October 2026; `results/VARKA-407-tier4-timing.txt`): the benchmark drives each arm at its own turn, just before it is timed, until its drive time is steady with C2 idle, as a stand-in for C2's code (the tier is not read; driven at least 8 times with C2 idle after each drive, then until two drives take the same time; a bounded wait that names the arm on a timeout), and the ladder's prefix-once arm shows no slow cell in five alternated rounds at 512 and at 256 bits (0 of 10 runs, against 5 of 9 on master). The warm-up change the row proposed, a wait for C2 to be idle at its end, was built and tested alone and did not remove the slow cells (7 of 10 runs slow), so it was dropped: the warm-up runs once, seconds in, and the one logged slow window is 17 to 54 s into the JVM. The first-queries question is answered from a PrintCompilation log of a real session: no late tier-4 request, kernel loops run in C1 code (1 entry) for about 0.4 s or interpreted where C1 refuses them (4 and 16 entries) for 0.4 to 3.6 s, then C2. Time the 64-tail ladder's arms in tier-4 code, and leave the warm-up's C2 queue drained. Found by row 406 (20 October 2026, `results/VARKA-406-slow-mode.txt`): the ladder's slow cells are arms timed entirely in tier-3 (C1) code, because the tier-4 request for their loops comes late (37 s in the logged slow arm, 0.1 s after the tier-3 install in the fast one) and the C2 compile starts 4 s after the request with idle compiler threads; the vector-template warm-up of `568582a9f77` is what makes it frequent (7 of 10 runs' cells slow, 0 of 15 with `-Dvarka.warmup.vectorTemplates=false`). Two changes: the benchmark drives each arm until its loops hold tier-4 code (repeat the required first drive and wait for the C2 queue to drain) before timing, and `VarkaVectorTemplateWarmup` leaves the C2 queue drained, as its own `awaitC2Queue` is meant to; on the way, read whether a real session's first queries also run in tier 3 right after the warm-up, the production-side cost. **Done when** the prefix-once arm shows no slow cell in five alternated rounds on the laptop's two widths, the first-queries question is answered from a JIT log, and the result is committed; why the request is late stays a finding of the row, not a requirement | `VARKA-335.md` | medium |
| 408 | C1 refuses the large masked loops of a real session's first queries. Found by row 407 (20 October 2026, `results/VARKA-407-tier4-timing.txt`, a real local session under `PrintCompilation`): for the 4- and 16-entry shapes C1 refuses the masked loop methods of 3.5 to 3.8 KB (five methods, "out of virtual registers in LIR generator"), so they run in the interpreter for 0.4 to 3.6 s until C2's on-stack-replacement compile lands, where the 1-entry shape runs in C1 code for about 0.4 s and then C2. A first query of a session therefore pays the interpreter for as long as the OSR compile takes. Not a correctness issue and not the benchmark's slow mode (row 406 was a harness artifact). **Done when** the cost is measured as the first-query latency of the 4-, 16- and 64-entry shapes in a fresh JVM against the same shapes with the loops split below the refusal size, read from the JIT log, and the record either names a change that pays for itself (a smaller loop method, a warm-up of the loop's shape, a lower OSR threshold) or says it does not, with the measurement committed | `results/VARKA-407-tier4-timing.txt` | small |
| 409 | **Done** (21 October 2026; `results/VARKA-409-ladder-repair.txt`): the refit (`00d9f79ec79`) and its base (`ca586f1dce6`), each with row 407's benchmark change carried onto it, were paired in five alternated rounds at 512 and at 256 bits and three at 128, no slow cell in 26 runs. Default arm (prefix once), time per row: 9.6% faster at 512 bits (rounds 8.9 to 11.0), 11.9% at 256 (11.0 to 14.8), 12.3% at 128 (11.6 to 14.6); recomputed arm: 19.0% slower at 512 (18.8 to 19.3), 16.1% at 256, 16.6% at 128. `VARKA-335.md` 9's table is corrected: its "256-bit" rows were the 512-bit runs (the pair script runs the wide width only), the 512-bit default arm did not move past the spread (10% to 9.6%), the 128-bit default arm moved from "no slower" to 12.3% faster, the recomputed arm's 20% and 17% are 19.0% and 16.6%. The benchmark's result files, the two bands (ten runs per width) and the provenance were regenerated from master `a350674efe4`: the two old slow cells of the wide file are gone (15.4 to 379.1 and 5.8 to 380.4 M/s), the 128-bit file's five-group cells moved +3% to +13% and its "every group split" cell from 3.9 to 146.8 M/s. | `results/VARKA-409-ladder-repair.txt` | medium |
| 410 | **Done** (21 October 2026; no plan file, the row is two suites and a lesson): `Varka suites: catalyst` under `lanesOverride=4`, the configuration the scoped CI run picks for any PR whose diff names the option, passes on a 512-bit host. The cause: the shared test JVM keeps one vector species per lane type and `VarkaSpeciesGuard` records the first size each vector class uses, so the configuration's 128-bit ints and 256-bit longs conflict with any suite that emits at another width outside a forked JVM. Two suites did, both by fixing a width the configuration had already set. `VarkaEmitterFramesSuite`'s long division case passed `lanes = 0` to `checkLongMatrix`, which overrides the matrix's lane count with the JVM's own, a 512-bit long species beside the configuration's 256 (it now passes `VarkaMatrix.base.lanesOverride()`, so it holds under every configuration and is unchanged at the defaults). `VarkaUnreadLocalsSuite`'s one loading test emits at the cost corpus's 16 lanes and loaded the class when the host's preferred width was 16 lanes, which a configuration's other kernels do not share (row 334's gate looked at the preferred width alone); it now loads when the width the JVM established, the matrix's override if one is set, else the preferred width, is the corpus's, and checks the census alone otherwise. Which suite is blamed depends on which ran first: CI's order failed 27 tests of other suites on the runner, `--split 2` here failed 35 (the split driver, chain, DateDiff, IfElse and Kleene suites and the guard-edge suite among them), a single JVM failed three (the guard-edge pair and the unread-locals test). Found by a temporary trace of every defined class's sizes with the guard off (the trace is not committed): only these two non-forked suites emitted off the configuration's widths. The guard and production code are unchanged. Evidence on this laptop (512 bits, catalyst, `lanesOverride=4`): 620 tests pass at `--split` 1, 2 and 3, the first two after failing 3 and 35 before the change; `lanesOverride=16`, `narrowHalfSpecies=true` and `validityByWidth=false` pass at `--split 2` (626 each); the defaults pass (757 passed); under `-XX:MaxVectorSize=32` (256-bit emulation) the defaults and all four configurations pass at `--split 2`. The lesson is in `skills/testing-and-debugging.md` | `skills/testing-and-debugging.md`, `m7/VARKA-246.md` 9.1 | small |
| 411 | **Done** (21 October 2026; no plan file, two suites and a lesson): the scheduled option matrix (run 39555729153, 21 October, 12 shards) failed five shards on tests that never ran under a configuration before: `VarkaUnreadLocalsSuite`'s month-once case and `VarkaEmitterValiditySuite`'s null-test prefix case, both added by row 334 (PR #938, 20 October), after the previous matrix run (19 October, whose three red shards were other tests). It is not the cost-model refit of row 335a and not `lanesOverride`. Each test builds its shape from `VarkaMatrix.base`, so a configuration changed the premise: `shareChronoPrefix=false`, `groupBudget=800` and `methodByteBudget=0` leave no group that loads a prefix (`scratchBytesPerRow` is 0, "no prefix is materialized"); `elideChronoMonth=false` keeps the month step in every fragment by the option's design (`ownMonth` is true), which the census counts as a dead op; under `division=DOUBLE_RECIP` and `DOUBLE_DIV` the census reports six unread `IntVector.sub` in the shape, an observation not yet diagnosed (row 412). The pinned case therefore checks neither of those two options' census; the first is by design, the second is row 412's. Both tests now take `shareChronoPrefix`, `groupBudget` and `methodByteBudget` from `VarkaEmitOptions.DEFAULTS` (the unread-locals case also `elideChronoMonth` and `division`) and every other option from the matrix, so the lane and cost configurations still run them. The third failure, `shareChronoPrefix=false` in "every shared slot ... is read", is the emitter declining the corpus's widest wide shape (`loopMasked23` 8082 bytes against the 8000-byte method budget, "output [39] cannot be regrouped smaller"), a stated decline: `classes` skips a shape declined for the method budget under that option only, and fails if more than a tenth of the corpus declines. The same tests reproduce on this laptop at 512 bits and under `-XX:MaxVectorSize=32`; they passed in no earlier matrix because they did not exist. Evidence: the two suites under the six configurations failed 1, 1, 1, 2, 2, 3 tests before the change and pass after (also under `lanesOverride=4` and at `MaxVectorSize=32`); the whole catalyst module (60 suites) under each of the six configurations, the defaults and `lanesOverride=4` on this laptop, one at a time with `-j 2`: every run passes except one test that also fails at the defaults on unchanged master, `VarkaWidthAuditSuite`'s "width_audit.json is this host's census" (the committed file has no `d2 - d` row; row 413), so 634, 634, 634, 634, 634 and 611 tests pass under `division=DOUBLE_DIV`, `DOUBLE_RECIP`, `elideChronoMonth=false`, `groupBudget=800`, `shareChronoPrefix=false` and `methodByteBudget=0` (116, 116, 116, 116, 116 and 139 cancelled as `PinsDefaults`), 627 under `lanesOverride=4`, and 721 at the defaults each time. The decline tolerance was narrowed (to the byte-budget decline, bounded) while those runs were going, so the two suites were rerun under all seven configurations at the final code, at 512 bits and at `-XX:MaxVectorSize=32`: all pass. The previous matrix run's (19 October) red tests were other tests, so no test of this row failed before the refit: they did not exist The lesson is in `skills/testing-and-debugging.md` | `skills/testing-and-debugging.md` | small |
| 412 | The census of unread reference locals finds dead vector ops under `division=DOUBLE_RECIP` and `DOUBLE_DIV`. Found by row 411 (21 October 2026): the shape of `VarkaUnreadLocalsSuite`'s month-once case emits six dead `IntVector.sub` (loopDense, epilogueDense, loopMasked twice, epilogueMasked twice) under both double dividers and none under the default, so the elision of unread locals does not see a value those dividers compute and nothing reads. Performance only, no wrong answer. Diagnose which value it is (a remainder the tail never reads is the first guess, unconfirmed), fix the elision or record the divider's cost, and lift the pin in the test that row 411 added | `VarkaChronoLowering`, `VarkaUnreadLocalsSuite` | small |
| 413 | `VarkaWidthAuditSuite`'s "sql/varka/width_audit.json is this host's census, per width" fails at the defaults on master. Found by row 411 (21 October 2026): at 70dab76c410 the test reports `d2 - d` absent from the committed file at 128, 256 and 512 bits (the row added by the date-minus-date fusion, row 39), with unsupported-operation entries at 128 bits that the file does not hold. Regenerate the file with its tool on a host of each width the audit covers, or state why this laptop's census may differ, and check why the scheduled runs on 256-bit runners do not report it | `sql/varka/width_audit.json`, `VarkaWidthAuditSuite` | small |
| 414 | `ym / -1` as a checked negation declines a batch correctly on `Int.MinValue`, but the error the row engine then raises lacks Spark's `== SQL ==` context block, so `VarkaMaybeThrows` and `VarkaCoverageDifferentialSuite` fail it; row 89 reverted the fusion without finding the cause. Found by row 89 and its review (21 October 2026). Evidence so far: the declined-batch fallback is not generally context-losing, since `VarkaYmDivideSuite` compares the full raised outcome (condition, parameters, message) for `(ym + ym2) / 7` over a sum that overflows and for `(ym + INTERVAL '2147483647' MONTH) / 7`, both fused and declined, and both engines raise the same error. So the loss is specific to the negation (`IntNeg` FAIL) or to the `DivideYMInterval`/`UnaryMinus` origin the fallback evaluates. **Done when** the cause is read (rebuild the reverted arm, print both messages, and trace which expression's `origin.context` the fallback's evaluation carries), the fix or its limit is stated, and `ym / -1` fuses with identical errors, or stays declined with the reason recorded | `VARKA-89.md` 9, `VarkaYmDivideSuite` | small |

## 4. Ordering

*Superseded 16 October 2026 by the owner's answer to question 4 of section 7: the order is 4.1.
The table below was the cheapest-first order and is kept as the record; its "why they wait" column
is one of the two sources 4.1's edges are read from.*

| wave | tasks | why they wait |
| ---: | :--- | :--- |
| 0 | 328, 28, 104, 333, 334, 335, 365, 375, 376, 377, 383 | 328 makes every later count honest; 28 gates most casts and the timestamp fields; 104, 333, 334 and 335 need nothing new; 365 needs nothing and every decimal row and 368's cast suites read through the paths it fixes, once question 3 of section 7 is answered (*16 October 2026: answered with option (iii), so 365 fixes no path; its three-path test pins the rule 350 and 364 match, and its documentation states the row path's disagreement*); 375, 376 and 377 say what the later rows can rely on and what they measure against (section 10); 383 because `DECIMAL` up to precision 18, `DOUBLE` and `TIMESTAMP_NTZ` are long-lane kernels, so every lane row's measurement and the warm-up's promise for them rest on the long lane's kernels compiling clean after a history (added 16 October 2026) |
| 1 | 329, 330, 331, 27, 332, 30, 95, 103, 39, 89, 336 | the first new types prove the plumbing on integral descriptors; 330 after 329's arms; 27 beside 331; 332 after 28 and 329; 30 and 95 after 104's checked forms; 39 after 28; 336 after 334, whose condition-over-validity node it is the second kind of (item 34's answer), and not after 331, which builds the boolean `<=>` itself |
| 2 | 338, 339, 340, 337 | the long lane's new type, after 28 (340's narrowing) and 332 (its casts' harness); 337's floating columns follow 341's canonicalisation node |
| 3 | 341, 342, 343, 344, 345, 367, 356, 357, 358, 346 | the floating split, comparisons first; arithmetic before the math functions; 345's contract and configuration, then 367's probe, before 356 to 358, which fuse only what 367 has measured; 344 after 341's lane; 346 once 344 and 350 have met their constructions |
| 4 | 347, 157, 348, 349, 350, 366, 351, 359, 360, 361, 362, 363, 364 | the decimal path last, its 64-bit load measured first, as item 1 asks; 366 after 350's guards and 341's lanes (*since the answer to question 3, 16 October 2026, 350's ANSI and TRY casts to `INT`, `SMALLINT` and `TINYINT` take 366's exact arm outside the guard, so that part of 350 lands with or after 366*); the lane pair (359) after the 64-bit rows, then its uncapped add and widening casts (360), the widening multiply (361), the full multiply (362), the precision-loss rescale (363) with 360's capped shapes, and the casts (364) |
| 5 | 352, 368, 369, 353, 354, 355 | 352 and 368 run from wave 1, as the casts land, and close here; 369 after 27 and its fixture; 354 last by definition; 355 written from what 354 records |

At the halfway point the milestone is reviewed against 1.1, not against this table, as milestone
7's was (`m7/PLAN.md` 9).

*Added 16 October 2026 by the review of section 10.* Every measurement at a 128-bit species
reports the release rate beside it, since `m7` row 374 closed "Done, no fix" (`m7/VARKA-374.md`
9) and there is no fix to wait for (10.3 item 4; until a correction of the same day this read
"waits for `m7` row 374, or reports its release rate beside it"); the place of the decimal wave is
question 4 of section 7. *Answered 16 October 2026: foundations first, in 4.1.*

### 4.1 Foundations first (16 October 2026)

*Added 16 October 2026, from the owner's answer to question 4 of section 7: "we should start from
the tasks that establish the foundation of Varka."* A foundation row is one other rows build on: a
lane, a load or a store, a shared IR node, or a harness or measurement that a later row's design or
numbers rest on. A function row is one nothing waits on. The edges are read from the rows of
section 3 and from the old table's "why they wait" column, not chosen, and every foundation row
lands before any function row, for every type. Among the foundations the lanes keep the old
table's type order, the narrow integral widths before the floating split (2.3); rows that share
files with a foundation row (330 with 329's descriptors; 343 and 344 with 341's lane; 157, 348 and
349 with 347's load) land beside it rather than reopen those files a wave later.

Wave 0 keeps its rows and its name, so every plan file that opens "wave 0" stays true. It is the
one place the rule is applied loosely: 333 and 335's first part (335a) are function rows, admitted
there because they need nothing new. The owner may move them to G1.

| wave | tasks | what they build on, and what builds on them |
| :--- | :--- | :--- |
| 0 | 328, 28, 104, 333, 334, 335 (335a), 365, 375, 376, 377, 383 | unchanged. 328's checks count every later row; 28 is under 30, 39, 332, 335b, 337, 340 and every implicit promotion; 104 under 30, 95 and 361 (its limb product); 334 under 336; 365's three-path test pins the rule 350 and 364 match and 368 reads; 375 under 331's mask; 376 under 329's arms and 30's per-host form; 377 under every family's first lane row; 383 under every long-lane measurement (338, 341's `DOUBLE`, 347); 333 and 335a need nothing new |
| F1 | 329, 330, 331, 27, 332 | the narrow and boolean lanes. 329, after 376, is under 330, 332, 335b, the narrow casts of 343 and 350 and 103's byte output; 330 beside it, and a foundation too, since its first 2^32 sweep is timed and the budget totalled before the next sweep lands (10.3 item 5), so the sweeps of 332 and 343 wait on it; 331, after 375, is under 27, the boolean casts of 332, 343 and 350, and 335b; 27 beside 331 (2.5), its boolean output under 369's kernel-against-kernel arm; 332, after 28, 329 and 331, is the casts' harness 338 builds on |
| F2 | 338, 341, 343, 344, 342, 345, 367 | the long and floating lanes. 338, after 28 and 332, is under 339 and 340; 341 under 337's canonicalisation node, 342 to 346, 366, 30's divisions in the `float` and `double` lanes and 350's floating casts; 343 beside it, its magic-number conversion under 344's `floor` and `ceil` to `BIGINT` on AVX2 and under 30's AVX2 form (10.1 item 4); 344's constructions under 346; 342 under the compositions of 356 to 358 (the old table's "arithmetic before the math functions"); 345's contract and configuration, then 367's probe, under 356 to 358, 345 held by question 2 with them |
| F3 | 347, 157, 348, 349, 350, 366, 359, 360 | the decimal lanes. 347's load and 157's store under 348 to 351, with 348 and 349 beside them over the same load; 347's de-interleave under 359's (2.8); 350, after 365, 341 and 343, is under 346, its guards beside 366; 366 under 350's three double-rule casts and 364's two-pass arm; 359, the 128-bit lane pair, after 347, is under 360 to 364 and holds the 128-bit store 351 needs; 360's uncapped add and widening casts are under 351's precision-19 results and every `BIGINT`-with-decimal shape (2.8), its capped shapes landing with 363 |
| G1 | 39, 95, 89, 335 (335b), 336, 30, 103 | function rows over the existing, narrow and boolean types. 30 after 104, 28, 329 and 341, its form per host by 343 and 376; 103 after 28 and 329, its decimal seconds being 351's shapes |
| G2 | 339, 340, 337, 346 | function rows over the long and floating lanes. 340's `SECOND` is 351's shape; 337 after 341 and the decimal loads; 346 after 344 and 350 |
| G3 | 361, 362, 363, 351, 364 | function rows over the decimal lanes. 362's 256-bit product feeds 363's capped shapes, and 363's division is what 364's decimal-to-decimal casts and 351's 128-bit rounding round through, so 363 leads both |
| G4 | 356, 357, 358 | the math family under the contract, held by question 2 with 345 |
| close | 352, 368, 369, 353, 354, 355 | 352 grows beside each lane from wave 0, as every lane's first pull request extends the oracles (2.10), 368 from 332's casts and 369 from 27, and the three close here; the rows over every type (336, 337, 352, 368) start when their first lane exists and close after the last; 354 last by definition; 355 written from what 354 records |

What follows from the order:

* **359 moves from last to F3.** It is the base of 360 to 364 and of 351's precision-19 results,
  and decimal columns are 18.3% of TPC-DS's and TPC-H's (10.4), so the lane pair no longer waits
  behind every function row of the milestone.
* **The cut order, if the halfway review needs one,** falls on function rows nothing else waits on,
  never on a lane: 362 and the capped shapes of 360 and 363 (the 129th bit and the 256-bit
  dividends), 346's declined bands and 357's compositions. 363's 128-bit division is not a
  candidate, since 364 and 351 round through it.
* **The math chain stays held by question 2** whatever the order: 345, and 356 to 358 under it.
  367's probe needs no lane and decides no row, so whether it runs before question 2 is answered,
  ahead of 345, which the old table placed before it, is open.
* **The existing types' function rows wait.** 30, 95, 103, 39, 89 and 336 sat in the old wave 1;
  they now land after F3 (2.9's note).

At the halfway point the milestone is still reviewed against 1.1, not against this table.

*19 October 2026, on the owner's word: rows 393 to 396, filed by milestone 7's close review and the
first runner fuzz night after this order was written, run beside wave 0 rather than in a wave, 394
and 396 first. 394's option matrix is where every new lane is checked across the emit options, and
it cannot judge one while it fails 51 tests; 396's wrong answers from C2's code sit under every
lane's kernels, and its runner sampling continues each night until a logged runner fails again.
395 lands before F1, since every lane's kernels pass through the template warm-up it fixes; 393's
remainder, the issue a failed scheduled workflow opens, is small and has no edge. Question 2 of
section 7 holds F2's row 345 and G4, not wave 0 or F1. Wave 0 starts with 328, whose checks count
every later row.*

*19 October 2026: rows 397 to 400, filed from the findings of rows 28, 394 and 395, run beside
wave 0 as those rows do. 397 lands before F1, since each lane row adds calls the emitter makes and
the warm-up must reach; 400 before F1 too, since every lane row runs the gate; 398 and 399 have no
edge.*

## 5. Verification

Every structural row cites the proof its row names:

* **Exhaustion** for every operation whose operands total at most 32 bits: the `TINYINT` and
  `SMALLINT` arithmetic (330, every operand pair), the casts from a source of at most 32 bits,
  every `FLOAT` bit pattern included (332, 343, 338), the integral division family at 32 bits (30),
  the bitwise family (335), the hash functions' 32-bit inputs (337).
* **SMT** (`dev/varka_prove.sh`, `VARKA-240.md`), stated over integers as `sql/varka/proofs/
  README.md` prescribes, for every 64-bit integer rule: the casts from `BIGINT`, `TIME`,
  `TIMESTAMP_NTZ` and the intervals (332, 340), the timestamp floor division and its magic
  constants (340), the decimal rescales with the `5^k` divisors, the no-overflow claim and the
  `ROUND_HALF_UP` division (348, 349, 350), the 64-bit division family (30, 104); and for every
  128-bit claim, in two layers - the ring identity, and the carry network with each limb product a
  free variable in `[0, (2^32 - 1)^2]` - at full width: the lane pair's comparisons, add and
  subtract with their carry, sign extension, the `10^p` checks, the widening and full multiplies
  and the magic division's constants (359 to 364), a reduced width only as a cross-check for 361
  and 362. The decimal-to-`INT` casts are mixed: SMT below precision 19, the sampled arm above.
  *Since the answer to question 3 of section 7 (16 October 2026)*: SMT for their LEGACY low bits,
  the sampled arm for their ANSI and TRY range check at every precision.
* **Sampled regions and the fuzzer**, never for a carry claim, where floating-point theory does not
  decide in useful time (`VARKA-241.md`): a `DOUBLE` source, a 64-bit value rounding to a floating
  type, the double rule of the decimal-to-`INT` cast (at every precision since the answer to
  question 3; before it, the 128-bit form's only), the exact decimal-to-floating arm
  (343, 350, 364, 366), with the regions of 2.2 and 2.7 and the tie windows of 352; the
  round-to-odd truncation as an SMT bit-vector claim with Theorem 3's Coq proof cited, exhausted
  through `binary16` (343).
* **Within the stated bound** - a count of representable doubles - for the 27 math functions under
  the ULP contract (2.7.1), on each host library, and nowhere else; their exact values bit for bit
  before the bound; a NaN never passing against a number.
* **The NaN rule** of `SCOPE_STANDARD_MODE.md` 3.5 in the bytes oracle and the fuzzers.
* **The coverage oracle** (the row engine) for every shape a row admits, with the poisoned null
  lanes and both validity fills of `VARKA-138.md`, its ANSI form comparing error classes, and the
  TLP `WHERE` oracle over the milestone's shapes (352).
* **The fuzzers**, IR and Spark-level, drawing each type from the pull request that adds it (352),
  the cross-mode properties over every pair and the `try_` arithmetic, and the count arm over the
  cached fixture (369).
* **Spark's own cast and hash suites** through the Varka evaluator, recorded per pair and mode and
  diffed against the matrix nightly (368).
* **The bytes oracle**: `emitted_bytes.json` unchanged for every existing shape when a lane lands
  (329, 331, 338, 341, 347, 359), and moved only by the shapes the row adds.
* **The measurements** of 329, 347, 359, 362, 363 and 366 follow the house methodology
  (`VARKA-14.md` 2.1) on an idle machine, at both vector widths, with the CI runners' readings
  beside the laptop's.
* `dev/varka_census.py --check`, `dev/varka_quote_check.py` at zero orphans and
  `dev/varka_toc.py --check` clean; the linters run locally before every push.

## 6. Risks

1. **The census is judgement as well as mechanism.** The registry half cannot be wrong without
   the script failing; the types and needs are read by hand. Mitigation: each type claimed fused is
   checked against a coverage row, and each lane row re-reads the census lines it touches and
   corrects them in its own pull request.
2. **The floating split touches every emitter path.** `Lane` derives its descriptors from an
   integral scalar, and 81 references in 16 files name the two lanes (2.3). Mitigation: the bytes
   oracle and the integral exhaustion unchanged as 341's gate, and the split landed before any
   floating arm.
3. **The decimal cache arm changes a file format.** Mitigation: a version tag the serializer checks,
   the row path's reader proven by the coverage oracle, and the de-interleave kept for every other
   source whatever the measurement says.
4. **Two-lane values touch the IR, the emitter and the fuzzers**, a change comparable to the
   floating split: every node over a 128-bit decimal carries two lanes, a lane pair holds four
   values per 512-bit register, half the long lane's density, and the shape key, the budget's op
   counts and the reference evaluator learn a value that is not one lane. Mitigation: 359 lands the
   pair with only comparisons and choice, the bytes oracle unchanged for every other shape, before
   any arithmetic.
5. **The proof files have a time budget.** The long-lane proof file already costs Z3 28 s on the
   laptop and 59 s on the runner, and this milestone adds the `5^k` divisors, the timestamp
   division and the two 128-bit layers. Mitigation: a second proof file run under cvc5, which
   decides the existing file in 8 s, each row stating its file's time before and after; the 128-bit
   claims are linear over integers and expected to decide at full width, and a claim that does not
   is reported with its solver time, not quietly moved to a reduced width. TPC-H q6's `sum()` stays
   Spark's: an aggregate is out of the milestone.
6. **The ULP contract is a departure users may not expect, and it is on by default.** Mitigation:
   the bound stated per function and host in the coverage and support tables, the default and its
   switch stated in `docs/sql-varka.md` where users look, the register entry, and the config-off
   arm of the differential suites.
7. **The exhaustive sweeps cost the nightly time.** Each 2^32 sweep is predicted at the int32
   sweeps' order and up to twice it (2.10). Mitigation: PR CI runs slices, the nightly the rest,
   and an idle window the first full pass, its times recorded against the prediction.
   The total is not known: the thirteen and a half minutes of `m7/PLAN.md` 1 are every int32
   lowering's sweep together, while this milestone asks for of the order of sixty sweeps against
   Spark's own `Cast`; 330 times its first and totals the budget (10.3 item 5).
8. **The new types will find disagreements.** NaN, `-0.0`, wraps at narrow widths and half-up
   rounding are where engines disagree. Each finding is fixed, or a named delta in the standard-mode
   register, or a row; the halfway review weighs the growth against 1.1.
9. **The floating conversions depend on the host.** `D2L`, `F2L`, `L2D` and `L2F` need AVX512DQ on
   x86, so the CI runners' AVX2 host and NEON take the magic-number and round-to-odd forms (2.7),
   which are slower and bounded below `2^51`. Mitigation: the host named in 343's row and in the
   coverage table's note, and each form measured on the host that runs it.
10. **The fork's two row paths disagree on decimals today** (2.8): the fallback's accessor builds a
    `BigDecimal`-backed value and the cache reader a long-backed one, so an ANSI cast of a decimal
    to `INT` can answer differently by batch with Varka on, before any kernel of this milestone.
    Mitigation: 365 in wave 0, its three-path test, and the upstream JIRA.
    *Since the answer to question 3 of section 7 (16 October 2026, option (iii)) the risk is
    accepted, not removed:* the readers stay as upstream has them, the kernel follows the
    fallback's double rule, so with Varka on kernel-served and fallback batches agree, and the
    disagreement left is between Varka-on reads and Varka-off reads of a cached table on the row
    path. Mitigation now: 365's three-path test as the regression test, a coverage note and a line
    in `docs/sql-varka.md` saying so, and the JIRA, which also reports the row reader's missing
    high-word check.
11. **`pow` has a case no probe has reached**: fdlibm's weak branch, whose quoted 636-ulp error
    has no source in the JDK tree.
    Mitigation: the guard of 2.7.1 declines the branch's domain to the row path, and the
    general-exponent kernel is enabled on a host only after 367 has measured the guarded domain.
12. **Arm runs a different program** (10.1 items 1 and 2). `VectorMask.fromLong` is intrinsic on
    aarch64 only with SVE2 BitPerm, and the JDK's SLEEF 3.6.1 sends `exp` and `pow` to infinity in a
    band where Spark's answer is finite. Mitigation: 375, 376, the output guard of 358 and 367's
    fixed vectors.
13. **A contracted value can decide a row** (10.3 item 1). A comparison, a cast to an integral type,
    a rounding function, `hash` or `IN` over a math function's value turns a one-step difference
    into a different row set or integer. Mitigation: the owner's answer to question 2 of section 7
    before 345 lands.

## 7. Open questions

1. **The post's headline: decided later by the owner.** The two options are the census share alone
   (2.11), or the share with a per-type benchmark beside it.
2. **The ULP contract under a discrete consumer** (10.3 item 1). (a) The contract covers values that
   reach an output through arithmetic only, and a contracted node under a comparison, a cast to an
   integral type, a rounding function, `hash` or `IN` declines, so filters and integral results stay
   Spark's; (b) the oracle learns a threshold-aware comparison, and users are told that a filter
   over a math function may select different rows; (c) the documentation alone. The review
   recommends (a) while the contract is on by default.
3. **Row 365 against upstream** (10.1 item 3). (i) Owner's decision A1 as it stands, with the
   departure from vanilla Spark on Arrow sources entered in `SCOPE_STANDARD_MODE.md`; (ii) A1 behind
   a configuration, off by default, so the fork answers as vanilla Spark until upstream agrees;
   (iii) no reader change, the kernel reproducing vanilla Spark's rule for an Arrow source - the
   double rule over a `BigDecimal`-backed value - for the three casts.

   *Upstream's record, read 16 October 2026.* SPARK-59443 (resolved, fixed) is a performance change
   that keeps the old behaviour on purpose: its description says the new accessor "retains
   BigDecimal-backed Decimal behavior for checked integer casts". Its pull request,
   apache/spark#58748, had one reviewer, who compared the old and new accessors on 63,523 reads
   covering "BigDecimal-backed rounding, overflow, and integral-conversion semantics" and approved;
   it merged to `master` and `branch-4.x` on 21 September 2026. Nobody discussed whether a
   long-backed value would be right, so the record keeps the behaviour without ruling on it. And
   vanilla Spark already answers by read path: its own `ArrowCachedBatchSerializer` (upstream
   `master`, the `DecimalType` case for precision up to `MAX_LONG_DIGITS`) copies a compact
   decimal's low word into the `UnsafeRow`, whose `getDecimal` returns a long-backed value, the
   rule Parquet gives, while `ArrowColumnVector` returns a `BigDecimal`-backed one. So (iii)'s
   "vanilla Spark's rule for an Arrow source" is not one rule but two, chosen by whether the batch
   is read as columns or as rows; (i) and (ii) make the column path agree with the row path and
   Parquet, and the upstream JIRA row 365 already owes can name both paths.

   **The owner's answer, 16 October 2026: (iii), no reader change.** Row answers follow vanilla
   Spark always, and (i) would make every Arrow consumer of the fork answer the three
   decimal-to-integral casts differently from vanilla Spark with Varka off as well. Under (iii) the
   kernel reproduces vanilla Spark's column-path rule (the double rule over a `BigDecimal`-backed
   value) for the ANSI and TRY casts of a decimal to `INT`, `SMALLINT` and `TINYINT`, which is what
   Varka's fallback reads through `ArrowColumnVector`. This supersedes owner's decision A1. What
   follows, in the rows: 350 builds the double rule below precision 19 as 364 does above it, on
   366's exact arm outside 350's guard, proven by the sampled arm and not SMT (2.2, 2.8, 5); the
   by-batch disagreement of risk 10 stays between Varka-on reads and Varka-off reads of a cached
   table on the row path, made visible by a coverage note and a line in `docs/sql-varka.md` when
   365 is built; the row reader's missing high-word check is reported as its own finding in the
   upstream JIRA, whose text `VARKA-365.md` 9 records and which is filed only with the owner's yes.
4. **The wave order** (10.4). (A) Cheapest first as section 4 has it, with a cut order written now
   (the review's candidates: 362 and 363's capped shapes, 346's bands, 357's compositions); (B) the
   64-bit decimal rows 347, 157, 348 and 349, which need the long lane and 365 only, ahead of the
   floating wave, and 350 after 341.

   **The owner's answer, 16 October 2026: "we should start from the tasks that establish the
   foundation of Varka."** Neither (A) nor (B) as written, but a third order, derived from the
   dependency graph that section 3's rows and section 4's "why they wait" column describe: every
   foundation row - one other rows build on, such as a lane, a load or store, a shared IR node or
   a harness - lands before any function row that nothing waits on, for every type. The order is
   4.1, the old table kept above it as the record. What it keeps of the two options: (A)'s cut
   order is written in 4.1 and falls on function rows only (362, the capped shapes of 360 and 363,
   346's bands, 357's compositions); (B)'s 64-bit decimal rows open the decimal foundation wave
   F3, though after the floating lanes rather than ahead of them, and 350 lands after 341. The
   128-bit lane pair (359) moves from last to that wave.

## 8. Explicitly out of milestone 8

* `STRING` and every cast to or from it, and `TIMESTAMP` with the local time zone: owner's
  decision, 14 October 2026. So are the functions that take or return them -
  151 registered names for strings, 20 for the zoned timestamp (2.1).
* Decimal division (`/`, `try_divide` and `div` over decimals): owner's decision, 14 October 2026
  (2.8).
* Aggregate, window and generator functions: Varka fuses scalar projections and filters; the
  aggregation items of `m9/SCOPE.md` (4, 5, 6) are a later milestone's.
* Nested types, `BINARY`, `VARIANT`, sketches and geospatial types, which have no representation.
* The nanosecond timestamp types, sixteen bytes a value (item 31's own exclusion).
* Everything else in `m9/SCOPE.md` not named above, among them the plugin (item 53), the
  configuration surface (item 64, and `VARKA-64.md` with it) and the guard walk (`VARKA-73.md`).
* Every milestone 7 row, open or closed: owner's decision, 14 October 2026.

## 9. Read and not taken

*Added 14 October 2026 from the review of this plan against six engine explorations and five paper
readings (the division, float-conversion, IEEE 754, math-accuracy and proofs-bits-fuzzing
readings), so that no one re-reads these for a milestone 8 row. Each entry is the reading's
verdict, not a measurement of Varka.*

* **Moller and Granlund's reciprocal algorithms, Robison's extra bit, and their timings.** Not
  needed in lanes: a 65th multiplier bit costs the same conditional add, and every Varka range is
  63 or 127 bits after the sign trick; the magic multiply of 2.8 wins on dependency depth and proof
  size (the division reading).
* **Ryu's printf form for `round`, `bround` and `truncate`.** The wrong rule - exact binary digits,
  not the shortest decimal Spark acts on - and too costly per lane. Ryu and Steele and White are
  background only (the float-conversions reading).
* **Round-to-odd as a way to widen the decimal-to-`DOUBLE` guard.** Theorem 3 does not: it needs
  a format wider than the target, which lanes have only for `FLOAT` (the float-conversions
  reading). Its working-precision form can (re-read 16 October 2026): Lemma 1 (AddOddEven) gives
  `RN(x + odd(z)) = RN(x + z)` when `5 |odd(z)| <= |x|`, so for `|u| > 2^53` and `s <= 22`, with
  `u = uh + ul`, `q = RN(uh / 10^s)` and `r = fma(-q, 10^s, uh)`, the answer is `RN(q + odd((r +
  ul) / 10^s))`, the odd neighbour chosen by a second FMA remainder: two divides, two FMAs. A
  sketch, sampled here at 200,000 values with no miss, unproven where `r + ul` must be exact and
  uncosted against 366's exact arm, so an option arm of 366.
* **Eisel and Lemire for the `round` tie test.** Nothing cheaper than the tie test of 2.7 (the
  float-conversions reading).
* **A sortable key per operand for the floating comparisons.** Six operations per operand against
  item 89's formulas; costed and not taken (the IEEE reading).
* **Harley-Seal popcount at 512-byte bitmaps; Fiat's modular and Montgomery machinery; TLP's
  grouping and aggregate oracles.** The bitmaps are too short for the first, the second serves
  modular arithmetic Varka has none of, and the third is for operators this milestone does not
  fuse (the proofs reading).
* **Correctly rounded math functions as an alternative to the contract.** Not one: fdlibm and
  Intel's `Math` stubs are not correctly rounded, so a correctly rounded kernel still differs from
  Spark wherever they misround, and by more than one step where fdlibm's own error passes one ulp
  (Gladman's Table 3 for the fdlibm-derived Newlib: `sinh`, `cosh`, `tanh`, `acosh` and `log10`
  above 2 ulp, `asinh`, `atanh`, `atan2` and `hypot` above 1). The first draft's "more often, not
  less" was never measured and runs the wrong way: such a kernel differs only where Spark's library
  misrounds, while a bound library differs there and also where it alone misrounds. No source
  states a misrounding rate (`READING_LIBM.md` 5); 367's MPFR column measures it. glibc's CORE-MATH
  functions serve that column, not lanes; `m9/SCOPE.md` 98 is the route to one bit pattern per
  host, not to Spark's bits (re-read 16 October 2026).
* **Other engines as a source of lane-form 128-bit decimal kernels, SIMD decimal de-interleaves,
  or per-function ULP bounds stated to users.** StarRocks, Velox, DuckDB, arrow-rs and
  spark-rapids-jni all have scalar 128-bit decimal arithmetic, which 2.8 borrows from, but none
  has a split-word layout, a CPU vector kernel or a de-interleave (the decimal-engines, arrow-rs
  and rapids notes, each section 1); per-function bounds are stated by libraries (SLEEF's tiers,
  Highway's headers), not by an engine. Varka's lane forms are its own (re-read 16 October 2026).

## 10. Review before the first row (16 October 2026)

*Written at the owner's request, after the eleven reading notes of this folder, against the plan as
merged at `7cc402b8f34` and the JDK 25 sources (`jdk-25-ga`); no row of this milestone had started.
**[V]** means read in the source or checked by a run here, **[I]** inferred. Nothing was run on an
Arm machine. Each finding is folded into the row named with it; three became rows of their own (375
to 377), and three are the owner's to decide (section 7, questions 2 to 4).*

### 10.1 Findings that change a row

1. **The JDK's SLEEF overflows early for `exp` and `pow` (358, 367).** The JDK bundles SLEEF 3.6.1
   (`libsleef/README.md`), and its generated Arm sources send `exp` and `pow` to infinity above
   709.78271114955742909 (`sleefinline_advsimd.h` lines 3297 and 3420, `sleefinline_sve.h` 3384 and
   3507) **[V]**. The true point is the logarithm of `Double.MAX_VALUE`, 709.782712893384, which
   current SLEEF uses (`LOG_DBL_MAX`; its changelog names the fix in 3.8). The 15,338,860 doubles
   between them give infinity on Arm where `StrictMath.exp` is finite **[V, counted here]**. The
   committed Arm probe (`dev/varka_canary/mathlane-aarch64-neoverse-n2.txt`) reads `EXP` at one step
   over its 262,144 inputs because none falls in the band, and a bit-pattern draw will never reach
   it. `expm1` uses the right constant **[V]**. Hence 358's output guard on SLEEF hosts and 367's
   fixed vector. *Observed 16 October 2026* (canary run 38884285755, the new
   `mathlane-aarch64-neoverse-n2.txt`): on the Neoverse N2 runner every `EXP` lane from the first
   double above the constant through 709.782712893384 is +Inf, and `POW` is +Inf wherever
   y * ln(x) passes the constant, `pow(Double.MAX_VALUE, 1)` included **[V]**. SVML uses the true
   limit: its band lanes equal `StrictMath` on the EPYC runner and on the laptop. At AVX2 SVML binds
   no `POW`, so that host's `POW` row is the fallback **[V]**.
2. **`VectorMask.fromLong` is the Java fallback on most Arm cores (375, 331).** `aarch64_vector.ad`
   refuses `VectorLongToMask` unless the core has SVE2 BitPerm (line 217) **[V]**. The emitter
   builds every validity mask with it (`VarkaBodyEmitter` 195, `VarkaVectorWalk` 355, 672 and 738,
   `IntRangeOps` 81 and 101, `SelectionVectorOps` 136 and 172) **[V]**, and 2.5's boolean leaf is
   the same call. NEON-only cores (Apple's, Graviton2) and SVE1 ones (Graviton3) lack BitPerm, so
   there it runs the fallback, which `skills/vector-api-and-width.md` describes as a second
   implementation **[I]**. Whether the Arm runner has BitPerm is not known: the canary's feature
   regex folds `svebitperm` into `sve`, and its log filter drops the JVM's feature lines. 374's
   allocation samples at a 128-bit x86 species (`maskFromArray`, `Mask.prepare`) are fallback code
   too, and one run with `-XX:+PrintIntrinsics` would say why **[I]**. *Observed 16 October 2026*
   (the same run): the Arm runner has BitPerm. `svebitperm` appears in `/proc/cpuinfo` and in the
   JVM's feature line, and `fromBitsCoerced` inside `fromLong` reads `late inline succeeded`
   wherever the species is a constant, with no `not supported` line **[V]**. So the runner cannot
   show the fallback that NEON-only and SVE1 cores take, and 375's allocation reading for those
   cores needs another host (`skills/vector-api-and-width.md`, the SLEEF and BitPerm entry).
   *Owner's decision, 16 October 2026:* `-XX:UseSVE=1` on the Neoverse N2 runner is accepted for
   the allocation half of that reading, since it simulates the JVM's feature set and so answers
   whether the fallback allocates per iteration; the timing half, on a real NEON-only or SVE1
   host, stays unbuilt until such a host exists (`VARKA-375.md` 2 and 9).
3. **Row 365 departs from a recent upstream choice (365; question 3).** The accessor that keeps
   "BigDecimal-backed Decimal semantics" is upstream's: SPARK-59443, merged on 21 September 2026
   (fork commit `f2dcec5bdd6`), reduced allocations while keeping "existing Spark decimal conversion
   semantics" **[V]**. 365 makes every Arrow consumer of the fork - the Python, R and external UDF
   runners among them - answer the ANSI and TRY casts of a decimal to `INT`, `SMALLINT` and
   `TINYINT` differently from vanilla Spark, with Varka off as well: a departure from "row answers
   follow vanilla Spark" outside Varka, which belongs in `SCOPE_STANDARD_MODE.md` or before the
   owner again. SPARK-59443's discussion, read since, is question 3's input: upstream kept the
   behaviour without ruling on it, and its own cached-batch row path already gives the long-backed
   value. *Answered 16 October 2026 (section 7 question 3): option (iii), no reader change, so 365
   no longer departs from upstream; the kernel follows the column path's double rule instead.*
4. **Row 30's full range does not reach the AVX2 runners (30).** `x86.ad` refuses a vector cast from
   long to a floating type and from a floating type to long without AVX512DQ
   (`match_rule_supported_vector`, lines 1757 to 1777) **[V]**. On the EPYC 7763 runners the four
   conversions of Monniaux and Pain's algorithm are therefore not intrinsic at all, and 343's AVX2
   forms stop at `2^51`, below which the double route is already exact. The choice is per host and
   static.
5. **345's binding check is half a check, and two more facts of `READING_JDK.md` were not carried
   (345, 357).** The DEBUG line proves the symbol, not the C2 call (its item 2.6); the call shows in
   the compiled code. SLEEF binds no `TANH` (2.5), so on Arm `tanh` is a per-lane `Math.tanh` loop.
   A masked math operator computes every lane and blends after (2.7), so a null slot holding a huge
   or subnormal value still pays the library call on its slow path; one blend before the call
   removes that.

### 10.2 What the reading notes said that the rows did not carry

The plan cited eight of the eleven notes; `READING_HIGHWAY.md`, `READING_LIBM.md` and
`READING_DECIMAL_ENGINES.md` reached no row. Folded in as citations and clauses, not rows:

* 359: the signed high-word compare is the pair's own, Highway's 128-bit compares being unsigned
  only (Highway 3.5).
* 361: a `BIGINT` operand arrives as `CAST(l AS DECIMAL(20, 0))` but stays a 64-bit value, so its
  product is 64 by 64, as StarRocks strips the cast (decimal engines 2.5).
* 362: Velox's leading-zero test as an option arm, and an SMT line naming which of `p1 + p2 + 1 <=
  38` and Velox's `< 38` the code uses (decimal engines 2.1, 2.2); arrow-rs's carry corners and a
  random `BigInteger` cross-check as tests (arrow-rs 3.4, decimal engines 2.6).
* 363: the rounding fixtures and the `10^k +- 1` and `5^k +- 1` divisor table (arrow-rs 3.2, 3.3),
  and rapids' compatibility triples and 38-nines edge (rapids 3.1).
* 345 and 367: SLEEF's stated tier beside each measured bound (SLEEF 6.5); glibc's and Intel's
  switching regions as fixed vectors (libm 6.3, JDK 2.8); a true-value column from MPFR through the
  foreign-function API, `libmpfr.so.6` being installed on the laptop, to say which side is wrong
  where the kernel and the row engine disagree.

### 10.3 What the rows should not assume, and how each is checked first

1. **A contracted value can decide a row (question 2).** The contract bounds a math function's
   value. A comparison, a cast to an integral type, `floor`, `ceil`, `round`, `hash` or `IN` over
   that value turns a one-step difference into a different row set or a different integer, which no
   bound in steps describes and which users see. The Spark-level fuzzer compares rows exactly, and
   its partition check runs four separate queries on the Varka session
   (`VarkaSparkFuzz.partitionBroken`) **[V]**, each choosing the kernel or the fallback per batch,
   so a row near a threshold can land in two partitions or in none **[I]**.
2. **The lane pair against a scalar loop (359).** Owner's decision A2 measures 362 and 363 against
   the row engine, which any allocation-free kernel beats. The design question is the lane pair
   against a scalar per-row loop inside the kernel on `Math.multiplyHigh` and
   `unsignedMultiplyHigh`, which needs no de-interleave and no sixteen-`vpmuludq` column sum. At a
   128-bit species a pair holds two values, and VARKA-149 found a scalar loop faster than a 128-bit
   kernel. 359's admission check measures it before the pair is built.
3. **Which operators are intrinsic where (376).** Items 2 and 4 of 10.1 are two operators the match
   rules refuse on a CI host. The record's operator table (`skills/vector-api-and-width.md`) is x86
   at 256 bits, and its two-species finding (6.4x to 12.8x slower) is a case 329's arms can create
   in one JVM.
4. **374 gates the 128-bit numbers (section 4).** At a 128-bit preferred species 41 to 43 of every
   100 compositions of the warm arm release at the deadline (`m7/VARKA-373.md` 9). This milestone's
   measurements at both widths, 367's Arm runs and the CI narrow step would measure that rate; they
   wait for 374, or report the rate beside every 128-bit number. *Corrected 16 October 2026:* 374
   closed "Done, no fix" (`m7/VARKA-374.md` 9: x86 C2 lowers no two-lane mask), so there is nothing
   to wait for, and every 128-bit number reports the release rate beside it.
5. **The sweep budget (330, risk 7).** The thirteen and a half minutes of `m7/PLAN.md` 1 are every
   int32 lowering's sweep together, while this milestone asks for of the order of sixty sweeps of
   2^32 (330 alone twelve to eighteen), each checked against Spark's own `Cast` **[I, counted from
   the rows]**. The first is timed before the next lands, the budget totalled, and the integer rules
   of 16-bit operands moved to SMT where that is complete and takes seconds.
6. **No row measures the new types against Spark (377).** 2.8's decimal speed-up is unmeasured, and
   a floating or boolean shape may tie Spark's generated code. A baseline class per family on the
   unchanged code, in its own pull request, makes each lane row's speed-up a diff.

### 10.4 The order

`m9/SCOPE.md` 1.1 counts the corpus's columns by type: `DECIMAL` is 18.3% of TPC-DS's and TPC-H's,
`DOUBLE` and `FLOAT` none (the taxi benchmark's measures are all doubles). The waves put the decimal
rows last and the 128-bit rows at the very end, and section 4 promises a halfway review without a
cut order. Question 4 of section 7 puts the two orders to the owner.

*Answered 16 October 2026 (section 7 question 4): foundations first, the order in 4.1. The decimal
lanes, the 128-bit lane pair (359) among them, land in wave F3, before any function row, and the
cut order is written in 4.1, falling on function rows only.*
