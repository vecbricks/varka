# VARKA-339: The `TIMESTAMP_NTZ` arithmetic

## 0. What exists now (admission check)

From the code at `a5ed9235723` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`). The row's text is the
milestone plan's 2.6, bullet 339. What the code says about each premise is below, with the file
and line it rests on.

1. **Every form is UTC long arithmetic, with no evaluation mode.** `zoneIdForType` answers UTC
   for NTZ (`expressions/datetimeExpressions.scala:71-75`), and none of the five expressions
   carries an `evalMode`, so LEGACY and ANSI compute and raise alike:
   - `ntz - ntz` is `SubtractTimestamps` (`:4767`), `ChronoUnit.MICROS.between` of the two UTC
     wall clocks (`util/DateTimeUtils.scala:930`). `LocalDateTime.until` (JDK 25,
     `LocalDateTime.java:1678-1723`) moves the day count one towards zero before
     `multiplyExact(days, MICROS_PER_DAY)` and `addExact` of the time part, so the product never
     exceeds the true difference: the result is `Math.subtractExact(end, start)`, raising exactly
     when it does. Under `spark.sql.legacy.interval.enabled` it is a wrapping `CalendarInterval`
     instead (`:4806`).
   - `ntz + dt` is `TimestampAddInterval` (`:2560`), `timestampAddDayTime` at UTC (`:2630`,
     `DateTimeUtils.scala:310`): days, then micros, on a `ZonedDateTime` whose date range is far
     wider than a long's, then `instantToMicros`, exact over every long through its `MIN_SECONDS`
     arm (`sql/api/.../SparkDateTimeUtils.scala:117,136-145`). So it is `Math.addExact(ts, dt)`.
   - `ntz + ym` is `TimestampAddYMInterval` (`:2988`), `timestampAddMonths` at UTC (`:3017`,
     `DateTimeUtils.scala:282`): `LocalDate.plusMonths` with its day-of-month clamp, the time of
     day carried, then `instantToMicros`.
   - `timestampadd(unit, q, ntz)` is `TimestampAdd` (`:5061`), its unit a `String` field and its
     quantity `LongType` (an int column arrives widened). `DateTimeUtils.timestampAdd`
     (`:945-990`): MICROSECOND adds `q`; MILLISECOND to HOUR add `multiplyExact(q, unit)`; DAY
     takes `toIntExact(q)` days; WEEK `multiplyExact(toIntExact(q), 7)` days in int; MONTH,
     QUARTER and YEAR `toIntExact(q)` months times 1, 3 or 12 by an int `multiplyExact`. Every
     `ArithmeticException` or `DateTimeException` becomes `DATETIME_OVERFLOW` (`:988`); an
     unknown unit, NANOSECOND among them for a microsecond type, raises its own error.
   - `time_bucket(width, ntz[, origin])` is `TimeBucket` (`:5242`), width and origin foldable
     (`:5274`, `:5301`), the default origin the epoch (`:5418`). For a day-time width,
     `timeBucketFromTimestampDTInterval` (`DateTimeUtils.scala:1372-1397`): `subtractExact(ts,
     origin)`, `floorDiv` by the width, and the start `origin + multiplyExact(k, width)`,
     by `addExact` below a day and by `timestampAddDayTime` from a day up (`:1348-1358`), the
     same sum at UTC. A width of a day or more also evaluates `boundary(k + 1)`
     (`:1391`) though at UTC the linear estimate is exact, so it raises when the *next* bucket's
     start overflows. A year-month width is `timeBucketYMInterval` (`:1429`): the year and month
     of the timestamp, then `plusMonths` of the origin, twice.
2. **How the forms arrive.** `BinaryArithmeticWithDatetimeResolver.scala` (in `analysis/`) gives
   `ntz + dt` as `Cast(TimestampAddInterval(ntz, dt), TIMESTAMP_NTZ)` (`:98-99`), whose identity
   cast 338 compiles to its child (`VarkaNodeCompiler.java:433-434`); `ntz - dt` as the same cast
   over `DatetimeSub(..., TimestampAddInterval(ntz, UnaryMinus(dt)))` (`:143-146`); `ntz +/- ym`
   as `TimestampAddYMInterval`, the minus over `UnaryMinus(ym)` (`:75-76`, `:118-119`); `ntz -
   ntz` as `SubtractTimestamps` (`:147-151`), a `DATE` operand cast to NTZ first
   (`TypeCoercionHelper.scala:875-886`). `DatetimeSub` is `RuntimeReplaceable`, which
   `VarkaNodeCompiler.fallback` compiles as its replacement (`:760-762`). `UnaryMinus` over an
   ANSI interval is `negateExact` whatever its flag (`expressions/arithmetic.scala:99-100`), so
   `ntz - dt` raises at `dt = Long.MIN_VALUE` and `ntz - ym` at `Integer.MIN_VALUE`. The TRY forms
   exist only as `try_add` and `try_subtract`, which resolve to a `TryEval` (`TryEval.scala:96`)
   that no compiler arm claims.
3. **The long lane has the checked add and subtract, and nothing builds them for NTZ.** The
   column leaf and literal slot take NTZ, `dt` and `bigint` alike (`VarkaTimeCompiler.java:158-
   167`). `IntArith` in `FAIL` mode condemns the batch on an overflowing live lane, so the row
   engine raises on the row (`varka/VarkaVectorIR.java:403-415`); its sign test is the lane's
   own (`varka/VarkaVectorWalk.java:696-709`) and the long form is tested
   (`VarkaEmitterLongLaneSuite.scala:92`, `:141`) and costed (`VarkaEmitCostRegister.java:134`,
   `:162` for `IntNeg`). A checked multiply has none, at either lane (`VarkaNodeCompiler.java:
   1023-1028`); 338 used a `GuardedRange` and a wrapping multiply instead (`:647-660`). The
   interval family negates a year-month interval only (`VarkaIntervalCompiler.java:187`).
4. **The month machinery is the date family's, and is int-lane only.** `AddMonths`
   (`varka/VarkaVectorIR.java:1345`) is `DateTimeUtils.dateAddMonths` with its clamp, its count
   from `compileMonths` (`VarkaChronoCompiler.java:578`): a literal inside `MONTH_ARITH_MIN_MONTHS`
   to `MONTH_ARITH_MAX_MONTHS` (-24,576 to 24,564, `varka/VarkaChrono.java:533`), a column under a
   runtime guard, the negated interval as a checked `IntNeg`. Its day must be admitted by
   `calendarInput` (`:681`), which needs a bounded day: `GuardedDay` bounds it to
   `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]` (1 March -12800 to 15 August 33134,
   `varka/VarkaChrono.java:105-109`; `varka/VarkaRangeAnalysis.java:272`), while a `NarrowLane`
   is unknown (`:286`), so `CAST(ntz AS DATE)` cannot feed it as it is (`VARKA-338.md` 0, item 7).
   The calendar lowerings require the int lane (`varka/Lane.java:391-406`); a kernel of both lanes
   runs an int node over the int species of the long species' width (`VARKA-28.md` 9), tested for
   a calendar field (`VarkaEmitterMixedLaneSuite.java:183`, `:380`) but never for `AddMonths`.
   The IR fuzzer's mixed corpus (`VarkaIrFuzzSuite.scala:430`) draws through
   `VarkaIrGrammar.MixedShapes` (`:1253`), whose int branch can draw `AddMonths` over int columns
   with a literal count, but its reach assertion (`VarkaIrFuzzSuite.scala:1317-1319`) does not
   require one.
5. **The exact floor division covers the day, not every width.** `floorDivLong`
   (`VarkaNodeCompiler.java:693`) takes a divisor `2^k * m` whose shifted dividend stays under
   `ConstDivide.EXACT_DIVIDEND_BOUND = 2^52` (`varka/VarkaVectorIR.java:783`): the day,
   `2^13 * 10,546,875`, qualifies; an hour, `2^10 * 3,515,625`, leaves `2^53` and is refused, and a
   minute or a second leaves more. So `time_bucket` is not one floor division over the long range.
6. **No sweep holds `AddMonths` to `dateAddMonths`.** `VarkaRangeRulesSuite.java:117-126` checks
   only that `dateAddMonths(d, m) - d` stays inside the range analysis's bounds, for twelve counts
   up to `NARROW_DECOMPOSE_MAX_DAYS` (`:53`), not the guard's ceiling; it never runs `AddMonths`.
   The one value check is `VarkaEmitterChronoSuite.scala:834`, 13 offsets over sampled days across
   the clamp boundaries. The opt-in whole-range sweep (`-Dvarka.sweep=true`, `:880`) covers
   `Year`, `Month`, `DayOfMonth`, `Quarter` and `DayOfYear`, not `AddMonths`.
7. **The conformance suite has no NTZ arithmetic on master; row 485's open work cancels one.** On
   master `hasArithmetic` is false for NTZ (`VarkaTypeConformance.java:120-122`), so the
   null-handling check drops its `x + y` (`:181`) and the overflow check asserts only that the
   census lists NTZ for none of `ARITHMETIC`'s eight operators, `+`, `-`, `*`, `negative`, `abs`,
   `try_add`, `try_subtract` and `try_multiply` (`:237-245`, `:650-651`). Nothing is cancelled
   naming this row. Row 485's unmerged work (`/home/max/proj/varka-485-conformance`) gives NTZ
   `PairForm("x - y", "ntz_minus_ntz", ...)` for null handling
   (`VarkaTimestampNtzConformanceSuite.java:56-57`), which the census owes here, so the check is
   cancelled naming 339 (its `VarkaTypeConformance.java:375`, `:430-456`); it removes
   `hasArithmetic`, and its overflow check keeps only forms whose census row lists the type
   (`:298`). The `+`, `-` and `*` rows never list `ntz` (`census.tsv:91-93`), so NTZ gets no
   overflow forms on either side. The census owes `ntz_minus_ntz`, `ntz_plus_dt`,
   `ntz_plus_ym`, `timestampadd`, `time_bucket` and `datetime_sub`'s NTZ form to it
   (`m8/census.tsv:604-611`, `:277`).
8. **The baseline covers three of the forms and cannot be rerun.**
   `VarkaTimestampNtzBaselineBenchmark` (`sql/core/src/test/java/.../benchmark/`) times `ntz +
   (ntz2 - ntz3) - INTERVAL '1' HOUR`, which is `ntz - ntz`, `ntz + dt` and `ntz - dt`: 271.9 ns
   a row on Spark's row path at 256 bits with no nulls (its committed results,
   `VarkaTimestampNtzBaselineBenchmark-jdk25-runner-epyc7763-results.txt` in
   `sql/core/benchmarks/`).
   Nothing times `ntz + ym`, `timestampadd` or `time_bucket`. The class throws when any section
   fuses (`:129`), and since 338 its comparison, cast and choice do, so it no longer runs as it is.

### What differs from the row text and the milestone plan

- **The `+-106,751,991` day guard is live for `timestampadd`'s DAY and WEEK, not for the month
  forms.** 2.6 states it for a calendar result day recombined with its time of day. Here the
  input day is held to `GuardedDay`'s range (item 4) and the count to `MONTH_ARITH_*`, so the
  result day is within about 12.1 million days of the epoch and its micros cannot overflow: the
  month forms recombine by a wrapping add that is exact by that structural bound, and day
  -106,751,992 is declined by the same guard, whole. The guard and checked add the row names are
  the DAY quantity's (2.2).
- **The day of an NTZ under a bound is built here, not by 340.** 340's text has it bound the
  cast's day; the month forms need it first, so it is a helper (`ntzDay`, 2.4) that 340 reuses,
  as 338 moved the floor division forward.
- **`time_bucket` is three arms, not a floor division** (item 5): exact over the whole long range
  for a width that divides a day or is a whole number of days, and a guarded window for the rest
  (2.5). A year-month width declines (question 1).
- **The conformance change is new overflow forms either way, and an un-cancelled null-handling
  pair if 485 lands first** (item 7, 3.2).

## 1. Goal and non-goal

`ntz - ntz`, `ntz +/- dt`, `ntz +/- ym`, `timestampadd` and `time_bucket` over a day-time width
fuse with the row engine's answers in LEGACY and ANSI; a batch with a lane that Spark raises on
goes to the row engine, which raises the same error on the row. Not this row: the TRY forms
(`TryEval`), a year-month `time_bucket`, the zoned `TIMESTAMP`, the date fields (340).

## 2. Design

All of it is existing IR nodes: `IntArith`, `IntNeg`, `GuardedRange`, `GuardedDay`, `WidenLane`,
`NarrowLane`, `BitOp`, `ConstDivide`, `AddMonths`. No new node, lowering or emit option, and no
emitted byte of an existing shape moves; the compiler's operand rule gains one case (2.3). `t` is
the NTZ operand, `D` 86,400,000,000.

### 2.1 The difference and the interval forms (time family)

- **`ntz - ntz`**: `IntArith(SUB, FAIL, end, start)`, a day-time interval output on the long lane
  as `d2 - d`'s is. The legacy `CalendarInterval` form declines by name, as `SubtractDates`'
  does (`VarkaChronoCompiler.java:155`).
- **`ntz + dt`**: `IntArith(ADD, FAIL, t, dt)`, `dt` a column, a literal or a nested
  `SubtractTimestamps`.
- **`ntz - dt`**: the `UnaryMinus` is absorbed, as `dateAdd` absorbs it into `SubDays`:
  `IntArith(SUB, FAIL, t, GuardedRange(dt, Long.MIN_VALUE + 1, Long.MAX_VALUE))`. The guard is
  `negateExact`'s domain and, inside it, `t - dt` overflows exactly when `t + (-dt)` does. A
  literal `Long.MIN_VALUE` interval declines at compile time.

### 2.2 `timestampadd` up to a week (time family)

The unit is upper-cased as Spark does; NANOSECOND and an unknown unit decline. `q` is the long
operand, `f` the unit's micros.

| unit | lowering | guard on `q` |
|---|---|---|
| MICROSECOND | `ADD FAIL(t, q)` | none |
| MILLISECOND to HOUR | `ADD FAIL(t, MUL WRAP(G(q), f))` | `+-floor(Long.MAX_VALUE / f)` |
| DAY, DAYOFYEAR | `ADD FAIL(t, MUL WRAP(G(q), D))` | `+-106,751,991` |
| WEEK | `ADD FAIL(t, MUL WRAP(G(q), 7 * D))` | `+-15,250,284` |

For the four time units the guard is exactly `multiplyExact`'s domain (no `f` is a power of two,
so it is symmetric). For DAY and WEEK it is the product's: a `q` past it either raises in Spark
(`toIntExact`, the int multiply, or the result past the long) or lands back in range from the far
end, and the batch goes to the row engine, which answers either. Every `q` that trips
`toIntExact` or WEEK's int multiply is past the guard, so the one guard covers all three. A
literal `q` is checked at compile time and folds to `q * f`.

### 2.3 The month forms (calendar family)

`ntz +/- ym`, and `timestampadd` by MONTH, QUARTER and YEAR:

    day   = ntzDay(t)                         (2.4)
    moved = AddMonths(day, months)
    out   = IntArith(ADD, WRAP, t,
              IntArith(MUL, WRAP, WidenLane(IntArith(SUB, WRAP, moved, day)), D))

`LocalDateTime.plusMonths` is the date's `plusMonths` with the time kept, so the result is `t`
moved by the change in its day. `months` is `compileMonths`' for `ym` (so `ntz - ym` keeps the
checked `IntNeg` that raises at `Integer.MIN_VALUE`); for `timestampadd` it is the quantity,
guarded to the `MONTH_ARITH_*` range over the factor (`[-24576, 24564]`, `[-8192, 8188]`,
`[-2048, 2047]` for 1, 3 and 12), then times the factor by a wrapping int multiply, which the
guard keeps exact, as the row's "month units in int" asks. A lane past it goes to the row engine.
The count position takes a column, a literal or int arithmetic at its top
(`varka/VarkaOperandPosition.java:75-95`): an int quantity arrives as `WidenLane(i)` and is read
as the int column, so `IntArith(MUL, WRAP, GuardedRange(i, lo, hi), k)` and, for MONTH, the bare
column under `AddMonths`' own count guard are shapes it takes today. A bigint quantity is
`NarrowLane(GuardedRange(q, lo, hi))`, and the position gains `NarrowLane` for it, safe for the
reason its javadoc gives a derived count: `AddMonths` guards the count's value at run time.

Both additions are exact: `|moved - day|` is at most 24,576 months, so `|out|` stays far under
`2^62` for every admitted day.

### 2.4 `ntzDay`

`GuardedDay(NarrowLane(floorDivLong(t, D)))`: 338's floor division, narrowed (exact, every
quotient fits an int) and guarded to the calendar's range, which `calendarInput` then admits. A
batch holding a timestamp before 1 March -12800 or after 15 August 33134 goes to the row engine.
One helper in `VarkaNodeCompiler`, beside `floorDivLong`, for 340.

*27 October 2026 (`VARKA-340.md` 2.1):* the helper is 340's guarded form,
`GuardedRange(NarrowLane(floorDivLong(t, D)), NARROW_MIN_DAYS + 365, NARROW_MAX_DAYS)`, not a
`GuardedDay`: the week forms and a field over `trunc` need a year of room below the floor. A
timestamp from 1 March -12800 to 28 February -12799 now goes to the row engine too. The month
forms are admitted over it as over `GuardedDay` (`addMonths` takes its day through
`calendarInput`, `VarkaChronoCompiler.java:325`); the sweep of 3.5 runs over the days it admits
and the day-guard edges of 3.4 are its bounds and one past each.

### 2.5 `time_bucket` over a day-time width (time family)

The width `b` and origin `o` must be literals (Spark makes them foldable; a null declines).
Spark's raise points, in order: `diff = ts - o`, `kb = k * b`, `o + kb`, and for `b >= D` also
`(k + 1) * b` and `o + (k + 1) * b` (0, item 1). Every arm computes the remainder
`r = floorMod(diff, b)` and then

    diff  = IntArith(SUB, FAIL, t, o)
    kb    = IntArith(SUB, FAIL, diff, r)          = k * b, raising where multiplyExact does
    start = IntArith(ADD, FAIL, o, kb)

and, for `b >= D`, `kb` and `start` each under `GuardedRange(_, Long.MIN_VALUE, Long.MAX_VALUE -
b)`, the next bucket's two overflows. The arms differ in `r`:

- **`b` divides a day** (`D = 2^13 * 3^3 * 5^8`; every width of a second, a minute, 15 minutes,
  an hour): `r1 = diff - floorDivLong(diff, D) * D` in wrapping arithmetic, exact modulo `2^64`
  (the product wraps at the lowest day, the difference does not) and in `[0, D)`, then `r = r1 -
  ConstDivide(r1, b, D) * b`, a truncating division of a non-negative value under `2^37`. Exact
  over every long, since `floorMod(x, b) = floorMod(floorMod(x, D), b)` when `b` divides `D`.
- **`b` is `n` whole days**: `q1 = floorDivLong(diff, D)`, in `[-106,751,992, 106,751,991]`, `k =
  floorDiv(q1, n)` by the sign move and a `ConstDivide` under `2^27 + n`, and `r = (q1 - k * n)
  * D + r1`. Exact over every long by the nested floors.
- **Any other width**: `diff` under `GuardedRange(_, -(2^52 - b), 2^52 - b)`, `k` by the sign
  move and one `ConstDivide`, and `r = diff - k * b`: about 142 years either side of the origin
  fuse, the rest decline. A width of this arm past `2^51` declines.

## 3. Tests and oracles

The oracle is Spark's own evaluation, through the reference evaluator and the differential
suites, as for every landed form.

1. **First, the risk**: `AddMonths` in a kernel of both lanes, in `VarkaEmitterMixedLaneSuite`, at
   the long lane's 2 and 8 lanes, the four null patterns, both bodies; and `AddMonths` added to the
   mixed corpus's reach assertion (item 0.4), so the IR fuzzer is held to drawing it.
2. **Conformance**, in `VarkaTypeConformance` and the NTZ suite (item 7). Null handling: if 485
   lands first, its `x - y` pair is un-cancelled by marking `ntz_minus_ntz` fused in the census;
   if not, 339 adds the same pair through a hook the NTZ suite fills. Overflow, either way: the
   forms come from `ARITHMETIC`'s census rows, which never list `ntz`, so the check gains a
   per-type list, NTZ's keyed on `ntz_minus_ntz`, `ntz_plus_dt` and `datetime_sub`: `x - y`, `x +
   (x - y)` and `x - (x - y)` in LEGACY and ANSI over the suite's values (the long range's ends,
   which overflow both ways), each fused and declining where Spark raises; `check` already
   matches a raised row to a declined batch in every mode (`VarkaTypeConformance.java:530-537`).
   On master the exemption's census assertion over the eight operators stays; 485 removes it.
3. **Coverage rows**, a family "TIMESTAMP_NTZ arithmetic": `ntz - ntz2`, `ntz - d`, `ntz + dt`,
   `ntz - dt`, `ntz + INTERVAL '1' HOUR`, `ntz + ym`, `ntz - ym`, `ntz + INTERVAL '1' MONTH`,
   `timestampadd` at MICROSECOND, HOUR, DAY, WEEK, MONTH, QUARTER and YEAR over an int and a
   bigint quantity, `time_bucket` at 15 minutes, one day with an origin, 30 days and 7 hours;
   each through the differential suite at every position, domains where a guard declines.
4. **The edges**, in `VarkaTimestampNtzSuite`, each overflowing row in one batch of three so the
   others stay fused, LEGACY and ANSI: `Long.MAX_VALUE` plus a microsecond; the long range's ends
   subtracted; `dt = Long.MIN_VALUE` and `Integer.MIN_VALUE` months negated; `timestampadd` DAY at
   `+-106,751,991` (fused) and `+-106,751,992` (declined, the row engine's answer whether a value or
   `DATETIME_OVERFLOW`), WEEK at `+-15,250,284` and one past, YEAR at 2047, 2048, -2048 and -2049;
   the day guard at `NARROW_MIN_DAYS - 1` and `NARROW_MAX_DAYS + 1`; `time_bucket` with a day width
   at a timestamp whose bucket starts in range and whose next does not (Spark raises), and the same
   timestamp with an hour width (fused, no raise).
5. **The calendar proof, the first whole-range sweep of `AddMonths`** (item 0.6). A JUnit sweep
   beside `VarkaRangeRulesSuite`: every day `GuardedDay` admits, at a time of day drawn per day
   plus 0 and `D - 1`, for the twelve counts of its `addMonths`, the composition of 2.3 in scalar
   form against `DateTimeUtils.timestampAddMonths(t, m, UTC)` and `LocalDateTime.plusMonths`; and
   the emitted kernel, `AddMonths` and the recombination, over the same days and counts, opt-in
   under `-Dvarka.sweep=true` beside the calendar sweep at `VarkaEmitterChronoSuite.scala:880`.
6. **The `time_bucket` proofs.** In `long_divide.smt2`, over all 64-bit `x`: the divides-a-day
   remainder for each coverage width, the nested floor for whole days, and the window's dividend
   bound. In the JVM: every one of the 504 divisors of a day and `n` in 1, 7, 30, 365 and
   106,751,991 days, over the long range's ends and a seeded draw of differences, against
   `timeBucketDTInterval`, raising where it raises.
7. **The equivalences of item 0.1**, pinned at `Long.MIN_VALUE`, `Long.MAX_VALUE` and their
   neighbours: `subtractTimestamps` against `subtractExact`, `timestampAddDayTime` against
   `addExact`, the `timestampadd` guards against `multiplyExact` at the bound and one past.
8. **The fuzzers**: the Spark fuzzer's grammar gains the forms over its NTZ column.
9. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order: `coverage.json`, the docs tables
   (the two families' `covers` text), `width_audit.json`, `emitted_bytes.json` (new shapes only),
   `census.tsv`'s fused column.
10. **The PR build and the dispatched option matrix** over the new and changed suites.

## 4. Predictions and costs

The measurement reruns the baseline (5, step 1) with the fuse check lifted, on the runners' EPYC
7763 at 256 and 128 bits, diffed against the committed files.

1. **No existing shape's bytes move**; the `option_arms` digests move with the new shapes.
2. **The chain** at least ten times faster than the row path at 256 bits: three loads, three
   checked ops and a store against 271.9 ns a row of `ZonedDateTime` arithmetic.
3. **The month form** at least five times faster: one `AddMonths` against `plusMonths` through
   `ZonedDateTime`. **`time_bucket`** at least twice as fast at 256 bits for the divides-a-day arm,
   whose two divisions are the conversion route; within 20% either way at 128 bits.
4. **Test time**: the calendar sweep under 10 s, `VARKA-281`'s 1.5 s at four times the work per
   day (calibration ratio 0.08 taken as 1, since `ZonedDateTime` is the slower decode); the
   differential's new rows under 20 s.

Cost: medium, as the row says. About four days after the baseline; `time_bucket` is half of it.

## 5. Order and files

1. **The baseline**, its own pull request on the unchanged code:
   `VarkaTimestampNtzArithmeticBaselineBenchmark`, the chain again, `ntz + dt` over the interval
   column, `ntz + INTERVAL '1' MONTH`, `timestampadd(HOUR, q, ntz)`, `time_bucket` at 15 minutes
   and at a day, each with and without nulls; its fuse check is the shared
   `VarkaSqlBenchmarkBase.requireBaseline` over each section's `fuses` flag, none set at step 1,
   so the after-run reuses the class: each of steps 2 to 4 sets the flag of the sections it
   fuses, and a listed section must then run fused over every batch
   (`VarkaSizeLadder.varkaFused`). `VarkaTimestampNtzArithmeticBaselineQueriesSuite` checks the
   same list at pull-request time, so a step that fuses a section it does not list fails there. The fixture's timestamps stay
   inside 2.4's `GuardedDay` range, the bound that matters: every int passes the HOUR guard.
2. **The mixed-lane `AddMonths` test** (3.1), then 2.1 with the conformance hook and its rows.
3. **`timestampadd` up to a week** (2.2), then **the month forms** (2.3, 2.4) and their sweep.
4. **`time_bucket`** (2.5) with its proofs.
5. **The generated files, the measurement** and its verdict.

| file | what |
|---|---|
| `VarkaTimeCompiler.java` | 2.1, 2.2, 2.5 |
| `VarkaChronoCompiler.java` | 2.3 |
| `VarkaNodeCompiler.java` | `ntzDay` |
| `varka/VarkaOperandPosition.java` | `NarrowLane` as a month count |
| `varka/VarkaFamily.java` | the time and calendar families' `covers` text |
| `sql/varka/proofs/long_divide.smt2`, `VarkaProofFiles.java` | the bucket claims |
| tests | conformance hook, mixed-lane case, coverage rows, edges, sweep, bucket and guard pins |
| benchmark | the baseline class, its results files at both widths |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| `sql/varka/plans/m8/census.tsv` | the five forms fused for `ntz` |

## 6. Out of this row

The TRY forms through `TryEval`, whose census row already lists `ntz` for rows 330, 342 and 349
(`census.tsv:628`). Under it a `FAIL` node here turns null; a lane past a guard is not a `FAIL`
node and declines the batch instead, which is still right for TRY. Also out: a year-month
`time_bucket` (question 1); the date and time fields over NTZ (340); `CAST(i AS INTERVAL DAY)`
as an operand (332); `timestampadd` over a `DATE`, which Spark casts to the zoned type.

## 7. Declines that stay

The legacy `CalendarInterval` forms; a windowed `time_bucket` width past `2^51`, a non-literal or
null width or origin; NANOSECOND and unknown units; a literal `Long.MIN_VALUE` interval negated;
every batch with a lane past a guard, which the row engine answers.

## 8. Questions for the owner

Answered by the owner on 27 October 2026: (a) to both - a year-month `time_bucket` declines by
name with `m9/SCOPE.md` item 117, and the windowed arm is built.

1. **A year-month `time_bucket`.** (a) Decline by name and add an `m9/SCOPE.md` item: it needs the
   year and month of the NTZ day and two `AddMonths` over the origin's day. (b) Build it here
   after 2.4. *Recommended: (a)*: 2.6 names only a day-time width, and the form is half again of
   `time_bucket`'s work for a rarer width.
2. **The windowed `time_bucket` arm** for a width that neither divides a day nor is whole days.
   (a) Build it (2.5, one guard and one division). (b) Decline those widths. *Recommended: (a)*:
   it is the plain floor division, and only timestamps over 142 years from the origin decline.
The baseline comes first, as its own pull request (5.1): not a question, since the house rule
for a change that improves something settles it, as it did for rows 377 and 27.

## 9. Done when

Row 339's text holds: each form fuses, an overflowing lane declines to the row engine's error, and
the month forms are proven against `java.time` by 3.5's whole-range sweep, scalar and emitted. And:
the conformance suite runs NTZ's own forms; the edges of 3.4 pass; the `time_bucket` claims hold
under both solvers; `dev/varka_census.py --check` passes; no existing shape's bytes move; the PR
build and the option matrix pass; the measurement is committed with its predictions scored.

## 10. Outcome

(Written when the work lands.)
