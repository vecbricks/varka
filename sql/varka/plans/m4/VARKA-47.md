# VARKA-47: one validity write per word

## 1. Where this came from

`m4/PLAN.md` row 47 and section 2.17, the last of the three tasks
that section opened after VARKA-32 measured the validity write at 55.6% to
56.7% of a four-field kernel's time. VARKA-45 took the dense path away with a
driver-side `setValid`; VARKA-46 gave the write a helper that inlines. 47 is
the one 2.17 said to do last, "only if 45 and 46 leave something on the
table", and three later tasks have said what they left.

**VARKA-76 (`VARKA-76.md` 10.6, 10.7).** The per-group read-modify-write
is measurably a *four-lane* problem. A validity group is `lanes` bits, so at
4 lanes it is half a byte and two consecutive groups read-modify-write the
same byte and serialise on it; at 8 lanes a group is exactly one byte and at
16 exactly two, and there the chain is absent. VARKA-76's write-count ladder
puts VARKA-46's width-named helpers ahead at every write count at 8 and 16
lanes and behind by 4 to 7% at 4 lanes with one or two writes - and its root
cause work (10.4) found the difference is register-file traffic around that
serialised chain rather than anything about the helpers themselves. VARKA-76
closed as a recorded decline for that reason: "the lever is the write
itself", and tuning a helper choice inside a loop shape scheduled for
replacement is the wrong move. This task is that replacement.

**VARKA-70 (`VARKA-70.md` 9.5).** The bitmap pass writes whole bitmaps in
the driver for every root whose validity is a pure AND/OR of input bitmaps,
so those roots have no per-group write at all. What it left per lane group is
named there: a `Cond` root's selection OR, an `IfElse`'s blend, `make_date`'s
validity test, a pick's two reads, and the read-and-AND a range guard keeps.
Those are this task's population - and it is **not** "the masked path",
which is how 2.17 described what 47 would be left with before VARKA-45 was
built. A `Cond` root's slot holds a selection *bitmap* rather than validity,
so `fillsValidityOnce` excludes it by design and a fused filter ORs once per
lane group **on every batch, dense or masked** (2.17's own update says so, and
the parity benchmark's comment beside case 884 repeats it). The columnar
filter is the most common shape in the whole project and it is in this task's
population at both null states; anything below that says "masked" means the
masked *value* roots, and the tests must cover the dense filter as well.

**Also VARKA-70 (9.5, and `VarkaLoopEmitter` at the input-prologue comment).**
A second, unrelated item was assigned here in as many words: the masked
driver's per-batch null-state prologue for every referenced input, most of
which is dead in the driver itself, "which is the whole of the gap left on a
64-row batch at AVX-512 (chunk 64 at 1080.1 against 1549.6)". The liveness
pass that would remove it exists - VARKA-70 built it for the loop bodies - and
the driver is planned with `live = null` deliberately, leaving the change
here. Section 3.4 takes it; it is a separate mechanism from the rest of this
plan and is measured separately.

**Gating.** Row 47 read "gated on VARKA-46 and on VARKA-44's non-aligned
lengths". VARKA-76 was the task-46 half and closed on 10 September, so that
half is discharged. The task-44 half is not a gate on the design below and
section 2.3 says why: this plan's word writer keeps the loop and the epilogue
as they are and never carries a live accumulator across the boundary between
them, so what VARKA-44 later decides about splitting the epilogue cannot
invalidate it.

## 2. The admission check, to do first

**Do not write the emitter change first.** This task's whole premise is a
64-bit store where today there are byte, short or int stores, and the
repository already carries a recorded reason why that was not done: the
javadoc on `VarkaVectorSupport.validityBitsAt` says that only the
`groupBytes(lanes)` bytes a group occupies are touched, "never a fixed 64-bit
word", because addressing a whole word "would read past the end of the
bitmap near it, which is why this used to need a word-alignment bound and
left every batch under 57 rows to the scalar path". A 64-bit store has the
same hazard as the 64-bit load that rule was written against. If the check
below fails, the honest outcome is to close this task with the finding
recorded, exactly as VARKA-69's section 2 was allowed to.

### 2.1 Is there a word to write into?

`VarkaLoopEmitter` step (2) computes `s.validityBytes = (length + 7) / 8` and
step (3) materialises each destination bitmap as a `MemorySegment` of exactly
that many bytes. A `putLong` at the last word's offset therefore throws
`IndexOutOfBoundsException` on any batch whose length is not a multiple of
64 - the segment bound is the hazard, before the allocation behind it is.

Two things to establish, in this order:

1. **The bound is ours to widen.** The size argument to `loadSegment` is a
   plan-time choice, so rounding `validityBytes` up to a multiple of 8 for
   the *destination* bitmaps is a one-line change. It is sound only if the
   underlying Arrow buffer really has those bytes.
2. **The Arrow buffer really has them.** `VarkaKernelEvaluator.allocateVector`
   builds each destination through `BaseFixedWidthVector.allocateNew(len)`,
   whose validity buffer is `(len + 7) / 8` bytes *requested* and whatever
   the allocator's rounding policy gives back. The number that matters is
   `getValidityBuffer().capacity()`, which the compaction path at
   `VarkaKernelEvaluator.scala:1387` already reads, so it is available and
   not a new dependency. Check it directly, over a ladder of lengths that
   includes the awkward ones - 1, 7, 8, 9, 63, 64, 65, 4095, 4096 - and
   assert `capacity() >= ((len + 63) / 64) * 8` for every one - that bound is
   the highest word a group can address, since the last group's rows are
   below `len` and its word index is therefore at most `(len - 1) >>> 6`. Do
   this as a committed test in the evaluator's suite, not as a scratch
   program, because the answer is a property of an Arrow version this
   repository upgrades.

**If capacity falls short at some length**, the fallback is not to abandon
the task: allocate the destination validity buffer explicitly at the rounded
size rather than through `allocateNew`'s implicit sizing. Establish which of
the two routes is needed before designing around either.

**The source side is not in scope and must not move.** VARKA-46 measured the
read helper as not the cost - the masked rows sit within 0.04 ns/row of their
dense-plus-one-write A/B - and a source bitmap belongs to the input batch,
whose sizing this project does not control. Every `validityBitsAt*` call and
its byte-only addressing stays exactly as it is. This is a write-side task,
as 46 was.

### 2.2 Is the destination word written by exactly one writer?

A plain store, unlike an OR, destroys whatever else is in the word. Three
claims have to hold, and all three are checkable by reading the emitter
rather than by measuring:

1. **The bitmap is zero before the loop runs.** Step (3) of the driver calls
   `VarkaVectorSupport.zero` on every destination bitmap that is neither
   served by VARKA-70's pass nor filled once by VARKA-45's `setValid`, which is
   exactly the population this task writes. Confirm that a root reaching the
   per-group write always takes the `zero` branch.
2. **No two loop methods share a destination.** `groupOutputs` partitions
   *outputs*, and each output owns its own `dstValidity[o]`, so two loop
   methods never write the same bitmap. Confirm against `planSlots` rather
   than assuming it from the partitioner's shape.
3. **The bitmap pass and the per-group write never both write one output.**
   `servedByPass` decides this per output and the driver's step (3) branches
   on the same predicate, so the two are the same decision by construction.
   Confirm that it is read at both sites with the same arguments.

If any of the three fails, the word writer must OR rather than store, which
keeps the read and therefore keeps the four-lane chain - that is, the task
loses its mechanism and should be closed rather than shipped in a form that
cannot deliver what it exists to deliver.

### 2.3 Does the loop/epilogue boundary force a live accumulator across it?

It must not. The loop and the epilogue are separate *methods* (VARKA-24), so
an accumulator live across the boundary would have to travel through a field
or a return value, and VARKA-44 may later split the epilogue again. The design
below avoids it: the loop flushes whatever it has accumulated before it
returns, and the epilogue keeps today's `orPartialValidityBitsAt` - one
read-modify-write per batch, on the one group whose row count is not a lane
width anyway. Confirm that `loopBound` can be a non-multiple of 64 (it is
`length - length % lanes`, so a 1000-row batch at 16 lanes gives 992), since
that is what makes the flush necessary rather than optional.

## 3. The design, if the check passes

### 3.1 The option space

Three ways to spend what the check buys, and they differ in what they remove
rather than in degree. The plan does not pick one by argument; section 6
measures them against each other on the same ladder.

**Option A: store every group, read none.** Keep one store per lane group
where there is one today, and drop the read: the accumulator holds the word
so far, each group ORs its bits into it in register, and the whole 64-bit
word is stored every time. The memory dependency chain VARKA-76 found becomes
a register one. Cheapest to build, and the variant that most directly tests
10.4's hypothesis, because it changes the traffic without changing the loop's
shape at all.

It is not free, and the plan should not pretend the store count staying equal
means the store cost does. The store gets *wider* - eight bytes where today
it is two at 16 lanes and one at 8 and 4 - so A trades a load and its
dependency for four to eight times the bytes written into the same cache
line. That is the claim to measure, not an argument to win: the stores are to
the same address within a word and the line is already dirty, so the extra
bytes should be nearly free, but "should be" is what VARKA-76 said about the
helper choice.

**Option B: store once per word.** The accumulator is stored only when a word
completes, which the emitter can test as `(i & 63) == 64 - lanes` - a compare
and a well-predicted branch per group, taken one time in four at 16 lanes and
one in sixteen at 4. Removes three stores in four at 16 lanes and fifteen in
sixteen at 4, which is what 2.17 described, at the price of a branch inside
the lane-group loop.

**Option C: an outer stride of 64 rows.** The group count per word is
`64 / lanes`, a compile-time constant, so the loop can be structured as an
outer word loop with the lane-group body emitted `64 / lanes` times inside
it. No branch and no accumulator test, and it hands C2 an unrolled body,
which is the ILP this project prefers to state rather than hope for. It also
multiplies the loop method's size by four at 16 lanes and by sixteen at 4.
The limit that bites there is *not* `GROUP_BUDGET`, which is a plan-time op
budget per group and would be unchanged by an unroll: it is the emitted
method's size against C2's parse and inlining thresholds - VARKA-46's whole
finding was the caller reaching `NodeCountInliningCutoff` from the `year`
body's intrinsics alone - and, further out, C1's virtual-register refusal
(VARKA-43 found it width-independent and landing on the epilogue) and the
JVM's 64KB method limit this project already tracks. Name the right limit
when measuring it, and rule the option out on a number rather than on this
prediction.

**Prediction register, before the runs.** A is expected to recover most of
the four-lane loss because the chain is the mechanism 10.4 named; B is
expected to add a little to A at 16 lanes and more at 4, where it removes
fifteen stores in sixteen; C is expected to beat both per row and to lose on
method size, and to be declined for that. If A alone recovers the four-lane
loss and B adds nothing measurable, ship A: it is the smaller change.

### 3.2 What the emitter emits

One new slot per output that keeps a per-group write - `Slots.validityAcc`,
a `long` local, allocated in the same pass that allocates `guardTmp`, only
for outputs the population test admits. `emitRootValidityOr` gains the arm
that ORs into the accumulator instead of calling the helper, and
`emitVectorLoop` gains the flush.

Whichever option wins, three properties are non-negotiable and are asserted
rather than assumed:

* **The emitted bytes for an output that does *not* keep a per-group write
  are identical to today's.** A dense value root (VARKA-45), a root the bitmap
  pass serves (VARKA-70), and a residual output must all produce byte-identical
  loop methods, so the blast radius is visible in a `codeSize` assertion.
* **The epilogue is unchanged.** Its group is not a lane width, its helper is
  the partial pair, and it runs once per batch.
* **`VarkaEmitOptions` gains one boolean**, `validityByWord`, so the A/B has
  two arms in one run and the shipped default is chosen from committed
  numbers rather than from the first shape measured. It follows
  `validityByWidth`'s pattern exactly, including staying out of `canonical()`
  when it holds its default so production shape hashes do not move.

**And two existing flags go inert under it, which is this task's likeliest
silent failure.** With the word writer on there is no helper call, so
`validityByWidth` has nothing to choose; and there is no call to place, so
`validityOrFirst` has nothing to order. That is exactly what happened once
already: VARKA-70's pass removed the per-group call for a served root, both of
VARKA-46's arms began emitting identical bytes, and the committed numbers
timed one kernel against itself for a whole regeneration before anyone
noticed (`VarkaLoopEmitterSuite`, "VARKA-76: every arm of VARKA-46's A/B still
emits two different kernels"). This task adds a third flag over the same
population and must carry the same assertion: for every arm pair it
benchmarks, the two emissions differ, asserted in the emitter suite before a
number is quoted. Where a flag is genuinely inert under `validityByWord`, say
so in its javadoc rather than leaving a benchmark to discover it.

### 3.3 Where the accumulator resets

The word index is `i >>> 6` and the bit position is `i & 63`. A group whose
`(i & 63) == 0` starts a new word and must not inherit the previous one's
bits. Under option A and B alike this is a clear-then-or at the word's first
group, which the emitter can hoist out of the general case only under option
C, where it knows statically which of the unrolled copies is first. Under A
and B it is a test per group; the plan does not assume it is free, and
section 6 measures A against today rather than against an idealised A.

### 3.4 The masked driver's dead null-state prologue

Separate mechanism, same task, per `VARKA-70.md` 9.5. The masked driver
derives `hasNulls[i]`, `srcValSeg[i]` and `srcSeg[i]` for every referenced
input, and reads them only inside `emitLaneGroup` and `emitValue` - which
only a loop or epilogue body calls. In the driver they are written and never
read. `dead[i]` is read by the all-null shortcut alone and is dead too on any
shape that emits no shortcut. VARKA-70 built the liveness pass that would
remove all of it and deliberately planned the driver with `live = null`.

The change is to plan the driver with a liveness set of its own rather than
with `null`, computed from what the driver's own body reads. It is worth its
own A/B because its population is different from the rest of this task's: it
pays per *batch*, so it is invisible on a 4096-row benchmark row and is the
whole of the remaining gap on a 64-row one (chunk 64 at 1080.1 against the
dense 1549.6 at AVX-512). Measure it on the short-batch rows VARKA-70 added,
not on the standard ones.

## 4. Tests

* `VarkaLoopEmitterSuite`: for each of 4, 8 and 16 lanes, a masked shape with
  a per-group write, run over batch lengths 1, 7, 8, 63, 64, 65, 127, 128 and
  4096, with the resulting bitmap compared bit for bit against the same shape
  under `validityByWord=false`. The lengths are the point - this is the task
  whose failure mode is a word that runs off the end or a partial word left
  unflushed, and both are length-dependent and silent.
* The byte-identity assertions of 3.2: a dense root, a pass-served root and a
  residual output emit the same `codeSize` under both arms.
* `VarkaVectorSupportTest` (or the width test beside it): the capacity ladder
  of 2.1, as a committed assertion about Arrow's allocation rather than a
  comment about it.
* `VarkaDifferentialSuite`: a masked shape from VARKA-70's leftover
  population - a filter's `Cond` root and an `IfElse` blend - over a batch
  whose length is deliberately not a multiple of 64, checked against the row
  engine. A wrong bit here is a wrong answer, not a slow one.
* The fuzz corpus at both widths, since the population is defined by a
  predicate over shapes and the fuzzer is what finds a shape the predicate
  admits by accident.

## 5. Verification

    build/sbt catalyst/Test/compile sql/Test/compile
    build/sbt 'catalyst/testOnly *Varka*' 'sql/testOnly *Varka*'
    JAVA_OPTS="-XX:MaxVectorSize=16" build/sbt 'catalyst/testOnly *Varka*'
    JAVA_OPTS="-XX:MaxVectorSize=16" build/sbt 'sql/testOnly *Varka*'
    dev/lint-java && dev/scalastyle && build/sbt catalyst/doc
    dev/varka_gate.sh
    dev/varka_precommit.sh --working-tree

Task-specific gate: with `validityByWord` off, every emitted method is
byte-identical to master's, asserted rather than eyeballed. That is what
makes the option's default the only thing the reviewer has to judge.

## 6. The measurement

**On VARKA-76's ladder's shapes, with arms of its own.** `VARKA-76.md`
10.9 committed the write-count ladder precisely so this task could measure
against the same rungs: `k` unserved `IfElse` blends over one date, `k` from
1 to 4, holding the shape family constant so only the write count moves.
Reuse the *shapes*, not the arms - 962-969 are the width-named/general helper
pair and price a different question. This task's arms are `validityByWord`
on and off over those same four rungs, which is a new pair of case ids;
enumerate the file's existing ids and take the next free block rather than
reading the ones nearby (the benchmark's own `require` has caught that
mistake twice). Run at 4, 8 and 16 lanes, four runs per width, arms adjacent
in one run, as 76 did - the same protocol, so the two tables can be read
against each other.

The number that decides the task is the **4-lane, one-write and two-write
rungs**, where VARKA-76 measured the width-named helpers 3.8 to 6.8% and 0.5
to 1.5% *behind*. If this task's writer removes the regime, that deficit
should disappear rather than shrink, and the width-named helpers should win
at 4 lanes as they do at 8 and 16. Register that as the primary prediction.

Secondary, on the committed parity file: the masked rows of the shapes VARKA-70 left with a per-group write, at both widths. And separately, on VARKA-70's
short-batch rows, the driver change of 3.4.

**The band, not a flat 3%.** `dev/varka_bench_band.py` and the committed band
files exist since VARKA-77; a rung that moves inside its band has not moved.
Compare with `dev/varka_bench_diff.py --band`.

## 7. Risks

1. **The store is wider than the buffer.** Section 2.1 is exactly this, and
   it is checked before anything is designed around it.
2. **A partial word is left unflushed.** Smaller than it looks, and the plan
   answers it rather than deferring it: a LOOP method is `emitVectorLoop`
   followed by `emitStatusReturn` and has exactly one exit, with no early
   return inside the loop - the all-null shortcut lives in the driver, which
   runs no lane groups, and the guard's status reduction is once per method
   after the back edge. So there is one flush point, between the loop's end
   and the status return, and the risk is that it is omitted rather than that
   it is hard to place. The length ladder in section 4 is what catches an
   omission, since a partial word only exists when `loopBound` is not a
   multiple of 64.
3. **The accumulator collides with the register pressure the task is trying
   to relieve.** One extra live `long` per written output, in a body that
   VARKA-50 already made visible as register-allocation-sensitive. This is a
   reason to measure option A first: it changes traffic without changing the
   loop's shape, so a regression there is attributable.
4. **The four-lane win does not appear.** 10.4 states the register-file
   finding as the leading hypothesis and not as proof - "nothing here
   measures spill traffic directly". If the ladder's 4-lane deficit survives
   this change, the mechanism was something else, and that is a finding worth
   the task even though it is not the one intended.

## 8. Sequencing

1. Section 2's admission check, as its own commit: the capacity ladder test
   and the three single-writer confirmations. If it fails, stop and record.
2. `validityByWord` and option A, with the length-ladder tests.
3. The ladder run at three widths; A against today.
4. Option B behind the same flag if A leaves something; measure. Option C
   only if B's numbers make the method-size question worth asking.
5. The driver's liveness set (3.4), measured on the short-batch rows.
6. Default chosen from the committed numbers, docs, `SKILLS.md`, the
   milestone row, this plan's outcome section.

## 9. Explicitly out of scope

The read side and every `validityBitsAt*` helper; the source bitmaps' sizing;
the epilogue's own write; VARKA-44's decision about splitting the epilogue;
VARKA-46's helper choice, which VARKA-76 declined and which this task's writer
is expected to make moot rather than revisit; and any change to what
`servedByPass` or `fillsValidityOnce` decide - this task changes how the
remaining writes are performed, never which roots perform them.

## 10. Outcome

### 10.1 Step 1: the admission check passes, on both halves

**2.1, the word.** `VarkaKernelEvaluatorSuite`, "VARKA-47: a destination
validity buffer carries whole 64-bit words, at every length". Over 1, 7, 8,
9, 63, 64, 65, 127, 128, 1000, 4095 and 4096 rows, and over all three
destination vector classes (`DateDayVector`, `IntVector`,
`IntervalYearVector`), Arrow's `allocateNew(len)` returns a validity buffer
whose `capacity()` is at least `((len + 63) / 64) * 8`. The fallback of 2.1 -
allocating the buffer explicitly at the rounded size - is not needed. What
remains is the emitter's own bound: `s.validityBytes` is `(length + 7) / 8`
and the destination segments are materialised at exactly that, so the segment
must be rounded up to a multiple of 8 for the destinations. That is the
one-line change 2.1 anticipated, and the buffer behind it is now known to
carry it.

**2.2, the single writer.** All three claims hold, and the third holds more
strongly than the plan asked:

1. The driver's step (3) writes each destination bitmap under exactly the two
   predicates that decide whether a per-group write is emitted: `setValid`
   when `fillsValidityOnce`, the bitmap pass when `servedByPass`, and `zero`
   otherwise. An output keeping the per-group write is by construction the
   `zero` case, so its bitmap is zero before any lane group runs.
2. `groupOutputs` walks the outputs once and appends each index to exactly
   one group, so the groups are a strict partition and no two loop methods
   address the same `dstValidity[o]`.
3. `servedByPass` is `!dense && analysis.served[o] != null` - a pure function
   of the analysis and the body's own `dense` flag - and the dense and masked
   kernels are emitted as two separate families, each with one driver and its
   own loop and epilogue bodies. The driver and its bodies therefore cannot
   disagree about which outputs the pass serves; it is not merely that they
   are called with the same arguments.

**And the same reading settles the population question of section 1 in the
code.** `servedByPass` returns false for every output of a *dense* body, and
`fillsValidityOnce` excludes a `Cond` root by design, so a dense filter
kernel ORs per lane group on every batch. The dense filter is in this task's
population, which is why section 4's tests cover it.

**2.3, the boundary.** Confirmed, and smaller than the plan feared: a LOOP
method is `emitVectorLoop` followed by `emitStatusReturn`, with no early
return inside the loop - the all-null shortcut is the driver's and the
guard's reduction is once per method after the back edge. There is exactly
one flush point, between the loop's end and the status return.

### 10.2 Step 2: option A, and what the ladder says

Option A shipped as `validityByWord`, default **off**, and the ladder is why.

Ten runs on an idle machine under the performance governor, pinned to the
fast CCX exactly as `dev/varka_bench_regen.sh` pins - four at 4 lanes
(`-XX:MaxVectorSize=16`), three at 8 (`=32`) and three at 16 - on VARKA-76's
own four rungs, arms adjacent in one run. Each run is 223 result rows and
took 16 minutes; the series ran 14:58 to 17:40 on 10 September 2026. The
advantage of the word writer over each of VARKA-76's two arms, as the range
across runs:

**Against the shipped arm (VARKA-46's width-named helpers):**

| writes | 4 lanes | 8 lanes | 16 lanes |
|---|---|---|---|
| 1 | +5.9 to +8.7% | -16.6 to -15.4% | -19.6 to -17.2% |
| 2 | +6.9 to +9.0% | -13.6 to -11.4% | -19.7 to -15.7% |
| 3 | +35.5 to +38.3% | +26.4 to +30.0% | +22.1 to +23.7% |
| 4 | -5.8 to -5.3% | -6.5 to -5.2% | -8.0 to -5.1% |

**Against the general pair:**

| writes | 4 lanes | 8 lanes | 16 lanes |
|---|---|---|---|
| 1 | -1.6 to +1.6% | +11.5 to +12.2% | +3.9 to +5.9% |
| 2 | +5.3 to +6.0% | +3.4 to +6.1% | -0.9 to +0.3% |
| 3 | +50.7 to +54.2% | +52.3 to +58.4% | +38.7 to +42.8% |
| 4 | +9.2 to +10.3% | +8.0 to +10.2% | +7.5 to +11.2% |

The run-to-run spread is 1 to 3 percentage points against effects of 5 to 38,
and every sign is stable across runs.

**Prediction 1 held, and it is the one that mattered.** Section 6 registered
that the 4-lane deficit at one and two writes should *disappear* rather than
shrink. VARKA-76 measured the shipped width-named helpers 3.8 to 6.8% and 0.5
to 1.5% behind the general pair there, and this series reproduces that on the
same rungs - at 4 lanes and one write the general pair is 7.4% ahead of the
shipped arm. With the word writer the inversion is gone: it beats the shipped
arm by 5.9 to 9.0% at one and two writes and lands level with the general
pair. The mechanism VARKA-76 named is therefore the mechanism. At four lanes a
validity group is half a byte, two consecutive groups read-modify-write the
same byte and serialise on it, and removing the read removes the regime.

**And the same table refuses the change as a default.** At 8 and 16 lanes,
where a group owns whole bytes and the chain never existed, the word writer
*loses* 11 to 20% at one and two writes. That is the honest reading of its
own hypothesis: where there is no serialised chain to remove, an eight-byte
store plus an accumulator, a mask, a shift and a branch is simply more work
than a one- or two-byte read-modify-write whose helper already inlines.
Production runs at the preferred width, which is 16 lanes on this machine, so
the shipped default stays off and `m4/PLAN.md`'s "one validity write
per word" is not, as written, an improvement.

**The k=3 column is a step in something else, and it is not the emitter.**
Every arm falls away sharply at three writes and both per-group arms fall
furthest, which is why the word writer's advantage there is 22 to 38% at
every width - an outlier against its own neighbours at k=2 and k=4. The first
thing to rule out was a layout change, and `VarkaLoopEmitterSuite`'s "the
write-count ladder really is one shape family" does: all four rungs emit one
masked loop method and the body grows by a steady ~130 bytes per write. So
the step is in how the JVM runs those bytes. The shape that fits is VARKA-46's
own mechanism - the caller's node count crossing C2's inlining cutoff, so
that one more OR call stops being inlined - and the word writer, which has
no call at that site to refuse, degrades smoothly instead. **Stated as the
leading hypothesis rather than as proof**, on VARKA-76's precedent: nothing
here reads `-XX:+PrintInlining`, and until something does, no rule may be
fitted across k=3 in *either* task's table.

### 10.3 The decision, and what carries forward

**VARKA-47 ships the option and keeps the default off.** The word writer is
correct - the emitter suite compares it against the per-group writer bit for
bit at three widths, four shapes, thirteen lengths through both sides of
every word boundary and three null states, and the differential runs it
through Arrow buffers on a 5000-row fixture whose second batch is fourteen
words and eight rows - and it is a 6 to 9% win at the one width where the
read-modify-write serialises. It is a 11 to 20% loss at the two widths where
it does not.

What follows is a narrower task than this row was written as, and a
better-founded one. The rule the numbers support is not keyed on the write
count, which is what VARKA-76 declined, but on a property of the bit layout:
**a validity group smaller than a byte**, which is 2 and 4 lanes and nothing
else. That is one condition, derived from the mechanism rather than fitted to
a threshold, and `widthSpecialised` already reads `analysis.lanes`. It is
recorded as milestone 5's VARKA-92 rather than shipped here, and the owner
merged it that way.

**A correction to the sentence this section first gave for that**, which was
that the rule "would turn on for no production query - the preferred width is
16 lanes - and a default that only a `MaxVectorSize` flag can reach should be
justified by a machine that runs at that width". The second half stands; the
first half is true of this laptop and false in general. `SPECIES_PREFERRED`
for an int lane is 128 bits on every NEON-only aarch64 - Apple Silicon among
them - and on x86 without AVX2, which is four lanes and exactly the regime
this task measured a 6 to 9% win in. That is not a flag-only path, and it is
the reason this project commits 128-bit companion results files at all. So
VARKA-92's value is a real target's, not a simulated width's, and the case for
turning the rule on is stronger than this section originally put it. What the
second half still asks for is confirmation on hardware that runs at that
width rather than on `-XX:MaxVectorSize=16`, which simulates the lane count
and not the store behaviour.

Option B of section 3.1 - store once per word rather than once per group - is
where the two widths that lose might be recovered, since what they are paying
for is the wider store rather than the removed read. It needs its own ladder
run and belongs with VARKA-92.

Section 3.4's driver item, the masked driver's dead null-state prologue, is
untouched by this measurement and independent of it; it also moves to VARKA-92,
where its short-batch A/B can share a run with the width rule's.
