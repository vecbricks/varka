# VARKA-329 and VARKA-330: `TINYINT` and `SMALLINT`, the lane and the arithmetic

*Rows 329 and 330 of `m8/PLAN.md`, wave F1, one file because the two share one design: 329 is the
lane (a byte or short column is read, compared, chosen and written, in two representations),
330 is the arithmetic on it (`+`, `-`, `*`, negation, `abs` and the `try_` forms in the three
modes, in both). Planned on 21 October 2026 at `a429af749f4`, from `m8/PLAN.md` 1.1, 2.3, 2.4, 3,
4.1 and 10, the code on master, `m8/VARKA-28.md` 3.2 and 9, `m8/VARKA-376.md` 9 and the census
files, and `m8/VARKA-39.md` 0 as the model of an admission check. Nothing here is measured yet;
every number below is either read from a committed file (and says which) or is a prediction with
its bound.*

## 0. Admission check against master (21 October 2026, `a429af749f4`)

What the code says now, each name verified by `grep` on this commit:

1. **No narrow type reaches a kernel anywhere.** `VarkaNodeCompiler.laneOf(DataType)` answers
   `INT` for `IntegerType`, `DateType` and `YearMonthIntervalType`, `LONG` for `LongType`,
   `TimeType` and `DayTimeIntervalType`, and empty for everything else, so a `ByteType` or
   `ShortType` expression declines at its first leaf. `VarkaEvaluatorBase.isArrowBacked` has no
   `TinyIntVector` or `SmallIntVector` case (its `default` refuses the batch) and
   `VarkaKernelEvaluator.allocateVector` has no branch for either type: the last `else` throws
   `no Varka output vector for ...`. Both need a case; the comment on `isArrowBacked` says
   admitting a type is "exactly one more case", which holds for the read side.
2. **The IR has two lane types and no byte.** `VarkaVectorIR.LaneType` is `{INT, LONG}` and
   `Lane` (the emitter's width table: vector class, species, byte stride, derived descriptors) has
   the same two members; `Lane.byteStride` is 4 and 8. `Lane`'s own comment says a lane is added
   by extending the enum, and `VarkaLaneTypeSuite` pins every derived descriptor.
3. **The emitter already separates "the lane a value is computed in" from "the lane an input is
   stored at".** `Analysis.laneOf(node)` names the instructions' vector class and species;
   `Analysis.inputLane(ordinal)` sizes the column's data segment (from `inputLanes`, filled from
   each `ColumnRef`'s lane at line 1098 of `Analysis`). Today the two always agree for a column.
   The widened representation is exactly the case where they differ (stored as bytes, computed as
   ints), and the native one is the case where they agree. That is the structural reason both arms
   can share one IR (section 2).
4. **The mixed-lane machinery is "drive at the widest lane, narrower values fill the low part of
   their own species"** (`VARKA-28.md` 3.2 and 9; `Analysis.mixed()` is defined for a kernel
   driven at the long lane only, and `narrowsAtStore` is a long-to-int root). It was chosen over
   the pair representation by 28's A/B, because a masked load costs two to five unmasked ones
   (`VARKA-28.md` 9.3, `spikes/lane-conversion/results/hand-loop.txt`): the loop stops one group
   early (`loopBound(length - lanes)`) so every loop iteration loads whole, and the epilogue is
   masked. A narrow lane has to meet this, and meets it differently in the two arms (2.3).
5. **`GuardedRange(child, lo, hi)` exists on either lane** and declines a batch when a live lane
   leaves `[lo, hi]`, leaving the row engine to raise Spark's error on the row (`VARKA-39.md` 0).
   It is the range check ANSI needs for a byte or a short (4.2) with no new node.
6. **Checked int multiply declines unless the operands' bounds prove no overflow**
   (`VarkaNodeCompiler.arithOver`: a `FAIL` or `NULL` `MUL` with no proof is declined with "checked
   int multiply whose operands do not rule out overflow"). A byte or short operand has a known
   range from its type, so the proof is free: the int product of two shorts is at most 2^30 in
   magnitude and of two bytes 2^14, and no int multiply of two narrow values overflows. This is
   what makes the widened arm's `*` possible in every mode while `INT * INT` still declines
   (4.1), and it is not in the old row text.
7. **`abs` over integers is not compiled.** `case Abs` appears only in `VarkaIntervalCompiler` for
   year-month intervals; `UnaryMinus` over an int is (`VarkaNodeCompiler` line 390,
   `IntNeg`). Row 330's `abs` is therefore new work, but not a new node: Spark's own ANSI codegen
   for `Abs` over a byte or short is `c < 0 ? negateExact(c) : c`
   (`arithmetic.scala` `Abs.doGenCode`), which is `IfElse(Compare(LT, x, 0), IntNeg(x), x)` over
   nodes that exist (the same "compose, do not add" finding as 39's).
8. **Spark's semantics, read in `arithmetic.scala` and `numerics.scala`.** `Add`, `Subtract` and
   `Multiply` over two `TINYINT` columns are `TINYINT` (`BinaryArithmetic.dataType` is the left
   type). LEGACY codegen is `(byte)(a op b)`. ANSI and TRY are both `failOnError` (the match in
   `BinaryArithmetic.failOnError`), which calls `ByteExactNumeric.plus`, `minus` or `times`:
   the int result is computed and `checkOverflow` raises when it is outside `[-128, 127]`
   (`ShortExactNumeric` the same at sixteen bits); TRY is that wrapped in a `try`/`catch` that sets
   the result null. So a TRY overflow is a null, an ANSI one an error, a LEGACY one a wrap, at the
   type's width, and the check is per node. Negation (`UnaryMinus`) under ANSI is
   `MathUtils.negateExact` (raises on `Byte.MinValue`, `Short.MinValue`), under LEGACY
   `(byte)(-x)`; `Abs` likewise, with its own `failOnError` flag. There is no `try_negative` and
   no `try_abs`, so `NULL` never reaches `IntNeg` (as the IR already says). `b + 1` (a `TINYINT`
   with an int literal) is `CAST(b AS INT) + 1`, an int-lane shape; only `b + 1Y` or two byte
   columns are narrow arithmetic. A comparison of a byte with an in-range int literal arrives from
   `UnwrapCastInBinaryComparison` already on the byte column.
9. **The census, read cell by cell** (`sql/varka/operator_census.json`, and
   `plans/m8/results/VARKA-376-census-epyc-7763.json` and `-neoverse-n2.json`; the verdict field is
   `I` for intrinsic). All 34 byte and short cells are `I` at 512 and 256 bits on the laptop, and at
   256 on the EPYC 7763; the laptop and EPYC at 128 bits and the Neoverse N2 (128 only) are `I`
   for 28 cells. **The other six cells at 128 bits are skipped, not intrinsic**:
   `int.B2I_load`, `byte.narrow_then_full`, `byte.full_then_narrow`, `byte.both_in_one_loop`,
   `byte.widen_then_full` and `byte.full_then_widen` carry `needs a preferred species of 256 bits
   or more`. The row's "intrinsic at all three widths on all three hosts" (`PLAN.md` 2.4 and 376's
   row) is true of the operators and false of the widening load and of the two-species case at 128
   bits, which is where the Neoverse N2 and the forced-width arm run. The reason is structural:
   the matching byte species for a four-lane int species would be 32 bits, and the Vector API has
   no 32-bit shape (`S_64` is the smallest), so a byte load widened onto the int lane at 128 bits
   has no whole-group form (2.3). The census has **no cell for `short` loads or two-species**
   (only `short.S2I` the conversion), none for `NEG`, `ABS`, `AND`, `OR`, `XOR`, `NOT`, `MIN`,
   `MAX`, `GT` or a byte `blend`, and none for any high multiply, so none is assumed here.

## 1. What the old text assumed that is not so

| old text | what the code says |
| :-- | :-- |
| the native arm gets a mixed `TINYINT` and `INT` projection for free as a quarter of the lanes | a mixed kernel is driven at its widest lane; the native byte value then fills 1/4 of a byte vector (a part-width species), which keeps no more rows per operation than the widened arm. The native arm's advantage is only in kernels whose every value is narrow (2.3) |
| `*` widens "in both arms" and an int multiply has no overflow test | true of the int lane for `INT`, but the narrow operand ranges make every checked narrow multiply provable with no 64-bit product (0, item 6) |
| the byte cells are intrinsic at all three widths | the widening load and two-species cells are skipped at 128 bits, and no byte-output, `short` load or `NEG`/`ABS`/bitwise cell exists (0, item 9) |
| a three-node arithmetic chain is 329's shape | arithmetic is 330 and follows 329; the benchmark builds its IR by hand, as `VarkaLaneConversionBenchmark` does (3.4) |
| the packed boolean output of a byte compare is a 329 shape | boolean outputs are row 27's and the mask machinery 331's and 375's; none has landed, so that shape is measured on a hand-written reference loop first and on the emitted kernel after 27 (3.4) |

## 2. The shared design

### 2.1 One IR, two emissions

`LaneType` gains `BYTE` and `SHORT`; the IR is written once in Spark's own terms. `ColumnRef(n,
BYTE)` is a `TINYINT` column, `IntArith(ADD, mode, a, b)` over two byte-typed children is Spark's
byte add with that mode, `Compare`, `IfElse`, `Greatest`, `Least`, `IsNotNull` and `InRanges` take
the narrow lane exactly as they take the int one. The emit option decides how the emitter names the
lane (the way `mulHiDivide` and `narrowHalfSpecies` decide a lowering without touching what the
tree means):

* **`narrowLanes = WIDENED`**: `Analysis.laneOf` answers `Lane.INT` for a `BYTE` or `SHORT` node
  and `inputLane` answers the narrow lane. A column is one widening load, a node is an int-lane
  operation, a root is narrowed at its store.
* **`narrowLanes = NATIVE`**: both answer the narrow lane.

The option is a `Flag`-like entry with `Reason.MACHINE` in `VarkaEmitOption.TABLE` (the reason
exists for exactly this: "which form wins depends on the machine"), so `VarkaEmitOptionSuite`
requires its table entry and the IR fuzzer draws it, and the weekly option matrix runs both
(`VARKA-248.md` 3.2). The tree's meaning is the same in both, so the oracle for one is the oracle
for the other and the differential suites need no per-arm expectation. The default is chosen in
3.5; the IR does not depend on it.

**Overflow proofs are lane-width aware (review of 21 October 2026, 9.1).** `IntArith(ADD, mode, a,
b)` over two byte children means overflow at BYTE width, so `FAIL` there is a byte-range check.
`VarkaNodeCompiler.arithOver` rewrites `FAIL`/`NULL` to `WRAP` when `cannotOverflow` holds, and
`cannotOverflow` is `boundedByIntMax`, a proof against the INT range (`VarkaNodeCompiler.java`
around lines 753 to 780, read). For two byte operands that proof is always true, so `127Y + 1Y`
under ANSI would compile to a silent wrap. Rule: `cannotOverflow` and `boundedByIntMax` take the
node's lane width, and for a BYTE or SHORT node `FAIL`/`NULL` is never rewritten to `WRAP` by an int
bound. A narrow checked node keeps its mode in the IR; the widened arm lowers it to an int
operation followed by the range form of 4.2, and the multiply's "cannot overflow the int" proof
lives in that lowering (the int product of two narrow values never overflows), not in `arithOver`.
Test: `127Y + 1Y` (through columns, 4.4) raises under ANSI on both arms.

**The mixed-width node (decided in the plan, object if wrong).** `WidenLane` and `NarrowLane` are
hard-wired: `WidenLane` throws unless its child is INT and always yields LONG, `NarrowLane` needs a
LONG child and yields INT (`VarkaVectorIR.java` around lines 376 to 415, read), and the walk emits
`I2L` and `L2I` only. The mixed kernel `b < i` needs a BYTE-to-INT widening, which neither node
can express. Choice: **extend the two records with a target lane, no new node**:
`WidenLane(child, target)` and `NarrowLane(child, target)`, with the one-argument forms kept as the
existing int-to-long and long-to-int. The constructor check becomes "the child's lane is strictly
narrower than the target for `WidenLane`, strictly wider for `NarrowLane`" over the order
BYTE < SHORT < INT < LONG, so the protection survives (a same-lane or wrong-direction conversion,
which would hide a missing conversion elsewhere, still throws; a BYTE child under a LONG target is
legal because Spark's `CAST(byte AS BIGINT)` is one conversion). Reason: it adds zero IR nodes and
zero sealed-switch sites (the existing `case WidenLane n` arms, about 30 sites in 9 files, get a
conversion chosen by the pair (child lane, target)), where a new node would add a site to each
of those switches. Under WIDENED, `Analysis.laneOf` already answers INT for a narrow node, so a
`WidenLane(byte, INT)` lowers to nothing; under NATIVE it is `B2I` (or `S2I`), part 0, from the
narrow species of the int species' width (2.3). The mixed-kernel test is **IR-level** (hand-built
trees, `VarkaReference` as the oracle); the SQL-level `b < i` waits for 332's casts, which is
where `VarkaConditionCompiler.onLongLane`'s `WidenLane(c.left())` (an INT-to-LONG builder that
would throw for a BYTE child) gets its narrow case.

**A boolean column beside a narrow sibling.** A `BoolColumn` (`VARKA-331.md` 2.1) takes the lane of
its sibling, BYTE and SHORT included, as an IR lane; under WIDENED `Analysis.laneOf` still answers
INT. The bit read is independent of the stored width (`validityBitsAt` handles 1 to 64 lanes), so
`And(BoolColumn, Compare(BYTE, ...))` passes `requireSameLane`.

### 2.2 What each arm costs in code

* **Widened.** `Lane` gets no new instruction family. Two narrow `Lane` entries still exist (a
  load width and a stride of 1 and 2) but only as storage descriptors: `BYTE`/`SHORT` rows of the
  table with `byteStride` 1 and 2, the load `ByteVector.fromMemorySegment(<narrow species>)` and
  the store `intoMemorySegment`, and the conversions `B2I`, `S2I`, `I2B`, `I2S` as
  `convertShape` calls, following 28's rule that the target species is loaded from the named
  species field, not a local (a local made C2 refuse the conversion; `VARKA-28.md` 9.3). The int
  lane's arithmetic, compare, blend, `GuardedRange`, `IntNeg` and the choice nodes are reused
  unchanged. New code: the load and store, the narrow-aware wrap and range forms (4.2), the narrow
  species selection (2.3). Small.
* **Native.** Two full `Lane` entries: vector class, species, derived descriptors (the overload
  trap in `Lane`'s comment returns: `ByteVector.broadcast(VectorSpecies, long)` coexists with the
  `byte` form, and the scalar of `lanewiseVI`, `compareVI` and `broadcast` must be the lane's own
  type, which makes the byte case a third scalar type that `runDesc` and the literal tables do
  not have: literals are `int[]` and `long[]` today, so a narrow literal reads the `int[]` and is
  narrowed with `i2b` or `i2s` before the broadcast). Every emitter site that asks `Lane` for a
  descriptor works for free; every site that assumes a scalar of `int` or `long` (the array a
  scalar argument comes from, `localWidth`) takes a narrow case; the `Lane` constructor's
  `localWidth` branch (`scalar.equals(ConstantDescs.CD_long) ? 2 : 1`, `Lane.java` around line 165,
read) needs no change for a byte or short scalar (one local slot), but `preferredLanes` (around
lines 166 to 168) knows only the `IntVector` and `LongVector` species and needs a byte and a short
arm (the preferred lane count of a byte or short species, read once). The checked arithmetic (4.3) needs the saturating
  operators and, for `*`, a widening round trip. Medium; the emitter's forty sites are the
  measure, and `VarkaLaneTypeSuite` pins each new descriptor against a hand-written expectation.

Both arms need the same non-emitter changes: the `isArrowBacked` cases and `allocateVector`
branches (`TinyIntVector`, `SmallIntVector`, built from the Arrow field Spark's own writer would
build, as the long-lane destinations already are), `VarkaNodeCompiler.laneOf` answering the new
lane types for `ByteType` and `ShortType`, the column and literal leaves, the comparison leaf
(`VarkaConditionCompiler`'s `intLeaf` takes a narrow column), and the casts of 332 (not here).

The two new `LaneType` members are a compile error at every exhaustive switch (`PLAN.md` 2.3:
the main sources name `LaneType.INT`/`LONG` 55 times and `Lane.INT`/`LONG` 26 times across 16
files, a count taken on 14 October 2026 and to be re-taken at implementation). The switch sites to
expect are the ones `VARKA-336.md` 0.7 lists for a new IR case: `VarkaVectorIR` (`permits`,
`laneType`, `canonical`, the render, `childrenOf`, `withChildren`), `Analysis`, `Slots`,
`VarkaVectorWalk`, `VarkaOperandPosition`, `VarkaEmitBudget`, `VarkaEmitCost.kindOf`,
`VarkaRangeAnalysis`, `VarkaChronoCompiler`'s rearm switch, `VarkaConditionCompiler.onLongLane`,
and in tests `VarkaReference` (two switches), the shrinker and `VarkaCanonicalIr`. Also
`VarkaEvaluatorBase.inputWidths` (around lines 873 to 880: casts to `BaseFixedWidthVector`, calls
`getTypeWidth`, feeds `VarkaWarmupGate`), the sanitizer, `VarkaReproduction.bytesOf` and
`VarkaKernelRunner.fill`, which must take the byte and short column widths.

### 2.3 The loop, the driving lane and 128 bits

A kernel is driven at its widest lane and narrower values fill the low part of their species
(0, item 4). The two arms meet that rule as follows, and the difference is the heart of the
measurement (row 331's boolean leaf carries a lane under the same rule, `VARKA-331.md` section 2, so the
two plans' reading of "the widest value's lane" must stay the same):

* **A kernel whose every value is narrow** (a comparison of two byte columns, a byte arithmetic
  chain written to a byte column): driven at the narrow lane under NATIVE, 64 rows per iteration
  at 512 bits, 32 at 256, 16 at 128, and a 64-row group is one `toLong` away from a 64-bit
  selection word. Under WIDENED it is driven at the int lane: 16, 8 and 4 rows per iteration, the
  load a 128-, 64- or 32-bit byte load converted to the int species.
* **A kernel with an int value as well** (`b < i` after coercion: `CAST(b AS INT) < i`): driven at
  the int lane. Under WIDENED the byte column is the widening load, whole in the loop (the byte
  species of the int species' width / 4, matching `int.B2I_load`). Under NATIVE the byte value is
  computed in the byte species of that same part width, so it keeps 16 rows per operation at 512
  bits like the widened arm, and the NATIVE arm's lane advantage is gone; what remains is whether
  its narrow ops are cheaper than the widened arm's convert-then-int op, and its cost is the
  conversion at the mixing node instead of at the load. This case, not the pure-narrow one,
  decides whether NATIVE is worth shipping for mixed shapes; the plan's expectation is that it ties
  (prediction 3). It also creates 376's two-species case in one JVM (a part-width byte species and
  the full int species), which the census found non-boxing at 512 and 256 on the laptop.
* **A kernel with a long value**: driven at the long lane, a byte in an eighth of its species;
  out of this row except as the conversion `byte to long`, which composes (`VARKA-28.md`) as a
  `B2I` then `I2L`, and is row 332's cast.
* **128 bits.** The byte species matching a four-lane int species would be 32 bits and the
  Vector API has no such shape, so the WIDENED byte load at 128 has no whole-group form: the
  census cell is skipped for that reason (0, item 9). The options are (a) a 64-bit byte load
  converted in two parts into two int vectors per iteration (the loop stops at a multiple of 8 rows,
  two int groups), (b) a 128-bit byte load converted in four parts (16 rows, four int vectors),
  (c) four bytes read as one `int` and unpacked with shifts, (d) the masked 4-lane load, which 28
  measured at two to five times a whole one. **The row's first step is to add the byte 64-bit load
  with part 0 and 1 (and the 128-bit one with parts 0 to 3) to the census at 128 bits**, with the
  allocation reading, before the arm is built; the plan expects (a), and `short` needs none of this
  (a 64-bit short species is four lanes, the int species' own count). If no 128-bit form is
  intrinsic on the Neoverse N2, the WIDENED arm declines the kernel at 128 bits on that host, and
  the row records it as such rather than carrying a masked form that was measured slow.
* **The epilogue** is masked in both arms, from the lane's own `indexInRange` (the narrow species'
  for a driven-at-narrow kernel), and the validity word is bits a row whatever the lane. A
  64-row group's validity word is a whole `long`; whether `validityBitsAt` has a 32- and 64-lane
  sibling (the emitter picks a helper by lane count, falling back to the general form,
  `VarkaBodyEmitter.emitValidityRead`) is a check the first step makes; the fallback is correct
  and slower.

### 2.4 Representation costs per row

A first-principles count, to be replaced by the measurement and not claimed as one: the column is
one byte (or two) a row in both arms, so a kernel past L2 reads the same bytes either way and the
two should tie; inside the cache the arms differ in operations per row. Per 64 rows at 512 bits, a
byte compare is one load and one compare under NATIVE and four widening loads and four compares
under WIDENED (the four masks then combine into the selection word under a shift and an `or` each).
For `SMALLINT` the ratio is two, not four. The output side is symmetric (one store of 64 bytes
against four narrowing conversions and four 16-byte stores). The widened arm's per-node overhead is
only in arithmetic (4.2). Hence the predictions in 3.4 bound the NATIVE gain by the lane ratio,
4 for bytes and 2 for shorts, and by 1 past the cache.

## 3. Row 329: the lane, built both ways

### 3.1 What lands

1. The census extension (2.3's 128-bit forms, and the `short` widening load and two-species cells,
   and the missing `NEG`, `ABS`, `AND`, `OR`, `XOR`, `MIN`, `MAX`, a byte `blend` and the greater
   compares), run through `VarkaOperatorCensusSuite` (forks one JVM per cell and width), committed
   beside the record's operator table in `skills/vector-api-and-width.md`; the runner columns by
   `varka-width-audit.yml` as 376 did. **A cell that reads `F` for a host changes that host's
   arm** before anything else is built.
2. The two `LaneType` members and `Lane` rows, the option, the leaves, the `isArrowBacked` and
   `allocateVector` cases (2.2).
3. The nodes a lane row owes: a load, a comparison (all five ops and the mask), `IfElse`,
   `Greatest`, `Least`, `IsNotNull`, `InRanges` (`IN`), a store; no arithmetic.
4. Coverage rows: one per shape on each type, `b1 < b2`, `b = 5Y`, `b IS NOT NULL`,
   `CASE WHEN b > c THEN b ELSE c END`, `greatest(b, c)`, `b IN (...)`, and a bare column
   projection, with a `SMALLINT` twin of each; the coverage table and its machine-readable twin are
   regenerated by their tools, never edited. The census marks the type's pairs fused and the
   bytes oracle gains the rows with both option arms in its option-arm digest (no existing row's
   bytes move; the option defaults to the arm that leaves every int and long kernel as it is), by
   its `VARKA_BYTES_REGEN` step (the variable named in `VarkaEmittedBytesSuite`), new rows only,
   checked by `git diff`. The differential fixtures have no `TINYINT` or `SMALLINT` column (the
   table's twelve columns, `VarkaCoverageDifferentialSuite`), so this row adds one of each and
   states, from the run, what the added columns do to the other rows' partitions.
5. No fuzzer draw (corrected in the review of 21 October 2026, 9.1). The IR fuzzer does **not**
   draw `BYTE` or `SHORT` here: the draw is row 352's, so that no grammar change moves the cost
   model's data (39's rule 0 item 3). The narrow types are covered by the deterministic series of
   3.2. The new nodes and lanes are **unpriced until 352's refit**: `VarkaEmitCost.Tally.count`
   marks a feature without a price unpriced and `predicted()` returns null, which is the intended
   state (copied from `VARKA-336.md` section 6), including `ColumnRef`/BYTE, `ColumnRef`/SHORT and,
   in 330, `IntArith` over a narrow lane. `drawMixedShape` is outside the cost corpus:
   `VarkaEmitCostCorpus` draws `drawShape` and `drawLongShape` only (grep, 21 October 2026), so an
   eventual narrow draw in `drawMixedShape` does not by itself reprice anything.

### 3.2 Tests, and what each is for

| test | oracle | proves |
| :-- | :-- | :-- |
| every byte value at every lane position and tail length, written then read back | the input `ColumnarBatch`'s own values | the load and store, both arms, strides 1 and 2, lengths 1 to 129; the lane-count sweep only in a forked JVM, over valid species (see below) |
| every `(a, b)` byte pair through `<`, `<=`, `=`, `>`, `>=`, `IN`, `greatest`, `least` (2^16 rows, one batch) | Spark's own expressions | the compares and choice exhaustively, both arms |
| the same over `SMALLINT`, as 2^16 batches of 2^16 | Spark's own expressions; budgeted with 330's (4.4) | exhaustive 16-bit compares |
| null patterns (`VARKA-138.md`), arbitrary data bits under null slots | row engine | validity at 32- and 64-row groups |
| `b < i` (mixed), IR-level: hand-built `Compare(WidenLane(ColumnRef BYTE -> INT), ColumnRef INT)` (2.1) | `VarkaReference` over the same IR | the part-width species beside the full int species, both orders of first use, in a forked JVM (below), with `-XX:+PrintIntrinsics` allocation reading as 376's suite takes it; the SQL-level form waits for 332 |
| both arms equal on a deterministic series of narrow trees (the shapes of 3.4 and the tests above, every null pattern) | each other, per tree | the option changes no answer; no fuzzer draw (3.1 item 5, 9.1) |

**JVM and lane-count rules for every suite of this row (row 410's lesson,
`skills/testing-and-debugging.md`, the `VarkaOwnJvm` paragraphs).** `VarkaSpeciesGuard` fails an
in-process test that puts a second species of one lane type in the shared JVM, and the two arms put
different `ByteVector` species there (a part-width one for the WIDENED load, a full-width one for
NATIVE). So each new suite either mixes in `VarkaOwnJvm` (Scala) or is a forked-JVM JUnit suite
like `VarkaEmitterMixedLaneSuite`; the width and both-arm sweeps and the two-species tests run
only there; an in-process suite emits at `VarkaMatrix.base` lanes. **What `lanesOverride` counts
for a narrow lane:** `Lane.emitLanes` takes `lanesOverride` (or the lane's `preferredLanes`) as a
count of the driving lane's own lanes, and returns 0 (the general `SPECIES_PREFERRED` form) unless
`hasSpecies(lanes)` (width `lanes * bits` is 64, 128, 256 or 512) and the validity helpers exist
(2, 4, 8 or 16 lanes), read in `Lane.java` around lines 268 and 315 to 334. For a BYTE-driven
kernel the valid counts are therefore 8 and 16 (2 and 4 give 16- and 32-bit species, which do not
exist); 32 and 64 name a species but have no helper pair and fall back to the general form. For a
SHORT-driven kernel 4, 8 and 16 are valid. Under WIDENED the kernel is driven at the INT lane, so
`lanesOverride` counts int lanes (4, 8, 16) and the byte species is derived from it (2.3). The
byte-lane width sweep runs only over the valid species and reports 32 and 64 as the fallback
form, never as a sweep point of its own.
| `VarkaEmitOptionSuite`, `VarkaLaneTypeSuite` | hand-written descriptor tables | the new option entry and descriptors |
| the width audit at 512, 256 and 128 | `width_audit.json` | no new refusal at a width the host supports; the 128-bit byte form as in 2.3 |

### 3.3 Predictions, registered before the run

| | prediction | bound |
| :-- | :-- | :-- |
| 1 | byte compare, two byte columns, as a selection, in L1 and L2 (16384 rows) at 512 and 256 bits: NATIVE faster than WIDENED | rate ratio between 1.5 and 4.0 (the lane ratio is the ceiling; the lower bound is the widened arm's four extra loads and combines costing at least half a native group). Outside the bounds is a finding |
| 2 | the same past the last-level cache (8388608 rows and up) | ratio within 0.85 and 1.3, both arms moving the same bytes; a ratio above 1.3 re-run by minimums before it is written |
| 3 | mixed `b < i` (int-driven) | ratio within 0.85 and 1.3 at every rung and width; NATIVE has no lane advantage here (2.3) |
| 4 | `SMALLINT` of 1 and 2 | ceiling 2.0, floor 1.2 in cache; 0.85 to 1.3 past it |
| 5 | at 128 bits | ceiling 4.0 for bytes (16 against 4 lanes) and 2.0 for shorts, with the release rate of 374 reported beside every 128-bit number (`PLAN.md` 10.3 item 4) |
| 6 | the two-species case | no allocation and no refusal in any of the five arrangements at 512 and 256, as 376 measured on the laptop; at 128 bits the census cells decide |
| 7 | the tiering artefact | the 16384-row int-lane arm of 28's benchmark read 24786.7 then 1160.7 for identical bytes (`VARKA-28.md` 9.3, `results/int-arm-tiering.txt`); the harness compares arms at each rung by minimum over repeated forks and reports a rung whose arm moved with run order as unreadable rather than as a result |

### 3.4 The measurement

The benchmark is a class of its own, `VarkaNarrowLaneBenchmark` (Java on `VarkaBenchmarkBase`, in
`sql/catalyst/src/test/java/org/apache/spark/sql/`, beside `VarkaLaneConversionBenchmark`, which it
copies the shape of: IR built by hand, emitted through `VarkaLoopEmitter.emit` with the option set
per arm, a scalar loop as the reference arm), because a new feature family gets its own benchmark
class and results files. It is in its own pull request on the unchanged code first, like 28's A/B
and the baseline rule (`PLAN.md` 10.3 item 6, row 377): the spike on the hand loops comes before the
emitter work, as 28's `Hand28.java` did, so the arm's shape is chosen on a measurement.

Shapes, each at both types, rungs 16384 (L2), 262144 (L3), 1000000 and 8388608 rows, at 512 and 128
bits on the laptop and at 256 and 128 on the EPYC runner (the AVX2 default is 256), arms WIDENED,
NATIVE, an `int` arm of the same predicate over `INT` columns (what a byte column costs against the
int the engine has), and a scalar loop:

1. `b1 < b2` as a selection (the comparison).
2. `b1 < b2 AND b2 < b3`, `CASE WHEN b1 > b2 THEN b1 ELSE b2 END`, and `greatest(b1, b2, b3)` (a
   three-node chain of choice nodes, standing in for the arithmetic chain of `PLAN.md` 2.4, which
   needs 330; the arithmetic chain is 4.5's second measurement, in 330's pull request).
3. `b < i` (mixed, int-driven).
4. The packed boolean output of a byte compare: a reference loop that writes a 64-bit mask word per
   64 rows (NATIVE: one `toLong`; WIDENED: four masks combined) is measured now, as hand loops;
   the emitted form waits on row 27, and the verdict for this shape is recorded as provisional
   until then.

Harness rule, from row 407: every arm is driven at its own turn, just before it is timed, until its
loops hold compiled code (at least eight drives, then two consecutive drives within 30 percent,
with a wait for C2 to be idle after each, bounded, naming the arm if it does not settle), so that
no arm is timed in C1 code, as the shared-prefix ladder's was and as prediction 7's 16384-row
swing was; the new benchmark copies that helper (`VarkaEmitterTestSupport.awaitC2Idle`), not the
older harness.

Reported: the verdict per operator and width, the ratios against the predictions above, and the
census cell each arm rests on; results files committed under `sql/catalyst/benchmarks/` by
`dev/varka_bench_regen.sh` on an idle machine at both widths, never ad hoc numbers; the runner
files by `benchmark.yml`. Rule from `AGENTS.md`: any ratio under about 1.3 is re-run and compared
by minimums before it is written.

### 3.5 The default

The default is the faster arm on the EPYC 7763 at 256 bits and the Neoverse N2 at 128 (the CI
runners' widths, `PLAN.md` 2.4), not the laptop's 512. If the arms are within the 1.3 band at a
width, the default is WIDENED: it reuses the int lane's whole machinery, changes no int or long
kernel's bytes, and costs the least code. A host where NATIVE wins by more than the band at its own
width is a case for a per-width default, which the option's `Reason.MACHINE` already allows; that
choice is the owner's if the arms split by host (question 1, section 8).

### 3.6 Done when (row 329)

Both arms load, compare, choose and store `TINYINT` and `SMALLINT` with the coverage rows of 3.1;
every census cell they rest on reads `I` on each host or the host's arm declines by name; the
benchmark of 3.4 is committed with its verdict per width and operator and the predictions scored
against it in section 9; the losing arm stays in the option matrix (the fuzzer's own draws of the narrow types are row
352's, not this row's, so that no grammar change reprices the cost model here); the census's
fused pairs count rises by the narrow comparison and choice pairs and `dev/varka_census.py --check`
passes. **Last step, common to 329 and 330 (review of 21 October 2026, M5):** on a clean tree,
regenerate `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json` (new rows only, checked by
`git diff`) and `sql/varka/golden_corpus.json` (`VARKA_GOLDEN_REGEN=true`, since `TINYINT` columns
occur in Spark's golden SQL and verdicts will move; `VarkaGoldenCorpusSuite` byte-compares it),
update `totalDomains` (`VarkaCoverageDifferentialSuite`) for the guarded rows added, add the
fixture columns of 3.1 item 4, run `dev/varka_census.py --check`, and run `VarkaCoverageSuite`,
`VarkaCoverageDifferentialSuite`, `VarkaGoldenCorpusSuite` and `VarkaEmittedBytesSuite` before the
first push.

## 4. Row 330: the arithmetic, in three modes

### 4.1 The semantic table

Spark's rule at the type's width `w` (8 or 16), per node (0, item 8): the exact integer result
`r` of `a op b`; LEGACY gives `signExtend(r mod 2^w)`; ANSI raises when `r` is outside
`[-2^(w-1), 2^(w-1)-1]`; TRY gives null there.

| | LEGACY (wrap) | ANSI (raise) | TRY (null) |
| :-- | :-- | :-- | :-- |
| WIDENED, `+ - *` | int op (int wraparound mod 2^32 is harmless to the result mod 2^w, so an unreduced operand under the deferral rule of 4.2 is fine), wrap by `I2B`/`I2S` at the store, or by a shift pair at an interior node whose consumer is not wrap-compatible (4.2) | int op then `GuardedRange(r, lo, hi)`: the batch declines and the row engine raises | int op, a range compare, the mask clears the output's validity |
| NATIVE `+`, `-` | the byte op wraps natively | `ADD` and `SADD` compared `!=` (`SUB` with `SSUB`); a lane that differs is an overflow: declines the batch | the same mask clears validity |
| NATIVE `*` | the byte (short) `MUL` wraps natively | widen (byte to short, short to int), multiply exactly, range compare, narrow | the same compare to validity |
| `-x` | `(byte)(-x)`: the lane wraps | declines the batch where `x == MIN` (`GuardedRange(x, MIN+1, MAX)` before the negate) | none: Spark has no `try_negative` |
| `abs(x)` | `(byte) Math.abs(x)`; `abs(-128Y)` is `-128Y` | `x < 0 ? negateExact(x) : x`: declines where `x == MIN` | none |

The column states the same thing once for both arms: the meaning does not depend on the option.

### 4.2 The widened arm

Add, subtract, multiply, negate, `AND`, `OR`, `XOR`, `NOT` and a left shift commute with reduction
modulo 2^w, and that closed set is the only one the deferral applies to: **every node outside it
forces the wrap before it reads its operand** (a division, a remainder or `pmod`, a right shift, a
compare, `abs`, `greatest` and `least`, a widening, an `IfElse` condition), so row 30's integral
divisions over narrow operands, which Spark keeps narrow, inherit no deferral by omission. Under
LEGACY the wrap therefore need only happen at the first consumer that is not a ring operation: a
compare, an `abs`, a `GuardedRange`, a `Greatest`, a widening to a wider type, an `IfElse`
condition, and the store. A chain `(a + b) * c - d` over bytes is then int adds and multiplies and
one `I2B` at the store, with an interior sign-extension (a shift left by 24 and an arithmetic
shift right by 24, or by 16 for shorts) only where a non-ring node reads the value. The int lane's
own wraparound does not break this (mod 2^32 reduces mod 2^8). This is a lowering rule of the
emitter, proved for the shift-pair form by SMT over every 16-bit pair (4.4), not a property
assumed. ANSI and TRY need the exact narrow value at every node (Spark checks per node: `(b1 + b2)
- b3` raises on `b1 + b2` alone), so each checked node is followed by its range form and no deferral
applies. The range proof for the multiply (0, item 6) is by the operand's type and
lives in the lowering, not in `arithOver` (corrected in the review of 21 October 2026, 9.1: the
earlier text had the compiler reach `arithOver` after the narrow bound was registered, which with
an int-width `cannotOverflow` would also turn a narrow ANSI/TRY add into a wrap; see 2.1). The
lowering is an int operation followed by the range form: for `+ - *` the int result of two narrow
operands cannot overflow 32 bits, so the int op is exact and the node's own check is the range
compare (ANSI: `GuardedRange`; TRY: the range compare into validity). A narrow column's range is
`[-128, 127]` or `[-32768, 32767]` by type, added to `VarkaRangeAnalysis` as the column bound a
`ColumnRef` of the new lane carries. That bound holds on **live lanes only**: under a TRY node, a
null lane holds out-of-range junk, so no consumer may read the bound off a lane whose validity bit
is clear (it is registered with that qualification, and the lowering masks before it compares).

### 4.3 The native arm

Add and subtract wrap for free and carry the overflow test as `add != sadd` (one more lanewise,
one compare, one mask `or` into the accumulator for ANSI or the validity word for TRY). Multiply
wraps for free; its checked forms widen (above), which brings the two-species case into an
arithmetic kernel, so each checked `MUL` under NATIVE is a place the census cells for `B2S` and
`S2I` at part 0/1 (present as `short.S2I`; `B2S` is a cell to add) matter. Because HotSpot lowers a
byte multiply by emulation through 16-bit lanes (`PLAN.md` 2.4, the integer reading), the verdict
is per operator: NATIVE may win `+` and `-` and lose `*` outright; the measurement of 4.5 records
that per operator and the default is allowed to differ per operator (question 2).

### 4.4 The oracle and the sweep budget

* **Exhaustive, against Spark's own expressions.** `TINYINT`: every pair, 2^16 rows, one batch,
  per operator, mode and arm. `SMALLINT`: every pair, 2^32, as 2^16 batches of 2^16 rows (one
  operand a constant column per batch), per operator, mode and arm: three operators times three
  modes times two arms is **18 sweeps of 2^32** for 330, the "twelve to eighteen" of `PLAN.md`
  10.3 item 5 at its upper end. Negation and `abs` are unary and exhaustive in 2^16 rows for both
  types. The named cases: `-(-128Y)`, `abs(-128Y)`, `-(-32768S)`, `abs(-32768S)` in each mode, and
  `127Y + 1Y`, `-128Y - 1Y`, `-128Y * -1Y` (which is 128, out of range, in ANSI and TRY), `127Y * 127Y`,
  and the short analogues, each with a null beside it at the edge. **They go through columns or a
  literal-free fixture**: written as literals, `ConstantFolding` evaluates them at plan time and
  the kernel never sees them. The ANSI `127Y + 1Y` case must raise on both arms through columns
  (the guard against 2.1's `arithOver` hazard).
* **Oracle cost.** The only committed timing of a 2^32 sweep is 28's: 2^32 int widenings and
  narrowings each way, no decline, about 25 seconds with the kernel on both sides
  (`VARKA-28.md` 9). A sweep against Spark's own expression evaluation costs more, by an amount not
  known until one runs. **The first sweep is `SMALLINT` `+` in LEGACY on the WIDENED arm; it is
  timed as its own step and committed with the machine and the oracle's share of the time**, and the
  total of the 18 (and of the milestone's sixty, `PLAN.md` 10.3 item 5) is extrapolated from it and
  written into `PLAN.md` before the next sweep lands, with the sweeps of 332 and 343 waiting on
  that number. The sweeps are opt-in and run in the nightly sweep step, as 28's are, with a 2^24
  slice on every build, and long jobs set `-Dspark.test.timeout` (a test body past 20 minutes fails).
* **SMT where complete.** The 16-bit lowering rules are bit-vector facts: the shift-pair
  sign extension equals reduction mod 2^w; `add != sadd` iff the exact sum leaves the range;
  the range compare equals Spark's `checkOverflow`; the deferred wrap equals per-node wrap. Each
  is a `proofs/*.smt2` file in the style of `bitmap.smt2` and `int_mulhi_divide.smt2`, checked in
  seconds over every pair (a 16-bit pair is 32 bits of state). The SMT proves the formulas, the
  sweep proves the emitted kernels and the plumbing, which is why both stay, and the 2^32 sweeps
  that SMT makes redundant for the formulas can be cut to one arm per operator and mode if the first
  sweep's timing says the budget does not hold (the cut is the owner's if it comes to that,
  question 3).

### 4.5 Tests and measurement for 330

* `VarkaEmitterNarrowArithSuite` (JUnit, its own JVM, as `VarkaEmitterMixedLaneSuite` is): the
  named cases over both arms and every mode at lengths 1 to 129 and four null patterns; the
  exhaustive sweeps above as opt-in; a TRY overflow beside a null and beside a valid neighbour (the
  validity AND rule: the output's validity is the operands' with the overflowing lanes cleared).
* Coverage rows: `b1 + b2`, `b1 - b2`, `b1 * b2`, `-b`, `abs(b)`, `try_add(b1, b2)`,
  `try_subtract`, `try_multiply`, under ANSI and LEGACY (the session sets the mode), and the
  `SMALLINT` twins; `b + 1` and `b * 2` as the int-lane shapes (documented, not new: no row of
  330). `VarkaCoverageDifferentialSuite` runs them against Spark, both consumers, the partitions
  and ANSI's positions, with the ANSI rows' "raises on exactly the rows Spark does" as the form.
* The benchmark: `VarkaNarrowLaneBenchmark` gains the arithmetic shapes in 330's own pull request,
  a three-node chain `(b1 + b2) * b3 - b4` and `b1 + b2` alone in each mode, with these predictions:
  LEGACY WIDENED within 0.85 to 1.3 of the int lane's rate per row at the same row count (the work
  is the same int ops plus one narrowing at the store); LEGACY NATIVE between 1.5 and 4.0 times
  WIDENED in cache for `+ - ` and below that for `*`; ANSI and TRY ratios reported per operator
  without a registered bound, because the check forms differ (a guard against a saturating compare)
  and the first number is what the model needs.
* The cost model is **not** repriced here (corrected in the review of 21 October 2026, 9.1): the
  new narrow arithmetic is unpriced until 352's refit (`VARKA-336.md` section 6; `Tally.count`
  marks it unpriced). 335a's refit was fitted on grammar draws and needed the owner's word; with no
  narrow draw before 352 there is no data to fit. Narrow-type differential checks here are
  deterministic series, not fuzzer draws.
* Suites follow 3.2's JVM rules: `VarkaEmitterNarrowArithSuite` is its own JVM, the arithmetic
  width and both-arm sweeps run only there, and any in-process suite emits at `VarkaMatrix.base`
  lanes.

### 4.6 Done when (row 330)

`+`, `-`, `*`, negation, `abs` and the `try_` forms fuse on both arms in the three modes; every
operand pair is proven for `TINYINT` (2^16, every build) and for `SMALLINT` (2^32, nightly, with a
2^24 slice per build), the named cases pass, the SMT files for the 16-bit rules are committed and
checked, the first 2^32 sweep is timed and the budget of 18 sweeps totalled in `PLAN.md` before the
next sweep lands, the coverage rows and the census count move, and the bytes oracle shows no change
to an existing row.

## 5. How the neighbouring rows meet a narrow operand

* **335b (bitwise).** `&`, `|`, `^` and `~` keep the operand type (`bitwiseExpressions.scala`
  casts back to byte or short), and are ring-compatible, so the widened arm needs no re-narrow
  between them and the native arm has the lane operation (the census has no byte `AND`/`OR`/`XOR`
  cell: step 3.1 item 1 adds it). The shifts `shiftleft`, `shiftright` and `shiftrightunsigned`
  accept `INT` and `BIGINT` only, so a narrow operand arrives under a widening and is an int shift
  with no narrow case at all. `bit_count` of a byte or short is `Long.bitCount` of the
  sign-extended value (the interpreted form reads `java.lang.Long.bitCount(input.asInstanceOf[Byte])`),
  a 64-bit popcount whose int result is at most 64: the landed int `BitCount` already is
  `bitCount(x) + ((x >> 31) & 32)` (`VarkaVectorIR.java` around lines 737 to 745, read), which is
  exactly `Long.bitCount` of the sign-extended value, so under WIDENED it needs a sign-extended
  input and nothing else (the correction this text once named is already inside the node;
  corrected in 9.1); under NATIVE it widens. `bit_get` and `getbit` return `TINYINT`
  (`BitwiseGet.dataType` is `ByteType`): their output is a byte root, which 329's store admits, so
  **335b's byte-output items are unblocked by 329's store and the comparisons, and need nothing
  from 330.**
* **334 (conditions).** `IS NULL` and `IS NOT NULL` read the validity bitmap, which is bits a row
  whatever the lane, so they work over a narrow column as soon as it loads; the narrow lane's
  validity words follow the group-size check of 2.3. `336` meets the same.
* **332 (casts).** `byte` to `int` is the widening this row has as a load (`B2I`, exact, every
  mode); `int` to `byte` is `I2B` under LEGACY and `I2B` over a `GuardedRange` under ANSI, the
  shape of 28's `NarrowLane`. This row builds the conversions as emitter operations and the IR for
  the narrow lane; the casts as compiler arms, the TRY forms and the matrix are 332's, which waits
  for both rows (`PLAN.md` wave F1).
* **103's byte output (hours and minutes extracts).** A byte root is a store through `I2B`
  (WIDENED) or the byte lane (NATIVE) after an int-lane value proven to fit, as 28's `NarrowLane`
  is admitted below a root; 329's store is that.
* **27 (boolean output).** A byte compare's 64-lane mask packs in one `toLong` under NATIVE; the
  fourth shape of 3.4 is the first place the emitted form would measure it.

## 6. Out of these rows (the declines that stay)

* `/` over narrow types is `DOUBLE` (`Divide` casts first), `div` is `BIGINT` and `%` and `pmod`
  keep the narrow type but need an integer divide the lane does not have; all stay on the row
  engine under row 30's design for division and the existing constant-divisor lowerings, with no
  narrow-lane form here.
* A narrow `Cast` to or from any type, and the casts' modes (332). Narrow arithmetic whose
  operand is a cast to a wider type is that type's arithmetic.
* `bit_get`, `getbit`, narrow bitwise and `bit_count` (335b).
* A narrow lane in a kernel driven at the long lane (an eighth-of-species load); only the cast
  composes it (332).
* Collation, `TINYINT` as a hash or sort key, `ARRAY<TINYINT>` and every nested use: not columnar.
* A high-multiply or a 128-bit-lane form: no census cell exists and none is assumed.
* Repricing the emit cost model and any fuzzer draw of a narrow type (352's); the narrow nodes
  stay unpriced until then.
* The tiny-lane cache serializer: the Arrow cache holds `TinyIntVector` and `SmallIntVector`
  already, so no change; confirmed by the read test of 3.2.
* A `BIGINT` or `INT` checked multiply (104's and `arithOver`'s refusal remain as they are).

## 7. Risks

1. **The 128-bit byte load has no whole-group form** (2.3), so the WIDENED arm may be absent or
   slow on the Neoverse N2 and at the forced width. Mitigation: the census step first; the arm
   declines by name where it has no intrinsic form.
2. **The two-species case.** The record measured 6.4x to 12.8x slower in a JVM that had touched a
   second species (`skills/vector-api-and-width.md`); 376 found no box in five arrangements on the
   laptop at 512 and 256 bits, and the runner columns and 128 bits are not covered. The arms create
   it in one JVM only in mixed kernels (2.3); the benchmark runs each arm in a forked JVM and the
   allocation reading is taken per arm.
3. **Tiering artefacts at the 16384-row rung** (28's finding, prediction 7).
4. **The sweep budget** is a milestone risk (`PLAN.md` 10.3 item 5): 18 sweeps here, and the number
   is unknown until the first one is timed; the SMT fallback is 4.4.
5. **The option matrix doubles for the narrow shapes**; the matrix's cost is the owner's to see in
   the weekly run, which has been the lagging workflow (`PLAN.md` row 393), so the new arm's weekly
   result is read, not assumed.
6. **A per-width or per-operator default** complicates the option from a flag to a rule; the plan
   defers it until the numbers say it is needed.

## 8. Sequencing, and the questions that are the owner's

Order inside the pair: (1) the census extension and its runner dispatch, and the hand-loop spike
with `VarkaNarrowLaneBenchmark`'s baseline pull request; (2) the lane (329 proper), both arms, the
option, coverage rows; (3) 330's first sweep, timed; the budget written into `PLAN.md`;
(4) 330's arithmetic, both arms; (5) the benchmark's arithmetic shapes. 330 follows 329's arms
(`PLAN.md` 4.1 wave F1). The sweeps of 332 and 343 wait on step 3.

Questions for the owner, each with this plan's recommendation:

1. **If the arms split by host (NATIVE wins at 512 and loses at 128), is the default per width?**
   Recommendation: yes, a per-width default, because the option's reason is `MACHINE` and the
   record's own rule is that many winners depend on the host; the CI runners' widths decide the
   shipped one (3.5).
2. **May the default differ per operator** (NATIVE for compares and `+ -`, WIDENED for `*`)?
   Recommendation: not in this row; ship one arm per width and record the per-operator verdicts,
   since an operator-level option would be a second option for a case the data may not need.
3. **If the first 2^32 sweep makes the 18 unaffordable, is the cut "SMT for the formula and one arm
   per operator and mode swept"?** Recommendation: yes, because the arms share the IR and the
   SMT proves the lowering for both, while the one swept arm proves the plumbing; decide after the
   timing, not before.
4. **Decided in the plan, object if wrong** (the owner delegated these to the plan's
   recommendation, 21 October 2026): (a) the mixed-width node is `WidenLane`/`NarrowLane` extended
   with a target lane, not a new node, because it adds no node and no sealed-switch site and the
   strictly-narrower check keeps the hidden-conversion protection (2.1); the mixed-kernel test is
   IR-level, the SQL-level one waits for 332; (b) overflow proofs are lane-width aware and a
   narrow checked node is never made a wrap by an int bound (2.1); (c) the narrow nodes are
   unpriced until 352's refit, there is no fuzzer draw and no repricing here (3.1 item 5, 4.5);
   (d) every new suite is `VarkaOwnJvm` or a forked-JVM JUnit suite, sweeps run only there, and
   byte-lane sweeps are restricted to the valid species of 3.2.
(What was a fourth question, whether the WIDENED arm declines at 128 bits where no whole-group
form is intrinsic, is not the owner's: the census extension of step 1 answers it, by the rule in
2.3 and row 28's measurement, that a masked load is never carried; the arm declines by name only if
no 128-bit form of the byte load, a 64-bit species converted with `convertShape(B2I, ...)` in
parts, reads `I` on the host.)

## 9. Outcome

*To be written as the work happens, with the predictions of 3.3 and 4.5 scored beside the measured
numbers and the corrections to this plan added, not retro-edited.*

### 9.1 Review of 21 October 2026

An independent read-only review of this plan, applied the same day. Corrected above, in place and
noted where it stands: the overflow proof was int-width and would have made a narrow ANSI/TRY add a
wrap (2.1, 4.2); `WidenLane`/`NarrowLane` could not build the mixed kernel (2.1, decision in 8);
3.1 item 5 and 4.5 contradicted 3.6 and 6 on the fuzzer and the cost model (now: unpriced, no
draw); the common regeneration set (3.6) was missing; the species-guard rule and what
`lanesOverride` counts for a narrow lane were missing (3.2); the `Lane` constructor wording (2.2),
the `LEGACY` WIDENED cell and the live-lane qualification (4.1, 4.2), the folded-literal named
cases (4.4) and the `bit_count` correction (5) were wrong or imprecise. Nothing is left as an
unconfirmed claim except that the `Lane`/`preferredLanes` and walk edits are the implementer's to
read at the time.
