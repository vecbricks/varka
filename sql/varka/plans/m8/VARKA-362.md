# VARKA-362: The full decimal multiply, 128 by 128 into 256 bits

*[Changed on 28 November 2026: this paragraph, the note under section 5's title and sections 11
and 12 are new; nothing else in the file is edited.] What is left of the row, cut into parts a
builder can take with nobody to ask, is section 11. Sections 0 to 10 are the plan of 30 October
2026 and the record of what was built; section 11.2 lists what in them is stale, and where they
and section 11 differ, section 11 holds.* *[E1 rework, 28 November 2026: by the owner's decision
E1 (a), part 362b3, form C's proof layers, is January 2027's, full strength, and no gate before
then; nothing waits on it, and every other part is Sonnet-class (11.6, 11.9).]*

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

*[Added on 28 November 2026.] The order and the parts below are superseded by section 11.6; the
table of files still says where each piece lives.*

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

### 10.1 Step 1, the baseline addition (20 November 2026)

Built on branch `varka-362-decimal-multiply-256` from master at `0b8be5a33e4` and brought to
master `a5c8d1f0972` on 27 November 2026 as branch `varka-362-363-baseline`, with row 363's
sections (`VARKA-363.md` 10), after row 350's casts had joined the baseline: a pull request of
its own, since rows 360 and 361's additions merged in #1460. `VarkaDecimalBaselineBenchmark` gains
eight sections, four shapes at both null densities, after every existing one so that no committed
section's values move:

| section | result type | class of 2.3 |
|---|---|---|
| `x20 * y17s2` as a value | `DECIMAL(38, 4)`, uncapped | the shortcut |
| `w20 * l` as a value, `l` the `BIGINT` | `DECIMAL(38, 2)`, capped, scale kept | 128 by 64, checked |
| `x38s2 * w38s2` as a value | `DECIMAL(38, 4)`, capped, scale kept | checked |
| `x20 * y17s2 < z20` as a filter | compares at `DECIMAL(38, 4)` | the shortcut |

Every section is `NONE`: no lowering computes a product past a long, and of the filter only the
null tests Spark infers would fuse, which VARKA-598 leaves on the row path. The three new columns
come after `l16`, the last column of row 350's casts: `y17s2`, a `DECIMAL(17, 2)`, and `w20` and `w38s2`, a `DECIMAL(20, 2)` and a
`DECIMAL(38, 2)` with narrower integer parts than `x20` and `x38s2` (the decisions below). The
queries suite pins the new columns' types, the sections' result types, the filter's comparison
type and the product's freedom from nulls. The runner results at 256 and 128 bits are owed: this
window is local only, so the two dispatches of `benchmark.yml` the class's javadoc names are left
to the next CI window.

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
| `x20 * y17s2` as a value | 744 / 755, 665 / 667 | 749 / 744, 658 / 661 |
| `w20 * l` as a value | 723 / 723, 651 / 636 | 709 / 705, 639 / 644 |
| `x38s2 * w38s2` as a value | 999 / 1002, 886 / 883 | 995 / 1008, 892 / 889 |
| `x20 * y17s2 < z20` as a filter | 1208 / 1211, 1013 / 1005 | 1191 / 1195, 999 / 1005 |

Every section prints 1.0X at both widths. The arms plan alike, so they tie: 0.99x to 1.02x by best
time at 256 bits and 0.99x to 1.01x at 128 bits. None of section 4's predictions is scored, since
each is about the spike or the build; these are the row-path times its predictions 4 and 5 are read
against when 362a lands. The baseline step is complete. The spike (step 2) and the kernels, 362a to
362c, are not built.

## Decisions taken while the owner was away (20 November 2026)

1. **The checked sections read narrower columns than section 4 names.** Section 4 names
   `x20 * l` and `x38s2 * y38s2`, but over the table's columns both products overflow their
   types: `x20`'s integer parts reach `5 * 10^17` and `l` reaches `2^63`, past the 36 integer
   digits of `DECIMAL(38, 2)`, and `x38s2 * y38s2` reaches `25 * 10^34`, past the 34 of
   `DECIMAL(38, 4)`. An overflow raises under ANSI and stops the timed run, and the baseline's rule
   is that no section's value overflows. So the sections read two new columns, `w20` with integer
   parts to `10^17` and `w38s2` with integer parts below `2 * 10^16`, which keep every product
   inside its type (the bounds are in `PRODUCT_COLUMNS`'s javadoc), rather than narrowing `x20` or
   `x38s2`, whose values earlier sections' committed results were timed over. What the checked
   product costs does not depend on its values in forms N and C; only the gate G does, and the
   spike, not the baseline, measures it over small and full-width columns.
2. **The filter compares with `z20`**, the existing `DECIMAL(20, 2)` column, which coercion casts
   to the product's `DECIMAL(38, 4)`, as row 360's `x20 + y20 < z20` does, rather than adding a
   `z` of the product's type.

## 11. The build plan for what is left (28 November 2026)

*[Added on 28 November 2026. Sections 0 to 10 above are the record and stay word for word; where
this section and they differ, this section holds. Code facts were read on master `6dafb8bd2f6`;
the unmerged branches were read with `git show` and `git diff`, nothing was built or run but
Python. The checks named "the model" are `/home/max/varka-ops/m8-row-plans/scratch-362/model.py`
and `verify362.py`; a builder does not need them, their results are quoted here.]*

### 11.1 What exists, and what does not

On master (each line is a command and what it prints):

| fact | command, from the repository root | prints |
|---|---|---|
| no product node, no lowering file | `grep -rl -e PairMul -e WideMul -e WordConst -e VarkaDecimalLowering sql/catalyst/src sql/core/src` | nothing |
| the products decline naming this row | `grep -c 'rows 362 and 363' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaDecimalCompiler.java` | `1` |
| the baseline's eight sections | `grep -cF -e 'x * y as a value, y a DECIMAL(17, 2)' -e 'w * l as a value' -e 'DECIMAL(38, 2) x * w' -e 'x * y < z filter, y a DECIMAL(17, 2)' sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` | `16` (two lines a section) |
| the pair's store takes every pair form | `grep -c 'new InterleaveDecimal(p.lo(), p.hi())' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaDecimalCompiler.java` | `1` |
| the two-lane refusal of a pair output | `grep -c 'TWO_LANE_DECIMAL_PAIR_OUTPUT_REASON' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaKernelPlan.java` | `2` |
| 359's spike ran loops 5, 6 and 7 on the runner | `grep -cF '5. x * y into 256 bits' sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-results.txt` | `6` |

Steps 1 (the baseline, #1793) and its readings (#1797) are done, sections 10.1 and 10.2. Nothing
else of the row is on master. The local branches of `/home/max/proj/vecbricks-varka`, all cut
from master `767dcddce50` of 20 November 2026 and 537 commits behind today's master, so none
merges (row 489's node descriptor, #1692 to #1696, replaced the switches they edit):

| branch | holds | against master | a part starts from it |
|---|---|---|---|
| `varka-362-decimal-multiply-256`, `-256-r` | the baseline sections, first cut and rebased | merged as #1793 in a later form | no: dead |
| `varka-362-363-baseline` | the baseline as merged, with row 363's sections | merged (#1793) | no: done |
| `varka-362-spike` | spike loops 5, 11, 12, 13 (644 counted lines), never run, four review findings open | behind, not merged | no: not landed, 11.3 decision 1 |
| `varka-362a-shortcut` | the shortcut from `WideMul` and two long multiplies, `ProductForm`, two suites, the shortcut proof layer, four coverage rows | behind, not merged | yes: parts 362a2 and 362a3 copy its compiler arm, suites and proof renderer |
| `varka-362a2-pairmul` | the same product under a `PairMul` node, form N, in every registry | behind, not merged | yes: part 362a1 re-ports its node onto `props()` |
| `varka-362b-column-sum` | form C in the lowering, `VarkaPairMulSuite`, the planted column 1 | behind, not merged | yes: part 362b1 copies its `columnSum` and suite |

The branches' own record of 20 November 2026 (their sections 10.2 to 10.5 and thirteen decisions)
is not on master. A part that lands a branch's code brings that branch's paragraphs of
`VARKA-362.md` section 10 with it, read with
`git -C /home/max/proj/vecbricks-varka show varka-362b-column-sum:sql/varka/plans/m8/VARKA-362.md`.

### 11.2 What in sections 0 to 10 is stale or wrong today

1. **0 item 11 and 2.1, "it has not run".** 359's spike ran on the EPYC 7763 at both widths
   (#1250) and on the laptop and the Arm runner (`VARKA-359.md` 10.1). Its loop 5 is this row's
   form C under `TWOS`, its loop 6 the product's `10^38` check. 11.3 reads the decisions from it.
2. **2.3, 3 item 8 and 5 item 5, "as filters now and projections after 359b's store".** The store
   merged (#1610, #1710): `VarkaDecimalCompiler.output` stores every form `pair` builds. Each
   part's shapes are outputs the day it lands, so 362c is a measurement only.
3. **2.2 and 5, "361a's partial-product helper".** The helper is row 360's, not 361a's: row
   360b's `WideMul` takes two variable operands and its lowering is the whole signed 64 by 64
   network, `VarkaDecimalLowering.productWord` in `VARKA-360.md`'s cut. Form N calls it for its
   low words, and form C has its own sixteen products (section 10.3 of the branch's record).
   11.5 lists what this row needs by name.
4. **2.4, "the disposal is row 360c's".** Row 481's `GuardedRange` has its `NULL` mode on master
   (#1176). This row builds its own guard (11.4.4) and waits on no part of row 360c.
5. **2.5, "the two-lane fallback does not reach the product".** It does: every pair's load is a
   two-vector `rearrange`, which C2 does not lower at two long lanes on x86, and the spike's
   product reads 142.9 ns a row there against 5.4 at 256 bits (the two runner files). 11.3
   decision 8.
6. **2.6 and 10.5 of the branch, the height.** The cap is an option now (`maxChainDepth`, rows
   620 and 629), 16 on the long lane today; row 697 (#1798, open) makes it 64 for a tree of the
   long lane alone at 256 bits and wider. Form C's top word is 22 levels and the checked product
   30 (11.4.5), so parts 362b2 on need row 697 merged; nothing else in the row does.
7. **6, "None alone".** The census has a mark for a cell fused in part, `dec^`. Part 362a2 sets
   it on `*` and `try_multiply`; 11.7.
8. **The row's note in `PLAN.md`**, "362a after 361a's partial-product helper": read item 3.

### 11.3 The decisions, each with its evidence

1. **The spike of step 2 is closed, and its loops are not landed.** What it was to decide is
   decided below from committed readings or from counts, and `varka-362-spike` carries four
   unfixed findings (its gate arms time the wrong thing, `decimal-chain.md` section 2). The
   readings used, AMD EPYC 7763, ns a row, in L2 to past L3
   (`sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-results.txt`):
   form C under `TWOS` 5.4 to 5.6 against the scalar loop's 7.1 to 10.9, and the product's check
   0.8 to 1.0 against 2.1 to 6.2. Spark's row path pays 499.7 ns a row for
   `DECIMAL(38, 2) x * w as a value` and 372.1 for `DECIMAL(20, 2) x * y as a value, y a
   DECIMAL(17, 2)`, both in
   `sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`.
2. **Prediction 1 of section 4 is scored: wrong.** It had S ahead of C by up to 2 times at 256
   bits; C leads at every rung (5.4 against 7.1 in L2, 5.6 against 10.9 past L3).
3. **Form C is built whatever it costs against S**: S is measured and not emitted (2.1), and C is
   near a hundredth of the row path's cost, so the row's exit "declined with its measured
   reason" is not taken at 256 bits.
4. **The shortcut is form N, with no measurement**: it is the same answer in fewer operations (33
   against 134 after sharing, by the model; 11 levels against 23), and it is the only form whose
   check is dead.
5. **One sign form, `TWOS`. No option `decimalWideSign` in this milestone.** `TWOS` is the form
   the spike measured and the branch built. `MAGNITUDE` was predicted 1.1 to 1.4 times slower
   (section 4, prediction 3), a ratio the house rule calls a tie under 1.3, on a kernel fifty
   times under the row path. The unsigned product that row 363 needs for its division is a
   third `Sign` value, `UNSIGNED`, added by row 363's part 363b1; it is not an option arm.
6. **No gate. No option `decimalWideGate` in this milestone.** The most a gate can save is form
   C's whole cost, 5.4 to 5.6 ns of a row that pays its load, check and store beside it, and a
   gate adds a pass over the batch and two more kernels to warm. It goes to `m10/SCOPE.md` with
   the loop that would reopen it (PRODUCT_SMALL of `varka-362-spike`).
7. **N's cross terms are one long multiply each**, as the branch built them
   (`VarkaDecimalLowering.cross`). Row 361's option `decimalWideMulLow`, if it lands, is about
   `WideMul`'s low word alone (its branch says so in `lowerAll`'s javadoc) and changes nothing
   in `PairMul`.
8. **At two long lanes on x86 every arm of this row declines in the compiler**, as row 360's
   plan decides for its arms (`VARKA-360.md` 11.3): each new arm first asks
   `sink.declinesTwoLaneChecks()` and declines with `VarkaDecimalCompiler.TWO_LANE_WIDE_REASON`,
   the constant row 360's rescale part adds. Behind it stand the refusals of the roots that
   exist: a filter's conjunct (`VarkaKernelPlan.TWO_LANE_CONDITION_REASON`), a pair output
   (`TWO_LANE_DECIMAL_PAIR_OUTPUT_REASON`), a comparison written as a value
   (`TWO_LANE_DECIMAL_VALUE_REASON`). The arm's own test closes the one form the roots leave, a
   choice with branches that are not decimals over a decimal condition, `IF(x * y < z, l, 0)`.
   Row 597's second half then writes the decline once over the readers and removes these uses
   (*28 November 2026:* the owner's answer to `VARKA-597.md` 11.7 question 1; a part built after
   597b merged writes no such test and expects `TWO_LANE_READ_REASON`, by `VARKA-360.md` 11.7,
   "The two-lane rule once row 597's part 597b merges"); row 698 measures whether a pair can
   win there. On aarch64 at 128 bits the cap is 16, so form N
   (13 levels under a comparison) fuses and form C declines with the cap's reason.
9. **No new emit option in this row.** A new shape is a new kernel where the row path ran,
   under the family switch its expression has already (`VarkaFamily`), as the shapes of rows
   359 and 360 are; an option is for a second lowering of one shape, and decisions 5 to 7 leave
   none.
10. **The checked product is `NULL` under LEGACY and TRY and `FAIL` under ANSI**, and the mode
    comes from `VarkaDecimalCompiler.decimalOverflow(m.evalMode())`, the helper row 360's part
    360c1 adds for the whole chain (`VARKA-360.md` 11.4, "The overflow mode of the whole
    chain"), never from `VarkaNodeCompiler.overflowOf` alone: `overflowOf` maps LEGACY to
    `WRAP`, the integral rule, and `GuardedRange`'s constructor throws on `WRAP`.

    | eval mode | Spark's `Multiply`, on a product past its type | `decimalOverflow` | the kernel |
    |---|---|---|---|
    | LEGACY (`spark.sql.ansi.enabled` false) | NULL (`toPrecision` with `nullOnOverflow` true) | `NULL` | the lane is null |
    | ANSI | raises `NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION` | `FAIL` | the batch declines; the row engine raises on the row Spark names |
    | TRY (`try_multiply`) | NULL (the node catches its own raise) | `NULL` | the lane is null |

    LEGACY and TRY share a kernel; ANSI has its own.
11. **`PairMul` is null where any of its four operand words is null**, the rule the owner set
    for `ScaleDown` on 27 November 2026, and not the narrowed rule of the branch's last commit
    (`b4d6e14e166`). `VarkaNodeProps` has `STRICT` for it and no kind for "the words read". The
    lowering ORs `w & 0` in for each operand word the lowered word does not reach (form N's
    word 0 reaches neither high word). Under `rightLong` the fourth child `yHi` is the
    long's sign extension, `yLo >> 63` by a slot, which the compiler's `signExtended` pair
    already holds as its high word: it is valid exactly where `yLo` is, so the rule gives the
    same validity whether a word reads it or not. Decision D5 of 11.9.

### 11.4 The algorithms, written out

All words are 64-bit and every operation wraps. `M = 2^32 - 1`. A pair is `(lo, hi)`, the value
`hi * 2^64 + lo` with `hi` signed and `lo` unsigned. `>>` is the arithmetic shift, `>>>` the
logical one.

#### 11.4.1 The type rule (`ProductForm`)

For `x * y` over `DECIMAL(p1, s1)` and `DECIMAL(p2, s2)`, typed `(p, s)` by Spark (read from the
node's `dataType`, never from the session), in this order:

| test | form | built by |
|---|---|---|
| `p <= 18` | `LONG` | row 349, on master |
| `s != s1 + s2` | `CUT` | row 363 |
| both operands are 64-bit values (precision 18 or less, or a `BIGINT` under its cast to a decimal of scale 0) | `WIDENING` | row 361 |
| `p1 + p2 <= 38` | `SHORTCUT`, form N, no check | part 362a2 |
| otherwise | `COLUMN_SUM`, form C, the check | part 362b2 |

`CUT` is asked before `WIDENING`, unlike the branch's `of`: a `BIGINT` times a
`DECIMAL(18, s)` with `s > 6` is two 64-bit values and a cut scale, and it is row 363's
(`VARKA-361.md` 0 item 5).

Counts over the 606841 ordered pairs of the 779 types, the 64-bit test read as "both
precisions 18 or less" (the model, equal to `results/VARKA-363-shapes.txt` and
`results/VARKA-362-shortcut.txt`): with precision loss allowed `LONG` 5644, `WIDENING` 30077,
`SHORTCUT` 65034 (64068 and the 966 at `p1 + p2 = 38`), `COLUMN_SUM` 20608, `CUT` 485478; with
it off 5644, 30077, 74650, 395200 and 101270.

Spark's `adjustPrecisionScale(P, S)` is `(P, S)` for `P <= 38` and
`(38, max(38 - (P - S), min(S, 6)))` above; `bounded(P, S)` is `(min(P, 38), min(S, 38))`. A
product has `P = p1 + p2 + 1` and `S = s1 + s2`.

#### 11.4.2 Form N, `PairMul` with `words = 2`

    WideMul(x, y), the signed 64 by 64 product of two words (row 360b's lowering):
      xl = x & M;  xh = x >>> 32;  yl = y & M;  yh = y >>> 32
      p0 = xl * yl;  p1 = xh * yl;  p2 = xl * yh;  p3 = xh * yh
      t = p1 + (p0 >>> 32);  u = p2 + (t & M)
      low  = (u << 32) | (p0 & M)
      high = p3 + (t >>> 32) - ((y & (x >> 63)) + (x & (y >> 63))) + (u >>> 32)
    PairMul word 0 = WideMul(xLo, yLo).low
    PairMul word 1 = WideMul(xLo, yLo).high + (xHi + (xLo >>> 63)) * yLo
                     + (yHi + (yLo >>> 63)) * xLo        (the last term dropped under rightLong)

It is the product modulo `2^128`, so it is the product where `p1 + p2 <= 38`:
`|x * y| <= (10^p1 - 1)(10^p2 - 1) <= 10^38 - 1 < 2^127`. A 64-bit operand goes on the right
with `rightLong`. The model: 114432 pattern pairs over every 128-bit pattern class, none wrong.

#### 11.4.3 Form C, `PairMul` with `words = 4`, sign `TWOS`

    x[0..3] = xLo & M, xLo >>> 32, xHi & M, xHi >>> 32;  y[0..3] likewise (y[0..1] under rightLong)
    c[0..7] = 0
    for i, j:  p = x[i] * y[j];  c[i + j] += p & M;  c[i + j + 1] += p >>> 32
    sx = xHi >> 63;  sy = (yHi, or yLo under rightLong) >> 63
    nxl = ~xLo & sy;  nxh = ~xHi & sy;  nyl = ~yLo & sx;  nyh = ~yHi & sx
    two pairs:    c[4] += (nyl & M) + (nxl & M) - sx - sy
                  c[5] += (nyl >>> 32) + (nxl >>> 32)
                  c[6] += (nyh & M) + (nxh & M);   c[7] += (nyh >>> 32) + (nxh >>> 32)
    a long right: top = ((~sx & sy) & M) + (sx & M)
                  c[2] += (nxl & M) - sy;          c[3] += nxl >>> 32
                  c[4] += (nxh & M) + (nyl & M) - sx
                  c[5] += (nxh >>> 32) + (nyl >>> 32);   c[6] += top;   c[7] += top
    for k = 1..7:  c[k] += c[k - 1] >>> 32
    word w = (c[2w] & M) | (c[2w + 1] << 32)

The folds are `X * Y = Xu * Yu - 2^128 ([X < 0] Yu + [Y < 0] Xu)` modulo `2^256`, each negation
written `~v + 1`; subtracting a sign word (0 or -1) adds its 1. A column holds at most twelve
halves below `2^32`, two ones and a carry below `2^4`, so it stays below `2^36` and none wraps
(35 bits is the widest seen). The planted fault, `PlantedFault.COLUMN_ONE_ONE_WORD`, sums
`x[0] * y[1] + x[1] * y[0]` in one word before taking its halves. The model: 64368 pattern pairs
for a pair and for a long right, none wrong; the plant is caught by `(2^64 - 1) * (2^34 - 1)`,
missed by `2^112 * 2^112` and by `-(2^64 - 1) * (2^34 - 1)`, and wrong in 351, 698 and 338 of
20000 draws for none, one and two negative operands.

#### 11.4.4 The check at full width, and the guard

With `w0..w3` form C's words, `B = 10^38 - 1`, its pair `(Blo, Bhi)` and `-B`'s `(Nlo, Nhi)` four
long literal slots (`0x098a223fffffffff`, `0x4b3b4ca85a86c47a`, `0xf675ddc000000001`,
`0xb4c4b357a5793b85`):

    s       = w1 >> 63
    upper   = (w2 ^ s) | (w3 ^ s)                    0 exactly where the product fits 128 bits
    outside = CondValue(Or(DecimalWords.compare(GT, w0, w1, Blo, Bhi),
                           DecimalWords.compare(LT, w0, w1, Nlo, Nhi)), LONG)
    g       = GuardedRange(upper | outside, 0, 0, mode)
    result  = (w0 + g, w1 + g)

`upper | outside` is 0 exactly where `|x * y| <= 10^38 - 1`: the model's 80017 products, among
them `B`, `B + 1`, `-B - 1`, `2^127`, `10^61` and 20000 draws straddling `10^38`, none wrong. `g`
is 0 on every live lane, so the sum is the word; under `NULL` the lane is null in `g` and so in
both words, under `FAIL` the batch declines. The check reads all four words before any is
dropped. `mode` is `decimalOverflow(m.evalMode())` (11.3 decision 10), and the last two lines
are one call of row 360's helpers: `guardedPair(new BitOp(BitOpKind.OR, upper, new
CondValue(outside38(w0, w1, sink), LaneType.LONG)), w0, w1, mode, type)`; `outside38` builds
the two compares and their four slots. Against Spark (`MathContext(39, DOWN)`, then
`changePrecision` `HALF_UP`, modelled with Python's `decimal`): 55318 draws of kept-scale products
at random types, none wrong, and no shortcut draw fails the check.

#### 11.4.5 Heights, by the model (a leaf counts 0, as `Analysis` counts)

The model reproduces every height of `results/VARKA-630-decimal-shapes.txt` (which counts a
leaf 1): 9 and 11 for form N's words, 9, 13, 19 and 23 for form C's, 10 and 22 for a long right.
Whole shapes, the taller of an output root and a comparison with a column: the shortcut 13 (12
by a long), the checked product 30 (29 by a long). So the shortcut fits today's cap of 16 on
the long lane, and the checked product needs row 697.

### 11.5 What the parts need from rows 360, 361 and 697, by name

Rows 360 and 361 are cut in `VARKA-360.md` and `VARKA-361.md`. **The table of every shared name
with its signature, the part that creates it and the parts that use it is `VARKA-360.md` 11.7,
"The names the four rows share"; the order of the whole chain is `VARKA-360.md` 11.2, "The
whole decimal chain, in order", where this row's parts are steps 9 to 13.** Where a spelling
here and there differ, that table holds. What this row takes, with the part that creates it:

| name | created by | what this row uses it for |
|---|---|---|
| `record WordConst(long value, LaneType lane)`, and `WordConst(long value)` for the long lane | 360b1 | masks and shift counts of a lowering; the null rule's `& WordConst(0)` |
| `record WideMul(VarkaVectorIR left, VarkaVectorIR right, Word word, boolean rightOneLimb, int hash)` with two variable operands | 360b1 | form N's low words |
| `VarkaDecimalLowering`: `lowerAll(outputs, options, faults)`, `lower(WideMul, VarkaEmitOptions, Set<PlantedFault>)`, `productWord(options, x, y, word, rightOneLimb, xSigned, ySigned, faults)`, `laneCalls`, `enum PlantedFault`, the private `bit`, `mul`, `add`, `sub` | 360b1 | `PairMul`'s lowering goes in this file, with the same parameter order: `lower(PairMul n, VarkaEmitOptions options, Set<PlantedFault> faults)` |
| `VarkaWideMulSuite` and the wide-product draw in `VarkaIrGrammar` | 360b1 | where `PairMul`'s draw joins |
| `widePair`, `signExtended`, `carry(a, s)`, `borrow(a, b)` | the waiting branch `varka-360a-sums`, merged by the lead | operands of a product as pairs |
| `bigintPair(Cast c, DecimalType type, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink)` | the waiting branch `varka-360a-bigint-cast` only (it is not on `varka-360a-sums`) | a `BIGINT` under its cast as a pair (362a2) |
| `TWO_LANE_WIDE_REASON` | 360b2, unless 597b merged first (`VARKA-360.md` 11.7's two-lane rule); 597b removes it | the two-lane decline of each new arm |
| `decimalOverflow(Object evalMode)`, `outside38(lo, hi, sink)`, `guardedPair(over, lo, hi, mode, type)` | 360c1 | the checked product's mode and guard (362b2) |
| `longValued`, `pairWidening`, `wideningProduct` | 361a | `ProductForm.WIDENING` is exactly `pairWidening`; the shortcut reads `longValued` |
| `VarkaEmitBudget.LONG_LANE_MAX_CHAIN_DEPTH` (64) | row 697, pull request #1798, branch `varka-long-lane-chain-cap`, not merged | 362b2: the checked product is 29 or 30 levels |

The three waiting branches of row 360 are merged by the lead before any part here starts; no
part starts from one, and each part's B0 greps the names on master. Row 361 lands before this
row (`PLAN.md` 12.5, and `VARKA-361.md` 11.3 decision 1), so its arm takes
`ProductForm.WIDENING`'s shapes and this row adds the enum around its predicates. Row 361's
options `decimalWideMulLow` and `decimalSignWord` are not read by `PairMul`'s own terms (11.3
decision 7); where `productWord` reads `decimalSignWord`, form N's low words follow it. The
checked product's guard is not written a second time: 362b2 waits on 360c1 and calls its three
helpers; there is no "if it is on master" case.

### 11.6 The parts

Each part is one pull request under 1000 counted lines (`python3 dev/varka_msg_check.py size`).
The rules every part follows are in `sql/varka/AGENTS.md` ("Pull requests", "Java code", "A new
bounded lowering comes with its proof file"), `sql/varka/ADDING_AN_EXPRESSION.md` ("Closing a
row", "Regenerating the generated files") and `VARKA-489.md` ("Adding a node, after 489c"). Paths:
`C` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`, `T` the
same under `src/test/java`, `TS` under `src/test/scala`. A test of a shape taller than 16 levels
sets `maxChainDepth(64)`, `methodByteBudget` and `cse` explicitly in its options, so that it
fuses at every width and under every matrix configuration (#1798's fix of the same fault).
*[Fix round 3, 28 November 2026.]* So does every other kernel test of a `PairMul` shape,
whatever its height (form N's 13 levels included): the cap on 64 distinct ops under the matrix
configuration `methodByteBudget=0` does not read the height. The options are
`VarkaDecimalPairSuite.DEEP` (`VARKA-360.md` 11.3 decision 5, which writes it and the
evidence), or a set built from it with `toBuilder()`; a test that asserts a decline only keeps
`VarkaMatrixRuns.BASE`.

*[Fix round 3, 28 November 2026.]* **A decline an earlier part asserts, which this part turns
into a fuse.** Before the change, `grep -rn '<a fragment of each reason this part retires>'
sql/catalyst/src/test sql/core/src/test`. Each assertion of a shape this part now fuses
becomes a value check of the same shape (`checkStored`, or `storedOrDeclined` for a live
check) under `VarkaDecimalPairSuite.DEEP`, or the cap's reason where the shape declines at
the cap; it is never deleted, and the pull request names each. The ones known now are
named in the parts.

| part | what | counted lines | builder | starts from | waits on |
|---|---|---|---|---|---|
| 362a1 | `PairMul`, form N, as a node | about 480 | Sonnet-class | master, code from `varka-362a2-pairmul` | 360b1; 361b in the lowering file's queue |
| 362a2 | the shortcut in the compiler, its outputs, the baseline's two shapes | about 750 | Sonnet-class | master, code from `varka-362a-shortcut` | 362a1, 361a, 360b2, the waiting branches merged |
| 362a3 | the shortcut's proof layer | about 200 | Sonnet-class | master, code from `varka-362a-shortcut` | 362a2 |
| 362b1 | form C in the lowering, the planted column | about 420 | Sonnet-class | master, code from `varka-362b-column-sum` | 362a1 |
| 362b2 | the checked product in the compiler, the guard | about 600 | Sonnet-class | master | 362b1, 362a2, 360c1, row 697 (#1798) |
| 362b3 | the ring, carry and check layers of the proof | 300 to 500 | **January 2027, full strength; not a gate before then** (E1, 11.9) | master | 362b1; nothing waits on it |
| 362c | the runner's readings, predictions scored, the row closed | under 100 and results | Sonnet-class | master | 362b2 |

The order: the node before its caller, so that each pull request is green alone; the shortcut
before the checked product, because it moves the census mark and two benchmark shapes with no
dependency on row 697; the proofs beside the code they prove, never ahead of it.
*[E1 rework, 28 November 2026.]* One proof comes after its code: 362b3, January 2027, by the
owner's decision E1 (11.9). Form C ships before it in 362b1 and 362b2 with the differential
tests the sheets name (362b1's 20000 patterns, the carry corners, the plant and the IR
grammar's form C draw; 362b2's 5000 seeded draws against Spark's `Decimal` in the three modes
and the coverage rows). Until January: no part writes a placeholder, a marker line or an empty
check for 362b3 into `decimal128.smt2` or `VarkaProofFiles`; a part that regenerates
`decimal128.smt2` (362a3, and rows 360 and 361's proof parts) expects no form C layer in it;
and the pull requests of 362b1 and 362b2 say in their bodies, in one sentence, "Form C's proof
layers are part 362b3, January 2027, by the owner's decision E1 of 28 November 2026", since
`sql/varka/AGENTS.md`'s rule "A new bounded lowering comes with its proof file" is met for
form C only then. In every
command below that reads a branch, `C/` and `T/` are not expanded by `git`: the commands are
written with whole paths.

#### Part 362a1, `[VARKA-362] Add the pair product's node, the low 128 bits of two pairs`

Branch `varka-362a1-pairmul-node`.

**B0, the premises** (stop and report if one does not hold):

    grep -c 'record WideMul(' C/varka/VarkaVectorIR.java                  -> 1
    grep -c 'record WordConst(' C/varka/VarkaVectorIR.java                -> 1
    grep -c 'static VarkaVectorIR lower(WideMul' C/varka/VarkaDecimalLowering.java   -> 1 or 2
    grep -c 'record PairMul(' C/varka/VarkaVectorIR.java                  -> 0

**Steps.**

1. `VarkaVectorIR`: `record PairMul(VarkaVectorIR xLo, VarkaVectorIR xHi, VarkaVectorIR yLo,
   VarkaVectorIR yHi, int words, int word, boolean rightLong, int hash)`, beside `WideMul`, in
   the `permits` list. The compact constructor: the four children on the long lane; `words` is
   2 (4 is refused with "form C is part 362b1's" until that part); `0 <= word < words`. A public
   constructor without `hash`, `hashCode()` returning it, as `WideMul` has. **Under `rightLong`
   the caller passes as `yHi` the sign extension of `yLo`** (`new BitOp(BitOpKind.SHR, yLo,
   sink.longSlot(63))`, the high word of the `signExtended` pair it already holds), never a
   constant and never another node: the lowering reads no bit of it, and its validity is
   `yLo`'s, so the null rule below changes nothing for it. The javadoc says so. The branch's
   record has no `props()` (it predates row 489); write it as `WideMul`'s:

       return new VarkaNodeProps(NEVER, false, STRICT, VarkaDecimalLowering.laneCalls(this),
           getClass().getSimpleName() + words + (rightLong ? "/l" : ""), FILLS_NOTHING,
           RESTRICTS_NOTHING, LongNarrowReader.NONE);

   The canonical text is `pmul<words>:<word>(xLo, xHi, yLo, yHi)`, with `:l` after the word
   under `rightLong`; all four children are printed and parsed in both cases.
2. `VarkaDecimalLowering`: `lower(PairMul n, VarkaEmitOptions options, Set<PlantedFault>
   faults)` as 11.4.2, copied from the branch (the command at the end of this step) with the
   `options` parameter added and passed to `productWord`, and its arm in the private
   `lowered`. Then the null rule of 11.3 decision 11: after the word is built, for each of the
   four children the word's graph does not reach (walk it, stopping at the children),
   `word = word | (child & WordConst(0))`. `laneCalls(PairMul)`, under
   `VarkaEmitOptions.DEFAULTS`, counting the lowered children and never calling `props()`.

       git -C /home/max/proj/vecbricks-varka show varka-362a2-pairmul:sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaDecimalLowering.java

3. **The files the node goes into: a command, not a list kept by hand.** `git grep -lw HashStep
   -- sql dev | grep -v '\.md$'` prints every file that names a composite value node of master
   (`HashStep`: value operands on one lane and a lowering class of its own; `InterleaveDecimal`
   is the wrong model, a store's root). On master `6dafb8bd2f6` it prints 24 files; after row
   360's 360b1 each of them but the last row's names `WideMul` too, so `git grep -lw WideMul --
   sql dev | grep -v '\.md$'` is the second check. **The rule: every file the command prints
   either gets `PairMul` or is in the last row, with the reason.**

| files the command prints | what `PairMul` gets there |
|---|---|
| `VarkaVectorIR.java` | the record and `permits` (step 1) |
| `Analysis.java` | `collectRefusals`: an empty arm; `analyze`: throws, the node is lowered first (as `WideMul`) |
| `Slots.java` | *[Fix round 3.]* One place: in the one exhaustive switch of `WordWalk.visit` (the demand walk; on master `6dafb8bd2f6` from line 1625, holding `case HashStep x -> demand(...)` at 1729, and after 360b1 also `WideMul`'s arm), `case VarkaVectorIR.PairMul x -> throw VarkaErrors.illegalState("PairMul is lowered before the analysis");` beside `WideMul`'s: a lowered node never reaches it. The CSE condition at line 1014 is a leaf's rule and does not get the node. |
| `VarkaVectorWalk.java` | throws, as `WideMul`: a lowered node never reaches the walk |
| `VarkaKernelPlan.java`, `VarkaChronoCompiler.java` | the exhaustive list that holds `WideMul _` |
| `VarkaRangeAnalysis.java` | `VarkaValueRange.UNKNOWN` |
| `VarkaSameAnswer.java` | beside `WideMul`, by its value children |
| `VarkaReference.java` | the words of the `BigInteger` product modulo `2^128`; null where any of the four operands is null |
| `VarkaCanonicalIr.java` | the `pmul` render and parse arms |
| `VarkaIrGrammar.java` | its bounds, and a draw one time in five inside row 360b's wide-product sequence, over any four long leaves |
| `VarkaSmtNodes.java` | `REFUSED`, as `WideMul` |
| `VarkaGuardSpecs.java`, `VarkaWordCensus.java` | the arm `WideMul` has there |
| `VarkaLaneTypeSuite.scala`, `VarkaRangeAnalysisSuite.scala` (`admissible`), `VarkaReproductionSuite.java`, `VarkaWithChildrenSuite.scala`, `VarkaIrFuzzSuite.scala`, `VarkaEmitCostSuite.scala`, `VarkaEmitterBudgetSuite.scala` | each suite's register of nodes |
| `VarkaHashCompiler.java`, `VarkaHashLowering.java`, `VarkaEmitterHashSuite.java` | **nothing**: `HashStep`'s own compiler, lowering and suite; `PairMul`'s own are `VarkaDecimalCompiler` (part 362a2), `VarkaDecimalLowering` and `VarkaPairMulSuite` |

   Two files the command cannot print: `VarkaDecimalLowering.java` (step 2) and
   `VarkaEmitCostTable` (a price for the feature `PairMul2`, as `VarkaEmitCostSuite` asks). The
   old branch also edited `VarkaEmitBudget`, `VarkaEmitCost` and `VarkaOperandPosition`: do not
   port those hunks, `props()` answers them (`VARKA-489.md`, "Adding a node, after 489c").
   **Stop rule:** if `build/sbt catalyst/Test/compile` passes while a file of the table's
   other rows does not name `PairMul`, or fails in a file the table does not hold, stop and
   report. `python3 dev/varka_push_check.py` (rule R1) names a fixture still missing.
4. `T/varka/VarkaPairMulSuite.java`, JUnit 5 under `@VarkaSuite`. The suite exists on one branch
   only, `varka-362b-column-sum`, in its form C shape, with four tests (`narrowWords`,
   `columnSumWords`, `plantedColumnOne`, `columnSumHeight`); read it with the command at the
   end of this step. Take from it `narrowWords` and the helpers it calls (`checkForm`, `node`, the
   value lists), cut to `words = 2`; leave its other three tests to part 362b1. Four tests here: (1)
   form N, a pair by a pair (`narrowWords`'s first half): the lowered words and the reference
   against `BigInteger` over every pair of the values `0, 1, -1, 2^31, 2^32 - 1, 2^32, 2^63 - 1,
   2^63, 2^64 - 1, 2^64, 2^96 - 1, 2^112, 2^127 - 1, -2^127, 10^19 - 1, 10^38 - 1` and the negation
   of each where the negation is in `[-2^127, 2^127 - 1]` (the negation of `-2^127` is not: leave it
   out, it would wrap to `-2^127` and read as a failure of the lowering), and 20000 seeded patterns
   of every bit length; (2) the same by a long right (`narrowWords`'s second half), with `yHi` the
   sign extension; (3) new, the null rule: `PairMul` word 0 with `xHi` a `NullValue` is null in the
   kernel and in the reference; (4) new, the canonical text parses back, with and without `:l`.

       git -C /home/max/proj/vecbricks-varka show varka-362b-column-sum:sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaPairMulSuite.java

**The failure only it catches.** Test 3: a kernel that answers a value where the node's rule says
null. Run it once before step 2's null rule is added: it must fail there.

**Commands, in order.**

    build/sbt catalyst/Test/compile                                   -> [success]
    build/sbt 'catalyst/testOnly *VarkaPairMulSuite'                  -> Passed: Total 4, Failed 0
    build/sbt 'catalyst/testOnly *VarkaNodePropsSuite *VarkaLaneTypeSuite *VarkaIrFuzzSuite
        *VarkaWithChildrenSuite *VarkaSmtNodesSuite *VarkaEmitCostSuite *VarkaEmitterBudgetSuite
        *VarkaRangeAnalysisSuite *VarkaReproductionSuite'             -> [success]
    python3 dev/varka_push_check.py                                   -> its R1 names no missing fixture
    dev/varka_regen_generated.sh --all                                -> varka regen: done

**Done when** the commands print that, `git diff` of `emitted_bytes.json` shows no existing
shape's bytes moved, and the gate passes. **Not here:** `VarkaDecimalCompiler`, `words = 4`, a
coverage row, the census.

#### Part 362a2, `[VARKA-362] Fuse a decimal product that always fits 128 bits`

Branch `varka-362a2-shortcut`.

**B0.**

    grep -c 'record PairMul(' C/varka/VarkaVectorIR.java                          -> 1
    grep -c 'rows 362 and 363' C/VarkaDecimalCompiler.java                        -> 1
    grep -c 'Optional<Pair> widePair' C/VarkaDecimalCompiler.java                 -> 1 or more
    grep -c 'boolean pairWidening(' C/VarkaDecimalCompiler.java                   -> 1
    grep -c 'TWO_LANE_WIDE_REASON' C/VarkaDecimalCompiler.java                    -> 1 or more
    grep -c 'Optional<Pair> productWords(' C/VarkaDecimalCompiler.java            -> 0
    grep -cF 'x * y as a value, y a DECIMAL(17, 2)' sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaDecimalBaselineBenchmark.java   -> 1

**Steps.**

1. `VarkaDecimalCompiler`: `enum ProductForm` with `of(t1, t2, result, bothLongValued)` in the
   order of 11.4.1 (the cut test first; its `WIDENING` test is row 361's `pairWidening`, and
   `longValued` is on master), `pairShortcut`, `pairProduct`, `productWords` and
   `signExtension`,
   copied from the branch (the command at the end of this step). `pair`'s switch gains
   `case Multiply m when pairShortcut(m) -> pairProduct(m, type, inputs, literals, sink)` ahead of
   its default. `productReason` takes the `Multiply` and switches over the form: `LONG` and
   `WIDENING` keep master's text; `COLUMN_SUM` is "a decimal product of N digits, capped at 38
   with its scale kept, is row 362's column sum"; `CUT` is "a decimal product of N digits,
   capped at 38 with its scale cut, is row 363's". The branch's reason "as a value is row 359's
   two-word store" is not copied: a shortcut product is an output now (11.2 item 2), and
   `wideArithmeticBelow` must let `pair` reach a shortcut product under a cast, a negation, a
   choice or a pick.
       git -C /home/max/proj/vecbricks-varka show varka-362a2-pairmul:sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaDecimalCompiler.java

   **`productWords` is on another branch.** *[Fix round 3, 28 November 2026.]*
   `varka-362a2-pairmul` holds `pairProduct` whole (its lines 718 to 745, with the operand code
   inline) and no `productWords`; the split into the two methods is on `varka-363a2-callers`
   only, its lines 874 to 911 (`pairProduct`'s signature to `productWords`'s closing brace;
   the javadoc above them, from line 855, is the same on both branches). Take those 38 lines
   from the second command below, as they stand: `pairProduct` keeps the type check and ends
   `return productWords(m, type, inputs, literals, sink);`, and `private static
   Optional<Pair> productWords(Multiply m, DecimalType type, VarkaSlotTable inputs,
   VarkaSlotTable literals, DeclineSink sink)` holds the two `pair` calls, the 64-bit operand
   moved to the right, `rightLong` and the two `PairMul` words. Against the first branch the
   text differs only by the split (`diff` of its lines 718 to 745 and the second branch's 874
   to 911 shows the ten added lines 886 to 895 and nothing else). The split is this part's;
   part 363a3 of `VARKA-363.md` adds `productWords`'s second caller, `cutProduct`, and copies
   nothing of lines 874 to 911.

       git -C /home/max/proj/vecbricks-varka show varka-363a2-callers:sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaDecimalCompiler.java | sed -n 874,911p

2. Before rewording a reason: `grep -rn 'rows 362 and 363' sql/catalyst/src/test sql/core/src/test`
   and change each test that quotes it.
3. `T/VarkaDecimalProductFormSuite.java` (the type sweep, one test): `ProductForm.of` over every
   `(p1, s1, p2, s2)` with Spark's `Multiply` typing it under both settings, held to the counts
   of 11.4.1. `T/varka/VarkaDecimalProductSuite.java`, six tests through kernels at 2, 4 and 8
   long lanes, lengths 1, 3, 37 and 64: (1) the shortcut's table, `+-(10^p - 1)` at `p = 19, 20`
   against `+-(10^q - 1)` at `q = 1, 17, 18` and `(10^19 - 1)^2` at `p1 + p2 = 38`, in LEGACY,
   ANSI and TRY; (2) 5000 seeded draws; (3) every null pattern; (4) a pair times a 64-bit value
   and the named draw `(2^64 - 1) * (2^34 - 1)` as a `DECIMAL(20, 0)` by a `DECIMAL(11, 0)`; (5)
   one shape hash in the three modes; (6) the declines: a kept-scale product past 38 operand
   digits names the column sum, rapids'
   `577694938495380589068894346.7625198736 * -1258508260891400005608241690.15647` names row
   363. Two more in `VarkaDecimalPairSuite` (the suite that holds the pair's outputs): the product
   as a projection output equals Spark's in the three modes (`checkStored`); and the two-lane
   refusals, each by the call that exists for it. The roots: `VarkaKernelPlan.admit(List.of(
   root), VarkaMatrixRuns.BASE, true)`, as `VarkaTwoLaneConditionSuite`'s private `admit(root,
   true)` does, refuses the stored product with `TWO_LANE_DECIMAL_PAIR_OUTPUT_REASON` and the
   filter with `TWO_LANE_CONDITION_REASON`. The arm itself: the sink's two-lane setting is the
   second component of `VarkaAdmission(Set<VarkaFamily> familiesOff, boolean
   declineTwoLaneConditions)`, so the test calls `VarkaExpressionCompiler.declines(entries,
   columns, VarkaMatrixRuns.BASE, new VarkaAdmission(Set.of(), true))` (the call
   `VarkaDecimalValueSuite` and `VarkaMathCompilerSuite` make with `false`) and holds the one
   decline's reason to `TWO_LANE_WIDE_REASON`; with `false` the same call returns no decline
   (`pairProduct` asks `sink.declinesTwoLaneChecks()` first).
4. `VarkaCoverageSuite`: a family "DECIMAL full multiply" with `d19 * d18b < d19`,
   `d19 * d19 > d19`, `d19 * d9 <= d9b`, `try_multiply(d19, d18b) >= d19` and one projection,
   `d19 * d18b`. `census_curated.txt`: `dec^` in the types cell of `*` and of `try_multiply`,
   the note's "the pair counts with 361 and 362" kept. Then the regeneration.
5. `VarkaDecimalBaselineBenchmark`, in `addFullProducts`, at both null densities. The class
   of a section that turns is `fusedFilter` (the local that file already has: `Fusion.WHOLE`,
   and `Fusion.NONE` on a session that refuses a 64-bit condition), for a value section and a
   filter section alike, since every arm of this row declines at two lanes. By title:

| section | before | after this part |
|---|---|---|
| "DECIMAL(20, 2) x * y as a value, y a DECIMAL(17, 2)" | `Fusion.NONE` | `fusedFilter` |
| "DECIMAL(20, 2) x * y < z filter, y a DECIMAL(17, 2)" | `Fusion.NONE` | `fusedFilter` |
| "DECIMAL(20, 2) w * l as a value, l a BIGINT" | `Fusion.NONE` | `Fusion.NONE` (part 362b2 turns it) |
| "DECIMAL(38, 2) x * w as a value" | `Fusion.NONE` | `Fusion.NONE` (part 362b2 turns it) |

   `addFullProducts` gains the `fusedFilter` parameter as `addCasts` has its `fused`. The class
   javadoc's sentence on these sections follows. `VarkaDecimalBaselineQueriesSuite` holds the
   classes.
6. The warm-up: add the shortcut to the long block as `VarkaWarmupReachSuite` asks.

**Commands.**

    build/sbt 'catalyst/testOnly *VarkaDecimalProductFormSuite *VarkaDecimalProductSuite
        *VarkaDecimalArithmeticSuite *VarkaDecimalPairSuite *VarkaExpressionCompilerSuite
        *VarkaCoverageSuite *VarkaWarmupReachSuite'                   -> [success]
    build/sbt 'sql/testOnly *VarkaDecimalBaselineQueriesSuite *VarkaDifferentialHarnessSuite'
                                                                      -> [success]
    grep -c 'Optional<Pair> productWords(' C/VarkaDecimalCompiler.java            -> 1
    dev/varka_emit.sh "x * y" --columns x:dec20s2,y:dec17s2   -> ends FusedOutput(0)
    dev/varka_regen_generated.sh --all; python3 dev/varka_census.py --check   -> done; nothing

**Done when** those print that and the gate and the option matrix pass. **Not here:** form C,
the check, a proof file.

#### Part 362a3, `[VARKA-362] Prove the product that fits 128 bits`

Branch `varka-362a3-shortcut-proof`. Copy `VarkaProofFiles`'s shortcut layer from the branch
(the first command below; 121 lines: the pair's split into `H` and the signed low word, the ring
identity, the words modulo `2^128` with each wrap a named quotient, the bound by sign case, and the
three lines, Velox's `P < 38`, the row's `P <= 38` and the sharp `P <= 39`), re-pointed at
`PairMul`'s lowering. Regenerate `decimal128.smt2`.

    git -C /home/max/proj/vecbricks-varka show varka-362a-shortcut:sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaProofFiles.java

    dev/varka_prove.sh --solver both sql/varka/proofs/decimal128.smt2
        -> exit status 0, and one line a solver that ends "holds":
           "<solver> decimal128.smt2  <N> checks  <seconds> s  holds"; "FAILED" is a stop
    build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'                 -> [success]

State both solvers' times before and after in the pull request. If a check answers `unknown` or
passes 60 seconds, stop and report: that is full-strength work (the branch's first form of the
words' layer hung cvc5 until each wrap was a named quotient). *[E1 rework, 28 November 2026.]* By
the owner's decision E1 such work is January 2027's: the claims that decide land in this
part, the ones that do not move to part 362b3's January text with their solver output quoted,
and nothing waits on them.

#### Part 362b1, `[VARKA-362] Add the 256-bit product's column sum to the pair product`

Branch `varka-362b1-column-sum`. B0: `grep -c 'record PairMul(' C/varka/VarkaVectorIR.java`
prints 1 and `grep -c 'columnSum' C/varka/VarkaDecimalLowering.java` prints 0.

1. `PairMul` admits `words = 4`. `VarkaDecimalLowering.columnSum` as 11.4.3, copied from the
   branch (the command at the end of this step), with the `options` parameter of part 362a1's
   `lower`, with `PlantedFault.COLUMN_ONE_ONE_WORD` and the null rule of part 362a1 (form C's words
   2 and 3 reach every operand; words 0 and 1 may not). The branch's `PairMul` record is the old one
   with no `props()`: keep part 362a1's record and change only what admits `words = 4`.

       git -C /home/max/proj/vecbricks-varka show varka-362b-column-sum:sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaDecimalLowering.java

2. `VarkaReference`: the four words of the `BigInteger` product. `VarkaIrGrammar`: form C joins
   the draw, each emitting option set with `maxChainDepth(64)`.
3. `VarkaPairMulSuite` gains five tests, from the branch's `columnSumWords`, `plantedColumnOne`
   and `columnSumHeight`: form C over part 362a1's values and 20000 patterns, a
   pair and a long right; `(10^38 - 1)^2` with its words `0xecebbb8000000001`,
   `0xe0ff0ca0bc87870b`, `0x0764b4abe8652978`, `0x161bcca7119915b5`; arrow-rs's carry corners
   (`READING_ARROW_RS.md` 3, item 4) and Trino's products (`READING_TRINO_DECIMAL.md` 10, item
   2); the plant, caught by `(2^64 - 1) * (2^34 - 1)` and by the first witness of each sign
   class of `results/VARKA-362-planted-carry.txt`, missed by `2^112 * 2^112`; and the heights,
   form C's top word 22 by `Analysis` and past a cap of 16, form N's within it.

    build/sbt 'catalyst/testOnly *VarkaPairMulSuite'                  -> Passed: Total 9, Failed 0
    (the registry suites and the regeneration of part 362a1)

**Not here:** the compiler, the check.

#### Part 362b2, `[VARKA-362] Fuse the decimal product checked against 38 digits`

Branch `varka-362b2-checked-product`.

**B0.**

    grep -c 'LONG_LANE_MAX_CHAIN_DEPTH' C/varka/VarkaEmitBudget.java   -> 1 or more (row 697 merged)
    grep -c 'columnSum' C/varka/VarkaDecimalLowering.java              -> 1 or more
    grep -c 'COLUMN_SUM' C/VarkaDecimalCompiler.java                   -> 1 or more
    grep -c 'static Overflow decimalOverflow' C/VarkaDecimalCompiler.java   -> 1 (360c1 merged)
    grep -c 'static Pair guardedPair' C/VarkaDecimalCompiler.java      -> 1

**Steps.**

1. `VarkaDecimalCompiler.checkedProduct(Multiply m, DecimalType type, ...)`: the two-lane test
   of part 362a2 first; the operands as pairs, a 64-bit one on the right (*[fix round 3]*: by
   the lines `productWords` has for it, the `signExtension` test, the swap and `rightLong`,
   copied); the four `PairMul` words
   with `words = 4`; the guard of 11.4.4 through `guardedPair` and `outside38`, with
   `mode = decimalOverflow(m.evalMode())` (11.3 decision 10; never `overflowOf` alone, whose
   LEGACY is `WRAP` and makes `GuardedRange` throw); the two slots of `upper` need nothing.
   `pair`'s switch gains the arm for `ProductForm.COLUMN_SUM`.
   `try_multiply` is the same node in TRY mode.
2. Tests, in `VarkaDecimalProductSuite`, each option set with `maxChainDepth(64)`,
   `methodByteBudget` and `cse` pinned: (1) `DECIMAL(38, 2) * DECIMAL(38, 2)`:
   `(10^19 - 1) * (10^19 + 1)` is `10^38 - 1` in every mode; `10^19 * 10^19` is null under
   LEGACY and TRY and declines the batch under ANSI; (2) the witness of a wrapped product,
   `77175736680830244327067381487379067960 * 20939809963070202291280` (61 digits, its low 128
   bits within `10^38 - 1`): null, never a value; (3) `DECIMAL(20, 2) * BIGINT` at the long's two
   extremes; (4) Polars's grid, scales 0, 1, 6, 18, 37 and 38 cubed, the kept-scale cells here,
   against Spark's `Decimal` (`m7/READING_POLARS.md` 7); (5) two shape hashes: LEGACY and TRY
   one, ANSI another; (6) 5000 seeded draws against Spark's `Decimal` in the three modes; (7) at
   `maxChainDepth(16)` the shape declines, and the decline's reason contains `chain deeper
   than MAX_CHAIN_DEPTH=16` (the text `Analysis.analyzeRoot` throws, the only place master
   words the cap; if the compile reports another text, stop and report it); (8) the two-lane
   refusals, as in part 362a2.
3. Coverage rows `d38s2 * d38s2b`, `d20 * l`, `try_multiply(d38s2, d38s2b)`, `d18 * d38`, each
   with a `domain` (the kernel can decline a batch under ANSI). The baseline's sections
   `DECIMAL(20, 2) w * l as a value, l a BIGINT` and `DECIMAL(38, 2) x * w as a value`, both
   densities, change from `Fusion.NONE` to `fusedFilter` (part 362a2 step 5's table). The
   warm-up gains form C.

*[Fix round 3.]* An earlier decline this part turns into a fuse (11.6's rule): 362a2's test
(6) holds "a kept-scale product past 38 operand digits names the column sum"; that product
fuses here, so its line becomes a value check under `VarkaDecimalPairSuite.DEEP`.

**The failure only test 2 catches:** a check that reads the low pair alone (arrow-rs's defect).
Plant it once (drop `upper` from the guard) and see test 2 fail.

    build/sbt 'catalyst/testOnly *VarkaDecimalProductSuite *VarkaCoverageSuite'   -> [success]
    build/sbt 'sql/testOnly *VarkaCoverageDifferentialSuite *VarkaDecimalBaselineQueriesSuite'
                                                                      -> [success]
    dev/varka_emit.sh "x * y" --columns x:dec38s2,y:dec38s2   -> ends FusedOutput(0)

**Not here:** a cut scale, an option, the gate.

#### Part 362b3, the proofs of form C (January 2027, full strength; not a gate before then)

`decimal128.smt2` gains, rendered from `columnSum` by `VarkaProofFiles`: the ring layer (the
sixteen products' weighted sum is `Xu * Yu`, stated whole and row by row); the carry layer (each
product free in `[0, (2^32 - 1)^2]`, the columns and the one carry pass give the four words, no
column past `2^64`); the `TWOS` folds; the check, `upper | outside = 0` exactly where
`|X * Y| <= 10^38 - 1`; and the plant as a `sat` witness. **What makes it hard:** the ring
identity over sixteen products is the largest polynomial the proof files have stated and may
answer `unknown`; the four-word carry layers took Z3 47 seconds in the branch's hand
transcription; choosing how to cut the claims so that both solvers decide them is a judgement, not
a transcription.

*[E1 rework, 28 November 2026.]* By the owner's decision E1 (a) this part waits for a
full-strength builder in January 2027; the text above is kept for that day. It starts from the
master of that day, after 362b2 and whatever proof parts of rows 360, 361 and 363 have merged,
and adds its layers to `decimal128.smt2` beside theirs. Nothing waits on it: 362c scores and
marks the row without it.

#### Part 362c, `[VARKA-362] Score the full product against its baseline`

After 362b2 merges: dispatch `benchmark.yml` for `VarkaDecimalBaselineBenchmark` at 256 bits and
with `-XX:MaxVectorSize=16`, as section 10.2 records the runs of #1797; commit the two runner
files; score predictions 4 to 6 of section 4 in section 10; mark the row in `PLAN.md`. The
decision rule: a section of this row whose `varka on` best time is not under its row path's at
256 bits is reported to the lead before anything is committed; at 128 bits every section ties,
since each declines (11.3 decision 8). *[E1 rework, 28 November 2026.]* Part 362b3 does not
count as left when the row is marked: the note adds "part 362b3, form C's proof layers, is
January 2027's, by the owner's decision E1 of 28 November 2026; not a gate".

### 11.7 Declines, census cells and benchmark sections, by part

| part | decline reasons that change | census | baseline sections that leave the row path |
|---|---|---|---|
| 362a1 | none | none | none |
| 362a2 | "rows 362 and 363's" splits into the column sum's and row 363's; the shortcut declines no more | `*` and `try_multiply` gain `dec^` | `DECIMAL(20, 2) x * y as a value, y a DECIMAL(17, 2)`; `DECIMAL(20, 2) x * y < z filter, y a DECIMAL(17, 2)` |
| 362b2 | the column sum's reason is gone; past the cap, the cap's own | none | `DECIMAL(20, 2) w * l as a value, l a BIGINT`; `DECIMAL(38, 2) x * w as a value` |
| row 363's 363b2 | (see `VARKA-363.md`) | `*` and `try_multiply` become `dec` by that plan's rule | (row 363's) |

### 11.8 What only a JVM can settle, with the command for the lead

1. That `PairMul`'s `props()` and fixtures are complete: `build/sbt catalyst/Test/compile` and the
   registry suites of part 362a1.
2. The heights on master against 11.4.5: `dev/varka_emit.sh "x * y" --columns
   x:dec38s2,y:dec38s2` after row 697; `FusedOutput(0)` means the checked product is
   within the cap.
3. Whether C2 folds `w & 0` and what the null rule costs: `dev/varka_emit.sh "x * y" --columns
   x:dec20s2,y:dec17s2 --asm`.
4. The proofs' solver times (part 362a3; part 362b3's in January 2027, E1): `dev/varka_prove.sh
   --solver both`.
5. Every speed: part 362c.

6. Whether a compile that stops at the cap reports `chain deeper than MAX_CHAIN_DEPTH=16` word
   for word: part 362b2's test 7.
7. That `VarkaExpressionCompiler.declines(..., new VarkaAdmission(Set.of(), true))` reaches the
   arm's own decline before a root's refusal: part 362a2's two-lane test.

### 11.9 Decisions for the owner

The decimal chain's owner decisions are numbered across its four plans: D1 to D3 in
`VARKA-360.md` section 12, D4 in `VARKA-361.md` section 12, D5 to D7 here, D8 in `VARKA-363.md`
11.9. All three below were answered by the owner on 28 November 2026 with the default.
*[E1 rework, 28 November 2026.]* E1, last below, is the owner's decision of the same day on
the proof parts of rows 346, 362 and 363; it carries no D number, being no question of this
chain alone.

- **D5. When is a 128-bit product null.** Answered: (a). The node `PairMul` multiplies two
  values of two 64-bit words each. One of its output words (the low word of the short form)
  is computed from the two low input words alone. (a) Every output word is null where any of
  the four input words is null, the rule the owner set on 27 November 2026 for `ScaleDown`,
  the node that divides by a power of ten; the lowering adds one AND and one OR for each word
  not read, and the node description needs nothing new. (b) An output word is null only where
  a word it reads is null: no extra operation, but a new validity kind in `VarkaNodeProps` and
  a second rule to keep beside `ScaleDown`'s. In every SQL shape the two agree, since a
  column's two words are null together. Applied: (a).
- **D6. A second way to handle signs in the 256-bit product, and a gate that skips it.**
  Answered: (a). The plan of 30 October proposed two emit options: `decimalWideSign` (multiply
  the magnitudes and put the sign back, against multiplying two's complement words, which is
  built) and `decimalWideGate` (a pass over the batch that takes a cheaper kernel when every
  value is small). The built form costs 5.4 to 5.6 ns a row on the EPYC 7763 where Spark's row
  path costs 372.1 to 499.7. (a) Neither is built in milestone 8; both go to `m10/SCOPE.md`
  with the spike loop that would reopen each: 0 lines. (b) Build the magnitude form as an arm
  after part 363b1 and measure it: about 300 lines and a runner dispatch. Applied: (a).
- **D7. The unfinished spike branches.** Answered: (a). `varka-362-spike` (644 counted lines of
  benchmark loops for this row, never run, four review findings open: its gate loops time the
  wrong thing) and row 363's `varka-363a3-spike` (one loop, never run). What they were to
  decide is decided in 11.3 from readings that are committed. (a) Neither is landed or run: 0
  lines. (b) Fix the findings, land and run them for the record: about 700 lines and two
  runner dispatches, changing no decision. Applied: (a).
- **E1. The proof parts that need a full-strength builder.** *[E1 rework, 28 November 2026.]*
  From 1 December 2026 the project is built by Sonnet-class agents only, for one or two months.
  (a) Move those parts to January 2027, when a full-strength builder is back, and ship the
  parts they prove before them with their differential tests and fuzz coverage; (b) build them
  before 1 December; (c) hold the parts they prove until the proofs land. **Answered on 28
  November 2026: (a).** Here: part 362b3, "January 2027, full strength; not a gate before
  then" (11.6). The same decision moves 363b3 (`VARKA-363.md` 11.9) and claim C of row 346's
  proofs (`VARKA-346.md` section 12, question 5). Every other part of this row is Sonnet-class.

## 12. What this row leaves rows 363, 351, 364 and 366 (28 November 2026)

Row 363 gets `productWords` (a cut product's exact 128-bit words), `PairMul` with `words = 4`
and its `Sign` slot, the guard of 11.4.4 and `ProductForm.CUT`. Rows 351, 364 and 366 of
`m9/PLAN.md` take nothing from this row directly: their multiplies are row 360b's `WideMul`.
