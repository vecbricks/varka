# VARKA-244: Spark's departures from the specification, named and tested

## 1. Where this came from

Row 244 of `m7/PLAN.md`, item 59 step 2 of `m10/SCOPE.md`: "the deltas, each named, each with a
test that shows Spark doing it, and the extensions' definitions marked as Spark's own or ISO
8601's". The specification is row 243's, `sql/varka/proofs/spec/STANDARD.md`: 19 definitions from
the SQL-92 text, each of which points with one line at a departure for this row to name, and a
list of 27 coverage-table operations the standard does not define, for this row to mark. Item 59
names three deltas in advance - `clamp_to_month_end`, `overflow_yields_null`, `proleptic_range` -
and its reason for wanting them is row 245: a kernel is to be proven against the standard's
definition patched by the deltas in Spark mode, and against the unpatched one in standard mode, so
a delta has to be a definition, not a remark. Row 276's oracle (`VARKA-276.md` 9.3) waits on the
same list: a reference that disagrees with Spark is fixed or becomes a named delta.

## 2. The admission check, done

Done on 16 October 2026, before any writing. What had to be true: each departure STANDARD.md
points at exists in this fork's Spark, behaves as the pointer says, and can be shown by a query;
and item 59's three names fit what Spark does.

### 2.1 Every departure, run in this fork

A throwaway suite on this branch's build ran 59 queries under `spark.sql.ansi.enabled` false and
true (and the same list on the Spark 4.2.0 binary distribution, which lacks `TIME` without a
flag). What it showed, against STANDARD.md's pointers:

| STANDARD.md | the pointer said | this fork does |
| :--- | :--- | :--- |
| 4.1 | non-ANSI wraps, ANSI raises | `2147483647 + 1` is `-2147483648` non-ANSI; ANSI raises `ARITHMETIC_OVERFLOW`, SQLSTATE 22003, the standard's own. `-(-2147483648)`, `*` and bigint the same |
| 4.13 | Spark clamps to the month's end | `DATE '2024-01-31' + INTERVAL '1' MONTH` is 2024-02-29 in both modes, as is `add_months` |
| 4.10, 4.14 | dates run past 0001-9999 | `DATE '9999-12-31' + INTERVAL '1' DAY` is `+10000-01-01`, `date_sub(DATE '0001-01-01', 1)` is year 0, `make_date(-1, 1, 1)` is `-0001-01-01`, in both modes |
| 4.11 | `second(t)` drops the fraction | true of `second(t)`, an int; but `EXTRACT(SECOND FROM t)` is `decimal(8,6)` = 56.789000, and `decimal(11,9)` over `TIME(9)` - **the standard's EXTRACT agrees**, and the pointer is wrong about which operation the coverage row is |
| 4.14 | SR 3: a date plus an HOUR interval is a timestamp | `DATE '2024-01-01' + INTERVAL '1' HOUR` is a `timestamp` |
| 4.15 | Spark raises where the standard wraps; DAY TO SECOND allowed | `TIME '23:30' + INTERVAL '1' HOUR` raises `DATETIME_OVERFLOW`, 22008; `TIME '01:00' + INTERVAL '0' DAY` is accepted, `+ INTERVAL '1' DAY` raises 22008 |
| 4.19, C6 | Spark's day interval runs to about 106,751,991 days | `INTERVAL '100' DAY` and `CAST(100 AS INTERVAL DAY)` are values; `INTERVAL '106751992' DAY` raises 22015 |
| C2 | no leap second | `TIME '23:59:60'` is `INVALID_TYPED_LITERAL` (42604); its cast from a string is null, or `CAST_INVALID_INPUT` (22018) under ANSI |

And four departures STANDARD.md does not point at:

* **The day count wraps past int32** in both modes: `unix_date(date_add(date_from_unix_date(
  2147483647), 1))` is `-2147483648`, and the same through `d + CAST(1 AS INTERVAL DAY)`, both
  coverage rows. `DateAdd.nullSafeEval` is a plain `Int` add. A month added there raises
  `ARITHMETIC_OVERFLOW` (22003), where 6.14 GR 3(c) says 22008.
* **Interval overflow carries the wrong SQLSTATE, or none.** `CAST(106751992 AS INTERVAL DAY)` and
  `CAST(178956971 AS INTERVAL YEAR)` raise `CAST_OVERFLOW`, 22003, where 6.10 GR 12(a)(i) says
  22015; `CAST(1073741824 AS INTERVAL MONTH) * 2` throws a bare `java.lang.ArithmeticException`
  ("integer overflow") with no condition and no SQLSTATE, where 6.15 GR 4(e) casts the product
  to the interval and 6.10 GR 12(a)(i) makes the overflow 22015. Interval `+`, `-`, negation and an over-long literal raise 22015, as the standard does.
* **Division by zero is null in non-ANSI mode** (`1 / 0`), 22012 under ANSI. No coverage row
  divides, so it is outside the fragment; it is the one place `overflow_yields_null` holds of a
  standard operation, and `make_date` of a day that does not exist is the one coverage row where
  it holds at all (null, or 22023 under ANSI).
* `typeof(TIME '12:34:56')` is `time(6)` where 5.3 SR 16 makes it `TIME(0)`. No coverage row
  carries a literal's precision; noted, not named.

What it confirmed STANDARD.md says Spark agrees on: `extract(YEAR FROM INTERVAL '-1-6' YEAR TO
MONTH)` and MONTH are -1 and -6; `extract(YEAR FROM INTERVAL '30' MONTH)` is an analysis error;
`INTERVAL '0.4' SECOND = INTERVAL '0' SECOND` is false; `1 IN (2, NULL)` and `NULL AND NULL` are
null, `NULL AND false` false, `NULL OR true` true; a `TIME(9)` difference of -1500 ns is -1 us;
`DATE '1582-10-04' + 1` is 1582-10-05 (no gap); `datediff` equals the day count of `a - b`.

### 2.2 Item 59's three names, against this

`clamp_to_month_end` and `proleptic_range` fit. **`overflow_yields_null` does not fit the
fragment**: no operation STANDARD.md defines returns null where the standard raises; the
arithmetic of 4.1 *wraps*. So the fragment's delta is `overflow_wraps`, and
`overflow_yields_null` is kept under item 59's name for the two places it is true - division,
outside the fragment, and `make_date`, an extension - so that the row which adds division finds
the name already defined. The check would have rejected a row whose pointers did not hold; one did
not (4.11), and it is corrected rather than named.

### 2.3 The SQL-92 text, again

The text STANDARD.md quotes was fetched again into the scratch area, SHA-256
`4817caeff431bec4cb49589384b645aa5742a56a280cba01c321d98b4ff11f95`, the same file. The clauses the
new deltas rest on were read in it: 6.12 GR 4 (division by zero), 6.10 GR 12(a)(i) (interval
field overflow), 10.1 SR 3 and SR 5 (leading precision), 5.3 SR 16 and 21 (literal
precision, four-digit years). It is still not committed (`VARKA-243.md` 2.1).

## 3. The design

### 3.1 A delta is a definitional patch

`sql/varka/proofs/spec/DELTAS.md`, one section per delta, each with:

* its **name**, in item 59's form (`snake_case`), which row 245's Spark-mode definitions cite;
* the **definition it patches** (a STANDARD.md section, or "outside the fragment");
* **when it applies**: both modes, or non-ANSI only;
* the **patched definition** over the lane values, in STANDARD.md's notation (`quot`, `div`,
  `days`), so that row 245 can write Spark mode as standard-definition-plus-patch;
* the **test** in `VarkaStandardDeltasSuite` that shows Spark doing it;
* what it means for standard mode (`SCOPE_STANDARD_MODE.md`): a range a guard can assume, or a
  result no guard can make agree.

Eleven deltas: `overflow_wraps` (4.1), `overflow_yields_null` (outside, and `make_date`),
`clamp_to_month_end` (4.13), `proleptic_range` (C1, 4.10, 4.13, 4.14), `day_count_wraps` (4.14),
`interval_precision_unbounded` (C6, 4.17 to 4.19), `interval_overflow_state` (4.18, 4.19),
`time_add_raises` (4.15), `time_add_day_field` (4.15), `date_add_time_field` (4.14),
`no_leap_second` (C2). A last section lists what was checked and is **not** a delta, each with its
test, so the list is closed: a reader can tell "Spark agrees" from "nobody looked".

### 3.2 The extensions, marked

`sql/varka/proofs/spec/EXTENSIONS.md`: the 27 operations of STANDARD.md section 5, plus
`second(t)` (moved there by 2.1), each marked **Spark's**, **ISO 8601's** or **the standard's by
reduction**, with a definition over the lane values and the deltas it inherits. ISO 8601's are the
week rules (`extract(DAYOFWEEK_ISO ...)`, `weekofyear`, `extract(YEAROFWEEK ...)`), the ordinal
day (`dayofyear`) and the week start `trunc(d, 'WEEK')` takes; `datediff`, `date_add` and
`date_sub` are the standard's by reduction (STANDARD.md 5 left that call to this row); the rest are
Spark's. ISO 8601 is cited by its rules, not quoted: its text is not in the repository either, and
the file says so.

### 3.3 STANDARD.md and the register

STANDARD.md's "Spark." lines name their delta and link it, 4.11's is corrected, and section 5
points at EXTENSIONS.md. `SCOPE_STANDARD_MODE.md` gets the delta names beside the entries they
are: 3.1 and 3.2 are `interval_precision_unbounded` read as a speedup, 3.3 is `time_add_raises`,
and a new entry 3.7 records `proleptic_range` as a register entry (the standard's date range is a
bound a guard could assume) without a measured saving, which it says.

### 3.4 What is deliberately unchanged

No kernel, emitter, proof file or coverage row. Row 245 writes the patched definitions as SMT-LIB;
row 276's oracle gets no delta list, since it found no disagreement with Spark to excuse.
`proofs/README.md` is not edited (another row may be in it); its `spec/` row already says the
folder is not proofs.

### 3.5 Registered op counts

None: nothing is emitted differently.

## 4. Files

| file | what |
|---|---|
| `sql/varka/proofs/spec/DELTAS.md` | the named deltas, and the checked non-deltas |
| `sql/varka/proofs/spec/EXTENSIONS.md` | the operations the standard does not define, marked |
| `sql/varka/proofs/spec/STANDARD.md` | its "Spark." lines name their delta; 4.11 corrected; section 5 points on |
| `sql/varka/plans/SCOPE_STANDARD_MODE.md` | delta names on 3.1 to 3.3; entry 3.7 |
| `sql/core/.../execution/VarkaStandardDeltasSuite.scala` | one test per delta, one per checked agreement group, the ISO 8601 marks |
| `sql/varka/plans/m7/PLAN.md` | row 244 only |

## 5. Tests, and what each is for

`VarkaStandardDeltasSuite`, sql/core, on `VarkaSharedSessions`. Each delta's test runs its query
on `spark`, the row engine with Varka off, and asserts the delta's answer - the value, or the
error condition and SQLSTATE - written as a literal, so a Spark change that removes or alters a
delta fails here and the definition gets corrected rather than silently going stale. Where the
query reads a cached column that a coverage row fuses (`i + 1`, `-i`, `d + ym`, `date_add(d, k)`,
`d + CAST(k AS INTERVAL DAY)`), the same query also runs on `varkaSpark` and must give the same
answer, which is section 1 of `SCOPE_STANDARD_MODE.md` checked on exactly the rows where Spark is
not the standard; those queries are asserted fused, not that the kernels served them, since a
range guard declining a far date is a correct way to give Spark's answer. Literal-only queries
(the errors at analysis, a time literal) run on `spark` alone. Non-ANSI deltas run with ANSI off
and their ANSI arm is asserted to be the standard's exception, so the test shows both halves of
"non-ANSI only".

### 5.1 Prediction, registered

1. The suite takes under 40 s with its sessions, its tests under 10 s: VARKA-243's suite of the
   same shape took 18 s against a 10 s estimate (ratio about 2, the calibration table), and this
   one has three cached views and about twice the queries.

## 6. The measurement

None: documents and a test.

## 7. Risks

1. **A delta's literal answer is wrong.** Each is asserted against Spark itself, so a wrong
   literal fails the suite.
2. **A delta is missed.** The non-delta section lists what was checked; anything not in either
   list was not looked at, and the file says that is the boundary.
3. **Varka's matrix configurations decline a query it asserts fused.** The fused assertions are
   on coverage rows the examples suite already asserts fused; a decline under a configuration
   gets a `skips.tsv` line, not a weaker test.

## 8. Sequencing

1. This plan, and row 244 marked Planned.
2. The suite.
3. DELTAS.md, EXTENSIONS.md, STANDARD.md's pointers, the register.
4. Section 9 and the row's outcome.

## 9. Outcome

Written on 16 October 2026.

**What was built.** `sql/varka/proofs/spec/DELTAS.md`: eleven named deltas, each a patch to the
`STANDARD.md` definitions it names, with its modes, the patched definition over the lane values,
its test and what it means for standard mode; then the four groups checked and found not to be
deltas, and one departure found and not named (a time literal's precision). `EXTENSIONS.md`: the
27 operations of `STANDARD.md` 5 plus `second(t)` and `hour`/`minute`, each marked - four the
standard's by reduction (`date_add`, `date_sub`, `datediff`, and the HOUR and MINUTE extracts the
coverage table spells as functions), five ISO 8601's (the ISO day of the week, week, week-year,
ordinal day, and the week start `trunc(d, 'WEEK')` takes), the rest Spark's - and defined.
`STANDARD.md`'s "Spark." lines name their delta, 4.11's is corrected, and sections 1 and 5 point at
the two new files. `SCOPE_STANDARD_MODE.md` 3.1 to 3.3 carry their delta's name, and entry 3.7
records the standard's date range as a bound with no measured saving: Varka's calendar guard
already assumes a range about five times as wide. `VarkaStandardDeltasSuite`, 16 tests: one per
delta, four for the non-deltas, one for the ISO 8601 marks; seven of them also run their query on
`varkaSpark`, assert it fused and get Spark's answer - with the kernels asserted to have served the
batch everywhere but the int32 day count's ends - and three assert that a fused Varka plan raises
Spark's exception with Spark's message.

**Where the plan was wrong, or the run said more.**

* **A DataFrame's row encoder reads the active session's settings, and `VarkaSharedSessions`
  leaves none active.** Setting `spark.sql.datetime.java8API.enabled` on `spark` and `varkaSpark`,
  first once in `beforeAll` and then around each comparison with `withBothConf`, still collected
  `java.sql.Date`, which prints year 10000 as `0000` and year 0 as `0001` through the hybrid
  calendar; five tests failed on equal-looking rows. The suite now makes the queried session
  active around each comparison (`onSession`). The lesson is in `skills/testing-and-debugging.md`
  ("A sql/core suite that collects dates makes its session active first"), added at the
  coordinator's request after this section first said it was owed.
* **The non-ANSI queries fuse and the kernels serve them**: `i + 1` and `-i` give the wrapped
  values from the kernels, and `make_date(2021, i, 1)` its nulls; the plan had not known whether
  they would. So do the year-10000 and year-0 rows of `proleptic_range`, inside the calendar
  guard's range (`SCOPE_STANDARD_MODE.md` 3.7). Section 5 had asserted only fusion; the review
  below made it the kernels wherever no guard can decline.
* `hour(t)` and `minute(t)` were in neither of `STANDARD.md`'s lists by name: 4.11 maps them to
  `EXTRACT`, so `EXTENSIONS.md` 1 lists them as the standard's by reduction rather than leave a
  coverage row unmarked.

**The prediction, scored.** *The suite under 40 s with its sessions, its tests under 10 s.* Held:
25 s for the suite, 8.4 s for its 16 tests, on cores 12 to 23 beside other agents' jobs. Its line
for the calibration table in `skills/testing-and-debugging.md` - "VARKA-244 (5.1 #1) | a new
sql/core suite under 40 s, its tests under 10 s | 25 s, its tests 8.4 s | 0.6 | predicted at the
ratio of VARKA-243's line, which this suite's shape repeats" - is in the calibration table, added
with the lesson.

**The self-review** (`/code-review high` on the branch) found ten things; fixed: `bothRaise` now
asserts the Varka plan fused and compares the exception's class and message across the engines, not
only its condition; `bothAnswer` asserts the kernels served the batch wherever no guard can
decline; `overflow_yields_null`'s patched definition was as wide as "every data exception is null",
which would have contradicted three other deltas in a Spark-mode proof, and is now the two cases;
`EXTENSIONS.md` claimed its definitions held over every int32 day, where `last_day`, `next_day` and
`trunc(d, 'WEEK')` wrap at the ends and `add_months` raises (`last_day`'s wrap and `add_months`'s
22003 now tested); interval subtraction's 22015 was claimed and untested, and is tested; an unused
helper went. Not taken: moving `onSession` into `VarkaSharedSessions` (not this row's file; it is
the lesson below), writing the helpers in Java (the sql/core suites stay ScalaTest on
`SharedSparkSession`, and `VarkaStandardExamplesSuite`, the model here, keeps its helpers in Scala),
and, at first, the lesson and calibration line (since added; see above). After the fixes the suite
took 72 s, its tests 37 s, at a load average of 46 on 24 cores from other agents' jobs - the first
run's 25 s is the one the prediction is scored on.

**The option matrix, run** (`dev/varka_matrix.sh --module sql --suites VarkaStandardDeltasSuite`,
cores 12 to 23) under two configurations chosen to break what the suite asserts: `guardDayProducers=
false` (the far-date rows) passed 16 of 16; `checkIntOverflow=false` failed one test, the ANSI arm of
`overflow_wraps`, whose `i + 1` the kernel now wraps instead of raising - by design, as the
configuration's other `skips.tsv` lines say. One line in `sql/varka/matrix/skips.tsv` for it; the
rerun reads 15 passed and 1 cancelled. `proofs/README.md` has rows for `DELTAS.md` and
`EXTENSIONS.md`.

**What this leaves.**

* Row 245 writes the patched definitions as SMT-LIB: a delta here is stated so that "Spark mode" is
  "standard definition, then patch", and `overflow_wraps`, `clamp_to_month_end`,
  `proleptic_range` and `day_count_wraps` are the four a Spark-mode proof of the coverage rows
  needs.
* Row 276's oracle needs no delta list: it found no disagreement between the reference evaluator
  and Spark, and every delta here is Spark's own behaviour, which the reference already follows.
* ISO 8601's clauses, quoted, join `STANDARD.md` 6's owed list in spirit: `EXTENSIONS.md` cites the
  week date's rules without its text.
