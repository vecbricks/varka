# VARKA-350: The 64-bit decimal casts

***Corrected 1 November 2026 (`VARKA-365.md` 11).** The owner decided that Varka follows vanilla
Spark, which answers the ANSI and TRY casts of a decimal of precision up to 18 to `INT`,
`SMALLINT` and `TINYINT` by the exact rule on every SQL read of the Arrow cache, and row 365 made
Varka's own row reads hand such a decimal long-backed. So this row builds the exact rule for those
three casts, integer arithmetic proven by SMT, and the double rule is row 364's alone, above
precision 18. Wherever this plan says the kernel follows the double rule below precision 19, or
reads its answer off the `BigDecimal`-backed column, that text is superseded by this note and is
reworked when the row is built.*

***Changed on 28 November 2026.** Sections 11 to 14 are new: what is left of the row, cut into
parts for a builder with nobody to ask. Sections 0 to 10 are the plan and its record, kept word
for word; where section 11 differs it wins, and section 14 lists each difference. On master:
the baseline (#1572), the casts to `BIGINT`, to `BOOLEAN` and between decimals (#1700) and their
proofs (#1707).*

***Fix round of 28 November 2026.** Since #1708 (`e52b6bc082a`, SPARK-60843, 25 November 2026)
vanilla Spark has no double rule at any precision: `Decimal.roundToNumeric` truncates a
`BigDecimal`-backed value exactly too (`truncatedLong`). So the note above, where it gives the
double rule to row 364, and every text of this file that has Spark narrow a computed decimal
through the double, are stale; 11.3's decision 1 and section 14 items 2 and 5 say what holds.
The owner's answers of 28 November 2026 are in section 12.*

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
to row 103 (or 332's interval work), `TIME` to decimal to `m10/SCOPE.md` (no `TIME`-cast row exists),
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

## 11. What is left, cut into parts a Sonnet-class agent can build (28 November 2026)

*New on 28 November 2026. Code facts were read on master `6dafb8bd2f6` and on the local branches
named below, in `/home/max/proj/vecbricks-varka`, with no JVM. This section stands alone: a
builder needs it, the `AGENTS.md` files and `sql/varka/ADDING_AN_EXPRESSION.md`. Where it differs
from sections 0 to 10 it wins, and section 14 lists each difference. Short names: `C` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`, `CT` the same
path under `src/test/java`, `CS` under `src/test/scala`, `DCC` is
`C/VarkaDecimalCastCompiler.java`, `DC` is `C/VarkaDecimalCompiler.java`, `QS` is
`sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaDecimalCastQuerySuite.scala`, `TS`
is `CT/VarkaDecimalCastTableSuite.java`, and `BENCH` is
`sql/core/src/test/java/org/apache/spark/sql/execution/benchmark`.*

### 11.1 The state on 28 November 2026

**On master**, each with its command, run from the repository root:

| what | command | prints |
|---|---|---|
| the baseline, forty sections (#1572) | `git log --oneline -1 e0b5fa6d655` | `[VARKA-350] Benchmark the 64-bit decimal casts on the row path first (#1572)` |
| 350a1: to `BIGINT`, to `BOOLEAN`, between decimals (#1700) | `git log --oneline -1 56a61bf3242` | `[VARKA-350] Fuse 64-bit decimal casts to BIGINT, BOOLEAN and between decimals (#1700)` |
| 350a2: the proofs of #1700's rules (#1707) | `git log --oneline -1 325511d01a3` | `[VARKA-350] Prove the 64-bit decimal casts' divisions and rescale guards (#1707)` |
| the narrow targets decline by name | `grep -c 'NARROW_REASON' DCC` | `3` |
| an integral or boolean source declines by name | `grep -c 'to a decimal is row 350' DC` | `1` |
| a decimal cast with a floating side declines by name | `grep -c 'a decimal cast with a floating side' C/VarkaNodeCompiler.java` | `1` |
| the proof file's checks | `grep -c '(check-sat)' sql/varka/proofs/decimal_casts.smt2` | `1109` |
| the query suite's tests | `grep -c 'test("' QS` | `8` |
| the table suite's tests | `grep -c '@Test' TS` | `2` |

A cast into a decimal is a projection output of its own since #1628 (`DC.output`), so the
sentence in `DCC.arm`'s javadoc, "as an output it declines naming row 157's store", is stale;
part 350a1b corrects it.

**On local branches, never pushed.** `git branch --list 'varka-350*'` prints ten. What each
holds, and whether a part starts from it:

| branch | tip, date | holds | verdict |
|---|---|---|---|
| `varka-350a1n-r2` | `2131486f733`, 25 November | #1700 and #1707's commits, then the narrow targets: `a3c982c766f` (code and tests, 308 lines added, 114 removed), `d61f1802037` (generated files and `decimal_casts.smt2`), `2131486f733` (13 lines) | **part 350a1n starts from it**: its base holds row 489's last part (#1696), so two cherry-picks |
| `varka-350a1b-integral-sources` | `2b2ee08070b`, 20 November | the integral and `BOOLEAN` sources (531 lines added), on a base of 18 November, before #1628 and row 489 | **part 350a1b re-ports it by hand**: it adds no IR node, so row 489 costs nothing, but its base is ten days old |
| `varka-350a2-proofs` | `dbf7f8c9c34`, 20 November | the proof part as first written, over 350a1b | superseded by #1707; read only for 350a1b's table test |
| `varka-350a1n-narrow-targets` | `662afddf5bc`, 25 November | an earlier state of #1700's branch | nothing to take |
| `varka-350a2-proofs-r2`, `varka-350a1-r2`, `varka-350a2-r2` | 26, 25 and 23 November | the merged pull requests' branches | merged |
| `varka-350-decimal-casts-64`, `varka-350a-integer-casts`, `varka-350a-wip-backup` | 20 November | the baseline and the first build before its split | merged or superseded |

No branch holds the floating targets (350b), 350c or 350d.

**What moved since the plan was written.** Rows 364 and 366 moved to milestone 9 on
25 November 2026 (`PLAN.md` 1.1's note), and rows 481 and 504's remainders to `m10/SCOPE.md`
item 140 on 27 November. So: the floating targets at precision 16 to 18 and the wide precisions
are not milestone 8's; `LIMB_MAGIC` (350d) needs row 360's `WideMul`, which is not on master; and
the planted fault's home, row 504's planted-fault harness, left the milestone.

### 11.2 The parts

| part | what | counted lines | builder | starts from | waits on |
|---|---|---|---|---|---|
| 350a1n | the casts to `TINYINT`, `SMALLINT` and `INT`; first, the gate test of #1708's answer | about 435 (the picks count 422 and 13; the deleted decline and the new test about even) | Sonnet-class | `varka-350a1n-r2`, two cherry-picks | nothing |
| 350a1b | the casts into a decimal from the integral types and `BOOLEAN` | about 620 | Sonnet-class | master, by hand from `2b2ee08070b` | 350a1n |
| 350b1 | the casts to `DOUBLE` at precision 15 or less and to `FLOAT` at 7 or less, and both at scale 0 | about 450 | Sonnet-class | master | 350a1n, and the two checks of 11.9 |
| 350m | the measurement, the predictions scored, the row marked | about 120, results files apart | Sonnet-class | master | the three above, and row 597's part 597b if it merged |

**The order and its reason.** 350a1n first: it is built, and it rewrites the class javadoc and
`toIntegral`, which the other two extend. 350a1b second: row 346's part 346c edits the same arm
of `DC.cast` (the sources that are not a decimal), and `PLAN.md` 12.5 orders "350 before 346c".
350b1 third: it is independent of 350a1b and may be built beside it.

**Landing the plan.** *[Fix round, 28 November 2026, finding 2 of review 1.]* The new text of
this file (sections 11 to 14 and the notes at its top, over 700 lines more than master's 893)
goes to master first, in its own plan-only pull request titled "Plan: re-plan row 350's last
parts for a Sonnet-class builder"; plan-only pull requests are exempt from the 1000-line cap,
and the lead opens it. Each part's branch starts from a master that has it, and a part's pull request adds
only the record its "Done when" names (a subsection of section 10).

**Not planned here, each with its home** (question 1 of section 12 asks the owner to confirm):

- `FLOAT` at precision 8 to 15 with a scale of 1 or more: the round-to-odd form. Its tree is
  written out and checked in 11.6, but it is a lowering that rounds, so the house rule asks a
  proof file of it. It declines naming row 366 of `m9/PLAN.md`, whose exact arm covers it.
- 350c, the floating targets at precision 16 to 18: row 366's, milestone 9.
- 350d, `LIMB_MAGIC`: `m10/SCOPE.md` item 140, a performance arm for twelve `(p, k)` pairs.
- The planted fault as an emit option: `m10/SCOPE.md` item 140, with row 504's remainder.
  `decimal_casts.smt2` refutes the multiply-then-check order meanwhile.

### 11.3 Decisions taken here, each with its evidence

1. **The narrow ANSI and TRY casts follow the exact rule at every precision to 18, with no
   decline for a computed decimal.** *[Fix round, 28 November 2026: replaces the decision that
   a computed decimal of 17 or 18 digits declines.]* Since #1708 (`e52b6bc082a`, SPARK-60843)
   vanilla Spark truncates a `BigDecimal`-backed decimal exactly too, so both backings answer as
   the kernel does. The evidence, on master `6dafb8bd2f6`:

| fact | where |
|---|---|
| #1708 is on master | `git merge-base --is-ancestor e52b6bc082a 6dafb8bd2f6` exits 0 |
| `roundToByte`, `roundToShort`, `roundToInt` call `roundToNumeric`, which calls `truncatedLong`: `actualLongVal` for a long-backed value, `bd.toBigInteger` for a `BigDecimal`-backed one | `sql/api/src/main/scala/org/apache/spark/sql/types/Decimal.scala` lines 271 to 334 |
| an ANSI cast of a decimal to a narrow target calls `roundToByte` and its kin, interpreted and in codegen | `Cast.scala` lines 1425, 1493 and 2585 to 2591 |
| a `try_cast` of a decimal takes the ANSI path: `canUseLegacyCastForTryCast` holds only from a string | `Cast.scala` lines 893 to 895 and 1782 to 1792 |
| the branch `varka-350a1n-r2` predates #1708 | `git merge-base --is-ancestor e52b6bc082a varka-350a1n-r2` exits 1 |

   So the branch's `COMPUTED_NARROW_REASON` and `DOUBLE_RULE_PRECISION` are a needless decline
   whose text is false on master, and its query-suite test that expects null from
   `try_cast(x + z AS TINYINT)` at `127.999999999999999` fails on master, where vanilla answers
   127. Part 350a1n deletes the decline and that test (11.4 step 4) and adds the test of
   11.4 step 1, which holds 127 with Varka on and off. A column read from the Arrow cache was
   long-backed before #1708 too (`VARKA-365.md` 11), so nothing else of 10.3's decision 1
   changes.
2. **An integral source's guard is the rescale up's, already proven.** `decimal_casts.smt2`
   states, for every long `u` and every `1 <= k <= P <= 18`, that `|u| <= 10^(P - k) - 1` holds
   exactly where `u * 10^k` has `P` digits, with no wrap (`VarkaDecimalCastProofFile.rescales`,
   "P = ..., k = ...: ... with no wrap"). An integral `v` into `DECIMAL(P, S)` is that claim at
   `k = S`; at `S = 0` there is no multiply and the guard is the precision check itself. So
   350a1b adds no proof claim. The guard is dead exactly where the type's widest value fits:
   `verify_350.py` checks `digits <= P - S` against `|MIN_VALUE| <= 10^(P - S) - 1` for every
   type and every `(P, S)`, and 350a1b's table test restates it from the code.
3. **To `DOUBLE` at precision 15 or less: one division of two exact doubles.** `|u| <= 10^15 - 1`
   is below `2^53`, so `(double) u` is exact; `10^s` is exact for `s <= 22`; IEEE division rounds
   the exact quotient once, which is `BigDecimal.doubleValue`'s answer (item 0.4).
   `verify_350.py` compares `float(u) / float(10**s)` with the correctly rounded `u / 10**s` over
   the bounds and 300 drawn values of every `(p, s)`.
4. **To `FLOAT` at precision 7 or less: the same double quotient, narrowed.** `|u| <= 10^7 - 1`
   is below `2^24` and `10^s` is a float for `s <= 7`, so both operands are binary32 values; the
   binary64 quotient of two binary32 values, narrowed, is their binary32 quotient (the double
   rounding is innocuous where `53 >= 2 * 24 + 2`). So the tree is the `DOUBLE` tree and one
   `ConvertLane` to `FLOAT`, with no int lane. This replaces 2.4's int-lane form, whose reason
   was `L2F`'s cost on the EPYC 7763; the long is converted by `L2D` here, whose default form is
   exact for every long on every host (`VarkaConversionLowering`, `XOR_SPLIT`). `verify_350.py`
   checks it against the exact rational rounded once to binary32. The sweep of 11.7 exhausts it
   in Java: at most `2 * 10^7` values at seven scales.
5. **At scale 0, both targets are one `ConvertLane` of the unscaled long, at every precision to
   18.** `L2D` and `L2F` round the exact integer once (`ConvertLane`'s javadoc).
6. **At two long lanes on x86 the new arms decline as the family's others do.** *[Fix round,
   28 November 2026: the owner answered `VARKA-597.md` 11.7 question 1 "remove".]* Row 597's
   part 597b deletes `DCC.TWO_LANE_REASON` and its three tests: a decimal operand declines in
   the decimal readers (`VarkaDecimalCompiler.TWO_LANE_READ_REASON`), and a cast into a decimal
   from a source that is not a decimal declines at the head of `DCC.intoDecimal` by the same
   reason (`VARKA-597.md` 11.5 step 3b). Each part of this row reads which state master is in,
   with one command:

        grep -c 'TWO_LANE_READ_REASON' C/VarkaDecimalCompiler.java

   `0` (597b not merged): the part writes its two-lane test as this section shows, with
   `TWO_LANE_REASON`. `1` or more (597b merged): the part writes no two-lane test in its arms;
   where a step below names `TWO_LANE_REASON`, it is skipped, and a test that expects it expects
   `VarkaDecimalCompiler.TWO_LANE_READ_REASON`. 597b then needs nothing from this row.
7. **`PLAN.md` section 3 item 1's "sample of each class against `Cast.eval`" is met by two suites
   that exist**: `QS` runs every cast against vanilla Spark in LEGACY, ANSI and TRY, and
   `VarkaCoverageDifferentialSuite` runs each coverage row inside and outside its domain. No
   third sampler is built.

### 11.4 Part 350a1n: the narrow targets

*[Rewritten in the fix round of 28 November 2026, finding 1 of review 1: #1708 removed the double
rule from Spark, so the branch's decline of a computed decimal and its test go, and the part
opens with a gate test on master.]*

**Title:** `[VARKA-350] Fuse 64-bit decimal casts to TINYINT, SMALLINT and INT`.
**Branch:** `varka-350a1n-r3`, from master (`varka-350a1n-r2` and `varka-350a1n-narrow-targets`
exist). **Builder:** Sonnet-class.

**B0.** The table of 11.1's rows four, seven, eight and nine must print `3`, `1109`, `8`, `2`,
and:

    git log --oneline -1 a3c982c766f   -> [VARKA-350] Fuse the decimal casts to TINYINT, SMALLINT and INT again
    git log --oneline -1 2131486f733   -> [VARKA-350] Seed narrowed outputs off the targets' bounds, and name the decimal casts WHOLE
    git merge-base --is-ancestor e52b6bc082a HEAD && echo has-1708                 -> has-1708
    grep -c 'record IntegralRange' C/varka/VarkaTypes.java                        -> 1
    grep -c 'TWO_LANE_READ_REASON' DC                                             -> 0 or 1 (step 3 reads it)

**Step 1, the gate: master's answer first.** Before any cherry-pick, on the new branch, add this
test to `QS` after the test "a decimal cast to BIGINT truncates toward zero in every mode", and
add `import org.apache.spark.sql.execution.VarkaMaybeThrows.Answered` after the `Row` import:

    test("a computed DECIMAL(18, 15) of 127.999999999999999 narrows to 127 under ANSI and TRY, " +
        "Varka on and off, as vanilla Spark truncates it exactly since SPARK-60843") {
      // VARKA-350.md 11.4 step 1. x + z is a sum of two scales, which vanilla Spark builds
      // BigDecimal-backed; before #1708 that value narrowed through the double, 128.0, and the
      // cast overflowed. Catches a kernel, or a residual cast over a fused sum, that rounds the
      // fraction up, and a Varka answer that differs from vanilla's.
      val sums = "varka_decimal_sums"
      val schema = StructType(Seq(StructField("x", DecimalType(17, 15)),
        StructField("z", DecimalType(2, 0))))
      val data = Seq(Row(dec("99.999999999999999"), dec("28")),
        Row(dec("-99.999999999999999"), dec("-29")), Row(null, dec("0")))
      Seq(spark, varkaSpark).foreach { session =>
        session.createDataFrame(session.sparkContext.parallelize(data, 1), schema)
          .createOrReplaceTempView(sums)
        session.catalog.cacheTable(sums)
      }
      try {
        val forms = Seq(
          ("ANSI", true, s"SELECT CAST(x + z AS TINYINT) AS a FROM $sums"),
          ("TRY", true, s"SELECT try_cast(x + z AS TINYINT) AS a FROM $sums"),
          ("TRY", false, s"SELECT try_cast(x + z AS TINYINT) AS a FROM $sums"),
          ("TRY over a fused sum", true,
            s"SELECT try_cast(s AS TINYINT) AS a, s FROM (SELECT x + z AS s FROM $sums)"))
        for ((mode, ansi, query) <- forms) {
          withAnsi(ansi) {
            withClue(s"$mode, ANSI $ansi: $query") {
              checkSparkAnswerMaybeThrows(query) match {
                case Answered(rows) =>
                  assert(rows.map(_.get(0)).toSet === Set[Any](127.toByte, -128.toByte, null))
                case r => fail(s"expected 127, -128 and null, both raised $r")
              }
            }
          }
        }
      } finally {
        Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable(sums))
      }
    }

`checkSparkAnswerMaybeThrows` runs the query with Varka on (`varkaSpark`) and off (`spark`) and
fails with both outcomes printed when they differ (`VarkaMaybeThrows.scala` line 74). Run:

    build/sbt 'sql/testOnly *VarkaDecimalCastQuerySuite -- -z "127.999999999999999"'
        -> Tests: succeeded 1, failed 0

**The stop rule.** If the test fails, the builder stops, commits nothing more, and reports the
failure text to the lead:

- a failure that prints two different outcomes (Varka on against off) is a wrong answer on
  master, the finding the owner asked a gate row for; the lead files the row;
- a failure where both engines agree but answer null, or both raise, means #1708's premise is
  false on this master, and this section's decision 1 (11.3) is wrong: the plan goes back to its
  planner.

On a pass, commit the test alone ("Hold a computed decimal's narrowing to vanilla Spark's
exact answer") and go on.

**Step 2, the picks.** `git cherry-pick a3c982c766f`, then `git cherry-pick 2131486f733`. Do not
pick `d61f1802037`: it is generated files, which step 5 regenerates.

**Step 3, conflicts.** The rule for each file: take master's side whole, then apply the commit's
hunks for that file by hand, reading them with `git show a3c982c766f -- <file>`.

- `sql/varka/plans/m8/PLAN.md` and `sql/varka/plans/m8/VARKA-350.md`: take master's side and
  apply nothing. The record is this file's section 10.5, added at the close of the part.
- `QS`: keep step 1's test; the import line becomes the commit's
  `import org.apache.spark.sql.execution.VarkaMaybeThrows.{Answered, Raised}`.
- `BENCH/VarkaDecimalBaselineBenchmark.java`: the commit turns four sections from
  `Fusion.NONE` to the `fused` argument of `addCasts`: `CAST(x9 AS INT)` under LEGACY and under
  ANSI, `try_cast(x9 AS SMALLINT)`, and `CAST(x18 AS INT)` under LEGACY. The two over `x18s14`
  (`DECIMAL(18, 14)`, ANSI and `try_cast`) fuse too, the column being a bare column. If row
  597's part 597b merged first, keep its `fusedFilter` edits and apply this table to the lines
  that still say `Fusion.NONE`.
- `CS/varka/VarkaCoverageSuite.scala`: five rows join the family "Decimal casts":
  `CAST(d9 AS INT)`, `CAST(d18 AS INT)` with the domain `d18 > -2147483649 AND d18 <
  2147483648`, `CAST(d9 AS SMALLINT)` with `d9 > -32769 AND d9 < 32768`, `CAST(d9s4 AS
  TINYINT)` with `d9s4 > -129 AND d9s4 < 128`, and `try_cast(d18 AS INT)` with the division's
  domain `abs(d18) * 100 < 18014398509481984`.
- If B0's last line printed `1` (597b is on master): in `DCC.toIntegral` and `DCC.test`
  the commit's context lines `if (sink.declinesTwoLaneChecks()) { return
  sink.decline(TWO_LANE_REASON, c); }` are gone on master; leave them out. In
  `CS/VarkaExpressionCompilerSuite.scala` the commit's one-line hunk (`Cast(x, IntegerType,
  evalMode = EvalMode.ANSI)` in the test "a decimal cast's value declines on a two-lane host")
  is applied to the test as 597b left it, whose expected reason is
  `VarkaDecimalCompiler.TWO_LANE_READ_REASON`.

**Step 4, the computed decimal's decline goes** (11.3 decision 1). After the picks, in `DCC`:

1. In `toIntegral`, delete the four lines

        if (target != LaneType.LONG && mode != Overflow.WRAP
            && from.precision() >= DOUBLE_RULE_PRECISION && !(c.child() instanceof BoundReference)) {
          return sink.decline(COMPUTED_NARROW_REASON, c);
        }

   and keep the line above them, `Overflow mode = VarkaNodeCompiler.overflowOf(c.evalMode());`,
   which the guard reads.
2. Delete the constant `DOUBLE_RULE_PRECISION` with its javadoc, and the constant
   `COMPUTED_NARROW_REASON` with its javadoc (the commit's text, `git show 2131486f733:<DCC>`
   lines 116 to 134). Delete `import org.apache.spark.sql.catalyst.expressions.BoundReference;`,
   which nothing else in `DCC` reads after the deletion (`grep -c BoundReference DCC` prints 0;
   part 350a1b imports it again).
3. In the class javadoc, the item "to an integral type" ends at "nulling the lane under TRY;":
   delete its last sentence, "Under ANSI and TRY a narrowing of a computed decimal of 17 or 18
   digits declines ({@link #COMPUTED_NARROW_REASON});".
4. In `QS`, delete the commit's test "an ANSI or TRY narrowing of a computed decimal of 17 or 18
   digits declines, since Spark narrows its BigDecimal-backed value through the double: x + z
   at 127.999999999999999" whole (its assertion `rows.head === Row(null)` is the pre-#1708
   answer). Step 1's test holds the same cells to 127. In the comment above the column `x`,
   "A mixed-scale sum of these, DECIMAL(18, 15), is BigDecimal-backed in Spark." becomes "A
   mixed-scale sum of these, DECIMAL(18, 15), is BigDecimal-backed in vanilla Spark, which
   truncates it exactly since SPARK-60843 (#1708)."
5. In `QS`'s class javadoc, the first paragraph becomes, word for word:

        The 64-bit decimal casts (VARKA-350) over an Arrow-cached table, Varka against vanilla
        Spark in LEGACY, ANSI and TRY: the rows both answer, or the error both raise. Each column
        holds the named cases of `VARKA-350.md` 3 item 2 for its type - the values a range
        check through a double gets wrong for INT (`0.999999999999999999` as `DECIMAL(18, 18)`,
        `2147483647.99999999` as `DECIMAL(18, 8)` and the three first differences of item
        0.14, in `f`, `k` and `m`), each target's bounds and one past them, `-0.5`, `-1.5` and
        `1.5`, Trino's `184467440737095517` at `(18, 0)` into `(18, 2)`, and `9.99` and `9.95`
        into `(2, 1)` (the rounding carry that overflows) - with a null beside them. The
        division guard's boundaries are the coverage rows' domains.

   each line prefixed ` * ` as the file's javadoc is; the second paragraph and the
   `varka-session-lint` comment are master's.
6. Step 1's test now also checks the fused form: after its `match`, for the two forms that are
   not "over a fused sum", add

        val explained = varkaSpark.sql(query).queryExecution
          .explainString(org.apache.spark.sql.execution.ExplainMode.fromString("formatted"))
        if (!mode.startsWith("TRY over")) assert(explained.contains("a: fused"), explained)

   If that assertion fails, the builder prints the entry's residual reason from `explained` and
   stops: the plan claims the computed narrowing fuses once the decline is gone.

In `COMPUTED_NARROW_REASON`'s place nothing is added: with it gone, the change of the old plan's
step 2 ("(VARKA-350, rows 350c and 366)" to "(row 366 of m9/PLAN.md)") has nothing to change.

**Step 5, regenerate:** `VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly
*VarkaProofFilesSuite'`, then `dev/varka_regen_generated.sh --all`.

**What the code is, for a reader without the branch.** In `DCC.toIntegral`, after the two-lane
test (or with none, once 597b merged), with `t = truncated(u, p, s, sink)`; the picks' `fits` is
a static method of `DCC`, `static boolean fits(DecimalType from, LaneType target)`:

    Overflow mode = VarkaNodeCompiler.overflowOf(c.evalMode());
    return operand(c, inputs, literals, sink).map(x -> {
      VarkaVectorIR t = truncated(x.unscaled(), x.type().precision(), x.type().scale(), sink);
      if (target == LaneType.LONG) {
        return t;
      }
      // A narrow target: the guard only where a value can leave the target's range.
      VarkaTypes.IntegralRange range = VarkaTypes.integralRange(target);
      VarkaVectorIR guarded = mode == Overflow.WRAP || fits(x.type(), target)
          ? t : new GuardedRange(t, range.min(), range.max(), mode);
      return new NarrowLane(guarded, target);
    });

    static boolean fits(DecimalType from, LaneType target) {
      return Decimal.POW_10()[from.precision() - from.scale()] - 1L
          <= VarkaTypes.integralRange(target).max();
    }

`fits` is exact: `verify_350.py` checks it against the widest truncated value at every `(p, s)`
and target, and the first value the guard rejects and the last it admits in both signs.

**The tests the picks bring**, and the failure only each catches: `QS`'s two tests of the narrow
targets (the bounds of each target and one past, in three modes, and `CAST_OVERFLOW`'s text;
catches a guard one off); `TS`'s narrow-target table test (the guard kept exactly where the
source's widest integral part leaves the range; catches a dead guard kept or a live one
dropped); `VarkaKernelChecks.runLoadedDecimal` laying out a narrowing root (so
`VarkaDecimalLongConformanceSuite`'s cast row runs the narrow forms against Spark);
`VarkaConjunctOrderSuite` and `VarkaDifferentialSuite`'s residual casts moved to 19 digits.
Step 1's test catches a computed 17 or 18 digit narrowing that rounds up, fused or residual.

**The commands, in order.**

    build/sbt catalyst/Test/compile sql/Test/compile                         -> [success]
    grep -c 'NARROW_REASON\|DOUBLE_RULE_PRECISION' DCC                       -> 0
    grep -c '(check-sat)' sql/varka/proofs/decimal_casts.smt2                -> 1280
    build/sbt 'catalyst/testOnly *VarkaDecimalCastTableSuite'                -> Passed: Total 3, Failed 0
    build/sbt 'sql/testOnly *VarkaDecimalCastQuerySuite'                     -> Tests: succeeded 10, failed 0
    build/sbt 'catalyst/testOnly *VarkaDecimalLongConformanceSuite *VarkaConjunctOrderSuite *VarkaFamilySwitchSuite *VarkaCoverageSuite'
        -> failed 0
    build/sbt 'sql/testOnly *VarkaDecimalBaselineQueriesSuite *VarkaCoverageHarnessSuite *VarkaDifferentialSuite'
        -> failed 0
    dev/varka_prove.sh --lint                                                -> exits 0
    python3 dev/varka_msg_check.py size                                      -> under 1000 (about 435)
    dev/varka_ops/pre-pr-slot.sh <w>                                         -> pre-pr: passed on <sha>

`QS`'s ten: master's eight, less the row-engine test of the narrow targets the commit deletes,
plus the commit's three ("each integral type" replaces master's `BIGINT` test in place, so it
is not counted again: "0.999999999999999999", the computed decimal's, `CAST_OVERFLOW`), plus
step 1's, less step 4's deletion: `8 - 1 + 3 + 1 - 1 = 10`. If `VarkaDifferentialSuite` fails
on a computed 17 or 18 digit narrowing with Varka's answer differing from vanilla's, the builder
stops and reports the case: it is a kernel fault, not a Spark rule, and the decline does not
come back.

**Done when** the commands print what is stated, the Build and the option matrix are green, and
section 10 gains "10.5 Step 3, part 350a1n" with the text the branch's commit `a3c982c766f`
wrote for it (`git show a3c982c766f -- sql/varka/plans/m8/VARKA-350.md`), less its paragraph on
the computed decimal's decline, plus one sentence: "The decline of a computed decimal of 17 or
18 digits was dropped, since #1708 Spark truncates both backings exactly; step 1's test holds
127.999999999999999 to 127 with Varka on and off", dated with the merge.

**Not here.** The integral sources. Any floating target.

### 11.5 Part 350a1b: into a decimal from the integral types and `BOOLEAN`

**Title:** `[VARKA-350] Fuse casts of integral types and BOOLEAN into a 64-bit decimal`.
**Branch:** `varka-350a1b-r2`, from master after 350a1n. **Builder:** Sonnet-class.

**B0.**

    grep -c 'to a decimal is row 350' DC                                          -> 1
    grep -c 'static boolean claimsIntoDecimal' DCC                                -> 1
    grep -c 'private static Optional<VarkaVectorIR> select(' C/VarkaIntegralCastCompiler.java  -> 1
    grep -c 'case SELECT -> () -> select(c, inputs, literals, sink);' C/VarkaIntegralCastCompiler.java -> 1
    grep -c 'static Optional<VarkaVectorIR> intOperand' C/VarkaNodeCompiler.java  -> 2
    grep -c 'COMPUTED_NARROW_REASON' DCC                                          -> 0
    grep -c 'TWO_LANE_READ_REASON' DC                                             -> 0 or 1 (step 3 reads it)
    git log --oneline -1 2b2ee08070b   -> [VARKA-350] Fuse the casts into a 64-bit decimal from the integral types and boolean

**What Spark computes** (section 0, items 7 and 8). `CAST(v AS DECIMAL(P, S))` for an integral
`v` is `v * 10^S` where `|v| <= 10^(P - S) - 1`, and past it null under LEGACY and TRY and
`NUMERIC_VALUE_OUT_OF_RANGE` under ANSI. For a boolean it is `10^S` or 0, the true overflowing
only into `DECIMAL(P, P)`. `DecimalType.forType` gives the digits of each type's widest value:
3 for `TINYINT`, 5 for `SMALLINT`, 10 for `INT`, 20 for `BIGINT`.

**Steps.** The old commit's main-code diff is the reference: `git show 2b2ee08070b -- C`. Apply
it by hand on master; every hunk still has its anchor but the three named below.

1. `C/VarkaIntegralCastCompiler.java`: `select` becomes package-private and takes the boolean
   expression and the target lane:
   `static Optional<VarkaVectorIR> select(Expression bool, LaneType target, Cast c,
   VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink)`; its first lines read
   `bool` and `target` in place of `c.child()` and `laneOf(c.dataType())`; the `SELECT` arm
   calls `select(c.child(), laneOf(c.dataType()), c, inputs, literals, sink)`.
2. `DCC`, the claims. `claimsIntoDecimal(Cast c)`:

        if (!(c.dataType() instanceof DecimalType to)) {
          return false;
        }
        DataType from = c.child().dataType();
        if (from instanceof DecimalType f) {
          return !VarkaDecimalCompiler.keepsDigits(f, to);
        }
        // A target wider than 18 digits is the wide rows', whose reason value() names.
        return to.precision() <= MAX_PRECISION
            && (integralLane(from).isPresent() || DataTypes.BooleanType.equals(from));

   and two helpers:

        /** The digits of an integral type's widest value, DecimalType.forType's precision. */
        private static int integralDigits(LaneType lane) {
          return switch (lane) {
            case BYTE -> 3;
            case SHORT -> 5;
            case INT -> 10;
            case LONG -> 20;
            case FLOAT, DOUBLE -> throw VarkaErrors.illegalArgument("no integral type on " + lane);
          };
        }

        /** Whether a cast from an integral type or BOOLEAN into `to` can overflow. */
        static boolean sourceCanOverflow(DataType from, DecimalType to) {
          if (DataTypes.BooleanType.equals(from)) {
            return to.precision() == to.scale();
          }
          return integralDigits(integralLane(from).orElseThrow()) > to.precision() - to.scale();
        }

3. `DCC.intoDecimal`, after its two-lane test and its `mode`. *[Fix round, 28 November 2026.]*
   Which two-lane test is there depends on B0's third line from the end: with `0` (597b not
   merged) it is master's `if (sink.declinesTwoLaneChecks()) { return
   sink.decline(TWO_LANE_REASON, c); }`, which declines every source; leave it. With `1` (597b
   merged) it is 597b's `if (!(c.child().dataType() instanceof DecimalType) &&
   VarkaDecimalCompiler.declinesAtTwoLanes(c, sink)) { return Optional.empty(); }`, which
   declines the sources this part adds; leave it too. Either way this part adds no two-lane code.
   The new lines:

        DataType from = c.child().dataType();
        if (from instanceof DecimalType) {
          return VarkaDecimalCompiler.value(c.child(), inputs, literals, sink)
              .map(x -> rescaled(x, to, mode, sink));
        }
        if (DataTypes.BooleanType.equals(from)) {
          return VarkaIntegralCastCompiler.select(c.child(), LaneType.LONG, c, inputs, literals,
              sink).map(one -> sourceCanOverflow(from, to)
                  // Only DECIMAL(P, P) has no integral digit for the 1; its 0 needs no multiply.
                  ? new Scaled(new GuardedRange(one, 0, 0, mode), to)
                  : new Scaled(timesPowerOfTen(one, to.scale(), sink), to));
        }
        LaneType lane = integralLane(from).orElseThrow();
        return integralOperand(c, lane, inputs, literals, sink).map(v -> {
          VarkaVectorIR guarded = sourceCanOverflow(from, to)
              ? guardDigits(v, to.precision() - to.scale(), mode) : v;
          return new Scaled(timesPowerOfTen(guarded, to.scale(), sink), to);
        });

   with

        /** An integral operand on the long lane: the long itself, or the value sign-extended. */
        private static Optional<VarkaVectorIR> integralOperand(Cast c, LaneType lane,
            VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
          Optional<VarkaVectorIR> compiled = lane == LaneType.INT
              ? VarkaNodeCompiler.intOperand(c.child(), inputs, literals, sink)
              : VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink);
          return compiled.flatMap(x -> {
            if (x.laneType() != lane) {
              return sink.decline("a " + c.child().dataType().simpleString() + " operand on "
                  + "the " + x.laneType() + " lane, not the " + lane + " one", c);
            }
            return Optional.of(lane == LaneType.LONG ? x : new WidenLane(x, LaneType.LONG));
          });
        }

   `guardDigits(v, 0, mode)` is `GuardedRange(v, 0, 0, mode)`: into `DECIMAL(P, P)` only zero
   fits, which is Spark's answer.
4. `DCC`, the validity. *[Fix round, 28 November 2026: written out, finding 7 of review 1.]*
   `sourceCanOverflow` throws for a decimal source (`integralLane(from).orElseThrow()`), so
   master's decimal half stays first. `unguardedIntoDecimal` becomes, whole:

        static boolean unguardedIntoDecimal(Cast c) {
          if (!(c.dataType() instanceof DecimalType to)) {
            return false;
          }
          DataType from = c.child().dataType();
          if (from instanceof DecimalType f) {
            return VarkaDecimalCompiler.keepsDigits(f, to) || !canOverflow(f, to);
          }
          return claimsIntoDecimal(c) && !sourceCanOverflow(from, to);
        }

   and a new method follows it (the old commit's text, `git show 2b2ee08070b --
   C/VarkaDecimalCastCompiler.java`, its second hunk), with `import
   org.apache.spark.sql.catalyst.expressions.BoundReference;` and the `IsNotNull` record of
   `VarkaVectorIR` imported:

        /**
         * The validity of an unguarded cast into a decimal from an integral column: the column's
         * own. A computed or boolean operand's null test is left to the row engine.
         */
        static Optional<Cond> operandValid(Cast c, VarkaSlotTable inputs, VarkaSlotTable literals,
            DeclineSink sink) {
          Optional<LaneType> lane = integralLane(c.child().dataType());
          if (lane.isEmpty() || !(c.child() instanceof BoundReference)) {
            return sink.decline("the validity of a cast into a decimal from a computed or boolean "
                + "operand (VARKA-350)", c);
          }
          // The operand is on the long lane already, a narrower column under its widening, which
          // is the form VarkaConditionCompiler.onLongLane gives a column's null test.
          return integralOperand(c, lane.get(), inputs, literals, sink).map(IsNotNull::new);
        }
5. `DC.cast`, the arm for a source that is not a decimal (**anchor 1**: on master the `if`
   block that opens `cast`, three lines: `if (!(c.child().dataType() instanceof DecimalType
   from)) {`, then `return sink.decline("a cast from " + c.child().dataType().simpleString()`
   and `+ " to a decimal is row 350's", c);` (`DC` lines 1398 and 1399 at `6dafb8bd2f6`), then
   its closing brace; replace that whole block by this one):

        if (!(c.child().dataType() instanceof DecimalType from)) {
          if (VarkaDecimalCastCompiler.claimsIntoDecimal(c)) {
            return VarkaNodeCompiler.inFamily(VarkaFamily.DECIMAL_CAST,
                () -> VarkaDecimalCastCompiler.intoDecimal(c, to, inputs, literals, sink), c, sink);
          }
          DataType source = c.child().dataType();
          boolean floating = DataTypes.FloatType.equals(source)
              || DataTypes.DoubleType.equals(source);
          return sink.decline("a cast from " + source.simpleString() + " to a decimal is "
              + (floating ? "row 346's" : "not fused; `VARKA-350.md` 7 names its row"), c);
        }

   Row 346's part 346c later replaces the `floating` half of this arm.
6. `DC.valid` (**anchor 2**), the arm `case Cast c when
   VarkaDecimalCastCompiler.unguardedIntoDecimal(c)`: its body becomes
   `c.child().dataType() instanceof DecimalType ? valid(c.child(), inputs, literals, sink)
   : VarkaDecimalCastCompiler.operandValid(c, inputs, literals, sink)`.
7. `DCC.arm` (**anchor 3**, changed by #1628): leave the code; replace the javadoc sentence "but
   its value is read only by the decimal compiler's readers - a comparison, an `IN`, a choice -
   and as an output it declines naming row 157's store" by "its value is compiled by the decimal
   compiler, under a comparison, an `IN`, a choice or as an output (`VarkaDecimalCompiler.output`),
   which never reaches this arm". Update the class javadoc's list and `VarkaFamily.DECIMAL_CAST`'s
   description to name the integral and boolean sources, as the old commit does.
8. Tests, from the old commit, re-typed on master's files:
   - `QS`: a `BIGINT` column `l` with `0`, `1`, `-1`, `9999999999999999`, `-9999999999999999`,
     `10000000000000000`, `-10000000000000000`, `Long.MaxValue`, `Long.MinValue`; an `INT`, a
     `SMALLINT`, a `TINYINT` and a `BOOLEAN` column with their bounds and zero. Three tests:
     "an integral value into a decimal is guarded by the target's integral digits and scaled"
     (through `CAST(CAST(v AS DECIMAL(P, S)) AS BIGINT)` and as an output, in three modes, at
     `(18, 2)`, `(5, 2)`, `(3, 0)`, `(10, 0)`, `(18, 18)`); "a boolean into a decimal is 1 or 0
     at its scale, true overflowing only into `DECIMAL(P, P)`" (`(3, 2)` and `(2, 2)`); and "at
     (18, 2), (16, 1), (17, 3) and (18, 7) the division's magnitude is guarded below
     `2^(52 + k)`" (`2^(52 + k) - 1` and `2^(52 + k)` at each, the batch inside the guard
     checked to run in the kernel). The old commit's `QS` hunks hold the exact SQL, to be
     copied, not re-typed: `git show 2b2ee08070b --
     sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaDecimalCastQuerySuite.scala`
     (110 lines added): the new columns in its hunks at old lines 61, 77 and 92, the three tests
     in its hunk at old line 217, and the guard view of the hunk at old line 92. None of the
     five columns it adds (`l`, `i`, `s`, `y`, `bo`) is a name of master's view after 350a1n
     (`a` to `h`, `k`, `m`, `x`, `z`). Its two javadoc hunks are not taken: master's class
     javadoc after 350a1n is the one to keep, with the clause "and the magnitudes either side of
     `2^(52 + k)`, where the division's guard first fires" added before "- with a null beside
     them".
   - `TS`, one new test, written out:

            @Test
            @DisplayName("an integral or boolean source's guard is kept exactly where its "
                + "widest value overflows the target")
            void sourceGuards() {
              var types = Map.of(DataTypes.ByteType, 1L << 7, DataTypes.ShortType, 1L << 15,
                  DataTypes.IntegerType, 1L << 31);
              for (int p = 1; p <= 18; p++) {
                for (int s = 0; s <= p; s++) {
                  var to = new DecimalType(p, s);
                  long bound = Decimal.POW_10()[p - s] - 1L;
                  for (var e : types.entrySet()) {
                    assertEquals(e.getValue() > bound,
                        VarkaDecimalCastCompiler.sourceCanOverflow(e.getKey(), to), to.toString());
                  }
                  // |Long.MIN_VALUE| is past every 10^(p - s) - 1 with p <= 18.
                  assertTrue(VarkaDecimalCastCompiler.sourceCanOverflow(DataTypes.LongType, to));
                  assertEquals(p == s,
                      VarkaDecimalCastCompiler.sourceCanOverflow(DataTypes.BooleanType, to));
                }
              }
            }

   - `CS/varka/VarkaCoverageSuite.scala`: `CAST(CAST(l AS DECIMAL(18, 2)) AS BIGINT)` with the
     domain `l > -10000000000000000 AND l < 10000000000000000` joins "Decimal casts"; `i < d9`,
     `CAST(y1 AS DECIMAL(5, 2)) < d9`, `CAST(s1 AS DECIMAL(7, 2)) > d9` and
     `CAST(b AS DECIMAL(3, 2)) < d9` join "Decimal cast predicates". In `familyVerified` add
     `"Decimal casts" -> Proved("decimal_casts.smt2")` and the same for "Decimal cast
     predicates"; if `VarkaCoverageSuite` then fails on the two `BOOLEAN` rows, give those two
     rows `Differential` in `rowVerified`.
   - `sql/core/src/test/java/org/apache/spark/sql/execution/VarkaAnsiForm.java`: the old commit's
     eleven lines.
   - `BENCH/VarkaDecimalBaselineBenchmark.java`: the two sections `BIGINT CAST(l AS
     DECIMAL(18, 2))`, LEGACY and ANSI, take `addCasts`'s `fused` argument.
9. Regenerate: `dev/varka_regen_generated.sh --all`. `casts_fused.tsv` gains the integral and
   boolean sources' `DECIMAL(10, 2)` forms; `decimal_casts.smt2` does not move (decision 2).

**The commands, in order.**

    build/sbt catalyst/Test/compile sql/Test/compile                         -> [success]
    dev/varka_emit.sh "CAST(l AS DECIMAL(18, 2))" --columns l:bigint         -> the line ends FusedOutput(0)
    build/sbt 'catalyst/testOnly *VarkaDecimalCastTableSuite'                -> Passed: Total 4, Failed 0
    build/sbt 'sql/testOnly *VarkaDecimalCastQuerySuite'                     -> Tests: succeeded 13, failed 0
    build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite *VarkaFamilySwitchSuite *VarkaFamilyChainSuite *VarkaDecimalValueSuite *VarkaCoverageSuite *VarkaCastMatrixSuite'
        -> failed 0
    build/sbt 'sql/testOnly *VarkaDecimalBaselineQueriesSuite *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite'
        -> failed 0
    git diff --stat -- sql/varka/proofs/                                     -> prints nothing
    python3 dev/varka_msg_check.py size                                      -> under 1000
    dev/varka_ops/pre-pr-slot.sh <w>                                         -> pre-pr: passed on <sha>

A test of `VarkaExpressionCompilerSuite` that asserts the decline "to a decimal is row 350's"
for an integral source now sees it fuse: change that assertion to the fused form, and keep the
decline's assertion for a `DOUBLE` source with the new text ("row 346's"). Before rewording the
decline, `grep -rn "is row 350" sql/catalyst/src/test sql/core/src/test` lists every test that
reads it.

**The two-lane test, by order with 597b.** *[Fix round, 28 November 2026.]* If B0's third line
from the end printed `1` (597b merged first), add `Cast(l, DecimalType(18, 2))` to the
`longLane` list of `CS/VarkaExpressionCompilerSuite.scala`'s test "a value that reads a decimal
column declines on a two-lane host, and fuses elsewhere" (597b's): the cast reads no decimal
column and declines at the head of `intoDecimal`. If it printed `0`, 597b adds that line
(`VARKA-597.md` 11.5 step 5).

**Done when** the commands print what is stated, the Build and the option matrix are green, and
section 10 gains "10.6 Step 3, part 350a1b" with what was built, what the tests hold and the
date.

**Not here.** A target wider than 18 digits (`BIGINT` into `DECIMAL(20, 0)` is row 360's). A
floating source (row 346's part 346c). A new proof claim.

### 11.6 Part 350b1: to `DOUBLE` and to `FLOAT`, where one division is exact

**Title:** `[VARKA-350] Fuse 64-bit decimal casts to DOUBLE and FLOAT where one division is exact`.
**Branch:** `varka-350b1-floating-targets`, from master after 350a1n. **Builder:** Sonnet-class,
after the lead has run the two checks of 11.9.

**B0.**

    grep -c 'a decimal cast with a floating side' C/VarkaNodeCompiler.java        -> 1
    grep -c 'static LiteralSlot doubleSlot(double v, DeclineSink sink)' C/VarkaNodeCompiler.java -> 1
    grep -c 'record ConvertLane(VarkaVectorIR child, LaneType target, int hash)' C/varka/VarkaVectorIR.java -> 1
    grep -c 'enum IntOp { ADD, SUB, MUL, DIV }' C/varka/VarkaVectorIR.java        -> 1
    grep -c 'COMPUTED_NARROW_REASON' DCC                                          -> 0
    grep -c 'TWO_LANE_READ_REASON' DC                                             -> 0 or 1 (step 1 reads it)

**The rule, as one table.** `u` is the unscaled long of `value(child)`, of `(p, s)`, `p <= 18`.

| target | where | tree |
|---|---|---|
| `DOUBLE` | `s = 0` | `ConvertLane(u, DOUBLE)` |
| `DOUBLE` | `s >= 1`, `p <= 15` | `q = IntArith(DIV, WRAP, ConvertLane(u, DOUBLE), doubleSlot(10^s))` |
| `DOUBLE` | `s >= 1`, `p >= 16` | declines, `FLOATING_EXACT_REASON` |
| `FLOAT` | `s = 0` | `ConvertLane(u, FLOAT)` |
| `FLOAT` | `s >= 1`, `p <= 7` | `ConvertLane(q, FLOAT)`, `q` as above |
| `FLOAT` | `s >= 1`, `p >= 8` | declines, `FLOATING_EXACT_REASON` |

No mode changes an answer, and no form raises or nulls: the validity is the operand's.
`doubleSlot(10^s)` is `VarkaNodeCompiler.doubleSlot((double) Decimal.POW_10()[s], sink)`, exact
for `s <= 15`. `Overflow.WRAP` on a floating `DIV` means "no zero test", as
`VarkaFloatingCompiler.divide` uses it for a non-zero literal divisor.

**Steps.**

1. `DCC`, two constants and the arm:

        /** The widest precision whose every unscaled value is an exact double: 10^15 < 2^53. */
        static final int EXACT_DOUBLE_PRECISION = 15;
        /** The widest precision whose every unscaled value is an exact float: 10^7 < 2^24. */
        static final int EXACT_FLOAT_PRECISION = 7;

        static final String FLOATING_EXACT_REASON = "a decimal cast to DOUBLE past 15 digits or "
            + "to FLOAT past 7, at a scale of 1 or more: the unscaled value is not exact in the "
            + "target, so one division rounds twice; the exact arm is row 366 of m9/PLAN.md";

        private static Optional<VarkaVectorIR> toFloating(Cast c, DecimalType from,
            LaneType target, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
          if (from.precision() > MAX_PRECISION) {
            return sink.decline("a cast of a decimal wider than " + MAX_PRECISION + " digits "
                + "to a floating type is row 364's", c);
          }
          if (sink.declinesTwoLaneChecks()) {
            return sink.decline(TWO_LANE_REASON, c);
          }
          int exact = target == LaneType.DOUBLE ? EXACT_DOUBLE_PRECISION : EXACT_FLOAT_PRECISION;
          if (from.scale() > 0 && from.precision() > exact) {
            return sink.decline(FLOATING_EXACT_REASON, c);
          }
          return operand(c, inputs, literals, sink).map(x -> {
            if (x.type().scale() == 0) {
              return new ConvertLane(x.unscaled(), target);
            }
            VarkaVectorIR q = new IntArith(IntOp.DIV, Overflow.WRAP,
                new ConvertLane(x.unscaled(), LaneType.DOUBLE),
                VarkaNodeCompiler.doubleSlot((double) Decimal.POW_10()[x.type().scale()], sink));
            return target == LaneType.DOUBLE ? q : new ConvertLane(q, LaneType.FLOAT);
          });
        }

   The test on `from` and the tree on `x.type()` agree: `value` returns a `Scaled` of the
   operand's own type. *[Fix round, 28 November 2026.]* If B0's last line printed `1` (597b
   merged), leave out the three lines `if (sink.declinesTwoLaneChecks()) { return
   sink.decline(TWO_LANE_REASON, c); }`: the constant is gone, and `operand` reaches the decimal
   readers, which decline first. With `0`, write them as shown; 597b deletes them later.
   In `arm`, beside the integral branch: where the child is a `DecimalType`
   and the target is `DataTypes.DoubleType` or `DataTypes.FloatType`, return
   `() -> toFloating(c, from, lane, inputs, literals, sink)`.
2. `C/VarkaNodeCompiler.java`, `floatingDecline`: the list of forms that pass gains, beside the
   floating casts family's line,
   `|| (e instanceof Cast c && c.child().dataType() instanceof DecimalType
   && isFloating(c.dataType()))`. The decline text "a decimal cast with a floating side (rows
   346, 350, 364 and 366)" then names only a floating source: reword it "a cast of a floating
   value to a decimal (row 346)". Run `grep -rn "a decimal cast with a floating side"
   sql/catalyst/src/test sql/core/src/test` first and change each test that reads it.
3. `VarkaFamily.DECIMAL_CAST`'s description and the class javadoc of `DCC` name the two targets.
4. Tests.
   - `QS`, two tests, each `checkProjection(s"CAST($c AS DOUBLE)")` and `... AS FLOAT)` in the
     suite's three modes. Columns: the suite's `c` (`DECIMAL(9, 2)`: `DOUBLE` fuses, `FLOAT`
     declines), `d` (`DECIMAL(18, 0)`: both fuse, scale 0, and its values `9007199254740991`,
     `9007199254740992` and `999999999999999999` are the rounding cells), and three new columns:
     `n7`, `DECIMAL(7, 4)`, with `999.9999`, `-999.9999`, `0.0001`, `-0.0001`, `0`, `838.8608`,
     `167.7215`; `n15`, `DECIMAL(15, 14)`, with `9.99999999999999`, `-9.99999999999999`,
     `0.00000000000001`, `1.00000000000001`, `4.50359962737049`; and `n16`, `DECIMAL(16, 14)`,
     with `64.00001907348633`. The declined pairs (`c` to `FLOAT`, `n15` to `FLOAT`, `n16` to
     both) are checked with the suite's answer check and `assert(!explained.contains("a:
     fused"))`. `64.00001907348633` to `FLOAT` is section 2.4's named value: the double
     quotient narrowed is wrong there, so the test fails if `EXACT_FLOAT_PRECISION` is raised.
   - `TS`, one test, written out:

            @Test
            @DisplayName("every unscaled value is exact in the target up to the two precisions "
                + "the floating casts divide at, and not one digit past them")
            void exactFloatingPrecisions() {
              long d = Decimal.POW_10()[VarkaDecimalCastCompiler.EXACT_DOUBLE_PRECISION] - 1L;
              assertEquals(d, (long) (double) d);
              long d1 = Decimal.POW_10()[VarkaDecimalCastCompiler.EXACT_DOUBLE_PRECISION + 1] - 1L;
              assertNotEquals(d1, (long) (double) d1);
              long f = Decimal.POW_10()[VarkaDecimalCastCompiler.EXACT_FLOAT_PRECISION] - 1L;
              assertEquals(f, (long) (float) f);
              long f1 = Decimal.POW_10()[VarkaDecimalCastCompiler.EXACT_FLOAT_PRECISION + 1] - 1L;
              assertNotEquals(f1, (long) (float) f1);
              for (int s = 1; s <= VarkaDecimalCastCompiler.EXACT_DOUBLE_PRECISION; s++) {
                assertEquals(new BigDecimal(Decimal.POW_10()[s]),
                    new BigDecimal((double) Decimal.POW_10()[s]));
              }
            }

   - A sweep, `CT/varka/VarkaDecimalFloatingCastSweepSuite.java`, JUnit 5 under `@VarkaSuite`,
     scalar Java with no kernel: for every `(p, s)` with `1 <= s <= p <= 7` and every `u` from
     `-(10^p - 1)` to `10^p - 1`, `(float) ((double) u / (double) POW_10[s])` equals
     `BigDecimal.valueOf(u, s).floatValue()` by `Float.floatToRawIntBits`; and for every `(p, s)`
     with `1 <= s <= p <= 15`, over the two bounds and 200000 values drawn from
     `new SplittableRandom(350)`, `(double) u / (double) POW_10[s]` equals
     `BigDecimal.valueOf(u, s).doubleValue()` by raw bits. The `FLOAT` half is exhaustive only
     under `-Dvarka.sweep=true` (register it in `sql/varka/ci/sweeps.tsv` as its neighbours
     are); without the flag it takes every 97th `u`. It is the proof of decision 4 by
     exhaustion, which `PLAN.md` section 5 accepts for a source of at most 32 bits.
   - `CS/varka/VarkaCoverageSuite.scala`: three rows join "Decimal casts": `CAST(d9 AS DOUBLE)`
     and `CAST(d9s4 AS DOUBLE)`, with no domain, and `CAST(CAST(d9 AS DECIMAL(7, 2)) AS FLOAT)`
     with the domain `abs(d9) < 100000`, the inner cast's precision check. No fixture column
     has seven digits or fewer, hence the inner cast. `d18` and `d16s9` to a floating type
     decline and are no rows.
   - `BENCH/VarkaDecimalBaselineBenchmark.java`: `DECIMAL(9, 2) CAST(x AS DOUBLE)` takes
     `addCasts`'s `fused` argument. The other six floating sections stay `Fusion.NONE`: their
     precisions are 9 to `FLOAT`, 15 to `FLOAT` and 18.
5. Regenerate: `dev/varka_regen_generated.sh --all`.

**The commands, in order.**

    build/sbt catalyst/Test/compile sql/Test/compile                         -> [success]
    dev/varka_emit.sh "CAST(x AS DOUBLE)" --columns x:dec9s2                 -> the line ends FusedOutput(0)
    dev/varka_emit.sh "CAST(x AS FLOAT)" --columns x:dec7s4                  -> the line ends FusedOutput(0)
    dev/varka_emit.sh "CAST(x AS FLOAT)" --columns x:dec9s2                  -> ResidualOutput - a decimal cast to DOUBLE past 15 digits or ...
    build/sbt 'catalyst/testOnly *VarkaDecimalCastTableSuite *VarkaDecimalFloatingCastSweepSuite'
        -> failed 0
    build/sbt 'sql/testOnly *VarkaDecimalCastQuerySuite'                     -> failed 0, two tests more than before the part
    build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite *VarkaFamilySwitchSuite *VarkaCoverageSuite *VarkaCastMatrixSuite *VarkaWarmupReachSuite'
        -> failed 0
    build/sbt 'sql/testOnly *VarkaDecimalBaselineQueriesSuite *VarkaCoverageHarnessSuite *VarkaFloatingLaneSuite'
        -> failed 0
    python3 dev/varka_msg_check.py size                                      -> under 1000
    dev/varka_ops/pre-pr-slot.sh <w>                                         -> pre-pr: passed on <sha>

If the second `dev/varka_emit.sh` line prints `ResidualOutput` with a reason of the kernel plan
(a conversion it does not lower), stop and report: 11.9's first check should have caught it, and
the `FLOAT` rows then decline by that reason until the plan lowers the conversion.

**Done when** the commands print what is stated, the Build and the option matrix are green, and
section 10 gains "10.7 Step 4, part 350b1".

**Not here.** `FLOAT` at precision 8 to 15. Precision 16 to 18. Any new IR node. Any proof file:
the lowering has no bound of its own beyond the two precisions, which the table test and the
sweep hold.

**For the record: the round-to-odd form that is not built.** For `FLOAT` at `8 <= p <= 15`,
`s >= 1`, with `ud = ConvertLane(u, DOUBLE)` and `P = doubleSlot(10^s)`: `q = ud / P`;
`r = Fma(q, doubleSlot(-10^s), ud)`, the exact remainder `u - q * 10^s`; `qb` and `rb` their
`ReinterpretLane` to `LONG`; where `(rb << 1) != 0` and `(qb & 1) == 0`, `qb` plus 1 where
`(rb ^ qb) >= 0` (the remainder has the quotient's sign) and minus 1 otherwise; the result
viewed as a double and narrowed once. `verify_350.py` checks it against the exact rational
rounded once to binary32 over every `(p, s)` of that range, binary32 midpoints among the values,
with no difference; in the same sample the plain narrowing of `q` differs on 4 lanes. Its owner
is row 366 of `m9/PLAN.md`.

### 11.7 Part 350m: the measurement and the close of the row

**Title:** `[VARKA-350] Measure the 64-bit decimal casts on the EPYC 7763 and score the plan`.
**Branch:** `varka-350m-measure`. **Builder:** Sonnet-class. The lead dispatches; the builder
splices, scores and writes.

1. The lead dispatches `benchmark.yml` for `VarkaDecimalBaselineBenchmark` at 256 bits and at
   128 bits (the second with `-f extra-java-options=-XX:MaxVectorSize=16`), as
   `VARKA-597.md` 11.5 step 10 writes the command, and `varka-width-audit.yml` for the EPYC 7763
   and Neoverse N2 columns of the two census cells of 10.1.
2. The builder splices the cast sections into the two runner files with
   `python3 dev/varka_bench_sections.py splice`, adds the runs to the provenance files, and
   scores section 4's predictions in section 10 by these rules, on best times:
   - prediction 1 holds if every fused integer cast section at 256 bits reads 5.0X or more in
     the file's `Relative` column;
   - prediction 2 holds if `DECIMAL(9, 2) CAST(x AS DOUBLE)` at 256 bits takes at most 1.5 times
     the Varka arm's time of `DECIMAL(9, 2) CAST(x AS BIGINT)`; its `FLOAT` half is "not built
     in this milestone";
   - predictions 3 and 4 are "not built in this milestone" (350d, and the double rule that
     10.3's decision 1 replaced);
   - prediction 5: at 128 bits every cast section is on the row path on both arms, 0.9X to
     1.1X, the family declining there (10.3's decision 7);
   - prediction 6 is scored by `git diff --stat` on `emitted_bytes.json` in each part.
3. `PLAN.md`: row 350 opens with `**Done**`, naming what moved: the floating targets past the
   two exact precisions to row 366 of `m9/PLAN.md`, and 350d, the planted fault and the cast
   rows' refinement in `decimal_refinement.smt2` to `m10/SCOPE.md` item 140 (a line added
   there, as the 27 November moves are written; the owner's answers 1 and 3 of section 12).
4. `census_curated.txt` and `casts.tsv`'s semantics column for the decimal pairs say what fuses:
   the integer rules at every precision to 18, `DOUBLE` to precision 15, `FLOAT` to precision 7,
   both at scale 0, and name row 366 for the rest. Then `dev/varka_regen_generated.sh --all` and
   `python3 dev/varka_census.py --check`.

**Done when** both runner files hold the spliced sections with their runs named, section 10
holds the scored predictions, `python3 dev/varka_plan_lint.py --offline` prints `0 finding(s)`,
and the Build is green.

### 11.8 What each part moves

| part | fuses after it | census and cast matrix | benchmark sections that become fused at 256 bits |
|---|---|---|---|
| 350a1n | decimal to `TINYINT`, `SMALLINT`, `INT` in three modes | `casts_fused.tsv`: the three pairs' `DECIMAL(10, 2)` forms; the census count moves by 0 (section 6) | `DECIMAL(9, 2) CAST(x AS INT)` LEGACY and ANSI, `try_cast(x AS SMALLINT)`, `DECIMAL(18, 4) CAST(x AS INT)` LEGACY, `DECIMAL(18, 14) CAST(x AS INT)` ANSI and `try_cast` |
| 350a1b | `TINYINT`, `SMALLINT`, `INT`, `BIGINT`, `BOOLEAN` into a decimal of 18 digits or fewer; coercion's `i < d9` | five more pairs' forms; 0 | `BIGINT CAST(l AS DECIMAL(18, 2))` LEGACY and ANSI |
| 350b1 | decimal to `DOUBLE` (`p <= 15`, or scale 0) and `FLOAT` (`p <= 7`, or scale 0); coercion's `d9 < x` over a double | two pairs' forms; 0 | `DECIMAL(9, 2) CAST(x AS DOUBLE)` |

### 11.9 What only a JVM can settle, and the command for each

The lead runs these before 350b1 starts; each is one compile, no timing.

| claim | command | settles |
|---|---|---|
| a long-driven kernel admits a `DOUBLE` division root over `L2D` | `dev/varka_emit.sh "CAST(l AS DOUBLE) / 100.0D" --columns l:bigint` | the line ends `FusedOutput(0)`: the `DOUBLE` tree of 350b1 is admitted |
| the same narrowed to `FLOAT` | `dev/varka_emit.sh "CAST(CAST(l AS DOUBLE) / 100.0D AS FLOAT)" --columns l:bigint` | the same for the `FLOAT` tree; if it declines, 350b1 builds `DOUBLE` and the scale-0 forms only, and `FLOAT` at `s >= 1` declines with the kernel plan's reason |
| a guarded cast into a decimal is a valid output under LEGACY | `QS`'s test "a cast into a decimal is a decimal output of its own" on master | it passes today; 350a1b's outputs rest on it |
| the cherry-picks of 350a1n apply | `git cherry-pick a3c982c766f` in the part's worktree | the conflict list of 11.4 step 3 |
| master answers 127 for a computed `127.999999999999999` narrowed to `TINYINT`, Varka on and off (fix round) | 11.4 step 1's test: `build/sbt 'sql/testOnly *VarkaDecimalCastQuerySuite -- -z "127.999999999999999"'` | 350a1n goes on, or stops by the rule of 11.4 step 1 |
| a computed 17 or 18 digit narrowing fuses and equals vanilla once the decline is gone (fix round) | 11.4's `VarkaDecimalCastQuerySuite` and `VarkaDifferentialSuite` commands | the stop rules of 11.4 step 4 and of its commands |

### Risks of section 11

1. **350a1n's cherry-pick meets more conflicts than 11.4 lists.** Held by the rule of step 3
   (master's side, then the commit's hunks by hand) and by the suites of its commands.
2. **350a1b's validity of an integral operand.** `operandValid` admits a bare column only; a
   computed operand's null test declines. Held by `QS`'s `IS NULL` cases over the casts and by
   `VarkaCoverageHarnessSuite`'s null-test forms of the four predicate rows.
3. **A reworded decline breaks a test that reads it.** Held by the two `grep` lines before each
   rewording.
4. **The option matrix under `useAVX=2` and at 128 bits.** `L2D`'s forms differ by level; the
   query suite runs under every configuration, and the two-lane arm declines first.

## 12. Questions for the owner (28 November 2026)

*The owner answered all three on 28 November 2026; each answer is recorded under its question.*

1. **What of row 350 stays in milestone 8.** Options: (a) the four parts of 11.2, with `FLOAT`
   at precision 8 to 15 and the precisions 16 to 18 declining to row 366 of `m9/PLAN.md`, and
   350d and the planted fault moved to `m10/SCOPE.md` item 140; (b) also build the round-to-odd
   `FLOAT` form here, at full strength, with a proof file (about 400 lines); (c) also build
   350d after row 360's `WideMul` merges. **Recommended: (a)**: item 3 of `PLAN.md` 1.1 asks
   each pair to fuse or decline with its reason, and (b) and (c) are a rounding proof and a
   performance arm. **Default applied: (a).** **Answered on 28 November 2026: (a), the default.**
   `FLOAT` at precision 8 to 15 and the precisions 16 to 18 go to row 366 of `m9/PLAN.md`; 350d,
   the planted fault and the `decimal_refinement.smt2` rows (question 3) go to `m10/SCOPE.md`
   item 140, written there by 350m (11.7 step 3).
2. **The finding of 14 item 5** (a residual narrowing over a fused decimal sum answers by the
   exact rule where vanilla Spark answers by the double rule) has no row. Options: (a) a new row
   of `m8/PLAN.md` that gates the close, since it is a wrong answer; (b) a row of `m9/PLAN.md`.
   **Recommended: (a).** No default is applied: this plan builds nothing for it.
   **Answered on 28 November 2026:** the owner asked for a gate row that first checks master.
   That gate is folded into part 350a1n's first step (11.4 step 1), and no row is filed; the
   owner confirmed the fold on 28 November 2026. The evidence that the wrong answer is the
   branch's and not
   master's: #1708 (`e52b6bc082a`, SPARK-60843) made vanilla Spark truncate a
   `BigDecimal`-backed decimal exactly (`Decimal.roundToNumeric` and `truncatedLong`,
   `Decimal.scala` lines 271 to 334 on `6dafb8bd2f6`); a `try_cast` of a decimal takes that
   path (`Cast.scala` lines 893 to 895, 1493, 2585 to 2591); and `varka-350a1n-r2` does not
   contain #1708 (`git merge-base --is-ancestor e52b6bc082a varka-350a1n-r2` exits 1). So on
   master vanilla answers 127, as Varka does, and the branch's test expecting null is what is
   wrong. Step 1's test holds 127 under ANSI and TRY with Varka on and off, and its stop rule
   says: an answer that differs with Varka on and off is a wrong answer, and the builder stops
   and reports it, so that the lead files the row then.
3. **The cast rows' refinement in `decimal_refinement.smt2`** (10.4, "Owed") is not in the row's
   done-when. Options: (a) `m10/SCOPE.md` item 140, with row 506's remainder; (b) a part here.
   **Recommended and applied: (a).** **Answered on 28 November 2026: (a), the default.**

## 13. The house rules that bind every part of section 11

Each is written where named; the builder reads them there.

- Answers equal vanilla Spark's bit for bit under both ANSI settings; a declined batch is the
  row engine's: `sql/varka/AGENTS.md`, "House rules that bite here specifically".
- A lowering exact only over a bound ships its proof file: the same section. Parts 350a1n and
  350a1b rest on `decimal_casts.smt2`; 350b1 has no bound but the two precisions (11.6).
- A test pins the configuration it depends on, and the option matrix passes under every
  configuration: the root `AGENTS.md`, "Build and Test".
- Never weaken a failing test, and never edit a generated file: the root `AGENTS.md`.
- Benchmarks are committed files, baselines first: `sql/varka/AGENTS.md`, "Measurements, not
  adjectives". The baseline of this row is on master since #1572.
- The gate is `pre-pr.sh`, the push is `open-pr.sh`: `ADDING_AN_EXPRESSION.md`, "Closing a row".
- Java for new code and new suites: `sql/varka/AGENTS.md`, "Java code".

## 14. Stale against the tree, found while planning (28 November 2026)

1. **Section 5's order and section 9's done-when** name 350b to 350d and row 366 as this row's
   path. Rows 364 and 366 are milestone 9's since 25 November 2026; 11.2 is the order now.
2. **Section 2.3's double rule at precision 17 and 18** was replaced by 10.3's decision 1 (the
   exact rule), and the row's text in `PLAN.md` still says "the three narrow ANSI and TRY casts
   by the double rule". The owner's note of 1 November 2026 at the top of this file is what
   holds, less its last clause: *[fix round]* since #1708 the double rule is no one's, row 364's
   included, and vanilla Spark has none (11.3 decision 1).
3. **Section 2.4's `FLOAT` at precision 7 or less through the int lane** is replaced by 11.3's
   decision 4.
4. **`DCC.arm`'s javadoc** says a cast into a decimal declines as an output. It fuses since
   #1628 (`DC.output`); `VarkaDecimalBaselineBenchmark`'s 256-bit runner file lists
   `DECIMAL(9, 2) CAST(x AS DECIMAL(9, 0))` as fused. Part 350a1b corrects the sentence.
5. **A finding recorded only on a branch.** The plan text of `a3c982c766f` on
   `varka-350a1n-r2` records: under ANSI, `SELECT try_cast(s AS TINYINT) AS a, s FROM (SELECT
   x + z AS s FROM t)`, with `x` a `DECIMAL(17, 15)` holding `99.999999999999999` and `z` a
   `DECIMAL(2, 0)` holding `28`, gives null on vanilla Spark and 127 with Varka. The inferred
   cause: the residual cast reads the fused sum back long-backed and takes the exact rule, where
   vanilla's codegen hands the cast a `BigDecimal`-backed sum, which takes the double rule. It
   fails the same way with every narrow target declined, so it is not the narrow kernel's. It
   has no row in `m8/PLAN.md`, `m9/PLAN.md` or `m10/SCOPE.md`
   (`grep -n '99.999999999999999' sql/varka/plans/m8/PLAN.md sql/varka/plans/m9/PLAN.md
   sql/varka/plans/m10/SCOPE.md` prints nothing). Question 2 of section 12.
   *[Fix round, 28 November 2026: stale since #1708.]* The observation was true on the branch's
   base, which predates #1708; on master vanilla Spark truncates the `BigDecimal`-backed sum
   exactly and answers 127, as Varka does (11.3 decision 1 has the code path). 11.4 step 1
   checks it on master before anything is built.
6. **The row's note in `PLAN.md`** lists as left "the runner columns, the planted fault, the
   table test's sample against `Cast.eval`, the cast rows' refinement, 350b to 350d". Section 11
   gives each a home: 350m, `m10/SCOPE.md` item 140, decision 7 of 11.3, question 3, and 11.2.
7. **Section 10.3's "State"** names 350a1b as following #1700 "then 350a2"; the order that
   merged is #1700, #1707, and the order left is 11.2's.
8. *[Fix round, 28 November 2026.]* **The branch `varka-350a1n-r2` predates #1708**: its
   `COMPUTED_NARROW_REASON`, `DOUBLE_RULE_PRECISION`, the class javadoc's sentence on them and
   its query-suite test expecting null at `127.999999999999999` follow the double rule, which
   master's Spark no longer has. 11.4 step 4 deletes them.
9. *[Fix round.]* **Section 11.3 decision 6 as first written** ("597b leaves these tests in
   place") followed the default of `VARKA-597.md` 11.7 question 1; the owner answered "remove".
