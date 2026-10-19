# VARKA-28: lane-width conversion

*Written 18 September 2026. Section 2.2 of `m5/PLAN.md` scoped this
task; it was moved there from `m4/PLAN.md` 2.6 on 4 September 2026.*

*Brought up to date on 16 October 2026, against `m8/PLAN.md` (row 28, wave 0) and its review of
the same day (section 10), at `8a50f61a368`. Milestone 8 took the row from the catalogue's item 39
table; its done-when adds "with the mixed-width trees of this milestone's casts admitted". The
sections below keep their September text; each place where the code or the record has moved since
carries a dated correction saying what it corrects. In short: `NarrowLane` already exists as a
root-only node (VARKA-102), so the task adds `WidenLane` and lifts a restriction rather than adding
two nodes; the emitter that held the 85 sites has been split into several classes, with about 90
reads of the emission's lane; the compiler is Java; and the plan's first-commit items are done.*

## 1. What this is for

One kernel, two lane widths. Today an emission carries exactly one
`VarkaLoopEmitter.Lane`, every node must agree with it, and `VarkaVectorIR`
refuses a tree that mixes lanes in eight constructors. That is the right default
and it is why VARKA-85 and VARKA-29 could ship a second lane without disturbing the
first. It also means Varka cannot compile the shapes where the two widths meet,
and three tasks are waiting behind exactly that:

* **104** (`Long` arithmetic, which closes VARKA-30) needs this task's cast.
* **39** (`date - date`) is an int32 input pair and an int64 output - the first
  mixed-width kernel - and closes inside **103**.
* Catalyst's own implicit promotions produce mixed trees unprompted: `i + l`
  over an `int` and a `bigint` column is `Cast(i, LongType) + l` before Varka
  ever sees it, so today it declines on a lane mismatch rather than on anything
  a reader would call unsupported.

## 2. The decision the milestone pre-registered, and why it has to be re-read

Section 2.2 records the loop-shape question as settled: *"VARKA-28 opens already
knowing the winner: narrowest-drive, for the simpler build (one trip count) at
the same throughput."* **That conclusion should not be built from.** The
measurement it rests on is committed and sound; the summary of it in 2.2 is not,
on two points, and the second one inverts the answer.

### 2.1 What the benchmark actually measured

`VarkaMilestone4MeasurementsBenchmark`, results committed at
`sql/varka/engine/benchmarks/VarkaMilestone4MeasurementsBenchmark-jdk25-results.txt`:

| shape | AVX-512 | 128-bit |
|---|---|---|
| `laneWidthNarrowestDrive` | 2844 / 2831 | 2018 / 2014 |
| `laneWidthPartLoop` | 2772 / 2767 | 2051 / 2050 |

Narrowest-drive is 1.02x ahead at AVX-512 and *behind* by 1.02x at 128-bit -
tied, inside the file's own noise band, exactly as 2.2 says.

**The first correction.** 2.2 attributes the tie-break to "part-loop's extra
bookkeeping - two trip counts, two stores per int chunk". Read the source:
`laneWidthPartLoop` strides `i += ILANES` and has **one** trip count, the same as
narrowest-drive. What it really spends is two conversions, two long loads and two
stores per iteration - against narrowest-drive's one of each over half as many
rows. That is the same work per row, which is why the two tie. There is no
simpler-build argument.

### 2.2 The constraint that outranks the throughput

Narrowest-drive is not implementable without a **second int species**. It matches
the int lane count to the long one by loading through `ISPEC_HALF`, and the
benchmark says so in its own state class: this is "the one measurement that
touches a second int species", kept out of the shared `@Setup` so that no other
benchmark's fork would ever see it.

`SKILLS.md`, "Every operator the plans rely on is one instruction; two species in
one JVM is a box per iteration", is what that precaution is for. Two species of
one lane type in a JVM make the shared `IntVector` templates inline
bimorphically, the receiver has to exist as an object for the other branch, and a
heap box survives per loop iteration. Measured, polluted against clean, same
loops and flags: saturating subtract **6.4x** slower, `selectFrom` **12.8x**,
gather **2.7x**. Whether a given shape boxes depends on the shape *and the order
the profiles filled in*, so it is not a cost that can be bounded by inspection.

A forked, one-benchmark-per-JVM measurement cannot see any of this: it is a cost
narrowest-drive would impose on **every other Varka kernel in the same JVM**, not
on itself. The results file reaches the same conclusion in its own reading -
*"the design decision (VARKA-39) is the two-part convertShape from the preferred
species regardless, because a second species poisons every other IntVector loop
in the same JVM"* - and that sentence, not 2.2's summary, is the one to build
from.

**So: two-part `convertShape` from the preferred species.** At equal measured
throughput, it is the shape that cannot poison the process. Section 2.2 of the
milestone should be corrected to say so, and this plan's first commit does that.

*Done, 16 October 2026:* `m5/PLAN.md` 2.2 carries the correction (its lines 299 to 319), and
`dev/varka_canary/I2LProbe.java` is committed. The two-species rule has since been enforced in
the test JVM as well: `VarkaSpeciesGuard` fails a suite that defines a second species of a lane
type in the shared JVM, and `VarkaOwnJvm` forks the suites that need one (VARKA-246,
`skills/vector-api-and-width.md`). VARKA-156's half-species store for a narrowed output
(`narrowHalfSpecies`, a second int species) stays a reference option, off by default, for the
same reason. Row 376's census adds the `I2L` and `L2I` conversions by `convertShape` at each width
the milestone measures, so this task's conversion is known intrinsic on the AVX2 runner and the
Arm runner before it is built, not only on the laptop where `I2LProbe` ran.

## 3. The design

### 3.1 The mechanism, already proven

VARKA-88 step 2 built and shipped exactly this conversion, for a different target
type: an `IntVector` at the preferred species converts into two vectors of half
the lanes with expanding parts `0, 1`, and converts back with contracting parts
`0, -1`, whose results are lane-disjoint and rejoin with a plain `or`.

`dev/varka_canary/I2LProbe.java` (this plan's commit) confirms the int64 target
behaves identically, negatives included - sign extension is `I2L`'s own, and the
round trip is exact:

    int Species[int, 16, S_512_BIT]  |  long Species[long, 8, S_512_BIT]
    I2L part0 -> [7, -1007, 2007, -3007, 4007, -5007, 6007, -7007]
    I2L part1 -> [8007, -9007, ...]
    ROUND TRIP EXACT, JOIN BY OR WORKS

So the arithmetic and the API questions are settled before any emitter work, the
way VARKA-88's were. What is left is structural.

*Prior art, added 19 September 2026.* SLEEF makes the same choice - one int
species per FP species, converted at the boundary - and its per-ISA helpers hold
the sequences this task will emit. On AVX2 widening is `cvtepi32_epi64`;
**narrowing int64 to int32 has no instruction**, and `helperavx2.h`'s
`vcast_vi_vm` is a `shuffle_ps 0x08 / 0x80` pair over the two 128-bit halves
and an `or`; mask narrowing is `permutevar8x32` over the even lanes and mask
widening the inverse index. On AdvSIMD the pair is `vmovl_s32` / `vmovn_s64`
and masks go through `vuzpq_u32` / `vzipq_u32`. The Vector API's
`convertShape` is what emits these, so the value here is knowing what to expect
in the assembly, not what to write.

### 3.2 What drives the loop, and what a long value is in a mixed kernel

The loop trip stays the **int species' lane count** - sixteen rows per iteration
at 512 bits - and in a mixed kernel every long-lane value is a **pair** of
`LongVector`s, the low half and the high half. A long column load becomes two
loads at `i` and `i + LSPEC.length()`; a long store becomes two stores; a long
lanewise op is emitted twice, once per half.

Two consequences to register rather than discover:

* **Slot pressure doubles for long values** in a mixed kernel. The group budget
  and the fused ceiling are counted in nodes today; a mixed kernel's long nodes
  cost two locals each, and `fitsBudgets` has to know that or a shape will pass
  the compiler's mirror and breach the emitter's real limit.
* **The epilogue's masks are per half.** The tail mask must not be built by
  narrowing the int species' sixteen-lane mask; take it from the long species
  directly at each half's own offset - `LSPEC.indexInRange(i, n)` and
  `LSPEC.indexInRange(i + LSPEC.length(), n)` - which needs no mask conversion
  and no new helper. The validity word is the same question: a sixteen-row word
  splits into two eight-bit halves, and the existing per-width
  `validityBitsAt<n>` helpers are chosen by lane count, so a mixed kernel asks
  for two different ones in one body.

*Correction, 19 October 2026: the loop is driven at the long lane, not the int one, and no long
value is a pair.* This section was written before `NarrowLane` shipped, and that node had already
built a mixed kernel the other way: its loop runs at the long species and its int value sits in the
low half of the int species of the same width, stored under a mask of the low lanes. The row
generalizes that instead. A kernel that holds both lanes is driven at the long lane; every int
value fills the low half of its species - an int column is a load under the mask of the first
`lanes` lanes at `(long) i * 4`, an int output a store under the same mask - and a widening and a
narrowing are one `I2L` and one `L2I` each, part 0, from and into the full-width species. The
upper int lanes read zero, as an epilogue's padding lanes always have, so every lowering is already
safe over them; the one place they could do harm is a guard, whose mask is narrowed to the long
species' lanes before it reaches the accumulator. Section 2.2's rule holds: one species per lane
type, the width's own, which `VarkaEmitterMixedLaneSuite` asserts on the emitted constant pool.
The cost moves to the int side: an int value in a mixed kernel is computed over twice the lanes it
needs, so a kernel whose int work dominates (340's calendar fields over a timestamp) pays it twice
per row, where the pair representation would pay its long work twice. Casts, promotions, `date -
date` and `div` over widenings are loads and one conversion and do not notice. The pair
representation stays the measured alternative for the int-heavy kernels; the report of this row
proposes it as an emit option beside 340, since only that row's kernels can tell the two apart.
Every per-half concern of this section and of risks 2 and 3 below falls away with it.

*Correction, 19 October 2026, after the A/B (9.3): the int loads and stores of the loop are whole.*
As first emitted, every int load and store sat under the low-half mask, and the kernel ran at half
the long lane's rate at 512 bits and a sixth at 128. A masked load costs two to five unmasked ones,
so the loop of a mixed kernel now stops a long lane group early, `loopBound(length - lanes)`, which
leaves every iteration a whole int vector of rows to load and store unmasked; the epilogue runs the
masked group once or twice for the last one to two groups. The upper int lanes in the loop are then
the next group's rows, not zero - harmless for the same reason zero was, since a guard's mask is
narrowed to the long species before it is collected, and a lowering sees row values either way. A
destination therefore must not overlap a source, which no caller does (`VarkaFusedKernel`'s
contract now says so).

### 3.3 What the IR needs

One new node, on VARKA-88's `ConstDivide` precedent - the last node added, and the
closest structural sibling:

    record WidenLane(VarkaVectorIR child)      // INT child, LONG value
    record NarrowLane(VarkaVectorIR child)     // LONG child, INT value

`laneType()` answers `LONG` and `INT` respectively rather than deriving from the
child, which is the whole point: these are the two nodes where the lane changes,
and every other node keeps deriving. The eight `requireSameLane` constructors
stay exactly as they are - a mixed tree is still refused everywhere except at a
conversion, which is what keeps the refusal meaningful.

*Correction, 16 October 2026: `NarrowLane` exists.* VARKA-102 added it
(`VarkaVectorIR.java` 343) for the `TIME` field extracts (`VarkaTimeCompiler` 373 to 389), "admitted
ahead of that task's bi-lane kernel under one restriction the emitter enforces: it may only be an
output root"; `Analysis` (877 to 891) refuses it below a root. Its doc says "VARKA-28 lifts the
restriction rather than adding a node", and that is now this task's half of the IR: one new node,
`WidenLane`, and `NarrowLane` allowed below a root once the pair representation of 3.2 exists. The
ANSI guard below needs no new node either: `GuardedRange(child, Integer.MIN_VALUE,
Integer.MAX_VALUE)` at the long lane, which already declines a batch for values the row engine
would throw on, under the `NarrowLane`.

`NarrowLane` needs a decision the widening does not: Spark's `CAST(bigint AS
int)` **throws** on overflow under ANSI and truncates otherwise. The kernel
cannot throw, so the ANSI form declines the batch through VARKA-26's guard
channel - the same mechanism `VARKA-39.md` section 3 uses, and for the same
reason: the row engine then throws the identical exception attributed to the
identical row, because it is the row engine. The non-ANSI form is a plain
truncation with no guard.

### 3.4 What the emitter needs, and how big it is

*Correction, 16 October 2026:* the emitter was split after this was written. `Analysis` is its
own class (`varka/Analysis.java`, the field `lane` at 208), and `analysis.lane` is read 91 times
across `VarkaVectorWalk` (42), `VarkaBodyEmitter` (37), `VarkaDivisionLowering` (5),
`VarkaChronoLowering` (3), `VarkaLoopEmitter` (3) and `Slots` (1) (`git grep -c "analysis\.lane"`
over the main tree). The staging below applies per class; the count, not the method, is what
changed. The refusal quoted next is now `Analysis` 889 to 891.

This is the task's real cost. `Analysis.lane` is read at 85 sites and is
singular by construction; `analyze` throws `"a <X> node in a <Y> lane emission"`
on any disagreement. Making a kernel bi-lane means the lane becomes a property of
the *node* rather than of the emission, and the descriptors each site uses follow
the node it is emitting.

The staging that keeps this reviewable, and keeps VARKA-85's oracle useful at
every step:

1. **`Analysis.laneOf(node)`** alongside `Analysis.lane`, answering the node's
   own lane and agreeing with `analysis.lane` everywhere in a single-lane
   emission. Every site switched to it one method at a time, oracle green after
   each, **no emitted byte moving** - the same discipline and the same proof VARKA-85 step 3 used for the forty sites it converted.
2. **The conversion nodes**, emitted, with the loop still driven at the int
   species and long values still single because nothing has produced a pair yet.
3. **The pair representation** - two halves per long value, the two loads, the
   two stores, the doubled slots, the per-half masks.
4. **The compiler arms**, 3.5.

Steps 1 and 2 move no production bytes and are checkable by the oracle alone.
Step 3 is where the emission changes, and only for kernels that actually mix.

### 3.5 What the compiler admits

The types are the ones the two lanes already carry: `IntegerType`, `DateType`
and `YearMonthIntervalType` at the int lane; `LongType`, `TimeType` and
`DayTimeIntervalType` at the long lane.

* `CAST(int AS bigint)` and the other widenings, as `WidenLane`.
* **Catalyst's implicit promotions**, which is the case that matters most because
  nobody writes it: `i + l` arrives as `Cast(i, LongType) + l`, and today it
  declines with "one kernel holds one lane". That decline should disappear.
* `CAST(bigint AS int)` as `NarrowLane`, ANSI declining the batch.
* Out: casts to and from types neither lane holds - the timestamps
  (`m9/SCOPE.md` item 31), floating point, decimal and string - which
  decline by type as they do now.

*Correction, 16 October 2026, from `m8/PLAN.md`.* The compiler is Java now: the arms go in
`VarkaNodeCompiler.java` beside the int arithmetic (218 to 224) and `laneOf` (279), not in a Scala
`VarkaExpressionCompiler`. Milestone 8's cast matrix tags six pairs with this row (`m8/casts.tsv`,
`rows` = `332,28`): `TINYINT`, `SMALLINT` and `INT` to `BIGINT` (sign extension, exact in every
mode) and `BIGINT` to each of them (LEGACY wraps, ANSI raises `CAST_OVERFLOW`, TRY nulls). This
row admits the two `INT` pairs in LEGACY and ANSI, which need only the int and long lanes; the
`TINYINT` and `SMALLINT` pairs wait for 329's lanes, and the TRY narrowing (a null where ANSI
raises) is 332's, on this row's nodes. The timestamps are no longer a later milestone's:
`TIMESTAMP_NTZ` is milestone 8's (338 to 340), and 340's date fields - a long dividing into the
int32 calendar prefix - are a narrowing below a root, so they wait on this row.

*Correction, 19 October 2026:* the implicit promotion of an arithmetic operator, `i + l` as
`CAST(i AS BIGINT) + l`, still declines, because a long-lane `Add` is row 104's arm, not this row's;
the widening under it compiles, and 104 fuses the add over it. What this row admits of the
promotions is every one under an operator that already fuses on the long lane: the comparisons, the
picks and the conditionals (`i < l`, `greatest(i, l)`, `CASE WHEN i < l THEN i ELSE l END`). And the
`TIME` extracts, which built a narrowing at a root only, now build it anywhere: `hour(t) + 1` and
`hour(t) = 12` fuse.

### 3.6 Registered op counts

Per widening, per iteration: two `I2L` conversions and nothing else; the halves
then carry the rest of the tree. Per narrowing: two `L2I` conversions and one
`or`. A long column in a mixed kernel costs two loads where a single-lane long
kernel costs one, over twice the rows - the same per row.

*Correction, 19 October 2026, with 3.2's:* per widening one `I2L` and per narrowing one `L2I`, and
no `or`; an int column in a mixed kernel is one masked load a lane group, at half the bytes of a
long column's.

The number worth registering as a *prediction* is the one the A/B cannot settle
in advance: a mixed kernel's throughput per row against the single-lane long
kernel doing the same work. 6.1 states it.

## 4. Files

*Superseded on 16 October 2026 by the list below the first table: the emitter split, the Java
compiler and the done first-commit items.*

| file | what |
|---|---|
| `dev/varka_canary/I2LProbe.java` | the conversion settled before the emitter work (this commit) |
| `sql/varka/plans/m5/PLAN.md` | 2.2 corrected per section 2 above (this commit) |
| `VarkaVectorIR.java` | `WidenLane`, `NarrowLane`, their lane rules |
| `VarkaLoopEmitter.java` | `laneOf(node)`, the conversion emission, the pair representation, per-half masks and validity |
| `VarkaExpressionCompiler.scala` | the cast arms and the implicit promotions |
| `VarkaReferenceEvaluator.scala`, `VarkaIrGrammar.scala` | the oracle arms and the fuzzer productions |
| `VarkaCoverageSuite.scala` | the mixed-width rows |
| `sql/varka/emitted_bytes.json` | new rows only, and the fuzz blocks once the grammar gains productions; **no existing row may move** |

* `.../codegen/varka/VarkaVectorIR.java`: `WidenLane`; `NarrowLane`'s root-only doc lifted
* `.../codegen/varka/Analysis.java`: `laneOf(node)`; `NarrowLane` below a root; the pair's slot
  count in the budgets
* `.../codegen/varka/VarkaVectorWalk.java`, `VarkaBodyEmitter.java`, `VarkaDivisionLowering.java`,
  `VarkaChronoLowering.java`, `VarkaLoopEmitter.java`, `Slots.java`: the 91 lane reads moved to the
  node's lane; the conversions; the pair representation, per-half masks and validity
* `.../codegen/varka/VarkaEmitBudget.java`, `VarkaEmitCostTable.java`: a long node of a mixed kernel
  costs two slots and two operations
* `.../codegen/VarkaNodeCompiler.java`: the cast arms and the implicit promotions
* test: `VarkaReference.java`, `VarkaIrGrammar.java`, `VarkaCanonicalIr.java`: the oracle arms, the
  fuzzer productions, the canonical form
* test: `VarkaCoverageSuite.scala`; `sql/varka/coverage.json`, `docs/sql-varka.md`: the mixed-width
  rows, regenerated
* `sql/varka/emitted_bytes.json`: new rows only; no existing row may move
* `sql/varka/plans/m8/census_curated.txt`, `census.tsv`: the `cast` and `bigint` entries' fused
  types

*19 October 2026, as built:* no pair representation, so `VarkaEmitBudget` prices a widening as one
operation and `VarkaEmitCostTable` gains its one feature; the lowerings moved are the walk, the body
emitter, `Slots`, `Analysis`, the chrono and division lowerings; the compiler's change is
`VarkaNodeCompiler`'s two arms, `VarkaTimeCompiler`'s and `VarkaExpressionCompiler`'s root-only
special case removed and `CompiledVarkaProjection`'s table check; the tests add
`VarkaEmitterMixedLaneSuite`, `VarkaKernelChecks`' mixed lane, `VarkaReference.mixedValue`,
`VarkaIrGrammar.MixedShapes` and its fuzz test and oracle block, and the benchmark
`VarkaLaneConversionBenchmark`; `dev/varka_census.py`'s fused pairs, `width_audit.json` and
`SKILLS.md` with them.

## 5. Tests

1. **The oracle at every step of 3.4**, with steps 1 and 2 moving no bytes at
   all. That is the check that the bi-lane refactor did not disturb the
   single-lane emissions, and it is the same one VARKA-85 relied on.
2. **The differential on mixed trees at both widths**, which is the milestone
   row's own validation: `cast(int AS long) + long`, `date - date`, and a tree
   that widens, computes and narrows again.
3. **Both signs and both extremes**, because `I2L` sign-extends and `L2I`
   truncates: `Integer.MIN_VALUE`, `Long.MIN_VALUE`, and values that do and do
   not survive a narrowing.
4. **The ANSI narrowing declines the batch** rather than throwing or wrapping,
   asserted as a status bit the way VARKA-26's guard is.
5. **The epilogue**, at lengths that leave the tail in the low half only, in
   both halves, and exactly on the boundary - the three cases per-half masks can
   get wrong and a single mask cannot.
6. **No regression on single-width shapes**, which the milestone row asks for and
   which the oracle answers byte-for-byte rather than by measurement.

## 6. The measurement

The A/B that decides nothing structural but has to be committed: a mixed kernel
against the row engine and against the single-lane long kernel, at both widths,
on `cast(int AS long) + long`.

*Added 16 October 2026.* `m7` row 374 closed "Done, no fix": at a 128-bit x86 species two-lane
long masks run the Java fallback and about 10 in 100 warm kernels or more release at the deadline,
and a mixed kernel's long halves are two-lane there. So the 128-bit numbers of this A/B carry the
release rate beside them (`m8/PLAN.md` 10.3 item 4), and prediction 1 below is read at 128 bits
against the single-lane long kernel's own rate, not as a tie in time alone. Where the A/B runs on
a runner it is pinned to one CPU (`benchmark.yml`'s `expected-cpu`).

### 6.1 Predictions, registered before the run

1. **A mixed kernel matches the single-lane long kernel per row**, within the
   band, because it does the same work per row - the tie section 2.1 explains.
   A mixed kernel that is materially *slower* per row means the pair
   representation is spilling, and the assembly says so before any timing.
2. **`cast(int AS long) + long` beats the row engine by the margin the long lane
   already shows** on `l + l`, not more: the conversion is two instructions and
   the shape is bandwidth-bound.
3. **No single-width benchmark moves.** Steps 1 and 2 of 3.4 are byte-identical
   by construction, so any movement is a bug rather than a cost.

## 7. Risks

1. **The 85-site lane refactor is the task.** Everything else here is small. If
   step 1 of 3.4 cannot be done without moving bytes, the oracle will say so
   immediately, and the answer is to split the step further rather than to
   regenerate.
2. **Slot pressure.** Doubled locals for long values in a mixed kernel can breach
   the group budget or the 64KB method limit - and the epilogue is already known
   to be able to exceed 64KB (`varka-epilogue-64k-method-limit`). A mixed kernel
   is the shape most likely to find it.
3. **Per-half validity.** Two helpers in one body, chosen per half, is the
   detail most likely to be wrong in a way only the epilogue tests catch. The
   `validityBitsAt1` regression during VARKA-85 step 4 is the precedent.
4. **A second species creeping back in.** Any future "just load the ints
   narrower" shortcut reintroduces exactly the pollution section 2.2 rules out.
   The emitter should have one species per lane type and a test that says so.

## 8. Sequencing

1. **This PR:** the plan, `I2LProbe`, and 2.2's correction. No emitter code.
   *Done (16 October 2026): all three are on master.*
2. `laneOf(node)` across the 85 sites, oracle green, no byte moved.
3. The conversion nodes and their emission, single-valued.
4. The pair representation: loads, stores, slots, per-half masks and validity.
5. The compiler arms, the implicit promotions, the coverage rows.
6. The A/B, 6.1 scored, and 2.2's numbers updated with what a real mixed kernel
   measures rather than what a JMH pair predicted.

*19 October 2026:* steps 2 to 5 landed in one pull request, step 4 replaced by 3.2's correction;
step 6's benchmark class is committed and its run is owed (9.2).

## 9. Outcome

*19 October 2026.* A kernel can hold both lanes: `WidenLane` is in the IR, `NarrowLane` is admitted
below a root, the compiler builds both from the two `INT` and `BIGINT` casts, and every promotion
under an operator the long lane already fuses compiles. Three departures from the plan, each
recorded where it applies: the loop is driven at the long lane (3.2), the arithmetic promotion
waits on 104 (3.5), and the A/B, run at the end, failed prediction 1 until the loop's int loads and
stores were made whole and a conversion's target species constant (9.3).

**The refactor (step 2).** The emitter asks `Analysis.laneOf(node)` for the lane a node's
instructions name, `Slots.species(lane)` for its species local and `lanesOf`/`literalsOf` for its
lane count and literal table, at every site the walk, the body emitter and the two lowerings
read the lane. In a single-lane kernel each answers what `analysis.lane` did, and
`VarkaEmittedBytesSuite` passed unchanged on the refactor alone, before any node was added. The
`TIME` extracts' narrowing roots are not mixed kernels - a root narrowing at its store keeps its
value in the long lane - so their bytes are what they were.

**The emission.** A kernel is mixed when it is driven at the long lane and holds an int-lane node
other than such a root; it then maps each column and each output at its own lane's width, keeps
an int species local and an int literal table beside the long ones (the long `run` already passes
both arrays), narrows an int-lane guard's mask to the long species before it is collected, keeps a
stored-value guard as compares rather than a min and a max, and materializes no calendar prefix (its
scratch is laid out by the driving lane's stride). `narrowHalfSpecies` is ignored there, since a
half-width int species beside the full-width one is a second species. Nothing else changed: the
validity words are bits a row whatever the lane, and `fromLong` reads as many bits as its species has
lanes, so the long lane's masks come from the same words.

**The compiler.** `CAST(int AS bigint)` is a `WidenLane` in every mode. `CAST(bigint AS int)` is a
`NarrowLane` under LEGACY and a `NarrowLane` over a `GuardedRange` of the int range under ANSI, which
declines a batch holding a value past the range so the row engine raises `CAST_OVERFLOW` on the row;
`try_cast` declines with its reason, for 332. The arms sit in the int arithmetic family, whose
switch now names them. Conditions of the two lanes meet on the long one: a filter's int conjunct
beside a long one, and an `AND` or `OR` of the two, compare the int side widened, and an int
`IS NOT NULL` reads its column through a widening (`VarkaConditionCompiler.onLongLane`); without
that the `isnotnull(i)` Catalyst infers beside `i < l` left half the filter to the row engine. A
range set has no long-lane form and still declines there, as does a `CASE` whose condition and
branches disagree. The `TIME` extracts' root-only restriction (`compileRoot`'s special case and
`narrowedOperand`) is gone. `CompiledVarkaProjection` admits both literal tables in a kernel driven
at the long lane.

**The proofs.** `VarkaEmitterMixedLaneSuite` (JUnit, its own JVM): the widening over int extremes,
over int arithmetic, a calendar field and a division; a narrowing below an int node and as a root
read again below one; the promotions as selections, conditionals and picks; widen, compute and
narrow again with literals of both lanes; the ANSI narrowing answering every value that fits and
declining wherever a value does not, at rows across the batch; a conjunction of a narrowing, a
calendar field over a column-bound date and two dates; an int guard that would condemn every batch from
the upper lanes and does not; one species per lane type; and, opt-in, every int widened and
narrowed back under ANSI, the narrowing converting in the tree - 2^32 rows each way, without a decline, in about 25 seconds (the suite took 36 s with it
and 10 to 14 s without); the nightly's sweep step runs the suite whole, since its ScalaTest `-z
opt-in` filter selects no JUnit test. Each case runs
at the long lane's 2 and 8 lanes, at lengths from 1 to 129, over four null patterns, both bodies and
both validity fills. The IR fuzzer has a third corpus, `drawMixedShape` (seed 20261019): trees of
both lanes built over the two single-lane grammars, so a calendar field, a division or a range set
can sit under a widening and a narrowing anywhere; 3000 draws under random options matched
`VarkaReference.mixedValue`, which answers each conversion and evaluates the rest with the existing
evaluators. The bytes oracle pins it as `fuzz_mixed`. Nine coverage rows (`CAST(i AS BIGINT)`,
`CAST(time_to_millis(t) AS INT)`, `greatest(i, l)`, `CASE WHEN i < l THEN i ELSE l END`, `i < l`,
`l = i`, `CAST(time_to_millis(t) AS INT) > i`, `hour(t) + 1`, `hour(t) = 12`); the narrowing's rows
narrow a bigint every fixture holds inside the int range, since `CAST(l AS INT)` raises under ANSI
on the differential suite's fixtures, whose `l` reaches 5000000000, on the row engine as on Varka,
and so has no "matches the row engine" form there. `VarkaCoverageDifferentialSuite` runs the nine
against Spark - both consumers, the partitions, ANSI's positions and the domains - with the
narrowing's two rows and `hour(t) + 1` listed as total domains; the `TIME` extracts' entries on its
declined list for a `CASE` arm and a false `AND` are gone, since they are served now, and their
`COALESCE` tails join the int-valued rows' known decline. The census marks the two pairs fused,
`INT` to `BIGINT` in every mode and `BIGINT` to `INT` in LEGACY and ANSI, and holds them to the
rows that cast them (VARKA-328's four-way rule); its fused count moves from 122 to 124. `emitted_bytes.json` gains the nine rows and the `fuzz_mixed` blocks; no existing row or block
moved (checked by key, not by eye), and the option-arm digests, taken over the whole table, moved
with the new shapes. `width_audit.json` gains the rows: at 512 bits no refusal, and neither
conversion is refused at either width; at 128 bits the long lane's two-lane masks the existing long
rows have, and since the fix of 9.3 the epilogue's on every row, `CAST(i AS BIGINT)` and `hour(t) =
12` included, which the docs table's 128-bit column now reads as per-lane. The cost model prices
`WidenLane` from one probe; refitting moved eight existing prices in their third decimal and no
emitted byte, and after the fix `WidenLane`'s own price moved, since a mixed kernel has no fixed
term of its own and the epilogue's loop is charged to the node every such probe holds.

**Found on the way.** The gate over both modules found what the row's own suites had not: an
int-lane column-bound guard in a mixed kernel kept its min-and-max accumulators, which are the
driving lane's vectors, and the class failed verification (now the guard compares, as 3.2's
correction says); the coverage composition fuzzer, the guard verdict and the differential suite's
verdict read a long-lane kernel through the long-lane reference and its one literal table, and now
read a kernel of both lanes through `mixedValue` and both tables. The review of the diff found the
same guard and the stale comments that still said a kernel holds one lane. A second review, of the condition
lifting, found a filter's lifted conjuncts committed before the budget check; they are now committed
only once it takes them.

### 9.1 The predictions

| | prediction | measured | |
| :-- | :-- | :-- | :-- |
| 1 | a mixed kernel matches the single-lane long kernel per row | as first emitted, half the long lane's rate at 512 bits and a sixth at 128 in L2; after the fix, at or above it at every rung and both widths but 128 bits in L2, 0.91 of it (9.3) | held after a fix; failed as first emitted |
| 2 | `cast(int AS long) + long` beats the row engine by the long lane's margin on `l + l` | not measured, and not compiled until 104 | open |
| 3 | no single-width benchmark moves | no single-width kernel's bytes moved, which is what the prediction rested on | held, structurally |

No calibration entry: the predictions are of throughput, not of a cost the calibration table keeps.

### 9.2 What is owed

Prediction 2 waits on 104's long `Add`, which `cast(int AS long) + long` compiles through. The
128-bit release rate section 6 asks beside the numbers was not measured; what the change does to it
is in 9.3. The pair representation stays the alternative to measure for the int-heavy kernels of
340, where a mixed kernel computes its int work over twice the lanes it needs.

### 9.3 The A/B, and the loop it changed

`VarkaLaneConversionBenchmark` was run first at the commit that implemented the row, alone on the
machine under both JVM slots, and its mixed arm failed prediction 1 outright: `cast(i AS bigint) + l`
ran 3969.0 M rows/s at 512 bits in L2 against 7861.8 for `l2 + l` and 5144.1 for a scalar loop, and
at 128 bits 1424.6 against 8346.4 (`sql/varka/spikes/lane-conversion/results/before-fix-*`). Two
causes, each read from the JIT rather than guessed from the timing:

* **The conversion was not intrinsified.** `convertShape` took its target species from the
  species local, and `-XX:+PrintIntrinsics` refuses both `I2L` calls with `missing constant ...
  vlen_to=LoadI` (`results/convert-intrinsic.txt`): C2 needs the target's lane count as a
  constant, and the local, though it holds a static final species, is a load. The emitter now
  loads the target from the named species field.
* **A masked load costs two to five unmasked ones.** A hand-written loop of the same work
  (`src/Hand28.java`, `results/hand-loop.txt`) ran 4702 M rows/s at 512 bits and 16384 rows with
  the int load under the low-half mask against 12646 unmasked and 12204 for the long lane alone,
  and at 128 bits 1552 against 7362 and 8552. The pair representation of 3.2 ran 11788 and 5127:
  close at 512, a third slower at 128. The bounded loop the emitter now takes - stop a long lane
  group early, load and store whole in the loop, masked in the epilogue - ran 10652 and 7150, and
  keeps the long-driven design. A branch between the whole and the masked form inside the loop,
  tried first, kept C2 from removing the vector boxes at the join and ran at 4604 at 512 bits,
  allocating on every call (`results/branch-form.txt`).

The benchmark after the fix, under both slots at a load of 0.93 (the committed files under
`sql/catalyst/benchmarks/`; an earlier run of the same commit, `results/first-run-*`, agrees but at
512 bits and 262144 rows, where the mixed arm read 6262.7 against the long lane's 6441.5, a tie rather
than a lead), against the first run:

| arm, 512 bits | 16384 rows | 262144 rows | 1000000 rows | 8388608 rows |
| :-- | --: | --: | --: | --: |
| `cast(i AS bigint) + l` | 3969.0 to 9972.0 | 7493.0 to 7547.0 | 3281.0 to 4166.3 | 1692.8 to 2020.1 |
| `l2 + l`, the long lane | 7861.8 to 7790.8 | 6459.0 to 6298.8 | 3364.7 to 3378.6 | 1811.6 to 1804.6 |
| `cast(i AS bigint) < l` | 8987.4 to 11122.9 | 8011.5 to 11450.8 | 6522.9 to 9867.8 | 2171.2 to 4605.9 |
| `l2 < l`, the long lane | 11122.9 to 11198.9 | 7836.4 to 9351.6 | 6575.3 to 6775.3 | 2834.5 to 3085.3 |
| `cast(l AS int) + i` | 10157.5 to 11206.6 | 9047.6 to 7919.3 | 4651.3 to 6195.7 | 1944.5 to 2926.7 |
| the scalar loop | 5144.1 to 5160.3 | 4790.4 to 4806.3 | 2956.0 to 2962.1 | 1915.4 to 1828.5 |

| arm, 128 bits | 16384 rows | 262144 rows | 1000000 rows | 8388608 rows |
| :-- | --: | --: | --: | --: |
| `cast(i AS bigint) + l` | 1424.6 to 7865.6 | 4665.6 to 7230.0 | 2045.0 to 3339.0 | 1180.9 to 2114.3 |
| `l2 + l`, the long lane | 8346.4 to 8655.0 | 5173.0 to 5163.9 | 2886.5 to 2902.4 | 1751.6 to 1756.3 |
| `cast(l AS int) + i` | 4824.5 to 7790.8 | 4509.0 to 6872.8 | 2826.4 to 5006.2 | 1379.5 to 2691.1 |
| the scalar loop | 4568.9 to 4581.7 | 4260.8 to 4151.3 | 2860.2 to 2839.5 | 2089.9 to 2092.3 |

A mixed kernel now matches or beats the long lane per row at every rung of both widths but one,
128 bits in L2, where it runs 0.91 of it; past L2 it is ahead because it reads four bytes a row
fewer. Three things the numbers carry that are not the mixed kernel's to answer:

* **The 128-bit selections run at about 240 M rows/s, mixed and long alike,** before and after.
  That is `m7` row 374's finding - x86 C2 lowers no two-lane mask, so a long selection runs the
  Vector API's Java fallback per mask operation - and `m9/SCOPE.md` item 101's design work, not a
  cost of the conversion.
* **At 128 bits a mixed kernel's epilogue now runs on every batch,** since the bounded loop leaves
  at least one long lane group to it, and its bounds mask is a two-lane long mask: about 90 to 120
  bytes a call of fallback allocation in a probe at 4096 and 16384 rows, under the 256 a column the
  warm-up's verdict allows (`VarkaKernelWarmup.SEGMENT_BYTES_PER_COLUMN`), so the verdict should
  stand; the release rate itself was not measured (9.2). A single-lane long kernel pays the same
  epilogue only on a batch of odd length.
* **The int-lane reference arm at 16384 rows is the tiered JIT's, not the kernel's.** `i2 + i` read
  24786.7 at 512 bits in the first run and 1160.7 in the committed one, though its kernel's bytes
  did not change. Run alone it reads 2595.7 at the first commit and 2894.7 at the fixed one, 20202.2
  under `-XX:-TieredCompilation`, and the 262144-row rung is unaffected
  (`results/int-arm-tiering.txt`): which compile the harness's call site ends up with at that rung
  depends on what ran before it. Not this row's; reported for a row of its own.
