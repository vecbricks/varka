# VARKA-335: the bitwise and shift operators, `bit_count`, `bit_get` and the bitmap positions

*Row 335 of `m8/PLAN.md`, wave 0. Planned on 16 October 2026 at `8a50f61a368`, from `m9/SCOPE.md`
item 32 and `m8/PLAN.md` 2.9. Section 2 finds that only part of the row can land in wave 0, and
section 8 splits it accordingly.*

## 1. Where this came from

Item 32 scoped the bitwise family as "nothing in the way": `&`, `|`, `^`, `~` and the three shifts
are one lanewise operation each at the operand's width, and the emitter already emits `AND`,
`XOR` and the shifts inside other lowerings. None of the fifteen census entries the row owns
(`&`, `|`, `^`, `~`, `shiftleft`, `shiftright`, `shiftrightunsigned`, `<<`, `>>`, `>>>`,
`bit_count`, `bit_get`, `getbit`, `bitmap_bucket_number`, `bitmap_bit_position`) is fused today.
The integer reading added the semantics a kernel has to copy: `bit_count` is a 64-bit popcount of
the sign-extended value, the vector shifts mask their count as Java does, and the bitmap functions
reduce to blended `AND`s and a signed shift with `Long.MIN_VALUE` as the edge (2.9). The row's
done-when asks for every integral type each takes, by exhaustion at 32 bits and below.

## 2. The admission check, done

1. **What Spark computes** (`sql/catalyst/src/main/scala/.../expressions/`):
   `BitwiseAnd`, `BitwiseOr`, `BitwiseXor`, `BitwiseNot` (`bitwiseExpressions.scala` 46, 102,
   158, 210) at the operand's type; `BitwiseCount` (257 to 279), `Long.bitCount` of the widened
   value for `BOOLEAN`, `TINYINT`, `SMALLINT`, `INT` and `BIGINT`, an `INT` result;
   `BitwiseGet` (316 to 345), `(byte) ((long) x >> pos & 1)` after a position check, a `TINYINT`
   result; `ShiftLeft`, `ShiftRight`, `ShiftRightUnsigned` (`mathExpressions.scala` 1507, 1543,
   1578, over `BitShiftOperation` 1443: value `INT` or `BIGINT`, count `INT`, the value's type as
   the result, the JVM's operators, which mask the count); `BitmapBucketNumber` and
   `BitmapBitPosition` (`bitmapExpressions.scala` 49, 87; `BitmapExpressionUtils.java` 30 to 43,
   `NUM_BITS = 32768`): bucket `v > 0 ? 1 + (v - 1) / 32768 : v / 32768`, position
   `v > 0 ? (v - 1) % 32768 : (-v) % 32768`, both `BIGINT` in and out.
2. **The IR has no bitwise node.** `IntOp` is `{ ADD, SUB, MUL }` (`VarkaVectorIR.java` 193) and
   `IntArith` carries overflow semantics the bitwise operators do not have. The emitter emits the
   operators only inside other lowerings: `emitShift` (`VarkaVectorWalk` 792 to 796, `LSHR` and
   `LSHL` by a constant, hard-wired to `IntVector`), `XOR` and `AND` in the overflow check (456 to
   461), `ASHR` in `VarkaDivisionLowering` 317, `LSHR` in `VarkaChronoLowering` 302 and 323. A
   shift by a vector count has never been emitted.
3. **Which parts need a later row.** Checked against the lanes as they are:
   * `TINYINT` and `SMALLINT` operands of `&`, `|`, `^`, `~` and `bit_count`: no such column is
     admitted until 329.
   * `bit_get`, `getbit`: a `TINYINT` result, written only once 329 admits the byte output.
   * `bit_count` over `BOOLEAN`: 331.
   * A `BIGINT` shifted by an `INT` column, and `bit_count(bigint)` under another node: mixed width,
     after 28. `bit_count(bigint)` as an output root needs nothing new - it is at most 64, and
     `NarrowLane` is admitted as an output root over values proven to fit (`VarkaVectorIR` 343),
     as the `TIME` extracts use it.
   * A `BIGINT` shifted by an `INT` literal: the count folds to a constant, single lane, wave 0.

   So wave 0 can fuse `&`, `|`, `^`, `~` over `INT` and `BIGINT`; the three shifts over `INT` by a
   literal or a column and over `BIGINT` by a literal; `bit_count` over `INT` and as a root over
   `BIGINT`; and both bitmap functions. That is 24 of the census's pairs and, by the census's
   types (the shift entries admit `INT` and `BIGINT` only, the bitmap ones `BIGINT` only), eight
   entries fused for every type they admit.
4. **`bit_count` on the int lane**: `pop32(x) + ((x >> 31) & 32)` is the 64-bit popcount of the
   sign-extended int (the high 32 bits are all ones exactly when `x < 0`); exhaustion over the
   2^32 ints checks it against `BitwiseCount`. The Vector API's `BIT_COUNT` lowers to `vpopcntd`
   only with AVX512_VPOPCNTDQ and to the JDK's nibble-table sequence on AVX2 (the integer reading,
   about 1.75 instructions per int row), so the row claims no speed-up for the count alone on
   AVX2 (2.9); 376's census records which host has which.
5. **The bitmap functions' lowering**: for `v > 0`, `v - 1` is non-negative, so the quotient is
   `(v - 1) >> 15` and the remainder `(v - 1) & 32767`; for `v <= 0`, the bucket is the truncating
   quotient `v / 32768`, which is `(v + ((v >> 63) >>> 49)) >> 15` (the sign trick
   `ConstDivide` applies), and the position `(-v) & 32767`, which equals `(-v) % 32768` for every
   `v <= 0` including `Long.MIN_VALUE`, where `-v` wraps to itself and both sides are 0. Two arms
   blended on `v > 0`. A full-range claim over a 64-bit value, so it goes to SMT
   (`dev/varka_prove.sh`), not to exhaustion, beside the named cases `Long.MIN_VALUE`, `-1`, `0`,
   `1`, `32768`, `32769`.

**What still needs a run.** Nothing gates the design: the operators are Java's own and the two
bitmap identities are checked by SMT as part of the work. One check is cheap and settles the
shift's emitted form before code: with a catalyst test build, `dev/varka_emit.sh "i << 3"
--columns i:int` today declines ("unsupported expression"), which confirms the arm is missing and
not merely undocumented; if it compiles, the row is coverage rows only for that shape.

## 3. The design

### 3.1 Two IR nodes and a popcount

* `BitOp(BitOpKind op, VarkaVectorIR left, VarkaVectorIR right)`, `op` one of `AND`, `OR`, `XOR`,
  `SHL`, `SHR`, `USHR`, both children on the node's lane (the shift count of a `BIGINT` is a long
  literal at wave 0), emitted as one `lanewise` - by a vector for a column count, by a scalar for
  a literal - through the lane's descriptors (`Lane.lanewiseVV`, and a lanewise-by-scalar shape
  for the literal count, generalizing `emitShift` beyond `IntVector`).
* `BitNot(VarkaVectorIR child)`, one `lanewise(NOT)`.
* `BitCount(VarkaVectorIR child)`: on the int lane `pop32(x) + ((x >> 31) & 32)`, on the long
  lane `pop64(x)` under a `NarrowLane` root.

A separate node rather than new `IntOp` members keeps `IntArith`'s overflow reasoning and the range
analysis's transfer function for it unchanged. `VarkaRangeAnalysis` answers `BitOp` and `BitNot`
as `UNKNOWN` except where the bound is free (`x & k` with a non-negative literal `k` is within
`[0, k]`, which lets a following checked add drop its check); `BitCount` is `[0, 64]`.

### 3.2 The bitmap functions

Compiled to the existing nodes plus `BitOp`, by section 2 item 5's two arms and a blend on
`v > 0`; no node of their own. Their SMT goals join the long-lane proof file's list as two claims,
stated over integers as `sql/varka/proofs/README.md` prescribes (the shift as floor division by
`2^15`, the mask as the non-negative remainder), with the file's time before and after recorded
(milestone risk 5).

### 3.3 What is deliberately unchanged

* `IntArith`, `IntNeg` and their overflow modes; `ConstDivide` is reused only as a pattern, not
  changed.
* `emitShift`'s existing callers keep their bytes; the generalization adds a path.
* Every part section 2 item 3 assigns to a later row.

### 3.4 Registered op counts

Expected, to be read with `dev/varka_emit.sh --table` once built and asserted by op-count tests:
`i & j`, `i | j`, `i ^ j`, `~i`, `i << 3`, `i << j` one `IntVector` invocation each, and the same
at the long lane; `bit_count(i)` four (the count, the shift, the `AND`, the add); `bit_count(l)`
one plus the narrowing store; `bitmap_bit_position(l)` about eight lane operations
(two arms of a subtract or negate and a mask, a compare, a blend), `bitmap_bucket_number(l)` about
ten. A count that differs is recorded with the tool's output, not reconciled by hand.

## 4. Files

* `.../codegen/varka/VarkaVectorIR.java`: `BitOp`, `BitNot`, `BitCount`
* `.../codegen/varka/Analysis.java`, `Slots.java`, `VarkaVectorWalk.java`: their analysis and
  emission; `emitShift` generalized
* `.../codegen/varka/Lane.java`: the lanewise-by-scalar shape for a literal count at either lane
* `.../codegen/varka/VarkaRangeAnalysis.java`: the transfer functions of 3.1
* `.../codegen/varka/VarkaEmitCostTable.java`: the new nodes' costs
* `.../codegen/VarkaNodeCompiler.java`: the compiler arms for the bitwise, shift, count and bitmap
  expressions
* test: `VarkaReference.java`, `VarkaIrGrammar.java`, `VarkaCanonicalIr.java`: the oracle, the
  fuzzer's productions, the canonical form
* test: a new `VarkaBitwiseSweepSuite.java` (`@VarkaSuite`): the exhaustion of section 5
* `sql/varka/proofs/` long-lane proof file, `VarkaProofFiles`: the two bitmap claims
* test: `VarkaCoverageSuite.scala`; `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`:
  rows, regenerated; new bytes rows only
* `sql/varka/plans/m8/census_curated.txt`, `census.tsv`: the fused types

## 5. Tests, and what each is for

1. **Exhaustion over every `INT`** for `~i`, `bit_count(i)` and each shift by every count in
   `[-64, 64]` (the masking), and over every pair in a sampled 2^32 slice for `&`, `|`, `^` against
   Spark's own expressions; the full 2^32 of the unary ones runs nightly, a fixed slice in PR CI.
2. **The bitmap functions by SMT** over the whole long, and their named cases as coverage rows.
3. **Coverage rows** for every fused pair at both widths, with the poisoned null lanes; the row
   engine is the oracle.
4. **A long shift by an int column declines** with a reason naming row 28; catches a mixed-width
   tree admitted before its lane conversion exists.
5. **`bit_count(l)` under another node declines** for the same reason.
6. **The IR fuzzer draws the new nodes** under the option matrix.

No existing fixture moves; the bytes oracle gains rows, and `emitShift`'s callers keep theirs.

## 6. The measurement

The row claims no speed-up for `bit_count` on AVX2 (2.9). The operators are one instruction each
on every host the milestone measures (376 checks); a throughput number would restate the int and
long lanes' existing ones. So no benchmark section is added; the sweep's time is the cost measured.

### 6.1 Predictions, registered before the run

1. **The census moves by 24 pairs and 8 fully fused entries** in wave 0 (section 2 item 3).
2. **The int-lane `bit_count` identity holds over all 2^32 ints** against `BitwiseCount`.
3. **Each unary 2^32 sweep takes under five minutes** on the laptop, against Spark's own
   expression per value: estimated at about one minute (2^32 interpreted evaluations at a few
   nanoseconds each, spread over the cores, as VARKA-281's sweeps were) and stated at five, the
   calibration table's ratio for a check whose cost is the harness's reads rather than the
   kernel's work (VARKA-274a's tier witness). The row has three such sweeps (`~`, `bit_count`, the
   shifts over their counts); whichever of 330's and this row's lands first times the
   milestone's first 2^32 sweep and starts the budget total of 10.3 item 5.
4. **The two bitmap SMT claims add under 5 s** to the long-lane proof file under Z3 on the laptop,
   being linear over integers with one case split.

## 7. Risks

1. **The shift count's masking.** The Vector API masks the count as Java does; Spark uses Java's
   operators, so they agree, and test 1's counts outside `[0, 63]` hold it.
2. **The range analysis's `AND` rule.** A wrong bound would take a check off a later add; the
   rule is stated as an SMT claim in the range-analysis proof file beside the others.
3. **The partial delivery reads as done.** The row's done-when spans `TINYINT` and `SMALLINT`;
   the row stays open after wave 0, its remaining parts listed in section 8, and the census shows
   which pairs remain.

## 8. Sequencing

1. This plan.
2. **335a, wave 0**: `BitOp`, `BitNot`, `BitCount` over `INT` and `BIGINT` as section 2 item 3
   lists, the bitmap functions with their proofs, the sweeps and the coverage rows.
3. **335b, after 28, 329 and 331**: the narrow operands, `bit_get` and `getbit` with 329's byte
   output, a `BIGINT` shifted by an `INT` column and `bit_count(bigint)` under another node with
   28, `bit_count(bool)` with 331. Each lands as a pull request of its own when its lane exists.

Explicitly out of this row: any speed-up claim for the popcount on AVX2, the `TINYINT` output
itself (329), and the lane conversion (28).

## 9. Outcome

*19 October 2026, 335a.* Wave 0's part landed: `&`, `|`, `^`, `~` over `INT` and `BIGINT`, the
three shifts over `INT` by a literal or a column and over `BIGINT` by a literal, `bit_count` over
`INT` anywhere and over `BIGINT` as an output root, and both bitmap functions. 335b (section 8
item 3) is untouched and the row stays open. Section 2's facts held on master `e6aa78e3cd5`: no
bitwise node, `IntOp` still `{ ADD, SUB, MUL }`, no compiler arm for any of the fifteen entries;
`i << 3` declined as an unsupported expression before the arms.

**What was built**, with the departures from section 3 marked.

1. **Two nodes, not three** (*departure*). `BitOp(op, left, right)` with `op` one of `AND`, `OR`,
   `XOR`, `SHL`, `SHR`, `USHR`, both operands on the node's lane, and `BitCount(child)`. `~x`
   compiles to `x ^ -1` with the literal on the operand's lane, which is one `lanewise` as
   `lanewise(NOT)` would be and saves a node its twenty switch arms. `BitCount` is
   `bitCount(x) + ((x >> 31) & 32)` at the int lane and the popcount at the long lane.
2. **One emission for both counts** (*departure*). A shift by a literal emits as a shift by the
   literal's broadcast vector, so `lanewise(op, Vector)` serves a literal count and a column count
   alike, the literal's value stays out of the IR as every literal's does, and `emitShift` and its
   callers are untouched; no lanewise-by-scalar shape was added to `Lane`. C2 emits `vpsllvd`,
   `vpsrlvq` and kin for both on the laptop. `Lane` gained only `lanewise(Unary)`, for the popcount.
3. **The compiler arms** are in the int arithmetic family (`intArithmetic`'s switch): the
   operators over `INT` and `BIGINT`; a `BIGINT` shifted by an `INT` literal carries the count as
   a long literal, the same count since the shift reads six bits of the sign-extended int; a
   `BIGINT` shifted by anything else declines naming VARKA-28; `bit_count` of a `BIGINT` is a
   `NarrowLane` root in `compileRoot` and declines under another node naming VARKA-28; the bitmap
   functions are matched as the `StaticInvoke` of `BitmapExpressionUtils` their replacement is.
4. **The bitmap lowering** is section 2 item 5's, written as one magnitude: `w = v > 0 ? v - 1 :
   -v`, the position `w & 32767` and the bucket `v > 0 ? (w >>> 15) + 1 : -(w >>> 15)`, a compare,
   a blend for `w` and one for the bucket, with no division and no `ConstDivide`. At
   `Long.MIN_VALUE`, `-v` wraps to itself, the mask gives 0 as Spark's `(-v) % N` does, and
   `w >>> 15` is `2^48`, whose negation is Java's truncating `MIN_VALUE / 32768`.
5. **The range analysis** answers `BitOp` as unknown and `BitCount` as `[0, 64]` (*departure*: no
   `x & k` rule, so no new claim for `range_analysis.smt2`). The count's bound is what takes the
   check off `bit_count(i) + 1` under ANSI, which the compiler suite asserts.
6. **The proof** is a file of its own, `sql/varka/proofs/bitmap.smt2` (*departure*: not appended
   to the long-lane division file), rendered by `VarkaProofFiles` from Spark's `NUM_BITS`: both
   identities for every long, the domain admitting `Long.MIN_VALUE`, and three neighbours (the
   shift one less, the arithmetic shift, the mask a bit narrower) refuted. Its logic is `ALL`:
   cvc5 answered the position's claim unknown at the 10 s limit under `QF_NIA`.
7. **The sweeps** are in `VarkaEmitterBitwiseSuite`, a Scala emitter suite in its own JVM
   (*departure*: not a JUnit `VarkaBitwiseSweepSuite`), because the nightly's sweep step selects
   `*VarkaEmitter*Suite -- -z opt-in` and the suite's helpers are the emitter base's. Under
   `-Dvarka.sweep=true`: every int through `~` and `bit_count` against Spark's `BitwiseNot` and
   `BitwiseCount`, and every int through each shift with its count cycling over `[-64, 64]`,
   against `ShiftLeft`, `ShiftRight` and `ShiftRightUnsigned`. On every build: a 2^24-int strided
   slice of the same, and `&`, `|`, `^` over a 2^24-pair slice. A shift's two operands are 64
   bits, past section 5's exhaustion rule, so every int meets one count per row rather than all
   129 (*departure* from section 5 item 1's "every count" over every int, 129 times the work).
8. **The fuzzer** draws both nodes at both lanes, the series enumerates them, and the template
   warm-up passes `BIT_COUNT` with its other unary operators, since a kernel now calls it.
9. **Coverage**: 18 rows in a family of their own, 137 rows from 119; the census's fused pairs
   122 to 146 and its fully fused entries 15 to 23. The coverage table now escapes a `|` inside
   a cell, which `i | 4096` was the first row to hold.

**The registered op counts** (section 3.4), read with `dev/varka_emit.sh --table` at the
defaults, the dump's lane-call count per kernel with its load and store: `-i`, a one-op node, is
4, and so is every `BitOp` row (`i & j`, `i | j`, `i ^ j`, `~i`, `i << 3`, `i << j`, `i >> j`,
`i >>> 3`, `l & l2`, `~l`, `l << 3`, `l >>> 63`), as registered; `bit_count(i)` is 6, three past a
one-op node, so four ops as registered; `bit_count(l)` is 4, one op and the narrowing store, as
registered; `bitmap_bit_position(l)` is 10 and `bitmap_bucket_number(l)` 13, six and nine past
the one op of the others' count, against the registered "about eight" and "about ten" lane
operations. C2's code for the dense loop on the laptop (`--asm`): `vpopcntd`, `vpsrad`, `vpandd`,
`vpaddd` for `bit_count(i)` and `vpopcntq` for `bit_count(l)`, with no boxing in either; AVX2's
form was not run, and no speed-up is claimed (section 6).

**What moved with the fuzzer.** The grammar's new arms move every later draw of both lanes, as
VARKA-235's did, and the emit cost model is fitted on those draws. A first run let them reach the
cost corpus: every price was refit, the bytes margin moved from 0.209 to 0.171 and the sites
margin from 0.182 to 0.204, and with them the planned grouping of shapes with no bitwise node in
them - `VarkaEmitterBudgetSuite`'s ladder of 64 cheap tails went from 6 loop methods to 8, a
benchmarked shape. So the cost corpus now draws without the bitwise arms
(`VarkaIrGrammar.drawShape(rnd, false)` and its three siblings, which make the draws the grammar
made before them), and the new features are priced on the register's probes over columns, as a
lowering variant the grammar never draws already was (*departure*: section 4 did not foresee it).
The table then moved by its new entries and by about a tenth of a percent on the existing prices,
the probes' rows joining the regression; the margins, the ladder's counts and the shapes that gain
a loop method under `predictGrouping` are master's. `emitted_bytes.json` gained the 18 rows at both
widths; its fuzz blocks, drawn from the whole grammar, and its option-arm hashes moved, and no
existing coverage row's bytes moved. `width_audit.json`, regenerated on the host it names, gained
the rows: none is refused at the preferred width, and at 128 bits the bitmap rows carry the
long-lane compare and blend refusals every long-lane row with a comparison has. Three tests of
`VarkaIrFuzzSuite` pinned to draws of the old fuzz sequence that grow past the op cap needed new
draws, found by scanning the same nights' seeds: (2026101303002, 4259) for the two skip tests,
(2026101307006, 32279) for the rebuilt arm and (2026101300002, 195413) for the stranger.

**The test run**: `VarkaEmitterBitwiseSuite` 9 of 9 with the full sweeps on (74 s in its own JVM,
of which the two unary sweeps 14.2 s and the three shift sweeps 47.6 s);
`VarkaExpressionCompilerSuite` 149 of 149 with its four new tests; the catalyst `*Varka*` suites
with the memory sanitizer on, all passing but `VarkaTierWitnessSuite` (9.2 item 6), which passes
with it off, and the JUnit suites among them (range rules, cast matrix, proof files) in a run of
their own;
`VarkaCoverageDifferentialSuite` 368 of 368 against Spark's row engine, the new rows' int-valued
forms under a later `COALESCE` argument and `bit_count(l)` under a `CASE` arm, a `COALESCE` and an
`AND` joining its list of positions Varka does not serve, for the reasons the rows they sit beside
give (an int column as a `COALESCE` value, item 55, and a narrowed int, VARKA-28);
`dev/varka_prove.sh` under both solvers; the census check; scalastyle and checkstyle.

### 9.1 The predictions

| | prediction | measured | |
| :-- | :-- | :-- | :-- |
| 1 | the census moves by 24 pairs and 8 fully fused entries | 122 to 146 pairs, 15 to 23 entries | held, exactly |
| 2 | the int-lane `bit_count` identity holds over all 2^32 ints against `BitwiseCount` | the full sweep passed | held |
| 3 | each unary 2^32 sweep under five minutes (about one minute estimated) | `~` and `bit_count` 14.2 s together, about 7 s each; each shift sweep about 16 s | held, at about 0.12 of the estimate |
| 4 | the two bitmap SMT claims add under 5 s under Z3 | 0.03 s under Z3 and 0.07 s under cvc5 for the file's six checks | held |

Prediction 3's estimate priced a few nanoseconds per interpreted evaluation spread over the cores;
the sweep ran on the laptop's 24 threads beside other agents' JVMs at about 25 ns of CPU per
evaluation, and the parallel stream kept about eleven cores busy, so the wall time fell well under
the minute. Calibration entries `VARKA-335-6.1-3.md` and `VARKA-335-6.1-4.md`.

### 9.2 What the work found

1. **A dead month step in a materialized prefix** (not this row's; a proposed row below). With the
   bitwise arms in the cost corpus, `VarkaUnreadLocalsSuite` found an unread reference local in a
   wide shape, and it reduces to four roots of master's nodes alone, `add_months(d1, i2)`,
   `(i0 + i2) + year(d1)`, a `ThursdayOf` and a `make_date` over a third date, grouped
   `[0, 1], [2], [3]` with `d1`'s prefix materialized: `year(d1)` and `add_months(d1, i2)` are
   two fragments of one date, keyed on different words, and the second to be emitted computes the
   month step because the materialized prefix needs it (`mat.needsMonth()` in
   `VarkaChronoLowering.emitChronoPrefixOnce`) though the first already stored it, so the step
   lands in a local nothing reads. It disappears with `materializeChronoPrefix` off. The fix is to
   compute the month for the store only in the fragment that stores; it is a wasted store, not a
   wrong answer, and with the corpus restored the suite no longer reaches it, so it is recorded
   here rather than fixed in this row.
2. **cvc5 and `QF_NIA`**: the position's claim, a remainder of a wrapping negation, came back
   unknown under `QF_NIA` and holds in 0.07 s under `ALL`, the first remedy the proofs' README
   lists.
3. **A coverage cell may hold a `|`**: the table now escapes it.
4. **The new prices are thin.** Priced on one probe over two columns each, the `BitOp` kinds
   come out alike and their masked methods' byte prices negative (about -96 at the int lane and
   -155 at the long), as other probe-only variants' already are; the fit cannot tell the node
   from its columns' reads there. A group of many bitwise nodes is then under-predicted in its
   masked methods, which the byte budget's measurement of the built class still corrects, so it
   costs a rebuild and not a wrong size. Pricing them on occurrences needs a corpus that holds
   them, which is the refit this row declined; it is a question for the next refit.
5. **The census calls the shifts `done`**: the script marks an entry done when every type it
   admits is fused, and both are, by a literal count; the `BIGINT` shift by a column that 335b
   owes is a form of a fused pair, so it is carried in the entries' note.
6. **The test run with the memory sanitizer on** fails `VarkaTierWitnessSuite`'s warmed history
   with a `speculate_null_assert` trap in `VarkaMemorySanitizer.check`; the suite measures the JIT,
   which the sanitizer's branches change, and it passes with the sanitizer off, as the gate runs
   it.

### 9.2a Round two: stacked on row 334 and row 28, and the cost model refit (20 October 2026)

*Departure, on the owner's word of 19 October 2026: the emit cost model is refit on the whole
grammar, bitwise arms included, and the flag that kept the cost corpus on the earlier draws is
gone.* What section 9 says above about the held corpus is the first round's record; this is what
replaced it.

**Stacked.** The branch merged `origin/master` at `0eda4cb66ce` (row 28: kernels of both lanes,
`WidenLane` and `NarrowLane` anywhere) and then row 334's branch (`IS NULL` and `IS NOT NULL` over
a predicate, its own grammar draw and the `IsNotNull/cond/<lane>` cost feature, and the month-step
fix 9.2 item 1 proposed). Merge conflicts were in the exhaustive switches beside `WidenLane`, the
compiler's root (row 28 removed the `TIME` root arm; the `bit_count(bigint)` root arm stays), the
differential suite's declined list and the generated files, which were regenerated.

**Found by the merge: the bitwise emitters took the kernel's lane, not the node's.** A kernel of
both lanes holds int-lane nodes under a long-lane kernel and the reverse; `emitBitOp` and
`emitBitCount` read `analysis.lane`, so a `BitCount` of an int under a long kernel called
`LongVector.lanewise` on an `IntVector` and the class failed verification (`VarkaIrFuzzSuite`'s
mixed-lane draw 719). Both now read `analysis.laneOf(node)`, as row 28's emitters do.

**Row 28 and 335b.** With `WidenLane` and `NarrowLane` admitted anywhere, both 335b items that
named row 28 are now a few lines each: a `BIGINT` shifted by an `INT` column is a `BitOp` over a
widened count, and `bit_count(bigint)` under another node is a `NarrowLane` over the count. Neither
is built here; their declines now name 335b instead of a pending row 28, and an operator between
an `INT` and a `BIGINT` operand, which the analyzer widens with a cast, fuses through row 28's
`WidenLane` with no change of this row's.

**The refit.** On the merged corpus with the bitwise arms in it:

| | master | merged base (334) | refit |
| :-- | --: | --: | --: |
| bytes margin | 0.209 | 0.205 | 0.155 |
| sites margin | 0.182 | 0.176 | 0.206 |

Every price moved; the bitwise nodes are priced from their occurrences in the draws. The pins that
moved, each checked to be the refit's (the same assertions hold, only the counts or the draws
changed): `VarkaEmitterBudgetSuite`'s 64-tail ladder, 6 to 8 loop methods with the prefix
recomputed and 4 to 5 materialized, at every fused ceiling, one build each; the wide shapes that
gain a loop method under `predictGrouping` with the greedy grouping, `wide long` 84 and 184 to 78
and 82; and `VarkaIrFuzzSuite`'s op-cap draws, redrawn by the arms and found again by a scan,
(2026101307006, 32279) for the rebuilt arm and (2026101300002, 195413) for the stranger. No
coverage row's emitted bytes moved.

**The benchmark**, on the quiet laptop after the night fuzz job (20 October 2026, 03:37 to 04:45,
load under 1 at each start, canary ok, `powerprofilesctl` performance, battery "Not charging").

*Emission* (`VarkaEmissionBenchmark`, regenerated at both widths and committed): the 64 cheap
tails take 875 us to emit predicted (863 before) at 256 bits and 866 us (884) at 128, inside the
file's spread, which the weights arm, untouched by the prices, shows at 1 to 5%. No row moved past
the file's noise.

*Running the kernels* (`VarkaSharedPrefixBenchmark`, which runs the ladder over a million rows;
256-bit lanes paired with `dev/varka_bench_pair.sh` against the merged base `ca586f1dce6`, five
alternating rounds; 128-bit lanes one run each side in the same session):

| 64 tails over one date | base | refit | change |
| :-- | :-- | :-- | :-- |
| prefix per group, 256-bit | 6 methods, 3.0 ns/row in 5 of 5 runs | 8 methods, 3.6 ns/row in 5 of 5 | **20% slower** |
| prefix per group, 128-bit | 6 methods, 8.1 to 8.2 ns/row | 8 methods, 9.5 to 9.6 ns/row | **17% slower** |
| prefix once (the default), 256-bit | 4 methods, best 2.9 ns/row; 106 to 268 ns/row in 4 of 5 runs at ceiling 400 | 5 methods, 2.6 ns/row in 5 of 5 | 10% faster by best, and stable |
| prefix once (the default), 128-bit | 4 methods, best 8.0 to 9.7 ns/row at two ceilings, 346 to 349 at two | 5 methods, best 7.0 to 7.7 ns/row | no slower by best |

So the two extra methods of the recomputed arm cost about a fifth of its throughput at both
widths, past the file's noise: the refit is materially slower there. That arm is not the default
(`materializeChronoPrefix` is on), and on the default arm the refit is no slower. Both sides of
the default arm fall at times into a slow mode a hundred times slower, the base more often; the
committed master file (12 October) shows neither, so it arrived after it, and it is recorded as a
finding (9.2a, last paragraph), not this row's. The committed `VarkaSharedPrefixBenchmark` files
are the refit's run; one of its wide rows (ceiling 50, prefix once) caught the slow mode.

**Round three, 20 October 2026: the slow mode bisected, stopped part-way** (every number in
`results/VARKA-335-slow-mode-bisect.txt`). Five alternated rounds of the whole benchmark per
commit, slow meaning a computed-once cell past 20 ns/row: master's 12 October commit
`a93cb83e706` 0 of 25 cells (0 of 5 rounds), the Java port `25a1bae6893` 0/25 (0/5), the commit
before row 28 `05242b26d2e` 10/25 (5/5), row 28 `0eda4cb66ce` 10/25 (4/5), row 334 `54bb641d947`
20/25 (5/5), this branch's `00d9f79ec79` 3/25 (2/5). The fast mode also moved, 2.3 to 2.4 ns/row
before to 2.7 after. So the slow mode predates rows 28, 334 and this one. A bisect over the 37
product and harness commits between `25a1bae6893` and `05242b26d2e` found `3f6f450bd71`
(14 October) good in three runs and was stopped there by the coordinator: the cause is in the 19
such commits from `3f6f450bd71` to `05242b26d2e`, which hold the Vector API template warm-up
(`aa457c3797b`, `568582a9f77`, `089f7659b1b`, `e6aa78e3cd5`). Not yet known: the commit, and the
JIT's account of a slow round (no PrintCompilation, deoptimization or allocation log was taken).

**Finding: the materialized ladder's slow mode.** At 4 or 5 loop methods with the prefix computed
once, the 64-tail kernel runs either at 2.6 to 2.9 ns/row or at 65 to 349 ns/row, run to run, at
both widths, on the merged base and on the refit alike; master's committed run of 12 October
holds 2.4 and 5.0 throughout. It looks like a kernel that stopped scalar-replacing its vectors
(the VARKA-295 family) and wants a row of its own: reproduce with `-XX:+PrintCompilation` and the
tier witness on the base, and bisect over rows 28 and 334.

### 9.3 What row 28 opens for 335b

A `BIGINT` shifted by an `INT` column (the count widened in the kernel), `bit_count(bigint)` under
another node (a narrowing inside the tree), and an operator between an `INT` and a `BIGINT`
operand, which the analyzer widens with a cast, all wait on row 28's conversion; `bit_get`,
`getbit` and the narrow operands on 329's byte and short lanes; `bit_count(bool)` on 331's.

### 9.4 335b, first two items (20 October 2026)

The two items that waited only on row 28 are built: a `BIGINT` shifted by an `INT` column or
expression, and `bit_count(bigint)` under another node. Section 9.3's reading held: both were a few
lines, and no hidden dependency turned up. `dev/varka_emit.sh` on `shiftleft(l, i)`,
`shiftright(l, i)` and `shiftrightunsigned(l, i)` over `l BIGINT, i INT` declined each ("a bigint
shifted by an int count that is not a literal is VARKA-335's second part"), and on
`bit_count(l) + 1` declined ("bit_count of a bigint fuses at an output only"); after the
change all four fuse as `(bit:SHL col:0:long (widen col:1))` and its two siblings, and
`(int:ADD:WRAP (narrow (bitCount col:0:long)) lit:0)`; `bit_count(l)` alone, unchanged, is
`(narrow (bitCount col:0:long))`.

**What changed.** `VarkaNodeCompiler.shift` builds a `BitOp` over the long value and a
`WidenLane` over the compiled int count (an int literal still folds to a long literal, as before);
the `BitwiseCount` arm over a `BIGINT` is `NarrowLane(BitCount(x))` as a node, so `compileRoot`'s
special case and `bitCountAtRoot` are gone and the arm serves roots and interior positions alike.
`VarkaRangeAnalysis` answers `[0, 64]` for a `NarrowLane` over a `BitCount`, as it already did for
the int lane's count, so `bit_count(l) + 1` compiles to a wrapping add rather than a checked one.
The emitter, the IR and the cost table needed nothing: `BitOp` already read its node's lane, and
`WidenLane` and `NarrowLane` are row 28's.

**The count's mask.** Spark's shifts are Java's `long << int`, which reads the count's low six
bits. A sign extension keeps the low 32 bits of the count and so its low six, which is the mask
the long lane's `LSHL`, `ASHR` and `LSHR` apply to a vector count; so -1 shifts by 63, 64 by 0,
65 by 1 and `Integer.MIN_VALUE` by 0, as in the row engine, and a null in either operand nulls the
row. 335a's own proofs and sweeps cover the int lane's five-bit mask and the long lane's literal
counts and are unchanged; what is new is the widening, which a test at each of three levels reads:

* `VarkaEmitterBitwiseSuite`, a kernel of both lanes at 2 and 8 long lanes: six roots (three shifts
  by a widened int column, a shift by a widened narrowed popcount of its own value, an XOR with a
  widened int expression of a narrowed popcount, and the narrowed popcount) over 10 long edge values
  (both extremes, -1, 0, 1, a bit at 32, the alternating patterns) against 13 counts (-1, 0, 1, 31,
  32, 33, 63, 64, 65, 127, 128, `Int.MinValue`, `Int.MaxValue`), every value meeting every count
  twice, under all sixteen pairs of the four null patterns, against `v << c`, `v >> c`, `v >>> c`
  and `Long.bitCount` computed in the test.
* `VarkaDifferentialSuite`, against the row engine end to end: 9 values (the same edges, two values
  just past the int range, and a null) crossed with 14 counts (the 13 and a null), ten
  expressions over them, each fused and each answering what the row engine answers, a second run
  served from the warm cache.
* `VarkaExpressionCompilerSuite` pins the IR of each shape, and the three coverage rows below run
  through `VarkaCoverageDifferentialSuite`'s fixtures, its partition oracle and its ANSI form.

**Bookkeeping, all regenerated by tools.** Three coverage rows (`shiftleft(l, i)`,
`shiftright(l, i ^ 5)`, `bit_count(l) + 1`), and the notes of `l << 3` and `bit_count(l)` lost
their "not fused yet" and "as an output only" clauses; `emitted_bytes.json` moved by exactly those rows at both lane
counts and the nine aggregate option-arm hashes, no existing row's method hash changed;
`width_audit.json` gained the rows, whose 128-bit entries carry the refusals every long-lane row
with a mask carries (a mask broadcast, a masked load, a mask test at two long lanes,
`VARKA-153`), none at the 256- and 512-bit widths; the census notes of the shifts and `bit_count`
changed and its counts did not. The differential suite's declined list lost `bit_count(l)`'s three
positions, which a kernel now serves, and its int-valued list gained the two rows.

**Not touched, as asked.** `bit_get`, `getbit`, the `TINYINT`, `SMALLINT` and `BOOLEAN` operands
and `bit_count(bool)` wait on rows 329 and 331; the row stays open for them. No fuzz-grammar draw
was added: the grammar draws `BitOp` and `BitCount` over any value of the lane, so the new shapes
were already reachable at the IR level and the fuzzers ran at their default iterations unchanged.
The cost model was not refit: the shapes are compositions of priced nodes.

