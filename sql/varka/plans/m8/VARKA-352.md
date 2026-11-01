# VARKA-352: The fuzzers and the reference evaluator across types

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

## 9. Done when

Row 352's text holds, with the corrections above: every type's descriptor has no `Owed` site; the IR
fuzzer draws every type in its sequence and the Spark-level fuzzer every type in its columns at
every nightly, the Spark one in CI; the TLP check runs on both pools with the raise rule and the
milestone's shapes; the cross-mode properties hold over the 98 pairs and the `try_` arithmetic, with
the fused count stated; the generators carry 2.5's specials and sweeps; every existing digest is
unchanged between refits and each refit's moves are listed; rows 431 and 432 are closed by it; and a
cross-type disagreement found is fixed or a named row.

## 10. Outcome
