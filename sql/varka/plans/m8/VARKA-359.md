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
only. *Corrected 8 November 2026:* it has one now, its `runner` input taking `ubuntu-24.04-arm`, a
Neoverse N2 (row 575), and a run of `VarkaDecimalPairSpikeBenchmark` there committed its results as
`2095329e157` on the fork branch `varka-359-arm-results`, now
`VarkaDecimalPairSpikeBenchmark-jdk25-runner-arm-128bit-results.txt` with its provenance file,
read in 10.1.

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

(Written when the work lands; the spike's reading is below.)

### 10.1 Step 2: the spike's reading, the laptop beside the EPYC 7763

*1 November 2026, at `5c660201b35`. The laptop (AMD Ryzen AI 9 HX PRO 370, JDK 25.0.4.1, pinned
to the four fast cores, both heavy locks held, canary ok) at `-XX:MaxVectorSize=64`, `32` and
`16`, written to `sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-laptop-results.txt`
and its `-laptop-256bit` and `-laptop-128bit` companions, each with a provenance file. The EPYC
7763 columns are the two `-runner-epyc7763` files of #1250. The laptop's datapath is 256 bits
wide (`dev/varka_datapath.sh` read 512:256 as 1.14 against a 256:128 control of 2.01), so its
512-bit column is eight long lanes issued as two 256-bit halves: double-pumped, not a wider
machine. The canary and the datapath probe ran once, before the three widths; each width's provenance file
records the machine's load at its own start (1.15 at 512 bits, 1.10 at 256, 1.60 at 128), so the
128-bit run, the last of the three, has no idle check of its own. Its columns are read only for
the 2x to 3x gap between A and P, which no such load explains, and decide nothing.*

Nanoseconds a row, P / A / S (the arithmetic P / S), from the files' `Per Row(ns)` column:

| section | rung | laptop 512 | laptop 256 | laptop 128 | EPYC 256 | EPYC 128 |
|---|---|---|---|---|---|---|
| the load and its 10^38 check | in L2 | 0.4 / 0.4 / 0.6 | 0.5 / 0.7 / 0.6 | 63.3 / 32.7 / 0.6 | 0.9 / 1.5 / 1.6 | 135.3 / 66.9 / 1.6 |
| | in L3 | 0.4 / 0.4 / 0.8 | 0.5 / 0.7 / 0.8 | 65.2 / 33.0 / 0.8 | 0.9 / 1.5 / 1.8 | 137.5 / 67.0 / 1.8 |
| | past L3 | 0.4 / 0.4 / 0.9 | 0.6 / 0.7 / 0.9 | 63.7 / 32.7 / 0.9 | 1.1 / 1.6 / 1.9 | 136.3 / 66.6 / 1.9 |
| `x < y` into a selection word | in L2 | 0.3 / 0.3 / 0.6 | 0.3 / 0.5 / 0.6 | 71.0 / 20.7 / 0.6 | 1.0 / 1.0 / 1.4 | 165.2 / 43.7 / 1.4 |
| | in L3 | 0.3 / 0.3 / 4.3 | 0.3 / 0.5 / 4.4 | 73.8 / 23.2 / 4.5 | 1.1 / 1.0 / 5.9 | 168.7 / 44.7 / 5.9 |
| | past L3 | 0.6 / 0.6 / 4.7 | 0.6 / 0.6 / 4.6 | 72.1 / 23.7 / 5.5 | 1.5 / 1.5 / 6.2 | 168.7 / 45.2 / 6.3 |
| `-x` stored | in L2 | 0.2 / 0.1 / 0.5 | 0.3 / 0.2 / 0.5 | 60.7 / 30.5 / 0.5 | 1.0 / 0.6 / 0.7 | 143.2 / 68.0 / 0.7 |
| | in L3 | 0.3 / 0.2 / 0.5 | 0.3 / 0.3 / 0.5 | 62.0 / 30.4 / 0.5 | 1.1 / 0.6 / 0.8 | 143.4 / 67.4 / 0.8 |
| | past L3 | 0.7 / 0.7 / 0.8 | 0.7 / 0.7 / 0.8 | 60.7 / 30.2 / 0.8 | 1.3 / 1.1 / 1.2 | 142.8 / 67.8 / 1.3 |
| `x * y` into 256 bits | in L2 | 2.0 / 3.2 | 2.2 / 3.2 | 56.8 / 3.3 | 5.4 / 7.1 | 142.9 / 7.0 |
| | in L3 | 2.2 / 5.3 | 2.4 / 5.3 | 57.9 / 5.4 | 5.5 / 10.6 | 143.1 / 10.6 |
| | past L3 | 2.6 / 5.6 | 2.8 / 5.6 | 57.6 / 6.9 | 5.6 / 10.9 | 141.8 / 11.0 |
| `HALF_UP` of `x / 10^6` | in L2 | 2.2 / 3.4 | 2.5 / 3.6 | 112.8 / 3.7 | 5.5 / 7.0 | 232.9 / 7.1 |
| | in L3 | 2.2 / 7.9 | 2.5 / 8.4 | 116.9 / 8.3 | 5.6 / 13.1 | 233.1 / 13.1 |
| | past L3 | 2.3 / 7.9 | 2.5 / 7.9 | 114.3 / 8.4 | 5.6 / 14.0 | 233.1 / 14.0 |

At the laptop's speeds the one-decimal `Per Row(ns)` column cannot separate P from A, so the rule
below is read on the `Rate(M/s)` column, which the harness derives from each arm's best time: the
comparison by minimums AGENTS.md asks for.

**The rule** (the 359a plan's 11.3, on the load and `x < y` at the in-L3 and past-L3 rungs):

- *A against P, at 512 bits:* the load is P's, 2771.8 against A's 2643.3 M rows/s in L3 and
  2565.8 against 2433.2 past it; `x < y` is A 3511.4 against P 3206.4 in L3 and a tie past it,
  1693.6 against 1698.3. Nowhere near 1.3x, and P leads the load. *At 256 bits:* P leads the load,
  1882.6 against 1417.4 and 1814.0 against 1392.6, and `x < y`, 3006.7 against 2050.5 in L3, ties
  past L3 at 1711.6 against 1655.8. Rule 2 does not hold at either width.
- *S against P, at 512 and 256 bits:* S is slower on both sections at both rungs (the load
  1205.8 against 2771.8 in L3 at 512, `x < y` 226.0 against 3006.7 in L3 at 256). Rule 3 does
  not hold.
- *At 128 bits* A beats P on both sections at both rungs by about two to three times (the load
  30.3 against 15.3 M rows/s in L3, `x < y` 43.1 against 13.6), so rule 2's words, read alone,
  are met there - as they already are by the EPYC's 128-bit column (67.0 against 137.5 ns a row
  on the load). Both are two-lane x86 columns where P and A are the Java fallback and S runs at
  the speed of the wider widths; the 359a plan's 11.4 decides that case (every 359a condition
  declines there, S is a row of its own), and its 11.3 (amended on 7 November 2026 to say so)
  limits rule 2 to widths of more than two long lanes, so neither x86 host's 128-bit column
  triggers the ask. That limit is a reading of the rule the owner has not yet confirmed; the pull
  request that merges this plan asks for it. The aarch64 128-bit column, where the pair runs
  natively, is not among those columns: it has no reading yet and 359a measures it (11.3,
  11.8 point 5).

**Verdict: build P.** Neither A nor S wins by the rule's margin at 256 or 512 bits on either host;
the laptop's 512-bit column, double-pumped on its 256-bit datapath, agrees with its 256-bit one.
The reading also shows the arithmetic travelling as section 2.1 expects: P's product and rescale
lead S at 512 and 256 bits on the laptop as on the EPYC (2.2 against 5.3 and 2.2 against 7.9 ns a
row in L3 at 512), and S leads everything at 128 bits.

**The aarch64 column at 128 bits** (8 November 2026). Run 42437050350 of `benchmark.yml` on the
`ubuntu-24.04-arm` runner (row 575; the file's processor line is empty, the job's runner label
is the source), committed as `VarkaDecimalPairSpikeBenchmark-jdk25-runner-arm-128bit-results.txt`
with its provenance file. NEON is 128 bits wide, so this is the two-lane column where the pair
runs natively (the width rule's case), the one 128-bit column the decision rule's
replan clause is applied to. By `Rate(M/s)`:

- *A against P:* the load is P's, 430.1 against 93.3 M rows/s in L3 and 410.5 against 93.1 past
  it; `x < y` is P's, 475.2 against 182.8 and 435.6 against 181.7. A is 2.4 to 4.6 times slower,
  so rule 2 does not hold and the aarch64 case asks for no replan.
- *S against P,* read for the record (rule 3 names only 256 and 512 bits): S leads the load,
  530.8 against 430.1 in L3 and 503.0 against 410.5 past it, 1.23 times, under the 1.3x margin
  and so a tie by rule 4; P leads `x < y`, 475.2 against 220.2 and 435.6 against 213.1.

**Verdict on aarch64: build P**, as on x86. The arithmetic reads as on the 128-bit x86 columns
only in part: S leads the product into 256 bits (6.4 against 12.8 ns a row in L3) and the
`HALF_UP` rescale (8.4 against 13.3), but P leads the product's `10^38` check (2.9 against 4.8),
readings that travel to rows 361 to 363 as their admission inputs on Arm.

### 10.2 359a, part 1: the layout-free base (6 November 2026)

*Branch `varka-359a-pair2`, from master at `511bb552070`; local commits only. The first local
attempt (`varka-359a-pair`, 1 and 2 November) built all of 359a in one 6000-line branch against a
master that has since moved (row 503's two-lane decline, row 157's store, row 348's rescales); this
re-lands it in parts under the 1000-line cap, ported by hand where master moved. The verdict of
10.1 stands: P is built.*

Part 1 is 11.5 items 1 to 3, which hold whatever layout carries the words and move no emitted
byte:

1. **The registry** (11.5 item 1). `Info.storageBits` is now `Storage` (`Plain`, `Bitmap`,
   `DecimalSlot`); `VarkaTypes.read(DataType)` answers a `Read.Lanes(lane, words, storage)`; the
   decimal's admission is `ByPrecision` over two `Band`s, 18 digits and one word (row 347, read)
   and 38 digits and two words (row 359, owed). `lane(DataType)` answers only for plain storage,
   so `VarkaTimeCompiler`'s long-column arm cannot take a decimal for eight-byte longs. The
   decimal compiler's column test asks `read`. Row 157's output decline keeps its messages, now
   read off the bands (`ByPrecision.oneWordPrecision`). `types.tsv` gains `storage` and `words`.
2. **The unsigned compares** (item 2). `CompareOp` gains `ULT`, `ULE`, `UGT` and `UGE`; a `Compare`
   refuses one over a floating lane and a `BoolCompare` refuses them all. The walk already emits
   a compare by its operator's name and the budget weighs them as any integral compare. The
   reference orders them by `Long.compareUnsigned`. The committed corpora draw
   `CompareOp.signed()`, so nothing they draw moves; the unsigned ones have a long-lane draw of
   their own (`UNSIGNED_FUZZ_SEED`), and their cost features are on row 352's refit list.
3. **The ring layer** (item 3), `proofs/decimal128.smt2` from `VarkaProofFiles` over
   `DecimalWords`: the word lowerings of `<`, `<=`, `=`, `>`, `>=` and `<=>` equal to the value
   comparisons, the signed low compare refuted, and for each precision 19 to 38 the check over the
   constant pairs of `DecimalWords.bound` exact, with a bound one off either way refuted. 67
   checks, holding under Z3 0.03 s and cvc5 0.07 s on the laptop.

**Departures.** `Info.lane()` is kept: it says the lane a type computes on, whatever its storage.
`types.tsv` keeps `storage_bits` beside the new columns. A band carries its row and whether its
reader is built. The unsigned draw runs a third of the corpora's iterations: at the full count it
took about three and a half minutes on the laptop, which the Build would pay on every run.
*Corrected 8 November 2026:* the three and a half minutes is the first attempt's reading at the
full count, carried over; this part did not measure it again.

**Not in this part**, owed in the following ones: the compiler's pair (11.5 item 4), the word
leaf, its shared load and check, the lowerings, the carry layer, the slot table and word edges,
the coverage rows and the conformance suite (11.5 item 5, 11.6).

### 10.3 359a, part 2: the word leaf, its shared load and its check (6 November 2026)

*Same branch, on part 1. Local commits only.* 11.6 items 7 and 8, hand-ported from the first
attempt's branch onto the master that now holds row 348 and row 157, without the planted faults
and without the lowerings, which are the next part's.

7. **The word leaf and its shared load.** `VarkaVectorIR.DecimalWord(ordinal, Word, precision)`,
   precision 19 to 38 on the long lane, rendering `decw:<n>:lo:p<p>` or `:hi:`. `Slots.decimalTmp`
   holds four locals per column under its low word's key (the two vectors of slots, then the low
   and the high words), and `Slots.pairLoaded`, cleared with `emittedUnshared` at each body, says
   whether the body has loaded the column: the first visit of either word loads, takes both
   rearranges, parks both words and checks; every later visit reads its word's local
   (`VarkaVectorWalk.emitDecimalWord`). Both words carry `Refusal.DECIMAL_SLOT`, whichever is
   visited first collecting it. `Analysis` registers the column at sixteen bytes a row
   (`decimalInputs`), and a null test over a word visits its leaf for the check, as the 64-bit
   leaf's does. The budget weighs each word the whole load (`DECIMAL_PAIR_LOAD_WEIGHT`, 9) and
   `GroupOps` takes it back from the second word of a column in a group; the cost model's feature
   `DecimalWord/<word>` joins row 352's awaiting-refit list. Every site that lists the IR's
   nodes learned the new one: the plan's switch over long-narrow roles, the same-answer walk, the
   range analysis, the operand positions, the word owners, the canonical parser, the reference
   evaluator, the lane-type, children, cost, budget and fuzz suites.
8. **The check**: `v > B` as `GT(hi, bhi) | (EQ(hi, bhi) & UGT(lo, blo))` and `v < -B` as
   `LT(hi, nhi) | (EQ(hi, nhi) & ULT(lo, nlo))`, ORed, ten lane calls and five mask calls, under
   `emitGuardCollect` and never qualified by an arm.

**Tests.** `VarkaDecimalWordSuite` (`ownJvm`, at 1, 2, 4 and 8 long lanes, both bodies): the slot
table over precisions 19, 20, 30 and 38 and lengths 1, 3, 37 and 64 (`10^p - 1`, `-(10^p - 1)`,
`2^63` and `-2^63 - 1` passing; `10^p`, `-10^p`, `2^127 - 1` and `-2^127` declining at the first,
middle and last row, valid and under a null; the oracle Spark's `ArrowColumnVector.getDecimal`), a
body reading both words loads and rearranges once, a null test still checks the slots, and the
leaf's record.

**Departures.** The pair's locals are four of their own, not `guardTmp`, because the check reads
both words and a body may hold only one of them. Each word weighs the whole shared load, since
the first visit of either emits it, not the half-and-half split the first attempt began with.

**Not in this part**, owed: the compile-time pair and the lowerings (11.6 item 9), the carry layer
(item 10), the coverage rows (item 11) and the generated files they move, the planted faults for
row 504, and the conformance suite (11.5 item 5).

**The review's fixes (8 November 2026).** `Analysis` refuses a column whose two words carry two
precisions and one read both as a `DecimalColumn` and as words; `dropDecimalBound` drops the pair's
check too, a fault test showing the slot table catches it; a repeated word takes no CSE shared slot
beside its parked local; the long-lane harness lays a word column out as sixteen-byte slots and
the reference answers its words; the shared-load test reads the epilogues as well; one precision
check in `DecimalWords`.

**Owed, as a later part of this row: one pair-load node.** The shared load is side state on two
leaves: `Slots.pairLoaded`, `decimalTmp` keyed by the low word, the `guardScratch` exemption, the
CSE exemption, `GroupOps` taking the load back from the second word and the budget suite's
`pairedColumns`, and both words carrying the refusal. One `DecimalPair(ordinal, precision)` node
that loads, checks and parks both words, which the two words project, would hold all of it in one
place. Not here: a new node is a row in every exhaustive switch this part already touched, past
this pull request's 1000-line cap, and it reopens 2.2's words-not-pairs choice, which the
lowerings of the next part are planned on. The next part decides it before its lowerings land.

### 10.4 359a, part 3: the comparisons and null tests over the words (8 November 2026)

*Branch `varka-359a-part3`, from master at `d37d1053215`, which holds parts 1 and 2 (#1392,
#1400). Local commits only.* 11.5 item 4 and 11.6 items 9 to 11, for 359a's forms alone: `=`,
`<`, `<=`, `>`, `>=`, `<>`, `<=>`, `IS [NOT] NULL`, a literal operand and `d18 < d38` at one scale.
`IN`, the choices, negation and `abs` are 359b's (section 5 item 4), so of the baseline's forms
this part fuses `<=>`, `IS NULL` and `x < y` as a filter and as a value.

**The decision 10.3 leaves to this part: no pair-load node yet.** The lowerings are built over
the two word leaves, as 2.2 plans them, and the `DecimalPair` node stays owed to a later part of
this row. From the plan's own rules: 2.5's forms are written over `x.hi` and `x.lo` as ordinary
long-lane nodes and read the same whether those are leaves or projections of a pair node; the
compiler builds a column's words in one method (`columnPair`), the one place such a node would
change; and the node is a row in every exhaustive switch part 2 touched, past this part's cap.

**Built.**

9. **The registry and the compiler's pair.** The 38-digit band is read: `VarkaTypes.read` answers
   `Lanes(LONG, 2, DecimalSlot)` above 18 digits, and `declineReason` only a negative scale.
   `VarkaDecimalCompiler.Pair(lo, hi, type)` is a value's two words: a column's two
   `DecimalWord` leaves, a constant's two long-table slots, a value within 18 digits and its sign
   extension `BitOp(SHR, x, 63)`, or a cast that keeps the scale and widens the precision over any
   of these. `DecimalWords.compare` and `nullSafeEqual` are 2.5's lines, `>` and `>=` the operands
   swapped; `<>` is the `Not` over `=` the condition compiler already builds; a null test is
   `IsNotNull` over the low word, which visits the leaf for the check. A comparison as a value is
   row 27's boolean output over the same condition, with nothing of its own.
10. **The declines.** Every other wide form keeps its reason (row 359 for negation, `abs`, the
    choices and `IN`, rows 360 to 363 for arithmetic); a rescale up into a wide type names rows
    359 and 360 as before; a cast that lowers a wide value's scale, which is the cast coercion puts
    over the larger scale of `DECIMAL(38, 10) < DECIMAL(38, 2)` at the capped common type, now
    names row 363 beside 350, by question 1's answer. Two pinned reasons of
    `VarkaDecimalArithmeticSuite` gain the 363.
11. **The carry layer** of `decimal128.smt2`: the five comparisons over the words as Java longs,
    the unsigned compare as `Long.compareUnsigned`'s compare with both sign bits flipped, and the
    sign extension's words holding the long. 73 checks, from 67; Z3 0.02 s and cvc5 0.07 s on the
    laptop, against 0.03 and 0.07 before. With the flip planted as the identity the four ordering
    claims turn `sat`.
12. **Coverage rows**, the family "DECIMAL lane pair" over `d19`, `d38`, `d38b` and `d3810`, the
    comparisons proven by `decimal128.smt2`; the differential fixtures build them from `l` and
    `l2`, so both words carry bits and both signs. The census's `isnull` and `isnotnull` count
    `dec` fused, and `dev/varka_new_lane.py` drops the census entry it held owed to this row.

**Tests.** `VarkaDecimalPairSuite` (`ownJvm`, at 1, 2, 4 and 8 long lanes): the word edges of
section 3 item 2 through the real compiler, every ordered pair of up to seventeen edge values and the
null patterns, at 19, 20 and 38 digits, a 64-bit and a 19-digit side widened into a 38-digit pair
both ways round, wide and narrow-scale literals, the null tests, and the two declines at two
scales, each answer held to Spark's own expression three-valued. Lowering `ULT` as `LT`, run by
hand, fails three of its five tests on the first sign-bit row. The baseline benchmark's fusion
classes follow: the precision-38 comparison and, at 38 and 20 digits, `<=>`, `IS NULL` and the
projected `x < y` are `WHOLE`; `-x < y` and `abs(x) < y` are `NULL_TESTS`, the null tests Spark
infers fused below the row path's condition; `IN`, under which Spark infers none, stays `NONE`.
The catalyst Varka suites at the defaults (`dev/varka_matrix.sh --module catalyst`) pass, 1290
tests; the sql ones, the golden suite's Varka arm and the literal-lift suite among them, gave
2968 passed and 4 failed, all four the baseline's classes, whose suite then passes alone, 9 of
9, as do the coverage differential and harness suites, 2216 tests, after the last row change.

**Predictions** (11.7), scored here where this part can:

1. *Op counts*: held. `dev/varka_emit.sh --table` (which now spells a decimal column `dec38s2`)
   gives the `LongVector` calls of a lane group over two `DECIMAL(38, 2)` columns: `a < b` 23,
   `a = b` 22, `a <> b` 22, `a <=> b` 22, of which the two columns' loads and checks are 20, so the
   operators add 3, 2, 2 and 2 compares, the mask calls of 2.5 (2, 1, 2, 1) on top; `a IS NULL`
   is 10, the one column's load and check, as predicted.
2. *No existing shape's bytes move*: held for every shape; `emitted_bytes.json` gains the new
   rows. The `option_arms` digests moved, contrary to the prediction: each is one digest over
   every coverage shape, so a new row moves it whatever the options do.
6. *The census moves by 2*: held, `isnull` and `isnotnull`.
7. *The width audit's 128-bit column*: held, one refusal per row of the family, all of them
   filters, under 503's rule as merged; its reason's lane list now says `DECIMAL`, not
   `DECIMAL up to 18 digits`. The projected comparison has no row of its own here: every
   predicate row also compiles as a boolean output, which the coverage suite checks.

3 to 5 wait for the measurement.

**The review's fixes (8 November 2026).**

- *Null tests read a wide value's validity alone*: a probe that needs no words passes a column,
  a constant, a cast that keeps every digit, a choice and a pick, so `IF(p, d38, d38b) IS NULL`,
  `coalesce(d38, d19) IS NULL` and `greatest(d38, d38b) IS NOT NULL` fuse. A sum, a product and
  a lowering cast still decline (each can null or raise on valid leaves), and so do `-x` and
  `abs(x)`: Spark negates a wide value through Scala's `BigDecimal`, which rounds it to 34
  digits (`Decimal.unary_-`), and near the bound raises `NUMERIC_VALUE_OUT_OF_RANGE`, as
  `VarkaDecimalPairSuite` showed; a 37-digit value's negation comes back rounded to 34 digits,
  on upstream master's `Decimal` too. **Owed to 359b:** 2.5's exact `-x` and `abs(x)` differ from
  Spark on every value of 35 or more significant digits; 359b matches the rounding or declines.
  The census note says which forms over a wide value stay owed.
- *Verification*: `decimal128.smt2` models values, not validity, so the family's `<=>` and null
  tests are marked differential.
- *Reasons*: `CAST(d38s10 AS DECIMAL(18, 2))` names rows 350, 359 and 363, as the same cast to
  38 digits does; the time compiler's reason for a wide column as a value names rows 359 and 360
  to 363, not the 64-bit ones.
- *Not changed*: `Pair`'s lane check cannot fire from `pair`, whose narrow words come from
  `Scaled`, which refuses a word off the long lane itself.
- *Deferred under the cap*, to the next part: a narrower side whose rescale up still fits 18
  digits (`p + k <= 18`, `d9s4 < d3810`) rescaled by one long multiply and sign-extended, where
  it now declines naming row 360; and four refactors (one reader of a comparison's sides for both
  arms, one constant fold, a word-leaf builder, `isWide` at the older width checks).

**Not in this part**, owed: the conformance suite (11.5 item 5), the planted faults for row 504 as
committed test flags, the op-count test, the measurement on the EPYC and Arm runners (11.8 point
5), the pair-load node, and 359b.

### 10.5 359a, part 4: the conformance suite over both representations (9 November 2026)

*Branch `varka-359a-part4`, from master at `ba2093c636e`, which holds parts 1 to 3. Local commits
only.* 11.5 item 5's oracle half and 11.8 point 6, ported from the first attempt's branch
(`varka-359a-pair`, `08cec925bf9` and `58f155d7861`) onto the harness as master has it. The
remainder of 359a is three pieces of very different size, so it is cut in three: this part, the
pair-load node (10.3's owed node, a row in every exhaustive switch over the IR, its own pull
request, proven by `emitted_bytes.json` byte-identical), and the measurement, which is a runner
dispatch and needs no code (below).

**Built.**

1. **The census's fused-in-part marker.** The suite hit a census gap first: at one scale the
   comparisons and `IN` fuse for `dec`, while the census counts `dec` fused for them only once rows
   360 and 363 bring the rescale across two scales, and the harness calls a form that fuses
   against the register drift. The lead's answer of 2 November 2026 to the first attempt (a census
   marker, not a harness allowance) stands: a type marked `^` in `census_curated.txt` is *fused in
   part*. It goes to `census.tsv`'s new last column, `fused_in_part`, never to `fused_today`, so
   the counts and the need rule read it as unfused (the first twelve columns are byte-identical),
   and a coverage row must still show it. The seven comparisons (`<`, `<=`, `>`, `>=`, `=`, `==`,
   `<=>`), `in` and `between` carry `dec^`; `in` is new against the first attempt, since the long
   representation's `IN` fuses at every scale through row 497's arm.
2. **The harness's sixteen-byte path.** `VarkaKernelChecks.runLoadedDecimal` runs a kernel whose
   columns are decimal slots (`DecimalSlotLane`): a drawn value is an index into the suite's table
   of unscaled values, each slot is written as the value's two words, a null slot holds `2^127 - 1`
   or `-2^127`, and the reference reads each word leaf as a column of a row twice as wide (a 64-bit
   `DecimalColumn` reads the low word, its value). `VarkaSparkOracle.answerInternal` takes
   Catalyst's values, so the harness hands Spark the `Decimal` an index names; `answer` is that
   over the lane values, unchanged for every other suite.
3. **Both representations**, which the first attempt left as an open point:
   `VarkaDecimalConformanceSuite` at the registry's `DECIMAL(30, 4)` (the pair) over eleven values,
   the bounds and their neighbours, `2^63`, `-2^63 - 1`, `-2^64`, `2^64 + 5`, and zero and one each
   way; and `VarkaDecimalLongConformanceSuite` at `DECIMAL(10, 2)` (the long) over seven, the bounds
   and their neighbours and zero and one each way. One fixture form serves both
   (`VarkaTypeConformance.decimal`). The registry's entry is `Suite("Decimal")`; the
   census's check of a landed type's suite reads that name, and the second class is the same
   contract at the other band.

**What it answers.** The pair: null handling passes whole (`x < y` as the pair form, `=` and
`<=>` as conditions and values, `IS NULL`); load runs `x < y`, `x = 2^64 + 5` (a literal pair)
and `IS NOT NULL` and is cancelled on `IN` alone; ordering runs the six comparisons as conditions
and values and is cancelled on `greatest` and `least` alone. The long: null handling and load pass
whole, `IN` included; ordering is cancelled on `greatest` and `least` alone. For both, choice and
round trip are cancelled on the choices as outputs (359b above 18 digits, the decimal store's
admission naming row 348 below), the cast row on the casts (350, 364 to 366) and overflow on the
arithmetic (349, 360 to 363), each message naming every owed form. With `LT` planted for the
pair's low-word `ULT` (by hand, reverted), load, null handling and ordering fail on the first
row whose low words straddle `2^63`: the reference evaluates the faulty lowering and disagrees
with Spark.

**Cost**, against 11.7's "of the size of 347's, seconds": the pair suite took 5.3 s of test time in
its own JVM and the long one 4.0 s, the fork's start aside. Holds.

**The measurement (11.8 point 5), prepared, not dispatched.** The Arm half is done (10.1's
correction). The EPYC half needs no code: part 3 set the baseline class's fusion classes, so
the run is `VarkaDecimalBaselineBenchmark` as it stands on any head holding part 3, at 256 and
128 bits, its fourteen 359a sections spliced into the class's two runner files
(`dev/varka_bench_sections.py splice`), as #1245 did:

    gh workflow run benchmark.yml -R MaxGekk/spark --ref varka-359a-part4 \
      -f class=VarkaDecimalBaselineBenchmark -f jdk=25 -f generate-tpcds=false \
      -f expected-cpu="AMD EPYC 7763"
    gh workflow run benchmark.yml -R MaxGekk/spark --ref varka-359a-part4 \
      -f class=VarkaDecimalBaselineBenchmark -f jdk=25 -f generate-tpcds=false \
      -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-XX:MaxVectorSize=16

The sections, `|`-separated for `--sections`: `DECIMAL(38, 10) comparison`, and at `(38, 10)` and
`(20, 2)` the `<=> filter`, `IS NULL filter` and `x < y as a value`, each at `no nulls` and `one
row in ten null`. 11.7's predictions 3 to 5 are scored from them when they land.

**The review's fixes (9 November 2026).** One `/code-review high` pass and one fix round:

- *A fused-in-part form that regresses to a decline cancelled instead of failing.* A suite now
  names the operators its fixture may see declined (`declinesInPart`, the pair's `in` alone);
  any other fused-in-part form that declines is drift and fails.
- *Spark was handed every decimal `BigDecimal`-backed.* Within 18 digits the harness builds the
  `Decimal` from its unscaled long, as Spark's column readers do, so a later cast check reads
  Spark's own rule for that representation.
- *The null slots alternated by row parity*, so a column's nulls could all sit on one side of
  the bound; they now alternate by null ordinal, as every other lane's poison does.
- *Latent paths fail loudly*: the slot lane refuses a decimal store, a narrowing root and a
  kernel of both lanes, which it does not lay out yet, and the oracle refuses a decimal output
  above 18 digits rather than reporting its `longValueExact` failure as Spark raising.
- *The fixture factory* is `VarkaTypeConformance.decimal`, beside the integral one; `between`,
  rewritten to two comparisons, carries `dec^` too. `equal_null` does not: no coverage row holds
  an `EqualNull` over a decimal, which the census requires of the marker.
- *Not changed*: the reference reads the high words through a rewrite of the word leaves to
  columns of a doubled row, not a reference overload; the per-row `BigInteger` work, which the
  suites' seconds do not need saved.

**Not in this part**, owed: the pair-load node (the next part), the measurement's run, the
planted faults for row 504 as committed test flags, the op-count test, and 359b.

## 11. 359a, the build plan (1 November 2026)

Written from master at `239995f4fe4` before the laptop's reading of the spike, so that 359a can
start the moment the reading lands, and refreshed on 7 November 2026 against master at
`0b682025e09` (the line numbers, the baseline figures of item 5 and the state of rows 503 and
538). Line numbers are that master's; Varka paths are under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` as in section 0.
359a is section 5's step 3, the pair's reading half; nothing below changes 359b's scope.

### 11.1 What exists now

1. **Row 347's leaf and reader are on master** (#1230), so section 0's branch line numbers moved:
   `emitDecimalLoad` is `varka/VarkaVectorWalk.java:613`, `Slots.decimalTmp` is
   `varka/Slots.java:333` (keyed by the leaf node, filled at `:988`), `Refusal.DECIMAL_SLOT` is
   `varka/Analysis.java:367`, `VarkaTypes.lane(DataType)` and `declineReason` are
   `varka/VarkaTypes.java:355` and `:406`, the decimal's entry `:515`, and the reader's declines
   naming this row are in `VarkaDecimalCompiler.java` (the wide-type reason at `:342`, the
   column's at `:856`). The 64-bit load
   is already the P form: two loads, the even and odd two-vector rearranges, the check folded
   under `emitGuardCollect`.
2. **The readers of the lane question**, by a grep of `VarkaTypes.lane(` and `storageBits` over
   every source set: six main files (`VarkaConditionCompiler` 3 sites, `VarkaNodeCompiler` 4,
   `VarkaTimeCompiler` 2, `VarkaDecimalCompiler` 2, `VarkaExpressionCompiler` 1, `VarkaTypes`
   itself 4) and six test files (`VarkaTypesSuite` 7, `VarkaArrowSource` 2, `VarkaLaneArrowSuite`
   2, `VarkaTypeConformance` 1, `VarkaSparkOracle` 1, `VarkaAnsiForm` 1). Section 2.3's list
   becomes this one.
3. **The spike's EPYC 7763 readings for 359a's two operators** (ns a row, P / A / S, from
   `sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-results.txt` and
   its `-128bit` companion, runs 41239460136 and 41264904788 on `3acae72d6df`):

   | width | rung | the load and its 10^38 check | `x < y` into a selection word |
   |---|---|---|---|
   | 256 | in L2 | 0.9 / 1.5 / 1.6 | 1.0 / 1.0 / 1.4 |
   | 256 | in L3 | 0.9 / 1.5 / 1.8 | 1.1 / 1.0 / 5.9 |
   | 256 | past L3 | 1.1 / 1.6 / 1.9 | 1.5 / 1.5 / 6.2 |
   | 128 | in L2 | 135.3 / 66.9 / 1.6 | 165.2 / 43.7 / 1.4 |
   | 128 | in L3 | 137.5 / 67.0 / 1.8 | 168.7 / 44.7 / 5.9 |
   | 128 | past L3 | 136.3 / 66.6 / 1.9 | 168.7 / 45.2 / 6.3 |

   So on this host P leads the load at every rung and ties A on the compare; A's lead on `-x`
   (0.6 against 1.0 in L2) and on the choice (1.0 against 1.6 in L2) is 359b's, and is where P
   pays its two zip rearranges at the store.
4. **The L2 rung flatters S's branches.** S's `x < y` reads 1.4 in L2 and 5.9 in L3, its `abs`
   0.9 and 4.3, its choice 1.6 and 5.5, while P and A are flat across the two rungs and S's
   branch-free `-x` is too (0.7 and 0.8). The L2 rung times 4096 rows again and again, which a
   branch predictor can learn and a query's batches do not repeat; the rule below reads the L3 and
   past-L3 rungs for that reason.
5. **The baseline's sections that 359a fuses** (row path, ns a row, at 256 and at 128 bits, from
   `sql/core/benchmarks`' two files
   `VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and
   `VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`, the "spark row path
   (varka off)" lines, as #1258 left them; the figures first quoted here were the files'
   earlier ones): at `(38, 10)` the comparison filter 365.5 and 373.9 (one row in ten null: 325.9
   and 331.8), `<=>` 358.5 and 353.6, `IS NULL` 6.7 and 5.5 (one row in ten null: 178.2 and
   158.8), `x < y` as a value 358.3 and 359.2; at `(20, 2)` `<=>` 370.9 and 373.8, `IS NULL` 6.2
   and 4.8, `x < y` as a value 411.3 and 401.6. `IN`, `-x < y`, `abs(x) < y` and the choice need
   359b's operators. The null test without nulls is the one section where the row path does not
   pay a `BigDecimal` a row, so it is where 359a's margin is smallest.
6. **What 359a leans on**: row 503's two-lane decline is merged (#1262, 2 November 2026; it has
   its row in `PLAN.md`, "Done in part"): `VarkaKernelPlan.twoLaneMasksFallBack` and the
   `emit.declineTwoLaneConditions` conf (`SQLConf.VARKA_DECLINE_TWO_LANE_CONDITIONS`) are on
   master. Row 538's host reading is merged too (#1255, 1 November 2026, without its default
   flip); 359a needs none of it beyond what 503 reads, since a species' lane count needs no
   module export. Still open: row 489's descriptor ("before 359", not started), and row 506's
   note that 359a emits `gt128` from the walk (the `PLAN.md` cell).

### 11.2 One design, a gate, and two exits

Sections 2.2 to 2.7 are the P design throughout: two word leaves sharing one load, one row per
lane, the borrow lanewise. 359a builds P. The three layouts are not three branches of this plan:

- **A** holds half a row per lane, which breaks the invariant 2.2 rests on ("one node, one lane",
  a row per lane in every vector the walk leaves on the stack). Building it would mean a leaf
  geometry of its own (two vectors per lane group that are not words of one row), a row's
  condition gathered by `Long.compress` into the long kernel's mask word wherever it meets another
  column, every lowering of 2.5 rewritten as Highway's cross-lane forms, and rows 360 to 363, 337b
  and 506, whose plans write a decimal's words as ordinary long-lane nodes, replanned. If the
  reading favours A, the owner is asked; A is not built inside 359a.
- **S** is the owner's answer (a) to question 3: the reference arm, emitted only as a row of its
  own where it beats both P and the row path. The spike shows that place, x86 at two long lanes
  (11.4), and 359a builds nothing of S.

### 11.3 The decision rule

Read once, when the laptop's reading is committed, over the committed files only, by minimums,
on 359a's two sections (the load with its check, and `x < y` into a selection word), at the in-L3
and past-L3 rungs (11.1 item 4):

1. **P is built** unless 2 or 3 holds.
2. **A replan**: A is faster than P by at least 1.3x (AGENTS.md's re-run threshold) on both
   sections at both rungs at one width of more than two long lanes (256 or 512 bits) on one host,
   the laptop's 512 bits included. The 128-bit columns are two-lane x86 columns, where P and A
   are both the Java fallback and 11.4 decides the case, so they are read under 11.4 and do not
   trigger this rule. The one 128-bit column that does bear on it is aarch64's, where the pair
   runs natively (11.4) and no reading exists yet: 359a's measurement step (11.8 point 5) runs
   the pair spike on the Arm runner, and rule 2 is applied to that column as to the 256- and
   512-bit ones. A 1.3x win by A there asks the owner for a replan of the aarch64 case alone; x86
   at 256 and 512 bits stays P. Then the owner is asked (11.2).
3. **S replan**: S is faster than P by at least 1.3x on either section at 256 or 512 bits on
   either host. Then the owner is asked whether S becomes an emission mode beyond two lanes.
4. A move under 1.3x is a tie and leaves 1. The laptop's 512-bit column is eight long lanes issued
   through its 256-bit datapath (`skills/vector-api-and-width.md`, "This machine's AVX-512 is 256
   bits wide"), and the verdict says so.

On the EPYC 7763's committed numbers 1 holds already: P is faster than A on the load at both rungs
(0.9 against 1.5, 1.1 against 1.6) and A ties on the compare, and S is slower than P on both
sections at both rungs. If the laptop triggers rule 2 or 3, the EPYC's reading travels with the
ask as the counter-evidence, not as a veto: a win on one machine is a machine-dependent winner,
and whether it pays for a replan is the owner's call. The verdict is written in 10.1, with the
laptop's files named and this rule applied; the cell of row 359 is updated in the same commit.

**The laptop reading and the verdict:** 10.1. Build P.

### 11.4 The width rule: what runs at two long lanes on x86

This is the one host-dependent choice, and it is made at planning, not at emit time from a
default: a JVM whose preferred long species has two lanes on x86 (`Lane.LONG.preferredLanes`
beside the instruction set, as row 503's `VarkaKernelPlan.twoLaneMasksFallBack` reads it) runs
every compare, blend and two-vector rearrange of the pair as the Java fallback (section 0, items
9 and 10; 11.1 item 3's 128-bit rows). The fact is read once on the driver into
`VarkaSessionEmit`, as 503's `declineTwoLaneConditions` default and 538's resolved level are,
carried with the plan so that the compile at planning and the evaluator's compile agree, and
pinned off in the test sessions so the committed oracles stay host-blind. `VarkaEmitOptions` does
not learn the host (538's 2.2: a record that varied by host under one rendering would hand one
class name two bodies). Consequences for 359a:

- **Every 359a filter or selection root declines there**, by 503's rule unchanged: the
  comparisons, `<>`, `<=>` and the null tests are conditions of a long-driven kernel.
- **A comparison as a projected value still fuses** (2.6, where 503 leaves projections fused), as
  P's fallback, and is measured against the row path; if it loses it declines by the same
  mechanism with the measured reason.
- **aarch64 at 128 bits fuses** (the Neoverse N2's census cells, all intrinsic).
- **The case S wins is left to a row of its own** (11.8 point 3): at 128 bits S's `x < y` reads
  5.9 in L3 against P's 168.7 and the row path's 373.9 for the comparison.

### 11.5 What is layout-independent, built first

Each of these commits leaves `emitted_bytes.json` unchanged and survives an A or S replan:

1. **The registry** (2.3): `Storage` (`Plain`, `Bitmap`, `DecimalSlot`), `Read` (`Lanes(lane,
   words)`), `ByPrecision` bands (18 and one word naming 347; 38 and two words naming this row);
   `lane(DataType)` answers only for `Plain` storage, the decimal compiler asks `read(DataType)`;
   every reader of 11.1 item 2 with the answer it now gets, listed in the pull request;
   `types.tsv` gains `storage` and `words`, and `dev/varka_new_lane.py`, `VarkaLaneArrowSuite`,
   `VarkaTypeConformance`, `VarkaArrowSource` and `VarkaCoverageDifferentialSuite` follow.
2. **The four unsigned `CompareOp` members** (`ULT`, `ULE`, `UGT`, `UGE`): canonical form, the IR
   parser (row 502's `everyNodeTypeRoundTrips`), the reference evaluator by
   `Long.compareUnsigned`, the emitter's operator names, an IR fuzz grammar sequence of its own
   seed, a budget weight and a cost-model feature on row 352's awaiting-refit list, the warm-up's
   long block and `VarkaWarmupReachSuite`. No SQL expression maps to them yet, so no shape's bytes
   move.
3. **The proof file's ring layer**, `sql/varka/proofs/decimal128.smt2` from `VarkaProofFiles`
   over the code's constants: with `v = hi * 2^64 + lo`, the word lowerings of `<`, `<=`, `=` and
   `<=>` equal the integer comparisons, and for each `p` from 19 to 38 the check's two compares
   against the constant pair hold exactly when `|v| <= 10^p - 1`, with a bound one off refuted at
   each `p`. Value semantics, so layout-free.
4. **The compiler's front half**: the compile-time `Pair(lo, hi, type)`, the literal pair (two
   long-table slots, the unscaled value's words), the sign extension `Pair(x, SHR(x, 63))` of a
   64-bit operand at one scale, the declines above 18 renamed to the forms still owed (unequal
   scales naming 360 and 363; a decimal in an int-driven kernel; a negative scale), behind no
   switch yet since nothing emits a pair; the census's notes on the 22 pairs of section 6
   corrected to name 360 and 363.
5. **The fixtures and oracles**: the slot table's and the word edges' data (section 3 items 1 and
   2) with `ArrowColumnVector.getDecimal` and `BigDecimal` as oracles, and
   `VarkaTypeConformance` taught sixteen-byte slots and a `Decimal` oracle (question 4).
6. **The width rule's predicate** (11.4), which is 503's and is on master: nothing to build.

### 11.6 What depends on the layout (P, built after the verdict)

7. **The word leaf** `DecimalWord(ordinal, Word, precision)`, rendering `decw:`, and the shared
   load: `Slots.decimalTmp` keyed by the ordinal for this leaf, the first word emitted loading
   `a` and `b`, taking both rearranges, arming the check and parking its sibling; a body split by
   the budget loads again per group (347's `perGroupBodiesUnderTheBudget`). This interface - one
   load per column per group, two word nodes - is what 360, 361 and 337b consume.
8. **The check** (2.4): `gt128` twice and an `or` under `emitGuardCollect`, `Refusal.DECIMAL_SLOT`
   unchanged, emitted under a null test as well; emitted from the walk, as row 506's note says.
9. **The lowerings of 359a's forms** (2.5's first eight lines): `=`, `<`, `<=`, `>`, `>=`, `<>`,
   `<=>`, `IS [NOT] NULL`, a literal operand, `d18 < d38` at one scale; the comparison as a value
   through row 27's boolean output.
10. **The proof's carry layer** for those forms: the words as emitted, wrapping stated by
    `mod 2^64` and the unsigned compare as the compare of the words read `mod 2^64`. Negation,
    `abs` and their borrow are 359b's sections of the same file.
11. **Coverage rows** of a "DECIMAL lane pair" family over section 3 item 4's fixtures, for 359a's
    forms only, each naming its verification for row 490; the generated files in
    `ADDING_A_TYPE.md`'s order.

### 11.7 Tests, proofs, predictions, costs

**Tests.** The slot table (section 3 item 1, `ownJvm`, at 2, 4 and 8 long lanes through
`lanesOverride`, precisions 19, 20, 30 and 38, lengths 1, 3, 37 and 64, both bodies); the word
edges for 359a's operators (section 3 item 2: a low word with its sign bit set under a zero high
word, equal high words with low words either side of `2^63`, `hi = -1`, every null pattern); the
unsigned compares in the fuzz suite and the reference; the parser round trip; an op-count test
(`PinsDefaults`); `VarkaTypesSuite` flipped (the decline now for a negative scale only, `read`
per band); the two-lane decline of a pair condition beside 503's suite, with a projected
comparison fusing; `VarkaDecimalConformanceSuite` over both representations (11.8 point 6); the
coverage differential, the golden suite's Varka arm, and the option matrix dispatched on the
branch. **Planted faults** for row 504, each under a test flag: `ULT` lowered as the signed `LT`
(caught by the word edges' sign-bit row and refuted by the ring layer), the check's bound at
`10^p` instead of `10^p - 1` (the slot table's `10^p` row and the proof's one-off refutation),
the sibling word parked from the wrong rearrange (the word edges, every row).

**Predictions, registered before the build**, scored in section 10:

1. **Op counts** on `dev/varka_emit.sh`'s scale, per lane group, the load counted once a column:
   the check 11 lane calls against 347's 6, `x < y` 5, `x = y` 3, `x <> y` 4, `x <=> y` 3, a null
   test 1 (2.5's table).
2. **No existing shape's bytes move**: `emitted_bytes.json` gains rows and changes none, and the
   `option_arms` digests are unchanged, since 359a adds no option.
3. **The comparison filter at `(38, 10)` at 256 bits on the EPYC 7763** is 10 to 50 times faster
   than the row path's 365.5 ns a row (section 4's prediction 4, kept); `<=>` and `x < y` as a
   value in the same band.
4. **`IS NULL` without nulls** is faster than the row path by 1.5 to 4 times only, against 6.7 at
   `(38, 10)` and 6.2 at `(20, 2)`: the kernel still loads and checks sixteen bytes a row where
   the row path reads the validity alone.
5. **At 128 bits on the EPYC 7763** the filter sections decline and tie the row path within the
   family's band; `x < y` as a value fuses and is 2 to 5 times faster than the row path's 359.2
   (section 4's prediction 5; the band is kept, its anchor requoted from the files as they stand).
6. **The census** moves by 2 fused pairs, `isnull` and `isnotnull`; `negative` and `abs` wait
   for 359b.
7. **`width_audit.json`'s 128-bit column** records one refusal per 359a coverage row that is a
   filter, and none for the projected comparison. This holds only if the audit simulates the
   two-lane host as 503's merged rule does; a check of the audit's simulation is a step of the
   build.

**Costs.** The proof file's ring and carry layers for 359a are about 30 checks (four compares and
twenty bounds, each with its refutation, and the carry forms), priced at VARKA-245's observed
ratio of about 0.8 from its spiked form: under 5 s per solver, the spiked form's time stated in
the pull request (risk 5). The slot table is 96 kernels (four precisions, three lane counts, four
lengths, two bodies), about 7 ms a cold emission by VARKA-280's measurement plus the fork's start:
under 10 s; the calibration table's sessionless JUnit class (VARKA-328) ran far under its estimate,
so the measured time is reported and joins the table. The word edges and the conformance suite
are of the size of 347's, seconds. The build's CI steps are read against
`sql/varka/ci/step-budget.tsv` in the pull request.

### 11.8 Open points for the lead

1. **The margin for A.** Section 2.1 says "the owner's margin" and no record names one.
   *Recommended:* 1.3x by minimums at the in-L3 and past-L3 rungs, as 11.3 writes it, and a win
   by that margin asks the owner for a replan rather than building A.
2. **Row 503 is merged (#1262).** 359a's width rule is 503's predicate and conf, both on master,
   so there is no twin to carry and no row to add; this point is settled and kept only so that
   prediction 7 can name what it rests on.
3. **S at two long lanes on x86.** At 128 bits S reads 1.8 to 6.3 ns a row on 359a's sections
   against P's fallback at 135.3 to 168.7 and the row path's 373.9 for the comparison, so it is the
   only form that fuses well there. Under the owner's answer (a) to question 3 it is a row of its
   own. *Recommended:* open that row now (a scalar subtree in the kernel's loop feeding the
   selection word), owned outside 359a, placed in this milestone only if its done-when claims x86
   hosts at 128 bits, otherwise as a scope item beside `m9/SCOPE.md` item 101.
4. **Row 489** is ordered "before 359" and not started. *Recommended:* do not wait. 359a adds one
   leaf and four compare members; 489 moves them with every other node when it lands.
5. **Measuring 359a's sections on its own head.** Section 5 puts the measurement in 359b, but
   four of the baseline's sections are 359a's forms. *Recommended:* dispatch `benchmark.yml` on
   359a's branch at 256 and 128 bits on the EPYC 7763 (runners, not the laptop) and score 11.7's
   predictions 3 to 5 in 359a; 359b measures its own. Also dispatch
   `VarkaDecimalPairSpikeBenchmark` on the Arm runner (`runner: ubuntu-24.04-arm`, row 575), whose
   128-bit column is the one reading rule 2 still lacks (11.3).
6. **The conformance suite's checks while 348 is open.** *Recommended:* the decimal suite runs the
   load, the null and the ordering checks over both representations (the reader already compares
   below 19 digits), and leaves the choice and cast checks owed to 359b and 364.
7. **One proof file.** *Recommended:* `decimal128.smt2` is one file; 359b appends the borrow,
   negation and `abs` sections.

### 11.9 Order, files and size

Commits 1 to 6 of 11.5 in that order, then 7 to 11 of 11.6 after 11.3's verdict, each green
alone, with the switch-free front half first because nothing emits a pair until 7.

| file | what (layout) |
|---|---|
| `varka/VarkaTypes.java`, its readers (11.1 item 2) | `Storage`, `Read`, `ByPrecision` (none) |
| `varka/VarkaVectorIR.java`, parser, reference | unsigned compares (none); `DecimalWord` (P) |
| `VarkaDecimalCompiler`, `VarkaConditionCompiler` | pair, literal, sign extension (none) |
| the same, `VarkaTimeCompiler` | 359a's lowerings (P) |
| `varka/Analysis.java`, `Slots.java`, `VarkaVectorWalk.java` | the shared load, the check (P) |
| `varka/VarkaEmitBudget.java`, cost register, warm-up | compares (none); the leaf (P) |
| `proofs/decimal128.smt2`, `VarkaProofFiles.java` | ring layer (none); carry layer (P) |
| tests | slot table, word edges, fuzz, conformance, coverage rows, planted faults |
| generated | `types.tsv`, `coverage.json`, `docs/sql-varka.md`, `width_audit.json` |
| generated | `emitted_bytes.json`, the census |

**Size**: about four days, as section 5 said, of which the layout-independent half is about one
and a half and can start before the reading; one pull request, or two if the lead wants the
registry change (11.5 item 1) reviewed alone, since it touches every reader of the table.
