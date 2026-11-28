# VARKA-361: The widening decimal multiply, 64 by 64 to 128 bits

## 0. What exists now (admission check)

From master at `6e207c226fb` for Spark's sources and the plans, row 347's local branches for the
decimal leaf it builds on (unmerged), row 349's plan on its unmerged branch `varka-348-349-plan`
at `7b66e5601bd`, and the JDK in `/home/max/proj/openjdk-build/jdk25` (30 October 2026). Spark
paths are under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/` unless named; Varka
paths under `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`. The
row's text is the milestone plan's 2.8 ("361, the widening multiply") and its 10.3 item 2.

1. **Spark's result type, and where the setting is read.** `Multiply` over two decimals takes
   scale `s1 + s2` and precision `p1 + p2 + 1`, through `DecimalType.adjustPrecisionScale` when
   precision loss is allowed and `DecimalType.bounded` when not (`expressions/arithmetic.scala:
   630-638`); the setting is the node's own `NumericEvalContext.allowDecimalPrecisionLoss`
   (`:241-244`, `expressions/NumericEvalContext.scala:38-50`), so the type is resolved per
   expression and the compiler reads it from the node's `dataType`, never from the session. Both
   rules are the identity at precision 38 or less (`DecimalType.scala:144-146`, `:175-184`).
2. **The three modes.** The generated code is `changePrecisionExact(e1.$times(e2), p, s)` under
   ANSI and TRY and `changePrecisionOrNull` under LEGACY (`expressions/arithmetic.scala:294-310`,
   `failOnError` at `:223-228`); TRY wraps it in a `catch` that sets the result null (`:356-371`,
   interpreted `:384-391`), and `try_multiply` over numerics is `Multiply(l, r, EvalMode.TRY)`
   (`expressions/TryEval.scala:268-274`). ANSI raises `NUMERIC_VALUE_OUT_OF_RANGE.WITH_SUGGESTION`
   (`CastUtils.java:107-111`, `errors/QueryExecutionErrors.scala:106-120`). So LEGACY and TRY
   null and ANSI raises, each only where the exact product has more than `p - s` integral digits
   after the rescale to the result scale.
3. **The product is exact before that check.** `Decimal.*` multiplies `BigDecimal`s under
   `MathContext(39, DOWN)` (`Decimal.scala:523-524`, `:597`), exact for a product of 39 digits or
   fewer; `changePrecision` then sets the scale `HALF_UP` and fails where the precision exceeds
   `p` (`Decimal.scala:458-463`). Where the result scale is `s1 + s2` there is no rounding and
   the check is the precision alone.
4. **In this row's domain the check is dead, in every mode and under both settings.** With
   `|x| <= 10^p1 - 1` and `|y| <= 10^p2 - 1`, `p1, p2 <= 18`, the product is below
   `10^(p1 + p2) <= 10^36 < 2^120`, and `P = p1 + p2 + 1 <= 37`: never capped, and the exact
   product always fits `P` digits. LEGACY's null, ANSI's raise and TRY's catch never fire, as
   row 349's plan found for its own multiply (its item 0.4).
5. **An integral operand arrives as a cast, and keeps a 64-bit value.** Type coercion casts an
   integral operand to `DecimalType.forType`, `(3, 0)`, `(5, 0)`, `(10, 0)` or `(20, 0)`
   (`analysis/DecimalPrecisionTypeCoercion.scala:178-181`, `DecimalType.scala:125-137`), and an
   integral literal to the minimum precision of its value (`:166-175`), which folding makes a
   decimal literal **[I]**. The cast is null-safe, `to.isWiderThan(from)`
   (`expressions/Cast.scala:534-541`), and emits no check. A `BIGINT` keeps `|l| <= 2^63`, so
   `CAST(l AS DECIMAL(20, 0)) * y` is a 64 by 64 product. Its type is `P = 21 + p2`:
   - `p2 <= 17`: uncapped, `|l * y| < 2^63 * 10^17 < 2^120`, the check dead;
   - `p2 = 18`: `P = 39`. Under `adjustPrecisionScale` the scale is `max(s2 - 1, min(s2, 6))`
     (`DecimalType.scala:186-199`): `s2 <= 6` keeps it, a `(38, s2)` result whose check is dead
     since `2^63 * 10^18 < 2^123 < 10^38`; `s2 > 6` cuts it by one, a `HALF_UP` rescale down,
     which is 363's. Under `bounded` the scale is kept and the check dead;
   - two `BIGINT`s cast, `(20, 0) * (20, 0)`: `P = 41`, `(38, 0)` under both settings, and
     `|l1 * l2| <= 2^126 < 10^38`, the check dead.
6. **Where q6's product runs.** TPC-H q6 is `sum(l_extendedprice * l_discount)`: the product is
   an aggregate function's input, which `HashAggregateExec` evaluates, and Varka replaces only
   `ProjectExec` and `FilterExec` (`VarkaProjectExec.scala`, `VarkaFilterExec.scala`). **[I]**:
   step 1 prints the physical plan to confirm. So q6's product fuses where a query projects or
   filters on it, not in q6 itself, which the milestone plan's 2.8 already says of the query.
7. **The vector multiply, as C2 selects it.** `MUL` on a `LongVector` keeps the low 64 bits; there
   is no multiply-high on any lane type (the milestone plan's 2.8). On x86 a `MulVL` is
   `evpmullq` with AVX-512DQ at cost 500 (`x86.ad:6018-6031`), otherwise the emulation
   `vmulL_reg` (`:6071-6094`), and `vpmuludq` or `vpmuldq` at cost 100 where
   `MulVLNode::has_uint_inputs` or `has_int_inputs` holds (`:6096-6118`), so the matcher takes
   the one-instruction form on AVX-512 hosts too. Each predicate needs **both** inputs to match
   (`vectornode.cpp:2229-2237`): an input fits 32 unsigned bits when it is an `AndV` with a
   `Replicate` of a constant at most `0xFFFFFFFF`, or a `URShiftVL` by a constant of 32 or more
   (`:2189-2211`); it fits 32 signed bits when it is a `VectorCastI2X` to long or an `RShiftVL` by
   32 or more (`:2213-2227`). Three consequences:
   - a signed high limb (`x >> 32`) times an unsigned low limb matches neither and is emulated,
     so all four limb products must be unsigned and the sign corrected after;
   - the mask and the shift count must be constants in C2's graph. The IR's `BitOp` takes its
     right operand as a vector (`VarkaVectorWalk.java:830-850`) and a `LiteralSlot` is a broadcast
     of a table load (`VarkaVectorIR.java:468`), so a product composed from today's nodes with a
     literal mask gets the emulation; the emitter's own constants, as `emitBitCount` pushes them
     with `loadConstant` before `and(long)` (`VarkaVectorWalk.java:876-880`), are constants;
   - `AndVNode` has an `Identity` and no `Value` (`vectornode.cpp:2094-2125`): it folds an `AND`
     with an all-ones or all-zeros replicate and `AND x x`, and leaves two constant replicates
     unfolded **[V]**. So `broadcast(c).and(0xFFFFFFFF)` stays an `AndV` unless the limb is 0
     (the `AndV` folds to the zero replicate) or exactly `0xFFFFFFFF` (the two replicates are one
     node after GVN, and `AND x x` is `x`), where the pattern is lost **[I; `--asm` settles it]**.
   The AVX2 `RShiftVL` of a long is a five-instruction emulation (`x86.ad:6923-6949`), one
   `evpsraq` on AVX-512. On aarch64 a NEON `MulVL` is two scalar multiplies through general
   registers (`aarch64_vector.ad:1041-1056`) and SVE's is one `sve_mul` (`:1060-1066`); no limb
   rule exists there.
8. **The scalar products.** `Math.multiplyHigh` and `unsignedMultiplyHigh` are C2 intrinsics
   (`VARKA-359.md` 0, item 9). Their Java bodies are Hacker's Delight 8-2 on signed limbs
   (`Math.java:1403-1418`) and that plus `(y & (x >> 63)) + (x & (y >> 63))`
   (`:1431-1437`), the inverse of the correction 2.3 applies. A scalar 64 by 64 product is
   `Math.multiplyHigh(a, b)` and `a * b`, with no `abs` (`READING_TRINO_DECIMAL.md` 3).
9. **The IR.** `IntArith(MUL, WRAP)` at the long lane exists (`VarkaVectorIR.java:672`) and
   lowers to one `MulVL`; nothing computes a high word. 347's leaf (`varka-347-reader`) yields a
   64-bit decimal's low word; row 349's plan builds the 64-bit value `Scaled(unscaled, type)`
   with `|u| <= 10^p - 1` (row 348's plan, 2.1, same branch); 359 plans the pair as two word
   nodes in a compile-time `Pair(lo, hi, type)`, shared work parked once (`VARKA-359.md` 2.2) and
   a store of two roots (2.4), meeting 157's store helper (`VARKA-157.md` 2.1).
10. **The bounds are SMT lemmas.** `VarkaRangeAnalysis` is int-only, so "no intermediate passes
    `2^64`" is proven, not analysed (the milestone plan's 2.8, "Where the bounds live").
11. **Row 104's option C is this construction's high half** (`VARKA-104.md` 3.3): a checked
    `BIGINT` multiply overflows exactly where the high word is not the low word's sign. Its plan
    says that if C is built there first, it is built as a helper this row reuses.
12. **The baseline reaches this row once.** `VarkaDecimalBaselineBenchmark`'s chain
    `(x + y) * z - x` at `(9, 2)` has its product at `(10, 2) * (9, 2) = (20, 4)`, this row's,
    beside 349's sum and 360's difference; the row path pays 168.7 ns a row for the chain on the
    EPYC 7763
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`).
    There is no product alone, no `BIGINT` column and no q6 type.
13. **359's spike has no loop for this row.** Its arithmetic loops are the 128 by 128 product,
    the `10^38` check and the rescale (`VARKA-359.md` 2.1); it is not built yet (its section 10 is
    empty), so no reading exists to take a design from.

### What differs from the row text and the milestone plan

- **No overflow check anywhere in the row** (items 4, 5): no `GuardedRange`, no use of row 481's
  `NULL` mode, one kernel per shape in all three modes and under both settings of
  `allowPrecisionLoss` where the resolved types agree.
- **`BIGINT * DECIMAL(18, s)` splits at `s = 6`**: kept scale, this row; cut scale, 363.
- **A node, not a composition** (item 7): today's nodes cannot reach `vpmuludq`, and the two words
  share their limb products, so one node per word with the work parked once, 359's rule.
- **The planted carry fault is live for one negative operand too**, not only two, and
  `-(10^18 - 1)` squared does not catch it: in a million random draws within 18 digits the
  planted order is wrong for 1525 pairs of one sign each and 117604 pairs of two negatives, never
  for two non-negatives, and right at `+-(10^18 - 1)` squared
  (`results/VARKA-361-planted-carry.txt`). The cross-check names a witness of each class, the
  one-negative case included. The text of rows 361 and 362, "two negative operands", is the
  owner's; it is flagged here and not edited.
- **The constant-limb case has two edges the row text lacks**: a zero limb and a limb of exactly
  `0xFFFFFFFF` lose the pattern (item 7, the second from the source and not yet read in `--asm`),
  so the compiler drops a zero limb's products, and the other becomes a shift and a subtraction
  if the reading confirms it.
- **A pair times a 64-bit value is 362's**, the narrowest case of its column sum.
- **q6 itself does not fuse** (item 6); the row's example fuses as a projection or a filter.

## 1. Goal and non-goal

`x * y` for two 64-bit-valued decimals - each of precision 18 or less, or an integral widened by a
cast - whose product type exceeds precision 18, computed as the exact 128-bit product into the
lane pair, the high word from 32-bit limbs that C2 lowers to `vpmuludq`, in every mode with one
kernel, the forms measured against each other first. Not this row: a pair operand (362), a product
whose scale is cut (363), a product of precision 18 or less (349), the pair's store (359, 157),
division (out of the milestone).

## 2. Design

### 2.1 The forms, and the measurement that picks

- **L, the limb network.** Four `vpmuludq` on `AND 0xFFFFFFFF` and `LSHR 32` limbs in Hacker's
  Delight's carry-free order, the low word from the same network, the sign corrected after (2.3).
- **L+M, the low word by `MulVL`.** As L, with the low word an `IntArith(MUL, WRAP)`: one
  `evpmullq` on an AVX-512DQ host, the emulation elsewhere. Option `decimalWideMulLow` (`LIMBS`,
  `MULVL`), `Reason.MACHINE`.
- **The sign word, three ways.** `x >> 63` (`ASHR`, the five-instruction emulation on AVX2),
  `-(x >>> 63)` (`LSHR` then a subtraction from zero, two instructions everywhere), and a compare
  with a blend (a mask, the Java fallback at two lanes on x86). Option `decimalSignWord` (`ASHR`,
  `NEG_LSHR`, `BLEND`); 360's sign extension takes the same option.
- **S, the scalar loop.** `Math.multiplyHigh(a, b)` and `a * b` per row: the reference arm under
  359's answered question 3 (a), measured, not emitted.
- **E, the emulated control.** The same network with the masks from the literal table, so every
  product is `vmulL_reg`: spike only, to price item 0.7's rule.
- **A, adjacent lanes,** differs from P here only at the store, since the inputs are 64-bit values
  read from 347's low words; it takes 359's verdict for the pair store and is not measured again.

**The spike, before any engine code**, as loops added to 359's `VarkaDecimalPairSpikeBenchmark`
(in 359's spike pull request if it has not merged, its own otherwise): each checked against
`BigInteger` before it is timed and driven to level-4 code, over 359's L2, L3 and past-L3 ladder,
at 512, 256 and 128 bits on the laptop (its 512-bit numbers double-pumped and labelled so) and 256
and 128 on the EPYC 7763 through `benchmark.yml`:

1. `x * y` over two `DECIMAL(15, 2)` columns read from sixteen-byte slots, the pair stored;
2. `x * 10^k` for `k = 2` (one limb) and `k = 12` (two limbs), the form 360's rescale takes;
3. `l * y`, a `BIGINT` column times a `DECIMAL(18, 4)`.

Each in L, L+M, the three sign words, S and E. **The verdict, per width and host, names the default
of each option; the losing arms stay behind their options**, as the house rule has it. Laptop
numbers are labelled as such; the defaults come from the runner.

### 2.2 The node: one word of the product

    WideMul(VarkaVectorIR left, VarkaVectorIR right, Word word, boolean rightOneLimb)

on the long lane, `Word` being 359's `LOW` or `HIGH`: the word of the exact 128-bit product of two
long values read as signed 64-bit integers. It renders `wmul:lo(<left>,<right>)` (or `:hi:`, and
`:1` for one limb). The two words of one product share one network: the first word a body emits
computes both and parks the other in a slot keyed by the pair of children, which its sibling
loads, as 359's word leaves share one load (`VARKA-359.md` 2.2); a body split by the budget
recomputes it per group. `rightOneLimb` is set by the compiler only for a non-negative constant
below `2^32` (`10^k` for `k <= 9`); it is part of the shape key. The reference evaluator answers
`a * b` and `Math.multiplyHigh(a, b)`; the IR fuzzer draws the node in a sequence of its own seed;
`VarkaCanonicalIr` gains its parse arm. Row 360 lands the node with constant multipliers (its
360b); this row adds the column-by-column product to it; row 104's option C takes the same node.

The compiler (`VarkaDecimalCompiler`, row 349's `Multiply` arm) builds the pair
`Pair(WideMul(x, y, LOW), WideMul(x, y, HIGH), (P, S))` where both operands are 64-bit-valued and
the resolved scale is `s1 + s2`; a 64-bit-valued operand is 348's `Scaled` or an integral under its
coercion cast (`WidenLane` from the int and narrow lanes, the long itself), its bound `2^63` where
the cast's precision is 20. A literal operand stays in the long table, its limbs split by the
emitter's constants, hoisted by C2.

### 2.3 The lowering

With `M = 0xFFFFFFFF` an emitted constant and every shift count an emitted `int`:

    xl = x & M;  xh = x >>> 32;  yl = y & M;  yh = y >>> 32       4 (y's hoisted if a literal)
    p0 = xl * yl;  p1 = xh * yl;  p2 = xl * yh;  p3 = xh * yh      4 vpmuludq
    t  = p1 + (p0 >>> 32)                                          2
    u  = p2 + (t & M)                                              2
    hi = p3 + (t >>> 32) + (u >>> 32)                              4
    lo = (u << 32) | (p0 & M)                                      3
    hi = hi - (y & sign(x)) - (x & sign(y))                        4, and two sign words

about 25 lane calls a lane group, and 13 with one limb (`p2`, `p3` and `x & sign(y)` gone, `hi = t
>>> 32`, `lo = (t << 32) | (p0 & M)`). No intermediate wraps: `t` and `u` are at most `(2^32 - 1)^2
+ 2^32 - 1 < 2^64` and `hi` before the correction at most `2^64 - 1`. The correction is exact
modulo `2^64` because `X * Y = x_u * y_u - 2^64 * ([x < 0] y_u + [y < 0] x_u) + 2^128 [x < 0][y <
0]`, and the result read as a signed 128-bit value is `X * Y` since `|X * Y| <= 2^126`. If the
`--asm` reading confirms that a constant limb of exactly `0xFFFFFFFF` loses the pattern (item 0.7),
that product is written `(x << 32) - x`. The emitter never
writes a negative mask under a multiply: `is_replicate_uint_constant` compares the constant signed
(`vectornode.cpp:2189-2194`), so a negative mask would pass it while keeping high bits **[I, not
run; reported, not this row's to fix]**.

### 2.4 At 128 bits, and on aarch64

The network has no compare and no mask: L with the `ASHR` or `NEG_LSHR` sign word is all lanewise
arithmetic, so the two-lane fallback that sinks the pair's compares on x86 (`VARKA-359.md` 0, item
9) does not reach it **[I]**; the spike's 128-bit column says whether it beats S there, and the pair
store, which is 359's, decides whether a projection fuses. Row 503's rule declines a condition
root at two long lanes on x86, so `x * y < z` declines there by construction. On aarch64 the
products are scalar under NEON and one `sve_mul` each under SVE, so the network does four full
multiplies where S does two; the Neoverse N2's reading is the census cells only, `benchmark.yml`
having no Arm runner.

### 2.5 Compiler, reference, cost, warm-up

`VarkaDecimalCompiler`'s `Multiply` arm gains this case beside 349's, ahead of its decline naming
361; `try_multiply` is the same node. The budget weighs `WideMul` by its lane calls (one weight
per word, the shared network counted once); the cost model gets a feature on row 352's
awaiting-refit list; the warm-up's long block gains the network, held by `VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The product table**, against `BigInteger`, at 2, 4 and 8 long lanes, lengths 1, 3, 37 and 64,
   both bodies: `+-(10^p - 1)` at `p = 9, 15, 17, 18` in every sign pair; `Long.MIN_VALUE` and
   `Long.MAX_VALUE` under a `(20, 0)` cast, squared and times `+-(10^17 - 1)`; low limbs of 0,
   1, `2^31` and `2^32 - 1`; the constants `10^k` for `k = 0..18` and one with a limb of exactly
   `0xFFFFFFFF`; every null pattern.
2. **The random cross-check** of every arm against `BigInteger`, with two negative operands and
   the two witnesses of `results/VARKA-361-planted-carry.txt` among the named draws, one with one
   negative operand and one with two, both of which detect the planted order of 3.1;
   `-(10^18 - 1)` squared, which the row text names, stays a named draw
   but passes the planted order (the same file), so it is not counted as detecting it; and
   QuestDB's `(2^64 - 1) * (2^34 - 1)` shape scaled into range.
3. **The type rule against Spark's own**: a unit test runs `Multiply.resultDecimalType` over every
   `(p1, s1, p2, s2)` with `p <= 18` and the four integral casts, under both settings, and holds
   the compiler's fuse-or-decline to item 0.5's table, naming 349, 362 or 363 where it declines.
4. **One kernel in every mode**: `VarkaEmittedBytesSuite` compiles each coverage row under LEGACY,
   ANSI and TRY and asserts one shape hash, row 349's test 3.
5. **The instruction mix**: `dev/varka_emit.sh --asm` on the EPYC 7763 and on the laptop shows four
   `vpmuludq` and no `vpmulld` sequence for a column product, two for a one-limb constant, the
   pattern surviving GVN for `10^2`, `10^12` and a `0xFFFFFFFF` limb; recorded in section 10.
6. **The SMT proofs**, `sql/varka/proofs/decimal128.smt2` (359's file, a section of its own) under
   Z3 and cvc5, over integers in 2.8's two layers at full width. The ring layer: with
   `x_u = xh * 2^32 + xl`, limbs in `[0, 2^32)`, the four products' weighted sum is `x_u * y_u`, a
   polynomial identity both solvers normalise. The carry layer, linear: `p0..p3` free in
   `[0, (2^32 - 1)^2]`, the network as emitted (Java's wrapping as `mod 2^64`, the shifts as
   division and remainder by `2^32`) gives `hi * 2^64 + lo = p3 * 2^64 + (p1 + p2) * 2^32 + p0`
   with no wrap in `t`, `u` or `hi`; and the correction, stated with `ite(x < 0, y_u, 0)`, gives
   the signed product. The planted order (3.1) as a `sat` witness. A 16-bit-limb version of the
   same claims runs as the reduced-width cross-check only. The file's times are stated before and
   after.
7. **Coverage rows**, a "DECIMAL widening multiply" family: `d15 * d15b` (q6's types, as a value
   and as `d15 * d15b < d38`), `d9 * d9b`, `d18 * d18b`, `l * d9`, `l * d18s4`, `l * l2` under
   explicit casts, `d18 * 3`, `try_multiply(d15, d15b)`, each naming its verification for row
   490's check; the projections after 359b's store.
8. **The differential, the matrix, the fuzzers**, and the generated files in `ADDING_A_TYPE.md`'s
   order, no existing shape's bytes moving.

### 3.1 The planted fault

Under a test flag the middle column is summed in one word, `t = p1 + p2 + (p0 >>> 32)` and
`hi = p3 + (t >>> 32)`, QuestDB's `a0 * m1 + a1 * m0` (`READING_QUESTDB.md` 9): refuted by the
carry layer as row 506 renders it, and failed by the cross-check of test 2, whose witnesses
include one negative operand as well as two, one of row 504's planted faults.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code**: `VarkaDecimalBaselineBenchmark`
gains a `BIGINT` column and `DECIMAL(15, 2)` columns, and the sections `x15 * y15`,
`x15 * (1 - y15)` (row 349's subtraction under this row's product, q1's form), `l * x9` and
`l * x18` as projections, and `x15 * y15 < z` as a filter, run on the EPYC 7763 at 256 and 128 bits.
Row 360's additions go in the same pull request.

Predictions, registered before the spike and the build:

1. **L against S** at 256 bits on AVX2: within a factor of 1.5 of each other, S ahead, since a
   lane group of four needs about 25 vector operations against S's two scalar multiplies per row;
   at 512 bits on the laptop L ahead by up to 2 times.
2. **L+M** wins at 512 bits on the laptop (one `evpmullq` against three operations) and loses on the
   EPYC 7763, where `MulVL` is the emulation.
3. **The sign word**: `NEG_LSHR` beats `ASHR` on AVX2 and ties it on AVX-512; `BLEND` ties
   `NEG_LSHR` at 256 bits and loses at 128.
4. **E** is 1.5 to 3 times slower than L at 256 bits: the C2 rule matters.
5. **The build against the baseline** at 256 bits: the product projection 10 to 40 times faster
   than the row path, which multiplies two `BigDecimal`s and allocates three.
6. **At 128 bits on x86** the projections still beat the row path, by 3 to 10 times, since the
   network has no mask.
7. **No existing shape's bytes move**; the `option_arms` digests move with the two options.

## 5. Order, files and size

1. **The reachability reading** (item 0.6): the physical plans of q6's product inside `sum`, as a
   projection and as a filter, printed on the laptop under a heavy slot, recorded in section 10.
2. **The baseline addition** (4), with 360's.
3. **The spike loops** (2.1), with 359's spike or after it; the verdict in section 10.
4. **361a, the column product on row 360's node.** Row 360 lands `WideMul` with constant
   multipliers in its 360b (`VARKA-360.md` 5): the node, its reference arm, grammar and parse arm,
   the one- and two-limb constant forms and `decimalSignWord`. This row adds the
   column-by-column product of two non-constant operands, `decimalWideMulLow`, the compiler case
   of 2.2, the column proofs, tests 1 to 6 and the filter coverage rows, and the `--asm` reading.
   Row 104's option C, the checked `BIGINT` multiply, takes the same node: one helper, as
   `VARKA-104.md` 3.3 asks.
5. **361b, after 359b's store**: the projections' rows, the measurement against the baseline at
   both widths and 512 bits on the laptop, the predictions scored.

| file | what |
|---|---|
| `varka/VarkaVectorWalk.java`, `varka/Slots.java` | the column network on 360b's node |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalWideMulLow` |
| `VarkaDecimalCompiler.java` (row 349's) | the `Multiply` case of 2.2 |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weight, feature, reach |
| `sql/varka/proofs/decimal128.smt2`, `VarkaProofFiles.java` | 3 item 6 |
| tests | product table, cross-check, type sweep, one-kernel test, coverage rows, fuzz sequence |
| benchmark | the baseline sections, the spike loops, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `census.tsv` |

**Size: medium, in four pull requests after the reading** (baseline with 360's, spike loops, 361a,
361b): the spike loops about a day, 361a about three, 361b one. Test time: the product table and the
cross-check are pure kernels of the slot table's size, seconds; the proof section is priced from
its spiked form at VARKA-245's observed ratio in the calibration table (about 0.8), under five
seconds per solver. It rests on 347, 348 and 349 (the value and the arm), 359 (the pair, the proof
file and the store), 360b (the node) and 503 for the 128-bit rule.

## 6. What it unlocks in the census

None alone. Under the milestone plan's 2.8 and 359's answered question 2, `*` and `try_multiply`
count only when they fuse at every precision pair to 38, which needs 362's full product and 363's
rescale; this row moves them nearer and moves the count by 0.

## 7. Out of this row, and declines that stay

Out: a pair operand, 128 by 64 or 128 by 128 (362); a cut scale, including `BIGINT *
DECIMAL(18, s > 6)` (363); the pair's store (359b, 157); row 104's checked `BIGINT` multiply, which
takes this node.

Declines that stay, each naming its row: an operand above 18 digits that is not an integral cast
(362), a cut result scale (363), a decimal in an int-driven kernel, a negative scale, and at two
long lanes on x86 a condition root (503).

## 8. Open points for the lead

Both answered by the lead on 30 October 2026, and folded in above: row 360 lands `WideMul` with
constant multipliers and this row adds the column-by-column product, row 104's option C taking the
same node (section 5); and the options' defaults come from the runner's verdict on the spike,
committed before 361a.

## 9. Done when

Row 361's text holds, with the corrections above: the spike's verdict is committed per width
before the build; the widening product fuses for every 64-bit-valued operand pair whose resolved
scale is `s1 + s2`, in every mode with one kernel, and declines naming 362 or 363 elsewhere; its
instruction mix read by `dev/varka_emit.sh --asm`, the constant-limb case included and surviving
GVN; the ring identity and the carry network proven by SMT over integers at full width in
`decimal128.smt2` under both solvers, a reduced width as a cross-check only; the planted fault
refuted by the proof and failed by the cross-check, with a witness of each sign class; the
measurement against the baseline committed at both widths with its predictions scored; and the PR
build and the option matrix pass.

## 10. Outcome

### 10.1 Step 1: the reachability reading, on the laptop

Printed on 9 November 2026 in a Varka session built as the benchmarks build it
(`VarkaArrowSessions.createSession(name, true, false, "local[1]")`), over the decimal baseline's
table with this row's columns, Arrow-cached; the trees, the suite and the command are in
`results/VARKA-361-reachability.txt`. Item 0.6 holds:

- **q6's form, `sum(x15 * y15)`**, with and without a `WHERE`, and q1's `sum(x15 * (1 - y15))`:
  the product is inside `HashAggregateExec`'s aggregate function, so no Varka node sees it. A
  `WHERE` beside it fuses its own condition as a `VarkaFilterColumnarToRow` below the aggregate,
  and the product stays above, in the aggregate.
- **As a projection**, `x15 * y15 AS a`, the product is a `ProjectExec`'s expression, and **as a
  filter**, `x15 * y15 < z15`, a `FilterExec`'s condition above the inferred null tests, which
  fuse: the two places this row's product fuses.
- **`l * x18`** shows coercion's `CAST(l AS DECIMAL(20, 0))` under the product in a
  `ProjectExec`, item 0.5's 64-bit value under a cast.

### 10.2 Step 2: the baseline addition, with row 360's

Built 9 November 2026 on #1422 (row 577's remainders, which first keyed the class's filter
sections on the two-lane refusal, `sections(boolean)`; merged into master since):
`VarkaDecimalBaselineBenchmark` gains 22 sections, eleven shapes at each null density. Row 360's: `x20 + y20`, `x20 - y20s4` (a
`DECIMAL(20, 4)`), `x38s2 + y38s2` (two `DECIMAL(38, 2)`, the sum capped at 38) and `l + x9`,
`l + x18` as projections, and `x20 + y20 < z20` as a filter. This row's: `x15 * y15` (q6's
types, `DECIMAL(31, 4)`), `x15 * (1 - y15)` (q1's form), `l * x9` (`DECIMAL(30, 2)`) and
`l * x18` (`DECIMAL(38, 4)`, precision 39 capped with the scale kept) as projections, and
`x15 * y15 < z15` as a filter. The columns `x15`, `y15`, `z15`, `z20`, `y20s4`, `x38s2`,
`y38s2` and the `BIGINT` `l` come after every earlier column, so the earlier sections' values
are unchanged; `l` is a hash shifted right by a hashed count, every bit length to the full
range. The projections are `NONE`; the two filters are `NULL_TESTS`, the inferred null tests
fused below the row path's sum or product, and `NONE` where the session refuses a 64-bit
condition, the 128-bit companion's host. `VarkaDecimalBaselineQueriesSuite` pins the new
columns' types, every result type, the two filters' comparison types and their operands' no
null, and the classes with the refusal off and on. Left: the EPYC 7763 runner results at 256
and 128 bits, committed in the class's two results files; then step 3, the spike loops, and
361a, which rests on row 360's 360b for the `WideMul` node (5, step 4).

## 11. What is left, cut into parts a Sonnet-class agent can build (28 November 2026)

*New section, written on 28 November 2026 by a planner that built nothing. Sections 0 to 10 above
are unchanged, word for word. This section replaces section 5's items 3 to 5 (the spike loops, 361a,
361b) and the two edges of the constant limb in 0 item 7 and 2.3, and says so where it does;
sections 12 and 13 are new too. Code facts were read on master `6dafb8bd2f6` (worktree
`/home/max/proj/varka-m9-read-w`) and on the branches named below, in
`/home/max/proj/vecbricks-varka`. No JVM was started; 11.9 lists what only a JVM settles. Row 360's
plan, `VARKA-360.md` section 11, is this row's base: its parts 360b1 and 360b2 land the node and the
compiler helpers this row builds on, and this section restates what it uses of them.*

Paths: `CODEGEN` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`,
`VARKA` is `CODEGEN/varka`, `TJ` is
`sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka` and `TS` the
same path under `src/test/scala`. `DC` is `CODEGEN/VarkaDecimalCompiler.java`.

### 11.1 The state on 28 November 2026

| piece | where | state |
|---|---|---|
| step 1, the reachability reading | master, `results/VARKA-361-reachability.txt` | done; section 10, step 1 |
| step 2, the baseline's five sections and their runner results | master (#1460, #1475, #1797) | done; 10.2. Every section runs the row path on both arms |
| step 3, the spike loops 14 to 16 | `varka-361-widening-multiply` | built on 20 November, compiled, never run; not a pull request |
| 361a, the column product in the compiler | `varka-361a-column-product` | built on 20 November on top of row 362's old stack; one suite run once |
| the option `decimalWideMulLow` | `varka-361a2-mul-low-option` | built, compiled only; its review found `VarkaEmitOptionSuite` failing |
| `WideMul` on master | none | `VARKA-360.md` 11, part 360b1, lands it |

The premises, each a command run on 28 November 2026:

    git -C /home/max/proj/vecbricks-varka branch --list 'varka-361*'
        varka-361-widening-multiply, varka-361a-column-product, varka-361a2-mul-low-option
    git -C /home/max/proj/varka-m9-read-w grep -c 'wideningProduct\|longValued' -- sql/catalyst/src
        (prints nothing: neither name is on master)
    grep -c '^DECIMAL(15, 2) x \* y as a value' \
      sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt
        4 (two densities, each a title line and a table head)

**The branches.** All three have their merge base at `767dcddce50` (20 November 2026), 164
first-parent commits behind master `6dafb8bd2f6`, under row 489's node descriptor and under the
three pull requests of 360a. None can be rebased as it is: each sits on row 362's old branches
(`varka-362-spike`, `varka-362a-shortcut`, `varka-362a2-pairmul`), which are not on master.

| branch | holds | ahead of its base | use |
|---|---|---|---|
| `varka-361-widening-multiply` | spike loops 14 to 16 (420 lines of benchmark, 29 of suite), one commit on `varka-362a2-pairmul` | 25 (1 its own) | **361s takes that one commit's two files' hunks** |
| `varka-361a-column-product` | `wideningProduct`, `longOperand`, `oneLimb`, `pairWidening` in the compiler; `VarkaWideningProductSuite`; two column tests of `VarkaWideMulSuite`; the column proof layer; coverage rows; 738 counted lines in 2 commits | 27 (2 its own) | **361a and 361p take its hunks**, applied by hand: it reads row 362's `ProductForm`, which this row does not have |
| `varka-361a2-mul-low-option` | the option `decimalWideMulLow`; 182 counted lines in 3 commits | 30 (3 its own) | **361b takes its hunks**, with the review's three faults fixed |

### 11.2 The parts

| part | what | counted lines | builder | starts from | waits on |
|---|---|---|---|---|---|
| 361a | the product of two 64-bit values past 18 digits, in the compiler | 600 to 750 | Sonnet-class | master, hunks of `varka-361a-column-product` | `VARKA-360.md`'s 360b1, 360b2, 360b1p (the totals of `VarkaWideMulSuite`; fix round 3) and, in the compiler file's queue, 360c1 |
| 361p | the column product's proof layer and its planted carry | 200 to 300 | Sonnet-class | master | 361a, 360b1p |
| 361b | the options `decimalWideMulLow` and `decimalSignWord` | 300 to 400 | Sonnet-class | master, hunks of `varka-361a2-mul-low-option` | 361p |
| 361s | the spike loops 14 to 16 | 450 to 550 | Sonnet-class | master, hunks of `varka-361-widening-multiply` | #1799 merged |
| 361c | the runner readings, the instruction mix, the options' defaults by rule, the row closed | 80 to 150, and results files | Sonnet-class, after the lead's dispatches | master | 361b, 361s |

No part of this row is full strength: the node, its lowering and its registries are row 360's
360b1, which is; what is left here is a compiler arm over that node, its tests, two options by
an existing template and a benchmark port.

**The order and why.** 361a first: it is what fuses, and row 362's shortcut product is cut on
top of it. Then 361p, then 361b, in that order and no other: both add a test to
`VarkaWideMulSuite`, and the totals the sheets give (8 after 361p, 9 after 361b) hold only so.
361s is independent of all three and can be built at any time after #1799; it only feeds 361c.
This departs from section 5, which has the spike before 361a: the spike's verdict names the
defaults of two options and nothing else, and 361a builds the forms the old branch proved (the
`ASHR` sign word, the limbs' low word), so the build does not wait for a reading (decision D4
of section 12).

**The whole chain's order, and the names the rows share,** are stated once, in `VARKA-360.md`:
11.2, "The whole decimal chain, in order (rows 360 to 363 and row 697)", where this row's parts
are steps 5 to 7 and the measurements beside the queue; and 11.7, "The names the four rows
share", which gives the signature of every name this plan takes from row 360 or hands to rows
362 and 363. Where a spelling here and there differ, that table holds. The three branches row
360 waits on (`varka-360a-spike`, `varka-360a-sums`, `varka-360a-bigint-cast`) are merged by the
lead before any part of this row starts; no part here starts from one.

### 11.3 Decisions taken here, each with its evidence

1. **Row 361 lands before row 362, as the close path orders them** (the close path's section of
   `m8/PLAN.md`), though the old branches were stacked the other way. So this row brings the two
   predicates it needs, `longValued` and `pairWidening`, and not row 362's enum `ProductForm`; row
   362 adds the enum and must keep its `WIDENING` case equal to `pairWidening`.
2. **The node is row 360's `WideMul`, unchanged**: `WideMul(left, right, Word word, boolean
   rightOneLimb)`, lowered by `VarkaDecimalLowering.productWord(options, x, y, word,
   rightOneLimb, true, true, faults)`, both operands read signed. `options` is its first
   argument from 360b1 on (`VARKA-360.md`, decision 3 of its decisions section), so 361b changes
   no signature.
   This row adds no IR node. The lowering already handles two column operands: the constant case
   of 360b1 is the same network with a literal slot as `right`.
3. **The two edges of the constant limb in 0 item 7 cannot occur, so their rewrites are not
   built.** Item 7 feared a limb that C2 folds: a zero limb, or one of exactly `0xFFFFFFFF`,
   written as `broadcast(c).and(0xFFFFFFFF)` with `c` a constant in C2's graph. In the lowering
   as built the multiplier is never such a constant: a SQL literal and a power of ten are both
   `LiteralSlot`s (`sink.longSlot(c)`), a broadcast of a load from the kernel's `long[]`
   argument, which C2 cannot fold; only the mask `0xFFFFFFFF` and the shift counts are
   `WordConst`s. So each limb is `AndV(x, Replicate(0xFFFFFFFF))` or `URShiftVL(x, 32)` with `x`
   unknown to C2, the two patterns `MulVLNode::has_uint_inputs` reads, whatever the literal's
   value. A zero high limb is handled before C2 sees anything: the compiler sets `rightOneLimb`
   from the literal's value (`oneLimb`, below), which drops two of the four products. The
   `--asm` reading of 361c confirms it; if it shows an emulated multiply, that is a finding
   against this decision and the part stops.
4. **The check is dead and one kernel serves every mode** (section 0 items 4 and 5, restated by
   `verify_361.py` over every operand pair): for two 64-bit values whose result scale is
   `s1 + s2`, `|x * y|` is within the result type and below `2^127`. Of 36100 operand pairs
   (every `DECIMAL(p, s)` with `p <= 18`, and a `BIGINT` under its cast) 5644 are a long
   multiply (row 349's), and of the rest 30432 keep the scale under precision loss and 30456
   without it; the 24 that are cut are `BIGINT * DECIMAL(18, s)` for `s` from 7 to 18 in both
   orders, under precision loss only: row 363's.
5. **At two long lanes on x86 the arm declines in the compiler**, with row 360's
   `VarkaDecimalCompiler.TWO_LANE_WIDE_REASON`, tested first by `sink.declinesTwoLaneChecks()`,
   until row 698 of `m9/PLAN.md` shows a two-lane pair load that wins (`VARKA-360.md` section 11,
   decision 4 has the argument). Section 2.4's hope that the mask-free network beats the row path
   there is not tested by this row: the pair's store and every decimal load fall back at two
   lanes whatever the network. *[28 November 2026.]* Once row 597's part 597b merged, the arm
   writes no two-lane test and its tests expect `TWO_LANE_READ_REASON`, by the paragraph "The
   two-lane rule once row 597's part 597b merges" of `VARKA-360.md`, after its table of shared
   names.
6. **The options: `decimalWideMulLow` (`LIMBS`, `MULVL`) and `decimalSignWord` (`ASHR`, `NEG_LSHR`,
   `BLEND`), both `Reason.MACHINE`, defaults `LIMBS` and `ASHR`**, the forms the proof layer of
   360b1p and 361p covers. The defaults move only by this rule, applied in 361c to the 256-bit EPYC
   7763 file of the spike with `python3 sql/varka/plans/m8/results/VARKA-360-arm-rule.py <file>
   "<default arm>" "<other arm>"` (the script of `VARKA-360.md`'s part 360s, which compares
   `Rate(M/s)` in every loop and rung that has both arms): an arm replaces the default if it leads
   by 1.3x or more in at least one cell and trails by 1.3x in none. The arm labels are `L ASHR`, `L
   NEG_LSHR`, `L BLEND` and `L+M` (the spike's section of branch `varka-361-widening-multiply`).
   `BLEND` never becomes the default, whatever it reads: it is a compare and a blend, a mask, and
   falls back at two lanes. It is built all the same, as the kept third arm: it costs about 10
   lines, the plan's text and the spike's loops name it, and the house keeps a losing arm.
   The rule reads the 256-bit EPYC 7763 file only, the one whose second line is `decimal lane
   pair hand loops at 256 bits, forms [...]`; the script refuses any other file. A losing arm
   stays behind its option. The laptop's 512-bit numbers are recorded and move no default in
   this milestone.
7. **The heights fit the cap of 16** (`verify_361.py` item 6, by the analysis's rule):
   `x15 * y15` stored 9, `x15 * y15 < z15` 11, `x15 * (1 - y15)` stored 10, the baseline's chain
   `(x9 + y9) * z9 - x9` stored 13. So no part of this row waits for row 697.
8. **The planted carry's witnesses are the committed ones**, from
   `results/VARKA-361-planted-carry.txt`: `818970913745787777 * -37375742756067484` (one
   negative operand) and `-122646352919130545 * -34701017952321210` (two), both wrong under the
   planted order; `-(10^18 - 1)` squared is right under it and is named as not detecting it.
   `verify_361.py` reads that committed file itself (beside it once the script is committed,
   or the path given as its first argument) and asserts each: the file's two "first" pairs are
   wrong under the planted order and right under the real one, and the four pairs the file
   calls right, `-(10^18 - 1)` squared among them, are right under the planted order. Its own
   200000 draws within 18 digits, of another seed, find the planted order wrong for 299 of
   100202 pairs of one negative operand, 23569 of 50099 of two, and none of 49699 of two
   non-negative ones; the pairs it prints as "pinned witnesses" are the first of that draw and
   are not the committed ones.
9. **The census moves by 0 pairs** (section 6). The section on what each part moves lists
   what does.

### 11.4 The algorithms, each checked on exact integers

Checked by `/home/max/varka-ops/m8-row-plans/scratch-361/verify_361.py` (Python's `int` as the
reference, words modelled as Java longs that wrap). Its last line on 28 November 2026:
`OK: 5507920 checks, 0 failures`. 361p commits it as
`sql/varka/plans/m8/results/VARKA-361-verify.py`.

**The 64 by 64 to 128 product.** With `M = 0xFFFFFFFF`, every operation wrapping, `>>>` the
logical shift:

    xl = x & M;  xh = x >>> 32;  yl = y & M;  yh = y >>> 32
    p0 = xl * yl;  p1 = xh * yl;  p2 = xl * yh;  p3 = xh * yh       four limb products
    t  = p1 + (p0 >>> 32)
    u  = p2 + (t & M)
    lo = (u << 32) | (p0 & M)
    hi = p3 + (t >>> 32) + (u >>> 32) - (y & sign(x)) - (x & sign(y))

    one limb (0 <= y < 2^32):
    t  = p1 + (p0 >>> 32);  lo = (t << 32) | (p0 & M);  hi = (t >>> 32) - (y & sign(x))

`sign(x)` is all ones where `x` is negative and 0 elsewhere. The value `hi * 2^64 + lo`, `hi` read
signed and `lo` unsigned, is `x * y` exactly, since `|x * y| <= 2^126`. No intermediate passes
`2^64`: the script asserts that `p0` to `p3`, `t`, `u` and the high word before the corrections each
fit a word, on every input. Checked against `x * y` with `x` over 46 edge longs (the ends of a long,
the limb edges `2^31`, `2^32 - 1`, `2^32`, `+-(10^p - 1)` for `p` of 9, 15, 17 and 18, every `10^k`
to `10^18`) and 3000 random longs, and `y` over the same edges and 60 random longs, in the two-limb
form and, for a multiplier in `[0, 2^32)`, the one-limb form.

**The low word, two ways** (`decimalWideMulLow`). `LIMBS`: `lo` above. `MULVL`: `lo = x * y`,
one wrapping long multiply. Checked equal on every input above, in both limb forms.

**The sign word, three ways** (`decimalSignWord`). `ASHR`: `x >> 63`. `NEG_LSHR`:
`0 - (x >>> 63)`. `BLEND`: all ones where `x < 0`, else 0. Checked equal on every input.

**The partial products row 362 reads** (`productWord`'s flags, landed by 360b1). An operand read
unsigned drops its correction: with `x` unsigned the term `y & sign(x)` is not subtracted, with
`y` unsigned the term `x & sign(y)`. Checked: unsigned by unsigned against `x_u * y_u` read as
an unsigned 128-bit value, signed by unsigned and unsigned by signed against the exact products,
over the edge longs and 600 random ones.

**The planted middle column** (row 504's fault, `PlantedFault.MIDDLE_COLUMN_ONE_WORD`):
`t = p1 + p2 + (p0 >>> 32)`, `lo = (t << 32) | (p0 & M)`, `hi = p3 + (t >>> 32)` less the
corrections. `p1 + p2` can pass `2^64` and lose its carry. Decision 8 has the witnesses.

**The type rule.** Spark types `x * y` as `(p1 + p2 + 1, s1 + s2)`; past 38 digits
`adjustPrecisionScale` gives `(38, max(38 - (P - S), min(S, 6)))` when precision loss is allowed
and `bounded` gives `(38, S)` when not. The cases this row meets, each asserted by the script:

| product | exact type | Spark's type | this row |
|---|---|---|---|
| `DECIMAL(15, 2) * DECIMAL(15, 2)` | `(31, 4)` | `(31, 4)` | fuses |
| `DECIMAL(10, 2) * DECIMAL(9, 2)` (the baseline's chain) | `(20, 4)` | `(20, 4)` | fuses |
| `DECIMAL(15, 2) * DECIMAL(16, 2)` (`x15 * (1 - y15)`) | `(32, 4)` | `(32, 4)` | fuses |
| `BIGINT * DECIMAL(18, 4)` | `(39, 4)` | `(38, 4)` | fuses: the scale is kept |
| `BIGINT * BIGINT`, both under explicit casts | `(41, 0)` | `(38, 0)` | fuses |
| `BIGINT * DECIMAL(18, 7)` | `(39, 7)` | `(38, 6)` under precision loss | declines naming row 363 |
| `BIGINT * DECIMAL(18, 7)`, precision loss off | `(39, 7)` | `(38, 7)` | fuses |

### 11.5 The build sheets

Rules for every sheet. Run every command from the worktree's root with
`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` and `SPARK_LOCAL_IP=127.0.0.1` exported. "Passes"
for an sbt test run means its last `[info] Passed: Total N, Failed 0, Errors 0, Passed N` line
shows no failure and no error; where the sheet gives `N`, it must match. A sheet's B0 lines are
premises: if one prints something else, stop and report, do not adapt. Before the pull request,
run `/code-review high` on the diff, then `dev/varka_ops/pre-pr.sh <worktree>` (it exits 0), then
`python3 dev/varka_msg_check.py size`, which must print a count under 1000.
`dev/varka_ops/INDEX.md` says how a pull request is opened and watched.

#### 361a: the widening product in the compiler

Title: `[VARKA-361] Fuse the product of two 64-bit decimals past 18 digits`.
Branch `varka-361a-widening-product`. Hunks to read first, then apply by hand:
`git -C /home/max/proj/vecbricks-varka diff varka-361-widening-multiply
varka-361a-column-product -- <DC>`.

- **B0**, on master after 360b2: `grep -c 'record WideMul' VARKA/VarkaVectorIR.java` prints 1;
  `grep -c 'TWO_LANE_WIDE_REASON' DC` prints at least 2; `grep -c 'longValued\|pairWidening'
  DC` prints 0; `grep -c 'arithmeticReason("product"' DC` prints 1; `grep -c 'OptionalLong
  longLiteralAt' CODEGEN/DeclineSink.java` prints 1.
  *[Fix round 3.]* `ls sql/varka/plans/m8/results/VARKA-360-verify.py` lists it, and `grep
  -c 'MIDDLE_COLUMN_ONE_WORD' TJ/VarkaWideMulSuite.java` prints at least 1 (360b1p merged:
  the suite holds 5 tests).
- **Step 1, the predicates and the operand** (in `DC`, beside `uncappedSum`), from the old
  branch, word for word but for `ProductForm`:

        /** A 64-bit value: a decimal within 18 digits, or a BIGINT under coercion's cast to a
         *  decimal of scale 0, whose value is the long's whatever the precision says. */
        private static boolean longValued(Expression e) {
          return e.dataType() instanceof DecimalType t && (t.precision() <= MAX_PRECISION
              || e instanceof Cast c && DataTypes.LongType.equals(c.child().dataType())
                  && t.scale() == 0);
        }

        /** A product of two 64-bit values typed past 18 digits with the scale s1 + s2 kept,
         *  reached through a TRY form too. */
        private static boolean pairWidening(Expression e) {
          Expression at = e instanceof RuntimeReplaceable r ? r.replacement() : e;
          return at instanceof Multiply m && m.left().dataType() instanceof DecimalType t1
              && m.right().dataType() instanceof DecimalType t2
              && m.dataType() instanceof DecimalType type && type.precision() > MAX_PRECISION
              && type.scale() == t1.scale() + t2.scale()
              && longValued(m.left()) && longValued(m.right());
        }

  `longOperand` and `oneLimb` are the old branch's, unchanged (`longOperand`: `value(e)`'s
  unscaled long, or for a `BIGINT` under its wide cast the compiled long itself, declining if
  it is not on the long lane; `oneLimb`: a `LiteralSlot` of the long table whose value, read by
  `sink.longLiteralAt` without interning, is in `[0, 2^32)`).
- **Step 2, the arm.** `wideningProduct` is the old branch's, with the two-lane test first:

        private static Optional<Pair> wideningProduct(Multiply m, DecimalType type,
            VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
          if (sink.declinesTwoLaneChecks()) {
            return sink.decline(TWO_LANE_WIDE_REASON, m);
          }
          DecimalType t1 = (DecimalType) m.left().dataType();
          DecimalType t2 = (DecimalType) m.right().dataType();
          ResultType rule = ResultType.ofProduct(t1, t2);
          // The rule's type, capped at 38 with the scale kept, which pairWidening has checked.
          var capped = new ResultType(Math.min(rule.precision(), DecimalType.MAX_PRECISION()),
              rule.scale());
          Optional<String> mistyped = mistyped(m, "product", capped, type);
          if (mistyped.isPresent()) {
            return sink.decline(mistyped.get(), m);
          }
          Optional<VarkaVectorIR> x = longOperand(m.left(), inputs, literals, sink);
          if (x.isEmpty()) {
            return Optional.empty();
          }
          Optional<VarkaVectorIR> y = longOperand(m.right(), inputs, literals, sink);
          if (y.isEmpty()) {
            return Optional.empty();
          }
          // A constant in [0, 2^32) goes on the right as one limb.
          boolean xOneLimb = oneLimb(x.get(), sink);
          VarkaVectorIR a = xOneLimb ? y.get() : x.get();
          VarkaVectorIR b = xOneLimb ? x.get() : y.get();
          boolean rightOneLimb = xOneLimb || oneLimb(b, sink);
          return Optional.of(new Pair(new WideMul(a, b, Word.LOW, rightOneLimb),
              new WideMul(a, b, Word.HIGH, rightOneLimb), type));
        }

  In `pair`, add `case Multiply m when pairWidening(m) -> wideningProduct(m, type, inputs,
  literals, sink);` beside the `Add` and `Subtract` arms. Everywhere `uncappedSum(x)` decides
  that a value is a pair's (`wideReason`, `wideArithmeticBelow`, and with 360c1
  `cappedKeepingScale`), add `|| pairWidening(x)`. `valid`'s `Multiply` arm already answers
  "valid where both operands are", which is right here: the check is dead (11.3 decision 4).
- **Step 3, the reasons.** `productReason` takes the node and its type. Before changing a text,
  `grep -rn '<a fragment of it>' sql/catalyst/src/test sql/core/src/test sql/varka docs dev`.

| the product | reason on master today | after this part |
|---|---|---|
| two 64-bit values, scale kept, under a pair consumer | "a decimal product of 31 digits is row 361's" (the digits vary) | none: it fuses |
| the same under a consumer of one long (`CAST(a * b AS BIGINT)`) | the same | "a decimal product past 18 digits is two words, which a consumer of one long does not read (row 359's)" |
| `BIGINT` times a decimal within 18 digits, scale kept | "a decimal product over an operand wider than 18 digits is rows 362 and 363's" (wrong row: the `BIGINT` is a 64-bit value) | none: it fuses |
| `BIGINT * DECIMAL(18, s)`, `s` from 7 to 18, precision loss on | "a decimal product of 39 digits, capped at 38, is rows 362 and 363's" | "a decimal product of 39 digits, capped at 38 with its scale cut, is row 363's" |
| an operand that is a pair (`DECIMAL(20, 2) * DECIMAL(17, 2)`) | "a decimal product over an operand wider than 18 digits is rows 362 and 363's" | unchanged (row 362's) |
| a product past 38 digits over a pair | "a decimal product of 41 digits, capped at 38, is rows 362 and 363's" | unchanged |
| on a two-lane x86 session | as above | `TWO_LANE_WIDE_REASON` |

- **Step 4, the harness.** `VarkaWideningProductSuite` (old branch) calls `VarkaDecimalPairSuite`'s
  helpers from outside and passes a `BIGINT` as a column type. On master those helpers are
  private and take a `DecimalType`. Make these package-private with the column's type a
  `DataType` (`DecimalType` or `LongType`), each as the branch has it (the command at the end
  of this step): `record Row(BigInteger a, BigInteger b)`, `static List<BigInteger> edges(DataType
  t)`, `static BigInteger bound(DataType t)`, `static List<Row> rows(DataType ta, DecimalType tb)`,
  `static void check(String sql, DataType ta, DecimalType tb, List<Row> rows)` (the three-argument
  `check` calls it with `rows(ta, tb)`), `static void inMode(boolean ansi, Runnable body)` and
  `static String decline(String sql, DataType ta, DecimalType tb)`. The old suite also calls
  `VarkaDecimalValueSuite.compile(String sql, List<Attribute> columns)`, which returns a
  `CompiledVarkaPredicate` and is package-private on master already. Take the harness changes only,
  no test of that old file. Give `checkStored` and row 360's `storedOrDeclined` the same widening of
  the first column's type. *[Fix round 3, 28 November 2026.]* The four-argument `check` takes
  the options too, as 360b2's overload does: `static void check(String sql, DataType ta,
  DecimalType tb, List<Row> rows, VarkaEmitOptions options)`, which compiles and emits under
  `options`; the shorter forms call it with `rows(ta, tb)` and `VarkaMatrixRuns.BASE`.

      git -C /home/max/proj/vecbricks-varka show varka-361a-column-product:sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaDecimalPairSuite.java

- **Step 5, tests.** New `TJ/VarkaWideningProductSuite.java` (JUnit 5 under `@VarkaSuite`), from
  the old file, six tests. *[Fix round 3, 28 November 2026.]* `VarkaWideningProductSuite` runs
  every kernel test under the suite's `DEEP`, the constant of `VarkaDecimalPairSuite`
  (`VarkaDecimalPairSuite.DEEP`, `VARKA-360.md` 11.3 decision 5: `maxChainDepth(64)`,
  `methodByteBudget` and `cse` pinned, since under the matrix configuration
  `methodByteBudget=0` a kernel past 64 distinct ops declines on size): tests 1 to 5, test 5's
  options built as `DEEP.toBuilder().validityByBitmap(false)...`; test 3 too, since "fuses
  exactly where `pairWidening` holds" must not meet a size decline. Test 6 asserts declines
  only and keeps `VarkaMatrixRuns.BASE`. The products fit the cap of 16 (11.3 decision 7);
  the pin is for the other two bounds.
  1. `wideningProducts`: the old test, with its `FORMS` (`a * b < b`, `a * b >= a`, `a * b > 0`,
     `a * b = b * a`, `try_multiply(a, b) <= b`, `(a * b) IS NULL`) under `check`, and four
     stored forms under `checkStored`, which the old branch could not have: `a * b`,
     `try_multiply(a, b)`, `-(a * b)`, `a * b + a * b`. Type pairs: `(15, 2)` with `(15, 2)`;
     `(9, 2)` with `(9, 2)` (the narrowest pair, `(19, 4)`); `(18, 2)` with `(18, 2)`; `(17, 0)`
     with `(18, 4)`; `BIGINT` with `(9, 2)`; `BIGINT` with `(18, 4)` (capped, scale kept).
     Under ANSI and LEGACY. Rows: the old `rows` (word and limb edges, the three `WITNESSES`, 256
     seeded pairs, every null pattern).
  2. `constantMultipliers`: `a * 3`, `a * 100`, `a * 4294967295`, `a * 4294967296`,
     `a * 999999999999999999`, `a * -7`, `a` a `(18, 2)`: one limb below `2^32` and two
     otherwise; the test also asserts `rightOneLimb` on the compiled root for the first three.
  3. `typeRule`: the old test: over every `(p1, s1, p2, s2)` within 18 digits and the `BIGINT`
     cast, under both settings of `spark.sql.decimalOperations.allowPrecisionLoss`, the compiler
     fuses exactly where `pairWidening` holds, and each decline names row 363 (the 24 cut pairs)
     or is row 349's long product. The counts of 11.3 decision 4 are asserted.
  4. `oneKernelInEveryMode`: the old test.
  5. `storedProductsWithoutTheBitmapPass`: test 1's first pair, stored, under the options of
     `VarkaDecimalPairSuite.storedSumsWithoutTheBitmapPass`.
  6. `productsThatDecline`: each row of step 3's table with its text; and `a * b` stored and
     `CAST(a * b < b AS INT)` decline with `TWO_LANE_WIDE_REASON` on the two-lane admission.
  `VarkaWideMulSuite` gains the old branch's two column tests ("a column product's reference and
  lowered network give BigInteger's two words" and "both words of a column product fuse at every
  width, length and body"): 5 tests after 360b1p, 7 after this part. Edit
  `VarkaDecimalArithmeticSuite`'s pinned reasons and type sweep by step 3's table. Planted
  check, by hand and reverted, recorded in the description: swap `Word.LOW` and `Word.HIGH` in
  `wideningProduct`; test 1 must fail on its first row.
- **Step 6, coverage and baseline.** `TS/VarkaCoverageSuite.scala`, two new families beside
  "DECIMAL wide add": "DECIMAL widening multiply" (predicates) with `d9 * d9b > 0`,
  `d18 * d18b >= d18 * d9`, `l * d9 < 0`, `CAST(l AS DECIMAL(20, 0)) * CAST(l2 AS
  DECIMAL(20, 0)) <= 0`, `d18 * 3 > d18`, `try_multiply(d16s9, d9) <= 0`; and "DECIMAL widening
  multiply values" with `d9 * d9b`, `d18 * d18b`, `l * d18`, `try_multiply(d18, d18b)`,
  `d18 * 3`. Both are `Proved("decimal128.smt2")` once 361p lands; until then they take the
  verification the suite's check accepts for a family held by the differential alone, as the
  pair's null tests do (`pairNulls`). `VarkaDecimalBaselineBenchmark`: the sections of 11.6 take
  `fused`, the `Fusion fused` parameter `addWidening(out, d, fused)` has since 360a (do not add
  it a second time; fix round 3) (and the `DECIMAL(9, 2) arithmetic chain` takes it in `sections`'
  first loop, at precision 9 only); `VarkaDecimalBaselineQueriesSuite` follows. The runner files are
  not regenerated here (361c).
- **Commands.**

        build/sbt 'catalyst/testOnly *VarkaWideningProductSuite'   -> Passed: Total 6
        build/sbt 'catalyst/testOnly *VarkaWideMulSuite'           -> Passed: Total 7
        build/sbt 'catalyst/testOnly *VarkaDecimal* *VarkaTwoLaneConditionSuite \
          *VarkaCoverageSuite *VarkaEmittedBytesSuite *VarkaExpressionCompilerSuite \
          *VarkaCoverageCompositionFuzzSuite *VarkaWarmupReachSuite'    -> passes
        dev/varka_regen_generated.sh --all                         -> passes
        build/sbt 'sql/testOnly *VarkaDecimal* *VarkaCoverageDifferentialSuite'   -> passes
        python3 dev/varka_census.py --check                        -> passes
        JDK_JAVA_OPTIONS=-XX:UseAVX=2 build/sbt 'catalyst/testOnly *VarkaWideningProductSuite'
            -> Passed: Total 6

  After the regeneration `emitted_bytes.json` holds new entries only, and the per-option digests
  move. The description states `typeRule`'s time on the laptop.
- **Done when** the commands pass, the option matrix passes, and section 10 gains a subsection
  "361a: the widening product".
- **Not here:** a pair operand (row 362); a cut scale (row 363); any option; the proofs (361p);
  `ProductForm`.

#### 361p: the column product's proof, and its planted carry

Title: `[VARKA-361] Prove the column product's carry network and fail its planted carry`.
Branch `varka-361p-column-proofs`.

- **B0.** `grep -c 'wideningProduct' DC` prints at least 2; `ls
  sql/varka/plans/m8/results/VARKA-360-verify.py` lists it (360b1p merged).
- **Steps.** (1) `TJ/VarkaProofFiles.java`: the old branch's column layer of `decimal128.smt2` (`git
  diff varka-361-widening-multiply varka-361a-column-product -- <TJ>/VarkaProofFiles.java`, 95
  lines): the ring identity (the four limb products' weighted sum is `x_u * y_u`); the carry layer
  with `p0` to `p3` free in `[0, (2^32 - 1)^2]`, no wrap in `t`, `u` or the high word; the two sign
  corrections giving the signed product; and the planted order as a `sat` witness. (2) Regenerate
  the proof file. (3) `VarkaWideMulSuite`: one test, "the planted middle column fails a column
  product at a witness of each sign class": through `VarkaLoopEmitter.emitPlantedForTest`, the two
  committed witnesses of 11.3 decision 8 answer a word unlike `BigInteger`'s and `-(10^18 - 1)`
  squared answers `BigInteger`'s (8 tests). (4) The two coverage families of 361a become
  `Proved("decimal128.smt2")`. (5) Add `sql/varka/plans/m8/results/VARKA-361-verify.py`. (6) Section
  10 gains a subsection "361p: the proofs" with the solver times.
- **Commands.**

        dev/varka_prove.sh --solver both decimal128.smt2             -> exit status 0, and one line
            a solver that ends "holds" (a line that ends "FAILED" is a stop)
        build/sbt 'catalyst/testOnly *VarkaProofFilesSuite *VarkaWideMulSuite *VarkaCoverageSuite'
            -> passes; VarkaWideMulSuite alone is Total 8
        python3 sql/varka/plans/m8/results/VARKA-361-verify.py       -> OK: 5507920 checks, 0 failures

- **Done when** they pass. **Not here:** row 362's shortcut and column-sum layers.

#### 361b: the options `decimalWideMulLow` and `decimalSignWord`

Title: `[VARKA-361] Put the wide product's low word and sign word behind emit options`.
Branch `varka-361b-product-options`. Template: the choice option `decimalStore`; every site is
`grep -rn 'decimalStore\|DecimalStore' sql/catalyst/src dev sql/varka/matrix` (on master:
`VarkaEmitOption`, `VarkaEmitOptions`, `VarkaMatrixRuns`, `VarkaSteering`, `VarkaEmitOptionSuite`,
`VarkaIrFuzzSuite`, `VarkaEmitterBudgetSuite` and the matrix's timing tables, which a matrix run
regenerates). Hunks of the old option:
`git diff varka-361a-column-product varka-361a2-mul-low-option`.

- **B0.** `grep -c 'decimalWideMulLow\|decimalSignWord' VARKA/VarkaEmitOptions.java` prints 0;
  `grep -c 'static VarkaVectorIR productWord' VARKA/VarkaDecimalLowering.java` prints 1.
- **Step 1, the options.** In `VarkaEmitOptions`: `enum DecimalWideMulLow { LIMBS, MULVL }` and
  `enum DecimalSignWord { ASHR, NEG_LSHR, BLEND }`, two record components with their builder
  setters and `with` methods, defaults `LIMBS` and `ASHR`. In `VarkaEmitOption.TABLE`: two
  `Choice` entries, `Reason.MACHINE`, declared as `decimalStore`'s is.
- **Step 2, the lowering.** `lowerAll(outputs, options, faults)`, `lower(WideMul, options,
  faults)` and `productWord(options, x, y, word, rightOneLimb, xSigned, ySigned, faults)` take
  the options first since 360b1, and `VarkaLoopEmitter.lowered` and the private `emit` pass
  theirs already; this part changes no signature and no caller. Two changes:

        // in lower(WideMul n, options, faults): the option's subject is WideMul alone
        if (n.word() == Word.LOW && options.decimalWideMulLow() == DecimalWideMulLow.MULVL) {
          return mul(n.left(), n.right());
        }

        // in productWord: the sign word of v, in the option's form
        private static VarkaVectorIR signWord(VarkaEmitOptions options, VarkaVectorIR v) {
          return switch (options.decimalSignWord()) {
            case ASHR -> bit(BitOpKind.SHR, v, SIGN);
            case NEG_LSHR -> new IntNeg(Overflow.WRAP, bit(BitOpKind.USHR, v, SIGN));
            case BLEND -> new IfElse(new Compare(CompareOp.LT, v, new WordConst(0)),
                new WordConst(-1), new WordConst(0));
          };
        }

  and the two `bit(BitOpKind.SHR, x, SIGN)` of `productWord` become `signWord(options, x)` and
  `signWord(options, y)`. `laneCalls` and `WideMul.props()` weigh the default arms
  (`VarkaEmitOptions.DEFAULTS`).
- **Step 3, tests.** `VarkaWideMulSuite`: one test, "every arm of the low word and of the sign
  word gives BigInteger's two words": the six arm pairs, each at the suite's widths and
  lengths, over a constant product and a column product (9 tests). `VarkaEmitOptionSuite`'s
  table gains the two entries; `VarkaIrFuzzSuite`'s wide product sequence runs once under each
  non-default arm by name, beside its drawn options (the old review found `MULVL` never drawn).
  `VarkaMatrixRuns` gets the options' configurations as `decimalStore` has its own.
- **Commands.**

        build/sbt 'catalyst/testOnly *VarkaWideMulSuite'             -> Passed: Total 9
        build/sbt 'catalyst/testOnly *VarkaEmitOptionSuite *VarkaIrFuzzSuite *VarkaMatrixSuite \
          *VarkaEmittedBytesSuite *VarkaEmitterBudgetSuite *VarkaWideningProductSuite'  -> passes
        dev/varka_regen_generated.sh --all                           -> passes

  After the regeneration no shape's bytes move in `emitted_bytes.json` (the defaults are the
  old forms); its `option_arms` digests move.
- **Done when** the commands pass and the option matrix passes with the new configurations.
- **Not here:** a default other than `LIMBS` and `ASHR` (361c, by rule); `PairMul`.

#### 361s: the spike loops 14 to 16

Title: `[VARKA-361] Add the widening product's loops to the decimal pair spike`.
Branch `varka-361s-spike-loops`. Source: the one commit `14ec20087d1` of
`varka-361-widening-multiply`, files `sql/catalyst/src/test/java/org/apache/spark/sql/
VarkaDecimalPairSpikeBenchmark.java` and `VarkaDecimalPairSpikeSuite.java`.

- **B0**, on master after #1799, with `<the benchmark>` the first of the two files above:
  `grep -c '^    ADD_CHECK(' <the benchmark>` prints 1 (loops 8 to 10 are in: the spike's ops
  are `ADD`, `SUB` and `ADD_CHECK`); `grep -c 'WIDE_BY_LONG' <the benchmark>` prints 0.
- **Steps.** Apply the commit's hunks by hand: the three `Op`s (`WIDE`, `WIDE_K2` and
  `WIDE_K12`, `WIDE_BY_LONG`), numbered 14, 15 and 16 as on the branch (11 to 13 are row 362's,
  not on master: leave the gap), their 24 loop methods, the helper `storeWide`, and the inputs
  `X15`, `Y15`, `Y18` and `L`. The branch takes `L`, a `BIGINT` column, from row 362's
  `columns362`; copy that one column's generator into a `columns361` and nothing else of 362's.
  The old review's one finding (nothing shows that `storeWide` is inlined into each `WIDE`
  loop) is closed by a sentence, not a test: `VarkaDecimalPairSpikeSuite` has no helper that
  reads a method's bytecode size (its only inlining tests are those of the `dontinline`
  command). Add to the class's javadoc, and to section 10's subsection: "whether `storeWide`
  is inlined into the loops 14 to 16 is read from `-XX:+PrintInlining` in part 361c, and a
  loop where it is not is not scored". Section 10 gains a
  subsection "361s: the spike loops" from the branch's "Step 3: the spike loops", with "not
  run" kept.
- **Commands.**

        build/sbt 'catalyst/testOnly *VarkaDecimalPairSpikeSuite'    -> passes
        build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaDecimalPairSpikeBenchmark" \
          with -Dvarka.bench.dryRun=true (as the class's javadoc gives it)  -> every arm built

- **Done when** they pass. **Not here:** any timing; loops 11 to 13; loop 7b.

#### 361c: the readings, the defaults, the close

Title: `[VARKA-361] Measure the widening product and set its options' defaults`.
Branch `varka-361c-measure`.

- **The lead's steps:** dispatch `benchmark.yml` on vecbricks/varka for
  `*.VarkaDecimalPairSpikeBenchmark` (with the class's `dontinline` option) and for
  `*.VarkaDecimalBaselineBenchmark`, each at 256 bits and under `-XX:MaxVectorSize=16`, with
  `expected-cpu="AMD EPYC 7763"`; download the four artifacts.
- **Steps.** (1) Replace the two classes' runner files; which artifact is which width is read
  from each results file's second line (`... at 256 bits ...` or `... at 128 bits ...`), as
  `VARKA-360.md`'s 360s sheet says. Read `-XX:+PrintInlining` of the spike's 256-bit run for
  `storeWide` (361s) and name any loop it is not inlined into. (2) Run `python3
  sql/varka/plans/m8/results/VARKA-360-arm-rule.py <spike 256-bit file> "L ASHR" "L NEG_LSHR"` and
  the same with `"L ASHR" "L+M"`; by 11.3 decision 6 set the defaults of `decimalSignWord` and
  `decimalWideMulLow`; if a default moves, regenerate and say which shapes' bytes moved. (3) The
  instruction mix, on the laptop: `dev/varka_emit.sh "x * y" --columns
  x:decimal(15,2),y:decimal(15,2) --asm` must show four `vpmuludq` in the dense loop and no
  `vpmullq`; `"x * 100"` two; `"x * 4294967296"` four. Under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` the
  same. Paste the mnemonic counts. (4) A subsection of section 10, "The measurement": for each
  section of 11.6, the row path's and varka's best times at both densities and widths; section 4's
  predictions 1 to 7 scored by number (prediction 6, the 128-bit win, as "not built: declined at two
  lanes"). (5) Mark the row done in `PLAN.md` if every section of 11.6 is faster than the row path
  by 1.3x or more at 256 bits; name any that is not.
- **Oracle.** The script's `VERDICT:` lines are quoted in that subsection; `python3
  dev/varka_quote_check.py sql/varka/plans/m8/VARKA-361.md` prints 0 orphans.

### 11.6 What each part moves

**Decline reasons**: 361a step 3's table.

**Census** (`sql/varka/plans/m8/census_curated.txt`, then `python3 dev/varka_census.py
--check`): no pair is counted. `*` and `try_multiply` gain `dec^` (fused in part) with 361a, and
their notes change from "fused where the result is within 18 digits (349a, a filter until 157)"
to name the widening product "(361a), under a comparison or as an output"; "the pair counts with
361 and 362 at every precision to 38" becomes "with 362 and 363". On master neither entry has
`dec^`, though row 349's product within 18 digits fuses: section 13 item 4.

**The cast matrix**: no cell moves.

**Coverage families**: "DECIMAL widening multiply" and "DECIMAL widening multiply values", new
with 361a; `results/VARKA-612-refused.txt` gains the five value rows.

**Baseline sections** of `VarkaDecimalBaselineBenchmark` that turn from the row path to a kernel
with 361a, each at both densities ("no nulls", "one row in ten null"), and to `NONE` again on a
session that refuses a 64-bit condition:

| section, by title | what fuses |
|---|---|
| "DECIMAL(15, 2) x * y as a value" | the product, stored |
| "DECIMAL(15, 2) x * (1 - y) as a value" | row 349's difference under the product |
| "BIGINT l * x as a value, x a DECIMAL(9, 2)" | a `BIGINT` operand, `DECIMAL(30, 2)` |
| "BIGINT l * x as a value, x a DECIMAL(18, 4)" | capped at `DECIMAL(38, 4)`, scale kept |
| "DECIMAL(15, 2) x * y < z filter" | the product against `z15` under coercion's rescaling cast (360b2's) |
| "DECIMAL(9, 2) arithmetic chain" | `(x + y) * z - x`: row 349's sum, this product at `(20, 4)`, row 360's difference |

Not this row's: "DECIMAL(18, 4) arithmetic chain" (its product reads a pair, row 362), the
`DECIMAL(20, 2) x * y ...` and `DECIMAL(38, 2) ...` product sections (row 362), and every
section titled "its scale cut" (row 363).

### 11.7 What this row hands to rows 362 and 363

| name | where | what the next rows may rely on |
|---|---|---|
| `VarkaVectorIR.WideMul`, `WordConst`, `VarkaDecimalLowering.lowerAll` | `VARKA-360.md`'s 360b1 | the node and the one lowering hook; `PairMul` and `ScaleDown` add their arms to `lowered` |
| `VarkaDecimalLowering.productWord(options, x, y, word, rightOneLimb, xSigned, ySigned, faults)` | 360b1, with `options` first from the start; 361b adds the reads | the partial-product helper: one word of any 64 by 64 product, each operand signed or unsigned; row 362's sixteen-product network is calls of it |
| `PlantedFault.MIDDLE_COLUMN_ONE_WORD`, `VarkaLoopEmitter.emitPlantedForTest` | 360b1 | row 504's planted carry, the same argument for `PairMul` |
| `VarkaDecimalCompiler.longValued`, `pairWidening`, `longOperand`, `oneLimb`, `wideningProduct` | 361a | row 362's `ProductForm.WIDENING` must equal `pairWidening`; its shortcut reads `longValued` to drop a cross term |
| the reason "a decimal product of 39 digits, capped at 38 with its scale cut, is row 363's" | 361a | the decline row 363 turns into its arm for `BIGINT * DECIMAL(18, s > 6)` |
| options `decimalWideMulLow` (`LIMBS`, `MULVL`), `decimalSignWord` (`ASHR`, `NEG_LSHR`, `BLEND`) | 361b | `decimalWideMulLow` is `WideMul`'s alone; `decimalSignWord` is read by `productWord`, so by every partial product. Row 362's `decimalWideSign` is its own |
| coverage families "DECIMAL widening multiply" and "... values" | 361a | row 362 adds its own families beside them |
| `VarkaDecimalPairSuite`'s package-private harness over a `DataType` column | 361a | `VarkaDecimalProductSuite` (row 362) builds on it |

### 11.8 Risks

1. **361a's arm and row 362's shortcut both edit the `Multiply` case of `pair`.** Held by the
   order (361a merges first) and by `pairWidening` being a named predicate row 362 tests first.
2. **The harness widening breaks `VarkaDecimalPairSuite`'s own tests.** Held by running that
   suite in 361a's commands; no expectation of its tests changes.
3. **`typeRule` is slow** (72200 compiles). Held by stating its time; if it passes 60 seconds
   on the laptop, sample every third scale and say so in the test's name.
4. **A default moved in 361c changes committed bytes.** Held by the regeneration in the same
   pull request and the matrix.
5. **The product is slower than the row path at 256 bits on AVX2** (about 25 lane calls a lane
   group against two scalar multiplies and three allocations a row). Held by 361c: a section
   under 1.3x is named in the row, and the lead decides.

### 11.9 What only a JVM can settle, and the command for each

1. The instruction mix (11.3 decision 3): 361c step 3's three `dev/varka_emit.sh --asm` runs.
2. That the two words of one `WideMul` pair share one network in the emitted loop: the same
   run's assembly holds four `vpmuludq` a lane group, not eight. Eight is a finding for 360b1
   (the lowered graph's sharing), which its suite's sharing test holds at the IR level only.
3. Whether `typeRule` fits its time: its run.
4. Every reading of 361c, and whether a default moves.
5. The solver times of 361p's layer: `dev/varka_prove.sh --solver both decimal128.smt2`.
6. Whether `VarkaWarmupReachSuite` needs an entry for the network (section 2.5 says the
   warm-up's long block gains it): the suite's own failure message names what to add; 361a's
   commands run it.

## 12. Decisions for the owner (28 November 2026)

*New section.* The decimal chain's owner decisions are numbered across its four plans: D1 to D3
in `VARKA-360.md` section 12, D4 here, D5 to D7 in `VARKA-362.md` 11.9, D8 in `VARKA-363.md`
11.9.

- **D4. Building the widening product before its measurement is committed.** Answered by the owner
  on 28 November 2026: (a), as applied. The shape: `x * y` over two `DECIMAL(15, 2)` columns, a
  `DECIMAL(31, 4)`: two 64-bit values multiplied into 128 bits. Two details of the kernel have
  more than one form (how the low word is computed, two forms; how a sign mask is computed, three
  forms), each behind an emit option. The lead's answer of 30 October 2026 has the runner's
  measurement of those forms committed before the kernel is built. (a) Part 361a builds the kernel
  now with the two forms that are already proven as defaults, and part 361c, after the
  measurement, moves a default by a fixed rule (a form replaces the default if it is 1.3x ahead in
  a cell and behind in none); the measurement can change two defaults and no answer of any query.
  (b) The spike's loops (part 361s, about 500 lines) and the lead's runner dispatch come first,
  and 361a and every step of the chain that waits on it (361p, 361b, 362a2 and the parts after
  them in 11.2 of `VARKA-360.md`) wait for them. Recommended: (a).

Two former questions are decided in the text and are not the owner's: `BLEND`, a third way to
compute a sign mask that can never be the default, is kept as an arm (11.3 decision 6); and
each row lands its own loops of the spike file, 361s here, since 361c's rule needs loops 14 to
16 only (11.2).

## 13. Stale against the tree, found while planning (28 November 2026)

*New section.*

1. Section 0 item 7's third consequence and 2.3's last sentences (a zero limb and a limb of
   `0xFFFFFFFF` "lose the pattern"; "the compiler drops a zero limb's products, and the other
   becomes a shift and a subtraction"): they assume the multiplier is a constant in C2's graph.
   It is a literal slot (11.3 decision 3). The shift-and-subtract identity is still checked by
   `verify_361.py`, for the record.
2. Section 2.2: "the first word a body emits computes both and parks the other in a slot keyed
   by the pair of children". Row 506's decision of 30 October 2026 replaced that: the node is
   lowered to primitive nodes and the two words share by the walk's common subexpressions.
3. Sections 5 and 7 and test 7 say "after 359b's store": #1710 merged the store, so the product
   is an output the day 361a lands, and "361b" of section 5 is this section's 361c.
4. `census_curated.txt` on master gives `*` and `try_multiply` no `dec^`, though their notes say
   the product within 18 digits fuses (row 349a) and the coverage family "DECIMAL arithmetic"
   holds it: a missing "in part" mark. 361a adds it; if `dev/varka_census.py --check` on master
   already asks for it, that is a finding for the lead before 361a.
5. `VarkaDecimalCompiler.productReason` on the 360a branches calls a `BIGINT` under its cast "an
   operand wider than 18 digits" and names rows 362 and 363 for `l * x9`: wrong row, corrected
   by 361a's `longValued`.
6. `/home/max/varka-ops/m8-triage/decimal-chain.md` orders 362a before 361a "by construction of
   the branches"; this plan restores the close path's order (11.3 decision 1), which costs the
   two predicates of 361a's step 1 and nothing else.
7. The row's line in `m8/PLAN.md` says "every constant limb written as
   `broadcast(c).and(0xFFFFFFFF)` so C2 selects `vpmuludq`": true of the mask, which is the
   constant; the limb's source is a slot.

