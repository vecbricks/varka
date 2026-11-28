# VARKA-352: The fuzzers and the reference evaluator across types

*Changed on 28 November 2026: sections 11 to 14 are new and are the plan of what is left, written
for a builder with nobody to ask; sections 0 to 10 are kept word for word as the record. Where
section 11 differs from them it wins, and section 14 lists each difference.*

## 0. What exists now (admission check)

From master at `9aa6ee74c63` (30 October 2026), with row 481's unmerged step 1 read on
`varka-481-null-mode` (`cbfc59ce01c`) and the type plans not yet merged read on their branches
(`varka-350-366-plan`, `varka-360-361-plan`, `varka-362-363-plan`, `varka-346-plan`,
`varka-368-plan`). Catalyst test paths are under `sql/catalyst/src/test/{java,scala}/org/apache/
spark/sql/catalyst/expressions/codegen/varka/`, sql/core ones under `sql/core/src/test/scala/org/
apache/spark/sql/execution/`. The row's text is the milestone plan's 2.10 (a) to (e) and its
close-wave note ("352 grows beside each lane from wave 0").

1. **Three committed IR corpora, each pinned, each with its own seed.** `VarkaIrGrammar` draws
   shape `k` of a sequence from `shapeRandom(seed, k)` (`VarkaIrGrammar.java:355-357`): the int
   corpus at `FUZZ_SEED` (`:129`), the long one at `LONG_FUZZ_SEED` (`:905`), both lanes at
   `MIXED_FUZZ_SEED` (`:1161`). `VarkaEmittedBytesSuite` pins ten thousand shapes of each as a
   hundred block digests at two widths (`emitted_bytes.json`, `lanes.{4,16}.fuzz`, `fuzz_long`,
   `fuzz_mixed`), and `VarkaEmitCostCorpus` fits and audits the cost model on the first two
   sequences and on two wide and two composition seeds of its own
   (`VarkaEmitCostCorpus.java:147-148`, `:202-224`, `:233-261`). The grammar's javadoc states the
   rule: one `rnd.next*` more or less in a committed draw "would move every shape after it"
   (`VarkaIrGrammar.java:100-106`). Six more suites read the committed corpora: `VarkaWarmupReach`,
   `VarkaEmitterBudgetSuite`, `VarkaEmitterDriverTableSuite`, `VarkaEmitterFramesSuite`,
   `VarkaSmallTreeSuite`, `VarkaWithChildrenSuite` (and `VarkaReproductionSuite`,
   `VarkaKernelHistoryProbe` by seed and iteration).
2. **What the grammar draws.** Int trees with the calendar, the divisions and the range sets; long
   trees over the lane-generic subset; mixed trees with both conversions. It draws no narrow column
   leaf (`drawNarrowShape`, `:439-468`, wraps whole roots in conversions for the rebuild tests and
   is unpinned, "the emitter fuzzer's committed sequences must not draw a narrow species"), no
   floating lane (`converted` throws on one, `:474`), and none of seven nodes: `NullSafeEqual`, the
   five boolean nodes and `CondValue`, each listed in the reach tests with its reason
   (`VarkaIrFuzzSuite.scala:974-992`, `:1013-1016`): "a draw of it moves every seeded shape after
   it, the cost corpus ..., the warm-up reach and `emitted_bytes.json`, and the model's refit needs
   the owner's word".
3. **The awaiting-refit list is code.** `VarkaEmitCostSuite.scala:80-86` holds those seven nodes
   and fails if one is priced while listed; `VarkaEmitCostFit.PRICED_LANES` leaves the narrow lanes
   "unpriced until VARKA-352's refit" and the floating ones "until their kernels exist"
   (`VarkaEmitCostFit.java:92-101`); `ADDING_A_TYPE.md` step 13 sends every new node to the list.
   Ten type plans add to it: 347 (`DecimalColumn`), 30 (`IntDivide`), 157 (`WidenDecimal`), 359
   (`DecimalWord`), 350 (its division forms), 366 (`LeadingZeros`, `ReinterpretLane`), 361
   (`WideMul`), 362 (`PairMul`), 363 (`ScaleDown`) and 346 (each lowering); 481's step 1 adds
   `GuardedRange/NULL/<lane>`.
4. **481's step 1 is the worked pattern for a production that must not move the pins.** It adds
   `NULLING_FUZZ_SEED` and `drawNullingShape`/`drawNullingLongShape`, the committed draws behind a
   flag that is off for them, and a test that the nulling draw reaches the `NULL` guard at both
   lanes and that the committed draws "hold none"; "the corpora `emitted_bytes.json` and the cost
   tables pin do not move" (`cbfc59ce01c`, its message and its `VarkaIrGrammar.java` diff, the
   step-1 pull request #1176). The nulling draw joins the committed corpora at 481's step 3, the
   general form, which moves every checked narrow multiply's bytes anyway (`VARKA-481.md` 8). Every
   type plan since 341 writes the same rule: "a sequence of its own seed, so no existing hash moves"
   (341 3.4, 347 3.5, 348 3.6, 157 3.6, 359 2.2, 361 2.2, 362 2.2, 332 3.7, 30 3.6).
5. **The reference evaluator is one Java class, a switch per lane.** `VarkaReference`
   (830 lines): `value`/`cond` for the int lane (`:82`, `:354`), `longValue`/`longCond` (`:423`,
   `:556`), `mixedValue`/`mixedCond` (`:620`, `:632`); a floating lane's rows are raw bits ordered
   by `SQLOrderingUtil` (`:52-58`). Each switch is exhaustive with no `default`, so a type row's new
   node does not compile until the reference answers it (`:45-48`); `ADDING_A_TYPE.md` step 8 is
   that rule. `VarkaReferenceEvaluator.scala` is a thin Scala face over the int and long entry
   points.
6. **The IR fuzzer's values.** `drawInt` and `drawLong` (`VarkaIrFuzzSuite.scala:274-354`) and
   `runOneMixed` (`:436-470`) draw every column uniformly inside the grammar's bound
   (`VarkaIrGrammar.within`), five null patterns (`:171-200`: null-free, every fifth, alternating,
   all null, random) and the options from `VarkaEmitOption.TABLE` after the shape. No column draw
   knows its type beyond its lane; there is no edge value, special value or sweep. The narrow
   harness exists (`VarkaKernelChecks.runAndCompareNarrow`, `VarkaKernelChecks.java:483-498`).
7. **The Spark-level fuzzer's columns.** Twelve `VALUES` columns (`d`, `d2`, `i`, the month
   inputs, `l`, `l2`, `t`, `t2`, `dt`, `dt2`) and derived ones: three year-month intervals, `ntz`
   and `ntz2`, and the floating `x`, `x2`, `f`, `f2` picked from the differential suite's lists
   (`VarkaSparkFuzz.scala:109-131`). Every coverage row reading a `BOOLEAN`, `TINYINT` or `SMALLINT`
   column is filtered out (`:134-139`), as it is from the composition fuzzer
   (`VarkaCoverageCompositionFuzzSuite.scala:77-87`), though the differential suite's fixture has
   `b`, `b2`, `y1`, `y2`, `s1`, `s2` (`VarkaCoverageDifferentialSuite.scala:586-599`): 70 of
   `coverage.json`'s 337 rows, 20 over a boolean column and 50 over a narrow one. Its nested
   trees come from `VarkaCatalystGrammar`, whose types are `DATE`, `INT`, `BIGINT`, `TIME`, the two
   interval kinds and `BOOLEAN` with no column (`VarkaCatalystGrammar.java:59-67`, `:113-120`); it
   writes one cast (`:205`) and none from `casts.tsv`. No decimal column exists anywhere.
8. **The TLP check returns nothing on a raise, and never meets one.** `partitionBroken`
   (`VarkaSparkFuzz.scala:486-505`) runs the whole query and the three parts and, if any raised,
   reports only a raise the expected-errors list does not allow. It is called from
   `VarkaSparkDifferential.checkOnce` on both engines (`VarkaSparkDifferential.scala:327-333`) for
   compositions with `it % partitionEvery == 0`, `partitionEvery = 4`
   (`VarkaSparkFuzzSuite.scala:73`, `:162`). The safe pool is `iteration % 2 == 0`
   (`VarkaSparkFuzz.scala:283`), so every partition check is over the safe pool, by design
   ("the partition check (which only safe compositions take)", `:295`). The safe pool keeps every
   value inside the guards and the ANSI checks (`:160-168`), so the check almost never sees a
   raise: the ANSI gap and the pool coupling are one gap.
9. **The cross-mode properties have no home.** No suite compares a form's TRY, ANSI and LEGACY
   answers with each other; the conformance contract's overflow check compares each mode with
   Spark's `eval` separately (`VarkaTypeConformance.java`, its seventh check). `casts.tsv` has 106
   LEGACY pairs and 98 ANSI and TRY pairs; the census puts `try_add`, `try_subtract`,
   `try_multiply`, `try_mod` and `try_divide` in scope over the numeric and interval types.
10. **The Spark-level fuzzer has no CI nightly.** `varka-fuzz.yml` runs the IR fuzzer (one JVM a
    core, 40000 iterations a lane, the date's seed), the composition fuzzer, the variants and the
    series (`:111-205`, `:216-300`); `VarkaSparkFuzzSuite` runs only from `dev/varka_nightly.sh`
    (`:75`, `:140-144`) on the laptop. Row 392 already records that the IR fuzzer's per-type draw
    "needs the nightly running"; row 368 plans a job of its own in `varka-fuzz.yml` (`VARKA-368.md`
    0 item 9 and 4, on its branch).
11. **Per-type facts live in two places already.** `VarkaTypes` and `types.tsv` hold fourteen
    types' facts (thirteen and the declined `TIMESTAMP`), and each conformance suite holds its
    type's values (`VarkaTypeConformance.ConformanceType`, `VarkaTypeConformance.java:110-125`;
    `integralValues`, `:167-170`; the floating suites' specials with payloads,
    `VarkaDoubleConformanceSuite.java`). `dev/varka_new_lane.py` walks the fuzz grammar's column
    leaves by regex (`grammar_leaf_lanes`, `:154-167`) against a hard-coded owed map naming this
    row for `BYTE` and `SHORT` and 341 for `FLOAT` and `DOUBLE` (`:69-76`).
12. **The pinned and keyed outputs a fuzz change can move**, besides 1: `option_arms` in
    `emitted_bytes.json` (a digest over every shape, which moves with any coverage row, as 338 and
    341 recorded), `emit_cost_audit.json`, `sql/varka/coverage/reach/VarkaIrFuzzSuite.md`,
    `known_failures.tsv` (lane, seed, iteration), and `skips.tsv`, whose lines name tests by titles
    that carry seeds and counts (`skips.tsv:110`, `:126`, `:132`).
13. **The open grammar rows.** 431 (`NullSafeEqual`) and 432 (mixed-lane `BitOp` over a widened
    count, a narrowed popcount) are grammar gaps "taken with 352's refit". 433 is the composition
    fuzzer's compiled check carrying one literal table, which excludes the long kernels with an
    int slot (`VarkaCoverageCompositionFuzzSuite.scala:790-799`), a row of its own that the
    composition fuzzer's mixed kernels wait on.
14. **Row 499(c)** plans a `draws` skip kind, legal only on a test that draws from a seed, a set
    `VarkaMatrixRuns` names (`VARKA-499.md` 2.3); row 497 lands after or with it, since its
    coverage rows move the seeded draws again.

### What differs from the row text and the milestone plan

- **The three parts can be checked on raising, but not by comparing the three alone** (item 8):
  the outputs are evaluated only on each part's rows, so a raising output can raise in one part and
  not another legitimately. 2.4 separates the filter's raise from the outputs'.
- **Closing the ANSI gap also changes a stated design**: the partition check takes the full pool
  too (item 8). It moves no draw, since `partitions` is not drawn from the case's stream.
- **"Both fuzzers at every nightly" needs a CI job** that does not exist (item 10).
- **433 stays its own row** (item 13): 352's composition-fuzzer draws of mixed kernels depend on
  it (section 5).
- **The floating IR sequence is 341's** (its 3.4 and the owed map), and each new node's draw is its
  row's; this row builds the shared parts they plug into and the value sources.
- **The tie windows are 2.10 (e)'s, the region where a tie is possible.** Lemire's section 6
  bounds it (`q` in `[-4, 23]` for binary64, `[-17, 10]` for binary32, p. 9; the transcription
  `sql/varka/papers/lemire-2021-number-parsing-gigabyte-per-second.md`, on `varka-350-366-plan`,
  pull request #1180), which gives `s <= 4` and `s <= 17`. `VARKA-350.md` 3 item 5 and
  `VARKA-366.md` 0 item 3 name scales 0 to 2 and 0 to 15: where ties occurred over 366's admission
  inputs, an observation inside the bound. The fuzzer probes the whole possible region.
- **"The try_ arithmetic"** is read as `try_add`, `try_subtract`, `try_multiply`, `try_mod` and
  `try_divide` (item 9); `try_make_timestamp_ntz` is a constructor and is left to its row.
- **"The sampled regions of 2.2, 2.7 and the matrix"** is read as the cast matrix's regions (2.2)
  and the floating functions' (2.7), drawn by the fuzzers' value sources; the suites that sample
  them exhaustively stay their rows'.

## 1. Goal and non-goal

Both fuzzers draw every one of the thirteen types, from the pull request that lands the type's
lane, through one shared description of the type, without moving a pinned byte or price of an
existing shape between two named regenerations. The TLP oracle is taken over the milestone's
shapes on both pools and holds the three parts to raising alike; the two cross-mode properties are
checked over every legal pair and the `try_` arithmetic; and the Spark-level fuzzer runs in CI
every night. Not this row: each type's nodes, their reference semantics and their productions
(the type rows, through the parts below), the exhaustive sweeps and the sampled suites (343, 344,
346, 350, 366), Spark's own suites (368), the count arm (369), row 433.

## 2. Design

### 2.1 The shared extension point: a fuzz descriptor per type

What 352 builds once, up front: the descriptor, the draw rule and its check (2.2), the first
refit (2.3), the TLP raise rule (2.4), the cast productions and generators' common part (2.5),
the cross-mode arm (2.6) and the nightly job (2.7). Everything per type is the type row's, done
through these parts.

A record per type, keyed by `VarkaTypeId`, in a new test class `VarkaFuzzTypes` (catalyst, Java,
beside `VarkaIrGrammar`, so both fuzzers read it; sql/core's tests already import catalyst's):

    record FuzzType(VarkaTypeId id, Site<IrLeaf> ir, Site<SparkColumns> spark,
        ValueSource values, List<String> tlpShapes)
    sealed interface Site<T> permits Present, Owed, NotApplicable
    record Owed<T>(String row) ...; record NotApplicable<T>(String reason) ...

`VarkaFuzzTypes.of(VarkaTypeId)` is an exhaustive switch with no `default`, so a type added to the
registry fails to compile until it has an entry, in the shape of `VarkaTypes.Info`'s `Admission`
and `Conformance` (item 0.11) and of row 489's node descriptor. The parts:

- **`IrLeaf`**: the lane, the leaf builder (`ColumnRef(o, BYTE)`, 347's `DecimalColumn`, 359's
  word pair, 331's `BoolColumn`), and the sequence the type's own trees are drawn in: a seed and
  a draw function (2.2). `DATE`, `INT` and the year-month interval name the committed int corpus;
  `BIGINT`, `TIME`, the day-time interval and `TIMESTAMP_NTZ` the long one (338 3 item 5: "the IR
  grammar's long leaf already covers the lane"); the others a sequence of their own.
- **`ValueSource`**: how a column of the type is drawn, as lane bits. A mix per value: an edge or
  special one draw in four, uniform inside the bound the grammar sets otherwise, null by the null
  pattern. Its edges are the one list of the type's values: the conformance suites' lists move into
  the descriptor and `ConformanceType` reads them (row 495's "one home"), and its specials and
  sweeps are 2.5's.
- **`SparkColumns`**: the fixture columns (names, SQL type, whether derived), a cell drawer each
  for the safe, hostile and wide pools, and the `VarkaCatalystGrammar.Type` they are read as.
- **`tlpShapes`**: predicate templates over the type's columns for the partition check (2.4).

What reads it: the IR fuzzer's three column draws (item 0.6) take values from `values`; the
Spark-level fixture's columns, pools and the catalyst grammar's columns map come from `spark`;
`dev/varka_new_lane.py` reads the `Owed` and `Present` sites from a generated
`sql/varka/fuzz/types.tsv` (written by a regeneration test, as `types.tsv` is by
`VarkaTypesSuite`), replacing its regex and its owed map; and a test fails when a type the registry
admits on a lane has an `Owed` IR site naming a row that is done. A type row's first pull request
turns its sites from `Owed` to `Present`: that is the "one agreed way".

### 2.2 The IR grammar across types, and the draw discipline

What moves a pinned output (items 0.1, 0.3, 0.12) is a change to the draws of a committed
sequence: a production, a weight, a bound, or the order of `rnd` calls before the shape is done.
So:

1. **A committed sequence is frozen between refits.** `FUZZ_SEED`, `LONG_FUZZ_SEED`,
   `MIXED_FUZZ_SEED` and the cost corpus's four seeds change their draws only in a refit (2.3).
2. **A new type draws in a sequence of its own** (2.1's `IrLeaf`): `fuzz_<type>` for the narrow
   lanes, the floating lanes (341), the 64-bit decimal (347) and the lane pair (359), the boolean
   leaves (331's nodes, as conditions over int trees). Each sequence is drawn by the generator of
   its lane (`Shapes`, `LongShapes`, `MixedShapes`, parameterised by the leaf), so the type meets
   the existing nodes, and a type's own test runs it against the reference.
3. **A new node on an existing lane draws in a side sequence** of its row's seed, behind a flag the
   committed draws leave off, with a test that the committed draws reach none of it: 481's pattern
   (item 0.4). It joins the committed sequences at the next refit, or at its own row's
   regeneration where that row moves the bytes anyway (481's step 3).
4. **What a new sequence pins.** Its blocks are added to `emitted_bytes.json` under a new key
   (`lanes.*.fuzz_<type>`), two thousand shapes as twenty blocks, so a refactor of the emitter is
   proven over the new lane too and no existing digest moves. A side sequence is unpinned, as
   481's and `NARROW_FUZZ_SEED`'s are. Five such sequences add ten thousand emissions to the
   thirty thousand fuzz shapes the bytes suite emits today: prediction 7.
5. **Values after the shape are free, and new value kinds take a stream of their own.** A
   committed sequence's lane values are not pinned (the bytes and the cost model read shapes only),
   but `known_failures.tsv` and the op-cap scans replay them; a new value kind (2.5) is drawn from
   `new Random(~(seed * 1000003L + k))`, the precedent of the Spark fuzzer's wider pool and the
   composition fuzzer's exact grouping, so a draw of the old kinds is the value it was.
6. **A test title carries its seed and count**, as today's do, so a `skips.tsv` line follows the
   sequence and not the order of tests; every new seeded test joins 499(c)'s seeded set.

A check makes rule 1 mechanical: `dev/varka_bytes_moved.py <base>` prints every key of
`emitted_bytes.json` whose digest changed against `<base>` (a coverage row, a fuzz block, an option
arm), and nothing for an added key. `ADDING_A_TYPE.md` step 9 gains it as its check (an empty list
but `option_arms`, which every new coverage row moves), and a refit's commit quotes its output.

### 2.3 The refits

A refit folds the side sequences and the awaiting-refit nodes into the committed corpora,
regenerates every pinned output once and refits the cost model. From 335a's record
(`VARKA-335.md` 9.2a), a refit moves: `emitted_bytes.json`'s fuzz blocks, both price tables and
`emit_cost_audit.json` (the fitted margins stated before and after), `VarkaWarmupReach`,
`VarkaEmitterBudgetSuite`'s ladder pins, the wide shapes' loop counts under `predictGrouping`,
`VarkaIrFuzzSuite`'s op-cap draw pairs (found again by a scan), the reach report, and the
`awaitingRefit` set and `PRICED_LANES`, each entry leaving as it is priced. No coverage row's bytes
move. 335a's refit was paired against its base on a benchmark of the 64-tail ladder because a
price change regroups shapes; a refit here does the same, on the runners, before it merges
(`VarkaSharedPrefixBenchmark` and `VarkaEmissionBenchmark`, alternated rounds at each width as
335's 9.2a did, with the corrections row 409 made to its first table).

The schedule is the owner's (335a's refit needed his word; question 1). Proposed: **refit A** in
352a's last step, taking 431, 432, the boolean nodes and `CondValue`, and the narrow leaves (the
narrow lanes join `PRICED_LANES`); **refit B** at the close, taking every node the type rows put on
the list and the floating and decimal lanes into `PRICED_LANES`. Between them a type's nodes stay
unpriced and weight-bounded, which every type plan already assumes. What A can price depends on
merge order: the floating lanes only after 341's kernels merge. 481's nulling draw is not a refit's:
it joins at 481's step 3 (item 0.4).

### 2.4 The TLP oracle over the milestone's shapes, with its ANSI gap closed

**The shapes.** Each descriptor's `tlpShapes` lists 2.10 (a)'s predicates for its type:

- **`FLOAT`, `DOUBLE`**: `x = x2`, `x < x2`, `x <=> x2`, `x = -0.0D`, `x > 0.0D`,
  `x = CAST('NaN' AS DOUBLE)`, `isnan(x)`, and the same over `f`.
- **`BOOLEAN`**: `b`, `NOT b`, `b = b2`, `b AND b2`, `b OR i > 3`, over columns with NULL leaves.
- **`TINYINT`, `SMALLINT`**: `y1 > 200`, `y1 < -200`, `y1 = 127`, `s1 > 40000`, `s1 = -32768`: a
  literal above the type, below it and at its bound, the three `UnwrapCastInBinaryComparison`
  rewrites.
- **`DECIMAL`**: `d18 < d19`, `d18_9 = d38_10`, `d17_7 > d19`, across the 18 to 19 boundary.
- **`TIMESTAMP_NTZ`**: `ntz < d`, `ntz = CAST(d AS TIMESTAMP_NTZ)`, `ntz >= d2`.

A composition that takes the partition check runs it on its own `WHERE`, as today, and on one
shape drawn from a stream of its own (rule 5), alone; so the main stream's compositions are as
they were.

**The pools.** The check is taken by `it % 8 == 0` (safe) and `it % 8 == 1` (full), a quarter of
the compositions as now, both pools.

**The raise rule.** For a filter `w`, on each engine, seven queries: the whole `SELECT s FROM fz`;
the parts `P_k = SELECT s FROM fz WHERE w_k` for `w`, `NOT (w)` and `(w) IS NULL`; and the
filter-only parts `F_k = SELECT 1 FROM fz WHERE w_k`. Each `F_k` evaluates `w` on every row, so:

1. **The filter's raise is shared.** The three `F_k` all raise or none does; when they raise, they
   raise one condition, or two that `racingErrors` allows (after the one-partition rerun that
   `disagreement` already makes, VARKA-381).
2. **The outputs' raise is partitioned.** When no `F_k` raises, the whole raises exactly when some
   `P_k` raises, and some `P_k` raises the whole's condition.
3. **The rows are partitioned**, today's check, when nothing raises.
4. **The engines agree query by query**: each of the seven outcomes is compared between the row
   engine and Varka by `difference` and `unexpectedRaise`.

Item 4 decides: a property 1 to 3 that fails on both engines alike is a fact about Spark (an
optimizer rewrite that changes which rows reach a subexpression of `NOT (w)` or `(w) IS NULL` would
show this way), counted and printed per composition, not failed; one that fails on Varka alone
fails item 4 too and is a finding. The first nightly's count of Spark's own asymmetries is
recorded in section 10, each read and named. The cost: a checked composition runs fourteen
queries instead of eight.

### 2.5 The generators

- **Floating.** Specials on in every draw, never off: both zeros, NaN canonical, with its sign bit
  set, with payload 1 and signalling (341 3 item 4), both infinities, the extremes, the smallest
  subnormal and `MIN_NORMAL` with its neighbours; then 2.2's sampled regions for the 12 pairs (each
  integral boundary and its neighbours, `+-2^31`, `+-2^63` and their neighbours) and 343's
  `+-2^51` to `+-2^53` (`VARKA-343.md` 3 item 5); then
  uniform bit patterns per exponent band. At the Spark level a NaN with a payload has no SQL
  spelling, so the fixture spells the canonical NaN and the IR and composition fuzzers carry the
  payloads, as 341 found. The floating fixture keeps 341's batch-pruning setting
  (`withFloatingPruningOff`, SPARK-58482).
- **Decimal shapes.** The seven of 2.10 (c), `(9, 2)`, `(17, 7)`, `(18, 0)`, `(18, 9)`, `(18, 18)`,
  `(19, 0)` and `(38, 10)`, at the four null densities, taken in turn: iteration `k` of the decimal
  sequence draws shape `k mod 7` at density `(k / 7) mod 4`, so every pair of the 28 is met each 28
  draws, and the Spark fixture carries one column of each shape whose null density is drawn per case
  from the four. Values: digit counts uniform up to `p`, the bounds `+-(10^p - 1)`, zero, and
  garbage slots under nulls (347 3 item 5).
- **Decimal operand types from Spark's rules.** Where the catalyst grammar puts two decimals under
  an operator, it asks Spark's expression for the result type (`Add`, `Multiply` and the others,
  their `dataType` over two typed attributes) instead of copying the rule, so a drawn tree's type
  is Spark's and `allowPrecisionLoss` is followed.
- **Narrow integers.** The type's range edges and their neighbours, and literals just outside it
  (which drive 2.4's rewrites).
- **Tie windows.** The decimal source draws exact midpoints inside the windows and one unit either
  side, and the sequence asserts on every draw that a midpoint test outside the windows is false
  (`s <= 4` for `DOUBLE`, `s <= 17` for `FLOAT`, the bound of Lemire's section 6).
- **Casts from `casts.tsv`.** `VarkaCatalystGrammar` gains a production family `CAST(<from> AS
  <to>)` and `try_cast(...)`, one per legal pair of the case's mode (98 under ANSI, 106 under
  LEGACY), drawn when both types have a `Present` Spark site, so a type's pairs turn on with its
  columns; the file is read through the reader row 328's suite holds to `Cast.canCast`.

*3 November 2026, from `READING_FUZZING.md` 2 (a) items 1 and 2:* two generators the list lacks.
- **Checked arithmetic that overflows.** `VarkaIrGrammar` draws a checked mode for `IntArith` and
  `IntNeg` only where the operands' bounds rule overflow out (`mode(boolean safe)`, the long
  grammar's add and subtract, `mode(long bound, long limit)`, and `IntNeg`'s int-bound check), so
  `VarkaReference.intArith`'s null on overflow and the `FAIL` mode's must-decline path are never
  drawn, nor a nulled lane under an `IfElse` or a second output. A side sequence on row 481's
  pattern draws the mode with its bound past the limit over data in which some rows overflow and
  some do not, YARPGen v2's split of the iterations into two subsets (PLDI 2023 section 3.5).
- **Values that tie.** Two columns uniform over `COLUMN_BOUND` are equal about once in 5 million
  rows, and a column almost never equals a literal, so a lowering that emits `<` for `<=` passes
  the fuzzer on non-null rows. A small per-batch pool, holding the literal table, seeds a share of
  the cells, drawn on a value stream of its own (2.2 rule 5) so no existing draw moves, and is
  proven on a planted `<` against `<=` mutant that the fuzzer without the pool does not catch.

### 2.6 The two cross-mode properties

A test of its own in `VarkaSparkFuzzSuite`, "the cross-mode properties hold over `casts.tsv`'s
pairs and the try arithmetic (seed ..., n)", stratified so that iteration `k` takes item `k mod N`
of the list: the 98 ANSI pairs, then each `try_` function at each census type. Its fixture is the
composition fixture with a row id `rid` (an extra literal per `VALUES` row), the hostile pool on.
On each engine, with `g` the `try_` form and `f` the plain one:

1. `T = SELECT rid, g(x) FROM fzx`, and `N` the ids where `T` is null over non-null operands.
2. Under ANSI, `SELECT rid, f(x) FROM fzx WHERE rid NOT IN (N)` raises nothing and equals `T`.
3. Under ANSI, each row of `N` alone raises, or answers null (a cast that is null in every mode,
   as `NaN` to a decimal is).
4. Under LEGACY, the query of 2 equals it: "ANSI equals LEGACY where ANSI does not raise", except
   on the pairs of the named exception, the double rule of 2.8 (decimal to `INT`, `SMALLINT`,
   `TINYINT`), whose differing rows are counted and printed.

So a row's three modes are compared without a one-row query per row: the hostile rows are none to
two per case. Each query is also compared between the engines. A pair Varka does not fuse is
still checked (the property is Spark's too); the done-when counts the pairs `casts_fused.tsv`
marks fused at that nightly, and the test prints that count.

### 2.7 The nightly and the matrix

A step of the one scheduled run in `varka-fuzz.yml` whose workflow change row 537 owns (section 5):
`VarkaSparkFuzzSuite`'s three seeded tests at the date's seed, sized to the step's cap (measured on
its first run; the laptop's nightly runs 50000 compositions in one JVM), one JVM, within the fork's
40-job budget. The option matrix already runs the three catalyst fuzz suites and the sql suites by
`VarkaTestReach`; the new tests join 499(c)'s seeded set, so a by-design disagreement under a
configuration is a `draws` line, never a stale `fails` line.

## 3. Tests and proofs

1. **The descriptor table** (`VarkaFuzzTypesSuite`): every registry type has an entry; an `Owed`
   site names an open row; `sql/varka/fuzz/types.tsv` matches; each `ValueSource` draws all its
   edges within a fixed number of draws; `dev/varka_new_lane.py --check` passes off the file.
2. **Each new sequence's reach test**, as the three today: the node types its lane admits, minus a
   listed reason per absent one; and each side sequence's "the committed draws hold none".
3. **The bytes rule**: `dev/varka_bytes_moved.py origin/master` empty for every existing key in
   352a's first steps, and its output quoted in the refit's commit.
4. **The partition check**: the existing planted tests (`VarkaSparkFuzzSuite.scala:346-354`), plus
   a planted kernel that raises in `WHERE p` and not in `WHERE NOT (p)` (property 1, on Varka only),
   one whose output raises on a row only one part holds (passes property 2), and a reproducer per
   case under `sql/varka/fuzz/spark/`.
5. **The cross-mode arm**: a planted TRY kernel that nulls a row ANSI does not raise on fails it;
   every one of the 98 pairs and the `try_` items is reached in one pass (`N` iterations).
6. **The landed types' draws**: `BOOLEAN`, `TINYINT`, `SMALLINT`, `TIMESTAMP_NTZ` and the floating
   columns drawn at the Spark level and in the composition fuzzer with the coverage-row filters
   gone; the narrow sequence run against the reference at both `narrowLanes` arms.
7. **The matrix**: dispatched on the branch of each step, the seeded tests under `draws` where a
   configuration disagrees by design.

## 4. The baseline, and predictions

No benchmark here but the refit's pair (2.3). Times are predicted, with the calibration table read
first (`dev/varka_calibration.py`), and stated at its observed ratio in the step's pull request.

1. **The landed types at the Spark level find something.** Lifting the boolean and narrow filters
   draws 70 coverage rows into compositions for the first time (item 0.7); a nightly at 50000
   compositions finds at least one disagreement or Spark asymmetry in its first week.
2. **The raise rule finds Spark's own asymmetries before Varka's.** Properties 1 to 3 fail on both
   engines on some compositions (`NOT` over a raising conjunct rewritten), and no Varka-only
   violation at the first nightly.
3. **The cross-mode arm**: no violation on the row engine outside the named exception; ten pairs
   fuse in some form today (row 486), so the fused count starts there.
4. **The partition check's added queries**: fourteen in place of eight on a quarter of the
   compositions, so the suite's query count grows by about a fifth, read from its info lines; its
   time is priced in step 3's pull request from the calibration table.
5. **Refit A** moves every fuzz block and no coverage row, and the 64-tail ladder is within its
   band at all three widths.
6. **No existing digest moves** in any step before refit A or between A and B, but 481's step 3.
7. **The bytes suite's time** grows by at most a third with the five pinned sequences (ten
   thousand emissions beside thirty thousand fuzz shapes and the coverage rows at two widths),
   scored against the suite's CI time in each sequence's pull request.

## 5. Order, files and size

The up-front part, 352a, in five pull requests:

1. **The descriptor** (2.1) with entries for the twelve types the registry admits today (all but
   `DECIMAL`, which is owed to 347) and `Owed` sites where a fuzzer cannot draw one yet,
   `sql/varka/fuzz/types.tsv`, `dev/varka_new_lane.py` reading it, `dev/varka_bytes_moved.py`, the
   IR fuzzer's three column draws taking `ValueSource`s from a stream of their own; no draw moves.
2. **The landed types**: the narrow sequence (`fuzz_byte`, `fuzz_short`, pinned under new keys),
   the boolean columns, narrow columns and their pools at the Spark level and in the composition
   fuzzer, the catalyst grammar's new types and the TLP shapes for them; the composition fuzzer's
   mixed kernels with an int slot wait on row 433.
3. **The TLP oracle** (2.4): the pools, the raise rule, the drawn shapes.
4. **The cross-mode arm** (2.6), the cast productions (2.5), and the Spark-level nightly step
   (2.7).
5. **Refit A** (2.3), on the owner's word, with its pair.

Then **per type, riding on each type row's first pull request** (the row's own size): its
descriptor sites from `Owed` to `Present`, its sequence or side sequence with its reach test, its
`ValueSource` and sweeps, its Spark columns and pools, its TLP shapes, its cast pairs turned on.

- **341 (`FLOAT`, `DOUBLE`)**: the `fuzz_float` and `fuzz_double` sequences and the specials
  source; 342, 343, 344 and 346 draw their nodes there.
- **331, 332, 27 (`BOOLEAN`)**: the boolean nodes, and 27's `BoolOutput` (left to this row by
  `VARKA-27.md` 3 item 8), join the int corpus at a refit; 332's cast compositions draw in their
  side sequence.
- **332, 330, 481 (the narrow types)**: the casts and the checked arithmetic in side sequences
  until a refit.
- **30, 104 (integral division, `BIGINT` arithmetic; existing types filled in, 1.1 item 6)**:
  `IntDivide` in its side sequence; `try_mod` and `try_divide` in the cross-mode list.
- **338 to 340 (`TIMESTAMP_NTZ`)**: the Spark columns and TLP shapes in 352a; 339's `AddMonths` in
  the mixed reach test.
- **347 to 351, 157 (the decimal within 18 digits)**: `fuzz_decimal` with the shapes sweep, and the
  Spark decimal columns `(9, 2)` to `(18, 18)`.
- **359 to 364, 366 (the lane pair)**: `fuzz_decimal_pair`, the columns `(19, 0)` and `(38, 10)`,
  the tie windows.
- **337 (`hash`)**: the hash forms in the Spark grammar, once 337's coverage rows exist, over
  every type with columns.

And **352b, the close**: refit B, the done-when read type by type against the descriptor table (no
`Owed` site left), the cross-mode count against `casts_fused.tsv`, section 10.

| file | what |
|---|---|
| `VarkaFuzzTypes.java`, `VarkaFuzzTypesSuite.java` (new) | 2.1 |
| `VarkaIrGrammar.java`, `VarkaIrFuzzSuite.scala` | the sequences, value sources, reach tests |
| `VarkaKernelChecks.java` | the narrow sequence's harness |
| `VarkaSparkFuzz.scala`, `VarkaSparkFuzzSuite.scala` | columns, pools, 2.4, 2.6 |
| `VarkaSparkDifferential.scala` | 2.4's seven queries |
| `VarkaCatalystGrammar.java` | the new types, the cast family, decimal result types |
| `VarkaCoverageCompositionFuzzSuite.scala` | the filters gone, the value sources |
| `VarkaEmittedBytesSuite.scala`, `sql/varka/emitted_bytes.json` | the new keys; refit A |
| `VarkaEmitCost*.java`, `VarkaEmitCostSuite.scala` | refit A |
| `sql/varka/emit_cost_audit.json` | refit A |
| `dev/varka_new_lane.py`, `dev/varka_bytes_moved.py` (new) | 2.1, 2.2 |
| `sql/varka/fuzz/types.tsv` (new) | 2.1 |
| `.github/workflows/varka-fuzz.yml`, `dev/varka_nightly.sh` | the Spark-level step |
| `sql/varka/ADDING_A_TYPE.md` | steps 8, 9 and 13 say how a type takes the parts |
| `sql/varka/fuzz/expected_errors.tsv` | new conditions |
| `sql/varka/matrix/skips.tsv` | `draws` lines |

**One scheduled run.** Three plans add scheduled work to `varka-fuzz.yml`: row 537 (the sweeps on
CI, pull request #1183), row 368 (the random-literal checks, #1182) and this row (the Spark-level
fuzzer). They are steps of one scheduled run: 537 owns the workflow change, and 368 and 352 add
steps to it, within the fork's 40-job budget.

**Size: medium as the row says for 352a's steps 1 to 4, about five days; refit A about two (the
regeneration is mechanical, the pair is not); each type's share half a day to a day inside its own
row; the close a day.** Test time added at the defaults: the partition check and the cross-mode
arm in `VarkaSparkFuzzSuite`, priced in step 3 and 4's pull requests from the calibration table;
the IR fuzzer's new sequences at its default 300 iterations each, of the order of the long
sequence's.

## 6. What it unlocks in the census

Nothing directly: the census counts what the compiler fuses. It is the done-when's "how it is
known" for every type (1.1 item 7) and the half of 1.1 item 1 that says each type is drawn by both
fuzzers.

## 7. Out of this row, and declines that stay

Out: each type's nodes, reference semantics and productions (the type rows, through 2.1 to 2.5);
the exhaustive sweeps and sampled suites (343, 344, 346, 350, 366); 368's suites and its nightly
job; 369's count arm; row 433. Stays as it is: the steering A/B (opt-in, `VARKA-280.md`
9); a NaN payload at the Spark level, which has no spelling.

## 8. Questions for the owner

1. **The refit schedule.** (a) Two refits, A in 352a and B at the close, each paired. (b) One at the
   close. (c) A refit with each type row. *Recommended: (a)*: seven nodes and two lanes wait
   unpriced today, 431 and 432 are blocked on it, and a refit per type would move every pin at
   every lane's landing, which 2.10 forbids. The caveat: refit A prices the floating lanes only
   after 341's kernels merge.

   *1 November 2026, by the owner's word:* (a): two refits, A in 352a and B at the close, each
   paired.

## 9. Done when

Row 352's text holds, with the corrections above: every type's descriptor has no `Owed` site; the IR
fuzzer draws every type in its sequence and the Spark-level fuzzer every type in its columns at
every nightly, the Spark one in CI; the TLP check runs on both pools with the raise rule and the
milestone's shapes; the cross-mode properties hold over the 98 pairs and the `try_` arithmetic, with
the fused count stated; the generators carry 2.5's specials and sweeps; every existing digest is
unchanged between refits and each refit's moves are listed; rows 431 and 432 are closed by it; and a
cross-type disagreement found is fixed or a named row.

## 10. Outcome

### 10.1 Step 1 of 352a, the descriptor (7 November 2026)

Built to 5 item 1: `VarkaFuzzTypes` and `VarkaFuzzTypesSuite` (catalyst, Java), an entry for each
of the fourteen registry ids (`TIMESTAMP` `NotApplicable`, by the registry's decline),
`sql/varka/fuzz/types.tsv`, `dev/varka_new_lane.py` reading it in place of its regex over
`new ColumnRef(` and its owed map for the fuzz grammar, `dev/varka_bytes_moved.py`, and the IR
fuzzer's three column draws (`drawInt`, `drawLong`, `runOneMixed`) taking their cells from the
value sources: the int corpus's day columns `DATE`'s and its month count `YM_INTERVAL`'s, the
mixed draw's int columns `INT`'s, the long columns `LONG`'s.

*What the descriptor says today.* IR site `Present` for `INT`, `DATE`, `YM_INTERVAL` (the int
corpus) and `LONG`, `TIMESTAMP_NTZ`, `TIME`, `DT_INTERVAL` (the long one), for `BYTE`, `SHORT` and
`BOOLEAN` (the cast sequence, `CAST_FUZZ_SEED`, its cells `castValue`'s) and for `DECIMAL` (the
decimal values' sequence, `DECIMAL_VALUE_FUZZ_SEED`, its cells the decimal draw's); `Owed` to 341
for `FLOAT` and `DOUBLE` alone. Spark site `Present` for the nine types the fixture's view holds a
column of, `Owed` to 352 for `BOOLEAN`, `BYTE`, `SHORT` and to 347 for `DECIMAL`. A `Present` IR
site is tested, not declared: 100 shapes of its seed must hold a node reading a column as the
type, and no form passes a bare int column - integer arithmetic over a column for `INT` and
`LONG`, a calendar field or day addition over one for `DATE`, `add_months`' month count for
`YM_INTERVAL`, the lane's column for `BYTE` and `SHORT`, a boolean or decimal column. The long
grammar has no node of `TIMESTAMP_NTZ`, `TIME` or `DT_INTERVAL`: their form is the long column
and the table marks it `(untyped)`. A `Present` Spark site is held to the view by
`VarkaSparkFuzzSuite`: each column is one of the view's output columns, of its SQL type, and
derived exactly when it is not a `VALUES` column. A value source's uniform arm is of its kind
(an integer by `within`, a finite float or double as its bits, a 0 or 1), from one value of the
case's stream whatever the kind.

*No draw moved.* The cells are the grammar's `within` draw from the case's own stream; the edge
arm (one cell in `-Dvarka.fuzz.edges`, off by default; a failure's label and replay carry it when
it is on) takes its coin and its edge from a stream of its own, and a test shows the main stream
is where it was with the arm on or off. The int corpus's bound of 2.5 million days clamps
`DATE`'s last day, 2932896, to it. Proven on the
fuzzer: `VarkaIrFuzzSuite`'s int, long and mixed draws with `-Dvarka.fuzz.answers`, 18603 batches
before and after, byte-identical (`cmp`), and 19203 lines after the review's fixes against the
step's first head. `dev/varka_bytes_moved.py origin/master` prints nothing,
`option_arms` included, and `dev/varka_regen_generated.sh --all` changed no file.

*What differs from 2.1.* The conformance suites' value lists did not move into the descriptor
(`ConformanceType` still holds them); the edges are the type's own range edges, listed again, and
moving the lists is step 2's, with the narrow sequence that draws them. `SparkColumns` holds names,
SQL types and the derived flag; the cell drawers, the pools, the catalyst grammar's `Type` and the
`tlpShapes` (empty lists) come with steps 2 and 3. The check that an `Owed` site names an open row
is only that the row is spelled as one: the plan's status is prose, and reading it is not built.
369's open point 1 (a drawer that renders a typed value as `VALUES` text) is still open: a
`ValueSource` yields lane bits and nothing renders them. The table is under the merge driver,
merged per row id like `types.tsv`; the driver keys its rules by file name but for this file,
which it keys by its path (`PATH_NAMES`), so the two names do not collide.
`varka_regen_generated.sh` rewrites it when owed and on `--all`, and the push check runs
`VarkaFuzzTypesSuite` with the other generating suites.

### 10.2 Step 2 of 352a, the landed types: its first part, the narrow sequence (8 November 2026)

*Where it is cut.* Step 2 is two pull requests at the seam between the fuzzers, 5 item 2 not
fitting the 1000-line cap in one: this one is the IR fuzzer's narrow sequence; the next is the
Spark-level half (the `BOOLEAN`, `TINYINT` and `SMALLINT` fixture columns and their pools, the
coverage-row filters gone at the Spark level and in the composition fuzzer, the catalyst grammar's
new types and the TLP shapes for them), whose fixture columns take a stream of their own (2.2 rule
5), since `drawData`'s hostile spots are drawn over the twelve columns' indices.

*What this part builds.* `BYTE_FUZZ_SEED` and `SHORT_FUZZ_SEED`, the sequences `fuzz_byte` and
`fuzz_short`, drawn by `LongShapes`, the lane-generic generator, parameterised by the lane (2.2
item 2); `VarkaIrFuzzSuite` runs each against the reference through the narrow harness and asserts
that both `narrowLanes` arms answered, and a reach test holds each to the node types the narrow
lane admits; `VarkaEmittedBytesSuite` pins two thousand shapes of each as twenty blocks at both
widths (2.2 item 4); `BYTE`'s and `SHORT`'s IR sites name the new sequences; and the integral and
boolean conformance suites read their values from the descriptor.

*How the plan's open points are settled, by its own rules.*

- **A pinned sequence follows the committed draws' discipline** (2.2 rule 1, the 3 November note):
  a checked mode of `IntArith` or `IntNeg` only where the tracked bounds rule overflow out, no
  nulling guard. The first column's bound is the lane's whole range, so wrapping arithmetic over
  it meets the lane's ends; every other column is drawn within half the lane's greatest value
  (`narrowColumnBound`), so the checked modes land on subtrees that read a column as well as on
  literals, and the reach test asserts both lanes draw a failing and a nulling mode over a
  column; the checked arithmetic that overflows is 2.5's side sequence, as at the other lanes.
- **What the narrow lane does not draw**, each because the compiler never builds it there: the
  constant division (Spark's `/` over a `TINYINT` is a `DOUBLE`, `div` a `BIGINT`), the range
  guard (a cast guards its wider source, the cast sequence's), the shifts (Spark widens a narrow
  operand to `INT`), the unsigned compares and the output narrowing to `INT`. `bit_count` is
  drawn though the compiler builds `BitCount` over the value widened to the int lane: the first
  run drew it at the narrow lanes and found the emitter adds the int lane's 32 sign bits to a
  negative byte's or short's popcount where Spark adds 56 or 48, a node the IR constructs and
  the emitter lowered wrongly. Fixed by row 580, 9 November 2026: the emitter adds the `64 - w`
  sign bits of a `w`-bit lane, and the narrow sequences draw `BitCount` again. The negation
  draws `abs` beside it at the narrow lanes, which the long lane's never does, and a condition
  may be a range set (`InRanges`, what `IN` over a narrow column compiles to), which it never is.
- **Owed to the Spark-level part:** the variants of a passing narrow draw (`VarkaVariantChecks`
  runs a case through the int or long harness only), and a nightly volume. The narrow tests take
  `varka.fuzz.narrowIterations`, the gate's 300, which the nightly's 40000 does not scale: the
  step budget's one nightly sample predates the suite's growth, so it shows no room to rely on.
- **The edge arm is on in the new sequences**, one cell in four (2.1's mix), from the value stream
  (2.2 rule 5); the committed sequences keep it off.
- **`option_arms` is not extended.** Its digest is over every shape of the three committed
  sequences, and adding the narrow ones would move an existing digest; the `narrowLanes=false` arm
  over the narrow sequences is held by the fuzz test's two-arm assertion, not pinned.
- **The value lists.** `TINYINT`, `SMALLINT`, `INT`, `BIGINT` and `BOOLEAN` conformance suites read
  the descriptor's edges, the lists being equal. The others stay the suites' own: `DATE`'s is the
  contract's range, narrower than the descriptor's Spark days, and `TIME`'s, the intervals' and
  `TIMESTAMP_NTZ`'s differ by values each type's row chooses, so their union is each type's share
  (5), not this part's.
- **No descriptor site moves from `Owed` to `Present` here**: `BYTE`'s and `SHORT`'s IR sites were
  `Present` through the cast sequence and now name their own; the `BOOLEAN`, `BYTE` and `SHORT`
  Spark sites stay owed to this row for the next part.

## 11. What is left, cut into parts a Sonnet-class agent can build (28 November 2026)

*New on 28 November 2026. Code facts were read on master `dbaa94ff498` and on the local branches
named below, in `/home/max/proj/vecbricks-varka`, with no JVM. This section stands alone: a
builder needs it, the `AGENTS.md` files and `sql/varka/ADDING_A_TYPE.md`. Where it differs from
sections 0 to 10 it wins, and section 14 lists each difference. Short names: `CT` is
`sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`, `CS` the
same path under `src/test/scala`, `Q` is `sql/core/src/test/scala/org/apache/spark/sql/execution`,
`FZ` is `Q/VarkaSparkFuzz.scala`, `FS` is `Q/VarkaSparkFuzzSuite.scala`, `DS` is
`Q/VarkaCoverageDifferentialSuite.scala`, and `FT` is `CT/VarkaFuzzTypes.java`. Every command runs
from the root of the part's worktree.*

### 11.1 The state on 28 November 2026

**What `PLAN.md` 1.1 item 1 asks of this row**: each new type drawn by "the IR fuzzer and the
Spark-level fuzzer (352)". `VARKA-354.md` step 2 reads it off `sql/varka/fuzz/types.tsv`: no
`Owed` cell. `PLAN.md` 12.5 lists row 352 as a gate under item 1, "347 before 352's decimal
cells".

**On master**, each with its command:

| what | command | prints |
|---|---|---|
| the plan (#1191) | `git log --oneline -1 5cdb032b9ef` | `5cdb032b9ef [VARKA-352] Plan the fuzzers across every type, with one way to add one (#1191)` |
| step 1, the descriptor (#1403) | `git log --oneline -1 4ca05bf6802` | `4ca05bf6802 [VARKA-352] Describe what the fuzzers draw of each type in one table (#1403)` |
| step 2's first part, the narrow sequences (#1412) | `git log --oneline -1 2acdd9a25eb` | `2acdd9a25eb [VARKA-352] Fuzz trees of TINYINT and SMALLINT values against the reference (#1412)` |
| the owed cells | `grep -c 'owed:' sql/varka/fuzz/types.tsv` | `4` (three Spark sites owed to 352, one line owed to 347 twice) |
| the decimal's line | `grep -P '^DECIMAL\t' sql/varka/fuzz/types.tsv` | `DECIMAL LONG present 20261101 dec decimal draw owed:347 - owed:347` (tabs) |
| the Spark fuzzer leaves out three kinds of row | `grep -c 'def leftOut(executable: String): Boolean' FZ` | `1` |
| the column-count test of row 495 (#1564) | `grep -c 'wanted.size === 18' FS` | `1` |
| the table's columns | `python3 -c "import json; print(len(json.load(open('sql/varka/coverage.json'))['columns']))"` | `35` |

The 35 columns: the 18 the Spark fixture builds today, the six boolean and narrow ones (`b`,
`b2`, `y1`, `y2`, `s1`, `s2`), and eleven decimals (`d9`, `d9b`, `d18`, `d18b`, `d9s4`, `d16s9`,
`d8`, `d19`, `d38`, `d38b`, `d3810`), from `coverage.json`'s `columns` object
(`scratch-352/columns.txt`).

**On local branches, never pushed.** `git branch --list 'varka-352*'` prints five:

| branch | tip, date | holds | verdict |
|---|---|---|---|
| `varka-352-fuzz-types` | `c76beb07a6a`, 20 November | on master `767dcddce50`: four commits of row 619 (merged since as #1676), then six of 352a step 2's Spark-level part (10.3 of its plan file): the `TINYINT`, `SMALLINT` and `BOOLEAN` columns in the Spark-level fixture, the catalyst grammar's narrow types and boolean leaves, the TLP shapes held to the view, the descriptor's three Spark sites `Present` | **part 352b starts from it**: the six picks `b0a4f7d7dce` to `c76beb07a6a`, 374 counted lines without 619's commits (`python3 dev/varka_msg_check.py size --base ae476949bcf --head varka-352-fuzz-types`) |
| `varka-352c-composition` | `5af95aa65e3`, 23 November | on master `f3b97c0b503`: five commits, step 2c (10.4 of its plan file): the composition fuzzer's compile-only tests draw every coverage row | **part 352c starts from it**: five picks, 179 counted lines |
| `varka-352-fuzz-types-pre-rebase-1120` | `0864573396e`, 20 November | 352b before its rebase onto row 619's branch | superseded |
| `varka-352a-pre-rebase-1392` | `c5c87132d39`, 8 November | step 1 before its merge | merged as #1403 |
| `varka-352c-composition-pre-rebase-1123` | `263474a65cd`, 21 November | 352c before its rebase | superseded |

The six commits of 352b, oldest first: `b0a4f7d7dce` (Draw TINYINT, SMALLINT and BOOLEAN columns
in the Spark-level fuzzer), `2f9cf392c52` (Fix the review's findings, alias the fuzz view, and
record row 619), `7de852f5ed1` (Plan: record the 700-composition run), `559a7ce2b43` (List the
narrow overflow, keep hostile narrow cells, and query the shapes), `7bbc5d6c853` (Plan: keep row
352's text through the rebase onto row 619's branch), `c76beb07a6a` (Fix the review's findings
after the rebase onto row 619's branch). The five of 352c: `37640940afb`, `118c4456a4e`,
`e1337f6450d`, `c3e7b091bed`, `5af95aa65e3`.

**What master changed under 352b since `767dcddce50`.** Row 495's #1564 added
`VarkaCoverageRows.columns`, `VarkaSparkFuzz.leftOut` (the rows over a `BOOLEAN`, narrow or
`DECIMAL` column) and the test "the fixture holds every column of coverage.json its draws read, at
its type", which counts 18 columns. The branch predates them and lifts the boolean and narrow
filter in its own `coverageRows`. 11.4 step 2 reconciles the two. `FS` has two more commits, row
335's and row 504's; `VarkaCoverageRows.scala` and `expected_errors.tsv` one each.

### 11.2 The parts

| part | what | counted lines | builder | starts from | waits on |
|---|---|---|---|---|---|
| 352b | the boolean and narrow columns at the Spark level | about 390 | Sonnet-class | master, six picks; if row 332's part 332c has merged, its seventeen interval-cast rows over the narrow columns are drawn too (11.4's stop rule) | nothing |
| 352c | the composition fuzzer's compile-only tests over every row | about 180 | Sonnet-class | master, five picks | nothing |
| 352d | the decimal columns at the Spark level, and the decimal's value source | about 220 | Sonnet-class | master after 352b | 352b, and row 347's part 347d (`VARKA-347.md` 11.2); not 347m, which no longer edits the descriptor |
| 352z | the row's record and its close: what moved, `types.tsv` with no owed cell | about 60 | Sonnet-class, or the lead | master after 352d | 352d, and question 1 of section 12 |

**No part is full strength**, and none is deferred by the owner's decision E1 of 28 November
2026: 352b and 352c are built code on branches, 352d is a fixture change with every expression
written out and checked in Python (11.6), and 352z is a plan edit.

**The order and its reason.** 352b first: 352d adds its columns beside 352b's and lifts the last
filter 352b leaves. 352c is independent and may land beside 352b; the two touch different blocks
of `VarkaCoverageRows.scala` (`git diff` of each shows the doc comments of `readsBoolean`,
`readsNarrow` in 352b and of `Filter` in 352c). 352d after row 347's part 347d, the order of
`PLAN.md` 12.5, so that the decimal load's forms are on master, under the option matrix, when the
Spark-level fuzzer first draws decimal columns.

**Landing the plan.** The new text of this file (sections 11 to 14 and the note at the top) goes
to master first, in a plan-only pull request titled `[VARKA-352] Plan: land the fuzzers' typed
columns and decide what moves`, which the lead opens.

### 11.3 Decisions taken here, each with its evidence

1. **What gates the close is the descriptor with no owed cell.** Item 1 names the IR fuzzer and the
   Spark-level fuzzer drawing each type; `VARKA-354.md` step 2 checks `types.tsv` for no `Owed`
   cell. The IR sites are all `Present` (`types.tsv`'s `ir` column). After 352b the three narrow
   and boolean Spark sites are `Present`; after 352d the decimal's Spark site and value source.
2. **Steps 3 to 5 of 352a, refit A and the close's refit B are not gates**, and 352z moves them to
   `m10/SCOPE.md` item 140 under the default of question 1 of section 12: the TLP oracle's raise
   rule and its second pool (2.4), the cross-mode properties and the cast productions (2.5, 2.6),
   the Spark-level fuzzer's CI nightly (2.7), the two refits (2.3), and the generators' sweeps of
   2.5 (specials beyond the value sources, the decimal shapes sweep, the tie windows). No clause of
   `PLAN.md` 1.1 names any of them (the triage of 27 November 2026,
   `~/varka-ops/m8-triage/part-1.md`, row 352: "Moves: TLP's ANSI gap, cross-mode properties, cast
   draws, which no clause of 1.1 names"). The owner's answer of 1 November 2026 to question 1 of
   section 8 scheduled refit A in 352a; rows 431 and 432, which it was to take, moved to milestone
   9 on 23 November 2026 (their notes), so its reason in this milestone is gone.
3. **The composition fuzzer's kernel tests keep their filters** (step 2d of 10.4). It is a third
   fuzzer, which item 1 does not name, and teaching its harness narrow, bitmap and sixteen-byte
   inputs is a part the size of 352b again. It moves with decision 2.
4. **The decimal columns are derived, as the floating ones are.** The Spark fixture's eleven
   decimal columns are computed from `l` and `l2` by the expressions the differential suite's
   fixture uses (`DS`, `fixtureView`'s `decimal` and `pair`), lifted to its companion object so
   that both fixtures build them from one text. One column differs: `d9s4` reads the row id `rid`
   in the differential view, which the Spark fixture has not, so the Spark fixture computes it
   from `l`, `l2` and `i` alone (11.6). Every expression fits its type for every long, so building
   the view cannot raise under ANSI: checked in Python over the long's ends and a sample,
   `decimal_columns_check: 3081694 cells of 11 columns fit their types`
   (`scratch-352/decimal_columns_check.out`).
5. **The decimal's value source is the long lane's edges at precision 18**: the bounds
   `+-(10^18 - 1)`, `+-(10^9 - 1)` (precision 9's), `-1`, `0` and `1`, of kind `INTEGRAL`. A
   lane-pair decimal's values are two words and are drawn by the IR's pair draw, not by this
   source; the javadoc says so.
6. **A decimal row's ANSI raise is listed, not avoided.** Two coverage rows cast a decimal to a
   narrower decimal (`CAST(CAST(d18 AS DECIMAL(12, 2)) AS BIGINT)`,
   `CAST(CAST(d9 AS DECIMAL(9, 4)) AS BIGINT)`), which raise under ANSI where the value does not
   fit, on both engines. 352d adds the condition both engines raise to `expected_errors.tsv`
   (11.6 step 5); the safe pool's promise ("every value inside the guards and the ANSI checks",
   `FZ`'s `drawData`) does not hold for those two rows, and the file's line says so.

### 11.4 Part 352b: the boolean and narrow columns at the Spark level

**Title:** `[VARKA-352] Draw TINYINT, SMALLINT and BOOLEAN columns in the Spark-level fuzzer`.
**Branch:** `varka-352b-spark-narrow`, from master (`varka-352-fuzz-types` exists).
**Builder:** Sonnet-class.

**B0.** On master, every line must print what it says, or the builder stops and reports:

    git log --oneline -1 b0a4f7d7dce   -> b0a4f7d7dce [VARKA-352] Draw TINYINT, SMALLINT and BOOLEAN columns in the Spark-level fuzzer
    git log --oneline -1 079d5553195   -> 079d5553195 [VARKA-619] Fuse a narrow column's null test through any chain of widenings (#1676)
    grep -c 'def leftOut(executable: String): Boolean' FZ                -> 1
    grep -c 'wanted.size === 18' FS                                      -> 1
    grep -c 'owed:352' sql/varka/fuzz/types.tsv                          -> 3

The last count is `3`, and `0` after step 3, whatever row 347 has merged: row 347's part 347m no
longer edits the descriptor (fix round of 28 November 2026, review 1, M1), so `DECIMAL`'s two cells
read `owed:347` until 352d fills them. The second line says row 619's fix is on master, so 619's
four commits on the branch are not picked.

**Step 1, the picks.** In the part's worktree:

    git cherry-pick b0a4f7d7dce 2f9cf392c52 7de852f5ed1 559a7ce2b43 7bbc5d6c853 c76beb07a6a

**Step 2, conflicts.** For each conflicted file: take master's side whole, then apply the
commit's hunks for that file by hand (`git show <sha> -- <file>`). Per file:

- `FZ`: keep master's `leftOut` and its use in `coverageRows`; make `leftOut` read the decimal
  columns only:

        def leftOut(executable: String): Boolean = VarkaCoverageRows.readsDecimal(executable)

  and its doc comment: "Whether the draws leave out `executable`: SQL that reads a DECIMAL column,
  of which the fixture has none yet (`VarkaFuzzTypes` owes the type's columns, VARKA-352 part
  352d). A column's name is the SQL that reads it, so the fixture's check of its columns asks the
  same of each name." Apply every other hunk of the commits: the six `VALUES` columns, the
  `narrowCells` draw from its own stream (`NarrowSalt`), the `FROM fz AS fz` alias, the `own`
  parameters of `drawData` and `laterData`.
- `FS`: keep master's test "the fixture holds every column of coverage.json its draws read, at
  its type", with its count changed from 18 to 24 and its comment's first line to "The draws leave
  out the rows over a DECIMAL column (`VarkaSparkFuzz.leftOut`)". In it, `drawData(new
  Random(495), 1, safe = true)` takes the two arguments the commits add: `drawData(new
  Random(495), 1, safe = true, None, new Random(496))`. Apply the commits' other hunks (the
  stream test, the shapes' test, the `drawData` and `laterData` call sites).
- `sql/varka/fuzz/expected_errors.tsv`: apply the commits' two edits to master's file (the
  families `Integral casts` and `Narrow arithmetic` added to two lines, and the
  `BINARY_ARITHMETIC_OVERFLOW` line).
- `sql/varka/fuzz/types.tsv`: take master's side; step 3 regenerates it.
- `sql/varka/plans/m8/PLAN.md`, `sql/varka/plans/m8/VARKA-352.md`: take master's side and apply
  nothing; step 4 writes the record.

**Step 3, commands.** With `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
SPARK_LOCAL_IP=127.0.0.1`:

    build/sbt catalyst/Test/compile sql/Test/compile                       -> [success]
    VARKA_FUZZ_TYPES_REGEN=true build/sbt 'catalyst/testOnly *VarkaFuzzTypesSuite'   -> passes
    grep -c 'owed:352' sql/varka/fuzz/types.tsv                            -> 0
    grep -c 'owed:347' sql/varka/fuzz/types.tsv                            -> 1 (DECIMAL's line)
    build/sbt 'sql/testOnly *VarkaSparkFuzzSuite'                          -> every test passes
    build/sbt -batch "project sql" 'set Test/javaOptions ++= Seq("-Dvarka.sparkfuzz.iterations=700", "-Dspark.test.timeout=170", "-Dvarka.test.watchdog.minutes=170")' 'testOnly *VarkaSparkFuzzSuite -- -z "agree with the row engine (seed"'
        -> passes
    build/sbt 'sql/testOnly *VarkaSparkReproducerSuite'                    -> every test passes
    dev/varka_regen_generated.sh --all                                     -> exits 0

The 700-composition line sets the budget through `Test/javaOptions` (fix round of 28
November 2026, found beside review 1's H2): sbt passes a `-D` given to `build/sbt` to the forked
test JVM only for `spark.*` properties (`project/SparkBuild.scala`), so `build/sbt
-Dvarka.sparkfuzz.iterations=700 ...` would run the default 200 and still pass. The form is
`dev/varka_nightly.sh`'s sparkfuzz step. The test's printed name shows the budget reached the JVM:
the log holds `- random compositions agree with the row engine (seed 20261008, 700 of them)`
(`VarkaSparkFuzzSuite`: the seed's default is `20261008`); `200 of them` is a stop.

**Commit what the regeneration wrote** (fix round of 28 November 2026, review 1, M3). After the
regeneration, `git status --short` lists only this part's own edits and generated files (`python3
dev/varka_generated_merge.py --list` prints every generated path); `git add` all of them and
commit them in the part's commit, never editing a generated file by hand. Then run the
regeneration line once more: it changes nothing, and `git status --short` prints nothing. A
generated file that changes on the second run, or a path in `git status --short` that is neither
this part's edit nor generated, is a stop: report it.

The 700-composition line is 10.3's run at the default seed, which draws each of the
table's rows at least once; its `-z` text selects the main test, "random compositions agree with
the row engine (seed $seed, $iterations of them)", and not the warmed arm's. On the branch it
found row 619 only, now fixed on master.

**The stop rule.** A composition that disagrees, Varka against the row engine, is a finding:
stop, keep the reproducer the suite writes under `target/varka-sparkfuzz/`, commit nothing more,
and report it to the lead, who files a row (row 619 was such a finding). A raise both engines
share whose condition is not listed is not a finding: add its line to `expected_errors.tsv` in
the file's form, `both`, the modes it was seen in, the family of the row that raised, and a
reason naming the expression, as the `BINARY_ARITHMETIC_OVERFLOW` line does. That includes a
`CAST_OVERFLOW` of an integral-interval cast that both engines raise, if row 332's part 332c has
merged before this part (its rows over `y1`, `s1`, `l`, `ym` and `dt` enter the draws here; fix
round, review 1, M9): family `Integral casts`, reason "an integral-interval cast whose value
does not fit the target raises under ANSI and LEGACY on both engines (VARKA-332 part 332c)".

**Step 4, the record.** Append to section 10 of this file the branch's 10.3:

    git show c76beb07a6a:sql/varka/plans/m8/VARKA-352.md | sed -n '/^### 10.3 /,$p'

and one dated line: "*Landed on 28 November 2026 or later by cherry-pick onto master, without row
619's commits, which merged as #1676; `leftOut` kept and narrowed to the decimal columns
(`VARKA-352.md` 11.4).*"

**The gate.** `dev/varka_ops/AGENTS.md`, "Opening or fixing a pull request", steps 1 to 5; the
lead opens the pull request and dispatches the option matrix on it.

**Done when** step 3 prints what it says, the gate passes and the matrix is green. **Not here:**
the decimal columns (352d), the composition fuzzer (352c).

### 11.5 Part 352c: the composition fuzzer's compile-only tests

**Title:** `[VARKA-352] Draw every coverage row in the composition fuzzer's compile-only tests`.
**Branch:** `varka-352c-r2`, from master (`varka-352c-composition` exists). **Builder:**
Sonnet-class.

**B0.**

    git log --oneline -1 37640940afb   -> 37640940afb [VARKA-352] Draw every coverage row in the composition fuzzer's compile-only tests
    grep -c 'def compositionFilters\|val compositionFilters' CS/VarkaCoverageRows.scala   -> 1

**Step 1.** `git cherry-pick 37640940afb 118c4456a4e e1337f6450d c3e7b091bed 5af95aa65e3`.
Conflicts: `PLAN.md` and `VARKA-352.md`, master's side and nothing applied; any other file, 11.4
step 2's rule.

**Step 2, commands.**

    build/sbt catalyst/Test/compile                                        -> [success]
    build/sbt 'catalyst/testOnly *VarkaCoverageCompositionFuzzSuite'       -> every test passes

*Rewritten in the fix round of 28 November 2026 (review 1, M6).* Its accounting line names the
table's row count first: on `dbaa94ff498` that is `868`, which `python3 -c "import json;
print(len(json.load(open('sql/varka/coverage.json'))['expressions']))"` prints (the branch's run
read 765, 103 rows ago: the decimal casts and pair outputs of rows 350 and 359 to 363, drawn by
these tests for the first time). The criterion: the first number equals that command's output on
the part's worktree, and `0 declined alone`. A decline of a narrow value under a bigint or
floating kernel is accepted by the suite (10.4's "narrow form of the lane rule") and is not
counted there. A different `declined alone` count, or a failure of either compile-only test, is
the stop rule of 11.4: keep the failure text, commit nothing more, report it to the lead, who
files a row.

    dev/varka_regen_generated.sh --all                                     -> exits 0

**Commit what the regeneration wrote** (fix round of 28 November 2026, review 1, M3). After the
regeneration, `git status --short` lists only this part's own edits and generated files (`python3
dev/varka_generated_merge.py --list` prints every generated path); `git add` all of them and commit
them in the part's commit, never editing a generated file by hand. Then run the regeneration line
once more: it changes nothing, and `git status --short` prints nothing. A generated file that
changes on the second run, or a path in `git status --short` that is neither this part's edit nor
generated, is a stop: report it. (This part changes test code only, so the regeneration is expected
to change nothing.)

**Step 3, the record.** Append the branch's 10.4:
`git show 5af95aa65e3:sql/varka/plans/m8/VARKA-352.md | sed -n '/^### 10.4 /,$p'`.

**Done when** step 2 passes and the gate passes. **Not here:** the kernel tests' filters (11.3
decision 3).

### 11.6 Part 352d: the decimal columns at the Spark level

**Title:** `[VARKA-352] Draw DECIMAL columns in the Spark-level fuzzer`.
**Branch:** `varka-352d-spark-decimal`, from master after 352b merged. **Builder:** Sonnet-class.

**B0.**

    grep -c 'def leftOut(executable: String): Boolean = VarkaCoverageRows.readsDecimal(executable)' FZ  -> 1 (352b merged)
    grep -c 'wanted.size === 24' FS                                        -> 1
    git ls-files | grep -c 'VarkaDecimalIntArm.java'                       -> 1 (row 347's part 347d merged)
    grep -c 'def fixtureView(values: Seq\[String\], where: String = "")' DS   -> 1

**Step 1, one text of the decimal columns.** In `DS`, in `private object
VarkaCoverageDifferentialSuite`, move the two local functions `decimal` and `pair` out of
`fixtureView` to the object, unchanged but for their names and visibility, and add the list of
the eleven columns:

    /**
     * A DECIMAL(p, s) column (VARKA-347, VARKA-348) from a bigint: its last p digits over 10^s,
     * an exact product that always fits the type, null where the bigint is, and of both signs.
     */
    def decimalColumn(long: String, precision: Int, scale: Int = 2): String =
      s"CAST(CAST($long % ${BigInt(10).pow(precision)} AS DECIMAL(${precision + 1}, 0)) * " +
        s"${BigDecimal(1).bigDecimal.movePointLeft(scale).toPlainString} " +
        s"AS DECIMAL($precision, $scale))"

    /**
     * A DECIMAL(p, s) of the 128-bit pair (VARKA-359): the unscaled value is `hi * 10^k` plus the
     * last k digits of `lo`, over 10^s, so both words carry bits and the sign of `hi` leads; `k`
     * keeps it within `digits`, and `digits + s + 1 <= 38` keeps the product with 10^-s exact at
     * its own type. Null where `hi` is.
     */
    def pairColumn(hi: String, lo: String, k: Int, digits: Int, precision: Int,
        scale: Int): String = {
      val p = BigInt(10).pow(k)
      s"CAST(CAST(CAST($hi AS DECIMAL(19, 0)) * $p + coalesce($lo, 7) % $p " +
        s"AS DECIMAL($digits, 0)) * " +
        s"${BigDecimal(1).bigDecimal.movePointLeft(scale).toPlainString} " +
        s"AS DECIMAL($precision, $scale))"
    }

    /**
     * The table's eleven DECIMAL columns, each `<expression> AS <name>`, over `l`, `l2` and the
     * unscaled value `d9s4` is built from, which each fixture gives: the differential suite's
     * reads its row id, the Spark-level fuzzer's cannot (VARKA-352 part 352d).
     */
    def decimalColumns(d9s4Unscaled: String): Seq[String] = Seq(
      s"${decimalColumn("l", 9)} AS d9",
      s"${decimalColumn("l2", 9)} AS d9b",
      s"${decimalColumn("l", 18)} AS d18",
      s"${decimalColumn("l2", 18)} AS d18b",
      s"${decimalColumn(d9s4Unscaled, 9, 4)} AS d9s4",
      s"${decimalColumn("l2", 16, 9)} AS d16s9",
      s"${decimalColumn("l2", 8)} AS d8",
      "CAST(CAST(CAST(l AS DECIMAL(20, 0)) + l DIV 13 AS DECIMAL(19, 0)) * 0.01 " +
        "AS DECIMAL(19, 2)) AS d19",
      s"${pairColumn("l", "l2", 16, 35, 38, 2)} AS d38",
      s"${pairColumn("l2", "l", 16, 35, 38, 2)} AS d38b",
      s"${pairColumn("l", "l2", 8, 27, 38, 10)} AS d3810")

In `fixtureView`, the eleven lines from `${decimal("l", 9)} AS d9,` to
`${pair("l", "l2", 8, 27, 38, 10)} AS d3810` become one interpolation,
`${decimalColumns(d9s4Unscaled).mkString(",\n       ")}`. The view's SQL then differs from
today's in its whitespace only.

**Step 2, the Spark fixture.** In `FZ`, `fixtureRows`, after the `$floating` line of the `SELECT`
list, add a comma and `${VarkaCoverageDifferentialSuite.decimalColumns(D9s4Unscaled)
.mkString(",\n       ")}`, with, beside `NarrowSalt`:

    /**
     * The unscaled value of `d9s4` in the Spark-level fixture: seven digits of a bigint times 100,
     * within nine digits, null where `i` is, as the differential suite's is; it has no row id to
     * vary the factor by (VARKA-352 part 352d).
     */
    private val D9s4Unscaled = "CASE WHEN i IS NOT NULL THEN coalesce(l, l2) % 10000000 * 100 END"

The fixture's doc comment gains a sentence: "The eleven DECIMAL columns are derived from `l` and
`l2` by the differential suite's `decimalColumns`, so a decimal cell moves with the bigint it is
built from and takes no draw of its own."

**Step 3, no filter left.** In `FZ`, delete `leftOut` and its doc comment, and make
`coverageRows` read every row (drop its `.filterNot(...)`); the doc comment of `coverageRows`
becomes "The coverage table's rows: every column the table names is one of the fixture's." In
`FS`, the test "the fixture holds every column of coverage.json its draws read, at its type"
drops `.filterNot(a => leftOut(a.name))`, its count becomes `35`, and its comment becomes "Every
column of the table is one of the fixture's, at its type." Check:

    grep -c 'leftOut' FZ FS                                                -> 0 in both

**Step 4, the descriptor.** In `FT`, after `private static final ValueSource SHORT_VALUES =
integral(LaneType.SHORT);` and before `BOOLEAN_VALUES`:

    /**
     * The long lane's edges of a DECIMAL(18, s): the largest unscaled values of precision 18 and
     * of precision 9, and -1, 0 and 1. A column of a lower precision clamps them to its bound. A
     * lane-pair decimal's values are two words, which the IR's pair draw makes, not this source.
     */
    private static final ValueSource DECIMAL_VALUES = integral(-999999999999999999L,
        -999999999L, -1, 0, 1, 999999999L, 999999999999999999L);

and the `DECIMAL` entry becomes

    case DECIMAL -> entry(id, new Present<>(DECIMAL_LEAF),
        columns(derived("d9", "DECIMAL(9,2)"), derived("d9b", "DECIMAL(9,2)"),
            derived("d18", "DECIMAL(18,2)"), derived("d18b", "DECIMAL(18,2)"),
            derived("d9s4", "DECIMAL(9,4)"), derived("d16s9", "DECIMAL(16,9)"),
            derived("d8", "DECIMAL(8,2)"), derived("d19", "DECIMAL(19,2)"),
            derived("d38", "DECIMAL(38,2)"), derived("d38b", "DECIMAL(38,2)"),
            derived("d3810", "DECIMAL(38,10)")),
        new Present<>(DECIMAL_VALUES));

replacing `case DECIMAL -> entry(id, new Present<>(DECIMAL_LEAF), new Owed<>("347"), new
Owed<>("347"));`, which names row 347 (row 347's plan no longer changes it, review 1, M1).
The SQL type strings are `DataType.sql` of each, which `FS`'s test "each column the fuzz descriptor
puts in the fixture is one of the view's, of its type" compares: `DecimalType(9, 2).sql` is
`DECIMAL(9,2)`, with no space.

**Step 5, commands.**

    build/sbt catalyst/Test/compile sql/Test/compile                       -> [success]
    VARKA_FUZZ_TYPES_REGEN=true build/sbt 'catalyst/testOnly *VarkaFuzzTypesSuite'   -> passes
    grep -c 'owed:' sql/varka/fuzz/types.tsv                               -> 0
    build/sbt 'sql/testOnly *VarkaCoverageDifferentialSuite *VarkaCoverageHarnessSuite'
        -> every suite passes (the differential view is the same SQL)
    build/sbt 'sql/testOnly *VarkaSparkFuzzSuite'                          -> every test passes
    build/sbt -batch "project sql" 'set Test/javaOptions ++= Seq("-Dvarka.sparkfuzz.iterations=700", "-Dspark.test.timeout=170", "-Dvarka.test.watchdog.minutes=170")' 'testOnly *VarkaSparkFuzzSuite -- -z "agree with the row engine (seed"'
        -> passes, after the expected-errors lines below, and the log names `700 of them`
    dev/varka_regen_generated.sh --all                                     -> exits 0

The 700 line's form is 11.4 step 3's, for the same reason. **Commit what the regeneration wrote**
(fix round of 28 November 2026, review 1, M3). After the regeneration, `git status --short` lists
only this part's own edits and generated files (`python3 dev/varka_generated_merge.py --list` prints
every generated path); `git add` all of them and commit them in the part's commit, never editing a
generated file by hand. Then run the regeneration line once more: it changes nothing, and `git
status --short` prints nothing. A generated file that changes on the second run, or a path in `git
status --short` that is neither this part's edit nor generated, is a stop: report it.

**Expected errors.** Under ANSI the two narrowing decimal casts of 11.3 decision 6 raise where a
value does not fit, on both engines. When the 700-composition run stops on an unlisted condition
raised by `both`, add one line per condition to `sql/varka/fuzz/expected_errors.tsv`, in the
file's form, with the condition exactly as the failure prints it, `both`, `ANSI`, the family
`Decimal casts`, and the reason: "A decimal cast to a narrower decimal
(`CAST(d18 AS DECIMAL(12, 2))`, `CAST(d9 AS DECIMAL(9, 4))`) raises under ANSI where the value
has more integer digits than the target, on both engines; the fixture's derived decimal columns
hold such values in every pool, the safe one included (VARKA-352 part 352d)". The stop rule of
11.4 step 3 holds for everything else.

**Step 6, the record.** Section 10 gains 10.5, "The decimal columns at the Spark level": the
eleven columns, the moved builder, the value source, the expected-error lines added, the run's
count of compositions and rows drawn.

**Done when** step 5 prints what it says, the gate passes, the option matrix is green, and
`types.tsv` has no owed cell. **Not here:** the catalyst grammar's decimal types (2.5's "decimal
operand types from Spark's rules"), the composition fuzzer's kernel tests (11.3 decision 3).

### 11.7 Part 352z: the row's close

**Title:** `[VARKA-352] Plan: close the fuzzers' type row and move its later steps`.
**Branch:** `varka-352z-close`. **Builder:** the lead, or Sonnet-class. Plan-only.

1. `sql/varka/plans/m10/SCOPE.md` item 140 gains these eight lines (fix round of 28 November
   2026, review 1, L4), inserted in its table in task order (after the last line whose task is
   below 352, before the first above it, after row 332's lines if they are there), each exactly:

       | 352 | The TLP oracle's raise rule under ANSI and its second pool of values | `m8/VARKA-352.md` 2.4 | no item of `m8/PLAN.md` 1.1 names it (11.3 decision 2) |
       | 352 | The fuzzers' cross-mode properties: one tree under LEGACY, ANSI and TRY, its answers held to each other | `m8/VARKA-352.md` 2.6 | no item of `m8/PLAN.md` 1.1 names it (11.3 decision 2) |
       | 352 | The IR fuzzer's cast productions between every pair of types | `m8/VARKA-352.md` 2.5 | no item of `m8/PLAN.md` 1.1 names it (11.3 decision 2) |
       | 352 | The generators' sweeps: specials beyond the value sources, the decimal shapes sweep, the tie windows | `m8/VARKA-352.md` 2.5 | no item of `m8/PLAN.md` 1.1 names it (11.3 decision 2) |
       | 352 | The Spark-level fuzzer's run in CI's nightly | `m8/VARKA-352.md` 2.7 | no item of `m8/PLAN.md` 1.1 names it (11.3 decision 2) |
       | 352 | Refits A and B of the committed IR sequences | `m8/VARKA-352.md` 2.3 | rows 431 and 432, which refit A was to take, moved to milestone 9 on 23 November 2026 (11.3 decision 2) |
       | 352 | The catalyst grammar's decimal operand types, from Spark's rules | `m8/VARKA-352.md` 2.5 | no item of `m8/PLAN.md` 1.1 names it (11.3 decision 2) |
       | 352 | The composition fuzzer's kernel tests over narrow, boolean and decimal inputs | `m8/VARKA-352.md` 10.4, step 2d | a third fuzzer, which item 1 of `m8/PLAN.md` 1.1 does not name (11.3 decision 3) |

   and the item's opening paragraph gains, at its end, the sentence "Row 352's eight lines are
   parts of a row that closed in milestone 8 (`m8/VARKA-352.md` 11.7)." If question 1 of section
   12 is answered (b), the first two lines are left out; if (c), none is added.
2. `CS/VarkaCoverageRows.scala`'s three `Filter`s name `Some("VARKA-352")` as the row that owes
   their rows; the accounting test of `VarkaCoverageCompositionFuzzSuite` fails on `None`. They
   stay as they are; 10.6 says the work moved.
3. Section 10 gains 10.6, "The close": the descriptor with no owed cell (`grep -c 'owed:'
   sql/varka/fuzz/types.tsv` prints `0`), what moved and where.
4. `PLAN.md`: row 352 opens with `**Done**`, naming what moved to `m10/SCOPE.md` item 140.

**Done when** `python3 dev/varka_plan_lint.py --offline` prints `0 finding(s)` and the lead
merges it green.

### What only a JVM can settle, and the command for each

| claim | command | settles |
|---|---|---|
| 352b's picks meet only the conflicts 11.4 step 2 lists | `git cherry-pick ...` (11.4 step 1), then `git diff --name-only --diff-filter=U` | the files resolved |
| no composition of 700 disagrees with the boolean and narrow columns drawn on master | 11.4 step 3's 700-composition command | a pass, or a finding row |
| the derived decimal columns analyze at the types the descriptor names | 11.6 step 5's `VarkaSparkFuzzSuite` run | the site test passes |
| no composition of 700 disagrees with the decimal columns drawn | 11.6 step 5's 700 run | a pass, or a finding row |

### Risks of section 11

1. **352b's picks meet master's `leftOut`.** Held by 11.4 step 2's rule and by the column-count
   test, whose count (24) fails if the filter is wrong either way.
2. **A decimal composition finds a real disagreement.** That is the fuzzer's purpose: held by the
   stop rule, which turns it into a finding row, not a skipped seed.
3. **The safe pool raises under ANSI on two decimal rows.** Held by the expected-errors line, which
   names them; the partition check, which takes only the safe pool, then reads their raise as
   expected.

## 12. Questions for the owner (28 November 2026)

*Rewritten in the fix round of 28 November 2026 (review 1, L6) to stand alone.*

1. **Which unbuilt fuzzer work leaves row 352 for milestone 10?** Row 352 makes the fuzzers draw
   every new type of milestone 8. What gates the milestone's close is only that each type's
   entry in `sql/varka/fuzz/types.tsv` (the table of what each fuzzer draws of each type) has no
   "owed" cell, which parts 352b and 352d achieve. Beside that, the row's plan names further
   work no item of the close names: a rule for when a query raises under ANSI in the
   ternary-logic (TLP) oracle and a second value pool for it; properties that hold one query
   across LEGACY, ANSI and TRY; cast productions in the IR fuzzer; wider generator sweeps; the
   catalyst grammar's decimal types; the Spark-level fuzzer in the nightly CI run; two refits of
   the committed random sequences; and the composition fuzzer's kernel tests over narrow,
   boolean and decimal inputs. Options: (a) move all of that to milestone 10's catalogue
   (`m10/SCOPE.md` item 140) and mark the row Done when the table has no owed cell; (b) keep the
   TLP raise rule and the cross-mode properties in milestone 8, a part each, and move the rest;
   (c) keep all of it in milestone 8. *Recommended: (a)*: no clause of the close
   (`m8/PLAN.md` 1.1) names any of it, rows 431 and 432, which the first refit was to serve,
   already moved to milestone 9, and (b) adds two parts the size of 352b to a close that has 33
   gates. **Default applied: (a)** (11.3 decisions 2 and 3; part 352z writes the lines).

## 13. The house rules that bind every part of section 11

Each is written in the file named; read it there.

- A committed IR sequence is frozen between refits, and a new value kind takes a stream of its
  own: section 2.2 of this plan, rules 1 and 5. 352b's narrow cells take `NarrowSalt`'s stream;
  352d's decimal cells take no draw at all.
- Errors compare by condition and SQLSTATE, never by message text: the row's note in `PLAN.md`
  (*Added 1 November 2026*).
- A finding becomes a row; a seed is never skipped to make a run pass: `sql/varka/AGENTS.md`,
  "Open work is recorded, never left as a marker".
- The option matrix must pass under every configuration: the paragraph of
  `sql/varka/skills/testing-and-debugging.md` that opens "A test that must hold under every
  configuration".
- A pull request under 1000 counted lines, reviewed by its author before it opens:
  `sql/varka/AGENTS.md`, "Pull requests"; branches, titles, who merges:
  `sql/varka/.agents/pull-requests.md`; the gate: `dev/varka_ops/AGENTS.md`.
- What not to do: do not edit `types.tsv` by hand (`VarkaFuzzTypesSuite` writes it); do not
  pick row 619's four commits; do not change a committed IR sequence's draws.

## 14. Stale against the tree, found while planning (28 November 2026)

1. **The row's note in `PLAN.md`**: "Left: step 2's Spark-level half, steps 3 to 5 and the close"
   is stale. The Spark-level half and step 2c are built on branches (11.1), and steps 3 to 5 move
   by question 1's default.
2. **Section 0 item 7**: "No decimal column exists anywhere" is history; the coverage table has
   eleven since rows 347 to 359, and the differential suite's fixture builds them.
3. **Section 2.4's `DECIMAL` TLP shapes** name columns the table does not have (`d17_7`,
   `d18_9`, `d38_10`); they move with the TLP oracle (11.3 decision 2).
4. **10.1's "`Owed` to 347 for `DECIMAL`"** is the state 352d ends.
5. **The branch's 10.3** says the main test fails on row 619 until it lands; #1676 landed it.
6. **The 700-composition commands** (found in the fix round of 28 November 2026): the earlier text
   gave `build/sbt -Dvarka.sparkfuzz.iterations=700 ...`, which sbt does not pass to the forked
   test JVM (only `spark.*` properties, `project/SparkBuild.scala`); it would run 200 and pass.
   11.4 step 3 and 11.6 step 5 now use `Test/javaOptions`.
7. **The composition fuzzer's row count** (review 1, M6): 765 on the branch, 868 on
   `dbaa94ff498`.
