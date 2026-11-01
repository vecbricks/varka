# VARKA-346: The constructions behind the former hard entries

## 0. What exists now (admission check)

From master at `7ea5b6242c7` (30 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/` unless named),
from the plans this row builds on - `VARKA-341.md`, `VARKA-342.md` and `VARKA-343.md` on master,
row 344's on `varka-344-plan` at `3084e7e1b1b`, rows 350's and 366's on `varka-350-366-plan` at
`6981bde9576` - from the JDK 25 sources in `/home/max/proj/openjdk-build/jdk25` (`src/java.base/
share/classes/` below) and from scala-library 2.13.18's sources (`pom.xml:182`). The row's text is
the milestone plan's 2.7, "The former hard entries (346)", and its G2 wave row ("346 after 344 and
350"). The numbers marked **[A]** are from `results/VARKA-346-admission.txt`, a Java program and its
output, run on the laptop for this plan; nothing else was run.

**What Spark computes.**

1. **`round`, `bround` and `truncate` over `DOUBLE` act on the shortest decimal.** `RoundBase`
   with `HALF_UP`, `HALF_EVEN` and `DOWN` (`mathExpressions.scala:1925-2023`) types a floating
   result as its operand (`:1724`) and takes the scale as a foldable `INT` (`:1727-1745`), a null
   scale giving null (`:1761`). Over `DOUBLE` the generated code is
   `java.math.BigDecimal.valueOf(d).setScale(s, mode).doubleValue()`, NaN and the infinities
   returned as they are (`:1876-1883`); the interpreted code is the same through Scala's
   `BigDecimal(d)` (`:1815-1821`), which is `decimal(d)`, `new BigDecimal(Double.toString(d),
   DECIMAL128)` (scala-library's `scala/math/BigDecimal.scala:50-51`, `:219`): at most 17 digits,
   so the 34-digit context never rounds. JDK 25's `BigDecimal.valueOf(double)` builds the value
   `Double.toString` prints (`java/math/BigDecimal.java:1369-1398`, its apiNote), and that decimal
   is selected by `Double.toString`'s specification (`java/lang/Double.java:513-530`): of the set
   `R` of decimals that round to the double, those of minimal length, or of length 1 or 2 when the
   minimum is 1, and among them the one closest to the double, an even last digit breaking a tie.
   So the operand is `T`, the shortest decimal, not the binary value: `2.675` at scale 2 rounds to
   2.68 under `HALF_UP` although the double is below 2.675 **[A, item 3]**.
2. **No answer is a negative zero, and none raises.** A `BigDecimal` has no signed zero:
   `round(-0.0, 2)`, `round(-0.004, 2)` and `truncate(-0.5, 0)` are `+0.0` **[A, item 3]**.
   `doubleValue` returns `(double) intCompact / DOUBLE_10_POW[scale]` for an unscaled value exact
   as a double and `0 < scale <= 22`, one rounding of exact operands (`BigDecimal.java:3825-3862`).
   `ansiEnabled` reaches only the integral types, so no mode changes a floating answer.
3. **`FLOAT` goes through its widened double.** The generated code passes the float to
   `BigDecimal.valueOf(double)` (the only overload a float widens to) and ends in `floatValue()`
   (`:1868-1875`), the interpreted code `BigDecimal(f.toDouble)` and `toFloat` (`:1808-1814`); so
   `T` is the widened double's shortest decimal, and the result is `floatValue`, rounded once from
   the exact decimal (`BigDecimal.java:3742-3764`, `fullFloatValue`; `VARKA-366.md` 0, item 2).
   `9.95f` widens to 9.949999809265137 and rounds to 9.9 at scale 1 **[A, item 3]**.
4. **`CAST(d AS DECIMAL(p, s))` is `HALF_UP` of the same decimal.** The interpreted cast is
   `changePrecision(Decimal(d), target)` inside a catch of `NumberFormatException` that answers null
   (`Cast.scala:1404-1410`); the generated one is `Decimal.apply(scala.math.BigDecimal.valueOf(
   (double) c))` in the same catch (`:1948-1957`), in every mode, so NaN and the infinities are null
   even under ANSI. `Decimal(d)` takes Scala's implicit `double2bigDecimal`, `decimal(d)`
   (`BigDecimal.scala:304`); `changePrecision` on that `BigDecimal`-backed value is `setScale(s,
   HALF_UP)` and fails when its precision exceeds `p`
   (`sql/api/src/main/scala/org/apache/spark/sql/types/Decimal.scala:458-463`), which is null under
   LEGACY and TRY and `NUMERIC_VALUE_OUT_OF_RANGE` under ANSI (`Cast.scala:1348-1361`). So the
   cast's unscaled value is `round(d, s)`'s `HALF_UP` integer with its sign, and the cast overflows
   exactly when that integer reaches `10^p` **[A, item 3: `9.95` at 1 is 100, `3527.61953125` at 7
   is 35276195313, `10.3` at 1 is 103]**. A `FLOAT` source is `(double) c` first.
5. **`%`, `mod` and `try_mod` over a floating type are Java's `%`.** `Remainder` computes
   `left % right` on doubles and floats (`arithmetic.scala:1095-1098`) after `DivModLike`'s divisor
   test `x == 0` (`:687-690`), true for `-0.0`: null under LEGACY, `REMAINDER_BY_ZERO` under ANSI
   (`:701-725`), with `failOnError` ANSI alone (`:1058`), so `try_mod`, `Remainder(_, _, TRY)`
   (`TryEval.scala:179-186`), answers as LEGACY. A non-zero literal divisor drops the test
   (`:696-699`). Java's `%` is the truncating remainder, exact, with the dividend's sign, a zero
   result included; NaN for a NaN operand or an infinite dividend; the dividend itself for a finite
   dividend over an infinite divisor (JLS 15.17.3).
6. **`pmod` is two remainders.** `MathUtils.pmod(Double)` is `r = a % n; if (r < 0) (r + n) % n
   else r` (`sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/MathUtils.scala:124-127`),
   called by both paths (`arithmetic.scala:1189`, `:1247`); the zero divisor is null under
   LEGACY and raised under ANSI. `-0.0 < 0` is false, so `pmod(-0.0, 3.0)` and `pmod(-3.0, 3.0)` are
   `-0.0`; `r + n` rounds, so `pmod(-1e-300, 1.0)` is `0.0`; a negative divisor gives a negative
   answer, `pmod(-1.0, -3.0)` being -1.0 **[A, item 7]**. The float overload is the same in float.

**What the rows before this one give.**

7. **Every form declines today.** `floatingDecline` declines `Remainder` and `Pmod` as "floating
   arithmetic (row 342)", the cast as row 343's, and `Round`, `BRound` and `Truncate` by its default
   (`VarkaNodeCompiler.java:338-358`); 342's plan keeps `Remainder` and `Pmod` declining naming this
   row (`VARKA-342.md` 2.5), and 344's keeps the three rounding functions over a floating operand
   declining naming it (`VARKA-344.md` 2.8, on its branch).
8. **344's `RoundToIntegral`** has `RINT` to `DOUBLE` and `FLOOR` and `CEIL` to `LONG`; its
   constructor refuses `FLOOR` or `CEIL` to `DOUBLE`, "which has no reader until 346", and leaves a
   `TRUNC` mode to this row (`VARKA-344.md` 2.2, 7). Its reference evaluates by `StrictMath`, never
   by the construction (its 2.8).
9. **342 gives the floating division and no FMA.** `IntOp.DIV` at the floating lanes, correctly
   rounded, never a reciprocal (`VARKA-342.md` 2.1, 2.2); C2 makes `FmaVD` only from an explicit
   FMA (its item 0.6), and no planned row builds an FMA node (`VARKA-350.md` 8, question 4, which
   names this row's remainder as a user and 342 as the natural owner).
10. **343 gives the conversions and the null disposal**: `F2D`, `D2F`, the reinterpretations, the
    floating `GuardedRange` with NaN outside and its `NULL` disposal in 343b (`VARKA-343.md` 2.1 to
    2.4). **350 gives the 64-bit decimal value** (`Scaled`, its overflow guard) and names row 157's
    store; 366 is the opposite direction, decimal to floating, and gives this row nothing to reuse.
11. **The census cells.** `double.FMA` and `float.FMA` are intrinsic at every width on the laptop
    (`sql/varka/operator_census.json`), the EPYC 7763 and the Neoverse N2
    (`results/VARKA-376-census-epyc-7763.json`, `results/VARKA-376-census-neoverse-n2.json`); the
    double compares are fallbacks at two lanes on both x86 hosts; there is no double `DIV` cell
    (342 adds it).
12. **The census names this row on 14 pairs and the cast matrix on 2.** `round`, `bround`,
    `truncate`, `%`, `mod`, `pmod` and `try_mod` over `float` and `double` (`m8/census.tsv`), and
    `float` and `double` to `dec` (`m8/casts.tsv`, semantics "a lanewise guard from the rounding
    interval and an integer arm in lanes, the exact shortest-digits path for the rest").
13. **No baseline times any form.** Row 377's `VarkaFloatingBaselineBenchmark` times a comparison,
    a chain, a cast to an integral type and a choice; its chain costs the row path 57.9 ns a row at
    256 bits with no nulls (`VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-results.txt`).
    344's planned `VarkaRoundingBaselineBenchmark` has no floating `round`.

**What the admission program found [A].** The constructions of section 2, written in scalar Java,
against the calls Spark makes:

14. **Below the guard every answer agrees.** 101438400 checks of `(d, s, mode)` over `s` from 0 to
    22 - every binade from the subnormals up, the doubles nearest every tie and multiple of 20000
    random integers per scale and three ulps either side, decimals of `s + 1` fractional digits -
    differ nowhere; 1014822 of them are ties at the shortest decimal. At scale 0 the formula also
    holds up to `2^52` (55800000 checks), and above the keep bound every answer is `d` (13903413).
15. **The guard is tight for `s >= 1`.** Run in the band anyway, the formula is wrong on about a
    fifth of `HALF_UP` lanes, from the first binade past the guard (`1.000 L` to `1.099 L` by
    scale).
16. **`FLOAT`**: 11349672 checks, none differ. **The integer arm**: an integral double is its own
    shortest decimal through `2^53`, and the first binade where it is not is `2^54` (3947 of 20000).
    **Subnormals** answer `+0.0` at every scale and mode (1800000 checks); `Double.MIN_VALUE` prints
    two digits, `4.9E-324`.
17. **The remainder**: 16792255 pairs, 1638331 of them in the declined band `|x / y| >= 2^53`, none
    different from Java's `%` or from the exact remainder; `pmod`, 15153924 pairs, none different.

### What differs from the row text and the milestone plan

- **The tie test computes the answer; it does not only guard.** One formula per mode gives the
  rounded integer below the guard, ties included, and the same `HALF_UP` integer is the cast's
  unscaled value (2.3, 2.4). So the cast uses the tie test, not the row's rounding-interval guard,
  which declines exactly the ties - `9.95` at scale 1 and `0.125` at scale 2 among them. The guard
  is not built. Ties are about one lane in a hundred (1014822 of the 101438400 checks of item 0.14,
  and decimal-entered data has more), so a guard that declines its batch on any tie declines nearly
  every real batch; it is not a competing arm with a machine-dependent winner, and the tie test
  accepts every lane the guard does below `L_s`, past which the guard reaches only a sliver below
  `2^49` at some scales, under one binade.
- **Scale 0 has no declined band** (2.3): there every boundary is a multiple of the ulp up to
  `2^52`, which the admission program confirms (item 14), and the keep bound is `2^52`. The band of
  `s >= 1` is the non-integral values of `[2^E, 10^(16 - s))`, three and a half to four and a half
  binades: an integral value there is its own shortest decimal and answers itself (2.2).
- **The exact shortest-digits path is the row engine**, through the declined batch: no kernel can
  select the shortest decimal in a lane today, which is `m9/SCOPE.md` item 97; an in-kernel scalar
  escape would be a new emission mode, a row of its own. The band is a decline that stays (7).
- **The FMA node is row 342's**, the earliest wave owning a shared node as every plan of the
  milestone has it (`VARKA-350.md` 8, question 4, names 342 its natural owner); this row uses it for
  the remainder and the `FLOAT` result's round-to-odd, and the `DOUBLE` rounding and the cast need
  none. 342's plan has no FMA today, so its plan gains it before 346a starts (8, flag 1).
- **This row owns the floating remainder node.** Row 30's `IntDivide` is integral only; the
  floating `%` and `pmod` are a node of this row's.
- **Only `FLOOR` is added to `RoundToIntegral`'s double modes.** The constructions work on
  magnitudes, where `TRUNC` is `FLOOR`; `CEIL` and `TRUNC` to `DOUBLE` have no reader and stay out,
  by 344's own rule.
- **The proof cites `Double.toString`'s specification, not Schubfach's R5.** Spark calls the JDK,
  whose specification (item 0.1) is the definition the proof needs; the Schubfach paper is not in
  the repository, so its R5 was not checked here (8, flag 3).
- **The cast is within precision 18.** Above it the unscaled value needs 359's pair, which is
  364's composition over this row's integer.
- **The order inside the row.** Only the cast needs 350; the remainder and the rounding functions
  need 342, 343a and 344a (section 5).
- **Large, in three pull requests after the baseline**, against the row's medium (8, question 1).

## 1. Goal and non-goal

`round`, `bround` and `truncate` over `DOUBLE` and `FLOAT` at every scale from 0 to 22; `%`, `mod`,
`try_mod` and `pmod` over `DOUBLE` and `FLOAT`; and `CAST` of `DOUBLE` and `FLOAT` to `DECIMAL(p,
s)` with `p <= 18` in LEGACY, ANSI and TRY - each fused with the row engine's answer to the bit,
each batch with a lane in its recorded band declined to the row engine, the census carrying the
band. Not this row: a negative scale or a scale above 22 (declined at compile time), the cast above
precision 18 (364), the integral and decimal forms (344, 30, 351, 363), Schubfach's selection in
lanes (`m9/SCOPE.md` 97).

## 2. Design

### 2.1 What every form computes

For a finite `d`, a scale `s` and a mode, with `a = |d|` and `T` the shortest decimal of `a`: `n`
is `T` rounded to `s` places by the mode, times `10^s`, a non-negative integer. Then:

- `round`, `bround`, `truncate` over `DOUBLE`: `RN(n / 10^s)` with `d`'s sign, `+0.0` when `n` is 0;
  `d` itself when it is NaN or infinite.
- over `FLOAT` `f`: the same `n` for `a = |(double) f|`, then `RN32(n / 10^s)` with the sign; `f`
  itself when NaN or infinite.
- `CAST(d AS DECIMAL(p, s))`: the unscaled value `+-n` (`HALF_UP`), overflowing when `n >= 10^p`;
  null for NaN and the infinities in every mode.

### 2.2 Three regions per scale

`L_s = 2^E`, `E` the largest integer with `2^(E - 53) < 10^-(s + 1)`, so every `a < L_s` has
`ulp(a) < 10^-(s + 1)`; `K_s = 10^(16 - s)` for `s >= 1`, as a double rounded up, and `2^52` for
`s = 0`. Below `L_s` the tie test computes `n` (2.3); from `K_s` up the answer is `d`; between them
an integral `a` answers `d` and a non-integral one declines the batch. At `s = 0`, `L_0 = K_0 =
2^52`. The bounds, from the admission program **[A]**:

| `s` | `L_s` | `K_s` | band, binades |
|---|---|---|---|
| 0 | `2^52` | `2^52` | none |
| 1 | `2^46` | `10^15` | 3.83 |
| 2 | `2^43` | `10^14` | 3.51 |
| 3 | `2^39` | `10^13` | 4.19 |
| 4 | `2^36` | `10^12` | 3.86 |
| 5 | `2^33` | `10^11` | 3.54 |
| 6 | `2^29` | `10^10` | 4.22 |
| 7 | `2^26` | `10^9` | 3.90 |
| 8 | `2^23` | `10^8` | 3.58 |
| 9 | `2^19` | `10^7` | 4.25 |
| 10 | `2^16` | `10^6` | 3.93 |
| 11 | `2^13` | `10^5` | 3.61 |
| 12 | `2^9` | `10^4` | 4.29 |
| 13 | `2^6` | `10^3` | 3.97 |
| 14 | `2^3` | `10^2` | 3.64 |
| 15 | `2^-1` | `10^1` | 4.32 |
| 16 | `2^-4` | `10^0` | 4.00 |
| 17 | `2^-7` | `10^-1` | 3.68 |
| 18 | `2^-11` | `10^-2` | 4.36 |
| 19 | `2^-14` | `10^-3` | 4.03 |
| 20 | `2^-17` | `10^-4` | 3.71 |
| 21 | `2^-21` | `10^-5` | 4.39 |
| 22 | `2^-24` | `10^-6` | 4.07 |

**The keep region.** For `s >= 1` and `a >= 10^(16 - s)`, `T` is no smaller than `10^(16 - s)`, so
it has its leading digit at `10^(16 - s)` or above and at most 17 significant digits, so its last
digit is at `10^-s` or above: the rounding leaves it unchanged, and `RN(T) = a`. For `s = 0` and
`a >= 2^52`, `a` is an integer, so its own digits are a decimal in `R` with no fractional digit;
`T` is no longer and, within one of `a`, has as many integer digits, so it is an integer too,
rounds to itself and gives `a`. NaN and the infinities take the same arm: it is written
`not (a < K_s)`, which a NaN satisfies. A `FLOAT` `f` in the keep region answers `f`: `T` lies
within half a double ulp of `(double) f`, a float, so `RN32(T) = f`.

**The integer arm in the band.** An integral `a < 2^53` is its own shortest decimal: `R` holds no
other integer, and a decimal with a fractional digit near `a` is longer than `a`'s digits (the
admission program confirms it through `2^53`, item 0.16). So it rounds to itself at every scale and
the answer is `d`. Every band value is below `K_1 = 10^15`, so the test `RINT(a) == a` (344's
`RINT`) keeps every integral lane of the band fused, and only the non-integral ones decline.

### 2.3 The tie test, and why it is exact

With `P = 10^s` and `2P` exact doubles (`s <= 22`), `y = RN(a * P)`:

    HALF_UP:    k = FLOOR(y);  t = (2k + 1) / (2P);  n = k + [a >= t]
    HALF_EVEN:  k = FLOOR(y);  t = (2k + 1) / (2P);  n = k + [a > t  or  (a == t and k odd)]
    DOWN:       m = RINT(y);   t = m / P;            n = m - [a < t]
    DOUBLE out: (bits(n / P) | sign(d)) + 0.0

Every operation is IEEE's, correctly rounded in a lane as in the scalar code; `FLOOR` and `RINT` to
`DOUBLE` are 344's construction. The argument, for `a < L_s`:

1. **Membership is one division.** `R`, the set of reals that round to `a`, is an interval of width
   at most `ulp(a)`, and the decimals `Double.toString` chooses from are those in `R` (item 0.1).
   For an integer `w < 2^53` and `c` = `P` or `2P`, `w / c` is computed with one rounding,
   Clinger's fast path with its bounds (`w <= 2^53`, `|q| <= 22`; Lemire 2021, section 3, in
   `sql/varka/papers/lemire-2021-number-parsing-gigabyte-per-second.md` on `varka-350-366-plan`). So
   `t == a` exactly when the decimal `w / c` lies in `R`.
2. **A boundary in `R` is `T`.** The `HALF` boundaries `(j + 1/2) / P` have `s + 1` fractional
   digits, and decimals with at most `s + 1` fractional digits are spaced `10^-(s + 1)`, wider than
   `R`; so a boundary in `R` is the only such decimal there, every other decimal in `R` has more
   fractional digits in the same decade and so more digits, and the boundary is `T`. When it has
   one digit (`5 * 10^-(s + 1)`), the length-2 candidates are spaced `10^-(s + 2)`, still wider than
   `R`, since `ulp(a) <= a * 2^-52`. The same holds for a multiple `j / P` in `R` under `DOWN`.
3. **Otherwise `T` rounds as `a` does.** With no boundary in `R`, `T` and `a` lie in one interval
   free of boundaries, so the mode rounds them alike.
4. **The side is one compare.** Where `t != a`, `a - (2k + 1) / (2P)` has the sign of `a - t`,
   since rounding is monotone and fixes `a`; the same for `m / P`.
5. **The candidates are right.** `y < 2^53 / 10`, so `|y - aP| <= 1/8`, and `R` scaled by `P` is
   narrower than `1/10`. So the integer nearest `aP` is `k` or `k + 1`, a boundary in `R` is
   `k + 1/2`, a multiple in `R` is `m`, and `floor(aP)` is `m - 1` or `m`. And `2k + 1` and `m`
   are below `2^53`, as step 1 needs.

So the formulas give `n` exactly. Then `n < 2^53` and `n / P` is `doubleValue`'s own `v /
DOUBLE_10_POW[scale]` (item 0.2), one rounding; the sign is put back by OR, and `+ 0.0` turns the
`-0.0` of `n = 0` into `+0.0` and leaves every other value alone. **At scale 0** the formula holds
up to `2^52`: for `a` in `[2^49, 2^52)` the ulp is 1/8, 1/4 or 1/2, every boundary `j + 1/2` and
every integer is a multiple of it, so one lies in `R` only when it equals `a`; then `T = a`, since
every decimal with one fractional digit in `R` shares `a`'s integer part and `a` is the closest;
and `y = a` exactly.

**`FLOAT`.** `a = |F2D(f)|`, the same `n`, then `RN32(n / P)` by round-to-odd: `q = n / P`, `r =
FMA(-q, P, n)`, the exact remainder, `q` moved to its odd neighbour toward `r` where `r != 0` and
`q`'s last bit is 0, then `D2F`, the sign by OR and `+ 0.0f`: one rounding to binary32 of the exact
quotient (Boldo and Melquiond's Theorem 3, 53 bits against `24 + 2`, as `VARKA-350.md` 2.4 uses it).
The plain narrowing of the double answer rounds twice; the admission program found no lane where it
differs (item 0.16), which shows the case is rare, not absent.

The construction is about 25 lane operations a `DOUBLE` lane, two of them divisions, with the band
guard and the keep blend.

### 2.4 The cast to a decimal

`CAST(d AS DECIMAL(p, s))`, `p <= 18`, over `a = |d|`:

- **below `L_s`**: `n` by the `HALF_UP` formula, converted to a long (343's `D2L`; `n < 2^50`, so
  the AVX2 magic form is exact with no guard), with `d`'s sign;
- **an integral `a < 2^53`** (`RINT(a) == a`): `n = D2L(a) * 10^s`, since `a` is its own shortest
  decimal (2.2); `D2L` is whole under 343's default AVX2 assembly, while its `MAGIC` arm declines
  a lane in `[2^51, 2^53)`;
- **`a >= 10^(p - s)`**: an overflow, decided without the digits: `10^(p - s)` is an exact double
  for `p - s <= 18`, so `T >= 10^(p - s)` as well;
- **otherwise** the batch declines: a non-integral `a` in `[L_s, 2^52)`, and any `a` in `[2^53,
  10^(p - s))`, which exists only for `p - s >= 16`;
- NaN and the infinities are null in every mode, by 343b's floating `GuardedRange(d, -MAX_VALUE,
  MAX_VALUE, NULL)`; the overflow is 350's guard on the unscaled long, `NULL` under LEGACY and TRY
  and `FAIL` under ANSI, which declines the batch so the row engine raises.

`FLOAT` widens first. The result is 350's `Scaled` value and 157's store.

### 2.5 The floating remainder

Over `ax = |x|`, `ay = |y|`, with `x`'s sign bit `sx`:

    ax < ay                          ->  x                (a finite x over an infinite y too)
    otherwise  q  = FLOOR(ax / ay)
               r0 = FMA(-q, ay, ax)
               r  = r0 < 0 ? r0 + ay : r0
               out = bits(r) | sx
    declines where  ax < inf, y != 0, ax >= ay and RN(ax / ay) >= 2^53

**Why it is exact.** Let `Q = ax / ay` and `qt = floor(Q)`, below `2^53` inside the window, so a
double. Rounding is monotone and fixes `qt`, so `q` is `qt` or `qt + 1`. Since `ax >= ay`, the
quantum of `ax` is at least that of `ay` (both `2^-1074` among subnormals), so `ax - q * ay` is an
integer multiple of `ay`'s quantum of magnitude at most `ay`, which is a double: the FMA is exact.
If `q = qt + 1`, `r0` is negative and `r0 + ay = ax - qt * ay` is again a double, so the add is
exact. `r` is then Java's `ax % ay`, and Java's `x % y` is it with the dividend's sign, a zero
result included (item 0.5). An infinite `x` gives NaN through the FMA, and a NaN operand
propagates, as Java's `%` does; both are computed NaNs, compared by NaN-ness. A zero divisor never
reaches the value: `FloatRemainder`'s zero test nulls or condemns the lane first.

**`pmod`** is `IfElse(r < 0, REM(RN(r + n), n), r)`. The second remainder's band guard is dead,
since `|r| < |n|` gives `|RN(r + n)| <= 2 |n|`, and the compiler drops it. `try_mod` is `%` under
LEGACY's disposal.

**`FLOAT`**: both operands widened by `F2D`, the double form, and `D2F` at the end, which is exact,
since the remainder of two floats is a float by the same quantum argument; the window is
`|x / y| < 2^53`, not the `2^24` a float lane would give. `pmod`'s `r + n` is a float addition in
Spark, computed here as the double addition narrowed, which is the binary32 sum: two roundings
through binary64 are one for an addition of binary32 operands (`53 >= 2 * 24 + 2`, Figueroa's
condition **[I]**, checked in 3.6).

### 2.6 The IR

- **`RoundToScale(ScaleMode mode, int scale, VarkaVectorIR child, ScaleOut out)`**, the child on the
  `DOUBLE` lane; `mode` is `HALF_UP`, `HALF_EVEN` or `DOWN`; `out` is `DOUBLE`, `FLOAT` (the
  round-to-odd narrowing) or `UNSCALED(p)` (the cast's long, with 2.4's arms). A node, by
  `VARKA-475.md` 4 rule 1 as 344 argues for `RoundToIntegral`: about 25 nodes per call spread
  through the shape key and the budget otherwise, and a reference that would check the construction
  against itself. `VarkaReference` evaluates it by Spark's calls - `BigDecimal.valueOf`, `setScale`,
  `doubleValue`, `floatValue`, the unscaled value - so the oracle is independent of the lowering.
  The lowering lives in `VarkaScaleRoundingLowering`, beside 344's `VarkaRoundingLowering`.
- **`RoundToIntegral` gains `FLOOR` to `DOUBLE`**, 344's `RINT` and its correction without the
  conversion.
- **`FloatRemainder(Kind kind, VarkaVectorIR dividend, VarkaVectorIR divisor, ZeroDivisor zero)`**,
  this row's node at the `FLOAT` and `DOUBLE` lanes, `Kind` being `REM` and `PMOD` and
  `ZeroDivisor` `NULL` (LEGACY, `try_mod`) or `FAIL` (ANSI), the same meanings as row 30's integral
  `IntDivide`, which has no floating kind. One node, since 2.5's proof is one sequence; the lowering
  is `VarkaFloatRemainderLowering`, and the reference evaluates it by Java's `%` and
  `MathUtils.pmod`.
- **Row 342's `Fma(a, b, c)`** at the floating lanes, `lanewise(FMA)`, evaluated by `Math.fma`,
  which this row uses and does not build; never made by rewriting a multiply and an add (342's
  no-contraction rule stands).

### 2.7 Modes and declines

Two new refusals, collected by `emitGuardCollect` as 347's slot check is, each with a status bit:
`ROUNDING_BAND` (a live non-integral lane in 2.2's band, or a lane in 2.4's) and `REMAINDER_BAND`
(2.5's window). Neither is narrowed by a choice arm unless `guardUnderArm` qualifies it. The zero
divisor is `FloatRemainder`'s, null under LEGACY and TRY and `FAIL` under ANSI; a literal non-zero
divisor drops it, as Spark does. No form of `round` has a mode. The cast's three modes differ only
in the overflow's disposal.

### 2.8 Compiler, reference, cost, census, warm-up

- `floatingDecline` admits `Round`, `BRound`, `Truncate`, `Remainder`, `Pmod` and the floating cast
  to a decimal by name; the arms join 344's `VarkaRoundingCompiler` (the rounding functions), 342's
  `VarkaFloatingCompiler` (the remainder) and 350's cast table (the cast). A non-literal or null
  scale, a negative scale and a scale above 22 decline with a reason naming the bound.
- The range analysis answers `UNKNOWN` off the int lane, so the nodes build their bounds directly.
- Cost: a feature per lowering, on row 352's awaiting-refit list; the budget weighs each by its
  operation count until the fit.
- The census cells: double `DIV` (342's), the reinterpretation (343's), and `FMA` already
  intrinsic; nothing new to run. The warm-up's double block gains the calls
  (`VarkaWarmupReachSuite`).
- At two lanes on x86 every double compare is the Java fallback, so a condition over these forms
  declines there by 503's rule when it lands, and a projection is measured against the row path and
  recorded in `width_audit.json` either way.

### 2.9 Exactness, and question 2

Every form here is exact: the rounding functions and the cast equal Spark's `BigDecimal` path to the
bit, and the remainder is exact by 2.5. None is under 2.7.1's contract, and the configuration
`spark.sql.codegen.varka.math.ulpContract.enabled` does not gate them. The milestone plan's question
2 is about a contracted value under one of them - `round(sin(x), 2)`, `CAST(exp(x) AS DECIMAL(10,
2))` or `sin(x) % 1.0` turns a one-step difference into a different digit - so these functions are
discrete consumers in its sense, as `floor` and `ceil` are in 344's 2.9. No contracted node exists
until 345, so this row is independent of the answer, and the check sits where 345 builds the
contract, keyed on the consumer's kind.

*30 October 2026, by the owner's word:* that configuration is an opt-in mode, off by default, since
every math function is now bit-exact to Spark by default (`VARKA-345.md` 2.1, 2.6); it does not gate
these functions in either state. Question 2 is answered (a) for that mode only: a discrete consumer
over a library node takes the function's exact form (`VARKA-345.md` 2.7), so this row stays
independent of it.

## 3. Tests and proofs

The oracle is Spark's own `Round`, `BRound`, `Truncate`, `Remainder`, `Pmod`, `TryMod` and `Cast`,
through the reference evaluator and the differential suites in the three modes; a batch declines
exactly when one of its live lanes is in a band or Spark raises on it.

1. **The cells**, a table test per mode against Spark's `eval`, the reference and the kernel: the
   row's vectors from spark-rapids - `9.95` at scale 1 gives 10.0, `3527.61953125` at scale 7 gives
   3527.6195313, `10.3` at scale 1 gives 103 as the cast's unscaled value - through the cast and
   `round`, and `9.95f` giving 9.9; `2.675`, `1.005` and `0.125` at scale 2 in the three modes;
   `-0.0`, `-0.004` and `-0.5`, giving `+0.0`; `truncate(0.7, 1)` giving 0.7; `2.5` and `-2.5` at
   scale 0; each band's ends and their neighbours at every scale, and an integral value inside
   each band, which answers itself; NaN payloads and both infinities
   passing through bit for bit. The remainder's: `5.0 % -inf` is 5.0, `inf % 2.0` NaN, exact
   multiples, `MIN_VALUE` and `MAX_VALUE` operands, the window's edge; `pmod(-0.0, 3.0)` and
   `pmod(-3.0, 3.0)` `-0.0`, `pmod(-1e-300, 1.0)` `0.0`, `pmod(-1.0, -3.0)` -1.0. The cast: the
   overflow at `10^(p - s)` and below it, NaN and infinities null in every mode.
2. **The subnormal probe**, the row's: `Double.MIN_VALUE`, `2 * MIN_VALUE` and `3 * MIN_VALUE`,
   whose shortest decimals take the length-2 arm of the specification, and a sweep of subnormals,
   at every scale and mode, answer `+0.0` on both engines, and `BigDecimal.valueOf` of the first
   prints two digits.
3. **The binary16 twin, exhausted, in the PR build.** The construction at binary16 (`10^s` exact
   for `s <= 4`, the twin's own `L_s`), over all 65536 bit patterns, at scales 0 to 4 and the three
   modes, against an oracle that selects the shortest decimal by brute force from
   `Double.toString`'s specification (item 0.1) - independent of the JDK's Schubfach - and rounds it
   with `BigDecimal`. A few seconds.
4. **The binary32 twin, exhausted, by hand.** The same at binary32 (`10^s` exact for `s <= 10`)
   over every float below its `L_s`, at scales 0 to 10 and the three modes, against
   `new BigDecimal(Float.toString(f))`; the shortest decimal computed once a float. Priced from the
   admission program's rate at a few minutes on the laptop's cores, an assumption the first run
   scores. No CI workflow runs a 2^32 sweep today, so it runs in the gate's sweep step by hand,
   with 344's.
5. **The binary64 structured sampling**, the admission program grown into
   `VarkaShortestRoundSweepSuite`: every binade from the subnormals to `K_s` and past it, the
   doubles nearest every tie and multiple and three ulps either side, decimals of `s + 1` digits, at
   every scale and mode, through the kernel on every host form; a seeded tenth in the PR build, the
   whole in the gate's sweep step.
6. **SMT**, by `dev/varka_prove.sh` under Z3 and cvc5, in the theory of floating point: 344's
   `fp_round.smt2` gains `FLOOR` to `DOUBLE`; a new `fp_remainder.smt2` states that 2.5's
   construction equals Java's remainder, written as `fp.rem` corrected by `ay` toward `x`'s sign,
   for every pair inside the window, at binary16 and binary32 and attempted at binary64 under a
   ten-minute bound, the time priced from the spiked form; and that `RN32(RN64(a + b))` equals
   `RN32(a + b)` for binary32 `a` and `b`, else the binary16-through-binary32 analog exhausted.
   Steps 1, 4 and 5 of 2.3's argument - the one-division membership, the monotone side and the
   candidates - are stated at binary16 and binary32 with a real-valued boundary; step 2, the
   selection of the shortest decimal, has no SMT form here and rests on the twins.
7. **The remainder's sweeps**: every exponent pair with `ex - ey` from -3 to 60, seeded mantissas,
   exact multiples and the special values, against Java's `%`, `BigDecimal`'s exact remainder and
   SLEEF's `xfmod` ported to the test tree as the row's second oracle; `pmod`
   against `MathUtils.pmod`; the `FLOAT` forms over every float exponent pair. A tenth in the PR
   build.
8. **Coverage rows**, a family "shortest decimal": `round(x, 0)`, `round(x, 2)`, `bround(x, 2)`,
   `truncate(x, 2)`, `round(f, 1)`, `x % y`, `x % 2.5`, `mod(x, y)`, `pmod(x, y)`, `try_mod(x, y)`,
   `f % g`, `CAST(x AS DECIMAL(10, 2))`, `CAST(f AS DECIMAL(9, 1))`, in the three modes, each naming
   its verification for row 490's check.
9. **The fuzzers**: the floating IR sequence draws the three nodes; the Spark fuzzer's grammar gains
   the functions at drawn scales 0 to 22.
10. **No folding**: `dev/varka_emit.sh --asm` over `round(x, 2)` shows the `+ 0.0` and both
    divisions kept, and a `vfmadd` only in the forms that emit `Fma`.
11. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order; the census's reason column carries
    each form's band per scale (2.2's table, 2.4's per `(p, s)`, 2.5's window), then the PR build
    and the option matrix.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code**, in the families' classes:
`round(x, 2)`, `bround(x, 2)`, `truncate(x, 2)`, `round(x, 0)` and `round(f, 1)` join 344's
`VarkaRoundingBaselineBenchmark`; `x % y`, `x % 2.5`, `pmod(x, y)` and `f % g` join
`VarkaFloatingBaselineBenchmark`, as does `CAST(x AS DECIMAL(10, 2))`; with and without nulls, the
values drawn below the guards so the build's numbers measure the fused path, on the EPYC 7763 at 256
and 128 bits.

Predictions, registered before the build:

1. **No existing shape's bytes move**; the `option_arms` digests move with the new shapes only.
2. **The rounding functions and the cast at 256 bits**: at least ten times faster than the row path,
   which builds a `BigDecimal` from the shortest digits per value.
3. **The remainder at 256 bits**: at least three times faster than the row path's `%`.
4. **At 128 bits on x86**: the rounding functions and the cast still faster than the row path, the
   remainder not; recorded in `width_audit.json` either way.
5. **Test time**: the PR build's share under a minute, at the admission program's rate (about 100
   million checks in under half a minute on 24 threads); the sweep step's binary32 twin a few
   minutes.

## 5. Order, files and size

1. **The baseline** (4), its own pull request.
2. **346a, the remainder**, after 342 (its division and its `Fma`) and 344a: `FLOOR` to `DOUBLE`,
   `FloatRemainder`, `%`, `mod`, `try_mod`, `pmod`, `REMAINDER_BAND`, the `xfmod` port, tests
   3.6 and 3.7, their coverage rows; the `FLOAT` forms after 343a's conversions.
3. **346b, the rounding functions**, after 344a and 343a: `RoundToScale` with `DOUBLE` and `FLOAT`
   outputs, `ROUNDING_BAND`, tests 3.1 to 3.5.
4. **346c, the cast**, after 350, 157 and 343b: `UNSCALED(p)`, the integer arm, the overflow and the
   NaN nulls, its cells and coverage rows.
5. **The measurement** against the baseline at both widths, each part against its sections.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `RoundToScale`, `FloatRemainder`, double `FLOOR` |
| `varka/VarkaScaleRoundingLowering.java`, `varka/VarkaFloatRemainderLowering.java` | 2.3 to 2.5 |
| `varka/VarkaVectorWalk.java`, `varka/Slots.java`, `varka/Analysis.java` | emission, refusals |
| `VarkaRoundingCompiler.java`, `VarkaFloatingCompiler.java`, 350's cast table | the arms |
| `VarkaNodeCompiler.java` | `floatingDecline`'s names |
| `varka/VarkaReference.java` | the three nodes by Spark's calls |
| `varka/VarkaEmitBudget.java`, `VarkaEmitCostRegister.java`, the warm-up | weights, features |
| `sql/varka/proofs/fp_remainder.smt2`, `fp_round.smt2`, `VarkaProofFiles.java` | 3.6 |
| tests | cells, twins, sweeps, the `xfmod` port, coverage rows, fuzz grammar |
| benchmark | the baseline sections and their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `census.tsv`, `casts.tsv`, `casts_fused.tsv` |

**Size: large, against the row's medium** (8, question 1). The baseline half a day; 346a about three
days (the node, the lowering, the SMT file, the port); 346b about four (the node and its three
outputs, the twins and the sweep); 346c about two. About ten days, in three pull requests after the
baseline.

## 6. What it unlocks in the census

- **`%`, `mod`, `try_mod`, `pmod`** over `float` and `double` in 346a: 8 pairs.
- **`round`, `bround`, `truncate`** over `float` and `double` in 346b: 6 pairs.
- **`float` and `double` to `dec`** in 346c: 2 of the cast matrix's pairs, within precision 18;
  above it they wait for 364.

**This row moves no pair.** Every form here has a declined band, and the milestone plan's 1.1
item 2 counts a pair with one as unfused, its band the reason in the census - the rule 359 and
348 and 349 apply. The census's reason column carries each band (3.11).

## 7. Out of this row, and declines that stay

Out: Schubfach's selection in lanes, which would fuse the band (`m9/SCOPE.md` 97); Ryu's printf form
(the milestone plan's section 9); the integral and decimal forms of every function here (344, 30,
351, 363); the cast above precision 18 (364); a remainder in a float lane, whose window would be
`2^24`; a scale above 22, where `10^s` is not exact, though values from `K_s` up could keep `d`
there with no division; `CEIL` and `TRUNC` to `DOUBLE`, which nothing reads.

Declines that stay: a batch with a live lane in its band, which the row engine computes - a gap,
each with its scope item:

- the rounding functions, a non-integral value in `[L_s, K_s)` at `s >= 1`, and the cast, a
  non-integral value in `[L_s, 2^52)` or any value in `[2^53, 10^(p - s))`: `m9/SCOPE.md` item 97,
  Schubfach's selection half in lanes;
- the remainder, `|x / y| >= 2^53` with `x` finite: no scope item names it yet (8, flag 4).

The other declines that stay: a negative,
non-literal or null scale or one above 22; an ANSI batch with a zero divisor or a cast overflow,
which the row engine raises; at two lanes on x86, the conditions 503's rule declines.

## 8. Open points for the lead

Settled by the lead on 30 October 2026, kept as the record: the FMA node is row 342's, the earliest
wave owning a shared node; the rounding-interval guard is not built (What differs); the band
declines to the row engine, a decline that stays with its scope item (7); the census follows the
milestone plan's 1.1 item 2 as it reads, so this row moves no pair (6) - the other reading, a pair
counting as fused with its band in the reason column, would count 16; the remainder's second oracle
is SLEEF's `xfmod`, ported to the test tree as the row says (`READING_SLEEF.md` 2), with its Boost
Software License notice, beside `BigDecimal`'s exact remainder; and this row owns the floating
remainder node, row 30's plan naming none.

**A question for the owner:**

1. **The size.** (a) Three pull requests as section 5 has them, after the baseline, each landing
   what its dependencies allow and the row **Done in part** between them, as 342 and 343 land; (b)
   one pull request after 350, 157 and 343b. *Recommended: (a)*: the remainder and the rounding
   functions need nothing of 350's. Either way the row is large, about ten days, against its medium.

**Flags, the owner's text left as written:**

1. **342's plan has no FMA node.** `VARKA-342.md` mentions FMA only to say C2 contracts none; this
   row's 346a needs `Fma` from it, so 342's plan gains the node before 346a starts.
2. **The `9.95f` vector.** The row's done-when calls the rapids vectors "float-to-decimal vectors"
   with "`9.95` at scale 1 gives `10.0`": that holds for a `DOUBLE` 9.95; a `FLOAT` `9.95f` widens
   to 9.949999809265137 and gives 9.9 (item 0.3), so the named tests take the vectors as doubles and
   add the `FLOAT` answer as its own cell.
3. **Schubfach's R5.** The milestone plan's 2.7 cites it for "the shortest decimal is `T` when
   `ulp(d) < 10^-(s + 1)`"; the paper is not in the repository and R5 was not checked. The citation
   here is the JDK's specification of `Double.toString` (`Double.java:513-530`), which is what Spark
   calls (2.3, step 2).
4. **2.7's keep bound, and the remainder's band.** 2.7's keep bound `10^(16 - s)` is `2^52` at scale
   0 here (2.2), where no band remains. And the remainder's band, `|x / y| >= 2^53`, has no scope
   item in `m9/SCOPE.md`: SLEEF's multi-pass loop (`READING_SLEEF.md` 4) is the route a scope item
   would name; the item is the owner's to add.

## 9. Done when

Row 346's text holds, with the corrections above: each form of section 1 fuses with coverage rows in
the three modes, or declines its recorded band, and the census carries the band; the cells of 3.1,
the rapids vectors among them, pass; the subnormal probe passes; the binary16 twin is exhausted in
the PR build and the binary32 twin in the sweep step; the binary64 sampling passes on every host
form; `fp_remainder.smt2` and `FLOOR`'s claim are proven at binary16 and binary32 (binary64 if it
decides); the remainder agrees with Java's `%`, the exact remainder and the ported `xfmod`; no
existing shape's bytes move; the PR build and the option matrix pass; the measurement is committed
against the baseline at both widths with its predictions scored.

## 10. Outcome

(Written when the work lands.)
