# VARKA-243: The specification fragment

## 1. Where this came from

Row 243 of `m7/PLAN.md`, item 59 step 1 of `m10/SCOPE.md`: "one definition per operation the
standard fixes, over the coverage table's operations first, with every interpretation choice
recorded where the text leaves one (the calendar before 1582, leap seconds,
implementation-defined precisions)", in `sql/varka/proofs/spec/`. `m7/READING.md` 11 and
`m7/PLAN.md` 2.1 add one requirement from the Alive and Granlund-Montgomery readings: each
operation says whether a division in it truncates or floors, since Java's `/` truncates,
`Math.floorDiv` floors, and SMT-LIB's `div` is Euclidean, which floors for a positive divisor; a
refinement proof that confuses them proves the wrong thing.

Item 59's reason is that item 58's proofs show a lowering equal to "the Java formula", which is
only as trustworthy as its transcription of Spark. A definition someone outside the project wrote
down is a better thing to prove against, and the same fragment is what the standard mode of
`SCOPE_STANDARD_MODE.md` would be implemented against. Row 244 then names Spark's departures from
it as deltas, each with a test, and row 245 restates the proofs as refinements of it.

## 2. The admission check, done

Done on 14 October 2026, before any writing.

### 2.1 What the repo holds of the standard: none of its text

Nothing under the repository, the papers folder or the owner's `~/Downloads/Milestone7` holds
ISO/IEC 9075 in any edition; the record cites it by name only (`m10/SCOPE.md` item 59 names
"ISO 9075-2" and three SQLSTATEs, `SCOPE_STANDARD_MODE.md` 3.1 the default leading field
precision). The current edition, ISO/IEC 9075-2:2023, is sold by ISO and was not to hand.

The one edition whose text could be read is SQL-92: the document "Information Technology -
Database Language SQL (Proposed revised text of DIS 9075)", July 1992, X3H2-92-154/DBL CBR-002,
as kept at `http://www.contrib.andrew.cmu.edu/~shadow/sql/sql1992.txt` (1,637,146 bytes, SHA-256
`4817caeff431bec4cb49589384b645aa5742a56a280cba01c321d98b4ff11f95`, fetched 14 October 2026). It
is the text ISO/IEC 9075:1992 was published from, not the published standard itself. A fetch of
the SQL:2003 working draft once mirrored by Wiscorp returned an HTML page instead of the archive.

So the fragment cites SQL-92 clauses, quoting each passage a definition rests on, and says once
and prominently that clause numbers moved in later editions and that the current edition's
clause for every definition is owed. Nothing in it carries a clause number that was not read in
that text. The text is not committed: it is 1.6 MB, its licence is not stated, and the quotes the
fragment needs are short; whether a transcription belongs under `sql/varka/papers/` is the owner's
call.

### 2.2 The coverage table, counted

`sql/varka/coverage.json` has 92 rows over 60 Catalyst classes. Grouped into SQL operations, the
SQL-92 text fixes **19** of them:

| | operation | Catalyst classes | SQL-92 clause |
| :--- | :--- | :--- | :--- |
| 1 | exact numeric `+`, `-`, `*`, monadic minus | `Add`, `Subtract`, `Multiply`, `UnaryMinus` | 6.12 |
| 2 | comparison of exact numerics | `EqualTo` ... `GreaterThanOrEqual` | 8.2 |
| 3 | comparison of datetimes (date, time) | the same | 8.2 |
| 4 | comparison of intervals | the same | 8.2 |
| 5 | `IS NULL`, `IS NOT NULL` | `IsNull`, `IsNotNull` | 8.6 |
| 6 | `NOT`, `AND`, `OR` | `Not`, `And`, `Or` | 8.12 |
| 7 | `IN` over a value list | `In`, `InSet` | 8.4, 8.7 |
| 8 | searched `CASE` | `CaseWhen`, `If` | 6.9 |
| 9 | `COALESCE` | `Coalesce` | 6.9 |
| 10 | `EXTRACT` of YEAR, MONTH, DAY from a date | `Year`, `Month`, `DayOfMonth` | 6.6 |
| 11 | `EXTRACT` of HOUR, MINUTE, SECOND from a time | `HoursOfTime`, `MinutesOfTime`, `SecondsOfTime` | 6.6 |
| 12 | `EXTRACT` of a field from an interval | `ExtractANSIIntervalYears`, `ExtractANSIIntervalDays` | 6.6 |
| 13 | date plus or minus a year-month interval | `DateAddYMInterval`, `DatetimeSub` | 6.14 |
| 14 | date plus a day interval | `DateAdd` over `ExtractANSIIntervalDays` | 6.14 |
| 15 | time plus a day-time interval | `TimeAddInterval` | 6.14 |
| 16 | time minus time | `SubtractTimes` | 6.15 |
| 17 | interval plus or minus interval, interval negation | `Subtract`, `UnaryMinus` | 6.15 |
| 18 | interval times a number | `MultiplyYMInterval` | 6.15 |
| 19 | `CAST` to an interval | `Cast` | 6.10 |

The other **27** are Spark's functions, which the standard does not define, as item 59 expected:
`date_add` and `date_sub` with an integer, `datediff`, `unix_date`, `date_from_unix_date`,
`quarter`, `dayofyear`, `dayofweek`, `weekday`, `extract(DAYOFWEEK_ISO ...)`, `weekofyear`,
`extract(YEAROFWEEK ...)`, `last_day`, `next_day`, `make_date`, `trunc`, `add_months`,
`make_ym_interval`, `abs` of an interval, `greatest`, `least`, `time_diff`, `time_trunc`,
`time_to_millis`, `time_to_micros`, `time_from_millis`, `time_from_micros`. Three of them reduce to
a standard operation (`date_add` to 14, `datediff` to 16 over dates, `date_sub` to 14 negated);
whether to define them that way is row 244's, which marks the extensions. `ABS`, `GREATEST` and
`LEAST` do not occur in the SQL-92 text; whether a later edition defines them for these types is
owed, and until it is checked they count as Spark's.

Two counts are smaller than they look. `BIGINT` is not in the SQL-92 text, so the long-lane rows
stand on 8.2's type-free comparison rule over a type a later edition added. And a predicate
projected as a value (`d < d2` as an output column) needs the BOOLEAN type, also later than SQL-92.
Both are listed as owed rather than counted out.

### 2.3 What the repo already records, and one correction to the brief

`SCOPE_STANDARD_MODE.md` 1 is the rule: **Varka's row answers follow vanilla Spark, always**; the
standard is read for the narrower ranges it would let a kernel assume, and those are recorded, not
acted on. `sql/varka/AGENTS.md` says nothing about the standard. So the fragment is a
specification to prove and to measure Spark against, never a description of what Varka returns.

### 2.4 The row, checked

The row is right in scope. It is corrected in one place: "the standard" is, for now, the SQL-92
text, with the current edition's clauses owed, and the row says so.

## 3. The design

### 3.1 The fragment

`sql/varka/proofs/spec/STANDARD.md`, one section per operation of 2.2, each with:

* the clause, and the sentences quoted that the definition rests on;
* the definition in lane terms - a date as an int32 day count from 1970-01-01, a year-month
  interval as an int32 month count, a time as an int64 nanosecond of the day, a day-time interval
  as an int64 microsecond count - written with `quot`/`rem` for the truncating division and
  `div`/`mod` for the flooring one, defined once at the top;
* the exception conditions and their SQLSTATEs (22003, 22007, 22008, 22012, 22015);
* **truncates, floors, or does not divide**, with a worked negative example where the two differ;
* each interpretation choice the text leaves open, with its reason;
* where Spark departs, one line naming the departure for row 244, and nothing more.

A preamble holds the choices that cut across operations: the source and its limits, the
proleptic calendar, leap seconds, time zones, integer precision, and the sign-and-magnitude
reading of intervals, which decides every interval division.

### 3.2 What is deliberately unchanged

No kernel, no proof file, no coverage row. Spark's departures and the Spark-only functions are row
244's; the refinement proofs row 245's. `dev/varka_prove.sh` globs `proofs/*.smt2` only, so
nothing under `spec/` reaches the linters' job, and the fragment carries no SMT-LIB yet: row 245
writes the definitions it proves against from the lane formulas here.

### 3.3 Registered op counts

None: nothing is emitted differently.

## 4. Files

| file | what |
|---|---|
| `sql/varka/proofs/spec/STANDARD.md` | the fragment |
| `sql/varka/proofs/README.md` | one row pointing at it |
| `sql/core/.../execution/VarkaStandardExamplesSuite.scala` | the worked examples where Spark and the standard agree, through the kernels |
| `sql/varka/plans/m7/PLAN.md` | row 243 only |

## 5. Tests, and what each is for

One sql/core suite, `VarkaStandardExamplesSuite`, over a small cached table of the worked
examples' inputs. Each query runs with Varka on, is asserted fused with its kernels run, and its
answer is asserted equal to the literal value the fragment states and to the row engine's. It pins
the examples where Spark agrees with the standard: `extract(YEAR FROM ym)` of minus eighteen
months is minus one (truncation), `year`, `month` and `day` of day minus one are 1969-12-31 (a
flooring decode), `d + ym` of minus one month from 1970-01-15 is 1969-12-15 (a flooring carry), a
negative day interval added, `ym - ymm` and `ym * 2` across zero. The examples where Spark departs
are row 244's tests, not these.

## 6. The measurement

None: a document and a test.

### 6.1 Predictions, registered before the run

1. The fragment needs no clause past what SQL-92 holds for 17 of the 19 operations; the two that
   reach a later edition are the BIGINT comparisons and a predicate as a value.
2. Three operations floor in lane terms (the date's decode from a day count, its month carry, and
   the time's modulo-24) and two truncate (the interval field extracts, and the time difference's
   rounding, where the text leaves the choice); the rest do not divide.
3. The suite adds under ten seconds to the sql/core run.

## 7. Risks

1. **A paraphrase drifts from the text.** Every definition quotes the sentences it rests on, so a
   reader checks the quote, not the paraphrase.
2. **A 1992 rule changed later.** Possible, and the fragment says which clauses are owed against
   the current edition rather than claiming them.
3. **A worked example is wrong.** The suite pins the ones Spark agrees with through the kernels;
   the departures are pinned by row 244.

## 8. Sequencing

1. This plan.
2. Row 243 in `m7/PLAN.md`, corrected as 2.4 says.
3. The fragment and the `proofs/README.md` row.
4. The suite.

## 9. Outcome

Written on 14 October 2026.

**What was built.** `sql/varka/proofs/spec/STANDARD.md`: the 19 operations of 2.2, each quoting
its SQL-92 clause, stated over the lane values, with its exception conditions, a verdict on
truncation and a worked negative example, its choices, and a one-line pointer where Spark departs.
Eight choices cut across operations (its section 3), among them the proleptic calendar, no leap
seconds, and the sign-and-magnitude reading of intervals that decides every interval division.
`VarkaStandardExamplesSuite` runs through the kernels the worked examples Spark agrees with that
a coverage row fuses, and
a lesson in `skills/calendar-algorithms.md` says which divisions truncate and which floor.

**What reading the text turned up that the record did not have.**

* SQL-92 has no `ABS`, `GREATEST`, `LEAST`, `BIGINT` or `BOOLEAN`. The first three count as
  Spark's until a later edition is checked; the long-lane comparisons and a projected predicate
  rest on the last two, owed.
* 8.2 GR 6 read literally compares intervals after `CAST (X AS INTEGER)`, which would drop a
  SECOND interval's fraction and make `INTERVAL '0.4' SECOND` equal zero. The fragment reads the
  "common base unit" as microseconds, which is what Spark does, and records the choice (4.4).
* Two SQLSTATEs claim a year past 9999 reached by arithmetic: 22008 (6.14 GR 3(c)) and 22007
  (6.1 GR 6). The fragment takes 22008 for arithmetic (4.14).
* 6.14 SR 3 forbids a time plus an interval with a DAY field, so the coverage row
  `t + INTERVAL '0 00:00:00' DAY TO SECOND` is outside the standard's syntax: a departure for
  row 244 beside the known one, that Spark raises where the standard wraps.
* `t - t2` truncates by the choice the text leaves open (6.15 GR 6(b)), Spark's; over the
  coverage table's `TIME(6)` columns the difference is whole microseconds and the choice never
  shows.
* A transcription loss: the "unknown" rows of 8.12's truth tables are missing from the text read,
  so `unknown AND unknown` and its kin are stated from the surviving rows and Spark, and owed.

**Two corrections to section 5.** The plan's comparison example ran `d < d2` as a projected
value; a predicate fuses only as a filter, so the suite runs comparisons as `WHERE` clauses. And
the `ym * 2` example is not pinned: the coverage table fuses an interval multiply only over
bounded operands, so a stored interval column times a constant declines to the row engine, and the
bounded form, `make_ym_interval(year(d), month(d)) * 2`, is not negative for any date the
standard admits, years 1 to 9999. Its definition (4.18) stands on the text alone until row 244
or 245 reaches it, as do two extracts of 4.12 that no coverage row fuses: an interval's MONTH,
whose `-6` against flooring's `6` is the sharpest case of C5 (`extract(MONTH FROM ym)` declines by design,
since Spark returns it as a byte and no lane holds one, `VarkaIntervalCompiler`), and a day-time
interval's DAY over a negative interval. Each is pinned when a coverage row fuses it.

**A correction to section 4.** The branch also adds a lesson to `skills/calendar-algorithms.md`
and regenerates `SKILLS.md`'s index, which the files table did not list.

**The predictions, scored.**

1. *17 of 19 need nothing past SQL-92, the two exceptions BIGINT and BOOLEAN.* Wrong as stated:
   all 19 definitions rest on SQL-92 clauses. BIGINT and BOOLEAN are types that some of them
   assume - the long-lane comparisons, a projected predicate - not operations of their own, and
   the prediction counted them as operations. Both are owed.
2. *Three floor in lane terms, two truncate, the rest do not divide.* Held: the date decode
   (4.10), the month carry (4.13) and the modulo-24 (4.15) floor; the interval extracts (4.12) and
   the time difference (4.16) truncate; the HOUR, MINUTE and SECOND extracts (4.11) divide a
   non-negative value, where the two agree.
3. *The suite adds under ten seconds.* Missed: its tests take under four seconds, but the suite
   takes eighteen with its sessions and cached table, on the laptop beside other agents' jobs. The
   line is in the calibration table in `skills/testing-and-debugging.md`.

**Owed**, as `STANDARD.md` section 6 lists: the current edition's clause for every definition;
the truth tables' unknown rows; the editions defining `BIGINT`, `BOOLEAN`, interval `ABS`,
`GREATEST` and `LEAST`; and the current wording of a time plus an interval. Whether the SQL-92
text read here belongs under `sql/varka/papers/` is the owner's call; it is not committed.

