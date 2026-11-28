# VARKA-360: 128-bit decimal add and subtract, and the widening casts

## 0. What exists now (admission check)

From master at `6e207c226fb` for Spark's sources and the plans, row 347's local branches for the
decimal leaf (unmerged), rows 348 and 349's plans on their unmerged branch `varka-348-349-plan` at
`7b66e5601bd`, and the JDK in `/home/max/proj/openjdk-build/jdk25` (30 October 2026). Spark paths
are under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/` unless named; Varka paths
under `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`. The row's
text is the milestone plan's 2.8 ("360, add and subtract") and waves F3 and G3 of its 4.1.

1. **Spark's result type.** `Add` and `Subtract` over two decimals take scale `S = max(s1, s2)` and
   precision `P = max(p1 - s1, p2 - s2) + S + 1`, through `DecimalType.adjustPrecisionScale` when
   precision loss is allowed and `DecimalType.bounded` when not (`expressions/arithmetic.scala:
   443-451`, `:541-549`), the setting being the node's own `NumericEvalContext` (`:241-244`,
   `expressions/NumericEvalContext.scala:38-50`). The operands are not cast to a common type
   (row 349's plan, its item 0.1). `adjustPrecisionScale` is the identity up to 38 and above it
   gives `(38, max(38 - (P - S), min(S, 6)))` (`DecimalType.scala:175-199`); `bounded` gives
   `(min(P, 38), min(S, 38))` (`:144-146`).
2. **The three modes.** `changePrecisionExact(e1.$plus(e2), p, s)` (or `$minus`) under ANSI and
   TRY, `changePrecisionOrNull` under LEGACY (`expressions/arithmetic.scala:294-310`), TRY's
   `catch` setting null (`:356-371`, `:384-391`); `try_add` and `try_subtract` over numerics are
   `Add` and `Subtract` in `TRY` (`expressions/TryEval.scala:90-94`, `:229-233`). ANSI raises
   `NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION` (`CastUtils.java:107-111`,
   `errors/QueryExecutionErrors.scala:106-120`).
3. **The sum is exact before the check.** `Decimal.+` and `-` add two longs when both values are
   compact and of one scale, and `BigDecimal`s otherwise (`Decimal.scala:506-520`); then
   `changePrecision` sets the scale `HALF_UP` and fails where the precision exceeds `p`
   (`Decimal.scala:458-463`).
4. **So the shapes fall in three classes.** Each operand rescaled to `S` has at most `P - 1`
   digits, so the exact result is below `10^P`:
   - **U, uncapped (`P <= 38`)**: the result fits its type and `10^38 < 2^127`, so no sum, no
     rescaled operand and no difference wraps 128 bits, and no mode's check can fire. One kernel
     serves LEGACY, ANSI and TRY, as Trino's reading found for its own type rule
     (`READING_TRINO_DECIMAL.md` 2 and 11).
   - **K, capped with the scale kept (`P > 38`, result scale `S`)**: under `adjustPrecisionScale`
     exactly where `S <= 6`, under `bounded` always. The result is `(38, S)`, the exact sum can
     reach `2 * 10^38 - 2`, past `2^127`, and the `10^38` check is live in every mode. A rescaled
     operand can pass `2^127` while the exact sum fits: Trino's `17015` vector, a `(38, 0)` plus a
     `(38, 1)`, answers in Spark (`READING_TRINO_DECIMAL.md` 9, item 3).
   - **C, capped with the scale cut (`P > 38`, `S > 6`, precision loss allowed)**: the result is
     the exact sum rounded `HALF_UP` to a smaller scale: 363's. Rescaling the coarser operand
     first takes the exact sum past 129 bits, up to 253, at most such type pairs (found by row
     363's plan, which measures its type pairs), so 363 measures a 256-bit sum against a
     whole-and-fraction split.
   The row's worked case, `DECIMAL(38, 10) + DECIMAL(38, 10)`, is `P = 39`, `S = 10`: class C
   (`(38, 9)`) with precision loss allowed and class K (`(38, 10)`) without.
5. **The wrapped sum fails the check (class K's lemma).** For `|a|, |b| <= 10^38 - 1` the 128-bit
   wrapping sum is `w = a + b - 2^128 * c` with `c` in `{-1, 0, 1}`; where `c != 0`,
   `|w| >= 2^128 - 2 * 10^38 + 2 > 10^38 - 1`, since `2^128 > 3 * 10^38 - 3`, by about
   `4 * 10^37`. So `|w| <= 10^38 - 1` holds exactly where the exact sum fits, and the check needs
   no 129th bit. It needs both operands within `10^38 - 1` after any rescale: the type gives it to
   an operand that is not rescaled, and 2.4's guard restores it for one that is.
6. **The integral operand and its cast.** Type coercion casts an integral operand to
   `DecimalType.forType` - `(3, 0)`, `(5, 0)`, `(10, 0)`, `(20, 0)` - and an integral literal to the
   minimum precision of its value (`analysis/DecimalPrecisionTypeCoercion.scala:166-181`,
   `DecimalType.scala:125-137`). The cast is `Decimal.apply((long) c)` then `changePrecision`
   (`expressions/Cast.scala:1942-1946`, interpreted `:1402-1403`), and null-safe since the target
   is wider (`:534-541`), so it emits no check. The first three are 64-bit decimals; `(20, 0)`
   holds a long in a type of precision 20, so `l + d` is a pair sum of a 64-bit value, and with
   `s > 0` the long is rescaled by `10^s` into 128 bits: `BIGINT + DECIMAL(10, 2)` is
   `(20, 0) + (10, 2) = (23, 2)` and computes `l * 100 + d`.
7. **The decimal-to-decimal widening cast.** `CAST(d AS DECIMAL(p', s'))` is `toPrecision` with
   `HALF_UP` (`expressions/Cast.scala:1400-1401`), emitted without a check where the target has
   more integral digits (`:534-541`). With `s' >= s` and `p + (s' - s) <= p'` it is a rescale up
   that cannot overflow, arrow-rs's infallible rule (`READING_ARROW_RS.md` 1), Trino's
   independently (`READING_TRINO_DECIMAL.md` 7). The baseline's `(18, 4)` to `(38, 10)` is one, and
   its javadoc names this row for it.
8. **The Varka pieces this row composes.** 359 plans the pair as two long words in a compile-time
   `Pair(lo, hi, type)`, a 64-bit decimal widened by `Pair(x, BitOp(SHR, x, 63))`, and the
   unsigned compares `ULT` to `UGE`, naming this row's carry as `ULT(sum.lo, a.lo)`
   (`VARKA-359.md` 2.2), plus the `10^p` check per lane group as `gt128` over a constant pair
   (2.4). `IntArith` in `WRAP` (`VarkaVectorIR.java:672`), `BitOp`'s `AND`, `OR`, `XOR`, `SHR` and
   `USHR` (`:401`, `:953`) and `CondValue`, a condition as 1 or 0 emitted as a broadcast 0 with 1
   added under the mask (`:1224`), exist on the long lane. `GuardedRange` declines a batch
   (`:536`); row 481 gives it a `NULL` mode that clears a live lane's validity (`VARKA-481.md` 3.1),
   the null disposal rows 332, 343 and 349 build on. Row 348's plan rescales a 64-bit value by
   `IntArith(MUL, WRAP, x, slot(10^k))` under the load's `10^p` check (its 2.2), and row 349's
   names the guard-the-inputs form of `CheckOverflow` for this row to reuse (its 2.5).
9. **The JDK.** On AVX2 an unsigned long compare is two sign flips and `vpcmpgtq`, on AVX-512
   `vpcmpuq`; at two long lanes on x86 every compare, mask and blend is the Java fallback
   (`VARKA-359.md` 0, items 9 and 10; `VARKA-376.md` 9). Lanewise `AND`, `OR`, `XOR`, `ADD` and
   the logical shifts carry no mask. `RShiftVL` is a five-instruction emulation on AVX2
   (`x86.ad:6923-6949`), the sign extension's cost; `VARKA-361.md` 0, item 7 has the multiply.
10. **The readings.** Trino's carry is arithmetic, `((a >>> 1) + (b >>> 1) + ((a & b) & 1)) >>> 63`,
    and its borrow `((~a & b) | (~(a ^ b) & (a - b))) >>> 63`, seven plain operations and no mask
    against one compare and a mask (`READING_TRINO_DECIMAL.md` 2); QuestDB's carry is the unsigned
    compare (`READING_QUESTDB.md` 7, item 3); rapids rescales both sides to the finer scale and
    adds in 256 bits (`READING_RAPIDS.md` 1); Comet takes its wide path for an add exactly where
    `max(s) + max(p - s) >= 38`, the uncapped boundary (`READING_ARROW_RS.md` 2).
11. **The baseline.** `VarkaDecimalBaselineBenchmark` has this row's cast, `(18, 4)` to
    `(38, 10)`, at 153.8 ns a row on the row path, and the `(9, 2)` chain `(x + y) * z - x`,
    whose final subtraction `(20, 4) - (9, 2) = (21, 4)` is this row's, at 168.7 ns a row
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`). It has
    no sum or difference alone above 18 and no `BIGINT` column.
12. **359's spike has no loop for this row** (`VARKA-359.md` 2.1): its loops are the comparisons,
    the choice and the three arithmetic loops of 362 and 363, and it has not run.

### What differs from the row text and the milestone plan

- **The uncapped shapes need no check and no lemma** (item 4): the type proves the exact result
  fits, so they are mode-free like row 349's arithmetic. The lemma of 2.8 belongs to class K.
- **The capped shapes are split, departing from the row's text.** The row lands "the capped
  shapes with 363 (the 129th bit feeding its unsigned division, or the whole-and-fraction split)".
  This plan splits them: class K, capped with the scale kept, lands here as 360c after row 481's
  `NULL` mode, with no 129th bit and no division, since a wrapped sum fails the `10^38` check
  (item 5), reusing 359's check and 481's `NULL` mode for the null, with a guard on a rescaled
  operand; class C, capped with the scale cut, stays 363's, the only capped shapes that need the
  129th bit and the division. Decided by the lead on 30 October 2026.
- **The worked test is class K without precision loss and class C with it** (item 4): it lands
  here under `allowPrecisionLoss = false` and with 363 under the default.
- **The rescale-up is a widening multiply** (item 6): `x * 10^k` into 128 bits is the node
  `WideMul` of `VARKA-361.md` 2.2 with a constant multiplier, so every unequal-scale shape and
  every `BIGINT` with a scaled decimal depends on it. This row lands the node with constant
  multipliers (360b) and row 361 adds the column-by-column product; row 104's option C takes the
  same node (section 5).
- **The infallible decimal widening casts** (item 7) are this row's: the F3 wave row's own words
  are "360's uncapped add and widening casts". 364 keeps the casts that round or can fail.
- **The integral casts to `(3, 0)`, `(5, 0)` and `(10, 0)` are row 350's**: they are 64-bit
  values, and this row declines them naming 350 until 350 builds them; only `(20, 0)` is this
  row's (section 7 records the overlap in the owner's text).

## 1. Goal and non-goal

`+`, `-`, `try_add` and `try_subtract` over two decimals with a result above precision 18 and the
scale kept (classes U and K), as the lane pair, with the carry or borrow from an unsigned compare
or its arithmetic equal, the coarser operand rescaled up with a named overflow defence, class K's
`10^38` check nulling or declining by mode; the constant-multiplier `WideMul` the rescale needs;
and the widening casts that feed them: a `BIGINT` to `DECIMAL(20, 0)`, a decimal to a wider one by
sign extension and an infallible rescale up. Not this row: class C (363), a pair times a pair
(362), the column product (361), the integral casts to `(3, 0)`, `(5, 0)` and `(10, 0)` (350),
the casts down or fallible (364, 350), the pair's store and load (359), division (out of the
milestone).

## 2. Design

### 2.1 The forms, and the measurement that picks

- **The carry, two ways.** `COMPARE`: `hi = a.hi + b.hi + CondValue(ULT(s.lo, a.lo))`, 359's node.
  `BITS`: Hacker's Delight 2-13, `c = ((a & b) | ((a | b) & ~s)) >>> 63` on the low words, six
  lane calls and no mask; the borrow `((~a & b) | (~(a ^ b) & d)) >>> 63` with `d = a.lo - b.lo`.
  Option `decimalCarry` (`COMPARE`, `BITS`), `Reason.MACHINE`.
- **The sign extension**, `x >> 63`, takes row 361's `decimalSignWord` (`ASHR`, `NEG_LSHR`,
  `BLEND`): one option for both rows.
- **P against A** follows 359's verdict: an adjacent-lane carry moves up a lane, which 359's spike
  prices for the negation's borrow. **S**, the scalar loop, is the reference arm under 359's
  answered question 3 (a).

**The spike, before any engine code**, as loops added to 359's `VarkaDecimalPairSpikeBenchmark`
beside row 361's, each checked against `BigInteger` and driven to level-4 code, over 359's ladder,
at 512, 256 and 128 bits on the laptop and 256 and 128 on the EPYC 7763:

1. `x + y` and `x - y` over two `DECIMAL(20, 2)` columns, the pair stored (class U);
2. `l + d` with `l` a `BIGINT` and `d` a `DECIMAL(10, 2)`, the rescale by `10^2` (361's one-limb
   `WideMul`) and the sign extension;
3. `x + y` over two `DECIMAL(38, 2)` columns with the `10^38` check folded into a status word
   (class K).

Each in `COMPARE` and `BITS`, the three sign words, and S. The verdict per width and host names
each option's default; the losing arms stay behind their options.

### 2.2 The shapes, as the compiler decides them

From the node's resolved type `(Pr, Sr)` and the operands' `(p_i, s_i)`, with `S = max(s1, s2)`:

| condition | class | here |
|---|---|---|
| `P <= 18` | 64-bit | row 349's |
| `19 <= P <= 38` | U | built, mode-free |
| `P > 38`, `Sr = S` | K | built, the check by mode (2.5) |
| `P > 38`, `Sr < S` | C | declines naming 363 |

The split of the capped shapes into K here and C with 363 departs from the row's text, which
lands every capped shape with 363; section 0's "What differs" states it.

The operands are values of the compiler's decimal kinds: row 348's 64-bit `Scaled`, an integral
under its coercion cast (a 64-bit value whatever its declared precision), or 359's `Pair`. A
64-bit operand of a pair operator is sign-extended at its use, after its rescale where the rescale
stays within 64 bits (`p + k <= 18`, 348's `rescale`).

### 2.3 The lowering

    s.lo = a.lo + b.lo
    s.hi = a.hi + b.hi + carry(a.lo, b.lo, s.lo)          COMPARE: 4 calls; BITS: 8
    d.lo = a.lo - b.lo
    d.hi = a.hi - b.hi - borrow(a.lo, b.lo, d.lo)         COMPARE: 4 calls; BITS: 9

in `WRAP` throughout, every word an existing long-lane node. Two 64-bit operands meeting in a
class U type, `DECIMAL(18, 2) + DECIMAL(18, 2)`, take the same lowering over their sign extensions:
no special form, since `(19, 2)` and above is one pair kernel.

### 2.4 The rescale up, and its overflow defence

The milestone plan's rule (row 360's text, 28 October): each rescale-up site names its defence,
and no check reads a value that may have wrapped.

- **Class U: guard-the-inputs, the guard being the operand's own bound.** The operand is within
  `10^p - 1` by 347's or 359's load check, or by its producer's proof, and `p + k <= P - 1 <= 37`,
  so `|x * 10^k| <= 10^37 - 1`: the multiply cannot wrap. A 64-bit operand with `p + k <= 18`
  stays a 64-bit `Scaled`; with `p + k >= 19` it is `WideMul(x, 10^k)` to a pair, one limb for
  `k <= 9`, two up to `k = 18`, and above that the constant's second word's low product added
  into the high word. A pair operand has `p >= 19`, so `k <= 18` and the constant is one
  non-negative word: `x * c` modulo `2^128`, exact by the bound, is `WideMul(x.lo, c)` with the
  low word read unsigned (one masked add on the high word) plus the low word of `x.hi * c`, an
  `IntArith(MUL, WRAP)`, in the high word.
  The test at the first value the guard rejects is the load's `10^p` slot under a rescale.
- **Class K: guard-the-inputs by a batch guard.** The rescaled operand gets
  `GuardedRange(x, -(10^(38 - k) - 1), 10^(38 - k) - 1)` in `FAIL` in every mode: a decline, never
  a null, since the exact sum can still fit (Trino's `17015` vector, a test that must decline and
  then answer through the row engine). After it `|x * 10^k| <= 10^38 - 1`, which item 0.5 needs.
  On a pair the range is two `gt128` compares as 2.5 builds them, disposed `FAIL`. For a 64-bit
  operand with `k <= 19` every long passes and no guard is emitted. The tests sit at `10^(38 - k)
  - 1` and `10^(38 - k)`.
- **The planted fault** for row 504: the multiply-then-check order, the rescale first and the
  guard on its wrapped result, Trino's `shortToShortCast` order, under a test flag; refuted by the
  proof of 3 item 5 and failed by the differential.

### 2.5 Class K's check, nulling through row 481's mode

The check is 359's: `ovf = gt128(w, C) | gt128(-C, w)` with `C = 10^38 - 1` as a constant pair,
eleven lane calls. Its disposal is row 481's, not a new one:

    g   = GuardedRange(CondValue(ovf), 0, 0, mode)      mode NULL for LEGACY and TRY, FAIL for ANSI
    lo' = IntArith(ADD, WRAP, w.lo, g)
    hi' = IntArith(ADD, WRAP, w.hi, g)

`g` is 0 on every lane it leaves valid, so the words are `w`'s, and its nulling reaches both words
through the null-intolerant adds, which keeps 359's rule that a pair's two words share one validity
word. About six lane calls ride on the check. ANSI declines the batch and the row engine raises;
LEGACY and TRY share a kernel. Row 349's `CheckOverflow` above 18, if its question 1 builds the
internals, is the same pair: its guard-the-inputs form over a pair, then this rescale.

### 2.6 The widening casts

- **An integral to its coercion type**: `(20, 0)` is the long as a 64-bit value of a
  precision-20 type, a pair only at a pair operator's use, with no check (item 0.6). `(3, 0)`,
  `(5, 0)` and `(10, 0)` are row 350's 64-bit casts: an operator here that meets one declines
  naming 350 until 350 lands, then consumes 350's value as a 64-bit operand.
- **A decimal to a wider one**, `s' >= s` and `p + (s' - s) <= p'`, with `p' >= 19`: the rescale of
  2.4's class U form, then the pair; with `s' = s` the sign extension alone. A fallible or
  narrowing cast declines naming 364 (above 18) or 350.
- **As an output**, a pair whose high word is a sign extension is 359's two-root output with
  `hi = SHR(lo, 63)`, the same store as 157's `WidenDecimal` (`VARKA-157.md` 2.1).
- **Under a multiply** the cast is not widened: row 361's product reads the 64-bit value
  (`READING_DECIMAL_ENGINES.md` 2, item 5).

### 2.7 At 128 bits, under row 503's rule

At two long lanes on x86 `COMPARE` and every check are the Java fallback, `BITS` and the sign words
`ASHR` and `NEG_LSHR` are not. So a class U projection with `BITS` is mask-free **[I]**, and the
spike's 128-bit column says whether it beats the row path there; a condition root declines by row
503's rule; class K carries masks either way and is measured as such, declining with its measured
reason if it loses (`m10/SCOPE.md` item 101, as `VARKA-359.md` 2.6).

### 2.8 Compiler, reference, cost, warm-up

`VarkaDecimalCompiler`'s `Add` and `Subtract` arms (row 349's) gain classes U and K ahead of their
decline naming 360; the cast arm gains 2.6. The reference evaluator needs nothing beyond 359's
unsigned compares and 481's `NULL` mode. The budget and the cost model need no new node; the
warm-up's long block gains the carry forms, held by `VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The edges**, against `BigDecimal`, at 2, 4 and 8 long lanes, lengths 1, 3, 37 and 64, both
   bodies: class U at `(19, 2)`, `(20, 2)`, `(30, 10)` and `(38, 0)`: a low word with its sign bit
   set under a high word of 0 and of -1, low words summing to exactly `2^64` and to `2^64 - 1`, a
   borrow from `lo = 0`, `+-(10^(P - 1) - 1)` in every sign pair; `BIGINT + DECIMAL(10, 2)` at
   `Long.MIN_VALUE` and `Long.MAX_VALUE`; every null pattern.
2. **Class K**: `DECIMAL(38, 2) + DECIMAL(38, 2)` and `-` at `+-(10^38 - 1)` (null under LEGACY and
   TRY, a decline under ANSI), rapids' 38 nines plus 1 in both signs (`READING_RAPIDS.md` 3, item
   1), `DECIMAL(38, 10) + DECIMAL(38, 10)` at `10^38 - 1` under `allowPrecisionLoss = false`, the
   rescale guard at its first rejected value and Trino's two vectors (`READING_TRINO_DECIMAL.md`
   10, item 3); each sum's valid lanes beside a nulled one. Under the default setting the worked
   case declines naming 363, a test, and its answer test lands with 363.
3. **The type rule against Spark's own**: `resultDecimalType` of `Add` and `Subtract` over every
   `(p1, s1, p2, s2)` with `p <= 38`, `0 <= s <= p`, under both settings, holds the compiler's class
   to 2.2's table; a sample of each class is compiled and run. A pure function, under a second.
4. **One kernel per mode where it should be**: `VarkaEmittedBytesSuite` holds one shape hash for a
   class U row under LEGACY, ANSI and TRY, and for class K one for LEGACY and TRY and another for
   ANSI.
5. **The SMT proofs**, in 359's `sql/varka/proofs/decimal128.smt2`, a section of their own, over
   integers in 2.8's two layers at full width, all linear: the carry and the borrow, as `ite` over
   the words read modulo `2^64`, give `s = a + b` and `d = a - b` modulo `2^128` (the ring layer),
   and `COMPARE` and `BITS` give the same bit for every pair of words (the carry layer, the bitwise
   form stated over the words' top bits as `ite`); for each `P` from 19 to 38 operands within
   `10^(P - 1) - 1` give an unwrapped result within `10^P - 1`, and one digit wider has a witness;
   item 0.5's lemma; the sign extension `v = lo + 2^64 * ite(lo >= 2^63, -1, 0)`; the rescale's
   bound per `k`, and the class K guard exactly `|x * 10^k| <= 10^38 - 1`, with Trino's wrap order
   a `sat` witness. The file's times stated before and after.
6. **Named tests over spark-rapids' overflow vectors** (`READING_RAPIDS.md` 3.1, `READING_TRACE.md`
   2 item 4): the scale 10 add that overflows for both signs among them, each in the class its type
   gives.
7. **Coverage rows**, a "DECIMAL wide add" family: `d20 + d20b`, `d38 - d38b` at one scale,
   `d20 + d20s4`, `l + d9`, `l + d18s4`, `d18 + d18b` (two 64-bit operands into `(19, s)`),
   `try_add(d20, d20b)`, `d38s2 + d38s2b` (class K), `CAST(d18s4 AS DECIMAL(38, 10))`, as filters
   now and projections after 359b's store, each naming its verification for row 490's check.
8. **The differential, the matrix, the fuzzers** (the IR fuzzer's decimal sequence gains the two
   carry forms), and the generated files in `ADDING_A_TYPE.md`'s order, no existing shape's bytes
   moving.
9. **`WideMul` with constant multipliers (360b)**: `VARKA-361.md` 3's product table and random
   `BigInteger` cross-check restricted to a constant operand, `10^k` for `k = 0..18` and a limb of
   exactly `0xFFFFFFFF`, over operands of both signs, and the constant-limb section of its SMT
   proofs. Its planted carry fault (`VARKA-361.md` 3.1) runs under the test flag with the
   one-negative case included: a negative operand times a positive constant is one negative
   operand, which `results/VARKA-361-planted-carry.txt` finds the planted order also gets wrong
   for column products. The powers of ten have a small high limb and may never reach the wrapped
   middle column, so the planted test drives the node at the IR level with a constant whose two
   limbs are both large, `Long.MAX_VALUE`, against negative operands. Rows 361 and 362's text
   names "two negative operands" only; that text is the owner's and is flagged here, not edited.

## 4. The baseline, and predictions

**The baseline first, on the unchanged code, in one pull request with row 361's**:
`VarkaDecimalBaselineBenchmark` gains `DECIMAL(20, 2)`, `DECIMAL(20, 4)` and `DECIMAL(38, 2)`
columns beside 361's `BIGINT`, and the sections `x20 + y20`, `x20 - y20s4`, `l + x9`, `l + x18`
and `x38s2 + y38s2` as projections and `x20 + y20 < z` as a filter, on the EPYC 7763 at 256 and
128 bits.

Predictions, registered before the spike and the build:

1. **`COMPARE` against `BITS`** at 512 and 256 bits: `COMPARE` ahead by up to 1.3 times on the
   laptop and within the runners' spread on the EPYC 7763; at 128 bits on x86 `BITS` ahead by 5 to
   20 times, `COMPARE` being the allocating fallback.
2. **S against P** for class U: P ahead by 2 to 4 times at 512 and 256 bits in L2; S ahead at 128
   bits on x86 unless `BITS` closes it.
3. **The build against the baseline** at 256 bits: the sums and differences 10 to 40 times faster
   than the row path; the cast 5 to 20 times; class K 5 to 20 times, its check and guard the
   difference.
4. **At 128 bits on x86** a class U projection with `BITS` beats the row path by 3 to 10 times.
5. **No existing shape's bytes move**; the `option_arms` digests move with `decimalCarry`.

## 5. Order, files and size

1. **The baseline addition** (4), with 361's.
2. **The spike loops** (2.1), with 359's spike and 361's loops; the verdict in section 10.
3. **360a**: class U at one scale, the `(20, 0)` cast and the scale-keeping widening casts,
   `decimalCarry`, the proofs of the carry, the borrow, the bounds and the sign extension, tests 1,
   3 and 4, the filter coverage rows. Needs 359a.
4. **360b, `WideMul` with constant multipliers**: the node (`VARKA-361.md` 2.2 and 2.3) with one
   and two constant limbs, its reference arm, grammar and parse arm, `decimalSignWord`, the
   constant-limb proofs and tests 9, then the rescale-up shapes of class U and the infallible
   rescaling casts. Row 361 adds the column-by-column product to the same node, and row 104's
   option C takes the same node for its checked `BIGINT` multiply, so there is one helper, as
   `VARKA-104.md` 3.3 asks.
5. **360c, after row 481's `NULL` mode**: class K, its guard, 359's `10^38` check and 481's `NULL`
   mode for the null, test 2.
6. **360d, after 359b's store**: the projections, the measurement against the baseline at both
   widths and 512 bits on the laptop, the predictions scored.

| file | what |
|---|---|
| `VarkaDecimalCompiler.java` (rows 348 and 349's) | the arms of 2.2 to 2.6 |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalCarry` |
| `varka/VarkaVectorIR.java`, `varka/VarkaVectorWalk.java`, `varka/Slots.java` | `WideMul` (360b) |
| `sql/varka/proofs/decimal128.smt2`, `VarkaProofFiles.java` | 3 item 5 |
| tests | edges, class K table, type sweep, one-kernel test, coverage rows, fuzz forms |
| benchmark | the baseline sections, the spike loops, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `census.tsv` |

**Size: medium as the row says for 360a, large with 360b and 360c**, in five pull requests after
the baseline: the spike loops a day with 361's, 360a about three days, 360b one or two once
`WideMul` exists, 360c two, 360d one. Test time: the type sweep is about two million pure function
calls, under a second; the edges are of the slot table's size; the proof section is priced from
its spiked form at VARKA-245's observed ratio in the calibration table, under ten seconds per
solver. It rests on 347, 348 and 349, 359 (the pair, the compares, the check, the proof file and
the store), 481 for 360c and 503 for the 128-bit rule.

## 6. What it unlocks in the census

None alone. Under the milestone plan's 2.8 and 359's answered question 2, `+`, `-`, `try_add` and
`try_subtract` count only when they fuse at every precision pair to 38, which needs class C, 363's.
The 22 comparison and choice pairs of `VARKA-359.md` 6 that wait on "360 and 363" get their rescale
up from 360b and their rescale down from 363, and count with 363. The ten casts count with 364.
The count moves by 0.

## 7. Out of this row, and declines that stay

Out: class C (363); the casts down, fallible or to integral types (364, 350); a product (361, 362);
`%`, `mod` and `pmod` (row 349's plan names their homes); the pair's load and store (359).

Declines that stay, each naming its row: class C (363), a fallible or narrowing decimal cast (364,
350), an integral cast to `(3, 0)`, `(5, 0)` or `(10, 0)` until 350 builds it, a decimal in an
int-driven kernel, a negative scale, and at two long lanes on x86 a condition root (503) and any
projection the measurement shows losing there.

**Flagged, not resolved.** Rows 350 and 360 both claim the integral casts to `(3, 0)`, `(5, 0)`
and `(10, 0)` in the owner's text; the lead gave them to 350 (30 October 2026) and this plan
declines them naming 350, but the two rows' text is the owner's and is left as it is.

**A possible follow-up to row 481, not built here.** Class K's disposal is composed from 481's
`GuardedRange` (2.5), about six lane calls on the eleven of the check. A `GuardedRange` constructor
taking a condition would save the `CondValue` and the guard's two compares. It would be row 481's,
owned and made there, and only if 360c's measurement shows the six calls matter.

## 8. Open points for the lead

All answered by the lead on 30 October 2026, and folded in above: `WideMul` lands here with
constant multipliers and row 361 adds the column product (section 5); class K lands here after
row 481 (0, "What differs"); the infallible widening casts are this row's by the F3 wave row's
words; the integral casts to `(3, 0)`, `(5, 0)` and `(10, 0)` are 350's (section 7); the disposal
is composed from 481's node, its condition variant a possible 481 follow-up (section 7); and the
options' defaults come from the runner's verdict on the spike.

## 9. Done when

Row 360's text holds, with the corrections above: the spike's verdict is committed per width before
the build; classes U and K fuse in the three modes, class U with one kernel and class K's `10^38`
check nulling through row 481's mode and declining under ANSI, and class C declines naming 363;
the rescale-up sites name their defences with tests at the first rejected value, and the planted
multiply-then-check order is refuted and failed; `DECIMAL(38, 10) + DECIMAL(38, 10)` at
`10^38 - 1` is a test in the class each setting gives; the `(20, 0)` cast and the infallible
decimal widening casts fuse, so a `BIGINT` with a decimal fuses, and the casts to `(3, 0)`,
`(5, 0)` and `(10, 0)` decline naming 350; `WideMul` with constant multipliers lands with its
planted-carry test over a negative operand; the carry, the borrow, the sign extension, the bounds
and item 0.5's lemma proven by SMT over integers at full width under both solvers; rapids'
overflow vectors named tests; the measurement against the baseline committed at both widths with
its predictions scored; and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)

### 10.1 Step 1, the baseline's readings (27 November 2026)

Step 1's twelve sections merged with row 361's in #1460, and #1475 committed their first runner
results. The class's two runner files,
`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and
`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`, were replaced on 27
November 2026 from `benchmark.yml` runs 46057445085 (256 bits, no `MaxVectorSize`) and 46057448792
(`-XX:MaxVectorSize=16`) of vecbricks/varka. Both ran at master `e103a4c548a`, dispatched with
`pr=1793`, so they measured `refs/pull/1793/head`, `6d1f14ae33a`, each on an AMD EPYC 7763 by its
provenance and by the job's probe. Best times in ms, Spark's row path / varka on, without nulls and
then at one row in ten null:

| section | 256 bits | 128 bits |
|---|---|---|
| `x20 + y20` as a value | 1036 / 1032, 935 / 921 | 1069 / 1057, 940 / 952 |
| `x20 - y20s4` as a value | 1088 / 1099, 962 / 964 | 1127 / 1119, 998 / 979 |
| `x38s2 + y38s2` as a value | 1021 / 1026, 914 / 911 | 1064 / 1042, 955 / 952 |
| `l + x9` as a value | 344 / 328, 297 / 308 | 347 / 322, 287 / 286 |
| `l + x18` as a value | 347 / 360, 308 / 317 | 346 / 333, 298 / 303 |
| `x20 + y20 < z20` as a filter | 1464 / 1457, 1245 / 1271 | 1492 / 1526, 1277 / 1274 |

No lowering of this row is built, so both arms run the row path and tie: 0.96x to 1.05x by best
time at 256 bits and 0.98x to 1.08x at 128 bits. None of section 4's predictions is scored, since
each is about the spike or the build. The baseline is in place before the kernel; everything of the
build is left.

### 10.2 360a, part 1: class U under a comparison (10 November 2026)

*Built on 10.1's pick, the `COMPARE` carry, in a pull request of its own stacked on the
spike's, which merges first. Written before row 359's store merged: 10.3 corrects what that
changed. The `BIGINT` cast built with it is cut into the next pull request, and its record is
10.5's.* Built, in `VarkaDecimalCompiler.pair`:

1. **The sum and the difference** (`widePair`, 2.2, 2.3): `+`, `-`, `try_add` and `try_subtract`
   typed past 18 digits whose exact type is within 38: `lo = a.lo +- b.lo`, `hi = a.hi +- b.hi +-
   c`, wrapping `IntArith`s, `c` the carry `CondValue(ULT(lo, a.lo))` or the borrow
   `CondValue(ULT(a.lo, b.lo))`, over any `Pair` operand at the result's scale, or one of a
   smaller scale whose rescale stays within a long, a folded constant or a 64-bit value times
   `10^k`, sign-extended (`atPairScale`). One kernel in every mode (0 item 4); Spark's `Decimal.+`
   and `-` are exact `BigDecimal` arithmetic here, with no 34-digit rounding.
2. **Departure:** the 64-bit and constant operands of a smaller scale, rescaled within a long, are
   part of 360b's "rescale-up shapes of class U" (section 5), pulled here by the review; the
   `WideMul` rescales stay 360b's.
3. **Declines**: a capped sum names rows 360 and 363; a rescale past a long names this row's
   `WideMul` (360b); a wide sum as a value waits for 359b's store.
   A negation, a choice or a lowering cast over an uncapped sum names only the wide form's rows,
   and `VarkaDecimalArithmeticSuite`'s pinned reasons and type sweep follow.

**Proofs.** `decimal128.smt2`'s sum layer: the words' sum and difference, carry and borrow as
unsigned compares over Java longs, equal to the values' modulo `2^128`; per `P` from 19 to 38,
operands within `10^(P - 1) - 1` give words exact within `10^P - 1`, and one digit wider the
words pass it (wrap, at 38). 155 checks, from 73; Z3 2.02 s and cvc5 1.32 s on the busy laptop.

**Tests.** `VarkaDecimalPairSuite` gains four: the word edges and null patterns at 2, 4 and 8
lanes through nine forms over six type pairs from `(18, 2) + (18, 2)` to `(37, 2) + (37, 2)`,
held to Spark under ANSI and LEGACY and through the `try_` forms; one kernel in the three modes;
the capped and rescaled declines, the worked `DECIMAL(38, 10)` sum among them; and the rescaled
64-bit and literal operands. The carry compare with its operands swapped fails the first, at
`1 + -1`. The coverage family "DECIMAL wide add" has four filter rows, proven by
`decimal128.smt2`. The baseline's `x20 + y20 < z20` is `WHOLE` (`NONE` under the
two-lane refusal). Its new rows moved `VarkaCoverageCompositionFuzzSuite`'s draw onto
`extract(SECOND FROM t)`, whose warm-up wrote a decimal output's sixteen-byte slots into eight
bytes a row; master's VARKA-157 (#1500) sizes it by the slot, so the branch's own fix was dropped
on the rebase of 20 November 2026.

**The review** (`/code-review high`, eight findings), fixed: a null test through any non-decimal
cast, now none; reasons over an uncapped
sum still naming row 360; the rescalable operands, which declined; three stale javadocs; the wider
witness, which read the integers, not the words; two copies of the sign extension. Trade-offs left:
two 64-bit operands into `DECIMAL(19, s)` could be one long add, six lane calls fewer (360d's
measurement). A
second review, after the merge of #1448 (whose harness holds `+` and `-` only as values, still
declined, so no drift): the reasons over a sum of two scales and over a wide sum as a value now name
row 359's store; the check does not ask whether the operands compile, so `-(x20 + c9 * e9)` names
359 and not 361. A third, after the rebase of 20 November 2026 (six nits): fixed, the census notes
of `+`, `-`, `try_add` and `try_subtract`, item 2's record and 10.1's two-lane claim; kept, the
reason above, the carry fixed as `COMPARE` until the runner reads `BITS`, and the baseline's
committed runner files, which time the `x20 + y20 < z20` filter on the row path until its owed
dispatch.

**Not in this part**, owed: 360b, `WideMul` and the rescaled shapes (`x20 - y20s4`, `l + x9`,
`l + x18`); 360c, class K after row 481 (`x38s2 + y38s2`); 360d, the projections after 359b's
store and the measurement; the rescaling casts; the `BITS` arm, should the runner ask; and the IR
fuzzer's carry forms.

### 10.3 360a on master: the sum is an output too (27 November 2026)

*10.2 brought onto master at `e103a4c548a` as its own pull request. Two merges since 10.2 was
written change what it says.*

**Row 359's store (#1710) makes the sum an output.** `VarkaDecimalCompiler.output` stores every
form `pair` builds under an `InterleaveDecimal` root, so a sum or difference `widePair` builds
is a projection's output as soon as this part
lands. This corrects 10.2 in three places: item 3's "a wide sum as a value waits for 359b's
store" (it fuses); the tests' "four filter rows" (a second family, "DECIMAL wide add values",
holds four output rows); and "Not in this part: 360d, the projections after 359b's store" (the
projections are here, and 360d keeps only the measurement). The reason a consumer of one long
gets for such a sum, a cast of it to an integral type for one, no longer names the store: it says
the sum is two words.

**What the output does, by test.** It fuses, and answers as Spark's own expression does on every
row tried. `VarkaDecimalPairSuite` gains four tests over the suite's word edges, each type's
bounds among them, and every null pattern of two operands, in the masked and the null-free body,
at 1, 2, 4 and 8 long lanes:

1. `storedSumsAndDifferences`: `a + b`, `a - b`, `b - a`, `try_add`, `try_subtract`, the sum
   under a negation, `abs`, `IF`, `coalesce` and `greatest`, and a sum of a sum, over the six type
   pairs of 10.2's comparison test, `(18, 2) + (18, 2)` to `(37, 2) + (37, 2)`, under ANSI and
   under LEGACY.
2. `storedSumsOverRescaledOperands`: a 64-bit operand and a literal rescaled within a long.
3. `storedSumsWithoutTheBitmapPass`: with `validityByBitmap` off the store reads both words'
   validity, so the carry and a sign extension are shown null exactly where the low word is.
4. `cappedAndRescaledSumsDeclineAsOutputs`: the capped sum, the sum of two scales and the
   rescale past a long decline as outputs with the reasons they give under a comparison.

No row holds an overflow: the exact type of every sum built here is within 38 digits, so Spark's
check cannot fire, and neither LEGACY's null nor ANSI's error has an input. The sums that can
overflow are the capped ones, which decline (test 4) and stay 360c's and row 363's. With the
carry's compare swapped, tests 1 to 3 fail. At two long lanes on x86 the output declines by its
root, as every pair's store does (`VarkaKernelPlan.TWO_LANE_DECIMAL_PAIR_OUTPUT_REASON`,
`VARKA-359.md`'s section on the store): `results/VARKA-612-refused.txt` lists the four new
value rows.

**The proof file on master.** `decimal128.smt2` holds 158 checks, the sum layer's 82 among
them (10.2's 155 counted three fewer of master's), and every one holds under Z3 and under cvc5.

**Also moved.** The baseline's `x20 + y20` as a value is `WHOLE` beside the filter, both `NONE`
where the session refuses a 64-bit condition; its four other sums stay `NONE`. The committed
runner files time both sections on the row path until 360d's dispatch.
`VarkaDecimalConformanceSuite` no longer lists `+`, `-`, `try_add` and `try_subtract` as
declining in part at its `DECIMAL(30, 4)`: they fuse as pair stores, which that harness does not
read, and cancel as unverified beside 359's forms. `VarkaDecimalOutputSuite`'s wide decline test
holds `DECIMAL(18, 2) + DECIMAL(18, 2)` to the pair's store and keeps a decline over a sum that
rescales past a long.

**Owed after this part**, in place of 10.2's list: 360b, `WideMul` and the rescaled shapes
(`x20 - y20s4`, `l + x9`, `l + x18`) with the rescaling casts; 360c, class K (`x38s2 + y38s2`);
360d, the measurement of the filter and of the projection against the baseline on the runner;
the `BITS` arm, should the runner ask; the IR fuzzer's carry forms; and the `BIGINT` cast, the
next pull request (10.5).

### 10.4 360a's review round (27 November 2026)

*A `/code-review high` of 10.3's head, which read the code and ran nothing: no wrong answer in the
lowering, six findings. Each one that changes behaviour was reproduced by a failing test before
its fix. The branch now holds the spike's (10.1) by a merge and is stacked on its pull request.*

1. **A constant of negative scale fused in a wide sum.** `atPairScale` folded a constant operand
   at the result's scale without the guard `atScale` has, so under
   `spark.sql.legacy.allowNegativeScaleOfDecimal` `DECIMAL(20, 2) + DECIMAL(3, -2)` fused as a
   pair where the same sum within 18 digits declines. `atPairScale` now declines any operand of
   negative scale first, with the reason every other site gives (the milestone plan's 2.8).
   `VarkaDecimalPairSuite.negativeScaleOperandsDecline` holds a constant, a column and a wide
   column, under a comparison, a null test and as an output; `VarkaDecimalValueSuite` holds the
   wide sum beside the narrow one.
2. The `BIGINT` cast's, recorded with it (10.5).
3. **One sum, two reasons.** Under a negation, a cast, a choice or a consumer of one long,
   `sumReason` still named rows 359 and 360 for a sum over an operand rescaled past a long; bare,
   `atPairScale` named the constant `WideMul`. Both give the second reason now
   (`rescaledPastLong`), for two 64-bit operands too, which read "a decimal sum of 21 digits is
   row 360's". `rescaledSumsDeclineWithOneReason` holds seven forms to one text.
4. **Citations of what was not on the branch.** 10.2's "10.1's pick", row 360 and `carry`'s
   javadoc cited this file's first outcome section and the spike's results file, which only the
   spike's branch held; with the merge they exist here. Row 360 and 10.2 name the section, which
   names the file: a passage that cites a results file has its section numbers read as quotes of
   it by `dev/varka_quote_check.py`.
5. **Three texts said a wide sum declines**: `nullTests`' name and comment, the conformance
   fixture's javadoc and the compiler's class javadoc now say a sum capped at 38 digits, or
   rescaled past a long, declines.
6. The `BIGINT` cast's, recorded with it (10.5).

No emitted byte, coverage row, width-audit entry or proof moved: every fix is a decline or a text.

### 10.5 The `BIGINT` cast: cut into its own pull request (28 November 2026)

*By the lead's decision, to keep each pull request under the cap of 1000 counted lines: with the
cast this part counted 1045 against the spike's branch.* 10.2 to 10.4 were written with the cast
built beside the sum. This pull request now holds the sum and the difference only; the cast, its
tests, its coverage rows, its probe form and the text about it in 10.2 to 10.4 follow in the next
one, stacked on it. No line of the sum changed in the cut. Until then a `BIGINT` cast into a
decimal wider than 18 digits declines as on master, "a decimal wider than 18 digits is row
359's", and so does a sum over it at one scale
(`VarkaDecimalPairSuite.sumsOverBigintOperandsDecline`).

**The cast's pull request** (28 November 2026), stacked on the sum's. It puts back what the cut
took out, as the reviewed head had it, so the tree is that head's but for this file, row 360 and
the test of the decline above, which goes: the forms it held fuse, and
`storedSumsOverRescaledAndBigintOperands` holds them to Spark. Its record, from 10.2 to 10.4:

1. **The cast** (2.6, `bigintPair`): `CAST(l AS DECIMAL(p, 0))`, `p >= 19`, is the long and its
   sign extension, under a comparison, as a wide sum's operand and, through row 359's store, as a
   projection's output. Every long has at most 19 digits, so the cast keeps the value in every
   mode and one kernel serves the three.
2. **Its null test declines**, with a reason of its own, until the cast has an edge table. Row
   350's casts (#1700) meet it in `valid()`, where master's rule is kept: a cast between decimals
   with no guard is valid where its operand is, and any other cast's null test is left to the row
   engine. `unguardedIntoDecimal` answers false for a source that is no decimal, so the `BIGINT`
   arm sits between the two. No test holds that reason's text: owed with the edge table.
3. **At a nonzero scale it declines** naming this row's constant `WideMul` (360b). Into fewer
   than 19 integral digits the cast can also overflow, so the reason names row 350's guard as
   well there (`DECIMAL(20, 5)`) and the `WideMul` alone elsewhere (`DECIMAL(38, 5)`,
   `bigintRescaleNamesItsRows`); `casts_suites.tsv` records it in the three modes. This was
   finding 6 of the review round.
4. **The cast probe holds the form** (finding 2 of the review round: the cast was listed as fusing
   only in context, and it fuses bare). `VarkaCastMatrixSuite` gains a third decimal form,
   `DECIMAL(20, 0)`, and the pair is not among the census's `CONTEXT_CASTS`. In the three cast
   matrices of `docs/sql-varka.md`, `bigint` to `dec` reads "1 of 3" in LEGACY, ANSI and TRY,
   where it read "declined". The third form also moves the two cells whose count is of decimal
   source forms, `dec` to `bool` and `dec` to `bigint`, from "1 of 2" to "1 of 3" in each mode:
   `DECIMAL(20, 0)` declines there as `DECIMAL(30, 4)` does. `casts_fused.tsv` gains 48 lines,
   one fused. One credit follows that is true of the pair and not of the entry: the census counts
   a cast alias by its type pair, so the `decimal` alias, which casts to `DECIMAL(10, 0)`, now
   lists `bigint` as fused in part, and the `bigint` row and the conversion row of the support
   tables each move one entry to "in part".
5. **Tests.** `VarkaDecimalPairSuite.storedSumsOverRescaledAndBigintOperands` stores the cast
   alone, a sum and a difference over it and a difference of two casts, on the suite's edges and
   null patterns at 1, 2, 4 and 8 long lanes, under ANSI and LEGACY, each slot held to Spark's
   own expression. Each coverage family of the wide sum gains its fifth row, over two `BIGINT`
   columns at `DECIMAL(21, 0)`, which the differential suite runs against Spark;
   `VarkaTwoLaneConditionSuite` holds a stored sum of two `BIGINT`s, which reads no decimal slot,
   to the two-lane refusal by its root, and `results/VARKA-612-refused.txt` lists the output row.
   That output row is the first pair's store to reach `VarkaCoverageCompositionFuzzSuite`, whose
   reference evaluator has no one long for two words: the fuzzer leaves out a row whose compiled
   root is the pair's store and counts it in its accounting (1 row); the filter row stays in.
6. **Left.** The `BIGINT` pair has no edge table of its own: its longs are those a
   `DECIMAL(18, 0)` column holds, and the differential's draws. One two-lane case, read from
   `VarkaKernelPlan.admit` and not built against: a comparison of pairs built from `BIGINT` casts
   alone, projected as a `BOOLEAN`, reads no decimal slot, so the refusal of a boolean output
   over a decimal column (#1478) does not see it and it is admitted as a `BIGINT` comparison is;
   row 597, which owned the decline in the readers, moved to milestone 10 (`m10/SCOPE.md` item
   140).

## 11. What is left, cut into parts a Sonnet-class agent can build (28 November 2026)

*New section, written on 28 November 2026 by a planner that built nothing. Sections 0 to 10 above
are unchanged, word for word; 10.0 and 10.1 are the text of branch `varka-360a-spike` and 10.2 to
10.5 the text of branch `varka-360a-bigint-cast`, as they will read once the three pull requests of
360a merge. This section replaces section 5's items 4 to 6 (360b, 360c, 360d), the option list of
2.1 and the two-lane rule of 2.7, and says so where it does. Sections 12 to 14 are new too. Code
facts were read on master `6dafb8bd2f6` (worktree `/home/max/proj/varka-m9-read-w`) and on the
branches named below, in `/home/max/proj/vecbricks-varka`. No JVM was started; 11.9 lists what only
a JVM settles.*

Paths: `CODEGEN` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`,
`VARKA` is `CODEGEN/varka`, `TJ` is
`sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka` and `TS` the
same path under `src/test/scala`. `DC` is `CODEGEN/VarkaDecimalCompiler.java`.

### 11.1 The state on 28 November 2026

| piece | where | state |
|---|---|---|
| the baseline's sections and runner results | master (#1460, #1475, #1797) | merged; 10.0 |
| the spike's loops 8 to 10 and the laptop reading | `varka-360a-spike`, pull request #1799 | open; merges first; 10.1 |
| class U at one scale, filter and output | `varka-360a-sums` | built and reviewed, 918 counted lines against the spike; 10.2 to 10.4 |
| the `BIGINT` cast to scale 0 | `varka-360a-bigint-cast` | built, 214 counted lines against the sums; 10.5 |
| `WordConst`, `WideMul`, `VarkaDecimalLowering` | not on master | `grep -c 'WideMul\|WordConst' VARKA/VarkaVectorIR.java` prints 0 |
| the rescales, class K, the measurement | not on master | everything of 11.5 |

The premises of this table, each a command run on 28 November 2026:

    git -C /home/max/proj/vecbricks-varka branch --list 'varka-360a-*'
        varka-360a-bigint-cast, varka-360a-r2, varka-360a-spike, varka-360a-sums
    git -C /home/max/proj/varka-m9-read-w grep -c 'WideMul\|WordConst' -- sql/catalyst/src/main
        (prints nothing: no file of the main tree holds either name)
    git -C /home/max/proj/vecbricks-varka show varka-360a-bigint-cast:$DC | grep -c 'bigintPair('
        2

**The three waiting branches are not master.** `varka-360a-spike` (pull request #1799),
`varka-360a-sums` and `varka-360a-bigint-cast` are stacked in that order: `-sums` holds the spike
by a merge, `-bigint-cast` holds `-sums`. On 28 November 2026
`git -C /home/max/proj/vecbricks-varka merge-base 6dafb8bd2f6 <branch>` prints `215f1bdfbb9` for
the spike, 3 first-parent commits behind master, and `1924c7a5afb` for the other two, 11 behind
(`git rev-list --count --first-parent <base>..6dafb8bd2f6`); the commit `f0bc0601300` they were
first cut from is 26 behind. What a builder must know: the lead merges the three, in that order,
each after a merge of master into it; no builder rebases one, merges one or starts a part from
one. Every part of 11.5 starts from master after the merges its B0 lines name, and reads a
waiting branch only with `git show <branch>:<path>`. Where the names live today: `bigintPair` is
on `varka-360a-bigint-cast` only; `uncappedSum`, `atPairScale`, `reachesScale`,
`rescaledPastLong`, `signExtended`, `carry`, `borrow` and `widePair` are on `varka-360a-sums` and
after it; none is on master. 11.7 gives each one's signature.

**The older branches.** None is a pull request. "Behind" counts first-parent commits of master
`6dafb8bd2f6` past the branch's merge base.

| branch | holds | against master | use |
|---|---|---|---|
| `varka-360a-r2` | 360a's sums and the cast in one, rebased on 23 November | 6 ahead, 95 behind | dead: `varka-360a-sums` and `varka-360a-bigint-cast` are its content on a newer master, reviewed again |
| `varka-360-decimal-add` | the first 360a (five commits) and a merge of master | 6 ahead, 158 behind | dead, superseded twice |
| `varka-360b-widemul` | `WordConst`, `WideMul`, `VarkaDecimalLowering`, `VarkaWideMulSuite`, the fuzz draw, the product proofs; 999 counted lines | 10 ahead (5 its own), 164 behind | **360b1 and 360b1p start from its files**, re-ported: it sits under row 489's node descriptor |
| `varka-360b2-rescale` | `wideRescale`, `pairRescale`, the rescaling casts, their tests and proof layer; 508 counted lines | 20 ahead (10 its own), 164 behind | **360b2 starts from its hunks**, applied by hand to the newer compiler |
| `varka-360b-full` | 360b and the rescales in one commit, before the split | 13 ahead, 225 behind | dead: the two branches above are its split, reviewed |
| `varka-360-decimal-add-pre-rebase-1120`, `varka-360b-widemul-pre-rebase-1120`, `varka-360b2-rescale-pre-rebase-1120` | backups taken before the rebase of 20 November | 11, 15 and 21 ahead, 225 behind | dead: backups of branches that are themselves superseded |
| `varka-361-widening-multiply`, `varka-361a-column-product`, `varka-361a2-mul-low-option` | row 361's, on top of row 362's old stack | 25, 27 and 30 ahead, 164 behind | `VARKA-361.md` 11 says what each gives |

### 11.2 The parts

Each part is one pull request under 1000 counted lines (`python3 dev/varka_msg_check.py size`).
Estimates are the old branch's counted lines adjusted for what 11.5 adds or drops.

| part | what | counted lines | builder | starts from | waits on |
|---|---|---|---|---|---|
| 360s | the spike's EPYC 7763 reading committed, the carry rule applied | 60 to 110, and results files | Sonnet-class, after the lead downloads two artifacts | master | #1799 merged |
| 360b1 | `WordConst`, `WideMul`, `VarkaDecimalLowering` and its hook in the emitter; no SQL shape | 850 to 950 (fix round 3: review 2's count of the old diff, one test moved to 360b1p) | **full strength, before 1 December** | master, files of `varka-360b-widemul` | nothing |
| 360b1p | the product's proof layer, the planted middle column and 360b1's moved test | 300 to 400 | Sonnet-class | master | 360b1, and the three waiting branches merged (fix round 3) |
| 360b2 | the rescales up of class U and the infallible rescaling casts | 650 to 800 | Sonnet-class | master, hunks of `varka-360b2-rescale` | 360b1, and the three waiting branches merged |
| 360c1 | class K at one scale: the `10^38` check, its null or decline, and the chain's overflow helpers | 500 to 650 | **full strength, before 1 December** | master | 360b2 |
| 360c2 | class K over a rescaled operand: the guard, the planted order, Trino's vectors | 400 to 550 | Sonnet-class | master | 360c1, row 697 |
| 360d | the measurement against the baseline, the predictions scored, the row closed | 60 to 120, and results files | Sonnet-class, after the lead's dispatch | master | 360c2 |
| 360e | `decimalCarry`, only if 360s's rule says `BITS` | 500 to 700 | the lead; not a builder's part (11.5) | master | 360s, 360b1 |

**The order and why.** 360b1 first: it is the largest risk, it touches no file the 360a pull
requests touch, and rows 361, 362 and 363 stand on its node. 360b2 before 360c1: 360b2 adds
`TWO_LANE_WIDE_REASON`, which 360c1 reads, and the totals of `VarkaDecimalPairSuite` in both
sheets count on that order. The lead, who builds 360c1, can write it while 360b2 is in review,
on a branch cut from 360b2's; its pull request opens after 360b2 merges. 360b2 and 360c1 before
360c2, which reuses the rescale helpers and the check. 360d last, once every section of the
baseline that this row turns into a kernel has turned.

#### The whole decimal chain, in order (rows 360 to 363 and row 697)

This list is the one statement of the order for `VARKA-360.md`, `VARKA-361.md`, `VARKA-362.md`
and `VARKA-363.md`; the other three plans point here. A builder takes the first step whose
"waits on" is all merged; a step that still waits is passed over, and the steps behind it that
do not wait on it go ahead. Nothing is built against an unmerged branch.

0. **The lead's merges.** #1799 (`varka-360a-spike`), then the pull requests of
   `varka-360a-sums` and `varka-360a-bigint-cast`, in that order. And row 697's #1798 (the
   long-lane chain cap, branch `varka-long-lane-chain-cap`, built, not merged), any time before
   step 8.
1. **360b1** (the lead). Waits on nothing; it can start before step 0 ends.
2. **360b1p**. Waits on 360b1 and on the three merges of step 0: both edit
   `VarkaProofFiles.java` and regenerate `decimal128.smt2` (`git diff --stat
   6dafb8bd2f6...varka-360a-bigint-cast` lists both). *[Fix round 3.]*
3. **360b2**. Waits on 360b1 and on the three merges of step 0.
4. **360c1** (the lead). Waits on 360b2.
5. **361a**. Waits on 360b2, 360b1p (`VarkaWideMulSuite`'s totals count on it: 5 after
   360b1p, 7 after 361a) and, in the queue of `VarkaDecimalCompiler.java`, on 360c1. *[Fix
   round 3.]*
6. **361p**. Waits on 361a and 360b1p.
7. **361b**. Waits on 361p (the totals of `VarkaWideMulSuite` count on it) and 361a.
8. **360c2**. Waits on 360c1 and row 697.
9. **362a1**. Waits on 360b1 and, in the queue of `VarkaDecimalLowering.java`, on 361b.
10. **362a2**. Waits on 362a1, 361a and 360b2.
11. **362a3**. Waits on 362a2.
12. **362b1**. Waits on 362a1.
13. **362b2**. Waits on 362b1, 362a2, 360c1 (the overflow helpers) and row 697.
14. **363a1**. Waits on 360b1 and, in the queue of `VarkaDecimalLowering.java`, on 362b1.
15. **363a2**. Waits on 363a1.
16. **363a3**. Waits on 363a1, 362a2 and row 697 (its checks are dead: it calls no helper
    of 360c1, `VARKA-363.md` 11.3 decision 7).
17. **363b1**. Waits on 362b1 and, in the same file queue, on 363a1.
18. **363b2**. Waits on 363b1, 363a3, 362b2, 360c1 (`guardedPair`; fix round 3) and row 697.
19. **363c1**. Waits on 363a3, 360b2 (`pairTimesWord`), 360c1 and row 697.
20. **363c2**. Waits on 363c1 and row 697.
21. **363c3**. Waits on 363c2 (the queue of `VarkaDecimalCompiler.java`), 360c2 (`rescaleGuard`),
    363b2 (the sweep's options `AUTO_CAP`, fix round 3) and row 697.

Beside the queue, since they touch no file a code part of the list touches:

- the proof parts **362b3** (after 362b1) and **363b3** (after 363a2): *[E1 rework, 28 November
  2026]* January 2027, full strength; not a gate before then, by the owner's decision E1 (a) of
  28 November 2026 (`VARKA-362.md` and `VARKA-363.md` 11.9). No step of the list above waits
  on either, and neither is built before January;
- the measurements: **360s** (after #1799 and the lead's download), **361s** (after #1799),
  **361c** (after 361b, 361s and the lead's dispatches), **360d** (after 360c2), **362c**
  (after 362b2), **363d** (after 363b2 and 363c3);
- **360e**, the lead's, only if 360s prints `VERDICT: P BITS`.

**The parts over 16 levels**, which wait on row 697 because the long lane's cap is 16 on master
and their shapes are taller: 360c2 (19 to 27 by the model), 362b2 (29 and 30), 363a3 (36 to
40), 363b2 (44 to 65), 363c1 (40 to 50), 363c2 (48 to 54) and 363c3 (43 to 52). Each of their
B0 lines greps `LONG_LANE_MAX_CHAIN_DEPTH` in `VarkaEmitBudget.java` and stops at 0.

**The file queues.** `sql/varka/AGENTS.md` ("Name the files you will edit") allows one open pull
request for a file at a time. `VarkaDecimalCompiler.java`: 360b2, 360c1, 361a, 360c2, 362a2,
362b2, 363a3, 363b2, 363c1, 363c2, 363c3. `VarkaDecimalLowering.java` and `VarkaVectorIR.java`:
360b1, 361b, 362a1, 362b1, 363a1, 363b1. The list above is in an order that respects both.

### 11.3 Decisions taken here, each with its evidence

1. **The carry stays `COMPARE` unless the runner separates the forms; the rule is numbers.**
   "The carry" is how the high word of a 128-bit sum learns that the low words' sum wrapped;
   11.4 gives its two forms, `COMPARE` (360a's) and `BITS`. The laptop does not separate them:
   all 18 cells at 512 and 256 bits are under 1.3x (10.1). Two runner readings ended in success
   on 28 November 2026, `benchmark.yml` runs 46084701494 and 46084708544 of vecbricks/varka;
   their files are on no branch yet. Part 360s commits them and applies the rule.
   **The rule reads one file and no other: the 256-bit EPYC 7763 file**, the results file whose
   second line is `decimal lane pair hand loops at 256 bits, forms [...]` and whose CPU line is
   `AMD EPYC 7763 64-Core Processor`. The script 360s commits refuses every other file with a
   `REFUSED:` line and exit status 2, so the 128-bit file and the laptop's file of three blocks
   cannot give a verdict. A cell is `Rate(M/s)` of `P BITS` over `Rate(M/s)` of `P COMPARE`
   (Spark's harness computes the rate from the best time) for one of the loops 8, 9 and 10 at
   one of the three rungs: nine cells, compared unrounded, so a cell printed as 1.3000 is read
   from the ratio and not from its print. **`BITS` is the verdict if at least one cell is 1.3 or
   more and no cell is 1/1.3 or less; in every other case the verdict is `COMPARE`.** On
   `COMPARE` nothing is built: 360a's `carry` and `borrow` stay, and no `decimalCarry` option
   exists, since an option with one arm is no option. On `BITS` part 360e is the lead's. The
   128-bit reading decides nothing: both vector forms lose to the scalar loop there by 66 times
   or more (10.1), and a pair shape does not run at two lanes (decision 4). The laptop's file,
   read with the script's `--record` flag, has no cell at 1.3 in its 512-bit and 256-bit blocks
   and one in its 128-bit block; `--record` prints no `VERDICT` line.
2. **`WideMul` is a composite node lowered to primitive nodes before the analysis**, with its
   masks and shift counts `WordConst` leaves, as row 360's text in `PLAN.md` asks ("written as its
   lowering function in `VarkaDecimalLowering`, primitive nodes over `WordConst` masks and counts,
   not as emitter code"). The shape key, the compiler, the reference evaluator and the cost model
   see the composite; the emitter's analysis, grouping and walk see the lowered graph.
3. **One helper computes a word of a 64 by 64 product, for the whole chain:**
   `VarkaDecimalLowering.productWord(options, x, y, word, rightOneLimb, xSigned, ySigned,
   faults)`. Each operand is read signed or unsigned by a flag. `lower(WideMul)` calls it with
   both flags true. Row 362's partial products call it with other flags. It is "the
   partial-product helper" of `VARKA-361.md` and `VARKA-362.md` 2.2, and it lands here, in
   360b1, since the constant rescale is its first user. **`options` (a `VarkaEmitOptions`) is
   the first argument of `lowerAll`, of every `lower` overload and of `productWord` from 360b1
   on**, though no lowering reads an option before row 361's part 361b. The other choice, adding
   the argument in 361b, would change three signatures and every call in the suites of four
   parts already merged; with the argument there from the start, 361b adds two reads and no
   later part of rows 362 and 363 has a second spelling to guess. All four plans write the
   calls this way.
4. **At two long lanes on x86 every new arm declines in the compiler, by a named reason, until
   row 698 of `m9/PLAN.md` shows a two-lane pair load that wins.** `DeclineSink` already carries
   the session's setting: `sink.declinesTwoLaneChecks()` (`CODEGEN/DeclineSink.java`), which
   `VarkaDecimalCastCompiler` reads for row 350's casts. Parts 360b2, 360c1 and 360c2 test it
   first in each new arm and decline with `VarkaDecimalCompiler.TWO_LANE_WIDE_REASON` (text in
   11.5, 360b2). Why not rely on the roots alone: `VarkaKernelPlan.hostRefusal` refuses a filter's
   condition, a `BOOLEAN` output over a decimal column and every pair store, but not a value
   root over a decimal condition, `CAST(x20 - y20s4 < z20 AS INT)` for one, which row 597's second
   half owns. Declining in the arm closes that hole for the new shapes without waiting for 597.
   Class U at one scale (360a) keeps what it does today, refused by its root only; row 597
   decides for it. This replaces 2.7, whose "`BITS` projection is mask-free" hope the spike
   refuted: the load and the store fall back whatever the carry.
5. **The chain depth: every class U shape and class K at one scale fit the cap of 16; class K
   over a rescaled operand does not, and waits for row 697.** Heights by the analysis's rule (a
   leaf is 0, a node one more than its deepest operand), computed by `verify_360.py` over the
   networks of 11.4:

| shape | height |
|---|---|
| `WideMul`, one limb, either word | 6 |
| `WideMul`, two limbs, either word | 8 |
| `x20 + y20` stored | 5 |
| `x20 + y20 < z20` | 7 |
| `CAST(x18 AS DECIMAL(38, 10))` stored | 7 |
| `l + x9` stored, `l + x18` stored | 11 |
| `x20 - y20s4` stored | 11 |
| `x20 - y20s4 < z` | 13 |
| a pair times `10^12`, then a sum, stored | 13 |
| `x38s2 + y38s2` stored (class K, one scale) | 12 |
| class K at one scale under a comparison | 14 |
| class K, a guarded pair times `10^k`, one limb, stored | 19 |
| class K, a guarded pair times `10^k`, two limbs, stored | 21 |

   The negations, `abs` and the comparisons the tests of 11.5 run, by the same model
   (`/home/max/varka-ops/m8-row-plans/scratch-decimal-r2/heights_r2.py`, whose last line is
   `OK heights r2: 47 forms, 12 past a cap of 16`). `Analysis.analyzeRoot` declines a root whose
   height is greater than the cap, so 16 fits and 17 does not:

| shape | height | at a cap of 16 |
|---|---|---|
| class U, a pair or a long by one limb (`x20 + y20s4`): `a + b` stored | 11 | fits |
| the same: `a + b < a`; `-(a - b)` stored | 13 | fits |
| the same: `abs(a - b)` stored | 14 | fits |
| the same: `-(a - b) < a` | 15 | fits |
| class U, a rescale by two limbs or by two words (`x20 + y19s12`, `x18 + y20s12`, `x5 + y30s25`, `x1 + y37s36`): `a + b` stored | 13 | fits |
| the same: `a + b < a`; `-(a - b)` stored | 15 | fits |
| the same: `abs(a - b)` stored | 16 | fits, with nothing to spare |
| the same: `-(a - b) < a` | 17 | **declines** |
| class K at one scale: `-(a + b)` stored | 15 | fits |
| class K at one scale: `abs(a - b)` stored | 16 | fits, with nothing to spare |
| class K at one scale: `-(a + b) < a`; `abs(a - b) < a` | 17; 18 | **declines** |
| class K, a guarded pair: `-(a + b)` stored; `abs(a - b) < a` | 22 to 24; 25 to 27 | **declines** |

   The model can be one level off the emitter (11.9 item 6). *[Fix round 3, 28 November 2026:
   the rule of 14 and 15 levels is replaced; the tables stay, since they say which shapes
   decline where the cap is 16.]* The height is not the only size bound a wide kernel meets, so
   the rule for every test of 11.5 is one constant, not a height and not a judgement:

   - **Every kernel test of 360b2, 360c1, 360c2 and of row 361's 361a whose shape holds a
     `WideMul` or a class K sum** (a rescale of a pair, of a long or of a `BIGINT` into a
     pair; a capped sum; a product), and every test that asserts where such a shape fuses,
     **is compiled and run under the suite's constant `DEEP`, whatever its height.** An options set a test builds for another reason starts from `DEEP`
     in place of `VarkaMatrixRuns.BASE`: the bitmap tests' options are
     `DEEP.toBuilder().validityByBitmap(false).denseValidityOnce(false).validityOrFirst(first)
     .build()`.
   - **A test whose assertion is a compiler decline** (the suite's `decline` and
     `outputDecline`, a two-lane admission's decline, a decline by a named reason) keeps
     `VarkaMatrixRuns.BASE`, or the options it names (`maxChainDepth(16)` for the cap's
     decline): the compiler declines before any size bound is read. The existing tests of
     360a's class U at one scale are not touched.

   `DEEP` is declared once, by 360b2, in `TJ/VarkaDecimalPairSuite.java`, package-private so that
   `VarkaTwoLaneConditionSuite` and 361a's `VarkaWideningProductSuite` read it as
   `VarkaDecimalPairSuite.DEEP`:

        /** The options of a kernel test of a wide decimal shape. A tree deeper than the long
         *  lane's cap of 16 (VARKA-360.md 11.3 decision 5); more than MAX_FUSED_NODES = 64
         *  distinct ops, the emitter's cap on a kernel where the byte budget is off (the matrix
         *  configuration methodByteBudget=0); and a body past the method budget where sharing
         *  is off (cse=false). #1798's suites pin the last two for the same reasons. */
        static final VarkaEmitOptions DEEP = VarkaMatrixRuns.BASE.withMaxChainDepth(64)
            .withMethodByteBudget(VarkaEmitOptions.DEFAULTS.methodByteBudget())
            .withCse(VarkaEmitOptions.DEFAULTS.cse());

   Rows 362 and 363 pin the same three for every kernel test of their shapes, whatever the
   height (`VARKA-362.md` 11.6, `VARKA-363.md` 11.6), so the four plans hold one rule. The
   evidence, each read on master `6dafb8bd2f6` and on branch `varka-long-lane-chain-cap`:

        VARKA/VarkaLoopEmitter.java:1906   boolean capOps = options.methodByteBudget() == 0;
        VARKA/VarkaLoopEmitter.java:1916   || (capOps && opNodes[0] > MAX_FUSED_NODES)) {
        VARKA/VarkaEmitBudget.java:328     public static final int MAX_FUSED_NODES = 64;
        sql/varka/matrix/seconds.tsv:7     cse=false
        sql/varka/matrix/seconds.tsv:32    methodByteBudget=0
        #1798, VarkaExpressionCompilerSuite.scala: ".withMethodByteBudget(VarkaEmitOptions
          .DEFAULTS.methodByteBudget())" beside "a 64-level chain leaves its neighbour no op",
          and ".withCse(true)" beside "with sharing off the 64-level chain's dense loop is
          about 10.5 KB, over the 8000 HotSpot compiles, so it declines on the method budget"

   `VarkaEmitOptions.DEFAULTS.cse()` is `true` (the record's third component, `true` in
   `DEFAULTS`), so `DEEP`'s sharing is #1798's. The pin removes no other configuration of the
   matrix from these tests: `DEEP` is the matrix's `BASE` with three fields set. Whether a given
   shape passes 64 ops is not known without a JVM (11.9); the pin makes the answer not matter.

   `VarkaEmitBudget.MAX_CHAIN_DEPTH` is 16 on master for a long-lane tree. Row 697 (branch
   `varka-long-lane-chain-cap`, pull request #1798, built, not merged) adds
   `VarkaEmitBudget.LONG_LANE_MAX_CHAIN_DEPTH = 64`, the auto cap of a long-lane tree at 256
   bits or wider, and the option `chainCapLongBits`. So 360c2 waits on row 697. In a session
   without it, or at 128 bits on aarch64, a shape past 16 declines with the emitter's own
   reason, "chain deeper than MAX_CHAIN_DEPTH=16 (the maxChainDepth emit option)"
   (`Analysis.analyzeRoot`); that is the existing rule and no part changes it. A shape built on
   a deeper operand, a sum of sums of rescales, declines the same way.
6. **The rescale's three forms cover every class U pair**, by the type rule (`verify_360.py`
   item 6, over every `(p1, s1, p2, s2)`): a pair operand is rescaled by at most 18 digits, a
   64-bit operand by at most 36. So: a 64-bit value times `10^k`, `k <= 18`, is one `WideMul`
   (`wideRescale`); a 64-bit value times `10^k`, `19 <= k <= 37`, is `WideMul` by the constant's
   low word and one long multiply by its high word (`longTimesTwoWords`); a pair times `10^k`,
   `k <= 18`, is `WideMul` of the low word and one long multiply of the high word
   (`pairTimesWord`). The old branch declined `k > 18`; this plan builds it, since `+` and `-`
   count in the census only when every pair to 38 digits fuses (section 6).
7. **Class K's disposal is section 2.5's**, unchanged: the check as a value under row 481's
   `GuardedRange`, in the mode `VarkaDecimalCompiler.decimalOverflow` gives (11.4, "The
   overflow mode of the whole chain"): `NULL` for LEGACY and TRY, `FAIL` for ANSI. One thing is
   new: **a class K sum is the first decimal value whose null is not its operands' null**, so
   `VarkaDecimalCompiler.valid` must decline it (11.4, "The null test"). A pair operand rescaled
   by more than 18 digits in class K declines; it is reachable only with
   `spark.sql.decimalOperations.allowPrecisionLoss` off (decision D2 of section 12).
8. **`decimalSignWord` and `decimalWideMulLow` are row 361's**, built in its part 361b over
   `VarkaDecimalLowering`; this row builds the lowering with the `ASHR` sign word and the limbs'
   low word, the forms the old branch proved. The compiler's own sign extension
   (`signExtended`, `x >> 63` by a slot) is not under the option: the compiler reads no emit
   option, and a sign extension is one lane call.
9. **The census moves by 0 pairs in this row**, as section 6 says; 11.6 lists the cells that do
   move ("in part" marks and cast cells) and the baseline sections each part turns.

### 11.4 The algorithms, each checked on exact integers

All of it is checked by
`/home/max/varka-ops/m8-row-plans/scratch-360/verify_360.py` (Python's `int` as the reference,
the words modelled as Java longs that wrap). Its last line on 28 November 2026:
`OK: 5231755 checks, 0 failures`. A copy of the script goes into the repository with 360b1p, as
`sql/varka/plans/m8/results/VARKA-360-verify.py`, so that later parts can rerun it.

**Notation.** A pair is `v = hi * 2^64 + lo`, `hi` read signed and `lo` unsigned. Every
operation below is a wrapping long operation. `M = 0xFFFFFFFF`. `x >> 63` is the arithmetic shift,
`x >>> 32` the logical one.

**The carry and the borrow** (360a, on its branches; restated because 360e may replace them):

    lo = a.lo + b.lo;  hi = a.hi + b.hi + carry      COMPARE: carry = (lo <u a.lo) ? 1 : 0
                                                      BITS: ((a.lo & b.lo) | ((a.lo | b.lo) & ~lo)) >>> 63
    lo = a.lo - b.lo;  hi = a.hi - b.hi - borrow     COMPARE: borrow = (a.lo <u b.lo) ? 1 : 0
                                                      BITS: ((~a.lo & b.lo) | (~(a.lo ^ b.lo) & lo)) >>> 63

Checked: both forms equal the 65th bit of the exact sum, and the exact borrow, on 100 edge pairs
and 200000 random pairs of words; and for each `P` from 19 to 38 the pair sum and difference of
operands within `10^(P - 1) - 1` are exact and within `10^P - 1`.

**A word of a 64 by 64 product** (`productWord`, 360b1):

    xl = x & M;  xh = x >>> 32;  yl = y & M;  yh = y >>> 32
    p0 = xl * yl;  p1 = xh * yl;  p2 = xl * yh;  p3 = xh * yh
    t  = p1 + (p0 >>> 32)
    u  = p2 + (t & M)
    lo = (u << 32) | (p0 & M)
    hi = p3 + (t >>> 32) + (u >>> 32)                  the unsigned product's high word
    hi = hi - (y & (x >> 63))                          only where x is read signed
    hi = hi - (x & (y >> 63))                          only where y is read signed

    with rightOneLimb (0 <= y < 2^32; yh, p2, p3 and y's correction are zero):
    t  = p1 + (p0 >>> 32);  lo = (t << 32) | (p0 & M);  hi = (t >>> 32) - (y & (x >> 63))

No limb product or sum passes `2^64`: the script asserts `p0` to `p3`, `t`, `u` and `hi` before
the corrections fit a word on every input. Checked against `x * y` for `x` over 23 edge longs and
3000 random longs and `y` each `10^k`, `k` from 0 to 18, in both forms; and for every pair of the
edge longs. The built node order of the high word, which keeps its height at 8, is
`((p3 + (t >>> 32)) - corrections) + (u >>> 32)` with `corrections` the sum of the two.

**The three rescales up** (360b2). `c = 10^k`:

    wideRescale(x, k), x a long, 1 <= k <= 18:
        lo = WideMul(x, c).LOW;  hi = WideMul(x, c).HIGH          one limb where c < 2^32 (k <= 9)

    pairTimesWord(x, c), x a pair, 1 <= k <= 18, modulo 2^128:
        lo = WideMul(x.lo, c).LOW
        hi = WideMul(x.lo, c).HIGH + (c & (x.lo >> 63)) + x.hi * c

    longTimesTwoWords(x, k), x a long, 19 <= k <= 37, 10^k = ch * 2^64 + cl, modulo 2^128:
        lo = WideMul(x, cl).LOW
        hi = WideMul(x, cl).HIGH + (x where cl >= 2^63, else 0) + x * ch

Why the added terms: `WideMul` reads both operands signed. In `pairTimesWord` the low word is an
unsigned word, so where its top bit is set its value is `2^64` more than the long, and the
product is `c * 2^64` more: add `c` to the high word. In `longTimesTwoWords` the same holds for
the constant's low word `cl`, known at compile time: add `x` where `cl` has its top bit set.
Checked: every 64-bit operand of `p` digits by every `k` with `p + k <= 37`; a `BIGINT` by `k`
from 1 to 19; every pair of `p` digits by every `k` with `p + k <= 37`; each exact.

**The rescale's defence, by class** (the milestone plan's rule, section 2.4). Class U: the
operand's own bound, `p + k <= P - 1 <= 37`, so no rescale can wrap and no guard is emitted.
Class K: a batch guard on the operand before the multiply,
`|x| <= 10^(38 - k) - 1`, in `FAIL` in every mode. The script checks that within the guard
`|x * 10^k| <= 10^38 - 1`, that the first value past the guard leaves that bound (the guard is
exact), and that for a 64-bit operand the guard is needed exactly where `p + k > 38` (a `BIGINT`
counts as 19 digits) and then its bound fits a long. The planted order, the multiply first and
the check on the wrapped product, passes a value the guard rejects: the script finds a witness at
`k = 1`. Trino's two vectors (`READING_TRINO_DECIMAL.md` 9 item 3) are `17014 * 10^33` and
`17015 * 10^33` as a `DECIMAL(38, 0)` plus a `DECIMAL(38, 1)`: both are past the guard
`10^37 - 1`, so both decline, and Spark's exact sum of the second with
`-(7015 * 10^34 + 1)` is 38 nines.

**Class K's check and its disposal** (360c1). With `w` the wrapped pair sum or difference and
`C = 10^38 - 1` as two long slots (`DecimalWords.bound(38)`), and `-C` as two more:

    ovf = Or(DecimalWords.compare(GT, w.lo, w.hi, C.lo, C.hi),
             DecimalWords.compare(LT, w.lo, w.hi, negC.lo, negC.hi))
    g   = GuardedRange(CondValue(ovf, LONG), 0, 0, mode)
    lo' = IntArith(ADD, WRAP, w.lo, g);  hi' = IntArith(ADD, WRAP, w.hi, g)

`mode` is `decimalOverflow(evalModeOf(node))`, below. `g` is 0 on every lane it leaves valid,
so the words are `w`'s; under `NULL` it nulls an overflowing lane and the two adds carry the
null to both words; under `FAIL` it condemns the batch, and the row engine raises Spark's
`NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION`.

**The overflow mode of the whole chain** (360c1 adds it; rows 362 and 363 call it). What Spark
does when a decimal `Add`, `Subtract` or `Multiply` gives a value past its result type, read in
`sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/arithmetic.scala`:
`checkDecimalOverflow` is `value.toPrecision(precision, scale, ROUND_HALF_UP, !failOnError,
context)`, and `Decimal.toPrecision` returns null where its `nullOnOverflow` argument is true
and raises where it is false. `failOnError` is false under LEGACY and true under ANSI and TRY;
a TRY node catches what it raises and answers null (`nullSafeCodeGen`'s try block and its
interpreted twin). `try_add`, `try_subtract` and `try_multiply` over numerics are the same
nodes in TRY mode (`TryEval.scala`). `CheckOverflow` takes the same flag as a field.
`DecimalAddNoOverflowCheck` checks nothing and is an aggregate buffer's; no part compiles it.

| eval mode | reached by | Spark, on a result past the type | `VarkaNodeCompiler.overflowOf` | `decimalOverflow` | the kernel |
|---|---|---|---|---|---|
| LEGACY | `spark.sql.ansi.enabled` false | NULL | `WRAP` | `NULL` | the lane is null |
| ANSI | `spark.sql.ansi.enabled` true | raises `NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION` | `FAIL` | `FAIL` | the batch declines; the row engine raises on the row Spark names |
| TRY | `try_add`, `try_subtract`, `try_multiply`, either setting | NULL | `NULL` | `NULL` | the lane is null |

`overflowOf` is the integral rule, under which LEGACY wraps; a decimal never wraps, and
`GuardedRange`'s constructor throws on `WRAP` (`VarkaVectorIR.java`, "a range guard fails or
nulls"). So no part of the chain passes `overflowOf`'s value to a guard. The helpers, in
`VarkaDecimalCompiler`, written out:

    /**
     * What a decimal result past its type's precision gives: NULL under LEGACY and TRY, a
     * failed batch under ANSI, on which the row engine raises. Not overflowOf alone: that is
     * the integral rule, whose LEGACY wraps, and a GuardedRange refuses WRAP.
     */
    static Overflow decimalOverflow(Object evalMode) {
      return VarkaNodeCompiler.overflowOf(evalMode) == Overflow.FAIL
          ? Overflow.FAIL : Overflow.NULL;
    }

    /** The eval mode of a decimal Add, Subtract or Multiply, a TRY form already unwrapped. */
    static Object evalModeOf(Expression e) {
      return switch (e) {
        case Add a -> a.evalMode();
        case Subtract s -> s.evalMode();
        case Multiply m -> m.evalMode();
        default -> throw VarkaErrors.illegalArgument(
            "no eval mode on " + e.getClass().getSimpleName());
      };
    }

    /** Whether the pair (lo, hi) is outside [-(10^38 - 1), 10^38 - 1], read signed. */
    static Cond outside38(VarkaVectorIR lo, VarkaVectorIR hi, DeclineSink sink) {
      DecimalWords top = DecimalWords.bound(DecimalType.MAX_PRECISION());
      DecimalWords bottom = top.negate();
      return new Or(
          DecimalWords.compare(CompareOp.GT, lo, hi, sink.longSlot(top.lo()),
              sink.longSlot(top.hi())),
          DecimalWords.compare(CompareOp.LT, lo, hi, sink.longSlot(bottom.lo()),
              sink.longSlot(bottom.hi())));
    }

    /**
     * The pair (lo, hi) behind a guard on over, a long that is 0 exactly where the value fits:
     * under NULL a lane where over is not 0 is null in both words, under FAIL the batch
     * declines. The guard is 0 on every lane it leaves, so the adds change no word.
     */
    static Pair guardedPair(VarkaVectorIR over, VarkaVectorIR lo, VarkaVectorIR hi,
        Overflow mode, DecimalType type) {
      var g = new GuardedRange(over, 0, 0, mode);
      return new Pair(new IntArith(IntOp.ADD, Overflow.WRAP, lo, g),
          new IntArith(IntOp.ADD, Overflow.WRAP, hi, g), type);
    }

`decimalOverflow` takes an `Object` as `overflowOf` does (the eval mode is a type nested in a
Scala object, which the Java sources do not name). `VarkaDecimalCastCompiler.intoDecimal` has
the same two-line idiom inline on master; 360c1 replaces it by a call of `decimalOverflow`, so
the rule is written once.

| setting | the exact result fits 38 digits | it does not |
|---|---|---|
| `spark.sql.ansi.enabled` false (LEGACY) | the value | NULL |
| `spark.sql.ansi.enabled` true (ANSI) | the value | the batch declines; Spark's row path raises |
| `try_add`, `try_subtract` (TRY), either setting | the value | NULL |

The lemma that lets a 128-bit check stand for a 129-bit sum (section 0 item 5): for
`|a|, |b| <= 10^38 - 1` the wrapped `w` has `|w| <= 10^38 - 1` exactly where the exact result
has. Checked in the three modes against Spark's rule (`changePrecision` at 38 digits) over 20
edge values, the wrap's own edges among them (`2^127 - (10^38 - 1)` and its neighbours), and
1500 random values, sums and differences: the script saw an overflow that wraps 128 bits, one
that does not, and fitting results, and every answer agreed.

**The null test** (360c1). `VarkaDecimalCompiler.valid` derives a value's validity from its
leaves, and its `Add` and `Subtract` arms answer "valid where both operands are". That is false
for class K under LEGACY and TRY, and under ANSI the null test would not run the guard that
raises. So `valid` declines a class K sum, as it declines a guarded cast, and every consumer that
calls `valid` (the null tests, `coalesce`, the picks, a choice's branches) declines with it.

### 11.5 The build sheets

Rules for every sheet. Run every command from the worktree's root with
`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` and `SPARK_LOCAL_IP=127.0.0.1` exported. "Passes" for
an sbt test run means its last `[info] Passed: Total N, Failed 0, Errors 0, Passed N` line shows no
failure and no error; where the sheet gives `N`, it must match. A sheet's B0 lines are premises: if
one prints something else, stop and report, do not adapt. Before the pull request, run `/code-review
high` on the diff, then `dev/varka_ops/pre-pr.sh <worktree>` (it exits 0), then `python3
dev/varka_msg_check.py size`, which must print a count under
1000. `dev/varka_ops/INDEX.md` says how a pull request is opened and watched.

#### 360s: the runner's reading of the spike, and the carry rule

Title: `[VARKA-360] Record the wide sum spike's EPYC 7763 reading and fix the carry form`.
Branch `varka-360s-spike-runner`.

- **B0.** `git log --oneline origin/master | grep -c '#1799'` prints 1.
  `ls sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-results.txt`
  lists the file, and `grep -c '^8\. ' <it>` prints 0 (the committed file holds loops 1 to 7).
- **The lead's step, needs the network:** `gh run download 46084701494 -R vecbricks/varka` and
  the same for 46084708544, into two empty folders. Each folder holds the class's results file
  and its provenance file.
- **Which run is which width is read from the files, never from the run numbers.** In each
  folder run `sed -n 2p <the results file>`. The 256-bit run prints
  `decimal lane pair hand loops at 256 bits, forms [...]`; the run under
  `-XX:MaxVectorSize=16` prints the same line with `at 128 bits`, and its provenance file
  names `MaxVectorSize=16`. If the two folders print the same width, or a third one, stop and
  report.
- **Steps.** (1) Replace the class's two runner results files and their provenance files with
  the artifacts': the folder whose line says 256 bits into `...-runner-epyc7763-results.txt`
  and `...-runner-epyc7763-provenance.txt`, the other into the `-128bit` companions. (2) Add
  `sql/varka/plans/m8/results/VARKA-360-arm-rule.py`, the script of
  `/home/max/varka-ops/m8-row-plans/scratch-decimal-r2/arm_rule.py`, with the licence header.
  (3) Run it on the 256-bit file with the arms `"P COMPARE"` and `"P BITS"`. (4) After 10.5 add
  a subsection, "The spike on the EPYC 7763", that holds the script's nine lines and its
  `VERDICT` line as printed, the two run numbers with the width each file's second line gives,
  and one sentence: the verdict and what it means by 11.3 decision 1.
- **Oracle.** `python3 sql/varka/plans/m8/results/VARKA-360-arm-rule.py
  sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-results.txt
  "P COMPARE" "P BITS"` prints nine lines that begin `block 0` and one line that ends
  `VERDICT: P COMPARE` or `VERDICT: P BITS`, and exits 0. The same command on the `-128bit`
  companion prints one line that begins `REFUSED:` and exits 2: run it, it is the proof that
  the rule cannot be read from the wrong file. `grep -c '^8\. ' <256-bit file>` prints 9 (three
  arms at three rungs). `python3 dev/varka_quote_check.py sql/varka/plans/m8/VARKA-360.md`
  prints 0 orphans.
- **Done when** the files and that subsection are merged. **Not here:** any code; the laptop's
  files.

#### 360b1: `WordConst`, `WideMul`, `VarkaDecimalLowering` (full strength)

Title: `[VARKA-360] Multiply a long by a long into 128 bits as a lowered limb network`.
Branch `varka-360b1-widemul`. Source of every file below:
`git -C /home/max/proj/vecbricks-varka show varka-360b-widemul:<path>`, and its change against
its base, `git diff 73a64c3333e varka-360b-widemul -- <path>`.

What makes it full strength: the old branch added arms to switches that row 489 deleted, so the
node is re-expressed on master's descriptor by hand; the lowering's hook in `VarkaLoopEmitter`
goes into methods that changed since; and nothing but the compiler and the registry suites says
whether the re-port is complete.

- **B0.** On master: `grep -c 'record WideMul\|record WordConst' VARKA/VarkaVectorIR.java` prints
  0; `ls VARKA/VarkaDecimalLowering.java` fails; `grep -c 'List.copyOf(outputs)'
  VARKA/VarkaLoopEmitter.java` prints 11; `grep -c 'Validity ALL_TRUE' VARKA/VarkaNodeProps.java`
  prints 1.
- **Step 1, the records** (`VARKA/VarkaVectorIR.java`). Take `WordConst(long value, LaneType
  lane)` and `WideMul(VarkaVectorIR left, VarkaVectorIR right, Word word, boolean rightOneLimb,
  int hash)` from the old branch, javadoc included, and add both to `permits`. Each gets
  `props()`, which the old branch did not have:

        // WordConst: a constant in the code, valid on every lane, an immediate of its operator.
        return new VarkaNodeProps(NEVER, false, ALL_TRUE, 0,
            getClass().getSimpleName() + '/' + lane, FILLS_NOTHING, RESTRICTS_NOTHING,
            LongNarrowReader.NONE);
        // WideMul: null where an operand is; the weight is its lowered network's lane calls.
        return new VarkaNodeProps(NEVER, false, STRICT, VarkaDecimalLowering.laneCalls(this),
            getClass().getSimpleName() + '/' + word + (rightOneLimb ? "/1" : ""), FILLS_NOTHING,
            RESTRICTS_NOTHING, LongNarrowReader.NONE);

  `WordConst` keeps both constructors of the old branch: the record's own, `WordConst(long
  value, LaneType lane)`, and `WordConst(long value)`, which is the long lane. Then add the
  structural arms (`laneType`, `childrenOf`, `withChildren`, the two canonical forms, the
  narrow walks), from the old diff. The canonical forms on master walk a DAG: where the old arm
  calls `canonical(n.left())`, master's neighbours call `kid.apply(...)`; copy the shape of
  `IntArith`'s arm in each form. The rendered heads stay `wconst:<value>` with the lane tag and
  `wmul:lo` or `wmul:hi`, then `:1` for one limb.
- **The files a new node goes into: a command, not a list kept by hand.**
  `git grep -lw HashStep -- sql dev | grep -v '\.md$'` prints every file that names a composite
  value node that exists on master. `HashStep` is the model: two value operands on one lane,
  lowered by a lowering class of its own. `InterleaveDecimal` is the wrong model: it is a
  store's root, and its list holds the store's files (`VarkaBodyEmitter`, `VarkaTypes`, the
  emit options). On master `6dafb8bd2f6` the command prints 24 files. **The rule: every file it
  prints either gets the new node or is in the last row below, with the reason.** Classified
  now, so that the builder checks and does not discover:

| files the command prints | what `WideMul` and `WordConst` get there |
|---|---|
| `VarkaVectorIR.java` | the records and `permits` (step 1) |
| `Analysis.java` | `collectRefusals`: an empty arm each, as `case HashStep n -> { }`; `analyze`: `WordConst` as `LiteralSlot`'s arm with no slot (column set 0, height 0, not skipping), `WideMul` throws, since it is lowered first |
| `Slots.java` | *[Fix round 3.]* Two places, nothing else. (a) The CSE condition at line 1014 (`if (cse && bodyUses.getOrDefault(node, 0) > 1 && !(node instanceof LiteralSlot)`): add `&& !(node instanceof VarkaVectorIR.WordConst)` beside `NullValue`'s, as the old branch did (a leaf re-emitted at each use is never shared). (b) The one exhaustive switch of `WordWalk.visit` (from line 1625; it holds `case LiteralSlot l -> { }` at 1688, `case VarkaVectorIR.NullValue x -> { }` at 1690 and `case HashStep x -> demand(analysis.wordOwner.get(x.word()));` at 1729): `case VarkaVectorIR.WordConst l -> { }` after `LiteralSlot`'s, and `case VarkaVectorIR.WideMul x -> throw VarkaErrors.illegalState("WideMul is lowered before the analysis");` (the helper `Slots` already throws with) beside `HashStep`'s. That switch is the demand walk; master's `propagate` (line 1747) has no switch, and the old branch's `wordRef` switch is gone (master reads `node.props().validity()`, line 1188). Lines on master `6dafb8bd2f6`. |
| `VarkaKernelPlan.java` | the exhaustive list that holds `VarkaVectorIR.HashStep _` (`longNarrowRole`'s) |
| `VarkaRangeAnalysis.java` | `VarkaValueRange.UNKNOWN` for both |
| `VarkaSameAnswer.java` | `WideMul` beside `HashStep _ -> byValueChildren(n)`; `WordConst` beside `LiteralSlot _` |
| `VarkaChronoCompiler.java` | the exhaustive list that holds `VarkaVectorIR.HashStep _` |
| `VarkaVectorWalk.java` | `WordConst`: the leaf of step 5; `WideMul`: throws, it never reaches the walk |
| `VarkaCanonicalIr.java`, `VarkaReference.java`, `VarkaIrGrammar.java`, `VarkaGuardSpecs.java`, `VarkaSmtNodes.java`, `VarkaWordCensus.java` | the test fixtures of step 6 |
| `VarkaLaneTypeSuite.scala`, `VarkaReproductionSuite.java`, `VarkaRangeAnalysisSuite.scala`, `VarkaWithChildrenSuite.scala`, `VarkaIrFuzzSuite.scala`, `VarkaEmitCostSuite.scala`, `VarkaEmitterBudgetSuite.scala` | each suite's register of nodes (step 6) |
| `VarkaHashCompiler.java`, `VarkaHashLowering.java`, `VarkaEmitterHashSuite.java` | **nothing**: they are `HashStep`'s own compiler, lowering and suite; the new nodes' own are `VarkaDecimalCompiler` (from 360b2), `VarkaDecimalLowering` and `VarkaWideMulSuite` |

  Two files the command cannot print get the nodes too: `VarkaDecimalLowering.java` (new) and
  `VarkaEmitCostTable` (a price for each cost feature, step 6). A leaf has three more sites, which
  `git grep -nw NullValue -- VARKA/VarkaLoopEmitter.java VARKA/VarkaVectorWalk.java
  VARKA/Slots.java` prints (on master: `VarkaLoopEmitter` line 1967, `VarkaVectorWalk` lines 185
  and 230, `Slots` lines 1014 and 1690); at each, do for `WordConst` what
  `git -C /home/max/proj/vecbricks-varka diff 73a64c3333e varka-360b-widemul -- <that file>`
  did. **Stop rule:** if `build/sbt 'catalyst/Test/compile'` passes while a file of the first ten
  rows holds neither node, or fails in a file the table does not hold, stop and report: the
  classification is wrong, and the plan is corrected before the part goes on.
- **Step 2, the old arms that have no home.** Do not port the old hunks in
  `VarkaEmitBudget.weightOf`, `VarkaEmitCost.kindOf`, `VarkaOperandPosition` (both),
  `VarkaKernelPlan.longNarrowReader`, `Analysis.isNarrowRing`, `Analysis.ownerOf`,
  `Analysis.pureOf`, `Slots.planWordRef` and the old branch's `wordRef` and `propagate` hunks
  (gone from master; *[fix round 3]*, the demand walk itself is `Slots.visit`, ported below):
  `props()` answers them
  (`VARKA-489.md`, "Adding a node, after 489c"). Port the others where `catalyst/compile` names
  a missing arm: `Analysis.collectRefusals` and `Analysis.analyze` (`WordConst` as `LiteralSlot`'s
  arm with no slot: column set 0, height 0, not skipping; `WideMul` throws, since it is lowered
  first), `Slots.visit` and `Slots`' CSE condition (the table's two places),
  `VarkaKernelPlan.longNarrowRole`, `VarkaRangeAnalysis` (both
  `UNKNOWN`), `VarkaSameAnswer`, `VarkaChronoCompiler`'s exhaustive list.
- **Step 3, the lowering** (`VARKA/VarkaDecimalLowering.java`, new). Take the old file. Replace
  its `lower(WideMul, faults)` body by a call of the helper of 11.3 decision 3, and add the
  helper, which is the old body with the two corrections under flags:

        static VarkaVectorIR lower(WideMul n, VarkaEmitOptions options,
            Set<PlantedFault> faults) {
          return productWord(options, n.left(), n.right(), n.word(), n.rightOneLimb(), true,
              true, faults);
        }

        /** One word of x * y, each operand read signed or unsigned (11.4's network). */
        static VarkaVectorIR productWord(VarkaEmitOptions options, VarkaVectorIR x,
            VarkaVectorIR y, Word word, boolean rightOneLimb, boolean xSigned, boolean ySigned,
            Set<PlantedFault> faults) {
          VarkaVectorIR xl = bit(BitOpKind.AND, x, MASK);
          VarkaVectorIR xh = bit(BitOpKind.USHR, x, HALF);
          VarkaVectorIR yl = bit(BitOpKind.AND, y, MASK);
          VarkaVectorIR p0 = mul(xl, yl);
          VarkaVectorIR p1 = mul(xh, yl);
          VarkaVectorIR p0High = bit(BitOpKind.USHR, p0, HALF);
          var corrections = new ArrayList<VarkaVectorIR>();
          if (xSigned) {
            corrections.add(bit(BitOpKind.AND, y, bit(BitOpKind.SHR, x, SIGN)));
          }
          if (rightOneLimb) {
            if (!faults.isEmpty()) {
              throw VarkaErrors.illegalArgument("the planted middle column needs two limbs");
            }
            VarkaVectorIR t = add(p1, p0High);
            if (word == Word.LOW) {
              return bit(BitOpKind.OR, bit(BitOpKind.SHL, t, HALF), bit(BitOpKind.AND, p0, MASK));
            }
            VarkaVectorIR high = bit(BitOpKind.USHR, t, HALF);
            return corrections.isEmpty() ? high : sub(high, corrections.get(0));
          }
          VarkaVectorIR yh = bit(BitOpKind.USHR, y, HALF);
          VarkaVectorIR p2 = mul(xl, yh);
          VarkaVectorIR p3 = mul(xh, yh);
          if (ySigned) {
            corrections.add(bit(BitOpKind.AND, x, bit(BitOpKind.SHR, y, SIGN)));
          }
          boolean planted = faults.contains(PlantedFault.MIDDLE_COLUMN_ONE_WORD);
          VarkaVectorIR t = planted ? add(add(p1, p2), p0High) : add(p1, p0High);
          VarkaVectorIR u = planted ? t : add(p2, bit(BitOpKind.AND, t, MASK));
          if (word == Word.LOW) {
            return bit(BitOpKind.OR, bit(BitOpKind.SHL, u, HALF), bit(BitOpKind.AND, p0, MASK));
          }
          VarkaVectorIR high = add(p3, bit(BitOpKind.USHR, t, HALF));
          if (!corrections.isEmpty()) {
            high = sub(high, corrections.size() == 1 ? corrections.get(0)
                : add(corrections.get(0), corrections.get(1)));
          }
          return planted ? high : add(high, bit(BitOpKind.USHR, u, HALF));
        }

  The old file's `lowerAll(outputs, faults)` and its private `lowered(node, faults, ...)`
  gain `VarkaEmitOptions options` as their second parameter and pass it down:
  `static List<VarkaVectorIR> lowerAll(List<VarkaVectorIR> outputs, VarkaEmitOptions options,
  Set<PlantedFault> faults)`. No lowering reads an option in this part (11.3 decision 3); the
  javadoc of `lowerAll` says "the options the kernel is emitted under; row 361's two options
  read them". `laneCalls(WideMul)` lowers under `VarkaEmitOptions.DEFAULTS` and counts the
  lowered children only: it must never call `props()` of the node it weighs, since
  `WideMul.props()` calls `laneCalls`. The constants `MASK`, `HALF`, `SIGN`, the private `bit`,
  `mul`, `add`, `sub` and the enum `PlantedFault` stay as the old file has them.
- **Step 4, the hook** (`VARKA/VarkaLoopEmitter.java`). Add a private
  `lowered(List<VarkaVectorIR> outputs, VarkaEmitOptions options)`, which returns
  `VarkaDecimalLowering.lowerAll(List.copyOf(outputs), options, Set.of())`. Replace
  `List.copyOf(outputs)` by `lowered(outputs, options)` at 10 of its 11 sites: every one that
  feeds `analyze`, `groupOutputs` or the private `emit`; leave the one in
  `readsCheckIntOverflowForTest`. Each of the ten sits in a method with a `VarkaEmitOptions`
  parameter; if one does not, pass `VarkaEmitOptions.DEFAULTS` there and name the site in the
  pull request's description. The private `emit` gains a last parameter
  `Set<VarkaDecimalLowering.PlantedFault> faults`, which it hands to `lowerAll` in place of
  `Set.of()`; every existing caller passes `Set.of()`. Add, as the old branch has it but for
  master's parameter list of `emit`:

        /** emit with faults planted in the composites' lowerings; no production path. */
        static byte[] emitPlantedForTest(String className, List<VarkaVectorIR> outputs,
            int numInputs, int numLiterals, VarkaEmitOptions options,
            Set<VarkaDecimalLowering.PlantedFault> faults) {
          return emit(className, outputs, numInputs, numLiterals, null, null, options,
              new VarkaEmitTrace(), Set.copyOf(faults));
        }

  In `fitsBudgets`, walk `lowered(outputs, options)` in the loop that calls `budgetWalk`, and
  keep `VarkaKernelPlan.of(outputs, options)` on the unlowered list.
  `VarkaCompileObserver.emitted` keeps the unlowered list.
- **Step 5, the walk** (`VARKA/VarkaVectorWalk.java`). The old branch's two additions: the
  `WordConst` leaf (the species, `loadConstant(value)`, the lane's `broadcast`), and
  `emitBitOpByConstant`, called first in the `BitOp` emission where the right operand is a
  `WordConst`: `lanewise(op, long)`, which keeps the mask or count a constant in C2's graph. In
  `VarkaLoopEmitter`'s test for a leaf that is inlined at its use (the one that names
  `CondConst` and `NullValue`), add `WordConst`.
- **Step 6, the fixtures.** From the old diff: `VarkaReference` (the constant's value;
  `a * b` and `Math.multiplyHigh(a, b)`), `VarkaCanonicalIr` (the `wmul` and `wconst` parse
  arms, re-written to master's DAG reader beside `IntArith`'s), `VarkaIrGrammar` (`boundsOf`,
  and `drawWideProductShape` with its seed `WIDE_PRODUCT_FUZZ_SEED`), `VarkaGuardSpecs`,
  `VarkaSmtNodes` (two `REFUSED` entries), `VarkaWordCensus`, and the registers of
  `VarkaLaneTypeSuite`, `VarkaReproductionSuite`, `VarkaRangeAnalysisSuite`,
  `VarkaWithChildrenSuite`, `VarkaIrFuzzSuite` (the wide product's sequence runs),
  `VarkaEmitCostSuite` and `VarkaEmitterBudgetSuite`. Price the two cost kinds in
  `VarkaEmitCostTable` as `VarkaEmitCostSuite` asks. `python3 dev/varka_push_check.py` names, in
  its rule R1, each fixture a new record still lacks.
- **Step 7, the suite** (`TJ/VarkaWideMulSuite.java`, JUnit 5 under `@VarkaSuite`). From the old
  file take the constants' tests only: "the reference and the lowered network give BigInteger's
  two words", "both words of a constant product fuse at every width, length and body" and "the
  two words share one limb network, and a tree without a product is kept". Add one test for the
  helper's flags, "a product word reads each operand signed or unsigned": for `x` and `y` over
  the suite's operands, evaluate `productWord(VarkaMatrixRuns.BASE, x, y, word, false, xs, ys,
  Set.of())` through `VarkaReference` for the four flag pairs, and hold
  `(hi * 2^64 + lo) mod 2^128`, both words read unsigned, to the `BigInteger` product of the
  operands read as the flags say, reduced `mod 2^128` too. The reduction is needed: with both
  operands unsigned the high word is an unsigned word, and a comparison that reads it signed
  fails. Four tests are written; three ship here (the size rule under Commands). The planted
  fault throws on a one-limb node by design, so a test that plants
  it (360b1p's) draws constants of two limbs only. *[Fix round 3.]* These tests emit one
  `WideMul`'s two words under `VarkaMatrixRuns.BASE`, as the old branch's do. If the option
  matrix's `methodByteBudget=0` or `cse=false` configuration fails one of them with a size
  decline, set `methodByteBudget` and `cse` in that test's options as `DEEP` does (11.3
  decision 5) and say so in the pull request; never a skip.
- **Commands.**

        build/sbt 'catalyst/Test/compile'                      -> [success]
        build/sbt 'catalyst/testOnly *VarkaWideMulSuite'       -> Passed: Total 3
        build/sbt 'catalyst/testOnly *VarkaNodePropsSuite *VarkaLaneTypeSuite \
          *VarkaWithChildrenSuite *VarkaReproductionSuite *VarkaSmtNodesSuite \
          *VarkaRangeAnalysisSuite *VarkaEmitCostSuite *VarkaEmitterBudgetSuite \
          *VarkaIrFuzzSuite *VarkaVectorIRSharingSuite *VarkaFuzzTypesSuite'   -> passes
        dev/varka_regen_generated.sh --all                     -> passes
        git status --short                                     -> no generated file moved

  The last line is the part's guard that no existing shape changed: the node is in no SQL
  shape, so `emitted_bytes.json`, `coverage.json`, `width_audit.json` and the census are
  byte-identical. *[Fix round 3, replacing the rule "if the size is over 900".]* Step 7's
  third test, "the two words share one limb network, and a tree without a product is kept",
  goes to 360b1p in every case: the old branch's diff outside `.smt2`, `.json` and `.md` is
  963 lines, 879 without the 84 proof lines that 360b1p takes, and this part adds about 85
  (review 2, L2), so the move is the normal case and a rule on it only makes the totals
  conditional. `VarkaWideMulSuite` is Total 3 here. If `python3 dev/varka_msg_check.py size`
  still prints 950 or more, move the fourth test (the flags) to 360b1p too: Total 2 here. After
  360b1p it is Total 5 either way.
- **Done when** the commands pass and the option matrix passes on the pull request.
- **Not here:** `VarkaDecimalCompiler`; any option; the planted fault's test and the proofs
  (360b1p); `PairMul`, `ScaleDown` (rows 362 and 363).

#### 360b1p: the product's proof and the planted middle column

Title: `[VARKA-360] Prove the wide product's limb network and fail its planted carry`.
Branch `varka-360b1p-product-proofs`. *[Fix round 3.]* Waits on 360b1 and on the three merges
of step 0 of 11.2's chain (#1799, `varka-360a-sums`, `varka-360a-bigint-cast`): they edit
`VarkaProofFiles.java` and regenerate `decimal128.smt2`, as this part does.

- **B0.** `grep -c 'static VarkaVectorIR productWord' VARKA/VarkaDecimalLowering.java` prints 1.
  `grep -c 'bigintPair(' DC` prints at least 2 (the last of step 0's merges is in).
  `grep -c 'MIDDLE_COLUMN_ONE_WORD' VARKA/VarkaDecimalLowering.java` prints at least 2.
- **Steps.** (1) `TJ/VarkaProofFiles.java`: the old branch's product layer of `decimal128.smt2`
  (`git diff 73a64c3333e varka-360b-widemul -- <TJ>/VarkaProofFiles.java`, 84 lines): the ring
  identity over limbs, and the carry layer for the constants `10^k`, `k` from 0 to 18, in the
  one-limb and the two-limb form. (2) Regenerate `sql/varka/proofs/decimal128.smt2`
  (`dev/varka_regen_generated.sh --all` rewrites it from the renderer). (3)
  `VarkaWideMulSuite`: the old test "the planted middle column misses the carry of a negative
  operand", with these pinned draws, each of which `verify_360.py` and `verify_361.py` hold to
  fail under the planted order and pass under the real one: the constant `Long.MAX_VALUE` times
  `-774389111720876485`; and a statement that a non-negative operand never fails (the old test
  has it). *[Fix round 3.]* With it, 360b1's moved test (or tests, by 360b1's size rule):
  "the two words share one limb network, and a tree without a product is kept", and the flags
  test if 360b1 moved it; 360b1's pull request description names which, and `grep -c '@Test'
  TJ/VarkaWideMulSuite.java` before this part prints 3 or 2 to match. (4) Add
  `sql/varka/plans/m8/results/VARKA-360-verify.py`, the script of 11.4, with
  the licence header. (5) Section 10 gains a subsection, "360b: the node and its proofs",
  with the solver times read from the run.
- **Commands.**

        dev/varka_prove.sh --solver both decimal128.smt2       -> exit status 0, and one line a
            solver, each ending "holds" ("FAILED" is a stop): the script prints
            "<solver> decimal128.smt2  <N> checks  <seconds> s  holds"
        build/sbt 'catalyst/testOnly *VarkaProofFilesSuite *VarkaWideMulSuite'   -> passes, and
            VarkaWideMulSuite is Total 5
        python3 sql/varka/plans/m8/results/VARKA-360-verify.py -> OK: 5231755 checks, 0 failures

- **Done when** they pass. **Not here:** the column product's proof layer (row 361's 361p).

#### 360b2: the rescales up of class U, and the infallible rescaling casts

Title: `[VARKA-360] Fuse decimal sums of two scales and the widening casts that rescale`.
Branch `varka-360b2-rescales`. Hunks to read first:
`git diff varka-360b-widemul varka-360b2-rescale -- <DC>` (the old part), then apply by hand:
the compiler changed under it in 360a.

- **B0**, on master after 360b1 and the three 360a pieces: `grep -c 'rescaledPastLong(' DC`
  prints 3; `grep -c 'bigintPair(' DC` prints 2; `grep -c 'reachesScale(' DC` prints 3;
  `grep -c 'declinesTwoLaneChecks' DC` prints 0; `grep -c 'record WideMul'
  VARKA/VarkaVectorIR.java` prints 1. *[28 November 2026.]* Once row 597's part 597b merged,
  `declinesTwoLaneChecks` prints 1 and 11.7's two-lane rule applies to this sheet; then
  `grep -c 'declinesAtTwoLanes(c, sink)' DC` must print 1, and where it prints 0 this part adds
  `bigintPair`'s two-lane call and its test line as 11.7 writes them.
- **Step 1, three helpers in `DC`**, beside `signExtended`:

        static final String TWO_LANE_WIDE_REASON = "a decimal rescale, checked sum or product "
            + "past 18 digits, on an x86 host whose 64-bit species have two lanes or fewer: a "
            + "pair is loaded and stored by two-vector rearranges, which C2 does not lower "
            + "there (VARKA-597, VARKA-698)";

        /** The long x times 10^k, 1 <= k <= 18: both words of one WideMul. */
        private static Pair wideRescale(VarkaVectorIR x, int k, DecimalType type,
            DeclineSink sink) {
          long c = Decimal.POW_10()[k];
          boolean oneLimb = c < 1L << 32;
          VarkaVectorIR slot = sink.longSlot(c);
          return new Pair(new WideMul(x, slot, Word.LOW, oneLimb),
              new WideMul(x, slot, Word.HIGH, oneLimb), type);
        }

        /** The pair x times 10^k, 1 <= k <= 18, modulo 2^128 (`VARKA-360.md` 11.4). */
        private static Pair pairTimesWord(Pair x, int k, DecimalType type, DeclineSink sink) {
          long c = Decimal.POW_10()[k];
          boolean oneLimb = c < 1L << 32;
          VarkaVectorIR slot = sink.longSlot(c);
          VarkaVectorIR lo = x.lo();
          // lo is an unsigned word: where its top bit is set it is 2^64 more than the long.
          VarkaVectorIR unsigned = new BitOp(BitOpKind.AND, slot,
              new BitOp(BitOpKind.SHR, lo, sink.longSlot(Long.SIZE - 1)));
          VarkaVectorIR hi = new IntArith(IntOp.ADD, Overflow.WRAP,
              new IntArith(IntOp.ADD, Overflow.WRAP, new WideMul(lo, slot, Word.HIGH, oneLimb),
                  unsigned),
              new IntArith(IntOp.MUL, Overflow.WRAP, x.hi(), slot));
          return new Pair(new WideMul(lo, slot, Word.LOW, oneLimb), hi, type);
        }

        /** The long x times 10^k, 19 <= k <= 37, modulo 2^128: 10^k = ch * 2^64 + cl. */
        private static Pair longTimesTwoWords(VarkaVectorIR x, int k, DecimalType type,
            DeclineSink sink) {
          DecimalWords c = DecimalWords.of(BigInteger.TEN.pow(k));
          VarkaVectorIR cl = sink.longSlot(c.lo());
          VarkaVectorIR hi = new WideMul(x, cl, Word.HIGH, false);
          if (c.lo() < 0) {
            // cl is an unsigned word with its top bit set: 2^64 more than the long.
            hi = new IntArith(IntOp.ADD, Overflow.WRAP, hi, x);
          }
          hi = new IntArith(IntOp.ADD, Overflow.WRAP, hi,
              new IntArith(IntOp.MUL, Overflow.WRAP, x, sink.longSlot(c.hi())));
          return new Pair(new WideMul(x, cl, Word.LOW, false), hi, type);
        }

  `pairTimesWord` is the old branch's `pairRescale` under the name the four plans use; no
  branch holds the new name. `longTimesTwoWords` is new.
- **Step 2, `atPairScale`.** With `k = s - t.scale()` and `at = (t.precision() + k, s)`, after
  the negative-scale test and the `k == 0` and `foldable` cases, which stay:

| the operand `e` | built |
|---|---|
| a `BIGINT` under its cast to a wide type of scale 0 | the long `l` (compiled as `bigintPair` compiles it): `wideRescale(l, k, at)`. Here `k <= 18` always: a `BIGINT` against a scale of 19 or more is a sum of 40 digits, not class U, and `at`'s precision would pass 38 |
| any other wide `e` (a pair), `k <= 18` | `pairTimesWord(pair(e), k, at)` |
| a value within 18 digits, `t.precision() + k <= 18` | as today: `rescale` within a long, sign-extended |
| a value within 18 digits, `k <= 18` | `wideRescale(value(e).unscaled(), k, at)` |
| a value within 18 digits, `k >= 19` | `longTimesTwoWords(value(e).unscaled(), k, at)` |

  Before the four rows that build a `WideMul`: `if (sink.declinesTwoLaneChecks()) return
  sink.decline(TWO_LANE_WIDE_REASON, whole);`. A pair with `k >= 19` cannot occur in class U
  (11.3 decision 6); *[fix round 3]* the cast arm can bring one (`CAST(a AS DECIMAL(38, 19))`
  with `a` a `DECIMAL(19, 0)` keeps every digit and rescales a pair by 19), and a hand-built
  tree can too: decline it with "a wide decimal rescaled by more than 18 digits", so that
  neither throws.
- **Step 3, the callers.** `reachesScale` returns true for every operand (delete it and its
  uses); `uncappedSum` is then the type rule alone, `P <= 38`. Delete `rescaledPastLong`;
  `sumReason` is reached only past 38 digits. In `pair`, the cast arm's guard is
  `case Cast c when c.child().dataType() instanceof DecimalType from && from.scale() ==
  type.scale() && keepsDigits(from, type)`: **delete the conjunct `from.scale() ==
  type.scale() &&`**, so that the guard is `... instanceof DecimalType from &&
  keepsDigits(from, type)`, and make the arm call
  `atPairScale(c, "cast", c.child(), from, type.scale(), ...)`, re-typed to `type`. In
  `bigintPair`, a scale from 1 to 19 with `type.precision() - type.scale() >= 19` builds
  `wideRescale` or `longTimesTwoWords` of the long; fewer integral digits decline (text below).
  In `compared`, `compiledOperands` and `wideOperand`, delete the loop that declines a side for
  `rescalesIntoWide`: `pair` builds that cast now.
- **Step 4, the reasons.** Before changing a text, `grep -rn '<a fragment of it>'
  sql/catalyst/src/test sql/core/src/test sql/varka docs dev` and move every hit with it.

| reason on master today | after this part |
|---|---|
| "a decimal sum past 18 digits over an operand rescaled past 18 digits takes a constant WideMul, row 360's" (and "difference") | gone: the sum fuses |
| "a rescale to a decimal wider than 18 digits is rows 359 and 360's" | for a cast that `keepsDigits`: gone under a pair consumer; under a consumer of one long: "a rescale to a decimal wider than 18 digits is two words, which a consumer of one long does not read (row 359's)" |
| "a decimal cast that lowers the scale or the integral digits is row 350's, over a rescale to a decimal wider than 18 digits, rows 359 and 360's" | "a decimal cast that lowers the scale or the integral digits of a decimal wider than 18 digits can round or overflow, rows 350 and 364's" |
| "a BIGINT rescaled into a decimal wider than 18 digits is row 360's constant WideMul" | gone where the type keeps 19 integral digits |
| the same with ", and with fewer than 19 integral digits it can overflow, which takes row 350's guard as well" | "a BIGINT cast into a decimal with fewer than 19 integral digits can overflow, which takes row 350's guard" |
| "the validity of a cast to a decimal from a bigint is row 360's next part" | "the validity of a cast to a decimal from a BIGINT is left to the row engine (row 350's integral sources)" |
| "a decimal sum of 39 digits, capped at 38, is rows 360 and 363's" | unchanged here (360c1 moves it) |

- **Step 5, tests** in `TJ/VarkaDecimalPairSuite.java`, with its helpers `check`, `checkStored`,
  `decline`, `outputDecline` and `inMode`. Replace `rescaledSumsDeclineWithOneReason`,
  `bigintRescaleNamesItsRows` and the rescale half of `cappedAndRescaledSumsDecline` and of
  `cappedAndRescaledSumsDeclineAsOutputs` by:
  1. `rescaledSums`: `a + b`, `a - b`, `b - a`, `try_add(a, b)`, `-(a - b)` under `check` (as
     `a + b < a` and so on) and under `checkStored`, in ANSI and LEGACY, over these type pairs:
     `(20, 2)` with `(20, 4)` (a pair by one limb); `(20, 0)` with `(19, 12)` (a pair by two
     limbs); `(18, 0)` with `(18, 4)` and `(18, 2)` with `(20, 4)` (a long by one limb); `(18, 0)`
     with `(20, 12)` (a long by two limbs); `(5, 0)` with `(30, 25)` (a long by `10^25`, two
     words); `(1, 0)` with `(37, 36)` (the widest, `10^36`). *[Fix round 3.]* Every form of
     this test runs under `DEEP` (11.3 decision 5: a kernel test of a shape with a `WideMul`);
     by the tables, `-(a - b) < a` over every pair (15 at one limb, 17 at two limbs or two
     words) and, over the four pairs of two limbs or two words, `a + b < a`, `a - b < a`,
     `b - a < a`, `try_add(a, b) < a` and `-(a - b)` stored (each 15) are the ones that would
     also decline at a cap of 16. First declare `DEEP` in the suite, as 11.3 decision 5
     writes it. `checkStored` has an overload with options; give `check` one the same way:
     `check(sql, ta, tb)` calls `check(sql, ta, tb, VarkaMatrixRuns.BASE)`, and the new
     overload compiles with `VarkaExpressionCompiler.compilePredicate(e, seq(columns),
     options, VarkaAdmission.NO_HOST_REFUSAL)` (the call `VarkaDecimalValueSuite.compile`
     makes with `VarkaMatrixRuns.BASE`, its residual assertion kept) and hands `options` to
     `checkRows` and on to `answers`, which emits with `options.withLanesOverride(lanes)` in
     place of `VarkaMatrixRuns.BASE.withLanesOverride(lanes)`.
  2. `bigintRescales`: `CAST(a AS BIGINT) + b` and `b - CAST(a AS BIGINT)` with `a` a
     `DECIMAL(18, 0)` and `b` a `(9, 2)`, an `(18, 4)` and a `(20, 2)`; `CAST(CAST(a AS BIGINT) AS
     DECIMAL(38, 19))` and `DECIMAL(30, 4)` stored; `CAST(CAST(a AS BIGINT) AS DECIMAL(20, 5))`
     declines with the new text.
  3. `rescalingCasts`: `CAST(a AS DECIMAL(38, 10))` with `a` an `(18, 4)`, `CAST(a AS
     DECIMAL(36, 29))` with `a` a `(16, 9)`, `CAST(a AS DECIMAL(38, 4))` with `a` a `(20, 2)`,
     stored and under `< b`; `CAST(a AS DECIMAL(38, 4))` with `a` a `(38, 2)` declines with the
     "rows 350 and 364's" text.
  4. `rescaledSumsWithoutTheBitmapPass`: test 1's first pair under the options
     `storedSumsWithoutTheBitmapPass` builds (`validityByBitmap` off, both `validityOrFirst`).
  5. `bigintCastNullTestDeclines`: `CAST(CAST(a AS BIGINT) AS DECIMAL(20, 0)) IS NULL` declines
     with the new validity text (the pin 10.5 item 2 owes).
  6. `oneReasonForAOneLongConsumer`: `CAST(CAST(a AS DECIMAL(38, 10)) AS BIGINT)` and
     `-CAST(a AS DECIMAL(38, 10)) > 0` name what they name on master for an uncapped sum.
  *[Fix round 3.]* Tests 2, 3 and 4 run their kernels under `DEEP` (test 4 builds its options
  from `DEEP.toBuilder()`, 11.3 decision 5). The decline lines of tests 2 and 3, and tests 5
  and 6, which assert declines only, keep `VarkaMatrixRuns.BASE`.
  The suite has 24 tests before and 28 after (two removed, two edited, six added). Any other
  test of the suite that then fails holds a reason of step 4's table (`unequalScalesDecline`,
  `wideCastDownNamesTheWideRows` and the test of the operands rescaled within a long are the
  likely ones): move its expectation by the table, and change no expectation of a value. Planted
  check, by hand and reverted, recorded in the description: drop the `unsigned` term of
  `pairTimesWord`; `rescaledSums` must fail on the `(20, 2)` pair's row with a low word past
  `2^63`. In `TJ/VarkaTwoLaneConditionSuite.java` add one test: `a - b` stored and
  `CAST(a - b < a AS INT)`, `a` a `(20, 2)` and `b` a `(20, 4)`, decline with
  `TWO_LANE_WIDE_REASON` on the two-lane admission and fuse without it (10 tests); the decline
  under `VarkaMatrixRuns.BASE`, the fuse under `VarkaDecimalPairSuite.DEEP` *[fix round 3]*.
- **Step 6, coverage, proofs, baseline.** `TS/VarkaCoverageSuite.scala`: in "DECIMAL wide add"
  add `d19 + d9s4 < d19`, `l + d9 < d19`, `CAST(d18 AS DECIMAL(38, 10)) > d3810`; in "DECIMAL
  wide add values" add `d19 - d9s4`, `l + d18`, `CAST(d18 AS DECIMAL(38, 10))`,
  `CAST(d16s9 AS DECIMAL(36, 29))`, `CAST(l AS DECIMAL(38, 4))`. `VarkaProofFiles`: the old
  branch's rescale layer (`git diff varka-360b-widemul varka-360b2-rescale --
  <TJ>/VarkaProofFiles.java`, 21 lines), extended by one claim for `longTimesTwoWords` per `k`
  from 19 to 37, written as `pairTimesWord`'s is. `VarkaDecimalBaselineBenchmark.addWidening`:
  the sections of 11.6 take `fused` in place of `Fusion.NONE` (`addWidening(out, d, fused)`
  has that parameter since 360a, `varka-360a-sums`: do not add it a second time; fix round 3),
  and the `DECIMAL(18, 4) cast to
  DECIMAL(38, 10)` section, in `sections`' first loop, takes `fusedFilter`'s class as the pair's
  choice does;
  `VarkaDecimalBaselineQueriesSuite` follows. The committed runner files are not regenerated
  here (360d).
- **Commands.**

        build/sbt 'catalyst/testOnly *VarkaDecimal* *VarkaTwoLaneConditionSuite \
          *VarkaCoverageSuite *VarkaEmittedBytesSuite *VarkaProofFilesSuite \
          *VarkaCastMatrixSuite *VarkaExpressionCompilerSuite *VarkaCoverageCompositionFuzzSuite'
            -> passes; VarkaDecimalPairSuite alone is Total 28
        dev/varka_prove.sh --solver both decimal128.smt2        -> exit status 0
        dev/varka_regen_generated.sh --all                      -> passes
        build/sbt 'sql/testOnly *VarkaDecimal* *VarkaCoverageDifferentialSuite \
          *VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite'   -> passes
        python3 dev/varka_census.py --check                     -> passes
        JDK_JAVA_OPTIONS=-XX:UseAVX=2 build/sbt 'catalyst/testOnly *VarkaDecimalPairSuite'
            -> passes

  After the regeneration, `git diff --stat` must show `emitted_bytes.json` with new entries only
  (no existing shape's bytes move; the per-option digests move), and the cells of 11.6.
- **Done when** the commands pass, the matrix passes, and section 10 gains a subsection
  "360b2: the rescales".
- **Not here:** class K; any option; `VarkaDecimalCastCompiler` (row 350's); a cast that rounds.

#### 360c1: class K at one scale (full strength)

Title: `[VARKA-360] Fuse a decimal sum capped at 38 digits, its overflow a null or a decline`.
Branch `varka-360c1-capped-sum`.

What makes it full strength: it is the first decimal value that can be null where its operands
are not, so every reader of validity must be found and held; and it is the first use of a
nulling guard over a condition's value to null two words, which only a build shows the emitter
handles in both bodies and with `validityByBitmap` off.

- **B0**, on master after 360b2: `grep -c 'Optional<Pair> widePair(Expression whole' DC` prints
  1; `grep -c 'case Add a -> allValid' DC` prints 1; `grep -c 'TWO_LANE_WIDE_REASON' DC` prints
  at least 2; `grep -c 'decimalOverflow\|outside38\|guardedPair' DC` prints 0; `grep -c
  'Overflow mode' VARKA/VarkaVectorIR.java` prints at least 1 (`GuardedRange`'s mode); `grep -c
  'static Overflow overflowOf' CODEGEN/VarkaNodeCompiler.java` prints 1.
- **Step 0, the chain's overflow helpers.** Add to `DC`, beside `signExtended`, the four
  methods of 11.4 ("The overflow mode of the whole chain"), exactly as written there:
  `decimalOverflow`, `evalModeOf`, `outside38`, `guardedPair`. In
  `CODEGEN/VarkaDecimalCastCompiler.java`, `intoDecimal`, replace the two lines
  `Overflow mode = VarkaNodeCompiler.overflowOf(c.evalMode()) == Overflow.FAIL ? Overflow.FAIL :
  Overflow.NULL;` by `Overflow mode = VarkaDecimalCompiler.decimalOverflow(c.evalMode());`.
  Rows 362 and 363 call these four by name; change no signature after this part merges.
- **Step 1, the class.** Add beside `uncappedSum`:

        /** A sum or difference Spark caps at 38 digits with its scale kept (class K), each
         *  operand already at that scale or a constant. */
        private static boolean cappedKeepingScale(Expression e) {
          return (e instanceof Add || e instanceof Subtract)
              && e.children().apply(0).dataType() instanceof DecimalType t1
              && e.children().apply(1).dataType() instanceof DecimalType t2
              && e.dataType() instanceof DecimalType type
              && ResultType.ofSum(t1, t2).precision() > DecimalType.MAX_PRECISION()
              && type.precision() == DecimalType.MAX_PRECISION()
              && type.scale() == Math.max(t1.scale(), t2.scale())
              && atCappedScale(e.children().apply(0), t1, type.scale())
              && atCappedScale(e.children().apply(1), t2, type.scale());
        }

        /** Whether class K reads e at scale s with no rescale: at s, or a constant. */
        private static boolean atCappedScale(Expression e, DecimalType t, int s) {
          return t.scale() == s || e.foldable();
        }

  Everywhere `uncappedSum(x)` decides that a sum is a pair's (`wideReason`,
  `wideArithmeticBelow`), read `uncappedSum(x) || cappedKeepingScale(x)`.
- **Step 2, `widePair`.** Where it declines `rule.precision() > 38`: if
  `cappedKeepingScale(whole)`, build the operands as today (`atPairScale` at the result's scale,
  which with `k = 0` or a constant builds no `WideMul`), the wrapped words as today, then:

        if (sink.declinesTwoLaneChecks()) {
          return sink.decline(TWO_LANE_WIDE_REASON, whole);
        }
        // LEGACY's null on overflow is TRY's; ANSI declines the batch and the row engine raises.
        return Optional.of(guardedPair(new CondValue(outside38(lo, hi, sink), LaneType.LONG),
            lo, hi, decimalOverflow(evalModeOf(whole)), type));

  `whole` is the `Add` or the `Subtract` itself: a TRY form reaches `widePair` already
  unwrapped, so `evalModeOf` needs no arm for it. The check for `mistyped` uses the capped type
  `(38, S)`. **Change `atPairScale`:** its `new DecimalType(t.precision() + k, s)` becomes `new
  DecimalType(Math.min(DecimalType.MAX_PRECISION(), t.precision() + k), s)`, since a constant
  operand of another scale is folded at that type and `new DecimalType` throws past 38; a
  constant whose value at `S` has more than 38 digits then declines by `widePairLiteral`'s own
  test ("a decimal literal outside its type's precision").
- **Step 3, the reasons.** `sumReason` past 38 digits:

| the sum | reason |
|---|---|
| scale cut (`type.scale() < max(s1, s2)`) | "a decimal sum of 39 digits, capped at 38 with its scale cut, is row 363's" (the digits and "difference" as today) |
| scale kept, an operand at another scale | "a decimal sum of 41 digits, capped at 38, over an operand rescaled to its scale is row 360's guarded rescale" (until 360c2) |
| scale kept, operands at one scale | none: it fuses; under a consumer of one long, the two-words text of an uncapped sum |

  The text "a decimal sum of 39 digits, capped at 38, is rows 360 and 363's" goes; grep the tests,
  the census notes, `docs/sql-varka.md` and `results/VARKA-612-refused.txt` for "rows 360 and
  363" first.
- **Step 4, the null test.** In `valid`, before `case Add a -> allValid(...)` and the
  `Subtract` arm:

        case Expression a when cappedKeepingScale(a) -> sink.decline("the validity of a decimal "
            + "sum Spark caps at 38 digits: its overflow nulls or fails rows its operands do "
            + "not, so its null test is left to the row engine (VARKA-360)", a);

  The `RuntimeReplaceable` arm above it already unwraps `try_add`. Then read every caller of
  `valid`, `allValid` and `anyValid` in `DC` (`nullTested`, `pairCoalesce`, `pairPick`,
  `pairChoice`, `pairBranch`) and confirm by test 4 below that each declines or answers as
  Spark; no other change is expected.
- **Step 5, tests** in `VarkaDecimalPairSuite`. A new helper is needed, since `stored` asserts a
  status of 0. First split `stored`: its body becomes `private static int run(
  CompiledVarkaProjection p, List<Row> rows, DecimalType ta, DecimalType tb, VarkaEmitOptions
  options, int lanes, BigInteger[] got)`, which returns the kernel's status and fills `got`;
  `stored` calls it and keeps its `assertEquals(0, status, ...)`. Then:

        /** checkStored for a shape whose kernel may decline a batch: the rows Spark answers
         *  run as one batch and must match; each row Spark raises on runs between three rows
         *  that fit, and that batch must end with STATUS_VALUE_RANGE set. */
        private static void storedOrDeclined(String sql, DecimalType ta, DecimalType tb,
            boolean ansi, VarkaEmitOptions options) {
          inMode(ansi, () -> {
            List<Attribute> columns =
                List.of(VarkaSqlResolve.column("a", ta), VarkaSqlResolve.column("b", tb));
            Expression e = VarkaSqlResolve.parse(sql, seq(columns));
            var type = (DecimalType) e.dataType();
            CompiledVarkaProjection p = VarkaExpressionCompiler.compile(
                CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq(),
                seq(columns), options)
                .orElseThrow(() -> new AssertionError(sql + " does not fuse as an output"));
            Expression bound = VarkaSqlResolve.bind(VarkaSqlResolve.replaced(e), seq(columns));
            var answered = new ArrayList<Row>();
            var raised = new ArrayList<Row>();
            for (Row r : rows(ta, tb)) {
              try {
                bound.eval(new GenericInternalRow(
                    new Object[] {decimal(r.a(), ta), decimal(r.b(), tb)}));
                answered.add(r);
              } catch (SparkArithmeticException overflow) {
                raised.add(r);
              }
            }
            assertTrue(answered.size() >= 3, () -> sql + ": three rows that fit");
            checkStoredRows(sql, p, bound, type, answered, ta, tb, options);
            for (Row r : raised) {
              List<Row> batch = List.of(answered.get(0), r, answered.get(1), answered.get(2));
              for (int lanes : WIDTHS) {
                int status = run(p, batch, ta, tb, options, lanes, new BigInteger[4]);
                assertTrue((status & VarkaFusedKernel.STATUS_VALUE_RANGE) != 0,
                    () -> sql + " " + label(lanes) + ": the batch of " + r + " must decline");
              }
            }
          });
        }

  Under LEGACY and for a TRY form Spark raises on no row, so `raised` is empty and every row is
  held to Spark's value or null. The answered batch holds the suite's rows with a null operand;
  its status of 0 therefore also shows that a null lane's words do not fire the guard. If that
  assertion fails under ANSI and passes under LEGACY, that is the cause: stop and report.
  1. `cappedSumsAnswerAsSpark`: `a + b`, `a - b`, `b - a`, `try_add(a, b)`,
     `try_subtract(a, b)`, `-(a + b)`, `abs(a - b)`, over `(38, 2)` with `(38, 2)`, `(38, 0)`
     with `(38, 0)`, `(38, 6)` with `(38, 6)` and `(37, 2)` with `(38, 2)`, under ANSI and
     LEGACY, through `storedOrDeclined`, every form with the options `DEEP` (11.3 decision 5;
     *[fix round 3]*: `-(a + b)` and `abs(a - b)`, 15 and 16 levels, are the ones the cap of
     16 alone would also reach). The suite's
     `edges` hold each type's bounds, so `+-(10^38 - 1)` meets itself in every sign pair: the
     overflow that wraps 128 bits, the one that does not, and the sums that fit.
  2. `cappedSumsUnderAComparison`: `a + b < a` and `a - b >= b` over the same pairs under
     LEGACY: the filter's bit is false where the sum is null, as Spark's is.
  3. `cappedSumKernelsByMode`: `VarkaEmittedBytesSuite`'s pattern, one shape hash for LEGACY
     and TRY and another for ANSI (`a + b` against `try_add(a, b)`).
  4. `cappedSumNullTestsDecline`: `(a + b) IS NULL`, `-(a + b) IS NOT NULL`,
     `coalesce(a + b, a)` and `greatest(a + b, a)` each decline with step 4's text: all four
     read the sum's validity through `valid`. If one of them fuses, stop and report; do not
     change the expectation.
  5. `cappedSumsWithoutPrecisionLoss`: under
     `spark.sql.decimalOperations.allowPrecisionLoss=false` (set with the suite's `withConf`),
     `DECIMAL(38, 10) + DECIMAL(38, 10)` at `10^38 - 1` is class K: it passes test 1's helper.
     Under the default it declines naming row 363 (step 3's first text).
  6. `rapidsOverflowVectors`: 38 nines plus 1 and minus 38 nines minus 1 at `(38, 0)`, and the
     scale 10 add that overflows for both signs under the setting of test 5: null under LEGACY,
     a decline under ANSI (`READING_RAPIDS.md` 3 item 1).
  7. `cappedSumOverAConstantPastItsDigits`: a `DECIMAL(38, 6)` column plus the literal
     `12345678901234567890123456789012345678` (38 digits at scale 0, 44 at scale 6) declines
     with the literal's text and does not throw; plus `1` it fuses and passes test 1's helper.
  8. `decimalOverflowByMode`: `decimalOverflow` of `EvalMode.LEGACY()`, `ANSI()` and `TRY()`
     is `Overflow.NULL`, `FAIL` and `NULL`, and `new GuardedRange(x, 0, 0, decimalOverflow(m))`
     constructs for each of the three (with `overflowOf` alone LEGACY would throw there).
  9. `cappedSumsWithoutTheBitmapPass`: test 1's first type pair, `a + b` and `a - b`, through
     `storedOrDeclined` under the options `storedSumsWithoutTheBitmapPass` builds
     (`validityByBitmap` off, both `validityOrFirst`).
  *[Fix round 3.]* Tests 1, 2, 3, 5, 6, 7 (its fused half) and 9 compile and run under `DEEP`
  (test 9 from `DEEP.toBuilder()`); test 4 and test 7's decline keep `VarkaMatrixRuns.BASE`;
  test 8 compiles nothing. `check` takes `DEEP` through 360b2's options overload.
  Nine tests: 28 before, 37 after. Planted check, by hand and
  reverted: swap `CompareOp.GT` for `CompareOp.GE` in `outside38`; test 1 must fail at
  `10^38 - 1`. `VarkaTwoLaneConditionSuite`: `a + b` stored at `(38, 2)` declines with
  `TWO_LANE_WIDE_REASON` on the two-lane admission (under `VarkaMatrixRuns.BASE`).
- **Step 6, coverage, proofs, baseline.** Coverage: `d38 - d38b < d38` and `try_add(d38, d38b)
  <= d38` in "DECIMAL wide add"; `d38 + d38b` and `try_subtract(d38, d38b)` in "DECIMAL wide add
  values". Proofs: `VarkaProofFiles` renders the lemma of 0 item 5 into `decimal128.smt2`'s sum
  layer: for integers `a`, `b` within `10^38 - 1`, with `w` the sum reduced into
  `[-2^127, 2^127)`, `|w| <= 10^38 - 1` is equivalent to `|a + b| <= 10^38 - 1`; the same for the
  difference; and the word form of the check, `compare(GT)` and `compare(LT)` over words, equals
  the integer comparison. Baseline: the section "DECIMAL(38, 2) x + y as a value" takes `fused`.
- **Commands.** As 360b2's, with `VarkaDecimalPairSuite` at its new total.
- **Done when** they pass, the matrix passes, and section 10 gains a subsection "360c1: class
  K at one scale".
- **Not here:** a rescaled operand in class K (360c2); class C (row 363); a condition form of
  `GuardedRange` (section 7's possible follow-up to row 481).

#### 360c2: class K over a rescaled operand

Title: `[VARKA-360] Guard the rescale of a capped decimal sum's operand`.
Branch `varka-360c2-capped-rescale`.

- **B0**, on master after 360b2, 360c1 and row 697: `grep -c 'cappedKeepingScale(' DC` prints
  at least 3; `grep -c 'longTimesTwoWords(' DC` prints at least 3; `grep -c 'chainCapLongBits'
  VARKA/VarkaEmitOptions.java` prints at least 1 (row 697's option; if 0, stop: the part waits).
- **Step 1.** `atCappedScale` returns true for every operand but a pair that needs `k >= 19`.
  In `widePair`'s class K branch each operand goes through a new `atCappedPairScale`, which is
  `atPairScale` with the guard: with `k > 0` and `G = 10^(38 - k) - 1`,

| the operand | guard, then rescale |
|---|---|
| a pair of `p` digits, `k <= 18`, `p + k <= 38` | no guard (the type's bound); `pairTimesWord` |
| a pair of `p` digits, `k <= 18`, `p + k > 38` | `pairTimesWord(rescaleGuard(x, k, sink), k, ...)`; `rescaleGuard` is written out below |
| a value within 18 digits or a `BIGINT`, `p + k <= 38` (a `BIGINT` is 19 digits) | no guard; `wideRescale` (`k <= 18`) or `longTimesTwoWords` |
| a value within 18 digits or a `BIGINT`, `p + k > 38` | `new GuardedRange(x, -G, G)` (the three-argument form is `FAIL`), then the same |
| a pair, `k >= 19` | declines: "a decimal sum capped at 38 digits over a wide operand rescaled by more than 18 digits is no row's in this milestone" |

  The guard is `FAIL` in every mode, never `NULL`: the exact sum may still fit (Trino's vectors),
  so the batch goes to the row engine, which answers. The pair's guard is a named helper in
  `DC`, beside `pairTimesWord`, which row 363's part 363c3 calls too:

        /** x, a pair about to be multiplied by 10^k, behind a guard |x| <= 10^(38 - k) - 1
         *  that declines the batch in every mode: the exact sum may still fit, so the row
         *  engine answers. The guard is 0 where the batch survives, so the add changes no
         *  word. */
        static Pair rescaleGuard(Pair x, int k, DeclineSink sink) {
          DecimalWords top = DecimalWords.bound(DecimalType.MAX_PRECISION() - k);
          DecimalWords bottom = top.negate();
          Cond past = new Or(
              DecimalWords.compare(CompareOp.GT, x.lo(), x.hi(), sink.longSlot(top.lo()),
                  sink.longSlot(top.hi())),
              DecimalWords.compare(CompareOp.LT, x.lo(), x.hi(), sink.longSlot(bottom.lo()),
                  sink.longSlot(bottom.hi())));
          var g = new GuardedRange(new CondValue(past, LaneType.LONG), 0, 0, Overflow.FAIL);
          return new Pair(new IntArith(IntOp.ADD, Overflow.WRAP, x.lo(), g), x.hi(), x.type());
        }
- **Step 2, the planted order.** A test-only switch in `DC`, under which the guard is built over
  the rescaled pair against `10^38 - 1` in place of the operand against `G`. *[Fix round 3,
  replacing "`static boolean plantedRescaleThenCheck`": a mutable static is read by every
  suite compiling in the JVM.]* It is a per-thread flag, the house's form of a test hook in
  this file (`CONDITIONS_COMPILED`, master `VarkaDecimalCompiler.java` line 1208;
  `VarkaCompileObserver.QUIET`):

        /** Test only: build 360c2's guard after the rescale (the planted order). */
        static final ThreadLocal<Boolean> PLANTED_RESCALE_THEN_CHECK =
            ThreadLocal.withInitial(() -> false);

  read once, where `atCappedPairScale` builds the guard. Test 3 sets it and resets it in
  `finally`: `PLANTED_RESCALE_THEN_CHECK.set(true); try { ... } finally {
  PLANTED_RESCALE_THEN_CHECK.remove(); }`. The witness the differential must fail on, from
  `verify_360.py`: `k = 1`, `x = 94967449697919733291807668211216319782`, whose wrapped product by
  10 is `-71172603783618057472047140183141436548`, inside the check.
- **Step 3, tests** in `VarkaDecimalPairSuite`, with 360c1's `storedOrDeclined` extended by one
  parameter, `Predicate<Row> pastGuard`: a row for which it holds goes to `raised` whatever
  Spark answers (Spark answers such a row; the kernel declines its batch). Each test passes the
  predicate it computes itself, `|operand| > 10^(38 - k) - 1` for the rescaled operand; 360c1's
  callers pass `r -> false`. Every test here builds a tree deeper than 16 (11.3 decision 5), so
  each compiles and runs under `DEEP` (`maxChainDepth`, `methodByteBudget` and `cse` pinned, as
  11.3 decision 5 writes it since fix round 3); test 4's decline keeps `VarkaMatrixRuns.BASE`.
  Four tests: 37 before, 41 after.
  1. `cappedRescaledSums`: `(38, 0)` with `(38, 1)`, `(38, 2)` with `(9, 4)`, `(38, 0)` with
     `(38, 6)`, `(20, 0)` with `(38, 6)`, under ANSI and LEGACY, values at the guard
     `10^(38 - k) - 1` and one past it in both signs added to the suite's rows.
  2. `trinoVectors`: `17014000000000000000000000000000000000` plus
     `-7014000000000000000000000000000000000.1`, and the same with 17015 and 7015, the first
     operand a `(38, 0)` and the second a `(38, 1)`: both batches decline, and Spark's own
     answer of each row is 38 nines at scale 1 (not null, not an error).
  3. `plantedRescaleThenCheckFails`: with the switch on, the witness row answers a value where
     Spark answers another (or null); with it off, the row declines.
  4. `cappedRescalePastEighteenDeclines`: `(38, 0)` with `(38, 30)` under
     `allowPrecisionLoss=false` declines with step 1's text; `(5, 0)` with `(38, 37)` under the
     same setting fuses and passes the helper (a long times `10^37`, guarded at 9).
- **Step 4.** Coverage: `d38 + d9s4` in "DECIMAL wide add values", `d38 - d9s4 < 0` in "DECIMAL wide
  add" (a column at `(38, 2)` cannot be cast to the sum's `(38, 4)`). Proofs: the guard's claim per
  `k` from 1 to 37, `|x| <= 10^(38 - k) - 1` exactly where `|x * 10^k| <= 10^38 - 1`, and the
  planted order as a `sat` witness. `m10/SCOPE.md` item 140 gains one line for the declined pair
  rescale past 18 digits.
- **Commands.** As 360b2's. **Done when** they pass and section 10 gains a subsection
  "360c2: class K over a rescaled operand".
- **Not here:** class C; the pair rescale past 18 digits in class K.

#### 360d: the measurement

Title: `[VARKA-360] Measure the wide decimal sums against the baseline on the EPYC 7763`.
Branch `varka-360d-measure`.

- **The lead's step:** dispatch `benchmark.yml` on vecbricks/varka for
  `*.VarkaDecimalBaselineBenchmark`, `jdk=25`, `expected-cpu="AMD EPYC 7763"`, once alone and
  once with `-XX:MaxVectorSize=16`, on the pull request's head, and download both artifacts.
- **Steps.** Replace the class's two runner results files and provenance files. Add a subsection
  to section 10, "The measurement", with one table: for each section of 11.6's last column, the best
  time of Spark's row path and of varka on, without nulls and at one row in ten null, at 256 and at
  128 bits, read from the files. Score section 4's predictions 3, 4 and 5 by number. At 128 bits the
  expected reading is a tie, since every such shape declines there (11.3 decision 4): prediction 4
  is scored "not built: declined at two lanes, rows 597 and 698". Mark the row done in `PLAN.md` if
  every section of 11.6 reads faster than the row path by 1.3x or more at 256 bits; a section that
  does not is named in the row's note with its ratio, and the lead decides.
- **Oracle.** `python3 dev/varka_bench_diff.py --git HEAD~1 <256-bit file>` lists the sections
  of 11.6 among those that moved; `python3 dev/varka_quote_check.py
  sql/varka/plans/m8/VARKA-360.md` prints 0 orphans.
- **Not here:** code.

#### 360e: `decimalCarry`, only on the verdict `BITS` (the lead's, not a builder's)

It exists only if 360s's script prints `VERDICT: P BITS`, which the laptop's reading says is
not to be expected (no cell at 1.3 at 512 or 256 bits). It is the lead's part and no builder
takes it from this sheet: it is a new node whose proof layer and whose reading of C2's code
are not specified here, and specifying them for a part that most likely never exists would be
text nobody checks. What is fixed, so that nothing is invented on the day: a composite node
`WordCarry(VarkaVectorIR a, VarkaVectorIR b, boolean borrow)` on the long lane, 1 where the low
words' sum carries (or their difference borrows) and 0 elsewhere, null where an operand is;
`widePair` builds it in place of `carry` and `borrow`; `enum DecimalCarry { COMPARE, BITS }` and
the option `decimalCarry` (`Reason.MACHINE`, declared as `decimalStore` is: every site of `grep
-rn decimalStore sql/catalyst/src dev sql/varka/matrix`), default the verdict;
`VarkaDecimalLowering` lowers the node to 11.4's two forms, `~x` written `BitOp(XOR, x,
WordConst(-1))`; the files it goes into are 360b1's table; its test is "every arm of the carry
gives the 65th bit" in `VarkaWideMulSuite`. If the verdict is `COMPARE`, this part does not
exist and section 9's "the spike's verdict is committed per width before the build" is met by
360s.

### 11.6 What each part moves

**Decline reasons**: the tables of 360b2 step 4 and 360c1 step 3, and 360c2 step 1's last row.

**Census** (`sql/varka/plans/m8/census_curated.txt`, then `python3 dev/varka_census.py
--check`). No pair is counted by this row: `+`, `-`, `try_add` and `try_subtract` stay `dec^`
(fused in part) until row 363's class C. Each part rewrites the four entries' notes: 360b2 from
"past 18 within 38 where no operand is rescaled past a long" to "past 18 within 38 at every
scale"; 360c1 adds "and capped at 38 with the scale kept, at one scale"; 360c2 drops "at one
scale" and names the declined pair rescale.

**The cast matrix** (`casts.tsv`, `casts_fused.tsv`, `casts_suites.tsv`, the three matrices of
`docs/sql-varka.md`; all regenerated). 360b2 moves two cells in each mode: `dec` to `dec`
(the probe's `DECIMAL(10, 2)` to `DECIMAL(30, 4)` and `DECIMAL(20, 0)` to `DECIMAL(30, 4)` are
infallible rescales and fuse) and `bigint` to `dec` (from "1 of 3" to "2 of 3":
`DECIMAL(30, 4)` keeps 19 integral digits). `casts_suites.tsv` loses the `BIGINT` to
`DECIMAL(38, 18)` reason and rewords the `DECIMAL(20, 5)` one. Any other cell that moves is a
finding: stop and report it. No other part moves a cast cell.

**Coverage families**: "DECIMAL wide add" and "DECIMAL wide add values" grow as the sheets say;
no new family. `results/VARKA-612-refused.txt` gains each new value row.

**Baseline sections** of `VarkaDecimalBaselineBenchmark` that turn from the row path to a
kernel, each at both densities ("no nulls" and "one row in ten null"), and to `NONE` again on a
session that refuses a 64-bit condition:

| part | sections, by title |
|---|---|
| 360a sums (merging) | "DECIMAL(20, 2) x + y as a value", "DECIMAL(20, 2) x + y < z filter" |
| 360b2 | "DECIMAL(20, 2) x - w as a value, w a DECIMAL(20, 4)"; "BIGINT l + x as a value, x a DECIMAL(9, 2)"; "BIGINT l + x as a value, x a DECIMAL(18, 4)"; "DECIMAL(18, 4) cast to DECIMAL(38, 10)" |
| 360c1 | "DECIMAL(38, 2) x + y as a value" |
| 360c2 | none (no section holds a capped sum of two scales) |
| not this row | "DECIMAL(9, 2) arithmetic chain" (row 361's product under it); "DECIMAL(18, 4) arithmetic chain" (row 362); "DECIMAL(38, 10) arithmetic chain", "DECIMAL(38, 10) x + y as a value, its scale cut" (row 363); "DECIMAL(38, 10) cast to DECIMAL(18, 4)" (rows 350 and 364) |

### 11.7 The names the four rows share

One table for `VARKA-360.md`, `VARKA-361.md`, `VARKA-362.md` and `VARKA-363.md`; the other three
plans point here. A part's B0 lines grep the names it needs; a part never changes a signature
of this table after the part that creates it has merged. "Branch" in the third column means a
waiting branch the lead merges (11.1); every other entry is a part.

| name | signature | created by | used by |
|---|---|---|---|
| `VarkaVectorIR.WordConst` | `record WordConst(long value, LaneType lane)`; also `WordConst(long value)`, the long lane | 360b1 | 360b1, 361b, 362a1, 362b1, 363a1 |
| `VarkaVectorIR.WideMul` | `record WideMul(VarkaVectorIR left, VarkaVectorIR right, Word word, boolean rightOneLimb, int hash)`, public constructor without `hash` | 360b1 | 360b2, 361a, 362a1 |
| `VarkaDecimalLowering.lowerAll` | `static List<VarkaVectorIR> lowerAll(List<VarkaVectorIR> outputs, VarkaEmitOptions options, Set<PlantedFault> faults)` | 360b1 | `VarkaLoopEmitter.lowered`; 362a1 and 363a1 add their node's arm to the private `lowered` |
| `VarkaDecimalLowering.lower` | `static VarkaVectorIR lower(WideMul n, VarkaEmitOptions options, Set<PlantedFault> faults)`; the same shape for `PairMul` (362a1) and `ScaleDown` (363a1) | 360b1 | 361b, 362a1, 362b1, 363a1 |
| `VarkaDecimalLowering.productWord` | `static VarkaVectorIR productWord(VarkaEmitOptions options, VarkaVectorIR x, VarkaVectorIR y, Word word, boolean rightOneLimb, boolean xSigned, boolean ySigned, Set<PlantedFault> faults)` | 360b1 | 360b1, 361b, 362a1 |
| `VarkaDecimalLowering.laneCalls`, private `bit`, `mul`, `add`, `sub`, `MASK`, `HALF`, `SIGN` | `static int laneCalls(WideMul n)`, an overload a node | 360b1 | 362a1, 363a1 |
| `VarkaDecimalLowering.PlantedFault` | `enum`; `MIDDLE_COLUMN_ONE_WORD` (360b1), `COLUMN_ONE_ONE_WORD` (362b1), `SCALE_COLUMN_ONE_WORD` (363a1) | 360b1 | 360b1p, 361p, 362b1, 363a1 |
| `VarkaLoopEmitter.emitPlantedForTest` | `static byte[] emitPlantedForTest(String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals, VarkaEmitOptions options, Set<VarkaDecimalLowering.PlantedFault> faults)` | 360b1 | 360b1p, 361p, 362b1, 363a1 |
| `carry`, `borrow` | `private static VarkaVectorIR carry(VarkaVectorIR a, VarkaVectorIR s)` (an addend's low word and the wrapped low sum); `private static VarkaVectorIR borrow(VarkaVectorIR a, VarkaVectorIR b)` (the low words of `a - b`) | branch `varka-360a-sums` | 363a3, 363c1, 363c2, 363c3 |
| `widePair`, `atPairScale`, `uncappedSum`, `signExtended` | `signExtended(VarkaVectorIR x, DecimalType type, DeclineSink sink)` returns `Pair`; the others as on the branch | branch `varka-360a-sums` | 360b2, 360c1, 361a, 362a2 |
| `bigintPair` | `private static Optional<Pair> bigintPair(Cast c, DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink)`; its first statement the two-lane call below this table | branch `varka-360a-bigint-cast` only | 360b2, 362a2, 363a3 |
| `TWO_LANE_WIDE_REASON` | `static final String` | 360b2, unless row 597's part 597b merged first (the rule below this table); 597b removes it | 360c1, 360c2, 361a, 362a2, 362b2, 363a3, 363b2, 363c1, 363c2, 363c3 |
| `wideRescale`, `pairTimesWord`, `longTimesTwoWords` | `(VarkaVectorIR x, int k, DecimalType type, DeclineSink sink)`; `pairTimesWord(Pair x, int k, DecimalType type, DeclineSink sink)`, `1 <= k <= 18` (the old branch's `pairRescale`, renamed) | 360b2 | 360c2, 363c1, 363c3 |
| `decimalOverflow`, `evalModeOf`, `outside38`, `guardedPair` | `static Overflow decimalOverflow(Object evalMode)`, `static Object evalModeOf(Expression e)`, `static Cond outside38(VarkaVectorIR lo, VarkaVectorIR hi, DeclineSink sink)`, `static Pair guardedPair(VarkaVectorIR over, VarkaVectorIR lo, VarkaVectorIR hi, Overflow mode, DecimalType type)`; bodies in 11.4, "The overflow mode of the whole chain" | 360c1 | 362b2, 363b2, 363c1, 363c3 |
| `cappedKeepingScale` | `private static boolean cappedKeepingScale(Expression e)` | 360c1 | 360c2, 361a |
| `rescaleGuard` | `static Pair rescaleGuard(Pair x, int k, DeclineSink sink)` | 360c2 | 363c3 |
| `longValued`, `pairWidening`, `longOperand`, `oneLimb`, `wideningProduct` | `private static boolean longValued(Expression e)`, `private static boolean pairWidening(Expression e)`, `wideningProduct(Multiply m, DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink)` | 361a | 362a2 |
| options `decimalWideMulLow`, `decimalSignWord` | `enum DecimalWideMulLow { LIMBS, MULVL }`, `enum DecimalSignWord { ASHR, NEG_LSHR, BLEND }` | 361b | read by `lower(WideMul)` and `productWord` |
| `VarkaVectorIR.PairMul` | `record PairMul(VarkaVectorIR xLo, VarkaVectorIR xHi, VarkaVectorIR yLo, VarkaVectorIR yHi, int words, int word, boolean rightLong, int hash)`; `words = 4` from 362b1; a `Sign sign` component (`TWOS`, `UNSIGNED`) from 363b1 | 362a1 | 362a2, 362b2, 363b2 |
| `ProductForm`, `pairShortcut`, `pairProduct`, `productWords`, `signExtension` | `signExtension(Pair v, DeclineSink sink)`; `pairProduct(Multiply m, DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink)` | 362a2 | 362b2, 363a3, 363b2 |
| `VarkaVectorIR.ScaleDown` | `record ScaleDown(List<VarkaVectorIR> magnitude, int k, BigInteger bound, ScaleRound round, int word, int hash)` | 363a1 | 363a3, 363b2, 363c1, 363c2, 363c3 |
| `Cut`, `rounded`, `negatedWhere` | `static Pair rounded(VarkaVectorIR lo, VarkaVectorIR hi, VarkaVectorIR sign, Cut cut, DecimalType type, DeclineSink sink)`; `negatedWhere(VarkaVectorIR lo, VarkaVectorIR hi, VarkaVectorIR sign, DecimalType type, DeclineSink sink)` | 363a3 | 363b2, 363c1, 363c2, 363c3 |
| `VarkaEmitBudget.LONG_LANE_MAX_CHAIN_DEPTH`, option `chainCapLongBits` | `public static final int ... = 64` | row 697 (#1798, branch `varka-long-lane-chain-cap`) | 360c2, 362b2, 363a3, 363b2, 363c1, 363c2, 363c3 |
| `VarkaDecimalPairSuite`'s harness | section 3, "Harness names"; `storedOrDeclined` (360c1), `DEEP` (360b2) | 361a, 360c1, 360b2 | 360c2, 361a, 362a2 |

Emit options of this row: none; `decimalCarry` exists only if 360e is built.

**The two-lane rule once row 597's part 597b merges** *[28 November 2026: the owner answered
`VARKA-597.md` 11.7 question 1 "remove"]*. 597b writes the two-lane decline once, in the
decimal readers (`VarkaDecimalCompiler.TWO_LANE_READ_REASON`), and removes
`TWO_LANE_WIDE_REASON` with every arm's two-lane statement that is on master when it is built
(`VARKA-597.md` 11.5 step 3b). Each part of rows 360 to 363 first reads
`grep -c 'TWO_LANE_READ_REASON' C/VarkaDecimalCompiler.java`. `0`: it builds as its sheet
writes. `1` or more: it adds no `TWO_LANE_WIDE_REASON` and no two-lane statement in an arm; a
B0 line that counts `TWO_LANE_WIDE_REASON` is read as `0`, and one that counts
`declinesTwoLaneChecks` in `DC` as `1`; a test that expects `TWO_LANE_WIDE_REASON` expects
`VarkaDecimalCompiler.TWO_LANE_READ_REASON` under the same admission, and the sheet's test
counts stand. The table above names who adds the constant; after 597b no part does.

**`bigintPair`'s two-lane call** (the owner: it declines with the readers). If 597b is on master
when the lead merges master into `varka-360a-bigint-cast` (step 0 of 11.2's chain), the lead
adds `if (declinesAtTwoLanes(c, sink)) { return Optional.empty(); }` as `bigintPair`'s first
statement, and `Cast(l, DecimalType(20, 0))` to the `pair` list of `VarkaExpressionCompilerSuite`'s
test "a value that reads a decimal column declines on a two-lane host, and fuses elsewhere";
360b2's B0 checks it and adds both where that merge did not. If `bigintPair` reaches master
first, 597b adds them (`VARKA-597.md` 11.5 step 2).

Section 10.5 item 6, the branch's record kept as it will merge, says row 597 moved to
milestone 10. That is stale since the owner's word of 28 November 2026: part 597b is milestone
8's and gates its close, and 597a stays in `m10/SCOPE.md` item 140. The case it names, a
comparison of pairs built from `BIGINT` casts alone projected as a `BOOLEAN`, declines at two
lanes once `bigintPair` has the call above.

### 11.8 Risks

1. **The re-port of 360b1 misses a registry.** Held by `dev/varka_push_check.py`'s rule R1 and
   the eleven suites of its sheet.
2. **A lowered `WideMul` changes an existing shape's bytes** through the hook (a list copied in
   another order). Held by the empty `git status` after the regeneration in 360b1.
3. **Class K's null leaks through a validity reader** (360c1). Held by its tests 2 and 4.
4. **A reason text is reworded and a test elsewhere still holds the old one.** Held by the grep
   each reason table asks for, and by the sql/core suites of the commands.
5. **A pair rescale costs more than the row path** at 256 bits: about 30 lane calls a lane
   group beside two rearranges. Held by 360d: a section under 1.3x is named, not hidden.

### 11.9 What only a JVM can settle, and the command for each

1. That 360b1 compiles and the registries are complete: `build/sbt 'catalyst/Test/compile'` and
   its sheet's suites.
2. That C2 selects `vpmuludq` for every limb product with the multiplier in a literal slot:
   `dev/varka_emit.sh "CAST(x AS DECIMAL(38, 10))" --columns x:decimal(18,4) --asm` after 360b2
   must show two `vpmuludq` in the dense loop and no `vpmullq`; with `--columns
   x:decimal(18,0)` and `DECIMAL(38, 12)`, four. Row 361's 361c records it.
3. That a `NULL` guard over a `CondValue` nulls both words in the masked body, the dense body
   and with `validityByBitmap` off: 360c1's tests 1, 2 and 9.
4. The solver times of the new proof layers: `dev/varka_prove.sh --solver both
   decimal128.smt2`.
5. The real counted size of each part, and every number of 360d.
6. Whether the emitter's heights equal 11.3's table: `dev/varka_emit.sh` prints a shape's
   methods; a shape that declines with "chain deeper than MAX_CHAIN_DEPTH" where the table says
   it fits is a finding against this plan. The forms the model puts at 15 and 16
   (`abs(x - y)` over two `DECIMAL(38, 2)` columns is one) are the ones to read first.
7. Whether a compile that stops at the cap reports the text `chain deeper than
   MAX_CHAIN_DEPTH=16` word for word in its decline (rows 362 and 363 assert that fragment):
   their cap tests.
8. *[Fix round 3.]* Whether a wide shape of 11.5 passes 64 distinct ops, so that `DEEP`'s
   byte-budget pin matters for it (11.3 decision 5; the pin makes the answer not change any
   test): on a scratch branch with `DEEP` set to `VarkaMatrixRuns.BASE.withMaxChainDepth(64)`
   alone, `dev/varka_matrix.sh --config methodByteBudget=0 --suites VarkaDecimalPairSuite` (and
   `--config cse=false`); a test that fails with "exceeds the emitter's fused budget" or the
   method budget names such a shape.

## 12. Decisions for the owner (28 November 2026)

*New section.* The four plans of the decimal chain number their owner decisions together: D1 to
D3 here, D4 in `VARKA-361.md` section 12, D5 to D7 in `VARKA-362.md` 11.9, D8 in `VARKA-363.md`
11.9. Each is written to be answered without reading the plan.

- **D1. The carry of a 128-bit sum: one form, or two behind an option.** Answered by the owner
  on 28 November 2026: (a). The shape: `x + y` over two `DECIMAL(20, 2)` columns. Each value is
  two 64-bit words; the high words' sum adds 1 where the low words' sum wrapped, and that 1 can
  be computed by one unsigned compare (what is built today) or by five bit operations and a
  shift. Part 360s measures both on the EPYC 7763 runner. (a) If the second form is ahead by
  1.3x in at least one of nine cells and behind in none, both forms are kept behind an emit
  option `decimalCarry`, about 600 counted lines and one part of the lead's; if not, nothing is
  built and there is no option. (b) In the first case swap the two helpers, about 30 lines, and
  drop the slower form. Recommended and applied: (a), the house keeps a losing arm.
- **D2. A capped sum whose wide operand is rescaled by 19 digits or more.** Answered by the owner
  on 28 November 2026: (a), as applied. The shape: `x + y` with `x` a `DECIMAL(38, 0)` and `y` a
  `DECIMAL(38, 30)` and `spark.sql.decimalOperations.allowPrecisionLoss=false`, which is not the
  default. Spark types the sum `DECIMAL(38, 30)`, so `x` is multiplied by `10^30` before the add.
  (a) Varka declines the expression by name and the query runs on Spark's row path, as it does
  today: 0 lines; 61600 of the 606841 ordered type pairs under that setting and none under the
  default setting; recorded in `m10/SCOPE.md` item 140 and named in the census note of `+` and
  `-`. (b) Build it in part 360c2: about 80 counted lines and one proof claim. Recommended: (a).
- **D3. Starting the build before the carry's measurement is committed.** Answered by the owner on
  28 November 2026: (a), as applied. Section 9 of this plan says the spike's verdict is committed
  for each vector width before the build. (a) The two parts the lead builds this week, 360b1 (the
  multiply node) and 360c1 (the capped sum), start before part 360s has merged: neither reads the
  carry form, and a verdict for the second form adds one part (D1) and changes neither. (b) They
  wait for 360s, which waits for the lead's download of two runner artifacts; the whole chain of
  21 steps starts that much later. Recommended: (a).

Two former questions are decided in the text and are not the owner's: class U at one scale at
two long lanes stays with row 597's second half (11.3 decision 4), and the order of 360b2 and
360c1 is fixed (11.2).

## 13. The house rules that bind every part of section 11

*New section.*

Read them where they are written; do not work from this list alone. Answers equal Spark's bit
for bit under both ANSI settings, and a decline is the answer wherever that is not shown
(`sql/varka/AGENTS.md`, "House rules that bite here specifically", the ghost fallback). A failing
test is never weakened (the root `AGENTS.md`). A new node runs every registry suite
(`VARKA-489.md`, "Adding a node, after 489c"; `dev/varka_push_check.py` R1). A new bounded
lowering ships its proof file (`sql/varka/AGENTS.md`, VARKA-242). Generated files are regenerated,
never edited (`sql/varka/AGENTS.md`, its opening section). A test starts from
`VarkaMatrixRuns.BASE` (Java) or `VarkaMatrix.base` (Scala), not from
`VarkaEmitOptions.DEFAULTS`, and one that pins `maxChainDepth` says why in a comment
(`sql/varka/AGENTS.md`, "Measurements, not adjectives", the option matrix). A pull request stays
under 1000 counted lines and is reviewed by its author first (`sql/varka/AGENTS.md`, "Pull
requests"). No `TODO`; a comment describes the code as it is.

## 14. Stale against the tree, found while planning (28 November 2026)

*New section.*

1. Section 2.7 and prediction 4 hold that a class U projection with `BITS` is mask-free at 128
   bits; 10.1 refuted it, and 11.3 decision 4 replaces the rule.
2. Section 5 item 6 and test 7 of section 3 say "after 359b's store": #1710 merged it, and every
   pair form is an output the day it is built (10.3). 360d keeps only the measurement.
3. Section 2.4 gives a pair operand `k <= 18` and a 64-bit one "two [limbs] up to `k = 18`, and
   above that the constant's second word's low product added into the high word": right, and
   11.4 writes that last form out (`longTimesTwoWords`); the old branch declined it.
4. Section 2.1 and 2.8 put `decimalCarry` and `decimalSignWord` in this row unconditionally:
   11.3 decisions 1 and 8.
5. `VarkaDecimalCompiler.valid`'s reason "the validity of a cast to a decimal from a bigint is
   row 360's next part" names a part that does not build it: 360b2 rewords it and pins it.
6. The milestone's chain cap: `m8/PLAN.md` row 620 says the cap of 16 "blocks rows 362b, 363,
   363b, 364 and 366"; of this row only class K over a rescaled operand is (11.3
   decision 5), and row 697 lifts it.
7. `/home/max/varka-ops/m8-triage/decimal-chain.md` (27 November) has 360a on `varka-360a-r2`
   with a conflict in `valid()`: the three 360a branches of 28 November resolved it, and
   `varka-360a-r2` is dead. Its part A5 "needs 481's `NULL` mode": merged, `GuardedRange` has
   the mode on master.

