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

(Written when the work lands.)
