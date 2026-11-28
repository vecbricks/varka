# VARKA-363: Spark's precision-loss rule and the decimal rescale down

*[Changed on 28 November 2026: this paragraph, the note under section 5's title and sections 11
and 12 are new; nothing else in the file is edited. A second round the same day closed the
findings of the plan's first review, took the shared names and the chain's order from
`VARKA-360.md` 11.7 and 11.2, and added parts 363c2 and 363c3 on the owner's decision D8.] What
is left of the row, cut into parts a builder can take with nobody to ask, is section 11.
Sections 0 to 10 are the plan of 30 October 2026 and the record of what was built; section
11.2 lists what in them is stale, and where they and section 11 differ, section 11 holds.*
*[E1 rework, 28 November 2026: by the owner's decision E1 (a), part 363b3, the four-word carry
network's proof, is January 2027's, full strength, and no gate before then; nothing waits on
it, and every other part is Sonnet-class (11.6, 11.9).]*

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

*7 November 2026, 363a0:* the constants are not derived for each shape's own bound but for ten
rungs, `2^64 - 1`, `2^96 - 1`, `10^38 - 1`, `2 * 10^38 - 2`, `2^128 - 1`, `2^160 - 1`, `2^192 - 1`,
`2^224 - 1`, `(10^38 - 1)^2` and `2^256 - 1` (the 224-bit rung added in review on 8 November, so
a product bound in `(2^192, 2^224]` takes seven dividend limbs, not eight), and a shape takes the least rung at or above its bound, so the
proof file states a finite table and still covers every shape; a shape just under a rung's bound
may carry a limb more than its own bound would need.

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

   *363a0, 8 November 2026:* scored loosely: the sweep was not timed alone, and the whole of
   `VarkaDecimalScaleSuite` (the sweep, the table against the magic file, the shift and first
   failure of every entry, and the rescale over the table against `BigDecimal`) runs in 1.3 to
   1.4 s on the laptop, so the sweep is at most that, a ratio of at most about 1.4.
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

   *363a0, 7 November 2026:* the file holds the constants' claims per entry of nine dividend rungs
   by 39 values of `k` (the claim over `[0, Nmax]`, over `[0, first failure - 1]`, and the failure
   itself as a `sat` witness, the first failure computed in closed form by
   `VarkaDecimalScale.Entry.firstFailure`), identities (1) and (2) per `k` with a refuted
   neighbour each, the lemma of 2.4 per `(D, k)` for `D` from 40 to 76, and item 0.7's bound per
   quotient digit count and `k`, with the bound reached at 38 digits. 4208 checks, all linear:
   **Z3 1.3 s and cvc5 3.4 s** on the laptop (`dev/varka_prove.sh --solver both`), against the
   milestone plan's risk of a long proof file; there is no earlier figure, the file being new. The
   whole division as one check, `(floor(N * c / 2^L) + 1) / 2` against the half-up quotient with
   the pre-shift inside, took Z3 56 s over the file and cvc5 past a 300 s limit (it answers
   `unknown` at 256 bits), so it is not stated: the claim, (1) and (2) are each instant and
   compose, and `VarkaDecimalScaleSuite` holds the composition to `BigDecimal` at every
   `(k, rung)`. The carry network of `N * c`, the check at full width and the planted carry
   stay with 363a.

   *363a0 review, 8 November 2026:* the claim over `[0, Nmax]` is dropped, since the claim to
   `first failure - 1` covers it (the renderer refuses an entry whose first failure is not past
   `Nmax`, and the label still names `Nmax`), and the `2^224 - 1` rung is added: ten rungs, 390
   entries, **3935 checks**, linear in substance though declared `QF_NIA` like the other integer
   files, **Z3 1.3 to 1.5 s and cvc5 3.8 to 5.5 s** at load 10 to 11, against 1.3 s and 3.4 s
   for 4208 checks above.
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

*[Added on 28 November 2026.] The order and the parts below are superseded by section 11.6; the
table of files still says where each piece lives.*

1. **The baseline addition** (4).
2. **The spike loops** (2.1), with 359's spike and rows 360 to 362's loops; the verdict in
   section 10.
3. **363a0, the constant table and its proof, needing no pair, node or emitter change** (split off
   on 7 November 2026, by the owner's word, to land while 359a's pair layout does):
   `VarkaDecimalScale`, the table of 2.3 by `(k, bound)` with the limbs of `c` and the shift `L`,
   each shape taking the entry of the least bound above its own, and its reference arithmetic over
   `BigInteger` and over 32-bit limbs, the test oracle of every later part; `decimal_scale.smt2`,
   rendered from the table (3 item 6): the constants' claims and first failures, identities (1) and
   (2), item 0.7's bounds and the lemma of 2.4; test 4's sweep of the result types against Spark's
   rule, held to 2.5's table and `results/VARKA-363-shapes.txt`; test 1's values over the reference
   (the divisor table, the ties and the bound at every `k` and rung; Trino's fixtures and the null
   patterns need a kernel and stay with 363a); and the proof's time, stated in 3 item 6. Needs
   nothing.
4. **363a, the helper and its 128-bit callers**: the node, the emission of the constant's multiply
   (the carry network, the check at full width and the planted carry as 3 item 6's remaining
   checks, added to `decimal_scale.smt2`), the exemption's use for 364's coercion cast, the class C
   sums at equal scales, the cut products with `p1 + p2 <= 38`, test 1 run through kernels, test
   4's last step (the compiler's caller held to the sweep), test 5, the filter coverage rows. Needs
   363a0, 359a, 360b and 362a.
5. **363b, the 256-bit callers**: the cut products with `p1 + p2 >= 39`, the live check through
   row 481's mode (after 481 and 360c), the SPARK-45786 port, tests 2 and 3. Needs 362b.
6. **363c, class C at unequal scales**: `WIDE` and `SPLIT` behind `decimalScaleDownAdd`.
7. **363d, after 359b's store**: the projections, the measurement against the baseline at both
   widths and 512 bits on the laptop, the predictions scored.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java`, `varka/VarkaVectorWalk.java`, `varka/Slots.java` | `ScaleDown` |
| `varka/VarkaDecimalScale.java` (new, 363a0) | the constant table and its reference arithmetic, read by the emitter, the proofs and the tests |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalScaleDownAdd` |
| `VarkaDecimalCompiler.java` (rows 348 and 349's) | 2.5's callers |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weight, feature, reach |
| `sql/varka/proofs/decimal_scale.smt2`, `VarkaProofFiles.java` | 3 item 6, the table's part in 363a0 |
| tests | rescale table (over the reference in 363a0), callers, SPARK-45786 port, type sweep (363a0), one-kernel test, coverage rows |
| benchmark | the baseline sections, the spike loops, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `census.tsv` |

**Size: large, in five pull requests after the baseline**: the spike loops about a day, 363a about
four days, 363b three, 363c three, 363d one. Test time: the rescale table at 39 `k` and two bounds
is pure kernels of the slot table's size, seconds; the type sweep under a second; the SPARK-45786
port a thousand rows; the proof file has a constant per `(k, bound)` the compiler can meet and
the lemma's cases, priced from its
spiked form at VARKA-245's observed ratio in the calibration table, its time stated before it
joins the gate. It rests on 347 to 349, 359, 360b and 360c, 361, 362, 481 and 503.

*7-8 November 2026, 363a0:* splitting 363a0 off 363a makes it six pull requests: 363a0 about two days
out of 363a's four, leaving 363a about three. The type sweep's time is scored with 3 item 4.

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

1. **Decimal `%`, `mod`, `pmod` and `try_mod` have no home.** *7 November 2026, by the owner's
   word: a milestone 8 row of its own, row 577 (`VARKA-577.md`); the census sends them there.* Three plans disagree:
   `VARKA-359.md` 6 lists them under 363; row 349's plan (section 7, branch `varka-348-349-plan`)
   suggests row 30; this plan finds they never cut a scale (item 0.5), so this row's division does
   not serve them. A decimal remainder by a column needs a general 128-bit division by a variable,
   which no plan builds; the interval quotients by a decimal (2.5) need the same. Is that division
   a milestone 8 row, or `m10/SCOPE.md` scope?
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

### 10.1 Step 1, the baseline addition (27 November 2026)

Built on 20 November 2026 and brought to master `a5c8d1f0972` on 27 November 2026, on branch
`varka-362-363-baseline`, in one pull request with row 362's products (`VARKA-362.md` 10.1).
`VarkaDecimalBaselineBenchmark` gains six sections, three shapes at both null densities, after
row 362's, so that no committed section's values move:

| section | exact type | result type |
|---|---|---|
| `x19s8 * y19s8` as a value | `DECIMAL(39, 16)` | `DECIMAL(38, 15)`, one digit cut |
| `x38 * y38` as a value | `DECIMAL(77, 20)` | `DECIMAL(38, 6)`, the floor of six |
| `x38 + y38` as a value | `DECIMAL(39, 10)` | `DECIMAL(38, 9)`, one digit cut |

Every section is `NONE`: no lowering cuts a scale. `x19s8` and `y19s8` are new columns, after
row 362's: `DECIMAL(19, 8)`s whose unscaled values stay inside a long, so the section times the
cut of a product and not operands with bits in the high word, which `x38` has. The queries suite
pins the columns' types and the sections' result types, and reads them at the default of
`spark.sql.decimalOperations.allowPrecisionLoss`, which it asserts. The baseline has 180 sections.

Owed: the runner's readings of the six sections at 256 and 128 bits, dispatched after the merge.

### 10.2 Step 1, the runner's readings (27 November 2026)

The step merged as #1793 on 27 November 2026. The class's two runner files,
`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and
`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`, were replaced on 27
November 2026 from `benchmark.yml` runs 46057445085 (256 bits, no `MaxVectorSize`) and 46057448792
(`-XX:MaxVectorSize=16`) of vecbricks/varka. Both ran at master `e103a4c548a`, dispatched with
`pr=1793`, so they measured `refs/pull/1793/head`, `6d1f14ae33a`, each on an AMD EPYC 7763 by its
provenance and by the job's probe. Best times in ms, Spark's row path / varka on, without nulls and
then at one row in ten null:

| section | 256 bits | 128 bits |
|---|---|---|
| `x19s8 * y19s8` as a value | 1132 / 1137, 990 / 983 | 1110 / 1087, 974 / 980 |
| `x38 * y38` as a value | 1432 / 1433, 1241 / 1240 | 1432 / 1407, 1218 / 1250 |
| `x38 + y38` as a value | 1173 / 1166, 1014 / 1021 | 1210 / 1207, 1042 / 1056 |

Every section prints 1.0X at both widths. The arms plan alike, so they tie: 0.99x to 1.01x by best
time at 256 bits and 0.97x to 1.02x at 128 bits. None of section 4's predictions is scored, since
each is about the spike or the build; these are the row-path times its predictions 4 and 5 are read
against when the kernels land. The baseline step is complete. The kernels, 363a0 onward, are not
built.

## 11. The build plan for what is left (28 November 2026)

*[Added on 28 November 2026. Sections 0 to 10 above are the record and stay word for word; where
this section and they differ, this section holds. Code facts were read on master `6dafb8bd2f6`;
the unmerged branches were read with `git show` and `git diff`, nothing was built or run but
Python and the two pinned solvers on scratch files. "The model" is
`/home/max/varka-ops/m8-row-plans/scratch-362/model.py` with `scratch-363/verify363.py`,
`split.py`, `split_words.py` and `heights.py`; a builder does not need them, their results are
quoted here.]*

### 11.1 What exists, and what does not

| fact | command, from the repository root | prints |
|---|---|---|
| 363a0 is on master: the constant table | `grep -c 'static Entry of(int k, BigInteger bound)' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaDecimalScale.java` | `1` |
| its proof file, 3935 checks | `grep -c 'check-sat' sql/varka/proofs/decimal_scale.smt2` | `3935` |
| no node, no caller | `grep -c 'ScaleDown' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorIR.java` | `0` |
| the cuts decline naming this row | `grep -c "row 363's" sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaDecimalCompiler.java` | `2` |
| the baseline's six sections | `grep -c 'as a value, its scale cut' sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaDecimalBaselineBenchmark.java` | `3` (each at two null densities) |
| 359's spike ran loop 7 on the runner | `grep -cF '7. HALF_UP of x / 10^6 stored' sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-results.txt` | `6` |

On master: step 1 (the baseline, #1793, #1797) and 363a0 (#1402: `VarkaDecimalScale`,
`decimal_scale.smt2`, `VarkaDecimalScaleSuite`, the result-type sweep). The local branches of
`/home/max/proj/vecbricks-varka`, cut from master `767dcddce50` of 20 November 2026, 537 commits
behind today's master and stacked on rows 360 and 362's unmerged branches, so none merges:

| branch | holds | against master | a part starts from it |
|---|---|---|---|
| `varka-363-precision-loss` | `ScaleDown`, its lowering, every registry, `VarkaScaleDownSuite`, the two-word carry proof's renderer; four review findings open, the NULL finding among them | behind, not merged | yes: parts 363a1 and 363a2 copy its node, lowering, suite and renderer |
| `varka-363a2-callers` | `Cut`, the two 128-bit arms, `rounded`, `VarkaDecimalCutSuite`; nothing fuses (the cap of 16) | behind, not merged | yes: part 363a3 copies its arms and suite |
| `varka-363a3-spike` | spike loop 7b (`k = 1`), compiled, never run | behind, not merged | no: not landed, 11.3 decision 1 |
| `varka-363-baseline` | the six sections on row 362's first baseline branch | superseded by #1793 | no: dead |
| `varka-362-363-baseline` | the baseline as merged | merged (#1793) | no: done |

No other local branch matches `varka-363*`. Row 351's `varka-351c-128bit-rounding` (milestone 9)
is stacked on `varka-363-precision-loss` and inherits its NULL finding until it is rebuilt on
part 363a1. The branches' record of 20 November 2026 (their sections 10.1 to 10.3 and seven
decisions) is not on master; a part that lands a branch's code brings those paragraphs, read with
`git -C /home/max/proj/vecbricks-varka show varka-363a3-spike:sql/varka/plans/m8/VARKA-363.md`.

### 11.2 What in sections 0 to 10 is stale or wrong today

1. **10.2's last sentence and the row's note, "the kernels, 363a0 onward, are not built".**
   363a0 merged as #1402 on 8 November 2026. The kernels from 363a on are not built.
2. **0 item 11 and 2.1, "it has not run".** 359's spike ran (#1250, `VARKA-359.md` 10.1); its loop
   7 is this row's form M at `k = 6` over a 128-bit dividend. 11.3 decision 1 reads it.
3. **2.5, class C at two scales, "`WIDE` or `SPLIT`".** Both are replaced by one form on 128-bit
   words, 11.4.6, proven and checked. The option `decimalScaleDownAdd` is not built.
4. **0 item 5, "class C ... `k` up to 32" holds; what the plan did not say** is that for two
   scales the cut `k` is at most `j + 1`, `j` the digits the coarser operand is rescaled up by:
   `k <= j` at 222896 type pairs and `k = j + 1` at 10912, every one of the latter with a coarser
   operand of precision 38 (`split.py`). 11.4.6 rests on it.
5. **2.6, "the disposal is row 360c's".** `GuardedRange`'s `NULL` mode is on master (#1176).
   The disposal is row 360c1's helpers after all (`VARKA-360.md` 11.4, "The overflow mode of
   the whole chain"): every live check here calls `guardedPair` in the mode `decimalOverflow`
   gives, and part 363c3 calls 360c2's `rescaleGuard` (11.3 decision 7).
6. **2.8, "a projection is measured there".** At two long lanes on x86 every shape of this row
   declines by a refusal that exists; 11.3 decision 8.
7. **3 item 7 and 5 item 7, "as filters now and projections after 359b's store".** The store is
   merged (#1610, #1710); every shape is an output the day its part lands.
8. **The review's example of the NULL finding does not reproduce in the model.** It names
   `ScaleDown([c0, c1], k = 6, rung 0)`; there the second limb is joined from both words, so `c1`
   is read. The finding is real at other entries: at rung 0 under `HALF_UP`, word 0 reaches no
   bit of `c1` at `k = 1` and `k = 33`, and word 1 at eighteen values of `k`; with four magnitude
   words, 31 (rung, word, unread word) cells. Part 363a1's test uses `k = 1`, where the unfixed
   network answers 1 for `c0 = 5` with `c1` null.
9. **3 item 7's coverage row `d38 + d38s38`** is a shape this milestone declines (11.3 decision
   5); `d30s2 + d38s10` stands for class C at two scales instead.
10. **6, the census.** The mark for a cell fused in part, `dec^`, moves by part; 11.7.

### 11.3 The decisions, each with its evidence

1. **The spike of step 2 is closed, and loop 7b is not landed.** The reading that exists, AMD EPYC
   7763 at 256 bits, ns a row, in L2 to past L3
   (`sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-results.txt`):
   form M at `k = 6` over a 128-bit dividend 5.5 to 5.6 against the scalar loop's 7.0 to 14.0.
   Spark's row path pays 566.0 ns a row for `DECIMAL(19, 8) x * y as a value, its scale cut`,
   716.0 for `DECIMAL(38, 10) x * y as a value, its scale cut` and 586.7 for
   `DECIMAL(38, 10) x + y as a value, its scale cut`
   (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`). M is
   the only form emitted (S is measured, not emitted, 2.1), and at a hundredth of the row path
   no `k` or width of dividend can turn the comparison, so the loops at `k = 1`, 27 and 32 and
   over a 256-bit dividend decide nothing and are not built. Each part's own runner reading
   against its baseline section is the measurement (part 363d).
2. **Prediction 1 of section 4, scored on loop 7:** M and S within 1.5 times of each other holds
   in L2 (5.5 against 7.0) and not past it (5.6 against 13.1 and 14.0, M ahead).
3. **A 256-bit dividend is a product of magnitudes.** The operands' magnitudes are taken first
   (two words each), multiplied by `PairMul` with `Sign.UNSIGNED`, and the sign goes back on the
   quotient's pair. Against `TWOS` followed by a four-word negation this is fewer operations and
   less height by the model's count (106 against 134 operations and 20 against 22 levels for the
   product, and no four-word carry chain), which matters at the cap (decision 9).
4. **Class C at two scales is the split form of 11.4.6**, on 128-bit words alone: no 256-bit sum,
   no option. Velox's whole-and-fraction add (2.5) is where the idea comes from; the form here
   divides one operand only.
5. **Every class C sum at two scales is built in this milestone**, in three parts (the owner,
   28 November 2026, decision D8 of 11.9). With `a` the operand of the smaller scale, `j` the
   digits it is rescaled up by, `k` the cut and `i = j - k`:
   - `k <= j` and `p_a + i <= 38`, 200720 type pairs: the split form, 11.4.6, part 363c1;
   - `k = j + 1`, 10912 type pairs, every one with a coarser operand of 38 digits and a dead
     check: the digit form, 11.4.7, part 363c2. Two divisions in sequence would pass the cap;
     the digit form runs four side by side;
   - `k <= j` and `p_a + i > 38`, 22176 type pairs, every one with a live check and `i` from 1
     to 6: the split form behind row 360c2's `rescaleGuard`, 11.4.8, part 363c3.
   Until parts 363c2 and 363c3 merge, part 363c1 declines their groups by name: "a decimal sum
   cut past its coarser operand's scale is row 363's digit form" and "a decimal sum whose
   rescaled operand can pass 38 digits is row 363's guarded split form". Each later part
   deletes its group's reason (grep the tests for the text first). Nothing goes to
   `m10/SCOPE.md`. The interval quotients by a decimal (2.5) are unchanged: they decline naming
   the missing division by a variable, which no row of this milestone owns. The counts are
   `/home/max/varka-ops/m8-row-plans/scratch-decimal-r2/class_c_rest.py`'s (its last line
   `ALL OK class C rest: 1490922 checks`), equal to `split.py`'s.
6. **`ScaleDown` is null where any magnitude word is null** (the owner, 27 November 2026;
   `PLAN.md` 12.2 item 3 and 12.5). The contract stays and the lowering is fixed: for each
   magnitude word the lowered word does not reach, `word = word | (m[i] & 0)`. Part 363a1, with
   its test; it lands before any caller.
7. **A live check nulls under LEGACY and TRY and declines the batch under ANSI**, through row
   360c1's helpers (`VARKA-360.md` 11.4, "The overflow mode of the whole chain"):
   `guardedPair(over, lo, hi, mode, type)` adds `GuardedRange(over, 0, 0, mode)` into both kept
   words, and `mode = decimalOverflow(evalModeOf(e))` for the `Add`, `Subtract` or `Multiply`
   `e`. Never `VarkaNodeCompiler.overflowOf` alone: it maps LEGACY to `WRAP`, the integral
   rule, and `GuardedRange`'s constructor throws on `WRAP`. A dead check emits nothing and is
   one kernel in three modes. Users here: parts 363b2, 363c1 and 363c3; parts 363a3 and 363c2
   have only dead checks and call none (the code 363a3 copies from `varka-363a2-callers` reads
   no `overflowOf`: the command below prints 0). Part 363c3's guard on its rescaled operand is
   `rescaleGuard`, which declines the batch in every mode, since past it Spark may still answer
   a value (11.4.8).

       git -C /home/max/proj/vecbricks-varka show varka-363a2-callers:sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaDecimalCompiler.java | grep -c overflowOf

8. **At two long lanes on x86 every arm of this row declines in the compiler**, as rows 360
   and 362 do it (`VARKA-362.md` 11.3 decision 8): each new arm first asks
   `sink.declinesTwoLaneChecks()` and declines with `VarkaDecimalCompiler.TWO_LANE_WIDE_REASON`.
   Behind it stand the roots' refusals in `VarkaKernelPlan` (a filter's conjunct, a pair
   output, a comparison written as a value). Row 597's second half writes the decline once and
   removes these uses (*28 November 2026:* the owner's answer to `VARKA-597.md` 11.7 question 1;
   a part built after 597b merged writes no such test and expects `TWO_LANE_READ_REASON`, by
   `VARKA-360.md` 11.7, "The two-lane rule once row 597's part 597b merges"); row 698 measures
   whether a pair can win there. On aarch64 at 128 bits the cap
   is 16 and every shape of this row is taller, so each declines with the cap's reason.
9. **The cap.** Every caller needs row 697 (#1798, open): the long lane's auto cap of 64 at 256
   bits and wider. By the model (a leaf counts 0; the taller of an output root and a comparison
   with a column): a cut product over a 128-bit dividend 36 to 40, a cut sum of one scale 36,
   the split form 40 to 50, the digit form 48 to 54, the guarded split form 43 to 52, a cut
   product over a 256-bit dividend 44 to 59 with the check dead and 50 to 65 with it live. The
   live check is ORed as `q3 | (q2 | outside)`: by the model that order leaves 750 type pairs
   at 65 (rung 8, `k` from 34 to 37) and 50790 at exactly 64, where `(q3 | q2) | outside`
   leaves 45650 at 65. The model's count of the wrapping nodes may be one off the emitter's, so
   which of these fuse is the JVM's to say (11.8); a shape past the cap declines by the
   compiler's own mirror of the budget, with the cap's reason, and no part writes a height test
   of its own for them. **The cap's reason** is the text `Analysis.analyzeRoot` throws, the one
   place master words it: `"chain deeper than MAX_CHAIN_DEPTH=" + cap + " (the maxChainDepth
   emit option)"`. A test that sets `maxChainDepth(16)` asserts that the decline's reason
   contains `chain deeper than MAX_CHAIN_DEPTH=16`; if the compile reports another text, stop
   and report it (11.8 item 6).
10. **`DOWN` stays in the node**, for rows 351 and 364 of `m9/PLAN.md`; this row has no caller of
    it, so its carry network is proven by the row that first calls it. `VarkaScaleDownSuite`
    holds its answers at every entry.
11. **The node holds its bound as a rung** of `VarkaDecimalScale.BOUNDS` (the branch's decision
    3): two bounds of one rung are one shape.
12. **No new emit option in this row**, for row 362's reason (`VARKA-362.md` 11.3 decision 9).

### 11.4 The algorithms, written out

Words are 64-bit and every operation wraps; `M = 2^32 - 1`; a pair is `(lo, hi)`; `>>` is the
arithmetic shift and `>>>` the logical one. `HALF_UP(n / 10^k)` rounds halves away from zero.

#### 11.4.1 The division, and the proof of its constants

For a magnitude `n >= 0` and `1 <= k <= 39`: `N = n >>> (k - 1)`, `d = 5^k`,
`q = (floor(N / d) + 1) >>> 1 = HALF_UP(n / 10^k)` (identities (1) and (2) of 2.3, proven per `k`
in `decimal_scale.smt2`). `floor(N / d) = (N * c) >>> L` with `c = ceil(2^L / d)`:

    write e = c d - 2^L (0 < e < d) and N = q d + r (0 <= r < d); then
    N c / 2^L = q + (r + N e / 2^L) / d.  Where e N < 2^L:  N e / 2^L < 1 and r <= d - 1,
    so r + N e / 2^L < d and the floor is q.

`VarkaDecimalScale.derive` picks the least `L` with `e * Nmax < 2^L`. The model rebuilt all 390
entries (ten rungs by 39 values of `k`) and checked, in integers, the premise `e * Nmax < 2^L` at
each, that each closed-form first failure is past `Nmax`, that the constant fails there and
holds one below. That is the proof for every `N` in `[0, Nmax]`; `decimal_scale.smt2` states
the same per entry under both solvers.

Which entries the type pairs need (every ordered pair of the 779 types, both settings): a cut
product takes rung 2 at `k = 1`, rung 5 at `k` 1 to 11, rung 6 at 1 to 20, rung 7 at 1 to 30 and
rung 8 at 1 to 39; a cut sum of one scale rung 3 at `k = 1`; the split form's dividend is one
operand, at most `10^38 - 1`, rungs 0 to 2 at `k` up to 32. Each is one of the 390.

#### 11.4.2 The lowered network (`VarkaDecimalLowering.lower(ScaleDown, faults)`)

    entry = VarkaDecimalScale.of(k, bound);  L = entry.shift();  c[] = entry.limbs()
    pre = k - 1 under HALF_UP, k under DOWN
    D   = min(entry.dividendLimbs(), (64 * words - pre + 31) / 32)
    N[i] = the 32 bits of the magnitude from bit pre + 32 i  (a word masked, shifted, or two joined)
    columns = max(D + c.length, L / 32 + 1) + 1
    for i < D, j with c[j] != 0:  p = N[i] * (c[j] & M)
                                  col[i + j] gets p & M;  col[i + j + 1] gets p >>> 32
    under HALF_UP:  col[L / 32] gets 2^(L mod 32)        (the + 1 of q, added before the carries)
    start = (L + 1 under HALF_UP, L under DOWN) + 64 * word;  first = start / 32;  off = start mod 32
    top = min(first + 2, columns - 1);  where first > top the word is m[0] & 0
    carried[0] = sum(col[0]);  carried[t] = sum(col[t]) + (carried[t - 1] >>> 32),  t <= top
    word = ((carried[first] & M) >>> off)
           | ((carried[t] & M) << (32 (t - first) - off))   for t in first + 1 .. top, count < 64
    the NULL rule:  for each magnitude word m[i] the word's graph does not reach:
                    word = word | (m[i] & WordConst(0))

Each column's sum is a balanced tree. A column holds at most sixteen halves below `2^32` and the
`2^L` term, so it stays below `2^37`. The model: 780 networks (every entry, both roundings, two
magnitude words for rungs 0 to 4 and four above), every word, 59403 values (0, 1, the bound,
`10^k` and `5^k` and their neighbours, ties and their neighbours, random of every length), none
wrong, with the NULL rule in. Its heights equal `results/VARKA-630-decimal-shapes.txt`'s at every
entry that file lists.

#### 11.4.3 The sign: `negatedWhere`

    negatedWhere(words w[0..n-1], sign)      sign is 0 or -1 a lane
      one = sign >>> 63;  carry = one
      for i:  f = w[i] ^ sign;  s = f + carry;  out[i] = s;  carry = CondValue(ULT(s, f))

It is `-w` modulo `2^(64 n)` where `sign` is -1 and `w` where it is 0. A magnitude is
`negatedWhere(value, value's sign word)`; a signed result is `negatedWhere(quotient, sign)`.

#### 11.4.4 The 128-bit callers (part 363a3)

The type rule, `Cut(k, bound, checked)`, read from the node's resolved type: `k` is the exact
scale minus the type's; `bound` is `(10^p1 - 1)(10^p2 - 1)` for a product, and for a sum both
operands' bounds at the finer scale added; `checked` is `HALF_UP(bound / 10^k) > 10^p - 1`.

    cut product, p1 + p2 <= 38 (always k = 1):
      (lo, hi) = productWords(x, y)             row 362's form N, exact below 2^127
      sign = hi >> 63
      q = ScaleDown(negatedWhere((lo, hi), sign), 1, bound, HALF_UP), words 0 and 1
      result = negatedWhere(q, sign)

    cut sum or difference of one scale (always k = 1, bound 2 * 10^38 - 2):
      sum:         lo = x.lo + y.lo;  hi = x.hi + y.hi + carry(x.lo, lo)
                   neg = (x.hi & y.hi) | ((x.hi ^ y.hi) & hi)
      difference:  lo = x.lo - y.lo;  hi = x.hi - y.hi - borrow(x.lo, y.lo)
                   neg = (x.hi & ~y.hi) | (~(x.hi ^ y.hi) & hi)
      sign = neg >> 63                           the 129th bit of the exact value
      q = ScaleDown(negatedWhere((lo, hi), sign), 1, 2 * 10^38 - 2, HALF_UP)
      result = negatedWhere(q, sign)

Both checks are dead. The model, against Spark's `Decimal` (`MathContext(39, DOWN)` then
`HALF_UP`, in Python's `decimal`): 241 such products among 40000 typed draws and 20000 sums
and differences, none wrong. `(10^38 - 1) + (10^38 - 1)` at `DECIMAL(38, 10)` is
`20000000000000000000000000000000000000` at `DECIMAL(38, 9)`.

#### 11.4.5 The 256-bit cut product (part 363b2), `p1 + p2 >= 39`, either setting

    sx = x.hi >> 63;  sy = y.hi >> 63
    ax = negatedWhere(x, sx);  ay = negatedWhere(y, sy)
    P[0..3] = PairMul(ax.lo, ax.hi, ay.lo, ay.hi, words 4, Sign.UNSIGNED)   (rightLong for a 64-bit y)
    q[w] = ScaleDown(P, k, (10^p1 - 1)(10^p2 - 1), HALF_UP, w)      w = 0, 1; and 2, 3 if checked
    sign = sx ^ sy
    r = negatedWhere((q[0], q[1]), sign)
    checked:  outside = CondValue(Or(Compare(UGT, q[1], Bhi),
                                     And(Compare(EQ, q[1], Bhi), Compare(UGT, q[0], Blo))), LONG)
              result = guardedPair(q[3] | (q[2] | outside), r.lo, r.hi, mode, type)
              mode   = decimalOverflow(m.evalMode())        VARKA-360.md 11.4; never overflowOf

`(Blo, Bhi)` are the words of `10^38 - 1`, `0x098a223fffffffff` and `0x4b3b4ca85a86c47a`. The
check is on the quotient's magnitude at full width, before the sign and before any word is
dropped. One rounding of the exact product is Spark's answer (the lemma of 2.4). The model: of
40000 typed draws under both settings, 24680 cut products over 256 bits, 11343 with a live
check and 2267 overflowing, none wrong, and no dead-check draw sets the guard. At `k = 14`:
`n = 10^52 - 5 * 10^13` rounds to `10^38` and sets the guard; `n - 1` gives `10^38 - 1` and
does not.

#### 11.4.6 Class C at two scales: the split form (part 363c1)

Let `a` be the operand of the smaller scale `s_a`, `b` the other at scale `S`, `b' = b` for a sum
and for `a - b` its negation (for `b - a` the roles are `a' = -a`), `j = S - s_a`, and the exact
value `E = a * 10^j + b'` at scale `S`. Spark's answer is `HALF_UP(E / 10^k)`. Where `k <= j`,
with `i = j - k` (so `i` is the result's scale minus `s_a`) and `A = a * 10^i`:

    beta = |b'|
    r1 = HALF_UP(beta / 10^k);  r0 = HALF_UP((beta - [beta != 0]) / 10^k)
    T1 = A + sgn(b') r1;        T0 = A + sgn(b') r0
    w  = (T1 <= 0) where b' >= 0,  (T1 >= 0) where b' < 0
    HALF_UP(E / 10^k) = T0 where w, T1 elsewhere

Why: `E / 10^k = A + b' / 10^k` with `A` an integer, and `HALF_UP(E / 10^k)` is
`floor((E + 10^k / 2 - [E < 0]) / 10^k)`. `r0` and `r1` differ only at a tie of `beta`, by one,
and at a tie the sign of `E` is the test `w` on `T1`. On words:

    sb   = b'.hi >> 63;  beta = negatedWhere(b', sb)
    nz   = CondValue(Not(And(Compare(EQ, beta.lo, 0), Compare(EQ, beta.hi, 0))), LONG)
    b1   = (beta.lo - nz,  beta.hi - borrow(beta.lo, nz))
    r1   = ScaleDown(beta, k, 10^p_b - 1, HALF_UP);  r0 = ScaleDown(b1, k, 10^p_b - 1, HALF_UP)
    A    = a                                                  where i = 0
    A1   = pairTimesWord(a, min(i, 18), new DecimalType(p_a + min(i, 18), s_a + min(i, 18)), sink)
    A    = A1 where i <= 18;  else pairTimesWord(A1, i - 18, new DecimalType(p_a + i, s_a + i), sink)
           (no second call where i <= 18: its k would be 0, outside 1 <= k <= 18)
    T(r) = pair sum of A and negatedWhere(r, sb), with carry(A.lo, sum.lo) into the high word
    w    = Or(Compare(EQ, (T1.hi >> 63) ^ sb, slot(-1)),
              And(Compare(EQ, T1.lo, 0), Compare(EQ, T1.hi, 0)))
    H    = (IfElse(w, T0.lo, T1.lo), IfElse(w, T0.hi, T1.hi))
    checked:  H = guardedPair(new CondValue(outside38(H.lo, H.hi, sink), LONG), H.lo, H.hi,
                              decimalOverflow(evalModeOf(e)), type)       e the Add or Subtract

The part admits a type pair only where `p_a + i <= 38`, so `|A| <= 10^38 - 1` by its type and
`|H| < 2 * 10^38 < 2^127`: no word wraps. Each `pairTimesWord` is exact for the same reason: its
value, `a * 10^min(i, 18)` or `a * 10^i`, is at most `10^38 - 1`, below `2^127`. `i` passes 18
only where `a` is a 64-bit operand (`p_a <= 18`), which enters as its `signExtended` pair; `i` is
at most 36. The evidence: the rule agrees with `HALF_UP` of the exact sum on 291924 small cases
exhaustively, on 151397 typed draws, and through the lowered networks on 240000 draws at six
`(k, precision)` pairs, none wrong; and as an SMT claim over integers (`gen_split_smt.py`,
`|b| <= 10^38 - 1`, `|A| <= 2 * 10^38 - 2`) it is `unsat` under Z3 and cvc5 at each of the 32
values of `k`, at once, with the plant "always `T1`" `sat`. Counts: 200720 type pairs are
admitted, 193328 with the check dead and 7392 with it live.

#### 11.4.7 Class C at two scales with `k = j + 1`: the digit form (part 363c2)

*[New in the fix round of 28 November 2026.]* Where the cut removes every digit of `b'` below
`a`'s scale and one digit of `a` too, `E / 10^(j + 1)` has no integral part that one operand
holds alone, so the split form does not apply. The 10912 type pairs have `j` from 1 to 31, `p_b`
from 8 to 38, the coarser operand always of 38 digits and the check always dead (the largest
result is `HALF_UP(((10^38 - 1) 10^j + 10^p_b - 1) / 10^(j + 1)) <= 10^38 - 1`). Names as in
11.4.6 (`a`, `b'`, `E = a * 10^j + b'`, Spark's answer `HALF_UP(E / 10^(j + 1))`). Four
divisions run side by side, all `HALF_UP`, and small integers join them:

    sa  = a.hi >> 63;  sb = b'.hi >> 63                          sign words, 0 or -1
    ma  = negatedWhere(a, sa);  mb = negatedWhere(b', sb)        magnitudes
    hj  = 5 * 10^(j - 1);  hj1 = 5 * 10^j                        two-word constants, long slots
    m + c = (m.lo + c.lo, m.hi + c.hi + carry(m.lo, m.lo + c.lo))    a magnitude plus a constant
    A1p = ScaleDown(ma + 5,      1,     10^38 + 4,        HALF_UP), words 0, 1  = floor(ma / 10) + 1
    Qc  = ScaleDown(mb + hj - 1, j,     10^p_b - 1 + hj,  HALF_UP), word 0     = ceil(mb / 10^j)
    Q1  = ScaleDown(mb + hj,     j,     10^p_b - 1 + hj,  HALF_UP), word 0     = floor(mb / 10^j) + 1
    B1p = ScaleDown(mb + hj1,    j + 1, 10^p_b - 1 + hj1, HALF_UP), words 0, 1 = floor(mb / 10^(j+1)) + 1
    a0  = ma.lo - 10 * A1p.lo + 10           the last digit of |a|, 0 to 9 (words wrap)
    d1  = Q1 - 10 * B1p.lo + 9               the digit of |b'| at 10^j, 0 to 9
    st  = Qc - Q1 + 1                        1 where 10^j does not divide |b'|, else 0
    y   = 2 * (((a0 ^ sa) - sa) + ((d1 ^ sb) - sb)) + ((st ^ sb) - sb)       in [-37, 37]
    c_gt = sum over t in (-30, -10, 10, 30) of CondValue(Compare(GT, y, slot(t)), LONG)
    c_ge = the same sum with Compare(GE, y, slot(t))
    Ip  = pair sum of negatedWhere(A1p, sa) and negatedWhere(B1p, sb), carry(lo) into hi
    e   = -4 - 2 * (sa + sb)
    Tt  = pair sum of Ip and (c_gt + e, (c_gt + e) >> 63);  Tg likewise with c_ge
    neg = Compare(LT, Tt.hi, slot(0))
    result = (IfElse(neg, Tt.lo, Tg.lo), IfElse(neg, Tt.hi, Tg.hi))

Why. `E / 10^(j + 1) = I + x / 10` with `I = sgn(a) floor(ma / 10) + sgn(b') floor(mb /
10^(j + 1))` an integer and `x = sgn(a) a0 + sgn(b') (d1 + eps)`, `0 <= eps < 1`, `eps > 0`
exactly where `st` is 1. `y` is `2x` with the fraction folded to its sign, so `x` against a half
point `t / 2` is `y` against `t`, and `y = t` is a tie. Rounding to nearest adds `count - 2`; at
a tie `HALF_UP` goes away from zero, which is `c_ge` where the value is not negative and `c_gt`
where it is, and `Tt < 0` is that test. `HALF_UP((n + h) / 10^k)` with `h = 10^k / 2` is
`floor(n / 10^k) + 1`, and with `h - 1` it is `ceil(n / 10^k)`; `e` and the constants 10 and 9
take the `+ 1`s out. No word wraps but where the text says so: every magnitude is below
`2 * 10^38`, and `a0` and `d1` are small, so their low words alone give them.

The evidence, in `/home/max/varka-ops/m8-row-plans/scratch-decimal-r2/`: `class_c_rest.py` checks
the rule on 801531 small cases exhaustively against `HALF_UP` of the exact sum, on 120000 typed
draws over the 10912 type pairs (ties of the exact sum, both signs; none past `10^38 - 1`) and
through the lowered networks on 180000 draws at six `(j, p_b)`, none wrong. `gen_digit_smt.py`
states the rule as an SMT claim over integers, each division written as `ScaleDown`'s
`HALF_UP` (which `decimal_scale.smt2` proves the node computes), `|a|, |b'| <= 10^38 - 1`: it
is `unsat` under Z3 and cvc5 at each of the 31 values of `j`, and the plant "always `Tg`" is
`sat` under both at each (`digit-smt.txt`: `digit claim: unsat under both solvers at 31 of 31
values of j; planted (always Tg): sat under both at 31 of 31`). Heights by the model (it
overstates the signed compares and the select by at most 2): 48 (728 type pairs), 50 (2814),
52 (7112), 53 (2), 54 (256). All fit row 697's cap of 64 and all are past 16, so where the cap
is 16 each declines with the cap's reason.

#### 11.4.8 Class C at two scales whose rescaled operand can pass 38 digits (part 363c3)

*[New in the fix round of 28 November 2026.]* The 22176 type pairs have `i` from 1 to 6, `k`
from 1 to 32 and `p_a` from 33 to 38: `a` is always a pair, one `pairTimesWord` suffices and the
check is always live. The split form of 11.4.6 unchanged, with one change to `A`:

    A = pairTimesWord(rescaleGuard(a, i, sink), i, new DecimalType(38, s_a + i), sink)

`rescaleGuard` (row 360c2, `VARKA-360.md` 360c2's sheet) adds into `a.lo` a `GuardedRange` in
`FAIL` mode on `|a| > 10^(38 - i) - 1`. The guard is exact: `|a| <= 10^(38 - i) - 1` exactly
where `|a * 10^i| <= 10^38 - 1`. Inside it `|A| <= 10^38 - 1`, so `|H| < 2^127` and the live
check is 363c1's (`guardedPair` over `outside38(H.lo, H.hi, sink)` in the mode
`decimalOverflow` gives). Past it the batch declines in every mode, LEGACY included, and the row
engine answers: of 120000 typed draws, 7360 rows past the guard have a value in Spark and 26851
an overflow, so a null there would be wrong. `split.py`'s claim covers the form inside the guard
(its premise `|A| <= 2 * 10^38 - 2`), so no new proof claim is needed. The evidence
(`class_c_rest.py`): 120000 typed draws, every answer inside the guard Spark's (6186 of them an
overflow); 120000 draws through the networks at six `(i, k)`, none wrong. Heights by the model:
43 (546 type pairs), 45 (3654), 47 (420), 48 (6426), 49 (42), 50 (9618), 52 (1470): all fit 64,
all past 16.

The test vectors of parts 363c2 and 363c3 are
`/home/max/varka-ops/m8-row-plans/scratch-decimal-r2/vectors_363c.py`'s, computed with Python's
`decimal` by Spark's rule and, for 363c2, also through the digit form (its last line
`ALL OK vectors 363c: 31 checks`).

### 11.5 What the parts need from rows 360, 361, 362 and 697, by name

*[Rewritten in the fix round of 28 November 2026.]* **The table of every shared name with its
signature, the part that creates it and the parts that use it is `VARKA-360.md` 11.7, "The
names the four rows share"; the order of the whole chain is `VARKA-360.md` 11.2, "The whole
decimal chain, in order", where this row's code parts are steps 14 to 21.** Where a spelling
here and there differ, that table holds. What this row takes:

| name | created by | what this row uses it for |
|---|---|---|
| `WordConst`; `VarkaDecimalLowering`: `lowerAll(outputs, options, faults)`, `enum PlantedFault`, `laneCalls`, the private `bit`, `mul`, `add`, `sub` | 360b1 | `ScaleDown`'s lowering goes in this file with the parameter order of `lower(WideMul, ...)`: `lower(ScaleDown n, VarkaEmitOptions options, Set<PlantedFault> faults)` |
| `carry(a, s)`, `borrow(a, b)`, `widePair`, `signExtended` | the waiting branch `varka-360a-sums`, merged by the lead | the pair sums of 11.4.4 and 11.4.6 to 11.4.8 |
| `bigintPair(Cast c, DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink)` | the waiting branch `varka-360a-bigint-cast` only | a `BIGINT` operand of a cut product (363a3's test 6) |
| `TWO_LANE_WIDE_REASON` | 360b2, unless 597b merged first (`VARKA-360.md` 11.7's two-lane rule); 597b removes it | the two-lane decline of every arm |
| `pairTimesWord(Pair x, int k, DecimalType type, DeclineSink sink)`, `1 <= k <= 18` | 360b2 (the old branch's `pairRescale`, renamed; no branch holds the new name) | `A` of 11.4.6 and 11.4.8 (363c1, 363c3) |
| `decimalOverflow(Object evalMode)`, `evalModeOf(Expression e)`, `outside38(lo, hi, sink)`, `guardedPair(over, lo, hi, mode, type)` | 360c1 | every live check: 363b2, 363c1, 363c3 |
| `rescaleGuard(Pair x, int k, DeclineSink sink)` | 360c2 | 363c3's guard on its rescaled operand |
| `productWords`, `ProductForm.CUT`, `PairMul` with `words = 4` | 362a2, 362b1 | 363a3, 363b1, 363b2 |
| `VarkaEmitBudget.LONG_LANE_MAX_CHAIN_DEPTH` (64) | row 697, #1798, branch `varka-long-lane-chain-cap`, not merged | every caller: 363a3, 363b2, 363c1, 363c2, 363c3 |

Nothing of row 361 is needed. No guard is written a second time: a part with a live check waits
on 360c1 and calls its helpers, and 363c3 waits on 360c2; there is no "if it is on master" case.
Each part's B0 greps on master the names it needs and stops at 0.

### 11.6 The parts

Each part is one pull request under 1000 counted lines (`python3 dev/varka_msg_check.py size`).
The house rules and the path names `C`, `T`, `TS` are as in `VARKA-362.md` 11.6, and so is the
rule for a test of a shape taller than 16 levels: its options set `maxChainDepth(64)`,
`methodByteBudget` and `cse` explicitly, as `VarkaDecimalPairSuite.DEEP` does (`VARKA-360.md`
11.3 decision 5); every kernel test of this row is such a shape, and a test that asserts a
decline only keeps `VarkaMatrixRuns.BASE` *[fix round 3]*. `C/` and `T/` are shorthand for
a path on master; `git` does not expand them, so every command below that reads a branch writes the
path whole. *[The table, the order and parts 363c2 and 363c3 are from the fix round of 28 November
2026.]*

*[Fix round 3, 28 November 2026.]* **A decline an earlier part asserts, which this part turns
into a fuse.** Before the change, `grep -rn '<a fragment of each reason this part retires>'
sql/catalyst/src/test sql/core/src/test`. Each assertion of a shape this part now fuses
becomes a value check of the same shape (`checkStored`, or `storedOrDeclined` for a live
check) under `VarkaDecimalPairSuite.DEEP`, or the cap's reason where the shape declines at
the cap; it is never deleted, and the pull request names each. The ones known now are
named in the parts.

| part | what | counted lines | builder | starts from | waits on |
|---|---|---|---|---|---|
| 363a1 | `ScaleDown`, its lowering, the NULL rule fixed | about 780 | Sonnet-class | master, code from `varka-363-precision-loss` | 360b1; 362b1 in the lowering file's queue |
| 363a2 | the two-word carry network's proof | about 250 | Sonnet-class | master, code from `varka-363-precision-loss` | 363a1 |
| 363a3 | the cut product and the cut sum over 128 bits | about 600 | Sonnet-class | master, code from `varka-363a2-callers` | 363a1, 362a2, row 697 |
| 363b1 | `PairMul`'s unsigned form | about 250 | Sonnet-class | master | 362b1; 363a1 in the file queue |
| 363b2 | the cut product over 256 bits, the live check, the products' class sweep | about 750 | Sonnet-class | master | 363b1, 363a3, 362b2, 360c1, row 697 |
| 363b3 | the four-word carry network's proof | 200 to 400 | **January 2027, full strength; not a gate before then** (E1, 11.9) | master | 363a2; nothing waits on it |
| 363c1 | class C at two scales, the split form | about 750 | Sonnet-class | master | 363a3, 360b2, 360c1, row 697 |
| 363c2 | class C at two scales with `k = j + 1`, the digit form | 600 to 750 | Sonnet-class, with a stop rule on the method byte budget | master | 363c1, row 697 |
| 363c3 | class C at two scales past 38 rescaled digits, the guarded split form; the sums' class sweep | 400 to 550 | Sonnet-class | master | 363c2 (the file queue), 360c2, 363b2 (`AUTO_CAP`; fix round 3), row 697 |
| 363d | the runner's readings, predictions scored, the row closed | under 100 and results | Sonnet-class | master | 363b2, 363c3 |

The order: the node with its fix before any caller (`PLAN.md` 12.5: the wrong answer is fixed in
363 before it lands); the 128-bit callers next, since they are the smallest step that fuses a
shape and turns two benchmark sections; the 256-bit product after row 362's form C; the split
form after the 128-bit pieces it stands on; then the digit form and the guarded split form,
which reuse the split form's `Cut.ofSplit`, its suite and its file. 363c3 goes last of the
sums because it holds the sums' census sweep, which needs every sum arm built.

*[E1 rework, 28 November 2026.]* 363b3 is January 2027's, by the owner's decision E1 (11.9),
and comes after the code it proves: `ScaleDown`'s four-word rungs ship in 363a1 and are first
called by 363b2, with the differential tests those sheets name (363a1's network tests and the
IR grammar's draw of the node at every rung; 363b2's tests against Spark's `Decimal`, its
products' class sweep, and the coverage rows). Until January: no part writes a placeholder, a
marker line or an empty check for 363b3 into `decimal_scale.smt2` or `VarkaProofFiles`; the
parts that regenerate `decimal_scale.smt2` (363a2, 363c1, 363c2) expect two-word rungs only in
its carry layer; and the pull requests of 363a1 and 363b2 say in their bodies, in one sentence,
"The four-word carry network's proof is part 363b3, January 2027, by the owner's decision E1
of 28 November 2026", since `sql/varka/AGENTS.md`'s rule "A new bounded lowering comes with its
proof file" is met for the four-word rungs only then.

#### Part 363a1, `[VARKA-363] Add the rounding division by a power of ten, null where any word is`

Branch `varka-363a1-scaledown-node`.

**B0.**

    grep -c 'static Entry of(int k, BigInteger bound)' C/varka/VarkaDecimalScale.java   -> 1
    grep -c 'enum PlantedFault' C/varka/VarkaDecimalLowering.java                       -> 1
    grep -c 'record ScaleDown(' C/varka/VarkaVectorIR.java                              -> 0

**Steps.**

1. `VarkaVectorIR`: `enum ScaleRound { HALF_UP, DOWN }` and
   `record ScaleDown(List<VarkaVectorIR> magnitude, int k, BigInteger bound, ScaleRound round,
   int word, int hash)`, copied from the branch (read it with the first command below): two or
   four magnitude words on the long lane, `1 <= k <= 39`, `bound` normalised to its rung in the
   compact constructor, `0 <= word < magnitude.size()`. `props()`: ring `NEVER`, validity
   `STRICT`, weight `VarkaDecimalLowering.laneCalls(this)`, cost feature
   `"ScaleDown" + magnitude.size() + ":r" + rung`, nothing restricted, written as row 362's
   `PairMul` has it (`VARKA-362.md` part 362a1 step 1: `new VarkaNodeProps(NEVER, false,
   STRICT, weight, feature, FILLS_NOTHING, RESTRICTS_NOTHING, LongNarrowReader.NONE)`).
   Canonical text `sdown<words>:<k>:<round>:<word>:r<rung>(...)`.

       git -C /home/max/proj/vecbricks-varka show varka-363-precision-loss:sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorIR.java
       git -C /home/max/proj/vecbricks-varka show varka-363-precision-loss:sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaDecimalLowering.java
       git -C /home/max/proj/vecbricks-varka show varka-363-precision-loss:sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaScaleDownSuite.java

2. `VarkaDecimalLowering.lower(ScaleDown n, VarkaEmitOptions options, Set<PlantedFault>
   faults)` as 11.4.2, from the branch's lowering (the second command), with the `options`
   parameter added as row 360b1 has it for `WideMul`; `PlantedFault.SCALE_COLUMN_ONE_WORD`, its
   arm in the private `lowered`, and `laneCalls(ScaleDown)`.
3. **The NULL rule.** At the end of `lower(ScaleDown, ...)`, in place of the branch's early
   return `m.get(0) & 0` alone: compute the word (the early return's value included), walk its
   graph down to the magnitude nodes without entering them, and for each magnitude word not
   met set `word = bit(OR, word, bit(AND, m.get(i), new WordConst(0)))`. The record's javadoc
   says "null where any magnitude word is null" and the lowering's javadoc says how.
4. **The files the node goes into: a command, not a list kept by hand**, as in `VARKA-362.md`
   part 362a1 step 3. `git grep -lw HashStep -- sql dev | grep -v '\.md$'` prints every file
   that names a composite value node of master; on master `6dafb8bd2f6` it prints 24 files
   (`/home/max/varka-ops/m8-row-plans/scratch-decimal-r2/files-HashStep.txt`). After 360b1 and
   362a1, `git grep -lw PairMul -- sql dev | grep -v '\.md$'` is the second check. **The rule:
   every file the command prints either gets `ScaleDown` or is in the last row, with the
   reason.**

| files the command prints | what `ScaleDown` gets there |
|---|---|
| `VarkaVectorIR.java` | the record, the enum and `permits` (step 1) |
| `Analysis.java` | `collectRefusals`: an empty arm; `analyze`: throws, the node is lowered first (as `WideMul`) |
| `Slots.java` | *[Fix round 3.]* One place: in the one exhaustive switch of `WordWalk.visit` (the demand walk; on master `6dafb8bd2f6` from line 1625, holding `case HashStep x -> demand(...)` at 1729, and after 360b1 also `WideMul`'s arm), `case VarkaVectorIR.ScaleDown x -> throw VarkaErrors.illegalState("ScaleDown is lowered before the analysis");` beside `WideMul`'s: a lowered node never reaches it. The CSE condition at line 1014 is a leaf's rule and does not get the node. |
| `VarkaVectorWalk.java` | throws, as `WideMul`: a lowered node never reaches the walk |
| `VarkaKernelPlan.java`, `VarkaChronoCompiler.java` | the exhaustive list that holds `WideMul _` |
| `VarkaRangeAnalysis.java` | `VarkaValueRange.UNKNOWN` |
| `VarkaSameAnswer.java` | beside `WideMul`, by its magnitude children |
| `VarkaReference.java` | the quotient by `BigInteger` division alone, never the table; null where any magnitude word is null |
| `VarkaCanonicalIr.java` | the `sdown` render and parse arms |
| `VarkaIrGrammar.java` | its bounds, and a draw: the node over leaves masked to its rung's bound, each emitting option set with `maxChainDepth(64)` |
| `VarkaSmtNodes.java` | `REFUSED`, as `WideMul` |
| `VarkaGuardSpecs.java`, `VarkaWordCensus.java` | the arm `WideMul` has there |
| `VarkaLaneTypeSuite.scala`, `VarkaRangeAnalysisSuite.scala` (`admissible`), `VarkaReproductionSuite.java`, `VarkaWithChildrenSuite.scala`, `VarkaIrFuzzSuite.scala`, `VarkaEmitCostSuite.scala`, `VarkaEmitterBudgetSuite.scala` | each suite's register of nodes; the old branch did not register the node in the last three of `VarkaEmitterBudgetSuite`, `VarkaReproductionSuite`, `VarkaWithChildrenSuite`, and this part must |
| `VarkaHashCompiler.java`, `VarkaHashLowering.java`, `VarkaEmitterHashSuite.java` | **nothing**: `HashStep`'s own compiler, lowering and suite |

   Two files the command cannot print: `VarkaDecimalLowering.java` (step 2) and
   `VarkaEmitCostTable` (a price for each feature `ScaleDown<words>:r<rung>`, as
   `VarkaEmitCostSuite` asks). The old branch also edited `VarkaEmitBudget`, `VarkaEmitCost`
   and `VarkaOperandPosition`: do not port those hunks, `props()` answers them (`VARKA-489.md`,
   "Adding a node, after 489c"). **Stop rule:** if `build/sbt catalyst/Test/compile` passes
   while a file of the table's other rows does not name `ScaleDown`, or fails in a file the
   table does not hold, stop and report. `python3 dev/varka_push_check.py` (rule R1) names a
   fixture still missing.
5. `T/varka/VarkaScaleDownSuite.java` from the branch (the third command; five tests: every
   entry in both roundings at every word against `BigInteger` and
   `VarkaDecimalScale.halfUpDivide` over the divisor table, the ties, the bound and Trino's
   `10^38 - 1`; the null rows; the rung, the text, the parse and the refusals; the plant; the
   heights), plus two: (6) **the regression**: `ScaleDown([c0, c1], k = 1, bound 2^64 - 1,
   HALF_UP, word 0)` over two long columns with `c0 = 5` and `c1` null on a row answers null in
   the kernel and in `VarkaReference`, at 2, 4 and 8 lanes; and word 1 of the same node
   likewise. (7) for every entry, rounding and word, every magnitude word is reached by the
   lowered graph (the walk of step 3, asserted).

**The failure only test 6 catches:** a value where the rule says null. Run test 6 before step 3
is written: it must fail, answering 1. If it passes there, stop and report.

**Commands.**

    build/sbt catalyst/Test/compile                                   -> [success]
    build/sbt 'catalyst/testOnly *VarkaScaleDownSuite'                -> Passed: Total 7, Failed 0
    build/sbt 'catalyst/testOnly *VarkaNodePropsSuite *VarkaLaneTypeSuite *VarkaIrFuzzSuite
        *VarkaWithChildrenSuite *VarkaSmtNodesSuite *VarkaEmitCostSuite *VarkaEmitterBudgetSuite
        *VarkaRangeAnalysisSuite *VarkaReproductionSuite *VarkaDecimalScaleSuite'   -> [success]
    python3 dev/varka_push_check.py                                   -> its R1 names no missing fixture
    dev/varka_regen_generated.sh --all                                -> varka regen: done

If the count passes 950 lines, move test 1's table into part 363a2. **Not here:**
`VarkaDecimalCompiler`, a proof file, a coverage row.

#### Part 363a2, `[VARKA-363] Prove the rounding division's carry network at two words`

Branch `varka-363a2-carry-proof`. `VarkaProofFiles.carryNetwork` from the branch (128 lines):
per entry of a two-word rung (rungs 0 to 4, 195 entries), the network transcribed, no column's
sum reaching `2^63`, the kept limbs equal to `N * c + 2^L`; the plant refuted at `k = 1` under
`2 * 10^38 - 2` and witnessed under `2^128 - 1` at `N = 2^64 - 1`. Regenerate
`decimal_scale.smt2`; bring `results/VARKA-363-carry-proof-laptop.txt` from the branch.

    git -C /home/max/proj/vecbricks-varka show varka-363-precision-loss:sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaProofFiles.java
    git -C /home/max/proj/vecbricks-varka show varka-363-precision-loss:sql/varka/plans/m8/results/VARKA-363-carry-proof-laptop.txt

    dev/varka_prove.sh --solver both sql/varka/proofs/decimal_scale.smt2
        -> exit status 0, and one line a solver that ends "holds":
           "<solver> decimal_scale.smt2  <N> checks  <seconds> s  holds"; "FAILED" is a stop
    build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'                    -> [success]

State both solvers' times before and after. The decision rule: if the file's whole time under
either solver passes 60 seconds, stop and report (the branch's hand transcription ran in under
ten); the four-word rungs are part 363b3's and are not rendered here. *[E1 rework, 28 November
2026.]* By the owner's decision E1 work past that stop is January 2027's: a two-word check that
does not decide within the 60 seconds moves to part 363b3's January text with its solver
output quoted, the rest lands here, and nothing waits on it.

#### Part 363a3, `[VARKA-363] Round the decimal products and sums cut by one digit`

Branch `varka-363a3-128bit-cuts`.

**B0.**

    grep -c 'record ScaleDown(' C/varka/VarkaVectorIR.java                 -> 1
    grep -c 'Optional<Pair> productWords(' C/VarkaDecimalCompiler.java     -> 1 (362a2)
    grep -c 'LONG_LANE_MAX_CHAIN_DEPTH' C/varka/VarkaEmitBudget.java       -> 1 or more
    grep -c 'static VarkaVectorIR carry(' C/VarkaDecimalCompiler.java      -> 1
    grep -c 'Optional<Pair> bigintPair(' C/VarkaDecimalCompiler.java       -> 1
    grep -c 'TWO_LANE_WIDE_REASON' C/VarkaDecimalCompiler.java             -> 1 or more
    grep -c '^  @Test' T/varka/VarkaDecimalPairSuite.java                 -> N0 (write it down)
    ls T/VarkaDecimalCutSuite.java                                         -> fails (new here)

**Steps.**

1. `VarkaDecimalCompiler`: `record Cut`, `cutPair`, `cutProduct`, `cutSum`, `cutSumWords`,
   `rounded`, `negatedWhere` as 11.4.3 and 11.4.4, from the branch:

       git -C /home/max/proj/vecbricks-varka show varka-363a2-callers:sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaDecimalCompiler.java

   `pair`'s switch gains the three arms (`Multiply`, `Add`, `Subtract` when `cutPair`) ahead of
   the uncapped ones, each asking `sink.declinesTwoLaneChecks()` first and declining with
   `TWO_LANE_WIDE_REASON`. Both checks of this part are dead, so it calls none of 360c1's
   overflow helpers. The branch's "as a value it declines naming the store" is not copied.
   Reasons: a cut this part does not build keeps master's "is row 363's" texts; grep the tests
   for each text before rewording. *[Fix round 3.]* The branch's file also holds `pairProduct`
   split into `productWords` (its lines 874 to 911): part 362a2 landed that split, so copy
   nothing of those lines; `cutProduct` (the branch's lines 545 to 555) is `productWords`'s
   second caller, and B0's `productWords` line is the check that 362a2 did it.
2. `T/VarkaDecimalCutSuite.java` from the branch (the command at the end of this step; three
   tests: `Cut` over every
   type pair held to `results/VARKA-363-shapes.txt`'s cut and live counts and 29568 live class
   C sums; the arms' shapes exactly the 9616 products and 1024 sums, all at `k = 1`; the
   rounding at the IR level), plus kernel tests in the suite that holds the pair's outputs,
   options pinned as this section's opening says: (4) `DECIMAL(19, 8) * DECIMAL(19, 8)` to
   `DECIMAL(38, 15)` against Spark's `Decimal` over the scale-1 ties `14, 15, 16, -14, -15,
   -16, 25, -25` (as last digits) and 5000 seeded draws, in the three modes, one shape hash;
   (5) `DECIMAL(38, 10) + DECIMAL(38, 10)` and the difference, `10^38 - 1` with itself giving
   `20000000000000000000000000000000000000`, and Trino's two capped-add vectors
   (`READING_TRINO_DECIMAL.md` 10, item 3); (6) a `BIGINT` times a `DECIMAL(18, 8)`; (7) at
   `maxChainDepth(16)` each shape declines, and the reason contains `chain deeper than
   MAX_CHAIN_DEPTH=16` (decision 9 of the decisions section); (8) the two-lane decline, by the
   recipe of `VARKA-362.md` part 362a2 step 3: `VarkaExpressionCompiler.declines(entries, columns,
   options, new VarkaAdmission(Set.of(), true))` returns one decline whose reason is
   `TWO_LANE_WIDE_REASON` for each arm, and with `false` none.

       git -C /home/max/proj/vecbricks-varka show varka-363a2-callers:sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaDecimalCutSuite.java

   *[Fix round 3.]* Earlier declines this part turns into fuses (11.6's rule): 360c1's test 5,
   `cappedSumsWithoutPrecisionLoss`, asserts that `DECIMAL(38, 10) + DECIMAL(38, 10)` under the
   default setting declines naming row 363: it is a one-scale cut by one digit and fuses here
   (test (5) of this part); 361a's `typeRule` holds the 24 pairs `BIGINT * DECIMAL(18, s)`, `s`
   from 7 to 18, both orders, to a decline naming row 363, and its `productsThatDecline` holds
   one of them to the text: each is `p1 + p2 = 38` cut by one digit, `cutPair`'s, and fuses
   here (test (6)). `typeRule`'s count of fused pairs grows by those 24.

3. Coverage family "DECIMAL rescale down": `d19s8 * d19s8b`, `d38s10 + d38s10b`,
   `d38s10 - d38s10b`, as a projection and under a comparison. `census_curated.txt`: no change
   (`+`, `-` and their TRY forms hold `dec^` already; `*` holds it since part 362a2). The
   baseline's `DECIMAL(19, 8) x * y as a value, its scale cut` and
   `DECIMAL(38, 10) x + y as a value, its scale cut`, both densities, change from `Fusion.NONE`
   to `fusedFilter` (`VARKA-362.md` part 362a2 step 5: `Fusion.WHOLE`, and `NONE` on a session
   that refuses a 64-bit condition). The warm-up gains one cut product and one cut sum.

    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite *VarkaDecimalPairSuite
        *VarkaDecimalArithmeticSuite *VarkaExpressionCompilerSuite *VarkaCoverageSuite'   -> [success]
    build/sbt 'sql/testOnly *VarkaDecimalBaselineQueriesSuite *VarkaDifferentialHarnessSuite'
                                                                      -> [success]
    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite'                -> Passed: Total 3
    build/sbt 'catalyst/testOnly *VarkaDecimalPairSuite'               -> Passed: Total N0 + 5
    dev/varka_emit.sh "x + y" --columns x:dec38s10,y:dec38s10   -> ends FusedOutput(0)

*[Fix round 3: the totals.]* `N0` is B0's count. In the chain's order it is 43 (360b2's 28,
360c1's 37, 360c2's 41, 362a2's two more), so 48 here; a part that merged out of that order
moves `N0`, never the `+ 5`. A total other than `N0 + 5` means a test was skipped or lost:
stop and report.

**Not here:** a dividend past two words, a live check, two scales.

#### Part 363b1, `[VARKA-363] Multiply two magnitudes into 256 bits`

Branch `varka-363b1-unsigned-product`. `PairMul` gains `enum Sign { TWOS, UNSIGNED }` as a record
component, `TWOS` for every existing caller, in the canonical text as `:u`. Under `UNSIGNED` (legal
at `words = 4` only) `columnSum` leaves out the folds: the `sx`, `sy`, `nxl`, `nxh`, `nyl`,
`nyh` and `top` terms; under `rightLong` the right operand is one unsigned word. `VarkaReference`
multiplies the unsigned readings. `VarkaPairMulSuite` gains two tests: the unsigned product
against `BigInteger` over the values of part 362a1 read unsigned and 20000 patterns, a pair and
a long right; and the text's round trip. The registry suites and the regeneration as in part
363a1. The model: 64368 pattern pairs each, none wrong; words 8, 12, 16 and 20 levels.

*[Fix round 3, 28 November 2026: B0, the record's shape, the text, the cost feature, `yHi`, the
total.]*

**B0.**

    grep -c 'record PairMul(' C/varka/VarkaVectorIR.java                  -> 1
    grep -c 'columnSum' C/varka/VarkaDecimalLowering.java                 -> 1 or more (362b1)
    grep -c 'UNSIGNED' C/varka/VarkaVectorIR.java                         -> 0
    grep -c '^  @Test' T/varka/VarkaPairMulSuite.java                     -> 9

**The record.** `record PairMul(VarkaVectorIR xLo, VarkaVectorIR xHi, VarkaVectorIR yLo,
VarkaVectorIR yHi, int words, int word, boolean rightLong, Sign sign, int hash)`, with `enum Sign
{ TWOS, UNSIGNED }` nested in it. The compact constructor adds one test: `UNSIGNED` with `words
!= 4` throws `VarkaErrors.illegalArgument("an unsigned PairMul is the 256-bit form, words = 4")`.
Two public constructors without `hash`: the one 362a1 wrote, `(xLo, xHi, yLo, yHi, words, word,
rightLong)`, which passes `Sign.TWOS`, so that 362a2's and 362b2's calls compile unchanged; and
`(xLo, xHi, yLo, yHi, words, word, rightLong, sign)`. `props()`'s cost feature is
`getClass().getSimpleName() + words + (rightLong ? "/l" : "") + (sign == Sign.UNSIGNED ? "/u" :
"")`: two new features, `PairMul4/u` and `PairMul4/l/u`, each priced in `VarkaEmitCostTable` as
`VarkaEmitCostSuite` asks (362a1 step 3's rule). The canonical text is
`pmul<words>:<word>[:l][:u](xLo, xHi, yLo, yHi)`: `:u` after `:l` where both are set, as in
`pmul4:3:l:u(...)`; the parse arm reads them in that order.

**`yHi` under `rightLong`, per sign.** 362a1's rule ("the sign extension of `yLo`") is `TWOS`'s.
Under `UNSIGNED` the right operand is a magnitude, `negatedWhere` of a 64-bit value's pair, and
its high word is 0 on every valid lane, while the sign extension of `yLo` is -1 where the
magnitude is `2^63` (the magnitude of `Long.MIN_VALUE`). So under `UNSIGNED` and `rightLong` the
caller passes as `yHi` the magnitude pair's own high word (`ay.hi()` of 11.4.5), valid exactly
where `yLo` is; the lowering reads no bit of it under either sign, and the null rule (`VARKA-362.md`
11.3 decision 11, D5) gives the same validity as for `TWOS`. The record's javadoc
says both rules. (Review 2 proposed `new WordConst(0)`; that also works, since the lowering does
not read it, but 362a1's rule forbids a constant there, and one rule for both signs is kept.)

**Commands.**

    build/sbt catalyst/Test/compile                                   -> [success]
    build/sbt 'catalyst/testOnly *VarkaPairMulSuite'                  -> Passed: Total 11, Failed 0
    (the registry suites of part 362a1, in its command)               -> [success]
    python3 dev/varka_push_check.py                                   -> its R1 names no missing fixture
    dev/varka_regen_generated.sh --all                                -> varka regen: done

**Done when** those print that and `git diff` of `emitted_bytes.json` moves no existing shape's
bytes (no caller builds `UNSIGNED` before 363b2). **Not here:** `VarkaDecimalCompiler`.

#### Part 363b2, `[VARKA-363] Round a decimal product cut from 256 bits`

Branch `varka-363b2-wide-cut-product`.

**B0.**

    grep -c 'UNSIGNED' C/varka/VarkaVectorIR.java                          -> 1 or more
    grep -c 'Pair rounded(' C/VarkaDecimalCompiler.java                    -> 1
    grep -c 'COLUMN_SUM' C/VarkaDecimalCompiler.java                       -> 1 or more (362b2)
    grep -c 'static Pair guardedPair' C/VarkaDecimalCompiler.java          -> 1 (360c1)
    grep -c 'LONG_LANE_MAX_CHAIN_DEPTH' C/varka/VarkaEmitBudget.java       -> 1 or more
    grep -c '^  @Test' T/varka/VarkaDecimalProductSuite.java              -> P0 (write it down)
    grep -c '^  @Test' T/VarkaDecimalCutSuite.java                        -> C0 (3 in the chain's order)

**Steps.**

1. `VarkaDecimalCompiler.wideCutProduct` as 11.4.5, the two-lane test first; its arm in `pair`
   for `ProductForm.CUT` with `p1 + p2 >= 39`, either setting (the form is read from the types,
   so `bounded`'s cuts need nothing more). A 64-bit operand goes on the right. The guard is
   built only where `Cut.checked`, as one call `guardedPair(q3 | (q2 | outside), r.lo, r.hi,
   decimalOverflow(m.evalMode()), type)` (11.3 decision 7; never `overflowOf` alone).
2. Tests, options pinned: (1) `DECIMAL(38, 10) * DECIMAL(38, 10)` to `DECIMAL(38, 6)`:
   `10^19 * 10^19` (unscaled) is `10^24`; `(10^38 - 1)^2` is null under LEGACY and TRY and
   declines the batch under ANSI; (2) at the IR level, the guard over `ScaleDown` at `k = 14`:
   the magnitude `10^52 - 5 * 10^13` sets it and one less does not; (3) arrow-rs's
   `(38, 18) * (38, 18)` to scale 28 and `0.000000000000000001 * 1.555555555555555555`, Comet's
   `overflow_scale` and `overflow_precision`, rapids' triples, 38-nines edge and `5e37 * 2`,
   every expected value from Spark's `Decimal` in the test; (4) SPARK-45786's generator ported
   from `ArithmeticExpressionSuite` ("SPARK-45786: Decimal multiply, divide, remainder, quot"),
   `Multiply` only, both settings, a thousand draws through the kernel; (5) `k = 39`:
   `DECIMAL(38, 38) * DECIMAL(38, 38)`; (6) Polars's grid, the cut cells; (7) two shape hashes
   for a live check, one for a dead one; (8) **the products' class sweep**, over every class of
   product that can reach the cap, one type pair a class (the first in the order `p1, s1, p2,
   s2`), each under the default setting and then with
   `spark.sql.decimalOperations.allowPrecisionLoss=false`, compiled as an output and under a
   comparison with a column, at the session's auto cap (`VarkaMatrixRuns.BASE`, which carries
   row 697's 64 at 256 bits). *[Fix round 3.]* The options are a constant this part declares in
   `VarkaDecimalPairSuite` beside `DEEP`, `static final VarkaEmitOptions AUTO_CAP`, the
   auto cap with every other size bound pinned as #1798's auto options pin them, at 256 bits
   whatever the host: `VarkaMatrixRuns.BASE.withMaxChainDepth(VarkaEmitBudget.AUTO_CHAIN_DEPTH)
   .withValidityByWidth(true).withChainCapLongBits(0).withLanesOverride(4)
   .withMethodByteBudget(VarkaEmitOptions.DEFAULTS.methodByteBudget())
   .withCse(VarkaEmitOptions.DEFAULTS.cse())`; under the matrix's `methodByteBudget=0` a
   decline on 64 distinct ops would otherwise fail the sweep for a reason that is not the cap. The
   classes: row 362's form N (by a pair, by a long), row 362's form C (by a pair, by a long), part
   363a3's cut products over 128 bits (rungs 0 to 2), and the 180 classes (rung, `k`, live, by a
   long) of cut products with `p1 + p2 >= 39`. The test records each decline with its reason and
   fails on any reason that does not contain `chain deeper than MAX_CHAIN_DEPTH=`; it prints one
   line, `product class sweep: <n> classes, <d> at the cap`.
3. **The census, by rule.** If test 8 records no decline under the default setting: `*` and
   `try_multiply` change from `dec^` to `dec` in `census_curated.txt`, and their note drops
   "the pair counts with". If it records any: the cells stay `dec^`, the note names the count
   of classes at the cap, and the pull request says so. The builder does not judge it. (By the
   model's heights, 11.3 decision 9, the expected outcome is `dec^` with a count.)
4. Coverage rows `d38s10 * d38s10b` (live), `d20s10 * d20s10b` (dead), `d38s38 * d38s38b`,
   `try_multiply(d38s10, d38s10b)`, with a `domain` where live. The baseline's
   `DECIMAL(38, 10) x * y as a value, its scale cut`, both densities, changes from `Fusion.NONE`
   to `fusedFilter` if `VarkaDecimalBaselineQueriesSuite`'s fuse check passes for it; if the
   shape declines at the cap, the section stays `NONE` and the pull request says so.

*[Fix round 3.]* Earlier declines this part turns into fuses (11.6's rule): 362a2's test (6)
holds rapids' `577694938495380589068894346.7625198736 * -1258508260891400005608241690.15647` to a
decline naming row 363; a product of 70 operand digits cut by 9, it is this part's (or the
cap's, if test (8) records its class at the cap).

**The failure only test 2 catches:** a check that reads the quotient's low pair alone, or reads
it after the sign. Plant it once (drop `q[3]` and `q[2]` from the guard): a magnitude of
`2^128 * 10^14` then passes, and the test must fail.

    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite *VarkaDecimalProductSuite
        *VarkaCoverageSuite'                                          -> [success]
    build/sbt 'sql/testOnly *VarkaCoverageDifferentialSuite *VarkaDecimalBaselineQueriesSuite'
                                                                      -> [success]
    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite'                -> Passed: Total C0 + 1
    build/sbt 'catalyst/testOnly *VarkaDecimalProductSuite'            -> Passed: Total P0 + 7

*[Fix round 3: where the tests go, the sweep's options, the totals.]* Test (2), at the IR
level, is a new test in `T/VarkaDecimalCutSuite.java`; tests (1) and (3) to (8) are new tests
in `T/varka/VarkaDecimalProductSuite.java` (362a2's suite of products), under
`VarkaDecimalPairSuite.DEEP`, but for test (8). `P0` is 14 in the chain's order (362a2's six,
362b2's eight), so 21 here.

#### Part 363b3, the four-word carry proof (January 2027, full strength; not a gate before then)

The carry layer of part 363a2 for rungs 5 to 9 (195 entries), `DOWN`'s network if rows 351 or
364 are to call it, and the check of 11.4.5. **What makes it hard:** in the branch's hand
transcription the four-word rungs took Z3 47 seconds and cvc5 23, a free witness of the plant
ran past two minutes, and whether the layer joins `decimal_scale.smt2` or a file of its own,
and how its claims are cut to stay decidable, is a judgement on solver behaviour.

*[E1 rework, 28 November 2026.]* By the owner's decision E1 (a) this part waits for a
full-strength builder in January 2027; the text above is kept for that day. It starts from the
master of that day, after 363b2 and the claims 363c1 and 363c2 add to `decimal_scale.smt2`, and
adds its layer beside them. Nothing waits on it: 363d scores and marks the row without it.

#### Part 363c1, `[VARKA-363] Round a decimal sum of two scales by its finer operand alone`

Branch `varka-363c1-split-sum`.

**B0.**

    grep -c 'Pair pairTimesWord(' C/VarkaDecimalCompiler.java              -> 1 (360b2)
    grep -c 'Pair rounded(' C/VarkaDecimalCompiler.java                    -> 1 (363a3)
    grep -c 'static Pair guardedPair' C/VarkaDecimalCompiler.java          -> 1 (360c1)
    grep -c 'LONG_LANE_MAX_CHAIN_DEPTH' C/varka/VarkaEmitBudget.java       -> 1 or more
    grep -c '^  @Test' T/varka/VarkaDecimalPairSuite.java                 -> N0 (write it down)
    grep -c '^  @Test' T/VarkaDecimalCutSuite.java                        -> C0 (4 in the chain's order)

**Steps.**

1. `VarkaDecimalCompiler.splitSum` as 11.4.6, the two-lane test first, admitted where the scales
   differ, `k <= j` and `p_a + i <= 38`; `A` as 11.4.6 writes it (a second `pairTimesWord` only
   where `i > 18`); the check through `guardedPair` and `outside38` in the mode
   `decimalOverflow(evalModeOf(e))`. The two declines of 11.3 decision 5 with their reason
   texts. *[Fix round 3, 28 November 2026: the glue below is written out; it replaces "ahead of
   master's 'rows 360 and 363's'" (360c1 deletes that text) and the prose of `ofSplit`.]*
   Beside `record Cut`, a record of its own, and in `Cut` its constructor of a split:

       /**
        * A class C sum or difference of two scales (VARKA-363.md 11.4.6 to 11.4.8): a, the
        * operand of the smaller scale, is rescaled up by j digits, Spark cuts k, and the
        * result's scale is a's plus i = j - k (-1 for the digit form).
        */
       record Split(Form form, int k, int j, int i, boolean leftIsA, boolean checked) {
         enum Form { SPLIT, DIGIT, GUARDED }
       }

       // in record Cut, beside ofSum:
       /** t1 + t2 or t1 - t2 typed result, of two scales and cut: its split, else empty. */
       static Optional<Split> ofSplit(DecimalType t1, DecimalType t2, DecimalType result) {
         int big = Math.max(t1.scale(), t2.scale());
         if (t1.scale() == t2.scale() || result.scale() >= big) {
           return Optional.empty();
         }
         boolean leftIsA = t1.scale() < t2.scale();
         DecimalType a = leftIsA ? t1 : t2;
         DecimalType b = leftIsA ? t2 : t1;
         int j = big - a.scale();
         int k = big - result.scale();
         int i = j - k;
         if (k == j + 1) {
           // Every such pair has a coarser operand of 38 digits and a dead check (11.4.7).
           return Optional.of(new Split(Split.Form.DIGIT, k, j, i, leftIsA, false));
         }
         BigInteger ten = BigInteger.TEN.pow(k);
         BigInteger top = digits(a).multiply(BigInteger.TEN.pow(i))
             .add(digits(b).add(ten.shiftRight(1)).divide(ten));
         Split.Form form = a.precision() + i <= DecimalType.MAX_PRECISION()
             ? Split.Form.SPLIT : Split.Form.GUARDED;
         return Optional.of(new Split(form, k, j, i, leftIsA, top.compareTo(digits(result)) > 0));
       }

   `digits` is `Cut`'s own (`10^p - 1`); the type rule never gives `k > j + 1`
   (`class_c_rest.py` asserts it). Empty is the answer for a pair of one scale (363a3's
   `cutPair`) and for a sum Spark does not cut. Over the 606841 ordered pairs of the 779 types
   with precision loss allowed, this code gives `SPLIT` 200720 (193328 unchecked, 7392 checked),
   `DIGIT` 10912 (none checked) and `GUARDED` 22176 (all checked), and with it off none: the
   same code in Python, `/home/max/varka-ops/m8-row-plans/scratch-decimal-r3/sum_classes.py`,
   whose last line is `OK sum classes: 159 under the default, 43 with
   allowPrecisionLoss=false`. Then, in `DC`:

       /** e's split where e is an Add or a Subtract of two decimals (Cut.ofSplit), else empty. */
       private static Optional<Split> splitOf(Expression e) {
         if ((e instanceof Add || e instanceof Subtract)
             && e.dataType() instanceof DecimalType type
             && e.children().apply(0).dataType() instanceof DecimalType t1
             && e.children().apply(1).dataType() instanceof DecimalType t2) {
           return Cut.ofSplit(t1, t2, type);
         }
         return Optional.empty();
       }

       // in pair's switch: after 363a3's two `cutPair` arms, before `case Add a -> widePair`
       case Add a when splitOf(a).isPresent() ->
           splitSum(a, splitOf(a).get(), type, inputs, literals, sink);
       case Subtract a when splitOf(a).isPresent() ->
           splitSum(a, splitOf(a).get(), type, inputs, literals, sink);

       private static Optional<Pair> splitSum(Expression e, Split s, DecimalType type,
           VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
         if (sink.declinesTwoLaneChecks()) {
           return sink.decline(TWO_LANE_WIDE_REASON, e);
         }
         if (s.form() == Split.Form.DIGIT) {          // part 363c2 replaces this decline
           return sink.decline(
               "a decimal sum cut past its coarser operand's scale is row 363's digit form", e);
         }
         if (s.form() == Split.Form.GUARDED) {        // part 363c3 replaces this decline
           return sink.decline("a decimal sum whose rescaled operand can pass 38 digits is "
               + "row 363's guarded split form", e);
         }
         Expression left = e.children().apply(0);
         Expression right = e.children().apply(1);
         Optional<Pair> a = pair(s.leftIsA() ? left : right, inputs, literals, sink);
         if (a.isEmpty()) {
           return Optional.empty();
         }
         Optional<Pair> b = pair(s.leftIsA() ? right : left, inputs, literals, sink);
         if (b.isEmpty()) {
           return Optional.empty();
         }
         // E = a * 10^j + b' (11.4.6): a + b keeps both; a - b negates b; b - a, with a on
         // the right of the Subtract, negates a. Each negation is exact: |v| <= 10^38 - 1.
         boolean sub = e instanceof Subtract;
         Pair av = sub && !s.leftIsA() ? negated(a.get(), sink) : a.get();
         Pair bv = sub && s.leftIsA() ? negated(b.get(), sink) : b.get();
         ... the words of 11.4.6 over av and bv, then the check where s.checked() ...
       }

       private static Pair negated(Pair v, DeclineSink sink) {
         return negatedWhere(v.lo(), v.hi(), sink.longSlot(-1), v.type(), sink);
       }

   The arms sit ahead of `widePair`'s, so no class C sum of two scales reaches 360c1's
   "capped at 38 with its scale cut, is row 363's" text any more; grep the tests for that text
   and for the two new ones before the change (`grep -rn "capped at 38 with its scale cut"
   sql/catalyst/src/test sql/core/src/test`) and move each hit that names a two-scale sum.
   The two B0 greps of parts 363c2 and 363c3, `"row 363's digit form"` and `"row 363's guarded
   split form"`, each read one line of the code above: keep those words on one line.
2. `decimal_scale.smt2` gains the split claim, one check for each `k` from 1 to 32 and the
   plant "always `T1`" at `k = 1` as a `sat` witness, rendered by `VarkaProofFiles` in the text
   of `/home/max/varka-ops/m8-row-plans/scratch-363/gen_split_smt.py` (copy its `define-fun`s).
3. Tests, options pinned. *[Fix round 3: where each test goes, the sweep's counts by form, the
   tie draws written out.]* Test (1) is a new test in `T/VarkaDecimalCutSuite.java`; tests (2)
   to (7) are new tests in `T/varka/VarkaDecimalPairSuite.java`, the suite of 363a3's kernel
   tests (the suite that holds the pair's outputs), each through `checkStored` or, for a live
   check, 360c1's `storedOrDeclined`, under `VarkaDecimalPairSuite.DEEP`; test (5) and test
   (6), which assert declines, under `VarkaMatrixRuns.BASE`. (1) the type sweep: over every
   ordered pair of the 779 types, under both settings, `Cut.ofSplit` with Spark's sum type
   gives `SPLIT` 200720 (193328 unchecked, 7392 checked), `DIGIT` 10912 (none checked),
   `GUARDED` 22176 (all checked) with precision loss allowed and empty for every pair with it
   off; parts 363c2 and 363c3 change no number of it; (2)
   `DECIMAL(30, 2) + DECIMAL(38, 10)` to `DECIMAL(38, 9)` (`k = 1`, `i = 7`), unscaled:
   `1 + (-15)` is `9999999`; `2 + (-15)` is `19999999`; `0 + 15` is `2`; `0 + (-15)` is `-2`;
   `-1 + 15` is `-9999999`; `0 + 0` is `0`; the same as differences with the right operand
   negated; (3) 5000 seeded draws for each of the type pairs of tests (2), (4) and (7),
   against Spark's `Decimal`, sums and differences, both operand orders, in the three modes.
   With `pa` and `pb` the coarser and the finer operand's precisions, row `d` of a draw is: for
   `d % 3 != 0`, `a` and `b'` uniform in `[-(10^pa - 1), 10^pa - 1]` and
   `[-(10^pb - 1), 10^pb - 1]`; for `d % 3 == 0`, a tie of the finer operand with the coarser
   one beside the quotient: `h = 5 * 10^(k - 1)`, `q` uniform in `[0, (10^pb - 1 - h) / 10^k]`,
   `sg` 1 or -1 drawn, `b' = sg * (q * 10^k + h)`, `m = |b'| / 10^j + (d / 3) % 2` (integer
   division), and `a = -sg * m` where `m <= 10^pa - 1`, else `a` uniform. The SQL rows are
   `(a, b')` for `x + y`, `(a, -b')` for `x - y` and `(-a, b')` for `y - x`, so that each row's
   exact value is `E = a * 10^j + b'`. Checked in
   `/home/max/varka-ops/m8-row-plans/scratch-decimal-r3/ties.py` (last line `ALL OK ties:
   45000 checks`): every value within its type, 5685 of the 15000 rows ties of `E` at the
   cut, 2734 of them with an answer whose sign is not `b'`'s, and the split rule equal to
   `HALF_UP` of `E` on every row; (4) a live shape, `DECIMAL(38, 6) + DECIMAL(38, 7)` to
   `DECIMAL(38, 6)` (`k = 1`, `i = 0`), unscaled: `(10^38 - 1) + 4` is `10^38 - 1`, and
   `(10^38 - 1) + 5` rounds to `10^38`: null under LEGACY and TRY, the batch declined under
   ANSI; (5) the declines: `DECIMAL(38, 9) + DECIMAL(38, 10)` (`k = j + 1`) and
   `DECIMAL(38, 0) + DECIMAL(38, 10)` (the rescale) with their reasons; (6) the two-lane
   decline, as in part 363a3; (7) `pairTimesWord` twice: `DECIMAL(10, 0) + DECIMAL(38, 30)` to
   `DECIMAL(38, 27)` (`k = 3`, `i = 27`), unscaled: `(10^10 - 1) + 0` is
   `9999999999000000000000000000000000000`, `1 + 1500` is `1000000000000000000000000002`,
   `1 + (-1500)` is `999999999999999999999999999`, `(10^10 - 1) + (10^38 - 1)` is
   `10099999999000000000000000000000000000`, and 1000 seeded draws against Spark's `Decimal`.
4. Coverage rows `d30s2 + d38s10`, `d38s10 - d30s2`, `try_add(d30s2, d38s10)`. The census keeps
   `dec^` on `+`, `-`, `try_add` and `try_subtract`; their note names the two groups still
   declined. Nothing goes to `m10/SCOPE.md` (11.3 decision 5).

Check test 2's vectors against the exact sum by hand before trusting the kernel: `1 + (-15)` is
`1 * 10^8 - 15 = 99999985` at scale 10, and `99999985 / 10` rounds to `9999999`.

    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite *VarkaDecimalPairSuite *VarkaCoverageSuite
        *VarkaProofFilesSuite'                                        -> [success]
    dev/varka_prove.sh --solver both sql/varka/proofs/decimal_scale.smt2
        -> exit status 0, and one line a solver that ends "holds"; "FAILED" is a stop
    dev/varka_emit.sh "x + y" --columns x:dec30s2,y:dec38s10   -> ends FusedOutput(0)
    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite'                -> Passed: Total C0 + 1
    build/sbt 'catalyst/testOnly *VarkaDecimalPairSuite'               -> Passed: Total N0 + 6

*[Fix round 3: the totals.]* `N0` is 48 in the chain's order (363a3's `N0 + 5`; 363b2 adds
none to this suite), so 54 here.

#### Part 363c2, `[VARKA-363] Round a decimal sum cut past its coarser operand's scale`

*[New in the fix round of 28 November 2026.]* Branch `varka-363c2-digit-sum`. The digit form of
11.4.7, for the 10912 type pairs with `k = j + 1`.

**B0.**

    grep -c 'Optional<Pair> splitSum(' C/VarkaDecimalCompiler.java         -> 1 (363c1)
    grep -c "row 363's digit form" C/VarkaDecimalCompiler.java             -> 1
    grep -c 'LONG_LANE_MAX_CHAIN_DEPTH' C/varka/VarkaEmitBudget.java       -> 1 or more
    grep -c '^  @Test' T/varka/VarkaDecimalPairSuite.java                 -> N0 (write it down)

**Steps.**

1. `private static Optional<Pair> digitSum(Expression e, Split s, DecimalType type,
   VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink)` in `DC`, beside
   `splitSum` (*[fix round 3]*: it takes 363c1's `Split`, whose `j` it reads; `k = j + 1`), as
   11.4.7 line by line: the two-lane test first; `a` and `b'` as pairs as `splitSum` takes them; the
   four `ScaleDown` nodes with the bounds the code block gives (each normalised to its rung by the
   node), `HALF_UP`, the words named there and no other; `carry` for every pair sum; the four
   constants `t` and the constants 10, 9, 1, 5, `hj - 1`, `hj`, `hj1` as long slots
   (`sink.longSlot`, a two-word constant as two slots by `DecimalWords.of`); `c_gt` and `c_ge` as
   sums of `CondValue(Compare(GT or GE, y, slot(t)), LaneType.LONG)`; the select as two
   `IfElse` on `Compare(LT, Tt.hi, slot(0))`. No guard: the check is dead. In `splitSum`, the
   `Split.Form.DIGIT` test returns `digitSum(e, s, type, inputs, literals, sink)` in place of
   363c1's decline; delete that reason text (grep the tests for it first). `digitSum` takes its
   operands with the lines `splitSum` has for them, from `Expression left` to `Pair bv`
   (`pair` of each side by `s.leftIsA()`, a `Subtract`'s negation by `negated`), copied; its
   `a` is `av` and its `b'` is `bv`.
2. `decimal_scale.smt2` gains the digit claim, one check for each `j` from 1 to 31 and the plant
   "always `Tg`" at `j = 1` as a `sat` witness, rendered by `VarkaProofFiles` in the text of
   `/home/max/varka-ops/m8-row-plans/scratch-decimal-r2/gen_digit_smt.py` (copy its
   `define-fun`s; the claim is `unsat` under both solvers at every `j`, 11.4.7).
3. Tests, options pinned. *[Fix round 3: where each goes, test (1) rewritten, the draws and
   the totals written out.]* Tests (2) to (5) are new tests in
   `T/varka/VarkaDecimalPairSuite.java`, (2) and (3) under `VarkaDecimalPairSuite.DEEP`, (4)
   and (5) as they say. (1) is no new test: 363c1's sweep already counts the 10912 `DIGIT`
   pairs, none checked, and keeps every number; in 363c1's test (5), delete the line of
   `DECIMAL(38, 9) + DECIMAL(38, 10)` and its reason (it fuses now; test (2) holds it), so that
   test (5) holds the `GUARDED` line alone; (2) `DECIMAL(38, 9) + DECIMAL(38, 10)` to
   `DECIMAL(38, 8)` (`j = 1`, `k = 2`), unscaled `a` at scale 9 and `b` at scale 10: `1 + 49` is
   `1`, `1 + 40` is `1`, `-1 + (-40)` is `-1`, `1 + (-60)` is `-1` (a negative tie), `0 + 50` is
   `1`, `0 + (-50)` is `-1`, `0 + 49` is `0`, `-1 + 50` is `0`, `2 + (-70)` is `-1`, `0 + 0` is `0`;
   `(10^38 - 1) + (10^38 - 1)` is `11000000000000000000000000000000000000` and its negation the
   negation; `(10^38 - 1) + (-(10^38 - 1))` is `9000000000000000000000000000000000000`; as
   differences `a - b`: `1 - (-49)` is `1`, `1 - 60` is `-1`, `(10^38 - 1) - (-(10^38 - 1))` is
   `11000000000000000000000000000000000000`; with the finer operand first, `b - a`: `49 - (-1)`
   is `1`, `-50 - 0` is `-1`; (3) seeded draws over 20 of the 10912 type pairs (test
   (2)'s first, then 19 drawn with the seed), 250 rows each, sums and differences, both
   operand orders, against Spark's `Decimal` in the three modes, and one shape hash for the
   three modes (the check is dead). With `pb` the finer operand's precision and `C = (10^pb -
   1) / 10^j` (integer division), row `d` is: for `d % 3 != 0`, `a` uniform in `[-(10^38 - 1),
   10^38 - 1]` and `b'` uniform in `[-(10^pb - 1), 10^pb - 1]`; for `d % 3 == 0`, a tie of the
   exact sum at the cut: `a` uniform as before, `c` uniform in `[-C, C]`, then `c'` the value
   nearest `c` with `|c'| <= C` and `|a + c'| % 10 == 5` (look from `c - 10` to `c + 10`; if
   none, the row is drawn as for `d % 3 != 0`), and `b' = c' * 10^j`, so that `E = (a + c') *
   10^j` ends in the digit 5 at the cut. The SQL rows are `(a, b')`, `(a, -b')` and `(-a, b')`
   as in 363c1's test (3). Checked in `scratch-decimal-r3/ties.py` at six `(pb, j)`: 10109 of
   30000 rows ties, 4997 of them with a negative answer, the digit rule equal to `HALF_UP` of
   `E` on every row; (4) the two-lane decline, as in part 363a3; (5) at `maxChainDepth(16)` the
   shape declines with the cap's reason (11.3 decision 9).
4. Coverage rows `d38s9 + d38s10`, `d38s10 - d38s9`, `try_add(d38s9, d38s10)`. The census note
   of `+`, `-`, `try_add`, `try_subtract` drops this group.

**The failure only test 2 catches:** the select read the wrong way, `Tg` everywhere. Plant it
once (return `Tg`'s words): `1 + (-60)` then answers `0`, and the test must fail.

**Stop rule, the method byte budget:** the kernel holds six `ScaleDown` words. If
`dev/varka_emit.sh` below does not end `FusedOutput(0)`, or any test's compile declines with a
reason about the method's bytes rather than the cap's, stop and report: the lead decides
whether the form is cut, and nothing in this plan settles it.

    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite *VarkaDecimalPairSuite *VarkaCoverageSuite
        *VarkaProofFilesSuite'                                        -> [success]
    dev/varka_prove.sh --solver both sql/varka/proofs/decimal_scale.smt2
        -> exit status 0, and one line a solver that ends "holds"; "FAILED" is a stop
    dev/varka_emit.sh "x + y" --columns x:dec38s9,y:dec38s10   -> ends FusedOutput(0)
    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite'                -> Passed: Total, unchanged
    build/sbt 'catalyst/testOnly *VarkaDecimalPairSuite'               -> Passed: Total N0 + 4

*[Fix round 3: the totals.]* `N0` is 54 in the chain's order, so 58 here; the cut suite is
5 in that order, as 363c1 left it.

**Not here:** a guard, a rescale past 38 digits, the census flip.

#### Part 363c3, `[VARKA-363] Round a decimal sum whose rescaled operand can pass 38 digits`

*[New in the fix round of 28 November 2026.]* Branch `varka-363c3-guarded-split-sum`. The
guarded split form of 11.4.8, for the 22176 type pairs with `p_a + i > 38`, and the sums' class
sweep that moves the census.

**B0.**

    grep -c 'Optional<Pair> digitSum(' C/VarkaDecimalCompiler.java         -> 1 (363c2)
    grep -c 'static Pair rescaleGuard' C/VarkaDecimalCompiler.java         -> 1 (360c2)
    grep -c "row 363's guarded split form" C/VarkaDecimalCompiler.java     -> 1
    grep -c 'LONG_LANE_MAX_CHAIN_DEPTH' C/varka/VarkaEmitBudget.java       -> 1 or more
    grep -c '^  @Test' T/varka/VarkaDecimalPairSuite.java                 -> N0 (write it down)
    grep -c 'AUTO_CAP' T/varka/VarkaDecimalPairSuite.java                 -> 1 or more (363b2)

**Steps.**

1. `splitSum` takes the case `p_a + i > 38`: `A = pairTimesWord(rescaleGuard(a, i, sink), i,
   new DecimalType(38, s_a + i), sink)`; everything else as 363c1. `i` is at most 6 there, so
   one `pairTimesWord`. *[Fix round 3.]* In `splitSum`, delete the `Split.Form.GUARDED` test
   and its decline (grep the tests for the text first); `SPLIT` and `GUARDED` then share the
   body, and `A` reads `s.form() == Split.Form.GUARDED` to take the guard. `Cut.ofSplit` is
   unchanged: it gave the form since 363c1.
2. Tests, options pinned. *[Fix round 3: where each goes, test (1) rewritten, test (6)'s key
   and the totals written out.]* Tests (2) to (6) are new tests in
   `T/varka/VarkaDecimalPairSuite.java`, (2) and (3) through `storedOrDeclined` under
   `VarkaDecimalPairSuite.DEEP`, (4) and (5) as they say, (6) under `AUTO_CAP`. (1) is no new
   test: 363c1's test (5) holds only the `GUARDED` line since 363c2, and that shape fuses now
   (test (2) holds it), so delete 363c1's test (5); 363c1's sweep keeps every number, since
   every class C pair of two scales, 233808 (193328 and 10912 unchecked, 7392 and 22176
   checked), now has an arm; (2)
   `DECIMAL(38, 0) + DECIMAL(38, 10)` to `DECIMAL(38, 6)` (`j = 10`, `k = 4`, `i = 6`; the
   guard's edge `|a| <= 10^32 - 1`), unscaled: `(10^32 - 1) + 0` is
   `99999999999999999999999999999999000000` in every mode; `(10^32 - 1) + (10^38 - 1)` and
   its negation overflow inside the guard: null under LEGACY and TRY, the batch declined under
   ANSI; `10^32 + (-5 * 10^13)` is `99999999999999999999999999995000000000` in Spark, and its
   batch declines (`STATUS_VALUE_RANGE` set) under LEGACY, ANSI and TRY; `10^32 + 0` overflows
   in Spark and its batch declines in every mode; `1 + 15000` is `1000002`, `1 + (-15000)` is
   `999999`, `1 + 14999` is `1000001`; a test of a declined batch goes through 360c1's
   `storedOrDeclined`, with a row past the guard put in `raised` by the test's own
   `|a| > 10^32 - 1`; (3) 5000 seeded draws inside the guard, a third ties, against Spark's
   `Decimal`, the three modes; (4) two shape hashes, LEGACY and TRY one, ANSI another; (5) the
   two-lane decline and, at `maxChainDepth(16)`, the cap's, as in part 363c2; (6) **the sums'
   class sweep**, as part 363b2's test 8 for products: one type pair a class, under the
   default setting and then with `allowPrecisionLoss=false`, as an output and under a
   comparison, at the session's auto cap. *[Fix round 3: the key, the order and the counts,
   replacing "the digit form by height" and "the guarded split form by height", which a test
   cannot compute.]* For each setting apart: enumerate every ordered pair `(t1, t2)` of the 779
   types, `p1` from 1 to 38, `s1` from 0 to `p1`, then `p2` and `s2` likewise; type `a + b` by
   resolving it under that setting (`withConf`), its `dataType()` the result `r`; skip the pair
   where `r.precision() <= 18` (row 349's long sum); key it; keep the first pair of each key.
   The key, with `s = max(s1, s2)`, `exact = max(p1 - s1, p2 - s2) + s + 1`, `width` the two
   letters `L` (precision 18 or less) or `P` for `t1` then `t2`, and where `s1 != s2`, `a` the
   operand of the smaller scale and `k = s - a.scale()`, `rescale` = `L` or `P` for `a` and
   then `1` (`k <= 9`), `2` (`k <= 18`) or `W` (two words), else `none`:

       exact <= 38                 ->  ("U", width, rescale)
       r.scale() == s              ->  ("K", width, rescale, a.precision() + k > 38)
                                       (false where s1 == s2)
       s1 == s2                    ->  ("C", width, "one scale")
       otherwise                   ->  ("C", split.form(), split.k(), split.checked()),
                                       split = Cut.ofSplit(t1, t2, r).orElseThrow()

   Each kept pair is compiled as `a + b`, `a - b` and `b - a`, each stored and under `< a`,
   six compiles, under `AUTO_CAP` (363b2). The counts, from the same key in Python
   (`/home/max/varka-ops/m8-row-plans/scratch-decimal-r3/sum_classes.py`, last line `OK sum
   classes: 159 under the default, 43 with allowPrecisionLoss=false`): **159 classes under the
   default** (18 of class U, 11 of class K, 130 of class C: 3 at one scale, 64 `SPLIT`, 31
   `DIGIT`, 32 `GUARDED`) and **43 with `allowPrecisionLoss=false`** (18 U, 25 K); the test
   asserts both counts. Under `allowPrecisionLoss=false` the two classes keyed `("K", _, "PW",
   true)` decline by name, row 360's capped sum over a wide operand rescaled by 19 digits or
   more (61600 ordered type pairs, `VARKA-360.md` section 12, D2; the script counts them): the
   test expects that decline and that reason there, and no other; under the default no class
   has that key. Every other decline fails the test unless its reason contains `chain deeper
   than MAX_CHAIN_DEPTH=`; it prints `sum class sweep: <n> classes, <d> at the cap` once a
   setting. The pull request states the sweep's time.
3. **The census, by rule.** If test 6 records no decline at the cap under the default setting:
   `+`, `-`, `try_add` and `try_subtract` change from `dec^` to `dec` in `census_curated.txt`,
   and their note names the one class declined under `allowPrecisionLoss=false`. If it records
   any: the cells stay `dec^` and the note names the count of classes at the cap. The builder
   does not judge it. Coverage rows `d38s0 + d38s10`, `d38s10 - d38s0`,
   `try_add(d38s0, d38s10)`, each with a `domain` (the kernel can decline a batch). Then the
   regeneration.

**The failure only test 2 catches:** a guard that nulls in place of declining. Plant it once
(build `rescaleGuard`'s `GuardedRange` with `Overflow.NULL`): `10^32 + (-5 * 10^13)` then
answers null under LEGACY where Spark answers a value, and the test must fail.

    build/sbt 'catalyst/testOnly *VarkaDecimalCutSuite *VarkaDecimalPairSuite *VarkaCoverageSuite'
                                                                      -> [success]
    build/sbt 'sql/testOnly *VarkaCoverageDifferentialSuite *VarkaDecimalBaselineQueriesSuite'
                                                                      -> [success]
    python3 dev/varka_census.py --check                               -> exit status 0
    dev/varka_emit.sh "x + y" --columns x:dec38s0,y:dec38s10   -> ends FusedOutput(0)
    build/sbt 'catalyst/testOnly *VarkaDecimalPairSuite'               -> Passed: Total N0 + 4

*[Fix round 3: the totals.]* Five tests added and 363c1's test (5) deleted: `N0 + 4`. `N0` is
58 in the chain's order, so 62 here; the cut suite keeps its total.

**Not here:** a new proof claim (11.4.8), a benchmark section.

#### Part 363d, `[VARKA-363] Score the rounding division against its baseline`

As `VARKA-362.md` part 362c: the two runner files of `VarkaDecimalBaselineBenchmark` after 363b2
and 363c3 merge, predictions 4 to 6 of section 4 scored in section 10, the row marked. The
section `DECIMAL(38, 10) arithmetic chain` stays on the row path: its three cuts sit in one
tree, each alone 36 levels or more by the model, so the tree is past the cap, and the note says
so. *[E1 rework, 28 November 2026.]* Part 363b3 does not count as left when the row is marked:
the note adds "part 363b3, the four-word carry network's proof, is January 2027's, by the
owner's decision E1 of 28 November 2026; not a gate".

### 11.7 Declines, census cells and benchmark sections, by part

| part | decline reasons that change | census | baseline sections that leave the row path |
|---|---|---|---|
| 363a1, 363a2, 363b1 | none | none | none |
| 363a3 | a product or a one-scale sum cut by one digit declines no more | none (`dec^` holds) | `DECIMAL(19, 8) x * y as a value, its scale cut`; `DECIMAL(38, 10) x + y as a value, its scale cut` |
| 363b2 | "capped at 38 with its scale cut, is row 363's" is gone for products; past the cap, the cap's own | `*`, `try_multiply`: `dec` if the products' class sweep declines nothing, else `dec^` | `DECIMAL(38, 10) x * y as a value, its scale cut`, if it is within the cap |
| 363c1 | two new reasons (11.3 decision 5); the other two-scale sums decline no more | `+`, `-`, `try_add`, `try_subtract` stay `dec^` | none (no section reads a sum of two scales alone) |
| 363c2 | "is row 363's digit form" is gone | stay `dec^` | none |
| 363c3 | "is row 363's guarded split form" is gone | `+`, `-`, `try_add`, `try_subtract`: `dec` if the sums' class sweep declines nothing at the cap, else `dec^` | none |

### 11.8 What only a JVM can settle, with the command for the lead

1. The heights on master against 11.3 decision 9, above all the 45650 type pairs at 64:
   part 363b2's class sweep (test 8), or by hand
   `dev/varka_emit.sh "x * y" --columns x:dec38s10,y:dec38s10`.
2. That test 6 of part 363a1 fails without the NULL rule and passes with it.
3. The proofs' times: `dev/varka_prove.sh --solver both sql/varka/proofs/decimal_scale.smt2`
   (two-word rungs now; the four-word ones are 363b3's, January 2027, E1).
4. Whether anything folds `m & 0` away and drops the NULL rule's validity: C2 by
   `dev/varka_emit.sh "<a cut product>" --asm`, and the IR passes `VarkaRangeAnalysis` and
   `Analysis` by part 363a1's test 6 in the kernel (the planner found no folding of `AND` with
   0 in either on master).
5. Every speed: part 363d. The spike gives form M over 128 bits only; a 256-bit dividend has no
   reading before the kernel's own.
6. Whether a compile that stops at the cap reports `chain deeper than MAX_CHAIN_DEPTH=16` word
   for word: part 363a3's test 7.
7. Whether six `ScaleDown` words fit the method byte budget: part 363c2's stop rule,
   `dev/varka_emit.sh "x + y" --columns x:dec38s9,y:dec38s10`.
8. That `VarkaExpressionCompiler.declines(..., new VarkaAdmission(Set.of(), true))` reaches each
   arm's own decline before a root's refusal: the two-lane tests.
9. *[Fix round 3.]* The class sweeps' outcome and time (363b2 test 8; 363c3 test 6, 159
   classes and 43, six compiles each): `build/sbt 'catalyst/testOnly *VarkaDecimalProductSuite'`
   and `build/sbt 'catalyst/testOnly *VarkaDecimalPairSuite'`, the lines `product class sweep:`
   and `sum class sweep:`; and whether `AUTO_CAP`'s pins leave a decline other than the cap's.

### 11.9 Decisions for the owner

The decimal chain's owner decisions are numbered across its four plans: D1 to D3 in
`VARKA-360.md` section 12, D4 in `VARKA-361.md` section 12, D5 to D7 in `VARKA-362.md` 11.9
(D7 covers this row's `varka-363a3-spike`), D8 here. *[Rewritten in the fix round of 28
November 2026.]*

*[E1 rework, 28 November 2026.]* E1, after D8 below, is the owner's decision of the same day on
the proof parts of rows 346, 362 and 363; it carries no D number, being no question of this
chain alone.

- **D8. Two groups of decimal sums at two scales that Spark rounds.** Answered by the owner on
  28 November 2026: build both. A sum of two decimals of different scales whose exact result
  has too many digits is rounded by Spark to fewer decimal places. Part 363c1 builds the common
  case (200720 ordered type pairs). Two groups were left: the rounding removes one digit more
  than the finer operand adds (10912 type pairs), and the coarser operand, scaled up, can pass
  38 digits (22176). (a) Decline both in milestone 8, written to `m10/SCOPE.md`; the census of
  `+`, `-`, `try_add` and `try_subtract` stays "fused in part". (b) Build both: parts 363c2
  and 363c3, about 1000 to 1300 counted lines in all, and the census can move to "fused". The
  owner chose (b); the plan applies it.
- **E1. The proof parts that need a full-strength builder.** *[E1 rework, 28 November 2026.]*
  From 1 December 2026 the project is built by Sonnet-class agents only, for one or two months.
  (a) Move those parts to January 2027, when a full-strength builder is back, and ship the
  parts they prove before them with their differential tests and fuzz coverage; (b) build them
  before 1 December; (c) hold the parts they prove until the proofs land. **Answered on 28
  November 2026: (a).** Here: part 363b3, "January 2027, full strength; not a gate before
  then" (11.6). The same decision moves 362b3 (`VARKA-362.md` 11.9) and claim C of row 346's
  proofs (`VARKA-346.md` section 12, question 5). Every other part of this row is Sonnet-class.

The interval quotients by a decimal stay declined, naming the missing division by a variable,
as 2.5 has it (11.3 decision 5); no row owns that division, and it is not a question.

## 12. What this row leaves rows 351, 364 and 366 of `m9/PLAN.md` (28 November 2026)

- **`ScaleDown(magnitude, k, bound, HALF_UP or DOWN, word)`**, two or four words, null where any
  word is, its constants proven at every rung and `k`, its `HALF_UP` carry network proven at two
  words (363a2) and at four (363b3, January 2027 by the owner's E1; until then the four-word
  network is held by its differential tests). Row 364's rescale down and truncation and row
  351's 128-bit rounding call it; `DOWN`'s carry network is theirs to prove.
- **`negatedWhere` and `rounded`** in `VarkaDecimalCompiler`: the magnitude, the division, the
  sign back. The digit form (11.4.7) is a pattern for a rounding of a sum that one operand
  cannot hold alone; no row of `m9/PLAN.md` needs it today.
- **The guard** `GuardedRange(ovf, 0, 0, mode)` added into both words.
- **Not left:** the coercion cast's expression and its exemption `p_in - k < p_out` (row 364's,
  as 2.5 decided); a tie test for `HALF_EVEN` (row 351's); anything for row 366, which needs
  row 360b's `WideMul` alone.
- **`varka-351c-128bit-rounding`** must be rebuilt on part 363a1: it carries the unfixed
  lowering.
