# VARKA-359: The 128-bit decimal lane pair

## 0. What exists now (admission check)

From master at `4e09c1ecfce` and from row 347's two local branches, which hold the code this row
builds on and are not merged: the leaf (step 3, `varka-347-leaf`) and the first reader (step 5,
`varka-347-reader` at `95e8e7fba90`); line numbers below are the reader branch's (27 October
2026; Varka paths under `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/
codegen/` unless named). The row's text is the milestone plan's 2.8 and its 10.3 item 2. Spark's
facts were read in the fork's sources, Arrow's in `arrow-vector-19.0.0-sources.jar`, the JDK's in
`/home/max/proj/openjdk-build/jdk25`.

1. **Spark's reader checks the precision and nothing else above 18.** `ArrowColumnVector` picks
   `DecimalAccessor` for precision above 18 (`ArrowColumnVector.java:207-213`), whose `getDecimal`
   is `Decimal.apply(getObject(rowId), p, s)` (`:496-499`). Arrow's `getObject` turns the sixteen
   bytes into a signed `BigInteger` with no validation (`DecimalUtility.java:46-65`), and
   `Decimal.set(BigDecimal, p, s)` raises `NUMERIC_VALUE_OUT_OF_RANGE` when the value has more
   than `p` digits (`Decimal.scala:119-136`). Every 128-bit pattern is a value, so there is no
   high-word check: the load's one decline is a valid slot with `|u| >= 10^p`, which raises in
   every mode, as 347 found below 19 (`VARKA-347.md` 0, item 3).
2. **The comparisons, choice, negation and `abs` are exact and cannot overflow.** A comparison
   is `Decimal.compare`, by value (`Decimal.scala:482-488`); `=` and `IN` are `equals`, which is
   `compare == 0` (`:490-495`; `predicates.scala:528`, `:570`); `<=>` adds the null rule
   (`:1177-1209`). `UnaryMinus` is `unary_-` (`arithmetic.scala:70`), which rebuilds the value
   through `set` (`Decimal.scala:554-560`) and so cannot fail on a value within its precision,
   since `|-u| = |u|`; `abs` is the same (`:562`). The census's note for `abs` and `negative`,
   "the minimum value overflows", is true for the integral types and false for a decimal.
3. **Two operands of one scale compare as their unscaled values; other scales are rescaled, up
   or down.** `DecimalPrecisionTypeCoercion` casts both sides of a comparison, and the branches of
   a choice, to `widerDecimalType` (`DecimalPrecisionTypeCoercion.scala:138-144`, `:195-205`;
   `TypeCoercionHelper.scala:261-272`): the larger scale, and above 38 digits
   `boundedPreferIntegralDigits` (`DecimalType.scala:148-158`), which keeps the integral digits
   and cuts the scale. So `DECIMAL(38, 10) < DECIMAL(38, 2)` compares at `(38, 2)`, a `HALF_UP`
   rescale down of the first operand (363's division), and `DECIMAL(20, 2) < DECIMAL(20, 4)` at
   `(22, 4)`, a rescale up by `10^2` of a 128-bit value (a 128 by 64 multiply, 361's limbs). Two
   precisions at one scale widen by a cast that keeps the unscaled value, which 347's reader
   already reads as its child (`VarkaDecimalCompiler`, `stripWidening`, line 153).
4. **The IR is one long vector per node, left on the operand stack.** `emitValue` is a post-order
   walk "leaving the node's vector on the operand stack", a shared node parked in one local
   (`VarkaVectorWalk.java:150-170`). The nodes this row needs exist on the long lane: `Compare`
   (`VarkaVectorIR.java:1027`, its ops `LT, LE, GT, GE, EQ` at `:388`, no unsigned one), `IfElse`
   (`:1260`), `IsNotNull` (`:1174`), `IntNeg` with its `abs` flag (`:752`), `IntArith` in `WRAP`
   (`:723`), `BitOp` (`XOR`, `SHR`) and `CondValue`, a condition as 1 or 0 (`:1224`).
5. **347's leaf already loads both words.** `emitDecimalLoad` (`VarkaVectorWalk.java:539-614`)
   loads `a` and `b` at sixteen bytes a row, under `indexInRange` masks in the epilogue, parks them
   in `Slots.decimalTmp` (`Slots.java:302`, keyed by the leaf node), takes the low words by
   `a.rearrange(EVEN, b)` and the high words by the odd shuffle (both built once a body,
   `VarkaBodyEmitter.java:250-263`), and drops the high words after its check. The check folds
   into the body's accumulator through `emitGuardCollect` under `Refusal.DECIMAL_SLOT`
   (`Analysis.java:344-349`), status `STATUS_DECIMAL_SLOT` (`VarkaFusedKernel.java:94`), never
   narrowed by an arm (`Analysis.java:602-610`), and is emitted under a null test as well, since
   Spark's `IS NULL` reads the column. The input is mapped at `length * 16` bytes through
   `decimalInputs` (`Analysis.java:515`, `VarkaBodyEmitter.java:1560-1580`), and the evaluator
   holds the Arrow field to 128 bits and the column's scale (`decimalFieldMatches`, sql/core
   `VarkaLaneArrow.java:168`). All of it serves the pair unchanged.
6. **The registry has one lane per type, and the decimal's storage is already separate.**
   `Info` holds `storageBits` (128 for the decimal) beside an admission
   (`varka/VarkaTypes.java:149-160`); the decimal's admission is `UpToPrecision(LONG, 18, "359")`
   (`:77-88`, `:388-392`), so `lane(DataType)` answers `LONG` for precision 18 or less and empty
   above (`:309-318`), and `declineReason` names this row (`:326-345`). `Info.lane()` answers
   `LONG` for the decimal at every precision (`:168-176`), and `VarkaTimeCompiler`'s long-column
   arm would read any `lane == LONG` column as eight-byte longs, which is why a decimal arm sits
   ahead of it (`VarkaTimeCompiler.java:159-170`), the confusion the owner's note of 27 October
   names. Twelve call sites in four main files ask `VarkaTypes.lane(DataType)`, and five test
   files read it.
7. **The literal table holds one long per slot, and a decimal literal above 18 digits declines**
   naming this row (`VarkaDecimalCompiler`, lines 178-197); so does a comparison or null test of a
   wider type (lines 82-85, `VarkaEmitBudget.java:604` weighs the leaf at 7).
8. **No decimal output exists.** `VarkaLaneArrow` maps `DECIMAL` to `DecimalVector` with a null
   allocator (sql/core `VarkaLaneArrow.java:118`); the 64-bit store is row 157's, not started.
9. **The JDK.** The unsigned long compares are `VectorOperators.ULT`, `ULE`, `UGT`, `UGE`
   (`VectorOperators.java:668-683`): on AVX2 C2 flips the sign bit of both inputs and emits
   `vpcmpgtq` (`x86.ad:7890-7915`), on AVX-512 `vpcmpuq` (`:7917-7981`), on NEON `cmhi` and `cmhs`.
   There is no lanewise multiply-high (`skills/vector-api-and-width.md`). `Math.multiplyHigh` and
   `unsignedMultiplyHigh` are C2 intrinsics (`vmIntrinsics.hpp:181-182`, `library_call.cpp:
   2101-2109`): `imulq` and `mulq` on x86_64 (`x86_64.ad:8713-8737`), `smulh` and `umulh` on
   aarch64, never vectorized by SuperWord. At two long lanes on x86 C2 refuses
   `VectorRearrange` and `VectorLoadShuffle` (`x86.ad:1726-1730`) and every mask load, store and
   cast (`:1734-1744`), so every compare, blend and two-vector rearrange of a pair is the Java
   fallback there, as `m7` row 374 found; aarch64 lowers them.
10. **The census cells say the same.** `VARKA-376.md` 9 and 9.2: the four unsigned long compares
    and `rearrange2` intrinsic and allocation-free at 512 and 256 bits on the laptop and the EPYC
    7763, the Java fallback at 128 bits on x86, intrinsic on the Neoverse N2. 347's slot cells
    (`VARKA-347.md` 10.1) read the de-interleave and its check the same way, 584 to 672 bytes a row
    allocated at 128 bits.
11. **Row 503 declines a long-lane condition at two lanes on x86** (open, branch
    `varka-503-two-lane-decline`, uncommitted there): `VarkaKernelPlan.admit` refuses a condition
    root of a long-driven kernel where `spark.sql.codegen.varka.declineTwoLaneConditions` says the
    host refuses, while projections still fuse, because its measurement found filters losing to
    the row path at two long lanes and projections winning.
12. **The baseline exists for part of the row.** `VarkaDecimalBaselineBenchmark` (row 377,
    #1055) times `DECIMAL(38, 10)` on the row path at 256 and 128 bits on the EPYC 7763: a filter
    `x < y`, a choice, an arithmetic chain and a cast to `(18, 4)`. The comparison costs the row
    path 365 ns a row and the choice 388
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`), a
    `BigDecimal` per value. It has no `IN`, negation, `abs`, `<=>` or null test, no comparison in a
    projection (the form row 503 leaves fused at 128 bits) and no precision between 19 and 37.
13. **Row 489 is ordered before this row** ("Before decimal's lane pair (359)", the node
    descriptor) and is not started; row 497 (one `IN` arm keyed on the registry's lane) is open.

### What differs from the row text and the milestone plan

- **No pair node.** A pair is two long values, each an ordinary node; the pair exists in the
  compiler only (2.2). "One node, one lane" holds, and risk 4 of the milestone plan (the shape
  key, the budget and the reference evaluator learning a value that is not one lane) does not
  arise. The cost is two to five nodes per decimal operator.
- **The load's check is `|u| < 10^p` alone** (item 1): there is no high word to check.
- **Unequal scales above 18 are not this row's** (item 3): they need 360's rescale up and 363's
  rescale down, and decline naming them. Question 1.
- **The census counts nothing for the comparisons and choices when this row lands** (section 6),
  contrary to the note 347's reader wrote ("counts fused with 359's wider ones"). Question 2.
- **The scalar arm is a measured arm, not necessarily an emitted one** (2.1). Question 3.
- **Row 489 comes first in practice**: it is being built now, and 359a starts only after the
  baseline and the spike, so the order of the milestone plan holds with no wait. The design adds
  one leaf and four compare operators, so it depends little on 489 either way.

## 1. Goal and non-goal

A `DECIMAL(p, s)` column with `19 <= p <= 38` and `s >= 0` is read by a kernel from Arrow's
sixteen-byte slots as two long values per row, the low and the high word, with every batch Spark's
reader would raise on declined; compared, tested for null, chosen, negated, `abs`-ed and stored, at
one scale, with answers equal to Spark's. Before any of it, the three representations are measured
against each other (2.1). Not this row: arithmetic and `CheckOverflow` over sums and products
(360 to 363), the casts (364), unequal scales above 18 (360, 363), `hash` (337), the 64-bit store
(157), a negative scale.

## 2. Design

### 2.1 The three representations, and the measurement that picks

- **The de-interleaved pair (P).** Two long vectors per lane group, the low words and the high
  words, one row per lane; 347's load and the inverse interleave at the store. Every operator is
  lanewise, its carry or borrow a compare in the same lane.
- **Adjacent lanes (A).** The slots loaded as they lie, `(lo0, hi0, lo1, hi1, ...)`, half a row
  per lane; no shuffle at the load or the store, but every result that crosses words crosses a
  lane: a compare combines an even lane's unsigned result with its odd neighbour's (Highway's
  `Lt128`, about five operations, `READING_HIGHWAY.md` 3.5, after XORing the high lanes' sign bit
  so one unsigned compare serves both words), a borrow moves up a lane, and a row's condition is
  one bit of two lanes, compressed (`Long.compress`) to meet a long column's mask.
- **The scalar loop (S).** A per-row loop inside the kernel reading `lo` and `hi` at `16 * i` and
  computing in `long`s: `Long.compareUnsigned`, and for the arithmetic `Math.multiplyHigh` and
  `unsignedMultiplyHigh` (item 0.9), with no de-interleave.

**The admission check, step 2, before any engine code** (the milestone plan's 10.3 item 2). A
hand-written spike, as row 329's #977 was: `VarkaDecimalPairSpikeBenchmark` (catalyst, test
side, no emitter), each loop checked against `BigInteger` before it is timed and driven to level-4
code first, over the ladder of `VarkaDecimalLoadBenchmark`'s rows (in L2, in L3, past L3), at 512,
256 and 128 bits. Its loops, in P, A and S:

1. this row's operators over one column pair: the load with its `10^p` check alone; `x < y` into a
   selection word; `-x` and `abs(x)` stored; `CASE WHEN x < y THEN x ELSE y END` stored;
2. the milestone plan's three: the 128 by 128 product into 256 bits (P by sixteen `vpmuludq` of
   2.8; S by `multiplyHigh` and `unsignedMultiplyHigh`), the product's `10^38` check, and the
   `HALF_UP` rescale of a 128-bit value by `10^6` (P by 363's magic multiply; S by the same
   constants in scalar). The rescale is 363's design written twice and is the most speculative
   loop, so it is held to one `k`; the product and the check are 362's.

The verdict, per width and host, names the form each operator family takes. **P is built where
it wins** (this row's operators), A only if it beats P by the owner's margin on this row's
operators, and the arithmetic verdicts go to rows 361 to 363 as their admission readings. The
spike's loops stay committed as the reference arm; whether S is also emitted is question 3. The
runs are the laptop at 512, 256 and 128 bits (`MaxVectorSize`), whose datapath is 256 bits wide
so its 512-bit numbers are double-pumped and labelled so, and `benchmark.yml` on the EPYC 7763 at
256 and 128; `benchmark.yml` has no Arm runner, so the Neoverse N2 reading is the census's cells
only.

### 2.2 The IR: words, not pairs

A pair value is a compile-time record in `VarkaDecimalCompiler`, `Pair(VarkaVectorIR lo,
VarkaVectorIR hi, DecimalType type)`, never an IR node. Its words are long-lane nodes:

- **The leaf** `DecimalWord(int ordinal, Word word, int precision)`, `Word` being `LOW` or
  `HIGH`, precision 19 to 38, on the long lane, rendering `decw:<ordinal>:lo:p<precision>` (or
  `:hi:`), so it shares no class with 347's `dec:` leaf or a `BIGINT` column. The two words of one
  column share one load: `Slots.decimalTmp` is keyed by the ordinal for this leaf, and the first
  word a body emits loads `a` and `b`, takes both rearranges, arms the check and parks the other
  word, which its sibling loads. A body split into groups by the budget loads again in each group
  that reads the column, as 347's `perGroupBodiesUnderTheBudget` requires of the shuffles.
- **A 64-bit decimal widened to the pair** is `Pair(x, BitOp(SHR, x, 63))`: sign extension, an
  existing node. This serves an operand of precision 18 or less meeting a wider one at one scale,
  `d18 < d38`, which coercion casts to the wider type; the value-position cast stays 360's.
- **A literal** is two long-table slots, the unscaled `BigInteger`'s low and high words.
- **The one IR extension**: `CompareOp` gains `ULT`, `ULE`, `UGT` and `UGE`, lowered to the
  `VectorOperators` of the same names, evaluated by the reference with `Long.compareUnsigned`,
  drawn by the IR fuzzer's grammar in a sequence of its own seed. No SQL expression maps to them.
  Row 360's carry, `ULT(sum.lo, a.lo)`, is the same node.

Every pair kernel is long-driven; a pair in an int-driven kernel is refused, as 347's leaf is
(`VarkaVectorWalk.java:543-546`).

### 2.3 The registry: a type's lane apart from its storage

The owner's note of 27 October: a consumer must not take "long lane" to mean "an eight-byte long".
Concretely:

- `Info.storageBits` becomes `Storage`, a closed type: `Plain(int bits)` (a value in its own
  width), `Bitmap` (`BOOLEAN`) and `DecimalSlot` (sixteen bytes, low word first).
- The admission's answer for one `DataType` becomes a `Read`: `Lanes(LaneType lane, int words)`
  over a `Storage`. Every type but the decimal is `Lanes(lane, 1)` over `Plain`; the decimal is
  `Lanes(LONG, 1)` over `DecimalSlot` for precision 18 or less and `Lanes(LONG, 2)` over
  `DecimalSlot` above. `UpToPrecision` becomes `ByPrecision`, a list of `(maxPrecision, words)`
  bands with the long lane, so `declineReason` names a band's row and the negative scale.
- `VarkaTypes.lane(DataType)` answers only for `Plain` storage, so `VarkaTimeCompiler`'s long
  column arm can no longer see a decimal; the decimal compiler asks `read(DataType)`. `Info.lane()`
  becomes `Info.lanes()`, the lanes a kernel loads in, for the warm-up and `types.tsv`.
- `types.tsv` gains `storage` and `words` columns; `dev/varka_new_lane.py`, `VarkaLaneArrowSuite`,
  `VarkaTypeConformance`, `VarkaArrowSource` and `VarkaCoverageDifferentialSuite`, which read the
  table, follow. Every consumer of `lane(DataType)` and `storageBits` is listed in the step's
  pull request, in the main and the test source sets, each with the answer it now gets.

No emitted byte moves: the registry is compile-time data.

### 2.4 The load, its check, and the store

The load is 347's, item 0.5, unchanged in its loads, shuffles, epilogue masks, segment mapping,
status bit, field check and null-test rule. The check differs: a valid slot declines where
`u > 10^p - 1` or `u < -(10^p - 1)`, each a 128-bit signed compare against a constant pair
(at `p = 19` the upper bound's high word is 0 and its low word has the sign bit set, since
`2^63 < 10^19 - 1 < 2^64`, so the unsigned low compare carries the check there, and the lower
bound's high word is -1 over a low word with the sign bit clear; from `p = 20` the upper bound's
high word is positive):

    gt128(u, c) = GT(u.hi, c.hi) | (EQ(u.hi, c.hi) & UGT(u.lo, c.lo))

two such compares and one `or`, eleven lane calls against 347's six, under `emitGuardCollect`
as there.
This check is the row's "`10^p` overflow check per lane group": a condition over a lane group's
two words, folded once a body. Its first disposition is the load's decline; 360 reuses the same
condition for `CheckOverflow`, whose LEGACY answer is a null and needs the null disposal rows 332
and 343 share (`VARKA-343.md` 0, item 2).

**The store** is the inverse: `lo.rearrange(ZIP_LOW, hi)` and `lo.rearrange(ZIP_HIGH, hi)`, two
two-vector rearranges with prologue shuffles, then two stores (masked in the epilogue). An output
of a decimal type binds two roots, the two words, to one `DecimalVector` output, and the words'
validity is one word (both derive from the same inputs; `Analysis` refuses a pair whose two words
differ in it). Row 157's 64-bit store is the same interleave with `hi = lo >> 63`; whichever row
lands first builds `VarkaLaneArrow`'s decimal allocator and the output binding, and the order of
the milestone plan's 4.1 puts 157 first.

### 2.5 The lowerings

Lane calls per lane group of P, the leaves' loads and rearranges counted once per column above:

| form | lowering over words | calls |
|---|---|---|
| `x = y` | `EQ(x.hi, y.hi) & EQ(x.lo, y.lo)` | 3 |
| `x < y` | `LT(x.hi, y.hi) \| (EQ(x.hi, y.hi) & ULT(x.lo, y.lo))` | 5 |
| `x <= y` | as `<` with `ULE` | 5 |
| `>`, `>=` | the operands swapped | 5 |
| `x <> y` | `NOT(x = y)`, as 347's reader builds it | 4 |
| `x <=> y` | `NullSafeEqual` on each word, joined by `AND` | 3 |
| `x IN (c1..ck)` | an `OR` chain of `x = ci`, two slots per literal | `4k - 1` |
| `IS [NOT] NULL` | `IsNotNull` over either word, the check emitted under it | 1 |
| choice | `IfElse` on each word, one shared condition | 2 |
| `greatest`, `least` | `IfElse` on each word under `b IS NULL OR (a IS NOT NULL AND a >= b)` | 11 |
| `-x` | `lo' = -lo`; `hi' = (hi XOR -1) + CondValue(EQ(lo, 0))` | 6 |
| `abs(x)` | `IfElse(LT(x.hi, 0), -x, x)` on each word | 9 |

The borrow of the negation is the only carry here: `-u = ~u + 1`, and the `+ 1` carries into the
high word exactly where `lo = 0`. The three-valued rules hold word by word: a null row makes every
word's compare unknown, and `unknown OR (unknown AND unknown)` is unknown. A comparison by a
subtraction's sign was considered and rejected: `|x - y|` reaches `2 * 10^38 - 2`, past `2^127`.
The counts above are written from the lowerings and registered by `dev/varka_emit.sh` when built.

### 2.6 At 128 bits, under row 503's rule

At two long lanes on x86 every pair operator is the Java fallback (items 0.9, 0.10), and a pair
kernel is long-driven, so row 503's rule applies as it stands: a decimal comparison as a filter
conjunct or a selection output declines there by construction, and a projection - a choice, a
negation, `abs`, a comparison as a value - fuses and is measured against the row path, which costs
365 ns a row at `(38, 10)` (item 0.12). If a projection loses there, it declines by the same
mechanism with its measured reason, a recorded refusal with `m9/SCOPE.md` item 101 as its scope
item. On aarch64 the pair fuses at 128 bits (the N2's cells all intrinsic).

### 2.7 Compiler, reference, cost, warm-up

`VarkaDecimalCompiler` builds the pairs and the table of 2.5 in its condition and value arms,
which today decline above 18 naming this row; `VarkaTimeCompiler`'s decimal arm narrows to the
forms still owed. The reference evaluator needs nothing beyond the unsigned compares. The cost
model gets a feature for the word leaf, on row 352's awaiting-refit list, and the budget a weight
row. The warm-up's long block gains the zip shuffles and the unsigned compares, held by
`VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The slot table** (`VarkaDecimalLoadSuite`'s pair half, `ownJvm`), at 2, 4 and 8 long lanes,
   precision 19, 20, 30 and 38, lengths 1, 3, 37 and 64, both bodies: `10^p - 1` and
   `-(10^p - 1)` pass, `10^p`, `-10^p`, `2^127 - 1` and `-2^127` decline, in the first group, a
   middle one and the last row, valid and under a null (which never declines); the oracle reads
   the same vector through `ArrowColumnVector.getDecimal`.
2. **The word edges**, through each operator of 2.5 against `BigDecimal`: a low word with its sign
   bit set under a high word of 0 (where a signed low compare is wrong), `lo = 0` under each high
   word (the borrow), equal high words with low words either side of `2^63`, `hi = -1`, and every
   null pattern of a pair.
3. **The SMT proofs**, `sql/varka/proofs/decimal128.smt2` under Z3 and cvc5, over integers in 2.8's
   two layers. The ring layer: with `v = hi * 2^64 + lo` for `hi` in `[-2^63, 2^63)` and `lo` in
   `[0, 2^64)`, the lowering of `<`, `<=`, `=` and `<=>` equals the integer comparison of the two
   values, and for each `p` from 19 to 38 the check's two compares against the constant pair hold
   exactly when `|v| <= 10^p - 1`. The carry layer: the words as emitted, Java's wrapping stated by
   `mod 2^64` and an unsigned compare as the compare of the words read `mod 2^64`, the borrow as
   `ite(lo = 0, 1, 0)`, never a bitwise operation on a negative; negation and `abs` give `-v` and
   `|v|` for every `|v| <= 10^38 - 1`, with no wrap. All linear; there is no limb product in this
   row. The file's solver times are stated before and after, against risk 5.
4. **Coverage rows**, a "DECIMAL lane pair" family over `d19` (`DECIMAL(19, 2)`), `d38` and `d38b`
   (`DECIMAL(38, 2)`) and `d3810` (`DECIMAL(38, 10)`, the baseline's type), the fixtures derived
   from `l` and `l2` so both words carry bits and both signs and every value stays within its
   precision: the six comparisons, `<>`, `<=>`, a literal wider than a long, `d18b < d38` across
   the boundary at one scale, `IN`, both null tests, `CASE`, `coalesce`, `greatest`, `least`,
   `-d38` and `abs(d38)` as outputs, each naming its verification for row 490's check
   (`decimal128.smt2`'s sections, or `differential`). The flipped tests: `VarkaTypesSuite`'s
   decline now for a negative scale only.
5. **The conformance suite.** `VarkaTypeConformance` builds four- and eight-byte batches and reads
   a value as a long (the reader branch's step 5, its departure 2), so this row teaches it
   sixteen-byte slots and a `Decimal` oracle and lands `VarkaDecimalConformanceSuite` over both
   representations, discharging the registry's `Owed("Decimal", "348")`. Question 4.
6. **The differential and the matrix**: the coverage differential over every fixture under both
   consumers, the golden suite's Varka arm, and the option matrix dispatched on the branch.
7. **The generated files** in `ADDING_A_TYPE.md`'s order. `emitted_bytes.json` adds the new rows
   and moves no existing shape; `width_audit.json`'s 128-bit column records the refusals of 2.6.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code.** Row 377's class covers this
row's filter and choice at `(38, 10)`. It gains, at `(38, 10)` and at `(20, 2)`: `x IN (c1, c2,
c3)` and `x <=> y` as filters, `x IS NULL` as a filter, `-x < y` and `abs(x) < y` as filters
(a lone negation is as fast as a copy, row 482's lesson), and `x < y` as a projected value, the
form row 503 leaves fused at 128 bits. Run on the EPYC 7763 at 256 and 128 bits, committed beside
the class's files.

Predictions, registered before the spike and the build:

1. **The spike, this row's operators:** P beats S by 2 to 4 times in L2 at 512 and 256 bits, and
   they come within 2 times of each other past L3, where reading sixteen bytes a row binds both.
   P beats A for every operator with a compare, and A ties or wins the bare choice and store.
2. **The spike at 128 bits on x86:** S beats P and A by 5 to 20 times, both of these being the
   allocating fallback.
3. **The spike's arithmetic:** for the product, S beats P at 256 bits on AVX2 (no 64-bit vector
   multiply) and P beats S at 512; the `10^38` check, P by 2 to 4 times at 512 and 256; the
   rescale, a tie at 256 and P ahead at 512.
4. **The build against the baseline** at 256 bits: the comparison filter and the choice 10 to 50
   times faster than the row path, which pays a `BigDecimal` per value.
5. **At 128 bits on x86:** the filters decline (2.6); the projections still beat the row path, by
   2 to 5 times.
6. **No existing shape's bytes move**; the `option_arms` digests move only if an option is added.

## 5. Order, files and size

1. **The baseline addition** (4), its own pull request. Done (31 October 2026): merged as
   `f0c1ab11c45`, its 24 sections' runner results committed in
   `VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` (256 bits, run 41235267549)
   and its `-128bit` companion (`-XX:MaxVectorSize=16`, run 41237311797), both on `2297f833e3b`;
   none fuses, and each section's two arms tie.
2. **The spike** (2.1), its own pull request: the benchmark, its results files, the verdict in
   section 10 of this file, and census cells for the unsigned compares over slots if the loops
   need any the census lacks.
3. **359a, the pair's reading half:** the unsigned `CompareOp` members, the registry's lane and
   storage (2.3), the word leaf, its load and check, the comparisons, `<>`, `<=>` and the null
   tests, the literal pair, the sign extension at one scale, the proof file, the slot table, the
   coverage rows of those forms, the conformance suite's sixteen-byte slots.
4. **359b, the value half:** the store and the output binding, `IN`, the choices, negation and
   `abs`, their coverage rows, the measurement against the baseline at both widths and 512 on the
   laptop, and the verdict with 503's rule applied.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `DecimalWord`, the unsigned `CompareOp` members, canonical form |
| `varka/VarkaTypes.java` | `Storage`, `Read`, `ByPrecision`, the entry |
| `varka/Analysis.java`, `varka/Slots.java` | the shared load by ordinal, the pair output's word |
| `varka/VarkaVectorWalk.java`, `varka/VarkaBodyEmitter.java` | the check, the store, the zips |
| `VarkaDecimalCompiler`, `VarkaConditionCompiler.java`, `VarkaTimeCompiler.java` | 2.5's forms |
| `VarkaOutputSpec.java`, `CompiledVarkaProjection.java` | an output of two roots |
| `VarkaLaneArrow.java`, `VarkaEvaluatorBase.java` (sql/core) | the allocator, the output vector |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weights, features, reach |
| `sql/varka/proofs/decimal128.smt2`, `VarkaProofFiles.java` | section 3 item 3's claims |
| tests | slot table, word edges, coverage rows, conformance suite, fuzz sequence |
| benchmark | the baseline addition, `VarkaDecimalPairSpikeBenchmark`, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `types.tsv`, `census.tsv` |

**Size: large, in four pull requests.** The baseline addition about half a day; the spike about
two days; 359a about four (the registry change touches every reader of the table, item 0.6); 359b
about three. The test time added: the slot table and word edges at four precisions and three lane
counts are of the size of 347's, which runs in seconds; the proof file is priced from its spiked
form, at VARKA-245's observed ratio in the calibration table, under ten seconds per solver. It rests
on row 347 (steps 4 and 6 open, its branches unmerged), 348 for the unequal scales below 19, 157
for the 64-bit store, and 503 for the 128-bit rule.

## 6. What it unlocks in the census

The census counts a decimal pair fused only when it fuses at every precision up to 38, both
representations (the milestone plan's 2.8, as row 347 step 5 found), so no decimal pair counts
until this row lands; and a pair is every form a query can give it, unequal scales included.
Of the census's 68 decimal pairs:

- **4 count when this row lands**, given 347's reader for the first two and 348 and 157 for the
  64-bit half of the last two: `isnull`, `isnotnull`, `negative`, `abs`. Their forms carry no
  coercion.
- **22 fuse at one scale here and count only after 360 and 363** (the rescale up and the capped
  rescale down of item 0.3), with 348 and 157 below 19: the comparisons `=`, `==`, `<`, `<=`, `>`,
  `>=`, `<=>`, `equal_null`, `in`, `in_set` and `between` (333's rewrite), and the choices
  `coalesce`, `if`, `when`, `ifnull`, `nvl`, `nvl2`, `nullif`, `nullifzero`, `zeroifnull`,
  `greatest`, `least`. Even `nullifzero` and `zeroifnull` rescale: their literal 0 is
  `DECIMAL(1, 0)`, and over `DECIMAL(38, 38)` the wider type is capped at `(38, 37)`.
- **42 are other rows'**, to which this row gives the load, the store and the `10^p` check:
  arithmetic and its `try_` forms (360 to 363), `%`, `mod`, `pmod`, `try_mod` (363), the rounding
  functions (351, 363), the ten casts and `try_cast` (350, 364, 366), `hash` and `xxhash64` (337),
  `check_overflow` (349, 360, 363), the five functions over decimal seconds (351), the interval
  products and quotients (349), `unscaled_value` (349, precision 18 or less by Spark's own rule),
  and `positive`, `known_not_null` and `try_eval` (333, 330).

So the row moves the fused pair count by 4, and the census's notes on the 22 are corrected in
359a's pull request to name 360 and 363 instead of "counts fused with 359's wider ones".

## 7. Out of this row, and declines that stay

Out: 2.8's arithmetic (360 to 363) and the arithmetic arms of the spike, which only inform them;
the casts (364); `hash` (337); a NEON host with no SVE (not in the census); a big-endian host.

Declines that stay, each naming its row: unequal scales above 18 (360, 363), a decimal in an
int-driven kernel, a negative scale (2.8), an Arrow field of another width or scale, and at two
long lanes on x86 every decimal condition root (503) and any projection the measurement shows
losing there.

## 8. Questions for the owner

Answered by the owner on 27 October 2026: (a) to all four - unequal scales above 18 decline
naming 360 and 363, a pair counts only when its unequal scales fuse too, the scalar loop stays the
measured reference arm, and this row teaches the conformance suite sixteen-byte slots.

1. **Unequal scales above 18.** (a) Decline them naming 360 and 363, as 347 declined unequal
   scales naming 348. (b) Build the rescale up here. *Recommended: (a)*: the rescale up of a
   128-bit value is a 128 by 64 multiply and the capped rescale down is 363's division, neither of
   which this row has, and both rows own them already.
2. **The census rule at unequal scales.** The milestone plan's 2.8 already counts a decimal pair
   only when it fuses at every precision up to 38; it does not say whether unequal scales are part
   of "every form". (a) They are, so this row moves 4 pairs and the 22 wait for 360 and 363. (b) A
   pair counts at one scale. *Recommended: (a)*: a user's query at two scales would still reach
   the row path, and the done-when's share claims the row engine's answer for every form.
3. **The scalar arm.** (a) The spike's loops and the reference evaluator are the reference arm, and
   S is emitted only at a width where the spike shows it beating both P and the row path, as a
   row of its own. (b) Emit S in this row as an option arm everywhere, the default where it wins.
   *Recommended: (a)*: an emitted per-row loop is a new emission mode (a scalar subtree feeding a
   vector kernel through a scratch word), and prediction 2 places its only win at 128 bits on x86,
   where 503's rule already decides what fuses. The standing rules to build competing designs and
   keep losing option arms are met by the spike, which builds all three forms and stays committed;
   what (a) defers is a new emission mode, not an arm of an existing one.
4. **The conformance suite.** (a) This row teaches `VarkaTypeConformance` sixteen-byte slots and
   lands the decimal suite over both representations. (b) Leave it to 348. *Recommended: (a)*:
   this is the row that makes a type's lane differ from its storage, and the contract is where
   that difference is held for every check.

## 9. Done when

Row 359's text holds, with the corrections above: the spike's verdict is committed per width
before the build; at every precision from 19 to 38 the comparisons, `<>`, `<=>`, `IN`, the null
tests, the choices, negation and `abs` fuse at one scale with coverage rows, and the store writes
the pair; the slot table and the word edges pass; `decimal128.smt2` proves the comparisons, the
`10^p` check, the borrow, negation and `abs` in both layers under both solvers; the decimal
conformance suite passes under question 4's answer; the bytes oracle is unchanged for every 64-bit
shape; the measurement against the baseline is committed at both widths with its predictions
scored; and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)
