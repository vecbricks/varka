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
  operand is a cast to a wider type is that type's arithmetic; the lift 329 built serves the
  int lane's form of it (`y1 + i` is an int add over a widened load), while a mixed narrow
  expression such as `y1 - s1`, whose byte operand Catalyst promotes with `Cast(y1 AS SMALLINT)`,
  waits for 332's narrow-to-narrow cast and declines to the row engine until then.
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

### 9.2 Outcome of step 1: the census extension and the hand-loop spike (22 October 2026)

Step 1 of section 8 ran on the laptop on master `e18c8937384` with nothing emitted: the census gained
the cells below and the spike is hand-written Vector API loops (`VarkaNarrowLaneBenchmark`, no
emitter, no IR change). The record is `results/VARKA-329-spike.txt` (commands, pins, the 25 runs'
load and power state, every rung and width, the predictions scored); the verdict follows. All
numbers are for the laptop (AVX-512 on 256-bit units) alone: the EPYC 7763 at 256 bits and the
Neoverse N2 at 128 bits, whose widths decide the default (3.5), are not measured, and the runner
columns of the new census cells, which section 8 counts in this step, are owed to the next
`varka-width-audit.yml` dispatch (it needs the branch pushed).

**Census (`sql/varka/operator_census.json`).** 26 new cells and 3 that changed from skipped to
measured, all `I` with no allocation at 512, 256 and 128 bits: byte and short `AND`, `OR`, `XOR`,
`MIN`, `MAX`, `NEG`, `ABS`, `GT` and a `blend` cell, `B2S`, the short widening load (`S2I_load`), the
byte load as a 64-bit species converted in parts (`B2I_load64`, skipped at 512, where it is not a
whole group) and as a 128-bit species (`B2I_load128`), the two-species cases for the short load and
for the 64-bit byte load, and the three byte two-species cells with no conversion, which now run at
128 bits. So **2.3's open question is answered on this host: the widened byte load at 128 bits has
two intrinsic whole-group forms, option (a) (a 64-bit load in two parts) and option (b) (a 128-bit
load in four), and neither declines.** The long `MUL` has no cell and is unchanged.

**What the 128-bit byte load costs.** Nothing measurable against an int column. The widened byte
compare reads 7.3 G rows/s at 128 bits against 7.2 for the same predicate over `INT` columns (26.9
against 26.8 at 512, 16.5 against 14.3 at 256), so a byte column loaded as ints is read at the
speed of an int column. The 128-bit-load form (b) is 10 to 13 percent faster than the 64-bit form
(a) at 256 bits and within noise of it at 128 (0.97 to 0.99 in the separate runs, 1.02 to 1.05 in
the all-arms ones). The widened loops at 128 bits are bimodal from JVM to JVM (a `SMALLINT` compare
at 5.8 G in 6 of 9 JVMs and at the INT arm's 7.2 in 3; the mixed byte shape at 6.1 in 5 and 5.4 in
4), cause not looked for, so the 128-bit ratios below come as a range.

**Verdict per width** (NATIVE over WIDENED, pure-narrow `b1 < b2` as a selection, medians of the
runs, rungs 16384 to 8388608 rows; the byte pair is 16 MB at the last rung, at the edge of the
16 MB L3 of the cores the runs were pinned to):

| | `TINYINT` | `SMALLINT` | mixed, int-driven (`greatest(b1, b2) < i`), by best rates |
| :-- | :-- | :-- | :-- |
| 512 bits | 5.6, 5.1, 2.5, 2.1 | 2.0, 1.6, 1.3, 1.1 | 0.92 to 1.06 |
| 256 bits | 4.7, 4.9, 3.7, 2.6 | 2.3, 2.2, 2.1, 1.2 | 0.95 to 1.03 |
| 128 bits | 5.9, 5.9, 5.4, 4.3 | 2.5, 2.5, 2.5, 2.1 (2.0, 2.0, 2.0, 1.7 by best rates) | 1.00 to 1.15 (1.18 to 1.30 for bytes by medians) |

For the one shape measured, a compare of two narrow columns into a selection word, the arms favour
**NATIVE wherever every value in the kernel is narrow, by 2x to 6x for bytes and 1.1x to 2.5x for
shorts, at all three widths**, and tie at 512 and 256 bits wherever an int value is present (the
byte value is converted at the load or at the mix and the narrow lane has nothing left to save); at
128 bits NATIVE keeps up to 1.3x there for bytes. The smallest NATIVE gains are the shorts at 512
bits past the cache (1.08) and at 256 (1.20), where both arms move the same bytes. The scalar loop
is 1.0 to 1.7 G rows/s throughout, 4x to 90x below the vector arms. These loops write one selection
word and nothing else: no validity bits, no nulls, no masked epilogue and no narrow store.

**Predictions of 3.3, scored** (full table in the record): 1 fails high (5.58 and 4.85 against a
ceiling of 4.0: the widened arm costs what an int compare costs, so the lane ratio is not the
ceiling); 2 holds for `SMALLINT` (1.08, 1.20) and fails for `TINYINT` (2.09, 2.59), because the
byte pair at 8388608 rows is at the edge of the L3, not past it, and fails at 128 bits where
nothing is memory bound; 3 holds at every rung and width on the plan's rule, at the edge of the
band for bytes at 128 bits by medians; 4 holds at 512 bits and fails high at 256, and is at or
above its ceiling at 128; 5 fails high for bytes at 128 bits (4.5 to 5.9 against 4.0) and sits at
the ceiling for shorts (2.0 by best rates, 2.5 by medians); 6 holds (no allocation and no refusal,
and no second-species slowdown in the all-arms JVMs); 7 holds (568 arm and rung settles at level 4
in the 8 runs that print them, none unsettled, no cell moved with run order by more than 10
percent apart from one at 512 bits and the 128-bit modes). **Corrections to this plan:** the
rungs' cache levels in 3.3 and 3.4 are wrong on this machine (the byte pair at 8388608 rows is
16 MB, the short pair 32 MB, the int pair 64 MB, against a 16 MB L3 for the pinned cores), so a
rung past L3 for bytes needs 33554432 rows or more; and for the mixed shape `b < i` the two arms
are the same operations by construction (2.3 said as much), so they differ only in a kernel that
computes in the byte lane before the mix, which the spike measured as `greatest(b1, b2) < i`.

**What B2 should build first, and why.** (1) The shared pieces, which both arms need (the two
`LaneType` members, `Lane` rows, `isArrowBacked` and `allocateVector` cases, the leaves). (2) **The
NATIVE emission for all-narrow kernels, as the first arm**: it is where the numbers are (2x to 6x),
it needs only operators the census reads `I` (byte and short compare, `MIN`, `MAX`, `blend`, load
and store), and it needs no conversion, so it avoids the 128-bit widening question altogether; its
64-row group is a whole selection word at 512 bits and two or four of them at 256 and 128. (3) The
WIDENED emission second, for kernels with an int value and as the option's losing arm in the matrix:
the part-width load plus `B2I`/`S2I`, with form (b), the 128-bit load, at 256 bits and form (a) or
(b) at 128 (the runs do not separate them there). The first checkpoint of B2 is the emitted NATIVE
byte compare measured against this record's NATIVE and INT columns, because three things the hand
loops leave out can eat the margin: the validity word for 32- and 64-row groups (2.3: whether
`validityBitsAt` has helpers for those counts, and what the general fallback costs), the masked
epilogue, and a narrow store. If the emitted kernel keeps less than about half of the hand loop's
ratio, the margin is in those and not in the lane. The default (3.5) is not decided here: it waits
for the runner hosts, and the laptop alone points to NATIVE for all-narrow kernels at every width.

**Deviations from the step as filed.** The spike's results are committed as the text record above,
not as `sql/catalyst/benchmarks/` files from `dev/varka_bench_regen.sh` (28's `Hand28.java` spike did
the same; the regenerated files belong to the emitted arms of B2). The benchmark has one arm for
`b < i`, with `greatest(b1, b2) < i` added as the shape on which the arms differ; 3.4's other
choice shapes (`b1 < b2 AND b2 < b3`, `CASE WHEN`, `greatest(b1, b2, b3)`) were not timed, and its
packed boolean output is the selection word of every loop here. The census change moved the minimum
width of three existing cells (`narrow_then_full`, `full_then_narrow`, `both_in_one_loop`) from 256
to 128 bits so that they run at 128; their 512 and 256 readings are unchanged. The `short`
two-species cells and the two with the 64-bit byte load are new.

**Not yet measured: the byte rung past the last-level cache (added in the review of 22 October
2026).** The ladder's top rung stays at the edge of the pinned L3 for bytes, so the spike has no
reading that is past the cache for `TINYINT`, and the 2.1 to 2.6 ratios of prediction 2 are
unexplained. `VarkaNarrowLaneBenchmark` now has a 33554432-row rung (a 64 MB byte pair, 128 MB of
shorts, 256 MB of ints), timed only under `-Dvarka.narrow.pastL3=true` because its columns are
about 600 MB. It has not been run: the laptop was in use for other timing that night. It is
`PLAN.md` row 415, and prediction 2 stays scored as in the table above until that row reads it.

### 9.3 What was built (22 October 2026)

Row 329 was built in `varka-329-lane` from master `94c4e70aebd`, in section 8's order: the shared
pieces, then NATIVE, then WIDENED, then the rows and the oracles. Nothing is timed here: 2.4's
benchmark, the runner hosts' columns and row 415's past-L3 rung are as 9.2 left them, and the
spike's verdicts stand.

**The shared pieces.** `LaneType` gained `BYTE` and `SHORT` ahead of `INT`, and `isNarrow()` is
`BYTE` or `SHORT`, so `WidenLane`'s target-is-wider rule and `NarrowLane`'s
child-is-wider rule hold unchanged. `Lane` gained its two rows: `ByteVector` at 8 bits and
`ShortVector` at 16, a stride of one and two bytes, a scalar-array class for the literal table, and
`hasSpecies(lanes)` refusing a species below 64 bits, which is what makes a widened byte load need
eight int lanes and a widened short load four. `VarkaVectorIR`'s leaves and loads are typed by the
column's lane, `emissionLane` picks LONG over INT over the narrow lane and the narrow lane only
where every value is narrow and `narrowLanes` is on, and `Analysis.inputLane` (the column's) is kept
apart from `laneOf` (the value's), which is the plan's 2.2 mechanism. `VarkaEvaluatorBase` allocates
and reads back a `ByteVector`/`ShortVector` output, and the validity words are the column's
whichever lane reads them. Every existing shape's emitted bytes are unchanged: 2.6's requirement is
met by the option's default, not by a second lowering.

**The option.** `VarkaEmitOptions.narrowLanes`, a `Flag` tagged `nativeNarrow`, default false
(superseded by row 478: default true, tagged `widenedNarrow` for the off arm).
False is WIDENED - the narrow column read with a widening load into the int species of the same
lane count, computed as an int, truncated once at the store - and true is NATIVE, 2.2's narrow
species throughout, for a tree whose every value is narrow; a tree with one int value in it drives
at the int lane under either arm, which is 9.2's verdict. The plan's `WIDENED` and `NATIVE` are
this one flag, and WIDENED is the default, so the flag names the arm it switches on.

**The loads and the store.** A narrow column of an int-driven kernel is read with one widening
load per lane group: the narrow species of the int species' width, `B2I` or `S2I` into the int
species, whole in the loop body and masked by the first `s.lanes` lanes in the epilogue. A narrow
value is written with `I2B` or `I2S` into the same narrow species from the int vector and stored
whole or masked - one or two bytes a row - which is Spark's `CAST(int AS TINYINT)` under `LEGACY`,
the mode a TINYINT expression's result is defined by. A shape conversion whose two sides are
emitted at one lane is no instruction at all, so the widening a load already did is free.

**The int-lane lift.** A narrow condition or branch beside an int one does not widen to the long
lane: the long lift of 2.2's mixed rule would give the kernel a long lane holding a narrow value,
which has no lowering. `VarkaConditionCompiler.onIntLane` puts a condition on the int lane, where
every condition has a form: it widens the value operands of a narrow one (a sign extension, exact
in every mode, and a validity word is the column's either way), names the int lane for a boolean
leaf, and lifts what a condition built over conditions is built from - the mirror of `onLongLane`
for the long lane. `onIntValue` widens a value, and `storedNarrow` wraps an int-lane root of a
narrow-typed expression in a `NarrowLane` to the output's lane so the store is narrow. The lift
reaches the connective (meetAll), IF, CASE WHEN, and the conjuncts of a filter, and
`VarkaNodeCompiler` gained the `CAST(narrow AS INT)` Catalyst inserts where a narrow column meets
an int. So `CASE WHEN rid < 0 THEN y1 ELSE y2 END`, `if(y1 IS NULL, 5Y, y1)`,
`WHERE rid < 0 AND (y1 < y2)` and a boolean condition over narrow ones,
`WHERE (y1 < y2) = (y1 <= y2)`, fuse and store narrow; a tree whose every value is narrow is
untouched and keeps its own arm. The wrap fixed a wrong answer the ANSI form of
`VarkaCoverageDifferentialSuite` caught: the lifted choice's root was still typed INT, so the int
store wrote four bytes a row into a one-byte output and `CASE WHEN rid < 0 THEN greatest(y1, y2)
ELSE y2 END` answered -73 for Spark's -1.

**The refusals.** `VarkaLoopEmitter.narrowRefusal` names the three shapes a kernel cannot emit: a
narrow value under a bigint kernel, a narrow column with no baked int lane count
(`validityByWidth` off), and a narrow column whose species does not exist at the int lane count
(under eight int lanes for bytes, under four for shorts). The compiler asks it before it admits a
kernel and declines with the reason; `width_audit.json` records the verdict per width (at 128 bits,
four int lanes, the eleven TINYINT rows are `declined: a BYTE column has no widening load at 4 int
lanes` and the eleven SMALLINT rows are audited, their species having 64 bits; at 256 and 512 bits
every narrow row is audited with no refusal at all), `VarkaWidthAuditSuite` asserts the refusal is
absent at the preferred width, and `emitted_bytes.json` records a refused width as `<declined>`
rather than omitting it, so a narrowing of a supported width that starts declining fails the
oracle. The bigint refusal has its own assertion in `VarkaEmitterNarrowSuite`, which pins the
message for a narrow value widened to the long lane under both arms and holds the same choice on
the int lane to the width's own refusals, never to that one.

**The rows.** 22 rows in `coverage.json`, 11 a type: 7 predicates (`y1 < y2`, `<=`, `>=`, `= 5Y`,
`IS NOT NULL`, a short `IN` list, and an `IN` list of eleven literals, which arrives as an `InSet`)
and 4 value shapes (the `CASE`/`greatest`/`least` choices and `if(y1 IS NULL, 5Y, y1)`), the
SMALLINT twins. The `docs/sql-varka.md` table gained the four families and the narrow verdict at
128 bits, and `census_curated.txt` names byte and short on the rows the census gained: `if`,
`when`, `isnull`, `isnotnull`, `in`, `in_set`, `=`, `==`, `>`, `>=`, `<`, `<=`, `greatest` and
`least`, all row 329's.

**What the rows do not cover, by rule or by scope.** A bare narrow column fuses nothing, as for
every type, so the load row is `y1 < y2` and the constant choice is `if(...)`; a value `CASE WHEN`
without an ELSE declines, the existing rule, so the choice rows have one; a null narrow literal is
claimed by no arm; `<=>` and `equal_null` over narrow columns have no coverage row, which is row
336's family, though the shared pieces already fuse them - `sh <=> sh` compiles to the null-safe
equality over widened operands, pinned in `VarkaExpressionCompilerSuite`; and a narrow value in a
long-lane kernel has no lowering at all, so no row mixes them. The
emit cost model has no price for a narrow feature, so a prediction over a narrow shape is
"unknown" and the grouping falls back to a building measurement; pricing the two lanes is row
352's, with the fuzzers' draw of them.

**The oracles and the driver.** `VARKA_COVERAGE_REGEN` (coverage.json and the docs table),
`dev/varka_census.py` (the census TSV), `VARKA_AUDIT_REGEN` (width_audit.json),
`VARKA_BYTES_REGEN` (emitted_bytes.json) and `VARKA_COVERAGE_REGEN` again, in that order and each
with its own tool: the first coverage run is what the census and the audit read.
`VARKA_GOLDEN_REGEN` exited 0 with no diff, so the golden corpus and the seven Spark input files
hold no TINYINT or SMALLINT text and 8's claim that they do is wrong - the corpus needed no change,
and the row's Spark-level proof is `VarkaCoverageDifferentialSuite`'s, whose fixture gained the four
narrow columns `y1`, `y2`, `s1` and `s2`, and whose composing fuzzers leave the narrow rows out
because drawing the types is row 352's.

**The suites.** `VarkaEmitterNarrowSuite` (8 tests in their own JVM) runs both arms over every
byte length and tail, the whole byte square through every comparison, a range set, `greatest`,
`least` and a choice, a narrow column beside an int one, a narrow-typed root stored through a
`NarrowLane` and int values past the byte and short ranges truncated by that store - each against
`VarkaReference` through `VarkaKernelCheck.runAndCompareNarrow`;
where a configuration has no species for the widening load it asserts the refusal instead of
emitting, and the bigint refusal is pinned the same way, so the option matrix can run it under
every configuration. `VarkaExpressionCompilerSuite` holds the compiler-level guard of the same
shape: a boolean equality of two narrow comparisons ANDed with an int conjunct compiles to the
lifted `BoolCompare`, where returning the mask unchanged built a node whose constructor throws.

**The matrices.** `VarkaEmitterNarrowSuite` passes its eight tests under all 51 of the option
matrix's configurations and the defaults (`dev/varka_matrix.sh --all --module catalyst --suites
VarkaEmitterNarrowSuite -j 4`: 416 tests, none failed or cancelled), the refusal taking the place
of an emission where a configuration has no species for the widening load; `narrowLanes=true` is
one of them, so the native arm is run at every width. `VarkaCoverageDifferentialSuite` passes all
744 of its tests at the defaults, at `lanesOverride=16` and under `narrowLanes=true`. Under
`validityByWidth=false` and `lanesOverride=4` the rows that need a widening load decline and the
row engine answers them, and `skips.tsv` holds the 48 and the 24 tests that fail for that reason;
a stale line fails the run, so the list cannot rot. Three of the lines the first run had were
stale and are gone: an `IS NOT NULL` row's null-test `CASE` reads only the column's validity
word, so it needs no widening load and fuses at every width, while the same row's projection and
partition tests select the narrow column and decline.

**The review.** One independent read of the diff found three things, fixed in their own
commits. `onIntLane` returned a narrow boolean leaf - a boolean column, a boolean constant, a
compare of two conditions, a null-safe equality of them, a choice between them - unchanged, and a
caller then built an `And` over it and a lifted sibling, whose constructor refuses a mix by
throwing: a query such as `IF(((y1 < y2) = (y1 <= y2)) AND rid < 0, 1, 2)` would have answered
with an internal error instead of fusing or declining. The lift now names the int lane for those
leaves and lifts what they are built from, as `onLongLane` does, so every condition has an
int-lane form and none reaches the fold unlifted. The conjunct path checked only the first
fused condition after a lift and now checks every one, so a partial lift cannot reach the AND
fold. And the width-audit probe caught `VarkaEmitDeclined` and printed its message as the width's
answer, which would have pinned a budget decline as a width verdict; the catch is gone, so a shape
the forced width cannot emit fails the audit. The reviewer's smaller points are also in: a unit
test pins the narrow-typed store root - a choice computed on the int lane under a `NarrowLane` to
the byte lane, the shape the ANSI form caught - with a reference that narrows as the store does,
and `emitNarrowTypeStore` refuses a narrow-driven kernel rather than naming a species it does not
have. Running the compiler suite for the new guard also caught an expectation the earlier lift had
left stale: `sh <=> sh` was held to decline beside the strings and doubles, and it fuses now, so
the test pins the fused shape.

**The second read.** A read of the fixed diff found no bug and three smaller things, all in. The
store unit test's data stayed inside the byte range, so the store's truncation was never exercised
through emitted code: it now also stores int values past the byte and the short range, and the
reference's truncation has to agree row by row. The width audit's prose called its declines the
emitter's, where the shapes are declined by the compiler before the probe runs; the suite's
comments and the description recorded in `width_audit.json` now say so, and a regeneration moved
that one field and no measurement. And `isNarrow()` was described above as an ordinal comparison
where the code names `BYTE` or `SHORT`.

### 9.4 Outcome of row 330: the narrow arithmetic in three modes (22 October 2026)

Built on the lanes of step 2 in the worktree `varka-330-arith`: the row's branch took the lane
branch in at the checkpoint commit `33996c315d9`, again at `588dffea06a` (the lane's
`62f7af53577`, `1689f255cca` and `281f63b9707`), at `d9233c94299` (the lane's `c4cf280ca20`,
`edea39e876b`, `20166917090`, `d033d3bbcc0` and `5584744d138`, the last a plan edit alone) and
once more at `9c7ca399df4` (the lane's `97c69a80403`, `68bc97a0b1f` and `69bc86a414c`, the last
a plan edit, with master's `50b5c4185ce`, `8f7fdd9a7b5`, `52101acab00` and `3dac916bd75` that
the lane merged in), rerunning the row's suites and regenerating the pins after each merge, the
last regeneration changing no file. The final merge took the lane's review fixes in
(`f539264f74d` and `d299abf5dbf`, 9.5 below); its only change to the row's pins is the emitted
bytes at four int lanes, where `y1 IS NOT NULL` emits instead of declining once finding 14 let a
validity-only narrow read through. No new IR node: a narrow `Add`, `Subtract`, `Multiply` or
`UnaryMinus` compiles to `IntArith(op, mode, left, right)` or `IntNeg(mode, child)` on the
node's own lane, and `abs` to `IfElse(Compare(LT, x, 0), IntNeg(mode, x), x)` - Spark's own
ANSI lowering, built from nodes that exist. The mode is the node's: `LEGACY` is
`Overflow.WRAP`, `ANSI` `Overflow.FAIL` and `TRY` `Overflow.NULL`.

**WIDENED, the default arm.** Everything is computed in the int lane over sign-extended narrow
values. Under `LEGACY` the reduction is deferred to the first non-ring consumer: `Analysis.wrapAt`
marks every unchecked narrow `IntArith`/`IntNeg` whose parent is not one of the ring nodes, and
`VarkaVectorWalk.emitWrapToNarrow` reduces it with the lane's shift pair, `(x << s) >>a s` with
`s = 32 - w` read from the lane. A checked node is not a ring node, so an ANSI or TRY node reduces
and checks at itself, which is Spark's per-node rule - `(b1 + b2) - b3` raises on the sum alone.
The check is the int result against the lane's range: `GuardedRange` under ANSI, which declines the
batch and lets the row engine raise, and the same compare into the output's validity under TRY,
ANDed with the operands' validity. The multiply needs no special case here: the product of two
narrow operands is exact in the int lane (at most 2^30), so the same range form checks it. The
one correction in `Analysis` is that the int arm's "a checked multiply never reaches the emitter"
rule now reads the lane: a checked narrow multiply does reach it, and needs its refusal like any
other checked node.

**NATIVE.** The byte and short lane operations do the arithmetic. `LEGACY` wraps in the lane.
`ANSI`'s check is one more lanewise and a compare - `ADD != SADD` for the sum and `SUB != SSUB` for
the difference, the saturating operator picked by the op - and a lane set in that mask declines
the batch; `TRY` turns the same mask into cleared validity bits. A checked multiply leaves the
lane: `VarkaVectorIR.holdsCheckedNarrowMultiply` drives its kernel at the int lane on either arm,
where the product of two narrow operands is exact, so the check is the same int compare against
the narrow range; this is the one narrow shape whose kernel is wider than its operands, the
two-species case of 2.4.

**Proofs.** Four files under `sql/varka/proofs/`, rendered by `VarkaProofFiles` and held to their
committed text by `VarkaProofFilesSuite`: `narrow_wrap.smt2` (16 checks: the shift pair is the
sign extension of the low `w` bits for every int, the ring operations commute with it, and the
logical shift and the shift by `s - 1` are refuted as the wrap), `narrow_overflow.smt2` (14
checks: `add != sadd` and `sub != ssub` are set exactly where the exact result leaves the range,
with the result's sign and the other operator's saturating form refuted), `narrow_range.smt2` (12
checks: the compiler's `r < min || r > max` is Spark's `checkOverflow` for a narrow result,
`<=`/`>=` refuted at the end each names, and the checked multiply's test in the widened lane is
the same test), and `narrow_deferred.smt2` (12 checks: the deferral law
`wrap(u op v) = wrap(wrap(u) op wrap(v))` for `+`, `-` and `*` at both widths, the int lane's ends
as the law's fixpoints, and a compare refuted as a ring node, so it forces the wrap). Each file is
`(set-logic ALL)` because it reasons over bit-vectors and takes the integer prelude;
`dev/varka_prove.sh --solver both` is green, every check holding under Z3 and cvc5 in hundredths
of a second.

**Tests.** `VarkaEmitterNarrowArithSuite` (its own JVM, `VarkaOwnJvm`) is the arithmetic's gate.
The named cases go through columns, never literals, at lengths 1 to 129 under four null patterns
on both arms: `-(-128Y)`, `abs(-128Y)`, `127Y + 1Y`, `-128Y - 1Y`, `-128Y * -1Y`, `127Y * 127Y` and
the short twins, each in `LEGACY`, `ANSI` and `TRY`. `TINYINT` is exhaustive over every operand
pair (2^16 rows in one batch) for every operator, mode and arm; `SMALLINT` runs a 2^24-row slice
on every build and all 2^32 pairs opt-in in slices of 2^16 rows with the second operand constant,
and each sweep holds the null rows, the answers and one row in 256 to Spark's own expression
evaluation. The suite also holds the check's byte cost where it is emitted, and the deferral
itself: a chain answers at its root and under every non-ring consumer, a checked chain declines at
its first overflowing node, and a TRY chain nulls the overflowing row and keeps its neighbours.
`VarkaCoverageDifferentialSuite` holds the same 16 rows to Spark end to end - both consumers, the
three domain partitions, ANSI's four positions - with `VarkaGuardSpecs` reading the narrow check at
its own lane on both arms; the suite's 786 tests are green at the matrix base, in 396 s before the
last merge and 514 s after it beside the five configurations, and under `narrowLanes=true` too.
The new suite's 20 checked-mode tests are on
`matrix/skips.tsv`'s `checkIntOverflow=false` list, where the option's promise - the same node
cheaper - is that a checked node wraps, and the narrow rows' differential tests are listed under
the three configurations that answer them differently: `checkIntOverflow=false` (26 tests, the
wrapped value where Spark raises), `validityByWidth=false` (26, no baked int lane count to widen a
narrow column from) and `lanesOverride=4` (13, the eight `TINYINT` rows, a four-lane int species
having no 32-bit byte species). All three configurations then pass, those declines being the arm's
answer. *Superseded on 23 October 2026 by 9.6's part-form load, which gives four int lanes a byte
lowering: the 13 `lanesOverride=4` lines, and the 28 of row 329 a merge brought back, are gone
(9.8, 989-1).* The row's four catalyst suites rerun as the whole catalyst matrix under all 51 option
configurations and the defaults, three JVMs each: every configuration exits 0, with no test
unfused and no skip line stale. The defaults answer 76 and cancel 19; most configurations answer
73 and cancel 22, and the two that turn on their own skip lines cancel them as well:
`checkIntOverflow=false` answers 49 and cancels 46, `methodByteBudget=0` answers 71 and
cancels 24.

**The first sweep, timed.** `SMALLINT` `+` in `LEGACY` on the widened arm, the cheapest of the 18,
ran as the suite's opt-in test with `-Dvarka.sweep.filter` naming it alone, under both agent locks
and a one-minute load under 3 before the JVM: 65,536 batches of 2^16 rows, each a constant short
against every short, all answered, **1106 s** (18 min 26 s) for the whole 2^32, with 16,777,216
rows held to Spark's own `eval` - one row in 256, the reference evaluator holding every row of
every batch. On a held batch the oracle costs 43.4 ms against 20.3 ms for the kernel and the
reference together, so at the sweep's sampling it is half a percent of the time. At that rate 330's
18 sweeps are 5 h 32 min and the milestone's sixty 18 h 26 min - 24 times and 82 times the thirteen
and a half minutes `m7/PLAN.md` 1 took for every int32 lowering's sweep together. The machine, the
command, both printed lines and the arithmetic are in `results/VARKA-330-sweep.txt`, and the budget
is in `PLAN.md` 10.3 item 5. The rate is the cheapest operator's in the cheapest mode, so the total
is a floor, and 4.4's cut - one arm per operator and mode, the SMT having proved the formulas -
remains the owner's if 5.5 hours for 330 alone is too much.

**Coverage and census.** The 16 rows - `y1 + y2`, `y1 - y2`, `y1 * y2`, `-y1`, `abs(y1)` and the six
`try_` forms, each with the short twin - are the coverage table's first `TINYINT` and `SMALLINT`
pairs. `VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly *VarkaCoverageSuite'` rewrote
`coverage.json` (16 rows, 182 lines) and `docs/sql-varka.md` (21 lines), and the regen run's ten
tests passed. A second coverage pass after the width audit, in the order
`sql/varka/skills/emitter-and-ir.md` gives, replaced the docs table's `not audited` with the
audit's verdict on all 16 rows - the eight byte rows refused at 128 bits - and folded in the
corrected byte-multiply note (one line of `coverage.json`); the check the same suite makes of the
committed files passes afterwards.
`m8/census_curated.txt` gained the eight lines the pairs make fused (`+`, `-`, `*`, `abs`,
`negative`, `try_add`, `try_subtract`, `try_multiply` over `byte,short`; `*` was fused for `int`
alone), `census.tsv` was regenerated by `dev/varka_census.py`, and `--check` is green. The registry
counts 164 fused pairs over its 13 types, 23 of them fused for every admitted type; `TINYINT` and
`SMALLINT` are 8 of their 75 pairs each, where neither had one, against `INT` 31 of 90 and `BIGINT`
28 of 86.

The bytes oracle pins the same 16 rows at both widths, its column list extended with the narrow
four - appended after the boolean pair, so no existing row's input ordinals move. At 512 bits they
emit on the widened arm as any int kernel does. At 128 bits the eight `TINYINT` rows were refused
(*until 9.6's part-form load, after which they emit; 9.8*), and the refusal was the compiler's own: `VarkaLoopEmitter.narrowRefusal` is what demotes a narrow
value it cannot widen, and at four int lanes the byte species below would be 32 bits, a constant
the Vector API does not declare. The oracle records that reason in the row rather than a body that
could not load; the eight `SMALLINT` rows emit, four shorts being a named 64-bit species. No
existing row moved. `sql/varka/width_audit.json` pins the same rows per width: the 16 shapes add
48 lines at 512, 256 and 128 bits, the eight byte rows refused at 128 with that same reason and
nothing else changed.

**Findings and limits.** Two things are recorded where a reader meets them rather than left to be
discovered. First, before the lane's lift landed, a `CASE WHEN` whose condition was an int-lane
comparison (`rid < 0`) and whose values were on the byte or short lane was refused, as was a
`try_` row's `rid < 0 AND ...`: `VarkaConditionCompiler.caseWhen` asked `sameLane` after compiling
both and lifted a value to the long lane only. The lift (`VarkaConditionCompiler.onIntLane` and the
`Cast(narrow AS INT)` arm, 329's `1689f255cca`) serves both, so the differential suite's
`ansiDeclined` now holds no position of these 16 rows except the checked rows' false AND, which
declines for the reason every check under an AND does (`m9/SCOPE.md` item 96); the `try_` forms
fuse in that position too. The other record is a commitment rather than a finding: the measurement
4.5 asks for (`VarkaNarrowLaneBenchmark`'s arithmetic shapes in this row's own pull request, with
their three predictions, the checked multiply's widening cost on the native arm among them) is in
neither this row's brief nor 4.6's done-when list, so it is not carried here; the shapes and the
predictions stay in 4.5 for the row that prices the narrow arithmetic (352's refit).

Three things were found on the way, all in this row's own code. The reduction's shift is now taken
from the lane the value is emitted at rather than from the int lane: the int lane's `32 - w` also
happens to be a no-op on a byte or short vector, because Java masks a shift count to the lane's
width, so the native arm emitted two useless operations and the code was right by accident; it now
skips the pair where the emitted lane is the node's own. With `checkIntOverflow` off, a checked
narrow node is a ring node like a wrapping one, or an intermediate under a non-ring consumer kept
the exact int (`127Y + 1Y` read as 128 rather than -128) instead of wrapping at its lane's width
the way the option promises the same node cheaper. And the edges fixture had no row inside the
short product's range - every other row's `300 * -400` is out of it - so its inside partition was
empty; one row of small shorts fixes it. One limit is kept rather than worked around: a checked
narrow multiply drives its kernel at the int lane on either arm
(`VarkaVectorIR.holdsCheckedNarrowMultiply`), the two-species case of 2.4, whose cost is left to
the measurement the row's shapes need and this row does not carry. That drive widens even on the
native arm, so where a widened byte load is refused - at the time of writing four int lanes,
since 9.6 only a width with no baked lane count - a checked byte multiply declines there too; the suite reads a decline as the arm's answer exactly when the width
cannot widen the node's lane. The `CAST` limit the row first recorded is gone with the lift:
`y1 + 1`, which analysis spells `Add(Cast(y1 AS INT), 1)`, compiles as an int add over a widened
narrow load by 329's `Cast(narrow AS INT)` arm, and so does `y1 + i`; at four int lanes the byte
rows decline there too, the same species refusal as any widened byte load.

**The independent review.** A read-only review of the row's diff found no lowering defect and
confirmed the proofs and the exhaustion tests. It found three statements that had not followed the
lane's final form - the native-arm paragraph above, `docs/sql-varka.md`'s byte-multiply note
(widened to `int`, not `short`) and `VarkaVectorWalk`'s comment claiming a saturating narrow
multiply - and one test oracle that had stayed lane-blind: `VarkaGuardSpecs.overflows` compared a
narrow `FAIL` node's exact result against the int lane's bounds while `bound` had learned the
node's own lane, so both now read `laneBounds`. A fourth finding is latent and recorded for the
cast row rather than fixed here: a non-root `NarrowLane` in an int-driven kernel emits no
narrowing today (`Analysis.laneOf`), which the stored-narrow construction's roots keep harmless;
`PLAN.md`'s row 332 now carries it.

### 9.5 Outcome of step 2: the emitted arms measured (23 October 2026)

Step 2 is the measurement 9.2 owed: the same shapes through `VarkaLoopEmitter` and
`VarkaFusedKernel`, under both arms of `narrowLanes`, instead of hand-written loops.
`VarkaNarrowEmitBenchmark` builds the IR by hand (a `y1 < y2` selection, `b (y1 + y2) < y1`, an
int-driven `b < i`, `e CASE WHEN y1 < y2 THEN y1 ELSE y2 END` as a `TINYINT` store; the plan's
packed-boolean shape `d` is the selection word of every compare root and so has no separate
kernel), emits it with the arm's option set, loads it, checks the whole batch against
`VarkaReference`, and times it at 16384, 262144 and 1000000 rows, with 8388608 and 33554432 under
opt-in properties. Fourteen runs on the laptop at 512, 256 and 128 bits, WIDENED and NATIVE both
in one JVM and each alone; the record, with commands, pins, the load and power state of every run
and every table, is `results/VARKA-329-emit-arm.txt`.

**Verdict** (NATIVE over WIDENED, medians, G rows/s in parentheses):

| shape | 512 bits | 256 bits | 128 bits |
| :-- | :-- | :-- | :-- |
| byte compare into a selection | 4.08, 3.88, 2.32 (104.1, 99.0, 58.4) | 3.06, 3.05, 2.91 (49.0, 48.7, 44.8) | NATIVE only: 36.1, 36.2, 34.0; WIDENED declines |
| the add-then-compare chain | 4.04, 3.84, 2.34 | 2.55, 2.64, 2.53 | NATIVE only: 35.6, 36.1, 33.9 |
| mixed `b < i`, int-driven | 1.00, 1.00, 0.99; the arms' bytes are identical | 1.00 at every rung, identical bytes | both arms decline |
| `CASE ... TINYINT` store | 4.57, 3.38, 2.24 (89.1, 65.8, 42.6) | 4.35, 3.52, 2.22 | NATIVE only: 57.7, 55.9, 41.2 |

Past the cache the compare reads 1.65 at 8388608 rows (a 16 MB byte pair, the edge of the pinned
complex's 16 MB L3) and 1.15 at 33554432 (64 MB); the chain 1.68 and 1.24; the store 1.45 and
1.04. The two-species arrangement shows no measurable cost: the separate-arm JVMs at 512 bits
agree with the all-arms ones on the compare within 1 percent, inside the store cell's run-to-run
spread.

**A second host.** The benchmark was dispatched on the fixed head and ran on a GitHub-hosted AMD
EPYC 7763 at 256 bits (run 39915371624, head `9679df08971`; the record's "a second host" section
has the tables and the probe). NATIVE over WIDENED there is 3.40, 3.39, 3.43 and 3.43 at the four
in-cache rungs of the compare and 2.53 at 33554432 rows; 2.92, 3.05, 3.06, 3.09 and 2.38 on the
chain; 5.39, 3.70, 3.36, 3.12 and 2.30 on the store; and the mixed shape ties (0.98 to 1.00).
Prediction 1 holds on the compare at every in-cache rung, prediction 2 is not scored at the top
rung because the runner's cache sizes are not probed, and 3.5's direction - NATIVE for every
all-narrow shape - holds on a second host. The arithmetic overflow probe reproduced: WIDENED
selects nothing, NATIVE selects the row.

The first campaign of ten runs was rerun. Its settle gate read a kernel's compile levels from the
JVM's code list by class name, and both arms' classes carried the same name in their own loaders,
so the gate could read one arm's level 4 for the other's loop still at C1; the review of this
section found it. Each arm's class is now named for its arm, and the rerun's 306 settles all read
level 4 with none unsettled; its 512-bit medians reproduce the first campaign's to within the cell
spreads, the store's 262144-row cell being bimodal at 62 to 88 G rows/s.

**Does the emitted kernel reproduce the hand-loop advantage?** The WIDENED arm, yes: 25.5, 25.5,
25.2 against the hand loop's 26.9, 26.6, 25.9 G rows/s at 512 bits and 16.0, 16.0, 15.4 against
16.5, 16.4, 16.0 at 256, within 5.5 percent. The NATIVE arm keeps 63 to 94 percent of the hand
loop's advantage, not all of it: 4.08 against 5.60 at 512 bits in L1, 3.06 against 4.66 at 256,
and 94 percent at the L3 rung. The gap is the fixed work a group of the emitted loop does and the
hand loops do not - the validity word, the general validity fallback at the 64- and 32-lane byte
species, the loop bounds and the epilogue - which is where 9.2 said to look if the margin went
missing. It did not go missing to that degree: 9.2's threshold was that an emitted kernel keeping
less than about half of the hand loop's ratio would put the margin outside the lane, and the
smallest kept fraction is 0.63. The NATIVE lane is still worth 2.5x to 4.6x for a pure byte shape
in L1 and L2, and the default rule of 3.5 is not contradicted on this host at 512 or 256 bits.

**The 128-bit finding, and it is not a timing.** At 128 bits the WIDENED arm declines every
`TINYINT` shape that reads a value, with the reason the emitter names: "a BYTE column has no
widening load at 4 int lanes" (the 4-lane byte species would be 32 bits). NATIVE declines the
mixed `b < i` for the same reason, because an int value in the tree forces the int lane and the
widening load whatever the arm. The census's two 128-bit byte-load forms of 9.2 (`B2I_load64`,
`B2I_load128`) convert in parts and span more than one int group an iteration, and the emitter
computes one group an iteration by design, so it cannot use either: 2.3's question is answered for
the intrinsics and not for this emitter. The consequence is for the CI hosts and for 3.5: on a
128-bit host (the Neoverse N2 runner) the default arm has no emission at all for a byte column
read as a value - not only for the all-narrow shapes, where NATIVE serves, but for the mixed ones,
where nothing does - and the row engine answers. A per-width default alone does not cover that
case; either the emitter learns the part-width load at 4 int lanes or the mixed narrow shapes stay
with the row engine on 128-bit hosts. That choice is question 1's, and nothing here changes the
default.

**Predictions of 3.3, scored on the emitted arms** (detail in the record). 1 holds at 256 bits
(3.06 and 3.05 inside the 1.5 to 4.0 band) and is touched at 512 (4.08 and 3.88, against the hand
loop's 5.60). 2 holds at the rung that is genuinely past the cache (1.15 and 1.24 at 33554432
rows) and is above the band at 8388608, which is at the L3 edge, not past it. 3 holds: the mixed
shape is one kernel in both arms, 1.00 everywhere but 1000000 rows at 512, which reads 0.99. 4 is
not measured, the shapes here are `TINYINT`. 5 has no emitted ratio to bound at 128 bits, because
WIDENED declines every shape there. 6 holds at 512 and 256 and is decided at 128 by the census
cell that does not exist. 7 holds: 306 case settles over the fourteen runs, 304 at eight drives
and two at nine, all at level 4, none unsettled, and the gate's class lookup is per arm. The
run-to-run spread reaches 42 percent in the store's 262144-row cell (62.4 to 88.4 G rows/s) and
26 percent in the compare's WIDENED cell at 256 bits, which is why the tables are medians.

**Still owed.** The Neoverse N2's columns at 128 bits (the other runner host),
`SMALLINT` on these shapes, the null and masked-epilogue path (no batch here has a null), and row
415's hand-loop past-L3 rung, which this benchmark's 33554432-row reading does not replace. The
arithmetic chain's timed rungs use bytes in [-64, 63] because the arms disagree on overflow until
row 330's deferred wrap; the run prints that disagreement (100 + 100: WIDENED selects nothing,
NATIVE selects the row) rather than hiding it.

### 9.6 Outcome of step 3: the part-form widening load at 128 bits (23 October 2026)

At 128 bits a `TINYINT` column had no widening load: the wide arm read the narrow species of the
int species' own width, and a 32-bit byte species does not exist, so every byte-driven shape
declined. Either the emitter learned the part-width load at 4 int lanes, or the mixed narrow shapes
stayed with the row engine; the emitter now learns it, in the 64-bit two-part form (9.2's option
(a), `B2I_load64`), and the decline is gone.

**What it does.** `Lane.widenLanes` names the species a widening load reads: the narrow species of
the int species' own width where it exists, and otherwise the next width up, so eight and sixteen
int lanes keep their bytes and four int lanes - and two, on a 64-bit host - read the next species
up: an eight-lane 64-bit byte load converted in two parts, four at two int lanes.
`Analysis.narrowGroups` is that load's part count, the kernel's rather than a body's because every
body's loop must agree on the iteration it covers. The loop computes the groups of one iteration,
each group's narrow load reading the whole iteration's species once from the iteration's first row
and converting its own part (`Slots.rowGroup`); the bound rounds down to a whole iteration and the
remainder goes through the masked epilogue one group at a time. A narrow store whose species is
wider than a group is always masked, since the last iteration has no next group to write over the
lanes past its own. Form (b), the 128-bit four-part load, is not used: the spike measured it
within noise of (a) at 128 bits (0.97 to 0.99 of it) and 10 to 13 percent faster at 256, where the
emitter already reads the int width's own species.

**Artifacts and tests.** `width_audit.json`'s 128-bit column loses the ten `declined: a BYTE column
has no widening load at 4 int lanes` cells and names them; `emitted_bytes.json` gains the 128-bit
`TINYINT` method hashes; `VarkaFamilySwitchSuite`'s recorded
declines and the 28 `lanesOverride=4` lines of `matrix/skips.tsv` follow, because those tests now
pass; coverage and the docs' 128-bit column are regenerated.

**Measured** (`VarkaNarrowEmitBenchmark`, the benchmark of step 2, six runs at
`-XX:MaxVectorSize=16`, both locks, the idle gate and the pinned cores; the record is
`results/VARKA-329-partload.txt`; G rows/s, medians, the rungs 16384, 262144 and 1000000 in cache):

| shape | WIDENED | NATIVE | N/W |
| :-- | :-- | :-- | :-- |
| compare, in cache | 5.2, 5.1, 5.1 | 36.3, 36.5, 34.3 | 6.97x, 7.10x, 6.77x |
| compare, past L3 | 5.0 / 2.2 (8388608), 4.8 (33554432) | 30.0, 20.5 | 6.0x / 13.6x, 4.23x |
| add-then-compare chain, in cache | 2.3, 2.3, 2.2 | 35.8, 36.2, 33.7 | 15.66x, 15.99x, 15.03x |
| that chain, past L3 | 2.2, 2.2 | 28.5, 20.6 | 12.93x, 9.37x |
| mixed `b < i`, in cache | 5.6, 5.5, 5.4 | 5.6, 5.5, 5.5 | 1.00x at every rung |
| `CASE ... TINYINT` store, in cache | 3.7, 3.7, 3.6 | 56.1, 57.6, 41.1 | 15.24x, 15.70x, 11.27x |
| that store, past L3 | 3.6, 3.3 | 19.1, 14.4 | 5.36x, 4.39x |

**Verdict.** The decline is gone and the mixed shape now runs at the int lane's own rate with the
same class bytes in both arms, where both arms declined before. But wherever every value is narrow,
NATIVE is 4.2x to 16x faster, far outside prediction 5's ceiling of 4.0 and past the 1.3 band in
which 3.5 keeps WIDENED the default. That is the lane's ratio (16 byte lanes against 4 int lanes)
and not a tuning miss: the emitted compare keeps 0.70 to 0.72 of the hand loop's WIDENED rate at
128 bits (5.2, 5.1, 5.1 against 7.3, 7.3, 7.1 G rows/s in `results/VARKA-329-spike.txt`). So
3.5's per-width default now has its 128-bit case, and the choice is the owner's (question 1,
section 8); nothing here changes the default. One measured fact is not explained: the chain reads
2.2 to 2.3 G rows/s in every JVM, and the compare falls to the same plateau in one of the five JVMs
that run WIDENED, from the 262144-row rung on - 9.2's 128-bit bimodality, with the compiled loops
not profiled here; the record lists both under "not established at 128 bits". One latent deviation
sits below this width and no test draws it: at two int lanes a kernel reading both a byte and a
short column sizes the short load by the byte column's four parts, so it reads an eight-lane
128-bit short species where a four-lane one would do - legal above the platform's maximum, where
the Vector API falls back, so a cost on a 64-bit host and not a wrong answer.

### 9.7 Review of 23 October 2026

A `/code-review high` of the row's diff found fifteen things. The numbered list below is the
record their comments cite; each was fixed or answered as stated.

1. `withChildren` rebuilt `WidenLane`/`NarrowLane` through the one-argument constructors and
   dropped the target. The rebuild passes the target, and the withChildren round trip and the
   narrow-draw test pin it.
2. The canonical renderings omitted the target, so two conversions shared a shape hash, class
   name and executions identity. They render `(widen:TARGET ...)` and `(narrow:TARGET ...)`, and
   the lane-type suite pins the rendering.
3. `VarkaReplay` picked the entry point by `lane == INT`, while the runner dispatches by
   `lane == LONG`; a BYTE or SHORT kernel threw. The replay dispatches as the runner does, and
   `VarkaReproductionSuite.narrowKernelReplays` replays a BYTE kernel.
4. `NullSafeEqual` was built without the lane guard `compare` has, so a mixed-lane pair threw
   instead of declining. It declines with the lane's reason, and the compiler suite pins it.
5. The `greatest`/`least` fold had no lane check for the same reason. It declines, and the
   compiler suite pins the reason.
6. `InRanges` checked pairing, order and disjointness but not the bounds against the child's
   lane, and the emitter narrowed an out-of-range bound silently. The constructor refuses a
   bound the lane cannot hold, and `VarkaLaneTypeSuite` pins both refusals and the accepted
   extremes.
7. `predicateOnce` recorded the fused-budget message whenever `fitsBudgets` failed, including a
   narrow refusal. It records the refusal's own reason and keeps the budget message otherwise.
8. The coverage writer's `columns` map lacked y1, y2, s1 and s2, which 22 rows read. The map
   names them, and the coverage table is regenerated.
9. The IR grammar drew only the one-argument conversions. `drawNarrowShape` draws target-carrying
   `WidenLane` and `NarrowLane` nodes into BYTE, SHORT and INT; the withChildren and round-trip
   tests consume it, and the emitter fuzzer's committed sequences keep no narrow species because
   not every host's width names one.
10. A narrow-target `NarrowLane` away from a root emitted the untruncated value while the
    reference truncates. The walk truncates through the narrow species (`I2B`/`I2S`) and
    sign-extends back, and a narrow-suite test pins the emitted answer against the reference.
11. Two `LaneType` switches ended in `default ->`. They list the lanes explicitly.
12. The preferred-width audit asserted no decline, which fails on a 128-bit host for the declines
    the committed census records there. The assertion admits a decline only where the census
    records it for that width, with its reason; a unit test proves both directions, and a forced
    128-bit census on any capable host compares live declines with the file's 128-bit column.
13. The narrow emitter suite returned early when `narrowRefusal` fired, so two configurations
    skipped every check silently. The suite cancels through `assume` with the refusal named, the
    matrix carries a `declines` line per configuration, and a refusal for any other reason fails.
14. `narrowRefusal` refused a narrow column read only for its validity word. The refusal now asks
    per node whether a narrow species is named, so `y1 IS NOT NULL` emits at four int lanes. A
    long-lane emission still declines: Analysis admits no narrow node there at all, so a lifted
    validity-only conjunct beside a bigint one stays with the row engine. The width audit, its
    128-bit column and the emitted bytes were regenerated.
15. The template warm-up left the narrow species and their conversions cold. The reach gate
    (`VarkaWarmupReachSuite`) counts every method the rows' kernels call, so the warm-up now
    has a byte and a short block - the lanes' loads, stores, compare, blend, min, max,
    broadcast and widening conversion, and the mask calls a selection makes - and the reach
    model enumerates all five lanes. Row 476 of the milestone plan still owns the measurement
    of the first narrow kernel on a fresh JVM.

### 9.8 Two `/code-review max` reviews of 23 October 2026: #987 and #989, combined

After both pull requests merged, each was reviewed at maximum effort, #987 against its squash
commit `a2fbcc74bb8` and #989 against `793f8b748b3`. Neither found a wrong answer reachable from
SQL. Each kept fifteen findings; three describe the same defect from both sides, and one of #987's
was already fixed by #989. "First" is the pull request that fixes it: the review fixes (bucket 1,
this record's pull request), the bytes-neutral refactors after it (bucket 2), the narrow emission
changes that move emitted bytes, landed with row 478 so the oracles move once (bucket 3), or a
row.

| Finding | What | Disposition |
| :-- | :-- | :-- |
| 989-1 | 41 `lanesOverride=4` skip lines with a reason 9.6 made false: 28 a merge brought back, 13 new | bucket 1: removed, the configuration run on the tree; 9.4's three statements marked superseded |
| 989-2, 987-7 | no fixture row with a narrow pair null on the left only; the "no nulls" fixture held narrow NULLs | bucket 2: a left-null pair in `mixed`, no narrow NULL in `dense` |
| 989-3 | the arithmetic suite's widened arm took the matrix base, so `narrowLanes=true` ran the native arm twice | bucket 1: both arms pinned |
| 989-4 | an arm that cannot widen passed with no kernel run | bucket 1: it cancels, naming the refusal |
| 989-5 | `narrow_range.smt2` proved a byte multiply in a 16-bit lane the emitter never uses | bucket 2: the check removed, the width read from `Lane.INT` |
| 989-6 | the oracle-share timing's filter cancelled the test on the arm it did not name | bucket 1: it skips that arm |
| 989-7 | `weightOf` weighs a wrapped narrow node 1 where it emits 3 | bucket 3 |
| 989-8 | narrow `abs` is a compare, a negate and a blend, null-skipping | bucket 3: one lanewise `ABS`, the negation node with an `abs` flag |
| 989-9 | the checked narrow multiply's int drive is per root, so the native arm splits a projection | bucket 3: `VarkaVectorIR.kernelLane` decides per kernel, needed once NATIVE is the default |
| 989-10 | the 18 always-on 2^24 SMALLINT slices cost about 78 s a build | kept always on, the owner's decision of 23 October 2026: the slices are row 330's own done-when |
| 989-11, 987-8 | the warm-up feeds the preferred narrow species, not the ones widened kernels use; dead `neg`/`abs`; no narrow `fromLong` | bucket 2: the reach gate models the `widenLanes` species, the warm-up warms them |
| 989-12 | negation is `mul` by -1, eight instructions on bytes where `NEG` is one | bucket 3 on the narrow lanes; the int and long lanes are row 482, measured first |
| 989-13 | `arithOver`'s narrow exception is dead and its javadoc false; no narrow range in `VarkaRangeAnalysis` | row 480 |
| 989-14 | four comments still said a checked multiply never reaches the overflow mask | bucket 1 |
| 989-15, 987-15 | `default` arms and `instanceof` ladders over the sealed IR in `isNarrowRing`, `laneBounds`, `narrowValuesIn`, `needsNarrowSpecies` | bucket 2: exhaustive switches, the two walks side by side |
| 987-1, 987-4, 987-5 | at four int lanes every group stores masked, reloads the iteration's narrow load, and the body repeats in int-only groups unpriced | row 479 |
| 987-2, 987-10 | a narrow entry first in a projection demotes later bigint entries; a validity-only narrow read declines a bigint kernel | row 480 |
| 987-3 | narrow arithmetic computed at int width with no wrap | fixed by #989 (`wrapAt`, the lane-relative reference) |
| 987-6 | the new `CAST(narrow AS INT)` arm has no coverage row | bucket 2 |
| 987-9 | a TINYINT condition choosing SMALLINT values declines | row 332, the narrow-to-narrow cast |
| 987-11 | an output's width has two unchecked sources | bucket 2: `CompiledVarkaProjection` checks them |
| 987-12 | the narrow suite's condition harness never held the masked body to the reference on a null-free batch, and one byte and short test never ran the dense body | bucket 1: both bodies run |
| 987-13 | the byte warm-up block throws above 512 bits and ends the round | bucket 1: the narrow blocks are skipped there |
| 987-14 | the `CAST(narrow AS INT)` coercion inserts is charged to the int-arithmetic family switch, though it emits nothing, and `VarkaFamily`'s text omits it | bucket 2: the cast is a relabel, as a `TIME` precision cast is |

Refuted on verification: #989's masked body untested, literal-slot leak and NULL `IntNeg` drift;
#987's composition-fuzz lane filter, the reference-narrows test and a coalesce lane mismatch.
Cut by the reviews' fifteen-finding caps and taken as they fall: the pull request and the
benchmark naming a SQL conf that does not exist - `narrowLanes` is an emit option no session
setting reaches (bucket 1: the javadoc corrected; the commit message cannot be); the two
truncation helpers and the narrow CASE reduced in each branch rather than once (bucket 3); the
five special cases of the checked narrow multiply (row 481, which needs a `NULL` mode on
`GuardedRange`); the moved `runAndCompareMixedStatus` and the canonical-rendering note (bucket 2). Row 476's done-when
presumed the narrow species were covered; bucket 2 is what makes that true.

### 9.9 Bucket 3 and row 478: the native arm as the default (23 October 2026)

`narrowLanes` ships true, tagged `widenedNarrow` for the off arm. Before the flip, the lane a
kernel drives at is decided once over all its roots (`VarkaVectorIR.kernelLane`): roots that agree
keep their lane, roots that differ with no long one share the int lane, and a long root beside
another shares none. Without it the flip would have sent the second entry of `SELECT y1 + y2,
i + 1` to the row engine (989-9). The compiler, the budget mirror, the emitter and
`CompiledVarkaProjection` all read it, and an entry that moves a native kernel to the int lane
where this width cannot widen is demoted with a reason that says so.

The emission changes: a narrow negation is the lanewise `NEG` (989-12; the int and long lanes are
row 482); a narrow `abs` is the negation node with an `abs` flag, one lanewise `ABS`, which at the
int lane reduces its own result and is not a ring node (989-8); a narrow `IfElse` is a ring node,
so a pick of two unreduced arms reduces once where its reader needs it; a `NarrowLane` to a narrow
target away from a store reduces by the shift pair, and the second truncation helper is gone; the
grouping weights count a wrapping narrow node's shift pair (989-7).

Measured (`results/VARKA-329-negabs.txt`, three runs a cell): on the native arm a byte negation
and abs run 3.0x to 3.5x faster in L1 and 2.1x to 2.3x at 512 KB at both 512 and 256 bits, and
1.15x to 1.26x at 2 MB where the store is memory bound; at 128 bits 3.4x to 3.7x, 3.2x to 3.6x
and 2.0x to 2.2x. On the widened arm abs gains 1.42x to 1.46x (1.19x to 1.20x at 128 bits) and
the negation 1.01x to 1.07x; the control shape, in neither change, moves 0.95x to 1.02x.

The bytes oracle moved only for the narrow coverage rows (34 at each width) and the aggregate
option-arm digests. Under `validityByWidth=false` the native arm needs no baked lane count, so 52
skip lines of the differential suite went stale and are removed; the rows that still decline there
are the checked multiplies (driven at the int lane, row 481) and the rows that read a narrow column
beside an int value. The matrix over the narrow, arithmetic, family and oracle suites and the
differential suite passes at the defaults, `narrowLanes=false`, `lanesOverride=4` and
`validityByWidth=false` with nothing unfused.

Left as they were, by design or to a row: an entry demoted for joining is not retried in a kernel
of its own (row 480, as for a lane mismatch); an int entry moves the kernel's native narrow entries
to the widened lowering, the mixed shape the arms tie on (9.5); and the IR fuzzers draw no narrow
arithmetic, narrow choice or `abs`, which row 352 owns.

### 9.10 The SMALLINT and TINYINT parity part (6 November 2026)

The census (`census.tsv`, `scope=in`) counted `SMALLINT` and `TINYINT` fused for 24 of 75 admitted
expressions each, against `INT` 48 of 90. The cheapest targets are the entries whose `fused_today`
holds `int` and not `short` (the same list for `byte`): 16 of them. This part is their admission
check, taken at master `511bb552070` with `dev/varka_emit.sh` on a byte and a short column for
each, and then the build of the ones that needed code.

| entry | rows | what master said | class | what this part does |
| :-- | :-- | :-- | :-- | :-- |
| `ifnull`, `nvl` | 359, 348 | fused already (rewritten to `coalesce`) | census only | coverage rows, census claim |
| `nvl2`, `between` | 359, 348 | fused already (`IF` over a null test; two comparisons) | census only | coverage rows, census claim |
| `&`, `\|`, `^`, `~` | 335, 329 | "unsupported expression": `onBitLane` is int and long | arm | `BitOp` on the narrow lane (`~x` is `x ^ -1` on that lane) |
| `bit_count` | 335, 329, 331 | "unsupported expression" | arm | the int `BitCount` over a `WidenLane` of the narrow value |
| `nullif`, `nullifzero` | 359 | "no null leaf for type tinyint" | lane | `NullValue` admitted on the byte and short lanes |
| `zeroifnull` | 359, 348 | "coalesce operand before the last is not a bare column" | arm | the null test of a widened narrow column lifts to the int lane |
| `date_add`, `dateadd` (and `date_sub`) | 329 | "non-integer day offset column of type tinyint" | arm | the offset is a `WidenLane` of the narrow column, admitted at `DAY_OFFSET` |
| `bigint` (the cast) | 332, 343, 350, 28, 364 | cast code | out | other work in flight; untouched |
| `ym_times_num` | 103, 329, 342 | interval product by a narrow factor | semantics | out: row 342's product rules |

Semantics, each against the row engine as the oracle:

* **Bitwise.** Spark's `&`, `|`, `^` and `~` keep the operand type (`bitwiseExpressions.scala`
  casts back to byte or short). The operation of two sign-extended values is sign-extended, so it
  is one lanewise operation on the narrow lane (native arm) or on the int lane that holds the
  values (widened arm) with no reduction of its own, in every evaluation mode. `Analysis.isNarrowRing`
  keeps `BitOp` out of the ring set, so a ring child (`(y1 + y2) & y3`) is reduced before it, as
  any non-ring reader does.
* **`bit_count`.** `BitwiseCount` is `Long.bitCount` of the sign-extended value for every
  integral type, so `bit_count(-1Y)` is 64. The landed int node is `bitCount(x) + ((x >> 31) &
  32)`, which is exactly that for a sign-extended int, so the narrow form is that node over the
  widening a `CAST(x AS INT)` is. The result is an `INT`, and no narrowing follows.
* **`nullif`, `nullifzero`, `CASE WHEN` with no `ELSE`.** `NullValue` is a broadcast zero in the
  lane's own species with its validity word clear; the emission was lane-generic already
  (`Lane.pushScalar`), and what refused the narrow lanes was the constructor and `nullLeaf`
  ("until a row tests its emission", 333 open point 3). This part is that test.
* **`zeroifnull`.** The analyzer spells `zeroifnull(y1)` as `coalesce(CAST(y1 AS INT), 0)`: the
  result is an `INT`, the literal being one, not a `TINYINT`. The null test reads the column's own
  validity word, which the widening does not change.
* **`date_add(d, y1)`.** Spark's `DateAdd` takes a byte or short offset as it is. The kernel reads
  it widened (a sign extension, exact in every mode), so the day sum wraps as the int offset's
  does and a calendar node above it still gets the runtime range guard, which reads any
  non-literal offset as a column shift.

Tests: `VarkaEmitterNarrowArithSuite` gained four, each over both arms: `&`, `|`, `^` over every
`TINYINT` pair (2^16 rows) and, for `SMALLINT`, the 256-operand slice of the arithmetic sweeps
against every short; `~` and `bit_count` over every value of both types; the bitwise operators,
`nullif` and `nullifzero` at the extremes `-128`, `127`, `-32768`, `32767`, `0` and `-1` under the
four null patterns at six lengths; and `date_add` over every byte and short offset. 34 coverage
rows (`VarkaCoverageSuite`) run through `VarkaCoverageDifferentialSuite` against Spark under the
defaults.

Owner decisions taken (the plan's recommended option each time): `NullValue` on the narrow lanes
is admitted now rather than waiting for row 352 (333 open point 3 says the row that needs it tests
it); `zeroifnull` of a narrow column is an `INT` kernel, as Catalyst types it; no new emit option,
since the narrow bitwise operations are the same IR on both arms.

Left for follow-up parts: the casts to and from the narrow types (332, 343, 350, 364), `bigint(x)`
among them; `ym_times_num` and the other interval factors (342); `bit_get` and `getbit`, whose
byte result is a byte root (335b); `equal_null` and `positive` (336, 333), which fuse for the
narrow types already and are claimed with their rows; `time_from_millis` and `time_from_micros`
with a narrow operand (329's census rows); the rounding and `mod` families (344, 346, 30); the
fuzzers' draw of narrow operands (352).


After this part the census reads `byte` and `short` 39 of 75 each (24 before) and `int` 49 of 90
(48 before; `date_sub`, which fuses for an int offset, was never claimed, and is now claimed for
all three offsets). The two types are still behind `int` by the entries in the follow-up list
above and by the entries `int` does not fuse either.
