# VARKA-346: The constructions behind the former hard entries

***Changed on 28 November 2026.** Sections 11 to 13 are new: what is left of the row, cut into
parts, with a build sheet per part (11.4). Sections 0 to 10 are the plan and its record, kept
word for word; where section 11 differs it wins, and section 13 lists each difference.*

***Fix round of 28 November 2026** (review 1 of the plans of rows 350, 346 and 597b). The plan
lands alone first (11.4's head); 346b2's premises allow 346c merged first; 346a's cost entry,
346m's script header and its new step 8 (the scope item of question 3) are written out; the
status-bit collisions in two m9 plans are listed for the lead (13 item 8); the owner's answers
are in section 12. Every changed passage is marked "fix round".*

***E1 rework of 28 November 2026.** The owner's decision E1 (a) moves claim C of the old part
346p to part 346p2, January 2027, no gate before then (section 12, question 5; question 4 is
moot); claims A, B and D are part 346p1. The lead split 346b1 into 346b1a (the node, its
lowering and its band's refusal) and 346b1b (the compiler arm), both Sonnet-class once the
lead's registry run on 346b1a passes; every later part waits on the half it reads (11.2).
Every changed passage is marked "E1 rework".*

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
  select the shortest decimal in a lane today, which is `m10/SCOPE.md` item 97; an in-kernel scalar
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
lanes (`m10/SCOPE.md` 97).

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

Out: Schubfach's selection in lanes, which would fuse the band (`m10/SCOPE.md` 97); Ryu's printf form
(the milestone plan's section 9); the integral and decimal forms of every function here (344, 30,
351, 363); the cast above precision 18 (364); a remainder in a float lane, whose window would be
`2^24`; a scale above 22, where `10^s` is not exact, though values from `K_s` up could keep `d`
there with no division; `CEIL` and `TRUNC` to `DOUBLE`, which nothing reads.

Declines that stay: a batch with a live lane in its band, which the row engine computes - a gap,
each with its scope item:

- the rounding functions, a non-integral value in `[L_s, K_s)` at `s >= 1`, and the cast, a
  non-integral value in `[L_s, 2^52)` or any value in `[2^53, 10^(p - s))`: `m10/SCOPE.md` item 97,
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
   item in `m10/SCOPE.md`: SLEEF's multi-pass loop (`READING_SLEEF.md` 4) is the route a scope item
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

### Decisions taken while the owner was away (20 November 2026)

The owner was away and no lead could be asked; each decision below is the option the plan, the
`sql/varka/AGENTS.md` rules and the plan's own recommendations favour, with its reason.

1. **The order inside the row is baseline, 346b's `DOUBLE` output, then the rest.** Section 5 puts
   346a (the remainder) before 346b, but 346a needs row 342's `Fma` node, which no branch builds
   (flag 1 of section 8 is still open: `VARKA-342.md` has no FMA node), and 346c needs row 350,
   which is Planned. 346b's `DOUBLE` output needs only what master has - 344a1's `2^52`
   construction, 342's floating division, 343a's conversions - so it is built next; its `FLOAT`
   output also needs `Fma` (the round-to-odd narrowing of 2.3) and declines by name until then.
   Question 1 is taken as recommended, (a): the row lands in parts, Done in part between them.
2. **The baseline's remainder sections divide by a new column, `w`.** The floating baseline's `y`
   and `g` are exactly zero on one row in about 100000 (`(id * 7) % 100019 = 49500`), so `x % y`
   raises under ANSI, Spark's default, and would end the run. `13 w` is an integer plus one half, so `w` is
   never zero, and every quotient stays below `2^18`; the existing columns and sections are unchanged, so the
   committed results stay comparable, and the new sections run after them.
3. **`ROUNDING_BAND` takes status bit 8, and the evaluator's three bits move up by one.** The
   kernels' bits 0 to 7 were all taken and `VarkaKernelRunner`'s own started at bit 8, with
   `VarkaEvaluatorComponentsSuite` holding every kernel bit below every evaluator bit. The
   evaluator's bits are never returned by a kernel, so moving them to 9, 10 and 11 changes no
   emitted byte; reusing `VALUE_RANGE`, as the bounded `L2D` does, would have kept the bits but
   lost the band's own name in the fallback counts, which 2.7 asks for.

### The baseline, built 20 November 2026

`VarkaRoundingBaselineBenchmark` gains `round(x, 2)`, `bround(x, 2)`, `truncate(x, 2)`,
`round(x, 0)` and `round(f, 1)` over a new `FLOAT` column `f` (`x / 1000` narrowed), and
`VarkaFloatingBaselineBenchmark` gains `x % w`, `x % 2.5D`, `pmod(x, w)`, `CAST(x AS DECIMAL(10,
2))` and `f % wf`, each at both null densities, all after the existing sections and none listed as
fusing. Every `x` is below `L_2 = 2^43` and every `|x / w|` below `2^18`, so the sections time the
fused paths once they land rather than a declined band. The queries suites check the new columns'
regions and that both sessions answer every section alike (50 and 30 sections). The runner results
at 256 and 128 bits are owed: no benchmark runs in this window.

## 11. What is left, cut into parts (28 November 2026)

*New on 28 November 2026. Code facts were read on master `6dafb8bd2f6` and on the local branches
named below, in `/home/max/proj/vecbricks-varka`, with no JVM. Short names: `C` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`, `V` is `C/varka`,
`CT` is the same path as `C` under `src/test/java`.*

### 11.1 The state on 28 November 2026

**On master**, each with its command, run from the repository root:

| what | command | prints |
|---|---|---|
| the baseline's ten sections (#1586) | `git log --oneline -1 f7b823978b6` | `[VARKA-346] Benchmark round over a double and the floating remainder on the row path (#1586)` |
| its runner results at both widths (#1597) | `git log --oneline -1 d49480da0fe` | `[VARKA] Commit the EPYC results of the math, rounding and floating baselines (#1597)` |
| row 342's `Fma` node | `grep -c 'record Fma(VarkaVectorIR a, VarkaVectorIR b, VarkaVectorIR c, int hash)' V/VarkaVectorIR.java` | `1` |
| no node of this row | `grep -rl 'RoundToScale\|FloatRemainder\|ROUNDING_BAND\|REMAINDER_BAND' sql/catalyst/src sql/core/src \| wc -l` | `0` |
| the remainder declines by name | `grep -c 'a floating remainder (row 346)' C/VarkaNodeCompiler.java` | `1` |
| the kernels' status bits 0 to 7 are taken | `grep -c 'int STATUS_DIVIDE_BY_ZERO = 1 << 7;' V/VarkaFusedKernel.java` | `1` |
| the runner's bits start at 8 | `grep -c 'STATUS_INPUT_BOUND = 1 << 8;' sql/core/src/main/java/org/apache/spark/sql/execution/varka/VarkaKernelRunner.java` | `1` |

So on master the row has its baseline and its runner numbers and no kernel. Section 10 on
master records only the baseline; the records of the parts built on 20 November 2026 exist only
in the plan file's versions on the branches below (`git show <branch>:sql/varka/plans/m8/
VARKA-346.md`), and each part's pull request brings its record into section 10.

**On local branches, built on 20 and 21 November 2026 while the owner was away, never pushed.**
All predate row 489's last part (#1696, 25 November 2026), which replaced the per-node switches
by a `props()` descriptor on each IR record.

| branch | commit | holds | counted |
|---|---|---|---|
| `varka-346b3-sweeps-r` (rebased 21 November) | `0345dd29a53` | 346b: `RoundToScale(mode, scale, child, DOUBLE)`, `VarkaScaleRoundingLowering` (311 lines), the refusal `ROUNDING_BAND` at status bit 8 with the runner's three bits moved up by one, the compiler arm in `VarkaRoundingCompiler`, `VarkaEmitterScaleRoundingSuite` (273 lines), four coverage rows in a family "Shortest decimal" | 945 added, 59 removed, 40 files |
| the same | `e6e639c4056` | 346b2: the bounds-table test, the named vectors, the subnormal probe, `VarkaShortestRoundTwinSuite` (the binary16 twin, exhausted), the fallback reason's test | 349 added, tests only |
| the same | `299c082ccbc` | 346b3: the binary32 twin behind `-Dvarka.sweep=true`, `VarkaShortestRoundSweepSuite` (the binary64 sampling), both registered in `sql/varka/ci/sweeps.tsv` | 240 added, tests only |
| `varka-346a-remainder` | `bb377a93cbf` | 346a: `FloatRemainder(dividend, divisor)` at the double lane, `VarkaFloatRemainderLowering` (190 lines), `REMAINDER_BAND` at status bit 9, `%`, `mod`, `try_mod` and `pmod` over `DOUBLE` and `FLOAT` in `VarkaFloatingCompiler`, `VarkaEmitterFloatRemainderSuite` (312 lines), `VarkaFloatRemainderQuerySuite` (119 lines), five coverage rows "Floating remainder" | 937 added, 31 removed, 35 files |
| `varka-346c-decimal-cast` | `9ca2d818541`, `1a7e5cd2ca6` | 346c: `RoundToScale` gains the output `UNSCALED` and a precision, answering on the long lane; the cast of `DOUBLE` and `FLOAT` into `DECIMAL(p, s)`, `p <= 18`, as `GuardedRange(RoundToScale(HALF_UP, s, d, UNSCALED, p), +-(10^p - 1), mode)` in `VarkaDecimalCastCompiler`; `VarkaEmitterFloatDecimalCastSuite` (285 lines), `VarkaFloatDecimalCastQuerySuite` (157 lines) | 816 and 14 added |
| `varka-346-hard-entries`, `varka-346b-double-round`, `varka-346b2-twins`, `varka-346b3-sweeps` | | the baseline (merged) and the unrebased 346b stack | nothing to take |

**What each branch's own record says was tested** (20 November 2026, the laptop, AVX-512):
346b's cells suite and the twin passed in one targeted run (7 tests); 346a's emitter suite
passed 5 of 5 after two test fixes, its query suite never ran; 346c's emitter suite passed 3 of
3 after a fix to `VarkaVectorWalk.guardLane`, its query suite never ran. No part ran the
regeneration, the registry suites or the option matrix.

**Not built on any branch:** the `FLOAT` output of `round`, `bround` and `truncate` (the
round-to-odd narrowing); `fp_remainder.smt2`; the SMT statement of 2.3's steps; the proof of
346c's `2^52` conversion form; SLEEF's `xfmod` port; the fuzz grammars' draws; the census's
band column; the measurement.

### 11.2 The parts

| part | what | counted lines | builder | starts from | waits on |
|---|---|---|---|---|---|
| 346b1a | the node `RoundToScale`, its `props()`, its lowering, `ROUNDING_BAND` at bit 8 and the runner's bits moved, the registry fixtures, the node's suite built by hand; no compiler arm | about 850 (11.4 has the size stop rule) | Sonnet-class once **the lead's registry run** on it has passed (11.4, 346b1a's sheet) | `0345dd29a53`, re-ported onto `props()` | the lead's registry run |
| 346b1b | the compiler arm of `round`, `bround`, `truncate` over a `DOUBLE`, the coverage family, the benchmark flags, the session tests | about 180 and the regenerated rows | Sonnet-class, on 346b1a's condition | the compiler and test hunks of `0345dd29a53` | 346b1a |
| 346b2 | the probes, the binary16 twin, the binary32 twin, the binary64 sampling | about 600 (350 and 243 on the branch) | Sonnet-class | `e6e639c4056` and `299c082ccbc`, cherry-picked | 346b1b |
| 346a | the floating remainder and `pmod` over `DOUBLE` and `FLOAT`: the node, `REMAINDER_BAND` | about 930 (968 on the branch); split off the query suite if it passes the cap | Sonnet-class, with 346b1a's merged pull request as its worked example (and 346b1b's, if merged) | `bb377a93cbf`, re-ported | 346b1a |
| 346c | the cast of `DOUBLE` and `FLOAT` into a decimal of 18 digits or fewer | about 820 (858 on the branch) | Sonnet-class | `9ca2d818541` and `1a7e5cd2ca6`, re-ported | 346b1a, and row 350's part 350a1b |
| 346p1 | the proof files' claims A, B and D: the `2^52` conversion, the float sum, the stepped floor (each decided here under both solvers or one) | about 150 to 250 | Sonnet-class | master | 346a, 346c |
| 346p2 | claim C, the remainder's construction in SMT | about 50 to 150 | **January 2027, full strength; not a gate before then** (the owner's E1, section 12) | master | 346p1 |
| 346m | the measurement at both widths, the census's band column, the row marked | about 150, results apart | Sonnet-class | master | 346b1a, 346b1b, 346b2, 346a, 346c, 346p1 (not 346p2) |

*[E1 rework, 28 November 2026.]* The table above replaces the parts 346b1 and 346p of the first
pass: 346b1 is split in two by the lead (11.4, 346b1a's sheet, says why the refusal sits in the
first half), and 346p's claim C moves to January 2027 by the owner's decision E1 (a) of 28
November 2026 (section 12, question 5). Nothing waits on 346p2. Until it lands, the remainder
is held by 346a's differential tests (its five emitter tests against Java's `%` and the
exponent-pair sweep against `BigDecimal`'s exact remainder, 346a's sheet) and by the floating
coverage rows 346a adds, as 346p's decision rule already provided for an undecided claim C.

**Why 346c waits on 350a1b** (`PLAN.md` 12.5, "350 before 346c"): both edit one arm of
`VarkaDecimalCompiler.cast`, the sources that are not a decimal. Part 350a1b of `VARKA-350.md`
11.5 leaves a floating source declining there with the text "row 346's"; 346c replaces that half.

**What makes each full-strength part hard.** *[E1 rework, 28 November 2026: the list is now
one item before January and one in January.]*

- Before January, the only full-strength work: **the lead's registry run on 346b1a** (11.4,
  346b1a's sheet, "The lead's run first"). A new IR record written before row 489 and re-ported
  after it, across about forty files, with a new refusal that takes status bit 8 and moves the
  runner's three bits; a missed switch fails at compile time, but a wrong `props()` answer
  fails only in a registry suite, and every later part of this row copies what it settles. The
  lead applies the sheet once and runs the eleven registry suites; a failure is fixed in the
  sheet's text before any builder starts.
- January 2027, **346p2**: the remainder's claim in SMT-LIB's floating-point theory, which
  neither pinned solver decides as stated (11.4, 346p2's sheet): it must be split into lemmas
  that converge, which a builder cannot be told how to find. The other three claims (346p1)
  decided here in seconds.

### 11.3 Decisions taken here, each with its evidence

1. **The order of the parts** (first written as 346b1, 346b2, 346a, 346c, 346p, 346m). Section 5
   put the remainder first, for
   want of the `Fma` node; the node is on master now, and the branches stack the other way
   (346a's `REMAINDER_BAND` is bit 9, above 346b's bit 8). Keeping the branches' order keeps
   their status bits and their file hunks. *[E1 rework, 28 November 2026.]* Now: the lead's
   registry run, then 346b1a; then 346b1b, 346a and 346c in any order once 346b1a is merged
   (346c also after 350a1b); 346b2 after 346b1b; 346p1 after 346a and 346c; 346m last. 346p2
   is January 2027's and nothing waits on it. Where two of 346b1b, 346a and 346c edit one
   place, each sheet says what the second one to land finds there.
2. **`ROUNDING_BAND` is status bit 8 and `REMAINDER_BAND` bit 9; the runner's three bits end at
   10, 11 and 12.** Section 10's decision 3, and 346a's record on its branch. On master the
   kernels hold bits 0 to 7 and `VarkaKernelRunner` bits 8, 9 and 10 (11.1). The runner's bits
   are never returned by a kernel, so the move changes no emitted byte.
3. **The `FLOAT` output of `round`, `bround` and `truncate` is not built in this milestone and
   declines by name.** It needs a division of two doubles rounded once to binary32, the
   round-to-odd form over `Fma`, which is a lowering that rounds and so owes a proof file; and
   the shortcut "keep `f` where `|f| * 10^s >= 2^24`" has an unproven edge at a power of two,
   where the spacing below `f` halves. It goes to `m10/SCOPE.md` item 140 (question 1 of section
   12). The remainder's `FLOAT` forms are built: they widen, take the double remainder and
   narrow exactly.
4. **The row moves no census pair** (section 6): every form has a declined band. The census's
   reason column carries each band; 346m writes it.
5. **The constructions were re-checked on exact arithmetic here**, against Python's `decimal`
   and `math.fmod`: `scratch-346/verify_346.py`, whose last line is
   `verify_346: 1000121 checks, 0 failures`. It holds 2.2's table of `L_s` for scales 1 to 22,
   2.3's three formulas with the keep and integer arms at scales 0 to 22 in both signs (ties and
   their neighbours among the values), the named cells (`2.675`, `9.95`, `0.125`, `1.005`,
   `-0.004`, `-0.5`, `3527.61953125`), the cast's unscaled values (100, 103, 35276195313), and
   2.5's remainder over 200000 exponent pairs with exact multiples. Python's `repr` is the
   shortest decimal Java prints for every normal double; the two differ on subnormals, which
   round to zero at every scale here.
6. **346c's cast as an output root fuses**, against its branch's decision 8: since #1628
   `VarkaDecimalCompiler.output` writes every 64-bit decimal value, so the cast needs no reading
   through `BIGINT`. Its tests keep the `BIGINT` forms and add the output.
7. **At two long lanes on x86**: the remainder and the rounding functions read no decimal column
   and are not row 597's. 346c's cast sits in the decimal cast family, whose arms test
   `sink.declinesTwoLaneChecks()` first and decline with `VarkaDecimalCastCompiler.
   TWO_LANE_REASON`; 346c does the same. *[Fix round, 28 November 2026.]* The owner answered
   `VARKA-597.md` 11.7 question 1 "remove": part 597b deletes `TWO_LANE_REASON` and writes, at
   the head of `DCC.intoDecimal`, a decline for every source that is not a decimal, by
   `VarkaDecimalCompiler.TWO_LANE_READ_REASON` (`VARKA-597.md` 11.5 step 3b). So 346c writes no
   two-lane code in either order: before 597b its floating source declines by
   `TWO_LANE_REASON`, after 597b by `TWO_LANE_READ_REASON`, and the cast declines at two long
   lanes on x86 either way. A test of 346c that names the reason reads
   `grep -c 'TWO_LANE_READ_REASON' C/VarkaDecimalCompiler.java` first: `0`, it names
   `TWO_LANE_REASON`; `1`, the read reason.

### 11.4 The build sheets

**Landing the plan.** *[Fix round, 28 November 2026, finding 2 of review 1.]*
`dev/varka_msg_check.py` counts a plan file's lines, and this file is over 800 lines longer than
master's (625), so it cannot ride in a part's pull request. Its new text goes to master first,
in its own plan-only pull request titled "Plan: re-plan row 346's last parts for a Sonnet-class
builder"; plan-only pull requests are exempt from the 1000-line cap, and the lead opens it.
Each part's branch starts from a master that has it, and a part's pull request adds only the
record its sheet names (a subsection of section 10).

**How a branch written before row 489 is re-ported** (`VARKA-489.md`, "Adding a node, after
489c"): the record's answers move from the deleted switches (`weightOf`, `kindOf`, `admits`,
`restrictedOperands`, `inputOrdinal`, `longNarrowReader`, `isNarrowRing`, `ownerOf`, `pureOf`,
`planWordRef`, `propagate`) into one `props()` on the record; the arms that stay are the
structural switches of `VarkaVectorIR` (`laneType`, `childrenOf`, `withChildren`, both canonical
forms), `Analysis.collectRefusals` and `Analysis.analyze`, `Slots.visit`, the walk in
`VarkaVectorWalk`, `VarkaKernelPlan.longNarrowRole`, `VarkaRangeAnalysis`, and in the tests
`VarkaIrGrammar.boundsOf`, `VarkaReference` and `VarkaGuardSpecs`; then the cost feature's price
in `VarkaEmitCostTable` (for a floating node, an entry in `VarkaEmitCostSuite`'s
`awaitingRefit` list instead, as `Fma` and `RoundToIntegral` have), a row in
`VarkaEmitterBudgetSuite`'s register, and the per-node
fixtures (`VarkaIrGrammar`, `VarkaLaneTypeSuite`, `VarkaReproductionSuite`, `VarkaSmtNodes`,
`VarkaRangeAnalysisSuite.admissible`). `build/sbt catalyst/Test/compile` names every switch
that lacks the record, and R1 of `dev/varka_push_check.py` every fixture.

#### 346b1a: the node `RoundToScale`, its lowering and its band's refusal (finished sheet)

*[E1 rework, 28 November 2026: the lead's split of the old sheet 346b1 (about 957 counted
lines) into 346b1a and 346b1b. The old sheet's B0, record text, arms table and tests are divided
between the two; what changed beyond the division is said where it is: the refusal's place, the
hand-built `Case.of`, the registry list and the lead's run.]*

**Builder: Sonnet-class, on one condition: the lead's registry run below has passed.** Until
the line "Registry run on 346b1a" below reads "passed", no builder starts 346b1a or 346b1b.
Branch `varka-346b1a-round-node` from master. Source: `0345dd29a53` on `varka-346b3-sweeps-r`;
read it with `git show 0345dd29a53 -- <path>`. Short names beside `C`, `V` and `CT`: `VT` is
`CT/varka`, `ST` is `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/
codegen/varka`, `E` is `sql/core/src/main/java/org/apache/spark/sql/execution/varka`, `B` is
`sql/core/src/test/java/org/apache/spark/sql/execution/benchmark`.

**What this part is.** The record and its `props()`, every structural arm of the IR, the
lowering, the band's refusal `ROUNDING_BAND` at kernel status bit 8 with the runner's three
bits moved up by one, the registry fixtures, and the node's suite with the node built by hand.
No compiler arm admits the node: `round`, `bround` and `truncate` over a double still decline by
b8's text, so no compiled plan, coverage row, census cell or benchmark section changes.

**Why the refusal and the bits are here, not in 346b1b** (the one change from the lead's split
of 28 November; facts on `6dafb8bd2f6`). The lowering calls `emitGuardCollect` at every scale
with a band, which is every scale from 1 to 22 (`hasBand(s)` is false at 0 alone), and
`VarkaVectorWalk.guardAcc` throws "a guard collect with no mask planned for its reason" for a
guarded node with no refusal (a1 below). So a node with no refusal could be emitted at scale 0
only, and the guard, its scratch slot and its word would go untested until 346b1b. Bit 8 is
the runner's `STATUS_INPUT_BOUND` today (b5), and `VarkaEvaluatorComponentsSuite` holds every
kernel bit below every evaluator bit, so taking bit 8 moves the runner's bits in the same part.

**The lead's run first.** The lead applies this sheet once, on a local branch
`varka-346b1a-lead-run` that is never pushed, and runs proving steps 1 to 5 below. Step 3 is
the rule of `BRIEF-M8.md` and `sql/varka/plans/m8/VARKA-563.md`: a part that adds an IR record
runs every registry suite, the list `dev/varka_push_check.py` computes (`registry_suites`: the
test files matching `REGISTRY_PATTERN` plus `REGISTRY_NAMED`, each file's `class ...Suite`).
On `6dafb8bd2f6` that list is eleven suites, all in catalyst:

    build/sbt 'catalyst/testOnly *VarkaEmitCostSuite *VarkaEmitterBudgetSuite
      *VarkaFuzzTypesSuite *VarkaIrFuzzSuite *VarkaLaneTypeSuite *VarkaNodePropsSuite
      *VarkaRangeAnalysisSuite *VarkaReproductionSuite *VarkaSmtNodesSuite
      *VarkaVectorIRSharingSuite *VarkaWithChildrenSuite'

The same list, with no JVM, from the branch: `python3 dev/varka_push_check.py --dry-run --base
master` prints `R1: the IR records RoundToScale changed; the registry suites run` and the
suites it would run; where its list and the eleven differ (master moved), its list wins. Or
in one call under the heavy lock: `python3 dev/varka_push_check.py --base master`, exit 0.

- **All pass:** the lead replaces the line below by "Registry run on 346b1a: passed on <date>,
  master `<sha>`, branch `varka-346b1a-lead-run` at `<sha>`." in a plan-only pull request (or
  in the plan's landing pull request, 11.4's head). Both halves are then Sonnet-class. A
  builder may compare its branch against the lead's with `git diff varka-346b1a-lead-run HEAD
  -- sql/catalyst/src/main`: an empty diff of the main code is expected.
- **One fails:** the lead fixes the port text of this sheet (the `props()` text, an arm's
  text, a fixture line) and runs again, before any builder starts; the line stays "not yet
  run" until a run passes.

Registry run on 346b1a: not yet run.

**B0, the premises** (run on `6dafb8bd2f6` from the repository root; a different count means
master moved: stop and re-read the anchor before editing). Numbered as in the old sheet; b8,
b9, b13, b16 and b17 are 346b1b's.

| # | premise | command | prints |
|---|---|---|---|
| g0 | the lead's run passed (the builder's premise; the lead's own run skips it) | `grep -c 'Registry run on 346b1a: passed' sql/varka/plans/m8/VARKA-346.md` | `1` |
| b1 | every record answers `props()` | `grep -c 'public VarkaNodeProps props()' V/VarkaVectorIR.java` | `62` |
| b2 | the anchor for the two enums | `grep -c '  enum RoundMode { RINT }' V/VarkaVectorIR.java` | `1` |
| b3 | nothing of this row in the tree | `grep -rl 'RoundToScale\|ScaleRoundingLowering\|ROUNDING_BAND' sql/catalyst/src sql/core/src \| wc -l` | `0` |
| b4 | the kernels' last bit is 7 | `grep -c 'int STATUS_DIVIDE_BY_ZERO = 1 << 7;' V/VarkaFusedKernel.java` | `1` |
| b5 | the runner's bits are 8, 9, 10 | `grep -c 'public static final int STATUS_[A-Z_]* = 1 << \(8\|9\|10\);' E/VarkaKernelRunner.java` | `3` |
| b6 | the last refusal ends the enum | `grep -c 'DECIMAL_SLOT(Family.REARMED, true, VarkaFusedKernel.STATUS_DECIMAL_SLOT);' V/Analysis.java` | `1` |
| b7 | the mechanism switch's last arm | `grep -c 'case DECIMAL_SLOT -> GUARD_DECIMAL_SLOT;' V/VarkaEmitMechanism.java` | `1` |
| b10 | the lowering's `rint` call | `grep -c 'static void emitRoundToNearest(CodeBuilder cb)' V/VarkaRoundingLowering.java` | `1` |
| b11 | the scratch map the node parks in | `grep -c 'final Map<VarkaVectorIR, int\[\]> roundTmp' V/Slots.java` | `1` |
| b12 | the awaiting-refit list's line | `grep -c '"WidenDecimal", "InterleaveDecimal", "MathUnary", "Fma", "RoundToIntegral",' ST/VarkaEmitCostSuite.scala` | `1` |
| b14 | the status names' last line | `grep -c '"decimal-slot", "divide-by-zero", "input-bound", "derived-input", "forced"};' E/VarkaFallbackAccounting.java` | `1` |
| b15 | the guard's lane, unchanged here | `grep -c 'private static Lane guardLane(VarkaVectorIR node, Analysis analysis)' V/VarkaVectorWalk.java` | `1` |
| a1 | a guard needs its refusal | `grep -c 'a guard collect with no mask planned for its reason' V/VarkaVectorWalk.java` | `1` |
| a2 | the grammars' undrawn lists, where the name goes | `grep -c '"Fma" - "RoundToIntegral" -' ST/VarkaIrFuzzSuite.scala ST/VarkaWithChildrenSuite.scala` | `1` each |
| a3 | no node suite yet | `git ls-files \| grep -c 'VarkaEmitterScaleRoundingSuite'` | `0` |
| b18 | the size of the source commit | `python3 dev/varka_msg_check.py size --base 0345dd29a53^ --head 0345dd29a53` | `pull request size: 1004 counted lines ...` |

The emission API the lowering calls was checked on master: every descriptor it imports
(`VECTOR_OPERATORS`, `VO_UNARY`, `DOUBLE_VECTOR`, `LANEWISE_VD`, `VECTOR`, `REINTERPRET_L`,
`REINTERPRET_D`, `LONG_VECTOR`, `VO_COMPARISON`, `VECTOR_MASK`, `MASK_BINARY`) is a
`static final` of `V/VarkaDescriptors.java`; `Lane.LONG.lanewiseVI`, `lanewiseVV` and
`Lane.DOUBLE.blend`, `compareVV`, `compareVI` are fields of `V/Lane.java`; `emitValue`, `line`
and `emitGuardCollect` keep their signatures in `V/VarkaVectorWalk.java`
(`grep -n 'static void emitGuardCollect(CodeBuilder cb, VarkaVectorIR node, Integer word,'`
prints one line). So the lowering file is taken whole.

**File taken whole from the commit** (`git show 0345dd29a53:<path> > <path>`):
`V/VarkaScaleRoundingLowering.java` (311 lines: `power`, `lowerBound`, `keepBound`, `inBand`,
`hasBand(int)`, `weight`, `SCRATCH = 4`, `emit` and its helpers). Unchanged.

**The record**, in `V/VarkaVectorIR.java` after `RoundToIntegral`'s record (the branch's place);
the two enums after `enum RoundMode { RINT }` (b2), with the branch's javadoc for each:

    enum ScaleMode { HALF_UP, HALF_EVEN, DOWN }

    enum ScaleOut { DOUBLE }

The record is the branch's text (its javadoc, the canonical constructor's two checks, `MAX_SCALE
= 22`, the short constructor, `hashCode`) plus this method, the only new code; the answers are
the branch's deleted switch arms of `VarkaEmitBudget.weightOf` and `VarkaEmitCost.kindOf`, and
`STRICT` is what its `ownerOf`, `pureOf` and `planWordRef` arms said (the child's word):

    @Override
    public VarkaNodeProps props() {
      // The lane calls VarkaScaleRoundingLowering.weight counts: the magnitude, its mode's tie
      // test, the answer's division, sign, + 0.0 and blend, and, at a scale with a band, the
      // band's compares and rint. A feature per mode and output, and per band: the guard is
      // its own lowering. Null where the child is.
      return new VarkaNodeProps(NEVER, false, STRICT, VarkaScaleRoundingLowering.weight(this),
          getClass().getSimpleName() + '/' + mode + '/' + out
              + (VarkaScaleRoundingLowering.hasBand(scale) ? "/band" : ""),
          FILLS_NOTHING, RESTRICTS_NOTHING, LongNarrowReader.NONE);
    }

**The arms, in master's names** (each beside `RoundToIntegral`'s arm of the same switch; the
compiler names any one missed):

| file | where | text |
|---|---|---|
| `V/VarkaVectorIR.java` | `permits` | `VarkaVectorIR.RoundToScale,` after `VarkaVectorIR.RoundToIntegral,` |
| the same | `laneType` | `case RoundToScale n -> LaneType.DOUBLE;` |
| the same | the two narrow walks' lists (`narrowValuesIn`, `needsNarrowSpecies`) | `RoundToScale _,` after `RoundToIntegral _,` |
| the same | the canonical form (`kid.apply`) | `case RoundToScale n -> "(roundScale:" + n.mode().name() + ":" + n.scale() + ":" + n.out().name() + " " + kid.apply(n.child()) + ")";` |
| the same | the line form (`lineOf`) | the same with `lineOf.applyAsInt(n.child())` |
| the same | `childrenOf`, `withChildren` | `new VarkaVectorIR[] {n.child()}`; `new RoundToScale(n.mode(), n.scale(), children[0], n.out())` |
| `V/Analysis.java` | `enum Refusal` (b6) | `DECIMAL_SLOT(...)` ends in `,` and `ROUNDING_BAND(Family.SELF, false, VarkaFusedKernel.STATUS_ROUNDING_BAND);` follows, with the branch's javadoc |
| the same | `collectRefusals` | `case RoundToScale n -> { if (VarkaScaleRoundingLowering.hasBand(n.scale())) { refuse(node, Refusal.ROUNDING_BAND); } }` |
| the same | `analyze` | `case RoundToScale n -> analyzeOp(node, false, n.child());` |
| `V/Slots.java` | the scratch allocation beside `if (node instanceof RoundToIntegral)` | the branch's block: `SCRATCH` slots into `s.roundTmp`; and `roundTmp`'s javadoc sentence "A RoundToScale parks four here" |
| the same | the switch with `case RoundToIntegral x -> { }` | `case RoundToScale x -> { }` (master has one such switch; the branch's second one is gone with 489) |
| `V/VarkaVectorWalk.java` | the emission switch | `case RoundToScale n -> VarkaScaleRoundingLowering.emit(cb, n, dense, analysis, s, computed);` |
| `V/VarkaRangeAnalysis.java` | beside `case Fma n -> VarkaValueRange.UNKNOWN;` | `case RoundToScale n -> VarkaValueRange.UNKNOWN;` |
| `V/VarkaSameAnswer.java` | the IR switch's `byValueChildren` list | `VarkaVectorIR.RoundToScale _,`: a NaN operand is returned as it is (the Catalyst step is 346b1b's) |
| `V/VarkaKernelPlan.java` | `longNarrowRole`'s `NONE` list | `VarkaVectorIR.RoundToScale _,` |
| `C/VarkaChronoCompiler.java` | the `Rebuilt(node, false, false)` list | `VarkaVectorIR.RoundToScale _,` (an exhaustive switch over the IR, not a compiler arm) |
| `V/VarkaFusedKernel.java` | after `STATUS_DIVIDE_BY_ZERO` (b4) | `int STATUS_ROUNDING_BAND = 1 << 8;` with the branch's javadoc |
| `V/VarkaEmitMechanism.java` | after `GUARD_DECIMAL_SLOT`, and the switch (b7) | `GUARD_ROUNDING_BAND(Family.GUARDS, "a guard on a double's rounding to a scale, its band"),`; `case ROUNDING_BAND -> GUARD_ROUNDING_BAND;` |
| `E/VarkaKernelRunner.java` | b5 | `STATUS_INPUT_BOUND = 1 << 9`, `STATUS_DERIVED_INPUT = 1 << 10`, `STATUS_FORCED = 1 << 11`, and their javadocs ("bit 9, above the kernels' refusal bits, bits 0 to 8") |
| `E/VarkaFallbackAccounting.java` | `STATUS_BITS`, `STATUS_NAMES` (b14) | `VarkaFusedKernel.STATUS_ROUNDING_BAND` after `STATUS_DIVIDE_BY_ZERO`; `"rounding-band"` after `"divide-by-zero"` |
| `docs/sql-varka.md` | the line ``decimal-slot`, `divide-by-zero`, `input-bound`, ...`` | `rounding-band` after `divide-by-zero` |

None of `VarkaEmitBudget`, `VarkaEmitCost` or `VarkaOperandPosition` is edited: their switches
are gone since row 489, and the branch's hunks there are what `props()` above answers.

**The tests, in master's names**, each the branch's hunk unless said:

- `VT/VarkaCanonicalIr.java`: `case "roundScale" -> new RoundToScale(ScaleMode.valueOf(parts[1]),
  Integer.parseInt(parts[2]), node(), ScaleOut.valueOf(parts[3]));` beside `case "round"`.
- `VT/VarkaGuardSpecs.java`: the `ROUNDING_BAND` throw beside `case DIVIDE_BY_ZERO`, the band
  predicate before `if (reason == Analysis.Refusal.DIVIDE_BY_ZERO)`, and `RoundToScale _` in
  the node list.
- `VT/VarkaIrGrammar.java`, `VT/VarkaReference.java` (both arms), `VT/VarkaReproductionSuite.java`,
  `VT/VarkaSmtNodes.java` (the `entry` and the list), `VT/VarkaWordCensus.java`.
- `ST/VarkaEmitCostSuite.scala` (b12: `"RoundToScale"` after `"RoundToIntegral"`),
  `ST/VarkaEmitterBudgetSuite.scala` (the four register rows), `ST/VarkaEmitterChronoSuite.scala`
  (`ROUNDING_BAND` in the set and "four" in its comment), `ST/VarkaLaneTypeSuite.scala` (three
  hunks), `ST/VarkaRangeAnalysisSuite.scala` (two arms).
- `ST/VarkaIrFuzzSuite.scala` and `ST/VarkaWithChildrenSuite.scala` (a2): `- "RoundToScale"`
  after `- "RoundToIntegral"` in each undrawn list. No grammar draws the node, so the node's
  suite below is its only test against Spark in this part.
- `VT/VarkaEmitterScaleRoundingSuite.java`: the branch's file (273 lines, 3 tests), then
  `Case.of` replaced by the text below, which builds the node by hand as 346c's suite does,
  since no compiler arm admits it yet. Spark's side is unchanged: the same SQL, parsed and bound
  by `VarkaSqlResolve`, evaluated by Spark. Delete the imports that fall unused
  (`java.util.Optional`, `CompiledVarkaProjection`, `VarkaExpressionCompiler`), and in the class
  javadoc "compiled from Spark's own expression" becomes "built as part 346b1b's compiler arm
  builds it, from Spark's own expression".

      static Case of(String fn, int scale) {
        var x = VarkaSqlResolve.column("x", DataTypes.DoubleType);
        var cols = CollectionConverters.asScala(List.<Attribute>of(x)).toSeq();
        String sql = fn + "(x, " + scale + ")";
        Expression e = VarkaSqlResolve.parse(sql, cols);
        // Built by hand: no compiler arm admits the node before part 346b1b.
        var node = new VarkaVectorIR.RoundToScale(modeOf(fn), scale,
            new VarkaVectorIR.ColumnRef(0, VarkaVectorIR.LaneType.DOUBLE),
            VarkaVectorIR.ScaleOut.DOUBLE);
        var emitted = VarkaEmitterFixtures.emit(List.of(node), 1, 0, VarkaMatrixRuns.BASE);
        return new Case(sql, VarkaEmitterFixtures.load(emitted), VarkaSqlResolve.bind(e, cols));
      }

      /** Spark's function to the node's mode: VarkaRoundingCompiler.toScale's map (346b1b). */
      private static VarkaVectorIR.ScaleMode modeOf(String fn) {
        return switch (fn) {
          case "round" -> VarkaVectorIR.ScaleMode.HALF_UP;
          case "bround" -> VarkaVectorIR.ScaleMode.HALF_EVEN;
          case "truncate" -> VarkaVectorIR.ScaleMode.DOWN;
          default -> throw new IllegalArgumentException("no rounding function " + fn);
        };
      }

  The APIs it calls hold on `6dafb8bd2f6`: `VarkaEmitterFixtures.emit(List<VarkaVectorIR>,
  int, int, VarkaEmitOptions)`, `VarkaMatrixRuns.BASE`, `VarkaSqlResolve.parse`, `bind`,
  `column` (a Scala object in `ST`), and the kernel's nine-argument `run` with `long[]` and the
  scratch address (`V/VarkaFusedKernel.java`).
- `sql/varka/plans/m8/PLAN.md` row 346 and `sql/varka/plans/m8/VARKA-346.md`: a record of the
  part in section 10 of **at most 12 lines** (what merged and what ran). The branch's 54-line
  record is not taken; this plan file lands first, alone (11.4's head, "Landing the plan").

**Proving it, in order** (JVM steps are the builder's; each is done at the output named):

1. `build/sbt catalyst/Test/compile sql/Test/compile`: `[success]`. Before the arms are in, it
   fails, and its `[error]` lines name each switch of the table above; that is the check that
   none is missed.
2. `python3 dev/varka_push_check.py --no-jvm`: no finding under `R1` (its note line "the IR
   records RoundToScale changed; the registry suites run" is not a finding) and none under `R2`
   (this part removes no string literal from a main file).
3. The eleven registry suites, the command of "The lead's run first" above: no failure. For
   the Scala suites that is `*** FAILED ***` absent; for the JUnit ones, `Failed 0`.
4. `build/sbt 'catalyst/testOnly *VarkaEmitterScaleRoundingSuite *VarkaEmitterChronoSuite
   *VarkaExpressionCompilerSuite *VarkaEmitterRoundingSuite'`: no failure;
   `VarkaEmitterScaleRoundingSuite` prints its 3 test sentences. `VarkaExpressionCompilerSuite`
   still asserts b17's decline: the node is admitted nowhere.
5. `build/sbt 'sql/testOnly *VarkaEvaluatorComponentsSuite *VarkaFallbackReasonSuite'`: no
   failure. The first holds every kernel bit below every evaluator bit.
6. `dev/varka_regen_generated.sh --all`: `varka regen: done`; then `git status --short` lists
   no file the part did not edit by hand. A moved `emitted_bytes.json`, `coverage.json` or
   `census.tsv` is a stop: no shape compiles differently in this part, so a moved byte is a bug
   in the port; report it.
7. `python3 dev/varka_census.py --check`: exit 0.
8. `python3 dev/varka_msg_check.py size --base master --head HEAD`: `under the cap of 1000`,
   expected near 850 (main code about 480: the branch's 534 less the compiler arm's 53, the
   Catalyst step's 5 and the three deleted switches' 8, plus the 12-line `props()`; the registry
   fixtures 72; the node's suite about 285; the record 12).
9. `dev/varka_ops/pre-pr-slot.sh <w>`: `pre-pr: passed on <sha>`. Then the review and
   `open-pr.sh` as `ADDING_AN_EXPRESSION.md`, "Closing a row", says.

**Size stop rule.** If step 8 prints 950 or more, move the node suite's third test
(`nullRowsNeitherDeclineNorAnswer`, about 35 lines) to 346b1b, whose step 4 then takes it from
the branch's file; if it still prints 1000 or more, stop and report to the lead, who splits
again. Nothing else moves: the record, its arms, the refusal and the registry fixtures compile
only together.

**Must not touch:** `C/VarkaRoundingCompiler.java`, `C/VarkaNodeCompiler.java`, the Catalyst
step of `V/VarkaSameAnswer.java`, `ST/VarkaCoverageSuite.scala`,
`B/VarkaRoundingBaselineBenchmark.java` (all 346b1b's); the `FLOAT` operand (it declines by
name, 11.3 decision 3); any other record's `props()`; `VarkaSuiteCheck.VALUE_STATUSES` (a band
is a refusal, not a value status: Spark never raises on it); the results files.

#### 346b1b: the compiler arm of `round`, `bround` and `truncate` over a `DOUBLE` (finished sheet)

*[E1 rework, 28 November 2026: the second half of the old sheet 346b1.]*

**Builder: Sonnet-class, on the condition of 346b1a** (the line "Registry run on 346b1a" reads
"passed"). Branch `varka-346b1b-round-arm` from master once 346b1a is merged. Source: the
compiler and test hunks of `0345dd29a53`. This part admits the node 346b1a built: from it on,
`round(x, s)`, `bround(x, s)` and `truncate(x, s)` over a `DOUBLE` at a constant scale from 0 to
22 compile to one `RoundToScale`, and four benchmark sections and four coverage rows fuse.

**B0, the premises** (on master after 346b1a's merge; f1 to f3 are 346b1a's output).

| # | premise | command | prints |
|---|---|---|---|
| f1 | 346b1a's record (346c, which adds `int precision` after `out`, may be merged too) | `grep -c 'record RoundToScale(ScaleMode mode, int scale, VarkaVectorIR child, ScaleOut out,' V/VarkaVectorIR.java` | `1` |
| f2 | 346b1a's bit | `grep -c 'int STATUS_ROUNDING_BAND = 1 << 8;' V/VarkaFusedKernel.java` | `1` |
| f3 | 346b1a's hand-built suite | `grep -c 'no compiler arm admits the node before part 346b1b' VT/VarkaEmitterScaleRoundingSuite.java` | `1` |
| b8 | the decline arm this part deletes | `grep -c 'case Round _, BRound _, Truncate _ -> "round, bround or truncate over a floating value "' C/VarkaNodeCompiler.java` | `1` |
| b9 | the comment this part replaces | `grep -c 'floating operand never reaches here: VarkaNodeCompiler.floatingDecline declines it first.' C/VarkaRoundingCompiler.java` | `1` |
| b13 | the benchmark section is unlisted | `grep -c 'new Form("DOUBLE round(x, 2)", "round(" + x + ", 2)", "DOUBLE"),' B/VarkaRoundingBaselineBenchmark.java` | `1` |
| b16 | no coverage family yet | `grep -c 'Family("Shortest decimal"' ST/VarkaCoverageSuite.scala` | `0` |
| b17 | the test that reads b8's text | `grep -rn 'over a floating value (row 346)' sql/catalyst/src/test sql/core/src/test \| wc -l` | `1` |
| f4 | the guards the coverage rows carry | `grep -c '"ROUNDING_BAND"' ST/VarkaCoverageSuite.scala` | `0`; `1` if 346c merged first (its cast rows carry the band; step 3 below) |

**The main hunks**, each the branch's unless said:

| file | where | text |
|---|---|---|
| `C/VarkaRoundingCompiler.java` | imports, class javadoc, b9, a new private method | the branch's hunks whole: the imports of `RoundToScale`, `ScaleMode`, `ScaleOut`; the javadoc's "and, at a scale from 0 to 22, over a `DOUBLE` (VARKA-346)"; the `DOUBLE` test calling `toScale` and the `FLOAT` decline in place of b9's comment; `toScale` (negative, null, non-constant and above-22 scales decline by name; the mode map `HALF_UP`, `HALF_EVEN`, `DOWN`) |
| `C/VarkaNodeCompiler.java` | after `\|\| e instanceof Rint \|\| e instanceof Floor \|\| e instanceof Ceil`; b8 | the branch's three lines admitting `Round`, `BRound`, `Truncate` with their comment; delete b8's two-line arm |
| `V/VarkaSameAnswer.java` | the Catalyst step, before `if (children.size() == 1)` | `if (n instanceof RoundBase r) { return new Step.Pass<>(r.left()); }` with its comment and the import of `RoundBase` |

**The tests:**

1. `ST/../VarkaExpressionCompilerSuite.scala` (in `codegen`, one directory up): the branch's
   decline assertions in place of b17's three lines (a negative scale, a scale above 22, a null
   scale, a `FLOAT` operand, each by name), and the test "round, bround and truncate over a
   double are one rounding-to-scale node". Its import line: master's line gains
   `RoundToScale, ScaleMode, ScaleOut` after `RoundToIntegral` (the branch's line is an older
   one; do not copy it).
2. `VT/VarkaEmitterRoundingSuite.java`: the branch's four-line comment change.
3. `ST/VarkaCoverageSuite.scala`: `bandDomain`, the family "Shortest decimal" (four rows), and
   `"Shortest decimal" -> Owed(346)`, the branch's hunks. In the set of guards the rows carry
   (the test "every row whose kernel can decline a batch names its domain"), add
   `"ROUNDING_BAND"` in its sorted place, before `"VALUE_RANGE"` (after `"REMAINDER_BAND"` if
   346a put that there), unless f4 printed `1`.
4. `VT/VarkaEmitterScaleRoundingSuite.java`: back to the branch's text whole
   (`git show 0345dd29a53:<path> > <path>`), so that `Case.of` compiles the SQL through this
   part's arm and asserts that it lowers through the node. `git diff 0345dd29a53 HEAD -- <path>`
   then prints nothing, which 346b2's premise c3 reads.
5. `B/VarkaRoundingBaselineBenchmark.java`: the four `DOUBLE` forms gain `, true` (b13) and the
   javadoc's sentence; `round(f, 1)` stays unlisted.
6. `sql/varka/plans/m8/PLAN.md` row 346 and `VARKA-346.md` section 10: a record of at most 12
   lines.

**Proving it, in order** (JVM steps the builder's):

1. `build/sbt catalyst/Test/compile sql/Test/compile`: `[success]`.
2. `python3 dev/varka_push_check.py --no-jvm`: nothing under `R1` (no record changed) and no
   finding under `R2`: b8's literal is gone from every test, since test 1 replaced b17's line.
3. `build/sbt 'catalyst/testOnly *VarkaEmitterScaleRoundingSuite *VarkaEmitterRoundingSuite
   *VarkaExpressionCompilerSuite *VarkaCoverageSuite'`: no failure;
   `VarkaEmitterScaleRoundingSuite` prints its 3 test sentences.
4. The session tests: `build/sbt 'sql/testOnly *VarkaRoundingBaselineQueriesSuite
   *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite *VarkaFallbackReasonSuite'`: no
   failure. The first fails if the four sections do not fuse as listed; the differential one
   holds the four coverage rows to Spark in a session.
5. `dev/varka_regen_generated.sh --all`: `varka regen: done`; `git diff --stat` then shows only
   `coverage.json`, the generated blocks of `docs/sql-varka.md`, `emitted_bytes.json` (new shapes
   only: prediction 1 of section 4) and `census.tsv` if its reason text moved.
6. `python3 dev/varka_census.py --check`: exit 0.
7. `python3 dev/varka_msg_check.py size --base master --head HEAD`: under the cap, expected
   near 180 and the regenerated rows (the compiler arm 53, the Catalyst step 5, the compiler
   suite 25, the coverage hunks 24, the benchmark 13, the suite's restored `Case.of` about 20,
   the comment 7, the record 12).
8. `dev/varka_ops/pre-pr-slot.sh <w>`: `pre-pr: passed on <sha>`.

**Size stop rule.** If step 7 prints 600 or more, something outside this sheet is in the
branch (a regenerated file the msg check counts, a stray hunk): stop and report it. Nothing of
this part moves to another.

**Must not touch:** any file of 346b1a's table but `V/VarkaSameAnswer.java`'s Catalyst step;
the `FLOAT` operand's decline (its text is the branch's); any other record's `props()`; the
results files.

**Why both halves were one full-strength part, and are Sonnet-class now.** The first new record
after row 489 on a stack written before it: a missed switch fails at compile time, but a wrong
`props()` answer (weight, validity) fails only in the budget register or the fuzzer, and every
later part of this row copies what this one settles. The lead's registry run on 346b1a settles
exactly that before a builder starts; what is left is the branch's text, re-typed by the
tables above.

#### 346b2: the probes, the twins and the sampling of `RoundToScale` (finished sheet)

**Builder: Sonnet-class.** Branch `varka-346b2-round-twins` from master once 346b1b is merged
*[E1 rework, 28 November 2026: 346b1b, which restores the cells suite to the branch's text]*.
Tests and two manifest rows only; no main code. Source: two commits on `varka-346b3-sweeps-r`,
`e6e639c4056` (350 counted lines) and `299c082ccbc` (243), both written on top of `0345dd29a53`.

**B0, the premises** (on master after 346b1b's merge; the first three are the output of 346b1a
and 346b1b).

| # | premise | command | prints |
|---|---|---|---|
| c1 | 346b1a is merged (346c, which adds `int precision` after `out`, may be too) | `grep -c 'record RoundToScale(ScaleMode mode, int scale, VarkaVectorIR child, ScaleOut out,' V/VarkaVectorIR.java` | `1` |
| c2 | the five names the tests use are the branch's | `git diff 0345dd29a53 HEAD -- V/VarkaScaleRoundingLowering.java \| grep -c '^-.*\(static double lowerBound(int\|static double keepBound(int\|static boolean inBand(int\|static boolean hasBand(int\|MAX_SCALE =\)'` | `0` (the whole diff is empty before 346c; 346c only adds overloads and one weight line) |
| c3 | the cells suite is the branch's file (346b1b's test 4 restored it) | `git diff 0345dd29a53 HEAD -- VT/VarkaEmitterScaleRoundingSuite.java \| wc -l` | `0` |
| c4 | no twin or sweep suite yet | `git ls-files \| grep -c 'VarkaShortestRound'` | `0` |
| c5 | the manifest's rows (on `6dafb8bd2f6`) | `grep -c 'varka-sweeps' sql/varka/ci/sweeps.tsv` | `18` |

The two commits use only `VarkaScaleRoundingLowering`'s `lowerBound`, `keepBound`, `hasBand`,
`inBand` and `MAX_SCALE`, and `VarkaEmitterFixtures.Loaded`, all unchanged by the re-port (c2),
so their test code applies as written. *[Fix round, 28 November 2026, finding 5.]* 346b2 need
not precede 346c: the cherry-picked suites call `RoundToScale`'s four-argument constructor,
which 346c keeps (it passes precision 0), and the `int` forms of `hasBand` and `inBand`, beside
which 346c adds overloads that take the node.

**Steps.**

1. `git cherry-pick e6e639c4056 299c082ccbc`.
2. Conflicts, by file, each resolved this way and no other:
   - `sql/varka/plans/m8/VARKA-346.md`, `sql/varka/plans/m8/PLAN.md`: take master's side
     (`git checkout --ours`), then write a record of at most 10 lines in section 10 and the
     row's note, after the cherry-picks.
   - `sql/varka/ci/sweeps.tsv`: keep master's rows and add the commit's two
     (`VarkaShortestRoundSweepSuite`, `VarkaShortestRoundTwinSuite`) at their places in the
     file's order by suite name; `python3 dev/varka_sweeps.py --check` (no JVM) prints
     `varka_sweeps: 20 rows listed, none missing` (18 on `6dafb8bd2f6`).
   - `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFallbackReasonSuite.scala`:
     keep master's text and add the commit's one test, "a double rounded to a scale with a
     value in its band is counted under rounding-band", after the file's last test.
   - any test file 346b1a or 346b1b changed by its size stop rule: master's side, then the
     commit's hunk.
   - A conflict in any other file is not expected: stop and report it.
3. *[E1 rework, 28 November 2026.]* Nothing moves here from 346b1a or 346b1b: their size stop
   rules move tests between those two parts only.

**Proving it** (JVM steps the builder's):

1. `build/sbt catalyst/Test/compile`: `[success]`.
2. `build/sbt 'catalyst/testOnly *VarkaEmitterScaleRoundingSuite *VarkaShortestRoundTwinSuite
   *VarkaShortestRoundSweepSuite'`: no failure; the three suites print 6, 3 and 1 test sentences
   (the branch's counts at `299c082ccbc`); without `-Dvarka.sweep=true` the binary32 twin prints
   `!!! CANCELED !!!` (its `assumeTrue`), which is its expected state in the PR build.
3. `build/sbt 'sql/testOnly *VarkaFallbackReasonSuite'`: no failure.
4. The sweep step, by hand on the laptop, once: `python3 dev/varka_sweeps.py --nightly` prints
   the sbt lines (a `set Test/javaOptions ++= Seq("-Dvarka.sweep=true", ...)` line, then
   `testOnly` lines); run them for the two new suites only; done
   when both pass, and the elapsed time goes into the manifest's note in place of "not yet
   timed" and into section 4's prediction 5.
5. `python3 dev/varka_msg_check.py size --base master --head HEAD`: under the cap (about 600).
6. `dev/varka_ops/pre-pr-slot.sh <w>`: `pre-pr: passed on <sha>`.

**Must not touch:** any main file; the lowering's bounds (a test that disagrees with them is a
346b1a bug, reported, not a test to change).

#### 346a: the floating remainder and `pmod` (finished sheet)

**Builder: Sonnet-class, with 346b1a's merged pull request open beside the sheet as the worked
example** (the same kind of record, refusal and status bit, one bit up; 346b1b's, if merged,
for the compiler arm). Branch `varka-346a-remainder-r` from master once 346b1a is merged
*[E1 rework, 28 November 2026: it waited on 346b1 and preferred 346b2; it needs only the node's
half, since 346b2 and 346b1b touch no line 346a's sheet does not place by a rule below]*.
Source: `bb377a93cbf` on `varka-346a-remainder`, 968 counted lines against its parent
`65fdfcfa1a7`. The branch carries the old 346b stack and row 342's old `Fma` commits below it;
take nothing from them.

**B0, the premises** (on master after 346b1a; d1 to d3 are 346b1a's output, the rest hold on
`6dafb8bd2f6` too).

| # | premise | command | prints |
|---|---|---|---|
| d1 | bit 8 is the band's | `grep -c 'int STATUS_ROUNDING_BAND = 1 << 8;' V/VarkaFusedKernel.java` | `1` |
| d2 | the runner's bits are 9, 10, 11 | `grep -c 'public static final int STATUS_[A-Z_]* = 1 << \(9\|10\|11\);' E/VarkaKernelRunner.java` | `3` |
| d3 | the last refusal is the band's | `grep -c 'ROUNDING_BAND(Family.SELF, false, VarkaFusedKernel.STATUS_ROUNDING_BAND);' V/Analysis.java` | `1` |
| d4 | nothing of this part in the tree | `grep -rl 'FloatRemainder\|REMAINDER_BAND' sql/catalyst/src sql/core/src \| wc -l` | `0` |
| d5 | the decline 346a deletes | `grep -c 'case Remainder _, Pmod _ -> "a floating remainder (row 346)";' C/VarkaNodeCompiler.java` | `1` |
| d6 | the tests that read d5's text | `grep -rn 'a floating remainder (row 346)' sql/catalyst/src/test sql/core/src/test \| wc -l` | `1` (`VarkaFloatingLaneSuite.scala:599`) |
| d7 | the four sections listed as the row path | `grep -c '+ from, Fusion.NONE));' B/VarkaFloatingBaselineBenchmark.java` | `5` (the four remainders and the cast) |
| d8 | the family's helpers | `grep -c 'private static VarkaVectorIR doubleSlot(double v, DeclineSink sink)' C/VarkaFloatingCompiler.java` | `1` |
| d9 | the family's ANSI test | `grep -c 'import org.apache.spark.sql.catalyst.expressions.EvalMode\$;' C/VarkaFloatingCompiler.java` | `1` |
| d10 | `Fma`'s word handling, which the lowering copies | `grep -c 'if (!dense && s.ownWord.contains(n)) {' V/VarkaVectorWalk.java` | `1` or more |
| d11 | the coverage suite's second double column | `grep -c 'private val x2 = AttributeReference("x2", DoubleType)()' ST/VarkaCoverageSuite.scala` | `1` |

The lowering's API, checked on master as for 346b1a: the descriptors and `Lane` fields it uses
are those 346b1a's lowering uses plus `Lane.DOUBLE.lanewiseVV` (a field of `V/Lane.java`), and
`loadWord` and `storeWord` keep their signatures (`static void loadWord(CodeBuilder cb, Slots s,
int ref)`, `static void storeWord(CodeBuilder cb, Slots s, int ref)` in `V/VarkaVectorWalk.java`).
The FMA is `DoubleVector.fma`, emitted inside the lowering (the branch's decision a), not the
`Fma` node.

**Files taken whole from the commit** (`git show bb377a93cbf:<path> > <path>`):

- `V/VarkaFloatRemainderLowering.java` (190 lines: `SCRATCH = 5`, `WEIGHT = 24`,
  `WINDOW = 0x1.0p53`, `inBand`, `emit`, `abs`). Unchanged.
- `VT/VarkaEmitterFloatRemainderSuite.java` (312 lines, 5 tests). Unchanged.
- `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatRemainderQuerySuite.scala`
  (119 lines, 3 tests; it never ran on the branch). Unchanged unless step 4 below fails on it.

**The record**, in `V/VarkaVectorIR.java` after `Fma`'s record: the branch's text (its javadoc,
the canonical constructor with `requireSameLane("a floating remainder", dividend, divisor)` and
the `DOUBLE` check, the short constructor, `hashCode`) plus:

    @Override
    public VarkaNodeProps props() {
      // VarkaFloatRemainderLowering.WEIGHT's lane calls: two magnitudes, the division, the
      // floor's rint, step and blend, the FMA's negation and FMA, the correction's compare,
      // add and blend, the sign's reinterpretations, AND and OR, the small dividend's
      // compare and blend, and the band's three compares. Null where either operand is.
      return new VarkaNodeProps(NEVER, false, STRICT, VarkaFloatRemainderLowering.WEIGHT,
          getClass().getSimpleName() + '/' + laneType(), FILLS_NOTHING, RESTRICTS_NOTHING,
          LongNarrowReader.NONE);
    }

`STRICT` is the branch's `ownerOf` (`andOwner(node, dividend, divisor)`), `pureOf` (`andExpr`)
and `planWordRef` (`andRef`) arms: the operands' AND.

**The arms, in master's names** (each beside `Fma`'s arm of the same switch):

| file | where | text |
|---|---|---|
| `V/VarkaVectorIR.java` | `permits` | `VarkaVectorIR.FloatRemainder,` after `VarkaVectorIR.Fma,` |
| the same | `laneType` | `case FloatRemainder n -> n.dividend().laneType();` |
| the same | `narrowValuesIn`, `needsNarrowSpecies` lists | `FloatRemainder _,` after `Fma _,` |
| the same | canonical form | `case FloatRemainder n -> "(frem " + kid.apply(n.dividend()) + " " + kid.apply(n.divisor()) + ")";` (master's `kid.apply`, not the branch's `canonical(...)`) |
| the same | line form | `"(frem " + lineOf.applyAsInt(n.dividend()) + " " + lineOf.applyAsInt(n.divisor()) + ")"` |
| the same | `childrenOf`, `withChildren` | `{n.dividend(), n.divisor()}`; `new FloatRemainder(children[0], children[1])` |
| `V/Analysis.java` | `enum Refusal`, after d3 | d3's `;` becomes `,`, then `REMAINDER_BAND(Family.SELF, false, VarkaFusedKernel.STATUS_REMAINDER_BAND);` with the branch's javadoc |
| the same | `collectRefusals` | `case FloatRemainder n -> refuse(node, Refusal.REMAINDER_BAND);` |
| the same | `analyze` | `case FloatRemainder n -> analyzeOp(node, false, n.dividend(), n.divisor());` |
| `V/Slots.java` | the scratch allocation beside 346b1a's `RoundToScale` block | the branch's block: `VarkaFloatRemainderLowering.SCRATCH` slots into `s.roundTmp` |
| the same | the switch with `case Fma x -> { }` | `case FloatRemainder x -> { }` (the operands' words are demanded by `propagate`, as `Fma`'s are) |
| `V/VarkaVectorWalk.java` | the emission switch | `case FloatRemainder n -> VarkaFloatRemainderLowering.emit(cb, n, dense, analysis, s, computed);` |
| `V/VarkaRangeAnalysis.java` | beside `Fma` | `case FloatRemainder n -> VarkaValueRange.UNKNOWN;` |
| `V/VarkaSameAnswer.java` | beside `case VarkaVectorIR.Fma _ -> new Step.Unfixed<>();` | `case VarkaVectorIR.FloatRemainder _ -> new Step.Unfixed<>();` with the branch's comment |
| `V/VarkaKernelPlan.java` | `longNarrowRole`'s `NONE` list | `VarkaVectorIR.FloatRemainder _,` |
| `C/VarkaChronoCompiler.java` | the `Rebuilt` list | `VarkaVectorIR.FloatRemainder _,` |
| `V/VarkaFusedKernel.java` | after d1 | `int STATUS_REMAINDER_BAND = 1 << 9;` with the branch's javadoc |
| `V/VarkaEmitMechanism.java` | after `GUARD_ROUNDING_BAND`, and the switch | `GUARD_REMAINDER_BAND(Family.GUARDS, "a guard on a floating remainder, its quotient's band"),`; `case REMAINDER_BAND -> GUARD_REMAINDER_BAND;` |
| `E/VarkaKernelRunner.java` | d2 | `1 << 10`, `1 << 11`, `1 << 12` and the javadocs ("bit 10, above ... bits 0 to 9") |
| `E/VarkaFallbackAccounting.java` | `STATUS_BITS`, `STATUS_NAMES` | `STATUS_REMAINDER_BAND` after `STATUS_ROUNDING_BAND`; `"remainder-band"` after `"rounding-band"` |
| `docs/sql-varka.md` | the reasons line 346b1a edited | `remainder-band` after `rounding-band` (**the branch lacks this**) |
| `C/VarkaFloatingCompiler.java` | imports, class javadoc, the `arm` switch, a new method | the branch's hunks whole: `case Remainder r -> ...`, `case Pmod p -> ...`, and `remainder(...)` |
| `C/VarkaNodeCompiler.java` | after 346b1b's `Round` line if `grep -c 'e instanceof Round \|\| e instanceof BRound \|\| e instanceof Truncate' C/VarkaNodeCompiler.java` prints `1`, else directly after `\|\| e instanceof Rint \|\| e instanceof Floor \|\| e instanceof Ceil`; d5 | `\|\| e instanceof Remainder \|\| e instanceof Pmod` with its comment; delete d5's line |

**The tests, in master's names** (the branch's hunks unless said):

- `VT/VarkaCanonicalIr.java` (`case "frem"`), `VT/VarkaGuardSpecs.java` (three hunks),
  `VT/VarkaIrGrammar.java`, `VT/VarkaReference.java` (two arms and `doubleRemainder`),
  `VT/VarkaSmtNodes.java`, `VT/VarkaWordCensus.java`.
- `ST/VarkaEmitCostSuite.scala` *[fix round, finding 8]*: `"FloatRemainder"` after
  `"RoundToScale"` in the `awaitingRefit` list, on b12's line as 346b1a left it, not a price; the
  branch's hunk adds the same name on an older form of that line, so its text is not applied.
  `ST/VarkaEmitterChronoSuite.scala` (the set gains
  `REMAINDER_BAND`, "five" in its comment), `ST/VarkaIrFuzzSuite.scala`,
  `ST/VarkaLaneTypeSuite.scala`, `ST/VarkaRangeAnalysisSuite.scala`,
  `ST/VarkaWithChildrenSuite.scala`,
  `ST/VarkaCoverageSuite.scala` (`remainderDomain`, the family "Floating remainder", five rows,
  `"REMAINDER_BAND"` in the refusal list, `"Floating remainder" -> Owed(346)`).
  *[E1 rework, 28 November 2026.]* The refusal list is the set of guards the rows carry (the
  test "every row whose kernel can decline a batch names its domain"); `"REMAINDER_BAND"`
  goes in its sorted place, after `"MONTH_COUNT"` and before `"ROUNDING_BAND"` where 346b1b or
  346c put that name there first, else before `"VALUE_RANGE"`.
- **Not on the branch, added here** (R1 and the suites need them):
  - `VT/VarkaReproductionSuite.java`, after the `Fma` entry: a comment line and
    `new VarkaVectorIR.FloatRemainder(new ColumnRef(0, LaneType.DOUBLE),
    new ColumnRef(1, LaneType.DOUBLE)),`.
  - `ST/VarkaEmitterBudgetSuite.scala`, after `(new Fma(dc, dc, dc), 1, 0, VarkaMatrix.base),`:
    `(new FloatRemainder(dc, dc), 1, 0, VarkaMatrix.base),`.
  - `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatingLaneSuite.scala` (d6):
    the test "EXPLAIN names why a floating cast or remainder declines, and fuses the picks and
    sum" becomes "EXPLAIN names why a floating cast declines, and fuses the picks, the remainder
    and the sum"; its line `assert(lines(3).contains("a floating remainder (row 346)"), lines)`
    becomes `assert(lines(3).endsWith(": fused"), lines)`; the section comment above it drops
    "the remainder,".
  - `B/VarkaFloatingBaselineBenchmark.java` (d7): the four sections `DOUBLE x % w`,
    `DOUBLE x % 2.5`, `DOUBLE pmod(x, w)` and `FLOAT f % wf` take `Fusion.WHOLE`; the cast stays
    `Fusion.NONE`; the javadoc's last sentence becomes "Row 346's remainders are `WHOLE` since its
    part 346a; its cast is `NONE` until part 346c."
- `sql/varka/plans/m8/PLAN.md` and `VARKA-346.md`: a record of at most 15 lines.

**Proving it** (JVM steps the builder's):

1. `build/sbt catalyst/Test/compile`: `[success]`.
2. `python3 dev/varka_push_check.py --no-jvm`: no finding under `R1` or `R2`.
3. `build/sbt 'catalyst/testOnly *VarkaEmitterFloatRemainderSuite *VarkaExpressionCompilerSuite
   *VarkaEmitterChronoSuite *VarkaCoverageSuite'`: no failure; the first prints 5 sentences.
4. `build/sbt 'sql/testOnly *VarkaFloatRemainderQuerySuite *VarkaFloatingLaneSuite
   *VarkaFloatingBaselineQueriesSuite *VarkaEvaluatorComponentsSuite *VarkaFallbackReasonSuite'`:
   no failure; the first prints 3 sentences. If `VarkaFloatingBaselineQueriesSuite` fails on a
   remainder section, its clue names the section and `refusal true` or `refusal false`. Under
   `refusal true` only (the two-lane refusal of rows 503 and 612, the 128-bit host's default):
   that section takes `declinesLongConditions ? Fusion.NONE : Fusion.WHOLE`, and the record
   says so. Under `refusal false`: a bug of this part; stop and report it.
5. The registry suites, as 346b1a's proving step 3 (the eleven, or the list
   `python3 dev/varka_push_check.py --dry-run --base master` prints).
6. `dev/varka_regen_generated.sh --all`: `varka regen: done`; then `python3 dev/varka_census.py
   --check`: exit 0.
7. `python3 dev/varka_msg_check.py size --base master --head HEAD`: under the cap, expected near
   930 (968 on the branch, less about 15 lines of switch arms and 60 of plan record, plus about
   35 for the tests the branch lacked).
8. `dev/varka_ops/pre-pr-slot.sh <w>`: `pre-pr: passed on <sha>`.

**The split rule.** If step 7 prints 1000 or more, move `VarkaFloatRemainderQuerySuite.scala`
(119 lines) to a pull request of its own right after, and nothing else.

**Must not touch:** `Fma` and its suite; the floating division's arms (`divide`), which the zero
test reuses as they are; the cast sections of the benchmark (346c's).

**Not built, as decided:** SLEEF's `xfmod` port (question 2 of section 12, default (a)); a
`FLOOR` mode of `RoundToIntegral` (the branch's decision d).

#### 346c: the cast of `DOUBLE` and `FLOAT` into a decimal of 18 digits or fewer (finished sheet)

**Builder: Sonnet-class.** Branch `varka-346c-decimal-cast-r` from master once 346b1a and row
350's part 350a1b are merged (346a, 346b1b and 346b2 need not be; *[E1 rework, 28 November
2026: it waited on 346b1; it reads the node, its lowering and its refusal, all 346b1a's]*).
Source: `9ca2d818541` (841 counted
lines against its parent `ca412ad7b51`) and `1a7e5cd2ca6` (17) on `varka-346c-decimal-cast`.
That branch's cast compiler is the old 350 stack's; master's after 350a1b has the same three
places this part edits, written by `VARKA-350.md` 11.5 steps 2, 3 and 5, so the hunks below are
re-typed onto those, not cherry-picked.

**B0, the premises** (on master after 346b1a and 350a1b; e1 to e5 are their output, e6 to e9 hold
on `6dafb8bd2f6` too; a different answer to e7 is allowed, as said).

| # | premise | command | prints |
|---|---|---|---|
| e1 | 346b1a's record, no precision yet | `grep -c 'record RoundToScale(ScaleMode mode, int scale, VarkaVectorIR child, ScaleOut out, int hash)' V/VarkaVectorIR.java` | `1` |
| e2 | 350a1b's decline of a floating source | `grep -c "(floating ? \"row 346's\"" C/VarkaDecimalCompiler.java` | `1` |
| e3 | 350a1b's claim | `grep -c '&& (integralLane(from).isPresent() \|\| DataTypes.BooleanType.equals(from));' C/VarkaDecimalCastCompiler.java` | `1` |
| e4 | 350a1b's overflow test | `grep -c 'static boolean sourceCanOverflow(DataType from, DecimalType to)' C/VarkaDecimalCastCompiler.java` | `1` |
| e5 | 350a1b's boolean arm in `intoDecimal` | `grep -c 'if (DataTypes.BooleanType.equals(from)) {' C/VarkaDecimalCastCompiler.java` | `1` |
| e6 | the guard's lane, which this part widens | `grep -c 'return analysis.laneOf(node instanceof ConvertLane c ? c.child() : node);' V/VarkaVectorWalk.java` | `1` |
| e7 | `floatingDecline`'s cast text | `grep -c '+ "346, 350, 364 and 366)";' C/VarkaNodeCompiler.java` | `1`; `0` if row 350's part 350b1 merged first, which rewords it "a cast of a floating value to a decimal (row 346)" |
| e8 | the benchmark's cast section | `grep -c '"SELECT CAST(" + x + " AS DECIMAL(10, 2))" + from, Fusion.NONE));' B/VarkaFloatingBaselineBenchmark.java` | `1` |
| e9 | the node compiler's floating test | `grep -c 'private static boolean isFloating(org.apache.spark.sql.types.DataType t)' C/VarkaNodeCompiler.java` | `1` |
| e10 | the tests that read e2's text | `grep -rln "row 346's" sql/catalyst/src/test sql/core/src/test` | the files to change in step 6 |

**The record change**, `V/VarkaVectorIR.java`, the branch's hunks re-typed onto 346b1a's record:

- `enum ScaleOut { DOUBLE, UNSCALED }` and its javadoc.
- The record gains `int precision` before `hash`:
  `record RoundToScale(ScaleMode mode, int scale, VarkaVectorIR child, ScaleOut out,
  int precision, int hash)`; the canonical constructor's two new checks (a `DOUBLE` answer has
  precision 0; an `UNSCALED` one is `HALF_UP` with `s <= p <= MAX_PRECISION`);
  `hashOf(mode, scale, child, out, precision)`; `MAX_PRECISION = 18`; the four-argument
  constructor passes precision 0; a new five-argument one. The javadoc paragraph on `UNSCALED`.
- `laneType`:

      case RoundToScale n -> n.out() == ScaleOut.UNSCALED ? LaneType.LONG : LaneType.DOUBLE;

- `scaleOutName(RoundToScale)` (the branch's helper) and both renderings call it, so an unscaled
  answer renders `UNSCALED10`; `withChildren` passes `n.precision()`.
- `props()`: the cost kind's band test becomes `VarkaScaleRoundingLowering.hasBand(this)`; the
  weight is already `weight(this)`. Nothing else in it changes:

      return new VarkaNodeProps(NEVER, false, STRICT, VarkaScaleRoundingLowering.weight(this),
          getClass().getSimpleName() + '/' + mode + '/' + out
              + (VarkaScaleRoundingLowering.hasBand(this) ? "/band" : ""),
          FILLS_NOTHING, RESTRICTS_NOTHING, LongNarrowReader.NONE);

**The lowering**, `V/VarkaScaleRoundingLowering.java`: the branch's hunks whole (its javadoc
paragraph, `overflowBound`, `inCastBand`, `hasBand(RoundToScale)`, `inBand(RoundToScale,
double)`, `TWO_52`, `TWO_52_BITS`, the `UNSCALED` weight, the `UNSCALED` branch of `emit`,
`emitUnscaled`, `BLEND_SCALAR_L`, `longPower`, `emitToLong`, `emitMagnitudeBitsCompare`,
`emitCastBand`) and the import of `ConstantDescs`, with one change: `TWO_52_BITS` is
package-private (`static final long`), so that 346p1's renderer reads it. Its arms, as
`verify_346c.py` models them:

    n      = the HALF_UP tie test's integer (346b1a), below L_s
    v      = a < L_s ? toLong(n) : toLong(a) * 10^s        toLong(t) = bits(t + 2^52) - bits(2^52)
    v      = x < 0 ? -v : v                                 (the sign bit of x, on the long lane)
    v      = (bits(x) & MAX) >= bits(10^(p - s)) ? 10^p : v NaN, the infinities, the overflow
    band   = a < 10^(p - s) and (a >= 2^52 or (a >= L_s and rint(a) != a))

and the compiler puts `GuardedRange(v, -(10^p - 1), 10^p - 1, mode)` over it (`guardDigits`),
which nulls `10^p` under LEGACY and TRY and declines the batch under ANSI, where the row engine
answers NaN as null and raises on the overflow.

**The other main hunks:**

| file | where | text |
|---|---|---|
| `V/Analysis.java` | 346b1a's `collectRefusals` arm | `hasBand(n.scale())` becomes `hasBand(n)` |
| `V/VarkaVectorWalk.java` | `guardLane` (e6) | `analysis.laneOf(node instanceof ConvertLane c ? c.child() : node instanceof RoundToScale r ? r.child() : node)` and its javadoc clause: the band compares the double, while the node's own lane is the long |
| `C/VarkaDecimalCastCompiler.java` | `claimsIntoDecimal` (e3) | the condition gains `\|\| isFloating(from)`, and a new `static boolean isFloating(DataType type)` follows it |
| the same | `sourceCanOverflow` (e4), first lines | `if (isFloating(from)) { return true; }` with the branch's comment |
| the same | `intoDecimal`, before the boolean arm (e5) | `if (isFloating(from)) { return floatingOperand(c, inputs, literals, sink).map(x -> new Scaled(guardDigits(new RoundToScale(ScaleMode.HALF_UP, to.scale(), x, ScaleOut.UNSCALED, to.precision()), to.precision(), mode), to)); }` |
| the same | after `integralOperand` | the branch's `floatingOperand` (a `DOUBLE` as it is, a `FLOAT` as `new ConvertLane(x, LaneType.DOUBLE)`) |
| the same | the class javadoc | the branch's list item "from `DOUBLE` and `FLOAT`" and the sentence on the casts from a decimal to the floating types |
| `C/VarkaDecimalCompiler.java` | e2 | `"row 346's"` becomes `"row 364's above 18 digits"` |
| `C/VarkaNodeCompiler.java` | `floatingDecline`'s passing list | the branch's four lines: `\|\| (e instanceof Cast fc && isFloating(fc.child().dataType()) && fc.dataType() instanceof DecimalType fd && fd.precision() <= 18)` |
| the same | e7 | with e7 `1`: `"346, 350, 364 and 366)"` becomes `"350, 364 and 366)"`; with e7 `0`: "a cast of a floating value to a decimal (row 346)" becomes "a cast of a floating value to a decimal wider than 18 digits (row 364)" |

The intoDecimal arm's first line is the two-lane test (`sink.declinesTwoLaneChecks()`, then
`TWO_LANE_REASON`), so the cast declines at two long lanes on x86 with no code here (11.3
decision 7). *[Fix round, 28 November 2026.]* The owner answered row 597b's question 1
"remove"; if 597b merged first, that first line is 597b's decline of a source that is not a
decimal (`VarkaDecimalCompiler.declinesAtTwoLanes`), which declines the floating source too. So
in both orders the cast declines at two long lanes, and the benchmark rule of step 5 below,
`declinesLongConditions ? Fusion.NONE : Fusion.WHOLE`, holds as written.

**The tests:**

1. `VT/VarkaEmitterFloatDecimalCastSuite.java`, from `9ca2d818541` whole (285 lines, 3 tests; it
   builds the node by hand and uses `lowerBound`, `overflowBound`, `inCastBand`, `longPower`).
2. `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatDecimalCastQuerySuite.scala`
   from the commit whole (157 lines, 5 tests), then each test body wrapped in
   `withBothConf(SQLConf.VARKA_DECLINE_TWO_LANE_CONDITIONS.key, "false") { ... }` (a helper of
   `VarkaSharedSessions`, which `VarkaMaybeThrows` extends). *[Fix round, finding 8.]* The
   shared sessions pin the setting to `false` already (`VarkaSharedSessions.sparkConf`, lines
   63 to 67 at `6dafb8bd2f6`); the wrap keeps the suite right if that pin changes, since the
   band test expects a kernel. One test more,
   written here: "at the two-lane refusal the cast declines and answers as Spark does", which
   sets the same key to `"true"` and checks `CAST(x AS DECIMAL(10, 2))` over the suite's double
   column in the suite's three modes, the answers only.
3. `VT/VarkaCanonicalIr.java`: `1a7e5cd2ca6`'s hunk (the `UNSCALED<p>` parse).
4. `VT/VarkaGuardSpecs.java` (`inBand((RoundToScale) node, ...)`), `VT/VarkaReference.java` (the
   `UNSCALED` arm). Added here: a `VT/VarkaReproductionSuite.java` entry
   `new VarkaVectorIR.RoundToScale(VarkaVectorIR.ScaleMode.HALF_UP, 2,
   new ColumnRef(0, LaneType.DOUBLE), VarkaVectorIR.ScaleOut.UNSCALED, 10),` (the round trip
   `1a7e5cd2ca6` exists for), and a `ST/VarkaEmitterBudgetSuite.scala` row
   `(new RoundToScale(ScaleMode.HALF_UP, 2, dc, ScaleOut.UNSCALED, 10), 1, 0, VarkaMatrix.base),`.
5. `B/VarkaFloatingBaselineBenchmark.java` (e8): the cast section takes
   `declinesLongConditions ? Fusion.NONE : Fusion.WHOLE`, and the javadoc's sentence on row 346
   says the cast is fused since 346c, except at the two-lane refusal.
6. The tests e10 lists: an assertion that a `DOUBLE` or `FLOAT` source into a decimal of 18
   digits or fewer declines "row 346's" becomes the fused form beside it in the same test (as
   350a1b's sheet did for the integral source); one at more than 18 digits asserts
   "row 364's above 18 digits".
7. `ST/VarkaCoverageSuite.scala`: the branch's two rows read through `BIGINT`, and two more as
   outputs (11.3 decision 6), `CAST(x AS DECIMAL(10, 2))` and `CAST(f AS DECIMAL(9, 1))`, each
   with the same `domain` string as its `BIGINT` row.
   *[E1 rework, 28 November 2026.]* These rows carry the band's guard, `ROUNDING_BAND`: if
   `grep -c '"ROUNDING_BAND"' ST/VarkaCoverageSuite.scala` prints `0` (346b1b not merged yet),
   add `"ROUNDING_BAND"` to the set of guards the rows carry (the test "every row whose kernel
   can decline a batch names its domain") in its sorted place, before `"VALUE_RANGE"`.
8. `sql/varka/plans/m8/PLAN.md` and `VARKA-346.md`: a record of at most 15 lines.

**Proving it** (JVM steps the builder's):

1. `build/sbt catalyst/Test/compile sql/Test/compile`: `[success]`.
2. `dev/varka_emit.sh "CAST(x AS DECIMAL(10, 2))" --columns x:double`: the entry's line ends
   `FusedOutput(0)`; the same with `--columns x:float` over `CAST(x AS DECIMAL(9, 1))`.
3. `python3 dev/varka_push_check.py --no-jvm`: no finding under `R1` (the record's components
   changed, so its registry suites run) or `R2`.
4. `build/sbt 'catalyst/testOnly *VarkaEmitterFloatDecimalCastSuite *VarkaEmitterScaleRoundingSuite
   *VarkaExpressionCompilerSuite *VarkaDecimalCastTableSuite *VarkaCoverageSuite
   *VarkaCastMatrixSuite'`: no failure; the first prints 3 sentences.
5. `build/sbt 'sql/testOnly *VarkaFloatDecimalCastQuerySuite *VarkaDecimalCastQuerySuite
   *VarkaFloatingBaselineQueriesSuite *VarkaCoverageHarnessSuite
   *VarkaCoverageDifferentialSuite'`: no failure; the first prints 6 sentences.
6. The registry suites, as 346b1a's proving step 3.
7. `dev/varka_regen_generated.sh --all`: `varka regen: done`; `casts_fused.tsv` gains the
   `double` and `float` to `dec` forms, and nothing in `sql/varka/proofs/` moves
   (`git diff --stat -- sql/varka/proofs/` prints nothing).
8. `python3 dev/varka_msg_check.py size --base master --head HEAD`: under the cap, expected near
   820 (858 on the branch, less its 80-line record, plus about 40 for steps 2, 4 to 7 of the tests).
9. `dev/varka_ops/pre-pr-slot.sh <w>`: `pre-pr: passed on <sha>`.

**The split rule.** If step 8 prints 1000 or more, move `VarkaFloatDecimalCastQuerySuite.scala`
to a pull request of its own right after, and nothing else.

**Must not touch:** the decimal sources' arms of `intoDecimal`, `rescaled`, the integral and
boolean arms 350a1b wrote; `decimal_casts.smt2`; the `DOUBLE` output of `RoundToScale`. The
`2^52` conversion's claim is 346p1's proof file, not this part's.

#### 346p1: the proof files' claims A, B and D (finished sheet)

*[E1 rework, 28 November 2026: the old sheet 346p split by the owner's decision E1 (a). Claims
A, B and D stay here, Sonnet-class, buildable now; claim C is part 346p2, January 2027.]*

**Builder: Sonnet-class.** Branch `varka-346p1-proofs` from master once 346a (for the float sum
and the remainder's floor) and 346c (for the conversion) are merged; the checks of claim A need
only 346c, those of claims B and D only 346a, so if one of the two is late the builder may land
the other's claims first, in a pull request of their own.

Every proof file is rendered by `VT/VarkaProofFiles.java` (a method per file, registered by
`files.put("<name>.smt2", <method>())` beside `files.put("fp_round.smt2", fpRound());`) and
drift-checked by `ST/VarkaProofFilesSuite.scala`; `VARKA_PROOFS_REGEN=true build/sbt
'catalyst/testOnly *VarkaProofFilesSuite'` writes it; `dev/varka_prove.sh` runs it; a row in
`sql/varka/proofs/README.md`'s table names it. Each check is `(echo "<what>: expect sat|unsat")`
then `(check-sat)`, after a `(reset)` and `(set-logic ALL)`, as `fp_round.smt2` runs them.

**What was run here, with no JVM** (the pinned solvers in `~/.cache/varka-solvers`, z3 5.1.0
and cvc5 1.4.1, on the drafts in `scratch-346/smt/`; times on the laptop beside other work):

| claim | draft | z3 | cvc5 |
|---|---|---|---|
| A: `bits(t + 2^52) - bits(2^52) = t` for every integral double `t` in `[-0, 2^52)`, and wrong at `2^52 + 1` | `conv52.smt2` | unsat, sat; 0.6 s | unsat, sat; 0.7 s |
| B: `RN32(RN64(a + b)) = RN32(a + b)` for binary32 `a`, `b`, and wrong narrowed toward zero | `sum32b.smt2` | the unsat check timed out at 280 s | unsat, sat; 15 s (default mode) |
| D: the floor both lowerings build, `rint(Q)` stepped down by 1 where it passed `Q` below `2^52` and `Q` itself above, is `fp.roundToIntegral RTN` for every non-negative binary64 `Q`, and wrong without the step | `floor52.smt2` | unsat, sat; 2.9 s | unsat, sat; 1.9 s |
| C: 2.5's construction is Java's `%` outside the band, at binary16 and binary32, and wrong without the correction | `fp_remainder_draft.smt2`, `lemma16.smt2`, `lemma32.smt2` | part 346p2 | part 346p2 |

**The files.**

1. `fp_round.smt2` gains claims A and D, two checks each, rendered by `fpRound()` from
   `conv52.smt2`'s and `floor52.smt2`'s text, with `C` built from `VarkaScaleRoundingLowering`'s
   `TWO_52_BITS` (346c makes that field package-private, not `private` as on its branch) as the
   file's binary64 checks build theirs from `VarkaRoundingLowering.TWO_52`; the README row's text
   gains "and the cast's conversion `bits(t + 2^52) - bits(2^52)` exact on every integral `t`
   below `2^52`, wrong at `2^52 + 1`, and the floor the rounding and the remainder build from
   `rint` (VARKA-346)". A few seconds per solver: no list in `dev/varka_prove.sh`.
2. `fp_remainder.smt2`, new, `fpRemainder()`: claim B's two checks (from `sum32b.smt2`) and
   nothing else. Its header comment, rendered by `fpRemainder()`, ends with the line "Claim C,
   the remainder's construction, is part 346p2 of VARKA-346, January 2027 (the owner's E1 of
   28 November 2026); until then 346a's tests hold it." Claim B runs under cvc5 only in the
   linters' job: add `fp_remainder.smt2` to `CVC5_IN_LINT` in `dev/varka_prove.sh` with a
   comment giving the times above. Its README row: "the float sum of `pmod` over `FLOAT`,
   `RN32(RN64(a + b)) = RN32(a + b)`, and wrong narrowed toward zero (VARKA-346); the
   remainder's construction is held by 346a's tests until part 346p2, January 2027".

**Proving it** (JVM where named):

1. `VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'` writes the
   files; then `build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'` with no variable: no failure.
2. `dev/varka_prove.sh --solver both fp_round.smt2 fp_remainder.smt2` (no JVM): every verdict as
   expected; `dev/varka_prove.sh --lint` (no JVM): passes, `fp_remainder.smt2` under cvc5.
3. `python3 dev/varka_msg_check.py size --base master --head HEAD`: an SMT-LIB file counts
   nothing; the Java renderer and the README do (about 150 to 250 lines).
4. `dev/varka_ops/pre-pr-slot.sh <w>`: `pre-pr: passed on <sha>`.

**Must not touch:** the lowerings; claim C (346p2's); a claim whose statement is changed to make
it decide must still say what the lowering emits (the README row names any weakening).

#### 346p2: claim C (January 2027, full strength; not a gate before then)

*[E1 rework, 28 November 2026: by the owner's decision E1 (a) of 28 November 2026 this part
waits for a full-strength builder, back in January 2027. Nothing waits on it: 346a ships before
it with its differential tests, 346p1 and 346m do not read it, and the row is marked by 346m
without it (346m step 6). Its text is the old sheet's, kept for January.]*

**Builder: January 2027, full strength; not a gate before then.** Branch
`varka-346p2-remainder-proof` from master once 346p1 is merged (it adds claim C to the
`fp_remainder.smt2` that 346p1 creates).

**What was run here, with no JVM.** Claim C did not decide. cvc5 (`--fp-exp`, 280 s a check):
the binary16 claim `unknown`, a check with the window one binade wider `unknown`, the refutation
without the correction `sat`, the binary32 claim `unknown`, the run stopped at 900 s. z3 (280 s
and 300 s): the binary16 claim and `lemma16.smt2` and `lemma32.smt2`, the positive finite case
alone, all `timeout`. The construction itself is not in doubt: `scratch-346/rem16_sample.py`
runs it at binary16 on exact integers against the exact `fmod` and prints `rem16_sample: 1989440
checks, 0 failures` (the same with the correction dropped: 157453 failures), and
`verify_346.py`'s 2.5 checks pass at binary64; what is missing is a form a solver decides. The
same sample with the window one binade wider also prints 0 failures (2147323 checks), so the
window is a sufficient bound and maybe not a tight one, as item 0.17 found at binary64 (none of
1638331 declined pairs differed); the draft's "a binade wider is wrong" check is therefore not a
refutation and is dropped. Widening the window would be a lift with its own proof, not this
row's.

**The claim.** The statement is `fp_remainder_draft.smt2`'s: over `x`, `y` of the format, `y`
not zero, the lane not declined (`ax` finite, `Q = RN(ax / ay) >= 2^p`), the construction `out`
equals `spec = sign(x) * fmod(ax, ay)` with `fmod` from `fp.rem` corrected by `ay` where it is
negative; and the refutation without the correction. The floor is `fp.roundToIntegral RTN`,
which claim D (346p1) proves the lowering's stepped `rint` is; the FMA is `fp.fma RNE`. The
builder's task is to make it decide: split it into lemmas, for instance the special values
(`ax < ay`, an infinite `x`, an infinite `y`, a NaN) apart from `lemma16.smt2`'s positive finite
case, and that case by the binade of `Q`. The **decision rule**, numbers only: a claim C check
is in the file if it is `unsat` (or `sat` for a refutation) under z3 or cvc5 within 600 s on the
laptop; a binary16 check needs cvc5's `--fp-exp` (cvc5 1.4.1 refuses the format otherwise, and
`dev/varka_prove.sh` does not pass it), so the binary16 checks run under z3, or the runner gains
the flag for this file, the builder's choice recorded in the file's header. If no form of the
binary16 positive case decides within 600 s, claim C is not in the file and the header line of
346p1 stays; the remainder's proof stays 346a's tests.

**What makes it hard.** The remainder's claim in SMT-LIB's floating-point theory, which neither
pinned solver decides as stated: it must be split into lemmas that converge, which a builder
cannot be told how to find.

**Proving it:** 346p1's four steps, over `fp_remainder.smt2`; the header line of 346p1 is
deleted, and the README row's last clause becomes "and the remainder's construction, outside its
band, at binary16 and binary32 (VARKA-346, part 346p2)".

**Must not touch:** the lowerings; claims A, B and D.

#### 346m: the measurement, the census's bands and the row marked (finished sheet)

**Builder: Sonnet-class.** Branch `varka-346m-close` from master once 346b1a, 346b1b, 346b2,
346a, 346c and 346p1 are merged *[E1 rework, 28 November 2026: not 346p2, January 2027's, by
the owner's E1]*. A results-and-plan pull request: no code.

**Steps.**

1. The lead dispatches, on vecbricks (`benchmarks-run-on-vecbricks`), the two classes at both
   widths: `VarkaRoundingBaselineBenchmark` and `VarkaFloatingBaselineBenchmark`, each at 256
   bits and at 128 bits (`extra-java-options=-XX:MaxVectorSize=16`, as the committed
   `-128bit-provenance.txt` files record), on an EPYC 7763.
2. Splice the row's sections only into the four committed files with
   `dev/varka_bench_sections.py splice <old> <new> --sections "<titles>"`, the titles being the
   eighteen sections `score_346.py` names (below) and the two `FLOAT round(f, 1)` ones.
3. Score: commit `/home/max/varka-ops/m8-row-plans/scratch-346/score_346.py` as
   `sql/varka/plans/m8/results/VARKA-346/score.py`, with the Apache license header of a
   neighbouring Python file under `dev/` (its 16 `#` lines, from `dev/varka_bench_sections.py`
   lines 2 to 17) placed first, above the script's docstring: `dev/.rat-excludes` excludes
   `.*\.txt` and `.*\.tsv` but no `.py`. `score.txt` needs no header. Run it from
   `sql/core/benchmarks`:

        python3 ../../varka/plans/m8/results/VARKA-346/score.py \
          VarkaRoundingBaselineBenchmark-jdk25-runner-epyc7763-results.txt \
          VarkaRoundingBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt \
          VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-results.txt \
          VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt

   Its rules are numbers: P2 every rounding and cast section at 256 bits at a ratio of 10.0 or
   more; P3 every remainder section at 256 bits at 3.0 or more; P4 every rounding section at
   128 bits at 1.1 or more and every remainder section there below 1.1 (the cast's 128-bit
   sections are unscored: they decline by the two-lane refusal, 11.3 decision 7). Run on
   today's baseline files it prints `P2: FAILED`, `P3: FAILED`, `P4 rounding: FAILED`,
   `P4 remainder: HELD`, every ratio between 0.94 and 1.03, as a baseline must. Its output is
   committed beside it as `score.txt`, and section 10 quotes each verdict, held or failed; a
   failed prediction is recorded, never re-run until it holds.
4. P1: `python3 dev/varka_bytes_moved.py <the merge base of 346b1a> --head HEAD --ignore
   option_arms` lists no existing shape's digest; P5: the PR Build's time for the row's suites
   (from its test report) under 60 s, and 346b2's sweep-step time under 600 s.
5. The census's band column: each form's line in `sql/varka/plans/m8/census_curated.txt` gets its
   band in the reason text, from 2.2's table (`round`, `bround`, `truncate`: "declines a
   non-integral value in `[L_s, K_s)`"), 2.4 (the cast: "declines a non-integral value from `L_s`
   and every value from `2^52` below `10^(p - s)`") and 2.5 (the remainder: "declines `|x / y| >=
   2^53`"); then `dev/varka_regen_generated.sh --all` and `python3 dev/varka_census.py --check`.
6. Mark the row in `PLAN.md`: **Done** if every form of section 1 but the `FLOAT` rounding
   output fuses (or declines its band) and the owner's answer to question 1 is (a); else **Done
   in part**, naming what is left. *[E1 rework, 28 November 2026.]* Part 346p2 does not count
   as left: the mark is **Done** by that rule, and the note adds "part 346p2, the remainder's
   SMT claim C, is January 2027's, by the owner's E1 of 28 November 2026; not a gate". Section
   10's record says the same of section 9's "proven at binary16 and binary32" for
   `fp_remainder.smt2`: not met until 346p2, by E1, with 346a's tests holding the remainder.
7. `python3 dev/varka_plan_lint.py --offline`: `0 finding(s)`; `python3 dev/varka_quote_check.py
   sql/varka/plans/m8/VARKA-346.md`: 0 orphans.
8. *[Fix round, 28 November 2026: the owner's answer to question 3 of section 12.]* Add the
   scope item that section 12 question 3 writes out to `sql/varka/plans/m10/SCOPE.md`, as the
   item after the last, its `<n>` computed by the command given there; the remainder's census
   reason of step 5 ends "(`m10/SCOPE.md` item <n>)". Then rerun step 7's two commands.

**Must not touch:** any code; any other section of the four results files (step 2's splice
keeps them); any item of `m10/SCOPE.md` but the one step 8 adds.

## 12. Questions for the owner (28 November 2026)

*Questions 1 to 3 were answered by the owner on 28 November 2026; each answer follows its
question. Question 4 is moot by decision E1, question 5 (E1 rework, 28 November 2026).*

1. **The `FLOAT` output of `round`, `bround` and `truncate`.** Options: (a) not in this
   milestone, declined by name, moved to `m10/SCOPE.md` item 140; (b) built here at full
   strength with a proof file. **Recommended: (a)**: the row moves no census pair either way,
   and item 4 of `PLAN.md` 1.1 names the floating-to-decimal answers, which 346c gives.
   **Default applied: (a).** **Answered on 28 November 2026: (a), the default:** not built,
   moved to `m10/SCOPE.md` item 140, declined by name.
2. **SLEEF's `xfmod` port as the remainder's third oracle** (section 3 item 7). Options: (a)
   not built: Java's `%` and `BigDecimal`'s exact remainder are two oracles already; (b) ported,
   with its Boost licence notice. **Recommended: (a). Default applied: (a).** **Answered on
   28 November 2026: (a), not built.**
3. **The remainder's band `|x / y| >= 2^53` has no scope item** (section 8, flag 4). Options:
   (a) a new item in `m10/SCOPE.md`, "the floating remainder past `2^53`, by SLEEF's multi-pass
   loop (`READING_SLEEF.md` 4)"; (b) folded into item 140. **Recommended: (a)**: item 140 holds
   the floating-to-decimal residue, a different construction. **Default applied: (a)**, written
   by 346m as the file's next free item number, which its census reason names. **Answered on
   28 November 2026: (a), a scope item, written by 346m.** Its text, word for word (346m step 8
   places it, after the file's last item and before its section "5. Ordering"):

        ### Item <n>. The floating remainder past a quotient of 2^53

        *Added <the date 346m merges>, from `m8/PLAN.md` row 346, on the owner's answer of
        28 November 2026 to question 3 of `m8/VARKA-346.md` section 12.* Row 346's
        `FloatRemainder` (`%`, `mod`, `try_mod` and `pmod` over `DOUBLE` and `FLOAT`) takes one
        truncated quotient and one FMA, exact while `|x / y|` is below `2^53`
        (`m8/VARKA-346.md` 2.5); a batch with a live lane at or past it declines under
        `REMAINDER_BAND`, and the row engine answers. SLEEF's `xfmod` covers the rest by a loop
        of at most 21 such passes, each exact, that leaves when every lane's remainder is below
        `|y|` (`m8/READING_SLEEF.md` 2 and 4). **Done when** the remainder fuses at every
        quotient, behind an emit option whose losing arm is the declined band; its pass loop
        has a proof file of one pass and a bound on the pass count for binary64 and binary32;
        `VarkaFloatingBaselineBenchmark` times a section whose operands lie past the band; and
        the census reason of the four forms no longer names the band.

   `<n>` is one more than the last item number of `m10/SCOPE.md` when 346m runs, which this
   prints (140 on `6dafb8bd2f6`, so `<n>` is 141 unless an item was added since):

        grep -o '^### Item [0-9]*' sql/varka/plans/m10/SCOPE.md | awk '{print $3}' | sort -n | tail -1
4. ***New, 28 November 2026.* If the remainder's SMT claim does not decide** (346p's decision
   rule). Options: (a) the remainder's proof is its tests (346a's five emitter tests and the
   exponent-pair sweep, against Java's `%` and `BigDecimal`'s exact remainder), the README row
   and section 10 say so, and the row closes; (b) the row stays **Done in part** until a proof
   decides. **Recommended: (a)**: the other two claims and every rounding lowering are proven or
   exhausted, and the remainder is exact by 2.5's argument. **Default applied: (a).**
   **Answered on 28 November 2026: moot by the owner's decision E1 (question 5).** Claim C
   moves to part 346p2, January 2027, and is no gate before then; the row closes on 346m's rule
   with 346a's tests holding the remainder, which is option (a) in effect, and in January
   claim C either decides by 346p2's rule or stays 346a's tests, with no question left.
5. ***E1 rework, 28 November 2026.* E1: the proof parts that need a full-strength builder.**
   From 1 December 2026 the project is built by Sonnet-class agents only, for one or two
   months. Options: (a) move the proof parts that need a full-strength builder to January 2027,
   when one is back, and ship the parts they prove before them with their differential tests
   and fuzz coverage; (b) build them before 1 December; (c) hold the parts they prove until the
   proofs land. **Answered on 28 November 2026: (a).** In this row: claim C of the old part
   346p is part 346p2, "January 2027, full strength; not a gate before then"; claims A, B and D
   stay Sonnet-class as part 346p1; nothing waits on 346p2 (11.2). In rows 362 and 363 the same
   decision moves 362b3 and 363b3 (`VARKA-362.md` 11.9, `VARKA-363.md` 11.9). With the lead's
   split of 346b1 on the same day, the only full-strength work left in this plan before January
   is the lead's registry run on 346b1a (11.2).

## 13. Stale against the tree, found while planning (28 November 2026)

1. **Section 10 on master stops at the baseline.** The records of 346b, 346b2, 346b3, 346a and
   346c exist only on the branches of 11.1.
2. **Section 10's decision 1** says no branch builds row 342's `Fma` and row 350 is Planned.
   `Fma` is on master, and row 350's casts to `BIGINT`, to `BOOLEAN` and between decimals are
   merged (#1700).
3. **Section 2.6** gives `FloatRemainder` a `Kind` and a `ZeroDivisor` and adds `FLOOR` to
   `RoundToIntegral`. The branches built neither: decisions b, c and d of 346a's record.
4. **Section 5's order** (346a first) is replaced by 11.3's decision 1.
5. **The row's note in `PLAN.md`** lists as left "the runner results at both widths"; they are
   on master since #1597.

*Added by the second planning pass, 28 November 2026:*

6. **346a's branch would fail master's tests as it stands.** It leaves
   `VarkaFloatingLaneSuite.scala:599` asserting "a floating remainder (row 346)", which the
   branch deletes (R2 of `dev/varka_push_check.py` and the suite itself fail), and the four
   remainder sections of `VarkaFloatingBaselineBenchmark` at `Fusion.NONE`, which
   `VarkaFloatingBaselineQueriesSuite` fails once they fuse; it also has no
   `VarkaReproductionSuite` round trip and no `VarkaEmitterBudgetSuite` row (master's R1 names
   both since #1687) and no `docs/sql-varka.md` status name. Its record's "the query suite never
   ran" is how these went unseen. 346a's sheet adds all five.
7. **346c's query suite pins no two-lane setting**, so the option matrix's two-lane
   configurations, under which the cast declines by `TWO_LANE_REASON` (by `TWO_LANE_READ_REASON`
   once 597b merged), would fail its kernel
   tests; 346c's sheet wraps them. The same holds for prediction 4 of section 4: at 128 bits on
   x86 the cast declines by that refusal (11.3 decision 7), so "the cast still faster than the
   row path" cannot hold there; 346m's scoring leaves the cast's 128-bit sections unscored.
8. **Two m9 plans collide with this row's status bits.** *[Fix round, 28 November 2026,
   finding 4: the line numbers corrected and row 685 added.]* After 346b1 and 346a the runner's
   bits are 10, 11 and 12 (11.3 decision 2), so row 682 takes `1 << 13`. *[E1 rework, 28
   November 2026: 346b1's bit 8 and the runner's first move are part 346b1a's; the lead's text
   below says 346b1a.]* The lead edits, in
   `/home/max/varka-ops/m9-row-plans/` (and in `sql/varka/plans/m9/` once those plans merge):
   - `VARKA-682.md` lines 154 and 155: "Statuses in use: `1 << 8`, `1 << 9`, `1 << 10`."
     becomes "Statuses in use: `1 << 10`, `1 << 11`, `1 << 12` once row 346's parts 346b1a and
     346a merged (346b1a takes kernel bit 8, 346a bit 9)."
   - `VARKA-682.md` line 388: `VarkaKernelRunner.STATUS_BATCH_ARGS = 1 << 11` becomes
     `VarkaKernelRunner.STATUS_BATCH_ARGS = 1 << 13`.
   - `VARKA-685.md` line 529: `grep -c 'STATUS_BATCH_ARGS = 1 << 11'` becomes
     `grep -c 'STATUS_BATCH_ARGS = 1 << 13'`.

   Rows 686, 688, 689 and 690 name `STATUS_BATCH_ARGS` with no number and need no edit. The
   check: `grep -n '1 << 1[0-3]\|1 << [89]' m9-row-plans/VARKA-6*.md` prints those three lines
   and `VARKA-684.md` line 163 (`SWEEP_ROWS = 1 << 16`, not a status) and nothing else.
9. **Section 3 item 6's binary16 checks under cvc5** are refused by cvc5 1.4.1 without
   `--fp-exp` ("only Float32 (8/24) or Float64 (11/53) types are supported in default mode"),
   and `dev/varka_prove.sh` passes no such flag; 346p2's sheet handles it.
10. **346b1's branch passes the cap**: `python3 dev/varka_msg_check.py size --base
    0345dd29a53^ --head 0345dd29a53` prints 1004 counted lines; its 54-line plan record is most
    of the excess, which 346b1's sheet cut. *[E1 rework, 28 November 2026: the lead split the
    part into 346b1a, about 850, and 346b1b, about 180 (11.4). The owner confirmed this cut on
    28 November 2026: the band's refusal ships in 346b1a, so its guard is tested from the start.]*
11. **Section 3 item 6's refutation "one binade wider" may not exist.** At binary16 the
    remainder construction gives the exact `fmod` with the window at `2^12` as well (346p2's
    sheet, `rem16_sample.py`), as item 0.17 found nothing different in the declined band at
    binary64: the `2^53` window is sufficient, perhaps not tight, so a check that a wider
    window is wrong would not be `sat`. 346p2 states only the refutation without the correction.
