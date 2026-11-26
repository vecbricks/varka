# Reading for milestone 7, continued: what Polars adds

Read on 9 October 2026 at the owner's request - "deeply explore Polars ... any ideas or approaches
could we borrow to Varka from it?" - as the thirteenth engine's companion to `m7/READING.md`.
Polars had not been read: a search of `SKILLS.md`, the lessons under `sql/varka/skills/`, the plans
and `VISION.md` found no mention of it. Nine read-only surveys, each over one slice of the
checkout with the same rubric (what it does, the evidence, where it would land in Varka, whether
the record already has it), and a check of the record for novelty before any item below was
called new.

Paths are relative to `/home/max/proj/polars` at commit `1b720ce026` (9 October 2026, crates
0.55.1, MIT). Every claim carries how well it was checked:

* **[V]** the author of this note re-read the code (or Spark's) for it;
* **[A]** a survey read it and the author did not re-check it;
* **[E]** a survey ran an experiment (jshell on JDK 25 or Python, nothing committed) and the
  author did not repeat it;
* **[I]** inferred and not run.

The checkout has no `benches/` directory and no benchmark result, so **every speed statement
below is a source comment or a commit title, never a measurement**; none of them is a number
this project can quote. Polars's `AI_POLICY.md` forbids agents from interacting with its
repository; the surveys read a local clone and posted nothing.

| system | checkout under `/home/max/proj` | commit | date |
| :--- | :--- | :--- | :--- |
| Polars | `polars` | 1b720ce026 | 2026-10-09 |

**What Polars is, against Varka.** An in-memory engine over its own fork of arrow2 (`polars-arrow`),
a lazy optimizer and two executors: the in-memory one, and a morsel-driven streaming engine that
became the default of `collect` in June 2026. Its kernels are Rust left to LLVM's auto-vectoriser,
with `std::simd` in the comparisons and the min/max, and one runtime-dispatched AVX-512 path (the
filter). It wraps on integer overflow, floors integer division, types literals by fit and has no
ANSI mode. So it is a weaker reference than the engines of `m7/READING.md` for what Varka does now,
expression evaluation under Spark's contract, and a stronger one for three things Varka has not had
to read yet: the Parquet decode path, aggregation and string layouts, and some habits of testing.

## 1. Kernels and the CPU strategy (`polars-compute`)

**Float comparison as a total order** [V]. `comparisons/simd.rs:176-252`. Equality is
`(l != l & r != r) | l == r`; `<` is `!(l != l | l >= r)`; `<=` is `r != r | l <= r`; `>` and `>=`
swap the operands (`comparisons/mod.rs:62-67`); `!=` is the negation of equality. Spark's
`genEqual` is the same form (`CodeGenerator.scala:1062`) and `SQLOrderingUtil.compareDoubles` gives
NaN equal to NaN, NaN above every number, and `-0.0` equal to `0.0` [A]. Polars only splats a
literal operand and leaves hoisting to LLVM (`:196-204`).

*For Varka.* The semantic hazard is recorded (`m10/SCOPE.md` item 82: IEEE vector compares answer
false on NaN where Spark orders it highest, and a NOT compiled as a swap of the result mask is
consistently wrong); the branch-free formulas are new, and a constant operand lets the emitter go
further than Polars. For a constant `c` that is not NaN: `=`, `<` and `<=` are the bare IEEE
compares, `>` is `!(x <= c)`, `>=` is `!(x < c)` and `!=` is `!(x == c)`, one compare each and no
reliance on the vector `NE`. For `c` = NaN: `=` is `isNaN(x)`, `<` is `!isNaN(x)`, `<=` is true,
`>` is false, `>=` is `isNaN(x)`. Under a total order NOT is plain mask negation, which removes the
swap hazard of item 82. (The derivation is the author's, checked by hand for each cell; the test
that would hold it is a table over NaN, both zeros, both infinities, the extreme finite values and
one ordinary pair, against `compareDoubles`.)

**Min and max under NaN** [A]. `min_max/simd.rs`. The ignore-NaN forms start from a NaN identity
with IEEE minNum (`:244-267`); the propagate-NaN forms use an infinity identity and
`(a < b | a != a).select(a, b)` (`:269-307`); null lanes are replaced by the identity before the
fold, and the ragged tail is identity-padded instead of run by a scalar loop. Spark's `Max` is
`greatest(max, child)` and `Min` is `least(min, child)` (`Max.scala:63`, `Min.scala:63`): max
propagates NaN, min ignores it unless every value is NaN. The Vector API's MIN and MAX probably
follow `Math.min` and `Math.max`, where NaN propagates and `-0.0 < 0.0` [I - the JDK source archive
was a dangling link, so not read]: right for `max`, wrong for `min`. Use "take b if a is NaN or
b < a" for `min`; a tie between the two zeros is order-dependent in Spark and Polars ignores it
(`polars-utils/src/min_max.rs:3-9`), so fuzz it. Item 35 and `least`/`greatest` over doubles.

**Compaction for the lanes that are not four bytes wide** [V for the structure]. `filter/scalar.rs:85-138`
handles each 64-bit mask word in one of four ways: a zero word is skipped; an all-ones word is
copied whole; up to 16 set bits are walked with trailing-zero count and clear-lowest-bit, unrolled
twice; a denser word stores every lane unconditionally and advances the output by the mask bit
(`written += (m >> i) & 1`, sixteen lanes by four). The output is sized popcount plus one slack
slot (`primitive.rs:75`). Without AVX-512 this is the whole filter: there is no AVX2 kernel
(`primitive.rs:31-65`). One comment is the evidence - "Rust generated significantly better code if
we write the below loop with v as a pointer" (`:32`) - and the 16 is a bare constant [A].

*For Varka.* Every column that is not four bytes wide goes through `compactFixed`, a data-dependent
`isSet` branch and Arrow's `copyFromSafe` per row (`VarkaFilterEvaluator.scala:372,253`) [A]. The
dense loop would replace it for one, two, eight and sixteen-byte lanes, and it is also the scalar
control for item 19.2 if the emulated `compress` loses on AVX2. A data-dependent store index
keeps its bounds checks, so it should reuse the slack-buffer rule of `compactInts` [I].

**Output validity from a register** [A]. `filter/boolean.rs:160-226` walks 56-bit windows:
`word |= pext(v, m) << bits_in_word`, one unaligned eight-byte store, advance whole bytes. There
is no read-modify-write and no pre-zeroing (the buffer is `8 * (words + 1)` bytes); `bits_in_word`
stays under eight, so 56 + 7 cannot overflow. Values and validity are filtered in separate passes
(a note at `:235` says so). *For Varka*, `orBitsAt` does a two-to-three-byte read-modify-write per lane
group (`SelectionVectorOps.java:217-227`); a window of 32 or 56 bits also cuts the `Long.compress`
calls, which eases item 22's PEXT worry, but needs eight spare validity bytes where
`allocateNew(count + lanes)` guarantees about two. Polars's CLMUL polyfill for `pext`
(`boolean.rs:6-52`) does not transfer: the JDK has no CLMUL.

**The tail as a padded copy** [A]. See section 2.

**Division by a constant** [A]. `arithmetic/signed.rs:142-161`: a zero divisor gives null, `-1`
becomes `wrapping_neg` (which dodges `MIN / -1`), `1` is the identity, and otherwise
`x.unsigned_abs() / StrengthReducedU*::new(|d|)` with the sign fixed afterwards; `|d| >= 2`
guarantees the quotient fits (`:114-116`). The loop is scalar, not SIMD, and the `strength_reduce`
crate's source is not in the checkout (its algorithm is a Lemire-style 64-bit multiplier, from
memory). *For Varka*: VARKA-104 section 3.4 declines bigint `div`, `mod` and `pmod` without a bound;
a scalar lane loop through `Math.unsignedMultiplyHigh` could serve an unbounded dividend instead
[I]. Polars's `//` and `%` floor (`polars-utils/src/floor_divmod.rs`) where Spark truncates, and
Polars has no truncated-remainder kernel, so only its truncated quotient fits; its `%` equals
Spark's `pmod` only for a positive divisor (section 7).

**A float sum with fixed logical lanes** [A]. `float_sum.rs`: sixteen logical lanes (`:13`),
128-element leaf blocks accumulated in order (`:14, :80-88`), a fixed halving fold then
`(v0 + v2) + (v1 + v3)` (`:45-64`), blocks combined by recursion (`:194-216`); the masked form
selects `0.0` under invalid lanes (`:97-101`). The lane count is part of the meaning, not the
hardware, so AVX2, AVX-512 and NEON should give the same bits [I]; the only reason the file gives
is "good shuffle instructions". The grouped paths differ: the in-memory engine uses Kahan
(`polars-core/.../aggregations/mod.rs:882`) and the streaming one a plain `+=`
(`polars-expr/src/reduce/sum.rs:94-97`), two engines and two policies. *For Varka*: this
reproduces nothing of Spark's sequential `coalesce(sum, 0.0) + x` (`Sum.scala:136-139`), and
`m5/PLAN.md` item 7's four accumulators have the same gap. Take the host independence; require an
ulp-tolerant oracle for double sums; start accumulators at `+0.0`, because Spark's zero is `+0.0`
and Polars's `-0.0` identity (`polars-utils/src/algebraic_ops.rs:26`) differs on `sum(-0.0)`.

**The CPU strategy** [A]. Each wheel has a static baseline: the main one `+avx2,+bmi2,+fma,...`
with `tune-cpu=skylake`, the compat one SSE4.2 and POPCNT only (`.github/workflows/release-python.yml:28-34`,
`Makefile:59-64`); the user picks at install (`polars[rtcompat]`) and a CPUID check in Python only
warns. AVX-512 is the one runtime-dispatched path, behind `avx512f`, a `POLARS_DISABLE_AVX512`
kill switch and VBMI2 for the eight and sixteen-bit lanes, cached in a `OnceLock`
(`polars-utils/src/cpuid.rs:42-57`, `primitive.rs:33,42`). Fast `pext` is a **blacklist**: AMD and
Hygon family 0x15 to 0x18 is slow and everything else is assumed fast "as future parts are fine"
(`cpuid.rs:21-24`). That is a finer data point than Arrow's Intel-only rule (item 22): Zen 3 and
later are trusted. The wheel split does not transfer; the JIT targets the host.

**Casts and nulls** [A]. Kernels compute over every lane and AND the validity afterwards
(`arity.rs:44`, `comparisons/mod.rs:7`), which `VARKA-70.md` 2.2 and Gandiva already do. A zero
divisor computes 0 with a `divisor != 0` mask ANDed into the validity (`signed.rs:47-58`); `+`, `-`
and `*` wrap silently and there is no raising mode; only `checked_mul_scalar` nulls on overflow
(`arithmetic/mod.rs:157`). A strict cast is a lenient cast followed by a comparison of the cached
null counts, and only on a mismatch does it XOR the validities to name the offenders
(`polars-core/.../cast.rs:62-63`, `utils/series.rs:67-126`) - raise-only-on-valid-lanes, as Varka's
decline of the batch gives without row indices (item 18). Rust's float-to-int cast saturates with
NaN to 0, which equals Java's `d2i` and `d2l` for int and long targets, but Spark takes byte and
short through `d2i` and wraps (`Cast.scala:1331-1333`), where Rust saturates to the narrow range.

**Read and not taken.** The register-compress-then-full-store of the AVX-512 filter
(`avx512.rs:56-60`) is already in `SelectionVectorOps.java:139-140` (items 19, 22, 23, 29). String
to number goes through per-row `atoi_simd` and `fast-float2` (`cast/binary_to.rs:19-65`) and parses
strictly, where Spark accepts `" 1.5"` as 1. Decimal from string rounds half-even
(`decimal.rs:1146`), where Spark uses HALF_UP (`Decimal.scala:353`). Scalar float division by the
reciprocal (`float.rs:78-115`) is the d = 49 failure that VARKA-88 proved. A float add of `+0.0` is
elided (`float.rs:45-48`), but `-0.0 + 0.0` is `+0.0`: fold only `x + (-0.0)` and `x - (+0.0)`.
The sorted-flag binary-search compares need a flag Spark batches do not carry; the `BinaryView`
literal compares need views Arrow-java does not write; `if_then_else` (`simd.rs:76`: "Auto-generated
SIMD was slower on ARM") is unmeasured and Varka already predicates.

## 2. Arrays, bitmaps and buffers (`polars-arrow`, `polars-buffer`)

**The tail as a zero-padded copy through the same body** [A]. `comparisons/simd.rs:11-54`: full
chunks write one mask word each; a remainder is copied into a zeroed stack array and the same
closure runs once on it, the whole word stored into an output sized in whole words. Reductions pad
with the operation's identity (`min_max/simd.rs:53-54`). There is no scalar tail and no masked
operation, and the padded-lane mask read in `bitmap/bitmask.rs:256` is saved only by the identity
fill, so Varka would have to clear padded validity bits itself.

*For Varka.* `VarkaBodyEmitter.java:605-636` re-lowers every group masked, at about 400 bytes an
output (`emitter-and-ir.md:485`), with an `epilogueMasked` of 66 to 80 KB that is past the 65,535 cap
and open as row 87 (`testing-and-debugging.md:432,471`), and no back edge, so it runs interpreted
for thousands of batches (`the-jit.md:842`) [A]. It is a no-op when `length % lanes == 0`, but a
compaction's output has an arbitrary length, so every batch above a filter pays it [I]. The
alternative: the driver copies the `rem` rows (data and validity bits) of each input into one-group
scratch, calls the existing loop methods with `length = lanes`, and copies `rem` lanes and the
validity out. It asks of the nodes only what a masked lane already asks - that they are total on 0
- and it deletes the epilogue methods and moves `emitted_bytes.json`. Unmeasured.

**Arrow-java zero-fills what the kernels overwrite** [A, from the arrow-vector 19.0.0 sources in the
coursier cache]. `allocateNew` runs `zeroVector()`, which zeroes both buffers over `capacity()`
(`BaseFixedWidthVector.java:204-217,329-333`), and the allocator rounds to a power of two: 4,096 int
rows (16,896 bytes) zero 32 KiB, 10,000 rows zero 64 KiB. Varka allocates per output per batch
(`VarkaKernelEvaluator.scala:333`, `VarkaFilterEvaluator.scala:312,358`); the kernels write every
data lane and the driver every validity word. Polars fills uninitialised (`arity.rs:65-71`). The
way to the same here is `allocator.buffer(...)` and `loadFieldBuffers(new ArrowFieldNode(len, n),
buffers)`, the IPC path (`BitVectorHelper.java:300-340`). No number exists; one store pass over 32
to 64 KiB per output is the order of the kernel itself at 4,096 rows [I].

**Register-carried bitmap writes and word-wise counts** [A]. `BitmapBuilder`
(`bitmap/builder.rs:12-18,160-176`) holds a `u64`: a push is a capacity check and one OR with a
flush every 64 bits; a word append ORs `word << fill`, stores a full word and carries
`word >> (64 - fill)`; the popcount accumulates per flush, so `freeze` and `into_opt_validity`
(`:462-491`) give an exact count and drop an all-valid bitmap; `count_zeros` is one masked load or
an aligned bulk (`bitmap/utils/mod.rs:66-88`). *For Varka*, `orBitsAt` carries nothing in a
register and `VarkaSelectionBitmap.countSet` (`:85`) is a byte loop, 1,250 iterations per 10,000
rows against 156 words, where `orInto` and `andInto` beside it already go eight bytes at a time.
Both sit under this project's A/B bar (about 1% of a filter's read cost) [I].

**Validate offsets once per buffer** [A]. `array/specification.rs:50-105`: only the last offset is
bounds-checked; `values[first..last]` is validated as one string (`is_ascii()`, else one `simdutf8`
pass); each element is then valid iff its first byte is not a continuation byte, an OR-reduce with
no early exit (`:101`). View arrays skip buffers proven ASCII (`binview/view.rs:582-613`). Spark's
`UTF8String` need not be well formed and Arrow-java never validates it (section 6), so character-level
operations make validity a batch precondition that declines to the row engine.

**Nested layouts** [A]. `trim_lists_to_normalized_offsets`, `rebuild_list_shallow`
(`polars-compute/src/rebuild_list.rs:17-45`) and `propagate_nulls` return `Option` and copy only from
the first changed field; slicing a list keeps its whole child, and consumers read the live window
`[offsets[0], offsets[n])`. The caution: the proptest asserts `array == propagate_nulls(array)`
(`propagate_nulls.rs:281`) while struct equality ignores children under null parents
(`array/equal/struct_.rs`), and the skip arm at `:251` tests parent-valid within child-valid where
the reverse is needed (it looks inverted; not run). There is no nested design in the record yet
(`VISION.md:112`; item 31 defers struct-backed nanosecond timestamps): before lists or structs,
decide where parent validity is ANDed into children - the scan is the natural place, since
definition levels give both - and test the postcondition, not equality.

**Known, one line each.** The AND and OR of validity outside the loop with the all-null, none-null
and mixed shortcuts (`bitmap_ops.rs:216-240`; item 22). Reads never pass a slice: a `u64` load
shifted by at most seven leaves 57 valid bits (`bitmask.rs:272-289`), `AlignedBitmapSlice` is prefix,
aligned bulk and suffix (`aligned.rs:6-15,67-128`), and an imported bitmap is exactly
`bytes_for(offset + len)` (`ffi/array.rs:338`) - which is "Arrow promises no padding" in
`testing-and-debugging.md`. Compress overshoot with pad lanes and the zero-block skip
(`filter/primitive.rs:67-83`, `avx512.rs:19-22`; item 22). O(n) invariants only under
`debug_assert` (`binview/mod.rs:184-219`); this project's sanitizer matrix plays that role.

**Read and not taken.** The cached unset-bit count (`bitmap/immutable.rs:55-68,238-320,753-813`: a
lazy `u64::MAX` sentinel, inclusion-exclusion on slice and split, seeded by the FFI `null_count`,
the bitmap dropped when it is 0). Arrow-java recomputes `getNullCount` per call
(`BitVectorHelper.java:180`), about a thousandth of item 14's floor; cache nodes already carry
`nullCount` (`ArrowCachedBatchSerializer.scala:386`). One note for the Parquet reader: a page's null
count lets Polars skip definition-level decoding (`polars-parquet/.../utils/mod.rs:51`). Ownership
by `SharedStorage` and reuse of a uniquely owned input as output (`arity.rs:47-127`, about 61 callers;
`arity_assign.rs:55-58` found a fresh output roughly twice as fast as copy-then-assign): fusion
already removes Varka's intermediates and Spark's batches are shared. Different-offset bitmap
operations that realign on the fly (`chunk_iterator/merge.rs:15-35`): Varka's inputs carry no bit
offset. FFI that trusts offsets, UTF-8 and dictionary keys; `TrustedLen`; `rechunk`.

## 3. The optimizer and the expression engine (`polars-plan`, `polars-expr`)

Polars's passes in order: type check, simplification and coercion at conversion; common-subplan
elimination, slice and predicate pushdown, join passes and projection pushdown once each; a batch
run to a fixpoint (fused arithmetic, simple projection, rechunk delay, **filter constraints**,
boolean simplification, union flattening); then slice again, join build side and runtime filters,
`cluster_with_columns`, common-subexpression elimination, window extraction and ordering
simplification (`polars-plan/src/plans/optimizer/mod.rs`) [A]. Catalyst has the analogue of every
pass except filter constraints and `cluster_with_columns`; nothing here is a candidate for Varka,
which runs after Catalyst's optimizer.

**Scope a guard by the sibling conjuncts** [A; see item 89, point 11]. Polars's `FilterConstraint`
(`aexpr/filter_constraint.rs:119-137`) keeps for each column a lower and an upper bound with
inclusivity, an excluded set, an allowed and a disallowed set for `IN`, and a null requirement; it
collects, propagates across `col == col`, resolves `!=` and `IN` against the final bounds and emits
(`:6-14, 470-547`), so `a >= 3 AND a != 3` becomes `a > 3` and `a IS NULL AND a > 5` is empty
(`:340-390`, `:349`). The idea for Varka is the other direction: guards are scoped only by the arms
of an `IfElse` (`varka/Analysis.java:409-436`, `ArmStep(IfElse, ...)` at `:1074`), so a guard in
the right operand of an `AND` or `OR` condemns lanes the left operand already rejected, e.g.
`d >= X AND year(date_add(d, off)) = 2021`. The cost is a batch declined to the row engine, not a
wrong answer. The cheapest fix would extend `emitArmContext` (`VarkaVectorWalk.java:697`) with `And`
and `Or` steps: the right of an `AND` is scoped by "not known false" on the left, the right of an
`OR` by "not known true". Reachability is the open question; item 89, point 11, records the probe.

**Orthogonal shape bits** [A]. `FunctionFlags` (`plans/options.rs:71-136`) separates ROW_SEPARABLE
from LENGTH_PRESERVING and uses all four cells (`DropNulls` is row-separable only; `Shift`, `Cum*`
and `Rank` are length-preserving only, `function_expr/mod.rs:1223-1300`), derives two lattices
bottom-up (`ExprProjectionHeight`, `ExprPushdownGroup`) and keeps fallibility and determinism as
hand-written matches. One "elementwise" bit served pushdown and streaming, so streaming grew a
deny-list (`lower_expr.rs:92-116`). For item 51: keep the null contract and the error capability
as separate properties and check each flag per consumer against `VarkaReferenceEvaluator`.

**NaN rules for a double range** [A]. With NaN excluded from min and max, a stats predicate may
conclude from `<` and `<=`, from `>` only against a NaN literal, from `==` only for a non-NaN
literal, and never from `>=` or `!=` (`aexpr/predicates/skip_batches.rs:175-197, 229-241`); Spark's
order is the same (`docs/sql-ref-datatypes.md:321-330`). When item 35 gives a double `Range`, it
needs a may-be-NaN bit and this table.

**"Fused" is loop fusion, not FMA** [A]. `optimizer/fused.rs:67-138` turns `a * b + c`, `a * b - c`
and `c - a * b` into one three-input call, evaluated as `*a * *b + *c` with the validities ANDed
(`polars-ops/src/series/ops/fused.rs:8-28`); `mul_add` appears nowhere in `crates/`, so it rounds
twice, as unfused. All three operands are first cast to their common supertype
(`:117-135`), which widens the product (`f32 * f32 + f64`) and changes results. For Varka, whose
loops already avoid the intermediate: `FloatVector.fma` rounds once and breaks bit-equality with
Spark, so never contract a multiply and an add outside an opt-in relaxed mode.

**Common-subexpression elimination** [A]. Value numbering on (shallow node, child ids) with
non-determinism carried per class (`aexpr/canonical.rs:110-198`); sharing becomes `__POLARS_CSER_*`
columns in a `with_columns` below the select (`cse/csee.rs:61-63, 456-590`). A `when/then` is
traversed in select context (`:79`), so a subexpression repeated inside one arm is computed on all
rows. Varka's in-loop DAG sharing and fragment sharing are ahead.

**The coercion tables must not be copied** [A]. Int32 or Int64 plus Float32 gives Float64
(`supertype.rs:230,251`) where Spark gives Float (`UpCastRule.scala:25`); a string against a number
in a comparison is an error (`type_coercion/binary.rs:168-189`) where Spark casts the string; an
untyped integer literal takes the smallest fitting dtype, so `u8 + 1` stays `u8`
(`supertype.rs:535-557, 660-699`) where Spark's `1` is an Int; Boolean joins the numerics; `//` and
`%` floor, integer division by zero is null and integers always wrap
(`polars-compute/src/arithmetic/signed.rs`). Derive coercion from `UpCastRule` and `TypeCoercion`.

**Verification** [A]. The only global check compares final column names, in debug builds
(`optimizer/mod.rs:137-139, 366-385`); the type check only requires filter predicates to be
Boolean. Tests compare an optimized run with one run with a flag off
(`py-polars/tests/unit/lazyframe/test_optimizations.py:332-374`), about ten run with the
pushdown-maintain-errors switch on and off, and `POLARS_OUTPUT_SKIP_BATCH_PRED=1` prints a predicate
beside its derived skip predicate. `explain` shows outcomes, never declined rewrites. Catalyst's
`RuleExecutor` is ahead (`RuleExecutor.scala:222`).

**Read and not taken.** A `when/then` computes both arms unless the mask is uniform and counts a
null condition as false (`expressions/ternary.rs:121-201`): known, and `emitArmContext` is finer.
Group-context evaluation (a state matrix with per-group fallback) has no Spark counterpart.
`pow` shortcuts are exact only for exponents 0, 1 and 2. Polars's skip-batch predicate rewrites a
filter into a predicate over (min, max, null count, length) evaluated by the ordinary engine
(`skip_batches.rs:161-838`): VARKA-84 is stronger on arithmetic and Polars on predicate structure
(null counts, `IN` up to 100 values), and it is scan-side where Spark prunes in `ParquetFilters`.

## 4. The streaming engine and its fallback (`polars-stream`, `polars-ooc`, `polars-observer`)

**The whole suite again at a tiny batch size** [V]. Polars's CI reruns its whole Python suite with
`POLARS_IDEAL_MORSEL_SIZE=4` (`.github/workflows/test-python.yml:49-66`, one matrix entry on
Ubuntu; `test-coverage.yml:181-184`), slow tests excluded because they are "prohibitively slow
with a tiny morsel size". Some files force 7, tests size their data from the setting (`3 *
morsel_size`), and other capacities shrink with it (a sort bucket of 1,024 bytes, 8 sample rows).
Eligibility is a stated law, ROW_SEPARABLE: `f(concat(a, b)) = concat(f(a), f(b))`
(`plans/options.rs:109-113`), so the tiny run tests that classification, and a deny-list covers
flags known to be wrong (`lower_expr.rs:92`). [A for the last three details.]

*For Varka.* Batch size is swept for speed only (item 14, `m10/SCOPE.md:2180`), `VARKA-262.md:254`
says the fuzzer never varies it, and `VarkaDifferentialSuite` sets 32 in two tests. Batch size is a
`SQLConf` (`spark.sql.inMemoryColumnarStorage.batchSize`, `spark.sql.parquet.columnarReaderBatchSize`),
not a `VarkaEmitOption`, so `dev/varka_matrix.sh` needs a new axis; one size below the lane count in
use (a batch that is all epilogue) and an odd one just above are the natural pair. Tests that assert
batch counts (the warm-up suite) go in `skips.tsv`. Row 301.

**Pin the fallback so it cannot plan back into the declining engine** [A]. Polars's group-by
fallback built an in-memory plan with the streaming hook still attached; the in-memory planner
handed the group-by back to streaming, which declined again: infinite recursion and a SIGSEGV. The
fix passes the hook `None` (`lower_group_by.rs:90`) and delegates only `if
build_streaming_executor.is_some()` (`polars-mem-engine/src/planner/lp.rs:662`), with a regression
test (commit `23b060d15c`, `test_streaming_group_by_nested_agg_fallback`). Two other fallbacks still
pass `Some(...)` (`lower_ir.rs:1738, 2082`) and were not established to be safe. *For Varka*, the
decline path is `UnsafeProjection.create(...)` (`VarkaKernelEvaluator.scala:189`), Janino through
Catalyst, and no generator hook is wired today (`CodeGenerator.scala:2390`: routing was retired).
Once Varka is the generator, that path and the ghost Janino path must be pinned to a backend that
cannot consult Varka's hook; test it with a decline-everything run that asserts termination and
Spark's answer [I].

**A min-cut for the spill point** [A]. `split_pre_post_select_minsize_elementwise`
(`physical_plan/split_select.rs:288-394`) cuts a select into a materialised pre-select and a cheap
elementwise post-select so that a group-by's spilled payload stays narrow: two booleans per
canonical DAG node, edges costing the dtype's bits per row, ties preferring fewer computed values,
and Dinic's algorithm reading the cut. *For Varka*, item 75's "cut where the live values are
fewest" has an algorithm, but the objective differs: Polars minimises intermediate bytes, sets no
limit on either side and counts recomputation as free, where item 75 needs both sides under
`GROUP_BUDGET` and duplicated calendar prefixes are not free. Adapt with input columns costing 0
(re-read the Arrow input), an output another group already writes as a free cut node, guards and
the shared chrono prefix as unsplittable, and recursion on an over-budget side; for an unshared
tree a budgeted tree DP is exact and simpler, and the flow form pays only with DAG sharing.

**Expression-level fallback, side by side** [A]. A non-lowerable subtree becomes `Column(PHYS_n)`
and its residual elementwise expression runs in `Select`; all such subtrees at one level share one
`InMemoryMap` (`lower_expr.rs:2810`). It is decided at plan time only; it materialises the whole
input through a sink, runs the in-memory oracle once, then re-sources (`nodes/in_memory_map.rs`,
`in_memory_sink.rs:72`); a fallback result may flow to a streamed parent (`lower_expr.rs:2819-2838`)
but a streamed column does not flow into the fallback - the original subtree re-runs on
pre-selected leaf columns (`:406-485`). It reports by a colour in the physical plan's DOT output
(`fmt.rs:19-45`) and nothing else: no reason, no warning, no counter; about 41 tests inspect that
DOT, a few assert no fallback node, and there is no global fail-on-fallback switch (the GPU engine's
`raise_on_fail` is the only one). Varka's per-output, per-batch ghost fallback with reasons in
`EXPLAIN` (item 65) is finer; the words that transfer are "residual", "island" (sink, oracle,
source) and "late-materialised placeholder leaf" (`utils/late_materialized_df.rs`).

**Engine policy and CI reruns** [A]. `auto` resolves to `Config.set_engine_affinity` and then to
streaming, `POLARS_FORCE_STREAMING=1` overrides even an explicit engine
(`polars-lazy/src/frame/mod.rs:635-657`). The timeline: the suite on the new engine behind a
whole-query `todo!` fallback (July 2024), a CI job (October 2024), the default flipped (June 2026),
the markers and jobs deleted (September 2026, `c64b354b4f`), the old engine kept as the variant
`POLARS_ENGINE_AFFINITY=in-memory` (`test-python.yml:55-66, 143-149`). The deselect marker
`may_fail_auto_streaming` could not rot loudly - it deselected, so `xfail_strict` and
`--strict-markers` did not apply - and one cleanup removed 39 of them across 23 files with no other
change, so those tests had been hidden while passing. `sql/varka/matrix/skips.tsv`'s stale-line check
is stricter; the lesson for a default flip is to keep the old engine as a permanent CI variant.

**Per-node metrics** [A]. Custom metrics are declared as Sum, Max or Gauge at registration
(`metrics.rs:225`); each task has a 64-byte-aligned cell folded when the task drops; `Option<i64>`
separates unreported from 0; `POLARS_LOG_METRICS=1` prints polls, rows and morsels in and out sorted
by time. Four tests assert that attribution survives rewrites, including a fallback on a throwaway
arena (`py-polars/tests/unit/lazyframe/test_query_monitoring.py:490-590`). Take unset-versus-zero
for item 65's fallback counters, and the test shape for item 28's adaptive-execution worry: after
each re-plan, every executed Varka node still resolves to its decline reason.

**Read and not taken.** The morsel policy (100,000 rows ideal, `polars-config/src/lib.rs:36-37`; a
100,000-row `i64` column is 800 KB, so not cache-sized, and the constant has no benchmark or cache
argument anywhere in the history; channel depths of 4 are marked as untuned), sequence ids,
source tokens and wait groups, which Spark's pull iterator and scheduler cover. The async executor
(priorities, LIFO slot, NUMA stealing, `block_in_place` hand-off): Varka's warm-up is one daemon
thread and Spark owns the task threads. `polars-ooc` spills whole morsels between operators as Arrow
IPC files under a budget of the smaller of a cap and four fifths of cgroup-aware memory, choosing
victims with a Thompson-sampling bandit; item 75's spill is a scratch column inside one fused loop,
for code size, so it has no landing here beyond the min-cut. Its test trick - a budget of 0 to force
a spill on tiny data - is the one reusable idea. `polars-config` registers 39 options against 96 raw
`env::var("POLARS_...")` names; `VarkaEmitOptionSuite` is stricter. An autouse fixture compares
`collect_schema()` with every collected schema (`py-polars/tests/conftest.py:394-430`); section 9.

## 5. Parquet (`polars-parquet`, `polars-io`)

**Baseline.** What Spark's reader already does, read before calling anything new [A]: parquet-mr
1.18.1 has statistics, dictionary, bloom and column-index filters and Hadoop vectored I/O on by
default, and merges adjacent column chunks; the decode layer works in resumable 4096-row batches
with row ranges (`ParquetReadState`), keeps dictionary ids and decodes them lazily
(`VectorizedColumnReader.java:308`), copies PLAIN in bulk and unpacks bits with
`Packer.unpack8Values`; late materialization is already in this fork for Bloom conjuncts
(SPARK-59620, `ParquetStorageFilter.scala:240`). There is no Vector API in the reader.

**Definition levels become the Arrow validity bitmap by a copy** [V for the claim, A for the rest].
`polars-parquet/src/arrow/read/README.md:3-7`: "when the maximum repetition level is 0 and the
maximum definition level is 1, the RLE-encoded definition levels correspond exactly to Arrow's
bitmap and can be memcopied without further transformations". `deserialize/utils/mod.rs:908-948`
walks the level runs: an RLE run of ones bumps a counter, a width-1 bit-packed run is
`extend_from_slice`d into the bitmap, and it returns `None` if nothing was null; a V2 header with
`num_nulls == 0` skips even that (`:51`). PLAIN BOOLEAN is viewed as a bitmap (`boolean.rs:33-47`),
and optional values expand through 56-bit validity windows (`plain/mod.rs:218-301`): one unaligned
load and a shift per window, each slot written with the next value and the offset advanced by the
bit, so a null slot holds the next valid value rather than zero. *For Varka's reader*, Spark's
`readPackedBatch` (`VectorizedRleValuesReader.java:271-305`) scans per element and then `putNulls`,
and writes a byte per row; set Arrow's `nullCount` exactly so the kernels pick the dense body;
unaligned destination bit offsets need a shift-merge; Vector API `expand` is native only on AVX-512
(`m10/SCOPE.md` near 2252), so keep the scalar window loop as the baseline.

**Mask-driven decode** [A]. A `Filter::{Range, Mask, Predicate}` (`utils/filter.rs:15`) drives every
decoder: each kernel trims leading and trailing zeros and delegates to the unfiltered kernel when
the mask is all ones (`plain/mod.rs:304-378`); with nulls the value index is
`popcount(validity & ((1 << k) - 1))` (`utils/mod.rs:240-264`); a dictionary kernel handles an RLE
run with `mask.split_at(len)` and a popcount fill with no unpack, a zero mask window only adds to a
skip count, and whole 32-value packs are skipped, the rest unpacked into a ring and picked with
trailing-zero counts (`dictionary_encoded/required_masked_dense.rs:48-169`); a page with no
selected row is never decompressed (`utils/mod.rs:760-766`). The family has proptests against scalar
references (`plain/mod.rs:495-590`) and had two bugs in a year (#26411, #28547), with a regression
test at `optional.rs:226-273`. *For Varka*, Spark's `readNextGroup` unpacks the whole PACKED group
before `skipValues` can discard it (`VectorizedRleValuesReader.java:981-1020`); the fused
predicate's `VectorMask.toLong` is the mask these kernels want. Polars decodes a whole column chunk
at once (row groups of 262,144 rows, `polars-io/src/parquet/write/writer.rs:147`, against parquet-mr's
128 MiB blocks), so the kernels must sit inside Spark-style resumable state. DELTA and BYTE_STREAM_SPLIT
pages decode fully and are masked afterwards (`integer.rs:360-393`).

**The predicate on the dictionary, once** [A]. `dict_mask` is built once per column chunk
(`utils/mod.rs:547-553`); a dictionary-encoded page whose dictionary has no matching entry is skipped
before decompression (`:564-584`); otherwise `dictionary_encoded/predicate.rs` compares 32 unpacked
indices to the needle and pushes one 32-bit mask word (one match, `:33-79`), or bit-tests `dict_mask`
per index after one branchless range check per block (several matches, `:82-135`); RLE runs become
`extend_constant`. For `col = lit` the column is never decoded: a constant fill plus a scalar column
(`utils/mod.rs:665-673`). It is not used for floats, decimals, nested types, FLBA or pages with nulls.
The concept is known (items 3 and 19, and parquet-mr's whole-chunk dictionary filter); the kernel and
the per-page skip are new. The index-to-mask step is `IntVector.compare(EQ, needle).toLong()`, and the
answer is keyed per (row group, column) because dictionaries differ.

**Bit-unpack kernels** [A]. `parquet/encoding/bitpacked/unpack.rs:39-117` has one function per
width built with `seq_macro!` and const generics, unpacking 16, 32 or 64 values per call with a jump
table on the width and a zero-padded scratch for a short tail (`decode.rs:30-38`). The comments say
constants make it "around 4.5 to 5 times" faster, separate functions plus a jump table "around 2 to
2.5 times", and that "attempts have been done to introduce SIMD here, but those attempts have not
paid off in comparison to auto-vectorization" (`unpack.rs:23-36`) - comments only. `decode.rs:235-237`
records, on taxi data, that "the average self.length is ~52.8 and the average num_packs is ~2.2": per
run setup and tails matter more than steady-state throughput. "Autovectorisation wins" is an LLVM
result and item 19 says that bet does not pay on the JVM, so the port is an explicit Vector API
kernel per width with constant shift and shuffle vectors, emitted by the classfile emitter [I].
Parquet's layout is horizontal and least-significant-bit first, not SIMD-BP128's vertical layout
(item 7), so a lane's value can span two words.

**Stats skipping as a compiled expression** [A]. `aexpr/predicates/skip_batches.rs:161-838` rewrites
a filter into a boolean over `<col>_min`, `_max`, `_nc` and `len`, evaluated once and columnar for
all row groups by `io_sources/parquet/statistics.rs:143-205`; an unknown (null) result keeps the row
group. `== B` skips on `nc == len or min > B or max < B`; `!= B` skips on `nc == 0 and min == B
and max == B`; AND becomes OR and OR becomes AND. Floats exclude NaN from the stats (section 3's table).
Bounds are used only if the column order matches the dtype (`arrow/read/statistics/bounds.rs:118-163`)
and bounds flagged inexact, and NaN bounds, are dropped (`parquet/statistics/mod.rs:96-114`).
Polars never reads the page index (`parquet/read/page/reader.rs:56-60`), so Spark is ahead there.
A channel already exists for Varka (`FileFormat.supportsStorageFilter` and
`buildReaderWithStorageFilters`, `FileFormat.scala:204-244`), so a Varka reader could compile skip
predicates with its own kernels instead of `sources.Filter` to `FilterApi`. Spark's `ParquetFilters`
has no NaN guard on float comparisons (a grep); how parquet-mr treats NaN in stats is not verified.

**A staged prefilter planned from measured selectivity** [A]. Predicate columns decode in passes
ordered by observed kept rows, the plan re-made after every row group, a column keeping more than 85%
merged into the next pass (`io_sources/parquet/passes.rs:4-7,56`); other columns decode last under
the final mask; on by default under `Auto`, with a docstring that calls it unstable and warns it "may
slow down the scan". Spark's plan is a fixed two phases (item 52); the 85% rule and the per-row-group
re-plan are cheap to copy.

**Strings are not zero-copy from the page** (the premise was wrong) [A]. PLAIN BYTE_ARRAY walks the
interleaved four-byte lengths serially; values of 12 bytes or fewer go inline into the 16-byte view,
longer ones are copied, prefixes stripped, into a new per-page buffer (`binview/mod.rs:328-415`).
Only dictionary pages share bytes: the dictionary buffers are pushed once and each row is a 16-byte
view gather (`:607-721`). Compressed pages decompress into one reused per-column `Vec`
(`parquet/read/compression.rs:79-131,193-203`). What transfers is keeping dictionary strings as ids
plus a dictionary. Varka's kernels take `VarCharVector` only and refuse `ViewVarCharVector`
(VARKA-59), so the reader's output must be offsets plus bytes.

**The writer** [A]. A dictionary is tried for every primitive and binary column: integers with a
range of at most 255 or 65,535 get a seen-bitmap over [min, max] and are accepted if the number of
distinct values is at most 16, 256, 512 or 2048 (by width) or the ratio of distinct to length is below
three quarters; the dictionary is sorted and ids go through a `u16` table with no hash map; other
types use a cardinality estimate and the same ratio when the length exceeds 128
(`arrow/write/dictionary.rs:67-198,252-258`). There is one dictionary per column chunk with no size
cap and no PLAIN fallback (parquet-mr caps the page at 1 MiB). Binary stats are truncated to 64
bytes at a UTF-8 boundary with the max incremented (`write/utils.rs:196-255`). Defaults: Zstd, V1
pages, 262,144-row groups. **The compatibility checklist for a Spark-readable writer**, which Polars
does not meet, is the part to keep:

| Spark constraint | Polars |
|---|---|
| `created_by` must parse with `VersionParser`, else parquet-mr ignores BINARY and FLBA stats (`CorruptStatistics`, checked with `javap` on parquet-mr 1.18.1) | writes "polars version X (build Y)" (`arrow/write/file.rs:53`) |
| the default timestamp write is INT96 (`SQLConf.scala:1870`) | reads INT96, never writes it |
| footer keys `org.apache.spark.version`, `legacyDateTime`, `legacyINT96` drive rebase of old files (`DataSourceUtils.scala:200-240`); an unknown writer follows the config, default CORRECTED (`SQLConf.scala:7584-7610`) | no rebase, no Spark keys, no `org.apache.spark.sql.parquet.row.metadata`; writes `ARROW:schema` |
| decimals are INT32, INT64 or FLBA by precision; `writeLegacyFormat` makes all FLBA (`ParquetSchemaConverter.scala:774-807`) | non-legacy only |
| field-id key `parquet.field.id` (`ParquetUtils.scala:167`) | `PARQUET:field_id` |
| Snappy by default (`SQLConf.scala:1883`), 128 MiB blocks, at most 20,000 rows per page | Zstd, row-count groups |

**I/O and testing** [A]. A 256 KiB speculative tail read for the footer, a hand-written Thrift
decoder that keeps min and max as offsets into the footer buffer ("drops ~400k heap allocs to zero"
on a 10,000-column by 20-row-group file, `parquet/metadata/compact.rs:1-16`), sorted ranges
coalesced within 4,096 bytes up to 8 MiB (`polars-io/src/utils/byte_source.rs:594-660`), row-group
prefetch bounded by count and byte semaphores. parquet-mr already coalesces and does vectored I/O;
Spark's `loadNextRowGroup` is synchronous (`VectorizedParquetRecordReader.java:491`), so only a bounded
next-row-group prefetch is new, and only for S3 or HDFS. There is no fuzzer and no parquet-testing
submodule; round-trip hypothesis tests run against pyarrow with `data_page_size=1` and every encoding
forced (`py-polars/tests/unit/io/test_parquet.py:1618-1660`), and a property test checks
`scan_parquet().filter(e) == df.filter(e)` across parallel modes (`:1666-1710`). *For Varka*, the
same shape with Spark's own vectorized reader as the oracle, over parquet-mr files with tiny pages,
V1 and V2 and dictionary on and off.

**Read and not taken.** The delta decoders (scalar prefix sum over a whole-page `Vec<i64>`; only the
bit-width-0 arithmetic-progression shortcut, `delta_bitpacked/decoder.rs:158-163`), BYTE_STREAM_SPLIT
(per-element byte gather), DELTA_BYTE_ARRAY (a `Vec` per value), nested decoding (u16 levels, then a
scalar loop per level and depth), the categorical decoder (it needs a guarantee that every page is
dictionary-encoded), decimal FLBA to `i128` and INT96 (scalar closures), cloud and cachestat code.
Not read: Comet's Parquet reader (`m10/SCOPE.md:1617`) and Trino's Vector API Parquet decoders
(`VISION.md:318`), which are the nearer prior art.

## 6. Strings and binary (`polars-arrow` view arrays, `polars-ops`)

**Premises checked** [A]. The fork already reads Arrow Utf8View (`ArrowColumnVector.java:222,566-597`,
`StringViewAccessor`, SPARK-57929) with a layout identical to Polars's `View`; nothing writes views
(`ArrowWriter.scala:138` throws, `ArrowCachedBatchSerializer.scala:323` says the cache never writes
them). `propagate_dictionary.rs` is not predicate propagation: it only folds nulls in dictionary
values into the key validity.

**A canonical 16-byte slot** [A]. A `View` is `{length u32, prefix u32, buffer_idx u32, offset u32}`
(`binview/view.rs:18-29`); strings of up to 12 bytes sit inline in bytes 4..16, zero-padded
(validated at `:537`). `small_view_encoding` (`polars-compute/src/comparisons/view.rs:9`) turns a
literal of up to 12 bytes into the whole view, so `==` is `v.as_u128() == val` (`:106`); a longer
literal compares the `(prefix << 32) | len` word first, then memcmp. Ordering compares the prefix via
`to_be()`, then the string (`:28`); an inline hash is `hash_one(u128)`; `IN` rejects on a length mask
(`(len_mask >> min(len, 63)) & 1`) and scans up to 8 literals linearly before a hash set
(`is_in/binary.rs:107`, `is_in/mod.rs:17`). All scalar, no explicit SIMD. *For Varka*, it needs a
producer, since the cache emits `VarCharVector`: a derived per-batch slot column on VARKA-59's
`derivedInputs` path fits [V - the path exists]. Over slots, `col = lit` is one `LongVector` equality
against `[lit0, lit1, ...]` and `bits & (bits >>> 1)` on the mask; `len_mask` is a per-lane shift of a
broadcast long [I]. **The caveat**: Spark pads a `CHAR(n)` literal to n (`ApplyCharTypePaddingHelper`
`padAttrLitCmp`; scans are wrapped in `readSidePadding`, `CharVarcharUtils.scala:320`), so TPC-DS
`i_category = 'Women'` is probably a 50-byte compare [I], the inline path never fires, length
carries no information and the first eight bytes do the rejecting. Gate on `supportsBinaryEquality`
(`expressions.scala:916`); `View::default()` equals `""`, so `= ''` needs the validity AND.

**Loading a 10-byte record with overlapping loads** [V]. `bytes_eq`
(`polars-expr/src/key_rows/layout.rs:559-577`) compares for `8 <= n <= 16` as
`(a[0..8] ^ b[0..8]) | (a[n-8..n] ^ b[n-8..n]) == 0` with two unaligned `u64` reads and no mask, and
with `u128` windows up to 32; no byte outside the value is read. Two other forms exist
(`View::new_inline_from_block` reads 16 bytes and ANDs `MASKS[len]`; `extend_with_inlinable_strided`
does one 16-byte load plus a constant swizzle per width, behind the nightly `simd` feature), and the
comments contradict each other (`view.rs:312` "can be massively faster"; `cast/binary_to.rs:192-198`
"This is really slow, and I don't think it has to be"). Spark's own scalar form is
`ByteArray.getPrefix`, eight bytes loaded and masked (`ByteArray.java:51-75`). *For Varka*: two
unaligned long loads per candidate row, at `off` and `off + n - 8`, into a `long[]` and then
`fromArray` - item 8's index-spill path (`m5/PLAN.md:4391`), and it needs no padding. Windows
`[0, 8)` and `[2, 10)` cover all of `yyyy-MM-dd`, so the date cast and a `CHAR(n)` compare share
one load, which is the "one load path" that `m10/SCOPE.md:447` asks for. The 16-byte block form
over-reads: Arrow inputs are unpadded and the sanitizer bounds-checks them
(`testing-and-debugging.md:226`), so it would need a scalar tail. Neither form avoids scalar loads,
since there is no gather over `MemorySegment`.

**`LIKE '%a%b%'` as a literal chain** [A]. `strings/literal_chain.rs:10-125` parses the regex into an
optional prefix, in-order `memmem::Finder`s and an optional suffix; `is_match` does `starts_with`,
then `ends_with` guarded by `end < start + suffix.len()`, then each finder advances `start`; a
thread-local LRU holds 32 patterns. Spark's `LikeSimplification` (`expressions.scala:878-936`)
rewrites one-literal shapes and `a%b`; two inner literals stay `Like`, and in this fork's TPC SQL 2
of 8 `LIKE`s are such chains (tpch q13 `%special%requests%`, q16 `%Customer%Complaints%`) [V, grep].
Compile the parts at plan time: anchors become masked-window compares, finders ByteVector first/last-byte
searches [I]. Exclude `_` and non-binary collations. Item 66.

**Code-point counts and ASCII chunks** [A]. `is_utf8_codepoint_start(b) = (b as i8) >= -64`
(`strings/substring.rs:8`) is summed per 16 bytes to map a character index to a byte index; `length` is
`chars().count()`; `convert_while_ascii` (`case.rs:4-37`) ORs two `usize` loads and tests `& 0x8080...`,
converting ASCII chunks and handing the first non-ASCII chunk to the Unicode path; there is no
per-array ASCII flag. Varka: `bv.compare(GE, (byte) -64).trueCount()` per 32 or 64 bytes with a
masked tail [I]. **Spark diverges on invalid UTF-8**: `numChars` and `substring` step by a lead-byte
table that maps 0x80-0xC1 and 0xF5-0xFF to 0, treated as 1 (`UTF8String.java:120-142, 271-277,
300-312`), so a stray 0x80 counts as one character where the continuation-byte count gives 0. The
count is exact only under an ASCII or valid-UTF-8 guard with non-ASCII rows delegated
(`emitter-and-ir.md:431`); Spark's `trim()` strips only 0x20 (`UTF8String.java:996`).

**Validation** [A]. Polars validates once: `is_ascii()` over the whole value range, else one simdutf8
pass plus the continuation-byte check on offsets; Parquet validates the concatenation once; import is
trust (`new_unchecked_unknown_md`, `ffi.rs:68,92`). Spark never validates (`UTF8String.fromBytes`;
`isValid()` is explicit, `:455`; both Arrow accessors wrap raw bytes), so byte equality, ordering and
hashing are safe and character-level operations need a per-batch ASCII OR-reduce, or the whole-range
plus boundary check (one scalar load per row), or a decline. Full non-ASCII validation is simdutf8, an
external crate not in this project's record.

**A group-key table of (hash, 128-bit key)** [A]. `binview_index_map.rs` is a hashbrown table of
indices into `Vec<(Key{hash, view}, V)>`: an inline probe is one `u128 ==` with no hash compare
(`:115`); a long probe checks the stored hash, then the length, then the bytes (`:136`), not
prefix-first; long bytes live in map-owned buffers that start at 1 KiB and double; a per-table random
odd multiplier decorrelates the hash from partitioning (`:21-32`). Hashes come from a separate pass into
a `u64` column and never live in the view (`hash_keys.rs:165-200`); the hasher is `foldhash::quality`.
*For Varka* a key of up to 16 bytes hashes as a two-word xor-shift-multiply in lanes using low
multiplies only (`folded_multiply` needs a 128-bit product); TPC-H q1's two one-character keys fit one
slot each. This hash is internal: shuffle partitioning, `hash()` and `xxhash64()` stay Spark's.

**Dictionary** [A]. Parquet evaluates the predicate once on the dictionary page into `dict_mask`
(`binview/mod.rs:497-510`) and decodes by the number of matching entries: 0 is constant false, 1 an
integer compare, many a mask lookup; an RLE run costs one lookup. `cat == lit` looks the literal up
once and an absent literal is a constant (`comparison/categorical.rs:218`); each category's hash is
cached at insert. The negative: `apply_to_cats` rebuilds the whole category array and `take`s on every
call (`dispatch/cat.rs:33-56`) with notes naming the missing `len >= categories` rule - weaker than
item 19's Trino rule. Items 3, 9, 16, 18, 19 and 29 already have the concept.

**View arrays: a cheap filter, paid in buffer retention** [A]. A filter copies only the views and
keeps every buffer (`filter/mod.rs:67-82`); `take` ends in `maybe_gc` (`gather/binview.rs:22`), which
skips if the buffers total 16 KiB or less, and otherwise compacts when the estimated saving is at
least 16 KiB and usage is at least four times the lower bound. Varka's VARKA-80 three-pass byte
compaction would become fixed-width compaction, but Spark has no view writer, so a Varka-owned format
would need a buffer lifetime and a gc rule. Undecided.

**Read and not taken.** `strptime.rs` (per-field `atoi_simd`; Lemire's design is stronger),
strip, pad, split, replace and zfill (scalar std with Unicode semantics), `contains_any` and
`replace_many` (aho-corasick, no Spark counterpart), `comparisons/dictionary.rs` (per row, `todo!()`
for the scalar case), block prefetch in `key_rows` (no JVM intrinsic), `hash_to_partition` (needs
multiply-high), and the fork's `BinaryView.java` (a pointer type, not a German string).

## 7. Temporal, decimal and numeric semantics (`polars-time`, `polars-compute`, `polars-core`)

**Zone conversion with one threshold array** [E]. Polars vectorises nothing and caches nothing: per
element it runs `from_utc_datetime` then `from_local_datetime` (chrono-tz, a binary search per
call, from memory because the sources are absent) plus `Ambiguous::from_str` on the policy string
(`polars-core/.../replace_time_zone.rs:76,104,125`); the only shortcut is identity (`:25`). Its
policies are ambiguous `earliest | latest | null | raise` and non-existent `null | raise`
(`polars-arrow/src/legacy/kernels/time.rs:14-44`); there is no shift-forward, so Polars is no
reference for gaps, and `earliest` equals Spark. The survey's experiment, over 555 non-fixed zones to
2100 on JDK 25.0.4.1's tzdata:

* Define `LT[i] = T[i] + max(O[i-1], O[i])` (transition instant plus the larger of the two offsets).
  It is strictly increasing in every zone, and `instant = l - O[#{LT <= l}]` equals
  `LocalDateTime.atZone` in gaps and overlaps (875,000 points, none wrong). In a gap the subtrahend is
  the offset before; the record's "post-gap offset" names the result's offset.
* The minimum transition spacing is 601,200 s (America/Boa_Vista), so buckets of 2^39 microseconds
  (`micros >> 39`, 11,483 per zone) hold at most one transition per axis, and then
  `off = t >= nextT[b] ? nextO[b] : baseO[b]` (569,000 points each way, none wrong). The tables cost
  16 bytes by 11.5 thousand by two axes, 368 KB per zone; assert the invariant at build and decline
  outside 1900-2100.
* Cheaper: use the batch as the bucket - the batch's min and max from the existing input bound, a
  scalar lookup, then compare, blend and add on broadcasts, declining a batch that spans two
  transitions. Spark's `truncTimestamp` (`DateTimeUtils.scala:597-643`) already reuses the offset and
  verifies.
* `ZonedDateTime.plus*` and `truncatedTo` keep the source offset in an overlap: the rule is the later
  offset iff `l` is in the overlap window and the source offset was the later one (1.8 million cases,
  none wrong). `atStartOfDay` differs only when midnight is strictly inside a gap (six zone instances,
  1919-03-31, the Toronto family).

The transition table, the two axes and the rules past the table's end are known (`m5/PLAN.md:4236-4275`);
the thresholds, the bucket bound and the batch-as-bucket path are new. Item 31. **Not repeated by the
author; the experiment must be re-run before a design leans on it.**

**Decimal: gate on the data, skip what the type proves** [A for the code, E for the clamp]. Polars
uses `i128` throughout with no SIMD; a high/low split appears only in the fit test and in 64-by-64
partial products (`polars-compute/src/decimal.rs:193-216`). `add_sub` and `mul_with_scale` try an
`i64` path first, guarded by `hi ^ (lo >> 63)` OR-ed per 1,024-value block (`decimal.rs:479-524`;
`polars-core/.../arithmetic/decimal.rs:79-115,136-164`), and redo with validity on failure because
"null slots can hold any value" (`:53-75`). For Varka (items 1 and 2):

1. AND the high-word check with validity, or garbage in null slots declines batches.
2. Spark's `+`, `-` and `*` results never exceed their result precision for valid inputs unless the
   type is capped at 38, so no overflow check is needed there; products with `p1 + p2 <= 18` fit a
   long.
3. Admit wider types on a **runtime bound**: if both unscaled operands fit `int32` in the batch, the
   long product is exact. That admits TPC-H q6 at the spec's `DECIMAL(15,2)` (prices under 2^31 cents;
   verify against the data), which item 2's static rule refuses.
4. For p over 18, two limbs (`UNSIGNED_LT` carry) for `+`, `-` and compare; decline `*`, `/` and
   rescale-down.

**Cross-scale comparison by clamp** [E; the argument is short and re-derived here]. Polars skips the
common-type cast: it saturating-multiplies the low-scale side and compares `i128`
(`polars-core/src/series/comparison.rs:263-285`). Spark wraps both sides in `Cast(widerDecimalType)`
(`DecimalPrecisionTypeCoercion.scala:137-143`), so Varka sees `Compare(Cast, Cast)`; when the wider
type exceeds 18 digits the long overflows. Lower it as `clamp(x, -B, B) * 10^d CMP y` with
`B = floor(10^p_y / 10^d) + 1`: if `|x| < B` the product is exact; otherwise `|x| * 10^d > 10^p_y >
|y|`, so the clamped value, whose product `B * 10^d` is still above `|y|` and at most 2 * 10^18 < 2^63,
compares to `y` exactly as the true product would. Four operations; the survey checked 1.26 million
small-precision cases with none wrong. Spark can null the cast at the 38-digit cap where Polars
answers; the difference lives above 18 digits.

**Polars's decimal rules must not be copied; its primitives can** [A].

| | Polars | Spark |
|---|---|---|
| result precision | always 38 (`arithmetic/decimal.rs:50`) | per operation, capped by `adjustPrecisionScale` (`arithmetic.scala:440,627,889`) |
| scale of `*` and `/` | API: max of the scales; SQL: `*` s1+s2, `/` max(s1, min(s1+6, 12)) (`dsl_to_ir/sql.rs:257-300`) | s1+s2; max(6, s1+p2+1) |
| rounding | half-even (`decimal.rs:251`) | HALF_UP (`Decimal.scala:121,427`) |
| decimal to integral | rounds (`cast/decimal_to.rs:93-110`) | truncates (`Decimal.scala:251`) |
| overflow and `/ 0` | error | null, or error under ANSI |
| float to decimal | binary multiply, ties to even, a note "rounds multiple times" (`decimal.rs:582`) | BigDecimal, HALF_UP |

Reusable: `div_128_pow10` adds d/2 then divides, and HALF_UP drops the parity fix (`:272-278`); its
constants come from Lemire, Kaser and Kurz's Theorem 1 with a divisibility test (`:7-28`), known (item
82), and the 128-bit constants need multiply-high, so only dividends below 2^31 transfer. The test
grid is worth importing for item 2: decimal kernels run against `bigdecimal` and `num_bigint` on scales
{0, 1, 6, 18, 37, 38} cubed, 200 draws each, with operands `10^e`, `10^e / 2`, `2^k`, the maximum, the
maximum minus one, random values and their negations (`decimal.rs:1256-1560`), against Spark's `Decimal`.

**Numeric traps** [A, with two re-checks]. NaN and `-0.0` are section 1. **A correction to item 33
[V]**: Spark's `pmod` is `r = a % n; if r < 0 then (r + n) % n else r`, which is a floor-mod only for
a positive divisor; `pmod(7, -3)` is 1 where a floor-mod gives -2 (the survey counted 90 of 287
pairs differing for a negative divisor [E]). Polars's `//` and `%` floor
(`polars-utils/src/floor_divmod.rs:39-74`), a zero divisor gives null and `MIN / -1` wraps; truncation
exists only on the SQL path (`polars-expr/src/dispatch/misc.rs:809-843`). A float scalar divisor is
multiplied by the reciprocal (`float.rs:78-100,113`) and `49.0 * (1 / 49.0)` is 0.9999999999999999
[E], a counterexample for the lesson VARKA-88 already holds. Rust's `as` saturates float to int and
wraps int to int, as Java and Spark non-ANSI do; Polars's own default (`cast/mod.rs:39-52`) nulls on
overflow, which is `try_cast`.

**`add_months` with a literal count, split at compile time** [E]. Polars's `add_month` uses the
truncated `months / 12`, `months % 12`, one plus-or-minus-12 fix and the day clamp
(`polars-defs/src/time/duration.rs:506-548`), equal to `plusMonths` including negatives (128,000
cases). For a literal n: `n = 12a + r`, `k = (month - 1) + r`, one compare for the carry,
`ny = year + a + carry`; it drops the `/ 12` magic and the 24,564 / -24,576 literal bound (1.9
million cases), while VARKA-60's column form keeps the magic. Partly known (VARKA-40 section 5
covers multiples of 12).

**Date and time, read and not taken** [A]. Extraction is chrono per field per element
(`polars-time/src/chunkedarray/kernels.rs:38-148`, quarter as `(m + 2) / 3`), out-of-range becomes
null, negatives floor correctly: nothing is faster than Varka's. Truncate and round: a floor fix
(`truncate.rs:17`), Monday by an epoch-Thursday offset, quarters aligned to 1970
(`duration.rs:673,692-769`); semantics match Spark and there is no kernel gain. `strftime` interprets
a parsed format per element (ClickHouse's template-and-patch, item 10, is better). The weekday
`((x - 4) % 7 + 7) % 7` (`business.rs:489`) is worse than the reciprocal trick. **`strptime`**
(`polars-core/.../string/strptime.rs:82-239`): a whitelist of fields (`Y y d m b B H M S` and
`%3f %6f %9f`), then per value a byte interpreter that compares literals, reads each field as
exactly 4 or 2 digits through `atoi_simd`, rejects a month over 12, leaves day validity to chrono
and requires the whole string consumed; any failure falls back to chrono, optionally behind a
square-root-sized LRU. With no format, the first value chrono accepts picks a family (DMY or YMD)
and a sticky `latest_fmt` is tried first. Mapping: a fixed `dddd-dd-dd` shape plus `make_date`'s
validity mask is this fast path; one-digit fields, signs and Spark's trailing space or `T` tail go to
the row engine, as Polars goes to chrono. `%y` pivots at 70 in Polars (`:177`) where Java's `yy`
gives 2070 [E].

## 8. Aggregation, joins and sorting (`polars-expr`, `polars-row`, `polars-ops`)

Not Varka's yet (items 3 to 5); read for the shape. No dense or direct-index key path and no radix
sort was found, and the commit messages are bare titles, so there are no magnitudes.

**A correction to item 5 [V]**: Spark's variance and standard-deviation partial buffer is
`(n, avg, m2)`, not a sum and a sum of squares (`CentralMomentAgg.scala:76`; the update at
`:124-127` and the merge at `:101` are Welford's and Chan's). Polars keeps the same triple as
`VarState{weight, mean, dp}` with a Chan-style `combine` (`polars-compute/src/moment.rs:42-118`),
and builds one state per 128-value chunk by two passes - the mean, then the squared deviations
(`:71-83, 629-641`) - and merges them. A sum of squares converted at the flush is a different
number and cancels badly when the mean squared is much larger than the variance. The final phase is
Spark's, so a columnar partial must emit this triple; per batch, one vector pass for the mean, one for
the squared deviations, then a scalar merge per group [I].

**Lane-replicated accumulators and an in-register "seen" mask** [A]. `reduce/mod.rs:46,54-136,
418-430`. With 2 to 32 groups each group is stored in `64 / stride` copies at `g + l * stride`, row r
updates copy `r mod (64 / stride)` and the copies are folded at the end; the scheme is gated by
`ORDER_INDEPENDENT` (`:238, 287`). For 64 groups or fewer, "group seen" flags accumulate in a `u64`
register and are OR-ed in once per batch, otherwise a store only if the bit is unset, because "a store
on each row would chain the updates" (`:552-575`). The comment at `:43-46` names store-load stalls; the
first version (`3908bad551`) was reverted the next day and re-landed with an eviction-free path. *For
Varka*, item 4's dense step: `acc[gid] += v` is a memory dependency chain C2 cannot break when
consecutive rows share a group (TPC-H q1); the seen bit is Spark's `isEmpty` for decimals
(`Sum.scala:59,100`); for double, lanes change rounding against Spark's row order and need a contract
(items 35 and 82).

**A bounded evicting table for the partial phase** [A]. `hot_groups/fixed_index_table.rs:71-169`: two
candidate slots per key tested by one branch, a miss evicting on a second-chance tag, a forced
insert, or when the next row has the same hash; evicted states are reset and emitted; separate dense
per-aggregate arrays updated by one loop per aggregate; 4,096 slots growing to 2^17 when HLL and AMS
sketches show heavy missed keys. This fork's `HashAggregateExec` already has an adaptive pass-through
(`:200-207`, off by default, checked every 100,000 rows, bypassing below a 1.05 ratio, emitting
single-row partial buffers), which proves duplicate partials per key are legal in Partial mode. It
keeps hot keys aggregated where Spark's switch is all-or-nothing and removes growth and spill from the
kernel [I]. Item 5, Partial and PartialMerge only.

**Hash-only probe, then column-wise verify with rollback** [A]. Per 256-key chunk, match by 64-bit hash
only, insert provisionally, write new rows column by column (`key_rows/map.rs:390-458`,
`layout.rs:342-403`), verify all candidates column by column (`:279-333`) and roll the chunk back on any
mismatch (a 64-bit collision) to replay on the exact path (`map.rs:316-335`). The split is item 4's;
the protocol is new.

**Multi-column keys as fixed-stride packed words** [A]. `key_rows/layout.rs:54-125`: fields in
descending width, null bits last, null values zeroed, so equality is an OR of XORs over words;
hashing folds each column into a per-row `hashes[]` (`keys.rs:146-191`); Polars left row encoding for
these keys (`de04f98413`). Varka: q1's two `CHAR(1)` keys pack into one long lane by shift and OR.
`folded_multiply` needs a 128-bit product and cannot run in lanes (a long multiply costs three ops on
AVX2, `vector-api-and-width.md:399`). Spark wraps float keys in `NormalizeNaNAndZero`
(`NormalizeFloatingNumbers.scala:135`), which is not in `coverage.json`; Polars's equivalent is
`x + 0.0` plus a NaN blend (`polars-utils/src/total_ord.rs:40-48`).

**Read and not taken.** Row encoding for sort (arrow-rs's format written column by column in
1,024-row tiles; item 9 stands); the streaming sort's sampled splitters and a branchless Eytzinger
classifier with 16 interleaved descents (`nodes/sort/split_tree.rs:40-98`), which is VARKA-172's search;
joins that materialise the build side, hash-partition it and size tables from HLL; block hash with
software prefetch (HotSpot has none); the duplicate chain and MARKED bit; the split-block Bloom filter
(Spark fixes its format, item 27); a decimal sticky-overflow sentinel (item 2); a sorted group-by that
needs a plan-time sorted flag.

## 9. Testing, CI and process

**Comments describe the code as it is** [A; the rule itself V]. `AI_POLICY.md:33-38` (added 20 July
2026, `e5b36aa2e4`) says AI "tends to leave such comments describing what it did or did not do" and a
human must delete them. Neither `CLAUDE.md` nor `sql/varka/CLAUDE.md` states the rule, though item 74.7
and row 252 sweep the existing ones. The survey read all 25 hits for `used to|the old|no longer|once
carried` in comments across Varka's 80 main sources: 21 narrate history (for example
`VarkaShapeCacheImpl.java:57-117`, `VarkaEmitBudget.java:420,547`, `VarkaChronoLowering.java:405,886,
1506`), 4 describe behaviour, all of them "no longer". So: a sentence in `sql/varka/CLAUDE.md`, and
`\bused to\b` and `\bthe old\b` in `dev/varka_precommit.sh` on added lines only (every hit of those
two in the sample was history; leave "no longer" out). Row 252.

**The AI policy, split by who it protects** [V for the facts]. `AI_POLICY.md` has eight rules, added
26 January 2026 (`74ae02910c`), made stricter on 2 March and again on 3 July, when agents were
forbidden from interacting with the repository at all (`8e7fbca598`); the rationale is that slop
pull requests cost reviewer time. `docs/source/development/contributing/index.md:307-337` adds
disclosure, "I reviewed all changes", a screenshot of a local `make test` and one open pull request for
first-timers. **The maintainers use agents anyway**: 90 commits since 1 January carry a Claude
co-author trailer, 36 of them by the project's founder, 29 by another maintainer. For a project that is
developed with an agent: adopt the comments rule; adapt "verified by running, no code for platforms
you cannot test" as "every width and architecture path runs in some CI job"; already met are disclosure
(the trailers), approval before touching the repository (`CLAUDE.md`) and the accepted issue (the plan's
admission check). The good-first-issue ban, no AI prose, human-edited issues, the screenshot and one
open pull request only make sense for an open-source project receiving drive-by pull requests.

**A blanket second oracle, landed as one ratchet pull request** [A]. An autouse fixture asserts that
every `collect()` result's schema equals `collect_schema()` (`py-polars/tests/conftest.py:387-427`); the
opt-out marker is `may_fail_lazy_schema` (`pyproject.toml:301`); three meta-tests show the check fires,
the marker suppresses it, and ordinary errors pass through (`tests/unit/test_conftest.py:44-65`). PR
#29224 (`c9358e10ff`, 9 September 2026, Claude co-authored) landed the check, the marker, the
meta-tests and every violator's annotation or fix in one change (19 files); the 15 opt-outs each carry
a cause (6 `reason:`, 9 marked as to-do). Build row 275 in that shape: a second blanket check at the Varka exec
nodes, behind the sanitizer's test flag - an output attribute declared non-nullable has no unset
validity bit. `VarkaKernelCheck.scala` and `VarkaSparkDifferential.scala` do not check output
nullability; other helpers were not searched [I]. `m10/SCOPE.md:2720` records StarRocks's runtime form.

**Deselect markers rot** [A]. `xfail_strict = true` (`pyproject.toml:323`) covers only the 13 real
`xfail`s; the `may_fail_*` markers skip or deselect. The marker lines went 28, 107, 81 to 88, 29 and
then were deleted with the CI jobs; #28675 deleted 39 across 23 test files and changed nothing else.
Only 9 of the last 29 carried a comment. Known here (`testing-and-debugging.md`, "A skip list without
reasons rots"; `skips.tsv` fails stale lines): keep a default flip's exclusions keyed to a
configuration.

**Pin the toolchain and bump it on purpose** [A]. `rust-toolchain.toml:2` pins a dated nightly,
symlinked into `py-polars/` and copied into three runtime crates; bumps landed as `chore:` pull
requests five times this year, one touching nine files, five of them lint fixes. Varka's CI jobs run a
floating `java-version: '25'` (`.github/workflows/varka-fuzz.yml:77`, `varka-option-matrix.yml:68`,
`varka-canary.yml:69`, `varka-width-audit.yml:63`) while results quote 25.0.4 and 25.0.4.1
(`the-jit.md:52,781`). Paired runs inside one job are immune; the committed bands, canary baselines,
nightly fuzz verdicts and the warm-up verdict tests (VARKA-295) are not. Pin the patch in those jobs
and bump it in a named task that reruns the canary and the bands. No incident is on record [I].

**Benchmarks in CI** [A]. `benchmark.yml` runs no benchmark: a release build, a wheel-size comment
updated in place through a hidden marker, then the tests. Timing lives in `benchmark-remote.yml`: a
self-hosted box triggered by pushes to `crates/**` on main and by pull requests labelled
`needs-bench`, running an external PDS-H repository at scale factor 10 with its scripts in the
runner's `$HOME`, so there is no gate in the repository. CodSpeed (instruction counting) ran from
April 2024 (`44f1097024`), was commented out inside a compiler bump in March 2025 and removed in May
2026 (`676e029c18`); the commit bodies are empty and nothing says why. Instruction counting does not
apply to JIT-compiled Java, and what remains is a dedicated box and wall-clock time, which is no
quieter than Varka's paired runs; the one portable piece is the in-place pull request comment.

**Known, one line each** [A]. A hung-test watchdog that exits with the Python traceback captured at
query start (`polars-python/src/timeout.rs:74-85`) is `VarkaTestWatchdog` plus `dev/varka_deadline.sh`.
A tripwire file of per-type hashes with a label on any pull request that changes it
(`test-rust.yml:147-168`, `changes-dsl-labeler.yml`) is `emitted_bytes.json`, which the build summary
already prints as changed. Coverage runs on main only, with the status check off and
`--cov-fail-under=0`: Polars does not gate on coverage (rows 265 and 285). The hypothesis frames are
capped at 5 rows and 5 columns, chunking splits at half, and the CI never sets a profile; proptest runs
in 7 crates against naive references on sliced bitmaps, with no fuzz targets and miri on one crate.
The float equality helper requires null and NaN positions to match exactly, then a tolerance, which
would hide kernel bugs here. Not taken: cargo-deny, `min-publish-age` (35 days), dependabot, typos,
dprint, CODEOWNERS, release-drafter and runtime wheels have no analogue (Varka's dependency surface is
Spark's pom, and `dev/check_varka_arrow_version.py` guards the one added dependency); the
deterministic-map ban (`clippy.toml`; Varka's emitter has 61 `new HashMap`, two of them iterated, both
order-independent); `tools/update-cargo-env.py` (it fixes Cargo fingerprinting).

## 10. What none of it changes

Polars is not a better expression evaluator than Velox, DuckDB, ClickHouse or Comet for Varka's
contract, and its optimizer, coercion, morsel scheduling, categorical, row-encoding, sort and join
machinery do not transfer. What it adds is concrete and mostly in four places: the Parquet decode path
for the planned Arrow-native reader (sections 5, 2), the aggregation and string layouts for items 3 to
5 and 66 (6, 8), a handful of kernel formulas and shapes for the filter and the double lane (1, 2),
and a few habits of testing (4, 9). Where Polars did the same thing as an engine already read, the
item above says "known".

## 11. What to do with it

`m10/SCOPE.md` item 89 records the work this reading points at, grouped by the items it feeds, and
corrects items 5 and 33. `m7/PLAN.md` takes one row from it: **row 301**, batch size as an axis of the
suites (section 4); and refines row 252 with the comments rule (section 9). Everything else waits for
the milestone that builds the thing it informs: the double lane (item 35), strings (items 3, 8 and 66),
aggregation (items 4 and 5), decimals (items 1 and 2), time zones (item 31), the Arrow-native Parquet
reader.
