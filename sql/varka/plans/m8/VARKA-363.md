# VARKA-363: Spark's precision-loss rule and the decimal rescale down

## 0. What exists now (admission check)

From master at `7ea5b6242c7` for Spark's sources and the plans, rows 360 and 361's plans on their
unmerged branch `varka-360-361-plan` at `40adc9359f4`, rows 350 and 366's on `varka-350-366-plan`
at `6981bde9576`, row 349's on `varka-348-349-plan`, row 359's plan (`VARKA-359.md`, merged), row
362's (`VARKA-362.md`, this branch) and the JDK in `/home/max/proj/openjdk-build/jdk25`
(30 October 2026). Spark paths are under `sql/catalyst/src/main/scala/org/apache/spark/sql/
catalyst/` unless named, `DecimalType.scala` and `Decimal.scala` under
`sql/api/src/main/scala/org/apache/spark/sql/types/`. The row's text is the milestone plan's 2.8
("Precision loss" and "363, the rounding division"), its section 10.3 item 2 and waves F3 and G3
of its 4.1, where 363 leads 364 and 351, which round through its division.

The type counts below are every ordered pair of the 779 decimal types with `1 <= p <= 38` and
`0 <= s <= p`, 606841 pairs, classed by a script (`results/VARKA-363-shapes.txt`).

1. **The rule, read per expression.** `Add`, `Subtract` and `Multiply` resolve their result type
   through `DecimalType.adjustPrecisionScale` when the node's
   `NumericEvalContext.allowDecimalPrecisionLoss` is true and `DecimalType.bounded` when not
   (`expressions/arithmetic.scala:443-451`, `:541-549`, `:630-638`; the setting at `:244`,
   `expressions/NumericEvalContext.scala:38-50`, fixed when the node is built). For a raw type
   `(P, S)` above 38, `adjustPrecisionScale` keeps `intDigits = P - S` and gives
   `(38, max(38 - intDigits, min(S, 6)))`, 6 being `MINIMUM_ADJUSTED_SCALE`
   (`DecimalType.scala:121`, `:175-199`); `bounded` gives `(min(P, 38), min(S, 38))` (`:144-146`).
   So the compiler reads the rule from the node's resolved `dataType`, never from the session
   configuration at kernel-compile time, which is the defect Comet fixed as issue #4124
   (`READING_ARROW_RS.md` 2).
2. **The rescale is `HALF_UP` and the check follows it.** `checkDecimalOverflow` and the generated
   code call `changePrecision` with `ROUND_HALF_UP` (`expressions/arithmetic.scala:273-275`,
   `:294-310`), which for a `BigDecimal`-backed value is `setScale(scale, HALF_UP)` and a
   precision test, and for a long-backed one a division by `POW_10(k)` rounding the dropped
   digits half away from zero (`Decimal.scala:387-470`, the rounding at `:427-430`): both are half
   away from zero on the magnitude.
3. **The three modes.** `changePrecisionExact` under ANSI and TRY and `changePrecisionOrNull`
   under LEGACY (`expressions/arithmetic.scala:294-310`, `expressions/CastUtils.java:107-115`), TRY
   catching to null (`expressions/arithmetic.scala:356-374`, interpreted `:376-397`;
   `try_add`, `try_subtract` and `try_multiply` at `expressions/TryEval.scala:90-96`, `:229-235`,
   `:268-274`). ANSI raises `NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION`
   (`errors/QueryExecutionErrors.scala:106-120`). The check is on the rounded value against
   `10^p`, so a value can round into overflow (`10^38 - 0.5` at a scale that drops the half).
4. **The product is cut before the rescale, and that is one rounding.** `Decimal.*` multiplies
   under `MathContext(39, DOWN)` (`Decimal.scala:523-524`, `:597`; the comment at `:593-596` names
   SPARK-45786), then `changePrecision` rounds `HALF_UP`. The two steps equal one `HALF_UP` of the
   exact product, the lemma of 2.4; of 400000 draws, those over cut shapes agree, 130510 values
   and 62548 overflows, 105031 of them with digits truncated (`results/VARKA-363-lemma.txt`).
   The fork's test is `ArithmeticExpressionSuite.scala:238` ("SPARK-45786: Decimal multiply,
   divide, remainder, quot", under `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/
   expressions/`), four known cases and 100 random `(p, s)` pairs under both settings.
5. **Three rules cut a scale, and this row's division serves all three.**
   - **Arithmetic, `adjustPrecisionScale`.** `Multiply`: 485478 pairs cut under the default and
     101270 under `allowPrecisionLoss = false`, since `bounded` cuts wherever `s1 + s2 > 38`. The
     cut `k` runs from 1 to 39, `k = 39` at 528 pairs (first `(38, 7) * (38, 38)`) and `k >= 28`,
     where `5^k` passes `2^63`, at 50887. `Add` and `Subtract`: class C of row 360's plan (its 0
     item 4), 234832 pairs, under the default only, `k` up to 32.
   - **Coercion, `boundedPreferIntegralDigits`.** A comparison and the branches of a choice are cast
     to `widerDecimalType` (`analysis/DecimalPrecisionTypeCoercion.scala:136-144`, `:191-206`;
     `analysis/TypeCoercionHelper.scala:261-272`), which above 38 keeps the integral digits and
     cuts the scale with no floor at 6 (`DecimalType.scala:148-158`): 202540 pairs rescale an
     operand down, `k` up to 38. These are the 22 comparison and choice pairs `VARKA-359.md` 6
     parks on "360 and 363". The rule object `DecimalPrecision` hands every node to these
     (`analysis/DecimalPrecision.scala:50-55`). Under
     `spark.sql.legacy.decimal.retainFractionDigitsOnTruncate` (`SQLConf.scala:8457-8458`, under
     `sql/catalyst/src/main/scala/org/apache/spark/sql/internal/`) the coercion uses `bounded`
     instead (`DecimalPrecisionTypeCoercion.scala:199-205`), which keeps the scale and can lose
     integral digits.
   - **The decimal-to-decimal cast** to a smaller scale, `toPrecision` with `HALF_UP`, which 364
     builds through this division, and the rounding functions over a 128-bit decimal, 351's
     (`expressions/mathExpressions.scala:1694`, `:1929`).
   `Remainder` and `Pmod` call `adjustPrecisionScale` too (`expressions/arithmetic.scala:
   1070-1076`, `:1159-1166`), but their raw precision `min(p1 - s1, p2 - s2) + max(s1, s2)` never
   passes 38, so they never cut; `VARKA-359.md` 6's listing of `%`, `mod`, `pmod` and `try_mod`
   under this row does not hold, and row 349's plan suggested row 30 for them (section 8).
   - **The interval quotients by a decimal**, `DivideYMInterval` and `DivideDTInterval`: the count
     over the decimal through `Decimal./`, then `setScale(0, HALF_UP)` and `intValueExact` or
     `longValueExact` (`expressions/intervalExpressions.scala:791-793`, `:870-872`; a zero divisor
     raises at `:747-766`). Row 349's plan, section 7 (branch `varka-348-349-plan`), sent them here
     and the interval products to 361; this row claims them. Their divisor is the decimal column,
     a variable, so they are `HALF_UP` of `count * 10^s / u` and not a division by `10^k`.
6. **The dividends are wider than the row text has them.** A cut product's exact value reaches 253
   bits (`(10^38 - 1)^2`), and is at most 128 bits only where `p1 + p2 <= 38`, 9616 pairs. A class C
   sum is not a 129-bit value either: the coarser operand is rescaled up to the finer scale first,
   and `(38, 0) + (38, 38)` reaches `10^76`; the rescaled sum passes 129 bits at 198704 of the
   234832 class C pairs, up to 253 (`results/VARKA-363-shapes.txt`). Row 360's plan calls class C
   "a division of a value of up to 129 bits" (its 0 item 4), and the milestone plan's F3 row "the
   129th bit feeding its unsigned division"; both hold for an equal-scale sum only. A coercion
   operand is one 128-bit value.
7. **Where the `10^38` check is live.** For a cut product the rounded quotient is below `10^38`,
   the check dead, exactly where the scale is `38 - intDigits` (the quotient is below
   `10^(p1 + p2 - k) <= 10^37`); where the floor of 6 sets the scale it is live, and the quotient's
   bound then passes `2^127`: under the default setting dead at 332565 cut pairs and live at
   152913, every live one past `2^127`, first `(2, 0) * (38, 7)`; under the setting false, which
   cuts to scale 38, live at 100529 and dead at 741 (`results/VARKA-363-shapes.txt`). A class C
   sum's check is dead by the same argument when the scale is `38 - intDigits` (each rescaled
   operand below `10^(P - 1)`, the quotient below `2 * 10^37`) and live under the floor. A
   coercion cast is infallible: the operand it cuts has fewer integral digits than the target (its
   `s > 38 - max(intDigits)`), so even the rounding carry fits, arrow-rs's `p_in - delta < p_out`
   (`READING_ARROW_RS.md` 1).
8. **The vector multiply.** C2 emits one `vpmuludq` per 32-bit limb product only where both inputs
   are visibly masked or shifted (`src/hotspot/share/opto/vectornode.cpp:2189-2237`), so the
   magic constant's limbs are emitter constants written `broadcast(c).and(0xFFFFFFFF)`, a zero limb
   dropped, as row 361's plan has it (its 0 item 7; `VARKA-362.md` 0 item 6). C2 turns a scalar
   long division by a constant into a multiply-high (`src/hotspot/share/opto/divnode.cpp:277`,
   `:354-433`, `READING_TRINO_DECIMAL.md` 4), which the S arm uses.
9. **What the prerequisites give.** 359: the pair, its unsigned compares, negation and `abs` with
   the borrow, and `gt128` (`VARKA-359.md` 2.2, 2.4, 2.5). 360b: `WideMul` with constant
   multipliers, the rescale up; 360's carry option `decimalCarry` and class K's disposal through
   row 481's `GuardedRange` modes (row 360's plan, 2.1, 2.5). 361: the 64 by 64 product, whose
   `BIGINT * DECIMAL(18, s > 6)` cut it leaves here (its 0 item 5). 362: the product words, in two
   sign forms, `TWOS` and `MAGNITUDE` (`VARKA-362.md` 2.1). 350's `LIMB_MAGIC` is the same
   magic-multiply division at 64 bits, its high word 360b's node (row 350's plan, 2.2).
10. **The baseline has three of this row's shapes.** On the EPYC 7763 at 256 bits the row path
    pays 1188.8 ns a row for the `(38, 10)` chain `(x + y) * z - x`, whose three nodes all cut (a
    class C sum at `k = 1`, a product at `k = 13`, a difference at `k = 4`), and 315.1 ns for
    `CAST((38, 10) AS (18, 4))`, a rescale down by `10^6` that is 364's through this division; the
    one-scale comparison costs 365.0 ns
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`).
    There is no comparison at two scales and no product cut alone.
11. **359's spike holds this row's main loop**: the `HALF_UP` rescale of a 128-bit value by
    `10^6`, by the magic multiply and by the same constants in scalar (`VARKA-359.md` 2.1); it has
    not run.

### What differs from the row text and the milestone plan

- **The 256-bit dividend is the common case, and class C needs it too** (items 0.5, 0.6): every cut
  product with `p1 + p2 >= 39` and most class C sums. Two forms are measured for class C, the wide
  sum and Velox's whole-and-fraction split (2.5), which the row names as alternatives.
- **The setting false also cuts** (item 0.5): `bounded` rounds a product with `s1 + s2 > 38` to
  scale 38. The row's "with the setting false, the exact type and its overflow" holds for every
  sum and for every product with `s1 + s2 <= 38`, whose kept shapes are row 362's.
- **The division is exact everywhere with no decline band** (2.3): the magic constant exists and is
  checked for every `k` from 1 to 39 at both dividend widths, so the arithmetic needs no band;
  a shape declines only with a measured loss (owner's decision A2).
- **`k = 39` is built like every other `k`, a departure from the row's "declined or folded"**
  (decided by the lead on 30 October 2026): in the pre-shift form its constant is 211 bits over a
  215-bit `N`, 49 limb products, exact like the rest (`results/VARKA-363-magic.txt`). It occurs at
  528 product pairs under the default, both operands of precision 38 and `s1 + s2 >= 45`, with the
  check dead.
- **The coercion's cast is 364's; the division under it is this row's** (decided by the lead on
  30 October 2026). Wave G3's row: "363's division is what 364's decimal-to-decimal casts and
  351's 128-bit rounding round through, so 363 leads both". The comparisons and choices at unequal
  scales coerce through a decimal-to-decimal `Cast`, so 364 builds that expression, and this row
  builds and proves the division it calls, the exemption of item 0.7 included.
- **`%`, `mod` and `pmod` never cut** (item 0.5) and decline here naming no row, a gap flagged in
  section 8; the interval quotients by a decimal are claimed (item 0.5) and wait on the same
  gap.

## 1. Goal and non-goal

The `HALF_UP` division of a 128-bit or 256-bit decimal value by `10^k`, exact over the value's
proven bound, as one helper for every caller, 364's coercion and decimal casts and 351's rounding
among them, and this row's two arithmetic callers: a product whose scale is cut, under either
setting, and a class C sum or difference. The result checked against `10^38` at full width where the
type does not prove it, nulling or declining by mode. Built and measured against the row engine,
declined with its reason only if it loses (owner's decision A2); and the interval quotients by a
decimal, claimed here and built once a division by a variable exists (section 8). Not this row: the
casts, the coercion's included (364), and the rounding functions (351), which call the helper in
their own rows; the product itself (361, 362); `%`, `mod` and `pmod`; decimal division (out of the
milestone).

## 2. Design

### 2.1 The forms, and the measurement that picks

- **M, the magic multiply** (2.3): the magnitude pre-shifted, multiplied by a constant on 32-bit
  limbs, the rounding a shift; 16 limb products for a 128-bit dividend, 9 from `k = 32`, and 49 to
  64 for a 256-bit one (`results/VARKA-363-magic.txt`).
- **The magnitude, two ways**, row 362's `decimalWideSign`: from `MAGNITUDE`, whose product is
  already unsigned and needs no 256-bit `abs`, or from `TWOS` with a four-word negation before the
  division. For a sum and a cast operand the magnitude is 359's `abs` on a pair, or a four-word
  one after a wide sum.
- **Class C, two ways** (option `decimalScaleDownAdd`, `WIDE` or `SPLIT`, `Reason.MACHINE`), 2.5.
- **S, the scalar loop**: the same pre-shift and constants on `unsignedMultiplyHigh`, the reference
  arm under 359's answered question 3 (a), measured, not emitted.

**The spike**, as loops added to 359's `VarkaDecimalPairSpikeBenchmark` beside rows 360 to 362's,
each checked against `BigDecimal.setScale(k, HALF_UP)` before it is timed and driven to level-4
code, over 359's ladder, at 512, 256 and 128 bits on the laptop and 256 and 128 on the EPYC 7763:

1. 359's loop, a 128-bit value down by `10^6`, and the same at `k = 1`, 27 and 32, the edges of the
   16-product and 9-product bands;
2. a 256-bit dividend from 362's product down by `10^6` (64 products) and `10^30` (49), in both
   magnitude forms;
3. the worked class C case, `(38, 10) + (38, 10)` to `(38, 9)`, and `(38, 0) + (38, 38)` to
   `(38, 6)`, each in `WIDE` and `SPLIT`.

Each in M and S. The verdict per width and host names each option's default; the losing arms stay
behind their options. Laptop numbers are labelled as such; the defaults come from the runner.

### 2.2 The node

    ScaleDown(List<VarkaVectorIR> magnitude, int k, BigInteger bound, Round round, int word)

on the long lane: the `word`-th word of `floor`-or-`HALF_UP` of `n / 10^k`, `n` the unsigned value
of the two or four magnitude words, every `n` at most `bound`. `round` is `HALF_UP` here and
`DOWN` for class C's split (2.5) and 364's truncations. The quotient's words share one network,
parked once as 361's and 362's are; it renders `sdown:<k>:<round>:<word>(...)` with the bound's
width class in the shape key. The constants are not in the node: the emitter derives `(L, c)` from
`k` and `bound` through the table of 2.3, which the proof renderer reads as well. The reference
evaluator answers with `BigInteger`; the IR fuzzer draws the node with magnitudes up to its bound;
`VarkaCanonicalIr` gains its parse arm. Its sign is outside it: the compiler puts it back on the
quotient pair with 359's negation, masked by the sign word.

### 2.3 The division, and why it is exact

For `n >= 0` and `k >= 1`, with `N = floor(n / 2^(k - 1))` and `d = 5^k`:

    floor(N / d) = floor(n / (2^(k - 1) 5^k)) = floor(2n / 10^k)                      (1)
    floor((floor(2n / 10^k) + 1) / 2) = floor(n / 10^k + 1/2) = HALF_UP(n / 10^k)       (2)

(1) is the nested-floor identity for positive integer divisors; (2) holds because
`floor((y + 1) / 2)` depends on `y` only through `floor(y)`. So `HALF_UP` needs no remainder:
`q = (floor(N / 5^k) + 1) >>> 1`, DuckDB's "divide by `factor / 2`, add one, divide by two"
(`READING_DECIMAL_ENGINES.md` 2, item 4) and Trino's "by fives" path in the other order
(`READING_TRINO_DECIMAL.md` 4). `floor(N / 5^k)` is the round-up magic multiply: `c =
ceil(2^L / 5^k)`, `floor(N * c / 2^L)`, with `L` the least that meets `e * Nmax < 2^L`,
`e = c * 5^k - 2^L`, the sufficient condition, since for `N = q * 5^k + r` the error term
`N * e / (5^k 2^L)` stays below `(5^k - r) / 5^k`. Lemire, Bartlett and Kaser's bound, which the
milestone plan's 2.8 names, gives the least `L` exactly; the sufficient condition can only ask for
a wider constant, and the proof checks the claim itself.

`results/VARKA-363-magic.txt` tables `L`, the width of `c` and the limb products for every `k` from
1 to 39 at three bounds, `10^38 - 1`, an equal-scale sum's `2 * 10^38 - 2` and `(10^38 - 1)^2`,
each checked on 20000 draws and the tie corners: 16 products up to `k = 31` and 9 from 32 for a
128-bit dividend (16 up to 31 for the sum as well); 64 up to `k = 27` (56 at 28, 64 at 29) and 49
from 30 for a 256-bit one; `5^39` is 91 bits and its constant fits the
same network. The emitter takes the constant for the shape's own bound, from the operand types
(`(10^p1 - 1)(10^p2 - 1)` for a product), so a narrower bound gives fewer limbs. The product
`N * c` is row 362's column sum with one operand constant (its carry network and its 35-bit
column headroom, `VARKA-362.md` 2.1); only the words from bit `L` up are kept, and the low columns
are still summed for their carries, no truncated product being proven (`READING_M8_PAPERS.md` 2).

**Exact, or declined: exact.** For every `k` and bound the compiler meets, the constant is proven
over the whole bound by the proof file (3 item 6), and the proof renderer finds the first `N`
past the bound where it fails; no shape needs a decline band for the arithmetic. A shape still
declines where its measurement loses to the row engine, with that reason.

### 2.4 The precision-loss lemma

Spark's product is `HALF_UP(trunc39(E) / 10^k)` against the exact `E`, `trunc39` keeping the first
39 significant digits toward zero (item 0.4). The lemma: both orders give the same value, or both
overflow. `HALF_UP` to scale `S - k` reads the digits from position `k` up and the first dropped
digit only; if the rounded result fits 38 digits, `E`'s digits above position `k` number at most
38, so the first dropped digit is at most the 39th significant one, which `trunc39` keeps, and the
digits it zeroes lie below it and decide nothing. If `E` has 39 or more digits above position `k`,
both overflow: truncation keeps those 39 leading digits, and `HALF_UP` never gives less than the
truncated quotient, which is at least `10^38`. Over integers, per digit count `D` of `E` from 40
to 76 and per `k`, the claim is linear (`E` in `[10^(D - 1), 10^D)`, `trunc39(E) = 10^(D - 39)
floor(E / 10^(D - 39))`), and the proof file states it so; the draw of
`results/VARKA-363-lemma.txt` confirms. The fork has the SPARK-45786 fix (item 0.4), so the lemma
holds against it; spark-rapids keeps the older double rounding on
purpose (`READING_RAPIDS.md` 2, item 1), which is why the port pins the fork's behaviour and keeps
rapids' compatibility triples as named vectors whose expected values come from the fork.

### 2.5 The callers

| caller | dividend | form | check |
|---|---|---|---|
| cut product, `p1 + p2 <= 38` | 2 words, 362's N or 361's product | M, 16 products | dead |
| cut product, `p1 + p2 >= 39` | 4 words, 362's C | M, 49 to 64 products | dead or full width |
| class C sum, equal scales | 2 words, below `2 * 10^38` | M, 16 products | dead or full width |
| class C sum, unequal scales | `WIDE` or `SPLIT` below | | dead or full width |
| 364's coercion cast | 2 words | M, 16 products or fewer | none, infallible |

**Class C at unequal scales, two forms.** `WIDE`: the coarser operand rescaled up by `10^j` into
256 bits (row 362's 128 by 64 for `j <= 19`, its 128 by 128 for `j >= 20`), the four-word sum with
360's carry, its magnitude, and M with 49 to 64 products; the defence is widen-then-check, the
rescaled value exact at a width where it cannot wrap (`|x| * 10^j < 2^254`) and the check on the
quotient. `SPLIT`: Velox's whole-and-fraction add (`velox/functions/sparksql/DecimalArithmetic.cpp`
at `484ef8297`, `applyAdd` at lines 48-82, `getWholeAndFraction` at 88, `addLargeNonNegative`,
`addLargeOpposite` and `addLarge` at 149-264): each operand split by a `DOWN` `ScaleDown` by its own
`10^s` into a whole part and a fraction, the fractions rescaled to `S` (below `10^38`) and summed
with a carry at `10^S`, the opposite-sign case adjusted so whole and fraction share a sign, the
fraction rounded down by `10^k`, and the whole sum scaled to the result scale and added: three
128-bit divisions and no 256-bit value. Velox's own fast path, both rescaled operands with three
leading zeros, is a gate in front of either, like row 362's.

**The comparison and the choice.** `CAST(x AS widerDecimalType)` with the scale cut is the
coercion cast of item 0.5, a decimal-to-decimal `Cast` that 364 builds through this helper. What
this row gives it is the division and the proof of the exemption `p_in - k < p_out` on the resolved
types, which holds at every pair under the default coercion (item 0.7, a type lemma the sweep of
test 4 checks) and not under `retainFractionDigitsOnTruncate`, where the cast can overflow. With
360b's rescale up of the other operand and 364's cast, the 22 pairs of `VARKA-359.md` 6 fuse at
unequal scales.

**The interval quotients.** `count / d` is `HALF_UP(count * 10^s / u)` with `u` the column's
unscaled value; `Decimal./` divides to 39 fractional digits `DOWN` before the `HALF_UP` to scale 0,
and the argument of 2.4 makes that one rounding of the exact quotient. The dividend is
`count * 10^s`, up to 158 bits for a month count and 190 for a microsecond count **[I]**, and the
divisor a variable of up to 127 bits: a general division, which no row plans and which this row's
constant division does not serve. The quotients decline naming the gap of section 8 until a
variable 128-bit division is planned; the overflow (`intValueExact`, `longValueExact`), the zero
divisor and `checkDivideOverflow`'s minimum by -1 follow Spark's raise in every mode.

### 2.6 The check at full width, and its disposal

Where item 0.7's argument does not make it dead, the check runs on the quotient's magnitude
before the sign returns and before any word is dropped: the words above the pair zero and the pair
at most `10^38 - 1` unsigned, the two compares of 359's `gt128` with unsigned high words. Where the
check is live the quotient's bound passes `2^127` (item 0.7), so 359's check on the low pair alone
would pass a wrapped value, arrow-rs's defect (`READING_ARROW_RS.md` 3, item 1). The disposal is
row 360c's, from row 481's node: `GuardedRange(CondValue(ovf), 0, 0, mode)` added into both kept
words, `NULL` under LEGACY and TRY, `FAIL` under ANSI, whose declined batch the row engine raises
on (row 360's plan, 2.5). A dead check emits nothing, and its shape is one kernel in all three
modes.

### 2.7 Overflow defences, as the 28 October rule asks

No check reads a value that may have wrapped. The pre-shift is a right shift of a magnitude and
cannot overflow; `N * c` is summed in columns that cannot wrap (`VARKA-362.md` 2.1); the `+ 1`
before the final shift cannot carry out of the words, `floor(N / 5^k)` being at most a fifth of
`Nmax`. The
rescales up are widen-then-check (`WIDE`, and `SPLIT`'s fractions below `10^S`), stated per site
with a test at the first value past each bound; the coercion cast needs none (item 0.7). The
planted fault for row 504 is the dropped column carry of the row text, two limb products of one
column of `N * c` summed in one word, as in row 362's plan (its 3 item 3).

### 2.8 At 128 bits, compiler, reference, cost, warm-up

M is lanewise arithmetic and `abs`, its compares in the sign and the check; at two long lanes on
x86 those are the Java fallback, a condition root declines by row 503's rule, and a projection is
measured there and declines with its measured reason if it loses (`VARKA-359.md` 2.6).
`VarkaDecimalCompiler`'s `Multiply`, `Add` and `Subtract` arms gain the cut shapes ahead of their
declines naming 363; 364's cast arm calls the same node. The budget weighs the node by its lane
calls, written down by `dev/varka_emit.sh` when built; the cost model gets a
feature on row 352's awaiting-refit list; the warm-up's long block gains M at one `k` per band,
held by `VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The rescale table**, against `BigDecimal.setScale(k, HALF_UP)`, at 2, 4 and 8 long lanes,
   lengths 1, 3, 37 and 64, both bodies, each `k` from 1 to 39 at both bounds: the divisor table of
   `READING_ARROW_RS.md` 3, items 2 and 3, `10^k - 1`, `10^k`, `10^k + 1` and `5^k +- 1` multiples,
   the ties `m * 10^k + 10^k / 2` and one either side, the bound itself, 0 and 1, in both signs;
   Trino's rescale fixtures (`READING_TRINO_DECIMAL.md` 10, item 1), `10^38 - 1` down by 1 among
   them; every null pattern.
2. **The callers**: arrow-rs's `(38, 18) * (38, 18)` to scale 28 and its `0.000000000000000001 *
   1.555555555555555555`, Comet's `overflow_scale` and `overflow_precision` shapes, and the scale-1
   ties `14, 15, 16, -14, -15, -16` and `25, -25` (`READING_ARROW_RS.md` 3, item 2); rapids'
   compatibility triples, its 38-nines edge and `5e37 * 2` (`READING_RAPIDS.md` 3, item 1), the
   expected values from the fork; `DECIMAL(38, 10) + DECIMAL(38, 10)` at `10^38 - 1` under the
   default setting, the case row 360's plan leaves here; Trino's two capped-add vectors and
   `10 + 10^-37` (`READING_TRINO_DECIMAL.md` 10, item 3); the helper at the coercion's
   `(38, 10)` to `(38, 2)` with operands at each tie, for 364's cast.
3. **SPARK-45786's random test, ported**: the generator of `ArithmeticExpressionSuite.scala:238`
   for `Multiply` under both settings, run through the kernel against the fork's `Decimal`, at a
   thousand draws, which Gluten excludes (the milestone plan's 2.8).
4. **The type rule against Spark's own**: the result types of `Add`, `Subtract` and `Multiply` and
   `widerDecimalType` over every `(p1, s1, p2, s2)` under both settings hold the compiler's caller,
   `k`, bound and check to 2.5's table and to `results/VARKA-363-shapes.txt`'s counts, the
   coercion's exemption included. A pure function, under a second.
5. **One kernel where it should be**: a dead-check row has one shape hash under LEGACY, ANSI and
   TRY, a live one two (LEGACY and TRY, ANSI).
6. **The SMT proofs**, `sql/varka/proofs/decimal_scale.smt2`, its own file under Z3 and cvc5
   because its constant table is long, rendered from the emitter's table by `VarkaProofFiles`, over
   integers at full width, all linear (the multiplier is a constant): per `(k, bound)` the
   constant's claim `floor(N * c / 2^L) = floor(N / 5^k)` over `[0, Nmax]` and its first failure
   past it; identities (1) and (2); the carry network of `N * c` with `c`'s limbs constants; the
   check exactly `|q| <= 10^38 - 1`; item 0.7's dead-check and exemption bounds per shape class;
   the lemma of 2.4 per `(D, k)`; and the planted carry order as a `sat` witness. The file's times
   stated before and after, against the milestone plan's risk on proof time.
7. **Coverage rows**, a "DECIMAL rescale down" family: `d19s8 * d19s8b` (128-bit dividend),
   `d38s10 * d38s10b` (256-bit, live check), `d38s38 * d38s38b` (`k = 39`), `d38s10 + d38s10b`,
   `d38 + d38s38` (class C at unequal scales), `try_multiply(d38s10, d38s10b)`, as filters now and
   projections after 359b's store, each naming its verification for row 490's check; the
   differential, the matrix and both fuzzers; the generated files in `ADDING_A_TYPE.md`'s order,
   no existing shape's bytes moving.

The sweeps - the rescale table over every `k` at both bounds, the random draws of tests 2 and 3,
the type sweep - run by hand in the gate, as the milestone plan's section 10.3 item 5 budgets
sweeps, and their times are stated in the pull request; no CI workflow runs them. The suites'
fixed tables and the proof file run where the existing ones do.

## 4. The baseline, and predictions

**The baseline first, on the unchanged code**, with rows 360 to 362's additions:
`VarkaDecimalBaselineBenchmark` gains `DECIMAL(19, 8)` and `DECIMAL(38, 2)` columns and the
sections `x19s8 * y19s8` (a 128-bit dividend), `x38 * y38` at `(38, 10)` (256-bit, live check) and
`x38 + y38` at `(38, 10)` (class C) as projections, on the EPYC 7763 at 256 and 128 bits. The
existing chain is this row's other reading; the cast down and a comparison at two scales are 364's
readings of the helper.

Predictions, registered before the spike and the build:

1. **M against S** for a 128-bit dividend at 256 bits on AVX2: within 1.5 times of each other; at
   512 bits on the laptop M ahead by up to 2 times. For a 256-bit dividend S ahead by 1.5 to 3
   times on AVX2, M's 64 products against S's sixteen scalar multiplies a row.
2. **`MAGNITUDE` against `TWOS`** under a division: `MAGNITUDE` ahead by 1.2 to 1.5 times, the
   four-word negation saved.
3. **`SPLIT` against `WIDE`** for class C at unequal scales: `SPLIT` ahead by 1.3 to 2 times;
   `WIDE` ahead at equal scales, where it needs no rescale up.
4. **The build against the baseline** at 256 bits: the 128-bit dividend shapes 10 to 30 times
   faster than the row path, the 256-bit ones 3 to 15 times; the
   `(38, 10)` chain, all three nodes cut, 3 to 10 times faster than its 1188.8 ns a row.
5. **At 128 bits on x86** the projections still beat the row path, by 2 to 5 times for a 128-bit
   dividend; the 256-bit ones are measured and may decline.
6. **No existing shape's bytes move**; the `option_arms` digests move with `decimalScaleDownAdd`.

## 5. Order, files and size

1. **The baseline addition** (4).
2. **The spike loops** (2.1), with 359's spike and rows 360 to 362's loops; the verdict in
   section 10.
3. **363a, the helper and its 128-bit callers**: the node, the constant table and its proof file,
   the lemma, the exemption's proof for 364's coercion cast, the class C sums at equal scales, the
   cut products with `p1 + p2 <= 38`, tests 1, 4, 5 and 6, the filter coverage rows. Needs 359a,
   360b and 362a.
4. **363b, the 256-bit callers**: the cut products with `p1 + p2 >= 39`, the live check through
   row 481's mode (after 481 and 360c), the SPARK-45786 port, tests 2 and 3. Needs 362b.
5. **363c, class C at unequal scales**: `WIDE` and `SPLIT` behind `decimalScaleDownAdd`.
6. **363d, after 359b's store**: the projections, the measurement against the baseline at both
   widths and 512 bits on the laptop, the predictions scored.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java`, `varka/VarkaVectorWalk.java`, `varka/Slots.java` | `ScaleDown` |
| `varka/VarkaDecimalScale.java` (new) | the constant table, read by the emitter and the proofs |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalScaleDownAdd` |
| `VarkaDecimalCompiler.java` (rows 348 and 349's) | 2.5's callers |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weight, feature, reach |
| `sql/varka/proofs/decimal_scale.smt2`, `VarkaProofFiles.java` | 3 item 6 |
| tests | rescale table, callers, SPARK-45786 port, type sweep, one-kernel test, coverage rows |
| benchmark | the baseline sections, the spike loops, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `census.tsv` |

**Size: large, in five pull requests after the baseline**: the spike loops about a day, 363a about
four days, 363b three, 363c three, 363d one. Test time: the rescale table at 39 `k` and two bounds
is pure kernels of the slot table's size, seconds; the type sweep under a second; the SPARK-45786
port a thousand rows; the proof file has a constant per `(k, bound)` the compiler can meet and
the lemma's cases, priced from its
spiked form at VARKA-245's observed ratio in the calibration table, its time stated before it
joins the gate. It rests on 347 to 349, 359, 360b and 360c, 361, 362, 481 and 503.

## 6. What it unlocks in the census

The arithmetic pairs count with the last of their rows: `+`, `-`, `try_add` and `try_subtract`
with 360 and this row, `*` and `try_multiply` with 361, 362 and this row (`VARKA-362.md` 6). The 22
comparison and choice pairs of `VARKA-359.md` 6 count with 360b's rescale up and 364's coercion
cast through this row's division. `check_overflow` counts with 359, 360 and this row. The casts
count with 364 and the rounding functions with 351, each of which calls the helper. The interval
quotients by a decimal count when the division by a variable exists. `%`, `mod`, `pmod` and
`try_mod` are not this row's (item 0.5); `VARKA-359.md` 6's census note on them is 359a's to
correct when it lands.

## 7. Out of this row, and declines that stay

Out: the decimal casts (364) and the rounding functions over either form (351), including
`bround`'s `HALF_EVEN`, whose tie needs a remainder or an exactness test that `HALF_UP`'s pre-shift
form does not give; the coercion's cast (364); the products themselves (361, 362) and the interval
products by a decimal (361, by row 349's plan); `%`, `mod`, `pmod`; decimal division (out of the
milestone); a truncated magic product.

Declines that stay, each naming its row: `%`, `mod`, `pmod` and `try_mod` naming no row (section
8), the interval quotients by a decimal naming the same gap, a decimal in an int-driven kernel, a
negative scale (2.8), at two long lanes on x86 a condition root (503), and any shape whose
measurement loses to the row engine, with that reason.

## 8. Open points for the lead

Answered by the lead on 30 October 2026 and folded in above: `k = 39` is built (0, "What
differs"); 364 owns the coercion's cast expression and this row the division under it, by wave
G3's row; the interval quotients by a decimal are claimed here, as row 349's plan sends them; and
`%`, `mod` and `pmod` decline naming no row. What stays, for the owner:

1. **Decimal `%`, `mod`, `pmod` and `try_mod` have no home.** Three plans disagree:
   `VARKA-359.md` 6 lists them under 363; row 349's plan (section 7, branch `varka-348-349-plan`)
   suggests row 30; this plan finds they never cut a scale (item 0.5), so this row's division does
   not serve them. A decimal remainder by a column needs a general 128-bit division by a variable,
   which no plan builds; the interval quotients by a decimal (2.5) need the same. Is that division
   a milestone 8 row, or `m9/SCOPE.md` scope?
2. **The setting false also cuts the scale** (item 0.5): the row's "with the setting false, the
   exact type and its overflow" misses the 101270 product pairs with `s1 + s2 > 38` that `bounded`
   rounds to scale 38 (`results/VARKA-363-shapes.txt`). The row text is the owner's; this plan
   builds them as cut shapes.
3. **Class C dividends reach 253 bits** (item 0.6): wave F3's row has 360's capped shapes landing
   with 363 by "the 129th bit feeding its unsigned division", which holds for an equal-scale sum
   only; at two scales the rescaled sum passes 129 bits at 198704 of the 234832 class C pairs. The
   row text is the owner's; this plan builds both forms of 2.5.
4. **`VARKA-359.md` 6's census note on `%`, `mod`, `pmod` and `try_mod`** names this row; 359a
   corrects its census notes when it lands.

## 9. Done when

Row 363's text holds, with the corrections above: the spike's verdict is committed per width
before the build; the helper divides a 128-bit or 256-bit magnitude by `10^k` `HALF_UP` for every
`k` the callers meet, its constants proven by the proof files' rule over integers at full width;
the cut products under both settings and the class C sums fuse in the
three modes with the full-width check nulling through row 481's mode and declining under ANSI
where it is live, or carry their measured reason; the precision-loss lemma stated and proven;
the rounding fixtures, the divisor table, rapids' triples and 38-nines edge and Trino's rescale
and capped-add vectors as named tests; SPARK-45786's random test ported; the planted column carry
refuted by the proof and failed by the differential, and each overflow defence named with a test
at its first rejected value; the measurement against the baseline committed at both widths with
its predictions scored; and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)
