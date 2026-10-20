---
layout: global
title: Varka - SIMD Date Arithmetic over Arrow
displayTitle: Varka - SIMD Date Arithmetic over Arrow
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Varka is a research/experimental execution engine inside this Spark fork. It
compiles whole date-expression projections into a single SIMD vector loop -
bytecode emitted with the JDK 25 Class-File API, running the Vector API over
zero-copy Panama `MemorySegment` views of Arrow `DateDayVector` buffers -
bypassing Spark's per-row code generation on the happy path.

This page is long, and the sections stand on their own. If you are new here, the
**Glossary** is the one to read first - *lane*, *word*, *morsel* and *epilogue* all mean
something specific in this engine and appear on almost every page after it.

* Table of contents
{:toc}

## Overview

The Spark SQL runtime normally executes expressions by generating Java source,
compiling it with Janino, and running one expression evaluation per row. Varka
eliminates that runtime compilation overhead (string generation and Janino
parsing) and unlocks SIMD by operating on whole columnar batches at once.
Since milestone 2 it does not dispatch to per-op kernels: an eligible
projection is compiled to a vector IR and *fused* - however many expressions
and however deep their nesting, the emitted class runs one loop with one load
per input column and one store per output. Since VARKA-21 the same machinery
serves filters: an eligible predicate becomes a mask kernel whose single
output is the selection bitmap, and the batch is compacted - or its rows
skipped at the row boundary - by that mask.

Varka reads three Spark types, and they are the three that are int32 both in
Spark and in the Arrow cache: `DateType` (stored as `INT` days since epoch),
`IntegerType`, and `YearMonthIntervalType` in every unit (VARKA-67), whose value
is a count of months whether it is spelled `INTERVAL YEAR`, `INTERVAL MONTH` or
`INTERVAL YEAR TO MONTH`. An interval column reaches the same lanes as a date
one; where it may appear is decided by Spark's own typing, so it fuses in
`d + ym`, in the ordered comparisons and `IN`, and in the same-typed
`greatest`/`least`/`coalesce`/`IF`/`CASE`. `CAST` between an `INT` and a
`MONTH`-unit interval is a relabel in both directions and emits nothing, as is
the cast between two year-month units that ends at `MONTH` - the one type
coercion inserts when two units meet, so `ymm + ymy` fuses like `ymm + ymm2`.
Since VARKA-68 the `INT`-to-`YEAR` cast, which multiplies by twelve, fuses
wherever the compile-time bound rules that multiply's overflow out - over a
calendar field it does, over a bare int column it does not. The direction that
divides by twelve, which is `EXTRACT(YEAR FROM ym)`, `ym / k` and a cast that
narrows a year-month interval to a `YEAR`-ended unit, stays residual and is VARKA-89's: the emitter's exact magic division covers about a
forty-thousandth of a full int32 month count.

VARKA-68 also admits the algebra whose operands and result are both intervals:
`ym + ym`, `ym - ym`, `-ym`, `abs(ym)`, `ym * k` and `make_ym_interval(y, m)`,
and, in `ADD_MONTHS`' month-count position, `d - ym_col`. None of these has a
wrapping form - Spark computes every one with `addExact`, `subtractExact`,
`negateExact` or `multiplyExact` whatever the session's ANSI mode - so the check
is unconditional and only a compile-time bound takes it off. A checked `*` has
no int-lane overflow test, so `ym * k` fuses only where the bound proves it safe
and is residual otherwise, exactly as VARKA-63's int multiply is; `abs` is not an
op the IR has but the blend `if (ym < 0) -ym else ym`, whose negation arm alone
can condemn a batch. `try_add(ym, ym)` and the other `try_*` spellings are
residual, having no null-on-overflow lowering.

The supported expression surface, then, over those columns and day offsets that
are a foldable integer literal, an
`IntegerType` column (VARKA-38; a `ShortType`/`ByteType` offset column still
declines) or int arithmetic over those (VARKA-63), including that column
spelled as a day interval,
`d + CAST(i AS INTERVAL DAY)` and `d - CAST(i AS INTERVAL DAY)` (VARKA-56; a
literal interval folds to `date_add` before planning). The cast throws past
106751991 days in every mode, so the evaluator checks the offset column per
batch and a batch holding such a value is recomputed on the row engine, which
raises that error, counted as `numFallbackBatchesDeclined`. A stored `INTERVAL
DAY` column and `i * INTERVAL '1' DAY` (which widens to a sub-day interval and
becomes timestamp arithmetic) decline:

* `DATE_ADD` / `DATE_SUB` / `DATEDIFF`, nested to any depth up to the
  emitter's chain cap, including chains mixing them.
* `CASE WHEN` and `IF` over date comparisons (`<`, `<=`, `>`, `>=`, `=`) and
  their `AND` / `OR` / `NOT` combinations - executed branch-free by mask
  blend, with SQL's three-valued null semantics. `BETWEEN` arrives from the
  optimizer as its paired comparisons and fuses the same way.
* `IN` over date literals in condition position (VARKA-20): an EQ chain
  joined by OR, capped at 16 deduplicated literals - a longer list declines
  with a recorded reason rather than risking the emitter's budgets.
* `COALESCE` / `NVL` / `IFNULL` / `NVL2` and the `IS [NOT] NULL` predicates
  (VARKA-20), lowered onto a validity-reading condition. A guarded operand
  must be a bare column, or for `IS [NOT] NULL` a predicate, whose unknown
  rows the test reads off the predicate's own masks (VARKA-334); any other
  operand declines.
* `GREATEST` / `LEAST` (null-skipping) and `DAYOFWEEK` / `WEEKDAY`.
* `YEAR` / `MONTH` / `DAYOFMONTH` / `QUARTER`, and the `EXTRACT` spellings that
  desugar to them (VARKA-26). One civil-from-days decomposition per date,
  lowered entirely to magic multiplies because no vector divide exists, and
  shared between the fields over it: `year(d), month(d), dayofmonth(d),
  quarter(d)` run one decomposition and four short tails in one loop method
  (VARKA-32). The lowering is defined over years -12800 to 33134 -
  every date SQL can write, and then some - and the range is enforced where a
  day can leave it, not at every extraction (VARKA-51 and VARKA-52). It stays exact
  for a further nine thousand years, to 42400, which is what an *intermediate*
  may reach: a value the compiler has already bounded below and is only
  shifting upward is admitted against that wider ceiling rather than the
  enforced one (VARKA-69). A date column
  is taken to hold `0001-01-01..9999-12-31`, the project's column contract,
  and is not checked at ingestion. Spark stores any 32-bit day count, so
  wherever a fused answer rests on that range the kernel checks it (VARKA-379):
  it compares the column's values with the range as it loads them, in its own
  loop, and declines a batch holding a live day outside it to the row path,
  counted as `numFallbackBatchesDeclined` under the value-range reason. The
  compiler bounds how far the arithmetic
  under a calendar function can shift such a day - literal `DATE_ADD` offsets,
  `NEXT_DAY`, `LAST_DAY`, `ADD_MONTHS` with a literal count, and their
  compositions - and an entry whose shift can leave the range is residual at
  compile time, with the interval as its reason (`year(date_add(d,
  20000000))` is the canonical case; the row engine computes it). Two
  producers it cannot bound that way carry a per-batch range check instead,
  and either one declines the whole batch to the row path, counted as
  `numFallbackBatchesDeclined`: a `DATE_ADD`/`DATE_SUB` with a *column*
  offset whose result a calendar function reads, checked against the enforced
  range above rather than the wider one an intermediate may reach, since what
  a producer hands on should stay inside the range every consumer expects; and
  an `ADD_MONTHS` with a *column*
  count, checked on the count itself (see below) - that one wherever it
  sits, because the check protects its own month arithmetic rather than a
  consumer's, so a bare `add_months(d, m)` carries it too. A `DATE_ADD` with
  no calendar consumer is never checked - it returns what 32-bit addition
  returns, as Spark's does.
* `NEXT_DAY` (VARKA-33) with a literal weekday, resolved at compile time so
  one kernel class serves every weekday; a null or unrecognized literal
  declines. Since VARKA-59 a weekday *column* fuses too, through a derived
  input: before the kernel runs, the evaluator maps the string column to the
  weekday number per batch by the row engine's own parser, so the kernel reads
  an int column and the semantics are the row engine's by construction - an
  unrecognized name is NULL with ANSI off; with ANSI on it declines the batch
  to the row engine, which raises its `ILLEGAL_DAY_OF_WEEK` error where a
  non-null date sits beside the name and returns NULL where the date is null,
  exactly as it does alone. Any collation is admitted, since the parser
  ignores it. A weekday that is an expression over a column stays residual.
* `DAYOFYEAR` (VARKA-34), `LAST_DAY` (VARKA-36) and `ADD_MONTHS` / `date +
  INTERVAL n MONTH` (VARKA-40), all over the same civil-from-days prefix;
  `LAST_DAY` and `ADD_MONTHS` return dates. `ADD_MONTHS`' month count is a
  literal, (VARKA-60) an integer column, (VARKA-67) a stored year-month
  interval column, or (VARKA-68) arithmetic over any of those - the negation
  behind `d - ym_col`, and the `CAST(m AS INTERVAL YEAR)` whose value is
  twelve times the column rather than the column itself. What lets a derived
  count in is that the range check below is lanewise on the count's *value*
  and cares nothing about what produced it; `NEXT_DAY`'s weekday, which has
  no such check, still takes a literal or a column only. A column count
  carries a per-batch
  range check on the count itself, the same route as the day producer above:
  a lane outside `VarkaChrono.MONTH_ARITH_MIN/MAX_MONTHS` (about 2047 years
  either way, where the lowering's magic division by 12 stops being exact)
  declines the batch to the row engine rather than compute past it.
* `MAKE_DATE(year, month, day)` (VARKA-42) over int columns and literals, in
  both evaluation modes: an invalid month or day is a null date with ANSI
  off and, with ANSI on, sends the batch to the row engine, which raises
  Spark's own error at that row; a year outside the whole years of the
  calendar range goes to the row engine in both modes, so the kernel never
  publishes a date the lowering is not exact for.
* `WEEKOFYEAR`, `EXTRACT(WEEK FROM d)` and `DATE_PART('WEEK', d)` (VARKA-37), the ISO-8601 week
  by the Thursday rule: the day is moved to the Thursday of its Monday-based
  week and the week is that Thursday's ordinal day in sevens, so the year
  boundaries need no correction. The shift is its own node and the week tail's
  prefix runs over it, which is what makes `EXTRACT(YEAROFWEEK FROM d)`
  (VARKA-58) `YEAR` over the same shift, sharing the shift and its prefix with
  `WEEKOFYEAR` of the same date; a projection mixing either with `YEAR` or
  `MONTH` of the same date decomposes twice, once per side.
* `EXTRACT(DAYOFWEEK_ISO FROM d)` / `DATE_PART('DOW_ISO', d)` (VARKA-57),
  Monday 1 to Sunday 7, as one node: the analyzer spells it `weekday(d) + 1`,
  and that spelling by hand fuses the same way. Since VARKA-63 any other
  integer arithmetic over a field fuses too, but as arithmetic over the
  weekday rather than as this node, so `weekday(d) + 2` answers 2 more than
  the weekday and not 1.
* `TRUNC(date, fmt)` at the date levels. With a literal format (VARKA-35),
  `YEAR`/`YYYY`/`YY`, `MONTH`/`MON`/`MM` and `QUARTER` are one node each with
  the level as part of the kernel's shape, and `WEEK` is rewritten onto
  `NEXT_DAY` over `DATE_SUB`, which is Spark's own definition of it. With a
  stored string column as the format (VARKA-61), one node computes all four
  periods per row and selects on the level, which the evaluator parses per
  batch through the row engine's own `parseTruncLevel` into a derived int32
  input (the mechanism `NEXT_DAY` uses for a weekday column), so any collation
  is admitted; rows whose format is null, unrecognised or below a day are NULL,
  as on the row engine, in either ANSI mode. That node costs about the three
  literal tails plus the week's, so a literal format remains the shape to
  write. The output is a date that can feed further date arithmetic in the
  same fused chain. A literal null format, a literal spelling `trunc` does not
  accept, every sub-day literal level, and a format that is an expression over
  a column decline: the row engine answers the literal cases with a NULL
  column, which no kernel can produce.
* `UNIX_DATE` relabels a date column to its underlying `INT` day count with no
  new node and no emitted code (VARKA-41); the paired `DATE_FROM_UNIX_DATE`
  compiles the same way but still declines, since its child is an integer
  column and only a `date_add`/`date_sub` offset position reads one (VARKA-38).
* `+`, `-`, `*` and unary `-` over int32 values (VARKA-63): an `IntegerType`
  column, an int literal, any fused int field (`YEAR`, `MONTH`, `DATEDIFF`,
  the weekday family) or nested arithmetic over those. The evaluation mode
  travels with the node. Under `LEGACY` the lanes wrap, as `DATE_ADD` always
  has. Under ANSI a sign test marks the overflowing lanes and the batch is
  recomputed on the row engine, which raises Spark's own
  `ARITHMETIC_OVERFLOW`; `TRY_ADD`, `TRY_SUBTRACT` and `TRY_MULTIPLY` clear
  those lanes from the output's validity instead and the batch runs on. Where
  the operands' own ranges prove the result cannot leave int32 the check is not
  emitted at all, which is what lets `YEAR(d) * 100 + MONTH(d)` fuse under
  ANSI. The calendar bounds every field; `DATEDIFF` is bounded only where both
  of its operands are, so a `DATE_ADD` with an offset large enough to wrap the
  int32 day, or one shifted by a column with no calendar function over it,
  leaves the difference unbounded and the check in place. Both of those rest on
  the stored-date contract below, which nothing enforces at ingestion: a date
  column holding a day outside 0001..9999 can carry a bound that is not true of
  it. A checked multiply whose operands carry no such bound declines: an
  int32 lane has no cheap overflow test for `*`, so it stays on the row engine
  rather than wrapping silently. `DIV`, `%` and the float and long types are
  not lowered.
* Common subtrees shared *across* outputs are computed once per lane group
  (DAG-CSE), which no per-row engine can keep in a vector register.

A projection does not have to be fully eligible: eligible entries fuse,
untouched input columns are forwarded zero-copy, and the remaining entries run
the standard row path per row, merged with the kernel outputs (VARKA-12).

Explicitly out of scope are `CalendarInterval` (a struct of months, days and
microseconds), `DayTimeIntervalType` (int64 microseconds), strings, decimals and
nested/complex types. Day offsets are int32, whether a literal, a column or
arithmetic over them.

Varka is designed as a drop-in, zero-risk replacement: every Varka path falls
back to the standard row engine on any failure, so results are always correct.

## Expression coverage

One row per expression the compiler fuses, in the spelling you would write it,
over the columns of the benchmark's `varka_dates` table: `d` and `d2` are dates,
`i` an int, and `ymm`, `ymy` and `ym` year-month intervals of the three unit
ranges. The Overview above states the conditions in prose; this is the list to
scan when the question is simply whether an expression is covered.

Anything absent declines and runs on Spark's row engine. A decline is a normal
outcome, not an error: the entries that fuse still fuse, and the rest of the
projection is unaffected.

Two things are worth stating before the list, because they decide several
rows and are invisible in the spelling. Every comparison and null test fuses
over each of the six types, a bare `IntegerType` column included (`i > 0`, as
`year(d) = 2021` does), and `IN` over dates and year-month intervals. A null
test fuses over a predicate as well as over a column, as a filter and as the
condition of a CASE: `(d = d2) IS NULL` is the predicate's unknown rows, read
off the masks it already computes. Spark's default optimizer
(`NullDownPropagation`) first rewrites a null test over an ordered comparison
or a negation into null tests on its operands, so `(d < d2) IS NULL` reaches
Varka as `d IS NULL OR d2 IS NULL`; a test over an equality, a conjunction or
a disjunction reaches it as written. And a checked
multiply fuses only where a bound proves it cannot overflow, which is why the
multiply rows below are written over a calendar field rather than over a plain
column.

The *Domain* column is filled for a row whose kernel can decline a batch: the
predicate, over the row's columns, inside which the kernel answers. Outside it
the batch goes to the row engine, which answers or raises exactly as Spark
would have; an empty cell means every input of the column types is answered.
Where the two evaluation modes differ the domain is ANSI's, the mode the table
is compiled under: the checked int arithmetic and `make_date`'s date check exist
only there, and the legacy mode wraps or nulls as Spark does with no guard. The
bounds are rendered from the constants the emitter reads, so a moved bound
regenerates the table rather than leaving it stale, and `VarkaCoverageSuite`
fails a row that carries a guard and names no domain, or names one and carries
no guard. `VarkaCoverageDifferentialSuite` runs each such row under its domain,
its negation and `IS NULL` (VARKA-279).

The table is generated, not written. `VarkaCoverageSuite` compiles every row
below through `VarkaExpressionCompiler` and fails if any of them declines, fails
if the compiler matches an expression class that no row exercises, and fails if
this file differs from what it renders. So a table claiming an expression the
engine does not have, and a table missing one the engine gained, are both build
failures. Regenerate it after adding a row with:

```
VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly *VarkaCoverageSuite'
```

<!-- BEGIN generated coverage table -->
The *128-bit lanes* column is `sql/varka/width_audit.json`'s census on AMD Ryzen AI 9 HX PRO 370 w/ Radeon 890M at a forced 128-bit species: `vector` means C2 refused no Vector API call in the row's kernel there; `per-lane` names the constructions it had no lowering for, which then run as Java loops over the lanes (VARKA-153). It is this x86 JVM's table below its own width: the pool's aarch64 runner, whose native species is 128 bits, refuses nothing (`VARKA-153.md` 6).

The *Switches* column names the family switches that keep the row off Varka, each `<name>` the setting `spark.sql.codegen.varka.family.<name>.enabled` (Configuration): the families whose nodes the row's kernel holds, as the compiler records them for a failure's `families:` line. A row with none compiles to a bare column read, which no switch declines.

#### Day arithmetic

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `date_add(d, 3)` | `calendar` |  |  | vector |
| `date_add(d, i)` | `calendar` |  | a column offset with no bound: the day sum wraps as Spark's own `+` does, and only a calendar node reading such a sum needs the range guard | vector |
| `date_sub(d, 5)` | `calendar` |  |  | vector |
| `d + CAST(i AS INTERVAL DAY)` | `calendar` | `i BETWEEN -106751991 AND 106751991` | the day-interval spelling of a column offset; past the bound the cast itself throws in every mode, so the batch declines and the row engine raises | vector |
| `datediff(d2, d)` | `calendar` |  |  | vector |
| `d2 - d` | `calendar` | `CAST(unix_date(d2) AS BIGINT) - unix_date(d) BETWEEN -106751991 AND 106751991` | a day-time interval in microseconds, not datediff's day count; Spark raises where the count's product overflows a long, in every mode, so the batch declines and the row engine raises. The legacy CalendarInterval result is not fused | not audited |
| `unix_date(d)` | `calendar` |  |  | vector |
| `date_from_unix_date(unix_date(d))` | `calendar` |  |  | vector |

#### Calendar fields

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `year(d)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `month(d)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `day(d)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `quarter(d)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `dayofyear(d)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `dayofweek(d)` | `calendar` |  |  | vector |
| `weekday(d)` | `calendar` |  |  | vector |
| `extract(DAYOFWEEK_ISO FROM d)` | `calendar` |  |  | vector |
| `weekofyear(d)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `extract(YEAROFWEEK FROM d)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `last_day(d)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `next_day(d, 'MONDAY')` | `calendar` |  | the day name must be a literal | vector |
| `make_date(2021, i, 1)` | `calendar` | `i BETWEEN 1 AND 12` | the month under ANSI: an invalid date declines the batch and the row engine raises; the legacy form answers null and declines nothing | vector |
| `trunc(d, 'YEAR')` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `trunc(d, 'MONTH')` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `trunc(d, 'QUARTER')` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `trunc(d, 'WEEK')` | `calendar` |  |  | vector |

#### Months and year-month intervals

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `add_months(d, 3)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `add_months(d, i)` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896 AND i BETWEEN -24576 AND 24564` | a column month count, inside the range the month arithmetic's magic divide is exact over | vector |
| `d + INTERVAL 3 MONTH` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `d + ym` | `calendar` | `unix_date(d) BETWEEN -719162 AND 2932896 AND ym BETWEEN INTERVAL '-2048-0' YEAR TO MONTH AND INTERVAL '2047-0' YEAR TO MONTH` | the interval's month count, under the same range | vector |
| `d - ym` | `calendar`, `interval` | `unix_date(d) BETWEEN -719162 AND 2932896 AND ym BETWEEN INTERVAL '-2047-0' YEAR TO MONTH AND INTERVAL '2048-0' YEAR TO MONTH` | the negated count under the range, so the bounds change sides | vector |
| `make_ym_interval(year(d), month(d))` | `calendar`, `interval` | `unix_date(d) BETWEEN -719162 AND 2932896` | the operands must be bounded - over a bare int column the checked multiply inside it keeps its overflow test and declines | vector |
| `make_ym_interval(year(d), month(d)) * 2` | `calendar`, `interval` | `unix_date(d) BETWEEN -719162 AND 2932896` | a checked multiply, so bounded operands again: `ym * 2` over a stored interval column declines | vector |
| `abs(ym)` | `interval` | `ym > INTERVAL '-178956970-8' YEAR TO MONTH` | interval arithmetic is exact in every mode: the one count whose negation overflows declines | vector |
| `ym - ymm` | `interval` | `CAST(ym AS BIGINT) - CAST(ymm AS BIGINT) BETWEEN -2147483648 AND 2147483647` | exact in every mode: a difference outside the month count's int declines | vector |
| `CAST(ymy AS INTERVAL MONTH)` | none |  |  | vector |
| `CAST(i AS INTERVAL MONTH)` | none |  | a relabel of the count; `CAST(i AS INTERVAL YEAR)` multiplies by twelve and declines | vector |
| `CAST(ymm AS INT)` | none |  | a relabel of a MONTH-ended interval; a YEAR-ended one divides and declines | vector |
| `extract(YEAR FROM ym)` | `interval` |  | a division by twelve over a month count nothing bounds, so the calendar's range-narrowed magic cannot serve it; a multiply-high through 64-bit lanes does | vector |
| `extract(YEAR FROM ym) - 1` | `interval`, `intArithmetic` | `extract(YEAR FROM ym) > -2147483648` | the quotient feeding further int arithmetic, checked under ANSI; the check cannot fire, since the quotient is within +/-178956970 | vector |

#### Integer arithmetic

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `i + 1` | `intArithmetic` | `i < 2147483647` | checked under ANSI; the legacy mode wraps as Spark does and declines nothing | vector |
| `i - 1` | `intArithmetic` | `i > -2147483648` | checked under ANSI | vector |
| `year(d) * 2` | `calendar`, `intArithmetic` | `unix_date(d) BETWEEN -719162 AND 2932896` | a multiply is checked, and only bounded operands take the check off: `i * 2` over a bare int column declines | vector |
| `-i` | `intArithmetic` | `i > -2147483648` | checked under ANSI | vector |
| `year(d) + i` | `calendar`, `intArithmetic` | `unix_date(d) BETWEEN -719162 AND 2932896 AND i <= 2147483647 - year(d)` | checked under ANSI; the lower bound is out of reach, since a year is at least 1 | vector |

#### Bitwise and shifts

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `i & 255` | `intArithmetic` |  |  | vector |
| `i \| 4096` | `intArithmetic` |  |  | vector |
| `i ^ 21845` | `intArithmetic` |  |  | vector |
| `~i` | `intArithmetic` |  |  | vector |
| `i << 3` | `intArithmetic` |  | the count is masked to its low five bits as Java's `<<` does, so `i << 33` is `i << 1` | vector |
| `shiftright(i, i)` | `intArithmetic` |  | a column count, masked the same way | vector |
| `i >>> 28` | `intArithmetic` |  |  | vector |
| `bit_count(i)` | `intArithmetic` |  | the bits of the value widened to a bigint, so a negative int counts its 32 high bits as well | vector |
| `l & l2` | `intArithmetic` |  |  | vector |
| `l \| 5000000000` | `intArithmetic` |  |  | vector |
| `l ^ l2` | `intArithmetic` |  |  | vector |
| `~l` | `intArithmetic` |  |  | vector |
| `l << 3` | `intArithmetic` |  | a literal count only: a bigint shifted by an int column is not fused yet | vector |
| `l >> 40` | `intArithmetic` |  |  | vector |
| `shiftrightunsigned(l, 63)` | `intArithmetic` |  |  | vector |
| `bit_count(l)` | `intArithmetic` |  | as an output only: a count computed in the long lane and stored as an int, so `bit_count(l) + 1` declines | vector |
| `bitmap_bucket_number(l)` | `intArithmetic` |  |  | per-lane: compare to mask, blend, mask cast, mask broadcast |
| `bitmap_bit_position(l)` | `intArithmetic` |  |  | per-lane: compare to mask, blend, mask cast, mask broadcast |

#### Choice and nulls

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `if(d < d2, d, d2)` | `predicate`, `condition` |  |  | vector |
| `CASE WHEN d < d2 THEN d ELSE d2 END` | `predicate`, `condition` |  |  | vector |
| `CASE WHEN (d < d2) IS NULL THEN d2 ELSE d END` | `predicate`, `condition` |  | a condition on whether a comparison is unknown (VARKA-334); a default session's NullDownPropagation pushes the test onto the columns, so this form reaches Varka as written only with that rule excluded | vector |
| `CASE WHEN (d < d2) IS NULL THEN d WHEN d < d2 THEN d2 ELSE d END` | `predicate`, `condition` |  | one comparison under its null test and as a condition of its own, lowered once; a default session's NullDownPropagation pushes the test onto the columns, so this form reaches Varka as written only with that rule excluded | vector |
| `CASE WHEN (d = d2) IS NULL THEN d2 ELSE d END` | `predicate`, `condition` |  | the optimizer leaves an equality under its null test, so a default session reaches this form | vector |
| `coalesce(d, d2)` | `condition` |  |  | vector |
| `greatest(d, d2)` | `intArithmetic` |  |  | vector |
| `least(d, d2)` | `intArithmetic` |  |  | vector |

#### Predicates

| Predicate | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `d IS NULL` | `predicate` |  |  | vector |
| `d IS NOT NULL` | `predicate` |  |  | vector |
| `d = d2` | `predicate` |  |  | vector |
| `d < d2` | `predicate` |  |  | vector |
| `d <= d2` | `predicate` |  |  | vector |
| `d > d2` | `predicate` |  |  | vector |
| `d >= d2` | `predicate` |  |  | vector |
| `year(d) = 2021` | `calendar`, `predicate` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `NOT (d = d2)` | `predicate` |  |  | vector |
| `d IN (DATE '2021-01-01', DATE '2021-06-01')` | `predicate` |  | up to 16 literals | vector |
| `year(d) = 2021 AND i > 0` | `calendar`, `predicate` | `unix_date(d) BETWEEN -719162 AND 2932896` | every conjunct of an AND fuses or the predicate is not in this table; the int column compares in the kernel rather than leaving a residual row filter (VARKA-122) | vector |
| `i > 0` | `predicate` |  | a bare int column compares in the kernel (VARKA-122) | vector |
| `i IS NOT NULL` | `predicate` |  | the same column's validity word, which the optimizer infers beside `i > 0` | vector |
| `i = 5` | `predicate` |  |  | vector |
| `month(d) > i` | `calendar`, `predicate` | `unix_date(d) BETWEEN -719162 AND 2932896` | an int column against a fused int field | vector |
| `year(d) = 2021 OR month(d) = 3` | `calendar`, `predicate` | `unix_date(d) BETWEEN -719162 AND 2932896` |  | vector |
| `(d < d2) IS NULL` | `predicate` |  | the unknown partition of a comparison, read off the masks it computes (VARKA-334); a default session's NullDownPropagation pushes the test onto the columns, so this form reaches Varka as written only with that rule excluded | vector |
| `(d < d2) IS NOT NULL` | `predicate` |  | the comparison known, true or false; a default session's NullDownPropagation pushes the test onto the columns, so this form reaches Varka as written only with that rule excluded | vector |
| `(d = d2) IS NOT NULL` | `predicate` |  | the optimizer leaves an equality under its null test, so a default session reaches this form | vector |
| `(year(d) = 2021 AND i > 0) IS NULL` | `calendar`, `predicate` | `unix_date(d) BETWEEN -719162 AND 2932896` | whether a conjunction is known depends on its operands' values, so the calendar field under it keeps its guard | vector |
| `(d < d2 OR i = 5) IS NOT NULL` | `predicate` |  | a disjunction is not null-intolerant, so a default session reaches this form | vector |
| `(NOT (d = d2)) IS NULL` | `predicate` |  | a negation's masks are its child's, swapped; a default session drops the negation and keeps the equality under the test | vector |
| `(d < d2) IS NULL OR d < d2` | `predicate` |  | one comparison under its null test and beside it: the rows where it is not false; a default session's NullDownPropagation pushes the test onto the columns, so this form reaches Varka as written only with that rule excluded | vector |
| `d IN (11 to 16 date literals)` | `predicate` |  | an IN list this long arrives from the optimizer as an InSet and fuses the same way | vector |
| `i < 7` | `predicate` |  |  | vector |
| `i <= 7` | `predicate` |  |  | vector |
| `i >= 7` | `predicate` |  |  | vector |
| `i IS NULL` | `predicate` |  |  | vector |
| `ym = INTERVAL '1-2' YEAR TO MONTH` | `predicate` |  |  | vector |
| `ym < INTERVAL '1-2' YEAR TO MONTH` | `predicate` |  |  | vector |
| `ym <= INTERVAL '1-2' YEAR TO MONTH` | `predicate` |  |  | vector |
| `ym > INTERVAL '1-2' YEAR TO MONTH` | `predicate` |  |  | vector |
| `ym >= INTERVAL '1-2' YEAR TO MONTH` | `predicate` |  |  | vector |
| `ym IN (INTERVAL '1-2' YEAR TO MONTH, INTERVAL '-0-3' YEAR TO MONTH)` | `predicate` |  |  | vector |
| `ym IN (11 to 16 year-month literals)` | `predicate` |  | an IN list this long arrives from the optimizer as an InSet and fuses the same way | vector |
| `ym IS NULL` | `predicate` |  |  | vector |
| `ym IS NOT NULL` | `predicate` |  |  | vector |

#### Long-lane predicates

| Predicate | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `l > l2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `l = l2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `l >= 5000000000` | `predicate` |  | a bigint literal takes a slot in the long lane's own table | per-lane: compare to mask, mask cast |
| `l IS NULL` | `predicate` |  |  | per-lane: mask broadcast, mask logic, mask cast |
| `l IS NOT NULL` | `predicate` |  |  | per-lane: mask cast, mask broadcast |
| `t < t2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `t = TIME'12:34:56.789'` | `predicate` |  | a TIME literal of another precision reaches the column through the widening cast, which is the identity on nanoseconds | per-lane: compare to mask, mask cast |
| `dt > dt2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `dt < INTERVAL '0' SECOND` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `dt IS NOT NULL` | `predicate` |  |  | per-lane: mask cast, mask broadcast |
| `l < l2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `l <= l2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `t > t2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `t >= t2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `t <= t2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `t IS NULL` | `predicate` |  |  | per-lane: mask broadcast, mask logic, mask cast |
| `t IS NOT NULL` | `predicate` |  |  | per-lane: mask cast, mask broadcast |
| `dt = dt2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `dt >= dt2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `dt <= dt2` | `predicate` |  |  | per-lane: compare to mask, mask cast |
| `dt IS NULL` | `predicate` |  |  | per-lane: mask broadcast, mask logic, mask cast |
| `CAST(TIME'12:34:56.789' AS TIME(6)) = t` | `predicate` |  | the widening cast written out; a narrowing one floors the nanoseconds and declines | per-lane: compare to mask, mask cast |
| `(l > l2) IS NULL` | `predicate` |  | the unknown partition at the long lane (VARKA-334); a default session's NullDownPropagation pushes the test onto the columns, so this form reaches Varka as written only with that rule excluded | per-lane: mask broadcast, mask logic, mask cast, compare to mask |
| `(t < t2) IS NOT NULL` | `predicate` |  | the long lane's; a default session's NullDownPropagation pushes the test onto the columns, so this form reaches Varka as written only with that rule excluded | per-lane: mask cast, mask broadcast, compare to mask |

#### Width conversions

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `CAST(i AS BIGINT)` | `intArithmetic` |  | a sign extension, exact in every mode | per-lane: mask broadcast, mask test |
| `CAST(time_to_millis(t) AS INT)` | `time`, `intArithmetic` | `time_to_millis(t) BETWEEN -2147483648 AND 2147483647` | a bigint narrowed to an int: under ANSI a value past the int range declines and the row engine raises CAST_OVERFLOW, and the legacy mode keeps the low 32 bits, as Spark does; a day's milliseconds always fit | per-lane: compare to mask, mask logic, mask broadcast, mask test |
| `greatest(i, l)` | `intArithmetic` |  | type coercion widens the int, and the pick is the long lane's | per-lane: blend, mask broadcast, load or store, mask test |
| `CASE WHEN i < l THEN i ELSE l END` | `predicate`, `condition`, `intArithmetic` |  | the widened int in a condition and in a branch of a long-valued conditional | per-lane: compare to mask, blend, mask cast, mask broadcast, load or store, mask test |

#### Width conversion predicates

| Predicate | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `i < l` | `predicate`, `intArithmetic` |  | an int against a bigint compares in the long lane, the int widened | per-lane: compare to mask, mask cast, mask broadcast, load or store |
| `l = i` | `predicate`, `intArithmetic` |  |  | per-lane: compare to mask, mask cast, mask broadcast, load or store |
| `CAST(time_to_millis(t) AS INT) > i` | `time`, `predicate`, `intArithmetic` | `time_to_millis(t) BETWEEN -2147483648 AND 2147483647` | the narrowed value compared in the int lane | per-lane: compare to mask, mask logic, mask broadcast, mask test, load or store |
| `hour(t) = 12` | `time`, `predicate` |  | the TIME extract narrowed inside the predicate | per-lane: mask broadcast, load or store |
| `(i = 5 AND l = l2) IS NOT NULL` | `predicate` |  | the int conjunct widened to meet the bigint one, and the null test over both | per-lane: mask cast, mask broadcast, compare to mask, load or store |
| `(i = 5) IS NULL OR l = l2` | `predicate` |  | a null test over an int comparison lifted whole to the long lane beside a bigint one | per-lane: mask broadcast, mask logic, compare to mask, mask cast, load or store |

#### Long-lane choice

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `greatest(l, l2)` | `intArithmetic` |  |  | per-lane: blend, mask broadcast |
| `least(l, 5000000000)` | `intArithmetic` |  |  | per-lane: blend, mask broadcast |
| `CASE WHEN l < l2 THEN l ELSE l2 END` | `predicate`, `condition` |  |  | per-lane: compare to mask, blend, mask cast, mask broadcast |
| `if(l IS NULL, l2, l)` | `predicate`, `condition` |  |  | per-lane: mask broadcast, mask logic, blend |
| `greatest(t, t2)` | `intArithmetic` |  |  | per-lane: blend, mask broadcast |
| `CASE WHEN dt > INTERVAL '0' SECOND THEN dt ELSE dt2 END` | `predicate`, `condition` |  |  | per-lane: compare to mask, blend, mask cast, mask broadcast |

#### Time arithmetic

| Expression | Switches | Domain | Notes | 128-bit lanes |
|---|---|---|---|---|
| `t - t2` | `time` |  | a day-time interval, the nanosecond difference divided by a thousand | vector |
| `time_diff('HOUR', t, t2)` | `time` |  | the unit is a literal; a column of units would need a kernel per distinct value | vector |
| `time_diff('microsecond', t2, t)` | `time` |  | units are read case-insensitively, as DateTimeUtils reads them | vector |
| `time_trunc('MINUTE', t)` | `time` |  |  | vector |
| `time_trunc('MILLISECOND', t2)` | `time` |  |  | vector |
| `hour(t)` | `time` |  | an int computed in the 64-bit lane and narrowed at the kernel's store; under another expression it narrows where it is | vector |
| `hour(t) + 1` | `time`, `intArithmetic` | `hour(t) < 2147483647` | the extract narrowed inside the tree, under an int add checked under ANSI; the check cannot fire, since an hour is at most 23 | per-lane: mask logic, mask broadcast, mask test, load or store |
| `minute(t2)` | `time` |  |  | vector |
| `second(t)` | `time` |  |  | vector |
| `time_to_millis(t)` | `time` |  |  | vector |
| `time_to_micros(t2)` | `time` |  |  | vector |
| `time_from_millis(time_to_millis(t2))` | `time` | `time_to_millis(t2) BETWEEN 0 AND 86399999` | the count is guarded to the day's worth of its unit, where the multiply is exact; a count outside declines the batch and the row engine raises Spark's error, as for t + dt - so the count here is one that is inside the day by construction | per-lane: compare to mask, mask logic, mask broadcast, mask test |
| `time_from_micros(time_to_micros(t))` | `time` | `time_to_micros(t) BETWEEN 0 AND 86399999999` | the same guard over the microsecond count | per-lane: compare to mask, mask logic, mask broadcast, mask test |
| `t + INTERVAL '0 00:00:00' DAY TO SECOND` | `time` | `time_to_micros(t) + 0 BETWEEN 0 AND 86399999999` | a zero interval on purpose: this fixture reaches both ends of the day, so any other constant crosses midnight on some row, where Spark raises an error rather than a value. The sum is still guarded to the day - the domain adds the interval's microseconds, 0 here, to the time's; the crossing case, and a column interval, are VarkaTimeArithmeticSuite's | per-lane: compare to mask, mask logic, mask broadcast, mask test |

<!-- END generated coverage table -->

## Glossary

Varka's code and documents lean on about two dozen words that mean something
specific here. Two are worth flagging before the list, because they are ordinary
English elsewhere and technical terms here: a **lane** is one element of a
vector register, not a code path; and a **word** is always a 64-bit validity
bitmask, never a machine word or a token of text.

| Term | What it means here |
| :--- | :--- |
| **lane** | One element position in a vector register. A 512-bit register holds 16 int32 lanes, a 256-bit one 8. Nearly every loop in the engine is written per lane group rather than per row. |
| **lane group** | The rows one vector register holds - the unit every emitted loop iterates over. Its size is the JVM's preferred species width divided by four bytes, so 16 rows at 512 bits. Not a fixed number: the same kernel runs a different group size on a narrower machine. |
| **lane type** | The width a node's value occupies, and a property of its physical representation rather than its Spark type: `DATE`, `INT` and a year-month interval are all the same 32-bit lane. Carried by the IR's leaves, derived at every other node, and refused where a node's operands disagree. |
| **species** | The Vector API's term for a lane type and width together, e.g. `IntVector.SPECIES_512`. What `-XX:MaxVectorSize` ultimately selects. |
| **epilogue** | The rows left over when the row count is not a whole number of lane groups. Varka runs them as one more iteration of the *same* vector body under a partial mask, rather than as a scalar loop - so there is one body to maintain, not two. |
| **word** | A 64-bit mask of validity bits, one bit per row, for one lane group: `0L` means every row null, `-1L` means none null, anything else is the row-by-row truth. Each node's word is computed from its children's - AND for operations that a null poisons, OR for `greatest`/`least`, a blend for `IF`. "The word" in this codebase never means anything else. |
| **validity buffer** | Arrow's per-column null map: one bit per row, set meaning *valid*. Bit-packed, so it is read a `long` at a time and turned into a mask - reading it a byte per lane would be a correctness bug, not a slow path. |
| **morsel** | The pair of Panama `MemorySegment`s a kernel reads and writes: an Arrow column's data buffer and its validity buffer, mapped zero-copy, off-heap. No per-row object is ever created on the fast path. |
| **dense body / masked body** | The two loop bodies every emitted kernel carries. A batch whose referenced inputs contain no nulls at all runs the *dense* body, which does no validity bookkeeping; anything else runs the *masked* body. The choice is one test per batch, not per row. |
| **fused kernel** | The class Varka emits at runtime for one projection or filter: a single vector loop computing every output, with intermediates living in registers. It implements `VarkaFusedKernel`. |
| **shape** | A plan's structure with its constants removed - the thing two queries share when they differ only in literal values. Literals travel separately as runtime arguments, so `date_add(d, 1)` and `date_add(d, 7)` have one shape and share one emitted class. |
| **shape cache** | A bounded LRU of emitted classes keyed by shape, shared *across tasks*. The cost it saves is not emission - that is about 80 microseconds - but JIT warm-up: a re-defined class is a new class to HotSpot and re-pays the whole tier ladder, 13 to 50 ms per task. Reusing the loaded class is the only thing that avoids it. |
| **literal slot** | An index into the kernel's runtime argument array where a folded constant lives. Why the IR carries no literal *values*: keeping them out is what makes the shape the identity. |
| **selection bitmap** | A filter's output: one bit per row, set meaning the predicate is known true. An unknown row - a null under the comparison - reads as false by construction, which is SQL's `WHERE` semantics for free. |
| **partial eligibility** | A projection qualifies when *at least one* of its entries compiles. The rest are forwarded zero-copy or left to stock Spark, and the results merge. Eligibility is per entry, not all-or-nothing. |
| **decline** | A compile-time refusal: the compiler cannot translate an expression, or the emitter will not build a shape. Declining is a normal outcome, not an error - the query runs on stock Spark instead. |
| **ghost fallback** | The run-time counterpart: if an emitted kernel fails on a batch, that batch is recomputed by the ordinary row engine and the query still returns the right answer. The Janino projection behind it is compiled lazily, only if some batch actually needs it. |
| **civil-from-days** | The calendar algorithm turning a day count since the epoch into year, month and day (Neri and Schneider's, transcribed in `sql/varka/papers`). Branch-free and 31 vector operations, which is why several outputs over one date share it. |
| **prefix** | The shared front half of `civil-from-days`. Calendar fields over the same date compute it once per lane group and each take their own tail from it, so `year(d)` beside `month(d)` costs barely more than one of them. |
| **range guard** | A runtime check that a date lies inside the range the decomposition is exact over. Where it sits matters: it is placed on the *producer* of a value rather than on each consumer, so one check covers every calendar field downstream. |
| **guarded producer** | A node that carries such a check on its own result - typically `date_add`/`date_sub` with a column offset, where the shift is not knowable at compile time. |
| **op count** | Vector operations in an emitted loop body, as `dev/varka_emit.sh --table` reports them. The project's unit for "how much work is this expression", used to size methods and to choose benchmark entries. |
| **fixed share** | `(wall time - executor time) / wall time` for a benchmark iteration: the fraction spent scheduling rather than computing. A run is failed above 5%, because below that the number measures the kernel and above it measures Spark's job overhead. |
| **datapath probe** | A measurement of *lanes per nanosecond* at 128, 256 and 512 bits. It answers the only question that matters for a benchmark claim - how many wide operations the machine issues per cycle - which no CPU flag reports: some server parts carry the full AVX-512 flag set and still issue 512-bit work at two thirds the rate. |

## Architecture

### One query's journey

Two things happen at different times, and most confusion about this engine comes
from mixing them up. **Plan time** decides what Varka will run and builds a
class to run it. **Run time** executes that class over Arrow buffers, batch by
batch.

```
PLAN TIME  - once per plan shape
===============================================================================

   Catalyst physical plan
            |
            v
   VarkaColumnarRule ............ finds a projection or filter over a
            |                     columnar source and asks: is it eligible?
            v
   VarkaExpressionCompiler ...... translates each entry from Catalyst
            |                     into the IR  -- or DECLINES it
            |
            |  declined ------> entry stays on stock Spark. A projection is
            |                   still taken if ANY entry compiled; the rest
            |                   are forwarded zero-copy or left as residual.
            v
   VarkaVectorIR ................ records over int32 lanes. Literal VALUES
            |                     do not appear - a folded constant becomes
            |                     a slot index, so two queries differing only
            |                     in constants have one shape.
            v
   VarkaLoopEmitter ............. walks the IR and assembles bytecode with
            |                     the Class-File API. Intermediates stay on
            |                     the operand stack, so they stay in vector
            |                     registers.
            v
   a class implementing VarkaFusedKernel
            |
            +--> VarkaShapeCache  one class per shape, reused across tasks:
                                  emitting costs ~80us, but re-defining a
                                  class costs a fresh JIT warm-up per task.


RUN TIME  - once per batch
===============================================================================

   Arrow ColumnarBatch
            |
            v
   VarkaMorsel .................. maps each column's data and validity
            |                     buffers onto Panama MemorySegments.
            |                     Zero-copy: nothing is materialised.
            v
   the emitted kernel's run(...)
            |
            |   are all referenced inputs null-free for THIS batch?
            |
            +-- yes --> dense body ...... no validity bookkeeping at all
            |
            +-- no ---> masked body ..... one 64-bit validity word per lane
            |                             group; each node's word computed
            |                             from its children's
            |
            |   ...both then run the epilogue for the rows past the last
            |   whole lane group - the same body under a partial mask,
            |   not a scalar loop.
            |
            +-- refuses the batch --> the ghost fallback: this batch is
            |                         recomputed by the row engine, and the
            |                         query still returns the right answer.
            v
   output columns (+ a selection bitmap, for a filter)
            |
            v
   VarkaProjectExec / VarkaFilterExec / VarkaColumnarToRowExec
            |
            v
   back to Spark - columnar to the next columnar operator, or converted to
   rows at the boundary. Crossing to rows costs about 25 ns per row, which
   is why a cheap predicate feeding COUNT(*) can lose to stock Spark while
   the same predicate feeding a columnar consumer wins by an order of
   magnitude - the kernel is identical; only the boundary differs.
```

**The one property worth carrying away**: every arrow labelled *declines* or
*refuses* leads somewhere correct. The compiler can decline an expression, the
emitter can refuse a shape, the kernel can refuse a batch - and each falls back
to stock Spark at that granularity. Nothing in this diagram can produce a wrong
answer by giving up.

### Columnar morsels

Spark's `DateType` columns reach Varka as Arrow `DateDayVector` (int32 days)
with a bit-packed validity buffer (1 bit per row, bit set = valid); an
`IntegerType` column arrives as an `IntVector` and a year-month interval as an
`IntervalYearVector`, both `BaseFixedWidthVector`s of width four with the same
buffer layout, which is why the lane code is unaware of the difference. A morsel
maps the data and validity buffers onto zero-copy Panama `MemorySegment`s, so
no heap objects are built per row:

    data segment     -> bytes of `4 * rowCount`
    validity segment -> bytes of `(rowCount + 7) / 8`, only when the column has
                        neither no nulls nor is fully null

The validity buffer is bit-packed. A byte-per-lane read would be a correctness
bug; the kernels instead load a `long` and build a `VectorMask` with
`VectorMask.fromLong`.

### The emitted fused loop

The live compute path since milestone 2. `VarkaExpressionCompiler` translates
an eligible projection into a small vector IR (`VarkaVectorIR` - column refs,
literal slots, arithmetic, comparisons, conditionals); `VarkaLoopEmitter`
assembles a class implementing `VarkaFusedKernel` from it with the Class-File
API - no Java source, no Janino, no external bytecode library. The emitted
class has a deliberate method anatomy:

* A per-batch dispatch picks one of two twin bodies: a *dense* body with
  unmasked loads and stores when every input is null-free (measured 2.3-2.9x
  the masked body in VARKA-10), and a *masked* body that builds a
  `VectorMask` per lane group from the bit-packed validity words otherwise.
  Since VARKA-70 the masked *driver* writes an output's validity bitmap once
  per batch wherever it is a pure AND/OR of the input bitmaps - which is every
  calendar field, every date arithmetic node, `datediff` and the picks - and
  the loop then neither writes it per group nor, where nothing else reads
  them, loads the input words per group; for such a shape the masked loop and
  epilogue are the dense ones' bytes. What stays per group is what is not a
  function of input bitmaps: a filter's selection bitmap, `IF`/`CASE`'s blend,
  `make_date`'s validity test, and every word a range guard or a comparison
  still reads. `greatest` and `least` are served for their own validity and
  still read both operands' words per group, because that is how the value is
  built - the null operand is substituted by the other - so they gain less
  than the calendar shapes do. A tree that mixes the two operators, such as
  `datediff(greatest(d, d2), greatest(d3, d4))`, is not served at all and
  keeps its per-group write.
* The vector walk is split into sibling loop methods, one output group per
  method, plus a shared *epilogue* method for the remainder rows. A group's
  outputs are priced by the vector ops their nodes emit, and a group grows up
  to `FUSED_CEILING` (400) while the emit cost model predicts its methods
  within the byte and call-site budgets, with the plan's margins (VARKA-254);
  where the model cannot bound it, the older rule binds - `GROUP_BUDGET` (16)
  ops a method, or up to the ceiling where the outputs share a calendar prefix
  (VARKA-32). The built class is then measured, and a method over a limit has
  its group split. Separate methods, not one big loop: each gets its own C2 compilation,
  so no method's inlining budget can starve another's intrinsics - VARKA-10
  measured 3-4x on exactly that cliff, and VARKA-24 measured the same cliff
  from the other side when it tried leaving the epilogue inline in a kernel's
  loop method.
* Each body's *driver* - the method that prepares every output's validity,
  takes the all-null shortcut and calls the loop and epilogue methods in turn -
  does its per-output work in one call reading a table baked into the class,
  rather than as code repeated for each output (VARKA-190). So it grows by two
  calls per loop-method group, 44 bytes, and not with the outputs, the columns
  or the literals: repeated per output it passed the 8000-byte
  `HugeMethodLimit` at about 140 outputs, which capped how wide one kernel
  could be. Past about 180 groups even those calls pass the limit, and the
  driver then moves them into *stage* methods, each calling a run of groups,
  which it calls in turn; the stages are sized from the driver's measured
  bytes, so the class is built once more and no width is capped by its driver.
* Interned subtrees (DAG-CSE) are computed once per lane group and reused
  across outputs; literals are hoisted to broadcast vectors in the prologue.
* Below the node level, the calendar extractions share their civil-from-days
  decomposition: `year(d)`, `month(d)`, `dayofmonth(d)`, `quarter(d)` and
  `add_months(d, n)` are distinct IR nodes, but a method that emits several of
  them over one date runs the 29-op decomposition once and gives each output
  only its own tail (VARKA-32). The grouping keeps such outputs in one loop
  method for exactly that reason - an output that reuses a prefix the method
  already computes may join it up to `FUSED_CEILING` whichever rule bounds the
  group - so the hot loop pays the decomposition once: the four-field projection runs at 1762.6 M rows/s against 818.0 with
  a method per field (2.15x at AVX-512, 2.51x at 128-bit; the parity file).
  The epilogue, the one method every output shares, decomposes once per date
  as well, which is what keeps a wide date projection's epilogue small enough
  for HotSpot to compile at all.
* Caps: chains up to `MAX_CHAIN_DEPTH` (16) deep, and up to `MAX_INPUTS` (64)
  input columns per kernel. A projection over more columns is served by
  several kernels, run in turn over each batch into one output batch: the
  compiler compiles the entries one kernel sets aside into a further kernel,
  and verbose `EXPLAIN` names the kernel of each such entry (VARKA-190). A
  kernel's size is bounded by the byte budget rather than by ops;
  `MAX_FUSED_NODES` (64 ops) applies only with the budget off. A chain past
  its depth falls back.

Date arithmetic wraps on overflow, matching Spark's `DateAdd`/`DateSub`,
which check nothing in any mode. The int arithmetic nodes VARKA-63 added are
the exception and carry their evaluation mode instead, described in the
surface list above; where a checked one is emitted, the sign test's mask joins
the same accumulator VARKA-52's range guard uses, so both kinds of refusal
leave the loop through one status bit. The rows past `loopBound` are one more iteration of the
same lane-group body under the mask `VectorSpecies.indexInRange` builds for a
partial group (VARKA-24): the loop stays unmasked, only the epilogue's loads and
stores take their masked overloads, and the lane count becomes the remainder so
every validity access stays bounded by the group. This replaced a scalar tail
that lowered every IR node a second time - the half of the emitter that would
otherwise have had to grow with every node type added after it. One invariant
comes with it: lanes outside the mask read `0`, so no operation in the walk may
trap on `0`.

The milestone-1 per-op kernels (`DateVectorOps.vectorAddDays` and friends)
remain in the engine as reference code and as the differential oracle for the
emitter's tests. The per-op dispatcher machinery they were once called through -
the `ClassFileCodegenSupport` trait, the `VarkaClassFileGen` assembler and the
kernel-shape interfaces - was retired in VARKA-17, along with the
`CodeAndComment` cache-key field it fed (`m2/PLAN.md` section 8).

### The IR in pictures

Three Graphviz drawings of `VarkaVectorIR` live under `docs/img/varka/`, each
as a `.dot` source and the `.svg` rendered from it (`dot -Tsvg x.dot -o x.svg`;
`-Tpng`/`-Tjpg` for a raster). They are a snapshot of the node set as of VARKA-61 (5 September 2026) and are re-rendered when a node is added, in the same
change that re-pins the shape hash.

* `varka-ir-hierarchy.svg`: what a node *is* - the sealed `permits` lists as a
  tree, grouped into the leaves, day arithmetic, the mod-7 family, selection,
  `AddMonths`, the `Chrono` family and the `Cond` family, with each record's
  components, its output kind (date, int field, mask) and its weight against
  `GROUP_BUDGET`.

  ![The sealed node families of VarkaVectorIR](img/varka/varka-ir-hierarchy.svg)

* `varka-ir-dataflow.svg`: what a node *takes* - every operator with one port
  per input component, typed edges from the kinds of value that can feed each
  port (a date value, an int field, a stored int column, a derived int32
  column from a leaf, a literal slot, a condition), and what each operator
  produces.

  ![Data flow between the IR nodes](img/varka/varka-ir-dataflow.svg)

* `varka-ir-levels.svg`: the same data flow in levels, bottom to top - the
  kernel's inputs, the leaves, the date producers, the field extractors, the
  conditions and selection, and what the user sees - with the SQL that
  compiles to each node written on it, and the emitter's two below-the-IR
  fragments drawn in as a dashed layer: the civil-from-days prefix that every
  calendar tail and `AddMonths` share within a lane group, and the mod-7
  lowering, which is emitted per node.

  ![The IR by data flow, in levels](img/varka/varka-ir-levels.svg)

### The shape cache, class loaders and Metaspace

VARKA-14 measured the cost of the original per-task lifecycle: every task
defined a fresh class, so HotSpot re-ran the whole tier ladder - interpreter,
C1 with boxed vectors, C2 OSR - a fixed 13-50 ms per task that emission (~80
us) never was. Since VARKA-18 the loaded class is shared instead:
`VarkaShapeCache` is a JVM-wide LRU keyed on the kernel's structural shape -
the IR (whose literal slots carry indices, never values), the input count and
the literal count, exactly the inputs the emitted bytes are a function of -
so tasks and sessions computing the same shape reuse one class, C2 code and
all. Each class lives in its own `VarkaClassLoader`, `release()`d when the
cache evicts it; once the last running task drops its reference the JVM
unloads the class, so Metaspace is bounded by the cache capacity rather than
by churn (Spark's codegen cache never releases a loader) or by task lifetime
(the pre-task-18 contract, still available at capacity 0). A registry +
`findClass` mirror Spark's `InMemoryClassLoader`.

### A new shape's first batches

A newly emitted class runs interpreted until HotSpot compiles it, and
interpreted Vector API code is a library call and an allocation per
operation - slower than Spark's own row code. A kernel's methods are called
once per batch, so over a query of ten batches they reach none of HotSpot's
compile thresholds. With `spark.sql.codegen.varka.warmup.enabled` (the
default), a shape's batches therefore take the node's per-row path until its
kernel is compiled: the first task to meet the new class copies its batch's
kernel inputs and queues a warm-up (`VarkaKernelWarmup`, one daemon thread per
JVM), which runs the kernel on that copy in short calls until it no longer
allocates, which it does only as C2's code. The calls alternate between the
kernel's two drivers - one for batches whose inputs have no nulls, one for
batches with them - so the verdict covers whichever the later batches need;
the second is left out only when no kernel input is nullable. From then on
every task of the shape runs the kernel. The per-shape state is
`VarkaKernelWarmth`, kept on the cache entry, and the batches served on the
row path meanwhile are counted in `numWarmupBatches` - on a Varka node above
another, that includes the batches the node below sends from its own row path
while its kernel warms. The warm-up is per cache entry, so a session with its
own artifact class loader warms its own class. A shape without a verdict a
minute after its warm-up was queued is released and runs its kernel as it is.

A warmed kernel is emitted under a class name of its own
(`VarkaFusedProjection_w...`), and C1 is kept off those classes by one
compiler directive (`VarkaKernelCompileDirective`). A kernel's loop methods
are too large for C1's fully profiled tier, and a method that C1 compiled at
the limited-profile tier instead - which HotSpot chooses while C2's queue is
long - would never be recompiled by C2; without C1 every warmed kernel method
goes from the interpreter to C2. The directive reaches warmed classes only,
because it also makes the interpreter start profiling later, which would
delay a kernel fed by its own batches alone: a session with the warm-up off,
or a JVM that cannot warm - no allocation accounting, no C2, or a directive
it did not accept - emits its kernels under the plain name and compiles them
as it did before the warm-up existed.

### Null semantics and predication

The vector loop cannot branch per row, so SQL's null and conditional
semantics are implemented in mask algebra; `m2/PLAN.md` section 2.6
is the normative statement of the rules. In brief:

* Arithmetic is null-intolerant: an output row is valid only where every
  referenced input is valid, tracked as per-lane-group validity words in the
  loop, and written whole by the driver where the word is a pure function of
  the input bitmaps (VARKA-70).
* Comparisons and `AND`/`OR`/`NOT` follow three-valued logic as a
  *known-true / known-false* mask pair (`unknown` is neither), so
  `null AND false = false` comes out right without a branch.
* `IF`/`CASE WHEN` execute *all* arms and pick per lane with
  `VectorMask.blend` - branch-free, so data-dependent conditions cost the
  same as predictable ones (the throughput benchmark prices this).
* `GREATEST`/`LEAST` skip null operands (Spark semantics) rather than
  propagating them.
* `DAYOFWEEK`/`WEEKDAY` lower `Math.floorMod(d, 7)` branch-free: two 15-bit
  digit-sum folds (`2^15 = 1 mod 7`) narrow the value until Granlund-Montgomery
  magic division by 7 is exact in the low 32 bits, with no final fixup - the
  full-range multiply-high the classic trick needs does not exist in the
  Vector API, but pre-folding makes the low half sufficient. The VARKA-11
  six-fold digit sum and the lanewise-DIV lowering are kept as reference
  variants (the parity benchmark's dayofweek section prices all three).

### Telemetry and debuggability

Every emitted class is self-describing (VARKA-13, reconciled with sharing in
VARKA-18): a `SourceFile` attribute named for the shape
(`VarkaFusedProjection_<hash>.java`, 16 hex chars of the shape's SHA-256), so
stack traces, profilers and heap dumps name the kernel with no mapping table,
and a `VarkaDebugInfo` custom attribute carrying the vector IR and the shape
identity. The class is shared across tasks, so the per-execution identity -
operator, stage, the projection list - is not in the bytes: the cache records
it per lookup in a bounded side table, and
`VarkaShapeCache.executionsFor(hash)` joins a shape name seen in a profile
back to the plan nodes that ran it. VARKA-16 extends that to the questions the
attributes alone did not answer:

* **Bytecode maps back to IR nodes.** The class carries a `LineNumberTable`
  whose line `n` is the `n`-th IR node in topological order, and
  `VarkaDebugInfo` records the decoding key (`<line>=<node>` per line). A
  stack frame reading `VarkaFusedProjection_<hash>.java:7` therefore names the node
  that threw, not merely the method - and profilers and crash logs inherit
  the same resolution for free.
* **Fallbacks name their kernel.** Every warning on the ghost-fallback path -
  emission failure and per-batch kernel failure, in both exec nodes - carries
  the kernel's `SourceFile` name, the IR it computes and the operator/stage it
  served, so a log line identifies the plan node without correlating
  timestamps.
* **The class reaches disk.** `spark.sql.codegen.varka.classDumpDirectory`
  writes each emitted class under its `SourceFile` name, so `javap -c -p`
  disassembles a generated loop with no debugger attached. Diagnostics only:
  a failed write is logged and never fails the query.
* **`EXPLAIN` says why an entry did not fuse.** Verbose `EXPLAIN` on a Varka
  projection node lists every projection entry as fused, forwarded (naming the
  child column) or residual with the compiler's decline reason - "unsupported
  expression", "CASE WHEN without an ELSE branch", "non-date column of
  type ..." - in the query's own column names. Since VARKA-38, a `date_add`/
  `date_sub` day offset that is neither a foldable literal nor a supported
  column gets its own reasons: "non-integer day offset column of type ..."
  for a `ShortType`/`ByteType` column, "day offset is not a foldable literal,
  an integer column or int arithmetic" for anything else (e.g. `i % 7`, whose
  operator no arm lowers). Since VARKA-63, "checked int multiply whose operands
  do not rule out overflow" names an ANSI or TRY `*` the compile-time bound
  cannot prove safe - which since VARKA-68 is also how an unbounded `ym * k`
  reports, the interval multiply being that same node - and "day offset
  arithmetic that lowers to a node the offset
  position does not take" names arithmetic in a `date_add`/`date_sub` offset
  that lowers to something other than an arithmetic node - `weekday(d) + 1`,
  which is VARKA-57's own node. (`intOperand` carries a third, "int arithmetic
  operand of type ...", which a resolved plan cannot reach: Spark requires both
  operands of an `Add` to share a type, so an `IntegerType` one has two int
  operands by construction.) Since VARKA-59, a `next_day` whose weekday is neither a literal
  nor a stored string column reports "next_day with a weekday that is neither
  a literal nor a column"; since VARKA-68, a multiplier that is not an int32
  lane reports "interval multiplier of type ... is not an int32 lane", naming
  the type rather than reporting it as a bad int operand, so `ym * 2.5` cannot
  be read as a `ym * i`; a `trunc` whose format is neither foldable nor a
  stored string column keeps VARKA-35's "trunc with a non-foldable format"
  (VARKA-61). Since VARKA-52, a calendar function over arithmetic
  that can leave the lowering's range reports "day range [lo, hi] leaves the
  calendar lowering's range", with the interval in epoch days, and one over a
  producer
  the range analysis does not know reports "day producer the calendar range
  analysis does not bound"; since VARKA-56, "day interval is not an int column
  cast to days" names a day interval that is not `CAST(i AS INTERVAL DAY)`
  over an int column. A Varka filter node (VARKA-21) reports its predicate the same
  way, one line per conjunct. The same account goes to the debug log once per
  task.

VARKA-22 extends the account to the SQL UI and to JDK Flight Recorder:

* **Fallback causes are SQL metrics.** Every Varka node carries, beside the
  row/batch counts and the class-cache hits and misses, cause-keyed
  metrics: batches falling back because the input was not Arrow-backed
  (empty batches are served trivially and carry no cause), batches the
  ghost fallback caught a kernel failure on (a failure in the per-row
  machinery beside the kernel is counted, evented and logged under its own
  `row-path-failure` cause instead), batches a kernel declined because a
  value fell outside the range one of its lowerings is defined over (the
  `range-declined` cause - a designed outcome rather than a defect, which is
  why it is not counted as a kernel failure and is logged at debug, with the
  guard that fired named by its status bit, below), tasks
  that could not emit or define their kernel class, and - on the projection
  nodes - the residual-entry
  count (a static plan property, added once driver-side and posted to the
  SQL listener; the per-entry reasons stay in verbose `EXPLAIN`; a
  filter's residual is a visible row `FilterExec` above it rather than a
  number, and its `numOutputRows` counts selected rows). A fallen-back
  query is diagnosable from the SQL UI alone: the cause class from the
  metrics, the exact reason from `EXPLAIN` or the log.
* **Declines by reason, rows and time by path (VARKA-313).** Each refusal
  reason has its own metric, `numDeclinedBatches[<reason>]` (`day-producer`,
  `month-count`, `make-date`, `int-overflow`, `day-range`, `value-range`,
  `input-bound`, `derived-input`, `forced`, and `unrecognised` for a status
  bit no name covers). A reason reads as a count, 0 included, where every
  declined batch that named it named it exactly, and as "not reported" where
  a batch named it among others: under the default emit options a loop
  method that holds guards of several reasons reports all of them for a
  batch any one declined (below), so such a batch cannot be counted against
  either, and setting `spark.sql.codegen.varka.emit.exactRefusalStatus` lets
  it be. Where both kinds occurred the cell gives both. `numVarkaRows` and
  `numFallbackRows` count the input rows each path answered (a projection
  that only forwards columns counts with the first), and `varkaKernelTime`
  the time in the kernels of the batches they served, until their output
  batch - on a row-out node the residual entries and the row conversion run
  lazily after it. `fallbackTime`, which includes the per-row machinery's
  one-time compile, is registered only on the columnar-out nodes, whose
  fallback builds its batch before it returns - a row-out node's fallback is
  a lazy row iterator, which a bracket around the call would not time. A
  Varka filter node's verbose `EXPLAIN` renders the whole condition when the
  rule split it, so a conjunct kept in the row `FilterExec` above reads as
  residual with its reason. Both the `EXPLAIN` lines and the metrics belong
  to the node itself, so they survive adaptive re-planning.
* **Boxing is sampled.** A kernel whose Vector API loop boxes - on HotSpot,
  because two vector species of one lane type ran hot in the same JVM -
  still answers correctly, several times slower, so no fallback and no
  differential test can see it. The evaluator samples the bytes the thread
  allocated across the kernel call on a schedule that skips the JIT warm-up
  (batch 512, then every power of two and every 4096th batch), counts a
  sample above a fixed allowance plus one byte per row under
  `numSuspectAllocationSamples`, and warns once per task on the second
  suspect sample in a row, naming the kernel. `VarkaAllocationSampler` holds
  the schedule and the verdict; `SKILLS.md`'s species-pollution section has
  the mechanism and the C2 evidence.
* **JFR events.** Four events under the `Varka` category fire while a JDK
  Flight Recorder recording is active - no JVM flag or build change is
  needed, `jdk.jfr` is a default module. Under the shared prefix
  `org.apache.spark.sql.varka`: `KernelEmission` (timed over emit plus
  class define; shape hash, class name, IR sizes, byte count),
  `ShapeCacheLookup` (shape hash, hit, the per-execution identity - the
  join a profile needs), `Fallback` (cause, kernel identity, the emit
  options that differ from the defaults, exception class, and for a
  declined batch its status word; the cause vocabulary is
  the constants on `VarkaFallbackEvent`), and
  `KernelAllocation` (one per allocation sample, suspect or not: kernel
  identity, batch index, rows, allocated bytes, verdict).
  Record with a programmatic `jdk.jfr.Recording`, `jcmd JFR.start`, or
  `-XX:StartFlightRecording`.
* **A declined batch names its guard.** Each runtime refusal reason has its
  own status bit (`VarkaFusedKernel`: day producer, month count,
  `make_date`, int overflow, re-armed day range, value range), above them
  the evaluator's own (input bound, derived input), and the debug line and
  the `Fallback` event carry the word. A loop method whose guards share one
  reason reports exactly it; one that holds guards of several reasons folds
  them into one mask and reports all of their bits, a candidate set, since a
  mask per reason would hold another vector register in the loop. The emit
  option `exactRefusalStatus` gives each reason its own mask, for exact
  attribution when diagnosing; a session sets it with
  `spark.sql.codegen.varka.emit.exactRefusalStatus=true` (internal, off by
  default), and the kernels it emits are cached apart from the default ones. It is off by
  default because it costs a guarded kernel 2.5 to 3.9 percent of its rate on the measured case
  (VARKA-313); set it for the session that diagnoses and pay for the exact reasons there.

VARKA-315 leaves a reproduction file behind a kernel failure, as the JVM leaves
an `hs_err` file behind a crash:

* **What writes one.** A kernel class that fails to verify or define, an
  exception thrown inside a kernel, and a memory-sanitizer violation. Each
  writes from the `catch` that already handles it and then falls back or fails
  exactly as before; a file that cannot be written is a warning.
* **What it holds.** The failure's signature (`VarkaFailureSignature`: its kind
  and its message with the numbers taken out), the context record of the
  warning, the shape key the class is built from - each output's canonical IR,
  the counts, the emit options that differ from the defaults by name - the
  literal values, the batch as the kernel read it (each input's values,
  validity, null count and buffer capacity), and the class bytes. A batch over
  `spark.sql.codegen.varka.reproduction.maxBatchBytes` keeps its shape and not
  its values.
* **Where it goes.** `varka-repro-<shape hash>-<kind>-pid<pid>-<token>.txt`
  (the token is the JVM's own, since containers often run every executor as
  pid 1) under `spark.sql.codegen.varka.reproduction.directory`, by default
  `varka-reproductions` under the failing JVM's `java.io.tmpdir`, owner-only
  since a batch is the query's data. One file per shape and kind per JVM, at
  most 32 per JVM and 256 in the directory from all JVMs, and the warning
  names the file. A projection's kernel and a filter's both write one.
* **Replaying it.** `dev/varka_emit.sh --replay <file>` rebuilds the kernel from
  the file's IR and options (refusing a file whose IR does not hash to its
  shape hash), walks the failing path - verification, definition, or a run over
  the batch under the sanitizer if the file says it was on - and exits 0 when
  the failure reproduces with the same signature. It rebuilds the class rather
  than running the bytes the file carries, so a fixed emitter stops reproducing.

All of it is metadata or diagnostics: the emitted methods are byte-identical
with and without the attributes, which the JVM ignores by specification.
`VarkaDebugInfoReader` turns captured class bytes back into those strings.

### Troubleshooting

Start from the symptom. Every branch begins with the same step, which also tells you whether
Varka is involved at all: set `spark.sql.codegen.varka.enabled=false` and run the query again.
That switches off all of Varka. When Varka is involved, narrow it to one expression family: set
the family's switch, `spark.sql.codegen.varka.family.<name>.enabled=false` (Configuration lists
them and what each covers), and run again. The switch keeps that family's expressions on the
row engine and the rest of Varka on, so it is also what to leave set while a fault is reported.

```
symptom
|-- a wrong answer
|     Varka off gives the right answer?  no  -> not Varka; stop
|                                        yes -> A1 which rows, A2 what it compiled to, A3 hand over
|-- a slow query
|     Varka off is fast?                 no  -> not Varka; stop
|                                        yes -> B1 counters, B2 reasons, B3 EXPLAIN, B4 change
`-- a crash or an error
      INTERNAL_ERROR at planning        -> C1 read the context, report it
      "failed on this batch" warning    -> C2 the file, C3 replay it, C4 read the class
```

**A wrong answer.** The row engine is the oracle.

1. Run the query with Varka off and on and compare the rows; cut the query to the one expression
   and the few rows that differ. On the planted fault (a guard removed, so a far date offset
   wraps) the on run reads `[56782, 49698, 2023, null, 1970, null]` against
   `[56782, -52735, ...]` off, and `numFallbackBatchesDeclined` is 0: nothing declined, the
   kernel answered.
2. `dev/varka_emit.sh "year(date_add(d, off))" --columns d:date,off:int` prints the IR, the shape
   hash and, per emitted method, the `VectorMask` count. A guard is a mask. The default emits 4 to
   7 masks per method; with `--options guardDayProducers=false` every method shows 0.
3. The fuzzers' shrinker works on a case a fuzzer drew, not on a query: no command takes your
   query and shrinks it. A developer writes the expression and the failing rows as a case for
   `VarkaIrFuzzSuite` (`sql/catalyst`), which shrinks every failure it finds. On a planted emitter
   bug it printed `shrunk to lane=int roots=[(isNotNull col:0)] options={misdescribeWordLiveness=true}
   length=1` from a tree of three conjuncts. Hand over the expression, the
   rows and the `spark.sql.codegen.varka.*` settings you ran with. Until it is fixed, set the
   switch of the expression's family to false (Configuration): its entries run on the row engine,
   the verbose `EXPLAIN` names the switch on each, and the rest of Varka stays on.

**A slow query.** A batch Varka cannot run goes to the row engine, so slowness is declined
batches, an entry that did not fuse, or a slow kernel.

1. Read the Varka node's metrics in the SQL UI (or `metrics` on the node). `numVarkaBatches` and
   `numFallbackBatchesDeclined` count batches by path, `numVarkaRows` and `numFallbackRows` rows,
   `varkaKernelTime` the time in the kernels. On the planted query: 1 batch on the kernel, 1
   declined, 3 rows each way.
2. `numDeclinedBatches[<reason>]` says why: `day-producer = 1`, the other nine `0`. A reason can
   read "not reported" when a loop method holds guards of several reasons and reports them
   together; `spark.sql.codegen.varka.emit.exactRefusalStatus=true` (internal, off by default)
   gives each reason its own count, and its kernels are cached apart.
3. `EXPLAIN FORMATTED` shows what did not fuse at planning: `Varka [2]: [a: fused, b: residual
   (unsupported expression: length(CAST(varka_dates.i AS STRING)))]`; a filter has one line per
   conjunct. It does not show a decline at run time: the planted query reads `y: fused`, and
   step 2 is the only record.
4. What to change. A residual entry is an expression Varka does not cover (the coverage table
   lists them): rewrite it or accept the row path for it. A decline names a value the kernel is
   not defined over (`day-range`, `int-overflow`, `value-range`...): the cure is the data or the
   query, not a setting. A slow query with nothing declined is a developer's: a JFR recording
   (the `Varka` events) or the VM flags `SKILLS.md` records (`-XX:+PrintCompilation`,
   `dev/varka_c2_report.py`).

**A crash or an error.**

1. At planning the query fails with `[INTERNAL_ERROR] Varka's compiler failed while planning.`
   and a `Varka context:` block: `kind: planning`, the entries being compiled, the options that
   differ from the defaults, the cause. It is a bug in Varka: report the whole message. With
   Varka off the query runs.
2. At run time a kernel that throws does not fail the query. The batch goes to the row engine and
   the executor logs "The Varka SIMD kernels VarkaFusedProjection_<hash>.java [...] failed on
   this batch; falling back to the per-row path.", the same `Varka context:` block (`kind:
   kernel-failure`, shape hash, options, the batch's rows and nulls, the cause) and "Wrote a
   reproduction of this Varka failure (...) to <file>; replay it with dev/varka_emit.sh --replay
   <file>".
3. On a checkout, `dev/varka_emit.sh --replay <file>` rebuilds the kernel from the file's IR and
   options, runs it over the recorded batch and prints `REPRODUCED: the same signature` with exit
   0 when it fails the same way. It exits 1 when it does not (a fixed emitter, or an edited
   file) and 2 when it could not run (no such file, no report). A file can hold the query's data, so it is owner-only; `...reproduction.maxBatchBytes=0`
   keeps values out of it and `...reproduction.enabled=false` writes none.
4. To read the class, set `spark.sql.codegen.varka.classDumpDirectory=<dir>` before the run and
   `javap -c -p <dir>/VarkaFusedProjection_<hash>.class`. `javap -v` shows the `LineNumberTable`
   (line n is the n-th IR node) and the `VarkaDebugInfo` decoding key.

Who reads each thing, and what they do:

| What you see | Reader | What they do |
| :--- | :--- | :--- |
| `INTERNAL_ERROR`, `kind: planning` | user, then developer | User: set the switch the message names (the `families:` line), or turn Varka off, to unblock the query, and report the message whole. Developer: the context names the entries and options; reproduce with `dev/varka_emit.sh` on the expression |
| "failed on this batch" warning | operator | Count them per shape hash in the executor logs. The answers are right (the row engine served the batch), so it is a cost and a bug report, not an outage. Collect the file the warning names; a kernel failing on every batch is worked around by the switch on the `families:` line |
| the reproduction file | developer | Replay it; the exit status is the verdict. Handle it as data |
| `numDeclinedBatches[...]`, rows per path | user, operator | A declined batch is slow, not wrong: read the reason, change the data or the query, or turn Varka off for the query |
| a residual entry in `EXPLAIN` | user | The expression is not fused: rewrite it or accept the row path for that entry. A reason naming `spark.sql.codegen.varka.family.<name>.enabled=false` is a switch the session set |
| `java.lang.VerifyError` at planning | user, developer | Not yet an `INTERNAL_ERROR` with a context block (open work); the reproduction file is written and replays |
| a memory violation (sanitizer on, `-Dvarka.sanitizeMemory=true`) | developer | A diagnostic JVM property: the violation fails the task by design and writes a file; replay it |

`VarkaRunbookSuite` runs each branch on a planted fault and asserts the reason counts, the `EXPLAIN`
lines and the warning text quoted above, so a step that stops being true fails a build.

### When Varka falls back, and when it fails

Varka answers a batch it cannot run on the row engine, and the answer is still right. Each such
batch has a cause, the tag on the `Fallback` JFR event (`VarkaFallbackEvent`), and each cause has a
metric on the Varka node:

| Cause | When | What it leaves | What to do |
| :--- | :--- | :--- | :--- |
| `non-arrow-batch` | the batch's columns are not Arrow vectors, such as vectorized Parquet's | `numFallbackBatchesNonArrow` | nothing: a property of the source; the Arrow cache serializer is what produces Arrow batches (Limitations) |
| `range-declined` | a value lies outside the range a lowering is exact over | `numFallbackBatchesDeclined`, `numDeclinedBatches[<reason>]` | nothing, or change the data or query (Troubleshooting B2) |
| `emission-failure` | the task could not build or load the kernel class | `numEmissionFailures` (tasks), the warning "Failed to emit the Varka fused kernel ...", once per task, a reproduction file | report it with the warning and the file |
| `kernel-failure` | the kernel threw on the batch | `numFallbackBatchesKernel`, the warning "... failed on this batch", a reproduction file | report it with the warning and the file |
| `row-path-failure` | the per-row machinery beside the kernel threw | `numFallbackBatchesRowPath`, the warning "The per-row machinery beside the Varka kernel ..." | report it with the warning |

The emitter's designed decline of a shape over its method budget logs "The Varka emitter
declined ..." once per JVM and is not a failure. Every warning of the last three causes ends with
the `Varka context:` block: the kind (the cause tag), the kernel's identity (its source file, the
IR of each output, the operator and stage), the shape hash, the emit options that differ from the
defaults by name, the families whose nodes the kernel holds, each with its switch (`families:`),
the batch's rows and nulls per input where a batch was involved, and the original exception. The event's `options` field carries the same options.

**Internal errors.** An invariant Varka checks in the compiler, the IR, the emitters, the
options, the runner or the exec nodes raises Spark's `INTERNAL_ERROR` (SQLSTATE `XX000`) with the
check's own text and "This is a bug in Varka, Spark's vectorized code generator. Please report it
with this message and the full stack trace." Varka adds no error condition of its own. The
exception is a `SparkIllegalArgumentException`, `SparkIllegalStateException` or
`SparkUnsupportedOperationException`, the JDK type the check raised before, so code that catches
`IllegalArgumentException` still does. Outside it: the engine module's own checks and the check
inside an emitted kernel raise plain JDK exceptions, which the fallback catches answer. Where an internal error surfaces:

* **At planning** it fails the query. The compiler's catch puts the `Varka context:` block
  around it (`kind: planning`, the entries being compiled, the options), with the check's
  message on the `cause:` line (Troubleshooting C1). A failure inside one family's lowering also
  names that family on the `families:` line and ends its sentence with the setting that works
  around it: "To plan the query with Varka's calendar family on Spark's row engine, set
  spark.sql.codegen.varka.family.calendar.enabled to false and retry."; an argument check stays an
  `IllegalArgumentException`, any other becomes an `IllegalStateException`. Raised on the driver it arrives as it is;
  raised by an evaluator's compile in a task, it arrives inside Spark's "Job aborted" error.
* **While building or running a kernel** it does not fail the query: it is one of the fallback
  causes above, and the warning's block names it on the `cause:` line. A kernel that fails on
  every batch runs every batch on the row engine; the switch of a family on its `families:` line
  keeps that family's entries off the kernel until the failure is fixed.
* **Under test**, a fallback over a failure no test declared fails the task with
  `INTERNAL_ERROR` and the same block (`VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting`).

The one user setting with a range, `spark.sql.codegen.varka.emit.useAVX`, rejects a value out of
range with `INVALID_CONF_VALUE.REQUIREMENT`, as any Spark setting does. A
`spark.sql.codegen.varka.classDumpDirectory` that cannot be written is a warning naming the
setting, and the query runs.

### Execution integration

`VarkaColumnarRule` (a `ColumnarRule`) rewrites a Varka-eligible projection
(at least one fusable entry) over a columnar source when
`spark.sql.codegen.varka.enabled` is set.
It works in two stages, on either side of Spark's transition insertion, because
which node belongs in the plan depends on what the consumer above wants:

    // preColumnarTransitions: columnar in, columnar out
    ProjectExec(projectList, columnarChild)
      -> VarkaProjectExec(projectList, columnarChild)

    // postColumnarTransitions: a to-row transition that was inserted anyway is fused in
    ColumnarToRowExec(VarkaProjectExec(projectList, child))
      -> VarkaColumnarToRowExec(projectList, child)

A consumer that takes batches - a DSv2 write whose connector declares
`supportsColumnarWrite`, such as `noop` - therefore receives the kernels' own
Arrow batches with no transition at all, while a row consumer gets the single
fused node. The rule is registered on every `SparkSession` but is inert while
the config is off.

Since VARKA-21 the same two-stage rewrite serves filters - the engine's first
plan-shape change. An eligible predicate's compilable conjuncts fuse into a
mask kernel (one fused loop whose single output root is the condition; the
selection bitmap lands in the output-validity slot, a set bit meaning known
true - SQL's null-as-false `WHERE` rule by construction), and the remaining
conjuncts stay in a row `FilterExec` above the Varka node, which then sees
only the surviving rows:

    // preColumnarTransitions
    FilterExec(condition, columnarChild)
      -> [FilterExec(residualConjuncts,)] VarkaFilterExec(fusedConjuncts, columnarChild)

    // postColumnarTransitions
    ColumnarToRowExec(VarkaFilterExec(condition, child))
      -> VarkaFilterColumnarToRowExec(condition, child)

The two filter nodes implement the v1 selected-batch contract (the milestone's
open question 2): `VarkaFilterExec` compacts the selected rows into a fresh
dense batch - an ordinary `ColumnarBatch`, so every consumer's invariants hold
and a Varka projection stacks directly on top - while
`VarkaFilterColumnarToRowExec` consumes the selection bitmap at the row
boundary, emitting only the selected rows during row conversion with no
compaction at all. Compaction copies date/int columns Arrow-to-Arrow (keeping
them kernel-servable) and everything else through the standard row-to-column
converter; a selection vector never travels to a non-Varka consumer, because
no Spark operator understands one. Output nullability follows `FilterExec`'s
tightening rule exactly.

Both nodes run the same kernels through `VarkaKernelEvaluator`, and differ only
in what they do with its output batch and in how they fall back:
`VarkaColumnarToRowExec` converts to rows and, when the kernels cannot serve a
batch, projects the input's rows one by one; `VarkaProjectExec` passes the batch
on and has to materialise its fallback into a writable batch instead.

Per task and Arrow-supported batch:

1. Bind the projection and compile it with `VarkaExpressionCompiler` into
   fused entries (the IR), forwarded entries (bare input columns, passed
   through zero-copy) and residual entries (everything else).
2. Look the fused shape up, lazily, in the JVM-wide class cache
   (`VarkaShapeCache`, VARKA-18): a miss emits and defines the class - named
   by its shape hash - in its own `VarkaGeneratedClassLoader`, and every
   task (or session) computing the same shape reuses the loaded class, C2
   code and all, skipping the fixed per-task JIT warm-up. The literals never
   enter the shape, they travel as runtime arguments - so one class serves
   every batch of every task of the shape.
3. Guard per batch that every referenced column is an `ArrowColumnVector`
   backed by a `DateDayVector`; otherwise the batch takes the per-row path.
   While the shape's class is new and not yet compiled by C2, the batch
   takes the per-row path too, and a background thread gets the kernel
   compiled (see "A new shape's first batches" below).
4. Run the kernel: one vector loop writes every fused output into freshly
   allocated Arrow vectors. Forwarded columns are re-wrapped, not copied.
   Residual entries are evaluated per row and merged - at-row on the
   row-consumer node (the escape hatch VARKA-12 measured both ways), into a
   writable batch on the columnar one.
5. Track `numVarkaBatches`, which only counts batches where the kernels
   succeeded, and the class-cache hit/miss per task. A class's loader is
   released when the bounded cache
   (`spark.sql.codegen.varka.cache.maxEntries`, default 100) evicts it, so
   Metaspace is bounded by cache capacity; running tasks keep their instance
   until they finish. The Janino fallback projection is compiled lazily,
   only if a batch actually needs it (VARKA-15).

Neither node is `CodegenSupport`; whole-stage codegen splits at the boundary
with the columnar producer. They depend on the engine only by kernel
descriptors (strings), so a missing engine jar degrades to the fallback.

## Key design decisions

* **Java 25 baseline and a self-contained engine.** The Vector API and the
  Class-File API require a recent JDK. `sql/varka/engine` is a module of the
  Spark reactor, so a plain `./build/mvn install` builds it, but it keeps its
  own pom rather than inheriting `spark-parent` so its sources and tests can use
  the incubator-vector and native-access flags the Spark build does not set;
  catalyst uses `java.lang.classfile` on the Java 25 baseline.
* **Arrow-only fast path.** Arrow-backed batches (for example the Arrow cache
  serializer) map directly to segments. Vectorized Parquet produces
  `OnHeapColumnVector`/`OffHeapColumnVector`, not Arrow, so those batches fall
  back per batch.
* **Plan-level interception.** The rewrite happens in a `ColumnarRule` rather
  than by editing `ColumnarToRowExec` itself, and it straddles Spark's
  transition insertion: the projection becomes columnar-out before transitions,
  and a transition inserted above it is fused back in afterwards. That way the
  decision of whether rows are needed at all stays Spark's.
* **Ghost fallback and caching.** Any assembly or load failure lazily routes to
  Janino; the winning path is cached under the same key so a failed assembly is
  never retried and the job never crashes.
* **Extreme-offset oracle.** At `INT` overflow (`Int.MaxValue - 1`,
  `Int.MinValue`) the differential oracle is the plain int32 day wrap that
  `DateAdd.eval` and the kernels implement; Spark's end-to-end row engine adds a
  calendar-day rebase for out-of-range `DATE` results.
* **One value-range lattice.** The compiler's two range questions - can a
  calendar node's input leave the range the calendar lowering is exact in, and
  can a checked int operation overflow - are queries on one analysis
  (`VarkaRangeAnalysis` over `VarkaValueRange`), asked with the kind the
  parent's slot gives a node and a guard policy that says whether a calendar
  consumer above has armed the runtime guards; every operation on the lattice
  saturates to unknown rather than wrapping, so no caller can prove a check
  away by overflowing.
* **No unused configuration.** Every `spark.sql.codegen.varka.*` entry must be
  consumed. Today `enabled`, `classDumpDirectory`, `cache.maxEntries`,
  `compilationWatch.enabled`, `emit.useAVX` and `warmup.enabled` exist, and
  each is read on the execution path that documents it.

## Module and file layout

| Location | Responsibility |
| :--- | :--- |
| `sql/varka/engine` | Standalone Java 25 module (`varka-engine`, Arrow 19.0.0): `VarkaMorsel`, `DateVectorOps`, `VarkaClassLoader` and their tests. |
| `sql/catalyst` | The vector IR, loop emitter, emit options, shape cache and telemetry attribute under `codegen/varka/`, all Java; `VarkaGeneratedClassLoader`, `VarkaExpressionCompiler` and the compiler families it chains, also Java; `DateVarkaSupport`'s day-offset folding; the Varka configs. |
| `sql/core` | `VarkaColumnarRule`, `VarkaColumnarToRowExec`, end-to-end test suites and benchmarks. |
| `sql/varka` | `VISION.md`, `ADDING_AN_EXPRESSION.md` (the contributor path for a new expression), `Varka_MVP.md`, and `plans/` with the milestone plans (`m1/PLAN.md` is the MVP) and per-task plans. |

## Configuration

All Varka configurations are internal. The `family.<name>.enabled` switches, in their own table
after the others, are the compiler's expression families (`VarkaFamily`): the families of the
chain `VarkaNodeCompiler` dispatches every node through, and the predicate family, which lowers
the comparisons and connectives of a filter and of a conditional's condition. A family that is
off declines every node it claims, with the reason
`Varka's <family> family is switched off (<switch>=false)` in the verbose `EXPLAIN`, and the entry
holding that node runs on the row engine while the rest of the projection or filter still fuses.
Each node is under exactly one switch, wherever it sits: a comparison inside a `CASE WHEN`
branch's condition is the predicate family's, so with that switch off the `CASE WHEN` declines
too, and the `CASE WHEN` itself is the condition family's. A column or literal is never declined
by a switch, so a `bigint` comparison, or a `TIME(3)` column relabelled to `TIME(6)` for one,
still fuses with the time family off. A node of one family that another's lowering builds
directly, such as the negated interval in `d - ym`, asks its own family's switch; helper code two
families share, such as the overflow decision or the pick fold `greatest` borrows, runs under the
switch of the node it serves. The date leaves (a date column, a date literal, the identity date
cast) have no switch. The coverage table's *Switches* column names each row's.

| Config | Default | Description |
| :--- | :--- | :--- |
| `spark.sql.codegen.varka.enabled` | `false` | When true, an eligible projection (at least one fusable entry) over Arrow `DateDayVector` columns runs the fused SIMD kernel instead of per-row codegen - as `VarkaProjectExec` where the consumer takes batches, and as `VarkaColumnarToRowExec` where it wants rows; ineligible entries run the row path per row and merge, and non-Arrow batches fall back entirely. |
| `spark.sql.codegen.varka.classDumpDirectory` | (none) | Diagnostics (VARKA-16). When set, every emitted kernel class is written to this directory under its `SourceFile` name, for `javap`. A failed write is logged and never fails the query; every task of a shape holds identical bytes and overwrites one file. |
| `spark.sql.codegen.varka.reproduction.enabled` | `true` | VARKA-315. A kernel class that fails to verify or define, an exception inside a kernel, or a memory-sanitizer violation writes one reproduction file, which `dev/varka_emit.sh --replay` runs again outside Spark (Telemetry and debuggability). The failure is handled as without it. |
| `spark.sql.codegen.varka.reproduction.directory` | (none) | Where reproduction files go on the failing JVM; unset, `varka-reproductions` under its `java.io.tmpdir`. Created owner-only, since a file can hold a batch of the query's data. |
| `spark.sql.codegen.varka.reproduction.maxBatchBytes` | `16m` | The most bytes of a failing batch's values a reproduction file holds; a larger batch keeps its row count, null counts and buffer sizes, and a replay runs a synthetic batch of that shape. `0` never writes values. |
| `spark.sql.codegen.varka.emit.useAVX` | `-1` | The `-XX:UseAVX` level the emitter lowers for where a lowering depends on it (VARKA-121): `3` or above takes a 64-bit division through double lanes, `2` the magic-number form, because the converts do not become instructions below AVX-512; `-1` leaves the emitter's own default, which is deliberately not the host's level so the shape hashes and the committed bytes describe no one machine. The level is part of the shape hash, so a session that sets it emits and caches its own classes. For benchmarks and A/Bs. |
| `spark.sql.codegen.varka.cache.maxEntries` | `100` | Static (VARKA-18). Capacity of the JVM-wide cache of loaded fused-kernel classes, keyed on the kernel's structural shape; the least recently used class is released on eviction, bounding Metaspace by this size. `0` restores the per-task emit-and-unload lifecycle. |
| `spark.sql.codegen.varka.warmup.enabled` | `true` | VARKA-212. When true, a newly emitted kernel serves no batch until C2 has compiled it: the shape's batches take the per-row path while a background thread runs the kernel on a copy of the first batch, and every task of the shape switches to the kernel once it runs without allocating. When false, a new kernel serves batches from the first and runs interpreted until enough batches have gone through it. |

The family switches (VARKA-318), generated from `VarkaFamily` by `VarkaCoverageSuite` with the
coverage table, and regenerated by the same command:

<!-- BEGIN generated family switches -->
| Config | Default | Covers |
| :--- | :--- | :--- |
| `spark.sql.codegen.varka.family.calendar.enabled` | `true` | The calendar family: day arithmetic (date_add, date_sub, datediff, unix_date, date_from_unix_date), the calendar fields (year to weekofyear), make_date, last_day, next_day, trunc, add_months and a date plus a year-month interval. |
| `spark.sql.codegen.varka.family.interval.enabled` | `true` | The interval family: year-month interval arithmetic, negation and abs, make_ym_interval, the year and month extractions, the multiply by an int and the casts between year-month intervals and ints. |
| `spark.sql.codegen.varka.family.time.enabled` | `true` | The time family: the TIME functions (hour, minute, second, time_trunc, time_diff, the conversions to and from micros and millis, a time plus a day-time interval) and the casts to TIME and to a day-time interval. |
| `spark.sql.codegen.varka.family.predicate.enabled` | `true` | The predicate family: the comparisons, IN over dates and year-month intervals, IS NULL and IS NOT NULL, AND, OR and NOT, wherever they sit: a filter's predicates, so that no filter fuses, and the conditions of IF and CASE WHEN. |
| `spark.sql.codegen.varka.family.condition.enabled` | `true` | The condition family: the conditionals IF, CASE WHEN and coalesce (the comparisons in their conditions are the predicate family's). |
| `spark.sql.codegen.varka.family.intArithmetic.enabled` | `true` | The int arithmetic family: +, -, * and unary minus over int operands, checked under ANSI, greatest and least, and the casts between int and bigint. |
<!-- END generated family switches -->

The rule is registered on every `SparkSession` but does nothing while the
config is off, so enabling the config is all that is needed:

```scala
val spark = SparkSession.builder()
  .appName("app")
  .config("spark.sql.codegen.varka.enabled", "true")
  // Arrow cache is the recommended production source of DateDayVector batches.
  .config("spark.sql.cache.serializer",
    "org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer")
  .getOrCreate()
```

The Arrow fast path reads cached batches through the in-memory columnar
vectorized reader (`spark.sql.inMemoryColumnarStorage.enableVectorizedReader`,
`true` by default), so caching with the Arrow serializer produces
`ArrowColumnVector` `DateDayVector` batches. Without an Arrow-backed source
Varka uses the row engine for every batch and results stay correct; where a
setting would give it batches - the vectorized reader off, another cache
serializer, or a query reading more than `spark.sql.codegen.maxFields` columns -
it logs the reason at INFO. With Varka on, an Arrow cache counts that limit over
the columns a query reads rather than the whole table.

The VISION draft also describes `spark.sql.codegen.varka.patch.threshold` and
`spark.sql.codegen.varka.fallback.ghost.enabled`. They are design intentions,
not configuration entries in this MVP: per the project rule ("no unused
config"), they will be added to `SQLConf` only when the code paths they gate
exist.

## Testing and benchmarks

* **Engine differential tests** cross-check every kernel against Arrow's own
  vector accessors, including null patterns, empty batches and offsets near
  `Integer.MAX_VALUE`.
* **Catalyst tests** check bytecode shape by disassembly, the loader
  define/release lifecycle, and ghost-fallback injection.
  They are ScalaTest suites or, for a suite whose helpers are Java,
  JUnit 5 classes under `@VarkaSuite`; both run under the same option
  matrix, skip list and per-test watchdog, and a JUnit test is printed and
  matched by its `@DisplayName` sentence as a ScalaTest test is by its name.
* **Random kernels, in the code users run** (`VarkaIrFuzzSuite`,
  `VarkaTierWitnessSuite`): the IR fuzzer compares random trees with a reference
  evaluator row by row, each kernel once in the interpreter and the first 20
  draws a lane again over eight batch histories - warmed until C2 has compiled
  every loop, warmed on calls that never leave a remainder or a null column and
  then given one (a trap back to the interpreter), recompiled after that trap,
  and run cold with C1 on through on-stack replacements at both tiers. A JFR
  stream and the code cache's method list say which JIT tier served each
  compared call (VARKA-274).
* **sql/core tests** (`VarkaColumnarToRowExecSuite`, `VarkaProjectExecSuite`,
  `VarkaFilterExecSuite`, `VarkaColumnarWriteSuite`, `VarkaEndToEndSuite`,
  `VarkaDifferentialSuite`, `VarkaCoverageDifferentialSuite`,
  `VarkaAutoRegistrationSuite`) prove plan fusion,
  `checkAnswer` equality over a query matrix - re-run warm so a cache hit is
  differentially checked too - `numVarkaBatches > 0` on fused plans,
  Metaspace bounds, and config-driven activation.
* **Four stronger forms over every coverage row.** The kernel checks
  poison every null lane, put a fixed batch with a poisoned lane in the loop
  and the epilogue in front of each row's masked body, and run every
  compared batch with the output validity pre-filled with zeros, as
  production's Arrow buffers are, and with ones, so a kernel that fails to
  set a bit fails as surely as one that fails to clear it.
  `VarkaCoverageDifferentialSuite` checks each predicate row's three
  partitions: `WHERE p`, `WHERE NOT (p)` and `WHERE (p) IS NULL` on Varka are
  each Spark's, compared as rows, share no row and leave none out, under
  the default optimizer and with `BooleanSimplification`,
  `NullDownPropagation` and `InferFiltersFromConstraints` excluded so
  `NOT (p)` and `(p) IS NULL` reach Varka as written; the two null tests also
  run as the condition of a CASE over every predicate row. And under ANSI, on a fixture of the
  types' extremes, each row runs alone and where Spark skips it - the
  untaken arm of a CASE, a later COALESCE argument, the right side of a false
  AND or a true OR: both engines raise the same error, compared by condition,
  SQLSTATE and message parameters, or both answer the same rows. A skipped
  position must be served by a kernel or be on the suite's declined list
  with its reason. And each row that names a domain runs over a fixture that
  straddles every guard's bound, cut as TLP cuts a table into the rows inside
  the domain, outside it and where it is unknown, as a projection and as a
  filter: inside and under `IS NULL` the kernels serve every batch and nothing
  declines; outside, every batch declines with exactly the status
  `VarkaGuardSpec`'s verdict calls for, read off the JFR fallback event, and
  the answer is Spark's, rows or the same error (VARKA-279).
* **Spark's own date tests** (`VarkaGoldenCorpusSuite`) run the `select`
  statements of the golden-file inputs (`date.sql`, `interval.sql`,
  `extract.sql`, the timestamp and datetime files) on both engines, each
  literal of a type Varka reads turned into a column of an Arrow-cached
  fixture, as a projection and as a filter. `sql/varka/golden_corpus.json`
  records every statement's rewrite, plan verdict and outcome, and the counts
  of what fuses per file.
* **Spark's whole golden-file suite** (`VarkaSQLQueryTestSuite`,
  `dev/varka_golden_suite.sh`) runs every `SQLQueryTestSuite` file with
  Varka on, Spark's committed `.sql.out` files the oracle. A test-only
  extension puts an Arrow-cached copy under every relation with a column
  Varka reads, except under a command or a hidden metadata column, so the
  queries reach the kernels, and lifts a `SELECT` with no `FROM` into a
  relation of seventeen equal rows where the compiler fuses its literals as
  columns; a second arm runs the same source with Varka off,
  so a failure in both is the harness's and one only with Varka on is
  Varka's. `sql/varka/spark_suites/excluded.tsv` says what the cache itself
  changes: the files whose `EXPLAIN` output prints plans, which then compare
  every other statement, and the tests that may fail and why; an entry that
  no longer applies fails as stale. `served.json` records per test how many
  query executions Varka served a batch in, and a test that served one when
  it was written must still serve one. It needs a JVM of its own, since it
  fixes the process-wide cache serializer, never writes golden files, and
  runs in `dev/varka_nightly.sh`.
* **Metaspace proof** (`VarkaGeneratedClassLoaderSuite`,
  `VarkaShapeCacheSuite`) verifies with weak references that a released
  loader is collected, that a batch of 1000 loaders is fully collected, and -
  since VARKA-18 - that a 10k-distinct-shape stress stays at cache capacity
  with every evicted loader collected.

Benchmark highlights from the committed runs - the throughput file from
VARKA-19's run, which extended the row-consumer matrix with heavy-op twins;
cold start and class generation from VARKA-18 (AMD Ryzen AI 9 HX PRO 370,
JDK 25, Linux, machine otherwise idle; every number below is the best of at
least five two-second-windowed iterations and lives in the committed results
files, which are the source of truth as the code moves):

* **End-to-end columnar throughput** over 2M Arrow-cached rows
  (`VarkaThroughputBenchmark`): 3.6-6.0x Janino for single ops and small
  trees (`date_add` 3.6x, `datediff` 4.8x, the nested
  `datediff(date_add(d, 1), d2)` 5.6x, the two-output shared subchain 6.0x),
  2.5x for a mixed projection where only one entry fuses. Before the class
  cache these read 1.7-2.3x: the per-task JIT warm-up was most of the gap
  between the buffer-level kernels and the end-to-end numbers.
* **`CASE WHEN` by mask blend**: 7.0x on data where the condition flips
  pseudo-randomly, 5.8x where it is perfectly predictable. The varka side
  costs the same on both within a millisecond (branch-free execution is
  data-oblivious, 8-9 ms best in the committed cases); the gap is Janino's
  branch misprediction on the unpredictable data.
* **Chain depth** (alternating `date_add`/`date_sub`, columnar consumer):
  6.4-6.9x, *flat* from depth 1 to depth 8. VARKA-14 committed this curve as
  2.2x eroding to 1.3x and diagnosed the erosion as the fixed per-task JIT
  warm-up that grew with the loop method's op count (`VARKA-14.md` 7.5);
  VARKA-18's cross-task class cache removed exactly that term - every task
  now runs the C2-compiled loop from its first row - and the end-to-end
  curve became what the buffer-level numbers always predicted (depth 8
  within 10% of depth 1).
* **The row-consumer cost, stated plainly**: assemble-then-read costs a
  flat ~25 ns/row on an all-fused single-output projection, whatever the
  fused work - VARKA-19's extended matrix pinned the floor (the ~16 ns/row
  previously quoted was contaminated by the pre-cache JIT warm-up). Fusion
  through rows wins exactly where Janino's own per-row cost exceeds that
  floor: `dayofweek` 1.2x and unpredictable `CASE WHEN` 1.1x, against the
  cheap chains at 0.8x (Janino ~20 ns/row) and residual-heavy at 0.6x.
  There is no break-even depth, and no plan-time cost gate separates the
  winners from the losers - an 8-op chain loses while the ~6-op `CASE WHEN`
  wins, because the differencer is Janino's cost, not Varka's - so the rule
  keeps fusing row consumers: VARKA-19's recorded decision.
* **`dayofweek`**: 8.8x - the largest committed
  win, and the shape that pays even through a row consumer (1.2x there). This case shipped as the honest loss of the original VARKA-14 run
  (0.9x, the magic-multiply lowering of 7.7 took it to 1.2x): its ~12-op
  loop method paid the heaviest per-task warm-up (~50 ms), so removing the
  ladder moved it furthest.
* **Filters** (`VarkaFilterBenchmark`, VARKA-21 and VARKA-24, `d < DATE` over 2M
  rows at a 0-100% selectivity ladder): the compacting `VarkaFilterExec` wins
  2.5x at 0% selected, rising to 5.4x at 100%. The rise is VARKA-24's
  `compress(mask)` compaction and it is the interesting part: the typed
  scalar copy it replaced was one Arrow call per *selected row*, so its cost
  grew across the ladder (5.4 ns/row at 15% selected to 8.6 at 100%), while
  `IntVector.compress` is one instruction per lane group whatever the group
  holds and stays flat at 3.8-4.2 ns/row. Compaction has stopped being a
  function of selectivity, which is why there is still no compaction
  threshold - there is now less reason for one than when VARKA-21 declined it.
  The mask-skip row node wins 2.3x at low selectivity, decaying to 1.1x at
  all-selected as the task-19 read-back floor takes over. The stacked
  filter-then-fused-projection shape holds 3.5x-4.7x, and the
  previously-parity `WHERE d IN (5 literals)` anchor runs 2.0x. The honest
  loss is unchanged: `COUNT(*)` over an 85%-selective filter is 0.8x (1.8x at
  15%) - nearly every row crosses the row boundary into the aggregate.
* **Cold start** (`VarkaColdStartBenchmark`, a fresh plan shape of 16 entries
  over 100K rows, on the milestone 7 build): the first run takes the row path
  while the warm-up compiles the kernel, 137 ms against stock's 119 ms; once
  the kernel is in, 1.6x - 31 ms vs 49 ms best, 40 ms vs 54 ms average - and
  the warm-up's verdict comes in about two seconds. A fresh shape misses the
  class cache, and the benchmark enforces that by invalidating the cache
  before each timed iteration - its column-and-literal freshness is invisible
  to the structural shape key. The Varka side pays emission plus the class
  define here; only repeated shapes get the cache's win.
* **Class generation in isolation** (`VarkaCodegenBenchmark`): emitting,
  defining, loading and instantiating a fused two-output kernel takes
  ~99 us against ~6.5 ms for one Janino projection compile - 66x. (The
  milestone-1 single-op dispatcher case that used to sit beside it, at
  ~420x, went with the dispatchers in VARKA-17.)

## Deployment and requirements

* JDK 25 with the incubator Vector API module:
  `--add-modules jdk.incubator.vector`
  `--enable-native-access=ALL-UNNAMED`
* The engine jar in the repo is a test-scoped dependency; at runtime supply it
  with `--jars` (its absence only falls back to per-row execution).
* Arrow `DateDayVector` and `IntVector` buffers come from Arrow-backed
  producers; the Arrow cache serializer is the recommended source.

## Limitations

The real current edges, stated with their numbers where they have one:

* **Int32 lanes for every expression SQL can reach.** The IR carries a lane
  type per node and the emitter serves two - 32-bit and 64-bit - but no
  compiler arm admits a `bigint` column yet, so every expression a query can
  fuse today is `INT`-shaped (`DateType` days or integer results). The 64-bit
  lane exists below that line: it emits, verifies and computes against a
  reference for the arithmetic, the comparisons and the conditional, which is
  what makes the types built on it - `bigint`, `TIME`, day-time intervals - a
  compiler question rather than an engine one. No `CalendarInterval`, strings,
  decimals, timestamps or nested types, and a day offset must be a foldable
  integer literal or an `IntegerType` column - `ShortType`/`ByteType` offset
  columns decline (VARKA-38).
* **A SIMD lane cannot throw row-accurately**, which shapes how ANSI overflow
  works rather than excluding it. VARKA-63 fuses integer arithmetic over a
  `datediff` result and over the calendar fields; where the operands' ranges do
  not rule overflow out, the kernel marks the overflowing lanes and declines the
  batch, and the row engine recomputes it and raises. So the error is Spark's
  own, from the engine that can attribute it to a row - at the cost of one
  fallback for the batch that contains it.
* **The row-consumer read-back can cost more than fusion saves**: cheap
  chains commit at 0.8x and residual-heavy at 0.6x, while heavy shapes win
  through rows (`dayofweek` 1.2x, `CASE WHEN` 1.1x) - the ~25 ns/row
  assemble-then-read floor decides which (`VarkaThroughputBenchmark`).
  VARKA-19 measured both sides and recorded the acceptance: no decline rule,
  because no plan-time number separates the shapes and VARKA-21's filters
  keep more output columnar.
* **Vectorized Parquet falls back**: `OnHeap`/`OffHeapColumnVector` batches
  are not Arrow. The Arrow cache serializer is the production source of
  eligible batches.
* **No whole-stage codegen integration.** The Varka nodes are not
  `CodegenSupport`; whole-stage codegen splits at the boundary.
* **Emitter caps**: chain depth 16, 64 distinct ops, 64 input columns per
  kernel, and 16 literals per fused `IN` list. Since VARKA-20 the compiler
  mirrors the depth and op budgets and demotes an overflowing entry to
  residual with a recorded reason, instead of the whole kernel silently
  falling back per batch at emission. A capped `IN` still lands in one
  loop method (the emitter never splits inside an output): 33 vector ops
  in the benchmarked cap shape - 16 EQ + 15 OR + the blend + the branch
  arithmetic, about twice the per-method `GROUP_BUDGET` - so a fresh IN
  shape's first execution pays a one-time C2 compile of roughly 1 ms per
  vector op; the class cache amortizes it across every later task of that
  shape, and the exception is registered with the `GROUP_BUDGET` rule in
  `sql/varka/AGENTS.md`.

## Building, testing and running benchmarks

```bash
./build/mvn -f sql/varka/engine/pom.xml install
build/sbt "sql/testOnly org.apache.spark.sql.execution.VarkaDifferentialSuite"
# Engine JMH kernels (in-process, gated):
./build/mvn -f sql/varka/engine/pom.xml test -Dvarka.jmh=true
# Spark benchmarks (SPARK_GENERATE_BENCHMARK_FILES=1 to regenerate the committed files):
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaThroughputBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaColdStartBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaCodegenBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaInExpressionBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaFilterBenchmark"
build/sbt "catalyst/test:runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
build/sbt "catalyst/test:runMain org.apache.spark.sql.VarkaArithmeticBenchmark"
```
## Development and benchmarking tools

Two dozen scripts under `dev/` support this engine. They exist because Varka's
correctness and its performance claims are both checked mechanically rather
than by review, and most of them encode a lesson learned the expensive way.
Every one answers `--help` with its own usage; what follows is what each is
*for*, so you can tell which one you want.

### Everyday checks

| Tool | What it is for |
| :--- | :--- |
| `varka_precommit.sh` | The house rules that slip most often - a non-ASCII byte outside a string, a source line over 100 columns, a `TODO` marker under a Varka directory, the quote check when a document changed, `SKILLS.md`'s generated index, `ruff` on Python - over the files about to be committed. Install once with `--install-hook`. |
| `varka_gate.sh` | The full standing gate in one command: everything a task plan's "Verification" section lists, in order, each step logged. Run before proposing a change. |
| `varka_toc.py` | Regenerates `SKILLS.md`, the index over the lesson files under `sql/varka/skills/`, from their headings; `--check` reports a stale one and the pre-commit hook runs it whenever either side changes. Without `--from` it lists a single document's own headings instead. |
| `varka_errorprone.sh` | Error Prone and NullAway over Varka's Java, in their own javac pass apart from the build, `-Werror` (VARKA-266). NullAway checks every Varka class but those listed in `dev/varka_nullaway_pending.txt`, a list the script lets only shrink; every `@SuppressWarnings` must say why on its line or the line above. The gate's lint step runs it; the jars are pinned by sha256 and fetched into `build/` on first use. |
| `varka_quote_check.py` | Does every performance number quoted in the plans, the lesson files, the docs and the README trace to a committed results file? Numbers that predate the tool live in `varka_quote_allowlist.txt` with a reason each, and that list only shrinks. |
| `varka_worktree.sh` | Lists the task worktrees this repository accumulates and removes the merged ones (`list`, `gc`). |
| `varka_pr_sweep.sh` | Dry-merges every open pull request against master, and every pair of open pull requests against each other, without touching the working tree - so a conflict between two in-flight branches is found before one of them merges. |
| `varka_task_new.sh` | Starts a task the way they all start: worktree and branch off master, a plan file from the template, the pre-commit hook installed. |
| `check_varka_arrow_version.py` | Guards that the engine module's Arrow version still matches the one Spark ships, since the morsel maps Arrow's buffers directly. |

### Understanding what the compiler did

| Tool | What it is for |
| :--- | :--- |
| `varka_emit.sh` | The main introspection tool. Give it SQL expressions and it prints what they compile to - the IR, the shape hash, and per emitted method the bytecode size and vector-op counts. `--asm` adds C2's assembly for the dense loop, `--table` prints the op-count table a plan registers, `--options k=v` selects an emitter variant, `--repeat N` re-emits the shape N times and reports the median, a loop a profiler can sample. Start here when asking "why did this not fuse" or "how expensive is this shape". |
| `varka_jfr_frames.py` | Where the time goes inside one code path: reads a JFR recording's execution samples (`jfr print --events jdk.ExecutionSample --stack-depth 64`) and attributes each to the emitter phase whose frame the stack holds and to the nearest Varka frame to the top, so the JDK's work on Varka's behalf is charged to the method that asked for it. VARKA-191's admission check is its first use. |
| `varka_hsdis_build.sh` | Builds `hsdis`, the disassembler plugin `-XX:+PrintAssembly` needs and no JDK ships, in seconds from the JDK's own source and the distribution's capstone library. Needed by `varka_emit.sh --asm`. |
| `varka_trap_census.py` | Splits HotSpot's `LogCompilation` output into the two different things called `uncommon_trap` and reports per-method deoptimisation and compile counts - for when a kernel is fast in isolation and slow in a query. |
| `varka_c2_report.py` | What the JIT made of one class's methods, from `-XX:+LogCompilation`: per compilation, which compiler made it or failed, the bytes of callees inlined, the intrinsics applied and the calls left in the graph, the allocations and boxes escape analysis eliminated, and the inlines refused with their reasons; then a per-method summary with the traps and discards that hit its C2 code - for when the same generated code allocates on one path and not on another (`VARKA-228.md` 7), or is compiled again and again (`VARKA-209.md` 9). |
| `varka_inlining_cliff.sh`, `varka_inlining_cliff.py` | VARKA-209's census: fresh JVMs of `VarkaInliningCliffProbe`, the cheap-tail kernel at a given output count and fused ceiling, under `-XX:+LogCompilation` and `PrintInlining`/`PrintIntrinsics` scoped to the emitted class; the reader gives every fork a fast-or-slow verdict, the second it settled, and what C1 and C2 made of its loop method, and splits C2's words by verdict - for the cliff a loop method meets past C1's limit (`VARKA-209.md` 9). |
| `varka_word_census.sh` | Reports what validity word each IR value root produces, over the whole corpus - the audit behind the null-handling algebra. |
| `varka_deopt_cycle.sh` | Does a kernel's dense loop compile once, or enter the C2 deoptimization cycle (VARKA-189)? Forks fresh JVMs of the `make_date` ladder's kernel at a width and in an epilogue form under `-XX:+PrintCompilation` and `-Xlog:deoptimization=debug`, since the cycle is decided at a method's first C2 compile and then stable for the JVM's life, so forks are the sample. |
| `varka_deopt_cycle.py` | Reads those logs and gives the verdict per fork from the JVM's own words - a loop method made not entrant three or more times and trapped at `profile_predicate` more than four times - never from a rate. With `--fail-on-cycle` it exits non-zero when any fork cycles, fails to finish, or no fork is read; `VarkaDeoptCycleSuite` holds the rule against recorded logs. |

### Refactoring and porting

A change that moves code between files, or ports a file from Scala to Java, starts from an
inventory and ends with imports to clean up. These three do both from the source and the
build's own reports, so the plan names members that exist.

| Tool | What it is for |
| :--- | :--- |
| `varka_members.py` | The member map: every member of each top-level type in the files given, with its line range, doc comment's first sentence, and, with `--callees`, which other listed members it calls. The inventory a refactor's plan lists, generated rather than written from memory. |
| `varka_callgraph.py` | The calls that cross between named groups of members (`--group NAME=REGEX`), or between files (`--by-file`): before a split, the seam with the fewest crossings is the one to cut; after it, what still crosses. |
| `varka_unused_imports.py` | Reads a build log's unused-import reports - scalac's, which Spark's build makes errors, and checkstyle's from `dev/lint-java` - and removes every one in a pass, down to the one selector of a brace import. A moved file inherits all its source's imports, and the build otherwise reports them one per multi-minute run. |

### Measuring, and not fooling yourself

The benchmark tooling is built around one observation: the same code has
measured its memory-bound kernels 20-27% apart on different days with every
compute-bound row flat. So the machine's state is checked before a number is
believed, and a number is compared against a committed baseline rather than
remembered.

| Tool | What it is for |
| :--- | :--- |
| `varka_bench_canary.sh` | Is this machine in the state the committed files were measured in? Runs three fixed loops - a compute-bound control, a cache-resident vector add, a memory-bound one - and compares them against this host's committed baseline. The regeneration script runs it first and stops if the machine has drifted. |
| `varka_bench_regen.sh` | Regenerates one benchmark's committed results at both vector widths, with a provenance sidecar, and ends by diffing against what was committed. The normal way to update a results file. |
| `varka_bench_diff.py` | Reads that diff on its own: before/after between two files or against a git revision, or A/B pairs inside one file. Scalar control rows are listed first, because if they moved the machine moved and nothing else in the file can be read. `--requote` turns a diff into the list of document lines still quoting the old numbers. |
| `varka_bench_gate.py` | Asserts the invariants a results file must satisfy whatever its numbers are - no duplicate case ids, no missing provenance, no impossible rate. |
| `varka_bench_band.py` | How far apart do repeated runs of one benchmark land, per case? `--split-half` asks whether that spread itself reproduces. Use it before believing a small win. |
| `varka_bench_repeat.sh` | Runs one benchmark N times so `varka_bench_band.py` has something to read. |
| `varka_bench_ids.sh` | Which benchmark case ids are taken and which is the next free one - the ids must be unique per file and picking one by eye gets it wrong. |
| `varka_nightly.sh` | The checks that need volume or an idle machine, into a dated log: the canary, the IR fuzzer at ten thousand iterations, the exhaustive sweeps, every small IR tree to size 5, the deoptimization-cycle guard (ten forks per width of the default twelve-output kernel), optionally the full gate. |
| `varka_series.sh` | Every IR tree the fuzzer's grammar could draw up to a size, counted in grammar productions, through the emitter against the reference evaluator, one JVM per core (VARKA-269): size 5 is 3.76 million trees at both lanes, about twelve minutes on the laptop; the gate runs size 3, and size 4 runs at night: at the narrow width in the nightly fuzz job and under every emitter configuration in the weekly option matrix. `--variants` runs each tree again in eight variants whose answers follow from its own - beside a child or an unrelated tree, duplicated, copied, its literals in columns, its columns renumbered, an untaken arm replaced (VARKA-278) - size 3 in the gate and size 4 at night. |

### The cross-distribution surface benchmark

These four run the public comparison against stock Apache Spark - the numbers
in the README - and are described from a user's side in that file's
"Reproducing it" section.

| Tool | What it is for |
| :--- | :--- |
| `varka_bench_surface.sh` | Runs the date surface, or the chained expressions, against several Spark distributions one after another and prints the comparison. Refuses a busy machine, records the datapath probe and host in every file, and fails a run whose cached table did not stay in memory or whose per-job fixed cost exceeded 5% of a Varka row's wall time. |
| `varka_datapath.sh` | Describes the machine and measures its vector datapath, exiting non-zero if it is not what the caller asked for. It reports *lanes per nanosecond* at three widths, because "has AVX-512" does not tell you whether 512-bit operations issue at full rate - some server parts read 1.33x where a full-width one reads 2x. Used as the gate on CI benchmark jobs and runnable on a laptop to see what it has. |
| `varka_surface_shards.py` | Runs one sharded surface across CI runners and re-dispatches the shards that miss, for when the whole list does not fit one job's time limit. |
| `varka_bench_merge.py` | Joins those shard files back into one file per distribution. |
| `varka_post_page.py` | Renders a post's Markdown and the SVGs its figure scripts produce into the standalone page that gets published, inlining each figure so the page carries its own drawings and fonts. |
