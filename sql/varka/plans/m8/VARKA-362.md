# VARKA-362: The full decimal multiply, 128 by 128 into 256 bits

## 0. What exists now (admission check)

From master at `7ea5b6242c7` for Spark's sources and the plans, rows 360 and 361's plans on their
unmerged branch `varka-360-361-plan` at `40adc9359f4`, rows 350 and 366's on `varka-350-366-plan`
at `6981bde9576`, row 359's plan (`VARKA-359.md`, merged) and the JDK in
`/home/max/proj/openjdk-build/jdk25` (30 October 2026). Spark paths are under
`sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/` unless named, `DecimalType.scala` and
`Decimal.scala` under `sql/api/src/main/scala/org/apache/spark/sql/types/`. The row's text is the
milestone plan's 2.8 ("362, the full multiply"), its 10.3 item 2 and waves F3 and G3 of its 4.1.

The type counts below are every ordered pair of the 779 decimal types with `1 <= p <= 38` and
`0 <= s <= p`, 606841 pairs, classed by a script (`results/VARKA-363-shapes.txt`).

1. **Spark's result type, read per expression.** `Multiply` over two decimals takes scale
   `S = s1 + s2` and precision `P = p1 + p2 + 1`, through `DecimalType.adjustPrecisionScale` when
   precision loss is allowed and `DecimalType.bounded` when not (`expressions/arithmetic.scala:
   630-638`). The setting is the node's own `NumericEvalContext.allowDecimalPrecisionLoss`
   (`expressions/arithmetic.scala:244`, `expressions/NumericEvalContext.scala:38-50`), fixed when
   the node is built, so the compiler reads the result type from the node's `dataType` and never
   from the session (Comet's bug #4124, `READING_ARROW_RS.md` 2). `adjustPrecisionScale` is the
   identity up to 38 and above it gives `(38, max(38 - (P - S), min(S, 6)))`, 6 being
   `MINIMUM_ADJUSTED_SCALE` (`DecimalType.scala:121`, `:175-199`); `bounded` gives
   `(min(P, 38), min(S, 38))` (`:144-146`).
2. **The product and its check.** `Decimal.*` multiplies two `BigDecimal`s under
   `MathContext(39, DOWN)` (`Decimal.scala:523-524`, `:597`), exact for a product of 39 digits or
   fewer; then `checkDecimalOverflow` and the generated code call `changePrecision` with
   `ROUND_HALF_UP` (`expressions/arithmetic.scala:273-275`, `:294-310`; `Decimal.scala:387-470`),
   which sets the result scale and fails where the rounded value has more than `p` digits
   (`:463`, `:470`). Where the result scale is `s1 + s2` nothing rounds and the check is the
   precision alone; that is this row's domain. A cut scale is row 363's.
3. **The three modes.** `changePrecisionExact` under ANSI and TRY, `changePrecisionOrNull` under
   LEGACY (`expressions/arithmetic.scala:294-310`, `expressions/CastUtils.java:107-115`); TRY
   catches to null (`expressions/arithmetic.scala:356-374`, interpreted `:376-397`), and
   `try_multiply` over numerics is `Multiply(l, r, EvalMode.TRY)` (`expressions/TryEval.scala:
   268-274`). ANSI raises `NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION` with the value, precision,
   scale and the ANSI key (`errors/QueryExecutionErrors.scala:106-120`,
   `common/utils/src/main/resources/error/error-conditions.json:6638-6655`). So LEGACY and TRY
   null and ANSI raises, each only where the product has more than `38 - S` integral digits.
4. **Where the product needs 256 bits, and where the check is live.** With `|x| <= 10^p1 - 1` and
   `|y| <= 10^p2 - 1`, `|x * y| <= (10^p1 - 1)(10^p2 - 1) = 10^(p1 + p2) - 10^p1 - 10^p2 + 1`.
   That is below `10^38 - 1`, and so below `2^127`, for every `p1 + p2 <= 38`, and the bound at
   `p1 + p2 = 39` passes `2^127` (`results/VARKA-362-shortcut.txt`; `(10^19 - 1)^2` is
   `10^38 - 2 * 10^19 + 1`). So:
   - **`p1 + p2 <= 37` (`P <= 38`)**: uncapped, the exact product fits its type and 128 bits; the
     check is dead in every mode. This is the row's static shortcut.
   - **`p1 + p2 = 38` (`P = 39`)**: capped to `(38, S)` when `S <= 6` (scale kept) and to
     `(38, S - 1)` otherwise (cut by one, 363's). The product is still below `10^38`, so the
     kept shapes fit 128 bits with the check dead, one step past the row's shortcut: 966 pairs with
     an operand above 18 digits, beside 9616 cut ones whose dividend 363 gets in 128 bits.
   - **`p1 + p2 >= 39` (`P >= 40`)**: the product needs up to 253 bits, and where the scale is kept
     the `10^38` check is live.
5. **The shapes this row owns.** Of the 606841 pairs under `allowPrecisionLoss = true`: 5644 are
   64-bit (349's), 30077 have both operands within 18 digits and a kept scale (361's), 64068 have an
   operand above 18 digits and `P <= 38` (the shortcut, this row), 21574 are capped with the scale
   kept (this row, 966 of them at `P = 39`), and 485478 cut the scale (363's). Under
   `allowPrecisionLoss = false` the kept capped shapes are 405782 and the cut ones 101270: `bounded`
   cuts the scale to 38 wherever `s1 + s2 > 38`, so the setting false is not "the exact type" at
   every pair (`results/VARKA-363-shapes.txt`; 363 takes those). An integral operand under its
   `(20, 0)` coercion cast is a 64-bit value whatever its precision says, and 361 keeps it so.
6. **The vector multiply, as C2 selects it.** `MUL` on a `LongVector` keeps the low 64 bits; there
   is no multiply-high on any lane type (the milestone plan's 2.8). A `MulVL` becomes one
   `vpmuludq` (cost 100) only where `MulVLNode::has_uint_inputs` holds, both inputs an `AndV` with
   a replicated constant at most `0xFFFFFFFF` or a `URShiftVL` by a constant of 32 or more
   (`src/hotspot/share/opto/vectornode.cpp:2189-2237`, `src/hotspot/cpu/x86/x86.ad:6096-6106`);
   otherwise `evpmullq` on AVX-512DQ or the emulation `vmulL_reg`, both cost 500. The constraints
   row 361's plan draws from this (branch `varka-360-361-plan`, its 0 item 7) hold here unchanged:
   every limb product unsigned with the sign handled apart, masks and shift counts as emitter
   constants, a zero constant limb dropped and a `0xFFFFFFFF` one at risk of GVN folding the
   pattern away. A lanewise leading-zero count on longs is one `vplzcntq` with AVX-512CD and a
   multi-instruction sequence with three vector temporaries and a general register on AVX2
   (`x86.ad:1905-1909`, `:2077-2080`, `:9634-9648`).
7. **The scalar products.** `Math.multiplyHigh` and `unsignedMultiplyHigh` are C2 intrinsics,
   `imulq` and `mulq` on x86_64 (`src/hotspot/share/classfile/vmIntrinsics.hpp:181-182`,
   `src/hotspot/share/opto/library_call.cpp:2101-2109`, `src/hotspot/cpu/x86/x86_64.ad:8715`,
   `:8728`), never vectorized; a 128 by 128 product is four of each pair plus the sign
   correction, the S arm of `VARKA-359.md` 2.1.
8. **What the prerequisites give.** 359 plans the pair as two long words in a compile-time
   `Pair(lo, hi, type)`, the unsigned compares `ULT` to `UGE`, negation and `abs` with their
   borrow, and the `10^p` check `gt128` per lane group (`VARKA-359.md` 2.2, 2.4, 2.5). Row 360b
   lands `WideMul(left, right, Word word, boolean rightOneLimb)` with constant multipliers, row 361a
   adds the column-by-column 64 by 64 product to it, one node per output word with the network
   parked once (row 361's plan, 2.2 and 2.3; row 360's, 5). Row 481's `GuardedRange` `NULL` mode
   nulls a lane, `FAIL` declines the batch (`VARKA-481.md` 3.1), and row 360c disposes class K's
   `10^38` check through it (row 360's plan, 2.5).
9. **The bounds are SMT lemmas.** `VarkaRangeAnalysis` is int-only, so "no intermediate passes
   `2^64`" is proven, not analysed (the milestone plan's 2.8, "Where the bounds live").
10. **The baseline reaches this row once, inside a chain.** `VarkaDecimalBaselineBenchmark`'s
    `(38, 10)` chain `(x + y) * z - x` costs the row path 1188.8 ns a row on the EPYC 7763
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`), but
    its product, `(38, 9) * (38, 10)`, cuts the scale and is 363's; no section multiplies a pair
    with the scale kept.
11. **359's spike holds this row's main loop.** Its loop 2 is the 128 by 128 product into 256
    bits, P by sixteen `vpmuludq` and S by the scalar products, with the product's `10^38` check
    (`VARKA-359.md` 2.1); it has not run, so no verdict exists to take a form from.

### What differs from the row text and the milestone plan

- **The shortcut reaches one step further than the row, a departure from its text** (item 4): the
  product fits 128 bits with the check dead for every `p1 + p2 <= 38`, `P <= 39`, where the row says
  `p1 + p2 + 1 <= 38`. The compiler uses the sharp bound (decided by the lead on 30 October 2026,
  from `results/VARKA-362-shortcut.txt`): 966 more kept-scale pairs take N, and 363 gets a 128-bit
  dividend for 9616 cut ones. The SMT line the row asks for states all three bounds, Velox's
  `P < 38`, the row's `P <= 38` and the sharp `P <= 39`.
- **The planted carry fault is live for every sign pair at 128 bits.** Row 361's plan found it
  live for one negative operand as well as two at 64 bits and never for two non-negative ones
  (its 0, "What differs"). Over 128-bit operands a non-negative value's three low limbs take any
  value, so two limb products of one column pass `2^64` for two non-negative operands too: in
  500000 draws the planted column-1 sum is wrong for 9117 non-negative pairs, 18211 with one
  negative operand and 9103 with two, in the two's complement form
  (`results/VARKA-362-planted-carry.txt`). The row's named draw `(2^64 - 1) * (2^34 - 1)` detects
  the column-1 plant, and `2^112 * 2^112`, one limb product, detects no carry plant: it is the
  test that the product's top word is kept, QuestDB's second defect (`READING_QUESTDB.md` 9,
  item 2). Row 362's text names no sign class, so nothing in it changes; test 3 states the
  finding.
- **Under `allowPrecisionLoss = false` some products cut the scale** (item 5): `s1 + s2 > 38`
  rounds to scale 38, 101270 pairs, which are 363's and decline here naming it.
- **A pair times a 64-bit value is this row's** (row 361's plan, 7), as 4 by 2 limbs.
- **A node of its own, not 360b's grown** (2.2, decided by the lead on 30 October 2026): 360b and
  361a land `WideMul` first and own it; this row shares their partial-product helper.

## 1. Goal and non-goal

`x * y` and `try_multiply(x, y)` for two decimals, at least one a lane pair, whose resolved result
scale is `s1 + s2`: the exact product as the lane pair where it fits 128 bits (the shortcut), and
otherwise the exact 256-bit product by a column sum of 32-bit limb products, compared with `10^38`
at full width before narrowing, nulling or declining by mode. The forms are measured against each
other and against the row engine first, and the product is declined with its measured reason only
if it loses (owner's decision A2). Not this row: a cut scale (363, which takes this row's 256-bit
words as its dividend), two 64-bit-valued operands (361), a product of precision 18 or less
(349), the pair's load and store (359), decimal division (out of the milestone).

## 2. Design

### 2.1 The forms, and the measurement that picks

The forms below come from 359's spike and stay option arms until it has run; the verdict per
width and host names each default, and the losing arms stay behind their options, as the house
rule has it.

- **N, the narrow product (the shortcut).** The low 128 bits of the product of the two words'
  patterns, which is the product itself when it fits: `lo = (x.lo * y.lo) mod 2^64`,
  `hi = umulhi(x.lo, y.lo) + x.lo * y.hi + x.hi * y.lo` modulo `2^64`. The unsigned 64 by 64 high
  word is row 361's network without its sign correction (four `vpmuludq`); each cross term's low
  word is three `vpmuludq` (`l * l + ((l * h + h * l) << 32)`) or one `MulVL`, row 361's option
  `decimalWideMulLow`. Ten `vpmuludq`, no compare, no sign word: the two's complement product
  modulo `2^128` is exact because the true product fits.
- **C, the column sum.** Four 32-bit limbs per operand, sixteen `vpmuludq`; each product's low
  half added to column `i + j` and its high half to column `i + j + 1`, the carries propagated once
  at the end (`acc[c + 1] += acc[c] >>> 32`), the four result words assembled from the columns'
  low halves. A column holds at most four low halves, four high halves, its carry and the sign
  addends below, and the widest seen in the replay is 35 bits
  (`results/VARKA-362-planted-carry.txt`), so no column wraps. No compare, no Karatsuba.
- **The sign, two ways** (option `decimalWideSign`, `TWOS` or `MAGNITUDE`, `Reason.MACHINE`):
  - `TWOS`: the operands' patterns multiplied unsigned, and `X * Y = Xu * Yu - 2^128 ([X < 0] Yu +
    [Y < 0] Xu)` modulo `2^256` folded into columns 4 to 7 as the limbs of `~Yu` masked by `x`'s
    sign word and of `~Xu` by `y`'s, plus each sign's `1` in column 4. Exact because
    `|X * Y| <= 2^254`. Eight more limb addends, no borrow chain.
  - `MAGNITUDE`: `|x|` and `|y|` by 359's `abs` (its borrow), the unsigned product, and the sign
    restored on the 256-bit result by a negation with a four-word borrow. Dearer here; cheaper for
    363, whose division wants a magnitude anyway (row 363's plan, 2.3).
  The sign word is row 361's `decimalSignWord` (`ASHR`, `NEG_LSHR`, `BLEND`).
- **128 by 64.** A pair times a 64-bit value (348's `Scaled`, or an integral under its cast) is
  4 by 2 limbs, eight `vpmuludq`, the sign folded as above over the 64-bit operand's two limbs;
  under the shortcut it is N with `y.hi` the sign word.
- **G, the gate (Velox's leading-zero test, an option arm).** Velox sends a row to its 256-bit
  path only when the operands' leading zeros sum to 128 or fewer (`READING_DECIMAL_ENGINES.md` 2,
  item 1). In lanes, a per-lane-group test chooses N where every lane's product fits 128 bits and
  C otherwise, taken when the test's mask is all true, as row 366's arm is taken per batch
  (`varka-350-366-plan`, its 2). Two tests: `LZ`, `clz(|x|) + clz(|y|) >= 130` over 128 bits, one
  instruction per word on AVX-512CD and an emulation on AVX2 (item 0.6); and `HIGH`, both
  magnitudes below `2^64`, which sends the group to row 361's unsigned four-product network and the
  check on its 128-bit magnitude. Option `decimalWideGate`
  (`NONE`, `LZ`, `HIGH`). The gate never decides an answer: N and C agree wherever N is chosen.
- **S, the scalar loop**: four `multiplyHigh` and `unsignedMultiplyHigh` pairs per row, the
  reference arm under 359's answered question 3 (a), measured, not emitted.

**The spike** is loops added to 359's `VarkaDecimalPairSpikeBenchmark` beside rows 360 and 361's,
each checked against `BigInteger` and driven to level-4 code, over 359's L2, L3 and past-L3 ladder,
at 512, 256 and 128 bits on the laptop (its 512-bit numbers double-pumped and labelled so) and 256
and 128 on the EPYC 7763 through `benchmark.yml`:

1. 359's loop 2, the `(38, 2) * (38, 2)` product with its `10^38` check, in C under both signs, G
   in its three settings over columns of small and of full-width values, and S;
2. the shortcut, `(20, 2) * (17, 2)`, in N with both `decimalWideMulLow` arms, and in C, to price
   what the shortcut saves;
3. 128 by 64, `(20, 2) * l` with `l` a `BIGINT`, in its eight-product form and in C.

### 2.2 The node

    PairMul(VarkaVectorIR xLo, VarkaVectorIR xHi, VarkaVectorIR yLo, VarkaVectorIR yHi,
            int words, int word, Sign sign)

a node beside 360b's `WideMul`, not that node grown: 360b and 361a land `WideMul` first and own it,
and this node's sixteen-product lowering, budget weight and evaluator path differ from its four.
What the two share is the emitter's partial-product helper - the limb split with the masks and
shift counts as emitted constants, the unsigned limb product and the half split - which 361a
writes for `WideMul` and this row calls, so there is one copy of the code C2's `vpmuludq` rule
constrains. `xHi` or `yHi` is empty for a 64-bit operand, whose sign word is computed inside;
`words` is 2 for N and 4 for C, `word` the output word, rendering `pmul<words>:<word>(...)`. The
words of one product share one network: the first word a body emits computes all of them and
parks the rest in a slot keyed by the children, which its siblings load, as 361's two words do; a
budget split recomputes it per group. The reference
evaluator answers the words of the `BigInteger` product; the IR fuzzer draws it in a sequence of
its own seed over every 128-bit pattern, since the network is exact for every pattern pair and not
only for values below `10^38`; `VarkaCanonicalIr` gains its parse arm.

### 2.3 The compiler

`VarkaDecimalCompiler`'s `Multiply` arm (row 349's, with 361's case) gains, ahead of its decline
naming 362, the pairs where an operand is a lane pair or a 64-bit value meets a pair, and the
resolved scale is `s1 + s2`:

| condition | form | check |
|---|---|---|
| `p1 + p2 <= 38` | N (2 words), or 128 by 64 under N | none: dead, item 0.4 |
| `p1 + p2 >= 39`, scale kept | C (4 words), G before it | `10^38` at full width (2.4) |
| scale cut | declines naming 363 | 363's |

A widening cast under a multiply is not widened: 359's `stripWidening` and row 360's 2.6 leave
the narrower operand at its own width, as StarRocks strips the casts before its narrow product
(`READING_DECIMAL_ENGINES.md` 2, item 5), so `d18 * d38` is 128 by 64 and not 128 by 128.

### 2.4 The check at full width, and its disposal

A product that may pass `10^38` is checked on all four words before any is dropped: arrow-rs's
unchecked multiply keeps the low 128 bits, and a product near `10^61` wraps into range and passes
(`READING_ARROW_RS.md` 3, item 1). Under `TWOS` the product fits exactly where `w2` and `w3` both
equal `w1`'s sign word and 359's `gt128` pair of compares holds `(w0, w1)` within
`+-(10^38 - 1)`; under `MAGNITUDE`, where `w2 | w3` is zero and `(w0, w1)` is at most
`10^38 - 1` unsigned, before the sign goes back. The disposal is row 360c's, from row 481's node:
`GuardedRange(CondValue(ovf), 0, 0, mode)` added into both kept words, `NULL` under LEGACY and TRY
and `FAIL` under ANSI, whose declined batch the row engine raises on (row 360's plan, 2.5). LEGACY
and TRY share a kernel; ANSI has its own.

### 2.5 At 128 bits, and on aarch64

N and C are lanewise arithmetic with no mask, so the two-lane fallback that sinks the pair's
compares on x86 (`VARKA-359.md` 0, item 9) does not reach the product **[I]**; the full-width check,
the gate and the `MAGNITUDE` sign do carry masks. A condition root declines at two long lanes on
x86 by row 503's rule; a projection fuses where the spike's 128-bit column says it beats the row
path, and declines with its measured reason otherwise (`VARKA-359.md` 2.6). On aarch64 each limb
product is a scalar multiply under NEON and one `sve_mul` under SVE (row 361's plan, 0 item 7), so
C's sixteen products cost what S's eight scalar multiplies do or more; the Neoverse N2 reading is
the census cells only, `benchmark.yml` having no Arm runner.

### 2.6 Compiler, reference, cost, warm-up

`try_multiply` is the same node. The budget weighs the node by its lane calls, one weight per
word with the shared network counted once (about 120 lane calls for C as 2.1 describes it:
sixteen products, eight limb splits, 32 half splits, 32 adds, the carry pass and the assembly;
written down by `dev/varka_emit.sh` when built); the cost model gets a feature on row 352's
awaiting-refit list; the warm-up's long block gains N and C, held by `VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The product table**, against `BigInteger`, at 2, 4 and 8 long lanes, lengths 1, 3, 37 and 64,
   both bodies: `+-(10^p - 1)` at `p = 19, 20, 30, 38` against `+-(10^q - 1)` at `q = 1, 17, 18,
   19, 38` in every sign pair; limbs of 0, 1, `2^31` and `2^32 - 1` in each position; the shortcut's
   edge `(10^19 - 1)^2` at `p1 + p2 = 38`; every null pattern.
2. **The random cross-check** of every arm (N, C under both signs, 128 by 64, each gate) against
   `BigInteger`: at the IR level over every 128-bit pattern, arrow-rs's 29 carry corners crossed
   (`READING_ARROW_RS.md` 3, item 4) and Trino's 128-bit products, `(10^38 - 1)^2` with its
   eight expected limbs among them (`READING_TRINO_DECIMAL.md` 10, item 2); at the SQL level within
   `10^38 - 1`. Named draws: `(2^64 - 1) * (2^34 - 1)` and `2^112 * 2^112` as the row asks, and a
   witness of the planted fault from each sign class of `results/VARKA-362-planted-carry.txt`.
3. **The planted fault** for row 504, under a test flag: column 1 summed as `x0 * y1 + x1 * y0` in
   one 64-bit word before its halves are taken, QuestDB's `a0 * m1 + a1 * m0`
   (`READING_QUESTDB.md` 9): refuted by the carry layer as row 506 renders it and failed by test 2.
   At 128 bits the fault is live for every sign pair, two non-negative operands included (9117,
   18211 and 9103 wrong of 500000 draws for none, one and two negative operands,
   `results/VARKA-362-planted-carry.txt`), unlike 64 bits, where row 361's plan found it never live
   for two non-negative ones; so test 2's witnesses include one of each class. The row's two named
   draws test different things: `(2^64 - 1) * (2^34 - 1)` catches the carry of column 1, and
   `2^112 * 2^112`, a single limb product, only checks that the product's top word is kept.
4. **The type rule against Spark's own**: `Multiply.resultDecimalType` over every `(p1, s1, p2,
   s2)` under both settings holds the compiler's form to 2.3's table, naming 349, 361 or 363 where
   it declines, and its counts to `results/VARKA-363-shapes.txt`. A pure function, under a second.
5. **One kernel where it should be**: `VarkaEmittedBytesSuite` holds one shape hash for a shortcut
   row under LEGACY, ANSI and TRY, and for a checked row one for LEGACY and TRY and another for
   ANSI.
6. **Named tests**: spark-rapids' opposite-sign product overflow,
   `577694938495380589068894346.7625198736 * -1258508260891400005608241690.15647`, whose
   type rounds and so declines here naming 363 (`READING_RAPIDS.md` 3, item 1), and Polars's grid,
   scales 0, 1, 6, 18, 37 and 38 cubed, against Spark's `Decimal` (`m7/READING_POLARS.md` 7), the
   kept-scale cells here and the cut ones with 363.
7. **The SMT proofs**, a section of 359's `sql/varka/proofs/decimal128.smt2` under Z3 and cvc5,
   over integers in 2.8's two layers at full width. The ring layer: with each operand the sum of
   its four limbs in `[0, 2^32)` weighted by `2^(32 i)`, the sixteen products' weighted sum is
   `Xu * Yu`, a polynomial identity; a solver that answers `unknown` on it fails the run, so the
   identity is also stated row by row, `Xu * y_j` for each limb `y_j`, which the proof file tries
   first if the whole is slow. The carry layer, linear: every limb product free in
   `[0, (2^32 - 1)^2]` and split into halves by division and remainder by `2^32`, the column sums
   and the one carry pass as emitted give the four words of the weighted sum with no column past
   `2^64`; N's two words equal the product modulo `2^128`; the `TWOS` addends, stated with
   `ite(x < 0, ~Yu, 0)`, give `X * Y` modulo `2^256`; the full-width check holds exactly when
   `|X * Y| <= 10^38 - 1`. The shortcut line: for each `(p1, p2)` the ground bound
   `(10^p1 - 1)(10^p2 - 1)` against `2^127` and `10^38 - 1`, below both up to `p1 + p2 = 38` and
   past `2^127` at 39, with Velox's `P < 38` and the row's `P <= 38` as named weaker lines; the step
   from the operands' bounds to the product's is the one nonlinear lemma, stated by sign case. The
   planted order as a `sat` witness; a 16-bit-limb version as the reduced-width cross-check only.
   The file's times stated before and after.
8. **Coverage rows**, a "DECIMAL full multiply" family: `d20 * d17s2` (the shortcut),
   `d19 * d19b` (`P = 39`, scale kept, the sharp shortcut), `d20 * l` (128 by 64, checked),
   `d38s2 * d38s2b` (checked), `d18 * d38` (the cast stripped), `try_multiply(d38s2, d38s2b)`, as
   filters now and projections after 359b's store, each naming its verification for row 490's
   check; the differential, the matrix and both fuzzers; the generated files in
   `ADDING_A_TYPE.md`'s order, no existing shape's bytes moving.

The sweeps - the random `BigInteger` cross-check of every arm at volume and the type sweep - run
by hand in the gate, as the milestone plan's section 10.3 item 5 budgets sweeps, and their times
are stated in the pull request; no CI workflow runs them. The suites' fixed tables and the proof
section run where the existing ones do.

## 4. The baseline, and predictions

**The baseline first, on the unchanged code**, in the pull request of rows 360 and 361's additions
if it has not merged, its own otherwise: `VarkaDecimalBaselineBenchmark` gains `DECIMAL(20, 2)`,
`DECIMAL(17, 2)` and `DECIMAL(38, 2)` columns beside their `BIGINT`, and the sections
`x20 * y17s2` (shortcut), `x20 * l` (128 by 64, checked) and `x38s2 * y38s2` (checked) as
projections and `x20 * y17s2 < z` as a filter, on the EPYC 7763 at 256 and 128 bits.

Predictions, registered before the spike and the build:

1. **C against S** at 256 bits on AVX2: S ahead by up to 2 times, C's 120 or so lane calls per
   four rows against S's eight scalar multiplies per row; at 512 bits on the laptop C within 1.5
   times of S either way.
2. **N against C**: N 1.5 to 2.5 times faster; the gate `HIGH` recovers most of that over columns of
   small values and costs under 10% over full-width ones.
3. **`TWOS` against `MAGNITUDE`** for the checked product: `TWOS` ahead by 1.1 to 1.4 times, the
   difference the four-word borrow.
4. **The build against the baseline** at 256 bits: the shortcut 10 to 40 times faster than the row
   path, the checked product 5 to 20 times, the row path multiplying two `BigDecimal`s under a
   `MathContext` and allocating three.
5. **At 128 bits on x86** the shortcut projection beats the row path by 3 to 10 times; the checked
   product carries masks and is measured, not predicted to win.
6. **No existing shape's bytes move**; the `option_arms` digests move with the three options.

## 5. Order, files and size

1. **The baseline addition** (4).
2. **The spike loops** (2.1), in 359's spike pull request or after it; the verdict in section 10.
3. **362a, the shortcut**: the node's two-word form (2.2), N and 128 by 64 under it, the
   compiler's first row of 2.3, the shortcut and N proofs, tests 1, 2, 4 and 5 for it, the filter
   coverage rows. Needs 359a and 361a (the partial-product helper).
4. **362b, the column sum**: the four-word form, both signs behind `decimalWideSign`, the
   full-width check and its disposal (after row 481 and 360c), the gate's options, the planted
   fault, the rest of tests 1 to 7.
5. **362c, after 359b's store**: the projections, the measurement against the baseline at both
   widths and 512 bits on the laptop, the predictions scored.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java`, `varka/VarkaVectorWalk.java`, `varka/Slots.java` | `PairMul`, N, C |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | the two options of 2.1 |
| `VarkaDecimalCompiler.java` (row 349's) | 2.3's table |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weight, feature, reach |
| `sql/varka/proofs/decimal128.smt2`, `VarkaProofFiles.java` | 3 item 7 |
| tests | product table, cross-check, type sweep, one-kernel test, named tests, coverage rows |
| benchmark | the baseline sections, the spike loops, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `census.tsv` |

**Size: large, in four pull requests after the baseline**: the spike loops about a day beside
360 and 361's, 362a about three days, 362b about four, 362c one. Test time: the product table and
the cross-check are pure kernels of the slot table's size, seconds; the type sweep under a second;
the proof section is priced from its spiked form at VARKA-245's observed ratio in the calibration
table, under ten seconds per solver for the linear layer, the ring identity's time to be measured
first, since sixteen products is the largest polynomial the proofs have stated. It rests on 347,
348 and 349, 359 (the pair, the compares, the check, the proof file and the store), 360b and 361a
(the partial-product helper), 481 and 360c (the disposal) and 503 for the 128-bit rule.

## 6. What it unlocks in the census

None alone. Under the milestone plan's 2.8 and 359's answered question 2 (a), `*` and
`try_multiply` count only when they fuse at every precision pair to 38, which needs 361's widening
product, this row's and 363's cut shapes; the two pairs count with the last of the three.

## 7. Out of this row, and declines that stay

Out: a cut scale (363); two 64-bit-valued operands (361); the pair's load and store (359, 157); a
truncated product that skips low columns, which would need its own error bound since nothing
cancels (`READING_M8_PAPERS.md` 2); decimal division (out of the milestone).

Declines that stay, each naming its row: a cut scale (363), a decimal in an int-driven kernel, a
negative scale (2.8), and at two long lanes on x86 a condition root (503) and any projection the
measurement shows losing there.

## 8. Open points for the lead

All three answered by the lead on 30 October 2026 and folded in above: the compiler uses the sharp
shortcut `p1 + p2 <= 38`, recorded as a departure from the row's text (0, "What differs"); row 362's
text has no "two negative operands" clause, so it needs no amendment, and test 3 states that the
planted fault is live for every sign pair and what each named draw tests; and the pair products are
a node of their own, `PairMul`, sharing 361a's partial-product helper rather than growing 360b's
`WideMul` (2.2).

## 9. Done when

Row 362's text holds, with the corrections above: the spike's verdict is committed per width
before the build; the product fuses for every precision pair whose result scale Spark keeps, in
every mode, with one kernel for the shortcut and the full-width check nulling through row 481's
mode and declining under ANSI, or carries its measured reason to decline; the cut shapes decline
naming 363; the ring identity and the carry network proven by SMT over integers at full width in
`decimal128.smt2` under both solvers, the shortcut line stating Velox's, the row's and the sharp
bound, a reduced width as a cross-check only; arrow-rs's carry corners, Trino's products and a
random `BigInteger` cross-check of every arm as tests; rapids' and Polars's named tests; the
planted fault refuted by the proof and failed by the cross-check with a witness of each sign
class; the measurement against the baseline committed at both widths with its predictions scored;
and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)
