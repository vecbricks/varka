# VARKA-103: Day-time interval arithmetic, products, quotients, extracts and `make_dt_interval`

## 0. What exists now (admission check)

From master at `5cdb032b9ef` (30 October 2026). Varka paths are under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named;
Spark's under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/` unless named; Guava
33.6.0 (the version `pom.xml:207` pins) was read in its sources jar, the JDK in
`/home/max/proj/openjdk-build/jdk25`. The row's text is the milestone plan's row 103 and its 2.6
(last paragraph), and `m5/PLAN.md` 2.38. Numbers marked **[A]** are from
`results/VARKA-103-admission.txt`, a Java program and its output: the planned constructions in
scalar Java against the calls Spark's code makes, sampled, not proved.

**Spark's answers.** Every day-time interval form below raises in every evaluation mode: none has
a LEGACY wrap or an ANSI switch, and the `try_` spellings over an interval are `TryEval`. So
LEGACY, ANSI and TRY collapse to one kernel per form, and TRY is item 12 (the casts of 2.7 aside).

1. **`dt + dt`, `dt - dt`, `-dt`** are `IntervalMathUtils.addExact`, `subtractExact` and
   `negateExact` on the long (`expressions/arithmetic.scala:466-467`, `:564-565`, `:99`, the
   codegen `:88-92`, `:314-319`). They raise `INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION`
   (`try_add`, `try_subtract`) and `.WITHOUT_SUGGESTION` for the negation, built with
   `context = null` (`util/IntervalMathUtils.scala:26-46`), so neither engine prints a SQL
   context for them. The class reads "Integer overflow while operating with intervals.", with
   "Use <functionName> to tolerate overflow and return NULL instead." or "Try devising
   appropriate values for the interval parameters." (`error-conditions.json:3699`).
2. **`abs(dt)`** is `LongExactNumeric.abs` interpreted and `c < 0 ? MathUtils.negateExact(c) : c`
   generated (`arithmetic.scala:180`, `:193-195`; `types/numerics.scala:110`), whatever
   `failOnError` says: at `Long.MIN_VALUE` it raises `ARITHMETIC_OVERFLOW` ("long overflow",
   `error-conditions.json:129`), not the interval class of item 1.
3. **`dt * k`, `k * dt`** arrive as `MultiplyDTInterval(dt, k)` from either side
   (`analysis/BinaryArithmeticWithDatetimeResolver.scala:163-164`). By the factor's type
   (`expressions/intervalExpressions.scala:697-705`, codegen `:709-722`):
   - integral: `Math.multiplyExact(micros, k.longValue())`, a bare `java.lang.ArithmeticException`
     with no error class;
   - `FLOAT`, `DOUBLE`: `DoubleMath.roundToLong((double) micros * k, HALF_UP)` - the long
     converted to a double first (rounding to nearest above `2^53`), one IEEE multiply, then
     Guava's `roundIntermediate`: `z = rint(x)`, and if `abs(x - z) == 0.5` then
     `x + copySign(0.5, x)`, a NaN or an infinity raising, then the range test
     `MIN_LONG_AS_DOUBLE - z < 1.0 & z < 2^63` and `(long) z` (`DoubleMath.java:54-98`,
     `:157-169`), its `ArithmeticException` carrying no error class;
   - decimal: `Decimal.set(micros) * k` under `MathContext(39, DOWN)`, then `setScale(0,
     HALF_UP).longValueExact()` (`sql/api/.../types/Decimal.scala:523-524`, `:597`).
4. **`dt / k`** is `DivideDTInterval` (`BinaryArithmeticWithDatetimeResolver.scala:171`,
   `intervalExpressions.scala:854-924`). Before dividing, in every mode: a zero `k` raises
   `INTERVAL_DIVIDED_BY_ZERO`, "Division by zero. Use `try_divide` to tolerate divisor being 0
   and return NULL instead." (`:747-756`, `-0.0` included, since `num == 0`;
   `error-conditions.json:3717`), and for an integral `k` the pair `micros == Long.MIN_VALUE`,
   `k == -1` raises - with **different classes in the two engines**: the interpreter's
   `checkDivideOverflow` raises `INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION` (`try_divide`,
   `:733-745`), the generated code `overflowInIntegralDivideError`, `ARITHMETIC_OVERFLOW` with the
   query context (`:889-893`; `errors/QueryExecutionErrors.scala:344-346`). The quotient itself:
   integral `LongMath.divide(micros, k, HALF_UP)` (`:869`; `LongMath.java:382-433`, the
   truncated quotient moved one step away from zero when `2|r| >= |k|`); floating
   `roundToLong(micros / (double) k, HALF_UP)` (`:874`); decimal `Decimal./`, to 39 fractional
   digits `DOWN`, then `HALF_UP` to scale 0 (`:870-872`; `Decimal.scala:526-533`).
5. **The extracts** (`intervalExpressions.scala:100-123`, parsed at `:139-158`, the unit admitted
   only inside the type's start and end fields) call `IntervalUtils` (`util/IntervalUtils.scala:
   47-68`): `getDays = (int) (x / D)`, `getHours = (byte) ((x % D) / H)`, `getMinutes = (byte)
   ((x % H) / M)`, `getSeconds = Decimal(x % M, 8, 6)`, with `D = 86,400,000,000`,
   `H = 3,600,000,000`, `M = 60,000,000`, Java's truncating `/` and `%`. None can fail.
6. **`make_dt_interval(days, hours, mins, secs)`** takes three ints and a `DECIMAL(18, 6)`
   (`intervalExpressions.scala:536-537`); the short forms fill `Literal(0)` and
   `Literal(Decimal(0, 18, 6))` (`:519-527`). `makeDayTimeInterval` (`IntervalUtils.scala:786-801`)
   starts from `secs.toUnscaledLong` and does `addExact(micros, multiplyExact(days, D))`, then the
   same with `hours * H`, then `mins * M`, each partial sum checked, any failure raising
   `INTERVAL_ARITHMETIC_OVERFLOW.WITHOUT_SUGGESTION` with the query context
   (`QueryExecutionErrors.scala:737-744`). Only the days product can overflow:
   `multiplyExact(days, D)` raises at `+-106,751,992` and not at `+-106,751,991` **[A, 6]**, while
   `Int.MIN_VALUE * H` is -7730941132800000000 and `Int.MIN_VALUE * M` far less **[A, 6]**. A
   partial sum can overflow where the final one fits: days 106,751,991, hours -1, secs
   14454775808 micros has a true sum that fits a long, and Spark raises **[A, 6]**.
7. **`dt div dt2`** is `IntegralDivide`, whose `inputType` takes the day-time interval
   (`arithmetic.scala:970-971`): Java's truncating long `/`, a `BIGINT`, a zero divisor null
   under LEGACY and `DIVIDE_BY_ZERO` under ANSI, and no `MIN / -1` check for an interval left
   operand (`:965-968`), so `Long.MIN_VALUE div -1` is `Long.MIN_VALUE` **[A, 5]**. Row 30 sends
   the interval `div` here (`VARKA-30.md` 1, 7).
8. **What Varka has.** A day-time interval column and literal on the long lane
   (`VarkaTimeCompiler.java:159-165`), its comparisons, null tests and choices (coverage family
   "Long-lane predicates" and "Long-lane choice"), and its SECOND-ended relabel cast, every
   coarser end declining "which truncates" (`:185-195`). No arm claims `Add`, `Subtract`,
   `UnaryMinus` or `Abs` over it, nor `MultiplyDTInterval`, `DivideDTInterval`, `MakeDTInterval` or
   the extracts, except `ExtractANSIIntervalDays` inside `d + CAST(i AS INTERVAL DAY)`
   (`VarkaChronoCompiler.java:521-532`). The nodes it needs exist: `IntArith` `ADD`/`SUB` in
   `FAIL` at the long lane (row 339's plan uses them), `IntNeg` `FAIL` at the long lane
   (`varka/VarkaVectorIR.java:701`), `GuardedRange` (`:536`), `WidenLane` and `NarrowLane`,
   `BitOp`, `IfElse`, `Compare`, and `ConstDivide` at the long lane under a stated bound of at
   most `2^52` (`:764-849`).
9. **The year-month forms this row mirrors** (`VarkaIntervalCompiler.java:183-209`): `+`, `-`,
   negation, `abs` (a compare, `IntNeg` `FAIL`, a blend, `:282-298`), `make_ym_interval`, the
   multiply by an int (`:345-366`, which declines over an unbounded column because `arithOver`
   refuses every checked `MUL` off the narrow lanes, `VarkaNodeCompiler.java:1119-1122`), and
   row 89's `ym / k` for an integral literal (`:385-430`: `k = 0` and `k = -1` decline, `k = 1` is
   the operand, the rest `ConstDivide`, the wrapping remainder and a step away from zero when
   `|r| >= ceil(|k| / 2)`). Row 414 holds `ym / -1`.
10. **The shift split is row 338's, built.** `floorDivLong` (`VarkaNodeCompiler.java:787-810`):
    `D = 2^13 * 10,546,875` **[A, 1]**, an arithmetic shift by 13, a down step of `1 - odd` for a
    negative value, and a `ConstDivide` by 10,546,875, proven over every long in
    `sql/varka/proofs/long_divide.smt2`. It floors; Spark's `getDays` truncates. Row 332's plan
    (2.4) designs the truncation over it: `trunc(x / D) = floorDivLong(x + ((x >> 63) & (D - 1)))`,
    three lane operations, the bias unable to overflow. The check: 50000022 longs, the edges among
    them, 0 differ from Java's `x / D`, the largest shifted dividend 2^50 in magnitude, a quarter
    of `ConstDivide`'s bound, and the quotient always an int (at most 106,751,991) **[A, 1]**. No
    Granlund-Montgomery multiply is involved: 340 found it needs a 64-bit high multiply the Vector
    API lacks (`VARKA-340.md` 0).
11. **One full-range division serves every extract.** With `q = trunc(x / D)` and the wrapping
    `r = x - q * D` (`|r| < D`, so under `2^37`), `x % H == r % H` and `x % M == r % M` because
    `H` and `M` divide `D` and `r` keeps `x`'s sign; so `getHours` is `r / H`, `getMinutes`
    `(r % H) / M`, `getSeconds` `r % M`, each a `ConstDivide` bounded by `D` with no guard
    **[A, 2]**: 0 of 50000022 differ on each. More: `trunc(x / u) = q * (D / u) + r / u` for every
    divisor `u` of `D`, over every long - checked for `H`, `M`, `10^6`, 1000, 60 and 24, 0
    differ **[A, 2]** - and `D` has 504 divisors, 75 of them from 2 to 1000 **[A, 2]**. This
    answers, for the units of a day, what 332's section 7 leaves to row 30 ("a full-range
    day-time division below a day").
12. **TRY is `TryEval`, which no arm claims.** `try_add`, `try_subtract`, `try_multiply` and
    `try_divide` over an interval become `TryEval(Add(...))` and kin (`expressions/TryEval.scala:
    90-97`, `:135-141`, `:229-235`, `:268-274`); 342 and 340 decline it by name, and row 481's
    `NULL` guard (`VARKA-481.md` 3.1) serves `GuardedRange`, not a caught exception. So the
    arithmetic needs nothing of 481 and builds no TRY form; only the parked casts of 2.7, whose TRY
    spelling is `Cast` itself and nulls, use 481's mode (103c).
13. **The other factors and divisors lean on other rows' nodes.** A floating factor's lowering,
    the multiply or divide and its `HALF_UP` conversion, is row 342's 2.4 after 343's conversions
    (`VARKA-342.md` 2.4); its form agreed with Guava on every cell of **[A, 7]** and on 50000000
    drawn pairs, and the named tests at the ties, `2^53` and the long edges are this row's (3.3,
    section 8, point 2). A decimal column factor: row 349's plan (section 7) marks the products
    unclaimed with 361 and 363 the suggested home, and the quotients go to 363, which claims them
    and declines them until a variable 128-bit division is planned (`VARKA-363.md` 2.5, 8 question
    1). A column divisor or factor needs row 30's `IntDivide` or 104's checked long multiply, and a
    literal divisor that does not divide `D` row 30's constant forms (`VARKA-30.md` 2.4, 7). A
    decimal literal in SQL is the common spelling: `dt * 1.5` is a decimal product, not a double
    one, and this row takes it (2.4, section 8, point 7).
14. **`java.time` is not an oracle for negative values.** `Duration` keeps floor seconds and a
    non-negative nano part, so at `x = -D + 1` `toDays()` is -1 where `x / D` is 0; over 2000000
    drawn longs `toDays` differs 66144 times and the hour, minute and second parts as often
    **[A, 3]**. The done-when's "`java.time` the oracle at the edges" holds for the products'
    and the sums' edges only through `Duration.plus` and `multipliedBy`, which raise at the long's
    edges; the extracts' oracle is Spark's own row engine and `BigInteger`.
15. **The outputs are other rows'.** `extract(DAY)` is an int from a long-driven kernel,
    `NarrowLane` as 338's `CAST(ntz AS DATE)`; hours and minutes are `ByteType`, and a narrow
    value under a long kernel is refused (`varka/VarkaKernelPlan.java:297-300`) until the
    narrowing store of a long value, `L2I` then the narrow store, which 332's 2.5 writes once for
    itself and 343; seconds is a `DECIMAL(8, 6)`, row 157's 64-bit store.
16. **The baseline exists for none of it.** `VarkaLongLaneThroughputBenchmark` times a day-time
    comparison filter and `least` only; no class times the arithmetic, the extracts or
    `make_dt_interval`.

### What differs from the row text and the milestone plan

- **The truncation is the biased floor, not the magnitude.** The row's "over the magnitude with
  the sign restored" needs a long `abs` (the Java fallback below AVX-512, `VARKA-348.md` 0) and a
  logical shift: with the arithmetic shift the row implies, `Long.MIN_VALUE`'s magnitude wraps and
  the quotient comes out 106751991 against -106751991, 4 of the drawn longs differing **[A, 1]**.
  The bias form of 332's 2.4 is exact and uses 338's `floorDivLong` unchanged.
- **Hours and minutes need 332's long-to-byte store, not 329's.** 329's byte store takes an
  int-lane value; this kernel is driven at the long lane (item 15).
- **"Each division exact under its stated bound or guarded"** holds with no guard for the extracts
  and for `dt / k` with `k` dividing `D` (item 11). No `2^52`-guarded quotient is built: a literal
  divisor that does not divide `D` declines naming row 30 until 30's constant forms land, which
  claim `/` and `%` over a literal divisor as over a column one (`VARKA-30.md` 7), and then 103d
  uses them (section 8, point 4).
- **The `HALF_UP` double-factor tests stay here** (item 13): 342's 2.4 builds the lowering, and the
  ties at both signs, `2^53` and the long edges are this row's named tests (section 8, point 2).
- **The row's magnitude form is wrong at `Long.MIN_VALUE`** (the first bullet): row 103's line in
  `PLAN.md` carries the dated correction.
- **The casts parked here are this row's** (section 8, point 5): the coarser day-time casts from
  332 and the decimal to and from interval casts from 350, as 103c.
- **`m5/PLAN.md` 2.38's correction is already there.** The dated note of 14 October 2026
  (`m5/PLAN.md:2379-2386`) is the done-when's "corrected by a dated note"; nothing is added.
- **`java.time` is the oracle at the arithmetic edges only** (item 14).
- **The wave edges are wider than G1's "after 28 and 329":** 332b (the byte store, and the bias
  helper, 332's, unless 103a lands first), 347 and 157 (the seconds and the decimal casts), 481
  (the TRY casts), 342 (the double factor), 30 (column divisors, other literal divisors, `div`) and
  104 (a column factor). G1 runs after F3 already, so only 332, 481, 30 and 104 add an edge; the
  lead edits the waves table (section 8, point 9).

## 1. Goal and non-goal

Every day-time interval form of `m5/PLAN.md` 2.38 fuses with Spark's answers and declines a batch
exactly where Spark raises: `+`, `-`, negation and `abs`; `dt * k` and `dt / k` for an integral
literal `k` (a divisor of `D` first, any other after 30), and for an integral column once 104 and
30 give the checked multiply and the division; `ym * k` for an integral literal; `dt * c` and
`dt / c` for a decimal literal `c`; the named tests of a double factor over 342's lowering;
`extract` of `DAY`, `HOUR`, `MINUTE` and `SECOND`; `make_dt_interval` with its partial sums in
Spark's order; `dt div` a literal or a column; and the interval casts parked here: day-time to a
coarser day-time end, and decimal to and from both interval types. Not this row: a floating
factor's lowering (342), a decimal column factor (361, 363), `TryEval` (no row; section 8's open
points propose a scope item), the year-month forms beyond `ym * k`.

## 2. Design

All arms go into a new day-time half of `VarkaIntervalCompiler`, gated on `DayTimeIntervalType`
as the year-month arms are on theirs, under the same family switch
(`spark.sql.codegen.varka.family.interval.enabled`). No new IR node.

### 2.1 The checked arithmetic

| form | lowering | declines a batch where |
|---|---|---|
| `dt + dt2`, `dt - dt2` | `IntArith(ADD or SUB, FAIL)` | a sum or difference overflows |
| `-dt` | `IntNeg(FAIL)` | `Long.MIN_VALUE` |
| `abs(dt)` | `IfElse(dt < 0, IntNeg(FAIL, dt), dt)`, the year-month `absolute` | `Long.MIN_VALUE` |
| `+dt` | the child | never |

The mode is `FAIL` unconditionally, as for the year-month arms (`VarkaIntervalCompiler.java:
448-470`). Two units meet through a cast to the wider type; a cast to an end field at least as fine
as the source's is the identity on the value (a whole number of the source unit is a whole number
of the finer one), so it compiles to the child beside the SECOND relabel; the truncating casts
are 2.7's, in 103c. The error each engine raises is the row engine's own: the
declined batch re-evaluates the expression, so `abs` raises `ARITHMETIC_OVERFLOW` and `-dt` the
interval class (items 1, 2). Under `checkIntOverflow = false` these `FAIL` nodes lose their check
as the year-month ones do (`varka/Analysis.java:676-686`); whatever row 481 settles for that option
applies to both.

### 2.2 The products by an integral literal

`multiplyExact(x, k)` raises exactly outside `[lo, hi]`, with `lo = ceil(MIN / k)` and
`hi = floor(MAX / k)` for `k > 0` and the two swapped for `k < 0`, computed in the compiler with
`BigInteger` and clamped to the long: a `GuardedRange(x, lo, hi)` then `IntArith(MUL, WRAP)`, 39's
composition (`VarkaChronoCompiler.java:340-358`). Over 3200160 cells at sixteen `k`, both signs,
`Long.MIN_VALUE`, `Long.MAX_VALUE` and the int edges among them, the guard and `multiplyExact`
never disagree; the symmetric bound `+-(MAX / |k|)` would wrongly decline 4 admitted cells, those
with `x * k = MIN` **[A, 4]**. `k = 0` is the literal 0 (Spark multiplies and never raises), `k = 1`
the operand, `k = -1` the checked negation of 2.1. A `TINYINT`, `SMALLINT` or `INT` literal is its
long value. `k * dt` is the same node.

**`ym * k` for an integral literal** (section 8, point 6) takes the same guard at the int lane:
`Math.multiplyExact` on the int raises exactly outside `[ceil(Int.MIN / k), floor(Int.MAX / k)]`
for `k > 0`, the two swapped for `k < 0`, so `GuardedRange` then `IntArith(MUL, WRAP)` at the int
lane replaces today's decline over an unbounded column (item 9). A `BIGINT` literal is Spark's
`multiplyExact` on longs then `toIntExact` (`intervalExpressions.scala:645-648`), which raises
exactly where the true product leaves the int, so it is the same guard with the bounds computed in
`BigInteger` and clamped to the int.

### 2.3 The extracts

    b = BitOp(AND, BitOp(SHR, x, 63), D - 1)             the bias, 0 or D - 1
    q = floorDivLong(IntArith(ADD, WRAP, x, b), D)       trunc(x / D), every long
    r = IntArith(SUB, WRAP, x, IntArith(MUL, WRAP, q, D))
    DAY    = NarrowLane(q, INT)
    HOUR   = narrow-to-byte(ConstDivide(r, H, bound D))
    MINUTE = narrow-to-byte(ConstDivide(IntArith(SUB, WRAP, r, MUL(ConstDivide(r, H), H)), M, D))
    SECOND = r - ConstDivide(r, M, D) * M                as DECIMAL(8, 6) through 157's store

`q`, `r` and the hour quotient are shared nodes, so a query extracting several fields computes
the chain once. The narrowing to an int is exact since `|q| <= 106,751,991`; to a byte, since
`|hours| <= 23` and `|minutes| <= 59`. The bias helper is row 332's: its 2.4 claims the biased
floor `floorDiv(x + ((x >> 63) & (d - 1)), d)` for a DAY end, with its claim in `long_divide.smt2`
beside 338's (`VARKA-332.md` 2.4, 3.3). 332b builds it; if 103a lands first, 103a builds it to
332's form and API, a helper beside `floorDivLong` taking the long node and a divisor that
`floorDivLong` admits, with the same `long_divide.smt2` claim, and 332b reuses it (section 8,
point 1). 332's plan names no method; this plan writes it `truncDivLong(x, d)`.

The new arm claims `ExtractANSIIntervalDays` where it is a node of the walk; inside `DateAdd` the
extract is the offset, which the date arm reads itself without recursing
(`VarkaChronoCompiler.java:477-485`), so no node has two claimants (`VarkaFamilyChainSuite` holds
that). That arm still declines a day offset other than `CAST(i AS INTERVAL DAY)` ("day interval is
not an int column cast to days", `:527`), so `d + dt` over a stored `INTERVAL DAY` column keeps
declining; admitting this row's quotient there under the date arm's day guard is a follow-up for
the calendar family, not built here, and section 8's open points propose its home.

### 2.4 The quotients by an integral literal

`LongMath.divide(x, k, HALF_UP)`: `k = 0` and `k = -1` decline at compile time, the first because
every valid row raises, the second as row 89 declines `ym / -1`, naming row 414 (item 4 is new
evidence for it, section 8, point 8); `k = 1` is the operand. Otherwise, with `q` the truncated
quotient and the wrapping `r = x - q * k`:

    bump = IfElse(|r| >= ceil(|k| / 2) and r != 0, x and k of one sign ? 1 : -1, 0)
    result = q + bump

where `|r|` is the compare-negate-blend of 2.1 (`|r| < |k|`, so it cannot overflow) and
`ceil(|k| / 2)` a compile-time constant, `(|k| >> 1) + (|k| & 1)`, and `2^62` for
`k = Long.MIN_VALUE`. The constant must not be `(|k| + 1) / 2`: at `k = Long.MAX_VALUE` it
overflows, and the admission program's first run failed 462239 of 7600418 cells on it; with the
overflow-free form 0 of 7600418 differ from `LongMath.divide` over nineteen divisors **[A, 5]**.
`q` and `r` come from:

- **`k` dividing `D`** (`|k|` among its 504 divisors), in 103a: `q = q_D * (D / k) + r_D / k`,
  `r = r_D % k` over 2.3's `q_D`, `r_D`, exact over every long with no guard; 4800264 cells, 0
  differ **[A, 5]**. This is the one quotient this row builds from its own nodes.
- **any other `k`**, in 103d: until row 30 lands the arm declines naming row 30, which claims `/`
  and `%` over a literal divisor as over a column one (`VARKA-30.md` 7); no `2^52`-guarded
  `ConstDivide` is built here (section 8, point 4). Once 30 lands, `q` and `r` are its constant
  forms for a `BIGINT` dividend (`VARKA-30.md` 2.4), whichever arm its `longConstDivide` option
  selects, and the step above follows; `k = Long.MIN_VALUE` is 30's `IfElse(x = MIN, 1, 0)` for
  `q`, with `r = x` elsewhere. Only here can `|k|` reach `Long.MAX_VALUE`, so the half constant's
  overflow above, and its planted fault (3.7), live in 103d.

**A decimal literal factor or divisor** (`dt * 1.5`, `dt / 1.5`; section 8, point 7), in 103a. For
`c = u / 10^s`, `u` its unscaled long, Spark's `Decimal` path (item 3) is `HALF_UP(x * u / 10^s)`
and `HALF_UP(x * 10^s / u)`: 2.2's guard on `x * u` or `x * 10^s`, a wrapping multiply, then the
step above by `10^s` or `u`, all 64-bit; 2400176 cells over eight literals agree with Spark where
the guard admits **[A, 8]**. The guard is exact only where no division follows (`s = 0` for the
product); elsewhere it can decline lanes Spark answers (it declined 1352613 products of the sample),
which the row engine computes. The division takes the day split, so the divisor must divide `D`:
`10^s` does for every scale up to 8, and a `u` that does not, or a larger scale, declines naming row
30 and joins 103d. A zero `u` declines at compile time as `k = 0` does, and `u = -1` at scale 0 as
`k = -1`. The named tests are the double factor's tie cells of 3.3, spelled as decimal literals,
with Spark's `Decimal` path as the expected value: not **[A, 7]**'s double answers, since nothing
converts to a double here, so `(2^53 + 1) * 0.5` is a true tie and `MAX * 1.0` fits where the double
raised (its guard declines, and the row engine answers).

**A double factor** is 342's 2.4 lowering, its arm and node; this row adds only the named tests of
3.3 over it, in 103d, after 342 (section 8, point 2).

### 2.5 Column factors and divisors, and `div`

- **`dt * i` over an integral column** is a checked long multiply of a value nothing bounds: row
  104's option C (or B with AVX512DQ, `VARKA-104.md` 3.3); until it lands the arm declines naming
  104. A `TINYINT` or `SMALLINT` column needs 332's narrow load at the long width.
- **`dt / i` over an integral column** is row 30's `IntDivide(QUOT)` and `REM` at the long lane
  (`VARKA-30.md` 2.1, 2.2), `ZeroDivisor.FAIL` and `checkMinOverMinusOne` set (Spark raises on both
  in every mode), then the `HALF_UP` step as Guava writes it, wrapping arithmetic included:
  `cmp = |r| - (|i| - |r|)`, a step of `1 | ((x ^ i) >> 63)` where `r != 0` and `cmp >= 0`. Copying
  Guava's operations keeps `i = Long.MIN_VALUE`, where `|i|` wraps, right by the same wrap.
- **`dt div dt2` and `dt div` a day-time literal**: `IntDivide(QUOT)` with the zero disposal by
  mode (`NULL` under LEGACY, `FAIL` under ANSI) and `checkMinOverMinusOne` unset, since Spark wraps
  there (item 7); a literal divisor dividing `D` takes 2.4's day split, any other 30's constant
  forms, `INTERVAL '0' SECOND` declining at compile time. The result is a `BIGINT`, the same lane.

### 2.6 `make_dt_interval`

    s0 = the seconds as an unscaled long at scale 6
    s1 = IntArith(ADD, FAIL, s0, IntArith(MUL, WRAP,
             GuardedRange(WidenLane(days), -106751991, 106751991), D))
    s2 = IntArith(ADD, FAIL, s1, IntArith(MUL, WRAP, WidenLane(hours), H))
    s3 = IntArith(ADD, FAIL, s2, IntArith(MUL, WRAP, WidenLane(mins), M))

The order is Spark's and is not reassociated: each checked add declines exactly the lanes whose
partial sum Spark finds overflowing, the cell of item 6 among them; 5000000 drawn argument sets,
2708702 of them raising in Spark, give 0 differences **[A, 6]**. The hour and minute products need
no guard by the int's range (item 6); the range analysis cannot see that at the long lane, so the
`WRAP` mode is the compiler's by construction, as the `TIME` conversions' is. `s0` is the default
`Literal(Decimal(0, 18, 6))`, a constant-folded decimal literal (`1.5` is cast to `(18, 6)` and
folded), or a `DECIMAL(18, 6)` column through 347's leaf; another seconds operand is a cast to
decimal and 350's (section 8, point 3, for which row reads the literal). Overflowing lanes decline
and the row engine raises the error with its context.

### 2.7 The casts parked here (103c)

Section 8, point 5 keeps both groups here, under the interval family's switch. Spark's rules,
pinned:

- **Day-time to a coarser day-time end** (54 forms, `VARKA-332.md` 7):
  `durationToMicros(microsToDuration(x), end)` (`Cast.scala:1105-1107`;
  `sql/api/.../util/SparkIntervalUtils.scala:56-76`), which is `x - x % u` for the target end's
  unit, `D`, `H` or `M`. The round trip through `Duration` is exact over every long and never
  raises: at `Long.MIN_VALUE` the seconds equal `minDurationSeconds`, `floorDiv(Long.MIN_VALUE,
  10^6)` (`:38`), and that branch adds the negative nano adjustment to `(minDurationSeconds + 1) *
  10^6` instead of multiplying. So the lowering is 2.3's chain with no guard, one kernel for every
  mode: `x - r_D` to DAY, `x - r_D % H` to HOUR, `x - r_D % M` to MINUTE, exact by item 11. A finer
  end is 2.1's identity.
- **Decimal to day-time** (`Cast.scala:1116-1118`; `IntervalUtils.scala:939-954`):
  `d.toBigDecimal * u` exactly, for `u` the end unit (`D`, `H`, `M` or `10^6`), then `setScale(0,
  HALF_UP)` and `toLongExact`, any failure `castingCauseOverflowError` (`CAST_OVERFLOW`). For a
  `DECIMAL(p, s)` with `p <= 18` and unscaled `v` it is `HALF_UP(v * u / 10^s)`: reduce `u / 10^s`
  to `a / b` in lowest terms at compile time, then 2.2's guard on `v * a` and 2.4's step by `b`, the
  shape of the decimal literal factor with the decimal as the column. `b` divides `D` at every scale
  up to 8; another `b` declines naming row 30 until 103d.
- **Decimal to year-month** (`Cast.scala:1136-1138`; `IntervalUtils.scala:841-853`): `d * 12` for
  a YEAR end, `d` for MONTH, `setScale(0, HALF_UP)`, `toIntExact`, the same construction with the
  bounds of the int and a `NarrowLane` to the int result.
- **Day-time to decimal** (`Cast.scala:1411-1416`; `IntervalUtils.scala:930-937`): `Decimal(v /
  unit)` for a DAY, HOUR or MINUTE end, Java's truncating division and so 2.3's quotient
  (`q_D * (D / u) + r_D / u`), and `Decimal(v, 19, 6)` for SECOND; then `changePrecision` to the
  target with `nullOnOverflow = false` (`CastUtils.changePrecisionExact`, `HALF_UP` by
  `Decimal.changePrecision`), raising `cannotChangeDecimalPrecisionError`. For a target `(p, s)`
  with `p <= 18`: a guard of `|n| < 10^(p - s)` on the integral value before its multiply by
  `10^s` (guard-the-inputs, as row 360's note asks), or for SECOND a `HALF_UP` division by
  `10^(6 - s)` when `s < 6` and the `10^p` check after it.
- **Year-month to decimal** (`Cast.scala:1417-1422`): `Decimal(yearMonthIntervalToInt(ym, start,
  end))`, `v / 12` truncating for a YEAR end (a bounded `ConstDivide` at the int lane) or `v`, then
  the same `changePrecision`.

**The modes.** The coarser day-time casts cannot fail. The decimal casts raise under LEGACY and
ANSI alike (the helpers throw whatever the mode); under TRY the cast's own `catch` returns null
(`Cast.scala:1586-1597`), so a TRY form needs row 481's `NULL` mode, and only where its guard is
exact (`b = 1`, and every precision check after the value is formed); a conservative guard
declines the batch under TRY too. **Prerequisites**: 347's decimal leaf for the sources, 157's
64-bit store for the targets, 481 for TRY, and 103a's quotient. A decimal above precision 18 on
either side needs 359's lane pair and declines (section 8, open points).

### 2.8 Reference, cost, warm-up, census

No new node, so the reference evaluator, the cost model's features and the budget's weights are
unchanged; the warm-up reach suite is rerun, since the arms emit long-lane `MUL`, `SHR` and
`ConstDivide` combinations the blocks may not hold (`VarkaWarmupReachSuite`). The census's
`abs`, `negative`, `+`, `-`, `div`, `dt_times_num`, `dt_div_num`, `ym_times_num`,
`make_dt_interval`, `extract`, `date_part`, `datepart`, the four `extract_dt_*` entries and the
cast register's interval rows are updated as each lands (section 6).

## 3. Tests and proofs

1. **SMT over integers**, linear by constants, in `sql/varka/proofs/long_divide.smt2` beside 338's
   claim: the bias form equals `trunc(x / D)` over every long and its biased dividend does not
   overflow; `x - trunc(x / D) * D` equals `x rem D` and lies in `(-D, D)`; `r rem H = x rem H`
   and `r rem M = x rem M`, which also give 2.7's coarser casts; and `trunc(x / u) = q * (D / u) +
   trunc(r / u)` for `u` in `H`, `M`, `10^6` and every divisor a coverage row or a test uses. In a
   new `sql/varka/proofs/dt_arith.smt2`: the multiply guard's bounds against `multiplyExact`'s
   range, per literal used by a test, at the long lane and for `ym * k` at the int lane; the days
   guard and the hour and minute products' ranges of 2.6; the `HALF_UP` rule of 2.4 per divisor, as
   `ym_divide.smt2` does at the int lane (a symbolic divisor is nonlinear and was not solved there
   either), `Long.MAX_VALUE` among the divisors once 103d builds it; and for each decimal literal
   and each cast's `(unit, scale)` a test uses, the reduced `a / b` and its guard. Priced at
   VARKA-245's observed ratio: under 8 s per solver.
2. **Sampled, where the claim is not linear**: the column divisor's Guava sequence (2.5) against
   `LongMath.divide` over drawn pairs with `i` at 0, `+-1`, `+-2`, `Long.MIN_VALUE`,
   `Long.MAX_VALUE`; the `make_dt_interval` draw of **[A, 6]**; the decimal literal forms against
   Spark's `Decimal` path, the draw of **[A, 8]**; the decimal casts against `IntervalUtils`'s and
   `CastUtils`'s own helpers over drawn unscaled values at each `(p, s)` a coverage row uses.
3. **Named cells** through the coverage differential and an emitter suite, both widths: `dt + dt2`
   at `MAX` and `MAX - 1`; `-dt` and `abs(dt)` at `MIN` and `MIN + 1`, with the raised class of
   each (items 1, 2); `dt * k` at `lo - 1`, `lo`, `hi`, `hi + 1` for `k = 2`, `-2`, `MIN`, and
   `ym * k` at the same four for `k = 2`, `-2` and `Int.MIN_VALUE`; the extracts at `MIN`, `MAX`,
   `+-D`, `+-(D - 1)`, `+-1` and 0; `dt / k` at the ties `+-5 / 2` and `5 / -2` **[A, 5]**, and
   `dt / 7` declining naming row 30 until 103d, then fused with `2^62 / MIN` **[A, 5]** and
   `1 / Long.MAX_VALUE`, whose answer is 0; `make_dt_interval` at days `+-106,751,991` and
   `+-106,751,992` and the partial-sum cell of item 6; `dt div -1` at `MIN`, which wraps.
   - **The double factor** (103d, over 342's lowering; section 8, point 2), the cells of
     **[A, 7]** with Guava's answers: the ties `+-1 * 0.5`, `1 * -0.5`, `+-5 * 0.5`, `+-3 * 0.5`,
     `+-3 / 2.0` and `5 / -2.0` at both signs; `2^53` by `(2^53 + 1) * 0.5`, which is
     4503599627370496 by the conversion, and `+-(2^53 - 1) * 0.5`; the long edges `MAX * 1.0`,
     `(MAX - 511) * 1.0` and `MIN * -1.0` raising, `(MAX - 512) * 1.0` 9223372036854774784,
     `MAX * -1.0` and `MIN * 1.0` `MIN`; NaN and the infinities, `7 / Infinity` 0.
   - **The decimal literal factor** (103a; section 8, point 7): the same cells spelled as decimal
     literals (`dt * 0.5`, `dt * -0.5`, `dt / 2.0`, `dt * 1.0`, `dt * -1.0`), the expected value
     Spark's `Decimal` path (2.4: no conversion to a double, so these answers differ from the
     double's at `2^53` and at `MAX`), and `dt / 7.0` declining naming row 30.
   - **The casts** (103c): each coarser end at `MIN`, `MAX`, `+-(D - 1)`, `+-(H - 1)` and
     `+-(M - 1)`; decimal to day-time and year-month at a `HALF_UP` tie of each sign and at the
     first value past the long and the int; day-time and year-month to a decimal at the target's
     `10^p - 1` and the first value past it, under each mode, TRY's null included.
4. **The sweep, run by hand in the gate**: a `VarkaDayTimeSweepSuite`, tagged so the PR build skips
   it as 481's and 330's sweeps are, drawing 2^28 longs per form against Spark's own expressions on
   the row engine. No CI workflow runs it; the gate records its time.
5. **Coverage rows**, a family "Day-time interval arithmetic": `dt + dt2`, `dt - dt2`, `-dt`,
   `abs(dt)`, `dt * 3`, `3 * dt`, `ym * 3`, `dt / 24` (day split), `dt * 1.5` and `dt / 1.5`, `dt /
   7` (after 30), `dt div dt2` (after 30), the four `extract`s and `date_part`,
   `make_dt_interval(i, i2, i3)` and with a `DECIMAL(18, 6)` column, `CAST(dt AS INTERVAL HOUR)`,
   `CAST(dec AS INTERVAL SECOND)` and `CAST(dt AS DECIMAL(18, 6))`, each naming its verification
   for row 490's check (the SMT sections, or `differential`).
6. **The fuzzers**: the Spark-level grammar gains the forms over `dt` and `dt2` (row 352's
   per-type draw, `VARKA-352.md` 2.2).
7. **The planted faults**, for row 504, each under a test flag, refuted by this section's proof or
   sweep and failed by the differential suite:
   - **The magnitude form with an arithmetic shift** in place of 2.3's bias: the day quotient as
     the floor of `|x|` with the sign restored, `|x|` by `(x ^ (x >> 63)) - (x >> 63)`, which wraps
     at `Long.MIN_VALUE`. Refuted by 1's `long_divide.smt2` claim, the planted form stated beside
     the bias form as a `sat` witness at `MIN`, and by test 4's sweep, whose draw holds the edges;
     failed by 3's `extract(DAY)` cell at `MIN`, where it answers 106751991 against -106751991
     (4 of the admission's drawn longs differ, **[A, 1]**). Planted in 103a.
   - **The half constant as `(|k| + 1) / 2`**, in 2.4's step: at `|k| = Long.MAX_VALUE` it wraps
     negative, so every lane with a non-zero remainder steps. Refuted by 1's `dt_arith.smt2`
     `HALF_UP` claim at `k = Long.MAX_VALUE` as a `sat` witness; failed by 3's `1 /
     Long.MAX_VALUE` cell, which it answers 1 for Spark's 0; the admission program's first run had
     this form and failed 462239 of 7600418 cells (`results/VARKA-103-admission.txt`, its header).
     Planted in 103d, the first step where `|k|` can exceed `D` (2.4); for the divisors of `D` in
     103a the fault is not live, and the test says so rather than pass vacuously.
   - **A partial sum of `make_dt_interval` checked out of Spark's order**: the hours and minutes
     products added to the seconds first and the days product last. Refuted by 2's
     `make_dt_interval` draw and test 4's sweep; failed by 3's partial-sum cell of item 6 (days
     106751991, hours -1, secs 14454775808 micros), where the reordered sums never overflow and so
     the fault fuses a lane on which Spark raises **[A, 6]**. Planted in 103b.

   A fault that only declines more, such as 2.2's symmetric bound `+-(MAX / |k|)` (**[A, 4]**),
   is not one of them: the row engine answers the declined batch correctly, so the differential
   cannot fail on it; the guard's exactness rests on 1's bounds claim instead.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code**:
`VarkaDayTimeIntervalBaselineBenchmark`, a class of its own, over Arrow-cached `dt`, `dt2`, three
int columns and a `DECIMAL(18, 6)` column: the projections `dt + dt2 - dt`, `abs(dt)`, `dt * 3`,
`dt * 1.5`, `dt / 24`, `extract(DAY FROM dt)`, `extract(HOUR FROM dt)`, the four extracts together,
`make_dt_interval(i, i2, i3)` and with the decimal seconds, `CAST(dt AS INTERVAL HOUR)`, and the
filter `extract(HOUR FROM dt) >= 12`. Run on the EPYC 7763 runners at 256 and 128 bits through the
benchmark workflow, committed beside the class.

Predictions, registered before the build:

1. **No existing shape's bytes move**; the `option_arms` digests move with the new shapes.
2. **The arithmetic chain and `abs`** at least five times faster than the row path at 256 bits:
   three loads, two checked ops and a store against the row path's `IntervalMathUtils` calls.
3. **The extracts** at least three times faster at 256 bits: about a dozen lane operations and two
   `ConstDivide`s against two `%` and one `/` per row; the four together within twice one alone,
   by the shared chain.
4. **`dt * 1.5`** within 30% of `dt / 24`: both are the day split's chain, the first with a
   guard and a multiply ahead of it.
5. **At 128 bits on x86**, the filter falls under row 503's two-lane rule as it stands and the
   projections still beat the row path.
6. **Test time**: the proof additions under 8 s per solver (VARKA-245's ratio); the coverage
   differential's new rows under 10 s (VARKA-317's observed ratio); the sweep, by hand, under
   five minutes on the laptop (VARKA-335's ratio, about 0.12, against a priced ten minutes).

## 5. Order, files and size

1. **The baseline** (4), its own pull request.
2. **103a, needing nothing unbuilt**: 2.1, 2.2 with `ym * k`, `extract(DAY)` (and the bias
   helper, if 332b has not built it), 2.4's day split and the decimal literal forms whose divisor
   divides `D`, the proof additions, the cells and coverage rows of those forms, the first planted
   fault.
3. **103b, after 332b and 157 and 347**: `HOUR`, `MINUTE`, `SECOND`, `make_dt_interval` (the
   seconds of both, section 8, point 3), the third planted fault, and the measurement against the
   baseline at both widths.
4. **103c, after 103a, 157, 347 and 481**: 2.7's casts, TRY over 481's `NULL` mode where the
   guard is exact, and their cells and measurement.
5. **103d, after 30, 104 and 342**: 2.5's column forms and `div`, the literal divisors that do not
   divide `D` (integral and decimal) over 30's constant forms, the second planted fault, and the
   double factor's named tests over 342's lowering.

| file | what |
|---|---|
| `VarkaIntervalCompiler.java` | the day-time arms of 2.1 to 2.6, `ym * k` |
| `VarkaTimeCompiler.java` | the identity relabel to a finer end field, 2.7's casts |
| `VarkaNodeCompiler.java` | the day split helper, and `truncDivLong` if 103a lands before 332b |
| `sql/varka/proofs/long_divide.smt2`, `dt_arith.smt2`, `VarkaProofFiles.java` | 3.1's claims |
| tests | the emitter and sweep suites, the coverage rows, the fuzz grammar |
| benchmark | the baseline class and its results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json` |
| generated | `width_audit.json`, `census.tsv` |

**Size: medium, in five pull requests.** The baseline about half a day; 103a about two and a half
days (the decimal literal and `ym * k` added); 103b about two after its prerequisites; 103c about
two (the casts, settled here on 30 October 2026); 103d about a day and a half after 30, 104 and
342. Eight to nine days in all against the first plan's five and a half, which may move the row
to large (section 8, open points).

## 6. What it unlocks in the census

The day-time pairs of `abs`, `negative`, `+` and `-` count when 103a lands; `extract_dt_days`
with it, and `extract_dt_hours` and `extract_dt_minutes` with 103b; `extract_dt_seconds` and
`make_dt_interval` with 103b over 157's store and 347's leaf; `extract`, `date_part` and `datepart`
over `dt` when the four fields fuse; `ym_times_num`'s literal integral factor with 103a;
`dt_times_num` and `dt_div_num` for an integral or decimal literal with 103a (a divisor of `D`)
and 103d (the rest), for an integral column with 103d, their `float` and `double` cells being 342's
lowering and their decimal column cells 361's and 363's; `div` over `dt` with 103d; the interval
rows of the cast register with 103c. `try_add` and `try_subtract` over `dt` stay with `TryEval`,
which no row builds; the census edits that record it are the lead's (section 8, open points).

## 7. Out of this row, and declines that stay

Out: a floating factor's lowering (342; the named tests are this row's); a decimal column factor
(361, 363); `TryEval` and the `try_` forms over an interval; the legacy `CalendarInterval` forms;
`avg(dt)`, whose `DivideDTInterval(sum, count)` lives inside an aggregate
(`expressions/aggregate/Average.scala:124`), which Varka does not fuse; `d + dt` over a stored
`INTERVAL DAY` column (2.3).

Declines that stay: a literal zero divisor; `dt / -1` until row 414; until 103d, a literal divisor
that does not divide `D`, naming row 30; a column factor before 104 and a column divisor before 30;
a batch with a lane Spark raises on (an overflowing sum, product, negation or partial sum, a zero
column divisor, `MIN / -1`, a value a cast cannot hold); a batch a conservative guard rejects (a
decimal literal's or a decimal cast's multiply ahead of a division), which the row engine answers;
a decimal above precision 18 on either side of 2.7's casts; at two long lanes on x86 a condition
root under row 503's rule.

## 8. Settlements and open points

None of the owner's. The lead settled the admission check's nine points on 30 October 2026:

1. **The truncation helper is row 332's**, since its 2.4 claims the biased floor. If 103a lands
   first, 103a builds it under 332's form and API and 332b reuses it (2.3).
2. **The double-factor tests stay in this row.** Row 103's own done-when names them: the ties at
   both signs, `2^53` and the long edges, as named tests in 103d (3.3). Row 342 is cited only for
   the multiply node, its 2.4 lowering.
3. **The seconds forms are this row's**: `extract(SECOND FROM dt)` and `make_dt_interval` over
   decimal seconds, both in 103b. Row 351's plan file does not design them; only `PLAN.md`'s row
   351 text and the G1 wave row mention them, and the lead edits those rows separately.
4. **No `2^52`-guarded quotient.** `VARKA-30.md` 7 claims `/` and `%` over a column divisor and a
   literal one alike. This row builds only its exact day-split quotient, for the divisors of a day;
   other literal divisors wait on row 30 and decline naming it, then join 103d (2.4).
5. **The casts parked on this row stay here**: the coarser day-time casts from 332's section 7
   and the decimal to and from interval casts, year-month and day-time, that row 350's plan sent
   here (`VARKA-350.md` 7). Bouncing them back would leave them with no owner, and they are
   interval casts, under the interval family's switch. They are 103c, with Spark's rules pinned
   in 2.7 (`IntervalUtils.decimalToYearMonthInterval`, `decimalToDayTimeInterval`, `HALF_UP`).
6. **`ym * k` for an integral literal**: yes, with the same guard (2.2), in 103a.
7. **A decimal literal factor** (`dt * 1.5`, `dt / 1.5`) is this row's (2.4), with the double
   factor's tie cells as its named tests (3.3). The 2400176 samples of **[A, 8]** are the admission
   check, not the test.
8. **Row 414's evidence is a hand-over, not fixed here.** The closer fact is item 1's:
   `IntervalMathUtils.negateExact` raises with `context = null`, so if the fallback evaluates a
   negation (the lowering `ym / -1` was given) rather than the original `DivideYMInterval`, whose
   own raise carries `origin.context`, the block vanishes as 414 describes. The second: the two
   engines raise different classes for `MIN / -1` (item 4; `intervalExpressions.scala:733-745`
   against `:889-893`, and `:799-801`, `:818` for the year-month form), which would show as a
   condition mismatch if the fallback and the oracle took different paths. That difference may be
   an upstream Spark defect, one input with an engine-dependent error class; the lead checks it on
   apache/master.
9. **The G1 edges** are the lead's to edit; what they should be is under the open points below.

### Open points for the lead

The edits this plan needs outside row 103's line, none made here:

1. **The G1 wave row.** Today: "103 after 28 and 329, its decimal seconds being 351's shapes".
   Proposed: "103 after 28 and 329 (103a), 332 (332b's byte store), 157 and 347 (103b and 103c),
   481 (103c's TRY casts), 342 (103d's double-factor tests), and 30 and 104 (103d), its decimal
   seconds its own". Of these, 332, 481, 30 and 104 add an edge; the others land in earlier waves.
   G1's own 30 makes 103d the wave's last step.
2. **Row 351's text** (settlement 3): drop `extract(SECOND FROM dt)` and "`make_dt_interval` ...
   over decimal seconds" from its list, `make_time` and `make_timestamp_ntz` staying.
3. **A `TryEval` item in `m9/SCOPE.md`**, the next free number (125 on master today), general rather
   than interval work: "`TryEval` over a fused subtree. Every `try_` function whose operand is not
   one of the arithmetic forms Spark rewrites into a `NULL`-returning node becomes `TryEval(child)`
   (`expressions/TryEval.scala:90-97`, `:135-141`, `:229-235`, `:268-274`), which turns any
   exception raised in its subtree into a null. Rows 342 and 340 decline it by name, and row 481's
   `NULL` guard serves a `GuardedRange` only, not a caught exception, so `try_add`, `try_subtract`,
   `try_multiply` and `try_divide` over an interval have no builder, nor `try_eval` over a subtree
   whose nodes raise (349's plan suggests 350 for a decimal one). A kernel can answer it where every
   raising site of the subtree is a guard whose rejection is exact: those guards switch to `NULL`
   mode and the subtree's null mask absorbs them; a subtree with a conservative guard or a site no
   guard covers declines. **Done when** the rule is built over 481's mode, each `try_` entry over an
   interval fuses with a coverage row, and a subtree with an inexact guard is shown to decline."
4. **A follow-up for `d + dt`** over a stored `INTERVAL DAY` column (2.3): (a) an `m9/SCOPE.md`
   item, the number after (3)'s: "the date arm reads a stored `INTERVAL DAY` column as its day
   offset: row 103's day quotient, narrowed to an int under the date arm's day guard
   (`VarkaChronoCompiler.java:527`'s decline today)"; (b) a new milestone 8 row after 103a. The
   form is not in `m5/PLAN.md` 2.38's list, so (a) fits the rule that a finding becomes a row only
   when it serves the milestone's done-when.
5. **The census entries** (`census_curated.txt`, then the script):
   - `try_add` and `try_subtract` (lines 110 and 113): rows `330,104,103,342,349,360` become
     `330,104,342,349,360`; the note "overflow gives null" gains "; over ym and dt it is
     `TryEval`, which no row builds (`m9/SCOPE.md` item 125)", or the number (3) takes.
   - `ym_times_num` (line 197): rows unchanged; the note gains "; an integral literal factor
     over a stored column is row 103's guard (103a)". Its integral column factor still declines
     (item 9) and its decimal literal factor is not in settlement 7: see point 7 below.
   - `dt_times_num` and `dt_div_num` (lines 199 and 200): rows `103,342,349` become
     `103,342,361,363` as 349's plan (section 7) and 363's (2.5) place the decimal column cells;
     the notes gain "an integral or decimal literal and an integral column are row 103's; a
     floating factor is 342's lowering".
   - `extract_dt_days` (line 203): the note gains "; inside `d + dt` over a stored `INTERVAL DAY`
     column it declines", naming (4)'s home.
   - The cast register (`m8/casts.tsv`, from its source): the rows column of `dec ym`, `dec dt`,
     `ym dec` and `dt dec` names `350,364` today and becomes `103` (with point 9's choice above
     precision 18), and `dt dt`'s `103,332` becomes `103`, the 54 coarser forms being 103c's.
6. **Row 414's hand-over** (settlement 8): the two facts above join row 414's evidence, the error
   class difference flagged as possibly upstream.

Decisions that the settlements do not reach:

7. **`ym * c` for a decimal literal, and `ym * i` for a column.** Settlement 7 names the day-time
   forms only; `ym * 1.5` is `MultiplyYMInterval`'s decimal arm (`intervalExpressions.scala:
   651-652`), the same construction at the int lane. (a) 103a takes it beside `dt * 1.5`. (b) It
   stays with 349. The int column factor has no checked int multiply off the narrow lanes
   (`VarkaNodeCompiler.java:1119-1122`); its home is (a) 104's checked multiply, at the int lane,
   (b) a scope item. Evidence for neither beyond item 9.
8. **The helper's name.** 332's plan gives the form and the proof's place but no method name; this
   plan writes `truncDivLong(x, d)`. (a) The name stands, and 332's plan adopts it when 332b is
   built. (b) 332's plan names it first.
9. **A 128-bit decimal in 2.7's casts.** 364's plan leaves the interval pairs to the homes 350's
   plan gave them, "the same above 18" (`VARKA-364.md` 0, item 20), so by settlement 5 they are
   this row's too; 103c declines them for want of 359's lane pair. (a) A later step of this row,
   after 359 and 364's rescale, sized then. (b) Declined naming this row with no step planned, an
   `m9/SCOPE.md` item holding the work.
10. **The size.** The settlements add the casts, the decimal literal and `ym * k`: about eight to
    nine days in five pull requests against the first plan's five and a half in four. (a) Medium
    stands. (b) Large, the size column of row 103 changed with this plan.

## 9. Done when

Row 103's text holds, with the corrections above: `m5/PLAN.md` 2.38's list fuses - `+`, `-`,
negation, `abs`, the products and quotients by an integral literal and, after 104 and 30, an
integral column, the four extracts and `make_dt_interval`, each with coverage rows - and so do
`ym * k` for an integral literal, the products and quotients by a decimal literal, and the casts of
2.7; every division is exact over every long (the extracts, the divisors of `D`, the coarser casts)
or 30's constant form after 103d, with no `2^52` guard of this row's own; the SMT claims of 3.1
hold under both solvers; the cells of 3.3 pass at both widths, the double factor's ties at both
signs, `2^53` and the long edges among them, with the error class of each declined lane the row
engine's; each planted fault of 3.7 is refuted by its proof or sweep and fails the differential;
the sweep has run in the gate with its time recorded; the measurement against the baseline is
committed at both widths with its predictions scored; and the PR build and the option matrix pass.

## 10. Outcome

