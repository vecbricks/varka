# VARKA-347: The decimal load path

## 0. What exists now (admission check)

From the code at `71ef57af282` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named). The
row's text is the milestone plan's 2.8 ("The load path below 19 is a measurement") and items 1 and
89 bullet 6 of `m9/SCOPE.md`. What the code says about each premise is below, with the file and
line it rests on.

1. **Arrow holds every decimal in sixteen bytes, low word first.** `ArrowUtils.toArrowType`
   (`sql/api/.../util/ArrowUtils.scala:108`) maps every `DecimalType` to `Decimal(p, s, 128)`.
   Arrow Java writes the slot in native byte order: the low word in the first eight bytes and its
   sign extension in the next eight on a little-endian host (`ArrowCachedBatchSerializer.scala:444`,
   `compactDecimalUnscaledOffset`). The kernels load every column `LITTLE_ENDIAN` today, so a
   big-endian host is outside this row as it is outside every lane.
2. **Arrow Java does not check the bit width.** In arrow-vector 19.0.0 (the version `pom.xml`
   pins), `Types.java:950-954` maps every `Decimal` whose bit width is not 256 to `DecimalVector`,
   and `DecimalVector(Field, ...)` (`DecimalVector.java:81-82`) takes sixteen bytes a row whatever
   the field says. A `Decimal(p, s, 64)` field from another writer is read at the wrong stride.
   `ArrowUtils.fromArrowType` (`:144`) also ignores the width. So the row's `bitWidth == 128`
   check is needed, and it has to be Varka's own.
3. **The column path builds a `BigDecimal`-backed value and raises on an out-of-precision slot.**
   `ArrowColumnVector` (`sql/catalyst/.../vectorized/ArrowColumnVector.java:207-213`) picks
   `SmallDecimalAccessor` for a vector of precision 1 to 18. Its `getDecimal` (`:469-483`) checks
   the high word against the low word's sign, then calls `Decimal.apply(BigDecimal.valueOf(u,
   arrowScale), p, s)`, or `getObject` when the high word differs. `Decimal.set`
   (`sql/api/.../types/Decimal.scala:119-131`) raises `NUMERIC_VALUE_OUT_OF_RANGE` when the value
   has more digits than `p`. So a valid slot holding `10^p`, or any wrong high word (whose value is
   then at least `2^63`), raises on read in every mode. It also rescales from the Arrow field's
   scale to Spark's by `ROUND_HALF_UP`, so a field whose scale is not the Spark type's is read
   rounded. A null slot is never read (`isNullAt` first), so garbage under a null is harmless.
4. **The cache can hold such slots.** Spark's own writer never writes one: `DecimalWriter`
   (`sql/catalyst/.../arrow/ArrowWriter.scala:308-315`) writes a null when `changePrecision`
   fails. But a columnar input whose fields match the cache schema is cached zero-copy
   (`ColumnarBatchToArrowCachedBatchIterator.scala:92-100`), and `isCompatibleWithDeclaredField`
   (`ArrowUtils.scala:581-620`) compares types, not values. So a cached batch carries whatever an
   Arrow source wrote, under nulls too.
5. **The cache's row reader truncates instead** (`ArrowColumnReader.scala:124-148`): it writes the
   low word into the `UnsafeRow` with no check. This is `VARKA-365.md` 2 item 2, reported upstream
   under option (iii) and not changed here. Varka reads the columnar path, whose fallback is
   `ArrowColumnVector`, so the answer the kernel must match is item 3's.
6. **The cache format has no version and no schema.** `ArrowCachedBatch`
   (`ArrowCachedBatch.scala:49`) is the row count, one record batch message and the statistics;
   the schema is rebuilt on every read from the Spark types by `toArrowSchema(...,
   losslessInternalTypes = true)` (`ArrowCachedBatchToColumnarBatchIterator.scala:54`). The
   format already tags a field whose Spark type its Arrow type cannot carry, in the field's
   metadata (`ArrowUtils.scala:165-245`, the time precision and the nanosecond structs).
7. **The registry owes the type, with one lane per type.** `varka/VarkaTypes.java:281-283` gives
   `DECIMAL` the admission `Owed("347")`, storage 128 bits, no lanes, and no conformance suite.
   An admission is `OnLane(LaneType)` (`:65`) per type id, and `VarkaTypes.lane(DataType)`
   (`:224`), which the compiler and the evaluator ask, answers from the id alone. A decimal's lane
   depends on its precision: the long lane up to 18 (this row), the int lane up to 9 (this row's
   third arm), the lane pair above 18 (row 359).
8. **The evaluator admits a column by its vector class.** `VarkaLaneArrow.of`
   (`sql/core/.../execution/VarkaLaneArrow.java:104`) maps `DECIMAL` to null, and `reads`
   (`:132-139`) matches the class only. `isArrowBacked` (`VarkaEvaluatorBase.java:918-937`) checks
   the class and the row count; nothing reads the field. `allocate` (`:145-154`) uses the same
   table for outputs, and a decimal output is row 157's store.
9. **A kernel gets addresses; the stride is the emitter's.** `VarkaKernelRunner.fill`
   (`sql/core/.../execution/varka/VarkaKernelRunner.java:213-235`) passes each input's data
   address. The emitted body maps it as `length * stride` bytes, the stride being a lane's
   (`varka/VarkaBodyEmitter.java:1536-1546`, `Lane.java:136`, `byteStride = bits / 8`). There
   are two other branches: a boolean column (`boolInputs`), and an input whose stored lane is not
   the driving one, which `loadNarrowedSegment` (`:1518-1530`) maps at its own lane's stride. So a
   sixteen-byte branch must be tested before the narrowed one, or a decimal read on the int lane
   in a long-driven kernel is mapped at four bytes a row. `Analysis.registerInput`
   (`varka/Analysis.java:1205-1214`) records one lane per column. No input today is sixteen bytes a
   row.
10. **A column leaf is an ordinal and a lane.** `ColumnRef` (`varka/VarkaVectorIR.java:415`)
    renders in the shape key as `col:` plus the ordinal and the lane (`:1465`). A decimal read on
    the long lane as a `ColumnRef` would share a kernel class with a `BIGINT` column of the same
    tree: the shape key must tell them apart. `GuardedRange` (`:504`) declines a batch with a live
    lane outside a range, which is the precision check's shape; its stored-values form reads null
    rows too, so the check here must be the live-lane form.
11. **The census reading is thinner than the row's three operators.** `VARKA-376.md` 9 has
    `rearrange2`, `selectFrom` and `compress` on longs intrinsic and allocation-free at 512 and 256
    bits, and the Java fallback at 128 bits on x86 (68 to 220 bytes a row on the EPYC 7763,
    `results/VARKA-376-census-epyc-7763.json`). But the probes
    (`VarkaOperatorCensusProbe.java:521-538`, catalyst tests) are thinner than the design for two
    of them. `rearrange2` is the design's own form, `fromArray(a).rearrange(DEINTERLEAVE,
    fromArray(b))`, which JDK 25 implements as two one-vector rearranges and a blend
    (`LongVector.java:2333-2353` in `/home/max/proj/openjdk-build/jdk25`); it differs only in
    reading arrays, not segments. `selectFrom` is the one-vector form, and the two-vector
    `selectFrom(v1, v2)` the scope names is a different intrinsic (`selectFromTwoVectorOp`), native
    on x86 only with AVX-512, VL below 512 bits (`x86.ad:1833-1836`), and lowered to rearranges and
    a blend elsewhere (`vectorIntrinsics.cpp:2724-2739`); `compress` takes a data-dependent mask,
    not the constant even-lane one, and has no splice. All three read arrays, not segments.
12. **`compress` needs AVX2 with BMI2 on x86, and SVE on Arm, not "not NEON".**
    `x86.ad:1349-1355` refuses `CompressV` below AVX2, and its case in `x86.ad:1868-1875` refuses
    it only below 128 bits or for a subword type without VBMI2, then falls through to
    `Op_VectorLongToMask` (`:1876-1882`), which refuses when `UseAVX < 3 && !supports_bmi2()`. So
    the lane count is no condition of `CompressV` on longs. At two long lanes (128 bits) `compress`
    is still the fallback on x86, because its mask is: `x86.ad:1734-1744` refuses
    `VectorLoadMask`, `VectorMaskCast` and `VectorStoreMask` at `vlen == 2`, the refusal m7 row 374
    found, and `long.dec_compress` at 128 bits lists `compressExpandOp`, `compare`, `rearrangeOp`
    and `blend` as failed. `aarch64_vector.ad:176-180` refuses it only when
    `UseSVE == 0`. The Neoverse N2 runner has SVE, and its census cells read `I` for all three at
    128 bits (`results/VARKA-376-census-neoverse-n2.json`). A host with NEON and no SVE is not in
    the census.
13. **The long lane's measurements rest on row 383.** A decimal kernel is a long-lane kernel, and
    row 383 found long-lane kernels released from tier 4 by profiled loop predication. Every arm
    here is timed under row 407's rule, each arm driven to level-4 code first, and its tier state
    recorded beside its number.
14. **The baseline is not yet merged.** Row 377's decimal class, `VarkaDecimalBaselineBenchmark`,
    is on branch `varka-377-decimal`, not on master and not yet a pull request. Its four sections
    (a comparison, an arithmetic chain, a cast and a choice) are rows 348 to 350's; none of them
    fuses by a load alone.

What depends on #1049 (the lane split, open): only the line numbers in `Lane.java` and
`VarkaVectorIR.java`, and `LaneType.bits()`, whose comment says a value "is this wide in a vector
and in its column". A decimal on the long lane is not; that sentence gains "except a decimal
column" here. The design needs no new `LaneType`.

### What differs from the row text and the milestone plan

- **The load needs the precision check, not only the high-word check** (item 3). A slot holding
  `10^p` has a correct high word and still raises on the column path, so the load declines its
  batch. Row 348's done-when names "the load's precision check"; this row builds it, and 348's
  clause is then met.
- **The scale is checked with the bit width** (items 2, 3): a field at another scale is read
  rounded by Spark and wrong by a kernel.
- **"No `compress` on NEON" is "no `compress` without SVE", and on x86 it needs AVX2 with BMI2,
  and more than two long lanes, since x86 C2 lowers no two-lane mask** (item 12), and the census
  does not
  cover the de-interleave as the row builds it (item 11), so step 2 adds those cells first.
- **The cache arm has no version tag to add** (item 6): the format would carry the layout as a
  tagged field, as it carries the nanosecond structs. And it is a reader change for every consumer
  of the cache (2.6), the kind of change `VARKA-365.md` 9.1 turned down. Question 1.
- **A load alone has no coverage row.** Every SQL reader of a decimal column - a comparison, a
  null test, a cast, a choice - is rows 348 to 350's, and a decimal output is 157's. Question 2.
- **The registry gains a precision-dependent lane** (item 7). Question 3.

## 1. Goal and non-goal

A `DECIMAL(p, s)` column with `p <= 18` and `s >= 0` is read by a kernel from Arrow's sixteen-byte
slots as unscaled values on the long lane, with every batch that Spark's column path would raise
on declined to that path. The load is built in the forms the row names, measured with load and
compute apart at each width, and the losing forms kept behind options. Not this row: anything
computed on the values (348 to 351), the store (157), precision above 18 (359), a negative scale.

## 2. Design

### 2.1 The leaf

A new value leaf, `DecimalColumn(int ordinal, LaneType lane, int precision)`, on the long lane (or
the int lane, 2.3). It renders as `dec:` plus the ordinal, the lane and the precision, so it shares
no kernel with a `BIGINT` column (item 0.10), and two precisions take two classes, since the bound
is part of the code. The scale is not in the leaf: the values are unscaled, and the nodes above
carry it.

The leaf loads the low words and arms one check over the live lanes: the batch declines if any
valid slot has `hi != lo >> 63`, or `lo` outside `[-(10^p - 1), 10^p - 1]`. One leaf owns both
checks because they are one fact - the slot holds a value of `DECIMAL(p, s)` - and a leaf without
its bound would compute where Spark raises. The check is unconditional, as `GuardedRange`'s is,
under a new decline status, "a decimal slot outside its precision": a new `VarkaFusedKernel`
status bit beside `STATUS_VALUE_RANGE` (`VarkaFusedKernel.java:68-87`; the runner's bits from 8
up are never returned by a kernel), named in `VarkaFallbackAccounting` (`:100-108`). It is not
`STATUS_VALUE_RANGE` itself, so the accounting tells a bad slot from a range guard's decline. In
the masked body the check is under the validity word (garbage under a null never declines); in
the dense body every lane is valid. The mismatch is accumulated in a mask across the
loop and tested once a body, not per group.

`Analysis` records the input as stored at sixteen bytes a row, in a `decimalInputs` bitmask beside
`boolInputs`, and `loadInputSegment` maps it as `length * 16` bytes.

### 2.2 The de-interleave and the check

For each output vector of `n` longs the leaf loads two vectors, `a` and `b`, over `n` slots. The
shuffles and masks are built once in the kernel's prologue from `SPECIES_PREFERRED`; no second
long species is used for a half-width load, which is the box `skills/vector-api-and-width.md`
names for two species of one lane type.

- **`rearrange`** (the default candidate): `lo = a.rearrange(EVEN, b)`, and for the check
  `hi = a.rearrange(ODD, b)`, then `hi != lo >> 63`. The censused operator (item 0.11).
- **`compress`**, an arm: `lo = a.compress(E).or(b.compress(E).unslice(n / 2))` with `E` the
  constant even-lane mask; for the check, the same with the odd mask. Its op count is not known
  until step 2's cell; expected to lose to `rearrange`, which JDK 25 builds as two
  one-vector permutes and a blend (item 0.11).
- **The two-vector `selectFrom`** is built only if the assembly (`dev/varka_emit.sh --asm`) on the
  AVX2 runner shows it lowered to fewer instructions than the `rearrange` form, or the measurement
  of section 4 times it faster there. Its census cell cannot decide it: it has a native form only
  under AVX-512, and where it has none C2 lowers it inside the intrinsic to rearranges and a
  blend while the decision still reads intrinsic (10.1 item 2).
- **The shuffle-free check**, an arm over either form: a second load of the same rows at byte
  offset 8 gives `(hi0, lo1, hi1, ...)`, aligned with `a`'s `(lo0, hi0, lo1, ...)`, so
  `a8.compare(NE, a.lanewise(ASHR, 63))` under the even mask checks every high word with no
  cross-lane op. One output vector covers two input vectors, so it takes two offset loads (`a8`,
  `b8`) and two compares in place of one shuffle. Its compares run over the interleaved positions,
  two longs a row, so in the masked body each row's validity bit is first spread to its even lane,
  once for `a` and once for `b`: one more mask build per input vector. Its last lane reads eight
  bytes past the group, so the last full group of a batch and the epilogue take it masked.
  Unverified until measured; its loads and mask spreads may cost more than the shuffle it
  removes.

The epilogue loads `2 * remaining` longs under `indexInRange` masks. The options are a
`Choice` `decimalLoad` (`REARRANGE`, `COMPRESS`, and `SELECT_FROM` if built) and a `Flag`
`decimalCheckShuffleFree`, both `Reason.MACHINE`, since which form wins depends on the host.

### 2.3 The int lane for precision up to 9

Under a `Flag` `decimalIntLane` (`Reason.MACHINE`), a `DECIMAL(p, s)` with `p <= 9` is read on the
int lane: twice the lanes per register for every node above it. From Arrow it is a four-to-one
selection (four bytes of value in sixteen), built as two long de-interleaves and checks, then
`L2I` of both halves spliced into one int vector; the precision check implies the value fits an
int. It is expected to lose on the load and is judged on density, by the compute it carries
(4.3). It applies where the kernel plan drives at the int lane; a tree with a long value drives at
the long lane, and the int arm's columns then load in the low half as int columns do (`VARKA-28.md`
9). As with `narrowLanes`, the compute lane is the kernel plan's choice, not the registry's.

### 2.4 The registry and the Arrow admission

`VarkaTypes` gains an admission for a type whose lane depends on its precision,
`UpToPrecision(LaneType lane, int maxPrecision, String widerRow)`, which `lane(DataType)` and
`declineReason(DataType)` read: the long lane for `p <= 18`; for a wider decimal the reason "a
decimal(30,4) column: precision above 18 is row 359's"; for a negative scale the recorded decision
of the milestone plan's 2.8. Its lanes are `LONG` and `INT`, for the warm-up. Its conformance is
`Owed("Decimal", "348")`: the contract's ordering, choice and cast checks have no form until 348 to
350. `types.tsv` is regenerated.

`VarkaLaneArrow.of` maps `DECIMAL` to `DecimalVector` for reads. `allocate`
(`VarkaLaneArrow.java:145-154`) calls the matching entry's allocator, so the entry's allocator is
null and `allocate` returns null for it, as it does for a type with no entry; the compiler
declines a decimal output root before that, naming row 157. `isArrowBacked` checks a referenced
decimal column's field once a batch: `ArrowType.Decimal` with bit width 128 and the Spark type's
scale. The compiler hands the evaluator each decimal input's scale. It is a field read and allocates
nothing.

### 2.5 The first reader

If question 2 is answered as recommended, this row carries row 348's equal-scale forms over the
new leaf: `=`, `<>`, `<`, `<=`, `>`, `>=` and `<=>` of two decimal columns of one scale, or of a
column and a literal of its scale (the literal's unscaled long in the long table, read once in
the prologue), and `IS [NOT] NULL`. They are the existing `Compare`, `NullSafeEqual` and
`IsNotNull` at the long lane; no new node. Unequal scales, `IN`, the choice, negation and `abs`
stay 348's.

### 2.6 The cache layout, if built

Question 1 decides whether it is built in this row. What it would take:

- a SQL configuration, off by default, with its binding policy and the configuration audit suite
  run;
- under it, `toArrowField` with `losslessInternalTypes` writes a `DECIMAL(p, s)` with `p <= 18`
  as an `Int64` field tagged in its metadata with the precision and scale (`Int32` for `p <= 9`),
  so the schema rebuilt on read carries the layout and needs no version;
- a writer for the tagged field, `changePrecision` first and a null when it fails, as today;
- an `ArrowColumnVector` accessor for the tagged field that builds the same `BigDecimal`-backed
  value `SmallDecimalAccessor` does. This is a constraint, not a choice: a long-backed value would
  move the fallback to the exact rule and undo `VARKA-365.md` 9.1, on which row 350's design rests;
- `ArrowColumnReader`, `calculateMinMaxDecimal` (`ArrowCachedBatchSerializer.scala:905`) and the
  zero-copy check taught the tagged field (a `Decimal(p, s, 128)` input then takes the row copy);
- in the kernel, a plain long load with no check, since the writer bounded the values; the plan
  reads the layout from the cached relation when it plans, and a batch of the other layout fails
  `isArrowBacked` and takes the fallback.

The kernel side of this arm costs what a `BIGINT` column costs, which can be measured today
without building it (4.1).

### 2.7 Warm-up, cost, census

The warm-up's long block gains the shuffle, `compress`, `unslice` and offset-load calls of 2.2, and
its int block the `L2I` splice, held by `VarkaWarmupReachSuite`. `DecimalColumn` gets a cost feature
(and joins row 352's awaiting-refit list). The census marks the decimal pairs of 2.5 fused at
precision up to 18 only; a pair counts fused when it fuses at every precision (the milestone plan's
2.8), so the count moves with row 359.

## 3. Tests and oracles

1. **The slot table** (`VarkaDecimalLoadSuite`, catalyst), with batches written straight into
   sixteen-byte slots, at every arm of 2.2 and 2.3, in both bodies, in the first group, a middle
   group and the epilogue: garbage high words (random and all ones) under null slots, which must
   not decline; `10^p - 1` and `-(10^p - 1)`, which pass; `10^p` and `-10^p` with correct high
   words, and wrong high words (zero under a negative low word, minus one under a positive one, and
   one), each of which declines with the new status. Precision 1, 9, 10 and 18.
2. **End to end** (sql/core): the declined batch takes the row path, which raises
   `NUMERIC_VALUE_OUT_OF_RANGE` as a Varka-off columnar read does. The Varka-off reference must
   read through `ArrowColumnVector`: a cached table with Varka off reads through
   `ArrowColumnReader`, which truncates (`VARKA-365.md` 2 item 3), unless a column needs the
   fallback, so the fixture carries such a column or the reference reads the vector directly. A
   `Decimal(p, s, 64)` field, a scale mismatch and a `Decimal256Vector` are refused by
   `isArrowBacked` and answered right.
3. **The arms agree**: the differential over every option of 2.2 and 2.3 on the same batches, and
   the option matrix dispatched on the branch.
4. **Coverage rows** for the forms of 2.5 at precision 9 and 18, with columns `d9`, `d9b`, `d18`,
   `d18b` in the fixtures; the flipped tests (`VarkaTypesSuite`'s decimal decline now for `p > 18`
   only).
5. **The fuzzers**: a decimal sequence in `VarkaIrGrammar` with a seed of its own, so no existing
   hash moves, drawing in-precision values, the bounds, and garbage under nulls.
6. **The generated files** in `ADDING_A_TYPE.md`'s order; only new shapes and rows move. The
   128-bit x86 shapes are recorded refusals in `width_audit.json` (item 0.11).

## 4. Predictions and costs

The measurement is a catalyst class of its own, `VarkaDecimalLoadBenchmark`, on
`VarkaBenchmarkBase`, kernels emitted from the IR as `VarkaLaneConversionBenchmark`'s are, over a
ladder of row counts (in L2, in L3, past L3), results committed from `benchmark.yml` on the EPYC
7763 at 256 bits and at 128 (`MaxVectorSize=16`), and at 512 bits on a full-width runner when the
pool gives one, the datapath probe in the file. The baseline is row 377's decimal class, merged
first; the load class diffs against it only for the forms of 2.5.

1. **Load and compute apart.** Three kernels per arm over the same rows: load only (the leaf into
   a `BIGINT` store, the cheapest consumer); load plus a chain of `k` long adds (`k` = 1, 4, 16;
   IR only, as no SQL form exists before 349); and the same chain over a `BIGINT` column, which is
   compute with an eight-byte load. A pre-pass that de-interleaves into scratch as an int64
   column, timed alone, is the load with no compute at all. Load cost is the first minus the
   `BIGINT` load-only kernel; compute cost the chain's slope in `k`.
2. **The prediction per arm.** In L2, `rearrange` with its check costs 2 to 3 times the `BIGINT`
   load-only kernel per row, and the shuffle-free check saves part of that; past L3 every form is
   bound by reading sixteen bytes a row and costs about twice the `BIGINT` kernel, the forms tied.
   `compress` loses to `rearrange` at 256 and 512 bits. So the cache layout's kernel side, which is
   the `BIGINT` kernel, wins the load by up to 2 times past L3, and the margin falls as `k` grows.
3. **The int arm** loses the load at every rung and wins only in cache, at a chain long enough for
   its doubled lanes to pay for the narrowing: predicted from `k` = 4 to 16. Past L3 it loses,
   since it reads sixteen bytes for four.
4. **At 128 bits on x86** every form is the fallback loop and allocates (item 0.11), since x86
   lowers no two-lane mask (m7 row 374). The reading compares it with row 377's 128-bit file; if
   every arm loses to Spark's row path there, question 4 applies.
5. **No existing shape's bytes move**; the `option_arms` digests move with the new options.

The verdict per width names the default form, keeps the others behind their options, and records
the JDK limits of items 0.11 and 0.12 beside it.

Cost: large, as the row says. About six days after the baseline, one more if the cache arm is
built.

## 5. Order and files

1. **Row 377's decimal class**, its own pull request on the unchanged code.
2. **Census cells** in `VarkaOperatorCensusCatalog` for the de-interleave as built: the
   two-vector `selectFrom`, the constant-mask `compress` with its `unslice` splice, the offset-load
   check, all from segment loads; the laptop's column, then a `varka-width-audit.yml` dispatch.
3. **The leaf, the check, the registry and the Arrow admission** (2.1, 2.2's default, 2.4), with
   the slot table: bytes unchanged for every existing shape.
4. **The arms** (the rest of 2.2, and 2.3) and the differential.
5. **The first reader and the coverage rows** (2.5), if question 2 says so.
6. **The measurement** (4), committed per width, and the verdict with the JDK's limits.
7. **The cache layout** (2.6), only as question 1 decides.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `DecimalColumn` and its canonical form |
| `varka/Analysis.java`, `varka/VarkaKernelPlan.java` | the sixteen-byte input, the int arm's lane |
| `varka/VarkaVectorWalk.java`, `varka/VarkaBodyEmitter.java` | the de-interleave, check, segment |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | the three options |
| `varka/VarkaTypes.java` | `UpToPrecision`, the entry, the reasons |
| `VarkaNodeCompiler.java`, `VarkaConditionCompiler.java` | the leaf; 2.5's forms |
| `VarkaLaneArrow.java`, `VarkaEvaluatorBase.java` (sql/core) | the vector, the field check |
| `VarkaFusedKernel.java`, `VarkaFallbackAccounting.java` | the decline status and its name |
| tests | the slot table, end to end, differential, coverage rows, fuzz sequence, census cells |
| benchmark | `VarkaDecimalLoadBenchmark` and its results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `types.tsv`, `census.tsv` |

## 6. Out of this row

The decimal operators other than 2.5's (348), arithmetic and `UnscaledValue` (349), casts (350),
the functions (351), the store (157), precision above 18 and its pair (359), the exact
decimal-to-floating arm (366), a host with NEON and no SVE (not in the census), and a big-endian
host.

## 7. Declines that stay

A decimal output root (157), a decimal of precision above 18 (359), a negative scale (the milestone
plan's 2.8), an Arrow field of another bit width or scale (item 0.2), and every decimal operator 2.5
does not carry, each naming its row.

## 8. Questions for the owner

Answered by the owner on 27 October 2026, after the advisor's review of the recommendations:

1. **(b), with a threshold:** the cache layout is built, as a row of its own, only if the `BIGINT`
   kernel beats the best sixteen-byte form by at least 1.5 times past L3 and loses nothing in L2,
   at both 256 and 128 bits.
2. **(a):** this row carries 348's equal-scale comparisons and null tests; the null tests read
   validity only and are there for the coverage rows.
3. **(a), `UpToPrecision`,** admitting the long lane for precision up to 18 only; the int arm of
   2.3 stays an emission choice behind its option, not a second threshold. The admission reads the
   `DecimalType` instance, since `VarkaTypes.lane` resolves through the type id alone today.
4. **Row 27's rule, not (a) as first written:** a decimal kernel fuses at two long lanes as the
   long filters do, unless 4.4's reading shows a loss the filters do not have; a decline there is
   a recorded refusal with a scope item.

The questions as they were put:

1. **The cache layout.** (a) Build it in this row, off by default, as the row text says. (b)
   Measure first: the `BIGINT` kernel is its kernel side (4.1), so build it only if that beats the
   best de-interleave by a margin the owner names (2 times past L3 is the prediction), as a row of
   its own. (c) Drop it. *Recommended: (b).* It changes the cache's readers for every consumer,
   with Varka off as well (2.6), which is what `VARKA-365.md` 9.1 weighed against, and the
   measurement can say first whether it pays.
2. **The first reader.** (a) This row carries row 348's equal-scale comparisons and null tests
   (2.5) for its coverage rows. (b) This row lands the leaf with IR-level tests only, and the
   coverage rows of its done-when move to 348. *Recommended: (a)*: section 4.1 of the milestone
   plan already lands 348 beside 347, and the comparison is row 377's first section, so the
   baseline diff starts here.
3. **The registry shape.** (a) A precision-reading admission, `UpToPrecision` (2.4). (b) Keep
   `OnLane(LONG)` and test the precision inside `lane(DataType)`. *Recommended: (a)*: the fact is
   the type's, (b) hides it in a function and `types.tsv` would not show it.
4. **Two long lanes.** If every form loses to Spark's row path at 128 bits on x86: (a) decline a
   decimal kernel at two long lanes, a recorded refusal; (b) fuse anyway, as the long compares
   there do. *Recommended: (a)*, decided by the measurement of 4.4.

## 9. Done when

Row 347's text holds, with the precision check in the load: the forms of 2.2 and 2.3 read a decimal
column (with coverage rows under question 2's answer, and the cache layout as question 1 decides);
the slot table passes with garbage high words under nulls, slots holding `10^p` and wrong high
words; `bitWidth == 128` and the scale are checked on the Arrow field; the measurement of 4 is
committed per width with its verdict and the JDK's limits; the losing arms stay behind their
options; the bytes oracle is unchanged for every existing shape; and the PR build and the option
matrix pass.

## 10. Outcome

(Written when the work lands; the readings of steps 2 and 3 are below.)

### 10.1 Step 2: the census cells, on the laptop

*27 October 2026, at `475322b11ca`, before any of the row's code. The laptop column of
`sql/varka/operator_census.json` (AMD Ryzen AI 9 HX PRO 370, JDK 25.0.4.1, species of 512, 256 and
128 bits); the runner columns wait on a `varka-width-audit.yml` dispatch on the branch.*

**What was built.** Fourteen cells in `VarkaOperatorCensusCatalog`. Ten are new probe loops that
read Arrow's sixteen-byte slots from native memory, mapped once a call through `VarkaSegments.map`
as a kernel maps its input once a batch, in the forms of 2.2 and 2.3; four are the check's
operators alone, on arrays, in the existing loops. The verdict and allocation are read as row 376
reads every cell (`VARKA-376.md` 9).

| cell | what the loop does | 512 | 256 | 128 | bytes a row at 128 |
| :-- | :-- | :-- | :-- | :-- | :-- |
| `long.dec_rearrange` | 2.2's `rearrange`: the low words of `a` and `b` | I | I | F | 220 |
| `long.dec_selectFrom2` | the two-vector `selectFrom`, index vector 0, 2, 4, ... | I | I | I | 0 |
| `long.dec_compress` | `compress` under the constant even mask, `b`'s half placed by `unslice(n / 2)`, `or` | I | I | F | 236 |
| `long.dec_check_rearrange` | the `rearrange` form with its check: the odd shuffle for the high words, `hi != lo >> 63` and `lo` outside `10^18 - 1`, gathered in a mask from `fromLong(0)` and tested once by `anyTrue` | I | I | F | 584 |
| `long.dec_check_rearrange_masked` | the same under the validity word's `fromLong` mask | I | I | F | 648 |
| `long.dec_check_compress` | the `compress` form with its check, the high words under the odd mask | I | I | F | 664 |
| `long.dec_check_offset` | the shuffle-free check: loads at byte offset 8, compares under the even mask, the precision on `rearrange`'s low words | I | I | F | 544 |
| `long.dec_check_offset_masked` | the same with each row's validity bit spread to its even lane (`Long.expand`) | I | I | F | 672 |
| `long.dec_tail` | the epilogue: both loads under `indexInRange`, the store under one | I | I | F | 220 |
| `int.dec_int_splice` | 2.3's int arm: two `rearrange` de-interleaves, `L2I` parts 0 and -1, `or` | I | I | F | 220 |
| `long.NE`, `long.GT`, `long.LT` | the check's compares alone | I | I | F | 92 |
| `long.ASHR` | the sign shift alone | I | I | I | 0 |

At 512 and 256 bits every cell is intrinsic. The ten slot cells allocate under a tenth of a byte
a row, which is the segment each call maps (one object a call of 1024 rows), not the loop; the
four array cells allocate nothing.

**What it says.**

1. **At 128 bits on x86 every form is the fallback but one.** Everything that makes or reads a
   two-lane long mask falls back, as item 11 and `m7` row 374 have it, and the check's compares
   do alone (`long.NE`, `GT`, `LT`; the census had no signed long compare, `VARKA-375.md` 0). The
   exception is the two-vector `selectFrom`, which `x86.ad` (`:1833-1836`) matches as one
   instruction on an AVX-512VL CPU below 512 bits, and whose loop needs no mask. Its low words
   alone do not make a load: the check still needs a compare, so a decimal kernel at two long
   lanes on x86 is the fallback whatever the form, and question 4 stands.
2. **An `I` for the two-vector `selectFrom` does not mean native.** Where `x86.ad` refuses
   `SelectFromTwoVector`, C2 lowers it inside the intrinsic to two rearranges, an `and` and a
   blend (`vectorIntrinsics.cpp:2724-2739` and its lowering at `:2617`), and the decision still
   reads `(intrinsic)`. So the rule 2.2 had, built only if the cell reads `I` on the AVX2
   runner, cannot tell the two apart: on the EPYC 7763, which has no AVX-512, an `I` can only be the
   lowered form, the `rearrange` form's work plus the index wrapping. Step 3 needs the
   assembly (`dev/varka_emit.sh --asm`) or the measurement for it, not the cell, and 2.2 now
   decides the form by those.
3. **`compress`'s fallback at 128 bits is in both halves**: `compressExpandOp`, and the
   `unslice` splice's `compare`, `rearrangeOp` and `blend`. Whether AVX2 with BMI2 lowers both at
   256 bits is the EPYC column's cell (item 12).
4. **The masked shuffle-free check builds three masks per output vector** (two spread validity
   words and the row word for the precision check) where the masked `rearrange` check builds one;
   all are intrinsic here at 512 and 256. That is counted from the loop, not timed.
5. **What this means for 2.2's default form.** On the laptop the census separates no form at 512
   and 256 bits: all are intrinsic and allocation-free, so the default is the measurement's
   (section 4), with `rearrange` as 2.2 has it until then. The runner columns can still remove a
   form at 256 bits on AVX2 (`compress`, by item 12) or at 128 bits on the Neoverse N2.

**Departures from step 2's list.** A `rearrange` cell from the slots was added beside the
existing array cell, so the forms are read on one footing; and the check's compares and shift
were added as cells of their own, so that a fallback in a form's cell can be told from one in its
check. The two masked loops are written out rather than shared with the dense ones through a
helper: with a helper, the inlining the suite reads showed no C2 compile of the loop at 128 bits.
And the suite's second-species tell is narrowed to a shape's own class (`Long256Vector` and the
like), as `skills/vector-api-and-width.md` defines it: C2's printer keys a call site by its chain
of bcis, and the segment loads print "callee changed to `LongVector::fromMemorySegment`" with one
species in the JVM, which the wider pattern read as a second species in every slot cell. The
narrowed tell trips in none of the two-species cells either, at any width: the printer says
"callee changed to" only when one chain of bcis meets two callees, and those loops call each
species from its own site. So the suite asserts only the tell's absence in one-species cells.

**What the cells do not cover.** The shuffle-free check's masked load at byte offset 8 in the
last full group of a batch (2.2) has no cell: the offset cells load unmasked, reading the 64
bytes of padding past the slots. And the cells' shuffles and masks (`EVEN`, `ODD`, `EVEN_LANES`,
the bound) are `static final` constants, which C2 can fold, while 2.2 builds them in the kernel's
prologue from `SPECIES_PREFERRED`; the measurement of section 4 reads the kernel's form.

**Against the plan's citations.** Item 12 said `compress` on x86 needs four long lanes, citing
`x86.ad:1868-1875`, which refuse `CompressV` only below 128 bits (and a subword type without
VBMI2) before falling through to the BMI2 check; the two-lane refusal is the mask lowering of row
374 (`x86.ad:1734-1744`), which the cells' `failed` lists show. Item 12 and the list above are
corrected to that. The other line references of items 11 and 12 match the JDK source.

### 10.2 Step 3: the leaf, the check, the registry and the Arrow admission

*27 October 2026, on master at `a5ed9235723`, then merged with master at `08006d9aabf` (the
floating lanes, #1054, and step 2's census cells of 10.1, #1062). This step's code does not read
the census cells.*

**What was built.** `DecimalColumn(ordinal, lane, precision)` in `VarkaVectorIR`, on the long lane
and precision 1 to 18, rendering as `dec:<ordinal>:long:p<precision>`. `Analysis` records it in
`decimalInputs`, which `loadInputSegment` tests first and maps at `length * 16` bytes, and refuses
one column read both as it and as a `ColumnRef`. The walk loads `a` at `i * 16` and `b` a vector
further on, both under `indexInRange` masks over `2 * lanes` longs in the epilogue, takes the low
words by `a.rearrange(EVEN, b)` and the high words by the odd shuffle, and condemns a lane with
`hi != lo >> 63`, `lo > 10^p - 1` or `lo < -(10^p - 1)` through `emitGuardCollect`: under the
column's word in the masked body, folded into the body's accumulator and tested once at its end.
The reason is `Refusal.DECIMAL_SLOT` (re-armed, unconditional), its bit `STATUS_DECIMAL_SLOT = 1
<< 6`, named `decimal-slot` in `VarkaFallbackAccounting` and `docs/sql-varka.md`, its mechanism
`GUARD_DECIMAL_SLOT`. The registry's `DECIMAL` is `UpToPrecision(LONG, 18, "359")`, lanes `LONG`
(departure 7), conformance `Owed("Decimal", "348")`; `types.tsv` is regenerated. `VarkaLaneArrow`
maps `DECIMAL` to `DecimalVector` with no allocator, and `isArrowBacked` holds a decimal input's
field to `bitWidth == 128` and the column's scale (`decimalFieldMatches`).

**Departures from 2.1, 2.2 and 2.4.**

1. **The compiler declines a decimal column for now.** Once `VarkaTypes.lane` answers `LONG` for
   `DECIMAL(10, 2)`, the long column arm of `VarkaTimeCompiler` would build a `ColumnRef` over it
   and read its slots at eight bytes a row: a wrong answer, not a decline. A decimal arm ahead of
   it declines with "the operators over a decimal are row 348's" until step 5 builds the reader.
2. **The shuffles are `VectorShuffle.iota(species, start, 2, false)`**, built once a body after the
   accumulators, not `fromOp`, which would take a lambda in emitted code. Without the wrap an index
   past the first vector is partially wrapped and selects from the second, so the shuffle is the
   census's `DEINTERLEAVE` (`AbstractShuffle.partiallyWrapIndex` in the JDK 25 source).
3. **The evaluator reads each decimal input's scale from the child's attribute**, not from the
   compiler as 2.4 has it: the attribute is the column the input reads, and
   `CompiledVarkaProjection` stays as it is.
4. **`Info.lane()`, which carries no precision, answers `LONG` for the decimal**, so `types.tsv`,
   `VarkaLaneArrowSuite` and the oracles read it as on a lane; only `lane(DataType)` and
   `declineReason(DataType)` read the precision and the scale. `dev/varka_new_lane.py` reads the new
   `up_to_precision` admission as it reads `on_lane`, and owes `DecimalType`'s coverage column,
   width-audit shape and census entry to step 5.
5. **`IsNotNull` over the leaf is not admitted yet.** The null test reads the word alone, while
   analyzing the leaf beneath it arms the slot check, which Spark's `IS NULL` never makes; step 5
   decides the form with the reader.
6. **Not built here, as the order has it:** the leaf at the int lane (step 4, its constructor
   refuses `INT` today), the fuzz sequence (`VarkaIrFuzzSuite` and `VarkaEmitCostSuite` list the
   node as out of the grammar and awaiting row 352's refit), the warm-up's shuffle calls and the
   cost feature's price.
7. **The registry lists the long lane alone**, not `LONG` and `INT` as 2.4 has it: a decimal leaf
   refuses the int lane until step 4 builds that arm, so a listed `INT` would name a lane no
   decimal kernel loads in. Step 4 adds it back with the arm.
8. **Neither `Info.sparkValue` nor `Info.bits` answers for the decimal**, at the merge with #1054:
   both read the id's lane, which is now `LONG`, while a decimal's lane bits are an unscaled value
   and Spark's value is a `Decimal` at a scale the id does not carry. Both refuse it, as before.

**The slot table** (`VarkaDecimalLoadSuite`, `ownJvm`): at 2, 4 and 8 long lanes, precision 1, 9,
10 and 18, lengths 1 and 3 (no loop at all), 37 (the last row in the epilogue at every width) and
64, both bodies, with nulls
on every third row holding a random low word under a random or all-ones high word. Each declining
slot - `10^p`, `-10^p`, high word 0 under a negative low word, -1 under a positive one, 1 over 5
and over 0, and the long's two extremes under the wrong sign - is planted in the first group, a
middle one and the last row, valid and then null; the bounds `10^p - 1` and `-(10^p - 1)`, 0 and
-1 are planted the same way. The oracle reads the same `DecimalVector` through
`ArrowColumnVector.getDecimal`: the kernel declines with `STATUS_DECIMAL_SLOT` exactly where some
valid row raises, and answers every valid row's unscaled value otherwise, over the leaf alone and
under a long add and as both operands of one (its shared local). All pass, as does an all-null
batch of bad slots. `emitted_bytes.json`, `coverage.json`, `docs/sql-varka.md`'s table and
`width_audit.json` do not move.

**Where this met #1054** (the floating lanes), at the merge: `VarkaLaneArrow.of`'s null line,
which now maps `FLOAT`, `DOUBLE` and `DECIMAL` and leaves `BOOLEAN` and `TIMESTAMP` unmapped;
`VarkaTypesSuite.declineNamesTheType`, whose assertion that `DECIMAL(10, 2)` declines naming row
347 is dropped (the decimal facts are in `decimalLaneReadsThePrecision`); `types.tsv`,
regenerated; and `dev/varka_new_lane.py`'s `OWED`, which keeps both rows' entries.

**The review's findings, and what was done** (27 October 2026, after the merge):

1. **The golden suite's Arrow source keeps decimals out until step 5.** With the registry admitting
   `decimal(p <= 18)`, `VarkaArrowSource.varkaTyped` would have cached every relation with a
   decimal column through the Arrow cache, and the lift would have tried decimal literals. Neither
   reaches a kernel: the compiler declines every operator over a decimal (departure 1), and the
   lift keeps an entry only if the compiler fuses it. So the change would only have moved Spark's
   row path onto the Arrow cache for the decimal-only relations. One explicit gate,
   `VarkaArrowSource.sourced`, admits a type on a lane but not a decimal, for both paths. Step 5
   drops the decimal clause with the reader. The suites were not run without the gate. With it,
   `VarkaLiteralLiftSuite` and `VarkaGoldenExclusionsSuite` pass and the golden suite's Varka arm
   passes over every golden file (789 tests, none failed, 1,098 s on the laptop).
2. **The canonical IR parses the decimal leaf** (`dec:N:long:pP`; a missing precision, the int
   lane and precision 19 are refused). `everyNodeTypeRoundTrips` also reaches `WidenLane`,
   `NullSafeEqual`, `CondValue`, `BitOp` and `BitCount`, which it did not, and now checks that it
   is complete: it collects the records under the sealed `VarkaVectorIR` and fails on any that no
   tree reaches. The five boolean value nodes (`BoolColumn`, `CondConst`, `BoolCompare`, `CondIf`,
   `BoolNullSafeEq`) have no parse arm at all; they are listed by name as not parsed yet, the
   owner turning that gap into a row of its own, and the check fails if a tree reaches one of them
   while it is still listed.
3. **For the owner at step 5: the slot check is narrowed by the arms above the leaf.** Under
   `guardUnderArm`, on by default, the condemning mask is ANDed with the `IfElse` arms the leaf sits
   under (`VarkaVectorWalk.emitArmContext`), so a bad slot on a row whose arm is not taken does not
   decline the batch. That matches Spark, which reads a column under `CASE WHEN` only where the arm
   is taken, so the row engine raises on the same rows. Declining whatever the arm would be strictly
   safer, and costs batches that Spark answers. Left as it is; step 5, which builds the first
   operators that put the leaf under an arm, takes the decision.
4. **The registry lists `LONG` alone** (departure 7); `types.tsv` regenerates with that one
   column changed.
5. **Three tests in `VarkaDecimalLoadSuite`**, all at 2, 4 and 8 long lanes:
   `twoReasonsUnderExactStatus` runs the leaf under a `GuardedRange`, a body of two reasons, and
   holds the status to the reason that fired under `exactRefusalStatus` and to both bits without
   it; `perGroupBodiesUnderTheBudget` splits six outputs over the leaf into groups with a method
   byte budget, checks that every group's loop builds the shuffles, and runs the slot cases; and
   `mixedLanesBesideTheLeaf` runs `d + widen(i)`, a kernel the plan marks mixed, which takes
   `emitMixedEpilogue`, through the slot table. None is owed to a later step.
6. `VarkaKernelPlan` reads an input leaf's ordinal through `VarkaVectorIR.inputOrdinal`. That also
   records a boolean column's lane, which nothing sizes by, since `loadInputSegment` tests a
   boolean column first. `emitted_bytes.json` does not move.

### 10.3 Step 5: the first reader

*27 October 2026, on `varka-347-leaf` at `d279e924df5`, before step 4: the arms of 2.2 and 2.3
are not built, so the reader reads through the default `rearrange` form alone.*

**What was built.** `VarkaDecimalCompiler` compiles the operands of 2.5's forms. A comparison
(`=`, `<`, `<=`, `>`, `>=`, `<=>`, and `<>` as the `NOT` of the equality) of two decimals of
one scale is the existing `Compare` or `NullSafeEqual` over two unscaled longs: a decimal
column admitted by the registry, as its `DecimalColumn`, or a literal, whose unscaled value at
the scale takes a slot in the long table. `IS [NOT] NULL` of a decimal column is the existing
`IsNotNull` over the leaf. No new node. The compiler's condition arms hand a decimal operand
to this class before `compileNode`, and `VarkaTimeCompiler`'s decimal arm still declines a
decimal column in a value position (a choice, an arithmetic or cast operand, an output), now
naming rows 348 to 351.

**Why the long compare is Spark's answer.** Two values of one scale `s` are `u1 / 10^s` and
`u2 / 10^s`, so they compare as `u1` and `u2` do, and `Decimal.compare` compares by value (its
long fields where both are compact, `BigDecimal.compareTo` otherwise). Where the precisions
differ, `DecimalPrecisionTypeCoercion.decimalAndDecimal` casts both operands to
`DECIMAL(max(p1 - s, p2 - s) + s, s)`: one scale, the larger precision, a cast that keeps every
unscaled value and cannot fail in any mode, so the compiler reads it as its child, as it reads
`TIME`'s widening precision cast. A literal arrives folded in a real query; in a tree only the
analyzer resolved (the coverage suite's) it is the literal under the coercion's casts, which the
compiler folds by evaluating them, declining where that fails. Operands of two scales decline
naming row 348 (their rescale), and a comparison whose type is wider than 18 digits names 359.

**The null test.** 10.2 departure 5 rested on Spark's `IS NULL` reading the validity alone. It
does not: `IsNull.doGenCode` emits its child's code, and a column's code is
`isNull = isNullAt(i); value = isNull ? default : getDecimal(i, p, s)`, in a filter
(`FilterExec.genPredicate` evaluates the columns a predicate references before it), under
`ColumnarToRowExec.genCodeColumnVector`, and in the interpreted `IsNull.eval`, which calls the
child's `eval`. So Spark raises on a valid slot outside the precision under `d IS NULL` as under
any reader, and the null test must check the slots. It does: `Analysis` admits a
`DecimalColumn` under `IsNotNull`, `Slots.bodyUses` counts that edge, and the walk emits the
leaf's load and check under the test and drops its value, in the masked body and in the dense
one, whose rows are all valid. A null slot is never read, by Spark or by the kernel.

**10.2 finding 3, decided: the slot check is not narrowed by the arms above it** (the owner
confirmed this on 27 October 2026, after step 5 had built it: a decline is always Spark's answer,
and a narrower check would need a proof that Spark never reads the column in any plan shape).
`Analysis.armChainOf` answers the empty chain for a `DecimalColumn`. Spark reads a
column where its code is first referenced: a filter's generated code reads every column its
predicate references before the predicate, so a bad slot under a `CASE` arm Spark does not take
still raises there, and narrowing the check by the arm would answer where Spark raises. Declining
whatever the arm costs only batches holding a slot Spark's own writer never writes (0.4).

**The gates lifted.** `VarkaTimeCompiler`'s decline of every decimal operator (10.2 departure 1)
now covers the value positions only. `VarkaArrowSource.sourced` (10.2 finding 1) is gone: a
relation with a decimal column of precision up to 18 is cached through the Arrow source and its
decimal literals are lifted, for both golden paths.

**Coverage.** A "DECIMAL columns" family of 17 predicate rows over `d9`, `d9b` (`DECIMAL(9,
2)`), `d18` and `d18b` (`DECIMAL(18, 2)`): the six comparisons, `<>` and `<=>` at precision 9,
a literal, `d9 < d18` across the two precisions, three comparisons and a literal at 18, and both
null tests at both precisions. The coverage differential's fixtures derive the four columns from
`l` and `l2`, so they carry both signs and the null patterns of the bigints in every fixture.
The rows carry `DECIMAL_SLOT`, and `VarkaCoverageSuite.guardsOf` leaves it out of the domain
check: no value of the column's type lies outside the check, so there is no domain to name.
The columns join `VarkaEmittedBytesSuite`'s and `VarkaWidthAuditShapes`' lists; the width
audit's probe writes a decimal input as sixteen-byte slots. `VarkaCoverageRows.readsDecimal`
keeps the rows out of the composition fuzzer and `VarkaSparkFuzz`, as `readsNarrow` does the
narrow ones: their batches are eight bytes a row, and drawing the type is row 352's. The
template warm-up's long block calls `VectorShuffle.iota` and the two-vector `rearrange`, which
the rows' kernels now reach (`VarkaWarmupReachSuite`).

**Departures.**

1. **The census marks nothing fused for `dec`.** The milestone plan's 2.8 counts a decimal pair
   fused only when it fuses at every precision up to 38, so the nine entries 2.5 serves carry a
   note and row 347 instead, and `dev/varka_new_lane.py`'s census line for `DecimalType` is owed
   to row 359; its coverage and width-audit lines are gone. The coverage rows cast `dec` to
   `dec` (the widening and the literal's cast), which the cast matrix never fuses as a value, so
   `dev/varka_census.py` lists the pair in `CONTEXT_CASTS`, and `casts.tsv` names the context.
2. **No decimal conformance suite.** `VarkaTypeConformance` builds four- and eight-byte batches
   and holds them to an oracle that reads the type's internal value as a long; a decimal needs
   sixteen-byte slots and a `Decimal`. The registry keeps `Owed("Decimal", "348")`.
3. **The row `d9 > 5` was dropped**: type coercion makes the int literal a decimal by an `int`
   to `dec` cast, a second cast pair no value form fuses, and `d9 = 0.42` covers the literal.

**Tests.** `VarkaDecimalLoadSuite` gains three: the slot table over `IfElse(cond, 1, 0)` for
`d IS NOT NULL` and `d IS NULL` (a valid bad slot declines in both bodies, a null one does not)
and for `d < 5` and `d <=> 0`, and a bad slot under a `CASE` arm the batch never takes, which
declines. The coverage differential runs the 17 rows end to end under both consumers on every
fixture, and the golden suite's Varka arm, now over cached decimal relations and lifted decimal
literals, passes over every golden file (789 tests, none failed, 1,058 s on the laptop);
`served.json` is regenerated, its served executions up from 1,761 to 2,018. `emitted_bytes.json`
adds the 17 rows and moves only the `option_arms` digests, which cover every shape;
`width_audit.json` adds them at every width. `VarkaLiteralLiftSuite`, `VarkaGoldenExclusionsSuite`,
`VarkaWarmupReachSuite`, the family suites and `VarkaTypesSuite` pass.

**At 128 bits** (`width_audit.json` on the laptop, `MaxVectorSize=16`): every decimal row's kernel
is the Java fallback for its two-lane long operations, the two-vector `rearrange` among them
(`op=shuffle/rearrange vlen=2`), beside the compares, blends and mask casts every two-lane long
kernel refuses; at 256 and 512 bits C2 refuses nothing in them. That is item 0.11's reading at
the row's first reader, and question 4's decision waits on step 6's measurement of it.

**Found on the way, fixed here.** `VarkaEmitterBudgetSuite`'s weight test had no row for
`DecimalColumn`, a step 3 omission, and the leaf's weight of 8 counted its two mask `or` calls,
which are not lane calls, and not its second load: it is 7, which moves no shape's bytes. A null
test over a decimal of precision above 18 now declines naming row 359, as a comparison does.

**Left by this step.** Section 3 item 2's end-to-end test, a declined batch whose bad slot
reaches the row path and raises `NUMERIC_VALUE_OUT_OF_RANGE` there, is built in neither step 3
nor this one; the slot table and the condition table are its oracles at the IR level until it
is. It needs an Arrow source that writes a slot Spark's writer never writes, and is step 6's,
beside the measurement's own fixtures.

**Correction, 30 October 2026: the coverage answers run below the session.** The paragraph on
tests above says the coverage differential runs the 17 rows end to end under both consumers on
every fixture. That was so when it was written; row 509, which reached this branch with the
merge `27511683ac9`, moved those checks below the session. At `1c75953cab8`
`VarkaCoverageHarnessSuite` runs each of the 17 rows four ways with no session - the answer
under both consumers, the null tests as `CASE` conditions, the partition oracle's true, false
and unknown rows, and the ANSI form - 68 tests, over fixtures the Arrow cache's own iterators
write from `VarkaCoverageDifferentialSuite.fixtureView`, which still derives `d9`, `d9b`, `d18`
and `d18b`. The session suite keeps one decimal row, `d9 < d9b` under both consumers, as the
family's end-to-end path.

**The second review's findings** (30 October 2026, fixed on `varka-347-leaf` and merged here):

1. **The registry separates the lane from the reader.** `UpToPrecision` carries `readerRow`, the
   row that owes the first operator over the column, and `VarkaTypes.lane(DataType)` answers
   empty while it is set; every consumer asks that one question. The leaf sets it to 348, which
   kept `dbl > 1.5 AND dec > 1.0` fusing its double conjunct there (the filter's integral-read
   test had counted the unread decimal); this branch empties it, and here the decimal conjunct
   fuses and the double one goes to the row filter, as beside a bigint. The time compiler's
   decimal arm declines any decimal column by type, the registry's reason first.
2. **The digit check is one unsigned compare**, `(lo + B) UGT 2B`, proven in
   `proofs/decimal_slot.smt2` at every precision and for any non-negative long bound. It moves
   the 17 decimal rows' bytes and 128-bit refusal lists and the option arms' digests, nothing
   else; the leaf's weight stays 7.
3. **The decimal segment takes the mapping fault injector**, held by `VarkaDecimalLoadSuite`
   over a condition root and by `VarkaReproductionEndToEndSuite` over a decimal filter, whose
   file carries sixteen bytes a row and replays.
4. `VarkaWithChildrenSuite` names `DecimalColumn` as drawn by no grammar, with the reason; the
   bound reads Spark's `Decimal.POW_10` and `MAX_LONG_DIGITS`; the stride check runs once after
   the walk.
