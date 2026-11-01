# VARKA-351: Decimals in the existing types' functions, and the rounding functions

## 0. What exists now (admission check)

From master at `9aa6ee74c63`, reworked over `7ebd03ba6e8` (30 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/` or `sql/api/src/main/
scala/org/apache/spark/sql/` as named), and from the plans this row builds on: on master
`VARKA-157.md`, `VARKA-340.md`, `VARKA-344.md`, `VARKA-348.md`, `VARKA-349.md`, `VARKA-350.md` and
`VARKA-359.md`; and two plans not yet on master, read on their branches: row 360's plan
(`varka-360-361-plan` at `ada77eb513a`) and row 363's plan (`varka-362-363-plan` at `b074cc19a17`,
in an open pull request), cited here in words. The row's text is the milestone plan's 2.8 ("Casts
and rounding below 19") and the G3 and F3 wave rows of 4.1. The counts marked **[A]** are from
`results/VARKA-351-admission.txt`, a Python script and its output, run on the laptop for this plan;
nothing ran Spark. A claim marked **[I]** is inferred, not read or run.

**The rounding functions.**

1. **The result type of every two-argument form is one rule.** `round`, `bround`, `truncate` and
   the two-argument `floor` and `ceil` (`RoundFloor`, `RoundCeil`) are `RoundBase`
   (`expressions/mathExpressions.scala:1694`, `:1925`, `:1965`, `:2005`, `:372-382`, `:675-685`),
   whose type over `DECIMAL(p, s)` at scale `k` is (`:1708-1725`): for `k >= 0`,
   `(min(p - s + 1 + min(s, k), 38), min(s, k))`; for `k < 0`, `(min(max(p - s + 1, 1 - k), 38),
   0)`. So at `k >= s` the type is `(min(p + 1, 38), s)` and the value is the operand's, a relabel:
   `DECIMAL(18, s)` becomes `DECIMAL(19, s)`, the row text's "precision 19". Over every `(p, s, k)`
   with `p <= 38` and `-40 <= k <= 40` **[A, item 1]**: of the 64-bit inputs (`p <= 18`), 608
   relabels, 4364 negative scales and no positive-scale division give a result above precision
   18; the 128-bit inputs give 3420 divisions and 6120 negative scales whose result is within 18.
2. **The value, and when it raises.** At `k >= 0` the value is `toPrecision(p', s', mode)`
   (`:1780`; generated `:1852`, `nullOnOverflow` true), which `Decimal.changePrecision` computes
   as a truncating division of the unscaled long by `10^d`, `d = s - k`, adjusted by the dropped
   digits per mode (`types/Decimal.scala:399-441`), or `BigDecimal.setScale` above 18 digits
   (`:458-464`); the comment "Overflow cannot happen" holds by item 1's type. At `k < 0` it is
   `Decimal(bd.setScale(k, mode), p', 0)` (`:1782`, generated `:1856`), whose `set` raises
   `NUMERIC_VALUE_OUT_OF_RANGE.WITHOUT_SUGGESTION` when the rounded value has more than `p'` digits
   (`Decimal.scala:119-136`), in every mode. `ansiEnabled` touches only the integral operands
   (`mathExpressions.scala:1784-1806`), so over a decimal the three modes give one answer. The
   shapes that can raise **[A, item 1]**: over a 64-bit input only `ceil(x, k)` and `floor(x, k)`
   at `k <= -38` (567 shapes each, a value rounded away from zero to `10^38`); over a 128-bit
   input `round` and `bround` at `(38, 0)` for `k` from -38 to -1 (38 shapes each) and the two
   directed forms at 1807 shapes each; `truncate` never.
3. **The one-argument `floor` and `ceil`** over `DECIMAL(p, 0)` are the operand, and otherwise
   `Decimal.floor` and `ceil` (`mathExpressions.scala:277-282`, `:296`, `:603-608`, `:622`), that
   is `toPrecision(min(p - s + 1, 38), 0, FLOOR or CEILING)` (`Decimal.scala:564-574`): the
   two-argument form at `k = 0`, its type differing only at `s = 0`.
4. **A non-decimal operand of the two-argument `floor` and `ceil` is cast.** Their inputs are
   `(DecimalType, IntegerType)` (`mathExpressions.scala:375`, `:678`), and both coercions cast a
   numeric operand to `DecimalType.forType` (`analysis/TypeCoercion.scala:230`,
   `analysis/AnsiTypeCoercion.scala:181`): `(3, 0)`, `(5, 0)`, `(10, 0)`, `(20, 0)`, `(14, 7)` and
   `(30, 15)` for the six types (`types/DecimalType.scala:125-139`). `round`, `bround` and
   `truncate` take any numeric (`mathExpressions.scala:1727`) and cast nothing.
5. **A null scale** gives null for every row without reading the operand (`:1761`, `:1887`).

**The functions over decimal seconds.**

6. **`time_from_seconds(x)`** keeps a decimal operand as it is (`NumericType`,
   `expressions/timeExpressions.scala:880-886`), and `timeFromSeconds(Decimal)` is
   `longValueExact` of `x * 10^9` (`util/DateTimeUtils.scala:1151-1154`) under a check that the
   result lies in the day (`:1119-1135`): it raises in every mode for a fraction finer than a
   nanosecond, for a value outside `[0, 86400)` and on overflow. So with `s` the scale, a lane is
   accepted exactly when `0 <= u < 86400 * 10^s` and, for `s > 9`, `10^(s - 9)` divides `u`; the
   result is `u * 10^(9 - s)` or `u / 10^(s - 9)` nanoseconds, written into a `TIME(6)`
   (`:846`) with no truncation to the microsecond **[I, from those lines]**.
7. **`make_time(h, m, sec)`** is `makeTime` (`timeExpressions.scala:621-643`), its seconds cast to
   `DECIMAL(16, 6)` (`:631`). It reads the unscaled long, `floorDiv` and `floorMod` by `10^6`, and
   `LocalTime.of` (`DateTimeUtils.scala:1091-1117`), raising in every mode unless `h` is 0 to 23,
   `m` 0 to 59 and `0 <= u < 6 * 10^7`; then the value is `h * 3600 * 10^9 + m * 60 * 10^9 + u *
   1000` with no division at all **[I, from those lines]**.
8. **`make_timestamp_ntz` with six fields** is `MakeTimestamp(..., dataType = TimestampNTZType)`
   (`expressions/datetimeExpressions.scala:3870-3888`), its seconds cast to `DECIMAL(16, 6)`
   (`:4150`), `failOnError` from the session's ANSI flag (`:4117`); `try_make_timestamp_ntz` with
   six fields is the same with `failOnError = false` (`:3934-3953`), not a `TryEval`. Its core
   (`expressions/DateTimeExpressionUtils.java:93-121`) **narrows the unscaled long to an int**,
   `(int) secAndMicros.toUnscaledLong()` (line 99, under the comment "8 digits cannot overflow
   Int", while the type has 16), then takes seconds and nanoseconds by `floorDiv` and `floorMod`;
   60 seconds with no fraction rolls to the next minute, and any other invalid field is a
   `DateTimeException`, null when `failOnError` is false and raised otherwise (`:4165-4176`). So
   `4324.967296` seconds, unscaled `4324967296`, is read as 30 seconds and accepted **[A, item 5;
   from the code, not run]**. The micros come from `localDateTimeToMicros`, whose
   `multiplyExact` raises an `ArithmeticException` past the long in every mode
   (`sql/api/.../SparkDateTimeUtils.scala:136-145`, `:212-214`).
9. **`make_dt_interval(d, h, m, sec)`**, row 103's (below), casts its seconds to `DECIMAL(18, 6)`
   (`expressions/intervalExpressions.scala:537`), the shorter forms taking the literal
   `Decimal(0, 18, 6)` (`:523`), and computes `u + d * D`, then `+ h * 3.6 * 10^9`, then `+ m * 6 *
   10^7` by `addExact` and `multiplyExact` in that order (`util/IntervalUtils.scala:786-803`),
   raising in every mode at the first overflow.
10. **`SECOND` over `TIMESTAMP_NTZ` and over a day-time interval.** `extract(SECOND FROM ntz)` is
    `SecondWithFraction` (`datetimeExpressions.scala:680-691`, `:4593`), `DECIMAL(8, 6)` whose
    unscaled value is `floorMod(t, 6 * 10^7)` at UTC (`VARKA-340.md` 0, item 2).
    `extract(SECOND FROM dt)` is `ExtractANSIIntervalSeconds` (`intervalExpressions.scala:119-123`),
    `DECIMAL(8, 6)` of `t % (6 * 10^7)` (`util/IntervalUtils.scala:66-68`): Java's truncating
    remainder, negative for a negative interval, row 103's (below). Neither raises.
11. **Both coercions insert the seconds' cast.** `TypeCoercion.implicitCast` casts any numeric to
    a concrete numeric target (`TypeCoercion.scala:232`), and the ANSI form casts where
    `canANSIStoreAssign` allows (`AnsiTypeCoercion.scala:207-213`), which a numeric pair passes
    **[I]**. So the decimal operand of items 7 to 9 arrives as `Cast(x, DECIMAL(16, 6))` or
    `(18, 6)` from every other type: row 350's cast below 19 and row 364's above.

**What Varka has, and what the prerequisites give.**

12. **Today every form declines.** `timeFromSeconds` takes a long-lane count and a decimal declines
    at its leaf (`VarkaTimeCompiler.java:395-416`); `make_time` is in the TIME target table
    (`:226`) and declines as not lowered (`:433-436`). `MakeDate` exists with its non-ANSI null
    (`varka/VarkaVectorIR.java:1362`), `Overflow` has `WRAP`, `FAIL` and `NULL` (`:415`),
    `CompareOp` has no unsigned member (`:385`), `GuardedRange` has no disposal mode (`:536`), and
    `ConstDivide` is exact below `2^52` (`:783`).
13. **The plans this row composes, none of them built.** 348's `Scaled(unscaled, type)` and
    `value()` (`VARKA-348.md` 2.1), every decimal consumer's one entry point; 157's root
    `WidenDecimal` (`VARKA-157.md` 2.1); 350's two magnitude divisions by `10^k`, `tdiv` and `hdiv`,
    through a pre-shift and `ConstDivide` by `5^k`, with `decimalDivisionForm`'s `GUARD` and
    `LIMB_MAGIC` arms for the twelve `(p, k)` past `2^52` (`VARKA-350.md` 2.2); 344's
    signed-remainder rounding of an integral value (`VARKA-344.md` 2.5); 359's `Pair`, unsigned
    compares, `gt128` and pair store (`VARKA-359.md` 2.2, 2.4); 360's sign-extended output and the
    constant-multiplier `WideMul` of 360b (row 360's plan, its 2.4 and 2.6); 363's `ScaleDown`, a
    `HALF_UP` or `DOWN` division of a two- or four-word magnitude by `10^k` for `k` 1 to 39, its
    constants tabled at the bounds `10^38 - 1` and `2 * 10^38 - 2` (row 363's plan, its 2.2, 2.3);
    340's `microsOfDay`, which leaves `SECOND` to this row (`VARKA-340.md` 8, item 1). 481 adds
    `GuardedRange`'s `NULL` mode.
14. **The baseline.** `VarkaDecimalBaselineBenchmark` times a `HALF_UP` rescale down from
    `DECIMAL(38, 10)` to `(18, 4)` on the row path at 315.1 ns a row and the cast of `(18, 4)` up
    to `(38, 10)` at 153.8 ns on the EPYC 7763 at 256 bits
    (`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`); it has no rounding
    function and no function over decimal seconds.

### What differs from the row text and the milestone plan

- **`time_to_seconds` and `second` with its fraction are row 157's.** `VARKA-157.md`'s question 1
  recommended that row 157 carry the two `TIME` producers, `time_to_seconds(t)` and
  `extract(SECOND FROM t)`, and that this row's text drop them; the owner approved that plan, so
  the split is decided and this plan builds neither. `second(x)` itself is an int over every type
  (`SecondExpressionBuilder`, `timeExpressions.scala:457-472`).
- **"Precision 19" is wider than the last three.** The directed two-argument `floor` and `ceil` are
  `RoundBase` too, and besides the relabel at `k >= s`, a negative scale gives a type above 18 in
  4364 of the 64-bit shapes (item 1). All of them wait on 359's store and 360's sign extension.
- **Over a 64-bit operand only the directed forms raise, and only at `k <= -38`** (item 2): the
  row's "a negative-scale `round` raising on overflow in every mode" is a 128-bit `(38, 0)` case
  for `round` and `bround`.
- **`try_make_timestamp_ntz`'s six-field form is in** (item 8): it is `make_timestamp_ntz` with the
  LEGACY disposal, and the census row names this row.
- **`extract(SECOND FROM dt)` and `make_dt_interval` are row 103's** (settled by the lead, 30
  October 2026). Row 103's text has "seconds over the decimal lane" and "the partial sums of
  `make_dt_interval`", and `VARKA-157.md` 1 and 6, approved by the owner, give
  `extract_dt_seconds`'s lowering to 103. The G1 wave row's "103 ... its decimal seconds being
  351's shapes" reads as 103's outputs taking the decimal value this row defines (a `Scaled` of
  `(8, 6)` or an `(18, 6)` operand through 348's `value()`, stored by 157), not as this row
  building them. This plan cites 103 and 157 for both and builds neither; that row 351's text
  still lists them is flagged in section 8.
- **No IR node.** Every form composes existing nodes and those of 359, 360 and 363. The 128-bit
  `HALF_EVEN`, `CEILING` and `FLOOR` are built around 363's `ScaleDown`, which keeps its two modes.
- **`time_from_seconds` needs no division**: an exact division by an odd constant is a multiply by
  its inverse modulo `2^64` (2.6).
- **The census moves later than the row suggests** (section 6): the rounding pairs count only with
  the 128-bit forms, and the two-argument `floor` and `ceil` hold their non-decimal pairs too.
- **Large, in four pull requests**, against the row's medium (section 5; the cell is the
  owner's, flagged in section 8).

## 1. Goal and non-goal

`round`, `bround`, `truncate`, `floor`, `ceil` and `ceiling`, with and without a scale, over a
`DECIMAL(p, s)` of either form at every precision and every literal scale, fuse with answers equal
to Spark's in the three modes, a value of more than 38 digits declining its batch to the row engine
that raises; and `time_from_seconds` over a decimal of either form, `make_time`, the six-field
`make_timestamp_ntz` and `try_make_timestamp_ntz`, and `extract(SECOND FROM ntz)` fuse over their
decimal operand or result. Not this row: `time_to_seconds` and `extract(SECOND FROM t)` over `TIME`
(157); `extract(SECOND FROM dt)` and `make_dt_interval` (103); the casts that bring a seconds
operand to `(16, 6)` (350, 364); the divisions themselves (350, 363); the store
(157, 359); a negative decimal scale; decimal division.

## 2. Design

### 2.1 The value: what the functions read and give

Every decimal operand is `value()` (item 0.13): a `Scaled` up to precision 18 or a 359 `Pair`
above. Every decimal result is one too, so it feeds 348's comparisons and 349's arithmetic and is
stored by 157's `WidenDecimal` or 359's pair store; a 64-bit value of a type above 18 is 360's
sign-extended output. `value()` gains one row per function of this plan, in a
`VarkaDecimalRoundCompiler` beside 350's cast compiler, under a family `DECIMAL_ROUND` whose switch
leaves 348 to 350's forms fused; the time functions' arms sit in `VarkaTimeCompiler`
and the NTZ arms of 340, under their families.

### 2.2 The rounding, one table by shape

With `d = s - k` the digits dropped and `R` the mode (`HALF_UP`, `HALF_EVEN`, `DOWN`, `CEILING`,
`FLOOR` for `round`, `bround`, `truncate`, `ceil`, `floor`):

| shape | when | unscaled value | result |
|---|---|---|---|
| identity | one-argument `floor` or `ceil`, `s = 0` | the operand's | the operand's type |
| relabel | `k >= s` | the operand's | `(min(p + 1, 38), s)` |
| divide | `0 <= k < s` | `R(u / 10^d)` (2.3, 2.4) | `(p - d + 1, k)` |
| negative | `k < 0`, `d <= 18` on a long, `<= 38` on a pair | `R(u / 10^d) * 10^(-k)` (2.5) | `T` |
| constant | `k < 0`, `d` past the operand | `-1`, 0 or 1 by sign and mode, `* 10^(-k)` | `T` |

`T` is `(min(max(p - s + 1, 1 - k), 38), 0)`.

The one-argument `floor` and `ceil` at `s > 0` are the divide row at `k = 0`. A relabel to
precision 19 from a 64-bit operand is 360's sign-extended output; a relabel of a pair is the pair.
A divide whose result is within 18 digits keeps one word, from either operand form. The compiler
decides the shape from the resolved types and the scale literal, so every shape is one kernel in
the three modes. A null scale declines naming row 333's typed null leaf.

### 2.3 The 64-bit division by mode

Over `x`, the operand's unscaled long, with `1 <= d <= 18`:

    m = abs(x)                                    348's compare, negation and blend
    b = 0                                         DOWN
        5 * 10^(d - 1)                            HALF_UP, HALF_EVEN
        IfElse(x > 0, 10^d - 1, 0)                CEILING
        IfElse(x < 0, 10^d - 1, 0)                FLOOR
    n = m + b                                     IntArith(ADD, WRAP), below 2 * 10^18
    q = floor(n / 10^d)                           350's magnitude division, pre-shift and 5^d
    HALF_EVEN: q = q - CondValue(EQ(n - q * 10^d, 0) AND EQ(q AND 1, 1))
    r = IfElse(x < 0, IntNeg(WRAP, q), q)

The bias rounds the magnitude, and the sign returns last, which is what the five modes do: the
half modes are symmetric, and `CEILING` and `FLOOR` round a magnitude up exactly on the side they
move away from zero. `HALF_EVEN` is `HALF_UP` stepped back by one where the quotient is odd and the
biased magnitude divides exactly, which is where the dropped digits were exactly a half. The
construction agrees with `BigDecimal.setScale` on 7557010 checked `(u, d, mode)` at every `p` to 18
**[A, item 2]**; SMT proves it (section 3).

The division is 350's `tdiv` and `hdiv` generalised to a bias, so they become one helper,
`rdiv(x, d, mode)` in `VarkaDecimalCompiler`, 350's two calls its `DOWN` and `HALF_UP` cases; 350
lands first and this row widens the helper. Its dividend bound `(10^p - 1 + b_max) >> d` passes
`2^52` at the same twelve `(p, d)` as 350's for every bias **[A, item 3]**, so
`decimalDivisionForm` serves unchanged: under `GUARD` a batch past `2^(52 + d)` declines (at
`(18, 1)` the guard admits 0.0090 of a uniform column and at `(18, 7)` 0.5765), under
`LIMB_MAGIC` (after 360b) nothing declines. The `HALF_EVEN` product `q * 10^d` is an
`IntArith(MUL, WRAP)` by a constant, below `2 * 10^18`: one `vpmullq` with AVX-512DQ and the
emulation on the EPYC 7763, as 348's rescale.

### 2.4 The 128-bit division by mode

Over a `Pair` `x`, `m` its magnitude by 359's `abs`, and `1 <= d <= 38`:

- **`DOWN` and `HALF_UP`** are 363's `ScaleDown(m, d, round, word)` with the bound `10^p - 1`.
- **`CEILING` and `FLOOR`** add the bias pair `IfElse(sign test, 10^d - 1, 0)` to `m` by 360's
  carry, then `ScaleDown` with `DOWN` at the bound `10^p - 1 + 10^d - 1`, at most `2 * 10^38 - 2`,
  the equal-scale sum's bound 363 tables **[A, item 4]**.
- **`HALF_EVEN`** is `ScaleDown` with `HALF_UP`, then the tie test by multiplying back:
  `t = WideMul(q, 10^d)` (360b's constant multiplier; `q * 10^d` stays below `2^127` **[A, item
  4]**), a tie where `t = m + 5 * 10^(d - 1)` (360's add and 359's word equality), and `q - 1`
  where the tie meets an odd `q.lo`, by 360's borrow. It agrees with `BigDecimal` on 15872 checked
  magnitudes at every `d` **[A, item 4]**.
- **The sign** returns by 359's negation, masked by the operand's sign word.

The quotient is at most `10^(p - d)`, so where the result type is within 18 digits only the low
word is asked of `ScaleDown` and the result is a `Scaled`. The low product columns of 363's
multiply would also give a divisibility test without the product back; it would make
`ScaleDown` give a second value and need its own proof, and is not built.

### 2.5 Negative scales, constants and the raise

- **`d` within the operand's division range** (`d <= 18` on a long, `d <= 38` on a pair): `q` by
  2.3 or 2.4, then `q * 10^(-k)`. On a long `|q * 10^(-k)| <= 10^(p - s) <= 10^18`, so an
  `IntArith(MUL, WRAP)` by the constant cannot wrap **[I, from item 1's type]**. On a pair the
  multiply is 360b's `WideMul` by a constant of one word (`-k <= 18`) or two, and the product is at
  most `10^38`, below `2^127`.
- **`d` past the operand** (`d >= 19` on a long, `d >= 39` on a pair): `|u| < 10^d / 10`, so
  `HALF_UP`, `HALF_EVEN` and `DOWN` give 0, `CEILING` gives 1 where `u > 0` and `FLOOR` -1 where
  `u < 0` (`Decimal.scala:408-410` reads the same at 64 bits); the result is that times
  `10^(-k)`, a long slot up to `10^18` and a constant pair above.
- **The raise.** Where the compiler finds the shape among item 2's (from the types, as the script
  does), the result is checked against `10^38 - 1` - 359's `gt128` pair on a pair, a nonzero lane on
  the constant shape - and disposed `FAIL` in every mode, as 360's class K check is disposed,
  `GuardedRange(CondValue(ovf), 0, 0, FAIL)` added into both words. The declined batch reaches the
  row engine, which raises. Elsewhere no check is emitted.

### 2.6 The functions over decimal seconds

Each arm reads its decimal operand through `value()` and its int fields through the int lane
widened, in a long-driven kernel; "bad" lanes are folded into one condition per arm and disposed as
Spark does.

- **`time_from_seconds(x)`**, `s` its scale, every disposal `FAIL` (Spark raises in every mode):
  - `s <= 9`: bad where `u < 0` or `u >= 86400 * 10^s`; the value `IntArith(MUL, WRAP, u,
    10^(9 - s))`, below `86400 * 10^9`, under `2^47`. On a pair, bad also where `hi != 0`.
  - `s > 9`, `j = s - 9`, up to `s = 18` on a long: bad where `u < 0` or `u`'s low `j` bits are not
    zero; then `q = IntArith(MUL, WRAP, u >>> j, inv(5^j))`, the inverse modulo `2^64`, which is the
    exact quotient wherever `5^j` divides `u >>> j` and lands above `(2^64 - 1) / 5^j` wherever it
    does not; bad where `q >=u 86400 * 10^9` (359's `UGE`), and for `j >= 8` also where `q >u (2^64
    - 1) / 5^j`, the second compare being dead for `j` up to 7. It agrees with Spark's rule on
    39600 checked values **[A, item 6]**.
  - `s > 9` on a pair: the magnitude shifted right by `j` across the two words, `q` from its low
    word as above, and the exactness checked by `WideMul(q, 5^j)` against both words, a product of
    at most 47 by 68 bits **[A, item 6]**.
- **`make_time(h, m, sec)`**: bad where `h` is outside 0 to 23, `m` outside 0 to 59 or `u` outside
  `[0, 6 * 10^7)`; the value `h * 3600 * 10^9 + m * 60 * 10^9 + u * 1000`, `WRAP` and below `2^47`.
  `FAIL` in every mode.
- **`make_timestamp_ntz` and `try_make_timestamp_ntz` with six fields**: `t = NarrowLane(u, INT)`
  widened back, Java's `(int)` (item 0.8); the date `MakeDate(y, mo, d, failOnError)`, its own
  `NULL` tail under LEGACY and TRY; bad fields where `h` is outside 0 to 23, the minute outside 0 to
  59 or `t` outside `[0, 6 * 10^7]`, the upper end being the rolled minute, which the sum below
  adds exactly; disposed by 481's `GuardedRange` mode, `NULL` when `failOnError` is false and
  `FAIL` when true. The micros are `GuardedRange(day, -106751991, 106751991, FAIL)` times `D`,
  exact under the guard, plus `h * 3.6 * 10^9 + mi * 6 * 10^7 + t` by `IntArith(ADD, FAIL)`: 338's
  day guard and 339's checked add, `FAIL` in every mode since Spark's `ArithmeticException` is
  raised in every mode (item 0.8).
- **`extract(SECOND FROM ntz)`**: `Scaled(r, (8, 6))` with `r` the remainder of 340's
  `microsOfDay(t)` by `6 * 10^7` (`ConstDivide` over a dividend below `2^37`, one new proven
  divisor); `6 * 10^7` divides the day, so this is `floorMod(t, 6 * 10^7)`. 340's own sketch,
  two divisions of the nanosecond of the day over divisors already proven, is the alternative with
  no new proof row and one more division; the one-division form is chosen.
Row 103's `extract(SECOND FROM dt)` and `make_dt_interval` (items 0.9, 0.10) take the same
shapes: a `Scaled` of `(8, 6)` for the first, a `value()` operand of `(18, 6)` for the second.

Each is a `Scaled` or a long-lane `TIME` or `TIMESTAMP_NTZ` value, so the outputs are
157's store or the lane's own.

### 2.7 Overflow defences, as the 28 October rule asks

No check reads a value that may have wrapped. The rounding's only multiplies are the `HALF_EVEN`
product back (below `2 * 10^18`, or `2^127` on a pair) and the negative scale's `q * 10^(-k)`
(below `10^18`, or `10^38` on a pair): check-after with no wrap possible. The biased magnitude is
below `2 * 10^18` on a long and `2 * 10^38` on a pair, unsigned. `time_from_seconds`' multiply by
`10^(9 - s)` is guard-the-inputs (the day bound first); its inverse multiply wraps by design and its
result is read only through the two compares that the inverse's lemma makes exact. The functions
over seconds guard each field before its multiply and add with `FAIL`. The planted fault for row
504: the `HALF_EVEN` step applied to the unbiased quotient, refuted by the proof and failed by the
named ties of section 3.

### 2.8 At 128 bits, compiler, reference, cost, warm-up

At two long lanes on x86 every compare and blend is the Java fallback (`VARKA-359.md` 2.6): a
condition root declines by row 503's rule, a projection fuses and is measured against the row
path, declining with its measured reason if it loses. The reference evaluator, the IR fuzzer and
the canonical parser need nothing, there being no new node; the IR fuzzer's grammar gains the
compositions as drawn trees. The budget weighs them by their nodes; the cost model gets a feature
per shape on row 352's awaiting-refit list; the warm-up's long block gains the inverse multiply and
the five biases, held by `VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The shape table**, a pure test over every `(p, s, k)` with `p <= 38` and `-40 <= k <= 40`: the
   compiler's result type equals `RoundBase.dataType`, and its raise predicate equals item 0.2's,
   the script's counts pinned.
2. **The edges, through each mode against Spark's `eval` and generated code**: the half, the half
   minus and plus one, `10^p - 1` and its negation, zero, one and minus one at every `d`; `CEILING`
   and `FLOOR` at an exact multiple (no step); `HALF_EVEN` at an odd and an even tie; on a pair, a
   low word with its sign bit set under a high word of 0 and a borrow across the words in the
   `HALF_EVEN` step; the twelve `(p, d)` at the first value `GUARD` rejects; each negative-scale
   raise shape at the first value that raises and the one below it.
3. **The seconds functions' named cases**: `time_from_seconds` at `86399.999999`, `86400`, -0.000001
   and a fraction finer than a nanosecond at scales 10 and 18 and on a pair at scale 20;
   `make_time` at 59.999999 and 60; the six-field `make_timestamp_ntz` at 60 (the rolled minute,
   across a day and a year), 60.000001, `4324.967296` (read as 30 seconds, Spark's answer through
   the `(int)` narrowing) and the day edges 106751991 and 106751992 with late times, in the three
   modes; `extract(SECOND FROM ntz)` over `Long.MIN_VALUE`, `Long.MAX_VALUE`, -1 and the minute's
   edges.
4. **SMT, over integers.** A file `sql/varka/proofs/decimal_round.smt2`, run under Z3 and cvc5:
   2.3's construction equals each mode of `u / 10^d` for every `|u| <= 10^18 - 1` and `d` from 1 to
   18, the wrap stated by `mod 2^64`, the bias's headroom and the dividend bounds; the negative
   scale's no-wrap bound; on the pair, the bias within `2 * 10^38 - 2`, the `HALF_EVEN` tie test
   equal to `BigDecimal`'s for every `n < 10^38` (linear, `10^d` a constant) and the product back
   below `2^127`; `time_from_seconds`' inverse lemma for `j` 1 to 29 (`q = N * inv mod 2^64` is
   linear with a free quotient word) and its accepted set equal to item 0.6's; the `make_time`
   and `make_timestamp_ntz` sums equal to Spark's checked arithmetic over the
   guarded ranges. `6 * 10^7` joins `long_divide.smt2`'s divisor list. 350's `5^k` file is reused,
   not repeated. Solver times stated before and after.
5. **Coverage rows**, a "decimal rounding" family over `d9` (`DECIMAL(9, 2)`), `d18`
   (`DECIMAL(18, 4)`) and 359's `d38` and `d3810`: each function at scales giving each shape -
   `round(d18, 2)`, `round(d18, 6)` (precision 19), `round(d18, -2)`, `bround`, `truncate`,
   `floor(d18)`, `ceil(d18, 1)`, `ceiling(d3810)`, `round(d38, -1)` (a raise shape), `floor(i, 2)`
   and `ceil(l, 1)` over the cast - and a "decimal seconds" family: `time_from_seconds` over a
   `(16, 6)` and a `(20, 10)` column, `make_time`, both six-field forms and
   `extract(SECOND FROM ntz)`, each naming its verification for row 490's check.
6. **The differential and the matrix**: the coverage differential under both consumers, the golden
   suite's Varka arm, and the option matrix dispatched on each pull request's branch.
7. **The generated files** in `ADDING_A_TYPE.md`'s order; `emitted_bytes.json` adds rows and moves
   no existing shape.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code**, as sections of row 377's
`VarkaDecimalBaselineBenchmark`, named so their labels survive the lanes landing, each in range for
its mode: at `(18, 4)` `round(x, 2)`, `bround(x, 2)`, `truncate(x, 2)`, `floor(x)`, `ceil(x, 2)`,
`round(x, 6)` (the relabel to 19) and `round(x, -2)`; at `(38, 10)` `round(x, 4)`, `bround(x, 4)`,
`floor(x)` and `round(x, -2)`; over new columns, `time_from_seconds` of a `(16, 6)` and a `(20, 10)`
column within the day, `make_time` and the six-field `make_timestamp_ntz` over int and `(16, 6)`
columns, and `extract(SECOND ...)` over an NTZ column. Run by `benchmark.yml` on the EPYC 7763 at
256 and 128 bits, committed beside the class's files.

Predictions, registered before the build:

1. **The 64-bit rounding at 256 bits** runs 5 to 15 times faster than the row path, which builds a
   `BigDecimal` a value, as 350's casts are predicted to; `bround` and the directed forms within 1.3
   times of `round`; at `k = 2`, a `GUARD` pair, nearly every batch of the class's uniform values
   declines until `LIMB_MAGIC`.
2. **The relabel to 19** costs the pair store over the 64-bit store, within 1.5 times of
   `round(x, 2)`.
3. **The 128-bit rounding** at `(38, 10)` by `10^6` runs 5 to 20 times faster than the row path,
   which pays about what the cast to `(18, 4)` does (315.1 ns a row,
   `VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`); `bround`'s product back costs
   1.3 to 2 times `round`.
4. **The seconds functions** at 256 bits run 5 to 20 times faster than the row path, which builds a
   `LocalTime` or `LocalDateTime` or a `BigDecimal` a row; `time_from_seconds` on a pair within 2
   times of the 64-bit form.
5. **At 128 bits on x86** the condition roots decline (row 503) and the projections beat the row
   path by 2 to 5 times, as 359's prediction 5.
6. **No existing shape's bytes move**; the `option_arms` digests do not move, no option being
   added.

## 5. Order, files and size

1. **The baseline addition** (4), its own pull request.
2. **351a, the 64-bit rounding**, after 157, 348 and 350a (`rdiv`, `GUARD`): the identity,
   relabel within 18, divide and negative shapes whose operand and result are both within 18, every
   mode, `decimal_round.smt2`'s 64-bit half, the shape table, the edges, their coverage rows.
3. **351b, the seconds functions within 18**, after 351a's helper, 340 (`microsOfDay`), 481 (`NULL`)
   and 359a (the unsigned compares): every arm of 2.6 over a 64-bit operand, its proofs, named cases
   and coverage rows; `make_time` and the two `make_timestamp_ntz` forms fuse first over a `(16, 6)`
   column and over 350's cast.
4. **351c, the 128-bit forms and the precision-19 results**, after 359b, 360b and 363: 2.4, the
   pair's negative scale, constants and raise, the relabels and negative scales from a 64-bit
   operand to a type above 18, `time_from_seconds` on a pair, their proofs and rows; the
   measurement against the baseline at both widths and 512 on the laptop, its predictions scored.

| file | what |
|---|---|
| `VarkaDecimalRoundCompiler.java` (new), `VarkaDecimalCompiler.java` | 2.2 to 2.5, `rdiv` |
| `VarkaTimeCompiler.java`, the NTZ arms of 340 | 2.6 |
| `varka/VarkaFamily.java` | `DECIMAL_ROUND` |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weights, features, reach |
| `sql/varka/proofs/decimal_round.smt2`, `long_divide.smt2`, `VarkaProofFiles.java` | 3 item 4 |
| tests | the shape table, edges, named cases, coverage rows, fuzz trees, planted fault |
| benchmark | the baseline sections and their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `census.tsv`, `census_curated.txt` |

**Size: large, in four pull requests**, against the row's medium. The baseline about half a day;
351a about three days (the proof file and the edges the larger part); 351b about two and a half
(four arms, each with its own disposal); 351c about three once its three predecessors are in. Test
time: the shape table is a pure function; the edges and named cases are of the size of 347's slot
tables, seconds; the proof file is priced from its spiked form at VARKA-245's observed ratio in the
calibration table, under twenty seconds per solver. It rests on 157, 348 and 350a for 351a; on 340,
481 and 359a for 351b; on 359b, 360b and 363 for 351c; and on 350 and 364 for the seconds' casts.

## 6. What it unlocks in the census

The milestone plan's 1.1 item 2, strictly: an entry-and-type pair counts only when every form a
query can give it fuses, at every precision for a decimal (2.8; `VARKA-359.md` 6). Ten decimal
pairs are this row's: `round`, `bround`, `truncate`, `floor`, `ceil`, `ceiling`,
`time_from_seconds`, `make_time`, `make_timestamp_ntz` and `try_make_timestamp_ntz`;
`make_dt_interval`'s, which also names this row in the census, is 103's.

- **351a and 351b move no pair.** Every rounding entry has 128-bit forms, and every seconds
  function's decimal operand can be wider than 18 or cast.
- **351c moves 7**: the six rounding entries and `time_from_seconds`, given the prerequisites of
  section 5 and 350's `LIMB_MAGIC` arm: a `GUARD` batch decline does not count as fused (settled by
  the lead, 30 October 2026), so the twelve `(p, d)` of 2.3 hold the six rounding pairs until it.
- **3 count only after 350 and 364** as well: `make_time` and both six-field forms, whose seconds
  arrive through a cast from every decimal type (item 0.11).
- **Pairs of other types this row holds**: `floor`, `ceil` and `ceiling` over `byte`, `short`,
  `int`, `bigint`, `float` and `double` (18 pairs), whose two-argument form casts to a decimal (item
  0.4), so they count after 344, this row's `RoundFloor` and `RoundCeil`, and the casts of 350, 360
  and 346, and never on 344's one-argument row alone; the `int` pairs of `make_time` and both
  six-field forms; `extract`, `date_part` and `datepart` over `ntz` (with 340). The census script
  decides each on regeneration **[I]**.

Corrections to `census_curated.txt` in 351a's pull request: `second`'s rows drop 351 and its note
on a fractional form, `second` being an int over both its types (item 0.12 and "What differs");
`time_to_seconds` names 157 alone; `make_dt_interval` names 103 alone, as `extract_dt_seconds`
already does; `floor`, `ceil` and `ceiling` note that their non-decimal pairs need the
two-argument cast form.

## 7. Out of this row, and declines that stay

Out: `time_to_seconds` and `extract(SECOND FROM t)` over `TIME` (157); `extract(SECOND FROM dt)`
and `make_dt_interval` (103); the casts that bring a seconds operand to `(16, 6)` (350 below 19,
364 above); the divisions, which this row
calls (350's `rdiv` core and its two arms, 363's `ScaleDown`); the stores (157, 359); the
nanosecond timestamp types and `SecondWithFractionNanos`; the two-field `try_make_timestamp_ntz`,
a `TryEval` (340's out list); a negative decimal scale; decimal division (owner's decision).

Declines that stay, each with its reason: a batch whose rounding raises (2.5), or whose seconds
function raises, declines in every mode and the row engine raises; under `GUARD`, a batch past
`2^(52 + d)` at the twelve `(p, d)` until `LIMB_MAGIC`; a null scale (333); a decimal in an
int-driven kernel (347's leaf rule); at two long lanes on x86, every condition root (503) and any
projection the measurement shows losing there.

## 8. Open points for the lead

Settled by the lead on 30 October 2026, with the reviewer, kept as the record: `extract(SECOND
FROM dt)` and `make_dt_interval` are row 103's ("What differs"); the two-argument `floor` and
`ceil` over non-decimal types are this row's, and 344's one-argument row alone cannot count
their pairs under 1.1 item 2 (section 6); a `GUARD` batch decline does not count as fused, so the
twelve `(p, d)` wait for `LIMB_MAGIC` (section 6); the `(int)` narrowing is copied and pinned by a
test (2.6, section 3 item 3). None is open. Flagged as the owner's text, not questions:

1. **Row 351's text still lists `extract(SECOND FROM dt)` and `make_dt_interval`**, which this plan
   leaves to row 103, as row 157's approved plan does for the first.
2. **`census.tsv`'s `time_from_seconds` pairs over `byte`, `short`, `int`, `bigint`, `float` and
   `double` have no owning row text**: the census names rows 351 and 343, row 351's text covers
   only the decimal operand, and `VarkaTimeCompiler` already lowers a long-lane count.
3. **The size cell**: this plan counts large, in four pull requests (section 5), against the row's
   medium.
4. **Filing the `(int)` narrowing upstream needs the owner's yes.** The witness: unscaled
   `4324967296` in the `DECIMAL(16, 6)` seconds of the six-field `make_timestamp_ntz`, that is
   `4324.967296` seconds, is narrowed to `30000000` and accepted as 30 seconds
   (`DateTimeExpressionUtils.java:99`, item 0.8; `results/VARKA-351-admission.txt` item 5). The
   kernel reproduces it whatever the answer.

## 9. Done when

Row 351's text holds, with the corrections above: `round`,
`bround`, `truncate`, `floor`, `ceil` and `ceiling` over a decimal fuse at every precision up to 38
and every literal scale in the three modes, the precision-19 and wider results stored through 359
and 360, a raising shape declining its batch in every mode; `time_from_seconds` over a decimal of
either form, `make_time`, both six-field `make_timestamp_ntz` forms and
`extract(SECOND FROM ntz)` fuse over their decimal operand or result; each with a
coverage row naming its verification; `decimal_round.smt2` proves 2.3, the pair's biases and tie
test, `time_from_seconds`' inverse lemma and the seconds sums under both solvers; the shape table,
the edges and the named cases pass, the planted fault failing with its flag; no existing shape's
bytes move; the measurement against the baseline is committed at both widths with its predictions
scored; the census and its notes agree with the compiler; and the PR build and the option matrix
pass.

## 10. Outcome

(Written when the work lands.)
