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
reason if it loses (`m9/SCOPE.md` item 101, as `VARKA-359.md` 2.6).

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
