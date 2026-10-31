# VARKA-27: Boolean outputs

## 0. What exists now (admission check)

From the code at `475322b11ca` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named). The
row's text is `m5/PLAN.md` 2.1 and the milestone plan's 2.5. What the code says about each premise
is below, with the file and line it rests on.

1. **A bare boolean column is already served beside a fused entry.** `classify` forwards any
   `BoundReference` entry before it compiles anything (`VarkaExpressionCompiler.java:584-586`), so
   `b AS c` is a zero-copy forward of the input's `BitVector`, as an int column's is. When nothing
   fuses, `EntryPass.run` returns no projection (`:565-566`), so a lone `SELECT b AS c` runs on
   Spark's projection. What declines is a boolean
   *expression* at the root: `compileRoot` (`:1116`) calls `compileNode`, no family of the chain
   claims a comparison or a connective (`VarkaNodeCompiler.java:194-205`: the predicate family is
   reached only through `compileCond`), the condition family leaves boolean `If`, `CASE WHEN` and
   `coalesce` to it (`VarkaConditionCompiler.java:106-110`), and `fallback` declines with
   "unsupported expression" (`VarkaNodeCompiler.java:663`). `SELECT d < d2 AS c`, `NOT b`, `b AND
   b2` and `IF(b, b2, NOT b2)` all decline there.
2. **A condition root is already a bitmap writer.** A filter's fused conjuncts are `Cond` roots of
   a `CompiledVarkaProjection` (`VarkaExpressionCompiler.java:817-830`). The body emits the
   condition and writes its known-true bits, `toLong` of the mask in the dense body and the `kt`
   word in the masked one, into the output's validity slot
   (`varka/VarkaBodyEmitter.java:1133-1155`), by `emitValidityWrite` (`:1265-1301`): per lane
   group through `orValidityBitsAt`, or accumulated
   in a register and stored a word at a time under `validityByWord` (`:1372-1375`). A `Cond` root
   maps no data segment (`:324`) and the kernel contract says its `dstData` is never dereferenced
   (`varka/VarkaFusedKernel.java:39-44`). The masked body already holds `kf` beside `kt`
   (`VARKA-331.md` 0), so a root's known word `kt | kf` costs one `lor`.
3. **The root needs a node of its own.** The shape key is the output IR, the counts and the options
   (`sql/core/.../VarkaEvaluatorBase.java:505-508`), not the output types. A bare `Cond` root in a
   projection would hash like the filter root over the same condition and share its class, which
   writes one bitmap where a boolean output needs two. A wrapper node keeps every filter shape, and
   so its bytes, as it is.
4. **A `BOOLEAN` output can only be a `BitVector`.** `ArrowUtils.toArrowType` maps `BooleanType`
   to `Bool` (`sql/api/.../util/ArrowUtils.scala:97`), and `ArrowColumnVector` answers
   `getBoolean` only from `BooleanAccessor` over a `BitVector`
   (`sql/catalyst/.../vectorized/ArrowColumnVector.java:193-194, 350-362`); every other accessor
   throws (`:293-295`). Varka hands
   each output out as an `ArrowColumnVector` (`VarkaEvaluatorBase.java:584`) and the cache may
   store it. So the "int column" of the measurement is not an output Spark can read as `BOOLEAN`;
   it can only be an intermediate packed afterwards (2.4).
5. **Arrow sizes both bitmaps to whole words, zeroed.** In arrow-vector 19.0.0 a `BitVector` has
   type width 0 (`BitVector.java:75`), and `allocateNew` gives the data and the validity buffer
   at least `((n + 63) >> 6) << 3` bytes each, one combined allocation rounded by the allocator's
   policy and then halved, data first (`BaseValueVector.java:130-148, 200-245`, the rounding at
   `:146`, the halving at `:207`), and zeroes both (`BaseFixedWidthVector.java:329-333`).
   A word store into the data bitmap therefore stays inside it; one past the last word would land
   in the validity.
6. **Every output site reads a width of 0 as nothing.** `VarkaLaneArrow.of(BOOLEAN)` is null
   (`sql/core/.../VarkaLaneArrow.java:104`), so `allocateVector` throws "no Varka output vector"
   (`VarkaKernelEvaluator.java:509`). `runKernel` records `dstWidth = getTypeWidth()`, 0 for a bit
   vector, and the sanitizer guards `len * 0` data bytes (`VarkaEvaluatorBase.java:587-593`). The
   cross-body check of `VARKA-303` allocates `max(len * w, 8)` bytes of data and compares slices of
   `w` bytes (`sql/core/.../varka/VarkaKernelRunner.java:373, 416-424`): a bit output would overrun
   its eight bytes and compare nothing. The reproduction records the 0 as the output width (`:511`),
   which its format documents as "a selection bitmap" (`varka/VarkaReproduction.java:195`) and
   `VarkaReplay` allocates as `rows * 0` data bytes
   (`sql/catalyst/src/test/.../VarkaReplay.java:217`). `VarkaLaneArrowSuite` holds a vector exactly
   where a lane reads the type (`:39`) and its type width at the storage bits (`:53-54`). These are
   the output mirror of the input sites row 331 fixed (`VARKA-331.md` 10).
7. **No condition root is served by the bitmap pass.** `planBitmapPass` skips every `Cond` root
   (`varka/Analysis.java:1050-1053`). `Analysis.pureOf` answers null for `CondValue` and every
   `Cond` but `BoolColumn`, whose pure word is its input's (`:1118, 1157-1164`). A `BoolOutput` is
   not a `Cond`, so the skip does not cover it; `m5/PLAN.md` 2.1 asks for the fact to be stated
   and tested.
8. **The all-null shortcut excludes condition roots** (`VarkaBodyEmitter.java:460-467`), and row
   460's review found the value over a condition that the shortcut answered wrongly
   (`VARKA-460.md` 5): `And` and `Or` analyze as skipping and `CondValue` inherits it.
9. **The count reads bytes.** `VarkaSelectionBitmap.countSet` is a byte loop
   (`varka/VarkaSelectionBitmap.java:85-97`), 512 reads for 4096 rows. Its one caller passes a
   slice of `(len + 7) / 8` bytes of a buffer laid out in whole words
   (`VarkaFilterEvaluator.java:211-212, 240-241`, sql/core), so a word loop needs the whole-word
   slice or a byte tail. No test calls it.
10. **The measurement, re-read.** `VarkaMilestone4MeasurementsBenchmark` compared `toLong` into a
    per-group short (`boolBitsOnly`) with an int column (`boolToVectorAnd`, `boolBlend`):
    8870 against 7511 ops/ms at 512 bits, 2761 against 3683 at 128
    (`sql/varka/engine/benchmarks/VarkaMilestone4MeasurementsBenchmark-jdk25-results.txt`). At
    four lanes the engine's own per-group bitmap write rewrites half a byte per group, the regime
    `validityByWord` exists for (`VarkaEmitOptions.java:159-171`), which `VARKA-47` measured 6 to
    9% ahead at four lanes and 11 to 20% behind at eight and sixteen (`m5/PLAN.md`, VARKA-92).
11. **The lanes.** Int, long and the narrow lanes have conditions today, and a narrow condition
    beside an int root runs widened at the int lane (`varka/VarkaKernelPlan.java:163-200`). The
    floating conditions are row 341's step 4, not built: `FLOAT` and `DOUBLE` are still
    `Owed("341")` (`varka/VarkaTypes.java:275-278`) after its lane split (#1049). The census
    (`VARKA-376.md` 9): `toLong` is intrinsic at every width but on longs at 128 bits on x86, where
    it is the Java fallback, which every long-lane filter root already pays there.
12. **The tables.** `coverage.json` holds 123 predicate rows of 260. The conformance contract
    cancels every boolean value form naming this row (`VarkaTypeConformance.java:279-283`), and its
    boolean round trip is `value("x", null)` (`:138-145`), a bare column that alone fuses nothing
    and runs on Spark's projection (item 1) once the rule goes. `VarkaTypes` names 27 as the row
    that reads a boolean as a value (`:261`).

### What differs from the row text and the milestone plan

- **"A boolean column as a projection result" is already forwarded beside a fused entry, and
  alone runs on Spark's projection** (item 1). This row builds the boolean expression as a root;
  a bare column stays as it is.
- **Both forms end in a `BitVector`** (item 4). The packed form writes the known-true bits into the
  data bitmap. The materialized form keeps the int column the measurement had, as `CondValue`'s
  0 and 1 stored to scratch at a byte a row and packed by a pass after the loop (2.4); it cannot be
  the output itself. Question 1.
- **The packed form has two writers already** (items 2, 10): per group and per word. Both are
  measured; the per-word default at four lanes stays with VARKA-92 (`m9/SCOPE.md` item 39), since
  flipping it moves every existing shape's bytes at four lanes.
- **The output sites need a bit width** (item 6), which the row text does not name.

## 1. Goal and non-goal

Every boolean-valued expression Varka fuses as a condition is also a projection output, written to
an Arrow `BitVector` with Spark's three-valued answer: data bit set where the condition is known
true, validity set where it is known (true or false), null where it is unknown. The selected-row
count is taken a word at a time. Not this row: boolean casts (332), `bit_count(bool)` (335b), the
floating conditions themselves (341), `greatest` and `least` over booleans (the census's rows 333
and 329), and the fuzzers' reach (352).

## 2. Design

### 2.1 The node

`BoolOutput(Cond cond) implements VarkaVectorIR`, a root only: its lane is its condition's, its one
child is the condition, its canonical form is `(bool-out <cond>)`, so no existing hash moves.
`Analysis` treats it as `CondValue` for `nullsFromValidInputs` (a `CondConst(NULL)` keeps the kernel
masked-only), `pureOf(BoolOutput)` is null, so the bitmap pass never serves it although
`planBitmapPass`'s `Cond` skip does not reach it (item 0.7), and a `BoolOutput` anywhere but a root
is refused. The sealed-switch sites are `VARKA-460.md` 5's list, which row 460 found by building the
last value node over a condition.

### 2.2 The write

In the root loop of `VarkaBodyEmitter` (item 0.2), beside the `Cond` arm:

| body | data bitmap | validity bitmap |
|:--|:--|:--|
| dense | `toLong` of the condition's mask | all ones: by the driver, or per group (\*) |
| masked | `kt` | `kt \| kf` |
| epilogue | the same, truncated by the existing lane mask | the same |

(\*) The driver fills it once under `denseValidityOnce`; with the option off, the per-group
all-ones write runs (`VarkaBodyEmitter.java:1318-1319`).

Both go through `emitValidityWrite`, the data into the output's data segment mapped as a bitmap of
whole words, so `validityByWord` switches both writers together and `Slots` plans a second
accumulator beside `validityAcc` only when a `BoolOutput` root exists: an unconditional slot would
renumber the later slots and move existing shapes' bytes. The driver zeroes the data bitmap with the
validity, since the warm-up and the cross-body check reuse their buffers. The bit has no stored
width, so none of the narrow, low-half or narrowed-segment stores
(`VarkaBodyEmitter.java:1171-1197`) apply: it is written at the driving lane's count. A total
condition (`<=>`, `IS NULL`) has `kt | kf` all ones, never null, as Spark has it.

The all-null shortcut (item 0.8) excludes a `BoolOutput` root outright, as it excludes a `Cond`
root, rather than through the skipping flag `CondValue` inherits: a total condition over an
all-null column has an answer (`b IS NULL` is true on every row, `b <=> b2` where both are null),
and row 460 tested the flag over `AND` and `OR` only. The tests are those three over an all-null
batch beside `b0 OR b1` with `b0` null throughout and `b1` true on a row.

### 2.3 The compiler and the lanes

`compileRoot` sends a `BooleanType` expression to `compileCond` and wraps the result, so every
predicate the compiler fuses is an output with no new rule, under the predicate family's switch;
a declined condition declines the entry with its own reason. The drive is the existing rule: a
`BoolOutput`'s emission lane is its condition's lanes, so an int condition beside int values
shares the kernel, a narrow one joins an int kernel widened, and an int condition beside long
values takes a kernel of its own (VARKA-480). A double condition follows row 341's 2.4 when it
lands (beside the long lane by `VectorMask.cast`), and its coverage rows come under this row's
test of 3.3 with no change here.

### 2.4 The materialized form

Behind a `MACHINE` option, `materializeBoolOutput`, off by default: the condition stored as
`CondValue`'s 0 or 1 narrowed to a byte a row into the kernel's scratch (the segment the
materialized prefixes use), and a pack pass after the loop and the epilogue that loads the scratch
as a `ByteVector` of the emission's width and writes `compare(NE, 0).toLong()` into the data bitmap:
one mask transfer per 16 rows at 128 bits where the packed form makes four. That holds in the dense
body only, where the condition is a `VectorMask`: in the masked body `kt` is a word, the packed form
writes it with no transfer, and the blend needs a mask made from the word per group
(`emitMaskOfWord`), so the option applies to the dense body and the masked body stays packed.
Validity is the packed form's. Its `toLong` is over a byte mask, so at 128 bits on x86 it also
avoids the Java fallback the long-lane `toLong` takes there (item 0.11). It is the int column of
item 0.10 made storable; whether it pays is the measurement's (4.3).

### 2.5 The evaluator and the runner

`VarkaLaneArrow.of(BOOLEAN)` names `BitVector` (its admission is unchanged: `isArrowBacked` matches
`BitVector` first, `VarkaEvaluatorBase.java:929`). BOOLEAN stays `AsCondition`, so
`VarkaLaneArrowSuite` gains an explicit exemption: BOOLEAN's vector is an output vector, not a
lane's, in its first test, and its type width of 0 against one storage bit in its second. Each
site of item 0.6 takes a bit output's data as `((len + 63) / 64) * 8` bytes: the sanitizer's guard,
the cross-body check's allocation and a bit-by-bit compare of data where validity is set. The
reproduction cannot reuse 0, which means a selection bitmap: a bit output records a new marker,
`KernelCall.BIT_OUTPUT = -1`, in `outputWidths` (the parser already reads it), and `VarkaReplay`
allocates whole words of data for it. The kernel contract's javadoc gains the
`BoolOutput` output: `dstData` is a bitmap of whole words. `VarkaTypes`'s boolean entry names 332
as the row that reads a boolean as a value (a cast) instead of 27.

### 2.6 The count

`countSet` counts `Long.bitCount` over the whole words and the last partial word masked, over the
whole-word slice the evaluator already allocates (item 0.9), which its javadoc then requires: 64
reads for 4096 rows. No Harley-Seal (the milestone plan's 2.5).

## 3. Tests and oracles

1. **`VarkaEmitterBoolSuite`**: `BoolOutput` over every leaf state (true, false, null with the data
   bit clear and set) for `AND`, `OR`, `NOT`, the five comparisons, `<=>`, `IF`, `coalesce` and
   `IS NULL`, int and long lanes, dense and masked, at 4, 8 and the default lanes and the suite's
   lengths and offsets, each row's data and validity bit held to Spark's `eval`, in both writers
   and the materialized form; the all-null cases of 2.2 (`b0 OR b1`, `b IS NULL`, `b <=> b2`,
   `CondConst(TRUE)`); a `CondConst(NULL)` root masked only.
2. **The bitmap-pass boundary** (`m5/PLAN.md` 2.1): `pureOf(BoolOutput)` is null, and every
   `BoolOutput` over a comparison, `And`, `Or`, `Not` or a `BoolColumn` of nullable operands is
   not served by the pass, a test beside the word-algebra tests; and the sentence in the algebra's javadoc.
3. **Every predicate row as an output.** `VarkaCoverageDifferentialSuite` runs each predicate row as
   `SELECT rid, (p) AS c` on every fixture, Varka against Spark row for row (true, false and null
   told apart), and asserts a kernel served it; a row that is a bare boolean column fuses nothing
   beside `rid` and asserts Spark's projection ran instead. `VarkaCoverageSuite` compiles each
   predicate row as a projection entry. Question 2.
4. **Coverage rows**: a family "Boolean outputs" of about eight rows for the documentation, one per
   lane and form (`d < d2`, `l < l2`, `y1 < y2`, `NOT b`, `b AND b2`, `(d < d2) OR b`,
   `l <=> l2`, `CASE WHEN b THEN b2 END`).
5. **Conformance** (`VARKA-484.md` 2): the owed rule for boolean values goes; the boolean round trip
   becomes `if(x IS NULL, true, x)` and `coalesce(x, false)`; and every condition form of every
   conformance suite also runs as a value output.
6. **The runner and the count**: the cross-body check fails on a flipped data bit planted on an
   all-valid row (a masked batch skips rows with a null input, `VarkaKernelRunner.java:419`); a
   replayed bit output writes whole words; an end
   to end boolean output under `-Dvarka.sanitizeMemory=true` at a length not a multiple of 64;
   `countSet` against the byte loop over lengths 0 to 200 and 4096, with stale bits past the end.
7. **The bytes oracle**: every existing shape's bytes unchanged; the new rows add entries.
8. **The option**: `materializeBoolOutput` in `VarkaEmitOption.TABLE`, so the IR fuzzer draws it and
   the option matrix runs it. The fuzzers' grammar leaves `BoolOutput` out with the other boolean
   nodes until row 352.

## 4. Predictions and costs

The measurement is a sql/core class of its own, `VarkaBooleanOutputBenchmark`, on
`VarkaBenchmarkBase` over Arrow-cached rows: `d < d2`, `l < l2`, `y1 < y2`, `NOT b`, `b AND b2` and
`(d < d2) AND (l < l2)` as outputs, each with no nulls (dense) and with nulls (masked), the filter
over the same condition as the control. Its baseline, both arms on the row path, is committed
first on the unchanged code; results from `benchmark.yml` on the EPYC 7763 at 256 and 128 bits.

1. **No existing shape's bytes move**; the `option_arms` digests move with the new option.
2. **Against Spark's row path**, every int and narrow output at least 1.5 times faster by best at
   256 bits, near the 2 to 3 times the filter benchmark's committed rows show; a boolean output is
   the filter's work and one more bitmap. Long outputs the same at 256 bits; at 128 bits on x86 the
   long dense body is the fallback `toLong` (item 0.11), predicted slower than the row path, a
   recorded result (question 4).
3. **The forms.** The per-word writer ahead of the per-group one by 5 to 10% at four lanes (int at
   128, long at 256) and behind at eight and more, as `VARKA-47` found for validity; the
   materialized form, dense body only (2.4), behind the packed one by more than 10% at 256 bits
   and within 10% either way at 128 bits.
4. **Test time**: the differential's 123 output tests at `VARKA-334`'s 0.38 s a test and the
   calibration table's ratio of 1.3, about 60 s; the emitter suite under 10 s more (`VARKA-460`'s
   0.07 of its estimate).

Cost: medium, as the row says. About four days after the baseline, one more for the materialized
form.

## 5. Order and files

1. **The baseline class**, its own pull request on the unchanged code.
2. **The count** (2.6) and its test.
3. **The node, the write and the runner sites** (2.1, 2.2, 2.5), IR-level tests only.
4. **The compiler arm, coverage rows, the differential and conformance** (2.3, 3.3 to 3.5), the
   generated files in `ADDING_AN_EXPRESSION.md`'s order.
5. **The materialized form** (2.4), if question 1 says so.
6. **The measurement** and its verdict; the PR build and the option matrix dispatched.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `BoolOutput` and its canonical form |
| `varka/Analysis.java`, `varka/Slots.java` | its flags, the data accumulator |
| `varka/VarkaBodyEmitter.java`, `varka/VarkaVectorWalk.java` | the write, the pack pass |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `materializeBoolOutput` |
| `varka/VarkaSelectionBitmap.java`, `varka/VarkaFusedKernel.java` | the count, the contract |
| `VarkaExpressionCompiler.java`, `varka/VarkaTypes.java` | the root arm, the values row |
| `VarkaLaneArrow.java`, `VarkaEvaluatorBase.java`, `VarkaKernelRunner.java` | the bit width |
| `varka/VarkaReproduction.java`, `VarkaReplay.java` (test) | the `BIT_OUTPUT` marker |
| `VarkaLaneArrowSuite.scala` (test) | BOOLEAN's output-vector exemption |
| tests | emitter suite, differential, coverage rows, conformance, runner, count |
| benchmark | `VarkaBooleanOutputBenchmark` and its results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `width_audit.json` |

## 6. Out of this row

Boolean casts and `CAST(p AS INT)` as an output (332), `bit_count(bool)` (335b), the floating
conditions (341), the fuzzers' boolean reach (352), the per-word default at four lanes
(VARKA-92, `m9/SCOPE.md` item 39), and lifting an int condition onto a long kernel's lane instead
of a kernel of its own. Also out: the cost model's price of a `BoolOutput` root's store.
`VarkaEmitCost.Tally.output` prices it as a value root's store at its lane, not as a selection
bitmap's or as two bitmaps, and stays so until the model's refit reprices it with the node's own
price, which the price table lists as awaiting that refit (11); no number of the model moves here.

## 7. Declines that stay

`greatest` and `least` over booleans, and every boolean expression whose condition declines today
(a string comparison, a UDF, `isnan` before 341), each with its condition's reason.

## 8. Questions for the owner

Answered by the owner on 27 October 2026: (a) to all four - both forms built, measured and kept;
the derived coverage run with about eight documented rows; an int condition beside long values in
a kernel of its own; long outputs fused at 128 bits on x86 unless 4.2's reading shows a loss the
filters do not have.

1. **The forms.** (a) The packed form with both writers, and the materialized form of 2.4 behind
   its option, measured and kept whichever wins. (b) The packed form only, with item 0.4 recorded
   as the reason the int column is not an output. *Recommended: (a)*: the row keeps both forms, and
   the materialized one is the only form that cuts the mask transfers at 128 bits.
2. **The coverage claim.** (a) The derived run of 3.3 over every predicate row, with about eight
   documented rows. (b) A projection row in the table for each of the 123 predicate rows.
   *Recommended: (a)*: the run covers every predicate row present and future, 341's included,
   without doubling the table.
3. **An int condition beside long values.** (a) A kernel of its own, as today's lane rule gives.
   (b) Lift it to the long lane. *Recommended: (a)*: correct with no new rule; the lift needs every
   earlier entry re-lifted when the kernel's lane moves.
4. **Long outputs at 128 bits on x86.** (a) Fuse, as the long filters there do. (b) Decline at two
   long lanes. *Recommended: (a)*, unless 4.2's reading shows a loss the filters do not have.

## 9. Done when

Row 27's text holds over every predicate row of the coverage table, with `m5/PLAN.md` 2.1's list:
boolean sub-expressions stay in mask space and are written once at the root; the forms are
measured per width with their verdict and kept behind their options; the output is a `BitVector`
whose data is the known-true bits and whose validity is the known bits; a null input gives a null
output, never a false one, under every null pattern of the differential; no boolean root has a pure
word or is served by the bitmap pass, tested and stated in the javadoc. Also: `countSet` reads
words; the conformance round trip for `BOOLEAN` runs; the bytes oracle is unchanged for every
existing shape; and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)

## 11. Step 3 as built (27 October 2026)

Sections 2.1, 2.2 and 2.5 with IR-level tests; nothing fuses from SQL until step 4's compiler arm.

**Built.** `BoolOutput(Cond cond)` in `VarkaVectorIR`, its lane its condition's, canonical form
`(bool-out <cond>)`, in every sealed switch of `VARKA-460.md` 5's list and the seven test lists of
node types (the fuzzers' reach leaves it out with the other boolean nodes until row 352, the
price table lists it as awaiting the refit, and its budget weight is the connectives' floor of 1).
`Analysis` analyzes it as `CondValue`, so a `CondConst(NULL)` under it keeps the kernel
masked-only; `pureOf` answers null for it, stated in `planBitmapPass`'s javadoc; and a
`BoolOutput` below a root is refused in the operand check. The write is
`VarkaBodyEmitter.emitBoolOutput`: the data bitmap gets `toLong` of the condition's mask in the
dense body and `kt` in the masked one, through the same per-group or per-word writer as the
validity (a second accumulator, `Slots.dataAcc`, planned after every validity accumulator and only
for a `BoolOutput` root, zeroed before the loop for the verifier as the validity's are). The
validity follows the value roots' rule as section 2.2's table has it: all ones in the dense body,
by the driver under `denseValidityOnce` or per group without it, and in the masked body the node's
own word `kt | kf`, stored as `CondValue`'s is. The all-null shortcut excludes the root outright.
At the runner sites, `VarkaLaneArrow.of(BOOLEAN)` is a `BitVector` with `VarkaLaneArrowSuite`'s
exemption, `runKernel` records `KernelCall.BIT_OUTPUT` (-1) as a bit vector's width,
`KernelCall.outputDataBytes` sizes a bit output's data at whole words for the sanitizer's guard,
the cross-body check's allocation and `VarkaReplay`, and the cross-body compare reads a bit
output's data bit by bit where the validity is set. The kernel contract's javadoc gains the
boolean output, and `VarkaTypes` names row 332 as the row that reads a boolean as a value
(`types.tsv` regenerated).

**Departures from the plan.**

1. *The driver's zero of the data bitmap.* The default driver is the table driver
   (`driverOutputTable`), whose plan (`VarkaVectorSupport.prepareOutputValidity`) prepares
   validity only and is handed no data addresses. Rather than a new plan step, which would widen
   that call in every kernel and move the engine's step set and its pinned equality with the
   emitter's, the table driver makes one more call after the plan's,
   `VarkaVectorSupport.zeroBitOutputData`, which zeroes each `BoolOutput`'s data bitmap to its
   last whole word from a string constant of their ordinals. It is one call at any count of
   boolean outputs, so the table driver's size stays its calls' (`VARKA-190.md` 10); a zero
   unrolled per output, about 22 bytes each, would have grown it with the outputs, and
   `splitDriver` moves only the group calls. The unrolled driver zeroes it where it maps the
   segment, as it does each validity. Neither form is emitted for a kernel without a boolean
   output, so no existing shape's bytes change.
2. *The data segment* is mapped at whole words under both writers, not only under
   `validityByWord`: the per-group writer stays inside it, and Arrow's `BitVector` holds the whole
   last word at every length (item 0.5).
3. *The narrow lanes.* The plan's tests name the int and long lanes; the emitter suite also runs
   `BYTE` and `SHORT` conditions as outputs, which pass with no change, so the node is not refused
   at a narrow lane as `CondValue` is.
4. *The projection's width check* (`CompiledVarkaProjection`) refuses a `BoolOutput` root whose
   Spark type is not `BOOLEAN`, beside its check of value roots' widths.
5. *The planted data bit of 3.6* is planted in the compare itself: the cross-body compare is now
   the static `VarkaKernelRunner.compareBodies`, and `VarkaEvaluatorComponentsSuite` hands it two
   bodies' bitmaps that differ in one data bit on a valid row of a null-free batch, and in one under
   a null row, which passes. Planting it through a kernel would need a second emission that
   disagrees with the first and `-Dvarka.checkBothBodies` at the JVM's start.
6. *A finding outside the step.* `VarkaCanonicalIr`, the reproduction's IR parser, reads no
   boolean-column leaf and none of the boolean nodes of row 331 (`BoolColumn`, `CondConst`,
   `BoolCompare`, `CondIf`, `BoolNullSafeEq`), so a reproduction file of a kernel over a boolean
   column does not replay. The round trip of `BoolOutput` is tested over comparisons and a null
   test. The owner made the parser's boolean leaves and nodes a row of their own, 502, with the
   decimal leaf of row 347.
7. *Test gaps for step 4.* The review of this step found three paths no test reaches, which step
   4's tests are to cover:
   - no `runKernel` with a `BOOLEAN` output, so the evaluator's assignment of `BIT_OUTPUT` as the
     width of a `BitVector` output (`VarkaEvaluatorBase`) and `-Dvarka.checkBothBodies` over a
     bit output run in no test; the compare is tested only as a static call (departure 5);
   - no sweep of `validityByWord` with `denseValidityOnce(false)`, the word writer with the dense
     validity written per group;
   - no `BoolOutput` over a kernel that converts lanes, whose epilogue is `emitMixedEpilogue`.

**Tests.** `VarkaEmitterBoolSuite` gained six tests: every boolean form (`AND`, `OR`, `NOT`, the
five comparisons, `<=>`, `IF`, `coalesce`, `IS NULL`, `IS NOT NULL`, the constants, and value
comparisons over `CondValue`) as outputs over every leaf state, dense and masked, at four lanes,
eight and the default width, each under four writers (per group, per word, the unrolled driver
with the per-group dense validity, the unrolled driver per word), on the int, long, byte and short
lanes; boolean outputs beside value outputs in one kernel; the all-null cases of 2.2 each alone in
its kernel (`b0 OR b1` and `b0 AND b1` with `b0` null throughout, `b IS NULL`, `b <=> b2` with one
and with both null throughout, `CondConst(TRUE)`); and `CondConst(NULL)` keeping the kernel
masked-only, with the refusal below a root. Each row's data bit is held to the known-true answer
and its validity bit to the known one, by `VarkaReference.cond`, which the suite's first test holds
to Spark's `eval` (now with `IS NULL`). Two planted faults each fail five or six of them: the masked
validity as `kt` alone, and the driver's zero of the data bitmap left out. 3.2:
`VarkaEmitterValiditySuite` asserts that no `BoolOutput` over a comparison, `AND`, `OR`, `NOT` or a
bare column is served by the bitmap pass. 3.6's runner parts: the compare above, and
`VarkaReproductionSuite` replays a bit output of 100 rows from its file's text, the marker read
back as -1 and the data allocated at two whole words.

**Measured.** The six emitter tests take 9.7 s in the defaults gate, inside 4.4's "under 10 s
more" for the emitter suite. The catalyst defaults gate (`dev/varka_matrix.sh --defaults --module
catalyst`, sanitizer on) passes, 911 tests with none failed; `VarkaLaneArrowSuite`,
`VarkaEvaluatorComponentsSuite` and `VarkaKernelEvaluatorSuite` pass in sql/core.
`emitted_bytes.json` is unchanged, so no existing shape's bytes moved (3.7; prediction 4.1's
first half), and `coverage.json` and `width_audit.json` are untouched, as nothing compiles to the
node yet.

**The review of step 3 (30 October 2026).** It corrects departure 4: the width check held one way
only, so a `TINYINT` value root typed `BOOLEAN` passed the width compare, was handed a `BitVector`
with the bit-output width and stored a byte a row past the data bitmap, and a filter's `Cond` root
typed `BOOLEAN` in a projection would write only the validity. `CompiledVarkaProjection` now
refuses a root that writes bitmaps (`VarkaVectorIR.writesBitmaps`: a `Cond` or a `BoolOutput`)
unless its type is `BOOLEAN`, and a value root typed `BOOLEAN`; the filter's kernel is the same
record with `Cond` roots typed `BOOLEAN`, so `PartialVarkaProjection` refuses a bare `Cond` root
in any of a projection's kernels. `VarkaEmitterBoolSuite` builds each wrong shape and shows it
refused. The same predicate replaces the copies of the rule in the all-null shortcut, the
epilogue's bounds-mask count, the output segment mapping and `planBitmapPass`, which skips a
`BoolOutput` root by it rather than through its null pure word. `zeroBitOutputData` refuses an
ordinal past the destinations by name; the cross-body compare takes each body's buffers as a
record; `VarkaLaneArrow` keeps one table. The cost model's price of the root's store stays out of
the row (6). `emitted_bytes.json` is unchanged.

## 12. Step 4 as built (27 October 2026)

Sections 2.3 and 3.3 to 3.5, with the three test gaps of 11's departure 7.

**Built.** `VarkaExpressionCompiler.compileRoot` compiles a `BooleanType` root through
`VarkaConditionCompiler.compileCond` and wraps it in `BoolOutput`; a declined condition declines
the entry with the condition's own reason, and the lane rule is the existing one (an int condition
beside long values plans alone and takes a kernel of its own, a long one shares their kernel; both
tested in `VarkaExpressionCompilerSuite`, whose assertion that `d < d2` as an output compiles to
nothing now asserts the `BoolOutput`). A bare boolean column is still forwarded before the arm.

*Coverage.* A family "Boolean outputs" of eight projection rows: `d2 > d`, `l2 > l`, `y2 > y1`,
`NOT b2`, `b AND b2`, `(d < d2) OR b`, `l2 <=> l` and `CASE WHEN b THEN b2 END` (the CASE without
ELSE fuses: the condition family already builds its null constant). The three over boolean columns
are verified by `VarkaEmitterBoolSuite`'s sweep of boolean outputs, the rest differentially.
`VarkaCoverageSuite` gains a test that compiles every predicate row as a projection entry and
requires a `BoolOutput` of type `BOOLEAN`, or, for the bare column `b`, no fused entry: all 136
predicate rows pass.

*The differential.* `VarkaCoverageDifferentialSuite` runs each predicate row as
`SELECT rid, (p) AS c` on its three fixtures, under the partition oracle's rule exclusions (the
null-safe set for a null-safe row), through the row consumer and the columnar one (a cached view
over the query, whose Arrow cache stores the kernel's `BitVector`), and compares the rows with
Spark's, a true, a false and a null each its own value. A kernel must serve the whole projection,
except for the bare column `b`, which must run on Spark's projection, and an output Spark's
optimizer folded to a constant. A planted test holds the comparison to a null answered as false.
The eight new rows also run the per-row test and the ANSI form, for which a `BOOLEAN` stand-in
(`b2`) and a long guard for a condition over long columns were added.

*Conformance.* The rule that owed every `BOOLEAN` value to this row is gone; the `BOOLEAN` round
trip is `if(x IS NULL, true, x)` and `coalesce(x, false)`, and passes; and every condition form
with a register entry also runs as a value, under the same entry, so a value over an operator the
census marks fused must fuse, and one it owes must decline. All four conformance suites pass with
the twins; their only cancelled checks are the cast rows owed to rows 28, 103, 332, 340, 343,
350 and 364, as before, now also listing the value twins. A boolean value is held to both fixture
rules (true and false somewhere, null where null-intolerant). For this `VarkaKernelChecks` learned
the `BoolOutput` root: its data is a bitmap of whole words pre-filled with stale bits the driver
must zero, registered with the sanitizer at whole words (`BIT_STRIDE`), compared row by row with the
data bit as the known-true answer and the validity as the known one, held to zero past the last
row, and folded into the answer digest. Inverting the data compare fails six tests of
`VarkaBooleanConformanceSuite` and `VarkaEmitterMixedLaneSuite`.

*The test gaps of 11's departure 7.*
- `VarkaKernelEvaluatorSuite` runs three boolean outputs (`i > 11`, `d IS NULL`, `i <=> 13`)
  through the evaluator's `runKernel` over four rows with nulls, asserts each output is a
  `BitVector` and each `dstWidth` is `BIT_OUTPUT`, and reads Spark's answers back;
  `VarkaMemorySanitizerEndToEndSuite` runs `b AND b2`, `(i > 50) OR b` and `b <=> b2` over 100
  rows under the sanitizer (3.6's end to end at a length not a multiple of 64). Both, and the
  differential, run under `-Dvarka.checkBothBodies` and the sanitizer in the defaults gate below,
  which puts both flags on every JVM; that is where a bit output's both-bodies check runs. The
  check is exercised there, not asserted: no test counts that the compare ran.
- `VarkaEmitterBoolSuite`'s writers gain a fifth, `validityByWord` with
  `denseValidityOnce(false)`.
- `VarkaEmitterMixedLaneSuite` runs boolean outputs in kernels of both lanes, whose epilogue is
  `emitMixedEpilogue`: `i < l`, `i < l OR i2 IS NOT NULL`, `CAST(l AS INT) = i` (an int-lane
  condition driven at the long lane) and `i < l` beside a CASE over it, under four writers, at
  both widths, every length and null pattern.

**Generated files**, in `ADDING_AN_EXPRESSION.md`'s order on this branch's base: `coverage.json`
and `docs/sql-varka.md` add the eight rows; `emitted_bytes.json` adds their entries at both
widths with no existing entry changed, and its `option_arms` digests move, as each is one digest
over every shape, the new ones included (prediction 4.1); `width_audit.json` adds the rows, the
long ones at 128 bits with the long `toLong`'s refusal item 0.11 named; the coverage table was then
regenerated over the audit. `golden_corpus.json` moves three statements of its interval file
from a plain projection to a fused one (`INTERVAL '1 02' DAY TO HOUR = INTERVAL '02:10:55' HOUR
TO SECOND` and two like it, comparisons as outputs), each answering as Spark does.
`emit_cost_audit.json`, the operator census and `census.tsv` did not move. Rows 485 and 341
land coverage rows on master; the files are regenerated again after they merge.

**Departures from the plan.**

1. *The documented rows' spellings.* The generated files key a row by its SQL, so a projection row
   cannot repeat a predicate row's text: `d < d2`, `l < l2`, `y1 < y2`, `NOT b` and `l <=> l2` are
   spelled `d2 > d`, `l2 > l`, `y2 > y1`, `NOT b2` and `l2 <=> l`, the same forms over the same
   lanes.
2. *The differential's optimizer.* The output form runs under the partition oracle's exclusions
   only, not also under the default optimizer, so that each row reaches Varka as written; the
   default optimizer's rewrites of these rows are already the per-row predicate tests' inputs.
3. *The guard of the ANSI form.* A boolean projection row has no lane of its own, so its guard is
   `lrid` where its condition reads a long-lane column, rather than by the result type alone.

**Measured.** The defaults gate (`dev/varka_matrix.sh --defaults --split 4`, the sanitizer and
`-Dvarka.checkBothBodies` on every JVM) ran 2468 tests over both modules with one failure, the
golden corpus before its regeneration, which then passes. Every one of the 136 predicate rows
answers as Spark does as a boolean output, row for row on every fixture under both consumers, with
a kernel serving it but for the bare column `b`, which runs on Spark's projection. The
exemption for an output Spark folds to a constant never fires under the exclusions: run once with
it removed, all 136 tests pass. The 136 output
tests take 28.3 s, against prediction 4.4's 60 s for 123: a ratio of 0.47, since each test's
queries are small and the fixtures stay cached across them.

## 13. Steps 3 and 4 brought up to master (30 October 2026)

Both branches took master by merge commits, 93 commits on from their base. What moved under
them, and where 11 and 12 now read differently:

- *The differential's output test is below the session.* Master moved every coverage row's
  answers from `VarkaCoverageDifferentialSuite` into `VarkaCoverageHarnessSuite` (VARKA-509) and
  lints a suite that checks rows through a session (VARKA-529), so 12's every-predicate-row test
  is now that suite's `booleanOutputs`: each predicate row as `SELECT rid, (p) AS c`, planned by
  the harness under the same rule exclusions, served through both consumers, its rows held to
  Spark's with a null kept apart from a false, a kernel required to answer with no output left
  to the row path, and the bare column `b` required to fuse nothing. The planted check holds
  the harness's row comparison to a null answered as false. The ANSI form's `BOOLEAN` stand-in
  (`b2`) and its guard moved with it to `VarkaAnsiForm`; the guard of a boolean output is now
  picked by the condition's columns as a predicate's is, so a long condition takes `lrid` and
  a floating one `xrid` or `frid`.
- *More predicate rows.* Row 341's floating rows bring the table to 164 predicate rows, and all
  164 answer as Spark does as boolean outputs, the floating conditions included, with a kernel
  serving each but `b`.
- *The conformance contract* now holds each form's discrimination over the batches the kernel
  answered as well as over the fixture; a `BOOLEAN` value is held to both rules in both places.
- *Generated files*, regenerated after the merge in `ADDING_AN_EXPRESSION.md`'s order with the
  same result as 12: the eight rows added to `coverage.json`, `docs/sql-varka.md`,
  `emitted_bytes.json` (no existing entry changed, the `option_arms` digests moved) and
  `width_audit.json` (the two long rows refused at 128 bits), and the same three interval
  comparisons of `golden_corpus.json` moved from a plain projection to a fused one.

**Checked** on the laptop: the catalyst defaults gate (`dev/varka_matrix.sh --defaults --module
catalyst --split 4`, the sanitizer on, and on step 4 `-Dvarka.checkBothBodies` too) passes on
both branches, 972
tests on step 3 and 977 on step 4 with none failed; on step 4 the sql suites the change reaches
(the coverage harness and differential, the expression harness suites, the evaluator, sanitizer,
lane, golden corpus suites) pass, 1409 tests with none failed; on step 3 the sql suites of 11
with `VarkaMemorySanitizerEndToEndSuite`, and the engine's `VarkaVectorSupportOutputPlanTest` pass.
