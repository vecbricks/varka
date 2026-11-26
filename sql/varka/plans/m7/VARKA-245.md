# VARKA-245: The proofs restated as refinements of the specification

## 1. Where this came from

Row 245 of `m7/PLAN.md`, item 59 step 3 of `m10/SCOPE.md`: "Item 58's proofs re-stated as refinements
of the spec, soundness and guard completeness, in Spark mode and in standard mode; the standard-mode
register cites the standard-mode theorems as its bounds." The proofs of rows 240 to 242 show a
lowering equal to a Java formula - the multiply-high equal to `/`, a calendar site's magic equal to
its quotient - and the specification of rows 243 and 244 (`proofs/spec/STANDARD.md`, `DELTAS.md`,
`EXTENSIONS.md`) says what the operations are. Nothing yet connects the two: no file states that a
kernel's answer is the specification's answer, or that its declines are exactly where the
specification raises or the lowering's bound is passed. `m7/PLAN.md` 2.1 fixes the form, from
`m7/READING.md` 11's reading of Alive: "five queries per mode over (data, valid) pairs, data free
under a null bit: a satisfiable precondition, a decline wherever Spark raises, null-ness equal,
values equal on valid lanes, and a decline only where Spark raises or the input leaves the bound."
The owner kept the row on 13 October 2026, to be decided again when 244 lands (`m7/PLAN.md` 9.4
item 1); 244 is in review as #854, and this branch is based on it.

## 2. The admission check, done

Done on 16 October 2026, before any writing. What had to be true: that the solvers decide the
hardest refinement, the calendar's decomposition against the standard's day count, within the
per-check limit (`VARKA_PROVE_LIMIT_MS`, 10 s) and with room in the linters' minute; and that the
standard's calendar rules can be tied to a closed formula without a sweep.

### 2.1 The decomposition in one check: too slow

The default prefix (`julianMap`, `neriSchneiderMonth`: `VarkaChrono.narrowedJulian` and `fields`),
every magic replaced by its exact quotient, as `chrono_divide.smt2` proves each one is over its
site's dividends, and the claim that for every day `n` in `[NARROW_MIN_DAYS,
NARROW_DECOMPOSE_MAX_DAYS]` the year, month and day it gives are a valid date whose
`range_calendar.smt2` `cal.epoch` is `n`: **unsat, in 91 s under Z3 and 28 s under cvc5** on cores
12 to 23 beside other agents' jobs. True, and nine times the per-check limit.

### 2.2 Split into lemmas: seconds

With the standard's day count written as one floor-division formula (`spec.days`, Hinnant's
`days_from_civil` over SMT-LIB's flooring `div`, one branch for every year where `cal.epoch` has two):

| check | Z3 | cvc5 |
| :--- | ---: | ---: |
| the era's tail: every day of era `[0, 146096]` gives a valid date with `spec.days` = day of era less 719468 (under `ALL`) | 1.9 s | 0.6 s |
| the same under `QF_NIA` | 15.5 s | 1.4 s |
| the 400-year period: `spec.days(y + 400 k, m, d) = spec.days(y, m, d) + 146097 k`, and the month length, for every `y`, `k` (under `ALL`) | 0.1 s | 1.0 s |
| the same under `QF_NIA` | 0.8 s | past 120 s |
| the rules: 1970-01-01 is day 0; the day after a day of the month, the first of the next month after the month's last day, 1 January after 31 December, each one more - for every year | 0.01 to 0.1 s | 0.01 to 0.1 s |

The rules make `spec.days` the Gregorian calendar's count without an exhaustive sweep: every valid
date's successor is one more, and day 0 is 1970-01-01, so by induction in both directions it counts
days, for every integer year (item 59 expected "rules to formula by exhaustion"; the solver does it
for all years in milliseconds). The file declares `ALL`: cvc5 does not decide the period under
`QF_NIA`, and Z3 takes eight times longer on the tail.

**What it would have rejected:** a form that misses the 10 s per-check limit under either solver,
or a period lemma neither solver decides; then the calendar refinements would have been stated over
the suite's sweep instead of proven. Neither happened.

### 2.3 What the kernels are, read from the emitter

`dev/varka_emit.sh` on each coverage row restated (16 October, this branch), and the guards
`VarkaGuardSpecs` reads (`VarkaLoopEmitter.guardsForTest`):

* `i + 1`, `i - 1`, `-i`, `year(d) + i` are `int:ADD:FAIL` and the like under ANSI - an
  `INT_OVERFLOW` guard on the exact result - and `WRAP` without ANSI;
* `date_add(d, i)` and `d + CAST(i AS INTERVAL DAY)` are both `addDays col col`, Java's wrapping
  add, the cast's bound checked per batch on the offset column (`VarkaInputBound`,
  `VarkaChrono.INTERVAL_DAY_LIMIT_DAYS`); no guard on the result unless a calendar node reads it;
* `year(d)` over a bare column is `(year col:0)` with **no guard**: the range analysis takes the
  column to lie in the project's column contract, 0001-01-01 to 9999-12-31
  (`VarkaRangeAnalysis.CONTRACT`), which "is not checked at ingestion" (`docs/sql-varka.md`);
* `d + ym`, `add_months(d, i)` are `addMonths`, the count held to `[MONTH_ARITH_MIN_MONTHS,
  MONTH_ARITH_MAX_MONTHS]` by a `MONTH_COUNT` guard; `d - ym` negates the count with a `FAIL` node;
* `extract(YEAR FROM ym)` is `divc:12`, the multiply-high `int_mulhi_divide.smt2` proves exact for
  every int;
* `ym - ymm` is `int:SUB:FAIL` and `abs(ym)` an `IfElse` over `neg:FAIL`;
* `t + dt` (`VarkaTimeCompiler.timeAddInterval`): the interval held to a day by a `GuardedRange`,
  times 1000 wrapping, the sum held to `[0, NANOS_PER_DAY - 1]` by a second; `t - t2`, `hour`,
  `minute` and `second` are `ConstDivide`s of the nanoseconds (`long_divide.smt2`,
  `time_bounds.smt2`);
* `weekday`, `dayofweek` and the ISO day of the week are `floorMod(n, 7)` (`floor_mod7.smt2`) and a
  modular offset, so the `+ 3` never overflows.

## 3. The design

### 3.1 A refinement, as five queries

One rendered file, `sql/varka/proofs/refinement.smt2`, by `VarkaProofFiles.refinement()`. Per lane, a
kernel's outcome is `(decline, valid, data)` and the specification's `(raises, valid, value)`, both
functions of the operands' `(data, valid)` pairs; the data under a clear bit is a free variable. For
each operation and each mode, after a precondition `pre` (each operand a value of its lane and its
type's contract - a time in `[0, NANOS_PER_DAY)`) and the lowering's registered `bound` (a guard's
domain, an input bound, the column contract), five checks, in `m7/PLAN.md` 2.1's words:

1. **a satisfiable precondition** - `sat`: `pre`, `bound`, every operand valid and the specification
   defined, so the next four are not vacuous;
2. **a decline wherever the specification raises** - `unsat`: `pre`, `raises`, not `decline`;
3. **null-ness equal** - `unsat`: `pre`, not `raises`, not `decline`, `valid_k != valid_s`;
4. **values equal on valid lanes** - `unsat`: `pre`, not `raises`, not `decline`, `valid_s`,
   `data != value`;
5. **a decline only where the specification raises or the input leaves the bound** - `unsat`:
   `pre`, `bound`, `decline`, not `raises`.

2 to 4 are soundness, 5 guard completeness; a null lane is inside every bound, so 5 also says a null
never declines. The kernel's arithmetic is written with `java.smt2`'s operators, and where a lowering
proof holds a form equal to an operator - a magic to its quotient, the multiply-high to `jint.div`, a
long division to `jlong.div`, the leap hash to the Gregorian rule - the refinement uses the operator
and cites the proof. That substitution is only sound over the range the lowering proof covers, so
the file **checks it**: for each site the refinement uses, a check that every dividend it feeds the
site lies within the site's `maxDividend` and its stride and residue (read from
`VarkaChronoLowering.ChronoDivide`, not typed), and that every year fed to the leap flag lies within
`[-YEAR_BIAS, LEAP_HASH_MAX_BIASED_YEAR - YEAR_BIAS]`. The composition is then lemmas the solver
holds, not a remark in a header.

### 3.2 The two modes

* **Spark mode**: the specification is `STANDARD.md`'s definition with `DELTAS.md`'s patches
  applied, or `EXTENSIONS.md`'s definition. Where a delta holds in one ANSI setting only
  (`overflow_wraps`, `overflow_yields_null`), the two settings are two arms, each against the
  kernel the compiler emits under it (`WRAP` or `FAIL`; `make_date`'s null or decline).
* **Standard mode**: the unpatched definition, with the register's ranges as the bound
  (`SCOPE_STANDARD_MODE.md` 2: assume the standard's range, register it as an input bound, decline
  outside), over the kernel as the emitter builds it. Where a delta is a *result* - one no guard can
  make agree - the refinement is false, and the query that fails is written `expect sat` with the
  delta's own example as its witness (`DATE '2024-01-31' + INTERVAL '1' MONTH` for
  `clamp_to_month_end`), followed by the same query with the delta's inputs excluded, `unsat`, which
  shows the delta is the only departure. The register (3.1 to 3.3, 3.7) then cites the `unsat`
  theorems as the bounds it would register and the `sat` witnesses as what standard mode cannot buy.

Where no delta touches an operation the two modes are one statement, and the file says so once
rather than rendering the same five checks twice.

### 3.3 The calendar layer

Under the refinements, the lemmas of 2.2 as checks: the four rules (with the leap year as C1
defines it), `spec.days` pinned to `LocalDate.toEpochDay` at rendered dates around year 0, the
1582 change, 1970, 9999/10000 and the narrow range's ends; the era's tail, the 400-year period,
their composition over the guarded range, and `spec.days` injective over valid dates, so the
kernel's date is *the* date of `n`. A calendar refinement then asserts those lemmas' instances
and is linear over a few variables. The inverse direction `add_months` recomposes with
(`daysFromCivil`) gets the same: equal to `spec.days` over every valid date of the years the month
arithmetic reaches.

### 3.4 The operations restated

| `STANDARD.md` / `EXTENSIONS.md` | coverage rows | lowering proofs composed | modes |
| :--- | :--- | :--- | :--- |
| 4.1 `+ - *` and negation | `i + 1`, `i - 1`, `-i`, `year(d) + i`, `year(d) * 2` | none (`java.smt2`) | Spark non-ANSI, Spark ANSI, standard |
| 4.10 YEAR, MONTH, DAY | `year(d)`, `month(d)`, `day(d)` | `chrono_divide`, the lemmas | Spark, standard |
| 4.11 HOUR, MINUTE | `hour(t)`, `minute(t2)` | `long_divide`, `time_bounds` | one statement |
| 4.12 YEAR of an interval | `extract(YEAR FROM ym)` | `int_mulhi_divide` | Spark, standard |
| 4.13 date plus months | `d + ym`, `d - ym`, `add_months(d, i)` | `chrono_divide`, `leap_hash`, the lemmas | Spark, standard |
| 4.14, 4.19 date plus days | `date_add(d, i)`, `d + CAST(i AS INTERVAL DAY)` | none | Spark, standard |
| 4.15 time plus interval | `t + dt` | `time_bounds` | Spark, standard |
| 4.16 time minus time | `t - t2` | `long_divide`, `time_bounds` | one statement |
| 4.17, 4.18 interval arithmetic | `ym - ymm`, `abs(ym)`, an interval times a literal | none | one statement |
| ext. `datediff` | `datediff(d2, d)` | none | Spark |
| ext. day of the week | `weekday`, `dayofweek`, `extract(DAYOFWEEK_ISO ...)` | `floor_mod7` | Spark |
| ext. `make_date` | `make_date(2021, i, 1)` | `leap_hash`, the inverse | Spark non-ANSI, Spark ANSI |
| ext. time conversions | `second(t)`, `time_from_micros(...)` | `long_divide`, `time_bounds` | Spark |

### 3.5 What is deliberately unchanged

* **The analyses' proofs** (`value_range`, `range_analysis`, `range_calendar`) state the
  preconditions the bounds come from, not lowerings; they are cited, not restated.
* **The proofs of 240 to 242** stay as they are: the refinement composes them.
* **`STANDARD.md` 4.2 to 4.9** (comparison, `IS NULL`, the connectives, `IN`, `CASE`, `COALESCE`)
  have no lowering proof to restate: no arithmetic, a lane-wise compare or blend. And the extensions
  whose lowerings carry their own algebra - the ISO week and week-year, `dayofyear`, `quarter`,
  `last_day`, `next_day`, `trunc`, `make_ym_interval`, `greatest`/`least`, `time_diff`,
  `time_trunc` - are not in this row; section 9 says where they go.
* **The century-then-year prefix**, the live reference variant behind `julianMap=false`: the
  default is restated; the variant is held by the suite's sweep, and section 9 says whether its
  lemma was cheap enough to add.
* **No kernel, emitter, guard or coverage row changes.** A refinement that fails because the code is
  wrong is a finding for the owner, not something this row fixes.
* **`STANDARD.md`, `DELTAS.md`, `EXTENSIONS.md`** are #854's; a correction one of them needs is
  reported, and at most a one-line pointer to the theorems is added.

### 3.6 Registered op counts

None: nothing is emitted differently.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/java/.../varka/VarkaProofFiles.java` | `refinement()` and its `render()` entry |
| `sql/varka/proofs/refinement.smt2` | rendered |
| `sql/varka/proofs/README.md` | its row |
| `sql/varka/plans/SCOPE_STANDARD_MODE.md` | 3.1, 3.2, 3.3 and 3.7 cite the theorems |
| `sql/varka/plans/m7/PLAN.md` | row 245 only |

## 5. Tests, and what each is for

* **`refinement.smt2` under `dev/varka_prove.sh --solver both`**, whole and each check alone (the
  README's step 4): every verdict its expectation. A kernel whose answer, null-ness or declines
  leave the specification fails a check; so does a site fed past its proven range.
* **`VarkaProofFilesSuite`**: the committed file is the rendering, so a constant that moves in
  `VarkaChrono`, `DateTimeConstants` or `ChronoDivide` without the proof fails here.
* **Planted faults, by hand, not committed**: the clamp dropped from the Spark-mode `add_months`
  kernel, the `INT_OVERFLOW` guard dropped from the ANSI arm, a null lane allowed to decline, the
  time guard's bound moved by one, and a rule's leap year wrong; each must fail a check.

## 6. The measurement

None in the benchmark sense. The solve times, under both solvers on the laptop, and the linters'
step before and after.

### 6.1 Predictions, registered before the run

The calibration table in `skills/testing-and-debugging.md` has no line for a solver's time; the
runner's ratio to the laptop is VARKA-241's 2.1 (`VARKA-241.md` 9.3).

1. **The file holds under both solvers** in every Spark-mode refinement, over the column contract
   for the calendar rows; standard mode is false exactly where `DELTAS.md` says "a result" -
   `overflow_wraps` (the `WRAP` kernel), `clamp_to_month_end`, `time_add_raises` - and in one place
   it does not: a date plus days, whose kernel has no guard on its result, so year 10000 is an
   answer where the standard raises (`proleptic_range`, which `DELTAS.md` calls a range).
2. **Under 250 checks; under 8 s under Z3 and under 8 s under cvc5** on the laptop with cores 12
   to 23 loaded, so the linters' step grows by about 17 s on the runner. The estimate is the
   heaviest spiked check (the era's tail, 1.9 s under Z3), the rest linear over a few variables.
   If Z3 is the slow one by more than double, the file joins `CVC5_IN_LINT`.
3. **The column contract is load-bearing and the proof says so**: without it, the unguarded
   `year(d)` is not a refinement of the Spark-mode definition (`proleptic_range` makes any int32 day
   a date), and the check that shows it is `sat` at the first day past
   `NARROW_DECOMPOSE_MAX_DAYS`.
4. **`VarkaProofFilesSuite` grows by under a second**; the gate and the nightly by the solver times
   of 2 alone (the nightly runs both solvers).
5. Every planted fault of section 5 fails at least one check.

## 7. Risks

1. **The refinement restates the emitter by hand.** A kernel written in `java.smt2` is a second
   implementation, and could agree with the specification while the emitted code does not. The
   lowering proofs and the emitter suites (the scalar twins, the sweeps) hold the code to the same
   forms; this row says which form each refinement assumes, with the IR `dev/varka_emit.sh`
   printed (2.3).
2. **A lemma instance asserted in the wrong form** would make a refinement vacuous. Each refinement
   keeps its query-1 `sat`, and each lemma is its own `unsat` check over symbolic variables.
3. **cvc5's fragility** (the README's step 4): a check that holds alone comes back `unknown` in a
   run. The file is run whole and each check alone.
4. **The linters' minute.** If the step passes it, the file is the one to move to the nightly.

## 8. Sequencing

1. This plan, and row 245 marked Planned.
2. `refinement()` and the rendered file, the README row; run under both solvers.
3. The register's citations, section 9 and the row's outcome.

## 9. Outcome

Written on 16 October 2026.

### 9.1 What was built

`sql/varka/proofs/refinement.smt2`, rendered by `VarkaRefinementFile` through `VarkaProofFiles.render`,
216 checks under `ALL`:

* **The calendar, rules to formula**: the four rules of 2.2 and C1's leap year, for every year, and
  `spec.days` pinned to `LocalDate.toEpochDay` at 17 dates from the int32 day count's ends through
  year 0, 1582 and 10000.
* **The lemmas**: the era's tail (1a over its quotients, split by century, 1b tying the quotients to
  the divisions, 1 their composition), the 400-year period (2), the decomposition over `[NARROW_MIN_DAYS,
  NARROW_DECOMPOSE_MAX_DAYS]` (3), injectivity (4), the inverse equal to `spec.days` over years
  -15199 to 44447 (5a, 5b), `emitAddMonths`'s month length (6), its month arithmetic (7), the count
  an int (8); and for the time and the week, the long lane's division as the floor (T), the
  remainder of sixty (S), `floorMod(n, 7)` (F).
* **The sites**: every dividend fed to `ERA_NARROW`, `JULIAN_CENTURY`, `JULIAN_YEAR`, `DAY_OF_MONTH`,
  `MONTH_ARITH`, `MONTH_START`, `YEAR_OF_ERA_400` and `YEAR_OF_ERA_100` lies in the shape and range
  `chrono_divide.smt2` proves the site over, read from `ChronoDivide`; every year fed to the leap hash
  and the inverse, by `add_months` under its guard and by `make_date` under its, is one they are proven
  for.
* **The refinements**, five queries each, for 3.4's table: 4.1's four operators in three arms each,
  `abs`, the calendar fields, `d + ym` and `d - ym` in both modes, `date_add`, `d + CAST(i AS INTERVAL
  DAY)` in both modes, `datediff`, the three days of the week, `make_date` in both ANSI settings,
  `extract(YEAR FROM ym)`, `hour`/`minute`, `second`, `t - t2`, `time_from_micros`, `t + dt` in both
  modes; and `SCOPE_STANDARD_MODE.md` 3.2's bound as a theorem.

`SCOPE_STANDARD_MODE.md` 3.1, 3.2, 3.3 and 3.7 cite the theorems; `proofs/README.md` has the file's row.

### 9.2 What the proofs found

Three things the specification work had not shown, none of them changed here:

1. **The column contract is load-bearing, and Spark breaks it.** A bare date column is taken to hold
   0001-01-01 to 9999-12-31 and is not checked, so `year(d)` over one fuses with no guard. Under
   `proleptic_range` Spark holds any int32 day, and the check "the column contract is load-bearing"
   is `sat`: the first day past `NARROW_DECOMPOSE_MAX_DAYS` feeds the era step past its proof. The
   scalar twin agrees on the consequence: `VarkaChrono.narrowed(14766814)` is 42400-03-02 where
   `java.time` says 42400-03-01, and at day 100,000,000 it gives month 31848. A cached column of such
   dates read by a fused `year(d)` would be a wrong answer with no fallback; that the cache path
   reaches the unguarded kernel is read from the IR and `docs/sql-varka.md`, **not run end to end**.
2. **Three guards decline batches Spark does not need declined, on rows whose other operand is
   null.** `d + CAST(i AS INTERVAL DAY)`'s input bound, `d - ym`'s negation and `t + dt`'s one-day
   interval guard each read one operand's validity, so a row whose other operand is null and whose
   bounded operand is out of range declines the batch, where Spark evaluates nothing on that row. Query 5 is `sat` at exactly those rows and `unsat` with
   the other operand valid: a decline costs the batch's speed, never an answer.
3. **`EXTENSIONS.md` 1 says `datediff` "never wraps"; it does.** Two int32 day counts differ by up to
   2^32 - 1; Spark's `DateDiff` is an `Int` subtraction and wraps, and so does the kernel. Inside the
   column contract the definition holds; outside it the check is `sat`. The sentence is #854's, now on
   master; corrected in this row's pull request.

And the standard mode shows what `DELTAS.md` predicted, plus two: `overflow_wraps`,
`clamp_to_month_end` and `time_add_raises` are the witnesses of results no guard buys;
`proleptic_range` is one too for `d + ym` and `d + CAST(...)`, because their kernels have no guard on
the result; and `interval_precision_unbounded` for the cast, whose input bound is Spark's
106,751,991 days and not the standard's two digits.

### 9.3 Where the plan was wrong

* **The solvers' time is the form's, not the claim's.** The first rendering held every check but
  took Z3 98 s, with three checks past a minute, and cvc5 answered `unknown` in a run on checks it
  decides alone in milliseconds. What fixed it: lemma 1a over declared quotients and split by
  century (Z3 three times faster than over `div`); `add_months`'s recomposition and day count as
  unknown functions `D` and `KC`, constrained only by the instances of lemmas 5 and 8, with their
  definitions given only to the checks that expect `sat` (an unsat check that holds with a function
  unknown holds of its definition); `add_months` restated over the prefix's fields, which the
  calendar fields' refinement proves are the specification's date; lemma 5 in two halves, by
  January and February; and the time and week divisions through quotient and remainder variables
  with lemmas T, S and F. Each of these is in the file's comments where it applies.
* **The century-then-year prefix**, the reference variant, is not restated (3.5): the default took
  the time there was.
* **The renderer is its own class**, `VarkaRefinementFile`, called from `VarkaProofFiles.render`,
  where 3.1 and 4 named a method `VarkaProofFiles.refinement()`: the file is 900 lines, and
  `VarkaProofFiles` was already 2,000.
* **The timings moved with the machine.** The same file took Z3 3.7 to 6.8 s in the afternoon's
  first runs and 14 to 16 s an hour later, when the unchanged `range_calendar.smt2` took 20 s against
  its 14 s and `chrono_divide.smt2` 1.2 s against 0.5 s: other agents' load on cores 12 to 23. Read
  the file's time against those two, about three quarters of `range_calendar.smt2`'s.
* `SCOPE_STANDARD_MODE.md` 3.1 described a seven-op lowering of `extract(YEAR FROM ym)`; the kernel
  is now VARKA-149's multiply-high, exact over all of int32, and the entry says so.

### 9.4 The predictions, scored

1. **Half held.** Every Spark-mode refinement holds in soundness (queries 2 to 4), over the column
   contract for the calendar rows; guard completeness (5) fails for the three guards of 9.2 item 2,
   which the prediction did not foresee. Standard mode fails where predicted and in two more places
   (9.2's last paragraph).
2. **Held, at the first runs' load**: 216 checks after the review (209 before it); 3.7 to 6.8 s
   under Z3 and 5.8 to 6.7 s under cvc5, whole, at load 25 to 35 on cores 12 to 23, and 14 to 16 s
   and 24 to 32 s an hour later on a slower machine (9.3); every check alone under both solvers (cvc5 `--incremental`, as the runner
   invokes it) meets its expectation, the slowest 2.6 s. The linters' run on the laptop went from
   42.8 s to the same plus 3.7 to 7.4 s, so 8 to 16 s on the runner by VARKA-241's ratio of 2.1; to be
   scored against the pull request's CI. Its line is in the calibration table. **What decides the
   linters' job is the slowest check, not the total**: `--lint` gives each check 10 s under Z3, and
   the slowest alone is lemma 5a, 2.3 to 2.7 s on the laptop at load 15 to 30 (5.9 s once at the
   heaviest load), so about 5 to 6 s on the runner by the ratio of 2.1: inside the limit, with less
   than a factor of two to spare. Splitting it by month or by year range moved the time between Z3
   and cvc5 rather than removing it (January alone takes cvc5 9 s under `--incremental`), so it is
   whole. After the review, `d + ym` and `d - ym`'s query 1 is pinned to 2024-01-15 and one month,
   which took their model searches from 1.4 to 3.5 s to under half a second.
3. **Held**: `sat` at day 14,766,814.
4. **Held**: `VarkaProofFilesSuite`'s one test takes 0.22 to 0.69 s with the new file rendered.
5. **Held**: dropping `add_months`'s clamp fails both `d + ym` and `d - ym`'s query 4; dropping the
   ANSI overflow guard fails `a + b`'s query 2; letting `make_date`'s year guard fire on a null lane
   fails both arms' query 5; moving the time sum's bound by one fails query 2 in Spark mode and 4 in
   standard mode; and a leap year without the century rule fails two rules and lemma 4.

### 9.5 Added test time

* **The gate** (`VarkaProofFilesSuite`, catalyst): within the suite's 0.22 to 0.69 s.
* **The linters' "Varka proofs" step**: plus 3.7 to 7.4 s under Z3 on the laptop in the first runs
  (14 to 16 s when the machine ran slow), about three quarters of `range_calendar.smt2`'s time; 8 to
  16 s on the runner by VARKA-241's ratio.
* **The slowest check** under Z3 alone, lemma 5a: 2.3 to 2.7 s on the laptop, about 5 to 6 s on the
  runner against the 10 s per-check limit (9.4 item 2).
* **The nightly's prove step** (both solvers): plus about 13 s on the laptop at the first runs'
  load, 40 to 48 s at the slower one.

### 9.6 What this leaves

* **For the owner**: the column contract (9.2 item 1): check it per batch, guard the calendar
  nodes over bare columns, or record that Varka assumes it; and an end-to-end reproduction first.
* **The three needless declines** (9.2 item 2): reading the node's word, the AND of both operands',
  rather than one operand's, would make query 5 hold; not done here, since no answer changes.
* **Not restated**: `STANDARD.md` 4.2 to 4.9 (no lowering proof to restate), the extensions of 3.5
  (the ISO week and week-year, `dayofyear`, `quarter`, `last_day`, `next_day`, `trunc`,
  `make_ym_interval`, `greatest`/`least`, `time_diff`, `time_trunc`), and the century-then-year
  prefix. Each would reuse the lemmas here.

### 9.7 The self-review

`/code-review high` on the branch found ten things; verified and fixed:

* **`make_date`'s month length was restated from the scalar twin**, `30 | (c ^ (c >>> 3))`, where
  `emitMakeDate` emits `30 | (c - (c >>> 3))`; the table is now evaluated from the emitted form (the
  two agree on every month, so the rendered table did not move).
* **`d - ym` had no standard-mode refinement** though 3.4 listed one; added, failing at its own
  `clamp_to_month_end` and `proleptic_range` witnesses and holding outside them.
* **The `extract(DAYOFWEEK_ISO ...)` disjunct followed from `weekday`'s**: dropped, and the title says
  the ISO row is weekday's tail plus one in the emitter, so its refinement follows.
* **3.2's theorem claimed more than it compares**: it is a comparison of constants, and now says so.
* **The column-contract check is true by construction** of `ERA_NARROW`'s range: its text now names
  the dividend, 20,161,386, at which `chrono_divide.smt2` shows the magic wrong, which is where the
  evidence is.
* **Query 5 is vacuous where the bound is the guard's own**, as for `make_date`'s years,
  `add_months`'s counts and `time_from_micros`: the header now says that 5 shows a kernel declines
  nothing but what its guards' bounds exclude, not that a bound is as wide as Spark's answers.
* `d - ym`'s setup sliced `d + ym`'s by position; the shared tail is its own list. `pinDates` returns
  `List.of`; the two records copy their lists and `Departure` refuses a query outside 2 to 5;
  `datediff`'s operands reuse `INT_AB`.

After the advisor's read of the review: query 1 of the four `add_months` refinements pinned to one
input, and lemma S's instance asserted under its own premise, as lemma 5's is.

Not taken: dropping "lemma 1, lemmas 1a and 1b together", a rewriting step the solver discharges in
under a second, which keeps lemma 3's hypothesis a checked instance rather than an assumption.

### 9.8 A risk the run showed

Risk 4 of section 7 is nearer than it read. At 13:17 on 16 October, with load 30 on the laptop, the
unchanged `range_calendar.smt2` took 20 s and answered `unknown` on one check under Z3 (its `add_months`
check with a negative count), the per-check limit passed. The linters' step's per-check margin is
thin for that file today, before this one; the runner is not loaded as the laptop was, but a slower
runner would show it there first.
