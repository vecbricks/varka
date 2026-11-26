# VARKA-475: the compiler's type architecture

## 0. Why this plan exists

Milestone 8 is "every numeric and temporal type", and the owner's direction is broader still: Varka
will support Spark's data types, read against the SQL standard's categories. Four of the seven
milestone types have not started, and the two that have show what one costs and where the
knowledge lives.

The project already keeps the two registers this plan needs: `m8/census.tsv`, which names each
in-scope expression and the thirteen types it admits, and `m8/casts.tsv`, whose 106 pairs each
carry the LEGACY, ANSI and TRY answer, the kind of lowering, the semantics and the proof
obligation. Both are documentation and coverage checks. Neither is read by the compiler: the cast
lowerings are `case Cast c when ...` arms spread across the family compilers, and a type is
admitted by the if-chain in `VarkaNodeCompiler.laneOf`.

The target space, by the standard's categories:

| SQL standard category | Spark types | Varka today | Milestone |
| :--- | :--- | :--- | :--- |
| Exact numeric | BYTE, SHORT, INT, LONG, DECIMAL(p,s) | two lanes; narrow and decimal in flight | m8 |
| Approximate numeric | FLOAT, DOUBLE | none | m8 |
| Boolean | BOOLEAN | conditions; values through `CondValue` (row 460), no compiler arm yet | m8 |
| Datetime | DATE, TIME, TIMESTAMP, TIMESTAMP_NTZ | DATE, TIME | NTZ in m8; LTZ out by decision |
| Interval | YEAR-MONTH, DAY-TIME | both | done |
| Character, binary | STRING, CHAR, VARCHAR, BINARY | none | out of m8; m9 item 3 |
| Row and collections | STRUCT, ARRAY, MAP | none | no representation yet |
| User-defined, JSON, spatial | UDT, VARIANT, geospatial | none | later |

Five pieces follow, in the order this plan schedules them. Each can take its own task row when
it starts; this plan is the design they share. Row 474 (`VARKA-474.md`, `ADDING_A_TYPE.md`) is the
sibling: it lists the sites a type touches and makes the mechanical ones a registration point;
this plan is about the architecture those sites sit in.

## 1. What the design gets right, and what must not move

The IR is sealed and typed, and the node-kind switches are exhaustive, so a new node is a compile
error at every consumer. A lane is a physical width, `Lane` is a per-lane facts table, and the
evaluator's `isArrowBacked` and `allocateVector` are the only Arrow boundary. The shape cache
amortises compilation, and three contracts hold the engine honest: the bytes oracle
(`emitted_bytes.json`), the fuzzers against the reference evaluator, and the option matrix.

Every piece below is additive to this design. None of them may change a byte of an existing
shape, except where a piece says so, and the checks for that are section 5's.

## 2. What the narrow types taught (revision of 23 October 2026)

The first version of this plan (22 October) was written before the `TINYINT`/`SMALLINT` lanes and
their arithmetic merged (#987, #989) and before the two `/code-review max` reviews of them
(`VARKA-329.md` 9.8). Their findings change the order and add one piece:

- **Most defects were in the tests, not the emitter.** No fixture row had a narrow pair null on
  the left only, the "no nulls" fixture held narrow NULLs, and two equality rows matched no row of
  any fixture, so a kernel answering a constant false would have passed them (989-2, 987-7, and
  the review of bucket 2). Each type writes its fixtures by hand, and nothing says what a type's
  fixtures must hold. The conformance contract (3.1) moves first.
- **The lane is decided in four places that must agree.** The compiler's admission
  (`narrowRefusal` and the lane check), the budget mirror (`fitsBudgets`), the emitter (`laneOf`,
  `Analysis`) and `CompiledVarkaProjection` each worked out a kernel's lane, and several read it
  from the first root alone. The disagreements were findings: an output's width with two unchecked
  sources (987-11), the checked narrow multiply's lane decided per root so the native arm split a
  projection (989-9), a refusal reason landing on the wrong entry. The review fixes that move
  emitted bytes (bucket 3 of 9.8) decide the lane once per kernel in one function; a
  kernel plan (3.3) finishes the job before `TIMESTAMP_NTZ`, whose semantics cross a lane
  boundary.
- **The registers already drift.** The census's fused-cast list missed byte and short to int
  until the pre-commit hook caught it; nothing checks `casts.tsv` against the compiler's arms.
  The register checks (3.2) are cheap and come early.
- **A new IR node costs about twenty files.** A node reaches `Analysis` in seven places, `Slots`,
  the cost table and its fit, three fuzzer grammars, the canonical parser, the census and the
  reference. A type multiplies that cost unless it adds operators, not nodes (section 4); the
  review fixes make narrow `abs` a flag on the negation node for that reason.
- **The warm-up is written by hand per type and per species.** The widened species went unwarmed
  until a review found it (989-11), and each new narrow operator is added to every warm-up
  block by hand, the reach gate finding the ones forgotten. The registry (3.4) should name each
  type's species, widened ones included, so the reach model reads it instead of restating it.

## 3. The five pieces

### 3.1 A conformance contract per type (first)

A `VarkaTypeConformance` base, in Java, parameterized by the type, with one check each for: the
load, the round trip, null handling, SQL ordering (NaN and both zeros for the floating types),
choice, the type's row of the cast matrix, and overflow in LEGACY, ANSI and TRY. The oracle is
the reference evaluator for the kernel checks and the row engine for the end-to-end ones.

Its fixtures are generated from the type's values, not written per suite: the type's minimum,
maximum, zero, -1 and the values next to them, NaN and both zeros for the floats, every null
pattern of a pair (left only, right only, both, neither), a pair of equal values for every
equality, and a batch with no null at all for the dense body. A fixture that cannot make a row of
the type's coverage true is a finding of the contract, not of a reviewer.

A type cannot land without it, exactly as an expression cannot land without its coverage row
today. A new type's definition of done becomes "the conformance suite passes", and the census
reports by the standard category, which makes the remaining work finite and standard-defined.

**Done when** the base exists with the seven checks and the generated fixtures; `TINYINT`,
`SMALLINT` and `BOOLEAN` pass it (the types already landed, so the contract is proven on them
before a new type depends on it); and the census check fails for a type with no conformance
suite.

Cost: three to five days. Tests only; no emitted byte moves.

### 3.2 Executable registers: `casts.tsv` and `census.tsv`

The cast matrix is the larger half of the breadth goal (106 pairs) and the register already holds
each pair's answer. Make it executable: a `dev/varka_casts.py --check` that compares the
hand-coded arms with `casts.tsv` and fails on a pair that is claimed and not emitted, or emitted
and not claimed. The same treatment for the census's `types` column: a type the census claims an
expression admits and the compiler declines, or the reverse, is a finding.

Generation - a `VarkaCastTable` built from `casts.tsv` that the family compilers consult, so a new
type adds rows rather than arms - waits until the arms outgrow review (6, question 2).

**Done when** both checks run in CI and in the pre-commit hook, and the 106 pairs and the census's
types agree with the compiler.

Cost: about three days for casts, one for the census. Tooling only; no emitted byte moves.

### 3.3 A kernel plan: the lane decided once per kernel

A `VarkaKernelPlan`, a record built once per kernel from its roots and the emit options, holding
what every consumer now works out for itself:

- the drive lane (one function after the review fixes, a reading of the first root before them);
- each input's load: the species, the part count and whether it widens;
- each node's emitted lane, which differs from its own lane under the widened arm;
- the reduction sites (`Analysis.wrapAt` today);
- the refusals, each with the reason and the entry it belongs to.

The compiler admits an entry by asking whether the plan of the kernel with the entry exists and
is within budget; the budget mirror prices the plan; the emitter executes it; the compiled
projection reads its drive lane and output widths from it; and the warm-up reach model lists the
plan's species. `Analysis` keeps what is about emission - slots, words, the guards - and stops
deriving the lane.

**Done when** the compiler, `fitsBudgets`, the emitter and `CompiledVarkaProjection` read one plan;
no consumer derives a lane from a single root; a refusal reason is produced only by the plan; and
`emitted_bytes.json`, `coverage.json` and `width_audit.json` are byte-identical.

Cost: three to five days. It lands before `TIMESTAMP_NTZ` (row 338): a long decomposed into the
int32 calendar prefix, with casts to and from `DATE`, is the first type whose lanes the four
consumers would otherwise have to agree on by hand.

### 3.4 A type registry: `VarkaTypes`

*Built as row 494 (`VARKA-494.md` 10, 26 October 2026).*

A closed `VarkaTypeId` enum - BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, DECIMAL, DATE, TIME,
TIMESTAMP_NTZ, YM_INTERVAL, DT_INTERVAL - with `of(DataType)` in one function, replacing `laneOf`
and its siblings. Each id carries a small descriptor record, and only what has a reader today:

- the admission answer, whose decline names the type, so "declined" stops being
  indistinguishable from "not implemented";
- the census name, so the census stops keeping a second list;
- the species a kernel of the type can reach, widened ones included, which the warm-up reach
  model and 3.1's fixtures read.

Ordering semantics, literal codecs and cast rules join the descriptor when a type needs them -
the floats bring ordering - not before. The registry is compile-time data, read once per shape;
nothing on the batch path calls it.

**Done when** `laneOf` and its siblings are one function; the census's type names and the reach
model's species come from the registry; a decline for a type the compiler does not support names
that type; and the three generated files are unchanged.

Cost: two to three days, after the kernel plan, whose species it then supplies.

### 3.5 Value shapes, with decimal

`LaneType` is a width, but a value is not always one lane-width. `DECIMAL(19..38)` is a pair of
long lanes (m8 2.8). The proposal: a `ValueShape` - `ONE(lane)` and `PAIR(lane)` - declared by
every IR node for its result, and every shape change an IR node of its own, as `WidenLane` and
`NarrowLane` already are; the `Interleave`/`Deinterleave` a decimal pair needs would join them.
The emitter then dispatches on shape and never guesses from the logical type.

`BITS`, `VARLEN` and `COMPOSITE` are added with the row that first builds one. The shape switches
are exhaustive, so adding a member later is a compile error at every site that must handle it -
the cheapest refactor there is - while declaring it now would make every new switch carry an arm
for a shape nothing builds. The boolean condition algebra already works on bit words without a
shape.

**Done when** decimal 128 is expressed as `PAIR` with its conversions as IR nodes; a shape change
never happens inside an emitter arm; and the emitted bytes of every existing shape are unchanged.

Cost: five to ten days, and it belongs to the row that forces it, `DECIMAL` (rows 347, 359), not
to a refactor milestone.

## 4. Rules for adding a type

These are not pieces with a done-when; they are how the pieces above are used, and what a
reviewer checks a new type's diff against.

1. **Operators, not nodes.** A new type reuses the lane-generic nodes and adds what differs as an
   operator of an existing node (`IntOp`, an `abs` flag on `IntNeg`), or a parameter (overflow
   mode, rounding). A new node is for a new kind of computation, not a new type.
2. **Lane facts, not lane branches.** What differs between lanes - species, conversions, range,
   saturating operators - is a field of `Lane`, read by the emitter, not an `isNarrow()` branch in
   it. There are about thirty such branches after the narrow types; the floats would add a second
   axis unless `Lane` absorbs it, which is why the `Lane` split into integral and floating halves
   is scheduled with them (section 5's table).
3. **Representations are option arms.** A type with more than one representation (widened and
   native narrow, decimal 64 and 128) keeps the losing one as an emit option run in the matrix,
   as `narrowLanes` is.
4. **The warm-up follows the registry.** A type's warm-up blocks call what its species reach,
   and the reach gate checks them; once the registry names the species, generating the blocks
   from the registry and the operator table is the next step (6, question 3).

## 5. Sequencing and dependencies

| When | Piece | Forced by |
| :--- | :--- | :--- |
| now | 3.1 conformance, 3.2 register checks | the defects of rows 329 and 330 (section 2) |
| before `TIMESTAMP_NTZ` | 3.3 kernel plan, then 3.4 registry, and row 474's `LaneArrow` table | `TIMESTAMP_NTZ` (338) |
| with floats | the `Lane` split into integral and floating halves, the ordering fields of 3.4 | `DOUBLE`, `FLOAT` (341) |
| with decimal | 3.5 shapes, `casts.tsv` generation if the arms outgrow review | `DECIMAL` (347, 359) |
| continuous | 3.1 for every new type, section 4's rules in every review | every type |

3.1 and 3.2 add tests and tooling only and depend on nothing. 3.3 touches `VarkaExpressionCompiler`,
`VarkaLoopEmitter`, `Analysis` and `CompiledVarkaProjection`; 3.4 touches `VarkaNodeCompiler` and
the family compilers and is cheaper after 3.3; 3.5 touches `VarkaVectorIR` and the emitters.

## 6. Verification

- The bytes oracle: `emitted_bytes.json`, `coverage.json` and `width_audit.json` are byte-identical
  across 3.1 to 3.4 for every existing shape, and move only where a shape is new in 3.5.
- The existing suites: the emitter suites of every type, the differential suites and both fuzzers
  pass unchanged after each piece.
- The matrix: every piece that adds an emitting test runs under all configurations
  (`dev/varka_matrix.sh --all`), and the counts stay green with nothing unfused.
- The registers: `dev/varka_casts.py --check` and `dev/varka_census.py --check` are the two
  commands that say whether a pair or a type is wired.
- The contract: every landed type has a conformance suite, and the census says which do not.

## 7. Non-goals and risks

- **No e-graph representation chooser.** `m10/SCOPE.md` item 11 argues for one; the `ValueShape`
  conversions are its seed, and the data to decide it does not exist yet.
- **No type-aware IR beyond semantic parameters.** A node carries the shape plus the parameters it
  needs (ordering, overflow mode, rounding). The logical type stays in the lowering and the
  reference evaluator, or the emitter stops being shape-directed.
- **No virtual calls on the batch path.** The registry and the kernel plan are built at compile
  time, once per shape.
- **Neither the registry nor the kernel plan may become a god object.** Both are records and
  small functions; the plan holds decisions, not emission, and the descriptor holds only fields
  with a reader.
- **The risk is abstraction ahead of the evidence.** Each piece is scheduled against the row that
  forces it. The first two add no production code; the kernel plan replaces four computations
  that already had to agree and did not.

## 8. Open questions

1. **Where the registry and the plan live.** In `varka/` beside `Lane` and `Analysis`, so the
   evaluator and the tests read them without depending on the compiler, is the default; the
   alternative is the catalyst codegen package beside `VarkaNodeCompiler`, which keeps the
   lowering together.
2. **Generated or checked casts.** 3.2 checks the hand-coded arms against `casts.tsv`.
   Generation is cheaper to keep in sync; checking keeps the lowerings reviewable as code. Checking
   first, generation only if the arms outgrow review, most likely with decimal.
3. **Generating the warm-up.** Once 3.4 names each type's species and an operator table names the
   Vector API calls each operator emits, the warm-up blocks can be generated and the reach gate
   becomes a check of the generator. Whether that pays before the floats double the blocks is
   open.

Settled by the revision: `VARLEN` and `COMPOSITE` are not declared ahead of the rows that build
them (3.5).

## 9. What 3.1 to 3.3 taught (revision of 24 October 2026)

Written after the conformance contract (row 484, #1006), the cast register's check (row 486,
#1008) and the kernel plan (row 488, not yet merged). Each row found the same kind of defect: a fact
about a type written down in more than one place, by hand, and the copies disagreeing with each
other or with the compiler while every test passed.

### 9.1 Findings

1. **Every hand-kept register had drifted.** The census claimed no fused operator over `bool`
   while fifteen fuse, since row 331 left the claims out; when 484 added them the census refused
   every one, because `VarkaCoverageSuite` credited `bool` to any boolean operand, so a
   comparison's own result counted and a boolean column did not. Three cast claims were
   wrong, and `m8/PLAN.md` row 332 said `CAST(ymy AS INT)` declines while a coverage row fused it
   (486). The existing checks ran one way: a claimed fusion has a coverage row, never an
   unclaimed one declines.
2. **What fuses is a fact of the form, not the type pair.** `dt -> dt` fuses for 36 of its 90
   form pairs (every target ending in `SECOND`), `ym -> int` for two of three sources. A
   register keyed by type pair cannot say that; a file the compiler writes per form can, and
   486 made `casts.tsv` such a derivation.
3. **The facts of a type live in many lists, in Python, Java and Scala.** The census type names are
   in `dev/varka_census.py` and in `VarkaCoverageSuite`; a type's SQL target is in
   `VarkaTypeConformance`'s `CAST_TARGETS` and its forms in `VarkaCastMatrixSuite`'s `TYPES`; the
   landed types in the census's `CONFORMANCE_SUITE` and `EXISTING`; a type's input arm in
   `VarkaSparkOracle`; its test storage layout in `VarkaKernelChecks` (484 added the bit-stored
   boolean there); its species in `VarkaVectorTemplateWarmup` and the reach model
   `VarkaWarmupReach`. A new type is added to each by hand, and a list it is missing from fails
   nothing until a reviewer notices.
4. **A lane was derived in eight places, four of them tests.** 488 made `VarkaKernelPlan` the
   one function, and found the four test sites reading a kernel's lane from its first root,
   which is what production stopped doing in the narrow lanes' review; read that way,
   `VarkaReplay` would pick the wrong entry point for a native narrow root beside an int one. A
   harness that recomputes what production computes drifts as production code did.
5. **The byte oracles pin acceptance only.** `emitted_bytes.json`, `coverage.json` and
   `width_audit.json` say nothing about what the compiler refuses: a refactor that stopped
   refusing a kernel would move no byte. 488's method held: build the new code beside the old,
   prove the two equivalent over a corpus under every configuration, move the consumers one at
   a time with the oracles run after each, delete the old code and the equivalence test last.
6. **A new IR node reaches about twenty places** (section 2 counted them), most of them exhaustive
   switches asking one small property: whether reduction modulo a narrow width commutes with it
   (`Analysis.isNarrowRing`), whether it is pure, whether it skips a null input, its budget weight,
   its cost kind, its children and its rebuild, its reach in three fuzzer grammars. Exhaustiveness
   is the safety net and stays; spelling the net out in every pass is the cost.

### 9.2 What changes

1. **3.4's registry is the one source of a type's facts, and the tools read it.** Beyond the
   admission answer, the census name and the species, an entry holds what finding 3 lists: the
   Spark type and its forms (field ranges, precisions), the storage layout (width, bit-packed or
   not), the conformance values and the oracle's conversion. A test writes the registry to a
   committed file, as `VarkaCastMatrixSuite` writes `casts_fused.tsv`, and the Python tools read
   that file instead of keeping their lists. 3.4's done-when gains: `CAST_TARGETS`, the cast
   suite's `TYPES`, the census's type lists and the oracle's per-type arms read the registry,
   and the test harness takes load layouts and lanes from the registry and the kernel plan,
   never from code of its own.
2. **Representation apart from semantics, before the floats.** `LaneType` is a storage width;
   the date, time and interval types already take their meaning from their nodes, not their
   lane. The floats bring a value domain with its own ordering (NaN, both zeros) and decimal a
   representation of two longs. The lane-generic nodes - comparison, choice, null tests, `IN`,
   load, store - take the representation and a small per-type semantics table (ordering,
   equality, overflow), so a type adds rows, as section 4's first two rules ask. It lands with
   the `Lane` split of section 5, in row 341's first step.
3. **Node properties as a descriptor, not switches in every pass** (new row 489). Each IR node
   supplies one record of its properties - ring under narrow reduction, pure, skips nulls,
   budget weight, cost kind - and the passes read the property they need. The record is
   required at construction, so a new node still cannot compile without deciding each property,
   and most of finding 6's twenty places become two or three. Before decimal, whose lane
   pair adds nodes (359 to 362).
4. **Every capability register is generated from the compiler.** 486 did the casts; row 487
   does the census, starting from substituting a column of each type into the coverage rows.
   People review the diff of a generated file rather than edit a claim, and a coverage row stays
   the end-to-end proof against Spark.
5. **Refactors of the compiler carry an equivalence test** while old and new code coexist, as
   finding 5 describes, whenever what they change can refuse.

### 9.3 Sequencing, amended

| When | Change |
| :--- | :--- |
| next | 3.4 with 9.2.1's wider done-when; row 487 beside it |
| with floats (341) | 9.2.2, with the `Lane` split |
| before decimal (347, 359) | row 489, 9.2.3 |
| continuous | 9.2.4 and 9.2.5 in every review |

*25 October 2026:* 3.4 with 9.2.1 is row 494 (`VARKA-494.md`), built before `TIMESTAMP_NTZ`
by the owner's decision.
