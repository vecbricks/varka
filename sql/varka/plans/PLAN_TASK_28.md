# Task 28: lane-width conversion

*Written 18 September 2026. Section 2.2 of `PLAN_MILESTONE_5.md` scoped this
task; it was moved there from `PLAN_MILESTONE_4.md` 2.6 on 4 September 2026.*

## 1. What this is for

One kernel, two lane widths. Today an emission carries exactly one
`VarkaLoopEmitter.Lane`, every node must agree with it, and `VarkaVectorIR`
refuses a tree that mixes lanes in eight constructors. That is the right default
and it is why tasks 85 and 29 could ship a second lane without disturbing the
first. It also means Varka cannot compile the shapes where the two widths meet,
and three tasks are waiting behind exactly that:

* **104** (`Long` arithmetic, which closes task 30) needs this task's cast.
* **39** (`date - date`) is an int32 input pair and an int64 output - the first
  mixed-width kernel - and closes inside **103**.
* Catalyst's own implicit promotions produce mixed trees unprompted: `i + l`
  over an `int` and a `bigint` column is `Cast(i, LongType) + l` before Varka
  ever sees it, so today it declines on a lane mismatch rather than on anything
  a reader would call unsupported.

## 2. The decision the milestone pre-registered, and why it has to be re-read

Section 2.2 records the loop-shape question as settled: *"task 28 opens already
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
*"the design decision (task 39) is the two-part convertShape from the preferred
species regardless, because a second species poisons every other IntVector loop
in the same JVM"* - and that sentence, not 2.2's summary, is the one to build
from.

**So: two-part `convertShape` from the preferred species.** At equal measured
throughput, it is the shape that cannot poison the process. Section 2.2 of the
milestone should be corrected to say so, and this plan's first commit does that.

## 3. The design

### 3.1 The mechanism, already proven

Task 88 step 2 built and shipped exactly this conversion, for a different target
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
way task 88's were. What is left is structural.

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

### 3.3 What the IR needs

One new node, on task 88's `ConstDivide` precedent - the last node added, and the
closest structural sibling:

    record WidenLane(VarkaVectorIR child)      // INT child, LONG value
    record NarrowLane(VarkaVectorIR child)     // LONG child, INT value

`laneType()` answers `LONG` and `INT` respectively rather than deriving from the
child, which is the whole point: these are the two nodes where the lane changes,
and every other node keeps deriving. The eight `requireSameLane` constructors
stay exactly as they are - a mixed tree is still refused everywhere except at a
conversion, which is what keeps the refusal meaningful.

`NarrowLane` needs a decision the widening does not: Spark's `CAST(bigint AS
int)` **throws** on overflow under ANSI and truncates otherwise. The kernel
cannot throw, so the ANSI form declines the batch through task 26's guard
channel - the same mechanism `PLAN_TASK_39.md` section 3 uses, and for the same
reason: the row engine then throws the identical exception attributed to the
identical row, because it is the row engine. The non-ANSI form is a plain
truncation with no guard.

### 3.4 What the emitter needs, and how big it is

This is the task's real cost. `Analysis.lane` is read at 85 sites and is
singular by construction; `analyze` throws `"a <X> node in a <Y> lane emission"`
on any disagreement. Making a kernel bi-lane means the lane becomes a property of
the *node* rather than of the emission, and the descriptors each site uses follow
the node it is emitting.

The staging that keeps this reviewable, and keeps task 85's oracle useful at
every step:

1. **`Analysis.laneOf(node)`** alongside `Analysis.lane`, answering the node's
   own lane and agreeing with `analysis.lane` everywhere in a single-lane
   emission. Every site switched to it one method at a time, oracle green after
   each, **no emitted byte moving** - the same discipline and the same proof task
   85 step 3 used for the forty sites it converted.
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
  (`SCOPE_MILESTONE_8.md` item 31), floating point, decimal and string - which
  decline by type as they do now.

### 3.6 Registered op counts

Per widening, per iteration: two `I2L` conversions and nothing else; the halves
then carry the rest of the tree. Per narrowing: two `L2I` conversions and one
`or`. A long column in a mixed kernel costs two loads where a single-lane long
kernel costs one, over twice the rows - the same per row.

The number worth registering as a *prediction* is the one the A/B cannot settle
in advance: a mixed kernel's throughput per row against the single-lane long
kernel doing the same work. 6.1 states it.

## 4. Files

| file | what |
|---|---|
| `dev/varka_canary/I2LProbe.java` | the conversion settled before the emitter work (this commit) |
| `sql/varka/plans/PLAN_MILESTONE_5.md` | 2.2 corrected per section 2 above (this commit) |
| `VarkaVectorIR.java` | `WidenLane`, `NarrowLane`, their lane rules |
| `VarkaLoopEmitter.java` | `laneOf(node)`, the conversion emission, the pair representation, per-half masks and validity |
| `VarkaExpressionCompiler.scala` | the cast arms and the implicit promotions |
| `VarkaReferenceEvaluator.scala`, `VarkaIrGrammar.scala` | the oracle arms and the fuzzer productions |
| `VarkaCoverageSuite.scala` | the mixed-width rows |
| `sql/varka/emitted_bytes.json` | new rows only, and the fuzz blocks once the grammar gains productions; **no existing row may move** |

## 5. Tests

1. **The oracle at every step of 3.4**, with steps 1 and 2 moving no bytes at
   all. That is the check that the bi-lane refactor did not disturb the
   single-lane emissions, and it is the same one task 85 relied on.
2. **The differential on mixed trees at both widths**, which is the milestone
   row's own validation: `cast(int AS long) + long`, `date - date`, and a tree
   that widens, computes and narrows again.
3. **Both signs and both extremes**, because `I2L` sign-extends and `L2I`
   truncates: `Integer.MIN_VALUE`, `Long.MIN_VALUE`, and values that do and do
   not survive a narrowing.
4. **The ANSI narrowing declines the batch** rather than throwing or wrapping,
   asserted as a status bit the way task 26's guard is.
5. **The epilogue**, at lengths that leave the tail in the low half only, in
   both halves, and exactly on the boundary - the three cases per-half masks can
   get wrong and a single mask cannot.
6. **No regression on single-width shapes**, which the milestone row asks for and
   which the oracle answers byte-for-byte rather than by measurement.

## 6. The measurement

The A/B that decides nothing structural but has to be committed: a mixed kernel
against the row engine and against the single-lane long kernel, at both widths,
on `cast(int AS long) + long`.

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
   `validityBitsAt1` regression during task 85 step 4 is the precedent.
4. **A second species creeping back in.** Any future "just load the ints
   narrower" shortcut reintroduces exactly the pollution section 2.2 rules out.
   The emitter should have one species per lane type and a test that says so.

## 8. Sequencing

1. **This PR:** the plan, `I2LProbe`, and 2.2's correction. No emitter code.
2. `laneOf(node)` across the 85 sites, oracle green, no byte moved.
3. The conversion nodes and their emission, single-valued.
4. The pair representation: loads, stores, slots, per-half masks and validity.
5. The compiler arms, the implicit promotions, the coverage rows.
6. The A/B, 6.1 scored, and 2.2's numbers updated with what a real mixed kernel
   measures rather than what a JMH pair predicted.

## 9. Outcome

*To be written when the work lands, section by section as the plan's own rule
asks. Nothing above is to be rewritten to look prescient; a correction is added
and says what it corrects - section 2 is the first of them, and it corrects the
milestone rather than this plan.*
