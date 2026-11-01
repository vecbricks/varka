# VARKA-157: The decimal widening store

## 0. What exists now (admission check)

From master at `ef32e007d3a` and from row 347's local branch `varka-347-reader` at `95e8e7fba90`
(its leaf, step 3, and its first reader, step 5, not merged; 30 October 2026). Line numbers of
Varka files are the reader branch's, since this row lands on top of it (the milestone plan's 4.1
puts 157 beside 347, sharing its files); Varka paths are under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named. The
row's text is the milestone plan's 2.8 ("Row 157 is the output's mirror") and `m5/PLAN.md` 2.93
with its table row. Spark's facts were read in the fork's sources, Arrow's in
`arrow-vector-19.0.0-sources.jar`, the JDK's in `/home/max/proj/openjdk-build/jdk25`. A claim
marked **[I]** is inferred, not read.

1. **Every writer Spark has puts the same sixteen bytes in a slot.** `ArrowUtils.toArrowType`
   maps every decimal to `Decimal(p, s, 128)` (`VARKA-347.md` 0, item 1). Spark's own writer,
   `DecimalWriter` (`ArrowWriter.scala:299-316`), calls `changePrecision` and then
   `DecimalVector.setSafe(BigDecimal)`, which checks precision and scale and calls
   `DecimalUtility.writeBigDecimalToArrowBuf` (`DecimalVector.java:345-349`): the unscaled
   value's two's complement bytes, reversed to little-endian and padded with `0xFF` when negative
   and `0x00` otherwise (`DecimalUtility.java:195-222`). `DecimalVector.set(int, long)` writes the
   long and then `Long.signum(value) == -1 ? -1L : 0L` in the next eight bytes
   (`DecimalUtility.java:164-183`), which is `value >> 63`. So for a value that fits a long, the
   slot is the low word `u` at byte 0 and `u >> 63` at byte 8, whichever writer made it, and this
   row's store must write exactly that.
2. **The reader the output meets checks that word, by the vector's precision.**
   `ArrowColumnVector` picks `SmallDecimalAccessor` when the *vector's* precision is 1 to 18
   (`ArrowColumnVector.java:207-213`, on master). Its `getDecimal` reads the low and the high
   word, and when `high == unscaled >> 63` builds `Decimal.apply(BigDecimal.valueOf(unscaled,
   arrowScale), p, s)`, otherwise `getObject` over all sixteen bytes (`:455-484`). A wrong high
   word therefore reads as a value of at least `2^63`, which `Decimal.set` rejects with
   `NUMERIC_VALUE_OUT_OF_RANGE` (`Decimal.scala:119-136`); a wrong field scale is read rounded
   by `ROUND_HALF_UP`. So the destination's field must carry the output type's precision and
   scale, and its high words must be the sign extension; nothing else makes a wrong answer.
3. **The cache reads every word too.** The columnar consumer caches the Varka node's output
   batch; the cache's statistics read each valid slot through `DecimalVector.getObject` and
   `Decimal(bigDecimal, p, s)` (`ArrowCachedBatchSerializer.scala:905-925`), and its comment on
   `compactDecimalUnscaledOffset` (`:438-445`) states the little-endian low-word-first order.
4. **No decimal output exists.** `VarkaLaneArrow.of` maps `DECIMAL` to `DecimalVector` with a null
   allocator (sql/core `VarkaLaneArrow.java:118`), so `allocate` returns null for it (`:182-193`)
   and `VarkaKernelEvaluator.allocateVector` would throw "no Varka output vector"
   (sql/core `VarkaKernelEvaluator.java:501-518`). `fromField` (`VarkaLaneArrow.java:125-128`)
   builds a vector from `ArrowUtils.toArrowField`, Spark's own field, and is what the long and
   floating lanes use; for a decimal it gives `Decimal(p, s, 128)` **[I, from item 1's mapping]**.
5. **The runner already sizes an output by its vector.** `runKernel` sets `dstWidth[o]` from
   `vector.getTypeWidth()` and guards `len * width` bytes under the sanitizer
   (`VarkaEvaluatorBase.java:590-604`); the dense-against-masked comparison maps outputs at
   `len * w` and compares `w` bytes a row (sql/core `varka/VarkaKernelRunner.java:373`,
   `:416-431`). A width of 16 needs no change there **[I, read, not run]**.
6. **The emitter maps an output at the driving lane's stride and stores one vector a group.**
   `emitOutputSegments` maps a root at `dataBytes`, or at its own lane's stride through
   `loadNarrowedSegment` (`varka/VarkaBodyEmitter.java:341-365`, `:1532-1546`); `emitLaneGroup`
   stores the root's vector at `byteOffset`, whole in the loop and under the epilogue mask
   (`:1194-1220`), with the narrowing store of a `NarrowLane` root (`:960-1001`) and the low-half
   store (`:1064-1098`) as the two other forms. The input side has the sixteen-byte branch this
   row mirrors: `loadDecimalSegment` maps `length * 16` bytes (`:1573-1584`), tested before the
   narrowed branch.
7. **347's load is the inverse of this store, and its form is the template.** `emitDecimalLoad`
   (`varka/VarkaVectorWalk.java:539-614`) loads `a` at `i * 16` and `b` a vector further on, in
   the epilogue under `indexInRange(0, 2 * lanes)` and `indexInRange(length, 2 * lanes)`, and takes
   the words by the two-vector `rearrange` under shuffles built once a body by
   `VectorShuffle.iota(species, start, 2, false)` (`VarkaBodyEmitter.java:254-266`).
8. **The JDK has the interleave as a factory, with no lambda in the caller.**
   `VectorShuffle.makeZip(species, part)` is `fromOp(i -> i / 2 + (i % 2) * VLENGTH + part *
   VLENGTH / 2)` (`VectorShuffle.java:475-481`, `zipIndex` `:520-523`), the odd lanes' sources
   being past the first vector, and the species builds it with indices partially wrapped
   (`Long256Vector.java:946-953`), so under the two-vector `rearrange` they select from the second
   vector (`LongVector.java:2326-2353`: two one-vector rearranges and a `blend` on
   `laneIsValid`). The two-vector `selectFrom(v1, v2)` takes an index vector and wraps each index
   into `[0, 2 * VLENGTH)` (`LongVector.java:539-550`, `:2463-2471`; `VectorIntrinsics.java:
   136-143`), so `makeZip(species, part).toVector()` is its index vector **[I, from the wrap]**.
9. **Where each form is native.** x86 C2 refuses `VectorRearrange` and `VectorLoadShuffle` at two
   lanes and every mask load, cast and store there (`x86.ad:1726-1744` in the JDK source), and
   matches `SelectFromTwoVector` only with AVX-512VL below 512 bits (`x86.ad:1833-1836`),
   lowering it to rearranges and a blend elsewhere (`VARKA-347.md` 10.1, item 2). The census
   agrees on the laptop (`sql/varka/operator_census.json`, AMD Ryzen AI 9 HX PRO 370, AVX-512VL):
   `long.dec_selectFrom2` intrinsic and allocation-free at 512, 256 and 128 bits,
   `long.rearrange2` and `long.dec_rearrange` intrinsic at 512 and 256 and the fallback at 128,
   and `long.ASHR` intrinsic at all three. The EPYC 7763 column (`results/VARKA-376-census-
   epyc-7763.json`) has `long.rearrange2` intrinsic at 256 and the fallback at 128 and no `dec_`
   cells yet; the store's own cells (the zip, both forms, from and to segments) exist on no host.
10. **The two producers.** The compiler declines them by name, naming this row:
    `getSecondsOfTimeWithFraction` and `timeToSeconds` (`VarkaTimeCompiler.java:442-443`, the
    reason at `:286-289`).
    - `time_to_seconds(t)` is `DECIMAL(14, 6)` (`timeExpressions.scala:987-990`), computed as
      `Decimal(nanos) / Decimal(NANOS_PER_SECOND)` then `changePrecision(14, 6)`
      (`DateTimeUtils.scala:1191-1195`). The division is to scale 39 rounding `DOWN`
      (`Decimal.scala:526-533`, `:597`), exact since `nanos / 10^9` has nine digits, and
      `changePrecision` rounds `HALF_UP` (`:352-353`); for nanoseconds of day `n >= 0` the
      unscaled value is `(n + 500) / 1000` **[I, from those three]**, at most `86400000000`.
    - `extract(SECOND FROM t)` and `date_part` over a `TIME(p)` are `SecondsOfTimeWithFraction`
      (`timeExpressions.scala:154`, `:378-392`), `DECIMAL(2 + p, p)`, `p` from 0 to 9
      (`TimeType.scala:51-54`). It computes `s = (n / 10^9) % 60`, `g = (n % 10^9) * 10^p / 10^9`
      and `Decimal(s + g.toDouble / 10^p, 2 + p, p)` (`DateTimeUtils.scala:173-179`): a `Double`,
      which reaches `Decimal.set(BigDecimal, p, s)` (`Decimal.scala:119-136`) through Scala's
      implicit `Double` to `BigDecimal` conversion, so its string form, rounded `HALF_UP` to `p`
      digits **[I: the implicit is the only overload that compiles, `Decimal.scala:602-623`]**.
      The kernel's form is `s * 10^p + g` with `g = (n % 10^9) / 10^(9 - p)`; that the two agree
      is the exhaustive check of `m5/PLAN.md` 2.93 (section 3 item 4).
    Both values are non-negative and within their precision, so neither raises in any mode.
11. **The lane already computes both.** `second(t)` is `remainderOfSixty(ConstDivide(n, 10^9))`
    (`VarkaTimeCompiler.java:377-382`, `:611-617`), whose dividend stays under
    `ConstDivide.EXACT_DIVIDEND_BOUND`, `2^52` (`VarkaVectorIR.java:783` on master). The long
    divisors proven in `sql/varka/proofs/long_divide.smt2` are 60, 1000, `10^6`, `10^9`,
    10546875, `6 * 10^10` and `3600 * 10^9` (and -60); `VarkaDivisorProofSuite` fails a compiled
    divisor the proof does not list. The fraction needs `10^(9 - p)`: `10^8`, `10^7`, `10^5`,
    `10^4`, 100 and 10 are new, six proof rows.
12. **No output-type admission stands in the way.** The calls of `VarkaTypes.lane` and
    `declineReason` are all on operands (`VarkaDecimalCompiler`, `VarkaTimeCompiler.java:166-177`,
    `VarkaNodeCompiler.java:540-548`, `:838`); an output's type reaches only the evaluator's
    allocator (item 4) **[I, from a search of the calls, not of every path]**.
13. **The baselines.** `VarkaTimeBenchmark` (catalyst) has the ladder of rungs the TIME kernels
    are measured on, and the narrowed store's arms beside the shipped one; it has no decimal
    output. Row 377's `VarkaDecimalBaselineBenchmark` (sql/core, results from the EPYC 7763 at 256
    and 128 bits) times decimal columns on Spark's row path and has neither TIME expression.

### What differs from the row text and the plans it cites

- **The store needs no check of its own.** The slot is a function of the unscaled long (item 1),
  so the store is `lo` and `lo >> 63` and cannot fail; what it needs is a producer whose value is
  within the output's precision, which both of item 10 are, by their range.
- **"Two two-vector selects" has two forms in the JDK** (items 8, 9): the two-vector `rearrange`
  under `makeZip`, which `VARKA-359.md` 2.4 names, and the two-vector `selectFrom` under
  `makeZip(...).toVector()`, the one form native at 128 bits on an AVX-512VL host. 2.2 builds
  both as one option and keeps the losing arm.
- **The two producers are also row 351's**: its text lists `time_to_seconds` and `second` with
  its fraction. `m5/PLAN.md` 2.93, whose list is this row's done-when, makes them this row's
  proof of the store, and the compiler's decline names this row. Question 1.
- **"Every nanosecond of a minute" is 60 times `10^9` evaluations at `TIME(9)`**, which 2.93 did
  not price (item 10; section 3 item 4).
- **`VarkaTimeBenchmark`'s ladder, not its file**: the rule that a feature family gets its own
  benchmark class puts the measurement in a class of its own over the same rungs (section 4).
- **Six new long divisors** join the proof file (item 11).

## 1. Goal and non-goal

A kernel writes a decimal output of precision 18 or less into Arrow's sixteen-byte slots, the low
word and its sign extension, so that every reader - `ArrowColumnVector`, the cache's statistics
and its row reader - sees the bytes Spark's own writer writes; `time_to_seconds(t)` and
`extract(SECOND FROM t)` over every `TIME(p)` fuse through it and agree with Spark. Not this row:
a decimal input (347), the operators over decimals (348 to 350), the other decimal-valued
functions (351, and `extract(SECOND FROM dt)`, row 103), a value of precision above 18 and its
pair store (359), a big-endian host.

## 2. Design

### 2.1 The node

A root-only node `WidenDecimal(VarkaVectorIR child)` on the long lane, as `m5/PLAN.md` 2.93 has it:
its value is its child's, unchanged, and its store writes each lane's word and that word's sign
extension into the lane's slot. `Analysis` refuses it under any other node and in a kernel not
driven at the long lane, as `emitDecimalLoad` refuses its leaf in another emission. It renders as
`wdec(<child>)`, so a decimal root never shares a kernel class with a `BIGINT` root of the same
tree; it carries no precision or scale, since its bytes depend on neither, and the evaluator takes
both from the output's type. The reference evaluator passes the value through and asserts
`|u| <= 10^18 - 1`. `VarkaCanonicalIr` gains its parse arm, which the completeness check of
`VARKA-347.md` 10.2 (finding 2) requires of a new record.

The other shape on record is `VARKA-359.md` 2.4's: an output of two roots, the low word and
`BitOp(SHR, lo, 63)`, bound to one `DecimalVector`. Both feed one emitter helper that takes the
two word vectors from the stack (2.2), so the pair store of 359 reuses this row's helper whichever
binding is chosen; this row builds the root-only node, as 2.93 has it. Section 7 records the
difference against 359's plan.

**Where the node goes.** The compiler wraps a decimal-valued expression in `WidenDecimal` only where
it is an output root, at the binding that knows the output's type, so the lowerings of 2.3 build
the bare long-lane value. The same expression in a value position under another operator -
`time_to_seconds(t) < 10.5`, a comparison of a decimal-valued function - declines naming row 348,
which compares decimal operands; that row can then consume the bare value with no node to remove.
This differs from `NarrowLane`, which is allowed anywhere because an int goes anywhere; a decimal
operand's other consumers are not built yet. Row 348's plan (on its branch `varka-348-349-plan`)
takes the same rule.

### 2.2 The store

Per lane group of `n` rows, with the child's vector `lo` on the stack:

1. `hi = lo.lanewise(ASHR, 63)`;
2. `out0 = lo.rearrange(Z0, hi)` and `out1 = lo.rearrange(Z1, hi)`, with `Zk =
   VectorShuffle.makeZip(species, k)` built once a body in the prologue beside 347's shuffles:
   `out0` holds rows 0 to `n/2 - 1` as `(lo0, hi0, lo1, hi1, ...)`, `out1` the rest;
3. `out0` stored at `i * 16`, `out1` at `i * 16 + vectorByteSize`, `LITTLE_ENDIAN`, whole in the
   loop and in the epilogue under `indexInRange(0, 2 * lanes)` and `indexInRange(length, 2 *
   lanes)`, the load's two masks.

Four lane calls and two stores per group, against the plain store's one. Under a `Choice`
`decimalStore` (`Reason.MACHINE`, the shape of 347's `decimalLoad`), the arm `SELECT_FROM` replaces
step 2 with `I0.selectFrom(lo, hi)` and `I1.selectFrom(lo, hi)`, `Ik = makeZip(species,
k).toVector()` built in the prologue; the default is `REARRANGE` until the measurement of section
4 names one per host. The losing arm stays behind the option, as the house rule keeps every losing
arm.

**The prologue's construction, under a hard rule.** `makeZip` goes through the species'
`shuffleFromOp`, which fills a
`long[VLENGTH]` through a lambda inside the JDK (`Long256Vector.java:946-953`) and is not the
intrinsic `iotaShuffle` 347's shuffles use; called in a body's prologue it may allocate once per
body call **[I, not measured]**. `sql/varka/AGENTS.md` forbids allocation on a path that runs per
batch, and a body's prologue runs once a batch, so this is not a preference: if `makeZip`
allocates there, the lanewise construction is required. It is `iota =
LongVector.zero(species).addIndex(1)` and the index vector `(iota >>> 1) + (iota & 1) * n + part *
n / 2`, used as it is by `SELECT_FROM` and through `toShuffle()` by `REARRANGE`. 157a's census cells
build the zip both ways as the prologue builds it, not from a `static final`, and each cell passes
only if it allocates nothing at 512 and 256 bits beyond the segment a call maps (the tenth of a
byte a row 347's slot cells read as that, `VARKA-347.md` 10.1); the form whose cell fails is not
emitted.
Null rows are stored like valid ones; their slots are `lo` and `lo >> 63`
of whatever the lane holds, which no reader reads (item 0.2's `isNullAt` first).

`Analysis` records the root in a `decimalOutputs` bitmask beside `decimalInputs`, and
`emitOutputSegments` maps it at `length * 16` bytes, tested before the narrowed branch, as
`loadInputSegment` tests its input. A kernel that converts between lanes stores the root in its
long groups as any long root; `mixedLanesBesideTheStore` runs one (section 3 item 1).

**At each width.** At 512 and 256 bits every call is intrinsic on the laptop (item 0.9); on the
EPYC 7763 the `rearrange` arm is, and `SELECT_FROM` is lowered by C2 to rearranges and a blend
plus the index wrap, so it can only tie or lose there **[I]**. At 128 bits on x86 both arms of the
zip are the Java fallback except `SELECT_FROM` on an AVX-512VL host, and the epilogue's masked
stores are the fallback on every x86 host (`long.dec_tail`); so the store follows `VARKA-347.md`
question 4's answer, row 27's rule: it fuses at two long lanes as the long projections do, unless
the measurement shows a loss the BIGINT store does not have, which then becomes a recorded refusal
with a scope item, as `VARKA-359.md` 2.6 has it. Row 503's decline covers condition roots only and
does not reach a store. The laptop's 128-bit numbers are AVX-512VL numbers and the runner's are
not, and every 128-bit number reports the release rate beside it (the milestone plan's 10.3 item
4).

### 2.3 The two lowerings

- `time_to_seconds(t)`: `WidenDecimal(ConstDivide(IntArith(ADD, WRAP, n, 500), 1000))`, the
  dividend under `86400 * 10^9 + 500`, below `2^47`. 1000 is a proven divisor.
- `extract(SECOND FROM t)` over `TIME(p)`: `WidenDecimal(s * 10^p + g)`, with `s` the existing
  `second(t)` tree, `r = n - (n / 10^9) * 10^9` sharing its `ConstDivide`, and `g = r / 10^(9 - p)`;
  at `p = 0` the value is `s` and at `p = 9` it is `s * 10^9 + r`, with no fraction division.
  Every intermediate is below `6 * 10^10` **[I, by the bounds]**.

Both replace the decline at `VarkaTimeCompiler.java:442-443`, whose reason text goes. Each
divisor of 2.3 joins `VarkaProofFiles.LONG_DIVISORS` and the rendered proof.

### 2.4 The evaluator

`VarkaLaneArrow.of(DECIMAL)` takes `fromField` as its allocator, refusing a decimal of precision
above 18 (359's) with a reason; `allocate` then builds the destination from Spark's own field, so
`ArrowColumnVector` picks `SmallDecimalAccessor` by the vector's precision (item 0.2) and the cache
sees the field its writer would build. Nothing else in the evaluator changes (item 0.5).

### 2.5 Warm-up, cost, census

The template warm-up's long block calls `makeZip`, the two-vector `rearrange`, the
`selectFrom(v1, v2)` form and two stores, held by `VarkaWarmupReachSuite`. `WidenDecimal` gets a
budget weight row (four lane calls, two stores) and a cost feature on row 352's awaiting-refit
list. The census is regenerated (section 6).

## 3. Tests and proofs

1. **The store table** (`VarkaDecimalStoreSuite`, catalyst, `ownJvm`, the mirror of 347's slot
   table): `WidenDecimal` over a long column at 2, 4 and 8 long lanes, lengths 1, 3, 37 and 64,
   both bodies and both arms of `decimalStore`, with values 0, -1, `10^18 - 1`, `-(10^18 - 1)`
   and random values of both signs within 18 digits planted in the first group, a middle one
   and the last row, under nulls and not. The oracle is byte-exact: each valid slot equals what
   `DecimalVector.set(int, long)` writes for the same value (item 0.1), and reading the vector
   through `ArrowColumnVector.getDecimal` gives `Decimal(u, 18, 0)`. Neither producer reaches a
   negative value, so this table is the only place the sign extension's `-1` is exercised before
   348's negation and 103's interval seconds; `Decimal.equals` compares by value (`compare == 0`),
   so the harness alone would see a wrong high word only as an error. Also
   `mixedLanesBesideTheStore`
   (a kernel that converts between lanes) and `perGroupBodiesUnderTheBudget` (six decimal outputs
   split by the byte budget, each group building its zip shuffles), as 347's table has them.
2. **The guard page.** The epilogue's two masked stores end on the last slot of a mapping whose
   next page is unmapped (`READING_QUESTDB.md` 10 item 6, the load's twin): a store past
   `length * 16` faults instead of passing silently.
3. **The producers against Spark.** `VarkaTimeHarnessSuite` gains both expressions over every
   second of the day with the sub-second offsets of its day table, at `TIME(0)`, `TIME(3)`,
   `TIME(6)` and `TIME(9)`, and the `time_to_seconds` rounding edges `x.0000005` and
   `23:59:59.9999995` (which rounds to `86400.000000`). Coverage rows in a "TIME decimal outputs"
   family, `time_to_seconds(t)` and `extract(SECOND FROM t)`, run through
   `VarkaCoverageHarnessSuite` and `VarkaCoverageDifferentialSuite` under both consumers - the row
   plan and the cached view the Arrow cache builder feeds from the columnar node - which is 2.93's
   "through both consumers".
4. **The fraction against the row engine's double round trip.** Spark's value depends on `n` only
   through `(s, g)` (item 0.10), so checking every pair checks every nanosecond of a minute:
   `60 * 10^p` pairs at `TIME(p)`, Spark's `getSecondsOfTimeWithFraction` against `s * 10^p + g`,
   in parallel over the cores. That is under `10^9` evaluations for `p <= 7`, `6 * 10^9` at
   `p = 8` and `6 * 10^10` at `p = 9`, the last being every nanosecond of a minute itself.
   **The sweep confirms; it is not the proof.** The kernel's side is `s * 10^p + g` built from the
   proven divisors of 0.11 (the six new ones and `10^9`), so the proof file carries its
   exactness, and `s` only indexes 60 values. Spark's side is `x = s + g / 10^p`, a decimal of at
   most 11 significant digits, carried through two `Double` roundings (the quotient and the sum),
   each within half an ulp of a value below 64, so the double is within `2^-46` of `x`; Java's
   shortest `Double.toString` lies within half an ulp of the double; and rounding `HALF_UP` to `p
   <= 9` digits moves a value less than half of `10^-9` away from `x` back to `x` **[I, stated
   here and not proven in a solver]**. The sweep checks that argument on every pair. Where it
   runs: `p <= 7` in the suite's own JVM only if its measured time fits the suite's budget with a
   margin, since `SparkFunSuite` fails a test body past 20 minutes; `p = 8` and `p = 9` in the
   nightly, priced against the sweep budget of the milestone plan's 10.3 item 5 (row 330's). This
   plan does not claim the nightly runs such sweeps today: whether `dev/varka_nightly.sh`'s opt-in
   filter reaches a JUnit suite is being checked separately.
5. **The proof file.** The six divisors of 0.11 rendered into `long_divide.smt2` by
   `VarkaProofFiles`, both forms, under Z3; `VarkaDivisorProofSuite` passes.
6. **The fuzzers.** `VarkaIrGrammar` draws `WidenDecimal` as a root over a long subtree whose range
   is within 18 digits, in a sequence of its own seed, so no existing hash moves.
7. **The generated files** in `ADDING_A_TYPE.md`'s order: `coverage.json`, `docs/sql-varka.md`,
   `emitted_bytes.json` (new rows only; the `option_arms` digests move with the new option),
   `width_audit.json` (the 128-bit fallbacks recorded) and `census.tsv`.

**Test time.** The store table is 347's slot table's size, in a JVM of its own, which the
calibration table's VARKA-484 row puts at 16 to 17 s for a suite of that kind: predicted under 30
s. Section 3 item 4 is unmeasured; priced from VARKA-335's 25 ns of CPU per interpreted
evaluation, and a `Double`-to-`BigDecimal` evaluation taken as ten times that **[I]**, `p <= 7`
is under a minute on the laptop's cores and `p = 9` of the order of half an hour. Both are timed
before the split is fixed and joined to the calibration table.

## 4. The baseline, and predictions

**The baselines first, each its own pull request on the unchanged code.**

- Spark's row path: row 377's `VarkaDecimalBaselineBenchmark` gains `time_to_seconds(t)` and
  `extract(SECOND FROM t)` over a `TIME(6)` column as projections, run on the EPYC 7763 at 256
  and 128 bits, its own pull request before any of this row's code, so the baseline is committed
  before the change.
- The store's own class, `VarkaDecimalStoreBenchmark` (catalyst, Java, on `VarkaBenchmarkBase`,
  kernels emitted from the IR as `VarkaLaneConversionBenchmark`'s are), over `VarkaTimeBenchmark`'s
  rungs (in L2, in L3, past L3), with four arms per shape: the `BIGINT` store of the same value
  (the control, eight bytes a row written); that store followed by the evaluator's scalar loop
  writing `u` and `u >> 63` into a `DecimalVector`, the form 2.93 names as the baseline to beat;
  and the store under each arm of `decimalStore`. Its shapes: `time_to_seconds(t)`, `extract(SECOND
  FROM t)` at `TIME(6)`, and the store alone over a long column. Its first commit carries the
  first two arms only, on the unchanged code; the store's arms land with 157b.

Results from `benchmark.yml` on the EPYC 7763 at 256 and 128 bits, and on the laptop at 512, 256
and 128 (its 512-bit numbers double-pumped and labelled so), each arm driven to level-4 code first
with its tier state beside its number (`VARKA-347.md` 0, item 13).

Predictions, registered before any of it is built:

1. **Against the scalar widening** at 256 bits: the store faster by 1.5 to 3 times in L2 and 1.2 to
   1.6 times past L3, where the scalar loop's extra pass over 24 bytes a row binds.
2. **Against the `BIGINT` control:** 1.2 to 2 times its time in L2, and about 1.5 times past L3,
   reading 8 bytes and writing 16 a row against writing 8.
3. **The arms:** on the laptop at 256 bits `SELECT_FROM` ahead of `REARRANGE` by 10 to 30% in L2;
   on the EPYC 7763 a tie or `REARRANGE` ahead; past L3 a tie on both.
4. **At 128 bits on the EPYC 7763:** both arms the allocating fallback, and the scalar widening 3
   to 10 times faster than the store, so the store loses there and section 2.2's recorded refusal
   applies, unless the whole kernel at two long lanes already loses to its scalar form by as much.
   On the laptop at 128 bits `SELECT_FROM` beats `REARRANGE` by 3 times or more.
5. **Against Spark's row path** at 256 bits: the kernel 10 to 40 times faster than Spark's
   per-row `Double` and `BigDecimal` work; end to end, a row consumer reading the output through
   `SmallDecimalAccessor` still builds a `BigDecimal` a value, so the query is 2 to 5 times faster
   **[I]**.
6. **No existing shape's bytes move.**

## 5. Order, files and size

0. **After 347's reader merges.** Nothing here stacks on another row's unmerged branch: 157's first
   pull request is cut from master once row 347's reader (`VarkaLaneArrow`'s decimal entry,
   `decimalInputs`, the shuffles' prologue) has merged, and line numbers are re-read then.
1. **The baselines** (4): the row-path addition to `VarkaDecimalBaselineBenchmark` as its own pull
   request, then the store class's first two arms.
2. **157a, the store:** the node, the helper and both arms, the segment, the allocator, the
   warm-up and budget rows, the store table and the guard page, the fuzz sequence, census cells for
   the zip (both forms, from and to segments) in `VarkaOperatorCensusCatalog` with a
   `varka-width-audit.yml` dispatch for the runner columns. Bytes unchanged for every existing
   shape.
3. **157b, the producers:** 2.3's lowerings and proof rows, the harness and coverage rows, the
   exhaustive check, the store's arms in the benchmark and the verdict per width.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java`, `VarkaCanonicalIr` (tests) | `WidenDecimal`, rendering, parse arm |
| `varka/Analysis.java`, `varka/Slots.java` | `decimalOutputs`, the zip shuffles or index vectors |
| `varka/VarkaBodyEmitter.java`, `varka/VarkaVectorWalk.java` | the segment, the interleave |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalStore` |
| `VarkaTimeCompiler.java` | 2.3's two lowerings |
| `VarkaLaneArrow.java` (sql/core) | the decimal allocator |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weights, features, reach |
| `sql/varka/proofs/long_divide.smt2`, `VarkaProofFiles.java` | six divisors |
| tests | store table, guard page, harness, coverage rows, exhaustive check, fuzz sequence |
| benchmark | the baseline addition, `VarkaDecimalStoreBenchmark`, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `census.tsv`, `operator_census.json` |

**Size: medium by this plan's count, against the table's small** (question 2). The baselines about
half a day; 157a about two days; 157b about two, the exhaustive check's run time included. It rests
on row 347's reader having merged (step 0), as 4.1's wave orders.

## 6. What it unlocks in the census

`time_to_seconds` over `time` names rows 351 and 157, its only form `DECIMAL(14, 6)`; `second`'s
note says "the fractional form is a decimal (row 157)"; `extract`, `date_part` and `datepart`
over `time` wait on the decimal `SECOND`. So the row moves the fused pair count by 1 to 4:
`time_to_seconds` on `time`, and `extract`, `date_part` and `datepart` on `time` if their other
fields over `time` already fuse **[I: the census script decides on regeneration]**. It moves
nothing that needs another type's lane. `extract_dt_seconds` (`DECIMAL(8, 6)`, rows 103 and 157)
gets its store here and its lowering in 103, and is the first producer of negative values end to
end. Every later decimal output (348's choices and negation, 349's arithmetic, 350's casts to a
decimal, 351's functions) stores through this row's node.

## 7. Out of this row

The decimal load (347), the operators and casts (348 to 350), 351's functions, the interval seconds
(103), a precision above 18 and its two-word store (359), a host with NEON and no SVE (not in the
census), a big-endian host. Declines that stay, each naming its row: a decimal output of precision
above 18 (359), a decimal output in an int-driven kernel, and at two long lanes on x86 a store the
measurement shows losing, as a recorded refusal with a scope item.

**The shape this row and row 359 do not share.** This row's decimal output is one root, a
`WidenDecimal` whose store derives the high word; `VARKA-359.md` 2.4 plans an output of two roots,
the low and the high word, bound to one `DecimalVector`, and says 157's store is "the same
interleave with `hi = lo >> 63`". The two meet in one emitter helper that takes the two word
vectors from the stack, which this row builds and 359's binding calls. When 359a is built, its
plan's 2.4 is corrected to name this node and the helper, so the two rows do not diverge silently.

## 8. Questions for the owner

1. **The two producers, with row 351.** Row 351's text lists `time_to_seconds` and `second` with its
   fraction among its functions; this row's done-when (`m5/PLAN.md` 2.93) makes the same two the
   store's proof, and the compiler's decline names this row. (a) This row carries both, and
   351's text drops them when this row's pull request lands. (b) This row lands the store with
   IR-level tests only, and 351 carries both. *Recommended: (a)*: a store alone has no SQL form
   and so no coverage row, the case 347's question 2 answered (a) for the load; both producers
   need nothing but the long lane and constant divisions, while 351's other functions need 348 to
   350 and 360.
2. **The row's size.** The table says small; this plan counts medium, in three pull requests (5):
   the two producers, six proof rows, an option with two arms, a benchmark class and a byte-exact
   suite. (a) The size cell becomes medium. (b) It stays small and the producers move out under
   question 1 (b). *Recommended: (a)*, if question 1 is answered (a).

## 9. Done when

`m5/PLAN.md` 2.93's list holds, with the corrections above: a decimal output of precision 18 or less
is written into sixteen-byte slots by `WidenDecimal` under both arms of `decimalStore`, the losing
one kept, the prologue allocating nothing (its census cells passing), the store
table byte-exact against Arrow's writer at 2, 4 and 8 lanes in both bodies, and the guard page
passing; the destination is a `DecimalVector` with Spark's field; under question 1's answer,
`time_to_seconds(t)` and `extract(SECOND FROM t)` fuse with coverage rows and agree with the row
engine over every second of the day at `TIME(0)`, `(3)`, `(6)` and `(9)` under both consumers, and
the fraction agrees with Spark's double round trip over every `(s, g)` of each precision, `p <=
7` where section 3 item 4 places it and `p >= 8` committed as a nightly sweep with its time; the six
divisors are proven; the store's measurement against the scalar widening
is committed per width with its predictions scored, the store beating it on the ladder or this
file saying by how much it does not, and the verdict naming the default arm per host; the bytes
oracle is unchanged for every existing shape; and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)
