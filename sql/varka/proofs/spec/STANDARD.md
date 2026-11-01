# The SQL standard's definitions of the operations Varka lowers

One definition for each operation in Varka's coverage table (`docs/sql-varka.md`,
`sql/varka/coverage.json`) that the SQL standard fixes. Each one quotes the text it rests on,
states the operation over the values Varka's lanes hold, says whether it truncates, floors or does
not divide, and records every choice the text leaves to the implementation. This is a
specification to prove lowerings against and to measure Spark against, not a description of what
Varka returns: Varka's answers follow vanilla Spark, always (`plans/SCOPE_STANDARD_MODE.md` 1).
Where Spark departs from a definition, one line names the departure; the departures themselves,
each a named patch to these definitions with a test showing Spark doing it, are in `DELTAS.md`,
and the operations the standard does not define are listed at the end and marked in
`EXTENSIONS.md` (VARKA-244).

## 1. The source, and what it is not

The repository holds none of the standard's text, and the current edition (ISO/IEC 9075-2:2023)
is sold by ISO and was not to hand. Every clause here is from the one edition whose text could be
read:

> Information Technology - Database Language SQL (Proposed revised text of DIS 9075), July 1992,
> X3H2-92-154/DBL CBR-002

as kept at `http://www.contrib.andrew.cmu.edu/~shadow/sql/sql1992.txt` (1,637,146 bytes, SHA-256
`4817caeff431bec4cb49589384b645aa5742a56a280cba01c321d98b4ff11f95`, read on 14 October 2026). It
is the text ISO/IEC 9075:1992 (SQL-92) was published from, not the published standard.

* **Clause numbers are SQL-92's.** Later editions renumbered them, and moved the material into
  part 2 (Foundation). The current edition's clause for each definition is owed (section 6).
* **Quotes are verbatim** except that words the text hyphenates across a line end are rejoined.
* **The text may have changed since 1992.** Where a rule is known to be argued over since (time
  plus an interval, section 4.15), it says so. Nothing here carries a clause number that was not
  read in the text above.

## 2. Notation and the lane values

| SQL value | lane value |
| :--- | :--- |
| `DATE` | an int32 `n`, days from 1970-01-01 in the proleptic Gregorian calendar |
| `TIME(p)` | an int64 `t`, nanoseconds from midnight, `0 <= t < 86,400 * 10^9` |
| year-month interval | an int32 `m`, a signed count of months |
| day-time interval | an int64 `u`, a signed count of microseconds |
| `INTEGER`, `BIGINT` | an int32 or int64 |
| null | a clear validity bit; the data under it is free |

Two divisions, never written `/`:

* `quot(a, b)` truncates toward zero and `rem(a, b) = a - b * quot(a, b)` takes the sign of `a`:
  Java's `/` and `%`.
* `div(a, b)` floors and `mod(a, b) = a - b * div(a, b)` takes the sign of `b`: `Math.floorDiv` and
  `Math.floorMod`. For `b > 0` this is SMT-LIB's `div` and `mod`.

They differ only where `a` is negative and `b` does not divide it: `quot(-18, 12) = -1`,
`div(-18, 12) = -2`. Every operation below says which one it needs, or that it divides nothing.

`days(Y, M, D)` is the day count of a Gregorian date, so `days(1970, 1, 1) = 0`; the dates the
standard admits, 0001-01-01 to 9999-12-31, are `n` in `[-719162, 2932896]`.

SQLSTATEs are from 22.1's table, class 22, "data exception": 22003 numeric value out of range,
22007 invalid datetime format, 22008 datetime field overflow, 22012 division by zero, 22015
interval field overflow.

## 3. The choices that cut across operations

**C1. The calendar is proleptic Gregorian, with no discontinuity.** 4.5.1: the datetime fields
are "constrained by the natural rules for dates using the Gregorian calendar", and Table 10 (6.1)
admits YEAR "0001 to 9999". The Note under Table 10 leaves the alternative open: "Interval
arithmetic that involves leap seconds or discontinuities in calendars will produce
implementation-defined results." The choice: the Gregorian rules apply to every year from 1, as
in Spark 3 and later, so there is no 1582 gap and `days` is one formula. A Julian or hybrid
calendar would make `days` piecewise and every lowering's proof with it.

**C2. No leap seconds.** Table 10 allows SECOND "00 to 61.9(N)", room for two leap seconds, under
the same Note. The choice: SECOND is 0 to 59 and every day is `86,400 * 10^9` nanoseconds, since
Spark's `TIME` cannot hold a 60th second and a lane of nanoseconds has no place for one. The
standard admits time values Spark cannot represent; that is a range difference, not a delta in an
answer, and is named `no_leap_second` (`DELTAS.md` 1.11).

**C3. A time without a time zone is local and never converted.** 4.5.1: a time "that does not
specify WITH TIME ZONE has an implicit time zone equal to the local time zone for the
SQL-session", and 6.14 SR 5(b) makes "AT LOCAL" implicit. The choice: Spark's `TIME` is that
local time, and since every operand of the operations here carries the same session zone, its
displacement cancels in comparison and subtraction and is never applied in `EXTRACT`.

**C4. Integer types are two's complement int32 and int64.** 6.1 SR 19: "INTEGER specifies the data
type exact numeric, with binary or decimal precision and scale of 0. The choice of binary versus
decimal precision is implementation-defined". The choice: binary, 32 bits for `INTEGER`. `BIGINT`
is not in the SQL-92 text; it is taken as the same rule at 64 bits, and the edition that defines it
is owed.

**C5. An interval is a sign and magnitudes, so its lane count decomposes by truncation.** 4.5.2
gives an interval fields that are each non-negative within their range (Table 7: "MONTH | Months
(within years) (0-11)"), and 6.6 GR 3(a)(i) gives an extracted field "the same sign as the
<extract source>" - one sign for the whole value. So `INTERVAL '-1-6' YEAR TO MONTH` has YEAR 1,
MONTH 6 and a minus sign. Over a signed count `m`, that is exactly `quot(m, 12)` and `rem(m, 12)`:
`quot(-18, 12) = -1` and `rem(-18, 12) = -6`. The flooring split, `-2` years and `+6` months,
describes the same count but not the standard's fields. This choice decides the interval
divisions of section 4.12, the only ones in this fragment, and how the counts that 4.17 to 4.19
produce read as fields.

**C6. Interval precision is a parameter.** 10.1 SR 5: "If <interval leading field precision> is
not specified, then an <interval leading field precision> of 2 is implicit", and SR 3 sets its
maximum as "implementation-defined, but shall not be less than 2". The definitions take the
leading precision `p` as a parameter: the standard's default `p = 2` is what standard mode would
register (`SCOPE_STANDARD_MODE.md` 3.1 and 3.2); in Spark mode the maximum is the lane's own
range, since Spark's interval types have no precision. Spark's wider range is a delta,
`interval_precision_unbounded` (`DELTAS.md` 1.6).

**C7. Evaluation order is not part of a definition.** The text defines values, not whether `AND`
or `CASE` evaluates an operand it does not need. Which operand raises first is Varka's contract
with vanilla Spark's order, not this fragment's.

**C8. A truth value as a column needs `BOOLEAN`, which SQL-92 does not have.** In SQL-92 a
predicate is a <search condition>, never a value. Spark projects predicates as booleans, with
unknown as null; the fragment reads a projected predicate that way, and the edition that defines
`BOOLEAN` is owed.

## 4. The definitions

### 4.1 Exact numeric `+`, `-`, `*` and monadic minus

`i + 1`, `i - 1`, `year(d) * 2`, `-i`, `year(d) + i` (`Add`, `Subtract`, `Multiply`,
`UnaryMinus`). SQL-92 6.12.

> GR 1: If the value of any <numeric primary> simply contained in a <numeric value expression> is
> the null value, then the result of the <numeric value expression> is the null value.
>
> GR 3: Monadic minus reverses the sign of its operand.
>
> GR 5(a): If the operator is not division and the mathematical result of the operation is not
> exactly representable with the precision and scale of the result type, then an exception
> condition is raised: data exception-numeric value out of range.

**Definition.** For non-null `a`, `b` of a type with range `[lo, hi]`: `a + b`, `a - b`, `a * b`
and `-a` are the exact integer results when they lie in `[lo, hi]`, and raise 22003 otherwise.
A `DECIMAL(p, s)` value (C4's exact numeric of precision `p` and scale `s`, `p <= 18` on the long
lane) is the algebraic value `u / 10^s` of its unscaled lane value `u`, with range
`|u| <= 10^p - 1`; its monadic minus is `-u / 10^s`, within the range for every `u` in it.
The sum and difference of two `DECIMAL` values `u1 / 10^s1` and `u2 / 10^s2` have scale
`S = max(s1, s2)` and their product scale `s1 + s2`, each precision the implementation's (SQL-92
6.12 SR 2), and each value is exact: `u1 * 10^(S - s1) +- u2 * 10^(S - s2)` at scale `S`, and
`u1 * u2` at scale `s1 + s2`; a result outside its type's precision raises 22003 (VARKA-349).

**Truncates or floors:** does not divide. `-2147483647 - 1 = -2147483648`, in range;
`-2147483648 - 1` raises 22003.

**Choices.** The result type's precision is implementation-defined (SR 1(b), 1(c)); with C4 the
result of two `INTEGER`s is `INTEGER`, so the range is int32's. Monadic minus is taken as an
arithmetic operation under GR 5(a), its result type that of its operand (SR 3), so `-(-2147483648)`
raises 22003 rather than wrapping.

**Spark.** Non-ANSI Spark wraps where this raises, `overflow_wraps` (`DELTAS.md` 1.1); ANSI Spark
raises 22003, as defined here.

### 4.2 Comparison of exact numerics

`i > 0`, `i = 5`, `year(d) = 2021`, `month(d) > i`, `l > l2`, `l = l2`, `l >= 5000000000`
(`EqualTo`, `LessThan`, `LessThanOrEqual`, `GreaterThan`, `GreaterThanOrEqual`). SQL-92 8.2.

> GR 1(a): If XV or YV is the null value, then "X <comp op> Y" is unknown.
>
> GR 2: Numbers are compared with respect to their algebraic value.

**Definition.** Unknown if either operand is null; otherwise the signed comparison of the two
integers.
Two `DECIMAL` values `u1 / 10^s1` and `u2 / 10^s2` compare as those rationals do, which for
`s1 <= s2` is the comparison of `u1 * 10^(s2 - s1)` with `u2`.

**Truncates or floors:** does not divide. `-1 < 0` is true; a comparison read unsigned would make
it false.

**Choices.** None beyond C4 and C8. The bigint rows rest on C4's `BIGINT`.

### 4.3 Comparison of datetimes

`d = d2`, `d < d2`, `d <= d2`, `d > d2`, `d >= d2`, `t < t2`, `t = TIME'12:34:56.789'`. SQL-92
8.2.

> GR 5: The comparison of two datetimes is determined according to the interval resulting from
> their subtraction. Let X and Y be the two values to be compared and let H be the least
> significant <datetime field> of X and Y. The result of X <comp op> Y is defined as:
> `( X - Y ) H <comp op> INTERVAL (0) H`

**Definition.** Unknown if either is null. For dates, H is DAY and `(X - Y) DAY` is `n_X - n_Y`
days, so the comparison is the signed comparison of `n_X` and `n_Y`. For times, H is SECOND with
its fraction, and the comparison is the signed comparison of `t_X` and `t_Y`.

**Truncates or floors:** does not divide; the difference is taken in the operands' own least
significant unit. `DATE '1969-12-31' < DATE '1970-01-01'` is `-1 < 0`, true.

**Choices.** Two times of different fractional precisions, as in the TIME(3) literal against a
TIME(6) column, compare at the finer one: the subtraction is exact (4.16), so no rounding enters.

### 4.4 Comparison of intervals

`dt > dt2`, `dt < INTERVAL '0' SECOND`, and the conditions of the long-lane `CASE` rows. SQL-92 8.2.

> GR 6: The comparison of two intervals is determined by the comparison of their corresponding
> values after conversion to integers in some common base unit. [...] X is effectively replaced
> by CAST (X AS INTERVAL U). Y is effectively replaced by CAST (Y AS INTERVAL U). The result of
> the comparison is effectively computed as: CAST ( X AS INTEGER ) <comp op> CAST ( Y AS INTEGER )

**Definition.** Unknown if either is null; otherwise the signed comparison of `u_X` and `u_Y`
(or of `m_X` and `m_Y` for year-month intervals).

**Truncates or floors:** does not divide, under the choice below.

**Choices.** Read literally, `CAST (X AS INTEGER)` of a SECOND interval with a fraction drops the
fraction (6.10 GR 3(c) keeps only the leading significant digits), which would make
`INTERVAL '0.4' SECOND = INTERVAL '0' SECOND` true. The choice: the "common base unit" of GR 6's
first sentence is the finest fractional unit either operand has, microseconds here, so the
integers compared are `u_X` and `u_Y` and no fraction is lost. Spark compares the microsecond
counts, so this choice makes no delta.

### 4.5 `IS NULL` and `IS NOT NULL`

`d IS NULL`, `d IS NOT NULL`, `i IS NOT NULL`, `l IS NULL`, `l IS NOT NULL`, `dt IS NOT NULL`
(`IsNull`, `IsNotNull`). SQL-92 8.6.

> GR 2: If all the values in R are the null value, then "R IS NULL" is true; otherwise, it is
> false.
>
> GR 3: If none of the values in R are the null value, then "R IS NOT NULL" is true; otherwise, it
> is false.

**Definition.** Over one value (degree 1): `IS NULL` is the validity bit's complement and `IS NOT
NULL` the bit; neither is ever unknown.

**Truncates or floors:** not arithmetic.

### 4.6 `NOT`, `AND`, `OR`

`NOT (d = d2)`, `year(d) = 2021 AND i > 0`, `year(d) = 2021 OR month(d) = 3`. SQL-92 8.12.

> GR 2: NOT(true) is false, NOT(false) is true, and NOT(unknown) is unknown. Table 13, "Truth table
> for the AND boolean", Table 14, "Truth table for the OR boolean" [...] specify the semantics of
> AND, OR [...]

**Definition.** Kleene's three-valued logic: `false AND x = false`, `true AND x = x`,
`true OR x = true`, `false OR x = x`, for `x` in {true, false, unknown}, and `unknown AND unknown =
unknown OR unknown = unknown`.

**Truncates or floors:** not arithmetic.

**Not checked against the text.** The transcription lost the tables' header rows and their
"unknown" rows; what survives is the true and false rows of Tables 13 and 14, which fix every
entry above with a true or false operand on the left. The entries with unknown on the left,
`unknown AND unknown` and `unknown OR unknown` among them, are stated from the commutativity those
rows show and from Spark, not from the text, and are owed against a complete copy.

### 4.7 `IN` over a value list

`d IN (DATE '2021-01-01', DATE '2021-06-01')`, and the 11 to 16 literal form the optimizer turns
into `InSet` (`In`, `InSet`). SQL-92 8.4 and 8.7.

> 8.4 SR 4: The expression RVC IN IPV is equivalent to RVC = ANY IPV
>
> 8.7 GR 2(c): If the implied <comparison predicate> is true for at least one row RT in T, then
> "R <comp op> <some> T" is true.
>
> 8.7 GR 2(d): If T is empty or if the implied <comparison predicate> is false for every row RT in
> T, then "R <comp op> <some> T" is false.
>
> 8.7 GR 2(e): If "R <comp op> <quantifier> T" is neither true nor false, then it is unknown.

**Definition.** True if `x = v` is true for some list element `v`; false if it is false for every
one; unknown otherwise, which is when no element equals `x` and `x` or some element is null.
`In` and `InSet` are one operation.

**Truncates or floors:** not arithmetic.

### 4.8 Searched `CASE`, and `if`

`CASE WHEN d < d2 THEN d ELSE d2 END`, `if(d < d2, d, d2)`, `if(l IS NULL, l2, l)` (`CaseWhen`,
`If`). SQL-92 6.9.

> GR 2(a): If the <search condition> of some <searched when clause> in a <case specification> is
> true, then the value of the <case specification> is the value of the <result> of the first
> (leftmost) <searched when clause> whose <search condition> is true, cast as the data type of the
> <case specification>.
>
> GR 2(b): If no <search condition> in a <case specification> is true, then the value of the
> <case expression> is the value of the <result> of the explicit or implicit <else clause> [...]
>
> SR 6: If an <else clause> is not specified, then ELSE NULL is implicit.

**Definition.** The first arm whose condition is true; an unknown condition is not true and falls
through; the `ELSE` value, or null, if none is. `if(c, a, b)` is `CASE WHEN c THEN a ELSE b END`.

**Truncates or floors:** not arithmetic.

**Choices.** The arms are taken already of one type: the cast GR 2 names is Spark's analyzer's
coercion, done before a kernel sees the expression.

### 4.9 `COALESCE`

`coalesce(d, d2)` (`Coalesce`). SQL-92 6.9.

> SR 2: COALESCE (V1, V2) is equivalent to the following <case specification>: CASE WHEN V1 IS
> NOT NULL THEN V1 ELSE V2 END
>
> SR 3: COALESCE (V1, V2, . . . ,n ), for n >= 3, is equivalent to the following <case
> specification>: CASE WHEN V1 IS NOT NULL THEN V1 ELSE COALESCE (V2, . . . ,n ) END

**Definition.** The first non-null operand, or null if all are null.

**Truncates or floors:** not arithmetic.

### 4.10 `EXTRACT` of YEAR, MONTH or DAY from a date

`year(d)`, `month(d)`, `day(d)` (`Year`, `Month`, `DayOfMonth`). SQL-92 6.6.

> GR 3(a)(i): If <extract field> is a <datetime field>, then the result is the value of the
> datetime field identified by that <datetime field> and has the same sign as the <extract
> source>.
> Note: If the value of the identified <datetime field> is zero or if <extract source> is not an
> <interval value expression>, then the sign is irrelevant.

**Definition.** The date's YEAR, MONTH or DAY field: for `n = days(Y, M, D)`, the result is `Y`,
`M` or `D`. Null in, null out (GR 3(b)).

**Truncates or floors: floors, in lane terms.** The standard divides nothing: a date is its
fields. The day count is this fragment's encoding (section 2), and recovering the fields from it
is a division of `n` that must floor, because the dates before 1970 have negative `n`. Worked
example: `n = -1` is 1969-12-31, so `year = 1969`. A year step written as `quot(n, 365)` gives
`0`, that is 1970, where `div(n, 365) = -1` gives 1969. Varka's calendar decomposition divides
only non-negative dividends, after adding a bias to `n`, so that each of its divisions can be the
truncating one (`chrono_divide.smt2`, and the site table in `plans/m7/VARKA-242.md` 2.1).

**Choices.** The result type is "exact numeric with implementation-defined precision and scale 0"
(SR 4(a)); it is int32.

**Spark.** Spark's dates run past 0001-9999 on the int32 day count, `proleptic_range` (`DELTAS.md`
1.4).

### 4.11 `EXTRACT` of HOUR, MINUTE or SECOND from a time

`hour(t)`, `minute(t2)`, `second(t)` (`HoursOfTime`, `MinutesOfTime`, `SecondsOfTime`). SQL-92 6.6,
GR 3(a)(i) as quoted in 4.10, and:

> SR 4(b): Otherwise [SECOND], the data type of the result is exact numeric with
> implementation-defined precision and scale. The implementation-defined scale shall not be less
> than the specified or implied <time fractional seconds precision> [...]

**Definition.** With `H = 3600 * 10^9` and `N = 60 * 10^9` nanoseconds: HOUR is `div(t, H)`,
MINUTE is `div(mod(t, H), N)`, and SECOND is `mod(t, N) / 10^9` as an exact decimal with at least
the time's fractional digits.

**Truncates or floors: either, since `t` is never negative.** A time is `0 <= t < 86,400 * 10^9`,
and over non-negative operands `quot = div`. There is no admissible negative example: a negative
`t` is not a time, and the definition need not choose.

**Spark.** Agrees: `extract(SECOND FROM TIME '12:34:56.789')` is 56.789000, a `decimal(8,6)`, and
a `decimal(11,9)` over `TIME(9)`. *Corrected by VARKA-244, which ran it:* this line said Spark
drops the fraction, which is true of the coverage row's `second(t)`, an int: that is Spark's own
`second` function, not `EXTRACT`, and is marked in `EXTENSIONS.md` 3 (`DELTAS.md` 2).

### 4.12 `EXTRACT` of a field from an interval

`extract(YEAR FROM ym)` and its use in `extract(YEAR FROM ym) - 1` (`ExtractANSIIntervalYears`),
and the day count taken from the interval in `d + CAST(i AS INTERVAL DAY)`
(`ExtractANSIIntervalDays`). SQL-92 6.6, GR 3(a)(i) as quoted in 4.10, and:

> SR 3(a): If <extract field> is a <datetime field>, then it shall identify a <datetime field> of
> the <interval value expression> or <datetime value expression> immediately contained in
> <extract source>.

**Definition.** By C5, for a year-month interval YEAR is `quot(m, 12)` and MONTH is `rem(m, 12)`.
For a day-time interval with `E = 86,400 * 10^6` microseconds, DAY is `quot(u, E)` and HOUR is
`quot(rem(u, E), 3600 * 10^6)`.

**Truncates or floors: truncates.** "The same sign as the <extract source>" applied to the
magnitudes. Worked example: `INTERVAL '-1-6' YEAR TO MONTH` is `m = -18`; YEAR is `quot(-18, 12) =
-1` and MONTH is `rem(-18, 12) = -6`, where flooring gives `-2` and `6`. `INTERVAL '-1 12' DAY TO
HOUR` is `u = -36` hours; DAY is `-1`, where flooring gives `-2`.

**Spark.** Agrees, SR 3(a) included: `ExtractIntervalPart.parseExtractField` admits a field only
inside the interval's range, so `EXTRACT(YEAR FROM INTERVAL '30' MONTH)` is an error in both, and
YEAR and DAY are Java's truncating `/` (`IntervalUtils.getYears`, `getDays`).

### 4.13 A date plus or minus a year-month interval

`d + INTERVAL 3 MONTH`, `d + ym`, `d - ym` (`DateAddYMInterval`, and `DatetimeSub` over it). SQL-92
6.14.

> GR 3(a): [...] successive <datetime field>s of the <interval value expression> or <interval
> term> are added to the corresponding fields of the <datetime value expression> or <datetime
> term>. [ii: otherwise] subtracted from [...]
>
> GR 3(b): Arithmetic is performed so as to maintain the integrity of the datetime data type that
> is the result of the <datetime value expression>. This may involve carry from or to the
> immediately next more significant <datetime field>. [...] If the <interval value expression> or
> <interval term> is a year-month interval, then the DAY field of the result is the same as the
> DAY field of the <datetime term> or <datetime value expression>.
>
> GR 3(c): If, after the preceding step, any <datetime field> of the result is outside the
> permissible range of values for the field or the result is invalid based on the natural rules
> for dates and times, then an exception condition is raised: data exception-datetime field
> overflow.

**Definition.** For `n = days(Y, M, D)` and `m` months (`-m` for minus): with the month carry
`c = div(M - 1 + m, 12)`, the result is `days(Y + c, mod(M - 1 + m, 12) + 1, D)`, or 22008 when
that year is outside 1 to 9999 or that month has no day `D`. Null in, null out (GR 1).

**Truncates or floors: floors, in the carry.** Worked example: `DATE '1970-01-15' + INTERVAL '-1'
MONTH` has `M - 1 + m = -1`; `div(-1, 12) = -1` and `mod(-1, 12) = 11` give 1969-12-15, where
`quot` and `rem` give a carry of 0 and month index `-1`, which is no month. Written over the
absolute month count `12 * Y + M - 1 + m` instead, every quantity is non-negative for the years
admitted, and the two divisions agree there.

**Choices.** None: the DAY rule and the overflow are the text's.

**Spark.** `DATE '2024-01-31' + INTERVAL '1' MONTH` is 22008 here, since February has no 31st;
Spark clamps to 2024-02-29, `clamp_to_month_end` (`DELTAS.md` 1.3). They agree whenever the day
exists in the target month, which the worked example's does.

### 4.14 A date plus a day interval

`d + CAST(i AS INTERVAL DAY)` (`DateAdd` over `ExtractANSIIntervalDays`, with the cast of 4.19).
SQL-92 6.14, GR 3 as quoted in 4.13, and:

> SR 3: If an <interval value expression> or <interval term> is specified, then the <interval
> value expression> or <interval term> shall only contain <datetime field>s that are contained
> within the <datetime value expression> or <datetime term>.

**Definition.** For a DAY interval of `k` days: `n + k`, or 22008 when it leaves `[-719162,
2932896]`.

**Truncates or floors:** does not divide. The day count read from a DAY interval (4.12) is exact,
since its microsecond count is a whole number of days. Worked example: `DATE '1970-01-01' +
INTERVAL '-1' DAY` is `0 + (-1) = -1`, 1969-12-31.

**Choices.** The year leaving 0001-9999 raises 22008 here, under GR 3(c), although 6.1 GR 6 names
22007 ("invalid datetime format") for a YEAR outside 1 to 9999. The choice: 22008 for a result of
arithmetic, since GR 3(c) is the rule for this operation and its Note points to Table 10's YEAR
range; 22007 for a value made any other way.

**Spark.** SR 3 forbids a date plus an interval with an HOUR, MINUTE or SECOND field; Spark accepts
one, casting the date to a timestamp and returning a timestamp
(`BinaryArithmeticWithDatetimeResolver`), outside the coverage table: `date_add_time_field`
(`DELTAS.md` 1.10). Spark's range is again the int32 day count (`proleptic_range`), and past it
`n + k` wraps, `day_count_wraps` (1.5).

### 4.15 A time plus a day-time interval

`t + INTERVAL '0 00:00:00' DAY TO SECOND` (`TimeAddInterval`). SQL-92 6.14, GR 3 as quoted in
4.13, with this sentence of GR 3(b):

> If the data type of the <datetime value expression> is TIME, then arithmetic on the HOUR
> <datetime field> is undertaken modulo 24.

**Definition.** For an interval with no DAY field (SR 3, quoted in 4.14): `mod(t + 1000 * u,
86,400 * 10^9)`.

**Truncates or floors: floors.** Worked example: `TIME '00:15' + INTERVAL '-1' HOUR` is
`mod(-45 minutes, 24 hours) = 23:15`. A truncating `rem` gives `-45` minutes, which is no time.

**Choices.** "Modulo 24" is read as the non-negative residue, because Table 10 admits HOUR only
"00 to 23", so no other residue is a valid field.

**Spark.** Spark raises 22008 where the sum leaves the day instead of wrapping, `time_add_raises`
(`DELTAS.md` 1.8; `SCOPE_STANDARD_MODE.md` 3.3; SPARK-57853 asks whether to follow the standard).
And the coverage row's interval is DAY TO SECOND, which SR 3 forbids beside a time,
`time_add_day_field` (1.9).

### 4.16 A time minus a time

`t - t2` (`SubtractTimes`, typed `INTERVAL HOUR TO SECOND`). SQL-92 6.15.

> GR 6(a): A and B are converted to integer scalars A2 and B2 respectively in units Y as
> displacements from some implementation-dependent start datetime.
>
> GR 6(b): The result is determined by effectively computing A2-B2 and then converting the
> difference to an interval using an <interval qualifier> whose <end field> is Y [...]. That
> interval is then converted to an interval using the specified <interval qualifier>, rounding or
> truncating if necessary. The choice of whether to round or truncate is implementation-defined.

**Definition.** `quot(t_A - t_B, 1000)` microseconds: the nanosecond difference is exact, and is
converted to the result's six fractional digits (10.1 SR 6's default).

**Truncates or floors: truncates, by choice.** The text leaves rounding or truncation to the
implementation; the choice is truncation toward zero, Spark's (`DateTimeUtils.subtractTimes`), so
that the choice is no delta. Worked example, `TIME(9)` operands 1,500 nanoseconds apart in the
negative direction: `quot(-1500, 1000) = -1` microsecond, where flooring gives `-2` and rounding
half away from zero gives `-2`. Over the coverage table's `TIME(6)` columns the difference is a
whole number of microseconds and the choice is never visible.

**Choices.** The standard writes the qualifier, `(t - t2) HOUR TO SECOND`; Spark's spelling
`t - t2` is read as that, its type.

### 4.17 Interval plus or minus interval, and interval negation

`ym - ymm`, and the negation inside `d - ym` (`Subtract`, `UnaryMinus` over intervals). SQL-92
6.15.

> GR 3: If the <sign> of an <interval factor> is <minus sign>, then the value of the <interval
> factor> is the negative of the value of the <interval primary>.
>
> GR 5(d): Let I, the result of the <interval value expression> expressed in terms of the
> <datetime field> Q, be the result of: CAST ((A OP B) AS INTERVAL Q)
>
> GR 5(e): The result of the <interval value expression> is CAST (I AS INTERVAL W) where W is an
> <interval qualifier> identifying the <datetime field>s P TO Q, but with <interval leading field
> precision> such that significant digits are not lost.

**Definition.** With both operands in the least significant field (MONTH, for these rows): `m_A -
m_B`, `m_A + m_B`, `-m`; 22015 only past the implementation's maximum precision (C6), which in
Spark mode is int32.

**Truncates or floors:** does not divide. Worked example: `INTERVAL '-1-6' YEAR TO MONTH -
INTERVAL '6' MONTH` is `-18 - 6 = -24` months, `INTERVAL '-2-0' YEAR TO MONTH` by C5.

### 4.18 Interval times a number

`make_ym_interval(year(d), month(d)) * 2` (`MultiplyYMInterval`). SQL-92 6.15.

> GR 4(c): Let E be an exact numeric result of the operation CAST (CAST (X AS INTERVAL Q) AS E1)
> where E1 is an exact numeric data type of sufficient scale and precision so as to not lose
> significant digits.
>
> GR 4(e): Let I, the result of the <interval value expression> expressed in terms of the
> <datetime field> Q, be the result of CAST ((E OP Y) AS INTERVAL Q).

**Definition.** For an integer `k`: `m * k` months; 22015 past the maximum precision.

**Truncates or floors:** does not divide for an integer multiplier. Worked example: `INTERVAL
'1-7' YEAR TO MONTH * -2` is `19 * (-2) = -38` months, YEAR `-3`, MONTH `-2` by C5. A fractional
multiplier would need GR 4(e)'s cast to an integral MONTH count to round or truncate, which the
text leaves to the implementation; no coverage row has one.

**Spark.** Past int32 the product throws an `ArithmeticException` with no SQLSTATE where this is
22015, `interval_overflow_state` (`DELTAS.md` 1.7).

### 4.19 `CAST` to an interval

`CAST(ymy AS INTERVAL MONTH)`, `CAST(i AS INTERVAL DAY)` (`Cast`). SQL-92 6.10.

> SR 5: If TD is an interval and SD is exact numeric, then TD shall contain only a single
> <datetime field>.
>
> GR 12(a)(i): If the representation of SV in the data type TD would result in the loss of leading
> significant digits, then an exception condition is raised: data exception-interval field
> overflow.
>
> GR 12(d): If SD is interval and TD and SD have different interval precisions, then let Q be the
> least significant <datetime field> of TD. (i) Let Y be the result of converting SV to a scalar in
> units Q according to the natural rules for intervals as defined in the Gregorian calendar.
> (ii) Normalize Y to conform to the datetime qualifier "P TO Q" of TD. If this would result in
> loss of precision of the leading datetime field of Y, then an exception condition is raised: data
> exception-interval field overflow.

**Definition.** `CAST(i AS INTERVAL DAY)` is `i` days, `u = i * 86,400 * 10^6`, or 22015 when `i`
has more digits than the leading precision allows. `CAST(x AS INTERVAL MONTH)` from a YEAR interval
is `12 * years` months, the same count `m` the lane already holds, or 22015 past the target's
precision.

**Truncates or floors:** does not divide; both casts scale up. Worked example: `CAST(-1 AS
INTERVAL DAY)` is minus one day, and `CAST(INTERVAL '-2' YEAR AS INTERVAL MONTH)` is `INTERVAL
'-24' MONTH`.

**Spark.** Spark's day interval holds about 106,751,991 days, int64 microseconds, where the
standard's default is two digits, `interval_precision_unbounded` (`DELTAS.md` 1.6;
`SCOPE_STANDARD_MODE.md` 3.2); and past the lane the cast raises 22003 where this is 22015,
`interval_overflow_state` (1.7).

## 5. The coverage table's operations the standard does not define

Spark's own functions, each marked as Spark's, ISO 8601's or the standard's by reduction, and
defined, in `EXTENSIONS.md`: `date_add` and `date_sub` with an integer, `datediff`, `unix_date`,
`date_from_unix_date`, `quarter`, `dayofyear`, `dayofweek`, `weekday`, `extract(DAYOFWEEK_ISO ...)`,
`weekofyear`, `extract(YEAROFWEEK ...)`, `last_day`, `next_day`, `make_date`, `trunc`, `add_months`,
`make_ym_interval`, `abs` of an interval, `greatest`, `least`, `time_diff`, `time_trunc`,
`time_to_millis`, `time_to_micros`, `time_from_millis`, `time_from_micros`; and `second(t)`, which
4.11 shows is not `EXTRACT`. Three reduce to an operation above: `date_add(d, k)` to 4.14 with
`CAST(k AS INTERVAL DAY)`, `date_sub` to the same with `-k`, and `datediff(a, b)` to `(a - b) DAY`:
6.15 GR 6, quoted in 4.16, over two dates, where the unit Y is DAY and the difference is `n_a - n_b`
exactly, so nothing rounds. No coverage row spells a date minus a date, so that reduction has no
section of its own here; `EXTENSIONS.md` 1 defines `datediff` by it, and Spark agrees. `ABS`,
`GREATEST` and `LEAST` do not occur in the SQL-92 text.

## 6. Owed

1. **The current edition's clause for every definition**, and a check that none of the quoted
   rules changed: ISO/IEC 9075-2:2023, or any edition after 1992.
2. **The "unknown" rows of 8.12's Tables 13 and 14** (4.6), lost in the transcription.
3. **`BIGINT`** (C4) and **`BOOLEAN`** (C8): the edition that defines each, and its wording.
4. **`ABS` of an interval, `GREATEST` and `LEAST`** (section 5): whether a later edition defines
   them for these types, which would move them into section 4.
5. **Time plus an interval** (4.15): the current wording of "modulo 24", which the upstream
   question (SPARK-57853) turns on.
