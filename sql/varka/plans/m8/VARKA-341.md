# VARKA-341: The floating lanes

## 0. What exists now (admission check)

From the code at `0ee770cc633` (26 October 2026; paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named). The
row's text is the milestone plan's 2.7 and items 35 and 89 of `m9/SCOPE.md`; what the code says
about each premise is below, with the file and line it rests on.

1. **The registry owes both types.** `varka/VarkaTypes.java` gives `FLOAT` and `DOUBLE` the
   admission `Owed("341")`, storage 32 and 64 bits and no lanes, so a column of either declines
   "a double column: the DOUBLE lane is row 341's". `VarkaLaneArrow.of` (`sql/core/...`) maps both
   to null. The census admits `float` in 66 entries and `double` in 102, none fused.
2. **`LaneType` is a width, and its order is the widening order.** `varka/VarkaVectorIR.java:82`
   declares `BYTE, SHORT, INT, LONG`; `isWiderThan` is `compareTo` on that order (`:95`), and
   `WidenLane` and `NarrowLane` refuse a pair it does not order (`:531`, `:573`). A floating member
   placed anywhere in that order would make `WidenLane(int -> double)` legal and wrong: a floating
   value is not a wider integer, and the conversions are row 343's casts.
3. **`Lane` is integral throughout.** `varka/Lane.java:55-78` derives each member's descriptors from
   an integral scalar. Sixteen sites outside `Lane.java` read the integral-only ones - the shift
   and bitwise shapes (`lanewiseBinaryI`, `lanewiseBinaryV`), the popcount (`lanewiseUnary`) and
   `narrowScalar` - and 35 `isNarrow()` branches in ten files read the narrow axis. The `run`
   descriptor (`:161-171`) has one branch: an `int[]` scalar table for every lane but the long one,
   which adds a `long[]`.
4. **The kernel's drive is a `LaneType`, chosen by the lanes a tree holds.**
   `varka/VarkaKernelPlan.java:163-180`: the long lane wherever a tree holds a long node, else the
   int lane, else a narrow one; `lanesShare` (`:198`) puts roots of different lanes on the int lane
   unless one is long, and a root no lane serves takes a kernel of its own (VARKA-480).
5. **A mask crosses lanes through a word.** `varka/VarkaVectorWalk.java:1193`
   (`emitToDrivingLane`) moves an int mask to the long species as `fromLong(toLong(mask))`, and
   its comment records why that is safe there and nowhere else: JDK 25's C2 miscompiles the pair
   on x86 without AVX-512 mask registers when both masks have the same lane count (VARKA-396),
   and an int mask has twice the long species' lanes. A `DoubleVector` mask and a `LongVector` mask
   at one width have the same count, so that conversion is the miscompiled case; the same-count
   path is `VectorMask.cast`, or the `VarkaMaskGuard` form of `emitMaskOfWord` (`:1214`).
6. **The tests read 64 bits as a long and 32 as an int.** `VarkaSparkOracle.scala:74`
   (`case ((Some(v), _), 64) => v`), `VarkaCoverageDifferentialSuite.scala:1136` and
   `VarkaVectorProjectionSuite.scala:67,93` (`case LongType | ...`), and `VarkaReference`'s value
   model, `longValue` and `intValue` over `long[]` rows (`VarkaReference.java:397`). A `DOUBLE`
   column would reach Spark's `eval` as a `Long`. Row 475's 9.2.1 names the oracle's conversion as
   a registry fact; this row adds it.
7. **Spark's ordering is IEEE's compare with NaN placed.** `SQLOrderingUtil.compareDoubles`
   (`sql/catalyst/.../util/SQLOrderingUtil.scala:28`) is `if (x == y) 0 else
   Double.compare(x, y)`: NaN equals NaN and is greater than everything, and `-0.0` equals `0.0`,
   as IEEE's `==` has it and `Double.compare` and the vector `max` do not.
   `Greatest` and `Least` fold with `ordering.gt` and `ordering.lt` (`arithmetic.scala:1462`,
   `:1367`), so the first of two equal operands stays. `In` compares with `ordering.equiv`
   (`predicates.scala:528`). `InSet` looks the value up in a Scala set, whose numeric equality
   and `##` both take `-0.0` as `0.0`, and answers NaN from its own `hasNaN` (`:656-690`): both
   lowerings give `-0.0 IN (0.0)` true and `NaN IN (NaN)` true.
8. **`isnan` is total, and `nanvl` keeps a null.** `IsNaN` is non-nullable and `false` on a null
   (`nullExpressions.scala:394`); `NaNvl` is null where its left is, its right where its left is
   NaN, and its left otherwise (`:449`).
9. **No Varka node sees `NormalizeNaNAndZero`.** `NormalizeFloatingNumbers` wraps the keys of an
   equi-join and of a window's partition (`optimizer/NormalizeFloatingNumbers.scala:60-100`), and
   grouping keys at physical planning; a join, an exchange, a window and an aggregate evaluate
   those keys, never a `Project` or `Filter`. The canonicalisation node of 2.7 has no end-to-end
   reader until `hash` (row 337) puts it in a projection.
10. **The bytes oracle's fuzz shapes are sequences with seeds of their own.**
    `VarkaEmittedBytesSuite.scala:272-298` draws the int, the long and the mixed sequences from
    `FUZZ_SEED`, `LONG_FUZZ_SEED` and `MIXED_FUZZ_SEED`, so a floating sequence with a seed of its
    own leaves every integral hash as it is.
11. **The census reading has the 128-bit cells.** `VARKA-376.md` 9: the float compares and `IS_NAN`
    are intrinsic at every width; the double ones are intrinsic at 512 and 256 bits, and at 128 bits
    on x86 they are the Vector API's Java fallback (two lanes, and C2 lowers no two-lane mask,
    `m7` row 374), on the laptop and the AVX2 runner alike. `VarkaWidthAuditSuite` records a forced
    128-bit census's refusals, so those shapes go into `width_audit.json` as recorded refusals, not
    as a failure.
12. **The baseline comes first.** Row 377 owns one benchmark class per new type family, each
    "committed before the family's first lane row", measured on the unchanged code in its own pull
    request. *26 October 2026:* the floating class is committed with its EPYC 7763 results (#1035).

### What differs from the row text and the milestone plan

- **The canonicalisation node moves to row 337** (item 0.9). 341 builds no node with no reader.
  `KnownFloatingPointNormalized`, the relabel the census lists beside it, is 337's with it.
- **The mixed-kind scope is stated** (2.4): `DOUBLE` beside the 64-bit integral lane and `FLOAT`
  beside the 32-bit one, the conversions between the kinds left to 343.
- **The oracles' conversion is a registry fact** (item 0.6), added here as row 475's 9.2.1 asks.

## 1. Goal and non-goal

A `DOUBLE` or `FLOAT` column is read, compared, tested for null and for NaN, chosen between and
written by a kernel, with the row engine's answers to the bit: NaN equal to NaN and greater than
every value, `-0.0` equal to `0.0`, the first of two equal operands kept by `greatest` and `least`.
Not this row: arithmetic and the exact functions (342), the casts (343), rounding (344), the math
family (345 to 358), the canonicalisation node and `hash` (337).

## 2. Design

### 2.1 The lane split

`LaneType` gains `FLOAT` and `DOUBLE` and a kind: integral (`BYTE`, `SHORT`, `INT`, `LONG`) or
floating. `isWiderThan` orders integral lanes only and refuses a floating one, so `WidenLane` and
`NarrowLane` refuse a floating operand at construction. Each lane also has a width, 32 or 64 bits,
which is what the kernel plan pairs (2.4). `isNarrow` is unchanged.

`Lane` gains `FLOAT` and `DOUBLE`, built from `FloatVector` and `DoubleVector` and the scalars
`float` and `double`. Its integral-only descriptors move into a field `integral` of a record
`Lane.Integral` (the shift and bitwise shapes, the popcount, `narrowScalar`), null on a floating
member; the sixteen sites read `lane.integral()`, which throws on a floating lane, and no
floating node reaches them. This is the split of `VARKA-475.md` 4 rule 2: a fact of the lane, not
a branch. The 35 narrow branches stay; none is reached by a floating node.

### 2.2 The type registry and Arrow

The `FLOAT` and `DOUBLE` entries become `OnLane(FLOAT)` and `OnLane(DOUBLE)`, with conformance
suites `Float` and `Double`, and `types.tsv` is regenerated. `VarkaLaneArrow.of` maps them to
`Float4Vector` and `Float8Vector`, built from Spark's own field. Each registry entry gains the
oracle's conversion, the one per-type fact the tests lack: the Java value a lane's bits are to
Spark (`Integer`, `Long`, `Float.intBitsToFloat`, `Double.longBitsToDouble`) and back. The oracle,
the reference evaluator's row readers and the differential suites' readers take it instead of
their width switches (item 0.6).

### 2.3 Literals

A double literal is its bits in the existing `long[]` table and a float literal its bits in the
`int[]` one; the kernel broadcasts `Double.longBitsToDouble(bits)` and `Float.intBitsToFloat(bits)`,
read once in the prologue. No new `run` descriptor, so no existing kernel's bytes move. The
descriptor's branch keys on `scalar.equals(CD_long)` today (`Lane.java:161`), which would hand a
`DOUBLE` lane the int table; it keys on the lane's 64-bit width instead, and the slot load stays a
`laload` followed by the bit conversion. The alternative, a `double[]` table, is a third
descriptor and a branch in every driver; rejected.

### 2.4 Kernels and masks

The drive stays a `LaneType`. A kernel whose every value is `DOUBLE` drives at `DOUBLE`, one of
`FLOAT` at `FLOAT`. A kernel of `DOUBLE` and 64-bit integral values (`BIGINT`, `TIME`,
`TIMESTAMP_NTZ`, the day-time interval) drives at `LONG`: the lane counts agree, and a double
condition reaches the long species by `VectorMask.cast`, never the word round trip of item 0.5.
`FLOAT` beside 32-bit integral values drives at `INT` the same way. A root of the other width -
`DOUBLE` beside `INT`, `FLOAT` beside `LONG`, `FLOAT` beside `DOUBLE` - takes a kernel of its own in
a projection and declines in a filter's conjunct, with the reason naming row 343. A
filter such as `x > 1.0 AND d > d2` therefore keeps its date conjunct in the kernel and the double
one as a row filter until 343 lands.

`VarkaKernelPlan.emissionLane` and `lanesShare` take these as rules of the plan, not of the
emitter, as row 488 placed every lane decision there.

### 2.5 Semantics

No new comparison node: `Compare` at a floating lane is lowered by the lane's ordering, a fact of
`Lane` (2.1).

- **Comparisons** take item 89 bullet 1's formulas: `=` is `(l != l & r != r) | l == r`, `<` is
  `!(l != l | l >= r)`, `<=` is `r != r | l <= r`, `>` and `>=` swap the operands, `<>` negates `=`.
  Against a literal that is not NaN, `=`, `<` and `<=` are the bare IEEE compares and `>`, `>=` and
  `<>` one negation each; against a NaN literal, the `isNaN` forms. `NOT` is plain mask negation.
- **`<=>`** is `NullSafeEqual` with the lane's `=`.
- **`greatest` and `least`** are `IfElse(b > a, b, a)` and `IfElse(b < a, b, a)` with the lane's
  ordering, the first of two equal operands kept; never `DoubleVector.max`, whose IEEE answer
  differs on NaN and on the zeros.
- **`IN` and `InSet`** are the EQ chain with the lane's `=`, by row 497's one arm where it has
  landed and by a floating literal reader on the present arms where it has not.
- **`IS [NOT] NULL`, `IF`, `CASE`, `coalesce`, `nvl`, `ifnull`** are the lane-generic choice and
  validity nodes; a pick moves bits, so a NaN's payload and a zero's sign pass through.
- **`isnan`** is a new condition, `IsNaN(x)`, total like `IsNotNull`: known true where `x` is valid
  and NaN, known false everywhere else, a null included (item 0.8). It is a new kind of test, not a
  new type of an old one, so rule 1 of `VARKA-475.md` 4 admits it.
- **`nanvl(a, b)`** is `IfElse(IsNaN(a), b, a)`: a null `a` makes the condition false, and the
  answer is `a`, null.

### 2.6 Oracles, reference and the NaN rule

`VarkaReference` evaluates the floating nodes over bits, with `compareDoubles` and `compareFloats`
as the ordering. The NaN rule of `SCOPE_STANDARD_MODE.md` 3.5 holds: every form of this row passes
bits through, so the bytes compare NaN lanes bit for bit; the computed forms, where NaN-ness is
compared instead, begin with 342.

### 2.7 Warm-up, cost, census

The warm-up gains a `DoubleVector` and a `FloatVector` block over what the kernels of 2.5 call,
held by `VarkaWarmupReachSuite`. `IsNaN` gets a cost feature. The census marks `float` and
`double` fused for the operators of 2.5 once coverage rows support them.

## 3. Tests and oracles

1. **The table test of item 89 bullet 1**, every operator against `compareDoubles` and
   `compareFloats` over NaN (quiet, signalling, negative, payload bits), both zeros, both
   infinities, the extremes, the smallest subnormal and one ordinary pair, at both kinds, in the
   general and the literal-specialised forms; and the tie cells of 2.7: `greatest(-0.0, 0.0)` is
   `-0.0`, `greatest(0.0, -0.0)` is `0.0`, two NaN payloads return the first, `-0.0 IN (0.0, ...)`
   with 2 and with 11 literals.
2. **`VarkaDoubleConformanceSuite` and `VarkaFloatConformanceSuite`** over row 484's contract, on
   the long-lane harness of #1031, whose fixtures take bit patterns. Their cast rows cancel, every
   cast being 343's (row 499 (d) records why the cancel hides the forms that ran), and their
   overflow checks cancel, the arithmetic being 342's.
3. **Coverage rows** for each form of 2.5 at both kinds, with literals the differential fixtures
   hold (row 499 (b)) and column pairs drawn so that NaN and both zeros meet; the fixtures gain
   `x`, `x2` (`DOUBLE`) and `f`, `f2` (`FLOAT`) columns, derived where the fixtures allow it.
4. **The fuzzers**: a floating sequence in `VarkaIrGrammar` with a seed of its own (item 0.10),
   drawing payload, negative and signalling NaNs; floating columns in the Spark fuzzer's fixture.
5. **The flipped tests**: the registry's decline for `DOUBLE` and `FLOAT` (`VarkaTypesSuite`) and
   any test that pins a floating column declined.
6. **The generated files** in `ADDING_A_TYPE.md`'s order; only new rows and shapes move.
7. **CI**: the PR build and the option matrix dispatched on the branch.

## 4. Predictions and costs

1. No integral shape's bytes move; the `option_arms` digests move with the new shapes.
2. The double compares at 128 bits on x86 are recorded refusals in `width_audit.json` (item 0.11).
3. Every form of 2.5 is at least as fast as Spark's row path at 512 and 256 bits against row 377's
   floating baseline; the 128-bit double forms may be slower, being the Java fallback.

Cost: large, as the row says - the `Lane` split and the mask rule are the work, the rest is
entries. About five days after the baseline.

## 5. Order and files

1. **Row 377's floating class**, its own pull request on the unchanged code.
2. **The lane split** (2.1) with `VarkaLaneTypeSuite` extended, no type admitted: bytes unchanged.
3. **The registry entries, Arrow, literals and the kernel plan's rules** (2.2 to 2.4).
4. **The semantics** (2.5) with the table test, then the conformance suites and coverage rows.
5. **The fuzzers, the generated files, CI.**

Dependencies: #1031 (the long-lane conformance harness and its pair form) before the conformance
suites; row 497 if it lands first (one `IN` arm). The coverage rows' literals keep one integer digit
(`1.5`), since the quote check reads a number like `12.5` as a measurement; row 500 skips only a
clock time's seconds.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `LaneType`'s floating members and kind; `IsNaN` |
| `varka/Lane.java` | `FLOAT`, `DOUBLE`, `Lane.Integral` |
| `varka/VarkaKernelPlan.java` | the drive rules of 2.4 |
| `varka/VarkaVectorWalk.java`, the emitters | the floating `Compare`, `IsNaN`, the mask cast |
| `varka/VarkaTypes.java`, `VarkaLaneArrow.java` | the entries, the conversion, the vectors |
| `VarkaConditionCompiler.java`, `VarkaNodeCompiler.java` | the leaves, `isnan`, `nanvl`, `IN` |
| tests | the table test, two conformance suites, coverage rows, fuzz sequence, oracle conversion |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `types.tsv`, `census.tsv`, `casts_fused.tsv` |

### 5.1 Steps 3 and 4 as built (27 October 2026)

Where the code departs from 2.4 to 2.7, and what is still owed:

- **`isnan` is no new IR node.** It is `x = NaN` under the lane's `=`, made total as
  `(x = NaN) IS NOT NULL AND x = NaN`, and `nanvl(a, b)` is `IfElse` over it. The answer is 2.5's;
  the cost feature of 2.7 has no node to attach to, and the compare's weight
  (`VarkaEmitBudget.FLOATING_ORDER_WEIGHT`) covers it.
- **No literal-specialised compares yet.** Every floating compare, against a literal or not, takes
  the general formula of 2.5. The specialised forms, and their table rows, are owed by 9.
- **No mixed-kind kernel yet.** A `DOUBLE` root beside a `LONG` one takes a kernel of its own in a
  projection, and a floating conjunct beside one that an integral lane reads stays a row filter.
  The mask crossing of 2.4 (`VectorMask.cast`) is the step after these two.
- **The harnesses read the literal table by the lane's width** (`bits() == Long.SIZE`), so a
  `DOUBLE` kernel runs through the long harness and a `FLOAT` one through the int harness, its
  rows and literals the values' raw bits: `VarkaCompiledCheck`, `VarkaReplay`,
  `VarkaVariantChecks`, the coverage differential, the composition fuzz, the bytes suite,
  `VarkaEmitDump`, the IR fuzz, the width audit probe, the time benchmark and
  `VarkaTypeConformance`. `VarkaReference` orders the floating `Compare`, `NullSafeEqual`,
  `Greatest` and `Least` by `SQLOrderingUtil.compareDoubles` and `compareFloats` over the bits,
  the first of two equal operands kept (2.6); `VarkaSparkOracle` hands Spark's `eval` the
  registry's conversion of 2.2 and compares its answer by raw bits.
- **Coverage rows**: 40, in four families (`DOUBLE` and `FLOAT` columns, as predicates and as
  values): the six comparisons, `<=>`, a literal on the right, `IN` in both its forms, `isnan`, the
  null tests, `greatest`, `least`, `nanvl`, `CASE`, `IF` and `coalesce`. Both `IN` lists hold
  `0.0` and NaN, written `CAST('NaN' AS DOUBLE)`, which `VarkaSqlResolve` folds as the optimizer
  does. The differential fixtures derive `x`, `x2`, `f`, `f2` from `rid` (`floatingColumns`): NaN
  against NaN and against negative infinity, a NaN with its sign bit set, both zeros both ways
  round, the two infinities, the extremes and the smallest subnormal; every ordered pair of them is
  `VarkaFloatingLaneSuite`'s. Other NaN payloads have no SQL spelling, so the composition fuzz draws them as bits
  and `VarkaFloatingLaneSuite` builds them. The ANSI form guards a floating row with `xrid` or
  `frid`, and the CASE-condition form gives a floating condition floating arms, since a mask
  does not cross the kinds yet. `floatingDecline` admits the six comparison classes by name, not
  `BinaryComparison`, so the table's check sees concrete classes and a comparison Spark adds later
  declines. Every integral shape's
  bytes are unchanged; the oracle gains the 40 shapes and the `option_arms` digests move with
  them. The width audit records the 128-bit double refusals, the census marks `float` and
  `double` fused for the 18 entries the rows show, `isnan` and `nanvl` done, and
  `dev/varka_new_lane.py` owes nothing at the types.
- **Conformance**: `VarkaDoubleConformanceSuite` and `VarkaFloatConformanceSuite` run the
  contract's load, round trip, ordering and choice over the special values' bits, two NaN
  payloads included; null handling, the cast row and overflow are cancelled naming the rows the
  registers give each form: the operator's census entry's whole rows column, 342 among them, and
  the cast pair's, 343 for most and 346, 350 and 364 for the cast to `dec`.
- **The end-to-end suite runs its filters with the cache's batch pruning off.** Spark's batch
  statistics leave NaN out, so a filter that matches a NaN row can prune that row's batch on both
  sides (SPARK-58482), and the kernel would never see the rows it is tested on. The Spark fuzz
  and its reproducer replay turn it off for the same reason, only for a case whose SQL reads a
  floating column: `NOT (x < 1.5D)` pruned its NaN pivot row on both sides. Every other case, and
  so every stored reproducer, runs with it on as recorded. The floating suite also caches its fixtures again before each test, a workaround for
  the expected session losing its cache (row 501, fixed by #1058); it comes out once #1058 merges.
- **The warm-up blocks** (2.7): `VarkaVectorTemplateWarmup`'s double block gains, and a float
  block makes, a floating column's calls - the dense and masked stores and loads, a literal's
  `broadcast`, the `IS_NAN` test, the `EQ`, `LE` and `GE` compares Spark's order is built from,
  the mask joins and the pick's `blend` - `THIN_CALLS_PER_ROUND` times a round.
  `VarkaWarmupReach` gives the float lane its own receivers; it had fallen back to the double
  lane's, so the float kernels' calls were named by their abstract owner and their mask calls
  credited to the double lane's mask.
- **Still owed by this row:** the fuzz grammar's floating sequence (3.4) and the benchmark
  (`dev/varka_new_lane.py` lists both), with the literal-specialised compares and the mask
  crossing above.
- **Not floating gaps:** a `BOOLEAN` output and a `CASE` with no `ELSE` fuse on no lane yet (the
  latter is row 333), so the suite shows a condition's three values through a `CASE` with an
  `ELSE`.

## 6. Out of this row

Arithmetic (342), casts (343), rounding (344), the math family (345 to 358), the canonicalisation
node, `hash` and `xxhash64` (337), `DOUBLE` and `FLOAT` in aggregates (outside milestone 8).

## 7. Declines that stay

Every floating arithmetic, cast, rounding and math form, each naming its row; a kernel mixing the
two widths across kinds (2.4), naming row 343.

## 8. Questions for the owner

Answered by the owner on 26 October 2026:

1. **The mixed-kind scope of 2.4:** `DOUBLE` with the 64-bit integral lane and `FLOAT` with the
   32-bit one in this row; the other mixes decline until 343 builds the conversions.
2. **The baseline first:** row 377's floating class lands as its own pull request before this
   row's first commit.

## 9. Done when

Row 341's text holds, with the canonicalisation node moved to 337: the table test of item 89
bullet 1 passes for every operator and its literal-specialised forms at both kinds, with NaN
payload bits and the tie cells; each form of 2.5 fuses with coverage rows and passes the
conformance suites; the bytes oracle is unchanged for every integral shape; the 128-bit double
refusals are recorded in `width_audit.json`; and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)
