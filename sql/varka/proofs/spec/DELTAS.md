# Spark's departures from the standard's definitions, named

`STANDARD.md` defines each operation of Varka's coverage table that the SQL standard fixes. This
file is what turns it into a definition of Spark: each section below is one **delta**, a named
patch to one or more of those definitions, so that "Spark mode" is the standard's definition with
its deltas applied and "standard mode" is the definition alone (`m9/SCOPE.md` item 59). Varka's
answers are Spark's, always (`plans/SCOPE_STANDARD_MODE.md` 1); a delta is a fact about Spark that
a proof in Spark mode has to include, not a choice Varka makes.

Every delta has a test in `VarkaStandardDeltasSuite` (`sql/core`) that runs its query on the row
engine and asserts the delta's answer as a literal, and, where the query reads a column a coverage
row fuses, asserts that Varka gives the same answer from a fused plan whose kernels served the
batch (or, over the int32 day count's ends, whose guard declined it), or raises the same exception
with the same message. The tests were written against this fork's
Spark on 16 October 2026 (VARKA-244); a Spark change that removes or alters a delta fails its
test, and the section here is then corrected.

Notation is `STANDARD.md`'s section 2: `n` a day count, `m` a month count, `t` nanoseconds of the
day, `u` microseconds, `quot`/`rem` truncating and `div`/`mod` flooring, `days(Y, M, D)`. **Modes**
says whether the delta holds with `spark.sql.ansi.enabled` on, off, or both. **Standard mode** says
what the delta means for `plans/SCOPE_STANDARD_MODE.md`: a range a guard could assume, so a
lowering may take the standard's definition and decline what lies outside; or a result no guard can
make agree, so standard mode cannot buy it.

## 1. The deltas

| name | patches | modes | in short |
| :--- | :--- | :--- | :--- |
| `overflow_wraps` | 4.1 | non-ANSI | integer arithmetic wraps where the standard raises 22003 |
| `overflow_yields_null` | 4.1 over `DECIMAL`; otherwise outside the fragment | non-ANSI | division by zero, Spark's `make_date`, and a decimal past its precision return null |
| `clamp_to_month_end` | 4.13 | both | a month added to a day the target month lacks gives its last day |
| `proleptic_range` | C1, 4.10, 4.13, 4.14 | both | a date is any int32 day count, not 0001 to 9999 |
| `day_count_wraps` | 4.14 | both | a day added past the int32 day count wraps |
| `interval_precision_unbounded` | C6, 4.17 to 4.19 | both | an interval's leading field has the lane's range, not two digits |
| `interval_overflow_state` | 4.18, 4.19 | both | interval overflow raises 22003, or no SQLSTATE, where the standard says 22015 |
| `time_add_raises` | 4.15 | both | a time plus an interval that leaves the day raises 22008, where the standard wraps |
| `time_add_day_field` | 4.15 | both | a time accepts an interval with a DAY field |
| `date_add_time_field` | 4.14 | both | a date plus an interval with an HOUR, MINUTE or SECOND field is a timestamp |
| `no_leap_second` | C2 | both | second 60 is refused |

### 1.1 `overflow_wraps`

**Patches** 4.1: `a + b`, `a - b`, `a * b` and `-a` over `INTEGER` and `BIGINT`, which the standard
makes 22003 outside the type's range.

**Modes:** non-ANSI only. With ANSI on, Spark raises `ARITHMETIC_OVERFLOW`, SQLSTATE 22003, which
is the standard's definition exactly.

**Patched definition.** The exact result reduced into the type's range modulo `2^32` (`INTEGER`) or
`2^64` (`BIGINT`), two's complement: Java's `+`, `-`, `*` and unary `-`. So `2147483647 + 1 =
-2147483648` and `-(-2147483648) = -2147483648`.

**Test:** "overflow_wraps: non-ANSI int arithmetic wraps where the standard raises 22003", over
`i + 1`, `i - 1` and `-i` fused, and its ANSI arm.

**Standard mode:** a result, not a range; no guard makes a wrapped value the standard's. The ANSI
arm is already the standard.

### 1.2 `overflow_yields_null`

**Patches** 4.1 over `DECIMAL` (the correction below; written first as patching no definition of
the fragment). Item 59 named it beside `overflow_wraps`; the check of
VARKA-244 (its plan, 2.2) found that no operation `STANDARD.md` defines returns null where the
standard raises, so it is kept under item 59's name for the two places it is true:

* **Division**, outside the coverage table: SQL-92 6.12 GR 4, "If the value of a divisor is zero,
  then an exception condition is raised: data exception-division by zero." Non-ANSI Spark returns
  null for `x / 0` and `x % 0`; ANSI raises `DIVIDE_BY_ZERO`, 22012, the standard's.
* **`make_date`**, an extension (`EXTENSIONS.md`): a day that does not exist is null non-ANSI, and
  `DATETIME_FIELD_OUT_OF_BOUNDS`, 22023, under ANSI. The standard has no `make_date`; its nearest
  operation, a date literal, is constrained "by the natural rules for dates" (5.3 SR 22).

*Corrected by VARKA-506, 11 November 2026:* the check above predates the decimal rows. Since
VARKA-348 and VARKA-349 put `DECIMAL` in 4.1, a third place is in the fragment:

* **A decimal result past its type's precision** (4.1, SQL-92 6.12 GR 5(a)'s 22003): non-ANSI
  Spark returns null, through `checkDecimalOverflow`'s `toPrecision(p, s, ROUND_HALF_UP,
  nullOnOverflow = true)` in `arithmetic.scala`; ANSI raises `NUMERIC_VALUE_OUT_OF_RANGE`, 22003,
  the standard's. `try_add`, `try_subtract` and `try_multiply` return null in both modes.

**Modes:** non-ANSI only.

**Patched definition.** Over exactly those three: `x / 0` and `x % 0` are null, `make_date(y, m,
dd)` is null where `(y, m, dd)` names no day, and a decimal `+`, `-` or `*` is null where its exact
result (the product's after `Decimal.*`'s 39 digits), moved `HALF_UP` to its result type's scale
(`STANDARD.md` 4.1), does not fit that type's precision. Nothing else: the other data exceptions of the fragment wrap (1.1) or still raise in
non-ANSI mode (1.5, 1.7, 1.8).

**Test:** "overflow_yields_null: non-ANSI division by zero, a non-existent make_date and a decimal
past its precision are null", with `make_date(2021, i, 1)` fused, a `DECIMAL(38, 0)` product of
`10^38` on the row engine, and its ANSI arm.

**Standard mode:** a result; and a row that adds division to the coverage table finds the name
defined here.

### 1.3 `clamp_to_month_end`

**Patches** 4.13: a date plus or minus a year-month interval, where "the DAY field of the result is
the same as the DAY field" of the date, and 6.14 GR 3(c) raises 22008 when the target month has no
such day.

**Modes:** both.

**Patched definition.** With the carry of 4.13, `Y' = Y + div(M - 1 + m, 12)` and `M' = mod(M - 1 +
m, 12) + 1`, the result is `days(Y', M', min(D, L(Y', M')))`, where `L` is the length of the month.
So `DATE '2024-01-31' + INTERVAL '1' MONTH = 2024-02-29`, and `DATE '2024-03-31' - INTERVAL '1'
MONTH` the same. Spark's `add_months` (`EXTENSIONS.md`) is the same function. Where `D <= L(Y',
M')` the patch is the identity, and the two definitions agree.

**Test:** "clamp_to_month_end: a month added to a day the target month lacks is its last day", over
`d + ym`, `d2 - ym` and `add_months(d, 1)` fused, in both modes.

**Standard mode:** a result: the standard's answer for these inputs is an exception, which no
kernel can return as a value.

### 1.4 `proleptic_range`

**Patches** C1 and every definition whose result is a date (4.10, 4.13, 4.14): SQL-92's Table 10
admits YEAR "0001 to 9999", so `n` lies in `[-719162, 2932896]`, and arithmetic leaving that range
raises 22008 (6.14 GR 3(c)).

**Modes:** both.

**Patched definition.** A date is any int32 day count `n`, on the same proleptic Gregorian rules
(C1 holds unchanged; only its range widens): years from -5877641 to 5881580, with year 0 a leap
year and negative years as `java.time` numbers them. `DATE '9999-12-31' + INTERVAL '1' DAY` is
`+10000-01-01`; `DATE '0001-01-01' - INTERVAL '1' DAY` is `0000-12-31`; `make_date(-1, 1, 1)` is
`-0001-01-01`; the literal `DATE '+10000-01-01'` parses, where 5.3 SR 21 wants four digits. A
result inside int32 is a value; past it, see `day_count_wraps`.

**Test:** "proleptic_range: a step past 9999-12-31 or before 0001-01-01 is a date, not 22008",
over `date_add(d, k)`, `d + CAST(k AS INTERVAL DAY)`, `d + ym` and `year(date_add(d, k))` fused.

**Standard mode:** a range. The standard's dates are a bound a guard can assume and check
(`SCOPE_STANDARD_MODE.md` 3.7); a batch outside it declines to the row engine, which returns
Spark's year 10000.

### 1.5 `day_count_wraps`

**Patches** 4.14: `n + k`, which is 22008 outside the range; under `proleptic_range` alone the range
would be int32's and the result past it an exception.

**Modes:** both.

**Patched definition.** `date_add(d, k)`, `date_sub(d, k)` and `d + CAST(k AS INTERVAL DAY)` are
`n + k` reduced modulo `2^32` into int32: Spark's `DateAdd` is a plain `Int` add. So
`date_add(date_from_unix_date(2147483647), 1)` is day `-2147483648`, the year -5877641. A
year-month interval added there does not wrap: `DateTimeUtils.dateAddMonths` converts through
`LocalDate` and `Math.toIntExact`, and raises `ARITHMETIC_OVERFLOW`, 22003, where 6.14 GR 3(c)
says 22008. Spark's own date functions there do the same plain `Int` arithmetic (`EXTENSIONS.md`):
`last_day(date_from_unix_date(2147483647))` is day `-2147483629`, and `add_months` raises 22003.

**Test:** "day_count_wraps: a day added past the int32 day count wraps to the other end", over both
coverage spellings fused, in both modes, and the month's 22003.

**Standard mode:** subsumed by `proleptic_range`'s guard, whose range is far inside the wrap.

### 1.6 `interval_precision_unbounded`

**Patches** C6, and through it 4.17, 4.18 and 4.19: with no <interval leading field precision>
written, "an <interval leading field precision> of 2 is implicit" (SQL-92 10.1 SR 5), so `INTERVAL
'100' DAY` and `CAST(100 AS INTERVAL DAY)` overflow (6.10 GR 12(a)(i), 22015).

**Modes:** both.

**Patched definition.** Spark's interval types carry no leading precision. A year-month interval
is any int32 month count `m`, a day-time interval any int64 microsecond count `u`, and each field's
range is what the lane allows: about 178,956,970 years, about 106,751,991 days. A literal or cast
past the lane raises (`interval_overflow_state` says with which state). This is a legal choice of
the "implementation-defined maximum" of 10.1 SR 3 applied as the default, which SR 5 does not
allow: the delta is the default, not the maximum.

**Test:** "interval_precision_unbounded: an interval's leading field is not held to two digits",
over literals and casts, with 106,751,991 days a value and 106,751,992 days 22015.

**Standard mode:** a range, and the register's largest entries: `SCOPE_STANDARD_MODE.md` 3.1 and
3.2 are this delta read as a speedup.

### 1.7 `interval_overflow_state`

**Patches** the exception condition of 4.18 and 4.19. SQL-92 6.10 GR 12(a)(i): a number cast to an
interval that would lose leading significant digits raises "data exception-interval field
overflow", 22015, and 6.15 GR 4(e) computes an interval's product through that cast.

**Modes:** both.

**Patched definition.** The values are the standard's (with `interval_precision_unbounded`'s
range); the exception is not:

* `CAST(x AS INTERVAL DAY)` or `... YEAR` past the lane raises `CAST_OVERFLOW`, 22003;
* `ym * k` past int32 throws `java.lang.ArithmeticException` ("integer overflow"), with no error
  condition and no SQLSTATE;
* interval `+`, `-`, negation and an over-long literal raise `INTERVAL_ARITHMETIC_OVERFLOW`, 22015,
  as the standard does (the literal in 1.6's test, the rest in this one's).

**Test:** "interval_overflow_state: a cast past the lane raises 22003, an interval product none",
including `d + CAST(i AS INTERVAL DAY)` on both engines.

**Standard mode:** an exception's identity; a guard declines to the row engine, which raises
Spark's, so nothing here is for a kernel to match.

### 1.8 `time_add_raises`

**Patches** 4.15: `mod(t + 1000 * u, 86,400 * 10^9)`, arithmetic on HOUR "undertaken modulo 24"
(6.14 GR 3(b)).

**Modes:** both.

**Patched definition.** `t + 1000 * u` when it lies in `[0, 86,400 * 10^9)`, and otherwise
`DATETIME_OVERFLOW`, 22008 (`DateTimeUtils.timeAddInterval`). So `TIME '23:30' + INTERVAL '1'
HOUR` raises where the standard gives 00:30, and `TIME '00:15' + INTERVAL '-1' HOUR` raises where
it gives 23:15. The SQLSTATE is the standard's for a datetime overflow; the standard's operation
never overflows.

**Test:** "time_add_raises: a time plus an interval that leaves the day raises 22008, no modulo",
over literals and a cached column on both engines, in both modes.

**Standard mode:** a result (`SCOPE_STANDARD_MODE.md` 3.3, and SPARK-57853 upstream).

### 1.9 `time_add_day_field`

**Patches** the domain of 4.15. SQL-92 6.14 SR 3: the interval "shall only contain <datetime
field>s that are contained within the <datetime value expression>", and a time has no DAY.

**Modes:** both.

**Patched definition.** A time accepts a day-time interval with a DAY field, DAY TO SECOND
included, and 1.8's definition applies to its whole microsecond count: `TIME '01:00' + INTERVAL
'0' DAY` is 01:00, `+ INTERVAL '1' DAY` raises 22008. The coverage row `t + INTERVAL '0 00:00:00'
DAY TO SECOND` is one such expression.

**Test:** "time_add_day_field: a time plus an interval with a DAY field is accepted", including
the coverage row fused.

**Standard mode:** none: an expression the standard rejects at compile time has no standard
answer to guard for.

### 1.10 `date_add_time_field`

**Patches** the domain of 4.14, by the same SR 3: a date has no HOUR, MINUTE or SECOND field.

**Modes:** both.

**Patched definition.** A date plus a day-time interval with an HOUR, MINUTE or SECOND field is
`CAST(d AS TIMESTAMP) + u`, a timestamp in the session's time zone
(`BinaryArithmeticWithDatetimeResolver`). Outside the coverage table.

**Test:** "date_add_time_field: a date plus an HOUR interval is a timestamp".

**Standard mode:** none, as 1.9.

### 1.11 `no_leap_second`

**Patches** C2. SQL-92's Table 10 admits SECOND "00 to 61.9(N)"; `STANDARD.md` already chose no
leap seconds for its definitions, and this delta is that choice shown in Spark.

**Modes:** both, with the error by mode.

**Patched definition.** SECOND is 0 to 59. `TIME '23:59:60'` is `INVALID_TYPED_LITERAL`, 42604;
`CAST('23:59:60' AS TIME)` is null non-ANSI and `CAST_INVALID_INPUT`, 22018, under ANSI.

**Test:** "no_leap_second: second 60 is refused, where the standard admits it".

**Standard mode:** a range Spark cannot represent; a lane of nanoseconds has no place for it.

## 2. Checked, and not deltas

What VARKA-244 ran and found Spark agreeing with the definition, each with its test, so that this
list and section 1 together are everything checked. An operation in neither has not been checked
against Spark.

* **4.11, `EXTRACT(SECOND FROM t)`.** `STANDARD.md` pointed a delta at the SECOND extract. Spark's
  `extract(SECOND FROM t)` keeps the fraction, as a `decimal(8,6)` over `TIME(6)` and
  `decimal(11,9)` over `TIME(9)`, which is 6.6 SR 4(b)'s scale "not less than" the time's
  precision. The coverage row `second(t)` is Spark's `second` function, an int with the fraction
  dropped; it is an extension (`EXTENSIONS.md`), not a departure. Test: "not a delta:
  EXTRACT(SECOND FROM t) keeps the fraction; Spark's second(t) is its own".
* **4.12, an interval's fields, and 4.4.** YEAR and MONTH of `INTERVAL '-1-6' YEAR TO MONTH` are -1
  and -6 and DAY of `INTERVAL '-1 12' DAY TO HOUR` is -1, by truncation; `extract(YEAR FROM
  INTERVAL '30' MONTH)` is refused (SR 3(a)); `INTERVAL '0.4' SECOND = INTERVAL '0' SECOND` is
  false. Test: "not a delta: an interval's fields truncate, and a field outside it is refused".
* **4.6, 4.7, C1 and `datediff`.** `NULL AND NULL` and `NULL OR NULL` are null, `NULL AND false`
  false, `NULL OR true` true, `1 IN (2, NULL)` null; `DATE '1582-10-04' + 1` is 1582-10-05, no
  Julian gap; `datediff(a, b)` is the day count of `a - b`. Test: "not a delta: three-valued logic,
  IN with a null, the calendar across 1582, datediff".
* **4.16, the time difference.** -1,500 ns between two `TIME(9)` values is -1 us, truncation, the
  choice `STANDARD.md` made to agree with Spark. Test: "not a delta: a time difference truncates
  toward zero, Spark's choice and the spec's".
* **4.1 under ANSI**, 22003; **4.17's overflow**, 22015: in the tests of 1.1 and 1.7.

The worked examples of `STANDARD.md` on which Spark agrees run through the kernels in
`VarkaStandardExamplesSuite` (VARKA-243).

## 3. Found, not named

* **A time literal's precision.** `typeof(TIME '12:34:56')` is `time(6)`, where 5.3 SR 16 makes a
  time literal without a fraction `TIME(0)`. No coverage row carries a literal's precision into an
  answer, so it is not named; a row that does would name it here.
