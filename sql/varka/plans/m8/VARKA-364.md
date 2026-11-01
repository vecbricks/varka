# VARKA-364: The 128-bit decimal casts

## 0. What exists now (admission check)

From master at `9aa6ee74c63` (30 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/` or `sql/api/src/main/
scala/org/apache/spark/sql/` as named), from four unmerged branches - rows 350 and 366's plans and
the two Lemire transcriptions (`varka-350-366-plan` at `a4d7e34b5de`), rows 360 and 361's
(`varka-360-361-plan` at `ada77eb513a`), rows 362 and 363's (`varka-362-363-plan` at `b074cc19a17`)
and open PR #1183's edits to the milestone plan (`varka-rows-537-539` at `d8acb4b587c`) - and from
the JDK 25 sources in `/home/max/proj/openjdk-build/jdk25`. The row's text is the milestone plan's
2.2 (the decimal-to-floating paragraph, whose abort wording PR #1183 rewords to "Varka declines `s
>= 28` by choice"), its 2.8, section 7's question 3 as answered (iii), and waves F3 ("366 under
350's three double-rule casts and 364's two-pass arm") and G3 ("363's division is what 364's
decimal-to-decimal casts ... round through, so 363 leads both"). The numbers marked **[A]** are from
`results/VARKA-364-admission.txt`, a Python script and its output, run on the laptop for this plan;
nothing else was run.

**Spark's behaviour per (from, to, mode), with a side above precision 18.** TRY is ANSI inside a
catch that answers null (`expressions/Cast.scala:720-722`, `:1586-1597`, `:1706-1716`), and every
pair below is legal in the three modes (`Cast.canAnsiCast` at `:105`, `:147`, `:149`, `canCast` at
`:265`, `:311`, `:314`), as row 350's plan reads them. `u` is the unscaled value, `|u| <= 10^p - 1`.

| from | to | LEGACY | ANSI | TRY |
|---|---|---|---|---|
| `DECIMAL(p >= 19, s)` | `BIGINT` | the low 64 bits of `trunc(u / 10^s)` | `trunc(u / 10^s)`; `CAST_OVERFLOW` outside `[-2^63, 2^63 - 1]` | null there |
| `DECIMAL(p >= 19, s)` | `INT`, `SMALLINT`, `TINYINT` | LEGACY's long, narrowed | double rule: `d = RN64(u / 10^s)`; `CAST_OVERFLOW` unless `floor(d) <= MAX` and `ceil(d) >= MIN`; else `(int) d`, narrowed | null where ANSI raises |
| `DECIMAL(p >= 19, s)` | `DOUBLE` | `RN64(u / 10^s)` | the same | the same |
| `DECIMAL(p >= 19, s)` | `FLOAT` | `RN32(u / 10^s)`, rounded once | the same | the same |
| `DECIMAL(p >= 19, s)` | `BOOLEAN` | `u != 0` | the same | the same |
| `DECIMAL(p1, s1)` | `DECIMAL(p2, s2)`, `max(p1, p2) >= 19` | rescaled, `HALF_UP` down; null past `p2` digits | `NUMERIC_VALUE_OUT_OF_RANGE` past `p2` digits | null there |
| integral | `DECIMAL(p >= 19, s)` | `v * 10^s`; null where `abs(v) >= 10^(p - s)` | `NUMERIC_VALUE_OUT_OF_RANGE` there | null there |
| `BOOLEAN` | `DECIMAL(p >= 19, s)` | `10^s` or 0; true null when `p = s` | true raises when `p = s` | true null there |

The sources of each cell, and the facts the design rests on:

1. **A column above 18 is `BigDecimal`-backed.** `ArrowColumnVector` picks `DecimalAccessor` above
   18 (`ArrowColumnVector.java:207-213`, under `sql/catalyst/src/main/java/org/apache/spark/sql/
   vectorized/`), whose `getDecimal` is `Decimal.apply(getObject(rowId), p, s)` (`:496-499`), and
   `set(BigDecimal, p, s)` keeps the `BigDecimal` (`types/Decimal.scala:119-136`). So the three
   narrow ANSI and TRY casts of a column take `roundToNumeric`'s `BigDecimal` branch, the double
   (`types/Decimal.scala:294-320`, the double at `:309-310`), at every precision above 18.
2. **A computed child can be long-backed.** `Decimal.+` and `-` over two long-backed values of one
   scale stay long-backed (`types/Decimal.scala:505-519`), `changePrecision` keeps a long while the
   rescaled value has at most 18 digits (`:440-453`), and an integral source is long-backed below
   `10^18` (`set(Long)`, `:55-67`); `*` is always a `BigDecimal` (`:522-523`). A long-backed value
   takes the exact rule. The long-backed values this row can be handed - an integral or boolean
   cast, and sums, differences, negations and rescales of such values - hold integers, on which the
   two rules agree (item 6's argument with no fraction to cross), and a long-backed value of a
   fractional function type such as `second`'s `DECIMAL(8, 6)` holds `|u| < 10^16`, below which they
   agree too (row 350's plan, its item 0.14). The kernel therefore follows the double rule over
   every child, classifies the long-backed producers it can see by this argument, and declines the
   three casts over a child whose producer it cannot classify (settled by the lead, 30 October
   2026).
3. **LEGACY to an integral type.** `Decimal.toLong` is `decimalVal.longValue` for a `BigDecimal`
   (`types/Decimal.scala:251-257`), which JDK 25 computes as `toBigInteger().longValue()`,
   truncation toward zero and then the low 64 bits, its zero and fraction-only fast paths giving the
   same answer (`java/math/BigDecimal.java:3553-3570`); `toInt`, `toShort` and `toByte` narrow that
   long (`types/Decimal.scala:259-263`). The interpreted cast is `numeric.toLong` and its kin
   (`expressions/Cast.scala:1159-1161`, `:1201-1203`, `:1265-1267`, `:1331-1333`), the generated one
   `castDecimalToIntegralTypeCode` (`:2403-2409`). **[A]:** `10^37` at `(38, 0)` is
   68739955140067328 as a `BIGINT` and 0 as an `INT`; the 38 nines at `(38, 10)` are
   4477988020393345023 and 268435455.
4. **`BIGINT` under ANSI raises above 18.** `roundToLong` is `toBigInteger.longValueExact()` on a
   `BigDecimal` (`types/Decimal.scala:327-345`), so the cast raises exactly where the truncated
   value leaves the long range. Row 350's "never raises for `p <= 18`" holds because `10^18 < 2^63`;
   here it holds wherever `p - s <= 18` and nowhere else. **[A]:** `-2^63` at `(19, 0)` passes,
   `-2^63 - 1` at `(20, 0)` and `2^63` at `(19, 0)` raise.
5. **The narrow targets under ANSI.** The generated code calls `roundToInt`, `roundToShort` or
   `roundToByte` (`expressions/Cast.scala:2403-2406`); the interpreted code
   `DecimalExactNumeric.toInt` (`types/numerics.scala:179-183`) and, for the two narrow targets, the
   int's range (`expressions/Cast.scala:1251-1264`, `:1317-1330`); row 350's plan shows both accept
   the same doubles. **The double decides the value as well as the range**: the 38 nines at `(38,
   38)` cast to `INT` give 1, where the exact rule gives 0 **[A]**.
6. **The double rule equals the exact rule below a scale, at every precision [A].** For `x = u /
   10^s` the two rules can differ only where an integer `n` lies within half an ulp of `x` and `0 <
   |n - x|`; that distance is at least `10^-s`, and half an ulp of an `x` either rule reads
   differently is at most `2^(e - 53)` with `e` the target's top binade, 31, 15 or 7 (for `|x| >=
   MAX + 2` both rules raise, `d` being at least `MAX + 2` by monotonicity). So they agree for `s <=
   6` to `INT`, `s <= 11` to `SMALLINT` and `s <= 13` to `TINYINT`, at any precision. A draw of
   1861364 values around every boundary confirms it and is not exhaustive: the first differences are
   at scales 7, 12 and 14, unscaled `10741071119999999` at `(24, 7)` to `INT` (exact 1074107111,
   double 1074107112), `16507999999999999` at `(19, 12)` to `SMALLINT` and `-12899999999999999` at
   `(19, 14)` to `TINYINT` (exact -128, double overflow). Named: `-21474836489999999` at `(19, 7)`
   raises on the double rule and is -2147483648 on the exact one; `-2147483648999999` at `(19, 6)`
   is -2147483648 on both.
7. **To a floating type.** `Decimal.toDouble` and `toFloat` are `toBigDecimal.doubleValue` and
   `floatValue` (`types/Decimal.scala:245-247`) in every mode (`expressions/Cast.scala:2656-2657`,
   `:2693-2694`), and JDK 25 rounds both correctly from the exact value (`java/math/
   BigDecimal.java:3742-3764`, `:3825-3862`, `fullFloatValue` at `:3766`, `fullDoubleValue` at
   `:3864`). Above 18 a nonzero value lies in `[10^-38, 10^38)`: no binary64 subnormal or overflow,
   no binary32 overflow, and one binary32 subnormal, unscaled 1 at `(38, 38)` (2 at `(38, 38)` is
   normal) **[A]**. Exact binary64 midpoints exist up to scale 22, binary32 ones up to 30 **[A]**.
8. **To `BOOLEAN`.** `!_.isZero` (`expressions/Cast.scala:870-871`, generated `:2321-2322`).
9. **To a decimal.** `toPrecision(..., ROUND_HALF_UP, !ansiEnabled)` (`expressions/Cast.scala:
   1369-1374`, `:1400-1401`), generated `changePrecision` on a clone (`:1849-1878`, `:1936-1941`): a
   `BigDecimal` takes `setScale(scale, HALF_UP)` and the precision test (`types/Decimal.scala:
   456-463`), a long the division or multiply of `:400-453`, half away from zero either way. Where
   `canNullSafeCastToDecimal` holds, `(p2 - s2) > (p1 - s1)` (`expressions/Cast.scala:534-540`), the
   result cannot fail. The ANSI error is `NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION`, "<value>
   cannot be represented as Decimal(<precision>, <scale>). If necessary set <config> to "false" to
   bypass this error, and return NULL instead." (`common/utils/src/main/resources/error/
   error-conditions.json:6648-6652`; `sql/api/.../errors/DataTypeErrors.scala:197-215`).
10. **The integral error.** `CAST_OVERFLOW`, SQLSTATE `22003`: "The value <value> of the type
    <sourceType> cannot be cast to <targetType> due to an overflow. Use `try_cast` to tolerate
    overflow and return NULL instead." (`error-conditions.json:1009-1014`;
    `DataTypeErrors.scala:247-257`), its value the decimal's string with `BD` appended
    (`types/Decimal.scala:292`).
11. **From an integral type.** `Decimal.apply((long) c)` then the precision change
    (`expressions/Cast.scala:1402-1403`, generated `:1942-1947`); `set(Long)` is `BigDecimal`-backed
    from `10^18` (`types/Decimal.scala:55-67`), with the same answer either way. The cast overflows
    exactly where `abs(v) >= 10^(p - s)`, so its guard is dead where `p - s` is at least the
    source's digits, 3, 5, 10 or 19 (`2^63 < 10^19`); **[A]:** of the 590 targets above 18 the guard
    is live on 60 for `TINYINT`, 100 for `SMALLINT`, 200 for `INT` and 380 for `BIGINT`.
12. **From `BOOLEAN`.** `Decimal.ONE` or `ZERO`, then the precision change (`expressions/Cast.scala:
    1386-1388`, generated `:1915-1920`): true fails only into `DECIMAL(p, p)`, 20 targets **[A]**.

**What Varka has, and what the prerequisites give.**

13. **Nothing above 18 fuses on master.** The lane pair is row 359's plan: `Pair(lo, hi, type)`, two
    long words, a 64-bit value widened by `Pair(x, SHR(x, 63))`, the unsigned compares, `gt128`
    against a constant pair, negation and `abs` with the borrow, the load's `10^p` check and the
    store (`VARKA-359.md` 2.2 to 2.5).
14. **Row 350's plan** (branch `varka-350-366-plan`, its 2.1 to 2.4): `VarkaDecimalCastCompiler`
    under a family `DECIMAL_CAST`, one table keyed by (source, target), the guards and their
    dead-guard exemptions, the double rule as the composition of a `DOUBLE` cast and row 343's
    floating guard (`VARKA-343.md` 2.4), the precision rules for the floating targets, and the
    integral sources into precision 18 (`(3, 0)`, `(5, 0)` and `(10, 0)` among them, settled by the
    lead).
15. **Row 366's plan** (the same branch, its 2.1 to 2.5): Eisel and Lemire's algorithm at a constant
    scale composed from long-lane nodes, two new nodes `LeadingZeros` and `ReinterpretLane`, the
    reciprocal table for `s = 1..27` rounded up and checked against Lemire's Table 4, `FLOAT` by
    round-to-odd at 53 bits and one narrowing, the scalar twin `ExactToFloatingScalar`, and the
    entry this row needs: a builder taking an already normalised `w'` (bit 63 set) and a per-lane
    exponent offset, "`w' + 1` overflowing 64 bits is 364's case to handle". `s >= 28` declines by
    Varka's choice, since Mushtak and Lemire show the abort never fires (their section 4,
    Proposition 4).
16. **Row 363's plan** (branch `varka-362-363-plan`, its 2.2 to 2.5): the node `ScaleDown(magnitude,
    k, bound, round, word)`, `HALF_UP` or `DOWN` ("`DOWN` for class C's split (2.5) and 364's
    truncations"), every `k` from 1 to 39 at a 128-bit dividend, its constants proven in its own
    proof file, and the proof of the coercion cast's exemption; by the lead's settlement there, "364
    owns the coercion's cast expression and this row the division under it".
17. **Row 360's plan** (branch `varka-360-361-plan`, its 2.4 to 2.6): `WideMul` with constant
    multipliers (360b), the pair-times-word rescale up (`WideMul(x.lo, c)` plus the low word of
    `x.hi * c`), the class K disposal `GuardedRange(CondValue(ovf), 0, 0, mode)` added into both
    words, and the widening casts it keeps: a decimal to a wider one with `s' >= s` and `p + (s' -
    s) <= p'`, and `BIGINT` to `DECIMAL(20, 0)` (PR #1183's edit of row 360, from that plan).
18. **The rest**: row 481's `NULL` mode (`VARKA-481.md` 3.1), row 332's target ranges and narrow
    shape (`VARKA-332.md` 2.1, 2.3), row 343's `ConvertLane` and bounds (`VARKA-343.md` 2.1, 2.4).
19. **The baseline has one of this row's casts.** `VarkaDecimalBaselineBenchmark` times `DECIMAL(38,
    10)` cast to `DECIMAL(18, 4)` at 315.1 ns a row on the row path, 256 bits, EPYC 7763
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`), a
    rescale down from 128 to 64 bits. Its `(38, 10)` column spreads integer parts to `5 * 10^12`, so
    59978 of 60000 drawn values have `|u| >= 2^64` **[A]**.
20. **`m8/casts.tsv` names this row on 21 decimal pairs.** The row's text covers 13: decimal to and
    from the four integral types, decimal to decimal, to and from `BOOLEAN`, to `DOUBLE` and
    `FLOAT`. The floating sources are row 346's, and the six interval, `TIME` and `DATE` pairs are
    those row 350's plan marks unclaimed with their homes (its section 7, settled by the lead); the
    same holds above 18.
21. **The two passes disagree on one lane in two to four thousand, and a decline is per batch [A].**
    For `|u| >= 2^64` the top 64 bits `w'` and `w' + 1` give two different doubles on 0.00027 to
    0.00053 of a lane over six uniform types and the baseline's column. Varka's bodies are
    straight-line and a guard's decline folds into the batch's status (`varka/VarkaBodyEmitter.java:
    645-661`), so one disagreeing lane declines its batch: of batches of 10000 rows, the Arrow
    cache's default (`ArrowCachedBatchSerializer.scala:107` reads
    `spark.sql.execution.arrow.maxRecordsPerBatch`, `internal/SQLConf.scala:5529-5537`), between
    0.004821 and 0.069459 would survive, and of 4096-row batches 0.1125 to 0.3354. On the same draws
    `FLOAT` and the `INT` outcome never disagree.

### What differs from the row text and the milestone plan

- **"Declined otherwise" declines nearly every batch of a wide `DOUBLE` cast** (item 21). The
  default is the exact correction 2.4 calls `MIDPOINT` - one pass over `w'` and an integer test
  against the midpoint above it - and the row's two passes with the decline are the `DECLINE` arm,
  measured against it (settled by the lead, 30 October 2026). This departs from the owner's text
  twice: wave F3's "364's two-pass arm", which the default no longer is, and 2.2's "about one in a
  thousand" with its decline of the lane, which item 21 measures as a decline of nearly every batch.
  Both are flagged in section 8.
- **Lanes with `|u| < 2^64` take one exact pass** (2.4), so a `DECIMAL(20, 2)` column of ordinary
  values reaches neither the second pass nor the correction.
- **The double rule is computed as the exact rule below scale 7, 12 and 14** (item 6), a refinement
  of the answer to question 3 in the shape of row 350's question 1, put to the owner (section 8).
- **`BIGINT` raises above 18 under ANSI** (item 4): an integer check on the truncated pair, by SMT.
- **`s >= 28`, 66 of the 590 types above 18 [A]**, declines by Varka's choice, as row 366's does (PR
  #1183's wording of 2.2; settled by the lead): a gap, recorded with one `m9/SCOPE.md` item shared
  with 366, which keeps five of the census's cast entries from counting (section 6).
- **The ownership is enumerated, not described** (2.1): of the 606841 ordered decimal-to-decimal
  type pairs, 467095 are this row's [A], every pair having exactly one owner. Three classes no row
  text names fall to this row: a rescale down into a wider type (row 360's widening casts need `s'
  >= s`), a fallible rescale up into the lane pair, and every cast from 128 bits down to 64.
- **No `(long) (double) u == u` guard is emitted**, so the row's `Long.MAX_VALUE` exclusion has
  nothing to apply to: above 18 the arm runs on every lane, and the guard form row 350's plan uses
  below 19 is `abs(u) <= 2^53`. `Long.MAX_VALUE` at `(19, 0)` is a named test; the wording is the
  owner's and is flagged in section 8.

## 1. Goal and non-goal

Every cast between a `DECIMAL(p, s)` with `p >= 19` and `s >= 0` and an integral type, a decimal of
either form, `BOOLEAN`, `DOUBLE` or `FLOAT` - the decimal being the source of the last two - fuses
in LEGACY, ANSI and TRY with the row engine's answers read through `ArrowColumnVector`: integer
rules by integer arithmetic on the lane pair proven by SMT, the floating targets correctly rounded,
the three narrow ANSI and TRY casts by the double rule; so do the casts from a 64-bit decimal into
the lane pair and back that 360 does not keep. An ANSI batch Spark raises on declines and the row
engine raises; TRY's and LEGACY's nulls are the kernel's. Not this row: everything within precision
18 (350), the infallible widening casts and `BIGINT` to `DECIMAL(20, 0)` (360), the division and its
constants (363), the exact arm's 64-bit sequence (366), floating sources (346), the six pairs of
item 0.20, a negative scale.

## 2. Design

### 2.1 Who builds which cast

Over the 779 decimal types and their 606841 ordered pairs **[A]**:

| class | pairs | row |
|---|---:|---|
| identity | 779 | none |
| both sides within precision 18 | 35532 | 350 |
| a wider type at one scale (sign extension or nothing) | 8720 | 360 |
| an infallible rescale up, `s2 > s1`, `p1 + k <= p2` | 94715 | 360 |
| a fallible rescale up, 128 to 128 bits, `k` up to 38 | 136230 | this row |
| a fallible rescale up, 64 to 128 bits, `k` up to 38 | 22800 | this row |
| a fallible rescale up, 128 to 64 bits | 22800 | this row |
| a narrower precision at one scale, into 128 or 64 bits | 4940, 3780 | this row |
| a rescale down, fallible, into 128 or 64 bits | 32585, 62130 | this row |
| a rescale down, infallible (`p1 - k < p2`), 128 to 128, 128 to 64, 64 to 128 | 136230, 22800, 22800 | this row |

The classes sum to 606841, so with 350 and 360 every precision pair has exactly one owner. Beyond
them, this row owns the integral sources into `p >= 19` but `BIGINT` to `DECIMAL(20, 0)`, a
`BOOLEAN` source into `p >= 19`, and every non-decimal target of a source above 18. The coercion
casts inside a comparison or a choice at two scales (`VARKA-359.md` 0 item 3) are this row's
rescale-down and rescale-up arms; their division and the proof that the coercion's rescale down
cannot overflow are 363's, the multiply 360b's.

### 2.2 One table, one shape per cast

The arms extend row 350's `VarkaDecimalCastCompiler` under `DECIMAL_CAST` with the lane pair as a
source and a target. `x` is the source value - 359's `Pair` above 18, 350's `Scaled` below - `m =
abs(x)` by 359's `abs`, `neg = SHR(x.hi, 63)`, and `sgn(q)` is `q` or 359's negation of it under
`neg`, since `ScaleDown` divides a magnitude (363's 2.2). `d` is `FAIL` under ANSI and `NULL` under
LEGACY and TRY (481's mode), except the integral targets, where LEGACY has no guard. A guard over a
pair is 360's class K disposal: `g = GuardedRange(CondValue(ovf), 0, 0, d)` added into both words.

| kind | tree | pairs |
|---|---|---|
| truncate | `t = sgn(ScaleDown(m, s, 10^p - 1, DOWN))` as a pair, `t = x` at `s = 0` | to every integral target |
| `BIGINT`, LEGACY | `t.lo` | |
| `BIGINT`, ANSI, TRY | `t.lo` under `ovf = NE(t.hi, SHR(t.lo, 63))` | dead where `p - s <= 18` |
| narrow, LEGACY | `NarrowLane(ConvertLane(t.lo, INT), target)`, 332's wrap | to `INT`, `SMALLINT`, `TINYINT` |
| narrow, exact rule | as LEGACY under `ovf = NE(t.hi, SHR(t.lo, 63)) \| LT(t.lo, MIN) \| GT(t.lo, MAX)` | ANSI and TRY, `s` below 7, 12, 14 |
| narrow, double rule | 343's tree over `toDouble(x)` (2.3, 2.4) | ANSI and TRY, `s` at or above them |
| to floating | `toDouble(x)`, `toFloat(x)` (2.4) | `DOUBLE`, `FLOAT` |
| test | `Not(Compare(EQ, BitOp(OR, x.lo, x.hi), 0))` as a condition | to `BOOLEAN` |
| rescale up | `up(x, k)` under `ovf = gt128(m, 10^(p2 - k) - 1)` | `s2 > s1`, `p1 + k > p2` |
| rescale down | `q = sgn(ScaleDown(m, k, bound, HALF_UP))` under `ovf = gt128(abs(q), 10^p2 - 1)` | `s2 < s1` |
| precision only | `x` under `ovf = gt128(m, 10^p2 - 1)` | `s2 = s1`, `p2 < p1` |
| integral source | `up(v, s)` under `GuardedRange(v, -(10^(p - s) - 1), 10^(p - s) - 1, d)` | into `p >= 19` |
| select | `IfElse(b, pair(10^s), pair(0))` on both words, true guarded when `p = s` | `BOOLEAN` into `p >= 19` |

`up(x, k)` multiplies by `10^k`: for `k <= 18` 360b's `WideMul(x.lo, 10^k)` on a 64-bit value and
its pair-times-word form on a pair; for `k >= 19`, where `10^k` passes 63 bits, steps of at most
`10^18` each - two up to `k = 36`, three at 37 and 38 - every step exact under the guard, which
bounds the final product by `10^p2 - 1` (43890 type pairs take more than one step **[A]**); an
integral source's `up(v, s)` steps the same way. A 64-bit result - `p2 <= 18` - is the low word
after the check, which then holds a sign-extended value; a 64-bit source enters as `Pair(x, SHR(x,
63))`. Each guard is dropped where the compiler proves it dead, the exemption the row names (`p_in +
delta <= p_out` up, `p_in - delta < p_out` down, `READING_ARROW_RS.md` 1 and 3.6) and the others row
350's 2.1 lists: `p - s <= 18` for `BIGINT`, `p - s` at most 9, 4 and 2 for the three narrow
targets, an integral source of at most `p - s` digits, `p > s` for `BOOLEAN`.

**The overflow defences, as the 28 October rule asks.** The rescale up is **guard-the-inputs**: the
guard is the target's precision check moved before the multiply, exact by row 349's lemma at full
width (`abs(x * 10^k) <= 10^p2 - 1` exactly when `abs(x) <= 10^(p2 - k) - 1`), and both steps of `k
>= 19` stay inside it; an integral source the same. The rescale down is **check-after with no wrap
possible**: the `HALF_UP` quotient is at most `10^(p1 - k)`, below `2^127`, and the check reads it
before any word is dropped (363's 2.6 and 2.7). No check reads a value that may have wrapped. The
planted fault for row 504 is the rescale up multiplied first and checked after, Trino's order, at a
value whose 128-bit product wraps back into range, the witness taken from the proof's `sat` model.

### 2.3 The three narrow casts under ANSI and TRY

- **Below the threshold scale - 7 for `INT`, 12 for `SMALLINT`, 14 for `TINYINT` - the exact rule**:
  the truncated pair range-checked against the target, `d` `FAIL` or `NULL`. By item 0.6 its answer
  is the double rule's on every input, so it is proven by SMT, and the sampled arm runs beside it.
- **At or above it, the double rule**: `NarrowLane(ConvertLane(GuardedRange(toDouble(x), lo, hi, d),
  INT), target)` for `SMALLINT` and `TINYINT`, without the `NarrowLane` for `INT`, with 343's bounds
  for a `DOUBLE` source and `toDouble` from 2.4. The kernel is long-driven with a double node beside
  the long ones (343's width rule, `VARKA-343.md` 2.2).
- **LEGACY** at every scale is the truncation and Java's narrowing; **`BIGINT`** is never the double
  rule (item 0.4).

Under the `DECLINE` arm of 2.4 the narrow casts compare the two passes' outcomes, not their doubles:
a lane declines only where one pass overflows and the other does not, or the two truncate to
different integers, which no lane of the admission's draws did **[A]**.

### 2.4 To `DOUBLE` and `FLOAT`: one pass over the top 64 bits, and its resolution

**The input to row 366's sequence.** Per lane over `m = abs(x)`, `m < 10^38 < 2^127`:

1. **Exact lanes**, `m.hi == 0`: `w = m.lo` read unsigned, `l0 = LeadingZeros(w)`, `w' = SHL(w,
   l0)`, offset `-l0`. Nothing is dropped.
2. **Wide lanes**, `m.hi != 0`: `l = LeadingZeros(m.hi)`, between 1 and 63 since `2^64 <= m <
   2^127`; `w' = SHL(m.hi, l) | USHR(m.lo, 64 - l)`, offset `64 - l`, and the dropped bits `D =
   NE(SHL(m.lo, l), 0)`. Both shift counts lie in `[1, 63]`, so the funnel needs no arm for a count
   of 64.
3. The two blend into one `(w', offset)` per lane, `m = 0` giving `+0.0` (366's step 1), and 366's
   builder runs once: its step 6 exponent takes the offset in place of `-l`. The value is `w' *
   2^offset * 10^-s` exactly on an exact lane, and on a wide one `x_lo <= |x| < x_hi` with `x_lo =
   w' * 2^offset * 10^-s` and `x_hi = (w' + 1) * 2^offset * 10^-s`. Scale 0 is the same tree at
   Lemire's `q = 0` row (Table 3, `8000000000000000` with a zero second word), so a 128-bit integer
   needs no other path.

This pass gives `d1 = RN64(x_lo)`, or `f1 = RN32(x_lo)` by 366's round-to-odd and one narrowing.
Where `D` is false, `d1` is the answer. Where `D` is true, the true answer is `d1` or its upper
neighbour: `x_hi - x_lo` is below `2^-63` of `x`, less than an ulp, and `RN64` is monotone, so `x`
cannot pass the midpoint above that neighbour. Option `decimalWideFloatResolve` (`MIDPOINT`,
`DECLINE`, `Reason.MACHINE`) decides which:

- **`MIDPOINT`, the default.** With `d1 = M * 2^E`, `M` its 53-bit significand, the midpoint above
  it is `mu = (2M + 1) * 2^(E - 1)`, and `|x|` against `mu` is `m` against `(2M + 1) * 5^s * 2^K`
  with `K = E - 1 + s`. `(2M + 1) * 5^s` is one 360b `WideMul` by the constant `5^s`, a single word
  for `s <= 27`; the per-lane `K` shifts that product left (`K >= 0`) or `m` left (`K < 0`), each
  side within two words, and one 128-bit unsigned compare and an `EQ` give the outcome: above `mu`
  the upper neighbour (`d1`'s bits plus one, a binade crossing included, since `M = 2^53 - 1` gives
  `mu` exactly between `d1` and `2^53 * 2^E`), at `mu` the even one of the two
  (`BigDecimal.doubleValue`'s ties-to-even), below it `d1`. `FLOAT` takes the same test with `f1`'s
  24-bit significand. No lane declines, and the second pass is not computed. The one binary32
  subnormal of item 0.7 is an exact lane.
- **`DECLINE`, the row's text.** A second pass on `w' + 1`, which at `w' = 2^64 - 1` is renormalised
  to `2^63` with the offset plus one; a wide lane is accepted where the two passes give one double
  (one float for `FLOAT`, one outcome for 2.3's narrow casts) and otherwise declines its batch by
  `GuardedRange(CondValue(disagree), 0, 0, FAIL)`. Accepting on agreement is sound because the true
  value lies between the passes' inputs and both roundings are monotone, so equal ends force the
  answer between them; the row engine computes a declined batch.

Both arms are built and measured on the runner against each other and the row path (section 4).
`MIDPOINT` is the default by the lead's settlement of 30 October 2026: at the measured disagreement
rate (item 0.21) `DECLINE` serves a wide `DOUBLE` column's batches only rarely. The arm stays, as
the owner's rule on losing option arms asks, and the measurement scores both.

### 2.5 Scales 28 to 38

Lemire's window and row 366's table stop at `s = 27`, and Varka declines `s >= 28` by choice (row
366's plan, its 2.5; PR #1183's wording of 2.2). Under that choice every floating cast and the three
narrow ANSI and TRY casts of a `DECIMAL(p, s >= 28)` decline at compile time with the reason "the
exact decimal-to-floating arm stops at scale 27", a recorded decline with a scope item in
`m9/SCOPE.md`, as the owner's standing rule for declines asks: one item covering rows 366 and 364
together, added with 364b. The integer casts at these scales are unaffected. Serving the band is a
follow-up to both rows, not this row's alone (settled by the lead): the table rows for `s = 28..38`
from Lemire's section 9.2, `5^s` as a two-word constant in `MIDPOINT`'s product, and Mushtak and
Lemire's Proposition 4 for the no-fallback claim, or Lemire's own fallback condition kept as a
decline that never fires.

### 2.6 At 128 bits, compiler, reference, cost, warm-up

At two long lanes on x86 every pair compare and blend is the Java fallback (`VARKA-359.md` 2.6): the
casts fuse as projections and are measured there, a condition root (`WHERE CAST(d AS BOOLEAN)`)
declines by row 503's rule, and a projection that loses declines with its measured reason. The
reference evaluator needs nothing beyond 359's unsigned compares, 363's `ScaleDown`, 366's two nodes
and 481's `NULL` mode. A `DOUBLE` cast is about a hundred nodes - 366's sequence, the normalisation
and the correction - so the budget's group split may cut it, weighed as any tree; the cost model
gains a feature for each resolve form, on row 352's awaiting-refit list. The warm-up's long block
gains the funnel shift, held by `VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The cast table against Spark's own.** For every type above 18 and every target and mode, and
   for all 606841 decimal pairs, the compiler's owner, its guards and its dead-guard exemptions are
   held to `Cast.canNullSafeCastToDecimal`, the type rules and the counts of 2.1; a sample of each
   class is compiled and run against `Cast.eval` over a `BigDecimal`-backed `Decimal`. A pure
   function, seconds.
2. **Named cases**, each in the three modes, valid and beside a null, first group, middle and last
   row: the 38 nines at `(38, 38)` to `INT` (1 under ANSI, 0 under LEGACY); item 0.6's three first
   differences and its `(19, 7)` and `(19, 6)` pair; item 0.3's low-bit cases and item 0.4's
   `BIGINT` edges, `Long.MinValue` at `(19, 0)` among them (the Comet gap of 2.8, which row 350's
   plan leaves here); `Long.MAX_VALUE`, `2^63`, `2^64 - 1`, `2^64` and `2^64 + 1` at `(20, 0)` to
   `DOUBLE` and `FLOAT`, across the exact-lane boundary; `10^38 - 1` at scale 0 to both; the one
   binary32 subnormal of item 0.7 if the band is served; `-0.5`, `-1.5`, `1.5` at `(38, 1)` to every
   integral type (truncation, never rounding); arrow-rs's fixtures above 18 - `1.050` to `1.1` and
   `-1.050` to `-1.1` from `(20, 3)` to `(19, 1)`, the all-nines carry from `(20, 3)` to `(17, 0)`
   and from `(38, 3)` to `(35, 0)` (`READING_ARROW_RS.md` 3.2); `(38, 0)` to `(38, 20)` at `10^18 -
   1` and `10^18`, the first value the guard rejects; true into `DECIMAL(38, 38)`; `Int.MinValue`
   into `DECIMAL(19, 10)`, whose nine integer digits it overflows.
3. **The planted faults, for row 504**: the multiply-then-check rescale up of 2.2; `MIDPOINT` with
   its tie rule dropped; `DECLINE` without its exact-lane test, which over-declines; each fails the
   suite that should catch it.
4. **SMT**, `sql/varka/proofs/decimal128_casts.smt2` (new), written by `VarkaProofFiles` from the
   code's constants, over integers at full width in 2.8's two layers, under Z3 and cvc5: the sign
   restore over `ScaleDown`'s `DOWN` result against truncation toward zero (the constants are 363's
   and proven in its file, which this one cites); the `BIGINT` check exactly where the truncated
   value leaves `[-2^63, 2^63 - 1]`; LEGACY's low word as reduction mod `2^64` and the narrow wrap
   mod `2^w` (332's `casts.smt2` claims cited if that file lands first); the narrow exact rule's
   check; each guard of 2.2 against the precision check it replaces, the stepped rescale's
   exactness, and each exemption as `unsat` of an overflow; the `BOOLEAN` test; the normalisation,
   `w' * 2^(64 - l) + dropped = m`; `MIDPOINT`'s comparison against `mu`, linear for each shift
   amount, the amounts a case split (if the file's time passes the budget, the split is sampled and
   the file says so); and the planted wrap as a `sat` witness. Item 0.6's lemma is a finite table,
   checked by the admission script and re-checked by a unit test from the code's constants, as row
   350's item 0.14 is.
5. **The sampled arm**, for `DOUBLE`, `FLOAT` and the three narrow casts at and above their
   threshold scale, at every type above 18 with `s <= 27` (and to 38 if the band is served): a
   seeded draw per binade of `[2^64, 10^p)`; the exact lanes below `2^64`; binary64 midpoints drawn
   at every scale 0 to 22 and binary32 ones at 0 to 30, with the unscaled values one either side;
   the lanes whose two passes disagree, found by a search in 366's scalar twin extended to 128 bits
   and tested by name; each integral bound at each threshold scale; against `Cast.eval` and
   `BigDecimal`. **No vacuous pass**: the suite counts exact lanes, wide lanes, each `MIDPOINT`
   outcome (below, above, the tie) and `DECLINE`'s disagreements, and fails if any count is zero.
6. **`MIDPOINT`'s exactness, and how each half is proven.** The one pass gives `q = RN(x_lo)` (or
   `RN32`), and the true answer is `q` or its upper neighbour, since `x_lo <= |x| < x_hi` and the
   gap `x_hi - x_lo` is at most `2^-63` of `x`, under a tenth of an ulp. Which one is decided by
   comparing the magnitude `m` with the midpoint `mu = (2M + 1) * 2^(E - 1)` between `q = M * 2^E`
   and its neighbour, scaled by `10^s`: `|x| > mu` exactly when `m > (2M + 1) * 5^s * 2^(E - 1 +
   s)`, an integer inequality, its equality the tie that goes to the even significand. The test
   therefore uses no floating arithmetic; `M` and `E` are read from `q`'s bits. **The proof route**:
   the integer compare - the product by the constant `5^s`, the per-lane shift of either side and
   the two-word unsigned compare and tie - is proven by SMT over integers in `decimal128_casts.smt2`
   (item 4); the one pass is row 366's sequence on a normalised input, exact by Lemire's theorems
   that row cites, and is held by the sampled arm (item 5), not SMT.
7. **Coverage rows** in 350's "DECIMAL casts" family over 359's fixtures (`d19`, `d38`, `d38b`,
   `d3810`) and one more, `d38s20` (`DECIMAL(38, 20)`, so the double rule's scale is reached): each
   form of 2.2's table in each legal mode, the `BOOLEAN` target as a filter, each naming its
   verification for row 490's check. `VarkaCastMatrixSuite` regenerates `casts_fused.tsv`, whose
   `DECIMAL(30, 4)` forms then fuse in every mode, and `dev/varka_census.py` the `fused_today`
   column.
8. **Conformance**: 359's decimal conformance suite (its answered question 4) gets this row's cast
   rows, the ANSI forms over an in-range value set.
9. **The fuzzers**: the IR grammar draws 2.2's shapes with each guard mode and both resolve arms in
   the decimal sequence; the Spark-level fuzzer's decimal columns above 18 cast to every target in
   each mode. Spark's cast suites through row 368's harness for these pairs.
10. **The bytes oracle**: `emitted_bytes.json` adds rows and moves no existing shape.

The sweeps - the cast table over the 606841 pairs, the sampled arm in full, the twin's search - run
by hand in the gate's sweep step (`-Dvarka.sweep=true`), their times stated in the pull request,
with a slice in PR CI: no CI workflow sets `varka.sweep` today, so no nightly run is claimed.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code**, in
`VarkaDecimalBaselineBenchmark` beside the sections rows 350, 360, 361 and 363 add to the same class
(each row names its own, and this one reuses 350's `BIGINT` column), every section in range for its
mode:

- over the `(38, 10)` column: `CAST(x AS BIGINT)` under LEGACY and ANSI, `CAST(x AS INT)` under
  LEGACY, `CAST(x AS DOUBLE)`, `CAST(x AS FLOAT)`, `CAST(x AS DECIMAL(38, 4))` (a rescale down
  within the pair) and `WHERE CAST(x AS BOOLEAN)`; its existing cast to `(18, 4)` is the
  128-to-64-bit rescale down;
- a `(38, 20)` column with integer parts below `10^4`: `CAST(x AS INT)` under ANSI and `try_cast(x
  AS INT)`, the double rule's arm, and `CAST(x AS DOUBLE)`;
- a `(20, 2)` column with integer parts to `5 * 10^12`, so every value is an exact lane: `CAST(x AS
  DOUBLE)`;
- over the `(18, 4)` column, `CAST(x AS DECIMAL(21, 8))` under LEGACY and ANSI, a fallible rescale
  into the pair (`18 + 4 > 21`) whose guard, at integer parts up to `10^13 - 1`, is live and never
  rejects; and over 350's `BIGINT` column, `CAST(l AS DECIMAL(25, 8))` under LEGACY and ANSI, a live
  integral guard.

Run by `benchmark.yml` on the EPYC 7763 at 256 and 128 bits with `expected-cpu`, committed beside
the class's files.

Predictions, registered before the build:

1. **At 256 bits on the EPYC 7763** the integer casts - truncations, rescales, the integral and
   boolean sources - run 5 to 20 times faster than the row path, which builds a `BigDecimal` per
   value: the existing rescale down costs 315.1 ns a row
   (`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`).
2. **`DOUBLE` at `(38, 10)` under `MIDPOINT`** 3 to 10 times faster than the row path; under
   `DECLINE` within 1.2 times of the row path, nearly every batch declining (item 0.21).
3. **`FLOAT` within 1.2 times of `DOUBLE`**; `DOUBLE` over the `(20, 2)` column, all exact lanes,
   1.2 to 2 times faster than over `(38, 10)`; there `MIDPOINT` and `DECLINE` within 1.1 times of
   each other, since neither does work a lane needs.
4. **The double rule at `(38, 20)`** under ANSI costs 1.5 to 3 times LEGACY's `INT` cast of the
   `(38, 10)` column.
5. **At 128 bits on x86** the casts are the fallback; the projections still beat the row path by 1.5
   to 4 times, and the filter declines by row 503's rule.
6. **No existing shape's bytes move**; the `option_arms` digests move with
   `decimalWideFloatResolve`.

## 5. Order, files and size

1. **The baseline addition** (4), its own pull request.
2. **The twin's extension**, test side only: 366's `ExactToFloatingScalar` gains the 128-bit entry
   and both resolve forms, finds the disagreeing lanes and the midpoints for 3 item 5, and times the
   sampled arm.
3. **364a, the integer casts**, after 359a and 359b, 360b, 363a (`ScaleDown` at a 128-bit dividend,
   both roundings), 481, 332a and 350a: truncation and its three modes, the `BIGINT` check, the
   narrow exact rule below the threshold scales, every decimal-to-decimal class of 2.1, the integral
   and boolean sources, the `BOOLEAN` test; `decimal128_casts.smt2`, the table test, the named
   cases, the planted rescale fault, the coverage rows.
4. **364b, the floating targets and the double rule**, after 366a, 343 and 350c: the normalisation,
   both resolve arms, `DOUBLE` and `FLOAT` at `s <= 27`, the narrow double rule at and above the
   threshold scales, the sampled arm, the remaining planted faults, the measurement against the
   baseline at both widths and 512 bits on the laptop, scored; the band of 2.5 declined with its
   shared scope item.

| file | what |
|---|---|
| `VarkaDecimalCastCompiler.java` (row 350's) | 2.2's table over the pair, 2.3, 2.4's composition |
| `VarkaDecimalCompiler.java` | the coercion casts at two scales through 2.2's arms |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalWideFloatResolve` |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weights, features, reach |
| `sql/varka/proofs/decimal128_casts.smt2`, `VarkaProofFiles.java` | 3 item 4 |
| tests | the table test, named cases, the twin's extension, sampled suite, planted faults, coverage rows, fuzz forms |
| benchmark | the baseline sections, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `casts_fused.tsv`, `casts.tsv`, `census.tsv`, `census_curated.txt` |

**Size: medium as the row says, near large**, in two pull requests after the baseline and the twin:
the baseline half a day, the twin a day, 364a about four days (the class count and the proof file),
364b about four. No IR node is new: the row composes 359's, 360b's, 363's and 366's. Test time: the
table test is a pure function; the named cases are of the slot table's size, seconds; the sampled
suite is a few million `Cast.eval` calls, opt-in in full and a slice in PR CI; the proof file is
priced from its spiked form at VARKA-245's observed ratio in the calibration table, under twenty
seconds per solver. It rests on 359, 360b, 363, 366, 350, 343, 481 and 332, and on 503 for the
128-bit rule.

## 6. What it unlocks in the census

A decimal pair counts fused only when it fuses at every precision up to 38 (the milestone plan's
2.8; 359's answered question 2), so this row is where the census's cast entries over a decimal input
count, given 350 and 366. Of the ten - `cast`, `try_cast`, `boolean`, `tinyint`, `smallint`, `int`,
`bigint`, `float`, `double`, `decimal` - `boolean` (as a projection given row 27's boolean output),
`bigint` and `decimal` count when 364a lands, while `float`, `double`, `int`, `smallint` and
`tinyint` cannot count while the band of 2.5 declines (section 8, item 5). `cast` and `try_cast`
wait on the six unclaimed pairs of item 0.20. The 22 comparison and choice pairs `VARKA-359.md` 6
parks on "360 and 363" count with 360b's rescale up and this row's coercion casts through 363's
division, as 363's plan has it.

## 7. Out of this row, and declines that stay

Out: everything within precision 18 (350); the infallible widening casts and `BIGINT` to
`DECIMAL(20, 0)` (360); `ScaleDown` and its constants (363); the 64-bit exact arm, its nodes and its
table (366); `DOUBLE` and `FLOAT` to decimal (346); the six pairs of item 0.20, with the homes row
350's plan gives them; a general FMA node; 366's working-precision option arm. An integer form of
the double rule - the truncated pair moved by one where the remainder lies within half an ulp of the
next integer - would avoid the floating lane for the narrow casts at and above the threshold scales;
`MIDPOINT` already makes the double exact there, so it is not built.

Declines that stay, each with its reason: an ANSI batch holding a value Spark raises on (the row
engine raises it); the floating casts and the narrow double rule at `s >= 28`, by Varka's choice
(2.5, one scope item shared with 366); under `DECLINE`, a batch with a disagreeing lane; a child
whose backing the compiler cannot classify, for the three narrow casts (item 0.2); a decimal in an
int-driven kernel (347's leaf rule); a negative scale; at two long lanes on x86 a condition root
(503) and any projection whose measurement loses there.

## 8. Questions for the owner

1. **A refinement of the answer to question 3, not a departure: the double rule computed as the
   exact rule below a scale.** The owner's answer of 16 October 2026 (the milestone plan's section
   7, question 3) has the kernel reproduce "vanilla Spark's column-path rule (the double rule over a
   `BigDecimal`-backed value) for the ANSI and TRY casts of a decimal to `INT`, `SMALLINT` and
   `TINYINT`". By item 0.6 the two rules give the same answer on every input at a scale below 7 for
   `INT`, 12 for `SMALLINT` and 14 for `TINYINT`, at any precision, so (a) the kernel computes those
   by the integer rule, proven by SMT, and takes the double through 2.4 at and above them; (b) it
   computes the double at every scale. **The answers are identical either way**, and the sampled
   checks against Spark's `Cast` run at every scale under both. *Recommended: (a)*: a truncating
   `ScaleDown` against the normalisation, a whole exact-arm pass and the correction, and an SMT
   proof added without removing the sampled one; it is row 350's question 1 at a scale threshold
   instead of a precision one.
2. **Flagged, the owner's text: "accepted when equal and declined otherwise".** Row 364's text and
   the milestone plan's 2.2 ("declines the lane otherwise, about one in a thousand by the reading's
   estimate") treat the decline as rare; in Varka a decline is per batch, and the admission's draws
   put one disagreeing lane in two to four thousand, so a wide `DOUBLE` column's batches decline
   nearly always (item 0.21). Wave F3's "364's two-pass arm" names the same form. This plan makes
   `MIDPOINT` the default, by the lead's settlement, and keeps the two passes as the measured
   `DECLINE` arm; both phrases are the owner's and are left as they are.
3. **Flagged, the owner's text: the `Long.MAX_VALUE` exclusion** applies to a guard this plan does
   not emit (0, "What differs"); the value is a named test.
4. **Flagged, the owner's text: 2.2's abort wording**, which PR #1183 rewords to Varka's choice;
   this row inherits the decline at `s >= 28` (2.5), with one scope item shared with 366.
5. **For information: the census cost of the band.** While `s >= 28` declines, five of the ten cast
   entries over a decimal input - `float`, `double`, `int`, `smallint` and `tinyint` - cannot count
   fused, since a pair counts only at every precision and scale (section 6). Serving the band, by
   extending the table to scale 38 on Mushtak and Lemire's Proposition 4, would be a follow-up to
   rows 366 and 364 together.

### Open points for the lead

Settled by the lead on 30 October 2026, kept as the record: (1) `MIDPOINT` is the default and
`DECLINE` the measured arm, with the exactness argument and its proof route stated (3 item 6) and
the two departures from the owner's text flagged (section 8, item 2); (2) `s >= 28` declines by
Varka's choice, as row 366's does, under one `m9/SCOPE.md` item for both rows, the census cost put
to the owner as information (section 8, item 5), and extending the table a follow-up to both rows;
(3) the double rule over every child, the long-backed producers Varka can see classified by item
0.2's argument and the rest declined; (4) the ownership of 2.1 as counted, one owner per pair, the
three classes no row text names recorded in "What differs". None is open. Outside this row: the
backing question reaches row 350 at precision 17 and 18, where the rules differ too, and that plan
does not raise it.

## 9. Done when

Row 364's text holds, with the corrections above and the answers to section 8: every decimal pair of
`m8/casts.tsv` that section 1 names fuses in the three modes at every `(p, s)` with `p >= 19`, or
carries its recorded reason (the band of 2.5, with its shared scope item); the three narrow ANSI and
TRY casts follow the double rule, computed as section 8's question 1 is answered; both resolve arms
of 2.4 are built and measured, `MIDPOINT` the default; the integer rules are proven in
`decimal128_casts.smt2` under Z3 and cvc5 at full width, item 0.6's lemma re-checked by its unit
test, and the floating targets' and the double rule's sampled arm pass with non-zero vacuity counts;
the table test, the named cases and the planted faults (failing with their flags) pass; each rescale
site names its defence with a test at the first value its guard rejects; `casts_fused.tsv`,
`casts.tsv` and the census agree with the compiler; no existing shape's bytes move; the measurement
against the baseline is committed at both widths with its predictions scored; and the PR build and
the option matrix pass.

## 10. Outcome

(Written when the work lands.)
