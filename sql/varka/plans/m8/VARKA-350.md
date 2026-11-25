# VARKA-350: The 64-bit decimal casts

***Corrected 1 November 2026 (`VARKA-365.md` 11).** The owner decided that Varka follows vanilla
Spark, which answers the ANSI and TRY casts of a decimal of precision up to 18 to `INT`,
`SMALLINT` and `TINYINT` by the exact rule on every SQL read of the Arrow cache, and row 365 made
Varka's own row reads hand such a decimal long-backed. So this row builds the exact rule for those
three casts, integer arithmetic proven by SMT, and the double rule is row 364's alone, above
precision 18. Wherever this plan says the kernel follows the double rule below precision 19, or
reads its answer off the `BigDecimal`-backed column, that text is superseded by this note and is
reworked when the row is built.*

## 0. What exists now (admission check)

From master at `6e207c226fb` (30 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/` or `sql/api/src/main/
scala/org/apache/spark/sql/` as named), from three unmerged plans read on their branches - row
332's integral casts (`varka-332-plan` at `65f091628f0`) and rows 348 and 349's 64-bit decimal value
(`varka-348-349-plan` at `7b66e5601bd`) - and from the JDK 25 sources in
`/home/max/proj/openjdk-build/jdk25`. The row's text is the milestone plan's 2.2, 2.8 ("Casts and
rounding below 19"), section 7's question 3 as answered (iii), and wave F3. The numbers marked
**[A]** are from `results/VARKA-350-admission.txt`, a Python script and its output, run on the
laptop for this plan and shared with `VARKA-366.md`; nothing else was run.

**Spark's behaviour per (from, to, mode), within precision 18.** TRY is ANSI inside a catch that
answers null: `ansiEnabled` is true for both (`expressions/Cast.scala:720-722`), and a TRY cast's
interpreted and generated code wrap the ANSI code in `try { } catch (Exception) { null }`
(`:1586-1597`, `:1706-1716`). `Cast.canAnsiCast` admits every numeric pair and both boolean
directions (`:147`, `:105`, `:149`), `canCast` the same (`:314`, `:265`, `:311`), so every pair
below is legal in the three modes. Spark reads a decimal from an Arrow column `BigDecimal`-backed at
every precision (`VARKA-365.md` 2 item 1), which is what Varka's fallback reads, so the table gives
the answer on that backing; where the long-backed answer differs it says so.

| from | to | LEGACY | ANSI | TRY |
|---|---|---|---|---|
| `DECIMAL(p, s)` | `BIGINT` | `trunc(u / 10^s)` | the same, never raises for `p <= 18` | the same |
| `DECIMAL(p, s)` | `INT`, `SMALLINT`, `TINYINT` | `trunc(u / 10^s)`, low bits kept | double rule: `d = RN(u / 10^s)`; `CAST_OVERFLOW` unless `floor(d) <= MAX` and `ceil(d) >= MIN`; else `(int) d`, narrowed | null where ANSI raises |
| `DECIMAL(p, s)` | `DOUBLE` | `RN64(u / 10^s)` | the same | the same |
| `DECIMAL(p, s)` | `FLOAT` | `RN32(u / 10^s)`, rounded once | the same | the same |
| `DECIMAL(p, s)` | `BOOLEAN` | `u != 0` | the same | the same |
| `DECIMAL(p1, s1)` | `DECIMAL(p2, s2)` | rescaled, `HALF_UP` down; null past `p2` digits | `NUMERIC_VALUE_OUT_OF_RANGE` past `p2` digits | null past `p2` digits |
| integral | `DECIMAL(p, s)` | `v * 10^s`; null where `abs(v) >= 10^(p - s)` | `NUMERIC_VALUE_OUT_OF_RANGE` there | null there |
| `BOOLEAN` | `DECIMAL(p, s)` | `10^s` or 0; true null when `p = s` | true raises when `p = s` | true null when `p = s` |

The sources of each cell:

1. **To an integral type, LEGACY.** `Decimal.toLong` is `longVal / POW_10(scale)` long-backed and
   `decimalVal.longValue` otherwise (`types/Decimal.scala:249-257`), truncation toward zero either
   way; `toInt`, `toShort` and `toByte` narrow that long (`:259-263`). The interpreted cast is
   `numeric.toLong`, `numeric.toInt` and `numeric.toInt(b).toShort` or `.toByte`
   (`expressions/Cast.scala:1159-1161`, `:1201-1203`, `:1265-1267`, `:1331-1333`), the generated
   one `$c.toLong()` and its kin (`castDecimalToIntegralTypeCode`, `:2403-2409`); both keep the
   low bits of the truncated long, so one answer.
2. **To an integral type, ANSI.** The generated code calls `roundToLong`, `roundToInt`,
   `roundToShort` or `roundToByte` (`:2403-2406`); the interpreted code calls
   `DecimalExactNumeric.toLong` and `toInt` (`types/numerics.scala:179-183`), which are
   `roundToLong` and `roundToInt`, and for the two narrow targets checks the int against the
   target's range (`Cast.scala:1251-1264`, `:1317-1330`). `roundToNumeric` (`types/Decimal.scala:
   294-320`) is the exact rule for a long-backed value and, for a `BigDecimal`-backed one, converts
   to a double and accepts `Math.floor(d) <= maxValue && Math.ceil(d) >= minValue`, answering
   `f2(d)`, which is the double truncated and narrowed: **the double rule decides the value as well
   as the range**, so `0.999999999999999999` as `DECIMAL(18, 18)` casts to 1. The two narrow
   targets' interpreted path (`roundToInt`, then the int's range) and generated path (the double
   against the short or byte bounds) accept the same doubles, since both reduce to
   `MIN - 1 < d < MAX + 1`. `roundToLong` (`:327-345`) is exact on both backings, through
   `longValueExact`, and cannot fail below precision 19, where `abs(x) < 10^18 < 2^63`.
3. **The error.** `DataTypeErrors.castingCauseOverflowError` (`sql/api/.../errors/
   DataTypeErrors.scala:247-257`): `CAST_OVERFLOW`, SQLSTATE `22003`, "The value <value> of the
   type <sourceType> cannot be cast to <targetType> due to an overflow. Use `try_cast` to tolerate
   overflow and return NULL instead." (`common/utils/src/main/resources/error/
   error-conditions.json:1009-1014`), its `value` the decimal's string with `BD` appended
   (`Decimal.scala:292`).
4. **To a floating type.** `Decimal.toDouble` is `toBigDecimal.doubleValue` and `toFloat` is
   `toBigDecimal.floatValue` (`Decimal.scala:245-247`), in every mode (`Cast.scala:2656-2657`,
   `:2693-2694`). JDK 25's `BigDecimal.doubleValue` and `floatValue` are correctly rounded from the
   exact value (the JDK's `java/math/BigDecimal.java:3742-3764`, `:3825-3862` and their full paths);
   the fast paths are the guards of the milestone plan's 2.2. `VARKA-366.md` 0 reads them in full.
5. **To `BOOLEAN`.** `!_.isZero` (`Cast.scala:870-871`, generated `:2321-2322`): never fails.
6. **To a decimal.** Interpreted, `toPrecision(..., ROUND_HALF_UP, !ansiEnabled)`
   (`Cast.scala:1369-1374`, `:1400-1401`); generated, `changePrecision` on a clone, then null or
   `CastUtils.changePrecisionExact` (`:1849-1878`, `:1936-1941`). `changePrecision`
   (`Decimal.scala:387-470`) divides a long-backed value by `POW_10(k)` and rounds `HALF_UP` on the
   dropped digits (`:400-439`), multiplies by `POW_10(k)` when it stays under 18 digits
   (`:440-453`), and checks the target precision (`:456-470`); a `BigDecimal`-backed value takes
   `setScale(scale, HALF_UP)` and the same check, so both backings answer alike. When
   `canNullSafeCastToDecimal` holds - the target's integral digits exceed the source's
   (`Cast.scala:534-540`) - the generated code ignores the result, which cannot fail.
   The ANSI error is `NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION` ("<value> cannot be represented
   as Decimal(<precision>, <scale>). If necessary set <config> to "false" to bypass this error, and
   return NULL instead.", `error-conditions.json:6638-6655`; `DataTypeErrors.scala:197-215`).
7. **From an integral type.** `Decimal.apply((long) c)` then `changePrecision` (`Cast.scala:
   1402-1403`, generated `:1942-1947`): `set(long)` builds a long-backed value of precision 20
   (`Decimal.scala:55-70`), and the rescale up by `s` overflows exactly where `abs(v) >= 10^(p -
   s)`. It cannot fail where the target is wider than the source's type (`DecimalType.forType`:
   `TINYINT` 3 digits, `SMALLINT` 5, `INT` 10, `BIGINT` 20, `types/DecimalType.scala:124-138`).
8. **From `BOOLEAN`.** `Decimal.ONE` or `ZERO`, then the same precision change (`Cast.scala:
   1386-1388`, generated `:1915-1920`): true needs `p - s >= 1`, so it overflows only into
   `DECIMAL(p, p)`.
9. **Coercion reaches these casts on its own.** A decimal meeting an integral operand is cast to
   `DecimalType.forType` of the integral type, and a decimal meeting a floating one to `DOUBLE`
   (`analysis/DecimalPrecision.scala:41-48`; `analysis/DecimalPrecisionTypeCoercion.scala:150-188`):
   `d18 < x` over a double arrives as `CAST(d18 AS DOUBLE) < x`, which is this row's.

**What Varka has, and what the prerequisites give.**

10. **No decimal value fuses on master.** The registry's decimal entry is `Owed("347")`
    (`varka/VarkaTypes.java:328-330`); 347's leaf and first reader are on its branches. A cast to
    or from a floating type declines in `floatingDecline` naming row 343
    (`VarkaNodeCompiler.java:338-358`).
11. **The nodes this row composes exist or are planned.** `ConstDivide` truncates toward zero and,
    at the long lane, is exact only for a dividend below `EXACT_DIVIDEND_BOUND`, `2^52`, which its
    caller states (`varka/VarkaVectorIR.java:725-860`); `GuardedRange` only declines today
    (`:536-543`), and its `NULL` mode is row 481's (`VARKA-481.md` 3.1); `CondValue` takes the int
    and long lanes (`:1224`). The `LONG`, `FLOAT` and `DOUBLE` lanes exist (`:88`). Row 343 plans
    `ConvertLane`, the floating `GuardedRange` with the `NULL` disposal, the kernel of two lane
    counts and the AVX2 conversion forms (`VARKA-343.md` 2.1 to 2.4); row 342 plans `IntOp.DIV` at
    the floating lanes (`VARKA-342.md` 2). No IR node computes a fused multiply-add.
12. **Row 332's cast pattern** (its plan, 2.1 and 2.3): one table of targets keyed by (source,
    target), the narrow shape `NarrowLane(g(x, target range), target)` with `g` the identity under
    LEGACY and a range guard under ANSI and TRY, the target ranges held once in `VarkaTypes`, a
    family `INTEGRAL_CAST` with its switch, and TRY by `GuardedRange`'s `NULL` mode, the kernel
    never raising: an ANSI batch Spark raises on declines and the row engine raises.
13. **Rows 348 and 349's value** (their plan, 348's 2.1 to 2.2 and 349's 2.5): a decimal is a
    compile-time `Scaled(unscaled, type)` with `abs(u) <= 10^p - 1` on every valid lane, built by
    one entry point `value()`, rescaled up by `rescale(x, k)` under guard-the-inputs; 348's
    `value()` declines a cast that lowers the scale or the integral digits, or has an integral
    source, naming this row; 349's guarded `CheckOverflow` - `GuardedRange(x, -(10^(p - k) - 1),
    10^(p - k) - 1, d)` before `rescale(x, k)` - is the rescale up with an overflow, and its
    question 1 recommends building it only for 350 and 360.
14. **The double rule equals the exact rule below precision 17 [A].** Under (iii) the three ANSI and
    TRY casts follow the double rule at every precision. **The reason is an argument:** for `x = u /
    10^s` with `abs(u) < 10^p` and `s >= 1`, `RN(x)` can only change `trunc(x)` or the range
    decision by crossing an integer `n` with `0 < n - abs(x)`; that distance is at least `10^-s`,
    while half an ulp of `x` is at most `2^(e - 53)` with `2^e <= abs(x) < 10^(p - s)`; so a
    crossing needs an integer `e` with `2^e * 10^s` in `[2^53, 10^p)`, and none exists for `p <= 16`
    (`s = 0` is an integer, exact below `2^53` and out of every narrow target's range above it). At
    `p = 17` there are 57 such `(s, e)` pairs and at 18, 120. **A draw confirms it and is not
    exhaustive:** sampled around every integer boundary, the two rules differ only at precision 17
    and 18, first at unscaled `20643709109999999` at scale 7 to `INT` (exact 2064370910, double
    2064370911), `16887999999999999` at scale 12 to `SMALLINT` and `-12899999999999999` at scale 14
    to `TINYINT` (exact -128, double overflow). **The layers:** SMT proves the integer rule; the
    argument, with the draw as confirmation, shows that the two rules agree below 17; and the
    sampled checks against Spark's own `Cast` run at every precision regardless.
15. **The constant division's gap, as a table [A].** `trunc(u / 10^k)` is `sign(u) *
    floor((abs(u) >> k) / 5^k)`, and `abs(u) >> k` reaches `2^52` only at `(p, k)` = (16, 1),
    (17, 1 to 4) and (18, 1 to 7); the `HALF_UP` dividend with the half added first reaches it at
    the same twelve pairs. 2.8's "`k = 1..7` guard" is the precision-18 row of that table. A guard
    of `abs(u) < 2^(52 + k)` admits 0.0090 of a uniform `DECIMAL(18, s)` at `k = 1` and 0.5765 at
    `k = 7`.
16. **Where a guard is dead by precision alone [A].** Every `abs(u) <= 10^p - 1` is an exact double
    for `p <= 15` (and below `2^51`, the bound of 343's AVX2 magic `L2D`), and an exact float for
    `p <= 7`; at `p = 16` 0.9007 of a uniform draw is at or below `2^53`, at 17 0.0901, at 18
    0.0090.
17. **The baseline has one of this row's pairs.** `VarkaDecimalBaselineBenchmark` (row 377) times
    `DECIMAL(9, 2)` cast to `DECIMAL(18, 4)` at 53.0 ns a row on the row path, 256 bits, EPYC 7763
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`), a
    rescale up; `DECIMAL(18, 4)` to `(38, 10)` is 360's. No section casts a decimal to an integral,
    floating or boolean type, or rescales down within precision 18.
18. **`m8/casts.tsv` names this row on 21 decimal pairs, the row's text on fewer.** The row's text
    covers decimal with integral, decimal, `BOOLEAN`, `DOUBLE` and `FLOAT`: 13 pairs (8 from a
    decimal and 5 to one, counting `BIGINT` to decimal, whose `(20, 0)` coercion type is 360's). The
    file also gives 350 the two floating sources (346's construction), the four interval pairs and
    `TIME` to decimal (a rescale or a unit division), and `DATE` to decimal (LEGACY's null). The
    six are not this row's (settled by the lead): 350a marks them unclaimed (section 7).

### What differs from the row text and the milestone plan

- **The double rule is computed as the exact rule below precision 17** (item 14), a refinement
  accepted by the owner on 7 November 2026 (question 1 of section 8), not a departure: the semantics are the owner's answer -
  the double rule at every precision - and the kernel's answer is that rule's on every input; what
  changes is the lowering and what proves it. Two thresholds follow, for two different casts:
  **decimal to an integral type** needs the double only at `p >= 17`, and **decimal to a floating
  type** needs 366's arm at `p >= 16`.
- **The double rule is the composition of two casts.** `roundToNumeric`'s accepted interval and
  value are 343's `DOUBLE` to `INT`, `SMALLINT` and `TINYINT` bounds and truncation
  (`VARKA-343.md` 2.4), so the three casts at precision 17 and 18 are this row's `DOUBLE` cast
  followed by 343's ANSI or TRY floating-to-integral tree, with no node of their own (2.3).
- **The `DOUBLE` and `FLOAT` guards become precision rules** (item 16): at `p <= 15` the double
  quotient is always correctly rounded, at `p <= 7` the float quotient; `FLOAT` between 8 and 15 is
  round-to-odd over the double quotient on every lane, and at 16 to 18 both targets take 366's arm
  on every lane, so the default kernel has no lane guard at all. Wherever a guard is used (366's
  possible per-batch option, `VARKA-366.md` 7) it is `abs(u) <= 2^53`, Trino's form
  (`READING_TRINO_DECIMAL.md` 7), the cheaper one, settled by the lead; 2.2's `(long) (double) u ==
  u` costs an `L2D` and a `D2L`, both the Java fallback on the EPYC 7763
  (`results/VARKA-376-census-epyc-7763.json`). 2.2's wording is the owner's text and is flagged in
  section 8, not edited; `casts.tsv`'s semantics column for the two floating pairs changes in 350a's
  pull request. `Long.MAX_VALUE`, which 2.2 excludes, is outside every precision up to 18.
- **The `k = 1..7` guard declines most of a uniform `DECIMAL(18, s)`** (item 15). Both division
  arms are built and measured, the default taken from the runner; the limb arm's product is row
  360b's `WideMul`, so the guard ships first and the limb arm after 360b (2.2).
- **The `DOUBLE` quotient needs row 342's floating division** (`IntOp.DIV`, F2, planned), and the
  F3 wave row names only 365, 341 and 343 as this row's predecessors; that is the owner's text,
  flagged in section 8. **The `FLOAT` round-to-odd at 8 to 15 needs an FMA node that no row
  builds**: `VARKA-342.md` mentions FMA only to say C2 contracts none (its 0). Row 342 is the
  natural owner (floating arithmetic, the earliest wave); this row does not build one, and until one
  exists `FLOAT` at 8 to 15 takes 366's arm, whose `FLOAT` form needs no FMA (`VARKA-366.md` 2.2).
- **Every integral source into precision 18 is this row's**, coercion's `(3, 0)`, `(5, 0)` and `(10,
  0)` included (settled by the lead, 30 October 2026: row 360's plan declines them naming 350, and
  348's and 349's plans decline `i < d9` naming 350); `(20, 0)` stays 360's. The owner's row texts
  still both claim them (section 7).
- **No product of this row's own.** The rescales multiply by `10^k` within 64 bits; the one multiply
  wider than 64 bits, the high word of `LIMB_MAGIC`'s product (2.2), is row 360b's `WideMul` over a
  constant multiplier, which this row calls and does not build.

## 1. Goal and non-goal

Every cast between a `DECIMAL(p, s)` with `p <= 18` and `s >= 0` and an integral type, another such
decimal, `BOOLEAN`, `DOUBLE` or `FLOAT` fuses in LEGACY, ANSI and TRY with the row engine's answers
read through `ArrowColumnVector`: integer rules by integer arithmetic proven by SMT, the floating
targets correctly rounded, the three narrow ANSI and TRY casts by the double rule. An ANSI batch
Spark raises on declines and the row engine raises; TRY's nulls are the kernel's. Not this row: the
exact arm itself (366), precision above 18 (359, 360, 363, 364), floating to decimal (346), the
interval, `TIME` and `DATE` pairs (section 7), a `BOOLEAN` output value (27), a decimal output
store (157), a negative scale.

## 2. Design

### 2.1 One table, one shape per cast, in the decimal value

The arms extend 348's `value()` for a decimal target and add a decimal-source arm for the integral,
boolean and floating targets, both keyed by (source, target) on 332's pattern, in a
`VarkaDecimalCastCompiler` under a family `DECIMAL_CAST` (`varka/VarkaFamily.java`) with its switch,
as 332 has `INTEGRAL_CAST` and 343 `FLOATING_CAST`. Coercion's widening rescale stays 348's, so
switching the family off leaves 348's comparisons fused. Every arm takes a column and a
`LiteralSlot` child alike, the rule 332 (its 2.6) states for row 368's harness. Two precision
thresholds appear below and must not be confused: the narrow ANSI and TRY casts to an integral type
take the double only at `p >= 17` (2.3), and the casts to a floating type take 366's arm at `p >=
16` (2.4).

| kind | tree, over `x = value(child)` and its `(p, s)` | pairs |
|---|---|---|
| truncate | `t = tdiv(x.unscaled, s)` (2.2) | to every integral target |
| narrow, LEGACY | `NarrowLane(t, target)` | to `INT`, `SMALLINT`, `TINYINT` |
| narrow, exact rule | `NarrowLane(GuardedRange(t, MIN, MAX, d), target)` | the same, ANSI and TRY, `p <= 16` |
| narrow, double rule | 343's tree over `toDouble(x)` (2.3) | the same, ANSI and TRY, `p` 17 and 18 |
| to floating | `toDouble(x)`, `toFloat(x)` (2.4) | `DOUBLE`, `FLOAT` |
| test | `Not(Compare(EQ, x.unscaled, 0))` as a condition | to `BOOLEAN` |
| rescale up | `rescale(GuardedRange(x, -(10^(p2 - k) - 1), 10^(p2 - k) - 1, d), k)` | `s2 > s1` |
| rescale down | `GuardedRange(hdiv(x.unscaled, k), -(10^p2 - 1), 10^p2 - 1, d)` (2.2) | `s2 < s1` |
| precision only | `GuardedRange(x, -(10^p2 - 1), 10^p2 - 1, d)` | `s2 = s1`, `p2 < p1` |
| scale up | `IntArith(MUL, WRAP, GuardedRange(v, -(10^(p - s) - 1), 10^(p - s) - 1, d), 10^s)` | integral |
| select | `IfElse(b, slot(10^s), slot(0))`, true guarded when `p = s` | `BOOLEAN` |

`d` is `FAIL` under ANSI and `NULL` under LEGACY and TRY (481's mode), except in the narrow casts,
where LEGACY has no guard. An integral `v` is first widened to the long lane: `WidenLane` for an
`INT`, 332's narrow load at the long width for `TINYINT` and `SMALLINT` (its 2.5). `MIN` and `MAX`
are the target's, from 332's table in `VarkaTypes`. Each guard is dropped where the compiler proves
it dead, the compile-time exemption 364's text and `READING_ARROW_RS.md` 1 and 3.6 name: a rescale
up with `p1 <= p2 - k`, a rescale down with `p1 - k < p2`, a precision change with `p1 <= p2`, an
integral source whose `forType` digits are at most `p - s`, a narrow target whose range holds
`10^(p - s) - 1` (`p - s <= 9` for `INT`, 4 for `SMALLINT`, 2 for `TINYINT`), and a boolean source
into `p > s`. Every guarded rescale names its overflow defence as the 28 October rule asks:
**guard-the-inputs** for the rescale up and the scale up, whose guard is the target's precision
check moved before the multiply, exact by 349's lemma (`abs(x * 10^k) <= 10^p - 1` exactly when
`abs(x) <= 10^(p - k) - 1`); **check-after with no wrap possible** for the rescale down, whose
quotient is below `10^18`; and the multiply never sees a value that could wrap, so no check reads a
wrapped value. 349's `CheckOverflow` arm and this row's rescale up are one helper in
`VarkaDecimalCompiler`, built by whichever of the two lands first.

**A decimal target's value is a `Scaled`**, so a cast feeds 348's comparisons and 349's arithmetic
unchanged, and as an output root it is 157's `WidenDecimal` over the unscaled long.

### 2.2 The two divisions by a power of ten

- **`tdiv(u, k)`, truncating**: `k = 0` is `u`; otherwise `m = abs(u)` by 348's blend, `q =
  ConstDivide(BitOp(USHR, m, k), 5^k, bound)`, then `IfElse(Compare(LT, u, 0), IntNeg(WRAP, q),
  q)`. The identity is `floor(floor(m / 2^k) / 5^k) = floor(m / 10^k)` for `m >= 0`. The shift is
  logical over a magnitude: an arithmetic shift of a negative `u` floors, and `trunc(floor(u / 2^k)
  / 5^k)` is wrong at `u = -19`, `k = 1` (-2 against -1).
- **`hdiv(u, k)`, `HALF_UP`**: DuckDB's signed half, `m = abs(u) + 10^k / 2` (below `2^61`, no
  wrap), then as `tdiv`. `HALF_UP` rounds the magnitude half away from zero, which the sign restored
  after the magnitude's rounding is (`Decimal.scala:427-430`).
- **The bound** is `(10^p - 1 + h) >> k` with `h` the half or 0, known at compile time and stated as
  `ConstDivide`'s `dividendBound`. At the twelve `(p, k)` of item 0.15 it passes `2^52`, and the arm
  `decimalDivisionForm` (`Reason.MACHINE`) picks: `GUARD`, a `GuardedRange(m, 0, 2^(52 + k) - 1,
  FAIL)` before the shift, whose declined batch the row engine computes; or `LIMB_MAGIC`, the 64-bit
  magic multiply for `10^k` with Lemire, Bartlett and Kaser's exact bound (2.8), its high word row
  360b's `WideMul` by the constant (four `vpmuludq`), exact over every `m < 2^63` and with no guard.
  Both are built and measured, and the default comes from the runner (section 4). **The order is a
  sequence, not a defect:** `GUARD` ships first, and `LIMB_MAGIC` lands after 360b. Until then a
  cast at one of the twelve pairs - precision 16 to 18 at the small `k` of item 0.15 - declines most
  batches of a uniform column to the row engine, which computes them: at `p = 18`, `k = 1` the guard
  admits 0.0090 of the lanes.

### 2.3 The three narrow casts under ANSI and TRY

- **`p <= 16`: the exact rule.** `NarrowLane(GuardedRange(tdiv(u, s), MIN, MAX, d), target)`, 332's
  narrow shape over the truncated long, `d` `FAIL` for ANSI and `NULL` for TRY. By item 0.14 its
  answer is the double rule's on every input.
- **`p` 17 and 18: the double rule.** `NarrowLane(ConvertLane(GuardedRange(toDouble(x), lo, hi, d),
  INT), target)` for `SMALLINT` and `TINYINT`, without the `NarrowLane` for `INT`, where `lo` and
  `hi` are 343's floating bounds for a `DOUBLE` source (`VARKA-343.md` 2.4: `[nextUp(-2^31 - 1),
  nextDown(2^31)]` and the narrow targets' kin) and `toDouble` is 2.4's, 366's exact arm on every
  lane at these precisions. The kernel is long-driven with a double node beside the long ones, 343's
  width rule (`VARKA-343.md` 2.2).
- **LEGACY** at every precision is `NarrowLane(tdiv(u, s), target)`: Java's narrowing of the
  truncated long, `L2I` and 332's narrow wrap. **`BIGINT`** in every mode is `tdiv(u, s)`, one
  kernel.

The error a declined ANSI batch leads to is the row engine's, `CAST_OVERFLOW` with Spark's `value`,
by construction (item 0.3).

### 2.4 To `DOUBLE` and to `FLOAT`

| target | precision | lowering |
|---|---|---|
| either | `s = 0`, any `p` | `ConvertLane(u, DOUBLE)` or `ConvertLane(u, FLOAT)`: one rounding of the exact value, 343's `L2D` and `L2F` |
| `DOUBLE` | `p <= 15` | `IntOp.DIV(ConvertLane(u, DOUBLE), 10^s)`, one rounding of exact operands |
| `DOUBLE` | 16 to 18 | 366's arm on every lane, one body, no guard |
| `FLOAT` | `p <= 7` | `IntOp.DIV(ConvertLane(NarrowLane(u, INT), FLOAT), 10^s)` in float, `s <= 7 <= 10` |
| `FLOAT` | 8 to 15 | round-to-odd: `q = IntOp.DIV(ConvertLane(u, DOUBLE), 10^s)`, `r = fma(-q, 10^s, u_d)` by an FMA node (owner to be settled, below), `q` moved to its odd neighbour toward `r`'s sign where `r != 0` and `q`'s last bit is 0, then `ConvertLane(q, FLOAT)`; 366's arm until an FMA node exists |
| `FLOAT` | 16 to 18 | 366's arm, its binary64 sequence with a round-to-odd output and one narrowing (`s >= 1` in both 366 rows, the first row taking `s = 0`) |

The float quotient converts through the int lane because `I2F` is intrinsic on every host and `L2F`
is the fallback on the EPYC 7763 (item 0.11's census file); at `p <= 7` the value fits an int. At
`p <= 15` `abs(u) < 2^51`, so 343's bounded magic `L2D` is exact there with no guard on AVX2, and
the compiler states that bound. Round-to-odd is 2.2's
construction: `q = RN(u / 10^s)` with both operands exact, `r = fma(-q, 10^s, u)` the exact
remainder, the odd neighbour chosen by `r`'s sign, then one `D2F` (Boldo and Melquiond's Theorem 3,
53 bits against 24 + 2; the theorem is quoted from the paper's transcription that 343 step 4 adds,
not from memory). **Never the double quotient narrowed**: 2.2's `DECIMAL(16, 14)` value
64.00001907348633 is a named test, and Trino's `391462049447` at scale 14
(`READING_TRINO_DECIMAL.md` 10 item 5) another.

**The FMA node, which this row does not build.** The round-to-odd remainder needs `fma(a, b, c)` at
the double lane, `lanewise(FMA)`, intrinsic at 512, 256 and 128 bits on the laptop and at 256 and
128 on the EPYC 7763 by the census. No row's plan builds one: `VARKA-342.md` mentions FMA only to
say C2 makes `FmaVD` from an explicit FMA alone (its 0), so a node is the only place one would
appear. Row 342 is its natural owner, floating arithmetic in the earliest wave; row 346's one-FMA
remainder would reuse it. Until it exists, `FLOAT` at 8 to 15 takes 366's arm, correct there too, so
no pair waits on it; the gap is flagged in section 8.

**The bit step.** "`q`'s last bit" and its odd neighbour are a reinterpretation of the double's bits
and a `+-1` on them; 343's AVX2 forms (`VARKA-343.md` 2.3) already reinterpret both ways, and this
row uses the same operators.

### 2.5 `BOOLEAN`

- `CAST(d AS BOOLEAN)`: the condition `u != 0`, fused wherever a condition is; as an output value it
  is row 27's.
- `CAST(b AS DECIMAL(p, s))`: `IfElse(b, slot(10^s), slot(0))` at the long lane, validity `kT | kF`
  as `CondValue`'s (`VARKA-460.md`); for `p = s` the true lanes overflow, so the tree is
  `GuardedRange(CondValue(b, LONG), 0, 0, d)` scaled, declining an ANSI batch with a true row and
  nulling a true lane under LEGACY and TRY. 332's 2.6 names this "a scaled select".

### 2.6 At 128 bits, compiler, reference, cost, warm-up

At two long lanes on x86 every long compare and blend is the Java fallback (`m7` row 374); the rule
is 347's answered question 4 as 348's 2.8 applies it: the casts fuse there as the long projections
do, a condition root under row 503's decline if it lands, and the measurement reports them. The
reference evaluator needs nothing new; `GuardedRange`'s `NULL` arm is 481's and its floating arm
343's.
The cost model gains a feature for each division form, on row 352's awaiting-refit list; the
budget weighs them by operation count until the fit. The warm-up's long block gains the pre-shift
form, held by `VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The cast table against Spark's own.** For every `(p, s)` with `1 <= p <= 18`, `0 <= s <= p`,
   every target of section 0 and each mode, the compiler's fuse-or-decline decision and its guard
   exemptions are held to Spark's `Cast.canNullSafeCastToDecimal` and the type rules, a sample of
   each class compiled and run against `Cast.eval` over a `BigDecimal`-backed `Decimal`, the
   backing `ArrowColumnVector` gives. A pure function over about six thousand `(p, s, target)`
   triples; seconds.
2. **Named cases**, each in the three modes, valid and beside a null, first group, middle and last
   row: `0.999999999999999999` as `DECIMAL(18, 18)` and `2147483647.99999999` as `DECIMAL(18, 8)`
   to `INT` (1 and an overflow, `VARKA-365.md`'s pair); item 0.14's three first differences and the
   largest `DECIMAL(16, s)` values below each target's bound, where the two rules must agree;
   `Int.MinValue` as `DECIMAL(10, 0)` to `INT` and one past (Comet's gap, 2.8; `Long.MinValue`
   needs precision 19 and is 364's);
   `-0.5`, `-1.5`, `1.5` to every integral type (truncation, never rounding); Trino's
   `184467440737095517` at `(18, 0)` to `(18, 2)`, null under LEGACY and TRY and declined under
   ANSI, and its rescale fixtures (`READING_TRINO_DECIMAL.md` 10 item 1) within precision 18;
   `9.99` as `(3, 2)` to `(2, 1)`, the rounding carry that overflows; the first value each guard of
   2.1 rejects and the last it admits; `true` into `DECIMAL(3, 3)`; each `(p, k)` of item 0.15 at
   `2^(52 + k) - 1` and `2^(52 + k)` under both division arms; `DECIMAL(16, 14)` at
   64.00001907348633 to `FLOAT`.
3. **The planted fault, for row 504.** Under a test flag the rescale up multiplies first and checks
   `10^p2` after, Trino's order: the proof's refinement check refutes it, and the named Trino case
   and the coverage differential fail with the flag on.
4. **SMT**, `sql/varka/proofs/decimal_casts.smt2`, written by `VarkaProofFiles` from the code's
   constants, over integers under `java.smt2`'s operators, run under cvc5 and Z3 as 2.8 asks of the
   `5^k` divisors ("a second proof file run under cvc5", since `long_divide.smt2` already costs Z3
   28 s on the laptop): `ConstDivide` by `5^k` for `k = 1..18` equals Java's `/` under its stated
   bound; the pre-shift identity of 2.2 and the `HALF_UP` form against `changePrecision`'s
   remainder rule; `tdiv` against truncation and the sign restore; each guard of 2.1 against the
   precision check it replaces, and each exemption as `unsat` of an overflow; `L2I` and the narrow
   wrap as reduction mod `2^w` (332's `casts.smt2` claims, cited if that file lands first); the
   limb-magic constants by the proof files' rule, linear since the multiplier is a constant. Item
   0.14's lemma is not an SMT claim: it is a finite table over `(s, e)`, checked by the admission
   script and re-checked by a unit test that recomputes it from the code's constants.
5. **The sampled arm**, for the three narrow casts at every precision and the two floating targets:
   every `DECIMAL(p, s)` with `p <= 18`, at each target's bounds, each integer `n` up to `10^(p -
   s)` in a seeded draw at `n * 10^s` and its neighbours `+-1`, `+-10^(s - 3)`, `+-10^s / 2`, every
   power of two, `+-2^53` and its neighbours, against `Cast.eval`; the tie windows of row 352 at
   scales 0 to 2 for `DOUBLE` and 0 to 15 for `FLOAT` (the midpoint scales of the admission file,
   **[A]**). At `p <= 16` the sampled arm and SMT both hold, so a disagreement between them is a
   finding about item 0.14.
6. **Coverage rows**, a "DECIMAL casts" family over 347's and 348's fixtures (`d9`, `d9s4`, `d16s9`,
   `d18`) and one more, `d18s14` (`DECIMAL(18, 14)`, so that the double rule's precision-18 arm and
   the floating targets' exact-arm precisions are reached): each form of the table of 2.1 in each
   legal mode, the `BOOLEAN` target as a filter, each naming its verification for row 490's check -
   a `decimal_casts.smt2` section, `differential`, or the sampled suite. `VarkaCastMatrixSuite`
   regenerates `casts_fused.tsv`, and `dev/varka_census.py` the `fused_today` column.
7. **Conformance.** The decimal conformance suite is 359's (its question 4 (a)); this row gives its
   cast rows answers, the ANSI forms with an in-range value set (`Form.over`), as 332 does for the
   integral types.
8. **The fuzzers**: the IR grammar draws the shapes of 2.1 with each guard mode and both division
   arms in the decimal sequence 348 opens; the Spark-level fuzzer gains the decimal pairs of
   `casts.tsv` as cast productions in each mode, leaving the rest to row 352. Spark's cast suites
   through 368's harness for these pairs, as 332's item 8.
9. **The bytes oracle**: `emitted_bytes.json` adds rows and moves no existing shape.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code**, shared with `VARKA-366.md`, in
row 377's class `VarkaDecimalBaselineBenchmark`, its sections named so the labels survive the lanes
landing, each in range for the mode it runs in (an ANSI section that raised would time the error).
The class's columns spread their integer parts evenly to `5 * 10^6` at `(9, 2)` and `5 * 10^12` at
`(18, 4)` (`VarkaDecimalBaselineBenchmark.java`, `PRECISIONS`), so at `(18, 4)` about four
unscaled values in five lie above `2^53`. The sections:

- at `(9, 2)`: `CAST(x AS BIGINT)`, `CAST(x AS INT)` under LEGACY and ANSI, `try_cast(x AS
  SMALLINT)` (mostly null by design), `CAST(x AS DOUBLE)`, `CAST(x AS FLOAT)` (the float quotient),
  `CAST(x AS DECIMAL(9, 0))` (a `HALF_UP` rescale down) and `WHERE CAST(x AS BOOLEAN)`;
- at `(18, 4)`: `CAST(x AS BIGINT)`, `CAST(x AS INT)` under LEGACY only, `CAST(x AS DOUBLE)`,
  `CAST(x AS FLOAT)` and `CAST(x AS DECIMAL(18, 2))` (a rescale down at `k = 2`, one of item 0.15's
  pairs);
- two new columns: `(15, 4)`, integer parts to `5 * 10^10`, for `CAST(x AS FLOAT)` by round-to-odd;
  and `(18, 14)`, integer parts below `10^4`, for `CAST(x AS INT)` under ANSI and `try_cast`, the
  double rule's precision-18 arm, and `CAST(x AS DOUBLE)`;
- and a `BIGINT` column with values below `10^16` for `CAST(l AS DECIMAL(18, 2))` under LEGACY and
  ANSI.

Run by `benchmark.yml` on the EPYC 7763 at 256 and 128 bits with `expected-cpu`, committed beside
the class's files; the existing cast to `(18, 4)` is the rescale up's baseline.

Predictions, registered before the build:

1. **At 256 bits on the EPYC 7763** the integer casts - truncation, LEGACY and the exact rule, the
   rescales, the integral source - run 5 to 15 times faster than the row path, which builds a
   `BigDecimal` per value: the existing rescale up costs 53.0 ns a row (item 0.17).
2. **`DOUBLE` at `(9, 2)`** within 1.5 times of `BIGINT` at `(9, 2)`, and `FLOAT` by round-to-odd
   at `(15, 4)` within 2 times of `DOUBLE` at `(9, 2)`; `FLOAT` and `DOUBLE` at `(18, 4)` are 366's
   arm and its predictions.
3. **The division arms at `(18, 4)` to `(18, 2)`, `k = 2`**: `GUARD` admits 0.0180 of a uniform
   draw (item 0.15) and declines nearly every batch of the class's values, so on them `LIMB_MAGIC`
   wins outright; on 366's column below `2^53` the guard form beats the limb magic on the laptop
   (AVX-512, `vcvtqq2pd`) and loses on the EPYC 7763, where the conversions inside `ConstDivide`
   are the fallback.
4. **The double rule at `(18, 14)`** under ANSI costs 1.5 to 3 times LEGACY's cast of the same
   column, the cost of 366's arm and the floating guard over the truncation.
5. **At 128 bits on x86** the casts are the fallback and tie the row path or lose as filters, as
   348's prediction 5; the projections still win by 2 to 4 times.
6. **No existing shape's bytes move**; the `option_arms` digests move with `decimalDivisionForm`.

## 5. Order, files and size

1. **The census cells**: the pre-shift and the masked-constant `long.MUL` (the limb arm's, read
   through 360b's `WideMul` cells if 360b has them), on the laptop and through
   `varka-width-audit.yml` on both runners.
2. **The baseline addition** (4), its own pull request, with 366's sections.
3. **350a, the integer casts**, after 347's load, 348's `value()`, 481's `NULL` mode and 332a's
   narrow table and narrow load: truncation to every integral target in the three modes with the
   exact rule at `p <= 16`, the rescales, the integral and boolean sources, the test to `BOOLEAN`,
   the `GUARD` division arm, `decimal_casts.smt2`, the table test, the named cases, the planted
   fault, the coverage rows; the six `casts.tsv` pairs of item 0.18 marked unclaimed with their
   homes; and `casts.tsv`'s semantics column for the floating pairs rewritten to the precision
   rules.
4. **350b, the floating targets**, after 343's `ConvertLane` and row 342's `IntOp.DIV`: `DOUBLE` and
   `FLOAT` at `p <= 15` (`FLOAT` at 8 to 15 by round-to-odd if an FMA node exists by then, else
   declined there until 366), and at `p >= 16` declined naming 366 until it lands; their sampled
   arm.
5. **Row 366**, the exact arm.
6. **350c, after 366 and 343b**: `DOUBLE` and `FLOAT` at `p >= 16` (and `FLOAT` at 8 to 15 if no FMA
   node exists) through 366's arm, the three narrow casts' double rule at precision 17 and 18, their
   sampled arm, the measurement against the baseline at both widths and 512 bits on the laptop,
   scored.
7. **350d, after 360b**: the `LIMB_MAGIC` arm over 360b's `WideMul`, measured against `GUARD` on the
   runner, the default set from that measurement.

| file | what |
|---|---|
| `VarkaDecimalCastCompiler.java` (new), `VarkaDecimalCompiler.java` | the table of 2.1, `tdiv`, `hdiv` |
| `varka/VarkaFamily.java` | `DECIMAL_CAST` |
| `varka/VarkaVectorIR.java`, `varka/VarkaVectorWalk.java` | `CondValue`'s scaled use |
| `varka/VarkaDivisionLowering.java` | the limb-magic arm over 360b's `WideMul` |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalDivisionForm` |
| `VarkaNodeCompiler.java` | `floatingDecline` admits the decimal-floating pairs |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weights, features, reach |
| `sql/varka/proofs/decimal_casts.smt2`, `VarkaProofFiles.java` | section 3 item 4 |
| tests | the table test, named cases, sampled suite, planted fault, coverage rows, fuzz forms |
| benchmark | the baseline sections and their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `casts_fused.tsv`, `casts.tsv`, `census.tsv`, `census_curated.txt` |

**Size: medium as the row says, in four pull requests after the baseline**, and the largest of the
F3 decimal rows below 19: 350a about four days (the proof file and the table test the larger part),
350b about two (round-to-odd), 350c about two once 366 is in, 350d one after 360b. Test time: the
table test is a pure function, the named cases and slot tables of the size of 347's, seconds; the
sampled suite is a few million `Cast.eval` calls, of the order of a minute, opt-in in full and a
slice in PR CI; the proof file is priced from its spiked form at VARKA-245's observed ratio in the
calibration table, under twenty seconds per solver. It rests on 347, 348 (`value()`), 481 (the
`NULL` mode, which this row does not build), 332a (the narrow load in a bigint kernel), 342 (the
floating division), 343 and 366, on 360b's `WideMul` for the `LIMB_MAGIC` arm only, on an FMA node
for the `FLOAT` round-to-odd only, and on 157 for the decimal outputs.

## 6. What it unlocks in the census

Under the milestone plan's 2.8 and 359's answered question 2 (a), a decimal pair counts fused only
when it fuses at every precision up to 38, so **this row moves the fused count by 0 when it lands**;
the ten casts and `try_cast` entries count with 364 (`VARKA-359.md` 6). What it gives: the 64-bit
half of `cast` and `try_cast` for the decimal pairs of section 1, and `casts_fused.tsv`'s
`DECIMAL(10, 2)` forms of those pairs fused in every mode, the `DECIMAL(30, 4)` forms staying
declined naming 364. The census notes and `casts.tsv`'s semantics column for the three narrow pairs
say the double rule computed as the exact rule below 17, if the owner accepts question 1.

## 7. Out of this row, and declines that stay

Out: the exact arm (366); every precision above 18 (359, 360, 363, 364), including the `(20, 0)`
coercion type of a `BIGINT`; any product wider than 64 bits (360b's `WideMul`, 361's general
product); the narrow column load in a bigint kernel (332's, which this row uses); `GuardedRange`'s
`NULL` mode (481's, from which every TRY null here comes); `DOUBLE` and `FLOAT` to decimal (346);
the six interval, `TIME` and `DATE` pairs of item 0.18; a general FMA node (row 342's by nature,
section 8); `try_eval` over a decimal
subtree; a `BOOLEAN` output (27); a negative scale. Trino's `FLOAT` midpoint-margin test
(`READING_TRINO_DECIMAL.md` 7, step 2) would keep more lanes off 366's arm at `p >= 16` but needs
its own error bound proven; it is not built here. An integer form of the double rule at precision 17
and 18 - the truncated quotient moved by one where the remainder lies within half an ulp of the next
integer - would avoid the floating lane there; it needs a per-lane leading-zero count and a tie
rule, costs about what 366's arm does, and is not built.

**The six pairs outside the row's text, unclaimed** (settled by the lead): 350a marks them so in
`census_curated.txt`, each with a home - decimal to and from the year-month and day-time intervals
to row 103 (or 332's interval work), `TIME` to decimal to `m9/SCOPE.md` (no `TIME`-cast row exists),
`DATE` to decimal (LEGACY's null) to row 333's typed null leaf. Their Spark rules: decimal to
year-month is `IntervalUtils.decimalToYearMonthInterval`, `HALF_UP` to months and `toIntExact`
(`util/IntervalUtils.scala:841-845`), to day-time `decimalToDayTimeInterval` (`Cast.scala:
1116-1118`); an interval to decimal is the interval as a decimal of its end unit, then
`changePrecision` with `nullOnOverflow` false, so every mode raises (`Cast.scala:1411-1423`); `TIME`
to decimal is `Decimal(t, 14, 9)` then `changePrecision` (`:1395-1399`).

**Flagged, not resolved:** the owner's row texts have rows 350 and 360 both claiming the integral
casts to `(3, 0)`, `(5, 0)` and `(10, 0)` ("to and from integral types" here, "the widening casts"
there). The plans settle them as this row's; aligning the row texts is the owner's.

Declines that stay, each naming its row: an ANSI batch holding a value Spark raises on (the row
engine raises it); under `decimalDivisionForm=GUARD`, a batch past `2^(52 + k)` at the twelve `(p,
k)` of item 0.15 until 350d (a uniform column's batches there mostly, 2.2); until 366, every
`DOUBLE` or `FLOAT` cast at `p >= 16`; a
decimal in an int-driven kernel (347's leaf rule); at two long lanes on x86, what 347's question 4
reading and row 503 decline.

## 8. Questions for the owner

1. **A refinement of the answer to question 3, not a departure: the double rule computed as the
   exact rule below precision 17.** The owner's answer of 16 October 2026 (milestone plan section 7,
   question 3): "**(iii), no reader change.** ... Under (iii) the kernel reproduces vanilla Spark's
   column-path rule (the double rule over a `BigDecimal`-backed value) for the ANSI and TRY casts of
   a decimal to `INT`, `SMALLINT` and `TINYINT`, which is what Varka's fallback reads through
   `ArrowColumnVector`." And what follows from it: "350 builds the double rule below precision 19 as
   364 does above it, on 366's exact arm outside 350's guard, proven by the sampled arm and not
   SMT". This plan keeps the rule and refines how it is computed and proven. By item 0.14's
   argument, the double rule and the exact integer rule give the same answer on every input below
   precision 17, so (a) the kernel computes the three casts by the integer rule at `p <= 16`, proven
   by SMT, and takes the double through 366's arm only at 17 and 18; (b) it computes the double at
   every precision, as the answer's wording has it. **The answers are identical either way.** The
   first input where the rules differ is at precision 17: unscaled `20643709109999999` at scale 7 to
   `INT`, 2064370910 by the exact rule and 2064370911 by the double rule, and there this plan uses
   the double. The sampled checks against Spark's `Cast` run at every precision under both.
   **Recommended: (a)**: integer arithmetic below 17 costs a truncating division where (b) costs an
   `L2D`, a divide, two floating compares and a `D2I` - the conversions being the EPYC 7763's
   fallbacks or 343's AVX2 forms - and it adds an SMT proof without removing the sampled one.

   *7 November 2026, by the owner's word:* (a). This changes the owner's 16 October clause that
   the double rule is "proven by the sampled arm and not SMT": below precision 17 the integer rule
   is now also proven by SMT, the sampled checks against Spark's `Cast` kept at every precision;
   at 17 and 18 the kernel takes the double through 366's arm.
2. **2.2's guard wording, flagged as the owner's text.** The milestone plan's 2.2 states the
   `DOUBLE` guard as `(long) (double) u == u`; this plan builds the cheaper form the lead settled
   (precision rules, and `abs(u) <= 2^53` wherever a guard is used), equally correct. The text is
   the owner's and is not edited here; `casts.tsv`'s semantics column changes in 350a.
3. **Flagged: row 342 is a predecessor the F3 wave row does not name.** The F3 row has "350, after
   365, 341 and 343"; this row also needs 342's floating division. The owner's text.
4. **Flagged: no row builds an FMA node.** 350's `FLOAT` round-to-odd at 8 to 15 needs one, 346's
   one-FMA remainder and 366's working-precision option would reuse one; `VARKA-342.md` has none.
   Row 342 is the natural owner. Until a row takes it, `FLOAT` at 8 to 15 runs on 366's arm.
5. **Flagged: rows 350 and 360 both claim coercion's integral casts** in the owner's row texts
   (section 7); the plans settle them as this row's.

### Open points for the lead

Settled by the lead on 30 October 2026, kept as the record: (1) the exact integer rule at `p <= 16`,
put to the owner as question 1; (2) one body for 366's arm, `VARKA-366.md` 2.4; (3) every integral
source into precision 18 is this row's, the row-text overlap flagged; (4) the six interval, `TIME`
and `DATE` pairs unclaimed with homes (section 7); (5) both division arms measured, the default
from the runner, the limb arm after 360b's `WideMul`; (6) the baseline first; (7) the cheaper guard
form, 2.2's wording flagged; (8) the floating division is 342's, the FMA gap flagged with 342 its
natural owner. None is open.

## 9. Done when

Row 350's text holds, with the corrections above and question 1 as the owner answers it: every pair
of section 1 fuses in the three modes at every `(p, s)` with `p <= 18`, the three narrow ANSI and
TRY casts by the double rule, the `DOUBLE` and `FLOAT` targets at `p >= 16` through 366's exact arm;
the six pairs of item 0.18 marked unclaimed with their homes; both division arms measured and the
default set from the runner; the integer rules proven in `decimal_casts.smt2` under cvc5 and Z3 with
`5^k` for `k = 1..18`, item 0.14's lemma re-checked by its unit test, and the double rule's and the
floating targets' sampled arm passing at every precision; the table test, the named cases and the
planted fault (failing with its flag) pass; each rescale site names its defence with a test at the
first value its guard rejects; `casts_fused.tsv`, `casts.tsv` and the census agree with the
compiler; no existing shape's bytes move; the measurement against the baseline is committed at both
widths with its predictions scored; and the PR build and the option matrix pass.

## 10. Outcome

### 10.1 Step 1: the census cells, on the laptop

*20 November 2026, at `e5d70012bdc`, before any of the row's code, on branch
`varka-350-decimal-casts-64`. The laptop column of `sql/varka/operator_census.json` (AMD Ryzen AI 9
HX PRO 370, JDK 25.0.5, species of 512, 256 and 128 bits); the EPYC 7763 and Neoverse N2 columns
wait on a `varka-width-audit.yml` dispatch on the branch, owed.*

Two cells in `VarkaOperatorCensusCatalog`, in the long lanewise family. `long.LSHR` is the existing
long shift loop under the logical shift, the pre-shift of 2.2. `long.MUL_masked_broadcast` is a new
loop, `mulc32.long`: each long masked to its low 32 bits and multiplied by a vector broadcast once
from an array element of 32 bits, the partial product a 64-bit magic multiply for `10^k` is made of
(2.2's `LIMB_MAGIC`), the constant masked to 32 bits as well, so that both inputs have the shape
C2's `MulVLNode::has_uint_inputs` matches (an AND with a replicated constant of at most `2^32 - 1`).
Row 360b has no `WideMul` cells to read it through yet, so the cell stands on its own and is named
apart from `long.MUL_broadcast`, whose inputs are not masked.

| cell | what the loop does | 512 | 256 | 128 | bytes a row |
| :-- | :-- | :-- | :-- | :-- | :-- |
| `long.LSHR` | `lanewise(LSHR, 3)`, 2.2's pre-shift | I | I | I | 0 |
| `long.MUL_masked_broadcast` | `lanewise(AND, 2^32 - 1)` then `lanewise(MUL, limb)` | I | I | I | 0 |

Both are intrinsic and allocation-free at every width on the laptop, and no existing cell's verdict
moved (the file's other change is the JDK's build string). What no verdict can say is whether C2
emits `vpmuludq` rather than AVX2's seven-instruction `MulVL` emulation: both are intrinsic and
allocation-free, so both read `I`, on the EPYC 7763 column too. The loop's assembly decides it, and
it decides 350d's measurement, not 350a.

### 10.2 Step 2: the baseline addition

*20 November 2026, on the unchanged code, the same branch. The runner results at 256 and 128 bits
come from `benchmark.yml` on the EPYC 7763 after the pull request, owed. Steps 1 and 2 share one
pull request, against section 5's order of a pull request each: both are test-only on the unchanged
code, and the baseline still lands ahead of any lowering, which is what section 4 asks of it.*

Forty sections of `VarkaDecimalBaselineBenchmark`, the plan's list of section 4 with `VARKA-366.md`
4's floating casts, twenty at each null density, all `NONE` on the unchanged code:

- at `(9, 2)`: `CAST(x AS BIGINT)`, `CAST(x AS INT)` under LEGACY and ANSI, `try_cast(x AS
  SMALLINT)` (past the range on most rows, which the queries suite asserts rather than the no-null
  rule),
  `CAST(x AS DOUBLE)`, `CAST(x AS FLOAT)`, `CAST(x AS DECIMAL(9, 0))` and `CAST(x AS BOOLEAN)` as a
  filter, with constraint propagation off as the integral cast baseline's boolean filter has it;
- at `(18, 4)`: `CAST(x AS BIGINT)`, `CAST(x AS INT)` under LEGACY, `CAST(x AS DOUBLE)`, `CAST(x AS
  FLOAT)` and `CAST(x AS DECIMAL(18, 2))`;
- over two new columns after `l`: `x15s4`, `DECIMAL(15, 4)` with integer parts to `5 * 10^10`, for
  `CAST(x AS FLOAT)`; `x18s14`, `DECIMAL(18, 14)` below `10^4`, for `CAST(x AS INT)` under ANSI,
  `try_cast(x AS INT)`, `CAST(x AS DOUBLE)` and `CAST(x AS FLOAT)` (366's);
- over `l16`, a `BIGINT` spread evenly below `10^16`: `CAST(l AS DECIMAL(18, 2))` under LEGACY and
  ANSI.

A section now carries the SQL settings both sessions run it under, as the integral cast baseline's
do, so the modes are explicit rather than the session's default. The new columns come after every
other, so no earlier section's values move.

### 10.3 Step 3: 350a, the integer casts

*20 November 2026, on branch `varka-350a-integer-casts`, stacked on step 1 and 2's branch. Built
while the owner was away; nothing pushed, so the PR Build, the option matrix and the width audit's
runner columns are owed.*

**What was built.** `VarkaDecimalCastCompiler`, under a new family `DECIMAL_CAST` (its switch
`spark.sql.codegen.varka.family.decimalCast.enabled`), with the table of 2.1 but for the floating
targets:

- **to an integral type** in the three modes: `t = trunc(u / 10^s)`; `BIGINT` is `t`; the narrow
  targets are `NarrowLane(t)` under LEGACY and `NarrowLane(GuardedRange(t, MIN, MAX, d))` under
  ANSI and TRY, the exact rule at every precision up to 18 (decision 1 below), the guard dropped
  where `10^(p - s) - 1` fits the target;
- **to `BOOLEAN`**: `NOT(u = 0)` as a condition, reached from `compileCond` under the family's
  switch;
- **into a decimal**: the rescale up behind its input guard (guard-the-inputs), the rescale down by
  `HALF_UP` then the precision check (check-after, its quotient below `10^18`), the precision check
  alone at one scale; each guard dropped where the types prove it dead, as 2.1 lists; coercion's
  widening rescale stays 348's, outside the family;
- **from an integral type and `BOOLEAN`** (350a1b, stacked on 350a): the value widened to the long
  lane behind `|v| <= 10^(P - S) - 1`, dead where the type's digits fit, then times `10^S`
  (coercion's `INT` into `DECIMAL(10, 0)` has no guard); a boolean is 332's select at the long
  lane times `10^S`, behind a guard of 0 into `DECIMAL(P, P)`.

A guard into a decimal is `FAIL` under ANSI and `NULL` under LEGACY and TRY. The two divisions are
2.2's with one change (decision 2): below `2^52` the truncation is one `ConstDivide` of the signed
value by `10^k` and the rounding adds DuckDB's signed half first; at and above it, the magnitude
takes the logical shift by `k` and the division by `5^k`, the sign put back, and at the twelve
`(p, k)` of item 0.15 the magnitude is guarded below `2^(52 + k)` first (`GUARD`, the only arm until
350d). `VarkaDecimalCompiler.valid` reads through a cast into a decimal only where it has no guard
(the widening rescale, or a cast whose source's widest value fits), to the operand's validity
(350a1b: a column's word for an integral operand); over a guarded cast a null test declines, in every mode:
under LEGACY and TRY the guard nulls lanes the operand's validity does not show, and under ANSI its
failure, Spark's error, is in the value the null test does not keep.

**Tests.** `VarkaDecimalCastQuerySuite` (sql/core) runs every cast against vanilla Spark over an
Arrow-cached table in LEGACY, ANSI and TRY, rows or the same error, and checks each runs in a
Varka node: the named cases of section 3 item 2 that need no floating lane - `0.999999999999999999`
as `DECIMAL(18, 18)` and `2147483647.99999999` as `DECIMAL(18, 8)` to `INT` (0 and `Int.MaxValue`,
the exact rule), item 0.14's three first differences, which take the exact rule's answer, the
bounds of each target and one past, `Int.MinValue` as `DECIMAL(10, 0)`, `-0.5`, `-1.5`, `1.5`,
Trino's `184467440737095517` at `(18, 0)` into `(18, 2)`, and `9.99` and `9.95` into `(2, 1)` (the
carry that overflows). 350a1b adds `true` into `DECIMAL(2, 2)` and both sides of the division
guard at `(18, 2)` (`2^54 - 51` and `2^54`) and at `(16, 1)`, `(17, 3)` and `(18, 7)` (`2^(52 + k)
- 1` and `2^(52 + k)`), each batch inside a guard checked to run in the kernel. Fifteen coverage
rows in two families, "Decimal casts" and "Decimal cast predicates", each guarded row with its
domain, which `VarkaCoverageHarnessSuite` checks at its boundary (decision 6); 350a1b adds five
over the integral and boolean sources, `TINYINT` and `SMALLINT` among them.

**The parts** (the 1000-line cap): 350a, the casts from a decimal; 350a1b, stacked on it, the
casts into a decimal from an integral type and `BOOLEAN` and the division guard's boundary test;
350a2, stacked on that: `decimal_casts.smt2`, the proof file
the house rule asks of a bounded lowering, which `VarkaDecimalCastProofFile` renders from the
compiler's constants; the table test of section 3 item 1, every guard decision held to Spark's
`Decimal.changePrecision` and `Cast.canNullSafeCastToDecimal`; and the planted multiply-then-check
fault for row 504. Until 350a2 the integer rules are held by the differential suites alone.

**Finding, not this row's.** `CAST(b AS DECIMAL(2, 2)) = 0` over a `BOOLEAN` column fails in Spark's
optimizer on both engines, a `NullPointerException` in `UnwrapCastInBinaryComparison` (the cast of
`true` has no value in `DECIMAL(2, 2)`, and the rule compares the null it gets); the suite compares
against a column instead. Vanilla Spark's, recorded for an upstream report, not a Varka row.

### Decisions taken while the owner was away (20 November 2026)

1. **The three narrow ANSI and TRY casts follow the exact rule at every precision up to 18, not the
   double rule at 17 and 18.** The plan's opening note (1 November 2026) and `VARKA-365.md` 11 record
   that vanilla Spark answers these casts by the exact rule on every SQL read of the Arrow cache,
   and the owner's standing rule is that row answers follow vanilla Spark. Section 2.3's double rule
   at 17 and 18, and question 1's answer of 7 November, which keep the double there through 366's
   arm, rest on the premise that the 1 November correction had already replaced; built that way,
   the kernel would answer `20643709109999999` at scale 7 as 2064370911 where vanilla Spark answers 2064370910. The
   query suite holds the exact rule to vanilla Spark at precision 17 and 18 (`f` and `k`, `m`
   columns), so the kernel needs no floating lane for these casts, and 350c loses its double-rule
   item. Reverting is one condition in `VarkaDecimalCastCompiler.toIntegral` if the owner decides
   otherwise.
2. **Below `2^52` the divisions skip the pre-shift.** `ConstDivide` truncates a signed dividend as
   Java's `/` does, so `trunc(u / 10^k)` is one node where `|u| < 2^52` (precision 15 and below),
   and `HALF_UP` is DuckDB's signed half, `u +- h`, then that division; the magnitude, shift and sign
   restore of 2.2 run only where the bound needs them. Fewer nodes for the same answers; the pre-shift
   identity is 2.2's and unchanged where it runs.
3. **No `decimalDivisionForm` option yet.** With one arm built an option has nothing to choose; it
   lands with `LIMB_MAGIC` in 350d, where both arms are measured.
4. **Steps 1 and 2 share one branch and pull request**, both test-only on the unchanged code; the
   baseline is still its own pull request ahead of any lowering, as section 4 asks.
5. **The census cell for the limb multiply stands on its own** (`long.MUL_masked_broadcast`): row
   360b has no `WideMul` cells to read it through.
6. **The coverage harness reads decimals.** `VarkaDomains.laneValue` now reads a decimal of 18
   digits or fewer as its unscaled long, the long lane's value, three lines; with it every
   guarded decimal row's domain is checked as the other guarded rows' are. The three guarded
   casts into a decimal move from predicate rows to value rows read through `BIGINT`
   (`CAST(CAST(d18 AS DECIMAL(12, 2)) AS BIGINT)` and its kin): a null test over a guarded cast
   declines by design, so as a predicate the harness's null-test, partition and boolean-output
   forms could never fuse it.
7. **At two long lanes the family's values decline.** On an x86 host whose 64-bit species have
   two lanes or fewer (the session setting of rows 104 and 503), every decimal cast's value
   declines with `VarkaDecimalCastCompiler.TWO_LANE_REASON`. The evidence is C2's assembly under
   `dev/varka_emit.sh --asm --width=16`: the dense body of `CAST(x AS BIGINT)` over
   `DECIMAL(9, 2)` (one constant division, no guard) and `DECIMAL(16, 9)` (the magnitude, shift
   and sign restore) calls into the Vector API's Java fallback for `Long128Vector.rearrange`,
   `compare`, `blend` and the mask `or`, 21 and 25 static calls and 34 allocation sites in the
   standard compilation. The rearranges are the decimal slots' load, which row 596 measured at
   0.2X of the row path for a decimal compare written as a value; so the decline covers the
   unguarded casts too, not only the guarded ones.

**State (20 November 2026, about 04:30 UTC).** 350a is one commit on
`varka-350a-integer-casts`, 350a1b one on `varka-350a1b-integral-sources` stacked on it; nothing
pushed. *23 November 2026:* 350a is rebuilt alone on master as `varka-350a1-r2` for its own pull
request; 350a1b's items above (the integral and `BOOLEAN` sources, the column word in `valid`, the
boundary queries) are not in it and follow in their own pull request, then 350a2. Passing on the laptop for 350a: `VarkaCoverageHarnessSuite` (2641 of 2641), `VarkaDecimalCastQuerySuite` (8 of 8),
`VarkaDecimalBaselineQueriesSuite` (11 of 11), `VarkaExpressionCompilerSuite`'s two-lane tests,
`VarkaFamilySwitchSuite`, `VarkaFamilyChainSuite`, `VarkaDecimalValueSuite`, and every suite
`dev/varka_regen_generated.sh --all` runs, the width audit at the laptop's widths among them. Under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` the width audit (5 of 7, the
two failures expected of an AVX2 run against the laptop's committed file) moves the decimal casts
at 256 bits from `[]` to the long-double conversions' misses (`cast#513/3`, `cast#515/3` at
`vlen2=4`), the constant division's exact double path, as it moves `CAST(ntz AS DATE)` and the
other constant divisions, 62 rows outside this row's; its second probe timed out at 600 s beside
the other agents' load. Whether AVX2 hosts should take another division form there is for 350d's
runner measurement, which reads the EPYC 7763, an AVX2 host. Left: 350a2, and the runner columns.

**Why the decimal casts and not every long-lane projection.** The width audit at 128 bits records
the same intrinsic misses (`not supported ... vlen=2 etype=long`) for long-lane projections with a
compare or a constant division that stay fused there, `CAST(l AS INT)` under ANSI among them; row
503 measured those at 1.3X to 5.6X of the row path, their fallback masks cheaper than the row
path's work. The decimal casts are the first decimal values a projection writes (every other
decimal form is a comparison's operand, refused at two lanes as a condition), and their load is
the one row 596 measured at 0.2X, so they decline with it. The runner pair at 128 bits can
confirm it once the benchmark runs.

**The narrow targets move out (25 November 2026, by the owner's choice).** #1700's CI failed three
tests, all 350a1's own: `VarkaDecimalLongConformanceSuite`'s cast row, whose decimal slot check
reads long, decimal-store and condition outputs and not the `NarrowLane` root a cast to `INT`
lowers to, and two order tests (`VarkaConjunctOrderSuite.castInBeforeDateAdd`, the
`VarkaDifferentialSuite` test of a residual decimal cast) that take `CAST(x AS INT)` over a
`DECIMAL(18, s)` as residual, which 350a1 fused. Before the split, with the cast fused, every
conjunct of both tests fused and both engines answered or raised alike, a null date ahead of the
raising cast included, so the order rule held and only the tests' premise was stale. The pull
request was at 997 of its 1000 counted lines, so the casts to `TINYINT`, `SMALLINT` and `INT` move
to the next stacked part, 350a1n, on branch `varka-350a1n-narrow-targets`: decision 1 above and
the first item of "What was built" are its, and #1700 declines those casts with
`VarkaDecimalCastCompiler.NARROW_REASON`. This corrects "What was built" and the counts of "Tests":
#1700 keeps the casts to `BIGINT` and `BOOLEAN` and between decimals in every mode, ten coverage
rows, and the query suite's narrow-target tests (the `INT` cases of section 3 item 2 and
`CAST_OVERFLOW`) go with 350a1n. With the cast residual again the two order tests pass unchanged,
and the conformance suite's cast row builds no narrow form. *The stack's order, by the owner's word the same day:* #1700,
then 350a2's proof file `decimal_casts.smt2` (branch `varka-350a2-proofs`) covering only #1700's
rules - the casts to `BIGINT` and `BOOLEAN` and between decimals - then 350a1n, then 350a1b
(branch `varka-350a1b-integral-sources`). This replaces "The parts" above, where 350a1b came
before the proofs.

### 10.4 Step 3, part 350a2: the proofs of #1700's rules

*25 November 2026, on branch `varka-350a2-proofs-r2`, stacked on #1700 (`varka-350a1-r2`).* The
proof part of 20 November (`dbf7f8c9c34` on `varka-350a2-proofs`) was written over 350a1b, which
still held the narrow targets and the integral sources. This part keeps only what proves #1700's
rules: the casts to `BIGINT`, to `BOOLEAN` and between decimals.

**A finding first.** On #1700's head `682d1d73d0e`, `VarkaDivisorProofSuite` failed: the coverage
rows compile long-lane `ConstDivide`s by 25, 78125 and 1953125 (`5^2`, `5^7`, `5^9`, the magnitude
form's divisors), which `long_divide.smt2` did not state. #1700 now adds the three to
`VarkaProofFiles.LONG_DIVISORS`. This part proves the rest of the set the compiler can build:
`10^1` to `10^15` below `2^52`, `5^1` to `5^18` past it.

**What it proves.** `VarkaDecimalCastProofFile` renders two files from the compiler's constants
(`Decimal.POW_10`, `ConstDivide.EXACT_DIVIDEND_BOUND`) and from its division table, `divisions()`:

- `decimal_casts.smt2` (1109 checks): per division of a `p`-digit value by `10^k`, truncating
  (the cast to `BIGINT`) and `HALF_UP` (the rescale down), all 342, the dividend under the bound
  stated to `ConstDivide` and the answer Spark's (Java's `/`; `changePrecision`'s `HALF_UP`); the
  magnitude form's logical shift, its division by `5^k` and the sign put back; at the twelve
  `(p, k)` of item 0.15 the `2^(52 + k)` guard needed and one wider wrong; an arithmetic shift
  (`-19`) and each half one less refuted; the rescale down's quotient within `10^(p - k)` and
  reaching it, so its precision check is dead exactly where `p - k < P`; the rescale up's guard
  `|u| <= 10^(P - k) - 1` exactly the target's precision with no wrap, one wider refuted; and the
  other order, the check after the multiply, wrong at Trino's `184467440737095517`.
- `decimal_divide.smt2` (2352 checks): both lowered forms of the constant division exact below
  `2^52` for the 21 divisors `long_divide.smt2` does not state (it now holds #1700's three), by
  `VarkaProofFiles`'s own `conversionExact` and `magicExact`.

**The tests.** `VarkaDecimalCastTableSuite` builds each of the 342 divisions with the compiler's
own `truncated` and `roundedHalfUp` and holds its divisor, dividend bound, form and guard to the
table, so a changed division fails before it ships unproven; and over every `(p, s)` into every
`(P, S)` (189 x 189) it holds the guard decision to Spark's `changePrecision` of the widest source
value and to `Cast.canNullSafeCastToDecimal`. `VarkaDivisorProofSuite` takes both files' divisors
and compiles a decimal of every `(p, s)` to `BIGINT` and to scale 0, so every divisor is collected.

**Measured** (laptop, nothing else running, `dev/varka_prove.sh --solver z3|cvc5`):
`decimal_casts.smt2` Z3 5 s and cvc5 6 s, so `--lint` runs it under Z3; `decimal_divide.smt2` Z3
83 s and cvc5 24 s, so `--lint` runs it under cvc5 (`CVC5_IN_LINT`), as `long_divide.smt2`, the
same obligations over 2479 checks, runs (Z3 85 s, cvc5 27 s). The whole `--lint` run takes 57 s
on the laptop without this part and 87 s with it.

**The lint, not the nightly: a change from 20 November.** In `dbf7f8c9c34` the divisor file,
then 24 divisors and 2688 checks, measured Z3 219 s and cvc5 90 s run alone, past the lint's
minute under both, so it went to `NIGHTLY_ONLY` with the nightly proofs job's limit raised from
30 to 50 minutes. Measured again on 25 November, the same 24 divisors took Z3 95 s and cvc5
27 s, an sbt run overlapping part of it; the cause of the difference was not measured. cvc5 is
now under the minute even at the runner's twice the laptop, and `proofs/README.md`'s rule sends a
file to the nightly only past the minute under both solvers, so it runs in every pull request's
lint under cvc5 instead, and `varka-fuzz.yml` is unchanged. Section 3 item 4 asked for "a second
proof file run under cvc5", which this is.

**Left out, to arrive with their parts.** The narrowing's guard dead where `p - s` digits fit and
the narrow-target table test go with 350a1n; the integral and `BOOLEAN` sources' table test with
350a1b; and the decline of an ANSI or TRY narrowing of a computed 17- or 18-digit decimal
(`COMPUTED_NARROW_REASON`, the review finding of 20 November) with 350a1n, whose casts it guards.

**Owed: the planted fault in code.** Section 3 item 3's flag, the rescale up's multiply first and
check after, was a static field in `VarkaDecimalCastFault`; VARKA-548's `global-fault` lint now
refuses a JVM-wide fault and wants it as a `VarkaEmitOptions` fault option, as `IntervalFault`
is. That is a main-code change to the options (and their matrix configurations) in a part that is
otherwise tests and proofs, so it is left for its own change; `decimal_casts.smt2` refutes the
order meanwhile. Also owed: the cast rows' refinement in `decimal_refinement.smt2`; the coverage
rows' `verified` field, still `differential`, pointed at `decimal_casts.smt2`; and section 3 item
1's sample of each class run against `Cast.eval` per mode, which the table suite does not do (the
query suite runs each cast against vanilla Spark in the three modes).

**The review's fix round** (`/code-review high`, nine nits, no defect): the table test now also
checks each division's half, shift count, sign restore and guard mode, and both its tests pin
the defaults; the half-one-less neighbours are stated where each form runs, the signed half at
precision 15 and the magnitude's half at 18; the divisor suite names `decimal_divide.smt2`; the
lint's time is restated where the minute was claimed. Kept: the 24 s cvc5 figure, measured with
nothing else running (only the 24-divisor re-measure overlapped an sbt run).
