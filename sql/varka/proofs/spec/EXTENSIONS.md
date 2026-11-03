# The coverage table's operations the standard does not define, marked

`STANDARD.md` section 5 lists the operations of Varka's coverage table that the SQL standard does
not define. Each is marked here by where its definition comes from, and defined over the lane
values in `STANDARD.md`'s notation, so that a proof has a specification for it as it has for the
standard's operations (`m9/SCOPE.md` item 59 step 2, VARKA-244):

* **the standard's, by reduction**: Spark's spelling of an operation `STANDARD.md` defines, so its
  definition is that one, with that one's deltas (`DELTAS.md`);
* **ISO 8601's**: the rule is ISO 8601's - the week date and the ordinal date - and Spark computes
  it, through `java.time`'s `IsoFields` and `DayOfWeek`;
* **Spark's**: the definition is Spark's own source, written down.

ISO 8601 is cited by its rules, not quoted: the repository holds none of its text, as it holds none
of the SQL standard's (`STANDARD.md` 1). The rules used are the week date's - weeks start on
Monday, Monday is day 1 and Sunday day 7, and week 1 of a week-numbering year is the week holding
that year's first Thursday - and the ordinal date's day of the year, from 1. Quoting the clauses is
owed with the SQL standard's current edition.

Every definition below holds over the int32 day counts of `proleptic_range` as long as its result
and Spark's intermediate sums stay in int32. At the two ends they do not, and Spark's plain `Int`
arithmetic wraps as `day_count_wraps` does (`DELTAS.md` 1.5): `last_day` computes `(n - D) + L`,
so `last_day(date_from_unix_date(2147483647))` is day `-2147483629` (tested); `next_day` and
`trunc(d, 'WEEK')` compute `n + 1` and `n - 7` first, so within a week of either end their answer
wraps, and its weekday is no longer the one named, since `2^32` is not a multiple of 7 (from the
source, not tested); the month functions raise 22003 instead. `dow(n) = mod(n + 3, 7)` is the day of the week counted from Monday = 0,
since day 0, 1970-01-01, was a Thursday. A test pins the ISO 8601 marks and Spark's two day-of-week
numberings: "ISO 8601's week rules, and Spark's own day-of-week numberings" in
`VarkaStandardDeltasSuite`.

## 1. The standard's, by reduction

| operation | definition | deltas |
| :--- | :--- | :--- |
| `date_add(d, k)` | `d + CAST(k AS INTERVAL DAY)`, 4.14: `n + k` | `proleptic_range`, `day_count_wraps` |
| `date_sub(d, k)` | `date_add(d, -k)`; Spark evaluates `n - k` | the same |
| `datediff(a, b)` | `(a - b) DAY`, SQL-92 6.15 GR 6 over two dates: `n_a - n_b`, exact, as an int | `proleptic_range` |
| `hour(t)`, `minute(t)` | `EXTRACT(HOUR ...)`, `EXTRACT(MINUTE ...)`, 4.11 | none |

`datediff` is the call `STANDARD.md` 5 left here: it is defined by the reduction, since the unit Y
of 6.15 GR 6 is DAY for two dates and nothing rounds. Spark agrees: `datediff(DATE '1969-12-31',
DATE '1970-01-02')` is -2 and the interval `DATE '1969-12-31' - DATE '1970-01-02'` is minus two
days (the test "not a delta: three-valued logic, IN with a null, the calendar across 1582,
datediff"). Inside the column contract (years 0001 to 9999) the difference of two day counts fits
an int; outside it two int32 day counts can differ by up to 2^32 - 1, and Spark's `DateDiff`, an
`Int` subtraction, wraps, as the kernel does (VARKA-245 9.2 item 3).

## 2. ISO 8601's

| operation | definition |
| :--- | :--- |
| `extract(DAYOFWEEK_ISO FROM d)` | `dow(n) + 1`: Monday 1 to Sunday 7 |
| `weekofyear(d)` | the ISO week number, 1 to 53, of the week-numbering year that holds the date's Thursday-of-week |
| `extract(YEAROFWEEK FROM d)` | the ISO week-numbering year: the calendar year of the date's Thursday-of-week, `n - dow(n) + 3` |
| `dayofyear(d)` | the ordinal date's day: `n - days(Y, 1, 1) + 1`, 1 to 366 |
| `trunc(d, 'WEEK')` | the ISO week's Monday, `n - dow(n)`; Spark's function, ISO 8601's week start |

`weekofyear(d)` is `quot(n - dow(n) + 3 - days(Y_w, 1, 1), 7) + 1`, where `Y_w` is the week-numbering
year above; every quantity is a whole number of days from a Thursday to its year's first, so the
division divides a non-negative dividend and truncating and flooring agree. 2021-01-03 is week 53
of 2020; 2024-12-30 is week 1 of 2025.

## 3. Spark's

| operation | definition | notes |
| :--- | :--- | :--- |
| `unix_date(d)` | `n` | the identity on the lane; the epoch is POSIX's, 1970-01-01 |
| `date_from_unix_date(k)` | the date with `n = k` | the identity |
| `quarter(d)` | `quot(M + 2, 3)`, 1 to 4 | ISO 8601 has no quarter |
| `dayofweek(d)` | `mod(n + 4, 7) + 1`: Sunday 1 to Saturday 7 | the ODBC numbering, not ISO 8601's |
| `weekday(d)` | `dow(n)`: Monday 0 to Sunday 6 | |
| `last_day(d)` | `days(Y, M, L(Y, M))` | `L` the month's length |
| `next_day(d, w)` | `n + 1 + mod(w - 1 - n, 7)` for weekday `w` as Spark's `DateTimeUtils.getNextDateForDayOfWeek` numbers it: the first day after `d`, never `d` itself | a name that is not a weekday is null, or `ILLEGAL_DAY_OF_WEEK` under ANSI |
| `make_date(y, m, dd)` | `days(y, m, dd)` | `overflow_yields_null`: a day that does not exist is null, or 22023 under ANSI; any year int32 days reach (`proleptic_range`) |
| `abs(x)` over a `DECIMAL(p, s)` | `abs(u) / 10^s` | never leaves the precision, so it raises in no mode (VARKA-348) |
| `trunc(d, 'YEAR')`, `'MONTH'`, `'QUARTER'` | `days(Y, 1, 1)`, `days(Y, M, 1)`, `days(Y, 3 * quot(M - 1, 3) + 1, 1)` | an unknown level is null |
| `add_months(d, k)` | 4.13 with `k` months: the standard's definition patched by `clamp_to_month_end` | Spark's name; the same function as `d + ym`, raising 22003 past int32 days |
| `make_ym_interval(y, mo)` | `12 * y + mo` months, exact; past int32, `INTERVAL_ARITHMETIC_OVERFLOW`, 22015 | `make_ym_interval(1, -18)` is minus six months: the fields are added, not sign-and-magnitude (C5) |
| `abs(ym)` | `m` with its sign dropped, exact; `abs` of `-2147483648` months raises `ARITHMETIC_OVERFLOW`, 22003 | no `ABS` in SQL-92 (`STANDARD.md` 6, item 4) |
| `greatest(a, b, ...)`, `least(...)` | the largest or smallest non-null operand, by 4.2 to 4.4's comparison; null only if all are | skips nulls, unlike a comparison, which is unknown on a null; not in SQL-92 |
| `second(t)` | `quot(mod(t, 60 * 10^9), 10^9)`, an int | not `EXTRACT(SECOND ...)`, which keeps the fraction (`DELTAS.md` 2) |
| `time_diff(unit, a, b)` | `quot(t_b - t_a, U)` for the unit's length `U` in nanoseconds | truncates: `time_diff('HOUR', TIME '23:00', TIME '01:30')` is -21 |
| `time_trunc(unit, t)` | `t - mod(t, U)` | `t >= 0`, so it floors and truncates alike |
| `time_to_millis(t)`, `time_to_micros(t)` | `div(t, 10^6)`, `div(t, 10^3)` | Spark writes `floorDiv`; over `t >= 0` it is `quot` too |
| `time_from_millis(k)`, `time_from_micros(k)` | `t = k * 10^6` or `k * 10^3` when that lies in `[0, 86,400 * 10^9)`, else `DATETIME_FIELD_OUT_OF_BOUNDS`, 22023 | |

`next_day`'s weekday number `w` is Spark's internal one, with which the formula above is the
source's; the answer is the first date after `d` that falls on the named day, so
`next_day(DATE '2024-01-07', 'MO')` is 2024-01-08 and `next_day(DATE '2024-01-08', 'MO')` is
2024-01-15.
