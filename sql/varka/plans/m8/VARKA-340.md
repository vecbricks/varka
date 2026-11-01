# VARKA-340: The `TIMESTAMP_NTZ` decomposition

## 0. What exists now (admission check)

From the code at `7ddd8f6715e` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`). The row's text is the
milestone plan's 2.6, bullet 340, and its paragraph on `TIME` to `TIMESTAMP_NTZ`. What the code
says about each premise is below, with the file and line it rests on. `t` is the NTZ value in
microseconds, `D` 86,400,000,000.

1. **Every field is total over the long, read at UTC.** The date fields are `GetDateField`s over
   `DATE` with implicit casts (`expressions/datetimeExpressions.scala:750-755`), so `year(ntz)` is
   `Year(CAST(ntz AS DATE))`, the cast being `floorDiv(t, D)` (`VARKA-338.md` 0, item 5).
   `hour`, `minute` and `second` over NTZ are `Hour`, `Minute` and `Second` (`:637`, `:654`,
   `:671`), chosen by the builders for any non-`TIME` argument
   (`expressions/timeExpressions.scala:360-376`, `:289-304`, `:457-472`); their input is
   `AnyTimestampType` with no cast and the zone `zoneIdForType`'s UTC (`:614-622`). Each is a
   getter of `LocalDateTime` at UTC (`util/DateTimeUtils.scala:104`, `:119`, `:134`), defined for
   every long (`sql/api/.../SparkDateTimeUtils.scala:123-129`, `:204`): no form raises, so each
   must fuse over the whole long or decline a batch to the row engine, never error.
2. **`extract` adds no expression.** `DatePart.parseExtractField` maps each field to the same
   classes (`expressions/datetimeExpressions.scala:4565-4597`), `DOW_ISO` to `Add(WeekDay(x), 1)`,
   which Varka already claims. Only `SECOND` differs: it is `SecondWithFraction` (`:4593`), a
   `DECIMAL(8, 6)` whose unscaled value is the microsecond of the minute, `getMicroseconds`
   (`util/DateTimeUtils.scala:147-149`, `:187-190`), so `floorMod(t, 60,000,000)`. `second(ntz)`
   itself is an int.
3. **`CAST(ntz AS TIME(p))` truncates.** It is `truncateTimeToPrecision(floorMod(t, D) * 1000, p)`
   (`expressions/Cast.scala:1073-1075`; `SparkDateTimeUtils.scala:398-400`, `:176-183`), whose
   `(n / f) * f` floors a non-negative `n`; for `p >= 6` it is the identity. It cannot fail.
4. **`TIME` to NTZ reaches a kernel only as `makeTimestampNTZ`.** `ComputeCurrentTime` rewrites
   the cast to `MakeTimestampNTZ(dateLiteral, time).replacement` with one date literal per query
   and zone (`optimizer/finishAnalysis.scala:182-191`), the `StaticInvoke` that
   `make_timestamp_ntz(d, t)` also becomes (`expressions/datetimeExpressions.scala:3688-3700`).
   An unrewritten cast reads the clock on each evaluation (`expressions/Cast.scala:950-955`).
   `makeTimestampNTZ` (`util/DateTimeUtils.scala:1223-1225`) is `instantToMicros` of the local
   date-time, which with its `MIN_SECONDS` arm is `d * D + nanos / 1000` exactly, raising
   `ArithmeticException` where that leaves the long (`SparkDateTimeUtils.scala:136-145`). The
   nanoseconds are truncated, not rounded (`NANOSECONDS.toMicros` of a non-negative count). There
   is no evaluation mode; the TRY form is `TryEval` (`:3934-3938`), which no arm claims.
5. **The date fields decline today, and the guard they need is not 339's.** The calendar arms take
   their day through `calendarInput` (`VarkaChronoCompiler.java:200-209`, `:228-229`, `:248-262`,
   `:681-689`) or `thursdayOver` (`:234-240`, `:301-315`), and the cast's `NarrowLane` has no day
   range (`varka/VarkaRangeAnalysis.java:286-287`), so they decline, pinned by
   `VarkaTimestampNtzCastSuite.java:222-236`. 339's planned `ntzDay` is a `GuardedDay`, whose range
   is `NARROW` (`varka/VarkaRangeAnalysis.java:143`, `:272`). Under the week forms the Thursday
   shift of `[-3, 3]` (`:175`, `:256-257`) takes that below `NARROW_MIN_DAYS`, where the lowering is
   undefined (`VarkaChronoCompiler.java:438-440`), and since the shift is not a runtime one `rearm`
   cannot re-guard it (`:761-764`, `:812-814`): `weekofyear` and `YEAROFWEEK` would decline, and so
   would a field over `trunc` to a year, quarter or month or by a format column, whose shift is
   `[-365, 0]` (`:185`; the level's `TruncDate` takes its day through `calendarInput`,
   `VarkaChronoCompiler.java:926`). A `GuardedRange` answers its own bounds (`:278`) and, over a
   computed value, checks live lanes only (`varka/Analysis.java:607-611`), so a null row holding
   `Long.MIN_VALUE` does not decline its batch.
6. **Five forms need no guard.** `dayofweek`, `weekday` and `DOW_ISO` compile their child through
   `over` (`VarkaChronoCompiler.java:159-163`), `next_day` its start date and `trunc(x, 'WEEK')` its
   date through `compileNode` (`:178`, `:190`; `:929-938`, a `NextDay(SubDays(x, 7))`), none with
   calendar admission, and their lowerings are exact for every int
   (`varka/VarkaVectorIR.java:1256-1298`). Nothing stops them fusing over the cast today; no
   coverage row or test shows it, and the census owes them here (`m8/census.tsv:236-237`).
7. **The time-of-day lowerings exist for `TIME`, over nanoseconds.** `getHoursOfTime` and its
   siblings are a `ConstDivide` of the nanosecond of the day and `remainderOfSixty`
   (`VarkaTimeCompiler.java:351-370`, `:599-604`), matched as `StaticInvoke`s through
   `TIME_TARGETS` (`:214-252`); `Hour` over NTZ is a plain expression, which no arm claims. Their
   divisors are proven (`sql/varka/plans/verify_double_division.py:120-128`). The `TIME`
   precision cast to a lower precision declines (`VarkaTimeCompiler.java:174-180`, VARKA-88).
8. **The day division is built and proven.** `floorDivLong` (`VarkaNodeCompiler.java:777-797`)
   is in `long_divide.smt2`'s divisor list with 10,546,875 and its four claims over every long,
   and in `verify_double_division.py`'s `NTZ_ROWS` (`:145-153`), from 338 (`VARKA-338.md` 10.1,
   item 4). 2.6's Granlund-Montgomery form for the second step (shift 71, multiplier
   223,875,151,780,487, first wrong dividend 1,933,372,399,218,749, rechecked here by exact integer
   arithmetic) needs a 64-bit high multiply, and JDK 25's `VectorOperators` has none
   (`src/jdk.incubator.vector/.../VectorOperators.java:549-588` in the local source).
9. **Mixed lanes are tested for a calendar field over an int column, not over a narrowed long.**
   `VarkaEmitterMixedLaneSuite.java:183` and `:379-391` run `Year` over an int column in kernels
   of both lanes; none feeds a calendar node from a `NarrowLane`.
10. **The decimal output does not exist.** `second(t)` with its fraction declines over `TIME`
    for the representation (`VarkaTimeCompiler.java:274-277`, `:430-431`); a decimal output is
    row 157's store (`VARKA-347.md` 0, item 8); 347's leaf (branch `varka-347-leaf`) reads one
    and writes none. Row 351 owns `second` with its fraction and `extract(SECOND FROM ntz)`.
11. **No baseline times these forms.** `VarkaTimestampNtzBaselineBenchmark` times a comparison,
    the arithmetic chain, the cast to `DATE` (70.5 ns a row on the row path at 256 bits with no
    nulls, its committed
    `VarkaTimestampNtzBaselineBenchmark-jdk25-runner-epyc7763-results.txt` in
    `sql/core/benchmarks/`) and a choice, and throws once a section fuses, which three do since 338.

### What differs from the row text and the milestone plan

- **The division clause of the done-when is 338's, already met** (item 8). This row adds the
  remainder's claim (2.1) and records the Granlund-Montgomery constants without building the arm,
  which the Vector API cannot express (8.2).
- **The guard is a `GuardedRange`, from 1 March -12799 to 15 August 33134.** 2.6 says the prefix
  is exact to "about 42400"; that is `NARROW_DECOMPOSE_MAX_DAYS`, the reach of an upward shift over
  a day already guarded. A value is held to `NARROW_MAX_DAYS`, and the floor moves up a year so the
  week forms and one field over a level `trunc` admit it (item 5). 339 reuses this helper in place
  of its `GuardedDay` (its 2.4, amended here).
- **`ntzDay` is not on master**: #1064 is 339's plan. Whichever row lands first builds it (2.1).
- **`SECOND`'s decimal waits on the store (157) and its arm (351)**, not on 347's load (item 10).
- **`CAST(ntz AS TIME(p))` below precision 6 is built**, a truncation the TIME narrowing cast
  will share (item 3).

## 1. Goal and non-goal

The date fields, `hour`, `minute`, `second`, their `extract` and `date_part` forms, `CAST(ntz AS
TIME(p))`, `CAST(time AS TIMESTAMP_NTZ)` and `make_timestamp_ntz(date, time)` fuse with the row
engine's answers in LEGACY and ANSI; a batch the kernel cannot answer goes to the row engine. Not
this row: `extract(SECOND FROM ntz)`'s decimal output (351, 157), the TRY forms, the nanosecond
timestamp types, the zoned type, the six-field `make_timestamp_ntz` (351).

## 2. Design

Existing IR nodes only: `BitOp`, `ConstDivide`, `IntArith`, `GuardedRange`, `WidenLane`,
`NarrowLane` and the calendar nodes. No new node, lowering or emit option; no existing shape's
bytes move.

### 2.1 Two helpers, beside `floorDivLong`

    microsOfDay(t) = IntArith(SUB, WRAP, t, IntArith(MUL, WRAP, floorDivLong(t, D), slot(D)))
    ntzDay(t)      = GuardedRange(NarrowLane(floorDivLong(t, D)), NARROW_MIN_DAYS + 365,
                                  NARROW_MAX_DAYS)

`microsOfDay` is exact modulo `2^64` (the product wraps only at the lowest day, the difference
never) and lies in `[0, D)`, so it is `floorMod(t, D)`; it is 339's `r1` (`VARKA-339.md` 2.5). A
claim in `long_divide.smt2` states it over every 64-bit `t`. `ntzDay`'s bounds are part of the IR
and so of the shape key; its lower bound is `NARROW_MIN_DAYS - TRUNC_SHIFT.lo()`, named so, room for
the widest downward shift a calendar node makes (the Thursday's is three days). A batch holding a
live day outside goes to the row engine.

### 2.2 The date fields (calendar family)

`calendarInput` and `thursdayOver` compile a child that is `CAST(ntz AS DATE)` as
`ntzDay(compile(ntz))`; everything else compiles as today. That serves `year`, `month`, `day`,
`dayofmonth`, `quarter`, `dayofyear`, `last_day`, `weekofyear`, `trunc` (to a year, quarter or
month, and by a format column), and `extract` of YEAR, YEAROFWEEK, QUARTER, MONTH, WEEK, DAY and
DOY. The plain cast keeps 338's unguarded form, exact over every long, so `SELECT CAST(ntz AS DATE)`
never declines. `dayofweek`, `weekday`, `DOW_ISO`, `next_day` and `trunc(ntz, 'WEEK')` take that
plain form (item 0.6). A cast under a day shift (`year(date_add(CAST(ntz AS DATE), i))`) stays an
unknown producer and declines (7).

The kernel is driven at the long lane and runs the calendar nodes at the int lane, `VARKA-28.md`
9's mixed form; an int or `DATE` result is stored from the int lane.

### 2.3 The time of day (time family)

`n = IntArith(MUL, WRAP, microsOfDay(t), slot(1000))`, the nanosecond of the day under `2^47`,
which is `CAST(ntz AS TIME)`'s value. `hour`, `minute` and `second` over NTZ are the `TIME`
lowerings over `n`: the three arms of item 0.7 become helpers taking the nanosecond node, called
by the `StaticInvoke` arms and by new `Hour`, `Minute` and `Second` arms that require an NTZ
child and ignore the session `timeZoneId` the analyzer fills in, which NTZ does not read. Every
dividend is under the `TIME` bound, so the proven divisors serve; nothing is guarded.

`CAST(ntz AS TIME(p))` is `n` for `p >= 6`, and `IntArith(MUL, WRAP, ConstDivide(n, f,
NANOS_PER_DAY), slot(f))` with `f = 10^(9 - p)` below; the divisors `10^4`, `10^5`, `10^7` and
`10^8` join `verify_double_division.py` and the proof file's list.

`SecondWithFraction` keeps declining. Its value, the microsecond of the minute, is two more
`ConstDivide`s of `n` over proven divisors, but a helper with no caller until 351 would be dead
code here, so 351 builds it beside its decimal output (8.1).

### 2.4 `TIME` to NTZ and `make_timestamp_ntz` (time family)

One arm, on the `makeTimestampNTZ` `StaticInvoke`, added to `TIME_TARGETS` as
`make_timestamp_ntz`:

    out = IntArith(ADD, FAIL, IntArith(MUL, WRAP, day, slot(D)),
                   ConstDivide(nanos, 1000, NANOS_PER_DAY))

`nanos` is the `TIME` operand (a column or literal of any precision); `day` is the date operand.
A date column is 338's `GuardedRange(WidenLane(d), -106751991, 106751991)`. A date literal, the
cast's case, is checked at compile time against the same bounds (outside, every non-null row
raises, and the expression declines) and is then `WidenLane` of its int slot with no guard, so
one kernel serves every date: the value travels as a runtime argument and is not in the shape key
(`varka/VarkaShapeKey.java:28-33`). Inside the bounds the product is exact; the checked add raises
where Spark does, on day 106,751,991 after 04:00:54.775807, and cannot underflow on day
-106,751,991. Day -106,751,992, whose evening is representable, is declined whole, as 2.6 has it.
An unrewritten `CAST(time AS TIMESTAMP_NTZ)` declines by name (item 0.4).

## 3. Tests and oracles

The oracle is Spark's own evaluation, through the reference evaluator and the differential
suites, and `java.time` at the edges.

1. **First, the risk** (item 0.9): in `VarkaEmitterMixedLaneSuite`, `Year`, `WeekOfYear` and
   `LastDay` over `ntzDay` of a long column, at the long lane's 2 and 8 lanes, the four null
   patterns, both bodies; a live lane past the guard declines the batch, a null lane holding
   `Long.MIN_VALUE` does not.
2. **Coverage rows**, a family "TIMESTAMP_NTZ fields": each form of 2.2, `dayofweek`, `weekday`,
   `extract(DOW_ISO ...)`, `next_day`, `trunc(ntz, 'WEEK')`, `hour`, `minute`, `second`,
   `hour(ntz) > 12`, `CAST(ntz AS TIME)` at precisions 0, 3 and 6, `make_timestamp_ntz` over a date
   column and over a literal, through the differential suite at every position, with domains where
   the guards decline.
3. **Conformance**: the NTZ suite's cast check, cancelled for the `TIME` pairs, runs once
   `casts.tsv` marks them fused (`VarkaTypeConformance.java:345-367`). `VarkaCastMatrixSuite`
   compiles the bare cast, which declines; for `TIME` to NTZ it compiles the cast after
   `ComputeCurrentTime.applyForExpression` (`optimizer/finishAnalysis.scala:124-125`), so the
   cell records what a query compiles.
4. **The sweep against `java.time`**: a JUnit sweep beside `VarkaRangeRulesSuite`, every day
   `ntzDay` admits at a time of day drawn per day plus 0 and `D - 1`, the scalar composition of
   2.1 and 2.2 against `LocalDateTime`'s fields and `DateTimeUtils`; every second of a day at a
   drawn microsecond over sampled days and the long's ends for 2.3, against the `LocalDateTime`
   getters, `getMicroseconds` and `timestampNTZToNanosOfDay`. The emitted kernels over the same
   days opt-in under `-Dvarka.sweep=true`, beside `VarkaEmitterChronoSuite.scala:621`.
5. **The edges**, in `VarkaTimestampNtzSuite`, each in one batch of three so the others stay
   fused, LEGACY and ANSI: `Long.MIN_VALUE` and `Long.MAX_VALUE` under `year` (declined, the row
   engine's year, which `java.time` gives as -290308 and 294247) and under `hour`, `minute`,
   `second` and the cast to `TIME` (fused); the guard's days and one past each; `make_timestamp_ntz`
   on day 106,751,991 at 04:00:54.775807 (fused) and .775808 (raised from the fallback), on day
   -106,751,991 at midnight (fused) and on day -106,751,992 (declined, Spark's answer); a `TIME(9)`
   ending in .999999999, truncated.
6. **The date pinning of 2.6**: `SELECT CAST(tm AS TIMESTAMP_NTZ), year(ntz)` over a view whose
   `ntz` is past the guard in every other batch, so half the batches decline: every row carries one
   date, the plan's literal. The same query under the session zones `Etc/GMT-14` and `Etc/GMT+12`,
   26 hours apart so their current dates always differ, reuses the cached kernel class and answers
   its own date. The flipped test: `dateFieldDeclines` asserts fused.
7. **Proofs**: the remainder claim and the new divisors in `long_divide.smt2`, both solvers; the
   Granlund-Montgomery constants and their first wrong dividend checked by exact integers in
   `verify_double_division.py`, beside `NTZ_ROWS`.
8. **The fuzzers**: the Spark fuzzer's grammar gains the forms over its NTZ and `TIME` columns;
   the IR grammar's mixed shapes gain a calendar node over a guarded narrowed long.
9. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order: `coverage.json`, the docs tables,
   `width_audit.json`, `emitted_bytes.json` (new shapes only), `census.tsv`, `casts.tsv`,
   `casts_fused.tsv`.
10. **The PR build and the dispatched option matrix** over the new and changed suites, the mixed
    kernels in JVMs of their own (`VARKA-338.md` 10.4).

## 4. Predictions and costs

The measurement reruns the baseline (5, step 1) with the fuse check lifted, on the runners' EPYC
7763 at 256 and 128 bits, diffed against the committed files.

1. **No existing shape's bytes move.**
2. **`year(ntz)` at least five times faster than the row path at 256 bits.** No fused NTZ rate is
   committed (the baseline's "varka on" is the row path). The basis is the `DATE` family's:
   `year(d)` 0.8 ns a row against 12.3 with Varka off, `weekofyear(d)` 1.2 against 30.5, 15 and 25
   times (`sql/varka/bench/benchmarks/DateSurface-varka-jdk25-results.txt` and
   `DateSurface-varka-off-jdk25-results.txt`, one core, the laptop at 512 bits), over an int
   column. The NTZ form adds the long division and runs the long lane at half the lanes, so the
   bound keeps a third of the smaller ratio. `hour(ntz)` and `CAST(tm AS TIMESTAMP_NTZ)` have no
   fused analogue on record: predicted faster than the row path at both widths, their ratios
   recorded; `year(ntz)` at 128 bits likewise.
3. **Test time**: the scalar sweep under 10 s (`VARKA-281`'s 1.5 s over the same days with
   `LocalDate`; the `LocalDateTime` decode taken at a ratio of 1, not the table's 0.08); the new
   `sql` tests under 30 s at `VARKA-243`'s ratio of 2.

Cost: medium, as the row says; about three days after the baseline.

## 5. Order and files

1. **The baseline**, its own pull request on the unchanged code (the house rule for a change that
   improves something): `VarkaTimestampNtzFieldsBaselineBenchmark`, `year(ntz)`, `weekofyear(ntz)`,
   `hour(ntz)`, `CAST(ntz AS TIME)`, `CAST(tm AS TIMESTAMP_NTZ)` and `make_timestamp_ntz(d, tm)`,
   each with and without nulls, its fuse check behind a property so the after-run reuses it.
2. **The helpers** (2.1) and the mixed-lane test (3.1).
3. **The date fields** (2.2) with the sweep and the flipped test.
4. **The time of day** (2.3): the three fields and the cast to `TIME`.
5. **`TIME` to NTZ** (2.4) with the pinning test.
6. **The generated files, the measurement** and its verdict.

**Against 339.** Nothing here needs 339's code: the shared parts are 2.1's two helpers, which the
first of the two rows to land builds and the second reuses. 339 takes this `ntzDay` (its 2.4,
amended in this branch), so its month forms newly decline a timestamp from 1 March -12800 to 28
February -12799, the year below the floor; they are admitted over either guard, `addMonths`
taking its day through `calendarInput` (`VarkaChronoCompiler.java:325`).
This row can start before 339 lands; both touch `VarkaNodeCompiler.java` beside `floorDivLong`,
`census.tsv` and the generated files, so the second rebases.

| file | what |
|---|---|
| `VarkaNodeCompiler.java` | `microsOfDay`, `ntzDay` |
| `VarkaChronoCompiler.java` | the NTZ cast under `calendarInput` and `thursdayOver` |
| `VarkaTimeCompiler.java` | the field helpers and arms, the cast to `TIME`, `make_timestamp_ntz` |
| `varka/VarkaFamily.java` | the calendar and time families' `covers` text |
| `sql/varka/proofs/long_divide.smt2`, `VarkaProofFiles.java` | the remainder, new divisors |
| `sql/varka/plans/verify_double_division.py` | the new rows, the constants' check |
| tests | mixed-lane case, coverage rows, sweep, edges, pinning, matrix rewrite, flipped test |
| benchmark | the baseline class, its results files at both widths |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| `sql/varka/plans/m8/` | `census.tsv`, `casts.tsv`, `casts_fused.tsv` |

## 6. Out of this row

`extract(SECOND FROM ntz)`, `date_part('SECOND', ntz)` and the six-field `make_timestamp_ntz`
(351, after 157's store); `try_make_timestamp_ntz` (`TryEval`); `TimestampNTZNanosType` and
`MakeTimestampNTZNanos`; the zoned type and the functions Spark coerces to it (`date_trunc`,
`months_between`, `timestampdiff`); the month forms (339).

## 7. Declines that stay

A batch with a live day outside `ntzDay`'s range; a date-field cast under a day shift; a field over
`trunc(ntz, 'WEEK')` (`year(trunc(ntz, 'WEEK'))`), whose plain cast is an unknown producer; literal
shifts stacked past the year of room (`weekofyear(trunc(ntz, 'YEAR'))`, down to -368 days); an
unrewritten `TIME` to NTZ cast; a literal date outside `+-106,751,991` days; a batch with a date
column outside it.

## 8. Decisions settled by the row

No question is open; the row text answers both that the first draft asked.

1. **`SECOND`'s decimal is 351's.** Row 351 owns `extract(SECOND FROM ntz)` (`m8/PLAN.md:1052`)
   and row 340 says "`SECOND` a `DECIMAL(8, 6)`, with 351". This row is done without it, and the
   microsecond-of-the-minute helper waits for 351 too (2.3).
2. **The Granlund-Montgomery arm is not built.** The done-when asks only "its magic constants
   recorded", and the Vector API has no 64-bit high multiply (item 0.8): the constants and their
   first wrong dividend are checked by exact integers (3.7).

## 9. Done when

Row 340's text holds: each form of 1 fuses, `extract(SECOND ...)` left to 351 (8.1); the date
pinning of 3.6 passes with half the batches on the fallback; `Long.MIN_VALUE` and `Long.MAX_VALUE`
are pinned through the fallback; the division is proven in the long-lane proof file's divisor list,
its constants recorded. And: the sweep of 3.4 passes; the claims hold under both solvers;
`dev/varka_census.py --check` passes; no existing shape's bytes move; the PR build and the option
matrix pass; the measurement is committed with its predictions scored.

## 10. Outcome

(Written when the work lands.)
